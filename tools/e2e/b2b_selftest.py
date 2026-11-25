"""Self-test of the B2B end-to-end scenario: the generator's invariants (counts, envelopes, Luhn, VAT keys, overlaps), the assertion logic on canned data, and the whole scenario against
the stand-in factory on 127.0.0.1 (b2c_standin.FakeFactory in B2B mode, b2b_standin.py). No EBX instance is involved, so tools/test_local.sh runs it.
Run alone: tools/e2e/b2b_scenario.sh --selftest"""
import contextlib
import csv
import datetime
import io
import json
import os
import re
import shutil
import sys
import tempfile
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "itest"))

import e2e_lib as L  # noqa: E402
import gen_b2b as G  # noqa: E402

FAILURES = []


def check(label, ok, detail=""):
    if not ok:
        FAILURES.append(label)
        sys.stdout.write("FAILED %s %s\n" % (label, detail))


def read_bytes(path):
    with open(path, "rb") as fh:
        return fh.read()


# ------------------------------------------------------------------------------------------------------------------ the generator

def test_identifiers():
    check("Luhn: a valid SIREN", G.luhn_ok("356000000") and G.siren_valid("356000000"))
    check("Luhn: a wrong checksum", not G.luhn_ok("356000001") and not G.siren_valid("356000001"))
    check("Luhn: not digits / empty", not G.luhn_ok("35600000A") and not G.luhn_ok("") and not G.siren_valid("12345"))
    check("FR VAT key, the textbook example (SIREN 404833048 -> FR83404833048)", G.fr_vat_key("404833048") == "83" and G.fr_vat("404833048") == "FR83404833048")
    check("FR VAT validity reads spaces and case", G.fr_vat_valid("FR 83 404 833 048") and G.fr_vat_valid("fr83404833048") and not G.fr_vat_valid("FR84404833048") and not G.fr_vat_valid("DE83404833048"))
    check("SIRET validity needs 14 Luhn-valid digits", G.siret_valid("35600000000048") == G.luhn_ok("35600000000048") and not G.siret_valid("356000000"))


