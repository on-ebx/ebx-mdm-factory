# Wizard visual pass - shell (2026-09-24)

Implements the SHELL part of the validated whole-wizard mockup (`docs/mockups/wizard-flow/*.dc.html`,
`canvas.json`'s `decide` note item 1: "Rail: 7 steps, horizontal, on top... 'Transfers' becomes
'Master'.") - the product owner's headline complaint after seeing the deployed wizard next to the
mockups: "the step breadcrumb must be at the TOP, the colours are wrong, do a complete pass on the whole
wizard."

## What changed

- **`wizard.WizardPage`**: new `renderTopbar` (called once from `render()` in place of the old direct
  `renderSteps` call) builds the white header bar - left, an eyebrow (`MDM Factory · Project <code>`, or
  `wizard.wiz.noProject` before a project is saved) and a step line (`Step n of 7 · <current step
  name>`); right, the existing `renderSteps` rail, UNCHANGED markup (`<ol class="mdmf-wiz-steps"><li
  data-step="n"><button data-mdmf-action="GOTO_STEP" ...><b>n</b><span>Name</span></button></li>`). Only
  the CSS around that markup changed, so `wizard.js`'s `stepLi()`/`panel()` lookups and the `GOTO_STEP`
  action needed zero changes.
- **`wizard.css`**: `.mdmf-wiz` is a plain column now (no more `222px` sidebar grid). New
  `.mdmf-wiz-topbar`/`-topbar-left`/`-eyebrow`/`-stepline` for the header bar. `.mdmf-wiz-steps` is
  restyled from a vertical list to a horizontal row: a short `::before` connector line between
  consecutive `<li>`s (no extra markup), current = filled `--accent` circle with white text, done (not
  current) = light-green circle (`--good-soft`/`--good`) with a check mark (`::after{content:"\2713"}`
  over a `color:transparent` number - the number stays in the DOM for accessibility/no-CSS, only its
  paint is swapped for the glyph), current-and-done keeps the accent colour (unchanged rule from the
  2026-09-23 "completed-rail" lot). Responsive: below 900px the step names hide and the connector lines
  shrink, so the rail never needs horizontal scroll.
- **Messages_en/fr.properties**: `wizard.step.transfers` value changed `Transfers` -> `Master` (both
  locales; the step's own panel title already said `Master` since an earlier lot, so this only fixes the
  rail label to match). New keys `wizard.wiz.eyebrow`, `wizard.wiz.noProject`, `wizard.wiz.stepline`.

## ROUND 1 (2026-09-24, coordinator split after the shell landed)

The coordinator confirmed the rail, then split the remaining per-step work into rounds across several
agents. This round did the shared grammar plus steps 1 and 7 in full, and every other step's head only.

### Shared page grammar (`WizardPage` + `wizard.css`)

New helpers, all `private static` on `WizardPage`, documented in that class's own Javadoc right above
them (search `shared page grammar` in `WizardPage.java`) - **other rounds/agents reuse these on steps
2-6's bodies**:

| Helper | What it renders |
| --- | --- |
| `renderPageHead(pOut, title, lead)` | The 30px/800 `<h1>` + 15px `#475467` lead, `max-width:740px`. Replaces every step's old `<h2>`/`<p class="mdmf-lead">` pair. |
| `render2ColStart(pOut)` / `render2ColSplit(pOut)` / `render2ColEnd(pOut)` | A growing main column next to a fixed 320px side column (`.mdmf-2col`/`-main`/`-side`), stacking under 1100px. Call `Start`, write the main column, call `Split`, write the side column, call `End`. |
| `renderSideCardOpen(pOut, pLocale, eyebrowKey)` / `renderSideCardClose(pOut)` | One side-column card: an eyebrow + free-form body written in between. |
| `renderGlossaryCard(pOut, pLocale, pKeys...)` | A complete "Words used in this step" side card wrapping the existing `renderGlossaryTerms`. |
| `renderPrimaryActionBlockOpen(pOut)` / `renderPrimaryActionBlockClose(pOut)` | The purple-tinted (`#fbfaff` bg, `#d9d2f2` border) call-to-action card - a big button (`.mdmf-btn-big`) plus explanation text (`.mdmf-primary-text`) written in between. |
| `renderExplainCard(pOut, number, title, text)` | One numbered explanation card (round accent badge + bold title + text). Not used by steps 1/7 (they don't need it) - for a later round's "walk through what this step does" screens. |
| `renderKpiRowOpen(pOut)` / `renderKpiTile(pOut, value, label)` / `renderKpiRowClose(pOut)` | A row of equal number+label tiles. Not used by steps 1/7 - step 6's KPI cards are pre-existing (`MatchResultsView`, a different component); a later round may still want this for another screen. |
| `renderStepStateCircle(pOut, state)` | A small state circle, `state` in `DONE`/`RUNNING`/`WAITING`/`NEEDS_PERSON` (`.mdmf-sc`/`.mdmf-sc-*`). Distinct from the landing run's own `renderStateCircle`/`.mdmf-landing-circle-*`, which is untouched (a different, already-wired vocabulary). Not used yet this round - offered for steps 4/5's "stage" indicators. |

`wizard.css` gained a `/* ---- shared page grammar ---- */` block with the matching classes, plus
`.mdmf-wizard{background:#f4f5f8}` (the boards' own page background, previously unset - the topbar's
existing `-6px` bleed already assumed a page background different from its own white).

### Step 1 (`renderStep1`, board `Main.dc.html`)

Rebuilt on the new grammar: the page head, then a two-column layout - main column has the fields card
(label/code/description), the **unchanged** style picker (`renderImplementationStyles`, not touched),
the staging-mode card, and the primary action block (the Save button, big, with the create/update hint
next to it); side column has the new "What the project will create" card (three dataspace names -
`<code>_Landing`/`_Staging`/`_Master`, the same suffix convention every already-saved project's own
`ProjectInfo` fields already follow, computed from the project once saved or from the typed code before
that - never a separate guess) and the "Words used in this step" glossary card (Project / Golden record
/ Consolidation, new `wizard.step1.glossary.*` keys). The outer `<section>` is no longer one big
`mdmf-panel` card - each block is its own card on the shared `#f4f5f8` background, like the board.

Fixed in passing: `.mdmf-mode-card.selected`'s check mark (absolutely positioned top-right on every
`.mdmf-choice-card`) overlapped the staging-mode card's own text once the new two-column layout gave it
a narrower column and its text wrapped to two lines - `padding-right:38px` when selected reserves room
for it.

### Step 7 (`renderStep7`, board `Summary.dc.html`)

No separate page head when data exists - the verdict banner (now an icon square + text, coloured per
verdict kind) IS the head, per the coordinator's instruction ("Summary per its verdict"); the empty
state (no `Model.summary` yet) still shows a plain `renderPageHead`, since there is no verdict to stand
in for it. Two-column layout: verdict + data journey + open points + go-to-production in the main
column, completeness + last full run in the side column (previously all single-column, with open
points/completeness side-by-side in their own inner grid - `.mdmf-summary-lower` is no longer used).

**Data journey is now the board's own four zone cards joined by arrows**, not the earlier SVG Sankey
diagram (`renderJourney`/`WizardSummaryData.Journey`) - the validated board has no such diagram, only
the zone-card row. `renderJourney` is kept (unused from `renderStep7`) rather than deleted, since its
data is still pure/unit-tested and a later round may want it elsewhere; `renderZoneCards` now prints a
"Data journey" eyebrow and an arrow SVG between consecutive cards (`.mdmf-zone-arrow`).

### Steps 2-6: head only

Every other step's `<h2>`/`<p class="mdmf-lead">` pair was swapped for a `renderPageHead(...)` call,
**bodies untouched** (explicit brief constraint for steps 4-6; steps 2-3 already matched the boards from
earlier lots, so only their head needed the same swap for consistency). Title/lead wording updated to
the boards' own text:

- Step 4: title "Staging" -> **"Decide what the MDM will hold"**, greenfield lead replaced with the
  `StagingStart.dc.html` sentence (the `TARGET_EXISTS` lead variant was left as it was - the boards only
  cover the greenfield case).
- Step 5: title "Master" -> **"Build the golden records' home"**, lead replaced with `MasterStart.dc.html`'s.
  (The rail still says "Master" - that is a separate key, `wizard.step.transfers`, unaffected.)
- Step 6: title "Matching" -> **"Find the same person twice"**, lead replaced with `MatchingPlan.dc.html`'s.
- Steps 2/3: title text was already the boards' wording (`wizard.step2.title`="Where does your data come
  from?", `wizard.step3.title`="Bring the raw data into EBX") - only the markup now goes through
  `renderPageHead` instead of a raw `<h2>`.

## Verification

- `tools/test_local.sh` (with the connectors jars from the brief): **all green**, both after the shell
  commit and after this round.
- Visual: the same throwaway harness (`scratchpad/wizard-preview/Preview.java`), extended this round
  with a `summaryModel()` fixture (a hand-built `WizardSummaryData.Input`, computed through the real
  `WizardSummaryData.compute`, since the shell-only pass never exercised step 7 with real data). Rendered
  and screenshotted at 1280 and 1100px for steps 1 (no project / with a project), 2, 6 and 7 - all match
  their boards closely (verdict banner, zone-card arrows, side-column completeness/last-run, page head
  wording, side "What the project will create"/glossary cards), no horizontal overflow at either width.
  Not committed (throwaway, per the brief).

## Still open after this round (for the next rounds/agents)

- Step 4e (`StagingLoad` board): Generate+Load still two separate buttons, not chained into one click
  with a business-object x application grid.
- Step 5: `MASTER_GENERATE`/`MASTER_LOAD` still two buttons, not the board's single "Build the master"
  with three stages.
- Steps 2-3, 4a-4d bodies: not re-verified against the boards this round (only their head changed) -
  assumed still matching from their own earlier lots.

## ROUND 2: step 6 (Matching) and step 7 polish (2026-09-24)

Picks up the "worth a fresh screenshot-vs-board check" note left by ROUND 1. Step 6's plan-as-tree, KPI
cards, tabs and suspect queue were ALREADY built by two earlier, separate lots (`feature/wizard-matching-
results`, merged as `9ddc5ba`, and the wizard-match-tree lot referenced in `WizardPage#renderMatchPlanBody`'s
own Javadoc) - this round did not rebuild them, only closed the gap between what they already render and
board `MatchingPlan.dc.html`/`MatchingResults.dc.html`, plus the four explicitly-requested step 7 fixes.

### Decision-tree reuse (task item 1)

**Nothing to refactor**: the tree was already reused, not duplicated. `review.MatchPlanTreeDiagram` (package-
private) is the ONE SVG tree renderer, called only from `review.MatchPlanView.render`/`renderTree`
(`review.MatchPlanView#treeScript` already exposes its client-side script publicly for `ebx.ui
.ProposalReviewUserService`). The wizard's step 6 plan card already called `MatchPlanView.renderTree(input)`
(added by the "wizard-match-tree lot, 2026-09-23", before this round) - the SAME tree the Proposal review
screen shows, byte-for-byte (same `Input`, same `render` private method underneath both `renderTree` and the
review screen's own `render`). This round added no new tree code and made no changes to
`MatchPlanTreeDiagram.java` at all.

### Step 6 additions this round

- **`review.MatchSurvivorshipBlock#survivorSummary`** (new public method, additive): a one-line "which value
  wins" sentence - the plan's top-ranked trusted source plus, when the plan's `survivorship.fields[]` carries
  at least one field whose first chain entry is not "most trusted source", an "except &lt;field(s)&gt;:
  &lt;rule&gt;" clause naming the real overriding fields (never a bare count). Reuses the SAME `functionWords`/
  `businessRules` helpers `render` already uses for the full survivorship table - no second phrasing.
- **`review.MatchPlanView#renderSummaryStrip`** (new public method, additive): the board's compact 2-column
  "Simulation on N random records" (sure/to-decide/alone chip counts, one worked "example to decide" pair) +
  "Which value wins" strip, shown directly under the tree for a plan still awaiting a decision - never behind
  the "Show details" fold (`renderDetails`), which keeps the full evidence table/simulation families/
  survivorship table. The counts come from the SAME `MatchSimulationBlock.analyze` tally the full simulation
  panel already computes (`matchCount`/`suspectCount`/`notMatchCount`) - no second computation, no invented
  number. Wired into `wizard.WizardPage#renderMatchPlanBody` right after `renderTree`.
- **`wizard.WizardPage#renderStep6`**: now wrapped in the shared `render2ColStart`/`Split`/`End` grammar (it
  previously rendered as one long single column with no side card, unlike steps 1/7). New
  `renderStep6Side`: a "Validated detail screens" card (points at the SAME `Show details` fold every plan card
  already has - board's own wording, no new explanation invented), the glossary card (Matching plan / Sure
  match / To decide, board's own three terms), and a "Next: results" link anchored to a new
  `id="mdmf-step6-results"` wrapper around the existing KPI/band/suspect-queue loop (the results block stays
  on the SAME page, unlike the board's separate screen, so this scrolls rather than navigates).
- **Not done**: the board's one-line card for a business object with no plan yet (e.g. "Address"). No
  existing data source lists business objects independent of `WizardSnapshot.matchPlans` (a plan-less entity
  is simply absent from that list) - adding one would mean new backend plumbing, out of this round's "UI
  plumbing to existing actions only" scope. Left as a gap, not invented.
- **Configure vs Run**: left as two separate buttons/steps, unchanged. `WizardRules.actionGates` gates
  `runMatching` on the table's matching activation state, which `MATCHING_CONFIGURE` is what actually flips
  (§8 of the root `CLAUDE.md`: activation is a real, separate backend step, not a UI nicety) - chaining them
  into one click would either silently skip a table whose configuration failed, or need new error-recovery
  plumbing neither the brief nor the existing action vocabulary supports. The board shows a single purple
  "Run matching" CTA assuming Configure already happened; this round kept Configure visible as its own action
  rather than hide a real precondition.

### Step 7 fixes this round (task item 4)

- **Links inside zone cards**: the "open" `<a>` in `renderZoneCards` had NO class at all (default browser
  blue, underlined) - now `class="mdmf-link-accent"` (`color:var(--accent)`, no underline until hover), the
  same `--accent` token every other accent element on the page already uses.
- **Eyebrow section titles**: "Data journey" already used `.mdmf-eyebrow` (ROUND 1). "Open points"
  (`renderOpenPoints`) and "Go to production" (`renderProductionCards`) were still a plain `<h3
  class="mdmf-subtitle">`/`<div class="mdmf-prod-title">` - both now render as `<span class="mdmf-eyebrow">`.
- **No "(s)" plurals**: fixed the ones actually rendered on steps 6/7 - `wizard.step7.verdict.openCount`
  (step 7's own verdict banner) and `review.match.summary.suspectOnly`/`mayMerge` (the plan card's one-line
  summary, shared with the Proposal review screen - fixing it here fixes both, same underlying method).
  Each is now a `.one`/`.other` key pair, picked in Java with the SAME "English rule, count==1" convention
  `wizard.WizardPage#plural`/`#countText` already established this same day for steps 2-5 (not introducing a
  second convention). New step 6 strings (the summary-strip chips, the side-column text) were written with
  proper singular/plural from the start. **Not fixed**: `wizard.step7.zoneFact.*` (per-zone, per-index
  strings baked by `WizardSummaryData`, would need a shape change to split per count) and every `wizard.step7.
  open.*`/`prod.deliver.checkResult`/`journal.rows` key, which are formatted in `ebx.ui
  .ProjectWizardUserService` rather than `WizardPage` - left as "(s)" for now, out of this round's time
  budget; `wizard.step7.journey.rows` is dead code (`renderJourney` is no longer called from `renderStep7`
  since ROUND 1), so it was left alone.
- **Completeness bar**: restyled to match board `Summary.dc.html`'s own bar exactly - 8px height, 99px
  (pill) radius, flat `var(--accent)` fill (new `.mdmf-completeness-bar` class). The previous bar reused
  `review.css`'s shared `.mdmf-mini-bar` (6px, threshold-coloured red/amber/green) - that class is untouched
  (still used by the review screen and step 7's own journal bar, out of this round's scope), only the
  completeness card stopped using it.

### Testing

- `tools/test_local.sh` (with the connectors jars from the brief): **all green**, including
  `MatchPlanViewTest`, `MatchSurvivorshipBlockTest`, `ReviewPageTest`, `MatchPlanTreeDiagramTest` and
  `WizardPageTest` - none needed a source change beyond `WizardMessagesTest`'s own `ARG_KEYS` list (the
  renamed `wizard.step7.verdict.openCount.one`/`.other` keys). No new pure-logic method needed a dedicated
  `*Test.java` beyond what these existing suites already exercise through `MatchPlanView.render`/
  `renderSummaryStrip` (both string-render methods covered by `MatchPlanViewTest`'s existing fixture-based
  assertions once that suite is extended - see "still open" below).
- **Not done, and a real gap**: no new `*Test.java` assertions were added specifically for
  `renderSummaryStrip`/`survivorSummary`, and no fresh screenshot pass was run against `MatchingPlan.dc.html`/
  `MatchingResults.dc.html`/`Summary.dc.html` this round (budget ran out before the visual-harness step) -
  the changes above were verified by reading the board markup side-by-side with the new Java/CSS, and by the
  full test suite staying green, not by a rendered comparison. The next session touching step 6/7 should
  screenshot before trusting this round's layout claims.

### ROUND 2, screenshot pass (2026-09-24, coordinator: the comparison with the boards is not optional)

Harness: `scratchpad/wizard-preview-mt/Preview.java` (throwaway, not committed) renders the real `WizardPage` for
three states built from real fixtures: a PROPOSED plan for Person (three-node decision tree, 20-pivot simulation,
two ranked sources, survivorship with email/phone on "last update"), the same plan APPLIED with a
`MatchResultsView.Input` (KPIs, band, two pairs to decide, one merged group, one distinct pair), and a full step 7.
Screenshots at 1280 and 1100 with headless Chrome, compared with `MatchingPlan.dc.html`, `MatchingResults.dc.html`
and `Summary.dc.html`. This supersedes the "not done" items of the ROUND 2 section above. Fixed after comparing:

- **Plan card** (`renderMatchPlan` rewritten, `renderMatchPlanActions` new): white 14px card; header = name, origin
  chip in the board's words ("proposed by the language model"), confidence chip, **Simulate** on the right (same
  `SIMULATE` action the details fold already had); an eyebrow row "Decision tree" + leaf legend (Match only when
  automatic merge is on - otherwise the tree draws no Match leaf) + the plan's one-line summary; the tree across the
  full card width (the node detail panel goes under it, `.mdmf-tree-layout` single column inside the wizard card
  only - review screen untouched); the simulation/which-value-wins strip; "Show details" and "Why this plan" folds;
  then the decisions under a hairline: **Accept the plan** (purple primary, check icon), Propose another, No matching
  for this object (quiet). Same `DecisionActions.forStatus` + same data attributes/inline panels as
  `renderDecideActions`, which is untouched (other steps still use it).
- **Propose / Refine** fold under "Propose the plans again" once a plan exists (first thing shown only when none).
- **Run matching** is the board's purple call-to-action block (`renderPrimaryActionBlockOpen`), big "Run matching"
  (every record); Configure and "Run (last loaded batch)" stay as small buttons in the same block (Configure is still
  a real precondition, see above).
- **Results** (`matching.MatchResultsView`): section head "Golden records · <object>" + the board's lead; KPI tiles
  on one grid row; band under an eyebrow; the To-decide tab is now **one card per pair** (source dots, names, "N%
  alike", one verdict chip per compared field - same/similar/different/missing in <source> - and the three decide
  buttons), the field-by-field table and engine path folded inside the card ("Compare field by field"); the empty
  right-hand detail pane is gone. A "See the summary" call-to-action (GOTO_STEP 7) and an "Undo a merge" side card
  appear once results exist.
- **Step 7**: every zone fact now pairs each count with its noun in singular/plural (`WizardPage#zoneFactText`,
  `wizard.unit.*` keys) - "2 tables", "1 business object", "2 feeds · 9 mapping rules", "1 matching plan applied, out
  of 1", "12 pairs to decide"; the `ProjectWizardUserService` keys too (`open.rejects/suspects/proposed.title`
  .one/.other, `prod.deliver.checkResult` with pluralized parts, journal rows via `wizard.unit.row`); completeness and
  last-run titles are eyebrows; an `<a class="mdmf-btn">` never keeps the browser underline; the "%" column fits
  "100 %". `WizardPageTest` now asserts step 7 contains no "(s)".

Still different from the boards: no record count chip on the plan card and no one-line card for a business object
without a plan (no data source for either); the tree keeps the shared renderer's own explanation header and node
detail panel (not on the board); results and plan share one page (the board has two screens); board's
"Same person — merge"/"Different people" read "Same record"/"Different records" (the object is not always a person).

- Step 6 body (plan-as-binary-tree, KPI cards, suspect queue): pre-existing from an earlier lot, not
  touched this round beyond its head - worth a fresh screenshot-vs-board check by whichever round owns it.
- Steps 2-3, 4a-4d bodies: not re-verified against the boards this round (only their head changed) -
  assumed still matching from their own earlier lots.

## Step 4e + step 5 round (2026-09-24, `feature/wizard-visual-staging-master`, boards `StagingLoad.dc.html` /
`MasterStart.dc.html` + `MasterDone.dc.html`)

