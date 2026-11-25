"""HTTP and Data Services plumbing of the MDM Factory integration suite (audit F-05). Python 3 standard library only.

Two rules are enforced here, not left to the scenarios:
  * credentials live in memory, reach the server only in an Authorization header and are never printed (no request line,
    header or credential is ever traced; a response body is cut before it is shown);
  * every request that is not a GET goes through Guard: it must carry the run's own prefix (ITEST<timestamp>) in its path or
    body, or be one of the few exact paths a scenario registered after checking the row is the suite's own. A request that
    would touch anything else is a bug of the suite and raises SafetyError before a byte is sent.
"""
import base64
import http.client
import json
import re
import ssl
import urllib.parse

LOCAL_HOSTS = ("localhost", "127.0.0.1", "::1", "[::1]")


class SafetyError(Exception):
    """The suite was about to send a mutating request that is not provably about its own scratch objects."""


def is_local_host(host):
    h = (host or "").lower().strip("[]")
    return h in ("localhost", "127.0.0.1", "::1") or h.endswith(".localhost") or h.endswith(".test")


def parse_auth(value):
    """'user:password' -> (user, password); None when it does not look like that (no colon or empty user)."""
    if not value or ":" not in value:
        return None
    user, password = value.split(":", 1)
    if not user:
        return None
    return user, password


def clip(text, limit=300):
    text = re.sub(r"\s+", " ", text or "").strip()
    return text if len(text) <= limit else text[:limit] + "..."


def unwrap(value):
    """Data Services wraps every value as {"content": x}; groups nest the same way."""
    while isinstance(value, dict) and "content" in value and len(value) <= 3:
        value = value["content"]
    return value


def row_fields(row):
    """The field map of a Data Services row or record: {"content": {field: {"content": v}}} or already flat."""
    if not isinstance(row, dict):
        return {}
    inner = row.get("content")
    nested = isinstance(inner, dict)
    out = {}
    for key, value in (inner if nested else row).items():
        if not nested and key in ("details", "label", "metadata", "validation"):
            continue   # record-level decorations of a flat answer; inside "content" every key is a field of the table
        out[key] = unwrap(value)
    return out


def wrap_fields(fields):
    return {"content": dict((k, {"content": v}) for k, v in fields.items())}


class Guard:
    def __init__(self, prefix):
        self.prefix = prefix
        self.paths = set()
        self.path_prefixes = []

    def allow_path(self, path):
        self.paths.add(urllib.parse.unquote(path.split("?", 1)[0]))

    def allow_path_prefix(self, path_prefix):
        self.path_prefixes.append(path_prefix)

    def check(self, method, path, body_text):
        if method in ("GET", "HEAD"):
            return
        plain = urllib.parse.unquote(path.split("?", 1)[0])
        full = urllib.parse.unquote(path)
        if self.prefix in full or (body_text and self.prefix in body_text):
            return
        if plain in self.paths or any(plain.startswith(p) for p in self.path_prefixes):
            return
        raise SafetyError("refused to send %s %s: it does not name this run's prefix %s" % (method, plain, self.prefix))


class Resp:
    def __init__(self, status, headers, text):
        self.status = status
        self.headers = headers
        self.text = text
        self._json = False

    def json(self):
        if self._json is False:
            try:
                self._json = json.loads(self.text) if self.text else None
            except ValueError:
                self._json = None
        return self._json

    def error_text(self):
        data = self.json()
        if isinstance(data, dict):
            for key in ("error", "message"):
                if isinstance(data.get(key), str):
                    return data[key]
        return clip(self.text)


