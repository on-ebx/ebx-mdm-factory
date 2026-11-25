"""Pure logic and plumbing of the B2C end-to-end scenario (lot H2). Python 3 standard library only.

Nothing in the first half of this file touches the network: the assertion logic (what a check proposes to reject, whether a pipeline drops a leading
'+', which entity is the person entity, the E.164 fill rate...) works on plain dicts so tools/e2e/selftest.py can run it on canned data. The second
half is the small amount of plumbing (poll with a bounded wait, report table) the scenario shares.

Data Services answers are normalised first by scalar() / as_list(): a foreign key, an enumeration or a multi-valued field may arrive as a bare value, as
{"content": v} or as {"key": v} depending on the route, and the scenario must not care.
"""
import json
import os
import re
import socket
import subprocess
import time
import unicodedata

# --------------------------------------------------------------------------------------------------------------------- the data of the test

EXPECTED_ROWS = {"ECOM": 160, "STORES": 234, "LOYALTY": 140}
EXPECTED_KEYS = {"ECOM": "customerId", "STORES": "NUM_TICKET", "LOYALTY": "card_number"}
# the key of this application is CHOSEN by the scenario (as a person would), the others are the suggestion validated as it stands when it is already the right one
EXPLICIT_KEY = {"STORES"}
EXPECTED_BIRTH_DATES = 300      # 160 web + 140 loyalty (the tickets carry none)
EXPECTED_PERSON_ROWS = 534      # one staging row per source row: 160 + 234 + 140
E164_MIN_RATE = 0.95
NAME_KEY_MIN_RATE = 0.95

# columns by role, compared lower-case
BIRTH_COLUMNS = {"birthdate", "date_naissance", "birth"}
PHONE_COLUMNS = {"mobile", "telephone", "phone"}
NAME_COLUMNS = {"firstname", "lastname", "nom", "prenom", "first_name", "last_name"}

TERMINAL_JOB = ("DONE", "DONE_WITH_FAILURES", "FAILED", "CANCELLED", "INTERRUPTED")
GOOD_JOB = ("DONE",)


# --------------------------------------------------------------------------------------------------------------------- normalising Data Services values

def scalar(value):
    """The bare value of a Data Services / MCP shaped field: {"content": v}, {"key": v} or v itself; lists are not flattened here."""
    seen = 0
    while isinstance(value, dict) and seen < 6:
        if "content" in value:
            value = value["content"]
        elif "key" in value:
            value = value["key"]
        else:
            return value
        seen += 1
    return value


def text(value):
    value = scalar(value)
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def as_list(value):
    value = scalar(value) if isinstance(value, dict) else value
    if value is None or value == "":
        return []
    if isinstance(value, list):
        return [text(v) for v in value if text(v) != ""]
    return [text(value)]


def truthy(value):
    return text(value).lower() == "true"


def to_int(value, default=0):
    try:
        return int(float(text(value)))
    except ValueError:
        return default


# --------------------------------------------------------------------------------------------------------------------- reading the proposal

# the two attribute findings of staging.ProposalChecks (bundle stagingProposal.check.*, English and French), both "suggestion: reject it"
_CHECK_HEAD = re.compile(r"^\s*(to check\s*:|à vérifier\s*:|a verifier\s*:)", re.IGNORECASE)
_REJECT_HINT = re.compile(r"\((suggestion\s*:\s*reject it|proposition\s*:\s*(le|la) refuser)\)", re.IGNORECASE)


def check_proposes_reject(rationale):
    """True when the rationale STARTS with a check of the proposal (lot A) whose own words propose rejecting the field. A value check ("|| Value check")
    never proposes anything and is not read here."""
    base = (rationale or "").split(" || Value check")[0]
    return bool(_CHECK_HEAD.match(base)) and bool(_REJECT_HINT.search(base))


def no_feed(entity):
    """Lot M7 (2026-10-18): True when the load said on the entity's row that no accepted mapping feeds it (ebx.StagingLoader.NO_FEED_MESSAGE: "No feed: ..."):
    nothing was loaded into it by design of the decisions, not by a defect of the engine. The scenario reports it as information, never as a failure."""
    return text(entity.get("lastLoadMessage")).strip().lower().startswith("no feed")


def value_check_failing(rationale):
    """True when the rationale carries a "Value check" note that did NOT fix the link (the note starts with "fixed." when it did)."""
    if not rationale or " || Value check" not in rationale:
        return False
    tail = rationale.split(" || Value check", 1)[1]
    tail = re.sub(r"^\s*\[was \d{1,3}\]", "", tail).lstrip(": ").lstrip()
    return not tail.lower().startswith("fixed")


