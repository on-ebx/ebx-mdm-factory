"""Pure logic of the B2C master and matching scenario (tools/e2e/b2c_master.py): the ground truth, the predicted clusters read from the Match and Merge metadataset,
the pairwise quality measures. Python 3 standard library only, no network: tools/e2e/b2c_selftest.py runs it on canned data.

Vocabulary
  record   a SOURCE record of the master person table: a row that came from a staging row, i.e. with a sourceSystem and a sourceId. The goldens the add-on creates
           when it merges (RecordMetadata.autoCreated) have none: they are not records, they only connect the records they were built from.
  truth    {(application, source key): person index}, written by gen_b2c.py (truth_b2c.json) from the very draw that built the files.
  cluster  a set of records the add-on put together: same RecordMetadata.groupId, or one pointing (goldenId) to a golden of the other. The measure (a) "merged" takes only
           that; the measure (b) "merged + suspects" adds the pairs of the Suspected table as edges.
  pair     two records of one cluster. A pair is TRUE when both come from the same person. Precision = true pairs found / pairs found, recall = true pairs found /
           true pairs that exist among the records present in the master table.
  false merge   a cluster of the measure (a) that holds records of two different people - the worst defect of an MDM (two customers become one).
"""
import json

STATE_UNSET, STATE_GOLDEN, STATE_MERGED, STATE_PENDING, STATE_DELETED = 0, 1, 2, 3, 4
STATE_NAMES = {0: "UNSET", 1: "GOLDEN", 2: "MERGED", 3: "PENDING_APPROVAL", 4: "DELETED"}

# the initial targets of the quality measures, documented in docs/E2E-B2C.md ("Master and matching"). The floors are deliberately low: a run must report the truth,
# not hide behind a green bar - a FAIL below a floor means the matching is broken or absent, a value between floor and target is a number to look at.
FALSE_MERGES_MAX = 0
PRECISION_TARGET, PRECISION_FLOOR = 0.98, 0.5
RECALL_TARGET, RECALL_FLOOR = 0.80, 0.5


def choose2(n):
    return n * (n - 1) // 2


# --------------------------------------------------------------------------------------------------------------------- the ground truth

def load_truth(path):
    """({(app, key): person}, summary). Raises ValueError on a file that is not what gen_b2c.py writes or that holds a key twice."""
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    rows = doc.get("rows") if isinstance(doc, dict) else None
    if not isinstance(rows, list) or not rows:
        raise ValueError("truth file without rows")
    out = {}
    for r in rows:
        key = (str(r["app"]), str(r["key"]))
        if key in out:
            raise ValueError("the key %s of %s appears twice in the truth file" % (key[1], key[0]))
        out[key] = int(r["person"])
    return out, {"people": doc.get("people"), "rows": len(out), "persons": len(set(out.values()))}


def true_pairs(persons):
    """Number of true pairs among records whose person is given by persons ({record: person})."""
    counts = {}
    for p in persons.values():
        counts[p] = counts.get(p, 0) + 1
    return sum(choose2(n) for n in counts.values())


def expected_clusters_summary(truth, apps):
    """What the truth says about the files that were loaded: rows, distinct people, true pairs, and how the duplicates spread (within one source / across sources)."""
    by_person = {}
    for (app, key), person in truth.items():
        if app in apps:
            by_person.setdefault(person, []).append(app)
    within = across = 0
    for apps_of in by_person.values():
        counts = {}
        for a in apps_of:
            counts[a] = counts.get(a, 0) + 1
        total = choose2(len(apps_of))
        same = sum(choose2(n) for n in counts.values())
        within += same
        across += total - same
    return {"records": sum(len(v) for v in by_person.values()), "persons": len(by_person), "truePairs": within + across, "pairsWithinASource": within,
            "pairsAcrossSources": across, "personsInSeveralRecords": sum(1 for v in by_person.values() if len(v) > 1)}


# --------------------------------------------------------------------------------------------------------------------- the master records

def source_to_app(sources):
    """{lower-case sourceSystem: application code} from {app: ProjectSource row}; the application code itself also maps to itself (the staging may carry either)."""
    mapping = {}
    for app, row in sources.items():
        mapping[app.lower()] = app
        system = str(row.get("sourceSystem") or "").strip()
        if system:
            mapping[system.lower()] = app
    return mapping


def auto_created_ids(meta_rows):
    """Lot M11: the ids (RecordMetadata.functionalId) of the golden records the add-on created itself (autoCreated, whatever their state - an unmerged one is DELETED but still a row of
    the table). Live (2026-10-18, project E2EMASTER20261018070131): 142 of them carry the source the policy stamps on a created golden (autoCreateNewGoldenSource, here "MDM") and a
    generated source key ("FID5979509493"), so "no source" does not recognise them."""
    return set(_text(m.get("functionalId")) for m in meta_rows if _text(m.get("autoCreated")).lower() == "true" and _text(m.get("functionalId")))


