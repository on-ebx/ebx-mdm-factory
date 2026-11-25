# Volume-scalability lot 4 - screen and matching reads must not load whole tables

Branch `feature/volume-screen-reads`, worktree `~/Dev/wt/ebx-mdm-factory-volume-4`, 2026-09-28. Part of a
volume-scalability plan alongside a sibling lot (`fix/volume-guards`, `MatchingConfigurator.run`/`LandingFlow`/
`LandingFlows`/`StagingLoadRun`/`KeyCertainty`/`LandingRun`/`FactoryJobs`/`BatchRetry`/`ColumnProfileSql`/
`LandingColumnProfiler` - not touched here). This lot's own scope: eight audit findings (2026-09-28 code audit)
where a screen or a matching read scanned or materialized a whole table instead of a bounded read/aggregate.

Every SQL shape below was validated live against `Companies_Master`/`Master`/`Company` (105 golden, 4 merged
groups, ERP/CRM/MDM sources) and `MdmFactory`/`TransferReject`/`Companies_Staging` via the read-only `data-access`
MCP server's `execute_query`/`count_records_by_xpath` tools, unless noted "not verified live". The whole module
compiles against the real `ebx-lib.jar`/`ebx-addons.jar` and every `*Test.java` (JUnit-free, `tools/test_local.sh`)
passes after every change in this lot.

## 1. `ebx.MatchingResultsBuilder.build`

Rewrote the KPI/band/pending-pairs/merged-groups sections to use bounded SQL aggregates and capped candidate
reads on the master dataset, instead of `MatchStats.MetaRow` list materializing every `RecordMetadata` row.

- KPIs: `SELECT c."Linked_Field_State" AS ST, c."sourceSystem" AS SYS, COUNT(*) AS N FROM "<Entity>" c WHERE
  c."Linked_Field_State" <> 4 GROUP BY c."Linked_Field_State", c."sourceSystem"` (one row per (state, source)
  combination, never per record) feeds the new pure `MatchStats.fromStateSourceCounts`. Merged-group count:
  `SELECT COUNT(DISTINCT c."Linked_Field_GoldenId"."id") AS N FROM "<Entity>" c WHERE c."Linked_Field_State" = 2`.
  Pending-suspect total: `SELECT SUM(r."suspectedCount") AS N FROM "RecordMetadata" r WHERE r."state" = 1 AND
  r."hasSuspects" = true` on the metadataset dataset (`RecordMetadata.suspectedCount` is the add-on's own
  maintained per-record suspect count - not independently re-verified against a live `MameGroups` count beyond
  one live sample, see "Not verified" below). False-positive count: `SELECT COUNT(*) AS N FROM
  "FalsePositiveDecision" f` on the metadataset. All measured live.
- Band ("where golden records come from"): a two-level aggregate, entirely server-side -
  ```sql
  WITH G AS (
    SELECT CASE WHEN c."Linked_Field_State" = 1 THEN c."id" ELSE c."Linked_Field_GoldenId"."id" END AS GKEY,
           COUNT(DISTINCT c."sourceSystem") AS NSRC, MIN(c."sourceSystem") AS SOLE_SRC
    FROM "<Entity>" c WHERE c."Linked_Field_State" IN (1, 2)
    GROUP BY CASE WHEN c."Linked_Field_State" = 1 THEN c."id" ELSE c."Linked_Field_GoldenId"."id" END
  )
  SELECT CASE WHEN G.NSRC > 1 THEN 'MULTIPLE' ELSE G.SOLE_SRC END AS LABEL, COUNT(*) AS N
  FROM G GROUP BY CASE WHEN G.NSRC > 1 THEN 'MULTIPLE' ELSE G.SOLE_SRC END
  ```
  Measured live: `{ERP: 53, MULTIPLE: 2, CRM: 50}` (sums to 105 = `goldenRecords`). `MatchStats.bandFromLabelCounts`
  (new, refactored out of `computeBand`'s own sort/assemble tail) turns this into the same `BandSegment` list.
- Pending pairs: `SELECT c."$pk" AS FID, c."Linked_Field_GroupId" AS GRP FROM "<Entity>" c WHERE
  c."Linked_Field_State" = 1 AND c."Linked_Field_HasSuspects" = true ORDER BY c."id" FETCH FIRST 60 ROWS ONLY`
  fetches at most 60 CANDIDATE goldens; the add-on's own `MameGroups` read then runs at most 60 times (was: once
  per golden-with-suspects row in the WHOLE table, just to size the total, which is now the SQL `SUM` above).
