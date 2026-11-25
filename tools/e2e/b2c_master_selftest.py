"""Self-test of the B2C master and matching scenario (tools/e2e/b2c_master.py, master_lib.py): the ground truth written by gen_b2c.py, the clustering and the quality measures on canned
data, and the whole scenario against the stand-in factory in master mode, a good run per variant and each defect shown to turn the intended check to FAIL. Called by
b2c_selftest.run(), which hands over its own `check` so that every failure lands in one list."""
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import e2e_lib as L  # noqa: E402
import master_lib as M  # noqa: E402

# the four files gen_b2c.py wrote BEFORE the ground truth was added (sha1): they must never change, the sources of the other scenarios are these bytes
FILE_SHA1 = {"ECOM_Clients.json": "c880ffae05fcb7a8574c82484352585aa40f0515", "Magasins_Ventes.csv": "54ad3ce67d024abcf6d217c55e57a32d3ef50a22",
             "Fidelite_Membres.csv": "d9cebd154cb1e85acfa218e324efa59c08d3d23a", "CRM_Contacts.csv": "f590e32c0f567c8b569dc212f00b58c4112fcf46"}


def sha1(path):
    with open(path, "rb") as fh:
        return hashlib.sha1(fh.read()).hexdigest()


def test_truth(check):
    import b2c_scenario as S
    first, second = tempfile.mkdtemp(prefix="b2c-truth-a-"), tempfile.mkdtemp(prefix="b2c-truth-b-")
    try:
        S.generate_files(first)
        S.generate_files(second)
        for name, digest in FILE_SHA1.items():
            check("truth: %s is byte for byte what it was before the truth file existed" % name, sha1(os.path.join(first, name)) == digest, sha1(os.path.join(first, name)))
        check("truth: the truth file is deterministic (same seed, same bytes)", sha1(os.path.join(first, "truth_b2c.json")) == sha1(os.path.join(second, "truth_b2c.json")))
        truth, summary = M.load_truth(os.path.join(first, "truth_b2c.json"))
        check("truth: 684 rows (160 + 234 + 140 + 150) of at most 220 people", summary["rows"] == 684 and 100 < summary["persons"] <= 220, str(summary))
        # every key of every file is in the truth, and nothing else
        with open(os.path.join(first, "ECOM_Clients.json"), encoding="utf-8") as fh:
            web = set(c["customerId"] for c in json.load(fh)["customers"])
        import csv
        keys = {"ECOM": web}
        for app, name, column, delimiter in (("STORES", "Magasins_Ventes.csv", "NUM_TICKET", ";"), ("LOYALTY", "Fidelite_Membres.csv", "card_number", ","), ("CRM", "CRM_Contacts.csv", "contact_id", ",")):
            with open(os.path.join(first, name), encoding="utf-8", newline="") as fh:
                keys[app] = set(r[column] for r in csv.DictReader(fh, delimiter=delimiter))
        for app, ks in keys.items():
            check("truth: the keys of %s are the keys of its file" % app, ks == set(k for (a, k) in truth if a == app), "%d vs %d" % (len(ks), sum(1 for (a, _k) in truth if a == app)))
        # a ticket person has several tickets; the same person appears in several sources
        expected = M.expected_clusters_summary(truth, ("ECOM", "STORES", "LOYALTY"))
        check("truth: duplicates exist within a source (tickets) and across sources", expected["pairsWithinASource"] > 0 and expected["pairsAcrossSources"] > 0
              and expected["truePairs"] == expected["pairsWithinASource"] + expected["pairsAcrossSources"], str(expected))
        check("truth: three sources, 534 records", expected["records"] == 534, str(expected))
        # the loader refuses a file that holds a key twice
        bad = os.path.join(first, "bad.json")
        with open(bad, "w", encoding="utf-8") as fh:
            json.dump({"people": 1, "rows": [{"app": "ECOM", "key": "a", "person": 1}, {"app": "ECOM", "key": "a", "person": 2}]}, fh)
        try:
            M.load_truth(bad)
            check("truth: a key twice is refused", False)
        except ValueError:
            check("truth: a key twice is refused", True)
    finally:
        shutil.rmtree(first, ignore_errors=True)
        shutil.rmtree(second, ignore_errors=True)


def meta(rid, group, state=1, golden=None, auto=False):
    return {"functionalId": str(rid), "groupId": group, "state": state, "goldenId": golden, "autoCreated": auto}


def test_measures(check):
    persons = {"1": "A", "2": "A", "3": "A", "4": "B", "5": "B", "6": "C"}
    perfect = M.metrics({"1": "x", "2": "x", "3": "x", "4": "y", "5": "y", "6": "z"}, persons)
    check("measures: a perfect clustering is precision 1, recall 1, no false merge", perfect["precision"] == 1 and perfect["recall"] == 1 and perfect["falseMergeClusters"] == 0 and perfect["clusters"] == 3
          and perfect["singletons"] == 1 and perfect["f1"] == 1, str(perfect))
    mixed = M.metrics({"1": "x", "2": "x", "3": "y", "4": "y", "5": "y", "6": "z"}, persons)
    check("measures: a cluster holding two people is a false merge, precision and recall are counted by pairs", mixed["falseMergeClusters"] == 1 and mixed["pairsFound"] == 4
          and mixed["pairsRight"] == 2 and mixed["pairsTrue"] == 4 and mixed["precision"] == 0.5 and mixed["recall"] == 0.5 and mixed["falseClusterLabels"] == ["y"], str(mixed))
    alone = M.metrics(dict((r, "r" + r) for r in persons), persons)
    check("measures: nothing grouped has an undefined precision and a zero recall", alone["precision"] is None and alone["recall"] == 0 and alone["singletons"] == 6 and alone["f1"] is None, str(alone))
    check("measures: true pairs", M.true_pairs(persons) == 4 and M.true_pairs({}) == 0)
    # clusters from the metadataset: a golden and its merged members, an unmerged golden, suspects
    rows = [meta(10, 111, 1, None, True), meta(1, 111, 2, "10"), meta(2, 111, 2, "10"), meta(3, 300), meta(4, 400), meta(5, 500), meta(6, 600), meta(11, 900, 4, None, True)]
    a = M.labels(rows, ["1", "2", "3", "4", "5", "6"])
    check("labels: members of one group (and the golden they point to) are one cluster, the others alone", a["1"] == a["2"] and len(set(a.values())) == 5, str(a))
    b = M.labels(rows, ["1", "2", "3", "4", "5", "6"], [("3", "4"), ("5", "999"), ("2", "5")])
    check("labels: a suspect pair joins two clusters, an unknown end joins nothing", b["3"] == b["4"] and b["5"] == b["1"] and b["6"] != b["1"], str(b))
    deleted = M.labels([meta(1, 7, 2, "10"), meta(2, 7, 2, "10"), meta(10, 7, 4, None, True)], ["1", "2"])
    check("labels: an unmerged (DELETED) golden connects nothing, the same group still does", deleted["1"] == deleted["2"])
    only_golden = M.labels([meta(1, 21, 2, "10"), meta(2, 22, 2, "10"), meta(10, 23, 1, None, True)], ["1", "2"])
    check("labels: two members pointing to the same live golden are one cluster even with different groups", only_golden["1"] == only_golden["2"])
    q = M.quality({"1": ("ECOM", "k1", "A"), "2": ("STORES", "k2", "A"), "3": ("LOYALTY", "k3", "B")}, [meta(10, 5, 1, None, True), meta(1, 5, 2, "10"), meta(2, 5, 2, "10"), meta(3, 8)], [("3", "10")])
    check("quality: merged only vs merged + suspects, golden count, states",
          q["merged"]["precision"] == 1 and q["merged"]["recall"] == 1 and q["withSuspects"]["falseMergeClusters"] == 1 and q["autoCreatedGoldens"] == 1
          and q["states"] == {"GOLDEN": 2, "MERGED": 2}, str(q))
    check("state is read as an ordinal, a digit string or a name", M.state_of({"state": 2}) == 2 and M.state_of({"state": "2"}) == 2 and M.state_of({"state": "MERGED"}) == 2 and M.state_of({}) == 0)
    # master records and the sources
    mapping = M.source_to_app({"ECOM": {"sourceSystem": "ECOM_CLIENTS_V5"}, "STORES": {"sourceSystem": "STORES"}})
    truth = {("ECOM", "W1"): 4, ("STORES", "T1"): 4}
    records, unknown, goldens = M.master_records([{"id": 1, "sourceSystem": "ECOM_CLIENTS_V5", "sourceId": "W1"}, {"id": 2, "sourceSystem": "stores", "sourceId": "T1"},
                                                  {"id": 3, "sourceSystem": "STORES", "sourceId": "ZZ"}, {"id": 4, "sourceSystem": None, "sourceId": None}], mapping, truth)
    check("master records: mapped to the truth by system and key, unknown sources and goldens apart", records == {"1": ("ECOM", "W1", 4), "2": ("STORES", "T1", 4)} and unknown == [("3", "STORES", "ZZ")]
          and goldens == ["4"], str((records, unknown, goldens)))
    # lot M11: a golden the add-on created carries a source ("MDM") and a generated key: told apart by RecordMetadata.autoCreated, not by an empty source
    meta_rows = [meta(4, 5, 1, None, True), meta(1, 5, 2, "4"), meta(2, 5, 2, "4"), meta(9, 7, 4, None, True)]
    auto = M.auto_created_ids(meta_rows)
    check("auto-created ids: every autoCreated row, an unmerged (DELETED) golden included", auto == {"4", "9"}, str(auto))
    rows_with_goldens = [{"id": 1, "sourceSystem": "ECOM_CLIENTS_V5", "sourceId": "W1"}, {"id": 2, "sourceSystem": "stores", "sourceId": "T1"},
                         {"id": 4, "sourceSystem": "MDM", "sourceId": "FID5979509493"}, {"id": 9, "sourceSystem": "MDM", "sourceId": "FID5979509494"}, {"id": 3, "sourceSystem": "STORES", "sourceId": "ZZ"}]
    records, unknown, goldens = M.master_records(rows_with_goldens, mapping, truth, auto)
    check("master records: the goldens with a source of their own are goldens, not unknown sources (a real unknown one still is)", set(records) == {"1", "2"} and unknown == [("3", "STORES", "ZZ")]
          and goldens == ["4", "9"], str((records, unknown, goldens)))
    records, unknown, goldens = M.master_records(rows_with_goldens, mapping, truth)
    check("master records: without the metadata the same goldens read as unknown sources (the defect of 142 on 2026-10-18)", len(unknown) == 3 and goldens == [], str((unknown, goldens)))
    q2 = M.quality({"1": ("ECOM", "W1", 4), "2": ("STORES", "T1", 4)}, meta_rows, [])
    check("quality: a golden's cluster is its MERGED members (one cluster of two, precision and recall 1), the golden is not a record", q2["merged"]["records"] == 2 and q2["merged"]["clusters"] == 1
          and q2["merged"]["precision"] == 1 and q2["merged"]["recall"] == 1 and q2["merged"]["personsPresent"] == 1, str(q2["merged"]))
    # the sample of people and the fields to print
    recs = {"1": ("ECOM", "a", 7), "2": ("STORES", "b", 7), "3": ("LOYALTY", "c", 7), "4": ("ECOM", "d", 3), "5": ("STORES", "e", 3), "6": ("STORES", "f", 9), "7": ("STORES", "g", 9)}
    sample = M.sample_persons(recs, 3)
    check("sample: the people in the most sources first, then the most records, ties by index", [p for p, _m in sample] == [7, 3, 9], str(sample))
    fields = M.pick_fields(["id", "sourceSystem", "sourceId", "firstName", "lastName", "email", "emailKey", "mobile", "mobileE164", "birthDate", "_loadId", "lastNameKey"])
    check("fields to print: names, e-mail, phones, birth date; keys and technical fields left out", fields == {"first name": ["firstName"], "last name": ["lastName"], "email": ["email"],
                                                                                                         "phone": ["mobile", "mobileE164"], "birth date": ["birthDate"]}, str(fields))
    golden = M.golden_of(["1", "2"], dict((m["functionalId"], m) for m in [meta(1, 5, 2, "10"), meta(2, 5, 2, "10"), meta(10, 5, 1, None, True)]), {})
    check("golden of a cluster: the live golden its records point to", golden == "10")
    check("golden of unmerged records: none", M.golden_of(["3"], {"3": meta(3, 8)}, {}) is None)
    # the plan, the add-on's answers
    flat = json.dumps({"decisionTree": {"root": "N1", "nodes": [{"id": "N1", "onTrue": "MATCH", "onFalse": "N2"}, {"id": "N2", "onTrue": "SUSPECTED", "onFalse": "NOT_MATCHED"}]}})
    check("plan leaves: counted in the flat tree", M.plan_leaves(flat) == {"MATCH": 1, "SUSPECTED": 1, "NOT_MATCHED": 1}, str(M.plan_leaves(flat)))
    steward = json.dumps({"decisionTree": {"root": "N1", "nodes": [{"id": "N1", "onTrue": "SUSPECTED", "onFalse": "N2"}, {"id": "N2", "onTrue": "SUSPECTED", "onFalse": "NOT_MATCHED"}]}})
    check("plan leaves: a plan that never matches by itself (every path ends at a steward)", M.plan_leaves(steward)["MATCH"] == 0)
    check("plan leaves: the older cascade shape and a text that is not a plan", M.plan_leaves('{"tiers":[{"outcome":"MATCH"},{"outcome":"SUSPECT"}]}') == {"MATCH": 1, "SUSPECTED": 1, "NOT_MATCHED": 0} and M.plan_leaves("nope") == {})
    check("policy codes", M.policy_codes({"success": True, "data": {"matchingPolicies": [{"processCode": "Client"}, {"processCode": "Client__preview"}]}}) == ["Client", "Client__preview"]
          and M.preview_codes(["Client", "Client__preview"]) == ["Client__preview"])
    inner = json.dumps({"success": True, "data": {"state": "ENABLED"}})
    plain = json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": inner}]}})
    check("tools/call answers: plain JSON and event-stream", M.mcp_text_result(plain)[0]["data"]["state"] == "ENABLED" and M.mcp_text_result("event: message\ndata: " + plain + "\n\n")[0]["success"] is True)
    check("tools/call answers: a JSON-RPC error is reported", M.mcp_text_result('{"jsonrpc":"2.0","error":{"message":"MCP server not found"}}')[0] is None
          and "not found" in M.mcp_text_result('{"jsonrpc":"2.0","error":{"message":"MCP server not found"}}')[1])
    check("judgements: floors", M.judge_precision(0.3, 10)[0] == "FAIL" and M.judge_precision(0.7, 10)[0] == "PASS" and M.judge_precision(None, 0)[0] == "SKIP"
          and M.judge_recall(0.2)[0] == "FAIL" and M.judge_recall(0.9)[0] == "PASS" and M.judge_recall(None)[0] == "SKIP")


# ------------------------------------------------------------------------------------------------------------------ the scenario against the stand-in

