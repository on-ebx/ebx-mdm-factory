#!/usr/bin/env python3
"""Amends the Eclipse .classpath of the INSTALLED module (tools/install.sh, X3-08 / 1.4.1).

usage: classpath_amend.py <.classpath> <pinned jar name>...      (the names of tools/embedded-jars.sha256)

Eclipse resolves a class path in order, and the project entry /ebx-ps-fasttrack-server exports the 2.14.2 Jackson of the server lib/. So for each pinned Jackson jar this puts
  <classpathentry kind="lib" path="src/main/webapp/WEB-INF/lib/<jar>"/>
BEFORE that project entry, and removes any `lib` entry of another build of the same artifact (jackson-core-2.14.2.jar, jackson-core.jar...). The Connectors jars are added (before
the output entry) only if missing; every other entry stays where it is. Idempotent: an unchanged file is not rewritten; the first change keeps the old file as .classpath.bak-<date>.
Prints one line. Exit 0 always except unreadable input (exit 2).
"""
import datetime
import os
import re
import shutil
import sys

LIB = "src/main/webapp/WEB-INF/lib/"
JACKSON = re.compile(r"^(jackson-(?:core|databind|annotations))(?:-[0-9][^/]*)?\.jar$")


def entry(name):
    return '\t<classpathentry kind="lib" path="%s%s"/>' % (LIB, name)


def lib_name(line):
    match = re.search(r'kind="lib"\s+path="%s([^"]+)"' % re.escape(LIB), line)
    return match.group(1) if match else None


def main(argv):
    path, pinned = argv[1], argv[2:]
    try:
        original = open(path, encoding="utf-8").read()
    except OSError as exc:
        print("classpath_amend: cannot read %s: %s" % (path, exc), file=sys.stderr)
        return 2
    lines = original.split("\n")
    pinned_jackson = [n for n in pinned if JACKSON.match(n)]
    artifacts = set(JACKSON.match(n).group(1) for n in pinned_jackson)
    kept = []
    for line in lines:
        name = lib_name(line)
        if name and JACKSON.match(name) and JACKSON.match(name).group(1) in artifacts:
            continue  # re-inserted below, in the right place
        kept.append(line)
    server = next((i for i, l in enumerate(kept) if 'path="/ebx-ps-fasttrack-server"' in l and 'kind="src"' in l), None)
    if server is None:
        server = next((i for i, l in enumerate(kept) if 'kind="output"' in l), len(kept))
    kept[server:server] = [entry(n) for n in sorted(pinned_jackson)]
    present = set(lib_name(l) for l in kept)
    missing = [n for n in pinned if not JACKSON.match(n) and n not in present]
    if missing:
        output = next((i for i, l in enumerate(kept) if 'kind="output"' in l), len(kept))
        kept[output:output] = [entry(n) for n in missing]
    result = "\n".join(kept)
    if result == original:
        print("classpath: %s already lists the pinned jars, Jackson before the server project (unchanged)" % path)
        return 0
    backup = "%s.bak-%s" % (path, datetime.date.today().strftime("%Y%m%d"))
    if not os.path.exists(backup):
        shutil.copy2(path, backup)
    open(path, "w", encoding="utf-8").write(result)
    print("classpath: amended %s (Jackson %s before the server project; old file kept as %s)" % (path, ", ".join(sorted(pinned_jackson)), os.path.basename(backup)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
