# Landing test fixes (2026-10-17)

Branch `fix/landing-test-defects` (from `main` 205dd5d9). Source: the live test of project "Tiers B2C - test landing" (three sample-file applications), report and 20
screenshots in `test-landing-b2c-2026-10-17/` (`COMPTE-RENDU.md`). Three other defects of that test (JSON envelope, CSV separator, stale catalog column) are fixed in
the ebx-connectors repository; item 1 below is the Factory's own defence for the first of them.

Status: coded, `tools/test_local.sh` green, NOT deployed, NOT seen running. **No XSD change, no module.xml change: a normal install is enough, and a Tomcat restart is
only needed because Java classes are loaded once (same as every Factory deployment).** Nothing here touches the data model.

## What changed

1. **A file read as one document holding a list is not planned silently, and a one-row load of that shape is a failure.** New pure `landing/FlattenedDocument`
   (columns shaped `customers[0].customerId`, or fields `customers_0__customerId`; at least half of the columns). `LandingPlanner` writes the message at the head of
   `LandingTable.message` and puts `warning` on the plan line; the walkthrough's "Why" column says "one document holding a list: the rows were not recognised"
   (`LandingWalk.why` = `flattened`, `wizard.walk.why.flattened`); `LandingFlows.load` turns an OK outcome of exactly one row on such columns into a FAILED load
   carrying the message (the table ends FAILED, the run counts it as failed) instead of LOADED.
2. **"Scan again" refreshes the tables planned but not generated.** `SourceRescanRun` calls `LandingPlanner.plan(..., pRefreshProposed = true)`: for every table
   of the application still `PROPOSED`, the `LandingColumn` rows follow the catalog's current scan (`landing/ColumnRefresh`: added, removed - a renamed column is both -,
   positions, types, fill rates), `columnCount`/`sourceRowCount` are updated. The person's tick is never touched. A key whose columns all remain is kept (a
   person's choice included); a key with a column gone is dropped whole and the naming rule proposes again over the new columns (as for a table with no key). The
   application's validation is cleared (same rule as any change of a tick or a key). Result text: `Fidelite_Membres: 13 columns now (was 1); the key X was dropped (...)`
   (`PlanOutcomes.refreshMessage`, a green success, never "Nothing to do"). GENERATED / LOADED tables keep today's drift handling, untouched. Other callers of
   `LandingPlanner.plan` (the Plan button, onboarding, REST) keep the old behaviour: only the rescan refreshes.
3. **Key candidates are ranked.** New pure `landing/KeyCandidateRank`: identifier-like names (id, uuid, num, no, number, numero, code, key, ref, ticket, card, sku as the first
   or last word) on text-like columns first; names or values that are dates, timestamps or amounts, and date/decimal types, last and NEVER preselected. The key
   chooser (`WizardPage.renderKeyChooserPanel`) lists candidates in that order and ticks the proposal (unless it is one date/amount column), else the current key, else the
   best identifier-like unique column. The walkthrough's inline picker no longer ticks a one-column proposal whose name is a date or an amount.
4. **Key naming rule.** `SourceKeyRule`: when nothing is named like the table, a single column named `<x>Id`, `<x>_id`, `id`, `<x>Number`, `<x>_number` whose profile shows
   no empty value and only distinct values is proposed (origin RULE, shown as "deduced") with confidence 60, below the 90 bar: the person still confirms, and
   `KeyCertainty` is unchanged. Several such columns: none proposed (nothing says which). Plural/singular of the table name both ways for the existing `<TABLE>_ID` rule.
   Rationale text: "... is the only column named like an identifier (Id, _id, Number) and its profile shows no empty value and only distinct values. Confirm it."
5. **Language-model consent kept.** New pure `landing/LlmConsent` reads the last `LandingRun` job's stored params (`useLlm`, `llmSourceIds`): the re-run checkbox
   opens on it (`WizardPage.Model.landingRunUseLlm`, now false by default), the walkthrough's radios on the applications that said yes. The walkthrough sends `useLlm`
   true with an empty `llmSourceIds` when every page said No: that reads as No. No field added, no XSD change.
