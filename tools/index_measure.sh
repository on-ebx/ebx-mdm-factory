#!/usr/bin/env bash
# Lot IDX (2026-10-12): memory and time of the OLD read indexes (HashMap<String, ...>) against the NEW compact ones (index.DigestMap / KeyValueIndex), on synthetic keys.
# No EBX needed: compiles index/*.java, RowDigest and the harness into a temporary folder, one JVM per line.
# Usage: tools/index_measure.sh [rows] [heap]      defaults: 1000000 rows, -Xmx4g
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
src="$repo_dir/ebx-mdm-factory/src"
rows="${1:-1000000}"
heap="${2:-4g}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
java_home="${JAVA_HOME:-}"
if [[ -z "$java_home" && -x /usr/libexec/java_home ]]; then
	java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
javac_bin="${java_home:+$java_home/bin/}javac"
java_bin="${java_home:+$java_home/bin/}java"
"$javac_bin" -encoding UTF-8 -nowarn -Xlint:none -d "$out" "$src"/main/java/com/on/ebx/factory/index/*.java "$src/main/java/com/on/ebx/factory/transfer/RowDigest.java" \
	"$src/test/java/com/on/ebx/factory/index/IndexMeasure.java"
echo "java $("$java_bin" -version 2>&1 | head -1), -Xmx$heap, $rows rows"
for kind in xref master merged staging; do
	for impl in old new; do
		"$java_bin" -Xmx"$heap" -cp "$out" com.on.ebx.factory.index.IndexMeasure "$kind" "$impl" "$rows"
	done
done
