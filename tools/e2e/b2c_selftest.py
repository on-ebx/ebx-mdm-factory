"""Self-test of the B2C end-to-end scenario (lot H2): the assertion logic on canned data, the fixtures, and the scenario's wiring against a stand-in server on
127.0.0.1. No EBX instance is involved, so tools/test_local.sh runs it. Run alone: tools/e2e/b2c_scenario.sh --selftest"""
import io
import json
import os
import shutil
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import e2e_lib as L  # noqa: E402

FAILURES = []


def check(label, ok, detail=""):
    if not ok:
        FAILURES.append(label)
        sys.stdout.write("FAILED %s %s\n" % (label, detail))


def test_fixtures():
    import b2c_scenario as S
    work = tempfile.mkdtemp(prefix="b2c-selftest-")
    try:
        paths = S.generate_files(work)
        problems, counts = S.check_files(paths)
        check("fixtures: no problem", not problems, str(problems))
        check("fixtures: 160 / 234 / 140", counts == {"ECOM": 160, "STORES": 234, "LOYALTY": 140}, str(counts))
        check("fixtures: 300 birth dates in the sources (160 web + 140 loyalty)", counts["ECOM"] + counts["LOYALTY"] == L.EXPECTED_BIRTH_DATES)
        check("fixtures: 534 person rows", sum(counts.values()) == L.EXPECTED_PERSON_ROWS)
        with open(paths["LOYALTY"], encoding="utf-8") as fh:
            header, first = fh.readline(), fh.readline()
        check("fixtures: loyalty birth dates are dd/MM/yyyy", first.split(",")[4].count("/") == 2, first)
        with open(paths["ECOM"], encoding="utf-8") as fh:
            doc = json.load(fh)
        check("fixtures: web phones carry a +", all(c["mobile"].startswith("+") for c in doc["customers"]))
        # a broken file is caught
        with open(paths["LOYALTY"], "w", encoding="utf-8") as fh:
            fh.write("card_number\nx\n")
        problems, _ = S.check_files(paths)
        check("fixtures: a one-column loyalty file is a problem", bool(problems))
        url = S.data_url("A b.csv", "text/csv", b"a,b\n1,2\n")
        check("data url has the name segment EBX requires", url.startswith("data:text/csv;name=A%20b.csv;base64,"), url)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def test_missed_pairs():
    """Lot M3: tools/e2e/b2c_missed_pairs.py on the generated files - the 224 pairs a plan on the e-mail key and the address keys cannot find (every one involves a store ticket
    without an e-mail), and the Java tests' fixture (b2c_person_keys.tsv) is exactly what the generator and the script write today."""
    import b2c_missed_pairs as M
    import b2c_scenario as S
    work = tempfile.mkdtemp(prefix="b2c-missed-")
    try:
        S.generate_files(work)
        recs = M.load_records(work)
        truth = M.load_truth(work)
        keys = M.parse_keys("email,address")
        true, found, false_pairs = M.analyse(recs, truth, keys)
        missed = [t for t in true if not M.found_by(t[2], keys)]
        check("missed pairs: 565 true pairs, 341 found, 224 missed", (len(true), len(found), len(missed)) == (565, 341, 224), str((len(true), len(found), len(missed))))
        check("missed pairs: every missed pair involves a ticket without an e-mail",
              all((a["app"] == "STORES" and a["emailKey"] is None) or (b["app"] == "STORES" and b["emailKey"] is None) for a, b, _ in missed))
        check("missed pairs: and shares the name, the phone and the postal code", all({"name", "phone", "postal"} <= set(s) for _, _, s in missed))
        check("missed pairs: the phone alone reaches all 565", len(M.analyse(recs, truth, M.parse_keys("phone"))[1]) == 565)
        out = os.path.join(work, "keys.tsv")
        M.write_tsv(recs, truth, out)
        committed = os.path.join(HERE, "..", "..", "ebx-mdm-factory", "src", "test", "resources", "com", "on", "ebx", "factory", "matching", "b2c_person_keys.tsv")
        with open(out, encoding="utf-8") as a, open(committed, encoding="utf-8") as b:
            check("missed pairs: the Java tests' fixture is what the generator writes", a.read() == b.read())
    finally:
        shutil.rmtree(work, ignore_errors=True)


def test_rationale_logic():
    en = "To check: no source column feeds this field, it would stay empty on every row (suggestion: reject it). Some other text."
    fr = "À vérifier : les mêmes colonnes source alimentent déjà Client, ce champ fait donc doublon sur Achat (proposition : le refuser)."
    check("check proposing a rejection (en)", L.check_proposes_reject(en))
    check("check proposing a rejection (fr)", L.check_proposes_reject(fr))
    check("a plain rationale proposes nothing", not L.check_proposes_reject("Le prénom est un attribut de la personne."))
    check("a check that does not propose rejection", not L.check_proposes_reject("To check: something odd (look at it)."))
    check("a value check never proposes a rejection", not L.check_proposes_reject("Fine. || Value check [was 85]: 12 values do not convert (suggestion: reject it)."))
    check("check text in the middle of a rationale is not a leading check", not L.check_proposes_reject("Because. To check: x (suggestion: reject it)."))
    check("no note: not failing", not L.value_check_failing("Plain rationale."))
    check("a fixed note: not failing", not L.value_check_failing("R. || Value check: fixed. The format was dd/MM/yyyy."))
    check("an unfixed note: failing", L.value_check_failing("R. || Value check [was 99]: 140 of 140 values do not convert."))
    check("decision: external service rejected", L.decision_for({"nature": "EXTERNAL_SERVICE", "rationale": ""})[0] == "REJECTED")
    check("decision: check reject", L.decision_for({"nature": "ATTRIBUTE", "rationale": en})[0] == "REJECTED")
    check("decision: the rest accepted", L.decision_for({"nature": "MAPPING", "rationale": "ok"})[0] == "ACCEPTED")
    ordered = L.order_lines([{"nature": "MAPPING", "id": "2"}, {"nature": "ENTITY", "id": "9"}, {"nature": "ATTRIBUTE", "id": "5"}, {"nature": "ATTRIBUTE", "id": "3"}])
    check("decisions go entity, attribute, mapping", [(l["nature"], l["id"]) for l in ordered] == [("ENTITY", "9"), ("ATTRIBUTE", "3"), ("ATTRIBUTE", "5"), ("MAPPING", "2")])


