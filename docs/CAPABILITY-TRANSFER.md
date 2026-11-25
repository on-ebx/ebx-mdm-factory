# Script-backed quality checks now gate the staging -> master transfer

Written 2026-09-23, branch `feature/quality-script-rule`, answering the product owner's own question on project
Personnes: "les regles de validation scriptees ne devraient pas s'appliquer egalement au staging, pour ne
pas laisser arriver en MDM des donnees erronees ?" ("shouldn't scripted validation rules apply to staging
too, so bad data doesn't reach master?").

## 1. The gap this closes

Before this lot, the module had two disjoint mechanisms:

- `QualityRule` (kind `PATTERN`/`REFERENCE`/`CHECK_DIGIT`/`UNIQUE`/`MANDATORY`, severity `MODEL_CONSTRAINT`/
  `REJECT`/`WARN`) is evaluated per row by `quality.QualityRuleCheck`, called from both `ebx.StagingLoader`
  (landing -> staging) and `ebx.MasterLoader` (staging -> master). `REJECT` is the only thing that has ever
  stopped a row before master. A regex cannot express "a valid phone number in 12 national plans" - on
  Personnes the deterministic/LLM quality passes proposed nothing at all for `phone`.
- `CapabilitySuggestion` (nature `SCRIPT_CONSTRAINT`): a published EBX Script library function
  (`Fasttrack.lib.phone.isValid`, `Fasttrack.lib.validator.email`, `.date`, ...) positioned as a
  `constraintOnNode` on a zone's data model, applied by `ebx.CapabilityApplier`. It flags a record in EBX's
  own validation report. `ebx.MasterLoader` never read that report, so an invalid phone still reached master.

This lot gives a script-backed check ONE definition (`CapabilitySuggestion`) with TWO enforcement points: it
still flags in EBX's own validation report (unchanged), and a new `transferPolicy` field can also gate the
staging -> master transfer.

## 2. Design chosen - A, measured

Two candidate designs were on the table (task brief):

- **A**: apply the constraint on the STAGING model (already supported: `CapabilitySuggestion.zone`), then have
  `ebx.MasterLoader` read each staging record's OWN EBX validation report and treat an item raised by THAT
  constraint as a transfer decision. No script is ever called from Java.
- **B**: generate and publish, per rule, a `restService` wrapper and call it over HTTP per row/batch from
  `quality.QualityRuleCheck`.

**A was chosen, and it did not need re-deriving from scratch - the apply lot (`docs/CAPABILITY-APPLY.md` sec
6/6b) had already measured the exact facts needed:**

- `com.onwbp.adaptation.Adaptation#getValidationReport()` (no-argument, PER RECORD) is real and reachable -
  `ebx.CapabilityApplier.measureEffect` already reads it, per record, after `fix/measure-observability` found
  the table-wide overload can miss record-level items.
- A `ValidationReportItem` identifies its subject's path (`getSubjectForAdaptation().getPathInAdaptation()`,
  record-relative) and severity (`getSeverity().toParsableString()`, `"WARNING"`/`"ERROR"`), but NOT which
  constraint raised it directly - no name/id is exposed on `ValidationReportItem`/`SchemaFacet`. Attribution is
  done by matching path + severity + the constraint's own literal message, extracted from its stored
  `scriptBody` (`capability.ConstraintBodyBuilder.extractReportedMessage`) - exact match when the whole
  `msg.addWarning`/`addError` argument is one literal, prefix match for a concatenation, and a documented
  "approximate" fallback (path+severity alone) when nothing is extractable. This is EXACTLY the same
  `capability.MeasurementMatch` gate `ebx.CapabilityApplier`'s own "measured effect" pass already uses to
  count a validation-report item against one applied constraint - reused as-is, not reimplemented.

