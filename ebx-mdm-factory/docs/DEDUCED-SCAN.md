# Deduced scan (boards 2d/2e)

Written 2026-09-24 on branch `feature/deduced-scan` (worktree `ebx-mdm-factory-deduced-scan`), from
`scratchpad/read-recipe-contract.md` (shared, fixed contract across three agents: ebx-data-catalog,
ebx-connectors, ebx-mdm-factory) and `scratchpad/sl-common-brief.md`. Covers section 3 of the contract only
(MDM Factory): the deduced-scan engine and wizard step 2 boards 2d ("a system without an official scan") and
2e ("confirm a deduced table"). Sections 1 (new `Asset`/`Column` catalog fields) and 2 (`readOnly` on
`connectors_describe`) belong to the other two agents, in their own repositories.

## 1. What, and why

Some applications a connection can reach say what they hold (a catalog operation): the connector's own
official scan reads their tables. Others do not (HubSpot, most plain REST APIs) - the factory has to work it
out: ask a language model which of the connection's READ-ONLY operations lists records (never a lookup, one
record at a time - that is an enrichment), read a small sample without writing anything, measure the columns
in Java, and ask the model to name the table(s) and propose a key. The result is written into the data
catalog exactly like an official scan's own `Asset`/`Column` rows, marked `scanOrigin=DEDUCED`,
`scanState=TO_CONFIRM` - a deduced table is never loaded into the landing before a person confirms it.

## 2. Pure logic (`com.on.ebx.factory.deducedscan`, EBX-free)

- `Flatten` - dotted-path flattening of one sample item (`properties.email`), ported from
  `ebx.LandingPreview#flatten`; arrays are joined into one text value rather than exploded per index (an
  index is not a stable column across different rows).
- `ColumnMeasurer` - per-column type (`text`/`number`/`date and time`/`yes / no`, inferred from VALUES only,
  never the column name - product rule), filled/sample-size/distinct counts, one example.
