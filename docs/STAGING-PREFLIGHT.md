# Staging preflight - the check before "Build and load the staging" (lot H1)

Written 2026-10-17, branch `feature/staging-preflight` (worktree `ebx-mdm-factory-evo-preflight`, from main `a11e0517`).
Sibling lots of the same evolution: H2 (end-to-end scenario, `tools/itest`), H3 (code transcoding proposals), H4 (EBX refusal classes), H5 (polish).
Builds on lots D / E (`docs/UX-STAGING-TEST-FIXES-D.md`: `TransformVerifier`, `StepChainCheck`, `NormalisationProfiler`) and the reject causes of lot B (`RejectCauses`).

## 1. Why

The verifications of lots D and E run **when the proposal is made**. A link a person accepted or edited afterwards, or landing data that changed since, is never checked
again: on TiersB2CRetestLanding ONE accepted link - `isodate()` on the loyalty `date_naissance`, written `dd/MM/yyyy` - made EBX refuse 140 rows at the load, and nothing
said so before. The preflight replays every ACCEPTED link on the landing values **before the build writes any row**.

## 2. What it does

```
 staging/PreflightLink     one accepted link as the loader runs it + the landing values it reads         (pure value object)
 staging/PreflightCheck    link + engine -> PreflightFinding (issues, fix)                              (pure, engine injected)
 staging/PreflightFinding  identity of the link, issues (kind, blocking, rows, of, atLeast), fix        (pure, JSON round trip)
 staging/PreflightReport   findings + derivations waiting; blocking() decides; stored JSON               (pure)
 wizard/PreflightHtml      the block at the top of step 4, in the viewer's language                      (pure)
 ebx/StagingPreflight      reads (loader's own mappings), runs, stores the report, applies a fix          (EBX)
```

### 2.1 The replay (`PreflightCheck`)

Exactly what the loader does, per link, on the distinct values of the landing column with their rows (bounded like lot D: 2000 distinct values per column, one read per
column, **no connector call, nothing leaves the instance**):

1. the ACCEPTED cleaning steps in position order (`CleaningSteps.applyPipeline`) - a value a step turns empty never reaches the chain;
2. the link's chain (its pipeline, or what its type-driven transformation translates to, `PipelineCompat`) through `Transformations.apply`, the engine of
   `on-ebx-mapping.jar` the load runs;
3. what EBX checks at the write: a typed field (`DATE`, `TIMESTAMP`, `DECIMAL`, `INTEGER`, `BOOLEAN`) must receive its xs lexical form
   (`TransformVerifier.isValidFor`), a string must fit its maximum length.

The mappings are **the loader's**: `StagingLoader.acceptedMappings` runs the same reads as `StagingLoader.loadInternal` (same entities, same built-entity rule, same
foreign keys, `readMappings`, cleaning steps with status ACCEPTED / APPLIED) - one more `mappingId` field on `TransferPlan.MappingInfo` carries the id of the link.

### 2.2 What it says