DIST_ENV = {"E2E_TIMEOUT_REALTIME": "1", "E2E_TIMEOUT_MERGE": "1", "E2E_TIMEOUT_CONNECTION": "1", "E2E_DIST_SETTLE": "0", "E2E_TIMEOUT_UNMERGE": "1", "E2E_TIMEOUT_UNMERGETOPIC": "1"}


def master_run(selftest, defects=(), args=None, variant="plain"):
    import b2c_master
    import b2c_standin
    server, factory = b2c_standin.serve(defects, False, True)
    try:
        # lot S1: the Kafka broker is the stand-in's FakeKafka, handed over through the scenario's two injection points; the waits of the distribution step are shortened
        full = {"through": 11, "topic_reader": factory.kafka.reader, "broker_probe": factory.kafka.probe, "topic_lister": factory.kafka.list_topics, "topic_deleter": factory.kafka.delete_topic}
        full.update(args or {})
        cls = b2c_master.SimulateFirstScenario if variant == "simulate-first" else b2c_master.MasterScenario
        code, text, sc, env = selftest.run_scenario(server.server_address[1], full, cls=cls, env_extra=DIST_ENV)
        return code, text, sc, factory
    finally:
        server.shutdown()


def failed(sc):
    return [c["name"] for c in sc.rep.checks if c["status"] == "FAIL"]


def status_of(sc, needle):
    return [c["status"] for c in sc.rep.checks if needle in c["name"]]


# a defect, the variant that shows it, and the check that guards it (names are substrings)
MASTER_DEFECTS = [
    ("mm_noplan", "plain", "a matching plan was proposed"),
    ("mm_nometadataset", "plain", "matching activation: state ENABLED and metadatasetExists true"),
    ("mm_masterrows", "plain", "Client: master rows = staging rows minus held back"),
    ("mm_masterreject", "plain", "0 open MASTER rejects"),
    ("mm_xrefdangling", "plain", "lineage: an Xref row per staged person row"),
    ("mm_unknownsource", "plain", "every master record maps back to a source row"),
    ("mm_runfails", "plain", "matching run ended DONE"),
    ("mm_sourceoverwritten", "plain", "the matching run left the source of every master record untouched"),
    ("mm_sourceoverwritten", "simulate-first", "the matching run left the source of every master record untouched"),
    ("mm_falsemerge", "plain", "METRIC false merges"),
    ("mm_falsemerge", "plain", "METRIC precision, merged only"),
    ("mm_nomatch", "plain", "METRIC recall, merged only"),
    ("mm_nomatch", "plain", "METRIC recall, merged + suspects"),
    ("mm_previewblocks", "simulate-first", "configure after a simulation: the person entity is configured"),
    ("mm_previewleft", "simulate-first", "no preview policy is left on the table"),
    ("mm_childplan", "plain", "child entities follow their parent"),
    # lot M4 (2026-10-18): a member of the business object without a default matching policy or a merge policy makes the add-on refuse the run
    ("mm_nomemberpolicy", "plain", "every member table of the business object holds a default matching policy and a merge policy"),
    ("mm_nomemberpolicy", "simulate-first", "every member table of the business object holds a default matching policy and a merge policy"),
    # lot M8 (2026-10-18 02:57): a member's merge policy in another mode than the main table's makes the add-on refuse the run ("Ensure the merge policies use the same 'Mode'")
    ("mm_membermode", "plain", "every member merge policy carries the mode of the main table's merge policy"),
    ("mm_membermode", "simulate-first", "every member merge policy carries the mode of the main table's merge policy"),
]


def test_master_flows(check, selftest):
    code, text, sc, factory = master_run(selftest)
    check("master good run (plain): exit 0", code == 0, text[-4000:])
    check("master good run (plain): 0 failed", sc.rep.counts()["FAIL"] == 0, "\n".join(c["name"] + " " + c["detail"] for c in sc.rep.checks if c["status"] == "FAIL"))
    passed = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
    for needle in ("ground truth covers every source row", "master model and dataset built", "Client: master rows = staging rows minus held back", "Achat: master rows", "0 open MASTER rejects",
                   "lineage: an Xref row per staged person row", "a matching plan was proposed for the person entity (Client)", "METRIC auto-match leaves in the proposed plan: 1 of 3",
                   "the matching plan(s) accepted", "configure without a simulation: the person entity is configured (plan APPLIED)", "configure without a simulation: the configure job ended DONE",
                   "matching activation: state ENABLED and metadatasetExists true", "a matching policy exists on the person table", "no preview policy is left on the table", "matching run ended DONE",
                   "every member table of the business object holds a default matching policy and a merge policy",
                   "every member merge policy carries the mode of the main table's merge policy",
                   "holds a row per master record", "the matching run left the source of every master record untouched", "every master record maps back to a source row", "METRIC false merges (merged groups holding 2+ people) = 0",
                   "METRIC precision, merged only = 1.000", "METRIC recall, merged only = 1.000", "METRIC precision, merged + suspects = 1.000", "METRIC recall, merged + suspects = 1.000"):
        check("master good run passes: " + needle, any(needle in n for n in passed), needle + " | " + str([n for n in passed if "METRIC" in n or "matching" in n])[:1200])
    infos = [c["name"] for c in sc.rep.checks if c["status"] == "INFO"]
    check("master good run: suspect chains 0 and the golden / merged counts are METRIC lines (INFO, never judged)", any(n.startswith("METRIC suspect chains = 0") for n in infos)
          and any(n.startswith("METRIC golden records created by the add-on = ") and "automatic merge was not turned on" in n for n in infos)
          and not any("records grouped" in c["name"] for c in sc.rep.checks), str(infos))
    check("master good run: no false pair, said so", "false pairs: 0" in text and sc.details["quality"]["falsePairsTotal"] == 0 and sc.details["quality"]["falsePairs"] == [], text[-2500:])
    check("master good run: the golden record check prints three sampled people with their golden", text.count("golden check - person") == 3 and "golden " in text and "lastName=" in text, text[-2500:])
    check("master good run: no simulation was made in the plain variant", "simulation of the accepted plan" not in " ".join(c["name"] for c in sc.rep.checks))
    check("master good run: the project is left in place and the addresses of steps 5 and 6 are printed", sc.code in factory.table("Project") and "PROJECT LEFT IN PLACE" in text
          and text.count("serviceParams=") >= 2 and "wizard, step 5" in text and "wizard, step 6" in text, text[-1500:])
    check("master good run: the secret is never printed", "not-a-real-secret" not in text)
    check("master good run: the report carries the quality, the plan leaves and the golden check", all(k in sc.details for k in ("quality", "planLeaves", "goldenCheck", "plans", "masterCounts", "truth")))
    quality = sc.details["quality"]
    check("master good run: the measures in the report", quality["merged"]["precision"] == 1.0 and quality["merged"]["recall"] == 1.0 and quality["merged"]["falseMergeClusters"] == 0
          and quality["personsPresent"] == quality["merged"]["clusters"] and quality["autoCreatedGoldens"] > 0, str(quality))
    check("master good run: the mcp tools called are read tools", True)
    try:
        sc.mcp("activate_matching", "/root/Client")
        check("the match-merge server is only ever called with read tools", False)
    except Exception as exc:    # noqa: BLE001  (client.SafetyError)
        check("the match-merge server is only ever called with read tools", type(exc).__name__ == "SafetyError")
    # the second variant
    code, text, sc, factory = master_run(selftest, variant="simulate-first")
    check("master good run (simulate-first): exit 0", code == 0, text[-3500:])
    names = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
    check("master good run (simulate-first): the plan is simulated on a preview policy, then configured after a simulation",
          any("simulation of the accepted plan ran" in n for n in names) and any("configure after a simulation: the person entity is configured" in n for n in names)
          and any("no preview policy is left" in n for n in names), str(names)[:1500])
    # the plan that never merges by itself: (a) is not judged, (b) is the measure
    code, text, sc, factory = master_run(selftest, ("mm_suspectonly",))
    check("suspect-only plan: exit 0", code == 0, text[-3500:])
    check("suspect-only plan: the auto-match leaves metric is a SKIP with its reason, recall of the merged measure is not judged", status_of(sc, "METRIC auto-match leaves in the proposed plan: 0 of 3") == ["SKIP"]
          and status_of(sc, "METRIC recall, merged only") == ["SKIP"] and status_of(sc, "METRIC precision, merged only") == ["SKIP"], str([(c["status"], c["name"]) for c in sc.rep.checks if "METRIC" in c["name"]]))
    check("suspect-only plan: merged + suspects is the measure and finds the duplicates", status_of(sc, "METRIC recall, merged + suspects = 1.000") == ["PASS"]
          and status_of(sc, "METRIC precision, merged + suspects = 1.000") == ["PASS"], str([(c["status"], c["name"]) for c in sc.rep.checks if "METRIC" in c["name"]]))
    check("suspect-only plan: the golden check says the records were not merged", "no golden: these records were not merged" in text, text[-2500:])
    test_auto_merge_and_chains(check, selftest)
    # the defect of 2026-10-17: the plain variant is fine, the simulate-first variant FAILS and stops at the configure
    code, text, sc, factory = master_run(selftest, ("mm_previewblocks",))
    check("preview defect, plain variant: configure without a simulation still passes (exit 0)", code == 0, "\n".join(failed(sc)))
    code, text, sc, factory = master_run(selftest, ("mm_previewblocks",), variant="simulate-first")
    check("preview defect, simulate-first: exit 1", code == 1, text[-2000:])
    check("preview defect, simulate-first: 'configure after a simulation' FAILS and says why", any("configure after a simulation: the person entity is configured" in n for n in failed(sc))
          and "Client__preview is already the manual-merge policy" in text, str(failed(sc)))
    check("preview defect, simulate-first: nothing is worked around (no matching run, no quality measure after the refusal)", not any("matching run" in c["name"] or c["name"].startswith("METRIC precision")
                                                                                                                                  for c in sc.rep.checks), str([c["name"] for c in sc.rep.checks][-8:]))
    for defect, variant, needle in MASTER_DEFECTS:
        code, text, sc, factory = master_run(selftest, (defect,), variant=variant)
        f = failed(sc)
        check("master defect %s: exit 1" % defect, code == 1, text[-1500:])
        check("master defect %s is caught by '%s'" % (defect, needle), any(needle in n for n in f), str(f))
    # lot M2: a preview that cannot be removed but is named by the configure job is not the defect (the defect is the silent leftover)
    code, text, sc, factory = master_run(selftest, ("mm_previewleftreported",), variant="simulate-first")
    check("a preview left on the table and reported by the configure job passes the cleanup check and says so", code == 0 and any("no preview policy is left" in n for n in
          [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]) and any("preview policy left on the table and reported" in c["name"] for c in sc.rep.checks), text[-2500:])
    # the good run has no child entity: said, not failed
    code, text, sc, factory = master_run(selftest)
    check("a model with no child entity says so", any(c["name"] == "child entities" or "child entities" in c["name"] for c in sc.rep.checks), str([c["name"] for c in sc.rep.checks][:40]))
    # a missing route ends the run with exit 3 and names the route
    code, text, sc, factory = master_run(selftest, ("nobuildroute",))
    check("a build without master/build: exit 3 naming the route", code == 3 and "POST /projects/{x}/master/build" in text, text[-1500:])
    # --through stops where asked, the cleanup deletes the project
    code, text, sc, factory = master_run(selftest, (), {"through": 7})
    check("--through 7: master built, matching and quality skipped", code == 0 and sum(1 for c in sc.rep.checks if c["status"] == "SKIP" and c["step"] in ("8", "9")) == 2
          and any("0 open MASTER rejects" in c["name"] for c in sc.rep.checks), text[-1200:])
    code, text, sc, factory = master_run(selftest, (), {"cleanup": True})
    check("master cleanup: exit 0 and the project deleted, no 'left in place'", code == 0 and "project deleted with its generated objects" in text and "PROJECT LEFT IN PLACE" not in text, text[-1500:])
    # lot M18 (2026-10-18): the plan's matching items are named after TABLES (/root/Client), accepted; the cleanup then reads the add-on's configuration dataset for the project's publication
    check("master cleanup: the plan's business-object and matching items (named after tables) are accepted", "deletion plan touches only this run's objects" not in " ".join(failed(sc))
          and any(c["name"] == "project deleted with its generated objects" and c["status"] == "PASS" for c in sc.rep.checks), text[-1500:])
    check("master cleanup: the add-on's dataset holds no row of the project's publication (PASS)", any("the add-on's configuration dataset holds no row of %s_Master" % sc.code in c["name"] and c["status"] == "PASS"
          for c in sc.rep.checks) and not any("Match and Merge" in l for l in sc.leftovers), str([(c["status"], c["name"]) for c in sc.rep.checks][-5:]))
    code, text, sc, factory = master_run(selftest, ("mm_boleft",), {"cleanup": True})
    check("master cleanup, rows left in the add-on's dataset: FAIL naming the business object and the table configurations, exit 1, left behind", code == 1 and any(
          "the add-on's configuration dataset holds no row of %s_Master" % sc.code in c["name"] and c["status"] == "FAIL" and "business object configuration 9" in c["detail"] and "table configuration 122" in c["detail"]
          for c in sc.rep.checks) and any("Match and Merge configuration rows of %s_Master (3)" % sc.code in l for l in sc.leftovers), text[-2000:])
    code, text, sc, factory = master_run(selftest, ("mm_boleft", "noorphanroute"), {"cleanup": True})
    check("master cleanup on a build without GET /matching/orphans: skipped and left behind as 'could not check', never passed", code == 0 and any(
          "the add-on's configuration dataset holds no row of" in c["name"] and c["status"] == "SKIP" for c in sc.rep.checks) and any("could not check" in l for l in sc.leftovers)
          and not any("the add-on's configuration dataset holds no row of" in c["name"] and c["status"] == "PASS" for c in sc.rep.checks), text[-2000:])


