#!/usr/bin/env python3
"""Self-test of tools/check_war.py (X3-07): a war that names Jackson without carrying it fails, the same war with the jars passes, an add-on reference
from a class that is not listed in optional-references.txt fails, and one from a listed class passes. Compiles two tiny classes with the JDK; no EBX instance.
Usage: check_war_selftest.py <server lib folder holding jackson-core/-databind/-annotations.jar and ebx-addons.jar>"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join(HERE, "check_war.py")


def run(war, env_extra=None):
    env = dict(os.environ)
    env.pop("EBX_STANDARD_JAR", None)
    env.pop("EBX_LIB_DIR", None)
    env.pop("TOMCAT_LIB_DIR", None)
    env.update(env_extra or {})
    result = subprocess.run([sys.executable, CHECK, war], capture_output=True, text=True, env=env)
    return result.returncode, result.stdout + result.stderr


def javac(work, cp, name, body):
    src = os.path.join(work, "src")
    package = name.rsplit(".", 1)[0]
    os.makedirs(os.path.join(src, package.replace(".", "/")), exist_ok=True)
    path = os.path.join(src, name.replace(".", "/") + ".java")
    with open(path, "w") as fh:
        fh.write("package %s;\npublic class %s { %s }\n" % (package, name.rsplit(".", 1)[1], body))
    subprocess.run(["javac", "-nowarn", "-cp", cp, "-d", os.path.join(work, "classes"), path], check=True, capture_output=True)


def expect(what, condition, detail=""):
    if not condition:
        sys.stderr.write("check_war selftest FAILED: %s\n%s\n" % (what, detail))
        sys.exit(1)


def main():
    lib = sys.argv[1]
    cp = os.pathsep.join(os.path.join(lib, j) for j in ("jackson-core.jar", "jackson-databind.jar", "jackson-annotations.jar", "ebx-addons.jar"))
    work = tempfile.mkdtemp(prefix="check-war-selftest-")
    try:
        war = os.path.join(work, "war")
        os.makedirs(os.path.join(war, "WEB-INF", "lib"))
        javac(work, cp, "com.example.needsjackson.UsesJackson", "Object o() { return new com.fasterxml.jackson.databind.ObjectMapper(); }")
        shutil.copytree(os.path.join(work, "classes"), os.path.join(war, "WEB-INF", "classes"))
        code, out = run(war)
        expect("a war naming Jackson without carrying it must fail", code == 1 and "com.fasterxml.jackson.databind.ObjectMapper" in out, out)
        for jar in ("jackson-core.jar", "jackson-databind.jar", "jackson-annotations.jar"):
            shutil.copy(os.path.join(lib, jar), os.path.join(war, "WEB-INF", "lib", jar))
        code, out = run(war)
        expect("the same war with the three Jackson jars must pass", code == 0, out)
        # the add-on is optional, but only from the classes listed in tools/optional-references.txt
        javac(work, cp, "com.example.needsjackson.UsesAddon", "Object o() { return com.orchestranetworks.addon.mame.model.State.class; }")
        shutil.rmtree(os.path.join(war, "WEB-INF", "classes"))
        shutil.copytree(os.path.join(work, "classes"), os.path.join(war, "WEB-INF", "classes"))
        code, out = run(war)
        expect("an add-on reference from an unlisted class must fail", code == 1 and "com.orchestranetworks.addon.mame.model.State" in out, out)
        listed = os.path.join(work, "classes", "com", "on", "ebx", "factory", "ebx")
        os.makedirs(listed)
        src = os.path.join(work, "src", "com", "on", "ebx", "factory", "ebx")
        os.makedirs(src)
        javac(work, cp, "com.on.ebx.factory.ebx.MameMatchingApi", "Object o() { return com.orchestranetworks.addon.mame.model.State.class; }")
        os.remove(os.path.join(work, "classes", "com", "example", "needsjackson", "UsesAddon.class"))
        shutil.rmtree(os.path.join(war, "WEB-INF", "classes"))
        shutil.copytree(os.path.join(work, "classes"), os.path.join(war, "WEB-INF", "classes"))
        code, out = run(war)
        expect("an add-on reference from a listed class must pass and name its carrier", code == 0 and "com.on.ebx.factory.ebx.MameMatchingApi" in out, out)
        # web.xml names a class that exists nowhere
        with open(os.path.join(war, "WEB-INF", "web.xml"), "w") as fh:
            fh.write("<web-app><servlet><servlet-name>x</servlet-name><servlet-class>com.example.Nowhere</servlet-class></servlet></web-app>")
        code, out = run(war)
        expect("a web.xml class found nowhere must fail", code == 1 and "com.example.Nowhere" in out, out)
        print("check_war selftest: ok")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
