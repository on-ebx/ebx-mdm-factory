# UX lot C4 - the deduced-scan loop of the Sources step (fourth wave)

2026-10-14, branch `fix/ux-c4-deduced-loop` (from `main` 841ee34d, which holds lots C1, C2, C3 and D). Seen live the same day on project UxCrmSf3
(ProjectSource 93 DEMOCRM, connection DemoCRM of type `rest`, catalog source DEMOCRM whose 2 tables Company/Contact were found from the data and CONFIRMED in
an earlier project; screenshot `view-c07b-step2-democrm-after-scan-full.png`). Status: coded, tests green, NOT deployed, NOT seen running.

## What was measured

1. Picking "Demo CRM - found from the data, 2 tables" queued the DEDUCED_SCAN job, which ended in 20 s with "2 table(s) up to date, decisions kept." The card
   then said "Press "Find its tables" above" with no table row, plus a second progress card of the same application. Pressing the card's button ran the same
   scan again and stacked a THIRD card. No `LandingTable` was ever written: the automatic plan (`ProjectWizardUserService.autoPlanLandingIfDeducedScanDone`)
   only runs when a person decides the LAST table - never when every table is already decided.
2. The same list offered "OpenAI - AI enrichment" as "will be read from its data": lot C1's readability rule treats `complete (read)` of the `llm` type as a
   listing operation.

## What was built

### 1. The job plans the tables when all are decided
`DeducedScanRun.runOneSource`, after the tables were written and the summary built, calls `planIfAllDecided`: it reads the decision counts of the catalog
source (`DeducedScanCatalog.decisionCounts`, the job's own session - the job is queued only for a person who can see the catalog) and, when
`DeducedPlanRule.allDecided(confirmed, leftOut, toConfirm)` (none to confirm, at least one decided), plans every `ProjectSource` of THIS project that uses
that catalog source with `LandingPlanner.plan(session, factory, id, NewTableSelection.selectNew(connectionType))` (the type read off the Connectors row by the
new `WizardData.connectorTypeOf`): the tables arrive unticked, a table planned earlier is never touched. It lives in the job, so it works whether the person
stays on the page or not. The job message becomes "2 tables found, ready to choose." (`DeducedPlanRule.readyMessage`) when tables were added to a source that
had none; otherwise the run's own summary stands (nothing new, or a table still to confirm). A failed plan does not fail the run (the tables ARE found): the
message says "The tables were found but could not be listed: ... Press "List its tables" to try again."
The rule is ONE pure function, shared with the card (below). The existing automatic plan after a Confirm / Leave out stays as it was.

### 2. The application card
For an application with no planned table, whose connection has no catalog scan (`deducedScan == OFFERED`) and whose tables found from the data are all decided
(`DeducedPlanRule.listable(unplannedAssets)`): the body says "Its tables were found from the data and you have decided them. Press "List its tables" to choose the
ones to keep.", the primary button is "List its tables" (the existing `SOURCE_FIND_TABLES` job `LandingSourceRun.PLAN`, new tables unticked - same as
`selectNew` for any connection that is not a sample file), and "Read its data again" (the former `DEDUCED_SCAN_START`) is a secondary link next to it. The
header no longer carries a scan button in that state. "Press Find its tables above" now only appears when the tables really are still to be found or
confirmed. To feed the rule, `UnplannedAsset` carries the catalog state of a table found from the data (`scanState`: CONFIRMED / LEFT_OUT / TO_CONFIRM, an
empty state of a deduced table counts as confirmed, "" for an object of an official scan), read by `CatalogReader.assetBriefs` (no extra query).

### 3. One progress card per source, merged into the application's card
- `DeducedScanRun.status` keeps only the LATEST run of each source (`DeducedScanCards.latestIndexes`; `running` still reads every job), so the page never
  stacks one card per run.
- A run that is DONE whose source has an application card is no longer a card of its own (`DeducedScanCards.mergedIntoApplicationCard`): its five stage
  circles move to a fold "How the tables were found" at the bottom of the application's card (`renderDeducedScanFold`, reuses the extracted
  `renderDeducedScanPipe`). A running, waiting, failed or skipped run keeps its own card (progress, reason, "Remove").

### 4. A language model is never an application
`AddApplicationOptions.neverASource(type)`: the `llm` type is greyed "cannot be read: a language model, not an application" (`REASON_NOT_AN_APPLICATION`,
`wizard.src2.app.reason.NOT_AN_APPLICATION`), whatever its operations say, after the "already in the project" and "switched off" rows (those keep their own
chip). `SourceOnboarder.readabilityInto` skips the type (no useless read). Connectors' type metadata (`connectors_list`: type, label, operations) carries NO
"enrichment" flag, so the list of such types is named in code (`llm` only). The lookup services (GLEIF, Sirene, VIES, BAN... all of kind `read`) are still
offered as "will be read from its data" and refused at the click by the deduced scan itself ("looks up one record at a time... Nothing was created").

