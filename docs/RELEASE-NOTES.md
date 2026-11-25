# MDM Factory 1.4.3 - release notes

Version 1.4.3, written 2026-11-12 on top of `v1.4.2` (decision of the owner: "copy the permissions of the parent dataspace by default"). **No data model change.** Read `docs/UPGRADE.md` section 15 before installing.

**What is measured and what is not.** Measured on a clean standard ON EBX 6.3.0-gp instance with the 1.4.2 war: with `-Debx.factory.zonePermissionsFrom=Reference` the end-to-end scenario passes the landing (12 steps out of 12); without it, `create_dataset` is refused (X3-11). Measured offline: the new tests (`ZonePermissionsTest`, `ZoneDatasetDenialTest`). **Not measured**: the 1.4.3 build on an instance (the default path calls the same public API as the measured setting).

## 1. What changes

- **A new zone copies its parent's permissions by default (X3-11).** `HomeCreationSpec.setHomeToCopyPermissionsFrom(parent)` for the landing, staging, master and domain dataspaces when `-Debx.factory.zonePermissionsFrom` is not set; the parent is the dataspace each site gives to `setParent` (Reference). A dataspace key keeps meaning "that dataspace" and wins over the default; `none` keeps the 1.4.2 behaviour (no copy); a key that names no dataspace copies nothing and logs a WARN. The owner stays `administrator`. Existing zones are not touched. The module's own dataspace (`FactoryInstaller`) is not a generated zone and is unchanged.
- **Visible.** `GET /rest/v1/status` has `zonePermissions` (mode, copiedFrom); the step detail `dataspaceDetail.permissionsCopiedFrom` names the source (`none` when nothing was copied); the kernel log says it once at start.
- **`MDMF-ZONE-1` is right in all three cases** (EN and FR): it names the current setting and says that with the default the refusal comes from the parent's own permissions, with a named dataspace check that one, with `none` remove the setting or name a dataspace.
- **The zone a refusal leaves behind is now really removed.** 1.4.2 removed a zone it had just created only when it held no dataset; a child dataspace starts with every dataset of its parent, so on a real instance that was never the case (measured 2026-11-12: "could not be removed (the zone is not empty)"). The zone is now removed when it holds nothing OF ITS OWN: no dataset its parent does not have, no snapshot but the initial one, no child dataspace.
- **For an operator:** what the roles see on a new zone becomes what they see on Reference; check Reference's permissions before the upgrade, or name a narrower template dataspace. `OPERATIONS.md` section 2.6 no longer has a "second prerequisite".

## 2. What is not in this release

Everything listed as not in 1.4.2 except the default of the zone permissions.

# MDM Factory 1.4.2 - release notes

Version 1.4.2, written 2026-11-12 on top of `v1.4.1` (the "restes d'octobre" of the production review). **No data model change** (the XSD is untouched). Read `docs/UPGRADE.md` section 14 before installing.

**What is measured and what is not.** Measured: the offline suite (`tools/test_local.sh`) with one new test class per finding (the results are in the hand-over of the lot). **Not measured**: any of this on a running instance - the access rule on `Project/style` (including whether Data Services also
refuses a `style` that equals the default), the restore bounds on a real archive (the defaults are reasoned from the code), and the behaviour of the translated `MDMF-ZONE-1` refusal (the zone deletion calls `Repository.deleteHome` as `GeneratedObjectDeleter` does, never run on a standard instance).

## 1. What changes

- **A project cannot be created in a style that is not open (F3-13, F3-114).** `Project/style` is read only on every permission-checked path, creation included: a DESIGN holder could create a Centralisation project by Data Services; now
  `MDMF-STYLE-1` (creation) / `MDMF-STYLE-2` (modification), bilingual. Only the wizard sets it, for an open style. **A client that creates projects by Data Services must send no `style`** (the default, Consolidation, applies).
  A restore of a Centralisation archive is refused (`MDMF-STYLE-3`). Existing Centralisation projects keep working. The wizard no longer writes "Coming soon" on the card of a saved project's own style.
