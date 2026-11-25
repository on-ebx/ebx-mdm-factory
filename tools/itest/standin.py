"""A stand-in for the MDM Factory REST surface and the part of EBX Data Services the suite uses, for tools/itest.sh --selftest.

It is NOT the module: it reproduces only the observable contract the suite relies on (statuses, bodies, rights ladder, the
job queue and the project deletion as FactoryResource.java describes them), so that the suite's own logic is tested offline.
Faults (a set of names) bend one behaviour at a time so that the suite is shown to FAIL or SKIP when it should:
  open_design      a design route lets the no-role account in
  ebx_refuses      the no-role account gets EBX's own dataspace refusal (a 403 that is not the module's JSON)
  ds_refuses       Data Services refuses to create a Project row
  leave_rows       the deletion leaves a PipelineStep row
  foreign_plan     the deletion plan lists an object that is not the scratch project's
  row_stays        deleting the Everyone row of Access rights does nothing
  stuck_jobs       the job engine never runs a job
Listens on 127.0.0.1 only, on a port chosen by the system.
"""
import base64
import json
import re
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LADDER = {"NONE": 0, "VIEW": 1, "STEWARD": 2, "DESIGN": 3, "ADMIN": 4}
USERS = {"admin": ("adminpw", True), "norole": ("norolepw", False)}

ROUTES = [  # (method, regex, right)
    ("GET", r"/projects/([^/]+)/jobs", "VIEW"),
    ("GET", r"/projects/([^/]+)/matching/activity", "VIEW"),
    ("GET", r"/projects/([^/]+)/lineage/[^/]+", "VIEW"),
    ("GET", r"/projects/([^/]+)/xrefs/[^/]+", "VIEW"),
    ("GET", r"/projects/([^/]+)/intake", "VIEW"),
    ("POST", r"/jobs/([^/]+)/cancel", "STEWARD"),
    ("POST", r"/projects/([^/]+)/staging/suggest", "DESIGN"),
    ("POST", r"/projects/([^/]+)/matching/run", "DESIGN"),
    ("POST", r"/projects/([^/]+)/matching/refresh-golden-keys", "DESIGN"),
    ("POST", r"/projects/([^/]+)/intake/process", "DESIGN"),
    ("GET", r"/projects/([^/]+)/distribution/targets", "DESIGN"),
    ("GET", r"/projects/([^/]+)/deletion/plan", "ADMIN"),
    ("POST", r"/projects/([^/]+)/delete", "ADMIN"),
    ("GET", r"/projects/([^/]+)/reset/plan", "ADMIN"),
    ("POST", r"/projects/([^/]+)/reset", "ADMIN"),
    ("POST", r"/projects/([^/]+)/master/reset", "ADMIN"),
    ("GET", r"/projects/([^/]+)/delivery/domain", "ADMIN"),
    ("GET", r"/connection/test", "ADMIN"),
    # the routes of the heavy scenario (ITEST_FULL): accepted with an empty answer, so that the scenario's own logic runs
    ("POST", r"/projects/([^/]+)/sources/files", "DESIGN"),
    ("POST", r"/sources/([^/]+)/landing/(?:plan|generate|flows|load)", "DESIGN"),
    ("POST", r"/projects/([^/]+)/(?:staging/(?:generate|load)|master/(?:generate|load)|matching/(?:suggest|configure))", "DESIGN"),
]
CHILD_TABLES = ("ProjectSource", "PipelineStep", "StagingEntity")