def master_records(rows, mapping, truth, auto_created=()):
    """(records, unknown, goldens): records = {master id: (app, key, person)} for every row whose (sourceSystem, sourceId) is in the truth; unknown = [(id, sourceSystem,
    sourceId)] for rows that carry a source but are not in the truth; goldens = [id] of the rows that are not a person's record: the rows with no source and, since lot M11, the ids in
    `auto_created` (what the add-on created, with a source of its own or none). A golden is the target of a merge: its cluster is its MERGED members (labels), it is never counted as a record."""
    records, unknown, goldens = {}, [], []
    auto = set(_text(x) for x in auto_created)
    for row in rows:
        rid = _text(row.get("id"))
        system, source = _text(row.get("sourceSystem")), _text(row.get("sourceId"))
        if rid in auto or (not system and not source):
            goldens.append(rid)
            continue
        app = mapping.get(system.lower())
        person = truth.get((app, source)) if app else None
        if person is None:
            unknown.append((rid, system, source))
        else:
            records[rid] = (app, source, person)
    return records, unknown, goldens


def _text(value):
    """The bare text of a Data Services / MCP shaped field. A foreign key comes back as a dict - RecordMetadata.goldenId is one (a FK to the business table):
    {'content': '536', 'label': ..., 'details': url, 'selector': url} - while functionalId is a plain string, so the dict is unwrapped like e2e_lib.scalar does ("content",
    repeatedly, else "key"); master_lib stays standalone, hence the copy. None is "", an integral float is its integer text."""
    seen = 0
    while isinstance(value, dict) and seen < 6:
        if "content" in value:
            value = value["content"]
        elif "key" in value:
            value = value["key"]
        else:
            break
        seen += 1
    if value is None:
        return ""
    if isinstance(value, float) and value == int(value):
        return str(int(value))
    return str(value).strip()


# --------------------------------------------------------------------------------------------------------------------- clusters

class UnionFind:
    def __init__(self):
        self.parent = {}

    def find(self, x):
        self.parent.setdefault(x, x)
        root = x
        while self.parent[root] != root:
            root = self.parent[root]
        while self.parent[x] != root:
            self.parent[x], x = root, self.parent[x]
        return root

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[max(ra, rb)] = min(ra, rb)


def state_of(row):
    """The ordinal of RecordMetadata.state, whichever shape the answer has (2, "2", "MERGED")."""
    value = _text(row.get("state"))
    if value.isdigit():
        return int(value)
    for number, name in STATE_NAMES.items():
        if value.upper() == name:
            return number
    return STATE_UNSET


def labels(meta_rows, record_ids, suspect_pairs=()):
    """{record id: cluster label} for every record in record_ids. A record without metadata is alone. DELETED rows (an unmerged golden) connect nothing.
    Edges: same groupId; a record to the golden it points to (goldenId); each suspect pair (only when both ends exist and are not DELETED)."""
    uf = UnionFind()
    alive = set()
    for m in meta_rows:
        if state_of(m) == STATE_DELETED:
            continue
        rid = _text(m.get("functionalId"))
        if not rid:
            continue
        alive.add(rid)
        group, golden = _text(m.get("groupId")), _text(m.get("goldenId"))
        if group:
            uf.union("r:" + rid, "g:" + group)
        if golden:
            uf.union("r:" + rid, "r:" + golden)
    for a, b in suspect_pairs:
        a, b = _text(a), _text(b)
        if a in alive and b in alive:
            uf.union("r:" + a, "r:" + b)
    return dict((rid, uf.find("r:" + rid)) for rid in record_ids)


def metrics(label_of, person_of):
    """Pairwise quality of a clustering. label_of / person_of: {record: cluster label / person}. Counting is by cells (cluster x person), never by enumerating pairs."""
    cells, sizes = {}, {}
    for rid, label in label_of.items():
        person = person_of[rid]
        cells[(label, person)] = cells.get((label, person), 0) + 1
        sizes[label] = sizes.get(label, 0) + 1
    found = sum(choose2(n) for n in sizes.values())
    right = sum(choose2(n) for n in cells.values())
    truth = true_pairs(dict((rid, person_of[rid]) for rid in label_of))
    people_in = {}
    for (label, person), n in cells.items():
        people_in.setdefault(label, set()).add(person)
    false_clusters = sorted((label for label, ps in people_in.items() if len(ps) > 1), key=str)
    precision = right / found if found else None
    recall = right / truth if truth else None
    f1 = (2 * precision * recall / (precision + recall)) if precision is not None and recall is not None and precision + recall > 0 else (0.0 if found and truth else None)
    persons = len(set(person_of[r] for r in label_of))
    mixed_sizes = [sizes[label] for label in false_clusters]
    return {"records": len(label_of), "clusters": len(sizes), "singletons": sum(1 for n in sizes.values() if n == 1), "personsPresent": persons,
            "pairsFound": found, "pairsRight": right, "pairsTrue": truth, "precision": precision, "recall": recall, "f1": f1,
            "falseMergeClusters": len(false_clusters), "falsePairs": found - right, "falseClusterLabels": false_clusters,
            "largestMixedCluster": max(mixed_sizes) if mixed_sizes else 0, "largestCluster": max(sizes.values()) if sizes else 0}


