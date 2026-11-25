# UX lot U3 - banners, live progress, counters, summary (2026-10-13)

Source: the coordinator's replay of the wizard on project `UxCrmSf` (`ux-factory-wizard-2026-10-13/RAPPORT.md` sections 2.5, 2.7, 2.9, 2.10, 2.12 and 3.3).
Branch `feature/ux-counters-banners`. Status: coded, tests green, NOT deployed, NOT seen running.

## 1. Recent tasks and the notice of the last action

- `RecentTasks` opens by itself only when its newest task ended less than 60 s ago (`FRESH_MILLIS`, it was 15 minutes: with a row per action the list
  was open on every later step, 10 rows, about 450 px above the step title).
- In the wizard the list shows the tasks of the step on screen plus the 3 most recent ones (`RecentTasks.visible`, pure); the others sit in a second,
  hidden `<tbody id="mdmfRecentMore">` behind a "Show all (N)" button (`data-mdmf-toggle`, no round trip). The title still counts every task. The Cockpit
  keeps passing no step (`ALL_STEPS`): it lists everything as before.
- A DONE job whose message starts with `Nothing to do` is rendered amber "Nothing to do" (row class `mdmf-recent-nothing`, status key
  `wizard.jobs.status.NOTHING`), not green "Done", in the list and in the end-of-task toast (`jobnotice.js`: `nothingToDo`, class `mdmf-toast-nothing`,
  label `wizard.jobnotice.nothing`). The sentence itself stays visible. This is the convention shared with the other UX lots: nothing in this lot
  produces such a message, the lots that do only have to start the message with that phrase.
- The green/yellow line of an action ("Planning the landing in the background...") used to live in the service instance until another action replaced it,
  and a client-side step change kept it on screen (16:56 notice still on step 6 at 17:33). Now `ProjectWizardUserService#handleAction` clears
  `notice/noticeKind/noticeDetail` at its start (the action that has something to say sets it again), and the element carries `id="mdmfNotice"` which
  `wizard.js showStep` removes when the step changes on the client.

## 2. Live proposal progress (step 4)

- The jobs route already returns `progressJson` for a RUNNING job: for `STAGING_PROPOSAL` it is the parent step's `detail`, i.e. the
  `ProposalProgress` JSON (stages, state, startedAt/endedAt, summary). Added: `serverTime` in the jobs answer (`FactoryJobStatus.of`), so elapsed times use
  the server's clock.
- `WizardPage#renderProposalRunning` writes the six-pass list with ids (`#mdmfProposalLive`, `li[data-key]`, `.mdmf-proposal-stage-time`,
  `.mdmf-proposal-stage-summary`, `#mdmfProposalNow`, `#mdmfProposalCount`) and the words as `data-mdmf-pl-*` attributes; `wizard.js proposalLive`
  (same pattern as `stagingLive`/`masterLive`, fed by the jobs banner's poll, 3 s) repaints the state, elapsed time ("running for 1 min 12 s", finished
  stages "(12 s)"), summaries, the "Now: <pass>" line, "N of 6 passes done" and the bar, and ticks every second between two polls.
  The generic banner row (title, running time, Stop) stays: it carries the Stop button.
- `RecentTasks.durationOf` is the one duration format (Java and `wizard.js` mirror each other).

## 3. Step 4 counters

`wizard/Step4Counts` (pure, `Step4CountsTest`): ONE set of numbers computed at render from the snapshot's rows, used by (a) the "Proposal ready for review"
block (what the run produced = total per nature), (b) its waiting line "N proposals waiting for review: ..." (the old separate line under "Advanced" is
merged into the block; `renderWaitingForReviewHeader` still draws it when no one-click run exists or while it runs) and (c) the "Your decisions" side
card (one row per nature: "Fields 40: 31 accepted, 2 rejected, 7 waiting for review", the waiting part links to the "decisions left" card).
Definitions (a row is counted once, in the bucket of its status; total = sum of the buckets): business objects (SUPERSEDED hidden), fields (technical key
excluded), source links (attribute mappings), cleaning steps (MAPPING_STEP), quality rules, checks = SCRIPT_CONSTRAINT + SCRIPT_DERIVATION lines +
connector-service suggestions ("checks and services" of the decisions card). The old tallies (`proposedCounts`/`decidedCounts` of the snapshot,
`StillToDecide` tallies in the side card) are no longer rendered there; `StillToDecide` still drives the "decisions left" card itself.

## 4. Summary (step 8)

`WizardSummaryData` (tests in `WizardSummaryDataTest`) and `ProjectWizardUserService#buildSummary`:
- Landing = tables ticked AND loaded ("Not kept: N tables" as a side note); rows "received" no longer include unticked tables.
- Master = rows of the master tables; "of which N golden records" only when a matching run is DONE for some object and matching is available.
- Matching card = "N matching plans applied, out of M business objects" + one line per object ("Address: plan applied, last run failed", "Organisation:
  no plan, not run yet"); a FAILED run turns the card to "Run failed". Open points: ERROR "<object>: the last matching run failed", WARN
  "<object>: no matching plan" (only when another object has a plan).
- Completeness is grouped per object: "Person - measured on the 30 golden records" with bars, or "Organisation - not matched yet" with no bar (an object
  is "matched" when its last run is DONE).
- Data: `GoldenRecordsReader.AttributeFill` now carries `entityName`/`attribute`.
- **Hypothesis, not measured**: "Master - 30 records" while the master holds 194 rows. The SQL aggregate of `GoldenRecordsReader.queryEntityCounts`
  filtered `Linked_Field_State <> 4`, which drops rows whose state is NULL (no metadata row: never reached by a matching run), whereas the full-scan fallback
  and the large-table path count them. The filter is now `IS NULL OR <> 4`. The step-7 sentence "The master holds N golden records" (`buildDistribution`,
  not touched) still reads the golden count.

## Not done

- Step 7's "The master holds N golden records" sentence; the unticked-table rows in the journey SVG (unused); the French/English word of a
  `Nothing to do` message produced by other lots (they store English text: the status label is translated, the sentence is not).
- No live run: the proposal list repaint, the notice removal and the SQL change are verified by unit tests and by parsing `wizard.js` only.

## Live checks for the coordinator (screenshots to compare: img/25b, 26, 28b, 34b, 42b, 52b)

1. Step 4, run the proposal: the list moves pass by pass every 3 s ("Now: ...", "2 of 6 passes done", elapsed per pass, summaries), the page re-renders
   once at the end (img/26 stayed frozen for 3 min 54).
2. Step 4 after the run, then after decisions in the matrix: the block, the waiting line and "Your decisions" give the same numbers (img/28b, 34b).
3. Recent tasks: folded on arrival unless a task ended less than a minute ago; on step 6 only step-6 tasks + 3 latest, "Show all (N)" opens the rest
   (img/42b); a "Nothing to do" task reads amber.
4. A notice produced by an action disappears at the next action or step change.
5. Summary on UxCrmSf: Landing 4 tables + "Not kept: 15 tables", Master 194 records (the SQL hypothesis above), Matching "2 ... out of 3 business objects"
   with the Address failure listed in Open points, Organisation "not matched yet" (img/52b).