Closed both "still open" items above: step 4e's Generate+Load and step 5's Generate+Load are now each one
purple primary action, with the boards' own grid/KPI views once the run finishes. Uses the shared page
grammar (`renderExplainCard`/`renderKpiRow*`/`renderPrimaryActionBlock*`/`renderStepStateCircle`) throughout
- no parallel grammar introduced.

### Why background, not a synchronous chain (task brief question)

Neither `StagingGenerator.generate`/`MasterGenerator.generate` (the "model" half) nor `StagingLoader.load`/
`MasterLoader.load` (the "load"/"copy" half) run synchronously today in the sense of "already fast" -
`generate` makes several MCP calls to the `data-model` server (build/publish the model, in `MasterGenerator`'s
case also declare the Match-and-Merge add-on, then create the dataset), the same call family CLAUDE.md
&sect;15 measured at 80-100s per source for the sibling landing-model step; the `MasterStart.dc.html` board's
own text even says "About two minutes". `load`/`copy` run with the caller's own session (no MCP), so they are
fast on their own, but chained right after `generate` in one HTTP postback the whole thing would still
comfortably exceed what a browser request should hold open. Both new one-click runs therefore reuse the
`STAGING_PROPOSAL_RUN` background-daemon-plus-polling pattern verbatim (own daemon `ExecutorService`, a parent
`PipelineStep` whose `detail` carries the progress JSON, a `data-mdmf-*-running="true"` root attribute wizard.js
polls every 5s with a no-op action) - never a synchronous chain.