def quality(records, meta_rows, suspect_pairs):
    """{"merged": metrics (a), "withSuspects": metrics (b), "autoCreatedGoldens": n, "states": {name: n}} for the records of the master person table."""
    person_of = dict((rid, rec[2]) for rid, rec in records.items())
    a = labels(meta_rows, records.keys())
    b = labels(meta_rows, records.keys(), suspect_pairs)
    states = {}
    for m in meta_rows:
        name = STATE_NAMES.get(state_of(m), "UNSET")
        states[name] = states.get(name, 0) + 1
    goldens = sum(1 for m in meta_rows if state_of(m) == STATE_GOLDEN and _text(m.get("autoCreated")).lower() == "true")
    # records of the master table that the add-on merged into a golden (state MERGED)
    merged_records = sum(1 for m in meta_rows if state_of(m) == STATE_MERGED and _text(m.get("functionalId")) in person_of)
    return {"merged": metrics(a, person_of), "withSuspects": metrics(b, person_of), "autoCreatedGoldens": goldens, "mergedRecords": merged_records, "states": states,
            "labelsMerged": a, "labelsWithSuspects": b}


def false_merge_examples(records, label_of, bad_labels, limit=10):
    """A few records of the clusters that mix people, for the report: [{cluster, records:[{id, app, key, person}]}]."""
    out = []
    for label in bad_labels[:limit]:
        members = sorted(rid for rid, lab in label_of.items() if lab == label)
        out.append({"cluster": str(label), "records": [{"id": rid, "app": records[rid][0], "key": records[rid][1], "person": records[rid][2]} for rid in members[:12]]})
    return out


# technical columns of a master row: never "equal fields" worth printing
def _technical(column):
    low = column.lower()
    return low in ("id", "sourcesystem", "sourceid") or low.startswith("_") or low.startswith("linked_field") or low.startswith("$") or low.startswith("mdmf")


def equal_fields(row_a, row_b, limit=12):
    """{column: value} for the business columns where the two master rows hold the same non-empty value (what made the add-on put them together), at most `limit`, a value cut at 40 characters."""
    out = {}
    if not row_a or not row_b:
        return out
    for column in sorted(row_a):
        if _technical(column) or column not in row_b:
            continue
        va, vb = _text(row_a.get(column)), _text(row_b.get(column))
        if va and va == vb:
            out[column] = va if len(va) <= 40 else va[:40] + "..."
            if len(out) >= limit:
                break
    return out


def false_pairs(records, meta_rows, suspect_pairs, master_by_id, label_merged, limit=20):
    """(pairs, total): the pairs found together that come from two different people, for a person to characterise from the log. Two kinds, the merged ones first (they are the defect that
    matters): kind "merged" = two records of one merged cluster, kind "suspect" = the two ends of a Suspected row (a chain of suspects is made of those). Each pair carries both records
    (id, application, key, person, sourceSystem, sourceId) and the business columns they hold equal. `total` counts every false pair of both kinds, `pairs` is cut at `limit`."""
    alive = set(_text(m.get("functionalId")) for m in meta_rows if state_of(m) != STATE_DELETED)

    def side(rid):
        row = master_by_id.get(rid) or {}
        app, key, person = records[rid]
        return {"id": rid, "app": app, "key": key, "person": person, "sourceSystem": _text(row.get("sourceSystem")), "sourceId": _text(row.get("sourceId"))}

    def pair(kind, a, b):
        return {"kind": kind, "a": side(a), "b": side(b), "equalFields": equal_fields(master_by_id.get(a), master_by_id.get(b))}
    by_label = {}
    for rid, label in label_merged.items():
        by_label.setdefault(label, []).append(rid)
    out, total, seen = [], 0, set()
    for label in sorted(by_label, key=str):
        members = sorted(by_label[label], key=lambda r: (len(r), r))
        for i, a in enumerate(members):
            for b in members[i + 1:]:
                if records[a][2] != records[b][2]:
                    total += 1
                    seen.add(frozenset((a, b)))
                    if len(out) < limit:
                        out.append(pair("merged", a, b))
    for a, b in suspect_pairs:
        a, b = _text(a), _text(b)
        if a in records and b in records and a in alive and b in alive and records[a][2] != records[b][2] and frozenset((a, b)) not in seen:
            total += 1
            seen.add(frozenset((a, b)))
            if len(out) < limit:
                out.append(pair("suspect", a, b))
    return out, total


def describe_false_pair(p):
    """One printable line for a false pair: both records, then the fields they share."""
    def one(s):
        return "%s/%s (id %s, person %s)" % (s["sourceSystem"] or s["app"], s["sourceId"] or s["key"], s["id"], s["person"])
    shared = ", ".join("%s=%s" % kv for kv in sorted(p["equalFields"].items())) or "no business field in common"
    return "%s  %s  <->  %s | equal: %s" % (p["kind"], one(p["a"]), one(p["b"]), shared)


# --------------------------------------------------------------------------------------------------------------------- the golden record check

