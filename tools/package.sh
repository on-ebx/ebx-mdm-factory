#!/usr/bin/env bash
# Builds the release package of the module (audit F-03), from the committed tree:
#   dist/ebx-mdm-factory-<version>.zip
#     ebx-mdm-factory-<version>.war   WEB-INF/classes + the content of src/main/webapp (WEB-INF/lib with the two pinned Connectors jars and the three pinned Jackson
#                                     jars, 2.18.12 since 1.4.1 (X3-08): a standard ON EBX server lib/ has no Jackson). tools/install.sh (FastTrack) installs the same five jars.
#     THIRD-PARTY-NOTICES.md, licenses/   the notice and the licence texts of the jars that are not ours (Jackson, Apache License 2.0, and the two small MIT parts jackson-core bundles)
#     dependency-check.txt            the output of tools/deps_check.py on the day of the build (OSV advisories of the embedded jars; or the named waiver if it was waived)
#     OPERATIONS.md, UPGRADE.md, AGENTS.md, DRIFT.md   the operator guide, the upgrade / rollback guide, the guides of the two functions shipped closed
#     RELEASE-NOTES.md                version, commit, jar hashes, docs/RELEASE-NOTES.md, then the git log since the previous tag (or RELEASE_SINCE)
#     embedded-jars.sha256            the pins the war was built with
#     workflow/*.json                 the workflow models an operator imports by hand (MCP workflow_model_import, then publish)
#     SHA256SUMS                      sha256sum format, every file above
# Steps: the advisories check (X3-08: tools/deps_check.py asks OSV about every embedded jar that has Maven coordinates and refuses an advisory that tools/deps-known.txt does not
# accept; with no network it refuses too, unless DEPS_OFFLINE_WAIVER="<reason>" names a waiver, which is then written in the package), the whole local regression (tools/test_local.sh, same environment), then the compile, then the packaging, then the self-sufficiency gate on the war
# just produced (tools/check_war.py: every class the war references is in the war, the JDK, Tomcat or a standard ebx.jar; X3-07).
# Environment:
#   EBX_LIB_DIR        REQUIRED: a server lib/ folder of an EBX 6.3 server holding ebx-lib.jar (FastTrack name) or ebx.jar (standard name; same jar) and ebx-addons.jar
#                      (compile-time libraries; its own Jackson, if any, is NOT used since 1.4.1: the pinned 2.18.12 jars come first on every class path).
#   JACKSON_JAR_DIR    the folder that holds the three pinned Jackson jars by their Maven names (jackson-core-2.18.12.jar...; Maven Central, com.fasterxml.jackson.core); default:
#                      <jar store>/maven, i.e. ~/.local/share/on-ebx-jars/maven. Their SHA-256 must match tools/embedded-jars.sha256 (ACCEPT_NEW_JARS=<jar name> re-pins one).
#   DEPS_OFFLINE_WAIVER  a reason, to build while api.osv.dev cannot be reached; the package then says in dependency-check.txt and in its release notes that the advisories were NOT checked
#   EBX_STANDARD_JAR   REQUIRED (1.4.2): the ebx.jar of a standard distribution, for the self-sufficiency gate (see tools/check_war.py); without it, MDMF-PACKAGE-6
#   TOMCAT_LIB_DIR     REQUIRED (1.4.2): Tomcat's lib/ folder for the same gate; without it, MDMF-PACKAGE-6 (the gate used to degrade to a list of package prefixes and write the package anyway)
#   ON_EBX_AGENT_JAR, ON_EBX_MAPPING_JAR   the two Connectors jars when they are not in ~/Dev/ebx-connectors/ebx-connectors/dist (tools/*_jar.sh;
#                      their SHA-256 must match tools/embedded-jars.sha256, ACCEPT_NEW_JARS=1 re-pins)
#   RELEASE_SINCE      a tag or commit: the release notes start after it (default: the nearest tag; without any tag, the last 100 commits)
#   ALLOW_DIRTY=1      package a tree with uncommitted changes (the version then ends with -dirty); refused by default
# The classes are compiled with --release 8 by ebx-mdm-factory/build.sh: the same level tools/install.sh has always produced and the
# regression suite runs on, and javac then also checks that no API newer than Java 8 is used; the EBX 6.3 JVM (21 and up) runs them.
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
module_dir="$repo_dir/ebx-mdm-factory"
# X3-07: the ON EBX core jar is ebx-lib.jar in a FastTrack workspace and ebx.jar in the standard distribution (the same bytes, measured 2026-11-12)
core_jar=""
for candidate in ebx-lib.jar ebx.jar; do
	if [[ -n "${EBX_LIB_DIR:-}" && -f "$EBX_LIB_DIR/$candidate" && -z "$core_jar" ]]; then core_jar="$EBX_LIB_DIR/$candidate"; fi