- **Restore bounds (F2-11).** `-Debx.factory.restore.maxBytes` (default 2 GiB) and `-Debx.factory.restore.maxRows` (default 5,000,000); `MDMF-RESTORE-1`; published as `restoreBounds` in `GET /rest/v1/status`.
- **A backup no longer holds master values of the agent tables while the agents are closed (F3-06).** `AgentProposal.{arguments, rationale, applyMessage, target}` and `AgentRun.inputs` are left out and named under `valuesOmitted`; `notIncluded` is exact.
- **Two leftovers of the closed functions (only matter if you open them).** F3-08: `POST /agents/{code}/run` needs the agent switched on with a valid signed activation. F3-12: a `SourceDrift` row's `project` must be the project of its landing table (`engine.invalid.driftProjectMismatch`).
- **A package needs the real gate (gap 12).** `tools/package.sh` refuses without `TOMCAT_LIB_DIR` and `EBX_STANDARD_JAR` (`MDMF-PACKAGE-6`).
- **Preparation for the Connectors jars (F3-100).** `tools/agent_jar.sh` and `tools/mapping_jar.sh` accept `ON_EBX_CONNECTORS_JARS_DIR` (an unpacked Connectors package), still against the pinned SHA-256. **The pin did not move.**
- **A readable refusal when a standard instance will not create a zone's dataset (X3-11).** Known finding, measured 2026-11-12 on a clean 6.3.0-gp instance: a zone created with no permissions refuses `create_dataset` even to an administrator
  (`tools/call: Tool invocation failed: `, nothing after the colon). Workaround: `-Debx.factory.zonePermissionsFrom=<a dataspace created by an administrator>` and a restart (`docs/OPERATIONS.md` section 2.6). 1.4.2 does NOT change the default;
  it translates the empty failure into `MDMF-ZONE-1` (landing, staging and master) and deletes the zone it has just created so that a relaunch with the setting works (as written in 1.4.2 this never happened on a real instance: a child dataspace starts with the datasets of its parent, so the zone was never "empty"; 1.4.3 removes the zone when it holds nothing of its own).
- **Guides corrected.** The MCP prerequisite of a new instance is a "Server permissions" line for the profile, not the "Global server activation" button (the servers are active by default); `OPERATIONS.md` and the README no longer say the module was never installed elsewhere; `PERMISSIONS.md` and `LLM-DATA-POLICY.md` cover the agents and the drift;
  the model build time of `docs/VOLUME.md` is re-measured (109 operations in 1.15 s on 6.3.0-gp; the 300 s delay is unchanged); `BackupTables.NOT_INCLUDED` is exact.

## 2. What is not in this release

The class loader of the module's threads (F3-09), a JVM per test class (F2-114), everything that only matters once the agents or the application of drifts are open beyond the two leftovers above (F3-03 to F3-07, F3-101, F3-104 to F3-112), the continuous integration of the repository,
the closing of the `ACCEL_*` actions of an existing Centralisation project, and the default of the zone permissions (question to the owner). `tools/itest/scenarios.py` still posts `style` when it creates its scratch project: change those two rows before running `tools/itest.sh` against 1.4.2
(it is on another branch).

# MDM Factory 1.4.1 - release notes

**Rehearsed on 2026-11-12, after the text below was written.** The three 1.4.1 wars, as packaged, were installed on a clean ON EBX 6.3.0-gp built from the standard distribution: new repository, 28 s, 0 ERROR, `/rest/v1/status` 200 for the three modules, each reporting Jackson 2.18.12 and its own read bounds; a body nested 80 and 5,000 levels deep got the coded refusal on the Connectors and catalog MCP entries; the OpenAPI import in YAML and the catalog's YAML contract route answered. The same wars then started on the repository created by 1.4.0, with Match and Merge and DAMA (43 s, 0 ERROR). `tools/install.sh` of the three modules was run on a copy of the development workspace: jars added and replaced, unknown jars left, Eclipse `.classpath` amended, build green, second run a no-op. Not done: an installation on the running development instance, a start from Eclipse, Kafka with snappy, a real Service Bus, a project of the MDM Factory from end to end, the way back to 1.4.0.

Version 1.4.1, written 2026-11-12 on top of `v1.4.0` (finding X3-08 of the production review of 2026-11-12). It changes the embedded libraries and what surrounds them; no data
model change, no behaviour of the data path changes. Read `docs/UPGRADE.md` section 13 before installing over a 1.4.0 instance.

