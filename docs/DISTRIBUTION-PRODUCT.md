# Distribution step, "Data product" (SPEC-distribution.md &sect;7c) — lot 2

Publishes a project's golden records as a Data Catalog data product with an output port on Snowflake.
Branch `feature/distribution-product`, from `main` `f969ea4`. No wizard screen in this lot (that is lot 3,
after lots 1 and 2 are merged) — this is the write/read engine and its REST routes only.

**Revised 2026-09-24 after coordinator review** (three corrections to the first version of this lot — see
"Coordinator review" at the end for exactly what changed and why): the cross-references table is now a
second Connectors mapping on the master table itself, not a doomed attempt to publish the Factory's own
shared `Xref` table; refresh only sets `OutputPort.maxLagMinutes`/`autoPublish` (the data catalog's own SLA
sweep does the republishing) instead of this lot writing its own Scheduler REST schedule; and the
`OutputPort` lookup/update goes through its `code` field via an XPath predicate, never
`PrimaryKey.parseString(code)` (its real primary key is the auto-increment `id`).

Two new classes:

- `com.on.ebx.factory.distribution.product.ProductPlan` — pure planner, no EBX API, unit-tested directly
  (`ProductPlanTest`, 15 cases). Turns a project's master tables, their child relations, cross-references
  and the mockup board's choices (`DataProduct.dc.html`) into: the tables the product will carry (published
  name, own table vs. nested-as-JSON-column), a contract preview, "what EBX will create", and every
  validation problem.
- `com.on.ebx.factory.ebx.DataProductPublication` — EBX-bound: resolves candidates from the project's
  master model and the data catalog's asset list; `detect` (warehouse reachability + default plan); `plan`
  (server-side re-run, no write, the `?dryRun=true` route); `create` (writes).

REST (`rest/FactoryResource.java`, admin-only like their neighbours):

- `GET /projects/{code}/distribution/product` — detection + candidates + default draft.
- `POST /projects/{code}/distribution/product?dryRun=true` — plans/validates the posted draft, no write.
- `POST /projects/{code}/distribution/product` — writes (idempotent).

## Why no compile dependency on ebx-data-catalog or ebx-connectors' domain classes

Same discipline `ebx.CatalogReader` already established (its own Javadoc: "no class of that module is
used, only its dataset"): the Factory module's build (`tools/build.sh`) compiles against `server/lib/*`
plus two SCOPED jars vendored from ebx-connectors (`on-ebx-agent.jar`: the MCP client;
`on-ebx-mapping.jar`: the mapping engine `MappingEngine`/`MappingDefinition`/`Transformations`/`Json` used
by `StagingLoader`) — neither ships `ProductPublisher`, `SnowflakeConnector`, `Connector`, or
ebx-data-catalog's `ProductWizard`/`CatalogPaths`/`OutputPorts`/`PortCoverage` classes. `DataProductPublication`
therefore:

- Reads/writes the catalogue's `DataProduct`/`OutputPort`/`Asset`/`Source`/`Column` tables **as data**
  (literal `Path.parse("./...")` constants mirroring `CatalogPaths`, read in the sibling repository
  2026-09-24 but never imported), inside one `Procedure` running as the caller's own session — the same
  create/update shape `ebx-data-catalog`'s own `ProductWizard.create` uses (read there for reference), not
  a merge/replace.
- Reaches ebx-connectors through the in-JVM bridge (`McpServers.connectors`, the same seam every other
  Factory class uses) and its **existing** MCP tools: `connectors_list`/`connectors_call` (the connectivity
  probe), `connectors_mapping_upsert` (one call per publishable table, nested children as sub-object
  mappings of the SAME call), `connectors_publish` (the first run). `connectors_publish` already implements
  exactly `ebx-connectors`' `core.ProductPublisher` end to end (golden filter via `mergedRecords=exclude`,
  crosswalk via `crosswalk=true`/`golden()`/`mdmState()`), so this lot never re-implements any of that logic
  — it only builds the mapping documents and calls the tool.

## Cross-references: a second mapping on the master table, not the shared Xref table

