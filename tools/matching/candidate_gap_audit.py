#!/usr/bin/env python3
"""Which TRUE pairs of the B2C master scenario did the add-on never bring together, and what do they have in common? Lot M16, docs/MATCHING.md section 68.

READ ONLY: Data Services GET (the master table and the add-on's RecordMetadata) and the stored matching policy through the match-merge MCP READ tool `get_matching_config`.
Credentials come from the environment (EBX_USER, EBX_PASSWORD), never from a file or an argument. Python 3 standard library only.

  EBX_USER=... EBX_PASSWORD=... python3 tools/matching/candidate_gap_audit.py --dataspace E2EMASTER20261018104106_Master --table Personne \
      --truth <dir written by gen_b2c.py>/truth_b2c.json [--policy Personne]

What it prints (the run 11 figures are in docs/MATCHING.md section 68)
  * recall of the clusters the metadataset holds (a record is merged into the golden `goldenId`; a record left alone is its own cluster) and the number of missed true pairs;
  * the missed pairs by source pair and by e-mail presence on each side, and the keys the two records share;
  * per record without an e-mail: whether the person has a record that fills EVERY declared field (the add-on's fullest pivot) and whether the record was brought together with
    an e-mail-bearing record of its person - the split that pins the add-on's candidate threshold (a candidate must score at least 30 % of the PIVOT'S OWN score, the pivot being worth the
    sum of the pre-clustering weights of the fields it fills);
  * the pre-clustering weights of the stored policy and the share of their total that each key set of the tree holds (CandidateScoreShare, master/), so the arithmetic is on the page.
"""
import argparse
import base64
import collections
import itertools
import json
import os
import sys
import urllib.parse
import urllib.request

KEYS = ("emailKey", "mobileE164", "personNameKey", "addressKeys")


def _load_text():
    """The unwrap of a Data Services field, shared with the end-to-end scenario's master_lib (tools/e2e) when it can be imported, else the same few lines (lot F4, 2026-10-19)."""
    try:
        here = os.path.dirname(os.path.abspath(__file__))
        sys.path.insert(0, os.path.join(os.path.dirname(here), "e2e"))
        import master_lib
        return master_lib._text
    except Exception:       # noqa: BLE001  (a standalone copy of the script: use the local one below)
        return None
    finally:
        if sys.path and sys.path[0].endswith(os.path.join("tools", "e2e")):
            sys.path.pop(0)


def _text(value):
    """The bare text of a Data Services field. A FOREIGN KEY (RecordMetadata.goldenId points to the business table) comes back as a dict {content, label, details, selector}, while a plain
    field is a scalar: the dict is unwrapped ("content", repeatedly, else "key") and str() of the dict was what this script compared with a record key before. None is ""."""
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


text = _load_text() or _text


def auth_header():
    user, password = os.environ.get("EBX_USER"), os.environ.get("EBX_PASSWORD")
    if not user or not password:
        sys.exit("EBX_USER and EBX_PASSWORD must be set in the environment")
    return "Basic " + base64.b64encode((user + ":" + password).encode("utf-8")).decode("ascii")


def request(url, auth, body=None):
    req = urllib.request.Request(url, data=body, headers={"Authorization": auth})
    if body is not None:
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json, text/event-stream")
    with urllib.request.urlopen(req, timeout=180) as answer:
        return answer.read().decode("utf-8")


def table(base, auth, dataspace, dataset, name):
    url = "%s/ebx-dataservices/rest/data/v1/B%s/%s/root/%s?pageSize=1000" % (base, dataspace, dataset, name)
    out = []
    while url:
        doc = json.loads(request(url, auth))
        for row in doc.get("rows", []):
            out.append(dict((k, v.get("content")) for k, v in row["content"].items() if isinstance(v, dict) and "content" in v))
        nxt = (doc.get("pagination") or {}).get("nextPage")
        url = nxt.get("href") if isinstance(nxt, dict) else nxt
    return out


def policy_weights(base, auth, dataspace, tbl, code):
    """The pre-clustering weights of the stored policy and its decision tree (get_matching_config, a read tool)."""
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "get_matching_config", "arguments": {
        "table": "/root/" + tbl, "dataModel": "urn:ebx:publication:" + dataspace}}}).encode("utf-8")
    raw = request(base + "/ebx-assistant/mcp/v1/match-merge", auth, body)
    if "data:" in raw[:20]:
        raw = [line[5:] for line in raw.splitlines() if line.startswith("data:")][-1]
    text = json.loads(raw)["result"]["content"][0]["text"]
    for policy in json.loads(text)["data"]["matchingPolicies"]:
        if policy.get("processCode") == code:
            return dict((f["field"].lstrip("/"), f["weight"]) for f in policy["fields"] if f.get("field")), policy["decisionTree"]
    return {}, None


def key_sets(tree):
    """The field sets the add-on derives from a nested tree (the fields of the ALL_FIELDS nodes passed through the true side, exact algorithms, NOT_MATCH nulls)."""
    sets = []

    def walk(node, passed):
        if node is None:
            return
        if not node.get("function"):
            if node.get("name") in ("Match", "Suspect"):
                sets.append(list(collections.OrderedDict(passed).items()))
            return
        mine = []
        if node["function"] == "All fields match":
            for f in node.get("fields", []):
                algo = (f.get("algorithm") or "").lower()
                if (algo.endswith("exact") or algo.endswith("exactfulltext")) and f.get("bothNullValueHandling") == "NOT_MATCH" and f.get("oneNullValueHandling") == "NOT_MATCH":
                    minimum = next((int(p["value"]) for p in f.get("params", []) if p["name"] == "minimumScore"), 100)
                    mine.append((f["field"].lstrip("/"), 100 if algo.endswith("exact") else minimum))
        walk(node.get("trueNode"), passed + mine)
        walk(node.get("falseNode"), passed)

    walk(tree, [])
    unique = []
    for entry in sets:
        if entry and entry not in unique:
            unique.append(entry)
    return unique


