#!/usr/bin/env bash
# Local regression: regenerates the XSD and checks it is the committed one, compiles the module into a
# temporary folder (safe while Tomcat runs), compiles and runs the JUnit-free test classes.
# Usage: EBX_WORKSPACE=<.../Workspace/ebx-ps-fasttrack> tools/test_local.sh      (or EBX_LIB_DIR=<ebx-ps-fasttrack-server/lib>)
# Audit F2-114: no workstation path is built in (the workspace that used to be the default is stopped): one of the two variables is mandatory.
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
module_dir="$repo_dir/ebx-mdm-factory"
if [[ -n "${EBX_LIB_DIR:-}" ]]; then
	server_lib="$EBX_LIB_DIR"
elif [[ -n "${EBX_WORKSPACE:-}" ]]; then
	server_lib="$EBX_WORKSPACE/ebx-ps-fasttrack-server/lib"
else
	echo "MDMF-TEST-1: set EBX_WORKSPACE (the Eclipse workspace project folder .../Workspace/ebx-ps-fasttrack) or EBX_LIB_DIR (its ebx-ps-fasttrack-server/lib), then run again." >&2
	exit 3
fi
[[ -f "$server_lib/ebx-lib.jar" || -f "$server_lib/ebx.jar" ]] || { echo "MDMF-TEST-2: neither ebx-lib.jar (FastTrack name) nor ebx.jar (standard distribution name) under '$server_lib': fix EBX_WORKSPACE / EBX_LIB_DIR." >&2; exit 3; }
test_output="$(mktemp -d)"
trap 'rm -rf "$test_output"' EXIT

# audit F2-114: install.sh and test_local.sh build in no default workspace - without EBX_WORKSPACE, install.sh refuses (exit 3, MDMF-INSTALL-1) before it touches anything
set +e
refusal="$(env -u EBX_WORKSPACE "$repo_dir/tools/install.sh" 2>&1)"; refusal_code=$?
set -e
[[ "$refusal_code" == "3" && "$refusal" == *MDMF-INSTALL-1* ]] || { echo "install.sh without EBX_WORKSPACE must exit 3 with MDMF-INSTALL-1 (exit $refusal_code): $refusal" >&2; exit 1; }
# ... and the one-deploy-source guard: a workspace stamped by another checkout refuses (exit 4) unless EBX_INSTALL_TAKE_OVER=1
guard_ws="$(mktemp -d)"
printf 'source=/somewhere/else\n' > "$guard_ws/.ebx-mdm-factory-install-stamp"
set +e
guard="$(EBX_WORKSPACE="$guard_ws" "$repo_dir/tools/install.sh" 2>&1)"; guard_code=$?
set -e
rm -rf "$guard_ws"
[[ "$guard_code" == "4" && "$guard" == *MDMF-INSTALL-3* ]] || { echo "install.sh from another checkout must exit 4 with MDMF-INSTALL-3 (exit $guard_code): $guard" >&2; exit 1; }
# 1.4.2: package.sh refuses (exit 2, MDMF-PACKAGE-6) before doing anything when the self-sufficiency gate would degrade (no TOMCAT_LIB_DIR / EBX_STANDARD_JAR) - this suite needs neither
set +e
package_gate="$(env -u TOMCAT_LIB_DIR -u EBX_STANDARD_JAR EBX_LIB_DIR="$server_lib" "$repo_dir/tools/package.sh" 2>&1)"; package_gate_code=$?
package_gate_half="$(env -u TOMCAT_LIB_DIR EBX_STANDARD_JAR="$server_lib/ebx-lib.jar" EBX_LIB_DIR="$server_lib" "$repo_dir/tools/package.sh" 2>&1)"; package_gate_half_code=$?
set -e
[[ "$package_gate_code" == "2" && "$package_gate" == *MDMF-PACKAGE-6* && "$package_gate" == *TOMCAT_LIB_DIR* && "$package_gate" == *EBX_STANDARD_JAR* ]] \
	|| { echo "package.sh without TOMCAT_LIB_DIR and EBX_STANDARD_JAR must exit 2 with MDMF-PACKAGE-6 (exit $package_gate_code): $package_gate" >&2; exit 1; }