`FactoryPaths.XREF` (`/root/Xref`) is one table shared by every project (rows carry their own
`project`/`masterTable`), and an OUTBOUND root Connectors mapping has no row-level predicate
(`EbxRecordSource.rows` in ebx-connectors: `table.createRequest()`, no `setXPathFilter`, unlike a CHILD
mapping's own `childFilter`) — so publishing `Xref` directly would leak every other project's rows. The
first version of this lot tried exactly that and stopped short of building the mapping (flagged
`mappingBlocked`). The coordinator's fix, now implemented:

- A master table already keeps **one row per source record**, together with its own `sourceSystem`/
  `sourceId` pass-through fields (`MasterFieldNameRule.RESERVED` — every master table carries them,
  unrenamed) and its Match and Merge state. It is therefore per-project and per-source-record BY
  CONSTRUCTION — no separate table is needed at all.
- `ProductPlan.Candidate` for a cross-references row (`source = SOURCE_CROSSWALK`) now carries the SAME
  `dataspace`/`dataset`/`tablePath` as its host master entity (`parentKey`), never `/root/Xref`.
- `DataProductPublication.writeMappings` builds it as a SECOND root `connectors_mapping_upsert` on that
  SAME EBX table, with:
  - input `crosswalk="true"` (implies `mergedRecords=include` — every source record, not golden-only, per
    `ProductPublisher.mergedRecordsMode`'s own default);
  - rule `GOLDEN_ID`: `sourcePath=./id`, `transformation=golden(<tablePath>,<dataspace>,<dataset>)`
    (`core.GoldenRecords`/`MameGoldenRecords`: resolves the record's own key to its golden record's key,
    following a MERGED chain, or itself when not merged);
  - rule `MDM_STATE`: `sourcePath=./id`, `transformation=mdmstate(<tablePath>,<dataspace>,<dataset>)`
    (its Match and Merge state name);
  - rules `SOURCE_SYSTEM`/`SOURCE_ID`: plain `sourcePath=./sourceSystem` / `./sourceId`.
- The data catalog's own `PortCoverage` already has a dedicated rule for exactly this shape: a mapping
  whose `crosswalk` input is `true` (read from its `<mappingId>|crosswalk` `MappingInput` row) is excluded
  from the "published twice" check in both directions (it targets the SAME EBX table as the master
  mapping) but still counts as covering that table's asset — so no separate `DataProduct.ASSETS` entry is
  needed for it; `create`'s own asset resolution naturally resolves the crosswalk candidate to the SAME
  asset id as its host master table and the de-duplicated `assets` list only lists it once.
- `ProductPlan.plan` refuses a cross-references table whose host master entity is not itself included
  (`distribution.product.error.parentNotIncluded`), the same rule a nested child already had.

This removes the `mappingBlocked` concept entirely: every planned table this lot offers is now buildable.

## Refresh: the catalogue's own SLA sweep, not a schedule this lot creates

The first version of this lot wrote its own Scheduler REST schedule and a workflow-launcher row. The
coordinator pointed out that `ebx-data-catalog` already ships exactly this mechanism (lot 26,
`PortSlaTask`/`PortSla`): a scheduled sweep that republishes any `OutputPort` with `autoPublish=true` once
its age nears `maxLagMinutes` (a configurable lead percent before the port is actually late, with a retry
gap so it never launches a storm of publications — see `PortSla.decide`, read in
`ebx-data-catalog/.../ebx/PortSla.java`, not imported).

- `ProductPlan.Draft.refreshMode` is one of `ON_DEMAND` (default), `HOURLY`, `DAILY`, `WEEKLY` — the exact
  same literal values `ebx-data-catalog`'s own `ProductWizard.REFRESH` enum already uses for
  `DataProduct.refreshFrequency`, so no translation is needed when writing that field.
- `ProductPlan.maxLagMinutes(refreshMode)` maps them to minutes (60/1440/10080) or `null` for `ON_DEMAND`.
- `DataProductPublication.writeCatalogue` sets `OutputPort.maxLagMinutes` and `OutputPort.autoPublish`
  (`true` iff a lag was chosen) — nothing else. No schedule, no workflow launcher, no Scheduler REST call
  anywhere in this lot any more.
- **The actual republish cadence is NOT exactly `maxLagMinutes`**: it depends on `PortSlaTask`'s own sweep
  interval (however often ITS OWN schedule runs on this instance) and its lead percent/retry-minutes
  properties (`PortSla.DEFAULT_LEAD_PERCENT=10`, `DEFAULT_RETRY_MINUTES=60` unless configured otherwise).
  A port becomes "due" once its age reaches `maxLagMinutes * (100 - leadPercent) / 100`, so a product asking
  for `DAILY` (1440 minutes) becomes due at roughly the 21.6-hour mark with the default 10% lead — but only
  actually republishes at the NEXT sweep tick, whatever that task's own schedule is. **Not verified live**:
  whether a `PortSlaTask` instance is even registered/scheduled on the target instance, and at what
  interval. `ProductPlan.plan` adds a `warnings` line saying exactly this whenever a non-`ON_DEMAND` refresh
  is chosen, so the screen (lot 3) can show it rather than implying an exact cadence.

## Marketplace listing

The mockup's "List it on Snowflake's internal marketplace" checkbox does **not** need a new write from this
lot: `SnowflakeConnector` already implements Snowflake listings entirely on its own, gated by the
CONNECTOR-level property `listing.enabled` (`listing.supportContact`/`listing.region`), applied automatically
during `ProductPublisher`'s `annotate()` step of a real publish. There is no catalogue field to carry a
per-product "list it" flag today. `DataProductPublication.create` records the person's request as a
`DataProduct.tags` entry (`MARKETPLACE_LISTING_REQUESTED`) purely for visibility — it does **not** and
cannot toggle the connector's own `listing.enabled` property (that is instance-wide configuration, not
per-product). Documented as a deviation from the mockup's implied "it configures the listing" — the real
control is the connector's own setting, already in place before this lot runs.

## Row counts approximation

`GoldenRecordsReader.read` is reused for the candidate row counts (its `rowsByEntity` map): "every promoted
entity's own total row count (every state but DELETED)", **not** filtered to GOLDEN-only. When Match and
Merge has never run on a table (`matchingAvailable=false`), this is simply the table's row count. A note is
added to the detect/plan response (`candidateNotes`) whenever this fallback is in effect, so the screen can
show "N records" instead of implying "N golden records" when the distinction matters. This is unchanged by
the coordinator review, and applies to a cross-references candidate's row count too (its true row count
after `crosswalk=true`/`mergedRecords=include` may differ from the master table's own golden-filtered
count — both currently show the same approximate number).