def test_generator():
    import b2b_scenario as B
    work = tempfile.mkdtemp(prefix="b2b-selftest-")
    try:
        paths, truth = G.write(os.path.join(work, "one"))
        paths2, truth2 = G.write(os.path.join(work, "two"))
        check("generator: deterministic, byte for byte (files and truth)", all(read_bytes(paths[a]) == read_bytes(paths2[a]) for a in paths)
              and read_bytes(os.path.join(work, "one", G.TRUTH_FILE)) == read_bytes(os.path.join(work, "two", G.TRUTH_FILE)))
        problems, counts, data = B.check_files(paths)
        check("generator: no problem, 150 / 120 / 90", not problems and counts == {"SAP": 150, "SALESFORCE": 120, "WEB": 90}, str(problems) + str(counts))
        summary = truth["summary"]
        by_company = dict((p["company"], p) for p in truth["companies"])

        # ---- envelopes
        with open(paths["SAP"], encoding="utf-8") as fh:
            sap_doc = json.load(fh)
        check("SAP envelope is OData v2 {d: {results: [...]}}", list(sap_doc.keys()) == ["d"] and list(sap_doc["d"].keys()) == ["results"] and len(sap_doc["d"]["results"]) == 150)
        with open(paths["WEB"], encoding="utf-8") as fh:
            web_doc = json.load(fh)
        check("web envelope is {submissions: [...]}", list(web_doc.keys()) == ["submissions"] and len(web_doc["submissions"]) == 90)
        for envelope, expected in (("results", ["results"]), ("array", None)):
            sub = G.write(os.path.join(work, "env-" + envelope), envelope, "array")[0]
            doc = json.load(open(sub["SAP"], encoding="utf-8"))
            check("SAP envelope %s" % envelope, (list(doc.keys()) == expected) if expected else isinstance(doc, list))
            check("web envelope array", isinstance(json.load(open(sub["WEB"], encoding="utf-8")), list))
            check("reader unwraps envelope %s" % envelope, len(B.read_sap(sub["SAP"])) == 150 and len(B.read_web(sub["WEB"])) == 90)
        raw = open(paths["SALESFORCE"], encoding="utf-8").read().splitlines()
        check("Salesforce: comma separated, every field quoted", len(raw) == 121 and all(line.startswith('"') and line.endswith('"') and '","' in line for line in raw), raw[0][:80])
        check("Salesforce: the header is the contract", raw[0] == ",".join('"%s"' % c for c in G.SF_COLUMNS))

        # ---- SAP
        sap = data["SAP"]
        check("SAP: 10-digit numbers with leading zeros, category 2", all(re.match(r"^\d{10}$", r["BusinessPartner"]) and r["BusinessPartnerCategory"] == "2" for r in sap)
              and all(r["BusinessPartner"].startswith("000") for r in sap))
        check("SAP: legal name cut at 40 characters, the rest in Name2 (20 of them)", all(len(r["OrganizationBPName1"]) <= 40 for r in sap)
              and sum(1 for r in sap if r["OrganizationBPName2"]) == summary["sapLongNames"] == 20)
        bp_company = dict((k, p) for p in truth["companies"] for k in p["sources"]["SAP"])
        check("SAP: Name1 + Name2 is the company's legal name", all((r["OrganizationBPName1"] + " " + r["OrganizationBPName2"]).strip().replace("  ", " ") == bp_company[r["BusinessPartner"]]["name"] or
                                                                   (r["OrganizationBPName1"] + r["OrganizationBPName2"]) == bp_company[r["BusinessPartner"]]["name"] for r in sap))
        check("SAP: CreationDate is an OData /Date(ms)/", all(re.match(r"^/Date\(\d{12,13}\)/$", r["CreationDate"]) for r in sap))
        check("SAP: 1 or 2 addresses, the seven fields, ISO2 country", all(1 <= len(r["to_BusinessPartnerAddress"]["results"]) <= 2 for r in sap)
              and all(set(a) == {"AddressID", "StreetName", "HouseNumber", "PostalCode", "CityName", "Country", "Region"} and re.match(r"^[A-Z]{2}$", a["Country"])
                      for r in sap for a in r["to_BusinessPartnerAddress"]["results"])
              and any(len(r["to_BusinessPartnerAddress"]["results"]) == 2 for r in sap))
        taxes = [(r, t) for r in sap for t in r["to_BusinessPartnerTax"]["results"]]
        check("SAP: tax types FR1 (SIREN, Luhn-valid) / FR0 (VAT, valid key) and the VAT of EU partners", {t["BPTaxType"] for _r, t in taxes} <= {"FR1", "FR0", "DE0", "ES0", "IT0", "BE0"}
              and all(G.siren_valid(t["BPTaxNumber"]) for _r, t in taxes if t["BPTaxType"] == "FR1")
              and all(G.fr_vat_valid(t["BPTaxNumber"]) for _r, t in taxes if t["BPTaxType"] == "FR0")
              and all(t["BPTaxNumber"].startswith(t["BPTaxType"][:2]) for _r, t in taxes if t["BPTaxType"] not in ("FR1",))
              and {"FR1", "FR0"} <= {t["BPTaxType"] for _r, t in taxes} and len({t["BPTaxType"] for _r, t in taxes} - {"FR1", "FR0"}) >= 2, str(sorted({t["BPTaxType"] for _r, t in taxes})))
        be = [t["BPTaxNumber"] for _r, t in taxes if t["BPTaxType"] == "BE0"]
        check("SAP: Belgian VAT numbers have their mod-97 key", all(97 - int(v[2:10]) % 97 == int(v[10:12]) for v in be))
        it = [t["BPTaxNumber"] for _r, t in taxes if t["BPTaxType"] == "IT0"]
        check("SAP: Italian VAT numbers are Luhn-valid", all(G.luhn_ok(v[2:]) for v in it))
        check("SAP: the SIREN of a partner is the company's", all(next(t["BPTaxNumber"] for t in r["to_BusinessPartnerTax"]["results"] if t["BPTaxType"] == "FR1") == bp_company[r["BusinessPartner"]]["siren"]
                                                                   for r in sap if bp_company[r["BusinessPartner"]]["country"] == "FR"))

        # ---- Salesforce
        sf = data["SALESFORCE"]
        for r in sf[:50]:
            suffix = ""
            for chunk in (r["Id"][0:5], r["Id"][5:10], r["Id"][10:15]):
                suffix += "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"[sum((1 << i) for i, ch in enumerate(chunk) if ch.isupper())]
            if suffix != r["Id"][15:]:
                check("Salesforce: the Id carries its case checksum", False, r["Id"])
                break
        check("Salesforce: 18-character 001 Ids, unique", all(len(r["Id"]) == 18 and r["Id"].startswith("001") for r in sf) and len({r["Id"] for r in sf}) == 120)
        sf_company = dict((k, p) for p in truth["companies"] for k in p["sources"]["SALESFORCE"])
        check("Salesforce: the country is a NAME", {r["BillingCountry"] for r in sf} <= {"France", "FRANCE", "france", "Germany", "Spain", "Italy", "Belgium"}
              and "France" in {r["BillingCountry"] for r in sf} and len({r["BillingCountry"] for r in sf}) >= 3)
        check("Salesforce: SIRET is 14 Luhn-valid digits starting with the company's SIREN, sometimes empty", all(not r["SIRET__c"] or (G.siret_valid(r["SIRET__c"])
              and r["SIRET__c"].startswith(sf_company[r["Id"]]["siren"])) for r in sf) and any(r["SIRET__c"] == "" for r in sf if sf_company[r["Id"]]["country"] == "FR"))
        check("Salesforce: VAT numbers have the right key (French) and the right country prefix", all(not r["VAT_Number__c"] or (G.fr_vat_valid(r["VAT_Number__c"]) if sf_company[r["Id"]]["country"] == "FR"
              else re.sub(r"\s", "", r["VAT_Number__c"]).startswith(sf_company[r["Id"]]["country"])) for r in sf))
        links = [r for r in sf if r["SAP_BP__c"]]
        sap_numbers = {r["BusinessPartner"] for r in sap}
        check("Salesforce: SAP_BP__c on 30 % of the rows, each the SAP number of the same company", len(links) == 36 and all(r["SAP_BP__c"] in sap_numbers and
              bp_company[r["SAP_BP__c"]]["company"] == sf_company[r["Id"]]["company"] for r in links))
        shapes = {re.sub(r"\d", "9", r["Phone"]) for r in sf if sf_company[r["Id"]]["country"] == "FR"}
        check("Salesforce: mixed phone formats", len(shapes) >= 4, str(sorted(shapes)))
        check("Salesforce: the street holds its number, abbreviated 'bd' sometimes, sometimes no number", any(" bd " in " " + r["BillingStreet"] for r in sf) and
              any(not re.match(r"^\d", r["BillingStreet"]) for r in sf if sf_company[r["Id"]]["country"] == "FR") and any(re.match(r"^\d+ ", r["BillingStreet"]) for r in sf))
        picklist = {r["Industry"] for r in sf}
        check("Salesforce: the Industry is a picklist value, not an SAP key", picklist.isdisjoint({r["Industry"] for r in sap}) and "Machinery" in picklist)

        # ---- website
        web = data["WEB"]
        web_company = dict((k, p) for p in truth["companies"] for k in p["sources"]["WEB"])
        check("web: uuid submission ids, unique", all(str(uuid.UUID(r["submissionId"])) == r["submissionId"] for r in web) and len({r["submissionId"] for r in web}) == 90)
        check("web: submittedAt is ISO with a timezone", all(datetime.datetime.fromisoformat(r["submittedAt"]).tzinfo is not None for r in web))
        check("web: consentMarketing is a boolean, utmSource and jobTitle present", all(isinstance(r["consentMarketing"], bool) for r in web) and any(r["utmSource"] for r in web) and all(r["jobTitle"] for r in web))
        sirens = [r["siren"] for r in web]
        compact = [re.sub(r"\s", "", s) for s in sirens]
        check("web: SIRENs typed with spaces, empty, and with a wrong checksum", any(" " in s for s in sirens) and any(s == "" for s in sirens) and
              any(len(c) == 9 and not G.siren_valid(c) for c in compact) and any(G.siren_valid(c) for c in compact))
        check("web: a typed SIREN is the company's, or its last digit is wrong", all((not c) or c == web_company[r["submissionId"]]["siren"] or (c[:8] == web_company[r["submissionId"]]["siren"][:8] and not G.siren_valid(c))
                                                                                   for r, c in zip(web, compact)))
        free = {r["country"] for r in web}
        check("web: the country is free text", {"france", "FR"} <= free or {"france", "France"} <= free, str(sorted(free)))
        own_domain = sum(1 for r in web if r["contactEmail"].endswith("@" + web_company[r["submissionId"]]["domain"]))
        check("web: the e-mail domain is the company's website for most rows", own_domain >= 0.7 * len(web) and own_domain < len(web), "%d of %d" % (own_domain, len(web)))
        check("web: phones +33 or 0...", any(r["contactPhone"].startswith("+33") for r in web) and any(r["contactPhone"].startswith("0") for r in web))
        typed, usable = B.web_siren_expectation(web)
        check("web: expectation of typed / usable SIRENs is what the summary says", (typed, usable) == (63, summary["webSirens"]["usableAfterCleaning"]) or typed == usable + summary["webSirens"]["wrongChecksum"], str((typed, usable)))

        # ---- overlaps and ground truth
        check("overlaps: 190 companies, 60 % in two sources or more, 25 % in all three", summary["companies"] == 190 and 58 <= summary["inAtLeastTwoSourcesPercent"] <= 62 and 24 <= summary["inAllThreePercent"] <= 27,
              str(summary))
        check("overlaps: prospects only on the website, duplicates inside a source", summary["onlyOnTheWebsite"] >= 10 and summary["salesforceDuplicateCompanies"] == 5 and summary["webDuplicateCompanies"] == 3)
        check("truth: every key of every file is in exactly one company, no extra key", all(
            sorted(k for p in truth["companies"] for k in p["sources"][app]) == sorted(r[key] for r in data[app])
            for app, key in (("SAP", "BusinessPartner"), ("SALESFORCE", "Id"), ("WEB", "submissionId"))))
        multi = sum(1 for p in truth["companies"] if sum(1 for s in p["sources"].values() if s) >= 2)
        three = sum(1 for p in truth["companies"] if all(p["sources"].values()))
        check("truth: the overlap counts are those of the keys", (multi, three) == (summary["inAtLeastTwoSources"], summary["inAllThreeSources"]))
        check("truth: two Salesforce accounts for 5 companies, two submissions for 3", sum(1 for p in truth["companies"] if len(p["sources"]["SALESFORCE"]) == 2) == 5 and
              sum(1 for p in truth["companies"] if len(p["sources"]["WEB"]) == 2) == 3 and all(len(p["sources"]["SAP"]) <= 1 for p in truth["companies"]))
        differing = 0
        for r in sf:
            sap_keys = sf_company[r["Id"]]["sources"]["SAP"]
            if sap_keys and r["Name"] != sf_company[r["Id"]]["name"]:
                differing += 1
        check("overlaps: the same company is written differently in Salesforce (names), and sometimes identically", differing >= 15 and any(
            r["Name"] == sf_company[r["Id"]]["name"] for r in sf))
        check("truth: expected ISO2 for the Salesforce and web keys", summary["expectedIso2"]["SALESFORCE"][sf[0]["Id"]] == sf_company[sf[0]["Id"]]["country"] and len(summary["expectedIso2"]["WEB"]) == 90)
        check("fictitious: no company carries a well-known name", not any(d in p["name"].lower() for p in truth["companies"] for d in G.DENYLIST))
        check("non-French companies have no SIREN and a VAT of their country", all((p["country"] == "FR") == bool(p["siren"]) and p["vat"].startswith(p["country"]) for p in truth["companies"]))
        check("describe() prints the numbers", any("114" in line and "60.0" in line for line in G.describe(truth)))
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ------------------------------------------------------------------------------------------------------------------ assertion logic

