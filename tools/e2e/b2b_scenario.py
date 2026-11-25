#!/usr/bin/env python3
"""End-to-end scenario of the MDM Factory on a B2B test: SAP Business Partners + Salesforce Accounts + website B2B requests -> landing -> staging proposal -> decisions -> build and
load, driven through REST and Data Services only. The twin of b2c_scenario.py (same steps 0-6, same safety guard, same report), with B2B assertions. See docs/E2E-B2B.md.

    EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2b_scenario.sh [--cleanup] [--through 1..6] [--report FILE] [--keep-files]
    tools/e2e/b2b_scenario.sh --selftest      # the assertion logic and the whole scenario against a stand-in factory on 127.0.0.1 (no instance)
    tools/e2e/b2b_scenario.sh --dry-run       # generates the three files and the ground truth, prints the counts and the overlaps, the plan and the routes; sends nothing
    tools/e2e/b2b_scenario.sh --cleanup-project CODE   # deletes a project of an earlier run (E2EB2B...) and reports what is left

Optional environment: E2E_SAP_ENVELOPE=odata|results|array (the SAP file's envelope: OData v2 {"d":{"results":[...]}} by default), E2E_WEB_ENVELOPE=submissions|array, and every knob of
the B2C scenario (E2E_POLL, E2E_THRESHOLD, E2E_TIMEOUT_*, E2E_OUT_DIR, E2E_CACERT...).

Credentials come only from the environment, reach the server in an Authorization header and are never printed. The scenario WRITES (a project, Connectors connections, dataspaces, models,
a staging load): run it against a disposable or test instance, never against production. Every mutating request must name the run's project code (E2EB2B<yyyymmddhhmmss>).

Exit: 0 every check passed, 1 at least one FAIL, 2 environment or arguments, 3 a route the scenario needs is missing on this build.
"""
import csv
import json
import os
import re
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "itest"))

import b2c_scenario as S  # noqa: E402
import client  # noqa: E402
import e2e_lib as L  # noqa: E402
import gen_b2b as G  # noqa: E402

# application, file stem (the application name EBX derives from the file name), extension, source code, trust rank
B2B_FILES = [
    ("SAP", "SAP_BusinessPartner", "json", "SAP", 100),
    ("SALESFORCE", "SF_Accounts", "csv", "SALESFORCE", 90),
    ("WEB", "WEB_AccountRequests", "json", "WEB", 70),
]
B2B_ROWS = {"SAP": 150, "SALESFORCE": 120, "WEB": 90}
B2B_KEYS = {"SAP": "BusinessPartner", "SALESFORCE": "Id", "WEB": "submissionId"}
B2B_TOTAL_ROWS = 360            # one staging row per source row: 150 + 120 + 90
B2B_STEPS = {1: "project", 2: "sources", 3: "landing", 4: "proposal", 5: "decisions", 6: "build and load"}

# the source columns by role, compared lower-case (a nested SAP column is a dotted path or a JSON text: it is looked at by name in the report, never by these sets)
SIREN_COLUMNS = {"siren"}
SIRET_COLUMNS = {"siret__c"}
VAT_COLUMNS = {"vat_number__c"}
SF_COUNTRY_COLUMNS = {"billingcountry"}
WEB_COUNTRY_COLUMNS = {"country"}

ISO2 = re.compile(r"^[A-Z]{2}$")
COUNTRY_STEP_KINDS = {"country_to_iso2", "transcode_reference", "normalise_country", "normalize_country"}
CLEANING_KINDS = {"normalise_identifier", "normalize_identifier", "strip_chars", "digits", "digits_only", "keep_digits", "only_digits", "numeric_only"}


# ----------------------------------------------------------------------------------------------------------------------------------- the files

def read_sap(path):
    """The SAP business partners of the file, whatever the envelope ({"d": {"results": [...]}}, {"results": [...]}, a bare array)."""
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    if isinstance(doc, dict) and isinstance(doc.get("d"), dict):
        doc = doc["d"]
    if isinstance(doc, dict):
        doc = doc.get("results")
    return doc if isinstance(doc, list) else []


def read_sf(path):
    with open(path, encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter=","))


def read_web(path):
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    if isinstance(doc, dict):
        doc = doc.get("submissions")
    return doc if isinstance(doc, list) else []


def check_files(paths):
    """(problems, counts, data): the parsed fixtures against the numbers the scenario asserts later (150 / 120 / 90, the keys, the envelopes, the nested collections, the identifiers)."""
    problems = []
    counts = {}
    data = {"SAP": [], "SALESFORCE": [], "WEB": []}
    for app, reader in (("SAP", read_sap), ("SALESFORCE", read_sf), ("WEB", read_web)):
        try:
            data[app] = reader(paths[app])
        except (OSError, ValueError, KeyError) as exc:
            problems.append("%s unreadable: %s" % (G.NAMES[app], exc))
        counts[app] = len(data[app])
        if counts[app] != B2B_ROWS[app]:
            problems.append("%s: %d row(s), expected %d" % (G.NAMES[app], counts[app], B2B_ROWS[app]))
        keys = [L.text(r.get(B2B_KEYS[app])) for r in data[app]]
        if len(set(keys)) != len(keys) or any(not k for k in keys):
            problems.append("%s: the key %s is empty or not unique" % (G.NAMES[app], B2B_KEYS[app]))
    for r in data["SAP"]:
        if not re.match(r"^\d{10}$", L.text(r.get("BusinessPartner"))) or L.text(r.get("BusinessPartnerCategory")) != "2":
            problems.append("SAP partner %s: not a 10-digit number / category 2" % r.get("BusinessPartner"))
            break
        if not r.get("to_BusinessPartnerAddress", {}).get("results") or not r.get("to_BusinessPartnerTax", {}).get("results"):
            problems.append("SAP partner %s: an address or tax collection is empty" % r.get("BusinessPartner"))
            break
    if data["SALESFORCE"] and list(data["SALESFORCE"][0].keys()) != G.SF_COLUMNS:
        problems.append("%s: columns %s" % (G.SF_FILE, ",".join(data["SALESFORCE"][0].keys())))
    if any(len(r["Id"]) != 18 or not r["Id"].startswith("001") for r in data["SALESFORCE"]):
        problems.append("%s: an Id is not an 18-character 001 Id" % G.SF_FILE)
    if data["WEB"] and any(k not in data["WEB"][0] for k in ("submissionId", "submittedAt", "companyName", "siren", "country", "contactEmail", "consentMarketing")):
        problems.append("%s: a submission lacks a column" % G.WEB_FILE)
    return problems, counts, data


