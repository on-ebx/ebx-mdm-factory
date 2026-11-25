# Staging last points - engine notes in the viewer's language, resolved reject groups, no incomplete code table accepted

Written 2026-10-17, branch `fix/staging-last-points` (worktree `ebx-mdm-factory-last-points`, from `main` 10cd1b68).
Source: the live finding of the staging test (captures in `test-staging-b2c-2026-10-17/visuel/`). Three items, one commit each.
Parallel lot `feature/orphan-connection-cleanup` (ConnectionCleanup / FactoryResource) is not touched.

Status: coded, `tools/test_local.sh` green, **NOT deployed, NOT seen running**. No XSD change (bundle keys and Java only): no data-model restart; the
usual Tomcat restart loads the classes.

## 1. The notes of the staging passes are coded and worded in the viewer's language

**Measured.** A French matrix card read "Vérification des valeurs : codes to align: 2 distinct in LOYALTY, 3 distinct in CRM, 0 in common out of 5. No reference
or mapping exists for them..." and "Normalisation: 160 of 160 rows are phone numbers written with separators...": the label was French, the sentence English,
because the passes (lots D, E, G2, H1, H3) stored an English sentence and the screens printed it as stored.

**Design.** A note is an ordered list of SEGMENTS - a bundle key `staging.note.<key>` and its arguments - built by `staging.NoteTexts.Note`. The stored text is
the English sentence (what REST, MCP and logs have always read) followed by ONE marker of the existing `EngineMessageCodec`:

```
... gives a valid date (yyyy-MM-dd) for 0 of 140 rows. [mdmf:staging.note.seq|s:v.gives|n:4|s:%27isodate%28%29%27|s:%40%40f.type.DATE|n:0|n:140|s:dot|n:0]
```

key of a segment, number of its arguments, then the arguments. An argument that starts with `@@` names a fragment (a bundle key without arguments: a type, a
letter case, "the value as it is", "no transformation") so that words are translated and names (pipelines, sources, codes) are not. Segments are joined by a
space, except in front of one that starts with `, ; . )`. The English of every key lives once in `NoteTexts.ENGLISH`; `Messages_en` says the same and a test pins
it (`NoteTextsTest`), as `Messages_fr` has every key.

A screen words a stored rationale with `wizard.RationaleText`: `display` (a whole rationale: the proposer's own text, then ` || `, then the localized
"Value check: ..." label; the `[was N]` stays internal), `one` (a text that is a note, with the free text that followed it - the language model's own words in
a code table step), `valueCheck` (the note alone, for the matrix). A row written before this change has no marker and stays readable (plain English); REST and
MCP readers get the English with the marker removed (`ProposalReviews.lines(..., Locale.ENGLISH)`), the only difference being that the review text no longer
prints `[was N]`.

**Families covered** (every builder of these passes):

