#!/usr/bin/env python3
"""The B2C scenario carried through the MASTER and MATCHING steps (wizard steps 5 and 6), with a ground truth to measure the matching, through REST and Data Services only.

Steps 1-6 are the nominal scenario's (project, three files, landing, staging proposal, decisions, staging build and load, all its checks), then:
  7  master (wizard step 5): the one-click "Build the master" run (POST master/build), the master tables against the staging, no open MASTER reject, the Xref (lineage) rows
  8  matching (wizard step 6): "Propose the matching plans" (rules + the language model as configured), accept, then ONE of two variants (below), the activation read on the match-merge
     server (state ENABLED and metadatasetExists true), the run, the Match and Merge metadataset read
  9  quality against the ground truth (tools/e2e/gen_b2c.py writes truth_b2c.json: the person every source row came from): pairwise precision / recall / F1 of the clusters the
     add-on made, (a) merged only and (b) merged + suspects, false merges, golden count, singletons, and the survivorship of three sampled people printed for the eye
  10 distribution (wizard step 7), ONLY with --distribute: a Kafka connection of the run, a REALTIME target, configure, install-triggers, first full send, the topics read with
     kafka-console-consumer.sh and measured (one message per golden record, none for a merged member, key = id, one topic per table), a real-time change, the retire gap, stop.
     A broker that does not answer SKIPs the step with one clear line (tools/e2e/b2c_distribution.py)
  11 summary (wizard step 8): the facts the summary is built from, checked against what the run measured (rows per zone, golden records, targets, last run)

The two VARIANTS (found by hand on 2026-10-17: Configure refuses an entity once its plan was simulated, "Merge policy Client__preview is already the manual-merge policy"):
  plain            configure the accepted plan without simulating it first
  simulate-first   press the plan card's Simulate (POST matching/simulate-plan: it writes the <Entity>__preview policy on a plan not applied yet), THEN configure
Each is a project of its own and reports its own PASS/FAIL ("configure without a simulation" / "configure after a simulation"). The scenario does NOT work around the defect: if the
configure refuses, that is a FAIL and the run stops there (Run stays disabled in the wizard too).

    EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_master.sh [--variants plain|simulate-first|both] [--cleanup] [--through 1..9] [--report FILE]
    tools/e2e/b2c_master.sh --auto-merge ...         # turns automatic merge on for the person plan (what the wizard's toggle writes) after the plans are accepted, before Configure
    tools/e2e/b2c_master.sh --distribute ...         # step 10: distributes the golden records to Kafka (E2E_KAFKA_BOOTSTRAP localhost:9082, E2E_KAFKA_HOME the broker's folder) and measures it
    tools/e2e/b2c_master.sh --cleanup-project CODE   # deletes a project left in place by an earlier run (E2EMASTER...), its Kafka topics included (only <code lower case>.* names; --keep-topics keeps them)
    tools/e2e/b2c_master.sh --from-project CODE --from-step N [--variants plain|simulate-first] [--through M]   # continues a project left in place, from step N (2..11; typically 7, 8 or 10)
    tools/e2e/b2c_master.sh --selftest               # the logic and the whole scenario against the stand-in factory (no instance)
    tools/e2e/b2c_master.sh --dry-run                # generates and checks the files and the truth, prints the plan and the routes, sends nothing

--from-project CODE --from-step N (lot F4, 2026-10-19): shortens the cycle. The scenario does not create a project: it loads CODE (its sources, landing tables and columns, entities, feeds, plans, staging / master /
metadata datasets, distribution targets - the same reads the steps make - and the fixtures regenerated with the same seed for the ground truth), SKIPs steps 1..N-1 and runs from N, the checks unchanged. A project
that is not in the state step N starts from (no master dataset for 8, no APPLIED plan for 9, step 10 already run for 10...) is REFUSED: exit 2, "RESUME REFUSED: ..." with what is missing, nothing posted. One variant:
plain unless --variants simulate-first. Combine with the instance's -Debx.factory.llm.replay.dir to replay the language model's answers too (docs/E2E-B2C.md, "Shorter test cycle").

--auto-merge (lot M10b): the accepted plan keeps autoMerge=false by default, so the MATCH leaves are configured as SUSPECTED and nothing merges (the "merged only" measures are skipped). With the option the
run writes MatchPlan.autoMerge=true on the person plan between "accepted" and "configure" - the same single-field write as the plan card's "Merge confirmed matches automatically" checkbox
(ProposalReviews.setMatchPlanAutoMerge: a Data Services write on the Factory dataset, no REST route exists for it) - and the merged-only precision / recall are judged, with the number of golden records the add-on created.
The default (suspects only) is unchanged.

--distribute (lot S1): off by default, like --auto-merge. It WRITES a Connectors connection (Kafka<project code>), a distribution target, script triggers on the master model and messages on
the broker's topics <project code lower case>.<table>; it never deletes a topic. Credentials stay out of every file; the Kafka CLI is only run on the machine that runs the scenario.

The projects are LEFT IN PLACE by default (the run ends with the addresses of steps 5 and 6 of the wizard). Credentials come only from the environment and are never printed. The
scenario WRITES (project, Connectors connections, dataspaces, models, a master load, a matching configuration and run): test instance only.
--cleanup / --cleanup-project also delete the project's Kafka topics (kafka-topics.sh --delete; E2E_KAFKA_HOME, E2E_KAFKA_BOOTSTRAP), only names that start with <project code lower case>.; --keep-topics leaves them
(listed in LEFT BEHIND). A broker that does not answer leaves them, said with the reason.

Exit: 0 every check passed, 1 at least one FAIL, 2 environment or arguments, 3 a route the scenario needs is missing on this build. See docs/E2E-B2C.md ("Master and matching").
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "itest"))

import b2c_distribution as D  # noqa: E402
import b2c_scenario as S  # noqa: E402
import client  # noqa: E402
import e2e_lib as L  # noqa: E402
import master_lib as M  # noqa: E402

MASTER_STEPS = dict(S.STEPS)
MASTER_STEPS.update({7: "master build (wizard step 5)", 8: "matching (wizard step 6)", 9: "quality against the ground truth", 10: "distribution (wizard step 7)", 11: "summary (wizard step 8)"})

# the routes this scenario needs beyond the nominal ones. matching/simulate-plan, master/build and matching/propose were added by this lot.
MASTER_ROUTES = [("POST", "/projects/{x}/master/build", None, "build the master (the page's one-click run)"),
                 ("POST", "/projects/{x}/matching/propose", None, "propose the matching plans"),
                 ("POST", "/projects/{x}/matching/simulate-plan", None, "simulate a plan (the card's Simulate button)"),
                 ("POST", "/projects/{x}/matching/configure", None, "configure the accepted plans"),
                 ("POST", "/projects/{x}/matching/run", None, "run the matching")]
MASTER_OPTIONAL_ROUTES = [("GET", "/sources/onboarding/connections", None, "list the connections (what the project deletion leaves behind)"),
                          ("GET", "/projects/{x}/rejects/causes", None, "why the rows were held back (read only when a master reject exists)"),
                          ("GET", "/projects/{x}/matching/activity", None, "the matching activity (read after the run)")]

MCP_PATH = "/ebx-assistant/mcp/v1/match-merge"
MCP_READ_TOOLS = ("get_matching_activation_status", "get_matching_config", "get_business_object_config")      # the only tools this scenario calls on the match-merge server: both read only
TRUTH_APPS = ("ECOM", "STORES", "LOYALTY")
S_CONNECTORS_SPACE, S_CONNECTORS_DATASET = D.CONNECTORS_SPACE, D.CONNECTORS_DATASET


def dry_run(out):
    work = tempfile.mkdtemp(prefix="b2c-master-dry-")
    try:
        paths = S.generate_files(work)
        problems, counts = S.check_files(paths)
        out.write("files generated by gen_b2c.py (seeded): %s\n" % ", ".join("%s %d" % (a, counts.get(a, -1)) for a in TRUTH_APPS))
        try:
            truth, summary = M.load_truth(os.path.join(work, "truth_b2c.json"))
            expected = M.expected_clusters_summary(truth, TRUTH_APPS)
            out.write("ground truth (truth_b2c.json): %d rows, %d people; among the three sources: %d records of %d people, %d true pairs (%d within a source, %d across sources)\n" % (
                summary["rows"], summary["persons"], expected["records"], expected["persons"], expected["truePairs"], expected["pairsWithinASource"], expected["pairsAcrossSources"]))
        except (OSError, ValueError, KeyError) as exc:
            problems.append("truth file: %s" % exc)
        for p in problems:
            out.write("PROBLEM %s\n" % p)
        out.write("\nplan (nothing is sent), once per variant (plain, simulate-first):\n")
        for n, name in sorted(MASTER_STEPS.items()):
            out.write("  %d. %s\n" % (n, name))
        out.write("\noption --auto-merge: before Configure, %s\n" % MasterScenario.AUTO_MERGE_ROUTE)
        out.write("option --from-project CODE --from-step N (2..11): loads the project from the factory dataset (Project, ProjectSource, LandingTable, LandingColumn, StagingEntity, TransferFeed, StagingAttribute, "
                  "AttributeMapping, MatchPlan, TransferReject, DistributionTarget; the staging, master and <dataset>_<table>_MDS datasets), regenerates the fixtures for the ground truth, skips steps 1..N-1; no extra route\n")
        out.write("option --keep-topics: with --cleanup / --cleanup-project, leaves the project's Kafka topics on the broker (kafka-topics.sh --delete otherwise, names starting with the project code in lower case only)\n")
        out.write("\noption --distribute (step 10), routes beyond the above:\n")
        for method, template, _b, label in D.DIST_ROUTES + D.DIST_OPTIONAL_ROUTES:
            out.write("  %-4s %-58s %s\n" % (method, template, label))
        out.write("  POST %s  (acceptSuspects: merges two suspects to measure the retire notices of a merge)\n" % D.ADDON_ACCEPT)
        out.write("  DELETE %s  (unMerge: unmerges a golden record with merged members to measure the retire path; lot S4)\n" % D.ADDON_UNMERGE)
        out.write("  Data Services: BConnectors/Connectors Connector + ConnectorProperty (the run's Kafka connection), the master person table (PUT one field), DistributionTarget / DistributionEvent / PipelineStep\n")
        out.write("  Kafka CLI: kafka-topics.sh --list (broker probe), kafka-console-consumer.sh --from-beginning --formatter-property print.key=true (--property on an older CLI) (E2E_KAFKA_HOME, E2E_KAFKA_BOOTSTRAP)\n")
        out.write("\nroutes needed:\n")
        for method, template, _b, label in S.REQUIRED_ROUTES + MASTER_ROUTES + S.OPTIONAL_ROUTES + MASTER_OPTIONAL_ROUTES:
            out.write("  %-4s %-48s %s\n" % (method, template, label))
        out.write("  POST %s  tools/call %s  (read only)\n" % (MCP_PATH, ", ".join(MCP_READ_TOOLS)))
        out.write("  GET  Data Services: Xref, MatchPlan, TransferReject (factory dataset); the master person table, its <dataset>_<table>_MDS RecordMetadata and Suspected (project's master dataspace)\n")
        return 1 if problems else 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


# ----------------------------------------------------------------------------------------------------------------------------------- the scenario

class MasterScenario(D.DistributionSteps, S.Scenario):
    CODE_PREFIX = "E2EMASTER"
    TITLE = "B2C master and matching scenario"
    REPORT_NAME = "b2c-master"
    PROJECT_LABEL = "B2C master and matching"
    STEP_NAMES = MASTER_STEPS
    KEEP_HINT = "left in place for a visual pass; delete it with --cleanup-project <code> or the wizard's Delete project"
    SIMULATE_FIRST = False
    VARIANT = "plain"
    AUTO_MERGE_ROUTE = "Data Services PUT MatchPlan/{id} autoMerge=true (the write of the wizard's \"Merge confirmed matches automatically\" toggle, ProposalReviews.setMatchPlanAutoMerge)"

    def __init__(self, *args, **kwargs):
        S.Scenario.__init__(self, *args, **kwargs)
        self.truth = None
        self.details = {"variant": self.VARIANT}
        self.report_data["details"] = self.details
        self.master_space = ""
        self.master_dataset = ""
        self.mst = None
        self.mds = None
        self.person_table = ""
        self.model = None
        self.plans = []
        self.master_rows = []
        self.meta_rows = []
        self.suspect_pairs = []
        self.person_plan = None
        self.auto_expected = False
        self.configure_message = ""
        self.init_distribution()

    def step_functions(self):
        return [(1, self.step1), (2, self.step2), (3, self.step3), (4, self.step4), (5, self.step5), (6, self.step6), (7, self.step7), (8, self.step8), (9, self.step9),
                (10, self.step10), (11, self.step11)]

    def required_routes(self):
        return S.REQUIRED_ROUTES + MASTER_ROUTES + (D.DIST_ROUTES if self.args.get("distribute") else [])

    def optional_routes(self):
        return S.OPTIONAL_ROUTES + MASTER_OPTIONAL_ROUTES + (D.DIST_OPTIONAL_ROUTES if self.args.get("distribute") else [])

    def step0(self):
        S.Scenario.step0(self)
        self.step0_distribution()

    # ------------------------------------------------------------------------------------------------------------------ --from-project / --from-step
    CAN_RESUME = True

    def resume(self, from_step):
        """Continues a project an earlier run left in place (lot F4, 2026-10-19): rebuilds what steps 1..from_step-1 leave in this object from the Factory's tables and the regenerated
        fixtures, then lets run() go on from step `from_step`. A project that is not in the state the step starts from is REFUSED (exit 2, nothing run) with what is missing."""
        resp, row = self.ds.get_row("Project", self.code)
        if resp.status != 200 or row is None:
            raise S.ResumeRefused("no project %s on this instance (GET Project: HTTP %d)" % (self.code, resp.status))
        self.project_row = row
        problems = self.load_state(from_step)
        if problems:
            raise S.ResumeRefused("project %s is not ready for step %d (%s): %s" % (self.code, from_step, self.STEP_NAMES[from_step], "; ".join(problems)))
        self.created = True
        self.details["resumed"] = {"project": self.code, "fromStep": from_step}
        self.rep.info("R", "project %s loaded for step %d (%s)" % (self.code, from_step, self.STEP_NAMES[from_step]), self.resume_summary(from_step))

    def resume_summary(self, from_step):
        parts = []
        if self.tables:
            parts.append("%d landing table(s)" % len(self.tables))
        if self.person is not None:
            parts.append("person entity %s" % L.text(self.person.get("name")))
        if self.stg is not None:
            parts.append("staging %s/%s" % (L.text(self.project_row.get("stagingDataspace")), L.text(self.project_row.get("stagingDataset"))))
        if self.mst is not None:
            parts.append("master %s/%s (%d %s row(s))" % (self.master_space, self.master_dataset, len(self.master_rows), self.person_table))
        if self.mds is not None:
            parts.append("metadataset %d row(s), %d suspected pair(s)" % (len(self.meta_rows), len(self.suspect_pairs)))
        return ", ".join(parts) or "project row only"

    def load_state(self, n):
        """The state steps 1..n-1 leave behind, read back; returns the list of reasons the project cannot start at step n (empty = ready). The reads are the ones the steps themselves make."""
        problems = []
        code = self.code
        # the fixtures are regenerated with the same seed (gen_b2c.py is deterministic: the selftest pins the bytes): the ground truth of the quality measures comes from them
        self.workdir = tempfile.mkdtemp(prefix="b2c-e2e-resume-")
        try:
            paths = S.generate_files(self.workdir)
            found, counts = S.check_files(paths)
            self.truth, summary = M.load_truth(os.path.join(self.workdir, "truth_b2c.json"))
            if found:
                problems.append("the regenerated fixtures are not the ones the scenario was written for: " + "; ".join(found))
            self.details["truth"] = dict(summary, usedSources=M.expected_clusters_summary(self.truth, TRUTH_APPS))
        except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as exc:
            problems.append("the fixtures could not be regenerated (%s)" % exc)
        jobs = self.jobs()
        if jobs and jobs.get("busy"):
            problems.append("a job is running on the project (wait for it, or cancel it): the state would be read halfway")
        # ---- steps 2 and 3: the sources and their landing tables
        resp, source_rows = self.ds.select("ProjectSource", "./project='%s'" % code)
        if n == 2:
            if source_rows:
                problems.append("step 2 uploads the three sample files and the project already has %d source(s): resume at step 3 or later" % len(source_rows))
            return problems
        if not self.sources_ready():
            problems.append("the three applications (%s) are not all there with their landing table and columns (%d source row(s) found): step 2 has not completed" % (", ".join(self.APPS), len(source_rows)))
            return problems
        for app, src in sorted(self.sources.items()):
            self.uploaded.append((L.text(src.get("connector")), L.text(src.get("catalogSource")), L.text(self.tables[app].get("tableName"))))
        for app, table in sorted(self.tables.items()):
            resp, cols = self.ds.select("LandingColumn", "./landingTable='%s'" % table["id"])
            self.columns[L.text(table["id"])] = cols
        if n >= 4:
            for app in self.APPS:
                resp, t = self.ds.get_row("LandingTable", self.tables[app]["id"])
                t = t or {}
                self.tables[app] = dict(self.tables[app], **t)
                if L.text(t.get("status")) != "LOADED" or L.to_int(t.get("lastLoadRows"), -1) != self.ROWS[app]:
                    problems.append("%s is not loaded (status %s, %d row(s) of %d): step 3 has not completed" % (app, L.text(t.get("status")), L.to_int(t.get("lastLoadRows"), -1), self.ROWS[app]))
        if n == 3 or problems:
            return problems
        # ---- steps 4 and 5: the entities, their feeds, the decisions
        resp, self.entities = self.ds.select("StagingEntity", "./project='%s'" % code)
        self.person = L.person_entity(self.entities)
        if n == 4:
            return problems
        if self.person is None:
            problems.append("no staging entity unifies the sources (%d entit(ies)): step 4 (proposal) has not completed" % len(self.entities))
            return problems
        resp, feeds = self.ds.select("TransferFeed", "./entity='%s'" % self.person["id"])
        self.person_feeds = [f for f in feeds if L.live(f)]
        fed = L.feed_tables(self.person_feeds)
        self.person_apps = set(app for app, t in self.tables.items() if L.text(t["id"]) in fed)
        if n <= 6:
            self.load_attributes()
        if n == 5:
            return problems
        live = [e for e in self.entities if L.text(e.get("status")) in ("ACCEPTED", "APPLIED")]
        if not live:
            problems.append("no staging entity is ACCEPTED or APPLIED: step 5 (decisions) has not completed")
            return problems
        if n == 6:
            return problems
        # ---- step 6: the staging dataset and its rows
        space, dataset = L.text(self.project_row.get("stagingDataspace")), L.text(self.project_row.get("stagingDataset"))
        if not space or not dataset:
            problems.append("the project has no staging dataset (Project.stagingDataspace / stagingDataset empty): step 6 (build and load) has not completed")
            return problems
        self.stg = client.Ds(self.admin, space, dataset)
        self.person_table = L.text(self.person.get("name"))
        resp, staged = self.read_table(self.stg, self.table_path(self.person))
        if resp.status != 200 or not staged:
            problems.append("the staging table of %s is empty or unreadable (HTTP %d, %d row(s)): step 6 has not completed" % (self.person_table, resp.status, len(staged)))
            return problems
        if n == 7:
            return problems
        # ---- step 7: the master
        self.master_space, self.master_dataset = L.text(self.project_row.get("masterDataspace")), L.text(self.project_row.get("masterDataset"))
        if not self.master_space or not self.master_dataset:
            problems.append("the project has no master dataset (Project.masterDataspace / masterDataset empty): step 7 (master build) has not completed")
            return problems
        self.mst = client.Ds(self.admin, self.master_space, self.master_dataset)
        resp, self.master_rows = self.read_table(self.mst, self.person_table)
        if resp.status != 200 or not self.master_rows:
            problems.append("the master table of %s is empty or unreadable (HTTP %d, %d row(s)): step 7 has not completed" % (self.person_table, resp.status, len(self.master_rows)))
            return problems
        self.details["masterCounts"] = self.load_master_counts()
        if n == 8:
            # a master that already went through a matching run holds the goldens the add-on created: step 7 counted the rows it BUILT, before them
            mds = client.Ds(self.admin, self.master_space, "%s_%s_MDS" % (self.master_dataset, self.person_table))
            r, rows = self.read_table(mds, "RecordMetadata")
            if r.status == 200:
                self.discount_goldens(rows)
            return problems
        # ---- step 8: the matching, configured and run
        self.read_plans()
        plan = self.person_plan
        if plan is None or L.text(plan.get("status")) != "APPLIED":
            problems.append("the person entity has no APPLIED matching plan (plan %s): step 8 (matching) has not completed" % (L.text((plan or {}).get("status")) or "none"))
            return problems
        leaves = M.plan_leaves(L.text(plan.get("requirements")))
        self.auto_expected = leaves.get("MATCH", 0) > 0 and L.truthy(plan.get("autoMerge"))
        self.mds = client.Ds(self.admin, self.master_space, "%s_%s_MDS" % (self.master_dataset, self.person_table))
        r, self.meta_rows = self.read_table(self.mds, "RecordMetadata")
        r2, suspects = self.read_table(self.mds, "Suspected")
        self.suspect_pairs = [(L.text(x.get("sourceId")), L.text(x.get("targetId"))) for x in suspects] if r2.status == 200 else []
        sourced = [x for x in self.master_rows if L.text(x.get("sourceSystem")) or L.text(x.get("sourceId"))]
        if r.status != 200 or len(self.meta_rows) < len(sourced):
            problems.append("the metadataset holds %d row(s) for %d master record(s) (HTTP %d): the matching run of step 8 has not completed" % (len(self.meta_rows), len(sourced), r.status))
            return problems
        self.discount_goldens(self.meta_rows)
        # step 11 compares the golden count with step 9's reading of the same metadataset: step 9 is skipped on a resume at 10 or 11, so its states are read here
        mapping = M.source_to_app(self.sources)
        records, _unknown, _goldens = M.master_records(self.master_rows, mapping, self.truth, M.auto_created_ids(self.meta_rows))
        self.details["quality"] = {"states": M.quality(records, self.meta_rows, self.suspect_pairs)["states"]} if records else {}
        # ---- step 10 creates the run's Kafka connection and a target; step 11 judges the distribution card
        if n >= 10:
            targets = self.targets()[1]
            connection = client.Ds(self.admin, S_CONNECTORS_SPACE, S_CONNECTORS_DATASET).get_row("Connector", "Kafka" + code)[1]
            if n == 10 and (targets or connection):
                problems.append("step 10 creates the connection Kafka%s and a distribution target, and the project already has %s: step 10 ran on it before (use --cleanup-project and start over, "
                                "or resume at step 11)" % (code, " and ".join(x for x in ("%d target(s)" % len(targets) if targets else "", "that connection" if connection else "") if x)))
            if n == 11 and targets:
                problems.append("the project has %d distribution target(s) of an earlier step 10 and step 11 judges the distribution card against the run's own step 10: run the whole scenario with --distribute" % len(targets))
        return problems

    def discount_goldens(self, meta_rows):
        """details.masterCounts[person table].master is what the master build wrote (step 7 counts it before the matching run); the table now also holds the goldens the add-on created
        (RecordMetadata.autoCreated, the DELETED ones included): they are taken off, so step 11 compares what it compares in a whole run."""
        entry = (self.details.get("masterCounts") or {}).get(self.person_table)
        if entry is not None:
            entry["master"] = len(self.master_rows) - len(M.auto_created_ids(meta_rows))

    def load_master_counts(self):
        """details.masterCounts as step 7 builds it: per active entity the staging rows, the master rows and the rows held back (an open MASTER reject), read the same way."""
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        active = [e for e in entities if L.text(e.get("status")) not in ("SUPERSEDED", "REJECTED", "PROPOSED")]
        resp, rejects = self.ds.select("TransferReject", "./project='%s'" % self.code)
        by_entity = {}
        for r in L.reject_select(rejects, stage="MASTER"):
            by_entity.setdefault(L.text(r.get("entity")), []).append(r)
        counts = {}
        for e in active:
            r1, staged = self.read_table(self.stg, self.table_path(e))
            r2, rows = self.read_table(self.mst, L.text(e.get("name")))
            if r2.status == 200:
                counts[L.text(e.get("name"))] = {"staging": len(staged), "master": len(rows), "heldBack": len(by_entity.get(L.text(e["id"]), []))}
        return counts

    # ------------------------------------------------------------------------------------------------------------------ 2. the files and the truth
    def make_files(self):
        paths = S.Scenario.make_files(self)
        try:
            self.truth, summary = M.load_truth(os.path.join(self.workdir, "truth_b2c.json"))
        except (OSError, ValueError, KeyError) as exc:
            self.rep.failed("2", "ground truth written with the files", "truth_b2c.json: %s" % exc)
            raise S.Stop()
        per_app = {}
        for (app, _key) in self.truth:
            per_app[app] = per_app.get(app, 0) + 1
        mismatch = [a for a in TRUTH_APPS if per_app.get(a) != self.ROWS[a]]
        expected = M.expected_clusters_summary(self.truth, TRUTH_APPS)
        self.details["truth"] = dict(summary, usedSources=expected)
        self.rep.check("2", "ground truth covers every source row (one person per row)", not mismatch, "%d rows, %d people; among the three sources %d true pairs (%d within a source, %d across)" % (
            summary["rows"], summary["persons"], expected["truePairs"], expected["pairsWithinASource"], expected["pairsAcrossSources"]),
            "truth rows per application %s, files %s" % (per_app, self.ROWS))
        if mismatch:
            raise S.Stop()
        return paths

    # ------------------------------------------------------------------------------------------------------------------ helpers
    def metric(self, step, name, status, detail=""):
        """A measure on the table: PASS / FAIL / SKIP with the value in the name, so the compact table shows it."""
        self.rep.add(step, "METRIC " + name, status, detail)

    def read_table(self, ds, table, xpath=None):
        resp, rows = ds.select(table, xpath, page_size=500, max_pages=200)
        return resp, rows

    def read_plans(self):
        plans = []
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        for e in entities:
            r, rows = self.ds.select("MatchPlan", "./entity='%s'" % L.text(e["id"]))
            for row in rows:
                plans.append(dict(row, _entityName=L.text(e.get("name")), _entityId=L.text(e["id"])))
        self.plans = plans
        mine = [p for p in plans if p["_entityId"] == L.text(self.person["id"]) and L.text(p.get("status")) not in ("REJECTED", "SUPERSEDED")]
        self.person_plan = mine[-1] if mine else None
        return plans

    def child_entities(self):
        """{entity name: parent entity name} of the entities that hold an accepted reference attribute to ANOTHER entity of the project (an address, a phone, a purchase of a
        person): lot M2, such an entity follows its parent and has no matching plan of its own."""
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        names = dict((L.text(e["id"]), L.text(e.get("name"))) for e in entities)
        live = [e for e in entities if L.text(e.get("status")) in ("ACCEPTED", "APPLIED")]
        out = {}
        for e in live:
            eid = L.text(e["id"])
            r, attributes = self.ds.select("StagingAttribute", "./entity='%s'" % eid)
            for a in attributes:
                ref = L.text(a.get("referenceEntity"))
                if ref and ref != eid and ref in names and L.text(a.get("status")) in ("ACCEPTED", "APPLIED"):
                    out[names[eid]] = names[ref]
                    break
        return out

    def mcp(self, tool, table, argument="table"):
        """tools/call on the match-merge server (read tools only), stateless like tools/deliver.sh does; (doc, error). A soft miss {success:false, code} comes back as the doc."""
        if tool not in MCP_READ_TOOLS:
            raise client.SafetyError("%s is not one of the read tools this scenario calls" % tool)
        self.guard.allow_path(MCP_PATH)

        def call(args):
            body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": args}}
            r = self.admin.request("POST", MCP_PATH, body, headers={"Accept": "application/json, text/event-stream"})
            if r.status != 200:
                return None, "HTTP %d %s" % (r.status, S.clip(r.text, 200))
            return M.mcp_text_result(r.text)
        doc, err = call({argument: table})
        if isinstance(doc, dict) and doc.get("code") == "AMBIGUOUS_TABLE" and self.master_space:
            doc, err = call({argument: table, "dataModel": "urn:ebx:publication:" + self.master_space})
        return doc, err

    def plan_text(self, plan):
        return "plan %s status %s origin %s confidence %s autoMerge %s" % (L.text(plan.get("id")), L.text(plan.get("status")), L.text(plan.get("origin")),
                                                                         L.text(plan.get("confidence")), L.text(plan.get("autoMerge")))

    # ------------------------------------------------------------------------------------------------------------------ 7. master
    def step7(self):
        self.start_job("7", "master build (one click: tables, copy, matching hand-over)", "/projects/%s/master/build" % self.code, self.timeout("MASTER", 1800), "the master build")
        resp, project = self.ds.get_row("Project", self.code)
        project = project or {}
        self.master_space, self.master_dataset = L.text(project.get("masterDataspace")), L.text(project.get("masterDataset"))
        built = bool(self.master_space and self.master_dataset)
        self.rep.check("7", "master model and dataset built", built, "%s / %s" % (self.master_space, self.master_dataset), "Project.masterDataspace / masterDataset are empty")
        if not built:
            raise S.Stop()
        self.mst = client.Ds(self.admin, self.master_space, self.master_dataset)
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        active = [e for e in entities if L.text(e.get("status")) not in ("SUPERSEDED", "REJECTED", "PROPOSED")]
        resp, rejects = self.ds.select("TransferReject", "./project='%s'" % self.code)
        open_master = L.reject_select(rejects, stage="MASTER")
        by_entity = {}
        for r in open_master:
            by_entity.setdefault(L.text(r.get("entity")), []).append(r)
        person_id = L.text(self.person["id"])
        self.person_table = L.text(self.person.get("name"))
        counts = {}
        for e in active:
            name, eid = L.text(e.get("name")), L.text(e["id"])
            r1, staged = self.read_table(self.stg, self.table_path(e))
            r2, rows = self.read_table(self.mst, name)
            if r2.status != 200:
                if eid == person_id:
                    self.rep.failed("7", "%s: master table readable" % name, "HTTP %d %s" % (r2.status, S.clip(r2.error_text())))
                    raise S.Stop()
                self.rep.info("7", "%s: no master table" % name, "kept in staging only (a member of another object, or not promoted)")
                continue
            held = len(by_entity.get(eid, []))
            ok = r1.status == 200 and len(rows) == len(staged) - held
            counts[name] = {"staging": len(staged), "master": len(rows), "heldBack": held}
            self.rep.check("7", "%s: master rows = staging rows minus held back" % name, ok, "%d = %d - %d" % (len(rows), len(staged), held),
                           "%d master row(s), %d staging row(s), %d held back" % (len(rows), len(staged), held))
            if eid == person_id:
                self.master_rows = rows
        self.details["masterCounts"] = counts
        if not self.master_rows:
            self.rep.failed("7", "the person entity has master rows", "the master table of %s is empty" % self.person_table)
            raise S.Stop()
        reasons = L.tally_by(open_master, "reasonCode")
        self.rep.check("7", "0 open MASTER rejects", not open_master, "none", "%d open master reject(s) by reason code: %s" % (len(open_master), json.dumps(reasons, sort_keys=True)[:300]))
        if open_master:
            self.details["masterRejects"] = reasons
            causes = self.get("/projects/%s/rejects/causes?stage=MASTER" % self.code)
            if causes.status == 200 and isinstance(causes.json(), dict):
                self.rep.info("7", "why the rows were held back", S.clip(str(causes.json().get("sentence", "")), 300))
        # lineage: one Xref row per staged person row, its masterKey the id of the master record it became
        resp, xrefs = self.ds.select("Xref", "./project='%s' and ./stagingTable='%s'" % (self.code, self.table_path(self.person)), page_size=500, max_pages=200)
        ids = set(L.text(r.get("id")) for r in self.master_rows)
        sourced = [r for r in self.master_rows if L.text(r.get("sourceSystem")) or L.text(r.get("sourceId"))]
        keyed = [x for x in xrefs if L.text(x.get("masterKey"))]
        dangling = [x for x in keyed if L.text(x.get("masterKey")) not in ids]
        self.rep.check("7", "lineage: an Xref row per staged person row, each pointing to its master record", resp.status == 200 and bool(xrefs) and len(keyed) == len(sourced) and not dangling,
                       "%d Xref rows, %d with a master key = %d master records with a source" % (len(xrefs), len(keyed), len(sourced)),
                       "HTTP %d, %d Xref row(s), %d with a masterKey, %d master record(s) with a source, %d masterKey(s) pointing to no master record" % (
                           resp.status, len(xrefs), len(keyed), len(sourced), len(dangling)))

    # ------------------------------------------------------------------------------------------------------------------ 8. matching
    def step8(self):
        job = self.start_job("8", "matching plans proposed (rules, then the language model as configured)", "/projects/%s/matching/propose?useLlm=true" % self.code,
                             self.timeout("MATCH_PROPOSE", 1500), "the matching proposal")
        message = L.text(job.get("message"))
        plans = self.read_plans()
        live = [p for p in plans if L.text(p.get("status")) not in ("REJECTED", "SUPERSEDED")]
        self.details["plans"] = [{"entity": p["_entityName"], "status": L.text(p.get("status")), "origin": L.text(p.get("origin")), "confidence": L.text(p.get("confidence")),
                                  "autoMerge": L.text(p.get("autoMerge")), "leaves": M.plan_leaves(L.text(p.get("requirements")))} for p in plans]
        if self.person_plan is None:
            self.rep.failed("8", "a matching plan was proposed for the person entity (%s)" % self.person_table, "%d live plan(s) for %s; the job said: %s" % (
                len(live), ", ".join(p["_entityName"] for p in live) or "no entity", S.clip(message, 300)))
            raise S.Stop()
        self.rep.passed("8", "a matching plan was proposed for the person entity (%s)" % self.person_table, self.plan_text(self.person_plan))
        # lot M2 (docs/MATCHING.md section 56): a child entity follows its parent - it gets no plan of its own, so the configuration and the run never meet the add-on defect on a
        # business object member table (NullPointerException in CascadeMergingService, 2026-10-17)
        children = self.child_entities()
        if children:
            own = [p["_entityName"] for p in live if p["_entityName"] in children]
            self.rep.check("8", "child entities follow their parent: no matching plan of their own (%s)" % ", ".join("%s -> %s" % kv for kv in sorted(children.items())), not own,
                           "none of them has a plan", "plan(s) proposed for: %s" % ", ".join(own))
            self.details["followsParent"] = children
        else:
            self.rep.info("8", "child entities", "the model has none: nothing follows a parent")
        leaves = M.plan_leaves(L.text(self.person_plan.get("requirements")))
        total = sum(leaves.values())
        auto = leaves.get("MATCH", 0)
        self.auto_expected = auto > 0 and L.truthy(self.person_plan.get("autoMerge"))
        detail = "%d of %d leaves are MATCH, %d SUSPECTED, %d NOT_MATCHED; autoMerge=%s" % (auto, total, leaves.get("SUSPECTED", 0), leaves.get("NOT_MATCHED", 0), L.text(self.person_plan.get("autoMerge")))
        self.metric("8", "auto-match leaves in the proposed plan: %d of %d (target >= 1)" % (auto, total), "PASS" if auto >= 1 else "SKIP",
                    detail + ("" if auto else " - no path ends in an automatic match: every duplicate goes to a steward, the merged measure (a) can only be empty"))
        self.details["planLeaves"] = leaves
        self.accept_plans()
        if self.args.get("auto_merge"):
            self.enable_auto_merge(auto)
        if self.SIMULATE_FIRST:
            self.simulate_plan()
        self.configure()
        self.activation()
        self.run_matching()
        self.read_results()

    def accept_plans(self):
        lines = [l for l in self.review("PROPOSED")["lines"] if l["nature"] == "MATCH"]
        accepted, refused = 0, []
        for i in range(0, len(lines), 100):
            batch = [{"nature": "MATCH", "id": l["id"], "status": "ACCEPTED"} for l in lines[i:i + 100]]
            r = self.post("/projects/%s/staging/decisions" % self.code, {"decisions": batch})
            data = r.json()
            if r.status != 200 or not isinstance(data, dict):
                self.rep.failed("8", "matching plans accepted", "HTTP %d %s" % (r.status, S.clip(r.error_text())))
                raise S.Stop()
            for res in data.get("results", []):
                if res.get("ok"):
                    accepted += 1
                else:
                    refused.append(S.clip(str(res.get("reason")), 120))
        self.read_plans()
        status = L.text((self.person_plan or {}).get("status"))
        self.rep.check("8", "the matching plan(s) accepted (%d line(s))" % len(lines), status in ("ACCEPTED", "APPLIED") and not refused, "%d accepted, person plan %s" % (accepted, status),
                       "person plan %s; refused: %s" % (status, "; ".join(refused)))
        if status not in ("ACCEPTED", "APPLIED"):
            raise S.Stop()

    def enable_auto_merge(self, match_leaves):
        """Option --auto-merge: the wizard's "Merge confirmed matches automatically" checkbox on the accepted person plan. That checkbox posts to a user service that calls
        ProposalReviews.setMatchPlanAutoMerge, i.e. ONE field written on the plan row (autoMerge) while the plan is PROPOSED or ACCEPTED, nothing else touched; there is no REST route for it, so this
        writes the same field through Data Services on the Factory dataset (the plan row was read first from a query filtered on this project: the safety guard allows exactly that record).
        Configure then picks the changed value up (MatchingConfigurator compares the stored plan and rewrites the policy)."""
        plan = self.person_plan or {}
        status = L.text(plan.get("status"))
        if status not in ("PROPOSED", "ACCEPTED"):
            self.rep.failed("8", "automatic merge turned on for the person plan", "the plan is %s: the toggle is only editable on a PROPOSED or ACCEPTED plan" % status)
            raise S.Stop()
        r = self.guarded_update("MatchPlan", L.text(plan["id"]), {"autoMerge": True})
        self.read_plans()
        now = L.truthy((self.person_plan or {}).get("autoMerge"))
        self.rep.check("8", "automatic merge turned on for the person plan (MatchPlan.autoMerge = true, the write the wizard's toggle makes)", r.status in (200, 204) and now,
                       "HTTP %d; %s" % (r.status, self.plan_text(self.person_plan)), "HTTP %d %s; autoMerge now %s" % (r.status, S.clip(r.error_text()), L.text((self.person_plan or {}).get("autoMerge"))))
        if not (r.status in (200, 204) and now):
            raise S.Stop()
        self.auto_expected = match_leaves > 0
        self.details["autoMerge"] = {"requested": True, "route": self.AUTO_MERGE_ROUTE, "matchLeaves": match_leaves, "plan": L.text(plan["id"])}
        if not match_leaves:
            self.rep.info("8", "automatic merge is on but the plan has no MATCH leaf", "every path ends at a steward: nothing is expected to merge by itself, the merged-only recall stays unjudged")

    def simulate_plan(self):
        """Variant simulate-first: the card's Simulate button on the ACCEPTED plan, as a person does before Configure."""
        r = self.post("/projects/%s/matching/simulate-plan?plan=%s" % (self.code, L.text(self.person_plan["id"])), slow=True)
        data = r.json()
        ok = r.status == 200 and isinstance(data, dict) and not data.get("failure") and data.get("onPreviewPolicy") is True
        self.rep.check("8", "simulation of the accepted plan ran (the preview policy is written)", ok,
                       "%s pivot(s), %s pair(s), verdicts %s, on preview policy %s (%s)" % (data.get("pivots"), data.get("pairs"), json.dumps(data.get("verdicts")), data.get("onPreviewPolicy"), data.get("policyCode"))
                       if ok else "", "HTTP %d %s%s" % (r.status, S.clip((data or {}).get("failure") if isinstance(data, dict) and data.get("failure") else r.error_text(), 300),
                                                    "" if not isinstance(data, dict) or data.get("onPreviewPolicy") is True else " (the simulation did not run on a preview policy: the variant tests nothing)"))
        self.details["simulation"] = data if isinstance(data, dict) else {}

    def configure(self):
        label = "configure after a simulation" if self.SIMULATE_FIRST else "configure without a simulation"
        job = self.start_job("8", label, "/projects/%s/matching/configure" % self.code, self.timeout("MATCH_CONFIGURE", 900), "the matching configuration", expect_done=False)
        self.configure_message = L.text(job.get("message"))
        self.read_plans()
        plan = self.person_plan or {}
        applied = L.text(plan.get("status")) == "APPLIED"
        self.rep.check("8", "%s: the person entity is configured (plan APPLIED)" % label, applied, "%s; job %s: %s" % (self.plan_text(plan), job.get("status"), S.clip(L.text(job.get("message")), 160)),
                       "job %s: %s | %s | plan message: %s" % (job.get("status"), S.clip(L.text(job.get("message")), 300), self.plan_text(plan), S.clip(L.text(plan.get("message")), 300)))
        every = [p for p in self.plans if L.text(p.get("status")) in ("ACCEPTED", "PROPOSED")]
        self.rep.check("8", "%s: the configure job ended DONE and no accepted plan was left unconfigured" % label, job.get("status") == "DONE" and not [p for p in every if L.text(p.get("status")) == "ACCEPTED"],
                       "job DONE", "job %s: %s | still ACCEPTED, not configured: %s" % (job.get("status"), S.clip(L.text(job.get("message")), 300),
                                                                                        ", ".join(p["_entityName"] for p in every if L.text(p.get("status")) == "ACCEPTED") or "none"))
        if self.args.get("auto_merge") and applied:
            self.rep.check("8", "%s: the applied plan still has automatic merge on" % label, L.truthy(plan.get("autoMerge")), "autoMerge=true", "autoMerge=%s on the applied plan: Configure lost the setting" % L.text(plan.get("autoMerge")))
        if not applied:
            raise S.Stop()

    def activation(self):
        table = "/root/" + self.person_table

        def fetch():
            doc, err = self.mcp("get_matching_activation_status", table)
            return doc if isinstance(doc, dict) else {"error": err}

        def ready(doc):
            data = doc.get("data") or {}
            return doc.get("success") is True and data.get("state") == "ENABLED" and data.get("metadatasetExists") is True
        try:
            doc = L.poll(fetch, ready, self.timeout("ACTIVATION", 120), self.interval(), "matching to be ENABLED with its metadataset")
            self.rep.passed("8", "matching activation: state ENABLED and metadatasetExists true", json.dumps(doc.get("data"), sort_keys=True))
        except L.Timeout as exc:
            last = fetch()
            self.rep.failed("8", "matching activation: state ENABLED and metadatasetExists true", "%s; last answer: %s (state ENABLED with metadatasetExists false is the case of CLAUDE.md section 8)" % (
                exc, S.clip(json.dumps(last), 300)))
            raise S.Stop()
        cfg, err = self.mcp("get_matching_config", table)
        codes = M.policy_codes(cfg) if isinstance(cfg, dict) and cfg.get("success") is not False else []
        previews = M.preview_codes(codes)
        real = [c for c in codes if c not in previews]
        self.rep.check("8", "a matching policy exists on the person table", bool(real), "policies: %s" % ", ".join(real),
                       "get_matching_config: %s" % S.clip(json.dumps(cfg) if cfg else err, 300))
        # lot M2: a preview that cannot be removed (the person table is the main table of a business object: no tool removes one policy there) must at least be REPORTED by the
        # configure job, with the reason - an unreported leftover is the defect
        unreported = [c for c in previews if c not in self.configure_message]
        if previews and not unreported:
            self.rep.info("8", "preview policy left on the table and reported by the configure job", "%s (the job says: %s)" % (", ".join(previews), S.clip(self.configure_message, 240)))
        self.rep.check("8", "no preview policy is left on the table after the configuration", not unreported,
                       "none" if not previews else "left but named by the configure job", "left behind and not reported by the configure job: %s" % ", ".join(unreported))
        self.details["policies"] = codes
        self.check_members(table)

    def check_members(self, table):
        """Lot M4 (2026-10-18, docs/MATCHING.md section 59): the main table of a business object is run only when EVERY member table holds a default matching policy and a merge policy -
        the add-on refuses the run otherwise ("The default matching policy is not defined" for /root/AdresseClient, 2026-10-18). The configure writes inert ones on the members."""
        doc, err = self.mcp("get_business_object_config", table, "businessObject")
        if not isinstance(doc, dict) or doc.get("success") is False:
            code = doc.get("code") if isinstance(doc, dict) else err
            self.rep.info("8", "business object of the person table", "none (%s): nothing to check on member tables" % code)
            return
        data = doc.get("data") or {}
        members = [m for m in (data.get("members") or []) if isinstance(m, dict) and m.get("table") != table]
        lacking = []
        for m in members:
            matching = [L.text(p.get("processCode")) for p in (m.get("matchingPolicies") or []) if isinstance(p, dict)]
            merge = [p for p in (m.get("mergePolicies") or []) if isinstance(p, dict)]
            default = L.text((m.get("tableSettings") or {}).get("defaultMatchingPolicy"))
            if not matching or default not in matching or not merge:
                lacking.append("%s (matching %s, default %s, merge %d)" % (m.get("table"), ",".join(matching) or "none", default or "none", len(merge)))
        self.details["businessObjectMembers"] = [m.get("table") for m in members]
        if not members:
            self.rep.info("8", "business object of the person table", "declared, no member table")
            return
        self.rep.check("8", "every member table of the business object holds a default matching policy and a merge policy (%s)" % ", ".join(m.get("table") for m in members), not lacking,
                       "all %d member(s) complete" % len(members), "a run of %s is refused while a member lacks them: %s" % (table, "; ".join(lacking)))
        # lot M8 (2026-10-18 02:57, project E2EMASTER20261018005227, PipelineStep 2311): the add-on also refuses the run while a member's merge policy is in another MODE than the main
        # table's ("Ensure the merge policies use the same 'Mode'": Adresse DISABLED, Client ONLY_DUPLICATES). The configure writes the member's policy in the main table's mode and
        # aligns an older one with set_merge_mode.
        main_modes = [L.text(p.get("mode")) for m in (data.get("members") or []) if isinstance(m, dict) and m.get("table") == table
                      for p in (m.get("mergePolicies") or []) if isinstance(p, dict) and p.get("mode")]
        if not main_modes:
            self.rep.info("8", "merge mode of the main table", "not given by get_business_object_config: the members' modes are not compared")
            return
        main_mode = main_modes[0]
        other = ["%s: %s %s" % (m.get("table"), L.text(p.get("code")), L.text(p.get("mode"))) for m in members for p in (m.get("mergePolicies") or [])
                 if isinstance(p, dict) and p.get("mode") and L.text(p.get("mode")) != main_mode]
        self.rep.check("8", "every member merge policy carries the mode of the main table's merge policy (%s)" % main_mode, not other,
                       "all %d member(s) in mode %s" % (len(members), main_mode), "a run of %s is refused while a member's merge policy is in another mode: %s" % (table, "; ".join(other)))

    def run_matching(self):
        scope = self.env.get("E2E_MATCH_SCOPE", "all")
        t0 = time.time()
        job = self.start_job("8", "matching run (scope %s)" % scope, "/projects/%s/matching/run?scope=%s" % (self.code, scope), self.timeout("MATCH_RUN", 1800), "the matching run", expect_done=False)
        self.details["matchingSeconds"] = int(time.time() - t0)
        self.rep.check("8", "matching run ended DONE", job.get("status") == "DONE", "%d s: %s" % (int(time.time() - t0), S.clip(L.text(job.get("message")), 200)),
                       "%s: %s" % (job.get("status"), S.clip(L.text(job.get("message")), 400)))
        if job.get("status") not in ("DONE", "DONE_WITH_FAILURES"):
            raise S.Stop()
        # lot M4 (2026-10-18): a child entity is a member of the person's business object; it is never run on its own - the job says it follows its parent (and the run did not fail on it)
        children = self.child_entities()
        if children:
            self.rep.check("8", "the run leaves the member tables to the run of their parent (%s)" % ", ".join(sorted(children)), "follow" in L.text(job.get("message")),
                           S.clip(L.text(job.get("message")), 200), "the job does not say that the child objects follow the parent: %s" % S.clip(L.text(job.get("message")), 300))
        act = self.get("/projects/%s/matching/activity" % self.code)
        if act.status == 200 and isinstance(act.json(), dict):
            self.details["activity"] = act.json()

    def read_results(self):
        name = "%s_%s_MDS" % (self.master_dataset, self.person_table)
        self.mds = client.Ds(self.admin, self.master_space, name)
        resp, master = self.read_table(self.mst, self.person_table)
        # lot M1 (2026-10-17): the run must leave the source of every record the master build wrote as it was (live: the add-on's constant survivor turned all 534 into MDM)
        before = dict((L.text(r.get("id")), L.text(r.get("sourceSystem"))) for r in (getattr(self, "master_rows", None) or []) if L.text(r.get("id")))
        changed = [(L.text(r.get("id")), before[L.text(r.get("id"))], L.text(r.get("sourceSystem"))) for r in master if L.text(r.get("id")) in before
                   and before[L.text(r.get("id"))] != L.text(r.get("sourceSystem"))]
        if before:
            self.rep.check("8", "the matching run left the source of every master record untouched", resp.status == 200 and not changed,
                           "%d record(s) read before the run, none changed its sourceSystem" % len(before),
                           "HTTP %d, %d of %d record(s) changed their sourceSystem during the run, e.g. %s" % (resp.status, len(changed), len(before), changed[:3]))
        self.master_rows = master
        sourced = [r for r in master if L.text(r.get("sourceSystem")) or L.text(r.get("sourceId"))]
        want = len(sourced)

        def fetch():
            r, rows = self.read_table(self.mds, "RecordMetadata")
            return r, rows
        try:
            r, rows = L.poll(fetch, lambda v: v[0].status == 200 and len(v[1]) >= want, self.timeout("METADATA", 90), self.interval(), "the metadataset to hold a row per master record")
        except L.Timeout as exc:
            r, rows = fetch()
            self.rep.failed("8", "the metadataset %s holds a row per master record" % name, "%s; HTTP %d, %d row(s) for %d record(s)" % (exc, r.status, len(rows), want))
            raise S.Stop()
        r2, suspects = self.read_table(self.mds, "Suspected")
        self.meta_rows = rows
        self.suspect_pairs = [(L.text(s.get("sourceId")), L.text(s.get("targetId"))) for s in suspects] if r2.status == 200 else []
        states = {}
        for m in rows:
            states[M.STATE_NAMES.get(M.state_of(m), "?")] = states.get(M.STATE_NAMES.get(M.state_of(m), "?"), 0) + 1
        self.rep.check("8", "the metadataset %s holds a row per master record, Suspected is readable" % name, r2.status == 200,
                       "%d row(s) (%s); %d suspected pair(s)" % (len(rows), ", ".join("%s %d" % kv for kv in sorted(states.items())), len(self.suspect_pairs)),
                       "Suspected: HTTP %d" % r2.status)
        self.details["metadataset"] = {"name": name, "states": states, "suspectedPairs": len(self.suspect_pairs), "masterRows": len(master)}

    # ------------------------------------------------------------------------------------------------------------------ 9. quality
    def step9(self):
        mapping = M.source_to_app(self.sources)
        records, unknown, goldens = M.master_records(self.master_rows, mapping, self.truth, M.auto_created_ids(self.meta_rows))
        # lot M11 (2026-10-18): the golden records the add-on created are the merge target, not a person's record - they carry the source of the policy's autoCreateNewGoldenSource
        # ("MDM") and a key of their own, so they are told apart by RecordMetadata.autoCreated and counted apart, not judged against the truth
        self.rep.check("9", "every master record maps back to a source row of the files", bool(records) and not unknown,
                       "%d record(s) mapped to a person" % len(records),
                       "%d record(s) mapped, %d with a source that is not in the truth, e.g. %s" % (len(records), len(unknown), unknown[:3]))
        if goldens:
            self.rep.info("9", "%d golden records created by the add-on, not source rows" % len(goldens),
                          "RecordMetadata.autoCreated: the merge targets, left out of the check above and of the pairs (a golden's cluster is its MERGED members)")
        if not records:
            raise S.Stop()
        q = M.quality(records, self.meta_rows, self.suspect_pairs)
        a, b = q["merged"], q["withSuspects"]
        expected = M.expected_clusters_summary(self.truth, TRUTH_APPS)
        persons = a["personsPresent"]
        by_id = dict((L.text(r.get("id")), r) for r in self.master_rows)
        pairs, pairs_total = M.false_pairs(records, self.meta_rows, self.suspect_pairs, by_id, q["labelsMerged"], 20)
        self.details["quality"] = {"records": len(records), "truePairs": a["pairsTrue"], "personsPresent": persons, "autoCreatedGoldens": q["autoCreatedGoldens"], "mergedRecords": q["mergedRecords"],
                                   "states": q["states"], "autoMerge": bool(self.args.get("auto_merge")),
                                   "merged": dict((k, v) for k, v in a.items() if k != "falseClusterLabels"), "withSuspects": dict((k, v) for k, v in b.items() if k != "falseClusterLabels"),
                                   # lot M10b: the two readings of "a cluster that mixes people", kept apart. falseMerges = built from MERGED pairs only (the defect); suspectChains = built from merged + suspect
                                   # pairs (a few false suspect pairs chain two people: judged by the precision of the suspects, never a failure of its own)
                                   "falseMerges": {"clusters": a["falseMergeClusters"], "falsePairs": a["falsePairs"], "largestCluster": a["largestMixedCluster"]},
                                   "suspectChains": {"clusters": b["falseMergeClusters"], "largestCluster": b["largestMixedCluster"], "falsePairs": b["falsePairs"],
                                                     "clustersTotal": b["clusters"], "singletons": b["singletons"], "people": persons},
                                   "falsePairsTotal": pairs_total, "falsePairs": pairs,
                                   "falseMergeExamples": M.false_merge_examples(records, q["labelsMerged"], a["falseClusterLabels"]),
                                   "suspectChainExamples": M.false_merge_examples(records, q["labelsWithSuspects"], b["falseClusterLabels"], limit=5), "expectedFromTruth": expected}
        self.rep.info("9", "the truth among the records present", "%d records of %d people, %d true pairs (%d within a source, %d across sources)" % (
            len(records), persons, a["pairsTrue"], expected["pairsWithinASource"], expected["pairsAcrossSources"]))
        # false merges: the worst defect - clusters built from MERGED pairs only
        self.metric("9", "false merges (merged groups holding 2+ people) = %d (target 0)" % a["falseMergeClusters"], "PASS" if a["falseMergeClusters"] <= M.FALSE_MERGES_MAX else "FAIL",
                    "%d merged group(s) mix people, %d false pair(s); examples in the report" % (a["falseMergeClusters"], a["falsePairs"]))
        # (a) merged only: judged only when the plan can merge by itself
        for name, value, judge in (("precision", a["precision"], M.judge_precision(a["precision"], a["pairsFound"])), ("recall", a["recall"], M.judge_recall(a["recall"]))):
            status, why = judge
            if not self.auto_expected and name == "recall":
                status, why = "SKIP", "not judged: the accepted plan has no automatic match path (or autoMerge is off), nothing is expected to merge by itself"
            self.metric("9", "%s, merged only = %s (floor %.2f)" % (name, M.fmt(value), M.PRECISION_FLOOR if name == "precision" else M.RECALL_FLOOR), status, "%s; %d pair(s) found together, %d right, %d true" % (
                why, a["pairsFound"], a["pairsRight"], a["pairsTrue"]))
        # (b) merged + suspects: the realistic measure while every path ends at a steward
        for name, value, judge in (("precision", b["precision"], M.judge_precision(b["precision"], b["pairsFound"])), ("recall", b["recall"], M.judge_recall(b["recall"]))):
            status, why = judge
            self.metric("9", "%s, merged + suspects = %s (floor %.2f)" % (name, M.fmt(value), M.PRECISION_FLOOR if name == "precision" else M.RECALL_FLOOR), status, "%s; %d pair(s) found together, %d right, %d true" % (
                why, b["pairsFound"], b["pairsRight"], b["pairsTrue"]))
        self.rep.info("9", "F1", "merged only %s, merged + suspects %s" % (M.fmt(a["f1"]), M.fmt(b["f1"])))
        # lot M10b: a cluster of merged + suspect pairs that mixes people is a CHAIN of suspects, not a false merge: reported, never a failure (the precision above judges the suspects)
        self.rep.add("9", "METRIC suspect chains = %d (clusters of merged + suspects mixing 2+ people; largest %d record(s); not judged)" % (b["falseMergeClusters"], b["largestMixedCluster"]), "INFO",
                     "%d clusters for %d people, %d alone, %d false pair(s) among the pairs found together; merged only: %d clusters, %d alone, largest %d" % (
                         b["clusters"], persons, b["singletons"], b["falsePairs"], a["clusters"], a["singletons"], a["largestCluster"]))
        # the golden records the add-on created (RecordMetadata autoCreated, state MERGED): the figure that shows an automatic merge happened
        auto = bool(self.args.get("auto_merge"))
        self.rep.add("9", "METRIC golden records created by the add-on = %d (autoCreated); records merged into a golden = %d (state MERGED)%s" % (
            q["autoCreatedGoldens"], q["mergedRecords"], "" if auto else "; automatic merge was not turned on"), "INFO",
                     "metadata states %s; %d merged cluster(s) of 2+ records (largest %d)" % (", ".join("%s %d" % kv for kv in sorted(q["states"].items())),
                                                                                              a["clusters"] - a["singletons"], a["largestCluster"]))
        # the false pairs, for a person to characterise from the log
        self.out.write("note  false pairs: %d (both records, then the business fields they hold equal)%s\n" % (pairs_total, "" if pairs_total <= len(pairs) else ", the first %d listed" % len(pairs)))
        for p in pairs:
            self.out.write("note    %s\n" % M.describe_false_pair(p))
        self.rep.info("9", "false pairs", "%d (%d listed above and in the report: details.quality.falsePairs)" % (pairs_total, len(pairs)))
        self.golden_check(records, q)

    def golden_check(self, records, q):
        """Three sampled people: the golden's survived values next to the members' values, printed for the eye (survivorship is not judged here)."""
        columns = list(self.master_rows[0].keys()) if self.master_rows else []
        fields = M.pick_fields(columns)
        by_id = dict((L.text(r.get("id")), r) for r in self.master_rows)
        meta_by_id = dict((L.text(m.get("functionalId")), m) for m in self.meta_rows)
        shown = []
        for person, members in M.sample_persons(records, 3):
            ids = [rid for _app, rid in members]
            self.out.write("note  golden check - person %d: %d record(s) from %s\n" % (person, len(ids), ", ".join(sorted(set(a for a, _r in members)))))
            golden = M.golden_of(ids, meta_by_id, q["labelsMerged"])
            line = {"person": person, "records": [], "golden": None}
            if golden and golden in by_id:
                self.out.write("note    golden %-6s %s\n" % (golden, M.describe_row(by_id[golden], fields)))
                line["golden"] = {"id": golden, "values": M.describe_row(by_id[golden], fields)}
            else:
                self.out.write("note    no golden: these records were not merged (%s)\n" % ("they are suspects of each other" if len(set(q["labelsWithSuspects"][r] for r in ids)) < len(ids) else "no duplicate was found"))
            for app, rid in members:
                self.out.write("note    %-8s %-6s %s\n" % (app, rid, M.describe_row(by_id.get(rid), fields)))
                line["records"].append({"app": app, "id": rid, "values": M.describe_row(by_id.get(rid), fields)})
            shown.append(line)
        self.details["goldenCheck"] = shown
        self.rep.info("9", "golden record check", "%d sampled people printed above and in the report" % len(shown))

    # ------------------------------------------------------------------------------------------------------------------ the end
    def epilogue(self):
        if self.args.get("cleanup") or self.args.get("cleanup_project") or not self.created:
            return
        space, dataset = self.factory_space, self.factory_dataset
        self.out.write("\nPROJECT LEFT IN PLACE for a visual pass: %s (variant %s)\n" % (self.code, self.VARIANT))
        if space and dataset:
            self.out.write("  wizard, step 5 (master):   %s\n" % L.wizard_url(self.base, space, dataset, self.code, 5))
            self.out.write("  wizard, step 6 (matching): %s\n" % L.wizard_url(self.base, space, dataset, self.code, 6))
            if self.dist_ran:
                self.out.write("  wizard, step 7 (distribution): %s\n" % L.wizard_url(self.base, space, dataset, self.code, 7))
            self.out.write("  wizard, step 8 (summary):  %s\n" % L.wizard_url(self.base, space, dataset, self.code, 8))
            self.out.write("  transfer-reject review:    %s\n" % L.rejects_url(self.base, space, dataset, self.code))
        self.out.write("  delete it (and its connections) later: tools/e2e/b2c_master.sh --cleanup-project %s\n" % self.code)


