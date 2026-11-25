# The delivery package — research and design

Research plus a design recommendation, written 2026-09-22 on branch `feature/delivery-package-study`
(worktree `ebx-mdm-factory-delivery`), answering the product owner's ask of the same day: "comment ce
qui est défini avec la Factory est mis en place sur un autre environnement ?", his choice of **initiative
1** (a package of artefacts a target imports, no agent on the target), and his two conditions of the
same afternoon: **the import must be authenticated by whatever the server runs (native or SSO)**, and
**use EBX's own Staging feature rather than redevelop one**. **No production code in this document or on
this branch.** A first version of this document, written before the second condition, designed a
Factory-specific zip and importer; it is superseded in full (§9 keeps what survives of it).

**Evidence tags**, the convention of `docs/SOURCE-RELATIONS.md`, `docs/NORMALISATION.md` and
`docs/MASTER-ZONE.md`:

- **READ `path:line`** — read in this worktree's Java source, `tools/build_xsd.py`, the sibling
  `ebx-connectors` repository, or the EBX 6.3.0-dev jars (`javap`, path given).
- **READ (doc)** — the instance's own documentation, `ebx-manager/www/common/html/onHelp/advanced/engine/staging.html`
  and `data_services/dataservices_rest_v1.html` (version `6.3.0-dev:c8de8c5`).
- **READ (live)** — a real call against the running instance in this session. **One write was made and
  undone**: a scratch staging domain `zz_factory_probe` was created, filled with six components, exported,
  its archive dry-run imported back, and the domain deleted (§3.4). Nothing else was written.
- **INFERRED** — reasoned from the READ facts; a design choice, not a fact.

---

## Recommendation (summary)

1. **The delivery package IS an EBX Staging archive; the Factory composes the staging domain and
   nothing else (§3).** EBX 6.3 ships Staging (Administration → Repository management → Staging): domains
   of components, an archive per domain, import with dry run, hash-based idempotence, dependency check,
   a report per component, and a REST API under `/ebx-dataservices/rest/staging/v1` (READ (doc), §2).
   Every artefact the Factory generates is a supported component type (READ (live), §3.1): dataspaces,
   DMA data models and their publications, datasets, data tables (the Factory's own rows, Connectors'
   mappings), and the Match and Merge configuration of a table (`ebx-addon-mame-MatchingConfiguration`).
   Measured end to end on a scratch domain: create → add components → export (42 KB archive) → dry-run
   import back → `IGNORED` (identical) → delete (§3.4).
2. **Authentication is EBX's, on both sides (§4).** The Staging REST API lives on `ebx-dataservices`,
   so it takes whatever the server runs — native login, token, SSO — and import needs an administrator
   (a delegated user can export only; READ (doc)). The Factory adds **no** endpoint that imports
   anything, and stores **no** account for delivery: the domain is created by the person's own session
   (the Cockpit's browser calls the Staging API on the same origin) or by a pipeline's own credentials.
3. **"Without the Factory" still means "without the Factory's agent" (§1).** The landing → staging →
   master transfer, `Xref` and the lineage screen are this module's Java (`ebx/StagingLoader.java:170`,
   `ebx/MasterLoader.java:146`); a target that loads data needs the jar as a runtime. It needs no LLM
   connector, no `suggest*` route, and — new with this design — no MCP account at all for the delivery
   itself.
4. **What the Factory still builds is small and pure (§5):** `DeliveryDomain`, the function from a
   project's rows to the list of staging components, tested without EBX; one read-only route that
   returns it; a guard that refuses `suggest*` on a project delivered from another repository; and a
   pre-check for what Staging cannot verify (a Connectors connector of the same id on the target, the
   secrets to set, the reference datasets). No zip format, no manifest, no importer, no id remapping.
5. **Two limits, measured, to decide on (§6):** a *filtered* table component is a **published custom
   view or advanced filter**, not an ad-hoc predicate (READ `StgFilteredTableService.lookup`), so
   "only this project's rows" needs one published view per Factory table per project, or the domain
   carries the whole tables; and staging data tables imports by primary key, so Connectors mapping ids
   must be reserved to deliveries on the target.
6. **Lot plan (§7): S / M / S.** Compose the domain first (it alone gives a real delivery through the
   standard UI), then the target-side guard and pre-check, then the Cockpit screen.

---

## 1. What a target environment needs, honestly

