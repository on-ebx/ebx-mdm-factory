# UX lot C1 - two defects of the Sources step (third wave)

2026-10-14, branch `fix/ux-c1-sources` (from `main` b5c71760, which holds every UX lot). Seen live on 2026-10-13 on project UxCrmSf2 after
lot U2 was deployed (`ux-factory-wizard-2026-10-13/AVANT-APRES.html`, "Ce que le rejeu a montre en plus" points 1 and 2; screenshots `a07b`,
`a09`). Status: coded, tests green, NOT deployed, NOT seen running.

## 1. Tables of a new application start unticked on every wizard path

Measured: picking "Salesforce CRM" in the single list ran "Adding a source" (0 s) and the card said "17 of 17 selected".

What was found by reading the code (the cause of the live observation could NOT be reproduced offline): `SourcesRun.addApplicationIfReady` already
receives `selectNew = !"sample-file".equals(facts.connectorType)` and `LandingPlanner.plan(..., false)` writes `LandingTable.selected = false` for the
tables it adds, as does the catalog-pick form (`addSource`), "Find its tables" and "Scan again". So the tree as merged (b5c71760) unticks on this path;
a build that still ticks them predates lot U2's `SourcesRun` change (the coordinator's deployment of 2026-10-13 copied a jar by hand) - check the
deployed version before looking further.

One path really did tick: `ProjectWizardUserService.autoPlanLandingIfDeducedScanDone` (the plan that runs once every table found from the data is decided)
called the 3-argument `plan` (ticked). Fixed.

The rule is now ONE pure function, `wizard/NewTableSelection.selectNew(connectorType)`: ticked only for a `sample-file` connection (its tables ARE the
person's choice, kept as U2 decided), unticked for everything else, unknown type included. `SourcesRun` (list pick, "Do everything", live panel, "Use this
source") and the deduced-scan auto plan use it. Unchanged on purpose: REST (`FactoryResource`), the accelerators and the step-3 Plan button keep the
ticked default; a table planned earlier, ticked or loaded, is never touched by a plan.

## 2. A connection with no catalog operation is pickable from the list

Measured: DemoCRM (type `rest`, source `DEMOCRM` already in the catalog with 2 tables found from the data) was greyed "no catalog scan: see More ways below".

`AddApplicationOptions` (pure), for an active connection that is not yet in the project and has NO catalog operation:

| situation | row | chip |
| --- | --- | --- |
| its catalog source exists and holds tables | pickable | "found from the data, N tables" (`FOUND_FROM_DATA`) |
| no such tables but it can list records | pickable | "will be read from its data" (`DEDUCED_READ`) |
| it can neither be scanned nor read | greyed | "cannot be read: <reason>" (`CANNOT_READ`) |

Reasons (`wizard.src2.app.reason.*`): "it has no operation that lists records" (`NO_READ_OPERATION`), "its operations could not be read" (`DESCRIBE_FAILED`).
"See More ways below" no longer appears anywhere. Rows with a catalog operation, inactive, in project, lineage-only and connection-missing are unchanged.

"Can be read" (`AddApplicationOptions.unreadableReason`, the first gate of the deduced scan, cf. `DeducedScanEligibility`): from `connectors_list`, a
type that lists operations is read when one of them has kind `read` (a `search` needs a value to look for, so it is not a listing: Slack's `channels
(search)` does not make Slack readable). A type that lists none (a REST connection keeps its operations per connection) is described with
`connectors_describe` (`SourceOnboarder.readabilityInto`, only when the list is opened, only for rows nothing else decides: no catalog operation, active,
no catalog source already holding tables); a read-only operation makes it readable, a failed describe is "unknown", never "no operation".

Picking such a row posts the same `SOURCE_ADD_APP`; `addApplicationFromConnection` finds the connection in the loaded list (reloaded when empty) and,
when `AddApplicationOptions.usesDeducedScan` (no catalog operation), calls `DeducedScanEntry.start` - the existing deduced flow (application added,
deduced scan queued, card shows its stages, tables to confirm) - instead of `SourcesRun`. That scan keeps the person's earlier decisions on tables
already found (`CONFIRMED`/`LEFT_OUT` kept), so DemoCRM's 2 tables are not lost.

## Files

`wizard/NewTableSelection` (new), `wizard/AddApplicationOptions` (states, `unreadableReason`, `usesDeducedScan`, `Row.reason/showsTables`),
`wizard/SourceOnboarding.ConnectionOption` (`readable`, `unreadableReason`), `ebx/SourceOnboarder` (`readabilityInto`), `ebx/SourcesRun` (one line),
`ebx/ui/ProjectWizardUserService` (`addApplicationFromConnection`, `autoPlanLandingIfDeducedScanDone`), `wizard/WizardPage` (chip of a row), both bundles
(`wizard.src2.app.state.*` replaced `NO_CATALOG_SCAN`, `wizard.src2.app.reason.*`, hint).

## Verified by test

`NewTableSelectionTest` (new), `AddApplicationOptionsTest` (the measured list: DemoCRM, GenericREST, Slack, OpenAI, OL_SALESFORCE_EU_PROD; the readability
rule; the pick path), `WizardPageTest.singleAddApplicationListRenders` (chips and pickable/greyed rows in the HTML). NOT tested offline: the live jobs
(`SourcesRun`, `DeducedScanEntry`) and `connectors_describe` behind `readabilityInto`.

## NOT done

- The wiring `SourcesRun` -> `LandingPlanner.plan(false)` is EBX-bound and has no offline test (only the rule has).
- A connection WITH a catalog source and tables found from the data, once the deduced scan has been confirmed, still goes through the deduced scan
  (queued, an LLM pass) rather than planning the existing tables at once; planning at once would bypass the confirmation of `TO_CONFIRM` tables.
- A REST connection whose own `connectors_describe` shows a `catalog` operation (the list cannot see it) is offered as "will be read from its data"
  and DeducedScanEntry then refuses it with "has an official catalog operation": rare, left.
- An LLM connector's operation kinds were not measured; if its `complete` is a `read` it will be offered, and `DeducedScanEntry` refuses it at the click
  ("looks up one record at a time... Nothing was created") when it has no read-only operation.

## Live checks (compare with `a07b`, `a09`)

1. Pick Salesforce again in a fresh project: its card must read "0 of 17 selected". If it reads 17 of 17, check the deployed build version first.
2. Open the list: DemoCRM is pickable, chip "found from the data, 2 tables"; GenericREST "will be read from its data"; Slack and OpenAI greyed
   "cannot be read: ..."; OL_SALESFORCE_EU_PROD greyed "no connection to read it". No "see More ways below".
3. Pick DemoCRM: an application appears with the deduced-scan stages; its confirmed tables keep their decisions; once all are decided the plan runs and
   the tables arrive unticked.
