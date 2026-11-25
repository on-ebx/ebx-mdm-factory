# Staging load and rejects (2026-10-17), lot B

Branch `fix/staging-load-rejects` (from `main` 4a8806c0). Source: the live test of project "Tiers B2C - retest landing" (`test-staging-b2c-2026-10-17/COMPTE-RENDU.md`, defects 2, 5 and the
rejects screen of 11). Lots A (proposal content), C (wizard step 4 page and matrix) and D (normalisation) are parallel; this lot owns the staging loader, the `TransferReject` data and
the review screen `TransferRejectReview`.

Status: coded, `tools/test_local.sh` green, NOT deployed, NOT seen running. **XSD change** (three optional fields on `TransferReject`: a Tomcat restart is needed, and the model
evolution adds the columns; rows written before them read as "other", uncoded). No `module.xml` change.

## What changed

1. **No orphan children.** A staging row EBX refuses to save (a date that does not convert) used to be rejected alone: its child rows, built from the same landing row, were still
   written (140 `CustomerAddress` rows of 140 rejected loyalty customers). Now `parent rejected: <the parent's reason>` rejects them too, and they are never written.
   - A row rejected while it is PLANNED (a failed check, a quality rule of severity REJECT, an unknown parent, a mapping error): `processRow` still computes the rows of its child
     feeds (their source id, their own checks, the empty-child rule) and rejects each with the parent's reason - an empty child stays silent, as for a good parent.
   - A row EBX refuses at WRITE time (the case of the test): the parent is flushed first; `flushTree` then takes the child rows computed from the refused parent (`RowPlan.parentKey`
     = the parent's formatted staging key) out of the write list and rejects them (`rejectOrphans`), before the children are flushed. Grandchildren pass the ROOT cause on (no
     "parent rejected: parent rejected").
   - **Why reject rather than "write and mark".** The loader links a child to its parent by the parent's staging key (`parentLinkField`, `addressKeys`...): a child written without its
     parent is a row whose foreign key points at nothing, and the master load, the matching and the counters would all have to know it is not real. Rejecting it keeps staging
     consistent (a row in staging has its parent in staging), puts it in the same review list as its parent with the same fix, and lets the next load write both once the parent's
     mapping is fixed. A child row that is UNCHANGED (already in staging from an earlier load) is left alone: it is not an orphan of this run.
   - The landing row whose ROOT row EBX refused is now marked `REJECTED` (it stayed `STAGED`, as if loaded).
2. **Coded reasons.** `rejects.RejectReason` (code + arguments), stored as the English sentence plus the `EngineMessageCodec` marker (`... [mdmf:rejects.reason.convert.date|s:birthDate|s:date_naissance|s:10%2F06%2F1963]`),
   so REST, MCP, logs and the CSV read plain English and the screens word it in the viewer's language. Codes: `convert.date|dateTime|integer|decimal|boolean|value`, `tooLong`,
   `mandatory`, `unknownParent`, `quality`, `parent`, `ebx.refused`, `compute`, `read`, `noKey`, `sourceIdEmpty`. A fielded reason names the staging field, the source column and the value.
   - `RejectClassifier` turns EBX's message into one of them: "ODateFormat[...]conversion error while handling input string '10/06/1963'" gives `convert.date` for the field whose
     value IS `10/06/1963` (and whose type agrees with the converter), with its source column (`FieldPlan.sourcePath`). Anything else stays `ebx.refused` with the raw text.
   - The loader's own checks (mandatory, length, unknown parent, quality rule, missing key, empty source id) are coded the same way.
   - **Double escaping.** EBX hands its messages back already HTML-escaped (`&gt; Caused by`); the screen escaped them again. The text is unescaped once where it is stored
     (`RejectText`) and escaped once where it is shown; rows written before this lot are recognised and cleaned at display.
   - New `TransferReject` fields: `category` (`QUALITY_RULE`, `EBX_REFUSAL`, `DATA_CHECK`, `COMPUTATION`, `PARENT_REJECTED`, `OTHER`), `reasonCode` (the signature of the first reason: code,
     field and column, never the value) and `detail` (the raw EBX text, folded on the screen).
   - **Review screen.** Each reject shows its sentences in the viewer's language and the raw text folded (`Technical detail`). A new section "Why the rows were set aside" groups identical
     reasons per entity ("140 rows - Customer: birthDate (source column date_naissance): date not in the expected format", what to do, an example, a "Show these rows" button that filters the list
     to that kind of reason: new filter `reasonCode`, `-` = rejects with no code). The groups are one aggregate query that follows the filter (10 shown, the rest said in a line). The CSV reads plain English.