def sample_persons(records, count=3):
    """The `count` people to eyeball: the ones present in the most applications, then with the most records (ties: the smaller person index) - deterministic."""
    by_person = {}
    for rid, (app, key, person) in records.items():
        by_person.setdefault(person, []).append((app, rid))
    ranked = sorted(by_person.items(), key=lambda kv: (-len(set(a for a, _r in kv[1])), -len(kv[1]), kv[0]))
    return [(person, sorted(members, key=lambda m: (m[0], int(m[1]) if m[1].isdigit() else 0))) for person, members in ranked[:count]]


ROLE_PATTERNS = (("first name", ("first", "prenom")), ("last name", ("last", "nom")), ("email", ("email", "mail")), ("phone", ("mobile", "phone", "tel")), ("birth date", ("birth", "naissance")))


def pick_fields(columns):
    """{role: [master columns]} among the columns of the master person table: the business fields to print for the survivorship check (matching keys and technical fields left out)."""
    out = {}
    for role, needles in ROLE_PATTERNS:
        found = []
        for c in columns:
            low = c.lower()
            if low.endswith("key") or low.startswith("_") or low in ("id", "sourcesystem", "sourceid"):
                continue
            if any(n in low for n in needles) and not (role == "last name" and "first" in low):
                found.append(c)
        if found:
            out[role] = found
    return out


def golden_of(member_ids, meta_by_id, label_of):
    """The golden record of a cluster: the master id of the auto-created GOLDEN row connected to these records (their goldenId), or None. A record that was never merged has none."""
    ids = set()
    for rid in member_ids:
        m = meta_by_id.get(rid)
        if m is None:
            continue
        golden = _text(m.get("goldenId"))
        if golden:
            ids.add(golden)
    live = [g for g in sorted(ids) if state_of(meta_by_id.get(g, {})) != STATE_DELETED]
    return live[0] if live else None


def describe_row(row, fields):
    """One printable line: 'role=value role=value' for the roles with a value (every column of the role shown)."""
    parts = []
    for role, columns in fields.items():
        for c in columns:
            v = _text(row.get(c)) if row else ""
            parts.append("%s=%s" % (c, v if v else "-"))
    return " ".join(parts)


# --------------------------------------------------------------------------------------------------------------------- the words of a measure

def fmt(value):
    return "n/a" if value is None else "%.3f" % value


def judge_precision(value, pairs_found):
    """('PASS'|'FAIL'|'SKIP', text): precision is undefined when nothing was found together; below the floor is a FAIL, below the target an honest number."""
    if value is None:
        return "SKIP", "no pair found together"
    if value < PRECISION_FLOOR:
        return "FAIL", "below the floor %.2f" % PRECISION_FLOOR
    return "PASS", "target %.2f%s" % (PRECISION_TARGET, "" if value >= PRECISION_TARGET else ", below the target")


def judge_recall(value):
    if value is None:
        return "SKIP", "no true pair among the records"
    if value < RECALL_FLOOR:
        return "FAIL", "below the floor %.2f" % RECALL_FLOOR
    return "PASS", "target %.2f%s" % (RECALL_TARGET, "" if value >= RECALL_TARGET else ", below the target")


# --------------------------------------------------------------------------------------------------------------------- the plan and the add-on's configuration

def plan_leaves(requirements):
    """{'MATCH': n, 'SUSPECTED': n, 'NOT_MATCHED': n}: the leaves of the decision tree stored in MatchPlan.requirements (the flat shape {decisionTree:{root,nodes:[{id,onTrue,onFalse}]}},
    or the older cascade {tiers:[{outcome}]}). A leaf is a target of onTrue / onFalse that is one of the three tokens. {} when the text is not a plan. Written 2026-10-17 after the first live
    plan had no MATCH leaf at all: every path ended at a data steward."""
    try:
        doc = json.loads(requirements) if isinstance(requirements, str) else requirements
    except ValueError:
        return {}
    if not isinstance(doc, dict):
        return {}
    counts = {"MATCH": 0, "SUSPECTED": 0, "NOT_MATCHED": 0}
    tree = doc.get("decisionTree")
    nodes = tree.get("nodes") if isinstance(tree, dict) else None
    if isinstance(nodes, list):
        for n in nodes:
            if not isinstance(n, dict):
                continue
            for side in ("onTrue", "onFalse"):
                target = str(n.get(side) or "").upper()
                if target in counts:
                    counts[target] += 1
        return counts
    tiers = doc.get("tiers")
    if isinstance(tiers, list):
        for t in tiers:
            outcome = str(t.get("outcome") or "").upper() if isinstance(t, dict) else ""
            if outcome in ("MATCH", "MATCHED"):
                counts["MATCH"] += 1
            elif outcome in ("SUSPECT", "SUSPECTED"):
                counts["SUSPECTED"] += 1
        return counts
    return {}