def test_logic():
    import b2b_scenario as B
    check("cleans spaces: NORMALISE_IDENTIFIER with a blank argument (default space, dot, dash)", B.cleans_spaces("", [{"kind": "NORMALISE_IDENTIFIER", "argument": "", "status": "PROPOSED"}]))
    check("cleans spaces: NORMALISE_IDENTIFIER with an argument holding a space", B.cleans_spaces("", [{"kind": "NORMALISE_IDENTIFIER", "argument": ". -", "status": "ACCEPTED"}]))
    check("cleans spaces: NORMALISE_IDENTIFIER that strips only dots does not", not B.cleans_spaces("", [{"kind": "NORMALISE_IDENTIFIER", "argument": ".", "status": "PROPOSED"}]))
    check("cleans spaces: STRIP_CHARS with a space", B.cleans_spaces("", [{"kind": "STRIP_CHARS", "argument": " ", "status": "PROPOSED"}]) and not B.cleans_spaces("", [{"kind": "STRIP_CHARS", "argument": "-", "status": "PROPOSED"}]))
    check("cleans spaces: a rejected step does not count", not B.cleans_spaces("", [{"kind": "NORMALISE_IDENTIFIER", "argument": "", "status": "REJECTED"}]))
    check("cleans spaces: a pipeline step, digits only", B.cleans_spaces("trim|digits", []) and B.cleans_spaces("trim|normalise_identifier()", []) and not B.cleans_spaces("trim", []))
    check("country step: COUNTRY_TO_ISO2 or a reference table, live", B.normalises_country("", [{"kind": "COUNTRY_TO_ISO2", "status": "PROPOSED"}]) and
          B.normalises_country("", [{"kind": "TRANSCODE_REFERENCE", "status": "ACCEPTED"}]) and B.normalises_country("trim|country_to_iso2()", []))
    check("country step: a trim is not one, nor a rejected step", not B.normalises_country("trim", [{"kind": "TRIM", "status": "PROPOSED"}]) and not B.normalises_country("", [{"kind": "COUNTRY_TO_ISO2", "status": "REJECTED"}]))
    entities = [{"id": "1", "name": "Organisation", "status": "PROPOSED"}, {"id": "2", "name": "Locations", "status": "PROPOSED"}, {"id": "3", "name": "OrganisationAddress", "status": "PROPOSED"},
                {"id": "4", "name": "Old", "status": "SUPERSEDED"}]
    feeds = {"1": [{"id": "f1"}], "2": [{"id": "f2", "parentFeed": "f1"}], "3": [{"id": "f3"}, {"id": "f4"}], "4": []}
    found, child = B.address_entity(entities, feeds, "1")
    check("address entity: the one named after addresses wins over an unnamed child", found["id"] == "3" and not child)
    found, child = B.address_entity([e for e in entities if e["id"] != "3"], feeds, "1")
    check("address entity: an unnamed entity with a feed under an organisation feed is a child", found["id"] == "2" and child)
    check("address entity: none", B.address_entity([entities[0], entities[3]], feeds, "1") == (None, False))
    check("ISO2", B.iso2_ok("FR") and B.iso2_ok(" DE ") and not B.iso2_ok("France") and not B.iso2_ok("fr") and not B.iso2_ok("") and not B.iso2_ok(None))
    rows = [{"sourceId": "a", "country": "FR"}, {"sourceId": "b", "country": "Germany"}, {"sourceId": "c", "country": "DE"}, {"sourceId": "d", "country": ""}, {"sourceId": "e|1", "country": "ES"}]
    bad, wrong, resolved = B.country_problems(rows, "country", "sourceSystem", {"a": "FR", "c": "AT", "e": "ES"})
    check("country problems: not ISO2 (typed name, empty), wrong code, tied to the truth", len(bad) == 2 and len(wrong) == 1 and resolved == 3, str((len(bad), len(wrong), resolved)))
    lines = [{"nature": "SCRIPT_CONSTRAINT", "id": "9", "element": "SCRIPT_CONSTRAINT", "rationale": "", "origin": "LLM", "confidence": 70, "status": "PROPOSED"},
             {"nature": "ATTRIBUTE", "id": "5", "element": "fax", "rationale": "To check: no source column (suggestion: reject it).", "origin": "LLM", "confidence": 20, "status": "PROPOSED"},
             {"nature": "EXTERNAL_SERVICE", "id": "7", "element": "x", "rationale": "", "origin": "LLM", "confidence": 60, "status": "PROPOSED"}]
    desk = B.desk_summary(lines)
    check("desk summary: by nature, in policy order, with the decision the policy will take", list(desk.keys()) == ["ATTRIBUTE", "SCRIPT_CONSTRAINT", "EXTERNAL_SERVICE"] and
          desk["ATTRIBUTE"][0]["policy"] == "REJECTED" and desk["SCRIPT_CONSTRAINT"][0]["policy"] == "ACCEPTED" and desk["EXTERNAL_SERVICE"][0]["policy"] == "REJECTED", str(desk))
    check("envelope hint: one SAP row from an OData file says why", "ONE row" in B.landing_envelope_hint("SAP", 150, 1, "odata") and "E2E_SAP_ENVELOPE=results" in B.landing_envelope_hint("SAP", 150, 1, "odata"))
    check("envelope hint: right count, other app, other envelope", B.landing_envelope_hint("SAP", 150, 150, "odata") == "" and B.landing_envelope_hint("WEB", 90, 1, "odata") == "" and
          "instead of 150" in B.landing_envelope_hint("SAP", 150, 1, "results"))
    check("sf identifier expectation", B.sf_identifier_expectation([{"SIRET__c": "1", "VAT_Number__c": ""}, {"SIRET__c": "", "VAT_Number__c": " x"}]) == (1, 1))
    check("web siren expectation (typed, usable after cleaning)", B.web_siren_expectation([{"siren": "356 000 000"}, {"siren": "356000001"}, {"siren": ""}]) == (2, 1))
    work = tempfile.mkdtemp(prefix="b2b-selftest-")
    try:
        paths, _truth = G.write(work)
        with open(paths["SALESFORCE"], "w", encoding="utf-8") as fh:
            fh.write('"Id","Name"\n"001","x"\n')
        problems, counts, _d = B.check_files(paths)
        check("a broken Salesforce file is a problem", len(problems) >= 2 and counts["SALESFORCE"] == 1, str(problems))
        with open(paths["SAP"], "w", encoding="utf-8") as fh:
            json.dump({"d": {"results": [{"BusinessPartner": "12", "BusinessPartnerCategory": "1"}]}}, fh)
        problems, counts, _d = B.check_files(paths)
        check("a broken SAP file is a problem", any("10-digit" in p for p in problems) and counts["SAP"] == 1, str(problems))
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ------------------------------------------------------------------------------------------------------------------ the scenario against the stand-in