def test_auto_merge_and_chains(check, selftest):
    """Lot M10b (2026-10-18): the option --auto-merge, and the split of 'a cluster that mixes people' into false merges (merged pairs only, FAIL) and suspect chains (merged + suspects, never a FAIL)."""
    def metrics_of(sc):
        return [(c["status"], c["name"]) for c in sc.rep.checks if "METRIC" in c["name"]]
    # the live state: a MATCH leaf, autoMerge false, nothing merges, no option
    code, text, sc, factory = master_run(selftest, ("mm_automergeoff",))
    q = sc.details["quality"]
    check("live state without the option: exit 0, merged recall SKIP, nothing merged, no golden", code == 0 and status_of(sc, "METRIC recall, merged only") == ["SKIP"] and q["autoCreatedGoldens"] == 0
          and q["mergedRecords"] == 0 and not any("automatic merge turned on" in c["name"] for c in sc.rep.checks) and not sc.details.get("autoMerge"), "\n".join(str(m) for m in metrics_of(sc)))
    check("live state without the option: the plan stays autoMerge=false", all(not p["autoMerge"] for p in factory.table("MatchPlan").values()))
    # the same, with the option: the write the toggle makes, then the plan merges by itself
    for variant in ("plain", "simulate-first"):
        code, text, sc, factory = master_run(selftest, ("mm_automergeoff",), {"auto_merge": True}, variant=variant)
        q = sc.details["quality"]
        passed = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
        check("--auto-merge (%s): exit 0" % variant, code == 0, "\n".join(failed(sc)) + text[-1500:])
        check("--auto-merge (%s): the toggle's write is made on the accepted person plan and read back" % variant, any("automatic merge turned on for the person plan (MatchPlan.autoMerge = true" in n for n in passed)
              and all(p["autoMerge"] for p in factory.table("MatchPlan").values() if p["entity"] == "180"), str(passed)[:800])
        check("--auto-merge (%s): the applied plan keeps it, the merged-only measures are judged" % variant, any("the applied plan still has automatic merge on" in n for n in passed)
              and status_of(sc, "METRIC recall, merged only = 1.000") == ["PASS"] and status_of(sc, "METRIC precision, merged only = 1.000") == ["PASS"], "\n".join(str(m) for m in metrics_of(sc)))
        check("--auto-merge (%s): the golden records and the merged records are counted and printed" % variant, q["autoCreatedGoldens"] > 0 and q["mergedRecords"] > q["autoCreatedGoldens"]
              and any(c["status"] == "INFO" and c["name"].startswith("METRIC golden records created by the add-on = %d (autoCreated); records merged into a golden = %d (state MERGED)" % (q["autoCreatedGoldens"], q["mergedRecords"]))
                      and "automatic merge was not turned on" not in c["name"] for c in sc.rep.checks), str(q)[:600])
        check("--auto-merge (%s): the goldens (source MDM, a generated key) are not records without a source: the check passes, they are counted apart as information" % variant,
              any("every master record maps back to a source row" in n for n in passed)
              and any(c["status"] == "INFO" and c["name"] == "%d golden records created by the add-on, not source rows" % q["autoCreatedGoldens"] for c in sc.rep.checks)
              and not any("every master record maps back" in n for n in failed(sc)), str([c["name"] for c in sc.rep.checks if c["status"] in ("INFO", "FAIL")])[:900])
        check("--auto-merge (%s): the report names the route and the leaves" % variant, sc.details["autoMerge"]["requested"] is True and "setMatchPlanAutoMerge" in sc.details["autoMerge"]["route"]
              and sc.details["autoMerge"]["matchLeaves"] == 1, str(sc.details.get("autoMerge")))
    # the older shape (a golden with no source at all) is still told apart the same way
    code, text, sc, factory = master_run(selftest, ("mm_automergeoff", "mm_goldennosource"), {"auto_merge": True})
    check("--auto-merge, goldens without any source: exit 0, the same count", code == 0 and any(c["status"] == "INFO" and c["name"].endswith("golden records created by the add-on, not source rows") for c in sc.rep.checks),
          "\n".join(failed(sc)) + text[-800:])
    # the write is accepted but the add-on does not merge: the merged-only recall is judged (the plan CAN merge) and FAILS
    code, text, sc, factory = master_run(selftest, ("mm_automergeoff", "mm_automergeignored"), {"auto_merge": True})
    check("--auto-merge, a run that merges nothing: exit 1, the merged-only recall FAILS", code == 1 and any("METRIC recall, merged only" in n for n in failed(sc)), "\n".join(str(m) for m in metrics_of(sc)))
    # no MATCH leaf in the plan: written like the UI would, said, recall not judged
    code, text, sc, factory = master_run(selftest, ("mm_suspectonly",), {"auto_merge": True})
    check("--auto-merge on a plan with no MATCH leaf: exit 0, said, merged recall stays SKIP", code == 0 and any("automatic merge is on but the plan has no MATCH leaf" in c["name"] for c in sc.rep.checks)
          and status_of(sc, "METRIC recall, merged only") == ["SKIP"], text[-1500:])
    # suspect chains: one false suspect pair chains two people. No false merge, so no FAIL; the chain is a METRIC line, the pair is listed
    code, text, sc, factory = master_run(selftest, ("mm_suspectchain",))
    q = sc.details["quality"]
    check("suspect chain: exit 0 (a chain of suspects is not a false merge)", code == 0, "\n".join(failed(sc)) + text[-1500:])
    check("suspect chain: false merges 0, one suspect chain of 2 records, reported as METRIC INFO", status_of(sc, "METRIC false merges (merged groups holding 2+ people) = 0") == ["PASS"]
          and q["falseMerges"]["clusters"] == 0 and q["suspectChains"]["clusters"] == 1 and q["suspectChains"]["largestCluster"] == 2
          and any(c["status"] == "INFO" and c["name"].startswith("METRIC suspect chains = 1 ") and "largest 2 record(s)" in c["name"] for c in sc.rep.checks), str(q["suspectChains"]) + str(metrics_of(sc)))
    check("suspect chain: the clusters-vs-people check no longer exists (it failed on exactly this)", not any("records grouped" in c["name"] for c in sc.rep.checks)
          and q["suspectChains"]["clustersTotal"] < q["suspectChains"]["people"], str(q["suspectChains"]))
    check("suspect chain: the false pair is printed (kind, both sources, ids, shared fields) and kept in the report", q["falsePairsTotal"] == 1 and len(q["falsePairs"]) == 1 and q["falsePairs"][0]["kind"] == "suspect"
          and q["falsePairs"][0]["a"]["person"] != q["falsePairs"][0]["b"]["person"] and q["falsePairs"][0]["a"]["sourceSystem"] and q["falsePairs"][0]["a"]["sourceId"]
          and "false pairs: 1" in text and "note    suspect  " in text and "<->" in text and "| equal:" in text, text[-3000:] + str(q["falsePairs"]))
    check("suspect chain: the precision of the suspects still judges it (one false pair in many)", status_of(sc, "METRIC precision, merged + suspects") and status_of(sc, "METRIC precision, merged + suspects")[0] == "PASS")
    # a real false merge: FAIL on the merged-only metric, merged pairs listed first, at most 20
    code, text, sc, factory = master_run(selftest, ("mm_falsemerge",))
    q = sc.details["quality"]
    check("false merge: exit 1 on 'false merges' (merged pairs only), the examples and the pairs are in the report", code == 1 and any("METRIC false merges" in n for n in failed(sc)) and q["falseMerges"]["clusters"] > 0
          and q["falsePairsTotal"] >= q["falseMerges"]["falsePairs"] > 0 and 0 < len(q["falsePairs"]) <= 20 and q["falsePairs"][0]["kind"] == "merged" and "note    merged  " in text, text[-2500:])
    check("false merge: at most 20 pairs printed whatever the total", text.count("note    merged  ") + text.count("note    suspect  ") <= 20 and q["falsePairsTotal"] > 20 and len(q["falsePairs"]) == 20, str(q["falsePairsTotal"]))


def test_false_pair_logic(check):
    records = {"1": ("ECOM", "w1", 7), "2": ("STORES", "t1", 7), "3": ("LOYALTY", "c1", 9), "4": ("ECOM", "w2", 8), "5": ("ECOM", "w3", 5)}
    rows = {"1": {"id": 1, "sourceSystem": "ECOM", "sourceId": "w1", "lastName": "Martin", "mobileE164": "+33600000001", "email": "", "Linked_Field_State": 1},
            "2": {"id": 2, "sourceSystem": "STORES", "sourceId": "t1", "lastName": "Martin", "mobileE164": "+33600000001", "email": "", "Linked_Field_State": 1},
            "3": {"id": 3, "sourceSystem": "LOYALTY", "sourceId": "c1", "lastName": "Martin", "mobileE164": "+33600000009", "email": "a" * 60, "Linked_Field_State": 1},
            "4": {"id": 4, "sourceSystem": "ECOM", "sourceId": "w2", "lastName": "Durand", "mobileE164": "+33600000009", "email": "a" * 60, "Linked_Field_State": 1},
            "5": {"id": 5, "sourceSystem": "ECOM", "sourceId": "w3", "lastName": "Petit", "mobileE164": "", "email": "", "Linked_Field_State": 1}}
    eq = M.equal_fields(rows["1"], rows["3"])
    check("false pairs: equal fields are the shared non-empty business columns (technical columns, empty values left out, long values cut)",
          eq == {"lastName": "Martin"} and M.equal_fields(rows["3"], rows["4"]) == {"email": "a" * 40 + "...", "mobileE164": "+33600000009"} and M.equal_fields(None, rows["1"]) == {}, str(eq))
    # 1 and 2 merged (same person), 3 and 4 merged together... 3/4 are two people: a false MERGE; 5 alone but suspect of 1
    meta_rows = [{"functionalId": str(i), "groupId": g, "state": 1} for i, g in (("1", 10), ("2", 10), ("3", 30), ("4", 30), ("5", 50))]
    label = M.labels(meta_rows, records.keys())
    pairs, total = M.false_pairs(records, meta_rows, [("5", "1"), ("1", "2"), ("5", "999")], rows, label)
    check("false pairs: a merged cluster mixing people first, then a suspect pair of two people; a suspect of the same person or with an unknown end is not false",
          total == 2 and [p["kind"] for p in pairs] == ["merged", "suspect"] and pairs[0]["a"]["id"] == "3" and pairs[0]["b"]["id"] == "4" and pairs[0]["equalFields"].get("mobileE164") == "+33600000009"
          and pairs[1]["a"]["id"] == "5" and pairs[1]["equalFields"] == {} and "no business field in common" in M.describe_false_pair(pairs[1]), str(pairs))
    only, total = M.false_pairs(records, meta_rows, [("5", "1")], rows, label, limit=1)
    check("false pairs: the list is cut at the limit, the total is not", total == 2 and len(only) == 1)
    m = M.metrics({"1": "x", "2": "x", "3": "y", "4": "y", "5": "z"}, dict((r, rec[2]) for r, rec in records.items()))
    check("measures: the largest cluster that mixes people is reported", m["largestMixedCluster"] == 2 and M.metrics({"1": "a", "2": "b"}, {"1": 1, "2": 2})["largestMixedCluster"] == 0, str(m))


def test_master_cli(check, selftest):
    import b2c_master
    import b2c_standin
    out = io.StringIO()
    check("master dry-run: exit 0 with the truth, the plan and the routes", b2c_master.dry_run(out) == 0 and "ground truth (truth_b2c.json): 684 rows" in out.getvalue()
          and "POST /projects/{x}/master/build" in out.getvalue() and "get_matching_activation_status" in out.getvalue(), out.getvalue()[:1500])
    # both variants through main(), against one stand-in: two projects, one verdict line
    server, factory = b2c_standin.serve((), False, True)
    try:
        env = {"EBX_URL": "http://127.0.0.1:%d" % server.server_address[1], "EBX_USER": "selftest", "EBX_PASSWORD": "not-a-real-secret", "E2E_POLL": "0.01",
               "E2E_OUT_DIR": tempfile.mkdtemp(prefix="b2c-master-rep-")}
        out = io.StringIO()
        rc = b2c_master.main([], env, out)
        text = out.getvalue()
        check("master main(): both variants run, two projects, one verdict line", rc == 0 and "variant: plain" in text and "variant: simulate-first" in text and "VARIANTS: plain PASS; simulate-first PASS" in text
              and len([c for c in factory.table("Project")]) == 2, text[-1500:])
        reports = sorted(f for f in os.listdir(env["E2E_OUT_DIR"]) if f.endswith(".json"))
        check("master main(): one JSON report per variant", len(reports) == 2 and any("simulate-first" in r for r in reports), str(reports))
        check("master main(): the secret is never printed", "not-a-real-secret" not in text)
    finally:
        server.shutdown()
    server, factory = b2c_standin.serve(("mm_automergeoff",), False, True)
    try:
        env = {"EBX_URL": "http://127.0.0.1:%d" % server.server_address[1], "EBX_USER": "selftest", "EBX_PASSWORD": "not-a-real-secret", "E2E_POLL": "0.01",
               "E2E_OUT_DIR": tempfile.mkdtemp(prefix="b2c-master-rep-")}
        out = io.StringIO()
        rc = b2c_master.main(["--auto-merge", "--variants", "plain"], env, out)
        text = out.getvalue()
        reports = [f for f in os.listdir(env["E2E_OUT_DIR"]) if f.endswith(".json")]
        doc = json.load(open(os.path.join(env["E2E_OUT_DIR"], reports[0]))) if len(reports) == 1 else {}
        check("master main() --auto-merge: parsed, plan merged by itself, the report carries autoMerge and the split figures", rc == 0 and "automatic merge turned on for the person plan" in text
              and doc.get("details", {}).get("autoMerge", {}).get("requested") is True and doc["details"]["quality"]["autoMerge"] is True and "suspectChains" in doc["details"]["quality"]
              and "falsePairs" in doc["details"]["quality"] and "not-a-real-secret" not in text, text[-1500:])
    finally:
        server.shutdown()
    out = io.StringIO()
    b2c_master.dry_run(out)
    check("master dry-run names the --auto-merge write", "--auto-merge" in out.getvalue() and "MatchPlan/{id} autoMerge=true" in out.getvalue(), out.getvalue()[:800])
    server, factory = b2c_standin.serve(("mm_previewblocks",), False, True)
    try:
        env = {"EBX_URL": "http://127.0.0.1:%d" % server.server_address[1], "EBX_USER": "selftest", "EBX_PASSWORD": "not-a-real-secret", "E2E_POLL": "0.01",
               "E2E_OUT_DIR": tempfile.mkdtemp(prefix="b2c-master-rep-")}
        out = io.StringIO()
        rc = b2c_master.main(["--variants", "both"], env, out)
        check("master main() with the preview defect: plain PASS, simulate-first FAIL, exit 1", rc == 1 and "VARIANTS: plain PASS; simulate-first FAIL" in out.getvalue(), out.getvalue()[-1200:])
    finally:
        server.shutdown()
    import contextlib
    with contextlib.redirect_stderr(io.StringIO()):
        check("master main(): an unknown variant is refused", b2c_master.main(["--variants", "x"], {}, io.StringIO()) == 2)
        check("master main(): --cleanup-project refuses a code that is not this scenario's", b2c_master.main(["--cleanup-project", "E2EB2C20261017"], {"EBX_URL": "http://127.0.0.1:1", "EBX_USER": "u", "EBX_PASSWORD": "p"},
                                                                                                           io.StringIO()) == 2)


# ------------------------------------------------------------------------------------------------------------------ lot S1: distribution (step 10) and summary (step 11)

class FakeNotice(object):
    CLI_NOTICE = "Option --property is deprecated and will be removed in a future version. Use --formatter-property instead."