### New classes

- **`staging.RunProgress`** (pure): a generalized `ProposalProgress` - same state machine and JSON shape, but
  the ordered stage-key list is a constructor argument instead of `ProposalProgress.STAGES`'s hardcoded six,
  so a 2-stage and a 3-stage run can share it. Self-describing JSON (stage keys are written to the JSON itself),
  so `parse(json, fallbackStages)` only needs the fallback for "no run yet". `RunProgressTest` covers it.
- **`staging.RunGridCellState`** (pure): the cell-state decision (`DONE`/`WAITING`/`NEEDS_PERSON`) both grid
  readers below call, extracted so it is unit-tested without a live repository - `RunGridCellStateTest`.
- **`ebx.StagingLoadRun`**: the step 4e one-click run - stages `MODEL` (`StagingGenerator.generate`) then
  `LOAD` (`StagingLoader.load(..., true)`), attempted even if `MODEL` failed (a re-run after a transient
  model-build failure should not need two clicks). Own daemon executor, `PipelineStep` `STAGING_LOAD_RUN`.
- **`ebx.MasterBuildRun`**: the step 5 one-click run - `TABLES` (`MasterGenerator.generate(..., false)`),
  `COPY` (`MasterLoader.load`), `MATCHING_READY`. The third stage is **not a separate EBX/MCP call**: the
  Match-and-Merge add-on is already declared on the model inside `TABLES` (`MasterGenerator`'s own `add_addon`
  call, "INERT until republished" - and `TABLES` republishes it), so `MATCHING_READY` is marked done right
  after `TABLES` succeeds, its summary built from `TABLES`'s own `entitiesPromoted` count. Actually preparing a
  table to run a match (metadataset, trigger attach) stays `match-merge`'s `activate_matching`, called from
  step 6's "Configure the matching" - not duplicated here.
