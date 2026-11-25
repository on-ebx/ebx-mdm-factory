# Incremental landing → staging transfer

Written 2026-09-23 on branch `feature/incremental-staging` (worktree
`ebx-mdm-factory-incremental-staging`). the product owner's request, as product owner: make the landing → staging
transfer incremental. Until this lot, `StagingLoader` re-read and re-transformed EVERY landing row of
every feed on every `staging/load` run - documented in its own class Javadoc as "No change detection",
and flagged as a known limitation in `docs/TRANSFER-WORKFLOW.md` §5 ("fine for the volumes seen on this
instance so far... worth revisiting before wiring a tight schedule onto a real-sized landing zone").

**Nothing in this lot was run live.** Every EBX access this lot used was READ-ONLY (`data-access`
discovery/count/search, `get_xpath_syntax`) to verify the XPath predicate against real landing data
(`Companies_Landing`/`ERP`/`/root/SUPPLIER`) and confirm its shape; no write tool was ever called, no
`staging/load` or `landing/flows` route was ever invoked, no Tomcat restart happened. `tools/test_local.sh`
is green (compiles against the real `ebx-lib.jar`, every pure unit test passes, the XSD diff is clean) -
that is the only verification this lot has had. The coordinator deploys and runs it.

## 1. The design

### 1.1 `_status` changes meaning: "version received", not "ever transferred"

`landing.LandingFlow#addStatusRule`'s `_status` rule was `constant(NEW)` with policy `IF_EMPTY` - a
reload never touched a row a previous transfer had already moved past `NEW`. That made `_status` mean
"has this row EVER been transferred" - useless for telling "reloaded with new content" apart from
"reloaded, identical" apart from "never reloaded again", which is exactly why `StagingLoader` had no
incremental read to begin with: there was nothing on the landing row that distinguished those cases.

The rule is now `ALWAYS`: every (re)received row goes back to `NEW`, whatever a previous transfer already
did with it. `STAGED`/`REJECTED` becomes the outcome of the LAST transfer of that version, not a
permanent mark. A useful side effect: a row rejected because the source data was bad is now retried
automatically the next time the source resends it (fixed or not) - no separate "retry rejected rows"
action needed.

### 1.2 `StagingLoader` reads a `scope`: `new` (default) or `all`

`StagingLoader.load` gained a fourth parameter, `pAllRows` (`boolean`):

- **`new`** (the ordinary, recurring case) reads only landing rows still at "version received, not yet
  transferred": the XPath predicate `./_status='NEW' or osd:is-null(./_status)` (verified live,
  2026-09-23, against `Companies_Landing/ERP//root/SUPPLIER` via `data-access`'s `get_xpath_syntax` +
  `count_records_by_xpath`: the predicate executes with no error). The `osd:is-null` half is defensive,
  not expected to ever match once every landing row has gone through the `ALWAYS` rule at least once -
  EBX stores an empty string as null (CLAUDE.md §9), so a plain `./_status=''` would silently never match
  anything and was deliberately not used.
- **`all`** is the ORIGINAL, pre-2026-09-23 behaviour: every landing row, whatever its `_status`. Still
  needed after a mapping, a cleaning step, or a derived-field rule changes - the source content of an
  already-`STAGED` row may not have changed, but its transformed OUTPUT should now differ, and only a
  full pass re-examines it.

The pure scope-parsing/predicate logic lives in a new class, `transfer.IncrementalTransfer`
(`IncrementalTransfer#isAllScope`, `#landingPredicate`), unit-tested without an EBX runtime
(`IncrementalTransferTest`) - same convention as every other pure class in the `transfer` package
(`FieldAvailabilityRule`, `NullFillRule`, `BatchRetry`).

### 1.3 A second, finer check: skip the write for a row whose content did not change

Even under scope `new`, a landing row can be re-read (its `_status` says so - the source resent it) yet
carry EXACTLY the same content as before (a full-table SQL feed that resends every row on every extract
is the common case). Every staging table now gets a new technical field, `_sourceHash` (string) - the
landing row's own `_rowHash` the staging row was last (re)built from. `IncrementalTransfer#shouldWrite`
is the pure decision:

```
shouldWrite(existingHash, incomingHash, allRows, sourceHashFieldExists):
    if allRows or !sourceHashFieldExists or existingHash == null: true   # always write
    else: existingHash != incomingHash                                  # write only if changed
```

(Target-mapping lot 1, 2026-09-23: `processRow` - what this section still calls `planRow`, renamed when
`StagingLoader` was rewritten around Connectors' `MappingEngine` - runs once per row per FEED, root or
child alike; a child feed's own `shouldWrite` check is independent of its root's, using the SAME incoming
hash, the root landing row's own `_rowHash` - see `docs/TARGET-MAPPING.md`.)

When it says "don't write": the staging row is left exactly as it is (no upsert, no Xref write), the
landing row is still marked `STAGED` (a transfer of that version DID happen, it just produced no change
to write), and the row is counted separately - `unchanged`, both per entity (`StagingEntity
.lastLoadUnchanged`) and in the run's own JSON totals - never folded into `written`. In scope `all`
this hash check is not used: `all`'s whole purpose is to re-apply the transformation pipeline even to a row
whose SOURCE content is unchanged (the pipeline itself may have changed instead), so the hash cannot say
"skip". (Until lot V3-S, 2026-10-04, scope `all` therefore wrote every row; since then it re-applies the
pipeline to every row and writes only the rows whose RESULT differs - section 8.)

The existing-row lookup this needs is only ever done when it could actually change the answer (scope
`new` AND the field exists) - `processRow` skips it entirely under `all` or when `_sourceHash` is not yet on
the table, since the answer ("write") is already known without reading anything.

### 1.4 A staging table that predates `_sourceHash` keeps working

`_sourceHash` is added to `StagingModel#buildTable` alongside the other technical bookkeeping fields
(`_loadId`, `_receivedAt`, `_status`, `_rejectReason`). For a table `staging/generate` already published
before this lot, `StagingModelDiff` sees it as one ordinary new field on the next regeneration - an
additive `create_field` op, exactly like any other new optional field, needing no person's decision
(`StagingModelDiffTest#existingTableWithoutSourceHashGetsItAdditively` exercises this exact shape).

Until a project's staging model IS regenerated, `StagingLoader` never assumes the field exists: it checks
the live schema once per feed (`AdaptationTable#getTableOccurrenceRootNode().getNode(...)`, the same
defensive pattern already used for every accepted-but-not-yet-generated field - CLAUDE.md §9,
`Adaptation.get(path)` throws on an undefined path). When the field is missing, every row of that entity
is treated as changed (whatever scope was requested) and the run's JSON reports `sourceHashField: false`
for that entity, and `false` at the top level as soon as ANY loaded entity is missing it - the signal a
caller (the wizard, a monitoring screen) can use to tell a project owner to regenerate.

### 1.5 What was checked and left alone

Per the product owner's brief, every other stage of the transfer was read and verified to stay correct under
scope `new`, not modified:

- **Foreign-key resolution** (`processRow`'s immediate-FK lookup) already resolves against the
  already-loaded STAGING table, not the landing rows read this run - a parent transferred on an earlier
  run is still found. Unaffected by scope.
- **The `UNIQUE` quality-rule pass** (`StagingLoader#runUniqueCheck`) explicitly scans the entity's WHOLE
  current staging table content every run, by design ("a whole-entity, whole-load concern" - its own
  Javadoc) - never limited to rows this run touched. Unaffected by scope.
- **`resolveDeferredReferences`** (pass 2: self references, cycle edges) reads the already-staged rows of
  the owning table, not landing. Unaffected by scope.
- **The cross-system fold / derived-field rules** (`quality.CrossSystemFold`, `DerivedFieldRule`) run per
  row, on whatever rows THIS run's pass 1 actually read - correctly scoped down with `new`, same as any
  other per-row computation.
- **The `REFERENCE`/`CHECK_DIGIT`/`PATTERN` quality rules** (`recordQualityCheck`, run inside `processRow`)
  DO depend on "every landing row was read this run": under scope `new`, `QualityRule.lastLoadChecked`/
  `lastLoadFailing` become a DELTA (only the rows actually re-examined this run), not a full-table count,
  the way they were before this lot (when every run WAS a full pass). This is a real, deliberate behaviour
  change, not fixed further this lot - a full, current picture of a REFERENCE/CHECK_DIGIT/PATTERN rule's
  pass rate needs a scope `all` run. `UNIQUE` is unaffected (see above): it is the one rule kind that was
  already whole-table by design.

## 2. What an existing project (e.g. `Companies`) must do before its first incremental run

Nothing here is automatic - a project generated before 2026-09-23 keeps its OLD landing mapping
(`_status` still `IF_EMPTY`) and its OLD staging model (no `_sourceHash`) until these two steps run:

1. **Regenerate the landing flows**, once per project source: `POST
   /ebx-mdm-factory/rest/v1/sources/{projectSourceId}/landing/flows?force=true` (what the wizard's
   "Generate the flows" button does with `force`). `connectors_mapping_upsert` is idempotent on the
   mapping's own label (`ebx.LandingFlows#generate`'s own Javadoc), so forcing safely OVERWRITES the
   mapping already written in place with the new `ALWAYS` `_status` rule - no dry-run/diff step needed,
   and nothing else about the mapping changes. Until this runs, that source's landing rows keep the old
   `IF_EMPTY` behaviour (a reload never resets `STAGED`/`REJECTED` back to `NEW`), which means scope
   `new` would never re-see a row that source already transferred once, correct behaviour or not.
2. **Regenerate the staging model**: `POST /ebx-mdm-factory/rest/v1/projects/{code}/staging/generate`
   (what the wizard's "Generate the staging model" button already does - no new flag). `StagingModelDiff`
   reports the new `_sourceHash` field on every existing entity as one additive change per table; the
   loader (`StagingGenerator`) applies it and republishes. Until this runs, `StagingLoader` reports
   `sourceHashField: false` for that entity and treats every one of its rows as changed under scope `new`
   - still correct (no data risk), just not yet taking advantage of the unchanged-row skip.
3. **First transfer after both of the above: run scope `all` once**, then go back to the default (`new`)
   for every later run. Reasoning: a table just regenerated with `_sourceHash` has EVERY existing staging
   row at `_sourceHash = null` (the field is new, nothing has ever written it) - under scope `new`,
   `shouldWrite` already treats a `null` existing hash as "write" (so this would self-correct within one
   `new` run too, at the cost of a full-table pass hidden inside a `new`-labelled run), but starting with
   an explicit `all` run is the clean, honest way to both populate `_sourceHash` on every row AND confirm
   the regenerated model's own field-level correctness (constraints, foreign keys) against every row, not
   just the ones that happen to reload first. The wizard's own "Load the staging" button already always
   passes `all` (a design-time action after changing mappings - see `ebx.ui.ProjectWizardUserService
   #loadStagingData`'s own Javadoc), so simply clicking it once after regeneration satisfies this step.

`Companies` specifically (the project measured live in `docs/TRANSFER-WORKFLOW.md` §6): its landing
tables read 0 rows against the new `new`-scope predicate today (measured live, read-only, 2026-09-23 -
every row is already `STAGED` under the OLD `IF_EMPTY` rule) - exactly the "keeps working, does nothing
useful yet" state step 1 above describes. It needs steps 1-3 before an incremental run does anything.

## 3. Every `StagingLoader.load` caller and the scope it now passes

(`grep -rn "StagingLoader.load" --include="*.java"`, 2026-09-23 - three real call sites, everything else
is a Javadoc `{@link}`.)

| Caller | Scope passed | Why |
|---|---|---|
| `rest.FactoryResource#loadStaging` (`POST /projects/{code}/staging/load`) | `?scope=new\|all` query param, default `new` | Since 2026-10-12 any other word answers 400 (`core.ScopeParam`, `docs/MATCHING.md` section 54) - it used to read as `new`. `IncrementalTransfer#isAllScope` stays the lenient pure reader for internal callers |
| `ebx.ui.ProjectWizardUserService#loadStagingData` (wizard step 4 button) | Always `true` (`all`) | A DESIGN-time action repeated right after changing a mapping/cleaning-step/derived-field rule - exactly the case scope `new` would silently under-transfer |
| `workflow.StagingLoadTaskBean#executeScript` | `BeanScope.parse` over `new`/`all` (an unknown word stops the step, 2026-10-12), bean property `scope`, default `new` (blank/unset) | Wired in `tools/workflow/MdmFactoryPipeline.json` to a new `stagingScope` data-context variable (default `"new"`), kept separate from node 9's own `scope` (the matching run's scope) - the two steps' scopes are independent choices |

## 4. Reporting shape (the loader's JSON result)

New top-level fields: `scope` (`"new"`/`"all"`), `unchanged` (the run's total), `sourceHashField`
(`false` as soon as ANY loaded entity's staging table lacks the field). Per-entity (`entities[]`): the
same `unchanged`/`sourceHashField`, alongside the pre-existing `read`/`written`/`skipped`/`rejected`.
`StagingEntity.lastLoadUnchanged` (a new `long` field, `tools/build_xsd.py`, same shape as its
`lastLoadRead`/`Written`/`Skipped`/`Rejected` siblings) carries the per-entity total across runs, same as
those siblings already do. `StagingLoadTaskBean` gains a matching output property, `unchanged` (text) -
not yet wired to a `MdmFactoryPipeline.json` data-context variable (nothing in this lot's brief asked for
it; a natural follow-up if a monitoring screen wants it).

## 5. What this does NOT cover (deliberately out of scope this lot)

- **Source-side deletions.** A row removed at the source is never told to the landing/staging pipeline at
  all (no delete feed exists) - staging keeps serving a stale row forever. Not attempted here; the
  eventual fix is a source-side "watermark"/full-reconciliation pass (see below), not something
  `_status`/`_sourceHash` can express on their own (there is no landing row left to carry either flag).
- **Staging → master stays a full transfer, unchanged.** `MasterLoader` was read (its Javadoc, its field
  plan built from `StagingAttribute` rows, never from raw schema enumeration) and confirmed to already
  tolerate `_sourceHash` safely - it copies only the business attributes it knows about, so the new
  technical field is simply never touched by it, no change needed there - but `MasterLoader` itself keeps
  reading every staging row on every `master/load` run. Making that incremental too (e.g. against
  staging's own `_loadId`/`_receivedAt`, or a `_status`-like field on staging) is a natural next lot, not
  started here. (Done since, lot M-INC, 2026-10-12: not against `_loadId`/`_receivedAt` but against a journal of changed staging rows that generated
  staging triggers write, so a steward's edit is covered too - `POST master/load?scope=changed`, `docs/VOLUME.md` "Lot M-INC".)
- **The source-side watermark ("couche 2" of the original three-layer plan).** This lot is "couche 1":
  landing → staging, based on what the landing model can already tell (a reload happened, the content
  hash). A true source-side incremental EXTRACT (only pulling changed rows FROM Snowflake/the source
  system in the first place, via a watermark column or CDC) is a separate, larger change to
  `LandingFlows`/`connectors_sync` and the source connector configuration - explicitly planned for later,
  not touched by this lot at all. Today's `landing/load` still pulls every row of every table on every
  run; this lot only changes what happens to those rows AFTER they land.

## 6. Files touched

- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/landing/LandingFlow.java` - `_status` rule policy
  `IF_EMPTY` → `ALWAYS`.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/transfer/IncrementalTransfer.java` - new pure class
  (`isAllScope`, `landingPredicate`, `shouldWrite`).
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/staging/StagingModel.java` - new `_sourceHash`
  technical field.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/StagingLoader.java` - `load` gains `pAllRows`;
  scoped landing read; unchanged-row skip; `unchanged`/`sourceHashField` counters and reporting; class
  Javadoc's "No change detection" paragraph replaced.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/core/FactoryPaths.java` - `StagingEntity
  .LAST_LOAD_UNCHANGED`.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/rest/FactoryResource.java` - `/staging/load` gains
  `?scope=`.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java` -
  `loadStagingData` always passes `all`.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/workflow/StagingLoadTaskBean.java` - `scope` input,
  `unchanged` output.
- `tools/build_xsd.py` / `ebx-mdm-factory/src/main/webapp/WEB-INF/ebx/schemas/MdmFactory.xsd` -
  `StagingEntity.lastLoadUnchanged` (regenerated, diff clean).
- `tools/workflow/MdmFactoryPipeline.json` - new `stagingScope` variable, node 3's `scope` beanParam
  (§7.1 of `docs/TRANSFER-WORKFLOW.md` has the full detail, alongside the separate library-mode-beans
  change on the same branch).
- Tests: `IncrementalTransferTest` (new), `StagingModelTest`/`StagingModelDiffTest` (updated for the new
  technical field), `LandingFlowTest` (updated for the `ALWAYS` policy), `StagingLoadTaskBeanTest`
  (`unchanged` helper coverage added).

## 7. Bug found live, 2026-09-23 (later): a landing table shared by two entities only fed the first one

**Found by the coordinator**, deployed and run as workflow instance 564, project `Companies` - the very
first live run of this lot's scope-`new` code path (the earlier §6 file list is what shipped with the
bug; this section documents the fix, on the same branch, same commit range).

(Target-mapping lot 1, 2026-09-23, merged after this fix: the single per-feed `StagingLoader#flush` this
section describes became `flushOne`/`flushTree` in the feed-tree rewrite - one call per node of a feed
tree, root or child - but the bug and the fix below are unchanged: `PendingLandingStatus` is fed only by a
ROOT feed's own landing read, exactly as this section describes, never by a child feed. See
`docs/TARGET-MAPPING.md`.)

### 7.1 What happened

Landing was reloaded (every row back to `_status=NEW`, confirming the `ALWAYS` rule itself works
correctly - `mapping 220` carries `_status constant(NEW) ALWAYS` as intended). Then `StagingLoader.load`
(`scope=new`) reported, per entity:

| Entity | read | unchanged |
|---|---|---|
| Supplier | 55 | 55 |
| Account | 52 | 52 |
| Contact | 89 | 89 |
| **Company** | **0** | **0** |

A scope-`all` run on the SAME data reads `Company` = 107 = 55 (Supplier's own landing rows) + 52
(Account's own landing rows) - **`Company` is fed by the SAME two landing tables** (ERP `SUPPLIER`, CRM
`ACCOUNT`) that ALSO feed the `Supplier`/`Account` entities directly (a canonical/member relationship -
`StagingModel`'s own Javadoc, "a canonical (language-model) entity and one of its members can both be
accepted at once"; `TransferPlan.loadOrder` processes `Supplier`/`Account` before `Company`).

### 7.2 Root cause

`StagingLoader#flush` wrote each batch's landing `_status` back to EBX immediately
(`writeLandingStatus`), per feed, per batch - the pre-existing design, unchanged by §1's scope work.
Under scope `new`, the landing READ predicate is `./_status='NEW'` (§1.2). So: `Supplier`'s feed reads
`SUPPLIER`'s rows at `_status=NEW`, processes them, and writes `_status=STAGED` back before
`TransferPlan.loadOrder` even reaches `Company`. When `Company`'s OWN feed on the SAME `SUPPLIER` table
runs next, every row is already `STAGED` - the scope-`new` predicate matches nothing. A landing table
consumed by N entities only ever fed the FIRST one, under scope `new`. (Scope `all` never showed this,
because its read predicate is `null` - "no filter" - so every entity always re-reads the whole table
regardless of `_status`; that is exactly why this bug shipped without being caught by this lot's own
read-only, pre-live verification, which never had EBX write access to reproduce a multi-entity feed.)

### 7.3 The fix

**Landing status write-back is deferred to the very end of the run**, accumulated across EVERY entity's
EVERY feed first:

- A new inner class, `ebx.StagingLoader.PendingLandingStatus`, replaces `flush`'s direct
  `writeLandingStatus` call with a `merge` into a per-landing-table accumulator (keyed by
  `landingDataspace|landingDataset|landingTableName` - plain strings off `TransferPlan.Feed`, never
  `Adaptation`/`AdaptationHome` identity, which two separate lookups of "the same" dataset do not
  guarantee stable - CLAUDE.md sec 9).
- `StagingLoader#load` writes the accumulator back exactly ONCE, ONLY after pass 1 (every entity's every
  feed) AND pass 2 (deferred references) BOTH complete without throwing - `flushPendingLandingStatus`,
  one landing table at a time, in batches of `FactorySettings.transferBatchSize` rows (never one giant
  `Procedure` for a large landing table - the coordinator's own instruction), reusing the existing
  `writeLandingStatus` (already a single `Procedure` per call - just handed a bounded chunk now instead
  of the whole map).
- **Merge rule** for two different feeds' decisions about the SAME landing row within one run:
  `REJECTED` wins over `STAGED` - a row any entity's feed rejected must be retried next time and stays
  visible in `TransferReject`, even when a different entity's feed accepted the very same row cleanly.
  Pure, unit-tested (`transfer.IncrementalTransfer#mergeLandingStatus`/`#mergeLandingStatusMaps`,
  `IncrementalTransferTest` - six new cases including a scenario shaped exactly like the live bug: two
  feeds touching the same landing table, one rejecting a row the other staged).
- `unchanged` rows are merged into the SAME accumulator, as `STAGED` (they already went into `flush`'s
  own `landingStatus` map alongside `written`/rejected rows before this fix - no change to that part).
- **Crash safety, explicit** (`StagingLoader`'s own class Javadoc, and `PendingLandingStatus`'s): a crash
  anywhere during pass 1 or pass 2 is caught by `load`'s existing `try`/`catch`, re-thrown, and the
  accumulator is discarded unwritten - every landing row this run touched stays at `_status=NEW`. This is
  SAFE: scope `new` picks every one of them back up on the next run, and the staging upsert
  (`StagingLoader#upsertOne`) is idempotent by primary key regardless of scope. This is actually SAFER
  than the per-batch write-back it replaces, which could leave an earlier entity's rows already marked
  `STAGED` while a later entity in the same crashed run never got a chance to process them.
- `TransferReject`/`Xref` writes in `flush` are untouched - they still happen per batch, as before; only
  the landing `_status` write-back moved.
- **Scope `all` behaves identically to before**, apart from the MOMENT its own landing-status writes
  happen (once at the end instead of per batch) - its read predicate was already `null` (no filter), so
  it never depended on an earlier feed's write-back to see its own rows, and its merge rule only matters
  when two feeds decide differently for the truly same row, which is scope-independent.

### 7.4 What was verified this fix (still nothing run live by this session)

`tools/test_local.sh` green, `IncrementalTransferTest` covers the merge rule (including the live-shaped
scenario above). The fix was NOT run against a live EBX instance by this session - it addresses exactly
the measured live symptom (Company read 0/0, Supplier/Account/Contact all correct) by removing its root
cause (per-batch write-back racing a later entity's own read of the same landing table), but re-running
workflow instance-style verification (a fresh `staging/load?scope=new` on `Companies` after this fix
deploys) is for the coordinator, same as every other live step in this lot.

## 8. Scope `all` since lot V3-S (2026-10-04): the pipeline runs on every row, only the rows whose result differs are written

**Measured problem** (project Vol1m, EBX 6.3.0-rc2): a scope `all` load with nothing changed submitted every one of 1,465,857 rows with
`doModifyContent` (349 s), whatever the values - each submitted row ran the staging table's script triggers, rewrote its `Xref` row with a new
`occurredAt`, and re-stamped `_loadId`/`_receivedAt`, so every row looked freshly received. Details and design: `docs/VOLUME.md`, "Lot V3-S".

**What scope `all` means now.** It still reads every landing row and re-applies the whole pipeline to it - mappings, cleaning steps, derived-field
rules, quality rules, foreign-key resolution - because that is its purpose (a mapping or a rule changed, the source did not). What changed is the
write: the loader reads the existing staging row once, compares each value it would write with what the row holds (`transfer.StagingRowDiff`), and
writes the row only when something differs. Scope `new` is untouched (it skips on `_sourceHash` and reads only new landing rows).

- **Compared**: every entry of the row's planned field map (business fields, foreign-key fields, the structural parent link of a child, derived fields,
  forced nulls), `_sourceHash` when the table carries it (a row whose landing hash changed but whose mapped values did not is still written, so scope
  `new` later sees the new hash), and `_status` (the stored status must already be `STAGED`; a row in another status is written).
- **Not compared**: `_loadId`, `_receivedAt` (they do not differ because of the data), `_rejectReason` (never written by the loader), a script-derived
  attribute that is APPLIED (the table triggers keep it; it is not in the planned map). An unapplied script-derived attribute is planned as null and
  compares like any field.
- **Form of the comparison**: the planned xs-string is parsed with `SchemaNode.parseXsString` - the call `setValueFromXsString` makes before `setValue`
  (javap on `CfContentHolder`) - and the typed value is compared with `Adaptation.get` (decimal by value, date by instant, empty string = null); a foreign
  key is compared as its key text. Anything not shown equal counts as different and the row is written as before.
- **An unchanged row**: no `doModifyContent`, no `Xref` write, `_loadId`/`_receivedAt` untouched - on a staging row they now mean "the load that last
  CHANGED this row" in both scopes. It is counted `unchanged` (per entity, `StagingEntity.lastLoadUnchanged`, the run's JSON, the step message); `written`
  counts only rows really written. A batch made only of unchanged rows opens no Procedure. Each row of each entity of a feed tree is judged on its own: a
  child row can change under an unchanged parent.
- **Rejects come first**: a row that fails a REJECT-severity rule, a blocking constraint or a foreign-key lookup is rejected whatever it would have compared
  to. Only a row that passed every check reaches the comparison.
- **Landing status**: a landing row already at the status the run gives it (`STAGED`, or `REJECTED` again) is not written back; one still `NEW`
  (reloaded since the last transfer) becomes `STAGED` as before. End state identical to the old behaviour.
- **`Xref`** (staging side): for a row that is written, the `Xref` is written only when it does not already hold every text this write would set
  (project, staging table and key, landing dataset, table and key, source system and id) - `loadId`/`occurredAt` of an `Xref` therefore say when it was last
  written. The prior-reject resolution of a first-time insert is unchanged.
- **The CHILD_KEYS sweep, the deferred-reference pass and the UNIQUE check** run after the load exactly as before.

Wording: the `unchanged` total (section 4) used to stay at 0 under scope `all`; it is now the number of rows an `all` load found up to date
(an `all` load of unchanged data reads N, writes 0, unchanged N). The wizard's live cell, the job result and the staging summary say it (written + unchanged
= the rows staging holds from the load).


**Reads of a scope `all` load since lot V3-S2 (2026-10-04).** The comparison above no longer costs a random read per landing row. Before the first row of the first root feed that needs a staging table, the loader
reads that table ONCE and keeps, per staging key, a 128-bit digest of what `StagingRowDiff` compares (`ebx.StagingLoadReads`, `transfer.StagingRowDigest`); a row whose planned digest equals the stored one is `unchanged` with
no read, the empty-child check and the parent-row check of a child feed are answered from the key sets of the tables, and only a row whose digest differs (or is unknown, or whose key was written earlier in the load)
takes the read-compare-write path of lot V3-S. Same rows written, unchanged and rejected, same `Xref` and landing-status end state, same counters; the entity detail adds `readMode`, `stagingIndexRows`,
`unchangedByIndex`, `stagingRowReads` and the parent / empty-child check counters. Above 15,000,000 rows in a table (`mdm.factory.stagingLoad.maxIndexedRows`, 0 disables; 5,000,000 before lot IDX, which made the index about 3.5 times smaller) or when a scan fails, the table keeps the lot V3-S
path. Scope `new` is untouched. Details, memory and what to measure live: `docs/VOLUME.md`, "Lot V3-S2".