`ebx/StagingLoader.java:170` and `ebx/MasterLoader.java:146` load landing → staging and staging →
master: Java under the caller's session, one `ProgrammaticService` per batch, with
`transfer/Transformations`, `transfer/TransferPlan`, the `QualityRule` execution and the `Xref` writes.
`PLAN.md` gives the reason ("moteur natif dans la factory, pas un flux Connectors"). The Connectors
flows the Factory generates cover only source → landing.

| On the target | Needed | Never |
|---|---|---|
| Factory module (jar, its `MdmFactory` dataset) | as a runtime: loaders, `Xref`, lineage, Cockpit | — |
| `FactorySettings.agentUser` / `agentPassword` | **not for delivery**; only if a target ever runs `generate`/`configure` itself | an LLM connector, any `suggest*` route |
| Connectors module, with the source connectors created under the same ids and their own credentials | yes, for the landing flows | the source's secrets (never staged, §3.3) |
| `ebx-addon-mame` | yes when a matching configuration is delivered | — |
| Staging feature (`ebx.staging.activated`, default on) | yes | — |

## 2. EBX Staging, as documented and as measured

READ (doc), `engine/staging.html`: *"The staging feature allows you to move repository elements from one
environment to another … data model publications, workflow model publications, dataspaces, etc."*;
domains, archives, import options (**Force archive import**, **Force import of components**, **Save the
staging domain definition**, **Dry run**); *"Importing is not fully transactional and errors might lead
to an unstable repository"*, hence a backup before import; *"Only product versions with the same minor
number are compatible"*; import report per component (`Success` / `Failed` / `Ignored`); *"Staging tries
to avoid writing as much as possible by default"*; **automatic import on startup** from
`<repository>/startup/<folder>/instructions.json`. Add-ons: *"Match and Merge … Matching configuration
… All elements that belong to a given matching configuration (main information, matching policies,
merging policies and replications) are also migrated"*. Data tables: *"default import mode is update or
insert, blocking constraints disabled, by delta, table triggers activated"*.

READ (live), `GET /ebx-dataservices/rest/api/v1/staging/v1` (OpenAPI): `POST /domains`
(`{uniqueName,label,description}`; the name allows only `[A-Za-z0-9._]` and no leading `_`),
`POST /domains/{d}/components` (`{"element":{"typeName","id","context"},"name"}` — read off
`ComponentCreationDTO` in `ebx-dataservices.jar`, `javap -p`), `GET /domains/{d}/components`,
`DELETE /domains/{d}`, `GET /archives/{domain}` (the archive, `application/octet-stream`),
`POST /archives?isDryRun&forceUpdateOnIdentical&forceUpdateOnSameHash&importDefinition&includes&excludes`
(body = the archive), asynchronous `POST /exports` / `POST /imports`, `GET /types`, and element
navigation (`GET /elements`, `POST /elements/selection`). The domain itself is a record of the technical
dataset `Bebx-staging/staging`, table `/root/domains`: `id`, `owner`, `components[]{componentId, type,
customName, elementId, context[]{key,value}}` (READ (live), Data Services `includeMeta`); import and
export reports are `/root/Logs/importedDomains`, `importedComponents`, `exportedDomains`.

The product owner already uses it: domains `FastTrack`, `Example`, `TestCompanies` and `POS`
(37 components: the `PointOfSale` model, publication, permissions, 23 tables, 3 workflow models,
2 perspectives, one matching configuration with its policies — READ (live)).

## 3. The domain the Factory composes

### 3.1 Components, per zone of a project (READ (live) on `zz_factory_probe`, all six accepted)

| Artefact | `typeName` | `id` | `context` |
|---|---|---|---|
| Landing / staging / master dataspace | `Dataspace` | `B<key>` | — |
| Zone data model (DMA) | `Datamodel` | model name | — |
| Its publication | `DatamodelPublication` | publication name | — |
| Dataset | `Dataset` | dataset name | `{"Dataspace":"B<key>"}` |
| Factory rows | `DataTable` | `/root/<Table>` | `{"Dataspace":"BMdmFactory","Dataset":"MdmFactory"}` |
| Connectors flows | `DataTable` | `/root/Mapping`, `/root/MappingInput`, `/root/MappingRule` | the Connectors dataset |
| Matching of a master table | `ebx-addon-mame-MatchingConfiguration` | the configuration's numeric id | — |
| Script library a constraint imports (lot 2, first) | `ScriptSource` | `definition.library.<name>` (e.g. `definition.library.Fasttrack.lib.phone`) | — |

