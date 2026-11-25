#!/usr/bin/env python3
"""End-to-end scenario of the MDM Factory on the B2C test (lot H2): landing + staging, driven through REST and Data Services only.

Replaces the manual screenshot retests of 2026-10-17 (test-landing-b2c, test-staging-b2c). See docs/E2E-B2C.md.

    EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_scenario.sh [--cleanup] [--through N] [--report FILE]
    tools/e2e/b2c_scenario.sh --selftest      # the assertion logic on canned data, no instance needed
    tools/e2e/b2c_scenario.sh --dry-run       # generates and checks the three files, prints the plan, touches nothing
    tools/e2e/b2c_scenario.sh --cleanup-project CODE   # deletes a project of an earlier run (E2EB2C...) and reports what is left

Credentials come only from the environment, reach the server in an Authorization header and are never printed. The scenario WRITES (a project, Connectors
connections, dataspaces, models, a staging load): run it against a disposable or test instance, never against production.

Exit: 0 every check passed, 1 at least one FAIL, 2 environment or arguments, 3 a route the scenario needs is missing on this build.
"""
import base64
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "itest"))

import client  # noqa: E402  (tools/itest/client.py: Http, Ds, Guard, row_fields)
import e2e_lib as L  # noqa: E402

STEPS = {1: "project", 2: "sources", 3: "landing", 4: "proposal", 5: "decisions", 6: "build and load"}

# file stem (the application name EBX derives from the file name), extension, application code, trust rank
FILES = [
    ("ECOM", "ECOM_Clients", "json", "ECOM", 100),
    ("STORES", "Magasins_Ventes", "csv", "STORES", 90),
    ("LOYALTY", "Fidelite_Membres", "csv", "LOYALTY", 80),
]
GENERATED_NAMES = {"ECOM": "ECOM_Clients.json", "STORES": "Magasins_Ventes.csv", "LOYALTY": "Fidelite_Membres.csv"}
MIME = {"json": "application/json", "csv": "text/csv"}

# the routes the scenario needs: (method, path with {x} = a project code that does not exist, body, label). A route missing on this build is reported by name.
REQUIRED_ROUTES = [
    ("POST", "/projects/{x}/sources/files", "[]", "import sample files"),
    ("POST", "/projects/{x}/landing/run", None, "launch the landing"),
    ("POST", "/projects/{x}/staging/propose", None, "propose the staging"),
    ("POST", "/projects/{x}/staging/decide", None, "accept in bulk"),
    ("GET", "/projects/{x}/staging/review", None, "list what is left to decide"),
    ("POST", "/projects/{x}/staging/decisions", "{}", "decide single lines"),
    ("PUT", "/projects/{x}/capabilities/0/binding", "{}", "provide a derivation parameter"),
    ("POST", "/projects/{x}/capabilities/apply", None, "apply accepted script constraints"),
    ("POST", "/projects/{x}/staging/generate", None, "build the staging model"),
    ("POST", "/projects/{x}/staging/load", None, "load the staging"),
    ("GET", "/projects/{x}/jobs", None, "poll the jobs"),
    ("GET", "/projects/{x}/deletion/plan", None, "plan the deletion"),
]
OPTIONAL_ROUTES = [("GET", "/projects/{x}/staging/preflight", None, "staging preflight (lot H1)")]


class Stop(Exception):
    """A step cannot go on: the failure is already reported; the run goes to its report."""


class MissingRoute(Exception):
    pass


class ResumeRefused(Exception):
    """--from-project / --from-step: the project is not in the state the requested step starts from (or cannot be read); nothing was run. Exit 2 and a sentence saying what is missing."""


def now_stamp():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S")


def data_url(name, mime, payload):
    return "data:%s;name=%s;base64,%s" % (mime, urllib.parse.quote(name, safe=""), base64.b64encode(payload).decode("ascii"))


def clip(s, n=240):
    return client.clip(s, n)


# ----------------------------------------------------------------------------------------------------------------------------------- the files

def generate_files(directory, names=None):
    """Runs the (seeded, unchanged) generator of the manual test into `directory`; returns {app: path} (the three files, or `names` = {app: file name})."""
    subprocess.run([sys.executable, "-I", os.path.join(HERE, "gen_b2c.py"), directory], check=True, stdout=subprocess.DEVNULL)
    return dict((app, os.path.join(directory, name)) for app, name in (names or GENERATED_NAMES).items())


def check_files(paths):
    """(problems, counts): the parsed fixtures against the numbers the scenario asserts later (160 / 234 / 140, the envelope, the separators)."""
    problems = []
    counts = {}
    try:
        with open(paths["ECOM"], encoding="utf-8") as fh:
            doc = json.load(fh)
        if not isinstance(doc, dict) or list(doc.keys()) != ["customers"]:
            problems.append("ECOM_Clients.json is not an envelope {\"customers\": [...]}")
        counts["ECOM"] = len(doc.get("customers", [])) if isinstance(doc, dict) else 0
    except (OSError, ValueError) as exc:
        problems.append("ECOM_Clients.json unreadable: %s" % exc)
    import csv
    for app, delimiter, first in (("STORES", ";", "NUM_TICKET"), ("LOYALTY", ",", "card_number")):
        try:
            with open(paths[app], encoding="utf-8", newline="") as fh:
                rows = list(csv.reader(fh, delimiter=delimiter))
            counts[app] = len(rows) - 1
            if not rows or rows[0][0] != first:
                problems.append("%s: first column is not %s (separator %r)" % (GENERATED_NAMES[app], first, delimiter))
            if len(rows[0]) < 5:
                problems.append("%s: only %d column(s) with separator %r" % (GENERATED_NAMES[app], len(rows[0]), delimiter))
        except (OSError, IndexError) as exc:
            problems.append("%s unreadable: %s" % (GENERATED_NAMES[app], exc))
    for app, expected in L.EXPECTED_ROWS.items():
        if counts.get(app) != expected:
            problems.append("%s: %s row(s), expected %d" % (GENERATED_NAMES[app], counts.get(app), expected))
    return problems, counts


# ----------------------------------------------------------------------------------------------------------------------------------- the scenario

