# Wizard step 7 - "Distribution" (lot 3, wizard UI)

Implements SPEC-distribution.md's lot 3 on top of the already-merged lots 1 ("Engine",
`docs/DISTRIBUTION.md`) and 2 ("Data product", `docs/DISTRIBUTION-PRODUCT.md`): a new wizard step 7
"Distribution" between Matching (6) and Summary, which becomes 8. Built 2026-09-24 on branch
`feature/distribution-wizard`, from `main` `90fc068`, revised the same day after a coordinator review of
the first commit (`8fac613`) - see "Coordinator review (2026-09-24)" at the end for exactly what changed.
**No EBX instance was reachable during this session** - see "What was NOT verified live" below; every
claim about live behaviour beyond what the existing lot 1/2 classes already documented is a reasoned
guess, not a measurement.

Boards (`docs/mockups/wizard-flow/`): `DistributionMain.dc.html` (7a, targets), `DistributionModes.dc.html`
(7b, one target's setup panel and arming checklist), `DistributionDataProduct.dc.html` (7c, the data
product panel) and `SummaryDistribution.dc.html` (the step-8 delta this lot did NOT change - see
"Summary delta" below).

## Step renumbering

`WizardRules`: `STEP_DISTRIBUTION = 7`, `STEP_SUMMARY = 8`, `STEP_COUNT = 8`. `stepStates` gates
Distribution on the SAME prerequisite as Matching (the master is loaded, `MASTER_LOAD` step `DONE`) -
distribution targets and the data product both only need the master's golden records to exist, not a
finished matching pass (a REALTIME target's own `DistributionArmingGate` separately requires a first
matching run before it can arm - that is enforced inside the step, not by step reachability). Its
`completed` flag (the rail's checkmark) is `WizardSnapshot.distributionActive` - a new best-effort field
`WizardData.readSnapshot` sets when at least one `DistributionTarget` of the project is `ARMED` or
`PAUSED` (`ebx.DistributionReader.listTargets`, wrapped so a Connectors/distribution read failure never
breaks the rest of the snapshot). **Distribution never blocks Summary**: `stepStates[STEP_SUMMARY]` was
already `reachable(false)` unconditionally before this lot and needed no change; the step 7 panel also
renders an always-enabled "Next: summary" link (`GOTO_STEP=8`) independent of any target/product state,
per SPEC-distribution.md ("Distribution is optional: it never blocks the Summary").

`WizardPage.renderStep7` (formerly the Summary renderer) is renamed to `renderStep8`; its `data-step`
attribute and `hiddenAttr` call move from `7` to `8`. **Its own bundle keys were deliberately left under
the `wizard.step7.*` prefix** - a mechanical rename across `WizardPage`/`ProjectWizardUserService`/both
`Messages_*.properties` files was judged too large and too risky for this lot's time budget (dozens of
call sites, all string literals). The keys are internal identifiers, never shown to a user; only the
literal `data-step` attribute, the `STEP_KEYS` array and the constants that actually drive routing were
renumbered. `renderStep6`'s "See the summary" call-to-action was updated from `GOTO_STEP=7` to `=8`.

## Architecture

- **`WizardPage.DistributionModel`** (new, pure DTO, `com.on.ebx.factory.wizard` package) and its
  siblings `DistributionTargetRow`/`DistributionConnectionRow`/`DistributionReadinessRow`/
  `DataProductModel`/`DataProductTableRow` - no EBX class, so `WizardPageTest` hand-builds fixtures the
  same way `WizardSummaryData.Output` fixtures already do for step 8. `Model.distribution` is `null`
  until built (renders `wizard.step7dist.empty`), never a stale table.