- **`ebx.StagingLoadGridReader`**: builds the step 4e grid (rows = accepted staging entities, columns =
  applications + "In staging"). Every number is a live read: an application cell's row count is
  `AdaptationTable.createRequestResult("./sourceSystem=...").getSize()` on the entity's own staging table (the
  field every staging row carries, `StagingLoader`'s own `./sourceSystem`); a cell's "held back" count is a
  live `TransferReject` count (`entity`+`sourceSystem`+`stage=STAGING`+not `resolved`) - the same table
  `FactoryCockpitUserService` already lists rejects from, here only counted and filtered. "In staging" is the
  table's own total row count. A table that does not exist yet (model/load never ran) renders every cell
  `WAITING`, never a fabricated 0.
- **`ebx.MasterBuildGridReader`**: builds the step 5 "built" grid (columns `Tables`/`Copy`/`Matching ready`,
  fixed). `Tables`/`Matching ready` follow the project's own `MASTER_DATASET` step / the latest
  `MasterBuildRun`'s `MATCHING_READY` stage (falling back to `MASTER_DATASET` done when no `MasterBuildRun` row
  exists at all - a project built earlier with the separate "Advanced" buttons). `Copy`'s row count is read
  from the latest `MASTER_LOAD` `PipelineStep`'s own `detail` JSON (`entities: [{entity, written, ...}]`,
  already measured precisely by `MasterLoader` during the transfer) rather than a fresh live count - unlike
  the staging grid, this class has no reliable way to compute the master table's own path to query it directly
  (**a documented guess, not measured**: `WizardPage.Model.masterOpenTableUrls`' "Open the &lt;table&gt;"
  links reuse `StagingEntity.targetTable`'s relative path against the MASTER dataset, assuming the master and
  staging table names follow the same convention - never verified live).

### WizardPage / ProjectWizardUserService / wizard.js / wizard.css

- `WizardPage.renderStagingLoadRun`/`renderStep5` each render three states from `Model.stagingLoadRun*`/
  `masterBuildRun*` (`Running`/`Exists`/`Stale`, `*Progress`, `*Grid`) - not-yet-run (intro or explain cards +
  primary button), running (`renderRunStages`, a generalized `renderProposalRunning` for any `RunProgress`
  stage list, reusing the SAME `wizard.step4.run.state.*`/`.mdmf-proposal-stage*` vocabulary/CSS - only a new
  `wizard.run.stage.runningSince` key for the "running for N min" line, since the pre-existing
  `wizard.track.runningSince` is worded for a language-model pass specifically ("can take several minutes")
  and would be misleading for a model-build/plain-transfer stage), done (`renderRunGrid`, generic over
  `WizardSnapshot.RunGridInfo` - new pure value classes `RunGridInfo`/`RunGridRow`/`RunGridCell`, the last
  carrying an optional `primaryValue` so a KPI tile can be built from the SAME number the grid cell already
  shows, never re-parsed from its localized text). The pre-existing separate Generate/Load buttons stay
  reachable under a (new, second) "Advanced" `<details>` on each step, per the task brief.
- `ProjectWizardUserService`: new actions `STAGING_LOAD_RUN`/`STAGING_LOAD_RUN_POLL` (step 4) and
  `MASTER_BUILD_RUN`/`MASTER_BUILD_RUN_POLL` (step 5), each a thin `start`/no-op-poll pair exactly like
  `STAGING_PROPOSAL_RUN`/`_POLL`. `model()` reads both runs' status fresh every render and only builds the
  grid/reject-count/KPI data when the run is NOT running (a mid-run read of the staging table would show a
  partial, misleading count).
- `wizard.css`: new `.mdmf-run-grid*` rules for the grid table (`.mdmf-run-grid-wrap`/`-label`/`-cell`/
  `-primary`); the stage-list/progress-bar CSS is reused verbatim from the staging-proposal-run lot
  (`.mdmf-proposal-stage*`), no new rules needed there.
- `wizard.js`: two new self-poll blocks, copy-pasted from the `LANDING_RUN_POLL` one (`data-mdmf-staging-load-
  running`/`STAGING_LOAD_RUN_POLL`, `data-mdmf-master-build-running`/`MASTER_BUILD_RUN_POLL`).

### Verification

- `tools/test_local.sh`: **all green**, jars from the brief. New tests: `RunProgressTest`,
  `RunGridCellStateTest`, and six new `WizardPageTest` cases (primary button + Advanced fold both render;
  running state shows the stage list and arms the poll attribute; the grid/held-back banner/KPI tiles/open-
  table link render from a hand-built `RunGridInfo` fixture, for both steps).
- Visual: `scratchpad/wizard-preview-sm/Preview.java` (copy of the round-1 harness, per the task brief - never
  committed), six new fixtures (`stagingBeforeModel`/`Running`/`Done`, `masterBefore`/`Running`/`Done`) with
  hand-built `RunGridInfo`/`RunProgress` data mirroring the boards' own example numbers (Person 165+180=342
  from SAP+SF with 3 held back, Address running), screenshotted at 1280 and 1100 with headless Chrome. Compared
  against `StagingLoad.dc.html`/`MasterStart.dc.html`/`MasterDone.dc.html`: grid layout, state circles, reject
  banner + review link, primary block wording, KPI tiles and "Open the table" links all match closely; no
  horizontal overflow at either width. One iteration: the first running-state screenshot showed the
  LLM-specific `wizard.track.runningSince` wording ("a language-model pass can take several minutes") on a
  plain model-build stage - fixed by the new `wizard.run.stage.runningSince` key above.
- **Not verified live**: the whole flow was never run against a real EBX instance this round (per the brief's
  read-only-MCP constraint) - `StagingLoadGridReader`'s `./sourceSystem` predicate, `MasterBuildGridReader`'s
  `MASTER_LOAD` detail-JSON parsing, and the master-table-path guess for "Open the table" links are all
  reasoned from the generators' own source (`StagingLoader`/`MasterLoader`/`MasterGenerator`), not measured.

## ROUND 3 (last, 2026-09-24) - links, plurals, steps 4/5 side columns, full tour

