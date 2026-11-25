# MDM Factory — upgrade, migration and rollback

Written 2026-10-06 (audit F-04), section 12 added 2026-11-12 for 1.4.0, from the code as it is: the history of `tools/build_xsd.py` and `DistributionOutbox.xsd`,
`FactoryInstaller`, `docs/VOLUME.md`, and the EBX 6.3 documentation shipped with the instance
(`ebx-manager/www/common/html/onHelp/advanced/...`, version 6.3.0). **Nothing below was exercised on a running
instance by this document's author**: every "EBX does X" is the EBX documentation or an earlier measurement
quoted from the owner's field notes (`CLAUDE.md` section 5), and is marked as such. Read `docs/OPERATIONS.md`
section 7 first; this file is the detail behind it.
**To go from 1.4.0 to 1.4.1, go to section 13.** **To go from 1.3.0 to 1.4.0, go to section 12** (the tag is placed after this text is written; "1.4.0" is the version the notes in `docs/RELEASE-NOTES.md` describe).

## 1. What an upgrade does today

- **There is no schema version and no migration code.** `MdmFactory.xsd` carries no version; `FactoryInstaller`
  creates the dataspace, the dataset and the `DEFAULT` settings row only when they are missing and never rewrites a
  row that exists (`ebx/FactoryInstaller.java`: a row an administrator may have edited "is never touched again"). What the data model looks like after
  an upgrade is exactly what EBX does with the new XSD at the next start.
- **Additive changes are applied by EBX itself at the start** (a new table, a new optional field, a new
  `DistributionOutbox` field): the table appears in the existing dataset without any operation. Seen in practice (owner's field notes, other models, `CLAUDE.md` sections 5 and 6) for a
  table added to a published model and for a field turned into a computed one (the start logs
  `[data model evolution] ... deleted column`); a removed field loses its values.
- **A new build of the module is the only version marker**: since audit F-03 `GET /ebx-mdm-factory/rest/v1/status`
  (administrator) answers `version`, `commit`, `buildTime` and the SHA-256 of the two embedded jars. Note the version
  before an upgrade and compare after it.
- A class loaded by Tomcat never reloads, and `module.xml` and the XSD are read at the start: **every upgrade is a full
  Tomcat restart**, and, per the owner's measurements, stopping Tomcat while EBX is still booting deadlocks it on
  6.3.0-rc2 (`CLAUDE.md` section 10): wait for `/ebx/` to answer first.
- The module also writes into other modules' technical datasets (Match and Merge configuration, `ebx-dma`, the
  publications dataset, `ebx-manager`) with shapes read from bytecode (audit F-06). After an EBX or add-on upgrade,
  run `tools/smoke.sh` and then one project end to end; nothing in the module checks those versions.
- `on-ebx-agent.jar` and `on-ebx-mapping.jar` must come from the Connectors build the Factory build was made with
  (a stale jar gave a `NoSuchMethodError` at the first agent run). They are pinned by SHA-256 in
  `tools/embedded-jars.sha256`; the war carries them in `WEB-INF/lib`. Upgrade Connectors and the Factory together.
- **Wave 12 (agent via the Connectors contract).** The embedded `on-ebx-agent.jar` changed (SHA-256 `135befef...`: `LlmChat`, `AgentLoop(LlmChat, ...)`,
  `ContractLlmChat`); `on-ebx-mapping.jar` did not. Nothing in the data model changed (no key, no field): no migration. Install Connectors
  (contract 1.1.0) first, then the Factory; the agent passes then ask the model through `llm.chat` and the Factory no longer reads or decrypts the
  connection's key for them. **Before the first agent pass, an administrator raises `maxTokens` of the language model connection to at least 4096**
  (1024 when unset): otherwise the passes are refused with `engine.llm.maxTokensTooLow` and the connection test shows it. New JVM properties
  `ebx.factory.agent.maxTokens` and `ebx.factory.agent.turnTimeoutMs` (`docs/OPERATIONS.md` section 3.1). Rollback of the Factory alone is safe
  (an earlier build reads the key itself); a Factory of wave 12 on a Connectors without 1.1.0 falls back to reading the key (one log line says so).

## 2. Primary keys: the only change that destroys data