| Family | Builder | Where it lands |
|---|---|---|
| verifier sentence (gives a valid date for N of M, drops the leading +, empty / lost +, sample, "converts all of them", ambiguous day/month, no pattern) | `TransformVerifier#sentence` (`Result.note`) | `AttributeMapping.rationale` behind the Value check delimiter (`NormalisationSuggester#verify`, `StagingLlmPass#typedOutcome`) |
| fixed / withdrawn / repaired / chain drops the + / redundant step | `NormalisationSuggester` (`reconcileChain`, `repairStripChars`) | mapping or step rationale |
| code table gaps and conflicts | `NormalisationSuggester#verifyCodeTable` | mapping rationale |
| codes to align | `NormalisationPass#alignCodes` (`Alignment.note`) | mapping rationale |
| preflight fix | `StagingPreflight#applyFix` | mapping rationale |
| "Normalisation: ..." step rationales (trim, collapse, empty-to-null, phone separators, reference, case alignment, sample suffixes) | `NormalisationPass` + `quality.Detectors` (`StepProposal.note`; also the quality stage's steps) | `MappingStep.rationale` |
| "Code transcoding: ..." step rationales | `CodeTranscodingPass#noteOf` (the model's words stay free text after the note), edit sentence by `CodeTableEdits` | `MappingStep.rationale` |

Writers keep the marker whole: `CheckNote.with(rationale, Note, was, max)` (the text gives way to the marker; no room for it = plain English), `Note#stored`,
`EngineMessageCodec.fit` in `StagingLlmSuggester`.

**Screens.** Mapping matrix (cell inspector `rationale`, `valueCheck`, `altRationale`; the queue line and the inspector of a "left to decide" link:
`MatrixNotes.valueCheck(rationale, locale)`), step 4 lists and Proposal review (`ProposalReviews` words the mapping and mapping step rationales for the lines
it builds, so `ReviewPage`, the wizard's lists and `StillToDecide` print them as they come), the Feed designer's suggestion rationale.

Tests: `NoteTextsTest` (bundle = built-in English, French for every key, round trip with hostile characters, the verifier case of the live finding, the codes-to-align
sentence of the live finding word for word in both languages, cut / no room, old rows, normalisation and code table steps, the edit sentence, REST view),
`MatrixNotesTest` (the coded note in the French queue line, item data, cell data and `valueCheck(locale)`); the existing suites (`CheckNoteTest`,
`TransformVerifierTest`, `NormalisationPassTest`, `CodeTranscodingPassTest`, `DetectorsTest`, `StagingLlmPassTest`) pass unchanged: the English is byte for byte what it was.

Not covered: a coded note is worded when a screen reads it; the language model's free text (its rationale, a code table's reasons) is shown as the model wrote it.

## 2. The reject review no longer lists resolved reason groups like open ones

**Measured.** After the date fix the 140 loyalty date rejects were resolved by the next load, yet "Pourquoi les lignes ont été mises de côté" listed them with the
same "Voir ces lignes" button as a problem still to look at. The group query counted every row the filter matches, whatever its status.

**Fix** (`RejectSql.groups`, `RejectReview.Group`, `RejectReviewPage`, `TransferRejectReviewUserService`):
- the group query reads OPN / ACK / RSV (open, acknowledged, resolved rows) of each group along, and orders the groups that still have unresolved rows first
  (`ORDER BY SUM(CASE WHEN not resolved ...) DESC, COUNT(*) DESC`); checked read-only on the live instance (Calcite accepts the aggregate in ORDER BY; the 140-row group
  came back as `OPN 140, ACK 0, RSV 0`);
- the screen splits the groups: those with rows left to look at stay in the main table, the "Show these rows" button is the primary one and asks for any status;
  the counts per group read "12 ouvertes, 3 prises en compte, 140 résolues" (a zero is left out; a group built without the counts reads "N lignes" as before);
- a group whose rows are ALL resolved (`Group.resolvedOnly()`) goes behind a fold `Résolus (N)` with the sentence that a later load wrote them; its button is not primary
  ("Voir les lignes résolues") and sets the status filter to resolved; the fold is open only when the person already filters on resolved rows; no "what to do" line for
  it; when no group has rows left, a sentence says so;
- 10 pending and 10 resolved groups at most (20 are read in one query; "And N other kinds" counts the rest).

There is no "resolved on <date>" column on `TransferReject` (adding one would be an XSD change and a restart): the fold is the chosen form. List, CSV and REST are unchanged.
Files: `rejects/RejectSql`, `ebx/RejectReview`, `ebx/ui/RejectReviewPage`, `ebx/ui/TransferRejectReviewUserService`, `factory.css`, bundles (the same block at the end).
Tests: `RejectReviewPageTest.resolvedGroupsAreNotListedLikeOpenOnes` (fold, counts, buttons, open fold under the resolved filter, all-resolved sentence, acknowledged stays
pending), `RejectReviewLogicTest` (the SQL).

## 3. A code table with an observed code that has no target is not accepted

**Measured.** A TRANSCODE_REFERENCE table where M. and Mme read "(à choisir)" and "140 lignes sur 140 n'ont pas encore de code cible" could be accepted in the matrix, by
the bulk accept and in the Proposal review. Accepted, it leaves those rows written in the source's own vocabulary beside the common list.

**Rule** (`quality.CodeMap#codesWithoutTarget`, `review.ReviewRules#canAcceptMappingStep`): accepting is refused while an observed code that has rows has no target code - an
empty target, or two spellings of one code (case, accents, punctuation) that go to different targets. A code whose recorded row count is 0 does not block; a table that records
no counts is taken to have rows. A reference-naming step, another kind of step and every REJECT are unaffected; a complete table accepts as before.

**One door.** `ProposalReviews#checkAccept` has a MAPPING_STEP branch, so every path is refused the same way: the matrix's Accept (`ACCEPT_CODES`) and its bulk sweeps (counted
as skipped), the wizard's "Accept all" job (`StagingBulkDecideRun`: its sentence now says "N code tables still lack a target code for some codes: fill them in the Mapping matrix
first"), the Proposal review (single and bulk), the wizard's decision cards and the REST `staging/decisions` route (`ok:false`, `reason` in English: "Choose a target code for
M., Mme, then accept."). `DecisionResult` carries the bundle key of the refusal (`reasonKey`, `reasonArgs`, `reasonIn(locale)`), so the screens word it in the viewer's language:
"Choisissez un code cible pour M., Mme, puis acceptez." (key `review.refuse.codeTable`, at most 6 codes named then "(+N)").

**The card.** The code table block of the matrix inspector gets `blocked` and `fill` in its JSON (`MatrixModel.CodeList#missing`, read by `MappingMatrixReader` from the same rule):
Accept is disabled (with the sentence as its title), the sentence is printed under the "N rows of M have no target code yet" line, Edit and Reject stay. Filling the table
with Edit (`ebx.CodeTableEdits`) releases Accept at the next display.

**E2E.** `tools/e2e` assumed an incomplete table could be accepted. The stand-in factory now lists a missing code with an empty target (the shape the factory writes) and refuses its
accept (single and bulk); the scenario's decision step answers such a refusal by REJECTING the table (documented policy: it cannot fill one with Edit) and reports it
("incomplete code table(s) rejected, not accepted"); the hard scenario's step 5 expects a table with a gap REJECTED and any other ACCEPTED / APPLIED. Self-tests cover it.

Tests: `CodeTableAcceptGateTest` (the rule on made-up tables, the codes, the reason in both languages, the card JSON and script, the one door by source), `StagingBulkDecideRunTest`,
`tools/matrix_codes_test.js` (Accept disabled on the incomplete table only, the sentence once as text and once as title, Edit and Reject kept), `b2c_selftest.py`.