NATURE_ORDER = ["RELATION", "ENTITY", "ATTRIBUTE", "FEED", "MAPPING", "MAPPING_STEP", "QUALITY", "SCRIPT_DERIVATION", "SCRIPT_CONSTRAINT", "EXTERNAL_SERVICE",
                "MATCH", "DIFFERENCE"]


def decision_for(line):
    """The documented policy of the flagged queue: ('REJECTED'|'ACCEPTED', why).
    - what a check of the proposal itself proposes to reject is rejected (a field that duplicates another or has no source);
    - an optional look-up through an external service is rejected: it needs a connector set-up that is not part of this scenario;
    - everything else is accepted."""
    nature = line.get("nature", "")
    if nature == "EXTERNAL_SERVICE":
        return "REJECTED", "external look-up: needs a connector set-up, out of this scenario"
    if check_proposes_reject(line.get("rationale", "")):
        return "REJECTED", "the proposal's own check proposes to reject it"
    return "ACCEPTED", "no check against it"


def order_lines(lines):
    rank = dict((n, i) for i, n in enumerate(NATURE_ORDER))
    return sorted(lines, key=lambda l: (rank.get(l.get("nature", ""), 99), to_int(l.get("id"), 0)))


def person_entity(entities):
    """The entity the sources feed: the one whose memberEntities hold the most rule entities, at least TWO (integration 2026-10-18: the model may
    legitimately keep the store tickets as a purchases entity and unify only the web and loyalty customers - the scenarios then check that the
    sources they NEED feed it, REQUIRED_PERSON_APPS). Entities are Data Services rows ({id, name, origin, status, memberEntities}). None when no
    entity unifies two."""
    best = None
    for e in entities:
        if text(e.get("status")) in ("SUPERSEDED", "REJECTED"):
            continue
        members = len(set(as_list(e.get("memberEntities"))))
        if members >= 2 and (best is None or members > best[0]):
            best = (members, e)
    return best[1] if best else None


def rule_entities_left_alone(entities):
    """Entities written by the rule pass (origin RULE) that are still PROPOSED: a source left alone instead of absorbed by the unified entity."""
    return [e for e in entities if text(e.get("origin")) == "RULE" and text(e.get("status")) == "PROPOSED"]


def feed_tables(feeds):
    """The landing table ids a set of feeds (TransferFeed rows) reads, REJECTED / SUPERSEDED feeds left out."""
    return set(text(f.get("landingTable")) for f in feeds if text(f.get("status")) not in ("REJECTED", "SUPERSEDED") and text(f.get("landingTable")))


def live(row):
    return text(row.get("status")) not in ("REJECTED", "SUPERSEDED")


def pipeline_steps(pipeline):
    """('name', 'argument') of each step of a mapping pipeline such as 'trim|date(dd/MM/yyyy,yyyy-MM-dd)|strip(.-)'."""
    out = []
    depth = 0
    token = ""
    parts = []
    for ch in pipeline or "":
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch == "|" and depth == 0:
            parts.append(token)
            token = ""
        else:
            token += ch
    if token:
        parts.append(token)
    for part in parts:
        part = part.strip()
        if not part:
            continue
        m = re.match(r"^([A-Za-z_][\w.\-]*)\s*(?:\((.*)\))?$", part, re.DOTALL)
        out.append((m.group(1).lower(), m.group(2) or "") if m else (part.lower(), ""))
    return out


_DROPS_PLUS_KINDS = {"digits", "digits_only", "keep_digits", "only_digits", "numeric_only"}
_STRIP_KINDS = {"strip", "strip_chars", "remove_chars", "normalise_identifier", "normalize_identifier", "delete_chars", "replace_chars"}


def step_drops_plus(kind, argument):
    """True when one cleaning step (a MappingStep kind + argument, or a pipeline step) removes a leading '+': a step that keeps digits only, or one that
    strips/normalises a set of characters that includes '+'."""
    kind = (kind or "").strip().lower()
    argument = argument or ""
    if kind in _DROPS_PLUS_KINDS:
        return True
    if kind in _STRIP_KINDS and "+" in argument:
        return True
    return False


def plus_droppers(pipeline, steps):
    """The labels of the steps of a phone mapping that drop a leading '+'. `steps` = MappingStep rows of the mapping (kind, argument, status)."""
    found = []
    for name, arg in pipeline_steps(pipeline):
        if step_drops_plus(name, arg):
            found.append("pipeline %s(%s)" % (name, arg))
    for s in steps:
        if live(s) and step_drops_plus(text(s.get("kind")), text(s.get("argument"))):
            found.append("step %s(%s)" % (text(s.get("kind")), text(s.get("argument"))))
    return found


def derived_children(attributes, parent_ids):
    """The attributes (rows) derived from one of `parent_ids` (derivedFrom)."""
    parents = set(parent_ids)
    return [a for a in attributes if text(a.get("derivedFrom")) in parents and live(a)]