## Idempotency

- `DataProduct` is looked up by its real primary key (`code`) and updated in place
  (`ProcedureContext.getContext`/`doModifyContent`) rather than duplicated.
- `OutputPort`'s primary key is the auto-increment `id`, NOT `code` (`ebx-data-catalog/tools/build_xsd.py`:
  `T('OutputPort', ..., ['id'], [F('id', 'id', ...), F('code', ...), ...])`) — coordinator-caught bug in the
  first version of this lot, which called `PrimaryKey.parseString(portCode)` on it. Fixed:
  `DataProductPublication.findPortByCode` looks it up with an XPath predicate (`./code='<code>.<TARGET>'`)
  and updates the row found that way; a create never sets the `id` field, letting EBX auto-increment it,
  the same convention already used elsewhere in the Factory for an `id`-keyed table (`AgentRuntime`,
  `PipelineStep`).
- `connectors_mapping_upsert` is idempotent on its own `label` (`"Data product <code> - <publishedName>"`),
  same as every other Factory class that writes a Connectors mapping (`LandingFlows`).

## What was NOT verified live (task constraint: no EBX write tool was used this session)

Every write path below was written against the sibling repositories' own source and the platform's
documented contracts, never exercised against a running instance. Verification steps for the next session
with write access:

1. **`DataProduct`'s primary key** is confirmed `code` by reading `ebx-data-catalog/tools/build_xsd.py`
   directly (`T('DataProduct', ..., ['code'], ...)`) — not re-derived from a live `describe_table` call.
   Cheap to double-check with `describe_table` (data-model MCP, read-only) on `/root/DataProduct` before
   the first real `create` call, since the two modules share no compile-time contract.
2. **`OutputPort`'s primary key** is confirmed `id` the same way (`build_xsd.py` line ~673) — the fix
   above (`findPortByCode`) is written against that reading, not measured against a live catalogue.
3. **Asset resolution.** `findAssetId` looks up `Asset` by `schemaName='<dataspace>/<dataset>'` and
   `physicalName='<tablePath>'` — the same convention `ebx-data-catalog`'s own `PortCoverage.members` uses
   (read there). Verify a project's master tables actually get catalogued this way by the catalog's EBX
   self-scan (they must be, for `create` to find an asset id at all — `create` reports the missing ones as
   a plan problem rather than guessing, so this fails LOUD, not silently, if the assumption is wrong).
4. **Cross-references mapping shape.** The `golden(...)`/`mdmstate(...)` transformation syntax is read
   directly from `Transformations.java`'s own registration comments
   (`golden(/root/Table[,dataspace,dataset])`) — never exercised against a live master table under Match
   and Merge. Verify with `connectors_mapping_upsert`'s own `dryRun=true` (already what `writeMappings`
   does before the real upsert) and inspect the returned plan; then a real `connectors_sync`/`dryRun`
   publish and check `GOLDEN_ID`/`MDM_STATE` resolve to sensible values for a MERGED record.