Branch already carried ROUND 1 (this doc's earlier sections) plus a step 6/7 round and a steps 4e/5
round (one-click staging load / master build) by other agents, HEAD `b79e073`. This round:

### 1. Global link style

`wizard.css`: `.mdmf-wizard a:not(.mdmf-btn){color:var(--accent);text-decoration:none;font-weight:600}`
(+ `:hover{text-decoration:underline}`) - every plain `<a>` in the wizard (step 5's "Open the Person
table", step 7's zone-card/production-card links...) now reads accent-purple, no underline, instead of
the browser default blue+underline. **First version of this rule instead re-set `color:inherit` on
`.mdmf-btn` links to exclude them - that STILL outranked `.mdmf-btn-primary`'s own `color:#fff}` on
specificity (two classes beat one) and rendered "Open the Cockpit" as invisible purple-on-purple text.**
Caught by the full-tour screenshot below, fixed with `:not(.mdmf-btn)` on the selector itself instead of
an after-the-fact override - `.mdmf-btn`/`.mdmf-btn-primary` (review.css) are never touched.

### 2. No bare "(s)" anywhere

Two existing conventions were already in the codebase and this round followed each on its own turf,
rather than inventing a third:
- **`WizardPage`/`WizardMessages`** (new, `WizardMessages.plural`/`WizardMessages.countWords`, public so
  `ebx.*` reader classes can call them too): a whole-sentence message with ONE counted noun splits into
  `.one`/`.other` bundle keys (`WizardPage`'s own pre-existing `plural` helper, now delegating to
  `WizardMessages`, gained a varargs overload for messages with extra, non-count placeholders). A
  sentence with SEVERAL independently-counted nouns (e.g. `wizard.step4.run.done.summary`, `wizard.step3.
  keyStudy.llm.ran`) keeps ONE template but its placeholders are now pre-pluralized PHRASES built with
  `countWords(locale, count, unitKey)` ("3 fields", "1 field") from small `wizard.unit.*.one/.many`
  bundle keys - some already existed (`wizard.unit.row`, `.businessObject`, `.qualityRule`...), others
  are new this round (`.field`, `.token`, `.entity`, `.mapping`, `.step`...).
- **`ProjectWizardUserService`** already had its OWN matching pair (`pluralKey` = `.one`/`.other` on the
  whole key, `countWords` = `.one`/`.many` on a bare unit word) from an earlier lot - every notice text
  in that class (`wizard.notice.*`) now goes through one of those two, never a raw count next to a
  `"(s)"` in the template.
- Roughly 40 message keys touched across both `Messages_en.properties` and `Messages_fr.properties`
  (mirrored with real French plural/agreement forms, not a mechanical copy - a handful of SECONDARY
  counts inside an already-`.one`/`.other`-split French sentence, e.g. "1 ligne décidée, {1} ignorée(s)",
  keep the invariant PLURAL word form rather than fully cross-agreeing every argument - a deliberate,
  documented simplification given the round's time budget, not a missed case).
- `WizardPageTest`/`WizardMessagesTest` updated for every renamed/resplit key (stale references to a
  since-removed bare key would otherwise silently pass - `WizardMessages.get` falls back to returning the
  key text itself on a miss, so a stale test assertion comparing against the OLD literal wording still
  "worked" by accident until the key stopped existing).
- **Explicitly out of scope**: two `ReviewLine` free-text fields (`rationale`, `measuredEffect`) carry
  arbitrary prose from `review.*` (a quality rule's rationale, a script constraint's measured-effect
  line) that can contain "(s)" from an LLM or a rule author, not from a fixed bundle key - fixing that
  would mean rewriting free-text generation in another package, outside "wizard.* and
  ProjectWizardUserService-built texts". `onboardScanMessage`/`previewError`-style raw pass-through
  fields are the same story (their own Javadoc already says "shown as-is, never localized").

### 3. Steps 4 and 5 side columns

Both steps' outer `<section>` lost its `mdmf-panel` wrapper (matching steps 1/6/7's own already-bare
`mdmf-wiz-panel` - a step whose section is still one big white card, like 2/3, was left alone this round
- not asked for) and now use `render2ColStart`/`Split`/`End` around the existing body.

- **Step 4** (`renderStep4SideColumn`, board `StagingLoad.dc.html`): "Your decisions" - business
  objects/fields/source-links/rules-and-checks counts, computed the same way `renderProposalDone`
  already does (SUPERSEDED entities excluded, never re-measured) - plus a "Back to the Matrix" link when
  `proposalDoneMatrixUrl` is set, and the "Staging"/"Held back" glossary.
- **Step 5** (`renderStep5SideColumn`, boards `MasterStart.dc.html`/`MasterDone.dc.html`): before a
  build, "From the staging" - one row per entity, its row count read straight off `Model#stagingLoadGrid`
  ("In staging" is that grid's own last column, already-formatted text, never re-measured); after a
  build, "Open in EBX" - the same `Model#masterOpenTableUrls` the done view's primary block already
  links. **No "Record lineage" link** (present on the board) - this model level carries no record-lineage
  URL to link to (that field only exists on `WizardSummaryData.Input`, built for step 7); omitted rather
  than invented, per the task's own "no invented data" rule.
- Fixed in passing: `.mdmf-choice-card`'s selected check mark isn't the only place this family of bug
  can show up, but this round only touched the two side columns above - no new instance found.

### 4. Full tour

Merged the three scratchpad harnesses conceptually (each already covered different steps; rather than a
literal file merge under time pressure, this round's `scratchpad/wizard-preview/Preview.java` was
extended with the fixtures the other two harnesses didn't have - `stagingModel()` (step 4, two accepted
entities with real field/mapping counts) and `masterModel(built)` (step 5, before/after) - and rendered
everything into `scratchpad/wizard-preview-all/`). Rendered and screenshotted at 1280 and 1100px:
`1-project-noProject`, `2-project-withProject`, `3-sources`, `4-staging`, `4b-master-before`,
`4c-master-done`, `5-matching`, `6-summary-empty`, `7-summary-full`. Compared against
`docs/mockups/wizard-flow/Main.dc.html`/`StagingLoad.dc.html`/`MasterStart.dc.html`/
`MasterDone.dc.html`/`Summary.dc.html` - matches closely (side columns, glossary cards, link colours, no
"(s)"); no horizontal overflow at either width. The one real bug the tour caught (the invisible Cockpit
button) is documented under "1. Global link style" above.

**Not independently re-verified this round** (their own earlier rounds already screenshot-checked them,
and this round's changes to them were head-text-only or none): steps 2, 3, 6's body, step 6's matching
plan tree/KPI cards, step 7's zone cards/journey (only their side-column neighbours on steps 4/5 were new
this round).

## Verification (ROUND 3)

- `tools/test_local.sh`: all green, after every edit batch (message-key rename, side columns, link-style
  fix).
- Visual: see "4. Full tour" above; PNGs live under `scratchpad/wizard-preview-all/` (throwaway, not
  committed).

## Live fixes wave 1 (2026-09-24, `fix/wizard-live-wave1`, measured by DOM inspection in a real EBX iframe, 654px wide)

Bugs measured live on the deployed wizard (not caught by the visual-pass rounds' own preview harness, which
never loaded inside EBX's own perspective iframe) plus a second batch found live on project Personnes.

1. **Overflow at narrow widths.** `.mdmf-2col`'s `align-items:flex-start` (needed in row mode) also applied
   once the `max-width:900px` media query (lowered from 1100px - EBX's own perspective menu leaves an iframe
   almost always under 1100px) switched it to column mode, where the SAME property stops a flex item from
   stretching to the container's width - `.mdmf-2col-main` sized itself to its own max-content width instead
   and cut off its content. Fixed with `align-items:stretch` inside the media query. Same family: `.mdmf-run-
   grid-wrap` used `overflow:hidden` (silently cropped a wide grid) - now `overflow-x:auto`; `.mdmf-grid-2`'s
   bare `1fr` tracks (no `min-width:0` by default) - now `minmax(0,1fr)`; `.mdmf-src2-trust-name` had no
   `min-width:0`, so a long application name never shrank and pushed the trust-order up/down buttons off the
   row (measured 23px) - fixed with `min-width:0` + ellipsis. Step 2's "Key:" line was `flex-direction:column`
   (label above the chips instead of one line) - now a wrapping row. Step 3's flow picture (source card / key
   arrow / landing card) only had `flex-wrap:wrap`, which let the arrow land mid-row between cards at ~600px -
   now stacks to a single column (arrow rotated 90deg) below 640px. EBX's own fixed bottom "Close" bar
   overlapped the last controls of every step - `.mdmf-wizard`/`#mdmm`/`#mdfd` all gained real bottom padding.
2. **Matrix and Designer left the perspective, and had no way back.** Both screens build at least one of their
   own navigation links from JavaScript AFTER page load (the Matrix|Designer switch, an entity's "Open in the
   Designer" link) - the wizard/explorer's own one-shot `querySelectorAll` rewrite (`ui/advanced/data/` ->
   `ui/custom/<key>/data/`) never saw them. `ebx.ui.FactoryHtml#perspectiveLinkRewriteScript` is a document-
   level, CAPTURE-phase click listener instead - it rewrites any matching link's `href` at the moment it is
   clicked, whenever it was added to the DOM, wired into both screens' own `write()`. Both also gained a
   top-left "Back to the wizard" link (`FactoryLinks#wizardUrl(..., WizardRules.STEP_STAGING)`, a new `step`
   launch parameter `ProjectWizardUserService#setupObjectContext` resolves the same one-time way it already
   resolves `project`) - the Matrix footer's own "Load the staging" link now uses the SAME url as a real,
   full-page href once ready, instead of only simulating a click on EBX's Close button (which needs a
   same-session origin-return token to do anything useful).
3. **The topbar's "Step n of 7 - name" line never updated on the client-side rail switch** (`wizard.js`'s
   `showStep` only ever moved the rail's own current/done classes - the topbar sentence was server-rendered
   once). `WizardPage#renderTopbar` now wraps the number and the name in their own spans
   (`.mdmf-wiz-stepline-n`/`-name`) inside the still-fully-localized sentence; `showStep` only ever replaces
   those two small pieces of text, never re-parses/rebuilds the sentence (would break a locale whose word
   order differs from English).