class Scenario:
    # what a variant of the scenario (tools/e2e/b2c_hard.py) changes: the project code prefix, the files, the expected rows, the steps and the report's name
    CODE_PREFIX = "E2EB2C"
    TITLE = "B2C end-to-end scenario"
    REPORT_NAME = "b2c"
    PROJECT_LABEL = "B2C end to end"
    FILES = FILES
    APPS = ("ECOM", "STORES", "LOYALTY")
    ROWS = L.EXPECTED_ROWS
    KEYS = L.EXPECTED_KEYS
    #: the applications whose key the scenario CHOOSES explicitly even when the suggestion is already the right one (the others: the suggestion is validated as it stands)
    EXPLICIT_KEY = L.EXPLICIT_KEY
    STEP_NAMES = STEPS
    #: whether --from-project / --from-step (resume an existing project at a step) is offered; a scenario that sets it implements resume()
    CAN_RESUME = False

    def __init__(self, env, out, args, code=None):
        self.env = env
        self.out = out
        self.args = args
        self.rep = L.Report(out)
        self.code = code or (self.CODE_PREFIX + now_stamp())
        self.suffix = "e2e" + self.code[-10:]                  # in the file names: a new connection per run
        self.guard = client.Guard(self.code)
        self.rest = None
        self.admin = None
        self.slow = None
        self.ds = None                  # the factory dataset
        self.stg = None                 # the project's staging dataset
        self.project_row = None
        self.workdir = None
        self.timings = {}
        self.report_data = {"routes": {}, "jobs": {}, "decisions": {}}
        self.leftovers = []
        self.uploaded = []              # (connectionId, catalogue sourceId, file name) of what the upload created: what is really left is read after the cleanup
        self.factory_space = ""
        self.factory_dataset = ""
        self.sources = {}               # app -> ProjectSource row
        self.tables = {}                # app -> LandingTable row
        self.columns = {}               # landing table id -> [LandingColumn rows]
        self.person = None
        self.person_feeds = []
        self.created = False
        self.incomplete_tables = []     # code tables the factory refused to accept (a code with rows and no target) and the scenario then rejected

    # ------------------------------------------------------------------------------------------------------------------ plumbing
    def timeout(self, name, default):
        return int(self.env.get("E2E_TIMEOUT_" + name, str(default)))

    def interval(self):
        return float(self.env.get("E2E_POLL", "5"))

    def say(self, text):
        self.out.write("note  %s\n" % text)
        self.out.flush()

    def connect(self):
        base = self.env.get("EBX_URL", "").strip().rstrip("/")
        user = self.env.get("EBX_USER", "")
        password = self.env.get("EBX_PASSWORD", "")
        if not base or not user or not password:
            sys.stderr.write("EBX_URL, EBX_USER and EBX_PASSWORD must be set in the environment\n")
            return False
        try:
            parts = urllib.parse.urlsplit(base)
            if parts.scheme not in ("http", "https") or not parts.hostname:
                raise ValueError
        except ValueError:
            sys.stderr.write("EBX_URL must look like http(s)://host[:port]\n")
            return False
        if parts.scheme == "http" and not client.is_local_host(parts.hostname) and self.env.get("E2E_ALLOW_INSECURE_HTTP") != "1":
            sys.stderr.write("refusing to send credentials in clear over http to %s (use https, or E2E_ALLOW_INSECURE_HTTP=1 on a trusted network)\n" % parts.hostname)
            return False
        module = self.env.get("EBX_MODULE", "ebx-mdm-factory")
        self.rest = "/%s/rest/v1" % module
        cafile = self.env.get("E2E_CACERT") or None
        trace = (lambda line: self.out.write("trace " + line + "\n")) if self.env.get("E2E_TRACE") == "1" else None
        self.base = base
        self.admin = client.Http(base, (user, password), "admin", cafile, timeout=120, guard=self.guard, trace=trace)
        self.slow = client.Http(base, (user, password), "admin-slow", cafile, timeout=self.timeout("SYNC", 1500), guard=self.guard, trace=trace)
        return True

    def get(self, tail):
        return self.admin.get(self.rest + tail)

    def post(self, tail, body=None, slow=False):
        return (self.slow if slow else self.admin).request("POST", self.rest + tail, body)

    def jobs(self):
        r = self.get("/projects/%s/jobs" % self.code)
        return r.json() if r.status == 200 else None

    def wait_job(self, job_id, timeout_s, label):
        """Waits for the job to end; returns the job dict (status, message, failedItems...). Raises L.Timeout with a message naming the wait."""
        last_line = [0.0]

        def fetch():
            job = L.job_in(self.jobs(), job_id)
            if job is None:   # fell off the last-ten list: the row itself
                resp, row = self.ds.get_row("FactoryJob", job_id)
                if resp.status == 200 and row:
                    job = {"jobId": job_id, "status": L.text(row.get("status")), "message": L.text(row.get("message")), "kind": L.text(row.get("kind"))}
            return job

        def tick(job, waited):
            if waited - last_line[0] >= 30:
                last_line[0] = waited
                self.say("%s: %s after %d s%s" % (label, (job or {}).get("status", "not listed yet"), waited,
                                                    (" - " + clip(job.get("stepMessage", ""), 100)) if job and job.get("stepMessage") else ""))
        return L.poll(fetch, lambda j: j is not None and j.get("status") in L.TERMINAL_JOB, timeout_s, self.interval(), label, on_tick=tick)

    def start_job(self, step, label, tail, timeout_s, wait_label, expect_done=True):
        """POSTs a job route (202 + jobId), waits for the job. Returns the finished job or raises Stop after reporting."""
        t0 = time.time()
        r = self.post(tail)
        body = r.json()
        if r.status != 202 or not isinstance(body, dict) or not body.get("jobId"):
            self.rep.failed(step, label + " queued", "HTTP %d %s" % (r.status, clip(r.error_text())))
            raise Stop()
        job_id = str(body["jobId"])
        self.rep.info(step, label + " queued", "job %s%s" % (job_id, " (merged into a running one)" if body.get("merged") else ""))
        try:
            job = self.wait_job(job_id, timeout_s, wait_label)
        except L.Timeout as exc:
            self.rep.failed(step, label + " ended", "%s (job %s; cancel it with POST /jobs/%s/cancel)" % (exc, job_id, job_id))
            raise Stop()
        self.report_data["jobs"][label] = {"jobId": job_id, "status": job.get("status"), "seconds": int(time.time() - t0), "message": clip(job.get("message", ""), 400)}
        ok = job.get("status") == "DONE"
        detail = "%s in %d s: %s" % (job.get("status"), int(time.time() - t0), clip(job.get("message", ""), 200))
        if job.get("failedItems"):
            detail += " | failed: " + "; ".join("%s (%s)" % (i.get("label"), clip(i.get("reason", ""), 80)) for i in job["failedItems"][:5])
        if expect_done:
            self.rep.check(step, label + " ended DONE", ok, detail, detail)
            if not ok:
                raise Stop()
        return job

    def guarded_update(self, table, pk, fields):
        self.guard.allow_path(self.ds.record(table, pk))
        return self.ds.update(table, pk, fields)

    # ------------------------------------------------------------------------------------------------------------------ 0. environment
    def step0(self):
        r = self.get("/status")
        data = r.json()
        if r.status != 200 or not isinstance(data, dict) or "dataspace" not in data:
            self.rep.failed("0", "administrator status answer", "HTTP %d %s" % (r.status, clip(r.error_text())))
            raise Stop()
        self.rep.passed("0", "module is up", "%s, version %s (commit %s), dataset %s" % (data.get("module"), data.get("version"), data.get("commit"), data.get("datasetState")))
        self.report_data["version"] = {"version": data.get("version"), "commit": data.get("commit"), "buildTime": data.get("buildTime")}
        self.ds = client.Ds(self.admin, data["dataspace"], data["dataset"])
        self.factory_space, self.factory_dataset = str(data["dataspace"]), str(data["dataset"])
        resp, settings = self.ds.get_row("FactorySettings", "DEFAULT")
        if resp.status != 200 or settings is None:
            self.rep.failed("0", "factory settings readable", "HTTP %d" % resp.status)
            raise Stop()
        if L.text(settings.get("role")) == "TARGET":
            self.rep.failed("0", "instance is not a delivery TARGET", "projects cannot be created on a TARGET instance")
            raise Stop()
        self.settings = settings
        r = self.get("/connection/test")
        data = r.json()
        ready = r.status == 200 and isinstance(data, dict) and data.get("ready") is True
        self.rep.check("0", "MCP servers ready (data-model, connectors, scripting, match-merge)", ready, "", "HTTP %d %s" % (r.status, clip(r.text, 300)))
        if not ready:
            raise Stop()
        self.inventory()

    def required_routes(self):
        return REQUIRED_ROUTES

    def optional_routes(self):
        return OPTIONAL_ROUTES

    def inventory(self):
        missing = []
        x = self.code + "NOSUCH"
        for required, routes in ((True, self.required_routes()), (False, self.optional_routes())):
            for method, template, body, label in routes:
                r = self.admin.request(method, self.rest + template.replace("{x}", x), body)
                gone = L.route_missing(r.status, r.json())
                self.report_data["routes"]["%s %s" % (method, template)] = "MISSING" if gone else "present (HTTP %d)" % r.status
                if gone and required:
                    missing.append("%s %s (%s)" % (method, template, label))
                elif gone:
                    self.rep.info("0", "optional route absent: %s %s" % (method, template), label + ": the step is skipped")
                elif r.status in (401, 403):
                    self.rep.failed("0", "route %s %s reachable" % (method, template), "HTTP %d %s" % (r.status, clip(r.error_text())))
                    raise Stop()
        if missing:
            self.rep.failed("0", "every route the scenario needs exists on this build", "missing: " + "; ".join(missing))
            raise MissingRoute()
        self.rep.passed("0", "every route the scenario needs exists on this build", "%d routes checked" % len(self.required_routes()))
        self.have_preflight = self.report_data["routes"].get("GET /projects/{x}/staging/preflight", "").startswith("present")

    # ------------------------------------------------------------------------------------------------------------------ 1. project
    def step1(self):
        row = {"code": self.code, "label": "%s %s (scratch)" % (self.PROJECT_LABEL, self.code[-14:]), "mode": "GREENFIELD", "status": "DRAFT",
               "llmValueSharing": "SHAPES_ONLY", "llmSampleSize": 20}
        if self.settings.get("instanceId"):
            row["originRepository"] = L.text(self.settings["instanceId"])
        r = self.ds.create("Project", row)
        if r.status not in (200, 201):
            self.rep.failed("1", "project created (style Consolidation)", "HTTP %d %s" % (r.status, clip(r.error_text())))
            raise Stop()
        self.created = True
        resp, fields = self.ds.get_row("Project", self.code)
        ok = resp.status == 200 and fields is not None and L.text(fields.get("style")) == "CONSOLIDATION"
        self.rep.check("1", "project created (style Consolidation)", ok, self.code, "read back: HTTP %d style=%s" % (resp.status, L.text((fields or {}).get("style"))))
        if not ok:
            raise Stop()

    # ------------------------------------------------------------------------------------------------------------------ 2. sources
    def step2(self):
        self.workdir = tempfile.mkdtemp(prefix="b2c-e2e-")
        paths = self.make_files()
        body = []
        for app, stem, ext, code, rank in self.FILES:
            name = "%s_%s.%s" % (stem, self.suffix, ext)
            with open(paths[app], "rb") as fh:
                body.append({"name": name, "dataUrl": data_url(name, MIME[ext], fh.read()), "code": code, "trustRank": rank})
        r = self.post("/projects/%s/sources/files" % self.code, body)
        data = r.json()
        if r.status != 200 or not isinstance(data, dict):
            self.rep.failed("2", "three sample files uploaded", "HTTP %d %s" % (r.status, clip(r.error_text())))
            raise Stop()
        errors = [f.get("error") for f in data.get("files", []) if f.get("error")]
        for f in data.get("files", []):
            self.uploaded.append((L.text(f.get("connectionId")), L.text(f.get("sourceId")), L.text(f.get("name"))))
        n = len(self.FILES)
        word = self.FILES_WORD
        self.rep.check("2", "%s sample files uploaded" % word, not errors and len(data.get("files", [])) == n, "%d connections written, scan queued" % n, "; ".join(errors) or "%d file(s) answered" % len(data.get("files", [])))
        if errors or len(data.get("files", [])) != n:
            raise Stop()
        try:
            L.poll(self.sources_ready, lambda ok: ok, self.timeout("READY", 300), self.interval(), "the %s applications to be ready (catalogue + scan)" % word)
        except L.Timeout as exc:
            self.rep.failed("2", "%s applications ready" % word, "%s; jobs: %s" % (exc, clip(json.dumps(self.jobs()), 300)))
            raise Stop()
        self.rep.passed("2", "%s applications ready" % word, ", ".join("%s: %s (%s column(s))" % (a, L.text(t.get("tableName")), L.text(t.get("columnCount"))) for a, t in sorted(self.tables.items())))
        for app, table in sorted(self.tables.items()):
            if not L.truthy(table.get("selected")):
                self.guarded_update("LandingTable", table["id"], {"selected": True})
                self.rep.info("2", "ticked the table of %s" % app, "it was not selected after the scan")
        self.info_tag_suggestions()

    def info_tag_suggestions(self):
        """INFO only (lot S10b): what the data catalogue classified of the project's columns after the scan - the TagSuggestion rows of the landing columns' catalogue sources, counted by
        (tag, origin, status). Never a FAIL: a file source has no sample operation, so nothing is expected here; the line tells what the catalogue holds."""
        try:
            sources = sorted(set(L.text(s.get("catalogSource")) for s in self.sources.values() if L.text(s.get("catalogSource"))))
            if not sources:
                self.rep.info("2", "tag suggestions of the catalogue", "the applications name no catalogue source")
                return
            catalogue = client.Ds(self.admin, "DataCatalog", "DataCatalog")
            counts = {}
            for source in sources:
                resp, rows = catalogue.select("TagSuggestion", "starts-with(./column,'%s:')" % source.replace("'", "''"))
                if resp.status != 200:
                    self.rep.info("2", "tag suggestions of the catalogue", "not readable (HTTP %d on TagSuggestion of %s)" % (resp.status, source))
                    return
                for row in rows:
                    key = (L.text(row.get("tag")), L.text(row.get("origin")), L.text(row.get("status")))
                    counts[key] = counts.get(key, 0) + 1
            if not counts:
                self.rep.info("2", "tag suggestions of the catalogue", "none for %s: the catalogue's sample flow did not classify these columns (a sample-file connection offers no sample operation)" % ", ".join(sources))
                return
            self.rep.info("2", "tag suggestions of the catalogue", "; ".join("%s %s %s x%d" % (tag, origin, status, n) for (tag, origin, status), n in sorted(counts.items())))
        except Exception as exc:   # an INFO never fails a run
            self.rep.info("2", "tag suggestions of the catalogue", "not readable (%s)" % clip(str(exc)))

    FILES_WORD = "three"
    #: the sources the nominal checks need in the person entity (all three: the nominal counts are 534 = 160 + 234 + 140)
    REQUIRED_PERSON_APPS = ("ECOM", "STORES", "LOYALTY")
    KEEP_HINT = "run again with --cleanup to remove them"

    def make_files(self):
        """Generates the fixtures into the work directory and checks them; returns {app: path}. Raises Stop (after reporting) when a fixture is not what the checks later assume."""
        paths = generate_files(self.workdir)
        problems, counts = check_files(paths)
        self.rep.check("2", "generated files match the fixtures (160 / 234 / 140)", not problems, "ECOM %(ECOM)d, STORES %(STORES)d, LOYALTY %(LOYALTY)d" % counts, "; ".join(problems))
        if problems:
            raise Stop()
        return paths

    def sources_ready(self):
        resp, sources = self.ds.select("ProjectSource", "./project='%s'" % self.code)
        if resp.status != 200:
            return False
        found, tables = {}, {}
        for s in sources:
            resp2, rows = self.ds.select("LandingTable", "./projectSource='%s'" % s.get("id"))
            for t in rows:
                for app, stem, _e, _c, _r in self.FILES:
                    if L.text(t.get("tableName")).lower().startswith(stem.lower()):
                        resp3, cols = self.ds.select("LandingColumn", "./landingTable='%s'" % t.get("id"))
                        if cols:   # the plan has written the columns
                            found[app], tables[app] = s, t
        jobs = self.jobs()
        busy = bool(jobs and jobs.get("busy"))
        if len(found) == len(self.FILES) and not busy:
            self.sources, self.tables = found, tables
            return True
        return False

    # ------------------------------------------------------------------------------------------------------------------ 3. landing
    def step3(self):
        for app in self.APPS:
            self.settle_key(app)
        before = self.count_jobs("LANDING_RUN")
        job = self.start_job("3", "landing launch", "/projects/%s/landing/run?useLlm=false" % self.code, self.timeout("LANDING", 600), "the landing run")
        resp, rows = self.ds.select("FactoryJob", "./project='%s' and (./kind='LANDING_RUN' or ./kind='LANDING_RUN_RESUME')" % self.code)
        kinds = [L.text(r.get("kind")) for r in rows]
        self.rep.check("3", "everything loaded in ONE launch (one run, no resume)", kinds.count("LANDING_RUN") - before == 1 and "LANDING_RUN_RESUME" not in kinds,
                       "1 LANDING_RUN job", "jobs of the project: %s" % ", ".join(kinds))
        total_ok = True
        for app in self.APPS:
            resp, t = self.ds.get_row("LandingTable", self.tables[app]["id"])
            t = t or {}
            rows_loaded = L.to_int(t.get("lastLoadRows"), -1)
            ok = L.text(t.get("status")) == "LOADED" and rows_loaded == self.ROWS[app]
            total_ok &= ok
            self.rep.check("3", "%s: %d rows loaded" % (app, self.ROWS[app]), ok, "status LOADED, %d rows" % rows_loaded,
                           "status %s, %d row(s) loaded, message: %s" % (L.text(t.get("status")), rows_loaded, clip(L.text(t.get("message")), 160)))
            self.tables[app] = dict(self.tables[app], **t)
        if not total_ok:
            raise Stop()

    def count_jobs(self, kind):
        resp, rows = self.ds.select("FactoryJob", "./project='%s' and ./kind='%s'" % (self.code, kind))
        return len(rows)

    def settle_key(self, app):
        """Validates the application's landing as a person does: the suggested key kept when it already is the expected one (except for the applications
        whose key the scenario chooses explicitly), otherwise the expected key chosen (origin USER, confidence 100); then ProjectSource.landingValidated."""
        table = self.tables[app]
        expected = self.KEYS[app]
        resp, columns = self.ds.select("LandingColumn", "./landingTable='%s'" % table["id"])
        self.columns[table["id"]] = columns
        names = [L.text(c.get("sourceColumn")) for c in columns]
        if expected not in names:
            self.rep.failed("3", "%s: column %s exists in the landing" % (app, expected), "columns: %s" % ", ".join(names))
            raise Stop()
        current = sorted(L.text(c.get("sourceColumn")) for c in columns if L.truthy(c.get("sourceKey")))
        suggested_ok = current == [expected]
        if suggested_ok and app not in self.EXPLICIT_KEY:
            self.rep.passed("3", "%s: suggested key %s validated as it stands" % (app, expected),
                            "origin %s, confidence %s" % (",".join(sorted(set(L.text(c.get("sourceKeyOrigin")) for c in columns if L.truthy(c.get("sourceKey"))))), L.text(table.get("keyConfidence"))))
        else:
            for c in columns:
                want = L.text(c.get("sourceColumn")) == expected
                r = self.guarded_update("LandingColumn", c["id"], {"sourceKey": want, "sourceKeyOrigin": "USER"})
                if r.status not in (200, 204):
                    self.rep.failed("3", "%s: key %s chosen" % (app, expected), "HTTP %d on column %s: %s" % (r.status, c.get("sourceColumn"), clip(r.error_text())))
                    raise Stop()
            r = self.guarded_update("LandingTable", table["id"], {"keyConfidence": 100, "keyRationale": "Key chosen by a person (end-to-end scenario)."})
            self.rep.check("3", "%s: key %s chosen explicitly" % (app, expected), r.status in (200, 204),
                           "was %s" % (",".join(current) or "none"), "HTTP %d %s" % (r.status, clip(r.error_text())))
        stamp = datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S")
        r = self.guarded_update("ProjectSource", self.sources[app]["id"], {"landingValidated": True, "landingValidatedBy": self.env.get("EBX_USER", "e2e")[:100], "landingValidatedAt": stamp})
        if r.status not in (200, 204):
            self.rep.failed("3", "%s: landing validated" % app, "HTTP %d %s" % (r.status, clip(r.error_text())))
            raise Stop()

    # ------------------------------------------------------------------------------------------------------------------ 4. proposal
    def step4(self):
        self.start_job("4", "staging proposal (language model on, as configured)", "/projects/%s/staging/propose?quality=true&capability=true" % self.code,
                       self.timeout("PROPOSAL", 1500), "the staging proposal")
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        self.entities = entities
        alone = L.rule_entities_left_alone(entities)
        self.rep.check("4", "no rule entity left PROPOSED alone", not alone, "%d entities, rule entities superseded" % len(entities),
                       "still PROPOSED: %s" % ", ".join(L.text(e.get("name")) for e in alone))
        self.person = L.person_entity(entities)
        if self.person is None:
            self.rep.failed("4", "the three sources feed one person entity", "no entity unifies the source entities; entities: %s" %
                            ", ".join("%s(%s members, %s)" % (L.text(e.get("name")), len(L.as_list(e.get("memberEntities"))), L.text(e.get("status"))) for e in entities))
            raise Stop()
        resp, feeds = self.ds.select("TransferFeed", "./entity='%s'" % self.person["id"])
        self.person_feeds = [f for f in feeds if L.live(f)]
        fed = L.feed_tables(self.person_feeds)
        want = set(L.text(t["id"]) for t in self.tables.values())
        # integration 2026-10-17 (live hard run E2EHARD20261017203246): the model may legitimately keep a source of another kind (store tickets =
        # purchases) out of the person entity - only the sources this scenario NEEDS in it (REQUIRED_PERSON_APPS) are strict; the others are noted
        self.person_apps = set(app for app, t in self.tables.items() if L.text(t["id"]) in fed)
        required = set(a for a in self.REQUIRED_PERSON_APPS if a in self.tables)
        missing_required = sorted(required - self.person_apps)
        self.rep.check("4", "the sources the scenario needs feed one person entity (%s)" % L.text(self.person.get("name")), not missing_required,
                       "feeds read %d of %d landing tables (%s)" % (len(want & fed), len(want), ", ".join(sorted(self.person_apps))),
                       "not fed by: %s" % ", ".join(missing_required))
        if want - fed:
            self.rep.info("4", "sources kept out of the person entity", ", ".join(sorted(a for a in self.tables if a not in self.person_apps)) + " (the model modelled them as another kind of record)")
        self.load_attributes()
        self.check_birth_date()
        self.check_plus("4")
        self.info_identity_facts()

    def info_identity_facts(self):
        """INFO only (lot S10b): the identity feedback the Factory gave the model, as far as it is stored. The inventory itself (rows, key, identity counts) is not stored anywhere readable;
        what is: the goal of the follow-up run (AgentRun.goal names the tables carrying identity attributes that fed no entity) and the record step's finding IDENTITY_SOURCE_UNFED in the
        detail of the proposal step."""
        try:
            resp, runs = self.ds.select("AgentRun", "./project='%s'" % self.code)
            asked = [r for r in runs if "identity attributes" in L.text(r.get("goal"))]
            if asked:
                for r in asked:
                    self.rep.info("4", "identity feedback asked of the model", clip(L.text(r.get("goal")), 400))
            else:
                self.rep.info("4", "identity feedback asked of the model", "no follow-up turn asked about identity (every table carrying an identity tag feeds an entity, or no column carries one)")
            resp, steps = self.ds.select("PipelineStep", "./project='%s'" % self.code)
            flagged = [L.text(st.get("step")) for st in steps if "IDENTITY_SOURCE_UNFED" in L.text(st.get("detail"))]
            self.rep.info("4", "identity open point at the review", "IDENTITY_SOURCE_UNFED reported by: %s" % ", ".join(flagged) if flagged else "none (no table left carrying an identity that feeds no entity holding it)")
            self.rep.info("4", "identity facts of the inventory", "not stored: the inventory the model reads (rows, key, identity counts) is built for the prompt only; see the goal above and the step detail")
        except Exception as exc:   # an INFO never fails a run
            self.rep.info("4", "identity feedback", "not readable (%s)" % clip(str(exc)))

    def load_attributes(self):
        resp, attrs = self.ds.select("StagingAttribute", "./entity='%s'" % self.person["id"])
        self.attributes = attrs
        self.col_by_id = {}
        for cols in self.columns.values():
            for c in cols:
                self.col_by_id[L.text(c["id"])] = c
        feed_ids = [L.text(f["id"]) for f in self.person_feeds]
        self.mappings = []
        for i in range(0, len(feed_ids), 10):
            resp, rows = self.ds.select("AttributeMapping", " or ".join("./feed='%s'" % f for f in feed_ids[i:i + 10]))
            self.mappings.extend(rows)

    def mappings_reading(self, roles):
        return [m for m in self.mappings if L.live(m) and L.text(self.col_by_id.get(L.text(m.get("landingColumn")), {}).get("sourceColumn")).lower() in roles]

    def check_birth_date(self):
        links = self.mappings_reading(L.BIRTH_COLUMNS)
        if not links:
            self.rep.failed("4", "birth date links exist on the person entity", "no mapping reads birthDate / date_naissance")
            return
        failing = [m for m in links if L.value_check_failing(L.text(m.get("rationale")))]
        loyalty = [m for m in links if L.text(self.col_by_id[L.text(m.get("landingColumn"))].get("sourceColumn")).lower() == "date_naissance"]
        converts = bool(loyalty) and all("dd/mm/yyyy" in (L.text(m.get("pipeline")) + " " + L.text(m.get("argument"))).lower() for m in loyalty)
        self.rep.check("4", "birth date link converts (no failing value check)", not failing, "%d link(s)" % len(links),
                       "failing value check on mapping(s) %s" % ", ".join(L.text(m["id"]) for m in failing))
        self.rep.check("4", "loyalty birth date is read as dd/MM/yyyy", converts, ", ".join(L.text(m.get("pipeline")) for m in loyalty),
                       "pipeline(s): %s" % (", ".join(L.text(m.get("pipeline")) or "(none)" for m in loyalty) or "no loyalty link"))

    def check_plus(self, step):
        links = self.mappings_reading(L.PHONE_COLUMNS)
        offenders = []
        for m in links:
            resp, steps = self.ds.select("MappingStep", "./mapping='%s'" % m["id"])
            for what in L.plus_droppers(L.text(m.get("pipeline")), steps):
                offenders.append("mapping %s: %s" % (L.text(m["id"]), what))
        self.rep.check(step, "no step drops a leading '+' of a phone number", bool(links) and not offenders, "%d phone link(s) checked" % len(links),
                       "; ".join(offenders) if offenders else "no mapping reads a phone column")

    # ------------------------------------------------------------------------------------------------------------------ 5. decisions
    def step5(self):
        threshold = int(self.env.get("E2E_THRESHOLD", "60"))
        self.start_job("5", "accept every proposal at %d %% or above" % threshold, "/projects/%s/staging/decide?accept=true&threshold=%d" % (self.code, threshold),
                       self.timeout("DECIDE", 900), "the bulk accept")
        self.decide_queue()
        self.provide_parameters()
        self.load_attributes()      # statuses changed with the decisions
        self.check_plus("5")

    def review(self, status="PROPOSED"):
        r = self.get("/projects/%s/staging/review?status=%s" % (self.code, status))
        data = r.json()
        if r.status != 200 or not isinstance(data, dict):
            self.rep.failed("5", "review readable", "HTTP %d %s" % (r.status, clip(r.error_text())))
            raise Stop()
        return data

    def decide_queue(self):
        tally = {"ACCEPTED": 0, "REJECTED": 0}
        refused = {}
        reasons = {}
        previous = None
        for _round in range(4):
            lines = self.review()["lines"]
            if not lines:
                break
            if previous is not None and len(lines) >= previous:
                break    # no progress: what is left is refused by the rules, reported below
            previous = len(lines)
            batch = []
            for line in L.order_lines(lines):
                status, why = L.decision_for(line)
                reasons[(line["nature"], status)] = why
                batch.append({"nature": line["nature"], "id": line["id"], "status": status})
            for i in range(0, len(batch), 100):
                r = self.post("/projects/%s/staging/decisions" % self.code, {"decisions": batch[i:i + 100]})
                data = r.json()
                if r.status != 200 or not isinstance(data, dict):
                    self.rep.failed("5", "flagged queue decided", "HTTP %d %s" % (r.status, clip(r.error_text())))
                    raise Stop()
                for d, res in zip(batch[i:i + 100], data.get("results", [])):
                    if res.get("ok"):
                        tally[d["status"]] += 1
                    elif d["nature"] == "MAPPING_STEP" and L.is_incomplete_code_table_refusal(res.get("reason")):
                        # staging last points (2026-10-17): the factory does not accept a code table while an observed code with rows has no target. Documented policy of
                        # this scenario (it cannot fill a table the way a person does, with Edit): such a table is REJECTED, and said so
                        rr = self.post("/projects/%s/staging/decisions" % self.code, {"decisions": [{"nature": d["nature"], "id": d["id"], "status": "REJECTED"}]})
                        out = rr.json()
                        if rr.status == 200 and isinstance(out, dict) and out.get("results") and out["results"][0].get("ok"):
                            tally["REJECTED"] += 1
                            self.incomplete_tables.append("%s (%s)" % (d["id"], clip(res.get("reason", ""), 90)))
                            reasons[(d["nature"], "REJECTED")] = "code table with codes still lacking a target: refused as an accept, rejected (the scenario cannot fill it)"
                        else:
                            refused[(d["nature"], clip(res.get("reason", ""), 90))] = refused.get((d["nature"], clip(res.get("reason", ""), 90)), 0) + 1
                    else:
                        refused[(d["nature"], clip(res.get("reason", ""), 90))] = refused.get((d["nature"], clip(res.get("reason", ""), 90)), 0) + 1
        left = self.review()
        counts = left.get("counts", {})
        self.report_data["decisions"] = {"accepted": tally["ACCEPTED"], "rejected": tally["REJECTED"], "refusedByRules": ["%s x%d %s" % (n, c, r) for (n, r), c in sorted(refused.items())],
                                         "finalCounts": counts, "policy": dict(("%s -> %s" % k, v) for k, v in reasons.items())}
        self.report_data["decisions"]["incompleteCodeTables"] = list(self.incomplete_tables)
        if self.incomplete_tables:
            self.rep.info("5", "incomplete code table(s) rejected, not accepted", "%d table(s) with codes still lacking a target: %s" % (len(self.incomplete_tables), "; ".join(self.incomplete_tables)))
        self.rep.info("5", "flagged queue decided", "accepted %d, rejected %d, refused by the rules %d" % (tally["ACCEPTED"], tally["REJECTED"], sum(refused.values())))
        self.rep.check("5", "nothing is left to decide", not left["lines"], "%d line(s) in the project, none PROPOSED" % sum(counts.values()),
                       "%d line(s) still PROPOSED: %s" % (len(left["lines"]), ", ".join(sorted(set("%s:%s" % (l["nature"], l["element"]) for l in left["lines"]))[:8])))

    def provide_parameters(self):
        resp, rows = self.ds.select("CapabilitySuggestion", "./project='%s' and ./nature='SCRIPT_DERIVATION'" % self.code)
        done, other = [], []
        for s in rows:
            if L.text(s.get("status")) in ("REJECTED", "SUPERSEDED"):
                continue
            for name in L.to_be_provided(L.text(s.get("binding"))):
                if name == "defaultCountry":
                    r = self.post_put("/projects/%s/capabilities/%s/binding" % (self.code, s["id"]), {"defaultCountry": "FR"})
                    if r.status == 200:
                        done.append(L.text(s["id"]))
                    else:
                        self.rep.failed("5", "defaultCountry provided on suggestion %s" % s["id"], "HTTP %d %s" % (r.status, clip(r.error_text())))
                else:
                    other.append("%s on suggestion %s" % (name, s["id"]))
        self.rep.info("5", "derivation parameters", "defaultCountry=FR provided on %d suggestion(s)%s" % (len(done), "; left to a person: " + ", ".join(other) if other else ""))

    def post_put(self, tail, body):
        return self.admin.request("PUT", self.rest + tail, body)

    def check_constraints_applied(self):
        # integration 2026-10-17: the staging load applies the accepted STAGING-zone constraints itself (ebx.ConstraintAutoApply);
        # accepted before the model existed, they used to stay ACCEPTED forever.
        # lot M4 (2026-10-18, point C): one the load CANNOT apply (the model left a parameter "to be provided": CapabilitySuggestion 333 and 351 of the automated test) stays ACCEPTED
        # on purpose, but the row must say why (applyMessage); a row left ACCEPTED with an empty applyMessage is the defect - nothing tells the person what to do.
        resp, rows = self.ds.select("CapabilitySuggestion", "./project='%s' and ./nature='SCRIPT_CONSTRAINT'" % self.code)
        left = [r for r in rows if L.text(r.get("status")) == "ACCEPTED" and L.text(r.get("zone")) in ("", "STAGING")]
        silent = [L.text(r["id"]) for r in left if not L.text(r.get("applyMessage")).strip()]
        explained = ["%s: %s" % (L.text(r["id"]), clip(L.text(r.get("applyMessage")), 140)) for r in left if L.text(r.get("applyMessage")).strip()]
        applied = [L.text(r["id"]) for r in rows if L.text(r.get("status")) == "APPLIED"]
        self.rep.check("6", "accepted script constraints applied by the load, or the reason said on the row", not silent,
                       "%d applied%s" % (len(applied), "; left ACCEPTED with the reason on the row - " + " | ".join(explained) if explained else ""),
                       "still ACCEPTED with no applyMessage (the load did not say why): %s" % ", ".join(silent))
        for line in explained:
            self.rep.info("6", "an accepted script constraint the load could not apply", line)

    # ------------------------------------------------------------------------------------------------------------------ 6. build, load, verify
    def step6(self):
        if self.have_preflight:
            self.preflight()
        else:
            self.rep.skipped("6", "staging preflight", "the route GET /projects/{code}/staging/preflight does not exist on this build (lot H1 not merged)")
        r = self.post("/projects/%s/staging/generate" % self.code, slow=True)
        data = r.json()
        self.rep.check("6", "staging model and dataset built", r.status == 200, clip(json.dumps(data), 160), "HTTP %d %s" % (r.status, clip(r.error_text(), 300)))
        if r.status != 200:
            raise Stop()
        self.start_job("6", "staging load (every row)", "/projects/%s/staging/load?scope=all" % self.code, self.timeout("BUILD", 1200), "the staging load")
        self.verify()

    def preflight(self):
        # integration 2026-10-17: lot H1's POST runs a fresh check and answers the report (blocking, blockingRows, blockingLinks…)
        r = self.post("/projects/%s/staging/preflight" % self.code)
        data = r.json()
        bad = r.status != 200 or (isinstance(data, dict) and (data.get("blocking") is True or data.get("ready") is False or data.get("ok") is False))
        self.rep.check("6", "staging preflight", not bad, clip(json.dumps(data), 200), "HTTP %d %s" % (r.status, clip(r.text, 400)))
        if bad:
            raise Stop()

    def table_path(self, entity):
        target = L.text(entity.get("targetTable"))
        return target.rsplit("/", 1)[-1] if target else L.text(entity.get("name"))

    def verify(self):
        self.check_constraints_applied()
        resp, project = self.ds.get_row("Project", self.code)
        space, dataset = L.text(project.get("stagingDataspace")), L.text(project.get("stagingDataset"))
        if not space or not dataset:
            self.rep.failed("6", "staging dataset recorded on the project", "stagingDataspace/stagingDataset empty")
            raise Stop()
        self.stg = client.Ds(self.admin, space, dataset)
        resp, entities = self.ds.select("StagingEntity", "./project='%s'" % self.code)
        active = [e for e in entities if L.text(e.get("status")) not in ("SUPERSEDED", "REJECTED", "PROPOSED")]
        self.rep.check("6", "built entities", bool(active), ", ".join(L.text(e.get("name")) for e in active), "no entity is ACCEPTED/APPLIED after the load")
        resp, rejects = self.ds.select("TransferReject", "./project='%s'" % self.code)
        rejects_by_entity = {}
        for rj in rejects:
            rejects_by_entity[L.text(rj.get("entity"))] = rejects_by_entity.get(L.text(rj.get("entity")), 0) + 1
        person_id = L.text(self.person["id"])
        rows_of = {}
        for e in active:
            name = L.text(e.get("name"))
            resp, rows = self.stg.select(self.table_path(e), None, page_size=500, max_pages=100)
            if resp.status != 200:
                self.rep.failed("6", "%s: staging table readable" % name, "HTTP %d %s" % (resp.status, clip(resp.error_text())))
                continue
            rows_of[L.text(e["id"])] = rows
            written = L.to_int(e.get("lastLoadWritten")) + L.to_int(e.get("lastLoadUnchanged"))
            if L.no_feed(e):
                # lot M7 (2026-10-18): an accepted entity no accepted mapping feeds - the load says so on the row; the model's variance (ContactEmail on
                # E2EMASTER20261017235630), not the engine, is what left it empty: information, not a failure
                self.rep.info("6", "%s: no feed (nothing loaded, said on the row)" % name, "%d row(s) in the table, last load: %s" % (len(rows), clip(L.text(e.get("lastLoadMessage")), 160)))
                continue
            self.rep.check("6", "%s: rows written" % name, len(rows) > 0 and written > 0, "%d row(s) in the table, %d written/unchanged by the last load" % (len(rows), written),
                           "%d row(s) in the table, last load: %s" % (len(rows), clip(L.text(e.get("lastLoadMessage")), 160)))
            self.rep.check("6", "%s: 0 TransferReject" % name, rejects_by_entity.get(L.text(e["id"]), 0) == 0 and L.to_int(e.get("lastLoadRejected")) == 0,
                           "none", "%d reject row(s), last load rejected %s" % (rejects_by_entity.get(L.text(e["id"]), 0), L.text(e.get("lastLoadRejected"))))
        rows = rows_of.get(person_id)
        if rows is None:
            self.rep.failed("6", "person entity rows readable", "the person entity %s has no readable table" % L.text(self.person.get("name")))
            return
        self.rep.check("6", "person entity holds one row per source row (534)", len(rows) == L.EXPECTED_PERSON_ROWS, "%d rows" % len(rows), "%d row(s), expected %d" % (len(rows), L.EXPECTED_PERSON_ROWS))
        self.verify_person(rows)

    def attribute_for(self, roles):
        """The person attribute fed by a column of one of `roles` (first live mapping), or None."""
        by_id = dict((L.text(a["id"]), a) for a in self.attributes)
        for m in self.mappings_reading(roles):
            attr = by_id.get(L.text(m.get("attribute")))
            if attr is not None and L.live(attr):
                return attr
        return None

    expected_birth_dates = L.EXPECTED_BIRTH_DATES
    VERIFY_STEP = "6"      # the step the checks of verify_person are filed under (the hard scenario verifies in step 7)

    def verify_person(self, rows):
        birth = self.attribute_for(L.BIRTH_COLUMNS)
        phone = self.attribute_for(L.PHONE_COLUMNS)
        if birth is None:
            self.rep.failed(self.VERIFY_STEP, "birth date attribute", "no live mapping reads a birth date column")
        else:
            n = L.fill_count(rows, L.text(birth.get("name")))
            self.rep.check(self.VERIFY_STEP, "birth dates filled (%d)" % self.expected_birth_dates, n == self.expected_birth_dates, "%d of %d rows (%s)" % (n, len(rows), L.text(birth.get("name"))),
                           "%d row(s) have %s, expected %d" % (n, L.text(birth.get("name")), self.expected_birth_dates))
        if phone is None:
            self.rep.failed(self.VERIFY_STEP, "phone attribute", "no live mapping reads a phone column")
            return
        pf = L.text(phone.get("name"))
        ecom = L.text(self.sources["ECOM"].get("sourceSystem"))
        bad = L.phone_plus_violations(rows, pf, "sourceSystem", ecom)
        self.rep.check(self.VERIFY_STEP, "phone keeps its '+' (web numbers)", not bad, "%s of the web source all start with +" % pf, "%d web row(s) lost the +, e.g. %s" % (len(bad), L.text(bad[0].get(pf)) if bad else ""))
        derived = L.derived_children(self.attributes, [L.text(phone["id"])])
        e164 = [a for a in derived if L.text(a.get("derivation")) == "SCRIPT"] or derived
        if not e164:
            self.rep.failed(self.VERIFY_STEP, "E.164 field exists and is filled on >= 95 % of rows with a phone", "no attribute is derived from %s" % pf)
        else:
            name = L.text(e164[0].get("name"))
            with_phone, filled, odd = L.e164_report(rows, pf, name)
            rt = L.rate(filled, with_phone)
            self.rep.check(self.VERIFY_STEP, "E.164 field %s filled on >= 95 %% of rows with a phone" % name, rt >= L.E164_MIN_RATE and odd == 0,
                           "%d of %d (%.1f %%)" % (filled, with_phone, rt * 100), "%d of %d filled (%.1f %%), %d value(s) not starting with +" % (filled, with_phone, rt * 100, odd))
        self.check_phone_per_source(rows, pf)
        by_attr = set(L.text(m.get("attribute")) for m in self.mappings_reading(L.NAME_COLUMNS))
        sources = [L.text(a.get("name")) for a in self.attributes if L.text(a["id"]) in by_attr]
        # lot M11 (2026-10-18): a name key is found by what computes it - a SCRIPT derivation of the names library (Fasttrack.lib.names), whoever proposed it (the Factory's rule or the
        # model) and whatever its function and its attribute are called - and, as before, by being derived from a mapped name column or by a name-and-key attribute name
        resp, suggestions = self.ds.select("CapabilitySuggestion", "./project='%s' and ./nature='SCRIPT_DERIVATION'" % self.code)
        by_library = L.name_derivation_attributes(self.attributes, suggestions if resp.status == 200 else [])
        by_library_ids = set(L.text(a["id"]) for a in by_library)
        name_attrs = [a for a in self.attributes if L.live(a) and (L.text(a["id"]) in by_library_ids or L.text(a.get("derivedFrom")) in by_attr or (
            L.text(a.get("derivation")) and re.search(r"name.*key|key.*name", L.text(a.get("name")), re.IGNORECASE)))]
        if not name_attrs:
            # integration 2026-10-17: whether the model proposes a name key varies from run to run - its absence is noted, not a failure (since lot M11 the Factory's own rule proposes one
            # whenever the names library is published and the entity has a first and a last name, so its absence now points at that rule: the step detail says why)
            self.rep.skipped(self.VERIFY_STEP, "name keys filled", "no attribute is computed by the names library nor derived from a first or last name in this run")
        else:
            for a in name_attrs:
                named, filled = L.key_report(rows, L.text(a.get("name")), sources)
                rt = L.rate(filled, named)
                self.rep.check(self.VERIFY_STEP, "name key %s filled on >= 95 %% of rows with a name" % L.text(a.get("name")), rt >= L.NAME_KEY_MIN_RATE,
                               "%d of %d (%.1f %%)" % (filled, named, rt * 100), "%d of %d filled (%.1f %%)" % (filled, named, rt * 100))

    def check_phone_per_source(self, rows, phone_field):
        """Lot M15 (2026-10-18, E2EMASTER20261018090711): the phone of EVERY source whose landing holds a filled phone column reaches the person entity. The E.164 check above
        counts only rows that have a phone, so the 234 STORES rows - whose TELEPHONE was mapped only to the superseded per-source entity, never to Client.mobile - were not
        in its count and it passed. FAIL: a source with rows in the entity and not one filled phone."""
        source_system_of = dict((app, L.text(s.get("sourceSystem")) or app) for app, s in self.sources.items())
        # the columns were read BEFORE the landing load (settle_key), when no fill rate is measured yet: read them again, the measured rate decides (lot M16)
        measured = {}
        for app, table in self.tables.items():
            resp, cols = self.ds.select("LandingColumn", "./landingTable='%s'" % L.text(table.get("id")))
            measured[L.text(table.get("id"))] = cols if resp.status == 200 and cols else self.columns.get(table.get("id"), [])
        phone_sources = L.phone_source_systems(self.tables, measured, source_system_of)
        step = self.VERIFY_STEP
        name = "phone reaches the person entity from every source whose landing holds a phone column"
        if not phone_sources:
            self.rep.skipped(step, name, "no landing column of a source is a filled phone column")
            return
        missing = L.sources_missing_phone(rows, phone_field, "sourceSystem", sorted(phone_sources))
        report = L.phone_by_source(rows, phone_field, "sourceSystem", sorted(phone_sources))
        self.rep.check(step, name, not missing, "; ".join("%s %d of %d rows (%s)" % (src, filled, n, ", ".join(phone_sources[src])) for src, n, filled in report),
                       "; ".join("%s: %d row(s) in %s, none with a phone although its landing column %s is filled - map it to the entity or say why"
                                 % (src, n, phone_field, ", ".join(phone_sources[src])) for src, n, _f in missing))

    # ------------------------------------------------------------------------------------------------------------------ cleanup
    def cleanup(self):
        try:
            self.cleanup_project()
        finally:
            self.connection_leftovers()

    def connection_leftovers(self):
        """What the upload created and the project deletion did NOT remove, read from the instance after the deletion (the deletion may or may not remove the
        sample-file connections and their catalogue sources, depending on the build): a connection of this run still listed by GET sources/onboarding/connections is
        left behind; one that is gone is not reported. When the listing cannot be read, what was created is reported as unverified."""
        r = self.get("/sources/onboarding/connections")
        data = r.json()
        if r.status != 200 or not isinstance(data, dict) or not isinstance(data.get("connections"), list):
            for cid, sid, name in self.uploaded:
                self.leftovers.append("Connectors connection %s / catalogue source %s (file %s): created by the upload; could not check whether the deletion removed it (HTTP %d)" %
                                      (cid or "?", sid or "?", name, r.status))
            return
        marker = self.suffix.lower()
        ours = set(cid for cid, _s, _n in self.uploaded if cid)
        for c in data["connections"]:
            ident, label = L.text(c.get("id")), L.text(c.get("label"))
            if ident in ours or marker in ident.lower() or marker in label.lower():
                source = L.text(c.get("catalogSourceId"))
                self.leftovers.append("Connectors connection %s%s: created by the upload, still there after the project deletion" %
                                      (ident, " and its catalogue source " + source if source else ""))

    def owned_name(self, name):
        """True when a deletion-plan item name is this run's: named after the project, after the run's file suffix (the sample-file connections and catalogue
        sources of the upload), or one of the ids the upload answered."""
        low = str(name).lower()
        return self.code.lower() in low or self.suffix.lower() in low or str(name) in set(i for c, s, _n in self.uploaded for i in (c, s) if i)

    def plan_item_owned(self, item):
        """Whether a deletion-plan item (other than a landing flow, a matching item and the like, handled in cleanup_project) is this run's: by name by default; a scenario that creates
        objects the plan names by a number (the master scenario's distribution flows are named by their Connectors mapping id) overrides it."""
        return self.owned_name(str(item.get("name", "")))

    def cleanup_project(self):
        flow_ids = set()
        resp, sources = self.ds.select("ProjectSource", "./project='%s'" % self.code)
        for s in sources:
            resp, tables = self.ds.select("LandingTable", "./projectSource='%s'" % s.get("id"))
            for t in tables:
                if t.get("inboundMapping"):
                    flow_ids.add(L.text(t["inboundMapping"]))
        r = self.get("/projects/%s/deletion/plan" % self.code)
        plan = r.json()
        if r.status != 200 or not isinstance(plan, dict):
            self.rep.failed("C", "deletion plan", "HTTP %d %s" % (r.status, clip(r.error_text())))
            self.leftovers.append("project %s and everything generated for it: delete it with the wizard's Delete project" % self.code)
            return
        foreign = None
        for item in plan.get("items") or []:
            name = str(item.get("name", ""))
            if item.get("kind") == "LANDING_FLOW":
                if name not in flow_ids:
                    foreign = "flow %s is not one of this run's landing flows" % name
            elif item.get("kind") in ("MATCHING_CONFIG", "MATCHING_BUSINESS_OBJECT") and name.startswith("/root/"):
                # lot M18: a matching item is named after a TABLE of the master model (/root/Client), not after the project; the plan is the project's own
                # (plan.project is checked below) and the same plan's master model / dataspace items are checked by name
                pass
            elif not self.plan_item_owned(item):
                foreign = "%s %s is not named after the project" % (item.get("kind"), name)
        if plan.get("project") != self.code or foreign:
            self.rep.failed("C", "deletion plan touches only this run's objects", foreign or "wrong project in the plan: NOT deleting")
            self.leftovers.append("project %s: deletion not run (plan not clean)" % self.code)
            return
        # the four options of the deletion screen, plus any other option the plan itself offers (a build that also removes connections or sources may name its own)
        options = ["matching", "flows", "dataspaces", "models"]
        for item in plan.get("items") or []:
            option = str(item.get("option") or "")
            if option and option not in options and re.match(r"^[A-Za-z]+$", option):
                options.append(option)
        r = self.post("/projects/%s/delete?confirm=%s&generated=%s" % (self.code, urllib.parse.quote(self.code), ",".join(options)))
        data = r.json()
        if r.status != 202 or not isinstance(data, dict) or not data.get("jobId"):
            self.rep.failed("C", "deletion queued", "HTTP %d %s" % (r.status, clip(r.error_text())))
            self.leftovers.append("project %s: deletion not queued" % self.code)
            return
        try:
            job = self.wait_job(str(data["jobId"]), self.timeout("DELETE", 1200), "the project deletion")
        except L.Timeout as exc:
            self.rep.failed("C", "deletion ended", str(exc))
            self.leftovers.append("project %s: deletion job %s did not end" % (self.code, data["jobId"]))
            return
        self.rep.check("C", "project deleted with its generated objects", job.get("status") == "DONE", "%d object(s)" % len(plan.get("items") or []),
                       "%s %s" % (job.get("status"), clip(job.get("message", ""), 200)))
        if job.get("status") == "DONE":
            models = [str(i.get("name")) for i in plan.get("items") or [] if i.get("kind") == "MASTER_MODEL"]
            self.matching_rows_left(models + [self.code + "_Master"])

    def matching_rows_left(self, models):
        """Lot M18 (2026-10-18): after the deletion, the Match and Merge add-on's own configuration dataset (Bebx-addon-mame) must hold NO row of this project's master
        publication: a business-object configuration left without its model breaks the add-on at the next start. Read from GET /matching/orphans?publication=<model>
        (the add-on's rows of that publication, whatever the state of its model); a build without the route is reported as not checked, never as clean."""
        seen = []
        for model in models:
            if model in seen:
                continue
            seen.append(model)
            r = self.get("/matching/orphans?publication=%s" % urllib.parse.quote(model))
            data = r.json()
            name = "the add-on's configuration dataset holds no row of %s" % model
            if r.status == 404:
                self.rep.skipped("C", name, "GET /matching/orphans is not on this build: not checked")
                self.leftovers.append("Match and Merge configuration rows of %s: could not check (GET /matching/orphans missing)" % model)
                continue
            if r.status != 200 or not isinstance(data, dict) or not isinstance(data.get("publications"), list):
                self.rep.failed("C", name, "HTTP %d %s" % (r.status, clip(r.error_text())))
                continue
            rows = []
            for p in data["publications"]:
                if str(p.get("publication")) != model:
                    continue
                rows += ["business object configuration %s (%s)" % (b.get("id"), b.get("mainTable")) for b in p.get("businessObjects") or []]
                rows += ["table configuration %s (%s)" % (t.get("id"), t.get("table")) for t in p.get("tableConfigurations") or []]
            if self.rep.check("C", name, not rows, "nothing left", "%d row(s) left: %s" % (len(rows), "; ".join(rows[:6]))):
                continue
            self.leftovers.append("Match and Merge configuration rows of %s (%d): clean them with POST /matching/orphans/delete" % (model, len(rows)))

    # ------------------------------------------------------------------------------------------------------------------ run
    def step_functions(self):
        return [(1, self.step1), (2, self.step2), (3, self.step3), (4, self.step4), (5, self.step5), (6, self.step6)]

    def resume(self, from_step):
        """--from-project: rebuilds, from the Factory's tables, the state the steps before `from_step` leave in this object, and refuses (ResumeRefused) when the project is not in the state
        step `from_step` starts from. Only a scenario with CAN_RESUME implements it."""
        raise ResumeRefused("this scenario cannot resume an existing project")

    def run(self):
        through = self.args["through"]
        from_step = self.args.get("from_step") if self.args.get("from_project") else None
        t_run = time.time()
        code_exit = 0
        try:
            self.step0()
            if from_step:
                self.resume(from_step)
            for n, fn in self.step_functions():
                if from_step and n < from_step:
                    self.rep.skipped(str(n), "step %d (%s)" % (n, self.STEP_NAMES[n]), "resumed from project %s at step %d" % (self.code, from_step))
                    continue
                if n > through:
                    self.rep.skipped(str(n), "step %d (%s)" % (n, self.STEP_NAMES[n]), "--through %d" % through)
                    continue
                t0 = time.time()
                self.out.write("\n== step %d: %s\n" % (n, self.STEP_NAMES[n]))
                fn()
                self.timings["step%d" % n] = int(time.time() - t0)
                self.out.write("note  step %d took %d s\n" % (n, self.timings["step%d" % n]))
        except MissingRoute:
            code_exit = 3
        except ResumeRefused as exc:
            self.out.write("\nRESUME REFUSED: %s\nNothing was run.\n" % exc)
            code_exit = 2
        except Stop:
            pass
        except KeyboardInterrupt:
            self.rep.failed("-", "run interrupted", "cleaning up")
        except client.SafetyError as exc:
            self.rep.failed("-", "stopped by the safety guard", str(exc))
        except Exception as exc:
            self.rep.failed("-", "scenario crashed", "%s: %s" % (type(exc).__name__, exc))
        finally:
            if self.args["cleanup"] and self.created:
                try:
                    self.out.write("\n== cleanup\n")
                    self.cleanup()
                except Exception as exc:
                    self.rep.failed("C", "cleanup crashed", "%s: %s" % (type(exc).__name__, exc))
            elif self.created:
                self.leftovers.append("project %s and its landing/staging dataspaces and models (%s)" % (self.code, self.KEEP_HINT))
                for cid, sid, name in self.uploaded:
                    self.leftovers.append("Connectors connection %s / catalogue source %s (file %s): created by the upload" % (cid or "?", sid or "?", name))
            if self.workdir and not self.args["keep_files"]:
                shutil.rmtree(self.workdir, ignore_errors=True)
        return self.finish(t_run, code_exit)

    def finish(self, t_run, code_exit=0):
        self.timings["total"] = int(time.time() - t_run)
        self.out.write(self.rep.table() + "\n")
        if self.leftovers:
            self.out.write("\nLEFT BEHIND:\n" + "".join("  - %s\n" % l for l in sorted(set(self.leftovers), key=self.leftovers.index)))
        self.epilogue()
        self.write_report()
        counts = self.rep.counts()
        self.out.write("total %d s\n" % self.timings["total"])
        if code_exit:
            return code_exit
        return 1 if counts["FAIL"] else 0

    def epilogue(self):
        """Printed after the table and the left-behind list (the hard scenario prints the project and the addresses of the pages to look at)."""

    def run_cleanup_only(self):
        """--cleanup-project CODE: the deletion of a project an earlier run left in place (the same plan check, deletion and left-behind reading as --cleanup)."""
        t_run = time.time()
        code_exit = 0
        try:
            self.step0()
            resp, row = self.ds.get_row("Project", self.code)
            if resp.status != 200 or row is None:
                self.rep.failed("C", "project %s exists" % self.code, "HTTP %d: nothing to delete" % resp.status)
                raise Stop()
            self.created = True
            self.out.write("\n== cleanup\n")
            self.cleanup()
        except MissingRoute:
            code_exit = 3
        except Stop:
            pass
        except client.SafetyError as exc:
            self.rep.failed("-", "stopped by the safety guard", str(exc))
        except Exception as exc:
            self.rep.failed("C", "cleanup crashed", "%s: %s" % (type(exc).__name__, exc))
        return self.finish(t_run, code_exit)

    def write_report(self):
        path = self.args["report"] or os.path.join(self.env.get("E2E_OUT_DIR", "e2e-out"), "%s-%s.json" % (self.REPORT_NAME, self.code))
        counts = self.rep.counts()
        doc = {"scenario": self.REPORT_NAME, "project": self.code, "base": self.env.get("EBX_URL", ""), "when": datetime.datetime.now(datetime.timezone.utc).isoformat(),
               "summary": {"passed": counts["PASS"], "failed": counts["FAIL"], "skipped": counts["SKIP"]}, "seconds": self.timings, "checks": self.rep.checks,
               "jobs": self.report_data["jobs"], "decisions": self.report_data["decisions"], "routes": self.report_data["routes"], "version": self.report_data.get("version"),
               "leftBehind": self.leftovers, "details": self.report_data.get("details", {})}
        try:
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(doc, fh, indent=1, ensure_ascii=False)
            self.out.write("report written: %s\n" % path)
        except OSError as exc:
            self.out.write("report NOT written (%s)\n" % exc)