def records_of(rows, meta_rows, truth):
    """{record id: row + person + golden} for the master rows that map to a person of the truth. `golden` is the key of the golden record a MERGED record points to (RecordMetadata.goldenId,
    a foreign key: a dict over Data Services - lot S6 of CLAUDE.md section 8, lot F4), else the record's own key (a record left alone is its own cluster)."""
    meta = dict((text(m.get("functionalId")), m) for m in meta_rows)
    recs = {}
    for r in rows:
        m = meta.get(text(r.get("id")))
        if m is None or m.get("autoCreated"):
            continue
        person = truth.get((r.get("sourceSystem"), r.get("sourceId")))
        if person is None:
            continue
        r = dict(r)
        r["person"] = person
        r["golden"] = text(m.get("goldenId")) if text(m.get("state")) in ("2", "MERGED") and text(m.get("goldenId")) else text(r.get("id"))
        recs[text(r.get("id"))] = r
    return recs


def pairs_of(recs):
    """(true pairs, missed pairs): the pairs of records of one person, and those of them that do not share a golden."""
    by_person = collections.defaultdict(list)
    for rid, r in recs.items():
        by_person[r["person"]].append(rid)
    pairs = [(a, b) for ids in by_person.values() for a, b in itertools.combinations(ids, 2)]
    return pairs, [(a, b) for a, b in pairs if recs[a]["golden"] != recs[b]["golden"]]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("EBX_URL", "http://localhost:8080"))
    parser.add_argument("--dataspace", required=True, help="the master dataspace key without the B prefix, e.g. E2EMASTER20261018104106_Master")
    parser.add_argument("--dataset", default="Master")
    parser.add_argument("--table", default="Personne")
    parser.add_argument("--truth", required=True)
    parser.add_argument("--policy", default=None, help="processCode of the policy to read (default: the table name)")
    args = parser.parse_args()
    auth = auth_header()

    truth = dict(((r["app"], r["key"]), r["person"]) for r in json.load(open(args.truth, encoding="utf-8"))["rows"])
    rows = table(args.base, auth, args.dataspace, args.dataset, args.table)
    meta_rows = table(args.base, auth, args.dataspace, args.dataset + "_" + args.table + "_MDS", "RecordMetadata")
    recs = records_of(rows, meta_rows, truth)
    by_person = collections.defaultdict(list)
    for rid, r in recs.items():
        by_person[r["person"]].append(rid)

    pairs, missed = pairs_of(recs)
    print("records %d, true pairs %d, found %d, missed %d (recall %.3f)" % (len(recs), len(pairs), len(pairs) - len(missed), len(missed), (len(pairs) - len(missed)) / max(1, len(pairs))))

    def has(r, k):
        return bool(r.get(k))

    print("\nmissed pairs by source pair / e-mail presence (E = filled) / keys shared")
    by_kind = collections.Counter()
    shared_keys = collections.Counter()
    for a, b in missed:
        x, y = sorted((recs[a], recs[b]), key=lambda r: (r["sourceSystem"], has(r, "emailKey")))
        by_kind[("%s-%s" % (x["sourceSystem"], y["sourceSystem"]), "".join("E" if has(r, "emailKey") else "-" for r in (x, y)))] += 1
        shared_keys[tuple(k for k in KEYS if has(x, k) and x[k] == y.get(k))] += 1
    for k, v in sorted(by_kind.items()):
        print("  %-16s %-3s %d" % (k[0], k[1], v))
    print("  keys shared by the missed pairs:", dict(shared_keys))
    two_sided = sum(1 for a, b in missed if has(recs[a], "emailKey") == has(recs[b], "emailKey"))
    print("  missed pairs where BOTH sides (or neither) fill the e-mail: %d" % two_sided)

    print("\nrecords without an e-mail that have an e-mail-bearing partner: joined or not, by the fullest record of the person")
    split = collections.Counter()
    for ids in by_person.values():
        fullest = any(all(has(recs[i], k) for k in KEYS) for i in ids)
        for i in ids:
            if has(recs[i], "emailKey"):
                continue
            partners = [j for j in ids if has(recs[j], "emailKey")]
            if partners:
                joined = any(recs[j]["golden"] == recs[i]["golden"] for j in partners)
                split[("a record fills every declared field" if fullest else "no record fills every declared field", "joined" if joined else "alone")] += 1
    for k, v in sorted(split.items()):
        print("  %-42s %-7s %d" % (k[0], k[1], v))

    code = args.policy or args.table
    weights, tree = policy_weights(args.base, auth, args.dataspace, args.table, code)
    if tree:
        total = sum(weights.values())
        print("\npolicy %s weights %s (total %d)" % (code, weights, total))
        for fields in key_sets(tree):
            share = sum(weights.get(f, 1) * pct for f, pct in fields) / (100.0 * total)
            print("  key set %-46s %5.1f %% of the total %s" % ([f for f, _p in fields], share * 100, "" if share > 0.3 else "<- under the add-on's 30 %"))


if __name__ == "__main__":
    main()
