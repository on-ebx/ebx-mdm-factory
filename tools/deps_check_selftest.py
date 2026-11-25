#!/usr/bin/env python3
"""Self-test of tools/deps_check.py (X3-08), offline: it replays a recorded OSV answer (tools/deps_check_fixtures/, taken from https://api.osv.dev on 2026-11-12 for Jackson 2.14.2,
the jars of 1.4.0) and a fake curl on PATH for the unreachable case. Run by tools/test_local.sh; needs no network."""
import json
import os
import stat
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "deps_check_fixtures")
CHECK = os.path.join(HERE, "deps_check.py")
FAILURES = []


def run(args, env=None):
    result = subprocess.run([sys.executable, "-I", CHECK] + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env or dict(os.environ))
    return result.returncode, result.stdout.decode(), result.stderr.decode()


def check(what, ok):
    if not ok:
        FAILURES.append(what)
        print("FAIL: " + what)


def main():
    recorded = os.path.join(FIX, "osv-jackson-2.14.2-2026-11-12.json")
    old = ["--pin", os.path.join(FIX, "pins-1.4.0.sha256"), "--maven", os.path.join(FIX, "maven-1.4.0.txt"), "--osv-response", recorded]
    with tempfile.TemporaryDirectory() as tmp:
        empty_known = os.path.join(tmp, "known-empty.txt")
        open(empty_known, "w").write("# none\n")
        code, out, err = run(old + ["--known", empty_known])
        check("1.4.0 jars: exit 1 (advisories not accepted)", code == 1)
        check("the Jackson 2.14.2 advisories come out: 3 on jackson-core, 11 on jackson-databind", "jackson-core-2.14.2.jar  com.fasterxml.jackson.core:jackson-core:2.14.2  3 advisories" in out and "jackson-databind-2.14.2.jar  com.fasterxml.jackson.core:jackson-databind:2.14.2  11 advisories" in out)
        check("and a named one: the stack-overflow advisory of the deep nesting", "GHSA-h46c-h94j-95f3 (CVE-2025-52999) [HIGH] fixed in 2.15.0" in out)
        check("the refusal is coded", "MDMF-DEPS-1: 14 advisories are not accepted" in err)
        check("the two Connectors jars are said to have no coordinates", out.count("no Maven coordinates") == 2)
        # accepting every advisory by id, with a date and a reason, clears the gate and says so
        ids = sorted(json.load(open(recorded))["vulns"].keys())
        known = os.path.join(tmp, "known-all.txt")
        with open(known, "w") as handle:
            for jar in ("jackson-core-2.14.2.jar", "jackson-databind-2.14.2.jar"):
                for vid in ids:
                    handle.write("%s %s 2026-11-12 test acceptance\n" % (jar, vid))
        code, out, err = run(old + ["--known", known])
        check("every advisory accepted: exit 0", code == 0)
        check("accepted lines say when and why", "accepted 2026-11-12: test acceptance" in out and "NOT ACCEPTED" not in out)
        # one accepted line short: exit 1 again
        short = os.path.join(tmp, "known-short.txt")
        lines = open(known).read().splitlines()
        open(short, "w").write("\n".join(lines[:-1]) + "\n")
        code, out, err = run(old + ["--known", short])
        check("one advisory not accepted: exit 1", code == 1 and "NOT ACCEPTED" in out)
        # the current pins: a clean answer (OSV has no advisory for Jackson 2.18.12 on 2026-11-12) passes
        clean = os.path.join(tmp, "clean.json")
        json.dump({"batch": {"results": [{}, {}, {}]}, "vulns": {}}, open(clean, "w"))
        code, out, err = run(["--osv-response", clean])
        check("the pinned jars with a clean answer: exit 0", code == 0 and "no unaccepted advisory" in out)
        check("the tracked known file is empty", "0 accepted lines" in out)
        check("the current pins name Jackson 2.18.12 only", "2.14.2" not in out and out.count("2.18.12") >= 3)
        # unreachable: a fake curl that fails
        bin_dir = os.path.join(tmp, "bin")
        os.mkdir(bin_dir)
        fake = os.path.join(bin_dir, "curl")
        open(fake, "w").write("#!/bin/sh\necho 'curl: (6) Could not resolve host: api.osv.dev' >&2\nexit 6\n")
        os.chmod(fake, os.stat(fake).st_mode | stat.S_IEXEC)
        env = dict(os.environ)
        env["PATH"] = bin_dir + os.pathsep + env.get("PATH", "")
        code, out, err = run([], env)
        check("no network, no waiver: refused with exit 2 and MDMF-DEPS-2", code == 2 and "MDMF-DEPS-2" in err)
        code, out, err = run(["--waiver", "built on a plane"], env)
        check("no network, named waiver: exit 0 and it says the advisories were NOT checked", code == 0 and "DEPS-CHECK WAIVED: advisories were NOT checked" in out and "built on a plane" in out)
    if FAILURES:
        print("deps_check_selftest: %d failure(s)" % len(FAILURES))
        return 1
    print("deps_check_selftest OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