done
if [[ -z "$core_jar" ]]; then
	echo "MDMF-PACKAGE-1: set EBX_LIB_DIR to a server lib folder that holds ebx-lib.jar (FastTrack workspace: ebx-ps-fasttrack-server/lib) or ebx.jar (standard distribution), together with the jackson-*.jar and ebx-addons.jar the module is compiled against" >&2
	exit 2
fi
# 1.4.2 (gap 12): a package is only written with a REAL self-sufficiency gate. Without Tomcat's lib/ and a standard ebx.jar, tools/check_war.py degrades to a list of package
# prefixes ("trusts what Tomcat provides") and the package would say it is self-sufficient on a weaker proof: refused here, before the suite runs. The ordinary suite
# (tools/test_local.sh) needs neither.
missing_gate=""
if [[ -z "${TOMCAT_LIB_DIR:-}" || ! -d "${TOMCAT_LIB_DIR:-/nonexistent}" ]]; then missing_gate="$missing_gate TOMCAT_LIB_DIR (Tomcat's lib/ folder, for example <tomcat>/lib of the server the war will run on)"; fi
if [[ -z "${EBX_STANDARD_JAR:-}" || ! -f "${EBX_STANDARD_JAR:-/nonexistent}" ]]; then missing_gate="$missing_gate EBX_STANDARD_JAR (the ebx.jar of a STANDARD ON EBX distribution, ebx.software/lib/ebx.jar)"; fi
if [[ -n "$missing_gate" ]]; then
	echo "MDMF-PACKAGE-6: a package needs the real self-sufficiency gate; set, to an existing path:$missing_gate. No package written (tools/test_local.sh does not need them)." >&2
	exit 2
fi
version="$("$repo_dir/tools/version.sh" version)"
commit="$("$repo_dir/tools/version.sh" commit)"
if [[ "$version" == *-dirty && "${ALLOW_DIRTY:-}" != "1" ]]; then
	echo "the working tree has uncommitted changes (version $version): commit them, or set ALLOW_DIRTY=1 for a trial package" >&2
	exit 1
fi
export FACTORY_VERSION="$version" FACTORY_COMMIT="$commit"

sha256_of() {
	if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi
}

echo "== dependency advisories (X3-08)"
deps_log="$(mktemp)"
trap 'rm -f "$deps_log"' EXIT
set +e
python3 -I "$repo_dir/tools/deps_check.py" ${DEPS_OFFLINE_WAIVER:+--waiver "$DEPS_OFFLINE_WAIVER"} > "$deps_log" 2>&1
deps_code=$?
set -e
cat "$deps_log"
if [[ $deps_code -ne 0 ]]; then
	echo "MDMF-PACKAGE-4: the dependency advisories check refused (exit $deps_code, see above): no package written. Fix the jars, accept an advisory with a date and a reason in tools/deps-known.txt, or (no network only) name DEPS_OFFLINE_WAIVER=\"<reason>\"" >&2
	exit 1
fi

echo "== regression suite"
"$repo_dir/tools/test_local.sh"

echo "== embedded jars"
"$repo_dir/tools/agent_jar.sh"
"$repo_dir/tools/mapping_jar.sh"
# shellcheck source=jar_pin.sh
source "$repo_dir/tools/jar_pin.sh"
# shellcheck source=jackson_jars.sh
source "$repo_dir/tools/jackson_jars.sh"
# X3-08: the module's own WEB-INF/lib holds the pinned Jackson, which build.sh puts first on its class path (test_local.sh already did the same for the suite)
"$repo_dir/tools/jackson_jars.sh"

dist="$repo_dir/dist"
stage="$dist/stage-$version"
rm -rf "$stage"
mkdir -p "$stage/war" "$stage/zip"