5. **Mapping shape for a nested child.** `writeMappings` builds a `children[]` sub-object exactly per
   `connectors_mapping_upsert`'s own tool description (`tablePath`, `label`, `collectionPath`,
   `parentLinkField`, `rules`) — never exercised against a live mapping. Verify with a `dryRun=true` first
   call (already what `writeMappings` itself does before the real upsert) and inspect the returned plan.
6. **`connectors_publish` mapping list.** Passed as a comma-separated list of the ROOT mapping ids just
   created — matches the tool's own description ("mappings is a comma-separated list of ids or labels").
   Verify the first real publish's report (`firstPublication.report` in the `create` response) names every
   table (master AND cross-references) with a non-zero row count.
7. **Snowflake connectivity probe** (`detectWarehouses`): `connectors_call` with `operation=query`,
   `inputs={sql:"select 1"}` — `query` is a real, documented operation of `SnowflakeConnector`
   (`Operation.search("query", ..., "sql")`, read in `ebx-connectors/.../SnowflakeConnector.java`), but the
   probe itself was never run against a live Snowflake connection this session.
8. **`PortSlaTask` cadence** (see "Refresh" above): whether it is registered/scheduled at all on the
   target instance, and at what interval/lead/retry — this lot only sets `maxLagMinutes`/`autoPublish` and
   trusts that sweep to exist; it does not check for it or create it.

## What IS verified

- `ProductPlan` compiles and its full test suite (`ProductPlanTest`, 15 cases: default draft, nested child,
  every validation rule, the cross-references table sharing its host master's EBX table and never blocked,
  a cross-references table refused when its host master is excluded, added catalogue assets, the two
  refresh-mode cases) passes standalone (`javac`/`java`, no EBX classes involved).
- The whole module (`ebx-mdm-factory/build.sh`) compiles clean against the real `ebx-lib.jar` and the
  vendored `on-ebx-agent.jar`/`on-ebx-mapping.jar` with `DataProductPublication` and the two new REST routes
  in place — every `ProcedureContext`/`ValueContextForUpdate`/`AdaptationTable`/`Path` call resolves against
  the real EBX API (not just against the module's own mocked classes).
- `tools/test_local.sh` is green (see the repository's own test run for this lot).

## Deviations from the spec/mockup

- **Marketplace listing** is recorded as a `DataProduct` tag only, never toggles the connector's own
  `listing.enabled` (see above) — that property already governs the real listing, per-connector, not
  per-product.
- **"More settings: continue in the Data product wizard"** (the mockup's link to `ebx-data-catalog`'s own
  `ProductWizard`) is not built — no change in the catalogue module was made or is needed for lot 2, and
  pre-filling that wizard from a Factory-created product is explicitly a separate TODO on the catalogue side
  per the spec text itself.
- **Refresh mode**: `ProductPublisher`/`connectors_publish` is v1, full-reload only (its own Javadoc: "v1:
  full reload only, Snowflake only") — there is no "changed" scope for a data-product publish today, unlike
  the distribution TARGETS of lot 1. `ProductPlan.Draft.refreshMode` therefore only controls the SLA sweep's
  `maxLagMinutes`/`autoPublish`, never an incremental scope.

## Coordinator review (2026-09-24) — what changed and why

1. **Cross-references** no longer target the shared `/root/Xref` table (see the dedicated section above):
   replaced by a second root mapping on the master table with `crosswalk=true` and `golden()`/`mdmState()`
   transformations. `ProductPlan.PlannedTable.mappingBlocked` and the corresponding warning were removed
   entirely (nothing is blocked any more); `ProductPlanTest` gained `crosswalkSharesHostMasterTableAndIsNotBlocked`
   and `crosswalkRefusedWhenHostMasterExcluded` in place of the old `crosswalkIncludedButMappingBlocked`.
2. **Refresh** no longer writes a Scheduler REST schedule or a workflow-launcher row: `writeSchedule`,
   `httpCall`, `launcherBody`, `scheduleBody` and the `HttpCall` class were deleted from
   `DataProductPublication` entirely, along with the `java.net`/`java.io`/Base64 imports they needed.
   `ProductPlan.Draft.refreshCron`/`refreshLabel` became `refreshMode` (`ON_DEMAND`/`HOURLY`/`DAILY`/
   `WEEKLY`) and the new `ProductPlan.maxLagMinutes` pure function; `ProductPlanTest` gained
   `onDemandRefreshHasNoWarningNoMaxLag` and `scheduledRefreshWarnsAboutSlaSweepCadence`.
3. **`OutputPort` primary key**: fixed from an assumed `code` to the real `id` (`findPortByCode`, an XPath
   lookup on `./code`, replaces every `PrimaryKey.parseString(portCode)` call against that table).
   `DataProduct`'s own primary key (`code`) was already correct and is unchanged.