def to_be_provided(binding_text):
    """Names of the parameters of a derivation binding that still wait for a person: {"p":{"toBeProvided":"why"}}. Empty on anything unreadable."""
    try:
        data = json.loads(binding_text or "{}")
    except ValueError:
        return []
    if not isinstance(data, dict):
        return []
    return sorted(k for k, v in data.items() if isinstance(v, dict) and "toBeProvided" in v)


# --------------------------------------------------------------------------------------------------------------------- measuring loaded rows

def non_empty(value):
    return text(value).strip() != ""


def fill_count(rows, field):
    return sum(1 for r in rows if non_empty(r.get(field)))


def rate(part, whole):
    return 1.0 if whole == 0 else float(part) / float(whole)


def phone_plus_violations(rows, field, source_field, source_system):
    """Rows of `source_system` whose phone does not start with '+' (a source that wrote its numbers with a '+' must keep it)."""
    return [r for r in rows if text(r.get(source_field)) == source_system and non_empty(r.get(field)) and not text(r.get(field)).strip().startswith("+")]


def e164_report(rows, phone_field, e164_field):
    """(rows with a phone, rows where the E.164 field is filled among them, E.164 values that do not start with '+')."""
    with_phone = [r for r in rows if non_empty(r.get(phone_field))]
    filled = [r for r in with_phone if non_empty(r.get(e164_field))]
    odd = [r for r in filled if not text(r.get(e164_field)).strip().startswith("+")]
    return len(with_phone), len(filled), len(odd)


def to_float(value, default=None):
    try:
        return float(text(value))
    except ValueError:
        return default


def phone_source_systems(tables_by_app, columns_by_table, source_system_of):
    """Lot M15 (2026-10-18), corrected by lot M16: {source system: [phone column names]} of the applications whose landing holds a FILLED phone column (a column named like
    PHONE_COLUMNS whose fill rate is above 0; an unknown fill rate counts as filled). `tables_by_app` = {app: LandingTable row}, `columns_by_table` = {landing table id: [LandingColumn
    rows]}, `source_system_of` = {app: the sourceSystem the staging rows carry}.

    The ids are compared as TEXT on both sides: Data Services answers a LandingTable `id` as a number (`{"content": 207}`) while the scenario keys the columns by that very value, so a
    lookup by `text(id)` missed every table on the real instance (run 11, 2026-10-18: STORES.TELEPHONE filled 234/234 and the check ended SKIP "no landing column of a source is a filled
    phone column"); the stand-in factory served string ids and hid it. A column is named by `sourceColumn`, else `fieldName`."""
    by_text = {}
    for key, columns in columns_by_table.items():
        by_text.setdefault(text(key), []).extend(columns)
    out = {}
    for app, table in tables_by_app.items():
        names = []
        for column in by_text.get(text(table.get("id")), []):
            fill = to_float(column.get("fillRate"))
            name = text(column.get("sourceColumn")) or text(column.get("fieldName"))
            if name.lower() in PHONE_COLUMNS and (fill is None or fill > 0):
                names.append(name)
        if names:
            out[source_system_of.get(app, app)] = names
    return out


def phone_by_source(rows, phone_field, source_field, sources):
    """Lot M15: [(source system, rows of that source in the staging, rows of that source where `phone_field` is filled)] for each of `sources`, in order. The check "E.164 filled
    on >= 95 % of rows with a phone" counts only rows that HAVE a phone: a source whose phone never reached the entity (E2EMASTER20261018090711: the STORES
    ticket phone was mapped only to a superseded entity) has none of its rows in that count and passed unseen."""
    out = []
    for source in sources:
        mine = [r for r in rows if text(r.get(source_field)) == source]
        out.append((source, len(mine), sum(1 for r in mine if non_empty(r.get(phone_field)))))
    return out


def sources_missing_phone(rows, phone_field, source_field, sources):
    """Lot M15: the (source, rows, filled) of the sources that have rows in the staging and not one filled `phone_field` although their landing holds a filled phone column."""
    return [t for t in phone_by_source(rows, phone_field, source_field, sources) if t[1] > 0 and t[2] == 0]


NAMES_LIBRARY_SUFFIX = re.compile(r"(^|\.)lib\.names$|^names$")


def is_names_library(*references):
    """Lot M11: whether one of the references (a library ide path such as 'library/Fasttrack/lib/names', or an import name such as 'Fasttrack.lib.names') is the published
    names library. The library, not the function and not the attribute name, is what tells a name key: sortedKeyOf, a later fullNameKey... all live there."""
    for reference in references:
        norm = re.sub(r"[/\\]", ".", text(reference).strip()).strip(".").lower()
        if norm and NAMES_LIBRARY_SUFFIX.search(norm):
            return True
    return False