def test_plus_and_pipelines():
    steps = L.pipeline_steps("trim|date(dd/MM/yyyy,yyyy-MM-dd)|strip(.-)")
    check("pipeline parsed", steps == [("trim", ""), ("date", "dd/MM/yyyy,yyyy-MM-dd"), ("strip", ".-")], str(steps))
    check("STRIP_CHARS with a space keeps the +", not L.step_drops_plus("STRIP_CHARS", " "))
    check("NORMALISE_IDENTIFIER '.+-' drops the +", L.step_drops_plus("NORMALISE_IDENTIFIER", ".+-"))
    check("digits drops the +", L.step_drops_plus("digits", ""))
    check("plain trim keeps the +", not L.step_drops_plus("TRIM", ""))
    found = L.plus_droppers("trim|digits", [{"kind": "NORMALISE_IDENTIFIER", "argument": ".+-", "status": "PROPOSED"},
                                            {"kind": "NORMALISE_IDENTIFIER", "argument": ".+-", "status": "REJECTED"}, {"kind": "STRIP_CHARS", "argument": " ", "status": "ACCEPTED"}])
    check("droppers: pipeline digits and the live normalise step, not the rejected one", len(found) == 2, str(found))


def test_entities_and_feeds():
    ents = [{"id": "177", "origin": "RULE", "status": "SUPERSEDED", "memberEntities": []},
            {"id": "178", "origin": "RULE", "status": "SUPERSEDED", "memberEntities": []},
            {"id": "179", "origin": "RULE", "status": "SUPERSEDED", "memberEntities": []},
            {"id": "180", "origin": "LLM", "status": "PROPOSED", "memberEntities": [{"content": "177"}, "178", {"key": "179"}]},
            {"id": "182", "origin": "LLM", "status": "PROPOSED", "memberEntities": ["178"]}]
    check("person entity = the one unifying three", L.person_entity(ents)["id"] == "180")
    check("no rule entity left alone", L.rule_entities_left_alone(ents) == [])
    ents2 = ents + [{"id": "183", "origin": "RULE", "status": "PROPOSED", "memberEntities": []}]
    check("a PROPOSED rule entity is left alone", [e["id"] for e in L.rule_entities_left_alone(ents2)] == ["183"])
    check("no unifying entity -> None", L.person_entity(ents[:3]) is None)
    feeds = [{"landingTable": "218", "status": "PROPOSED"}, {"landingTable": "219", "status": "REJECTED"}, {"landingTable": "220", "status": "ACCEPTED"}]
    check("feed tables skip the rejected feed", L.feed_tables(feeds) == {"218", "220"})
    binding = '{"value":"buyerPhone","defaultCountry":{"toBeProvided":"why"},"other":{"literal":"x"}}'
    check("toBeProvided names", L.to_be_provided(binding) == ["defaultCountry"])
    check("toBeProvided on garbage", L.to_be_provided("not json") == [] and L.to_be_provided(None) == [])
    check("scalar unwraps", L.scalar({"content": {"key": "X"}}) == "X" and L.text(None) == "" and L.as_list(None) == [] and L.as_list("a") == ["a"])