**What is measured and what is not.** Measured on 2026-11-12 on the build machine: the offline suite (`tools/test_local.sh`) green on Jackson 2.18.12 (the line that names the jar
the tests loaded is in the suite's journal, `core.JacksonLoadedTest`), `tools/install.sh` on a FAKE workspace (`tools/install_selftest.sh`), the advisories check against the live
OSV on the 1.4.0 and on the 1.4.1 jars. **Not measured**: the 1.4.1 package or `install.sh` on a running instance (no deployment was made), the module on the FastTrack server
with the Jackson of its `WEB-INF/lib` in front of the server's 2.14.2 (read from the Tomcat configuration, not run), and a project end to end on 2.18.12.

## 1. What changes

- **Jackson 2.14.2 becomes 2.18.12** (`jackson-core`, `jackson-databind`, `jackson-annotations`, Apache License 2.0, from Maven Central; SHA-256 in `tools/embedded-jars.sha256`).
  2.14.2 carried 14 OSV advisories on 2026-11-12 (3 on `jackson-core`, 11 on `jackson-databind`; the review of the morning counted 11, three were published since), fixed from
  2.18.11; 2.18.12 has none today. The code of the module uses only the stable `readTree` / tree API (no polymorphic typing, no `@JsonView`, no `readValue` to classes), so the
  advisories on those features never applied to it by its own code; the ones on parsing (deep nesting, huge numbers) did.
- **The module runs on its own Jackson everywhere.** The package already carried it (1.4.0). `tools/install.sh` now puts the same three jars (with the two Connectors jars: exactly
  five) in the installed module's `WEB-INF/lib`, so a FastTrack instance no longer runs code built on 2.18.12 on the 2.14.2 of its server `lib/`. `/status` `embeddedJars` and
  `version.properties` list the three Jackson jars in both installation modes. `build.sh`, the suite and the Eclipse `.classpath` put them first.
- **JSON read bounds are a written choice** (Jackson 2.15 and later bound every parser): nesting depth 1000, string 20,000,000 characters, number 1000 characters, name 50,000.
  The module keeps these defaults (no legitimate input of the module comes near them: `docs/OPERATIONS.md` "JSON read bounds"), publishes them as `readBounds` in `GET /rest/v1/status`,
  and refuses a document beyond them with a code: `MDMF-JSON-1` (beyond a bound) or `MDMF-JSON-2` (not JSON) in a 400 on the routes that read a body, `invalid.jsonBeyondBound` /
  `invalid.malformedJson` on the distribution-target route, a coded `Refusal` on the Connectors contract. On Jackson 2.14.2 the same body was read whatever its depth
  (measured: `readTree` of 1,000,000 nested objects or arrays answered without error, no `StackOverflowError` on that call; the advisories on deep nesting concern other
  call paths); it is now a 400.
- **The package refuses an unaccepted advisory.** `tools/package.sh` runs `tools/deps_check.py` first: it asks OSV (`api.osv.dev`, through `curl`) about each embedded jar that has
  Maven coordinates (`tools/embedded-jars-maven.txt`: the three Jackson jars), compares with `tools/deps-known.txt` (accepted advisories with a date and a reason; empty), and
  refuses (`MDMF-PACKAGE-4`). Without network it refuses too, unless `DEPS_OFFLINE_WAIVER="<reason>"` names a waiver; the package then says in `dependency-check.txt` and in these
  notes that the advisories were NOT checked. The two Connectors jars have no Maven coordinates and are covered by the Connectors repository's own check. `tools/test_local.sh`
  stays offline (it tests the script against a recorded answer).
- **Licences.** `jackson-core` 2.18 bundles a copy of FastDoubleParser (MIT, with a part under the Boost Software License) and the Schubfach number writer (MIT): their texts are in
  `licenses/`, named in `THIRD-PARTY-NOTICES.md`.

## 2. What you may notice

- A JSON document with a single string of more than 20 million characters, nested deeper than 1000 levels, or a number of more than 1000 digits is refused (it was accepted, or
  overflowed the stack, on 2.14.2). Nothing the module sends or stores comes near that; the one route that takes a file (`POST /projects/{code}/sources/files`) is capped at
  1,400,000 raw bytes per submit.
- `install.sh` announces, jar by jar, what its `rsync --delete` removes from the module's `WEB-INF/lib` of the workspace, then checks that what remains is exactly the five pinned
  jars (`MDMF-INSTALL-4`, exit 5 otherwise). A jar somebody added there by hand is removed (it always was; it is now said).
- `MDMF-PACKAGE-2` (a missing or different Jackson jar) now says where to find the jars (Maven Central coordinates and SHA-256) instead of pointing at `EBX_LIB_DIR`.

## 3. Found on the way (reported, not changed here)

- **`on-ebx-agent.jar`'s HTTP path words a read-bound refusal "MCP server unreachable at <url>: StreamConstraintsException ..."** (measured with a stand-in server answering 5,000
  nested arrays): the bound is the cause and the text names it, but "unreachable" is wrong. The jar is built by the ebx-connectors repository; the Factory does not change it.
