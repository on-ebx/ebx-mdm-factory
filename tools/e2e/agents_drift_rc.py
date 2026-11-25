#!/usr/bin/env python3
"""RC test of the agent framework (lot A, docs/AGENTS.md) and of source drift (lot B, docs/DRIFT.md), driven through REST and Data Services.

    EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/agents_drift_rc.sh [options]
    tools/e2e/agents_drift_rc.sh --dry-run          # prints the plan, sends nothing

Options:
    --only agents|drift      run one scenario (default: both, agents first)
    --project CODE           reuse a scratch project an earlier B2C run left (E2EB2C..., loaded through step 6); it is kept unless --cleanup
    --cleanup                with --project: delete that project at the end too
    --keep                   keep the scratch project this run created (and the agent and drift rows), to look at them
    --decide-by-route        decide the agent's proposals through POST /agents/proposals/{id}/decide instead of waiting for you to accept the task
                             in the EBX inbox (the default when the terminal is interactive)
    --decide-in-inbox        wait for the task to be approved in the EBX inbox even when stdin is not a terminal (one line on stdin resumes)
    --act                    scenario A with autonomy ACT and threshold 0: the corrections are applied by the runner, nothing is escalated
    --event                  after scenario A, scenario E: an enabled agent listening to STAGING_LOAD_DONE runs after a staging load
    --report FILE            write the JSON report there

Fixture: a scratch project built by the B2C scenario (tools/e2e/b2c_scenario.py, steps 1..6: three sample files, landing, staging proposal,
decisions, build and load), in its own E2EB2C... project with its own landing and staging dataspaces. Nothing is written to Reference or to another
project: the factory rows this run writes (AgentDefinition, AgentProposal, SourceDrift, LandingColumn) all belong to the scratch project, and the
safety guard refuses any write that does not name the project's code, apart from the few id-addressed routes it registers one by one.

Scenario A (agents): a QUALITY_FIX agent on the project's person staging table, autonomy SUGGEST, owner profile Badministrator. The run makes one
field too long (trailing blanks past its maxLength) so validation flags it, runs the agent, and checks: the job ends DONE, AgentRun records the
inputs and the decision, at least one AgentProposal is PROPOSED, and the run opened a MdmFactoryAgentEscalation task. Then the proposals are
decided (in the inbox by you, or through the REST route) and checked ACCEPTED / REJECTED with decidedBy. A suggestion is never applied.

Scenario B (drift): on one landing table of the project, the run edits the frozen copy of the scan (LandingColumn): one column renamed (so the
catalog's column comes back ADDED and the renamed one REMOVED) and one source type changed (RETYPED). A landing plan must propose exactly those
three SourceDrift rows and set the table DRIFTED. Then: RETYPED accepted and applied (the type is back), ADDED accepted and applied (the landing
model patched and republished, a new LandingColumn), REMOVED rejected; the table goes back to its status; a second plan proposes nothing new; a
second decision on an applied row is refused.

Exit: 0 every check passed, 1 at least one FAIL, 2 environment or arguments, 3 a route this build lacks.
"""
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "itest"))

import client  # noqa: E402  (tools/itest/client.py)
import e2e_lib as L  # noqa: E402
import b2c_scenario as B  # noqa: E402

OWNER_PROFILE = "Badministrator"
WORKFLOW = "MdmFactoryAgentEscalation"
REQUIRED_ROUTES = [
    ("POST", "/agents/{x}/run", None, "run an agent"),
    ("POST", "/agents/proposals/{x}/decide?accept=false", None, "decide an agent proposal"),
    ("GET", "/projects/{x}/drift", None, "list the source drifts"),
    ("POST", "/drift/{x}/decide?accept=false", None, "decide a source drift"),
    ("POST", "/drift/{x}/apply", None, "apply a source drift"),
]
AGENT_INSTRUCTIONS = ("RC test: for every finding propose CORRECT with the corrected value (a value longer than its limit because of trailing blanks "
                      "is corrected by removing them). Do not answer ABSTAIN in this test.")


class Stop(Exception):
    pass


def route_missing(status, body_json):
    """A 405, or a 404 that is not this build's own "<thing> not found" answer (an unknown agent, proposal or project): the route does not exist."""
    if status == 405:
        return True
    if status != 404:
        return False
    error = body_json.get("error") if isinstance(body_json, dict) else None
    return not (isinstance(error, str) and error.endswith(" not found"))


def clip(text, n=240):
    return B.clip(text, n)


