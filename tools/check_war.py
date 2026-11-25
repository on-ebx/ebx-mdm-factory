#!/usr/bin/env python3
"""Offline self-sufficiency gate for the module's war (audit X3-07).

Every class referenced by the classes and the jars of the war must be found in the war, in the JDK, in Tomcat's lib/ or in a standard ON EBX
ebx.jar; the only references allowed to stay unresolved are those to the optional Match and Merge add-on (com.orchestranetworks.addon.*), and only from
the classes named in tools/optional-references.txt (each one behind a LinkageError guard). Also checks that the classes web.xml names exist.
It uses jdeps (JDK): nothing is deployed, nothing is started.

Usage: check_war.py <war or exploded war folder>
Environment:
  EBX_STANDARD_JAR  the ebx.jar of a standard ON EBX distribution (ebx-lib.jar of a FastTrack lib is the same jar). Without it the script looks for
                    ebx.jar / ebx-lib.jar in EBX_LIB_DIR; without any jar it falls back to a list of package prefixes ON EBX provides and SAYS SO:
                    that mode cannot see a class missing from ebx.jar under those prefixes.
  TOMCAT_LIB_DIR    Tomcat's lib/ folder. Without it the script trusts the prefixes Tomcat provides (jakarta.servlet., org.apache.catalina., ...).
  JAVA_HOME         the JDK whose jdeps is run (default: the one on PATH or /usr/libexec/java_home).
Exit code: 0 self-sufficient, 1 unresolved references, 2 the check itself could not run.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ADDON_PREFIX = "com.orchestranetworks.addon."
# what a standard ON EBX 6.3 server provides when no ebx.jar is at hand (ebx.jar: com.orchestranetworks.*, com.onwbp.*, jakarta.ws.rs.*; lib-mail: jakarta.mail.)
EBX_PREFIXES = ("com.orchestranetworks.", "com.onwbp.", "jakarta.ws.rs.", "jakarta.mail.")
# what Tomcat 10.1 provides to every web application
TOMCAT_PREFIXES = ("jakarta.servlet.", "jakarta.websocket.", "jakarta.el.", "jakarta.annotation.", "org.apache.catalina.", "org.apache.tomcat.",
                   "org.apache.coyote.", "org.apache.juli.")


def fail(message, code=2):
    sys.stderr.write(message + "\n")
    sys.exit(code)


def jdeps_binary():
    home = os.environ.get("JAVA_HOME", "")
    if not home and os.path.exists("/usr/libexec/java_home"):
        home = subprocess.run(["/usr/libexec/java_home"], capture_output=True, text=True).stdout.strip()
    candidate = os.path.join(home, "bin", "jdeps") if home else ""
    return candidate if candidate and os.path.exists(candidate) else (shutil.which("jdeps") or fail("MDMF-WARCHECK-1: jdeps (JDK) not found: set JAVA_HOME to a JDK"))


def core_jar():
    explicit = os.environ.get("EBX_STANDARD_JAR", "")
    if explicit:
        if not os.path.isfile(explicit):
            fail("MDMF-WARCHECK-2: EBX_STANDARD_JAR '%s' is not a file" % explicit)
        return explicit
    lib = os.environ.get("EBX_LIB_DIR", "")
    for name in ("ebx.jar", "ebx-lib.jar"):
        if lib and os.path.isfile(os.path.join(lib, name)):
            return os.path.join(lib, name)
    return ""


def jar_has_class(zf_cache, jar, class_name):
    zf = zf_cache.get(jar)
    if zf is None:
        zf = zf_cache[jar] = set(zipfile.ZipFile(jar).namelist())
    return class_name.replace(".", "/") + ".class" in zf


def outer(class_name):
    return class_name.split("$", 1)[0]


def read_optional():
    allowed = {}
    with open(os.path.join(HERE, "optional-references.txt"), encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                allowed[line.split()[0]] = line
    return allowed


def main():
    if len(sys.argv) != 2:
        fail(__doc__.split("Usage:")[1].split("Environment:")[0].strip(), 2)
    target = sys.argv[1]
    work = tempfile.mkdtemp(prefix="check-war-")
    try:
        if os.path.isdir(target):
            root = target
        else:
            root = os.path.join(work, "war")
            with zipfile.ZipFile(target) as zf:
                zf.extractall(root)
        classes = os.path.join(root, "WEB-INF", "classes")
        libs = sorted(f for f in os.listdir(os.path.join(root, "WEB-INF", "lib"))) if os.path.isdir(os.path.join(root, "WEB-INF", "lib")) else []
        if not os.path.isdir(classes):
            fail("MDMF-WARCHECK-3: no WEB-INF/classes in %s" % target)
        ebx = core_jar()
        tomcat = os.environ.get("TOMCAT_LIB_DIR", "")
        cp = [os.path.join(root, "WEB-INF", "lib", "*")] if libs else []
        if ebx:
            cp.append(ebx)
        if tomcat:
            cp.append(os.path.join(tomcat, "*"))
        out = subprocess.run([jdeps_binary(), "-verbose:class", "--multi-release", "base"] + (["-cp", os.pathsep.join(cp)] if cp else []) + [classes]
                             + [os.path.join(root, "WEB-INF", "lib", j) for j in libs if j.endswith(".jar")], capture_output=True, text=True)
        if out.returncode != 0:
            fail("MDMF-WARCHECK-4: jdeps failed:\n" + out.stderr + out.stdout)
        print("war: %s (%d jar(s) in WEB-INF/lib)" % (target, len(libs)))
        print("EBX classes from: %s" % (ebx if ebx else "NO ebx.jar given (EBX_STANDARD_JAR / EBX_LIB_DIR): package prefixes %s only, a class missing from the real ebx.jar is NOT detected" % (EBX_PREFIXES,)))
        print("Tomcat classes from: %s" % (tomcat if tomcat else "NO TOMCAT_LIB_DIR: package prefixes %s only" % (TOMCAT_PREFIXES,)))
        missing = {}
        for line in out.stdout.splitlines():
            m = re.match(r"^\s{3}(\S+)\s+->\s+(\S+)\s+not found$", line)
            if m:
                missing.setdefault(m.group(2), set()).add(m.group(1))
        allowed = read_optional()
        problems = {}
        optional = {}
        for target_class, carriers in missing.items():
            if target_class.startswith(ADDON_PREFIX):
                for carrier in carriers:
                    (optional if outer(carrier) in allowed else problems).setdefault(target_class, set()).add(carrier)
                continue
            if not ebx and target_class.startswith(EBX_PREFIXES):
                continue
            if not tomcat and target_class.startswith(TOMCAT_PREFIXES):
                continue
            problems[target_class] = carriers
        # a missing class that the add-on prefix hides in prefix-only mode is still the add-on: nothing to do. web.xml: every declared class must exist
        cache = {}
        webxml = os.path.join(root, "WEB-INF", "web.xml")
        if os.path.isfile(webxml):
            with open(webxml, encoding="utf-8") as fh:
                for name in re.findall(r"<(?:servlet|listener|filter)-class>\s*([\w.$]+)\s*<", fh.read()):
                    found = os.path.isfile(os.path.join(classes, name.replace(".", "/") + ".class"))
                    found = found or any(j.endswith(".jar") and jar_has_class(cache, os.path.join(root, "WEB-INF", "lib", j), name) for j in libs)
                    found = found or (bool(ebx) and jar_has_class(cache, ebx, name))
                    found = found or (not ebx and name.startswith(EBX_PREFIXES)) or (not tomcat and name.startswith(TOMCAT_PREFIXES))
                    if not found:
                        problems[name] = {"WEB-INF/web.xml"}
        if optional:
            print("\nOptional Match and Merge references (admitted: %d add-on class(es) named by %d listed class(es), see tools/optional-references.txt):" % (len(optional), len({outer(c) for v in optional.values() for c in v})))
            for carrier in sorted({outer(c) for v in optional.values() for c in v}):
                print("  " + carrier)
        if problems:
            print("\nUNRESOLVED: %d class(es) are referenced by the war but found nowhere (war, JDK, Tomcat, ebx.jar):" % len(problems))
            for name in sorted(problems):
                carriers = sorted(problems[name])
                print("  %s   <- %d class(es), e.g. %s" % (name, len(carriers), ", ".join(carriers[:3])))
            print("\nMDMF-WARCHECK-5: the war is NOT self-sufficient on a standard ON EBX server. Put the missing library in WEB-INF/lib (pinned in tools/embedded-jars.sha256,"
                  "\nwith its licence), or, if the class is provided by the server, say why in tools/check_war.py; do not hide it.")
            return 1
        print("\nOK: the war is self-sufficient (every referenced class is in the war, the JDK, Tomcat or ebx.jar).")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
