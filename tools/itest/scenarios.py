"""The scenarios of the MDM Factory integration suite (audit F-05). See docs/OPERATIONS.md, section "Integration tests".

Everything the suite writes carries the run's prefix (ITEST<timestamp>) and is removed at the end; see client.Guard for the
rule that stops the suite from touching anything else.
"""
import base64
import json
import time
import urllib.parse

from client import clip, Ds

TERMINAL_JOB = ("DONE", "FAILED", "CANCELLED", "INTERRUPTED")

# One representative route per family of the REST surface, with the right it asks (core/FactoryRights.java; test_pure.py
# compares this table with that file). {x} = a project code that does not exist, {job} = a job id that does not exist.
FAMILIES = [
    ("projects read (jobs)", "GET", "/projects/{x}/jobs", "VIEW"),
    ("matching activity (read)", "GET", "/projects/{x}/matching/activity", "VIEW"),
    ("lineage", "GET", "/projects/{x}/lineage/Entity?key=1", "VIEW"),
    ("cross-references", "GET", "/projects/{x}/xrefs/Entity?key=1", "VIEW"),
    ("intake status", "GET", "/projects/{x}/intake", "VIEW"),
    ("design action (staging proposal)", "POST", "/projects/{x}/staging/suggest", "DESIGN"),
    ("matching run", "POST", "/projects/{x}/matching/run", "DESIGN"),
    ("distribution", "GET", "/projects/{x}/distribution/targets", "DESIGN"),
    ("intake control", "POST", "/projects/{x}/intake/process", "DESIGN"),
    ("jobs (cancel)", "POST", "/jobs/{job}/cancel", "STEWARD"),
    ("deletion plan", "GET", "/projects/{x}/deletion/plan", "ADMIN"),
    ("deletion", "POST", "/projects/{x}/delete?confirm={x}", "ADMIN"),
    ("step reset", "POST", "/projects/{x}/reset?from=STAGING&confirm={x}", "ADMIN"),
    ("master reset", "POST", "/projects/{x}/master/reset?confirm={x}", "ADMIN"),
    ("delivery domain", "GET", "/projects/{x}/delivery/domain", "ADMIN"),
    ("connection test", "GET", "/connection/test", "ADMIN"),
]
# the routes the right-by-level scenario watches (indexes into FAMILIES are avoided on purpose: each is named)
VIEW_ROUTE = ("GET", "/projects/{x}/jobs")
STEWARD_ROUTE = ("POST", "/jobs/{job}/cancel")
DESIGN_ROUTE = ("POST", "/projects/{x}/staging/suggest")
ADMIN_ROUTES = [("GET", "/projects/{x}/deletion/plan"), ("POST", "/projects/{x}/delete?confirm={x}"), ("GET", "/projects/{x}/reset/plan"),
                ("POST", "/projects/{x}/master/reset?confirm={x}")]

# a read route that finds no such project answers one of these (the gate is open); a closed gate answers 403
OPEN_GATE_CODES = (200, 202, 400, 404, 409, 422)


class Ctx:
    """What the scenarios share."""

    def __init__(self, rep, base, module, admin, norole, anon, prefix, guard, env):
        self.rep = rep
        self.base = base
        self.module = module
        self.admin = admin
        self.norole = norole
        self.anon = anon
        self.prefix = prefix
        self.guard = guard
        self.env = env
        self.rest = "/%s/rest/v1" % module
        self.x = prefix + "NOSUCH"       # a project code that never exists
        self.job = "0"                   # a job id that never exists (ids start at 1); the one POST that uses it is allowed by name
        self.scratch = prefix + "P"      # the scratch project of scenario 4
        self.full = prefix + "F"         # the scratch project of scenario 5
        self.full_created = False
        self.dataspace = None
        self.dataset = None
        self.ds = None
        self.status = None               # the administrator's /status answer
        self.status_resp = None
        self.admin_ok = False
        self.norole_gated = None         # None = not established, True = the account is refused by the module's own gate
        self.cleanups = []               # (label, callable) run in reverse order at the end
        self.created_right_row = False
        self.scratch_created = False
        self.scratch_deleted = False
        self.delete_tried = False
        self.leftovers = []              # human-readable lines for the final report
        if guard is not None:
            guard.allow_path("/%s/rest/v1/jobs/%s/cancel" % (module, self.job))

    def path(self, template):
        return self.rest + template.replace("{x}", self.x).replace("{job}", self.job)


# ------------------------------------------------------------------------------------------------------------- helpers

def classify_gate(resp, right):
    """('PASS'|'FAIL'|'SKIP', detail) for a response that must be the module's own refusal naming `right` (VIEW, STEWARD, DESIGN, ADMIN)."""
    if resp.status == 0:
        return "FAIL", "no answer (%s)" % clip(resp.text, 120)
    if resp.status == 401:
        return "FAIL", "HTTP 401: the account was not authenticated (check the credentials)"
    if resp.status != 403:
        return "FAIL", "HTTP %d, expected 403 (%s)" % (resp.status, clip(resp.error_text(), 120))
    data = resp.json()
    error = data.get("error") if isinstance(data, dict) else None
    if not isinstance(error, str):
        low = (resp.text or "").lower()
        why = "EBX's own dataspace/dataset permission refusal" if ("dataspace" in low or "dataset" in low or "permission" in low) \
            else "a 403 that is not the module's gate"
        return "SKIP", "%s (body: %s)" % (why, clip(resp.text, 120))
    if not error.startswith("Access denied"):
        return "FAIL", "403 with an unexpected message: %s" % clip(error, 160)
    if right == "ADMIN":
        ok = "administrators only" in error
    else:
        ok = ("MDM Factory right " + right) in error
    if not ok:
        return "FAIL", "403 but the message does not name the right %s: %s" % ("administrator" if right == "ADMIN" else right, clip(error, 160))
    return "PASS", "HTTP 403, " + ("administrators only" if right == "ADMIN" else "right " + right)


