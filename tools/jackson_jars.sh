#!/usr/bin/env bash
# Jackson 2.18.12 (FasterXML, Apache License 2.0), the three jars the module is compiled against, tested with, installed with and packaged with (X3-07, then X3-08 / 1.4.1).
# Why embedded: a standard ON EBX 6.3 server lib/ holds ebx.jar, the database driver and jakarta.mail, and NO Jackson (ebx.jar carries its own copy relocated under
# com.onwbp.com.fasterxml.jackson), so the module dies at start with NoClassDefFoundError com/fasterxml/jackson/databind/JsonNode without these jars (measured 2026-11-12).
# Why 2.18.12 and not the 2.14.2 that a FastTrack server lib/ holds: 11 OSV advisories on 2.14.2, fixed from 2.18.11 (X3-08). A FastTrack server keeps its 2.14.2 in
# the common class loader; Tomcat loads WEB-INF/lib of the module first, so the module runs on its own copy, and since 1.4.1 tools/install.sh puts these jars there too.
# The pins live in tools/embedded-jars.sha256 under the versioned names, the Maven coordinates (for tools/deps_check.py) in tools/embedded-jars-maven.txt.
# Where the jars come from: JACKSON_JAR_DIR (default: <jar store>/maven, i.e. ~/.local/share/on-ebx-jars/maven), by their Maven file names (jackson-core-2.18.12.jar...);
# a pinned jar already in the jar store (jar_pin.sh) is taken from there when the source folder has none. $EBX_LIB_DIR is NOT a source any more: its jars are 2.14.2.
#
# Two ways to use it:
#   sourced (tools/package.sh):  embed_jackson <WEB-INF/lib folder to fill>   (needs jar_pin.sh sourced first)
#   executed (test_local.sh, install.sh):  fills the module's own src/main/webapp/WEB-INF/lib, which build.sh and the tests put FIRST on their class path
#   (the .gitignore keeps the jar files out of git).

jackson_version="2.18.12"
jackson_names=(jackson-core-$jackson_version.jar jackson-databind-$jackson_version.jar jackson-annotations-$jackson_version.jar)

# embed_jackson <WEB-INF/lib folder to fill>: puts the three pinned jars there and REMOVES any other build of the same three artifacts (an older pin left by an earlier run)
embed_jackson() {
	local dir="${JACKSON_JAR_DIR:-$jar_store/maven}" name artifact other
	lib="$1"
	mkdir -p "$lib"
	for name in "${jackson_names[@]}"; do
		embed_jar "$name" "$dir/$name" "MDMF-PACKAGE-2: set JACKSON_JAR_DIR to a folder that holds $name (Maven Central, com.fasterxml.jackson.core, Apache License 2.0; SHA-256 $(pinned_sha256 "$name")), or put the pinned build in the jar store $jar_store" \
			|| { echo "MDMF-PACKAGE-2: the Jackson jar $name was not embedded: JACKSON_JAR_DIR (default $jar_store/maven) must hold $name as pinned in $pin_file" >&2; return 3; }
	done
	for artifact in jackson-core jackson-databind jackson-annotations; do
		for other in "$lib/$artifact"-*.jar "$lib/$artifact.jar"; do
			[[ -e "$other" ]] || continue
			case " ${jackson_names[*]} " in
				*" $(basename "$other") "*) ;;
				*) echo "removing $(basename "$other") from $lib (not the pinned Jackson $jackson_version)" >&2; rm -f "$other" ;;
			esac
		done
	done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
	set -euo pipefail
	repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
	# shellcheck source=jar_pin.sh
	source "$repo_dir/tools/jar_pin.sh"
	embed_jackson "$repo_dir/ebx-mdm-factory/src/main/webapp/WEB-INF/lib"
fi
