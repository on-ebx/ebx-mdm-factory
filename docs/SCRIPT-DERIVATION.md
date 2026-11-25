# Script-backed derivation: a published library function computes a NEW attribute

Written 2026-09-23, branch `feature/script-backed-derivation`, answering the product owner's own finding on project
Personnes: the language-model matching pass proposed "Phone identical" for entity Sample, but `phone` is
written `+33612345601` (E.164) by SALESFORCE and `0612345601` / `06.12.34.56.01` / `+33 (0)6 12 34 56 01`
(national, various punctuations) by SAP - no cleaning step normalises a phone number (`tools/build_xsd.py`'s
own `MAPPING_STEP_KIND` has nothing phone-shaped) and no Match and Merge algorithm is phone-aware (exact,
jarowinkler, levenshtein, beider-morse, soundex, range, operator, hybridFuzzy, `*FullText`). The most
discriminating signal of a person registry was unusable for cross-system matching. A published EBX Script
library, `Fasttrack.lib.phone` (idePath `library/Fasttrack/lib/phone`), already normalises to E.164 for 12
national plans and validates. This lot gives that normalisation a home in the Factory: **one definition** (the
library), used by the Factory as both a validation constraint (already possible, `SCRIPT_CONSTRAINT`,
`docs/CAPABILITY-SUGGESTIONS.md`/`docs/CAPABILITY-APPLY.md`) and now also a **derivation** - a new attribute
whose value the library computes.

> **Lot T1, 2026-10-04: the computed function field described in sections 1-3 and 6-9 is ABANDONED.** The derived attribute is now an
> ordinary STORED field fed by two EBX-Script table triggers (`beforeCreate`, `beforeModify`) - read **section 10** first. Sections 1-9 are kept
> as the history of the decision (and of what the loader does, which has not changed); every statement below that says the value is "computed
> on read" or that a `functionField` is declared describes the original design only.

Nothing in this lot has run against a live EBX instance - the task brief's own instruction. Every MCP fact
below comes from **read-only** calls (`list_operations`, `get_syntax_guide`) against the live scripting/
data-model servers, and from `javap` against the real `ebx-lib.jar` on this machine; no `execute_operation`,
`update_script` or `publish_data_model` call was ever made by this lot itself.

---

## 1. The design question, settled by measurement

The transfer engine (`ebx.MasterLoader`/`ebx.StagingLoader`) is Java. There is no Java API to call a
published EBX Script library function directly - CLAUDE.md's own EBX Script sections never mention one, and
none was found by inspecting `ebx-lib.jar`. Three candidates were on the table:

- **A - a published `restService` wrapper**, called over HTTP by the transfer engine, batch or per-row. Costs
  one more permanent published endpoint per function (CLAUDE.md sec 3: "never a throwaway script... the URL
  is permanent"), a real outbound HTTP round trip the engine would have to make (and the 500ms/2s outbound
  timeout caps, CLAUDE.md sec 2, which a bulk transfer of hundreds of rows would hit hard), and credentials
  the engine would have to hold for the call. Rejected: none of that is needed once D is proven to work.
- **B - port the normalisation to Java.** Rejected unless A and D both prove impossible - the whole point of
  the product owner's ask is "one definition", and porting a 12-national-plan phone normaliser (or any future
  normalisation function) into Java duplicates logic that already exists, tested, in the library.
- **D (chosen) - a NEW `StagingAttribute` (`derivation=SCRIPT`), computed by EBX ITSELF** as a published
  `functionField` value function on the STAGING model, declared with `set_script_function` - the exact
  sibling of the `set_script_constraint` recipe CLAUDE.md sec 5 already measured for a script constraint
  (declare on `data-model` → author on `scripting` → publish). `ebx.MasterLoader` then reads the computed
  value like any ordinary field and writes it into a REAL master attribute of the same name - no HTTP, no new
  published surface except the model function script itself (one per derivation, deterministic target, never
  a throwaway).

**Four things had to be true for D to work, all checked read-only, all confirmed:**

1. **`set_script_function` exists and is exactly the constraint sibling.** `data-model.list_operations
   (name="set_script_function")`, 2026-09-23: "Mark a field's value as computed by an EBX-Script function...
   The field becomes read-only... INERT UNTIL AUTHORED: this writes the DECLARATION only... verify with
   `list_script_functions`... Remove with `unset_element_property(property="scriptedFunction")`." Rejected on
   a group/table, a primary key, an auto-increment field, or an element under a reusable named type - none of
   which a derived attribute ever is.
2. **The `functionField` script type can call a published library.** `ebx-scripting.get_syntax_guide
   (scriptType=functionField)`, same date: entry point `export function getValue(): <type>`, `_ebx.record`/
   `_ebx.dataspace`/`_ebx.dataset`/`_ebx.root` in scope, capabilities `DATABASE_READ, SCRIPT_LIBRARY_ACCESS,
   IMPURE` - `SCRIPT_LIBRARY_ACCESS` is exactly what lets it `import Fasttrack.lib.phone as phone;` and call
   it, same as a constraint. (A field marked "optimized for search" restricts this to PURE code with no
   library import - not used here; the derived attribute is a plain field.)
3. **A function field's computed value is readable through the SAME Java API `ebx.MasterLoader` already
   uses.** `javap` on `ebx-lib.jar`: `com.orchestranetworks.schema.SchemaNode` has both `isValueFunction()`
   and `isTerminalValueComputed()`; `com.onwbp.adaptation.Adaptation#get(Path)` is the exact method
   `MasterLoader` already calls for every staging field (`pStagingRow.get(Path.parse("./" + field
   .stagingField))`). No documented guarantee that `get(Path)` evaluates a value-function field was found in
   any doc, but the two `SchemaNode` flags existing on the same interface that method resolves through is
   strong structural evidence, and it is the same "identified by the field itself" mechanism the operation's
   own description promises - **this is the one fact in this lot closest to an assumption rather than a
   direct measurement; verify it first on the live instance** (see section 8).
4. **A function field is NOT what Match and Merge should index directly** - it is a STAGING-only mechanism.
   The value it computes is copied, ordinarily, into a REAL master attribute at the next staging → master
   transfer, and matching runs on that real master field, exactly like any other promoted attribute.

**Result: `ebx.MasterLoader` needed ZERO changes.** Once the derived attribute is `ACCEPTED`/`APPLIED` and
promoted like any other `StagingAttribute` (the SAME generic mechanism `master.MasterModel`/
`master.MasterFieldFilter` already apply to a `FOLD_KEY`/`VALID_VALUE` derived attribute - see that class's
own Javadoc, which documents a `VALID_VALUE` field being promoted this exact way), the existing per-field copy
loop (`fields.put(field.masterField, pStagingRow.get(Path.parse("./" + field.stagingField)))`) reads the
computed value exactly like an ordinary one. `ebx.MasterGenerator`/`master.MasterModel` needed no change
either - they already select fields purely by `TechnicalKeyRule.isAcceptedLike(attribute.status)`, with no
derivation-kind gate at all.

---

## 2. What EBX computes vs. what Java computes - the one place this had to be told apart

`StagingAttribute.derivation` already had one value, `FOLD_KEY` (`quality.CrossSystemFold`, computed by
`staging.DerivedFieldRule` in JAVA, at staging load time - a matching-key fold, never promoted to master).
`SCRIPT` is the opposite: the field is computed BY EBX, once, when the record is read/saved, and the field
becomes **read-only on the EBX side** the moment `set_script_function` is declared. `ebx.StagingLoader` must
therefore never attempt to write into it - a write would either be silently meaningless or throw on a
read-only path, and forcing a `null` default into it (the loader's own `transfer.NullFillRule`, which seeds
"every accepted attribute this feed does not supply" with `null` so a rejected mapping's stale value gets
cleared) would do exactly that on EVERY row, unconditionally, since a `SCRIPT`-derived attribute has no
`AttributeMapping` of its own either.

Fixed at the two exact points that needed it:

- `staging.StagingModel.AttributeInfo` gained an additive `derivation` field (new overloaded constructor, the
  existing 8-arg one unchanged - 16 pre-existing call sites untouched).
- `transfer.NullFillRule.defaultsFor` skips an attribute whose `derivation` is `SCRIPT` - treated like a
  primary-key/not-yet-accepted attribute: no null-fill, never touched.
