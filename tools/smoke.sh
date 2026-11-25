#!/usr/bin/env bash
# Post-install smoke test of an MDM Factory installation (audit F-05, part). READ requests only: nothing is created, changed or launched.
# Usage:   EBX_AUTH=<administrator>:<password> [EBX_AUTH_NOROLE=<account with no MDM Factory right>:<password>] tools/smoke.sh <base-url>
#          e.g. tools/smoke.sh http://localhost:8080
# Checks (PASS / FAIL / SKIP per line, exit 1 when any FAIL):
#   1. GET /ebx/ answers (EBX itself is up)
#   2. GET /ebx-mdm-factory/rest/v1/status without credentials discloses nothing (401/403, or an answer with no dataset state)
#   3. with EBX_AUTH: the status answer is 200, names the module, reports the dataset state, the build version (version.properties,
#      not "unknown") and sessionTokenMintable (false = the internal token API changed on this EBX build: FAIL)
#   4. with EBX_AUTH: a gated GET route (the jobs of a project that does not exist; right VIEW since audit F-02) answers 404, i.e. the administrator got past the gate
#   5. with EBX_AUTH_NOROLE (no Factory role, no "Access rights" row, no Everyone row): the same route answers 403, and its status answer is the bare liveness one
# Credentials come ONLY from the environment and reach curl through a private (mode 600) -K file removed on exit: never on a command
# line, never printed. Without EBX_AUTH the authenticated checks are SKIPped and the exit code is 4 ("incomplete"), unless SMOKE_ANON_ONLY=1.
set -euo pipefail

base="${1:-}"
if [[ -z "$base" ]]; then
	echo "usage: EBX_AUTH=user:password tools/smoke.sh <base-url>" >&2
	exit 2
fi
base="${base%/}"
module="${EBX_MODULE:-ebx-mdm-factory}"
rest="$base/$module/rest/v1"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
umask 077

# a curl config file: the value sits between double quotes with backslash and quote escaped, so nothing in a password is read as syntax
write_curlrc() {
	local file="$1" auth="$2" escaped
	escaped="${auth//\\/\\\\}"
	escaped="${escaped//\"/\\\"}"
	printf 'user = "%s"\n' "$escaped" > "$file"
}
auth_ok() {
	[[ "$1" == *:* && "${1%%:*}" != "" ]]
}

admin_rc=""
norole_rc=""
if [[ -n "${EBX_AUTH:-}" ]]; then
	auth_ok "$EBX_AUTH" || { echo "EBX_AUTH must look like user:password" >&2; exit 2; }
	admin_rc="$work/admin.rc"
	write_curlrc "$admin_rc" "$EBX_AUTH"
fi
if [[ -n "${EBX_AUTH_NOROLE:-}" ]]; then
	auth_ok "$EBX_AUTH_NOROLE" || { echo "EBX_AUTH_NOROLE must look like user:password" >&2; exit 2; }
	norole_rc="$work/norole.rc"
	write_curlrc "$norole_rc" "$EBX_AUTH_NOROLE"
fi

pass=0
fail=0
skip=0
report() { # <PASS|FAIL|SKIP> <label> [detail]
	case "$1" in
	PASS) pass=$((pass + 1)) ;;
	FAIL) fail=$((fail + 1)) ;;
	SKIP) skip=$((skip + 1)) ;;
	esac
	printf '%-5s %s%s\n' "$1" "$2" "${3:+ - $3}"
}

# fetch <body file> [curl config file]: prints the HTTP status code (000 when nothing answered); never prints the body
fetch() {
	local out="$1" rc="${2:-}" url="$3" code
	local args=(-s -o "$out" -w '%{http_code}' --max-time 30 -H 'Accept: application/json')
	if [[ -n "$rc" ]]; then args+=(-K "$rc"); fi
	code="$(curl "${args[@]}" "$url" 2>/dev/null)" || code="000"
	printf '%s' "${code:-000}"
}

# json_field <file> <key>: the value of a top-level key as text, empty when absent or when the file is not a JSON object
json_field() {
	python3 -c '
import json, sys
try:
	data = json.load(open(sys.argv[1]))
	value = data.get(sys.argv[2]) if isinstance(data, dict) else None
except Exception:
	value = None
if value is not None:
	print(json.dumps(value) if isinstance(value, (bool, dict, list)) else value)
' "$1" "$2"
}