- Merged tab: `SELECT DISTINCT c."Linked_Field_GoldenId"."id" AS GID FROM "<Entity>" c WHERE
  c."Linked_Field_State" = 2 ORDER BY c."Linked_Field_GoldenId"."id" FETCH FIRST 60 ROWS ONLY` for the golden ids,
  then `SELECT c."$pk" AS FID, c."sourceSystem" AS SYS, c."Linked_Field_GoldenId"."id" AS GID FROM "<Entity>" c
  WHERE c."Linked_Field_State" = 2 AND c."Linked_Field_GoldenId"."id" IN (<up to 60 ids>)` for their members - one
  query for the ids, one for every member, never a scan of every MERGED row.
- Per-record source attribution now reads the master row's own `sourceSystem` field directly (already read into
  the `Adaptation` the code already has in hand) instead of a whole-project `Xref` scan kept in a Java map
  (`readSourceSystems`, removed). **Documented behavior difference**: an auto-created golden with no `Xref` row of
  its own used to show as `UNKNOWN` in the KPI/band; it now shows whatever source the add-on itself stamped on the
  golden's own row (measured live: `MDM` on `Companies_Master`, one row) - arguably more correct (CLAUDE.md sec 8,
  "the source stamped on an auto-created golden has its own native field"), but a real, deliberate change from the
  Xref-derived number. `recordsBySource`'s map iteration order can also differ (SQL `GROUP BY` order vs. Xref
  first-seen order) - cosmetic only (a KPI card list), not asserted anywhere.
- `MatchingResultsBuilder.scanMetadata`/`readSourceSystems`/`countFalsePositives`(old body)/`addSource` removed
  (fully unused after the rewrite). `MatchStats.computeFromRows`/`computeBand`/`MetaRow`/`withSuspectCounts` kept
  untouched (still unit-tested pure utilities; no other production caller left after this lot, kept for
  low-risk/no-scope-creep reasons rather than removed).
- **Wizard-render laziness** (`ebx.ui.ProjectWizardUserService#model`): the per-ACCEPTED/APPLIED-plan
  `MatchingResultsBuilder.build` loop now only runs when `model.currentStep == WizardRules.STEP_MATCHING` OR on
  the very first render (`!this.postedOnce`) - the exact same gate `buildSummary`'s own comment already
  established for step 7's similarly-expensive block (wizard.js switches steps client-side with no round trip, so
  the first render must still populate every step; only a later postback from an unrelated step now skips it).

## 2-4. `ebx.MatchingConfigurator` (only these three methods changed, per task brief)

Investigated first: `Query.setSession(Session)` is required for the SQL path, but none of these three methods
(nor their callers, `run`/`parentPendingSuspectNote`) had a `Session` to spare without editing `run`'s body or
`parentPendingSuspectNote`'s signature - both explicitly out of this lot's allowed footprint (the sibling
`fix/volume-guards` lot owns `MatchingConfigurator.run`). Fixed all three WITHOUT SQL/Session instead, using two
public EBX Java APIs `javap`-confirmed on `ebx-lib.jar` and not previously used in this codebase:
`RequestResult.getSize()` (a real count primitive - the same one the `data-access` MCP server's own
`count_records_by_xpath` tool uses) and `AdaptationTable.createRequestResult(String, RequestSortCriteria)`
(server-side sort). Both keep the touched methods' EXACT signatures, so `run`'s two call sites did not need to
change at all.

- **`countPendingSuspectGoldens`**: was `MatchingResultsBuilder.scanMetadata(table)` (whole `RecordMetadata` table
  into a `List<MatchStats.MetaRow>`) then a Java loop counting `state==GOLDEN && hasSuspects`. Now
  `table.createRequestResult("./state=1 and ./hasSuspects=true").getSize()`. Measured live via
  `count_records_by_xpath` against `Companies_Master`/`Master_Company_MDS`/`RecordMetadata`: 69, matching an
  independent SQL `COUNT(*)` on the same predicate exactly.