echo "== compile and assemble the war"
mkdir -p "$stage/war/WEB-INF/classes"
EBX_LIB_DIR="$EBX_LIB_DIR" "$module_dir/build.sh" "$stage/war/WEB-INF/classes"
# the webapp content (web.xml, module.xml, the XSD, the www assets, WEB-INF/lib): everything under src/main/webapp except OS litter
rsync -a --exclude '.DS_Store' --exclude 'classes/' "$module_dir/src/main/webapp/" "$stage/war/"
# X3-07 / X3-08: the war carries the pinned Jackson (embed_jackson also removes any other build of the three artifacts); the same five jars tools/install.sh installs
embed_jackson "$stage/war/WEB-INF/lib"
war_jars=(on-ebx-agent.jar on-ebx-mapping.jar "${jackson_names[@]}")
for jar in "${war_jars[@]}"; do
	[[ -f "$stage/war/WEB-INF/lib/$jar" ]] || { echo "$jar is missing from the war's WEB-INF/lib" >&2; exit 2; }
done
[[ "$(cd "$stage/war/WEB-INF/lib" && ls | LC_ALL=C sort | tr '\n' ' ')" == "$(printf '%s\n' "${war_jars[@]}" | LC_ALL=C sort | tr '\n' ' ')" ]] \
	|| { echo "MDMF-PACKAGE-5: the war's WEB-INF/lib is not exactly the five pinned jars: $(ls "$stage/war/WEB-INF/lib")" >&2; exit 2; }
# /status (embeddedJars) names them: build.sh wrote their hashes into version.properties, which sits in the war's classes (checked here, not appended a second time)
for jar in "${jackson_names[@]}"; do
	grep -q "^embeddedJar.$jar=$(sha256_of "$stage/war/WEB-INF/lib/$jar")\$" "$stage/war/WEB-INF/classes/com/on/ebx/factory/version.properties" \
		|| { echo "MDMF-PACKAGE-5: version.properties does not carry the SHA-256 of $jar" >&2; exit 2; }
done
war_name="ebx-mdm-factory-$version.war"
(cd "$stage/war" && zip -X -r -q "$stage/zip/$war_name" . -x '.DS_Store')

echo "== self-sufficiency gate (X3-07)"
export EBX_STANDARD_JAR TOMCAT_LIB_DIR # both are required above (MDMF-PACKAGE-6)
python3 "$repo_dir/tools/check_war.py" "$stage/zip/$war_name" || { echo "MDMF-PACKAGE-3: the war just built is not self-sufficient (see above): no package written" >&2; exit 1; }

echo "== third-party notices"
mkdir -p "$stage/zip/licenses"
core_war_jar="$stage/war/WEB-INF/lib/jackson-core-$jackson_version.jar"
unzip -p "$core_war_jar" META-INF/LICENSE > "$stage/zip/licenses/jackson-LICENSE.txt"
unzip -p "$core_war_jar" META-INF/NOTICE > "$stage/zip/licenses/jackson-core-NOTICE.txt"
unzip -p "$stage/war/WEB-INF/lib/jackson-databind-$jackson_version.jar" META-INF/NOTICE > "$stage/zip/licenses/jackson-databind-NOTICE.txt"
unzip -p "$stage/war/WEB-INF/lib/jackson-annotations-$jackson_version.jar" META-INF/NOTICE > "$stage/zip/licenses/jackson-annotations-NOTICE.txt"
# jackson-core 2.18 bundles two small third-party parts (named in its NOTICE): their licence texts travel with it
unzip -p "$core_war_jar" META-INF/FastDoubleParser-LICENSE > "$stage/zip/licenses/jackson-core-FastDoubleParser-LICENSE.txt"
unzip -p "$core_war_jar" META-INF/FastDoubleParser-ThirdParty-LICENSE > "$stage/zip/licenses/jackson-core-FastDoubleParser-ThirdParty-LICENSE.txt"
unzip -p "$core_war_jar" META-INF/Schubfach-LICENSE > "$stage/zip/licenses/jackson-core-Schubfach-LICENSE.txt"
cp "$deps_log" "$stage/zip/dependency-check.txt"
{
	echo "# Third-party software in $war_name"
	echo
	echo "The war embeds, in WEB-INF/lib, the following libraries that are not ON EBX code. A standard ON EBX server provides none of the Jackson jars."
	echo
	echo "| File | Version | SHA-256 | Licence |"
	echo "|---|---|---|---|"
	for jar in "${jackson_names[@]}"; do
		echo "| $jar | $jackson_version | $(sha256_of "$stage/war/WEB-INF/lib/$jar") | Apache License 2.0 (FasterXML, LLC) |"
	done
	echo
	echo "The licence text is licenses/jackson-LICENSE.txt and the notices are licenses/jackson-*-NOTICE.txt (copied from the jars' META-INF). jackson-core $jackson_version also bundles"
	echo "a shaded copy of FastDoubleParser (MIT licence, Werner Randelshofer; it includes code under the Boost Software License, Daniel Lemire) and the Schubfach number writing code"
	echo "(MIT licence, Raffaello Giulietti): their texts are licenses/jackson-core-FastDoubleParser-LICENSE.txt, licenses/jackson-core-FastDoubleParser-ThirdParty-LICENSE.txt and"
	echo "licenses/jackson-core-Schubfach-LICENSE.txt. on-ebx-agent.jar and on-ebx-mapping.jar are ON EBX code (see the release notes for their hashes)."
} > "$stage/zip/THIRD-PARTY-NOTICES.md"

