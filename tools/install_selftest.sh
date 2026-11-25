#!/usr/bin/env bash
# X3-08 (1.4.1): tools/install.sh against a FAKE workspace in a temporary folder - never the real one. The fake server lib/ is made of symbolic links to the real one
# (EBX_LIB_DIR, read only: nothing in it is written), the module copy already holds what an installation of an earlier build or of a person's hand leaves behind (a Jackson 2.14.2,
# a jar nobody pinned), and the check is what the installation leaves: WEB-INF/lib = exactly the five pinned jars with their pinned SHA-256, no other Jackson, the classes built
# against them, their hashes in version.properties, the Eclipse .classpath entries, the stamp and the context. It also runs install.sh twice (the second run is a no-op on the jars).
# Usage: EBX_LIB_DIR=<server lib folder> [INSTALL_SELFTEST_LOG=<file: keeps the first install.sh output>] tools/install_selftest.sh        (needs the same jar variables as tools/test_local.sh)
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
[[ -n "${EBX_LIB_DIR:-}" && -d "$EBX_LIB_DIR" ]] || { echo "MDMF-TEST-1: set EBX_LIB_DIR to a server lib folder" >&2; exit 3; }
fake="$(mktemp -d)"
trap 'rm -rf "$fake"' EXIT
ws="$fake/ebx-ps-fasttrack"
mkdir -p "$ws/ebx-ps-fasttrack-server/lib" "$ws/ebx-ps-fasttrack-server/conf/Catalina/localhost"
for jar in "$EBX_LIB_DIR"/*; do ln -s "$jar" "$ws/ebx-ps-fasttrack-server/lib/$(basename "$jar")"; done
# the Eclipse .classpath of a 1.4.0 installation: Jackson of an older version, the server project, the two Connectors jars
mkdir -p "$ws/ebx-mdm-factory"
cat > "$ws/ebx-mdm-factory/.classpath" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<classpath>
	<classpathentry kind="src" output="target/classes" path="src/main/java"/>
	<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
	<classpathentry kind="lib" path="src/main/webapp/WEB-INF/lib/jackson-core-2.14.2.jar"/>
	<classpathentry combineaccessrules="false" kind="src" path="/ebx-ps-fasttrack-server"/>
	<classpathentry kind="lib" path="src/main/webapp/WEB-INF/lib/on-ebx-agent.jar"/>
	<classpathentry kind="output" path="target/classes"/>
</classpath>
XML
# what an earlier installation (1.4.0: the two Connectors jars) or a person leaves in the module copy
old_lib="$ws/ebx-mdm-factory/src/main/webapp/WEB-INF/lib"
mkdir -p "$old_lib"
printf 'not a jar' > "$old_lib/jackson-core-2.14.2.jar"
printf 'not a jar' > "$old_lib/jackson-databind-2.14.2.jar"
printf 'not a jar' > "$old_lib/jackson-annotations.jar"
printf 'not a jar' > "$old_lib/handmade.jar"
printf 'not a jar' > "$old_lib/jackson-core-2.18.12.jar"   # a pinned name with another content: must be replaced
fail() { echo "install_selftest: $1" >&2; echo "--- install.sh output:" >&2; cat "$fake/install.log" >&2; exit 1; }

EBX_WORKSPACE="$ws" "$repo_dir/tools/install.sh" > "$fake/install.log" 2>&1 || fail "install.sh failed"
grep -q "rsync --delete will remove jackson-core-2.14.2.jar" "$fake/install.log" || fail "the removal of the old Jackson was not announced"
grep -q "rsync --delete will remove handmade.jar" "$fake/install.log" || fail "the removal of the unknown jar was not announced"
grep -q "rsync will replace jackson-core-2.18.12.jar" "$fake/install.log" || fail "the replacement of a pinned name with other content was not announced"
installed="$(cd "$old_lib" && ls)"
expected="$(awk '{ print $2 }' "$repo_dir/tools/embedded-jars.sha256" | LC_ALL=C sort)"
[[ "$installed" == "$expected" ]] || fail "WEB-INF/lib is [$(echo $installed)], expected [$(echo $expected)]"
(cd "$old_lib" && shasum -a 256 -c "$repo_dir/tools/embedded-jars.sha256" >/dev/null 2>&1) || fail "a jar of WEB-INF/lib is not the pinned build"
ls "$old_lib" | grep -q '2\.14\.2' && fail "a 2.14.2 jar is left"
props="$ws/ebx-mdm-factory/target/classes/com/on/ebx/factory/version.properties"
for name in jackson-core-2.18.12.jar jackson-databind-2.18.12.jar jackson-annotations-2.18.12.jar; do
	grep -q "^embeddedJar.$name=" "$props" || fail "version.properties does not list $name"
done
grep -q '^agentJarSha256=.\+' "$props" || fail "version.properties lacks the agent jar hash"
# the classes were compiled against 2.18.12: StreamReadConstraints (2.15+) is referenced by JsonLimits
javap -c -cp "$ws/ebx-mdm-factory/target/classes" com.on.ebx.factory.core.JsonLimits | grep -q "StreamReadConstraints" || fail "the installed classes were not compiled against Jackson 2.15 or later"
[[ -f "$ws/.ebx-mdm-factory-install-stamp" && -f "$ws/ebx-ps-fasttrack-server/conf/Catalina/localhost/ebx-mdm-factory.xml" ]] || fail "stamp or context missing"
grep -q "refresh the project in Eclipse (F5)" "$fake/install.log" || fail "the F5 reminder is missing"
# the .classpath: amended in place (not replaced by the repository's), Jackson in order BEFORE the server project, the old Jackson entry gone, the Connectors jars present, backup kept
cp_file="$ws/ebx-mdm-factory/.classpath"
python3 - "$cp_file" <<'PY' || fail ".classpath check failed (see above)"
import re, sys
lines = open(sys.argv[1]).read().splitlines()
paths = [re.search(r'path="([^"]+)"', l).group(1) for l in lines if 'classpathentry' in l]
lib = "src/main/webapp/WEB-INF/lib/"
server = paths.index("/ebx-ps-fasttrack-server")
for n in ("jackson-annotations-2.18.12.jar", "jackson-core-2.18.12.jar", "jackson-databind-2.18.12.jar"):
    assert lib + n in paths, n + " missing"
    assert paths.index(lib + n) < server, n + " is not before the server project"
assert not any("2.14.2" in p for p in paths), "an old Jackson entry is left"
for n in ("on-ebx-agent.jar", "on-ebx-mapping.jar"):
    assert paths.count(lib + n) == 1, n + " not exactly once"
assert paths.index(lib + "on-ebx-agent.jar") > server, "the agent jar entry moved"
assert paths[0] == "src/main/java" and paths[-1] == "target/classes", "other entries moved"
PY
ls "$ws/ebx-mdm-factory"/.classpath.bak-* >/dev/null 2>&1 || fail "no .classpath backup"
grep -q "^classpath: amended" "$fake/install.log" || fail "the first run did not say it amended the .classpath"
cp "$cp_file" "$fake/classpath.after1"
# second run: nothing to remove, nothing to replace
EBX_WORKSPACE="$ws" "$repo_dir/tools/install.sh" > "$fake/install2.log" 2>&1 || { cat "$fake/install2.log" >&2; fail "the second install.sh failed"; }
grep -q "rsync" "$fake/install2.log" && { cat "$fake/install2.log" >&2; fail "the second run announced a removal or a replacement"; }
[[ "$(cd "$old_lib" && ls)" == "$expected" ]] || fail "the second run changed WEB-INF/lib"
grep -q "^classpath: .*(unchanged)" "$fake/install2.log" || fail "the second run did not report the .classpath unchanged"
cmp -s "$cp_file" "$fake/classpath.after1" || fail "the second run rewrote the .classpath"
[[ "$(ls "$ws/ebx-mdm-factory"/.classpath.bak-* | wc -l | tr -d ' ')" == "1" ]] || fail "the second run made another backup"
# a workspace without a .classpath: none is created, and the output says so
ws2="$fake/ws2/ebx-ps-fasttrack"
mkdir -p "$ws2/ebx-ps-fasttrack-server/lib" "$ws2/ebx-ps-fasttrack-server/conf/Catalina/localhost"
for jar in "$EBX_LIB_DIR"/*; do ln -s "$jar" "$ws2/ebx-ps-fasttrack-server/lib/$(basename "$jar")"; done
EBX_WORKSPACE="$ws2" "$repo_dir/tools/install.sh" > "$fake/install3.log" 2>&1 || { cat "$fake/install3.log" >&2; fail "install.sh without a .classpath failed"; }
[[ ! -e "$ws2/ebx-mdm-factory/.classpath" ]] || fail "a .classpath was created in a workspace that had none"
grep -q "^classpath: .*does not exist" "$fake/install3.log" || fail "the missing .classpath was not said"
[[ -z "${INSTALL_SELFTEST_LOG:-}" ]] || cp "$fake/install.log" "$INSTALL_SELFTEST_LOG"
echo "install_selftest OK: WEB-INF/lib = [$(echo $installed)]"
