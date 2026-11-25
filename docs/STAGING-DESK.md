# Validation desk (lot L1)

The fourth view of step 4 of the project wizard, next to the wizard card, the Mapping matrix and the Feed designer. It shows the SAME decisions as the matrix,
organised by WHAT THE PERSON ACTUALLY DECIDES instead of by row type, and it never re-implements a decision: every button posts the matrix' own action name and the
matrix' own handler writes it. Mockups: `docs/mockups/staging-desk/` (`Main.dc.html` = the screen, `Done.dc.html` = the empty state; `Check.dc.html` is lot L3).

Code: `ebx.ui.StagingDeskServiceDeclaration` / `StagingDeskUserService` (the EBX-bound part), `core.StagingDeskLink` (the URL), and the pure package
`stagingdesk` (`DeskModel`, `DeskGroups`, `DeskHtml`, `DeskMessages` + `DeskMessages_en/fr.properties`). Tests: `stagingdesk/DeskGroupsTest`, `DeskHtmlTest`.

## 1. What the screen shows, and where each value comes from

| On screen | Comes from |
|---|---|
| The items of every group | `MatrixCompute.stillToDecide(project, threshold)` - the very list the matrix' footer counts (`project` = `MappingMatrixReader.read`, threshold = `MatrixCompute.DEFAULT_BULK_THRESHOLD`) |
| "Accept what you see", one group per business object | the items of kind Business object, Field, Matching key, Computed field, Source link, Field link of that object |
| "Checks", one group per business object | the items of kind Rule and Check (quality rules, script constraints, script derivations not decided with their field) |
| "Cannot run as proposed", one group | `wizard.StillToDecide.of(snapshot).checks` filtered by `CheckItem.cannotRun()`: `CapabilitySuggestion.ready = false` -> "No secret" (+ `readinessDetail`), binding containing `toBeProvided` -> "Needs an input no field holds" (`StillToDecide.queryToWrite`). Same rows the wizard card's "Set all N aside" sweeps |
| "Follow their object - nothing to decide" | `WizardSnapshot.AttributeInfo.followsEntity()` (technical key `primaryKey`, parent link `parentLink`, see `staging/ParentLinkRule`, `ebx/ParentLinkReader`); without a snapshot, `MatrixModel.Attr.sourceKey` / `parentLink` |
| Folded optional line | `MatrixCompute.optionalServices(project)` minus the ids already in "Cannot run": look-ups that could run, MAPPING-MATRIX sec 15 - listed, never counted |
| "N decisions for you" | the number of groups (non-empty), one each: an object group counts 1 however many lines it holds. The optional lines and the "followed" list are not groups |
| "M proposals are still open" and "X of Y proposals decided" | every decidable row of the matrix model (business object, non-key field, source link, field link, rule/check/service) and how many are no longer `PROPOSED` (`DeskGroups.figures`). A suggestion set aside is in neither (the matrix model does not hold it) |
| "N proposals" chip, "N points to check" chip | the group's lines; points to check = distinct fields with a line whose reason is `MatrixCompute.REASON_ATTENTION` (the `AttentionRules` flag) - the lines themselves are also counted (`Group.flaggedItems`) |
| A line: kind, element, proposition, why, confidence | `StillItem` (kind, label, reason, confidence) + the matrix model (`Attr` type / mandatory / derivation / rationale, `Cell` landing column / steps / rationale, `Guard` kind / argument / severity). The "why" is `MatrixHtml.reasonText` - the matrix queue's own words - then the proposer's rationale |
| Views in the header | wizard step 4 (`FactoryLinks.wizardUrl(..., STEP_STAGING)`), the matrix (`core.MappingMatrixLink`), the Designer (`Feed.designerUrl` of the object's first root feed). All `target=_top`, class `mdmf-matrix-designer-link` (voluntary departure, rewritten into the perspective route when clicked - CLAUDE.md sec 9) |

A parent link or technical key still `PROPOSED` in the matrix model (it is: `Adresse.personne` on demoBala) is dropped from every group by the desk and listed as followed.

## 2. What each button calls

Every button is a real `<button type="button">` that fills the matrix' hidden fields (`mdmmAction`, `mdmmRowId`, `mdmmRowId2`, `mdmmComment`) and the desk's group
field (`mdskGroup`) client-side, then clicks the one EBX submit button (`mdskSubmit`). `StagingDeskUserService#handle` refuses any action outside a whitelist, re-checks
the DESIGN right, and hands the action to `MappingMatrixUserService#handle` through `MappingMatrixUserService.forDesk(...)`. That handler re-checks the right, the
project and the write access (`canEdit`), then:

| Button | Action posted | Existing function that writes |
|---|---|---|
| Accept / Reject on a business object line | `ACCEPT_ENTITY` / `REJECT_ENTITY` | `ProposalReviews.decide(ENTITY, ...)` via `MappingMatrixUserService#decideOne` |
| Accept / Reject on a field, matching key, computed field | `ACCEPT_ATTRIBUTE` / `REJECT_ATTRIBUTE` | `ProposalReviews.decide(ATTRIBUTE, ...)` (`decideAttributeWithItsDerivation` carries a computed field's function) |
| Accept / Reject on a source link | `ACCEPT_FEED` / `REJECT_FEED` (asks why) | `ProposalReviews.decide(FEED, ...)` |
| Accept / Reject on a field link | `ACCEPT_MAPPING` or `ACCEPT_ATTR_THEN_MAPPING` (the matrix' `CellAcceptAction`) / `REJECT_MAPPING` (asks why) | `ProposalReviews.decide(MAPPING, ...)`, `acceptAttributeThenMapping` |
| Accept / Reject on a rule or check | `ACCEPT_GUARD` / `REJECT_GUARD` (id + nature) | `ProposalReviews.decide(<guard nature>, ...)` |
| **Accept <object>** | `BULK_ENTITY` | `MappingMatrixUserService#bulkEntity` = `sweepMappingSteps` + `MatrixCompute.planEntityBulk` + `applyPlan` (skips every flagged field; the flagged lines stay listed) |
| Set aside / **Set the N aside** | `SET_ASIDE_GUARD` / `SET_ASIDE_GUARDS` | `WizardData.setCapabilitiesAside` - the wizard card's button |
| Reject on a service that cannot run | `REJECT_GUARD` (nature `EXTERNAL_SERVICE`) | `ProposalReviews.decide(EXTERNAL_SERVICE, ...)` |
| Empty state "Fill the shared structure" | a link to the wizard's step 4 | the wizard's own `STAGING_LOAD_RUN` button (not re-implemented) |

After a decision the page is rebuilt from a fresh read and keeps the group (`DeskGroups.resolveSelection`): the same group while it still has a line, else the group
that followed it in the last render (`StagingDeskUserService#renderedKeys`), else the first, else the empty state. `setupObjectContext` runs after the event on a
postback, so `mdskGroup` is set by the script BEFORE the submit (MAPPING-MATRIX sec 13).

## 3. Entry points and registration

- Step 4 of the wizard: "Open the Validation desk" next to "Review in the Matrix" (`WizardPage.Model#proposalDoneDeskUrl`, set in `ProjectWizardUserService#model`, opens on the first business object).
- The matrix header: a "Validation desk" link in the view switch (`MatrixHtml.State#deskUrl`, set by `MappingMatrixUserService#write`, opens on the business object in view).
- Service `StagingDesk`, `OnTableView` on `/root/Project`, registered in `FactorySchemaExtensions` next to the matrix, right DESIGN (`FactoryRights`, like the matrix).
  The class is loaded once at Tomcat start (CLAUDE.md sec 9): **opening the link before the next restart answers "service not found"**, the same as the matrix did.
  The XSD is unchanged.

## 4. L2: as it will be loaded

The centre of an object group is `DeskHtml.renderObjectCentre(...)`. With a preview in `DeskHtml.State#preview` it becomes "<Object>, as it will be loaded" (mockup
`Main.dc.html`: grey = what the source holds, white = what the staging will hold); without one (the preview could not be built) it is the L1 list, unchanged.
Code: `mappingmatrix.RowTransform` (the shared transformation), `stagingdesk.DeskPreview` (the pure model and its builder), `ebx.DeskPreviewReader` (the only EBX-bound part),
`MatrixCompute#planEntityAsShown`, `MappingMatrixUserService#bulkEntityAsShown`. Tests: `DeskPreviewTest`, `DeskHtmlTest` (L2 part), `MatrixComputeTest` (the plan).

### 4.1 Where each value comes from

| On screen | Comes from |
|---|---|
| One block per source (feed) of the object | `MatrixModel.Entity#feeds`, source name from `MatrixModel.Project#sources`, landing table and row count from the feed (`Feed#landingTableName`, `Feed#rowCount` = `LandingRowCounts.rowCountOf`, the number the matrix' own flags use) |
| The 5 rows, the same for every column | `DeskPreviewReader#readLandingRows`: the landing table found like `MappingMatrixReader#sample` finds it (project landing dataspace, source landing dataset), `UserView.select(table, null)` (the user's rows, primary-key order), read forward and abandoned after `offset + 5 + 1` rows (the extra one only says "another 5" exists). Row documents: `StagingLoader#buildRowDocument`, the loader's and the sample's |
| "another 5" / "back to the first 5" | `NEXT_ROWS` (feed id + target offset), kept in `StagingDeskUserService#feedOffsets`. Pure navigation: no right is needed, nothing is written; EBX has no seek on a request, so the rows before the offset are read and dropped (the offset only grows by 5 per click) |
| The grey "source" column | The landing columns a link TRANSFORMS (a pipeline other than a constant or a look-up of the load, or accepted cleaning steps), first; when no link transforms anything, the first two columns the links read; at most two, joined by " . "; none when the source only feeds constants. Never a whole landing row: only columns a link reads |
| A produced value | `RowTransform#apply`, **the method `MappingMatrixReader#sample` now calls too** (it was extracted from it): the mapping's ACCEPTED-or-APPLIED cleaning steps (`StagingLoader#readCleaningSteps`, `CleaningSteps.scopeFieldOf`, `CleaningSteps.applyPipeline`), then the mapping's resolved pipeline through `MappingEngine.compute` on the row document. The cell keeps the loader's wording of an empty result ("(empty)") and of an engine error (the engine's message, red) |
| "computed at load" | A pipeline that `RowTransform#needsRuntimeLookup` (fk/lookup/golden/mdmstate, the matrix' own test); a matching key or computed field (`Attr#isDerived`): no sample code computes them, so the column says so on every row |
| The parent link | A column for the `Attr#parentLink` attribute whose cell is `ENGINE`: header "by the engine", chip "its <Parent>" (`Attr#parentEntityName`, `StagingAttribute.referenceEntity` resolved by `MappingMatrixReader`) |
| Technical key | Not shown |
| Column header "how" | The landing column label, or the link's pipeline (`constant('MAILING')`, `regex(...)`) when it has steps |
| Cleaning steps with a lookup | The preview passes the loader's real lookups (`QualitySuggester#buildLookups`, loaded only when a shown mapping has a cleaning step or a field has a REFERENCE rule); the matrix' sample still passes none and says "sample before cleaning steps" |
| A highlighted cell, reason (a) | `MatrixModel.Cell#attentionReason`, the matrix' own flag (`AttentionRules` and the emptied / order-unknown / conversion flags `MappingMatrixReader` adds): the whole column is highlighted, the reason is printed once, under the first row |
| A highlighted cell, reason (b) | A rule of the field (`Attr#guards`, nature QUALITY, kind PATTERN / REFERENCE / CHECK_DIGIT, severity WARN or REJECT, status PROPOSED / ACCEPTED / APPLIED) evaluated on the produced value with `QualityRuleCheck.verdict`, the evaluator the transfer engine calls (`StagingLoader`, `recordQualityCheck`). "<rule> would warn" / "would reject". An empty value never fails; a REFERENCE rule is not evaluated when the reference data was not loaded (it would fail every value); a MODEL_CONSTRAINT is a model facet, not a per-value check; a pattern the engine refuses is not a failure |
| The fill line "K of M rows have no <fields>" | `Cell#landingFillRate` (`LandingColumn.fillRate`, a percentage) with the row count: K = round(M x (100 - fill) / 100), fields with the same K grouped; nothing when the fill rate or the row count is unknown, or K is 0 |
| Fallback for a feed | A feed with a `collectionPath`, no landing table, a landing dataset that cannot be found, a failed read or an empty table: the reason (`RowTransform.REASON_*`, the very words of `MatrixModel.Sample.unavailable`) and the L1 lines of that feed (its source link and its field links) |

Personal data: the preview reads through the same scope as the matrix (`UserView.scoped`, the user's EBX permissions), on the same screen right (DESIGN), and shows the values of
the columns a link reads - the matrix' sample shows the first value of each of those columns, the desk shows 5 rows of them. Nothing is masked or logged by either; the right
decides. No landing value is written to a log line, and the rows are not kept anywhere (the service keeps only the offsets).

### 4.2 What the bar's buttons call

| Button | Action posted | Function that writes |
|---|---|---|
| **Accept <Object> as shown** | `ACCEPT_ENTITY_AS_SHOWN` (object id) | `MappingMatrixUserService#bulkEntityAsShown`: re-reads the project, asks the desk for the keys of what it shows (`DeskPreviewReader#shownKeys` = the same preview at the current offsets), `MatrixCompute#planEntityAsShown`, then `applyPlan` - the matrix' write path (`ProposalReviews#decide`, rights, project and `canEdit` re-checked by `handle`) |
| **Reject a column...** | `REJECT_ATTRIBUTE` (field id) | Unchanged from the matrix: `ProposalReviews.decide(ATTRIBUTE, REJECTED)`, whose cascade takes the field's links and checks |
| Another 5 | `NEXT_ROWS` | None (navigation) |

`planEntityAsShown(entity, ShownKeys)` (pure, `MatrixCompute`): the object when `PROPOSED`; then the fields, then the source links (feeds), then the field links (mappings) - the
cascade order of `planEntityBulk`, which the review rules require (a field or a link only once the object is accepted, a link once its field). It takes every `PROPOSED` item of a
SHOWN feed **whatever its confidence** (the person saw the result), except the links flagged by (a) or (b), which stay `PROPOSED` and stay listed (`BulkPlan#held`); a field is held
only when every one of its shown links is. Never touched: a `REJECTED` item, an already `ACCEPTED` / `APPLIED` one, a feed that fell back to the list (its result was not shown), the
technical key, the parent link, the matching keys and computed fields (decided in the list, where their function is described). **A mapping's cleaning steps are not swept**
(`BULK_ENTITY` does): the preview applied only the accepted ones, so what loads is what was shown. A runtime-lookup column ("computed at load") is taken with its field: its
header showed the pipeline, not a value. The sentence ("Accepting takes the 5 fields and 8 of the 10 links. 2 flagged links wait for you.") is computed from that same plan.

The L1 list stays under the tables, folded: "Details, one line per proposal (N)" holds the per-item table and the confidence-based "Accept <object>" bulk (`BULK_ENTITY`).

### 4.3 What a render costs

One render = the matrix read of L1 (unchanged: it still samples 20 landing rows per mapping of every entity, `MappingMatrixReader#sample`) plus, for the ONE selected object: per feed,
one request on its landing table that stops after `offset + 6` rows (never a scan) and one row document per row read; one `MappingStep` request per shown mapping; one pass over the
reference bindings only when a cleaning step or a REFERENCE rule needs them. Nothing for the other objects. `DeskPreviewReader.Cost` counts the landing requests and rows read.
An "Accept as shown" click repeats the preview read once (to know what is flagged) before writing. A preview that throws falls back to the L1 centre and does not fail the page.

### 4.4 What differs from the mockup, and what is not verified

- The yellow "point to check first" callout and its RESIDENTIAL / MAILING buttons are lot L3/L4; the flagged cells and the one-line reasons are there.
- The source column of a source with no transformation reads "HouseNumber . StreetName" (the order of the fields), where the mockup writes "StreetName . HouseNumber".
- The mockup's "Rows 1 to 5" is "Rows 1 to 5 of 180" and the link reads "another 5" / "back to the first 5" (it cycles).
- "Reject a column..." is a menu of the fields shown, upwards from the bar; it asks for no confirmation (like the list's Reject).
- Not run against a live instance: the reader was written from the sample's code and compiled, never executed; the pipeline syntax of the tests is the Connectors catalogue's
  (`regex('^([0-9]+) ',1)`, `constant('MAILING')`); the layout of the preview table in a real EBX pane (width, the upward menu inside `overflow:hidden` containers) was not looked at;
  the cost of `offset` skipping on a 1M-row table was reasoned, not measured; the flagged set is re-read at the click, so a table that changed between the render and the click can
  flag differently from what the person saw.

## 5. Not in L1 (and why)

- The mockup's right column ("Why these fields", keyboard shortcuts A / R / J / K): the reason of each line is in the list itself; the shortcuts are not wired.
- The "Use RESIDENTIAL / Keep MAILING / Another value" resolution of a point to check, the "Add a fullAddress field" action: they need the checks of L3 (the preview and "Reject a column..." are L2, sec 4).
- "Checks" shows the lines and the per-line actions; the measured "345 of 345 pass" figures of `Check.dc.html` are lot L3.
- The "followed" list uses the wizard snapshot, read once per render (the wizard's own `WizardData.readSnapshot`); on a project with many sources this is the heaviest part of the page.
- Cannot-run services of a business object that is not `ACCEPTED` are not listed (the wizard card's own scope, `StillToDecide`).

## 6. Not verified (L1)

Nothing was run against a live instance: the page was rendered from fixtures only (`DeskHtmlTest`), the service compiled but not exercised in a browser, the
perspective route rewrite and the sticky/scroll behaviour inside a real EBX pane were not looked at, and the delegation to the matrix' handler was read, not run.

## 7. Live fixes 2026-10-22 (DemoBala, RC)

Four defects seen on the screen, fixed in `fix/staging-desk-live`:

1. **Fill line** (`DeskPreview#fillLines`): a column whose pipeline starts with `constant(` (it never depends on the landing value) or needs the load (fk / lookup / golden / mdmstate) is never listed in "K of M rows have no ...". The parent link was never a DATA column.
2. **A check that cannot run is not a check.** `Guard` now carries `needsInput` (binding with `toBeProvided`) and `notReady` (`CapabilitySuggestion.ready = false`) for every capability nature (`GuardReader`); `DeskGroups#build` moves any such item out of "Checks" into "Cannot run as proposed" (once, deduplicated against the snapshot's services). **`WizardData#setCapabilitiesAside` moves `EXTERNAL_SERVICE` rows only** and skips every other nature silently, so a script check there has Reject only (posted with its own nature), no "Set aside" button, and "Set the N aside" counts and posts the services only. On the wizard card the same script check stays a normal check (Accept / Reject) - the card never looked at its binding.
3. **Reason column** (`DeskHtml#whyText`): the matrix' "goes with the Accept button" is never printed on the desk, "confidence N %, under the M % bar" only next to the confidence bulk (the folded list of an object), and "needs your decision" likewise; "needs your eye" and the field-first / parent-first reasons stay. The Checks group prints the proposer's rationale only.
4. **Header "how"** (`DeskPreview#how`): a straight copy (no step, `trim`, `trim()`) shows the landing column; a real transformation shows `<column> . <pipeline>`; a constant shows `constant(...)` alone. A default `trim()` no longer makes a column "transformed" for the grey source column.

Tests: `DeskPreviewTest`, `DeskGroupsTest`, `DeskHtmlTest`. Not run against the live instance.

## 8. L5: an input no field holds

Four useful capabilities on DemoBala (BAN, GoogleGeocoding, Nominatim, the script check `address.geocode`) cannot run: their binding says `{"toBeProvided":"the complete address as one value; ..."}` because no
accepted field holds the assembled address. The "Cannot run as proposed" group now offers to close that gap: **the model proposes a computed field, a person accepts it, the waiting suggestions are bound to it.**
Java measures and moves; it never writes the formula and never decides what an address is made of.

### 8.1 What the desk shows, and where each value comes from

Above the list of the "Cannot run as proposed" centre (`DeskInputHtml`, one call from `DeskHtml#renderCannotRunCentre`), one card per input no field holds:

| On the card | Comes from |
|---|---|
| "4 waiting for: <sentence>" and the names | `ebx.MissingInputReader`: every `CapabilitySuggestion` of nature `EXTERNAL_SERVICE` / `SCRIPT_CONSTRAINT` not rejected, superseded or set aside, whose binding still holds `toBeProvided` (the wizard card's own test, `StillToDecide.queryToWrite`); one `MissingInputs.Need` per such input (suggestion, input name, the model's sentence). Only the ones the group lists with "Needs an input no field holds" are kept. Names: the connector, or `address.geocode` for a script check |
| One card per need | `MissingInputs.group`: the same normalised sentence (lower case, blanks collapsed, final dot dropped), else the same input name. **No similarity test**: needs named differently stay apart until a proposed field lists them together (below) |
| "Propose a field for it" | posts `PROPOSE_INPUT_FIELD`, handled by the desk service (not the matrix handler): DESIGN right, project, write access re-checked, then `ebx.MissingInputRun.start` queues a background job (kind `STAGING_INPUT` (its label is short on purpose: the wizard inlines every kind label and its script is capped at 30 000 characters by `WizardScriptLoadingTest`), resource DESIGN, `FactoryJobKinds`). The end-of-task notice (`JobNotice`) follows it. While a job of that kind is queued or running for the project the button is replaced by "A proposal is being prepared..." |
| The proposed field: name, type, confidence | the `StagingAttribute` (status PROPOSED, origin LLM, derivation SCRIPT) the pass wrote, found through the `SCRIPT_DERIVATION` suggestion that carries the marker (8.2) |
| Its script: the call and its arguments | the suggestion's `targetFunction` and `binding`: `buildFullAddress(houseNumber, streetName, postalCode, city)`, then one line per argument in the matrix' words (`mappingMatrix.computed.arg.*`); the function's text is `capability.fn.<library>.<function>.description` of the bundle (the matrix' source for it); "what it computes" is the model's rationale |
| "Accept the field" / "Reject" | `ACCEPT_ATTRIBUTE` / `REJECT_ATTRIBUTE` with the attribute id: the matrix handler's existing decisions (`decideAttributeWithItsDerivation`, which also accepts the field's own derivation suggestion) |
| "Accepting binds the N waiting suggestions." | the distinct suggestions of the card that the field's marker names |

Cards of different words are merged when a proposed field lists them under one field (`DeskInput#cards`): the model, shown the whole list, is what says two needs are the same.
The proposed field also stays where computed fields already appear: the business object's group lists it as a "Computed field" with its Accept / Reject (nothing was changed there), and the matrix shows it as before.

### 8.2 How the fact reaches the model

`CapabilityLlmSuggester#suggestForMissingInputs` is the existing capability pass (`CapabilityLlmPass`, tool `suggest_script_derivation`, the existing `write`) narrowed to this one job; the
only existing pass that can propose a computed staging attribute is that tool (`docs/SCRIPT-DERIVATION.md`): a published library function applied to attributes of the entity.
`Fasttrack.lib.address.buildFullAddress(number, street, zipcode, city)` is the function a model finds for the address; Java names none.

- Only entities that have a waiting need are visited; scope `MappingScope.PROPOSAL` (the fields it assembles may still be PROPOSED). The prompt is the pass's own, **plus** `MissingInputs.promptBlock`: the measured list
  ("suggestion 695 (EXTERNAL_SERVICE BAN), input "q": <the model's sentence>"), fenced as data, and the rule: propose ONE computed field per missing input, assembled only from attributes of the entity, with a library
  function looked up with `get_library_functions`; several needs worded differently are served by ONE field, listed in `suppliesInputs`; if nothing can be built, say so (note tool) and propose nothing; no script constraint and
  no service in this run (those two tools refuse, `Session#restrictToMissingInputs`).
- The entity's accepted attributes with types and value shapes come from the pass's own entity description (`toLlmAttribute`, the LLM data policy of `docs/LLM-DATA-POLICY.md` decides what shapes are shown; this pass never shows a
  real value, so none is added for this run).
- `suggest_script_derivation` has one new optional property, `suppliesInputs` (`[{suggestionId, input}]`), required in this mode and checked against the waiting list. The link between the field and the suggestions it was
  proposed for is **a sentence at the end of the rationale of the field's `SCRIPT_DERIVATION` suggestion**: `[Supplies the input of: 695 (q); 701 (address)]` (`MissingInputs#marker`, never cut when the rationale is
  truncated). No XSD change: no field exists for it and none was added (the same facts could live in a table of their own if the marker proves too frail; the reader and the hook are the only two places that parse it).
- Not changed: the field is a PROPOSED row like every other, written by the pass's own `write`; origin LLM; measured confidence rules of `DerivationEvidence` apply only to cross-system keys.

### 8.3 Re-binding when the field is accepted

`ProposalReviews#decide` (the procedure behind every attribute accept: desk, matrix, wizard, review) calls `MissingInputRebind#afterAccept` after an ACCEPT of an attribute. For a script-computed attribute whose
suggestion carries the marker, each named suggestion gets its `toBeProvided` input rewritten to the attribute's name (`MissingInputs#rebind`) and one sentence appended to its rationale ("Input 'address' bound to the
computed field 'fullAddress' when it was accepted."). Skipped, one by one: a suggestion that is no longer PROPOSED/ACCEPTED (rejected, set aside, superseded, applied), that belongs to another business object, or whose input is
not `toBeProvided` any more (somebody settled it). A rejected field changes nothing. A suggestion with no other blocker leaves "Cannot run" at the next read (its binding no longer holds `toBeProvided`); a missing secret still
blocks it. The script check (`SCRIPT_CONSTRAINT`) is re-bound the same way as a service and is still rejected, never set aside. The accept notice says how many inputs were bound
(`desk.l5.notice.decidedBound`). The binding is still capped in confidence by the pass at suggestion time (the cap is not lifted by the re-bind).

### 8.4 What needs a restart, and what is not verified

- A restart: the desk class and the new job kind (`FactoryJobKinds` is read at module start). Nothing in the XSD.
- Not run against a live instance: the page was rendered from fixtures (`DeskInputTest`), the pass only against stubs (`MissingInputsTest`); no language model was called, so the real prompt, the model's
  `suppliesInputs`, the quality of the field it assembles, and `Fasttrack.lib.address` being published on the instance are unverified. The accept hook compiled and its source position is tested; the procedure itself
  (`MissingInputRebind`) was not executed against a repository. The end-of-task notice does not reload the desk: the person opens the group again.
- Differs from the mockup: the callout "Add a fullAddress field" of the left column is the card in the centre (the left-hand row stays the group's, with its counts); the field name is the model's, not `fullAddress` by rule.
- `DeskHtmlTest#everyKeyExistsInBothLanguages` now reads every `Desk*.java` of the package (it listed three files and its pattern refused digits, so `desk.l5.*` could not pass).

## 9. Lot L3: points to check (`DeskPointsHtml`)

**What it shows.** Above the preview of a business object, one yellow callout per attribute whose every source writes the SAME constant (DemoBala: `Adresse.addressType` is
`constant(MAILING)` from both SAP and Salesforce, while SAP's own rationale says the row is the residential address). Per source: its name, "writes MAILING into addressType",
the mapping's rationale quoted, then the decision. The "Accept <object> as shown" bar needed no change: the flagged cells are held out by L2, so it already says "N flagged links wait for you".

**Where each value comes from.**
- The fact is measured by Java, deterministically: `AttentionRules.SAME_CONSTANT` (`staging/AttentionRules#detectSameConstant`). Per (entity, attribute), the non-rejected, non-alternative
  mappings; when they come from at least two distinct feeds and every one is a single-step `constant(...)` (`AttentionRules.constantValueOf`, `MAILING`, `'MAILING'` or `"MAILING"`) with the same value, EVERY such
  mapping is flagged. The rule reads the shape of the pipeline and never a word of the value. `MappingInput` got three optional fields (feed id, pipeline, "is an alternative"); the old 7-argument constructor
  stays and never raises the flag (the Feed designer's per-feed flags therefore do not show it: it needs the whole entity).
- `ebx/MappingMatrixReader` builds those inputs for the matrix, so the matrix shows the flag like its others ("every source writes MAILING (2 sources): the field no longer tells them apart", key `mappingMatrix.attention.sameConstant`),
  holds the links out of the bulk accepts, and the desk's preview highlights the cells (L2 already shows matrix flags).
- The callout is built from the matrix model (`DeskPointsHtml#collect`, pure): the cells flagged `SAME_CONSTANT` and still `PROPOSED` (a decided link no longer waits), the rationale of the cell, the model's alternative
  (`Cell.alternative`, read from `AttributeMapping.alternativeTo`) and the Feed designer of the feed's root (a child feed climbs to its parent).

**The model proposes, a person decides.** `ebx/AnomalyLlmSuggester` (the anomaly pass of the proposal chain) now also receives the finding: the system prompt carries the rule ("a field that should distinguish rows must not receive the
same constant from every source; for each source propose the value its rationale and its data support, or say that one constant is right"); the user prompt carries, per source, the constant, the landing row count, the mapping's own rationale and the measured shape
(fill rate, distinct count, max length) of the other columns of its landing table (section `SAME CONSTANT ON EVERY SOURCE`). A second tool, `propose_constant` (source, value, rationale, confidence), writes through the pass's existing
output path: a NEW `AttributeMapping` (same attribute, feed and column as the original, `transformation NONE`, `pipeline constant(VALUE)`, origin `LLM`, `PROPOSED`, `alternativeTo` the original). The tool refuses an unknown source,
the current value, a value that would change the pipeline's shape (only letters, digits, space and `_ . / + -`, at most 60 characters) and a second answer for the same source. No Java picks RESIDENTIAL; "one constant is right" = the model calls no tool.
A source that already has a PROPOSED alternative is not sent again. The full "Propose the staging" chain gets this for free (same pass).

**Buttons and the write each goes through.**
| Button | Posted action | Write |
|---|---|---|
| Use RESIDENTIAL for SAP | `ACCEPT_ALTERNATIVE` (added to the desk's whitelist) | the matrix handler: `ProposalReviews#decide` MAPPING accepted; its `alternativeTo` cascade rejects the original |
| Keep MAILING for SAP | `ACCEPT_MAPPING` | the matrix handler: `ProposalReviews#decide` on the original mapping |
| Ask for a correction | `ASK_CORRECTION` (desk only, `StagingDeskUserService#askCorrection`) | right DESIGN + write access + entity of this project re-checked, then `AnomalyReviewRun.start` = a job of kind `STAGING_ANOMALY_REVIEW` (resource DESIGN, `FactoryJobKinds`) running `AnomalyLlmSuggester.suggest(..., entityId, sameConstantOnly=true)`; the desk writes `JobNotice` + `watch` so the end-of-task notice shows |
| Another value... | link | the existing Feed designer (`FeedDesignerLink`), no new editor |

"Ask for a correction" shows while at least one source has no proposal yet. Keeping one source's constant leaves the other source's callout row; once every source is decided the callout is gone.

**Wording fix.** "Cannot run as proposed" intro: "These services and script checks cannot run as they stand." (en) / "Ces services et contrôles par script ne peuvent pas tourner tels quels." (fr).

**Needs a restart:** yes - new Java classes (job kind registered at module start) and bundles. No XSD change (a job kind is free text; the `AttributeMapping` columns used already exist).

**Tests.** `AttentionRulesTest` (the rule, in two unrelated domains, every non-firing case), `AnomalyLlmPassTest` (prompt content, `propose_constant` checks), `DeskPointsHtmlTest` (collect, buttons, escaping, French, read-only).

**Not verified.** Nothing was run against the live instance and no model was called: the real prompt has never been answered by a model, so whether it proposes RESIDENTIAL for SAP on DemoBala is unknown. The
reader code that feeds `SAME_CONSTANT` to the matrix and the job were compiled, not executed. After "Keep MAILING" on one source the other source's alternative (if any) stays PROPOSED in the L1 list (the matrix does the same for an
undecided alternative). The page does not refresh by itself when the job ends (the notice says so; the person reloads the desk).

## 10. L4: a check shown on the values it would flag

Mockup `docs/mockups/staging-desk/Check.dc.html`. DemoBala: the proposed WARN rule `postalCode ^[0-9]{5}$` passes 287 of 335 values; the 48 others are correct postal codes of other countries
(CH 13, BE 9, SE 8, PT 6, PL 5, NL 4, IE 3), France 247 values / 0 failing. The desk now shows where a check fails and lets the person accept it with a condition the model proposed.

### 10.1 Model (needs a Tomcat restart; existing rows stay valid)

`QualityRule` (generated by `tools/build_xsd.py`, `MdmFactory.xsd` regenerated) gains three optional fields, all empty on an existing row:
- `conditionAttribute` (FK to `StagingAttribute`) + `conditionValues` (list of strings): the rule applies only to rows whose staging value of that attribute is one of them (trimmed, case-insensitive). Empty = always.
- `failureBreakdown` (JSON, 4000): `{"attribute", "groups":[{"value","seen","failing","examples":[<=5]}]}`; `{"groups":[]}` is the marker "measured, nothing to show".

A module XSD evolution: the next start adds the columns; no migration. A `MODEL_CONSTRAINT` rule can never carry a condition (the LLM pass refuses it, `WizardData#setQualityRuleSeverity` refuses to set the severity on a conditioned rule).

### 10.2 Engine

`StagingLoader#processRow`: a rule with a condition is skipped (`QualityRuleCheck.Rule#appliesTo`) BEFORE its verdict, on the condition attribute's PRODUCED staging value (`values.get("./" + field)`, not the landing text). A skipped
row is neither counted as checked nor failed, so `lastLoadChecked` / `lastLoadFailing` describe the rows the rule covers. A row whose condition field is empty is outside the condition. `readQualityRules(factory, attributeId)` (validity derivations,
match plans, the study's own measures) leaves conditioned rules OUT - they have no row to read the condition on; only the transfer's `readQualityRules(..., true)` returns them. The desk's L2 preview applies the same condition to its rows.

### 10.3 The measured fact (Java measures, the model decides)

`ebx/FailureBreakdowns#joints` reads, per column of the entity, the joint distinct counts with each OTHER attribute mapped from the same landing table (`QualitySuggester#distinctValues` with the grouping column as the scope field: same cache,
same bounds, whole table under `mdm.factory.profile.maxRowsFullScan`), for at most 8 candidates with at most 30 distinct values, never an attribute the instance's `LlmDataPolicy` calls sensitive. `quality/FailureBreakdown` (pure) groups the
failing values by each candidate and keeps the one that best separates them (fewest rows a group majority does not explain, strictly better than treating the column as one group). It is stored on the rule (`failureBreakdown`) by the quality
LLM pass, for the rules it proposes and for the open rules of an entity that have none, and by the "Measure" job (`RuleBreakdownRun`, kind `QUALITY_BREAKDOWN`, resource DESIGN) for one rule.
Examples follow the rule of `QualityRule.examples`: the value only when the project shares real values and the column is not sensitive, else its shape, else none.

### 10.4 Reaching the model

`QualityLlmPass`: the `measure` result and the existing rules listed under a column carry the breakdown ("countryCode=CH: 13/13 fail; ... no failure for countryCode in FR (247)"); the system prompt says: if the failures are concentrated in some
values of another field, propose the condition under which the rule is valid (`propose_quality_rule` with `conditionAttribute` and `conditionValues`), or a pattern that covers the failing shapes, never a warning that fires on correct data. Without real values
shared (`SHAPES_ONLY`/`NONE`) only the concentration is stated, so no condition can be named. The answer is validated: the attribute must be one measured against the column, every value one actually measured in a group of it, severity WARN, kind
PATTERN/REFERENCE/CHECK_DIGIT. The rule is then judged on the rows the condition covers (a pattern that fails 75 % overall is accepted under the condition that makes it clean). A condition on a rule that already exists (PROPOSED, unconditional) updates that row.
Written PROPOSED; no Java picks a country.

### 10.5 Desk

"Checks" group: each rule line has "Look at it" (`mdskCheck(id)`, hidden field `mdskCheck`, read back by `setupObjectContext`). `DeskCheckHtml` (data: `DeskCheck`, read by `ebx/DeskCheckReader`): title in words, the figures (pass / would be flagged;
the "empty" count of the mockup is not stored on the rule and is not shown), the table by group, three cards, "<- Validation desk" back.
- Accept with the condition (only when the rule carries one; says how many values the condition covers and how many it flags today, from the breakdown's groups): `ACCEPT_GUARD` (the condition is part of the row).
- Accept as proposed (says how many values it would flag): `ACCEPT_GUARD`, or, when the rule carries a condition, the new `ACCEPT_GUARD_AS_PROPOSED` of the matrix handler: `DeskCheckWrites#clearCondition` (PROPOSED rule of this project only) then the same decision.
- Reject: `REJECT_GUARD`.
- Never measured: "Measure" posts `MEASURE_CHECK` (desk service: write access, rule of the project) which queues `RuleBreakdownRun`; the card shows "Measuring..." while the job is active. A rule measured with nothing to show says so.
- A rule that passes everything is "Already safe" with Accept and Reject.

### 10.6 Not verified / differs from the mockup

Not run against the live instance (no Tomcat, no model call): the SQL joint reads (`QualitySuggester#distinctValues` with a scope field) were read, not executed; the model's use of the breakdown was never seen. The breakdown is measured on LANDING
values and the condition is matched on STAGING values: a grouping attribute whose mapping cleans the value (a country name turned into ISO) shows the landing spelling in the table, so the proposed values may not match the staged ones (the
L2 preview shows the staged truth). No per-source counts and no "format of that country" column (the mockup's last two); the total only. The feeds' mappings of the condition attribute must exist in the same feed as the checked field.
Installing the XSD needs a Tomcat restart.

## 11. Live fixes, round 2 (DemoBala, RC, 2026-10-22)

1. **One "Cannot run as proposed" input card per business object** (`DeskInput#cards`). The sentences of the four DemoBala suggestions (`address.geocode`, BAN, GoogleGeocoding, Nominatim) differ, so no Java similarity test merges them; the product rule is that the MODEL says which needs are the same. Before a field is proposed, all waiting needs of one object are ONE card ("4 wait for an input no field holds", `desk.l5.titleMixed.*`, each suggestion listed with its own sentence) with ONE "Propose a field for it" button. Once fields exist, cards follow the fields (needs a field supplies, or that share a field, form one card) and the needs no field covers stay in one remaining card of their object. A card whose needs all carry the same sentence keeps the older title and chips. `MissingInputs.group` is unchanged (still literal).
2. **The older "points to check" list no longer repeats the L3 callout** (`DeskHtml#renderObjectCentre`, `DeskModel.Line#sameConstant` set in `DeskGroups#line`). A SAME_CONSTANT flag is shown only by its callout (when the object has one for that attribute); the older list keeps the other flags and disappears when empty. The preview's cell highlight is untouched.
3. **Sources named by their system** (`DeskSourceNames`, used by `DeskGroups`, `DeskPointsHtml`, `DeskPreview`, hence the preview header, callout lines and the "Use X for SAP" / "Keep X for SAP" buttons). Measured in the data: the DemoBala project sources carry `sourceSystem` = `PERSONNESSAPSAMPLE`, the landing dataset name, and the matrix's own source cards print that same value; "SAP" exists only in the landing table name `Personnes_SAP_Sample`. When a source's system merely repeats its landing table name, the desk prints the words of the table name that no other source's table shares (`SAP`, `Salesforce`); otherwise the system name as is. The landing table stays the secondary chip. L4 does not name sources. The matrix itself still prints the raw system.

Not verified live: all three need an install + restart to be seen.

## 12. Model answers, 2026-10-22 (DemoBala, RC)

Two live defects with one cause: the desk asked the model something, the model answered, and the desk behaved as if nothing had happened. No XSD change.

### 12.1 "Measure" also asks the model for a condition (lot L4b)

`RuleBreakdownRun` (job kind `QUALITY_BREAKDOWN`) used to stop at the stored breakdown, so on QualityRule 530 (`postalCode ^[0-9]{5}$`, failing CH 13, BE 9, SE 8, PT 6, PL 5, NL 4, IE 3, grouped by `countryCode`) only "Accept as proposed" / "Reject" were offered. After the breakdown is stored the SAME job now runs the quality pass **narrowed to that rule**: `QualityLlmSuggester#suggestForRule` (an entity filter, scope `PROPOSAL`, plus a `quality.RuleFocus`). No second prompt:
- `QualityLlmPass#userPrompt(project, focus)` is the ordinary prompt for the rule's column (profile, shapes, the existing rule with its breakdown, the system prompt's rule about conditions) plus `RuleFocus#promptBlock` ("this run is narrowed to ...: propose that rule with a condition, or call note once if none applies"). Only the rule's own column is listed; the other attributes of the object still serve as grouping candidates (`FailureBreakdowns#joints` reads the whole object).
- `QualityLlmPass.Session#restrictTo(focus)`: `propose_mapping_step` is refused, `propose_quality_rule` is refused unless it is that rule (kind + argument) WITH `conditionAttribute`. The condition itself is validated by the pass as before (`readCondition`: measured attribute, values measured in a group, WARN, PATTERN/REFERENCE/CHECK_DIGIT) and written on the existing PROPOSED row (`updatesExisting`). Java picks nothing.
- The project's `PipelineStep` of the quality pass is not opened for a narrowed run.
- The model is not asked (the breakdown stays) when `RuleFocus.whyNotAsked` says so: data policy NONE or SHAPES_ONLY (the prompt hides the values of the other field, so no condition can be named), a sensitive column, no connection or any failure of the run (the reason is the engine's message).
- End message (`RuleBreakdownRun#endMessage`, keys `desk.l4b.job.*`): "Measured: failures broken down by countryCode. The model proposes the condition countryCode in FR." / "... The model proposes no condition[: its note]." / "... The model was not asked: <why>."
- `DeskCheckHtml`: the chip "Measuring and asking the model..." is also shown while the job runs on a rule whose breakdown is already stored; when the job ends and the desk is reopened, the "Accept with the condition" card appears as it already did for a rule that carries one.

### 12.2 "Ask for a correction" disappears once the model has answered (lot L3b)

After the ask on Adresse.addressType the model proposed `constant(RESIDENTIAL)` for SAP and nothing for Salesforce (it judged MAILING right); the callout kept offering the ask. Now the answer is remembered, per mapping, on the job row itself:
- `AnomalyLlmSuggester#suggest` lists, per entity whose agent run ended normally, the SAME_CONSTANT mappings it submitted (`constants[].sources[].mappingId`) and the ones it answered with a `propose_constant`; `Result.answers` = `staging.ModelAnswers#answers` (proposed, or kept with the model's closing words, one line, 240 characters).
- `AnomalyReviewRun.HANDLER` writes them into the job's `params` (`"answers":[{"m":"5780","a":"kept","n":"..."}]`, `FactoryJobQueue#replaceParams`, the id from `FactoryJobQueue#currentJobId`, a thread-local set around the handler). `ModelAnswers#withAnswers` keeps the 4000-character column safe (notes dropped, then the list). `FactoryJobStatus` only reads `detail`, never these params for a DONE job.
- `AnomalyReviewRun#answersOf` reads them back (latest DONE job of the kind whose params name the object); `StagingDeskUserService` passes them to `DeskPointsHtml#collect(project, entity, answers)`.
- Callout: a kept source shows "The model keeps MAILING for Salesforce." (+ the note quoted) and keeps "Keep MAILING for Salesforce" and "Another value...". `Point#needsAsk` = `ModelAnswers#needsAsk`: a source with no alternative whose mapping id is not in the answers (never submitted, or a newer mapping) brings "Ask for a correction" back.

### 12.3 Tests, restart, not verified

`ModelAnswersTest`, `RuleFocusTest`, `RuleBreakdownEndMessageTest`, `QualityLlmPassTest#aRunNarrowedToOneRuleAsksOnlyForItsCondition`, `DeskPointsHtmlTest#aSourceTheModelKeptIsSaidAndNoLongerAsked`. A restart is needed for the Java; nothing in the XSD.
Not verified: no model was called and nothing ran on the instance. The narrowed prompt has never been answered by a real model (whether it names `countryCode in FR` on DemoBala is unknown), `suggestForRule` and the params write-back were compiled, not executed, and the answers of jobs that ran before this change do not exist (the desk then offers the ask once more, which records them). The "kept" note is the model's closing summary of the whole run for the object, not a per-source sentence.
