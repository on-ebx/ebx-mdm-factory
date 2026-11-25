"""Which true pairs of the B2C master scenario a matching plan cannot find, and why (lot M3, 2026-10-17).

Input: the directory written by tools/e2e/gen_b2c.py (the three source files and truth_b2c.json). The script rebuilds, for each of the 534 person records the
master holds (web shop ECOM, store tickets STORES, loyalty members LOYALTY), the keys the Factory derives (e-mail key = upper-case e-mail, name key = accent-folded
lower-case tokens sorted, phone in E.164 with the default country +33, postal code, address key = street + postal code + town, folded) and lists every TRUE pair
(same person) with the keys the two records share. A plan that finds a pair only through the keys it uses is simulated (`--plan emailOrAddress` = the plan of project
E2EMASTER20261017212252: e-mail key equal OR address keys shared; any other set of keys can be given with `--keys email,phone,name+postal`).

    python3 tools/e2e/b2c_missed_pairs.py <dir written by gen_b2c.py> [--keys email,address]
    python3 tools/e2e/b2c_missed_pairs.py <dir> --tsv ebx-mdm-factory/src/test/resources/com/on/ebx/factory/matching/b2c_person_keys.tsv   (the Java tests' fixture)

Python 3 standard library only, no network. The measured result is in docs/MATCHING.md section 56.
"""
import csv
import json
import os
import re
import sys
import unicodedata


def fold(s):
    s = unicodedata.normalize("NFD", s or "")
    return "".join(c for c in s if unicodedata.category(c) != "Mn").lower()


def name_key(first, last):
    toks = re.findall(r"[a-z0-9]+", fold(first) + " " + fold(last))
    return " ".join(sorted(toks)) or None


def e164(raw, default_cc="33"):
    d = re.sub(r"\D", "", raw or "")
    if not d:
        return None
    if (raw or "").strip().startswith("+"):
        return "+" + d
    if d.startswith("0"):
        return "+" + default_cc + d[1:]
    return "+" + d


def addr_key(street, postal, city):
    if not street or not postal:
        return None
    return fold("%s|%s|%s" % (street, postal, city)).replace(" ", "")


def load_records(directory):
    recs = []
    with open(os.path.join(directory, "ECOM_Clients.json"), encoding="utf-8") as fh:
        for r in json.load(fh)["customers"]:
            recs.append(dict(app="ECOM", key=r["customerId"], first=r["firstName"], last=r["lastName"], email=r["email"], phone=r["mobile"],
                             birth=r["birthDate"], postal=r["postalCode"], addr=addr_key(r["street"], r["postalCode"], r["city"])))
    with open(os.path.join(directory, "Magasins_Ventes.csv"), encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh, delimiter=";"):
            recs.append(dict(app="STORES", key=r["NUM_TICKET"], first=r["PRENOM"], last=r["NOM"], email=r["EMAIL"], phone=r["TELEPHONE"],
                             birth=None, postal=r["CODE_POSTAL"], addr=None))
    with open(os.path.join(directory, "Fidelite_Membres.csv"), encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh):
            recs.append(dict(app="LOYALTY", key=r["card_number"], first=r["prenom"], last=r["nom"], email=r["email"], phone=r["telephone"],
                             birth="-".join(reversed(r["date_naissance"].split("/"))), postal=r["cp"], addr=addr_key(r["adresse"], r["cp"], r["ville"])))
    for r in recs:
        r["emailKey"] = (r["email"] or "").strip().upper() or None
        r["nameKey"] = name_key(r["first"], r["last"])
        r["e164"] = e164(r["phone"])
        r["postal"] = r["postal"] or None
    return recs


def load_truth(directory):
    with open(os.path.join(directory, "truth_b2c.json"), encoding="utf-8") as fh:
        return {(t["app"], t["key"]): t["person"] for t in json.load(fh)["rows"]}


def eq(a, b):
    return a is not None and b is not None and a == b


def shared(a, b):
    """The keys two records share, as a frozenset of short names."""
    out = set()
    if eq(a["emailKey"], b["emailKey"]):
        out.add("email")
    if eq(a["e164"], b["e164"]):
        out.add("phone")
    if eq(a["nameKey"], b["nameKey"]):
        out.add("name")
    if eq(a["postal"], b["postal"]):
        out.add("postal")
    if eq(a["addr"], b["addr"]):
        out.add("address")
    if eq(a["birth"], b["birth"]):
        out.add("birth")
    return frozenset(out)


def found_by(keys_shared, plan_keys):
    """plan_keys: list of alternatives, each a set of key names that must ALL be shared (name+postal = both)."""
    return any(alt <= keys_shared for alt in plan_keys)


def parse_keys(text):
    return [frozenset(alt.split("+")) for alt in text.split(",")]


def analyse(recs, truth, plan_keys):
    for r in recs:
        r["person"] = truth[(r["app"], r["key"])]
    pairs = []
    true = []
    for i in range(len(recs)):
        for j in range(i + 1, len(recs)):
            a, b = recs[i], recs[j]
            s = shared(a, b)
            if a["person"] == b["person"]:
                true.append((a, b, s))
            elif found_by(s, plan_keys):
                pairs.append((a, b, s))
    found = [t for t in true if found_by(t[2], plan_keys)]
    return true, found, pairs


def write_tsv(recs, truth, path):
    """The fixture of the Java tests (KeyFactsTest, KeyFindingsTest): one line per record, the keys the master derives, and the true person."""
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("system\tperson\temailKey\tnameKey\te164\tpostal\taddressKey\tbirth\n")
        for r in recs:
            fh.write("\t".join([r["app"], str(truth[(r["app"], r["key"])])] + [r[k] or "" for k in ("emailKey", "nameKey", "e164", "postal", "addr", "birth")]) + "\n")


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    directory = argv[0]
    keys = "email,address"
    for i, a in enumerate(argv):
        if a == "--keys":
            keys = argv[i + 1]
        if a == "--tsv":
            write_tsv(load_records(directory), load_truth(directory), argv[i + 1])
            return 0
    plan_keys = parse_keys(keys)
    recs = load_records(directory)
    truth = load_truth(directory)
    true, found, false_pairs = analyse(recs, truth, plan_keys)
    missed = [t for t in true if not found_by(t[2], plan_keys)]
    print("records %d, true pairs %d, found by %s: %d (recall %.3f), false pairs %d" % (len(recs), len(true), keys, len(found), len(found) / len(true), len(false_pairs)))
    table = {}
    for a, b, s in missed:
        apps = "-".join(sorted((a["app"], b["app"])))
        cause = []
        for rec in (a, b):
            if rec["app"] == "STORES" and rec["emailKey"] is None:
                cause.append("store ticket without e-mail")
        table.setdefault((apps, ",".join(sorted(s)) or "none", "; ".join(sorted(set(cause))) or "-"), []).append(1)
    print("\nMISSED pairs by applications / keys shared / reason")
    for k in sorted(table, key=lambda k: -len(table[k])):
        print("%5d  %-22s shares: %-28s %s" % (len(table[k]), k[0], k[1], k[2]))
    print("\nALL true pairs by keys shared (found or not)")
    allt = {}
    for a, b, s in true:
        allt.setdefault(",".join(sorted(s)) or "none", [0, 0])[0 if found_by(s, plan_keys) else 1] += 1
    for k in sorted(allt, key=lambda k: -sum(allt[k])):
        print("%5d  found %4d  missed %4d  shares: %s" % (sum(allt[k]), allt[k][0], allt[k][1], k))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