def stand_in_run(defects=(), args=None, env_extra=None, code=None):
    import b2c_standin
    import b2b_scenario as B
    server, factory = b2c_standin.serve(defects, b2b=True)
    try:
        out = io.StringIO()
        env = {"EBX_URL": "http://127.0.0.1:%d" % server.server_address[1], "EBX_USER": "selftest", "EBX_PASSWORD": "not-a-real-secret", "E2E_POLL": "0.01",
               "E2E_OUT_DIR": tempfile.mkdtemp(prefix="b2b-rep-")}
        env.update(env_extra or {})
        full = {"cleanup": False, "through": 6, "report": None, "keep_files": False, "cleanup_project": None, "from_project": None, "from_step": None}
        full.update(args or {})
        sc = B.B2BScenario(env, out, full, code=code)
        rc = sc.run() if sc.connect() else 2
        return rc, out.getvalue(), sc, factory, env
    finally:
        server.shutdown()


def failed_names(sc):
    return [c["name"] for c in sc.rep.checks if c["status"] == "FAIL"]


# a defect and the check that guards it (names are substrings)
DEFECTS = [
    ("orphan", "no rule entity left PROPOSED alone"),
    ("twomembers", "one organisation entity unifies the three sources"),
    ("sapnotfed", "the organisation entity is fed by SAP, SALESFORCE and WEB"),
    ("noaddress", "an address entity is proposed"),
    ("addrnosf", "the address entity is fed by SALESFORCE"),
    ("addrnosap", "the address entity is fed by SAP"),
    ("nocountrystep", "Salesforce BillingCountry is normalised to ISO2 by a step"),
    ("nocountrystep", "web country is normalised to ISO2 by a step (accepted state)"),
    ("nosirenclean", "web SIREN is cleaned of its spaces by a step"),
    ("nosiren", "SIREN typed on the website is carried"),
    ("sirenspaces", "web SIREN loaded cleaned"),
    ("badiso", "every loaded address has an ISO2 country"),
    ("badiso", "country of the Salesforce and web rows is the right ISO2 code"),
    ("wrongcountry", "country of the Salesforce and web rows is the right ISO2 code"),
    ("sapnoaddr", "address entity: at least one address per SAP partner"),
    ("sfaddrshort", "address entity: one billing address per Salesforce account"),
    ("reject", "Organisation: 0 TransferReject"),
    ("lowload", "SAP: 150 rows loaded"),
    ("norows", "organisation entity holds one row per source row"),
]


