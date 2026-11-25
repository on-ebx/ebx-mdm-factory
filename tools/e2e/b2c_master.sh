#!/usr/bin/env bash
# The B2C scenario through the MASTER and MATCHING steps (wizard steps 5 and 6), with a ground truth to measure the matching quality (precision / recall / false merges), run in two
# variants (configure without / after a simulation). See docs/E2E-B2C.md ("Master and matching") and tools/e2e/b2c_master.py (header).
# Usage:   EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_master.sh [--variants plain|simulate-first|both] [--cleanup] [--through 1..11] [--report FILE] [--keep-files] [--auto-merge] [--distribute]
#          --distribute: step 10, the distribution of the golden records to Kafka (E2E_KAFKA_BOOTSTRAP localhost:9082, E2E_KAFKA_HOME the broker's folder); skipped with one line when the broker is down. Step 11 reads the summary's facts.
#          tools/e2e/b2c_master.sh --cleanup-project CODE [--keep-topics]   # deletes a project an earlier run left in place (the default: it stays, for a visual pass) and its Kafka topics (<code lower case>.* only)
#          tools/e2e/b2c_master.sh --from-project CODE --from-step N [--variants plain|simulate-first] [--through M]   # continues that project from step N (2..11: typically 7 master, 8 matching, 10 distribution)
#          tools/e2e/b2c_master.sh --selftest               # the assertion logic and the wiring against a stand-in server (no instance)
#          tools/e2e/b2c_master.sh --dry-run                # generates and checks the files and the truth and prints the plan; sends nothing
# Credentials come only from the environment and are never printed. The scenario WRITES (project, Connectors connections, dataspaces, models, a master load, a matching run): test instance only.
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
exec python3 "$here/b2c_master.py" "$@"