4. **Step 3 (Landing): an already-loaded landing (loaded before the one-click run existed, or reloaded some
   other way) kept showing the plain intro.** `renderLandingRun` gated the done view on `Model#landingRunExists`
   alone (a `LandingRun` record); `WizardPage#landingRunDone` (new, pure, tested) now ALSO checks the
   snapshot's own table status - any selected table already `LOADED` is enough, the same signal
   `WizardRules#suggestedStep` already uses for the same purpose.
5. **Step 4 wording/counts.** The side card counted every non-SUPERSEDED entity/attribute regardless of ITS
   OWN status - "2 business objects - 26 fields - ... accepted" while nothing was actually accepted yet. Now
   counts only ACCEPTED/APPLIED entities and reads "N business object(s) proposed, waiting for review" when
   none are. The primary "Build and load" button no longer repeats the section heading text ("Fill the shared
   structure" twice in a row). Every run-stage summary (rule pass, language-model pass, profiling, the anomaly
   pass, quality, capability) and the step 6 agent-run line were hardcoded English sentences built by string
   concatenation in `ebx.StagingProposalRun`/`AnomalyLlmSuggester`/`MatchPlanLlmSuggester` - never localized,
   full of bare "(s)" and words outside the product's own vocabulary ("entity", "feed", "target"). Now built
   through `WizardMessages.get`/`countWords` at the launching session's own locale, with product vocabulary
   (business object, source link, landing column) and new plural-safe bundle keys.
6. **Step 5: the "From the staging" side card rendered an eyebrow with an empty body** when nothing was loaded
   in staging yet (every grid row skipped). Now shows a one-line hint instead.
7. **Step 7 (Summary) showed "no data yet" for a project that plainly had data**, because `buildSummary` was
   only ever called when `model.currentStep == STEP_SUMMARY` on a REAL POSTBACK - but `wizard.js` renders all
   7 panels up front and switches between them purely client-side, so that condition could not be true on any
   request a person could actually trigger by clicking through the rail (`GOTO_STEP` never posts back). Now
   also computed on the very first render of a fresh page load (`ProjectWizardUserService#postedOnce`, false
   only before `handle()` has ever run) - cheap (once per page open) and the only way to guarantee step 7 has
   real data by the time a person can jump there.
8. **Reopening the wizard on a project in progress landed on step 1.** `ProjectWizardUserService#currentStep`
   defaulted to `WizardRules.STEP_PROJECT` (a valid step number, indistinguishable from a deliberate choice) -
   `model()`'s own "0 means pick `WizardRules.suggestedStep`" fallback could therefore never fire on a fresh
   load. Default changed to 0 (an `int` field's own Java default); a real postback or the "step" launch
   parameter above still overwrite it with an explicit step before `model()` ever reads it.
9. **Step 2's "Continue to the landing" block counted every table of a source, not only the ticked
   (selected) ones** ("3 applications, 19 tables" while 4 were ticked). `renderContinueToLanding` now skips
   `!table.selected` both for the table count and the row-count sum.
10. **Mapping matrix: the guidance summary/attention line and the footer's "N still to decide" reason both had
    bare "(s)"** and non-product words ("source(s)") - now plural-safe with `WizardMessages.countWords`/
    `plural` and the product's own "application" wording. The footer's own reason count
    (`MatrixCompute#itemsStillToDecide`, new, pure, tested) now agrees EXACTLY with what
    `readyToLoad` gates on (it now delegates to the same method) - the previous `plan.leftOut +
    summary.attentionPoints` sum could read "0 left" while the link was still disabled for a plain
    proposed/flagged row neither of those two counted.
11. **The overview graphic (fixed-pixel SVG + absolutely-positioned cards, deliberately NOT a scaled viewBox -
    see `renderOverview`'s own Javadoc) used to be squeezed by its own `max-width:100%`** while its content
    kept its real pixel size, so the right-hand cards and the far end of the curves rendered past the shrunk
    box's edge with no scrollbar to reach them. A new outer `.mdmmOvWrap` (`overflow-x:auto`) now does the
    "fit my container" job; `.mdmmOv` itself keeps its full `OV_WIDTH`, so the SVG/cards' shared coordinate
    system stays intact.

### Root cause not found (documented, not guessed)

Item "step 6 shows 'No matching plan proposed yet.' while the same page's agent-run line says '1 matching
plan(s) written' (agent run 46, DONE)": the wording is fixed (both now plural-safe, product vocabulary, see
above), but the underlying disagreement between the listing (`WizardData#readMatchPlans`, scoped by
`StagingEntity` id) and the historical run message was NOT found from code review alone - `readMatchPlans`
iterates every entity of the project unconditionally (no status filter) and its query predicate matches
exactly what `MatchPlanLlmSuggester#write` writes, so no static bug was found in either path. This lot's brief
restricts live MCP reads to `LandingTable`/`LandingColumn`/`ProjectSource`/`PipelineStep` of dataset
`MdmFactory` (project Personnes) and dataspace `Personnes_Landing` - `StagingEntity`/`MatchPlan` were out of
reach to actually inspect the real rows. Left as an open item for whoever can read those two tables live.

### Verification

- `tools/test_local.sh`: all green (jars from the brief), including new tests: `WizardPageTest` (landing-done
  decided from table status, step 4 accepted-vs-proposed side card in both directions, step 5 empty-staging
  hint, the two stepline spans, step 2 counting only selected tables), `FactoryLinksTest` (the new `step`
  serviceParams field, `<= 0` omits it), `FactoryHtmlTest` (the click-delegated rewrite script's own contract:
  capture phase, never preventDefault), `MatrixHtmlTest` (the back-to-wizard link, the plural-safe summary,
  the scrollable overview wrapper, the footer's disabled-reason/enabled-href), `MatrixComputeTest`
  (`itemsStillToDecide` agrees with `readyToLoad` in both directions).
- Visual: `scratchpad/wizard-preview-wave1/Preview.java` (copy of the round-1/round-3 harness, extended with
  fixtures for the live bugs above - `landingDoneNoRunModel`/`stagingProposedOnlyModel`/
  `masterEmptyStagingModel`, plus a direct `MatrixHtml.render` page since that class is pure and needs no EBX)
  - served over `python3 -m http.server` (a `file://` page cannot be resized/scripted in the preview pane) and
  screenshotted at 650 and 1100px. Confirmed visually and via `get_page_text`: step 3 shows the done view with
  no `LandingRun` record ("Landing ready / 1 table loaded, 180 rows loaded in total"); step 4 reads "2
  business objects proposed, waiting for review" with nothing accepted; step 5 shows "Nothing loaded in
  staging yet" instead of an empty card; the Matrix shows "Back to the wizard", the plural-safe summary/
  attention lines, and "1 item still to decide..." next to a disabled "Load the staging" link; no page showed
  a `document.body.scrollWidth` past its `clientWidth` at either width. **Not exhaustively swept**: only the
  screens most exposed by this wave's own CSS/state changes were screenshotted (steps 1, 3, 4, 5, 6, 7, the
  Matrix) - steps 2's full body and the Feed designer screen (not pure - needs EBX to render, string-level
  tests only, per the brief's own fallback) were not screenshotted this round.

## Step gating (2026-09-24)

Product owner's rule: "the buttons to go to the next steps must not be shown as long as what is needed has
not been done." Before this lot, the rail (`WizardRules.stepStates`), the bottom "Next" button and each
end-of-step call-to-action each judged reachability on their own, and the two latter did not judge it at all:
"Next" was only ever disabled on the last step, and four call-to-actions rendered unconditionally or on a
condition weaker than the step's real prerequisite (step 4's "Continue to the master" button did not even
exist - only a static hint sentence did).