def web_siren_expectation(web_rows):
    """(typed, usable): the web submissions that typed a SIREN (any form), and those whose SIREN is a Luhn-valid nine digits once its spaces are removed."""
    typed = usable = 0
    for r in web_rows:
        raw = L.text(r.get("siren"))
        if raw.strip():
            typed += 1
            if G.siren_valid(re.sub(r"\s", "", raw)):
                usable += 1
    return typed, usable


def sf_identifier_expectation(sf_rows):
    """(rows with a SIRET, rows with a VAT number)."""
    return sum(1 for r in sf_rows if L.text(r.get("SIRET__c")).strip()), sum(1 for r in sf_rows if L.text(r.get("VAT_Number__c")).strip())


# ----------------------------------------------------------------------------------------------------------------------------------- pure assertion logic

def cleans_spaces(pipeline, steps):
    """True when a mapping's pipeline or one of its live MappingStep rows removes the spaces of an identifier: NORMALISE_IDENTIFIER (its blank argument means space, dot and dash),
    STRIP_CHARS with a space in its argument, a digits-only step."""
    for name, arg in L.pipeline_steps(pipeline):
        if name in ("normalise_identifier", "normalize_identifier") and (arg == "" or " " in arg):
            return True
        if name in ("strip", "strip_chars", "remove_chars", "delete_chars", "replace_chars") and " " in arg:
            return True
        if name in ("digits", "digits_only", "keep_digits", "only_digits", "numeric_only"):
            return True
    for s in steps:
        if not L.live(s):
            continue
        kind, arg = L.text(s.get("kind")).lower(), L.text(s.get("argument"))
        if kind in ("normalise_identifier", "normalize_identifier") and (arg.strip() == "" or " " in arg):
            return True
        if kind == "strip_chars" and " " in arg:
            return True
        if kind in ("digits", "digits_only", "keep_digits", "only_digits", "numeric_only"):
            return True
    return False


def normalises_country(pipeline, steps):
    """True when a mapping's pipeline or one of its live steps turns a country into an ISO2 code: COUNTRY_TO_ISO2, or a TRANSCODE_REFERENCE (a reference or code table)."""
    for name, _arg in L.pipeline_steps(pipeline):
        if name in COUNTRY_STEP_KINDS or "iso2" in name or "country" in name:
            return True
    for s in steps:
        if L.live(s) and L.text(s.get("kind")).lower() in COUNTRY_STEP_KINDS:
            return True
    return False


def address_entity(entities, feeds_by_entity, org_id):
    """(entity, is_child): the address entity the model proposed - a live entity other than the organisation that is named after addresses and/or has a feed whose parent feed is a
    feed of the organisation. Named AND child first. (None, False) when there is none."""
    org_feeds = set(L.text(f.get("id")) for f in feeds_by_entity.get(org_id, []))
    best = None
    for e in entities:
        eid = L.text(e.get("id"))
        if eid == org_id or not L.live(e):
            continue
        feeds = feeds_by_entity.get(eid, [])
        child = any(L.text(f.get("parentFeed")) in org_feeds for f in feeds if L.text(f.get("parentFeed")))
        named = bool(re.search(r"addr|adress", L.text(e.get("name")), re.IGNORECASE))
        if not (child or named):
            continue
        rank = (named, child, len(feeds))
        if best is None or rank > best[0]:
            best = (rank, e, child)
    return (best[1], best[2]) if best else (None, False)


def iso2_ok(value):
    return bool(ISO2.match(L.text(value).strip()))


def rows_of_source(rows, source_field, source_code):
    return [r for r in rows if L.text(r.get(source_field)) == source_code]


def country_problems(rows, field, source_field, expected_by_key=None, key_field="sourceId"):
    """(not_iso2, mismatches, resolved): the rows whose `field` is empty or not two capital letters; the rows whose country differs from the truth's for the same source key
    (`expected_by_key` = {source key: ISO2}); how many rows could be tied to a source key of the truth."""
    not_iso2, mismatches, resolved = [], [], 0
    expected_by_key = expected_by_key or {}
    for r in rows:
        value = L.text(r.get(field)).strip()
        if not iso2_ok(value):
            not_iso2.append(r)
            continue
        sid = L.text(r.get(key_field))
        want = expected_by_key.get(sid) or expected_by_key.get(sid.split("|")[0])
        if want:
            resolved += 1
            if want != value:
                mismatches.append(r)
    return not_iso2, mismatches, resolved