def name_derivation_attributes(attributes, suggestions):
    """Lot M11: the live attributes that a live SCRIPT_DERIVATION suggestion computes with the names library, whatever its origin (RULE or LLM), its function and the
    attribute's own name (personNameKey, nameMatchKey, fullNameKey...)."""
    wanted = set(text(s.get("attribute")) for s in suggestions
                 if text(s.get("nature")) == "SCRIPT_DERIVATION" and live(s) and is_names_library(s.get("targetLibrary"), s.get("targetImportName")))
    wanted.discard("")
    return [a for a in attributes if text(a.get("id")) in wanted and live(a)]


def key_report(rows, key_field, name_fields):
    """(rows with a first or last name, rows among them where the key field is filled)."""
    named = [r for r in rows if any(non_empty(r.get(f)) for f in name_fields)]
    return len(named), sum(1 for r in named if non_empty(r.get(key_field)))


# --------------------------------------------------------------------------------------------------------------------- the hard cases (tools/e2e/b2c_hard.sh)

HARD_ROWS = {"ECOM": 160, "STORES": 234, "LOYALTY": 140, "CRM": 150}
HARD_KEYS = {"ECOM": "customerId", "STORES": "NUM_TICKET", "LOYALTY": "card_number", "CRM": "contact_id"}
HARD_PERSON_ROWS = 684          # 160 + 234 + 140 + 150, one staging row per source row
HARD_BIRTH_DATES = 450          # 160 web + 140 loyalty + 150 CRM (the tickets carry none)
CRM_LONG_COMMENTS = 13          # every 12th CRM comment is 600..640 characters long
CIVILITY_COLUMNS = {"civilite", "civility"}
TIER_COLUMNS = {"niveau", "tier"}
COMMENT_COLUMNS = {"comment"}
# the codes each file really holds for the two concepts the sources write differently (the generator is seeded: these sets are exact)
OBSERVED_CODES = {("civility", "LOYALTY"): ("M.", "Mme"), ("civility", "CRM"): ("MR", "MRS", "MISS"),
                  ("tier", "LOYALTY"): ("Bronze", "Argent", "Or"), ("tier", "CRM"): ("L1", "L2", "L3")}
TRANSCODE_KIND = "TRANSCODE_REFERENCE"
FLAG_CEILING = 59               # a transcoding table that leaves a code without a target is flagged at this confidence or lower
LOYALTY_FIX_PIPELINE = "trim|date(dd/MM/yyyy,yyyy-MM-dd)"
FORCED_PIPELINE = "isodate()"
BLOCKED_BUILD_WORDS = ("was not started", "nothing to do")


def code_key(code):
    """The comparison key of a code (quality/CleaningSteps#normalizeKey, as the transcoding engine matches): case, accents, punctuation and white space ignored.
    A code made only of punctuation keeps itself."""
    text_value = unicodedata.normalize("NFKD", text(code))
    text_value = "".join(ch for ch in text_value if not unicodedata.combining(ch)).lower()
    key = re.sub(r"[^0-9a-z]", "", text_value)
    return key if key else text(code).strip()


def parse_code_table(argument):
    """The table a TRANSCODE_REFERENCE step carries: {"codes": {observed: target}, "targets": [...], "counts": {...}, "basedOn": ""}, or None when the argument is not one."""
    try:
        data = json.loads(argument or "")
    except ValueError:
        return None
    if not isinstance(data, dict) or not isinstance(data.get("codes"), dict):
        return None
    return {"codes": dict((text(k), text(v)) for k, v in data["codes"].items()),
            "targets": [text(t) for t in data.get("targets") or [] if text(t)],
            "counts": data.get("counts") if isinstance(data.get("counts"), dict) else {},
            "basedOn": text(data.get("basedOn"))}


def is_incomplete_code_table_refusal(reason):
    """True for the refusal of ProposalReviews.decide on a code table with an observed code that has rows and no target code (English: the REST surface carries no locale)."""
    return text(reason).startswith("Choose a target code for ")


def code_gaps(table, observed):
    """The observed codes a table leaves without a target (not listed, or listed with an empty target), matched by code_key."""
    mapped = dict((code_key(k), v) for k, v in table["codes"].items() if text(v).strip())
    return [c for c in observed if code_key(c) not in mapped]


def code_targets_of(table, observed):
    """The normalised target codes a source ends up with: the target of each observed code, the code itself when the table has none for it (the engine leaves it as it is)."""
    mapped = dict((code_key(k), v) for k, v in table["codes"].items() if text(v).strip())
    return set(code_key(mapped.get(code_key(c), c)) for c in observed)