def test_distribution_logic(check):
    """The pure part: the console consumer's output, the broker probe, the golden-only audit, the value comparison, the pickers."""
    # the console consumer: key, a TAB, the value; a message without a key prints null
    out = L.parse_console_output('1\t{"id":1}\nnull\t{"x":2}\n\nno tab here\n')
    check("console output: key and value split on the first TAB, a keyless message prints null as its key, a line with no TAB is not a message", out == [{"key": "1", "value": '{"id":1}'}, {"key": "null", "value": '{"x":2}'}], str(out))
    msgs, notices = L.split_console_output(FakeNotice.CLI_NOTICE + '\n7\t{"id":7,"_change":"UPSERT"}\n')
    check("console output: the CLI's own deprecation notice (live 2026-10-18, `Option --property is deprecated...`) is a notice, never a message", msgs == [{"key": "7", "value": '{"id":7,"_change":"UPSERT"}'}]
          and notices == [FakeNotice.CLI_NOTICE], str((msgs, notices)))
    # the broker probe never fails, never hangs: it says why the distribution checks are skipped
    home = tempfile.mkdtemp(prefix="kafka-home-")
    try:
        os.makedirs(os.path.join(home, "bin"))
        for name in ("kafka-topics.sh", "kafka-console-consumer.sh"):
            open(os.path.join(home, "bin", name), "w").close()
        settings = {"bootstrap": "localhost:9082", "home": home, "idle_ms": 1000}

        def refuse(addr, timeout=None):
            raise ConnectionRefusedError(61, "Connection refused")

        class Done(object):
            def __init__(self, rc, out="", err=""):
                self.returncode, self.stdout, self.stderr = rc, out, err

        class Sock(object):
            def close(self):
                pass
        ok, why = L.kafka_probe(settings, run=lambda *a, **k: Done(0), connect=refuse)
        check("probe: a socket that refuses is a down broker, said with the address", not ok and "no Kafka broker answers at localhost:9082" in why, why)
        ok, why = L.kafka_probe(settings, run=lambda *a, **k: Done(0, "t1\nt2\n"), connect=lambda addr, timeout=None: Sock())
        check("probe: a broker that answers kafka-topics.sh --list is up (2 topics)", ok and "2 topic(s)" in why, why)
        ok, why = L.kafka_probe(settings, run=lambda *a, **k: Done(1, "", "Timed out waiting for a node assignment"), connect=lambda addr, timeout=None: Sock())
        check("probe: a socket that answers but a CLI that fails is not up", not ok and "failed" in why, why)

        def too_slow(*a, **k):
            raise subprocess.TimeoutExpired("x", 30)
        ok, why = L.kafka_probe(settings, run=too_slow, connect=lambda addr, timeout=None: Sock())
        check("probe: a CLI that does not answer in 30 s is not up", not ok and "30 s" in why, why)
        ok, why = L.kafka_probe(dict(settings, home="/nonexistent-kafka-home"), run=lambda *a, **k: Done(0), connect=lambda addr, timeout=None: Sock())
        check("probe: a missing CLI names E2E_KAFKA_HOME", not ok and "E2E_KAFKA_HOME" in why, why)
        read = L.kafka_cli_reader(settings, run=lambda *a, **k: Done(1, '5\t{"id":5}\n', "TimeoutException\nProcessed a total of 1 messages"))
        msgs, err = read("t")
        check("topic reader: the timeout exit (status 1) after the messages is the normal end", err is None and msgs == [{"key": "5", "value": '{"id":5}'}], str((msgs, err)))
        msgs, err = L.kafka_cli_reader(settings, run=lambda *a, **k: Done(1, "", "Processed a total of 0 messages"))("t")
        check("topic reader: an empty topic is no message and no error", msgs == [] and err is None, str((msgs, err)))
        msgs, err = L.kafka_cli_reader(settings, run=lambda *a, **k: Done(1, "", "Connection to node -1 (localhost/127.0.0.1:9082) could not be established. Connection refused"))("t")
        check("topic reader: a broker that cannot be reached is an error, not an empty topic", msgs == [] and err and "could not be reached" in err, str((msgs, err)))
        msgs, err = L.kafka_cli_reader(settings, run=lambda *a, **k: Done(2, "", "Error: Could not find or load main class"))("t")
        check("topic reader: a CLI that crashes is an error", err and "exited 2" in err, str((msgs, err)))
        seen = []
        L.kafka_cli_reader(settings, run=lambda cmd, **k: seen.append(cmd) or Done(0))("my.topic", 5000)
        check("topic reader: the command reads the topic from the beginning with the key, and stops after the idle time", seen and "--from-beginning" in seen[0] and "print.key=true" in seen[0]
              and seen[0][seen[0].index("--timeout-ms") + 1] == "5000" and seen[0][seen[0].index("--topic") + 1] == "my.topic", str(seen))
        check("topic reader (lot S4): the key is asked with --formatter-property print.key=true and key.separator=TAB, not the deprecated --property", seen and "--property" not in seen[0]
              and seen[0][seen[0].index("--formatter-property") + 1] == "print.key=true" and "key.separator=\t" in seen[0], str(seen))
        msgs, err = L.kafka_cli_reader(settings, run=lambda *a, **k: Done(1, FakeNotice.CLI_NOTICE + '\n5\t{"id":5}\n', "TimeoutException\nProcessed a total of 1 messages"))("t")
        check("topic reader (lot S4): the CLI's deprecation notice on stdout is not a message", err is None and msgs == [{"key": "5", "value": '{"id":5}'}], str((msgs, err)))
        calls = []

        def old_cli(cmd, **k):
            calls.append(cmd)
            if "--formatter-property" in cmd:
                return Done(1, "", "joptsimple.UnrecognizedOptionException: formatter-property is not a recognized option")
            return Done(1, '5\t{"id":5}\n', "TimeoutException")
        msgs, err = L.kafka_cli_reader(settings, run=old_cli)("t")
        check("topic reader (lot S4): an older CLI that does not know --formatter-property is read again with --property", err is None and msgs == [{"key": "5", "value": '{"id":5}'}] and len(calls) == 2
              and "--property" in calls[1] and "--formatter-property" not in calls[1], str((msgs, err, calls)))
        msgs, err = L.kafka_cli_reader(dict(settings, home="/nonexistent-kafka-home"))("t")
        check("topic reader: a missing CLI is an error naming E2E_KAFKA_HOME", err and "E2E_KAFKA_HOME" in err, str(err))
    finally:
        shutil.rmtree(home, ignore_errors=True)
    env_settings = L.kafka_settings({"E2E_KAFKA_BOOTSTRAP": "broker:9999", "E2E_KAFKA_HOME": "/k", "E2E_KAFKA_IDLE_MS": "x"})
    check("settings: from the environment, an unreadable idle time falls back to 10 s", env_settings == {"bootstrap": "broker:9999", "home": "/k", "idle_ms": 10000}, str(env_settings))
    check("settings: the defaults are the local broker", L.kafka_settings({})["bootstrap"] == "localhost:9082" and L.kafka_settings({})["home"].endswith("kafka_2.13-4.2.0"))
    # which records a target must send
    meta = [{"functionalId": "1", "state": 1}, {"functionalId": "2", "state": 2, "goldenId": "9"}, {"functionalId": "3", "state": 2, "goldenId": "9"}, {"functionalId": "4", "state": 4, "autoCreated": True},
            {"functionalId": "5", "state": 3}, {"functionalId": "9", "state": 1, "autoCreated": True}]
    sets = M.publication_sets([1, 2, 3, 4, 5, 6, 9], meta)
    check("golden sets: GOLDEN and no metadata are sent, MERGED and DELETED are not, PENDING is neither", sets["golden"] == {"1", "6", "9"} and sets["merged"] == {"2", "3"} and sets["deleted"] == {"4"}
          and sets["pending"] == {"5"} and sets["autoCreated"] == {"4", "9"}, str(sets))
    msgs = [{"key": "1", "value": '{"id": 1, "lastName": "A"}'}, {"key": "9", "value": '{"id": 9}'}, {"key": "9", "value": '{"id": 9}'}, {"key": "2", "value": '{"id": 2}'}, {"key": "4", "value": '{"id": 4}'},
            {"key": "77", "value": '{"id": 77}'}, {"key": "8", "value": '{"id": 6}'}, {"key": "2", "value": '{"id": 2, "retired": true, "retiredTo": "9"}'}, {"key": "5", "value": "not json"}]
    a = M.audit_publication(msgs, sets)
    check("audit: a golden record without a message, one sent twice, a merged member, a deleted golden, a key that is no record, a key that is not the id, a retire notice, a message that is not JSON",
          a["missing"] == ["6"] and a["duplicates"] == ["9"] and a["mergedPublished"] == ["2"] and a["deletedPublished"] == ["4"] and a["unknown"] == ["8", "77"] and a["keyMismatch"] == [("8", "6")]
          and len(a["notices"]) == 1 and a["notices"][0][0] == "2" and a["notJson"] == 1 and a["data"] == 7, str(a))
    clean = M.audit_publication([{"key": "1", "value": '{"id": 1}'}, {"key": "6", "value": '{"id": 6}'}, {"key": "9", "value": '{"id": 9}'}], sets)
    check("audit: one message per golden record and nothing else is clean", not (clean["missing"] or clean["duplicates"] or clean["mergedPublished"] or clean["deletedPublished"] or clean["unknown"] or clean["keyMismatch"]))
    # lot S4: the payload convention of lot S2 (`_change` on every message)
    kinds = [M.message_kind(d) for d in ({"_change": "UPSERT", "id": 1}, {"id": 1}, {"_change": "CREATE", "id": 1}, {"_change": "RETIRE", "id": 2, "retired": True}, {"id": 2, "retired": True},
                                         {"_change": "DELETE", "id": 3, "deleted": True}, {"_change": "UPSERT", "id": 4, "retired": True})]
    check("message kind: _change decides (UPSERT and any other value = a record, RETIRE, DELETE); with no _change `retired: true` is a retire notice", kinds == ["DATA", "DATA", "DATA", "RETIRE", "RETIRE", "DELETE", "DATA"], str(kinds))
    msgs2 = [{"key": "1", "value": '{"id": 1, "lastName": "A", "_change": "UPSERT"}'}, {"key": "9", "value": '{"id": 9, "_change": "UPSERT"}'}, {"key": "6", "value": '{"id": 6, "_change": "UPSERT"}'},
             {"key": "2", "value": '{"_change": "RETIRE", "id": 2, "retired": true, "retiredTo": "9", "reason": "MERGE", "mergeId": 3, "at": "x"}'},
             {"key": "4", "value": '{"_change": "RETIRE", "id": 4, "retired": true, "reason": "UNMERGE", "at": "x"}'}, {"key": "5", "value": '{"_change": "DELETE", "id": 5, "deleted": true, "at": "x"}'}]
    a2 = M.audit_publication(msgs2, sets)
    check("audit with _change: retire and delete notices are counted apart and never as a merged member or a deleted golden published; a record message needs _change UPSERT",
          a2["data"] == 3 and len(a2["notices"]) == 2 and len(a2["deletes"]) == 1 and a2["mergedPublished"] == [] and a2["deletedPublished"] == [] and a2["notJson"] == 0 and a2["missing"] == []
          and a2["changes"] == {"UPSERT": 3, "RETIRE": 2, "DELETE": 1}, str(a2))
    check("live-only violations: only RECORD messages of a merged or deleted record count (a retire notice is the point of the notice)", M.live_only_violations(msgs2, sets) == ([], [])
          and M.live_only_violations(msgs2 + [{"key": "2", "value": '{"_change": "UPSERT", "id": 2}'}, {"key": "4", "value": '{"id": 4}'}], sets) == (["2"], ["4"]))
    check("foreign fields: a field starting with _ (_change, _loadId...) is technical and never foreign", M.foreign_fields([{"id": 1, "_change": "UPSERT", "_x": 1, "buyer": 2}], ["id"]) == {"buyer": 1})
    check("message against row: _change is not compared with a column", M.compare_message_to_row({"id": 1, "lastName": "Martin", "_change": "UPSERT"}, {"id": 1, "lastName": "Martin", "_change": "x"}) == [])
    rows3 = [{"id": 1, "Linked_Field_State": 1}, {"id": 2, "Linked_Field_State": 2}, {"id": 3, "Linked_Field_State": "2"}, {"id": 4, "Linked_Field_State": 1}, {"id": 5, "Linked_Field_State": 4}, {"id": 6}]
    s3 = M.publication_sets_from_rows(rows3)
    check("sets from a table's own Linked_Field_State: GOLDEN (and no state), MERGED, DELETED", s3["golden"] == {"1", "4", "6"} and s3["merged"] == {"2", "3"} and s3["deleted"] == {"5"}, str(s3))
    line, explained = M.member_table_figure([{"id": i, "Linked_Field_State": st} for i, st in ((1, 1), (2, 1), (3, 2), (4, 2))], 2)
    check("member table figure: 4 rows = 2 GOLDEN + 2 MERGED against 2 messages is explained; 3 messages are not; a table with no state column has no figure",
          explained and "2 GOLDEN, 2 MERGED" in line and not M.member_table_figure([{"id": 1, "Linked_Field_State": 1}], 3)[1] and M.member_table_figure([{"id": 1}], 1) == (None, False))
    recs = {"1": ("ECOM", "a", 7), "2": ("STORES", "b", 7), "3": ("ECOM", "c", 8), "4": ("CRM", "d", 8), "5": ("CRM", "e", 9)}
    meta5 = [{"functionalId": "3", "state": 2, "goldenId": "20"}, {"functionalId": "4", "state": 2, "goldenId": "20"}, {"functionalId": "20", "state": 1, "autoCreated": True}, {"functionalId": "1", "state": 1}]
    missed = M.missed_true_pairs(recs, meta5, [("1", "2")])
    check("missed true pairs: a person with 2+ source records still GOLDEN (person 7: 1 and 2, 2 has no metadata so it is golden), the suspect ends named; a merged person is not missed", missed == [(7, ["1", "2"], ["1", "2"])], str(missed))
    pick_u = M.pick_unmerge_golden(meta5 + [{"functionalId": "30", "state": 1, "autoCreated": True}, {"functionalId": "6", "state": 2, "goldenId": "30"}, {"functionalId": "7", "state": 2, "goldenId": "30"},
                                           {"functionalId": "8", "state": 2, "goldenId": "30"}])
    check("unmerge pick: an add-on golden in state GOLDEN with merged members, the smallest group first", pick_u == ("20", ["3", "4"]) and M.pick_unmerge_golden(meta5, avoid=["20"]) is None
          and M.pick_unmerge_golden([{"functionalId": "1", "state": 1}]) is None, str(pick_u))
    # lot S6: goldenId of RecordMetadata is a FOREIGN KEY to the business table, so the scenario's reader returns it as the Data Services dict while functionalId stays a string
    def fk(v):
        return {"content": str(v), "label": "MME", "details": "http://h/ebx-dataservices/rest/data/v1/BM/Master/root/Personne/%s" % v, "selector": "http://h/sel"}
    fkmeta = [{"functionalId": "20", "groupId": 7, "state": 1, "autoCreated": True, "goldenId": None}, {"functionalId": "3", "groupId": 7, "state": 2, "autoCreated": False, "goldenId": fk(20)},
              {"functionalId": "4", "groupId": 7, "state": 2, "autoCreated": False, "goldenId": fk(20)}, {"functionalId": "30", "groupId": 9, "state": 1, "autoCreated": True, "goldenId": None},
              {"functionalId": "6", "groupId": 9, "state": 2, "autoCreated": False, "goldenId": fk(30)}, {"functionalId": "7", "groupId": 9, "state": 2, "autoCreated": False, "goldenId": fk(30)},
              {"functionalId": "8", "groupId": 9, "state": 2, "autoCreated": False, "goldenId": fk(30)}, {"functionalId": "1", "groupId": 1, "state": 1, "autoCreated": False, "goldenId": None}]
    check("goldenId as the foreign-key dict: _text unwraps content / key, None is empty, an integral float is an integer",
          M._text(fk(536)) == "536" and M._text({"key": "12"}) == "12" and M._text({"content": {"content": " 5 "}}) == "5" and M._text({"content": None}) == "" and M._text(None) == ""
          and M._text(7.0) == "7" and M._text("a ") == "a" and M._text({"x": 1}) == "{'x': 1}")
    check("unmerge pick with goldenId as the FK dict: the smallest group's golden and its members (was None before lot S6)",
          M.pick_unmerge_golden(fkmeta) == ("20", ["3", "4"]) and M.pick_unmerge_golden(fkmeta, avoid=["20"]) == ("30", ["6", "7", "8"]) and M.pick_unmerge_golden(fkmeta, avoid=["20", "30"]) is None)
    by_fk = dict((m["functionalId"], m) for m in fkmeta)
    check("golden_of with goldenId as the FK dict: the member joins its golden's master id, a DELETED golden is not returned, a never-merged record has none",
          M.golden_of(["3", "4"], by_fk, {}) == "20" and M.golden_of(["1"], by_fk, {}) is None and M.golden_of(["6"], dict(by_fk, **{"30": dict(by_fk["30"], state=4)}), {}) is None)
    check("merge_outcome with goldenId as the FK dict: the golden id is the master id, not the dict", M.merge_outcome(fkmeta, ["3", "4"]) == ({"3": 2, "4": 2}, "20"))
    # same groupId would hide the edge: give the members DIFFERENT groups so only the goldenId edge can join them to their golden
    split = [{"functionalId": "20", "groupId": 70, "state": 1, "autoCreated": True, "goldenId": None}, {"functionalId": "3", "groupId": 71, "state": 2, "goldenId": fk(20)},
             {"functionalId": "4", "groupId": 72, "state": 2, "goldenId": fk(20)}, {"functionalId": "5", "groupId": 73, "state": 1}]
    lab = M.labels(split, ["3", "4", "5", "20"])
    check("labels with goldenId as the FK dict: members of different groups joined by the golden they point to, the golden included", lab["3"] == lab["4"] == lab["20"] and lab["5"] != lab["3"], str(lab))
    q2 = M.quality({"3": ("ECOM", "k", "A"), "4": ("CRM", "k", "A"), "5": ("CRM", "j", "B")}, split, [])
    check("quality with goldenId as the FK dict: the pair 3-4 is found, no false merge", q2["merged"]["pairsRight"] == 1 and q2["merged"]["falseMergeClusters"] == 0, str(q2["merged"]))
    paths = M.unmerge_paths("E2EMASTER2026_Master", "Master", "Personne", "300")
    check("unmerge paths: the B-prefixed dataspace first, then without it, then the double-slash table path; the golden's key is the last segment",
          paths[0].endswith("/mergingProcesses/BE2EMASTER2026_Master/Master/root/Personne/300") and paths[1].endswith("/mergingProcesses/E2EMASTER2026_Master/Master/root/Personne/300")
          and paths[2].endswith("/Master//root/Personne/300") and len(paths) == 3, str(paths))
    check("listing: at most 20 items, then how many more", M.listing(range(25)).endswith("19 (+5 more)") and M.listing(["a", "b"]) == "a, b")
    check("values: numbers by value, empty as empty, dates by their day, booleans by word, text as text", M.values_equal(3, "3.0") and M.values_equal(None, "") and M.values_equal("1980-04-02T00:00:00", "1980-04-02")
          and M.values_equal(True, "true") and not M.values_equal("Martin", "Martyn") and not M.values_equal("1980-04-02", "1980-04-03"))
    row = {"id": 1, "lastName": "Martin", "email": "m@x.fr", "Linked_Field_State": 1, "_loadId": "7"}
    check("message against row: equal fields pass, a different value is named, a message sharing nothing is an error, technical fields are not compared",
          M.compare_message_to_row({"id": 1, "lastName": "Martin", "email": "m@x.fr"}, row) == [] and M.compare_message_to_row({"id": 1, "lastName": "Marton"}, row) == [("lastName", "Marton", "Martin")]
          and M.compare_message_to_row({"zzz": 1}, row)[0][0] == "*" and M.compare_message_to_row({"id": 1, "_loadId": "9"}, row) == [])
    check("foreign fields: a field that is no column of the table shows another table's record", M.foreign_fields([{"id": 1, "lastName": "A"}, {"id": 2, "buyerLastName": "B"}], ["id", "lastName"]) == {"buyerLastName": 1})
    before = [{"key": "1", "value": "a"}, {"key": "2", "value": "b"}]
    check("new messages: the multiset difference, whatever the order; a duplicate counts", M.new_messages(before, [{"key": "2", "value": "b"}, {"key": "1", "value": "a"}, {"key": "1", "value": "c"}, {"key": "1", "value": "c"}])
          == [{"key": "1", "value": "c"}, {"key": "1", "value": "c"}] and M.new_messages(before, before) == [])
    fields = M.free_text_fields({"id": 3, "sourceSystem": "ECOM", "firstName": "Alice", "lastName": "Martin", "email": "a@b.fr", "comment": "hello world", "mobile": "+33612345678", "age": 4, "Linked_Field_State": "x"})
    check("free text fields: a comment first, then names; length kept, last three characters E2E; never an id, e-mail, phone, source", fields == [("comment", "hello woE2E"), ("firstName", "AlE2E"), ("lastName", "MarE2E")], str(fields))
    check("free text fields: a value already ending in E2E gets e2e (the write is a real change)", M.free_text_fields({"comment": "abcE2E"}) == [("comment", "abce2e")])
    rows = [{"id": 1}, {"id": 2}, {"id": 3}, {"id": 4}]
    sets2 = {"golden": {"1", "2", "3", "4"}, "autoCreated": {"1"}, "merged": set(), "deleted": set(), "pending": set()}
    pick = M.pick_realtime_record(rows, sets2, [{"functionalId": "2", "hasSuspects": True}], [("3", "9")])
    check("real-time record: a source golden alone (not created by the add-on, not flagged, not in a suspect pair), the smallest id", pick == {"id": 4}, str(pick))
    check("real-time record: falls back to a flagged one when every source golden is in a pair", M.pick_realtime_record(rows, sets2, [], [("2", "3"), ("4", "5")]) == {"id": 2})
    meta2 = [{"functionalId": "10", "groupId": 111, "state": 1}, {"functionalId": "11", "groupId": 112, "state": 1}, {"functionalId": "12", "groupId": 113, "state": 1}]
    sets3 = {"golden": {"10", "11", "12"}, "autoCreated": {"12"}, "merged": set(), "deleted": set(), "pending": set()}
    check("suspect pair: both ends golden and not created by the add-on, with the target's group; an add-on golden or an avoided record is skipped",
          M.pick_suspect_pair([("12", "10"), ("11", "10")], sets3, meta2) == ("11", "10", "111") and M.pick_suspect_pair([("11", "10")], sets3, meta2, avoid=["10"]) is None and M.pick_suspect_pair([], sets3, meta2) is None)
    check("group number: text, scientific text and float all become the integer", M.group_number("1834926167335937") == 1834926167335937 and M.group_number("1.834926167335937E15") == 1834926167335937 and M.group_number(5.0) == 5)
    merged_meta = [{"functionalId": "10", "state": 2, "goldenId": "30"}, {"functionalId": "11", "state": 2, "goldenId": "30"}]
    check("merge outcome: both members MERGED into one golden gives it; one still golden gives none", M.merge_outcome(merged_meta, ["10", "11"])[1] == "30" and M.merge_outcome(meta2, ["10", "11"])[1] is None
          and M.merge_outcome(merged_meta[:1], ["10", "11"])[1] is None)
    check("pairs and topics: table=value pairs parsed, the topic is the project code and the table in lower case", M.parse_pairs("/root/A=1, /root/B=2,bad,=3") == {"/root/A": "1", "/root/B": "2"}
          and M.expected_topic("E2EMASTER2026", "/root/Client") == "e2emaster2026.client")


