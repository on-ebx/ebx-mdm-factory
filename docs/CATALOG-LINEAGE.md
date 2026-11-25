# Declaring the factory's structure lineage to the data catalog

Written 2026-09-21, for the task "the MDM Factory declares to the sibling Data Catalog module the
STRUCTURE-level lineage of what it builds" (decision recorded at the end of `PLAN.md`). Everything below was
established by reading the source of `~/Dev/ebx-data-catalog` and `~/Dev/ebx-connectors` (read-only,
untouched) and this module's own tables - never run against a live instance this session (no EBX access from
this worktree). Every claim below names the file and line it comes from; anything that could not be
established from code alone is listed in &sect;5.

## 1. How `ebx-connectors` reaches the catalog - the precedent this module follows

**Channel: HTTP POST with EBX Basic authentication, straight to the catalog's own REST endpoint - never a
Java call, never a shared dataset write.** `ebx-connectors` ships an `openlineage` **connector type**
(`ebx-connectors/src/main/java/com/on/ebx/connect/connectors/OpenLineageConnector.java:20-24`, its own class
Javadoc): *"the base URL is the endpoint that accepts `RunEvent`s (`http://host/ebx-data-catalog/rest/v1/openlineage`
for the ON EBX data catalog ... with its authentication (EBX Basic for the catalog)"*. The `emit` operation
(`OpenLineageConnector.java:117-159`) does a plain `POST` of the JSON body through `AbstractRestConnector`/
`HttpJsonClient` (`this.send("POST", "", null, pPayload)`, line 143) - a generic REST call, not an OpenLineage
SDK. The credentials and base URL live in `ebx-connectors`' OWN `Connector` configuration row (type
`openlineage`), entered once through its UI/REST/OpenAPI-import surface, encrypted the same way as every other
connector secret (CLAUDE.md &sect;5's `PasswordTypeEncryption`/reversible-encryption family - not re-verified
this session, out of scope: this module never reads `ebx-connectors`' `Connector.apiKey`/password field for
the CATALOG connector specifically, only for its OWN `llm` connector, see &sect;1.2).

`DeclaredLineage` (`ebx-connectors/src/main/java/com/on/ebx/connect/core/DeclaredLineage.java:20-40`, class
Javadoc) is what actually decides WHEN to send: triggers on `Mapping`/`MappingInput`/`MappingRule` changes
call `mappingChanged` (debounced 2s outside the transaction), a connector's own type/base-URL/activity change
re-declares every mapping that targets it (`ConnectorLineageTrigger.java:11-17`), a startup sweep catches up
everything (`ebx.connectors.lineage.declareOnStartup`, default true), and `declareNow`
(`DeclaredLineage.java:299-368`) is the synchronous path both the REST route (`POST /v1/lineage/declare`) and
the MCP tool `connectors_lineage_declare` call.

**This is entirely a `Mapping`-table mechanism.** Every entry point above resolves through
`ConnectorRepository.mappings()` (`DeclaredLineage.java:157,269,315,335` - `for (final MappingDefinition
mapping : repo.mappings())` / `.get(...)`), i.e. `ebx-connectors`' OWN `Mapping`/`MappingRule` rows. There is
no generic "send an arbitrary lineage event" tool: `connectors_lineage_declare`
(`ebx-connectors/src/main/java/com/on/ebx/connect/ebx/mcp/ConnectorsTools.java:74,368` - the tool's own
description, *"connectors_lineage_declare sends the declared lineage again"*) only re-declares mappings that
already exist. **This module (MDM Factory) has no `Mapping` rows of its own for the landing-to-staging or
staging-to-master crossings** (those are plain EBX-to-EBX table copies done by `ebx.StagingLoader`/
`ebx.MasterLoader`, not `ebx-connectors` flows) - so `connectors_lineage_declare` cannot be reused for two of
this task's three zone crossings. It COULD, in principle, cover the source-table-to-landing-table crossing,
since `ebx.LandingFlows.generate` (`src/main/java/com/on/ebx/factory/ebx/LandingFlows.java:38-46`, class
Javadoc) already creates one root INBOUND `Mapping` per landing table through `connectors_mapping_upsert` -
but see &sect;1.1 for why this module still declares that crossing itself rather than relying on it.