class SimulateFirstScenario(MasterScenario):
    """The second variant: Simulate the accepted plan, then Configure."""
    SIMULATE_FIRST = True
    VARIANT = "simulate-first"
    REPORT_NAME = "b2c-master-simulate-first"


# ----------------------------------------------------------------------------------------------------------------------------------- command line

def main(argv, env, out=None):
    out = out or sys.stdout
    argv = list(argv)
    variants = None
    rest = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--variants" and i + 1 < len(argv):
            variants = argv[i + 1]
            i += 1
        elif a == "--simulate-first":
            variants = "simulate-first"
        else:
            rest.append(a)
        i += 1
    if variants is not None and variants not in ("plain", "simulate-first", "both"):
        sys.stderr.write("--variants takes plain, simulate-first or both\n")
        return 2
    if "--from-project" in rest:
        # a resumed run continues ONE existing project: one variant (plain unless --variants simulate-first is given), never two projects
        if variants == "both":
            sys.stderr.write("--from-project continues one project, so one variant: --variants plain (the default) or simulate-first\n")
            return 2
        variants = variants or "plain"
    variants = variants or "both"
    offline = any(a in ("--selftest", "--dry-run", "-h", "--help", "--cleanup-project") for a in rest)
    kwargs = dict(max_step=11, dry=dry_run, doc=__doc__, flags={"--auto-merge": "auto_merge", "--distribute": "distribute", "--keep-topics": "keep_topics"})
    if offline:
        return S.main(rest, env, out, cls=MasterScenario, **kwargs)
    runs = []
    if variants in ("plain", "both"):
        runs.append(("plain", MasterScenario))
    if variants in ("simulate-first", "both"):
        runs.append(("simulate-first", SimulateFirstScenario))
    results = []
    for name, cls in runs:
        args = list(rest)
        if len(runs) > 1 and "--report" in args:
            k = args.index("--report")
            if k + 1 < len(args):
                root, ext = os.path.splitext(args[k + 1])
                args[k + 1] = "%s-%s%s" % (root, name, ext)
        if results:
            time.sleep(1.1)       # the project code ends with the second: two variants never share one
        out.write("\n################ variant: %s ################\n" % name)
        rc = S.main(args, env, out, cls=cls, **kwargs)
        results.append((name, rc))
        if rc in (2, 3):
            break
    if len(results) > 1 or results[0][1] != 0:
        out.write("\nVARIANTS: %s\n" % "; ".join("%s %s" % (n, {0: "PASS", 1: "FAIL", 2: "NOT RUN (environment)", 3: "NOT RUN (route missing)"}.get(rc, "exit %d" % rc)) for n, rc in results))
    codes = [rc for _n, rc in results]
    return 3 if 3 in codes else 2 if 2 in codes else 1 if 1 in codes else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))
