#!/usr/bin/env bash
# Prints the module's build identity, derived from git (audit F-03): the one place that decides what "version" means.
#   tools/version.sh version   -> the nearest tag (a leading "v" removed) plus "-<commits since>-g<hash>" when HEAD is past it,
#                                 "0.0.0-<short hash>" while the repository has no tag at all; "-dirty" is appended when the
#                                 working tree has uncommitted changes (tracked files only: ignored build outputs do not count)
#   tools/version.sh commit    -> the full commit hash of HEAD, with "-dirty" appended on a modified tree
#   tools/version.sh describe  -> prints nothing but the nearest tag, or fails when there is none (package.sh uses it for the release notes)
# Outside a git repository (a copy rsync'ed into a workspace) it prints "0.0.0-unknown" / "unknown": callers that know better
# pass FACTORY_VERSION / FACTORY_COMMIT instead (install.sh does, build.sh honours them).
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
what="${1:-version}"
if ! git -C "$repo_dir" rev-parse --git-dir >/dev/null 2>&1; then
	case "$what" in
	version) echo "0.0.0-unknown" ;;
	commit) echo "unknown" ;;
	*) exit 1 ;;
	esac
	exit 0
fi
dirty=""
if [[ -n "$(git -C "$repo_dir" status --porcelain --untracked-files=no 2>/dev/null)" ]]; then
	dirty="-dirty"
fi
# audit F2-114: a new, not yet tracked source file is a modification of what gets deployed too
if [[ -z "$dirty" && -n "$(git -C "$repo_dir" ls-files --others --exclude-standard -- ebx-mdm-factory/src 2>/dev/null)" ]]; then
	dirty="-dirty"
fi
case "$what" in
version)
	if tag="$(git -C "$repo_dir" describe --tags --abbrev=8 2>/dev/null)"; then
		echo "${tag#v}$dirty"
	else
		echo "0.0.0-$(git -C "$repo_dir" rev-parse --short=8 HEAD)$dirty"
	fi
	;;
commit) echo "$(git -C "$repo_dir" rev-parse HEAD)$dirty" ;;
describe) git -C "$repo_dir" describe --tags --abbrev=0 2>/dev/null ;;
*) echo "usage: version.sh version|commit|describe" >&2; exit 2 ;;
esac
