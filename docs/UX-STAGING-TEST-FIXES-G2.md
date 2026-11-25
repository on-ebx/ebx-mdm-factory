# Staging test fixes, lot G2 - the reason on the card, absurd controls, normalisation on constants, function texts in the viewer's language

Written 2026-10-17, branch `fix/matrix-reasons-controls` (worktree `ebx-mdm-factory-matrix-reasons`, from `main` 4143edde).
Source: the live retest of project TiersB2CRetestFinal (MdmFactory rows StagingEntity 180-182, StagingAttribute 2664-2698, CapabilitySuggestion 223-240,
MappingStep 116-121; screenshot `test-staging-b2c-2026-10-17/retest2/04-champ-20pct-sans-raison.jpg`). Parallel lot G1 (`fix/derivation-apply-backfill`) owns
the derivation apply / backfill, the staging reload and the step-4 parameter input; nothing of it is touched here.

Status: coded, `tools/test_local.sh` green, **NOT deployed, NOT seen running**. No XSD change (bundle keys and Java only).

## 1. The reason a proposal was held back is on its card

**Measured.** `Achat.buyerFirstName` sat at 20 % in the "left to decide" list. Its card read only "Confidence 20 %, under the 60 % bar", while the proposal had
written the real reason as the FIRST sentence of its own rationale (lot A, `staging.ProposalChecks`, bundle `stagingProposal.check.*`): "To check: no source column
feeds this field, it would stay empty on every row (suggestion: reject it)." The matrix never read `StagingAttribute.rationale`.

**Fix.**
- `MatrixModel.Attr.rationale` (read by `MappingMatrixReader`), and a new pure class `mappingmatrix.MatrixNotes`:
  - `leadingCheck(rationale)`: recognises the two attribute findings of lot A (`duplicatesRelated`, `noSource`) at the START of the text. It builds a regular
    expression from the bundle sentences themselves in English AND French (each `{n}` a lazy wildcard, apostrophes tolerated either way), so a row written in either
    language is found without a marker and without reading a name. The sentence, whether it proposes rejecting (both do), and the rest of the rationale come back.
  - `valueCheck(rationale)`: the " || Value check [was N]: ..." tail of lots D/E (`staging.CheckNote`), without its marker. It never proposes anything.
- **The queue line** (`#mdmmQueue`): the check sentence is shown ahead of the generic reason, in its own element (`mdmmQCheck`, amber, semibold). A field link
  shows its value check, labelled "Value check: ..." (`mappingMatrix.check.valueCheck`, en/fr).
- **Reject as the primary action** when the check proposes rejecting (a field with a lot A check): the Reject button takes `mdmmBtnPrimary`, Accept does not - on the
  queue line and in the inspector (`it.checkReject` in `itemButtons`). The person still decides; a value check never changes the buttons.
- **The inspector**: `mdmmShowItem` prints the check as a highlighted block (`mdmmInspText mdmmAttn mdmmCheck`) before the generic reason, then the proposer's own text
  (the rationale without the check sentence) in muted type. The cell inspector gets the same two blocks (`check` = the field's check, `valueCheck` = the link's note);
  the cell's `rationale` is now the base text, so the note is no longer printed raw inside it with its `||` delimiter.

