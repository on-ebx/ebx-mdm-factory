# Sources-landing lot SOURCES (2026-09-24)

Scope: task brief for lot SOURCES of the "sources + landing" wizard-step-2 redesign (three ways in, one
progress view, one review, per `docs/mockups/sources-landing/Main.dc.html` /
`SourcesAdding.dc.html` / `SourcesReview.dc.html`), a coordinator follow-up the same day asking for the
three pieces an initial pass had left out (wiring `ebx.SourcesRun`, the Review screen, the start screen),
a SECOND coordinator follow-up asking for an actual visual pass (the product owner validated the mockups
because they are graphic and simple, and "reuses the existing component vocabulary" was not what was
validated), and a THIRD coordinator follow-up (rendered the four states headless, 1400 wide, with concrete
fixes) closing the remaining gaps between the rendered page and the mockups. This document records what
was actually built, what was verified and how, and - per the common brief's own instruction ("say so
rather than invent data") - every part of the brief that is still **not** completed, with the reason.

## Live fixes 2026-09-24

Data/wording/logic bugs found running the Review screen live ((a local scratch file)), diagnosed
with the read-only `data-access` MCP tools against `MdmFactory`/`DataCatalog`/`Connectors`, no writes. The
screenshot's two application titles (`PERSONNES_SALESFORCE`, `PERSONNES_SAP_SAMPLE`) match `ProjectSource`
ids 8/9 of project **`A`** (a scratch project, `ProjectSource` rows read live: id 8 `sourceSystem =
PERSONNES_SALESFORCE`, `catalogSource = PERSONNESSALESFORCESAMPLE`, status `GENERATED`; id 9 `sourceSystem =
PERSONNES_SAP_SAMPLE`, `catalogSource = PERSONNESSAPSAMPLE`, status `SELECTED`) - not project `Personnes`
(ids 3/4, `sourceSystem = SALESFORCE`/`SAP`, both fully `LOADED`, used below as a second data point). Worked
from that same worktree branch, `fix/sources-step-live`, no CSS touched (a parallel branch,
`feature/wizard-visual-pass`, owns the visual pass).

1. **Kind chip said "Live connection" for two sample-file sources.** Measured: `DataCatalog/Source` rows
   `PERSONNESSALESFORCESAMPLE`/`PERSONNESSAPSAMPLE` both carry `platform = OTHER` - the catalog's own
   `platform` is a coarse category (`CLOUD_STORAGE`/`DATABASE`/`OTHER`...), never the string
   `sample-file`, and `WizardData.sourceInfo` was reading exactly that field for the chip. The REAL signal
   lives on the connector: `Connectors/Connector` row `PersonnesSalesforceSample.type = "sample-file"`
   (read live). Fix: `WizardData` now reads `Connector.type`/`Connector.label` directly off the Connectors
   dataspace (`WizardData.connectorFacts`, same "read as data, no ebx-connectors compile dependency"
   duplication `SampleSourceImporter`/`DeliveryDomainReader` already use, and the same fact
   `SourceOnboarder.Facts#connectorType` already reads through `connectors_describe` for the
   still-in-progress cards) and prefers it for `SourceInfo.platform`, falling back to the catalog's
   `platform` only when the connector row itself cannot be found. `WizardPage`'s own chip check
   (`"sample-file".equals(pSource.platform)`) did not need to change - only the data feeding it was wrong.

2. **Titles showed the raw application code, all caps** (`PERSONNES_SALESFORCE`/`PERSONNES_SAP_SAMPLE`).
   Measured: `ProjectSource.sourceSystem` genuinely holds these values - a legitimate, auto-suggested
   application CODE (within the 40-char/`[A-Za-z][A-Za-z0-9_]*` limit, `WizardRules.CODE_MAX`/
   `CODE_PATTERN`), not corrupted data; the "Application code" input correctly still shows it, and its
   visual truncation at 90px is a CSS layout concern left to the visual-pass branch. The bug was using this
   CODE as the card's readable NAME. Fix: new `WizardPage.displayName(SourceInfo)` - the catalog source's
   own label when there is a catalog link (`SourceInfo.catalogSourceLabel`, already falls back to the
   catalog id when the catalog has no real label), else the connector's own label (new
   `SourceInfo.connectorLabel`, from the same `connectorFacts` read as bug 1), else the `ProjectSource` row's
   own id - used for both the Review card's `<b>` header and the trust-order sidebar's row name (which had
   the identical bug, same raw `sourceSystem`).