def policy_codes(config):
    """The codes of the matching policies in a get_matching_config answer ('data' of the result): processCode / code / name of each entry of matchingPolicies."""
    data = config.get("data") if isinstance(config, dict) and "data" in config else config
    policies = data.get("matchingPolicies") if isinstance(data, dict) else None
    out = []
    for p in policies if isinstance(policies, list) else []:
        if isinstance(p, dict):
            out.append(str(p.get("processCode") or p.get("code") or p.get("name") or "?"))
    return out


def preview_codes(codes):
    return [c for c in codes if c.endswith("__preview")]


def mcp_text_result(raw):
    """The JSON document a tools/call answer carries (result.content[0].text), from a plain JSON body or an event-stream one; (doc, error text). doc is None when unreadable."""
    import re
    m = re.search(r"\{.*\}", raw or "", re.S)
    if not m:
        return None, "no JSON in the answer"
    try:
        answer = json.loads(m.group(0))
    except ValueError:
        return None, "unreadable answer"
    if isinstance(answer.get("error"), dict):
        return None, str(answer["error"].get("message") or answer["error"])[:300]
    result = answer.get("result") or {}
    content = result.get("content") or []
    text_part = content[0].get("text", "") if content and isinstance(content[0], dict) else ""
    try:
        doc = json.loads(text_part)
    except ValueError:
        return None, ("tool error: " if result.get("isError") else "") + text_part[:300]
    return doc, ""


# --------------------------------------------------------------------------------------------------------------------- the distribution step (lot S1, step 10)
#
# What a distribution target sends: the GOLDEN records of each master table, as full JSON messages keyed by the record id, one topic per table (Kafka). The functions below are pure: they
# take the messages a topic reader returned ([{key, value}]) and the master rows / metadataset rows the scenario read, and say what is wrong. No Kafka, no network.

import collections
import re

#: the names of the fields a message never carries as a column of the table (the Factory's mapping sends the business fields, id, sourceSystem and sourceId, plus - since lot S2 - the
#: technical `_change`). Every field whose name starts with `_` is technical (lot S4): `_change`, `_loadId`, `_receivedAt`, whatever a later build adds.
MESSAGE_SKIP_FIELDS = ("_loadId", "_receivedAt", "_change")


def is_technical_field(name):
    return _text(name).startswith("_")
#: how many wrong items a check lists (the task asks for up to 20)
LISTING_LIMIT = 20
_DAY = re.compile(r"^\d{4}-\d{2}-\d{2}")


def num_key(value):
    """Sort key for record ids written as text: numeric ids in numeric order, anything else after them."""
    value = _text(value)
    return (0, int(value), "") if value.isdigit() else (1, 0, value)


def listing(items, limit=LISTING_LIMIT):
    """'a, b, c' of at most `limit` items, then '(+N more)'."""
    items = list(items)
    shown = ", ".join(_text(i) for i in items[:limit])
    return shown + (" (+%d more)" % (len(items) - limit) if len(items) > limit else "")


def table_name(table_path):
    return _text(table_path).rsplit("/", 1)[-1]


def expected_topic(project_code, table_path):
    """The destination the Factory proposes for a table when the connection's own `topic` property is the lower-cased project code (TableDestinationDefaults.proposeDestination)."""
    return "%s.%s" % (project_code.lower(), table_name(table_path).lower())


def parse_pairs(stored):
    """{table path: value} from 'path=value,path=value' (the format of DistributionTarget.connectorsMappingIds and tableDestinations, TargetMappingList)."""
    out = {}
    for part in _text(stored).split(","):
        part = part.strip()
        eq = part.find("=")
        if 0 < eq < len(part) - 1:
            out[part[:eq].strip()] = part[eq + 1:].strip()
    return out


def publication_sets(master_ids, meta_rows):
    """Which master records a target built on `mergedRecords=exclude` must send: {golden, merged, deleted, pending, autoCreated} (sets of text ids). A record with no metadata row is
    golden (a table nobody matched); MERGED and DELETED are excluded by the mapping; PENDING_APPROVAL (state 3) is neither required nor forbidden (it depends on the target's includePending)."""
    state = {}
    auto = set()
    for m in meta_rows:
        rid = _text(m.get("functionalId"))
        if rid:
            state[rid] = state_of(m)
            if _text(m.get("autoCreated")).lower() == "true":
                auto.add(rid)
    sets = {"golden": set(), "merged": set(), "deleted": set(), "pending": set(), "autoCreated": auto}
    for rid in (_text(i) for i in master_ids):
        s = state.get(rid, STATE_GOLDEN)
        if s == STATE_MERGED:
            sets["merged"].add(rid)
        elif s == STATE_DELETED:
            sets["deleted"].add(rid)
        elif s == STATE_PENDING:
            sets["pending"].add(rid)
        else:
            sets["golden"].add(rid)
    return sets


def message_doc(value):
    """The JSON object a message value carries, or None (not JSON, or not an object)."""
    try:
        doc = json.loads(value) if isinstance(value, str) else value
    except ValueError:
        return None
    return doc if isinstance(doc, dict) else None


KIND_DATA, KIND_RETIRE, KIND_DELETE = "DATA", "RETIRE", "DELETE"