def test_scenario_flows():
    rc, text, sc, factory, env = stand_in_run()
    check("good run: exit 0", rc == 0, text[-3500:])
    check("good run: 0 failed", sc.rep.counts()["FAIL"] == 0, "\n".join(c["name"] + " " + c["detail"] for c in sc.rep.checks if c["status"] == "FAIL"))
    names = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
    for needle in ("generated files match the fixtures (150 / 120 / 90)", "SAP: 150 rows loaded", "SALESFORCE: 120 rows loaded", "WEB: 90 rows loaded", "everything loaded in ONE launch",
                   "one organisation entity unifies the three sources (Organisation)", "the organisation entity is fed by SAP, SALESFORCE and WEB", "an address entity is proposed",
                   "the address entity is fed by SAP (the address collection)", "the address entity is fed by SALESFORCE (the billing fields)", "SIREN typed on the website is carried",
                   "SIRET of Salesforce is carried", "VAT number of Salesforce is carried", "Salesforce BillingCountry is normalised to ISO2 by a step", "web country is normalised to ISO2 by a step",
                   "web SIREN is cleaned of its spaces by a step", "nothing is left to decide", "web SIREN is cleaned of its spaces by a step (accepted state)", "Organisation: rows written",
                   "Organisation: 0 TransferReject", "OrganisationAddress: 0 TransferReject", "organisation entity holds one row per source row (360)", "organisation rows per source",
                   "address entity: one billing address per Salesforce account (120)", "address entity: at least one address per SAP partner (150)", "web SIREN loaded cleaned: 63 typed",
                   "Salesforce SIRET loaded: 78 filled", "Salesforce VAT loaded: 88 filled", "every loaded address has an ISO2 country", "country of the Salesforce and web rows is the right ISO2 code"):
        check("good run passes: " + needle, any(needle in n for n in names), needle + " | " + str(names)[:2500])
    infos = [c["name"] for c in sc.rep.checks if c["status"] == "INFO"]
    check("good run: the Validation desk after the bulk accept is written to the report, by nature, before the decisions",
          sc.details["validationDesk"]["leftAfterBulkAccept"] >= 4 and {"ATTRIBUTE", "SCRIPT_CONSTRAINT", "EXTERNAL_SERVICE"} <= set(sc.details["validationDesk"]["byNature"]) and
          any("Validation desk after the bulk accept" in n for n in infos), str(sc.details.get("validationDesk"))[:600])
    first_decision = next(i for i, c in enumerate(sc.rep.checks) if c["name"].startswith("flagged queue decided"))
    desk_at = next(i for i, c in enumerate(sc.rep.checks) if c["name"] == "Validation desk after the bulk accept")
    check("good run: the desk is read BEFORE the policy decides", desk_at < first_decision)
    check("good run: the policy of the desk is stated for each line", all(l["policy"] in ("ACCEPTED", "REJECTED") for items in sc.details["validationDesk"]["byNature"].values() for l in items))
    check("good run: the SAP key is validated as it stands, the Salesforce key (suggested wrongly) is chosen", any("SAP: suggested key BusinessPartner validated as it stands" in n for n in names) and
          any("SALESFORCE: key Id chosen explicitly" in n for n in names) and any("WEB: suggested key submissionId validated as it stands" in n for n in names), str(names)[:1500])
    check("good run: the SAP nested collections are described", any("SAP nested collections in the landing" in n for n in infos))
    check("good run: the ground truth is in the report and next to it", sc.details["groundTruth"]["companies"] == 190 and os.path.exists(sc.details["groundTruthFile"]) and "ground truth of the files" in text)
    check("good run: the secret is never printed", "not-a-real-secret" not in text)
    reports = [f for f in os.listdir(env["E2E_OUT_DIR"]) if f.startswith("b2b-") and f.endswith(".json") and "truth" not in f]
    check("good run: a JSON report with the desk", len(reports) == 1 and "validationDesk" in json.load(open(os.path.join(env["E2E_OUT_DIR"], reports[0])))["details"])
    check("good run: defaultCountry FR provided on the derivation", "FR" in factory.table("CapabilitySuggestion")["326"]["binding"])
    check("good run: the check proposing a rejection was rejected by the policy", factory.table("StagingAttribute") and any(a["name"] == "fax" and a["status"] == "REJECTED" for a in factory.table("StagingAttribute").values()))

    rc, text, sc, factory, env = stand_in_run(args={"cleanup": True})
    check("cleanup run: exit 0, the project deleted", rc == 0 and "project deleted with its generated objects" in text, text[-1500:])
    rc, text, sc, factory, env = stand_in_run(args={"through": 3})
    check("--through 3 stops after the landing and skips the rest", rc == 0 and sum(1 for c in sc.rep.checks if c["status"] == "SKIP" and c["step"] in ("4", "5", "6")) == 3, text[-800:])
    rc, text, sc, factory, env = stand_in_run(env_extra={"E2E_SAP_ENVELOPE": "results", "E2E_WEB_ENVELOPE": "array"})
    check("other envelopes (results, array): the whole scenario still passes", rc == 0, text[-1500:])
    rc, text, sc, factory, env = stand_in_run(("lowload",))
    check("SAP landing one row: the OData envelope is named as the probable cause", "SAP landed ONE row" in text and "E2E_SAP_ENVELOPE=results" in text, text[-1800:])
    rc, text, sc, factory, env = stand_in_run(("nosirenclean",))
    check("a defect never prints the secret either", "not-a-real-secret" not in text)
    for defect, needle in DEFECTS:
        rc, text, sc, factory, env = stand_in_run((defect,))
        failed = failed_names(sc)
        check("defect %s: exit 1" % defect, rc == 1, text[-800:])
        check("defect %s is caught by '%s'" % (defect, needle), any(needle in n for n in failed), str(failed))
    # judgement calls are reported, never failed: no web country link in the address entity is not a failure
    rc, text, sc, factory, env = stand_in_run(("noaddress",))
    check("no address entity: the check fails, the load checks are skipped with a reason (no crash)", any(c["status"] == "SKIP" and "address" in c["name"] for c in sc.rep.checks) and "scenario crashed" not in text)