- The advisory list the review of the morning used had 11 entries; OSV had 14 on the same jars in the evening (three new on `jackson-databind`).


---

# MDM Factory 1.4.0 - release notes

Version 1.4.0, written 2026-11-12 for the content of `main` after `v1.3.0` (the tag is placed afterwards, by someone else; nothing here tags or pushes).
Source of these notes: `git log --first-parent v1.3.0..HEAD` and the diffs behind it, and the production review of 2026-11-12
(`audit-production-2026-11-12/`, findings F3-xx). `tools/package.sh` puts this file in the release zip, followed by the git log since the previous tag.
Read `docs/UPGRADE.md` section 12 before installing over a 1.3.0 instance, and `docs/OPERATIONS.md` for everything else.

**Partly rehearsed.** On 2026-11-12 the three wars were installed on a clean instance built from the standard distribution (with and without Match and Merge); no project was run there (the ON EBX MCP servers are closed on a new instance), and the 1.4.0 package has not been installed on any other instance. No upgrade from a 1.3.0 repository has been
replayed, and no rollback has been run. The offline suite is green (`tools/test_local.sh`); what it cannot show is listed in `docs/OPERATIONS.md` section 2.6
and `docs/UPGRADE.md` section 12.6.

## 1. Shipped open (active on an instance that sets nothing)

- **Match and Merge MCP server found under both its names.** The module looks for `match-merge-admin` (the name since the Match and Merge add-on of 2026-10-21),
  then `match-merge` (the earlier one). `-Debx.factory.matchMergeServer=<segment>` pins one. The choice is made once per JVM, at the first Match and Merge client, by an MCP `initialize`.
- **The module's services sit under one submenu** of the Actions menus of business tables, named after the module.
- **Centralisation is shown as "Coming soon"** in step 1 of the wizard; only Consolidation starts a new project. A project that already has the
  Centralisation style keeps its steps. `Project.style` is read only on a saved project.
- **Data model, additive only (restart required).** 30 tables become 33: `AgentDefinition`, `AgentProposal`, `SourceDrift`; eleven optional fields on `AgentRun`;
  one on `FactorySettings` (`agentActivationKey`, drawn at the first start, hidden from everybody). No key changed, nothing removed. The tables exist and are
  empty on an instance that never opens the functions below. A sixth workflow bean is declared in `module.xml`.
- **Lifecycle of the new tables.** Project deletion, landing reset, the daily purge and the project backup know them (`AgentDefinition` is deliberately left out of the
  backup): `docs/OPERATIONS.md` section 9 (F3-10, F3-103), `docs/PROJECT-BACKUP.md`.
- **`GET /status`** (administrators) now reports `featureSwitches.agents` and `featureSwitches.sourceDrift` (`enabled`, `property`, and for the agents
  `activationKeyPresent`) and counts 33 tables.
- **The release zip carries `AGENTS.md`, `DRIFT.md` and these notes** beside `OPERATIONS.md` and `UPGRADE.md`; the guides are corrected to what the code does today
  (F3-15). `docs/OPERATIONS.md` section 1 now says that `ebx-data-catalog` is required (F3-100), section 2.6 describes an install on a non-FastTrack instance (rehearsed in part on 2026-11-12, X3-07), section 7.2 forbids hot reload in production.