- `ebx.StagingLoader.readDerivedFields` excludes `SCRIPT`-kind rows from what it hands to
  `staging.DerivedFieldRule.apply` - that class still only ever computes `FOLD_KEY`/`VALID_VALUE`.

With both of these, a `SCRIPT`-derived field is simply never mentioned by the Java loader at all - not read,
not written, not null-filled - exactly the "EBX computes it, Java stays out of the way" contract. The field
itself is built as a perfectly ordinary field by `staging.StagingGenerator`/`StagingModel.build` (same as
`FOLD_KEY`/`VALID_VALUE` already are, by `dataType`/`maxLength`) - `set_script_function` is a SEPARATE,
LATER step, exactly the way `set_script_constraint` is layered onto an already-built field by
`ebx.CapabilityApplier`.

---

## 3. What was built

- **`quality.ScriptDerivation`** - the `DERIVATION_SCRIPT` constant, with the full design rationale in its
  Javadoc (this document, condensed).
- **`tools/build_xsd.py`** (additive): `StagingAttribute.derivation` (`DERIVATION`) gains `SCRIPT`;
  `CapabilitySuggestion.nature` (`CAPABILITY_NATURE`) gains `SCRIPT_DERIVATION`. **Zero new fields on either
  table** - `SCRIPT_DERIVATION` reuses `CapabilitySuggestion.attribute` (the NEW derived attribute, not the
  source - a deliberate reinterpretation, documented inline), `targetLibrary`/`targetFunction`/
  `targetImportName`/`binding` (source attribute(s) → function parameters, identical shape to
  `SCRIPT_CONSTRAINT`), `zone` (always `STAGING`), and every apply-lot field
  (`appliedModel`/`appliedElementPath`/`appliedScriptIdePath`/`scriptBody`/`explanation`/`appliedAt`/
  `applyMessage`) - `scriptName`/`constraintScope` stay blank (a `functionField` has no name/scope concept)
  and `recordsChecked`/`recordsReported`/`offendingShapes`/`measuredAt`/`severity`/`transferPolicy`/
  `userMessage` stay blank (a derivation never flags a record, there is nothing to measure or report).