def test_measures():
    rows = [{"sourceSystem": "ECOM", "mobile": "+33 6", "e": "+336", "ln": "x", "k": "k1"},
            {"sourceSystem": "ECOM", "mobile": "0633", "e": "+3333", "ln": "y", "k": ""},
            {"sourceSystem": "STORES", "mobile": "0644", "e": "", "ln": "", "k": ""},
            {"sourceSystem": "STORES", "mobile": "", "e": "", "ln": "z", "k": "k3"}]
    check("web number without + is a violation", len(L.phone_plus_violations(rows, "mobile", "sourceSystem", "ECOM")) == 1)
    check("e164 report", L.e164_report(rows, "mobile", "e") == (3, 2, 0), str(L.e164_report(rows, "mobile", "e")))
    check("e164 odd value counted", L.e164_report([{"mobile": "1", "e": "336"}], "mobile", "e") == (1, 1, 1))
    check("key report", L.key_report(rows, "k", ["ln"]) == (3, 2), str(L.key_report(rows, "k", ["ln"])))
    check("rate of nothing is 1", L.rate(0, 0) == 1.0 and abs(L.rate(95, 100) - 0.95) < 1e-9)
    check("fill count", L.fill_count(rows, "mobile") == 3)
    # lot M15: the per-source phone check is blind-proof where the E.164 report is not
    gap = [{"sourceSystem": "ECOM", "mobile": "+33 6", "e": "+336"}, {"sourceSystem": "ECOM", "mobile": "+33 7", "e": "+337"},
           {"sourceSystem": "STORES", "mobile": "", "e": ""}, {"sourceSystem": "STORES", "mobile": "", "e": ""}]
    check("the E.164 report is blind to a source with no phone at all", L.e164_report(gap, "mobile", "e") == (2, 2, 0))
    check("per source: STORES has 2 rows and no phone", L.phone_by_source(gap, "mobile", "sourceSystem", ["ECOM", "STORES"]) == [("ECOM", 2, 2), ("STORES", 2, 0)])
    check("and is the one missing", L.sources_missing_phone(gap, "mobile", "sourceSystem", ["ECOM", "STORES"]) == [("STORES", 2, 0)])
    check("a source with no row in the entity is not judged here", L.sources_missing_phone(gap, "mobile", "sourceSystem", ["ECOM", "LOYALTY"]) == [])
    tables = {"ECOM": {"id": "1"}, "STORES": {"id": "2"}, "LOYALTY": {"id": "3"}, "CRM": {"id": "4"}}
    columns = {"1": [{"sourceColumn": "mobile", "fillRate": "100.0"}], "2": [{"sourceColumn": "TELEPHONE", "fillRate": 100}, {"sourceColumn": "NOM"}],
               "3": [{"sourceColumn": "telephone", "fillRate": "0"}], "4": [{"sourceColumn": "phone"}]}
    check("phone sources: filled phone columns by name, an empty one skipped, an unknown fill rate counts",
          L.phone_source_systems(tables, columns, {"ECOM": "ECOM-SYS"}) == {"ECOM-SYS": ["mobile"], "STORES": ["TELEPHONE"], "CRM": ["phone"]},
          str(L.phone_source_systems(tables, columns, {"ECOM": "ECOM-SYS"})))
    # lot M16: Data Services answers a LandingTable id as a NUMBER and the scenario keys the columns by that very value - the lookup by text(id) missed every table (run 11: SKIP although
    # STORES.TELEPHONE was filled 234/234); ids of either kind must be found, the column named by sourceColumn or, failing that, fieldName
    int_tables = {"ECOM": {"id": 207}, "STORES": {"id": 211}, "LOYALTY": {"id": 213}}
    int_columns = {207: [{"sourceColumn": "mobile", "fillRate": 100.0}], 211: [{"sourceColumn": "TELEPHONE", "fillRate": 100.0}, {"sourceColumn": "EMAIL", "fillRate": 55.98}],
                   213: [{"sourceColumn": "", "fieldName": "telephone", "fillRate": None}]}
    check("phone sources: numeric ids (the real instance), STORES.TELEPHONE found",
          L.phone_source_systems(int_tables, int_columns, {}) == {"ECOM": ["mobile"], "STORES": ["TELEPHONE"], "LOYALTY": ["telephone"]},
          str(L.phone_source_systems(int_tables, int_columns, {})))
    check("phone sources: ids as text on one side and number on the other", L.phone_source_systems({"STORES": {"id": "211"}}, {211: int_columns[211]}, {}) == {"STORES": ["TELEPHONE"]})
    check("phone sources: a filled column is not hidden by a table whose id matches nothing", L.phone_source_systems({"STORES": {"id": 999}}, int_columns, {}) == {})
    # lot M11: a name key is recognised by its library, whatever its origin, function and attribute name
    for ref in ("library/Fasttrack/lib/names", "Fasttrack.lib.names", "library.Fasttrack.lib.names", "names", "Acme.lib.names"):
        check("names library recognised: " + ref, L.is_names_library(ref))
    for ref in ("library/Fasttrack/lib/phone", "Fasttrack.lib.phone", "library/Fasttrack/lib/surnames", "", None):
        check("not the names library: %r" % (ref,), not L.is_names_library(ref))
    check("recognised through the import name when the path is empty", L.is_names_library("", "Fasttrack.lib.names"))
    attrs = [{"id": "1", "name": "personNameKey", "status": "PROPOSED"}, {"id": "2", "name": "nameMatchKey", "status": "ACCEPTED"}, {"id": "3", "name": "fullNameKey", "status": "REJECTED"},
             {"id": "4", "name": "mobileE164", "status": "PROPOSED"}, {"id": "5", "name": "x", "status": "PROPOSED"}]
    sugg = [{"attribute": "1", "nature": "SCRIPT_DERIVATION", "status": "PROPOSED", "targetLibrary": "library/Fasttrack/lib/names", "targetFunction": "sortedKeyOf", "origin": "RULE"},
            {"attribute": "2", "nature": "SCRIPT_DERIVATION", "status": "ACCEPTED", "targetLibrary": "library/Fasttrack/lib/names", "targetFunction": "fullKeyOf", "origin": "LLM"},
            {"attribute": "3", "nature": "SCRIPT_DERIVATION", "status": "ACCEPTED", "targetLibrary": "library/Fasttrack/lib/names", "targetFunction": "fullKeyOf"},
            {"attribute": "4", "nature": "SCRIPT_DERIVATION", "status": "PROPOSED", "targetLibrary": "library/Fasttrack/lib/phone", "targetFunction": "toE164"},
            {"attribute": "5", "nature": "SCRIPT_CONSTRAINT", "status": "PROPOSED", "targetLibrary": "library/Fasttrack/lib/names", "targetFunction": "isValid"}]
    found = [a["name"] for a in L.name_derivation_attributes(attrs, sugg)]
    check("name derivations: rule and model, any function and name; not a phone, not a constraint, not a rejected attribute", found == ["personNameKey", "nameMatchKey"], str(found))


def test_routes_jobs_poll():
    check("module 404 is not a missing route", not L.route_missing(404, {"error": "Project X not found"}))
    check("empty 404 is a missing route", L.route_missing(404, None))
    check("another 404 message is a missing route", L.route_missing(404, {"error": "Not found"}))
    check("405 is a missing route", L.route_missing(405, None))
    check("403 is not", not L.route_missing(403, {"error": "Access denied"}))
    jobs = {"active": [{"jobId": "5", "status": "RUNNING"}], "recent": [{"jobId": "4", "status": "DONE"}]}
    check("job found in active then recent", L.job_in(jobs, 5)["status"] == "RUNNING" and L.job_in(jobs, "4")["status"] == "DONE" and L.job_in(jobs, "9") is None)
    # bounded wait with a fake clock
    clock = [0.0]
    slept = []

    def fake_sleep(s):
        slept.append(s)
        clock[0] += s
    try:
        L.poll(lambda: None, lambda v: False, 20, 5, "the thing", sleep=fake_sleep, clock=lambda: clock[0])
        check("poll times out", False)
    except L.Timeout as exc:
        check("timeout names the wait and the delay", "waiting for the thing" in str(exc) and "20 s" in str(exc), str(exc))
        check("timeout is bounded", len(slept) == 4, str(slept))
    seq = iter([1, 2, 3])
    check("poll returns when done", L.poll(lambda: next(seq), lambda v: v == 3, 100, 1, "x", sleep=lambda s: None) == 3)


def test_report():
    out = io.StringIO()
    rep = L.Report(out)
    rep.passed("1", "a")
    rep.failed("3", "b", "because")
    rep.skipped("6", "c", "why")
    rep.info("2", "i")
    table = rep.table()
    check("table lists PASS FAIL SKIP and the reason of the failure", "PASS" in table and "FAIL" in table and "SKIP" in table and "because" in table and "1 passed, 1 failed, 1 skipped" in table, table)
    check("info is not in the table", "i\n" not in table.split("-" * 60)[1])


# ------------------------------------------------------------------------------------------------------------------ stand-in server