- **The package installs on a standard ON EBX server (X3-07).** Its war embeds Jackson 2.14.2 (three pinned jars, Apache License 2.0, notices in the zip); a standard server has none and the module did not start without it. `tools/package.sh` also refuses a war that references any class found nowhere (`tools/check_war.py`), and the scripts accept `ebx.jar` as well as `ebx-lib.jar`. `tools/install.sh` (FastTrack) is unchanged. `docs/OPERATIONS.md` section 2.6 says what was rehearsed and what was not.
- **Behaviour you should notice on upgrade: none for the data path.** With both switches closed a landing plan behaves exactly as in 1.3.0, including the
  "Source modifiée" warning on a table whose columns were added or removed.

## 2. Shipped closed (in the code, off unless a server administrator opens them)

| Function | Opened by | Closed means |
|---|---|---|
| Governed agents (`docs/AGENTS.md`): declared agents, runs, proposals, review workflow `MdmFactoryAgentEscalation`, triggers (manual, event, schedule) | `-Debx.factory.agents.enabled=true` | No run (hand, event, schedule), no decision or application of a proposal, switching an agent on refused (`engine.missing.agentsClosed`, HTTP 409). Switching off and reading stay possible. |
| Source drift (`docs/DRIFT.md`): schema changes of a source proposed as `SourceDrift` rows, decided by a steward, applied by a designer | `-Debx.factory.sourceDrift.enabled=true` | The 1.3.0 warning stays; no row written; decide and apply refused (`engine.missing.sourceDriftClosed`). |

Hardening that came with the switches, effective once opened:

- an agent acts for a **server-signed activation** (`POST /agents/{code}/enable`, then `disable`), never for a login typed in a row; **every agent enabled before this
  version must be switched on again**; changing an agent's settings cancels its activation;
- `AgentProposal` is **written by the module only**; its deciding fields are read only for everybody and its values are hidden from simple readers; the review workflow's
  task text no longer asks the reviewer to edit a line (F3-03, F3-06);
- the operations of a drift are **recomputed by the server** from the landing's frozen columns and the catalog's scan; the stored text is display only (F3-01);
- the routes `run`, `enable`, `disable`, `decide` and the drift routes run a second permissions pass on the project, with the caller's own EBX permissions (F3-12, F3-11).

### What is missing before a customer opens them

Do not open either function on a customer instance before these are decided (finding numbers refer to `audit-production-2026-11-12/factory-a-securite.md` and
`factory-b-perf-ux-indus.md`; the detail is not repeated here):

- **F3-03** (partly closed): the proposal line can no longer be edited, but its digest and the agent's current autonomy are not rechecked when it is applied.
- **F3-04**: a generic MCP call is checked on one field and run from another; the path does not work as written.
- **F3-05**: the validation message goes to the model whatever the sharing level; the "sample" consent also sends other fields of master records.
- **F3-07**: in `ACT` autonomy the only barrier before a write or a merge is the confidence the model declares.
- **F3-101**: a scheduled or event agent asks the model again about the same items at every run, with no spending ceiling.
- **F3-104**: the review shows primary keys, not the records, and Approve applies every open line.
- **F3-105**: drift has no screen: decide and apply exist only as routes, and the end-of-plan message announces a review step that does not exist.
- Also open and relevant: F3-106 (applying a drift runs in the HTTP request, without reserving the landing) and F3-108 (the agent engine and the application of a drift have
  no offline test beyond their pure parts; the 2026-10/11 test on the RC predates the correction wave and has not been re-run).

## 3. Upgrade in one paragraph

Stop Tomcat, back up, deploy, start (a full restart is required: new tables, a new bean). Check `GET /status`: version, `featureSwitches` both `false`, 33 tables,
`agents.activationKeyPresent: true`. To keep the 1.3.0 perimeter nothing else is needed. The full sequence, the new properties and their defaults, the manual
steps to open the agents, and what a rollback to 1.3.0 with rows in the new tables does according to the code (not repeated) are in `docs/UPGRADE.md` section 12.