[[ "$package_gate_half_code" == "2" && "$package_gate_half" == *MDMF-PACKAGE-6* && "$package_gate_half" == *TOMCAT_LIB_DIR* ]] \
	|| { echo "package.sh without TOMCAT_LIB_DIR must exit 2 with MDMF-PACKAGE-6 (exit $package_gate_half_code): $package_gate_half" >&2; exit 1; }
# build.sh empties its output folder: a class left by a previous build does not survive
stale_out="$(mktemp -d)"; mkdir -p "$stale_out/gone" && : > "$stale_out/gone/Removed.class"

xsd="$module_dir/src/main/webapp/WEB-INF/ebx/schemas/MdmFactory.xsd"
EBX_WORKSPACE="$test_output/xsd" python3 "$repo_dir/tools/build_xsd.py" >/dev/null
diff -q "$test_output/xsd/ebx-mdm-factory/src/main/webapp/WEB-INF/ebx/schemas/MdmFactory.xsd" "$xsd" \
	|| { echo "MdmFactory.xsd is not what tools/build_xsd.py generates: re-run it" >&2; exit 1; }
xmllint --noout "$xsd"

# message bundles must be plain ASCII (accents as \uXXXX): a JVM or a loader that reads them as ISO-8859-1 would show mojibake.
# `grep -P` is a GNU extension - BSD grep (macOS) rejects it with "invalid option" and this check then silently
# no-ops (an error exit from grep alone, never reaching the "if" body) - found 2026-09-22, coordinator review.
# A plain bracket range (space..tilde, the printable-ASCII span, plus a literal tab) is portable POSIX ERE/BRE.
non_ascii_class="[^ -~$(printf '\t')]"
if LC_ALL=C grep -n "$non_ascii_class" $(find "$module_dir/src/main/resources" -name '*.properties') ; then
	echo "non-ASCII character in a .properties file: write it as \\uXXXX" >&2; exit 1
fi

# a stray "*/" or an unbalanced "/*" inside a factory/*.css header comment usually means the comment closed
# itself early, mid-sentence (an informal "class-a*/-class-b*" wildcard notation is exactly this shape) -
# CLAUDE.md-worthy lesson from 2026-09-21/22: factory/lineage.css lost EVERY rule this way for as long as it
# existed, with no build error anywhere, only caught by measuring `document.styleSheets` in a real browser;
# factory/wizard.css and factory/review.css each silently lost one rule the same way. Plain CSS never
# legitimately contains either two-character sequence outside a comment delimiter, so counting raw
# occurrences (no attempt to actually strip comments / reimplement a CSS parser) is a simple, robust check.
python3 -c "
import re, sys
bad = False
for path in sys.argv[1:]:
	text = open(path, encoding='utf-8').read()
	opens, closes = len(re.findall(r'/\*', text)), len(re.findall(r'\*/', text))
	if opens != closes:
		print('%s: %d occurrence(s) of \"/*\" vs %d of \"*/\" - a comment likely closes itself early or never closes' % (path, opens, closes), file=sys.stderr)
		bad = True
sys.exit(1 if bad else 0)
" $(find "$module_dir/src/main/webapp/www/common/stylesheets/factory" -name '*.css') \
	|| { echo "unbalanced CSS comment marker(s) under stylesheets/factory: fix before continuing" >&2; exit 1; }

"$repo_dir/tools/agent_jar.sh"
"$repo_dir/tools/mapping_jar.sh"
# X3-08 (1.4.1): the three pinned Jackson jars go into the module's WEB-INF/lib, which build.sh and the tests below put BEFORE the server lib/ (a FastTrack lib/ holds
# Jackson 2.14.2): the suite runs on the Jackson the package ships. core.JacksonLoadedTest prints which jar was really loaded and fails on any other version.
"$repo_dir/tools/jackson_jars.sh"
jackson_version="$(awk '$2 ~ /^jackson-core-.*\.jar$/ { v = $2; sub(/^jackson-core-/, "", v); sub(/\.jar$/, "", v); print v }' "$repo_dir/tools/embedded-jars.sha256")"
[[ -n "$jackson_version" ]] || { echo "MDMF-TEST-3: tools/embedded-jars.sha256 pins no jackson-core jar" >&2; exit 3; }
module_lib="$module_dir/src/main/webapp/WEB-INF/lib"
EBX_LIB_DIR="$server_lib" "$module_dir/build.sh" "$test_output/classes"
EBX_LIB_DIR="$server_lib" "$module_dir/build.sh" "$stale_out" >/dev/null
[[ ! -e "$stale_out/gone/Removed.class" ]] || { echo "build.sh left a stale class in its output folder" >&2; exit 1; }
rm -rf "$stale_out"