### The one rule

`WizardRules.stepStates` (unchanged signature, `StepState[]`, one entry per step 1-7) now enforces:

| Step | Reachable when |
| --- | --- |
| Sources | the project is saved |
| Landing | at least one application has at least one **ticked** (selected) table (`hasTickedLandingTable`) - previously just "a source exists" |
| Staging | **every** ticked table, across every application, is `LOADED` (`allTickedTablesLoaded`) - previously "any" table loaded |
| Transfers (Master) | the staging is loaded - `STAGING_LOAD` step `DONE` - previously just "a staging entity was proposed" |
| Matching | the master is loaded - `MASTER_LOAD` step `DONE` - previously the same loose "a staging entity exists" |
| Summary | always (a readiness view, never gated) |

Going BACK is always allowed - the rule only ever blocks moving forward past an unmet prerequisite; a step
already open, or an earlier one, is never re-blocked.

### What changed per step

- **The rail** (`renderSteps`) needed no change: it already rendered `disabled` + a `title` tooltip from
  `StepState.blockedKey` - it inherited the stricter rule automatically once `stepStates` itself changed.
- **`renderNav`** (bottom "Next"): now disables it, with the SAME `title` reason as the rail, whenever the
  step right after the current one is blocked - not only on the last step.
- **Step 2 -> 3** (`renderContinueToLanding`): the "Continue to the landing" block now renders the
  `wizard.blocked.needTickedTable` line instead of the button when no table is ticked yet.
- **Step 3 -> 4** (`renderLandingRunDone`'s closing block): "Continue to the staging" used to show as soon as
  ANY selected table was loaded (the "done" view's own gate, `landingRunDone`, is unchanged and still uses
  that looser condition to decide intro-vs-done) - the button itself is now additionally gated on `stepStates
  [STEP_STAGING].reachable`, so the "done" view can show with the blocked-reason line in place of the button
  when one ticked table is still not loaded.
- **Step 4** (`renderStagingLoadRun`): "Continue to the master" (`wizard.step4.build.continue`) was a
  bundle key with NO button behind it anywhere in the page - only a static hint sentence
  (`wizard.step4.build.continue.hint`, now unused) always rendered. It is now a real `GOTO_STEP=5` button,
  shown once `STAGING_LOAD` is `DONE`, with the blocked line otherwise.
- **Step 5 -> 6** (`renderStep5Done`): "Continue to matching" used to render whenever `masterBuildGrid` was
  non-empty, even a partial/failed build - now gated on `MASTER_LOAD DONE`.
- **Step 6 -> 7**: unchanged - the summary is always reachable, so no gating was needed there.
- **Step 7's own GOTO_STEP buttons** (zone cards' "Open"/`zone.openGotoStep`, open points'
  `point.gotoStep`, "Back to matching"): left as they were. They only ever point at an EARLIER step from the
  summary (review/fix an already-touched zone) or back to matching, which the central client-side guard
  below refuses anyway if one were ever pointed forward at a blocked step - not independently disabled, since
  none of them currently can name a forward-blocked target.
- **`wizard.js`**: `showStep` already refused a switch onto a blocked rail `<li>` (existing guard, unchanged).
  It now ALSO recomputes the bottom "Next" button's `disabled`/`title` from that same rail `<li>` every time a
  step is entered client-side, instead of only ever un-disabling it once past the last panel - so "Next" never
  drifts out of sync with the rail after a purely client-side step switch (GOTO_STEP, the rail itself, or
  "Next"/"Back"). No new client-side reachability computation was added: it reads the server-rendered
  `disabled` attribute of the target step's own rail button, per the task's own instruction.
- New message keys (`Messages_en`/`fr.properties`, ISO-8859-1): `wizard.blocked.needTickedTable`,
  `wizard.blocked.needLandingLoaded`, `wizard.blocked.needStagingLoad`, `wizard.blocked.needMasterLoad`.
  `wizard.blocked.needStaging` is now unused (kept, unused keys are harmless) - the old, looser Transfers/
  Matching blocked reason it served no longer applies to either step.

### Verification

- `tools/test_local.sh`: all green (jars from the brief). `WizardRulesTest`: rewrote
  `sourceWithNoLoadedTableBlocksStaging` -> `sourceWithNoTickedTableBlocksLanding` (an unticked table, even
  `LOADED`, still blocks Landing; a ticked-but-not-loaded one blocks Staging with the new reason; an unticked
  sibling never counts against `allTickedTablesLoaded`) and `demoStateUnlocksEveryStepButTransfersNeedsStaging`
  -> `demoStateReachabilityRequiresZoneLoads` (steps 1-4 and summary reachable on the demo fixture; Transfers/
  Matching blocked until `STAGING_LOAD`/`MASTER_LOAD DONE` are added one at a time); updated
  `completionFollowsTheSnapshotNotTheCurrentStep` to add `STAGING_LOAD DONE` alongside `MASTER_LOAD DONE`
  (Transfers must first be REACHABLE before its `completed` flag means anything - a blocked step is never
  "completed" by construction). `WizardPageTest`: updated `railTicksCompletedStepsWhateverStepIsOpen` (step 5
  is now `blocked`, not merely "not done", until `STAGING_LOAD DONE` is added); new tests for each of the four
  call-to-action sites (hidden+blocked-line-shown vs. shown+button-rendered) and for the bottom "Next" button
  (`disabled` + `title` vs. enabled with no title).
- Visual: `scratchpad/wizard-preview-gating/Preview.java` (copy of the wave1 harness, eight fixtures - a
  blocked/open pair for each of the four gated transitions: `landing-blocked/open.html`,
  `staging-blocked/open.html`, `transfers-blocked/open.html`, `matching-blocked/open.html`), served over
  `python3 -m http.server` and driven with the Browser pane. Verified live (screenshot + `document`
  inspection, not guessed): on every `-blocked` fixture, `#mdmfWizNext` is `disabled` with the exact
  `wizard.blocked.*` sentence as its `title`, the step's own end-of-step call-to-action button is absent and
  the blocked-reason line is present in its place, and the rail's own next-step `<li>` button is `disabled`
  with the same title; on every `-open` fixture the button renders, "Next" is enabled with no title, and
  (`landing-open.html`) the rail's step-3 button is not disabled. PNGs were not saved to disk this round (the
  Browser pane's screenshot is inline-only from this harness); the HTML fixtures above plus the
  `document.getElementById('mdmfWizNext')`/`querySelector` checks are the record of what was actually seen,
  and can be re-screenshotted directly by opening any of the eight files.

# Wizard - visual/live-fix status

Living log of live bugs found by testing the wizard against a real EBX instance, and how each was fixed.
No dedicated doc existed for wave 1 or wave 2 (documented only in their commit messages, `git log --oneline
--all | grep -i wave`); this file starts with wave 3 and should be kept going forward.

## Live fixes wave 3 (2026-09-24, project Vague1: SF/SAP sample files, SFDC live Salesforce, CRM Snowflake)

1. **Idempotency - re-running "Prepare the landing" on an already-loaded feed table duplicated a primary key**
   (`UowErrorCannotCreateSincePrimaryKeyUsed`). Root cause: `landing.LandingFlow#feedMapping`'s
   `keyPredicate` was `"./_sourceId='${SOURCE_ID}'"`, copied verbatim from the SQL path - but
   `ConnectorService.resolvePredicate` (ebx-connectors) only ever substitutes a `${...}` placeholder from
   the RAW item JSON, never a rule's computed output; a feed row has no `SOURCE_ID` field (only the SQL
   path's generated `SELECT` does), so the predicate always resolved to `./_sourceId=''`, never matched an
   existing row, and every re-run inserted instead of updating. Fixed by building the predicate from the
   RAW key column(s) directly (`LandingFlow#feedKeyPredicate`), joined the same bare way the `concat(...)`
   rule builds `_sourceId` itself, so the lookup finds the exact value the rule will recompute. The SQL
   path's own `keyPredicate` is untouched. Tests: `LandingFlowTest` (extended:
   `checkFeedMappingWithKey`/`checkFeedMappingWithoutKey`, new `checkFeedMappingCompositeKeyPredicate`).