def test_distribution_flows(check, selftest):
    # --- the default run does not distribute: step 10 says so, nothing is written to Connectors, step 11 reads the summary's facts with no target
    code, text, sc, factory = master_run(selftest)
    check("no --distribute: exit 0", code == 0, text[-2500:])
    check("no --distribute: step 10 is a SKIP that names the option, no connection and no target are created", status_of(sc, "distribution (wizard step 7)") == ["SKIP"] and not factory.table("Connector")
          and not factory.table("DistributionTarget") and not factory.kafka.topics and sc.details["distribution"] == {}, str(status_of(sc, "distribution")))
    names = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
    for needle in ("summary, landing zone: 534 rows", "summary, staging zone: rows per entity = rows in the staging tables", "summary, open points: no unresolved reject",
                   "summary, master zone: Client rows = rows built + goldens the add-on created", "summary, golden records: the business table's Linked_Field_State agrees with the metadataset",
                   "summary, matching card:", "summary, distribution card: no target (step 10 not run)", "summary, last full run: LANDING_LOAD", "summary, last full run: STAGING_LOAD",
                   "summary, last full run: MASTER_LOAD", "summary, last full run: MATCHING_RUN"):
        check("summary (no distribution): " + needle, any(needle in n for n in names), needle + " | " + str([n for n in names if "summary" in n])[:900])
    check("summary: said that the wizard draws it and no REST route returns it", "no REST route returns it" in text, text[-1500:])
    # --- a good distribution run (the stand-in merges by itself: merged members and add-on goldens exist, no suspect is left)
    code, text, sc, factory = master_run(selftest, (), {"distribute": True})
    check("distribution good run: exit 0", code == 0, "\n".join(failed(sc)) + text[-3500:])
    passed = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
    topic = sc.code.lower() + ".client"
    for needle in ("Kafka connection created in Connectors", "the new connection is a candidate for a target, with a write operation (publish)", "distribution target created on the new connection",
                   "configure: the outbound mappings are written", "configure: one outbound mapping per master table (/root/Achat, /root/Client)", "one topic per table: 2 tables, 2 distinct topics",
                   "the topics are named after the project (%s.<table>)" % sc.code.lower(), "install-triggers: the real-time triggers are installed on the master model %s_Master" % sc.code,
                   "first full send of the golden records ended DONE", "the target is ARMED after the first full send", "%s: one message per golden record" % topic,
                   "%s: no message for a merged member or an unmerged golden" % topic, "%s: every key is the id of a master record of Client" % topic, "%s: the key of every message is the id the message carries" % topic,
                   "%s: the fields of 3 sampled golden records equal the master row" % topic, "%s: only records of Client on the topic" % topic, "%s.achat: the table Achat has its own messages" % sc.code.lower(),
                   "%s.achat: only records of Achat on the topic" % sc.code.lower(), "real time: a golden record modified through Data Services reaches the topic as exactly one new message",
                   "live-only sends: after the real-time write no topic holds a record message for a MERGED or DELETED record",
                   "unmerge a golden record: the topic receives notices", "%s.achat: no message for a merged or deleted row of Achat" % sc.code.lower(),
                   "stop: the target's sends are stopped", "the target is disarmed at the end of the run", "summary, distribution card: the target is listed REALTIME and ARMED"):
        check("distribution good run passes: " + needle, any(needle in n for n in passed), needle + " | " + str([n for n in passed if "[" not in n and ("topic" in n or "target" in n or "real" in n)])[:1500])
    golden_ids = set(str(r["id"]) for r in factory.master_data[sc.code]["Client"] if factory.meta_state(sc.code, r["id"]) not in (2, 4))
    keys = [m["key"] for m in factory.kafka.topics[topic]]
    um = sc.details["distribution"]["unmerge"]
    # the person topic: the golden records of the first send (the unmerged golden among them) + the real-time change + the retire notice of the unmerged golden + each member that came back
    check("distribution good run: the person topic holds the golden records of the first send, the real-time change, the retire notice of the unmerged golden and the members back (every message carries _change)",
          sorted(set(keys)) == sorted(golden_ids | {um["golden"]}) and len(keys) == len(golden_ids) + 3
          and all(json.loads(m["value"]).get("_change") in ("UPSERT", "RETIRE") for m in factory.kafka.topics[topic]), "%d keys, %d golden now" % (len(keys), len(golden_ids)))
    first_send = [m for m in factory.kafka.topics[topic] if json.loads(m["value"]).get("_change") == "UPSERT" and factory.meta_state(sc.code, m["key"]) in (2, 4) and m["key"] != um["golden"]]
    check("distribution good run: no record message for a merged member", not first_send, str(first_send[:3]))
    check("distribution good run: the CLI's deprecation notice was never counted as a message (0 not JSON on the topic metric)", any(c["name"].startswith("METRIC messages on %s" % topic) and "0 not JSON" in c["detail"]
          for c in sc.rep.checks), str([c["detail"] for c in sc.rep.checks if c["name"].startswith("METRIC messages on")]))
    check("distribution good run: the unmerge is measured as a METRIC - RETIRE of the golden (reason UNMERGE), the members back, never judged",
          any(c["status"] == "INFO" and c["name"].startswith("METRIC unmerge notifications: RETIRE of the deleted golden = 1 (reason UNMERGE); UPSERT for the members back = %d of %d" % (len(um["members"]), len(um["members"]))) for c in sc.rep.checks)
          and um["retireOfGolden"] == 1 and um["retireReason"] == "UNMERGE" and um["membersUpserted"] == um["members"] and um["route"].startswith("/mergingProcesses/B%s_Master/Master/root/Client/" % sc.code),
          str(um) + str([c["name"] for c in sc.rep.checks if "unmerge" in c["name"]]))
    check("distribution good run: the true pairs the run did not merge are listed as a METRIC (INFO)", any(c["status"] == "INFO" and c["name"].startswith("METRIC true pairs the run did not merge") for c in sc.rep.checks), str([c["name"] for c in sc.rep.checks if "true pairs" in c["name"]]))
    check("distribution good run: the member table's figure is an INFO, one message per row when every row is GOLDEN", any(c["status"] == "INFO" and c["name"].startswith("METRIC messages on %s.achat" % sc.code.lower()) and "one message per row" in c["detail"] for c in sc.rep.checks),
          str([(c["name"], c["detail"]) for c in sc.rep.checks if "achat" in c["name"] and "METRIC" in c["name"]]))
    check("distribution good run: the summary knows the unmerge changed the figures", any(c["name"].startswith("summary, matching card") and c["status"] == "INFO" and "unmerged" in c["detail"] for c in sc.rep.checks), str([c["detail"] for c in sc.rep.checks if "matching card" in c["name"]]))
    check("distribution good run: the merged members exist (the filter is really exercised)", sc.details["distribution"]["golden"]["merged"] > 0 and sc.details["distribution"]["golden"]["autoCreated"] > 0, str(sc.details["distribution"]["golden"]))
    check("distribution good run: one real-time message with the new value, the record and field in the report", sc.details["distribution"]["realtime"]["newMessages"] == 1 and sc.details["distribution"]["realtime"]["field"], str(sc.details["distribution"].get("realtime")))
    check("distribution good run: no suspect is left, so the merge test is a SKIP that says so", any(c["status"] == "SKIP" and "merge two suspects" in c["name"] and "no suspect pair" in c["detail"] for c in sc.rep.checks), str([(c["status"], c["name"]) for c in sc.rep.checks if "merge" in c["name"]]))
    check("distribution good run: the target ends DISABLED (disarmed), the connection and the target are left with the project and the topics are LEFT BEHIND",
          factory.table("DistributionTarget")[sc.dist_target]["state"] == "DISABLED" and "left on the broker" in text and "Connectors connection Kafka%s" % sc.code in text and sc.code.lower() in text.split("LEFT BEHIND")[1], text[-2200:])
    check("distribution good run: the wizard's steps 7 and 8 are among the addresses printed", "wizard, step 7 (distribution)" in text and "wizard, step 8 (summary)" in text, text[-900:])
    check("distribution good run: the secret is never printed", "not-a-real-secret" not in text)
    check("distribution good run: the connection was written with the properties of the existing test connections (topic prefix = project code)", factory.table("Connector")["Kafka" + sc.code]["type"] == "kafka"
          and factory.table("Connector")["Kafka" + sc.code]["baseUrl"] == "localhost:9082" and factory.connector_topic("Kafka" + sc.code) == sc.code.lower(), str(factory.table("ConnectorProperty")))
    # --- suspects left: the merge test runs, and the retire notices are MEASURED (since lot S2: golden yes, retire yes)
    code, text, sc, factory = master_run(selftest, ("mm_suspectonly",), {"distribute": True})
    passed = [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]
    check("merge test: exit 0, the new golden record's message arrives and equals its master row", code == 0 and any("merge two suspects: the new golden record is sent" in n for n in passed), "\n".join(failed(sc)) + text[-2500:])
    metric = [c for c in sc.rep.checks if c["name"].startswith("METRIC merge notifications")]
    check("merge test: the retire notices are an INFO METRIC line - golden yes, retire notices 2 of 2 (lot S2), never judged", len(metric) == 1 and metric[0]["status"] == "INFO"
          and "golden message = yes" in metric[0]["name"] and "retire notices for the 2 merged members = 2 of 2" in metric[0]["name"], str(metric))
    check("merge test: the report carries the pair, the golden and the retire notices", len(sc.details["distribution"]["merge"]["retireNotices"]) == 2 and sc.details["distribution"]["merge"]["goldenMessages"] == 1, str(sc.details["distribution"].get("merge")))
    check("merge test then unmerge: the golden the merge made is the one unmerged, its RETIRE and the members' records follow", code == 0 and sc.details["distribution"]["unmerge"]["retireOfGolden"] == 1
          and sc.details["distribution"]["unmerge"]["golden"] == sc.details["distribution"]["merge"]["golden"], str(sc.details["distribution"].get("unmerge")))
    check("merge test: the summary knows the merge changed the figures (not compared with step 8)", any(c["name"].startswith("summary, matching card") and c["status"] == "INFO" for c in sc.rep.checks), str([c["name"] for c in sc.rep.checks if "matching card" in c["name"]]))
    code, text, sc, factory = master_run(selftest, ("mm_suspectonly", "ds_noretire"), {"distribute": True})
    metric = [c for c in sc.rep.checks if c["name"].startswith("METRIC merge notifications")]
    check("merge test with no retire notice (the gap measured 2026-09-27, before lot S2): 0 of 2 is counted and the run is still exit 0", code == 0 and len(metric) == 1 and "retire notices for the 2 merged members = 0 of 2" in metric[0]["name"], str(metric) + text[-1500:])
    code, text, sc, factory = master_run(selftest, ("mm_suspectonly", "ds_nogoldenmsg"), {"distribute": True})
    check("merge test: a golden record that is never sent is the one FAIL of this part", code == 1 and any("merge two suspects: the new golden record is sent" in n for n in failed(sc)) and len(failed(sc)) == 1, str(failed(sc)))
    code, text, sc, factory = master_run(selftest, ("mm_suspectonly", "ds_acceptnomerge"), {"distribute": True})
    check("merge test: a suspect accepted without a merge is INFO + SKIP, exit 0", code == 0 and any("were not merged" in c["name"] and c["status"] == "INFO" for c in sc.rep.checks)
          and any(c["status"] == "SKIP" and "no merge happened" in c["detail"] for c in sc.rep.checks), text[-1800:])
    code, text, sc, factory = master_run(selftest, ("mm_suspectonly", "ds_acceptnoB"), {"distribute": True})
    check("merge test: a dataspace the add-on wants without its B prefix is retried once and merges", code == 0 and any("merge two suspects: the new golden record is sent" in n for n in [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]), "\n".join(failed(sc)) + text[-1500:])
    code, text, sc, factory = master_run(selftest, ("mm_suspectonly", "ds_acceptrefused"), {"distribute": True})
    check("merge test: a refusal of the add-on is INFO + SKIP (the request shape was never seen live), exit 0", code == 0 and any("acceptSuspects refused" in c["name"] for c in sc.rep.checks)
          and any(c["status"] == "SKIP" and "refused POST manageGroup/acceptSuspects" in c["detail"] for c in sc.rep.checks), text[-1500:])
    check("merge test refused: the unmerge test has no golden to unmerge, a SKIP that says so, exit 0", any(c["status"] == "SKIP" and c["name"].startswith("unmerge a golden record") and "nothing to unmerge" in c["detail"] for c in sc.rep.checks), text[-1500:])
    # --- lot S4: the retire path (unmerge), the live-only guard, the member table, an older build
    def unmerge_checks(code_, sc_, text_):
        return [c for c in sc_.rep.checks if c["name"].startswith("unmerge a golden record")]
    code, text, sc, factory = master_run(selftest, ("ds_unmergerefused",), {"distribute": True})
    u = unmerge_checks(code, sc, text)
    check("unmerge refused by the add-on: INFO with every attempt + SKIP naming the refusal, exit 0", code == 0 and len(u) == 1 and u[0]["status"] == "SKIP" and "refused DELETE mergingProcesses" in u[0]["detail"]
          and any(c["status"] == "INFO" and "unmerge refused" in c["name"] and c["detail"].count("HTTP 500") == 3 for c in sc.rep.checks), str(u) + text[-1200:])
    code, text, sc, factory = master_run(selftest, ("ds_unmergenoop",), {"distribute": True})
    u = unmerge_checks(code, sc, text)
    check("unmerge answered 200 but nothing happened: INFO + SKIP, exit 0", code == 0 and len(u) == 1 and u[0]["status"] == "SKIP" and "no unmerge happened" in u[0]["detail"]
          and any(c["status"] == "INFO" and "is not DELETED" in c["name"] for c in sc.rep.checks), str(u) + text[-1200:])
    code, text, sc, factory = master_run(selftest, ("ds_unmergesilent",), {"distribute": True})
    u = unmerge_checks(code, sc, text)
    check("unmerge that sends NOTHING: the one FAIL of this part (exit 1)", code == 1 and len(u) == 1 and u[0]["status"] == "FAIL" and "nothing arrived" in u[0]["detail"] and failed(sc) == [u[0]["name"]], str(failed(sc)) + text[-1200:])
    code, text, sc, factory = master_run(selftest, ("ds_unmergenoretire",), {"distribute": True})
    um = sc.details["distribution"]["unmerge"]
    check("unmerge with the members re-sent but no retire notice of the golden: a measured METRIC (RETIRE 0), no FAIL, exit 0", code == 0 and um["retireOfGolden"] == 0 and len(um["membersUpserted"]) == len(um["members"])
          and any(c["name"].startswith("METRIC unmerge notifications: RETIRE of the deleted golden = 0") for c in sc.rep.checks), str(um) + text[-1000:])
    code, text, sc, factory = master_run(selftest, ("mm_unmergepending",), {"distribute": True})
    um = sc.details["distribution"]["unmerge"]
    check("unmerge with the members back PENDING_APPROVAL: the golden's RETIRE arrives, no record is expected for the members (0 of 0), exit 0", code == 0 and um["retireOfGolden"] == 1 and um["membersUpserted"] == []
          and all(v == "PENDING_APPROVAL" for v in um["membersAfter"].values()) and any("UPSERT for the members back = 0 of 0" in c["name"] for c in sc.rep.checks), str(um) + text[-1000:])
    code, text, sc, factory = master_run(selftest, ("ds_unmergebare",), {"distribute": True})
    um = sc.details["distribution"]["unmerge"]
    check("unmerge: a dataspace the add-on wants without its B prefix is retried and unmerges, exit 0", code == 0 and um["route"].startswith("/mergingProcesses/%s_Master/" % sc.code) and um["retireOfGolden"] == 1, str(um) + text[-1000:])
    code, text, sc, factory = master_run(selftest, ("ds_unmergedouble",), {"distribute": True})
    um = sc.details["distribution"]["unmerge"]
    check("unmerge: the double-slash table path is the last attempt and unmerges, exit 0", code == 0 and "//root/Client/" in um["route"] and um["retireOfGolden"] == 1, str(um) + text[-1000:])
    code, text, sc, factory = master_run(selftest, ("ds_livemerged",), {"distribute": True})
    check("live-only guard: a record message for a MERGED member sent with the real-time write is the one FAIL (exit 1)", code == 1 and failed(sc) == ["live-only sends: after the real-time write no topic holds a record message for a MERGED or DELETED record"], str(failed(sc)))
    code, text, sc, factory = master_run(selftest, ("ds_nochange",), {"distribute": True})
    check("a build before lot S2 (no _change on any message): the retire notice is recognised by `retired: true`, the run passes, exit 0", code == 0 and sc.details["distribution"]["unmerge"]["retireOfGolden"] == 1
          and all("_change" not in json.loads(m["value"]) for m in factory.kafka.topics[sc.code.lower() + ".client"]), "\n".join(failed(sc)) + text[-1200:])
    # a member table whose rows the cascade merge duplicated: more rows than messages is EXPECTED and said so
    code, text, sc, factory = master_run(selftest, ("mm_membercopies",), {"distribute": True})
    achat = [c for c in sc.rep.checks if c["name"].startswith("METRIC messages on %s.achat" % sc.code.lower())]
    check("member table with MERGED originals and golden copies: exit 0, the INFO line says the figure is expected (the MERGED rows are not sent)", code == 0 and len(achat) == 1 and achat[0]["status"] == "INFO"
          and "expected, the MERGED rows are not sent" in achat[0]["name"] and "2 MERGED" in achat[0]["detail"] and "cascade merge" in achat[0]["detail"], str(achat) + text[-1200:])
    check("member table: the report carries the rows by state and the messages", sc.details["distribution"]["memberTables"]["Achat"]["merged"] == 2
          and sc.details["distribution"]["memberTables"]["Achat"]["messages"] == sc.details["distribution"]["memberTables"]["Achat"]["golden"], str(sc.details["distribution"].get("memberTables")))
    code, text, sc, factory = master_run(selftest, ("mm_membercopies", "ds_mergedpub"), {"distribute": True})
    check("member table: a MERGED row published is a FAIL of its own check", code == 1 and any("no message for a merged or deleted row of Achat" in n for n in failed(sc)), str(failed(sc)))
    # --- a broker that is down: SKIP with a clear line, no write, no hang
    code, text, sc, factory = master_run(selftest, ("ds_brokerdown",), {"distribute": True})
    skips = [c for c in sc.rep.checks if c["status"] == "SKIP" and ("broker" in c["name"] or "distribution (" in c["name"])]
    check("broker down: exit 0, one SKIP at the start and one at step 10, each saying why", code == 0 and len(skips) == 2 and all("no Kafka broker answers at localhost:9082" in c["detail"] for c in skips)
          and skips[0]["step"] == "0" and skips[1]["step"] == "10" and "SKIPPED, not failed" in skips[1]["detail"], str(skips) + text[-1500:])
    check("broker down: nothing was written - no connection, no target, no topic, no read of a topic", not factory.table("Connector") and not factory.table("DistributionTarget") and not factory.kafka.topics
          and factory.kafka.reads == 0, str(factory.table("Connector")))
    check("broker down: step 11 still runs and passes", code == 0 and any(n.startswith("summary, landing zone") for n in [c["name"] for c in sc.rep.checks if c["status"] == "PASS"]))
    check("broker down: the left-behind list has no Kafka line", "Kafka" not in text.split("LEFT BEHIND")[-1] if "LEFT BEHIND" in text else True, text[-900:])
    # --- the defects, each turned into the FAIL of the check that guards it
    DEFECTS = [
        ("ds_mergedpub", "no message for a merged member or an unmerged golden"),
        ("ds_livemerged", "live-only sends: after the real-time write"),
        ("ds_unmergesilent", "unmerge a golden record: the topic receives notices"),
        ("ds_missinggolden", "one message per golden record"),
        ("ds_dupmsg", "one message per golden record"),
        ("ds_wrongkey", "the key of every message is the id the message carries"),
        ("ds_wrongkey", "every key is the id of a master record of Client"),
        ("ds_onetopic", "one topic per table"),
        ("ds_onetopic", "the topics are named after the project"),
        ("ds_rtmissed", "real time: a golden record modified through Data Services reaches the topic as exactly one new message"),
        ("ds_rtdouble", "real time: a golden record modified through Data Services reaches the topic as exactly one new message"),
        ("ds_sendfails", "first full send of the golden records ended DONE"),
        ("ds_notarmed", "the target is ARMED after the first full send"),
        ("ds_notarmed", "summary, distribution card: the target is listed REALTIME and ARMED"),
        ("ds_configfails", "configure: the outbound mappings are written"),
        ("ds_notriggers", "install-triggers: the real-time triggers are installed"),
        ("ds_noconnection", "the new connection is a candidate for a target"),
        ("ds_nojournal", "summary, last full run: MASTER_LOAD has a completed run"),
    ]
    for defect, needle in DEFECTS:
        code, text, sc, factory = master_run(selftest, (defect,), {"distribute": True})
        f = failed(sc)
        check("distribution defect %s: exit 1" % defect, code == 1, text[-1200:])
        check("distribution defect %s is caught by '%s'" % (defect, needle), any(needle in n for n in f), str(f))
    # a stopped distribution (configure refused) does not stop the summary
    code, text, sc, factory = master_run(selftest, ("ds_configfails",), {"distribute": True})
    check("a distribution that stops at configure still gets its target stopped and its summary read", any("stop: the target's sends are stopped" in c["name"] for c in sc.rep.checks)
          and any(c["name"].startswith("summary, landing zone") for c in sc.rep.checks), text[-1500:])
    # a build without the distribution routes: exit 3 naming them, only when --distribute is asked for
    code, text, sc, factory = master_run(selftest, ("nodistroutes",), {"distribute": True})
    check("a build without the distribution routes: exit 3 naming the first one, with --distribute", code == 3 and "GET /projects/{x}/distribution/connections" in text and "POST /projects/{x}/distribution/targets/0/first-full-send" in text, text[-1500:])
    code, text, sc, factory = master_run(selftest, ("nodistroutes",))
    check("a build without the distribution routes is fine without --distribute (exit 0)", code == 0, text[-1500:])
    # --through
    code, text, sc, factory = master_run(selftest, (), {"through": 9, "distribute": True})
    check("--through 9: steps 10 and 11 are skipped", code == 0 and sum(1 for c in sc.rep.checks if c["status"] == "SKIP" and c["step"] in ("10", "11")) == 2 and not factory.table("Connector"), text[-800:])
    code, text, sc, factory = master_run(selftest, (), {"through": 10, "distribute": True})
    check("--through 10: the distribution runs, the summary is skipped, the target is disarmed at the end all the same", code == 0 and any(c["status"] == "SKIP" and c["step"] == "11" for c in sc.rep.checks)
          and factory.table("DistributionTarget")[sc.dist_target]["state"] == "DISABLED", text[-1200:])
    # the guard: nothing that does not name the project is ever sent
    try:
        sc.admin.request("POST", sc.rest + "/projects/SOMEOTHERPROJECT/distribution/targets/1/stop")
        check("guard refuses a stop on another project", False)
    except Exception as exc:    # noqa: BLE001
        check("guard refuses a stop on another project", type(exc).__name__ == "SafetyError")