java_home="${JAVA_HOME:-}"
if [[ -z "$java_home" && -x /usr/libexec/java_home ]]; then
	java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
javac_bin="${java_home:+$java_home/bin/}javac"
java_bin="${java_home:+$java_home/bin/}java"

tests=()
while IFS= read -r file; do
	tests+=("$file")
done < <(find "$module_dir/src/test/java" -name '*Test.java' -type f -print)
mkdir -p "$test_output/test-classes"
"$javac_bin" -encoding UTF-8 -nowarn -Xlint:none -cp "$test_output/classes:$module_lib/*:$server_lib/*" -d "$test_output/test-classes" "${tests[@]}"
for file in "${tests[@]}"; do
	class="${file#"$module_dir/src/test/java/"}"; class="${class%.java}"; class="${class//\//.}"
	"$java_bin" -ea -Dmdmf.sourceDir="$module_dir/src/main/java" -Dmdmf.jsDump="$test_output/js" -Dmdmf.expectedJackson="$jackson_version" -cp "$test_output/test-classes:$test_output/classes:$module_lib/*:$server_lib/*" "$class"
done
# wave 10: the end-of-task notice (jobnotice.js) run against a stand-in DOM, and every script the Java side writes (wizard, review) parsed - with
# JavaScriptCore through osascript, so on a Mac only (a machine without it skips this step and says so)
if command -v osascript >/dev/null 2>&1; then
	osascript -l JavaScript "$repo_dir/tools/jobnotice_test.js" "$module_dir/src/main/resources/com/on/ebx/factory/ebx/ui/jobnotice.js" \
		"$module_dir/src/main/webapp/www/common/jscripts/factory/wizard.js" "$test_output/js" \
		|| { echo "tools/jobnotice_test.js failed" >&2; exit 1; }
	# wave 13 (security): browser storage keyed by the EBX user (userstore.js)
	osascript -l JavaScript "$repo_dir/tools/userstore_test.js" "$module_dir/src/main/resources/com/on/ebx/factory/ebx/ui/userstore.js" "$test_output/js" \
		|| { echo "tools/userstore_test.js failed" >&2; exit 1; }
	# codes transcoding lot H3: the code-table block of the Mapping matrix inspector (what it prints, escaped, and what Accept / Save post)
	osascript -l JavaScript "$repo_dir/tools/matrix_codes_test.js" "$test_output/js" \
		|| { echo "tools/matrix_codes_test.js failed" >&2; exit 1; }
else
	echo "osascript not found: tools/jobnotice_test.js and tools/userstore_test.js skipped" >&2
fi
# F3-100 (1.4.2): the Connectors jars taken from an unpacked package folder (ON_EBX_CONNECTORS_JARS_DIR), still against the pin - on a scratch copy of tools/
bash "$repo_dir/tools/jar_source_selftest.sh" >"$test_output/jar-source-selftest.log" 2>&1 \
	|| { cat "$test_output/jar-source-selftest.log" >&2; echo "tools/jar_source_selftest.sh failed" >&2; exit 1; }
# X3-07: the war self-sufficiency gate's own logic (tools/check_war.py, run by tools/package.sh on the war it just built)
PYTHONDONTWRITEBYTECODE=1 python3 "$repo_dir/tools/check_war_selftest.py" "$server_lib" >"$test_output/check-war-selftest.log" 2>&1 \
	|| { cat "$test_output/check-war-selftest.log" >&2; echo "tools/check_war_selftest.py failed" >&2; exit 1; }
