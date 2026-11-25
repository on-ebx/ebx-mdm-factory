# Wizard step 7 - "Summary"

Implements the mockup `docs/mockups/wizard-step7-summary.dc.html` as the wizard's real 7th step. Built
2026-09-24 on branch `feature/wizard-summary`, in parallel with a sibling lot on step 6 ("Matching
results") in another worktree - this lot never touches `WizardPage.renderStep6`/matching code, and keeps
a small reader of its own (`ebx.GoldenRecordsReader`) rather than depending on whatever the step 6 lot
builds for its own screen (the task brief's own instruction: "the coordinator will merge them later").

## Architecture

- **`wizard.WizardSummaryData`** (pure, EBX-free, `com.on.ebx.factory.wizard` package): `Input` in,
  `Output` out - verdict, journey diagram geometry, zone cards, ordered open points, completeness
  percentages, journal bars. Unit-tested directly by `WizardSummaryDataTest`, same discipline as
  `com.on.ebx.factory.ebx.ui.CockpitData` for the Cockpit screen.
- **`wizard.WizardPage`** (`renderStep7` and its `renderSummaryBanner`/`renderJourney`/`renderZoneCards`/
  `renderOpenPoints`/`renderCompleteness`/`renderProductionCards`/`renderJournal` helpers): renders
  `Model.summary` (`WizardSummaryData.Output`) and `Model.summaryInput` (the raw gates/URLs the pure
  Output does not carry - `canLaunchPipeline`, `cockpitUrl`...). `Model.summary == null` (a fixture that
  never computed one, or a brand-new project) renders the `wizard.step7.empty` notice, never a stale table.
- **`ebx.ui.ProjectWizardUserService#buildSummary`**: the EBX-bound assembly point, called from `model()`
  ONLY when `currentStep == STEP_SUMMARY` (every other step's postback pays nothing extra). Combines:
  - `WizardSnapshot` (already read for the whole wizard) - sources, staging feeds/mappings, per-entity
    quality/constraint proposal status (from `StagingEntityInfo.proposalLines`), match plans, and the
    mandatory-mapping-gap flag already computed by `WizardRules`/`WizardData` (`AttributeInfo.mappingWarning`).
  - **`ebx.ui.WizardSummaryReader`** (new, package-private, thin): the two facts `WizardSnapshot` does not
    carry - `StagingEntity.lastLoadWritten/lastLoadRejected/lastLoadAt` (only the decision status is on the
    snapshot today) and `PipelineStep.startedAt/endedAt` (the snapshot only has `startedAtMillis`, enough
    for "running since N min" but not a completed step's own duration). Also the unresolved `TransferReject`
    count.
  - **`ebx.GoldenRecordsReader`** (new, public): master row counts per promoted entity, golden/pending-
    suspect counts, and golden-record fill rate per business attribute - reads the business table directly
    (`Linked_Field_State`/`HasSuspects`, CLAUDE.md &sect;8), never the add-on's metadataset table. A table
    with no `Linked_Field_State` field (add-on absent, or never matched) falls back to a plain, unfiltered
    read of the master table, labelled `wizard.step7.completeness.titleFallback`.
  - **`ebx.PipelineWorkflowLauncher`** (new): `isPublished` (no session needed, CLAUDE.md &sect;7) gates
    the "Launch now" button; `launch` gets a `ProcessLauncher` and sets only the input parameters the
    published `MdmFactoryPipeline` model actually declares (`isDefinedAsInputParameter`).
  - `ebx.DeliveryDomainReader` (existing, `docs/DELIVERY.md`) for "Check the delivery domain" - the SAME
    read-only route the module already exposes, called directly (no HTTP loopback, no new delivery engine).

## Mapping the mockup to real data

| Mockup block | Real source |
| --- | --- |
| Verdict banner | `WizardSummaryData.verdict` - `OPERATIONAL` iff landing/staging/master all loaded and no ERROR-severity open point; `BLOCKED` iff nothing loaded past sources; `NOT_READY` otherwise. |
| Data journey SVG | `WizardSummaryData.journey` - one proportional band per SOURCE carried at its own row-share through Landing and Staging (3 hops, same y-range), then ONE aggregate Master -> Golden band (a matched/merged golden record can no longer be attributed to one source). **Simplified from the mockup's hand-authored bezier control points** - a generic banded-Sankey generator, not a pixel match; documented as a deliberate simplification rather than attempted fidelity, since the geometry only needs to stay proportional and readable for 1..~8 sources. |
| Zone cards (Landing/Staging/Master/Matching) | `WizardSummaryData.zones()` - facts assembled from `WizardSnapshot` + `WizardSummaryReader` + `GoldenRecordsReader`; "Open" is a real `<a>` (landing table, mapping matrix, master table - `ebx.ui.FactoryLinks.genericTableUrl`, new) or a client-side `GOTO_STEP` button for Matching (no separate screen - it opens this same wizard's step 6). |
| Open points | Computed, never hardcoded: unresolved `TransferReject` (ERROR), pending suspects (WARN, from `GoldenRecordsReader`), a mandatory attribute with every feeding mapping rejected (ERROR, from `WizardSnapshot.AttributeInfo.mappingWarning` - the exact same "Mapping matrix missing" notion, reused rather than recomputed), one row per proposal nature still `PROPOSED` (`WizardSnapshot.proposedCounts`, WARN), and a fixed "no schedule" INFO point once landing has loaded (the module has no per-project schedule concept yet - see below). |
| Golden record completeness | `GoldenRecordsReader` fill rate per business attribute (mandatory business attributes = `ACCEPTED`/`APPLIED`, not the entity's technical primary key) of GOLDEN-state master rows; falls back to plain master-row fill rate, labelled, when matching was never activated. |
| "Go to production" - Chain the loads | `PipelineWorkflowLauncher.launch` - launches `MdmFactoryPipeline` (`docs/TRANSFER-WORKFLOW.md`) for this project, `loadLanding=true/reviewRejects=true/scope=incremental` (same inputs the document's own instance 562 used), administrator-only, re-checked server side. |
| "Go to production" - Schedule... | **Not implemented** - the module has no per-project recurring-schedule feature yet (CLAUDE.md's own "taskScheduler"/Scheduler REST API notes describe the mechanism, but nothing in this module wires a project to it). The button links to the Cockpit and `docs/TRANSFER-WORKFLOW.md`'s own Task Scheduler section instead of pretending to schedule anything - `wizard.step7.prod.chain.scheduleHint` says so explicitly. |
| "Go to production" - Deliver | `DeliveryDomainReader.read` (existing, read-only) - component count + pre-check error/warning tally. The actual archive export/import stays `tools/deliver.sh` against the EBX Staging REST API (`docs/DELIVERY.md`) - a link to that doc, not a new "Generate archive" button, per the task brief ("do not invent a new delivery engine"). |
| "Go to production" - Follow and explain | Plain links: Cockpit (`FactoryLinks.cockpitUrl`) and Record lineage (`CockpitPage.lineageUrl`, reused). |
| Last full run journal | `WizardSummaryReader.readSteps` - the LATEST completed (`startedAt` AND `endedAt` both set) run of `LANDING_LOAD`/`STAGING_LOAD`/`MASTER_LOAD`/`MATCHING_RUN`; a step that never completed is simply absent from the journal (never a fabricated zero-second bar). |

## Simplifications, and why

- **Journey diagram geometry is a generic proportional Sankey, not the mockup's exact bezier curves** - see
  table above. Verified for 1, 2 and 8 sources (`WizardSummaryDataTest`), including that every node stays
  inside the SVG canvas and no path/height computation produces NaN or a negative value.
- **Pending suspects is an approximate count**, not an exact pair count: `GoldenRecordsReader` counts GOLDEN
  rows with `HasSuspects=true`, which can double-count a pair where both sides still carry the flag. The
  parallel step 6 ("Matching results") lot may read the metadataset's own `Suspected` table for an exact
  pair count; this lot deliberately stayed with the business-table-only read CLAUDE.md &sect;8 documents as
  sufficient for a summary card, to avoid a second dependency on the add-on's metadataset schema while step 6
  is being built concurrently.
- **`CockpitData`/`CockpitPage` were not reused directly** even though the task suggested it: `CockpitData`
  and `FactoryCockpitUserService` are package-private / private-method reads in the SAME `ebx.ui` package the
  parallel step 6 lot also touches; rather than refactor `FactoryCockpitUserService`'s private `readX()`
  methods into shared statics (a real risk of a merge conflict with concurrent work in the same package,
  and `CockpitData.compute()` does not even compute per-entity MASTER volumes - see its own `VolumeRow("master",
  null, null, "NONE")` - so master zone facts needed a dedicated read regardless), this lot wrote a
  narrowly-scoped sibling reader (`WizardSummaryReader`) that reads only the two facts `WizardSnapshot` was
  missing. Everything `CockpitData` WOULD have given for free but `WizardSnapshot` already carries (sources,
  staging feed/mapping counts, per-entity quality/constraint acceptance) is read from `WizardSnapshot`
  instead - zero duplicate EBX reads for those.
- **Staging/constraint "accepted" counts come from `WizardSnapshot.StagingEntityInfo.proposalLines`**, which
  is itself built from `ProposalReviews.lines` (already loaded for step 4) - not a fresh `QualityRule`/
  `CapabilitySuggestion` table scan.

## Deployment notes

- No XSD/data-model change (`WizardSnapshot.SourceInfo.landingDataset` is a new field on an EXISTING pure
  Java class, not a schema field - it copies `ProjectSource.landingDataset`, a field the model already had).
- No restart needed for the module's own code (a Java-class/JS/CSS change under `backend.mode=development`
  still needs a full Tomcat restart per CLAUDE.md &sect;9's own "a Java class already loaded by Tomcat never
  reloads without a full restart" - same as every other lot in this module).
- `PipelineWorkflowLauncher` assumes `MdmFactoryPipeline` is already published (`docs/TRANSFER-WORKFLOW.md`)
  - if it never was on a given instance, "Launch now" stays disabled with `wizard.step7.gate.notPublished`,
  never a 500.
- **Nothing in this lot was click-tested against a live instance** (Tomcat was not reachable from this
  worktree during this session - the MCP calls to check `MdmFactoryPipeline`'s publication state and to
  read a real project's `Linked_Field_State`/`HasSuspects` fields both answered `ECONNREFUSED`). Every EBX
  API call (`WorkflowEngine`/`ProcessLauncher`, `AdaptationTable`/`RequestResult`, `DeliveryDomainReader`)
  was checked against the real `ebx-lib.jar` (`javap`) and against sibling classes in this same module that
  already use the same APIs live (`workflow.MatchingRunTaskBean` for the workflow launch shape,
  `ebx.MasterZoneIndex`/`ProjectMasterEntities` for the table-read shape) - not against a running server.
  Field names `Linked_Field_State`/`HasSuspects` are exactly as CLAUDE.md &sect;8 documents them, measured
  there on a real project (`Companies_Master`); this lot could not re-verify them on `Personnes`.

## Tests

`ebx-mdm-factory/src/test/java/com/on/ebx/factory/wizard/WizardSummaryDataTest.java` - verdict (blocked/not
ready/operational/kept out of operational by an error), open-point ordering, completeness percentages
(including a 0-total row never dividing by zero), journal bar proportionality and duration formatting,
journey geometry for 1 and 8 sources (every node inside the canvas, no NaN/negative height) and for 0
sources (no crash, no source node). `WizardPageTest.step7ReflectsMasterAndMatchingReadiness` (updated, was
asserting on the old thin table) exercises the empty state, a populated `Input`/`Output`, and that the
rendered HTML never contains a literal `<script` tag. `WizardMessagesTest.ARG_KEYS` updated for the new/
removed `wizard.step7.*` keys.
