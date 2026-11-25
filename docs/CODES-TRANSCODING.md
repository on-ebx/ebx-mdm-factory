# Codes to align become a reviewable transcoding proposal (lot H3)

Written 2026-10-17, branch `feature/codes-transcoding-proposal` (worktree `ebx-mdm-factory-evo-codes`, from main `a11e0517`).
Follows lot D (`docs/UX-STAGING-TEST-FIXES-D.md`, sec 2.2: "codes to align ... are flagged for a person, counts only, no mapping invented").

## 1. What was missing

Two sources that feed one attribute with a short repeated list of codes written differently - the loyalty file's title `M.` / `Mme`
against another system's `MR` / `MRS` / `MISS`, its level `Bronze` / `Argent` / `Or` against `L1` / `L2` / `L3` - only got a sentence on
the links ("codes to align: 2 distinct in LOYALTY, 3 in PARTNER, 0 in common out of 5") and a confidence under the bulk-accept bar. The stored
values then differed by source, and matching, grouping and validity rules saw two vocabularies.

`TRANSCODE_REFERENCE` only worked against a configured `ReferenceBinding` (flat or scoped lookup), and lot D proposes it only when one resolves
at least 95 % of the column. Nothing wrote a table of codes of its own.

## 2. Design

Nothing here reads an attribute or column name, a tag or a business vocabulary (CLAUDE.md sec 15, the product owner's rule): the detector is lot D's, structural;
the correspondence is the **language model's** call; Java only checks the answer against the values.

```
 staging/NormalisationPass       (lot D, small edit) alignCodes also hands over the sources and their codes; a source that already has
                                 a code table is compared by the codes it maps TO; Column.codeMap / entityName
 staging/CodeTranscodingPass     pure: the prompts, the tool propose_code_transcoding, the Session that validates the answer, the drafts
 quality/CodeMap                 pure: the table a step carries (parse, lookup, coverage, edit)
 quality/CleaningSteps           TRANSCODE_REFERENCE resolves against an argument that carries its own table
 ebx/CodeTranscodingSuggester    reads the policy, runs AgentRuntime once per business object, writes the MappingStep rows
 ebx/NormalisationSuggester      (small edits) Outcome.alignments; the table of a mapping is verified on the values
 ebx/NormalisationHooks          afterLlmPass: ask the model, then re-run the (cached) normalisation to verify and flag
 ebx/CodeTableEdits              the matrix's Edit: writes the person's targets back to the step
 mappingmatrix + MappingMatrixReader + MappingMatrixUserService     the card: two-column table, Accept / Edit / Reject
```

### 2.1 Where the proposal lives: a `MappingStep`, kind `TRANSCODE_REFERENCE`, with the table as argument

The brief offered two carriers; the choice is the step, for four reasons read in the code:

1. The loader already runs a mapping's ACCEPTED steps in position order, before its pipeline (`StagingLoader#readCleaningSteps`,
   `CleaningSteps#applyPipeline`). The matrix's sample, `StepChainCheck`, `QualitySuggester` and the verifier call the same engine. A `map(...)` would be
   executed as well - through `AttributeMapping.pipeline`.
2. That field is the mapping's ONE pipeline, which the staging model may already have filled with its conversion (a `trim|date(...)`), so a `map(...)` would
   have to be spliced into somebody else's text, and accepting it would be accepting the whole link.
3. A `MappingStep` is a reviewable item of its own: it is in the proposal review, counted in the "decisions left", decided by the same
   `ProposalReviews#decide`, and can be rejected without touching the link.
4. Two sources of one attribute each get their own step, with their own evidence and their own decision.

`CleaningSteps#transcodeReference` gets one new branch: an argument that parses as a `CodeMap` is resolved against its own table; any other argument goes the
lookups' way, unchanged. The argument (at most 2000 characters, the field's limit - the XSD documentation of the field is updated, no structure changes):

```
{"codes":{"Bronze":"BRONZE","Argent":"SILVER","Or":"GOLD"},"targets":["BRONZE","SILVER","GOLD"],"counts":{"Bronze":50,"Argent":38,"Or":52},"basedOn":"TIER"}
```

`codes` is observed code -> target (empty = not mapped yet); `targets` the canonical list every source of the attribute is mapped onto; `counts` the rows each code
had when the table was proposed (what the card shows, without reading the landing); `basedOn` the `ReferenceBinding` kind the targets come from, if any
(the key is not `reference`, which already names a lookup).

Matching is `CleaningSteps#normalizeKey` (case, accents, punctuation, white space ignored - `M.` = `m`), except for a code made only of punctuation. An unlisted
code is left as it is and counted *unresolved* - the lookup steps' rule, never invented. Two spellings with one key and two targets are a conflict: neither resolves.