class Standin:
    def __init__(self, faults=()):
        self.faults = set(faults)
        self.lock = threading.RLock()
        self.tables = {"Project": {}, "ProjectSource": {}, "PipelineStep": {}, "FactoryJob": {}, "AccessRight": {}, "StagingEntity": {}, "StagingAttribute": {}, "TransferFeed": {}, "QualityRule": {}, "MatchPlan": {}, "AttributeMapping": {}, "LandingTable": {},
                       "FactorySettings": {"DEFAULT": {"code": "DEFAULT", "role": "DEVELOPMENT", "instanceId": "inst-1"}}}
        self.counter = 0
        self.server = None
        self.thread = None
        self.log = []

    # -- lifecycle
    def start(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                outer.handle(self, "GET")

            def do_POST(self):
                outer.handle(self, "POST")

            def do_PUT(self):
                outer.handle(self, "PUT")

            def do_DELETE(self):
                outer.handle(self, "DELETE")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return "http://127.0.0.1:%d" % self.server.server_address[1]

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    # -- plumbing
    def reply(self, h, status, body=None):
        data = b"" if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode("utf-8"))
        h.send_response(status)
        if data:
            h.send_header("Content-Type", "application/json")
        h.send_header("Content-Length", str(len(data)))
        h.end_headers()
        h.wfile.write(data)

    def user_of(self, h):
        header = h.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return None
        try:
            name, password = base64.b64decode(header[6:]).decode("utf-8").split(":", 1)
        except Exception:
            return None
        known = USERS.get(name)
        return (name, known[1]) if known and known[0] == password else None

    def right_of(self, user):
        if user is None:
            return "NONE"
        if user[1]:
            return "ADMIN"
        row = self.tables["AccessRight"].get("Beveryone")
        return row["right"] if row else "NONE"

    def handle(self, h, method):
        length = int(h.headers.get("Content-Length") or 0)
        raw = h.rfile.read(length) if length else b""
        parts = urllib.parse.urlsplit(h.path)
        path, query = parts.path, urllib.parse.parse_qs(parts.query)
        body = None
        if raw:
            try:
                body = json.loads(raw.decode("utf-8"))
            except ValueError:
                body = None
        with self.lock:
            self.log.append((method, path, (self.user_of(h) or ("-",))[0]))
            if path in ("/ebx", "/ebx/"):
                return self.reply(h, 302)
            user = self.user_of(h)
            if path.startswith("/ebx-mdm-factory/rest/v1"):
                return self.module(h, method, path[len("/ebx-mdm-factory/rest/v1"):], query, user)
            if path.startswith("/ebx-dataservices/rest/data/v1"):
                return self.dataservices(h, method, path[len("/ebx-dataservices/rest/data/v1"):], query, body, user)
            return self.reply(h, 404, {"error": "no such path"})

    # -- the module
    def module(self, h, method, path, query, user):
        if path == "/status":
            if user is None:
                return self.reply(h, 200, {"module": "ebx-mdm-factory", "alive": True})
            if not user[1]:
                return self.reply(h, 200, {"module": "ebx-mdm-factory", "alive": True})
            tables = dict((t, len(r)) for t, r in self.tables.items())
            return self.reply(h, 200, {"module": "ebx-mdm-factory", "version": "9.9.9-test", "commit": "abc1234", "dataspace": "MdmFactory",
                                       "dataset": "MdmFactory", "datasetState": "VERIFIED", "user": user[0], "sessionTokenMintable": True, "tables": tables})
        if user is None:
            return self.reply(h, 401, {"error": "authentication required"})
        for m, regex, right in ROUTES:
            hit = re.fullmatch(regex, path)
            if m != method or not hit:
                continue
            have = self.right_of(user)
            if "open_design" in self.faults and right == "DESIGN" and "staging/suggest" in path:
                have = "ADMIN"
            if LADDER[have] < LADDER[right]:
                if "ebx_refuses" in self.faults and not user[1]:
                    return self.reply(h, 403, {"message": "You are not allowed to read the dataspace MdmFactory"})
                if right == "ADMIN":
                    msg = "Access denied: %s %s is for EBX administrators only." % (m, regex)
                else:
                    msg = "Access denied: %s %s requires the MDM Factory right %s. An EBX administrator gives it to one of your roles." % (m, regex, right)
                return self.reply(h, 403, {"error": msg})
            return self.route(h, method, path, hit.group(1) if hit.groups() else "", query)
        return self.reply(h, 404, {"error": "no such route"})

    def project(self, code):
        return self.tables["Project"].get(code)

    def route(self, h, method, path, arg, query):
        projects = self.tables["Project"]
        if re.fullmatch(r"/sources/[^/]+/landing/\w+", path):
            return self.reply(h, 200, {})
        if path.endswith("/cancel"):
            return self.reply(h, 200, {"jobId": arg, "cancelRequested": False})
        if path == "/connection/test":
            return self.reply(h, 200, {"ready": True})
        if arg not in projects:
            return self.reply(h, 404 if method == "GET" and "deletion" not in path else 409, {"error": "Project %s not found" % arg})
        if path.endswith("/jobs"):
            active = [j for j in self.tables["FactoryJob"].values() if j["project"] == arg and j["status"] in ("QUEUED", "RUNNING")]
            recent = [j for j in self.tables["FactoryJob"].values() if j["project"] == arg and j["status"] not in ("QUEUED", "RUNNING")]
            return self.reply(h, 200, {"projectId": arg, "active": [{"jobId": j["id"]} for j in active], "recent": [{"jobId": j["id"], "status": j["status"], "message": j.get("message")} for j in recent],
                                       "busy": bool(active)})
        if path.endswith("/intake"):
            return self.reply(h, 200, {"project": arg, "state": "OFF"})
        if path.endswith("/deletion/plan"):
            counts = dict((t, sum(1 for r in self.tables[t].values() if r.get("project") == arg)) for t in CHILD_TABLES)
            counts["Project"] = 1
            items = []
            if "foreign_plan" in self.faults:
                items.append({"kind": "STAGING_DATASPACE", "name": "SomeoneElse_Staging", "eligible": True})
            return self.reply(h, 200, {"project": arg, "label": projects[arg].get("label"), "delivered": False, "items": items, "rowCounts": counts})
        if path.endswith("/delete"):
            if query.get("confirm", [""])[0] != arg:
                return self.reply(h, 409, {"error": "confirm"})
            return self.reply(h, 202, self.enqueue(arg, "PROJECT_DELETE_RUN"))
        if path.endswith("/refresh-golden-keys"):
            return self.reply(h, 202, self.enqueue(arg, "GOLDEN_KEY_REFRESH_RUN"))
        if path.endswith("/sources/files"):
            for code in ("ITA", "ITB"):
                self.counter += 1
                self.tables["ProjectSource"][str(self.counter)] = {"id": str(self.counter), "project": arg, "sourceSystem": code, "status": "SELECTED"}
            return self.reply(h, 200, {"files": [{"name": "a.csv", "connectionId": "c1", "sourceId": "s1", "applicationAdded": True},
                                                 {"name": "b.csv", "connectionId": "c2", "sourceId": "s2", "applicationAdded": True}]})
        if path.endswith("/matching/suggest") or path.endswith("/staging/suggest"):
            self.counter += 1
            self.tables["StagingEntity"][str(self.counter)] = {"id": str(self.counter), "project": arg, "status": "PROPOSED"}
            return self.reply(h, 200, {})
        return self.reply(h, 200, {})

    def enqueue(self, code, kind):
        self.counter += 1
        job = {"id": str(self.counter), "project": code, "kind": kind, "status": "QUEUED", "message": ""}
        self.tables["FactoryJob"][job["id"]] = job
        if "stuck_jobs" not in self.faults:
            threading.Timer(0.15, self.run_job, (job["id"],)).start()
        return {"started": True, "jobId": job["id"], "statusUrl": "/projects/%s/jobs" % code}

    def run_job(self, job_id):
        with self.lock:
            job = self.tables["FactoryJob"][job_id]
            if job["kind"] == "GOLDEN_KEY_REFRESH_RUN":
                job["status"], job["message"] = "FAILED", "Project %s has no master zone yet" % job["project"]
                return
            code = job["project"]
            for table in CHILD_TABLES:
                for pk in [k for k, r in self.tables[table].items() if r.get("project") == code]:
                    if table == "PipelineStep" and "leave_rows" in self.faults:
                        continue
                    del self.tables[table][pk]
            self.tables["Project"].pop(code, None)
            job["status"], job["message"] = "DONE", "Project %s deleted." % code

    # -- Data Services
    def dataservices(self, h, method, path, query, body, user):
        if user is None or not user[1]:
            return self.reply(h, 403, {"message": "Access denied by EBX"})
        if path in ("", "/"):
            return self.reply(h, 200, {"rows": [{"name": "MdmFactory"}, {"name": "Reference"}]})
        m = re.fullmatch(r"/BMdmFactory/MdmFactory/root/(\w+)(?:/([^/]+))?", path)
        if not m or m.group(1) not in self.tables:
            return self.reply(h, 404, {"error": "unknown table"})
        table, pk = m.group(1), m.group(2)
        pk = urllib.parse.unquote(pk) if pk else None
        rows = self.tables[table]
        if method == "GET" and pk is None:
            found = [r for r in rows.values() if self.matches(r, query.get("filter", [None])[0])]
            return self.reply(h, 200, {"rows": [self.wrap(r) for r in found], "pagination": {}})
        if method == "GET":
            return self.reply(h, 200, self.wrap(rows[pk])) if pk in rows else self.reply(h, 404, {"error": "no such record"})
        if method == "POST" and pk is None:
            if table == "Project" and "ds_refuses" in self.faults:
                return self.reply(h, 403, {"error": "forbidden"})
            for item in (body or {}).get("rows", []):
                fields = dict((k, v.get("content")) for k, v in item.get("content", {}).items())
                key = fields.get("code") if table == "Project" else fields.get("profile") if table == "AccessRight" else None
                if key is None:
                    self.counter += 1
                    key = fields["id"] = str(self.counter)
                if key in rows:
                    return self.reply(h, 409, {"error": "duplicate key"})
                rows[key] = fields
            return self.reply(h, 201, {"rows": [{"details": path}]})
        if method == "PUT" and pk in rows:
            for k, v in (body or {}).get("content", {}).items():
                rows[pk][k] = v.get("content")
            return self.reply(h, 204)
        if method == "DELETE" and pk in rows:
            if table == "AccessRight" and "row_stays" in self.faults:
                return self.reply(h, 200, {"deletedCount": 0})
            del rows[pk]
            return self.reply(h, 200, {"deletedCount": 1})
        return self.reply(h, 404, {"error": "no such record"})

    @staticmethod
    def wrap(row):
        return {"content": dict((k, {"content": v}) for k, v in row.items()), "details": "x"}

    @staticmethod
    def matches(row, flt):
        if not flt:
            return True
        for clause in flt.split(" and "):
            m = re.fullmatch(r"\s*\./(\w+)\s*=\s*'([^']*)'\s*", clause)
            if not m or str(row.get(m.group(1))) != m.group(2):
                return False
        return True

    def leftovers(self, prefix):
        out = []
        for table, rows in self.tables.items():
            for pk, r in rows.items():
                if prefix in json.dumps(r) or prefix in str(pk):
                    out.append((table, pk))
        return out