def test_distribution_cleanup(check, selftest):
    import b2c_master
    # --cleanup after a distribution: the target and the flows go with the project, the connection by Data Services; only the topics are left on the broker
    code, text, sc, factory = master_run(selftest, (), {"distribute": True, "cleanup": True})
    check("distribution cleanup: exit 0", code == 0, "\n".join(failed(sc)) + text[-2500:])
    check("distribution cleanup: the deletion plan's distribution flows (named by mapping id) are this run's, the project is deleted", "deletion plan touches only this run's objects" not in " ".join(failed(sc))
          and any(c["name"] == "project deleted with its generated objects" and c["status"] == "PASS" for c in sc.rep.checks), str(failed(sc)))
    check("distribution cleanup: the Kafka connection and its properties are deleted, the target rows are gone, no mapping is left", not factory.table("Connector") and not factory.table("ConnectorProperty")
          and not factory.table("DistributionTarget") and not factory.dist_mappings and any("Kafka connection Kafka%s deleted" % sc.code in c["name"] and c["status"] == "PASS" for c in sc.rep.checks), text[-1800:])
    check("distribution cleanup: the project's topics are deleted from the broker (lot F4), no Kafka topic is LEFT BEHIND", "Kafka topic " not in text.split("LEFT BEHIND")[-1] and not [t for t in factory.kafka.topics if t.startswith(sc.code.lower() + ".")]
          and factory.kafka.deleted and all(t.startswith(sc.code.lower() + ".") for t in factory.kafka.deleted) and any(c["status"] == "PASS" and c["name"].startswith("Kafka topics of the run deleted") for c in sc.rep.checks), text[-900:])
    check("distribution cleanup: no target is disarmed (the project is gone)", not any("disarmed at the end" in c["name"] for c in sc.rep.checks))
    # the Factory's deletion leaves the target rows: a gap, said, and the rows deleted by Data Services
    code, text, sc, factory = master_run(selftest, ("ds_targetsleft",), {"distribute": True, "cleanup": True})
    check("distribution cleanup, target rows left by the project deletion: FACTORY GAP said, rows deleted here, exit 0", code == 0 and "FACTORY GAP" in text and "DistributionTarget row(s)" in text and not factory.table("DistributionTarget")
          and not factory.table("Connector"), text[-2200:])
    # the flows are left (the deletion did not remove them): the connection cannot go - a FAIL and a left-behind line, not a crash
    code, text, sc, factory = master_run(selftest, ("ds_flowsleft",), {"distribute": True, "cleanup": True})
    check("distribution cleanup, flows left behind: the connection is refused its deletion - FAIL and LEFT BEHIND", code == 1 and any("Kafka connection" in n and "deleted" in n for n in failed(sc))
          and "Connectors connection Kafka%s" % sc.code in text.split("LEFT BEHIND")[-1] and "Kafka%s" % sc.code in factory.table("Connector"), text[-1800:])
    # --cleanup-project in a new process after a run that left everything in place
    import b2c_standin
    server, factory = b2c_standin.serve((), False, True)
    try:
        port = server.server_address[1]
        injected = {"topic_reader": factory.kafka.reader, "broker_probe": factory.kafka.probe, "topic_lister": factory.kafka.list_topics, "topic_deleter": factory.kafka.delete_topic}
        full = dict({"through": 10, "distribute": True}, **injected)
        code, text, sc, env = selftest.run_scenario(port, full, cls=b2c_master.MasterScenario, env_extra=DIST_ENV)
        kept = sc.code
        check("cleanup-project setup: project, connection and target are in place", kept in factory.table("Project") and ("Kafka" + kept) in factory.table("Connector") and len(factory.table("DistributionTarget")) == 1 and code == 0, text[-800:])
        out = io.StringIO()
        sc2 = b2c_master.MasterScenario(env, out, dict({"cleanup": False, "through": 11, "report": None, "keep_files": False, "cleanup_project": kept}, **injected), code=kept)
        sc2.connect()
        rc = sc2.run_cleanup_only()
        check("--cleanup-project: the target, its flows and the Kafka connection of the run are deleted, and so are the project's topics (lot F4)", rc == 0 and not factory.table("Connector") and not factory.table("DistributionTarget")
              and not factory.dist_mappings and "Kafka topic " not in out.getvalue().split("LEFT BEHIND")[-1] and not [t for t in factory.kafka.topics if t.startswith(kept.lower() + ".")] and factory.kafka.deleted, out.getvalue()[-2200:])
    finally:
        server.shutdown()