def gate_open(resp):
    """True when a response shows the module's gate let the caller in (anything but a refusal)."""
    return resp.status not in (0, 401, 403)


def poll_interval(ctx, default):
    return float(ctx.env.get("ITEST_POLL_INTERVAL", str(default)))


def settle_timeout(ctx):
    """How long a change of the Access rights table may take to show (the module caches it for 5 s)."""
    return float(ctx.env.get("ITEST_SETTLE_TIMEOUT", "45"))


def poll(fn, timeout, interval=1.5):
    """Calls fn() until it returns something truthy; returns that value, or None after `timeout` seconds."""
    end = time.time() + timeout
    while True:
        value = fn()
        if value:
            return value
        if time.time() >= end:
            return None
        time.sleep(interval)


def wait_job(ctx, job_id, timeout):
    """Polls FactoryJob/<id> through Data Services until it ends; returns its field map, or None on timeout."""
    def look():
        resp, fields = ctx.ds.get_row("FactoryJob", job_id)
        if fields and fields.get("status") in TERMINAL_JOB:
            return fields
        return None
    return poll(look, timeout, poll_interval(ctx, 2.0))


def select_count(ctx, table, xpath):
    resp, rows = ctx.ds.select(table, xpath)
    return resp, len(rows), rows


# ----------------------------------------------------------------------------------------------- 1. status and liveness

def load_admin_status(ctx):
    """Reads /status as the administrator and fills the shared context (module status, dataspace, dataset, Data Services handle); None when it fails."""
    ctx.status_resp = ctx.admin.get(ctx.rest + "/status")
    data = ctx.status_resp.json()
    if ctx.status_resp.status != 200 or not isinstance(data, dict):
        return None
    ctx.status = data
    ctx.admin_ok = True
    ctx.dataspace = data.get("dataspace")
    ctx.dataset = data.get("dataset")
    if ctx.dataspace and ctx.dataset:
        ctx.ds = Ds(ctx.admin, ctx.dataspace, ctx.dataset)
    return data


def scenario_status(ctx):
    rep = ctx.rep
    rep.section("1. Status and liveness")
    r = ctx.anon.get("/ebx/")
    if r.status in (200, 301, 302, 303, 307):
        rep.passed("GET /ebx/", "HTTP %d" % r.status)
    else:
        rep.failed("GET /ebx/", "HTTP %d: EBX does not answer here" % r.status)
    r = ctx.anon.get(ctx.rest + "/status")
    data = r.json() if r.status == 200 else None
    if r.status in (401, 403):
        rep.passed("status without credentials", "HTTP %d" % r.status)
    elif r.status == 200 and isinstance(data, dict) and "datasetState" not in data and "version" not in data:
        rep.passed("status without credentials", "liveness only")
    else:
        rep.failed("status without credentials", "HTTP %d and it may disclose installation state" % r.status)

    data = load_admin_status(ctx)
    r = ctx.status_resp
    if data is None:
        rep.failed("status as administrator", "HTTP %d %s" % (r.status, clip(r.text, 160)))
        return
    rep.passed("status as administrator", "HTTP 200")
    if data.get("module") == ctx.module:
        rep.passed("status names the module", data.get("module"))
    else:
        rep.failed("status names the module", "got %r" % data.get("module"))
    state = data.get("datasetState")
    if state == "VERIFIED":
        rep.passed("dataset state", "VERIFIED")
    else:
        rep.failed("dataset state", "%r (VERIFIED expected: installed on MdmFactory.xsd)" % state)
    version = data.get("version")
    if not version or version == "unknown":
        rep.failed("build version", "%r: version.properties is missing from the deployed classes" % version)
    else:
        rep.passed("build version", "%s (commit %s)" % (version, data.get("commit")))
    mint = data.get("sessionTokenMintable")
    if mint is False:
        rep.failed("sessionTokenMintable", "false: the internal token API changed on this EBX build")
    else:
        rep.passed("sessionTokenMintable", "true" if mint else "not reported")
    tables = data.get("tables")
    needed = ("Project", "ProjectSource", "PipelineStep", "FactoryJob", "AccessRight")
    if isinstance(tables, dict) and all(t in tables for t in needed):
        rep.passed("model tables counted", "%d tables" % len(tables))
    else:
        rep.failed("model tables counted", "the status answer lacks one of %s (a model older than audit F-02?)" % ", ".join(needed))

    if ctx.norole is None:
        rep.skipped("status as a non-administrator", "EBX_AUTH_NOROLE is not set")
        return
    r = ctx.norole.get(ctx.rest + "/status")
    data = r.json()
    if r.status == 200 and isinstance(data, dict) and data.get("alive") is True and not any(k in data for k in ("datasetState", "version", "tables", "user", "dataspace")):
        rep.passed("status as a non-administrator is liveness only", "HTTP 200")
    elif r.status == 401:
        rep.failed("status as a non-administrator", "HTTP 401: the EBX_AUTH_NOROLE credentials are refused")
    else:
        rep.failed("status as a non-administrator is liveness only", "HTTP %d %s" % (r.status, clip(r.text, 160)))


# ------------------------------------------------------------------------------- 2. one 403 per route family, no role

