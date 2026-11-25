# Lot A - staging proposal content fixes (test B2C, 2026-10-17)

Branch `fix/staging-proposal-quality` (from `main` 4a8806c0), 2026-10-17. Source: `test-staging-b2c-2026-10-17/COMPTE-RENDU.md`
(project TiersB2CRetestLanding, defects 3, 8 and 10). Coded, tests green, **NOT deployed, NOT seen running**. Parallel lots: D
(normalisation pass, transforms, dates, phone), B (loader / rejects), C (wizard step 4, matrix UI).

## 1. Computed fields accepted but empty (defect 3)

**Root cause (read live, read-only).** `CapabilitySuggestion` 201 (`Customer.phoneE164`, `Fasttrack.lib.phone.toE164WithDefault`) and 202
(`Customer.personNameKey`, `Fasttrack.lib.names.sortedKeyOf`) are `status = ACCEPTED` with `appliedAt`, `appliedModel`, `appliedElementPath`
and `applyMessage` all empty: **never applied**. Accepting a script derivation and applying it are two moments (`docs/SCRIPT-DERIVATION.md`
sec 11); the apply (declare the two table triggers on the staging model, author them, publish) is the "To apply" list of step 4, and the
one-click "Build and load with what is accepted" (`StagingLoadRun`) ran neither it nor anything that replaces it. The loader then did what
lot M5 says: an unapplied script derivation is never copied from its source and is held EMPTY (`NullFillRule`), on every row. `emailKey` and
`addressKeys` were filled because they are not script derivations: Java computes them at load (`DerivedFieldRule` fold key, `ChildKeysPass`).
So: no wrong field name, no missing trigger generation - the triggers were simply never generated for these two, because nobody applied them.

**Fix.**
- `ebx.DerivationAutoApply` (new), called at the top of `StagingLoader.loadInternal` (so every path - the queued job, the workflow bean, the
  REST route - gets it): every `ACCEPTED`, not-applied `SCRIPT_DERIVATION` of the project is applied through the same
  `DerivationApplier.apply` as the button. Selection is the pure `ScriptDerivationGate.idsToApply(entries, acceptedIds)`. A refusal or a
  failure (a parameter still `toBeProvided`, the model not built) never fails the load: the attribute stays held empty as before, and one
  note per attribute says why (load message, step detail `derivationsAutoApplied`, listener note).
- The apply's own backfill cannot claim the STAGING resource the load holds (it answers SKIPPED); this matters only for rows already in the
  table, and the first load finds it empty - the triggers compute the value as rows are created. The post-load check below reports the
  other case.
- **Post-load check** (`staging.ComputedFieldFill`, pure; `StagingLoader.collectEmptyComputed`): for each loaded entity, every computed field
  (folded / validity key, script derivation - applied or not) with no value on ANY row is reported as a sentence on the entity's load
  result (`entities[].computedFieldsEmpty`, top-level `computedFieldsEmpty`, the step message and the listener note), e.g.
  "Customer.phoneE164: computed field empty on all 394 row(s) - its script derivation is not applied and could not be applied by this load".
  Cost: one row count per entity and one "any row with a value" request per field (stops at the first match).

Tests: `ScriptDerivationGateTest` (selection), `ComputedFieldFillTest` (report rule and wording).

## 2. Modelling (defect 8)

Measured on the test: `StoreTransaction` existed and its facts (`storePurchaseDate`, `storeCode`, `purchaseAmount`) were also on `Customer`;
`Customer` kept `city` / `streetAddress` (0 source links) and `postalCode` (1 source) while `CustomerAddress` holds the address; every ticket
became one `Customer` without a word.

**Prompt** (`StagingLlmPass#systemPrompt`, rules 8-10, shared by every project, domain examples only in the existing two): a fact is carried once
(a parent does not get the fields a related entity carries from the same columns; with an address child the parent keeps no address field);
every declared attribute is fed by at least one rule; `businessKey` only on the attributes that identify a record in its own source, and a
source that is not one row per record of the entity says so in the rationale of its feed.

**Deterministic post-check** (`staging.ProposalChecks`, pure, `ProposalChecksTest`; run by `StagingLlmPass.Session#reviewModelling` between the
end of the model's run and the write, so it works on the run's own proposals - nothing is read back). Domain-free: it reads which landing column
feeds which attribute, business-key flags, and which entities a table feeds - never a name. Three findings:

| Finding | Rule | Effect |
|---|---|---|
| `DUPLICATES_RELATED` | every source link of an attribute reads a column that another entity of the run also reads from the same table, and that entity is a descendant of this one, or - when unrelated - fed from fewer landing tables (the narrower concept owns the fact). Business keys, mandatory fields and the link to the parent are exempt; an ancestor never qualifies (both sides of one duplicate are never flagged). | attribute (and its rules) confidence capped at 40; sentence "to check ... suggestion: reject it" first in the rationale |
| `NO_SOURCE` | an attribute of an entity that has feeds, read by no rule of any of them (link to parent exempt) | confidence capped at 20; sentence "no source column feeds this field ... suggestion: reject it" |
| `ONE_ROW_PER_RECORD` | a ROOT feed of an entity that has business-key attributes but maps none of them: each row of that table is its own record | sentence added to the FEED's rationale: "One row of {table} gives one {entity} record (this source holds no identifier of it): duplicates will be merged at matching." No confidence change |

"Needs you": the matrix's bulk accept takes a proposal at or above its threshold (60 by default, `MatrixCompute.DEFAULT_BULK_THRESHOLD`; the
wizard's bulk 80), so a capped field is outside the sweep and in the "needs you" count with no change to the matrix code (lot C's). There is no
"proposed reject" state on a `StagingAttribute`, so the proposal is carried by the low confidence and the first sentence of the rationale; the
person still decides. Findings are also listed on the run result (`modellingFindings`) and in the step message. Sentences come from the
bundle (`stagingProposal.check.*`, en/fr, one block at the end of `Messages_*.properties`).

Not done: no per-source note keyed on a "ticket" noun (the sentence names the landing table); if the model sets no `businessKey` at all on an
entity, `ONE_ROW_PER_RECORD` cannot fire (the prompt rule 10 asks it to, and to say it in the feed rationale otherwise).

## 3. Language (defect 10, proposal part)

`StagingLlmPass.systemPrompt(Locale)` appends a LANGUAGE paragraph (not for English): entity and attribute LABELS, rationales, notes and the
final summary in the person's language; entity / attribute names, variants, pipelines, landing names stay English identifiers.
`StagingLlmSuggester.suggest(..., Locale)` takes the launcher's UI locale: `StagingProposalRun`, `StagingSuggestLlmRun`, `SuggestRulesRun` pass the
locale they already capture at enqueue time (the job session is the system user's); the synchronous REST route uses the session's. Existing
English rows are untouched. The check sentences (item 2) are written in the same locale. Left English (not this lot): the technical
`sourceSystem` / `sourceId` attribute labels written by `StagingRules`, the load notes of `StagingLoader`, the panel texts (lot C).

## Files

Item 1: `ebx/DerivationAutoApply`, `ebx/StagingLoader`, `ebx/CapabilityDispatcher` (one method package-visible), `staging/ScriptDerivationGate`,
`staging/ComputedFieldFill`, tests `ScriptDerivationGateTest`, `ComputedFieldFillTest`.
Items 2-3: `staging/ProposalChecks`, `staging/StagingLlmPass`, `ebx/StagingLlmSuggester`, `ebx/StagingProposalRun`, `ebx/StagingSuggestLlmRun`,
`ebx/SuggestRulesRun`, `Messages_en/fr.properties`, tests `ProposalChecksTest`, `StagingLlmPassTest`. No XSD change, no restart for the XSD; the
Java classes need the usual Tomcat restart at deployment.

---

# Lot G1 - the backfill of an applied derivation, stale rows, parameter asked where the build is asked (2026-10-17)

Branch `fix/derivation-apply-backfill` (from `main` 4143edde). Coded, tests green, **NOT deployed, NOT seen running**: the live instance was read-only for this lot, so the
fill of the 534 rows was exercised only against a fake table (`DerivationRepairSimulationTest`), never measured on EBX.

## The live defect, read (read-only)

TiersB2CRetestFinal, `CapabilitySuggestion` 226 (`Client.mobileE164`, `Fasttrack.lib.phone.toE164WithDefault`, binding `{"value":"mobile","defaultCountry":{"literal":"FR"}}`
after the person entered FR): `status APPLIED`, `appliedAt 2026-10-17T18:42:50`, `applyMessage` "Applied, but the rows already in the staging table were not recomputed (skipped:
Another run is using the staging of this project (CAPABILITY_APPLY_STAGING, started by admin ...)). Run POST /projects/{code}/capabilities/1696/backfill." Three defects, one
chain:

1. **The apply job refused its own backfill.** `CapabilityApplyRun` (kind `CAPABILITY_APPLY_STAGING`) holds the project's STAGING resource and runs `DerivationApplier.apply` on
   its pool thread; the apply ends with `DerivationBackfillPass.runAfterApply`, which called `FactoryJobs.claim(STAGING)` for itself, got `null` (the job's own claim) and answered
   SKIPPED. The lot A paragraph above ("the apply's own backfill cannot claim the STAGING resource the load holds ... only matters for rows already in the table") was wrong in
   this case: it matters whenever a table has rows. The same refusal happened for the apply that `DerivationAutoApply` runs inside a staging load.
2. **A reload could not repair it.** The loader never writes an applied derivation and, with `_sourceHash` equal and every planned value equal (compare-before-write, lots V3-S /
   V3-S2), skips the row: no write, so no `beforeModify`. The column stayed empty on all 534 rows and the UI offered nothing.
3. **The parameter was asked in the wrong place.** The matrix accepted the derivation without a word; the build went through (an unapplied derivation is held empty, lot M5); step 4
   then read "The staging is built and loaded"; the only input for `defaultCountry` was in the bottom "To apply" list.

Also found on the way: `DerivationApplier` passed the PipelineStep id (1696) instead of the suggestion id to `runAfterApply`, so the backfill answer and the `applyMessage` hint
named the wrong id ("POST .../capabilities/1696/backfill" for capability 226). Fixed.

## 1. How the backfill works now

- `DerivationBackfillPass.claimAndRun` decides with `EngineClaim.decide` (`claimDecision`): a STAGING claim held by **the calling thread** (the apply job, the staging load that
  auto-applies, `EngineClaim.enter`'s own claim) is **re-entered** - no second claim, nothing released at the end, the holder's handle is the cancellation flag; a free resource is
  claimed as before; a claim held by another thread still answers SKIPPED with the holder's message. Test: `DerivationBackfillClaimTest` (the apply job's kind
  `CAPABILITY_APPLY_STAGING` re-entered on its own thread, refused from another).
- **How a row is recomputed (unchanged, chosen again).** The pass writes the fixed marker `__recompute__` into the DERIVED field of the row (`doModifyContent`, only that field,
  triggers ACTIVE) and the published `beforeModify` trigger overwrites it in the same transaction because the derived field differs between old and new record
  (`differs_string(old derived, new derived)`; measured 2026-10-04: a value forced by hand is overwritten). The alternatives were rejected: "touch the source field" needs a
  different value to write and then restore it (two writes, history rows, the Match and Merge trigger twice); "compute in Java" would duplicate the library, against the one-definition
  rule of `docs/SCRIPT-DERIVATION.md`. The marker is never stored: the first row is written alone and read back inside its own transaction, `TRIGGER_NOT_LIVE` ends the pass with
  nothing changed otherwise. Limitation (unchanged): a derived field that is not text takes `null` instead and a row already empty cannot be provoked that way
  (`skippedNullNonString`, reported).
- Not measured here: that the marker really is replaced on a live table by this very code path after the claim fix (it was on `Vol100k_Staging`, 2026-10-04, and the guard stops the
  pass at the first row otherwise).

## 2. A reload does not skip a stale row (`staging.DerivationStaleness`, pure)

`StagingLoader.loadInternal` now runs `DerivationAutoApply.repairStale` right after `applyAccepted` and before the first landing row is read (so before the read index of a scope
all load is built). For every **applied** derivation of the project (not one marked "parameters changed": its live triggers carry the old parameter - the load only notes "apply it
again"):

| Question (cheap first) | Answer | What is recomputed |
|---|---|---|
| table has no row, or `appliedAt` unknown | `NONE` | nothing |
| `AdaptationTable.getLastModificationDate()` is not after `appliedAt` (nothing written since the apply: the live case) | `ALL_ROWS` | every row with an input or a value |
| otherwise | `EMPTY_DERIVED` | the rows matching `osd:is-null(./derived) and (osd:is-not-null(./src1) or ...)` whose own `Adaptation.getTimeOfLastModification()` is not after `appliedAt` |

The per-row time test is what keeps a value the library legitimately refuses (an invalid phone gives null) from being rewritten at every load: the repair writes such a row once,
it is then newer than `appliedAt`. The writes are the backfill pass of section 1 (same marker, same guard, same batches). The result is a note per derivation ("Client.mobileE164: 534
existing row(s) recomputed - the derivation was applied after they were written and nothing had been written to the table since"), in the load message, the pane's notes and the
step detail `derivationsRepaired`; a repair that could not run (`SKIPPED`, `TRIGGER_NOT_LIVE`, `FAILED`) is a note, never a failed load. Cost in the common case: one row count and
one predicate probe per applied derivation. **Known gap:** a FILLED but outdated value on a table that was partly rewritten since the apply is not found (it would need the write time
of every row); `POST .../apply` on the APPLIED row (re-apply, ends with a full backfill) recomputes everything.

Tests: `DerivationStalenessTest` (decision, row rule, predicate, wording), `DerivationRepairSimulationTest` (the 534-row case against a fake table with a trigger: every row the
library accepts is filled, the next load finds nothing stale, a table without live triggers stops at the guard).

## 3. A parameter is asked where the build is asked (`wizard.DerivationParamGate`, pure)

- **Step 4 primary card.** While an ACCEPTED derivation of the "To apply" list still has a `toBeProvided` parameter and the step is in `REVIEW`, `BUILD` or `CONTINUE`, the card
  (next to "Build and load" / "Continue to the master") carries a block `mdmf-primary-params`: title "A derivation needs a value only you can give", one input per parameter and ONE
  "Save parameters" per derivation - the same `DERIVATION_SET_PARAMS` action and `wizard.js` code as the bottom list (the box ids differ: `mdmf-params-top-<id>`). The build is not
  held back; the block says the attribute stays empty until the derivation is applied and that the next build applies it (`DerivationAutoApply` applies every accepted derivation whose
  parameters are settled, and its backfill now works).
- **The staging is never called done without it.** In `CONTINUE` the sentence under the card is `wizard.step4.primary.next.paramWaiting.*` ("The staging is built and loaded, except 1
  derived attribute: it waits for the parameter defaultCountry ...") instead of "The staging is built and loaded"; in `REVIEW`/`BUILD` an extra line says the same.
  `DerivationParamGate.continueTextKey` puts a waiting parameter before every other wording.
- **Said before the accept.** A still-to-decide derivation whose binding has a `toBeProvided` parameter shows a "Needs a parameter" chip and a sentence in step 4's list
  (`StillToDecide` now fills `parameters` for PROPOSED derivations); the **matrix** accept of a computed field answers with `mappingMatrix.notice.decidedNeedsParam` instead of "Decision
  recorded" when the field's derivation needs a parameter (`ScriptDerivations.parametersToProvide`). This touches only the notice of `MappingMatrixUserService#decideAttributeWithItsDerivation`
  - not the cards, not the accept/apply gating (lot G2 owns the card text).
- The load note of an unapplied derivation that waits for a parameter now names it ("waits for the parameter(s) [defaultCountry]: left empty - enter them in step 4 ..."), and
  `DerivationAutoApply` no longer tries (and notes a refusal for) such a derivation. The saved-parameters notice says where to apply it.
- Messages: one block "derivation apply (2026-10-17)" at the end of `Messages_en/fr.properties`; the existing `wizard.notice.derivationParamsSaved` text was reworded in place.

## Files

`staging/DerivationStaleness` (new), `staging/ScriptDerivationGate` (Entry: `waitingParameters`, `appliedAt`), `ebx/DerivationBackfillPass` (re-entry, repair mode),
`ebx/DerivationAutoApply` (`repairStale`), `ebx/DerivationApplier` (suggestion id), `ebx/ScriptDerivations`, `ebx/StagingLoader`, `ebx/ui/MappingMatrixUserService` (notice only),
`wizard/DerivationParamGate` (new), `wizard/WizardPage`, `wizard/StillToDecide`, `wizard.css`, `Messages_en/fr.properties`; tests `DerivationStalenessTest`,
`DerivationRepairSimulationTest`, `DerivationBackfillClaimTest`, `DerivationParamGateTest`, `ScriptDerivationGateTest`, `WizardPageTest`. **No XSD change**; the Java classes need the usual
Tomcat restart at deployment (nothing to do for the data already on the instance: the next "Build and load" of TiersB2CRetestFinal repairs `Client.mobileE164` through the new path).