def codes_alignment(sources):
    """sources: {source: (observed codes, table or None)} for ONE concept. Returns (problems, vocabulary): the vocabulary is the union of the target lists of the tables (normalised);
    a problem is a source left with a code outside it (no table and codes of its own, or a table that leaves a code unmapped), or no table at all."""
    vocabulary = set()
    for _o, table in sources.values():
        if table is not None:
            vocabulary |= set(code_key(t) for t in table["targets"]) | set(code_key(v) for v in table["codes"].values() if text(v).strip())
    problems = []
    if not any(t is not None for _o, t in sources.values()):
        return ["no source has a code table"], vocabulary
    for source, (observed, table) in sorted(sources.items()):
        final = code_targets_of(table, observed) if table is not None else set(code_key(c) for c in observed)
        outside = sorted(final - vocabulary)
        if outside:
            problems.append("%s keeps code(s) outside the common list: %s" % (source, ", ".join(outside)))
    return problems, vocabulary


def pipeline_norm(pipeline):
    """A pipeline compared without white space or case: 'trim | date(dd/MM/yyyy, yyyy-MM-dd)' = 'trim|date(dd/mm/yyyy,yyyy-mm-dd)'."""
    return re.sub(r"\s+", "", text(pipeline)).lower()


def preflight_finding(report, mapping_id):
    """The finding of one mapping in a preflight answer, or None."""
    if not isinstance(report, dict):
        return None
    for f in report.get("findings") or []:
        if isinstance(f, dict) and text(f.get("mapping")) == text(mapping_id):
            return f
    return None


def blocking_issues(finding):
    return [i for i in (finding or {}).get("issues") or [] if isinstance(i, dict) and i.get("blocking") is True]


def blocking_findings(report):
    return [f for f in (report or {}).get("findings") or [] if isinstance(f, dict) and blocking_issues(f)]


def build_was_blocked(message):
    """True when a build job's message is the 'the build was not started' sentence of the preflight (English, with or without its marker)."""
    low = text(message).lower()
    return any(w in low for w in BLOCKED_BUILD_WORDS)


def reject_code_parts(reject):
    return text(reject.get("reasonCode")).split("|")


def reject_open(reject):
    return not truthy(reject.get("resolved"))


def reject_select(rejects, stage="STAGING", entity=None, code_prefix=None, category=None, open_only=True, source=None, column=None):
    """The rejects (TransferReject field maps) matching every given criterion. code_prefix is matched on reasonCode; column on its last segment (the source column)."""
    out = []
    for r in rejects:
        if stage and text(r.get("stage")) != stage:
            continue
        if open_only and not reject_open(r):
            continue
        if entity is not None and text(r.get("entity")) != text(entity):
            continue
        if code_prefix is not None and not text(r.get("reasonCode")).startswith(code_prefix):
            continue
        if category is not None and text(r.get("category")) != category:
            continue
        if source is not None and text(r.get("sourceSystem")) != text(source):
            continue
        if column is not None and reject_code_parts(r)[-1].lower() != column.lower():
            continue
        out.append(r)
    return out


def tally_by(rows, key):
    out = {}
    for r in rows:
        k = text(r.get(key))
        out[k] = out.get(k, 0) + 1
    return out


def count_longer(values, limit):
    return sum(1 for v in values if len(text(v)) > limit)


def causes_problems(causes, expect_ebx, expect_parent):
    """What is wrong with the answer of GET rejects/causes (the step 4 banner's own counts and sentence): [] when it is right."""
    problems = []
    counts = (causes or {}).get("counts") or {}
    sentence = text((causes or {}).get("sentence"))
    total = to_int((causes or {}).get("total"))
    if not isinstance(causes, dict) or not sentence:
        return ["no sentence"]
    if to_int(counts.get("EBX_REFUSAL")) < expect_ebx:
        problems.append("EBX_REFUSAL counts %s, expected at least %d" % (counts.get("EBX_REFUSAL"), expect_ebx))
    if to_int(counts.get("PARENT_REJECTED")) < expect_parent:
        problems.append("PARENT_REJECTED counts %s, expected at least %d" % (counts.get("PARENT_REJECTED"), expect_parent))
    if "EBX" not in sentence:
        problems.append("the sentence does not say EBX refused the rows")
    if str(total) not in sentence:
        problems.append("the sentence does not say how many rows (%d)" % total)
    if to_int(counts.get("QUALITY_RULE")) == 0 and re.search(r"\brule\b", sentence, re.IGNORECASE):
        problems.append("the sentence blames a rule although none stopped a row")
    return problems