def scenario_no_role(ctx):
    rep = ctx.rep
    rep.section("2. Route families: an account with no Factory right is refused")
    if ctx.norole is None:
        rep.skipped("route families as a no-role account", "EBX_AUTH_NOROLE is not set")
        return
    refusals = []
    ebx_own = 0
    for label, method, template, right in FAMILIES:
        resp = ctx.norole.request(method, ctx.path(template))
        verdict, detail = classify_gate(resp, right)
        if verdict == "SKIP":
            ebx_own += 1
            rep.skipped("403 %s [%s %s]" % (label, method, template.split("?")[0]), detail)
        elif verdict == "PASS":
            refusals.append(label)
            rep.passed("403 %s [%s %s]" % (label, method, template.split("?")[0]), detail)
        else:
            rep.failed("403 %s [%s %s]" % (label, method, template.split("?")[0]), detail)
    ctx.norole_gated = len(refusals) > 0 and ebx_own == 0 and len(refusals) == len(FAMILIES)
    # the administrator gets past the same gates: the routes exist and the gate is open for him (a project that does not exist answers 404/409)
    if ctx.admin_ok:
        for label, method, template, right in FAMILIES:
            if method != "GET" or template == "/connection/test":
                continue   # a POST on a project that does not exist is harmless but pointless here; /connection/test probes the MCP servers
            resp = ctx.admin.request(method, ctx.path(template))
            if resp.status in OPEN_GATE_CODES:
                rep.passed("administrator passes the gate %s" % label, "HTTP %d" % resp.status)
            else:
                rep.failed("administrator passes the gate %s" % label, "HTTP %d %s" % (resp.status, clip(resp.error_text(), 120)))


# ----------------------------------------------------------------------------- 3. rights by level (temporary Everyone row)

def _levels_route(ctx, who, route):
    method, template = route
    return who.request(method, ctx.path(template))


def scenario_rights(ctx):
    rep = ctx.rep
    rep.section("3. Rights by level (a temporary 'Everyone' row of the table Access rights)")
    if ctx.env.get("ITEST_RIGHTS", "1") == "0":
        rep.skipped("rights by level", "ITEST_RIGHTS=0")
        return
    if ctx.norole is None:
        rep.skipped("rights by level", "EBX_AUTH_NOROLE is not set")
        return
    if ctx.ds is None:
        rep.skipped("rights by level", "no administrator status answer, so no dataset to write the row in")
        return
    if ctx.norole_gated is None:   # scenario 2 did not run: establish the baseline here
        ctx.norole_gated = classify_gate(_levels_route(ctx, ctx.norole, VIEW_ROUTE), "VIEW")[0] == "PASS"
    if ctx.norole_gated is not True:
        rep.skipped("rights by level", "the no-role account is not cleanly refused by the module's gate (scenario 2), so a level cannot be told from the baseline")
        return
    resp, existing = ctx.ds.get_row("AccessRight", "Beveryone")
    if resp.status == 200:
        rep.skipped("rights by level", "a row for Everyone already exists in Access rights: the suite never touches an existing row")
        return
    if resp.status not in (404,):
        rep.skipped("rights by level", "Data Services does not let the administrator read the table Access rights (HTTP %d %s)" % (resp.status, clip(resp.error_text(), 100)))
        return

    comment = "%s temporary row of the integration suite, removed at the end of the run" % ctx.prefix

    def remove_row():
        r = ctx.ds.delete("AccessRight", "Beveryone")
        gone = ctx.ds.get_row("AccessRight", "Beveryone")[0].status == 404   # a 200 answer alone proves nothing: read it back
        if gone:
            ctx.created_right_row = False
        return gone and r.status in (200, 204, 404)

    created = ctx.ds.create("AccessRight", {"profile": "Beveryone", "right": "VIEW", "comment": comment})
    if created.status not in (200, 201):
        rep.skipped("rights by level", "the administrator cannot create the row (HTTP %d %s)" % (created.status, clip(created.error_text(), 140)))
        return
    ctx.created_right_row = True
    ctx.guard.allow_path(ctx.ds.record("AccessRight", "Beveryone"))
    ctx.cleanups.append(("remove the temporary Everyone row", remove_row))
    rep.passed("temporary Everyone row created", "VIEW, comment starts with %s" % ctx.prefix)

    def set_right(right):
        r = ctx.ds.update("AccessRight", "Beveryone", {"right": right, "comment": comment})
        return r.status in (200, 204)

    def settle(route, expect_open, label):
        """Waits (the module caches the table for 5 s) until the route's gate is open/closed for the no-role account; returns the last response."""
        last = [None]

        def look():
            last[0] = _levels_route(ctx, ctx.norole, route)
            return gate_open(last[0]) == expect_open
        ok = poll(look, settle_timeout(ctx), poll_interval(ctx, 1.5))
        return ok, last[0]

    def expect(label, route, right_word, level):
        resp = _levels_route(ctx, ctx.norole, route)
        verdict, detail = classify_gate(resp, right_word)
        if verdict == "PASS":
            rep.passed("%s: %s refused" % (level, label), detail)
        else:
            rep.failed("%s: %s refused" % (level, label), detail)

    def expect_open(label, route, level):
        resp = _levels_route(ctx, ctx.norole, route)
        if gate_open(resp):
            rep.passed("%s: %s allowed" % (level, label), "HTTP %d (past the gate, no such project)" % resp.status)
        else:
            rep.failed("%s: %s allowed" % (level, label), "HTTP %d %s" % (resp.status, clip(resp.error_text(), 140)))

    try:
        # VIEW
        started = time.time()
        ok, last = settle(VIEW_ROUTE, True, "VIEW")
        if not ok:
            rep.failed("VIEW applies", "the read route stayed closed for %ds after the row was created (last: HTTP %s)" % (settle_timeout(ctx), last.status if last else "none"))
        else:
            rep.passed("VIEW applies", "after %.0f s" % (time.time() - started))
            for label, route in (("jobs read", VIEW_ROUTE), ("intake status", ("GET", "/projects/{x}/intake")), ("lineage read", ("GET", "/projects/{x}/lineage/Entity?key=1"))):
                expect_open(label, route, "VIEW")
            expect("design POST (staging proposal)", DESIGN_ROUTE, "DESIGN", "VIEW")
            expect("job cancel", STEWARD_ROUTE, "STEWARD", "VIEW")
        # STEWARD
        if not set_right("STEWARD"):
            rep.failed("STEWARD level", "the row could not be changed to STEWARD")
        else:
            started = time.time()
            ok, last = settle(STEWARD_ROUTE, True, "STEWARD")
            if not ok:
                rep.failed("STEWARD applies", "the job-cancel route stayed closed for %ds (last: HTTP %s)" % (settle_timeout(ctx), last.status if last else "none"))
            else:
                rep.passed("STEWARD applies", "after %.0f s" % (time.time() - started))
                expect_open("job cancel", STEWARD_ROUTE, "STEWARD")
                expect("design POST (staging proposal)", DESIGN_ROUTE, "DESIGN", "STEWARD")
                expect("deletion plan", ("GET", "/projects/{x}/deletion/plan"), "ADMIN", "STEWARD")
        # DESIGN
        if not set_right("DESIGN"):
            rep.failed("DESIGN level", "the row could not be changed to DESIGN")
        else:
            started = time.time()
            ok, last = settle(DESIGN_ROUTE, True, "DESIGN")
            if not ok:
                rep.failed("DESIGN applies", "the design route stayed closed for %ds (last: HTTP %s)" % (settle_timeout(ctx), last.status if last else "none"))
            else:
                rep.passed("DESIGN applies", "after %.0f s" % (time.time() - started))
                expect_open("design POST (staging proposal)", DESIGN_ROUTE, "DESIGN")
                for method, template in ADMIN_ROUTES:
                    expect("%s %s" % (method, template.split("?")[0]), (method, template), "ADMIN", "DESIGN")
    finally:
        done = remove_row()
        if done:
            ok = poll(lambda: not gate_open(_levels_route(ctx, ctx.norole, VIEW_ROUTE)), settle_timeout(ctx), poll_interval(ctx, 1.5))
            if ok:
                rep.passed("temporary Everyone row removed", "the no-role account is refused again")
            else:
                rep.failed("temporary Everyone row removed", "the row is gone but the read route still answers the no-role account after %ds (cache?)" % settle_timeout(ctx))
        else:
            rep.failed("temporary Everyone row removed", "COULD NOT DELETE the row 'Everyone' in Access rights: everybody keeps its right. Delete it by hand now.")
            ctx.leftovers.append("Access rights row 'Everyone' (comment starts with %s): delete it in the table Access rights of the MdmFactory dataset" % ctx.prefix)


