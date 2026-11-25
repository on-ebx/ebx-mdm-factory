#!/usr/bin/env python3
"""Offline self-test of the integration suite (audit F-05): pure units, then the whole suite against standin.py on 127.0.0.1.

Run by tools/test_local.sh and by `tools/itest.sh --selftest`. No EBX instance, no network beyond the loopback stand-in.
"""
import io
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import client  # noqa: E402
import itest  # noqa: E402
import scenarios  # noqa: E402
import standin  # noqa: E402

JAVA = os.path.join(HERE, "..", "..", "ebx-mdm-factory", "src", "main", "java", "com", "on", "ebx", "factory")
ENV = {"EBX_AUTH": "admin:adminpw", "EBX_AUTH_NOROLE": "norole:norolepw", "ITEST_POLL_INTERVAL": "0.1", "ITEST_JOB_TIMEOUT": "8", "ITEST_SETTLE_TIMEOUT": "2"}


class FakeResp(object):
    def __init__(self, status, text):
        self.status = status
        self.text = text
        import json
        try:
            self._j = json.loads(text)
        except ValueError:
            self._j = None

    def json(self):
        return self._j

    def error_text(self):
        return client.clip(self.text)


class PureTests(unittest.TestCase):
    def test_unwrap_and_rows(self):
        self.assertEqual(client.unwrap({"content": {"content": 3}}), 3)
        self.assertEqual(client.row_fields({"content": {"id": {"content": "7"}, "x": {"content": None}}, "details": "u"}), {"id": "7", "x": None})
        self.assertEqual(client.row_fields({"id": {"content": 1}}), {"id": 1})
        self.assertEqual(client.wrap_fields({"a": 1}), {"content": {"a": {"content": 1}}})

    def test_auth_and_hosts(self):
        self.assertEqual(client.parse_auth("u:p:w"), ("u", "p:w"))
        self.assertIsNone(client.parse_auth("nocolon"))
        self.assertIsNone(client.parse_auth(":pw"))
        for host in ("localhost", "127.0.0.1", "::1", "x.localhost", "a.test"):
            self.assertTrue(client.is_local_host(host), host)
        self.assertFalse(client.is_local_host("ebx.example.com"))

    def test_guard(self):
        g = client.Guard("ITEST20260101000000")
        g.check("GET", "/anything", None)
        g.check("POST", "/x/projects/ITEST20260101000000P/delete?confirm=1", None)
        g.check("POST", "/x/root/ProjectSource", '{"project":"ITEST20260101000000P"}')
        for method, path in (("POST", "/x/projects/RealProject/delete"), ("DELETE", "/x/root/FactoryJob/5"), ("PUT", "/x/root/Project/Other")):
            with self.assertRaises(client.SafetyError):
                g.check(method, path, "{}")
        g.allow_path("/x/root/FactoryJob/5")
        g.check("DELETE", "/x/root/FactoryJob/5", None)
        g.allow_path_prefix("/x/sources/9/")
        g.check("POST", "/x/sources/9/landing/plan", None)

    def test_guard_stops_before_sending(self):
        h = client.Http("http://127.0.0.1:9", ("a", "b"), "t", guard=client.Guard("ITEST1"))
        with self.assertRaises(client.SafetyError):
            h.post("/projects/RealProject/delete")

    def test_gate_classification(self):
        mod = '{"error":"Access denied: POST /x requires the MDM Factory right DESIGN. An EBX administrator gives it"}'
        adm = '{"error":"Access denied: GET /x is for EBX administrators only."}'
        self.assertEqual(scenarios.classify_gate(FakeResp(403, mod), "DESIGN")[0], "PASS")
        self.assertEqual(scenarios.classify_gate(FakeResp(403, mod), "VIEW")[0], "FAIL")
        self.assertEqual(scenarios.classify_gate(FakeResp(403, adm), "ADMIN")[0], "PASS")
        self.assertEqual(scenarios.classify_gate(FakeResp(403, adm), "DESIGN")[0], "FAIL")
        self.assertEqual(scenarios.classify_gate(FakeResp(403, '{"message":"not allowed to read dataspace X"}'), "VIEW")[0], "SKIP")
        self.assertEqual(scenarios.classify_gate(FakeResp(403, "<html>"), "VIEW")[0], "SKIP")
        self.assertEqual(scenarios.classify_gate(FakeResp(401, ""), "VIEW")[0], "FAIL")
        self.assertEqual(scenarios.classify_gate(FakeResp(200, "{}"), "VIEW")[0], "FAIL")
        self.assertEqual(scenarios.classify_gate(FakeResp(0, "refused"), "VIEW")[0], "FAIL")
        self.assertTrue(scenarios.gate_open(FakeResp(404, "{}")))
        self.assertFalse(scenarios.gate_open(FakeResp(403, "{}")))

    def test_plan_problem(self):
        code = "ITEST20260101000000P"
        ok = {"project": code, "delivered": False, "items": [{"kind": "LANDING_DATASPACE", "name": code + "_Landing"}]}
        self.assertIsNone(scenarios._plan_problem(ok, code))
        self.assertIsNone(scenarios._plan_problem({"project": code, "items": []}, code))
        self.assertIsNotNone(scenarios._plan_problem({"project": "Other", "items": []}, code))
        self.assertIsNotNone(scenarios._plan_problem({"project": code, "items": [{"kind": "MASTER_DATASPACE", "name": "Customers_Master"}]}, code))
        self.assertIsNotNone(scenarios._plan_problem({"project": code, "delivered": True, "items": []}, code))
        self.assertIsNotNone(scenarios._plan_problem(None, code))

    def test_prefix(self):
        self.assertEqual(itest.make_prefix(0), "ITEST19700101000000")
        self.assertTrue(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", itest.make_prefix() + "P"))
        self.assertLessEqual(len(itest.make_prefix() + "P"), 40)   # Project.code maxLength

    def test_arguments(self):
        out = io.StringIO()
        self.assertEqual(itest.run([], {}, out), 2)
        self.assertEqual(itest.run(["http://127.0.0.1:1"], {}, out), 2)                                             # no EBX_AUTH
        self.assertEqual(itest.run(["http://127.0.0.1:1"], {"EBX_AUTH": "a:b"}, out), 2)                            # no EBX_AUTH_NOROLE
        self.assertEqual(itest.run(["http://ebx.example.invalid"], {"EBX_AUTH": "a:b", "EBX_AUTH_NOROLE": "c:d"}, out), 2)   # clear credentials, remote host
        self.assertEqual(itest.run(["ftp://x"], {"EBX_AUTH": "a:b", "EBX_AUTH_NOROLE": "c:d"}, out), 2)
        self.assertEqual(itest.run(["http://127.0.0.1:1", "--only", "9"], {"EBX_AUTH": "a:b", "EBX_AUTH_NOROLE": "c:d"}, out), 2)
        self.assertEqual(itest.run(["http://127.0.0.1:1", "--only", "5"], {"EBX_AUTH": "a:b", "EBX_AUTH_NOROLE": "c:d"}, out), 2)   # heavy scenario without ITEST_FULL
        self.assertNotIn("a:b", out.getvalue())

    @unittest.skipUnless(os.path.exists(os.path.join(JAVA, "core", "FactoryRights.java")), "module sources not present")
    def test_rights_table_matches_the_module(self):
        rights = {}
        with open(os.path.join(JAVA, "core", "FactoryRights.java"), encoding="utf-8") as handle:
            text = handle.read()
        for m in re.finditer(r"FactoryRights\.route\(FactoryRight\.(\w+),(.*?)\);", text, re.S):
            for key in re.findall(r'"([^"]+)"', m.group(2)):
                rights[key] = {"ADMINISTRATOR": "ADMIN"}.get(m.group(1), m.group(1))
        self.assertGreater(len(rights), 50)
        with open(os.path.join(JAVA, "rest", "FactoryResource.java"), encoding="utf-8") as handle:
            resource = handle.read()
        paths = set(re.findall(r'@Path\("([^"]+)"\)', resource))

        def expected(method, template):
            path = template.split("?")[0].replace("{x}", "x").replace("{job}", "7")
            hits = []
            for key, right in rights.items():
                km, kp = key.split(" ", 1)
                regex = re.sub(r"\\\{[^}]*\\\}", "[^/]+", re.escape(kp))
                if km == method and re.fullmatch(regex, path.replace("/x", "/{code}", 1) if "/projects/" in path else path) or (km == method and re.fullmatch(regex, path)):
                    hits.append((key, right))
            return hits

        wanted = [(m, t, r) for _, m, t, r in scenarios.FAMILIES]
        wanted += [(scenarios.VIEW_ROUTE[0], scenarios.VIEW_ROUTE[1], "VIEW"), (scenarios.STEWARD_ROUTE[0], scenarios.STEWARD_ROUTE[1], "STEWARD"),
                   (scenarios.DESIGN_ROUTE[0], scenarios.DESIGN_ROUTE[1], "DESIGN")]
        wanted += [(m, t, "ADMIN") for m, t in scenarios.ADMIN_ROUTES]
        for method, template, right in wanted:
            hits = expected(method, template)
            self.assertTrue(hits, "no route of FactoryRights.java matches %s %s" % (method, template))
            for key, got in hits:
                self.assertEqual(got, right, "%s %s: the suite expects %s, FactoryRights.java says %s for %s" % (method, template, right, got, key))
                self.assertIn(key.split(" ", 1)[1], paths, "no @Path for " + key)
        # the refresh job used by scenario 4 is a DESIGN route that exists
        self.assertEqual(rights.get("POST /projects/{code}/matching/refresh-golden-keys"), "DESIGN")
        self.assertEqual(rights.get("POST /projects/{code}/delete"), "ADMIN")
        self.assertEqual(rights.get("GET /projects/{code}/deletion/plan"), "ADMIN")


def run_suite(faults=(), extra=None, argv=()):
    server = standin.Standin(faults)
    base = server.start()
    try:
        env = dict(ENV)
        env.update(extra or {})
        out = io.StringIO()
        code = itest.run([base] + list(argv), env, out)
        text = out.getvalue()
        m = re.search(r"run (ITEST\d+)", text)
        return code, text, server, (m.group(1) if m else "ITEST")
    finally:
        server.stop()


class StandinRuns(unittest.TestCase):
    def lines(self, text, status):
        return [l for l in text.splitlines() if l.startswith(status + " ") or l.startswith(status.ljust(5))]

    def test_happy_path(self):
        code, text, server, prefix = run_suite()
        self.assertEqual(code, 0, text)
        self.assertEqual(self.lines(text, "FAIL"), [], text)
        for needle in ("scratch project created", "harmless job queued", "deletion job ended", "temporary Everyone row removed", "generated dataspaces",
                       "VIEW applies", "STEWARD applies", "DESIGN applies", "403 deletion plan", "403 matching run"):
            self.assertIn(needle, text)
        self.assertEqual(server.leftovers(prefix), [], "the run left rows behind")
        self.assertNotIn("adminpw", text)
        self.assertNotIn("norolepw", text)
        # nothing outside the scratch objects was written: every mutation of the stand-in names the prefix or is a job row of it
        for method, path, who in server.log:
            if method in ("POST", "PUT", "DELETE") and "/rest/v1/" in path and "/jobs/0/cancel" not in path:
                self.assertIn(prefix, path)

    def test_only_selects(self):
        code, text, server, prefix = run_suite(argv=["--only", "1,2"])
        self.assertEqual(code, 0, text)
        self.assertNotIn("scratch project created", text)
        self.assertEqual([m for m, p, u in server.log if m != "GET" and "/ebx-dataservices/" in p], [])

    def test_open_gate_is_a_failure(self):
        code, text, server, prefix = run_suite(["open_design"])
        self.assertEqual(code, 1, text)
        self.assertTrue(any("design action" in l for l in self.lines(text, "FAIL")), text)

    def test_ebx_own_refusal_is_skipped(self):
        code, text, server, prefix = run_suite(["ebx_refuses"])
        self.assertEqual(code, 0, text)
        self.assertGreaterEqual(len(self.lines(text, "SKIP")), len(scenarios.FAMILIES))
        self.assertIn("EBX's own dataspace/dataset permission refusal", text)
        self.assertIn("rights by level", text)   # scenario 3 skipped, not run
        code2, text2, _, _ = run_suite(["ebx_refuses"], {"ITEST_STRICT": "1"})
        self.assertEqual(code2, 1, text2)

    def test_data_services_refusal_skips_the_lifecycle(self):
        code, text, server, prefix = run_suite(["ds_refuses"])
        self.assertEqual(code, 0, text)
        self.assertIn("scratch project created", text)
        self.assertTrue(any("scratch project created" in l for l in self.lines(text, "SKIP")), text)
        self.assertEqual(server.leftovers(prefix), [])

    def test_rows_left_by_the_module_are_reported(self):
        code, text, server, prefix = run_suite(["leave_rows"])
        self.assertEqual(code, 1, text)
        self.assertTrue(any("PipelineStep rows" in l for l in self.lines(text, "FAIL")), text)
        self.assertIn("LEFT BEHIND", text)

    def test_foreign_plan_is_never_executed(self):
        code, text, server, prefix = run_suite(["foreign_plan"])
        self.assertEqual(code, 1, text)
        self.assertTrue(any("deletion plan touches only the scratch project" in l for l in self.lines(text, "FAIL")), text)
        self.assertFalse(any(m == "POST" and p.endswith("/delete") and u == "admin" for m, p, u in server.log), "the deletion was sent although the plan was foreign")

    def test_everyone_row_that_cannot_be_removed_is_loud(self):
        code, text, server, prefix = run_suite(["row_stays"])
        self.assertEqual(code, 1, text)
        self.assertTrue(any("temporary Everyone row removed" in l for l in self.lines(text, "FAIL")), text)
        self.assertIn("LEFT BEHIND", text)

    def test_stuck_job_engine(self):
        code, text, server, prefix = run_suite(["stuck_jobs"], {"ITEST_JOB_TIMEOUT": "1"})
        self.assertEqual(code, 1, text)
        self.assertTrue(any("job ran to its end" in l for l in self.lines(text, "FAIL")), text)

    def test_interruption_still_cleans_up(self):
        real = scenarios.wait_job
        calls = []

        def interrupted(ctx, job_id, timeout):
            calls.append(job_id)
            if len(calls) == 1:
                raise KeyboardInterrupt()   # Ctrl-C while the first job of the scratch project is awaited
            return real(ctx, job_id, timeout)
        scenarios.wait_job = interrupted
        try:
            code, text, server, prefix = run_suite()
        finally:
            scenarios.wait_job = real
        self.assertEqual(code, 1, text)
        self.assertIn("run interrupted", text)
        self.assertEqual(server.leftovers(prefix), [], "an interrupted run left rows behind:\n" + text)
        self.assertIn("deletion job ended", text)

    def test_full_scenario_logic_against_stubs(self):
        code, text, server, prefix = run_suite(extra={"ITEST_FULL": "1"}, argv=["--only", "5"])
        self.assertEqual(code, 0, text)
        for needle in ("end-to-end project created", "sample files imported", "landing load of ITA", "matching run", "no failed pipeline step",
                       "end-to-end plan touches only scratch objects", "end-to-end deletion ended"):
            self.assertIn(needle, text)
        self.assertIn("LEFT BEHIND", text)          # the Connectors connections the project deletion does not remove
        self.assertEqual(server.leftovers(prefix), [], text)

    def test_administrator_only_run(self):
        code, text, server, prefix = run_suite(extra={"ITEST_SKIP_NOROLE": "1", "EBX_AUTH_NOROLE": ""})
        self.assertEqual(code, 0, text)
        self.assertIn("EBX_AUTH_NOROLE is not set", text)
        self.assertIn("scratch project created", text)


def main():
    suite = unittest.TestSuite()
    loader = unittest.defaultTestLoader
    suite.addTests(loader.loadTestsFromTestCase(PureTests))
    suite.addTests(loader.loadTestsFromTestCase(StandinRuns))
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
