#!/usr/bin/env bash
# RC test of the agent framework (lot A) and source drift (lot B): see tools/e2e/agents_drift_rc.py (header).
# Usage:   EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/agents_drift_rc.sh [--only agents|drift] [--project CODE [--cleanup]] [--keep] [--decide-by-route] [--report FILE]
#          tools/e2e/agents_drift_rc.sh --dry-run    # prints the plan; sends nothing
# Credentials come only from the environment and are never printed. The test WRITES (a scratch project, its dataspaces and models, agent and drift rows): test instance only.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }
mode_offline=0
for arg in "$@"; do
	case "$arg" in
	--dry-run | -h | --help) mode_offline=1 ;;
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
exec python3 "$here/agents_drift_rc.py" "$@"