Names of the Factory's generated things are already on its rows: `ProjectSource.landingModel` /
`landingPublication` / `landingDataset`, `Project.landingDataspace` / `stagingDataspace` /
`stagingDataset` / `masterDataspace` / `masterDataset` (READ `tools/build_xsd.py:206-210, 250-252`),
`LandingTable.inboundMapping` (`:271`). The matching configuration id is not stored: it is the
`MatchAndMergeConfiguration` row of the master table in dataset `ebx-addon-mame-configuration`,
resolvable by table path and data model (the same lookup `master/MameDataModelResolution` already does
for `get_matching_config`) — **to measure in lot 1**: whether `GET /elements/ebx-addon-mame-MatchingConfiguration`
lists it with the table path in its `paths`, which would avoid touching the add-on's dataset.

Domain name: `mdm.factory.<projectCode>` (dots are the documented namespace convention); components named
`<zone>_<artefact>` so the archive's folders read (`ds_staging/`, `pub_staging/`, `factory_project.xml`,
`mame7.json` in the probe).

### 3.2 What is deliberately not in the domain

- `FactorySettings` (the account), `AgentRun` / `AgentStep` (the LLM trace, may hold sampled values),
  `Xref`, `TransferReject`, `PipelineStep` (DEV's runs and loads). The **decision** rows are in:
  `Project`, `ProjectSource`, `LandingTable`, `LandingColumn`, `LandingRelation`, `StagingEntity`,
  `StagingAttribute`, `AttributeMapping`, `MappingStep`, `QualityRule`, `CapabilitySuggestion`,
  `MatchPlan`, with every status kept (a `REJECTED` proposal is a decision too), and the instance-level
  `ReferenceBinding` rows the accepted steps use.
- Connectors' `Connector` and `ConnectorProperty` tables: `Connector.apiKey` / `password` are
  `osd:password` fields (READ `Connectors.xsd:317-430`) and a property may be a secret. A connector
  exists on the target under the **same id** with its own credentials (§5, pre-check).
- Perspectives and workflows: the Factory generates none for a Consolidation project (the `MdmFactory`
  perspective is installed by the module at startup).

### 3.3 Secrets

Nothing in the domain carries one: the Factory's rows hold none (`FactorySettings` excluded), the
Connectors tables staged hold none, a matching configuration holds none. The pre-check (§5) lists, per
source connector, the credentials the operator must have set on the target before the first load.

### 3.4 The measurement (READ (live), 2026-09-22 12:12)

`POST /domains` `zz_factory_probe` → 200; six `POST …/components` (Datamodel `Companies_Staging`,
DatamodelPublication `Companies_Staging`, Dataspace `BCompanies_Staging`, Dataset `Staging`, DataTable
`/root/Project` of `MdmFactory`, `ebx-addon-mame-MatchingConfiguration` `7`) → each answered with its
hash and `paths`; `GET /archives/zz_factory_probe` → 200, 42 050 bytes, `_package.json`
(`uniqueName`, `hash`, `componentRefs`, `externalDependencies`, `globalImportOptions`, `stagingInfo`) plus
one folder per component; `POST /archives?isDryRun=true` with that archive → `status: "IGNORED"`
(everything identical on the same repository, as the doc says); `DELETE /domains/zz_factory_probe` → 204.

## 4. Authentication and permissions

- **Staging REST = EBX authentication.** `ebx-dataservices` takes the server's own mechanism (HTTP
  Basic, token, SSO — READ (doc) `restapi_introduction.html`); the probe used Basic. Import requires an
  administrator; a user with delegated staging administration can export and manage domains but *"Cannot
  import a staging archive"* (READ (doc)).
- **The Factory stores nothing for delivery.** Composing the domain is done **as the person**: the
  Cockpit's "Delivery" screen (lot 3) calls `/ebx-dataservices/rest/staging/v1` from the browser on the
  same origin with the person's EBX session; a pipeline calls it with its own credentials. The Factory's
  own route (§5) is read-only and, like every Factory route, runs on the EBX REST Toolkit with EBX
  authentication (READ `rest/FactoryRestApplication.java:8-11`) — it needs no administrator, because it
  reads the project's rows under the caller's rights and writes nothing.
- **On the target**, nothing of the Factory is called to import: the operator (or the startup import,
  `instructions.json`) imports the archive with EBX's screen or REST, authenticated by the target's own
  mechanism. The Factory's `originRepository` guard (§5) is enforced by the module on its own routes.