**In EBX, changing a table's primary key empties that table, in every dataset and dataspace** (EBX documentation,
`references/datamodel_evolutions.html`, "Limitations related to primary key evolutions"; measured and noted in
`CLAUDE.md` section 5): the definition is treated as a new table; type conversion is never done; a foreign key whose
target's key changes restarts from empty; and **the modification is rejected if the table has, or ever had, history**
(the documented workaround is to drop that table's history first). If a key goes back to a definition used in the past,
the documentation says the table then shows the data of that time again (not exercised here).

Primary keys today (`tools/build_xsd.py`, `T(name, ..., [key fields], ...)`; `DistributionOutbox.xsd` separately):

| Table | Primary key |
|---|---|
| `FactorySettings` | `code` |
| `Project` | `code` (every foreign key to a project would restart from empty if this changed) |
| `ReferenceBinding` | `kind` |
| `Favorite` | `userLogin`, `targetType`, `targetKey` |
| `Xref` | `project`, `stagingTable`, `stagingKey` |
| `TransferReject` | `project`, `entity`, `stage`, `sourceSystem`, `sourceId` |
| every other table of `MdmFactory.xsd` | `id` (auto-increment; `IntakeChain.id` a random UUID) |
| `DistributionOutbox.xsd`: `Event` | `id`; `Control` | `project` |

**Every primary-key change in the history** (`git log -p` of `tools/build_xsd.py`, every commit read; the two XSDs
checked line by line):

| Date | Commit | Table | Change | What it cost |
|---|---|---|---|---|
| 2026-09-28 | `2ac10280` | `Xref` | `id` -> (`project`, `stagingTable`, `stagingKey`); the `id` field removed | The old rows have no value for the new key. `docs/VOLUME.md` "Deployment procedure (lot 2a)": **delete every project through the Factory's own deletion screen first, on the old code**, deploy, restart; a surviving old row is unreachable and there is no migration path (delete it, re-run the load). The lineage (`Xref`) of a deleted project is gone; it is rebuilt by reloading the project. |
| 2026-09-28 | `20375c9c` | `TransferReject` | `id` -> (`project`, `entity`, `stage`, `sourceSystem`, `sourceId`) | Same procedure (`CLAUDE.md` section 5: past key changes were handled by deleting every project first); the reject log of a deleted project is gone. |

No other table changed its key. Tables added since (no key change involved): `Favorite` (2026-09-23), `LoadPause`
(2026-10-05), `DistributionOutbox` `Control` (2026-09-28, `d1c9811b`, new table). `DistributionOutbox` `Event` has been `id`
since its creation (2026-09-26, `7c122cc5`).

**Before deploying any build, compare the keys** (a difference is data loss in that table, and in every table that
references it):

```bash
git diff <previous-tag>..<new-tag> -- ebx-mdm-factory/src/main/webapp/WEB-INF/ebx/schemas/ | grep -n 'primaryKeys'
```

An empty answer is the safe case. The release notes of a package are not a substitute: read the diff.

### 2.1 A field type change: `LandingTable.catalogAsset` (audit D-07, 2026-10-06)

Not a key change, but the factory's side of the catalog's natural keys (catalog `docs/UPGRADE.md` sections 2-3): the
catalog's `Asset` lost its integer `id` and is keyed `assetKey = source:qualifiedName`, so `LandingTable.catalogAsset`
went from `xs:int` to `xs:string` (`maxLength` 1000) and holds that key.

- **What EBX does to existing rows at the next start** (EBX documentation `references/datamodel_evolutions.html`,
  "Limitations related to field-level evolutions"; not exercised here): `xs:int` to `xs:string` is a supported
  conversion for a single-valued field, so each value is KEPT as its digits (`51` becomes the text `"51"`); adding
  `maxLength` is a facet change, allowed. Nothing is emptied in the factory.
- **Those values name nothing any more**: the catalog's own D-07 start empties `Asset`, and the rescan creates the
  assets under their keys. `CatalogKeys.plausibleAssetKey` refuses such digits.
- **Reading keeps working at once**: every reader (`WizardData`, `LandingFlows`, `LandingPreview`,
  `KeySourceUniqueness`) goes through `CatalogReader.resolveAssetKey`: the stored key when it names an asset,
  otherwise the key computed from the project source's `catalogSource` and the table's `sourceObject` (primary-key
  lookups only). So after the catalog rescan a table planned before finds its asset (declared key, scan state, read
  recipe, live row count) without any write.
- **Backfill**: the next landing plan of each source (wizard step 3 "Prepare the landing", the automatic re-plan after
  a deduced-scan decision, or `POST /ebx-mdm-factory/rest/v1/sources/{id}/landing/plan`) writes the matched asset's key into
  `catalogAsset` of every table it already had (same source, `qualifiedName` = `sourceObject`); its answer counts them
  in `catalogAssetRepointed` and flags each line with `catalogAssetRepointed: true`. A table whose source object the
  rescan no longer has keeps its old value and reads as "no asset" (as before for a vanished asset).
- **Deploy together with the catalog's D-07 build.** This factory build writes `Asset.assetKey`/`Column.columnKey`
  (a deduced scan) and reads `assetKey`; the previous factory build reads `Asset.id`, which no longer exists
  (the landing plan would fail). Order: install both, restart, run the catalog rescan (catalog `docs/UPGRADE.md`
  section 3.1), then re-plan the landing of each project source.
- **To check live after the install**: wizard step 2, a deduced scan, then on a pending table Confirm, Leave it out and
  Rename + Confirm (the asset moves to `source:newName`, its columns with it; renaming onto an existing table's name is
  refused with a message); a second deduced scan of the same connection creates no second asset; step 3 "Prepare the
  landing" on a project planned before (answer `catalogAssetRepointed` > 0, then 0 on a second run); the key study of a
  table and the data preview (both read the asset's read recipe through the resolved key); the declared-key chips and
  the live row count under each table of step 2.

### 2.2 A new table: `AccessRight` (audit F-02, 2026-10-06)

Additive only: a new table `/root/AccessRight` (primary key `profile`), no field or key of an existing table changes.
EBX adds it to the existing `MdmFactory` dataset at the next start (section 1). It starts empty: nobody below the
administrator has a right until the default roles exist or a row is added (section 7). A rollback to a build before F-02
ignores the table (section 5).

### 2.3 New fields, no key change (audit F-38 and F-30, wave 10)

Additive only; no primary key changes, nothing is emptied. EBX adds the fields to the existing tables at the next start
(section 1), and every existing row stays valid because every new field is optional:

- `TransferReject`: `acknowledged` (boolean, default `false`), `reviewedBy`, `reviewedAt`, `reviewComment`. A row written
  before the start has no value: the screen and every query read it as "not acknowledged" (`COALESCE(acknowledged, false)`),
  so nothing has to be backfilled.
- `IntakeChain`: `startedBy` (the person the chain runs for). Empty on the chains written before; filled on the next ones.
- `PipelineStep.step`: two new enumeration values, `PROJECT_BACKUP` and `PROJECT_RESTORE` (a static enumeration; the
  rows older builds wrote are unchanged).

What changes in behaviour at the first start: the daily retention purge now also deletes acknowledged rejects older than
the retention (`historyRetentionDays`); open rejects are still never purged. The Cockpit's "Rejects" tile counts open
rejects only (not resolved, not acknowledged) and its per-entity table is one aggregate query. A new user service,
`TransferRejectReview`, is registered with the schema extension (it appears in the Actions menu of a `Project` record; the
Cockpit and the wizard link to it). A new REST pair, `GET /projects/{code}/backup` and `POST /projects/{code}/restore`
(administrator), and the Cockpit's "Download the backup" link: see `docs/PROJECT-BACKUP.md`. A rollback to an older build
ignores the new fields (section 5).

## 3. What to back up first

Stop Tomcat for the backup (a consistent copy; EBX documentation: a global backup of the repository is delegated to the
RDBMS).

1. **The database of the EBX repository, with the RDBMS's own tool** (EBX documentation, "Repository backup"). This is
   the only complete backup and the only one that restores a table after a key change.
2. **The module as deployed**: the previous release zip/war (keep every release's `dist/ebx-mdm-factory-<version>.zip`
   and its `SHA256SUMS`), or, with `tools/install.sh`, the whole `<workspace>/ebx-mdm-factory/` tree including
   `target/classes` (`install.sh` replaces it with `rsync --delete`, no copy is kept).
3. **The data, as archives, for a partial restore** (EBX: dataspace *Actions > Export*, whole content, datasets with
   data, permissions and information; the archive is written to `<ebx.repository.directory>/archives/` on the server,
   readable by an administrator only, never cleaned by EBX, and must be protected like the database):
   - dataspace `MdmFactory` (the module's own tables: projects, mappings, decisions, `Xref`, `TransferReject`, the
     pipeline trace, the job queue, favorites, settings);
   - each project's generated dataspaces: `<Project>_Landing`, `<Project>_Staging`, `<Project>_Master` (the suffixes are
     `FactorySettings.landingSuffix/stagingSuffix/masterSuffix`; the per-project `DistributionOutbox` dataset lives in the
     master dataspace, and the Match and Merge metadatasets `<dataset>_<table>_MDS` sit in the same dataspaces).
4. **Configuration that lives outside those dataspaces** and that a dataspace archive does not carry: the DMA models and
   publications (`ebx-dma`), the Match and Merge configuration of each table, the Connectors mappings, the published
   script libraries, the workflow models and publications, the perspective. EBX Staging composes them
   (`docs/DELIVERY.md`, `GET /projects/{code}/delivery/domain`, `tools/deliver.sh export <project> <archive.zip>`), but
   it is meant for configuration, not for data that users update, and it **never deletes on import**. The workflow
   models of the module (six files since 1.4.0, one a template) are in the package (`workflow/*.json`), imported by hand.
5. Before a key change only: export the table to a file you can re-read (Data Services `GET`, or the data-export MCP
   server), because the table is empty after the start.

## 4. Procedure

1. `GET /ebx-mdm-factory/rest/v1/status` as administrator: write down `version`, `commit`, `embeddedJars`, `datasetState`
   and the row counts.
2. Read the key diff (section 2) and the `RELEASE-NOTES.md` of the package. If a key changes, stop here: it is a
   migration, not an upgrade (section 6).
3. Back up (section 3).
4. Stop Tomcat (not while EBX is booting). Deploy the new build: a war (`dist/ebx-mdm-factory-<version>.zip` contains
   it; a deployment of the war on a real server was never exercised) or `EBX_WORKSPACE=<workspace> tools/install.sh`.
5. Start Tomcat. Read `kernel.log` for the lines listed in `OPERATIONS.md` section 7.3.
6. `EBX_AUTH=<admin>:<password> EBX_AUTH_NOROLE=<non-admin>:<password> tools/smoke.sh <base-url>`: it must print no FAIL,
   and the `build version` line must be the new one. Compare the row counts of step 1. On a staging copy, `tools/itest.sh`
   (OPERATIONS section 2.5) also checks the rights per route family and a scratch project from creation to deletion.
7. Import the workflow models only if the release notes say a model changed (`workflow_model_import`, then publish; a
   republication under the same name needs the unpublish first, `CLAUDE.md` section 7).

## 5. Rollback

- **Code only (no key change, nothing else irreversible)**: stop Tomcat, put back the previous war or the previous
  module tree, start, run `tools/smoke.sh`, check `version`. Data written meanwhile in a *new* table or field stays in
  the repository and is ignored by the old code; whether EBX keeps or drops the table of a model that no longer declares
  it was **not established** (not measured).
- **After a key change**: only the RDBMS restore (section 3, item 1) is a safe way back. EBX documents that returning
  to a previous key definition shows the table's data of the time again, but rows written under the new key are not
  part of that view; do not rely on it.
- A deployed upgrade whose start failed before EBX opened (a module that did not load) has changed nothing in the
  repository: put the previous build back.
- **No rollback was ever exercised**, for the module or for the data. Do one rehearsal on a copy of the repository
  before the first production upgrade.

## 6. Design note: a migration ledger (not built)

What audit F-04 asks for, shaped so that a key change stops being "delete every project first":

- **A ledger table** `SchemaMigration` in `MdmFactory.xsd`: `id` (step id, e.g. `2026-10-xref-v2`) as primary key,
  `status` (`RUNNING`, `DONE`, `FAILED`), `startedAt`, `finishedAt`, `appliedBy` (the module version), `detail` (counts,
  error). The current version of the data is the last `DONE` step; `FactorySettings.schemaVersion` is a convenience copy.
- **Ordered, idempotent steps** in one `Migrations` class run by `FactoryInstaller.ensure` right after the dataset exists
  and before any background engine (job queue, distribution dispatcher, intake) starts. A step records `RUNNING`, does its
  work in batches that can resume (it counts what is already converted), then `DONE`. A `FAILED` or `RUNNING` row left by a
  stopped server blocks the engines and says so in `/status` and in `kernel.log`, instead of letting the new code read a
  half-converted table.
- **A downgrade guard**: if the ledger holds a step the running build does not know, the module refuses to start its
  engines (an old build must not run on data a newer one migrated).
- **Key change = expand, copy, switch, contract**, never an in-place key edit:
  1. release N (additive, safe): a new table with the new key (`XrefV2`) next to the old one; the migration step copies
     the old rows into it in batches (`export -> new table -> reimport`, the key of each new row computed from the old
     row's fields), the code writes to both;
  2. release N+1: the code reads and writes only the new table; the ledger shows the copy `DONE`;
  3. release N+2: the old table is removed from the XSD (EBX drops its values, which are now redundant).
  The in-place alternative (export to a file under `archives/`, change the key, the table empties at the start,
  re-import) is a single window with no copy of the data in the repository: only acceptable with a verified RDBMS
  backup, and impossible for a table that has history (EBX refuses it).
- **A CI check** (part of `tools/test_local.sh`): the keys recorded in a committed file (`tools/primary-keys.txt`,
  written by `tools/build_xsd.py`) must equal the generated ones, so a key change cannot be committed without the
  commit saying so.

## 7. What changes for a non-administrator (audit F-02, 2026-10-06)

Before this build every design and run action was the EBX administrator's, and four things were open to ANY
authenticated user who could open the screens: the project explorer, the cockpit (read), the decisions of the proposal
review (accept / reject) and record lineage (and its cross-references REST projection). The new model is deny by
default with three rights (`docs/OPERATIONS.md` section 4.1).

**What a plain authenticated user LOSES** (no Factory role, no "Access rights" row): the explorer, the cockpit, the
proposal review (looking and deciding), record lineage on a master record and `GET /projects/{code}/lineage|xrefs/...`
(now 403). The rows of the `MdmFactory` tables are also hidden to them in the EBX data views and Data Services, even
where a dataspace permission let them read (`FactoryRightAccessRule`). Restore the old reach with one row of
"Access rights": `Everyone -> VIEW` (explorer, cockpit, lineage, read-only review) or `Everyone -> STEWARD` (plus the
review decisions, as before, and the suspect-pair decisions, which are new).

**What a non-administrator GAINS with a role**: VIEW opens the wizard read only (it was administrator-only); STEWARD
decides the suspect pairs of the matching results (administrator-only before); DESIGN runs the whole wizard, the
mapping matrix and the feed designer, the matching and distribution REST routes, and launches the pipeline workflow
(all administrator-only before). The language-model passes follow DESIGN.

**What stays the administrator's**: settings and the connection screen (agent account, LLM connector and limits,
`pipelineLauncherRole`), the table "Access rights", project deletion, step reset, master reset, the arming override,
the delivery check and domain, diagnostics and maintenance routes.

**`pipelineLauncherRole`**: an empty setting meant "administrators only"; it now means "DESIGN holders only"
(administrators included). A role set there keeps working as before for the workflow beans and the intake, and now
also enables "Launch the pipeline" and the intake buttons in the wizard for its members.

**Before switching the build in production**: create the three roles (or decide the "Access rights" rows), grant read
on the dataspace `MdmFactory` to VIEW holders and read-write to STEWARD / DESIGN holders, grant the generated zones
(`-Debx.factory.zonePermissionsFrom` for the new ones), the MCP servers and the workflow launch permission to the
designers (OPERATIONS section 4.1, "EBX permissions to grant as well"). Then run the live test plan of that section.
`tools/smoke.sh` keeps working: its `EBX_AUTH_NOROLE` account must hold no Factory right.

### 7.1 Catalog content follows the catalog's rights (audit F-02, catalog reads, 2026-10-06)

No XSD change, no data migration: a Tomcat restart loads the new classes. What changes is what a Factory user sees of the
data catalog (ebx-data-catalog), now read under their own session (`docs/OPERATIONS.md` section 4.2):
- **Before**: the Factory read the catalog without a session, so since the catalog's wave 7 its screens and REST answers
  showed catalog sources, tables, columns, profiles, deduced tables and data products to any Factory user, whatever their
  catalog right.
- **After**: a Factory user sees in the Factory exactly what the catalog lets them see. Without a catalog right, steps 2
  and 3 and the data product panel show a banner naming the right (CONSUME) and none of the catalog's content; the landing
  plan from the catalog, the onboarding and sample-file jobs, the deduced scan and the panel's Register / Scan are refused
  with that message instead of running; a write the catalog refuses (Confirm / Leave out: STEWARD, Rename and source
  registration: ADMINISTER, data product: OWN, lineage declaration: ADMINISTER) is said with the right it needs.
- **Unchanged**: the EBX administrator (and the system user of the jobs and workflow steps) sees and does everything as
  before; what the Factory already copied into its own tables (planned landing tables and columns) stays visible under the
  Factory rights.

**Before switching the build**: give the catalog rights the Factory roles need, in the catalog's table "Access rights" or
through its default roles - typically `CatalogConsumer` for `FactoryViewer` and `FactoryDesigner` holders, `CatalogSteward`
for whoever confirms deduced tables, `CatalogOwner` for whoever publishes data products, `CatalogAdmin` for whoever registers
sources or declares lineage through REST in the request (section 4.2 of OPERATIONS has the per-action table). Then run the
live test plan of OPERATIONS 4.2. **Rollback**: the previous build reads the catalog without a session again (content shown
to every Factory user); nothing to undo in the data.

## 8. Distribution mappings written before wave 13

Until wave 13 the OUTBOUND mappings of a distribution target were stored without `dataspace` (only `dataset`, e.g. `Master`), and Connectors resolved the dataset name to whichever dataspace it met first -
possibly another project's (`docs/DISTRIBUTION.md`, "The mapping document always names the master dataspace"). After installing this build, re-run Configure on every distribution target of every project
(wizard step 7, or `POST /projects/{code}/distribution/targets/{targetId}/configure`) before the next send; `mappingDocument` then writes the dataspace and the mapping of the same label is updated in
place. A mapping can be checked with `connectors_mapping_get`: its `dataspace` must be the project's master dataspace.

## 9. The distribution send is asynchronous (wave 13, 2026-10-12)

No XSD change, no data migration: a Tomcat restart loads the new classes. What changes:
- A send starts each mapping's `connectors_sync` with `async: true` and follows the Connectors run (`connectors_run_get`), instead of one synchronous call under a long read timeout; a stop request stops the run and ends the send STOPPED;
  a send whose previous Connectors run is still running is refused (`docs/DISTRIBUTION.md` "Following and stopping a send"). **The Connectors of 2026-10-12 accepts `async` for an inbound feed only** and refuses it for a scope send:
  with it the send falls back, by itself, to the synchronous call and the read timeout of `ebx.factory.distribution.perRowMs` / `sendTimeoutMaxMs` still applies. Nothing needs to change on either side, and the asynchronous path
  starts working the day Connectors accepts `async` for an outbound scope and keeps the run state (`execution{}`) for it.
- New route `POST /projects/{code}/distribution/targets/{targetId}/stop` (administrators), new messages `engine.conflict.sendStillRunning`, `engine.external.sendRunTimeout`, `engine.external.pollSendRun` (English and French).
- `DistributionTarget.firstFullSendRunId` now holds the Connectors run ids of a successful first full send (it held the Factory's step id). Existing values are left as they are; the wizard only tests that the field is not empty.
- `DistributionTarget.lastStatus` can read `STOPPED`.
- **Rollback**: the previous build sends synchronously as before; a Connectors run still going when the build is switched is not seen by it - check `connectors_run_get` before sending.
- **Message fix (2026-10-12, same wave, no data change)**: a send stopped on request now reads the Connectors run to its final state, so "Send stopped ... after N row(s) sent; the rows sent keep their outbound state, a 'changed' send resumes after them" carries the real count
  (it said 0 for 13000); the end message of a send separates sent from unchanged ("Sent 0 row(s), 146559 unchanged, across 2 mapping(s), scope=changed"). The send result and each table's detail gain `sent` and `unchanged` next to `rows`. No new bundle key.

## 10. Relation fields are native: master models need a business object (wave 13, 2026-10-12)

No XSD change (`MdmFactory.xsd` and `tools/build_xsd.py` are untouched), no data migration; a Tomcat restart loads the new classes. **Requires Match and Merge 6.3.0.5-RC3 or later** (the
`businessObjectField` of a matching field and the business-object tools). What changes:
- The placeholder/relink workaround is deleted (`mcpgap` package, the route `POST /projects/{code}/matching/relation-fields/relink`, the writer switch `ebx.factory.writer.mameRelationFields`, `docs/RELATION_FIELDS_WORKAROUND.md`).
  A plan that compares a child field is now written as a business-object field (`docs/MATCHING.md` section 33.12).
- **A master model generated before this build has no business object.** Until it has one, configuring the matching of an entity whose accepted plan compares child fields is REFUSED with the coded message
  `engine.missing.businessObject` ("... declares no business object for ..."): nothing is written, there is no fallback to the old route, and entities whose plan has no child field configure as before.
  The simulation of such a plan (Simulate on N pivots) is run without its relation tiers and says which tiers were not simulated and why.
- **The remedy is to run the Master step again** (`POST /projects/{code}/master/generate`, or the wizard's Generate master): even when the model is up to date it declares, for each parent table that has child tables,
  an inverse association per child (a string host field named after the child, `adresses` for `Adresse`), the `businessObjects` extension, one business object `<Parent>BO` and one relationship per child, and
  republishes the model. It is idempotent and additive (nothing existing is changed). Or declare it by hand in the data model assistant (Extensions, Business objects: main table = the parent, one relationship per child
  through an inverse association) and publish; then Configure matching again.
- **Activation changes for any table that belongs to a business object**: the parent AND its child tables are activated through `activate_business_object_matching` (`activate_matching` is refused on a member). Configure the child
  entity too: a business object whose member has no matching configuration broke the after-submission matching of its main table on 6.3.0-rc2.
- **Rollback**: the previous build reads such a master as before, but its relink needs the MCP gap it was written for (a 6.3.0.4 add-on); on 6.3.0.5-RC3 the earlier build cannot write a relation field. The business object stays in the
  model, harmless to a build that does not know it (tables of a business object are still configured by the table tools for a plan without child field - but `activate_matching` is refused on them).
- **Rewriting an already configured business-object table (wave 13b, 2026-10-12).** A second Configure on a table that belongs to a business object no longer calls `generate_matching_policy` over its stored policy (the add-on
  refuses: "already exists"): a missing policy is generated, a different one is patched in place, an identical one is left alone, and a structural change of the decision tree is refused with the coded message
  `engine.conflict.policyRewriteNeedsManualChange` (delete the policy in the Match and Merge administration screen, then Configure again). A member of the object (not its main table) is configured without
  `matchingOnCreation` / `matchingOnUpdate`: the object drives its matching. Nothing to migrate; a configuration written by the previous build is read as it is. Rollback: the previous build fails on the second Configure
  of such a table.

## 11. Browser storage is keyed by the EBX user (wave 13, security)

No XSD change, no data migration, nothing to configure; a Tomcat restart loads the new classes and `userstore.js`. What changes (`docs/OPERATIONS.md` section 4.3):
- Every key a Factory screen keeps in the browser is now `mdmf:<login>:<name>`. **The keys of the earlier builds (`mdmfOrigin`, `mdmfWizardOrigin`, `mdmfScrollAnchor`, `mdmfScrollTop`, `mdmfScrollBlock`, `mdmfChecking`, `mdmfJobNoticesShown`, `mdmxRail`,
  `mdmxMoreOpen`) are purged at the first opening of any Factory screen** in a browser (both storage areas), together with the keys of any other login. Visible effects, once: the filter rail and the "more filters" block of the project explorer
  come back to their defaults (open / folded), and a task that finished and was already announced can be announced once more if it ended less than two hours ago; a Close pressed on the very page open during the upgrade falls back to EBX's plain close.
- The Java API of `FactoryHtml` changed: `originCaptureScript`, `originCaptureDelegatedScript`, `wizardOriginKeepScript` and `originReturnScript` take the login as their first argument (a custom screen calling them must pass `FactoryHtml.userId(session)`).
- **Rollback**: the previous build ignores the new keys (they stay in the browsers until the storage is cleared; nothing in them is sensitive) and writes its old ones again.

## 12. From 1.3.0 to 1.4.0 (production review of 2026-11-12, F3-107)

**Not rehearsed.** Everything below is read from the code of `main` at the 1.4.0 content (`git diff v1.3.0..HEAD`) and from the existing sections of this
guide. No 1.3.0 repository was upgraded to this build by the author of this section, and no rollback was run. Do the upgrade first on a copy of the
repository (section 5 asks the same) and report what differs.

**Installing the package on a server that is not a FastTrack workspace (X3-07).** The war of the package embeds `jackson-core`, `jackson-databind` and
`jackson-annotations` 2.14.2 in `WEB-INF/lib` (a standard ON EBX server `lib/` has no Jackson); on a FastTrack workspace installed with `tools/install.sh` nothing
changes and the server's own Jackson is used. **(1.4.1: Jackson is 2.18.12 and `install.sh` installs it too, section 13.)** Do not put a second copy of Jackson in the server `lib/` of an instance where the war carries it. The ON EBX MCP
servers must be opened first on a new instance (`OPERATIONS.md` section 2.6, prerequisite). Replacing a war that does not embed Jackson by one that does needs no
data step.

### 12.1 The data model: additive only, a restart

Compared table by table in `tools/build_xsd.py` between the tag and `main` (30 tables at `v1.3.0`, 33 now):

- **Three new tables**: `AgentDefinition` (primary key `code`), `AgentProposal` (`id`, auto-increment) and `SourceDrift` (`id`, auto-increment). Empty after the start.
- **Eleven new optional fields on `AgentRun`**: `agent`, `actingFor`, `via`, `inputs`, `decision`, `confidence`, `applied`, `escalated`, `refused`, `escalation`,
  and the association `proposals`. Rows written before read as empty.
- **One new optional field on `FactorySettings`**: `agentActivationKey` (the secret that signs who an agent acts for; hidden from every person, administrators
  included; never logged, returned, backed up or delivered). The first start of 1.4.0 draws it into the existing `DEFAULT` row (32 random bytes) and logs
  `[mdm-factory] agent activation key drawn ...`; it is never rewritten afterwards (rewriting it would cancel every activation).
- No table or field removed, no type changed, **no primary key changed**: nothing is emptied (section 2's check, `git diff v1.3.0..HEAD -- ebx-mdm-factory/src/main/webapp/WEB-INF/ebx/schemas/ | grep primaryKeys`,
  adds only the keys of the three new tables and removes none).
- Access rules of the new tables and fields are set in Java (`FactorySchemaExtensions`) at each start, not in the XSD.
- `module.xml` declares a sixth workflow bean, `AgentDecisionTaskBean`, so **a full Tomcat restart is required** (the XSD and `module.xml` are read at start;
  section 1). No hot redeploy (`docs/OPERATIONS.md` section 7.2, item 5).
- Behaviour that changes with no setting: the module's services appear under one submenu of the Actions menus of business tables; step 1 of the wizard
  shows Centralisation as "Coming soon" and only Consolidation starts a new project (an existing Centralisation project keeps its steps); the Match and Merge
  MCP server is looked up under `match-merge-admin` first, then `match-merge` (`ebx.factory.matchMergeServer` pins one); `Project.style` is read only on a saved project.

### 12.2 New JVM properties and their defaults

| Property | Default | What it does |
|---|---|---|
| `ebx.factory.agents.enabled` | not set = **closed** | Only `true` opens the governed agents (`docs/AGENTS.md`). |
| `ebx.factory.sourceDrift.enabled` | not set = **closed** | Only `true` opens source drift (`docs/DRIFT.md`). Closed, a landing plan behaves as in 1.3.0 (the "Source modifiée" warning for added or removed columns, no `SourceDrift` row, a retyped column alone marks nothing). |
| `ebx.factory.matchMergeServer` | not set | Pins the Match and Merge MCP path segment; unset, the module tries `match-merge-admin`, then `match-merge`. |

Both switches are read at each call; a restart is needed only to set them. A server that sets nothing runs the 1.3.0 perimeter.

### 12.3 Manual steps

1. Stop Tomcat (not during a boot), back up (section 3), deploy the 1.4.0 war or tree, start, wait for `EBX(R) started and initialized`.
2. **Nothing else is required to keep the 1.3.0 perimeter.** The steps below matter only if you open the agents:
3. **Publish the review workflow**: import and publish `workflow/MdmFactoryAgentEscalation.json` (publication name `MdmFactoryAgentEscalation`, like the models
   of `OPERATIONS.md` section 2.3 step 5). Unpublished, an agent that proposes something leaves its lines in `AgentProposal` and says why in `AgentRun.escalation`;
   nobody gets a task.
4. **Register the scheduled task** `com.on.ebx.factory.scheduler.AgentScheduledTask` once in the Task Scheduler (a `/tasks` row, no `userReference`, a
   schedule every 5 minutes; `docs/AGENTS.md`, "Triggers"), only if some agent has the `SCHEDULE` trigger. The scheduler session must be allowed to queue jobs.
5. **Open the switch** (`-Debx.factory.agents.enabled=true`, restart) and **switch every agent on again**: `POST /ebx-mdm-factory/rest/v1/agents/{code}/enable`
   (Factory right DESIGN, write access to the agent's project in EBX). The module signs the caller's login with the agent's settings; an agent row whose
   `enabledBy` is not such a signature, or whose project, kind, entity, zone, autonomy, threshold, maxItems, owner profile, tools or instructions changed after the
   activation, is treated as not switched on (one WARN per start names it). There were no signed activations before 1.4.0, so **every agent already enabled by a
   development build between 1.3.0 and this version is switched off until this is done.** (1.3.0 itself had no agents: a plain 1.3.0 instance has none to switch on.)
6. Give the Factory roles and the EBX permissions of `OPERATIONS.md` section 4.1 to the people who review (owner profile of the agent) and to those who switch on.
7. Language model: the connection's `maxTokens` must be at least 4096 (`OPERATIONS.md` section 3.1). The agents send data to the model as the project's
   data policy says (`docs/LLM-DATA-POLICY.md`); read the open points before enabling them for a customer (`docs/RELEASE-NOTES.md`, "Shipped closed").

### 12.4 Checks after the start

- `kernel.log`: `agent activation key drawn` (first 1.4.0 start only). With the switches closed, one INFO line per start from the first event or tick:
  `agents: closed on this instance (ebx.factory.agents.enabled is not true)`. A WARN `agent <code> is marked enabled but its activation is not signed ...` means
  that agent must be switched on again (12.3 step 5).
- `GET /ebx-mdm-factory/rest/v1/status` (administrator): `featureSwitches.agents.enabled` and `featureSwitches.sourceDrift.enabled` are `false` on a default
  installation and `true` when you opened them; `featureSwitches.agents.activationKeyPresent` must be `true`; `tables` now lists 33 tables, the three new ones at 0;
  `version` / `commit` is the 1.4.0 build.
- Closed, a call to an agent route answers HTTP 409 with `engine.missing.agentsClosed` (a drift decision or application: `engine.missing.sourceDriftClosed`);
  `POST /agents/{code}/disable` keeps working. Both texts name the property.
- `tools/smoke.sh <base-url>` as in section 4 step 6.

### 12.5 What deletion, reset, purge and backup do with the new tables

Deleting a project removes its `SourceDrift`, `AgentStep`, `AgentProposal`, `AgentRun` and `AgentDefinition` rows; a landing reset removes the `SourceDrift` rows of its landing
tables; the daily purge removes decided proposals and decided drifts older than `historyRetentionDays`; the project backup holds `AgentRun`, `AgentStep`, `AgentProposal` and
`SourceDrift`, not `AgentDefinition` (`docs/OPERATIONS.md` section 9, "F3-10 / F3-103"; `docs/PROJECT-BACKUP.md`).

### 12.6 Rolling back to 1.3.0 with rows in the new tables (read from the code, NOT rehearsed)

- **Rows in `AgentDefinition`, `AgentProposal`, `SourceDrift`, the eleven `AgentRun` fields and the settings key.** The 1.3.0 build does not know them: nothing in its code reads,
  writes or deletes them, and its `MdmFactory.xsd` does not declare them. What EBX does at the start with a model that no longer declares a table or a field is **not
  established for tables** (for a removed field this guide's section 1 records that its values are lost, owner's field notes). Assume the worst: export the three tables
  (Data Services `GET`, or the data-export MCP server) and keep the repository backup of section 3 before rolling back; if EBX keeps the rows, they sit unused.
- **No cascade in 1.3.0.** The 1.3.0 project deletion and landing reset do not know the three tables: rows of a project deleted under 1.3.0 stay behind pointing at
  nothing, and EBX deletes a record other rows point to without a word (owner's field notes, section 9); the dataset validation report then lists the dangling
  foreign keys. Delete the projects that have such rows on 1.4.0 first, or delete the rows by hand.
- **The review workflow and the scheduled task point at 1.4.0 classes.** `MdmFactoryAgentEscalation` names the user task `AgentEscalationUserTask` and the script
  task `AgentDecisionTaskBean`, which 1.3.0 does not have; the `AgentScheduledTask` row of the Task Scheduler names a class that disappears. Before the rollback:
  switch every agent off (`POST /agents/{code}/disable`), end the open review instances in the workflow inbox, unpublish the model, and delete the scheduler task. After
  a rollback without that, expect workflow errors on the open reviews and a scheduler error at each tick (expected from the code, not seen).
- **Drift under 1.3.0** is the DRIFTED warning only; a `SourceDrift` row, a `DRIFTED` table whose open change was only recorded in a row, stays `DRIFTED` until its landing
  is prepared again (the same as 1.4.0 closed).
- **Project backups taken on 1.4.0** contain `AgentProposal` and `SourceDrift` sections; whether 1.3.0's restore accepts or ignores them was not checked.
- Re-upgrading later draws a NEW activation key if the field was lost with the rollback: the agents are switched on again then.
- The guide's rule stands: **no rollback was ever exercised.** Rehearse this one on a copy before relying on it; the lines above say what to look for.

## 13. From 1.4.0 to 1.4.1 (dependencies, finding X3-08, 2026-11-12)

**Not rehearsed on a running instance.** What was run: the offline suite on the new jars, `tools/install.sh` on a fake workspace (`tools/install_selftest.sh`), the advisories check
against the live OSV. No 1.4.0 instance was upgraded to this build and no rollback was run; what follows about a server is read from the scripts and from the Tomcat configuration.

**Data and configuration: nothing.** No data model change, no setting, no JVM property added. A Tomcat restart loads the new classes (and the new jars).

**The jars that change.**

| Jar | 1.4.0 | 1.4.1 | Where it is |
|---|---|---|---|
| `jackson-core` | 2.14.2 | 2.18.12 | the war's `WEB-INF/lib` (package) **and, new, the installed module's `WEB-INF/lib` (`tools/install.sh`)** |
| `jackson-databind` | 2.14.2 | 2.18.12 | same |
| `jackson-annotations` | 2.14.2 | 2.18.12 | same |
| `on-ebx-agent.jar`, `on-ebx-mapping.jar` | unchanged | unchanged | pins in `tools/embedded-jars.sha256` did not move (they use only Jackson's stable tree API; the suite exercises them on 2.18.12) |

**What `tools/install.sh` does now (FastTrack workspace).** Until 1.4.0 the installed module had only the two Connectors jars and used the 2.14.2 of the server `lib/`. From 1.4.1 the
source tree's `WEB-INF/lib` holds the five pinned jars (the script fills it: `tools/jackson_jars.sh`) and `rsync -a --delete` makes the workspace copy's `WEB-INF/lib` EXACTLY that:
a jar that is in the workspace copy and not in the source (an older Jackson, anything added by hand) is deleted, and the script names each one before it runs. It then checks the
result (exactly the five pinned names, the pinned SHA-256; `MDMF-INSTALL-4`, exit 5, otherwise), builds against them (`build.sh` puts the module's `WEB-INF/lib` before the server
`lib/`), and amends the installed module's Eclipse `.classpath` itself (`tools/classpath_amend.py`: the three Jackson `lib` entries BEFORE the `/ebx-ps-fasttrack-server` project, whose `lib/` has 2.14.2; older-version entries removed; backup `.classpath.bak-<date>`; none created if the workspace has no `.classpath`). **Refresh the project in Eclipse (F5) after the installation.** If Eclipse is reopened on a workspace whose `.classpath` was not amended it compiles against 2.14.2 and writes "Unresolved compilation problem" classes into `target/classes`: run `install.sh` again, F5, Project > Clean, then start Tomcat. Tomcat loads a web application's own `WEB-INF/lib` before the common class loader (no `delegate` or `<Loader>` in the
FastTrack context files of the demonstration instance, checked 2026-11-12); that the running module then reports 2.18.12 is read from `/status` `embeddedJars` (the files' hashes) and
`readBounds.jacksonVersion` (the version Jackson itself reports): look at both after the first start.
The jars come from `JACKSON_JAR_DIR` (default `~/.local/share/on-ebx-jars/maven`) or from the jar store; they are never downloaded by a script.

**What changes in behaviour: the JSON read bounds.** Since Jackson 2.15 every parser refuses: a nesting depth over 1000, a string over 20,000,000 characters, a number over 1000
characters, a property name over 50,000 (document length: unlimited). The module keeps those values. Nothing the module reads comes near them (OPERATIONS.md "JSON read bounds");
a document beyond them answers `MDMF-JSON-1` (400 on the REST routes that read a body). 2.14.2 read any depth. A **custom client** that sent such a body now gets a 400.

**Rollback.** Install the 1.4.0 build the usual way. For a package: the 1.4.0 war carries the 2.14.2 jars. For `install.sh`: the 1.4.0 script's source `WEB-INF/lib` has the two
Connectors jars only, so its `rsync --delete` removes the three Jackson 2.18.12 jars from the workspace copy and the module is back on the server's 2.14.2 (read from the script, not
run). Both ways need a Tomcat restart. There is no data step in either direction.

**After the start.** `GET /rest/v1/status`: `version` 1.4.1, `embeddedJars` lists the three Jackson 2.18.12 jars (and the two Connectors jars), `readBounds.applied` true with
`maxNestingDepth` 1000 and `maxStringLength` 20000000. `kernel.log` must show no `NoSuchMethodError` or `NoClassDefFoundError` naming `com.fasterxml.jackson` (it would mean a class
of another module is being served a Jackson it was not built for; none was seen: the modules exchange JSON as text, `ConnectorsBridge` passes a `Session` and a `String`).

## 14. From 1.4.1 to 1.4.2 (the remaining October items, 2026-11-12)

**Not rehearsed on a running instance.** Run: the offline suite, one new test per finding. **Data: nothing.** No data model change, no row to migrate. A Tomcat restart loads the new classes.

**New JVM properties (all optional, with defaults).** `ebx.factory.restore.maxBytes` (2147483648) and `ebx.factory.restore.maxRows` (5000000), see OPERATIONS.md section 3.1. Nothing to set for the default. `ebx.factory.zonePermissionsFrom` is not new, but a standard instance now needs it (below).

**What changes in behaviour.**
1. `Project/style` is read only at creation for every person; only the wizard writes it. **Data Services clients must stop sending `style`** when they create a project (the default, Consolidation, applies); `MDMF-STYLE-1` otherwise. A restore of an archive of a Centralisation project answers `MDMF-STYLE-3`.
2. A restore beyond a bound answers `MDMF-RESTORE-1` (the default bounds are wide; a backup that left the volume tables out is far below them).
3. A backup written while the agents are closed has no master values in `AgentProposal` / `AgentRun.inputs` (field `valuesOmitted`).
4. With the agents open, `POST /agents/{code}/run` refuses an agent that is not switched on. With the drift open, a `SourceDrift` row whose project differs from its landing table's is refused.
5. `tools/package.sh` needs `TOMCAT_LIB_DIR` and `EBX_STANDARD_JAR` (`MDMF-PACKAGE-6`).

**Known finding for a standard instance (X3-11), not changed by this release.** A zone created by the factory with no copied permissions refuses the creation of its dataset (`tools/call: Tool invocation failed: `, empty). Put
`-Debx.factory.zonePermissionsFrom=<dataspace created by an administrator>` on the JVM before the first project (OPERATIONS.md section 2.6). The setting applies to new zones only; 1.4.2 deletes the empty zone it has just created in the failing step,
so after setting it and restarting, the step can be run again. A zone that already existed must be deleted (if empty) or given its permissions by hand. **Also**: the MCP prerequisite is a "Server permissions" line for the profile (OPERATIONS.md section 2.3 step 2), not "Global server activation".

**Rollback.** Install the 1.4.1 build the usual way and restart; no data step. Rows created meanwhile are ordinary rows. A `Project` row created by the wizard needs nothing special. The `valuesOmitted` field of a 1.4.2 archive is ignored by 1.4.1.

**After the start.** `GET /rest/v1/status`: `version` 1.4.2 and a `restoreBounds` block.

## 15. From 1.4.2 to 1.4.3 (the default of the zone permissions, X3-11, 2026-11-12)

**Not rehearsed on a running instance by the author of the change** (the owner checks the end-to-end scenario with no setting, and with `none`). Run: the offline suite. **Data: nothing.** No data model change. A Tomcat restart loads the new classes.

**What changes in behaviour (a default).** Without `-Debx.factory.zonePermissionsFrom`, a NEW zone (landing, staging, master and domain dataspaces) copies the permissions of its PARENT dataspace (Reference), as the ON EBX screen and Data Services do. Before, it copied nothing and a standard 6.3.0-gp instance refused the creation of the zone's dataset (`MDMF-ZONE-1`); measured 2026-11-12 on such an instance, with `=Reference` the landing passed 12 steps out of 12. **Existing zones do not change** (the permissions are copied at creation only).

**Who has to do what.**
- Nothing, on a standard instance that was waiting for this: remove the `-Debx.factory.zonePermissionsFrom` you set as a workaround if it named Reference (the default now does the same); a setting that names another dataspace keeps working and wins over the default.
- Whoever wants the 1.4.2 behaviour (zones closed to everyone but their owner `administrator`) sets `-Debx.factory.zonePermissionsFrom=none`.
- **Consequence to check BEFORE the upgrade:** what a role sees and may do on a new zone becomes what it sees and may do on Reference. If Reference grants wide rights (for example read-write to a broad profile), the factory's new zones inherit them; in that case prepare a narrower template dataspace and name it with the property. A key that names no dataspace copies nothing (WARN in the kernel log, `zonePermissions.mode` is `NAMED_MISSING`): it is not replaced by the parent's permissions.
- A zone created by 1.4.2 or before, empty, that refused its dataset: delete it in the ON EBX dataspaces screen and run the step again (the new zone then copies the parent's permissions).

**Where to read it.** `GET /rest/v1/status` has a `zonePermissions` block (`mode` PARENT, NAMED, NAMED_MISSING or NONE, `copiedFrom`, `description`); the step detail has `dataspaceDetail.permissionsCopiedFrom` (`none` when nothing was copied); the kernel log says it once at start.

**Rollback.** Install the 1.4.2 build and restart; no data step. Zones created by 1.4.3 keep the permissions they copied.

**After the start.** `GET /rest/v1/status`: `version` 1.4.3 and `zonePermissions.mode` `PARENT` (or what you set).