def desk_summary(lines):
    """What the Validation desk would show after the bulk accept: {nature: [line, ...]} with the policy decision each line will get, in the nature order of the policy."""
    by_nature = {}
    for line in L.order_lines(lines):
        decision, why = L.decision_for(line)
        by_nature.setdefault(line.get("nature", ""), []).append({"id": L.text(line.get("id")), "element": L.text(line.get("element")), "proposition": L.text(line.get("proposition")),
                                                                   "origin": L.text(line.get("origin")), "confidence": line.get("confidence"), "status": L.text(line.get("status")),
                                                                   "rationale": client.clip(L.text(line.get("rationale")), 300), "policy": decision, "why": why})
    return by_nature


def landing_envelope_hint(app, expected, loaded, envelope):
    """The sentence that explains a landing with the wrong row count for the SAP file: the OData envelope is one document to a reader that unwraps only the well-known keys."""
    if app != "SAP" or loaded == expected:
        return ""
    if loaded == 1 and envelope == "odata":
        return ("SAP landed ONE row: the sample-file connector reads {\"d\": {\"results\": [...]}} as a single document (it unwraps rows/items/data/results/value/records at the top, "
                "or a single array property next to pagination metadata, not an OData `d` wrapper). Run again with E2E_SAP_ENVELOPE=results to flatten the envelope, and report the OData "
                "shape to the Connectors owners")
    return "SAP landed %d row(s) instead of %d" % (loaded, expected)


# ----------------------------------------------------------------------------------------------------------------------------------- the scenario

