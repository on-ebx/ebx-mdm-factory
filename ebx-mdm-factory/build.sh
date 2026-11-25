#!/usr/bin/env bash
# Compiles the module into target/classes with the jars of ebx-ps-fasttrack-server/lib and of its own WEB-INF/lib
# (on-ebx-agent.jar, built by ebx-connectors/build-agent.sh; tools/agent_jar.sh fetches it). No Maven.
# Usage: ./build.sh            -> compiles into target/classes (Tomcat must be stopped, see README)
#        ./build.sh /tmp/out   -> compiles elsewhere (safe while Tomcat runs)
# X3-08 (1.4.1): the module's own WEB-INF/lib comes FIRST on the class path, so the pinned Jackson 2.18.12 there (tools/jackson_jars.sh, tools/install.sh) is the
# one compiled against and not the 2.14.2 of a FastTrack server lib/; a Jackson jar of WEB-INF/lib is also listed in version.properties (embeddedJar.<name>=<sha256>).
# Also writes com/on/ebx/factory/version.properties (audit F-03): version, commit, build time and the SHA-256 of the two
# embedded jars. FACTORY_VERSION / FACTORY_COMMIT win when set (tools/install.sh passes them: the rsync'ed copy has no
# .git); otherwise tools/version.sh of the repository this module sits in decides; otherwise "unknown".
set -euo pipefail

module_dir="$(cd "$(dirname "$0")" && pwd)"
fasttrack_dir="$(cd "$module_dir/.." && pwd)"
# Audit F2-114: no workstation path is built in; the default is the workspace this copy sits in (the deployed one), else EBX_WORKSPACE, else EBX_LIB_DIR.
if [[ -n "${EBX_LIB_DIR:-}" ]]; then
	server_lib="$EBX_LIB_DIR"
elif [[ -n "${EBX_WORKSPACE:-}" ]]; then
	server_lib="$EBX_WORKSPACE/ebx-ps-fasttrack-server/lib"
else
	server_lib="$fasttrack_dir/ebx-ps-fasttrack-server/lib"
fi
out="${1:-$module_dir/target/classes}"

# X3-07: ebx-lib.jar (FastTrack workspace) and ebx.jar (standard distribution) are the same jar
if [[ ! -f "$server_lib/ebx-lib.jar" && ! -f "$server_lib/ebx.jar" ]]; then
	printf 'MDMF-BUILD-1: missing EBX runtime libraries under %s: neither ebx-lib.jar (FastTrack name) nor ebx.jar (standard distribution name) is there (set EBX_WORKSPACE to the workspace project folder, or EBX_LIB_DIR)\n' "$server_lib" >&2
	exit 2
fi

java_home="${JAVA_HOME:-}"
if [[ -z "$java_home" && -x /usr/libexec/java_home ]]; then
	java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
javac_bin="${java_home:+$java_home/bin/}javac"

sources=()
while IFS= read -r file; do
	sources+=("$file")
done < <(find "$module_dir/src/main/java" -name '*.java' -type f -print)

mkdir -p "$out"
# Audit F2-114: an output folder is rebuilt from scratch - a class deleted or renamed by a lot must not stay loadable (install.sh's rsync excludes target/)
case "$out" in
	""|"/"|"$HOME"|"$HOME/") echo "MDMF-BUILD-2: refusing to empty '$out'" >&2; exit 2 ;;
esac
find "$out" -mindepth 1 -delete
"$javac_bin" --release 8 -encoding UTF-8 -nowarn -Xlint:none -cp "$module_dir/src/main/webapp/WEB-INF/lib/*:$server_lib/*" -d "$out" "${sources[@]}"
if [[ -d "$module_dir/src/main/resources" ]]; then
	cp -R "$module_dir/src/main/resources/." "$out/"
fi
# wave 11 (audit F-42): wizard.js is a static file of the web application (www/common/jscripts/factory, cached by the browser under a
# ?v=<build> address). The same bytes are copied next to the classes so that the inline form of the script (ProjectWizardUserService.script,
# the -Debx.factory.wizard.inlineScripts=true escape hatch and the offline tests) reads the one source: it is not a second copy to edit.
if [[ -f "$module_dir/src/main/webapp/www/common/jscripts/factory/wizard.js" ]]; then
	mkdir -p "$out/com/on/ebx/factory/ebx/ui"
	cp "$module_dir/src/main/webapp/www/common/jscripts/factory/wizard.js" "$out/com/on/ebx/factory/ebx/ui/wizard.js"
fi
# fix/review-assets (2026-09-21): a cache-busting value for every factory.css/review.css/wizard.css <link>
# tag (core.Assets, read once at class load) - written LAST, after the resources copy, so it always
# carries THIS run's own timestamp rather than being overwritten by it. Not under src/main/resources on
# purpose: Eclipse's own incremental builder does not manage this file (see core/Assets.java's own
# Javadoc for what that means for an Eclipse-only F5/Clean rebuild that never re-runs build.sh).
mkdir -p "$out/com/on/ebx/factory"
printf 'build=%s\n' "$(date +%Y%m%d%H%M%S)" > "$out/com/on/ebx/factory/build.properties"
printf '%s classes -> %s\n' "$(find "$out" -name '*.class' | wc -l | tr -d ' ')" "$out"

# audit F-03: what this build is. Not under src/main/resources for the same reason as build.properties above (it is
# derived from git and from the jars at build time and must never be committed).
sha256_of() {
	if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi
}
version="${FACTORY_VERSION:-}"
commit="${FACTORY_COMMIT:-}"
repo_tools="$module_dir/../tools/version.sh"
if [[ -z "$version" && -x "$repo_tools" ]]; then version="$("$repo_tools" version)"; fi
if [[ -z "$commit" && -x "$repo_tools" ]]; then commit="$("$repo_tools" commit)"; fi
agent_sha=""
mapping_sha=""
[[ -f "$module_dir/src/main/webapp/WEB-INF/lib/on-ebx-agent.jar" ]] && agent_sha="$(sha256_of "$module_dir/src/main/webapp/WEB-INF/lib/on-ebx-agent.jar")"
[[ -f "$module_dir/src/main/webapp/WEB-INF/lib/on-ebx-mapping.jar" ]] && mapping_sha="$(sha256_of "$module_dir/src/main/webapp/WEB-INF/lib/on-ebx-mapping.jar")"
printf 'version=%s\ncommit=%s\nbuildTime=%s\nagentJarSha256=%s\nmappingJarSha256=%s\n' \
	"${version:-unknown}" "${commit:-unknown}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$agent_sha" "$mapping_sha" > "$out/com/on/ebx/factory/version.properties"
for jar in "$module_dir"/src/main/webapp/WEB-INF/lib/jackson-*.jar; do
	if [[ -f "$jar" ]]; then printf 'embeddedJar.%s=%s\n' "$(basename "$jar")" "$(sha256_of "$jar")" >> "$out/com/on/ebx/factory/version.properties"; fi
done