class Http:
    """One identity against one base URL. Redirects are never followed (an Authorization header must not follow a 30x)."""

    def __init__(self, base, auth=None, label="", cafile=None, timeout=60, guard=None, trace=None):
        parts = urllib.parse.urlsplit(base)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ValueError("the base URL must look like http(s)://host[:port]")
        self.scheme = parts.scheme
        self.host = parts.hostname
        self.port = parts.port
        self.base_path = parts.path.rstrip("/")
        self.label = label
        self.timeout = timeout
        self.guard = guard
        self.trace = trace
        self._auth_header = None
        if auth is not None:
            token = base64.b64encode(("%s:%s" % auth).encode("utf-8")).decode("ascii")
            self._auth_header = "Basic " + token
        self._ctx = None
        if self.scheme == "https":
            self._ctx = ssl.create_default_context(cafile=cafile) if cafile else ssl.create_default_context()

    def _connection(self):
        if self.scheme == "https":
            return http.client.HTTPSConnection(self.host, self.port, timeout=self.timeout, context=self._ctx)
        return http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)

    def request(self, method, path, body=None, content_type="application/json", headers=None):
        """path starts with '/' and is relative to the base URL; body is a str, dict or list (sent as JSON)."""
        if not path.startswith("/"):
            raise ValueError("path must start with /")
        body_text = None
        if body is not None:
            body_text = body if isinstance(body, str) else json.dumps(body)
        if self.guard is not None:
            self.guard.check(method, path, body_text)   # relative to the base URL: registered exact paths do not depend on a context path
        sent = {"Accept": "application/json", "User-Agent": "mdm-factory-itest"}
        sent.update(headers or {})
        headers = sent
        if self._auth_header:
            headers["Authorization"] = self._auth_header
        data = None
        if body_text is not None:
            data = body_text.encode("utf-8")
            headers["Content-Type"] = content_type
        elif method == "POST":
            headers["Content-Length"] = "0"
        conn = self._connection()
        try:
            conn.request(method, self.base_path + path, body=data, headers=headers)
            raw = conn.getresponse()
            payload = raw.read().decode("utf-8", "replace")
            resp = Resp(raw.status, dict((k.lower(), v) for k, v in raw.getheaders()), payload)
        except (OSError, http.client.HTTPException) as exc:
            resp = Resp(0, {}, "%s: %s" % (type(exc).__name__, exc))
        finally:
            conn.close()
        if self.trace:
            self.trace("%s %s %s -> %s" % (self.label, method, path.split("?", 1)[0], resp.status))
        return resp

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, body=None):
        return self.request("POST", path, body)


class Ds:
    """Minimal EBX Data Services REST (data/v1) client for the factory dataset: select, read, create, update, delete."""

    def __init__(self, http_client, dataspace, dataset):
        self.http = http_client
        self.root = "/ebx-dataservices/rest/data/v1/B%s/%s/root" % (urllib.parse.quote(dataspace), urllib.parse.quote(dataset))

    def table(self, name):
        return "%s/%s" % (self.root, name)

    def record(self, table, pk):
        return "%s/%s/%s" % (self.root, table, urllib.parse.quote(str(pk), safe=""))

    def get_row(self, table, pk):
        resp = self.http.get(self.record(table, pk))
        fields = row_fields(resp.json()) if resp.status == 200 else None
        return resp, fields

    def select(self, table, xpath_filter=None, page_size=200, max_pages=20):
        """(Resp of the first page, list of field maps). A page that is not 200 ends the read; the caller looks at the Resp."""
        query = "pageSize=%d" % page_size
        if xpath_filter:
            query += "&filter=" + urllib.parse.quote(xpath_filter, safe="")
        path = "%s?%s" % (self.table(table), query)
        rows = []
        first = None
        for _ in range(max_pages):
            resp = self.http.get(path)
            if first is None:
                first = resp
            data = resp.json()
            if resp.status != 200 or not isinstance(data, dict):
                return first if first.status != 200 else resp, rows
            for row in data.get("rows") or []:
                rows.append(row_fields(row))
            nxt = None
            pagination = data.get("pagination")
            if isinstance(pagination, dict):
                nxt = pagination.get("nextPage")
            if isinstance(nxt, dict):
                nxt = nxt.get("href")
            if not isinstance(nxt, str) or not nxt:
                break
            parts = urllib.parse.urlsplit(nxt)
            path = parts.path + ("?" + parts.query if parts.query else "")
            if self.http.base_path and path.startswith(self.http.base_path):
                path = path[len(self.http.base_path):]
        return first, rows

    def create(self, table, fields):
        return self.http.post(self.table(table), {"rows": [wrap_fields(fields)]})

    def update(self, table, pk, fields):
        return self.http.request("PUT", self.record(table, pk), wrap_fields(fields))

    def delete(self, table, pk):
        return self.http.request("DELETE", self.record(table, pk))