**Conclusion for this task**: the honest, precedented channel is "HTTP POST with EBX Basic authentication,
straight to `/ebx-data-catalog/rest/v1/openlineage`, credentials from a per-module account entered once" -
`ebx.CatalogLineageDeclarer` does exactly that, with the SAME account already configured for every other
outbound call this module makes (`FactorySettings.agentUser`/`agentPassword`, see &sect;1.2) rather than a new
credential of its own - never a new HTTP channel invented, never a write into the catalog's own tables
directly (Data Services or otherwise: the catalog's write surface for structure is `/openlineage`, not its
raw `Asset`/`Column` tables, and this module never touches those).

### 1.1 Why `ebx-connectors`' own automatic declaration is not relied on for source-to-landing

`ebx.LandingFlows.generate` creates the source-to-landing crossing as a real `ebx-connectors` INBOUND
`Mapping`. If `ebx-connectors`' own `openlineage` sink is configured and its `emit.inbound` property is
`true`, that crossing is ALREADY declared automatically, with no help from this module. But:

- `emit.inbound` **defaults to `false`** (`OpenLineageEmitter.java:87`: `"true".equalsIgnoreCase(...
  "emit.inbound" ..., "false")`) - an inbound mapping (`MappingDefinition.Direction.INBOUND`, `out=false` in
  `Settings.accepts`, `OpenLineageEmitter.java:106-123`) is refused unless a person opted in explicitly. This
  module cannot assume that switch was flipped on any given instance, and it was never checked live here.
