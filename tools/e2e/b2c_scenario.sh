#!/usr/bin/env bash
# Automated end-to-end scenario of the MDM Factory on the B2C test (lot H2): project -> three sample files -> landing -> staging proposal -> decisions
# -> build and load, with a PASS/FAIL table and a JSON report. See docs/E2E-B2C.md and tools/e2e/b2c_scenario.py (header).
# Usage:   EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_scenario.sh [--cleanup] [--through 1..6] [--report FILE] [--keep-files]
#          tools/e2e/b2c_scenario.sh --selftest   # the assertion logic and the wiring against a stand-in server (no instance)
#          tools/e2e/b2c_scenario.sh --dry-run    # generates and checks the three files and prints the plan; sends nothing
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
exec python3 "$here/b2c_scenario.py" "$@"
