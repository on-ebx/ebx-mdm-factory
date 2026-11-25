#!/usr/bin/env bash
# The B2C scenario's HARD CASES (lot A): a 4th source with other codes, code tables, a bad link forced after the acceptance, the preflight, the blocked build, "build anyway",
# the rejects and their causes, the fix and the rebuild, long values. See docs/E2E-B2C.md ("Hard cases") and tools/e2e/b2c_hard.py (header).
# Usage:   EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_hard.sh [--cleanup] [--through 1..7] [--report FILE] [--keep-files]
#          tools/e2e/b2c_hard.sh --cleanup-project CODE   # deletes a project an earlier run left in place (the default: it stays, for a visual pass)
#          tools/e2e/b2c_hard.sh --selftest               # the assertion logic and the wiring against a stand-in server (no instance)
#          tools/e2e/b2c_hard.sh --dry-run                # generates and checks the four files and prints the plan; sends nothing
# Credentials come only from the environment and are never printed. The scenario WRITES (project, Connectors connections, dataspaces, models): test instance only.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }
mode_offline=0
for arg in "$@"; do
	case "$arg" in
	--selftest | --dry-run | -h | --help) mode_offline=1 ;;
	esac
done
if [[ "$mode_offline" -eq 0 ]]; then
	for var in EBX_URL EBX_USER EBX_PASSWORD; do
		if [[ -z "${!var:-}" ]]; then
			echo "$var must be set in the environment (see --help)" >&2
			exit 2
		fi
	done
fi
export PYTHONDONTWRITEBYTECODE=1
exec python3 "$here/b2c_hard.py" "$@"