echo "smoke test of $base (module $module)"

# 1
code="$(fetch "$work/ebx.out" "" "$base/ebx/")"
case "$code" in
200 | 301 | 302 | 303 | 307) report PASS "GET /ebx/" "HTTP $code" ;;
*) report FAIL "GET /ebx/" "HTTP $code" ;;
esac

# 2
code="$(fetch "$work/anon.out" "" "$rest/status")"
case "$code" in
401 | 403) report PASS "GET status without credentials" "HTTP $code" ;;
200)
	if [[ -z "$(json_field "$work/anon.out" datasetState)" ]]; then report PASS "GET status without credentials" "HTTP 200, liveness only"; else report FAIL "GET status without credentials" "HTTP 200 and it discloses the dataset state"; fi
	;;
*) report FAIL "GET status without credentials" "HTTP $code" ;;
esac

if [[ -z "$admin_rc" ]]; then
	report SKIP "administrator checks" "EBX_AUTH is not set"
else
	# 3
	code="$(fetch "$work/status.out" "$admin_rc" "$rest/status")"
	if [[ "$code" != "200" ]]; then
		report FAIL "GET status as administrator" "HTTP $code"
	else
		report PASS "GET status as administrator" "HTTP 200"
		mod="$(json_field "$work/status.out" module)"
		if [[ "$mod" == "$module" ]]; then report PASS "status names the module" "$mod"; else report FAIL "status names the module" "got '${mod:-nothing}'"; fi
		state="$(json_field "$work/status.out" datasetState)"
		if [[ -n "$state" ]]; then report PASS "dataset state" "$state"; else report FAIL "dataset state" "absent from the status answer"; fi
		ver="$(json_field "$work/status.out" version)"
		if [[ -z "$ver" ]]; then
			report FAIL "build version" "absent from the status answer (a build older than audit F-03?)"
		elif [[ "$ver" == "unknown" ]]; then
			report FAIL "build version" "unknown: version.properties is missing from the deployed classes (run build.sh or install the war)"
		else
			report PASS "build version" "$ver (commit $(json_field "$work/status.out" commit))"
		fi
		mint="$(json_field "$work/status.out" sessionTokenMintable)"
		case "$mint" in
		true) report PASS "sessionTokenMintable" "true" ;;
		false) report FAIL "sessionTokenMintable" "false: the internal token API changed on this EBX build, calls to EBX's own MCP servers will fail" ;;
		*) report PASS "sessionTokenMintable" "not reported (not checked at startup)" ;;
		esac
	fi
	# 4
	code="$(fetch "$work/adminroute.out" "$admin_rc" "$rest/projects/smoke-no-such-project/jobs")"
	if [[ "$code" == "404" ]]; then report PASS "gated route as administrator" "HTTP 404 (past the gate, no such project)"; else report FAIL "gated route as administrator" "HTTP $code, expected 404"; fi
fi

# 5
if [[ -z "$norole_rc" ]]; then
	report SKIP "non-administrator checks" "EBX_AUTH_NOROLE is not set"
else
	code="$(fetch "$work/norole.out" "$norole_rc" "$rest/projects/smoke-no-such-project/jobs")"
	if [[ "$code" == "403" ]]; then report PASS "gated route as an account with no Factory right" "HTTP 403"; else report FAIL "gated route as an account with no Factory right" "HTTP $code, expected 403"; fi
	code="$(fetch "$work/noroles.out" "$norole_rc" "$rest/status")"
	if [[ "$code" == "200" && -z "$(json_field "$work/noroles.out" datasetState)" ]]; then report PASS "status as a non-administrator is liveness only" "HTTP 200"; else report FAIL "status as a non-administrator is liveness only" "HTTP $code"; fi
fi

echo "$pass passed, $fail failed, $skip skipped"
if ((fail > 0)); then exit 1; fi
if [[ -z "$admin_rc" && "${SMOKE_ANON_ONLY:-}" != "1" ]]; then
	echo "INCOMPLETE: set EBX_AUTH (and EBX_AUTH_NOROLE) for the authenticated checks, or SMOKE_ANON_ONLY=1 to accept an anonymous-only run" >&2
	exit 4
fi
exit 0