Rejected: a Factory import route using the stored agent account (the first version of this document).
It would have added an endpoint that writes models and configuration under an account that is not the
caller's, which is exactly what the product owner's first condition excludes.

## 5. What the Factory builds

| Piece | Package | Pure? | Notes |
|---|---|---|---|
| `DeliveryDomain` — rows of a project → ordered list of `{typeName,id,context,name}` | `delivery` | yes | dependencies first (dataspace before dataset before tables); refuses a zone whose generation step is not `DONE` (`PipelineSteps.isDone`) |
| `DeliveryPreCheck` — what Staging does not verify | `delivery` | report pure | connectors referenced by the landing mappings (ids), secrets to set (`Connector.apiKey`/`password` non-empty on the source ⇒ "to set on the target"), `ReferenceBinding` datasets, add-on needed |
| Route `GET /rest/v1/projects/{code}/delivery/domain` | `rest` | — | answers `{domainName, components[], preCheck}`; any authenticated user; writes nothing |
| `Project.originRepository` (new field) | `tools/build_xsd.py` | — | stamped at project creation with this repository's id; every `suggest*` route and the wizard refuse a project whose value differs from the current repository ("delivered from …, suggestions run on its source"); regenerate the XSD, restart |
| Cockpit "Delivery" block (lot 3) | UI | — | shows the component list and the pre-check; buttons **Create/refresh domain** and **Download archive** call the Staging REST from the browser with the person's session; a link to Administration → Staging for import |
| `docs/DELIVERY.md` §8 runbook | docs | — | the operator's steps on the target, including the loads that are never part of an import |

Not built: an exporter, a zip format, a manifest, an importer, a dry run, id remapping, checksums — all
EBX Staging's.

Tests, pure only, as the repository does: `DeliveryDomainTest` on a fixture mirroring the real
`Companies` rows (two sources, three zones, one matching plan) checking order, names and the refusal of an
unpublished zone; `DeliveryPreCheckTest` on connectors with and without credentials and a missing
reference dataset.

## 6. Limits measured, and the choices they force

1. **Filtered tables are views, not predicates.** `StgFilteredTableService.lookup` resolves the element
   id with `TableViewFactory.findAdvancedFilter(CustomViewKey.forFilter(id))` or
   `findCustomViewFromId(id)` and takes the predicate from the view (`ViewCriteria.getFilterExpression`);
   `getList` enumerates `TabularHelper.findPublishedCustomViewsForTable` (READ `javap -c`,
   `ebx-lib.jar`). So a per-project domain needs **one published custom view per Factory table**
   (`./project=<id>`), which no public API creates. Choice for lot 1: the domain stages the **whole**
   Factory tables and the whole Connectors `Mapping` / `MappingInput` / `MappingRule` tables — correct
   for an instance that delivers all its projects together, wrong for a target that must receive one
   project among several. Lot 2 measures whether a published view can be written as data
   (`Bebx-manager` views tables) and, if so, `DeliveryDomain` switches to `FilteredTable` components.
2. **Data tables import by primary key.** A staged row lands under its own id (*update or insert, by
   delta*). Factory ids are auto-increment: on a target that also creates projects locally, ids could
   collide. Rule: **a target that receives deliveries does not create projects** (its Factory is a
   runtime; `originRepository` enforces it for suggestions, and the wizard's "New project" is hidden when
   the instance is marked as a target in `FactorySettings.role`, a new enum `DEVELOPMENT` /
   `TARGET`). Same rule for Connectors mappings.
3. **Matching activation is not measured.** The add-on stages the configuration *"main information,
   matching policies, merging policies and replications"*; whether the per-table metadataset and the
   trigger (`activate_matching`, READ `ebx/MatchingConfigurator.java:348`) come with it is to measure in
   lot 2 on a second Tomcat. If not, the runbook step is "activate in the Match and Merge UI" — never a
   Factory route with a stored account.
