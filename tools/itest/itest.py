#!/usr/bin/env python3
"""Integration test suite of the MDM Factory against a RUNNING ON EBX instance (audit F-05). Python 3 standard library only.

Usage:   EBX_AUTH=<administrator>:<password> EBX_AUTH_NOROLE=<account with no Factory right>:<password> tools/itest.sh <base-url> [--only 1,2,4]
Env:     EBX_AUTH, EBX_AUTH_NOROLE   credentials (both required; ITEST_SKIP_NOROLE=1 runs the administrator part only)
         EBX_MODULE                  module name in the URL (default ebx-mdm-factory)
         ITEST_FULL=1                also run scenario 5 (heavy: creates models, dataspaces, flows and a matching configuration)
         ITEST_RIGHTS=0              skip scenario 3 (the temporary 'Everyone' row of Access rights)
         ITEST_STRICT=1              a SKIPPED check counts as a failure
         ITEST_CACERT=<pem file>     CA bundle for an https instance with a private certificate
         ITEST_ALLOW_INSECURE_HTTP=1 allow credentials in clear over http to a host that is not localhost
         ITEST_JOB_TIMEOUT=<s>       wait for a background job (default 90), ITEST_FULL_TIMEOUT=<s> for scenario 5 (default 1800)
         ITEST_TRACE=1               print "METHOD path -> status" of every request (never headers, bodies or credentials)
Exit:    0 every check passed (skips are listed), 1 at least one FAIL, 2 environment or arguments missing or refused.
"""
import os
import signal
import sys
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import client  # noqa: E402
import scenarios  # noqa: E402


class Reporter:
    def __init__(self, out=None):
        self.out = out or sys.stdout
        self.counts = {"PASS": 0, "FAIL": 0, "SKIP": 0}
        self.skips = []

    def _line(self, status, label, detail):
        self.counts[status] += 1
        if status == "SKIP":
            self.skips.append(label)
        self.out.write("%-5s %s%s\n" % (status, label, " - " + detail if detail else ""))
        self.out.flush()

    def passed(self, label, detail=""):
        self._line("PASS", label, detail)

    def failed(self, label, detail=""):
        self._line("FAIL", label, detail)

    def skipped(self, label, detail=""):
        self._line("SKIP", label, detail)

    def section(self, title):
        self.out.write("\n== %s\n" % title)
        self.out.flush()

    def note(self, text):
        self.out.write("note  %s\n" % text)
        self.out.flush()


SCENARIOS = [
    ("1", "status and liveness", scenarios.scenario_status),
    ("2", "route families, no-role account", scenarios.scenario_no_role),
    ("3", "rights by level", scenarios.scenario_rights),
    ("4", "scratch project lifecycle", scenarios.scenario_lifecycle),
    ("5", "end to end (ITEST_FULL)", scenarios.scenario_full),
]


def make_prefix(now=None):
    return "ITEST" + time.strftime("%Y%m%d%H%M%S", time.gmtime(now if now is not None else time.time()))