class B2BScenario(S.Scenario):
    CODE_PREFIX = "E2EB2B"
    TITLE = "B2B end-to-end scenario"
    REPORT_NAME = "b2b"
    PROJECT_LABEL = "B2B end to end"
    FILES = B2B_FILES
    APPS = ("SAP", "SALESFORCE", "WEB")
    ROWS = B2B_ROWS
    KEYS = B2B_KEYS
    EXPLICIT_KEY = set()        # every key is the suggestion validated as it stands when it is already the expected column, otherwise the expected column is chosen
    STEP_NAMES = B2B_STEPS
    FILES_WORD = "three"
    REQUIRED_PERSON_APPS = ("SAP", "SALESFORCE", "WEB")
    KEEP_HINT = "run again with --cleanup, or use --cleanup-project <code>, to remove them"

    def __init__(self, *args, **kwargs):
        S.Scenario.__init__(self, *args, **kwargs)
        self.details = {}
        self.report_data["details"] = self.details
        self.truth = None
        self.data = {}              # app -> the rows of the generated file, read back
        self.org = None
        self.address = None
        self.address_child = False
        self.entities = []
        self.live_entities = []
        self.feeds = {}             # entity id -> live feeds
        self.attributes = []        # live attributes of live entities
        self.mappings = []          # live mappings of those feeds
        self.col_by_id = {}
        self.app_of_table = {}
        self.steps_cache = {}
        self.sap_envelope = self.env.get("E2E_SAP_ENVELOPE", "odata")

    # ------------------------------------------------------------------------------------------------------------------ 2. the files
    def make_files(self):
        web_envelope = self.env.get("E2E_WEB_ENVELOPE", "submissions")
        paths, truth = G.write(self.workdir, self.sap_envelope, web_envelope)
        self.truth = truth
        problems, counts, self.data = check_files(paths)
        self.rep.check("2", "generated files match the fixtures (150 / 120 / 90)", not problems, "SAP %(SAP)d, SALESFORCE %(SALESFORCE)d, WEB %(WEB)d" % counts, "; ".join(problems))
        if problems:
            raise S.Stop()
        summary = dict((k, v) for k, v in truth["summary"].items() if k != "expectedIso2")
        self.details["groundTruth"] = summary
        for line in G.describe(truth):
            self.rep.info("2", "ground truth", line)
        try:
            out_dir = self.env.get("E2E_OUT_DIR", "e2e-out")
            os.makedirs(out_dir, exist_ok=True)
            target = os.path.join(out_dir, "b2b-truth-%s.json" % self.code)
            shutil.copyfile(os.path.join(self.workdir, G.TRUTH_FILE), target)
            self.details["groundTruthFile"] = target
        except OSError:
            pass
        return paths

    # ------------------------------------------------------------------------------------------------------------------ 3. landing
    def step3(self):
        try:
            S.Scenario.step3(self)
        finally:
            try:
                self.landing_facts()
            except S.Stop:
                raise
            except Exception as exc:    # an INFO never fails a run
                self.rep.info("3", "what the landing produced", "not readable (%s)" % client.clip(str(exc)))

    def landing_facts(self):
        """INFO: what the landing actually produced for the SAP file's nested collections (child landing tables or columns), and the explanation of a wrong row count."""
        if "SAP" not in self.tables or "SAP" not in self.sources:
            return
        resp, table = self.ds.get_row("LandingTable", self.tables["SAP"]["id"])
        loaded = L.to_int((table or {}).get("lastLoadRows"), -1)
        hint = landing_envelope_hint("SAP", self.ROWS["SAP"], loaded, self.sap_envelope)
        if hint:
            self.rep.info("3", "SAP row count", hint)
        resp, tables = self.ds.select("LandingTable", "./projectSource='%s'" % self.sources["SAP"]["id"])
        resp, columns = self.ds.select("LandingColumn", "./landingTable='%s'" % self.tables["SAP"]["id"])
        names = [L.text(c.get("sourceColumn")) for c in columns]
        nested = [n for n in names if "." in n or "[" in n or n.lower().startswith("to_")]
        collections = {"address": [n for n in nested if "address" in n.lower()], "tax": [n for n in nested if "tax" in n.lower()]}
        self.details["landing"] = {"sapLandingTables": len(tables), "sapColumns": len(names), "sapNestedColumns": nested, "sapRowsLoaded": loaded}
        self.rep.info("3", "SAP nested collections in the landing",
                      "%d landing table(s) for the SAP file (%s), %d column(s), %d row(s) loaded; the address collection arrives as %s, the tax collection as %s" % (
                          len(tables), "no child table" if len(tables) == 1 else "child tables: " + ", ".join(L.text(t.get("tableName")) for t in tables),
                          len(names), loaded, ", ".join(collections["address"][:6]) or "NOTHING (no column mentions it)", ", ".join(collections["tax"][:6]) or "NOTHING (no column mentions it)"))
        for app in ("SALESFORCE", "WEB"):
            if app in self.tables:
                resp, other = self.ds.select("LandingTable", "./projectSource='%s'" % self.sources[app]["id"])
                if len(other) != 1:
                    self.rep.info("3", "%s landing tables" % app, "%d landing table(s) for a flat file" % len(other))

    # ------------------------------------------------------------------------------------------------------------------ the model of the proposal
    def load_model(self):
        """Reads the proposal: the live entities, their live feeds, attributes and mappings (over every entity, the identifiers may sit in a child entity)."""
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        self.entities = entities
        self.live_entities = [e for e in entities if L.live(e)]
        self.feeds, self.attributes, self.mappings = {}, [], []
        self.col_by_id = {}
        for cols in self.columns.values():
            for c in cols:
                self.col_by_id[L.text(c["id"])] = c
        self.app_of_table = dict((L.text(t["id"]), app) for app, t in self.tables.items())
        feed_ids = []
        for e in self.live_entities:
            eid = L.text(e["id"])
            resp, feeds = self.ds.select("TransferFeed", "./entity='%s'" % eid)
            self.feeds[eid] = [f for f in feeds if L.live(f)]
            feed_ids += [L.text(f["id"]) for f in self.feeds[eid]]
            resp, attrs = self.ds.select("StagingAttribute", "./entity='%s'" % eid)
            self.attributes += [a for a in attrs if L.live(a)]
        for i in range(0, len(feed_ids), 10):
            resp, rows = self.ds.select("AttributeMapping", " or ".join("./feed='%s'" % f for f in feed_ids[i:i + 10]))
            self.mappings += [m for m in rows if L.live(m)]
        self.attr_by_id = dict((L.text(a["id"]), a) for a in self.attributes)
        self.steps_cache = {}

    def column_of(self, mapping):
        return self.col_by_id.get(L.text(mapping.get("landingColumn")), {})

    def app_of(self, mapping):
        return self.app_of_table.get(L.text(self.column_of(mapping).get("landingTable")), "")

    def reading(self, roles, app):
        """The live mappings that read a column of `roles` (lower-case names) of the application `app`."""
        return [m for m in self.mappings if self.app_of(m) == app and L.text(self.column_of(m).get("sourceColumn")).lower() in roles]

    def steps_of(self, mapping):
        mid = L.text(mapping["id"])
        if mid not in self.steps_cache:
            resp, steps = self.ds.select("MappingStep", "./mapping='%s'" % mid)
            self.steps_cache[mid] = steps if resp.status == 200 else []
        return self.steps_cache[mid]

    def attributes_of(self, mappings):
        out, seen = [], set()
        for m in mappings:
            a = self.attr_by_id.get(L.text(m.get("attribute")))
            if a is not None and L.text(a["id"]) not in seen:
                seen.add(L.text(a["id"]))
                out.append(a)
        return out

    def entity_name(self, entity_id):
        for e in self.entities:
            if L.text(e["id"]) == L.text(entity_id):
                return L.text(e.get("name"))
        return "?"

    def apps_feeding(self, entity_id):
        return sorted(set(self.app_of_table.get(t, "") for t in L.feed_tables(self.feeds.get(L.text(entity_id), []))) - {""})

    def describe_attribute(self, attr):
        return "%s.%s" % (self.entity_name(attr.get("entity")), L.text(attr.get("name")))

    # ------------------------------------------------------------------------------------------------------------------ 4. proposal
    def step4(self):
        self.start_job("4", "staging proposal (language model on, as configured)", "/projects/%s/staging/propose?quality=true&capability=true" % self.code,
                       self.timeout("PROPOSAL", 1500), "the staging proposal")
        self.load_model()
        alone = L.rule_entities_left_alone(self.entities)
        self.rep.check("4", "no rule entity left PROPOSED alone", not alone, "%d entities, rule entities superseded" % len(self.entities),
                       "still PROPOSED: %s" % ", ".join(L.text(e.get("name")) for e in alone))
        self.org = L.person_entity(self.entities)
        self.person = self.org
        if self.org is None:
            self.rep.failed("4", "one organisation entity unifies the three sources", "no entity unifies the source entities; entities: %s" % ", ".join(
                "%s(%s members, %s)" % (L.text(e.get("name")), len(L.as_list(e.get("memberEntities"))), L.text(e.get("status"))) for e in self.entities))
            raise S.Stop()
        members = sorted(set(L.as_list(self.org.get("memberEntities"))))
        member_names = [self.entity_name(m) for m in members]
        self.rep.check("4", "one organisation entity unifies the three sources (%s)" % L.text(self.org.get("name")), len(members) >= 3,
                       "memberEntities: %s" % ", ".join(member_names), "only %d member(s): %s (expected the 3 rule entities of SAP, SALESFORCE and WEB)" % (len(members), ", ".join(member_names)))
        org_id = L.text(self.org["id"])
        fed = self.apps_feeding(org_id)
        missing = [a for a in self.APPS if a not in fed]
        self.rep.check("4", "the organisation entity is fed by SAP, SALESFORCE and WEB", not missing, "feeds read the landing tables of %s" % ", ".join(fed), "not fed by: %s (fed by %s)" % (", ".join(missing), ", ".join(fed) or "nothing"))
        self.check_address_entity()
        self.check_identifiers_proposed()
        self.check_normalisation("4")
        self.info_modelling()

    def check_address_entity(self):
        org_id = L.text(self.org["id"])
        self.address, self.address_child = address_entity(self.live_entities, self.feeds, org_id)
        if self.address is None:
            self.rep.failed("4", "an address entity is proposed (child of the organisation)", "no live entity is named after addresses or has a feed under an organisation feed; entities: %s" %
                            ", ".join(L.text(e.get("name")) for e in self.live_entities))
            return
        fed = self.apps_feeding(self.address["id"])
        self.rep.passed("4", "an address entity is proposed (child of the organisation)", "%s%s, fed by %s" % (L.text(self.address.get("name")),
                                                                                                          "" if self.address_child else " (named after addresses, but no feed under an organisation feed)", ", ".join(fed)))
        self.rep.check("4", "the address entity is fed by SAP (the address collection)", "SAP" in fed, "yes", "fed by %s only" % (", ".join(fed) or "nothing"))
        self.rep.check("4", "the address entity is fed by SALESFORCE (the billing fields)", "SALESFORCE" in fed, "yes", "fed by %s only" % (", ".join(fed) or "nothing"))
        self.rep.info("4", "the website form and the address entity", "the web form %s the address entity (its only address datum is the country)" % ("feeds" if "WEB" in fed else "does not feed"))

    def check_identifiers_proposed(self):
        for label, roles, app in (("SIREN typed on the website", SIREN_COLUMNS, "WEB"), ("SIRET of Salesforce", SIRET_COLUMNS, "SALESFORCE"), ("VAT number of Salesforce", VAT_COLUMNS, "SALESFORCE")):
            attrs = self.attributes_of(self.reading(roles, app))
            self.rep.check("4", "%s is carried" % label, bool(attrs), ", ".join(self.describe_attribute(a) for a in attrs), "no live mapping reads the %s column of the %s file" % ("/".join(sorted(roles)), app))
        sap_tax = [m for m in self.mappings if self.app_of(m) == "SAP" and "tax" in L.text(self.column_of(m).get("sourceColumn")).lower()]
        self.rep.info("4", "SAP identifiers (the nested tax collection FR1 SIREN / FR0 VAT)", "carried by %s" % ", ".join(sorted(set(self.describe_attribute(a) for a in self.attributes_of(sap_tax)))) if sap_tax
                      else "no mapping reads the tax collection: the SIREN and the VAT of the SAP partners are not carried (a nested array)")

    def check_normalisation(self, step, suffix=""):
        for label, roles, app in (("Salesforce BillingCountry", SF_COUNTRY_COLUMNS, "SALESFORCE"), ("web country", WEB_COUNTRY_COLUMNS, "WEB")):
            links = self.reading(roles, app)
            bare = [L.text(m["id"]) for m in links if not normalises_country(L.text(m.get("pipeline")), self.steps_of(m))]
            self.rep.check(step, "%s is normalised to ISO2 by a step%s" % (label, suffix), bool(links) and not bare, "%d link(s), each with a country / reference step" % len(links),
                           ("no mapping reads the column" if not links else "mapping(s) %s have no country step" % ", ".join(bare)))
        links = self.reading(SIREN_COLUMNS, "WEB")
        bare = [L.text(m["id"]) for m in links if not cleans_spaces(L.text(m.get("pipeline")), self.steps_of(m))]
        self.rep.check(step, "web SIREN is cleaned of its spaces by a step%s" % suffix, bool(links) and not bare, "%d link(s), each with an identifier-cleaning step" % len(links),
                       ("no mapping reads the siren column" if not links else "mapping(s) %s have no step that removes the spaces" % ", ".join(bare)))

    def info_modelling(self):
        """What the model chose where it is a judgement call, reported and never failed: the entities, what feeds them, the attributes, the landing columns nothing reads."""
        listing = []
        for e in self.live_entities:
            eid = L.text(e["id"])
            attrs = [L.text(a.get("name")) for a in self.attributes if L.text(a.get("entity")) == eid]
            listing.append({"entity": L.text(e.get("name")), "origin": L.text(e.get("origin")), "status": L.text(e.get("status")), "fedBy": self.apps_feeding(eid), "attributes": attrs})
            self.rep.info("4", "entity %s (%s, %s)" % (L.text(e.get("name")), L.text(e.get("origin")), L.text(e.get("status"))),
                          "fed by %s; %d attribute(s): %s" % (", ".join(self.apps_feeding(eid)) or "no feed", len(attrs), ", ".join(attrs[:30])))
        self.details["entities"] = listing
        mapped = set(L.text(m.get("landingColumn")) for m in self.mappings)
        unread = {}
        for tid, cols in self.columns.items():
            app = self.app_of_table.get(L.text(tid), "")
            names = [L.text(c.get("sourceColumn")) for c in cols if L.text(c["id"]) not in mapped]
            if names:
                unread[app] = names
        self.details["landingColumnsNoMappingReads"] = unread
        for app in self.APPS:
            if app in unread:
                self.rep.info("4", "%s columns no live mapping reads" % app, ", ".join(unread[app][:30]))

    # ------------------------------------------------------------------------------------------------------------------ 5. decisions
    def step5(self):
        threshold = int(self.env.get("E2E_THRESHOLD", "60"))
        self.start_job("5", "accept every proposal at %d %% or above" % threshold, "/projects/%s/staging/decide?accept=true&threshold=%d" % (self.code, threshold),
                       self.timeout("DECIDE", 900), "the bulk accept")
        self.validation_desk()
        self.decide_queue()
        self.provide_parameters()
        self.load_model()
        self.check_normalisation("5", " (accepted state)")

    def validation_desk(self):
        """What is LEFT after the bulk accept, by nature, before any policy decision: it is what the Validation desk would show, for a person to compare with the screen."""
        data = self.review()
        lines = data["lines"]
        by_nature = desk_summary(lines)
        self.details["validationDesk"] = {"leftAfterBulkAccept": len(lines), "countsByStatus": data.get("counts", {}), "byNature": by_nature}
        self.rep.info("5", "Validation desk after the bulk accept", "%d line(s) left PROPOSED (%s)" % (len(lines), ", ".join("%s %d" % (n, len(v)) for n, v in sorted(by_nature.items())) or "nothing"))
        for nature, items in sorted(by_nature.items()):
            for item in items[:8]:
                self.rep.info("5", "desk: %s %s" % (nature, item["element"] or item["id"]), "%s, %s %s%%, policy -> %s (%s)%s" % (
                    item["origin"], "confidence", item["confidence"], item["policy"], item["why"], "; " + item["rationale"] if item["rationale"] else ""))
            if len(items) > 8:
                self.rep.info("5", "desk: %s" % nature, "... and %d more line(s), all in the JSON report (details.validationDesk)" % (len(items) - 8))

    # ------------------------------------------------------------------------------------------------------------------ 6. build, load, verify
    def source_code(self, app):
        return L.text(self.sources[app].get("sourceSystem")) or app

    def reject_listing(self, rejects):
        """'reason x n' for a list of TransferReject rows."""
        tally = L.tally_by(rejects, "reasonCode")
        return "; ".join("%s x%d" % (k or "(no reason code)", n) for k, n in sorted(tally.items(), key=lambda kv: -kv[1])[:8])

    def verify(self):
        self.check_constraints_applied()
        resp, project = self.ds.get_row("Project", self.code)
        space, dataset = L.text(project.get("stagingDataspace")), L.text(project.get("stagingDataset"))
        if not space or not dataset:
            self.rep.failed("6", "staging dataset recorded on the project", "stagingDataspace/stagingDataset empty")
            raise S.Stop()
        self.stg = client.Ds(self.admin, space, dataset)
        self.load_model()
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        active = [e for e in entities if L.text(e.get("status")) not in ("SUPERSEDED", "REJECTED", "PROPOSED")]
        self.rep.check("6", "built entities", bool(active), ", ".join(L.text(e.get("name")) for e in active), "no entity is ACCEPTED/APPLIED after the load")
        resp, rejects = self.ds.select("TransferReject", "./project='%s'" % self.code)
        self.details["rejects"] = {"total": len(rejects), "byReason": L.tally_by(rejects, "reasonCode"), "byCategory": L.tally_by(rejects, "category")}
        by_entity = {}
        for rj in rejects:
            by_entity.setdefault(L.text(rj.get("entity")), []).append(rj)
        rows_of = {}
        counts = {}
        for e in active:
            name = L.text(e.get("name"))
            eid = L.text(e["id"])
            resp, rows = self.stg.select(self.table_path(e), None, page_size=500, max_pages=100)
            if resp.status != 200:
                self.rep.failed("6", "%s: staging table readable" % name, "HTTP %d %s" % (resp.status, client.clip(resp.error_text())))
                continue
            rows_of[eid] = rows
            counts[name] = len(rows)
            written = L.to_int(e.get("lastLoadWritten")) + L.to_int(e.get("lastLoadUnchanged"))
            if L.no_feed(e):
                self.rep.info("6", "%s: no feed (nothing loaded, said on the row)" % name, "%d row(s) in the table, last load: %s" % (len(rows), client.clip(L.text(e.get("lastLoadMessage")), 160)))
                continue
            self.rep.check("6", "%s: rows written" % name, len(rows) > 0 and written > 0, "%d row(s) in the table, %d written/unchanged by the last load" % (len(rows), written),
                           "%d row(s) in the table, last load: %s" % (len(rows), client.clip(L.text(e.get("lastLoadMessage")), 160)))
            mine = by_entity.get(eid, [])
            self.rep.check("6", "%s: 0 TransferReject" % name, not mine and L.to_int(e.get("lastLoadRejected")) == 0, "none",
                           "%d reject row(s), last load rejected %s: %s" % (len(mine), L.text(e.get("lastLoadRejected")), self.reject_listing(mine) or "(no TransferReject row read)"))
        self.details["rowsPerEntity"] = counts
        if self.org is None or L.text(self.org["id"]) not in rows_of:
            self.rep.failed("6", "organisation entity rows readable", "the organisation entity has no readable table")
            return
        self.verify_organisation(rows_of[L.text(self.org["id"])])
        self.verify_address(rows_of)
        self.verify_identifiers(rows_of)
        self.verify_countries(rows_of)

    def verify_organisation(self, rows):
        by_source = dict((app, len(rows_of_source(rows, "sourceSystem", self.source_code(app)))) for app in self.APPS)
        self.rep.check("6", "organisation entity holds one row per source row (%d)" % B2B_TOTAL_ROWS, len(rows) == B2B_TOTAL_ROWS, "%d rows" % len(rows), "%d row(s), expected %d" % (len(rows), B2B_TOTAL_ROWS))
        self.rep.check("6", "organisation rows per source (SAP 150, SALESFORCE 120, WEB 90)", all(by_source[a] == self.ROWS[a] for a in self.APPS),
                       ", ".join("%s %d" % (a, by_source[a]) for a in self.APPS), ", ".join("%s %d (expected %d)" % (a, by_source[a], self.ROWS[a]) for a in self.APPS))

    def verify_address(self, rows_of):
        if self.address is None:
            self.rep.skipped("6", "address entity rows", "no address entity was proposed (step 4 says so)")
            return
        rows = rows_of.get(L.text(self.address["id"]))
        if rows is None:
            self.rep.skipped("6", "address entity rows", "the address entity %s was not built or not readable" % L.text(self.address.get("name")))
            return
        by_source = dict((app, len(rows_of_source(rows, "sourceSystem", self.source_code(app)))) for app in self.APPS)
        self.details["addressRowsPerSource"] = by_source
        self.rep.check("6", "address entity: one billing address per Salesforce account (120)", by_source["SALESFORCE"] == self.ROWS["SALESFORCE"], "%d" % by_source["SALESFORCE"],
                       "%d address row(s) from SALESFORCE, expected %d" % (by_source["SALESFORCE"], self.ROWS["SALESFORCE"]))
        exploded = self.truth["summary"]["sapAddresses"]
        self.rep.check("6", "address entity: at least one address per SAP partner (150)", by_source["SAP"] >= self.ROWS["SAP"], "%d (the file holds %d addresses in all: %s)" % (
            by_source["SAP"], exploded, "every collection item became a row" if by_source["SAP"] == exploded else "not every collection item became a row"),
                       "%d address row(s) from SAP, expected at least %d" % (by_source["SAP"], self.ROWS["SAP"]))
        self.rep.info("6", "address rows per source", ", ".join("%s %d" % (a, by_source[a]) for a in self.APPS) + " (%d row(s) in all)" % len(rows))

    def verify_identifiers(self, rows_of):
        typed, usable = web_siren_expectation(self.data.get("WEB", []))
        sf_siret, sf_vat = sf_identifier_expectation(self.data.get("SALESFORCE", []))
        web, sf = self.source_code("WEB"), self.source_code("SALESFORCE")
        found = {}

        def loaded(label, roles, app, code):
            attrs = self.attributes_of(self.reading(roles, app))
            if not attrs:
                self.rep.skipped("6", label, "no live mapping reads the column (step 4 says so)")
                return None
            values = []
            for a in attrs:
                rows = rows_of.get(L.text(a.get("entity")))
                if rows is None:
                    continue
                values += [L.text(r.get(L.text(a.get("name")))).strip() for r in rows if L.text(r.get("sourceSystem")) == code and L.non_empty(r.get(L.text(a.get("name"))))]
            found[label] = [self.describe_attribute(a) for a in attrs]
            return values
        values = loaded("web SIREN loaded", SIREN_COLUMNS, "WEB", web)
        if values is not None:
            clean = [v for v in values if v.isdigit() and len(v) == 9]
            valid = sum(1 for v in values if G.siren_valid(v))
            self.rep.check("6", "web SIREN loaded cleaned: %d typed, all nine digits, %d Luhn-valid" % (typed, usable), len(values) == typed and len(clean) == len(values) and valid == usable,
                           "%d loaded, %d clean (nine digits), %d Luhn-valid, in %s" % (len(values), len(clean), valid, ", ".join(found["web SIREN loaded"])),
                           "%d loaded (typed %d), %d of them nine digits without spaces, %d Luhn-valid (expected %d)" % (len(values), typed, len(clean), valid, usable))
            self.rep.info("6", "SIREN fill rate on the web rows", "%d of %d web rows (%.1f %%); Luhn-valid %d of %d loaded" % (len(values), self.ROWS["WEB"], 100.0 * len(values) / self.ROWS["WEB"], valid, len(values)))
        values = loaded("Salesforce SIRET loaded", SIRET_COLUMNS, "SALESFORCE", sf)
        if values is not None:
            valid = sum(1 for v in values if G.siret_valid(re.sub(r"\s", "", v)))
            self.rep.check("6", "Salesforce SIRET loaded: %d filled in the file, all Luhn-valid" % sf_siret, len(values) == sf_siret and valid == len(values), "%d loaded, %d Luhn-valid" % (len(values), valid),
                           "%d loaded (file: %d), %d Luhn-valid" % (len(values), sf_siret, valid))
        values = loaded("Salesforce VAT loaded", VAT_COLUMNS, "SALESFORCE", sf)
        if values is not None:
            compact = [re.sub(r"[\s.\-]", "", v).upper() for v in values]
            fr = [v for v in compact if v.startswith("FR")]
            fr_ok = sum(1 for v in compact if G.fr_vat_valid(v))
            spaced = sum(1 for v in values if re.search(r"\s", v))
            self.rep.check("6", "Salesforce VAT loaded: %d filled in the file" % sf_vat, len(values) == sf_vat, "%d loaded; %d French, %d with a valid key; %d kept spaces as typed" % (len(values), len(fr), fr_ok, spaced),
                           "%d loaded (file: %d)" % (len(values), sf_vat))
            if spaced:
                self.rep.info("6", "VAT numbers with spaces", "%d loaded value(s) keep the spaces typed in Salesforce (no identifier cleaning on that link)" % spaced)

    def verify_countries(self, rows_of):
        fed = self.attributes_of(self.reading(SF_COUNTRY_COLUMNS, "SALESFORCE") + self.reading(WEB_COUNTRY_COLUMNS, "WEB"))
        address_fields = []
        if self.address is not None:
            address_fields = [a for a in self.attributes if L.text(a.get("entity")) == L.text(self.address["id"]) and re.search(r"country|pays", L.text(a.get("name")), re.IGNORECASE)]
        expected = self.truth["summary"]["expectedIso2"]
        sf, web = self.source_code("SALESFORCE"), self.source_code("WEB")
        # 1. every loaded address has an ISO2 country
        if self.address is not None and rows_of.get(L.text(self.address["id"])) is not None and address_fields:
            rows = rows_of[L.text(self.address["id"])]
            bad = []
            for a in address_fields:
                not_iso2, _m, _r = country_problems(rows, L.text(a.get("name")), "sourceSystem")
                bad += not_iso2
            bad_by_source = L.tally_by(bad, "sourceSystem")
            self.rep.check("6", "every loaded address has an ISO2 country", not bad, "%d address row(s), country %s" % (len(rows), ", ".join(L.text(a.get("name")) for a in address_fields)),
                           "%d of %d address row(s) have no ISO2 country (by source: %s); e.g. %s" % (len(bad), len(rows), bad_by_source, [L.text(r.get(L.text(address_fields[0].get("name")))) for r in bad[:4]]))
        else:
            self.rep.skipped("6", "every loaded address has an ISO2 country", "no address entity or no country attribute on it")
        # 2. the Salesforce names and the web's free text become the RIGHT code
        if not fed:
            self.rep.skipped("6", "country of the Salesforce and web rows is the right ISO2 code", "no live mapping reads BillingCountry or country")
            return
        total_bad, total_wrong, total_resolved, total = 0, 0, 0, 0
        detail = []
        for a in fed:
            rows = rows_of.get(L.text(a.get("entity")))
            if rows is None:
                continue
            field = L.text(a.get("name"))
            for code, app in ((sf, "SALESFORCE"), (web, "WEB")):
                if not self.reading(SF_COUNTRY_COLUMNS if app == "SALESFORCE" else WEB_COUNTRY_COLUMNS, app):
                    continue
                mine = [r for r in rows_of_source(rows, "sourceSystem", code) if L.non_empty(r.get(field))]
                not_iso2, wrong, resolved = country_problems(mine, field, "sourceSystem", expected.get(app))
                total += len(mine)
                total_bad += len(not_iso2)
                total_wrong += len(wrong)
                total_resolved += resolved
                detail.append("%s %s: %d value(s), %d not ISO2, %d wrong code, %d tied to the truth" % (app, self.describe_attribute(a), len(mine), len(not_iso2), len(wrong), resolved))
        self.rep.check("6", "country of the Salesforce and web rows is the right ISO2 code", total > 0 and total_bad == 0 and total_wrong == 0, "; ".join(detail),
                       "; ".join(detail) or "no loaded value")
        if total and not total_resolved:
            self.rep.info("6", "country against the truth", "no loaded row could be tied to a source key of the ground truth (the staging sourceId has another shape): only the ISO2 form was checked")

    # ------------------------------------------------------------------------------------------------------------------ report
    def epilogue(self):
        path = self.details.get("groundTruthFile")
        if path:
            self.out.write("\nground truth of the files (company -> keys in each source): %s\n" % path)
        desk = self.details.get("validationDesk")
        if desk:
            self.out.write("Validation desk after the bulk accept: %d line(s) left (details.validationDesk in the JSON report)\n" % desk["leftAfterBulkAccept"])