def change_of(doc):
    """The `_change` of a message in capitals ('UPSERT', 'RETIRE', 'DELETE'), or '' when the message carries none (a build before lot S2)."""
    return _text(doc.get("_change")).upper() if isinstance(doc, dict) else ""


def message_kind(doc):
    """What a message of a table's topic is (lot S2's payload convention, docs/DISTRIBUTION.md 'One payload convention'): `_change` = UPSERT -> a record (KIND_DATA), RETIRE -> a retire notice
    {_change, id, retired: true, retiredTo?, reason?, mergeId?, at}, DELETE -> a delete notice {_change, id, deleted: true, at}. A message with no `_change` (a build before the lot) is
    a retire notice when it says `retired: true`, a record otherwise. Anything else in `_change` (CREATE, MODIFY...) is a record."""
    change = change_of(doc)
    if change == "RETIRE":
        return KIND_RETIRE
    if change == "DELETE":
        return KIND_DELETE
    if not change and isinstance(doc, dict) and _text(doc.get("retired")).lower() == "true":
        return KIND_RETIRE
    return KIND_DATA


def is_retire_notice(doc):
    return message_kind(doc) == KIND_RETIRE


def is_delete_notice(doc):
    return message_kind(doc) == KIND_DELETE


def audit_publication(messages, sets):
    """What the messages of ONE table's topic say about the golden-only rule. `messages` = [{key, value}]; `sets` = publication_sets of that table. Returns
    {messages, data (count), docs, notices [(key, doc)] (retire), deletes [(key, doc)], changes {_change: count}, notJson, published (count per key), missing, duplicates, mergedPublished,
    deletedPublished, unknown, keyMismatch}; the lists hold text ids, sorted. Only RECORD messages (`_change` UPSERT, or none) count for the golden-only rule; retire and delete notices are
    counted apart (lot S4: a notice about a merged member is the point of the notice, never a "merged member published")."""
    data, notices, deletes, bad = [], [], [], []
    changes = collections.Counter()
    for m in messages:
        doc = message_doc(m.get("value"))
        if doc is None:
            bad.append(m)
            continue
        changes[change_of(doc) or "(none)"] += 1
        kind = message_kind(doc)
        if kind == KIND_RETIRE:
            notices.append((_text(m.get("key")), doc))
        elif kind == KIND_DELETE:
            deletes.append((_text(m.get("key")), doc))
        else:
            data.append((_text(m.get("key")), doc))
    counts = collections.Counter(key for key, _doc in data)
    everything = sets["golden"] | sets["merged"] | sets["deleted"] | sets["pending"]
    return {"messages": len(messages), "data": len(data), "docs": data, "notices": notices, "deletes": deletes, "changes": dict(changes), "notJson": len(bad), "published": counts,
            "missing": sorted((k for k in sets["golden"] if k not in counts), key=num_key),
            "duplicates": sorted((k for k, n in counts.items() if n > 1), key=num_key),
            "mergedPublished": sorted((k for k in counts if k in sets["merged"]), key=num_key),
            "deletedPublished": sorted((k for k in counts if k in sets["deleted"]), key=num_key),
            "unknown": sorted((k for k in counts if k not in everything), key=num_key),
            "keyMismatch": [(key, _text(doc.get("id"))) for key, doc in data if _text(doc.get("id")) != key]}


def live_only_violations(messages, sets):
    """After the real-time write (lot S4, regression guard for lot S2's live-only sends): the record messages of `messages` whose key is a record Match and Merge holds as MERGED or
    DELETED NOW. ([merged keys], [deleted keys]), sorted, a key once. A retire or delete notice is not a record message, so it never counts."""
    a = audit_publication(messages, sets)
    return a["mergedPublished"], a["deletedPublished"]


def publication_sets_from_rows(rows):
    """The same sets as publication_sets, read from the Linked_Field_State column the add-on adds to the business table itself (a member table has no metadataset of its own that the
    scenario reads): {golden, merged, deleted, pending, autoCreated (empty)} of text ids. A row with no state is golden."""
    sets = {"golden": set(), "merged": set(), "deleted": set(), "pending": set(), "autoCreated": set()}
    for r in rows:
        rid = _text(r.get("id"))
        s = state_of({"state": r.get("Linked_Field_State")})
        if s == STATE_MERGED:
            sets["merged"].add(rid)
        elif s == STATE_DELETED:
            sets["deleted"].add(rid)
        elif s == STATE_PENDING:
            sets["pending"].add(rid)
        else:
            sets["golden"].add(rid)
    return sets


def has_linked_state(rows):
    return bool(rows) and "Linked_Field_State" in rows[0]


def member_table_figure(rows, message_count):
    """(line, explained) for the INFO line of a table other than the person table: the rows by Match and Merge state against the messages. `explained` is True when the rows carry
    Linked_Field_State and the messages equal the GOLDEN rows (plus PENDING_APPROVAL ones: a target may include them) - the cascade merge makes golden copies of a member table and the
    originals go MERGED, so 'more rows than messages' is the expected shape. None when the table has no state column."""
    if not has_linked_state(rows):
        return None, False
    sets = publication_sets_from_rows(rows)
    golden, merged, deleted, pending = len(sets["golden"]), len(sets["merged"]), len(sets["deleted"]), len(sets["pending"])
    explained = message_count in (golden, golden + pending)
    line = "%d row(s) by Linked_Field_State: %d GOLDEN, %d MERGED, %d DELETED, %d PENDING_APPROVAL; %d message(s)" % (len(rows), golden, merged, deleted, pending, message_count)
    return line, explained