2. **Key certainty for source-declared keys - SFDC's `Id` (origin `SOURCE`) showed "Needs your eye / No
   sample evidence yet"**, and its key chip carried no origin label. Root cause: `ebx.WizardData#originRank`
   did not know about the `SOURCE` origin (added by the same day's composite-key lot) and fell through to
   its `0` default - the same rank the never-yet-set `keyOrigin` accumulator starts from, so
   `strongerOrigin`'s strict `candidate > current` check never actually assigned it. `keyColumns` ended up
   correctly populated but `keyOrigin` stayed `""`, so `LandingRun.runKeyStage`'s short-circuit for an
   already-certain key (`"SOURCE".equals(keyOrigin)`) never matched, the table fell through to the full
   sample-based `KeyStudy`, and the missing origin was also why the key chip showed no label (both symptoms,
   one root cause). Fixed by giving `SOURCE` its own rank (above `RULE`/`LLM`, below `USER`). The
   `KeyCertainty`/message-key pieces were already correct - no change needed there. Test: new
   `WizardDataTest#main` (reaches the package-private `strongerOrigin` directly).

3. **Done-view overcounts - "6 tables landed, 345 rows loaded in total" while 4 of 6 never loaded.** Root
   cause: `wizard.WizardPage#landingRunDone` showed the done view as soon as a SINGLE ticked table was
   `LOADED`, then summed every SELECTED table's `lastLoadRows` regardless of whether that table was actually
   loaded. Fixed by reusing `WizardRules#allTickedTablesLoaded` - the SAME stricter prerequisite the step
   rail already gates Staging's reachability on (step-gating lot, commit 5efdb86) - so the done view and its
   totals only ever show once every ticked table is genuinely `LOADED`; when it is not, the existing running
   grid (`renderLandingRunning`) is shown instead of the plain "start" intro, as long as a run exists to show
   progress from. Tests: `WizardPageTest#landingRunNotDoneWhileAnyTickedTableStillNotLoaded` (new).

4. **Stale row counts in step 2** - a Salesforce catalog rescan updated `Asset.rowCount`, but the wizard kept
   showing the count captured once at planning time. Root cause: `LandingTable.sourceRowCount` is copied ONCE
   by `LandingPlanner` and never refreshed. Fixed in `ebx.WizardData#tableInfo`: when a `catalogAsset` is
   known, read `Asset.rowCount` LIVE (`CatalogReader#rowCount`, new) and use it instead of the stored
   snapshot, falling back to the snapshot only when the asset row is unreadable. Table/column selections are
   untouched - only the displayed count is live. No literal columns/rows swap was found in the rendering
   code (`renderReviewTableRow` already prints column count then row count, in that order).

5. **Wrong initial step on reopen - a partially-loaded project (Landing = step 3) opened on step 7
   Summary.** Root cause: `WizardRules#suggestedStep` used `anyTableLoaded` (true as soon as ONE ticked table
   was loaded) instead of the stricter, already-existing `allTickedTablesLoaded` the rail itself uses to gate
   Staging - so a project with some tables loaded and others not (with staging already proposed from the
   loaded ones) skipped straight to Summary. Fixed by reusing `allTickedTablesLoaded` there too, matching
   commit 5efdb86's "one rule gates the rail, Next and every call-to-action". Test:
   `WizardRulesTest#suggestedStepFollowsWhatIsMissing` (extended with a partially-loaded fixture).

6. **Wrong proposed application code - ticking `SNOWFLAKE_LEARNING_DB.CRM_SOURCE` proposed `DATA_PRODUCTS`.**
   Root cause: `ebx.ui.ProjectWizardUserService#pickCatalogSource` always derived the suggested code from
   `pickedCatalogSourceSchemas.get(0)` - EVERY schema of the picked catalog source, in whatever order
   `WizardData.schemasOf` returns them, never the schema(s) the person actually ticked
   (`sourceDraft.schemas`, posted from the client's checkboxes). Fixed to prefer the ticked subset, falling
   back to the full list only when nothing is ticked yet (a fresh pick before any checkbox is touched).
   UI-service glue code (no pure logic added) - unverified without a live Tomcat.

7. **Wording/staleness.**
   - (a) The run grid always read "N of M table(s) failed." (never real plural) with no reason. Root cause:
     `ebx.LandingRun#runLoadForSourceIfReady` built the summary as a literal `"table(s)"` string and never
     read the per-table `error`/`transportError`/`writeBackError` fields `LandingFlows#load` already
     recorded. Fixed: real pluralization on the FAILED count, plus a new
     `LandingFlows#firstFailureReason` surfacing the first failing table's own reason. Test:
     `LandingFlowsTest` (new).
   - (b) Step 6 kept showing an old "N matching plan(s) written..." line (AgentRun 46) after a project reset
     deleted every `MatchPlan`. Root cause: the `MATCHING_SUGGEST_LLM` `PipelineStep` track is a permanent
     history record that a reset never touches. Fixed: `WizardPage`'s step 6 rendering only shows that track
     when the project still has at least one `MatchPlan`. Test:
     `WizardPageTest#step6HidesStalePipelineTrackWhenNoMatchPlanExists` (new), plus a sibling assertion that
     the track still renders when a plan genuinely exists.

8. **Landing run blocks on WAITING - "8 of 24 stages" frozen, SFDC WAITING, CRM tables never started.**
   Investigation found the hypothesized "breaks the loop on a WAITING table" bug does NOT exist:
   `runInBackground`'s per-table/per-source loop already continues past a `WAITING` cell exactly as its own
   Javadoc documents, and every per-stage call already catches `OperationException`/`RuntimeException`
   locally (an earlier wave's own bug 2 fix). The real gap: those catches do not cover a `Throwable` that is
   not an `Exception` (an `Error`, same family as CLAUDE.md's documented `VerifyError` precedent) - one
   escaping anywhere inside one source's processing used to propagate straight past every remaining source
   in the `for` loop AND past `maybeCloseStep` (only reached via a normal return from the `try`), leaving the
   step stuck `RUNNING` forever with later sources' tables permanently `TODO` and no visible failure. Fixed
   by wrapping each SOURCE's own four stages in `try { ... } catch (Throwable ex)`: a catastrophic failure
   now marks only that source's own still-`TODO`/`RUNNING` cells `FAILED` and the loop moves on to the next
   source - never blocking a source it has nothing to do with. Test:
   `LandingRunProgressTest#oneSourceFailingNeverBlocksAnotherSource` pins the data-model invariant the fix
   relies on (no cross-source coupling in `LandingRunProgress`); the actual `try`/`catch` control flow needs
   a live EBX `Session` and could not be exercised here.

### Unverified (needs a live EBX Tomcat re-check)

Every item above compiles and is covered by the fastest test the pure/`ebx.*` split allows, and the full
suite (`tools/test_local.sh`) is green, but none of the following were seen running live in this session:
- Bug 1's re-run no longer duplicating a real Salesforce/sample-file row (only the generated `keyPredicate`
  JSON was asserted).
- Bug 2's SFDC `Id` key actually reaching KEY `DONE` with no sample, and the origin chip showing "declared
  by the source" in the rendered page.
- Bug 3/5's done-view/resume-step behavior against a real partially-loaded project.
- Bug 4's live row count actually refreshing after a real catalog rescan.
- Bug 6's suggested code against the real catalog-source combobox/checkbox flow.
- Bug 7's run-grid wording and step 6 track visibility in the rendered page.
- Bug 8's actual effect on a live run where one source genuinely throws a `Throwable` - the per-source
  isolation was only proven at the `LandingRunProgress` data-model level.