4. **Not transactional.** The doc says so; the runbook says "backup, dry run, import".
5. **A library publication is not a staging element (measured 2026-09-22 on the target).** The staging model's
   script constraints import `Fasttrack.lib.phone` / `Fasttrack.lib.iban`; the model publication import failed with
   "Library 'Fasttrack.lib.phone' is not published or does not exist". The `ScriptPublication` type only lists
   message bundles, REST, role mappers and workflows; a library travels as a `ScriptSource` (id
   `definition.library.<name>`, its dependency is only "Dataset:Script sources"), imports fine, and is still
   **unpublished** on the target: the same failure again after the import. EBX computes no library-to-library
   dependency either (`Fasttrack.lib.phone` imports `Fasttrack.lib.validator.regex`). Hence: `delivery.ScriptImports`
   closes the libraries over their `import X as y;` lines (sources read from `ebx-script-source-2/ebx-script-source`,
   `/root/definition`), the domain lists them **first**, and the import runs in two phases — libraries, **publish
   them on the target** (IDE, or the scripting MCP server's `publish_script` with the operator's credentials:
   `tools/deliver.sh import … --publish`), then the rest. The scripting MCP server must be reachable on the target
   (`mcp-servers-administration`, a permission grant the target's administrator makes, never this tool).

## 7. Lot plan

| Lot | Size | Content | Verify live, before trusting |
|---|---|---|---|
| 1 — Compose | S | `DeliveryDomain`, `DeliveryPreCheck`, route `GET …/delivery/domain`, `Project.originRepository`, tests | with curl: create `mdm.factory.Companies` from the route's list, export, dry-run on the same instance → `IGNORED`; that the matching configuration of `Companies_Master/Company` is found by `GET /elements` without reading the add-on's dataset |
| 2 — Target | M | `FactorySettings.role`, the `suggest*` / wizard guard, published-view probe (§6.1), matching-activation probe (§6.3), runbook | a second Tomcat on this machine: import the archive (dry run, then real), `staging/load` and `master/load` there give 303 / 196 rows as on DEV, the record-lineage screen opens on a delivered golden record |
| 3 — Screen | S | Cockpit "Delivery" block: list, pre-check, create/refresh domain, download, link to import | a delivery without curl, by the person's own session, under SSO if the instance has it |

## 8. Runbook (target) — measured end to end on 2026-09-22

Prerequisites on the target, once: the Factory, Connectors and `ebx-addon-mame` modules (same EBX minor version as
the source); `FactorySettings.role = TARGET` (the wizard then refuses local projects); for a delivery holding script
libraries, the **sensitive permissions** of the importing administrator must allow *Scripting → Script library*
(`sensitivePermissions_1.0.xsd`, `scripting/scriptLibraryAllowed`; without it the IDE hides the Library category and
`publish_script` answers ACCESS_DENIED even for an administrator) and the scripting MCP server must be reachable
(Administration → MCP servers) when `--publish` is used; the source connectors created under the same ids with the
target's own credentials (the pre-check names them); the reference datasets the pre-check names.

1. Backup the target repository (EBX's own doc: an import is not transactional).
2. On the source: `tools/deliver.sh domain <code>` (refuses on a pre-check ERROR), then `tools/deliver.sh export <code> <archive.zip>`.
3. On the target: `EBX=<target base URL> EBX_AUTH=<admin login:password> tools/deliver.sh dryrun <archive.zip>` — read the
   report. **Known:** on an empty target the dry run FAILS on the matching configuration alone (its dependency on the
   master publication is checked against the repository, not against the archive) — everything else must be
   `IGNORED`/`SUCCESS`.
4. `tools/deliver.sh import <archive.zip> --publish`: phase 1 imports the script libraries and publishes them through
   the target's scripting MCP, phase 2 imports the rest (`forceUpdateOnSameHash=true`, because the dry run registered
   the archive's hash). A matching configuration refused in phase 2 for the dependency above: rerun the import with
   `includes=matching_<Entity>` (or the same command again — already-imported components are IGNORED).
5. Read the report: every component `SUCCESS` or `IGNORED`; a `FAILED` model publication naming "Library ... is not
   published" means step 4's publication did not run (permission, MCP disabled): publish in the IDE and rerun.
6. Loads, never part of an import: `POST /projects/<code>/landing/load` (needs the connectors and their
   credentials), `staging/load`, `master/load` — the Factory's own routes on the target, the operator's session.
   Measured with landing rows delivered by a separate test-data domain: `staging/load` 303 read / 303 written,
   `master/load` 196 / 196, `Xref` 303 — the same numbers as the source.
7. Matching: the delivered configuration is `ENABLED` on the target without any Factory call
   (`get_matching_activation_status`); `POST /projects/<code>/matching/run` runs it (Java add-on API, no MCP).
8. Record lineage opens on a delivered golden record (`GET /projects/<code>/lineage/<Entity>?key=…`).
9. `POST /projects/<code>/staging/suggest` and every other `suggest*` answer the origin refusal: the project is
   designed on the source, delivered here.

## 9. Kept from the first design

The honest statement of §1 (the target keeps the jar as a runtime); the exclusion list of §3.2; the
"never a load in an import" rule; the "transfers as artefacts" idea (an EBX Script or Connectors
mappings for the closed `transfer/Transformations` vocabulary) as the only way to remove the jar from a
target, not planned.

## 10. Open questions

- **Several projects on one instance, one delivered at a time** (§6.1): views as data, or accept
  whole-table domains and document it.
- **`originRepository`**: the repository id read through `Repository`'s public API is to confirm
  (`StagingInfo.getRepositoryId()` exists internally); fall back to the instance's `ebxBaseUrl`.
- **Connectors mapping ids** on a target that also authors mappings by hand (§6.2).
- **Startup import** (`instructions.json`) as the CI route: worth documenting once lot 2 has run.

## 11. Lot 1 — status (2026-09-22)

Coded on branch `feature/delivery-domain`, `tools/test_local.sh` green (three new pure tests, 704 classes), not
deployed, not seen running. What exists:

- `delivery.DeliveryDomain` (pure): rows → ordered components, one request body each; refusals by name;
  `delivery.DeliveryPreCheck` (pure): connectors to create and secrets to set, reference datasets, add-on;
  `delivery.OriginRule` (pure) with `FactorySettings.instanceId` (UUID, installer) and
  `Project.originRepository` (wizard at creation, installer backfill) — the seven `suggest*` entry points
  call `ebx.ProjectOrigin.refuseIfDelivered` right after their project lookup, so the REST routes and the
  wizard refuse alike.
- `ebx.DeliveryDomainReader` + route `GET /rest/v1/projects/{code}/delivery/domain` (any authenticated
  user, caller's session, no MCP). The matching configuration id is read from
  `ebx-addon-mame/ebx-addon-mame-configuration/root/TableConfiguration` by `dataModel =
  urn:ebx:publication:<Master model>` and `table` (the `GET /elements` probe of sec 3.1 answered 500 for that
  type, so the add-on's own table it is - REST-readable per CLAUDE.md sec 8).
- `tools/deliver.sh domain|export|dryrun|import`: the pipeline route of sec 4, curl against the Staging API
  with the credentials it is given.

To verify live once deployed (XSD changed: full restart): `tools/deliver.sh domain Companies` (expect
14 zone components + 13 Factory tables + 3 Connectors tables + `ebx-addon-mame-MatchingConfiguration 9`,
nothing refused), `export`, `dryrun` → `IGNORED` on the same instance.

### 11.1 Seen running (2026-09-22, 16:22 → 16:35)

- Startup: `[mdm-factory] instance identifier 99c138f5-… written, 1 project(s) stamped as created here`.
- `GET /projects/Companies/delivery/domain`: 32 components (3 dataspaces, 4 models + 4 publications, 4 datasets,
  13 Factory tables, 3 Connectors tables, `ebx-addon-mame-MatchingConfiguration 9`), nothing refused,
  `originRepository` = `instanceId`, pre-check `ok` with 5 INFO items (connector `Snowflake` to create with its
  `apiKey` and `password`, three reference datasets, the add-on).
- `tools/deliver.sh domain Companies`: domain `mdm.factory.Companies` created, 32/32 components added;
  `export`: 184 724 bytes, 217 files, one folder per component.
- `dryrun` of the whole archive: **HTTP 500** before any component is checked
  (`kernel.log`: `IllegalArgumentException: Invalid step [Project[.]: there is no closing bracket` at
  `ServiceSelectionStagingDependency.exists` ← `StgDomainPusher.checkExternalDependencies`). Bisected with
  `includes=`: only `master_model` and `master_publication` fail; every other family answers `IGNORED`.
  Cause: `Companies_Master`'s toolbar buttons (the master-toolbars lot of the same day) passed
  `xpath=/root/Project[./code='Companies']` as a service parameter, and EBX Staging parses every service
  parameter of a toolbar as a `Path`. The same buttons were already dead (`No service with key
  'ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@FactoryCockpit'` at boot); the fix on `main`
  (51f19ea, target `c`, no parameters) removes the predicate. **The pre-check now reads
  `ebx-dma/<model>/root/toolbarNode` of every delivered model and refuses a parameter value holding `[`
  (`TOOLBAR_PREDICATE`, ERROR)**, so the archive is never built on a model Staging cannot import.
- `dryrun` with `excludes=master_model,master_publication`: `IGNORED` (everything else identical).
- **A dry run registers the archive's hash**: the next dry run or import of the same archive answers
  `IGNORED` / "This archive was already imported on …" unless `forceUpdateOnSameHash=true`. The runbook (sec 8)
  must say so: after a dry run, import with `forceUpdateOnSameHash=true`.
- Import log rows of a crashed import stay `STARTED` in `Bebx-staging/staging/root/Logs/importedDomains`.
- **Closed the same evening (main e602994 installed, toolbars rewritten without parameters)**: the pre-check
  reads the toolbars and answers `ok`; the domain refresh adds nothing (32/32 already there); the re-exported
  archive (184 171 bytes) dry-runs with `forceUpdateOnSameHash=true` → `status: IGNORED`, **32 report items, all
  `IGNORED`** — every component of the delivery, master model and its publication included, is accepted by EBX
  Staging and identical to the source. Lot 1 done.

### 11.2 Lot 2 — the target instance (2026-09-22 evening)

- A second EBX instance on this machine: Tomcat base `ebx-ps-fasttrack-server-target` (ports 8081/8444/8006, its own
  copy of `webapps`, `lib` and `compress` symlinked, the module contexts resolved through `${catalina.base}/..` to
  the same workspace modules), EBX home `ebx-ps-fasttrack-home-target/home` (`ebx.persistence.factory=h2.standalone`,
  repository id `00D89900E1E1`, label `Target`), started by its `start.sh` through the space-free symlink `~/Dev/ft63`
  (catalina.sh word-splits `CATALINA_OPTS`: a path with a space became the main class "6.3.0.FT"). Up in 36 s.
- Dry run of the whole archive on the target: **FAILED before any component** — the matching configuration's
  dependency on `urn:ebx:publication:Companies_Master` is checked against the *repository*, not against the archive,
  so a dry run on an empty target always fails on it. Import in two calls: `excludes=matching_Company` then
  `includes=matching_Company`.
- Real import without matching: 29 SUCCESS, 2 FAILED (`staging_publication`, `staging_dataset` — the libraries, sec
  6.5), 1 IGNORED; then matching alone: SUCCESS. On the target: the project's rows (75 attributes, 69 mappings…),
  both landing datasets **empty** (a `Dataset` element carries the dataset, never its rows — data is not configuration; a `DataTable` element would), the master dataset, the matching configuration,
  100 Connectors mappings (whole table — sec 6.1).
- Libraries imported as `ScriptSource` (3 SUCCESS), the staging publication still refused: not published. Publishing
  needs the scripting MCP server enabled on the target — a permission grant left to the administrator.
- On the target, `POST /projects/Companies/staging/suggest` answers the refusal of `OriginRule` ("delivered from
  instance 99c138f5-…"): the guard works with a project whose rows arrived by Staging. The target's own
  `delivery/domain` route reads the project as undelivered zones (the `PipelineStep` ledger is deliberately not
  delivered) — a target never re-delivers, so this is expected.
- Landing rows for the loads test came through a separate scratch domain of `DataTable` elements
  (`mdm.factory.Companies.testdata`), never part of a real delivery.
- **Libraries published, staging zone delivered, loads and matching run on the target (22:05–22:20)**: after the
  administrator granted *Scripting → Script library* in the sensitive permissions, `publish_script` answered
  "Script was published" for the three libraries; the staging publication and dataset imported (`SUCCESS`, model
  warnings only); `staging/load` 303/303 in 1.6 s, `master/load` 196/196 in 1.0 s, `Xref` 303 rows; the matching
  configuration reads `ENABLED`; the record-lineage route answers for master key 1 (one CRM source, six fields).
  **Lot 2 done** except the published-view probe (sec 6.1, still whole tables) and the Cockpit screen (lot 3).
- `POST /projects/Companies/matching/run` on the target: `Company` DONE in 124 ms (completion 100 %), the per-table
  metadataset created by the run (`metadatasetExists: true`), 52 `RecordMetadata` rows of which 2 with suspects —
  the delivered configuration runs as on the source. (`getDetail()` NullPointerException after completion: the known
  add-on behaviour, CLAUDE.md sec 8, reported as a note.)