echo "== documentation, workflow models, release notes"
cp "$repo_dir/docs/OPERATIONS.md" "$stage/zip/OPERATIONS.md"
cp "$repo_dir/docs/UPGRADE.md" "$stage/zip/UPGRADE.md"
cp "$repo_dir/docs/AGENTS.md" "$stage/zip/AGENTS.md"
cp "$repo_dir/docs/DRIFT.md" "$stage/zip/DRIFT.md"
cp "$repo_dir/tools/embedded-jars.sha256" "$stage/zip/embedded-jars.sha256"
mkdir -p "$stage/zip/workflow"
cp "$repo_dir"/tools/workflow/*.json "$stage/zip/workflow/"

since="${RELEASE_SINCE:-}"
if [[ -z "$since" ]]; then since="$("$repo_dir/tools/version.sh" describe || true)"; fi
{
	echo "# MDM Factory $version"
	echo
	echo "- Commit: $commit"
	echo "- Built: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
	echo "- on-ebx-agent.jar SHA-256: $(sha256_of "$stage/war/WEB-INF/lib/on-ebx-agent.jar")"
	echo "- on-ebx-mapping.jar SHA-256: $(sha256_of "$stage/war/WEB-INF/lib/on-ebx-mapping.jar")"
	for jar in "${jackson_names[@]}"; do
		echo "- $jar SHA-256: $(sha256_of "$stage/war/WEB-INF/lib/$jar") (Apache License 2.0, see THIRD-PARTY-NOTICES.md)"
	done
	if grep -q "DEPS-CHECK WAIVED" "$deps_log"; then
		echo "- **Dependency advisories NOT checked** (waiver named by the person who built this package): $(grep -m 1 'DEPS-CHECK WAIVED' "$deps_log")"
	else
		echo "- Dependency advisories checked against OSV on the day of the build: no unaccepted advisory (dependency-check.txt)"
	fi
	echo "- Compiled with --release 8 (see tools/package.sh)"
	echo
	# the hand-written notes of the release (docs/RELEASE-NOTES.md), headings lowered one level under the title above
	if [[ -f "$repo_dir/docs/RELEASE-NOTES.md" ]]; then
		sed 's/^#/##/' "$repo_dir/docs/RELEASE-NOTES.md"
		echo
	fi
	if [[ -n "$since" ]]; then
		echo "## Changes since $since"
		echo
		git -C "$repo_dir" log --format='- %h %s' "$since..HEAD"
	else
		echo "## Changes (no tag in the repository: the last 100 commits)"
		echo
		git -C "$repo_dir" log -n 100 --format='- %h %s'
	fi
	echo
	echo "Read UPGRADE.md before installing over an existing instance."
} > "$stage/zip/RELEASE-NOTES.md"

(cd "$stage/zip" && find . -type f ! -name SHA256SUMS ! -name '.DS_Store' | LC_ALL=C sort | sed 's|^\./||' | while IFS= read -r file; do
	printf '%s  %s\n' "$(sha256_of "$file")" "$file"
done > SHA256SUMS)

package="$dist/ebx-mdm-factory-$version.zip"
rm -f "$package"
(cd "$stage/zip" && zip -X -r -q "$package" .)
rm -rf "$stage"
echo "== $package"
unzip -l "$package"
