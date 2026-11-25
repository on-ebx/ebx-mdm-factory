# Volume scalability

Written 2026-09-28 on branch `fix/volume-guards` (worktree `ebx-mdm-factory-volume-1b`), from `main`
`fef988e0`. Task: guard-rails against the seven failure modes the 2026-09-28 code audit found once the
factory is pointed at a landing/staging/MDM zone with real volume (hundreds of thousands to millions of
rows), not the tens-to-low-thousands every prior lot was measured against.

**Nothing in this lot was run live.** No EBX instance was reachable from this session; every check against
the EBX SQL dialect (whether `LIMIT` is supported, task item 7) was done read-only via the `data-access`
MCP server's `get_query_syntax` tool, not by guessing from memory or by writing to any instance.
`tools/test_local.sh` is green (XSD regeneration diff clean, compiles against the real `ebx-lib.jar` of
`EBX_LIB_DIR`, every pure unit test passes) - that is the only verification this lot has had. Deploy and
exercise it before trusting it against real volume.

## Lot 1b — guard-rails

### 1. Truncated landing loads must fail, not pass

- `landing/LandingFlow.java`: `LandingFlow.max(long)` and `MINIMUM_MAX` are GONE. `LandingFlow.SYNC_MAX_ROWS`
  (`Integer.MAX_VALUE`) replaces them - `ebx/LandingFlows.java`'s load loop now always asks
  `connectors_sync` for the largest `max` it could ever be given, never sized from the catalog's own
  `LandingTable.sourceRowCount` (which can be 0, never profiled, or stale). Whatever the connector's own
  hard cap turns out to be (the JDBC connector's 10,000-row clamp is a separate, parallel Connectors-side
  fix that raises it and adds the fields below), this factory-side sizing can never be a SECOND, independent
  way to truncate a load on top of it.
- New pure class `landing/LoadOutcomeRule.java` (+ `LoadOutcomeRuleTest.java`): decides `FAILED`/`WARN`/`OK`
  from the connector answer's own `truncated`/`available` fields (not on every Connectors build yet - a
  parallel lot adds them) with a fallback to comparing `count` against the catalog's `sourceRowCount`.
  **Decision taken where the brief left one:** `count < 0.95 * sourceRowCount` is `FAILED`, `0.95 <= count <
  sourceRowCount` is `WARN` (ordinary churn - rows legitimately deleted at the source between the scan and
  the load), `count >= sourceRowCount` is `OK` (a source that GREW since the last scan is never flagged - no
  guess in that direction). `truncated=true` or `available > count` always wins over the ratio check,
  whatever the ratio says.
- `landing/LandingFlow.loadOutcome` gained a `(JsonNode, long sourceRowCount)` overload implementing the
  above; the old 1-arg form now delegates to it with `sourceRowCount=0` (skips the ratio check, used by
  `ebx.SourceOnboarder`'s catalog-scan path, which has no meaningful "row count last seen" - the
  `truncated`/`available` guard still applies there, a strictly safe addition).
  `ebx/LandingFlows.java`'s load loop passes the real `table.sourceRowCount`. A `FAILED` verdict turns
  `LandingTable.status` to `FAILED` and `message` to the reason (existing `writeLoadOutcome` plumbing,
  unchanged) - the wizard's landing panel already displays that field, so it needs no separate change to
  show the new message.

### 2. Staging build job defaults to scope `new`

- `ebx/StagingLoadRun.java`: `start(...)` gained a `pAllRows` parameter (new 5-arg overload); the two
  existing overloads (the primary "Build and load" button, the advanced single-stage buttons) now default
  it to `false` instead of hardcoding `true` at `runInBackground`'s `StagingLoader.load(...)` call. Job
  params carry `"allRows"` (missing on an old queued job = `false`, never silently upgraded).
- **Decision taken where the brief offered a "regenerated model -> default to all" heuristic:** deliberately
  NOT implemented. `StagingGenerator.generate` is additive only (new tables/fields never retroactively
  invalidate already-transferred rows), so "a MODEL step ran more recently than the last LOAD" is not
  actually equivalent to "every row needs re-transforming" - wiring it in would silently turn an ordinary
  incremental run into a full one after any stand-alone "Build the tables" click. Left to the explicit
  choice below, documented as the reason rather than merely deferred.
- Wizard: a plain, unchecked-by-default checkbox "Recompute every row (full re-transform, not just what
  changed since the last load)" (`mdmfStagingScopeAll`), rendered once above the primary "Build and load"
  button (`WizardPage.renderStagingLoadRun`) - posts with the shared wizard form like `mdmfDistScopeAll`/
  `mdmfDistApproval` already do, no `wizard.js` sync needed (one project-wide checkbox, not one per row).
  Governs BOTH the primary combined button and the advanced stand-alone "Load the staging" button (they
  share the one posted field - no second checkbox was added next to the advanced button; it is inside a
  collapsed `<details>` disclosure most people never open, and duplicating the field under a second `name`
  would need a second posted field threaded through Java for a rarely-used escape hatch). i18n:
  `wizard.step4.build.scopeAll` in both `Messages_en.properties`/`Messages_fr.properties`.
- The REST route `/staging/load?scope=` (`FactoryResource.loadStaging`, synchronous, admin-only) was
  ALREADY correct (`scope=new` default via `IncrementalTransfer.isAllScope`) - untouched.

### 3. A matching run must be awaited to its terminal state

- The exact bug the audit describes was still present in `ebx/MatchingConfigurator.runInternal`: the
  not-done branch ended the `MATCHING_RUN` `PipelineStep` with `ok=true` ("launched, still RUNNING") even
  though `GoldenKeyRefresher.refresh` was skipped - despite several unrelated, already-merged fixes on this
  same file (lock-retry, honest final-result reads, per-poll cancellation) that made it LOOK finished.
- `ebx/MatchingConfigurator.java`: new `UNBOUNDED_WAIT_MS` constant (10 years, not `Long.MAX_VALUE` - see
  its own Javadoc for why `Long.MAX_VALUE` would overflow `MameMatchingApi`'s deadline arithmetic into the
  past). `runInternal` now computes `effectiveMaxWaitMs = pHandle != null ? UNBOUNDED_WAIT_MS : pMaxWaitMs`
  before calling `MameMatchingApi.launchScoped` - a job-driven call (`ebx.MatchingRunRun`'s background job,
  the only caller that ever passes a `Handle`) polls to the process' own terminal state with no wall-clock
  cap at all; a synchronous caller (the REST route, the workflow bean, both pass no `Handle`) keeps the
  bounded `DEFAULT_MAX_WAIT_MS` (15 minutes). The unbounded wait is safe because `MameMatchingApi.launchScoped`
  already checks `FactoryJobs.Handle#cancelled()` on every poll (a pre-existing lot, R12) - both how a
  person's Stop button reaches it AND, since this lot's item 5, how the job's own inactivity deadline stays
  alive while it is genuinely still polling.
- The not-done branch now ends the step `ok=false` unconditionally (was `true`), and its message
  distinguishes "stopped by a person/the job engine" (`pHandle.cancelled()` true - only reachable now, since
  the wait is otherwise unbounded) from "still RUNNING after Nms" (the synchronous path only).
- Not changed: the PARENT `MATCHING_RUN` step's own `ok` flag (`ebx.MatchingRunRun.HANDLER`) still only
  reflects `!pHandle.cancelled()`, not each entity's own per-entity step outcome - a pre-existing, separate
  aggregation choice this lot did not touch. Worth a follow-up if a run's overall step should read `ok=false`
  whenever any entity's refresh was skipped.

### 4. Landing key applied only if unique at the source

- `landing/KeyCertainty.certain` gained a fourth parameter, `pUniqueAtSource` (`Boolean`) - `SOURCE`/`USER`/
  `DEDUCED` origins are unaffected (never needed a sample or source check to begin with); a `RULE`/`LLM`
  proposal is now certain only when BOTH the existing sample check AND `pUniqueAtSource == TRUE` hold. `null`
  (not verifiable) is treated exactly like `FALSE` (a real collision), per the brief's own "not verifiable ->
  not certain -> the chooser is shown". `KeyCertaintyTest` extended with the new parameter on every existing
  call plus dedicated cases for the collision/not-verifiable/always-certain-origins combinations.
- New pure SQL builder `landing/LandingFlow.uniquenessCheckSql(connectorType, sourceObject, keyColumns)` (+
  tests in `LandingFlowTest`): `SELECT COUNT(*) AS totalCount, COUNT(DISTINCT <key expr>) AS distinctCount
  FROM <object>`, reusing the existing dialect/quoting/`concatCoalesce` machinery `_sourceId` itself is built
  from (deliberately WITHOUT `escapeKeyPart` - this expression only feeds a count comparison, never persisted
  or looked up, so the rare pathological collision escaping guards against would only make this ONE check
  slightly optimistic, never a data-corruption risk).
- New EBX-layer class `ebx/KeySourceUniqueness.java` (package-private, no dedicated test - it needs a live
  connector, same posture as `ebx.KeyStudy` itself): `verify(session, factory, tableId, keyColumns)` returns
  `TRUE`/`FALSE`/`null`, never throws. Two paths, chosen the same way `ebx.LandingPreview` already chooses a
  table's read/query shape: a SQL-capable connector runs the query above through `connectors_call`
  (`operation: query`, read-only, counted at the source - never a bulk read); a file/recipe source (no SQL
  dialect) is read IN FULL through the EXISTING `ebx.LandingPreview.sample` only when BOUNDED
  (`LandingTable.sourceRowCount` known and `<= KeySourceUniqueness.FULL_READ_BOUND` = 5000), then
  `landing.KeyEvidence.uniqueTogether` decides on the full row set - an unknown or too-large row count is
  `null` (not verifiable), never assumed small.
- `ebx/LandingRun.runKeyStage` calls `KeySourceUniqueness.verify` only when the sample already looks unique
  for a `RULE`/`LLM` proposal (an extra MCP round trip is pointless otherwise - `certain()` would read false
  regardless).
- **Not measured live**: the SQL alias-casing workaround in `KeySourceUniqueness.verifyBySql` (reads the two
  numeric fields positionally, `ObjectNode.elements()`, rather than by the `totalCount`/`distinctCount` alias
  name, since H2/Snowflake/PostgreSQL are known to disagree on unquoted-alias casing - CLAUDE.md §2) has never
  been run against a real connector answer. Verify the shape of a real `connectors_call` `query` response
  before trusting this on a live instance.

### 5. Job deadline based on inactivity, not total duration

- `ebx/FactoryJobs.java`: `Handle` gained `touch()` (pushes `deadline` to `now + maxMillis`) and `cancelled()`
  now calls it before returning - so EVERY existing check-in point across the whole module (every `*Run`/
  `*Configurator` body already calls `pHandle.cancelled()` "between stages", per this class' own original
  Javadoc) extends the deadline for free, with no other file needing to change. `enforceDeadlines`/`deadline`
  themselves are untouched (same field, same watchdog sweep, same test-visible mutability
  `FactoryJobsTest.old.deadline = 1L` still works) - only what PUSHES `deadline` forward changed.
  `FactoryJobsTest` extended with `checkinKeepsAliveWhileHungJobHitsTheDeadline` (a job that keeps checking in
  survives a short window; one that stops checking in is still caught).
  `Settings.jobMaxMinutes`'s label/description re-worded (no field/enum change) to state the new inactivity
  semantics - see "XSD change" below.
- This composes directly with item 3: a job-driven matching run's unbounded poll loop calls
  `pHandleOrNull.cancelled()` every 500ms (`MameMatchingApi.launchScoped`, pre-existing), which now also
  keeps that SAME job's own inactivity deadline alive - a multi-hour matching run on a huge table is never
  killed by `jobMaxMinutes` merely for taking a long time, only if it stops responding entirely.

### 6. BatchRetry: bisect + circuit breaker

- `transfer/BatchRetry.commit`: on a whole-batch failure, splits the batch in half and retries each half the
  SAME way, recursively down to a single item - `Attempt#tryOne` is now reached only once bisection has
  narrowed a failure down to exactly one item, never as a flat fallback for the whole batch. The `Attempt`
  interface is UNCHANGED (`tryBatch(List<T>)`/`tryOne(T)`) - both callers (`ebx.StagingLoader`,
  `ebx.MasterLoader`) needed zero interface-level changes. A single bad row among N costs `~2*log2(N)+2`
  calls instead of `N+1` (worked example and an exact-call-count test for N=8 in `BatchRetryTest`, which also
  now asserts `tryOne` is reached only for the isolated bad row, not for every item - a change from the
  pre-lot flat-fallback assertion `oneCalls == 3` it used to check).
- New pure class `transfer/BatchCircuitBreaker.java` (+ `BatchCircuitBreakerTest.java`): `report(batchWasEmpty,
  batchWroteNothing)` trips after `K` (default 3, configurable via the constructor, floored at 1) CONSECUTIVE
  batches that wrote NOTHING AT ALL (even after bisection) - a partial failure (bisection isolated one bad
  row out of 500) resets the streak, it is not a "batch that failed entirely". An empty flush (nothing to
  commit) is never counted either way.
  - `ebx/StagingLoader.loadRootFeed`: one breaker per root feed's own flush loop, threaded through
    `flushTree`/`flushOne` (both gained a `BatchCircuitBreaker` parameter) - shared by every entity of the
    feed's tree (root and every recursive child), since one flush of the root cascades into one
    `BatchRetry.commit` per entity.
  - `ebx/MasterLoader.loadEntity`: one breaker per entity's own flush loop, threaded through `flush` (gained
    the same parameter) - reports both the insert-buffer and update-buffer outcomes to the same breaker.
  - Either loader throws a plain `OperationException` with a clear message the moment the breaker trips -
    this propagates out of the whole pass-1 entity/feed loop (`StagingLoader.load`) or the whole entity load
    (`MasterLoader`), ending the run's `PipelineStep` `FAILED` with that message, naming the entity and how
    many consecutive batches failed. Rows already written earlier in the SAME run are kept (nothing is rolled
    back) - only the REMAINING, not-yet-attempted rows are abandoned.
- **Not measured live**: no systematic-failure scenario (a dropped connection mid-run, a permission revoked
  mid-run) was reproduced against a real instance - the breaker's trip condition and message were only
  exercised by the pure unit tests above.

### 7. Column profiling on a bounded sample

- Confirmed via the `data-access` MCP server's `get_query_syntax` tool (read-only, 2026-09-28) before writing
  any code: EBX SQL supports `LIMIT n` directly (also `OFFSET ... FETCH`). No Java-side fallback (an unbounded
  read capped in Java with a per-column `HashSet`) was needed.
- `landing/ColumnProfileSql.java`: `build(tableName, columns)` now delegates to a new `build(tableName,
  columns, sampleLimit)` overload - a positive `sampleLimit` wraps the FROM clause in
  `(SELECT * FROM "table" LIMIT n) AS "table"` (re-aliased to the SAME quoted name so every existing,
  unqualified column reference keeps resolving unchanged); 0 or negative stays unbounded (the pre-existing
  behaviour). New `Statement.sampleLimit` field and a new pure `sampled(Statement, rowCountInAnswer)` helper
  (true only when the answer's own `ROW_COUNT` reached the cap - a table smaller than the cap was profiled in
  FULL, not sampled, even though a limit was requested). `interpret`'s own signature/output shape is
  UNCHANGED - every existing caller of it keeps working verbatim.
- New Settings field `columnProfileSampleRows` (int, default 50000, `tools/build_xsd.py` + `FactoryPaths.
  Settings.COLUMN_PROFILE_SAMPLE_ROWS`) - "K = 50,000, a Settings value" per the brief. `ebx/
  LandingColumnProfiler.profileOneTable` reads it and passes it to `ColumnProfileSql.build`; a table whose
  answer says `sampled` logs one diagnostic line (`System.err`, this module's only logging channel - see the
  class' own pre-existing convention) naming the table and the cap.
- **Decision taken where the brief asked for it ("report sampled... so the LLM prompt says 'on a sample of K
  rows'")**: NOT implemented as a persisted per-table flag or a live number threaded into the prompt. No
  `LandingColumn`/`LandingTable` field exists to carry it, and `StagingLlmPass.userPrompt`'s per-column
  fill-rate renders happen at several call sites across a very large file - threading a boolean through all
  of them for this guard-rail lot felt disproportionate. Instead, `StagingLlmPass.systemPrompt()` (the ONE
  static, cached prompt every run sends) gained one generic sentence: "On a large landing table, fill rates
  and distinct-value counts are measured on a bounded sample of its rows, not necessarily the whole table -
  treat them as informative, not exact." This tells the model the epistemic caveat without a live number or a
  schema change; a live per-table "sampled: true, K rows" fact would need a proper follow-up lot (new
  `LandingColumn`/`LandingTable` field, wired through `StagingProposalRun`'s `ProjectInfo` construction and
  every `userPrompt` render site).
- New test `landing/ColumnProfileSqlTest.java` (the class had NO test at all before this lot): unbounded vs.
  bounded SQL shape, the re-aliasing, `interpret`'s own output unaffected by the limit, and `sampled`'s three
  cases (cap reached, cap exceeded - should not happen with a real `LIMIT` but never misreported, table
  smaller than the cap).

## XSD / restart

Two Settings fields changed, both structural (data-model) changes even though one is text-only:

- `jobMaxMinutes` (item 5): field/type/default UNCHANGED, only its `osd:label`/`osd:description` text
  changed to state the new inactivity semantics. **A restart (or the `backend.mode=development` UI-navigation
  reload, CLAUDE.md §9) is needed only to show the corrected label/description in the Settings screen** - the
  actual behavior change (item 5's `Handle.touch()`) is a plain class-file change, already live the moment the
  module's classes are reloaded (i.e., needs the SAME restart every code change in this module needs, nothing
  extra).
- `columnProfileSampleRows` (item 7): a genuinely NEW field. **Needs a full model/module reload (restart) to
  exist at all** - until then, `LandingColumnProfiler.sampleRows` reads `null` off the settings row (the field
  does not exist yet in the running schema) and falls back to `0` (unbounded profiling, the pre-lot
  behaviour) rather than throwing; profiling remains correct, just not yet bounded, until the restart happens.

Every other change in this lot (items 1, 2, 3, 4, 6 and the wizard checkbox of item 2) is a plain Java/i18n
change - the usual "restart Tomcat to load new classes" rule applies, no model reload beyond that.

## What was NOT verified (nothing in this lot runs live)

- Item 1: no real `connectors_sync` answer carrying `truncated`/`available` was seen (the Connectors-side lot
  that adds them was not available to test against); the fallback ratio check (`count` vs. catalog
  `sourceRowCount`) was not exercised against a real truncated load either.
- Item 2: the wizard checkbox was not clicked in a browser; the posted-field plumbing was read, not run.
- Item 3: `MatchingConfigurator`'s job-driven unbounded wait was not exercised against a real long-running
  matching process (nothing here was long enough, live, to actually hit the old 15-minute bound).
- Item 4: `KeySourceUniqueness.verify`'s SQL path (alias-casing workaround, the exact `connectors_call` answer
  shape) and its bounded-file-read path were both written against the documented/observed shapes of
  `ebx.LandingPreview`/`connectors_call`, never run against a live connector.
- Item 5: the inactivity-deadline change was proven by the pure unit test only, never against a real
  multi-hour background job.
- Item 6: the circuit breaker's trip condition was never exercised against a real systematic failure (a
  dropped DB connection, a revoked permission) mid-load.
- Item 7: `LIMIT` support was confirmed via `get_query_syntax` (a live, authoritative read-only tool), but the
  generated bounded-subquery SQL text itself was never executed against a real landing table.

Deploy this branch and exercise each item against a real project before relying on it at volume.

## Lot 2a — Xref natural key, and why `<index>` is not the answer

Written 2026-09-28 on branch `feature/volume-xref-key` (worktree `ebx-mdm-factory-volume-2a`), from
`integration/volume-wave1` `0ce70995` (already carrying lot 1b above). Task: `Xref` (one row per staged/master
record - the single most volume-scale table in this module) had primary key `/id` (auto-increment) and no
index at all; every access - `StagingLoader.writeXref`, `MasterLoader.lookupXrefMasterKey`/`writeXref`/
`ParentKeys.resolve`, `GoldenKeyRefresher.refresh`/`refreshScoped`, `MasterReset`, `StepResetter`,
`ProjectDeleter`, `RecordLineageReader.resolveSource`/`selfHealGoldenKeys` - was a full-table XPath predicate
scan: O(n) per row, O(n²) per load. `TransferReject` had the same shape (no index, filtered in Java in one
spot) but a much smaller row count (rejects, not every row).

**Nothing in this lot was run live either** (same constraint as lot 1b) - same posture: `tools/test_local.sh`
is green (XSD regeneration diff clean, compiles against the real `ebx-lib.jar`, every pure unit test passes,
1468 classes), that is the only verification. Deploy and exercise before trusting it at volume.

### `<index>` is a no-op on EBX 6.3 - do not use it

The original plan (this file's own draft, and CLAUDE.md §5's note from earlier the same day) was to declare
`<index name="idxMaster">/project /masterTable /masterKey</index>` inside `<osd:table>`, next to
`primaryKeys`, for the master-side lookups, plus one on `TransferReject` for `(project, entity, stage)`.
**The coordinator verified live, mid-lot, that this does nothing on EBX 6.3**: `javap` on the RC instance's
own `ebx-lib.jar`, `com.orchestranetworks.cce.cache.table.CfTableFunction` (the bean behind `osd:table`) shows
`setIndex(String)` doing only `this.hasDeprecatedIndexes = true` - the value itself is discarded, never stored,
never used to build an actual index. A DMA model also drops the element on export. **No index support was
added to `tools/build_xsd.py`; no `<index>` element is emitted anywhere in this schema; the planned
`TransferReject` index was dropped entirely, never applied.** The primary key is the ONLY indexed access path
EBX 6.3 offers a custom module's table - every fix in this lot works within that one constraint.

### 1. `Xref`'s primary key is now the natural key `(project, stagingTable, stagingKey)`

`tools/build_xsd.py`'s `Xref` table: `pk=['project', 'stagingTable', 'stagingKey']` (was `['id']`); the `id`
field (`F('id', 'id', ...)`) is REMOVED entirely - nothing else in the module ever referenced
`FactoryPaths.Xref.ID` for anything but row identity, which the natural key now provides. `stagingTable`/
`stagingKey` gained `min=1` (a primary-key field is mandatory, same convention every other composite-key table
in this schema already follows, e.g. `Favorite`). `FactoryPaths.Xref.ID` is gone from `core/FactoryPaths.java`.
This alone turns every STAGING-side lookup - `StagingLoader.writeXref`, `MasterLoader.lookupXrefMasterKey`/
`writeXref`/`ParentKeys.resolve` (all keyed by project + entity name + a formatted staging key, exactly the
new PK) - from an O(n) predicate scan into an O(1) `AdaptationTable.lookupAdaptationByPrimaryKey` call. The
regenerated XSD diff is exactly the primary key line, the removed `id` element, and the two `minOccurs`
changes - nothing else in the table moved.

### 2. `XrefByMasterRecord` - deriving the natural key for a MASTER-side lookup

