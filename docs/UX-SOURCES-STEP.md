# UX lot U2 - the Sources step (wizard step 2)

2026-10-13, branch `feature/ux-sources-step` (from `main` a4a74dc0). Part of the wizard UX review of the same day
(`ux-factory-wizard-2026-10-13/RAPPORT.md` sections 2.3-2.5, 3.1, 3.2, 3.7; screenshots `04b`, `05`, `06`, `08b`, `09b`, `12`, `13`,
`14`, `16b`, `18b`). Status: coded, tests green, NOT deployed, NOT seen running.

## What was measured (the starting point)

Read live on 2026-10-13 and written in the lot brief: the landing plan of a catalog source whose only asset came from OpenLineage
(no scanned column) answers "0 table(s) planned" in green because `LandingPlanner` skips the asset with `SKIPPED_NO_SCANNED_COLUMN`;
the "Connect a live system" diagnosis says "Ready" because `lastScanStatus` is OK; "Use this source" shows the add form again for a
system that already is an application of the project; an application whose catalog scan is the regular one has no "Rescan"; the 17
Salesforce tables arrive ticked and every untick is a postback; the application code is empty when the source has no schema; the trust
rank is a free number next to the arrows; the texts say "official scan", "deduced scan", "landing plan".

## What was built

### 1. A plan that writes nothing says why
- `wizard/PlanOutcomes` (pure): turns the planner's outcomes into the job's result text. When nothing was planned the text starts with
  `Nothing to do:` (convention shared with the other UX lots), then the reasons in user words and the gesture that unblocks, e.g.
  `Nothing to do: Account: no column scanned yet. Scan the application again.` Four names at most, then "and N more"; an unmatched schema
  filter, tables that changed in the source, "already planned" and "the catalog holds nothing" each have their sentence.
- `LandingSourceRun.PLAN_HANDLER` ends with `LandingSourceRun.planMessage(...)` (same job kind `LANDING_PLAN_RUN`; the step-3 Advanced
  "Plan" button gets the same honest message).
- The application card with no table lists the reasons itself: `WizardData.attachUnplannedAssets` reads, for an application with no
  planned table, the objects the catalog holds for its source and whether each has a scanned column (`CatalogReader.assetBriefs`, one
  existence query per object, read as the person). The card shows one of three states (`SourceTableList.noTableState`):
  tables with columns exist -> "Find its tables" (primary); objects exist but no column was read -> the objects named with "No column
  scanned yet." and "Scan again" (primary); nothing at all -> "The catalog holds no table for this application yet" and "Scan again".
  NOTE: the reasons are recomputed from the catalog at each display, they are NOT read back from the plan's JSON report (the report is
  not stored anywhere; the job message carries the same words).
- "Scan again" on EVERY card with a connection (`SOURCE_RESCAN`): new job `SourceRescanRun` (kind `SOURCES_RESCAN_RUN`, resource
  SOURCES, right DESIGN, filed under step 2 by `RecentTasks.stepOf`): `SourceOnboarder.rescan` (creates the catalog source and scan
  mapping when missing, then the same `connectors_sync` as `runScan`, "Catalog scan: <SOURCE>") then `LandingPlanner.plan` (new tables
  unticked) in the SAME job, ending with the plan's message. A scan Connectors refused or a source left in ERROR ends the job as an
  expected failure that says so. An application whose tables were found from the data (no catalog scan exists for its connection) keeps
  its own button (`DEDUCED_SCAN_START`), now worded "Find its tables" / "Scan again".

### 2. Tables: unticked, ordered, kept in one action
- `LandingPlanner.plan(session, factory, id, selectNew)`: `false` writes the tables it ADDS with `selected = false`; a table planned
  earlier is never touched (a ticked or loaded table stays so). Used by: step 2's "Find its tables" job (new action
  `SOURCE_FIND_TABLES`, param `selectNew=false` so it never folds into a queued step-3 plan), "Scan again", the catalog-pick add path
  (`addSource`), and `SourcesRun.addApplicationIfReady` for every connection except a sample-file one (a sample file IS the tables the
  person chose, they stay ticked). REST, accelerators and the 3-argument `plan` keep ticked.