- **`mostRecentLoadId`**: was a full scan of the master table computing a running string max of `_loadId` by
  hand. Now `new RequestSortCriteria().add(Path.parse("./_loadId"), false)` (descending) +
  `table.createRequestResult(null, sort)`, reading at most `MOST_RECENT_LOAD_ID_SCAN_CAP` (20) leading rows
  looking for the first non-blank value (defensive against an unusual null-sort-order; a real master row always
  carries a non-blank `_loadId` per `ebx.MasterLoader`, so this cap is never expected to bind). **Not verified
  live**: this session's reachable instance had no project with more than one distinct `_loadId` on its master
  table to meaningfully check descending sort order against a real second value. Compiled against the real
  `ebx-lib.jar` (`RequestSortCriteria`'s public constructor/`add(Path, boolean)` confirmed by `javap`).
- **`masterKeysOf`**: `MatchingRunScope.all`'s own Javadoc/parameter doc states the returned key list is "only
  ever consulted for 'is there anything to scope at all'" when `pWholeTableBelongsToProject` is true - the ONLY
  value this codebase's one caller (`run`) ever passes. The old body built the FULL deduplicated key list (a
  whole-project `Xref` scan with an O(n^2) `ArrayList.contains` dedup) only to check `isEmpty()` on it. Now reads
  at most ONE matching `Xref` row (same "read one, stop" idiom as `findProject`) and returns a 0/1-element list -
  functionally identical for the one real caller, at O(1) EBX reads instead of O(project's `Xref` row count).

## 5. `ebx.MatchPlanSimulationRunner`

- `sharedKeyValuesSql`/`nearMissGroupsSql` now end with `FETCH FIRST <cap> ROWS ONLY` (100 = `MAX_PROBE_PAIRS *
  5` each), confirmed supported (`get_query_syntax`) and validated live: both statements (with the literal `100`)
  ran against `Companies_Master`/`Master`/`Company`. `MatchPlanSimulationRunnerSqlTest`'s pinned-exact-text
  assertions updated to match; two new tests added for `sqlStringLiteral`/`comboKey`.
- `probeSharedKeyPairs`: the old body ran one `rowsForFieldValue` XPath scan PER candidate key value (up to
  `MAX_CANDIDATE_KEY_VALUES` = 100). New `rowsByFieldValue` runs ONE `SELECT c."$pk" AS PK, c."<field>" AS V FROM
  "<Entity>" c WHERE c."<field>" IN (v1, ..., v100)` and buckets rows by value in Java. Validated live (`IN` over
  two real shared `name` values on `Company`, correct `$pk`s back). Returns `null` (not merely empty) on ANY
  failure - the caller then falls back to the ORIGINAL per-value read, since a matching KEY field is not
  guaranteed to be a string column (the pre-existing XPath path already quoted every value string-style, which
  the core repository's XPath engine tolerates; this codebase makes no claim the SQL engine does the same
  coercion for a non-string column, and none was available to test against live).
- `probeNearMissPairs`: same fix, `rowsByFieldCombo` - ONE `WHERE (f0=a0 AND f1=b0) OR (f0=a1 AND f1=b1) OR ...`
  query for every candidate group at once (validated live with two real `city` groups), same null-on-failure /
  per-group fallback discipline.
- `MAX_ROWS_SCANNED_PER_GROUP` (200) still caps each bucket in the new bulk reads, same bound the old per-value/
  per-group `rowsForPredicate` already applied.

## 6. `ebx.CrossSystemMeasures` + `ebx.MatchPlanLlmSuggester`

`perSystemValidityCounts` and `perSystemValueShapes` sent the IDENTICAL `GROUP BY sourceSystem, field` SQL
statement independently - up to 30 attributes, doubled for any STRING attribute that also carried a validity
rule. Factored the raw-row fetch into `CrossSystemMeasures.perSystemDistinctValues` (same SQL, same cap/failure
discipline) and the shape aggregation into a new pure `shapesFromDistinctValues`; both `perSystemValidityCounts`/
`perSystemValueShapes` now call these (unchanged public behavior/signature for any other caller). At the ONE real
call site (`MatchPlanLlmSuggester`'s per-attribute survivorship loop), `needsValidity`/`needsShapes` are computed
first, the raw rows fetched ONCE when either is true, and both derivations (`ValidityAggregator.aggregate`,
`CrossSystemMeasures.shapesFromDistinctValues`) run off that SAME list - never a second query for one field
within one suggestion run. Not independently re-verified live this session (no write access to set up a live
survivorship run this lot) - the underlying SQL shape (`perSystemValidityCountsSql`) is unchanged from what the
coordinator already verified live on 2026-09-23 per that method's own pre-existing Javadoc.

## 7. `ebx.GoldenRecordsReader` + `ebx.ui.WizardSummaryReader`

- `GoldenRecordsReader.readEntity` (renamed `scanEntity`, kept as a fallback) iterated every master row of every
  promoted entity in Java. New `queryEntityCounts` runs ONE `SELECT COUNT(*) AS TOTAL, COUNT(CASE WHEN
  "Linked_Field_State"=1 THEN 1 END) AS GOLDEN, COUNT(CASE WHEN "Linked_Field_State"=1 AND
  "Linked_Field_HasSuspects"=true THEN 1 END) AS PENDING, COUNT(CASE WHEN "Linked_Field_State"=1 THEN
  "<field>" END) AS F<i>, ... FROM "<Entity>" c WHERE "Linked_Field_State" <> 4` per entity (or, for a table with
  no `Linked_Field_State` at all - matching never activated - a simpler `COUNT(*)`/`COUNT(field)` query with no
  `WHERE`). `COUNT(field)` (not a `TRIM`-based blank check) is used for fill rate: type-agnostic for any column
  type, and EBX itself stores a blank string as `NULL` (CLAUDE.md sec 9), so it answers the same "has a real
  value" question the old per-row check did without risking a `TRIM` on a non-string column. Needs a `Session`
  (added as a new first parameter; the old signature is kept, `@Deprecated`, calling the new one with `null` and
  falling back to `scanEntity`) - both in-tree callers (`ebx.DataProductPublication`, updated) already have one
  in scope, and neither is on the restricted-file list. Not independently verified live this session (the exact
  `COUNT(CASE WHEN ...)` shape was built by direct analogy to the other, live-validated `CASE WHEN` band query in
  item 1 above, but not itself re-run against a live table with a real fill-rate gap this session).
- `WizardSummaryReader.unresolvedRejectCount`: was a full `TransferReject` scan counting `!resolved` in Java. Now
  `table.createRequestResult("./project='X' and not(./resolved=true())").getSize()` - one bounded XPath count,
  the `not(./resolved=true())` fragment mirroring the old Java check exactly (a null/unset `resolved` still counts
  as unresolved). Measured live via `count_records_by_xpath`: 541 project-wide, 196 for project `Companies`.

## 8. `ebx.ui.FactoryCockpitUserService` + `ebx.ui.CockpitData` + `ebx.StagingLoadGridReader`

- `FactoryCockpitUserService.readRejects`: was an unbounded `TransferReject` scan with one `this.lookup` (a
  `STAGING_ENTITY` PK lookup) PER REJECT ROW. Now reads at most `REJECTS_READ_CAP` (200) rows, most-recent-first
  (`RequestSortCriteria` on `./id` descending - `TransferReject.id` is a plain auto-increment, so this is the
  same order sorting by `rejectedAt` would give without fetching everything first to sort it), and resolves an
  entity's name ONCE per DISTINCT entity id (a small `HashMap` cache) instead of once per row. The accurate
  unresolved TOTAL for the KPI tile (`CockpitData.Snapshot#rejects`) no longer comes from counting this now-
  capped sample - `FactoryCockpitUserService.write` calls `WizardSummaryReader.unresolvedRejectCount` (item 7)
  separately and feeds it through a new `CockpitData.Inputs#unresolvedRejectsTotal` field; `computeRejects` prefers
  it over counting `Inputs#rejects` when set (`null` - the default - keeps every existing `CockpitDataTest`
  scenario, which lists a project's WHOLE reject set in `Inputs#rejects`, working unchanged). **Known
  simplification**: `Snapshot#rejectSummaries` (the per-entity/stage breakdown shown lower on the Cockpit) is
  still derived from the capped 200-row sample, so on a project with more than 200 total rejects it can under-
  count a summary line - a smaller, documented trade-off against the much larger win of never materializing an
  unbounded reject history. `latestRejects` (shown, up to 10) is unaffected either way.
- `StagingLoadGridReader.build`: the "Fill the shared structure" grid used to run up to TWO `RequestResult.
  getSize()` predicate-count calls PER CELL (rows for that application, held-back rejects for that application),
  plus one more per entity for the "In staging" total - up to roughly `2 x entities x applications + entities`
  separate EBX round trips for one grid render. Now: ONE query for the WHOLE grid's held-back counts -
  `SELECT t."entity"."id" AS ENT, t."sourceSystem" AS SYS, COUNT(*) AS N FROM "TransferReject" t WHERE
  t."project"."code" = '<project>' AND t."stage" = 'STAGING' AND NOT (t."resolved" = true) GROUP BY
  t."entity"."id", t."sourceSystem"` (measured live: `t."project"."code"` is required - `Project`'s real primary
  key is `code`, not `id` as first assumed; comparing `t."project"."id"` answered `INVALID` live, `t."entity"."id"`
  worked since `StagingEntity`'s own PK genuinely is `id`) - and ONE query PER ENTITY for its own staging-table row
  counts - `SELECT c."sourceSystem" AS SYS, COUNT(*) AS N FROM "<Entity>" c GROUP BY c."sourceSystem"` (measured
  live against `Companies_Staging`/`Staging`/`Company`: `{ERP: 55, CRM: 52}`) - whose values also SUM into the "In
  staging" total, so no separate total query either. Needs a `Session` (added, old signature kept `@Deprecated`,
  falls back to `null`); the one in-tree caller (`ebx.ui.ProjectWizardUserService`) updated. Either query
  returning `null` (failed) makes the affected cell/entity fall back to the original per-cell/per-entity read,
  never silently zero.

## Verify live

1. `Companies_Master`/`Master`/`Company` (or any project's own master table) - open the wizard's step 6
   ("Matching results") for an ACCEPTED/APPLIED plan: KPI numbers, band, pending pairs (first 60), merged groups
   (first 60 golden ids) should read identically to before this lot, EXCEPT an auto-created golden's own source
   attribution (was `UNKNOWN`, now the add-on's own stamped value - see item 1).
2. Step 4's "Fill the shared structure" grid and the Cockpit's reject block/KPI tile should read identically to
   before.
3. `tools/test_local.sh` (with `ON_EBX_AGENT_JAR`/`ON_EBX_MAPPING_JAR` set) - "all green".

# Wave 2 - results snapshot (lot V2-A)

Branch `fix/wizard-matching-results-snapshot` (from main f295ea6d), 2026-09-30. Measured on the 100 000-row volume test
(project `Vol100k`, master `Vol100k_Master`/`Master`: `Personne` 57 568 rows, `Adresse` 88 991, no record merged, `Suspected` =
33 374 / 8 958 rows): every first render of the wizard, whatever the step, took 32-46 s and wrote 826 KB. Thread dumps showed the
HTTP thread in `MatchingResultsBuilder.build` (`queryStateSourceCounts`, `queryMergedGroupsCount`, `queryBandSegments`) and in the
add-on's `MetadataServiceImpl.findRecordsInGroup`. Lot 4 above gated that build with "step 6 shown, or first render"; every fresh
navigation is a first render, so it always ran.

## 1. What is computed when

The matching-results block of step 6 (KPIs, source band, merged groups, the first 60 pending pairs with their field comparison and
decision-tree detail, false positives) is computed once, when it can change, and only READ when the page is written.

| When | What | Where |
| --- | --- | --- |
| End of `MatchingConfigurator.run`, per entity that ran, after the golden-key refresh, inside the job | full computation | `MatchingResultsStore.compute` -> merged into the detail passed to `PipelineSteps.end` of that entity's `MATCHING_RUN` step |
| A pair decided (`RecordLineageReader.decideSuspect`, i.e. the wizard's queue and the lineage screen) | local patch of the stored figures (the pair and every pair of a merged record leave the list, pending/different counts move, flagged stale) + a `MatchingResultsRun` refresh queued | `MatchingResultsStore.afterDecisions` |
| Wizard bulk merge | same, once for the whole loop (`decideSuspect(..., pTrackResults=false)`, then one `afterDecisions`) | `ProjectWizardUserService.bulkMergeMatchPairs` |
| "Refresh results" button (`MATCH_RESULTS_REFRESH`) or a decision | `MatchingResultsRun` job (kind `MATCHING_RESULTS_RUN`, resource MATCHING - never concurrent with a run) recomputes every entity whose latest run is DONE | `MatchingResultsRun.HANDLER` |

Nothing is computed while a page is written: a missing or stale snapshot shows a small notice with a button that queues the job.

## 2. Where it is stored

No XSD change. `PipelineStep.step` is a static enumeration (a new key would be stored but flagged invalid), so the snapshot is the
`results` member of the `detail` JSON of the entity's latest `MATCHING_RUN` `PipelineStep` row (scope = entity name). Shape and
rules: `matching.MatchResultsSnapshot` (pure, tested): version 1, `at` (epoch ms), `stale`, `kpis`, `goldensWithSuspects`, `band`,
`colors`, `pending[]`, `pendingTotal`, `tree{}`, `merged[]`, `distinct[]`. Size: a value is cut to 80 characters and the whole JSON
is kept under 70 000 characters (`DEFAULT_BUDGET_CHARS`; the column cap is 100 000): over budget, the per-pair decision-tree detail
goes first, then the pairs are halved - totals are kept, so "N more not shown" stays right; a cut string is never stored. 60 pairs
of 8 fields measured 48 879 characters. Rows are read/written by `MatchingResultsStore` (`latestRunRow` is project + step + scope;
`PipelineSteps.updateDetail` rewrites only `detail`). Only a DONE row carries or shows results: a RUNNING row reads MISSING
("being refreshed"), a FAILED/STOPPED/RESET row shows no block.

Staleness rule (`MatchResultsSnapshot.state`): MISSING = nothing usable stored; STALE = flagged by a decision, or computed before the
start of the project's latest `MASTER_LOAD`; FRESH otherwise. STALE still shows the figures.

## 3. What a render reads

- Step 6: the snapshot rides on `plan.runStep.detail`, i.e. on the `MATCHING_RUN` row `WizardData.latestPipelineStep` already read
  for the plan's status line - no extra EBX read, one JSON parse per entity. That read used to filter on step + scope only, not on
  the project: two projects with an entity of the same name (Personne on every volume test) read each other's latest run; it now
  filters on the project too (mandatory now that the row carries the results). Only when the block is not fresh: one `FactoryJob`
  scan (`MatchingResultsRun.pending`) to say "being refreshed" instead of offering the button.
- The pair cards are written only when step 6 is the step shown (`Input.deferPairs`); otherwise a placeholder
  (`data-mdmf-deferred-step="6"`) that `wizard.js loadDeferredStep` reloads with the same `STEP_VIEW` round trip step 7 uses.
- Step 8 (summary): `buildSummary` no longer runs on the first render (`|| !postedOnce` removed); the panel is a deferred
  placeholder (`data-mdmf-deferred-step="8"`, rail button already posts `STEP_VIEW`) and the model is built only when step 8 is the
  step shown. Its whole-table aggregates (`GoldenRecordsReader`: counts and one fill-rate count per attribute) are cached in memory by
  `GoldenRecordsCache`, keyed on project + master dataset + `MatchingResultsStore.epoch` (bumped by every run and decision) + the
  start/status of the latest `MASTER_LOAD` and of each plan's latest run, expiry 10 minutes, 32 entries. Step 7 (distribution) shares
  the cache for its "the master holds N golden records" sentence.
- The bulk merge reads its eligible pairs fresh but cheaply (`MatchingResultsBuilder.buildPendingPairs`: the `Suspected` rows, no
  aggregate, no decision-tree call).

## 4. The wrong total (46 663)

Reproduced read-only on `Master_Personne_MDS`: `SELECT SUM(suspectedCount), COUNT(*) FROM RecordMetadata WHERE state = 1 AND
hasSuspects = true` = **66 748** and **46 663**; `SELECT COUNT(*) FROM Suspected` = **33 374** (26 119 distinct sources, 26 803 distinct
targets); `Adresse`: 17 916 / 17 210 / **8 958**. So:
- 46 663 is the number of GOLDENS carrying a suspect flag: `MatchingConfigurator.countPendingSuspectGoldens` (the "N Personne
  pair(s) await a decision" note of the Adresse run step, and `GoldenRecordsReader.pendingSuspects`, the summary's "N pairs to decide").
- 66 748 (the screen's KPI and "N more not shown") is `SUM(suspectedCount)`, exactly twice the pairs: each pair sits on both records.
- 33 374 is the number of distinct pending pairs = the rows of `Suspected` (one per pair, no status column - a decided pair leaves it).
Every "pairs to decide" figure is now `COUNT(*) FROM "Suspected"`: the KPI and tab count (`MatchingResultsBuilder.pendingPairTotal`,
fallback ceil(sum / 2)), the run note (`MatchingConfigurator.countPendingSuspectPairs`, renamed, `RequestResult.getSize()` on the
table), the summary's open point (`GoldenRecordsReader.pendingSuspects`, summed per entity). Goldens with suspects are kept, named
as such (`GoldenRecordsReader.Result.goldensWithSuspects`, `Input.goldensWithSuspects`, `RecordMetadata` `state=1 and
hasSuspects=true`), never shown as pairs.

The pairs themselves are read from `Suspected` (`sourceId`/`targetId` are master primary keys - `10000~45396` = master ids 10000 and
45396, HELENE-SOPHIE PERTEAU in CRM and ERP, a true duplicate), each record by primary key, anchor = `sourceId`. This removes the add-on
group read from the render path altogether and lists a pair once (the former route listed a pair twice when both records were among the 60
candidate goldens). The former route (flagged goldens, then `MameGroups`) remains as the fallback when `Suspected` cannot be queried.
The `GoldenRecordsReader` aggregate also lost its `Linked_Field_HasSuspects` column (a join to the metadataset per master row).

## 5. Statements kept, run live on `Vol100k_Master` (read-only `execute_query`)

All returned the figures above at once; the tool reports no elapsed time, so **no timing was measured** - what changed is that none of
them runs while a page is written any more (they run in a job, once per run/refresh).

| Statement | Live answer |
| --- | --- |
| `SELECT COUNT(*) FROM "Suspected"` | 33 374 / 8 958 |
| `SELECT s."sourceId" AS SRC, s."targetId" AS TGT FROM "Suspected" s ORDER BY s."id" FETCH FIRST 60 ROWS ONLY` | 60 rows |
| `SELECT COUNT(*) FROM "FalsePositiveDecision"` | 0 |
| `SELECT COUNT(*) FROM "RecordMetadata" WHERE "state" = 1 AND "hasSuspects" = true` | 46 663 / 17 210 |
| `SELECT "Linked_Field_State", "sourceSystem", COUNT(*) ... GROUP BY` on `Personne` | 3 rows: 19 200 ERP, 22 990 CRM, 15 378 ECOM, all state 1 |
| `SELECT COUNT(DISTINCT "Linked_Field_GoldenId"."id") ... WHERE "Linked_Field_State" = 2` | 0 (now skipped when the aggregate above shows no MERGED row) |
| two-level band `WITH G AS (...)` | same 3 rows (now skipped when nothing is merged: `MatchStats.bandFromGoldenCounts` builds it from the aggregate above, tested equal) |

## 6. Why the page weighs 826 KB

Every step's panel is written up front (hidden by CSS; `wizard.js` switches steps client side). Step 6 writes, per entity, one card per
pair (60) with its chips, three decision buttons and a folded field-by-field table: measured 4 638 characters per pair (8 compared
fields), i.e. ~278 KB per entity, ~550 KB for two entities - two thirds of the page. The rest is the other panels plus the inline
`wizard.js` (85 KB) and CSS (73 KB). Trimmed: on a render where step 6 is not the step shown, the cards are replaced by a 2 KB
placeholder per entity (the block drops from 280 537 to 2 224 characters in the measurement). Not trimmed: the size of step 6 when it is
the step shown (~280 KB per entity with 60 pairs) and the inline JS/CSS.

## 7. Not verified

Nothing here was run in the module (no deployment allowed): the job, the `PipelineSteps.updateDetail` procedure, the run-end
computation, the wizard render paths, the deferred placeholders (`loadDeferredStep` for steps 6 and 8) and the cache are compiled and
unit-tested on their pure parts only. Not measured: the time of each statement, the render time after the change, the duration of the
job at 100 000 rows. Open: a `decideSuspect` from another screen patches the snapshot only if a run row with results exists; a
matching run that failed after an earlier good one shows no block until the next successful run; the summary cache does not see
records entered through the add-on's own triggers (10-minute expiry); a merge policy that merges automatically on accept leaves
`mergedGroups`/record counts stale until the refresh job ends.

## 8. Lot M4 (2026-10-03, branch `fix/matching-run-defects`) - after an incremental run, and the jobs poll

- The snapshot's `kpis` gained `unmatched` (additive; an older snapshot reads 0): records whose `Linked_Field_State` is UNSET or NULL, i.e. not
  through matching yet. `queryStateSourceCounts` no longer filters `state <> 4` (a NULL state is not `<> 4`: Vol1m showed 338,257 records for a
  576,692-row table after an incremental run); `total` is the table's row count, DELETED aside, and step 6 says "N of the M records have not been
  through matching yet". The entity's run step message/detail state the scope (`scope.pivots` of `scope.totalRecords`). Details: `docs/MATCHING.md` §49.3.
- `GET /projects/{code}/jobs` (`FactoryJobStatus`) does one `FactoryJob` scan instead of two and reads a step's `detail` only for RUNNING jobs
  (not for the ten recent finished ones). `docs/MATCHING.md` §49.4.
- Still open, same defect class: `GoldenRecordsReader.queryEntityCounts` (step 8) keeps `WHERE "Linked_Field_State" <> 4`.

# Wave 11 - the wizard page weighs what is shown (audit F-42, F-48; branch `feat/w11-page-weight`)

Measured offline with `WizardPageWeightTest` (`-Dmdmf.sizeReport=true` prints the lines): `WizardPage.render` on a project of N business
objects of 25 fields (two mappings a field, a quality proposal on every other field, one feed with a rule per field), HTML only (the CSS,
73 KB, was already a static file). The fixture has no step 6 pair cards: the 826 KB of section 6 above is not reproduced here, only the
share that does not depend on the pairs.

| Business objects x fields | Page before wave 11 (every step, every card open) | Page now, step 1 / 2 / 3 / **4** / 5 / 6 / 7 / 8 |
| --- | --- | --- |
| 2 x 25 | 207 KB | 25 / 14 / 10 / **174** / 10 / 11 / 7 / 7 KB |
| 12 x 25 | 908 KB | 25 / 14 / 10 / **83** / 10 / 11 / 7 / 7 KB |
| 40 x 40 | 4 507 KB | 25 / 14 / 10 / **99** / 10 / 11 / 7 / 7 KB |

The inline script went from 123 650 characters (English; 99 493 of them `wizard.js`) to 24 926 (labels, the end-of-task notice, the
combobox, the loader, two small block scripts); `wizard.js` is fetched once per build.

1. **`wizard.js` is a static file** (`www/common/jscripts/factory/wizard.js`, its one source; `build.sh` copies it next to the classes
   for the inline form and the tests). The page writes the labels it needs (they depend on the language), then a loader that appends
   `<script src=".../wizard.js?v=<Assets.version()>">`: the address changes with every `build.sh`, so a changed file is never served from
   a stale cache. If the file cannot be fetched (error, or a proxy answering something else: the script sets `window.mdmfWizardReady`
   first thing), the loader asks once more with a changing `&r=` query no cache can hold, then shows `#mdmfScriptFail` ("reload the
   page...") instead of a wizard whose buttons do nothing. `-Debx.factory.wizard.inlineScripts=true` brings the former page back (whole
   script inline). `combo.js` and `jobnotice.js` (14 KB) stay inline: the Connexion and review screens read them from the class path.
2. **Only the step shown is written in full** (`Model.currentStepOnly`, set by `ProjectWizardUserService.model`). Each other step is a
   panel with its title and `data-mdmf-deferred-step` (the placeholder steps 6, 7 and 8 already used): Back, Next, the rail and the
   "go to step" links show it and `wizard.js` posts `STEP_VIEW`, which answers with that step in full; the opened step starts at its top
   (no stale scroll position). `-Debx.factory.wizard.allSteps=true` writes every step again. **What changes for a person**: a field typed
   on a step and not saved used to survive a look at another step (every step stayed in the page); now `wizard.js` asks first
   ("... the change is lost. Open the other step anyway?") when a real field of the step shown was changed - search boxes are not edits.
3. **Step 4 folds**: beyond `WizardPage.ENTITY_FOLD_THRESHOLD` (3) business objects, or while a filter is set, a card is written in full
   only when open; the others are one line (name, origin, status, "N fields (n to decide), n proposals to decide, n source links") and a
   Show button. Opened/folded cards, "Unfold all"/"Fold all" and the filter are plain state of `ProjectWizardUserService` (kept across
   postbacks, never persisted). The filter (name, label, target table of the object; name and label of its fields; every word must
   match) opens the matching cards, folds - never removes - the others and marks the matching field rows. The "decisions left" card
   writes at most `STILL_GROUP_LIMIT` (25) items per group and offers "Show the other N" / "Fold the list". The page-wide Accept all /
   Reject all and the counts read the snapshot, not what is written. All five new actions are VIEW-level (`core.FactoryRights`).
4. **No Connectors call while rendering** (F-48). The wizard drew each queued application's card (the 15 minutes after its onboarding job)
   from `SourceOnboarder.facts`: describe, catalog-source plan, mappings list - up to three calls per card per display. Every live
   `facts` read (the job's own, a person's action's) now leaves its result in `SourceFactsCache` (in memory, 200 connections at most); the
   display reads it with `SourceOnboarder.cachedFacts` and, when it is missing or older than 15 s (job queued/running) or 5 min
   (finished), asks `SourceOnboarder.refreshInBackground` - one daemon thread, one read per connection at a time, 16 queued at most, the
   thread dies when idle. A card with no facts yet says "Checking the connection..." (or the same on its line when an application is
   already ready) and the page asks again by itself every 3 s, at most 20 times a tab (`data-mdmf-sources-checking`). Entries are kept
   per reading user: a viewer only ever sees facts their own session read (the job's system session reads feed the job's own decisions,
   never a screen), so a person without the catalog right never sees what another session could see; their first display says "checking"
   until their own background read comes back (restricted as their rights are).
   Measured by the source test only (no Connectors in the offline suite): 0 `SourceOnboarder.facts`/`connections` calls in `model()`.

Not done: the size of step 6 when it is the step shown (pair cards, ~280 KB per entity for 60 pairs) and of an open step 4 card (about
65 KB for 25 fields: the attribute table, the feed's rules and the proposal lines are written whole).