Tests: `MatrixNotesTest` (recognition in both languages and with arguments, not-a-check cases, value-check parsing, the rendered queue line and button order, the item
and cell JSON, the script's use of `check` / `checkReject` / `valueCheck`).

## 2. Absurd LLM controls (capability pass)

**Measured.** Suggestion 228: `regex.matches "[0-9]{9}"` on `AdresseClient.addressType`, whose only mappings are `constant(HOME)` / `constant(BUYER)` (accepted); 227: a
timestamp regex on `country`, profiled through a 0 % alternative mapping that reads a `createdAt`-like column (the capability pass kept the LAST mapping it read per
attribute); 233: `Fasttrack.lib.names.sortedKeyOf`, a name KEY, positioned as a constraint (its own rationale says it does not check completeness).

**Where.** `capability.CapabilityLlmPass.Session#suggestScriptConstraint` - the one place a constraint suggestion is recorded. A refused call is never stored (the pass's
own refusal discipline: the message goes back to the model, three identical refusals stop the run), so the model can correct or drop it. Generic and structural: a
function's return type, whether an attribute is fed only by constants, and the pass rate of a literal regular expression on the attribute's own values. Nothing reads a
name or a business term. New pure classes `capability.ConstraintSanity` (the guards) and `capability.ValueEvidence` (what Java knows of an attribute's values).

| Guard | Rule | Message to the model |
|---|---|---|
| return type | a function must return a verdict (`boolean`) or a report (`json_object` - the real `validate(...)` entry points, kept since the 2026-09-21 coordinator fix). Any other declared type (string, decimal, date, a collection) computes a value and cannot flag a record. An unparsed return type is never refused. | "returns 'string', a computed value ...: propose it with suggest_script_derivation, otherwise do not propose it" |
| constants only | every mapping of an attribute the check reads (the `attribute` argument and every attribute of the binding) is a `constant(...)` pipeline (the LAST step is the constant): a closed set of texts the proposal chose. | "attribute 'addressType' is fed only by constants ('HOME', 'BUYER') ... Do not propose a constraint on it." |
| 0 % pass rate | for `Fasttrack.lib.validator.regex` `matches` / `matchesIgnoreCase` with a literal `pattern`: the pattern is run in Java (whole-string match, `(?i)` for the ignore-case variant) on the distinct values sampled for the attribute, weighted by rows; 0 % = it would flag every record. Not judged when nothing was read, the pattern does not compile, or the function is anything else. | "the pattern '...' matches none of the N distinct value(s) measured on 'x' (0 % of its rows) ..." - counts only, never a value |

Evidence (`ebx.CapabilityLlmSuggester`): `AttributeInfo.evidence` (never put in the prompt) is the sampled distinct values of the attribute's mapping, or the constants when
every mapping is a constant. The values are now read whatever the data policy level (what the policy withholds is what reaches the MODEL; `shapesFor` still filters): the same
stance as the normalisation lot's value profile - the check runs inside the instance. Cost: one extra distinct-values read per attribute for a sensitive column or level NONE.

The attribute is now read from its **most trusted mapping that reads a column** (`preferredMapping`: a pipeline that reads its column beats a constant, then the higher
`AttributeMapping.confidence`, a tie keeps the later row as before) instead of whichever came last - this alone removes the root cause of 227. `QualitySuggester.MappingRow` gets
`confidence`. The system prompt states the rules (verdict or report only; constants and unsatisfiable patterns are measured and refused).

Not done: a constraint whose attribute is fed through a low-confidence mapping is not capped (the better mapping choice above covers the measured case); only the regex
library's `matches` is evaluated in Java - other libraries' verdicts cannot be computed without running EBX Script. The sampled values are the landing column's raw values (before the
mapping's steps), an approximation of what the staging will hold.

Tests: `ConstraintSanityTest` (the three live cases rebuilt with made-up values, evidence building, `constantOf`, pass rate, prompt wording, no value in the message);
`CapabilityLlmPassTest.scriptAnyReturnTypeIsAccepted` now pins that a decimal-returning function is refused (json_object stays accepted: the IBAN tests).

## 3. Normalisation steps are never put on a mapping that does not read its column

**Measured.** MappingStep 120 (`TITLE_CASE`, accepted) on mapping 2820 whose pipeline is `constant(HOME)`: the pass profiled the landing column the mapping nominally
reads (identifiers in capitals) and changed the case of values the staging never sees.

**Fix.** `staging.NormalisationPass.readsColumn(pipeline, sourceColumn)` (pure, public): false for a pipeline with a `constant`, `now` or `runid` step, a `golden` /
`mdmstate` step, or a `concat` that does not name the column (a leading `/` or `$.` and case ignored); true for a blank pipeline and any other. `NormalisationPass.propose`
leaves such columns out of everything - the single-column steps, the case alignment across sources and the codes to align - and `NormalisationSuggester` skips them while reading
the project's mappings and does NOT add them to the covered set, so an own step still PROPOSED on one is withdrawn by the existing orphan sweep. A step already ACCEPTED (the
bulk accept took it) stays: nothing here rewrites a decision.

Tests: `NormalisationPassTest.mappingsThatDoNotReadTheirColumnGetNoStep` (the helper's table, no step on a `constant(HOME)` mapping over capital identifiers, no effect on the
alignment of the real sources, the same column read for real still gets its step).

## 4. A computed field's texts in the viewer's language

**Measured.** The "What it computes / When it is empty" sections of a computed field read the English doc comment of the library function. The library's `documentation` is
localized per language but only for the library as a whole (verified on `Fasttrack.lib.names`, `.phone`, `.address`: `documentation[]` en-US / fr-FR / it-IT); the documentation of
a FUNCTION lives only in the comment above it in the source, one language.

**Fix.** `mappingmatrix.MatrixFunctionTexts`: bundle keys `capability.fn.<import name>.<function>.description | .returns | .param.<name>` (import name from the suggestion's
`targetLibrary`, `library/Fasttrack/lib/names` -> `Fasttrack.lib.names`). For the viewer's language a bundle text that DIFFERS from the English one is the translation and wins;
otherwise the library's own comment is shown (an English viewer always reads the library - English and current; a function the bundle does not know keeps its library text). Used by
`MatrixComputed` for the cell's second line, the inspector's "what", "when empty" and per-argument texts. Texts written (en + fr, in the "matrix reasons and controls (2026-10-17)"
block at the end of both bundles): `Fasttrack.lib.names` `sortedKey`, `sortedKeyOf`, `sortedKeyAny`; `Fasttrack.lib.phone` `toE164`, `toE164WithDefault`, `normalize`, `countryOf`,
`nationalNumberOf`, `callingCodeOf`, `countryOfCallingCode`; `Fasttrack.lib.address` `buildFullAddress`, `normalizeLocal`, `orEmpty`. The English texts are the library's wording at the
time of writing (they are the reference a translation is told apart from, and are never shown). A new library function needs its key block to be translated; until then it reads
in the library's language.

Tests: `MatrixFunctionTextsTest` (French bundle text, English library text, unknown function falls back, library unreadable, the cell and the inspector JSON in both languages, every
listed function has a French text that differs from its English one).

## Files

New: `mappingmatrix/MatrixNotes`, `mappingmatrix/MatrixFunctionTexts`, `capability/ConstraintSanity`, `capability/ValueEvidence`; tests `MatrixNotesTest`, `MatrixFunctionTextsTest`,
`ConstraintSanityTest`.
Touched: `mappingmatrix/MatrixModel` (`Attr.rationale`), `mappingmatrix/MatrixHtml` (queue line, item and cell JSON, script, CSS), `mappingmatrix/MatrixComputed`,
`mappingmatrix/MatrixMessages` (`find`), `ebx/MappingMatrixReader`, `capability/CapabilityLlmPass` (`AttributeInfo.evidence`, the two guards, system prompt),
`ebx/CapabilityLlmSuggester` (evidence, `preferredMapping`), `ebx/QualitySuggester` (`MappingRow.confidence`), `staging/NormalisationPass`, `ebx/NormalisationSuggester`,
`Messages_en` / `Messages_fr` (one block at the end); tests `CapabilityLlmPassTest`, `NormalisationPassTest`.

## Deployment

**No XSD change, no module.xml change: no data-model restart.** Java classes load once: the usual Tomcat restart at deployment. Nothing was installed or run live from this lot.

## Not verified

- Nothing ran under Tomcat or in a browser: the amber check line, the primary Reject button and the inspector blocks are verified on the rendered HTML and the parsed script only.
- The recognition of a lot A sentence depends on the bundle text of that sentence: rewording `stagingProposal.check.*` later makes older stored rows unrecognised (they still show
  their full rationale elsewhere) - the two keys are read by `MatrixNotes`, change them together.
- The 0 % guard measures the landing column's sample (2000 distinct values at most), not the staging values after the mapping's steps.
