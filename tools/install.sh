#!/usr/bin/env bash
# Copies the module into the Eclipse workspace next to the sibling modules, compiles it into its
# target/classes overlay and declares its Tomcat context.
# RUN ONLY WHILE TOMCAT IS STOPPED: the server auto-deploys a new context file, and a class replaced
# under a running Tomcat gives NoSuchMethodError on already-loaded classes.
# Audit F2-114: no workstation path is built in. EBX_WORKSPACE is mandatory (exit 3 without it: the workspace that used to be the default is stopped),
# and ONE source installs at a time: the workspace keeps a stamp of the checkout that installed last (.ebx-mdm-factory-install-stamp); a script run
# from another checkout (another worktree) refuses (exit 4) until EBX_INSTALL_TAKE_OVER=1 says that checkout is now the deploy source (CLAUDE.md sec 15).
# Usage: EBX_WORKSPACE=<.../Workspace/ebx-ps-fasttrack> tools/install.sh     (redirect its output to a file: never pipe it into head)
set -euo pipefail
if [[ -z "${EBX_WORKSPACE:-}" ]]; then
	echo "MDMF-INSTALL-1: EBX_WORKSPACE is not set. Export it to the Eclipse workspace project folder of the instance whose Tomcat you stopped (.../Workspace/ebx-ps-fasttrack), then run again." >&2
	exit 3
fi
ws="$EBX_WORKSPACE"
repo_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
# shellcheck source=jar_pin.sh
source "$repo_dir/tools/jar_pin.sh"
stamp="$ws/.ebx-mdm-factory-install-stamp"
if [[ -f "$stamp" && "${EBX_INSTALL_TAKE_OVER:-}" != "1" ]]; then
	last_source="$(sed -n 's/^source=//p' "$stamp" | head -n 1)"
	if [[ -n "$last_source" && "$last_source" != "$repo_dir" ]]; then
		echo "MDMF-INSTALL-3: this workspace was last installed from '$last_source' (see $stamp), not from '$repo_dir'. Only one checkout deploys: install from that one, or set EBX_INSTALL_TAKE_OVER=1 to make this checkout the deploy source." >&2
		exit 4
	fi
fi
# Only the Tomcat of THIS workspace matters: another instance on the machine (a 6.2.3 demo, a second RC) may keep running.
if pgrep -f "org.apache.catalina.startup.Bootstrap" 2>/dev/null | xargs -r ps -o command= -p 2>/dev/null | grep -F -- "-Dcatalina.base=$ws/" >/dev/null 2>&1; then
	echo "The Tomcat of $ws is running (org.apache.catalina.startup.Bootstrap): stop it first." >&2
	exit 1
