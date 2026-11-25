#!/usr/bin/env python3
"""Audit of the pairs a matching plan found, against the ground truth of the B2C scenario (tools/e2e/gen_b2c.py writes truth_b2c.json). Lot M10a, docs/MATCHING.md section 64.

READ ONLY: Data Services GET (master table, its child table, the metadataset's Suspected table, the stored MatchPlan). Credentials come from the environment
(EBX_USER, EBX_PASSWORD), never from a file or an argument. Python 3 standard library only.

  EBX_USER=... EBX_PASSWORD=... python3 tools/matching/pair_audit.py --dataspace E2EMASTER20261018014728_Master --table Personne --child Adresse --child-ref personneRef \
      --truth <dir written by gen_b2c.py>/truth_b2c.json --plan 74 [--rule]

What it prints
  * the clusters the suspect pairs form (the measure (b) of tools/e2e/master_lib.py: pairs of a cluster, not only the suspect edges) and how many of their pairs are two people;
  * for every suspect EDGE between two people: the fields the two records share (exact) and the fields they differ on;
  * with --plan: the tree is replayed on every suspect edge (names: Jaro-Winkler, ids as stored) and the edges are counted by the last node the pair passed through onTrue and by
    leaf - which node of the plan let the false pairs through;
  * with --rule: the path rule of lot M10a (master/PathEvidenceRule) applied to the same edges - every path must pass an ALL_FIELDS node on a predictive key of the plan's stored key
    facts, or on a precise field - and the precision / recall of the clusters before and after.
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

EXACT = ("exact", "exactfulltext")


def get(base, path, auth):
    request = urllib.request.Request(base.rstrip("/") + path)
    request.add_header("Authorization", "Basic " + auth)
    with urllib.request.urlopen(request, timeout=120) as answer:
        return json.loads(answer.read().decode("utf-8"))


def value(row, field):
    cell = row["content"].get(field)
    return cell.get("content") if isinstance(cell, dict) else None


def rows(base, auth, dataspace, dataset, table):
    return get(base, "/ebx-dataservices/rest/data/v1/B%s/%s/root/%s?pageSize=10000" % (dataspace, dataset, table), auth).get("rows", [])


def jaro_winkler(a, b):
    a, b = a.lower(), b.lower()
    if a == b:
        return 1.0
    la, lb = len(a), len(b)
    if not la or not lb:
        return 0.0
    window = max(la, lb) // 2 - 1
    ma, mb, matches = [False] * la, [False] * lb, 0
    for i in range(la):
        for j in range(max(0, i - window), min(lb, i + window + 1)):
            if not mb[j] and a[i] == b[j]:
                ma[i] = mb[j] = True
                matches += 1
                break
    if not matches:
        return 0.0
    transpositions, k = 0, 0
    for i in range(la):
        if ma[i]:
            while not mb[k]:
                k += 1
            if a[i] != b[k]:
                transpositions += 1
            k += 1
    transpositions /= 2
    jaro = (matches / la + matches / lb + (matches - transpositions) / matches) / 3
    prefix = 0
    for i in range(min(4, la, lb)):
        if a[i] != b[i]:
            break
        prefix += 1
    return jaro + prefix * 0.1 * (1 - jaro)


def score(algorithm, x, y):
    if algorithm == "exact":
        return 100 if x == y else 0
    if algorithm == "exactfulltext":
        wx, wy = set(str(x).lower().split()), set(str(y).lower().split())
        return 100 * len(wx & wy) / max(len(wx), len(wy)) if wx and wy else 0
    if algorithm == "jarowinkler":
        return 100 * jaro_winkler(str(x), str(y))
    return 0


def field_score(field, a, b):
    algorithm = field["algorithm"].split("@")[-1].lower()
    if field.get("via"):
        best = 0
        for x in a["child"]:
            for y in b["child"]:
                vx, vy = value(x, field["field"]), value(y, field["field"])
                if vx and vy:
                    best = max(best, score(algorithm, vx, vy))
        return best
    x, y = value(a["row"], field["field"]), value(b["row"], field["field"])
    return None if x in (None, "") or y in (None, "") else score(algorithm, x, y)


def passes(node, a, b):
    scores = [field_score(f, a, b) for f in node["fields"]]
    if node["function"] == "ALL_FIELDS":
        return all(s is not None and s >= f.get("minimumScore", 100) for s, f in zip(scores, node["fields"]))
    return sum(s or 0 for s in scores) / len(scores) >= node.get("minimumScore", 100)


def walk(nodes, root, a, b):
    current, passed = root, []
    while current not in ("MATCH", "SUSPECTED", "NOT_MATCHED"):
        node = nodes[current]
        if passes(node, a, b):
            passed.append(node)
            current = node["onTrue"]
        else:
            current = node["onFalse"]
    return current, passed


def predictive_keys(facts, min_rate=0.9, min_comparable=10, max_pairs=2):
    """Mirror of KeyFacts.predictiveKeys (matching/KeyFacts.java)."""
    keys = {k["name"]: k for k in facts["keys"]}

    def selective(name):
        key = keys.get(name)
        return key is not None and 0 < key["pairsSharing"] <= max_pairs * sum(key["filledBySystem"].values())

    out = set()
    for agreement in facts["agreements"]:
        comparable = agreement["agree"] + agreement["conflict"]
        if comparable >= min_comparable and agreement["agree"] / comparable >= min_rate and selective(agreement["key"]) and selective(agreement["other"]):
            out.add(agreement["key"])
    return out


def identifies(field, node, predictive, keys, child_rows_per_value):
    """Mirror of master/PathEvidenceRule.judge for the stored (short) field names."""
    algorithm = field["algorithm"].split("@")[-1].lower()
    if node["function"] != "ALL_FIELDS" or algorithm not in EXACT:
        return False
    if "MATCH" in (field.get("bothNull", "NOT_MATCH"), field.get("oneNull", "NOT_MATCH")):
        return False
    if algorithm == "exactfulltext" and field.get("minimumScore", 100) < 50:
        return False
    if field.get("via"):
        rows = child_rows_per_value.get(field["field"])
        return rows is None or rows <= 5
    if field["field"] in keys:
        return field["field"] in predictive
    return True


def clusters_metrics(edges, person):
    parent = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for a, b in edges:
        parent[find(a)] = find(b)
    groups = collections.defaultdict(set)
    for x in list(parent):
        groups[find(x)].add(x)
    found = sum(len(g) * (len(g) - 1) // 2 for g in groups.values())
    right = sum(sum(n * (n - 1) // 2 for n in collections.Counter(person[x] for x in g).values()) for g in groups.values())
    truth = sum(n * (n - 1) // 2 for n in collections.Counter(person.values()).values())
    return groups, found, right, truth


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--dataspace", required=True, help="master dataspace key, without the B prefix")
    parser.add_argument("--dataset", default="Master")
    parser.add_argument("--table", required=True)
    parser.add_argument("--child", help="child table holding the related records (address)")
    parser.add_argument("--child-ref", help="field of the child that points to the parent")
    parser.add_argument("--truth", required=True)
    parser.add_argument("--plan", help="MatchPlan id in dataset MdmFactory: replay its tree on the suspect edges")
    parser.add_argument("--rule", action="store_true", help="apply the path rule of lot M10a to the plan (needs --plan)")
    parser.add_argument("--show", type=int, default=20, help="false edges listed")
    args = parser.parse_args()
    user, password = os.environ.get("EBX_USER"), os.environ.get("EBX_PASSWORD")
    if not user or not password:
        sys.exit("set EBX_USER and EBX_PASSWORD in the environment")
    auth = base64.b64encode(("%s:%s" % (user, password)).encode()).decode()

    truth = dict(((t["app"], t["key"]), t["person"]) for t in json.load(open(args.truth, encoding="utf-8"))["rows"])
    records = {}
    for row in rows(args.base, auth, args.dataspace, args.dataset, args.table):
        rid = str(value(row, "id"))
        person = truth.get((value(row, "sourceSystem"), value(row, "sourceId")))
        if person is not None:
            records[rid] = {"row": row, "person": person, "child": [], "system": value(row, "sourceSystem")}
    if args.child:
        for row in rows(args.base, auth, args.dataspace, args.dataset, args.child):
            if value(row, "Linked_Field_AutoCreated"):
                continue  # the golden copies the cascade merge makes of originals
            parent = str(value(row, args.child_ref))
            if parent in records:
                records[parent]["child"].append(row)
    suspects = rows(args.base, auth, args.dataspace, "%s_%s_MDS" % (args.dataset, args.table), "Suspected")
    edges = sorted(set(tuple(sorted((str(value(s, "sourceId")), str(value(s, "targetId"))), key=int)) for s in suspects))
    edges = [e for e in edges if e[0] in records and e[1] in records]
    person = dict((rid, r["person"]) for rid, r in records.items())
    groups, found, right, truth_pairs = clusters_metrics(edges, person)
    print("%d records, %d suspect edges, %d clusters; pairs found %d, right %d, false %d; precision %.3f recall %.3f" % (
        len(records), len(edges), len(groups), found, right, found - right, right / found if found else 0, right / truth_pairs if truth_pairs else 0))
    for group in groups.values():
        people = collections.Counter(person[x] for x in group)
        if len(people) > 1:
            print("  cluster mixing %d people: %s" % (len(people), ", ".join("person %s (%s)" % (p, ",".join("%s/%s" % (x, records[x]["system"]) for x in sorted(group, key=int) if person[x] == p)) for p in people)))
    fields = [k for k in records[next(iter(records))]["row"]["content"] if not k.startswith("_") and not k.startswith("Linked_") and k not in ("id", "sourceId", "sourceSystem", "Association_Reverse")]
    false_edges = [e for e in edges if person[e[0]] != person[e[1]]]
    print("%d false suspect edge(s):" % len(false_edges))
    for a, b in false_edges[:args.show]:
        ra, rb = records[a]["row"], records[b]["row"]
        same = [f for f in fields if value(ra, f) not in (None, "") and value(ra, f) == value(rb, f) and not isinstance(value(ra, f), dict)]
        diff = [f for f in fields if value(ra, f) not in (None, "") and value(rb, f) not in (None, "") and value(ra, f) != value(rb, f)]
        child_same = args.child and any(value(x, "postalCode") == value(y, "postalCode") for x in records[a]["child"] for y in records[b]["child"])
        print("  %s~%s (%s/%s, persons %s/%s) share %s%s; differ on %s" % (a, b, records[a]["system"], records[b]["system"], person[a], person[b], ",".join(same) or "-",
              " + postalCode of " + args.child if child_same else "", ",".join(diff) or "-"))

    if not args.plan:
        return
    plan = get(args.base, "/ebx-dataservices/rest/data/v1/BMdmFactory/MdmFactory/root/MatchPlan/%s" % args.plan, auth)["content"]
    requirements = json.loads(plan["requirements"]["content"])
    nodes = dict((n["id"], n) for n in requirements["decisionTree"]["nodes"])
    root = requirements["decisionTree"]["root"]
    by_node = collections.Counter()
    verdict = {}
    for edge in edges:
        leaf, passed = walk(nodes, root, records[edge[0]], records[edge[1]])
        verdict[edge] = (leaf, passed)
        by_node[(passed[-1]["id"] if passed else "-", leaf, "true" if person[edge[0]] == person[edge[1]] else "FALSE")] += 1
    print("suspect edges by the last node passed (and leaf):")
    for (node, leaf, kind), n in sorted(by_node.items()):
        print("  %-5s %-10s %-5s %d" % (node, leaf, kind, n))
    if not args.rule:
        return
    facts = requirements["keyFacts"]
    predictive = predictive_keys(facts)
    keys = set(k["name"] for k in facts["keys"])
    child_rows = {}
    if args.child:
        for f in set(f["field"] for n in nodes.values() for f in n["fields"] if f.get("via")):
            values = [value(x, f) for r in records.values() for x in r["child"] if value(x, f) not in (None, "")]
            child_rows[f] = len(values) / len(set(values)) if values else None
    print("predictive keys: %s; rows per value of the related fields: %s" % (", ".join(sorted(predictive)), dict((k, round(v, 1) if v else v) for k, v in child_rows.items())))
    kept = []
    for edge in edges:
        leaf, passed = verdict[edge]
        if any(identifies(f, n, predictive, keys, child_rows) for n in passed for f in n["fields"]):
            kept.append(edge)
    for label, e in (("before", edges), ("after the path rule", kept)):
        g, f, r, t = clusters_metrics(e, person)
        print("%-20s edges %4d, pairs found %4d, false %3d, precision %.3f recall %.3f" % (label, len(e), f, f - r, r / f if f else 0, r / t if t else 0))


if __name__ == "__main__":
    main()