class Run:
    def __init__(self, env, out, args):
        self.env = env
        self.out = out
        self.args = args
        self.rep = L.Report(out)
        reuse = args["project"]
        fixture_args = {"cleanup": False, "through": 6, "report": None, "keep_files": False, "cleanup_project": None, "from_project": None, "from_step": None}
        self.fx = B.Scenario(env, out, fixture_args, code=reuse)   # the B2C scenario: the fixture, its HTTP clients, its guard and its cleanup
        self.code = self.fx.code
        self.created_here = reuse is None
        self.report_data = {"project": self.code, "agents": {}, "drift": {}}
        self.agent_code = self.code + "QA"
        self.agent_codes = [self.agent_code]   # every AgentDefinition this run declares, deleted at cleanup
        self.agent_rows = {"runs": [], "proposals": []}
        self.drift_ids = []
        self.restore = []          # (Ds, table, pk, fields) put back at cleanup
        self.leftovers = []

    # ------------------------------------------------------------------------------------------------------------ plumbing
    def say(self, text):
        self.out.write("note  %s\n" % text)
        self.out.flush()

    def post(self, tail, slow=False, allow=False):
        if allow:
            self.fx.guard.allow_path(self.fx.rest + tail)
        return self.fx.post(tail, slow=slow)

    def select(self, table, xpath):
        resp, rows = self.fx.ds.select(table, xpath)
        if resp.status != 200:
            raise RuntimeError("select %s [%s]: HTTP %d %s" % (table, xpath, resp.status, clip(resp.error_text())))
        return rows

    def update(self, ds, table, pk, fields):
        self.fx.guard.allow_path(ds.record(table, pk))
        return ds.update(table, pk, fields)

    def delete(self, ds, table, pk):
        self.fx.guard.allow_path(ds.record(table, pk))
        return ds.delete(table, pk)

    def inventory(self):
        missing = []
        for method, template, body, label in REQUIRED_ROUTES:
            # a GET names a project that does not exist; a POST names id 0, which no agent, proposal or drift has: each answers 404 or 409, never acts
            x = self.code + "NOSUCH" if method == "GET" else "0"
            path = self.fx.rest + template.replace("{x}", x)
            if method != "GET":
                self.fx.guard.allow_path(path)
            r = self.fx.admin.request(method, path, body)
            if route_missing(r.status, r.json()):
                missing.append("%s %s (%s)" % (method, template, label))
        if missing:
            self.rep.failed("0", "the agent and drift routes exist on this build", "missing: " + "; ".join(missing))
            raise B.MissingRoute()
        self.rep.passed("0", "the agent and drift routes exist on this build", "%d routes" % len(REQUIRED_ROUTES))

    # ------------------------------------------------------------------------------------------------------------ fixture
    def fixture(self):
        if self.created_here:
            self.out.write("\n== fixture: B2C scenario steps 1..6 on project %s\n" % self.code)
            code = self.fx.run()
            self.fx.leftovers = []   # its "project kept" line: this run deletes the project itself at the end
            self.rep.check("F", "fixture project built and loaded (B2C steps 1..6)", code == 0, self.code, "the B2C scenario ended %d: see its table above" % code)
            if code != 0:
                raise Stop()
        else:
            self.fx.step0()
            resp, project = self.fx.ds.get_row("Project", self.code)
            ok = resp.status == 200 and project and L.text(project.get("stagingDataspace"))
            self.rep.check("F", "reused project %s exists and has a staging zone" % self.code, bool(ok), "", "HTTP %d" % resp.status)
            if not ok:
                raise Stop()
            self.fx.stg = client.Ds(self.fx.admin, L.text(project.get("stagingDataspace")), L.text(project.get("stagingDataset")))

    def person_entity(self):
        entities = self.select("StagingEntity", "./project='%s'" % self.code)
        active = [e for e in entities if L.text(e.get("status")) in ("ACCEPTED", "APPLIED")]
        person = L.person_entity(active) or (active[0] if active else None)
        if person is None:
            self.rep.failed("A", "an accepted staging entity", "none on project %s" % self.code)
            raise Stop()
        return person

    # ------------------------------------------------------------------------------------------------------------ scenario A: agents
    def agents(self):
        self.out.write("\n== scenario A: a quality agent that escalates to its review task\n")
        person = self.person_entity()
        table = self.fx.table_path(person)
        attributes = self.select("StagingAttribute", "./entity='%s'" % L.text(person.get("id")))
        keys = [L.text(a.get("name")) for a in attributes if L.truthy(a.get("primaryKey"))]
        keys.sort(key=lambda n: (0 if "system" in n.lower() else 1, n))
        candidates = [a for a in attributes if not L.truthy(a.get("primaryKey")) and L.to_int(a.get("maxLength")) > 0 and L.live(a)]
        resp, rows = self.fx.stg.select(table, None, page_size=200, max_pages=2)
        injected = None
        for a in candidates:
            name, limit = L.text(a.get("name")), L.to_int(a.get("maxLength"))
            row = next((r for r in rows if L.text(r.get(name)) and all(L.text(r.get(k)) for k in keys)), None)
            if row is None or not keys:
                continue
            pk = "|".join(L.text(row.get(k)) for k in keys)
            before = L.text(row.get(name))
            too_long = before + " " * (limit - len(before) + 3)
            r = self.update(self.fx.stg, table, pk, {name: too_long})
            if r.status in (200, 204):
                injected = (name, pk, before, limit)
                self.restore.append((self.fx.stg, table, pk, {name: before}))
                break
            self.say("the staging write of %s on %s was refused (HTTP %d %s): next attribute" % (name, pk, r.status, clip(r.error_text(), 120)))
        self.rep.check("A1", "a field made too long so validation flags it", injected is not None,
                       "%s.%s on record %s (limit %s)" % (table, injected[0], injected[1], injected[3]) if injected else "",
                       "no attribute with a maxLength and a filled value could be written (%d candidate(s)); the agent may still find other findings" % len(candidates))
        self.report_data["agents"]["injected"] = injected and {"field": injected[0], "record": injected[1]}

        # under the default SHAPES_ONLY policy the model sees only a.A/9 shapes and rightly abstains on a correction: the
        # scratch project opts in to SAMPLE_VALUES (allowed only when Settings.llmValueSharingMax allows it, docs/LLM-DATA-POLICY.md)
        resp, project = self.fx.ds.get_row("Project", self.code)
        before = L.text((project or {}).get("llmValueSharing")) or "SHAPES_ONLY"
        r = self.update(self.fx.ds, "Project", self.code, {"llmValueSharing": "SAMPLE_VALUES"})
        if r.status in (200, 204):
            self.restore.append((self.fx.ds, "Project", self.code, {"llmValueSharing": before}))
        self.rep.check("A1", "the scratch project shares sample values with the model", r.status in (200, 204), "was %s" % before,
                       "HTTP %d %s" % (r.status, clip(r.error_text())))

        autonomy = "ACT" if self.args["act"] else "SUGGEST"
        # allowedTools is a list field: Data Services wants each item wrapped as {"content": ...}
        definition = {"code": self.agent_code, "label": "RC quality agent %s" % self.code, "purpose": "RC test of the agent framework on project %s" % self.code,
                      "kind": "QUALITY_FIX", "project": self.code, "entity": table, "zone": "STAGING", "trigger": "MANUAL",
                      "allowedTools": [{"content": "factory:record.correct"}], "autonomy": autonomy, "confidenceThreshold": 0 if self.args["act"] else 90, "ownerProfile": OWNER_PROFILE,
                      "maxItems": 5, "instructions": AGENT_INSTRUCTIONS, "enabled": False}
        r = self.fx.ds.create("AgentDefinition", definition)
        self.rep.check("A2", "agent %s declared (%s, owner %s)" % (self.agent_code, autonomy, OWNER_PROFILE), r.status in (200, 201), "", "HTTP %d %s" % (r.status, clip(r.error_text())))
        if r.status not in (200, 201):
            raise Stop()

        r = self.post("/agents/%s/run" % self.agent_code)
        body = r.json() if isinstance(r.json(), dict) else {}
        job_id = str(body.get("jobId") or "")
        self.rep.check("A3", "run queued as a background job", r.status == 200 and bool(job_id), "job %s" % job_id, "HTTP %d %s" % (r.status, clip(r.error_text())))
        if not job_id:
            raise Stop()
        try:
            job = self.fx.wait_job(job_id, int(self.env.get("E2E_TIMEOUT_AGENT", "900")), "the agent run")
        except L.Timeout as exc:
            self.rep.failed("A3", "agent job ended", "%s (cancel it with POST /jobs/%s/cancel)" % (exc, job_id))
            raise Stop()
        self.rep.check("A3", "agent job ended DONE", job.get("status") == "DONE", clip(job.get("message", ""), 200), "%s: %s" % (job.get("status"), clip(job.get("message", ""), 300)))

        runs = self.select("AgentRun", "./agent='%s'" % self.agent_code)
        self.agent_rows["runs"].extend(L.text(x.get("id")) for x in runs)
        run = runs[-1] if runs else {}
        self.rep.check("A4", "AgentRun recorded, acting for the caller", bool(run) and L.text(run.get("actingFor")) == self.env.get("EBX_USER", ""),
                       "run %s, via %s" % (L.text(run.get("id")), L.text(run.get("via"))), "runs: %d, actingFor %s" % (len(runs), L.text(run.get("actingFor"))))
        self.rep.check("A4", "the run shows its inputs and the model's decision", bool(L.text(run.get("inputs"))) and bool(L.text(run.get("decision"))),
                       "inputs %d chars, decision %d chars, lowest confidence %s" % (len(L.text(run.get("inputs"))), len(L.text(run.get("decision"))), L.text(run.get("confidence"))),
                       "inputs or decision empty: %s" % clip(L.text(run.get("message")) or job.get("message", ""), 300))
        if injected:
            self.rep.check("A4", "the injected finding was shown to the model", injected[1] in L.text(run.get("inputs")) or injected[0] in L.text(run.get("inputs")),
                           "", "the inputs do not name %s / %s" % (injected[0], injected[1]))
        run_id = L.text(run.get("id"))
        proposals = self.select("AgentProposal", "./run='%s'" % run_id) if run_id else []
        self.agent_rows["proposals"].extend(L.text(p.get("id")) for p in proposals)
        if self.args["act"]:
            self.act_checks(run, proposals, injected, table)
            return
        pending = [p for p in proposals if L.text(p.get("status")) == "PROPOSED"]
        self.rep.check("A5", "at least one decision waits for review (PROPOSED)", bool(pending), "%d of %d proposal(s)" % (len(pending), len(proposals)),
                       "%d proposal(s), none PROPOSED (did the model abstain? decision: %s)" % (len(proposals), clip(L.text(run.get("decision")), 200)))
        self.rep.check("A5", "no suggestion was applied", not any(L.text(p.get("status")) == "APPLIED" for p in proposals), "", "an APPLIED line under autonomy SUGGEST")
        escalation = L.text(run.get("escalation"))
        self.rep.check("A6", "the run opened the review workflow %s" % WORKFLOW, bool(escalation), "process instance %s" % escalation,
                       "AgentRun.escalation is empty: is %s published? run message: %s" % (WORKFLOW, clip(L.text(run.get("message")) or job.get("message", ""), 300)))
        self.report_data["agents"].update({"job": job_id, "run": run_id, "escalation": escalation, "proposals": len(proposals)})
        if not pending:
            raise Stop()

        user = self.env.get("EBX_USER", "")
        if self.args["decide_by_route"]:
            first, rest = pending[0], pending[1:]
            r = self.post("/agents/proposals/%s/decide?accept=true" % L.text(first.get("id")), allow=True)
            self.rep.check("A7", "proposal %s accepted through the route" % L.text(first.get("id")), r.status == 200, "", "HTTP %d %s" % (r.status, clip(r.error_text())))
            for p in rest:
                r = self.post("/agents/proposals/%s/decide?accept=false" % L.text(p.get("id")), allow=True)
                self.rep.check("A7", "proposal %s rejected through the route" % L.text(p.get("id")), r.status == 200, "", "HTTP %d %s" % (r.status, clip(r.error_text())))
            expected = {L.text(first.get("id")): "ACCEPTED"}
            expected.update((L.text(p.get("id")), "REJECTED") for p in rest)
            if escalation:
                self.leftovers.append("workflow process instance %s (%s): its task is still in the inbox; accept or reject it (nothing is left to decide) or "
                                      "terminate it in Administration > Workflow management" % (escalation, WORKFLOW))
        else:
            self.out.write("\n>>> Open the EBX inbox as %s: the task 'Review the agent's decisions' (agent %s, run %s) is offered to %s.\n"
                           ">>> Optionally open its records, then click Approve. Press Enter here once the task is closed. " % (user, self.agent_code, run_id, OWNER_PROFILE))
            self.out.flush()
            try:
                sys.stdin.readline()
            except KeyboardInterrupt:
                raise Stop()
            expected = dict((L.text(p.get("id")), "ACCEPTED") for p in pending)
        deadline = time.time() + 120
        while True:
            now = dict((L.text(p.get("id")), p) for p in self.select("AgentProposal", "./run='%s'" % run_id))
            if all(L.text(now.get(i, {}).get("status")) != "PROPOSED" for i in expected) or time.time() > deadline:
                break
            time.sleep(3)
        for pid, status in sorted(expected.items()):
            row = now.get(pid, {})
            got = L.text(row.get("status"))
            if not self.args["decide_by_route"] and got == "REJECTED":
                status = "REJECTED"   # you rejected the task: every line is REJECTED, which is as valid a decision
            self.rep.check("A8", "proposal %s is %s, decided by %s" % (pid, status, user), got == status and L.text(row.get("decidedBy")) == user,
                           "decided at %s" % L.text(row.get("decidedAt")), "status %s, decidedBy %s" % (got, L.text(row.get("decidedBy"))))
        if self.args["decide_by_route"] and expected:
            pid = sorted(expected)[0]
            r = self.post("/agents/proposals/%s/decide?accept=true" % pid, allow=True)
            self.rep.check("A9", "a second decision on a decided line is refused", r.status == 409, "HTTP 409", "HTTP %d %s" % (r.status, clip(r.error_text())))

    def act_checks(self, run, proposals, injected, table):
        """Autonomy ACT, threshold 0: every allowed decision is applied by the runner (a TOOL step, an APPLIED line), none waits, nothing escalates."""
        applied = [p for p in proposals if L.text(p.get("status")) == "APPLIED"]
        self.rep.check("A5", "the runner applied the corrections (ACT)", bool(applied), "%d of %d line(s) APPLIED" % (len(applied), len(proposals)),
                       "%d line(s): %s" % (len(proposals), ", ".join("%s %s" % (L.text(p.get("status")), clip(L.text(p.get("applyMessage")), 80)) for p in proposals) or
                                            "none (decision: %s)" % clip(L.text(run.get("decision")), 200)))
        self.rep.check("A5", "no line waits for a review under ACT with threshold 0", not any(L.text(p.get("status")) == "PROPOSED" for p in proposals), "",
                       "a PROPOSED line under ACT with threshold 0")
        steps = self.select("AgentStep", "./run='%s'" % L.text(run.get("id")))
        tools = [s for s in steps if L.text(s.get("kind")) == "TOOL"]
        self.rep.check("A6", "one TOOL step per applied line", len(tools) >= len(applied) and bool(applied), "%d TOOL step(s)" % len(tools), "%d TOOL step(s) for %d APPLIED" % (len(tools), len(applied)))
        if injected:
            name, pk, before, limit = injected
            resp, row = self.fx.stg.get_row(table, pk)
            now = L.text((row or {}).get(name))
            self.rep.check("A7", "the injected value is corrected in staging", resp.status == 200 and len(now) <= limit and now.strip() == before.strip(),
                           "%s = %r (%d chars, limit %d)" % (name, clip(now, 60), len(now), limit), "HTTP %d, %s = %r (%d chars)" % (resp.status, name, clip(now, 60), len(now)))

    def events(self):
        """Scenario E: an enabled EVENT agent on STAGING_LOAD_DONE runs after a staging load job, acting for enabledBy."""
        self.out.write("\n== scenario E: an agent started by the end of a staging load\n")
        user = self.env.get("EBX_USER", "")
        code = self.code + "EV"
        person = self.person_entity()
        # an empty allow-list: whatever the model answers is refused, so nothing is applied or escalated; the test is the trigger
        definition = {"code": code, "label": "RC event agent %s" % self.code, "purpose": "RC test of the event trigger on project %s" % self.code,
                      "kind": "QUALITY_FIX", "project": self.code, "entity": self.fx.table_path(person), "zone": "STAGING", "trigger": "EVENT",
                      "triggerEvent": "STAGING_LOAD_DONE", "allowedTools": [], "autonomy": "SUGGEST", "confidenceThreshold": 90, "ownerProfile": OWNER_PROFILE,
                      "maxItems": 3, "instructions": AGENT_INSTRUCTIONS, "enabled": True, "enabledBy": user}
        r = self.fx.ds.create("AgentDefinition", definition)
        self.rep.check("E1", "event agent %s declared (STAGING_LOAD_DONE, enabled by %s)" % (code, user), r.status in (200, 201), "", "HTTP %d %s" % (r.status, clip(r.error_text())))
        if r.status not in (200, 201):
            raise Stop()
        self.agent_codes.append(code)
        self.fx.start_job("E2", "staging load (every row)", "/projects/%s/staging/load?scope=all" % self.code, self.fx.timeout("BUILD", 1200), "the staging load")
        deadline = time.time() + int(self.env.get("E2E_TIMEOUT_AGENT", "900"))
        runs = []
        while time.time() < deadline:
            runs = self.select("AgentRun", "./agent='%s'" % code)
            if runs and all(L.text(x.get("status")) not in ("", "RUNNING") for x in runs):
                break
            time.sleep(5)
        self.agent_rows["runs"].extend(L.text(x.get("id")) for x in runs)
        for x in runs:
            self.agent_rows["proposals"].extend(L.text(p.get("id")) for p in self.select("AgentProposal", "./run='%s'" % L.text(x.get("id"))))
        run = runs[-1] if runs else {}
        self.rep.check("E3", "the staging load started the agent, acting for %s" % user,
                       bool(run) and L.text(run.get("via")).startswith("event STAGING_LOAD_DONE") and L.text(run.get("actingFor")) == user,
                       "run %s, via %s, status %s" % (L.text(run.get("id")), L.text(run.get("via")), L.text(run.get("status"))),
                       "runs: %d, via %s, actingFor %s" % (len(runs), L.text(run.get("via")), L.text(run.get("actingFor"))))
        self.rep.check("E3", "exactly one run for one event", len(runs) == 1, "", "%d runs" % len(runs))
        self.report_data["agents"]["event"] = {"run": L.text(run.get("id")), "via": L.text(run.get("via")), "status": L.text(run.get("status"))}

    def cleanup_agents(self):
        for pid in self.agent_rows["proposals"]:
            self.delete(self.fx.ds, "AgentProposal", pid)
        for run_id in self.agent_rows["runs"]:
            for step in self.select("AgentStep", "./run='%s'" % run_id):
                self.delete(self.fx.ds, "AgentStep", L.text(step.get("id")))
            self.delete(self.fx.ds, "AgentRun", run_id)
        for code in self.agent_codes:
            resp, row = self.fx.ds.get_row("AgentDefinition", code)
            if resp.status == 200 and row:
                r = self.fx.ds.delete("AgentDefinition", code)
                if r.status not in (200, 204):
                    self.leftovers.append("AgentDefinition %s (HTTP %d on delete)" % (code, r.status))

    # ------------------------------------------------------------------------------------------------------------ scenario B: drift
    def drift(self):
        self.out.write("\n== scenario B: source drift proposed, decided, applied\n")
        sources = self.select("ProjectSource", "./project='%s'" % self.code)
        target = None
        for source in sources:
            for table in self.select("LandingTable", "./projectSource='%s'" % L.text(source.get("id"))):
                if L.text(table.get("status")) in ("GENERATED", "LOADED"):
                    columns = [c for c in self.select("LandingColumn", "./landingTable='%s'" % L.text(table.get("id"))) if not L.truthy(c.get("sourceKey"))]
                    typed = [c for c in columns if L.text(c.get("sourceDataType"))]
                    if len(columns) >= 2 and typed:
                        target = (source, table, columns, typed)
                        break
            if target:
                break
        self.rep.check("B1", "a GENERATED/LOADED landing table with two non-key columns", target is not None,
                       target and "%s (%s)" % (L.text(target[1].get("tableName")), L.text(target[1].get("status"))) or "", "none on project %s" % self.code)
        if target is None:
            raise Stop()
        source, table, columns, typed = target
        table_id, status_before = L.text(table.get("id")), L.text(table.get("status"))
        retyped = typed[0]
        renamed = next(c for c in columns if L.text(c.get("id")) != L.text(retyped.get("id")))
        real_name, real_type = L.text(renamed.get("sourceColumn")), L.text(retyped.get("sourceDataType"))
        fake_name, fake_type = "%s_OLD_%s" % (self.code, real_name), "E2E_TYPE_%s" % self.code
        for row, fields, back in ((renamed, {"sourceColumn": fake_name}, {"sourceColumn": real_name}), (retyped, {"sourceDataType": fake_type}, {"sourceDataType": real_type})):
            r = self.update(self.fx.ds, "LandingColumn", L.text(row.get("id")), fields)
            self.rep.check("B2", "scan copy edited: %s" % json.dumps(fields), r.status in (200, 204), "", "HTTP %d %s" % (r.status, clip(r.error_text())))
            if r.status not in (200, 204):
                raise Stop()
            self.restore.append((self.fx.ds, "LandingColumn", L.text(row.get("id")), back))
        self.report_data["drift"].update({"landingTable": table_id, "renamed": real_name, "retyped": L.text(retyped.get("sourceColumn"))})

        r = self.post("/sources/%s/landing/plan" % L.text(source.get("id")), slow=True, allow=True)
        line = next((t for t in (r.json() or {}).get("tables", []) if L.text(t.get("tableName")) == L.text(table.get("tableName"))), {}) if r.status == 200 else {}
        self.rep.check("B3", "the landing plan reports the table DRIFTED", r.status == 200 and L.text(line.get("outcome")) == "DRIFTED", "",
                       "HTTP %d, line %s %s" % (r.status, clip(json.dumps(line), 200), clip(r.error_text(), 200) if r.status != 200 else ""))
        drifts = self.select("SourceDrift", "./landingTable='%s'" % table_id)
        self.drift_ids = [L.text(d.get("id")) for d in drifts]
        by_kind = dict((L.text(d.get("kind")), d) for d in drifts if L.text(d.get("status")) == "PROPOSED")
        want = {"REMOVED": fake_name, "ADDED": real_name, "RETYPED": L.text(retyped.get("sourceColumn"))}
        self.rep.check("B4", "exactly three proposals: ADDED, REMOVED, RETYPED", len(drifts) == 3 and set(by_kind) == set(want),
                       ", ".join("%s %s" % (k, L.text(d.get("sourceColumn"))) for k, d in sorted(by_kind.items())),
                       "%d row(s): %s" % (len(drifts), "; ".join("%s %s %s" % (L.text(d.get("kind")), L.text(d.get("sourceColumn")), L.text(d.get("status"))) for d in drifts)))
        for kind, column in want.items():
            d = by_kind.get(kind, {})
            self.rep.check("B4", "%s names %s and states its landing, staging and mapping changes" % (kind, column),
                           L.text(d.get("sourceColumn")) == column and all(L.text(d.get(f)) for f in ("landingChange", "stagingChange", "mappingChange")),
                           clip(L.text(d.get("landingChange")), 120), "row: %s" % clip(json.dumps(d), 300))
        added = by_kind.get("ADDED", {})
        self.rep.check("B4", "ADDED carries a create_field operation", "create_field" in L.text(added.get("operations")), clip(L.text(added.get("operations")), 160), "operations: %s" % clip(L.text(added.get("operations")), 200))
        resp, now = self.fx.ds.get_row("LandingTable", table_id)
        self.rep.check("B5", "the landing table is DRIFTED", L.text((now or {}).get("status")) == "DRIFTED", clip(L.text((now or {}).get("message")), 160), "status %s" % L.text((now or {}).get("status")))
        r = self.fx.get("/projects/%s/drift?status=PROPOSED" % self.code)
        listed = r.json() if r.status == 200 and isinstance(r.json(), list) else []
        self.rep.check("B5", "GET /projects/{code}/drift lists them", len([x for x in listed if L.text(x.get("landingTable")) == table_id]) == 3, "", "HTTP %d, %d listed" % (r.status, len(listed)))
        if set(by_kind) != set(want):
            raise Stop()

        def decide(kind, accept):
            r = self.post("/drift/%s/decide?accept=%s" % (L.text(by_kind[kind].get("id")), "true" if accept else "false"), allow=True)
            self.rep.check("B6", "%s %s" % (kind, "accepted" if accept else "rejected"), r.status == 200, "", "HTTP %d %s" % (r.status, clip(r.error_text())))

        def apply(kind):
            r = self.post("/drift/%s/apply" % L.text(by_kind[kind].get("id")), slow=True, allow=True)
            body = r.json() if isinstance(r.json(), dict) else {}
            self.rep.check("B7", "%s applied" % kind, r.status == 200 and body.get("status") == "APPLIED", clip(body.get("message", ""), 200), "HTTP %d %s" % (r.status, clip(r.error_text(), 400)))
            return body

        r = self.post("/drift/%s/apply" % L.text(by_kind["RETYPED"].get("id")), allow=True)
        self.rep.check("B6", "a PROPOSED change is never applied (409 before a decision)", r.status == 409, "", "HTTP %d %s" % (r.status, clip(r.error_text())))
        decide("RETYPED", True)
        apply("RETYPED")
        resp, col = self.fx.ds.get_row("LandingColumn", L.text(retyped.get("id")))
        self.rep.check("B7", "RETYPED: the landing column has its real type back", L.text((col or {}).get("sourceDataType")) == real_type, real_type, "sourceDataType %s" % L.text((col or {}).get("sourceDataType")))
        self.restore = [x for x in self.restore if x[2] != L.text(retyped.get("id"))]
        decide("ADDED", True)
        body = apply("ADDED")
        if body.get("status") == "APPLIED":
            # the catalog's column now has its own landing column: the renamed copy stays as it is, like the removal that is rejected below
            self.restore = [x for x in self.restore if x[2] != L.text(renamed.get("id"))]
        self.report_data["drift"]["addedApply"] = body
        field = L.text(added.get("fieldName"))
        new_cols = [c for c in self.select("LandingColumn", "./landingTable='%s'" % table_id) if L.text(c.get("sourceColumn")) == real_name]
        self.rep.check("B7", "ADDED: a landing column %s -> %s exists" % (real_name, field), len(new_cols) == 1 and L.text(new_cols[0].get("fieldName")) == field, "",
                       "%d column(s) named %s" % (len(new_cols), real_name))
        self.rep.check("B7", "ADDED: the landing model was republished", body.get("published") is True, clip(body.get("patch", ""), 160), "answer: %s" % clip(json.dumps(body), 300))
        self.rep.info("B7", "ADDED: the landing flow", "regenerated" if "Landing flow regenerated" in body.get("message", "") else clip(body.get("message", ""), 200))
        decide("REMOVED", False)
        resp, now = self.fx.ds.get_row("LandingTable", table_id)
        self.rep.check("B8", "every change decided: the table is back to %s" % status_before, L.text((now or {}).get("status")) == status_before, "", "status %s" % L.text((now or {}).get("status")))

        r = self.post("/sources/%s/landing/plan" % L.text(source.get("id")), slow=True, allow=True)
        again = self.select("SourceDrift", "./landingTable='%s'" % table_id)
        self.drift_ids = [L.text(d.get("id")) for d in again]
        self.rep.check("B9", "a second plan proposes nothing new (the rejected change is not brought back)", r.status == 200 and len(again) == 3, "",
                       "HTTP %d, %d row(s) now" % (r.status, len(again)))
        resp, now = self.fx.ds.get_row("LandingTable", table_id)
        self.rep.check("B9", "and leaves the table %s" % status_before, L.text((now or {}).get("status")) == status_before, "", "status %s" % L.text((now or {}).get("status")))
        r = self.post("/drift/%s/decide?accept=true" % L.text(by_kind["RETYPED"].get("id")), allow=True)
        self.rep.check("B10", "a decision on an applied change is refused", r.status == 409, "HTTP 409", "HTTP %d %s" % (r.status, clip(r.error_text())))

    def cleanup_drift(self):
        for did in self.drift_ids:
            self.delete(self.fx.ds, "SourceDrift", did)

    # ------------------------------------------------------------------------------------------------------------ run
    def cleanup(self):
        if self.fx.ds is None:
            return   # the module never answered: nothing was written
        self.out.write("\n== cleanup\n")
        for ds, table, pk, fields in reversed(self.restore):
            r = self.update(ds, table, pk, fields)
            if r.status not in (200, 204):
                self.leftovers.append("%s %s not restored to %s (HTTP %d)" % (table, pk, json.dumps(fields), r.status))
        self.cleanup_agents()
        self.cleanup_drift()
        drop_project = (self.created_here and not self.args["keep"]) or (not self.created_here and self.args["cleanup"])
        if drop_project and self.fx.created is False and not self.created_here:
            self.fx.created = True   # a reused project: B2C's cleanup deletes it when asked to
        if drop_project and self.fx.created:
            self.fx.cleanup()
            self.leftovers.extend(self.fx.leftovers)
        elif self.fx.created or not self.created_here:
            self.leftovers.append("project %s kept (%s)" % (self.code, "--keep" if self.created_here else "reused; --cleanup deletes it"))

    def run(self):
        code_exit = 0
        try:
            if not self.created_here:
                self.fixture()
            else:
                self.fx.step0()
            self.inventory()
            if self.created_here:
                self.fixture()
            if self.args["only"] in (None, "agents"):
                try:
                    self.agents()
                except Stop:
                    pass
                if self.args["event"]:
                    try:
                        self.events()
                    except (Stop, B.Stop):
                        pass
            if self.args["only"] in (None, "drift"):
                try:
                    self.drift()
                except Stop:
                    pass
        except B.MissingRoute:
            code_exit = 3
        except (Stop, B.Stop):
            if not self.rep.counts()["FAIL"]:
                # a stop inside the B2C fixture (its step 0) is recorded in its own report only: count it here too
                last = [x for x in self.fx.rep.checks if x["status"] == "FAIL"]
                self.rep.failed("0", "stopped before the scenarios", last[-1]["name"] if last else "see above")
        except KeyboardInterrupt:
            self.rep.failed("-", "run interrupted", "cleaning up")
        except client.SafetyError as exc:
            self.rep.failed("-", "stopped by the safety guard", str(exc))
        except Exception as exc:
            self.rep.failed("-", "run crashed", "%s: %s" % (type(exc).__name__, exc))
        finally:
            try:
                self.cleanup()
            except Exception as exc:
                self.rep.failed("C", "cleanup crashed", "%s: %s" % (type(exc).__name__, exc))
        self.out.write(self.rep.table() + "\n")
        for item in self.leftovers:
            self.out.write("left: %s\n" % item)
        if self.args["report"]:
            with open(self.args["report"], "w") as fh:
                json.dump({"report": self.report_data, "checks": self.rep.checks, "leftovers": self.leftovers}, fh, indent=2, default=str)
        if code_exit:
            return code_exit
        return 1 if self.rep.counts()["FAIL"] else 0