# ------------------------------------------------------------------------------------------------ 4. scratch lifecycle

def _plan_problem(plan, code):
    """None when a deletion plan touches only the scratch project `code`, else the reason (pure; unit-tested)."""
    if not isinstance(plan, dict):
        return "the plan is not a JSON object"
    if plan.get("project") != code:
        return "the plan is for project %r, not %r" % (plan.get("project"), code)
    if plan.get("delivered") is True:
        return "the plan says the project is delivered"
    for item in plan.get("items") or []:
        name = str(item.get("name", ""))
        if code.lower() not in name.lower():
            return "the plan lists %s %r, which is not named after the scratch project" % (item.get("kind"), name)
    return None


def scenario_lifecycle(ctx):
    rep = ctx.rep
    rep.section("4. A scratch project, from creation to deletion (no source, no connector, no model)")
    if not ctx.admin_ok or ctx.ds is None:
        rep.skipped("scratch project lifecycle", "no administrator status answer, so no dataset to write the project row in")
        return
    ds = ctx.ds
    code = ctx.scratch
    resp, settings = ds.get_row("FactorySettings", "DEFAULT")
    if resp.status != 200 or settings is None:
        rep.skipped("scratch project lifecycle", "Data Services does not let the administrator read FactorySettings (HTTP %d %s)" % (resp.status, clip(resp.error_text(), 100)))
        return
    if (settings.get("role") or "DEVELOPMENT") == "TARGET":
        rep.skipped("scratch project lifecycle", "this instance is a delivery TARGET: projects are not created here")
        return
    resp, existing = ds.get_row("Project", code)
    if resp.status != 404:
        rep.skipped("scratch project lifecycle", "the scratch code %s is not free (HTTP %d)" % (code, resp.status))
        return

    rep.note("the module has no REST route that creates a project (only the wizard's SAVE_PROJECT does): the Project row is written through "
             "EBX Data Services with the fields the wizard writes; every other step uses the module's own routes")
    row = {"code": code, "label": "%s integration test (scratch)" % ctx.prefix, "description": "Created by tools/itest.sh, removed at the end of the run.",
           "mode": "GREENFIELD", "status": "DRAFT",
           "llmValueSharing": "SHAPES_ONLY", "llmSampleSize": 20}
    if settings.get("instanceId"):
        row["originRepository"] = settings["instanceId"]
    created = ds.create("Project", row)
    if created.status not in (200, 201):
        if created.status in (403, 404, 405):
            rep.skipped("scratch project created", "Data Services refused the Project row (HTTP %d %s): no way to create one without the wizard" % (created.status, clip(created.error_text(), 120)))
        else:
            rep.failed("scratch project created", "HTTP %d %s" % (created.status, clip(created.error_text(), 200)))
        return
    ctx.scratch_created = True
    ctx.cleanups.append(("delete the scratch project through the module", lambda: _delete_scratch(ctx, final=True)))
    rep.passed("scratch project created", "%s (Data Services, HTTP %d)" % (code, created.status))

    # child rows the deletion has to remove
    src = ds.create("ProjectSource", {"project": code, "sourceSystem": "ITESTSRC", "status": "SELECTED"})
    rep.passed("scratch source row created", "ProjectSource of %s" % code) if src.status in (200, 201) else \
        rep.failed("scratch source row created", "HTTP %d %s" % (src.status, clip(src.error_text(), 160)))
    step = ds.create("PipelineStep", {"project": code, "step": "STAGING_SUGGEST", "status": "DONE", "message": "%s scratch step" % ctx.prefix})
    rep.passed("scratch pipeline step row created", "PipelineStep of %s" % code) if step.status in (200, 201) else \
        rep.failed("scratch pipeline step row created", "HTTP %d %s" % (step.status, clip(step.error_text(), 160)))

    # read it back: the module's own routes see it
    resp, fields = ds.get_row("Project", code)
    if resp.status == 200 and fields and fields.get("code") == code:
        rep.passed("project read back (Data Services)", "label %r" % fields.get("label"))
    else:
        rep.failed("project read back (Data Services)", "HTTP %d" % resp.status)
    r = ctx.admin.get("%s/projects/%s/jobs" % (ctx.rest, code))
    data = r.json()
    if r.status == 200 and isinstance(data, dict) and isinstance(data.get("active"), list):
        rep.passed("module sees the project (jobs)", "HTTP 200, %d active job(s)" % len(data["active"]))
    else:
        rep.failed("module sees the project (jobs)", "HTTP %d %s" % (r.status, clip(r.text, 160)))
    r = ctx.admin.get("%s/projects/%s/intake" % (ctx.rest, code))
    rep.passed("intake status of the project", "HTTP 200") if r.status == 200 and isinstance(r.json(), dict) else \
        rep.failed("intake status of the project", "HTTP %d %s" % (r.status, clip(r.text, 160)))
    for label, tail in (("matching activity", "matching/activity"), ("record lineage", "lineage/Entity?key=1"), ("cross-references", "xrefs/Entity?key=1"),
                        ("distribution targets", "distribution/targets"), ("delivery domain", "delivery/domain")):
        r = ctx.admin.get("%s/projects/%s/%s" % (ctx.rest, code, tail))
        if r.status in (200, 400, 404, 409, 422):
            rep.passed("read route %s on a project with no zone" % label, "HTTP %d" % r.status)
        else:
            rep.failed("read route %s on a project with no zone" % label, "HTTP %d %s" % (r.status, clip(r.error_text(), 160)))

    # a harmless job: a golden-key refresh on a project without a master zone queues, runs and ends FAILED at once, no external system involved
    r = ctx.admin.post("%s/projects/%s/matching/refresh-golden-keys" % (ctx.rest, code))
    data = r.json()
    if r.status != 202 or not isinstance(data, dict) or not data.get("jobId"):
        rep.failed("harmless job queued (golden-key refresh)", "HTTP %d %s, expected 202 with a jobId" % (r.status, clip(r.text, 160)))
    else:
        job_id = str(data["jobId"])
        rep.passed("harmless job queued (golden-key refresh)", "HTTP 202, job %s" % job_id)
        ended = wait_job(ctx, job_id, int(ctx.env.get("ITEST_JOB_TIMEOUT", "90")))
        if ended is None:
            rep.failed("job ran to its end", "job %s did not end within the timeout: is the background-job engine running?" % job_id)
        else:
            message = ended.get("message") or ""
            if ended.get("status") == "FAILED" and "no master zone" in message.lower():
                rep.passed("job ran to its end", "FAILED as expected: %s" % clip(message, 100))
            elif ended.get("status") in ("FAILED", "DONE"):
                rep.passed("job ran to its end", "%s: %s" % (ended.get("status"), clip(message, 100)))
            else:
                rep.failed("job ran to its end", "%s: %s" % (ended.get("status"), clip(message, 100)))
        r = ctx.admin.get("%s/projects/%s/jobs" % (ctx.rest, code))
        if r.status == 200 and job_id in (r.text or ""):
            rep.passed("job listed by the jobs route", "job %s" % job_id)
        else:
            rep.failed("job listed by the jobs route", "HTTP %d, job %s not in the answer" % (r.status, job_id))

    # deletion: plan, check it touches only the scratch project, then delete
    _delete_scratch(ctx, final=False)