- **`WizardPage.renderStep7`** and its helpers (`renderDistributionTargetList`/`-TargetCard`/
  `-TargetSetup`/`-ModeOption`/`-Arming`/`-ArmingChecklistItem`/`-Product`/`-ProductTargetOption`): three
  sub-views on the ONE panel (no separate screen per board, unlike the boards' own three-screen flow) -
  the 7a target list (default), a target's 7b setup card shown IN PLACE of the list once
  `DistributionModel.openTargetId` is set, and the 7c data product panel below the list, opened by its
  own toggle.
- **`ebx.ui.ProjectWizardUserService#buildDistribution`** (new): the EBX-bound assembly point, called
  from `model()` ONLY when `currentStep == STEP_DISTRIBUTION` or on the wizard's very first render
  (`!this.postedOnce`) - the SAME rule `buildSummary` already established for step 8 (fix/wizard-live-
  wave1 bug 7: `wizard.js` renders every panel up front and switches between them purely client-side,
  so a click through the rail to this step must already find real data from the very first server
  render). Calls the lot 1/2 classes DIRECTLY, in-process - `ebx.DistributionReader.listTargets`,
  `ebx.DistributionConnections.listCandidateConnections`, `ebx.DistributionRunner.readiness`,
  `ebx.DataProductPublication.detect`/`.plan` - never an HTTP loopback (SPEC-distribution.md/CLAUDE.md
  9's own rule). Every read is best-effort: a failure sets `DistributionModel.error` instead of taking
  down the rest of the wizard render (same discipline `buildSummary`'s own per-block try/catch already
  uses).
- Twelve new wizard actions in `ProjectWizardUserService#handle`'s action chain:
  `DISTRIBUTION_TARGET_OPEN`/`_CLOSE`/`_SAVE`/`_CONFIGURE`/`_INSTALL_TRIGGERS`/`_FIRST_FULL_SEND`/
  `_ARM_OVERRIDE`/`_DISARM`/`_SEND_NOW`, `DISTRIBUTION_PRODUCT_OPEN`/`_ADD_ASSET`/`_CREATE`. Each is a thin
  call into `ebx.DistributionReader.upsertTarget`/`setState`, `ebx.DistributionConfigurator.configure`,
  `ebx.DistributionTriggerInstaller.install`, `ebx.DistributionRunner.sendFirstFull`/
  `armWithoutFirstFullSend`/`sendNow`, or `ebx.DataProductPublication.plan`/`.create` - the same
  "transient instance state across one postback" convention as `stepReset`/`onboardOpen` elsewhere in this
  class (`distOpenTargetId`/`distProductPanelOpen`/`distNotice`/`distError`, and, coordinator review, the
  longer-lived `distProductDraft` - see below).
- New form fields on `ProjectWizardUserService.Posted`/`readPosted` mirror the setup/product panels'
  own input names (`mdmfDistConnector`/`mdmfDistOperation`/`mdmfDistMode`/`mdmfDistCron`/
  `mdmfDistScopeAll`/`mdmfDistApproval`/`mdmfDistIncludeCrosswalk`/`mdmfDistIncludePending`/
  `mdmfDistWatchedFields`/`mdmfDistSendScope`, `mdmfProductName`/`Code`/`Owner`/`Domain`/`Target`/
  `Schema`/`Refresh`/`Listing`). **Boolean checkboxes are plain named inputs** (`name="mdmfDistApproval"
  value="true"`), relying on ordinary HTML form semantics (an unchecked checkbox is simply not posted,
  so `ProjectWizardUserService.paramBool` reads it as `false`) rather than this codebase's usual
  "hidden field synced by `wizard.js` right before submit" convention used elsewhere in `WizardPage` -
  a deliberate simplification given this lot's time budget: it needs no `wizard.js` change for any of
  the seven new checkboxes, and is standard, well-defined HTML form behaviour. The one exception is the
  data product's per-table "included" checkboxes, which **share one name**
  (`mdmfProductTableChecked`, one `value="<key>"` per row) and are read with
  `UserServiceEventContext.getParameterValues` (a real multi-value HTTP parameter, not a `wizard.js`
  sync) - the only new `wizard.js` change this lot needed is copying a "Send now" button's own
  `data-mdmf-dist-send-scope` attribute into the hidden `mdmfDistSendScope` field, the SAME
  `data-mdmf-*` convention every other per-button parameter in that file already uses (see
  `wizard.js`'s own comment next to it).

## Mapping the boards to real data

| Board block | Real source |
| --- | --- |
| 7a target list | `ebx.DistributionReader.listTargets` (id/connector/operation/mode/state/lastMessage) |
| 7a candidate connections | `ebx.DistributionConnections.listCandidateConnections` - a connection without a write operation is never offered in the 7b connector picker (board: "listed greyed") |
| 7a "the master holds N golden records" | `readGoldenRecords`/`readMasterModel` (coordinator review - see below), `0` (no sentence) when the master was never built or the read fails |
| 7b arming checklist | `distribution.DistributionArmingGate.Readiness` (via `ebx.DistributionRunner.readiness`) for the three pipeline facts; "first full send done" reads the target's own `state == ARMED` directly (the fourth checklist item the board shows) |
| 7b Configure / Install triggers / Run the first full send / Arm without it / Disarm / Send now | `ebx.DistributionConfigurator.configure`, `ebx.DistributionTriggerInstaller.install` (model name from `master.MasterNames.modelName(projectCode)` - the same deterministic name `MasterGenerator` already publishes under, never asked of the user), `ebx.DistributionRunner.sendFirstFull`/`armWithoutFirstFullSend`/`sendNow`, `ebx.DistributionReader.setState(..., DISABLED)` |
| 7c "Snowflake is connected and reachable" | `ebx.DataProductPublication.detect`'s `warehouses.snowflake.{present,reachable}` |
| 7c "What the product contains" table, per-child nest mode, contract preview, "What EBX will create" | `ebx.DataProductPublication.detect` (candidates) + `.plan` (dry-run re-plan of the PERSISTED draft - problems/warnings/contractColumnCount/creationSteps), same shapes `distribution.product.ProductPlan.Result` already defines - see "Coordinator review" for the persistence fix |
| 7c "+ Add assets from the catalogue" | `detect`'s own `CATALOG_ASSET`-source candidates, offered via the shared `combo.js` searchable combobox; picking one posts `DISTRIBUTION_PRODUCT_ADD_ASSET` and moves it into the main table |
| 7c "Create and publish" | `ebx.DataProductPublication.create` - idempotent, re-plans server-side and refuses to write on a problem (`write.success:false`), exactly as lot 2 already implements |
| Step 8 "Distribute" card + "&lt;target&gt; not armed" open point | `buildSummary`'s own best-effort reads of `ebx.DistributionReader.listTargets`/`ebx.DataProductPublication.existingForSummary` (new, coordinator review) |
| Step 8 Deliver/Follow text | Plain bundle-text additions ("and the distribution" / "and every send") |

## What is not wired (documented scope reduction, revised after coordinator review)

- **Databricks target** - correctly rendered `disabled` with "no connection" (lot 2 itself: "no
  Databricks publisher exists yet" - `ebx.DataProductPublication.detectWarehouses` never even probes
  it), matching the board.
- **"More settings: continue in the Data product wizard"** - not built, exactly as lot 2's own
  `DataProductPublication`'s deviations section already states (no change to `ebx-data-catalog`,
  pre-filling its `ProductWizard` from here is a separate TODO on that side).
- **Watched-fields enforcement, retry/backoff visibility, event log (Cockpit-side)** - out of scope for
  this lot by design (SPEC-distribution.md lot 1's own "still NOT implemented" list already covers
  `watchedFields`; the wizard has no event-log screen, that stays a Cockpit concern per
  `docs/DISTRIBUTION.md` REST table).
- **The step-8 "Distribute" card's product line always reads "product"** (a fixed word, `wizard.step7.prod.distribute.product`)
  rather than repeating the board's own "Person 360 · Snowflake · Daily · product" full sentence with the
  product's actual target (Snowflake/Databricks/EBX only) - `DataProductPublication.existingForSummary`
  (the new best-effort read) only reads `code`/`name`/`refreshFrequency` off the `DataProduct` row, not
  its output port's target, to keep that read a single cheap lookup; a closer wording match would need a
  second `OutputPort` read, left as a follow-up.
- **A data product created under a hand-typed code** (the 7c form lets a person override the default
  `ProductPlan.sanitize(projectCode)` code) **is not found by the step-8 card's lookup**
  (`existingForSummary` only tries that one deterministic code) - documented in that method's own
  Javadoc; the card simply shows nothing for that product rather than guessing.
- **7c's "sync only on the panel's own actions"**: the persisted product draft (`ProjectWizardUserService
  .distProductDraft`, see "Coordinator review" below) is only updated by `DISTRIBUTION_PRODUCT_ADD_ASSET`/
  `_CREATE` - a table checkbox or nest-mode select touched then abandoned by clicking an UNRELATED wizard
  action elsewhere on the page (technically possible: the whole wizard is one HTML form, so any submit
  posts every currently-rendered field) is silently NOT persisted and reverts on the next render of this
  panel. Given every real path to changing these fields ends in "Add asset" or "Create" in normal use,
  this was accepted as a documented edge case rather than syncing on every postback (which would need
  reading the product form's fields even from actions that have nothing to do with distribution).

## Deviations from the boards, and why

- **One panel, three sub-views, not three screens.** The boards are three separate pages (7a/7b/7c);
  this lot renders all three on the ONE step-7 panel (list, in-place setup card, product panel below),
  to avoid inventing a second sub-step navigation vocabulary for what is already an optional step within
  the 8-step wizard. Opening a target's setup (7b) replaces the list rather than covering it.
- **7b's "Save" always shows every field**, including `cron` and the scope/approval checkboxes only
  when the mode is not `REALTIME` - the board's own conditional layout (fields appear once a mode is
  picked) is approximated with a plain `if` in Java rather than client-side show/hide JS, since the mode
  radios already round-trip on Save (no live re-render on radio click within the same request).
  Consequence: changing the mode radio in the browser does not immediately reveal/hide the cron/scope
  fields without a page reload; a future round could add a small `wizard.js` toggle purely for that.
- **Checkbox posting convention** - see the Architecture section above (plain named checkboxes instead
  of the codebase's usual hidden-field-synced-by-JS pattern).

## i18n

`wizard.step.distribution` (rail label) and ~90 new `wizard.step7dist.*` keys in both
`Messages_en.properties` and `Messages_fr.properties` (ISO-8859-1 `\uXXXX` escapes, per this module's
own convention; French sentences carrying an argument use the typographic apostrophe `'` per the task
brief). `WizardMessagesTest`/`WizardPageTest` both pass unchanged (no `ARG_KEYS` update was needed - see
Tests below).

## Tests

`WizardRulesTest` needed no new assertions beyond the renumbering (every existing test references
`WizardRules.STEP_MATCHING`/`STEP_SUMMARY` by constant, never a literal step number, so they keep
passing once the constants shift). `WizardPageTest`:

- `railTicksCompletedStepsWhateverStepIsOpen` - updated: step 7 (now Distribution) is `blocked` in the
  fixture used there (no master load yet), step 8 (Summary) keeps the old "always reachable, never done"
  assertion this test used to make about step 7.
- `step7ReflectsMasterAndMatchingReadiness` - updated: the `data-step="7"` substring extraction now
  targets `data-step="8"` (Summary's real panel number after the rename).
- **`step7DistributionEmptyStateThenTargetsAndProduct`** (new): the empty state
  (`Model.distribution == null`), a target list with one ARMED REALTIME target and one write-capable
  connection (checks the connector label, the state chip, the always-present "Next: summary" link, and
  the data-product panel's pre-open Snowflake-reachable note), a REALTIME target's 7b setup panel with
  the arming checklist (checks the target's own title, the "Master built" checklist line, and that the
  first-full-send button is `disabled` when matching has not finished), and an `ON_DEMAND` target's 7b
  panel (checks "Send now" is offered and the real-time arming checklist is NOT shown).

`tools/test_local.sh` (with `ON_EBX_MAPPING_JAR`/`ON_EBX_AGENT_JAR` from the task brief): **all green**,
including the whole module compile against the real `ebx-lib.jar` (every `DistributionReader`/
`DistributionConnections`/`DistributionRunner`/`DistributionConfigurator`/`DistributionTriggerInstaller`/
`DataProductPublication`/`MasterNames` call resolves against the real classes, not a mock).

## What was NOT verified live

No EBX instance was reachable during this session (task constraint - "instance only read-only", and in
practice no server was running). Every JSON field name this lot reads
(`ebx.DistributionReader.listTargets`'s `state`/`lastMessage`/..., `DistributionConnections`'
`hasWriteOperation`/`writeOperations`, `DataProductPublication.detect`'s `warehouses.snowflake.*`/
`candidates[].*`/`defaultDraft.*`, `.plan`'s `problems[].detail`/`warnings`/`creationSteps`/
`contractColumnCount`) was checked by READING THE SOURCE of those lot 1/2 classes directly (quoted
verbatim in this document's own tables above), not by an actual round trip - lot 1/2's own docs already
carry the "not verified live" caveats for the classes themselves; this lot adds no new live-verification
risk on top of them beyond the UI's own field-name agreement with them, which the successful compile
already confirms (a wrong field name would still compile - `JsonNode.path(...)` never throws - so a
genuine mismatch would only surface as a blank/wrong value on screen, not a build failure). Recommended
first checks on a live instance, in order:

1. Open step 7 on a project whose master is loaded: confirm the target list/connections render with
   real data, not blank fields (the single biggest risk given the "path() never throws" point above).
2. Create a target (`DISTRIBUTION_TARGET_SAVE`), Configure it, Install its triggers, and check the
   Connectors/DMA side exactly as `docs/DISTRIBUTION.md` section 8's own live-verification list already
   describes - this lot's actions are thin wrappers around exactly those same calls.
3. Open the data product panel on a project with a reachable Snowflake connection and a project without
   one; confirm the default draft/candidates/contract preview match `docs/DISTRIBUTION-PRODUCT.md`'s own
   expectations, then Create once.
4. Toggle a REALTIME target's mode radios and confirm the cron/scope fields' conditional visibility is
   acceptable without a live re-render (see "Deviations" above) - if it reads as broken in practice, add
   the small client-side toggle.
5. Open the 7c panel, uncheck a table, add a catalogue asset, set a child's nest mode, then Create - the
   plan/create call must reflect exactly that state (the persisted-draft fix below); a failed Create
   should re-render the SAME choices, never reset to the default draft.
6. Confirm step 8's "Distribute" card and the "&lt;target&gt; not armed" open point ("Run it") land on
   step 7 with that ONE target's setup panel already open (the combined-button click-order trick - see
   its own code comment in `WizardPage#renderOpenPoints`), on a real browser, not just the JUnit-free
   string-match test.

## Coordinator review (2026-09-24), applied in this pass

Four items raised on the first commit (`8fac613`), all closed in this pass:

1. **Step 8 Summary delta** (`SummaryDistribution.dc.html`) - now implemented. `WizardSummaryData.Input`
   gained `distributionTargets`/`distributionProductExists`/`distributionProductLabel`/
   `distributionProductRefreshMode` (plain pass-through, read by `WizardPage#renderProductionCards`
   straight off `Model.summaryInput`, the same convention `canLaunchPipeline`/`cockpitUrl` already use -
   no `Output` computation needed) and `OpenPointInput` gained an optional `targetId` (new overloaded
   constructor, the 6-arg one unchanged). `buildSummary` now reads
   `ebx.DistributionReader.listTargets` (best-effort, same try/catch discipline as its neighbours) to fill
   the "Distribute" card and add a WARN open point per REALTIME target not `ARMED`, and the new
   `ebx.DataProductPublication.existingForSummary` (a cheap, catalogue-only lookup by the product's
   default code - no MCP round trip, unlike `detect`) for the product line. The open point's "Run it"
   button is the interesting bit: it must both switch to step 7 AND open that ONE target's setup panel in
   a single click. Rather than extend `wizard.js`, the button carries BOTH `data-mdmf-action=
   "DISTRIBUTION_TARGET_OPEN"` and `data-mdmf-goto-step="7"` - `wizard.js` already attaches two
   independent click listeners per matching selector (`[data-mdmf-goto-step]`'s `showStep(...)`, then
   `[data-mdmf-action]`'s submit logic), both fire on one click in registration order, and `showStep`
   updates the script's own "current step" variable before the action handler reads it into the posted
   `mdmfStep` field - so the target opens on the right step with zero JS changes. Verified by a new
   `WizardPageTest` assertion on the exact markup, not by a live click-order trace in a browser (see
   "What was NOT verified live", item 6 above).
2. **7c "+ Add assets from the catalogue" and per-child nest mode** - both built.
   `DataProductModel.addableAssets` is now populated (every `CATALOG_ASSET`-source `detect` candidate not
   yet added) and rendered as the shared searchable combo (`combo.js`, `renderDistributionProductAssetCombo`
   - a third `initCombo(...)` call in `wizard.js`, no new widget code); picking one posts
   `DISTRIBUTION_PRODUCT_ADD_ASSET` with the asset's key, which moves it into the main table. A child
   row (`DataProductTableRow.child`) now renders an "Own table / Nested in &lt;PARENT&gt;" `<select>`
   named `mdmfProductNest__<key>`, read back into the draft on the next panel action. **This surfaced a
   real bug the first commit had**: the panel rebuilt its whole draft from `detect`'s default every
   single render, so unticking a table or changing a nest mode was silently discarded on the very next
   postback (exactly the "contract preview must follow these choices" problem the review named). Fixed
   with a new persisted `ProjectWizardUserService.distProductDraft` (a `ProductDraftState`, instance-
   scoped like `stepReset`/`onboardOpen`): seeded once from `detect`'s `defaultDraft` on first open, then
   only ever updated by `syncProductDraftFromPosted` - called by the panel's OWN actions (`_ADD_ASSET`/
   `_CREATE`), see "What is not wired" for the one documented edge case this leaves (a change abandoned
   via an unrelated action elsewhere in the form is not persisted).
3. **7a "the master holds N golden records"** - now computed, without duplicating the entity-spec
   assembly: `buildSummary`'s own `ProjectMasterEntities`/`MasterModel`/`GoldenRecordsReader.EntitySpec`
   block was factored into two shared private methods, `readMasterModel` and `readGoldenRecords`, called
   by BOTH `buildSummary` (unchanged behaviour/output) and the new `buildDistribution` (`d.masterGoldenCount
   = readGoldenRecords(...).goldenCount`, best-effort, `0` when the master has no built entities or the
   read fails). No second `GoldenRecordsReader` scan of the master table - one shared call site, two
   callers.
4. **Tests extended** for all three: `WizardSummaryDataTest` gained
   `targetNotArmedOpenPointCarriesTargetIdAndOrdersAsWarn` (the new `targetId` field/constructor survive
   `compute()` unchanged, still order as WARN) and `distributionTargetsAndProductPassThroughUnchanged`
   (the new `Input` fields are untouched by `compute()`); `WizardPageTest`'s
   `step7ReflectsMasterAndMatchingReadiness` fixture now also asserts the "Distribute" card (both a
   REALTIME and a SCHEDULED target, the existing product's name/refresh, the "Change" link) and the
   combined open-point button markup, and the Deliver/Follow text additions; a new
   `step7DistributionProductPanelOffersNestModeAndAddAssets` covers the nest select (present only for a
   child row, naming the real parent), the "added" tag on an already-added catalogue asset, and the
   add-assets combo listing only the NOT-yet-added ones.

`tools/test_local.sh` (both jars from the task brief): **all green** after every change in this pass.

## Retire notices from the merge log (UI lot, 2026-09-28)

Implements the "History and UI (UI lot)" section of `SPEC-retire.md` on top of the already-merged engine
(branch `feature/distribution-retire-log`, `aa6d08f7`): the merge-log watch itself
(`distribution.MergeLogWatch`/`ebx.MergeLogWatcher`), `DistributionTarget.retireMode`/
`retireUndoneMerges`, and the `reason`/`mergeId`/`survivorKey` fields on `DistributionEvent`/
`DistributionOutbox` all already exist and are wired into the dispatcher. This lot only adds the 7b
screen and the target's own send history. Boards: `docs/mockups/wizard-flow/Retire.dc.html` (7b, the
dashed "When two records are merged" block), `RetireHistory.dc.html` (the target's send history),
`RetireContract.dc.html` (context only, not a screen - where a retire notice comes from and its exact
JSON shape).

### The dashed block (7b, REALTIME targets only)

`WizardPage#renderDistributionRetireSettings`, called from `renderDistributionTargetSetup` only when
`t.mode.equals("REALTIME")` (same gating as the scope/approval checkboxes just above it) - a merge-log
watch only ever drains a REALTIME target's outbox (`ebx.DistributionDispatcher#drainTarget`), so the
block would be meaningless for `ON_DEMAND`/`SCHEDULED`. Contents, in the existing wizard idiom rather
than pixel-perfect against the board (no new CSS classes added - `.mdmf-panel`/`.mdmf-eyebrow`/
`.mdmf-hint`/`.mdmf-chip`/`.mdmf-chip-real`/`-bad`/`-info`/`.mdmf-choice-card`/`.mdmf-mode-card` all
already exist):

- A static, project-agnostic explanation and a four-line example list (`CREATE`/`RETIRE`/`RETIRE`/
  `MODIFY` chips) - illustrative only, never live data (the board's own "Personne 692/347/527" is
  business data from one real incident, deliberately generalised here so every project's wizard shows
  the same four lines rather than fabricated record ids).
- The `retireMode` three-way radio (`mdmfDistRetireMode`), reusing the exact `.mdmf-choice-card
  mdmf-mode-card` pattern `renderDistributionModeOption` already uses for the REALTIME/ON_DEMAND/
  SCHEDULED radios - `WizardPage#renderDistributionRetireModeOption`. `TOMBSTONE` is a fully selectable,
  savable option (the field just stores it); its own explain text says "Refused until the connector
  supports it" as a static hint. The actual refusal (`ebx.DistributionConfigurator#configure` throwing
  when `retireMode == TOMBSTONE` and the connector is not Kafka-with-tombstone-support - engine, not
  touched by this lot) surfaces through the SAME `DistributionModel#error` path every other Configure
  failure already uses (`ProjectWizardUserService#runDistributionTargetAction`'s existing `catch
  (OperationException) { this.warn(ex.getMessage()); }`) - no new error-surfacing code was needed.
- The `retireUndoneMerges` checkbox (`mdmfDistRetireUndoneMerges`), same plain-checkbox convention as
  `includeCrosswalk`/`includePending` just below it.

Both post alongside every other 7b field on `DISTRIBUTION_TARGET_SAVE`. `retireMode`/
`retireUndoneMerges` are only added to the outbound JSON body when `Posted.distRetireMode` is non-blank
(`ProjectWizardUserService#saveDistributionTarget`) - the radio's `name` attribute only exists in the DOM
when the block was actually rendered (a REALTIME target), so an empty posted value reliably means "the
block was not on this page" rather than "NOTICE was explicitly chosen". This mirrors
`DistributionReader.upsertTarget`'s own partial-write discipline for these two fields (`body.has(...)`)
and is the same "present = write it, absent = leave untouched" convention `tableDestinations` already
uses one field up - saving a target while it is NOT in REALTIME mode therefore never resets a
`retireMode`/`retireUndoneMerges` a previous REALTIME save already set.

### Target history (7b, existing target only)

Board `RetireHistory.dc.html`. `ebx.DistributionReader#listEvents` already existed (REST-only, no wizard
caller before this lot) but did not return `survivorKey`/`createdAt` - both were added to its JSON (two
lines, additive, same shape as every other field in `targetJson`/`listEvents`). `WizardPage#EventRow` is
a new pure DTO (id/tablePath/recordKey/change/status/message/reason/mergeId/survivorKey/createdAt);
`DistributionTargetRow#events` holds them, populated ONLY for the currently open target
(`ProjectWizardUserService#buildDistribution`, right after `d.openTarget` is resolved) - never for every
row of the plain 7a list, which would mean one `listEvents` call per target on every render. Best-effort:
a read failure leaves the list empty and `WizardPage#renderDistributionHistory` renders nothing (a no-op,
not a second error banner next to the panel's own).

Wording (`WizardPage#distEventDetail`), per the task brief:

- A `RETIRE` row with `survivorKey` set: `"merged into <survivor> · merge <mergeId>"`
  (`wizard.step7dist.history.retire.merged`).
- A `RETIRE` row with `reason=UNMERGE` and no survivor (the golden itself, deleted by the unmerge):
  `"golden deleted by an undone merge · no replacing id"` (`wizard.step7dist.history.retire
  .unmergedGolden`).
- A `CREATE` row with `reason=UNMERGE` (a former member reinstated once golden again):
  `"live again after an undone merge"` (`wizard.step7dist.history.create.liveAgain`).
- Every other row falls back to its own stored `message`, exactly as `listEvents` already returned it -
  no behaviour change for a plain trigger-written CREATE/MODIFY/DELETE event.

### i18n

~25 new `wizard.step7dist.retire.*`/`wizard.step7dist.history.*` keys in both `Messages_en.properties`
and `Messages_fr.properties`, same ASCII-with-`\uXXXX` convention as every other key in this bundle
(checked by `tools/test_local.sh`'s own non-ASCII grep).

### Tests

`WizardPageTest` (pure, no EBX): `step7DistributionRetireSettingsRenderRadioAndUndoneCheckbox` (the
block's title/explanation/example chips render, the three retireMode radios post under the right name,
a stored `TOMBSTONE` is the checked option and NOTICE is not, the TOMBSTONE hint text is shown, a stored
`retireUndoneMerges=false` renders the checkbox unchecked, and a brand-new unsaved target defaults to
NOTICE checked / the checkbox checked), `step7DistributionRetireSettingsHiddenForNonRealtimeMode` (no
title, no radio, no checkbox for an `ON_DEMAND` target), `step7DistributionHistoryRendersRetireAndReinstatedWording`
(all three special wordings plus the plain fallback render correctly from hand-built `EventRow`
fixtures, and a target with no events renders no history panel at all). `tools/test_local.sh`: **all
green**.

### What was NOT verified live

No EBX instance was reachable during this session (this module is a wizard/`ebx.ui`-only lot, deliberately
scoped away from `DistributionDispatcher`/`MergeLogWatcher`/`MergeLogWatch`/`DistributionConfigurator`/
the XSDs, all owned by the parallel engine work on the same base branch). Everything above was checked by
reading the engine's own source (`ebx.DistributionReader`/`ebx.DistributionConfigurator`'s exact field
names and refusal path, quoted above) and by the JUnit-free rendering tests, never by an actual round
trip. Recommended first checks on a live instance, in order:

1. Open a REALTIME target's 7b panel: confirm the dashed block renders where expected, save `TOMBSTONE`,
   confirm it persists (re-open the panel), then click Configure on a non-Kafka (or Kafka-without-
   tombstone-support) connector and confirm the refusal message from `DistributionConfigurator` actually
   surfaces as the panel's warning banner (this lot never triggered that path live - only the pass-through
   plumbing was verified in isolation).
2. Accept a suspect pair into an existing group (Match and Merge) on a project with an ARMED REALTIME
   target, then reopen that target's 7b panel and confirm the history table shows the golden's CREATE,
   the RETIRE rows with the "merged into ... · merge ..." wording, and that `createdAt`/`survivorKey`
   actually come back non-blank from `listEvents` (the two new JSON fields were added by inspection of the
   existing `targetJson`/`Date`-to-ISO-string pattern, not measured against a real `DistributionEvent`
   row).
3. Unmerge that group and confirm the golden's RETIRE (no survivor) and the two reinstated members'
   CREATE rows read with the dedicated "undone merge" wording, not the plain fallback message.
4. Confirm the table/history layout is legible at the wizard's usual panel width - the history reuses the
   generic `.mdmf-table`/`.mdmf-table-wrap` classes (no dedicated retire-history CSS was added), never
   checked in a real browser against this specific column set.

### Retire of a golden deleted by an unmerge after the first full send (lot S8, 2026-10-18)

The state sweep (`MatchingStateSweep` rule R2) used to treat a deleted auto-created golden like a merged member: announced only when its merge id was above the target's baseline. That is wrong for a golden, because the first full send publishes every GOLDEN row, so a golden whose merge id is at or under the baseline was already on the target (measured 2026-10-18, scenario run 4: golden 539 of merge 1, baseline 1, unmerged after the first send, no RETIRE queued). The sweep now also sends `RETIRE(golden, no survivor, UNMERGE, mergeId)` when the target recorded a first full send (`firstFullSendRunId` non-empty, read by `MatchingStateSweeper` into `Input.firstFullSendDone`) and the golden's merge id is at or under the baseline, or unknown. A target armed without a first full send ("Armer sans cela") keeps the old rule, and merged members keep theirs; former members come back as GOLDEN-only CREATEs once they are GOLDEN (PENDING_APPROVAL on RC3 gets nothing yet).

## Step 7 screen counts (2026-10-18)

Measured on E2EMASTER20261018181651 (master of 699 Personne rows - 212 golden, 487 merged -, 1068 Adresse, 234 Achat; first full send "Sent 980 row(s) across 3 mapping(s)"):

- **What the master holds.** `GoldenRecordsReader.Result.goldenCount` is the golden rows of ALL tables added up (212 + 534 + 234 = 980): it is no figure to show as "golden records". Step 7 and the summary quote the
  golden records (and merged records) of the MAIN object - the built object no other built object points to (`wizard.MasterMain`), per-table maps `goldenByEntity` / `mergedByEntity` - and the rows of every table on
  its own line; the rows of several tables are never added into one "records" figure. "Le master contient 212 golden records de Personne ; 980 lignes en tout, dans 3 tables."
- **Run texts.** A send writes `RunTexts.sent(...)` (code `wizard.runtext.dst.sent*`, the tables of the send in its detail) into the step, the target's `lastMessage` and the job; the job sentence is
  `dst.first.*` / `dst.send.*`. Old English rows are read back by `RunTexts.parse`. `DistributionReader.targetJson` keeps `lastMessage` in plain English and adds `lastMessageCoded`.
- **Target card.** The chip keeps the state; the card adds "Dernier envoi : 18/10 20:23, 980 lignes" (`DistributionTexts.lastSend`). A REALTIME target the person disabled AFTER its first full send is not a
  "not armed" open point; the Distribute card says "Désactivée après un premier envoi complet (980 lignes le 18/10 20:23)".
- **Connections.** A connection made for another Factory project (a target of another project uses it, its id/label names another project, or a scenario code `E2E...<digits>` that is not this project's) is listed
  under the folded "other connections" (`DistributionConnectionGroups`, `DistributionReader.otherProjects`).
- **Summary.** "Compared" in the last full run is the pivots each matching run says (`RunTexts.pivotCount`), per object; a link to the parent with no mapping of its own is no "no mapping feeds it" gap
  (`ReviewRules.attributeMappingWarning(..., pLinkToEntity, ...)`).

## Install triggers after lot F3 (2026-10-19)

The generated trigger bodies no longer read any Match and Merge field, so the old hint "a master table has no Match and Merge state field - reinstall once matching is activated" is gone (`matchingNotActiveHint` is always false; the wizard code that reads it was not touched). "Install triggers" is now an idempotent repair: each trigger is compared with the stored script, only a different one is rewritten, and the model is always republished (`triggersWritten` / `triggersUnchanged` in the step detail). Run it once on a project installed before this lot to replace its old `afterModify` body. Not seen running in the wizard (instance stopped).
