# Sources+landing redesign — lot LANDING

Written 2026-09-24 on branch `feature/landing-run` (worktree `ebx-mdm-factory-sl-landing`), from the common
brief (`docs/mockups/sources-landing/*.dc.html`) and merged, after the fact, with lot KEY
(`feature/landing-composite-key`, main `f3dd59b`), whose `landing.KeyCertainty`/`ebx.LandingKeyChange` this
lot's code calls directly (its own earlier stubs of the same names were dropped at the merge - see the merge
commit).

## 1. What, in one paragraph

Wizard step 3 ("Landing") becomes a one-click "Prepare the landing" button, the same shape step 4 ("Staging")
already got from the staging-proposal-run lot: `ebx.LandingRun.start` launches, on a small daemon executor,
one background run per project that walks every SELECTED `LandingTable` through four stages - find/confirm
its **key**, build the landing **table** (its source's data model/dataset), create the **flow**, **load**
the data - and only stops for a person when no key is certain enough. Progress is a
`landing.LandingRunProgress` grid (table x stage) written into a parent `PipelineStep` (`LANDING_RUN`) the
wizard polls, exactly like `staging.ProposalProgress`/`ebx.StagingProposalRun` for step 4. Every pre-existing
per-source control (plan/generate/key picker/relations/preview) moves under an "Advanced" `<details>`
disclosure, unchanged.

## 2. API surface

- `landing.LandingRunProgress` — pure. A grid: one `TableProgress` row per landing table (`id`, `tableName`,
  `sourceSystem`), four `Cell`s (`KEY`/`TABLE`/`FLOW`/`LOAD`), states `TODO`/`RUNNING`/`DONE`/`FAILED`/
  `SKIPPED`/`WAITING` (`WAITING` only ever set on `KEY`, by `waiting(tableId, reason)`).
  `newRun(List<TableRef>)`, `start`/`finish`/`skip`/`waiting`, `isOver()` (nothing left `TODO`/`RUNNING` -
  true even with a `WAITING` table, since nothing MORE will run on its own), `isFullyDone()` (every cell
  `DONE`/`FAILED`/`SKIPPED` - false while any table is `WAITING`), `hasWaiting()`/`waitingTableIds()`,
  `doneStageCount()`/`totalStageCount()` (the "n of m stages" bar - `WAITING` does not count as done),
  `toJson()`/`parse(String)` (never throws). Unknown table ids passed to a transition are silently ignored,
  never an exception (a background thread with nobody watching it must never die on a stale reference).
- `landing.KeyCertainty` — **lot KEY's real class**, used as-is: `certain(origin, confidence,
  uniqueInSample)`. `uniqueTogether` (the sample check it is fed from) already accounts for emptiness, so
  this lot needed no separate "never empty" check of its own.
- `ebx.LandingKeyChange` — **lot KEY's real class**, used as-is by the "Change the key..." control on an
  already-loaded table: `change(session, factory, projectCode, tableId, columns)` → `Result{ok, message,
  rowsDeleted}`.
- `ebx.LandingRun` — `start`/`status`/`stop`/`applyKeyAndResume`, all EBX-bound. See section 3 for exactly
  when each stage runs, and section 5 for the open "does a Session survive its own HTTP request" question
  this lot inherits unchanged from `StagingProposalRun`.

## 3. Stage granularity — what runs per TABLE and what runs per SOURCE, and why

- **KEY runs per table, independently.** A table whose landing already left `PROPOSED` keeps whatever key it
  has (`WizardData.setTableKey` refuses to change it any more). A `PROPOSED` table whose key origin is
  already `USER`/`SOURCE` passes at once (a source-declared or person-chosen key is always certain -
  `KeyCertainty.certain`). Otherwise `KeyStudy.study(session, factory, tableId, useLlm)` runs (a NEW
  3-argument overload of the existing 2-argument method - `useLlm=false` forces the naming rule alone even
  for an administrator with a connector configured, a new `llmSkippedReasonKey` explains why); its proposal
  is applied on the spot (`KeyStudy.apply`) when `KeyCertainty.certain` says so, else the table is left
  `WAITING` with a one-line reason (`"Not unique (or empty on some rows) in the sample."` / `"Confidence too
  low (N%)."` / `"No sample evidence yet."` / `"No column is certain."`).