6. **Layout.**
   a. Step 1: the primary action sits right under the form (before the style cards, which keep their default ticked) and is sticky above the frame's bottom bar.
   b. The step-2 dropped-files table is in a `mdmf-scroll-x` box (the inputs shrink); the step-3 live grid has a fixed layout and wrapping cells in its own scrolling box.
   c. With the uniqueness study open under a row, its chooser replaces the inline picker of that row.
   d. "Recent tasks" opens by itself only when its newest task failed or was interrupted less than 10 minutes ago (was: any task that ended less than a minute ago).
   e. An application that is one of the project's own and whose run finished reads "Ready: part of the landing." (green) in the "in progress" list; the "not part of the
      landing until it is ready" line stays for the others (`WizardPage.stuckLineKey`).
   f. Dropped files propose the smallest trust rank not held by an application of the project nor typed in another row (`SampleFileRules.nextFreeRank`, mirrored in
      `wizard.js`; the held ranks ride on the drop zone's `data-mdmf-taken-ranks`).

## Tests added
`FlattenedDocumentTest`, `ColumnRefreshTest`, `KeyCandidateRankTest`, `LlmConsentTest`, `SourceKeyRuleTest` (generic id cases), `PlanOutcomesTest`, `RecentTasksTest`
(rewritten rule) and `LandingWalkTest` (flattened Why, one chooser, preselection, re-run checkbox, step-1 order, scrolling wrapper and free rank, ready wording).

## Not covered by a unit test
The EBX-side writes (planner flag, `refreshColumns`, the load failure on a real feed) need a running instance: see the live checks.

## Live checks on a fresh project (the three files of `test-landing-b2c-2026-10-17/fichiers/`)
Needs the ebx-connectors fixes (JSON envelope, CSV separator) for the happy path; the defensive checks below can be seen without them.
1. Step 1 at 1280x900 without scrolling: "Create the project" is visible and a real click creates it.
2. Drop the three files: the table does not cut "Trust rank" / the remove button (scroll bar if narrow); drop them again on a project that already holds ranks 1-3: the proposed ranks are 4, 5, 6.
3. With the OLD connector (or any bad first scan): the 1-column Fidelite_Membres. Fix the connection, click "Scan again": the job says "Fidelite_Membres: 13 columns now (was 1)", the ticks are
   unchanged, the table shows 13 columns, a key is proposed. A generated table is untouched.
4. ECOM_Clients with the JSON envelope not recognised: the Why column says "one document holding a list"; if launched anyway the table ends FAILED with the message, never LOADED.
5. Magasins_Ventes: "Measure uniqueness on 25 rows": ONE chooser, NUM_TICKET first and the only one ticked; DATE_ACHAT and MONTANT_TTC last, not ticked.
6. ECOM_Clients with the JSON fixed: `customerId` is proposed as a deduced key at 60 %, the person confirms.
7. Say No to the language model on every page, launch, then look at the re-run form: the checkbox is unchecked; say Yes on one application, launch again: checked.
8. Step 3 right after leaving step 2: "Recent tasks" is folded. Step 2 after the run: the application card and the lower list both say ready.

## Landing leftovers (2026-10-17, `fix/landing-leftovers`, from `main` d91bbb3c)

Three issues left by the live retest (`test-landing-b2c-2026-10-17/COMPTE-RENDU.md`, "Retest"). Status: coded, `tools/test_local.sh` green, NOT deployed, NOT seen running.
**No XSD change, no module.xml change, no new message.** A normal install; Tomcat restart only because Java classes load once.

1. **A key for tables the source gave no profile for.** `SourceKeyRule` needs fill rate and uniqueness; a sample file never has them, so ECOM_Clients_v2 read "none" until
   "Measure uniqueness". New `ebx/KeyPrimer` (EBX reads/writes) + pure `landing/KeyPrimeRunner` (selection, bound, failure handling), called at the end of the jobs that
   plan tables: `LandingSourceRun` PLAN, `SourceRescanRun`, `SourcesRun` (add application). For each table of the application still `PROPOSED`, with no key column and no
   profile statistic (no `fillRate`/`distinctCount` on any column), it runs the existing rule-only study (`KeyStudy.study(..., allowLlm=false, administrator=false)`: the
   25-row sample, never the language model) and records the proposal like the study's "Apply" does (`WizardData.writeTableKey`, origin RULE, confidence 60, the rule's rationale),
   so the walkthrough shows "customerId - deduced - 60 %" at first display. It never touches the person's validation (new `pClearValidation=false` overload). Bound: ticked tables
   first then by name, at most 20 per job, 20 s per table (the read runs on a worker thread and is abandoned on timeout), cancellation checked between tables. A failed or
   timed-out read is appended to the table's message ("Key not measured: ...") and never fails the job. The wizard display is unchanged: it still never calls Connectors.
   Not covered: the two synchronous planning calls of the wizard's own click handlers (`ProjectWizardUserService`, "add source" / "ensure plan") are not jobs and do not prime.