def test_cli():
    import b2b_scenario as B
    out = io.StringIO()
    check("dry-run: exit 0, the counts and the overlaps", B.dry_run(out) == 0 and "SAP 150, SALESFORCE 120, WEB 90" in out.getvalue() and "114 (60.0 %)" in out.getvalue() and "plan (nothing is sent)" in out.getvalue(), out.getvalue()[:600])
    check("main --dry-run", B.main(["--dry-run"], {}, io.StringIO()) == 0)
    with contextlib.redirect_stderr(io.StringIO()):
        check("an unexpected argument is exit 2", B.main(["--bogus"], {"EBX_URL": "http://127.0.0.1:1", "EBX_USER": "u", "EBX_PASSWORD": "p"}, io.StringIO()) == 2)
        check("--cleanup-project refuses a code that is not this scenario's", B.main(["--cleanup-project", "E2EB2C20261017"], {"EBX_URL": "http://127.0.0.1:1", "EBX_USER": "u", "EBX_PASSWORD": "p"}, io.StringIO()) == 2)
        check("missing credentials is exit 2", B.main([], {}, io.StringIO()) == 2)
    out = io.StringIO()
    B.main(["--help"], {}, out)
    check("--help prints the usage", "E2E_SAP_ENVELOPE" in out.getvalue())


def run(out=None):
    out = out or sys.stdout
    for fn in (test_identifiers, test_generator, test_logic, test_cli, test_scenario_flows):
        fn()
    if FAILURES:
        out.write("b2b scenario selftest: %d failure(s): %s\n" % (len(FAILURES), "; ".join(FAILURES)))
        return 1
    out.write("b2b scenario selftest OK\n")
    return 0


if __name__ == "__main__":
    sys.exit(run())