- `SourceTableList.order`: row count descending (the catalog's profile, else the last load; unknown last; a real 0 is known), then name.
- The card: "Select the tables to keep." + counter ("0 of 17 selected", kept live by `wizard.js`) + "Select all / Select none" (client
  side), the rows (checkbox without any action, the key chips kept), and one button "Keep these tables" (`TABLES_KEEP`,
  `data-mdmf-ticks-scope=<card id>`). `wizard.js` collects the boxes changed since the page was drawn (`checkbox.checked !=
  defaultChecked`) as `+id,-id` into the hidden `mdmfTableTicks` and posts ONE action. "Continue to the landing" carries every card's
  ticks (`data-mdmf-ticks-scope="all"`) and skips the round trip when nothing changed; the footer "Next" button of the wizard also saves
  pending ticks when leaving step 2. NOT done: the rail's step-3 button does not save pending ticks.
- `WizardData.setTablesSelected`: all ticks in one `Procedure`; a table of another project, a missing one or one whose landing was
  generated (status not PROPOSED: its scope is fixed, its box is disabled) is left alone and counted ("N table(s) could not be changed").
- The purple banner moved above the cards and shows only when tables exist and none is ticked (`SourceTableList.tablesButNoneTicked`);
  with no table the card explains itself. Its text (`wizard.blocked.needTickedTable`, also the rail tooltip) no longer says "tick".

### 3. Honest diagnosis
- `SourceOnboarding.Facts.assetsWithColumns` (new, pure): the scan step is DONE only when the catalog holds a scanned asset WITH
  columns; objects without column give step `scan` TODO, message `wizard.onboard.step.scan.noColumns` ("... read no column: scan
  again"), next action `SCAN_RUN`. `SourceOnboarder.sourceInto` fills it from `CatalogReader.hasAssetWithColumns`.
- A diagnosed-ready connection whose catalog source already is an application of the project: the panel says so
  ("This system is already the application X of this project") and offers "Find its tables" and "Scan again" instead of "Use this
  source" (`WizardData.applicationOfCatalogSource`, `Model.onboardExistingSourceId/Code`); `SOURCE_ONBOARD_USE` refuses a stale click
  with a notice and never pre-fills the add form for it. Limitation kept on purpose: one catalog source hosting several applications
  (ERP_SOURCE / CRM_SOURCE schemas of one Snowflake account) is still added through the catalog pick form ("More ways").

### 4. Vocabulary (EN + FR)
No "official scan", "deduced scan" or "landing plan" left in the step-2 texts: "Run its landing plan" is "Find its tables"; the card's
explanations are one short line; the deduced-scan chip reads "found from the data"; engine messages shown on step 2
(`engine.unexpected.landingPlan`, `engine.conflict.assetGone`, `engine.source.landingPlanned`, `engine.missing.scanStateField`) were
reworded. "Confirmed by a person" became "confirmed by <user> on <date>" (`Asset.scanConfirmedBy/At`, new `TableInfo.scanConfirmedBy/At`),
"confirmed earlier" when neither is stored. LIMIT: the catalog stores the user and the date but NOT the project, so "confirmed in project
<code>" is not possible without a catalog change; the catalog state stays shared between projects (what let DemoCRM arrive confirmed).
Left as they were (not step-2 cards of this lot): the step-3 text `wizard.step3.keyOrigin.DEDUCED`, the label "Planning the landing" of
the shared job kind `LANDING_PLAN_RUN` in the task list.

### 5. The add form
`ApplicationCodes.propose` (pure): the code comes from a specific schema name when there is one (ERP_SOURCE gives ERP), else from the
catalog source's label, the connection's label or id (a URL-like label such as `salesforce://login.salesforce.com` only as a last
resort, then its scheme), slugged to `[A-Z][A-Z0-9_]*` within `CODE_MAX`, made unique against the project's codes. The numeric trust
rank input is gone: a new application is appended last (`WizardRules.nextTrustRank`, also enforced in `addSource`); the arrows panel is
the only way to reorder. A source with no schema no longer shows "No schema scanned yet"; the technical connection id sits in an
"Advanced" fold; "Source system code" is labelled "Application code".

### 6. One "Add an application" list
`AddApplicationOptions` (pure) merges the Connectors connections and the catalog's sources into rows with a chip: "scanned, N tables"
(N counted by `SourceOnboarder.connections(..., true)`), "connection only: will be scanned", "already in this project" (greyed),
"switched off in Connectors", "no catalog scan: see More ways below", "no connection to read it" (a source declared by lineage events),
"its connection was removed" - the last four greyed with the reason, sorted last, never pickable (`wizard.js` guards click and Enter).
Picking a row posts `SOURCE_ADD_APP`, which queues the same job as "Do everything" (`SourcesRun`: catalog source and scan when missing,
then the application and its plan, new tables unticked); the existing progress cards follow it. Like the step-by-step panel, the list
loads its connections only when opened (`SOURCE_APP_OPEN`, flag `Model.addAppOpen`): an input-styled button stands in until then.
The sample-files panel, the catalog pick form and the step-by-step "Connect a live system" panel are folded under "More ways to add an
application" (open while one of them is mid-flow). NOT done: no connection-less catalog source can be added from the list (they are
greyed); a connection with no catalog scan can only be added from "More ways" (its deduced path); the list does not search the sample
files; the three old panels were kept as they are, not merged into the list's own code.

## Files

Pure + tests: `wizard/PlanOutcomes`, `SourceTableList`, `ApplicationCodes`, `AddApplicationOptions`, `UnplannedAsset` and their
`*Test`; `SourceOnboarding` (+ `SourceOnboardingTest`). Live: `ebx/SourceRescanRun` (new), `LandingSourceRun`, `LandingPlanner`
(overload), `SourceOnboarder` (`rescan`, `connections(..., withTableCounts)`, `assetsWithColumns`), `CatalogReader` (`assetBriefs`,
`hasAssetWithColumns`, `ScanInfo.confirmedBy/At`), `WizardData` (`setTablesSelected`, `applicationOfCatalogSource`,
`attachUnplannedAssets`, `connectorLabelOf`), `SourcesRun` (the `selectNew` argument, 3 lines), `FactoryJobKinds` (one line),
`FactoryRights` (5 actions), `CatalogReadSites` (+1 site), `ProjectWizardUserService` (actions `TABLES_KEEP`, `SOURCE_FIND_TABLES`,
`SOURCE_RESCAN`, `SOURCE_APP_OPEN`, `SOURCE_ADD_APP`; `pickCatalogSource`, `addSource`, `onboardUse`), `WizardPage` (step-2 methods, one hidden
field), `wizard.js`, `wizard.css`, both message bundles (keys `wizard.src2.*`, `wizard.review.key.deduced.*`, `engine.missing.noCatalogScan`
/ `noConnectorToScan`, reworded `wizard.deduced.*`).

## Verified by test (offline, static HTML + pure rules)

`PlanOutcomesTest`, `SourceTableListTest`, `ApplicationCodesTest`, `AddApplicationOptionsTest`, `SourceRescanRunTest` (job message from a
planner answer, job kind registration and label), `SourceOnboardingTest` (objects without a column are not ready), `WizardPageTest`
(`sourceTablesAreKeptInOneAction`, `singleAddApplicationListRenders`, `addFormProposesACodeAndNoLongerAsksATrustRank`,
`liveSystemAlreadyAnApplicationOffersItsTablesNotTheAddForm`, `deducedScanButtonFollowsTheRunsOwnRule` rewritten), the existing page
tests adapted, `CatalogReadSitesTest` (one more decided site), `FactoryRightsTest` (the five actions). `wizard.js` parses (the harness's
JavaScriptCore step). NOT tested offline: the scripts' behaviour in a browser (tick collection, select all/none, the greyed rows), the
jobs against a real Connectors/catalog.

## What the coordinator must check live (compare with the screenshots)

1. `04b` (first contact): one "Add an application" field above, the three old ways under "More ways to add an application" (closed).
   Click the field: the connections load; Salesforce reads "scanned, N tables" or "connection only: will be scanned"; the OpenLineage
   "Salesforce CRM (EU production)" is greyed "no connection to read it" and cannot be picked (mouse and Enter).
2. `05`-`08b`: pick Salesforce: a background job (banner) scans if needed, adds the application with a code proposed (SALESFORCE),
   and its card lists the tables unticked, most rows first (Account 52, Contact 51, Lead 41, User 6...), with "0 of 17 selected".
3. `09b`: on an application whose catalog object has no column (state of fzfez), the card names the object ("No column scanned yet.")
   with "Scan again"; the job "Scanning the application again" runs the catalog scan then plans; with nothing found the job message
   starts with "Nothing to do:" (the task list is another lot's - check the text only).
4. `12`/`13`: the live-system diagnosis does NOT say "Ready" for a source whose objects have no column ("... read no column: scan
   again"); on a system already in the project the panel offers "Find its tables", not "Use this source".
5. `16b`/`18b`: tick/untick without any reload; "Keep these tables" saves; "Continue to the landing" saves pending ticks of every card
   and lands on step 3; the banner shows only with tables and none ticked; DemoCRM's keys read "confirmed by <user> on <date>" or
   "confirmed earlier". A table whose landing was generated has a disabled box.
6. Add an application through "More ways" (catalog pick): the code is proposed, no trust rank, no "No schema scanned yet" for a
   schema-less source.
7. Side effects to look at: `SourcesRun` ("Do everything", the list) now leaves new tables of a non-sample source unticked.