One step per source column whose codes change. A source that already writes the common list (an identity table, every code mapped) gets none. The common list is
repeated in each step (`targets`); the person edits one source at a time.

"A new small reference": the target list is the `targets` of the steps, kept in the factory's tables. No EBX reference table or dataset is created by this lot
(that needs a data model and a dataset, and a person's decision on where they live); "reuse an existing reference table of the instance if one fits" is the
configured `ReferenceBinding`s: the model is shown their kinds and a few of their codes and may name one, in which case its targets must be codes of that reference
(checked with the full code list).

### 2.2 When it runs

`NormalisationHooks#afterLlmPass` - the stage "language-model structure pass" of the one-click proposal, which only runs when a model is configured - after the
normalisation pass of that stage (the model has just unified the sources, so the attributes fed by several of them exist):

1. `NormalisationSuggester.run` returns its `Outcome.alignments` (lot D's detector, now with the sources and their codes).
2. `CodeTranscodingSuggester.suggest`: attributes where a source already has a code table, **in any status, a rejected one included**, are left alone (a row a person
   decided on is never touched; the counts-only flag stays on the links); the others are grouped by business object and put to the model, one `AgentRuntime` call
   per object (`PipelineStep` `CODES_TRANSCODE_LLM`, `AgentRun` phase `CODES_TRANSCODE_LLM`).
3. The valid answers are written as `PROPOSED` steps, origin `LLM`, with the confidence of sec 2.4, evidence `valuesSeen` / `valuesChanged` / `valuesUnresolved` and examples as shapes.
4. If anything was written, the normalisation pass runs again (columns cached): it **verifies the tables on the values** and flags the links they leave incomplete
   (sec 2.4), and the attribute is no longer reported as misaligned since its sources are now compared by their target codes (the detector skips the case step of a
   column with a table and compares `CodeMap#apply`-ed values).

Without a language model nothing of this runs; the detector's counts-only flag stays, as in lot D.

### 2.3 What the model receives, by sharing level

`LlmDataPolicy` has no "short code lists" allowance (a code is a value), so the code-list pass follows the same two settings as the other passes: the effective
level is the more restrictive of `Settings.llmValueSharingMax` and `Project.llmValueSharing`, and a column on the sensitive list never gets more than counts.

| Level for the attribute | Per source, the model sees | Its answer | What is written |
|---|---|---|---|
| `SAMPLE_VALUES`, and neither the attribute nor a source column is on the sensitive list | the codes with their rows (at most 30 distinct, lot D's detector) | targets + the correspondence of EVERY code of EVERY source | the complete table |
| `SHAPES_ONLY` (the default), not sensitive | the SHAPE of each code (`Aaa`, `A.`) with its rows | the target list only | the observed codes (Java knows them) on the left; a target only where the code already IS a target (same key), or the chosen reference resolves it; the rest empty, for a person |
| `NONE`, or the attribute / a source column is sensitive (whatever the level) | the number of distinct codes and of rows | the target list only, from the attribute's name and label | same as the row above |

Reference lists (kind, number of codes and
the first 40) are instance configuration, not project data, and go at every level. Everything that comes from the sources - attribute, business object, source and
reference names, codes, shapes - sits between `PromptData` fences; the system prompt carries the notice.

**An unlisted code is a failure**, as `map(...)`'s rule: under `SAMPLE_VALUES` the tool refuses an answer that leaves a code of any source without a target (naming the
missing ones, which the model was shown), a target that is not in its list, a source with no entry, two targets for one code, a list over 60 codes or a table too long
for the step's argument; nothing is recorded and the model is told to send again. Correspondences sent when the codes were not shared are ignored.

### 2.4 Verified on the values; flagged at 59 or under

- Each draft is measured when written: the model's confidence is kept (at most **85**: a person still reads it) only when the table covers every row of the source;
  otherwise the confidence is the share of rows covered, **at most 59** - one under the 60 % bar of "accept all", like lot D's flags.
- On every normalisation run, for a mapping still `PROPOSED` that has a table, the table is run over the values that reach it (the column's values after the steps
  before it) with `CodeMap#cover`. A code with no target (a value that appeared since, a target a person emptied, the targets the model could not give without seeing the codes)
  flags the link: confidence capped at 59 and a "Value check" note with counts only (`code table: 63 of 141 rows (1 distinct codes) have no target code ...`); a
  spelling collision is flagged too. A later run that verifies clean removes the note and restores the confidence (`CheckNote`).

### 2.5 The matrix card

`MappingMatrixReader#readCodeLists` reads the mapping's non-rejected steps that carry a table (their rows and counts come from the argument: no landing read);
`MatrixModel.Cell#codeLists` -> `cellJson` -> a new block in the inspector (`codeBlock`, added as a wrapper of `mdmmSelect`, so the cell inspector above it is untouched):

```
Code table   [To validate]  Confidence: 59%
Common list: MR, MRS, MISS
63 of 141 rows have no target code yet.
 Source code | Target code | Rows
 M.          | MR          | 77
 Mme         | (to choose) | 63
 [Accept] [Edit] [Reject]
```

- **Accept / Reject** post `ACCEPT_CODES` / `REJECT_CODES`: `ProposalReviews#decide` on the `MAPPING_STEP`, as every other step (the accepted table is what the loader runs).
- **Edit** turns the target cells into text boxes; **Save** posts `SAVE_CODES` with `{"<observed code>":"<target>"}` (the code read from a `data-` attribute, never
  from a JS string). `CodeTableEdits#save` re-checks everything server side: the step must still be `PROPOSED`, a code outside the table is ignored, a collision or a
  table over 2000 characters is refused. The step then belongs to the person (origin `USER`, "Edited by a person." added to the rationale), the evidence and
  the confidence are recomputed from the table's own counts (complete: kept, between 60 and 85; incomplete: the share covered, at most 59). Edits stay in the
  factory's `MappingStep` table; no reference table of the instance is written.
- The proposal review and the "decisions left" list read the step as "Map the 2 codes of this source onto the common list (MR, MRS, MISS)" (`ProposalWording`).

## 3. Files

New: `quality/CodeMap`, `staging/CodeTranscodingPass`, `ebx/CodeTranscodingSuggester`, `ebx/CodeTableEdits`; tests `CodeMapTest`, `CodeTranscodingPassTest`;
`tools/matrix_codes_test.js`.

Touched outside new classes (all small): `quality/CleaningSteps` (+ the own-table branch, `Lookups#codesOf`), `staging/NormalisationPass` (alignment members, `Column#codeMap` /
`entityName`, compared by target codes), `ebx/NormalisationSuggester` (`Outcome.alignments`, `codeTableOf`, `verifyCodeTable`), `ebx/NormalisationHooks` (`afterLlmPass`),
`review/ProposalWording`, `mappingmatrix/MatrixModel` + `MatrixHtml` + `ebx/MappingMatrixReader` + `ebx/ui/MappingMatrixUserService`, `tools/build_xsd.py` and the generated
`MdmFactory.xsd` (documentation of `MappingStep.argument` only), `Messages_en` / `Messages_fr` (one block "codes transcoding (2026-10-17)" at the end), `tools/test_local.sh`
(+ `matrix_codes_test.js`), `MatrixHtmlTest`, `ProposalWordingTest`, `PromptFencingTest`.

## 4. Deployment

**No restart is needed for the behaviour**: no table, no field, no enumeration value changes (`TRANSCODE_REFERENCE` and the origins exist). The XSD differs by the
documentation of `MappingStep.argument` only (read at the next start; nothing depends on it). Module classes follow the usual install.

## 5. Tested / not tested

Tested on unit data and on the real B2C loyalty file against a synthetic second loyalty source with other codes (title `MR` / `MRS` / `MISS`, level `L1` / `L2` / `L3`):
the detector's hand-over, a scripted "model" at each sharing level (complete table, left-out code, foreign target, reference reuse, shapes/none skeleton, limits), the
step engine on the real values (100 % covered), the prompts (levels, fencing of a hostile name or code), the card's JSON and its script (escaping, what Accept / Save post, run
in JavaScriptCore). **Not run live under Tomcat** (nothing installed, by instruction): `CodeTranscodingSuggester`, `CodeTableEdits`, the hook and the reader's new query are
EBX-bound and were compiled, not executed; the language model's real behaviour on codes is unmeasured.

## 6. Not done / to watch

- A source that already has a code table, in any status, is not asked again: a stale `PROPOSED` table (values changed since) is flagged by the verifier but not
  regenerated; to ask again, reject it and delete the step, or edit it.
- The common list lives in each source's step: after an Edit that adds a target, the other sources' steps do not learn it (the card shows each source's own list).
- The Edit does not refresh the link's "Value check" note: the next normalisation run does (a few seconds, columns re-read).
- No EBX reference table is created for a new common list (sec 2.1); a ReferenceBinding is the only way to reuse one.
- Codes of a typed attribute, more than 30 distinct values per source, and a column fed by a collection feed are outside lot D's detector, hence outside this pass.
- The model proposes from the codes and the attribute's name and label: a code it cannot interpret gets a low confidence and a rationale saying so, never a silent guess;
  the confidence shown is the model's own, capped.

## Accepting a table with a gap (2026-10-17)

A table with an observed code that has rows and no target code cannot be accepted (matrix, bulk accept, Proposal review, REST): fill it with Edit, or reject it. Details: `docs/UX-STAGING-LAST-POINTS.md` sec 3. The notes written about these tables are coded and worded in the viewer's language (sec 1).