def test_topics_cleanup(check, selftest):
    """Lot F4 (2026-10-19): the cleanup deletes the project's own Kafka topics - only names that start with <code lower case>. - and --keep-topics keeps them."""
    import e2e_lib
    foreign = ["ebx.companies", "e2emaster20200101000000.client", "e2emasterother", "mdm-other.person"]

    def run(defects=(), args=None, plant=True):
        import b2c_standin
        server, factory = b2c_standin.serve(defects, False, True)
        if plant:
            for t in foreign:
                factory.kafka.publish(t, "k", {"a": 1})
        try:
            injected = {"topic_reader": factory.kafka.reader, "broker_probe": factory.kafka.probe, "topic_lister": factory.kafka.list_topics, "topic_deleter": factory.kafka.delete_topic}
            code, text, sc, env = selftest.run_scenario(server.server_address[1], dict({"through": 10, "distribute": True, "cleanup": True}, **dict(injected, **(args or {}))), cls=__import__("b2c_master").MasterScenario,
                                                        env_extra=DIST_ENV)
            return code, text, sc, factory
        finally:
            server.shutdown()
    # the pure rule
    check("topic rule: only names that start with the lower-cased code and a dot, and something after it", e2e_lib.project_topics("E2EMASTER2026", ["e2emaster2026.client", "e2emaster2026.", "e2emaster2026", "e2emaster20261.x",
          "E2EMASTER2026.client", "ebx.e2emaster2026.client", "e2emaster2026.achat", None]) == ["e2emaster2026.achat", "e2emaster2026.client"])
    deleter = e2e_lib.kafka_topic_deleter({"home": "/nonexistent-kafka-home", "bootstrap": "x:1"})
    check("topic deleter: a name that could carry an option is refused before any command is built", deleter("--bootstrap-server")[0] is False and "plain topic name" in deleter("a b")[1]
          and "plain topic name" in deleter("")[1] and "not found" in deleter("e2e.client")[1])
    # a good cleanup: the project's topics go, the foreign ones stay, nothing is LEFT BEHIND
    code, text, sc, factory = run()
    mine = sc.code.lower() + "."
    check("topics cleanup: exit 0, the topics of the project are gone from the broker, the others untouched", code == 0 and not [t for t in factory.kafka.topics if t.startswith(mine)]
          and all(t in factory.kafka.topics for t in foreign) and factory.kafka.deleted and all(t.startswith(mine) for t in factory.kafka.deleted), text[-1500:] + str(factory.kafka.deleted))
    check("topics cleanup: the check names the prefix and the count, no left-behind topic line", any(c["status"] == "PASS" and c["name"] == "Kafka topics of the run deleted (only names starting with %s)" % mine for c in sc.rep.checks)
          and not any("Kafka topic" in l for l in sc.leftovers), str(sc.leftovers))
    # --keep-topics
    code, text, sc, factory = run(args={"keep_topics": True})
    check("--keep-topics: nothing is deleted, the topics are LEFT BEHIND and the option is named", code == 0 and not factory.kafka.deleted and any(t.startswith(sc.code.lower() + ".") for t in factory.kafka.topics)
          and "kept on the broker (--keep-topics)" in text.split("LEFT BEHIND")[-1], text[-1500:])
    # a broker that refuses a deletion: FAIL and LEFT BEHIND with the reason
    server_run = run
    import b2c_standin
    server, factory = b2c_standin.serve((), False, True)
    factory.kafka.refuse_delete = {"*"}
    try:
        injected = {"topic_reader": factory.kafka.reader, "broker_probe": factory.kafka.probe, "topic_lister": factory.kafka.list_topics, "topic_deleter": factory.kafka.delete_topic}
        code, text, sc, env = selftest.run_scenario(server.server_address[1], dict({"through": 10, "distribute": True, "cleanup": True}, **injected), cls=__import__("b2c_master").MasterScenario, env_extra=DIST_ENV)
        check("a deletion the broker refuses: exit 1, the check FAILs, the topics are LEFT BEHIND with the reason", code == 1 and any(c["status"] == "FAIL" and c["name"].startswith("Kafka topics of the run deleted") for c in sc.rep.checks)
              and "TopicDeletionDisabledException" in text.split("LEFT BEHIND")[-1] and sc.code.lower() + "." in text.split("LEFT BEHIND")[-1], text[-1800:])
        # a broker that cannot be listed at cleanup time: SKIP, the topics are left with the reason
    finally:
        server.shutdown()
    server, factory = b2c_standin.serve((), False, True)
    try:
        calls = {"n": 0}
        base_list = factory.kafka.list_topics

        def lister():
            calls["n"] += 1
            return ([], "kafka-topics.sh --list failed at localhost:9082: Connection refused")
        injected = {"topic_reader": factory.kafka.reader, "broker_probe": factory.kafka.probe, "topic_lister": lister, "topic_deleter": factory.kafka.delete_topic}
        code, text, sc, env = selftest.run_scenario(server.server_address[1], dict({"through": 10, "distribute": True, "cleanup": True}, **injected), cls=__import__("b2c_master").MasterScenario, env_extra=DIST_ENV)
        check("a broker that cannot be listed at cleanup: SKIP, nothing deleted, the topics LEFT BEHIND with the reason, exit 0", code == 0 and not factory.kafka.deleted
              and any(c["status"] == "SKIP" and c["name"] == "Kafka topics of the run deleted" for c in sc.rep.checks) and "Connection refused" in text.split("LEFT BEHIND")[-1], text[-1500:])
    finally:
        server.shutdown()
    # a topic name read from a target row that is not the project's own is never deleted
    server, factory = b2c_standin.serve((), False, True)
    try:
        injected = {"topic_reader": factory.kafka.reader, "broker_probe": factory.kafka.probe, "topic_lister": factory.kafka.list_topics, "topic_deleter": factory.kafka.delete_topic}
        code, text, sc, env = selftest.run_scenario(server.server_address[1], dict({"through": 10, "distribute": True}, **injected), cls=__import__("b2c_master").MasterScenario, env_extra=DIST_ENV)
        factory.kafka.publish("ebx.companies", "k", {})
        target = list(factory.table("DistributionTarget").values())[0]
        target["tableDestinations"] += ",/root/Foreign=ebx.companies"
        import io as _io
        out = _io.StringIO()
        sc2 = __import__("b2c_master").MasterScenario(env, out, dict({"cleanup": False, "through": 11, "report": None, "keep_files": False, "cleanup_project": sc.code}, **injected), code=sc.code)
        sc2.connect()
        rc = sc2.run_cleanup_only()
        check("a topic named by a target row that does not start with the project's prefix is never deleted, and is reported", rc == 0 and "ebx.companies" in factory.kafka.topics and "ebx.companies" not in factory.kafka.deleted
              and "does not start with %s" % (sc.code.lower() + ".") in out.getvalue().split("LEFT BEHIND")[-1], out.getvalue()[-1800:])
    finally:
        server.shutdown()