def dry_run(out):
    out.write(__doc__)
    out.write("\nPlan (nothing sent):\n")
    out.write("  0  GET %s/status, then each of: %s\n" % ("/ebx-mdm-factory/rest/v1", ", ".join("%s %s" % (m, p) for m, p, _, _ in REQUIRED_ROUTES)))
    out.write("  F  B2C scenario steps 1..6 on a new E2EB2C<timestamp> project (or --project CODE)\n")
    out.write("  A  StagingAttribute with maxLength -> one value padded past it; AgentDefinition <CODE>QA (QUALITY_FIX, SUGGEST, %s); POST /agents/<CODE>QA/run;\n"
              "     poll GET /projects/<CODE>/jobs; AgentRun / AgentProposal / escalation; decide (inbox, or POST /agents/proposals/{id}/decide)\n" % OWNER_PROFILE)
    out.write("  B  LandingColumn: rename one sourceColumn, change one sourceDataType; POST /sources/{id}/landing/plan; SourceDrift x3; decide/apply; plan again\n")
    out.write("  C  restore edited rows; delete AgentProposal/AgentStep/AgentRun/AgentDefinition and SourceDrift rows; delete the project (B2C cleanup)\n")
    return 0


def main(argv, env, out=None):
    out = out or sys.stdout
    args = {"only": None, "project": None, "cleanup": False, "keep": False, "decide_by_route": not sys.stdin.isatty(), "report": None, "act": False, "event": False}
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--dry-run":
            return dry_run(out)
        if a in ("-h", "--help"):
            out.write(__doc__)
            return 0
        if a == "--only" and i + 1 < len(argv) and argv[i + 1] in ("agents", "drift"):
            args["only"] = argv[i + 1]
            i += 1
        elif a == "--project" and i + 1 < len(argv):
            args["project"] = argv[i + 1]
            i += 1
        elif a == "--report" and i + 1 < len(argv):
            args["report"] = argv[i + 1]
            i += 1
        elif a == "--cleanup":
            args["cleanup"] = True
        elif a == "--keep":
            args["keep"] = True
        elif a == "--decide-by-route":
            args["decide_by_route"] = True
        elif a == "--decide-in-inbox":
            args["decide_by_route"] = False
        elif a == "--act":
            args["act"] = True
        elif a == "--event":
            args["event"] = True
        else:
            sys.stderr.write("unexpected argument: %s (see --help)\n" % a)
            return 2
        i += 1
    given = args["project"]
    if given is not None and not (re.match(r"^[A-Za-z0-9]+$", given) and given.startswith(B.Scenario.CODE_PREFIX) and len(given) > len(B.Scenario.CODE_PREFIX)):
        sys.stderr.write("--project takes the code of a scratch project the B2C scenario created (it starts with %s)\n" % B.Scenario.CODE_PREFIX)
        return 2
    if args["cleanup"] and given is None:
        sys.stderr.write("--cleanup goes with --project (a project this run creates is deleted anyway, unless --keep)\n")
        return 2
    run = Run(env, out, args)
    if not run.fx.connect():
        return 2
    out.write("Agents and source drift RC test against %s, project %s%s\n" % (run.fx.base, run.code, "" if run.created_here else " (reused)"))
    return run.run()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))