def wizard_url(base, dataspace, dataset, project, step=0):
    """The address of the wizard on a project (ebx.ui.FactoryLinks#wizardUrl: the code, and the step, travel as base64 serviceParams)."""
    import base64
    import urllib.parse
    params = {"project": project}
    if step:
        params["step"] = step
    blob = base64.b64encode(json.dumps(params, separators=(",", ":")).encode("utf-8")).decode("ascii")
    return "%s/ebx-ui/ui/advanced/data/B%s/%s/root/Project?service=ProjectWizard&serviceMode=TABLE_VIEW&serviceParams=%s" % (
        base.rstrip("/"), urllib.parse.quote(dataspace, safe=""), urllib.parse.quote(dataset, safe=""), urllib.parse.quote(blob, safe=""))


def rejects_url(base, dataspace, dataset, project):
    """The address of the transfer-reject review on a project (ebx.ui.FactoryLinks#rejectsUrl)."""
    import urllib.parse
    return "%s/ebx-ui/ui/advanced/data/B%s/%s/root/Project?predicate=%s&service=TransferRejectReview&serviceMode=RECORD" % (
        base.rstrip("/"), urllib.parse.quote(dataspace, safe=""), urllib.parse.quote(dataset, safe=""), urllib.parse.quote("./code='%s'" % project, safe="/"))


# --------------------------------------------------------------------------------------------------------------------- jobs, routes

def route_missing(status, body_json):
    """True when a 404/405 means 'this route does not exist on this build' rather than 'this project does not exist': the module's own answer for an
    unknown project is a 404 {"error": "Project <code> not found"}; a missing route answers anything else (an empty body, an HTML page, another message)."""
    if status == 405:
        return True
    if status != 404:
        return False
    error = body_json.get("error") if isinstance(body_json, dict) else None
    return not (isinstance(error, str) and error.startswith("Project ") and error.endswith(" not found"))


def job_in(jobs_answer, job_id):
    """The job `job_id` in a GET /jobs answer (active first, then recent), or None."""
    if not isinstance(jobs_answer, dict):
        return None
    for key in ("active", "recent"):
        for job in jobs_answer.get(key) or []:
            if isinstance(job, dict) and str(job.get("jobId")) == str(job_id):
                return job
    return None


class Timeout(Exception):
    pass


def poll(fetch, done, timeout_s, interval_s, label, sleep=time.sleep, clock=time.monotonic, on_tick=None):
    """Calls fetch() every interval until done(value) is truthy. Bounded: raises Timeout with a message naming what was waited for and how long."""
    start = clock()
    last = None
    while True:
        last = fetch()
        if done(last):
            return last
        waited = clock() - start
        if waited >= timeout_s:
            raise Timeout("timed out after %d s waiting for %s" % (int(waited), label))
        if on_tick is not None:
            on_tick(last, waited)
        sleep(interval_s)


# --------------------------------------------------------------------------------------------------------------------- Kafka (the distribution step, lot S1)
#
# There is no Kafka client library on the machine that runs the scenario, so the broker is probed with a socket and `kafka-topics.sh`, and a topic is read with
# `kafka-console-consumer.sh` (the CLI of the broker's own distribution). Both are injectable (the scenario takes a `topic_reader` and a `broker_probe` in its
# arguments), so the self-test needs no Kafka at all.

DEFAULT_KAFKA_HOME = "kafka_2.13-4.2.0"
DEFAULT_KAFKA_BOOTSTRAP = "localhost:9082"


def kafka_settings(env):
    """{bootstrap, home, idle_ms} from E2E_KAFKA_BOOTSTRAP (host:port, the broker the Connectors connection points to), E2E_KAFKA_HOME (the broker's distribution folder: bin/kafka-*.sh)
    and E2E_KAFKA_IDLE_MS (the console consumer stops after this long without a message)."""
    try:
        idle = int(env.get("E2E_KAFKA_IDLE_MS", "10000"))
    except ValueError:
        idle = 10000
    return {"bootstrap": env.get("E2E_KAFKA_BOOTSTRAP", DEFAULT_KAFKA_BOOTSTRAP).strip() or DEFAULT_KAFKA_BOOTSTRAP,
            "home": env.get("E2E_KAFKA_HOME", DEFAULT_KAFKA_HOME).strip() or DEFAULT_KAFKA_HOME, "idle_ms": idle}


def kafka_probe(settings, run=subprocess.run, connect=socket.create_connection):
    """(ok, detail): the broker answers a TCP connection AND `kafka-topics.sh --list`. A broker that is down is a normal state of this machine (its start is a person's decision): the
    caller SKIPs the distribution checks with this sentence, it never fails and never waits long (3 s for the socket, 30 s for the CLI)."""
    bootstrap = settings["bootstrap"]
    host, _sep, port = bootstrap.rpartition(":")
    try:
        connect((host or "localhost", int(port)), 3).close()
    except (OSError, ValueError) as exc:
        return False, "no Kafka broker answers at %s (%s)" % (bootstrap, exc)
    cli = os.path.join(settings["home"], "bin", "kafka-topics.sh")
    if not os.path.isfile(cli):
        return False, "%s not found: set E2E_KAFKA_HOME to the broker's distribution folder" % cli
    try:
        done = run([cli, "--bootstrap-server", bootstrap, "--list"], capture_output=True, text=True, timeout=30)
    except subprocess.TimeoutExpired:
        return False, "kafka-topics.sh --list did not answer within 30 s at %s" % bootstrap
    except OSError as exc:
        return False, "kafka-topics.sh could not be run: %s" % exc
    if done.returncode != 0:
        return False, "kafka-topics.sh --list failed at %s: %s" % (bootstrap, re.sub(r"\s+", " ", (done.stderr or "").strip())[:200])
    return True, "%s answers; %d topic(s) listed" % (bootstrap, len([l for l in (done.stdout or "").splitlines() if l.strip()]))


