# Sourced by agent_jar.sh and mapping_jar.sh (audit F-03): the two embedded jars are pinned by SHA-256 in
# tools/embedded-jars.sha256 (sha256sum format: "<hash>  <jar name>"). A jar whose hash differs from the recorded one
# (or that has no recorded hash) is refused unless ACCEPT_NEW_JARS=1, which records the new hash: the file is then
# part of the commit that moves the module to a new Connectors build, so "which jar shipped" is always in git.
# No shebang and no set -e of its own: it runs inside the caller's shell.
#
# Jar store (2026-10-14): every jar that passes the pin is also kept in $jar_store as <sha256>-<name>. The default source,
# ~/Dev/ebx-connectors/.../dist, holds whatever was last built in that shared checkout, on whatever branch it was on (it
# drifted to an older build, and a fresh worktree has no WEB-INF/lib copy): embed_jar then takes the PINNED build from the
# store instead of refusing. The hash check is the same; ACCEPT_NEW_JARS=1 still means "take this new jar".

pin_file="${pin_file:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/embedded-jars.sha256}"
jar_store="${ON_EBX_JAR_STORE:-$HOME/.local/share/on-ebx-jars}"

# accepts_new <jar name>: ACCEPT_NEW_JARS=1 accepts a new build of every jar of the run; ACCEPT_NEW_JARS=<jar name> only that one
# (install.sh and test_local.sh embed both jars: a bare 1 also takes whatever the other source holds).
accepts_new() {
	[[ "${ACCEPT_NEW_JARS:-}" == "1" || "${ACCEPT_NEW_JARS:-}" == "$1" ]]
}

pinned_sha256() {
	if [[ -f "$pin_file" ]]; then awk -v n="$1" '$2 == n { print $1 }' "$pin_file"; fi
}

# store_jar <jar name> <path>: keeps an accepted jar in the store (no-op when already there)
store_jar() {
	local sha
	sha="$(jar_sha256 "$2")"
	mkdir -p "$jar_store"
	if [[ ! -f "$jar_store/$sha-$1" ]]; then
		cp "$2" "$jar_store/$sha-$1.tmp" && mv "$jar_store/$sha-$1.tmp" "$jar_store/$sha-$1"
	fi
}

jar_sha256() {
	if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1; else sha256sum "$1" | cut -d' ' -f1; fi
}

# pin_jar <jar name as recorded> <path of the jar to check>
pin_jar() {
	local name="$1" path="$2" actual recorded
	actual="$(jar_sha256 "$path")"
	recorded=""
	if [[ -f "$pin_file" ]]; then
		recorded="$(awk -v n="$name" '$2 == n { print $1 }' "$pin_file")"
	fi
	if [[ "$recorded" == "$actual" ]]; then
		store_jar "$name" "$path"
		return 0
	fi
	if ! accepts_new "$name"; then
		{
			echo "$name: SHA-256 $actual is not the pinned one (${recorded:-none recorded}) in $pin_file."
			echo "If this jar is the intended new build (a new Connectors build, or a deliberate move to another Jackson version), re-run with ACCEPT_NEW_JARS=$name and commit the updated pin file."
		} >&2
		return 3
	fi
	local tmp
	tmp="$(mktemp)"
	{
		if [[ -f "$pin_file" ]]; then awk -v n="$name" '$2 != n' "$pin_file"; fi
		printf '%s  %s\n' "$actual" "$name"
	} | LC_ALL=C sort -k2 > "$tmp"
	cat "$tmp" > "$pin_file"
	rm -f "$tmp"
	store_jar "$name" "$path"
	echo "$name: pin updated to $actual in $pin_file" >&2
}

# embed_jar <jar name> <source path> <how to build it>: puts the pinned jar into <module>/WEB-INF/lib. The source is used when
# it is the pinned build (or when ACCEPT_NEW_JARS=1); otherwise the pinned build from the store; otherwise the copy already in
# WEB-INF/lib; otherwise the source is refused by pin_jar as before. Expects $lib (the WEB-INF/lib folder) set by the caller.
embed_jar() {
	local name="$1" source="$2" hint="$3" pinned stored
	pinned="$(pinned_sha256 "$name")"
	stored="$jar_store/$pinned-$name"
	mkdir -p "$lib"
	if [[ -f "$source" ]] && { accepts_new "$name" || [[ "$(jar_sha256 "$source")" == "$pinned" ]]; }; then
		pin_jar "$name" "$source" || return $?
		cp "$source" "$lib/$name"
	elif [[ -n "$pinned" && -f "$stored" ]]; then
		[[ -f "$source" ]] && echo "$name: $source is not the pinned build; using the pinned one from $jar_store" >&2
		pin_jar "$name" "$stored" || return $?
		cp "$stored" "$lib/$name"
	elif [[ -f "$lib/$name" && ! -f "$source" ]]; then
		# nothing to copy: the jar already in WEB-INF/lib is the one that will be compiled against and shipped, so it is the one pinned
		pin_jar "$name" "$lib/$name" || return $?
	elif [[ -f "$source" ]]; then
		pin_jar "$name" "$source" || return $?
		cp "$source" "$lib/$name"
	else
		echo "$name not found: $hint" >&2
		return 2
	fi
}
