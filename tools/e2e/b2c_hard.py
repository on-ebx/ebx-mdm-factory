#!/usr/bin/env python3
"""The B2C scenario's HARD CASES (lot A, 2026-10-17): the paths the nominal run (b2c_scenario.sh) never reached, driven through REST and Data Services only.

A 4th source (CRM_Contacts: the same people, civility MR/MRS/MISS and loyalty tier L1/L2/L3 where the loyalty file has M./Mme and Bronze/Argent/Or, a birth date
written yyyyMMdd, a free-text comment far longer than any sensible maximum), then:
  - the codes to align must come out as TRANSCODE_REFERENCE proposals (a code table on a mapping step), covering every observed code, and be accepted;
  - a bad link is forced after the acceptance (the loyalty birth date read as isodate()): the preflight must block, name the rows and propose the fix; the build without
    "build anyway" must stop and write nothing; the build WITH it must reject the loyalty rows as convert.date (EBX_REFUSAL) and their child rows as PARENT_REJECTED;
    the banner's own causes (GET rejects/causes) must say so;
  - the preflight fix is applied and the staging rebuilt: the rejects of that link are resolved and the rows are loaded;
  - the too long comments are caught (preflight blocking, or refused at the load as tooLong).

    EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_hard.sh [--cleanup] [--through 1..7] [--report FILE] [--keep-files]
    tools/e2e/b2c_hard.sh --cleanup-project CODE   # deletes a project left in place by an earlier run (E2EHARD...) and reports what is left behind
    tools/e2e/b2c_hard.sh --selftest               # the assertion logic and the whole scenario against a stand-in factory (no instance)
    tools/e2e/b2c_hard.sh --dry-run                # generates and checks the four files, prints the plan, sends nothing

The project is LEFT IN PLACE by default (a visual pass can then open it): its code and the wizard's address are printed at the end. Credentials come only from the
environment and are never printed. The scenario WRITES (project, Connectors connections, dataspaces, models, a staging load): test instance only.
Exit: 0 every check passed, 1 at least one FAIL, 2 environment or arguments, 3 a route the scenario needs is missing on this build. See docs/E2E-B2C.md ("Hard cases").
"""
import csv
import os
import shutil
import sys
import tempfile
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "itest"))

import b2c_scenario as S  # noqa: E402
import client  # noqa: E402
import e2e_lib as L  # noqa: E402

CRM_FILE = ("CRM", "CRM_Contacts", "csv", "CRM", 70)          # application, file stem, extension, source code, trust rank
HARD_FILES = list(S.FILES) + [CRM_FILE]
HARD_NAMES = dict(S.GENERATED_NAMES, CRM="CRM_Contacts.csv")
S.MIME.setdefault("csv", "text/csv")

HARD_STEPS = {1: "project", 2: "sources (four files)", 3: "landing", 4: "proposal and code tables", 5: "decisions", 6: "a bad link: preflight, blocked build, build anyway",
              7: "the fix, the rebuild"}

# the routes the hard scenario needs beyond the nominal ones: the preflight (lot H1) and the causes behind the banner (this lot)
HARD_ROUTES = [("POST", "/projects/{x}/staging/preflight", None, "check the accepted links before the build"),
               ("GET", "/projects/{x}/staging/preflight", None, "read the last check"),
               ("GET", "/projects/{x}/rejects/causes", None, "why the held back rows were set aside (the banner's counts and sentence)")]
# read only by --cleanup to say what the project deletion left behind; without it the connections are reported as "could not check"
HARD_OPTIONAL_ROUTES = [("GET", "/sources/onboarding/connections", None, "list the connections (what the project deletion leaves behind)")]


# ----------------------------------------------------------------------------------------------------------------------------------- the files

def read_crm(path):
    with open(path, encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter=","))


def check_hard_files(paths):
    """(problems, counts): the three nominal fixtures checked as ever, then the CRM file against the numbers the hard scenario asserts later."""
    problems, counts = S.check_files(dict((a, paths[a]) for a in ("ECOM", "STORES", "LOYALTY")))
    try:
        rows = read_crm(paths["CRM"])
    except (OSError, KeyError) as exc:
        return problems + ["CRM_Contacts.csv unreadable: %s" % exc], counts
    counts["CRM"] = len(rows)
    if len(rows) != L.HARD_ROWS["CRM"]:
        problems.append("CRM_Contacts.csv: %d row(s), expected %d" % (len(rows), L.HARD_ROWS["CRM"]))
    wanted = ["contact_id", "civility", "last_name", "first_name", "birth", "email", "phone", "tier", "comment"]
    if rows and any(w not in rows[0] for w in wanted):
        problems.append("CRM_Contacts.csv: columns %s, expected at least %s" % (",".join(rows[0].keys()), ",".join(wanted)))
        return problems, counts
    if any(len(r["birth"]) != 8 or not r["birth"].isdigit() for r in rows):
        problems.append("CRM_Contacts.csv: a birth date is not written yyyyMMdd")
    for column, concept in (("civility", "civility"), ("tier", "tier")):
        seen = tuple(sorted(set(r[column] for r in rows)))
        if seen != tuple(sorted(L.OBSERVED_CODES[(concept, "CRM")])):
            problems.append("CRM_Contacts.csv: %s codes %s, expected %s" % (column, seen, tuple(sorted(L.OBSERVED_CODES[(concept, "CRM")]))))
    long_rows = L.count_longer([r["comment"] for r in rows], 255)
    if long_rows != L.CRM_LONG_COMMENTS or any(len(r["comment"]) > 0 and 255 < len(r["comment"]) < 600 for r in rows):
        problems.append("CRM_Contacts.csv: %d comment(s) over 255 characters, expected %d of at least 600" % (long_rows, L.CRM_LONG_COMMENTS))
    # the loyalty file really writes the other codes
    try:
        with open(paths["LOYALTY"], encoding="utf-8", newline="") as fh:
            loyalty = list(csv.DictReader(fh))
        for column, concept in (("civilite", "civility"), ("niveau", "tier")):
            seen = tuple(sorted(set(r[column] for r in loyalty)))
            if seen != tuple(sorted(L.OBSERVED_CODES[(concept, "LOYALTY")])):
                problems.append("Fidelite_Membres.csv: %s codes %s, expected %s" % (column, seen, tuple(sorted(L.OBSERVED_CODES[(concept, "LOYALTY")]))))
    except (OSError, KeyError) as exc:
        problems.append("Fidelite_Membres.csv unreadable: %s" % exc)
    return problems, counts