def run(argv, env, out=None):
    out = out or sys.stdout
    only = None
    args = list(argv)
    base = None
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--only" and i + 1 < len(args):
            only = set(x.strip() for x in args[i + 1].split(",") if x.strip())
            i += 2
            continue
        if a == "--list":
            for key, title, _ in SCENARIOS:
                out.write("%s  %s\n" % (key, title))
            return 0
        if a in ("-h", "--help"):
            out.write(__doc__)
            return 0
        if base is None and not a.startswith("-"):
            base = a
        else:
            sys.stderr.write("unexpected argument: %s\n" % a)
            return 2
        i += 1
    if not base:
        sys.stderr.write("usage: EBX_AUTH=user:password EBX_AUTH_NOROLE=user:password tools/itest.sh <base-url> [--only 1,2,4]\n")
        return 2
    base = base.rstrip("/")
    admin_auth = client.parse_auth(env.get("EBX_AUTH", ""))
    if admin_auth is None:
        sys.stderr.write("EBX_AUTH must be set to administrator:password\n")
        return 2
    norole_auth = client.parse_auth(env.get("EBX_AUTH_NOROLE", ""))
    if norole_auth is None and env.get("ITEST_SKIP_NOROLE") != "1":
        sys.stderr.write("EBX_AUTH_NOROLE must be set to account:password (an account with no MDM Factory right); ITEST_SKIP_NOROLE=1 runs without it\n")
        return 2
    if env.get("EBX_AUTH_NOROLE") and norole_auth is None:
        sys.stderr.write("EBX_AUTH_NOROLE must look like account:password\n")
        return 2
    try:
        parts = urllib.parse.urlsplit(base)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise ValueError
    except ValueError:
        sys.stderr.write("the base URL must look like http(s)://host[:port]\n")
        return 2
    if parts.scheme == "http" and not client.is_local_host(parts.hostname) and env.get("ITEST_ALLOW_INSECURE_HTTP") != "1":
        sys.stderr.write("refusing to send credentials in clear over http to %s (use https, or ITEST_ALLOW_INSECURE_HTTP=1 on a trusted network)\n" % parts.hostname)
        return 2
    if only is not None:
        unknown = only - set(k for k, _, _ in SCENARIOS)
        if unknown:
            sys.stderr.write("unknown scenario(s): %s\n" % ", ".join(sorted(unknown)))
            return 2
    if env.get("ITEST_FULL") and only is None:
        only = set(k for k, _, _ in SCENARIOS)
    elif only is None:
        only = set(k for k, _, _ in SCENARIOS if k != "5")
    if "5" in only and not env.get("ITEST_FULL"):
        sys.stderr.write("scenario 5 is heavy: set ITEST_FULL=1 to run it\n")
        return 2

    prefix = make_prefix()
    guard = client.Guard(prefix)
    module = env.get("EBX_MODULE", "ebx-mdm-factory")
    cafile = env.get("ITEST_CACERT") or None
    trace = (lambda line: out.write("trace " + line + "\n")) if env.get("ITEST_TRACE") == "1" else None
    admin = client.Http(base, admin_auth, "admin", cafile, guard=guard, trace=trace)
    norole = client.Http(base, norole_auth, "norole", cafile, guard=guard, trace=trace) if norole_auth else None
    anon = client.Http(base, None, "anon", cafile, guard=guard, trace=trace)
    rep = Reporter(out)
    ctx = scenarios.Ctx(rep, base, module, admin, norole, anon, prefix, guard, env)
    out.write("integration test of %s (module %s), run %s\n" % (base, module, prefix))

    def on_signal(signum, frame):
        raise KeyboardInterrupt()
    old = {}
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            old[sig] = signal.signal(sig, on_signal)
        except (ValueError, OSError):
            pass
    interrupted = False
    try:
        if "1" not in only and only & {"3", "4", "5"}:
            scenarios.load_admin_status(ctx)   # the dataset the later scenarios write in comes from the administrator's /status
        for key, title, fn in SCENARIOS:
            if key not in only:
                continue
            try:
                fn(ctx)
            except client.SafetyError as exc:
                rep.failed("scenario %s stopped by the safety guard" % key, str(exc))
            except KeyboardInterrupt:
                raise
            except Exception as exc:   # a bug in the suite must not leave scratch objects behind
                rep.failed("scenario %s crashed" % key, "%s: %s" % (type(exc).__name__, exc))
    except KeyboardInterrupt:
        interrupted = True
        rep.failed("run interrupted", "cleaning up")
    finally:
        try:
            scenarios.scenario_cleanup(ctx)
        except client.SafetyError as exc:
            rep.failed("clean-up stopped by the safety guard", str(exc))
        except Exception as exc:
            rep.failed("clean-up crashed", "%s: %s" % (type(exc).__name__, exc))
        for sig, handler in old.items():
            signal.signal(sig, handler)
    if ctx.leftovers:
        out.write("\nLEFT BEHIND (remove by hand):\n")
        for line in sorted(set(ctx.leftovers), key=ctx.leftovers.index):
            out.write("  - %s\n" % line)
    strict = env.get("ITEST_STRICT") == "1"
    failed = rep.counts["FAIL"] + (rep.counts["SKIP"] if strict else 0)
    out.write("\n%d passed, %d failed, %d skipped\n" % (rep.counts["PASS"], rep.counts["FAIL"], rep.counts["SKIP"]))
    if rep.skips:
        out.write("skipped checks are NOT passes: %s\n" % "; ".join(rep.skips[:6]) + (" ..." if len(rep.skips) > 6 else ""))
    if interrupted:
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:], os.environ))
