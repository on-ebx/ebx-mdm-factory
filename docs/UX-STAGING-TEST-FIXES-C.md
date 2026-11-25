# Staging screens, lot C (2026-10-17, `fix/staging-screens`, from `main` 4a8806c0)

Source: the live test of project "Tiers B2C - retest landing" (`test-staging-b2c-2026-10-17/COMPTE-RENDU.md`, captures 01-10), defects 5-11 of that report that belong to
step 4 of the wizard and to the Mapping matrix. Parallel lots: A (proposal content), D (normalisation pass), B (`fix/staging-load-rejects`: loader, `TransferReject`, rejects screen).

Status: coded, `tools/test_local.sh` green, NOT deployed, NOT seen running. **No XSD change, no module.xml change** (bundle keys, Java and CSS only): a normal install; Tomcat
restart only because Java classes load once.

## 1. The matrix header ("Latelier propose {0}, ...")
Cause: a French pattern with ONE apostrophe next to placeholders. `MessageFormat` reads a lone `'` as the start of a quoted span: the apostrophe vanishes and every `{n}` after
it stays as written. 31 keys of the shared bundle had that shape (23 French, 8 English: `mappingMatrix.summary`, `.explain.*`, `.guards.*`, `.scope.feed`, `wizard.step4.primary.next.toApply.one`,
`wizard.sources.run.queued`, step 7 verdicts, `lineage.graphic.tooltip.*`, `designer.*`...): all doubled (`''`). `wizard.stepReset.notice.*` was called without its argument (printed `{0}`): the
stage is now passed. `BundleApostropheTest` reads the WHOLE bundle in both languages (a key with a placeholder and a lone apostrophe fails) and formats the header sentence.
Rule kept: `''` only in messages formatted with arguments.

## 2. One source of truth for the counts
- `MatrixCounter.Tally.acceptAll` = size of the bulk plan = the N of "Accept the N proposals". The footer button, the "only the button is left" headline, the footer count and step 4 of
  the wizard all quote it (`withButton`, the items that go with the button, stays the "is anything left" test; a test pins acceptAll >= withButton and "empty exactly together").
- Per business object, one figure: items still to decide (`MatrixCompute.countOfEntity`) on the tab, the toolbar chip and the overview card (was `entityStats.proposed`: 49 against 62).
  The source chips of the overview stay per-source cell counts (a different breakdown, not a per-object figure).
- Step 4: `ProjectWizardUserService.model()` reads `MatrixCounter.of(project)` and fills `matrixNeedYou` AND `matrixAcceptAll`: "13 points need your attention, the other 93 go with one click"
  (`WizardRules.step4ReviewNote(waiting, needYou, acceptAll)`; without the figure the old subtraction stands).
- Optional suggestions (connector look-ups): `Step4Counts.Row.optionalWaiting` / `Step4Counts.optionalWaiting()`; they are NOT in `waiting()`, so they never keep step 4 in "Review the proposal"
  nor hide "Build and load". The primary block says so ("N optional look-up suggestions ... never block the build"), the side card shows "N optional suggestions" instead of a "to review" chip,
  the "decisions left" card titles with the blocking count (or "N optional suggestions" with its own lead when only those remain). `StillToDecide.blockingTotal()/optionalTotal()`.