# ----------------------------------------------------------------------------------------------------------------------------------- the scenario

class HardScenario(S.Scenario):
    CODE_PREFIX = "E2EHARD"
    TITLE = "B2C hard-cases scenario"
    REPORT_NAME = "b2c-hard"
    PROJECT_LABEL = "B2C hard cases"
    FILES = HARD_FILES
    APPS = ("ECOM", "STORES", "LOYALTY", "CRM")
    ROWS = L.HARD_ROWS
    KEYS = L.HARD_KEYS
    STEP_NAMES = HARD_STEPS
    FILES_WORD = "four"
    #: the forced bad link is on LOYALTY and the long comments / codes on CRM: those two must be in the person entity; ECOM and STORES may be modelled apart
    REQUIRED_PERSON_APPS = ("LOYALTY", "CRM")
    KEEP_HINT = "left in place for a visual pass; delete it with --cleanup-project <code> or the wizard's Delete project"
    expected_birth_dates = L.HARD_BIRTH_DATES
    VERIFY_STEP = "7"

    def __init__(self, *args, **kwargs):
        S.Scenario.__init__(self, *args, **kwargs)
        self.paths = {}
        self.crm_comments = []
        self.facts = {}                 # what step 6 works out about the model: comment attribute, forced links, child entities...
        self.codes = {}                 # concept -> {app: {"observed", "mapping", "steps", "table"}}
        self.baseline = None
        self.pf_comment_flagged = None
        self.details = {}
        self.report_data["details"] = self.details

    def step_functions(self):
        return [(1, self.step1), (2, self.step2), (3, self.step3), (4, self.step4), (5, self.step5), (6, self.step6), (7, self.step7)]

    def required_routes(self):
        return S.REQUIRED_ROUTES + HARD_ROUTES

    def optional_routes(self):
        return HARD_OPTIONAL_ROUTES

    # ------------------------------------------------------------------------------------------------------------------ 2. the four files
    def make_files(self):
        self.paths = S.generate_files(self.workdir, HARD_NAMES)
        problems, counts = check_hard_files(self.paths)
        self.rep.check("2", "generated files match the fixtures (160 / 234 / 140 / 150, CRM codes, yyyyMMdd, long comments)", not problems,
                       "ECOM %(ECOM)d, STORES %(STORES)d, LOYALTY %(LOYALTY)d, CRM %(CRM)d" % counts, "; ".join(problems))
        if problems:
            raise S.Stop()
        self.crm_comments = [r["comment"] for r in read_crm(self.paths["CRM"])]
        return self.paths

    # ------------------------------------------------------------------------------------------------------------------ 4. the proposal: birth dates, codes
    def step4(self):
        S.Scenario.step4(self)
        self.check_crm_birth()
        self.check_codes_proposed()

    def check_crm_birth(self):
        links = self.mappings_reading({"birth"})
        if not links:
            self.rep.failed("4", "CRM birth date (yyyyMMdd) is read", "no live mapping reads the CRM birth column")
            return
        shown = [(L.text(m.get("pipeline")) + " " + L.text(m.get("transformation")) + " " + L.text(m.get("argument"))).strip() for m in links]
        ok = all("yyyymmdd" in t.lower() for t in shown) and not any(L.value_check_failing(L.text(m.get("rationale"))) for m in links)
        self.rep.check("4", "CRM birth date is read as yyyyMMdd", ok, "; ".join(shown),
                       "the model reads it as: %s (the preflight below is the safety net)" % "; ".join(t or "(nothing)" for t in shown))

    def concept_mappings(self, roles):
        """[(mapping row, application)] of the live mappings of the person entity that read a column of `roles`."""
        app_of_table = dict((L.text(t["id"]), app) for app, t in self.tables.items())
        out = []
        for m in self.mappings_reading(roles):
            column = self.col_by_id.get(L.text(m.get("landingColumn"))) or {}
            out.append((m, app_of_table.get(L.text(column.get("landingTable")), "?")))
        return out

    def read_codes(self):
        """Fills self.codes from the live mapping steps: {concept: {app: {observed, mapping, steps, table}}} (table = the first live code table of the source, or None)."""
        self.codes = {}
        for concept, roles in (("civility", L.CIVILITY_COLUMNS), ("tier", L.TIER_COLUMNS)):
            per = {}
            for m, app in self.concept_mappings(roles):
                resp, steps = self.ds.select("MappingStep", "./mapping='%s'" % m["id"])
                coded = [st for st in steps if L.text(st.get("kind")) == L.TRANSCODE_KIND and L.live(st) and L.parse_code_table(L.text(st.get("argument"))) is not None]
                per[app] = {"observed": L.OBSERVED_CODES.get((concept, app), ()), "mapping": L.text(m["id"]), "attribute": L.text(m.get("attribute")), "steps": coded,
                            "table": L.parse_code_table(L.text(coded[0].get("argument"))) if coded else None}
            self.codes[concept] = per

    def check_codes_proposed(self):
        self.read_codes()
        produced = {}
        for concept, per in sorted(self.codes.items()):
            produced[concept] = {}
            for app, info in sorted(per.items()):
                produced[concept][app] = {"mapping": info["mapping"], "steps": [
                    {"id": L.text(st["id"]), "status": L.text(st.get("status")), "confidence": L.text(st.get("confidence")), "table": L.parse_code_table(L.text(st.get("argument")))}
                    for st in info["steps"]]}
            sources = dict((app, (i["observed"], i["table"])) for app, i in per.items() if i["observed"])
            if len(sources) < 2:
                self.rep.failed("4", "%s: the two sources feed one attribute" % concept,
                                "mappings read the %s columns of: %s (the model did not put LOYALTY and CRM on one attribute)" % (concept, ", ".join(sorted(per)) or "no source"))
                continue
            # live run E2EHARD20261017191017: the model may keep the two sources' codes on two different attributes (CRM tier and loyalty niveau
            # as separate fields) - then nothing has to be aligned on one attribute: not judged, never a FAIL
            attributes = set(i.get("attribute") for app, i in per.items() if i["observed"])
            if len(attributes) > 1:
                self.rep.skipped("4", "%s: the codes to align come as a TRANSCODE_REFERENCE proposal" % concept,
                                 "the model kept the sources' %s codes on %d different attributes - nothing to align on one attribute" % (concept, len(attributes)))
                continue
            with_step = [app for app, i in sorted(per.items()) if i["steps"]]
            self.rep.check("4", "%s: the codes to align come as a TRANSCODE_REFERENCE proposal" % concept, bool(with_step),
                           "code table on: %s" % ", ".join(with_step),
                           "no mapping step of kind %s on %s: the codes %s stay different by source (language model off, or the pass did not run?)" %
                           (L.TRANSCODE_KIND, " or ".join(sorted(per)), " vs ".join("/".join(i["observed"]) for _a, i in sorted(per.items()) if i["observed"])))
            flagged_gap = False
            for app, info in sorted(per.items()):
                for st in info["steps"]:
                    table = L.parse_code_table(L.text(st.get("argument")))
                    gaps = L.code_gaps(table, info["observed"])
                    confidence = L.to_int(st.get("confidence"), 100)
                    reason = (L.text(st.get("rationale")) + " " + L.text(self.mapping_rationale(info["mapping"]))).strip()
                    flagged = bool(gaps) and confidence <= L.FLAG_CEILING and reason != ""
                    flagged_gap |= flagged
                    shown = ", ".join("%s->%s" % (k, v or "?") for k, v in sorted(table["codes"].items()))
                    self.rep.check("4", "%s %s: every observed code has a target (or the table is flagged <= %d with a reason)" % (concept, app, L.FLAG_CEILING), not gaps or flagged,
                                   "%s%s" % (shown, " | flagged at %d: %d code(s) left to a person" % (confidence, len(gaps)) if gaps else ""),
                                   "code(s) %s of %s have no target and the step is at %d%s" % (", ".join(gaps), app, confidence, "" if confidence <= L.FLAG_CEILING else " (not flagged)"))
            if with_step and not flagged_gap:
                problems, vocabulary = L.codes_alignment(sources)
                self.rep.check("4", "%s: both sources end on one common list" % concept, not problems, "common list: %s" % ", ".join(sorted(vocabulary)), "; ".join(problems))
            elif with_step:
                self.rep.info("4", "%s: alignment not judged" % concept, "a table is flagged for a person (codes without a target)")
        self.details["codes"] = produced

    def mapping_rationale(self, mapping_id):
        for m in self.mappings:
            if L.text(m.get("id")) == mapping_id:
                return L.text(m.get("rationale"))
        return ""

    # ------------------------------------------------------------------------------------------------------------------ 5. the code tables are accepted
    def step5(self):
        S.Scenario.step5(self)
        self.check_codes_accepted()

    def check_codes_accepted(self):
        for concept, per in sorted(self.codes.items()):
            ids = [L.text(st["id"]) for info in per.values() for st in info["steps"]]
            if not ids:
                continue
            states = {}
            for sid in ids:
                resp, row = self.ds.get_row("MappingStep", sid)
                states[sid] = L.text((row or {}).get("status"))
            # staging last points (2026-10-17): the factory does not accept a code table while an observed code with rows has no target; the scenario rejects such a table
            # (documented policy, it cannot fill one with Edit), so a table with a gap is expected REJECTED, any other ACCEPTED / APPLIED
            incomplete = set()
            for info in per.values():
                for st in info["steps"]:
                    if L.code_gaps(L.parse_code_table(L.text(st.get("argument") or "")) or {"codes": {}}, info["observed"]) and L.parse_code_table(L.text(st.get("argument") or "")):
                        incomplete.add(L.text(st["id"]))
            bad = dict((k, v) for k, v in states.items() if v not in (("REJECTED",) if k in incomplete else ("ACCEPTED", "APPLIED")))
            self.rep.check("5", "%s: the code table(s) are accepted (an incomplete one is rejected, never accepted)" % concept, not bad,
                           "%d step(s) %s%s" % (len(ids), "/".join(sorted(set(states.values()))), " (%d incomplete, rejected)" % len(incomplete) if incomplete else ""),
                           "step(s) in the wrong state: %s" % ", ".join("%s=%s" % kv for kv in sorted(bad.items())))
        self.read_codes()      # the statuses changed; step 7 reads the accepted tables

    # ------------------------------------------------------------------------------------------------------------------ 6. a bad link
    def step6(self):
        self.load_attributes()
        self.work_out_facts()
        self.baseline_preflight()
        self.force_bad_link()
        self.build_blocked_then_anyway()
        self.check_bad_link_rejects()

    def work_out_facts(self):
        f = self.facts
        person_id = L.text(self.person["id"])
        f["personId"] = person_id
        f["loyaltyCode"] = L.text(self.sources["LOYALTY"].get("sourceSystem"))
        # the link to break: the accepted loyalty birth-date mapping(s)
        forced = [m for m in self.mappings_reading({"date_naissance"}) if L.text(m.get("status")) in ("ACCEPTED", "APPLIED")]
        if not forced:
            self.rep.failed("6", "an accepted loyalty birth-date link exists to break", "no ACCEPTED mapping reads date_naissance (statuses: %s)" %
                            ", ".join(L.text(m.get("status")) for m in self.mappings_reading({"date_naissance"})))
            raise S.Stop()
        f["forced"] = forced
        f["originalPipelines"] = dict((L.text(m["id"]), L.text(m.get("pipeline"))) for m in forced)
        f["birthDateColumn"] = "date_naissance"
        # the comment attribute and how many CRM comments are longer than its maximum length
        f["commentAttribute"] = None
        f["commentMapping"] = None
        f["maxLength"] = None
        f["longRows"] = 0
        by_id = dict((L.text(a["id"]), a) for a in self.attributes)
        for m in self.mappings_reading(L.COMMENT_COLUMNS):
            attribute = by_id.get(L.text(m.get("attribute")))
            if attribute is not None and L.live(attribute):
                f["commentAttribute"], f["commentMapping"] = attribute, m
                limit = L.to_int(attribute.get("maxLength"), 0)
                f["maxLength"] = limit or None
                f["longRows"] = L.count_longer(self.crm_comments, limit) if limit else 0
                break
        f["longInPerson"] = f["longRows"] if f["commentAttribute"] is not None and L.text(f["commentAttribute"].get("entity")) == person_id else 0
        # the child entities built from the person rows of the loyalty source (their rows are rejected with their parent)
        loyalty_table = L.text(self.tables["LOYALTY"]["id"])
        loyalty_feeds = [L.text(fd["id"]) for fd in self.person_feeds if L.text(fd.get("landingTable")) == loyalty_table]
        children = []
        for fid in loyalty_feeds:
            resp, rows = self.ds.select("TransferFeed", "./parentFeed='%s'" % fid)
            children.extend(fd for fd in rows if L.live(fd))
        f["childFeeds"] = children
        entity_ids = set(L.text(fd.get("entity")) for fd in children)
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        f["childEntities"] = [e for e in entities if L.text(e["id"]) in entity_ids and L.text(e.get("status")) not in ("SUPERSEDED", "REJECTED")]
        self.rep.info("6", "what the model built", "forced link(s): %s; comment: %s (maxLength %s, %d CRM value(s) longer); child entities under the loyalty feed: %s" % (
            ", ".join(f["originalPipelines"]) or "-", L.text(f["commentAttribute"].get("name")) if f["commentAttribute"] is not None else "not kept", f["maxLength"] or "-",
            f["longRows"], ", ".join(L.text(e.get("name")) for e in f["childEntities"]) or "none"))

    def preflight(self, apply=None):
        tail = "/projects/%s/staging/preflight" % self.code + ("?apply=" + urllib.parse.quote(apply, safe="*") if apply else "")
        r = self.post(tail)
        data = r.json()
        if r.status != 200 or not isinstance(data, dict) or data.get("exists") is not True:
            self.rep.failed("6", "the preflight answers", "HTTP %d %s" % (r.status, S.clip(r.error_text() or r.text, 300)))
            raise S.Stop()
        return data

    @staticmethod
    def summary(report):
        return "%s link(s) checked, %s blocking link(s), %s blocking row(s), %s warning(s)" % (report.get("linksChecked"), report.get("blockingLinks"), report.get("blockingRows"), report.get("warnings"))

    def baseline_preflight(self):
        """The model's own links, before anything is broken: the long comment is the only thing that may block; anything else is the model's link refused at the load."""
        report = self.preflight()
        self.baseline = report
        self.details["baselinePreflight"] = {"blocking": report.get("blocking"), "blockingRows": report.get("blockingRows"), "blockingLinks": report.get("blockingLinks")}
        self.rep.info("6", "baseline preflight (before the bad link)", self.summary(report))
        comment_mapping = L.text(self.facts["commentMapping"]["id"]) if self.facts["commentMapping"] is not None else None
        pf = L.preflight_finding(report, comment_mapping) if comment_mapping else None
        self.pf_comment_flagged = bool(pf and any(i.get("kind") == "TOO_LONG" and i.get("blocking") is True for i in pf.get("issues") or []))
        others = [fd for fd in L.blocking_findings(report) if L.text(fd.get("mapping")) != comment_mapping]
        self.rep.check("6", "baseline preflight: every link the model proposed converts (only the long comment may block)", not others, self.summary(report),
                       "blocking: %s" % "; ".join("%s.%s <- %s (%s, %s row(s)%s)" % (fd.get("entity"), fd.get("attribute"), fd.get("column"), fd.get("pipeline") or "no pipeline",
                                                                                      sum(L.to_int(i.get("rows")) for i in L.blocking_issues(fd)),
                                                                                      ", fix " + str((fd.get("fix") or {}).get("pipeline")) if fd.get("fix") else ", no fix") for fd in others))
        if others:
            fixed = 0
            for fd in others:
                if fd.get("fix") and fd.get("mapping"):
                    self.preflight(apply=L.text(fd["mapping"]))
                    fixed += 1
            if fixed:
                self.rep.info("6", "baseline preflight: the model's own bad link(s) fixed to go on", "%d fix(es) applied through the preflight" % fixed)

    def force_bad_link(self):
        """TEST-ONLY SHORTCUT: no REST route edits a mapping's pipeline (the Mapping matrix is a user service), so the pipeline is written on the row through Data
        Services - the very field the matrix' edit writes and the preflight fix writes. The status is left as it is (ACCEPTED)."""
        f = self.facts
        for m in f["forced"]:
            r = self.guarded_update("AttributeMapping", m["id"], {"pipeline": L.FORCED_PIPELINE})
            if r.status not in (200, 204):
                self.rep.failed("6", "bad link forced (loyalty birth date read with %s)" % L.FORCED_PIPELINE, "HTTP %d on mapping %s: %s" % (r.status, m["id"], S.clip(r.error_text())))
                raise S.Stop()
        self.rep.passed("6", "bad link forced (loyalty birth date read with %s)" % L.FORCED_PIPELINE,
                        "mapping(s) %s, was %s (Data Services write on AttributeMapping.pipeline: test-only shortcut)" % (", ".join(f["originalPipelines"]), "; ".join(
                            "'%s'" % p for p in f["originalPipelines"].values())))
        report = self.preflight()
        self.details["forcedPreflight"] = {"blocking": report.get("blocking"), "blockingRows": report.get("blockingRows"), "blockingLinks": report.get("blockingLinks")}
        self.rep.check("6", "preflight blocks the build (blocking = true)", report.get("blocking") is True, self.summary(report), "blocking is %r: %s" % (report.get("blocking"), self.summary(report)))
        for m in f["forced"]:
            finding = L.preflight_finding(report, m["id"])
            issues = [i for i in L.blocking_issues(finding) if i.get("kind") in ("CONVERSION", "LOST")]
            rows = sum(L.to_int(i.get("rows")) for i in issues)
            self.rep.check("6", "preflight: at least %d blocking rows on the loyalty birth-date link" % self.ROWS["LOYALTY"], bool(issues) and rows >= self.ROWS["LOYALTY"],
                           "%d row(s) (%s) on %s <- %s" % (rows, "/".join(sorted(set(i["kind"] for i in issues))), finding.get("attribute") if finding else "?", finding.get("column") if finding else "?"),
                           "no blocking CONVERSION/LOST issue on mapping %s" % m["id"] if not issues else "only %d blocking row(s)" % rows)
            fix = (finding or {}).get("fix") or {}
            self.rep.check("6", "preflight proposes the fix %s" % L.LOYALTY_FIX_PIPELINE, L.pipeline_norm(fix.get("pipeline")) == L.pipeline_norm(L.LOYALTY_FIX_PIPELINE),
                           "fix '%s' converts %s row(s)" % (fix.get("pipeline"), fix.get("rows")), "proposed fix: %r%s" % (fix.get("pipeline"), " (ambiguous: %r)" % finding.get("ambiguous") if finding and finding.get("ambiguous") else ""))
            self.rep.check("6", "preflight judged the edited chain (%s)" % L.FORCED_PIPELINE, finding is not None and L.pipeline_norm(finding.get("pipeline")) == L.pipeline_norm(L.FORCED_PIPELINE),
                           "pipeline '%s'" % (finding or {}).get("pipeline"), "the finding names pipeline %r" % (finding or {}).get("pipeline"))

    # ------------------------------------------------------------------------------------------------------------------ the build
    def open_staging(self):
        resp, project = self.ds.get_row("Project", self.code)
        space, dataset = L.text((project or {}).get("stagingDataspace")), L.text((project or {}).get("stagingDataset"))
        if not space or not dataset:
            self.rep.failed("6", "staging dataset recorded on the project", "stagingDataspace/stagingDataset empty")
            raise S.Stop()
        self.stg = client.Ds(self.admin, space, dataset)

    def table_rows(self, entity):
        resp, rows = self.stg.select(self.table_path(entity), None, page_size=500, max_pages=100)
        return rows if resp.status == 200 else None

    def read_rejects(self):
        resp, rows = self.ds.select("TransferReject", "./project='%s'" % self.code, page_size=500, max_pages=100)
        if resp.status != 200:
            self.rep.failed("6", "TransferReject readable", "HTTP %d %s" % (resp.status, S.clip(resp.error_text())))
            raise S.Stop()
        return rows

    def build_blocked_then_anyway(self):
        r = self.post("/projects/%s/staging/generate" % self.code, slow=True)
        self.rep.check("6", "staging model and dataset built", r.status == 200, S.clip(r.text, 160), "HTTP %d %s" % (r.status, S.clip(r.error_text(), 300)))
        if r.status != 200:
            raise S.Stop()
        self.open_staging()
        job = self.start_job("6", "staging load WITHOUT 'build anyway'", "/projects/%s/staging/load?scope=all" % self.code, self.timeout("BUILD", 1200), "the staging load", expect_done=False)
        blocked = L.build_was_blocked(job.get("message"))
        self.rep.check("6", "the build is stopped by the preflight (nothing to do, not started)", job.get("status") in ("DONE", "DONE_WITH_FAILURES") and blocked, S.clip(L.text(job.get("message")), 160),
                       "job %s: %s" % (job.get("status"), S.clip(L.text(job.get("message")), 200)))
        rows = self.table_rows(self.person)
        rejects = self.read_rejects()
        self.rep.check("6", "the stopped build wrote nothing and rejected nothing", rows is not None and len(rows) == 0 and not rejects,
                       "0 person row, 0 reject", "%s person row(s) in staging, %d reject(s)" % ("?" if rows is None else len(rows), len(rejects)))
        job = self.start_job("6", "staging load with 'build anyway'", "/projects/%s/staging/load?scope=all&buildAnyway=true" % self.code, self.timeout("BUILD", 1200),
                             "the staging load (build anyway)", expect_done=False)
        ran = job.get("status") in ("DONE", "DONE_WITH_FAILURES") and not L.build_was_blocked(job.get("message"))
        self.rep.check("6", "'build anyway' sets the preflight aside and loads", ran, "%s: %s" % (job.get("status"), S.clip(L.text(job.get("message")), 160)),
                       "job %s: %s (the build did not run: buildAnyway ignored by this build?)" % (job.get("status"), S.clip(L.text(job.get("message")), 200)))
        if not ran:
            raise S.Stop()

    def check_bad_link_rejects(self):
        f = self.facts
        rejects = self.read_rejects()
        self.details["rejectsAfterBadLink"] = {"openStaging": len(L.reject_select(rejects)), "byReasonCode": L.tally_by(L.reject_select(rejects), "reasonCode"),
                                               "byCategory": L.tally_by(L.reject_select(rejects), "category")}
        person = f["personId"]
        dated = L.reject_select(rejects, entity=person, code_prefix="convert.date|", column=f["birthDateColumn"])
        n = self.ROWS["LOYALTY"]
        self.rep.check("6", "the %d loyalty rows are rejected with reason code convert.date" % n, len(dated) == n,
                       "%d reject(s), e.g. %s" % (len(dated), L.text(dated[0].get("reasonCode")) if dated else "-"),
                       "%d convert.date reject(s) on %s; open STAGING rejects by reasonCode: %s" % (len(dated), f["birthDateColumn"], self.details["rejectsAfterBadLink"]["byReasonCode"]))
        wrong = [r for r in dated if L.text(r.get("category")) != "EBX_REFUSAL"]
        self.rep.check("6", "those rejects are of category EBX_REFUSAL", bool(dated) and not wrong, "%d of %d" % (len(dated), len(dated)),
                       "category(ies) %s" % L.tally_by(dated, "category"))
        rows = self.table_rows(self.person)
        loyalty_rows = [r for r in rows or [] if L.text(r.get("sourceSystem")) == f["loyaltyCode"]]
        # integration 2026-10-17: expected from the sources that really feed the person entity (the model may keep STORES apart)
        expected = sum(self.ROWS[a] for a in self.person_apps) - n - f["longInPerson"]
        self.rep.check("6", "the loyalty rows are not in staging; every other source is", rows is not None and not loyalty_rows and len(rows) == expected,
                       "%d person row(s): none from LOYALTY" % len(rows or []),
                       "%s person row(s) (%d from LOYALTY), expected %d from the other sources" % ("?" if rows is None else len(rows), len(loyalty_rows), expected))
        children = f["childEntities"]
        if not children:
            self.rep.skipped("6", "the child rows of the rejected parents are rejected as PARENT_REJECTED",
                             "the model built no child entity under the loyalty feed: this run does not exercise the cascade (look at the proposal)")
            expect_parent = 0
        else:
            child_ids = set(L.text(e["id"]) for e in children)
            parents = [r for r in L.reject_select(rejects, category="PARENT_REJECTED") if L.text(r.get("entity")) in child_ids]
            odd = [r for r in parents if not L.text(r.get("reasonCode")).startswith("parent|convert.date|")]
            expect_parent = n
            self.rep.check("6", "the child rows (%s) of the %d rejected parents are rejected as PARENT_REJECTED" % (", ".join(L.text(e.get("name")) for e in children), n),
                           len(parents) >= n and not odd, "%d reject(s), cause %s" % (len(parents), L.text(parents[0].get("reasonCode")) if parents else "-"),
                           "%d PARENT_REJECTED reject(s) on the child entities (expected at least %d)%s" % (len(parents), n, "; %d with another cause: %s" % (len(odd), L.text(odd[0].get("reasonCode"))) if odd else ""))
            written = []
            for e in children:
                child_rows = self.table_rows(e)
                if child_rows is not None:
                    written.extend(r for r in child_rows if L.text(r.get("sourceSystem")) == f["loyaltyCode"])
            self.rep.check("6", "no child row of a rejected loyalty parent was written", not written, "0 row", "%d child row(s) of LOYALTY in staging" % len(written))
        self.check_banner_causes(rejects, n, expect_parent, "6")
        self.check_too_long(rejects)

    def read_causes(self):
        r = self.get("/projects/%s/rejects/causes?stage=STAGING" % self.code)
        data = r.json()
        if r.status != 200 or not isinstance(data, dict):
            self.rep.failed("6", "the causes route answers", "HTTP %d %s" % (r.status, S.clip(r.error_text())))
            return None
        return data

    def check_banner_causes(self, rejects, expect_ebx, expect_parent, step):
        causes = self.read_causes()
        if causes is None:
            return
        self.details["causes-" + step] = causes
        problems = L.causes_problems(causes, expect_ebx, expect_parent)
        self.rep.check(step, "the banner says why: refused by EBX, not a rule (GET rejects/causes)", not problems, S.clip(L.text(causes.get("sentence")), 200), "; ".join(problems))
        mine = L.tally_by(L.reject_select(rejects), "category")
        counts = dict((k, L.to_int(v)) for k, v in (causes.get("counts") or {}).items())
        self.rep.check(step, "the causes' counts are those of the open STAGING rejects", counts == mine and L.to_int(causes.get("total")) == sum(mine.values()),
                       "%s" % counts, "route counts %s, TransferReject rows %s" % (counts, mine))

    def check_too_long(self, rejects):
        f = self.facts
        if f["commentAttribute"] is None:
            self.rep.skipped("6", "long comment values are caught", "no live mapping reads the CRM comment column: the model did not keep the field")
            return
        if not f["maxLength"]:
            self.rep.skipped("6", "long comment values are caught", "the comment field %s has no maximum length: nothing is too long" % L.text(f["commentAttribute"].get("name")))
            return
        if not f["longRows"]:
            self.rep.skipped("6", "long comment values are caught", "the maximum length of %s (%d) holds the longest CRM comment (%d)" % (
                L.text(f["commentAttribute"].get("name")), f["maxLength"], max(len(c) for c in self.crm_comments)))
            return
        refused = L.reject_select(rejects, code_prefix="tooLong|")
        by_preflight = bool(self.pf_comment_flagged)
        self.rep.check("6", "long comment values are caught (preflight blocking, or refused at the load as tooLong)", by_preflight or bool(refused),
                       "preflight: %s; load: %d tooLong reject(s) (maxLength %d, %d value(s) longer)" % ("blocking" if by_preflight else "silent", len(refused), f["maxLength"], f["longRows"]),
                       "neither the preflight nor the load caught the %d comment(s) longer than %d characters" % (f["longRows"], f["maxLength"]))
        self.details["tooLong"] = {"preflightBlocking": by_preflight, "loadRejects": len(refused), "maxLength": f["maxLength"], "valuesLonger": f["longRows"]}
        self.rep.check("6", "the load refuses exactly the %d too long comments with reason code tooLong" % f["longRows"], len(refused) == f["longRows"],
                       "%d reject(s), category %s" % (len(refused), "/".join(sorted(set(L.text(r.get("category")) for r in refused)))),
                       "%d tooLong reject(s), expected %d; open STAGING rejects by reasonCode: %s" % (len(refused), f["longRows"], self.details["rejectsAfterBadLink"]["byReasonCode"]))

    # ------------------------------------------------------------------------------------------------------------------ 7. the fix, the rebuild
    def step7(self):
        f = self.facts
        report = self.preflight(apply="*")
        fix = report.get("fix") or {}
        self.rep.check("7", "the preflight fix is applied (apply=*)", L.to_int(fix.get("applied")) >= len(f["forced"]) and L.to_int(fix.get("stale")) == 0 and L.to_int(fix.get("missing")) == 0,
                       "applied %s, stale %s, missing %s" % (fix.get("applied"), fix.get("stale"), fix.get("missing")),
                       "applied %s, stale %s, missing %s (expected %d applied)" % (fix.get("applied"), fix.get("stale"), fix.get("missing"), len(f["forced"])))
        if L.to_int(fix.get("applied")) < len(f["forced"]):
            raise S.Stop()
        for m in f["forced"]:
            resp, row = self.ds.get_row("AttributeMapping", m["id"])
            row = row or {}
            before = L.text(m.get("status"))
            self.rep.check("7", "the link now reads %s and keeps its status (ACCEPTED, or APPLIED by the rebuild)" % L.LOYALTY_FIX_PIPELINE, L.pipeline_norm(row.get("pipeline")) == L.pipeline_norm(L.LOYALTY_FIX_PIPELINE)
                           and (L.text(row.get("status")) == before or (before == "ACCEPTED" and L.text(row.get("status")) == "APPLIED")), "pipeline '%s', status %s" % (L.text(row.get("pipeline")), L.text(row.get("status"))),
                           "pipeline %r, status %s (was %s)" % (L.text(row.get("pipeline")), L.text(row.get("status")), before))
            finding = L.preflight_finding(report, m["id"])
            self.rep.check("7", "the fixed link no longer blocks in the new check", not L.blocking_issues(finding), "no blocking issue", "still blocking: %s" % (finding or {}).get("issues"))
        comment_mapping = L.text(f["commentMapping"]["id"]) if f["commentMapping"] is not None else None
        still = [fd for fd in L.blocking_findings(report) if L.text(fd.get("mapping")) != comment_mapping]
        self.rep.check("7", "nothing but the long comment still blocks", not still, self.summary(report),
                       "still blocking: %s" % "; ".join("%s.%s <- %s" % (fd.get("entity"), fd.get("attribute"), fd.get("column")) for fd in still))
        anyway = report.get("blocking") is True
        suffix = "&buildAnyway=true" if anyway else ""
        label = "staging load again (build anyway: the long comments still block)" if anyway else "staging load again (the preflight lets it through)"
        job = self.start_job("7", label, "/projects/%s/staging/load?scope=all%s" % (self.code, suffix), self.timeout("BUILD", 1200), "the staging load", expect_done=False)
        ran = job.get("status") in ("DONE", "DONE_WITH_FAILURES") and not L.build_was_blocked(job.get("message"))
        self.rep.check("7", "the rebuild runs", ran, "%s: %s" % (job.get("status"), S.clip(L.text(job.get("message")), 160)), "job %s: %s" % (job.get("status"), S.clip(L.text(job.get("message")), 200)))
        if not ran:
            raise S.Stop()
        self.check_after_fix()

    def check_after_fix(self):
        f = self.facts
        n = self.ROWS["LOYALTY"]
        rejects = self.read_rejects()
        every = L.reject_select(rejects, open_only=False, code_prefix="convert.date|") + [r for r in L.reject_select(rejects, open_only=False) if L.text(r.get("reasonCode")).startswith("parent|convert.date|")]
        open_ones = [r for r in every if L.reject_open(r)]
        self.rep.check("7", "the rejects of that link are resolved or gone (none open)", not open_ones,
                       "%d reject(s) of the link kept, all resolved" % len(every), "%d of %d reject(s) of the link still open (e.g. %s)" % (len(open_ones), len(every), L.text(open_ones[0].get("reasonCode")) if open_ones else "none ever written"))
        others = [r for r in L.reject_select(rejects) if not (L.text(r.get("reasonCode")).startswith("tooLong|") or L.text(r.get("reasonCode")).startswith("parent|tooLong|"))]
        self.rep.check("7", "the only rows still held back are the too long comments", not others, "%d open reject(s), all tooLong" % len(L.reject_select(rejects)),
                       "%d other open reject(s): %s" % (len(others), L.tally_by(others, "reasonCode")))
        rows = self.table_rows(self.person)
        if rows is None:
            self.rep.failed("7", "person entity rows readable", "the person entity %s has no readable table" % L.text(self.person.get("name")))
            return
        loyalty_rows = [r for r in rows if L.text(r.get("sourceSystem")) == f["loyaltyCode"]]
        expected = sum(self.ROWS[a] for a in self.person_apps) - f["longInPerson"]
        self.rep.check("7", "the person entity holds one row per source row (%d)" % expected, len(rows) == expected and len(loyalty_rows) == n,
                       "%d rows, %d from LOYALTY" % (len(rows), len(loyalty_rows)), "%d row(s) (%d from LOYALTY), expected %d (%d from LOYALTY)" % (len(rows), len(loyalty_rows), expected, n))
        children = f["childEntities"]
        if children:
            written = []
            for e in children:
                child_rows = self.table_rows(e)
                if child_rows is not None:
                    written.extend(r for r in child_rows if L.text(r.get("sourceSystem")) == f["loyaltyCode"])
            self.rep.check("7", "the child rows of the loyalty parents are written", len(written) >= n, "%d row(s) of LOYALTY in %s" % (len(written), ", ".join(L.text(e.get("name")) for e in children)),
                           "%d child row(s) of LOYALTY, expected at least %d" % (len(written), n))
        causes = self.read_causes()
        if causes is not None:
            self.details["causes-7"] = causes
            counts = dict((k, L.to_int(v)) for k, v in (causes.get("counts") or {}).items())
            mine = L.tally_by(L.reject_select(rejects), "category")
            self.rep.check("7", "the banner no longer blames the date (counts follow the open rejects)", counts == mine and L.to_int(causes.get("total")) == sum(mine.values())
                           and (bool(L.text(causes.get("sentence"))) == bool(mine)), S.clip(L.text(causes.get("sentence")) or "no banner: nothing held back", 160),
                           "route counts %s, TransferReject rows %s, sentence %r" % (counts, mine, S.clip(L.text(causes.get("sentence")), 100)))
        self.expected_birth_dates = L.HARD_BIRTH_DATES - f["longInPerson"]
        self.verify_person(rows)
        self.check_codes_loaded(rows)

    def check_codes_loaded(self, rows):
        by_id = dict((L.text(a["id"]), a) for a in self.attributes)
        for concept, per in sorted(self.codes.items()):
            if not any(i["steps"] for i in per.values()):
                continue
            attrs = []
            for info in per.values():
                m = next((x for x in self.mappings if L.text(x.get("id")) == info["mapping"]), None)
                a = by_id.get(L.text((m or {}).get("attribute")))
                if a is not None and a not in attrs:
                    attrs.append(a)
            if len(attrs) != 1:
                self.rep.info("7", "%s: stored codes" % concept, "the sources feed %d attribute(s): not judged" % len(attrs))
                continue
            name = L.text(attrs[0].get("name"))
            values = sorted(set(L.text(r.get(name)) for r in rows if L.non_empty(r.get(name))))
            complete = all(not L.code_gaps(i["table"], i["observed"]) for i in per.values() if i["table"] is not None)
            problems, vocabulary = L.codes_alignment(dict((app, (i["observed"], i["table"])) for app, i in per.items() if i["observed"]))
            if not complete or problems:
                self.rep.info("7", "%s: stored codes" % concept, "tables incomplete or not aligned (judged at step 4): stored values %s" % ", ".join(values))
                continue
            odd = [v for v in values if L.code_key(v) not in vocabulary]
            self.rep.check("7", "%s: every stored value is a code of the common list" % concept, bool(values) and not odd, "%s hold %s" % (name, ", ".join(values)),
                           "%s holds %s; outside the list: %s" % (name, ", ".join(values) or "nothing", ", ".join(odd)))

    # ------------------------------------------------------------------------------------------------------------------ the end
    def epilogue(self):
        if self.args.get("cleanup") or self.args.get("cleanup_project") or not self.created:
            return
        space, dataset = self.factory_space, self.factory_dataset
        self.out.write("\nPROJECT LEFT IN PLACE for a visual pass: %s\n" % self.code)
        if space and dataset:
            self.out.write("  wizard, step 4 (staging, the preflight block and the banner): %s\n" % L.wizard_url(self.base, space, dataset, self.code, 4))
            self.out.write("  wizard, step 3 (landing):                                    %s\n" % L.wizard_url(self.base, space, dataset, self.code, 3))
            self.out.write("  transfer-reject review:                                      %s\n" % L.rejects_url(self.base, space, dataset, self.code))
        self.out.write("  delete it (and its connections) later: tools/e2e/b2c_hard.sh --cleanup-project %s\n" % self.code)


# ----------------------------------------------------------------------------------------------------------------------------------- command line

def dry_run(out):
    work = tempfile.mkdtemp(prefix="b2c-hard-dry-")
    try:
        paths = S.generate_files(work, HARD_NAMES)
        problems, counts = check_hard_files(paths)
        out.write("files generated by gen_b2c.py (seeded): %s\n" % ", ".join("%s %d" % (a, counts.get(a, -1)) for a in HardScenario.APPS))
        for p in problems:
            out.write("PROBLEM %s\n" % p)
        out.write("\nplan (nothing is sent):\n")
        for n, name in sorted(HARD_STEPS.items()):
            out.write("  %d. %s\n" % (n, name))
        out.write("\nroutes needed:\n")
        for method, template, _b, label in S.REQUIRED_ROUTES + HARD_ROUTES + HARD_OPTIONAL_ROUTES:
            out.write("  %-4s %-48s %s\n" % (method, template, label))
        out.write("  POST /projects/{x}/staging/load?buildAnyway=true   build with the preflight set aside (parameter added by this lot)\n")
        return 1 if problems else 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main(argv, env, out=None):
    return S.main(argv, env, out, cls=HardScenario, max_step=7, dry=dry_run, doc=__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))