def test_resume(check, selftest):
    """Lot F4 (2026-10-19): --from-project CODE --from-step N continues a project an earlier run left in place, from the Factory's tables, and refuses one that is not in the state the step starts from."""
    import b2c_master
    import b2c_standin
    import contextlib

    def injected(factory):
        return {"topic_reader": factory.kafka.reader, "broker_probe": factory.kafka.probe, "topic_lister": factory.kafka.list_topics, "topic_deleter": factory.kafka.delete_topic}

    def first(server, factory, through, extra=None, cls=None):
        return selftest.run_scenario(server.server_address[1], dict({"through": through}, **dict(injected(factory), **(extra or {}))), cls=cls or b2c_master.MasterScenario, env_extra=DIST_ENV)

    def resume(server, factory, code, step, extra=None, cls=None):
        args = dict({"through": 11, "from_project": code, "from_step": step}, **dict(injected(factory), **(extra or {})))
        return selftest.run_scenario(server.server_address[1], args, cls=cls or b2c_master.MasterScenario, code=code, env_extra=DIST_ENV)

    def judged(sc):
        return [(c["step"], c["status"]) for c in sc.rep.checks if c["status"] in ("PASS", "FAIL")]

    # the reference: the whole scenario once, with the distribution
    server, factory = b2c_standin.serve((), False, True)
    try:
        code, text, whole, env = first(server, factory, 11, {"distribute": True})
        check("resume reference: the whole scenario passes", code == 0, text[-1500:])
    finally:
        server.shutdown()

    # a master project resumed at the master build (7), at the matching (8), at the distribution (10): same checks as the whole run, the earlier steps skipped, nothing created
    for step in (7, 8, 10):
        server, factory = b2c_standin.serve((), False, True)
        try:
            code, text, sc0, env = first(server, factory, {7: 6, 8: 7, 10: 9}[step])
            kept = sc0.code
            before = dict((t, len(factory.table(t))) for t in ("Project", "ProjectSource", "LandingTable", "StagingEntity", "Connector"))
            code, text, sc, env = resume(server, factory, kept, step, {"distribute": True})
            after = dict((t, len(factory.table(t))) for t in before)
            check("resume at step %d: exit 0, the project is the same one" % step, code == 0 and sc.code == kept, text[-2500:])
            check("resume at step %d: steps 1..%d are SKIPped with the reason, step 0 and R ran" % (step, step - 1),
                  all(any(c["step"] == str(n) and c["status"] == "SKIP" and "resumed from project %s at step %d" % (kept, step) in c["detail"] for c in sc.rep.checks) for n in range(1, step))
                  and not any(c["step"] in [str(n) for n in range(1, step)] and c["status"] in ("PASS", "FAIL") for c in sc.rep.checks)
                  and any(c["step"] == "R" and c["name"].startswith("project %s loaded for step %d" % (kept, step)) for c in sc.rep.checks), str(judged(sc)))
            check("resume at step %d: no project, source, landing table or entity is created (a project is continued, never started)" % step, before["Project"] == after["Project"] == 1
                  and all(before[t] == after[t] for t in ("ProjectSource", "LandingTable", "StagingEntity")), "%s -> %s" % (before, after))
            norm = lambda name, c: name.replace(c, "<CODE>").replace(c.lower(), "<code>")      # noqa: E731  (the project code is in some names)
            got = dict((norm(c["name"], kept), c["status"]) for c in sc.rep.checks if c["step"] in [str(n) for n in range(step, 12)] and c["status"] in ("PASS", "FAIL"))
            want = dict((norm(c["name"], whole.code), c["status"]) for c in whole.rep.checks if c["step"] in [str(n) for n in range(step, 12)] and c["status"] in ("PASS", "FAIL"))
            check("resume at step %d: the checks of steps %d..11 are the ones the whole run made, with the same verdicts" % (step, step), got == want,
                  "only in the whole run: %s | only in the resumed run: %s" % (sorted(set(want) - set(got))[:6], sorted(set(got) - set(want))[:6]))
            check("resume at step %d: the report says it was resumed" % step, sc.details.get("resumed") == {"project": kept, "fromStep": step}, str(sc.details.get("resumed")))
            if step in (8, 10):
                check("resume at step %d: the quality is judged on the metadata read back" % step if step == 8 else "resume at step 10: the distribution and the summary pass on a loaded project",
                      (any(c["name"].startswith("METRIC recall, merged + suspects = 1.000") and c["status"] == "PASS" for c in sc.rep.checks)) if step == 8 else
                      (any("the topics" in c["name"] or "first full send" in c["name"] for c in sc.rep.checks) and any(c["name"].startswith("summary, master zone") and c["status"] == "PASS" for c in sc.rep.checks)
                       and any(c["name"].startswith("summary, matching card") for c in sc.rep.checks)), str(judged(sc))[-1500:])
            check("resume at step %d: the project is left in place, its addresses printed" % step, "PROJECT LEFT IN PLACE for a visual pass: %s" % kept in text, text[-800:])
            check("resume at step %d: the secret is never printed" % step, "not-a-real-secret" not in text)
        finally:
            server.shutdown()

    # refusals: nothing is run, exit 2, the sentence says what is missing
    server, factory = b2c_standin.serve((), False, True)
    try:
        code, text, sc0, env = first(server, factory, 3)
        kept = sc0.code
        jobs_before = len(factory.table("FactoryJob"))
        code, text, sc, env = resume(server, factory, kept, 7)
        check("resume refused: a project that stopped at the landing cannot start at the master build (exit 2, what is missing named, nothing posted)",
              code == 2 and "RESUME REFUSED" in text and "no staging entity" in text and "Nothing was run." in text and len(factory.table("FactoryJob")) == jobs_before
              and not [c for c in sc.rep.checks if c["step"] in ("7", "8")], text[-1200:])
        code, text, sc, env = resume(server, factory, kept, 2)
        check("resume refused at step 2: it would upload the files a second time", code == 2 and "already has 3 source(s)" in text, text[-800:])
        code, text, sc, env = resume(server, factory, kept, 4)
        check("resume at step 4 after the landing: allowed (the landing tables are loaded)", code in (0, 1) and "RESUME REFUSED" not in text and any(c["step"] == "4" and c["status"] in ("PASS", "FAIL") for c in sc.rep.checks), text[-600:])
        code, text, sc, env = resume(server, factory, "E2EMASTER19990101000000", 4)
        check("resume refused: a project that does not exist", code == 2 and "no project E2EMASTER19990101000000 on this instance" in text, text[-600:])
    finally:
        server.shutdown()
    server, factory = b2c_standin.serve((), False, True)
    try:
        code, text, sc0, env = first(server, factory, 6)
        kept = sc0.code
        code, text, sc, env = resume(server, factory, kept, 9)
        check("resume refused: the matching steps are missing (no master dataset)", code == 2 and "no master dataset" in text and "step 7" in text, text[-800:])
    finally:
        server.shutdown()
    server, factory = b2c_standin.serve((), False, True)
    try:
        code, text, sc0, env = first(server, factory, 7)
        kept = sc0.code
        code, text, sc, env = resume(server, factory, kept, 9)
        check("resume refused: master built but no matching plan applied", code == 2 and "no APPLIED matching plan" in text, text[-800:])
        code, text, sc, env = resume(server, factory, kept, 8)
        check("resume at step 8 on a built master is allowed", code == 0 and "RESUME REFUSED" not in text, text[-800:])
    finally:
        server.shutdown()
    server, factory = b2c_standin.serve((), False, True)
    try:
        code, text, sc0, env = first(server, factory, 11, {"distribute": True})
        kept = sc0.code
        code, text, sc, env = resume(server, factory, kept, 10, {"distribute": True})
        check("resume refused at step 10 on a project whose step 10 ran: it would create its connection and target a second time", code == 2 and "step 10 ran on it before" in text, text[-800:])
        code, text, sc, env = resume(server, factory, kept, 11, {"distribute": True})
        check("resume refused at step 11 on a project with a distribution target: its card is judged against the run's own step 10", code == 2 and "distribution target" in text, text[-800:])
    finally:
        server.shutdown()

    # a job running on the project: refused, the state would be read halfway
    server, factory = b2c_standin.serve((), False, True)
    try:
        code, text, sc0, env = first(server, factory, 7)
        kept = sc0.code
        factory.job(kept, "MATCHING_RUN", "running", status="RUNNING")
        code, text, sc, env = resume(server, factory, kept, 8)
        check("resume refused while a job runs on the project", code == 2 and "a job is running on the project" in text, text[-800:])
    finally:
        server.shutdown()

    # the command line
    err = io.StringIO()
    with contextlib.redirect_stderr(err):
        env = {"EBX_URL": "http://127.0.0.1:1", "EBX_USER": "u", "EBX_PASSWORD": "p"}
        main = b2c_master.main
        check("cli: --from-project without --from-step is refused", main(["--from-project", "E2EMASTER20261018000000"], env, io.StringIO()) == 2 and "go together" in err.getvalue())
        check("cli: --from-step without --from-project is refused", main(["--from-step", "7"], env, io.StringIO()) == 2)
        check("cli: --from-step 1 is refused (step 1 creates the project)", main(["--from-project", "E2EMASTER20261018000000", "--from-step", "1"], env, io.StringIO()) == 2 and "starts at 2" in err.getvalue())
        check("cli: --from-step 12 is not a step", main(["--from-project", "E2EMASTER20261018000000", "--from-step", "12"], env, io.StringIO()) == 2)
        check("cli: a code that is not this scenario's is refused", main(["--from-project", "E2EB2C20261018000000", "--from-step", "7"], env, io.StringIO()) == 2 and "starts with E2EMASTER" in err.getvalue())
        check("cli: --through before --from-step is refused", main(["--from-project", "E2EMASTER20261018000000", "--from-step", "8", "--through", "7"], env, io.StringIO()) == 2 and "nothing to run" in err.getvalue())
        check("cli: --variants both cannot continue one project", main(["--from-project", "E2EMASTER20261018000000", "--from-step", "8", "--variants", "both"], env, io.StringIO()) == 2 and "one variant" in err.getvalue())
        import b2c_scenario
        check("cli: a scenario that cannot resume does not know the options", b2c_scenario.main(["--from-project", "E2EB2C20261018000000", "--from-step", "3"], env, io.StringIO()) == 2 and "unexpected argument: --from-project" in err.getvalue())
    server, factory = b2c_standin.serve((), False, True)
    try:
        code, text, sc0, env0 = first(server, factory, 7)
        kept = sc0.code
        env = {"EBX_URL": "http://127.0.0.1:%d" % server.server_address[1], "EBX_USER": "selftest", "EBX_PASSWORD": "not-a-real-secret", "E2E_POLL": "0.01", "E2E_OUT_DIR": tempfile.mkdtemp(prefix="b2c-master-rep-"),
               "E2E_TIMEOUT_REALTIME": "1", "E2E_DIST_SETTLE": "0", "E2E_KAFKA_BOOTSTRAP": "127.0.0.1:1", "E2E_KAFKA_HOME": "/nonexistent-kafka-home"}
        out = io.StringIO()
        rc = b2c_master.main(["--from-project", kept, "--from-step", "8", "--through", "9"], env, out)
        text = out.getvalue()
        reports = [f for f in os.listdir(env["E2E_OUT_DIR"]) if f.endswith(".json")]
        check("cli: main() --from-project --from-step runs ONE variant (plain) on that project, no second project", rc == 0 and "(resumed at step 8)" in text and "variant: simulate-first" not in text
              and len(factory.table("Project")) == 1 and len(reports) == 1 and "simulate-first" not in reports[0] and kept in reports[0] and "not-a-real-secret" not in text, text[-1500:])
    finally:
        server.shutdown()
    out = io.StringIO()
    b2c_master.dry_run(out)
    check("master dry-run names the resume options", "--from-project" in out.getvalue() and "--from-step" in out.getvalue(), out.getvalue()[:600])


def test_candidate_gap_audit(check):
    """Lot F4: tools/matching/candidate_gap_audit.py read a foreign key (RecordMetadata.goldenId) as a dict and compared "{'content': ...}" with a key."""
    import importlib.util
    path = os.path.join(os.path.dirname(HERE), "matching", "candidate_gap_audit.py")
    spec = importlib.util.spec_from_file_location("candidate_gap_audit", path)
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    fk = lambda v: {"content": v, "label": "label %s" % v, "details": "http://x/%s" % v, "selector": "http://x/s"}   # noqa: E731  (the shape of a FOREIGN KEY over Data Services)
    check("audit: a foreign key is unwrapped to its key, a plain value and None are not changed into something else", audit.text(fk("536")) == "536" and audit.text("7") == "7" and audit.text(7) == "7"
          and audit.text(None) == "" and audit.text({"content": {"content": "9"}}) == "9" and audit.text({"key": "5"}) == "5")
    rows = [{"id": "1", "sourceSystem": "ECOM", "sourceId": "e1"}, {"id": "2", "sourceSystem": "STORES", "sourceId": "s1"}, {"id": "10", "sourceSystem": "MDM", "sourceId": "g"},
            {"id": "3", "sourceSystem": "ECOM", "sourceId": "e2"}, {"id": "4", "sourceSystem": "ECOM", "sourceId": "unknown"}]
    meta = [{"functionalId": "1", "state": 2, "goldenId": fk("10")}, {"functionalId": "2", "state": 2, "goldenId": fk("10")}, {"functionalId": "10", "state": 1, "autoCreated": True, "goldenId": None},
            {"functionalId": "3", "state": 1, "goldenId": None}, {"functionalId": "4", "state": 1, "goldenId": None}]
    truth = {("ECOM", "e1"): 1, ("STORES", "s1"): 1, ("ECOM", "e2"): 2}
    recs = audit.records_of(rows, meta, truth)
    check("audit: two records merged into the golden 10 (goldenId a foreign key) share one golden, a record left alone is its own", set(recs) == {"1", "2", "3"} and recs["1"]["golden"] == "10" and recs["2"]["golden"] == "10"
          and recs["3"]["golden"] == "3", str(recs))
    check("audit: the true pair of the person is found together (goldenId compared as its key, not as the text of a dict), nothing is missed", audit.pairs_of(recs) == ([("1", "2")], []), str(audit.pairs_of(recs)))
    unmerged = audit.records_of(rows, [dict(m, state=1) if m["functionalId"] == "2" else m for m in meta], truth)
    check("audit: a record that is not MERGED is its own cluster, so the pair is missed", audit.pairs_of(unmerged) == ([("1", "2")], [("1", "2")]), str(audit.pairs_of(unmerged)))
    meta_plain = [dict(m, goldenId=(m["goldenId"]["content"] if isinstance(m["goldenId"], dict) else m["goldenId"])) for m in meta]
    check("audit: a plain goldenId gives the same clusters", dict((k, v["golden"]) for k, v in audit.records_of(rows, meta_plain, truth).items()) == dict((k, v["golden"]) for k, v in recs.items()))


def test_distribution_cli(check, selftest):
    import b2c_master
    out = io.StringIO()
    b2c_master.dry_run(out)
    text = out.getvalue()
    check("master dry-run names the distribution step: its routes, the add-on's acceptSuspects, the Kafka CLI", "--distribute" in text and "/distribution/targets/0/first-full-send" in text
          and b2c_master.D.ADDON_ACCEPT in text and "kafka-console-consumer.sh" in text, text[-1500:])
    check("master dry-run lists steps 10 and 11", "10. distribution (wizard step 7)" in text and "11. summary (wizard step 8)" in text, text[:900])
    import contextlib
    with contextlib.redirect_stderr(io.StringIO()):
        check("master main(): --through 12 is refused", b2c_master.main(["--through", "12"], {"EBX_URL": "http://127.0.0.1:1", "EBX_USER": "u", "EBX_PASSWORD": "p"}, io.StringIO()) == 2)
    # main() parses --distribute and writes the report with the distribution details
    import b2c_standin
    server, factory = b2c_standin.serve((), False, True)
    try:
        env = {"EBX_URL": "http://127.0.0.1:%d" % server.server_address[1], "EBX_USER": "selftest", "EBX_PASSWORD": "not-a-real-secret", "E2E_POLL": "0.01", "E2E_OUT_DIR": tempfile.mkdtemp(prefix="b2c-master-rep-"),
               "E2E_TIMEOUT_REALTIME": "1", "E2E_DIST_SETTLE": "0", "E2E_KAFKA_BOOTSTRAP": "127.0.0.1:1", "E2E_KAFKA_HOME": "/nonexistent-kafka-home"}
        out = io.StringIO()
        rc = b2c_master.main(["--distribute", "--variants", "plain"], env, out)
        text = out.getvalue()
        # the real probe is used here (no injection): nothing listens on 127.0.0.1:1, so the distribution is skipped with the address in the sentence
        check("master main() --distribute with no broker (the real probe): exit 0, step 10 skipped naming the address, nothing written", rc == 0 and "no Kafka broker answers at 127.0.0.1:1" in text
              and not factory.table("Connector") and not factory.table("DistributionTarget"), text[-1800:])
        reports = [f for f in os.listdir(env["E2E_OUT_DIR"]) if f.endswith(".json")]
        doc = json.load(open(os.path.join(env["E2E_OUT_DIR"], reports[0]))) if len(reports) == 1 else {}
        check("master main() --distribute: the report carries the skipped distribution", "skipped" in doc.get("details", {}).get("distribution", {}) and "not-a-real-secret" not in json.dumps(doc), str(doc.get("details", {}).get("distribution")))
    finally:
        server.shutdown()


def run_all(check, selftest):
    test_truth(check)
    test_measures(check)
    test_false_pair_logic(check)
    test_master_flows(check, selftest)
    test_master_cli(check, selftest)
    test_distribution_logic(check)
    test_distribution_flows(check, selftest)
    test_distribution_cleanup(check, selftest)
    test_topics_cleanup(check, selftest)
    test_candidate_gap_audit(check)
    test_resume(check, selftest)
    test_distribution_cli(check, selftest)