# X3-08: the advisories check's own logic, against a recorded OSV answer (no network)
PYTHONDONTWRITEBYTECODE=1 python3 -I "$repo_dir/tools/deps_check_selftest.py" >"$test_output/deps-check-selftest.log" 2>&1 \
	|| { cat "$test_output/deps-check-selftest.log" >&2; echo "tools/deps_check_selftest.py failed" >&2; exit 1; }
# X3-08: tools/install.sh against a fake workspace (symbolic links to the server lib/): WEB-INF/lib = exactly the five pinned jars, no 2.14.2, classes built on 2.18.12
EBX_LIB_DIR="$server_lib" bash "$repo_dir/tools/install_selftest.sh" >"$test_output/install-selftest.log" 2>&1 \
	|| { cat "$test_output/install-selftest.log" >&2; echo "tools/install_selftest.sh failed" >&2; exit 1; }
# audit F-05: the integration suite's own logic, against its stand-in server on 127.0.0.1 (no EBX instance involved)
PYTHONDONTWRITEBYTECODE=1 python3 "$repo_dir/tools/itest/selftest.py" >"$test_output/itest-selftest.log" 2>&1 \
	|| { cat "$test_output/itest-selftest.log" >&2; echo "tools/itest/selftest.py failed" >&2; exit 1; }
# lot H2: the B2C end-to-end scenario's own logic (assertions on canned data, the generated fixtures, the whole scenario against a stand-in factory on 127.0.0.1)
PYTHONDONTWRITEBYTECODE=1 python3 "$repo_dir/tools/e2e/b2c_selftest.py" >"$test_output/e2e-selftest.log" 2>&1 \
	|| { cat "$test_output/e2e-selftest.log" >&2; echo "tools/e2e/b2c_selftest.py failed" >&2; exit 1; }
# lot A (2026-10-17): the HARD-CASES scenario (tools/e2e/b2c_hard.sh) is tested by the very same file - the 4th source, the code tables, the preflight, build anyway,
# the rejects and their causes, the cleanup's left-behind reading - against the stand-in factory in hard mode; its shell wrapper and its dry run are checked here too
PYTHONDONTWRITEBYTECODE=1 "$repo_dir/tools/e2e/b2c_hard.sh" --dry-run >"$test_output/e2e-hard-dry-run.log" 2>&1 \
	|| { cat "$test_output/e2e-hard-dry-run.log" >&2; echo "tools/e2e/b2c_hard.sh --dry-run failed" >&2; exit 1; }
# lot E2E-master (2026-10-17): the master and matching scenario (tools/e2e/b2c_master.sh), its ground truth and its quality measures are tested by the same file (b2c_selftest.py runs
# b2c_master_selftest.py: both variants against the stand-in factory in master mode, every defect); its shell wrapper and its dry run are checked here too
PYTHONDONTWRITEBYTECODE=1 "$repo_dir/tools/e2e/b2c_master.sh" --dry-run >"$test_output/e2e-master-dry-run.log" 2>&1 \
	|| { cat "$test_output/e2e-master-dry-run.log" >&2; echo "tools/e2e/b2c_master.sh --dry-run failed" >&2; exit 1; }
# B2B end-to-end scenario (tools/e2e/b2b_scenario.sh): the generator's invariants (counts, envelopes, Luhn, VAT keys, overlaps), the assertion logic on canned data and the whole scenario
# against the stand-in factory in B2B mode (b2b_selftest.py, b2b_standin.py, every defect); its shell wrapper and its dry run are checked here too
PYTHONDONTWRITEBYTECODE=1 python3 "$repo_dir/tools/e2e/b2b_selftest.py" >"$test_output/e2e-b2b-selftest.log" 2>&1 \
	|| { cat "$test_output/e2e-b2b-selftest.log" >&2; echo "tools/e2e/b2b_selftest.py failed" >&2; exit 1; }
PYTHONDONTWRITEBYTECODE=1 "$repo_dir/tools/e2e/b2b_scenario.sh" --dry-run >"$test_output/e2e-b2b-dry-run.log" 2>&1 \
	|| { cat "$test_output/e2e-b2b-dry-run.log" >&2; echo "tools/e2e/b2b_scenario.sh --dry-run failed" >&2; exit 1; }
echo "all green"