- **TABLE runs per SOURCE** (`ebx.LandingGenerator.generate` builds one landing model/dataset for EVERY
  selected table of that source in a single call - it was never per-table, and this lot does not duplicate
  it, per the common brief's "reuse, do not duplicate"). Attempted once every table of that source has LEFT
  `TODO` in `KEY` (settled `DONE`/`FAILED`/`WAITING`) - a `WAITING` table does not block it: the key only
  shapes the flow, never the table's own columns.
- **FLOW runs per TABLE** (revised 2026-09-24, coordinator request, using lot KEY's `LandingFlows.generateOne`
  - regenerates the flow of exactly ONE table). A table whose KEY resolved `DONE` gets its FLOW at once, via
  `LandingRun.runFlowForTable`, NEVER waiting on a sibling table of the same source that is still `WAITING` -
  the per-table independence the task brief originally asked for. A table left `WAITING`/`FAILED` in KEY has
  its own FLOW cell `SKIPPED` with a one-line reason, its siblings unaffected. Because `generateOne` answers
  a per-table `status`/`error`, a FLOW failure is always attributable to the exact table it happened on -
  unlike the old batch call, there is no "N of M table(s) failed" ambiguity for FLOW any more.
- **LOAD stays per SOURCE, precisely because `LandingFlows` has no per-table load entry point**: the only
  method is `LandingFlows.load(session, factory, projectSourceId)`, one `connectors_sync` per eligible table
  of the WHOLE source inside a single `LANDING_LOAD` `PipelineStep` - there is no `loadOne` the way
  `generateOne` exists for FLOW (confirmed by reading `LandingFlows.java`: `generateOne`'s own Javadoc even
  says it is the ONE table variant "the write `ebx.LandingKeyChange#change` needs", explicitly contrasted
  with the batch `load`). `LandingRun.runLoadForSourceIfReady` therefore keeps the original source-level
  wait: attempted only once EVERY table of the source has left `WAITING` in `KEY` - one waiting table in a
  five-table source still holds up that source's LOAD (but never another source's, and never that source's
  OWN already-flowed tables' FLOW cells, which do not wait on it). A partial LOAD batch failure is still not
  distinguished per table, for the same reason as before (`LandingFlows.load` reports a failed COUNT, not
  which table).

**Resume**: once a person applies a key to a `WAITING` table (`LandingRun.applyKeyAndResume` - the wizard's
key chooser), that table's `KEY` cell settles `DONE` and its OWN FLOW runs at once (`runFlowForTable`,
independent of any sibling); `runLoadForSourceIfReady` is then re-checked for the whole source, and only
fires the batch load once every table of that source has resolved. Both run in the background - same
executor, same parent `PipelineStep` row (a resumed run never creates a second step). `runLoadForSourceIfReady`
only writes a `LOAD` cell that is still `TODO`, so calling it twice for the same source (once from the main
run, once from a later resume) never re-skips an already-settled cell. **Stop**: `LandingRun.stop` sets an
in-memory, per-project
cancellation flag (`ConcurrentHashMap<String, AtomicBoolean>`, this JVM only - lost on a restart, same
limitation `StagingProposalRun`'s own daemon executor already accepts) checked BETWEEN stages/sources, never
inside one (a stage already running finishes); every cell still `TODO` when the run notices the flag is
marked `SKIPPED` "Stopped by a person." (never left `TODO` forever - see `LandingRunProgress`'s own `isOver`
contract), and the parent step ends `DONE` (not `FAILED` - stopping is a deliberate choice, not an error).

The parent `PipelineStep` is kept `RUNNING` (not closed) for as long as ANY table is `WAITING`, even though
the executor genuinely has nothing left to do - `LandingRun.maybeCloseStep` only ends it once
`!progress.hasWaiting() && progress.isOver()`. This is deliberate: the mockup's Running screen shows the
grid and the "table(s) wait for you" banner together, not a separate "paused" screen, so the wizard's own
`landingRunRunning` flag (which drives the polling AND which of the three screens renders) must stay true
through a wait, not flip to "done" with tables still incomplete.

## 4. Screens (`wizard.WizardPage`/`ebx.ui.ProjectWizardUserService`, `wizard.js`, `wizard.css`)

- **Intro** (no run yet): `renderLandingRun`/`renderLandingRunForm` — plain-language explanation, the four
  numbered items, a glossary line, and the primary "Prepare the landing" button with the "use the language
  model" checkbox (default on) and its disclosure line. Gate reuse: `WizardRules.ActionGates#generateLanding`
  (the same "is there a landing to work over" gate the old primary button used), no new pure gate added.
- **Running**: `renderLandingRunning` — a table x stage grid (one row per landing table, four state cells),
  an overall "n of m stages" bar, a "table(s) wait for you" banner naming the count, a "Choose the key"
  button on every `WAITING` cell (opens `renderLandingKeyChooser` right under that table's row), "Stop the
  run". `wizard.js` polls this state every 5s (`data-mdmf-landing-running="true"` on the wizard root,
  `LANDING_RUN_POLL`, a plain no-op re-render server-side) - identical mechanism to
  `data-mdmf-proposal-running`/`STAGING_PROPOSAL_POLL`.
- **Key chooser** (`renderLandingKeyChooser`, action `LANDING_KEY_CHOOSE` opens it, `LANDING_KEY_APPLY`
  writes and resumes) — **revised 2026-09-24, coordinator follow-up, to match `LandingReview.dc.html`
  properly**: candidate CARDS (the proposal's own single column first, then every other column measured
  unique in the sample, capped at 4) that are plain buttons (`data-mdmf-key-select`) moving the checked state
  onto ONE real checkbox set (`.mdmf-key-check`, inside the "Several columns together" builder,
  `renderCandidateCard`/the composite `<div>`) - never a second, independent input per column. Checking or
  unchecking ANY of those boxes, or clicking a candidate card, triggers a LIVE recompute in `wizard.js` (no
  round trip): the "unique (together) in N of M" / "never empty" / "empty on N rows" / "partly empty on N
  rows" chips, the sample table's highlighted key columns (`renderSampleTableHighlighted`,
  `data-mdmf-key-col`), and the "Use `<key>` and continue" button's own label all update from the SAME
  embedded sample (`LandingKeyChooserJson.sample`, exactly `KeyEvidence.sampleJson(report.sampleColumns,
  report.sampleRows)`, pinned by `LandingKeyChooserJsonTest`). `wizard.js`'s recompute mirrors
  `KeyEvidence#combination`'s own counting rules (empty/partly-empty/all-empty, distinct-together) as an
  INDEPENDENT reimplementation - it is never compared back against a server value, and the eventual
  `LANDING_KEY_APPLY` write re-measures nothing client-side, it just posts whatever is checked. Every
  localized template the JS needs is read WITHOUT `MessageFormat` arguments (so its `{0}`/`{1}` placeholders
  survive) and passed as `data-mdmf-i18n-*` attributes on the panel - no translation table duplicated in
  JavaScript. Also reachable from "Change the key..." on an already-loaded table
  (`LANDING_KEY_CHANGE_PICK`/`LANDING_KEY_CHANGE`, `ebx.LandingKeyChange`) under the Advanced per-source
  table, which **keeps the OLD plain checkbox picker** (per the coordinator's explicit instruction) - its own
  button label states the consequence (data loss + reload) as the confirmation, since no separate modal/
  second-click confirm step was built.
- **Done**: `renderLandingRunDone` — the summary line (selected table count, total rows loaded, both real
  figures already measured, never re-derived), plus a `<details>` fold per table whose key has 2+ columns
  (`renderLandingCompositeFold`, `LandingComposite.dc.html`): key chips + origin come straight from
  `WizardSnapshot.TableInfo` (already known, no round trip); the "why one column is not enough" bars (each
  key column's own distinct-of-sample count, unique/repeats chip, then the SAME for the columns together -
  `KeyEvidence.combination`) only render once a person opens the fold (`LANDING_KEY_COMPOSITE_DETAIL` fetches
  a fresh `KeyStudy.study(..., false)` - evidence/sample only, the language model NEVER runs for this) - see
  the deviation note below for why this needs a click rather than rendering unconditionally.
- Advanced disclosure: every pre-existing step-3 control (plan/generate/key picker/relations/preview/key
  study), byte-for-byte unchanged behaviour, just moved under `<details class="mdmf-disclosure">`.

### Deviations from the mockups, flagged explicitly

- **The composite-key fold (`LandingComposite.dc.html`) needs one click to open, not zero.** The mockup shows
  it fully populated; this implementation cannot, because the "why one column is not enough" bars need a
  FRESH sample (`KeyEvidence.combination` over `report.sampleColumns`/`report.sampleRows`), and this
  codebase's own, consistently-followed rule is that `WizardPage.render` is a pure function of the `Model` -
  every live EBX/connector read happens in an explicit `ProjectWizardUserService` action, never automatically
  during a plain render (the SAME reason the original "Study the key" panel needs its own `TABLE_KEY_STUDY`
  click). Rendering the fold for every multi-column-key table unconditionally would mean re-sampling every
  such table's SOURCE on every single render of the done screen - expensive and a real side effect from what
  looks like a passive page load. `LANDING_KEY_COMPOSITE_DETAIL`'s one click is the honest cost of measuring
  something real instead of inventing plausible-looking bars.
- **No computed, per-row `_sourceId` preview column.** The mockup's "How a row is recognised" table shows an
  actual `_sourceId` value per sample row (the key parts joined with `|`, a `|` inside a value escaped as
  `\|`). Reproducing that exactly would mean either duplicating `LandingFlow`'s own SQL-generation escaping
  rule (`dialect.escapeKeyPart`, §2/§8 of that class - built to emit SQL text, not a plain Java string
  transform) or writing a second, parallel escaping implementation purely for this preview - both judged out
  of scope for the time available. The fold instead states the RULE in words (`wizard.step3.run.composite.lead`
  — "the value of each is joined with `|` to form `_sourceId`; a `|` inside a value is escaped as `\|`") and
  still shows the real, unmodified sample rows underneath (highlighted on the key columns), just without a
  computed `_sourceId` column added to them.

### The `data-mdmf-key-input` reuse, on purpose

The key chooser, the composite fold's sample table and the "Change the key..." picker are all rendered
wrapped in a `<td>` so `wizard.js`'s EXISTING `data-mdmf-key-input="1"` collection (`b.closest("td")`, gather
checked `.mdmf-key-check` values into the hidden `mdmfKeyColumns` field) works completely unmodified for the
final `LANDING_KEY_APPLY`/`LANDING_KEY_CHANGE` write - only the LIVE, in-browser recompute (stats, highlight,
button label) needed new `wizard.js` code; the "what gets posted" mechanism did not change at all.