def dry_run(out):
    work = tempfile.mkdtemp(prefix="b2c-e2e-dry-")
    try:
        paths = generate_files(work)
        problems, counts = check_files(paths)
        out.write("files generated by gen_b2c.py (seeded): %s\n" % ", ".join("%s %d" % (a, counts.get(a, -1)) for a in ("ECOM", "STORES", "LOYALTY")))
        for p in problems:
            out.write("PROBLEM %s\n" % p)
        out.write("\nplan (nothing is sent):\n")
        for n, name in sorted(STEPS.items()):
            out.write("  %d. %s\n" % (n, name))
        out.write("\nroutes needed:\n")
        for method, template, _b, label in REQUIRED_ROUTES + OPTIONAL_ROUTES:
            out.write("  %-4s %-48s %s\n" % (method, template, label))
        return 1 if problems else 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main(argv, env, out=None, cls=None, max_step=6, dry=None, doc=None, flags=None):
    """The command line of a scenario: `cls` is the Scenario class to run (the hard cases subclass it), `max_step` its last step, `dry` its --dry-run, `doc` its --help text, `flags` {"--name": "args key"} the boolean options of that scenario only."""
    out = out or sys.stdout
    cls = cls or Scenario
    args = {"cleanup": False, "through": max_step, "report": None, "keep_files": False, "cleanup_project": None, "from_project": None, "from_step": None}
    flags = flags or {}
    for key in flags.values():
        args[key] = False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--selftest":
            import b2c_selftest as selftest
            return selftest.run(out)
        if a == "--dry-run":
            return (dry or dry_run)(out)
        if a == "--cleanup":
            args["cleanup"] = True
        elif a == "--keep-files":
            args["keep_files"] = True
        elif a in flags:
            args[flags[a]] = True
        elif a == "--through" and i + 1 < len(argv) and argv[i + 1].isdigit() and 1 <= int(argv[i + 1]) <= max_step:
            args["through"] = int(argv[i + 1])
            i += 1
        elif a == "--report" and i + 1 < len(argv):
            args["report"] = argv[i + 1]
            i += 1
        elif a == "--cleanup-project" and i + 1 < len(argv):
            args["cleanup_project"] = argv[i + 1]
            i += 1
        elif cls.CAN_RESUME and a == "--from-project" and i + 1 < len(argv):
            args["from_project"] = argv[i + 1]
            i += 1
        elif cls.CAN_RESUME and a == "--from-step" and i + 1 < len(argv) and argv[i + 1].isdigit() and 1 <= int(argv[i + 1]) <= max_step:
            args["from_step"] = int(argv[i + 1])
            i += 1
        elif a in ("-h", "--help"):
            out.write(doc or __doc__)
            return 0
        else:
            sys.stderr.write("unexpected argument: %s (see --help)\n" % a)
            return 2
        i += 1
    given = args["cleanup_project"]
    if given is not None and not (re.match(r"^[A-Za-z0-9]+$", given) and given.startswith(cls.CODE_PREFIX) and len(given) > len(cls.CODE_PREFIX)):
        sys.stderr.write("--cleanup-project takes the code of a project this scenario created (it starts with %s)\n" % cls.CODE_PREFIX)
        return 2
    resumed = args["from_project"]
    if resumed is not None or args["from_step"] is not None:
        if resumed is None or args["from_step"] is None:
            sys.stderr.write("--from-project CODE and --from-step N go together (the project to continue and the step, 2..%d, to start from; see --help)\n" % max_step)
            return 2
        if not (re.match(r"^[A-Za-z0-9]+$", resumed) and resumed.startswith(cls.CODE_PREFIX) and len(resumed) > len(cls.CODE_PREFIX)):
            sys.stderr.write("--from-project takes the code of a project this scenario created (it starts with %s)\n" % cls.CODE_PREFIX)
            return 2
        if args["from_step"] < 2:
            sys.stderr.write("--from-step starts at 2 or later: step 1 creates the project, and --from-project continues one that exists\n")
            return 2
        if args["from_step"] > args["through"]:
            sys.stderr.write("--from-step %d is after --through %d: nothing to run\n" % (args["from_step"], args["through"]))
            return 2
        if given is not None:
            sys.stderr.write("--cleanup-project deletes a project and cannot be combined with --from-project\n")
            return 2
        given_code = resumed
    else:
        given_code = given
    sc = cls(env, out, args, code=given_code)
    if not sc.connect():
        return 2
    if given is not None:
        out.write("Deleting project %s (and what it generated) on %s\n" % (sc.code, sc.base))
        return sc.run_cleanup_only()
    out.write("%s against %s (module %s), project %s%s\n" % (cls.TITLE, sc.base, env.get("EBX_MODULE", "ebx-mdm-factory"), sc.code,
                                                              " (resumed at step %d)" % args["from_step"] if resumed else ""))
    return sc.run()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:], os.environ))