def _delete_scratch(ctx, final):
    """Plan, check and delete the scratch project through the module's routes. Idempotent; `final` = called from the clean-up stack."""
    rep = ctx.rep
    code = ctx.scratch
    ds = ctx.ds
    if ctx.scratch_deleted or not ctx.scratch_created:
        return True
    if ctx.delete_tried:
        return False   # already attempted and reported (the clean-up stack calls this again only as a safety net)
    ctx.delete_tried = True
    plan_resp = ctx.admin.get("%s/projects/%s/deletion/plan" % (ctx.rest, code))
    plan = plan_resp.json()
    if plan_resp.status != 200:
        rep.failed("deletion plan of the scratch project", "HTTP %d %s" % (plan_resp.status, clip(plan_resp.error_text(), 200)))
        ctx.leftovers.append("scratch project %s (deletion plan refused): delete it with POST %s/projects/%s/delete?confirm=%s" % (code, ctx.rest, code, code))
        return False
    problem = _plan_problem(plan, code)
    counts = plan.get("rowCounts") if isinstance(plan, dict) else None
    if problem:
        rep.failed("deletion plan touches only the scratch project", problem + ": NOT deleting")
        ctx.leftovers.append("scratch project %s: the deletion plan listed foreign objects, nothing was deleted" % code)
        return False
    rep.passed("deletion plan touches only the scratch project", "%d generated object(s) listed, %s" % (len(plan.get("items") or []), "row counts " + json.dumps(counts, sort_keys=True) if counts else "no row counts"))
    if isinstance(counts, dict):
        have = dict((k, v) for k, v in counts.items() if isinstance(v, int) and v > 0)
        rep.passed("plan counts the scratch rows", ", ".join("%s=%s" % kv for kv in sorted(have.items())) or "none above zero") if have else \
            rep.failed("plan counts the scratch rows", "no table has a row for the scratch project")
    d = ctx.admin.post("%s/projects/%s/delete?confirm=%s" % (ctx.rest, code, urllib.parse.quote(code)))
    data = d.json()
    if d.status != 202 or not isinstance(data, dict) or not data.get("jobId"):
        rep.failed("deletion queued", "HTTP %d %s, expected 202 with a jobId" % (d.status, clip(d.text, 200)))
        ctx.leftovers.append("scratch project %s: deletion not queued" % code)
        return False
    job_id = str(data["jobId"])
    rep.passed("deletion queued", "HTTP 202, job %s" % job_id)
    ended = wait_job(ctx, job_id, int(ctx.env.get("ITEST_JOB_TIMEOUT", "90")))
    if ended is None:
        rep.failed("deletion job ended", "job %s did not end within the timeout" % job_id)
        ctx.leftovers.append("scratch project %s: deletion job %s did not end" % (code, job_id))
        return False
    if ended.get("status") != "DONE":
        rep.failed("deletion job ended", "%s: %s" % (ended.get("status"), clip(ended.get("message") or "", 240)))
        ctx.leftovers.append("scratch project %s: deletion job %s ended %s" % (code, job_id, ended.get("status")))
        return False
    rep.passed("deletion job ended", "DONE: %s" % clip(ended.get("message") or "", 120))
    ctx.scratch_deleted = True
    return True