class Stub(BaseHTTPRequestHandler):
    missing = set()          # route tails answered with an empty 404
    jobs = {"active": [{"jobId": "1", "status": "RUNNING", "stepMessage": "working"}], "recent": [], "busy": True}

    def log_message(self, *a):
        pass

    def reply(self, status, body=None):
        data = b"" if body is None else json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_any(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            self.rfile.read(n)
        path = self.path.split("?", 1)[0]
        if not self.headers.get("Authorization", "").startswith("Basic "):
            return self.reply(401, {"error": "no credentials"})
        base = "/ebx-mdm-factory/rest/v1"
        if path == base + "/status":
            return self.reply(200, {"module": "ebx-mdm-factory", "version": "selftest", "commit": "abc", "datasetState": "READY", "dataspace": "MdmFactory", "dataset": "MdmFactory"})
        if path == base + "/connection/test":
            return self.reply(200, {"ready": True})
        if path.endswith("/jobs") and "NOSUCH" not in path:
            return self.reply(200, Stub.jobs)
        if path.startswith(base + "/projects/"):
            tail = "/" + path[len(base + "/projects/"):].split("/", 1)[1]
            tail = tail.replace("/0/binding", "/{id}/binding")
            if any(tail == m or tail.startswith(m) for m in Stub.missing):
                return self.reply(404)
            return self.reply(404, {"error": "Project %s not found" % path.split("/")[5]})
        if path.startswith("/ebx-dataservices/"):
            if "FactorySettings" in path:
                return self.reply(200, {"content": {"role": {"content": "SOURCE"}, "instanceId": {"content": "i-1"}}})
            return self.reply(404, {"error": "stand-in has no data"})
        return self.reply(404)

    do_GET = handle_any
    do_POST = handle_any
    do_PUT = handle_any
    do_DELETE = handle_any


def serve():
    server = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def run_scenario(port, args=None, cls=None, code=None, env_extra=None):
    import b2c_scenario as S
    out = io.StringIO()
    env = {"EBX_URL": "http://127.0.0.1:%d" % port, "EBX_USER": "selftest", "EBX_PASSWORD": "not-a-real-secret", "E2E_POLL": "0.01", "E2E_OUT_DIR": tempfile.mkdtemp(prefix="b2c-rep-")}
    env.update(env_extra or {})
    full = {"cleanup": False, "through": 6, "report": None, "keep_files": False, "cleanup_project": None}
    full.update(args or {})
    sc = (cls or S.Scenario)(env, out, full, code=code)
    ok = sc.connect()
    code = sc.run() if ok else 2
    return code, out.getvalue(), sc, env


def test_scenario_wiring():
    server = serve()
    port = server.server_address[1]
    try:
        Stub.missing = {"/landing/run", "/staging/propose", "/staging/preflight"}
        code, text, sc, env = run_scenario(port)
        check("a missing route ends the run with exit 3", code == 3, text)
        check("the report names the missing routes", "POST /projects/{x}/landing/run" in text and "POST /projects/{x}/staging/propose" in text, text)
        check("optional preflight route absence is a note, not a failure", "optional route absent" in text, text)
        check("the secret is never printed", "not-a-real-secret" not in text)
        reports = [f for f in os.listdir(env["E2E_OUT_DIR"]) if f.endswith(".json")]
        check("a JSON report is written even when the run stops", len(reports) == 1)
        if reports:
            doc = json.load(open(os.path.join(env["E2E_OUT_DIR"], reports[0])))
            check("the JSON report holds the checks and the routes", doc["summary"]["failed"] >= 1 and "POST /projects/{x}/landing/run" in doc["routes"] and doc["routes"]["POST /projects/{x}/landing/run"] == "MISSING")
            check("the JSON report holds no credential", "not-a-real-secret" not in json.dumps(doc))
        Stub.missing = set()
        code, text, sc, env = run_scenario(port, {"through": 1})
        check("all routes present, project creation refused by the stand-in: exit 1 with a clear failure", code == 1 and "project created" in text, text)
        # bounded wait: the stub's job never ends
        sc.ds = None
        import client
        sc.ds = client.Ds(sc.admin, "MdmFactory", "MdmFactory")
        try:
            sc.wait_job("1", 0.05, "a job that never ends")
            check("wait_job times out", False)
        except L.Timeout as exc:
            check("wait_job timeout message", "a job that never ends" in str(exc), str(exc))
        # a mutating request that does not name the run is refused before it is sent
        try:
            sc.admin.request("POST", sc.rest + "/projects/SOMEOTHERPROJECT/delete?confirm=SOMEOTHERPROJECT")
            check("guard refuses a request on another project", False)
        except client.SafetyError:
            check("guard refuses a request on another project", True)
    finally:
        server.shutdown()


# the stand-in factory (b2c_standin.py): the whole scenario on a good run, and each defect shown to be caught by the assertion that guards it
DEFECT_EXPECTATIONS = [
    ("orphan", "no rule entity left PROPOSED alone"),
    ("isodate", "birth date link converts"),
    ("isodate", "birth dates filled (300)"),
    ("plusdrop", "no step drops a leading '+'"),
    ("noe164", "E.164 field exists and is filled"),
    # lot M15 (2026-10-18, E2EMASTER20261018090711): the STORES phone never reached the entity; the E.164 check counts only rows with a phone and passed
    ("phonegap", "phone reaches the person entity from every source"),
    ("reject", "0 TransferReject"),
    ("lowload", "ECOM: 160 rows loaded"),
    ("resume", "everything loaded in ONE launch"),
    # lot M4 (2026-10-18, point C): a constraint left ACCEPTED with no word on the row is the defect
    ("constraintleft", "accepted script constraints applied by the load, or the reason said on the row"),
]


def stand_in_run(defects, args=None, hard=False):
    import b2c_standin
    server, factory = b2c_standin.serve(defects, hard)
    try:
        if hard:
            import b2c_hard
            full = {"through": 7}
            full.update(args or {})
            code, text, sc, env = run_scenario(server.server_address[1], full, cls=b2c_hard.HardScenario)
        else:
            code, text, sc, env = run_scenario(server.server_address[1], args)
        return code, text, sc, factory
    finally:
        server.shutdown()


def test_scenario_flows():
    code, text, sc, factory = stand_in_run(())
    check("good run: exit 0", code == 0, text[-3000:])
    check("good run: 0 failed", sc.rep.counts()["FAIL"] == 0, "\n".join(c["name"] + " " + c["detail"] for c in sc.rep.checks if c["status"] == "FAIL"))
    names = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
    for needle in ("ECOM: 160 rows loaded", "STORES: 234 rows loaded", "LOYALTY: 140 rows loaded", "birth dates filled (300)", "phone keeps its '+'", "E.164 field", "phone reaches the person entity from every source", "name key", "person entity holds one row per source row"):
        check("good run passes: " + needle, any(needle in n for n in names), str(names))
    check("good run: the stores key was chosen explicitly (origin USER)", any(c["sourceKeyOrigin"] == "USER" for c in factory.table("LandingColumn").values()
                                                                               if c["sourceColumn"] == "NUM_TICKET"))
    check("good run: defaultCountry FR provided", "FR" in factory.table("CapabilitySuggestion")["226"]["binding"])
    check("good run: the queue was decided by policy (check rejected, rest accepted)", sc.report_data["decisions"].get("rejected", 0) >= 1, str(sc.report_data["decisions"]))
    check("good run: the preflight is skipped with a note when the route is absent", any(c["status"] == "SKIP" and "preflight" in c["name"] for c in sc.rep.checks)
          or any("preflight" in c["name"] for c in sc.rep.checks))
    import os as _os
    reports = [f for f in _os.listdir(sc.env["E2E_OUT_DIR"]) if f.endswith(".json")]
    check("good run: a JSON report", len(reports) == 1)
    code, text, sc, factory = stand_in_run((), {"cleanup": True})
    check("cleanup run: exit 0 and the project deleted", code == 0 and "project deleted with its generated objects" in text, text[-1500:])
    code, text, sc, factory = stand_in_run((), {"through": 3})
    check("--through 3 stops after the landing and skips the rest", code == 0 and sum(1 for c in sc.rep.checks if c["status"] == "SKIP" and c["step"] in ("4", "5", "6")) >= 3, text[-800:])
    # lot M11: a name key is found by its library when nothing in its name or its source says it is one; without any, the check is skipped, not failed
    code, text, sc, factory = stand_in_run(("namelib",))
    check("name key computed by the names library (attribute 'matchToken', no source): exit 0", code == 0, text[-1500:])
    check("name key found by its library and checked", any(c["status"] == "PASS" and "name key matchToken filled" in c["name"] for c in sc.rep.checks), str([c["name"] for c in sc.rep.checks]))
    code, text, sc, factory = stand_in_run(("nonamekey",))
    check("no name key at all: exit 0, the check is skipped", code == 0 and any(c["status"] == "SKIP" and c["name"] == "name keys filled" for c in sc.rep.checks), text[-800:])
    # lot M4 (point C): a constraint the load cannot apply stays ACCEPTED, and the row says why - not a failure, reported as information
    code, text, sc, factory = stand_in_run(("constraintexplained",))
    explained = [c for c in sc.rep.checks if "accepted script constraints applied by the load" in c["name"]]
    check("constraint left ACCEPTED with its reason on the row: the check passes", code == 0 and explained and explained[0]["status"] == "PASS" and "to be provided" in explained[0]["detail"], text[-1500:])
    check("constraint left ACCEPTED with its reason: the reason is printed as information", any(c["status"] == "INFO" and "could not apply" in c["name"] and "to be provided" in c["detail"] for c in sc.rep.checks),
          str([c["name"] for c in sc.rep.checks if c["status"] == "INFO"]))
    # lot M7 (2026-10-18): an accepted entity no column feeds, said on its row by the load ("No feed: ..."), is reported as information - the scenario measures the
    # engine, not the model's variance (ContactEmail on E2EMASTER20261017235630 failed "rows written" although the engine had done exactly what the decisions said)
    code, text, sc, factory = stand_in_run(("nofeed",))
    check("entity without a feed, said on the row: exit 0", code == 0, text[-1500:])
    check("entity without a feed: no 'rows written' failure on it", not [c for c in sc.rep.checks if c["status"] == "FAIL" and "ContactEmail" in c["name"]],
          str([c["name"] for c in sc.rep.checks if c["status"] == "FAIL"]))
    check("entity without a feed: an information line names it with the row's message",
          any(c["status"] == "INFO" and c["name"] == "ContactEmail: no feed (nothing loaded, said on the row)" and "No feed: no accepted mapping" in c["detail"] for c in sc.rep.checks),
          str([(c["name"], c["detail"]) for c in sc.rep.checks if c["status"] == "INFO"]))
    check("the French form of the check sentence is read as a rejection too", L.check_proposes_reject(
        "\u00c0 v\u00e9rifier : aucune colonne source n\u2019alimente cette entit\u00e9, rien n\u2019y serait charg\u00e9 - associez au moins un attribut de X \u00e0 une colonne de landing, "
        "ou ne la proposez pas (proposition : la refuser).") and L.check_proposes_reject(
        "To check: no source column feeds this entity, nothing would be loaded into it - map at least one attribute of X to a landing column, or do not propose it (suggestion: reject it)."))
    check("no_feed reads the head of the row's message only", L.no_feed({"lastLoadMessage": "No feed: no accepted mapping feeds this entity"}) and not L.no_feed({"lastLoadMessage": "300 written"})
          and not L.no_feed({}))
    for defect, needle in DEFECT_EXPECTATIONS:
        code, text, sc, factory = stand_in_run((defect,))
        failed = [c["name"] for c in sc.rep.checks if c["status"] == "FAIL"]
        check("defect %s: exit 1" % defect, code == 1, text[-800:])
        check("defect %s is caught by '%s'" % (defect, needle), any(needle in n for n in failed), str(failed))


# ------------------------------------------------------------------------------------------------------------------ the hard cases (b2c_hard.py)

def test_hard_fixtures():
    import b2c_hard as H
    import b2c_scenario as S
    work = tempfile.mkdtemp(prefix="b2c-hard-selftest-")
    try:
        paths = S.generate_files(work, H.HARD_NAMES)
        problems, counts = H.check_hard_files(paths)
        check("hard fixtures: no problem", not problems, str(problems))
        check("hard fixtures: 160 / 234 / 140 / 150", counts == {"ECOM": 160, "STORES": 234, "LOYALTY": 140, "CRM": 150}, str(counts))
        check("hard fixtures: the sum is the person rows and the birth dates", sum(counts.values()) == L.HARD_PERSON_ROWS and counts["ECOM"] + counts["LOYALTY"] + counts["CRM"] == L.HARD_BIRTH_DATES)
        check("hard fixtures: 13 comments of 600 characters or more", L.count_longer([r["comment"] for r in H.read_crm(paths["CRM"])], 599) == L.CRM_LONG_COMMENTS)
        # the three nominal files are untouched by the 4th (the nominal scenario checks them with its own numbers)
        problems3, counts3 = S.check_files(dict((a, paths[a]) for a in ("ECOM", "STORES", "LOYALTY")))
        check("hard fixtures: the nominal three still pass the nominal checks", not problems3, str(problems3))
        # a broken CRM file is caught
        rows = H.read_crm(paths["CRM"])
        with open(paths["CRM"], "w", encoding="utf-8") as fh:
            fh.write("contact_id,civility,last_name,first_name,birth,email,phone,tier,comment\nx,MR,a,b,1990-01-01,e,p,L1,c\n")
        problems, _ = H.check_hard_files(paths)
        check("hard fixtures: a one-row CRM file with ISO dates is a problem", len(problems) >= 2, str(problems))
    finally:
        shutil.rmtree(work, ignore_errors=True)


def test_hard_logic():
    check("code key: case, accents, punctuation, spaces", L.code_key("M.") == "m" and L.code_key("Mme") == "mme" and L.code_key(" Or ") == "or" and L.code_key("Ér") == "er")
    check("code key of pure punctuation keeps itself", L.code_key("--") == "--")
    table = L.parse_code_table('{"codes":{"M.":"MR","Mme":"MRS"},"targets":["MR","MRS","MISS"],"counts":{"M.":5}}')
    check("code table parsed", table is not None and table["codes"]["Mme"] == "MRS" and table["targets"] == ["MR", "MRS", "MISS"])
    check("a non-table argument is None", L.parse_code_table("reference:TITLE") is None and L.parse_code_table("") is None and L.parse_code_table('["a"]') is None)
    check("no gap when every observed code has a target", L.code_gaps(table, ("M.", "Mme")) == [])
    check("a code with an empty target is a gap, matched by key", L.code_gaps(L.parse_code_table('{"codes":{"m":"MR","Mme":""}}'), ("M.", "Mme")) == ["Mme"])
    check("an unlisted code is a gap", L.code_gaps(table, ("M.", "Mme", "Dr")) == ["Dr"])
    problems, vocabulary = L.codes_alignment({"LOYALTY": (("M.", "Mme"), table), "CRM": (("MR", "MRS", "MISS"), None)})
    check("a source already on the common list needs no table", problems == [] and vocabulary == {"mr", "mrs", "miss"}, str(problems))
    problems, _ = L.codes_alignment({"LOYALTY": (("M.", "Mme"), table), "CRM": (("A", "B"), None)})
    check("a source on another vocabulary is a problem", len(problems) == 1 and "CRM" in problems[0], str(problems))
    problems, _ = L.codes_alignment({"LOYALTY": (("M.", "Mme"), None), "CRM": (("MR",), None)})
    check("no table at all is a problem", problems == ["no source has a code table"])
    check("pipelines compare without spaces or case", L.pipeline_norm("Trim | date(dd/MM/yyyy, yyyy-MM-dd)") == L.pipeline_norm(L.LOYALTY_FIX_PIPELINE))
    report = {"blocking": True, "findings": [{"mapping": "7", "issues": [{"kind": "CONVERSION", "blocking": True, "rows": 140}], "fix": {"pipeline": "x"}},
                                            {"mapping": "8", "issues": [{"kind": "LOST_PLUS", "blocking": False, "rows": 3}]}]}
    check("preflight finding by mapping id (number or text)", L.preflight_finding(report, 7)["mapping"] == "7" and L.preflight_finding(report, "9") is None)
    check("blocking findings and issues", [f["mapping"] for f in L.blocking_findings(report)] == ["7"] and len(L.blocking_issues(report["findings"][1])) == 0)
    check("the blocked build sentence is recognised", L.build_was_blocked("Nothing to do: the build was not started - 140 row(s) on 1 link(s) would be refused. [mdmf:x]")
          and not L.build_was_blocked("Staging build finished: 140 written, 0 rejected."))
    rejects = [{"stage": "STAGING", "entity": "180", "resolved": False, "category": "EBX_REFUSAL", "reasonCode": "convert.date|birthDate|date_naissance", "sourceSystem": "LOYALTY"},
               {"stage": "STAGING", "entity": "184", "resolved": False, "category": "PARENT_REJECTED", "reasonCode": "parent|convert.date|birthDate|date_naissance"},
               {"stage": "STAGING", "entity": "180", "resolved": True, "category": "EBX_REFUSAL", "reasonCode": "convert.date|birthDate|date_naissance"},
               {"stage": "MASTER", "entity": "180", "resolved": False, "category": "EBX_REFUSAL", "reasonCode": "convert.date|birthDate|date_naissance"}]
    check("rejects selected open, STAGING, by code prefix and column", len(L.reject_select(rejects, entity="180", code_prefix="convert.date|", column="date_naissance")) == 1
          and len(L.reject_select(rejects, open_only=False, entity="180")) == 2 and len(L.reject_select(rejects, category="PARENT_REJECTED")) == 1)
    check("tally by key", L.tally_by(rejects, "category") == {"EBX_REFUSAL": 3, "PARENT_REJECTED": 1})
    good = {"total": 280, "counts": {"EBX_REFUSAL": 140, "PARENT_REJECTED": 140}, "sentence": "280 rows set aside: refused by EBX, the data model does not accept the row (140); the row belongs to a parent (140)."}
    check("a right banner has no problem", L.causes_problems(good, 140, 140) == [], str(L.causes_problems(good, 140, 140)))
    check("a banner that blames a rule is a problem", any("rule" in p for p in L.causes_problems(dict(good, sentence="280 rows set aside - a configured rule stopped them."), 140, 140)))
    check("a banner with too few parents is a problem", any("PARENT" in p for p in L.causes_problems(dict(good, counts={"EBX_REFUSAL": 140}), 140, 140)))
    check("no sentence is a problem", L.causes_problems({"counts": {}, "sentence": ""}, 1, 0) == ["no sentence"])
    url = L.wizard_url("http://h:8080/", "MdmFactory", "MdmFactory", "E2EHARD1", 4)
    check("wizard url shape", url.startswith("http://h:8080/ebx-ui/ui/advanced/data/BMdmFactory/MdmFactory/root/Project?service=ProjectWizard&serviceMode=TABLE_VIEW&serviceParams=") and "%3D" in url, url)
    import base64
    import urllib.parse
    blob = urllib.parse.unquote(url.rsplit("serviceParams=", 1)[1])
    check("wizard url carries the project and the step as base64 json", json.loads(base64.b64decode(blob)) == {"project": "E2EHARD1", "step": 4})
    check("rejects url selects the project", "predicate=./code%3D%27E2EHARD1%27" in L.rejects_url("http://h", "A", "B", "E2EHARD1") and "service=TransferRejectReview" in L.rejects_url("http://h", "A", "B", "E2EHARD1"))


def hard_run(defects=(), args=None):
    return stand_in_run(defects, args, hard=True)


def failed_names(sc):
    return [c["name"] for c in sc.rep.checks if c["status"] == "FAIL"]


# a defect and the check that guards it (names are substrings)
HARD_DEFECTS = [
    ("nocodes", "civility: the codes to align come as a TRANSCODE_REFERENCE proposal"),
    ("nocodes", "tier: the codes to align come as a TRANSCODE_REFERENCE proposal"),
    ("codegap", "civility LOYALTY: every observed code has a target"),
    ("misaligned", "tier: both sources end on one common list"),
    ("crmiso", "CRM birth date is read as yyyyMMdd"),
    ("crmiso", "baseline preflight: every link the model proposed converts"),
    ("pfblind", "preflight blocks the build"),
    ("nofix", "the preflight fix is applied"),
    ("nobuildanyway", "'build anyway' sets the preflight aside and loads"),
    ("orphans", "are rejected as PARENT_REJECTED"),
    ("orphans", "no child row of a rejected loyalty parent was written"),
    ("stuck", "the rejects of that link are resolved or gone"),
    ("toolongloaded", "the load refuses exactly the 13 too long comments"),
    ("bannerrule", "the banner says why"),
]


def test_hard_flows():
    code, text, sc, factory = hard_run()
    check("hard good run: exit 0", code == 0, text[-4000:])
    check("hard good run: 0 failed", sc.rep.counts()["FAIL"] == 0, "\n".join(c["name"] + " " + c["detail"] for c in sc.rep.checks if c["status"] == "FAIL"))
    names = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
    for needle in ("civility: the codes to align come as a TRANSCODE_REFERENCE proposal", "tier: the codes to align", "civility LOYALTY: every observed code", "civility: both sources end on one common list",
                   "tier: both sources end on one common list", "civility: the code table(s) are accepted (an incomplete one is rejected, never accepted)", "baseline preflight", "bad link forced", "preflight blocks the build",
                   "at least 140 blocking rows", "preflight proposes the fix", "the build is stopped by the preflight", "the stopped build wrote nothing", "'build anyway' sets the preflight aside",
                   "the 140 loyalty rows are rejected with reason code convert.date", "category EBX_REFUSAL", "are rejected as PARENT_REJECTED", "the banner says why",
                   "long comment values are caught", "exactly the 13 too long comments", "the preflight fix is applied", "the rejects of that link are resolved", "only rows still held back are the too long",
                   "the person entity holds one row per source row (671)", "birth dates filled (437)", "civility: every stored value is a code of the common list", "tier: every stored value"):
        check("hard good run passes: " + needle, any(needle in n for n in names), needle + " | " + str(names)[:3000])
    check("hard good run: the loyalty civilities were stored as MR / MRS and the tiers as L1 / L2 / L3",
          set(r.get("civility") for r in factory.staging[sc.code]["Client"] if r["sourceSystem"] == "LOYALTY") <= {"MR", "MRS"}
          and set(r.get("tier") for r in factory.staging[sc.code]["Client"] if r["sourceSystem"] == "LOYALTY") <= {"L1", "L2", "L3"})
    check("hard good run: the project is left in place", sc.code in factory.table("Project") and not any("deleted" in c["name"] for c in sc.rep.checks))
    check("hard good run: the wizard address is printed", "PROJECT LEFT IN PLACE" in text and "serviceParams=" in text and "TransferRejectReview" in text and "--cleanup-project %s" % sc.code in text, text[-1500:])
    check("hard good run: the secret is never printed", "not-a-real-secret" not in text)
    check("hard good run: the details of the codes and of the rejects are in the report", "codes" in sc.details and "rejectsAfterBadLink" in sc.details)
    check("hard good run: the bad link was written on the mapping, then fixed", any(m["pipeline"] == "trim|date(dd/MM/yyyy,yyyy-MM-dd)" for m in factory.table("AttributeMapping").values()))
    check("hard good run: LEFT BEHIND names the project and the four connections", "LEFT BEHIND" in text and text.count("Connectors connection") == 4, text[-1500:])
    # tolerated shapes
    code, text, sc, factory = hard_run(("keepflags",))
    check("a code table flagged at 59 with its reason is tolerated (exit 0)", code == 0, "\n".join(failed_names(sc)))
    check("... and the alignment is then not judged", any(c["status"] == "INFO" and "alignment not judged" in c["name"] for c in sc.rep.checks))
    # staging last points (2026-10-17): the factory refuses to ACCEPT a code table while an observed code with rows has no target; the scenario rejects it (documented policy)
    steps = [st for st in factory.table("MappingStep").values() if st["kind"] == "TRANSCODE_REFERENCE" and any(not t for t in json.loads(st["argument"])["codes"].values())]
    check("an incomplete code table is not accepted: the stand-in refused it and the scenario rejected it", steps and all(st["status"] == "REJECTED" for st in steps)
          and len(sc.report_data["decisions"].get("incompleteCodeTables", [])) == len(steps), str([(st["id"], st["status"]) for st in steps]))
    check("... and the report says so", any("incomplete code table(s) rejected, not accepted" in c["name"] for c in sc.rep.checks))
    check("the refusal is recognised by its words", L.is_incomplete_code_table_refusal("Choose a target code for M., Mme, then accept.") and not L.is_incomplete_code_table_refusal("Attribute 'x' is not accepted yet.")
          and not L.is_incomplete_code_table_refusal(None))
    check("the stand-in applies the same rule to a bulk accept (the table stayed unaccepted at confidence 59 and is refused if it were above the bar)",
          factory.accept_refusal("MAPPING_STEP", steps[0]) == "Choose a target code for Mme, then accept." and factory.accept_refusal("MAPPING_STEP", {"kind": "TRIM"}) is None)
    code, text, sc, factory = hard_run(("nochild",))
    check("no child entity: the cascade is skipped with a reason, not failed", code == 0 and any(c["status"] == "SKIP" and "PARENT_REJECTED" in c["name"] for c in sc.rep.checks), "\n".join(failed_names(sc)))
    code, text, sc, factory = hard_run(("pfsilent",))
    check("a preflight that does not see the long comments: the load catches them (exit 0)", code == 0 and any("long comment values are caught" in c["name"] and "silent" in c["detail"] for c in sc.rep.checks if c["status"] == "PASS"),
          "\n".join(failed_names(sc)))
    code, text, sc, factory = hard_run(("pfsilent", "toolongloaded"))
    check("neither the preflight nor the load catches the long comments: failed", any("long comment values are caught" in n for n in failed_names(sc)), str(failed_names(sc)))
    code, text, sc, factory = hard_run(("nocomment",))
    check("a model that keeps no comment field: skipped, exit 0", code == 0 and any(c["status"] == "SKIP" and "long comment values" in c["name"] for c in sc.rep.checks), "\n".join(failed_names(sc)))
    for defect, needle in HARD_DEFECTS:
        code, text, sc, factory = hard_run((defect,))
        failed = failed_names(sc)
        check("hard defect %s: exit 1" % defect, code == 1, text[-1200:])
        check("hard defect %s is caught by '%s'" % (defect, needle), any(needle in n for n in failed), str(failed))


def test_hard_cleanup():
    # connections left behind: really read after the deletion
    code, text, sc, factory = hard_run((), {"cleanup": True})
    check("hard cleanup: exit 0, the project deleted", code == 0 and "project deleted with its generated objects" in text, text[-2000:])
    check("hard cleanup: the four connections are LEFT BEHIND when the deletion keeps them", text.count("still there after the project deletion") == 4 and "PROJECT LEFT IN PLACE" not in text, text[-1500:])
    code, text, sc, factory = hard_run(("delconn",), {"cleanup": True})
    check("hard cleanup, deletion that removes the connections (lot C): nothing is left behind", code == 0 and "LEFT BEHIND" not in text and not sc.leftovers, text[-2000:])
    check("hard cleanup, lot C: the plan's connection items are this run's (accepted by suffix / id)", "deletion plan touches only this run's objects" not in " ".join(failed_names(sc)), str(failed_names(sc)))
    code, text, sc, factory = hard_run(("noconnroute",), {"cleanup": True})
    check("hard cleanup without the listing route: reported as unverified, not as removed", code == 0 and text.count("could not check whether the deletion removed it") == 4, text[-1500:])
    # --cleanup-project deletes a project of an earlier run
    import b2c_standin
    import b2c_hard
    server, factory = b2c_standin.serve((), True)
    try:
        port = server.server_address[1]
        code, text, sc, env = run_scenario(port, {"through": 4}, cls=b2c_hard.HardScenario)
        kept = sc.code
        check("cleanup-project setup: the project is in place", kept in factory.table("Project"))
        code2, text2, sc2, env2 = run_scenario(port, {"cleanup_project": kept}, cls=b2c_hard.HardScenario, code=kept)
        out = io.StringIO()
        sc2 = b2c_hard.HardScenario(env2, out, {"cleanup": False, "through": 7, "report": None, "keep_files": False, "cleanup_project": kept}, code=kept)
        sc2.connect()
        rc = sc2.run_cleanup_only()
        check("--cleanup-project: the project is deleted and the connections reported (read from the instance by suffix)",
              rc == 0 and kept not in factory.table("Project") and out.getvalue().count("still there after the project deletion") == 4, out.getvalue()[-1800:])
        bad = io.StringIO()
        import contextlib
        with contextlib.redirect_stderr(io.StringIO()):
            refused_code = b2c_hard.main(["--cleanup-project", "E2EB2C20261017"], {"EBX_URL": "http://127.0.0.1:1", "EBX_USER": "u", "EBX_PASSWORD": "p"}, bad)
        check("--cleanup-project refuses a code that is not this scenario's", refused_code == 2)
    finally:
        server.shutdown()
    # --through stops where asked
    code, text, sc, factory = hard_run((), {"through": 5})
    check("--through 5: steps 6 and 7 are skipped", code == 0 and sum(1 for c in sc.rep.checks if c["status"] == "SKIP" and c["step"] in ("6", "7")) == 2, text[-800:])
    # the dry run
    out = io.StringIO()
    import b2c_hard as H
    check("hard dry-run: exit 0 and the plan is printed", H.dry_run(out) == 0 and "staging/load?buildAnyway=true" in out.getvalue() and "CRM 150" in out.getvalue(), out.getvalue()[:500])


def run(out=None):
    out = out or sys.stdout
    for fn in (test_fixtures, test_missed_pairs, test_rationale_logic, test_plus_and_pipelines, test_entities_and_feeds, test_measures, test_routes_jobs_poll, test_report, test_scenario_wiring, test_scenario_flows,
               test_hard_fixtures, test_hard_logic, test_hard_flows, test_hard_cleanup):
        fn()
    # lot E2E-master: the ground truth, the measures, the master and matching scenario against the stand-in (both variants)
    import b2c_master_selftest
    import sys as _sys
    b2c_master_selftest.run_all(check, _sys.modules[__name__])
    if FAILURES:
        out.write("b2c scenario selftest: %d failure(s): %s\n" % (len(FAILURES), "; ".join(FAILURES)))
        return 1
    out.write("b2c scenario selftest OK\n")
    return 0


if __name__ == "__main__":
    sys.exit(run())