# ----------------------------------------------------------------------------------------------------------------------------------- command line

def dry_run(out):
    work = tempfile.mkdtemp(prefix="b2b-e2e-dry-")
    try:
        paths, truth = G.write(work, os.environ.get("E2E_SAP_ENVELOPE", "odata"), os.environ.get("E2E_WEB_ENVELOPE", "submissions"))
        problems, counts, _data = check_files(paths)
        out.write("files generated by gen_b2b.py (seeded): %s\n" % ", ".join("%s %d" % (a, counts.get(a, -1)) for a in B2BScenario.APPS))
        for line in G.describe(truth):
            out.write("  %s\n" % line)
        out.write("  ground truth: %s (company -> keys in each source)\n" % G.TRUTH_FILE)
        for p in problems:
            out.write("PROBLEM %s\n" % p)
        out.write("\nplan (nothing is sent):\n")
        for n, name in sorted(B2B_STEPS.items()):
            out.write("  %d. %s\n" % (n, name))
        out.write("\nroutes needed:\n")
        for method, template, _b, label in S.REQUIRED_ROUTES + S.OPTIONAL_ROUTES:
            out.write("  %-4s %-48s %s\n" % (method, template, label))
        return 1 if problems else 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main(argv, env, out=None):
    out = out or sys.stdout
    if "--selftest" in argv:
        import b2b_selftest
        return b2b_selftest.run(out)
    return S.main(argv, env, out, cls=B2BScenario, max_step=6, dry=dry_run, doc=__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))