# -------------------------------------------------------------------------------------------------- 6. clean-up check

def _remove_job_history(ctx, code):
    """The deletion leaves the project's FactoryJob rows (job history, purged later by the module's retention): removed here, each row re-read first."""
    ds = ctx.ds
    resp, rows = ds.select("FactoryJob", "./project='%s'" % code)
    if resp.status != 200:
        return resp, -1
    removed = 0
    for row in rows:
        pk = row.get("id")
        if pk is None or row.get("project") != code:
            continue
        if row.get("status") not in TERMINAL_JOB:
            continue
        path = ds.record("FactoryJob", pk)
        ctx.guard.allow_path(path)
        if ds.delete("FactoryJob", pk).status in (200, 204):
            removed += 1
    return resp, removed


def scenario_cleanup(ctx):
    rep = ctx.rep
    rep.section("6. Clean-up verification")
    # run what registered itself (rights row first, then the scratch project if a scenario stopped early)
    while ctx.cleanups:
        label, fn = ctx.cleanups.pop()
        try:
            fn()
        except Exception as exc:   # a clean-up must never hide the rest
            rep.failed("clean-up: %s" % label, "%s: %s" % (type(exc).__name__, exc))
    if ctx.ds is None:
        rep.skipped("clean-up verification", "no Data Services handle (administrator status was not read)")
        return
    ds = ctx.ds
    if ctx.created_right_row:
        rep.failed("access-rights row", "the temporary Everyone row is still there")
    else:
        resp, row = ds.get_row("AccessRight", "Beveryone")
        if resp.status == 200 and row and (row.get("comment") or "").startswith(ctx.prefix):
            rep.failed("access-rights row", "a row carrying this run's comment is still there")
        else:
            rep.passed("access-rights row", "no temporary row left")
    for code in (ctx.scratch, ctx.full):
        if code == ctx.full and not ctx.env.get("ITEST_FULL"):
            continue
        resp, row = ds.get_row("Project", code)
        if resp.status == 404:
            rep.passed("project %s gone" % code, "Data Services answers 404")
        elif resp.status == 200:
            rep.failed("project %s gone" % code, "the Project row is still there")
            ctx.leftovers.append("Project %s" % code)
        else:
            rep.failed("project %s gone" % code, "HTTP %d" % resp.status)
        if ctx.admin.get("%s/projects/%s/jobs" % (ctx.rest, code)).status == 404:
            rep.passed("project %s unknown to the module" % code, "the jobs route answers 404")
        else:
            rep.failed("project %s unknown to the module" % code, "the jobs route still finds it")
        for table in ("ProjectSource", "PipelineStep"):
            r, n, rows = select_count(ctx, table, "./project='%s'" % code)
            if r.status != 200:
                rep.failed("%s rows of %s" % (table, code), "HTTP %d" % r.status)
            elif n:
                rep.failed("%s rows of %s" % (table, code), "%d row(s) left" % n)
                ctx.leftovers.append("%d %s row(s) of %s" % (n, table, code))
            else:
                rep.passed("%s rows of %s" % (table, code), "none left")
        r, removed = _remove_job_history(ctx, code)
        if removed < 0:
            rep.failed("FactoryJob rows of %s" % code, "HTTP %d" % r.status)
        else:
            r2, n, rows = select_count(ctx, "FactoryJob", "./project='%s'" % code)
            if r2.status == 200 and n == 0:
                rep.passed("FactoryJob rows of %s" % code, "job history removed (%d row(s)); none left" % removed)
            else:
                rep.failed("FactoryJob rows of %s" % code, "%d row(s) left (a job still running?)" % n)
                ctx.leftovers.append("%d FactoryJob row(s) of %s" % (n, code))
    # generated dataspaces: none expected; read the list when Data Services offers it
    resp = ctx.admin.get("/ebx-dataservices/rest/data/v1")
    data = resp.json()
    if resp.status == 200 and data is not None:
        names = []
        stack = [data]
        while stack:
            cur = stack.pop()
            if isinstance(cur, dict):
                for k, v in cur.items():
                    if k in ("name", "key", "dataspace") and isinstance(v, str):
                        names.append(v)
                    elif isinstance(v, (dict, list)):
                        stack.append(v)
            elif isinstance(cur, list):
                stack.extend(cur)
        mine = sorted(set(n for n in names if ctx.prefix.lower() in n.lower()))
        if mine:
            rep.failed("generated dataspaces", "left: %s" % ", ".join(mine))
            ctx.leftovers.extend("dataspace %s" % n for n in mine)
        else:
            rep.passed("generated dataspaces", "none named after %s (%d dataspace(s) listed)" % (ctx.prefix, len(set(names))))
    else:
        rep.skipped("generated dataspaces", "Data Services does not list dataspaces here (HTTP %d); the scratch project generates none" % resp.status)
    if ctx.norole is not None and ctx.norole_gated:
        r = ctx.norole.request(*(VIEW_ROUTE[0], ctx.path(VIEW_ROUTE[1])))
        if r.status == 403:
            rep.passed("no-role account refused again", "HTTP 403")
        else:
            rep.failed("no-role account refused again", "HTTP %d: the rights of the instance are not back to what they were" % r.status)