def values_equal(message_value, master_value):
    """Whether a value of a message and the same field of the master row are the same value: empty = empty, numbers by value, booleans by word, dates by their day (a message may carry
    the time or not), anything else as trimmed text."""
    a, b = _text(message_value), _text(master_value)
    if a == b:
        return True
    if isinstance(message_value, bool) or isinstance(master_value, bool):
        return a.lower() == b.lower()
    try:
        return float(a) == float(b)
    except ValueError:
        pass
    return bool(_DAY.match(a) and _DAY.match(b) and a[:10] == b[:10])


def compare_message_to_row(doc, row, skip=MESSAGE_SKIP_FIELDS):
    """[(field, value in the message, value in the master row)] for every field the message carries that the master row also has and where they differ. A message with none of the row's
    fields in common is reported as [("*", ...)]: an empty message is never a match."""
    common = [f for f in doc if f in row and f not in skip and not is_technical_field(f)]
    if not common:
        return [("*", "no field in common with the master row", "")]
    return [(f, doc.get(f), row.get(f)) for f in common if not values_equal(doc.get(f), row.get(f))]


def foreign_fields(docs, columns):
    """The fields that appear in messages of a table's topic and are not columns of that table: the sign of another table's records on the same topic. {field: how many messages}.
    A field whose name starts with `_` is technical (`_change` of lot S2, `_loadId`...) and is never foreign (lot S4)."""
    cols = set(columns)
    out = collections.Counter()
    for doc in docs:
        for f in doc:
            if f not in cols and f not in MESSAGE_SKIP_FIELDS and not is_technical_field(f):
                out[f] += 1
    return dict(out)


def new_messages(baseline, current):
    """The messages of `current` that are not in `baseline`: the multiset difference of (key, value), so the order the consumer returns them in does not matter and a message that
    arrives twice counts twice."""
    before = collections.Counter((m.get("key"), m.get("value")) for m in baseline)
    out = []
    for m in current:
        k = (m.get("key"), m.get("value"))
        if before.get(k, 0) > 0:
            before[k] -= 1
        else:
            out.append(m)
    return out


_FREE_FIELD_PREFERRED = ("comment", "note", "civil", "title", "tier", "level", "label", "city", "status")
_FREE_FIELD_NAMES = ("lastname", "firstname", "name")
_FREE_FIELD_AVOID = ("mail", "phone", "mobile", "tel", "birth", "date", "key", "source", "linked", "state", "country", "group", "golden", "token", "zip", "postal")


def free_text_fields(row):
    """[(field, new value)] - the string fields of a master row that can be changed for the real-time test without touching an identifier, a contact channel or a matching key, best
    first (comment / note / civility... before names). The new value keeps the length: the last three characters become 'E2E' ('e2e' when they already are). Only fields whose
    current value is a string of at least 4 characters qualify (so a maxLength cannot refuse the write)."""
    scored = []
    for field, value in row.items():
        low = field.lower()
        if not isinstance(value, str) or len(value) < 4 or low == "id" or low.endswith("id") or low.startswith("_"):
            continue
        if any(a in low for a in _FREE_FIELD_AVOID):
            continue
        if any(p in low for p in _FREE_FIELD_PREFERRED):
            score = 0
        elif low in _FREE_FIELD_NAMES or any(low.endswith(n) for n in _FREE_FIELD_NAMES):
            score = 1
        else:
            score = 2
        marker = "e2e" if value.endswith("E2E") else "E2E"
        scored.append((score, field, value[:-3] + marker))
    scored.sort(key=lambda t: (t[0], t[1]))
    return [(f, v) for _s, f, v in scored]


def pick_realtime_record(rows, sets, meta_rows, suspect_pairs):
    """The golden record to modify for the real-time test: a source record (not created by the add-on), alone (no suspect of anybody, not an end of any suspect pair), the smallest id.
    Falls back to any golden record when every one is in a pair. None when there is no golden record."""
    ends = set()
    for a, b in suspect_pairs:
        ends.add(_text(a))
        ends.add(_text(b))
    flagged = set(_text(m.get("functionalId")) for m in meta_rows if _text(m.get("hasSuspects")).lower() == "true")
    by_id = dict((_text(r.get("id")), r) for r in rows)
    order = sorted((i for i in sets["golden"] if i in by_id), key=num_key)
    for rid in order:
        if rid not in sets["autoCreated"] and rid not in ends and rid not in flagged:
            return by_id[rid]
    for rid in order:
        if rid not in sets["autoCreated"]:
            return by_id[rid]
    return by_id[order[0]] if order else None