| kind | rule | severity |
|---|---|---|
| `CONVERSION` | the chain throws, or leaves a value the typed field cannot store ("N rows of LOYALTY would be refused: birthDate <- date_naissance (dates written dd/MM/yyyy, transformation isodate()) - fix it in the matrix") | **blocking** |
| `LOST` | a typed field gets nothing from a value that holds one (`date(yyyy-MM-dd)` on a `dd/MM/yyyy` column): the conversion did not work, the value is lost | **blocking** |
| `TOO_LONG` | a string longer than the attribute's maximum length | **blocking** |
| `MANDATORY_EMPTY` | rows (empty at the source, or emptied by a cleaning step or the chain) that leave a mandatory attribute empty: the loader refuses them (`RejectReason.mandatory`). Counted per link, i.e. **per source** when several feed the attribute ("7 rows of WEB would be refused: the mandatory attribute email stays empty for them ... - map a column or make the attribute optional in the matrix"). No fix is offered | **blocking** |
| `LOST_PLUS` | a phone-like column loses its leading `+` (by a cleaning step or by the chain, e.g. `digits()`): the number is kept, its country is not | warning |
| `DUPLICATES` | rows repeating the value of the entity's ONLY business-key attribute inside this source (a composite key cannot be judged column by column) | warning |
| derivation waiting | an ACCEPTED script derivation that still waits for a parameter only a person can give (`DerivationParamGate`, the wizard's own reading) - its attribute stays empty | warning |
| `NO_FEED` | lot M7 (2026-10-18): an ACCEPTED entity that no accepted mapping feeds ("Nothing will be loaded into ContactEmail: no accepted mapping feeds it - map at least one attribute to a landing column and accept the mapping, or reject the entity"). An entity-level finding (no attribute, no column, no fix); the load then writes "No feed: ..." in the entity's `lastLoadMessage`. See `docs/STAGING-PROPOSAL.md`, "Lot M7" | warning |
| `NOT_CHECKED` | the chain reads another column, a live lookup or the clock (`TransformVerifier.readsOutsideItsColumn`), or the link belongs to a collection feed: nothing is claimed | information (counted) |

When the column has more distinct values than were read, every count is a lower bound and the page says "at least N rows".
Counts, format names and pipeline texts only: no value is in the report (it is served by REST and shown to anyone who sees the project).

### 2.3 The fix

When a blocking or a `+` issue has one, the fix is the one `TransformVerifier` derives from the measured profile **and only when it converts every sampled value**
(`FIXED`, only for a conversion that fails or a lost value - never for a too-long value or an empty mandatory attribute; for the loyalty file `trim|date(dd/MM/yyyy,yyyy-MM-dd)`; for `digits()` on a phone: the chain without `digits`; for a decimal comma: `trim|number`). A day / month
order no value proves is kept as `ambiguousPipeline`, shown as "choose it in the matrix", never offered.

**"Apply the fix"** (`StagingPreflight.applyFix`): writes `AttributeMapping.pipeline` of that link (the pipeline overrides the type-driven transformation, as lot D's `FIXED`),
appends a "Value check" note to its rationale (`CheckNote`: "fixed before the build: ... gave N refused row(s); '<pipeline>' converts every sampled value"), **keeps the status
ACCEPTED / APPLIED**, then checks again so the page shows what is left. A link edited since the report (its chain is no longer the one the report judged) is left alone and counted
`stale`. "Apply every fix" (when two links or more carry one) writes them all.

## 3. Behaviour

- **The build goes through the check.** `StagingLoadRun` (the job behind the wizard's "Build and load", the matrix' "Build and load the staging" and `POST .../staging/load`
  without `sync=true`) makes the check first, when its stages include the load, **before the model is touched or a row is written**: the journal says "Checking the accepted links on
  the landing values before any row is written."
  - **blocking findings** stop it: the step is recorded `SKIPPED` (neither done nor failed), the job's message is `Nothing to do: the build was not started - N row(s) on M link(s)
    would be refused. See the check at the top of step 4.` (coded `wizard.runtext.stg.preflight.blocked`, worded in the viewer's language; the task reads amber "Nothing to do"), and
    the report shows **at the top of step 4**;
  - **warnings never block**: the journal says how many, the build goes on;
  - **"Build anyway"** (`STAGING_LOAD_RUN_ANYWAY`, `StagingLoadRun.start(..., buildAnyway=true)`) sets the check aside (the job's parameters carry `buildAnyway: true`, the journal
    says so); the refused rows go to the reject review as before;
  - **fails open**: a check that cannot run (an unreadable column, a bug) says so in the journal and the build goes on - the check is a safety net, never a new way to lose a build.
- **Standalone "Check before building"** (`STAGING_PREFLIGHT`): a secondary button next to "Build and load" and in step 4's "Advanced" fold. It runs in the postback (a bounded
  sample per column, no connector call: seconds), stores the report and shows it.
- **The matrix' "Build and load" button** starts the same job, so it goes through the same check; when the check stops it, the page it returns to (step 4) shows the report.
- **The report at the top of step 4** (`PreflightHtml`, above the "rows not loaded" banner): the title, one line per link grouped by business object with "Apply the fix" /
  "Open the matrix", "Apply the N fixes", "Build anyway" (blocking only), "Check again". Not drawn while a build runs, nor once a build has started after the report was made
  (`StagingPreflight.builtSince`: it is history then), nor to a viewer without the design right as buttons (the report is read).
- **Persisted**: the last report is the `detail` JSON of a `PipelineStep` named `STAGING_PREFLIGHT` (a free text step name, like `STAGING_LOAD_RUN`; no field added).

## 4. REST

`/ebx-mdm-factory/rest/v1/projects/{code}/staging/preflight` (rights in `core.FactoryRights`):

| route | right | answer |
|---|---|---|
| `GET` | view | the last stored report, `{"exists": false}` when none: `exists`, `builtSince`, `at`, `linksChecked`, `linksNotChecked`, `blocking`, `blockingRows`, `blockingLinks`, `warnings`, `findings[]`, `derivationsWaiting[]`, `notes[]` |
| `POST` | design | makes the check now, stores it, answers it (same body, `exists: true`). `?apply=<mapping id>` (or `apply=*` for every fixable link) first writes the fix of the LAST report and checks again; the answer carries `fix: {applied, stale, missing}` |

A `finding` is `{mapping, entity, attribute, source, table, column, dataType, pipeline, written, writtenKind, issues:[{kind, blocking, rows, of, atLeast, detail}], fix:{pipeline, rows}, ambiguous}`.
`POST .../staging/load?sync=true` keeps its pre-lot behaviour (it calls `StagingLoader.load` directly, no job, no check) - an end-to-end scenario that wants the check calls the route above first.

## 5. Files

New: `staging/PreflightLink`, `PreflightCheck`, `PreflightFinding`, `PreflightReport`; `wizard/PreflightHtml`; `ebx/StagingPreflight`; tests `PreflightCheckTest`, `PreflightHtmlTest`.

Touched outside new classes (all small, kept apart from the lot's other work):
- `ebx/StagingLoadRun` - `start(..., buildAnyway)` overload, `buildAnyway` job parameter, `preflight(...)` called at the top of `runInBackground` (before the model stage);
- `ebx/StagingLoader` - `acceptedMappings(...)` (the loader's own reads, package-private) and the mapping id passed to `TransferPlan.MappingInfo`; `transfer/TransferPlan.MappingInfo` - one `mappingId` field + constructor overload;
- `staging/TransformVerifier` - `readsOutsideItsColumn` (the test `verify` already makes before `UNVERIFIABLE`, package-visible);
- `landing/RunTexts` - six codes (`PREFLIGHT_*`) for the journal and the job's message;
- `ebx/ui/ProjectWizardUserService` - actions `STAGING_PREFLIGHT`, `STAGING_PREFLIGHT_FIX`, `STAGING_LOAD_RUN_ANYWAY`, `checkBeforeBuild`, `startStagingLoadRun(..., buildAnyway)`, the model field;
- `wizard/WizardPage` - `Model.stagingPreflight`, `renderStep4Preflight`, `renderStep4CheckButton` (BUILD state + Advanced fold);
- `core/FactoryRights` (3 wizard actions, 2 routes), `rest/FactoryResource` (2 routes), `wizard.css` (a few lines);
- `Messages_en` / `Messages_fr`: one block "staging preflight (2026-10-17)" at the end (fr ISO-8859-1 `\u` escapes; no apostrophe in a message with an argument).

## 6. Deployment

**No XSD change, no `module.xml` change: no data-model restart.** Java classes: the usual restart at deployment. Nothing was installed or run live from this lot.

## 7. Not verified / to watch

- Nothing ran under Tomcat: `StagingPreflight` (reads, store, `applyFix`), the blocked path of `StagingLoadRun`, the wizard actions and the REST routes are verified by compilation, the rights
  tests, and the pure classes (`PreflightCheckTest`, `PreflightHtmlTest`) which run the REAL Connectors engine on the real B2C files (`isodate()` on `date_naissance` = 140 blocking rows and the
  `dd/MM/yyyy` fix).
- The standalone check and "Apply the fix" run in the postback (one distinct-value read + one aggregate read per mapped landing column). Measured nowhere yet on a wide project; if it is
  slow, move it into a job like the proposal (the report and the page would not change).
- `MANDATORY_EMPTY` is BLOCKING (coordinator's change after the first version, which made it a warning): the loader refuses such rows. It is counted from the exact table counts (`COUNT(*)` minus the filled rows)
  plus the sampled values a step or the chain empties, so on a sampled column it is "at least".
- `LOST_PLUS` stays a warning (no row is refused), like a repeated business key; its fix exists only when the `+` is lost by the chain, not by a cleaning step (the step is named in the matrix).
- Composite business keys and duplicates ACROSS sources are not judged. A column with more than 2000 distinct values gives lower bounds ("at least").
- The written format names ("decimal comma") are English in the French page (format names, like `dd/MM/yyyy`).