# --------------------------------------------------------------------------------- 5. heavy end to end (ITEST_FULL=1)

SAMPLE_A = "id,firstName,lastName,email\n1,Alice,Martin,alice.martin@example.test\n2,Bob,Durand,bob.durand@example.test\n3,Chloe,Petit,chloe.petit@example.test\n"
SAMPLE_B = "id,firstName,lastName,email\n10,Alice,Martin,alice.martin@example.test\n11,Bob,Durand,b.durand@example.test\n12,David,Moreau,david.moreau@example.test\n"


def data_url(text, name):
    """The data URL the wizard's file panel sends: the import requires the name parameter (SampleFileRules.DATA_URL_PATTERN),
    percent-encoded like the browser's encodeURIComponent. Without it the file is refused as "not in the expected format"."""
    return "data:text/csv;name=%s;base64,%s" % (urllib.parse.quote(name, safe=""), base64.b64encode(text.encode("utf-8")).decode("ascii"))


def _accept_all(ctx, code):
    """Sets every proposal of the project to ACCEPTED through Data Services (what the wizard's Accept all does, decision by decision)."""
    ds = ctx.ds
    done = 0

    def update(table, pk, fields):
        ctx.guard.allow_path(ds.record(table, pk))   # the row was selected through a filter on the scratch project
        return ds.update(table, pk, fields)
    resp, entities = ds.select("StagingEntity", "./project='%s'" % code)
    for entity in entities:
        eid = entity.get("id")
        if update("StagingEntity", eid, {"status": "ACCEPTED"}).status in (200, 204):
            done += 1
        for table, key in (("StagingAttribute", "entity"), ("TransferFeed", "entity"), ("QualityRule", "entity"), ("MatchPlan", "entity")):
            r, rows = ds.select(table, "./%s='%s'" % (key, eid))
            for row in rows:
                if row.get("status") == "PROPOSED" and update(table, row.get("id"), {"status": "ACCEPTED"}).status in (200, 204):
                    done += 1
        r, feeds = ds.select("TransferFeed", "./entity='%s'" % eid)
        for feed in feeds:
            r, maps = ds.select("AttributeMapping", "./feed='%s'" % feed.get("id"))
            for m in maps:
                if m.get("status") == "PROPOSED" and update("AttributeMapping", m.get("id"), {"status": "ACCEPTED"}).status in (200, 204):
                    done += 1
    return done


def _wait_idle(ctx, code, timeout):
    def idle():
        r = ctx.admin.get("%s/projects/%s/jobs" % (ctx.rest, code))
        data = r.json()
        return isinstance(data, dict) and data.get("busy") is False
    return poll(idle, timeout, poll_interval(ctx, 3.0))