fi
[[ -d "$ws/ebx-ps-fasttrack-server/lib" ]] || { echo "MDMF-INSTALL-2: not an ebx-ps-fasttrack workspace: $ws" >&2; exit 2; }
"$repo_dir/tools/agent_jar.sh"
"$repo_dir/tools/mapping_jar.sh"
# X3-08 (1.4.1): the module runs on its OWN Jackson 2.18.12 (three pinned jars in WEB-INF/lib), not on the 2.14.2 of the FastTrack server lib/ - code compiled and tested
# against 2.18.12 must not execute on 2.14.2 (11 OSV advisories). Tomcat loads WEB-INF/lib of the module before the common class loader's server lib/ (no delegate/Loader in the
# context file below). Pinned by SHA-256 (tools/embedded-jars.sha256), taken from JACKSON_JAR_DIR or the jar store (tools/jackson_jars.sh).
"$repo_dir/tools/jackson_jars.sh"
source_lib="$repo_dir/ebx-mdm-factory/src/main/webapp/WEB-INF/lib"
dest_lib="$ws/ebx-mdm-factory/src/main/webapp/WEB-INF/lib"
# audit F-03: the rsync'ed copy has no .git, so the version and commit are decided here, from the repository this script lives in,
# and handed to the build (build.sh writes them into version.properties; GET /status then reports them).
FACTORY_VERSION="$("$repo_dir/tools/version.sh" version)"
FACTORY_COMMIT="$("$repo_dir/tools/version.sh" commit)"
export FACTORY_VERSION FACTORY_COMMIT
echo "version $FACTORY_VERSION (commit $FACTORY_COMMIT)"
mkdir -p "$ws/ebx-mdm-factory"
# The rsync below runs with --delete: the WEB-INF/lib of the workspace copy becomes EXACTLY the source's (the two Connectors jars and the three pinned Jackson jars). A jar
# that sits in the workspace copy's WEB-INF/lib and not in the source (an older Jackson, anything somebody added by hand) is deleted - said here, jar by jar, before it happens.
if [[ -d "$dest_lib" ]]; then
	for existing in "$dest_lib"/*; do
		[[ -e "$existing" ]] || continue
		if [[ ! -e "$source_lib/$(basename "$existing")" ]]; then
			echo "rsync --delete will remove $(basename "$existing") from $dest_lib (not one of the module's pinned jars)"
		elif ! cmp -s "$existing" "$source_lib/$(basename "$existing")"; then
			echo "rsync will replace $(basename "$existing") in $dest_lib (another build than the pinned one)"
		fi
	done
fi
# the workspace's own Eclipse .classpath is neither overwritten nor deleted (it is amended below, in place); a workspace without one gets none
rsync -a --delete --exclude 'target/' --exclude '.settings/' --exclude '/.classpath' --exclude '/.classpath.bak-*' "$repo_dir/ebx-mdm-factory/" "$ws/ebx-mdm-factory/"
# ... and the result is checked, not assumed: exactly the five pinned jars, with the pinned SHA-256, and no other build of Jackson anywhere in it
installed_jars="$(cd "$dest_lib" && ls)"
expected_jars="$(awk '{ print $2 }' "$repo_dir/tools/embedded-jars.sha256" | LC_ALL=C sort)"
if [[ "$installed_jars" != "$expected_jars" ]]; then
	echo "MDMF-INSTALL-4: $dest_lib holds [$(echo $installed_jars)] but the pinned jars are [$(echo $expected_jars)]: nothing was compiled, check the workspace copy by hand" >&2
	exit 5
fi
while read -r pinned_hash pinned_name; do
	[[ -n "$pinned_name" ]] || continue
	if [[ "$(jar_sha256 "$dest_lib/$pinned_name")" != "$pinned_hash" ]]; then
		echo "MDMF-INSTALL-4: $dest_lib/$pinned_name is not the pinned build ($pinned_hash): nothing was compiled" >&2
		exit 5
	fi
done < "$repo_dir/tools/embedded-jars.sha256"
echo "WEB-INF/lib of the installed module: $(echo $installed_jars)"
"$ws/ebx-mdm-factory/build.sh"
context="$ws/ebx-ps-fasttrack-server/conf/Catalina/localhost/ebx-mdm-factory.xml"
if [[ ! -f "$context" ]]; then
	cat > "$context" <<'XML'
<Context path="/ebx-mdm-factory" docBase="${catalina.base}/../ebx-mdm-factory/src/main/webapp" reloadable="false">
    <Resources>
        <PostResources className="org.apache.catalina.webresources.DirResourceSet" webAppMount="/WEB-INF/classes" base="${catalina.base}/../ebx-mdm-factory/target/classes"/>
    </Resources>
</Context>
XML
	echo "context declared: $context"
fi
# the stamp is written last, once the install succeeded
{
	printf 'source=%s\n' "$repo_dir"
	printf 'commit=%s\n' "$FACTORY_COMMIT"
	printf 'installed=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$stamp"
echo "installed in $ws/ebx-mdm-factory - import the project in Eclipse (first time) or F5, then start Tomcat"
# Eclipse resolves the class path in order and its builder recompiles target/classes when the workspace is opened: the pinned Jackson must come BEFORE the server project entry
# (whose lib/ exports Jackson 2.14.2), or Eclipse would write "Unresolved compilation problem" classes (StreamReadConstraints...) into the target/classes Tomcat serves.
if [[ -f "$ws/ebx-mdm-factory/.classpath" ]]; then
	python3 -I "$repo_dir/tools/classpath_amend.py" "$ws/ebx-mdm-factory/.classpath" $(awk '{ print $2 }' "$repo_dir/tools/embedded-jars.sha256")
else
	echo "classpath: $ws/ebx-mdm-factory/.classpath does not exist (project not imported in Eclipse yet): nothing created. Import the project, then run this script again, or add the five lib entries of src/main/webapp/WEB-INF/lib yourself, the three jackson-*.jar BEFORE the /ebx-ps-fasttrack-server project entry."
fi
echo "REMINDER: refresh the project in Eclipse (F5) after this installation. If Eclipse was opened on a workspace whose .classpath was not amended, it compiled against Jackson 2.14.2: fix the .classpath (run this script again), refresh, then Project > Clean, before starting Tomcat."