3. **"18 columns, 0 rows" on a table that had loaded 180.** Measured: `LandingTable.sourceRowCount` (what
   the catalog profiled) is null for these sample-file sources - the catalog rarely profiles them - and
   `WizardData.tableInfo` coerced that null to the primitive `0`, indistinguishable from a real profiled
   zero; `LandingTable.lastLoadRows` (the landing's own last load) held the real count (180 for
   `PERSONNES_SALESFORCE`) unused. Fix: `TableInfo.sourceRowCountKnown` (new, set from whether the raw field
   was actually a `Number`) plus a new pure class `wizard.TableRowCount` (tested,
   `TableRowCountTest`): profiled count when known (may legitimately be a real zero - kept, never
   overridden), else `lastLoadRows` when it moved at least one row, else unknown - and unknown omits the
   row-count segment entirely rather than rendering "0 rows". Applied to the per-table line AND to
   "Continue to the landing"'s own row sum (same silent-drop bug there, one table's worth: the sum only
   counted `sourceRowCount > 0`, so a table known solely through its load was left out).

4. **`PERSONNES_SAP_SAMPLE` said "No table yet" with no `LandingTable` row.** Measured (live, project `A`):
   zero `LandingTable` rows exist for `ProjectSource` 9 (`./projectSource='9'` on `LandingTable` - confirmed
   the filter itself is correct: the SAME filter shape correctly finds project `Personnes`' own loaded SAP
   table, id 5, `projectSource=4`). Its four `LANDING_MODEL`/`LANDING_DATASET`/`LANDING_FLOWS`/
   `LANDING_LOAD` `PipelineStep` rows (`scope = PERSONNES_SAP_SAMPLE`) all read status **`RESET`** (not
   `DONE`) with real `startedAt`/`endedAt`/`message` from an earlier successful run ("Landing model built
   and published.", "Loaded 1 landing table(s).") - `PipelineStep.status`'s own description: "a DONE step a
   later master/reset invalidated - not deleted, so history stays". **This is a live data-inconsistency, not
   a code bug in the query** (`WizardData.sourceInfo`'s `./projectSource=` filter, "wrong project-source
   link"/"filter on the wrong id" and "a second `ProjectSource` row" - there IS a second row elsewhere,
   `ProjectSource` 4 of project `Personnes` with the same catalog source, but it is a DIFFERENT row on a
   different project, not a cross-link bug) - project `A`'s SAP source was reset by an earlier session's
   step-reset test/cleanup (matches memory note "Doublon staging projet A + nettoyage d'étape") and its
   landing was never regenerated since; per the common brief, **no write was made** to fix this on the live
   instance. What WAS fixed: the message a person sees no longer claims the same thing for "never tried" and
   "was tried, then reset" - new pure class `wizard.LandingTableHint` (tested, `LandingTableHintTest`):
   `wasReset(pipelineSteps)` is true when at least one of the source's latest-per-step statuses is `RESET`;
   the Review card then shows `wizard.review.tables.wasReset` ("The landing for this application was reset:
   run its landing plan again to regenerate its tables.") instead of the generic "has not run yet, or found
   none." **What a person should do**: re-run the landing plan for `ProjectSource` 9 of project `A` (step 3,
   Plan/Generate/Load) to produce a fresh `LandingTable` row, or leave it as a genuinely stuck/abandoned test
   source if project `A` itself is scratch and disposable.

5. **Key line wording/chips.** Measured the three cases against `WizardPage.renderReviewTableRow`: (a) a
   source-declared key (`TableInfo.declaredKeyColumns` non-empty, from `CatalogReader.primaryKeyColumns`)
   already read "Key declared by the source:" + chips - correct, untouched. (b) A landing-assigned key
   (`TableInfo.keyColumns` non-empty, any origin) read "Key found at the landing:" + chips but **never an
   origin chip** - the ONLY thing actually wrong on this line. (c) No key anywhere already read "Key found
   at the landing." (no colon, no chips) - correct, untouched (round 3 of the visual pass already fixed the
   stray colon). Fix for (b) only: text changed to "Key:", and chips now go through `renderKeyChips`'s
   existing 4-arg overload (`pLocale, csv, pOrigin` - the exact one step 3's own key chooser already uses)
   passing `TableInfo.keyOrigin`, so RULE/LLM/USER/SOURCE now renders as a second chip
   (`wizard.step3.keyOrigin.*`), same as step 3. "Remove": used to disappear once a source left
   `SELECTED`/untouched (`removable = "SELECTED".equals(status) && !anyTableTouched`) - a card with a
   generated or loaded landing had no Remove at all. Now renders on EVERY card, with a confirmation
   (`data-mdmf-confirm`, read generically by `wizard.js`'s single `[data-mdmf-action]` click handler,
   `window.confirm` before the round trip) - the actual guard against removing a source whose landing was
   already generated stays server-side in `WizardData.removeSource` (an `OperationException`, shown as the
   page's own warning banner), never silently hidden client-side again.

6. **Trust order.** Measured: `WizardPage.renderTrustOrderAndGlossary` already sorts
   `Collections.sort(..., Integer.compare(pLeft.trustRank, pRight.trustRank))` - ascending, lower first -
   and live `ProjectSource.trustRank` for project `A` is 1 (`PERSONNES_SALESFORCE`) / 2
   (`PERSONNES_SAP_SAMPLE`), rendered in exactly that order in the screenshot. **Verified correct, no fix
   needed** - "lower wins" is genuinely applied end to end.

Not touched (out of scope for this branch): any CSS/layout (the visual-pass branch's own territory,
including the code input's 90px truncation from bug 2 and the row-count/key-line markup's visual polish);
`renderApplicationProgressCards` (the still-in-progress cards, a separate model - `SourcesRunCard` - already
reads its own `connectorType` correctly, unaffected by bug 1); the other `sourceSystem`-as-display-text call
sites in steps 3/4/other screens (`renderLandingFlow`, the staging feed header, etc.) - a different lot's own
territory per the common brief's "touch only your step" rule. Verified: `tools/test_local.sh` all green,
including new `TableRowCountTest` and `LandingTableHintTest` and an updated `WizardPageTest.
sourceCardsAndAddFormRender` (fixture now sets `catalogSourceId` alongside `catalogSourceLabel`, matching
what `WizardData` actually produces, plus new assertions for Remove-on-every-card and the confirmation
text). Not run against the live instance (read-only MCP tools only, per the common brief) - the display/
message changes are verified by test and by direct reading of the measured live values above, not by seeing
the page rendered against project `A`/`Personnes` again.

## Visual pass, round 3 (tiny final polish)

(a) A table with no landing key at all used to read "Key found at the landing:" with a stray
trailing colon - `wizard.review.key.atLandingNone` (no colon) is now used whenever no chips
follow, `wizard.review.key.atLanding` (colon) only when they do. (b) The two start-card icons
(folder/lightning glyphs) and the drop-zone's own arrow are now inline stroke SVGs
(`WizardPage.ICON_BOOK`/`ICON_PLUG`/`ICON_UPLOAD`), the exact line art `Main.dc.html` itself uses
for these three icons - no Unicode/emoji glyph anywhere in this trio. (c) "What each stage does"/
"Only the stuck ones need you" now gate on `snapshot.sources.isEmpty()` (pure adding state), not
on `sourcesRunCards` alone - once any application is ready, the sidebar is trust order + glossary
only, even if another application is still stuck alongside the ready ones (matches
`SourcesReview.dc.html`, which drops those two cards despite still showing a stuck row inline).
Re-rendered and re-checked (`get_page_text` confirmed the exact wording/period, no colon).

## Visual pass, round 2 (coordinator rendered the four states headless, 1400 wide)

Six more fixes, same branch: (1) the harness was missing `review.css` (declares `.mdmf-btn`/
`.mdmf-btn-primary`) - every button rendered unstyled; now inlines factory.css/review.css/
wizard.css in the exact order `ProjectWizardUserService` links them, and "Browse files" is now
`mdmf-btn-primary`. (2) Step title/lead now use the mockup's own copy ("Where does your data come
from?" + its lead) - the old lead named `ProjectSource.schemaFilter` directly. (3) The two start
cards now carry the mockup's titles/one-line descriptions/icons ("Pick a source already in the
catalog" / "Connect a live system", `wizard.step2.add.title/.lead` and `wizard.onboard.title/.lead`
rewritten); "Connect a live system"'s connection combobox now renders OPEN in the card by default
(the lazy-load-on-open gate stays - `SOURCE_ONBOARD_OPEN` still fires the one live MCP round trip,
only its CLOSED-state look changed, to an input-styled stand-in button rather than a plain "Create
a catalog source" button); "Create the sources from these files" is hidden until at least one file
is dropped (`wizard.js` toggles it next to the sample table). (4) Pipeline sub-texts are now short
fixed phrases (`wizard.step2.stageShort.<key>.<state>`), the full diagnosis message moved to a
`title=""` tooltip; the orange circle carries an alert glyph; a needs-a-person card's result line
now carries "Open the connection"/"Check again" (`SOURCES_RUN_POLL` - a plain re-render, every
card's diagnosis is already read fresh on every render, so "check again" needs no dedicated
action). (5) The per-table tick is a real `<input type="checkbox">` (label = table name) posting
`TABLE_TOGGLE` unchanged; the header status chip is now the mockup's fixed "ready" chip, not the
raw `ProjectSource.status`; a table with no key at all now reads "key found at the landing" (chips
omitted) instead of a separate "no key yet" line. (6) `WizardPage.countText` (new helper) composes
"{n} {word}" from two bundle keys per noun (`wizard.unit.<noun>.one`/`.many`) - no `{0,choice,...}`
pattern exists anywhere else in this bundle, so this follows the two-keys-picked-in-Java convention
instead of introducing the first one; used for applications/tables/columns/rows everywhere counted.

Re-verified the same way: rebuilt the throwaway harness (still
`.../scratchpad/sources-preview/Preview.java`, never committed), re-rendered all four states, and
inspected each in the Browser pane again - confirmed purple primary buttons throughout, the new
title/lead, both start cards' new copy with the connection combobox open, short stage texts with
green check-circles and the orange alert-triangle circle, "Open the connection"/"Check again" on
the blocked card, real checkboxes labeled with the table name, the "ready" chip, "Key found at the
landing." text, and correct plurals ("2 applications, 2 tables, 577 rows known from the catalog").
`WizardPageTest` had two assertions tied to markup this pass changed on purpose (the closed
onboarding panel's own button text; the now-removed Close button) - updated, not weakened.

**Not done**: the closed "Connect a live system" stand-in is a styled `<button>`, not a real
`<input>` (kept clickable/keyboard-accessible without duplicating the combobox's own JS); the
"Check again" wording is reused for a plain re-render rather than a dedicated single-card recheck
action (no such action exists - building one was out of scope for a wording/copy pass).

## Visual pass (first follow-up)

`WizardPage`'s step 2 was rebuilt to match the mockups' own layout/hierarchy - main column + right
sidebar (`mdmf-src2-layout`/`mdmf-src2-main`/`mdmf-src2-sidebar`, `wizard.css`): a big dashed drop zone as
the first way in on the start screen (`renderSampleFilesPanel(...,true)`, same ids/data-mdmf-* attributes
`wizard.js` depends on, only the outer framing changed), the two smaller "pick from catalog"/"connect
live" cards below it in a 2-column grid, one application card per queued connection with the 4-circle
pipeline joined by lines (`renderOneApplicationProgressCard`, orange ONLY for `NEEDS_PERSON`), review
cards with an editable code field and per-table rows (tick + `mdmf-key-chip`/`mdmf-key-plus` key chips),
still-stuck applications as compact one-line rows, the primary "Continue to the landing" block, and a
sidebar that switches between "Your applications" (empty state, start), "What each stage does" + "only
the stuck ones need you" (while anything is still being prepared), and "When two applications disagree"
(numbered trust order with up/down) + glossary (once at least one application is ready). Every color
reuses factory.css's own tokens (`--accent`/`--good`/`--warn`/`--muted`/`--line`); no new visual language.

**A real bug the visual check itself caught**: the first version rendered a not-yet-ready queued
application BOTH as its own full pipeline card AND, once any application was ready, as a "stuck" one-line
row underneath - "SalesforceSample - still scanning" appeared twice on screen. Fixed in
`renderStep2Main`: a not-ready application renders as a full pipeline card only while NO application is
ready yet (pure "Adding" state); once at least one is ready, every not-ready one becomes the compact
stuck row instead, never both. This was only visible by actually rendering the page - it would not have
been caught by a code read alone.

**How it was checked**: a throwaway harness,
(a local scratch file)
(never committed - outside the repo per instruction), modeled on the coordinator-supplied example, renders
the REAL `WizardPage.render(Model)` with hand-built fixtures into four static HTML files (also in that
scratchpad directory, not the repo):
- `1-start.html` - no application ready or queued.
- `2-adding.html` - one ready sample file (SAP, all 4 stages green), one still scanning (Salesforce, spinner
  on Scan), one needing a person (Snowflake CRM, orange Connection circle, "Open the connection"/"Check
  again" absent from this fixture but the orange badge and message are the point being checked).
- `3-review.html` - two ready applications (SAP, EBX/Contact) with editable codes and table rows, one
  still-stuck application (Snowflake CRM) as a compact row, the continue block, and the sidebar trust order.
- `4-review-composite-key.html` - same as 3 but the EBX/Contact table carries a source-declared two-column
  key (`companyId` + `contactNumber`), to check the `mdmf-key-chip`/`mdmf-key-plus` rendering.
Served over `python3 -m http.server` and opened in the Browser pane (screenshots taken, read critically) -
the duplicate-stuck-row bug above was found and fixed this way, then re-rendered and re-checked.

**What still differs from the mockups**, read after the visual check:
- No icons on the two start-screen "way" cards (catalog-pick/connect-live) - `renderAddSourceForm`/
  `renderSourceOnboardingPanel` were reused unchanged (their own combobox/toggle logic, not rewritten) and
  only wrapped in a 2-column grid; the mockup's folder/plug icons were not added.
- The drop zone's icon is a plain Unicode arrow glyph, not the mockup's line-art SVG.
- No drag-handle icon on the sidebar's trust-order rows (the mockup has one; this build only has the
  numbered circle, source dot, name, and up/down buttons).
- Review cards dropped the catalog-source id/schemas/connector detail lines the pre-visual-pass card used
  to show (not in the mockup either, but a real loss of information versus the OLD build) - not restored,
  to keep the card close to the mockup's own density.
- Still not a true CSS Grid/absolute layout matching the mockup's exact spacing/type scale - flexbox
  approximation, "not pixel-perfect, same structure" per the coordinator's own instruction.

## What was built

### 1. `wizard.ApplicationStages` (pure, tested)

`src/main/java/com/on/ebx/factory/wizard/ApplicationStages.java` +
`src/test/java/com/on/ebx/factory/wizard/ApplicationStagesTest.java`.

Maps `SourceOnboarding.Diagnosis` (the existing six-step connection/catalogOperation/catalogSource/
scanFlows/scan/ready diagnosis, source-onboarding lot 2026-09-23) to the mockup's four-stage pipeline:
Connection = `connection`; Catalog = `catalogOperation` + `catalogSource` + `scanFlows`; Scan = `scan`;
Ready = `ready`. Each grouped stage's state/text come from the FIRST sub-step (in order) that is not
`DONE` - a real bug caught by the test suite during development (a trailing `WAITING` sub-step, which
`SourceOnboarding` produces *by construction* whenever an earlier one in the group is not `DONE`, was
outranking an earlier `TODO` that was genuinely about to run automatically) is documented on
`ApplicationStages.group`'s own Javadoc. State mapping: `BLOCKED` -> `NEEDS_PERSON` (orange, "a person
must act"); `TODO` -> `RUNNING` (automatic); `WAITING` -> `WAITING`; all `DONE` -> `DONE` (text from the
LAST sub-step). Tested on 12 diagnosis shapes.

### 2. `wizard.SourcesProgress` (pure, JSON round-trip tested)

`src/main/java/com/on/ebx/factory/wizard/SourcesProgress.java` +
`src/test/java/com/on/ebx/factory/wizard/SourcesProgressTest.java`.

The JSON progress ledger of one `ebx.SourcesRun` background pass: one `Entry`
(`WAITING`/`RUNNING`/`DONE`/`FAILED`, timestamps, one-line summary, plus - added in the follow-up - an
optional `codeHint`/`trustRankHint` carried from the sample-files table's own typed fields) per connector
id queued. Same shape/round-trip convention as `staging.ProposalProgress`. `entries()` stays in
seed/queue order regardless of processing order (an earlier version reordered on `start()`, breaking a
round-trip test - fixed). `enqueue()` (idempotent append, follow-up) and `remove()` (drop an entry, used
by the Review screen's "Remove" on a still-stuck application, follow-up) round out the pure API; both are
tested, including that a `remove()`-then-`start()`/`finish()` sequence is a silent no-op rather than
resurrecting the entry.

### 3. `ebx.SourcesRun` (live, background) - now wired end to end

`src/main/java/com/on/ebx/factory/ebx/SourcesRun.java`.

Follows `ebx.StagingProposalRun`'s own pattern (one small daemon `ExecutorService`, one parent
`PipelineStep` row per project, `step = SOURCES_RUN`, progress persisted as `SourcesProgress` JSON,
`isStale` after 30 minutes). The one real structural difference from `StagingProposalRun`: a sources
run's queue GROWS while it runs, one connector id at a time - `start()`/`startById()` either launch a
fresh background loop or, if one is already `RUNNING`, append the new connector id to the persisted
queue (`SourcesProgress.enqueue`) and return `queued=true`; the loop re-reads the persisted progress
before picking its next entry, so an appended id is picked up without a second thread.

**Wired in this follow-up** (previously built but unused):

- `ProjectWizardUserService.onboardAll` (`SOURCE_ONBOARD_ALL`) now calls `SourcesRun.startById` and
  returns at once, instead of running `SourceOnboarder.onboardAll` synchronously on the request thread.
- `SampleSourceImporter.importFiles` (`SOURCE_FILES_IMPORT`) still writes the Connectors connection
  synchronously (the file itself has to land somewhere before anything else can happen), but the
  catalog+scan chain that used to run right after is now queued into `SourcesRun.startById` too, carrying
  the sample-files table's own code/trust-rank as hints. `SampleFileRules.ReportRow` gained a `queued`
  flag/factory method for the new "queued, see below" report row.
- New no-op `SOURCES_RUN_POLL` action (identical shape to `STAGING_PROPOSAL_POLL`), and a matching
  `data-mdmf-sources-running` attribute + 5-second poll in `wizard.js`.
- **Auto-pick-on-ready moved onto the background thread.** `SourcesRun.addApplicationIfReady` (new)
  reuses `SampleSourceImporter.applicationUsing` (made package-visible instead of duplicated) to check no
  other application of the project already uses the catalog source, then builds a `WizardRules.SourceDraft`
  (schemas from `WizardData.schemasOf`; `sourceSystem` from the hint when the sample-files table's own
  code field was filled in, else auto-suggested from the schema name via `WizardRules.suggestSourceSystemCode`
  and de-duplicated against the project's other codes with a numeric suffix - `SourcesRun.uniqueCode`) and
  calls `WizardData.addSource` directly - there is no request/form left to pre-fill once the work moved to
  a background thread, so this replaces what `onboardUse()`'s "pre-fill the form, let a person submit it"
  used to do for this flow. `WizardRules.CODE_MAX`/`CODE_PATTERN` were made `public` (from `private`) for
  this cross-package reuse - the only production behavior change to that class.
- **How this was verified to still work**: not against a live EBX instance (no MCP write tools available
  to this session - see the common brief's own tool restriction). Verified by construction: the new method
  is a direct move of `SampleSourceImporter`'s own previously-synchronous `addApplicationIfReady` logic
  (same dedup call, same `WizardRules.sourceProblems`/`WizardData.addSource` calls) onto the background
  thread, plus the same idempotent `LandingPlanner.plan` call the catalog-pick path also got (below). The
  only genuinely new logic is `uniqueCode`, which is pure enough to have been unit-tested but was not
  (an oversight - see "not completed" below).
- One `WizardPage.SourcesRunCard` per queued connector id (not only the interactively-picked one),
  `model.sourcesRunCards`, each with a FRESH live `SourceOnboarder.facts`/`ApplicationStages` diagnosis
  read every render (the queue holds a handful of applications, never hundreds, so this stays cheap) and
  the run's own coarse state as a badge.

### 4. The Review screen (task brief item 3 / follow-up item 2)

Per-application, on the same card the applications list already rendered:

- Kind chip (`Source.platform` - `sample-file` shown as "Sample file", anything else as "Live
  connection"; `CatalogReader.SourceStatus` gained `platform`/`label` fields, and `WizardData.sourceInfo`
  now also fixes a pre-existing gap where `catalogSourceLabel` always fell back to the raw id).
- Editable application code (`WizardData.setSourceCode`, new): validates blank/pattern/`CODE_MAX`/
  uniqueness among the project's OTHER sources (never refuses a no-op edit to the same code) before
  writing `ProjectSource.sourceSystem` - the exact field/pattern `tools/build_xsd.py` declares
  (`[A-Za-z][A-Za-z0-9_]*`, `WizardRules.CODE_PATTERN`/`CODE_MAX`, now public).
- Per-table tick = `LandingTable.selected`, reusing the EXISTING `TABLE_TOGGLE` action verbatim (no new
  write path). **Tables now exist to tick**: `LandingPlanner.plan` (confirmed idempotent by reading its
  own class Javadoc - "run again, it only adds what is new... keeps its name and its rows, whatever the
  user changed on them" - before wiring it in, never verified live) now runs automatically right after
  every successful `WizardData.addSource`, both in `SourcesRun.addApplicationIfReady` and in
  `ProjectWizardUserService.addSource` (the synchronous catalog-pick path, the one way in that was never
  routed through `SourcesRun`).
- Under each table: `CatalogReader.primaryKeyColumns(catalog, assetId)` (new) reads the source's OWN
  declared primary key straight off `LandingTable.catalogAsset` (the same `Asset.id` `CatalogReader.assets`
  itself returns; since audit D-07, 2026-10-06, the asset KEY `source:qualifiedName`, a string, resolved by
  `CatalogReader.resolveAssetKey` - see `docs/UPGRADE.md` section 2.1) - "key declared by the source", rendered as chips (`mdmf-key-chip`/`mdmf-key-plus`,
  column order) via a new shared `WizardPage.renderKeyChips`. Falls back to "key found at the landing"
  (the landing's OWN key decision, `TableInfo.keyColumns`) when the source declared none, or "no key yet".
  This reads `LandingColumn`/the catalog only for DISPLAY - it never writes to `LandingColumn` or touches
  the key rules, which stay lot KEY's own territory.
- Still-stuck applications (a queued `SourcesRun` entry whose `ApplicationStages.Pipeline` is not
  `ready`): one line each, "not part of the landing until it is ready", with See (a plain in-page anchor
  link to that connector's progress card, `#mdmf-app-progress-<connectorId>`) and Remove
  (`SOURCES_RUN_REMOVE` -> `SourcesRun.cancel` -> `SourcesProgress.remove`).
- "When two applications disagree": trust order as a numbered list (current `trustRank` ascending) with
  up/down buttons (`SOURCE_TRUST_UP`/`SOURCE_TRUST_DOWN` -> `WizardData.reorderSourceTrust`, new). **Lower
  wins** was verified by reading `WizardSnapshot.SourceInfo#trustRank`'s own pre-existing Javadoc ("lower
  wins, feeds the most-trusted-source order of the eventual merge policy") before writing anything, not
  assumed. Every move REWRITES every source's `trustRank` as 1..n in the new order (never a raw
  two-value swap - most sources share the default 100, so a swap would leave ties), exactly as the
  coordinator's message specified.
- A glossary line, and "Continue to the landing" (`data-mdmf-goto-step="3"`, the existing client-side step
  rail - no new navigation mechanism) with the counts actually known: applications and tables always;
  rows from the catalog (`TableInfo.sourceRowCount`, summed) ONLY when at least one table carries a
  non-zero value, omitted entirely otherwise (never shown as a misleading "0").

### 5. The start screen (follow-up item 3)

`renderStep2` now branches on `startState = snapshot.sources.isEmpty() && sourcesRunCards.isEmpty()`:
in that state every add control (catalog-pick form, sample-files panel, guided-connection panel) renders
DIRECTLY, open, with a short heading/lead and the same glossary line the Review screen uses; once at
least one application exists (ready or in progress), the exact same controls collapse behind the
pre-existing `mdmf-disclosure` mechanism, now labeled "Add more applications" instead of "Advanced" - the
brief's own "slim add-more row". This reused the disclosure the earlier pass had already wrapped
everything in, rather than building a second, separate rendering path for the controls.

## What was NOT completed - read before assuming this lot is finished

1. **No visual rebuild of the Main/SourcesAdding/SourcesReview mockups.** The start screen, the
   per-application cards and the Review screen all render with the PRE-EXISTING component vocabulary of
   this wizard (`mdmf-panel`, `mdmf-entity`, `mdmf-chip`, `mdmf-disclosure`...), not the mockups' own
   restyled drop-zone/two-card/sidebar layout. Functionally each requirement is met; visually it looks
   like the rest of this wizard, not like the mockup.
2. **No true two-column ("glossary/trust order on the right") layout.** These render as stacked sections
   below the applications list - no sidebar CSS convention existed anywhere in this file to reuse, and
   building one was out of scope for this follow-up.
3. ~~`SourcesRun.uniqueCode` has no dedicated unit test~~ - caught while writing this document, fixed the
   same session: `uniqueCode` is now package-visible and `SourcesRunUniqueCodeTest` (7 cases: free code,
   first collision, several collisions, blank-base fallback, truncation at `CODE_MAX`, truncation-with-
   suffix, "never returns an existing code" over 50 pre-taken suffixes) exercises it directly, no EBX
   needed despite living in the `ebx.*` package.
4. **Nothing in this lot was run against a live EBX instance.** No MCP write tool was available to this
   session (the common brief's own restriction: read-only `data-access` only). Every live-side claim above
   (auto-add-on-ready still working, `LandingPlanner.plan` being genuinely idempotent, the background
   `Session` staying usable past the request that started it) is verified by reading the relevant source
   and/or by direct code reuse from an already-synchronous path, never by seeing it run.
5. **No new `WizardPageTest` fixture** covers the new Review/start-screen HTML specifically - only that
   the pre-existing `WizardPageTest` continues to pass unmodified (it does not target this new markup).
6. Composite-key `SOURCE` origin rendering on `LandingColumn`/`wizard.step3.keyOrigin.SOURCE` (a DIFFERENT,
   lower-level signal than this lot's own `TableInfo.declaredKeyColumns` - that one lives on `LandingColumn`
   and is lot KEY's own territory) was not touched, per the common brief's own exclusion.

## What WAS verified, and how

- `tools/test_local.sh` (`ON_EBX_AGENT_JAR`/`ON_EBX_MAPPING_JAR` set, `EBX_LIB_DIR` not set) ends `all
  green` after each of the three follow-up commits, including every new/extended pure test
  (`ApplicationStagesTest`, `SourcesProgressTest` - now also covering `enqueue`/`remove`/hint round-trip).
- Every EBX-bound method (`WizardData.setSourceCode`/`reorderSourceTrust`, `CatalogReader.primaryKeyColumns`,
  `SourcesRun.addApplicationIfReady`/`cancel`) compiles against `ebx-lib.jar` and follows an existing,
  already-shipped sibling method's own pattern (`setSourceTrustRank`, `removeSource`,
  `CatalogReader.assets`/`columns`) rather than inventing new EBX-access idioms.
- `trustRank`'s "lower wins" meaning was read from `WizardSnapshot.SourceInfo#trustRank`'s own Javadoc
  before any code was written that depends on it, per the coordinator's explicit instruction to verify
  first.

## Every guess made, listed explicitly

- That "applications being prepared" (task brief item 2) covers all three entry paths once queued through
  `SourcesRun` (guided connection AND, since this follow-up, sample files) - the catalog-pick path stays
  synchronous (a single round trip with a person watching it) and was deliberately not queued.
- That a still-stuck application's "Remove" should drop it from the run's ledger outright (never
  tombstone it) - a removed connector id can be queued again later exactly like a new one.
- That the Review screen's glossary can reuse the exact same text as the start screen's - the task brief
  asks for "a glossary block per screen" without specifying whether the words differ per screen; here they
  are identical since the vocabulary (application/table/key/landing) does not change between them.
- The CSS/message key `wizard.step2.addMore` and the now-dead `wizard.step2.advanced` (superseded by the
  `startState` branch, left in the properties files rather than deleted, to minimize risk of colliding
  with a key another parallel lot might reference).