## 3. "Build and load the staging" in the matrix
The footer control is a button (`LOAD_STAGING`): `MappingMatrixUserService.loadStaging` re-checks the DESIGN right and that nothing is left to decide, starts `StagingLoadRun.start(...STAGES, false)`
(the wizard's `STAGING_LOAD_RUN`, same job and gate), and the render that answers clicks the "Back to the assistant" link (perspective-aware, step 4) so the live panel is what the person sees
(`State.goToWizard`, `data-goto`). Wording: guide step 3 / footer say "Build and load the staging" (the French "préparation" meant staging). On step 4 the build button is the primary block's
as soon as nothing blocks (state BUILD, above the Advanced fold), replacing the "will appear here" sentence.

## 4. Rows not loaded
Banner at the TOP of step 4 (`renderStep4RejectsBanner`): "140 rows of Customer were not loaded (refused at load time)." + Review (several objects: "152 rows were not loaded: Customer 140, Order 12 (...)";
no breakdown: the total). The per-object count comes from the grid (`RunGridCell.heldBackValue`, set in `StagingLoadGridReader.applicationCell`: one line, lot B may touch the same method). The old
banner below the grid ("a rule set to stop the row") is gone, as is that claim in the glossary. While rows are open the CONTINUE block puts "Review" as its primary and "Continue to the master" plain, with
a note that the rows stay out of the master.
**Hook for lot B: `WizardPage.rejectCauseSentence(Model, objectName, Locale)`** (returns null now; the neutral phrase "refused at load time" / "refus au chargement" stands). Lot B's
`RejectCauseReader.staging(...).entity(name).sentence(locale)` goes there (read it in `ProjectWizardUserService.model()` into a `Model` field); `rejectCauseWording` is the one caller.

## 5. Language of the staging run and job messages
Same mechanism as the landing run panel (`RunTexts` codes `wizard.runtext.stg.*`, `Messages_en/fr`, English followed by the `EngineMessageCodec` marker, old English parsed by `RunTexts.parse` incl. a
legacy template for the old "row(s) written" sentence). Coded: proposal end/stop/fail, skip reasons ("Not run: ...", "rule pass did not complete ...", "No language model ...", "Not selected."),
"Queued: ...", build accepted / finished / stopped / failed (model, load, both), "Staging model at X/Y.", rows written, "Stopped mid-run: ...", the journal's "Failed: ...", the load activity
("E . S: reading N rows." and the four progress shapes), the generator's notes (preparing / creating / UPDATING THE MODEL WITH THE NEW DECISIONS / publishing / dataset), the loader's notes, and the
sources job's "Ready: application X added (source Y)." (+ the plan-failed variant, reworded "planning its landing tables failed": "landing plan" is banned vocabulary; the old English is still read). The step's own `message` stays plain English (REST, cockpit); the job's message and the stage texts carry the marker.
Displayed: `renderRunLive` (now line, journal, trace), the proposal panel's stage summaries, Recent tasks / toasts (already through `RunPanelText.display`), and the jobs route: `FactoryJobStatus.progressIsWorded`
now covers STAGING_PROPOSAL, STAGING_LOAD_RUN, MASTER_BUILD_RUN (`localizeProgressJson` also words top-level `stages`). `RunProgress.line` no longer cuts a marker. The master build shares the generic codes
("Build accepted.", "Not requested ...", "Not run: ...").
French bundle: every "atterrissage" is now "landing" ("le landing", "du landing", "1 table chargée dans le landing"); `RunPanelTextTest` updated.
Stays English: free text (connector / EBX errors, language-model words, a stop reason), the master's own end messages, the LLM suggesters' English notes.

## 6. Layout
One title (the intro panel lost its own), one vocabulary (the inline paragraph is gone, the side glossary stays), "Propose the staging" first (the intro is read under it), the Staging glossary entry
says "your applications" (not "both"), "held back" no longer says "a rule". The sources x objects grid has a fixed layout (`mdmf-run-grid-fixed`, cells wrap, own scrolling box below 640 px).
Matrix: every submit posts the scroll offset of the page's scrolling container (`mdmmScroll`, `MappingMatrixUserService.parseScroll`), the render restores it, and the selected item is scrolled into view
only when it is off screen; a click on the queue no longer sends the page to the top.

## Tests
`BundleApostropheTest`, `StagingScreensMatrixTest`, `StagingScreensTest`, `StagingRunTextsTest`, `MappingMatrixUserServiceTest` (scroll), updated `Step4CountsTest`, `WizardPageTest`, `MatrixHtmlTest`,
`MatrixCounterTest`, `RunPanelTextTest` (the new codes, French "landing").

## Not verified / live checks
Nothing ran in a browser or against EBX: the matrix scroll restore and the click on the back link after the build (perspective rewrite), the jobs route with `?lang=fr` for the staging jobs, the top
banner with real counts, the fixed grid at three applications. Matrix source chips keep their own per-source figures. Per-object reject counts depend on the grid reader.
