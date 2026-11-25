# Step reset (wizard steps 3-6)

Written 2026-09-23 on branch `feature/step-reset` (worktree `ebx-mdm-factory-step-reset`), answering
the product owner's request the same day: *"Pouvoir nettoyer cette etape en cas d'erreur serait interessant. Sachant
que nettoyer une etape doit egalement nettoyer les suivantes."* Trigger case: at step 4 (Staging) of project
`A`, a second language-model pass created a duplicate entity; there was no way to put that step back to zero
and start it over without deleting the whole project (screen 7, `docs/PROJECT-DELETION.md`) and losing the
chosen sources.

**Written, never deployed, never run against EBX.** No `install.sh`, no Tomcat restart, no MCP write call was
made for this lot; the only MCP calls made while writing this document were read-only (`describe_table`,
`preview_records`) to check field shapes already documented below, per the task's own instruction. `tools/
test_local.sh` is green (XSD regeneration diff, ASCII/CSS-comment checks, compile, the full test suite
including the two new test classes this lot adds: `StepResetPlanTest`, and the step-reset extensions to
`WizardPageTest`).

## What it is

A "Clean this step and the following ones" button on wizard steps 3 (Landing), 4 (Staging), 5 (Master) and 6
(Matching), administrators only. A click opens, in place, a confirmation panel listing exactly what would be
removed; typing the project code and confirming runs the reset. Screen 7's own project-deletion screen
(`docs/PROJECT-DELETION.md`) is untouched — this is a narrower, in-place operation: the project itself, its
identity, and its chosen sources (`ProjectSource` rows) are never touched.

**Resetting a stage also resets every stage downstream of it** — a stage's generated objects are built FROM
the stage before it, so leaving a downstream stage in place after wiping an upstream one would just mean
regenerating on top of stale data. Pipeline order: `LANDING` < `STAGING` < `MASTER` < `MATCHING`. Resetting
`from=STAGING` cleans Staging, Master and Matching; `from=MATCHING` cleans only Matching.

## Files

- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/project/StepResetPlan.java` — the pure planning class (no
  EBX type anywhere), reusing `ProjectDeletionPlan.Facts`/`SourceFacts`/`MatchingFacts` as its input rather
  than duplicating them (`StepResetPlan.Facts` wraps a `ProjectDeletionPlan.Facts` plus one extra field, row
  counts already scoped to the chosen stage — see its own Javadoc for why counting itself is not done in the
  pure class). Unit-tested, `StepResetPlanTest`: scope per stage, execution order, every refusal rule, the
  `TARGET_EXISTS` carve-out, `stepNames` as a pure function of the stage.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/GeneratedObjectDeleter.java` — the four per-kind
  deletion mechanics (matching config, Connectors flow, dataspace, data model), extracted out of
  `ProjectDeleter` with NO behaviour change (a straight move) so `StepResetter` reuses the exact same code
  instead of a second implementation. `ProjectDeleter.java` was updated to delegate to it.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/StepResetter.java` — the EBX-facing class: `plan`
  gathers the live evidence into a `StepResetPlan.Facts` and returns it as JSON; `reset` re-gathers,
  validates, executes every `DELETE`-disposition item (stopping before touching a single row on the first
  failure, exactly like `ProjectDeleter.delete`), then one `Procedure` that deletes the in-scope rows (children
  before parents), clears the `Project`/`StagingEntity`/`Xref` fields that memorize the reset zones, moves
  every in-scope `PipelineStep` to `RESET`, and writes this call's own `STEP_RESET` step (`scope` = the
  stage, `status DONE`, `detail` = the plan JSON — an audit record, never itself reset by a later call, same
  posture as `MASTER_RESET`).
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/rest/FactoryResource.java` — `GET
  /projects/{code}/reset/plan?from=<stage>` and `POST /projects/{code}/reset?from=<stage>&confirm=<code>`,
  documented in the class's own route Javadoc right after `/delete`.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/wizard/WizardPage.java` — `Model.stepReset`
  (`StepResetView`, null = no panel open), `StepResetItem`, and `renderStepReset(pOut, pModel, pLocale,
  stage)` — called once per step (3/4/5/6), right after each step's own `mdmf-lead` paragraph, one line each,
  to keep the diff in `renderStep4` minimal (a neighbouring lot, `feature/wizard-staging-focus`, touches that
  method's own body). Reuses `.mdmf-btn`/`.mdmf-delete-item`/`.mdmf-notice-*`/`.mdmf-field` classes already in
  `factory.css` — no new CSS.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java` — `stepReset`
  instance field (same transient-state convention as `keyStudy`/`onboardDiagnosis`), the `STEP_RESET_PLAN`/
  `STEP_RESET_CONFIRM`/`STEP_RESET_CANCEL` action cases, and the two handler methods (`stepResetPlan`,
  `stepResetConfirm`) that call `StepResetter` and rebuild the `StepResetView` from its JSON.
- `ebx-mdm-factory/src/main/webapp/www/common/jscripts/factory/wizard.js` — two attribute readers in the
  existing `[data-mdmf-action]` click handler (`data-mdmf-reset-stage` → `mdmfResetStage`,
  `data-mdmf-confirm-input` → `mdmfResetConfirm`, the latter read from the confirm `<input>`'s current value
  right before submit, same idiom as `data-mdmf-pipeline-input`).
- Message bundles: every `wizard.stepReset.*` key in both `Messages_en.properties` and
  `Messages_fr.properties` (French written first-class, per this codebase's own convention). Every accented
  character is a `\uXXXX` escape, checked by `tools/test_local.sh`'s ASCII scan.
- `ebx-mdm-factory/src/test/java/com/on/ebx/factory/project/StepResetPlanTest.java` (new) and the step-reset
  additions to `ebx-mdm-factory/src/test/java/com/on/ebx/factory/wizard/WizardPageTest.java`.

## What each stage cleans, and why — corrections to the original brief

The brief (quoted in full in `StepResetPlan`'s own Javadoc) named a first cut at the row/step tables per
stage. Verified against the real code (grep on every `PipelineSteps.begin`/`isDone` call site, and the real
foreign keys in `tools/build_xsd.py`) before writing the executor — four corrections follow, each explained
in the class Javadoc of the code that makes it real:

1. **`Xref` rows are never DELETED**, contrary to the brief's literal "lignes MatchPlan, Xref" for the
   `MATCHING` stage. `Xref` is the landing→staging→master **lineage** record (`docs/MASTER-ZONE.md` §3.4) —
   deleting it would destroy the provenance of every record, matching or not, most of which have nothing to
   do with matching at all. Instead:
   - a `MATCHING`-only reset clears `Xref.goldenKey` alone (the master record and its provenance survive
     unchanged — only the "which golden group is this row in" fact is invalidated);
   - a reset that also reaches `MASTER` clears `masterTable`/`masterKey`/`goldenKey` together — the exact
     three fields `ebx.MasterReset` already clears for the same reason (the master record itself is gone).
   - Both are project-wide, not filtered to the entities being reset — the same scope `ebx.MasterReset`
     already uses for its own, narrower (`MASTER`-only) case.
2. **`MatchPlan` rows ARE deleted** (the brief's other half, taken literally) whenever `MATCHING` is in scope
   — which is always, since `MATCHING` is the most-downstream stage and every reset reaches it. A `MatchPlan`
   row is a matching-only proposal/decision (policy names, requirements, `autoMerge`), meaningless once the
   configuration it describes is gone.
3. **`CAPABILITY_APPLY`/`CAPABILITY_REMOVE` `PipelineStep` rows are scoped by the acted-on
   `CapabilitySuggestion` row's OWN id** (`ebx.CapabilityApplier`/`DerivationApplier`: `scope = suggestionId`,
   confirmed by grep — never a literal zone string). `StepResetPlan.stepNames` only names the step
   vocabulary; `StepResetter.gather` resolves which ROWS carry those names: for a `MASTER`-only reset (the
   `CapabilitySuggestion` rows survive), it reads the in-scope suggestion ids by `zone = MASTER` first; for a
   `STAGING`-or-deeper reset, every suggestion id of the project (every row is about to be deleted anyway).
4. **`TransferFeed` is included in the row set** (`MappingStep`→`AttributeMapping`→`TransferFeed`→
   `StagingAttribute`→`StagingEntity`, children before parents — `AttributeMapping.feed` and
   `TransferFeed.landingTable`/`.entity` are all foreign keys that must go before their targets). Noted in
   passing while verifying the deletion order: **`ebx.ProjectDeleter`'s own row set does NOT include
   `TransferFeed` at all** — a project deletion today leaves every `TransferFeed` row of a deleted project
   orphaned. This predates the target-mapping lot's own introduction of that table and is out of scope for
   this lot (flagged separately, not fixed here — `ebx.ProjectDeleter` is proven, deployed code, not touched
   by this branch).

## Per-stage table, as implemented (verified, not assumed)

Ordered downstream-first (the actual execution/deletion order); a stage's own row/step set is always a
superset of every stage after it (in pipeline terms, "after" = more downstream = included by a shallower
`from`).

| Stage | Generated objects (disposition `DELETE`, unless noted) | Rows deleted | Rows/fields modified | `PipelineStep` names → `RESET` |
|---|---|---|---|---|
| `MATCHING` | one `delete_matching_config` per promoted+configured master table | `MatchPlan` (of those entities) | `Xref.goldenKey` cleared (project-wide) | `MATCHING_SUGGEST_LLM`, `MATCHING_CONFIG`, `MATCHING_RUN`, `MATCHING_SURVIVORSHIP`, `MATCHING_SIMULATE_PREVIEW`, `GOLDEN_KEY_REFRESH` (the five entity-scoped ones, one row per promoted entity; `MATCHING_SUGGEST_LLM` is project-scoped) |
| `MASTER` | + master dataspace, + master DMA model — **EXCLUDED (never touched) for a `TARGET_EXISTS` project**, see below | + `TransferReject` where `stage = MASTER` | + `StagingEntity.targetTable` cleared; + `Project.masterDataspace`/`masterDataset` cleared | + `MASTER_MODEL`, `MASTER_DATASET`, `MASTER_LOAD`; + `CAPABILITY_APPLY`/`CAPABILITY_REMOVE` of every `CapabilitySuggestion` with `zone = MASTER` |
| `STAGING` | + staging dataspace, + staging DMA model | + `MappingStep`, `AttributeMapping`, `QualityRule`, `CapabilitySuggestion`, `TransferFeed`, `StagingAttribute`, `StagingEntity`; `TransferReject` widens to EVERY stage (both `STAGING` and `MASTER`) | + `Project.stagingDataspace`/`stagingDataset` cleared | + `STAGING_SUGGEST`, `STAGING_MODEL`, `STAGING_DATASET`, `STAGING_LOAD`, `QUALITY_SUGGEST`, `QUALITY_SUGGEST_LLM`, `CAPABILITY_SUGGEST_LLM`; + `CAPABILITY_APPLY`/`CAPABILITY_REMOVE` of EVERY `CapabilitySuggestion` of the project (every row is deleted, so every scope is in play) |
| `LANDING` | + one Connectors flow (`connectors_mapping_delete`) per source's landing tables, + the shared landing dataspace, + one landing model per source | + `LandingColumn`, `LandingRelation`, `LandingTable` | + `ProjectSource.landingModel`/`landingPublication`/`landingDataset`/`message` cleared, `status` reset to `SELECTED` (the `ProjectSource` ROW itself survives — the chosen sources are never revisited) | + `LANDING_MODEL`, `LANDING_DATASET`, `LANDING_FLOWS`, `LANDING_LOAD` (per source system) |

Never touched by any stage: the `Project` row itself, `ProjectSource` rows, `AgentRun`/`AgentStep` (trace and
cost, same rule as screen 7), `ReferenceBinding`/`FactorySettings` (instance-wide, no project link), and
every `PipelineStep` HISTORY row (moved to `RESET`, never deleted — `tools/build_xsd.py`'s own comment on
`PipelineStep.status`: *"a later reset invalidated - not deleted, so history stays, but `isDone` must treat
it as not-done again"*, written for `MASTER_RESET` and equally true here). `PipelineSteps.isDone` already
queries `status = 'DONE'` exactly, so a row moved to `RESET` is automatically read as not-done — no change
needed to that method for this lot.

## Guardrails — deliberately STRICTER than screen 7's own deletion screen

Same three checks as `ProjectDeletionPlan` (delivered / wrong computed name / no `PipelineStep` creation
evidence), but a DIFFERENT consequence, per the product owner's own framing (quoted in `StepResetPlan`'s Javadoc):

- **A delivered project** (`delivery.OriginRule.isDelivered`) → the whole reset is refused outright, same
  posture as every `suggest*`/generation route.
- **A dataspace/model whose stored name is not what this factory would itself compute, or with no
  `PipelineStep` evidence the factory created it** → **the whole reset is REFUSED, not just that one item
  skipped.** This is the deliberate inversion from screen 7: there, an ineligible item just means its
  checkbox is unavailable and the rest proceeds; here, a stage cannot be called "clean" while a generated
  object nobody can prove the factory owns is left in place, so the entire call is blocked until the
  ambiguity is resolved by hand.
- **The master zone (dataspace + model) of a `TARGET_EXISTS` project** → the ONE deliberate carve-out:
  `EXCLUDED`, never touched, and this does **not** block the rest of the reset — the matching configuration
  on that table and every factory row are still cleaned (the product owner: *"zone MDM d'un projet TARGET_EXISTS ->
  jamais supprimee, mais les lignes de la factory et la config matching le sont"*). `StepResetPlan.build`
  deliberately does NOT propagate this exclusion to the matching-config item the way `ProjectDeletionPlan`
  propagates an ineligible master dataspace to its own matching items — see `StepResetPlan`'s own Javadoc on
  `MATCHING_CONFIG` for exactly why the two classes diverge here.
- **`confirm`** must equal the project code exactly, same ceremony as `/master/reset` and `/delete`. A
  build-time refusal always wins over a correct confirm — `StepResetPlan.validate` checks `plan.refusal`
  first (tested, `validateRefusalWinsOverConfirm`).

## The two routes

- `GET /projects/{code}/reset/plan?from=<stage>` — read-only, administrators only (preflights `MATCH_MERGE`
  only when the project has a master dataspace at all, since `MATCHING` is always in scope regardless of
  `from`). Returns the plan: items with their `disposition` (`DELETE`/`EXCLUDED`/`REFUSED`) and, if not
  `DELETE`, their `reason`; the row counts that would be deleted; the `PipelineStep` names that would move to
  `RESET`; and, if the whole call would be refused, `refusal`.
- `POST /projects/{code}/reset?from=<stage>&confirm=<code>` — administrators only, same posture as
  `/master/reset` and `/delete` (acts with the factory's one agent account on the MCP servers and performs
  irreversible repository-level deletions). Preflights only the servers this particular stage could reach
  (`MATCH_MERGE` whenever a master dataspace exists; `DATA_MODEL` for any stage but a bare `MATCHING`;
  `CONNECTORS` only for `LANDING`).

## The wizard screen

One button per step (3/4/5/6), `data-mdmf-action="STEP_RESET_PLAN"` + `data-mdmf-reset-stage="<STAGE>"`,
administrators only (`WizardPage.renderStepReset`'s own guard — re-checked server-side in
`ProjectWizardUserService.stepResetPlan`/`stepResetConfirm`, a client-side check never being trusted alone).
A click opens, on the SAME step, a confirmation block: the plan's items (disposition-coloured, same
`.mdmf-delete-item`/`.mdmf-chip` classes as `ProjectDeletePage`), the row-count table, the `PipelineStep`
names that would be reset, and — only when the plan is not refused — a "type the project code" field and a
"Confirm cleanup" button. A refused plan shows the refusal text and only a "Close" button. Once confirmed,
the panel switches to a short result notice (`{N} pipeline step(s) reset, {M} row(s) deleted.`) instead of
re-showing the plan.

State is entirely on the `ProjectWizardUserService` instance (`stepReset` field), same convention as
`keyStudy`/`onboardDiagnosis` — never persisted, rebuilt fresh by `STEP_RESET_PLAN` (or a failed
`STEP_RESET_CONFIRM`, which re-reads the plan so the panel still shows exactly what is blocking it) and
cleared by `STEP_RESET_CANCEL`.

## What was not verified

Everything: this lot was written, unit-tested, and never run against a live EBX instance (no MCP write call,
no install, no Tomcat restart — see the note at the top). In particular, unverified assumptions carried over
unchanged from the proven code this reuses (`ProjectDeleter`/`MasterReset`/`GeneratedObjectDeleter`'s own
per-kind deletion mechanics) are exactly as documented in `docs/PROJECT-DELETION.md`/`docs/MASTER-ZONE.md` —
nothing new is assumed beyond what those two documents already flag. Two things genuinely specific to this
lot and worth a live check before trusting it on a real project:

- The row-deletion order (`MappingStep` → `AttributeMapping` → `QualityRule` → `CapabilitySuggestion` →
  `MatchPlan` → `TransferReject` → `TransferFeed` → `StagingAttribute` → `StagingEntity` → …) was reasoned
  from `tools/build_xsd.py`'s own foreign keys, not measured against a real `doDelete` call — `ebx.
  ProjectDeleter`'s own proven order actually deletes `TransferReject` AFTER `StagingEntity` (the opposite of
  what a strict FK topological sort would demand) and is described as "seen running" successfully in
  `docs/PROJECT-DELETION.md`, which suggests EBX tolerates a transiently dangling mandatory reference inside
  one `Procedure` — this lot's own order was still built topologically-correct out of caution, not because a
  live test showed the looser order fails.
- The wizard's confirmation panel and its wiring in `wizard.js` were checked by reading `WizardPage.render`'s
  own HTML output in `WizardPageTest` (string assertions on the exact markup), never in a browser.