A caller that only has a MASTER key (not a staging key) - `GoldenKeyRefresher.refreshScoped`,
`RecordLineageReader.resolveSource`/`selfHealGoldenKeys` - cannot use the new PK directly. New class
`ebx/XrefByMasterRecord.java`: given the master record (or its key + the master table), reads its own
`sourceSystem`/`sourceId` (plain, reserved fields `master.MasterModel` always writes verbatim - see that
class' own Javadoc, "sourceSystem/sourceId move to plain, OPTIONAL fields", never renamed by
`MasterFieldNameRule`), recomputes `stagingTable.computePrimaryKey(new Object[]{sourceSystem,
sourceId}).format()` - the EXACT `stagingKey` value `Xref.STAGING_KEY` was written with - and looks the Xref
row up by the resulting natural key. Returns `null` (never throws) for every case that means "no Xref row
exists for this record": a blank `sourceSystem`/`sourceId` (an add-on auto-created golden, which never had a
source row), an unresolvable staging table (the project's staging zone is gone), or no PK match. Both
`RecordLineageReader.resolveSource` and `selfHealGoldenKeys` now call this instead of a
`project=X and masterTable=Y and masterKey=Z` predicate scan; `resolveSource`'s signature gained
`pEntityName`/`pMasterTable`, and the staging-table resolution in `RecordLineageReader.read` was moved earlier
(before the self-heal call) so both can use it. `GoldenKeyRefresher.refreshScoped` resolves its own
`entityName` (`MameMetadataset.lastPathStep(masterTablePath)`) and staging table (a new
`lookupStagingTable`, reading the project's `STAGING_DATASPACE`/`STAGING_DATASET`) once, then derives each of
its (typically a handful of) master keys individually - answers a zero `Result` when either cannot be
resolved, rather than falling back to a scan.

### 3. `ParentKeys`: a bounded LRU cache, and skipping the add-on hop when matching is not activated

`MasterLoader.ParentKeys.resolve` (called once per foreign key, per staged row - the actual O(n²) hot path)
now:

- Caches the resolved parent key per `(targetEntityName, stagingRef)` for the lifetime of one load - a
  fan-out of many children pointing at the same parent resolves it once, not once per child. Pulled out to a
  new pure class, `master/ParentKeyCache.java` (`ParentKeyCacheTest`, no EBX needed): a dedicated `Key` class
  (never string concatenation - a staging key can itself embed the `PrimaryKey` `"|"` separator, CLAUDE.md
  §9, so two different `(entity, ref)` pairs could otherwise collide on the same concatenated string), a
  `Value` carrying both the resolved master key and whether it was a golden-record redirection (so a cache
  hit still counts `Counters.referencesToGolden` correctly), and a bounded `LinkedHashMap` (access-order,
  `removeEldestEntry`) - default cap `DEFAULT_MAX_ENTRIES = 200 000`. Memory estimate (documented in the
  class' own Javadoc): ~250-400 bytes/entry (two short strings + a value + `LinkedHashMap.Entry` overhead) →
  at most ~50-80 MB at the cap - a deliberate, bounded trade-off, never presented as a hard limit (CLAUDE.md's
  own "EBX tient le volume").
- Skips `SurvivingRecord.follow` (the add-on hop: a master-record lookup plus a `MetadataHelper.getState`
  read) entirely when matching was never activated (`MatchPlan.status == APPLIED`) for the TARGET entity -
  nothing could have been merged away, so the hop would only ever answer "not merged" the slow way. Activation
  is checked once per target entity and cached (`matchingActivatedByEntity`), read the same way
  `RecordLineageReader` already gates `MameRecordState` (that class' own Javadoc: `MatchPlan.status` is a
  plain fact of the factory's own data, checked BEFORE ever touching the add-on).

### 4. Pass 2 (deferred self/cycle references), both loaders

`StagingLoader.resolveDeferredReferences` and `MasterLoader.resolveDeferredReferences` (self-reference / cycle
foreign keys, resolved in a second pass once every promoted entity has a master/staging key) were restructured
into two phases, never overlapping:

1. A server-side predicate (`osd:is-not-null(...)` per deferred field, OR-ed together for `MasterLoader`, since
   an entity can have more than one deferred field) restricts the scan to rows that actually carry a reference
   to resolve at all - most rows of a self-referencing entity have none (e.g. a root account with no parent).
   Only PRIMARY KEY strings are collected, never full rows.
2. The `RequestResult` from step 1 is fully closed before any write. Each candidate row is re-read fresh by
   primary key (an indexed lookup) inside its own batch's `Procedure` - a still-open scan of the SAME table a
   separate write transaction is about to modify was the exact anti-pattern this restructuring removes
   (`MasterLoader`'s old code did the `Xref` lookup, and `StagingLoader`'s old code called
   `flushRefUpdates` mid-iteration, both while their own driving `RequestResult` was still open).

`MasterLoader`'s version additionally checks `rawRef` on every deferred field of a candidate row BEFORE ever
touching `Xref` (the old code looked `ownMasterKey` up for EVERY row the outer scan returned, regardless of
whether any deferred field actually had a reference to resolve), and compares each resolved value against the
master row's CURRENT value before writing - an unchanged value is never re-written. Batches commit at
`Settings.TRANSFER_BATCH_SIZE` (the same `pBatchSize` already threaded through from the caller), not a new
constant.

**Decision, not fully implemented as literally worded**: the brief also asked to "restrict the second scan to
the rows of the current run (`_loadId` = this run's id if such a field exists on the staging row)". Checked
and rejected: a staging row's own `_loadId` field records which LANDING/STAGING load produced or last touched
it - it is not, and cannot be made to mean, "was processed by this particular MASTER run" (`MasterLoader` has
no run-scoping concept on staging rows at all, and STAGING's own `_loadId` is the LANDING load's id, carried
through - see `MasterModel`'s own Javadoc). Filtering by it would silently skip legitimately still-unresolved
rows from an earlier run whose parent has since arrived. The documented fallback - "a predicate on the not-null
reference field" - was implemented instead, for both loaders, unconditionally. **Flagged for the coordinator**:
if an actual "only rows touched by this run" scoping is wanted for `MasterLoader`'s pass 2, it needs a new
run-id concept threaded onto staging or master rows first; nothing in the existing schema carries one today.

### 5. `GoldenKeyRefresher`: two different strategies, not one shared implementation any more

- `refresh` (whole-entity sweep - the on-demand/scheduled project-wide refresh, and the post-matching-run
  call): stays a streamed predicate scan (§4 above only applies to a per-row Xref lookup; "every row of this
  entity" has no PK to derive from - a genuine linear pass is correct here, per the coordinator's own
  instruction). No longer collects a `List<Adaptation>`: the scan collects PRIMARY KEY strings only, fully
  closes, then commits fix-ups in batches of `BATCH_SIZE = 1000` (a new pure `transfer/BatchRanges.java`,
  `BatchRangesTest` - "split N items into batches of at most size S" as a plain, unit-tested range computation,
  shared with `VolumeBatchOps` below), each batch its own `Procedure` - a huge project's refresh commits
  incrementally instead of one all-or-nothing transaction. Only rows whose decision is not `NO_CHANGE` are
  written (unchanged already, before this lot).
- `refreshScoped` (a handful of specific master keys - the suspect, the anchor, and every record sharing the
  resulting golden, from `RecordLineageReader.decideSuspect`): no predicate scan at all any more - each key's
  own `Xref` row is DERIVED (`XrefByMasterRecord`, §2). Answers a zero `Result` when the master/staging table
  cannot be resolved, rather than falling back to the whole-project scan.

**Not implemented, flagged for the coordinator**: "`refreshScoped` looks up by PK when the staging key is
known, else by the (now indexed) master-key predicate" - the only production caller of `refreshScoped`
(`RecordLineageReader.decideSuspect`) only ever has MASTER keys (anchor, suspect, group members), never a
staging key, so a "staging key known" branch would be dead code today. `refreshScoped` derives via
`XrefByMasterRecord` unconditionally instead (§2) - functionally equivalent (an indexed PK lookup either
way), just not literally the two-branch shape the brief sketched.

### 6. `MasterReset`, `StepResetter`, `ProjectDeleter`: `VolumeBatchOps`, and narrower scope than the brief's table list

New shared class `ebx/VolumeBatchOps.java`: streams a table for a predicate into PRIMARY KEY strings (never a
`List<Adaptation>`), closes the scan, then applies a caller-supplied `RowOp` (clear fields, or delete) in
batches of `BATCH_SIZE = 1000` (`BatchRanges`, §5), each batch its own `Procedure`. Used by:

- `MasterReset`: the whole-project `Xref` clear (`masterTable`/`masterKey`/`goldenKey` → null) - previously one
  giant `Procedure` iterating an open `RequestResult` over every `Xref` row of the project. The predicate now
  also filters to rows with a non-blank `masterTable` server-side (the old Java `if (blank) continue` guard is
  gone). The small, model-scale bookkeeping (`Project` pointer fields, `PipelineStep` rows to `RESET`) stays a
  single ordinary `Procedure`, run separately.
- `StepResetter`: same `Xref` treatment (predicate: `osd:is-not-null(./goldenKey)`, OR-ed with
  `osd:is-not-null(./masterTable)` only when `MASTER` is in scope), pulled entirely out of `writeReset`'s main
  `Procedure` and out of `Gathered` (it was never read by `plan()` at all - `planJson` never touched
  `Gathered.xrefRows` - so `gather()` no longer computes it for either caller). `TransferReject` is now
  filtered by `./stage` IN THE PREDICATE (`queryEqAnd`, a new helper) instead of fetching every row of the
  project and filtering in Java - a `MASTER`-only reset never even reads the (usually far larger)
  `STAGING`-stage rows.
- `ProjectDeleter`: same `Xref` and `TransferReject` treatment for full DELETION (not a field clear) - both
  pulled out of `gather()`'s `rowsByTable`/`deleteFactoryRows`'s single `Procedure` entirely. `plan()`'s row
  counts for these two now come from a new `countEq` (`RequestResult.getSize()`, never a drained list);
  `delete()` batch-deletes them via `VolumeBatchOps` before the main `Procedure` runs (children before
  parents, same order the class Javadoc already documents).

**A real, accepted trade-off**: splitting one giant `Procedure` into several breaks the "all-or-nothing"
atomicity the original single-`Procedure` design had. If the main bookkeeping `Procedure` fails AFTER the
`Xref`/`TransferReject` batches already committed, the reset/deletion is left partially applied. This is the
SAME posture lot 1b's `BatchCircuitBreaker` already established for this module (rows already written earlier
in a run are kept; fix the issue and retry) - `plan()`/`gather()` always recompute from live state, so a
retried reset/deletion sees an already-cleared/deleted row as `SKIPPED_ABSENT`/absent, never a failure.

**Scope narrower than the brief's literal table list, on purpose**: `MappingStep`, `AttributeMapping`,
`QualityRule`, `CapabilitySuggestion`, `MatchPlan`, `TransferFeed`, `StagingAttribute`, `StagingEntity`,
`LandingColumn`, `LandingRelation`, `LandingTable`, `ProjectSource`, `AgentStep`, `AgentRun`, `PipelineStep`
are all still gathered into `List<Adaptation>` and deleted inside one `Procedure`, in both `StepResetter` and
`ProjectDeleter` - unchanged by this lot. These tables scale with the MODEL definition (entities, attributes,
mappings, sources) or with the number of PIPELINE RUNS, never with data volume (one row per staged/master
record) - the two genuinely volume-scale tables named throughout the original audit are `Xref` and
`TransferReject`, both addressed above. `StepResetter.plan()`/`ProjectDeleter.plan()` still eagerly gather
these bounded tables even for a mere preview (a real, if much smaller, inefficiency than the `Xref`/
`TransferReject` case this lot fixes) - flagged for the coordinator as a possible follow-up, not attempted
here given the size of the refactor it would need (a `pNeedRows`-style split threaded through every query in
both classes' `gather()`).

### 7. `RecordLineageReader` - moved, not re-architected

`resolveSource` and `selfHealGoldenKeys` are the two `RecordLineageReader` methods the coordinator named in
item 3 - both are now thin callers of `XrefByMasterRecord` (§2); the actual change lives there. The only
`RecordLineageReader`-local change is ordering: `read()` used to resolve the staging table AFTER calling
`selfHealGoldenKeys`, which never needed it before (a predicate scan only needed `masterTablePath`); it now
needs the staging table to derive the natural key, so that resolution block was moved up, before the self-heal
call, with a comment explaining why. `resolveSource`'s signature gained `pEntityName`/`pMasterTable` (both
already in scope at every call site - `read()`'s own parameter and local variable); `selfHealGoldenKeys`
gained `pEntityName`/`pMasterTable`/`pStagingTable` alongside the `pMasterTablePath` it already had (kept for
its log message). The unused `master.GoldenKeyRefreshScope` import was removed from this file (still used, and
still tested, from its own package - only these two call sites stopped needing it).

### 8. Tests

New pure classes, each with a JUnit-free test (`main` + `check`, like every other test in this module):

- `master/ParentKeyCache.java` / `ParentKeyCacheTest.java`: round-trip, the `NOT_FOUND` sentinel is a real
  cache hit (distinct from an actual miss), two `(entityName, stagingRef)` pairs that would concatenate to the
  same string do not collide, bounded LRU eviction (the least-recently-used entry is evicted, not an
  arbitrary one), a read refreshes recency, and a non-positive max is floored at 1.
- `transfer/BatchRanges.java` / `BatchRangesTest.java`: exact multiples, a remainder producing a shorter last
  batch, fewer items than one batch, zero/negative total (no ranges, no exception), zero/negative batch size
  (one big batch, never an infinite loop), and a general "tiles the whole space with no gap or overlap"
  property check.

`tools/test_local.sh` (XSD regeneration diff, compile against the real `ebx-lib.jar`, every pure test):
green, 1468 classes, every test `OK` (including `RecordLineageReaderTest`/`StagingLoaderTest`, which exercise
the changed classes' EBX-facing code paths at the type level - a compile-time check, not a live run).

### What was NOT verified (nothing in this lot runs live)

- The `<index>` no-op finding is the coordinator's own live `javap` evidence, not re-verified independently in
  this session.
- No real EBX instance was reachable: the natural-key PK lookup, `XrefByMasterRecord`'s derivation (does
  `sourceSystem`/`sourceId` really round-trip through `computePrimaryKey`/`format()` to the exact stored
  `stagingKey`, for every entity shape, including a composite staging key with an escaped `"|"`), the
  restructured pass-2 predicates (`osd:is-not-null` OR-chains), `VolumeBatchOps`'s batched `Procedure`
  commits, and `GoldenKeyRefresher`'s two split strategies were all written against the documented/measured
  API shapes this codebase already relies on elsewhere (CLAUDE.md §9, existing call sites), never executed.
- The claimed atomicity trade-off (§6) - a partial reset/deletion after an early batch commits and a later one
  fails - was reasoned about, never reproduced.
- The 200 000-entry `ParentKeyCache` bound and its ~50-80 MB estimate are a documented calculation, never
  measured against a real JVM heap with real key/value string lengths.

## Deployment procedure (lot 2a)

`Xref`'s primary key changed from `/id` (auto-increment) to `(project, stagingTable, stagingKey)` - a
structural (data-model) change. **Every existing project's `Xref` rows were written under the OLD key shape**
and have no value for a primary key that no longer exists once the new schema loads - EBX has no in-place
primary-key migration.

1. **Before deploying this branch**, delete every existing project through the Factory's own deletion screen
   (`ProjectDeleter` - covers `Xref`, `TransferReject`, and everything else the project owns). Do this on the
   OLD code/schema, while `Xref.id` still resolves normally.
2. Deploy this module's classes and the regenerated `MdmFactory.xsd`.
3. **Restart Tomcat** - a primary-key change is a structural data-model change (same rule as every other XSD
   change in this module, CLAUDE.md §9's `backend.mode=development` limits: a full restart is the only
   reliable reload for a change like this, not just a UI navigation).
4. If an old `Xref` row somehow survives past the restart (a project not cleaned up first): expect the
   dataset/table to fail to load, or the surviving row to be unreachable by any of this lot's PK-based lookups
   (its `stagingTable`/`stagingKey` fields are blank, since the old schema never populated them as a key) -
   there is no migration path for it; delete it and re-run the load that produced it.

### How to verify live, once deployed

- Create a small test project (the reference `Company`/`Contact` demo is enough), run landing → staging →
  master, and confirm `Xref` rows exist with the new key shape (`GET` a row by `B<staging dataspace>/... /root/
  Xref/<project>|<entity>|<stagingKey>` over Data Services, or `describe_table`/`preview_records` via the
  `data-access` MCP server - `primaryKeys` should read `project`, `stagingTable`, `stagingKey`, no `id` field
  at all).
- Run a master load twice in a row (a no-op incremental run): confirm no duplicate `Xref` rows are created for
  the same staged record (the natural key itself prevents this - a re-run must find and update the SAME row,
  not insert a second one).
- Activate matching on the test project, merge two records, confirm `GoldenKeyRefresher.refresh` still sets
  `Xref.goldenKey` correctly (the whole-entity streamed-scan path), then use the record-lineage screen's
  "accept a suspect" action and confirm `refreshScoped`'s PK-derived path also updates the right row (compare
  against what `refresh` would have found, on a project small enough to run both).
- With a self-referencing entity (e.g. `Account.parentAccountId`, per `MasterLoader`'s own Javadoc example),
  confirm a root row (no parent) never touches `Xref`/the target table at all (pass-2's rawRef-before-lookup
  guard), and a real parent/child pair still resolves correctly after this lot's restructuring.
- Delete the test project and confirm `Xref`/`TransferReject` rows are fully gone (`VolumeBatchOps`'s batched
  delete) even for a project whose row count exceeds one batch (1000) - the easiest way to force more than one
  batch without a truly large project is to temporarily lower `VolumeBatchOps.BATCH_SIZE` for the test, or
  accept single-batch coverage as the practical live check and rely on `BatchRangesTest`'s pure coverage for
  the multi-batch math.

## Lot 8a — operations

Written 2026-09-28 on branch `feature/volume-operations` (worktree `ebx-mdm-factory-volume-8a`), from
`integration/volume-wave1` `9af356a7` (carrying lots 1b, 4 and 2a above). Task: multi-hour runs of this module
need to stop tying up a Tomcat request thread, serialize correctly with each other, and stop paying a
per-record EBX/query cost that should be a per-pass one - the "operability" half of the volume-scalability
plan, as opposed to the earlier lots' "correctness/throughput at volume" half. Same posture as every lot
above: nothing here was run live (no EBX/Connectors instance reachable this session); `tools/test_local.sh` is
the only verification.

### 1. Long REST routes go through the job queue

Seven `rest.FactoryResource` routes ran their work directly on the Tomcat request thread; each now defaults to
enqueueing the SAME job the wizard's own button already uses (or, for two routes that had no job wrapper at
all before this lot, a new thin one) and answers **`202`** with `{started, jobId, merged, position,
statusUrl}` (`statusUrl` is the existing `GET /projects/{code}/jobs` route, already polled by the wizard - the
caller filters to its own `jobId`). Every one of the seven keeps a **`?sync=true`** query parameter that
preserves the EXACT pre-lot behaviour (call the underlying method directly, block, answer its own JSON body
unchanged) - added because `docs/DELIVERY.md`'s migration runbook and several worked examples in
`docs/MATCHING.md` read these routes' response bodies inline as manual operator/curl steps (row counts,
`{"ran": [...], "results": {...}}` shapes quoted verbatim) rather than through a script that would need
updating; `tools/deliver.sh` itself was checked and does not call any of these seven (it drives the EBX Staging
delivery archive mechanism, an unrelated concern).

| Route | Default (no `sync`) | `?sync=true` |
|---|---|---|
| `POST .../staging/load` | enqueues `StagingLoadRun` (stage `LOAD` only), resource `STAGING` | `StagingLoader.load` directly (unchanged) |
| `POST .../master/load` | enqueues `MasterBuildRun` (stage `COPY` only), resource `MASTER` | `MasterLoader.load` directly (unchanged) |
| `POST .../matching/configure` | enqueues `MatchingConfigureRun`, resource `MATCHING` | `MatchingConfigurator.configure` directly (unchanged) |
| `POST .../matching/run` | enqueues `MatchingRunRun`, resource `MATCHING` (`maxWaitMs` has no effect - the job path always polls to the process' own terminal state, no wall-clock cap) | `MatchingConfigurator.run` directly, `maxWaitMs` honoured (unchanged) |
| `POST .../matching/refresh-golden-keys` | enqueues new `GoldenKeyRefreshRun`, resource `MATCHING` (had NO job wrapper before this lot) | `MatchingConfigurator.refreshGoldenKeys` directly (unchanged) |
| `POST .../distribution/targets/{id}/first-full-send` | enqueues new `DistributionSendRun.startFirstFull`, resource `DISTRIBUTION` (new resource this lot) | `DistributionRunner.sendFirstFull` directly (unchanged) |
| `POST .../distribution/targets/{id}/send` | enqueues new `DistributionSendRun.startSendNow`, resource `DISTRIBUTION` | `DistributionRunner.sendNow` directly (unchanged) |

`FactoryResource.accepted(ObjectNode, String)` is the one shared helper building the `202` body. The workflow
beans (`workflow.MatchingRunTaskBean`, `.MasterLoadTaskBean`, `.StagingLoadTaskBean`) were left untouched, as
instructed - they already call the exact same underlying methods (`MatchingConfigurator.run`, `MasterLoader.load`,
`StagingLoader.load`) the job handlers now also call, so "the same code path" holds without any change there;
blocking a workflow thread is that mechanism's own design, not something this lot's REST changes touch.
`DistributionTaskBean` (the one workflow bean with no resource claim of its own before this lot - every other
bean already claims its resource via `PipelineLauncher.run(..., FactoryJobs.XXX, ...)`) was **not** updated to
claim the new `FactoryJobs.DISTRIBUTION` resource, per this lot's own "leave the workflow beans" instruction -
flagged for the coordinator: a workflow-launched send and a wizard/REST-launched send of the SAME project can
still run concurrently against the same `DistributionTarget`/`DistributionOutbox` rows, a pre-existing gap this
lot made *visible* (by finally giving distribution sends a resource to claim) without closing it for that one
caller.

### 2. Reset and delete as jobs

`FactoryJobs` gained `claimAll`/the `PROJECT` synthetic resource (claims every entry of `RESOURCES` **and**
`PROJECT` under the SAME `Handle` instance, so `Handle.cancelled()`'s inactivity-deadline `touch()` and a
`cancel(projectId, kind)` by kind both reach every one of the seven keys through one object) and
`FactoryJobQueue` gained `registerWholeProject` (a kind whose `tryStart` calls `claimAll` instead of a
single-resource `claim`). `release`/`submit`'s cleanup were refactored into one `unclaim` helper that knows to
free all seven keys for a `PROJECT`-resource handle - the ONE place a bug here would leak a resource forever
had this not been centralised. `liveForProject` was fixed to de-duplicate (a whole-project handle appears
under multiple `LIVE` map values); `busyMessage` reads "the whole project" instead of "the project of this
project" for one.

Three new whole-project job wrappers - `StepResetRun`, `ProjectDeleteRun`, `MasterResetRun` - call
`StepResetter.resetHoldingProject`/`ProjectDeleter.deleteHoldingProject`/`MasterReset.resetHoldingProject`
**directly** (all three made package-visible, were `private`) rather than through
`FactoryJobQueue.exclusively`, which would deadlock on the claim the job's own `claimAll` already holds. The
three REST routes (`POST .../reset`, `POST .../delete`, `POST .../master/reset`) got the same
enqueue-by-default/`?sync=true` treatment as task 1's seven. `StepResetter.plan`/`ProjectDeleter.plan` are
UNCHANGED and stay synchronous (task brief: "it only counts now" - true since lot 2a's own row-count rewrite).

**A real behaviour change from `exclusively`'s old posture, worth flagging**: `exclusively` used to REFUSE
outright ("N job(s) of this project are waiting or running...") when any job was active for the project: the
caller had to retry by hand. A whole-project job now QUEUES like every other job kind ("nothing is refused" -
`FactoryJobQueue`'s own class Javadoc) and starts once every resource frees up. This matches the rest of the
job engine's philosophy but is a genuine behaviour change for a reset/delete caller that previously got an
immediate, explicit refusal.

**Deliberately NOT done, and why**: the wizard's own Java UI (`ebx.ui.ProjectWizardUserService`,
`ProjectDeleteUserService`, `ProjectDeletePage`) still calls `StepResetter.reset`/`ProjectDeleter.delete`/
`MasterReset.reset` directly (the synchronous, `exclusively`-wrapped entry points, unchanged) - a
`UserServicePane` postback for one of these actions still blocks the Tomcat thread for the whole reset/
deletion, exactly as before this lot. `ebx/ui/*` files are outside this lot's file-ownership boundary (not in
the explicit "you may edit" list, and a `UserServicePane` rewrite to a background-job-plus-polling UI - the
same shape `StagingLoadRun`'s own "while running" screen already uses - is a real, non-trivial UI change this
lot did not attempt to make blind). The job-queue engine work above (task 2's actual ask) is complete and
available to any caller today (REST, a future UI polling screen); only the EXISTING wizard buttons were left
wired to the old synchronous path. Flagged for the coordinator as the natural next step for a UI-owning lot.

### 3. Distribution trigger cost — investigated, found infeasible as literally asked, partially delivered

New pure class `distribution/ArmedTargetCache.java` (+ `ArmedTargetCacheTest.java`): a per-JVM cache of "is
there an ARMED REALTIME target for this project", keyed by project id, with an explicit `invalidate` and a
30 s TTL fallback - exactly the cache the brief describes. Wired for invalidation into every Java write path
that changes a `DistributionTarget`'s `state`/`mode`: `DistributionPause.setState` (pause/resume),
`DistributionRunner.writeTargetState` (first-full-send arm, admin override), `DistributionReader.setState`
(disarm) and `.upsertTarget` (mode can change on an update).

**What this does NOT fix, read before assuming it closes the gap the brief describes**: the actual per-row
cost the brief measured ("5 million queries during a load") is a live SQL query `ebx.DistributionTriggerBody`
GENERATES AS EBX SCRIPT TEXT, compiled and run by EBX's own script engine on every master-table save - a
table trigger, not Java code this module controls at execution time. EBX Script has no sanctioned way to call
an arbitrary custom Java static method (CLAUDE.md sec 2: only the fixed `core.*` built-ins and OTHER
script-authored libraries, via `SCRIPT_LIBRARY_ACCESS`, are ever callable from a script) - so a per-JVM Java
cache genuinely cannot be consulted from the generated trigger text, and `DistributionTriggerBody`'s SQL is
UNCHANGED by this lot; every create/modify/delete of a master row still runs the same query it did before.
Closing this for real needs one of two things, both flagged for the coordinator rather than attempted here:
(a) redeclare the trigger as a plain Java `osd:trigger class="..."` instead of an EBX-Script one (a
data-model/schema-DECLARATION change - this lot's own instructions say to stop and report on that, not make
one), or (b) a denormalized "any REALTIME target armed" flag on `Project` itself, cheap enough for a script to
read without a query (an XSD change to `MdmFactory.xsd`, owned by the sibling `feature/volume-loader-memory`
lot per this lot's file-ownership boundary). `ArmedTargetCache` exists, is tested, and is correctly
invalidated - it is simply not reachable from the one place the brief's own evidence points at, and no other
Java call site in this codebase is hot enough today to need it (`DistributionDispatcher`'s own drain pass
already reads `DistributionTarget` once per pass, not once per record).

### 4. Outbox drain bounded

`ebx.DistributionDispatcher.drainTarget`:

- **Read cap**: `DRAIN_BATCH_CAP` (1000, a hard-coded constant - see its own Javadoc for why NOT a `Settings`
  field: that would be an XSD change to `MdmFactory.xsd`, out of this lot's file-ownership boundary). The
  initial PENDING-row scan stops after this many rows READ (not groups - a burst on one record can produce
  fewer groups than rows), closes the `RequestResult`, and logs one line if it was capped; the rest drains on
  a later pass (every 10 s).
- **History/delete batched to one `Procedure` each per pass**: a new `CURRENT_WRITES` `ThreadLocal` accumulator
  (`DrainWrites`) - every `writeHistory`/`deleteOutboxRows` call during one `drainTarget` pass appends to it
  instead of opening its own transaction; `drainTarget` flushes it in EXACTLY TWO transactions (one history
  write for the whole pass, one delete for the whole pass - either skipped entirely when its list is empty)
  once every group has been decided. Both leaf methods fall back to their OLD immediate-`Procedure` behaviour
  when called with no accumulator set (defensive - never expected to trigger live, since `drainTarget` always
  sets one, but a future caller added elsewhere in this class must not silently lose a write). The DECISION
  logic (which branch runs, what gets sent, skipped, retried, folded) is completely UNCHANGED - only WHEN the
  already-decided writes actually hit the database changed.
  - **Accepted trade-off, documented on `CURRENT_WRITES`'s own Javadoc**: deferring every group's write to the
    end of the pass means a crash mid-pass loses the WHOLE pass' history/deletes, not just the groups not yet
    reached - the next pass re-sends every record the crashed pass had already, successfully, sent. Judged
    safe because every send in this class is a re-sync of the record's CURRENT state, keyed by its own primary
    key (`connectors_sync`/`connectors_call`), not a create-only operation - a re-send after a crash is
    redundant, not duplicating data. The pre-lot per-group-immediate-commit design never had to make this
    trade-off; this is a real, if judged-acceptable, regression in partial-crash behaviour.
- **`closeCrossPassSuperseded` partially addressed, flagged for the coordinator**: the WRITES it triggers are
  now batched into the same pass-wide flush as everything else (they go through the same `writeHistory`/
  `deleteOutboxRows`), but its own READ (one XPath predicate query per successfully-sent CREATE/MODIFY group)
  is UNCHANGED - still one query per group, not merged into a single pass-wide query across every sent group's
  `(tablePath, recordKey)`. A correct, bounded OR-predicate covering an arbitrary number of distinct keys (up
  to `DRAIN_BATCH_CAP` groups) felt like real, never-live-tested risk for one remaining query-count reduction,
  once the two actual per-record WRITE transactions (the brief's own biggest cost items) were already removed.
  See the method's own Javadoc for the shape a real fix would take (reuse `drainTarget`'s own initial scan,
  without its `nextAttemptAt` filter, bucketed by key, instead of re-querying per sent group).
- **Batch sending via `connectors_sync` - checked, not available**: `mcp__ebx-connectors__connectors_sync`'s
  own tool schema (read directly, 2026-09-28) takes `key` (ONE record's primary key, singular string), never a
  list - `predicate`/`scope` exist for a different "whole table, filtered" shape, not "these N specific
  records in one call". Kept one `connectors_sync`/`connectors_call` per record, as the brief's own fallback
  instruction says to when the tool does not support a list.
- **Not verified live**: none of the above - no EBX/Connectors instance was reachable this session. In
  particular, whether `DRAIN_BATCH_CAP`'s value (1000) is right for a real target's throughput, and whether
  the two-Procedure-per-pass shape holds up against a genuinely large backlog, were reasoned about, not
  measured.

### 5. Retention

`FactoryRetention.purge` gained two more tables, both genuinely volume-scale (one row per outbound event / per
rejected staged row, unlike the model-scale tables it already purged): `DistributionEvent` (permanent SENT/
SKIPPED/FAILED history - every row is already terminal, purged on `createdAt` alone) and `TransferReject`,
**RESOLVED rows only**, on `rejectedAt` (an unresolved reject is still an open item a data steward has to act
on - never purged regardless of age). Neither read touches a `detail`-sized field the way the existing
`PipelineStep` purge does (`creationProof`) - both are a plain date/flag predicate, batched the same way
(transactions of `FactoryRetention.BATCH` = 500) as every table already purged. `ProjectDeleter` gained the
matching `DistributionEvent` entry in its own per-project table list, using the exact `VolumeBatchOps` pattern
lot 2a already established for `Xref`/`TransferReject` (streamed to primary-key strings, batched deletes of
1000, counted via `RequestResult.getSize()` for `plan()`) - reused, not redeveloped.

### 6. Landing mappings use the new Connectors properties

`landing/LandingFlow.java`'s three mapping generators (`mapping` - the SQL/root shape, `feedMapping`,
`recipeMapping`) each now set `"provenance": false` and `"unchangedIfEqual": "./_rowHash"` on every mapping
document. See `docs/LANDING-FLOWS.md`'s own "Lot 8a" section for the full reasoning and the documented
behaviour change for `_loadId`/`_receivedAt`/`_status` on an unchanged row (short version: an unchanged row is
no longer touched at all, which is the correct behaviour for incremental staging, but means those two
timestamp fields now mean "when this row's data last actually changed" rather than "when it was last seen").
`LandingFlowTest` extended with a `provenance`/`unchangedIfEqual` assertion in the three shape-pinning tests
(`checkMappingShapeWithKey`, `checkFeedMappingWithKey`, `checkRecipeMappingShape`). `ebx.LandingFlows` needed
no change - it already calls `LandingFlow.mapping(...)` verbatim and posts whatever it returns.

### New REST contract summary

Every route in task 1's table (plus `POST .../reset`, `POST .../delete`, `POST .../master/reset` from task 2)
now answers, by default, `202 Accepted` with:

```json
{"started": true, "jobId": "123", "merged": false, "position": 1, "statusUrl": "/projects/<code>/jobs"}
```

`merged: true` means an identical request already queued absorbed this one (existing `FactoryJobQueue`
behaviour, unchanged); `position` is 1-based among the active jobs of the same resource. Poll `statusUrl`
(already used by the wizard) and match on `jobId`. Every one of these routes still answers its EXACT pre-lot
synchronous body when called with `?sync=true` (e.g. `POST .../staging/load?scope=new&sync=true`).

### Settings

No new `Settings` field was added by this lot (`DRAIN_BATCH_CAP` is a hard-coded constant, deliberately, per
its own Javadoc - a `Settings` field would need an XSD change to `MdmFactory.xsd`, outside this lot's
file-ownership boundary). No restart is needed for anything in this lot beyond the usual "restart Tomcat to
load new classes" rule - no schema/model change anywhere in it.

### What was NOT verified (nothing in this lot runs live)

- Task 1: every `?sync=true` fallback path is a straight passthrough to already-existing, already-documented
  synchronous methods - not re-verified live, but nothing about their own behaviour changed. The new `202`
  paths (job enqueue) were not exercised against a live job dispatcher.
- Task 2: `FactoryJobs.claimAll`'s multi-key claim/release/cancel/touch semantics were reasoned through
  carefully (see the class' own Javadoc) but never exercised against a live concurrent job engine - in
  particular, whether a whole-project job queued behind a continuous stream of smaller single-resource jobs
  could starve was not measured (a real, if likely rare, possibility this design does not prevent).
- Task 3: `ArmedTargetCache` itself is pure and unit-tested; its invalidation call sites were reasoned about
  against the write methods' own code, never exercised live. As documented above, it is not wired into the
  trigger body at all this lot.
- Task 4: none of `DRAIN_BATCH_CAP`, the two-Procedure-per-pass flush, or the fallback-to-immediate path were
  run against a live dispatcher pass.
- Task 5: the two new retention queries were written against the exact `FactoryPaths` field names/existing
  `keys()`/`delete()` helpers, never run against a live `FactoryJob`/`TransferReject` table with resolved rows
  to purge.
- Task 6: the two new mapping properties were never round-tripped through a live `connectors_mapping_upsert`/
  `connectors_sync` call - their names and meaning come from the Connectors lot 5 brief this lot was handed,
  not from an independent live probe.

Deploy this branch and exercise each item against a real project before relying on it at volume.

## Lot 3 - loader memory

Written 2026-09-28 on branch `feature/volume-loader-memory` (worktree `ebx-mdm-factory-volume-3`), from
`integration/volume-wave1` `9af356a7` (already carrying lots 1b, 4 and 2a above). Task: the two structures the
2026-09-28 audit found still growing with the RUN'S OWN ROW COUNT inside `ebx.StagingLoader`/`ebx.MasterLoader`
themselves (as opposed to lot 2a's `Xref`/`TransferReject` lookups, or lot 4's screen reads) - a landing-status
map kept for the whole run, a whole-table scan for `UNIQUE`, an unbounded reject buffer, a per-row metadata
read, and a family of small per-row `Path.parse`/`deepCopy` costs.

**One correction to this file's own earlier note (lot 1b, item 7):** live-tested 2026-09-28 against a real
instance (dataspace `A_Staging`, table `Personne`, 345 rows) via the `data-access` MCP server's
`execute_query`/`get_query_syntax`: **plain `LIMIT n` is directly supported** on this build - the syntax
reference itself lists `LIMIT n` (checked), `OFFSET n ROW[S]` (checked), `FETCH NEXT/FIRST n ROW[S] ONLY`
(checked), only the two-argument MySQL form `LIMIT offset, count` is rejected. Lot 1b's claim that EBX SQL
only had `OFFSET...FETCH` was itself based on a `get_query_syntax` read, so this may be a difference between
builds/instances rather than a plain error - treat CLAUDE.md's note the same way and re-verify per instance,
per its own "build-specific" discipline.

### 1. Landing status per batch (`transfer.LandingTableFanout`, `ebx.StagingLoader`)

Before this lot, `StagingLoader.PendingLandingStatus` accumulated every root feed's `_status` decision
(`Map<landing row key, STAGED|REJECTED>`) for the WHOLE run, for every landing table, only writing it back
once every entity's every feed had finished - roughly 1 GB at 5M rows (one map entry per row, string key +
string value + `LinkedHashMap.Entry` overhead). This existed to fix a real, previously-live bug: a landing
table read by MORE THAN ONE root feed in the same run (a canonical entity and one of its members both reading
the same landing table) must not have its rows marked by the FIRST feed to touch them, or the SECOND feed's
own `_status='NEW'` read would find nothing left (measured live pre-lot-1: `Company` read 0 where scope `all`
on the same data read 107).

**The rule this lot applies, explicitly** (as the task brief asked for): *a landing table read by exactly ONE
root feed this run writes its `_status` back per BATCH, immediately, no accumulation at all - a landing table
read by MORE THAN ONE root feed this run keeps the pre-lot whole-run accumulation, unchanged.* This is computed
ONCE per run, before any row is read: `LandingTableFanout.countReaders` walks `plan.loadOrder`'s root feeds
(one key per feed, `landingDataspace|landingDataset|landingTableName` - the same key `PendingLandingStatus`
already used), and `LandingTableFanout.isShared` answers the question per table. `StagingLoader.loadRootFeed`
then branches per batch: `sharedLandingTable` (computed once per feed, not per row) sends the batch's decisions
into the unchanged `pPendingLandingStatus.merge` path; otherwise `StagingLoader.writeLandingStatus` is called
directly on that batch's own (bounded, `pBatchSize`-sized) map, and nothing about this table is kept in memory
across batches.

**Why this is safe without re-deriving REJECTED-wins-over-STAGED for the common case**: when exactly one feed
reads a landing table this run, there is no SECOND feed's decision to merge against - the row is decided once,
by the only feed that will ever touch it this run. The multi-feed merge rule (`IncrementalTransfer.mergeLandingStatus`,
unchanged) is only needed, and only ever invoked, for the shared case.

**Memory**: for a project with no canonical/member sharing at all (the common case, most projects) this
removes the ~1 GB structure entirely - the pending map size is bounded by `pBatchSize` (default 500) for the
duration of one batch, not the run's total row count. **Residual, documented gap**: a landing table genuinely
shared by two-or-more root feeds still accumulates for the WHOLE RUN, exactly as before this lot - its memory
cost is bounded by THAT table's own row count, not the whole project's, which is a real, if narrower, risk for
a canonical structure whose shared source table itself has millions of rows. Fixing that residual case cleanly
(without a landing-schema change to carry a "resolved by the last reader" marker field, which was considered
and rejected as out of scope for a loader-only lot) would need either a second read pass or a technical field
on the landing table added by `LandingGenerator` - flagged for the coordinator, not attempted here.

New pure class `transfer/LandingTableFanout.java` (+ `LandingTableFanoutTest.java`): `key`, `countReaders`,
`isShared` - reader counting and the shared/not-shared decision, unit-tested without EBX (single reader, two
readers, several independent tables, an empty run, an unknown key defaulting to "not shared", the cheaper and
safer default).

### 2. UNIQUE rule in SQL (`ebx.StagingLoader.runUniqueCheck`)

Before this lot, `runUniqueCheck` read the WHOLE staging table (not merely this run's rows) into a
`List<QualityRuleCheck.RowValue>`, then grouped it in Java (`QualityRuleCheck.duplicatesAmong`) - O(table size)
memory for every `UNIQUE` rule checked, every run, regardless of how many rows actually loaded this time.

**Replaced with three bounded SQL round trips**, all validated LIVE 2026-09-28 (dataspace `A_Staging`, table
`Personne`, 345 rows, column `lastName`, via the `data-access` MCP server's `execute_query`) - every shape ran
verbatim, no fix needed:

1. `SELECT COUNT(c."<field>") AS N FROM "<Entity>" c` - the non-null value count (`checked`). Measured:
   `CHECKED = 345`.
2. `SELECT SUM(t.N) AS N FROM (SELECT COUNT(*) AS N FROM "<Entity>" c WHERE c."<field>" IS NOT NULL GROUP BY
   c."<field>" HAVING COUNT(*) > 1) t` - the EXACT total failing row count across every duplicated value,
   never capped, computed entirely server-side (`failing`). Measured: `FAILING = 324` (matches the sum a
   full-table group-by would give, without materializing a single row of it in Java).
3. `SELECT c."<field>" AS V, COUNT(*) AS N FROM "<Entity>" c WHERE c."<field>" IS NOT NULL GROUP BY
   c."<field>" HAVING COUNT(*) > 1 LIMIT 11` for up to 11 duplicated VALUES (11, not 10 - a safety margin for
   the worst case of every group being exactly the minimum size 2, needing up to 5 groups to gather 10 keys),
   then, per value in turn, `SELECT c."$pk" AS PK FROM "<Entity>" c WHERE c."<field>" = '<value>' LIMIT
   <remaining>` for up to `MAX_QUALITY_RULE_EXAMPLES` (10) example row keys total. Measured: `LIMIT 11` worked
   with no error (confirms the correction above); a `$pk`-based lookup for one duplicated value returned 5
   correctly pipe-delimited staging keys.

The reported shape (`RuleRunCounters`: `checked`, `failing`, `exampleKeys` capped at 10) is unchanged - only
how the three numbers are computed. Falls back to the original, unbounded-but-correct scan
(`runUniqueCheckByScan`) when the entity/attribute name is not SQL-quotable (should never happen - both come
from this factory's own generated identifiers) or when any of the three queries fails outright (a transport
error, or - documented, not measured, the same residual uncertainty `ebx.MatchPlanSimulationRunner` already
carries for an analogous value-based `WHERE` equality - a non-string `UNIQUE` column the string-literal
comparison in step 3 does not coerce against; steps 1-2 use no value literal at all, so `checked`/`failing`
stay accurate even if step 3's examples come back empty for such a column).

**Not verified live**: the exact failure/fallback path (an unquotable name, a genuinely non-string `UNIQUE`
column) - only the happy path was exercised, on a `STRING` column, since that is what every `UNIQUE` rule
proposed by this codebase's own LLM pass targets in practice.

#### Lot M11 (2026-10-18): a DATE key killed the load; the examples are an advisory now

**Measured.** Project `E2EMASTER20261018070131`, job 642 (`STAGING_LOAD_RUN`, 09:05:50): `ExceptionInInitializerError at BazCte_0.enumerator ... StagingLoader.queryUniqueExampleKeys` caused by
`IllegalArgumentException: Invalid DATE value, 'Sun Apr 01 00:00:00 CEST 2018'`. Query 3 above wrote the value of the duplicated group as `'<String.valueOf(value)>'`; for a date column that
is the `java.util.Date` text, which the query engine compiles into a DATE constant in generated code and refuses. Two faults made one bad literal fatal: (1) the literal ignored the column's type;
(2) the failure is an **Error** (the static initialiser of the generated class), which the `catch (RuntimeException)` of every query in the unique check let through to `loadInternal`.

**Now.** The literal follows the column kind (`StagingLoader.sqlLiteralFor(value, kind)`, the kind read from the staging field: `uniqueColumnKind`): `DATE 'yyyy-MM-dd'`, `TIMESTAMP 'yyyy-MM-dd
HH:mm:ss[.SSS]'`, `TIME 'HH:mm:ss'` (the date text in the JVM's zone, how EBX reads a date), a bare number for decimal / integer, `TRUE`/`FALSE`, a quoted string with the apostrophe doubled for anything
else; with no kind known the Java class of the value decides (a `Date` with a time of day is a TIMESTAMP, one at midnight a DATE, one on 1970-01-01 a TIME). The example gathering
(`gatherUniqueExamples`) never throws: an `Exception` or a `LinkageError` from either read stops it, keeps the keys already found and records the reason (root cause, one line, 200 characters at most);
the load writes a line in the step detail, `<Entity>: unique check on <attribute>: examples unavailable (<reason>).` (bundle key `wizard.runtext.stg.uniqueExamplesUnavailable`), and goes on. `checked` and
`failing` come from queries 1-2, which carry no literal, and they too now turn a `LinkageError` into the scan fallback. Tested without an instance: `StagingLoaderTest` (every literal kind, a gathering whose
read throws an `ExceptionInInitializerError`). **Not verified live**: the engine's acceptance of `DATE '...'` / `TIMESTAMP '...'` against a staging date field; the fix only guarantees that a refusal costs the
examples, never the load.

### 3. `rejectBuffer` bounded (`ebx.MasterLoader.loadEntity`)

The flush-threshold check was `insertBuffer.size() + updateBuffer.size() >= pBatchSize` - `rejectBuffer` was
never counted. An entity whose rows are MOSTLY rejected (a systematic data problem - a broken mapping, a
mis-declared script constraint) could keep `insertBuffer`/`updateBuffer` small forever while `rejectBuffer`
grew without bound for the whole entity's run. Fixed: `insertBuffer.size() + updateBuffer.size() +
rejectBuffer.size() >= pBatchSize` - the exact one-line fix the audit named. `ebx.StagingLoader` was checked
for the same pattern and does NOT have it: `FeedBuffers.size()` already sums `stage + reject + unchanged`, and
that combined size is what triggers `loadRootFeed`'s own flush - it was already bounded before this lot, not a
false negative in the audit but a spot-check the task brief itself asked for ("both loaders if the same
pattern exists in StagingLoader") that came back negative.

### 4. Validation pre-pass (`quality.ScriptConstraintCheck.expectedKeys`, `capability.MeasurementMatch.key`, `ebx.MasterLoader`)

**What the brief asked for literally - a Set of failing staging keys computed once via SQL/XPath, replaced by
a per-row Set lookup - was investigated and is NOT generally achievable**, and this lot does not pretend
otherwise: `fix/measure-observability` (referenced in this class's own pre-existing Javadoc) already tried the
table-wide validation-report overload and found it can MISS record-level items; a `SCRIPT_CONSTRAINT` calls an
arbitrary published EBX Script function (`Fasttrack.lib.phone.isValid` and friends), which has no SQL or XPath
equivalent to precompute against. Re-introducing the table-wide report to get a "compute once" shortcut would
reintroduce a known-bad, already-rejected approach - not done.

**What WAS implemented is the safe half of the brief's own fallback instruction** ("call it only for rows
whose entity has an active REJECT rule AND cache/skip when the report is empty cheaply") - other than
literally skipping the report read for WARN-only entities (rejected as a real behavior change that would
silently stop maintaining `CapabilitySuggestion.lastLoadFailing` counters for WARN-only rules at scale; not
done without asking, see "flagged for the coordinator" below): `pStagingRow.getValidationReport()` is still
read once per row (unavoidable - it is the only reliable source), but `item.getMessage().formatMessage(pLocale)`
- the actually expensive step (renders a full message, potentially via a script's own message-building
concatenation) - is now called ONLY for items whose (severity, path) could possibly match ONE of this entity's
own rules, instead of for every WARNING-or-above item on the record. `MeasurementMatch.key(severityLabel,
path)` (new, pure, reuses the SAME `normalizePath` `onExpectedPath` already applies, so the coarse filter and
the real gate agree exactly) builds a stable key; `ScriptConstraintCheck.expectedKeys(rules)` builds the set
of every key this entity's own rules could ever fire on, ONCE per entity (not per row); `MasterLoader.readReportItems`
looks each item's own key up in that set before formatting. An item that fails the coarse filter can never pass
`firingMessage`'s own `onExpectedPath` gate either (both use the identical key), so leaving its
`renderedMessage` null is provably safe, not merely a probabilistic optimization.

**Flagged for the coordinator**: would you accept dropping `getValidationReport()` entirely (and with it,
`lastLoadChecked`/`lastLoadFailing` accuracy) for entities whose script rules are ALL `WARN` policy, to save
the per-row report read for that case? Not done here since it is a real behavior change to a documented
feature ("WARN keeps the row and only feeds recordScriptRuleCheck's own counters"), not merely a performance
detail - a call only the coordinator should make.

New tests: `MeasurementMatchTest` (`keyAgreesWithOnExpectedPathOnMatchingPairs`,
`keyDisagreesWhenSeverityOrPathDiffer`, `keyNormalizesPathTheSameWayOnExpectedPathDoes`), `ScriptConstraintCheckTest`
(`expectedKeysOneEntryPerDistinctSeverityPathPair`, `expectedKeysEmptyForNoRulesOrNull`).

### 5. Light merge check (`ebx.MameRecordState`, `ebx.MasterLoader.MergeCheck`)

Two independent fixes, both real, both bounded (not "growing" per se, but O(1)-per-row costs paid on every
single UPDATE row regardless of whether matching was ever configured):

- **`MameRecordState.readStateOnly`** (new, default method delegating to the full `read` unless overridden;
  `AddonMameRecordState` overrides it with a single `MetadataHelper.getState` call): `MasterLoader.RecordMetadataMergeCheck.isMergedAway`
  only ever needed the record's `state` name, never the group id/auto-created flag/golden target the full
  `read()` also fetches (`getGroupId`, `isAutoCreated`, and - only when `MERGED` - `getTargetRecord`) - three
  wasted add-on calls per UPDATE row, every one of them, before this lot. `goldenOf` (which genuinely needs the
  golden target) still uses the full `read()`.
- **`MergeCheck.NEVER_MERGED`** (new, a default interface constant): before this lot, `ebx.MasterLoader.loadInternal`
  built a REAL `RecordMetadataMergeCheck` (a master-table resolution at construction, a record lookup plus an
  add-on call per UPDATE row) for every promoted entity, unconditionally - even for the overwhelmingly common
  case of a project that has a master zone but has never yet run matching (matching is a LATER wizard step; the
  master table itself exists and resolves fine regardless of activation, so the pre-existing "table not found"
  fallback never actually triggered for this case). Now gated on the exact same fact `ParentKeys#matchingActivated`
  already uses for its own, narrower add-on-hop skip (lot 2a): `MatchPlan.status == APPLIED` for the entity,
  checked once per entity via the existing `readMatchPlanStatus` helper. Not activated -> `MergeCheck.NEVER_MERGED`,
  which answers `false` for every key without touching the add-on OR the master table at all - correct, not a
  fallback, since nothing could possibly be merged away yet.

**Not implemented, deliberately, and documented as a real residual gap**: "reuse the master `Adaptation`
already fetched instead of a second PK lookup in `upsertOne`" (the task brief's own phrasing). The candidate
"already fetched" `Adaptation` comes from `RecordMetadataMergeCheck.isMergedAway`'s own lookup, done during the
READ loop (`planRow`), while `upsertOne`'s own lookup happens later, inside a DIFFERENT `Procedure`, at flush
time. Every other cross-procedure reuse in this codebase passes an `AdaptationName` forward (`pContext.getContext(name)`),
never a raw `Adaptation` object, into a later procedure's `doModifyContent` call - reusing the raw object itself
was not verified safe against this instance's `ebx-lib.jar` (no live write access to confirm `doModifyContent`
behaves identically with an `Adaptation` resolved by an earlier, now-closed read), and the saving (one indexed
PK lookup per UPDATE row - already O(1), not a "growing" structure) did not seem to justify the correctness
risk of guessing at unverified EBX API behavior. Flagged for the coordinator rather than implemented on a guess.

### 6. Rejects deduplicated (`tools/build_xsd.py`, `FactoryPaths.TransferReject`, both loaders)

`TransferReject`'s primary key is now the natural key `(project, entity, stage, sourceSystem, sourceId)` -
same recipe as lot 2a's `Xref`. The `id` field is gone entirely (`FactoryPaths.TransferReject.ID` removed);
every reader was grepped (`FactoryCockpitUserService` sorted by `id` descending as a cheap proxy for
`rejectedAt` descending - switched to sorting by `rejectedAt` directly, which is both correct now and was
always the semantically right field; `StepResetter`/`ProjectDeleter`/`StagingLoadGridReader`/`MasterBuildGridReader`/
`WizardSummaryReader` all filter by `project`/`stage`/`entity`, never by `id` - unaffected).

**Both loaders now upsert by that key** instead of always inserting: `writeRejects` looks the row up by
`(project, entity, stage, sourceSystem, sourceId)` first; found, it updates the SAME row (new `reasons`, new
`rejectedAt`, `resolved` reset to `false`) instead of creating a new one. A row that keeps failing the same way
across many runs no longer accumulates one reject row per run forever.

**Resolving a fixed row**: "when a row loads successfully at a stage, mark its earlier reject of that stage
`resolved=true`" - implemented as a CHEAP, provably-scoped check rather than an unconditional per-successful-row
lookup, using a signal already available where the write happens:

- `StagingLoader`: `RowPlan` gained a mutable `inserted` field, set by `upsertOne` (`existing == null`) - the
  SAME distinction `MasterLoader`'s insert/update buffers already made structurally. `writeXref` (which already
  runs, once per written row, in the SAME procedure that just wrote the Xref row) does the resolve-lookup ONLY
  when `row.inserted` - an UPDATE row was already staged successfully before, so it can never have an open
  STAGING-stage reject; only a first-time insert might be resolving one.
- `MasterLoader`: the insert/update distinction already existed (`RowResult.inserted`, from which buffer the
  row came from) - no new field needed. Same rule, same placement (inside `writeXref`'s existing procedure),
  same one-extra-indexed-lookup-per-INSERT cost.

Both cases add exactly one indexed PK lookup per newly-inserted row (never per update, never a scan) - bounded,
consistent with everything else these loaders already do per row.

**Deployment**: this is a structural (primary-key) change, same posture as lot 2a's `Xref` - **existing
projects must be deleted before this branch is deployed** (product owner decision, stated in the task brief;
no migration path exists for an old auto-increment `TransferReject` row under the new schema, same reasoning
lot 2a's own deployment section gives for `Xref`). Regenerated `MdmFactory.xsd` via `tools/build_xsd.py`
(`xmllint --noout` clean); the diff is exactly the primary key line and the removed `id` element, nothing else
in the table moved.

### 7. Per-row CPU

- **`Path` objects precomputed per entity/feed, not per row**:
  - `ebx.StagingLoader.buildRowDocument`: new `FieldRef` (name, `Path`, `SchemaNode`) and `fieldRefsOf(SchemaNode)`,
    computed ONCE per landing table (`loadRootFeed`, before the row loop) - the pre-lot version re-walked the
    table's schema and re-ran `Path.parse` for EVERY field of EVERY row. The one-argument overload
    (`buildRowDocument(Adaptation)`) is kept, delegating to the two-argument form, for `ebx.MappingMatrixReader`'s
    own bounded "before -> after" sample - not worth precomputing for a caller that never reads more than a
    handful of rows.
  - `ebx.MasterLoader.loadEntity`: a `Map<String, Path> fieldPaths` built once per entity (every master field
    name, every staging field/staging-Ref-field name), used by `planRow` (reading staging values) and
    `upsertOne` (writing master values, threaded through `MasterAttempt`) instead of `Path.parse("./" + name)`
    per field per row. A missing cache entry (should not happen - defensive) falls back to a fresh parse rather
    than throwing.
- **`deepCopy` skipped when a feed never cleans anything**: `FeedContext.hasCleaningSteps` (new, computed once
  in `prepareFeed`) - `processRow`'s own cleaning-pipeline copy (`pDocument.deepCopy()`) is now only made, and
  the cleaning loop only entered, when the feed has at least one writable field with a non-empty cleaning
  pipeline. Most CHILD feeds (and many root feeds nobody ever proposed a cleaning step for) skip this entirely.
  **Not changed, documented rather than guessed at**: `computeChildren`'s own per-child-feed `deepCopy` (giving
  each child its own document plus a `_parent` back-reference) was evaluated and left as-is - landing rows are
  flat by construction, and Jackson's `ObjectNode.deepCopy()` on a flat structure is cheap (immutable leaf
  nodes like `TextNode` return `this` from their own `deepCopy()`; only the outer container is actually
  rebuilt), so the real cost here is O(row width) per child feed instantiation, not the unbounded-growth class
  of problem this lot otherwise targets. Removing it safely would need giving each child feed either a shared
  read-only view with copy-on-write semantics or a schema guarantee that no child ever mutates a field the
  parent (or a sibling) also reads - neither was judged safe to build in the time this lot had; flagged as a
  smaller, lower-priority follow-up rather than attempted on a guess.
- **`PATTERN` rules compiled once, not per row**: `QualityRuleCheck.Rule` gained a `compiledPattern` field
  (private, `Pattern`), compiled ONCE in the constructor from the rule's own `argument` - `null` uniformly for
  "no pattern stored" and "malformed pattern" (both already degraded to "always passes" identically before this
  lot, so collapsing them into one sentinel changes no behavior). `matchesPattern` now takes the pre-compiled
  `Pattern` instead of calling `Pattern.compile` on every value of every row. Existing `QualityRuleCheckTest`
  (constructed via the same public `Rule` constructor, asserting only on `passes()`'s boolean outcome) passed
  unchanged, confirming no behavioral regression.

### 8. Cancellation + tracking info (`ebx.FactoryJobs.Handle`, both loaders, `StagingLoadRun`/`MasterBuildRun`)

**Cancellation**: `StagingLoader.load`/`MasterLoader.load` gained a new overload taking a nullable
`FactoryJobs.Handle` (every synchronous caller - the REST route, a workflow task bean - passes `null` via the
existing overloads, unchanged); `StagingLoadRun`/`MasterBuildRun` (the only callers that ever hold a real
`Handle`, since they already run as `FactoryJobQueue` jobs) now pass theirs through. Checked once per BATCH
(right after that batch's flush/write-back, a clean point where nothing partially written is ever left behind)
in: `StagingLoader.loadRootFeed`'s row loop, `StagingLoader.resolveDeferredReferences`'s pass-2 loop,
`MasterLoader.loadEntity`'s row loop, `MasterLoader.resolveDeferredReferences`'s pass-2 loop - plus once more
between entities/feeds in each `loadInternal`, so a run made of very small/short feeds still stops promptly. A
stop mid-run unwinds cleanly: the current method returns `false` ("not completed"), every caller up the chain
stops entering further feeds/entities/FK edges, and the run's own `PipelineStep` ends `STOPPED` (via the
existing `PipelineSteps.endStopped`, status `STOPPED` - **not** a new `INTERRUPTED` status as the task brief's
own wording suggested: `INTERRUPTED` is already a distinct, reserved meaning in this codebase, restricted to a
`PipelineStep` orphaned by a SERVER RESTART - `FactoryJobs.INTERRUPTED`/`interruptOrphans` - while `STOPPED` is
the existing, correct status for "a person or the time limit asked this to stop", exactly what `pHandle.cancelled()`
means; reusing the established convention rather than introducing a second, overlapping status was the more
correct reading of the brief's intent, not a literal one) with a message naming `pHandle.stopReason()` and
whatever counts were reached so far - never rolled back, same "keep what's written" posture `BatchCircuitBreaker`
already established.

**A real integration bug found and fixed while wiring this up**: `StagingLoadRun.runStage`/`MasterBuildRun.runStage`
(the `*Run` job wrappers) only ever treated a stage as "stopped" if it THREW while `pHandle.cancelled()` was
already true BEFORE the stage started - they had no way to notice a stop that happened MID-STAGE, because
`StagingLoader.load`/`MasterLoader.load` return NORMALLY (with a partial result) on a mid-batch stop, never an
exception. Before this lot's own cancellation checks existed inside the loaders, this gap was latent (a stop
could only ever land between stages); now that a stop can land mid-load, `runStage` was fixed to re-check
`pHandle.cancelled()` immediately after `pStage.run()` returns, and treat a stop-during-the-stage the same as a
failure for THIS stage's own accounting (so the run's own final `if (modelOk && loadOk)`/`if (tablesOk && copyOk)`
check - which already knows how to render `pHandle.cancelled()` as "stopped", not "failed" - takes over
correctly) rather than reporting a partial run as a clean "finished".

**Tracking info**: the task brief's own text named `ProcedureContext.setTrackingInfo(String)` - **verified via
`javap` against this instance's own `ebx-lib.jar` (2026-09-28) that no such method exists**; `ProcedureContext`
has `setExecutionInformation(String)`/`getExecutionInformation()` instead (`Session` separately has a
READ-ONLY `getTrackingInfo()`, set only at session creation, not per-procedure - not usable here since these
loaders run under the caller's existing session, never minting a new one per batch). `pContext.setExecutionInformation(
"mdm-factory:<stepId>:<stage>:<entity>")` is now called at the start of every Procedure in the hot per-batch
write path of both loaders: the main upsert (`StagingAttempt`/`MasterAttempt`, both `tryBatch` and `tryOne`),
`writeRejects`, and `writeXref` (both loaders, all threaded from the `PipelineStep` id `loadInternal` already
creates). **Deliberately not threaded further**: pass-2's `flushRefUpdates` and the end-of-run bookkeeping
writers (`writeEntityCounters`, `writeQualityRuleCounters`) run once per run (or a handful of times), not once
per batch of a potentially 5M-row load - the monitoring value of tagging them was judged not to justify the
extra parameter-threading for this lot; flagged as a small, low-priority follow-up.

### What was NOT verified (this session's live access)

- Item 1: the shared-vs-single-reader split was reasoned from the existing (already-live-measured, lot 1's own
  bug fix) multi-feed scenario, but the NEW per-batch direct write-back path itself was never run against a
  real multi-entity project this session.
- Item 2: only the happy path (a `STRING` `UNIQUE` column) was validated live; the fallback-to-scan path
  (an unquotable name, a non-string column, a query failure) was reasoned about, not reproduced.
- Item 3: the fixed threshold was not exercised against a real systematic-rejection scenario live.
- Item 4: the coarse-filter optimization was validated by the existing pure test suite (`ScriptConstraintCheckTest`,
  `MeasurementMatchTest`) and by reasoning about `MeasurementMatch`'s own existing, live-derived `normalizePath`
  rule - never re-run against a live staging record carrying both a script-constraint warning and an unrelated
  one.
- Item 5: `MameRecordState.readStateOnly`/`MergeCheck.NEVER_MERGED` were not exercised against a live master
  zone (with and without matching activated) this session.
- Item 6: the upsert-by-natural-key and resolve-on-insert logic were not run against a live re-run of a
  previously-rejected row.
- Item 7: the `Path`-caching and `Pattern`-precompile changes are behavior-preserving by construction (same
  values, same lookups, only WHEN they are computed changed) and covered by the existing pure test suites
  (`QualityRuleCheckTest` unchanged and green) - never measured for an actual wall-clock improvement.
- Item 8: the cancellation checks and the `runStage` mid-stage-stop fix were not exercised against a real
  running job being stopped mid-batch; `setExecutionInformation`'s effect was not checked against EBX's own
  execution-monitoring screen.
- The `tools/test_local.sh` run (XSD regeneration diff clean, compiles against the real `ebx-lib.jar` of
  `EBX_LIB_DIR`, all 257 pure unit tests pass including the new `LandingTableFanoutTest` and the extended
  `MeasurementMatchTest`/`ScriptConstraintCheckTest`) is the only verification this lot has had beyond the one
  live SQL-syntax check (item 2) and the one live `javap` check (item 8's tracking-info correction). Deploy
  this branch (after deleting every existing project, per item 6's deployment note) and exercise each item
  against a real project before trusting it at volume.

## Lot 8b — distribution

Written 2026-09-28 on branch `feature/volume-distribution` (worktree `ebx-mdm-factory-volume-8b`), from
`integration/volume-wave1` `c2ef47f2` (carrying lots 1b, 4, 2a, 8a and 3 above). Task: close the two distribution
gaps lot 8a's own task 3 investigated and reported as infeasible as literally asked, plus the operational gaps
its task 2/3 left for a UI-owning lot. Same posture as every lot above: no EBX/Connectors instance was reachable
this session; `tools/test_local.sh` is the only verification (`xmllint --noout` run by hand on
`DistributionOutbox.xsd`, since `tools/build_xsd.py` only generates `MdmFactory.xsd`).

### 1. Trigger short-circuit while distribution is paused

Lot 8a's own `ArmedTargetCache` (task 3) is real, tested, and correctly invalidated - but unreachable from the
generated trigger script (EBX Script cannot call an arbitrary custom Java static method, CLAUDE.md sec 2), so
`DistributionTriggerBody`'s SQL query against `factory."DistributionTarget"` ran on every master-row save
regardless. **What a script trigger CAN do cheaply is a plain primary-key lookup** - so this lot adds a new
table, `Control` (`DistributionOutbox.xsd`, one row per project, PK `project`, fields
`armedRealtimeTargets`/`updatedAt`), in the SAME per-project outbox dataset the trigger already writes into.
The generated trigger now reads this row FIRST (`db.lookupRecord`, no SQL) and returns at once when it is
missing or the count is `0`; the old SQL fallback is UNCHANGED and still runs whenever the short-circuit does
not return early.

**The invariant that makes a missing/zero row safe (never fail-open)**: a target must never become `ARMED`
(REALTIME) before this row already counts it. Two mechanisms maintain it, both new this lot:

- **`distribution.ArmedRealtimeChange`** (pure, tested) - classifies a target's `(mode, state)` transition as
  `NONE`/`ARM`/`DISARM`. `ebx.DistributionControlRow.beforeStateChange`/`.afterStateChange` are wired into the
  EXACT four write paths lot 8a already wired to `ArmedTargetCache.invalidate` (`DistributionPause.setState`,
  `DistributionRunner`'s `writeTargetState`, `DistributionReader.setState`/`.upsertTarget`). The two calls are
  deliberately asymmetric: an `ARM` transition bumps the row (live count + 1, since the live query still
  reflects the target's old, non-counting state) BEFORE the target's own `state` field commits - never after,
  which would leave a window where a record saved in the gap reads a stale, too-low count and the trigger
  wrongly skips a genuinely-armed target's event. A `DISARM` transition refreshes the row (a fresh recount)
  AFTER the commit - safe the other way, since a stale, too-HIGH count only costs one harmless extra SQL query,
  never a lost event. See `ArmedRealtimeChange`'s own Javadoc for the full reasoning; `distribution.ArmedTargetCache`
  itself is untouched by this lot (still exists, still correctly invalidated, still simply not reachable from
  script - kept for any future Java-side caller, per its own Javadoc).
- **Unconditional recompute** at two points, closing the gap for anything the write-path wiring above cannot
  see: `DistributionControlRow.recomputeOne` is called by `DistributionTriggerInstaller.install` right after
  `DistributionOutboxInstaller.ensure`, so the row is correct from a project's very FIRST trigger install
  onward (covers a target armed before its project ever had triggers, or a project onboarded pre-lot-8b);
  `DistributionControlRow.recomputeAll` runs once for every project at module startup
  (`FactoryModuleRegistration`), the self-heal for a server upgraded to a build carrying this lot.

**Deployment**: like any generated-script change, an ALREADY-INSTALLED project's trigger only picks up the
short-circuit when `DistributionTriggerInstaller.install` runs again for it (its declare-then-author-then-
publish sequence is unconditional on the author step - `authorOne` always calls `update_script` with a freshly
built body - so a plain reinstall is enough, no special-casing added) or the master model is otherwise
republished; deploying this lot alone (a Tomcat restart to package the new `Control` table) does not retrofit
a running project by itself. Document this to whoever operates a deployed instance.

**Not validated by `validate_draft`**: that tool only accepts free-standing script types; a table trigger needs
`validate_saved` against an actually-declared script, which needs write access this pass did not have (task
instructions restrict EBX access to read-only tools). Checked instead against `get_syntax_guide(triggerAfterCreate)`'s
documented `core.data.lookupRecord`/`core.complex.primaryKeyOf` and the exact typed-record field-access idiom
already used elsewhere in the same generated body - consistent with every earlier round of this trigger, none
of which were validated by `validate_draft` either (it never supported this script type).

**Generated trigger excerpt** (the new block, `DistributionTriggerBody.body`, before the unchanged SQL
fallback):

```
const outboxDataset := db.findDataset(_ebx.dataspace, 'DistributionOutbox');
if isNull(outboxDataset) then return;
const controlTable := db.findTable<outbox.Control>(outboxDataset);
var controlPk := cp.primaryKeyOf<outbox.Control>();
controlPk.project := '<projectId>';
const controlRow := db.lookupRecord(controlTable, controlPk);
if isNull(controlRow) then return;
if controlRow.armedRealtimeTargets = 0i then return;
const factoryDs := db.findDataspace('MdmFactory');
... (unchanged SQL fallback) ...
```

Not observed live in one respect worth naming: `DistributionConfigurator` was named in the task brief as one
of lot 8a's `ArmedTargetCache`-wired write paths, but a grep of the current tree shows it never writes
`DistributionTarget.state`/`.mode` at all (only `connectorsMappingIds`) and carries no `ArmedTargetCache.invalidate`
call - the four sites actually wired (and the only four this lot wires) are the ones listed above.

### 2. Outbox `Event` table - natural key evaluated, NOT viable

Read `distribution.EventCoalescer`/`ebx.DistributionDispatcher` end to end before touching the key: several
PENDING rows sharing the exact same `(target, tablePath, recordKey)` legitimately coexist BOTH within one
drain pass (`EventCoalescer.coalesce` exists precisely to net several changes to one record queued since the
last drain) AND across passes (`EventCoalescer.idsSupersededBySend`/`DistributionDispatcher`'s round-5 fix -
a row still in retry backoff alongside a fresh event for the same record). A primary key can only ever hold
one row per key value, so a natural key would force every insert into an upsert, silently collapsing a
`CREATE` row into a later `MODIFY` row's own `change` value (or the reverse) - exactly the netting this design
does ON PURPOSE, but done wrong (by whichever write happens to land last) and with the sequencing information
(read off the auto-increment `id`) destroyed in the same stroke. **Verdict: not viable. The key stays `id`.**
Documented on `EventCoalescer`'s own class Javadoc (the evidence lives there) and on the `Event` table's own
XSD-level reasoning.

**Drain reads are already bounded, unchanged by this lot**: `DistributionDispatcher.drainTarget`'s own PENDING
scan already reads by `target`+`status` only (lot 8a, task 4) and stops at `DRAIN_BATCH_CAP` (1000) rows read.
`closeCrossPassSuperseded`'s own per-group query (`target`+`tablePath`+`recordKey`) is unchanged and still runs
once per sent group - lot 8a already flagged merging it into one pass-wide query as untested-live risk not
worth taking for the last remaining query-count reduction once the two real per-record WRITE transactions were
already removed; nothing new here changes that judgment.

### 3. Wizard buttons for reset and delete now run as jobs

- **Step reset** (`ProjectWizardUserService.stepResetConfirm`): switched from calling `StepResetter.reset`
  directly to enqueuing `StepResetRun.start`. A synchronous confirm-text pre-check was ADDED (comparing the
  posted text to the project code directly) because `StepResetRun.start` itself does not validate before
  enqueuing (lot 8a's own "nothing is refused" job-queue philosophy) - without it, a mistyped confirm would
  only surface after a full queue/run/poll round trip instead of at once, a real UX regression versus the old
  synchronous call. The panel switches to a "running" view (`WizardPage.StepResetView.running`, a new field);
  a new `stepResetPendingKind`/`stepResetRunRunning()` pair, wired into `renderJobsBanner`'s own `active`
  computation and the `JOBS_POLL` handler, mirror the EXISTING `onboardPendingKind` pattern exactly (job-engine
  lot C3) - the wizard's own jobs banner is reused as instructed, no new polling infrastructure built for it.
  `StepResetRun.status` (new) reads back the SAME `STEP_RESET` `PipelineStep` audit row's `detail` JSON
  `stepResetConfirm` used to read directly from `StepResetter.reset`'s own synchronous return value - the rich
  report survives the async move because, unlike a project deletion, a step reset never removes the project
  (or its `PipelineStep` rows).
- **Project deletion** (`ebx.ui.ProjectDeleteUserService`/`ProjectDeletePage`): switched from `ProjectDeleter.delete`
  to `ProjectDeleteRun.start`, with the same synchronous confirm-text pre-check added for the same reason. This
  screen is a STANDALONE `UserService` with no jobs-banner infrastructure of its own (unlike the wizard) - a
  small, self-contained equivalent was built instead: a hidden `mdmfDeleteAction` field and an inline script
  (`write()`) that, while the "running" notice is on the page, clicks the SAME submit button every few seconds
  with `mdmfDeleteAction=poll`; `processEvent` branches on that field to call `pollDelete` instead of
  `handleDelete`. **A real, structural information loss, not a bug**: `ProjectDeleteRun`'s handler builds the
  SAME full per-item `generated[]`/`rowsDeleted` report `ProjectDeleter.delete` always did, but nothing durable
  is left to read it back from once the job ends - a SUCCESSFUL delete removes the project's own `PipelineStep`
  rows along with everything else (`ProjectDeleter.deleteFactoryRows`' own per-project table sweep), so there
  is nowhere query-able-after-the-fact to keep that detail (the same `PipelineStep`-detail trick step-reset
  uses is unavailable here - the audit row would need to survive the very deletion it is auditing). The
  `FactoryJob` row itself DOES survive (`ProjectDeleter.deleteFactoryRows` never touches `FactoryJob` - only
  `MdmFactory`'s own per-project tables - confirmed by reading the class, not asserted), so `ProjectDeleteRun.status`
  polls it BY ITS OWN PRIMARY KEY (same lookup `FactoryJobQueue.cancel` already does, independent of whether
  the project row is still there) and reads back the handler's own summary MESSAGE - "Project X deleted (N
  row(s) across M table(s))" - instead of the old detailed items table. `ProjectDeletePage`'s report mode keeps
  the old detailed rendering working (for a caller that still fills `items[]`, none does today) but shows this
  plain sentence when `items` is empty and a `doneMessage` is set. **Flagged for the coordinator**: this is a
  real UX simplification versus the old synchronous screen, not merely a refactor - accept it, or a follow-up
  lot could keep the full detail by writing it to a NEW field that survives the delete (e.g. a small history
  table outside the project's own per-project sweep), which this lot did not attempt.
- **`MasterResetRun`/`MasterReset.reset`** (also named in the task brief) is NOT called from any `ebx/ui` file
  today - only from `rest.FactoryResource`'s `/master/reset` route, already switched to the job path in lot 8a
  itself. Nothing to change here; noted so the coordinator does not go looking for a wizard button that does
  not exist.
- **A known, accepted rough edge**: the delete screen's submit button stays labelled "Delete project" and
  clickable while a deletion is running (no infrastructure was built to hide/disable it - see
  `ProjectDeleteUserService`'s own class Javadoc). Clicking it in that window is harmless: the confirm/option
  fields are not rendered while running, so it fails the confirm-text pre-check and just re-shows the plan.
- **Confirm panel's `plan()` stays synchronous** for both screens, unchanged, per the task brief's own
  instruction and lot 8a's own precedent ("it only counts now").

### 4. `workflow.DistributionTaskBean` claims its resource

A one-line fix flagged verbatim by lot 8a: `executeScript` now calls `PipelineLauncher.run`'s 5-arg overload
with `FactoryJobs.DISTRIBUTION` and `this.projectCode`, exactly like every sibling bean
(`MasterLoadTaskBean`/`MatchingRunTaskBean`/...). A workflow-launched send now waits for that resource of the
project to be free (`FactoryJobs.awaitClaim`) and holds it for the whole send, closing the race lot 8a's own
`docs/VOLUME.md` flagged (a workflow-launched send could run concurrently with a wizard/REST-launched send of
the same project against the same `DistributionTarget`/`DistributionOutbox` rows).

### Tests

New: `distribution.ArmedRealtimeChangeTest` (the `NONE`/`ARM`/`DISARM` classification, every direction named
above), three new assertions in `DistributionTriggerBodyTest` (the short-circuit block's exact shape, its
position strictly before the SQL fallback, and that `db.findDataset(_ebx.dataspace, 'DistributionOutbox')` is
called exactly once - the read and the write share the same lookup), `ebx.ProjectDeleteRunTest` (the pure
`totalRows` row-count summation the new job message is built from). `tools/test_local.sh` green (XSD
regeneration diff clean for `MdmFactory.xsd`, `xmllint --noout` run by hand on `DistributionOutbox.xsd` since
`build_xsd.py` does not generate it, all pure unit tests pass including the five new ones above).

### What was NOT verified (nothing in this lot runs live)

- Task 1: the generated trigger text was checked against `get_syntax_guide`/`get_api_help` only, never
  compiled or run - same posture as every earlier round of `DistributionTriggerBody`. `DistributionControlRow`'s
  write paths (the `beforeStateChange`/`afterStateChange` asymmetry in particular - whether the ordering
  actually closes the race it is designed to close under real concurrent writes) were reasoned through, never
  exercised against a live repository or a real race.
- Task 2: the natural-key non-viability is a code-reading judgment (`EventCoalescer`/`DistributionDispatcher`
  read in full), not something proven against live duplicate PENDING rows.
- Task 3: neither the wizard's step-reset job-banner wiring nor the standalone delete screen's self-poll
  script were run in a browser against a live Tomcat - in particular, whether `document.getElementById(...).click()`
  correctly resubmits `ProjectDeleteUserService`'s own form the same way `wizard.js` already does for the
  wizard's form was reasoned by analogy (CLAUDE.md sec 9), not re-measured.
- Task 4: the resource claim was not exercised against a live workflow launch racing a wizard/REST send.

Deploy this branch and exercise each item against a real project - in particular, reinstall triggers on an
already-armed project and confirm the short-circuit takes effect, and run a step reset and a project deletion
through to completion in a browser - before relying on it at volume.

## Lot 7c — asynchronous landing load

Written 2026-09-28 on branch `feature/volume-async-landing` (worktree `ebx-mdm-factory-volume-7c`), from
`integration/volume-wave1` `e3525fd2` (carrying lots 1b, 4, 2a, 8a, 3 and 8b above). Task: `ebx.LandingFlows.load`
still ran ONE long, blocking `connectors_sync` call per landing table - the exact multi-hour-tie-up-a-thread
problem lot 8a's own task 1 already fixed for the seven REST routes it moved onto the job queue, left open here
because at the time no Connectors-side asynchronous contract existed to move it onto. It now does, on
`ebx-connectors`' own `integration/volume-wave1` (volume lot 7a, "streamed read, summary answer, asynchronous
run", docs there &sect;2.14.5): `connectors_sync(..., async: true)` answers at once with a run id instead of
blocking, and `connectors_run_get(runId)` reads live progress and the eventual terminal state. Same posture as
every lot above: no EBX/Connectors instance was reachable this session (Connectors access was read-only MCP
tools only, per this lot's own instructions, and even those were not exercised against a live server carrying
volume lot 7a); `tools/test_local.sh` is the only verification.

### 1. `ebx.LandingFlows.load` polls instead of blocking

- New pure class `landing/RunStateOutcome.java` (+ `RunStateOutcomeTest.java`, 12 cases): `isAsyncStart(JsonNode)`
  tells the async-start acknowledgement (`{runId, status: "RUNNING", mapping, feed: true, async: true,
  nextStep}` - never a `count` field) apart from an older Connectors build's complete synchronous answer
  (always carries `count`, whatever its own `status` reads) - STRUCTURALLY, never from a version/build number,
  per this lot's own instruction. `isTerminal(status)` is `DONE`/`PARTIAL`/`ERROR`/`STOPPED`/`INTERRUPTED`.
  `outcome(execution, sourceRowCount)` builds the SAME `LandingFlow.LoadOutcome` shape a synchronous answer
  builds (`LandingFlow.loadOutcome`), from a TERMINAL `connectors_run_get` `execution{}` object instead, per
  the task's own field mapping: `count = created + updated + noChange`, `errors = errorCount`, `truncated`/
  `available` read the same way and fed to the SAME `LoadOutcomeRule` (lot 1b) a synchronous answer already
  uses - a truncated/incomplete async load fails exactly like a truncated synchronous one did. `ERROR` always
  fails; `STOPPED`/`INTERRUPTED` fail unconditionally too (never routed through `LoadOutcomeRule` - a mid-run
  stop legitimately reads far fewer rows than the source has, which is not the same signal `LoadOutcomeRule`
  exists to catch), with a message naming `nextIndex` (the resume point) when known. `processedSoFar`/
  `errorsSoFar` read the running counters off a still-`RUNNING` execution, for the live-progress note while
  still polling. `stoppedByHandle`/`interrupted` are two small factory methods `RunStateOutcome` exposes purely
  because `LandingFlow.LoadOutcome`'s own constructor is package-private to `landing` - `ebx.LandingFlows`
  (a different package) cannot build one directly for its own two "stopped"/"thread interrupted" cases.
- `ebx/LandingFlows.java`: the `connectors_sync` call for a table's load now sets `"async": true`. A new
  private `awaitLoad` (+ `PollOutcome`, `stopRun`, `tryRunGet`, `recordNextIndex`) reads the answer via
  `RunStateOutcome.isAsyncStart`: an old-shape (already-complete) answer goes through the pre-existing
  `LandingFlow.loadOutcome` unchanged; an async-start acknowledgement is polled - `connectors_run_get` every
  `LOAD_POLL_INTERVAL_MS` (5000, a plain constant: no `Settings` field exists for this yet, and adding one is
  an XSD/restart change out of proportion for a sleep interval alone, same reasoning lot 8a's own
  `DRAIN_BATCH_CAP` documents) - until `execution.status` is terminal, feeding the live-progress `ProgressNote`
  with `RunStateOutcome.processedSoFar` on every non-terminal poll (throttled for free by the 5 s poll interval
  itself - well under the 1.5 s `staging.RunProgress.LIVE_WRITE_INTERVAL_MS` convention this module otherwise
  follows for a live panel, so no new throttling infrastructure was built).
- **Cancellation**: `load` gained a `FactoryJobs.Handle`-accepting overload (the two existing overloads
  delegate with `pHandle = null` - a caller with no job, `rest.FactoryResource`'s synchronous route and
  `workflow.LandingLoadTaskBean`, keeps running the poll loop to completion with no way to be cancelled mid-way,
  same posture lot 8a's own task 1 documents for every workflow bean). The loop checks `pHandle.cancelled()`
  BEFORE starting a new table's run (skips straight to ending the step) and, inside `awaitLoad`, BETWEEN polls
  of a table already running: on a stop, `connectors_run_stop(runId)` is called (best-effort - a failed stop
  call never blocks recording the cancellation; the run may still stop on its own between batches per
  ebx-connectors' own task 5) and one more `connectors_run_get` captures whatever the run had reached, before
  the whole `LANDING_LOAD` `PipelineStep` ends `STOPPED` (`PipelineSteps.endStopped`, the SAME status/method
  `LandingRun.maybeCloseStep` already uses for a job-level stop) rather than starting the next table. `load`'s
  own returned `ObjectNode` gained a third `status` value, `STOPPED`, alongside the pre-existing `DONE`/
  `FAILED`. `ebx.LandingSourceRun.GENERATE_HANDLER` and `ebx.LandingRun.runLoadForSourceIfReady` (both call
  sites that already own a `Handle`) were updated to pass it through - `LandingRun`'s own progress line now
  reads "Stopped (&lt;reason&gt;)." instead of a generic failure count when `status` comes back `STOPPED`.
  Before this lot, a Stop click only ever reached a landing load BETWEEN tables/stages (`pHandle.cancelled()`
  checked between the three `LandingSourceRun.GENERATE_HANDLER` calls, or between sources/tables in
  `LandingRun`'s own loop) - never WHILE a single table's own `connectors_sync` was still running, since that
  call used to block for the table's entire duration. It now can, within one poll interval (at most 5 s).
- **`nextIndex` recorded, not resumed automatically** (deliberately, per the task brief): whenever a terminal
  `execution{}` (normal completion, or the one read right after a Handle-triggered stop) carries `nextIndex`,
  it is written onto the table's own `PipelineStep.detail` entry (`tableDetail.put("nextIndex", ...)`) - a
  later lot that wants to resume a `STOPPED`/`INTERRUPTED` load from where it left off (`connectors_sync(...,
  resumeFrom: nextIndex)`, ebx-connectors &sect;2.14.1/§2.14.2) has somewhere to read it from without
  re-deriving it from `connectors_run_get` after the fact. `INTERRUPTED` (the server restarted mid-run,
  ebx-connectors' own `markInterruptedRunningRuns`) is mapped to `LandingTable` status `FAILED` with a message
  inviting a plain re-run - never resumed automatically in this lot.

### 2. `LandingFlows` tool detail no longer stores the whole answer

Audit finding: `tableDetail.set("sync", LandingFlows.toolDetail(args, sync))` used to serialise the WHOLE
`connectors_sync` answer verbatim, `results[]` (up to 1000 entries even before ebx-connectors volume lot 7a's
own bound) included, into `PipelineStep.detail` - and the whole per-source detail (every table's own entry, in
the SAME JSON document) is truncated at `TextLimits.DETAIL_MAX_LENGTH` the moment a handful of tables push it
past that, silently losing the LATER tables' own detail (truncation cuts the tail, not the oversized field).
New private `LandingFlows.summaryDetail(ObjectNode, McpClient.ToolResult)`, used for every "sync"/"poll"/
"stop"/"pollAfterStop" table-detail entry `load` writes: keeps every top-level SCALAR field verbatim (an array
- `results`/`calls`/`errorSamples` - is dropped, never copied whole) and the same for `execution{}`'s own
fields when present, plus at most `DETAIL_ERROR_SAMPLE_MAX` (5) non-OK/non-NO_CHANGE/non-DRY_RUN messages from
`errorSamples[]` (falling back to `results[]` when there is no `errorSamples` at all - the shape an older
Connectors build answers). The pre-existing `toolDetail` (unchanged, full echo) stays in use for the small,
array-free answers elsewhere in this file (`connectors_mapping_upsert`, `connectors_describe`).

### 3. Bounded reads elsewhere: kept synchronous, and a `results[]`-completeness sweep

Per the task brief, `ebx.LandingPreview`/`ebx.KeySourceUniqueness`/`ebx.DeducedScanSample` (all `connectors_call`,
unchanged and out of scope for ebx-connectors' own volume lot 7a - that lot's scope is `connectors_sync`'s
feed-import path only) and `ebx.SourceOnboarder.runScan` (a catalog scan - `connectors_sync` with no `inputs`/
`max` at all, "a catalog scan is not a landing load", an EARLIER lot's own deliberate decision, still correct)
were left untouched - all genuinely small, bounded reads with no truncation risk this lot's async change needs
to address. Swept the whole module (`grep -rn '"results"'`) for a reader that assumes a `connectors_sync`/
`connectors_call` answer's `results[]` array IS the complete row set rather than reading the answer's own
counters: found none in the landing package beyond `LandingFlow.firstError` (already only ever picks the FIRST
error out of whatever is there, never assumes completeness). One reader elsewhere in the module DOES fall back
to `results.size()` when `count` is absent - `distribution.SyncOutcome.read`, used by `DistributionRunner`/
`DistributionDispatcher` for OUTBOUND per-record/per-mapping sends - but it reads a DIFFERENT, UNCHANGED
`connectors_sync` answer shape (`syncRecord`/`sendScope`, explicitly untouched by ebx-connectors' own volume lot
7a, whose scope is `importFrom`/`importDocument` feed callers only) for a call site this lot's task list does
not name; left as-is, flagged here rather than fixed, since touching distribution's own read of an unrelated,
unchanged answer shape is outside this lot's assignment.

### 4. `McpServers.READ_TIMEOUT_MS` (300 s) - still fine, now for a different reason

`McpServers.connectors(session, factory)`'s HTTP client read timeout (300000 ms) used to bound how long ONE
`connectors_sync` call - and therefore one table's ENTIRE load - could take before the MCP call itself timed
out; a landing table genuinely larger than that budget could allow had no way to finish. Since this lot, every
individual call (`connectors_sync(async: true)`'s own immediate start, each `connectors_run_get` poll,
`connectors_run_stop`) returns almost at once regardless of how large or long-running the underlying load is -
the 300 s figure now only needs to bound a single fast round trip, never the whole table's copy time, and is
generously oversized for that purpose. No code change was needed for this: `awaitLoad` reuses the SAME
`McpClient` `load` already obtained from `McpServers.connectors(...)` for the whole call (in-JVM
`ConnectorsBridge` or HTTP, whichever that method picked), so a poll goes through the exact transport the
initial `connectors_sync` call did, with no separate wiring.

### Tests

New: `landing/RunStateOutcomeTest.java` (12 cases: async-start detection including the "carries count" negative
case, terminal-status classification, `processedSoFar`/`errorsSoFar` on a running execution, `outcome()` for
`DONE`/`PARTIAL`/`ERROR`/`STOPPED`/`INTERRUPTED`, the truncated-still-fails and source-row-count-ratio-fallback
cases reusing `LoadOutcomeRule`, and defensive null/blank-input cases). No test touches `ebx.LandingFlows`'s own
poll loop directly (it needs a live `McpClient`/`Session`/`Repository`, same posture as every other EBX-facing
class in this module - `LandingFlowsTest`/`LandingFlowsEligibilityTest`, unaffected by this lot, exercise its
OTHER pure helpers at the type level). `tools/test_local.sh` green (XSD regeneration diff clean - no XSD change
in this lot at all, confirmed by the diff being empty - compiles against the real `ebx-lib.jar`, every pure
unit test passes, `RunStateOutcomeTest`/`LoadOutcomeRuleTest`/`LandingFlowTest`/`LandingFlowsTest`/
`LandingFlowsEligibilityTest` included).

### What was NOT verified (nothing in this lot runs live)

- No EBX instance and no Connectors instance were reachable this session; even the read-only MCP tools this
  lot was permitted to use were not exercised against a server carrying ebx-connectors' own volume lot 7a (the
  `async`/`connectors_run_get`/`connectors_run_stop` contract this whole lot rests on) - every shape read here
  comes from `ebx-connectors` `integration/volume-wave1`'s own `docs/REFERENCE.md` &sect;2.14.5, itself written
  by that lot's own session without a live instance either (its own "Session lifetime - not verified live" and
  "not measurable in this task's environment" notes apply transitively here).
- The poll loop itself (`awaitLoad`'s `Thread.sleep`/`connectors_run_get` cadence, whether a real run's
  `execution{}` row is actually visible on the very first poll as the docs claim, whether `connectors_run_stop`
  actually stops a real batched import between chunks) was never run against a live server.
- Cancellation end to end (a job's Stop button reaching a table mid-poll, the resulting `connectors_run_stop`
  call, the `LANDING_LOAD` step ending `STOPPED`, `LandingRun`'s own "Stopped (...)." progress line) was
  reasoned through and unit-tested at the pure-decision level only (`RunStateOutcome`) - never exercised
  against a live job queue and a live Connectors run.
- Whether the in-JVM `ConnectorsBridge` transport (as opposed to HTTP) behaves identically under repeated
  `connectors_run_get` polls over a long-running background thread (ebx-connectors' own lot 7a "Session
  lifetime - not verified live" note) was not independently re-checked here.
- The `nextIndex` value this lot records was never seen on a real `execution{}` object - its presence,
  field name and meaning come entirely from ebx-connectors' own docs, not a live probe.

Deploy this branch (alongside an `ebx-connectors` build carrying its own volume lot 7a) and exercise a landing
load - both to completion and stopped mid-way via the wizard's Stop button - against a real project before
relying on it at volume.

## Wave 2 — model build in one pass

Lot V2-C, branch `fix/model-build-single-pass`. Measured on 2026-09-29 (project Vol100k, 3 sources): `LANDING_MODEL`
32.0 s / 37.3 s / 25.4 s for models of 16 / 17 / 9 columns, `STAGING_MODEL` 51.3 s (2 entities), `MASTER_MODEL` 42.3 s
(2 tables) - a fixed cost per project (about 3 minutes), independent of the data volume.

### Model build time, re-measured (6.3.0-gp, 2026-11-12) - F3-102

**The "about one second per field" of this section dates from 6.3.0-rc2 and no longer holds.** Measured on the development instance, 6.3.0-gp, on a throwaway model:
109 operations (4 tables, 100 fields) applied in ONE transaction took **1.15 s** (about 10.5 ms per operation); the whole `build_data_model` call, validation and compilation
included, took 4 to 5 s. The one-element-per-transaction cost (`CreateFieldProcedure` at about 0.95 s each) was the rc2 behaviour. The figures of the section above (32 s for 16
columns, 51 s for two entities) are therefore rc2 figures; re-measure before quoting them for a GP instance.

Decision (F3-102): `McpServers.READ_TIMEOUT_MS` (300 s) is **not changed**. When it would become a subject, by extrapolation and not by measure: the patch costs about 10 ms per operation
plus about 3 s of fixed cost, so 300 s corresponds to roughly 28,000 operations in ONE call, i.e. a single model of the order of 25,000 fields on a linear cost. The terminal validation and
compilation of a model are not known to be linear, so keep a margin of ten: **look at the delay again for a single model beyond about 3,000 fields**, after measuring 1,000 and 5,000 fields
(not done). A generated landing, staging or master model of a real project stays one to two orders of magnitude below that (a 360-field model, by the same extrapolation, builds in about 7 s; not measured). What the server leaves behind after
a call that was abandoned at the timeout has not been measured either.

### What the rehearsal was for

Each generator (`LandingGenerator.runModelStep`, `StagingGenerator.runModelStep`, `MasterGenerator.runModelStep`)
called `build_data_model(dry_run: true)` first: a full throwaway `ebxmcp_rehearsal_<ts>` model, validated, compiled and
deleted, then the same call for real. On 6.3.0-rc2 EBX built a DMA model one element per transaction (~1 s per field), so the
rehearsal doubled the cost (that figure is out of date on 6.3.0-gp, see "Model build time, re-measured" below). What it bought: a model that fails the terminal validate + compile left NOTHING under the
real name. The real build is atomic only for an OP failure ("any op failure rolls the partial model back
(delete-on-fail)", answer field `deletedOnFailure`); a model that builds but does not validate or compile is created and
reported `valid:false` / `compiled:false`, so it would stay behind and the next "Generate" would meet it as a
`MODEL_ONLY` leftover. Read live with `dry_run: true` on 2026-09-30: an unknown field type answers `built:false,
compiled:false, valid:false` and a `BUILD_OP_FAILED` finding; a good model answers `valid:true, compiled:true` with the
compile findings (warnings included); a dry run always answers `built:false`. The rehearsal also caught nothing the
real answer does not carry: the same findings, the same `valid`/`compiled` gates.

### What replaces it

- `ebx.ModelBuilder.build` (shared by the three generators): ONE `build_data_model` call, not dry. Its answer is read by
  the pure `core.ModelBuildAnswer` with the same test the dry-run answer had (`valid` and `compiled`, and the tool's
  own error flag). A failure raises the same messages as before (`build_data_model could not create the <kind> model
  ...` / `The <kind> model of <owner> does not compile or is not valid (build_data_model): <text>`, the `(dry run)`
  wording only when the rehearsal is on) and the step ends `FAILED` with the same `PipelineStep` detail (`build`; the
  `dryRun` key exists only with the rehearsal).
- The model the failed build may have left behind is removed: `ModelBuildAnswer.cleanupNeeded()` (failed and
  `deletedOnFailure` not true - also for a tool error or an unreadable answer) -> `get_data_model` on the built name ->
  when it exists, `GeneratedObjectDeleter.deleteDataModel` (`delete_data_model`, the call project deletion uses). The
  build only runs on a name found free just before (`decision.build` is true for `NOTHING` only), so a model found
  after a failed build is the build's own leftover. What happened is in `detail.buildCleanup` and in the failure
  message ("The model left behind was deleted again" / "was left behind and could not be deleted: delete it before
  generating again"). `ModelBuildAnswer` deliberately ignores the `built` field: a wrongly read `built` must never make a
  good model look failed, because a failed answer triggers a deletion.
- The rehearsal stays available for diagnosis: JVM option `-Debx.mdmfactory.modelRehearsal=true` (`core.ModelRehearsal`,
  read at each build, default off, no XSD or `FactorySettings` change).

### Other fixed costs removed

- `apply_data_model_patch` (facets after the build; the regeneration patch of staging and master) now passes
  `gate: false`. The patch's own terminal validate + compile (measured 1.6-2.4 s, flat) ran right before
  `publish_data_model`, which validates and compiles again and is the only gate that decides whether a model is
  published. The per-op `failed` count is still checked. No patch used `rehearse: true`.
- Not removed, deliberately: `get_data_model` / `get_publication` (needed for the idempotence decision, one call each,
  `get_publication` only when the model exists), `ensureEmbedded` (one `get_global_properties` read; a defensive check
  whose default for a `build_data_model` model is not verified), the master's per-table `set_default_label` /
  `set_toolbars` / `list_toolbars` / `list_addons` calls (`execute_operation`s with no patch equivalent - a
  `defaultLabel` in a patch aborts it, `docs/MASTER-ZONE.md` sec 10).
- Idempotence is untouched: `LandingModelState`, `StagingModelState`, `MasterModelState`, `StagingModelDiff` decide
  exactly as before, a second "Generate" still answers `SKIPPED_ALREADY_DONE` / patches only what changed.

### Expected gain

About half of each build step: the dry run paid the full per-element cost (a 16-field landing model ~25-30 s of the
32 s) and is gone. Expected, from the 2026-09-29 numbers: landing 32 / 37 / 25 s -> ~17 / 20 / 13 s, staging 51 s ->
~28 s, master 42 s -> ~24 s; about 1.5 minutes per project of the 3.

### Not verified

- Nothing ran live (no `build_data_model` without `dry_run`, no patch, no publish on the instance): the times above are
  arithmetic on the measurements, not a new measurement.
- Whether a REAL build that builds but fails the terminal validate/compile really leaves the model behind (assumed
  from the contract) and what `deletedOnFailure` answers in that case; the cleanup handles both readings (looks, then
  deletes only if found).
- That `apply_data_model_patch(gate: false)` answers without `valid` / `compiled` as its schema says (the code reads
  them with a default of true, so a null is not a failure), and that a patch left invalid is then refused by the
  publish gate with a usable message.
- That a failed build's cleanup call (`delete_data_model`) works for the agent account on a model in that state.

## Wave 2 — landing watermark

Lot V2-E, branch `fix/landing-watermark`. Builds on ebx-connectors lot V2-D (`docs/REFERENCE.md` 2.14.7, "Watermark
(incremental feed reads)"): a landing reload of an UNCHANGED source cost 0.47 ms per row (99,249 rows: 47 s) because
every row is read, transformed and looked up before the hash compare reports `NO_CHANGE`. With a watermark the feed
reads only the rows changed at the source since its last successful run. This lot makes the landing flows use it.

### 1. A change column per landing table (`tools/build_xsd.py`, `landing.ChangeColumnRule`)

`LandingTable` gains three optional fields (additive, nothing else of the XSD moves): `changeColumn` (the source column
that says a row changed), `changeColumnKind` (`TIMESTAMP` / `MONOTONIC_ID` / `ORDERED_STRING`, the Connectors
`checkpointStrategy` names) and `changeColumnLoaded` (the change column the last successful load read through, see
section 4). Empty = the table is reloaded in full, exactly as before.

`LandingPlanner` picks the column for a NEW database table, from catalog metadata only (the pure `ChangeColumnRule`; no
data read, no language model):

- a column of type `TIMESTAMP` or `DATE` whose name, upper-cased with every non-alphanumeric dropped, is one of a short
  list of "last changed" markers (`LAST_MODIFIED`, `LASTMODIFIEDDATE`, `SYSTEMMODSTAMP`, `MODIFIED`, `UPDATED_AT`,
  `UPDATE_DATE`, `CHANGED_ON`, `AEDAT`, `DATE_MODIFICATION`, `DT_MAJ`, ...); the list is ordered, the best name wins, then
  the table's column order. Kind `TIMESTAMP`.
- else a numeric column (`INTEGER`/`DECIMAL`) named like a source-wide growing marker (`ROW_VERSION`, `CHANGE_SEQ`,
  `LSN`, ...). Kind `MONOTONIC_ID`.
- else none.

Left out on purpose, because a wrong watermark does not fail, it silently misses rows: a creation date; an
auto-increment `ID` (grows for inserts, blind to updates); a bare `VERSION` / `REVISION` (usually a per-row
optimistic-lock counter, "row A at 5, row B at 2" is not an order); a bare `SEQ` (often a line number inside a
document); a text column. A column the catalog PROFILED with NULLs is refused too (`>=` never returns a NULL). Never
planned for a `FILE` asset or a deduced-scan table: their flows are not generated SQL and would ignore it. A table
planned before this lot, or re-planned, is never touched (a person's choice, "none" included, survives).

### 2. The mapping (`landing.LandingFlow.mapping`)

Only a SQL flow reads a watermark: the generated-SQL (`query`) mapping and, since the recipe fix below, a catalog recipe
whose operation is a SQL `query`; the file-feed mapping and any other recipe are not candidates (their flow detail says
`changeColumnIgnored`). For a table with a change column the mapping gets
`loadMode: INCREMENTAL`, `checkpointStrategy: <kind>`, `checkpointValuePath: CHANGE_VALUE`, and the SQL
`... from <source> s where (${watermark.isSet} = 0 or s."<col>" >= ${watermark})`, with the column also selected as
`s."<col>" as CHANGE_VALUE`:

- `checkpointValuePath` is a field of the returned row (REFERENCE 2.14.7), and the JDBC row carries a timestamp as ISO
  text and a number as a number, which `Watermark` compares as such; the `C<n>` aliases are `TO_VARCHAR`/`CAST` text
  (driver- and dialect-formatted, `2026-09-30 08:41:07.500 -0700` for a Snowflake `TIMESTAMP_TZ`), so the typed column is
  selected separately. The path is matched case-insensitively, so H2's upper case and PostgreSQL's lower case both work.
- `>=`, inclusive, because `unchangedIfEqual: ./_rowHash` (always set by the Factory) makes the rows read again
  `NO_CHANGE` without a write; `checkpointBoundary` stays empty (= inclusive when `unchangedIfEqual` is set).
- `(${watermark.isSet} = 0 or ...)` and not a bare `>=`: on a first run and on a full refresh it reads EVERYTHING, rows
  whose change column is NULL included. Later runs never read such a row until it gets a value (documented limit; a
  periodic full refresh is the answer).
- no `ORDER BY`: the checkpoint only moves after a run that ends DONE, so the order of the rows does not matter and a sort
  of the whole table would cost what the watermark saves.
- how the value reaches the statement depends on the connector: a JDBC-family connector (H2, PostgreSQL) BINDS
  `${watermark}` as a typed parameter (`SqlDialect.bindsWatermark()`, default true); Snowflake's SQL API receives it as
  TEXT, so a timestamp is written as a quoted literal (`'${watermark}'`) and a number bare. A `ORDERED_STRING` change
  column is REFUSED for Snowflake (the value is source data and the engine never escapes it: a hostile value in the
  column would run as SQL); it is fine for a binding connector.
- without a change column the generated mapping is byte for byte what it was (`LandingFlowWatermarkTest` pins it).
- the three properties are refused by an ebx-connectors build without V2-D; `LandingFlows.upsertFlow` then writes the flow
  again WITHOUT the change column (the table is reloaded in full, as before) and says so in the table's detail
  (`changeColumnIgnored`) rather than leaving the table with no flow.

**Recipe fix (measured live 2026-10-03, project Vol100k, `VOL100KCRM`).** The first version wired the watermark on the
generated-SQL branch only, but every JDBC source that went through the catalog (the normal path of the wizard) is built
from the catalog's read recipe (`LandingFlow.recipeMapping`), so `POST .../landing/flows?force=true` still wrote FULL with
`"recipe":true, "changeColumnIgnored":...`. A recipe whose operation is `query` with an `inputs.sql` text, on a connector type
that has a `SqlDialect` (H2, PostgreSQL, Snowflake), now gets the same three properties, and its SQL is WRAPPED, never parsed:
`select s.*, s."<col>" as CHANGE_VALUE from (<recipe sql>) s where (${watermark.isSet} = 0 or s."<col>" >= <operand>)` (a
trailing `;` of the recipe is dropped). `LandingFlow.changeFilter` builds the `CHANGE_VALUE` item and the WHERE for both
branches, with the same literal/binding rules per dialect. The recipe row keeps its own column names (the rules read them);
`CHANGE_VALUE` is one more field nobody maps. Any other recipe (file `read`, REST, a query with no `inputs.sql`) or a SQL
recipe on a type with no dialect stays FULL, and `changeColumnIgnored` says why (`LandingFlow.changeColumnIgnoredReason`).
The load side needs nothing: it reads `LandingTable.changeColumn` and the sync answer, not the mapping's origin.

### 3. The load outcome (`landing.LoadOutcomeRule`, `WatermarkOutcome`, `LandingFlow.loadOutcome`, `RunStateOutcome.outcome`)

- An incremental run that STARTED FROM a stored checkpoint reads only the rows changed since, so `count` of 0 out of
  500,000 is "nothing changed", not "incomplete". `LoadOutcomeRule.decide` has a new overload (`pSkipped`,
  `pFiltered`): when filtered the comparison with the catalog's `sourceRowCount` is skipped, and `available` is weighed
  against `count + skippedByWatermark` (the engine drops rows at or below the checkpoint after the source returned
  them). `truncated=true` still fails. A FIRST incremental run (no stored checkpoint) and a full refresh read everything:
  the catalog comparison applies to them as before.
- `NO_DATA` can now legitimately mean "nothing changed", and the engine may also report it with errors on the side. A
  `NO_DATA` with `errors > 0` (synchronous answer) and a polled `DONE` with no row and errors both FAIL ("no row was read
  and N error(s) were reported"); neither reads as a clean load any more. (`ImportOutcome.status()` itself says
  `NO_DATA` only when nothing was attempted AND no error was counted, so this is a guard on the answer, not a case seen.)
- `execution{}` of `connectors_run_get` carries `watermarkBefore/After/skippedByWatermark` but not the field name or
  `fullRefresh`: the Factory knows whether it asked for one and passes it.
- The message reads, for example: `Loaded 3 of 3 row(s) - changed since the watermark 2026-09-30T08:41:07.5, 41 unchanged
  rows skipped by the watermark, watermark now 2026-10-02T10:00.` / `Loaded 0 row(s) (no row changed at the source since
  the watermark) - changed since the watermark ...` / `... - first incremental load, everything read` / `... - full
  refresh, watermark ignored`. The table's `PipelineStep.detail` entry carries `changeColumn`, `fullRefresh`,
  `fullRefreshBecause`, `watermarkBefore`, `watermarkAfter`, `skippedByWatermark`, `watermarkNote`, `landingRows`.

### 4. Full refresh (`LandingFlows.load`, `ChangeColumnRule.fullRefreshNeeded`)

The Connectors checkpoint is not tied to a column or to the landing's content, so the Factory decides when to ignore it
(`connectors_sync(fullRefresh: true)`: `${watermark}` becomes the floor, `watermark.isSet` 0, and the checkpoint is reset
to the highest value seen when the run ends DONE):

- a person asked: the "Full refresh: ignore the change columns and reload every row" checkbox of the landing step when it is
  re-run (`LandingRun.start(..., pFullRefresh)`, `mdmfLandingRunFullRefresh`), or `POST /sources/{id}/landing/load?fullRefresh=true`;
- the table's `changeColumn` is not the one its last successful load read through (`changeColumnLoaded`: never loaded, set
  after being empty, replaced by another column) - "changing `checkpointValuePath` does not invalidate the stored value";
- the landing table is EMPTY (`getSize() == 0`) although a checkpoint may exist: a key change (`LandingKeyChange` deletes the
  rows), a rebuilt dataset or a step reset would otherwise be refilled with only the latest changes.

`changeColumnLoaded` is written with the load outcome when the load succeeded, except for a full refresh over an empty source
(Connectors leaves the checkpoint untouched there, so the column is not claimed). The workflow bean `LandingLoadTaskBean` and
the `LandingSourceRun` GENERATE job keep their signatures: they use the two automatic rules, not the manual switch.

`LandingTable.lastLoadRows` is read everywhere as "the rows of the landing table" (`LandingRowCounts`, the profile
percentages, the Mapping matrix); after an incremental run that read only the changed rows it is therefore set to the
landing table's own size (a `getSize()` after the run), not to the rows the run read (that number is in the message and in the
detail).

### 5. Wizard step 3

Under each table's key cell (`WizardPage.renderChangeColumn`): a select of the table's own columns with "None - reload in full"
first and the chosen one selected, a select for the kind (pre-selected from the column's type by `wizard.js` when the column
changes: date -> `TIMESTAMP`, number -> `MONOTONIC_ID`, anything else -> `ORDERED_STRING`) and a Save button
(`TABLE_SET_CHANGE_COLUMN` -> `WizardData.setTableChangeColumn`, validated by `ChangeColumnRule.validate`). Unlike the key it is
editable at any status: every landing run regenerates the flow, and a column the last load did not read through makes the next
load a full refresh by itself. The per-table detail line states what it does and that deleted rows are not seen.

### What this does NOT do

- Rows DELETED at the source are never seen by a watermark read (REFERENCE 2.14.7): an incremental landing keeps them. Run a
  full refresh now and then; retiring them is a separate reconciliation.
- A table with no declared key has `_sourceId` = the row hash: a changed row is a different id, in an incremental reload as in
  a full one (not worse than before).
- `lastLoadRows` after an incremental run is the landing table's size at the end of that run, which a concurrent writer could
  move; it is a display figure, not an audit one.

### Tests

`ChangeColumnRuleTest` (the rule, the validation, the full-refresh and recorded-column decisions),
`LandingFlowWatermarkTest` (the mapping with and without a change column for H2, PostgreSQL and Snowflake, the refusals, the load
outcome of quiet / first / full-refresh / truncated / skipped / NO_DATA-with-errors runs through both answer shapes),
`LandingPlannerChangeColumnTest`, `WizardPageTest` (the selector and the checkbox).

### Not verified

- Nothing ran live: no `connectors_mapping_upsert` of the incremental mapping, no `connectors_sync` with a watermark, no
  EBX start with the three new `LandingTable` fields (a module XSD loads at the next start; until then every read of the fields is
  guarded and a table reads as "none", and the wizard's Save answers that the field is not in the loaded model yet).
- Snowflake: the quoted timestamp literal relies on Snowflake coercing a string to the column's type in a comparison, and
  the Snowflake connector's row for a timestamp column being an ISO text (read in its source, never run); REFERENCE 2.14.7 says
  the same ("not verified against those two"). PostgreSQL and the binding itself were exercised by Connectors' H2 test only.
- That the checkpoint of a mapping whose generated SQL changed (a key change) behaves: the Factory forces a full refresh then
  because the landing table is empty, nothing else was measured.
- The size of the gain (the whole point): never measured on the 99,249-row source of the 47 s figure.

### Found while measuring: two projects on one source system shared one mapping (fix/landing-flow-key-per-project, 2026-10-03)

Project `Vol100kM` was created with the SAME connector and source system name as `Vol100k` (each with its own landing dataspace). Its
`POST /sources/{id}/landing/flows` did not create a mapping: it rewrote mapping 346 of `Vol100k` (its `dataspace` became `Vol100kM_Landing`)
and stored `inboundMapping = 346` on its own landing table; regenerating `Vol100k` pointed 346 back. The watermark checkpoint is kept per
mapping, so both projects also shared one checkpoint, and the deletion plan of `Vol100kM` offered 346 as its own flow.

Cause: `connectors_mapping_upsert` identifies a mapping by connector + operation + direction + table path + **label**, and the label was
`Landing: <source system> <table>` - no project. Fix:

- The label is `Landing: <project code> <source system> <table>` (`LandingFlow.label`; cut with a short stable hash when it would exceed
  the 120 characters of `Mapping.label`). A table built without a project keeps the old label (`legacyLabel`, tests only).
- **Upsert by id exists in Connectors** (`ConfigTransfer.importMapping`: the lookup by label comes first; when it finds nothing and the
  document has `id` and `updateById`, the mapping with that id is updated, label included). The Factory sends both when the table already
  holds a mapping it OWNS, so regenerating an already generated project updates its mapping in place and renames it - no second landing
  mapping, the watermark checkpoint survives.
- `LandingTable.inboundMapping` is only a pointer. Before using it, `LandingFlows.ownedMappingId` reads the mapping
  (`connectors_mapping_get`) and follows it only when it writes into this very table (dataspace, dataset, `/root/<table>`). A mapping that
  writes elsewhere (the state `Vol100kM` is in today: its table 91 points at 346) is left untouched and the table gets its own mapping
  through its project label; a gone mapping gives a new one; one that cannot be read refuses the generation for that table.
- If the label nevertheless finds another mapping than the one the table owned, the old (verified own) one is deleted, so no table is left
  with two active landing mappings; a failed deletion is recorded in the table's detail (`supersededMapping`).
- Deletion: `ProjectDeletionPlan` (and `StepResetPlan`) read each `inboundMapping` id's dataspace (`LandingFlowOwnership.dataspaces`) and
  offer the flow only when it is the project's landing dataspace. Another dataspace = "shared or foreign", not eligible (a step reset leaves
  it alone and goes on); a mapping that cannot be read is not offered (a step reset refuses, retry once Connectors answers); a gone one stays
  eligible and is reported absent. The two other labels the Factory writes through `connectors_mapping_upsert` already carry an identifier
  (`Distribution: target <targetId> ...`, `Data product <code> - ...`) and were left as they are.

To repair the live state: regenerate the flows of `Vol100kM` (`POST /sources/60/landing/flows?force=true` equivalent for its source) - table 91
gets a new mapping labelled with its project and 346 stays `Vol100k`'s; then regenerate `Vol100k` once so its mapping 346 is renamed.

Tests: `LandingFlowLabelTest`, `LandingFlowOwnershipTest` (a scripted Connectors transport: own mapping followed, foreign / gone / unreadable,
update by id, old mapping retired, the two-project scenario), `ProjectDeletionPlanTest` (foreign, unreadable, gone). Not run live.

### Found by the 1M test: a cleaning step rewrote the column every mapping of the feed reads (fix/cleaning-step-scope-and-derivation-path, 2026-10-04)

On Vol100k (15,378 rows) and Vol1m (153,937 rows) the ECOM feed of Personne maps `FULL_NAME` twice (`firstName`, `lastName`, each with a
correct regex on the raw text); the `lastName` mapping has one accepted `UPPER` step. The loader applied that step into the row document under
the shared column name, so the first-name regex ran on `AURELIE-SOPHIE NAVRET`: `firstName` was empty on 100 % of the ECOM rows in staging and
in master, `lastName` was the first token in upper case. Nothing flagged it: no reject, no warning, a green load. Fix: each field with steps
reads its own alias column (`transfer.FeedRules`), the shared column is never rewritten, and the feed keeps skipping the document copy when none
of its fields cleans (the volume optimisation above is unchanged). Guard: `transfer.FieldFillCheck` counts, inside the existing per-row loop
(three increments per mapped field, no extra read), the rows seen, the rows whose source column is filled and the rows that got a value; a
field filled on at most 1 % of the rows its source column fills is reported at the end of the load (`STAGING_LOAD` message and detail
`attributesNotFilled`). A volume run should be read for these lines: an attribute empty on 100 % of a feed is the signature of a mapping defect,
which a row count never shows. Tests: `FeedRulesTest`, `FieldFillCheckTest`. Not run live.

## Lot M10 - the `CHILD_KEYS` sweep in two scans (branch `perf/child-keys-single-pass`, 2026-10-04)

Cause (Vol1m, 576,692 parents, 889,165 child rows, 2026-10-04): the sweep took 728 s, 574 s when it only recomputed. Read from the code (nothing ran live): `K = 3` child
scans with the key of every row recomputed in each (8 M regex-and-Normalizer folds, 2.7 M digests), 576,692 primary-key lookups to read the parents, `K` predicate scans of
the parents to clear the unsupported ones, and 576,692 writes in 1,154 transactions of 500, each write preceded by a second lookup inside the Procedure. The full account and the
new algorithm are in `docs/MATCHING.md` section 50.7; the rules of this file that it applies:

- **One query, modifications applied while browsing it** (`installation/performance.html`, "Accessing and modifying a table"): the parents are read by ONE streaming request in table
  order, each row comes with the value it holds, and a changed row is modified through the `Adaptation` the request returned, in a batch Procedure that does nothing but
  `doModifyContent`. No lookup by key, no predicate read between two writes (that is what forces a Lucene refresh), no per-parent predicate scan. The request is frozen when it is
  created ("Query snapshot isolation"), so it neither sees nor is disturbed by the batches; while it is open the superseded index files of the table cannot be deleted.
- **No work for what did not change**: a parent whose computed value equals the stored one is not written (`writesSkippedUnchanged`); a sweep over unchanged data is two scans and
  `writeBatches = 0`.
- **Memory is a count of `long`s**: 16 bytes per child row (two parallel arrays), sorted in place, instead of a map of strings; the budget is 8,000,000 child rows (128 MB) for the
  default `hash` encoding (`ChildKeysPass.MAX_PAIRS_IN_MEMORY`), 400,000 for `plain`. Above the budget the parents are split in hash partitions and each table is scanned once per
  partition; a child's key is computed only in its parent's partition, so a row is still folded once. Distinct raw values of a part are folded once (LRU, 50,000 per part).
- **Write batch >= 5,000** for this field-only write (`Settings.transferBatchSize` = 500 when larger wins): 116 commits for Vol1m instead of 1,154. A refused batch undoes up to 5,000
  parents; the marker stays empty and the next run recomputes.

Tests: `ChildKeysSweepTest` (parents with 0/1/many children, a child of a missing parent, a removed child clearing its parent in the same pass, a moved child, unchanged data = zero
writes, one scan of each table, writes through the scanned rows, partitions giving the same values and folding each child once, the hash-collision restart, `sortPairs` against a
reference sort, a single parent with 200,000 children), `ChildKeysDerivationTest` (`part` equal to the old fold over a random corpus, `KeyEncoder` equal to `token` for both encodings).
A synthetic in-memory run of the sweep itself (576,692 parents, 889,165 children) takes about 1 s of CPU: the time left in EBX is its reads and its writes.

What was NOT verified (nothing in this lot runs live): that EBX accepts writes through the scan's `Adaptation` while the scan is open (the documented snapshot isolation says it does; mapped-mode
PostgreSQL invalidates cursors at commit); the time of the two scans; the cost of 576,692 modifications even in 116 transactions. Read `childScanMs`, `parentPassMs`, `writeMs`,
`writeBatches`, `scans`, `parentsRead` and `writesSkippedUnchanged` in `detail.childKeys[]` of the next Vol1m staging load.


## Lot V3-M - master load: write only the rows that change (branch `perf/master-load-write-on-change`, 2026-10-04)

Measured problem (project Vol1m, 2026-10-04): a master reload of 1,465,857 rows (576,692 persons + 889,165 addresses) with NOTHING changed in staging took 436 to 641 s and each run reported
"Wrote 1465857 row(s)". Read from `ebx.MasterLoader` (nothing ran live): `planRow` made an update row of every staged row whose `Xref` already holds a master key; `upsertOne` looked the master
record up AGAIN inside the Procedure and called `doModifyContent` on it, always setting `_loadId` and `_receivedAt` to the staging row's values - values that differ from the stored ones whenever the
staging row was itself rewritten, and in any case a write EBX has to index and to announce to the table triggers (the Match and Merge add-on's, and since lot T2 the derivation triggers);
`writeXref` then did a second `doModifyContent` per written row on the Factory's own `Xref` table with a `masterTable`/`masterKey` that had not changed. The staging loader had its own skip
(`transfer.IncrementalTransfer.shouldWrite`, a `_sourceHash` on the staging row); the master had none, and gets none here (no XSD change in this lot).

What the lot does (`ebx.MasterLoader`, `transfer.StoredValueEquality`):

- **One read of the existing master record per update row, before any Procedure** (`planRow`, `lookupAdaptationByPrimaryKey` on the key `Xref` holds). The same read answers the merged-away
  question (`MergeCheck.isMergedAway(key, record)`: with matching activated it used to be a second lookup of the same record; with matching not activated, `NEVER_MERGED`, the load did no read
  at all outside the Procedure and now does this one - the lookup the Procedure used to do, moved out of it) and feeds the comparison. The record is handed on in the `RowPlan` and `upsertOne`
  modifies it: **no second read inside the Procedure**. No predicate read happens on the master table during planning, and nothing is written until `flush`, so the rule above ("never interleave
  predicate reads and writes on the same table inside one Procedure") still holds.
- **Compare, then write or skip.** Every entry of `RowPlan.fields` is compared with the master record's value at the same path (`StoredValueEquality.sameValue`). All equal: the row is counted
  `unchanged`, nothing is buffered, no `doModifyContent`, `_loadId`/`_receivedAt` untouched; a batch made only of such rows opens no Procedure. One differs: the row is written exactly as before
  (every planned field + `_loadId` + `_receivedAt`).
- **Equality rule** (`StoredValueEquality`, unit-tested without EBX): null = empty string (EBX stores an empty string as null) and null = empty list; two numbers by value whatever their boxed type
  (`BigDecimal` by `compareTo`, so 10 = 10.0, `Integer` 7 = `BigDecimal` 7); two dates by time in milliseconds; two lists element by element with the same rule; everything else by `equals`. A
  `String` is never compared with a non-string through `toString`: whatever cannot be shown equal is "different" and the row is written as before - a doubt costs a write, never a lost update. What
  `planRow` plans: a plain field is the raw `Adaptation.get(path)` of the staging field (`String`, `Integer`, `BigDecimal`, `Boolean`, `Date`, `List` of those - the staging and the master field share
  the declared type, so the stored side comes back as the same class), a foreign key is the formatted key `String` of the parent's master record (what `Adaptation.get` answers for a foreign key
  field, and what pass 2 already compared with `getString`).
- **`Xref` is written only when it changes** (`writeXref`): the `Xref` rows of the written rows are read before the Procedure; a row that already holds this `masterTable` and `masterKey` gets no
  `doModifyContent` (the ordinary case of an update row), an insert always goes through (new key, and its earlier MASTER reject may have to be resolved - unchanged logic), and no Procedure is opened
  when nothing needs writing.
- **Pass 2 composes** (`resolveDeferredReferences`): it already compared before writing. A deferred (self-reference / cycle) foreign key used to be planned as `null` by pass 1 on every row and patched
  back by pass 2 - a guaranteed change, so no row of such an entity could be unchanged. For an UPDATE row pass 1 now leaves the deferred field out of the plan when staging holds a reference (pass 2
  resolves it and patches only if it differs) and plans `null` when staging holds none (clears a reference removed in staging, written only if the master holds one); an insert still plans `null`.
  To keep the end state of the old null-then-patch, pass 2 also clears a stored reference it can no longer resolve (it used to rely on pass 1's null). A row unchanged in pass 1 whose deferred
  reference changed is therefore still patched.
- **Rejects and script-constraint rules are untouched**: the validation-report read, the counters of every rule (`recordScriptRuleCheck`, they count every row READ) and the REJECT decision happen
  before the comparison, so a staging row that fails a REJECT rule is rejected whether or not its master row would have been unchanged.
- **Progress and stop**: an unchanged row buffers nothing, so the flush threshold is no longer reached every batch; the listener's progress and the stop request are also checked every
  `transferBatchSize` rows READ (pending buffered rows are flushed first when a stop falls between two flushes). Each entity also posts one activity line "N row(s) read, W written, U unchanged".
- **Wording**: `written` = rows really written (inserted + updated); the `MASTER_LOAD` message is "Wrote W row(s) from R source row(s) read (U unchanged, S skipped merged, J rejected, D deferred
  reference(s) resolved, X still unresolved)." with `unchanged` also in `detail.entities[]` and in the REST answer; the job result (`MasterBuildRun`) reads "W row(s) written from R source row(s) read
  (U unchanged, H held back)."; the wizard's Copy cell (`MasterBuildGridReader`) shows `written + unchanged` (the rows the master holds from the copy - an unchanged reload must not read "0 rows").
  `skippedMerged` was never incremented before (the report always read 0): it now counts the rows skipped because their master record is merged. No stored counter exists on the master side
  (`StagingEntity.lastLoadWritten` is staging's), none was added.

What does NOT change: the business values the master holds after a load are the same as before this lot. A value edited directly on a master record differs from the staging value, so the row is still
overwritten (that policy is not this lot's question); a MERGED record is still skipped; a golden record created by the add-on has no staging row and is never touched. What changes is the meaning of
`_loadId`/`_receivedAt` on a master row: "the load that last CHANGED this row" (see `docs/MASTER-ZONE.md` section 15).

Tests: `StoredValueEqualityTest`. `MasterLoader` itself needs a live instance. Not run live.

What remains to measure live: (1) the time of a master reload with nothing changed on Vol1m (expected: the cost of reading 1.47 M `Xref` rows, 1.47 M master records and their values, no write - the read side
is the floor; the 436-641 s were dominated by writes and triggers, but how much is not known); (2) the time with a small change (staging row edited, one new source row); (3) that the second of two
consecutive loads on unchanged staging reports `unchanged` equal to the rows read and `written` 0 - **if `unchanged` stays well below `read` on the second load, a value type is stored differently
from how it is planned**: take a row of the entity whose count never settles, compare the staging value with `Adaptation.get` of the master field (a decimal with a different scale is covered, a
date/time with a different precision, a trimmed or normalised string, a foreign key answered in another form are not) and add the missing case to `StoredValueEquality`; (4) the entity detail
`written`/`updated` of that second load, per entity, tells which entity it is.

## Lot V3-M2 - master load: one streaming read per table instead of three random reads per row (branch `perf/master-load-scans`, 2026-10-04)

Measured problem (project Vol1m, 2026-10-04, EBX 6.3.0-rc2, after lot V3-M): a master reload with nothing changed writes 0 row out of 1,465,857 (576,692 persons + 889,165 addresses) and still
takes 199-263 s: Personne 59-88 s, Adresse 138-174 s. Thread dumps taken during the load put the time in THREE RANDOM READS PER STAGING ROW, each ending in block reads and LZ4 decompression:
(1) `lookupXrefMasterKey` (`Xref.lookupAdaptationByPrimaryKey`), (2) `planRow`'s read of the master record (`lookupAdaptationByPrimaryKey`), (3) the merge check, `AddonMameRecordState.readStateOnly` ->
the add-on's `MetadataHelper.getState` / `RecordMetadataMergeCheck.read` -> a metadataset lookup. A child entity (Adresse) adds one `Xref` lookup per row for its parent's master key
(`ParentKeys`). The cure is the one lot M10 found for `CHILD_KEYS` (574 s to 11 s on the same 889,165 rows): one streaming read per table and a join in memory.

What the lot does (`ebx.MasterLoadReads`, `transfer.RowDigest`, `master.ScanBudget`, `ebx.MameMetadatasetReads#readMergedFunctionalIds`, wiring in `ebx.MasterLoader`):

- **Three indexes per entity, built before its first row, each by ONE request** (`readMode: SCAN` in the step detail; every request is closed before the first write):
  1. **Xref**: `stagingKey -> masterKey`, one request on `Xref` restricted by `./project=... and ./stagingTable=<entity>` (the key is `(project, stagingTable, stagingKey)`; there is no
     other index to use, see CLAUDE.md "`<index>` is IGNORED on 6.3"). A row with no master key yet is absent. Kept up to date as rows are written (below).
  2. **Master digests**: `masterKey -> RowDigest`, one request on the master table (no predicate), reading exactly the fields `differsFromStored` would read (the plan's writable fields, same
     paths, `Adaptation.get`) and keeping a digest of them, never the record.
  3. **Merged-away keys** (only when matching is activated for the entity, the same `MatchPlan.status = APPLIED` fact as before): the `functionalId` of every `RecordMetadata` row of the
     entity's metadataset (`<dataset>_<table>_MDS`, `master.MameMetadataset`) whose `state` is 2 = `MERGED`, one request restricted by `./state=2` (`state` is an `xs:int` enumeration in
     `MetadataTemplate.xsd`: UNSET 0, GOLDEN 1, MERGED 2, PENDING_APPROVAL 3, DELETED 4). This is the very fact `MetadataHelper.getState` answers, so "merged away" keeps its meaning (only
     MERGED; an unmerged golden, state DELETED, is loaded as before). If the predicate is refused the table is read whole and filtered in Java on the same value; if the metadataset or the table
     is missing or unreadable, the entity keeps the per-row check (`mergedRead: LOOKUP`). `MameMetadatasetReads` stays the only class reading the metadataset directly.
- **Planning a row** (`planRow`): the master key comes from the Xref index; when the master index knows that key and the merged question is free (merged keys read, or matching not activated), no
  record and no add-on is read; the planned values are digested the same way and compared with the stored digest. **Equal: `unchanged`, zero random reads.** Different, unknown (a value of a class
  the digest cannot stand for), or a master key the index does not know (a vanished record): the path of lot V3-M, unchanged - the record is read by key, `StoredValueEquality` decides, the row is
  written. A record about to be written is also asked LIVE whether it is merged away (the merged keys date from the start of the entity), so a record the add-on merged since is never written.
- **`RowDigest`**: 128 bits of SHA-256 over a typed, length-prefixed encoding of the values, with the equivalence of `StoredValueEquality.sameValue` field by field (null = "" = empty list at every
  level; numbers by value whatever the boxed type or scale; dates by millisecond whatever the class; lists element-wise; a `String` never equal to a non-String: `"10"` and 10 differ; booleans;
  anything else is UNKNOWN and equals nothing, so a doubt costs a read, not a lost update). `StoredValueEquality` is untouched; `RowDigestTest` pins the digest against it on a table of cases, on
  every ordered pair of a pool of 49 values and on 20,000 random rows. **A deferred foreign key left out of the plan on an update row** (lot V3-M) is left out of the comparison on BOTH sides: the
  digest is one 128-bit hash of the always-compared fields plus one 64-bit hash per DEFERRED field (rare: self-referencing entities), compared only for the fields the row plans. This is the lowest
  memory that keeps the exemption: 40 bytes a record for an entity without deferred fields, +8 per deferred field, against 16 + 8 x F for a hash per field (F = 20 to 40 fields of a person).
- **Parent keys of a child entity**: `ParentKeys` resolves a foreign key through the PARENT entity's Xref index (still in memory, it was built when the parent loaded and was updated as the parent's
  rows were written), not one lookup per child row. With matching activated it also used to follow the golden-record chain with a master-record read plus add-on reads per distinct parent; a parent
  absent from the merged keys is now answered "not merged" directly, **only while no row was written to the parent's table in this load** (a write can make the add-on merge a record on its own;
  after the first write the live chain is used, as before). A load that wrote nothing to the parent - the unchanged reload - takes the whole saving.
- **Pass 2** (`resolveDeferredReferences`) keeps its logic and reads the entity's own `Xref` key from the same index (it includes the keys inserted in pass 1: `recordWritten` puts each written row's
  key in the map after its `Xref` write) and its parents' keys through `ParentKeys`. An entity's Xref index is kept after its pass 1 only while a later entity, or pass 2, reads it as a parent;
  everything else is released as the entity ends (the master index and the merged keys always). Pass 2 still reads each candidate's master row to compare a deferred reference (self-referencing
  entities only, and only rows that carry one) - left as it is.
- **Memory and fallback** (`ScanBudget`): the indexes are built only when the entity's staging table AND its master table have at most 5,000,000 rows (`mdm.factory.masterLoad.maxIndexedRows`, read
  at each load; 0 or less disables the indexes). Above it, or when a scan fails (logged, the load goes on), the entity runs in `LOOKUP` mode: lot V3-M exactly, same counters. Per row (JDK 17+,
  compressed pointers, keys like `SAP|123456` and a decimal master id): Xref index about 145 bytes (staging key 56, master key 48, map node 32, table about 8), master index about 130 (key 48, node
  32, table 8, digest 40, +8 a deferred field), merged keys about 90 each. For Vol1m: Adresse 889,165 rows = about 129 MB + 116 MB, Personne 576,692 rows = about 84 MB + 75 MB, and the peak during
  Adresse is Personne's Xref index (kept for the foreign keys) + Adresse's two = about 330 MB. At the bound, one entity's two indexes are about 1.4 GB (the FastTrack JVM has `-Xmx8G`). The maps are
  pre-sized from the table sizes, so there is no rehash.
- **Progress and stop**: each scan looks at the stop request every 20,000 rows (which also keeps the job's inactivity deadline alive) and posts a line ("reading the Xref index (one scan)", "reading the
  master table (one scan)", "reading the merged records (one scan)"); a stop during the build ends the load cleanly before the entity wrote anything.
- **Step detail, per entity** (`MASTER_LOAD` `entities[]`, new fields only): `readMode` (`SCAN`/`LOOKUP`, with `lookupReason` for the latter), `xrefIndexRows`/`xrefIndexMs`,
  `masterIndexRows`/`masterIndexMs`, `mergedRead` (`NEVER`/`SCAN`/`LOOKUP`), `mergedIndexRows`/`mergedIndexMs`, `unchangedByIndex` (rows classified from the index alone),
  `masterRecordReads` (master records read by key to plan a row) and `xrefLookups` (`Xref` rows read by key, own rows and parents). **In SCAN mode an unchanged reload shows
  `masterRecordReads: 0` and `xrefLookups: 0` on every entity**; the two counters are what to look at to see whether the saving happened.
- **Behaviour does not change**: the same rows are inserted, updated, left unchanged, skipped as merged and rejected as in lot V3-M for the same data; the script-constraint rule checks and the rejects
  are untouched (they run before the comparison); messages and counters are as before. The only difference that can exist: a record the add-on merges AS A SIDE EFFECT of a write of this very load
  and whose staging row is unchanged counts `unchanged` instead of `skippedMerged` (a changed row is asked live and skipped as before). No XSD change.

Tests: `RowDigestTest`, `ScanBudgetTest` (pure). `MasterLoader`/`MasterLoadReads` need a live instance. Not run live, nothing deployed.

What remains to measure live: (1) the time of a master reload with nothing changed on Vol1m, per entity: the expectation is `xrefIndexMs + masterIndexMs + mergedIndexMs` plus a pass over 1.47 M
staging rows - three streaming reads instead of 4.4 M random ones; if one index dominates, its `*IndexMs` says which; (2) **that SCAN classifies exactly like LOOKUP**: run the reload twice on
unchanged staging, once with the default and once with `-Dmdm.factory.masterLoad.maxIndexedRows=0` (restart, or set it from a console snapshot of the JVM options), and compare the two steps' entity
details: `read`, `written`, `inserted`, `updated`, `unchanged`, `skippedMerged`, `rejected`, `deferredResolved`, `deferredUnresolved`, `referencesToGolden` must be identical, with `readMode`
`SCAN` / `LOOKUP` the only difference, `masterRecordReads` and `xrefLookups` 0 in SCAN against about the row count in LOOKUP; (3) the same comparison after a small change in staging (a few
edited rows, one new source row) - the changed rows must come out `updated`/`inserted` in both modes; (4) on a project with matching activated and some MERGED records: `skippedMerged` and
`referencesToGolden` equal in both modes, `mergedRead: SCAN` (if it says `LOOKUP`, the metadataset or its `RecordMetadata` table could not be read: look at `MameMetadatasetReads`); (5) peak heap
during Adresse (about 330 MB expected for the indexes) and that the scans did not make the instance unusable for other users (they are streaming reads of three tables, not snapshots held open for
the length of the load: the master and Xref requests are closed before the entity's first write).


**Fix measured the same day (lot V3-M2 fix, branch `fix/master-load-parent-merged-refresh`).** On Vol100k (57,568 persons, 88,991 addresses) a master load where ONE Personne row changed took Personne 1.0 s but Adresse 28.0 s instead of 1.9-2.4 s, with 0 Adresse row written: `MasterLoadReads.knownNotMerged` answered from the pre-built merged keys only while `writes == 0`, so after Personne's single write every parent foreign key of every Adresse row went through the live add-on check (on Vol1m: minutes). The merged-key read itself is one streaming request of 1-30 ms (`mergedIndexMs`). Now, when an entity's own pass is over (`MasterLoader`, right after its last flush) and rows were written to its table, `MasterLoadReads.refreshMergedKeys` reads the merged keys AGAIN once (same `readMergedFunctionalIds` call) and restarts `writesSinceMergedRead`, the counter `knownNotMerged` tests (`writes` keeps its meaning for the detail); the re-read sees any merge the entity's own writes caused, so the pre-built answer is as fresh as the live one. A write after that (pass 2 patches of a self-referencing entity) counts again; a failed re-read keeps the live check. Step detail: `mergedRefreshMs` and `mergedRefreshRows` on an entity that refreshed. To see live: Adresse back to about 2 s on the one-changed-row load, `mergedRefreshMs` of a few ms on Personne.

## Lot MP - the add-on's matching is paused during a master load of a REALTIME project (branch `feature/master-load-matching-pause`, 2026-10-04)

On Vol1mF an "on update" matching policy made the add-on run an inline matching at every batch commit of a master load (about 50 ms per batch of 500, harmless alone); on the 8th batch of a 634,295-row
load a clustering thread recovered the table's Lucene index to an older transaction and every later read of it failed until Tomcat was restarted (product defect). A load of a project whose
`matchingTrigger` is `REALTIME` now pauses the matching on its master tables (`set_table_settings pauseMatching`), resumes them in a `finally`, then matches what was written meanwhile: records
modified since the pause or never matched, up to 5,000 by explicit key chunks of 200, above that one run on the whole table. Cost: one streaming read of the master table and one of the metadataset's
keys per paused table (`matchingPause.tables[].catchUp.scanMs`), the matching runs themselves (a whole-table run after a bulk load is the "all" scope of the matching run). `MANUAL`: nothing.
The `MASTER_LOAD` (and `STAGING_LOAD`) step also always ends now - a read failure used to leave it `RUNNING`. Rules, limits and what a live run must confirm: `docs/MASTER-ZONE.md`, section "Matching
paused during a load (lot MP)".

## Lot V3-S - staging load, scope `all`: write only the rows that change (branch `perf/staging-full-scope-write-on-change`, 2026-10-04)

Measured problem (project Vol1m, 2026-10-04, EBX 6.3.0-rc2): a staging load in scope `all` - the "re-apply mappings, cleaning and derivations to every row" mode, the one the wizard always asks for -
with NOTHING changed "wrote" 1,465,857 rows in 349 s. Read from `ebx.StagingLoader` (nothing ran live for this lot): `IncrementalTransfer.shouldWrite` answers true as soon as the scope is `all`, so
`processRow` buffered every row for `upsertOne`, which called `doModifyContent` whatever the values. Each submitted row runs the staging table's script triggers (lot T1: about 0.08 ms per row), gets
`_loadId`/`_receivedAt` re-stamped (so every row looks freshly received), and then `writeXref` did a second `doModifyContent` per row on the Factory's `Xref` with a new `occurredAt`. Scope `new` was
unaffected (it skips on `_sourceHash` and reads only new landing rows) and is left exactly as it is.

What the lot does (`ebx.StagingLoader`, `transfer.StagingRowDiff`, `transfer.IncrementalTransfer`):

- **Scope `all` still runs the whole pipeline on every landing row** (mappings, cleaning, derivations, quality rules, foreign-key resolution), then **compares before writing**. The existing staging row is
  read ONCE per row, in `processRow`, outside any Procedure (`lookupAdaptationByPrimaryKey` on the key the row will have - nothing is written before `flush`, so no predicate read is interleaved with a write in
  one Procedure, the rule of `installation/performance.html`), compared with the planned values, and handed on in the `RowPlan`: `upsertOne` modifies that record - **no second read inside the Procedure**
  when the row exists. A row found missing, a key planned twice in the same batch (two landing rows computing the same staging key: the second is never compared against a row read before the first was
  written), and every scope `new` row keep the lookup inside the Procedure, as before.
- **Form of the comparison (shown exact).** `upsertOne` writes with `setValueFromXsString(xs, path)`; `javap -c` on `com.onwbp.adaptation.uifacade.CfContentHolder` (ebx-lib.jar of FT 6.3 RC) shows its body is
  `getNode(path)` then `SchemaNode.parseXsString(xs)` then `setValue(parsed, path)`. So the value a write would store is `parseXsString(planned)`; `StagingRowDiff` parses the planned string the same way and
  compares the TYPED value with `Adaptation.get` through `StoredValueEquality.sameValue` (decimal by value so `"1.0"` = `1`, date by instant so a date-time with or without milliseconds is the same, empty
  string = null, `Integer` = `BigDecimal` of the same value). API read by `javap`: `SchemaNode.parseXsString(String) throws ConversionException`, `SchemaNode.formatToXsString(Object)`,
  `SchemaNode.getFacetOnTableReference()`, `isTerminalValue()`, `isTerminalValueComputed()`, `getMaxOccurs()`. A second sufficient proof is accepted when the typed comparison finds a difference: the stored
  value formatted with `formatToXsString` is the planned string. A foreign key (a column with a `tableRef` facet) is compared as its key text - the planned value is `PrimaryKey.format()` of the parent
  and `Adaptation.get` answers that same text (the V3-M measurement, and what pass 2 already compares with `getString`); it is never parsed. **Whatever cannot be shown equal is "different" and the row is
  written exactly as before**: a text the node cannot parse (the write fails the same way and rejects the row), a multi-valued or computed column, a planned field the table does not define, any exception
  while reading. A doubt costs a write, never a lost update.
- **Compared / not compared.** Compared: every entry of the planned field map; `_sourceHash` when the table carries it (a row whose landing hash changed but whose mapped values did not is written, otherwise
  scope `new` would later mis-skip it); `_status` (must already be `STAGED`, else the row is written). Not compared: `_loadId`, `_receivedAt`, `_rejectReason`; an APPLIED script-derived attribute (not in the
  planned map - the triggers keep it; it can only be stale after its function changed, which is the derivation backfill's job, not a load's: whether EBX fires `beforeModify` for a no-op modification was never
  measured, so scope `all` never reliably did it); an unapplied one is planned as null and compares like any field. An unchanged row therefore keeps its `_loadId`/`_receivedAt`: on a staging row they mean "the
  load that last CHANGED this row" in both scopes (already the case in scope `new`).
- **Counters and wording.** `unchanged` (already in `Counters`, `detail.entities[]`, the REST answer, `StagingEntity.lastLoadUnchanged`, the step message) now counts these rows in scope `all`; `written` counts only
  rows really written. A batch made only of unchanged rows opens no Procedure and no Xref write. Every root row adds exactly one entry (reject, stage or unchanged) to the root buffer, so the progress tick and
  the stop check still run every `transferBatchSize` rows READ on a load where nothing is written (the case lot V3-M hit on the master). Truthfulness: `RunProgress.Unit` and `StagingLoadListener` carry
  `unchanged` (new default overload, the master's listener is untouched); the wizard's live cell reads "N rows" for a child cell from written + unchanged and adds "M unchanged" to its line (en/fr); the activity
  line says "X of T rows read, W written, U unchanged"; the job result reads "W row(s) written from R source row(s) read (U unchanged, H held back)"; the staging summary's per-entity rows and the master
  readiness evidence (`lastLoadWritten`) count written + unchanged - an unchanged reload must not read as "0 rows" or as "staging never loaded". The "Written this run" column of the live grid stays what its
  header says.
- **Rejects and order of operations.** The comparison happens after every reject check (constraint pre-check, REJECT-severity quality rules, foreign-key lookups, `sourceId`) and after the empty-child check, so a
  row that fails a REJECT rule is rejected whatever its unchanged status. One difference to know: a row that EBX itself would now refuse at save time (a constraint added to the staging model after the row was
  written, not covered by `PipelineCompat.checkConstraints`) used to be rejected by a scope `all` reload and is now left alone when its values are unchanged.
- **Landing status.** Written back by `writeLandingStatus` for every root row before this lot, including the many already `STAGED` (a modification each). Now, in scope `all`: a table read by one root feed records
  only rows whose status changes (`decideLandingStatus`, read from the landing row already in hand), a batch with nothing to record opens no Procedure; a table SHARED by two root feeds still records every decision
  (the merge "REJECTED wins" needs both answers) and `flushPendingLandingStatus` drops the up-to-date rows when it writes (rows read before the Procedure, none = no Procedure). A landing row still `NEW`, `null` or
  in the other status is written exactly as before - the end state of every landing row is the same. Scope `new` passes `false` and runs the old code.
- **`Xref` (staging side).** Scope `all` only: for the rows that ARE written, the `Xref` rows are read before the Procedure (indexed lookups on the natural key); a row that already holds `project`, `stagingTable`,
  `stagingKey`, `landingDataset`, `landingTable`, `landingKey`, `sourceSystem` and `sourceId` is not rewritten - `loadId`/`occurredAt` of an `Xref` now say when it was last written. A first-time insert always goes
  through the Procedure so its earlier STAGING `TransferReject` is resolved exactly as before. Scope `new` writes every `Xref` as before.
- **Unchanged**: the end state of every business field after a scope `all` load; empty-child handling; the deferred-reference pass; the CHILD_KEYS sweep (it runs when the scope is `all`, whatever was written);
  the UNIQUE check; the distribution pause around a scope `all` load.

Tests: `StagingRowDiffTest` (typed comparison, null = empty, `"1.0"` against 1, foreign keys, the status rule, the hash rule, every doubt = difference, the `Xref` texts), `IncrementalTransferTest` (landing status rule),
`RunProgressTest` (`unchanged` through the JSON). `StagingLoader` itself needs a live instance. Not run live.

What remains to measure live: (1) the time of a scope `all` reload with nothing changed on Vol1m (the floor is now the READ side: landing row, `MappingEngine`, one staging lookup per row, one `Xref` lookup per row
that is written - none when unchanged); (2) with a small change (a mapping edited, one landing row changed); (3) **two consecutive scope `all` loads on unchanged data: the second must report `unchanged` = rows read
and `written` 0, per entity (`entities[]` of the answer, `StagingEntity.lastLoadWritten`/`lastLoadUnchanged`). If `unchanged` stays well below `read` for an entity, a column's planned form never equals its stored
form**: the entity detail names the entity, take one row whose count never settles, compare `parseXsString(planned)` with `Adaptation.get` of each column (a date-time stored with less precision than planned, a
decimal parsed to another scale are covered; a string normalised by the type, a foreign key answered in another form, a column the comparison treats as not comparable are not) and add the missing case to
`StagingRowDiff`/`StoredValueEquality`; (4) the entity `written` of the first load after a mapping change = the rows whose result really differs.


## Lot V3-S2 - staging load, scope `all`: one streaming read per table instead of random reads per landing row (branch `perf/staging-load-scans`, 2026-10-04)

Measured problem (project Vol1m, 2026-10-04, EBX 6.3.0-rc2, after lot V3-S): a scope `all` staging reload with nothing changed writes 0 row out of 1,465,857 (0 written / 1,465,857 unchanged) in 94.5-124 s on a quiet
machine (349-379 s before V3-S), but 169 s and 315 s when the machine was loaded - while the master load, which since lot V3-M2 reads each table once, stayed at 32-44 s under the same load. What is left in
`ebx.StagingLoader.processRow` is the same disease the master had: RANDOM reads per landing row, each ending in block reads and LZ4 decompression, so the time follows the load of the machine. Per row read in scope `all`:
(1) `lookupAdaptationByPrimaryKey` of the existing staging row, to compare it (1.47 M); (2) for a child feed, `parentTable.lookupAdaptationByPrimaryKey` of the parent row, the foreign-key check; (3) for every empty child,
`lookupAdaptationByPrimaryKey` of its own key to count a stale row (142,003 on Vol1m). The cure is again one streaming read per table and a join in memory.

What the lot does (`ebx.StagingLoadReads`, `transfer.StagingRowDigest`, `master.ScanBudget`, `staging.DerivedFieldRule#producesKey`, wiring in `ebx.StagingLoader`) - **scope `all` only; scope `new` runs the same code as before,
every new branch is behind `reads != null`**:

- **One index per staging TABLE, built by ONE request, before the first row of the first root feed whose tree needs the table** (`readMode: SCAN`; the request is closed before the first write): `stagingKey ->
  digests`, key = `PrimaryKey.format()`. A root feed's tree needs: its own entity, the entity of every descendant feed (a child entity such as Adresse is loaded by its parent's root feed, not by a turn of its own), and the
  target entity of every immediate foreign key (the parent check). The index is shared by every feed that writes the table and is dropped after the last root feed of the load that needs it (`releaseAfter`), and all of
  them when the entity loop ends (pass 2 and the child-keys pass read the tables themselves).
- **What the digest is** (`StagingRowDigest`, built on `RowDigest`, whose encoding is the equivalence of `StoredValueEquality.sameValue` and is unit-tested against it): 128 bits of SHA-256 over, in this order, the typed
  value of each column of a LAYOUT (sorted column names), `_sourceHash` when the live table carries it, and the flag "`_status` is STAGED" - exactly what `StagingRowDiff.unchanged` compares. The PLANNED side builds
  the typed values the way a write does (`SchemaNode.parseXsString` of the planned xs-string, the call `setValueFromXsString` makes; the key text itself for a foreign key; null for a planned null) with the flag true; the
  STORED side reads `Adaptation.get` of each column and `_status`. **Equal digests imply `StagingRowDiff.unchanged`** (the other direction is not claimed), pinned by `StagingRowDigestTest` on a table of cases and on 100,000
  random rows of planned and stored values (equal digests never contradict `StagingRowDiff`; 100+ equal cases so the test is not vacuous).
- **The three questions, answered from the index** (the three replaced lookups): (1) *unchanged?* the planned digest equals the stored one: `unchanged`, **no read**; a different or unknown digest sends the row down the lot
  V3-S path unchanged (read the row by key once, `StagingRowDiff`, write when it differs); a key the index does not hold is a new row (nothing to read or compare; `upsertOne` creates it, looking the key up itself as
  before). (2) *empty child already staged?* the key is in the child table's index. (3) *parent row there?* the key is in the parent entity's index. Rows inserted by this load are added to the key set when written.
- **Several feeds on one table: layouts.** Each feed plans its own column set (`plannedColumns`: forced-null defaults, writable mapped fields, immediate foreign-key fields, a child feed's `parentLinkField`, the derived
  fields `DerivedFieldRule.apply` adds - mirrored from `processRow`, `producesKey` pinned against `apply`). Which feed will plan a stored row is unknown when the table is scanned, and the feed set of a canonical
  entity can differ in columns per source, so the scan computes **one 128-bit digest per DISTINCT layout of the entity** (`StagingLoadReads.addLayout`; nearly always ONE: every feed plans every accepted attribute, supplied or
  forced to null, so feeds differ only by the child link) and a row is compared under the layout of its own feed. **Why this layout and not one 64-bit hash per column** (the other exact choice, which would let any feed compare
  any subset): a hash per column costs 8 x F bytes (a person has 40 columns: 320 + 16 bytes) against 16 + 16 x L for L layouts (32 bytes for the usual one); both are exact because a planned map whose columns are not exactly the
  layout's (`StagingRowDigest.sameColumns`; `layoutMismatches` counts them) is never digested, it takes the read path. More than 4 layouts for one entity: no digest kept (`digestsDisabled`), the key set still serves
  checks (2) and (3) and every row takes the read path. A column the digest cannot stand for - a planned value on a multi-valued, computed or non-terminal column or on a column the table does not define, a text the node
  cannot parse, a stored value of a class the digest cannot encode - makes the planned (or stored) digest UNKNOWN, which equals nothing: the row is read and `StagingRowDiff` decides, as in lot V3-S. A row equal only
  through `StagingRowDiff`'s second test (the stored value formats to the planned text without parsing to an equal typed value) also has different digests and is read - a doubt costs a read, never a lost update.
- **Staleness.** The index is built before the table's first write of the load. A row COMMITTED by this load gets its entry replaced by "unknown" (`recordWritten` in `flushOne`, for the rows `BatchRetry` reports written): a later
  landing row with the same key (two landing rows computing one staging key, in two batches) is read and compared with what the first one wrote, as before; a key inserted by the load is present for the parent and empty-child checks of the
  next batches. A key planned twice in ONE batch is written and never compared (lot V3-S's `plannedKeys`, applied before the index is consulted). **Pass 2** (deferred references) patches foreign-key columns that are in no
  layout (a deferred reference is not planned in pass 1), runs after the indexes are dropped and compares before it writes: a load after a pass 2 patch compares unchanged exactly as in lot V3-S.
- **Memory and fallback** (`ScanBudget`): an index is built only when the table has at most 5,000,000 rows (`mdm.factory.stagingLoad.maxIndexedRows`, read at each load, 0 or less disables; the master's property is a separate one).
  Per row, JDK 17+ compressed pointers, keys like `SAP|123456#MAILING`: key string 48-56 bytes, `HashMap` node 32, table slot about 11, `long[2]` digest 32 (+16 per extra layout) = **about 125-135 bytes**.
  889,165 addresses: about 115 MB; 576,692 persons: about 75 MB; the peak during Personne's root feed is Personne + Adresse (its child entity), about 190 MB; at the bound one table is about 0.7 GB against the 8 GB of the FastTrack JVM. The map is
  pre-sized from the table size (no rehash). Above the bound, or when a scan throws (logged, the load goes on), the table runs in `LOOKUP` mode: lot V3-S exactly, same counters, `lookupReason` says why.
- **Progress and stop**: a line "<entity>: reading the staging index (one scan)." is posted before each build; the scan looks at the stop request every 20,000 rows (which also keeps the job's inactivity deadline alive); a stop during
  a build ends the load as STOPPED before the feed read a row.
- **Step detail, per entity** (`STAGING_LOAD` `entities[]` and the REST answer, scope `all` only, new fields only): `readMode` (`SCAN` / `LOOKUP`, with `lookupReason`), `stagingIndexRows`, `stagingIndexMs`, `layouts`,
  `unchangedByIndex` (rows classified from the index alone), `newByIndex` (key absent: new row, nothing read), `stagingRowReads` (staging rows read by key in `processRow`), `layoutMismatches`, `writtenKeysRecorded`,
  `parentChecksByIndex` / `parentChecksByLookup` and `emptyChildChecksByIndex` / `emptyChildChecksByLookup` (counted on the entity that makes the check). **In SCAN mode an unchanged reload shows `stagingRowReads: 0`,
  `parentChecksByLookup: 0` and `emptyChildChecksByLookup: 0` on every entity**, and `unchangedByIndex` = `unchanged` = rows read.
- **Behaviour does not change**: the same rows are written, left unchanged, rejected and skipped as in lot V3-S for the same data, the landing status end state, the `Xref` writes, the counters and the messages are the
  same (the `Xref` of a WRITTEN row is still read by key before its write - one lookup per written row, none per unchanged row). Rejects are decided before the comparison as before. No XSD change, no new dependency.

Tests: `StagingRowDigestTest` (pure), `RowDigestTest` (`words()`), `ScanBudgetTest` (staging bound), `DerivedFieldRuleTest` (`producesKey` against `apply`). `StagingLoader`/`StagingLoadReads` need a live instance. Not run live, nothing deployed.

What remains to measure live: (1) the time of a scope `all` reload with nothing changed on Vol1m, per entity: the expectation is `stagingIndexMs` plus a pass over the landing rows (landing read, `MappingEngine`, digest), flat under
machine load like the master's; (2) **that SCAN classifies exactly like the lot V3-S path**: two consecutive scope `all` loads on unchanged data; the second must report, per entity, `written` 0, `unchanged` = `read`,
`readMode: SCAN`, `unchangedByIndex` = `read` (minus `emptyChildrenSkipped` rows, which are neither written nor unchanged), `stagingRowReads` 0, `parentChecksByLookup` 0, `emptyChildChecksByLookup` 0, `layoutMismatches` 0; then
the same load with `-Dmdm.factory.stagingLoad.maxIndexedRows=0`: every count identical except `readMode: LOOKUP`, `unchangedByIndex` 0 and `stagingRowReads`, `parentChecksByLookup`, `emptyChildChecksByLookup` about the row counts; (3) `unchangedByIndex` well below
`unchanged` means digests that never match for a column (look at which entity, then compare `parseXsString(planned)` with `Adaptation.get` of its columns - the rows then fall back to `stagingRowReads`, still correct) and
`layoutMismatches` above 0 means `plannedColumns` disagrees with `processRow` for a feed; (4) after a small change (a mapping edited, a few landing rows edited, one new row): the changed rows must come out written / inserted in
both modes with the same counts; (5) peak heap during Personne's root feed (about 190 MB expected for the two indexes) and that the scans did not make the instance unusable for other users.

## Lot V3-M3 - the golden of a merged parent and the golden keys, read by scan (branch `perf/golden-resolution-scans`, 2026-10-04)

Measured problem (project Vol1mF, 2026-10-04, EBX 6.3.0-rc2: 576,692 source persons, 417,841 of them merged automatically into 180,746 golden records, 889,165 addresses of which 637,795 are children of a merged
person):

1. **A master reload that writes NOTHING took 389 s instead of about 40 s.** Personne 18.6 s (the 417,841 merged skipped from the merged-keys index: fine), **Adresse 370 s with 0 row written**, `referencesToGolden: 637795`.
   For every DISTINCT merged parent `ParentKeys.resolve` asked the add-on LIVE for the golden record (`targetCheck.goldenOf` -> a master record lookup, then `MetadataHelper.getState`/`getTargetRecord`): 417,841 random
   reads. The first load after the merges (634,295 addresses re-pointed) took 725 s for the same reason.
2. **`GoldenKeyRefresher.refresh`** (after every matching run, every catch-up of lot MP, and the `refresh-golden-keys` route) worked per record: one `Xref` lookup by key, one master record lookup, one add-on state read.
   A refresh where nothing changes ("576692 unchanged") took about 50 s, and the first one after the merges is part of the 18 min 49 of the person matching run.

What the lot does (`master.GoldenKeyScanPlan`, `ebx.MameMetadatasetReads`, `ebx.MasterLoadReads`, `ebx.MasterLoader`, `ebx.GoldenKeyRefresher`, `master.ScanBudget`; no XSD change, no new dependency):

- **The golden of a merged record comes from the same single read as the merged keys.** `MameMetadatasetReads.readMergedGoldens` is the lot V3-M2 request (`RecordMetadata` restricted by `./state=2`, whole table filtered
  in Java when the predicate is refused, null when the metadataset, the table or `functionalId`/`state` is missing) and also reads each row's `goldenId`: it answers `functionalId -> goldenId` (the empty string when the
  row has none; a missing `goldenId` FIELD is not a failure, every value is then empty and the add-on is asked as before). `readMergedFunctionalIds` is now its key set (same contract for its other caller).
  `MasterLoadReads` keeps that map; `mergedKeys` is its key set (no second copy). **`goldenOfMerged(masterKey)`** answers under the SAME staleness rule as `knownNotMerged`: only while no row was written to that table since
  the merged keys were read (a write can make the add-on merge or re-point something as a side effect; `refreshMergedKeys` reads both again after the entity's own pass). In LOOKUP mode, or when the keys were set without
  goldens, or for a record whose `goldenId` is empty, it says "unknown" (null) and the caller asks the add-on, exactly as before.
- **`ParentKeys.resolve`**: each hop of `SurvivingRecord.follow` asks the index first - `knownNotMerged` (answer: not merged, the chain ends), else `goldenOfMerged` (answer: the golden; a golden that was itself merged later
  is followed on the next hop through the same map; the existing cycle/hop guards apply) - and the live add-on call remains only when the index cannot answer. `referencesToGolden` keeps its meaning. New per-entity counters
  in the `MASTER_LOAD` detail: **`goldenHopsByIndex`** (hops answered by the index, merged or not) and **`goldenHopsLive`** (hops asked of the add-on); the second is 0 for an unchanged reload in SCAN mode.
- **`GoldenKeyRefresher.refresh` by two scans.** (1) `MameMetadatasetReads.readRecordMetadataIndex`: one streaming read of the whole `RecordMetadata`, `functionalId -> ` an encoded state (a shared constant for any state but
  MERGED, the golden's key for a MERGED record, a second constant for MERGED without golden: no object per row beyond the key). (2) One streaming read of the entity's `Xref` rows (same predicate as before: project,
  `masterTable`, `masterKey` not null; staging key not needed). Each row is decided at once by `master.GoldenKeyScanPlan.accept` (the unchanged `GoldenKeyRefreshDecision.decide`; a MERGED record's golden is followed through
  the index, cycle-guarded, so the key written is the survivor) and **only the rows whose `goldenKey` differs are kept**; they are then read by key and written in batches of 1,000 (one `Procedure` each), nothing else is read
  by key. Same `set` / `cleared` / `unchanged`, same public signatures (`refresh`, `refreshScoped` untouched). The result also carries **`scanMs`** (the two reads), **`readMode`** (`SCAN` or `PER_RECORD`) and **`readReason`**
  (why the per-record path ran); the matching run's `goldenKeyRefresh`, the catch-up's `goldenKeyRefresh` (lot MP: it only runs when a record was selected - `NOTHING_TO_MATCH` returns before it) and the
  `refresh-golden-keys` route record them.
- **Memory and fallback.** Strings only, same order as the other indexes: the merged map about 90 bytes per MERGED record (the key set it replaces cost the same) + about 50 per distinct golden key (golden strings are shared
  in a group); the refresher's index about 90 bytes per `RecordMetadata` row (key 48, map node 32, table 8) + 50 per distinct golden. Vol1mF: 757,438 `RecordMetadata` rows = about 70 MB (+ 9 MB of golden strings) for the
  refresh, 417,841 merged = about 38 MB + 9 MB for the load. The refresh index is guarded by `ScanBudget` (property **`mdm.factory.goldenRefresh.maxIndexedRows`**, default 5,000,000 = about 0.5 GB, 0 or less disables, read at
  each refresh); above the bound, when the add-on's Java API or metadataset cannot be used, when the master table cannot be resolved, or when a read fails (logged), the per-record path of lot 2a runs and says why in `readReason`.
- **What does not change**: the rule that decides a row (`GoldenKeyRefreshDecision`, below), the set of rows refreshed, `refreshScoped`, the master a load produces. See "Not identical" for the two edges.

The rule per record state, as the per-record path applied it (and the scan applies it): no `RecordMetadata` row with a state -> blank `goldenKey` left alone, otherwise cleared; any state except MERGED (UNSET, GOLDEN,
PENDING_APPROVAL, DELETED) -> `goldenKey` = the record's own master key; MERGED with a `goldenId` -> the golden's key; MERGED without one -> the own key; set only when it differs from the stored value.

Not identical (read from the code, to confirm live): (1) the per-record path took the golden from ONE `getTargetRecord` call; the scan follows the chain through the index to the survivor. They coincide when the add-on
re-points the members of a group whose golden is merged later (then `goldenId` is already the survivor); if it does not, the scan writes the survivor where the old path wrote the intermediate golden. (2) A master record that
no longer exists but still has a `RecordMetadata` row reads "no metadata" in the old path (cleared) and "present" in the scan (set); not reachable unless a master row is deleted by hand next to its metadata.

Tests: `GoldenKeyScanPlanTest` (every state, a two-hop chain, a cycle, a self-merge, no metadata, a merged record with no golden id, a key already right, counters and writes, same answer as the per-record decision),
`MasterLoadReadsGoldenOfTest` (answers, staleness, keys-only re-read, the chain through the index with live asked only when it cannot answer), `ScanBudgetTest`. The scans themselves need a live instance.

To measure live (nothing deployed, nothing run): (1) an **unchanged reload of Vol1mF**: Adresse `goldenHopsLive: 0`, `goldenHopsByIndex` about 2 x the distinct merged parents, `referencesToGolden: 637795` as before, and a
duration close to an unmerged project's (about 40 s in all, against 389 s); the first load after merges should no longer pay 725 s for the references; (2) the same reload with `-Dmdm.factory.masterLoad.maxIndexedRows=0`
must give identical counters (`goldenHopsLive` then about the row count), which proves the index answers as the add-on does; (3) a matching run's refresh: `goldenKeyRefresh.readMode: SCAN`, `scanMs`, and the
`set`/`cleared`/`unchanged` of the refresh run twice (the second must be 0 / 0 / all) and compared with the same refresh under `-Dmdm.factory.goldenRefresh.maxIndexedRows=0` (`PER_RECORD`) on a copy: the three
numbers must match, which also settles point (1) of "Not identical"; (4) peak heap during the refresh (about 80 MB expected) and the time of an unchanged refresh against the 50 s.

## Lot L2 — parallel landing loads (branch `perf/landing-parallel`, 2026-10-12)

Measured problem (project Vol1mF, 2026-10-04, EBX 6.3.0-rc2): the landing is the slowest stage of a full 1M-row process. 992,479 rows from 3 sources took **1,190 s**, the three loads (282 s + 669 s + 171 s = 1,122 s,
about 1.13 ms per row) running one after the other; the staging and master reloads take under a minute. The sources are independent (each has its own landing dataset, its own Connectors mappings), nothing but the
code made them wait for each other.

**Nothing in this lot was run live** (no EBX instance, no connector, no language model was called from this work). `tools/test_local.sh` is green; that is the only verification it has had.

### What changed

- **Setting `-Dmdm.factory.landing.parallelSources`** (default **2**, `1` = the behaviour before the lot, clamped to 1..4, read at the start of each chain, no restart; documented in `docs/OPERATIONS.md` 3.1). Pure class
  `landing.LandingParallelism` (parse, clamp, `usesPool`, and `schedule`, a model of the chain used by the tests and by "How to measure" below).
- **`ebx.LandingRun.runMainStages`** (the wizard's "Prepare the landing" job, the only place that chains ALL the sources of a project in the wizard): for each source, in the grid's order, ONE thread (the job's) still does
  KEY (per table), TABLE (landing model and dataset), FLOW (per table); then the LOAD of the source is handed to a `LandingLoadPool` and the job's thread goes on with the next source at once. With a bound of 1 (or one
  source) the load stays inline: exactly the sequence of before. The job waits for every submitted load before judging the step (`finally`: `awaitAll`, `close`).
- **`workflow.LandingLoadTaskBean`** (the transfer workflow's landing step with no `projectSource`): the sources load on a pool of `min(bound, sources)` threads instead of in a loop. With a bound of 1 the loop is the old one
  (an `OperationException` stops the step at once). In parallel, an `OperationException` or runtime error of one source keeps the not-yet-started ones from starting, the running loads finish, and the first exception in
  the sources' order is thrown once they have ended - the same exception the loop would have thrown for that source, so the step lands `inError` on the same cause. The result JSON (`sources[]` in order, `failed`) is unchanged.
- **`ebx.LandingLoadPool`** (new): one pool per chain, daemon threads **`mdm-factory-landing-N`**, FIFO queue, closed at the end of the chain, and every open pool is stopped by `FactoryModuleRegistration.handleRepositoryShutdown`
  (`LandingLoadPool.stopAll`, its own `try`, `LinkageError` tolerated). A pool thread runs under the same `Actor` as the thread that submitted (the launcher of a workflow step, the actor is thread-bound), so the
  `PipelineStep` rows keep their "started by" attribution.
- **`landing.LandingRunProgress` is thread-safe** (it was not, and several loads now report into the one grid): a cell is immutable and replaced in a concurrent map, the journal and its cap are under a lock, and the write of the
  grid into `PipelineStep.detail` (the change listener, and `LandingRun.updateDetail`) runs one caller at a time under `writeLock()`, so the last stored JSON is always the newest state and two writes never cross.
- **Final status names the failed sources**: `landing.LandingChainOutcome` - "Landing run finished with failures on: SAP, WEB." (each application once, in the grid's order) instead of "...with some failures." (same text for the
  reconcile path that closes a step from the status poll). Nothing failed: the old "Landing run finished: N of M stages done."

### What stays exactly as it was

- **One `PipelineStep` row per source and step**: `LandingFlows.load` is called per source as before and opens its own `LANDING_LOAD` row (`scope` = the source system); `LANDING_MODEL`/`LANDING_DATASET`/`LANDING_FLOWS` rows are
  written by the same calls. The parent `LANDING_RUN` row and its grid (one row per table, four columns) are unchanged. The only addition is one journal line "Up to N applications load at the same time (...)" when a pool is used.
- **A failure of one source does not cancel the others**: a load's exception, `Error` included, settles ONLY that source's unsettled cells as failed (`LandingRun.failSource`, shared with the per-source catch of the
  preparation) and the other loads go on; a table that fails inside `LandingFlows.load` is counted by it, as before. Stop (the job's Handle) is polled by every load between tables and between polls of a run: all of them stop,
  and a load still waiting for a pool thread when Stop arrives never starts (its cells close as skipped).
- **Job queue and claims: not changed, deliberately.** The chain is ONE job holding the project's `LANDING` resource; the loads run inside it, so there is nothing for a second load of the same project to wait for and the
  queue was not what serialised them. The project-level steps (staging load, master build, matching) are other resources and relate to the landing exactly as before the lot (the wizard orders them; no load of this
  chain outlives the job that holds `LANDING`, because the job waits for them). Widening the claim to "project + source" was NOT done: it would touch `FactoryJobs.claim` (keys are `project|resource`), the whole-project claims
  (`claimAll`) and every job kind on `LANDING`, to gain only that two stand-alone per-application "Read now" / "Generate" buttons (separate jobs `LANDING_READ_RUN`, `LANDING_GENERATE_RUN`) could run side by side. Those two
  still queue one behind the other. If that is wanted later, the place is a source-keyed resource for `READ_STEP` only.
- **`ebx.factory.maxHeavyJobs`** counts the chain as one heavy job; its pool threads are not counted, and neither are they in `jobsMaxParallel` (a separate, bounded pool per chain).

### Why only the LOAD is parallel (reasoned from the code and the docs, nothing measured)

- **Model generation stays sequential.** `LandingGenerator` drives the `data-model` MCP server (`build_data_model`, then `publish_data_model`; one element per transaction on 6.3.0-rc2, one transaction on 6.3.0-gp) with a session token of the launcher;
  nothing in this module or in `CLAUDE.md` says two model builds into the same landing dataspace are safe to run at once (the DMA publishes into a shared repository of models, and a second build calls the same server
  with the same user). It is also not where the time goes at volume (80-100 s per source against hundreds of seconds of load). Flows (`generateOne`) and keys (LLM) are on the same thread for the same reason and because they
  are short. The structure still lets them overlap with loads: while source A loads, the job prepares source B (that is where the saving beyond "two loads at once" comes from).
- **Connectors clients are not shared.** `McpServers.client` says "Not shared between threads"; `LandingFlows.load` creates its own (`McpServers.connectors(session, factory)`) at each call, so each pool thread has its own
  client. The in-JVM bridge behind it (`ConnectorsBridge`) is a pool of at most 8 threads that REFUSES a ninth concurrent call ("bridge is busy"): a load holds one only for the length of one call (start the run, then
  short polls), hence the cap of 4 on the setting.
- **The `Session`** passed to the loads is the queue's system session (or the workflow step's), which several jobs already use at the same time; `Adaptation` handles and `Procedure`s are per call. `LandingFlows.load` uses no
  `EngineClaim` (nothing to refuse on a second thread). `IntakeEngine.sourceLoaded` is a thread-safe notification.

### Expected gain (a model, not a measurement)

`LandingParallelism.schedule` with the Vol1mF numbers (loads 282 / 669 / 171 s, the remaining 68 s of the 1,190 s split as 23 / 23 / 22 s of preparation per source): bound 1 = **1,190 s** (the baseline, to the second), bound 2 =
**715 s** (source B, the 669 s one, starts at 46 s; A then C share the other slot), bound 3 or 4 = 715 s too: **the longest source decides, not the bound**, so with this project the default of 2 already gets all there is. Expect
about 40 % off the chain if the rows/s of one load do not drop when two run together; less if they do (see below).

### Not verified (nothing in this lot runs live)

- **That two loads really run faster together.** They write into different landing datasets, but they share Connectors' own tables: `FieldProvenance` (one XPath scan per changed field and one row created per field, 2/3 of a
  row's cost according to the 2026-09-23 measurement, and it grows with every load), `SyncLog`, the metrics table, and the auto-increment `id` of each. If those serialise (a table lock, an index refresh), the loads overlap
  in time but not in throughput. This is THE number to watch.
- That the EBX repository and the H2/JDBC/Kafka sources behind the connectors tolerate N concurrent bulk writes (disk, CPU: the 8 `ebx-clustering` threads are matching only; a load is mostly the Connectors engine's 4
  transactions per row).
- The cancellation of loads waiting for a thread, the interruption path at module shutdown, and the workflow bean's parallel path against a real step (the pool, the actor and the grid are unit-tested; the calls to Connectors are not).
- That the grid shown by the wizard stays readable with several cells moving at once (the screen reads the same JSON; the journal now interleaves lines of different sources).

### Tests

`LandingParallelismTest` (parse/clamp/default, property read at each call, when a pool is used, bound 1 = the sequential sum, never more loads than the bound, the Vol1mF numbers, preparations never overlap),
`LandingChainOutcomeTest` (final message, failed sources named once in order, one failure leaves the others untouched, and four threads reporting 100 cells into one grid with a serialised listener: no lost update, no torn
JSON, journal cap kept), `LandingLoadPoolTest` (bound respected with real threads, names `mdm-factory-landing-N` and daemon, the submitter's actor carried, a failing task does not stop the others, `stopAll` interrupts).

### How the coordinator should measure it

Deploy, then on Vol1mF (3 sources, 992,479 rows) run "Prepare the landing" three times from an empty landing, with `-Dmdm.factory.landing.parallelSources` = **1**, **2**, then **3**, restarting nothing (the property is read at
each chain; set it in the JVM options and restart once per value, or per `System.setProperty` if you have a console). Compare with the 1,190 s baseline:

1. **The `LANDING_RUN` step duration** (`PipelineStep`): expected about 715 s at 2 and at 3 (same), 1,190 s at 1 (must reproduce the baseline, otherwise the conditions changed).
2. **The three `LANDING_LOAD` rows' own durations**: at 2, B alone may keep 669 s; if A and B each take much longer than 282 s / 669 s while they overlap, the shared Connectors tables are the bottleneck - then the number to report
   is rows/s per load while two run, against rows/s alone (992,479 / 1,122 s = 884 rows/s aggregate when sequential).
3. **Row counts and `LandingTable.lastLoadRows`** identical to a bound-1 run (992,479 in all), the three loads `OK`, and no "bridge is busy" in `kernel.log` (`ConnectorsBridge`, 8 threads).
4. **Thread names in a `jstack` during the run**: `mdm-factory-landing-1`, `-2` (and the job thread preparing the third source); never more than the bound.
5. The final message of the `LANDING_RUN` row, and one deliberately failing source (point one source's connection at a wrong host): "Landing run finished with failures on: <that application>.", the other two `DONE`.
6. Stop in the middle of two overlapping loads: both `LANDING_LOAD` rows end STOPPED and the grid has no cell stuck `RUNNING`.
## Lot IDX - compact indexes (branch `perf/compact-indexes`, 2026-10-12)

Measured problem (Vol1mF, 1,465,857 master rows, 2026-10-04): the three read indexes of a master load (lot V3-M2) and the one of a scope `all` staging load (lot V3-S2) cost about 15 s and about 330 MB of heap
at the peak, because every key was a Java `String` (a staging key `VOL1MCRM|C000123`, a master key, a golden key, a `RecordMetadata.functionalId`) in a `HashMap`: a 48-56 byte string, a 32 byte node, a table slot, and
for the master index a 40 byte `RowDigest` object, i.e. 90 to 155 bytes a row. That is what fixed the bound `maxIndexedRows` at 5,000,000 rows (about 1.4 GB for one entity). This lot changes the structure, nothing else:
**the same indexes, built from the same single scans, answering the same questions, in flat `long[]` tables.**

### What changed (`index/`, `ebx.MasterLoadReads`, `ebx.StagingLoadReads`, `ebx.MameMetadatasetReads`, `ebx.GoldenKeyRefresher`, `master.GoldenKeyScanPlan`, `transfer.RowDigest`)

- **`index.KeyDigest`**: a key text as 128 bits - the first 16 bytes of SHA-256 of its UTF-16 code units (never `getBytes("UTF-8")`, which collapses lone surrogates to `?`). SHA-256 and not Murmur/FNV on purpose: the
  keys come from source systems and a hash anyone can invert would let a crafted source key collide with another record's. About 0.25 microsecond a key. `(0, 0)` is reserved (empty slot) and stored as `(0, 1)`.
- **`index.DigestMap`**: open addressing with linear probing over ONE `long[]`, `[hi, lo, v0..v(w-1)]` per slot, no object per entry, no boxing. The slot is a multiply-shift of the digest, so the table is sized to the row
  count (`rows / 0.85 + 8` slots), not to a power of two, and a build that knows its row count never rehashes. It still grows by half if more keys arrive (a load that inserts more rows than it scanned). Max 85 % full.
- **`index.KeyCodec`**: the VALUES that are keys (the master key an `Xref` row points at, the golden a merged record went into) are kept as a `long`: a canonical decimal of at most 18 digits (no sign, no leading zero) is
  its own value and comes back digit for digit; anything else (composite `a|b`, UUID, `007`, `1.0`) goes into a per-index table of distinct texts and round-trips exactly. 8 bytes for every auto-increment master key.
- **`index.KeyValueIndex`** = `DigestMap(width 1)` + `KeyCodec`, a `Map<String, String>` for: the `Xref` index (`stagingKey -> masterKey`), the merged records (`masterKey -> golden key`), the golden refresh's `RecordMetadata`
  index (`functionalId -> encoded state or golden`, `master.GoldenKeyScanPlan` takes it through a `Lookup`).
- **The master digest index** is a `DigestMap` of width `2 + deferred fields`: the `RowDigest`'s 128 bits then one 64-bit hash per deferred foreign key (`RowDigest.storedWords` / `sameAsWords`, pinned equal to the old
  `sameAs` on 20,000 random row pairs by `RowDigestWordsTest`). **All-zero words mean "the record exists, its digest is not known"** (a value the digest cannot stand for; also a digest that is zero by accident, 2^-128):
  never "unchanged", so the record is read and compared with `StoredValueEquality`, exactly as the old `RowDigest.UNKNOWN` entry did.
- **The staging index** (`StagingLoadReads.Entity`) is a `DigestMap` of width `2 x layouts` (0 when no digest is kept): the digests of every layout side by side, all-zero = unknown (`StagingRowDigest.same` now refuses
  zero stored words). `entry()`, `contains()` and `recordWritten()` keep their signatures, so `StagingLoader` is untouched.
- **Where the TEXT is still needed, and only there**: the master key of an `Xref` row (written into child foreign keys, used for the read of the record to write, the next argument of every other lookup) and the golden of
  a merged record (the next hop of the chain, then a foreign key). Both are decoded from the code on the way out (a short-lived `String`). The staging keys, the merged master keys, the `functionalId`s and the master
  keys of the digest index are NEVER held as text: they are only ever asked about.
- **Counters**: every counter of lots V3-M2 / V3-S2 / V3-M3 is unchanged (`readMode`, `lookupReason`, `xrefIndexRows/Ms`, `masterIndexRows/Ms`, `mergedRead`, `mergedIndexRows/Ms`, `mergedRefreshMs/Rows`,
  `unchangedByIndex`, `masterRecordReads`, `xrefLookups`, `goldenHopsByIndex/Live`, `stagingIndexRows/Ms`, `layouts`, `newByIndex`, `stagingRowReads`, `layoutMismatches`, `writtenKeysRecorded`, the parent / empty-child
  check counters, and the refresh's `scanMs` / `readMode` / `readReason`). **New: `indexBytes`** (estimated heap of the index at its largest) on a master entity, a staging entity in SCAN mode and the refresh's result.

### Exactness: how a digest collision can never cause a wrong write

A digest is 128 bits of SHA-256, so the question is not "can two keys collide" but "what happens if they do". Two defences, the second chosen because it is free:

1. **Build time (detected, certain).** Every scan is of a table whose key is its primary key (`Xref` per project and entity, the master table, the staging table, `RecordMetadata.functionalId`), so the same key never
   comes twice. A digest that is ALREADY in the index when a new key arrives is therefore a collision between two different keys; `DigestMap.addUnique` notices it while probing it anyway (no extra work) and counts it in
   `duplicates()`. **An index with a duplicate is not used**: the entity runs in `LOOKUP` mode (`lookupReason` says "N key(s) of the Xref scan share a 128-bit digest with another one", plus a WARN in `kernel.log`), a
   merged read falls back to the per-row add-on check (`mergedRead: LOOKUP`), the golden-key refresh falls back to the per-record path (`readReason`), a staging table keeps the per-row path of lot V3-S. This is the
   coarse form of "mark the digest ambiguous and read live for it": the unit of fallback is the index, not the key, because the event is astronomically rare and the per-key form would need a live callback in
   every consumer. The result is exact either way.
2. **Query time (not detectable, negligible).** A key that is NOT in the index (a new staging row, a vanished master record) whose digest equals an indexed key's would be answered with that key's entry. The
   probability is at most `n x q / 2^128` for `n` indexed keys and `q` distinct queries: **10^7 x 10^7 / 3.4 x 10^38 = 3 x 10^-25 for one full-bound load** (and SHA-256 gives no way to construct one on purpose: it takes
   about 2^64 hashes). For comparison the master digest of the row VALUES (`RowDigest`, lot V3-M2) already relied on 128 bits for a "wrongly unchanged" answer. The write path was NOT given an extra re-read of the
   real key: the only index whose wrong hit could redirect a write is the `Xref` one, and a re-check would cost one random read per written update row for a 3 x 10^-25 event. If a stricter rule is wanted later, the place
   is `MasterLoader.planRow` right before the record is read for an update (one `Xref` lookup by primary key, compare its `masterKey` with `existingMasterKey`).

A build-time duplicate is reported but not testable live short of forging a SHA-256 collision: it is covered by `DigestMapTest` (`addUnique`), `KeyValueIndexTest` (`add`) and `StagingLoadReadsCompactTest`.

### Measured: memory and time (`tools/index_measure.sh`, no EBX; synthetic keys of Vol1mF's shape; JDK 25.0.2, G1, one JVM per line, heap used after a full GC)

One million rows (`merged` has 333,333 entries, 40 members to a golden; the build includes making the key strings and, for `master`, the SHA-256 `RowDigest` of five fields, which is the same in both columns):

| index | old: heap / bytes a row | new: heap / bytes a row | gain | build old -> new | hit old -> new | miss old -> new |
| --- | --- | --- | --- | --- | --- | --- |
| Xref (staging key -> master key) | 147.7 MB / 155 | 28.5 MB / 30 | 5.2x | 0.28-0.59 s -> 0.47-0.78 s | 59-114 -> 189-314 ns | 20-32 -> 215-355 ns |
| master digests | 125.0 MB / 131 | 36.4 MB / 38 | 3.4x | 0.63-0.97 s -> 0.70-0.98 s | 68-84 -> 193-310 ns | 31-52 -> 279-398 ns |
| merged records (per MERGED record) | 30.0 MB / 88 | 10.3 MB / 31 | 2.9x | 0.05-0.17 s -> 0.16-0.38 s | 23-65 -> 165-240 ns | 16-40 -> 163-222 ns |
| staging digests (one layout) | 132.4 MB / 139 | 36.5 MB / 38 | 3.6x | 0.35-0.59 s -> 0.51-0.75 s | 60-145 -> 214-357 ns | 25-65 -> 288-353 ns |

(the ranges are two or three runs of the harness; a lone JVM on a laptop is noisy.) Ten million rows, 8 GB heap: Xref 1,442 -> 273 MB, master 1,214 -> 360 MB, staging 1,290 -> 361 MB, merged (3.3 M entries) 295 -> 92 MB;
build 3.6 -> 4.2 s, 7.9 -> 5.6 s, 3.9 -> 4.2 s. **One entity of Vol1mF's shape (Xref + master + a third merged) = 303 MB -> 75 MB at 1 M rows, 2,951 MB -> 725 MB at 10 M rows: 4.0x.** Per index, the target (1 M rows <= 60 MB,
10 M rows <= 600 MB) is met by every index (<= 38 MB and <= 361 MB); the three together at 1 M rows each are 75 MB when a third is merged and 96 MB when every record is (more than 60: said rather than rounded).
The 5x of the brief is reached on the `Xref` index only: the master, staging and metadata indexes carry a 128-bit key and a 128-bit value, 32 bytes a row before the load factor.

**What it costs: time per lookup.** A lookup hashes the key (SHA-256, about 0.2 us): 0.2-0.3 us against 0.03-0.1 us for a `HashMap` hit whose string hash is cached. A master row asks about two keys (its staging key, its master key; the
merged check and the digest compare reuse the master key's digest through a one-entry memo) and a child row one more (its parent's), so a load pays about 1 us per row, +1.5 s on the 1.47 M rows of Vol1mF (a 30-40 s
load), and the three builds about +0.3-0.4 s per million rows. At 10 M rows the lookups are still 0.22-0.24 us (a `HashMap` of that size misses cache on the node, the key and its characters; the flat table on one line). A miss
costs more than a hit (probing to an empty slot at 85 % full: up to 0.4 us) and is the path of new rows.

### Defaults raised (`master.ScanBudget`)

`mdm.factory.masterLoad.maxIndexedRows`, `mdm.factory.stagingLoad.maxIndexedRows` and `mdm.factory.goldenRefresh.maxIndexedRows` go from **5,000,000 to 15,000,000** (one constant, `DEFAULT_MAX_INDEXED_ROWS`). The gain
measured is 3.4-5.2x; the bound was raised by 3 only, because the three indexes of an entity can coexist with the parent's `Xref` index (kept for the foreign keys) and with the add-on's own caches. Worst entity at the new
bound (all three master indexes full, every record merged, plus a parent's `Xref` index of the same size): 15 M x (30 + 38 + 31) + 15 M x 30 = about 1.9 GB, which is what 5 M rows weighed before the lot (1.4 GB for the
entity's `Xref` and master indexes + 0.8 GB for the parent's, 2.2 GB) and under a quarter of the 8 GB heap (more than 4x margin). A staging table at the bound: 570 MB. Set the property lower to keep the old reach, higher after reading `indexBytes` on real data.
`docs/OPERATIONS.md` gives the new figures. Nothing else changed in the property semantics (0 or less disables, read at each load).

### Tests

New, pure (no EBX): `KeyDigestTest` (against an independent SHA-256, lone surrogates, the reserved pair, long keys, four threads), `KeyCodecTest`, `DigestMapTest` (put / get / absent, same home slot, probing that wraps the end
of the table, `addUnique` duplicates, growth from nothing, a table sized from the row count that never grows, `ensureCapacity`, `clear`, width 0, 400,000 random operations against a `HashMap`), `KeyValueIndexTest` (values of
every shape, overwrite, the duplicate counter, null keys, lookup by a precomputed digest, 300,000 random operations), `RowDigestWordsTest` (the stored-words round trip against `sameAs`, deferred fields left out, unknown on
either side, zero words, 20,000 random pairs), `MasterLoadReadsCompactTest`, `StagingLoadReadsCompactTest`, `GoldenKeyScanPlanCompactTest` (30,000 random records, the plan over the compact index decides every `Xref` row
like the plan over a `HashMap`). Updated: `MasterLoadReadsGoldenOfTest`, `MasterLoadReadsMergedRefreshTest` (they set the indexes through `applyMerged`), `ScanBudgetTest` (15,000,000). The harness `tools/index_measure.sh` is
not part of `tools/test_local.sh` (it takes minutes and measures, it does not assert).

### What was NOT verified (nothing here ran in EBX)

- The scans themselves (`scanXref`, `scanMaster`, `readMergedIndex`, `readRecordMetadataIndex`, `StagingLoadReads.Entity.scan`) compile and keep the shape of the old ones, but need a live instance: no load, no refresh was run.
- Real heap and time on Vol1mF: the numbers above are synthetic keys of the right shape. Real master keys may be non-numeric (they then cost a string each in the codec table - an entity with UUID or composite master keys
  gets the Xref gain of about 2x, not 5x; `indexBytes` counts the table).
- That SCAN still classifies exactly like LOOKUP (the whole point of lots V3-M2 / V3-S2 / V3-M3): the logic is untouched, but it is the same question asked to a different structure.
- The behaviour at the new bound (15 M rows): the maps were built to 10 M rows in the harness, not inside the server.
- GC behaviour: one 100-300 MB `long[]` is a humongous allocation for G1 (it needs contiguous regions); the old maps were many small objects. Not seen to matter in the harness (-Xmx4g to 8g), not tried in a server with 8 GB and
  the add-on's caches.

### What the coordinator should check live (deploy, then on Vol1mF)

1. **Identical counters.** An unchanged master reload of Vol1mF must give, per entity, the same `read`, `written`, `unchanged`, `unchangedByIndex`, `skippedMerged`, `referencesToGolden`, `goldenHopsByIndex`, `goldenHopsLive` (0),
   `masterRecordReads` (0), `xrefLookups` (0), `mergedRead: SCAN` as the run of lot V3-M3 (Adresse `referencesToGolden: 637795`, `unchangedByIndex` = `unchanged`). Then the same with `-Dmdm.factory.masterLoad.maxIndexedRows=0`
   (LOOKUP): identical counts except `readMode`.
2. **A staging scope `all` reload** (unchanged data): `readMode: SCAN`, `unchangedByIndex` = `read`, `stagingRowReads` 0, `layoutMismatches` 0, as in lot V3-S2; then a small change (a few edited landing rows, one new): changed rows written
   in both modes.
3. **A matching run's golden-key refresh**: `readMode: SCAN`, `set`/`cleared`/`unchanged` as lot V3-M3 (second run 0 / 0 / all), plus `indexBytes`.
4. **Heap peak during the master reload**: read `indexBytes` per entity (expected about 75 MB for Personne and about 80 MB for Adresse's three indexes + Personne's Xref kept, instead of the 330 MB peak), and watch the JVM heap (`jstat -gc`
   or the console) while Adresse loads; the peak should drop by about 250 MB.
5. **Time**: `xrefIndexMs` + `masterIndexMs` + `mergedIndexMs` of lot V3-M3 (about 15 s on Vol1mF) should not grow by more than about 1 s; the whole unchanged reload (29.7 s) by about 1-2 s (hashing). If it grows more, the hash is the
   suspect: `KeyDigest.of` is the single place to speed up (SipHash-2-4/128 with a random per-JVM key would be 3-4x faster and still collision-hard against an adversary; not done, no reference vectors to test it against here).
6. **No `index key(s) share a 128-bit digest` WARN** in `kernel.log` (if one ever appears, it is a finding worth reporting, and the entity has run in LOOKUP mode).
## Lot M-INC - incremental master load (branch `perf/master-incremental`, 2026-10-12)

Measured problem (Vol1mF, 2026-10-04, after lots V3-M..V3-M3): a master load always re-reads the WHOLE staging zone, even when one row changed - 29-42 s for 1,465,857 staging rows with nothing to write. The cost is linear in the table
(three streaming reads plus the digest of every planned row), so it is minutes at 10 M rows. Goal: a load scope that reads only the staging rows changed since the last successful master load of the entity. **Nothing in this lot ran
live; the generated scripts were not validated by EBX; nothing is deployed.**

### What was built

1. **A journal of changed staging rows, written by EBX itself.** The Factory generates, on the staging table of every promoted entity, two EBX Script table triggers - `MasterJournalAfterCreate` and `MasterJournalAfterModify`
   (`master.MasterJournalTriggerBody`, installed through `data-model` `set_script_trigger` + `scripting` `update_script` + `publish_data_model`, the machinery of `ebx.ScriptTriggerOps`/`IntakeTriggerInstaller`). Because the triggers
   belong to the staging MODEL, every write path is covered: the staging loader (any scope), the derivation triggers and the `_recompute_` backfill (they save records, so `afterModify` fires), a steward editing a staging record
   in the UI, a Data Services write. **No `afterDelete`**: the master load never deletes (MASTER-ZONE.md 3.2), a staging row that disappears needs no master action, and a journal key whose row is gone is skipped and counted
   (`stagingKeysMissing`). No code anywhere in the module deletes staging rows outside a reset/delete of the whole zone, which takes the journal with it (it is a dataset of the staging dataspace).
2. **Where the journal lives: `MasterJournal.xsd`, a second ROOT dataset (`MdmFactoryMasterJournal`) of the project's STAGING dataspace** (`ebx.MasterJournal.ensureDataset`, `doCreateRoot`, exactly the `IntakeSignal` pattern).
   Why not the `MdmFactory` dataset: a table trigger cannot open `execute transaction`; it writes through its own ambient `_ebx.transaction`, scoped to its own dataspace (PipelineStep 395; the reason `IntakeSignal` and
   `DistributionOutbox` exist), so the Factory dataset - another dataspace - is out of reach. Why not a technical table inside the generated staging model (the brief's second choice): (a) the only trigger write
   mechanism measured live is `db.findDataset(_ebx.dataspace, name)` + `db.findTable<m.T>` + `db.recordOf(_ebx.transaction, ...)` against a PACKAGED model referenced with `references model ... from module`; a table of the trigger's own
   model has no measured syntax; (b) the staging model is generated, diffed (`StagingModelDiff`) and patched on every regeneration, so a technical table would need the generator, the diff and a patch of every existing project;
   a packaged model needs none of that. The price is a new packaged XSD (module deployment, i.e. the restart any new Java class needs anyway). **`MdmFactory.xsd`/`tools/build_xsd.py` are NOT changed.**
   Two tables: `Control` (PK project + entity: `armed`, `armedAt`, `recordingSince`, `planFingerprint`) and `Change` (PK project + entity + sourceSystem + sourceId; `changedAt`, `changes`). The brief's key "project + entity +
   stagingKey" is stored as the staging record's two key fields: the staging key is rebuilt in Java with `computePrimaryKey(...).format()` (CLAUDE.md sec 9: never a hand-built composite key). A record changed twice is
   journaled once (the same row, `changes + 1`).
3. **The trigger returns at once for an entity that is not recording**: first a `Control` lookup by primary key. Cost of a recording entity: that lookup, one `Change` lookup, one create or update - measured elsewhere at 0.05-0.08 ms
   per written row for the derivation triggers; the journal write is a real record write, so expect more (to measure: section "To check live"). While an entity has no `Control` row (before the first master load) the staging
   loads pay one failed lookup per row.
4. **Scope.** `POST /projects/{code}/master/load?scope=changed|full` (`sync=true` or the queued `MASTER_BUILD_RUN` job; the job parameter `scope` is left out when blank, so existing queued jobs still merge), `MasterLoader.load(..., scope)`,
   the `scope` property of `workflow.MasterLoadTaskBean` (`MdmFactoryPipeline` binds it to a new variable `masterScope`, default `changed` - the model must be republished; an older published model leaves it null). A blank scope takes
   `-Dmdm.factory.masterLoad.defaultScope` (default `full`: the REST route, the wizard buttons and the stand-alone bean behave as before). `master.MasterLoadScope.parse`: only `changed` asks for the journal, a typo is `full`.
5. **`changed` is a request.** `MasterLoadScope.decide` answers "changed" for an entity only when every condition that makes the journal a complete account holds, otherwise the entity is loaded in full and the step says why
   (`scopeReason`, and `Scope full (changed was asked: ...)` at the head of the message): the journal dataset exists and the triggers are installed and authored on the table (verified by ONE `list_script_table_triggers` per load); they
   were not installed by this very load; the entity is armed (a full load finished since recording started, and nothing disarmed it since - this is also "the last master load of the entity did not fail"); the plan fingerprint
   is the armed one; the master table is not empty; the entity has no deferred (self-reference) foreign key (its pass 2 reads the whole staging table anyway); the journal does not hold more than `MAX_JOURNAL_ROWS` (2,000,000) rows.
   A problem inside the journal code (MCP unreachable, a write failing, the model not packaged yet) never fails the load: the journal is "unusable: <reason>" and every entity loads in full.
6. **The first load installs the triggers.** Any scope: `ebx.MasterJournal.prepare` creates the dataset if missing and declares/authors/publishes the triggers on every promoted staging table if one is missing (a staging model that
   was regenerated loses them: the next load installs them again). That load is full by construction (`installedNow`), starts recording each entity (`Control` row created with `armed=false`, BEFORE any row is read) and arms it
   when the whole load ended normally. Publishing the staging model takes time (like any model publish) and must not overlap a staging load.
7. **A changed entity** reads its journal rows plus the staging keys of its open MASTER rejects (`TransferReject` stage MASTER, not resolved: a row refused earlier - "unknown parent" - can be accepted now though its staging row
   did not change). **Read mode** (`master.MasterLoadScope.readMode`): at most `mdm.factory.masterLoad.journalLookupMax` keys (default 20,000) are read by primary-key lookup (`readMode` `KEYS`; the entity runs in LOOKUP mode, no index is
   built: the per-row path of lot V3-M, whose cost follows the number of rows); more is one streaming read of the staging table filtered on the key set (`FILTERED_SCAN`; the three indexes of lot V3-M2 are built as in a full
   load). An entity with an empty journal and no open reject reads nothing and builds nothing ("nothing changed (empty journal)"); if that holds for every entity the whole load ends at once with "Nothing changed: scope changed, the
   journal holds no row and no staging row was read." (the distribution and matching pauses of the surrounding load still run; they are cheap and unchanged).
8. **Consumption, never losing a change.** At the very end of a load that ended normally (not stopped, not failed; after pass 2), a changed entity deletes the journal rows it read, each ONLY if its `changes` counter still has
   the value read (`JournalConsumption.shouldDelete`): a staging record written again while the load ran has a higher counter, its row stays and the next load reads it. A failed or stopped load consumes nothing (the next one
   re-reads the same rows: idempotent). A FULL entity snapshot-reads its journal rows before its scan (everything visible then was committed before the scan, so the scan saw it) and consumes that snapshot the same way after
   arming (skipped above `MAX_SNAPSHOT_ROWS`, 1,000,000: an optimisation only). A failure of any journal bookkeeping after a finished load is logged and leaves the entity as it is.
9. **Parent/child entities.** A changed child row needs its parent's master key and the parent may be unchanged. The Xref index of lot V3-M2 is built per entity for a scan. Here: parents and children loaded `KEYS` use NO index -
   `ParentKeys` falls back to a primary-key lookup of the parent's `Xref` row (already the path when an entity has no index), cached per distinct parent for the load, and to the live golden-chain check of the add-on when the parent's
   entity is matched (the merged keys are not pre-read for a `KEYS` entity). A changed child whose parent entity was loaded in full (or `FILTERED_SCAN`) still resolves through that parent's in-memory `Xref` index. So the cost of
   a small incremental load is the number of changed rows times (one `Xref` lookup, one master record read, one add-on read per distinct merged parent), never a table scan. A FULL child entity next to a `KEYS` parent loses the
   scan saving of lot V3-M3 (golden hops live); that mix only happens when the child falls back to full for one of the reasons above.
10. **Plan fingerprint.** `master.PlanFingerprint` is a SHA-256 of what decides what a load writes for a staging row that did NOT change: the entity's plan fields (master field <- staging field, type, foreign key, target, deferred, the
    staging `<attribute>Ref` field), the master fields that are writable on the published master table, and the script rules (id, transfer policy, path). It is stored in `Control.planFingerprint` when the entity is armed; a
    difference (a field promoted since, a REJECT rule added, a master field that appeared after `master/generate`) forces a full load, so unchanged staging rows follow the new plan.
11. **Reporting.** The `MASTER_LOAD` step message starts with `Scope changed (N journal row(s), read KEYS). ` when the scope is not full; the step detail has `requestedScope`, `scope` (`full`/`changed`/`mixed`), `journalRows`,
    `readMode` (`SCAN_ALL`/`KEYS`/`FILTERED_SCAN`/`MIXED`), `nothingChanged`, the `journal` node (usable, reason, installedNow, `triggersMissing`, the install calls) and per entity `scope`, `scopeReason`, `stagingRead`, `journalRows`,
    `rejectsReread`, `stagingKeysMissing`, `journalConsumed`, `journalArmed` next to the existing `readMode` (the lot V3-M2 index mode, `LOOKUP` for a `KEYS` entity). The REST answer carries the same top-level fields; the queued job's
    result reads `(..., N held back; scope changed, N journal row(s))` or "Nothing changed (scope changed, empty journal, no staging row read)."

### What `changed` does NOT do (choose `full` for these)

- A value edited directly on a master record, or drift between staging and master from any other cause, is not corrected for a staging row that did not change (a full load overwrites it, a changed one does not touch it).
- A master record merged since the last load, or unmerged (a golden redirect of a child's foreign key to a merged parent, a member back to GOLDEN): a child row whose staging row did not change is not re-pointed by a changed load
  (the add-on's own alignment, or a full load, does it).
- The counters `lastLoadChecked`/`lastLoadFailing` of the script rules count the rows read, i.e. the delta (as the staging load's scope `new` does).
- A row whose master insert/update is rejected at write time (not by a REJECT script rule) is not retried by a changed load unless its staging row changes or it is in `TransferReject`.
- Two successive changed loads of a project that has an entity with deferred (self-reference) foreign keys always load that entity in full.

### Properties (JVM options, read at each load)

| Property | Default | Meaning |
| --- | --- | --- |
| `mdm.factory.masterLoad.defaultScope` | `full` | The scope used when a caller names none: `full` or `changed`. Flip to `changed` once the live checks below passed. |
| `mdm.factory.masterLoad.journalLookupMax` | `20000` | Journal keys read by primary-key lookup; above it, one filtered streaming scan of the staging table. Zero or less: always scan. |
| `mdm.factory.masterLoad.journalEnabled` | `true` | `false` is the kill switch: no trigger installed, every load full, and the `Control` rows of the project are deleted so the generated triggers return at their first lookup (nothing is republished). |

### Risks

- **A generated trigger that fails aborts the staging write it fires on.** The bodies are NOT validated against an instance (the shape is `IntakeTriggerBody`'s, whose parts were validated one by one; `recordOf`/`saveRecord` through
  `_ebx.transaction` was proven by the distribution outbox). `update_script`'s validation is read at install and a refusal fails the install (journal unusable, loads stay full). A run-time error would hit every staging write of a
  recording entity: first thing to check live is one UI edit and one `staging/load`. Remedy without a republish: `-Dmdm.factory.masterLoad.journalEnabled=false` and a master load (deletes the `Control` rows).
- Journal field lengths (project 120, entity 200, sourceSystem and sourceId 1000) are above the staging key fields' usual size; a longer key makes the journal write, hence the staging write, fail.
- Staging model publication at install time: do not run it during a staging load.

### Tests

`MasterLoadScopeTest` (scope parsing, the whole decision table one failing condition at a time and the order they are reported in, the lookup-vs-scan threshold, property parsing), `JournalConsumptionTest` (what is read:
journal then open rejects, no duplicate; what is consumed: only after a normal end, only journal keys; the counter rule; batches), `PlanFingerprintTest`, `MasterJournalTriggerBodyTest` (both bodies: entry points, the early
returns, the Control lookup before any Change access, no hand-built key, escaping, no `try`/literal index/`execute transaction`). `MasterLoader`, `MasterJournal` and the trigger run time need a live instance.

### To check live (nothing was run)

1. **Install on an existing project**: first `master/load` (any scope) on Vol100k or Personnes: the step detail's `journal` node shows `installedNow: true`, `usable: true`; `data-model` `list_script_table_triggers` lists
   `MasterJournalAfterCreate/AfterModify` authored on every promoted staging table; the staging dataspace holds the dataset `MdmFactoryMasterJournal`; the load is full (`scope full`, reason "installed by this load") and every entity ends
   `journalArmed: true`. Read `kernel.log` for the publish and any script error.
2. **One row changed, one row read**: edit one staging record (Data Services PUT), `master/load?scope=changed`: `scope changed`, `journalRows 1`, `readMode KEYS`, `read 1` (plus the open rejects), `written` 1 or `unchanged` 1, a few seconds in all;
   the `Change` table is empty afterwards (`journalConsumed 1`).
3. **Nothing changed**: a second `scope=changed` load answers "Nothing changed ..." in about a second, per-entity `read 0`.
4. **A steward edits a staging record in the UI**: the `Change` row appears (and `changes` increments on a second save).
5. **The `_recompute_` backfill is journaled**: run the derivation backfill on an entity; the rows it rewrites are in `Change`; the next changed load reads exactly those.
6. **Equivalence with full**: after a few edits, `scope=changed` then `scope=full` on a copy: the second must report every row `unchanged`; counts per entity otherwise identical.
7. **Fallbacks**: delete a `Control` row or republish the staging model (triggers lost) or change a promoted attribute: the next `changed` load says why it is full. A parent/child pair (Personne/Adresse): change an address only, then a person only.
8. **Cost of the triggers**: a `staging/load` of 100k rows with and without an armed journal (`journalEnabled=false` + one master load stops it); and the staging write when the journal dataset is absent.
9. **Scale**: Vol1mF with one changed row: seconds instead of 29-42 s; a journal of 50k rows takes `FILTERED_SCAN`.
10. **Republish `MdmFactoryPipeline`** (variable `masterScope`, bean parameter `scope`) before expecting the intake chain to load `changed`.

## Operator JVM properties (audit wave 2, 2026-10-05)

Read at the moment they are used; set them in the Tomcat JVM options (`-Dname=value`). Nothing below needs a model change.

| Property | Default | Meaning |
| --- | --- | --- |
| `ebx.factory.landingLoadMaxMinutes` | `720` (12 h) | The longest one table's landing load may wait for its Connectors run (`LandingFlows.awaitLoad`). Past it the run is stopped (`connectors_run_stop`), the table fails with the run id, the wait and the rows reached, and the step ends FAILED. Zero, negative or unreadable reads as the default, never as "no limit". |
| `ebx.factory.distribution.maxRows` | `100000` | The records one distribution send asks Connectors for, per mapping and call (audit C-35, wave 10; before, no `max` was passed and Connectors took its default of 200, so a larger table was only partly sent, in silence). When Connectors still answers `truncated`, a `changed` send asks again until its backlog drains (at most 1000 rounds); an `all` send ends FAILED with a sentence naming this property. Zero, negative or unreadable reads as the default, never as "no limit". Catalog scans of a source ask for 10000 tables and say so when cut. |
| `ebx.factory.distribution.perRowMs` | `20` | Wave 13: milliseconds added, per record of `maxRows`, to the 300 s read timeout of a distribution send (`DistributionBatches.sendTimeoutMs`). Since the asynchronous send (wave 13, `docs/DISTRIBUTION.md` "Following and stopping a send") this only matters when Connectors answers a send synchronously: an older Connectors that ignores `async`, or one that refuses it for a scope send (the Connectors of 2026-10-12 does) - a send Connectors runs asynchronously is followed with the ordinary client and waits on no such timeout. A `connectors_sync` call sends up to `maxRows` records and lasts in proportion (150-250 rows/s measured on Vol100k, 2026-10-12: 88,991 addresses took more than 300 s, the fixed read timeout of the other Connectors calls, so the Factory gave up while Connectors went on sending). With the defaults a send waits 300 s + 100,000 x 20 ms = 2,300 s. Zero, negative or unreadable reads as the default. |
| `ebx.factory.distribution.sendTimeoutMaxMs` | `7200000` (2 h) | Wave 13: the ceiling of that read timeout (same: only for a synchronous send), whatever `maxRows` and `perRowMs` give; never below the 300 s base. Zero, negative or unreadable reads as the default. |
| `ebx.factory.maxHeavyJobs` | `0` (unlimited) | The most heavy jobs running at the same moment on the instance: matching runs, staging loads, master builds, landing loads, first full distribution (`FactoryJobKinds`). A job over the limit stays QUEUED and is retried on the next dispatcher tick (2 s). Applies on top of the pool size (`jobsMaxParallel`) and the per-project resource claim. |
| `mdm.factory.landing.parallelSources` | `2` | Lot L2 (2026-10-12): the sources of one project whose landing data is copied at the same time when a chain loads them all (wizard run, workflow step). `1` = one after the other. Clamped 1..4. See "Lot L2" above and `docs/OPERATIONS.md` 3.1. |

A QUEUED job is claimed and shown RUNNING only when a pool thread is free; its time limit (`jobMaxMinutes`, an inactivity window) starts when its body starts.
