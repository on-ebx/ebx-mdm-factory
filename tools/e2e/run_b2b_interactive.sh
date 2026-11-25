#!/usr/bin/env bash
# Runs the B2B end-to-end scenario against the local RC, asking for the administrator's credentials in the terminal.
# The password is read without echo, kept in this process's environment only, and never written to disk or printed.
# Usage: tools/e2e/run_b2b_interactive.sh [extra b2b_scenario.sh arguments]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
export EBX_URL="${EBX_URL:-http://localhost:8080}"
read -r -p "EBX administrator login: " EBX_USER
read -r -s -p "Password (not shown): " EBX_PASSWORD
echo
export EBX_USER EBX_PASSWORD
# a SAP OData envelope {"d":{"results":[...]}} is not unwrapped by Connectors' file reader yet: the run uses {"results":[...]} unless told otherwise
export E2E_SAP_ENVELOPE="${E2E_SAP_ENVELOPE:-results}"
exec "$here/b2b_scenario.sh" "$@"