- **`capability.DerivationBodyBuilder`** (pure, unit-tested - `DerivationBodyBuilderTest`): the return-type ↔
  `StagingAttribute.dataType` mapping (`string`/`int`/`decimal`/`boolean`/`date`/`datetime` only - anything
  else, e.g. `json_object` or a collection, has no attribute shape to become and is refused upstream), the
  deterministic `functionField` body (`import <lib> as <alias>; export function getValue(): <type> begin
  <null guards on non-mandatory bound attributes> return <alias>.<function>(<args>); end` - `_ebx.record.<x>`,
  never the constraint template's `record.<x>`), and a plain-language explanation. Reuses
  `ConstraintBodyBuilder.parseBinding`/`BindingEntry`/`renderLiteral`/`escapeEbxString` directly rather than
  duplicating the binding JSON parser (identical shape between the two natures).
- **`capability.CapabilityLlmPass`** gained a THIRD local tool, `suggest_script_derivation`, alongside the
  existing `suggest_script_constraint`/`suggest_external_service` - same `Session`, same catalogue, same
  refusal/circuit-breaker discipline. The model names `newAttributeName` (also the identity/dedup check - a
  collision with an existing accepted attribute name of the entity refuses the call, since a derivation's
  real identity is the NEW attribute it creates, not a function+scope pair), `libraryIdePath`/`functionName`
  (must already have been inspected via `get_library_functions` this run), and `binding` (must name at least
  one real accepted attribute - the source). The function's return type is checked against
  `DerivationBodyBuilder.dataTypeForReturnType` before the suggestion is even recorded. `ebx
  .CapabilityLlmSuggester` writes TWO rows per accepted proposal, in one procedure: the new `StagingAttribute`
  first (its auto-increment id read straight off the created occurrence), then the `CapabilitySuggestion`
  naming it.
- **`ebx.DerivationApplier`** (rewritten by lot T1, section 10: two triggers instead of a function field; the paragraph below is the original design) - the `SCRIPT_DERIVATION` sibling of `ebx.CapabilityApplier` (which stays
  `SCRIPT_CONSTRAINT`-only, unchanged; a new, small, dedicated class rather than growing an already very
  large one). `apply(session, factory, projectCode, suggestionId)`: `get_data_model` (verify the field
  exists - `staging/generate` must already have run) → `execute_operation(set_script_function)` →
  read the `scripting` idePath of the declared function: **the field path itself**,
  `data-model/<Model>/root/<Entity>/<Attribute>` (`get_script`, accepted when `scriptTypeId` is `functionField`; measured live
  2026-10-04 on `Vol1m_Staging`, lot M6 - the earlier `.../@function` guess was wrong and every apply failed with "could not find
  the declared functionField script"; `list_scripts` on the field as a FOLDER is empty and stays only as a fallback) → re-fetch the library's exported function signature
  → `DerivationBodyBuilder.buildTemplateBody` → `update_script`/`publish_data_model` →
  `execute_query(list_script_functions)` to verify `authored:true` → write the row `APPLIED`. `remove()`
  un-declares (`unset_element_property(property="scriptedFunction")`) and republishes. Considerably simpler
  than `CapabilityApplier` **by construction**: a derivation body is ALWAYS the deterministic template (one
  call, no branching, no message) - no LLM-authored body path, no compile-retry loop, no measured-effect pass,
  no publish batching across several suggestions at once (one row is accepted at a time from the review
  screen/wizard).
- **Wired into the same "accept applies directly" flow `SCRIPT_CONSTRAINT` already uses**
  (`ebx.ui.ProposalReviewUserService#decideOne`), dispatching to `DerivationApplier` vs `CapabilityApplier` by
  the row's own nature (read from the already-posted `mdmfNature` form parameter). Every nature-routing switch
  a `CapabilitySuggestion` row needs to be handled correctly (not silently mis-typed as `EXTERNAL_SERVICE`)
  was extended: `ProposalReviews.tableOf`/`statusOf`/`statusPathOf`, `addCapabilitySuggestionLines`
  (proposition text via the new `ProposalWording.scriptDerivationProposition`, explanation/applyMessage
  display), `ReviewLine.SCRIPT_DERIVATION`, `ReviewReference` (`DERIVE` prefix), `DecisionActions`
  (`REMOVE_ONLY` for an `APPLIED` line - no Measure again, nothing to measure), the review screen's nature
  filter and status-chip title, and the wizard's own inline proposal table (`ebx.WizardData
  .attachProposalLines` was filtering to `MAPPING_STEP`/`QUALITY`/`SCRIPT_CONSTRAINT` only and silently
  dropped every `SCRIPT_DERIVATION` line - fixed).

---

## 4. The derived attribute's own review, and reaching master

The new `StagingAttribute` (e.g. `phoneE164`) needs its OWN accept, through the exact same generic attribute
review the wizard already gives `FOLD_KEY`/`VALID_VALUE` rows - no new UI for it, it is an ordinary
`StagingAttribute` row (`derivedFrom` = the source attribute, `derivation` = `SCRIPT`, shown with its own
enumeration label "Script function"/"Fonction de script"). Two independent decisions, both needed for the
whole thing to actually work:

1. **Accept the `StagingAttribute` itself** (wizard, attribute review) - this is what makes `staging/generate`
   build the field on the model, and what makes `master/generate` later promote it to a real master field
   (both purely by `isAcceptedLike(status)`, no derivation-kind gate).
2. **Accept the `CapabilitySuggestion` (`SCRIPT_DERIVATION`)** (review screen, or the wizard's own inline
   table) - this is what actually declares and authors the `functionField` script (`ebx.DerivationApplier`).

Order does not strictly matter for the FIRST apply (both must be `ACCEPTED`-or-`APPLIED` in the end), but
`DerivationApplier.apply` needs the field to already exist on the PUBLISHED staging model, which needs
`staging/generate` to have run AFTER the attribute was accepted - the same "accepted before the field exists"
race `ebx.CapabilityApplier` already documents for constraints, with the same clear refusal message ("run
staging/generate first") rather than a confusing failure.

---

## 5. Sequence for the product owner, project Personnes

1. On entity **Sample**, run the capability language-model pass again (the one that already proposed
   `SCRIPT_CONSTRAINT` rows 11/12 on `phone`) - it can now ALSO call `suggest_script_derivation`. Expect it to
   propose something like `phoneE164`, source `phone`, `Fasttrack.lib.phone.toE164` (verify the library's
   real exported function name/signature first - `get_library_functions` on `library/Fasttrack/lib/phone`,
   the LLM pass does this itself before proposing).
2. **Accept the new `phoneE164` StagingAttribute** in the wizard's attribute list for Sample (same button as
   any other proposed attribute).
3. **Accept the `SCRIPT_DERIVATION` suggestion** (Proposal review screen, or the wizard's inline capability
   table) - acceptance applies it directly: `Personnes_Staging` gets two published table triggers on
   `/root/Sample` that fill `phoneE164` (lot T1; the original design published a `functionField`), and the rows already in the
   table are recomputed.
4. **Generate the staging** (wizard step, if not already current) so the field genuinely exists before step 3
   is attempted - if step 3 was tried first and refused ("run staging/generate first"), just accept-retry
   after generating (the row is left `ACCEPTED`, not `APPLIED`, with `applyMessage` explaining why - the
   Apply button on that row's own line, or re-clicking Accept, retries).
5. **Load the staging** (lot T1: the triggers fill `phoneE164` on every row the load creates or whose source it changes; the
   rows that were already there when the derivation was applied are recomputed by the backfill at the end of the apply).
6. **Generate the master** - `phoneE164` becomes a real field of the master `Sample`/`Contact` table (the
   SAME "accepted attribute reaches master automatically" path `FOLD_KEY`/`VALID_VALUE` already prove works).
7. **Load the master** - the computed value is copied across like any ordinary field.
8. **Re-run the matching-plan proposal** for Sample - `phoneE164` is now an ordinary promoted attribute with
   its own profile, available to the matching-plan language-model pass exactly like every other master
   attribute (no change was made to that pass; it reads promoted attributes generically). Expected: with both
   systems' phone numbers normalised to E.164, "phone identical" (or a fuzzy variant) on `phoneE164` finally
   bridges SALESFORCE and SAP records that share the same real phone number - a few genuinely foreign or
   malformed numbers are expected to come back `null` from the library (never guessed at), so those specific
   pairs stay unmatched on this signal, which is the correct, honest outcome, not a bug.

---

## 6. What this deliberately does not cover

- **Fact 3 of section 1 (a function field's computed value is readable via `Adaptation#get(Path)`) is the one
  assumption in this lot not directly confirmed against a live instance** - `SchemaNode.isValueFunction()`/
  `isTerminalValueComputed()` existing, and the operation's own "identified by the field itself" wording, are
  strong structural evidence, not a captured read. **Verify this first**, on a scratch field, before trusting
  the rest of the pipeline in anger.
- **The `functionField` scripting idePath is the field path itself** (measured live 2026-10-04, lot M6, EBX 6.3.0-rc2): right after
  `execute_operation(set_script_function, {"element_path":"/root/Personne/phoneE164"})`,
  `get_script(scriptIdePath="data-model/Vol1m_Staging/root/Personne/phoneE164")` answers `scriptTypeId: functionField`, `name:
  Vol1m_Staging.root.Personne.phoneE164`, the template `export function getValue(): string begin return 'A string value'; end` and a
  `revisionId`; `list_scripts` on that path as a folder is empty and `list_scripts("data-model/Vol1m_Staging")` only shows `.../root` as a
  `schema_group`. `DerivationApplier.discoverFunctionIdePath` reads the field path first (`DerivationBodyBuilder.guessedScriptIdePath`
  now returns it) and keeps the folder listing as a fallback. Tested with a scripted transport (`DerivationApplierTest`); the apply itself
  (`POST .../capabilities/<id>/apply`) is confirmed only by a live run.
- **No bulk-apply batching for `SCRIPT_DERIVATION`** - `ebx.CapabilityApplier`'s own one-publish-per-model
  batching across several accepted constraints was NOT ported; a bulk accept of several `SCRIPT_CONSTRAINT`
  rows still batches, a bulk accept including `SCRIPT_DERIVATION` rows applies each one individually (its own
  `get_data_model`/`publish_data_model` round trip). Fine at the scale this feature is used at today (a
  handful of derivations per project); revisit if that changes.
- **No Re-apply/staleness tracking** for an `APPLIED SCRIPT_DERIVATION` line (the `SCRIPT_CONSTRAINT`-only
  `capability.ConstraintStaleness` was not extended) - Remove-then-accept-again is the only way to refresh one
  today.
- **The wizard's zone-based apply gate (`WizardRules.capabilityApplyGate`, which greys the Apply/Accept button
  out before the staging model exists) stays `SCRIPT_CONSTRAINT`-only** - a `SCRIPT_DERIVATION` accepted
  before `staging/generate` has run simply surfaces `ebx.DerivationApplier`'s own clear error as
  `applyMessage` after the fact, rather than being pre-emptively disabled. The applyMessage/appliedInfo
  DISPLAY itself (why an apply failed, when it succeeded) IS shown for both natures.
- **`Remove` does not touch the `StagingAttribute` row** - un-declaring the function on the model (field
  reverts to an ordinary, empty EBX field) leaves `StagingAttribute.derivation = SCRIPT` untouched, so
  `ebx.StagingLoader` keeps excluding the field from its own write loop regardless - a removed-but-not-
  rejected derived attribute simply stops being computed at all (stays `null`) rather than reverting to being
  written by the loader. Rejecting the `StagingAttribute` itself is the real "undo everything", a separate
  decision left to the person.
- **The "N waiting" summary counters/banners in the wizard were not extended** to include `SCRIPT_DERIVATION`
  (`WizardPage.countOf`/`waitingPart` stay `SCRIPT_CONSTRAINT`-only) - cosmetic under-counting only, the line
  itself is fully visible and decidable in the inline table.
- **Return type is restricted to the six scalar `StagingAttribute.dataType`s** - a normalisation function
  returning a structured value (`json_object`, a list) has no attribute shape to become and is refused at
  suggestion time; splitting a structured result into several derived attributes (or a follow-up derivation
  reading a derivation) was not attempted.

---

## 7. Test result

`bash tools/test_local.sh`: all green, including the new `DerivationBodyBuilderTest` (return-type mapping in
both directions, the deterministic body's null guards/literal escaping/multi-attribute case, the
`_ebx.record.` form, and the plain-language explanation). No EBX-bound class in this lot has a direct unit
test (`ebx.DerivationApplier`, like `ebx.CapabilityApplier` before it, is not unit-testable without a live
instance) - its correctness rests on the MCP contracts measured in section 1 and the existing, tested MCP
client plumbing (`ebx.McpServers`) it reuses unchanged.

---

## 8. Lot M5, 2026-10-04: an accepted derivation that was never applied no longer fills its attribute with the raw value

**The defect** (measured 2026-10-04 on Vol100k and Vol1m): `Personne.phoneE164` (`derivedFrom = phone`, `derivation = SCRIPT`, capability 107
`ACCEPTED`, `appliedModel` empty) held exactly the raw phone on 100 % of 491,805 rows, the matching plan compared it as an exact field, and
nothing said anything. Two causes, both fixed:

1. **The raw copy came from `ebx.StagingLoader.readDerivedFields`.** It handed every accepted attribute whose `derivation` is non-blank to
   `staging.DerivedFieldRule.apply` and excluded only `CHILD_KEYS`; `apply`'s last branch was a plain `else` that computed the matching-key
   fold (`CleaningSteps.foldKey`: trim, collapse blanks, strip accents, upper-case) for ANY derivation it did not know. For a phone number
   that fold changes nothing, hence `phoneE164 = phone`. (The Javadocs of `ScriptDerivation` and `NullFillRule` said the loader excluded
   SCRIPT attributes; `NullFillRule` did, but the reader `StagingLoader.readAttributes` never passed the derivation of a SCRIPT attribute, so
   that exclusion never took effect either.) `DerivedFieldRule.apply` now computes `FOLD_KEY` and `VALID_VALUE` only and leaves any other
   derivation alone, and `readDerivedFields` no longer hands it a SCRIPT row.
2. **Nothing but the review screen applied a derivation** (`POST .../capabilities/{id}/apply` answered "only SCRIPT_CONSTRAINT suggestions
   can be applied"). See `docs/CAPABILITY-APPLY.md` section 10.

**The rule now** (`staging.ScriptDerivationGate`, `ebx.ScriptDerivations`): a script-derived attribute is never written by the loader. It is
*applied* when an accepted `SCRIPT_DERIVATION` suggestion names it with `appliedModel` and `appliedElementPath` set (what `DerivationApplier`
writes and `remove` clears). Applied: EBX computes it (lot T1: two table triggers of the staging model keep a STORED field up to date; the original design was a computed `functionField`, never stored). Not applied: the field is an
ordinary field and `NullFillRule` forces an explicit `null` into it on every row the load writes (so a value an older load copied there is
cleared), and the load says so: one sentence per attribute in the STAGING_LOAD step **message** and in its **detail**
(`scriptDerivationsNotApplied`), in the wizard's live notes, and in the REST answer:

> `phoneE164 is declared as a script derivation that is not applied: left empty - apply it (Proposal review, or POST /projects/{code}/capabilities/107/apply)`

A mapping on a script-derived attribute is ignored (`readMappings` skips it): it would write the raw source value.

**Where the user is told**: (a) step 4's "decisions left" card has a group "To apply" listing every `ACCEPTED`, not `APPLIED`
`SCRIPT_DERIVATION` with an Apply button (`StillToDecide.toApply`); (b) `matching/configure` records a warning in the MATCHING_CONFIG step
message and detail (`scriptDerivationWarnings`) when a clustering field or a decision-tree field of the plan is a not-applied script derivation.

**What to re-run after applying** (written for the function field; with lot T1 the staging value is stored, written by the triggers and by the
backfill of the apply - see section 10 - so the staging value is right as soon as the apply returns `backfill.status = DONE`).
`MasterLoader` copies the staging value with `pStagingRow.get(...)` and has no incremental hash, so **master load** (all rows) is what moves
the correct value to the master; then **matching must be run again** on that master. A project whose master was loaded while the attribute was
unapplied holds the raw copy there until that master load.

## 9. Lot M6, 2026-10-04: an indexed function field refuses a library import - what was measured and what was read in the jars

*(Superseded the same day by lot T1, section 10: `DerivationBodyBuilder.libraryAccessRefusal` and `DerivationApplier.discoverFunctionIdePath` no longer exist. The
measurements below are what made the function field a dead end.)*

**Measured live** (EBX 6.3.0-rc2, `Vol1m_Staging`, `/root/Personne/phoneE164`): `update_script` on the declared function with a body that does
`import Fasttrack.lib.phone as phone; ... return phone.toE164(_ebx.record.phone, '');` is refused - `Method 'toE164' requires capability
SCRIPT_LIBRARY_ACCESS which is not available in this context.` A pure body (`uses core.string as str; ... str.replaceFirst(str.replaceAll(...))`)
validated and published. `get_script` reports `indexed: true`. The scripting server's syntax guide for `functionField`
(`mcp/prompt/templates/functionField.md` in `ebx-ide.jar`) says the capabilities `IMPURE, SCRIPT_LIBRARY_ACCESS, DATABASE_READ` apply to NOT-indexed
fields only; an indexed ("optimized for search") one allows pure operations, no library import, and only fields that are themselves optimized for search.

**What the Factory does now** (`DerivationBodyBuilder.libraryAccessRefusal`, `DerivationApplier.apply`): when the validation text of `update_script`
holds `SCRIPT_LIBRARY_ACCESS`, the apply rolls the declaration back (as for any failure) and fails with "the function field '<attr>' is optimized
for search (indexed): EBX refuses library imports there (...) so a derivation that calls a library function cannot be authored on it. The
declaration was rolled back and the field stays empty." - stored in `CapabilitySuggestion.applyMessage`, not a generic compile error. Until the flag can
be written (below), a `SCRIPT_DERIVATION` that calls a library function cannot be applied on a DMA model. Test: `DerivationBodyBuilderTest`.

**How a DMA function field becomes NOT indexed - read in the jars (`javap` on `ebx-lib.jar`, `ebx-ide.jar`, `ebx-assistant.jar` of FT 6.3 RC,
2026-10-04; nothing was run against the instance):**

- The flag is NOT a property of the data-model element. `com.orchestranetworks.dma.beans.FunctionProperties` / `dma.scripting.FunctionPropertiesBean`
  (what `set_script_function` writes on the `DmpElement`, via `ScriptsExtensionHelper.declareScriptedFunction`) carry ONE boolean, `scripted`.
- It is a field of the built-in `function` data-model extension, i.e. of a row of the scripts dataset of the model (the table `/root/function`, class
  `com.orchestranetworks.dsl.script.model.ScriptsDataModelPaths$_Function`, paths `_FieldPath`, `_RevisionId`, `_RevisionDate`, `_RevisionUser`,
  **`_Indexed`**, `_Script`; history table `_FunctionHistory` has `_Indexed` too). For a packaged module model the same value is the
  `<indexed>` element of each `<function>` in the sidecar `<Model>_@function.xml` (CLAUDE.md section 5).
- Runtime meaning, `dsl.script.function.ScriptFunctionField.setup`: `FunctionScript.getIndexed() != Boolean.FALSE` adds the field/table dependencies
  (indexed, computed from its dependencies); `FALSE` does not. A row with a null value reads as `ScriptsDataModelExtension.LEGACY_SCRIPT_INDEXED = TRUE`.
- **Who writes it**: `ebx-ide`'s `ScriptSourceDataModelRecord`: creating the function row sets `_Indexed = TRUE` (hard-coded), and
  `updateFunction(ProcedureContext, BigDecimal revisionId, Boolean indexed, String source)` sets it to the `indexed` argument (null becomes TRUE).
  `ScriptType.isIndexedEditable()` marks the script types for which the IDE lets a person choose it (the `functionField` type; triggers and
  constraints refuse it: "Property 'indexed' must be null for a trigger/constraint").
- **Can a data-model MCP operation write it? No.** `set_script_function` takes `element_path` only (`SetScriptFunctionOperation`); `list_script_functions`
  only REPORTS `indexed`, `scripted`, `authored`. **Can the scripting MCP? No.** `update_script`'s parameters are `scriptIdePath, source, revisionId,
  openApiJson, parameters, documentation, confirmReplaceMetadata`; its code reads the CURRENT `ScriptDetails.indexed` and passes it back unchanged
  (`lambda$updateScript$4`), so every MCP update keeps whatever the row holds (TRUE for a row the IDE created).
- **A write path exists, outside both MCP servers: the IDE REST API of `ebx-ide`.** `ScriptService.updateAndValidateDefinition`, `POST
  /ebx-ide/rest/v1/script/definition-validate/<ideSteps>` (`ideSteps` = the idePath, i.e. `data-model/<Model>/root/<Entity>/<Attribute>`;
  query `publish`, `dataModelVersion`, `publicationName`), body `ScriptUpdate` = `{source, revisionId, documentation, parameters, openApi,
  indexed}`; it calls `ScriptSourceEdition.update(..., indexed, ...)`. That is what the IDE screen calls. **Not tried**: authentication (the module
  would need a session or an `EBXUID` cookie the way `McpServers` gets one), whether the `ideSteps` form takes a data-model path as such, and what
  `indexed: false` does to a field that other fields or SQL depend on were NOT verified. No implementation was written on that unverified route:
  a live probe on a scratch model is the next step (one `POST` with `"indexed": false` and a pure body, then `get_script` -> `indexed: false`, then
  the library-import body through the MCP `update_script`, which keeps the flag).

Consequence of `indexed: false` to check live before relying on it: the field is no longer optimized for search, so a filter, a SQL `where` or a
sort on a computed attribute may be refused or slow (the attribute is computed on read) - for `phoneE164`, a matching key built on it would be the
first thing to test.

## 10. Lot T1, 2026-10-04: the derived attribute is a stored field fed by two table triggers (staging zone)

**Decision** (the product owner, 2026-10-04). Section 9 showed that an INDEXED function field cannot import a script library, and that matching needs the field
indexed; no MCP operation writes the "indexed" flag. So a `SCRIPT_DERIVATION` is no longer a computed function field: the derived attribute is an
ORDINARY STORED field (built by `staging.StagingModel` like any other), and two EBX-Script **table triggers**, `beforeCreate` and `beforeModify`, call the
library function and write the field. One mechanism for real time and for bulk loads. Recomputed on modification only when needed. This lot covers the
**staging zone only**; the MCP half (`ebx.DerivationTriggers`) takes the model name and the table as parameters so a later lot can reuse it for the master model.

**Measured live** (EBX 6.3.0-rc2, 2026-10-04, model `Vol100k_Staging`, table `/root/Personne`; nothing below was re-derived by the lot):

1. *Declare*: data-model `execute_operation(operation="set_script_trigger", arguments_json={"element_path":"/root/Personne","name":"DerivePhoneE164OnCreate",
   "type":"beforeCreate","scope":"table"})`, and again with `...OnModify` / `beforeModify`. `type` is case-sensitive, `scope` required. Answer
   `{"...","replaced":false,"publishSafe":true,...}`. Additive: the same name replaces that entry in place, another name adds a trigger; a script cannot be renamed.
2. *Where the body is*: `data-model/<Model>/root/<Table>/@trigger/<TriggerName>` (`get_script` answers `scriptTypeId: triggerBeforeCreate` / `triggerBeforeModify`, with a
   `revisionId`; skeleton `export procedure onBeforeCreate()\nbegin \nend\n`). `list_scripts` on `data-model/<Model>/root/<Table>` shows a folder `@trigger`.
3. *Author*: `update_script(scriptIdePath, source, revisionId, confirmReplaceMetadata=true)`, `data.validation.isSuccess`. Both bodies compiled first time WITH the
   library import (trigger script types have `SCRIPT_LIBRARY_ACCESS`).
4. *Publish*: `publish_data_model` alone publishes the triggers; the dataset built on the publication follows at once, no restart.
5. *Verify*: data-model `execute_query(operation="list_script_table_triggers")` answers `{"tableTriggers":[...]}` (it was `[]` before declaring); the entries report
   the declared type and `authored`. The Factory reads the answer defensively (`DerivationTriggers.stateOf`): an entry is found by the trigger name anywhere inside it,
   a missing `authored` flag is "unknown" and never fails the apply.
6. *Remove*: `unset_element_property(data_model_name, element_path=<TABLE path>, property="triggers", item="<TriggerName>")` - WITHOUT `item` the whole trigger list of
   the table is wiped; the Factory never calls it without `item`. Then publish.
7. *Runtime*, through Data Services with the bodies below: create with phone `+33 (0)6 12 34 56 78` stores `phoneE164 = +33612345678`; modifying phone
   recomputes; writing `TAMPERED` into `phoneE164` by hand is overwritten by the computed value; phone to null gives null; a number the library refuses gives null; modifying
   another field leaves the value alone. No error with nulls.

**The two bodies** (`DerivationBodyBuilder.buildCreateTriggerBody` / `buildModifyTriggerBody`; phone example, library alias `lib`, second parameter a literal blank):

```
import Fasttrack.lib.phone as lib;

export procedure onBeforeCreate()
begin
  if isNull(_ebx.record.phone) then
  begin
    _ebx.record.phoneE164 := null;
  end
  else
  begin
    _ebx.record.phoneE164 := lib.toE164(_ebx.record.phone, '');
  end
end
```

```
import Fasttrack.lib.phone as lib;

function differs_string(a: string, b: string): boolean
begin
  if isNull(a) then return isNotNull(b);
  if isNull(b) then return true;
  return a <> b;
end

export procedure onBeforeModify()
begin
  if differs_string(_ebx.record.phone, _ebx.nextRecord.phone)
    or differs_string(_ebx.record.phoneE164, _ebx.nextRecord.phoneE164)
    or isNull(_ebx.nextRecord.phoneE164) then
  begin
    if isNull(_ebx.nextRecord.phone) then
    begin
      _ebx.nextRecord.phoneE164 := null;
    end
    else
    begin
      _ebx.nextRecord.phoneE164 := lib.toE164(_ebx.nextRecord.phone, '');
    end
  end
end
```

Rules: an attribute-bound parameter reads `_ebx.record.<attr>` (create) / `_ebx.nextRecord.<attr>` (modify; `_ebx.record` is the OLD values, read only); a literal parameter is
rendered as in a constraint; one null guard over every distinct attribute-bound parameter, generated only when none of them is mandatory (`isNull(a) and isNull(b)` gives `derived := null` - "and" since 2026-10-12, see "The null guard" below; none to guard gives the bare
call); the modify trigger recomputes when ANY bound source differs between old and new, OR the derived attribute differs (someone wrote it), OR the new derived value is empty.
One `differs_<type>` helper per distinct script type needed (`string`, `int`, `decimal`, `boolean`, `date`, `datetime`, from `StagingAttribute.dataType` by
`DerivationBodyBuilder.scriptTypeOrNull`); an attribute whose data type is none of those is refused at apply with a clear message, never guessed. Trigger names
`Derive<Attribute>OnCreate` / `Derive<Attribute>OnModify` (`DerivationBodyBuilder.triggerName`; `phoneE164` gives the names already on `Vol100k_Staging`, so a re-apply replaces them
in place).

**What `apply` does now** (`ebx.DerivationApplier`, one PipelineStep `CAPABILITY_APPLY`, one detail entry per MCP call): model, table and fields exist; the library function is
read and the two bodies built BEFORE anything is declared; **migration** - if the field is still declared as a function (`list_script_functions`, an older apply), it is
un-declared with `unset_element_property(property="scriptedFunction")` first (a stored field cannot also be a function; detail `undeclaredFunction: true`); both triggers declared;
both bodies authored (each `update_script` must answer `success` and `validation.isSuccess`); ONE `publish_data_model`; `list_script_table_triggers` verified; the row written `APPLIED`
(`appliedElementPath` = the FIELD path, so `ScriptDerivationGate.isApplied` is unchanged; `appliedScriptIdePath` = the create trigger, the modify one is in the answer and the
`scriptBody` holds both bodies); then the backfill. Any failure before the row is written takes both triggers off the draft BY NAME and republishes only if a publish had happened
this round. `remove` takes both triggers off by name (only those `list_script_table_triggers` reports), un-declares a leftover function field, publishes, and puts the row back to
`ACCEPTED`; the field stays a stored field, its stored values stay, and the M5 rule (section 8) holds it EMPTY at the next load because the derivation is no longer applied.

**The loader never writes an applied derivation, and still holds an unapplied one empty** (read, lot T1): `NullFillRule.defaultsFor` skips a SCRIPT attribute that is applied
(`transfer/NullFillRule.java`, the `continue` under "script-backed-derivation lot"), `StagingLoader.readMappings` skips any mapping on a SCRIPT attribute, and
`readDerivedFields` does not hand it to `DerivedFieldRule` - so on create and on modify the loader's field map never contains it, the trigger's value is never overwritten, and
`_sourceHash` change detection (`IncrementalTransfer`) does not read it. An unapplied one falls through `defaultsFor` to the null seeding (a feed never covers it), so it is written `null`
on every row the load writes. No loader change was needed.

**Backfill** (`ebx.DerivationBackfillPass` + the pure `staging.DerivationBackfill`; `POST /projects/{code}/capabilities/{id}/backfill`; automatic at the end of an apply when the
table has rows). A trigger only fires on a write, so a row loaded before the derivation was applied holds an old copy or nothing. Whether EBX fires `beforeModify` for a modification that
changes nothing is NOT known from Java, so the pass does not rely on it: for every row whose bound sources are not all empty, or whose derived value is not empty, it writes a fixed
marker, `__recompute__`, into the derived field; the modify trigger sees the derived field differ and overwrites it in the same transaction, so the marker is never stored. **Guard**: the
first row to touch is written alone and read back inside its own transaction; if the stored value is still the marker the triggers are not live, the transaction is rolled back and the pass
ends with `TRIGGER_NOT_LIVE` ("trigger not live on <table>, nothing changed"). Then batches of at least `ChildKeysPass.MIN_WRITE_BATCH` rows (or `transferBatchSize` when larger), one
Procedure each, the triggers left ACTIVE, one streaming scan, writes through the record the scan gave. The answer and the PipelineStep detail carry `scanned`, `recomputed`, `skipped`,
`skippedNullNonString`, `writeBatches`, `durationMs`; `status` is `DONE`, `EMPTY` (no row yet), `SKIPPED` (a staging load holds the table: the pass claims the STAGING resource and never overlaps
one), `STOPPED`, `TRIGGER_NOT_LIVE` or `FAILED`. A problem in the backfill never fails the apply (the triggers are live and recorded): it is in the answer's `backfill` node and noted in `applyMessage`.
**Limitation**: a derived field that is not text (or a text field shorter than the marker) cannot take the marker, so `null` is written when it holds a value, and a row whose value is already empty
cannot be provoked that way: it is counted in `skippedNullNonString` and computed the next time its source changes.

**The null guard (2026-10-12): the derived value is null only when EVERY guarded input is null.** Until then both bodies guarded the call with `isNull(a) or isNull(b)`, i.e. ONE empty
input was enough to write `null` without calling the function. They now write `isNull(a) and isNull(b)`: with several guarded inputs the function is called as soon as one of them has a
value, and the library decides what a partly filled input gives (its own documented null-safety); with a single input the guard is the same as before. **When at least one attribute input is mandatory in staging the inputs can never all be null, so NO guard is generated** (the bare call, create and modify
bodies alike); the `and` guard exists only when every attribute input is optional. The inputs are the distinct attribute-bound parameters; literal parameters are not inputs. (The master bodies, §12, are
built with no mandatory flag, so they always carry the guard.) The modify body's recompute condition (`differs_<type>(old, new)` on each source, on the derived
attribute, or `isNull(_ebx.nextRecord.<derived>)`) does not depend on the guard and is unchanged; only the inner `if` that follows it changed. **Measured case** (project Vol100kN, 2026-10-12):
`Person.nameMatchKey = Fasttrack.lib.names.sortedKeyOf(firstName, lastName)` - the library says "Either field may be null" and answers `'francois stephane'` from the first name alone - but
the `or` guard gave no key to the 8 % of LOYALTY rows written in capitals (a casing-based split put the whole name in `firstName`, `lastName` empty), and about 1,900 true duplicate pairs
were missed because the key is the matching plan's candidate-search field. **A derivation applied before this change keeps its old bodies** (stored in the model's scripts and in the row's
`scriptBody`) until it is applied again: `POST /projects/{code}/capabilities/{id}/apply` (administrator, with an `X-Requested-With` header; no body) on the APPLIED suggestion re-authors both
triggers from the current builder in place, republishes once and ends with the backfill (below), which recomputes every row with a source or a derived value - so the rows that have no key
today get one, without the re-apply marker of section 11 being needed (the route accepts an APPLIED row as is); `POST /projects/{code}/capabilities/{id}/backfill` alone would NOT help, it
runs the old triggers. The master triggers (section 12) are regenerated by the same apply, but there is no master backfill: master rows get the new value when they are next written (a master load).

**Not covered**: the master zone (done by lot T2, section 12); the bulk-load optimisation "triggers off + one grouped computation" is deliberately not
built before the per-row overhead of the triggers during a load is measured; no staleness tracking of a changed library (re-apply replaces both triggers in place).

**Not verified without the live instance** (listed so it is tested after deployment): the exact shape of `list_script_table_triggers`' `authored` flag (parsed defensively); that the `Adaptation` returned
by `doModifyContent` shows the value the trigger wrote (if not, the backfill guard reports "trigger not live" on a live trigger - the safe direction); the `<>` comparison of `int`, `decimal`,
`boolean`, `date` and `datetime` values in the `differs_<type>` helpers (only `string` was measured); a re-apply over triggers already published; the migration of a field still declared as a function;
a backfill of a large table (batch size and duration).


## 11. Parameters a person settles (lot P1, 2026-10-04)

**The defect, measured 2026-10-04 on project Vol1m.** The model proposed `Fasttrack.lib.phone.toE164(value, country)` with the binding
`{"value":"phone","country":{"literal":""}}`. With a blank country the function returns null for every national-form number: **68,905 of the 92,175
phones of one source system came out empty** (no effect on the matching pairs of that data set, but a real loss of evidence). The model even wrote it in its
own rationale ("with no country attribute, national values may produce no result"); the tool contract FORCED the blank: since `fix/match-key-confidence`
(2026-09-26) `suggest_script_derivation` refused any `toBeProvided` entry ("a derived attribute is computed on every record, so a missing input can never be
supplied later") and told the model to bind `{"literal":""}` when the function allowed a blank. The model cannot always know the right constant either: by data
policy it sees value SHAPES with counts, not values.

**The rule since lot P1.**

1. *Accept and apply are two moments.* A derivation binding entry may be `{"toBeProvided":"<what is needed and why>"}` again. The new attribute is accepted as
   such, with its confidence untouched: `TO_BE_PROVIDED_CONFIDENCE_CAP` is NOT applied to a derivation - a capped key attribute would fall out of the one-click
   accept and of the matching plan (the PersVol4 regression described in `capability.DerivationEvidence`), and `DerivationEvidence`'s measured confidence keeps
   working. The block is at APPLY time: `DerivationApplier.prepare` refuses a binding with a `toBeProvided` entry, and its message now names the parameter(s) and says
   where to supply them. Until then the attribute stays empty (lot M5: the loader holds an unapplied derivation empty). `AT LEAST ONE` parameter must still bind to a
   real accepted attribute. The stored rationale gets one more sentence: "A person must still provide <parameters> (wizard, step 4) before this derivation can be applied; ..."
   (`DerivationParameters.stillToProvideSentence`).
2. *What the model is now told* (tool schema and system prompt, same words): a parameter that completes values which do not carry the information themselves (a default
   country for national phone numbers, a default currency, a locale) decides the result for part of the rows. Read the profiled shapes of the source attribute; when some
   values need such a default, prefer the function variant that takes one when the library offers it (the library now exports `toE164WithDefault(value, defaultCountry)`:
   international values read off their own calling code, national ones completed with the default); bind it to a real attribute when one holds that information per record;
   otherwise to a literal ONLY when what it was given establishes the value - and say what establishes it in the rationale; otherwise `toBeProvided`. A blank literal is right
   only when the shapes show that no value needs the parameter - say so in the rationale. Nothing in Java knows what a country or a currency is.
3. *Settling a parameter is a person's action.* `PUT /projects/{code}/capabilities/{id}/binding`, body `{"<parameter>":"<value>", ...}` (administrators; same CSRF
   guard as every PUT). Only a `SCRIPT_DERIVATION`; each named parameter must exist in the stored binding and currently be a `literal` or a `toBeProvided` entry (an
   attribute-bound parameter is never changed here); a value may be blank only when the entry already was a literal (a blank literal is the deliberate "no default"), never
   to settle a `toBeProvided`; the result is stored as `{"literal":"<value>"}`. 400 on a bad body (an unknown parameter answers the list of settable ones), 404 unknown
   project or suggestion, 409 not a derivation / rejected. Answer: `binding`, `changed`, `toBeProvidedRemaining`, `reapplyNeeded`. Rules: `capability.DerivationParameters`
   (pure); write: `ebx.DerivationParameterSettings` (one `Procedure`, same write path as `DerivationApplier.writeApplied`). The wizard's step 4 "To apply" list offers the same
   thing: per derivation, each settable parameter with a text input (the model's note beside a to-be-provided one) and ONE "Save parameters" button; Apply is replaced by its
   reason while a `toBeProvided` remains.
4. *The stored bodies go stale, and the route says so instead of recomputing them.* When the binding of an APPLIED suggestion changes, the two stored trigger bodies (and the live
   triggers) still carry the old parameter. Regenerating the bodies just to compare would need the library read through MCP, so the route records the fact: `applyMessage` is set to
   the fixed sentence `DerivationParameters.REAPPLY_MARKER` ("Parameter(s) changed since this derivation was applied: apply it again to recompute the stored values."), and
   every reader of the applied state treats exactly that sentence as "apply again": `staging.ScriptDerivationGate.Entry.reapplyNeeded` (read by `ebx.ScriptDerivations`, and by
   the matching plan warnings), and step 4's "To apply" list ("parameters changed - apply again", button "Re-apply"). `Entry.applied` is NOT changed by the marker - the loader's rule
   (an applied derivation is never written by the loader) holds either way. The route never applies: applying republishes a model and rewrites a table, it stays an explicit
   action. A successful `DerivationApplier.apply` overwrites `applyMessage` (`writeApplied` writes null), which clears the marker; the apply on an already APPLIED suggestion
   re-declares both triggers in place (same names), re-authors them from the NEW binding, republishes once, and the backfill recomputes every row with a source or a derived value
   (`DerivationBackfill.decide`).

**Not verified without the live instance**: that a model really proposes `toBeProvided` / the `...WithDefault` variant under the new wording; the wizard's input round trip (the
script posts only the inputs that differ from `data-initial`); the PUT route against Jersey (`@PUT` with a plain `String` body, like the neighbouring POST routes); a re-apply over
triggers published with the old parameter (already listed in section 10); that the new rationale sentence survives the 2000-character bound of `rationale` when the model's own text is long.


## 12. Master zone (lot T2, 2026-10-04)

**What.** The two triggers of section 10 (`Derive<Attr>OnCreate`, `Derive<Attr>OnModify`, same names, same library function, same parameters) are also declared on the
MASTER table of the entity, when the project has a master model. Code: `master.DerivationMasterTarget` (the pure decision, tested), `ebx.DerivationMasterTriggers` (the MCP
sequence, through `ebx.DerivationTriggers`, which was already parameterised by model and table), `ebx.DerivationApplier` and `ebx.MasterGenerator` (the three moments below).
No XSD change.

**Why.** In the master zone the derived attribute used to be a copy: `MasterLoader` copies the staging value and nothing recomputes it. Three situations leave a stale or
missing value there, and the triggers close all three:

- (a) a data steward edits the SOURCE attribute directly on a master record: `beforeModify` sees the source differ and recomputes;
- (b) Match and Merge creates a golden record whose source value survives from one record and whose derived value survives from another (survivorship is per field):
  `beforeCreate` computes the derived value from the surviving source value, and a later merge that changes the source is a `beforeModify`;
- (c) a record is created directly in the master: `beforeCreate`.

**When they are declared** (three moments, the same decision each time):

1. *At the end of `DerivationApplier.apply`*, after the staging part succeeded and was recorded (`writeApplied`): the master model is read live (`get_data_model`), the decision
   is taken, then declare, author, ONE `publish_data_model` of the master model, verify. A failure here never undoes or fails the staging apply: the answer's `master` node
   (`status` `DECLARED` / `SKIPPED` + `reason` / `FAILED` + `reason`), the PipelineStep detail (`master`, `masterTriggers`) and `applyMessage` say what happened (`applyMessage` only for
   a failure or a limitation worth telling - "no master model yet" is the normal case and stays in the answer).
2. *In `MasterGenerator`*, for every APPLIED derivation of the project whose entity is promoted (generate and re-generate): the triggers are declared and authored on the model DRAFT
   **before** the generator's own `publish_data_model` - in the create path just before `ensureEmbedded`/`ensureMameAddon`/publish (`runModelStep`), in the regeneration path just before
   `republish("regeneratePublish")` (`runRegeneration`) - so the generator pays no publish of its own for them. The one case with an extra publish is the `UP_TO_DATE` regeneration, which
   publishes nothing otherwise: it republishes when at least one trigger was (re)declared, because whether the draft's copy is the published one is not known from the generator's side.
   A derivation that cannot be handled is a line in the step's `notes` and in the detail (`masterDerivationTriggers`), never a failed generation.
3. *In `DerivationApplier.remove`*: both triggers are taken off the master table by name (only those `list_script_table_triggers` reports for THAT table) and the master model is
   republished; no master model, no table or no trigger is `ABSENT`, not an error; a failure is in the answer's `master` node and in `applyMessage`, and never undoes the staging removal.

**When the master is skipped, with a recorded reason, never an error** (`DerivationMasterTarget.Kind`): no master model yet (`NO_MASTER_MODEL`, normal: the generator declares them
later), no master table for the entity (`NO_MASTER_TABLE`: a member of another entity, or accepted after the last generation), an attribute the triggers read or write that is not a
field of the master table under its OWN name (`ATTRIBUTE_NOT_ON_MASTER`: not promoted, or renamed by `MasterFieldNameRule` - `Id` becomes `Id_src`, and the trigger reads `_ebx.record.<name>`;
the master name is NOT guessed), or the suggestion carries the re-apply marker of section 11 (`PARAMETERS_CHANGED`: the staging triggers are stale and so is what master would get; the next
apply updates both).

**The master bodies are built with no mandatory flag.** Section 10's body leaves a bound attribute unguarded when it is mandatory in staging (it can never be null there). A master
business attribute is never mandatory (`MasterModel`: a golden record created by the add-on may leave it empty), so the same unguarded call would hand a null to the library function.
Everything else is the staging body. (When no bound attribute is mandatory in staging the two bodies are identical.)

**No backfill on the master, deliberately.** Master rows already hold the value computed in staging (copied by `MasterLoader`), and a mass rewrite of master rows would fire the Match and Merge
add-on's own table trigger (matching on update) row by row - a cost and a side effect the derivation must not impose. So there is no master counterpart of `DerivationBackfillPass`: a master
row gets a recomputed value only when it is next written (a steward's edit, a merge, a master load). Where a master row's value is wrong today, the way to fix it is to fix staging and
reload the master.

**Since lot V3-M (2026-10-04) `MasterLoader` writes only the rows whose values changed** (`docs/MASTER-ZONE.md` section 15): the next paragraph describes the loader as it was when this section was written
(every row rewritten), and the triggers now run only for the rows that are written. One consequence to read for: a master row whose trigger-computed derived value differs from the staging value (a
library changed between two applies) is compared as changed at every load - the loader writes the staging value, the trigger puts its own back - so such a row never counts as `unchanged`; it
costs what it cost before the lot, no more.

**What `MasterLoader` does once the master triggers exist (read, not measured).** `loadEntity` scans EVERY staging row (`MasterLoader.java:575`, `createRequestResult(null)`) and `planRow` /
`upsertOne` write every non-merged row with `doModifyContent` (`MasterLoader.java:615`, `:889`) - the loader has NO change detection of its own (`MasterUpsertDecision` only decides insert /
update / skip-merged). So a master load already rewrites every master row at every load, with or without our triggers; the triggers add no churn of their own. What they do per row:
`beforeModify` compares old and new SOURCE (equal unless staging changed it), old and new DERIVED (the loader writes the staging value over the stored one; equal unless staging's value
changed), and the new derived value for emptiness. Equal and non-empty: nothing is computed, cost = three comparisons. Source changed in staging: the loader writes the new source and the new
staging-computed derived value, the trigger recomputes the same value from the new source (the library is deterministic). Derived value empty (the library refused the number): recomputed at
each write, to the same empty value - one library call per such row per load, no write loop. A staging value that differs from what master would compute (a library changed between the two
applies): the trigger wins, once per row, and the master value is then stable. There is no fight between the copied value and the computed one: `beforeModify` runs on the loader's own write,
in the same transaction, so the computed value is the one stored.

**Not covered.**

- A master row is not recomputed by anything but a write (see "no backfill" above).
- Whether the add-on's merge procedure fires table triggers when it creates or updates a golden record (situation (b)) is not known from here: EBX fires them for every programmatic write
  unless the writer switches them off, and nothing in the factory switches them off - to be confirmed live.
- The master triggers follow the LAST apply: a parameter change (section 11) leaves them as they were until the suggestion is applied again, and a library change is not tracked (re-apply
  replaces both pairs of triggers in place).
- A project whose master model is generated before the derivation is applied gets the triggers from the apply; one generated after gets them from the generator; one that exists but whose
  entity is promoted later gets them at the regeneration that promotes it.
- No master-side check that the stored values equal what the triggers would compute.

**Two defects of lot P1 fixed in the same lot (`DerivationApplier`).**

- *A failed RE-apply* (the suggestion was already APPLIED, its triggers already published) used to go through `writeApplyFailure` - status back to ACCEPTED, `appliedModel` kept - and its
  rollback took both triggers off the model DRAFT by name, so the next `publish_data_model` of that model, for any reason, would have silently dropped the published triggers. Now
  (`capability.DerivationApplyFailure`, pure, tested): the declarations are left alone, the row STAYS `APPLIED`, the previous bodies are written back to the scripting draft from the stored
  `scriptBody` (`DerivationBodyBuilder.splitStoredBodies`; the stored text now carries an unambiguous separator line, `STORED_BODY_SEPARATOR`, and the earlier form is still read), the model is
  republished only when this round had already published (then the previous bodies must become live again), and `applyMessage` is the failure text followed by the re-apply marker of section 11
  (`DerivationParameters.failedReapplyMessage`; `isReapplyMarker` also accepts that form, so the wizard keeps saying "apply again"). A stored body that cannot be told apart reliably is not guessed:
  nothing is restored and the message says so. A failed FIRST apply keeps the old rollback (unset by name, republish only if published this round, back to ACCEPTED). The same decision serves the
  master part (the triggers were "already there" when the list said so; previous sources read back with `get_script(includeSource=true)`).
- *`prepare` throws before `PipelineSteps.begin`*, so a refusal (a parameter still to be provided, a vanished attribute) was never stored: `apply` now stores it in `applyMessage` (no PipelineStep:
  nothing was attempted), only for an ACCEPTED or APPLIED derivation of that project, and an APPLIED row keeps its re-apply marker next to it.

**Not verified without the live instance**: that the generator's planned fields (`MasterModel.Result.fields`, used because the model is still a draft there) agree with the live table the apply path reads
with `get_data_model`; the master publish through `publish_data_model` alone (no `ensureMameAddon`/`ensureEmbedded`, as the toolbar republish does) on a model that carries the add-on;
the table-aware read of `list_script_table_triggers` (`tablePath` is the measured key; an entry without one is accepted); two tables of the master model deriving an attribute of the same name; that
Match and Merge's golden creation and merge fire the triggers (situations (b)); the `UP_TO_DATE` republish cost on a large master; a failed re-apply restoring both bodies on the scripting server and
the row staying APPLIED with the failure text and marker; the stored `scriptBody` of an apply made before this lot being split by the legacy rule; the effect of the master triggers on a master load
of a large table (per-row overhead; staging measured about 0.05 ms per written row).


## 13. Lot M10c, 2026-10-18: the person name key no longer depends on the model remembering to propose it

**Measured motive** (seventh end-to-end run, 2026-10-18, scenario of `tools/e2e/b2c_master.py`, three source systems). The same person entity (a first-name and a last-name attribute) gave two
outcomes: the project `E2EMASTER20261018014728` had the model's own derivation `Fasttrack.lib.names.sortedKeyOf(first, last)` (binding `{"firstName":"prenom","lastName":"nom"}`, precision of the
matching pairs 0.97); the project `E2EMASTER20261018015240` (simulate-first variant) had none for the entity `Client`, the matching plan found no exact name key and the precision fell to 0.67. The
open-ended capability run proposes among a score of other things; a key the matching plan depends on cannot be left to that variance.

**The rule** (`capability.NameKeyRule`, pure, `NameKeyRuleTest`). The rules side of the capability pass proposes the derivation itself when ALL of these hold, otherwise it says why it does not (the
`nameKeyRule` node of the entity's block in the `suggest-llm` answer: `status` `PROPOSED`, `SKIPPED` + `skipped`, `NO_PAIR`, `NOT_ANSWERED`, `NOT_PROPOSABLE`, `FAILED`):

1. the published library `Fasttrack.lib.names` offers `sortedKeyOf` with exactly two text parameters and a text result (`NameKeyRule.findFunction`; library absent, function missing or signature changed
   = `SKIPPED LIBRARY_ABSENT`, the same refusal as any proposal the inventory cannot back);
2. the entity is eligible (`NameKeyRule.eligible`, lot M11 below: not rejected, not superseded, not absorbed by a canonical entity; promotion is NOT asked) - else `NOT_ELIGIBLE` (it read `NOT_PROMOTED` in lot M10c);
3. no derivation of that library exists for the entity: neither proposed by the model in this run, nor stored by an earlier run in ANY status, a rejected one included (a person's refusal stands) -
   else `DERIVATION_EXISTS`. The test is the library, not the exact pair of attributes: a derivation of the names library on a full-name attribute already is somebody's decision about how the name is keyed;
4. at least two candidate attributes: STRING, accepted, not the technical key, not computed (`derivation` empty), not a reference to another entity - else `TOO_FEW_CANDIDATES`;
5. two of them are declared the first name and the last name (below).

It then writes exactly the two rows the model's own proposal writes (`StagingAttribute` with `derivation = SCRIPT`, `derivedFrom` = the first-name attribute, and a `CapabilitySuggestion` of nature
`SCRIPT_DERIVATION`, zone `STAGING`, binding `{"<parameter 1>":"<first name attribute>","<parameter 2>":"<last name attribute>"}` with the library's own parameter names), but with **origin `RULE`**, confidence
**70**, new attribute `personNameKey` (`personNameKey2`... if an attribute of the entity, any status, already has the name), label and rationale in the person's language (bundle keys
`capability.nameKey.label` / `capability.nameKey.rationale`; the rationale says the model did not propose it and why a name key matters for matching). `crossSystemKey` is set like for the model's keys, so
`DerivationEvidence` decides the STORED confidence from the measured coverage: 85 when both attributes are filled on every system feeding the entity (the level of the rule-proposed fold keys, so every bulk
accept treats the key alike), 70 otherwise. Accept, apply, backfill, master triggers and the matching pass's measure of the key are the existing ones; nothing changes there.

**How the first and last name are recognised.** The Factory stores no first-name/last-name role on an attribute (checked in `FactoryPaths.StagingAttribute`, the XSD and the mapping tables; the catalogue's
`PERSON_NAME` tag does not tell the parts apart and sample-file sources carry none), and Java never matches an attribute or column name (language-neutral by design). The pair is therefore an INPUT of the
rule, `NameKeyRule.NameParts`, and its only producer today is `capability.NamePartsPass`: ONE closed question per entity the rule can serve (steps 1-4 are checked first, so an entity the rule can never serve
costs no model call), through a single local tool `declare_name_parts` (`hasNamePair`, `firstName`, `lastName`, `reason`) whose answer can only be attributes of the candidate list (the tool's schema enumerates
them, and the session refuses anything else, the same attribute twice, a missing half, with the list in the refusal; three identical refusals end the run). `hasNamePair=false` is a valid answer: a single
full-name attribute, a company name, no person name. The prompt shows names, labels, types, lengths, profiles and value SHAPES under the project's data policy exactly as the capability pass does (no value
ever leaves). What changed against the open-ended run is the shape of the question - one closed choice among the entity's own attributes instead of one proposal among many - and everything after the answer
(whether a key exists, its name, binding, origin, confidence) is deterministic. If a later staging pass stores the roles, it builds the same `NameParts` and `NamePartsPass` becomes unnecessary.

**Not covered / not verified.** A full-name attribute on its own (`sortedKey`) and the three-input `sortedKeyAny` are not proposed by the rule (an entity fed by both kinds of system keeps what the model proposes);
the rule is not checked against the live model (no run was made from this worktree): the first end-to-end run after deployment must show `nameKeyRule.status` and, on the simulate-first variant, the key with origin
`RULE`. A name key the rule wrote is not withdrawn by a later run (a stored derivation stands, rule 3).

### 13.1 Lot M11, 2026-10-18: the rule never fired - it asked for a promotion that is decided later

**Measured.** On the project `E2EMASTER20261018065557` every entity answered `"nameKeyRule":{"status":"SKIPPED","skipped":"NOT_PROMOTED"}`. The capability pass runs BEFORE the master model is built
(`MasterModel.buildFromStaging` only promotes entities that are `ACCEPTED`/`APPLIED`, and at that point the person entity is still `PROPOSED`), so "reaches the master zone" was false for
every entity at the only moment the rule runs. The model's own key derivations (`mobileE164` on the same entity) are proposed with no promotion test.

**Changed.** The eligibility is the one those derivations use (`NameKeyRule.eligible(status, absorbedByCanonical)`): the entity is neither `REJECTED` nor `SUPERSEDED`, and no other entity still in
play lists it in its `memberEntities` (a member a canonical entity absorbs is kept in staging only, a key there serves nothing). Promotion is not asked. `Skip.NOT_PROMOTED` became `Skip.NOT_ELIGIBLE`;
the other preconditions, the closed question about the roles, the origin `RULE`, confidence 70 and the stored rows are unchanged (`NameKeyRuleTest`).

**The end-to-end check follows the library, not the name** (`tools/e2e/e2e_lib.name_derivation_attributes`): "name keys filled" (step 6 of the B2C scenario) now takes every live attribute that a live
`SCRIPT_DERIVATION` suggestion computes with the names library (`targetLibrary` / `targetImportName` ending `lib.names`), whatever its origin (`RULE` or `LLM`), its function (`sortedKeyOf`...) and
the attribute's own name (`personNameKey`, `nameMatchKey`, `fullNameKey`...); the older readings (derived from a mapped name column; a derived attribute whose name says name and key) stay. The check is
skipped, not failed, only when none of the three finds anything. Stand-in: defects `namelib` (a key found by its library alone) and `nonamekey` (skipped). **Not verified live**: the next run after
deployment must show `nameKeyRule.status = PROPOSED` and the key with origin `RULE` on the person entity.