3. **The true cause, for the wizard.** The load result carries, per entity, `entities[].rejectedBy` (`{"EBX_REFUSAL":140}`). `TransferReject.category` keeps it. See "Integration point for lot C".
4. **Read counter.** A child entity counts the rows it builds from its parent's rows as read (`FeedBuffers.read`, folded into the entity's counter by `flushOne`): "300 written of 0 read"
   becomes "300 written of 300 read". The root feed's own counter (per landing row) is unchanged. Because `read` now includes child rows, the load's total-failure verdict is no longer
   `read == rejected` but "something was rejected and nothing was written, unchanged or skipped" (`StagingLoader.isTotalFailure`): a root written with all its children rejected used to read
   as a total failure.
5. **Rejects screen.** "Back to the Cockpit" goes where the screen was opened from: `FactoryHtml.originBackLinkScript` reads the per-user origin token the wizard's links arm (the same one the
   Close button follows) and points the link at it - "Back to the wizard" when it is a wizard address (the wizard stores the URL of its current step, so step 4), "Back" for another screen, the
   Cockpit wording kept when the origin is the Cockpit, nothing changed without a token; a click gives the token back to its parent. The filter's labels sit above their controls (`.mdmf-rejects-filter`
   is a flex row of columns), not after the select.

## Integration point for lot C (wizard step 4)
- `ebx.RejectCauseReader.staging(session, factory, projectCode)` (one aggregate SQL over the unresolved STAGING rejects, the population of the "held back" number) returns `Causes`:
  `.project()` and `.entity(name)` are `rejects.RejectCauses` (`total()`, `count(category)`, `blamesARule()`, `sentence(locale)`).
  `RejectCauses.sentence(locale)` is the whole sentence, e.g. "140 rows set aside (refused by EBX, a value does not fit the type of its field)." / "140 lignes mises de côté (refus d'EBX, ...)".
- Replace `wizard.step4.build.heldBack.banner.*` ("... a configured rule stopped them"), which blames a rule for rows no rule touched, by `RejectCauses.sentence`; keep a rule wording only when
  `blamesARule()`. The same counts are in the last load step's detail JSON (`entities[].rejectedBy`, `RejectCauses.of(node)`).
