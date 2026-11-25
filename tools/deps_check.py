#!/usr/bin/env python3
"""Dependency advisory check of the embedded jars (X3-08, 1.4.1).

Reads the jars the war embeds (tools/embedded-jars.sha256), their Maven coordinates (tools/embedded-jars-maven.txt), asks OSV (https://api.osv.dev, public package names
and versions only) about each one with curl, compares the advisories with the accepted ones (tools/deps-known.txt, empty today) and exits 1 on any advisory that is not accepted.

  exit 0  nothing unaccepted (or the check was waived by name, said in the output)
  exit 1  at least one advisory that tools/deps-known.txt does not accept
  exit 2  OSV could not be asked (no network, curl missing, unreadable answer) and no waiver was given: the check is a gate, an unanswered gate refuses (MDMF-DEPS-2)

Options:
  --pin FILE / --maven FILE / --known FILE   other files than the tracked ones (the 1.4.0 pins, for a before/after)
  --osv-response FILE                        replay a recorded OSV answer instead of asking the network (tools/deps_check_selftest.py; {"batch": <querybatch answer>, "vulns": {id: <vuln>}})
  --record FILE                              write the answers received from OSV into FILE in that shape (to make a fixture)
  --waiver REASON                            the named waiver: with no network the check passes, and says in its output and in the exit text that advisories were NOT checked and why.
                                             tools/package.sh passes DEPS_OFFLINE_WAIVER here and writes the line into the release notes of the package.
python3 only for the logic: HTTPS goes through curl (the workstation's Python has no root certificates).
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/"


def read_pins(path):
    pins = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) == 2:
                pins.append((parts[1], parts[0]))
    return pins


def read_maven(path):
    out = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, coord = line.split(None, 1)
            out[name] = coord.strip()
    return out


def read_known(path):
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 3)
            if len(parts) < 4:
                raise SystemExit("MDMF-DEPS-3: %s: a line needs <jar> <advisory id> <date> <reason>: %r" % (path, line))
            out[(parts[0], parts[1])] = (parts[2], parts[3])
    return out


class Unreachable(Exception):
    pass


def curl_json(url, body=None):
    command = ["curl", "-sS", "--fail", "--max-time", "40", "-H", "Accept: application/json"]
    if body is not None:
        command += ["-H", "Content-Type: application/json", "--data-binary", "@-"]
    command.append(url)
    try:
        result = subprocess.run(command, input=None if body is None else json.dumps(body).encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Unreachable("%s: %s" % (url, exc))
    if result.returncode != 0:
        raise Unreachable("%s: curl exit %d: %s" % (url, result.returncode, result.stderr.decode("utf-8", "replace").strip()))
    try:
        return json.loads(result.stdout.decode("utf-8"))
    except ValueError as exc:
        raise Unreachable("%s: the answer is not JSON: %s" % (url, exc))


def fixed_versions(vuln, package_name):
    out = []
    for affected in vuln.get("affected", []):
        if affected.get("package", {}).get("name") != package_name:
            continue
        for rng in affected.get("ranges", []):
            for event in rng.get("events", []):
                if "fixed" in event and event["fixed"] not in out:
                    out.append(event["fixed"])
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pin", default=os.path.join(HERE, "embedded-jars.sha256"))
    parser.add_argument("--maven", default=os.path.join(HERE, "embedded-jars-maven.txt"))
    parser.add_argument("--known", default=os.path.join(HERE, "deps-known.txt"))
    parser.add_argument("--osv-response")
    parser.add_argument("--record")
    parser.add_argument("--waiver")
    args = parser.parse_args()

    pins = read_pins(args.pin)
    maven = read_maven(args.maven)
    known = read_known(args.known)
    queries = []
    for name, _ in pins:
        if name in maven:
            group_artifact, version = maven[name].rsplit(":", 1)
            queries.append((name, maven[name], {"package": {"ecosystem": "Maven", "name": group_artifact}, "version": version}))
    print("deps_check: %d pinned jars (%s), %d with Maven coordinates (%s); advisories from OSV (%s)"
          % (len(pins), os.path.basename(args.pin), len(queries), os.path.basename(args.maven), "recorded answer " + args.osv_response if args.osv_response else "https://api.osv.dev"))

    recorded = {"batch": None, "vulns": {}}
    try:
        if args.osv_response:
            with open(args.osv_response, encoding="utf-8") as handle:
                recorded = json.load(handle)
            batch = recorded["batch"]
            vuln_for = lambda vid: recorded["vulns"][vid]
        else:
            batch = curl_json(OSV_BATCH, {"queries": [q[2] for q in queries]}) if queries else {"results": []}
            recorded["batch"] = batch

            def vuln_for(vid):
                if vid not in recorded["vulns"]:
                    recorded["vulns"][vid] = curl_json(OSV_VULN + vid)
                return recorded["vulns"][vid]
        results = batch.get("results", [])
        if len(results) != len(queries):
            raise Unreachable("OSV answered %d results for %d questions" % (len(results), len(queries)))
        findings = []
        for (name, coord, query), result in zip(queries, results):
            for entry in result.get("vulns", []):
                findings.append((name, coord, query["package"]["name"], vuln_for(entry["id"])))
    except (Unreachable, KeyError, ValueError) as exc:
        message = "MDMF-DEPS-2: the advisories of the embedded jars could not be checked: %s" % exc
        if args.waiver:
            print("DEPS-CHECK WAIVED: advisories were NOT checked (%s). Waiver: %s" % (exc, args.waiver))
            return 0
        print(message, file=sys.stderr)
        print("Check from a machine with network, or name a waiver (tools/package.sh: DEPS_OFFLINE_WAIVER=\"<reason>\"; the package then says the check was not done).", file=sys.stderr)
        return 2

    if args.record:
        with open(args.record, "w", encoding="utf-8") as handle:
            json.dump(recorded, handle, indent=1, sort_keys=True)

    for name, _ in pins:
        if name not in maven:
            print("%s: no Maven coordinates (ON EBX code built by the ebx-connectors repository; covered by that repository's own dependency check, tools/deps_check.sh there)" % name)
    unaccepted = 0
    by_jar = {}
    for finding in findings:
        by_jar.setdefault(finding[0], []).append(finding)
    for name, coord, _ in queries:
        found = sorted(by_jar.get(name, []), key=lambda f: f[3]["id"])
        print("%s  %s  %s" % (name, coord, "no advisory" if not found else "%d advisor%s" % (len(found), "y" if len(found) == 1 else "ies")))
        for _, _, package_name, vuln in found:
            aliases = [a for a in vuln.get("aliases", []) if a.startswith("CVE-")]
            severity = (vuln.get("database_specific") or {}).get("severity", "?")
            fixed = ", ".join(fixed_versions(vuln, package_name)) or "no fix listed"
            accepted = known.get((name, vuln["id"])) or next((known[(name, a)] for a in vuln.get("aliases", []) if (name, a) in known), None)
            status = "accepted %s: %s" % accepted if accepted else "NOT ACCEPTED"
            if not accepted:
                unaccepted += 1
            print("  %s%s [%s] fixed in %s -- %s -- %s" % (vuln["id"], " (" + ", ".join(aliases) + ")" if aliases else "", severity, fixed, (vuln.get("summary") or "").strip()[:110], status))
    if unaccepted:
        print("MDMF-DEPS-1: %d advisor%s not accepted in %s. Move to a fixed version, or accept it there with a date and a reason." % (unaccepted, "y is" if unaccepted == 1 else "ies are", os.path.basename(args.known)), file=sys.stderr)
        return 1
    print("deps_check: no unaccepted advisory (%d accepted line%s in %s)" % (len(known), "" if len(known) == 1 else "s", os.path.basename(args.known)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