def pick_suspect_pair(suspect_pairs, sets, meta_rows, avoid=()):
    """(source id, target id, target groupId) of the suspect pair to accept for the retire-gap test: both ends golden, not created by the add-on, neither one in `avoid`, smallest ids
    first. None when there is none."""
    meta = dict((_text(m.get("functionalId")), m) for m in meta_rows)
    avoid = set(_text(a) for a in avoid)
    for source, target in sorted(((_text(a), _text(b)) for a, b in suspect_pairs), key=lambda p: (num_key(p[1]), num_key(p[0]))):
        if source == target or source in avoid or target in avoid:
            continue
        if source not in sets["golden"] or target not in sets["golden"] or source in sets["autoCreated"] or target in sets["autoCreated"]:
            continue
        group = _text((meta.get(target) or {}).get("groupId"))
        if group:
            return source, target, group
    return None


def group_number(group):
    """The groupId as the number the add-on's request wants (it may arrive as '1834926167335937', as '1.834926167335937E15' or as a float)."""
    try:
        return int(_text(group))
    except ValueError:
        return int(float(_text(group)))


def merge_outcome(meta_rows, ids):
    """After an accept: ({id: state number}, golden id or None). The golden is the goldenId the members agree on once they are all MERGED; None before that."""
    meta = dict((_text(m.get("functionalId")), m) for m in meta_rows)
    states = dict((i, state_of(meta[i])) for i in ids if i in meta)
    if len(states) == len(ids) and all(s == STATE_MERGED for s in states.values()):
        goldens = set(_text(meta[i].get("goldenId")) for i in ids)
        goldens.discard("")
        if len(goldens) == 1:
            return states, goldens.pop()
    return states, None


# --------------------------------------------------------------------------------------------------------------------- the unmerge test (lot S4)

def missed_true_pairs(records, meta_rows, suspect_pairs):
    """The people (ground truth) who still have 2+ SOURCE records in state GOLDEN after the run: [(person, [record ids], [ids that are an end of a suspect pair])], smallest person first.
    `records` = {master id: (app, key, person)} from master_records (add-on goldens are not in it). A record with no metadata row is golden. These are true pairs the run did NOT merge:
    the add-on's REST can merge them only when they are suspects of one group (acceptSuspects); a pair with no link has no REST route."""
    state = dict((_text(m.get("functionalId")), state_of(m)) for m in meta_rows)
    ends = set()
    for a, b in suspect_pairs:
        ends.add(_text(a))
        ends.add(_text(b))
    by_person = {}
    for rid, rec in records.items():
        if state.get(rid, STATE_GOLDEN) == STATE_GOLDEN:
            by_person.setdefault(rec[2], []).append(rid)
    out = []
    for person in sorted(by_person):
        ids = sorted(by_person[person], key=num_key)
        if len(ids) >= 2:
            out.append((person, ids, [i for i in ids if i in ends]))
    return out


def pick_unmerge_golden(meta_rows, avoid=()):
    """(golden id, [member ids]) of the merged group to UNMERGE for the retire test: a golden the add-on created (autoCreated), state GOLDEN, with MERGED members pointing to it (goldenId).
    The smallest group first (fewest members, at least one), then the smallest golden id; a golden in `avoid` is skipped. None when there is none."""
    members = {}
    for m in meta_rows:
        if state_of(m) == STATE_MERGED and _text(m.get("goldenId")):
            members.setdefault(_text(m.get("goldenId")), []).append(_text(m.get("functionalId")))
    avoid = set(_text(a) for a in avoid)
    best = None
    for m in meta_rows:
        gid = _text(m.get("functionalId"))
        if gid in avoid or gid not in members or state_of(m) != STATE_GOLDEN or _text(m.get("autoCreated")).lower() != "true":
            continue
        key = (len(members[gid]), num_key(gid))
        if best is None or key < best[0]:
            best = (key, gid)
    if best is None:
        return None
    return best[1], sorted(members[best[1]], key=num_key)


def unmerge_paths(master_space, dataset, table, record_id):
    """The URLs to try, in order, for DELETE /rest/v1/mergingProcesses/{dataspace}/{dataset}/{pathSegments} (the add-on's `unMerge`, read by javap in MergingProcessesController: the path
    segments after the dataset are the table path then, LAST, the record's primary key; the table path is the segments joined with '/'). The dataspace is the DataSpace key with its B prefix
    (HomeKey.parse), then without; the table path 'root/<Table>' (relative, as the controller joins it) then '/root/<Table>' (an empty first segment, the double slash). Never seen live
    before lot S4: the scenario tries them in this order and reports which one the add-on accepted."""
    import urllib.parse
    base = "/ebx-addon-mame/rest/v1/mergingProcesses"
    q = lambda x: urllib.parse.quote(_text(x), safe="")
    out = []
    for space in ("B" + master_space, master_space):
        out.append("%s/%s/%s/root/%s/%s" % (base, q(space), q(dataset), q(table), q(record_id)))
    out.append("%s/%s/%s//root/%s/%s" % (base, q("B" + master_space), q(dataset), q(table), q(record_id)))
    return out