## Files
Pure + tests: `wizard/DeducedPlanRule` (new, `DeducedPlanRuleTest`), `wizard/DeducedScanCards` (new, `DeducedScanCardsTest`), `wizard/UnplannedAsset`,
`wizard/AddApplicationOptions` (`AddApplicationOptionsTest`). Live: `ebx/DeducedScanRun`, `ebx/CatalogReader.assetBriefs`, `ebx/WizardData.connectorTypeOf`,
`ebx/SourceOnboarder.readabilityInto` (one condition), `core/CatalogReadSites` (one decided site: DeducedScanRun reads the counts as the job's session),
`wizard/WizardPage` (`renderCardScanButton`, `renderNoTableBody`, `renderDeducedScanCards`, `renderDeducedScanFold`, `renderDeducedScanPipe` extracted from
`renderOneDeducedScanCard`, `renderFindTablesButton` with a label key), both bundles (`wizard.src2.listTables`, `wizard.src2.noTable.fromDataListable`,
`wizard.deduced.readAgain`, `wizard.deduced.foldTitle`, `wizard.src2.app.reason.NOT_AN_APPLICATION`). No change to `ProjectWizardUserService`, no XSD, no
`module.xml`.

## Verified by test (offline)
`DeducedPlanRuleTest` (decision rule incl. the measured DemoCRM, objects of an official scan never listable, ready message), `DeducedScanCardsTest` (latest run
per source on the three-runs case, merge rule per state), `AddApplicationOptionsTest` (the measured list with OpenAI as `llm` + readable: greyed with
`NOT_AN_APPLICATION`; a switched-off llm stays "switched off"), `WizardPageTest.decidedDeducedTablesAreListedNotScannedAgain` (card in the four cases: still
to confirm = old behaviour; all decided = "List its tables" primary, link, no "Press ... above"; finished run = fold, no second card; failed run = own card),
`CatalogReadSitesTest` (the new decided site), the rest of the harness.

## NOT done / not tested
- `planIfAllDecided` (EBX-bound: decision counts, ProjectSource lookup, `LandingPlanner.plan`) has no offline test; only its rule and message do.
- `DeducedScanRun.status` collapsing is tested through the pure function, not against real `FactoryJob` rows.
- "List its tables" for a deduced source goes through `SOURCE_FIND_TABLES`, whose `selectNew=false` equals `NewTableSelection.selectNew` for every connection
  that is not a sample file (a deduced source is never one).
- Other lookup-only connection types stay offered in the list (see section 4).
- A source decided before the job ran but whose card still shows "Find its tables" because `deducedScan` is not OFFERED (eligibility not checked) keeps the
  old behaviour.

## Live checks (compare with `view-c07b-step2-democrm-after-scan-full.png`)
1. New project, pick "Demo CRM - found from the data, 2 tables": the job ends with "2 tables found, ready to choose." and the application card lists
   Company and Contact UNTICKED with "0 of 2 selected" - no "Press Find its tables above", no second card, no manual `POST /sources/<id>/landing/plan`.
2. On a project where the source has decided tables and no LandingTable (the state of UxCrmSf3 before the coordinator's manual plan): the card shows
   "List its tables" (primary) and "Read its data again" (link); "List its tables" plans the 2 tables.
3. Press "Read its data again" twice: still ONE progress card at most while it runs; once finished it is the fold "How the tables were found" inside the
   application's card (stage circles, expandable), never a stacked card.
4. A failed or "not a source" deduced run still shows its own card with the reason and "Remove".
5. Open the "Add an application" list: "OpenAI - AI enrichment" (and Claude, Gemini, Mistral, Azure OpenAI, Ollama when active) is greyed "cannot be read: a
   language model, not an application"; DemoCRM stays pickable.

## Addendum (same day, second commit): ticking a table then leaving step 2

Measured live on 1.2.0-133 (UxCrmSf3): tick a table without "Keep these tables", press "Continue to the landing": a `window.confirm` "You changed a field of this
step and did not save it... Open the other step anyway?" appeared (`wizard.step.leaveDirty`), and on "OK" the page submitted twice. Cause in `wizard.js`:
every input change of the visible panel set `dirty`, a tick included; "Continue" carries `data-mdmf-goto-step="3"` AND `data-mdmf-action="TABLES_KEEP"`, so the
goto listener asked `confirmLeave(3)` and then called `loadDeferredStep` (a STEP_VIEW submit) while the action listener also submitted TABLES_KEEP.
Fixed in `wizard.js` only:
1. a change on a `.mdmf-tick` box never sets `dirty` (TABLES_KEEP saves it, it is not a lost edit);
2. the goto listener, when leaving step 2 for another step with ticks pending (`collectTicks("all")`), does exactly ONE submit - `TABLES_KEEP` with
   `mdmfTableTicks` and `mdmfStep=<target>` (the same path as Next) - after `stopImmediatePropagation()`, so neither the confirm, `loadDeferredStep` nor the
   button's own action listener runs. This covers "Continue to the landing" and the rail's buttons (step 3 and every other step: the rail did not save pending
   ticks before). With no pending tick nothing changes: "Continue" shows step 3 client side (its action listener returns on `data-mdmf-skip-if-clean`).
Tested: `wizard.js` still parses in the harness (JavaScriptCore step); the repo has no DOM harness for `wizard.js` (only `jobnotice_test.js` and
`userstore_test.js`), so the behaviour is a MANUAL check on a project with an application card: (a) tick a table, press "Continue to the landing": no
confirm, ONE request (TABLES_KEEP), step 3 opens and the ticked table is in scope; (b) same through the rail's step 3 button; (c) untouched ticks: "Continue"
opens step 3 at once, no request; (d) edit the application code field (not a tick) without saving, press the rail: the confirm still appears on a step that
has to be loaded (steps 7 and 8).