So design A costs nothing new on the EBX side: the validation engine already evaluates an APPLIED constraint
every time a staging record is saved/validated, and the exact matching logic to attribute an item to one
constraint already existed and was already tested (`MeasurementMatchTest`). Design B would have meant a new,
permanent, published `restService` per rule (CLAUDE.md sec 3: "never publish a throwaway script... the URL is
permanent") called once per row at transfer time - slower, and a maintenance liability neither the apply lot
nor this one needed to accept.

## 3. What was built

- **`tools/build_xsd.py`**: `CapabilitySuggestion.transferPolicy` (enum `CAPABILITY_TRANSFER_POLICY`, values
  `WARN`/`REJECT` only - blank is the third, unenumerated state meaning "unenforced", the same convention
  `CapabilitySuggestion.severity` already uses for "meaningless for this row"), plus the transfer-time
  execution counters `lastLoadChecked`/`lastLoadFailing`/`lastLoadExamples`/`lastLoadCheckedAt` - the exact
  same shape `QualityRule` already carries. Additive only; `MdmFactory.xsd` regenerated.
- **`quality.ScriptConstraintCheck`** (pure, no EBX class): the sibling of `quality.QualityRuleCheck` for
  script-backed checks. `Rule` carries `expectedPath`/`expectedSeverityLabel`/`attribution`/`transferPolicy`/
  `reasonLabel`; `firingMessage(Rule, List<ReportItemView>)` walks a record's already-reduced validation items
  and returns the first matching one's rendered message, or `null`. `expectedPathFor`/`expectedSeverityLabelFor`
  are the pure derivations from `constraintScope`/`appliedElementPath`/`severity` a caller needs before
  building a `Rule`. Unit-tested (`ScriptConstraintCheckTest`) against every gate: wrong path, wrong severity,
  exact/prefix/approximate attribution, first-match-wins, `isRejectPolicy`.
- **`ebx.MasterLoader`**: `readScriptConstraintRules(project, entity)` reads every APPLIED, zone-STAGING,
  non-blank-`transferPolicy` `CapabilitySuggestion` of the entity (once per entity, not per row) and builds a
  `ScriptConstraintCheck.Rule` for each. `planRow` reads the staging record's own `getValidationReport()`
  (`readReportItems`, severity WARNING-or-above only - guarded so a report is never read at all for an entity
  with no active rule) and, for each active rule, records the checked/failing counters
  (`recordScriptRuleCheck`) and - for a `REJECT`-policy rule that fires - adds a reason to the SAME `reasons`
  list an unresolved foreign key already funnels into, so the row lands in `TransferReject` (stage `MASTER`)
  the ordinary way, with a reason naming the library/function and the attribute (e.g. `"Fasttrack.lib.phone
  .isValid on phone: Phone number is invalid. Format your number according to the international E.164
  standard."`). `writeScriptRuleCounters` writes the run-wide counters back onto every enforced
  `CapabilitySuggestion` row once, at the very end of `MasterLoader.load` - best-effort, like every other
  bookkeeping write in this class.
- **`capability.CapabilityLlmPass`**: `suggest_script_constraint`'s schema gained an optional `transferPolicy`
  argument, enum-limited to `"WARN"` - the tool refuses anything else, including `"REJECT"`, with the same "a
  person raises it" wording `quality.QualityLlmPass` already uses for `QualityRule.severity`.
  `ProposedScriptConstraint.transferPolicy` is clamped to `null` for a non-STAGING zone in its own constructor
  (defence in depth, even though the schema already scopes it to STAGING suggestions in the prompt).
  `ebx.CapabilityLlmSuggester` writes it only when non-null.
- **Wizard**: a `SCRIPT_CONSTRAINT` line of zone `STAGING` now shows a blank/`WARN`/`REJECT` select next to its
  `lastLoadChecked`/`lastLoadFailing` evidence (`WizardPage.renderCapabilityTransferPolicy`,
  `WizardData.setCapabilityTransferPolicy`/`fillCapabilityExtras`), the same `mdmf-severity-select`-style
  pattern the `QualityRule.severity` control (2026-09-23, same day) already established - wizard-only, the
  Proposal review screen is deliberately untouched, same precedent. Unlike that control, this one is NEVER
  disabled once `APPLIED`: `transferPolicy` is read live at every transfer, never baked into the published
  script body, so raising it after the constraint is already applied is the normal path, not an edge case - no
  restart, no re-publish, no re-apply needed to take effect on the next transfer.

## 4. Exactly where a REJECT now stops a row

Staging -> master ONLY (`ebx.MasterLoader.planRow`), never landing -> staging. A landing row is not yet a
staging `Adaptation` when `ebx.StagingLoader` runs it through the cleaning pipeline - it has no validation
report of its own to read, because it does not exist as a staging record until that very write succeeds. A
post-write pass (write the batch, then re-read each just-written record's report, then delete the offending
ones back out of staging) was considered and deliberately NOT built: it would need to undo an EBX write it
just committed, re-derive `Xref`/`TransferReject` bookkeeping for a row that technically WAS staged for a
moment, and duplicate `MasterLoader`'s own reject machinery one zone earlier for a benefit questionable to
the product owner's own restated goal (keeping bad data out of MASTER, not staging - staging is allowed to hold rows a
person can still review and fix, per `QualityRule.severity`'s own established WARN/REJECT split, which
already only ever gates AT the staging -> master transfer for exactly this reason). If this residual gap
matters later, it is a new lot, not a corner cut here silently.