- The review link opened from the wizard returns to the step it was opened from (nothing to do: the wizard's `a[href*="service=TransferRejectReview"]` capture already stores it).

## Tests
`rejects/RejectReasonTest` (classification of the live text, escaping, both languages, round trip, legacy rows, signatures, parent nesting, marker never cut), `rejects/RejectCausesTest`,
`ebx/StagingLoadRejectsTest` (orphans, refused landing rows, coded checks, the loader source), `ebx/StagingLoadOutcomeTest` (new verdict), `ebx/ui/RejectReviewPageTest` (coded reasons, no
double escaping, groups, back link, filter layout), `rejects/RejectReviewLogicTest` (reasonCode filter, groups SQL, CSV), `ebx/RejectReviewEngineSourceTest`, and `tools/userstore_test.js`
(the back link against a stand-in window: wizard origin, explorer parent, Cockpit, foreign or broken tokens, another login).

## Not covered by a unit test (needs an instance)
The EBX writes: that a refused parent's children really leave the write list in a live load, the `category`/`reasonCode`/`detail` columns on the new model, the aggregate SQL on them
(the grouped and counted statements were run on the live `TransferReject` with existing columns: `MIN(text)`, `GROUP BY` with `COALESCE`, a `COUNT` over a sub-query all answer).

## Live checks (after a restart: new XSD fields)
1. Reload the loyalty source with `isodate()` on `date_naissance`, "Build and load": Customer 140 rejected, **CustomerAddress 140 rejected** (cause "parent rejected"), none written; the
   load line says "of 140 read"; `TransferReject` rows carry `category`, `reasonCode`, `detail`.
2. Rejects screen (French): "Pourquoi les lignes ont été mises de côté" lists two groups of 140, the sentence names `date_naissance`, the fix points at the mapping matrix; "Voir ces lignes"
   filters; "Détail technique" opens the raw text with a real `>`, no `&gt;`.
3. Open the screen from step 4 of the wizard: the link reads "Retour à l'assistant" and lands on step 4; from the Cockpit: "Retour au cockpit".
4. Fix the link (`date(dd/MM/yyyy)`), load again: customers and addresses are written, the rejects resolve.

---

# Lot H4 (2026-10-17): the other EBX refusals, and the MASTER stage worded like staging

Branch `feature/ebx-refusal-classes` (from `main` a11e0517). Lot B coded only the type-conversion refusal; every other text of EBX stayed `ebx.refused` with the raw message, and the
MASTER stage wrote free text ("unknown parent X (not yet transferred to master)", "EBX refused the save: ..."). Now both stages write the same coded reasons.

Status: coded, `tools/test_local.sh` green, NOT deployed, NOT seen running. **No XSD change** (`TransferReject.category` / `reasonCode` / `detail` come from lot B; the MASTER stage
now fills them too): **no restart for the model**, only the usual one for new Java classes. No `module.xml` change.

## Where the real texts come from (and what was not captured)
The kernel.log of the RC (5 GB, 23 days) holds one refusal text of a Factory save (the date conversion) and a few others of EBX's own Procedures; it holds no maximum-length, mandatory,
foreign-key or enumeration refusal of a Procedure - the Factory pre-checks length and mandatory (`PipelineCompat.checkConstraints`) and static enumerations are non-blocking (CLAUDE.md
section 2). So each pattern is built from the text EBX prints for that class, in this order of preference: (1) a **live** log line, (2) **EBX's own message bundle** in `ebx-lib.jar`
(`com/orchestranetworks/i18n/*.mxml`). The shape of a failed Procedure around those texts was read in the bytecode of `UowUserPrinter` (`<subject> : <message>` for an error with a user
message; `Unexpected error...` then ` > Caused by: <message of the cause>` for anything else - the class of the cause is NOT printed) and checked against the live reject of 2026-10-17.
**Not verified live**: a facet / foreign-key / enumeration violation arriving through a failing `Procedure` of this module (no row of the retest produced one). The patterns do not depend on
the wrapper (tests feed each text bare, behind its subject, and behind the wrapper), but the claim "EBX's Procedure prints the validation-report text for a blocking facet" stands on the
bytecode, not on a captured refusal.

| Class (code) | Real text the pattern is built from | Source |
|---|---|---|
| maximum length (`tooLong`, category EBX_REFUSAL; same sentence as the loader's own check) | `Value must not exceed 20 character(s) long.` | validation.log.2026-10-05; bundle `facet.facetMaxLength` |
| minimum / exact length (`ebx.minLength`, `ebx.length`) | `Value must be at least 5 character(s) long.` / `Value must be 2 character(s) long.` | bundle `facet.facetMinLength`, `facet.facetLength` |
| list of values (`ebx.enumeration`) | `The value 'Mrs' is not valid (see enumeration facet).` | validation.log.2026-10-05; bundle `facetEnum.*` |
| unknown foreign key (`ebx.foreignKey`: field, value, target table) | `The value 'ACC-BAG' is not valid (record does not exist).` | validation.log.2026-10-07; bundle `facetTableRef.occurrenceNotDefined` |
| mandatory field empty (`mandatory`, category EBX_REFUSAL) | `Field 'Revision identifier' is mandatory.` / `Field is mandatory.` | validation.log.2026-09-26; bundle `validation.mandatory` |
| duplicate primary key (`ebx.duplicateKey`: the key) | `Cannot create record with primary key './dataSet='...' and ./...='...'' on /root/import/DataSetImportSpec of content type=VALUES in dataset MIMA in dataspace MIMA since a record with this key already exists, last update by tx: ...` and the printer's `Cannot create the new record since its primary key is already used.` | kernel.log.2026-09-27 (UowErrorCannotCreateSincePrimaryKeyUsed); bundle `uowPrinter.mxml` |
| pattern (`ebx.pattern`: pattern, value) | `The following pattern must be respected: [A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?` (also `[^@\s]+@[^@\s]+\.[^@\s]+`) | validation.log.2026-10-05; bundle `facet.facetPattern` |
| range (`ebx.range`: operator, bound) | `Value must be greater than or equal to 1.` (also less than or equal to, greater than, less than) | bundle `facet.facetMin/MaxInclusive/Exclusive` |
| total / fraction digits (`ebx.totalDigits`, `ebx.fractionDigits`) | `The number must have at most 5 digits.` / `Maximum number of decimal places is 2.` | bundle `facet.facetTotalDigits`, `facet.facetFractionDigits` |
| blocking constraint (`constraint`: name when known, its message) | the constraint's own message, e.g. `IBAN is incorrect.`, `A marketing consent needs its consent date.` (subject `[/root/...]` or `In table '/root/Customer' - Selection xpath ./id=1`) | validation.log.2026-10-06 / 2026-10-07 |
| table trigger failure (`ebx.trigger`: trigger name, its message) | `Dataset 'DistributionOutbox' not found in dataspace 'Personnes_Master'` raised by the script trigger `DistributionAfterCreate` line 26 | kernel.log.2026-09-27 22:53:56 |

The samples (business values replaced) are in `src/test/resources/com/on/ebx/factory/rejects/ebx-refusals.txt`, each with its source; `rejects/RejectRefusalClassesTest` builds a test from every one.

## How the field is found (no guessing)
- The subject EBX prints before a message (`[/root/Customer[./id=1]/bic] : ...`) names the field when the feed has a field of that name (`RejectClassifier.subjectField`).
- Otherwise the row decides, like the conversion of lot B: the field whose value is the quoted one (list of values, foreign key: a field whose plan names a target entity is preferred), the
  text longer than the limit (and whose plan limit is that limit), the value that does not match the pattern, the number outside the bound / with too many digits. If **two** fields qualify,
  none is named (the sentence says "A field", the class and the limit are still given); a mandatory field is the one whose name squashes to EBX's label, else the only empty field the plan
  says is mandatory. The length of a value is stated only when the row holds a value that breaks the limit, else "?".
- `StagingLoader` now hands the classifier each field's maximum length, mandatory flag and target entity (`RejectClassifier.FieldInfo`).

## What the text cannot say: `RefusalHints`
A trigger failure and a blocking constraint read like any other message (EBX drops the class of the cause). `rejects.RefusalHints.header(Throwable)` reads the exception instead and puts ONE
line in front of the stored text, `[mdmf-hint trigger=DistributionAfterCreate;line=26]` or `[mdmf-hint constraint]`, which the classifier consumes and removes (the raw detail stays EBX's own
text):
- **trigger**: a frame `com.onwbp.adaptation.Trg*TriggerApply` is in the stack of the failure, i.e. it was raised while a table trigger ran. A script trigger is named by the file name and line
  of its own frame (`ScriptTableTriggerAfterCreate$$74.onHandle(DistributionAfterCreate:26)` in the live trace), a Java trigger by its class.
- **constraint**: the exception is a `UowErrorWithUserMessage` (EBX's error for a rule that has a message of its own) or a `ConstraintViolationException` / `ConstraintWrapperException` is in
  the chain. EBX does not give a constraint's name in the error, so a save-time constraint reads "A constraint of the model refused the row: <message>"; the MASTER stage's script rules (which
  have a name, `alias.function on /root/Table`) say it.
- A facet message always wins over the constraint hint; a text with no header is never called a trigger.
Both attempts (`StagingLoader.StagingAttempt`, `MasterLoader.MasterAttempt`) add the header. The hint of a constraint rests on the class of the exception (read in the bytecode, not seen in a
refusal of this module): if it turns out not to be raised, those rows stay `ebx.refused` with the raw text - the safe default.

## MASTER stage
- `MasterLoader` builds `RejectReason`s, not strings; `RejectPlan` carries them; `writeRejects` stores `RejectReasons.store(...)` (English + marker), `category`, `reasonCode` (signature) and `detail`
  exactly as staging does, so the review screen, the CSV, the REST/MCP text and the Cockpit read them the same way.
- Codes: `unknownMasterParent` (category DATA_CHECK: "company (staging field companyRef): refers to a Company (SAP|C1) that has not reached master."), `constraint` (QUALITY_RULE: the script rule of
  the transfer, named by `reasonLabel`), and every EBX class above (EBX_REFUSAL) through `RejectClassifier` with the master fields: a master reason's "column" is the STAGING field the value is
  read from (`RejectReason.stagingColumn`, wording "staging field X" where staging says "source column X").
- **Parent cascade.** The rows a master load rejects are remembered (`rejects.RejectedParents`, per entity and staging key, bounded to 100,000); a child whose parent is among them is rejected with
  `parent` (category PARENT_REJECTED): "Parent Company row rejected: <the parent's reason>", the root cause passed on to grandchildren, never written. A parent that is simply not at master yet
  (not loaded, or rejected by an earlier load) keeps `unknownMasterParent`.
- The master load result carries `entities[].rejectedBy` (`{"EBX_REFUSAL":3,"PARENT_REJECTED":4}`) like the staging one, and `ebx.RejectCauseReader.master(session, factory, projectCode)` answers the
  same `Causes` as `staging(...)` for the MASTER stage (same SQL, `stage = 'MASTER'`): the wizard's master step or the Cockpit can word "N rows set aside (...)" with `RejectCauses.sentence`. This lot
  does not touch the wizard (parallel lots): nothing calls `master(...)` yet.
- Rejects written before this lot stay as they are (free text, shown as "Other reasons (recorded before the reasons were classified)").

## Wording changes of existing keys (not in the new block)
`rejects.cause.EBX_REFUSAL` no longer says "a value does not fit the type of its field" (it now also covers lengths, keys, constraints, triggers): "refused by EBX, the data model does not accept the
row" / "refus d'EBX, le modele de donnees n'accepte pas la ligne" (the word "rule" stays out, as `RejectCausesTest` requires). `rejects.fix.tooLong` says "data model" instead of "staging model" (the
same text now serves the master stage). 48 new keys, one block "ebx refusal classes (2026-10-17)" at the end of `Messages_en/fr.properties`.

## Tests
`rejects/RejectRefusalClassesTest` (every class from its real sample in three shapes, field resolution and ambiguity, subject naming, trigger / constraint hints from a stack copied from the log,
both languages, marker round trip, signatures, the master wording and cascade, the bundle block), `ebx/MasterRejectsSourceTest` (the engine source: coded master rejects, classifier, cascade,
stored fields, `rejectedBy`, header, `RejectCauseReader.master`), and the lot B tests adapted to the new cause wording. `tools/test_local.sh` green.

## Not covered by a unit test (needs an instance)
A real master load with a rejected parent (the cascade is pinned by source and by `RejectedParents`), the trigger header from a real failing save (the stack is the copied one), and a real facet /
foreign-key refusal arriving through a Procedure (see "Not verified live").

## Live checks (after a restart: new Java classes)
1. Staging: load a source with a value longer than a field's maximum that the pre-check does not know (a field with no plan length), a value outside a list of values, a duplicate key: the rejects
   screen groups them by kind, names field / column / value, and the Technical detail shows EBX's text with no `[mdmf-hint ...]` line.
2. Master: promote a child entity whose parent row is rejected at master: the child's reason reads "Parent <Entity> row rejected: ..." and the load line says `rejectedBy`; a child whose parent was never
   loaded reads "has not reached master".
3. A table trigger that throws (script `msg`/runtime error) on the master table: the reject reads "The table trigger '<script>' failed: <message>".