2. **Step 1 order: form, style, button.** `WizardPage.renderStep1` renders the style choice (two compact cards side by side, facts folded, the not-yet-built ones behind one
   line) above the primary block again. The block keeps the previous lot's `position:sticky;bottom:64px` (it is the last child of the left column, so it stays pinned above the
   frame's bottom bar whatever the cards' height) and is made more compact in step 1 (padding 12/18, small text, a light top shadow; cards padded 10/12). Layout math against
   `retest/01-projet-bouton-visible.jpg` (about 0.62 scale of the real frame): header and rail end at about 190 real px, form panel at about 470; compact cards add about 170,
   so the button's natural place (about 640-790) is past the fold of a frame about 720 px tall at 1280x900 - the sticky position (bottom 64 px) is what keeps it visible. To be
   confirmed live (see below). Tests: order assertions in `WizardPrimaryButtonsTest` and `LandingWalkTest` (form, style, button; sticky rule present).
3. **Key chooser no longer widens the walkthrough table.** The chooser (and the uniqueness study) stay in a `colspan=5` row directly under their table row, now
   `<tr class="mdmf-walk-panel-row"><td colspan="5"><div class="mdmf-scroll-x mdmf-walk-panel">`, and the application tables have `table-layout:fixed` with a `<colgroup>`
   (load 7 %, table 22 %, rows 11 %, key 36 %, why 24 %, `.mdmf-walk-fixed`, min-width 640 px inside the existing `.mdmf-table-wrap` which scrolls below that). The wide
   sample table of the chooser scrolls in its own box instead of stretching the table, so the "Key" column is no longer cut. Launch page table untouched.

Tests: `KeyPrimeRunnerTest` (selection, rule proposal recorded as origin RULE/60, failure and apply failure noted and the next table still done, 20-table bound with ticked first,
time budget, cancellation), `LandingWalkTest` (step-1 order, chooser row, fixed layout), `WizardPrimaryButtonsTest` (order).
Not unit-tested: the EBX-side reads and writes of `KeyPrimer` (need a running instance); that it passes `allowLlm=false` is by reading the one call.

### Live checks
1. Walkthrough project, drop the three files: on first display of ECOM_Clients_v2 the key reads `customerId - deduced - 60 %` without clicking anything; the job's tables
   show no "Key not measured" note. Click "Scan again": a table whose key was dropped gets it back; a validated application stays validated when only a key was primed.
2. Step 1 at 1280x900 in the EBX frame: form, the two style cards, then "Create the project" visible without scrolling and a real click creates it.
3. Walkthrough page, "Measure uniqueness on 25 rows" on a keyless table: the chooser opens full width under the row, the Key column of the table stays whole, the sample
   scrolls inside the chooser's box.

## Key rationales in the viewer's language (2026-10-17)

