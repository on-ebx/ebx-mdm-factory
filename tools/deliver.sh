#!/usr/bin/env bash
# Delivery of one MDM Factory project through EBX Staging (docs/DELIVERY.md): the Factory's read-only route
# lists the staging components of the project; this script creates (or refreshes) the staging domain from
# that list, exports its archive, and can dry-run or import that archive on a target - every call under the
# credentials given here, which EBX authenticates itself (native login, token or SSO). No Factory account.
#
# Usage:
#   tools/deliver.sh domain  <projectCode>                          create/refresh the domain mdm.factory.<projectCode> on $EBX
#   tools/deliver.sh export  <projectCode> <archive.zip>            download the domain's archive from $EBX
#   tools/deliver.sh dryrun  <archive.zip>                          dry-run import of an archive on $EBX (writes nothing)
#   tools/deliver.sh import  <archive.zip> [--dry-first]            import an archive on $EBX (administrators; back up first);
#   tools/deliver.sh import  <archive.zip> [--dry-first] [--publish]
#                            script libraries in the archive are imported FIRST (phase 1), then, with --publish, published
#                            through the scripting MCP server of $EBX (publish_script, same credentials), then everything
#                            else (phase 2): a library publication is not a staging element (docs/DELIVERY.md sec 6.5)
#                                                                    --dry-first runs the dry run first and only imports if it
#                                                                    did not answer FAILED
# import always passes forceUpdateOnSameHash=true: a dry run registers the archive's hash on the target, so a real
# import of the very same archive right after one - the normal "dry-run then import" sequence - would otherwise be
# silently IGNORED as "already imported" instead of actually writing anything (measured 2026-09-22, docs/DELIVERY.md
# sec 8). dryrun never sets it, so a repeated dry run still reports the true per-component status.
# Environment: EBX (base URL, default http://localhost:8080), EBX_MODULE (Factory context, default ebx-mdm-factory),
#              EBX_AUTH (user:password for the target, REQUIRED - there is no default account). When EBX_AUTH is not set
#              and the script runs in a terminal, it asks for the user and the password (typed password is not echoed);
#              without a terminal it stops. The credentials never go on a command line: curl reads them from a private
#              (mode 600) temporary configuration file that is removed when the script ends (audit F-52).
set -euo pipefail

ebx="${EBX:-http://localhost:8080}"
module="${EBX_MODULE:-ebx-mdm-factory}"
staging="$ebx/ebx-dataservices/rest/staging/v1"

status_file=""
curlrc=""
cleanup() { rm -f "$curlrc" "$status_file"; }
trap cleanup EXIT

# only the usage needs no credentials
case "${1:-}" in
domain|export|dryrun|import) ;;
*) sed -n 2,14p "$0"; exit 2 ;;
esac

auth="${EBX_AUTH:-}"
if [ -z "$auth" ]; then
	if [ -t 0 ]; then
		read -r -p "EBX user for $ebx: " ebx_user
		read -r -s -p "Password: " ebx_password; echo
		auth="$ebx_user:$ebx_password"
		unset ebx_password
	else
		echo "EBX_AUTH (user:password) is not set and there is no terminal to ask on: refusing to guess an account." >&2
		exit 2
	fi
fi
case "$auth" in
?*:*) ;;
*) echo "the credentials must look like user:password" >&2; exit 2 ;;
esac
# a curl config file: the value sits between double quotes with backslash and quote escaped, so nothing in a password is read as syntax
umask 077
curlrc="$(mktemp)"
escaped="${auth//\\/\\\\}"
escaped="${escaped//\"/\\\"}"
printf 'user = "%s"\n' "$escaped" > "$curlrc"
unset auth escaped
export DELIVER_CURLRC="$curlrc"

req() { curl -s -K "$curlrc" -H "Accept: application/json" "$@"; }

case "${1:-}" in
domain)
	code="${2:?project code}"
	listing="$(req "$ebx/$module/rest/v1/projects/$code/delivery/domain")"
	python3 - "$listing" "$staging" <<'EOF'
import json, os, subprocess, sys
listing, staging = json.loads(sys.argv[1]), sys.argv[2]
curlrc = os.environ["DELIVER_CURLRC"]
if "error" in listing:
	sys.exit("factory: " + listing["error"])
name = listing["domainName"]
for line in listing.get("refused", []):
	print("refused:", line)
for item in listing["preCheck"]["items"]:
	print("%-5s %s" % (item["level"], item["message"]))
def call(method, url, body=None):
	cmd = ["curl", "-s", "-K", curlrc, "-H", "Accept: application/json", "-H", "Content-Type: application/json", "-X", method, url]
	if body is not None:
		cmd += ["-d", json.dumps(body)]
	out = subprocess.run(cmd, capture_output=True, text=True).stdout
	return json.loads(out) if out.strip() else {}
existing = call("GET", "%s/domains/%s" % (staging, name))
if "uniqueName" not in existing:
	created = call("POST", "%s/domains" % staging, {"uniqueName": name, "label": "MDM Factory: " + listing["project"],
		"description": "Composed by the MDM Factory from project " + listing["project"] + "; refreshed by tools/deliver.sh."})
	if "uniqueName" not in created:
		sys.exit("staging: domain not created: " + json.dumps(created))
	print("domain created:", name)
else:
	print("domain exists:", name)
have = {(c["element"]["typeName"], c["element"]["id"], json.dumps(c["element"].get("context") or {}, sort_keys=True))
	for c in call("GET", "%s/domains/%s/components" % (staging, name)) if isinstance(c, dict)}