- `OperationCatalog` - `readOnlyOperations` (the ONE place `readOnly=true` is enforced - contract: "Java
  refuses anything not readOnly=true even if the LLM names it"; a missing/absent flag defaults to `false`,
  never trusted), `hasNoReadOnlyOperation`, and `firstViolation` (refuses an operation/input the model named
  that isn't actually among the read-only ones).
- `TableProposal` - the "Name and key" answer (table name, key columns, confidence 0-100, rationale),
  validated against the columns actually measured (`firstViolation`) - never a test on the NAME's business
  meaning, only structural consistency.
- `ReadRecipe` - the exact contract JSON shape (`connector`/`operation`/`inputs`/`itemsPath`), built once so
  the catalog writer and any future landing generator agree byte for byte.
- `ListOperationPass` / `NameKeyPass` - system/user prompts plus a `Session` owning one local tool each
  (`choose_list_operation`, `propose_table`), same shape as `staging.StagingLlmPass` (prompts + `Session` +
  `toolGroup()`).

## 3. EBX-bound classes (`com.on.ebx.factory.ebx`)

- `DeducedScanOperations` - `connectors_describe(connector)` turned into `OperationCatalog.OperationInfo`,
  plus `hasCatalogOperation` (the SAME "operation literally named `catalog`" detection
  `ebx.SourceOnboarder#catalogCapable` already uses - no per-connector-type code, contract's own rule).
- `DeducedScanSample` - `connectors_call` with the chosen operation/inputs, capped at 25 items, items
  flattened; discovers `itemsPath` heuristically (`records`/`rows`/`results`/`items`/`data`/`documents`, or
  the answer's own array) - not exhaustive of every shape the contract's own examples name (a nested
  `messages[*].value` Kafka-style answer is not descended into automatically), flagged as a guess below.
- `DeducedScanLlmSuggester` - runs `AgentRuntime.run` once for "How to list" (whole connection) and once per
  candidate table for "Name and key" - same "local tool group only" posture as `StagingLlmSuggester`.
- `DeducedScanCatalog` - `pendingAssets` (every `TO_CONFIRM` `Asset` of one catalog `Source`, with columns)
  for board 2e, and `ensureSource` (finds or creates a minimal catalog `Source` row keyed on the connector id
  - a connection with no catalog operation never goes through the official onboarding path that would
  otherwise create `ProjectSource.catalogSource`, so this fills that gap the first time a deduced scan runs).
- `DeducedScanCatalogWriter` - writes one `Asset` + its `Column` rows, `scanOrigin=DEDUCED`,
  `scanState=TO_CONFIRM`, `readRecipe` - every `scan*`/`readRecipe`/`sampleFilled`/`sampleExample` write is
  GUARDED (`AdaptationTable#getTableOccurrenceRootNode().getNode(...) != null`) since those fields do not
  exist yet on the live instance (contract section 1, owned by the ebx-data-catalog agent); `Column.
  lastDistinctCount` is reused verbatim (already exists, no new field needed for "distinct").
- `DeducedScanConfirm` - Confirm/Leave out (`scanState` + `scanConfirmedBy`/`At`) and rename (`qualifiedName`
  only). "Choose another key" is deliberately NOT a third write action: board 2e's own link goes to step 3's
  existing key chooser (the landing plan reads `Column.primaryKey` as a declared key, deduced or official, the
  same machinery the composite-key/landing-key-study lots already built) - no new key editor here.
- `DeducedScanRun` - the background pass, one `ProjectSource` at a time, reusing the EXACT pattern
  `ebx.SourcesRun` established (small daemon executor, one `PipelineStep` per project, `STALE_AFTER_MINUTES`,
  a queue that can grow while running) - see that class's own Javadoc for the shared design and
  `DeducedScanRun`'s for the one structural difference (four sub-stages per entry, via
  `wizard.DeducedScanProgress#stage`, rather than a single start/finish).

## 4. Progress model (`com.on.ebx.factory.wizard.DeducedScanProgress`)

Combines both existing patterns deliberately, since board 2d genuinely needs both: `SourcesProgress`'s
growing multi-entry queue (one entry per connection) and `staging.ProposalProgress`'s fixed stage list
(`LIST`/`SAMPLE`/`MEASURE`/`NAME`) INSIDE each entry. An entry whose `LIST` stage finds no listing operation
ends the whole entry `SKIPPED` right there (contract: "an enrichment, not a source, nothing written") - the
remaining three stages simply stay `WAITING` forever for that entry, which `isOver()` treats correctly (only
the entry's own top-level state matters). JSON round-trips losslessly (`DeducedScanProgressTest`).

## 5. Wizard step 2 (`wizard.WizardPage`, `ebx.ui.ProjectWizardUserService`, `wizard.js`)

- Board 2d: `WizardPage.renderDeducedScanCards`/`renderOneDeducedScanCard` - the SAME visual pipeline
  component `renderOneApplicationProgressCard` already renders for the official scan (`mdmf-src2-pipe*` CSS
  classes reused verbatim, no new CSS needed), five nodes instead of four (a static "Connection" node, always
  done, then the four real stages).
- Board 2e: `WizardPage.renderDeducedScanConfirm`/`renderOneDeducedScanConfirmCard` - one card per
  `TO_CONFIRM` `Asset`: an editable table-name input, key chip(s) with the model's confidence, the measured
  columns table (type/filled/distinct/example), the "How it is read" line (`readRecipe.operation`/
  `itemsPath`, the model's rationale quoted), and Confirm / Choose another key / Leave it out.
- "Continue to the landing" (`WizardPage.renderContinueToLanding`) shows a warning with the count
  (`wizard.deduced.pendingAtLanding`) whenever ANY `TO_CONFIRM` deduced table exists for the project
  (`WizardRules.hasUnconfirmedDeducedTable`, pure, tested in `WizardRulesTest`). It first DISABLED the
  button; fix/continue-landing-warning (2026-09-26) made it a warning only, because Next, the rail and the
  confirm card's own "Choose another key" reached step 3 anyway, and the rule is enforced per table on the
  server (`ReadRecipe.resolve` fails a `TO_CONFIRM` table's flow; the other tables load normally).
- New actions: `DEDUCED_SCAN_START` (queues a `ProjectSource` - offered as a small button on any application
  card with no table yet), `DEDUCED_SCAN_POLL` (the pipeline cards' self-poll, `data-mdmf-deduced-scan-
  running`, same 5s-resubmit pattern as `SOURCES_RUN_POLL`/`STAGING_PROPOSAL_POLL`), `DEDUCED_SCAN_CONFIRM`/
  `DEDUCED_SCAN_LEAVE_OUT` (act on one `Asset` via the generic `mdmfId`/new `mdmfAssetName` postback fields -
  `data-mdmf-id`/`data-mdmf-name-input`, same "button names an input's id, read its CURRENT value right
  before submit" convention as `data-mdmf-code-input`).
- Messages: one contiguous `wizard.deduced.*` block in both `Messages_en.properties`/`Messages_fr.properties`,
  right after the existing `wizard.step2.stageShort.ready.needs-person` block.

## 6. Guess: a deduced-scan candidate must already have a `ProjectSource` row

`SourceOnboarder#facts` stops early ("no catalog operation") before ever creating a `ProjectSource` -
`ebx.SourcesRun`'s own auto-add-on-ready path therefore NEVER fires for a connection with no catalog
operation. Rather than duplicate that machinery, this lot assumes the application was already added through
the normal step-2 flow (any connector, even one that will turn out to have no official scan) and offers
`DEDUCED_SCAN_START` as a follow-up action on that existing (tableless) `ProjectSource` card. This is a
product simplification, not confirmed against a live "add a HubSpot connection" walkthrough - flagged here
rather than silently assumed correct.

**Fix 2026-09-27 (project A, `Personnes_SAP_Sample`, PipelineStep 528):** the button used to be offered on
every tableless card. On a sample-file connection (which has a `catalog` operation) the click ended SKIPPED
at LIST, "This connection has an official scan", and the card showed every stage skipped. The card now reads
`SourceInfo.deducedScan`, set by `WizardData#attachDeducedScanEligibility` for tableless applications only
(wizard page model, not `readSnapshot`) from `connectors_describe` through `wizard.DeducedScanEligibility#of`
- the same rule `DeducedScanRun`'s LIST stage now branches on. `OFFERED` shows the button; `OFFICIAL_SCAN`
shows why and a "Run its landing plan" button (the step-3 `PLAN` action); `UNREACHABLE` shows the describe
error. The answer is cached per `connectorId|Connector.type` for the life of the module (failures never
cached), since the page re-renders on every poll.

## 7. What was verified, and how

`tools/test_local.sh` (both `on-ebx-agent.jar`/`on-ebx-mapping.jar` from sibling worktrees per the common
brief) ends **"all green"**: the whole module compiles clean, both `.properties` files stay plain ASCII, and
every `*Test.java` runs, including the ones this lot adds:

- `deducedscan.FlattenTest`, `ColumnMeasurerTest`, `OperationCatalogTest`, `TableProposalTest`,
  `ReadRecipeTest`, `ListOperationPassTest`, `NameKeyPassTest` - pure, no EBX, exercise every branch named
  above (readOnly enforcement, type inference, validation refusals, JSON round-trip).
- `wizard.DeducedScanProgressTest` - the progress model's full state machine and JSON round-trip.
- `WizardRulesTest.hasUnconfirmedDeducedTable` - the pending-table check.
- `WizardPageTest.deducedScanCardsAndConfirmCardsRender` - a STATIC render harness (no browser, no Tomcat,
  same technique every other lot's `WizardPageTest` method uses): builds a `WizardPage.Model` by hand with one
  RUNNING deduced-scan pipeline card and one `TO_CONFIRM` Contact confirm card, calls `WizardPage.render`, and
  asserts the resulting HTML string contains the expected structure/wording (the pipeline card's stage
  summary, the confidence, the measured columns, the "How it is read" line, the Confirm/Leave-out buttons,
  and that "Continue to the landing" stays enabled with a warning while the pending table exists, and
  without the warning once it does not). This is the "render harness" asked for; no live Tomcat instance was used to visually
  confirm pixel layout against the mockups.

## 8. What was NOT run live

No live EBX instance was used for any write in this session (per the task's own read-only-MCP rule); in
particular, never exercised against real data:

- The full LIST -> SAMPLE -> MEASURE -> NAME pipeline against a real connector with no catalog operation (a
  real HubSpot-like connection) - the language-model prompts/tool schemas were shaped from the contract's own
  worked example, never run for real.
- `DeducedScanCatalogWriter`/`DeducedScanCatalog.ensureSource` against a real catalog dataset - whether the
  guarded writes actually degrade cleanly on today's (un-upgraded) `Asset`/`Column` schema was only reasoned
  through code (the same `getTableOccurrenceRootNode().getNode(...) != null` guard `CatalogReader.scanInfo`
  and several other classes in this module already use live), never measured with a live `describe_table`.
- `DeducedScanConfirm`'s three actions (confirm/leave-out/rename) against a real `Asset` row.
- The wizard's new step-2 rendering in an actual browser/Tomcat - only the static string-level harness above.
- `DeducedScanRun`'s background-thread/session-survives-the-request question - same open question
  `docs/STAGING-PROPOSAL.md` section 3 already flags for `StagingProposalRun`, inherited here verbatim (this
  lot reuses the identical executor/PipelineStep pattern, so the same risk and the same two lines of defence
  - the outer `try/finally` and the `STALE_AFTER_MINUTES` supersede rule - apply unchanged).

## 9. Guesses and judgement calls worth flagging

- `DeducedScanSample`'s `itemsPath` discovery is a fixed candidate list, not exhaustive - a connector whose
  useful rows sit one level deeper (`messages[*].value`, the contract's own Kafka-shaped example) is read as
  "the whole document, one item" today, not descended into automatically.
- `DeducedScanCatalog.ensureSource` stamps `platform="OTHER"` on the Source row it creates for a
  deduced-scanned connection - never measured what a real catalog scan would set there.
- The `DEDUCED_SCAN_START` button used to be shown on any application card with zero tables; since
  2026-09-27 only when the connection has no catalog operation (section 6, "Fix 2026-09-27"). The second LIST
  gate (a listing operation exists) is the language model's choice inside the run and cannot be pre-checked:
  the button can still end SKIPPED "an enrichment, not a source".
- `ColumnMeasurer`'s date/time regex is a plain ISO-8601-shaped pattern (not a real parse) - same "substring/
  pattern, not a real date library" posture `landing.ColumnProfileSql`'s own type inference already takes.
- Landing generation reading `Asset.readRecipe`/refusing a non-`CONFIRMED` deduced table (contract section 3,
  "Landing flow generation") is explicitly OUT of this lot's scope - owned by the parallel agent editing
  `LandingFlow`/`LandingFlows`, per the task brief's own "do not touch those files" instruction.

---

## 10. Fix lot (2026-09-25): a brand new connection with no catalog operation was a dead end

Branch `fix/deduced-scan-entry`, worktree `ebx-mdm-factory-deduced-entry`, from `main` (`5d23606`). Found
live right after deploying this module's own deduced-scan lot: picking a connection with no `catalog`
operation (OpenFDA measured live - operations `udi`/`deviceSearch`/`drug`, all read-only, no catalog
operation) in the "Connect a live system" onboarding panel (§9's own guided chain, `SourceOnboarding`/
`SourceOnboarder`) answered a flat dead end - `catalogOperation` BLOCKED, `Action.NONE`, "Connection type
{0} cannot be scanned into the catalog: pick a connection of a type that offers a catalog operation" - even
though the deduced scan this document describes exists for exactly this case. §6's own "guess" explains
why: `DeducedScanRun.start` only ever ran on an ALREADY-EXISTING, tableless `ProjectSource` - nothing in
the onboarding panel ever created one for a connection whose diagnosis stops at the `catalogOperation`
step.

### 10.1 The fix (bug 1)

- `SourceOnboarding.Facts` gained `hasReadOnlyOperation` (from the SAME `connectors_describe` answer
  `SourceOnboarder#describeInto` already reads for `catalogOperation` - no second MCP call), and
  `Action` gained `DEDUCED_SCAN`. `catalogOperationStep` now branches three ways instead of two: a catalog
  operation is still `DONE`; no catalog operation but at least one `readOnly=true` operation is `BLOCKED`
  with the NEW `wizard.onboard.step.catalogOperation.deduced` message and `Action.DEDUCED_SCAN` (BLOCKED,
  not TODO: like `OPEN_CONNECTION`, this is a step a PERSON must click, never something `SourcesRun`'s own
  background pass runs by itself - `ApplicationStages#group` already maps any BLOCKED sub-step to
  `NEEDS_PERSON` regardless of which action it carries, so the "applications in progress" pipeline card
  needed no change at all for this to render correctly); no catalog operation AND no read-only operation at
  all keeps the true dead end, renamed `wizard.onboard.step.catalogOperation.enrichmentOnly` (same wording
  family as `DeducedScanRun`'s own SKIPPED-entry message, §7 "background pass"). The old
  `catalogOperation.unsupported` key/behaviour no longer exists - every consumer (`SourceOnboardingTest`,
  `ApplicationStagesTest`, `WizardMessagesTest`) was updated, not left pointing at a stale key.
- New class `ebx.DeducedScanEntry` (the missing entry point): given a connector with `Action.DEDUCED_SCAN`
  reachable, re-confirms from a FRESH `connectors_describe` (never trusts a diagnosis the caller might be
  holding stale) that it truly has no catalog operation and at least one read-only operation, then (1)
  `DeducedScanCatalog#ensureSource` - the SAME helper the deduced-scan job itself already used for a
  connection that skipped official onboarding (§3's own bullet on `DeducedScanCatalog`) - finds or creates
  a minimal catalog `Source`; (2) adds it as an application of the project with the SAME code-proposal/
  trust-rank rules `SourcesRun#addApplicationIfReady` already applies for the official path (no schemas are
  known yet, so the code always falls back to the connector id, exactly like that method's own empty-schema
  fallback); (3) queues the deduced-scan job on the freshly-created `ProjectSource`
  (`DeducedScanRun#start`) - the SAME job board 2d already polls for, so the card switches straight into the
  four-stage pipeline the instant the click returns. Refuses (writes nothing) when the connection turns out
  to have a catalog operation after all, or no read-only operation at all (the enrichment-only case - "an
  enrichment, not a source. Nothing was created.").
- Wizard wiring: `WizardPage.renderOnboardNextAction` renders a `SOURCE_ONBOARD_DEDUCED` button for
  `Action.DEDUCED_SCAN` (new message `wizard.onboard.deducedScan`, "Use this connection for a deduced
  scan"); `ProjectWizardUserService#onboardDeduced` calls `DeducedScanEntry#start`, shows
  `wizard.onboard.notice.deducedStarted`, and closes the panel exactly like `onboardUse()` does for the
  official path - no new state to carry, the project's own step-2 board (§4/§5) picks the queued job up on
  its next poll. Neither the new action nor `DeducedScanEntry#start` is admin-gated (same as the existing
  `DEDUCED_SCAN_START`/plain `WizardData#addSource` - adding an application is not one of the MCP-write
  actions §7's javadoc reserves for administrators).

### 10.2 Display bugs 2/3 - verified with a static render, both in a real browser engine

Reproduced `WizardPage.render` with a hand-built `Model` (onboarding panel open, OpenFDA picked, the exact
"Connect a live system" state the brief describes) through a throwaway harness (same technique as
`scratchpad/wizard-preview-all/Preview.java`) served over plain HTTP and driven with a real Chromium
instance (not just a screenshot - `getBoundingClientRect()` on the actual DOM). Findings:

- **Bug 2 (checklist message wrapping one/two letters per line): did NOT reproduce.** The `.mdmf-onboard-
  name`/`.mdmf-onboard-text` flex split (a prior "coordinator review" CSS fix, already on `main` before
  this branch) already makes the BLOCKED row's message wrap normally at every width tried (560px and
  800px viewports) - screenshotted and confirmed word-level wrapping, not letter-by-letter. No code change
  made for this half of bug 2/3; documented here rather than silently dropped.
- **Bug 3 (combobox wider than its card / dropdown clipped): the literal overflow did NOT reproduce** -
  `getBoundingClientRect()` on `#mdmfOnboardConnectorCombo`/`#mdmfOnboardConnectorList` and
  `#mdmfCatalogSourceCombo`/`#mdmfCatalogSourceList` always stayed inside their card's own right edge, and
  no ancestor between the list and `<html>` had `overflow` other than `visible` (walked the whole chain
  with `getComputedStyle`) - so nothing in this static render clips the list or lets it spill into the
  neighbour card. **A real, related, and clearly reproduced defect was found instead**: the catalog-source
  combo's option rows (`.mdmf-combo-option`, `justify-content:space-between` with `.mdmf-combo-meta`'s
  fixed-width chips) left `.mdmf-combo-label` almost no room on a ~240px card - real catalog labels
  rendered as "Pers...", "CRM...", "ERP..." (screenshotted). Fixed: `.mdmf-combo-option` now wraps
  (`flex-wrap:wrap`), `.mdmf-combo-label` gets a `160px` flex-basis so it wins the first line whenever
  there is any room at all (the platform chip + connector id drop to their own line only when they don't
  fit), and `.mdmf-combo{width:100%;box-sizing:border-box}` was made explicit rather than relying on the
  block default - re-verified after the change: the same three labels now render nearly in full ("Persons
  SAP Sample export (C...", "CRM demo Snowflake account ...", "ERP demo Snowflake account -...") and the
  list's right edge still stays inside the card. This CSS is shared by every searchable combobox in the
  module (`combo.js`'s `mdmfInitCombo` - catalog-source picker, Connectors-connection picker, product-asset
  picker, LLM-connector picker on the Connexion screen), so the fix reaches all of them, not just step 2.
- **Not verified**: the real EBX runtime wraps a `UserServicePane` in its own scrollable container div
  (CLAUDE.md section 9, "window never scrolls: an EBX container div does") which this static harness has no
  equivalent for - if THAT container is what actually clips the dropdown live (rather than anything in this
  module's own CSS), no code in this repository can fix it; this was reasoned about, not measured against
  a live Tomcat/EBX session (the task's own read-only-MCP rule forbids it regardless).

### 10.3 Tests

`SourceOnboardingTest`: `typeWithoutCatalogOperationBlockedAtCatalogOperation` (renamed intent, now
explicitly sets `hasReadOnlyOperation=false`) plus a new
`typeWithoutCatalogOperationButReadOnlyOffersDeducedScan` (the OpenFDA fixture: `Action.DEDUCED_SCAN`, the
`deduced` message key, `catalogSource` still WAITING, diagnosis not ready). `ApplicationStagesTest`: the
existing `typeWithoutCatalogOperationCatalogNeedsPerson` updated the same way, plus a new
`typeWithoutCatalogOperationButReadOnlyStillNeedsPerson` confirming the grouped "catalog" stage still reads
`NEEDS_PERSON` (a click is required, never automatic) with the new message/action. `WizardMessagesTest`:
the `catalogOperation.unsupported` entry replaced by `.deduced`/`.enrichmentOnly`, plus
`wizard.onboard.notice.deducedStarted`. `tools/test_local.sh` ends "all green".

### 10.4 What was NOT run live, beyond section 8's own list

- `DeducedScanEntry#start` was never exercised against a live instance (no MCP write tool was called this
  session, per the task's own read-only rule) - the sequence (`connectors_describe` → `DeducedScanCatalog
  #ensureSource` → `WizardData#addSource` → `DeducedScanRun#start`) was reasoned from the already-live-
  verified pieces it reuses (`ensureSource` and `DeducedScanRun#start` are both existing, previously-
  documented code; `addApplicationIfReady`'s own code path, which this class mirrors, is likewise existing
  code, not new), never run end to end for real.
- The new `SOURCE_ONBOARD_DEDUCED` button/action was rendered and read back as HTML in the static harness
  above, never clicked through a real Tomcat instance.

### 10.5 Follow-up fix (same day): `Source.id` primary key, and the display bugs reproduced after all

Deployed and tried live: clicking "Use this connection for a deduced scan" (OpenFDA) answered "Cannot
create a new record because primary key has unset element(s): [osd:is-null(./id)]" and created nothing.
Cause: `DeducedScanCatalog#ensureSource` never set `./id` before creating the `Source` row -
`DataCatalog`/`Source.id` (live `describe_table`, 2026-09-25) is a plain MANDATORY `string` (max 40, e.g.
`"DWH_PROD"`, `"PERSONSSALESFORCESAMPLE"`), never an auto-increment - unlike `Asset.id`/`Column.id` (both
`int`, `autoIncrement=true`, confirmed by the same live `describe_table` call): `DeducedScanCatalogWriter`
never sets either explicitly and was already correct; the audit found no equivalent bug there. (Superseded by
audit D-07, section 16: `Asset`/`Column` are now keyed by natural keys the writer sets itself.)

- New pure class `deducedscan.CatalogSourceId` - `forConnector` is a VERBATIM port of the official path's
  own naming rule, `com.on.ebx.connect.core.CatalogDetection#sourceIdFor` (`ebx-connectors`, read-only
  reference): uppercase, non-`[A-Z0-9_]` runs become `_`, leading/trailing `_` stripped, blank falls back
  to `SOURCE`, truncated to 40 - matches the contract's own measured fixture (`SourceBidon` ->
  `SOURCEBIDON`) and this bug's own (`OpenFDA` -> `OPENFDA`) exactly. `unique(connectorId, existingIds)`
  disambiguates with a `_2`/`_3`/… suffix, always <= 40 characters, never returning an id already in the
  given set.
- `ensureSource` now reads every `Source.id` already in the catalog (a plain full-table scan - the table
  is small, same "read once for a defensive uniqueness check" pattern this module already uses elsewhere,
  e.g. `WizardData#existingSourceSystemCodes`), computes the id through `CatalogSourceId.unique`, and sets
  it explicitly before `doCreateOccurrence`. The existing connector-based reuse (`./connector=<id>`,
  checked FIRST, unchanged) still means a second deduced scan of the same connection never creates a
  second Source.
- **`SourceTrigger`/`CatalogFlows` (`ebx-data-catalog`, read-only reference, `~/Dev/ebx-data-catalog`) -
  what it does for a connector like this**: `CatalogFlows.ensure` reads the connector's real operations
  (`udi`/`deviceSearch`/`drug` for OpenFDA) and only creates a flow when one of them is literally named
  `catalog`/`profile`/`sample`/`execute`/`glossary`/`usage`/`lineage`/`refreshes` - `DECLARED_OPERATIONS`'s
  fallback list covers the same fixed set. None of OpenFDA's three operations match any of them, so EVERY
  `if (offered.contains(...))` branch is skipped, `created` stays `0`, and the trigger returns "0 flow(s)
  created (already present)" - a genuine, safe no-op, not an error and not a broken mapping. Nothing to
  avoid from the Factory side; no field/flag needed.
- `DeducedScanEntry#start` now wraps `ensureSource`'s `OperationException` with
  `"Could not register " + connectorId + " in the data catalog: " + ex.getMessage()` before rethrowing, so
  the wizard's notice (`ProjectWizardUserService#onboardDeduced`, unchanged - the outer dispatcher's own
  `catch (OperationException ex) { this.warn(ex.getMessage()) }` already surfaces whatever message is
  thrown) names what failed instead of a bare EBX message.
- **The two display bugs (2/3) DID reproduce, once tested at the coordinator's own live measurement (EBX
  pane, 943px viewport, card content 176-222px)** - the static harness used the first time around only
  ever rendered wide, 240-330px cards, which is why it missed both:
  - Bug 3 (combobox input wider than its card): `.mdmf-field input{width:100%}` (factory.css) was losing
    to SOME wider rule live (measured computed width 400px) - never found in this module's own CSS, most
    likely an EBX kernel default the wizard has no control over. Fixed with a higher-specificity,
    `!important` rule directly on `.mdmf-field .mdmf-combo input` (`width:100%`, `min-width:0`,
    `box-sizing:border-box`) - re-verified by literally injecting a competing `input[type="text"]
    {width:400px}` rule AFTER this module's own stylesheet in the static harness and confirming the input
    still computed to the combo's own width, not 400px.
  - Bug 2 (checklist message wrapping letter by letter): `.mdmf-onboard-name`'s `flex:0 0 auto` never
    shrinks, so at a 176px row it left `.mdmf-onboard-text` only ~21px. `.mdmf-onboard-step` now wraps
    (`flex-wrap:wrap`) and `.mdmf-onboard-text` has `flex:1 1 100%` (always its own full-width line under
    the name, on every card width, not just a wide one) - the row's bare `" - "` text node (a third,
    unstyled flex item) moved inside `<b class="mdmf-onboard-name">` so the row is exactly two flex items.
    Re-verified in the static harness at a forced 176px card width: the message now measures the full
    176px row width and the row's total height stays proportionate to the wrapped text (no longer one/two
    letters per line).
  - `WizardPageTest`'s onboarding-checklist assertions were updated for the new `"name - </b>"` markup
    order (previously `"</b> - "`); no other behaviour changed.

`tools/test_local.sh` ends "all green" again, `CatalogSourceIdTest` included.

---

## 11. Fix lot (2026-09-26): the first full live run (project DeducedTest, connection DemoCRM)

Branch `fix/deduced-scan-live-3`, worktree `ebx-mdm-factory-deduced-live3`, from `main` (`be52b18`). DemoCRM
(`listContacts` 30 rows, `listCompanies` 10 rows) went LIST -> SAMPLE -> MEASURE -> NAME -> confirm -> landing
for the first time end to end; OpenFDA (no listing operation) exercised the enrichment-only path. Eight defects
found, all fixed on read-only live evidence (`data-access` MCP on `MdmFactory`/`DataCatalog`) - no live write.

1. **`Asset.physicalName` (mandatory) was never written** - every deduced `Asset` row was invalid the moment it
   was created. `DeducedScanCatalogWriter#write` now sets it (same value as `qualifiedName`, unguarded - already
   a long-standing live field, used unguarded elsewhere in this module) and, guarded like the `scan*` fields,
   `firstSeen`/`lastSeen` (`Asset` and `Column`) and `Column.columnKey` (the `source:qualifiedName.column`
   convention an official scan's own Connectors mapping fills - CLAUDE.md, `CatalogOperations`). `Asset.rowCount`
   (hardcoded `0` before) is now the sample's own real row count.
2. **Confirming the last `TO_CONFIRM` table of an application never ran the landing plan** - the application
   card kept saying "No table yet" and step 3 stayed blocked until a manual `POST /sources/{id}/landing/plan`.
   `ProjectWizardUserService#autoPlanLandingIfDeducedScanDone` (new) runs `LandingPlanner.plan` once every
   deduced `Asset` of the same application (same `Asset.source`) is decided, called from both
   `deducedScanConfirm`/`deducedScanLeaveOut`. Best-effort: a failure is logged, not surfaced over the
   confirm/leave-out notice - the person can still open step 3 and run "Prepare the landing" by hand.
3. **"Use this connection for a deduced scan" landed on step 3 (Landing)** while the scan's tables were still
   `TO_CONFIRM`/being found. Root cause in `WizardRules#suggestedStep`: a brand new application with ZERO
   landing tables at all made `!allTickedTablesLoaded` true (vacuously - nothing ticked, nothing loaded) and
   jumped to Landing before there was anything there. Fixed with a new `hasAnyLandingTable` gate (only suggest
   Landing once at least one table is actually known); `onboardDeduced` also now sets `this.currentStep` back to
   `STEP_SOURCES` explicitly, the same defensive pattern `saveProject` already uses for its own step move.
4. **Board 2e showed "25 rows read" and every column "FILLED N/25"** for a 10-row source - `wizard.deduced.
   rowsRead` and `DeducedScanCatalog.PendingColumn.sampleSize` were both hardcoded to the requested sample cap
   (`DeducedScanSample.MAX_ITEMS`), never the real count. Fixed end to end: `DeducedScanCatalogWriter` writes the
   real sampled row count into `Asset.rowCount` (see item 1); `DeducedScanCatalog.PendingAsset`/`PendingColumn`
   read it back and propagate it; `WizardPage` reads `pAsset.sampleSize` instead of a literal `25`.
5. **Table names came back inconsistent** ("Company" singular, "Contacts" plural) - `NameKeyPass.systemPrompt`
   now explicitly asks for a SINGULAR business name in PascalCase, with a worked counter-example (no Java name
   rewriting, per the product rule).
6. **A deduced-and-confirmed key's message said "Source key: Primary key declared at the source."** -
   indistinguishable from a genuine database primary key, and wrong: it was proposed by the language model and
   confirmed by a person. `CatalogReader.Asset` now carries `scanOrigin`; `LandingPlanner` uses it to pick the
   `LLM` key origin (`wizard.step3.keyOrigin.LLM`, already existing - "proposed by the language model", the
   closest truthful existing origin) and a distinct rationale sentence for this one case, leaving `SourceKeyRule`
   itself untouched (it has no notion of which catalog a key came from, on purpose).
7. **The landing plan proposed a relation in BOTH directions** (`Company.id` <-> `Contacts.id`, confidence 80
   each) and then reported a `loadOrder` cycle. Root cause, confirmed against the live rows: `RelationRule`'s
   exact-name rule (confidence 80) matches ANY column named like another table's key - including a column that
   is ALSO its OWN table's entire key, purely because two tables happened to both use "id". `Contacts.properties.
   company` (the one real relation - its values are `Company`'s own id range) is not caught by any name rule at
   all, so it was never proposed either way; this fix does not add a value-based proposer for it (out of scope -
   flagged as a real gap below), it only stops the wrong, symmetric guess: a table's own entire single-column key
   is never proposed as a reference to another table's identically-named key. `RelationRuleTest.
   ownKeyNamedLikeAnotherTablesKeyIsNeverProposedEitherDirection` reproduces the exact Company/Contacts shapes.
8. **The enrichment-only outcome (OpenFDA: "an enrichment, not a source") ended both the `PipelineStep` and the
   `FactoryJob` `FAILED`** (red), although it is an expected, correct answer. New `PipelineSteps.endSkipped`
   (status `SKIPPED` - a plain free-text field, not an XSD enumeration, same convention `endStopped` already
   established); `DeducedScanRun`'s handler now only throws `FactoryJobQueue.ExpectedFailure` for a genuine
   `FAILED`, never for `SKIPPED` - the job itself ends `DONE`. The board 2d card already rendered a neutral chip
   for this state (`mdmf-chip-accent`, `wizard.deduced.state.SKIPPED` = "not a source") - untouched. Added: a
   "Remove it" button on a SKIPPED card, reusing the Review screen's own `SOURCE_REMOVE` action/guard verbatim
   (`WizardData.removeSource` already refuses if a landing was generated, never the case here) - removal is a
   person's own choice, not automatic, since `DEDUCED_SCAN_START` can also be pointed at a pre-existing
   application added some other way (&sect;6's own guess), so this code cannot always be sure the `ProjectSource`
   was created FOR this deduced scan alone.

**What is unverified**: none of the eight fixes were exercised against a live instance in this session (read-only
MCP only, per the task's own rule) - each is reasoned from the live evidence gathered by a read-only agent pass
(`data-access` on `MdmFactory`/`DataCatalog`, `connectors_describe`) plus the existing, already-live-verified
code each fix touches. Item 7 deliberately does not attempt to discover `Contacts.properties.company -> Company.
id` (that relation has no name-based signal at all; a value-based proposer would need real per-row samples
`RelationRule` does not have at plan time - flagged as a gap, not fixed here, so a person still has to add it by
hand from the landing key chooser). `tools/test_local.sh` ends "all green", `RelationRuleTest` included.

---

## 12. Fix lot (2026-09-26): the second full live run (project DeducedTest2, connection DemoCRM)

Branch `fix/deduced-scan-live-4`, worktree `ebx-mdm-factory-deduced-live4`, from `main` (`ba11f17`). Six defects
found from a coordinator pass on the same DemoCRM/OpenFDA pair, one live-confirmed with `data-access` (read-only):
`DataCatalog`/`Asset` 561 and 571 are BOTH `qualifiedName=Company`, `source=DEMOCRM`, and carry the byte-identical
`readRecipe` `{"connector":"DemoCRM","operation":"listCompanies","inputs":{},"itemsPath":"results"}` - a genuine
duplicate from a second deduced scan of the same connection (562/572 = `Contact`/`Contacts`, same shape).

1. **A second deduced scan of a connection already in the catalog duplicated its assets.** Nothing ever looked for
   an existing `Asset` before writing a new one - `DeducedScanCatalogWriter#write` always `doCreateOccurrence`d.
   Fixed with a recipe-based reconciliation, matched on the SAME signal the contract itself defines as identity
   (connector + operation + inputs + itemsPath), never the proposed table name (DEDUCED-SCAN.md item 11: "table
   names came back inconsistent" - a rename or a differently-worded second answer must not create a second row).
   - `deducedscan.ReadRecipe` gained `equals`/`hashCode` (same four fields).
   - New pure class `deducedscan.AssetReconciliation#reconcile(existingState, existingColumnNames,
     newColumnNames)`: unchanged columns keep whatever decision (CONFIRMED/LEFT_OUT/TO_CONFIRM) the asset already
     had; changed columns (added or removed) always go back to `TO_CONFIRM`, with a reason a person can read - "a
     person's decision is never silently reopened just because the source was scanned again", except when the
     shape it was decided on has genuinely changed.
   - `ebx.DeducedScanCatalog#findByRecipe` - every `Asset` of the source (ANY `scanState`, not just `TO_CONFIRM`)
     with a `readRecipe`, parsed back and compared with `ReadRecipe#equals`; returns the existing id, its
     `scanState` and every column's name + `primaryKey` flag. Never searched at all when the catalog does not
     have `readRecipe` yet (degrades to the original always-create behaviour).
   - `ebx.DeducedScanCatalogWriter#write` branches on `findByRecipe`'s answer: found -&gt; update the SAME `Asset`
     row (`rowCount`/`lastSeen` always refreshed; `qualifiedName`/`scanConfidence`/`scanRationale`/`scanState`
     only touched when `AssetReconciliation` says the columns changed) and REPLACE its `Column` rows (deleted
     with `doDelete`, recreated from the fresh sample) - a column's `primaryKey` is carried forward from the
     previous row when nothing changed (never clobbers a key a person chose since via the landing key chooser),
     or taken from the fresh proposal when columns changed/there was no previous asset at all.
   - **Not fixed live**: the two pairs of live duplicates (561/571, 562/572) - the task's own instruction was to
     document a one-off cleanup rather than delete them from this read-only session. Live-read (`data-access`,
     `MdmFactory`/`LandingTable`, `projectSource` 37): the landing plan already generated a LandingTable row for
     BOTH assets of each pair - `LandingTable` 61 (`Company`) -&gt; `catalogAsset` 561, `LandingTable` 62
     (`Company_2`) -&gt; `catalogAsset` 571, `LandingTable` 63 (`Contact`) -&gt; `catalogAsset` 572, `LandingTable`
     64 (`Contacts`) -&gt; `catalogAsset` 562. So a plain `DELETE` of one `Asset` of each pair is NOT enough (it
     would orphan the `LandingTable` row that points at it); the real cleanup is: pick which of the two
     `LandingTable` rows per application to KEEP (the one whose `sourceObject`/name a person is happiest with -
     e.g. keep `Company`/61, drop `Company_2`/62; keep `Contacts`/64, drop `Contact`/63, or the reverse), delete
     the OTHER `LandingTable` row and its `LandingColumn` rows (Data Services REST, `B` + dataspace prefix,
     CLAUDE.md &sect;6) if it was never loaded, THEN delete the now-orphaned `Asset` row it pointed at. Do this
     only after re-deploying this fix (so a THIRD deduced scan of DEMOCRM reconciles instead of creating a fifth
     row) - not attempted in this read-only session.
   - Test: `AssetReconciliationTest` (every branch: unchanged/added/removed column, blank prior state), extended
     `ReadRecipeTest` (`equals`/`hashCode`).
2. **The step-2 review card's key label always read "Key declared by the source:", even for a deduced-scan
   table's language-model-proposed, person-confirmed key** - `WizardPage#renderReviewTableRow` now picks the
   label from `TableInfo#scanOrigin` (already read by `WizardData#tableInfo`, unused here before this fix): `DEDUCED`
   -&gt; new key `wizard.review.key.deduced` ("Key proposed by the language model, confirmed by a person:"), same
   distinction `LandingPlanner`'s own `keyRationale` already makes for the landing table's message (item 6 of
   &sect;11). Verified live (Chrome, 943px, project DeducedTest2 fixture): "Company ... Key proposed by the
   language model, confirmed by a person: id" next to "SUPPLIER ... Key declared by the source: SUPPLIER_ID".
3. **"Prepare the landing"'s automatic key-finding stage re-asked the model for a key a person already confirmed
   via board 2e**, overwriting its origin/rationale with a fresh LLM answer - `LandingRun#runKeyStage` only
   skipped `USER`/`SOURCE` origins; a deduced-scan-confirmed key carries origin `LLM` (the closest truthful
   existing origin, `LandingPlanner`'s own choice) with confidence EXACTLY 100 (`LandingPlanner`'s own
   `deducedConfirmedKey` signature), indistinguishable from a genuine, still-uncertain `LLM` proposal without that
   exact check. New `landing.KeyCertainty#alreadySettled(origin, hasKeyColumns, confidence)` recognises it (exact
   100, not the lower `LLM_CENTAINTY_MIN`/90 "good enough" bar used elsewhere for a different purpose); a plain
   `LLM` proposal below 100 keeps being re-verified on every run, unaffected. `LandingRun.WizardSnapshotTable`
   gained `keyConfidence` (read from `TableInfo#keyConfidence`, already computed) to make the check possible.
   Test: `KeyCertaintyTest` (settled/not-settled for every origin, including the boundary at 90 vs. 100).
4. **The "Add more applications" disclosure closed itself on every postback** - a plain `&lt;details&gt;` with no
   `open` attribute, and this whole page is rebuilt from scratch on every round trip. New pure gate
   `wizard.WizardRules#addMoreApplicationsOpen(onboardOpen, onboardConnectorId, onboardDiagnosisPresent,
   pickedCatalogSourceId, sampleFilesReportPresent)` - open whenever the person opened it, OR an onboarding panel
   inside it is mid-flow (a connection picked, its diagnosis already run, a catalog source picked, or a
   sample-files import report just shown). Verified live (Chrome, 943px): `details.open` reads `false` with
   nothing set, `true` with `onboardOpen=true`. Test: `WizardRulesTest#addMoreApplicationsOpen`.
5. **The OpenFDA "not a source" card kept its three untouched stages reading "waiting"** (DeducedScanProgress's
   own Javadoc: "SAMPLE/MEASURE/NAME simply stay WAITING forever" once `LIST` ends the whole entry `SKIPPED` -
   documented as fine, but read live as a stuck card next to a chip that already says "not a source"). Fixed in
   `WizardPage#renderOneDeducedScanCard`: a stage still `WAITING` when the entry itself is `SKIPPED` renders as
   `SKIPPED` instead - the `wizard.deduced.stageShort.*.skipped` messages already existed for this (added by an
   earlier lot) but were never wired to any actual state before this fix. The "Remove it" button item 8 of
   &sect;11 added (`SOURCE_REMOVE`, reused verbatim) was found to already be correct: it renders whenever
   `pCard.entry.state` is `SKIPPED`, confirmed live (Chrome, 943px) - the coordinator's live report of "no Remove
   action" is most likely explained by `DeducedScanRun.RECENT_MINUTES` (15 minutes): a `SKIPPED` job falls out of
   `DeducedScanRun#batchJobs`'s own recent window exactly like a `DONE`/`FAILED` one, so the WHOLE card (button
   included) disappears if a person looks more than 15 minutes after the run finished - a real gap, left
   unfixed here (out of this lot's scope; flagged for a follow-up: either a longer window for `SKIPPED` cards
   specifically, or a standing "not a source" list independent of the recent-jobs window).
6. **The DEMOCRM card's end line stayed "2 table(s) deduced, waiting for confirmation." forever**, even after
   both tables were confirmed and one loaded - `entry.summary` is a snapshot frozen the moment the run itself
   finished, never updated by a later Confirm/Leave-out click. New pure class `wizard.DeducedScanCardSummary#parts
   (confirmed, leftOut, toConfirm)` - ordered, non-zero-only parts (confirmed, then left out, then waiting);
   `ebx.DeducedScanCatalog#decisionCounts(catalog, sourceId)` counts a source's `scanOrigin=DEDUCED` assets by
   `scanState` (guarded, all-zero when `scanOrigin` does not exist yet). `WizardPage#renderOneDeducedScanCard`
   uses the counts instead of `entry.summary` once the entry is `DONE` and at least one count is non-zero -
   falls back to the legacy frozen text otherwise (an older catalog, or a card whose counts were never
   populated). Verified live (Chrome, 943px): "2 tables confirmed." replaces the frozen text for a DONE card
   with `confirmedCount=2`. Test: `DeducedScanCardSummaryTest` (every ordering/filtering case).

**What was verified, and how**: `tools/test_local.sh` ends "all green" (every new/extended test above included).
Defects 2/4/5/6 were additionally verified with a REAL browser (not just a screenshot): a throwaway harness
(`scratchpad/wizard-preview-deduced4/Preview.java`, same technique as &sect;10.2's own harness) builds a
`WizardPage.Model` by hand for each scenario, renders the real `WizardPage.render`, serves it over
`python3 -m http.server`, and a live Chrome tab at a 943px viewport (the coordinator's own live measurement,
&sect;10.5) reads the DOM/text back - `get_page_text` for the visible wording, a `javascript_tool` probe for
`details.open` specifically. Defect 1 (the reconciliation) is unit-tested only (pure logic) - not re-run against
a live catalog in this session (read-only MCP, no write tool called); its premise is itself live-confirmed
(`data-access`, `DataCatalog`/`Asset` 561/571 above). Defect 3 (`KeyCertainty.alreadySettled`) is unit-tested
only - the wiring into `LandingRun` was not exercised end to end against a live "Prepare the landing" run in this
session.

---

## 13. Small fix lot (round 5, 2026-09-27): rescan button, per-outcome run message, explicit DEDUCED key origin

Branch `fix/deduced-scan-live-5`, worktree `ebx-mdm-factory-deduced-live5`, from `main` (`fb997f0`). Three small,
independent fixes, no live write in this session (read-only `data-access` only, per this round's own rule).

1. **The "Run a deduced scan" button only ever rendered when an application had NO landing table at all**
   (`pSource.tables.isEmpty()`, `WizardPage#renderReviewCard`) - live-verified during round 4 ("I had to post
   `DEDUCED_SCAN_START` by hand" - &sect;12 item 1), an application already onboarded through a deduced scan whose
   source later gained a new column or a new listable table had no way back into the wizard's own UI. New pure
   gate `WizardRules#hasDeducedAsset(SourceInfo)` - true whenever the application already holds at least one
   `DEDUCED`-origin table (`TableInfo#scanOrigin`), regardless of its `scanState` (CONFIRMED included, per that
   field's own contract). `renderReviewCard` now renders the SAME button/action (`DEDUCED_SCAN_START`, unchanged
   posting code in `ProjectWizardUserService`) whenever `pSource.tables.isEmpty() || hasDeducedAsset` - labelled
   `wizard.deduced.start` ("Run a deduced scan") in the original, table-less case, `wizard.deduced.rescan`
   ("Rescan") once a deduced asset already exists. A second/later click reconciles rather than duplicating
   (`AssetReconciliation`, round 4's own defect 9 fix), so this is always safe to offer. Verified with a REAL
   browser at 943px (`scratchpad/wizard-preview-live5/Preview.java`, same technique as &sect;10.2/&sect;12's own
   harnesses): a `SourceInfo` with one `DEDUCED`/confirmed table renders "Rescan" next to Remove; a `SourceInfo`
   with zero tables still renders "Run a deduced scan" - `get_page_text` read both back. `WizardRulesTest` covers
   `hasDeducedAsset` directly (empty tables, no deduced table, a `DEDUCED` table regardless of `scanState`, a
   null source).
2. **A rescan that reconciled every asset back to its earlier `CONFIRMED`/`LEFT_OUT` decision still ended with
   "N table(s) deduced, waiting for confirmation."** - `DeducedScanRun`'s own `outcome.summary` (also the
   `PipelineStep` row's message, and a card's fallback text before `DeducedScanCardSummary` has fresher counts,
   &sect;12 item 6) was one frozen phrase for every outcome, true only for a first-ever scan. New pure class
   `deducedscan.DeducedScanRunSummary` - `Counts` tallies four mutually exclusive per-table outcomes (`created`,
   `keptDecided`, `keptToConfirm`, `reopened`), `build(counts, problems)` composes the message: a single-outcome
   run reads exactly like before ("N table(s) deduced, waiting for confirmation.") or its natural opposite ("N
   table(s) up to date, decisions kept." / "N table(s) changed: to confirm again (columns changed)."), a mixed
   run lists each non-zero part ("1 new table(s) to confirm, 1 kept."). `DeducedScanCatalogWriter.Result` gained
   `created`/`reopened`/`keptState` (derived from the SAME `existing`/`AssetReconciliation.Decision` the writer
   already computes for reconciliation, round 4's own defect 9 - no new read); `DeducedScanRun#runOneSource`
   tallies one `Counts` bucket per written table and calls `DeducedScanRunSummary.build` instead of the old
   literal string. Test: `DeducedScanRunSummaryTest` (every branch, plus null/empty problems). Not verified live
   in this session (no write tool called) - unit-tested only, built directly on round 4's already-live-verified
   reconciliation.
3. **A deduced-scan-confirmed key was recognised by a fragile signature: origin `LLM` plus confidence forced to
   EXACTLY 100** (round 4's own defect 11 fix, &sect;12 item 3) - indistinguishable in `KeyCertainty`'s own
   vocabulary from a genuine, still-uncertain language-model proposal that happened to score 100 too.
   `LandingColumn.sourceKeyOrigin` (`KEY_ORIGIN` in `tools/build_xsd.py`) gained its own value, `DEDUCED`
   ("Proposed by the language model during a deduced scan, confirmed by a person" / French translation) -
   `MdmFactory.xsd` regenerated (`python3 tools/build_xsd.py`, clean diff, one new `xs:enumeration`).
   `LandingPlanner#plan` now writes `DEDUCED` (was `LLM`) for a deduced-confirmed key's columns; `KeyCertainty
   .certain`/`#alreadySettled` treat `DEDUCED` exactly like `USER`/`SOURCE` (always certain/settled, no sample or
   confidence check at all); the old `LLM`+confidence-100 branch of `alreadySettled` is DROPPED outright, not
   kept as a second path - a plain `LLM` key is never settled by that method any more, whatever its confidence.
   New message key `wizard.step3.keyOrigin.DEDUCED` (en/fr) renders wherever `wizard.step3.keyOrigin.*` already
   does (`WizardPage`'s several generic `"wizard.step3.keyOrigin." + origin` lookups) - the step-2 Review card's
   own distinct label (`wizard.review.key.deduced`, round 4's own fix, &sect;12 item 2) was untouched, it already
   keys off `TableInfo#scanOrigin`, not the column-level `sourceKeyOrigin`. **A row written by round 4's code
   (or by this fix's own code before a re-plan) keeps its OLD `LLM`+100 signature until the next "Prepare the
   landing" plans that table again** - `LandingPlanner` is idempotent and rewrites `sourceKeyOrigin` on every
   plan, so no migration of existing rows was written; documented here rather than fixed live (no write tool
   available this session). Test: `KeyCertaintyTest` extended (`DEDUCED` always certain/settled at any
   confidence, the dropped `LLM`+100 signature explicitly asserted NOT settled any more).

**What was verified, and how**: `tools/test_local.sh` ends "all green" (`DeducedScanRunSummaryTest` and the
extended `WizardRulesTest`/`KeyCertaintyTest` included). Item 1 was verified with a real browser at 943px, per
its own paragraph above. Items 2 and 3 are unit-tested only - neither was exercised against a live "Run a
deduced scan"/"Prepare the landing" pass in this session (read-only `data-access` only, no MCP write tool
called). The XSD change (item 3, `KEY_ORIGIN` gained `DEDUCED`) needs the model reloaded on the target instance
before `DEDUCED` is a legal `sourceKeyOrigin` value there - a Tomcat restart (or, per CLAUDE.md &sect;9, whatever
reload path the deployed instance already uses for this module's data model) after the next install, not
attempted from this session.

## 14. Fix lot (2026-09-27, round 6): the DEDUCED origin was lost again before it ever reached `KeyCertainty`, and the key study's masked sample values read as literal duplicates

Live incident (project DeducedTest3, board 2e confirmed, "Prepare the landing"): a table whose key columns
carried `LandingColumn.sourceKeyOrigin = DEDUCED` (round 5's own fix, &sect;13 item 3) was re-studied by the
key stage anyway - the language model was asked again, and answered with a low confidence ("Confidence too
low (55%)" / 5% on the second table), ending "Landing run paused, waiting for a key choice." for a key a
person had already confirmed.

1. **Root cause: `WizardData.originRank` did not know about `DEDUCED`, the exact same defect class as the
   2026-09-24 `SOURCE` incident (project Vague1 - see `WizardDataTest`'s own class Javadoc).** `KeyCertainty
   .alreadySettled`/`.certain` (round 5's fix) are correct in isolation and were never the problem - both
   already treat `DEDUCED` like `USER`/`SOURCE`. But the origin they are handed, `WizardSnapshotTable.keyOrigin`,
   is not read straight off one column: `WizardData#tableRow` folds every key column's `sourceKeyOrigin` through
   `WizardData.strongerOrigin`/`#originRank` into one aggregated value for the table, and `originRank` returned
   its `0` (unrecognised-origin) default for `DEDUCED` - the same rank the initial, never-yet-set accumulator
   starts from. Since `strongerOrigin` only overrides on a STRICTLY greater rank, a table whose key columns are
   ALL `DEDUCED` folded to `keyOrigin = ""`, not `"DEDUCED"` - `KeyCertainty.alreadySettled("", ...)` naturally
   answers false (an unrecognised origin), and `LandingRun.runKeyStage` fell through to a full `KeyStudy`
   re-study, calling the language model on a key already confirmed via the deduced-scan board. Fixed by adding
   `DEDUCED` to `originRank` (ranked just under `USER`, both being a person's own confirmation - board 2e for
   `DEDUCED`, the key picker for `USER` - and above `SOURCE`/`LLM`/`RULE`). `LandingRun.keySummary`'s own
   hardcoded origin-to-text mapping had the same gap (fell through to "proposed by the naming rule" for a
   `DEDUCED` table) and is fixed alongside it. Test: `WizardDataTest` extended with the exact live shape (a
   single `DEDUCED` column folding onto `""`, `USER` still winning over `DEDUCED`, `DEDUCED` winning over
   `SOURCE`/`LLM`/`RULE`) - reproduces the real path `LandingRun.runKeyStage` depends on (`WizardData
   .strongerOrigin`), not `KeyCertainty` in isolation, which was never wrong. Not verified live in this session
   (read-only `data-access` only): live-confirmed only that the reported `LandingColumn.sourceKeyOrigin` values
   were indeed `DEDUCED` on both tables (records 1506/1514), and that the run's own message matched this defect's
   signature - the fix itself was unit-tested, not re-run against a live "Prepare the landing" pass.
2. **The key study sends masked SHAPES by default (`LlmDataPolicy.Level.SHAPES_ONLY`, `KeyLlmPass.Sharing
   .SHAPES`), and nothing in the prompt said so - the model reasoned from the masked display as if it were real
   data.** The same live run's rationale text cited "every displayed sample row has the same value, A99, so its
   uniqueness is contradicted" while the measured `distinct` count (computed on the REAL values, never masked -
   `KeyEvidence`) said 10 distinct out of 25: several genuinely different ids of the same shape (e.g. three
   characters, letter then two digits) all mask to the identical string `"A99"`, and the model concluded
   non-uniqueness from that visual repetition instead of trusting the JSON's own `distinct`/`uniqueInSample`
   fields sitting right next to it. Considered sending no sample rows at all instead of masked ones, but that
   would also remove the model's only way to judge FORMAT - exactly why shapes are sent rather than nothing (see
   `docs/LANDING-KEY-STUDY.md`, "Why the model sees values") - so the fix keeps sending shapes and instead makes
   the masking explicit: `KeyLlmPass.userPrompt` now writes a `sampleValueNote` field (new method
   `KeyLlmPass.sampleValueNote(Sharing)`) whenever `pSharing != VALUES` - for `SHAPES`, it states plainly that
   values are masked, that two identical-looking masked values may be different real values, and that
   `distinct`/`uniqueInSample` (computed on the real data) are what to trust for uniqueness, shapes being only
   for format; for `NONE`, that values are withheld entirely and only the counts are available. Nothing is
   written when `pSharing == VALUES` (real values, no caveat needed). Test: `KeyLlmPassTest` extended -
   `sampleValueNote` present and mentioning both masking and the two trusted field names in `SHAPES` mode,
   present and mentioning withholding in `NONE` mode, absent in `VALUES` mode; `KeyLlmPass.sampleValueNote`
   itself asserted null/non-null per sharing level. Not run against a live language-model call in this session
   (no write tool, no live "Study the key"/"Prepare the landing" pass exercised) - the prompt text itself is
   what was verified, not the model's resulting answer on a masked sample.

**What was verified, and how**: `tools/test_local.sh` ends "all green" (extended `WizardDataTest` and
`KeyLlmPassTest` included, `KeyCertaintyTest` unchanged and still green). Live, read-only `data-access` reads
confirmed the reported `LandingColumn.sourceKeyOrigin = DEDUCED` on records 1506/1514 (`MdmFactory`/`MdmFactory`,
project DeducedTest3) - nothing else was exercised live: no write tool was called, per this round's own rule,
and neither fix was re-run against a live "Prepare the landing" pass or a real language-model call.

## 15. Small fix lot (2026-09-28): a stale keyConfidence survived even after the key origin was fixed

Live incident (project DeducedTest3, tables 74/75): `LandingColumn.sourceKeyOrigin` correctly reads `DEDUCED`
(round 5's own fix) and the latest "Prepare the landing" runs correctly skipped the key study - but
`LandingTable.keyConfidence` still read `55`/`5`, `keyRationale` still an unapplied study proposal's low-
confidence text ("Confidence too low (55%)..."). Those values were written by an EARLIER run of round 6's own
bug (`WizardData.originRank` not knowing `DEDUCED` yet, section 14 above): the table's key aggregated to
`keyOrigin=""`, `alreadySettled` answered false, the model was asked again, came back unsure, and
`WizardData.recordKeyProposal` recorded that low confidence on the table (the "not applied" path is designed
to keep a proposal visible while a person decides - it is not designed to run on a key that is already
settled). Section 14's fix stops this from happening again, but never corrected the two rows it had already
written, and by the time it landed the tables had moved past `PROPOSED` to `LOADED` - past the short-circuit
that would otherwise have self-corrected them on the next run.

Two independent fixes, `landing.KeyCertainty`/`ebx.WizardData`/`ebx.LandingRun` (no XSD change):

1. **A canonical settled value now exists, and self-heals both places a table's key is or becomes settled.**
   `KeyCertainty.settledConfidence(origin)` returns `(100, "<sentence>")` for `SOURCE`/`USER`/`DEDUCED` (the
   exact rationale text `LandingPlanner`/`WizardData.setTableKey`'s `USER` default already write when the key
   is FIRST settled - not a new convention), `null` for `RULE`/`LLM`/anything else (those keep whatever
   confidence the proposal that was actually applied carried - never forced to 100).
   `KeyCertainty.staleSettledCorrection(origin, confidence, rationale)` is the pure decision built on top of it:
   `null` when there is nothing to fix (unsettled origin, or the table already carries the canonical value),
   otherwise the `Settled` value to write. `ebx.LandingRun#runKeyStage` calls it (new private
   `selfHealSettledConfidence`) in BOTH places it can find a settled key: the `alreadySettled`-while-`PROPOSED`
   short-circuit (section 14's own fix), and the "left `PROPOSED` already, key is final" branch for a table
   whose landing was already generated - the second one is what actually reaches tables 74/75 today, since
   they are `LOADED`. The actual write, `WizardData.settleKeyConfidence`, is itself a no-op when the table
   already carries the canonical value (re-reads before writing) and never touches `LandingColumn`.
2. **`WizardData.recordKeyProposal` (the "not applied" write) now refuses outright on an already-settled key**,
   as a defensive backstop: it re-aggregates the table's CURRENT `LandingColumn.sourceKeyOrigin` (the same fold
   `strongerOrigin`/`originRank` already do) and checks `KeyCertainty.alreadySettled` before writing anything.
   With section 14's fix in place this should never actually be reached for a settled table any more - the
   caller already skips it - but a future bug of the same shape (a short-circuit that fails to fire) can no
   longer stomp a settled key's confidence through this path either.

Test: `KeyCertaintyTest` extended (`settledConfidence` per origin including the exact rationale text;
`staleSettledCorrection` reproducing the live incident's own numbers - 55 and 5 both corrected to 100 with the
canonical `DEDUCED` sentence, a table already at the canonical value left alone, `RULE`/`LLM`/blank/null never
corrected, a null rationale treated as empty). Not a live write in this session (read-only `data-access` only,
per this round's own rule): `tools/test_local.sh` ends "all green"; live reads confirmed the exact stale values
on tables 74 (`Company`, 55%) and 75 (`Contact`, 5%) quoted above, matching this defect's signature - the fix
itself was not re-run against a live "Prepare the landing" pass (no write tool called), so those two rows still
read their stale confidence until the next such run self-heals them (or an administrator resaves the tables).

## 16. Audit D-07 (2026-10-06): the catalog's natural keys

The data catalog dropped the auto-increment `/id` of `Asset`, `Column`, `LineageEdge`, `ProfileSnapshot`,
`TagSuggestion` and `Alert` (decided by the owner, catalog branch `fix/audit-wave5-natural-keys`, its `docs/UPGRADE.md`
sections 2-3). An asset is keyed `assetKey = source:qualifiedName`, a column `columnKey = assetKey.name`; a foreign key
to them holds that string itself. The factory side (branch `fix/audit-wave5-catalog-keys`):

- **Formulas.** `core.CatalogKeys` is a verbatim port of the catalog's `core/CatalogKeys` (the factory never loads a
  class of that module): `assetKey`, `columnKey`, plus `plausibleAssetKey` (refuses the digits of a pre-D-07 id),
  `lookupOrder` and `domToken`. `CatalogKeysTest` pins the outputs on the values the catalog's class gives for names with
  quotes, a pipe, a backslash, accents, spaces, colons and a slash. Lookups go through `CatalogReader.assetRow`
  (`computePrimaryKey(new Object[] { key })`), never `PrimaryKey.parseString`, which reads `|` and `\` as escapes.
- **Writer.** `DeducedScanCatalogWriter` sets `./assetKey` on the asset and `./columnKey` (always, from the row's real
  key) on every column. `Result.assetId` (int) became `Result.assetKey` (String).
- **Same source + same table name = the same asset (reconcile, not disambiguate).** The writer now looks the key up
  FIRST: when it exists and was written by a deduced scan, the fresh sample is reconciled into it whatever recipe wrote
  it before - another operation of the same run naming the same table, or a re-scan whose recipe differs. A different
  recipe reopens the asset (`TO_CONFIRM`, `AssetReconciliation.reconcile(..., recipeChanged)`, its own reason), the
  latest recipe and columns replace the previous ones, and the run's end message says "another read of the source
  (<operation>) named this same table - kept as one table, read the latest way, to confirm again". When the key holds a
  row of an OFFICIAL scan of the source, nothing is written ("already in the catalog from an official scan of this
  source, left as it is"). Only when the key is free does the writer fall back to the same-recipe lookup (section 12):
  unchanged columns keep the row under its own name; changed columns give it the fresh name, which is a new key - the
  row moves (`DeducedScanCatalog.rekey`).
- **Rename = re-key.** A key cannot change in place, and an asset whose name and key disagree would be found by no
  lookup and duplicated by the next scan. Board 2e's rename (`DeducedScanConfirm.rename`) therefore copies the asset
  and its columns to `source:newName` (`getContextForNewOccurrence(row, table)`, every value kept, `physicalName`
  untouched), deletes the old rows and answers the new key, which Confirm then uses. Refused, with a message, when the
  source already has a table of that name. Nothing else is moved: a profile snapshot, tag suggestion or lineage edge
  pointing at the old key keeps pointing at it (a deduced table waiting for confirmation has none).
- **Wizard.** `mdmfId` posts the key, read raw (never trimmed, never parsed as a number); an empty or unknown key is
  refused with a notice instead of the old silent `catch (NumberFormatException)`. The key travels only in escaped
  attribute values (`data-mdmf-id`); the rename input's element id is `mdmfDeducedName-<CatalogKeys.domToken(key)>`.
  `WizardPageTest.deducedScanKeysRoundTrip` renders five hostile keys and reads each one back from the attributes.
- **Not run live.** None of this was exercised on a running instance (the owner was giving a demo on it): see the
  checks listed in `docs/UPGRADE.md` section 2.1.