### Visual check against the mockups, round 1 (coordinator, 2026-09-24)

A throwaway harness (`Preview.java`, never in this repo) rendered the REAL `WizardPage.render` for five
states into static HTML with `factory.css`/`wizard.css`/`wizard.js` inlined, opened in a browser. Added the
four-card "what the factory does" row, a coloured background per grid-cell state, and verified the key
chooser's live recompute worked end to end. Superseded by round 2 below on several points (the harness
itself was missing `review.css`, so no button was actually purple in these screenshots - not caught then).

### Visual check against the mockups, round 2 (coordinator, 2026-09-24) - fixes to round 1

The coordinator reviewed round 1's five renders (headless Chrome, 1400px) and returned ten concrete defects.
All ten addressed on the same branch:

1. **Harness now also inlines `review.css`** (it styles `.mdmf-btn`/`.mdmf-btn-primary` - without it every
   button rendered as a bare browser button, which is what made item 2 below look like a real bug).
2. Primary buttons ("Prepare the landing", "Use ... and continue", "Continue to the staging") were already
   `.mdmf-btn .mdmf-btn-primary` in the Java - the missing purple was entirely item 1's harness gap.
3. **`wizard.step3.title`/`.lead`** replaced (the old text - "a pure function of the catalog's scanned
   metadata, no language model" - became false the moment the one-click run's key step could call an LLM);
   the intro card's own duplicate "Bring the raw data into EBX" heading removed.
4. **"Advanced" is closed by default** in the real page (confirmed: never rendered with an `open` attribute)
   - round 1's harness forced EVERY `<details>` open indiscriminately, which is what made every render look
   like Advanced was open; the harness now opens only the composite fold (content set server-side regardless)
   and, for the one state that needs it, "Advanced" itself.
5. **State dot overlap fixed**: `.mdmf-table td`'s own padding rule was MORE specific than the single-class
   `::before` trick, so the dot sat on the text instead of before it. Replaced with a real 20px inline-block
   `<span>` circle (`renderStateCircle`, one glyph per state - check/alert/ellipsis/dash) that cannot be
   pushed under anything by a more specific selector.
6. **`renderKeyChips`** (columns as chips, "+"-joined, plus an origin chip) is now the ONE place a key
   renders - the running grid's `KEY` column (looked up fresh from `WizardSnapshot.TableInfo`, not the
   progress cell's own text summary, once `DONE`), the Advanced table's non-editable key cell, and the done
   view's new per-table list all go through it.
7. **Counting consistency**: `WizardPage#displayDistinct`/`#displayEmptyRows`/`#displayUnique` are now the
   ONE place "unique in N of M" is derived from a `KeyEvidence.Combination` (a single-column candidate card
   is just a one-column combination now, not `KeyEvidence.Column`'s own "distinct non-empty" convention,
   which is exactly what disagreed with the live builder before) - a row with any empty key part no longer
   counts toward the numerator. `wizard.js`'s `recompute()` mirrors the SAME arithmetic. Singular/plural
   (`emptyOnRow`/`emptyOnRows`) through two message keys, chosen by count in both Java and JS.
8. **Intro**: `renderLandingFlowPicture` (application table card → arrow with key chips or "key to find" →
   landing table card with the four technical-column chips), one row per selected table; a local two-column
   `mdmf-landing-layout`/`-main`/`-sidebar` flex (never the shared `.mdmf-wiz-main`) with the glossary block
   in the sidebar; the four numbered cards stayed.
9. **Done view**: a per-table list (`renderKeyChips` + rows loaded + a `TABLE_PREVIEW` Preview link, reusing
   the exact panel the Advanced table already renders) and a purple "Continue to the staging" block
   (`data-mdmf-goto-step`, the wizard's own client-side step switch, no round trip). The composite fold's
   "why one column is not enough" is now bars (`renderCompositeBar`, width = distinct/rows), and the sample
   table gets a computed `_sourceId` column - `landing.LandingFlow#sourceIdPreview(List<String>)`, a new pure
   helper mirroring `SqlDialect#escapeKeyPart` EXACTLY (backslash doubled first, then `|` escaped), pinned by
   a new `LandingFlowTest#checkSourceIdPreview` (parts escaped only for 2+ columns, verified live in the
   harness: `ACME|FR` + `1` renders `ACME\|FR|1`).
10. **Running**: the progress bar and the "N table(s) wait for you" banner (now carrying one "Choose the key"
    button per waiting table) moved above the grid.

Verified live in the browser (not just read): items 6/7 (candidate card and live builder now both read
"unique in 4 of 5 · not a key" + "empty on 1 row" for the same EmailAddress selection - the exact
contradiction reported), item 9's `_sourceId` column, item 4's closed-by-default Advanced.

**What still differs, not fixed in round 2**: no `.mdmf-landing-layout` was exercised at a genuine two-column
desktop width inside THIS tool's own preview pane (it renders correctly per plain flexbox rules, confirmed
reading the CSS, but the pane's own viewport made a true side-by-side screenshot hard to capture); the
Advanced disclosure's per-source table keeps its existing dense table look, not restyled to the mockup's
card style.

### Round 3 (coordinator, 2026-09-24) - exact counting, WAITING vs SKIPPED, plural wording

Three more fixes, all verified with `tools/test_local.sh` and re-rendered live in the browser:

1. **`WizardPage#displayDistinct` (an approximation) replaced by an exact, tested definition in
   `landing.KeyEvidence`**: `Combination` gained `rowsUniqueComplete` - rows whose key has NO empty part AND
   whose joined key value occurs EXACTLY ONCE among those complete rows, computed with a second pass over
   `combination()`'s own rows (a `Map<String,Integer>` of combined-value → count, restricted to complete
   rows only). `unique()` is now simply `rowsInSample > 0 && rowsUniqueComplete == rowsInSample`. Every
   caller (candidate cards, the composite fold's bars, `wizard.js`'s live builder) now reads
   `rowsUniqueComplete` directly - `WizardPage#displayDistinct`/`#displayEmptyRows`/`#displayUnique` are
   gone. `wizard.js`'s `combination()` mirrors the SAME two-pass algorithm byte-for-byte (a `completeCombined`
   array, a counts object, a second loop counting entries whose count is 1) - independently implemented,
   still never compared back against a server value (see that block's own header comment). `KeyEvidenceTest`
   gained a case with SEVERAL different partly-empty combinations alongside a duplicate among the complete
   rows (`distinctTogether` says 4, the old approximation would have said "2 unique", the exact answer is 1) -
   this is exactly the shape of sample the old approximation got wrong.
2. **FLOW/LOAD blocked behind a WAITING key are now WAITING, not SKIPPED.** `LandingRunProgress` gained
   `waitingStage(tableId, stage, reason)` (generic, any stage) alongside the existing `waiting` (KEY only);
   `ebx.LandingRun`'s two call sites that used to `skip(..., "Waiting for ...")` now call `waitingStage`
   instead - `SKIPPED` means "will not run this pass", which was never true here (the stage WILL run, the
   moment `applyKeyAndResume` resolves the key). The running grid renders this as a PASSIVE wait: the same
   dashed/muted circle and cell tint as `SKIPPED` (`WizardPage#renderStateCircle`'s new `pPassiveWait` flag),
   with its own wording "Waits for the key" (`wizard.step3.run.state.WAITING.stage`) - distinct from the
   `KEY` column's own active "Needs your eye" (solid orange alert), since a FLOW/LOAD cell is not asking that
   table to do anything itself. `LandingRunProgressTest#waitingStageIsWaitingNotSkipped` covers the whole
   chain: WAITING (not SKIPPED) on FLOW/LOAD, excluded from `doneStageCount`, `isOver()` true /
   `isFullyDone()` false while waiting, then `isFullyDone()` true once every cell resolves.
3. **Singular/plural through the bundle, one key per form, swept across every step-3 text this lot wrote.**
   `WizardPage#plural(locale, count, oneKey, otherKey)` is the one place a count picks between two message
   keys (English rule: singular only at exactly 1). Fixed: `wizard.step3.run.waitingBanner` (now `.one`/
   `.other`, was reported literally as "1 table(s) wait for you."); `wizard.step3.run.done.summary` split
   into two independently pluralized sentences (`tablesLanded`/`rowsLoadedTotal` - a single template with
   two `(s)` cannot be correctly pluralized on two independent counts at once); `wizard.step3.run.done
   .rowsLoaded` (the per-table row count in the done view's list); the intro glossary's "column(s)" reworded
   to "one column, or several together" (not count-driven, so no `.one`/`.other` pair applies - there was no
   `{0}` to switch on).

## 5. What was verified, and how

`ON_EBX_AGENT_JAR=.../on-ebx-agent.jar ON_EBX_MAPPING_JAR=.../on-ebx-mapping.jar tools/test_local.sh` ends
**"all green"**: the XSD regenerates identically (a new `LANDING_RUN` `PipelineStep.step` enum entry — see
`tools/build_xsd.py`'s own comment on why this is a label-only addition, a static enumeration being
non-blocking in EBX, CLAUDE.md section 2), every `.properties` file stays plain ASCII, no CSS file lost a
comment-balance check, the whole module compiles clean, and every `*Test.java` runs, including this lot's
own new ones — `LandingRunProgressTest` (new run all-`TODO`, every transition including `waiting`, `isOver`
vs `isFullyDone` vs `hasWaiting`, `doneStageCount`/`totalStageCount`, JSON round-trip, `parse` on null/blank/
garbage, an unknown table id silently ignored on every transition) — and `WizardPageTest`/`WizardMessagesTest`/
`WizardStyleDiagramTest`, unchanged and still green, confirming the step-3 rewrite did not break the
pure-page tests already pinned to this file. `landing.KeyCertaintyTest` is lot KEY's own (kept as-is at the
merge, this lot's earlier duplicate dropped). **Coordinator follow-up (2026-09-24)**: `LandingKeyChooserJsonTest`
(new) pins the EXACT JSON string `LandingKeyChooserJson.sample` produces for a small fixture (one object per
row, keyed by column name, an empty sample answering `"[]"`) - this is the one contract point `wizard.js`'s
live composite-key recompute reads, so a change to that shape would fail loudly here rather than silently
breaking the client-side check.

## 6. What was NOT run live

No live EBX instance was used this session for anything write-capable (the common brief's own read-only-MCP
rule, and this lot's whole surface — a background orchestrator, key studies, flow/load batches — is
write-capable by nature). In particular, never exercised against real data:

- The full `LandingRun` chain end to end on a real project (Personnes or otherwise): stage sequencing across
  several sources, the KEY→WAITING→resume path, the FLOW/LOAD-waits-for-every-table-of-its-source behaviour,
  the Stop button's mid-run cancellation. Only `LandingRunProgressTest`'s pure model was exercised.
- Whether a captured `Session` survives past the end of its own HTTP request on this instance — the SAME open
  question `StagingProposalRun`'s own Javadoc documents, inherited unchanged (this lot's background thread
  has the identical shape: closes over the caller's `Session`, no agent account). The same two lines of
  defence apply: a session EBX refuses fails loudly on the first EBX call inside `runInBackground` (caught,
  recorded as a `KEY` failure), and `LandingRun.STALE_AFTER_MINUTES` (30) lets a fresh `start` supersede a
  step stuck `RUNNING` with no progress.
- The new `KeyStudy.study(..., useLlm)` overload's `false` branch — that the naming rule alone genuinely runs
  and the model genuinely is not called; only compiled and read against the existing gated branches above it.
- The wizard's three new step-3 screens (intro/running/key chooser/done, the Advanced disclosure, the polling
  reload) — never rendered in a browser or driven through a live Tomcat; only the pure, string-level
  `WizardPageTest` (unchanged, still passing) exercises any of `WizardPage.java`.
- `ebx.LandingKeyChange`/`landing.KeyCertainty` themselves — lot KEY's own code, used here exactly as merged,
  not re-verified by this lot beyond "it compiles against the calls this lot makes".
- **The `wizard.js` live recompute itself, in a real browser** (coordinator follow-up, 2026-09-24): the
  candidate-card click handling, the checkbox `change` listener, the mirrored `combination()` counting logic
  and the `data-mdmf-i18n-*` template substitution were written and read carefully against `KeyEvidence
  #combination`'s own Javadoc, but never executed - no browser/Tomcat was available this session. Only
  `LandingKeyChooserJsonTest` (Java-side, pins the JSON shape the JS parses) gives any real assurance here;
  the counting logic's actual behaviour on real sample data, and whether the highlight/label updates render
  as intended, are unverified.
- `ebx.LandingRun`'s per-table FLOW independence (section 3, revised 2026-09-24) and the composite-key fold's
  `LANDING_KEY_COMPOSITE_DETAIL` round trip - both compile and read correctly against `LandingFlows.generateOne`/
  `KeyStudy.study`'s own contracts, neither was exercised against a live EBX instance.

## 7. Guesses and judgement calls worth flagging

- **Revised 2026-09-24 (coordinator request)**: FLOW is now per TABLE (`LandingFlows.generateOne`, lot KEY) -
  a table's flow is never delayed by a sibling still `WAITING`. LOAD stays per SOURCE because `LandingFlows`
  genuinely has no per-table load entry point (`load(session, factory, projectSourceId)` is the only method -
  see section 3) - this is not a scope choice, it is what the reused API offers. Re-read section 3 before
  assuming full per-table independence covers LOAD too; it does not, and cannot without a `LandingFlows` API
  change (out of this lot's scope, a shared class no lot was asked to redesign).
- **The parent step stays `RUNNING` through a `WAITING` pause** (section 3, end) rather than a distinct
  "paused" `PipelineStep.status` value — chosen so the wizard's existing running/done dispatch (one boolean,
  `landingRunRunning`) needed no third branch, at the cost of `LandingRun.status`'s `running` flag technically
  meaning "still needs attention, one way or another" rather than literally "a background thread is active
  right now".
- **A partial LOAD batch failure (some tables of a source ok, some not) is not distinguished per table** in
  the grid — every table of that source gets the same "N of M table(s) failed" LOAD cell, since
  `LandingFlows.load` reports a failed COUNT, not which table, without re-parsing its own `PipelineStep.detail`.
  FLOW no longer has this limitation (see above): a FLOW failure is always attributed to its own table now,
  via `generateOne`'s per-table `status`/`error`. A real per-table LOAD breakdown would need either a
  `LandingFlows` API change (out of scope) or parsing its `detail` JSON structure (a private convention of
  that class, not built to be read back by a caller) — flagged as a follow-up, not attempted.
- **`landingRunUseLlm`'s checkbox state is not persisted across postbacks server-side** (unlike, say, the
  staging-proposal-run lot's own quality/capability checkboxes, which ALSO turn out not to be persisted
  server-side either — confirmed by grep, `ProjectWizardUserService` never reads them back into the Model,
  the checkbox's own `checked="checked"` in the freshly-rendered HTML is what "sticks"). Same convention,
  deliberately not changed here.
- **"Change the key..." does not auto-reload** after a successful change — `ebx.LandingKeyChange` itself
  documents that it never reloads on its own; the existing Advanced-disclosure "Generate" button (which
  already runs model+flow+load together for a whole source) is the way to reload afterwards. Not automated
  in this lot, to avoid silently starting a real, possibly slow, connector sync from what reads like a
  "just fix the key" click.

## Live fixes 2026-09-24

Six bugs seen LIVE on project Vague1 (branch `fix/landing-run-live`, worktree `ebx-mdm-factory-landing-live`,
from `main` `2993a63`), fixed in `ebx.LandingRun`, `wizard.WizardPage`, `ebx.ui.ProjectWizardUserService` and
`wizard.js`. `tools/test_local.sh` green after every fix (full suite, no new pure-logic tests: every change
here is EBX/session-bound `ebx.*`/rendering code, the same class of code this file's own class already has no
JUnit-free test for).

1. **The "use the language model" checkbox never actually reached the server.** `wizard.js`'s `syncAlways()`
   copies the checkbox's checked state into a hidden field it looks up by id `mdmfLandingRunUseLlm` right
   before `LANDING_RUN_START` submits - but `WizardPage` never rendered a hidden field with that id (only the
   checkbox itself, id `mdmfLandingRunUseLlmCheckbox`), so `byId(...)` found nothing, nothing was copied, and
   `ProjectWizardUserService.paramBool` always read the missing parameter as `false`. The one-click run
   therefore NEVER called the language model, whatever the checkbox showed (checked by default) - `KeyStudy
   .study(..., false)` ran the naming rule alone, which does not recognise a name like `BusinessPartner`, so
   `report.proposal` stayed null and `LandingRun.runKeyStage` reported "No column is certain." even though a
   manual "Study the key" (which calls the 3-argument `KeyStudy.study` directly, no checkbox involved) had
   shown a 100%-confidence LLM proposal, unique in 25 of 25, never empty - which by `KeyCertainty.certain`
   really is `CERTAIN`; `KeyCertainty` itself was never at fault. Fix: added the missing
   `WizardPage.hidden("mdmfLandingRunUseLlm", "true")` next to the other run-checkbox hidden fields. Test: not
   independently unit-tested (`WizardPageTest`'s pinned-string assertions did not target this exact hidden
   field; re-ran green after the change) - **needs a live re-check**: start a run with the checkbox left
   checked and confirm the language model actually runs for a table like SAP's `BusinessPartner`.
2. **A resume skipped TABLE, and an uncaught `RuntimeException` from `LandingGenerator.generate` could silently strand a whole run.**
   `runTableStage` only caught `OperationException`; a `RuntimeException` (an unwrapped EBX/connector error)
   propagated straight past it, past the per-source loop, into `runInBackground`'s own outer catch-and-log -
   which never persists the `RUNNING` state `LandingRunProgress.start` had only set IN MEMORY, so TABLE stayed
   `TODO` in what was actually written to `PipelineStep.detail`, and the run silently never reached FLOW/LOAD
   for that source (or any later source). `applyKeyAndResume` then only ran `runFlowForTable`/
   `runLoadForSourceIfReady`, assuming TABLE had already settled - which is why "Use BusinessPartner and
   continue" moved KEY to DONE but left TABLE "To do", and FLOW then failed with "no landing dataset yet."
   Fix: added the missing `catch (RuntimeException ex)` to `runTableStage` and `runLoadForSourceIfReady`
   (mirroring `runKeyStage`/`runFlowForTable`, which already had it - "a background thread with nobody
   watching it must never let one table's bug take the run down"), and added `runTableStageIfNeeded` -
   `applyKeyAndResume` now runs every stage still `TODO` for the resumed table's source, in order: TABLE (only
   if needed), then FLOW, then LOAD. Also added `LandingRun.friendlyMessage`: `LandingGenerator`/`LandingFlows`
   raise messages meant for their REST callers ("... run POST /sources/13/landing/flows first"), which a
   person reading this grid should never see verbatim - recognises the fixed shapes those two classes raise
   and rewrites them in plain sentences; anything else passes through unchanged, and a residual "explanation:
   run POST ..." shape is stripped defensively. Test: not independently unit-tested (same EBX-bound-code
   caveat as bug 1); `tools/test_local.sh` green. **Needs a live re-check**: reproduce the original sequence
   (let a table go WAITING, apply a key, confirm TABLE/FLOW/LOAD all actually run and the messages read as
   plain sentences) - the exact `RuntimeException` `LandingGenerator.generate` raised for SAP's table was never
   identified (only made non-fatal), so it could still resurface as a genuine TABLE failure, now visible and
   recoverable instead of silently stranding the run.
3. **The wizard kept polling every 5s while every remaining table was WAITING, wiping the key chooser's in-progress checkboxes.**
   `wizard.js` gated the poll on `data-mdmf-landing-running`, which stays `"true"` for as long as the run is
   PAUSED on a WAITING table too (deliberately - see section 3's "the parent step stays RUNNING through a
   WAITING pause"), so a poll kept firing even with nothing left running in the background, and a full-page
   reload mid-poll silently dropped whatever the "Several columns together" builder had checked. Fix: a new,
   narrower `data-mdmf-landing-poll` attribute (`WizardPage#render`), `"true"` only while `landingRunProgress`
   is NOT `isOver()` (something genuinely still `TODO`/`RUNNING`) AND no key chooser panel is open on this
   render (`landingKeyChooserTableId` empty); `wizard.js`'s poll block now checks that attribute instead.
   Applying a key (`LANDING_KEY_APPLY`) both resumes real background work (not `isOver()` any more) and closes
   the chooser, so polling resumes on the very next render. **Needs a live re-check**: never exercised in a
   browser - confirm a poll genuinely stops once every table is WAITING, and that opening the key chooser
   under a *running* (not yet WAITING) grid also correctly suppresses the next poll.
4. **The "landing run has started" and "Key saved." notices kept re-appearing on every poll, for minutes.**
   `this.notice` and `this.landingKeyAppliedMessage` are plain instance fields of the open `UserService` pane
   (which is NOT rebuilt fresh per request - only its form fields are, per CLAUDE.md §9); `LANDING_RUN_START`/
   `applyLandingKey` set them once each, and nothing ever cleared them, so every subsequent `LANDING_RUN_POLL`
   re-rendered the same stale notices alongside whatever the CURRENT poll actually reported - explaining the
   "four notices at once" report (a genuine one from the current action, plus leftovers from every earlier
   one). Fix: `LANDING_RUN_POLL` now clears both fields before returning, so each notice shows exactly once,
   on the response of the action that produced it. Test: `tools/test_local.sh` green; **needs a live
   re-check** (never exercised under Tomcat).
5. **A run paused on a WAITING table could be superseded by a fresh start, or misreported as merely "old", once it crossed 30 minutes.**
   `isStale` only ever looked at the row's age; Project B was seen RUNNING for hours with a WAITING key,
   which is the CORRECT state for a run waiting on a person, not evidence of one stuck dead - but the
   30-minute rule did not distinguish the two, so a fresh `LandingRun.start` after 30 minutes would have
   silently abandoned a run a person simply had not gotten back to yet. Decision (documented here, per the
   coordinator's ask): **a run paused on a `WAITING` table is never stale**, however old - the stale-after-30-
   minutes rule exists only for a run that died silently (a crashed background thread, a session that stopped
   working mid-run) with NOTHING waiting for a person's attention; resolve a genuinely paused run with the key
   chooser or "Stop the run", never by starting a new one out from under it. Fix: `start()` now checks
   `hasWaiting()` FIRST (unconditionally refuses a fresh start with a clear reason, before even considering
   age) and `status()`'s own `"stale"` flag is now `running && !progress.hasWaiting() && isStale(row)` - the
   same rule in both places, so the UI and the actual supersede logic can never disagree. Test:
   `tools/test_local.sh` green; **needs a live re-check** on Project B specifically (confirm `status()` no
   longer reports it "stale" and that a fresh `LANDING_RUN_START` on it is refused, not superseding).
6. **The grid was a stale snapshot: a table built/loaded through the Advanced disclosure's own "Generate the landing" button never updated the one-click run's own progress.**
   The two paths are fully independent and both fully usable in parallel (by design - see the "Advanced
   disclosure" bullet above); the running grid, though, is only ever written by `LandingRun`'s own code, so a
   table actually `LOADED` through the OLD button still showed "Needs your eye / No column is certain" with
   FLOW/LOAD "Waits for the key" forever, and the step never closed even though nothing was left to do. Fix:
   `LandingRun.status` (now taking a `Session`) reconciles the persisted grid against each table's own real
   `LandingTable.status` while the step is `RUNNING`, before reporting anything:
   `reconcileWithActualTableState` settles a table's cells from `GENERATED` (KEY+TABLE done) or `LOADED`
   (all four done) whenever the grid shows less progress than that - never regressing an already `DONE`/
   `FAILED` cell - persists the correction, and closes the step (same success/failure message
   `maybeCloseStep` would write) once the reconciled grid is fully over. `ProjectWizardUserService` now passes
   `this.session` into `status()`. Combined with bug 4's fix, this should also address the "four notices at
   once" report: the stale "Landing generated..." notice (bug 4) sat alongside a grid banner ("1 table waits
   for you") that reconciliation now clears once the table is actually loaded. Test: `tools/test_local.sh`
   green; **needs a live re-check** (the most consequential unverified fix of this batch) - confirm on Vague1
   that a table generated/loaded via the Advanced button gets its cells reconciled to DONE on the very next
   render, and that a fully-reconciled run actually closes (moves from the "running" screen to "done").
