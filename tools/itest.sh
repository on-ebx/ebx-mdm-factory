#!/usr/bin/env bash
# Integration suite of the MDM Factory against a running ON EBX instance (audit F-05). See tools/itest/itest.py (header) and
# docs/OPERATIONS.md, "Integration tests". Intended for a disposable or staging instance.
# Usage:   EBX_AUTH=<administrator>:<password> EBX_AUTH_NOROLE=<account with no Factory right>:<password> tools/itest.sh <base-url> [--only 1,2,4]
#          tools/itest.sh --selftest      # the suite against its own stand-in server on 127.0.0.1 (no EBX needed); also run by tools/test_local.sh
# Credentials come only from the environment and are never printed. ITEST_FULL=1 adds the heavy end-to-end scenario.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }
if [[ "${1:-}" == "--selftest" ]]; then
	exec python3 "$here/itest/selftest.py"
fi
exec python3 "$here/itest/itest.py" "$@"