def split_console_output(stdout):
    """([{key, value}], [notice lines]) from the lines of `kafka-console-consumer.sh` run with print.key=true: a message is the key, a TAB, the value (a message with no key prints `null` as
    its key). A line WITHOUT a TAB is never a message - with print.key=true every message line has one - it is a notice of the CLI itself (lot S4, measured live 2026-10-18: a CLI 4.x
    printed `Option --property is deprecated and will be removed in a future version. Use --formatter-property instead.` on its stdout and the scenario counted it as a message that
    is not JSON). Blank lines are dropped."""
    messages, notices = [], []
    for line in (stdout or "").splitlines():
        if not line.strip():
            continue
        key, sep, value = line.partition("\t")
        if sep:
            messages.append({"key": key, "value": value})
        else:
            notices.append(line.strip())
    return messages, notices


def parse_console_output(stdout):
    """The messages of split_console_output (the CLI's own notice lines are not messages)."""
    return split_console_output(stdout)[0]


_BROKER_TROUBLE = re.compile(r"Connection refused|Connection to node -?\d+ .* could not be established|Timed out waiting for a node|Failed to find brokers", re.IGNORECASE)


_OLD_CLI = re.compile(r"unrecognized option|Unrecognized option|is not a recognized option|unknown option|Unknown option|invalid option|OptionException", re.IGNORECASE)