def scenario_full(ctx):
    """landing -> staging -> master -> matching on two sample-file sources, then deletion with every generated object ticked.

    Designed from FactoryResource.java and the wizard's own sequence; NEVER EXECUTED against a live instance when it was written. It
    creates, per run: a Connectors connection and a catalogue Source per sample file (NOT removed by the project deletion: they are
    listed in the final report), a landing/staging/master dataspace and model each, Connectors flows and a Match and Merge
    configuration (all removed by the deletion with matching, flows, dataspaces and models ticked). It needs the MCP servers of the
    instance (data-model, match-merge, ebx-connectors) and the agent account or the caller's session token to work.
    """
    rep = ctx.rep
    rep.section("5. End to end on two sample-file sources (ITEST_FULL=1: heavy, creates datasets and models)")
    if not ctx.env.get("ITEST_FULL"):
        rep.skipped("end to end", "ITEST_FULL is not set (this scenario creates models and datasets)")
        return
    if not ctx.admin_ok or ctx.ds is None:
        rep.skipped("end to end", "no administrator status answer")
        return
    ds = ctx.ds
    code = ctx.full
    timeout = int(ctx.env.get("ITEST_FULL_TIMEOUT", "1800"))
    r = ctx.admin.get(ctx.rest + "/connection/test")
    data = r.json()
    if r.status != 200 or not isinstance(data, dict) or data.get("ready") is not True:
        rep.skipped("end to end", "the MCP connection test is not ready (HTTP %d %s)" % (r.status, clip(r.text, 160)))
        return
    resp, settings = ds.get_row("FactorySettings", "DEFAULT")
    if resp.status != 200 or (settings or {}).get("role") == "TARGET":
        rep.skipped("end to end", "settings unreadable or the instance is a delivery TARGET")
        return
    row = {"code": code, "label": "%s end to end (scratch)" % ctx.prefix, "mode": "GREENFIELD", "status": "DRAFT",
           "llmValueSharing": "SHAPES_ONLY", "llmSampleSize": 20}
    if settings.get("instanceId"):
        row["originRepository"] = settings["instanceId"]
    if ds.create("Project", row).status not in (200, 201):
        rep.failed("end-to-end project created", "Data Services refused the Project row")
        return
    ctx.full_created = True
    rep.passed("end-to-end project created", code)

    def step(label, method, tail, expect=(200, 202), wait=True):
        r = ctx.admin.request(method, "%s%s" % (ctx.rest, tail))
        if r.status not in expect:
            rep.failed(label, "HTTP %d %s" % (r.status, clip(r.error_text(), 240)))
            return False
        if r.status == 202 and wait and not _wait_idle(ctx, code, timeout):
            rep.failed(label, "the background job did not end within %d s" % timeout)
            return False
        rep.passed(label, "HTTP %d" % r.status)
        return True

    ok = True
    try:
        files = [{"name": "%s_a.csv" % ctx.prefix, "dataUrl": data_url(SAMPLE_A, "%s_a.csv" % ctx.prefix), "code": "ITA", "trustRank": 100},
                 {"name": "%s_b.csv" % ctx.prefix, "dataUrl": data_url(SAMPLE_B, "%s_b.csv" % ctx.prefix), "code": "ITB", "trustRank": 90}]
        r = ctx.admin.post("%s/projects/%s/sources/files" % (ctx.rest, code), files)
        body = r.json()
        if r.status != 200 or not isinstance(body, dict):
            rep.failed("sample files imported", "HTTP %d %s" % (r.status, clip(r.error_text(), 240)))
            return
        errors = [f.get("error") for f in body.get("files", []) if f.get("error")]
        for f in body.get("files", []):
            ctx.leftovers.append("Connectors connection %s / catalogue source %s created for %s: remove them in the Connectors UI" % (f.get("connectionId"), f.get("sourceId"), f.get("name")))
        if errors:
            rep.failed("sample files imported", "; ".join(errors))
            return
        rep.passed("sample files imported", "%d file(s)" % len(body.get("files", [])))
        r, sources = ds.select("ProjectSource", "./project='%s'" % code)
        if len(sources) < 2:
            rep.failed("two project sources", "%d found" % len(sources))
            return
        for source in sources:
            sid = source.get("id")
            ctx.guard.allow_path_prefix("%s/sources/%s/" % (ctx.rest, sid))
            for label, tail in (("plan", "plan"), ("generate", "generate"), ("flows", "flows"), ("load", "load")):
                if not step("landing %s of %s" % (label, source.get("sourceSystem")), "POST", "/sources/%s/landing/%s" % (sid, tail)):
                    ok = False
                    break
            if not ok:
                break
        stages = [("staging proposal", "/projects/%s/staging/suggest" % code, True), ("staging model and dataset", "/projects/%s/staging/generate" % code, False),
                  ("staging load", "/projects/%s/staging/load?scope=all" % code, False), ("master model and dataset", "/projects/%s/master/generate" % code, False),
                  ("master load", "/projects/%s/master/load" % code, False), ("matching proposal", "/projects/%s/matching/suggest" % code, True),
                  ("matching configuration", "/projects/%s/matching/configure" % code, False), ("matching run", "/projects/%s/matching/run?scope=all" % code, False)]
        for label, tail, then_accept in stages:
            if not ok:
                break
            if not step(label, "POST", tail):
                ok = False
                break
            if then_accept:
                n = _accept_all(ctx, code)
                rep.passed("%s accepted" % label, "%d decision(s) set to ACCEPTED through Data Services" % n)
        if ok:
            r, bad = ds.select("PipelineStep", "./project='%s' and ./status='FAILED'" % code)
            if r.status == 200 and not bad:
                rep.passed("no failed pipeline step", "none FAILED for %s" % code)
            else:
                rep.failed("no failed pipeline step", "%d step(s) FAILED: %s" % (len(bad), "; ".join("%s %s" % (b.get("step"), clip(b.get("message") or "", 80)) for b in bad[:3])))
    finally:
        _delete_full(ctx, code)


def _delete_full(ctx, code):
    rep = ctx.rep
    ds = ctx.ds
    flow_ids = set()
    r, sources = ds.select("ProjectSource", "./project='%s'" % code)
    for source in sources:
        r, tables_rows = ds.select("LandingTable", "./projectSource='%s'" % source.get("id"))
        for t in tables_rows:
            if t.get("inboundMapping"):
                flow_ids.add(str(t["inboundMapping"]))
    plan_resp = ctx.admin.get("%s/projects/%s/deletion/plan" % (ctx.rest, code))
    plan = plan_resp.json()
    if plan_resp.status != 200 or not isinstance(plan, dict):
        rep.failed("end-to-end deletion plan", "HTTP %d %s" % (plan_resp.status, clip(plan_resp.error_text(), 200)))
        ctx.leftovers.append("project %s and everything generated for it: delete it with the wizard's Delete project" % code)
        return
    foreign = None
    for item in plan.get("items") or []:
        name = str(item.get("name", ""))
        if item.get("kind") == "LANDING_FLOW":
            if name not in flow_ids:
                foreign = "flow %s is not one of the scratch landing tables' flows" % name
        elif code.lower() not in name.lower():
            foreign = "%s %s is not named after the scratch project" % (item.get("kind"), name)
    if plan.get("project") != code or foreign:
        rep.failed("end-to-end plan touches only scratch objects", foreign or "wrong project in the plan: NOT deleting")
        ctx.leftovers.append("project %s: deletion not run (plan not clean)" % code)
        return
    rep.passed("end-to-end plan touches only scratch objects", "%d generated object(s)" % len(plan.get("items") or []))
    d = ctx.admin.post("%s/projects/%s/delete?confirm=%s&generated=matching,flows,dataspaces,models" % (ctx.rest, code, urllib.parse.quote(code)))
    data = d.json()
    if d.status != 202 or not isinstance(data, dict) or not data.get("jobId"):
        rep.failed("end-to-end deletion queued", "HTTP %d %s" % (d.status, clip(d.error_text(), 240)))
        ctx.leftovers.append("project %s: deletion not queued" % code)
        return
    ended = wait_job(ctx, str(data["jobId"]), int(ctx.env.get("ITEST_FULL_TIMEOUT", "1800")))
    if ended is None or ended.get("status") != "DONE":
        rep.failed("end-to-end deletion ended", "%s %s" % (ended.get("status") if ended else "timeout", clip((ended or {}).get("message") or "", 240)))
        ctx.leftovers.append("project %s: deletion job %s did not finish DONE" % (code, data["jobId"]))
        return
    rep.passed("end-to-end deletion ended", "DONE")
