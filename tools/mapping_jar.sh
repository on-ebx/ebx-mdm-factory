#!/usr/bin/env bash
# Puts on-ebx-mapping.jar (the Connectors mapping engine, MappingEngine and its closure, built by
# build-mapping.sh of the ebx-connectors repository) into the module's WEB-INF/lib. The jar is never
# committed (see .gitignore: src/main/webapp/WEB-INF/lib/*.jar).
# ON_EBX_CONNECTORS_JARS_DIR names an unpacked Connectors package folder (both jars; see docs/OPERATIONS.md, "Rebuilding the package on another machine"). ON_EBX_MAPPING_JAR names another jar than the one of ~/Dev/ebx-connectors. The jar must match tools/embedded-jars.sha256
# (audit F-03): ACCEPT_NEW_JARS=1 records a new hash instead of refusing. When the source is not the pinned build, the pinned
# one is taken from the jar store (see jar_pin.sh).
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=jar_pin.sh
source "$repo_dir/tools/jar_pin.sh"
# 1.4.2 (F3-100): the source can be an unpacked Connectors package folder: ON_EBX_CONNECTORS_JARS_DIR holds on-ebx-agent.jar and on-ebx-mapping.jar. Order: ON_EBX_MAPPING_JAR
# (one jar), then that folder, then the ebx-connectors checkout's dist. Whatever the source, the jar must still match the pin (tools/embedded-jars.sha256).
if [[ -n "${ON_EBX_MAPPING_JAR:-}" ]]; then
	jar="$ON_EBX_MAPPING_JAR"
elif [[ -n "${ON_EBX_CONNECTORS_JARS_DIR:-}" ]]; then
	jar="$ON_EBX_CONNECTORS_JARS_DIR/on-ebx-mapping.jar"
else
	jar="$HOME/Dev/ebx-connectors/ebx-connectors/dist/on-ebx-mapping.jar"
fi
lib="$repo_dir/ebx-mdm-factory/src/main/webapp/WEB-INF/lib"
embed_jar "on-ebx-mapping.jar" "$jar" "run build-mapping.sh in the ebx-connectors repository, or set ON_EBX_MAPPING_JAR, or ON_EBX_CONNECTORS_JARS_DIR to an unpacked Connectors package"