The French wizard showed English key rationales ("No key declared at the source; customerId is the only column named like an identifier ... Confirm it."): the Factory built
them in English, stored them, and the screens printed them as stored.

- **Stored format.** Each sentence the Factory builds about a key is a code (a bundle key `wizard.keytext.*`) plus arguments, kept in the existing fields as the English
  sentence followed by the marker `EngineMessageCodec` already uses, e.g. `... Confirm it. [mdmf:wizard.keytext.genericId|s:customerId]` (URL-encoded arguments, never cut by
  the 2000-character limit: `KeyTexts.fit`). A reader that ignores the marker (REST, MCP, logs, `Proposal.rationale`, `Settled.rationale`) still sees plain English; the
  `FactoryJobStatus` rule (REST gets `decode(...).raw`) is unchanged. No XSD change.
- **Codes** (`landing/KeyTexts`, the English lives there once; `Messages_en` says the same, a test pins it): `declared`, `none`, `named` / `namedSample`, `noProfile`,
  `genericId` / `genericIdSample`, `chosen`, `deduced`, `changedAfterLoad`, `notMeasured` / `notMeasuredTimeout`, `flattened` / `flattenedLoad`, and `pending` (the note kept on a
  table whose proposal waits for a person: reason, confidence, columns, origin, then the proposal's own rationale as a separate sentence).
- **Profile or sample.** `SourceKeyRule.propose(table, columns, fromSample)`: the key study (`KeyStudy`) passes `true`, the planner `false`, so the sentence no longer claims
  "its profile shows" when the evidence is the 25-row sample ("dans l'echantillon" / "dans le profil").
- **Rows stored before the lot** carry no marker: `KeyTexts.parse` recognises the fixed English templates (column names with spaces, dots, accents, parentheses) and gives
  back the same code and arguments, so Zefz, TiersB2C* etc. display in French with no migration. Free text (the language model's rationale, a connector error) is shown as it is.
- **Display.** `wizard/KeyRationaleText.display(stored, locale)`, used at the three places that print a key rationale: step 3 rationale folds (applied key and pending proposal)
  and the key-study panel (so the walkthrough's study too).
- Not covered on purpose: the progress lines of the live landing panel ("Key chosen by a person.", the key summary, the waiting reason: job progress, English), the
  `warning` of the plan's JSON line (REST), and the `LandingTable.message` text (stored and parsed, shown nowhere today). `FlattenedDocument.MESSAGE` is a code too (`flattened`,
  `flattenedLoad`) and displays localized if a screen prints it; the load outcome itself still carries the plain English text.
- Tests: `wizard/KeyRationaleTextTest` (round trip, parser for each template, French/English rendering, fallback, REST text unchanged, waiting note old vs new, marker kept by `fit`).

## The landing run's live panel in the viewer's language (2026-10-17, `fix/run-panel-i18n`)

The French wizard still showed English in step 3 while and after a landing run: per-cell texts ("Built.", "customerId (chosen by a person)", "Waits for the key."), the journal
("Ce que fait la factory"), the partial-failure block, the job toasts and the Recent tasks lines. They were built by the engine as English strings and printed as stored.
Status: coded, `tools/test_local.sh` green, NOT deployed, NOT seen running. **No XSD change, no module.xml change.** Java classes load once: a Tomcat restart after install.

- **Same format as the key rationales.** `landing/RunTexts` holds one code per sentence the run builds (bundle keys `wizard.runtext.*`; the English lives there once and `Messages_en`
  says the same, a test pins it). A stored text is the English sentence followed by the `EngineMessageCodec` marker (`Built. [mdmf:wizard.runtext.built]`), so REST, MCP and logs still
  read plain English. Plurals are chosen where the sentence is built (`loadFailed.one` / `.other`, `runAccepted.11/1n/n1/nn`), so every pattern is a plain `{0}` substitution, the same in
  Java and in the browser. An argument may itself be a coded text (the key summary inside the waiting cell: `keyWait`) or the bare key of a fragment (`frag.low`, `seeDetail`).
- **Rows written before the lot** have no marker: `RunTexts.parse` recognises the fixed English templates (and the nested key summary + waiting reason), so a stored run, a finished job
  and a Recent tasks line display in French with no migration. Free text (a connector error, an EBX exception, the language model) is shown as it is. A refusal the Factory raised
  with its own message code (`EngineRefusal`) keeps that code in the failed cell and is worded from it.
- **Server-rendered panel**: `wizard/RunPanelText.display / event` in `renderLandingRunning` (the "Now" line, the cell label and summary, the journal), `PartialFailuresView` (reasons),
  `RecentTasks` and `EngineMessageCodec.Decoded.sentence` (failed jobs).
- **Client repaint**: no word is built in the browser. The jobs poll of the wizard (and the proposal review's watch) is `GET /projects/{code}/jobs?lang=fr`: with a language, the answer
  carries the texts the run stored with a code already worded in it (`message`, `failedItems[].reason`, and the summaries, activities and journal lines inside the `progressJson` of a
  running landing job, `RunPanelText.localizeProgressJson`), so `wizard.js` and `jobnotice.js` are unchanged and repaint exactly the words of the page. Without `lang` (REST, MCP, anyone
  else) the answer is what it always was: English, markers stripped. `GET /projects/{code}/landing/run` strips the markers from `detailJson`. (A first version shipped the
  patterns to the browser; it pushed the wizard's inline script over its 30 000-character budget, `WizardScriptLoadingTest`, and duplicated the logic in JavaScript.)
- **Journal lines** keep their English shape (`table · Key done: summary`, `tables · activity`) and the marker of their last part; `displayEvent` words the stage and state ("Clé : terminé - ...").

### French texts (the live panel)
| English | French |
|---|---|
| Built. / Created. / Loaded. | Construite. / Créé. / Chargé. |
| Key confirmed (the table is already loaded). | Clé confirmée (la table est déjà chargée). |
| customerId (chosen by a person) | customerId (choisie par une personne) |
| Waits for the key. | Attend la clé. |
| The table step failed: nothing to build a flow on. | L'étape de la table a échoué : rien sur quoi construire un flux. |
| The landing flow has not been created yet. | Le flux d'atterrissage n'a pas encore été créé. |
| 2 of 2 tables failed (see detail). | 2 tables sur 2 en échec (voir le détail). |
| X (proposed by the naming rule) - No column is certain. | X (proposée par la règle de nommage) - Aucune colonne n'est certaine. |
| ECOM_Clients · Publishing the landing model X. | ECOM_Clients · Publication du modèle d'atterrissage X. |
| Creating the landing model X (1 table, 19 fields, about one second each). | Création du modèle d'atterrissage X (1 table, 19 champs, environ une seconde chacun). |
| Building the landing tables. | Construction des tables d'atterrissage. |
| Key done: customerId (...) | Clé : terminé - customerId (...) |
| Looking for the key: reading a sample of rows | Recherche de la clé : lecture d'un échantillon de lignes |
| Creating the landing dataset X in Y. | Création du jeu de données d'atterrissage X dans Y. |
| Up to 2 applications load at the same time (...). | Jusqu'à 2 applications sont chargées en même temps (...). |
| Copying the rows of Contact through Connectors (table 2 of 2). | Copie des lignes de Contact par Connectors (table 2 sur 2). |
| Contact: 0 rows copied. | Contact : 0 lignes copiées. |
| Landing run finished: 12 of 12 stages done. | Exécution de l'atterrissage terminée : 12 étapes sur 12 faites. |
| Landing run finished with failures: 2 of 4 tables were not loaded (A, B). | Exécution de l'atterrissage terminée avec des échecs : 2 tables sur 4 n'ont pas été chargées (A, B). |
| Landing run paused, waiting for a key choice. | Exécution de l'atterrissage en pause, en attente du choix d'une clé. |

The full list is the `wizard.runtext.*` block of `Messages_fr.properties` (81 keys).

### Stays English, and why
- Free text: connector and EBX error messages, the language model's rationale, a stop reason (`Stopped (stopped by admin).`: the reason comes from `FactoryJobs` as an English phrase).
- Not touched: the other background jobs' messages (`LandingSourceRun` "The landing model was built.", `DeducedScanRun`, staging and master load notes), `PipelineStep.message` of the landing step (kept plain English for REST readers).

### Tests
`wizard/RunPanelTextTest`: bundle pinned to the built-in English and same placeholders in both languages, every code (round trip, old English parsed, English and French wording),
the journal, the stored progress, the end-of-run messages, the partial-failure reasons through the 4 000-character detail, Recent tasks, the server-rendered panel in both languages,
the grid the jobs route hands the script (`localizeProgressJson`). No JavaScript changed, so no browser harness to extend (`tools/jobnotice_test.js` still parses `wizard.js`).

### Live checks
1. French wizard, launch a landing run: the cells, the "Now" line and the journal read in French while it runs and after the page re-renders; no `[mdmf:` anywhere.
2. A run that fails on one table: the partial-failure block lists "SOURCE.Table" with a French reason; the toast and Recent tasks line are French.
3. `curl .../projects/<code>/jobs` (no parameter): English, no marker anywhere; `?lang=fr`: `progressJson` and `message` in French.

## A key the person validated is applied; a waiting key is never settled by a poll (2026-10-17, `fix/landing-validated-key`, from `main` e8026838)

Live defect, project TiersB2CRetestStaging (three sample-file applications): the walkthrough showed ECOM_Clients_v4 "customerId - deduced - 60 % - checked at the load" and Fidelite_Membres_v4
"card_number - deduced - 60 %"; the person validated both pages and launched. The run put both tables in KEY WAITING, built the tables, left FLOW and LOAD "Waits for the key", then closed
with "the last launch did not load every table"; the walkthrough offered no key chooser any more. Only validating and launching a second time worked (160 / 234 / 140 rows).
Status: coded, `tools/test_local.sh` green, NOT deployed, NOT seen running. **No XSD change, no module.xml change** (a normal install; Tomcat restart only because Java classes load once).

### Root cause (three defects chained)
1. **The run ignored the validation.** The key was primed at planning (`KeyPrimer`, origin RULE, 60 %). `LandingRun.runKeyStage` re-studied it and asked `KeyCertainty.certain`: RULE needs the
   sample AND the whole source to show it unique, and a sample file's source is rarely verifiable, so the table waited - for a key the person had accepted on a page that said "checked at the load".
2. **`reconcileWithActualTableState` settled the waiting KEY cell as "Key confirmed (the table is already built)"** because the built table had key columns (the primed proposal). With KEY no longer
   WAITING, `hasWaiting()` read false, `isOver()` true (FLOW/LOAD WAITING are neither TODO nor RUNNING) and the poll closed the step with FLOW/LOAD still waiting, nobody to resume them. The
   chooser (driven by a WAITING key cell) disappeared with it.
3. **No way back from the walkthrough.** A built table is frozen there (`status != PROPOSED`): no key action is drawn. And even the run view's chooser would have failed: `applyKeyAndResume` wrote a
   first choice only on a built table that held NO key, otherwise `setTableKey` refused ("its key can no longer be changed here").

### What changed
1. **A validated key is accepted** (`landing/ValidatedKey`, `LandingRun.runValidatedKey`, `WizardSnapshotTable.landingValidated` read from `ProjectSource.landingValidated` with the snapshot of the run).
   For a table of a validated application whose key is a deduction (origin RULE, or a model key at or above 90 % - exactly what the walkthrough shows as "deduced", `ValidatedKey.covers`), the KEY
   stage reads the 25-row sample with the rule-only study (no language model), measures the CURRENT key on it (`KeyEvidence.uniqueTogether`) and asks the whole source (`KeySourceUniqueness`) unless the
   sample already proved the opposite. Verdict (`ValidatedKey.decide`): unique or not measurable -> KEY DONE ("customerId (validated by a person, unique in the sample)" or "... uniqueness not measured"),
   the key itself untouched (origin RULE and 60 % stay as the person saw them); the sample shows a repeated or empty value -> KEY FAILED "The key X, validated by a person, is not unique in the sample ...
   it was not applied. Choose another key."; the source shows a collision -> KEY FAILED, same sentence with "across the whole source". A validated page never ends in WAITING. Not covered by the
   validation (ordinary rules): a settled key (USER, SOURCE, DEDUCED, already skipped earlier), no key (the page says the table waits), a model key under 90 % (the walkthrough shows it as "no key").
2. **Reconciliation never settles a WAITING key** (`LandingKeyRules.reconcileMayConfirmKey`: key columns AND cell not WAITING). *Why not resume instead:* the status poll is a read path, the
   proposal in the columns was never confirmed by anyone, and resuming would apply it - the very decision the person is asked to make. So the cell stays WAITING, `hasWaiting()` stays true, the run
   is not closed, the chooser stays reachable (run view banner, and item 3), and the person's click on "Use this key and continue" (`applyKeyAndResume`) both confirms and resumes FLOW/LOAD. A
   LOADED table settles all four cells as before. `applyKeyAndResume` now writes the key as a first choice on any built, never-loaded table (`LandingKeyRules.firstChoiceAllowed`), whether it holds a
   proposed key or not - without this the chooser of the live case would have refused.
3. **The walkthrough offers the chooser again** for a table the last run left waiting (`LandingKeyRules.waitsInRun` on `Model.landingRunWaitingTableIds`, `LandingWalkPage.renderKeyCell`): the built
   table's row shows its key chips, a "waits for the key" chip, the sentence "The last launch stopped on this table: its key was not certain. Choose the key to continue the run." and
   "Choose the key" (`LANDING_KEY_CHOOSE`); the opened chooser's button is `LANDING_KEY_APPLY` ("Use this key and continue", writes AND resumes), not the walkthrough's `LANDING_WALK_KEY_APPLY`
   (which only writes). After the apply the review flag is cleared so the person lands on the run view.

New messages (en + fr): `wizard.runtext.keyValidated.{checked,unchecked,notUniqueSample,notUniqueSource}`, `wizard.walk.key.waitsInRun`, `wizard.walk.key.waitsInRun.hint`.

### Tests
`landing/ValidatedKeyTest` (what the validation covers, accepted with/without measure, failures by sample and by source, not-validated and settled keys unchanged, when the source is asked, the reconcile
rule end to end on a progress grid, first-choice rule, chooser rule, the cell texts), `RunPanelTextTest` (four new codes: bundle parity, apostrophes, round trip, French), `LandingWalkTest`
(chooser offered only for the waiting table, key kept visible, resume action on the opened chooser, nothing changed for the others).
Not unit-tested (need a running instance): `LandingRun.runValidatedKey` itself (the EBX reads and `KeySourceUniqueness`), the reconcile call site, `applyKeyAndResume`'s write.

### Live checks (project of three sample files, fresh)
1. Drop the three files, walk the pages, validate each ("customerId - deduced - 60 %"), launch: KEY cells read "customerId (validated by a person, unique in the sample)", FLOW and LOAD run, rows loaded
   in one launch, no waiting banner.
2. Same with a file whose key column repeats a value in its first 25 rows: that table's KEY cell is red with "... is not unique in the sample ... Choose another key."; the other tables load; no wait.
3. Do NOT validate (launch through the Advanced path, or change a tick after validating): the deduced key still waits for a person (banner, "Choose the key"); poll for a minute: the KEY cell stays
   "waiting", the run stays open. "Use this key and continue": FLOW and LOAD run.
4. After such a run, "Review the applications" -> the page of that application: the waiting table shows "waits for the key" and "Choose the key"; the chooser's "Use this key and continue" resumes the run
   and lands on the run view.