## 5. What this deliberately does not cover

- No enforcement on the landing -> staging leg (section 4).
- A `TABLE`-scope constraint is handled (`ScriptConstraintCheck.expectedPathFor` falls back to `"."`, the
  record itself) but none exist live today - `fix/node-scope-multi-attribute` already migrated every
  `SCRIPT_CONSTRAINT` to `NODE` scope; the fallback is defensive, not exercised by real data.
- An "approximate" attribution (no literal extractable from `scriptBody` - a variable or a built message) is
  enforced the same as an exact one (path+severity alone decides), inheriting the exact same caveat
  `ebx.CapabilityApplier`'s own measured-effect pass already documents and accepts.
- Nothing here was run against a live transfer - no `CapabilitySuggestion` row on this instance is `APPLIED`
  with zone `STAGING` yet (the four Personnes rows read live, ids 10-14, are all `PROPOSED`, zone `MASTER`) and
  no `Personnes_Staging` dataspace exists yet (`Generate the staging` has not been run for that project). The
  manual checklist below is what proves this end to end.

## 6. Manual checklist for the product owner, project Personnes

1. **Generate and load the staging** for Personnes (wizard step 4) if not already done - `Personnes_Staging`
   must exist before anything below is reachable.
2. On the Sample entity's `phone` attribute, re-propose (or hand-edit) a `SCRIPT_CONSTRAINT` suggestion with
   `zone = STAGING` (today's rows 11/12 are `zone = MASTER` - the LLM pass, run again, can now also set
   `transferPolicy = WARN` for a STAGING suggestion; a MASTER-zone row has no transfer-policy control at all).
3. Accept it - acceptance applies it directly (unchanged apply-lot behaviour): the constraint is declared as a
   `ConstraintOnNode` on the STAGING model and published.
4. In the wizard's proposal line for that constraint, set the transfer policy to **REJECT** and save (no
   restart, no re-apply needed).
5. Run **Load the staging** again (re-runs `StagingLoader`, refreshing the staging records' own validation
   reports against the just-applied constraint) if the sample rows were loaded before the constraint existed,
   then run **Load the master**.
6. Expected figures: the sample `phone` values are deliberately mixed formats - E.164 (`+33612345601`),
   national (`0612345601`), dotted (`06.12.34.56.01`), spaced-with-parenthesis (`+33 (0)6 12 34 56 01`), plus a
   few foreign numbers. `Fasttrack.lib.phone.isValid` normalises before validating, so MOST of these should
   PASS - expect only a handful of genuinely malformed/foreign-plan numbers to fail. A failing row should:
   - appear in `TransferReject` (stage `MASTER`) with a reason starting `Fasttrack.lib.phone.isValid on
     phone: ...` and never reach the master `Sample`/`Contact` table;
   - be reflected in `CapabilitySuggestion.lastLoadChecked`/`lastLoadFailing` for that row (visible on the
     wizard's proposal line, next to the transfer-policy control) and in `lastLoadExamples`
     (`sourceSystem|sourceId` of up to 10 failing rows).
   A passing row (a normalised-but-valid phone) should reach master exactly as before - REJECT only removes
   the genuinely invalid ones, it does not change how a valid value is transferred.
7. To see WARN instead: set the same control back to WARN and reload - the same failing rows should now reach
   master, with the same counters recorded, and no `TransferReject` entries for this constraint.