added = 0
for component in listing["components"]:
	e = component["element"]
	key = (e["typeName"], e["id"], json.dumps(e.get("context") or {}, sort_keys=True))
	if key in have:
		continue
	answer = call("POST", "%s/domains/%s/components" % (staging, name), component)
	if "id" not in answer:
		sys.exit("staging: component refused: %s -> %s" % (json.dumps(component), json.dumps(answer)))
	added += 1
print("components: %d in the listing, %d added, %d already there" % (len(listing["components"]), added, len(listing["components"]) - added))
if not listing["preCheck"]["ok"]:
	sys.exit("pre-check has errors: fix them before exporting")
EOF
	;;
export)
	code="${2:?project code}"; out="${3:?archive path}"
	status="$(curl -s -K "$curlrc" -o "$out" -w "%{http_code}" "$staging/archives/mdm.factory.$code")"
	[ "$status" = "200" ] || { echo "export failed: HTTP $status"; cat "$out"; exit 1; }
	echo "archive: $out ($(wc -c < "$out" | tr -d ' ') bytes)"; unzip -l "$out" | tail -1
	;;
dryrun|import)
	archive="${2:?archive path}"
	dryFirst=false; publish=false
	for flag in "${@:3}"; do
		case "$flag" in
		--dry-first) dryFirst=true ;;
		--publish) publish=true ;;
		*) echo "unknown flag $flag" >&2; exit 2 ;;
		esac
	done
	# the archive's script libraries: component names and IDE paths, read from its own manifest
	libraries="$(unzip -p "$archive" _package.json | python3 -c '
import json, sys
pkg = json.load(sys.stdin)
for ref in pkg.get("componentRefs", []):
	element = ref.get("elementId", {})
	if element.get("typeName") == "ScriptSource":
		print(ref["name"], "library/" + element["id"].replace("definition.library.", "", 1).replace(".", "/"))
')"
	library_names="$(printf "%s\n" "$libraries" | awk "NF{print \$1}" | paste -sd, -)"
	status_file="$(mktemp)"

	# $1 = isDryRun (true/false), $2 = extra query string (e.g. "&forceUpdateOnSameHash=true", or ""); writes the
	# domain-level status to $status_file so the caller can act on it without re-parsing the printed report.
	run_stage() {
		curl -s -K "$curlrc" -H "Content-Type: application/octet-stream" --data-binary "@$archive" "$staging/archives?isDryRun=$1$2" | python3 -c '
import json, sys
report = json.load(sys.stdin)
print("import", report.get("importId"), "domain", report.get("domain"), "status", report.get("status"))
for message in report.get("messages", []):
	print(" ", json.dumps(message)[:300])
# per-component result: the JSON field is reportItems (each with its own status), not only the domain-level one above
items = report.get("reportItems") or report.get("items") or report.get("components") or []
tally = {}
for item in items:
	status = str(item.get("status"))
	tally[status] = tally.get(status, 0) + 1
	label = item.get("name") or item.get("componentName") or item.get("id") or item.get("elementId") or "?"
	print("  %-30s %s" % (label, status))
if tally:
	print("component statuses:", ", ".join("%s=%d" % (k, v) for k, v in sorted(tally.items())))
with open(sys.argv[1], "w") as f:
	f.write(str(report.get("status")))
' "$status_file"
	}

	if [ "$dryFirst" = true ]; then
		echo "-- dry run first (--dry-first) --"
		run_stage true ""
		status="$(cat "$status_file")"
		if [ "$status" = "FAILED" ]; then
			echo "dry run failed: import aborted (--dry-first)"
			exit 1
		fi
		echo "-- dry run ok (status $status): importing for real --"
	fi

	if [ "$1" = "dryrun" ]; then
		run_stage true ""
	elif [ -z "$library_names" ]; then
		run_stage false "&forceUpdateOnSameHash=true"
	else
		echo "-- phase 1: script libraries ($library_names) --"
		run_stage false "&forceUpdateOnSameHash=true&includes=$library_names"
		if [ "$publish" = true ]; then
			echo "-- publishing the libraries through $ebx/ebx-assistant/mcp/v1/scripting --"
			printf "%s\n" "$libraries" | awk 'NF{print $2}' | while read -r idePath; do
				printf "  publish %-45s " "$idePath"
				curl -s -K "$curlrc" -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -X POST "$ebx/ebx-assistant/mcp/v1/scripting" \
					-d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"publish_script\",\"arguments\":{\"scriptIdePath\":\"$idePath\"}}}" | python3 -c '
import json, re, sys
text = sys.stdin.read(); match = re.search(r"\{.*\}", text, re.S)
answer = json.loads(match.group(0)) if match else {}
result = answer.get("result") or {}
content = result.get("content") or []
line = content[0].get("text", "") if content else json.dumps(answer)[:200]
if not result: line = "no result: is the scripting MCP server enabled on the target (Administration > MCP servers)? " + line
print(("ERROR " if result.get("isError") else "") + line[:200].replace("\n", " "))'
			done
		else
			echo "-- libraries imported as sources: publish them on the target (IDE or --publish) before phase 2 --"
		fi
		echo "-- phase 2: everything else --"
		run_stage false "&forceUpdateOnSameHash=true&excludes=$library_names"
	fi
	;;
*)
	sed -n 2,14p "$0"; exit 2
	;;
esac