def kafka_cli_reader(settings, run=subprocess.run):
    """reader(topic, idle_ms) -> (messages, error): every message of the topic from the beginning, read with kafka-console-consumer.sh (it ends by itself after `idle_ms` without a message,
    and then exits with status 1 and a TimeoutException on stderr: that is the normal end). `error` is None, or a sentence when the CLI could not run or the broker could not be reached;
    an empty or unknown topic answers ([], None) - the CLI cannot tell them apart.
    Lot S4: the key printing is asked with `--formatter-property print.key=true` (the CLI 4.x deprecates `--property` and prints a notice on stdout); an older CLI that does not know
    `--formatter-property` answers with a usage error and no message, and the read is repeated once with `--property`. The notice lines of the CLI are never messages (split_console_output)."""
    def command(cli, topic, idle, flag):
        return [cli, "--bootstrap-server", settings["bootstrap"], "--topic", topic, "--from-beginning", "--timeout-ms", str(idle), flag, "print.key=true", flag, "key.separator=\t"]

    def read(topic, idle_ms=None):
        cli = os.path.join(settings["home"], "bin", "kafka-console-consumer.sh")
        if not os.path.isfile(cli):
            return [], "%s not found: set E2E_KAFKA_HOME" % cli
        idle = int(idle_ms or settings["idle_ms"])
        env = dict(os.environ)
        env.setdefault("KAFKA_HEAP_OPTS", "-Xmx256m")
        done = None
        for flag in ("--formatter-property", "--property"):
            try:
                done = run(command(cli, topic, idle, flag), capture_output=True, text=True, timeout=idle / 1000.0 + 90, env=env)
            except subprocess.TimeoutExpired:
                return [], "kafka-console-consumer.sh on %s did not end within %d s" % (topic, idle // 1000 + 90)
            except OSError as exc:
                return [], "kafka-console-consumer.sh could not be run: %s" % exc
            if flag == "--formatter-property" and not parse_console_output(done.stdout) and _OLD_CLI.search((done.stderr or "") + (done.stdout or "")):
                continue          # a CLI that does not know --formatter-property: once more with the old name
            break
        messages = parse_console_output(done.stdout)
        if not messages and done.returncode not in (0, 1):
            return [], "kafka-console-consumer.sh exited %d: %s" % (done.returncode, re.sub(r"\s+", " ", (done.stderr or "").strip())[:200])
        if not messages and _BROKER_TROUBLE.search(done.stderr or "") and "Processed a total of" not in (done.stderr or ""):
            return [], "the broker could not be reached: %s" % re.sub(r"\s+", " ", (done.stderr or "").strip())[:200]
        return messages, None
    return read


def topic_prefix(code):
    """The prefix every topic of a project starts with: its code in lower case and a dot (<code lower case>.<table>, what the distribution configure writes)."""
    return str(code).lower() + "."


def project_topics(code, names):
    """The names of `names` that are the project's own topics: they start with <code lower case>. and nothing else. The ONLY filter a topic deletion goes through."""
    prefix = topic_prefix(code)
    return sorted(set(n for n in names if isinstance(n, str) and len(n) > len(prefix) and n.startswith(prefix)))


def kafka_topic_lister(settings, run=subprocess.run):
    """lister() -> (topic names, error): `kafka-topics.sh --list`; `error` is None, or a sentence when the CLI could not run or the broker did not answer."""
    def list_topics():
        cli = os.path.join(settings["home"], "bin", "kafka-topics.sh")
        if not os.path.isfile(cli):
            return [], "%s not found: set E2E_KAFKA_HOME" % cli
        try:
            done = run([cli, "--bootstrap-server", settings["bootstrap"], "--list"], capture_output=True, text=True, timeout=60)
        except subprocess.TimeoutExpired:
            return [], "kafka-topics.sh --list did not answer within 60 s at %s" % settings["bootstrap"]
        except OSError as exc:
            return [], "kafka-topics.sh could not be run: %s" % exc
        if done.returncode != 0:
            return [], "kafka-topics.sh --list failed at %s: %s" % (settings["bootstrap"], re.sub(r"\s+", " ", (done.stderr or "").strip())[:200])
        return [l.strip() for l in (done.stdout or "").splitlines() if l.strip()], None
    return list_topics


def kafka_topic_deleter(settings, run=subprocess.run):
    """deleter(topic) -> (ok, detail): `kafka-topics.sh --delete --topic <name>` for ONE topic. The caller has already restricted `topic` to a project's own (project_topics); this refuses a name that
    holds anything but the characters Kafka allows in a topic name, so no option can ever be smuggled in as a topic."""
    def delete(topic):
        if not re.match(r"^[A-Za-z0-9._-]+$", topic or ""):
            return False, "refused: '%s' is not a plain topic name" % topic
        cli = os.path.join(settings["home"], "bin", "kafka-topics.sh")
        if not os.path.isfile(cli):
            return False, "%s not found: set E2E_KAFKA_HOME" % cli
        try:
            done = run([cli, "--bootstrap-server", settings["bootstrap"], "--delete", "--topic", topic], capture_output=True, text=True, timeout=60)
        except subprocess.TimeoutExpired:
            return False, "kafka-topics.sh --delete did not answer within 60 s"
        except OSError as exc:
            return False, "kafka-topics.sh could not be run: %s" % exc
        if done.returncode != 0:
            return False, re.sub(r"\s+", " ", (done.stderr or done.stdout or "").strip())[:200]
        return True, ""
    return delete


# --------------------------------------------------------------------------------------------------------------------- report

class Report:
    """PASS / FAIL / SKIP / INFO checks, printed as they come and summarised as a compact table at the end."""

    def __init__(self, out):
        self.out = out
        self.checks = []

    def add(self, step, name, status, detail=""):
        self.checks.append({"step": step, "name": name, "status": status, "detail": detail})
        self.out.write("%-5s [%s] %s%s\n" % (status, step, name, " - " + detail if detail else ""))
        self.out.flush()

    def passed(self, step, name, detail=""):
        self.add(step, name, "PASS", detail)

    def failed(self, step, name, detail=""):
        self.add(step, name, "FAIL", detail)

    def skipped(self, step, name, detail=""):
        self.add(step, name, "SKIP", detail)

    def info(self, step, name, detail=""):
        self.add(step, name, "INFO", detail)

    def check(self, step, name, ok, detail_ok="", detail_ko=""):
        if ok:
            self.passed(step, name, detail_ok)
        else:
            self.failed(step, name, detail_ko)
        return ok

    def counts(self):
        c = {"PASS": 0, "FAIL": 0, "SKIP": 0, "INFO": 0}
        for x in self.checks:
            c[x["status"]] += 1
        return c

    def table(self, width=118):
        lines = ["", "%-6s %-4s %s" % ("RESULT", "STEP", "CHECK"), "-" * 60]
        for x in self.checks:
            if x["status"] == "INFO":
                continue
            lines.append("%-6s %-4s %s" % (x["status"], x["step"], x["name"]))
            if x["status"] == "FAIL" and x["detail"]:
                lines.append("       %s" % (x["detail"] if len(x["detail"]) <= width else x["detail"][:width] + "..."))
        c = self.counts()
        lines.append("-" * 60)
        lines.append("%d passed, %d failed, %d skipped" % (c["PASS"], c["FAIL"], c["SKIP"]))
        return "\n".join(lines)