- Even when it IS declared, it comes from a DIFFERENT job (`OpenLineageEmitter.jobName`, keyed off the
  mapping's own id/label) under `ebx-connectors`' own `job.namespace` (default `ebx-connectors`,
  `OpenLineageEmitter.java:84`) - a separate object this module has no authority over and would have to read
  as data (yet another cross-module coupling) to avoid duplicating.

So `CatalogLineageBuilder` declares source-to-landing itself too, under this module's OWN job namespace
(`mdm-factory`, distinct from `ebx-connectors`), so the factory's declaration is self-contained and correct
regardless of whether `ebx-connectors`' own sink is configured for inbound traffic. If BOTH modules declare
the same crossing, the catalog ends up with two `LineageEdge` rows for the same `(fromColumn, toColumn)` pair
under two different jobs - harmless (both resolve to the SAME assets by qualified name, &sect;2.4) and not
tested live this session.

### 1.2 The precedent this module already follows for `ebx-connectors`' OWN data - reused here

This module already reads `ebx-connectors`' `Connector` table AS DATA (never as a Java dependency) to run its
own LLM calls: `ebx.AgentRuntime.java:36-40,53-56` (class Javadoc): *"loads the `llm` connector of
ebx-connectors as data (never as a Java dependency - see the knowledge base's 'couplage par la donnee'
rule)"* / *"this module has no compile dependency on ebx-connectors"*. `ebx.CatalogLineageDeclarer` follows
the SAME rule for the data catalog: `CatalogReader.dataset` (`ebx.CatalogReader.java:25-31,132-150`, its own
class Javadoc) finds the catalog's dataset by looking it up where its own convention says it lives
(`-Debx.catalog.dataspace`/`-Debx.catalog.dataset`, default `DataCatalog`) and checking it is really the
catalog's own packaged model - no class of `ebx-data-catalog` imported, only a `SchemaLocation` check. This
module's own outbound calls (MCP to `data-model`/`ebx-connectors`/etc.) already run as one shared "agent
account" entered once in `FactorySettings` (`ebx.McpServers.java:20-23,58-72`) - the SAME account
(`agentUser`/`agentPassword`) is reused for the catalog POST, rather than asking for yet another credential.

## 2. The catalog's `/openlineage` ingestion contract, as it really is

Endpoint: `ebx-data-catalog/src/main/java/com/on/ebx/catalog/rest/CatalogResource.java`.

- `GET /openlineage` (`CatalogResource.java:66-83`): a contract probe - `module`, `contractVersion`,
  `features`, `dataspace`/`dataset` (the catalog's own), `datasetState` (`VERIFIED`/`ABSENT`/`FOREIGN` - see
  &sect;2.5), `ready` (`!"FOREIGN".equals(state)`). `CatalogLineageDeclarer` does not call this first (it goes
  straight to `POST`, matching `OpenLineageConnector`'s own `emit` operation which never probes first either)
  - a documented option for a future health-check surface, not exercised this lot.
- `POST /openlineage` (`CatalogResource.java:87-112`): body is one OpenLineage event object OR a JSON array of
  them (`OpenLineageImporter.importEvents`, `ebx-data-catalog/.../OpenLineageImporter.java:91-140`: `if
  (pBody.isArray()) ... else if (pBody.has("events") ...) ... else events.add(pBody)`). **One event = one EBX
  transaction** (`importEvents`, line 121: `ProgrammaticService.createForSession(...).execute(new
  Procedure() { ... importOne(...) ... })`) - a failing event is reported and the OTHERS still go through
  (line 134-137). `?dryRun=true` rolls every transaction back after running it (`DryRunRollback`,
  lines 128-159) - a real dry-run, not a simulation, so it also validates real EBX constraints. `409` when the
  catalog's own dataset is a same-named dataset of ANOTHER model (`FOREIGN`, `CatalogResource.java:104-108`) -
  the one case this endpoint refuses outright.

### 2.1 Event shape accepted

Two OpenLineage event kinds, told apart by `importOne` itself (`OpenLineageImporter.java:170-184`):

- **A `RunEvent`** (`eventType` + `run` present) - what a real execution reports (`OpenLineageEmitter.event`
  in `ebx-connectors`, `.../OpenLineageEmitter.java:165-247`, is the reference shape this module's builder was
  modeled on for field-by-field consistency, never imported as code).
- **A `JobEvent` "declaration"** (`declared = !pEvent.has("run") && !pEvent.has("eventType")`, line 177) - a
  job's WHOLE current lineage, no run attached. This is what `ebx.CatalogLineageDeclarer`/
  `CatalogLineageBuilder` send exclusively - the structure the factory KNOWS, not something that "ran" in the
  OpenLineage sense (`ebx-connectors`' own `OpenLineageEmitter.declaration`, `.../OpenLineageEmitter.java:357-379`,
  is the exact shape reused here). Every event: `eventTime`, `producer`, `schemaURL`
  (`https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/JobEvent`), `job.namespace`/`job.name`
  (`job.name` is REQUIRED - `if (name.isEmpty()) throw new IllegalArgumentException("job.name is required")`,
  line 181-184), `job.facets.documentation.description` (free text, read into `Job.description`, line
  215-216), `job.facets.jobType` (`processingType`/`integration`/`jobType`, read but not enforced against an
  enum - lines 201? not directly read besides `orchestrator` guess from `producer`/`namespace`/`jobType.integration`,
  `orchestrator(...)`, lines 735-746), `inputs[]`/`outputs[]` (dataset objects, `{namespace, name, facets}`).

### 2.2 Dataset/field resolution - the naming that actually decides where an edge attaches

`assetByName` (`OpenLineageImporter.java:446-488`) is the WHOLE resolution rule:

1. Find a `Source` whose `lineageNamespace` equals the event dataset's `namespace`
   (`./lineageNamespace=<namespace>`, line 454). Call it `known` (may be null).
2. Try `qualifiedName` equal to the dataset's `name`, then upper-cased, then lower-cased
   (`for (candidate : {pName, upper, lower})`, lines 455-470), **scoped to `known`'s own `source` field ONLY
   WHEN `known` is non-null** (`" and ./source=" + ... : ""`, line 458 - the `and` clause is dropped entirely
   when no `Source` claimed the namespace). The first hit wins, updates `lastSeen` if the event is newer
   (`newerObservation`, line 462), and is returned.
3. Only if NOTHING matched: a brand-new `Asset` is created, under `known` if it exists, else a NEW auto-created
   `Source` named `OL_<namespace, sanitised>` (lines 400-429, `source(...)`) - `kindFor`/`platformFor`
   (lines 684-712) guess `TABLE`/`FILE`/`TOPIC`/`DASHBOARD` and a platform from the namespace's scheme.

**Consequence that matters for this task**: when the event's `namespace` does NOT match any `Source`'s
`lineageNamespace`, resolution falls back to matching `qualifiedName` GLOBALLY (step 2, no source scoping at
all) - so a table already scanned under the REAL `Source` (e.g. the catalog's own EBX self-scan, or a real
Snowflake scan under `SNOW_DEMO`) is still found and reused, AS LONG AS the `name` this module sends is
BYTE-IDENTICAL to that asset's own `qualifiedName`. Getting the `namespace` "right" only matters for (a)
scoping when two different sources happen to hold the identical qualified name (not expected here) and (b)
avoiding a throwaway `OL_...` `Source` being created for a table THIS module names correctly but that has
never been scanned by anything yet. `ebx.CatalogLineageDeclarer.ebxNamespace` (this module,
`src/main/java/com/on/ebx/factory/ebx/CatalogLineageDeclarer.java`) takes its namespace from
`ebx.LineageNamespace` (setting `FactorySettings.ebxNamespace`, else the system property
`ebx.factory.catalog.ebxNamespace`, else a NON-loopback address left in `ebxBaseUrl` by an older setup, else
`ebx://localhost:8080`, the `ebx-connectors` default - since 2026-09-23 the namespace no longer follows the
address the factory calls itself on, `ebx.LocalEndpoint`) for exactly this reason - correctness of the GRAPH does not depend on it being
exactly right, only on the `name`/`qualifiedName` being exact (see &sect;2.4 for what "exact" means for an
EBX table and &sect;5 for what was not verified about the Snowflake side).

Columns resolve the identical way, scoped to the asset instead of a Source
(`column(...)`, `OpenLineageImporter.java:490-526`): `./asset=<id> and ./name=<candidate>`, same three-case
try, auto-created with a placeholder `description` (`ColumnPlaceholders.DESCRIPTION`) when unseen.

### 2.3 Column-level lineage and the transformation description - where they show

`outputs[].facets.columnLineage.fields.<targetField>.inputFields[]`
(`OpenLineageImporter.java:300-330`), each `{namespace, name, field, transformations:[{type, subtype,
description}]}`. **This is resolved INDEPENDENTLY of the top-level `inputs[]`/`outputs[]` arrays** - the
importer calls `assetByName`/`column` directly on `inField.namespace`/`inField.name`/`inField.field` for EVERY
input field (line 314-316), never checking it against the `inputs[]` list at all. The plain (asset-level, no
column) fallback (`if (!columnLevel) { for (in : inputs) edge(...) }`, lines 331-337) only fires when an
output carries NO `columnLineage` facet whatsoever. **Practical consequence for `CatalogLineageBuilder`**: a
job whose columns come from SEVERAL different source datasets (a canonical/LLM staging entity fed by two
source systems) does not need its `inputs[]` array to be exhaustive - each `inputFields[]` entry names its own
source dataset directly. `CatalogLineageBuilder`'s staging jobs rely on exactly this (see its own class
Javadoc).

The transformation `description` (`inField.transformations[].description`, falling back to the OLDER
`transformationType`/`transformationDescription` top-level fields on the field spec, lines 309-324) is stored
VERBATIM on `LineageEdge.transformation` (`OpenLineageImporter.java:564`) and `type`/`subtype` maps through
`transformationType(...)` (lines 749-758: `IDENTITY`/`AGGREGATION`/`JOIN`/`FILTER`/`TRANSFORMATION`/`UNKNOWN`,
matched by substring on the upper-cased subtype-else-type) onto `LineageEdge.transformationType`. **Where it
shows**: the Attribute view (`GET /lineage/attributes`) and the column-level links
(`GET /lineage/links`) read `LineageEdge` rows directly - `transformation`/`transformationType` are plain
fields on that table (`CatalogPaths.LineageEdge.TRANSFORMATION`/`TRANSFORMATION_TYPE`,
`ebx-data-catalog/.../CatalogPaths.java:295-296`) - not re-verified live which exact UI pane prints the
`transformation` STRING itself (the Journey view groups by asset, not necessarily surfacing edge text; the
Attribute view's own rendering of it was not read this session - see &sect;5).

### 2.4 EBX asset naming - matched EXACTLY against the catalog's own scan

Both `ebx-connectors`' own scanner and its OpenLineage emitter use the IDENTICAL convention, confirmed in two
independent places:

- `EbxCatalog.java:21-31` (class Javadoc, the `catalog` operation that feeds the catalog's structure scan):
  *"Qualified name `<dataspace>/<dataset><tablePath>` (`Companies/Companies/root/Companies`), schema
  `<dataspace>/<dataset>`, name `<tablePath>`, `assetKey = source:qualifiedName`"*.
- `OpenLineageEmitter.ebxName` (`.../OpenLineageEmitter.java:481-487`): `(dataspace.isEmpty() ? dataset :
  dataspace + "/" + dataset) + (table.startsWith("/") ? table : "/" + table)` - byte-for-byte the same shape.

`CatalogLineageBuilder.ebxName` (this module) reproduces this exactly:
`<dataspace>/<dataset>/root/<tableName>` - verified in `CatalogLineageBuilderTest.landingNamingMatchesEbxConnectorsConvention`.
Every value fed into it comes from the factory's OWN stored fields, never recomputed: `Project.landingDataspace`/
`ProjectSource.landingDataset`/`LandingTable.tableName` for the landing crossing, `Project.stagingDataspace`/
`stagingDataset`/the entity name for staging, `Project.masterDataspace`/`masterDataset`/the entity name for
master (`master.MasterModel.tablePath`/`staging.StagingModel.tablePath` both already return `/root/<name>` -
`src/main/java/com/on/ebx/factory/master/MasterModel.java:599-602`,
`src/main/java/com/on/ebx/factory/staging/StagingModel.java:481-485`).

**The source table's own qualified name is never recomputed either.** `LandingTable.sourceObject` is set
DIRECTLY from the catalog's own scanned asset at planning time - `ebx.LandingPlanner.java:164`:
`table.setValue(asset.qualifiedName, FactoryPaths.LandingTable.SOURCE_OBJECT)`, where `asset` came from
`CatalogReader.assets(...)` (`ebx.CatalogReader.java:166-208`), itself reading the catalog's own
`Asset.qualifiedName` field directly (`ebx.CatalogReader.java:185`). So `LandingTable.sourceObject` IS the
catalog's own name for that table, by construction - `CatalogLineageBuilder` only ever carries it through
(`LandingTableInput.sourceObjectQualifiedName`, never rebuilt), which is exactly what closes the loop for
non-EBX sources (Snowflake included) without this module needing to know anything about Snowflake account
names, JDBC namespaces, or `ebx-connectors`' own `namespaceOf`/`jdbcNamespace` logic
(`.../OpenLineageEmitter.java:546-599,601-679`) - deliberately not reimplemented here, see &sect;5.

For the demo project named in the task brief: catalog source `SNOW_DEMO`, schemas
`SNOWFLAKE_LEARNING_DB.ERP_SOURCE`/`.CRM_SOURCE` (memory: `snowflake-companies-demo-sources.md`) - a scanned
table there has `qualifiedName = SNOWFLAKE_LEARNING_DB.ERP_SOURCE.<TABLE>` (schema-qualified with dots,
`CatalogOperations.catalog`, `ebx-connectors/.../CatalogOperations.java:245-260`: `qualified = (schema.isEmpty()
? "" : schema + ".") + name`, `schema = qualifiedSchema(database, TABLE_SCHEMA)` = `database + "." +
schema`). This module never builds that string itself - it is already sitting in `LandingTable.sourceObject`
once the project's tables were planned (`LandingPlanner`), which is why `CatalogLineageBuilder` takes it as a
plain input rather than a source-system-specific naming function.

### 2.5 What happens to an asset the catalog has never scanned

Created on the fly by `assetByName` (`OpenLineageImporter.java:471-487`): `status=ACTIVE`, `description` a
placeholder (`ColumnPlaceholders.DESCRIPTION`), `kind`/`schemaName`/`physicalName` guessed from the dotted
name and the namespace's scheme (&sect;2.2). It is a REAL row from then on - a later real scan of that source
(EBX's own periodic self-scan, or a Snowflake catalog run) would need to resolve to the SAME row to avoid a
duplicate; that resolution is the SCANNER's own job (matching by its own `assetKey`/`qualifiedName`
convention, `EbxCatalog.java`/`CatalogOperations.java`), not something this task's declaration influences
either way - not run live this session, so whether a subsequent real scan cleanly reuses an
OpenLineage-created placeholder asset is unverified (plausible, given both sides agree on `qualifiedName`, but
listed honestly in &sect;5).

### 2.6 Idempotence - re-sending the same declaration

Re-declaring the identical job (same `job.namespace`/`job.name`, same `inputFields`) is a straight no-op past
the first call: `edge(...)` (`OpenLineageImporter.java:533-588`) looks up an EXISTING `LineageEdge` by
`(fromAsset, fromColumn, toAsset, toColumn)` (lines 538-542) and, for a DECLARED event, only overwrites
`job`/`transformationType`/`transformation`/`active` when the existing edge's `origin` is not already
`OPENLINEAGE` or `PLATFORM` (`declarationCanUpdate`, line 559) - a declaration NEVER downgrades an edge a real
run or the platform's own foreign-key scan already produced (CLAUDE.md-style "never silently corrupt a
stronger fact" rule, confirmed by reading the code, not by a live re-send). `lastSeen` is untouched by a
declaration (`current = existing == null || (!pDeclared && newerObservation(...))`, line 557) - only a REAL
run event moves it. Re-running `CatalogLineageBuilder.build` with the SAME input therefore produces the
BYTE-IDENTICAL JSON (checked by `CatalogLineageBuilderTest.idempotentByteIdenticalOutput`), and POSTing it
twice leaves the catalog in the SAME state both times - not run live, but a direct, confident reading of
`edge(...)`'s own logic.

### 2.7 Retraction - what happens when a mapping is later REJECTED

**A declaration is the job's WHOLE current lineage.** `retireEdges` (`OpenLineageImporter.java:368-396`) runs
after every declared event: every `LineageEdge` still `active=true` for that `job` that was NOT touched by
THIS declaration (`pKept`, the set of edge ids the current call actually wrote) is set `active=false` - kept
for history, hidden from the lineage views (comment, line 364-366). So when
`ebx.CatalogLineageDeclarer` re-declares a `staging:<project>:<entity>` job and a mapping that used to be
ACCEPTED is now REJECTED, the corresponding `inputFields[]` entry simply does not appear in the new
declaration (`CatalogLineageBuilder` never sees a REJECTED row at all, see &sect;3) - the edge that used to
represent it is retired automatically by the CATALOG's own logic, with zero delta-tracking needed on the
factory side. This is the real case named in the task brief: `Account.accountType -> Company.legalForm` and
`Account.siren -> Company.vatNo`, both REJECTED - `CatalogLineageBuilderTest.rejectedMappingNeverDeclared`
reproduces exactly this shape (an attribute whose only mappings are absent from the input, because the
EBX-side declarer already filtered them out).

## 3. Only ACCEPTED-or-`APPLIED` rows are ever declared

`ebx.CatalogLineageDeclarer` filters every `AttributeMapping`/`MappingStep` it reads by
`staging.TechnicalKeyRule.isAcceptedLike` (`ACCEPTED` or `APPLIED` only,
`src/main/java/com/on/ebx/factory/staging/TechnicalKeyRule.java:29-32`) BEFORE handing anything to the pure
builder - `CatalogLineageBuilder` itself never sees a `PROPOSED`/`REJECTED` row, so there is no "is this
accepted" branch inside the pure class at all: absence at the input IS the exclusion (documented in both
classes' own Javadoc).

## 4. The two links (task item 5)

**Forward (catalog asset -> factory's Record Lineage screen): not implemented, and not possible through this
contract today.** `Asset.url` DOES exist as a field (`CatalogPaths.Asset.URL`,
`ebx-data-catalog/.../CatalogPaths.java:224`), but `OpenLineageImporter.assetByName`/`asset()`
(`OpenLineageImporter.java:431-488`, read in full) never reads or writes it, on creation OR on update - the
ONLY facet the importer applies to a dataset object is `facets.schema.fields[]` (types), and column-lineage
facets. There is no `url`/`documentation` facet path for a plain `outputs[]`/`inputs[]` dataset object
anywhere in this importer (unlike `job.facets.documentation.description`, which the importer DOES read, lines
215-216 - jobs and datasets are not treated the same way). So: the contract does not allow it, and per the
task brief's own instruction ("if the honest answer is X because Y, stop and report instead of inventing a
channel") this module does not write `Asset.url` through any other channel either (a direct Data Services PUT
to the catalog's own `Asset` table would be a completely different, unrelated coupling this task's brief does
not authorize, and risks clobbering a value a real scan or a person maintains).

**Reverse (factory's Record Lineage screen -> catalog's Journey view of a table): built on this branch
(`fix/lineage-catalog-link`, 2026-09-22).** The Journey view is not a standalone URL-addressable service - it
is a tab (`"lineageJourney"`) of the Asset record's own EBX form
(`ebx-data-catalog/.../ebx/ui/CatalogForm.java:302`: `.tab("lineageJourney", new LineageJourneyPane())`),
rendered by `LineageJourneyPane` (`ebx-data-catalog/.../ebx/ui/LineageJourneyPane.java:12-16`) directly inside
the standard record view - `context.getCurrentRecord()`, no REST parameters of its own beyond what opening
that record already needs. Reaching that Asset's record form is therefore about opening its record view, and
**the base URL originally documented here - the ordinary-looking trailing-primary-key route
`<ebxBaseUrl>/ebx-ui/ui/advanced/data/B<catalogDataspace>/<catalogDataset>/root/Asset/<id>` - is REFUSED by
ebx-ui.** Measured live 2026-09-22 against a real asset (dataspace/dataset `DataCatalog`/`DataCatalog`, id
511, `qualifiedName` `Companies_Staging/Staging/root/Company`): opening that exact URL answers the modal error
`Node /root/Asset/511 not found in current dataset.`, even though the row genuinely exists at that id - the
ebx-ui route simply does not accept a primary key as a trailing path segment. The working form is the same
`predicate` route this codebase already uses for its own landing/staging/master record links (CLAUDE.md
sect9, "to link to a filtered table, use the ebx-ui route's predicate query parameter"):
`<ebxBaseUrl>/ebx-ui/ui/advanced/data/B<catalogDataspace>/<catalogDataset>/root/Asset?predicate=./id%3D<id>`
(the `id` value quoted the same way `RecordLineageReader#recordPredicate` quotes every PK value, regardless
of its declared type). `RecordLineageReader#catalogUrl` builds this via the class's own existing `openUrl`
helper - the same one that builds the landing/staging/master record links - rather than hand-rolling the
route again. Catalog dataspace/dataset default to `Reference`/`DataCatalog`,
`CatalogResource.openLineageInfo`'s own `out.put("dataspace", ...)`/`out.put("dataset", ...)` echoes whatever
`CatalogPaths.DATASPACE`/`DATASET` resolve to on the target instance - read them from a live
`GET /openlineage` call rather than assuming the default. **Still not verified this session**: the exact EBX
query parameter (if any) that selects a specific tab of a multi-tab record form on load, so a deep link
straight into the `lineageJourney` tab specifically (rather than the record's default tab) was not confirmed -
a future branch should check this against a live instance before promising tab-precise navigation.

**Reverse link REMOVED, 2026-09-22 (coordinator review, decision by the product owner, branch
`fix/lineage-drop-catalog-link`).** The two "Open in catalog" links built above (on the STAGING and MASTER
bands of the Record Lineage screen's "The journey" part) are gone: the screen is about ONE record, the link
jumps to the catalog's page of the whole TABLE without saying so, and it answers no question the reader has
at that moment looking at a single record. The forward direction above (catalog asset -> factory) remains the
one considered to keep a meaning; nothing in this section's "not implemented" conclusion about it changes.
Removed end to end: `RecordLineageReader#catalogUrl` and its two call sites, `CatalogReader#findAssetId`,
`RecordLineage.Input#stagingCatalogUrl`/`#masterCatalogUrl` and their JSON emission, the graphic's
`"openInCatalog"` label pass-through, and the band-header link in `lineage-graphic.js`. `CatalogReader.dataset`
itself and the record-level "Open in EBX" links are untouched - only the table-level catalog link goes.

## 5. What could not be established from code alone

- Whether the catalog's own periodic EBX/Snowflake scans cleanly ADOPT an asset this module's declaration
  created as a placeholder (same `qualifiedName`, no live re-scan run this session to confirm - &sect;2.5).
- Which lineage VIEW (Journey/Radial/Attribute) actually prints the `LineageEdge.transformation` free-text
  description on screen, and how it is truncated/formatted there - only that it is stored, and on which field
  (&sect;2.3). The three views' REST payload shapes (`/lineage/neighbors`, `/lineage/links`,
  `/lineage/attributes`) were read from `CatalogResource`'s own route Javadoc, not from a live response.
  Whether an EBX record's actual field-level chain feeds `/lineage/attributes` PER SYSTEM (as its own route
  doc claims - "which system touches which attribute") in a way that visually distinguishes the factory's
  landing/staging/master zones from each other, or lumps them together, was not seen rendered.
- Whether a site's `ebx-connectors` `openlineage` connector, if one already exists, has `ebx.namespace` set to
  something other than the `ebx://host:port` default - this module takes its own namespace from
  `FactorySettings.ebxNamespace` (&sect;2.2, `ebx.LineageNamespace`) and never reads that connector's configuration (deliberately, to
  avoid one more cross-module data coupling for a case with no live evidence either way); §2.2 explains why a
  mismatch here does not break edge attachment, only namespace scoping/`Source` creation for a
  never-yet-scanned table.
- The exact reversible-encryption mechanism `ebx-connectors` uses for a `Connector`'s own stored secret
  (referenced in &sect;1 by analogy with CLAUDE.md &sect;5's `PasswordTypeEncryption` family) - not
  re-verified this session; irrelevant to this task since `ebx.CatalogLineageDeclarer` never reads
  `ebx-connectors`' OWN credentials, only this module's already-existing `FactorySettings.agentPassword`
  (already decrypted by the existing `ebx.McpServers.secret` helper this class reuses).
- Whether `POST /openlineage` enforces any authorization beyond a valid EBX session (e.g. a specific profile)
  - `CatalogResource.openLineage` (`CatalogResource.java:87-112`) does not check `isUserInRole` anywhere in the
  method; unverified whether an underlying servlet filter or the JAX-RS registration itself restricts it
  further. `ebx.CatalogLineageDeclarer` degrades to a `FAILED` `PipelineStep` on any refusal regardless, so an
  unexpected `401`/`403` is reported, never silently swallowed.
- **Nothing in this branch was run against a live EBX instance.** Every claim above is a reading of the two
  sibling repositories' source and this module's own tests; the worked example in &sect;2.4/&sect;6 and the
  test fixtures reproduce the demo project's SHAPE from `PLAN.md`/memory, not a live call's actual JSON.

## 6. One full example declaration (demo project)

For the demo project (`Companies`, source `SNOW_DEMO`, `Company` canonical fed by `Supplier`+`Account`,
`Contact` its own entity - PLAN.md's own running example), the `staging:Companies:Company` job (built by
`CatalogLineageBuilder`, one column shown for brevity) looks like this - a REAL shape produced by
`CatalogLineageBuilderTest`'s own fixture (field names shortened to the task brief's example), not hand-typed:

```json
{
  "eventTime": "2025-09-21T00:00:00.000Z",
  "producer": "https://github.com/on-ebx/ebx-mdm-factory",
  "schemaURL": "https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/JobEvent",
  "job": {
    "namespace": "mdm-factory",
    "name": "staging:Companies:Company",
    "facets": {
      "documentation": { "description": "MDM Factory: staging entity Company built from 2 column mapping(s)." },
      "jobType": { "processingType": "BATCH", "integration": "MDM_FACTORY", "jobType": "TRANSFORMATION" }
    }
  },
  "inputs": [],
  "outputs": [
    {
      "namespace": "ebx://localhost:8080",
      "name": "Companies_Staging/Companies_Staging/root/Company",
      "facets": {
        "columnLineage": {
          "fields": {
            "name": {
              "inputFields": [
                {
                  "namespace": "ebx://localhost:8080",
                  "name": "Companies_Landing/Companies_Landing_ERP/root/Supplier",
                  "field": "name",
                  "transformations": [ { "type": "DIRECT", "subtype": "TRANSFORMATION", "description": "Trim leading/trailing spaces", "masking": false } ]
                },
                {
                  "namespace": "ebx://localhost:8080",
                  "name": "Companies_Landing/Companies_Landing_CRM/root/Account",
                  "field": "accountName",
                  "transformations": [ { "type": "DIRECT", "subtype": "IDENTITY", "masking": false } ]
                }
              ]
            }
          }
        }
      }
    }
  ]
}
```

`legalForm`/`vatNo` (both fed by a REJECTED mapping in the real project) carry NO `inputFields` entry at all -
absent from `fields`, never a "declared then immediately empty" entry (&sect;2.7/&sect;3).

The declarer sends `X-Requested-With: ebx-catalog-client` on every request to the catalog (cookie and Authorization paths alike): the catalog's CSRF guard on `POST /ebx-data-catalog/rest/v1/openlineage` accepts a cookie-only call only until 2026-12-31 (audit D2-15).
