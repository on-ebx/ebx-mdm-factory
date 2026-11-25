#!/usr/bin/env bash
# 1.4.2 (F3-100): tools/agent_jar.sh and tools/mapping_jar.sh take their jars from an unpacked Connectors package folder (ON_EBX_CONNECTORS_JARS_DIR), still against the pinned
# SHA-256. Run against a scratch copy of tools/ with a scratch pin file and jar store: nothing of the repository is touched.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/tools" "$work/ebx-mdm-factory/src/main/webapp/WEB-INF/lib" "$work/pkg-good" "$work/pkg-bad"
cp "$repo_dir/tools/agent_jar.sh" "$repo_dir/tools/mapping_jar.sh" "$repo_dir/tools/jar_pin.sh" "$work/tools/"
printf 'agent jar bytes' > "$work/pkg-good/on-ebx-agent.jar"
printf 'mapping jar bytes' > "$work/pkg-good/on-ebx-mapping.jar"
printf 'another build' > "$work/pkg-bad/on-ebx-agent.jar"
printf 'another build' > "$work/pkg-bad/on-ebx-mapping.jar"
sha() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi; }
printf '%s  on-ebx-agent.jar\n%s  on-ebx-mapping.jar\n' "$(sha "$work/pkg-good/on-ebx-agent.jar")" "$(sha "$work/pkg-good/on-ebx-mapping.jar")" > "$work/tools/embedded-jars.sha256"
lib="$work/ebx-mdm-factory/src/main/webapp/WEB-INF/lib"
export ON_EBX_JAR_STORE="$work/store"
unset ON_EBX_AGENT_JAR ON_EBX_MAPPING_JAR ACCEPT_NEW_JARS || true

ON_EBX_CONNECTORS_JARS_DIR="$work/pkg-good" bash "$work/tools/agent_jar.sh"
ON_EBX_CONNECTORS_JARS_DIR="$work/pkg-good" bash "$work/tools/mapping_jar.sh"
cmp -s "$lib/on-ebx-agent.jar" "$work/pkg-good/on-ebx-agent.jar" || { echo "agent jar was not taken from ON_EBX_CONNECTORS_JARS_DIR" >&2; exit 1; }
cmp -s "$lib/on-ebx-mapping.jar" "$work/pkg-good/on-ebx-mapping.jar" || { echo "mapping jar was not taken from ON_EBX_CONNECTORS_JARS_DIR" >&2; exit 1; }

# a folder holding another build is refused against the pin (exit 3) when nothing pinned is stored: the folder is a source, never an authority
rm -f "$lib"/*.jar; rm -rf "$work/store"
set +e
bad="$(ON_EBX_CONNECTORS_JARS_DIR="$work/pkg-bad" bash "$work/tools/agent_jar.sh" 2>&1)"; code=$?
set -e
[[ "$code" == "3" && "$bad" == *"is not the pinned one"* ]] || { echo "a package folder with another build must be refused with exit 3 (exit $code): $bad" >&2; exit 1; }
[[ ! -f "$lib/on-ebx-agent.jar" ]] || { echo "a refused jar was copied" >&2; exit 1; }

# the single-jar variable still wins over the folder
rm -f "$lib"/*.jar
ON_EBX_AGENT_JAR="$work/pkg-good/on-ebx-agent.jar" ON_EBX_CONNECTORS_JARS_DIR="$work/pkg-bad" bash "$work/tools/agent_jar.sh"
cmp -s "$lib/on-ebx-agent.jar" "$work/pkg-good/on-ebx-agent.jar" || { echo "ON_EBX_AGENT_JAR must win over ON_EBX_CONNECTORS_JARS_DIR" >&2; exit 1; }
echo "jar_source_selftest OK"
