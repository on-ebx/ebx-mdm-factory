# Mapping matrix

Written 2026-09-23 on branch `feature/mapping-matrix` (worktree `ebx-mdm-factory-mapping-matrix`), from the
task brief asking for a graphical, whole-project review of the landing -> staging mapping (docs/TARGET-MAPPING.md),
modelled on the reference mockup `docs/mockups/mapping-matrix-5-sources.dc.html`. Extended the same day, same
branch, after a coordinator review found the screen could only ever decide `MAPPING` rows - on project Personnes
every entity/attribute/feed is still `PROPOSED`, so `review.ReviewRules#canAcceptAttributeMapping`/
`#canAcceptStagingAttribute` refused every Accept and every bulk sweep. Section 4 and section 9 (new) describe
what changed. Nothing in this document was run live in EBX; every claim was verified by compiling and running
the module's offline test suite (`tools/test_local.sh`), never by opening the page in a browser (see "What was
never run" at the end).

## 1. What the screen shows, and where each value comes from

The service is `ebx.ui.MappingMatrixUserService` (registered `MappingMatrix`, declared `OnTableView` on
`/root/Project` exactly like `FeedDesigner`/`ProjectWizard`). It shows ONE project at a time; the project code
and an optional entity id travel as `serviceParams` (base64 JSON `{"project":"<code>","entity":"<id>"}`),
resolved by `ebx.ui.MappingMatrixUserService#setupObjectContext` the same way `FeedDesignerUserService`
resolves its own `feed` parameter.

The only class that touches EBX is `ebx.MappingMatrixReader#read`: it reads `ProjectSource`, `LandingTable`,
`LandingColumn`, `StagingEntity`, `StagingAttribute`, `TransferFeed` and `AttributeMapping` of one project and
turns them into a plain `mappingmatrix.MatrixModel.Project` - no EBX type past that point. Everything else
(`mappingmatrix.MatrixCompute`, `mappingmatrix.MatrixHtml`) is pure and unit-tested without a live repository,
the split this module already keeps between its `ebx.*` readers and its pure page/model classes
(`wizard.WizardPage`, `review.ReviewPage`, ...).

| Screen element | Source |
|---|---|
| Source cards (overview, left) | `ProjectSource` of the project, one per row; colour = a positional palette assignment (the module's own copy of `lineage.SourceColorPalette`'s 12 hues - see "Simplifications" below) |
| Entity cards (overview, right) | `StagingEntity` of the project, excluding `REJECTED`/`SUPERSEDED` (same "hide what nobody acts on" rule the wizard's staging-focus lot already applies) |
| Curves (overview) | one per (source, entity) pair that has at least one feed; colour = the source's colour, dashed violet when "key-linked" (see below); the chip text is the aggregated stats of every feed of that source under that entity (`MatrixCompute.statsBySource`) |
| Entity tabs, toolbar counters | `MatrixCompute.entityStats` (sum of every feed's `MatrixCompute.feedStats` over the active entity's attributes, PLUS the entity's own and every non-key attribute's own pending decision - section 9.4) |
| Matrix rows | `StagingAttribute` of the active entity, excluding `REJECTED` and the technical `sourceSystem` primary key; the `sourceId` primary key IS shown, as the "source key" row the task brief asked for. A PROPOSED, non-key row also gets its own Accept/Reject buttons (section 9.1) |
| Matrix columns | `TransferFeed` of the active entity, excluding `REJECTED`; header shows source tag, landing table name, arrival mode (see below), a stacked accepted/proposed/rejected bar, unused-column count, a PROPOSED feed's own Accept/Reject buttons (Reject asks for an optional comment, section 9.1), a rejected feed's own comment as a tooltip, and a per-feed "Accept N (>= T%)" button (section 9.3) |
| A cell | the `AttributeMapping` row(s) of that (attribute, feed) pair - first mapping shown, `+N` when more than one targets the same attribute in the same feed |
| Sample "before -> after" | `MappingMatrixReader#sample`, computed only for the currently open entity's feeds (never for every entity on every render) - see section 3 |
| Inspector | filled entirely client-side from the cell's own `data-cell` JSON attribute (`MatrixHtml.cellJson`) - no round trip for mere selection |

## 2. Cell status vocabulary

Seven values (`mappingmatrix.MatrixModel`), the same the reference mockup uses:

- `ACCEPTED` / `PROPOSED` / `REJECTED` - an ordinary `AttributeMapping` decision, read as-is.
- `KEY` - the synthetic "source key" row only (the entity's `sourceId`): never backed by a real `AttributeMapping`
  row, its landing column is the feed's `sourceIdPipeline` when set, else "(_sourceId)".
- `ENGINE` - a child feed's own link-to-parent attribute (`StagingAttribute.referenceEntity` pointing at the
  parent, the field `TransferFeed.parentLinkField` names): written by `StagingLoader` itself at load time, never
  by a mapping row, so this cell is synthesized rather than looked up.
- `NONE` - the source provides nothing for this (optional) attribute in this feed. Fine: another feed may.
- `MISSING` - a MANDATORY attribute this feed does not cover at all, or covers only with a `REJECTED` mapping:
  every row from this feed will fail validation in staging.

**"Key-linked" (the mockup's violet dashed curve)** is not a cell status - it is a FEED-level fact
(`MatrixModel.Feed#keyLinked`): a ROOT feed (never a child - see below) whose entity has a `referenceEntity`
attribute with a real `AttributeMapping` row IN THAT FEED. The ordinary FK-by-source-key case
(`docs/TARGET-MAPPING.md` sec 2.1's "the two-table source is a ROOT feed with an ordinary attribute mapping,
never a child feed") reads exactly like this from the data: a child feed's landing table is ALWAYS the same as
its parent's (`propose_feed`'s own refusal, sec 4.2), so a cross-table link can only ever be a root feed's
ordinary mapping. That cell itself renders with its own ordinary ACCEPTED/PROPOSED/REJECTED tone; only the
column header ("linked by key") and the overview curve (dashed violet) call it out.

## 3. Sample before -> after

`MappingMatrixReader#sample` reads at most 20 rows of the feed's own landing table (same dataspace/dataset
resolution `ebx.StagingLoader#loadRootFeed` uses: `Project.landingDataspace` + `ProjectSource.landingDataset`),
picks the first row whose column is non-empty, builds the row's document with `ebx.StagingLoader#buildRowDocument`
itself (that method was made package-private for exactly this reuse - coordinator review, 2026-09-23: "do not
duplicate StagingLoader.buildRowDocument" - there is now only ever ONE implementation of it), applies this
mapping's own ACCEPTED-or-APPLIED cleaning steps (`ebx.StagingLoader#readCleaningSteps` + `quality.CleaningSteps
.applyPipeline`, also reused rather than reimplemented) BEFORE the pipeline - exactly the order
`StagingLoader#processRow` itself uses (task D) - and only then runs it through the SAME engine `StagingLoader`
uses at transfer time: `com.on.ebx.connect.core.MappingEngine.compute` with a one-rule `MappingDefinition`
(`sourcePath` = the column's field name, `targetPath` = `./sample`, the mapping's own resolved pipeline, policy
`ALWAYS`). It never writes anything, and passes a `null` staging dataset to `TransformationContext` - safe for
every pipeline this method attempts, because:

- a pipeline naming `fk(`/`lookup(`/`golden(`/`mdmstate(` (the four transformations that need a live EBX lookup
  at run time) is refused BEFORE the engine is even called - the cell shows "no sample: pipeline needs a live
  lookup (fk/lookup/golden/mdmstate)";
- **a CHILD feed whose `TransferFeed.collectionPath` is non-empty answers "no sample for collection feeds"
  outright** (coordinator review: "either take the first element of the collection ... or return an explicit
  'no sample', check which"). Checked in `ebx.StagingLoader#computeChildren`: the real loader processes EVERY
  element of the collection, one child staging row per element - there is no single "the" element to read a
  sample from, and the landing COLUMNS a collection element carries are nested under that JSON path, not at the
  row's own top level `buildRowDocument` reads. Faking "the first element" would need a second JSON-path reader
  this lot does not have, so it refuses cleanly instead of guessing. A CHILD feed with an EMPTY `collectionPath`
  (the flat "same row" case) is unaffected - its document already IS the parent row, exactly what
  `buildRowDocument` reads;
- **a cleaning step that needs a reference lookup this method cannot honestly provide** (`COUNTRY_TO_ISO2`/
  `CURRENCY_TO_ISO`/`TRANSCODE_REFERENCE`, run here with `CleaningSteps.Lookups.empty()`) is detected through
  `CleaningSteps.Result#unresolved` - rather than presenting a value as "cleaned" when it was really left
  untouched for lack of a lookup table, the pipeline runs on the RAW landing value instead and
  `MatrixModel.Sample#note` says "sample before cleaning steps: a cleaning step needs a reference lookup this
  screen cannot provide" - never a silently wrong "after";
- an empty/missing landing table, a table with no non-empty value in the first 20 rows, or any exception the
  engine itself throws, degrades to `MatrixModel.Sample.unavailable(reason)` - a single failing sample NEVER
  breaks the rest of the page (every call site is wrapped in `try { ... } catch (Exception ex)`).

Samples are computed ONLY for the entity the caller is currently viewing (`MappingMatrixReader.read`'s
`pSampleForEntityId` parameter) - never for every entity of the project on every render, which would multiply
the landing-table reads by the number of entities for no benefit.

## 4. Decisions

**Five decidable natures, all through `ebx.ProposalReviews#decide` - the SAME path the wizard's own inline
accept/reject and the Proposal review screen use, so `review.ReviewRules`/`staging.MappingFollowRule` and every
permission check apply identically:**

| Nature | Where | Reject comment? |
|---|---|---|
| `ENTITY` | Toolbar Accept/Reject, shown only while the active entity is `PROPOSED` | No - the task brief: "no comment field there, just decide" |
| `ATTRIBUTE` | Row header Accept/Reject, shown only for a `PROPOSED`, non-`sourceKey` attribute | No |
| `FEED` | Column header Accept/Reject, shown only for a `PROPOSED` feed | Yes - `window.prompt`, stored in `TransferFeed.reviewComment` |
| `MAPPING` | The inspector's own Accept/Reject of the selected cell | Yes - `window.prompt`, stored in `AttributeMapping.reviewComment` |
| `MAPPING` (two-step) | A cell whose attribute is not accepted-like yet: "Accept the attribute and this mapping" decides the `ATTRIBUTE` first, then the `MAPPING` - never attempted when the ENTITY itself is not accepted-like (section 9.1) | No (the attribute step never carries one) |

The page posts through ONE shared hidden submit button (`mdmmSubmit`) driven by hidden fields
`mdmmAction`/`mdmmRowId`/`mdmmRowId2`/`mdmmComment` (`mdmmRowId2` added for the two-step cell action, which
needs both the attribute id and the mapping id) - the exact `data-mdmf-action` convention
`ebx.ui.ProjectWizardUserService` already uses (CLAUDE.md &sect;9: one real EBX form on the page). A bulk action
(`BULK_FEED`/`BULK_ENTITY`) re-reads the entity FRESH from EBX and recomputes the plan with
`mappingmatrix.MatrixCompute#planEntityBulk`/`#planFeedBulk` at decision time - it never trusts what the page
last rendered, the same "never trust what was rendered before this submit" rule `ProposalReviews#decide` itself
follows. Section 9 describes what changed on top of the very first cut of this screen.

`ebx.ProposalReviews#decide` was extended (not rewritten) for the new `AttributeMapping.reviewComment` /
`TransferFeed.reviewComment` fields (see section 6): a `MAPPING`/`FEED` decision now also writes the comment on
`REJECTED` (blank comment = write nothing extra, same rule the existing `MatchPlan` reviewComment already
follows) and CLEARS it on `ACCEPTED` (so a stale rejection reason never lingers once the row is later accepted).
The two pure predicates behind this (`ProposalReviews.reviewCommentApplies`/`reviewCommentField`) are
unit-tested directly (`ProposalReviewsCommentTest`) without any EBX runtime; the write itself (inside
`decideInProcedure`) needs a live session and was only compiled and read carefully, never exercised against a
running instance.

## 5. What is genuinely client-side, and what is a postback (simplifications against the mockup)

The task brief called out ONE interaction explicitly - "selection is client side ... no postback for
selecting" - and that is exactly what is implemented: clicking a cell reads its own `data-cell` JSON attribute
and fills the inspector's `innerHTML` with plain vanilla JS (`mdmmSelect`, `mappingmatrix.MatrixHtml#renderScript`),
no round trip. The "only what's left to do" toggle and the source-focus highlight are also pure client-side CSS
class toggles.

Two things the mockup does purely client-side (it has no server at all) are REAL postbacks here, because every
decision must run a validated, audited EBX write:

- **Switching the active entity tab** is a postback (`GOTO_ENTITY`), not a client-side tab switch. The
  alternative (rendering every entity's full matrix into the DOM at once, switched by CSS) was considered and
  rejected for this lot: it would multiply the page's size and the sample-computation cost by the number of
  entities on every single render, for a benefit (avoiding one round trip on a tab click) that seemed marginal
  against that cost. The overview graphic itself IS computed for every entity on every render (cheap: only
  counters, no samples), so the "which entity needs attention" view stays live without switching tabs.
- **Rejecting a mapping's or a feed's optional comment** is collected with the browser's native
  `window.prompt(...)` rather than a small inline textarea styled like the mockup's inspector. A dedicated
  textarea (matching the mockup exactly) was not built in this lot for time - `mdmmReject`/`mdmmRejectFeed` in
  `MatrixHtml#renderScript` are the two places to change if this is revisited. An entity/attribute Reject asks
  for nothing (section 4: no comment field on those natures).

Other simplifications, all named in `ebx.MappingMatrixReader`'s own class Javadoc too:

- Only `ACCEPTED`/`PROPOSED`/`APPLIED` `StagingEntity`/`TransferFeed` rows are shown (a `REJECTED`/`SUPERSEDED`
  one is dead, same rule the wizard's staging-focus lot already applies to `SUPERSEDED` entities).
- "Unused landing columns" only subtracts columns actually referenced as a mapping's OWN `landingColumn` -
  a column referenced only inside a `concat(...)` elsewhere in a pipeline is not detected as "used" and may be
  listed as unused. Parsing every pipeline's `concat()` arguments was judged not worth the complexity for this
  lot; the count is therefore an upper bound, not exact.
- The source colour palette is the module's OWN copy of `lineage.SourceColorPalette`'s 12 hues
  (`MappingMatrixReader.PALETTE`), not a call into that class - `SourceColorPalette` and its methods are
  package-private to `com.on.ebx.factory.lineage`, and widening that visibility (with its own test coverage)
  was out of scope for this lot. Both classes assign colours by the same "position in the project's own source
  order" rule, so a source keeps the same colour on both screens in practice, but nothing enforces that they
  never drift apart if one list's own array is ever edited independently of the other's.
- The Proposal review screen was NOT extended with a "Mapping matrix" link for FEED/MAPPING lines (the task
  brief's own "if easy" qualifier) - `review.ReviewPage`/`ebx.ui.ProposalReviewUserService` were read but not
  touched, for time; the wizard's step 4 entry point (section 7) was judged the higher-value one to build first.

## 6. Data model change: `reviewComment`

`tools/build_xsd.py`: `AttributeMapping` and `TransferFeed` each gained an optional `reviewComment` field
(`osd:text`, maxLen 2000), same shape and the exact same labels convention (en-US "Review comment" / fr-FR
"Commentaire de revue") as the existing `MatchPlan.reviewComment`. `MdmFactory.xsd` was regenerated
(`python3 tools/build_xsd.py`) - never hand-edited. `core.FactoryPaths.AttributeMapping.REVIEW_COMMENT` /
`TransferFeed.REVIEW_COMMENT` were added alongside every other field path of those two tables.

`ProjectDeleter`/`StepResetter` (checked, not modified): neither copies or special-cases individual fields of
`AttributeMapping`/`TransferFeed` - both delete/reset whole rows by predicate, so the new field needed no
change there.

## 7. Entry points

- **Wizard step 4**: a "Mapping matrix" button next to the existing "Designer" button on every ROOT feed's own
  header (`wizard.WizardPage#renderFeed`), opening the matrix on that feed's own entity. The URL is built in
  `ebx.ui.ProjectWizardUserService#model` (the same place `designerUrl` is built) and carried on the pure
  `WizardSnapshot.FeedInfo.matrixUrl` field, rendered by `WizardPage` - the same "computed in the EBX-bound
  service, copied onto the plain snapshot object the pure page renders" pattern `designerUrl` already
  established.
- **Proposal review screen**: not done (see "Simplifications" above).

## 8. Deployment - what needs a Tomcat restart

- **The XSD change (`reviewComment` on two tables) needs a restart to be picked up outside `ebx-manager`'s own
  development-mode UI navigation reload** (CLAUDE.md &sect;9's own "backend.mode=development reload is much
  narrower than it sounds" - and this deployment is going through a full `install.sh`+restart anyway per the
  MDM Factory's own deployment convention, never a live-reload-only path).
- **The new `MappingMatrix` user service class is loaded once at Tomcat startup** (`FactorySchemaExtensions`
  registers it, but the CLASS itself, like every other custom `UserService` of this module, is only picked up
  by a full restart - CLAUDE.md &sect;9's own "a Java class already loaded by Tomcat never reloads without a
  full restart"). Opening the "Mapping matrix" link before that restart will answer with EBX's own "service not
  found" for the `MappingMatrix` service key.
- No new jar dependency: `ebx.MappingMatrixReader` reuses the SAME `com.on.ebx.connect.core.*` classes
  (`MappingEngine`, `MappingDefinition`, `MappingRule`, `TransformationContext`) `ebx.StagingLoader` already
  imports from `on-ebx-mapping.jar` (already wired via `tools/mapping_jar.sh`/`ON_EBX_MAPPING_JAR` - no new
  `tools/*.sh` script, no new `.classpath`/`WEB-INF/lib` entry needed beyond what lot 1 of target-mapping
  already put there).

## 9. Coordinator review fixes (2026-09-23, same branch)

The first cut of this screen only ever decided `MAPPING` rows - on project Personnes every `StagingEntity`/
`StagingAttribute`/`TransferFeed` is still `PROPOSED`, so `review.ReviewRules#canAcceptAttributeMapping` (needs
the attribute accepted-like) and, one level up, `#canAcceptStagingAttribute` (needs the ENTITY accepted-like)
refused every single Accept, and every bulk sweep would only ever have produced refusals. This section covers
what changed to fix it; section 4 above already reflects the end state.

### 9.1 The missing decisions and the cell's own three-way accept action

`mappingmatrix.MatrixCompute` gained three pure visibility predicates - `canDecideEntity`/`canDecideAttribute`/
`canDecideFeed` (all: is the row `PROPOSED`? `canDecideAttribute` additionally: is it NOT the technical
`sourceKey` row, which follows its entity and never gets a decision of its own) - `MatrixHtml` uses them to
render (or not render) the toolbar's, the row header's and the column header's own Accept/Reject buttons.

The cell's own accept is the more subtle one: `MatrixCompute#cellAcceptAction(entityStatus, attributeStatus)`
returns one of three values, mirroring `review.ReviewRules`'s own chain exactly (mapping needs its attribute
accepted-like; attribute needs its entity accepted-like) so the button never promises something the server will
then refuse:

- `ENTITY_NOT_ACCEPTED` - the entity itself is not accepted-like: the inspector shows "Accept the entity first
  (toolbar above)" and offers NO accept button at all - **never a silent cascade to the entity**, exactly the
  task brief's own wording. Reject stays available regardless (`review.ReviewRules` never gates REJECT on the
  parent's status - only ACCEPT).
- `ACCEPT_ATTRIBUTE_THEN_MAPPING` - the entity is accepted-like but the attribute is not: the button reads
  "Accept the attribute and this mapping" and posts `ACCEPT_ATTR_THEN_MAPPING` with BOTH ids (`mdmmRowId` =
  attribute id, `mdmmRowId2` = mapping id, the reason `mdmmRowId2` exists at all).
  `ebx.ui.MappingMatrixUserService#acceptAttributeThenMapping` decides the attribute first and only attempts the
  mapping when that succeeded, surfacing whichever step's own refusal reason if either fails.
  - `ACCEPT_MAPPING_ONLY` - both accepted-like already: the ordinary single Accept button.

This is embedded per-cell in `data-cell` (new fields `attrId`/`attrStatus`/`entityStatus`), so the inspector
picks the right button/label entirely client-side, no round trip just to find out which one to show - the JS
function `mdmmSelect` mirrors `cellAcceptAction` with the same three branches
(`MatrixHtml#renderScript`).

### 9.2 `handle()` re-checks write access on every decision

`ebx.ui.MappingMatrixUserService#handle` now calls `canEdit()` again, right after resolving the posted action
(but AFTER the pure `GOTO_ENTITY` navigation, which needs no write access) and BEFORE acting on any decision -
`fail()`s with a message (`mappingMatrix.notice.readOnly`) rather than silently doing nothing or trusting that
the buttons the page last rendered were actually disabled for this session. A hand-crafted request (or a
session that lost write access between two renders) can no longer bypass the read-only gate just because an
earlier render happened to show enabled buttons.

### 9.3 Bulk sweeps: the same cascade order and eligibility rule as the wizard, extended with feeds

`MatrixCompute` gained a pure planner - `planEntityBulk`/`planFeedBulk`, returning a `BulkPlan` (an ordered
`List<PlannedDecision>` plus a `leftOut` count and the lowest left-out confidence) - built on the exact SAME
`wizard.WizardRules#bulkEligibility` rule `ebx.ui.ProjectWizardUserService#bulkStaging` uses for the rows it
already knows (entity, then non-key attributes, then mappings): `planEntityBulk` cascades **entity, then
non-key attributes, then FEEDS (new - `bulkStaging` never touches `TransferFeed` at all), then mappings**,
exactly the order the task brief specified. `planFeedBulk` is narrower: the feed itself, then only the
attributes ITS OWN mappings point to (never a sibling feed's attribute), then its own mappings.
`ebx.ui.ProjectWizardUserService#bulkStaging` itself was NOT touched - extracting a single shared helper across
both would have meant either adding a feed loop to the wizard's own sweep (an actual behaviour change, forbidden
by the task brief: "do not change the wizard's behaviour") or leaving the wizard on a different, narrower
helper than the matrix - so the two share only what was already shared before this lot
(`WizardRules#bulkEligibility` and `ProposalReviews#decide`), and the cascade/ordering logic itself is this
screen's own, new, pure code.

`ebx.ui.MappingMatrixUserService#applyPlan` decides every planned row through `ProposalReviews#decide` and
reports `{ok} accepted, {skipped} skipped` or, when the plan left rows out below the threshold, `{ok} accepted,
{skipped} skipped, {leftOut} left out below the threshold (lowest confidence {conf}%)` - never just a bare
accepted count. The bulk buttons' own displayed count (toolbar "Accept the N proposals" / column header "Accept
N") now reads `BulkPlan.toDecide.size()` too, so the number on the button always matches what a click would
actually attempt.

### 9.4 Counters count an undecided entity/attribute/feed as "to validate" too

Coordinator's own example: "a feed PROPOSED with all mappings accepted is not 'all validated'". `MatrixCompute
#feedStats` now adds one to its own `proposed` count when the FEED's own `status` is `PROPOSED` (independent of
its mappings' statuses); `#entityStats` adds one more for the ENTITY's own pending decision and one per
non-key ATTRIBUTE still `PROPOSED`. Because `#statsBySource` and the toolbar/overview both build on
`#feedStats`/`#entityStats`, this propagates everywhere those counts are shown (the per-source overview chip,
the toolbar's "to validate" chip, the entity card's own status text) without a second, parallel counting path.
The per-feed column header's own stacked accepted/proposed/rejected BAR is unaffected on purpose - it visualises
the feed's MAPPINGS specifically, not the feed's own decision (which now has its own Accept/Reject buttons right
there in the same header, section 9.1).

### 9.5 Tests added

`MatrixComputeTest`: `buttonVisibilityFollowsStatus`, `cellAcceptActionNeverCascadesToTheEntitySilently`,
`entityBulkCascadesInOrderAndSkipsTheTechnicalKey`, `entityBulkLeavesLowConfidenceRowsOut`,
`feedBulkOnlyTouchesItsOwnFeedAndAttributes`, `countersIncludeUndecidedStructuralRows`. `MatrixHtmlTest`:
`rendersEntityAcceptRejectOnlyWhenProposed`, `rendersFeedRejectedCommentAndHidesActionsOnceDecided`. All pure,
no EBX runtime - `handle()`'s own re-check of `canEdit()` (9.2) and the live sample computation (section 3) are
EBX-bound and were verified only by compiling and reading the code carefully, same as the rest of this feature
(see "What was never run" below).

## 10. Live-deployment fixes (2026-09-24, branch `fix/mapping-matrix-live`, off `main` 1e63021)

The screen was deployed (`main` 2007440) and clicked through live by the product owner. Every reported symptom traced
back to one root cause plus three smaller, independent issues.

### 10.1 The root cause: the script never ran at all

`MatrixHtml` wrote its interaction code as a hand-authored `<script>(function(){...})();</script>` tag inside
the pane's own HTML, appended with `UserServicePaneWriter#add`. **EBX does not execute a script added that
way** (CLAUDE.md &sect;9: "JavaScript goes through `addJS(String)`, not a hand-written `<script>` tag") - this
alone explains every interaction symptom reported: the "Adresse" entity card did nothing (its `onclick`
handler called a `window.mdmmOpenEntity` that was never defined), the inspector never filled (`mdmmSelect`
likewise undefined), and so did source-focus, the only-todo toggle, and every Accept/Reject/bulk button.

Fix: `MatrixHtml#render` now returns ONLY html (`MatrixHtmlTest.renderHasNoScriptTagAndScriptHasTheHandlers`
asserts the output never contains `<script`); a new `MatrixHtml#script()` returns the exact same JS as a plain
string, fed to `pWriter.addJS_cr(...)` by `ebx.ui.MappingMatrixUserService#write` - the identical mechanism
`ProjectWizardUserService`/`FeedDesignerUserService` already use for their own (larger) inline scripts. The
click handlers themselves were left as plain inline `onclick="..."` attributes rather than rewritten to a
data-attribute/`addEventListener` scheme: an inline handler resolves the function it calls at CLICK time, not
at parse time, and the script now genuinely executes (synchronously, right after the HTML that references it,
on every render) - by the time a person can click anything, every `window.mdmmX` function already exists.
CLAUDE.md &sect;9's own fallback ("at least make sure the globals exist before any click") is satisfied by
fixing the delivery mechanism alone; the "prefer attaching listeners from the addJS code" alternative was not
pursued, for time, and is noted here as the one still open if this screen is revisited again.

Whether `UserServicePaneWriter#getElementById`/`setId` themselves worked was never actually in question:
`ProjectWizardUserService`'s own submit button (`SUBMIT_ID = "mdmfWizSubmit"`) is found the exact same way
(`byId("mdmfWizSubmit")` inside the resource file `ebx/ui/wizard.js`, itself delivered through `addJS_cr` - see
`ProjectWizardUserService#script`) and is known to work live - confirming the id mechanism was always fine and
the bug was purely about script delivery.

### 10.2 The overview graphic: one coordinate system for the curves and the cards

The SVG used `viewBox="0 0 1180 H" preserveAspectRatio="none"` while the source/entity cards were absolutely
positioned in literal pixels (`left:10px`, `left:920px`, ...) inside a container with no fixed width - so the
SVG scaled to whatever width the pane actually rendered at while the cards did not, desynchronising curve
coordinates from card positions (the reported "~50px gap" and misaligned curves).

Fix: the SVG now declares explicit `width`/`height` HTML attributes (1180 &times; the computed height, no
`viewBox`, no `preserveAspectRatio`) so 1 SVG unit is exactly 1 real pixel, and `.mdmmOv` is pinned to the same
`width:1180px` inline. `x1`/`x2` (where every curve starts/ends) are now the SOURCE card's own right edge
(`left:10px + width:200px = 210`) and the ENTITY card's own left edge (`left:920px = 920`) - actual CSS
numbers, not independently chosen constants (`MatrixHtml.OV_X1`/`OV_X2`). `MatrixHtmlTest
.overviewCurvesStartAndEndAtTheCardEdges` asserts every curve path both starts at `M 210 ...` and ends at
`... 920 y`.

### 10.3 Status chips on the curves

`MatrixCompute.curveChipPoint` existed (from the first cut) but nothing ever called it - no chip was ever
drawn. `MatrixCompute.chipT(entityIndex, entityCount)` is the missing "which point along the curve": one
distinct `t` per ENTITY (never per source - two sources' curves into the SAME entity already separate
vertically from their own distinct source row), spread across a fixed range so chips of different entities
never collide, whatever the entity count (`MatrixComputeTest.chipPositionsDoNotCollideAcrossEntityCounts`,
checked for 2, 5 and 8 sources per the task's own "2x2, 5x2, 8x6" spread - `chipT` itself only depends on the
entity count, so the source count does not change the result, and was exercised at all three anyway).
`MatrixHtml#renderOverview` now renders one `mdmmOvChip` button per (source, entity) curve: `"N to validate"`/
`"all validated"`/`"N missing"`, prefixed with the arrival mode when the feed is a child/collection/key one
(`"same row"`, `"collection addresses[]"`, `"linked by key"` - `MatrixHtml#arrivalModeFor`), clickable
(`mdmmOpenEntityFocus`, a new script function: opens that entity AND focuses that source in one round trip,
by setting the `mdmmFocus` hidden field before the `GOTO_ENTITY` postback).

### 10.4 Source cards now show their landing table(s)

`MatrixHtml#landingTablesOf` collects every DISTINCT landing table name a source feeds, across every entity of
the project (not only the active one), joined `" + "` (the mockup's own "Contact + BUT_ADDRESS" convention),
rendered next to the source tag.

### 10.5 Samples

The reported "no sample values are ever visible" symptom is, on inspection, fully explained by 10.1 alone: with
the script never running, `mdmmSelect` never fired, so the inspector - the ONLY place a sample was shown -
never filled for ANY cell, whether or not that cell's own embedded `data-cell` JSON actually carried a real
sample. `ebx.MappingMatrixReader#sample`'s own dataspace/dataset resolution
(`Project.landingDataspace`/`ProjectSource.landingDataset`) was re-checked line by line against every other
reader of those same two fields (`ebx.StagingLoader#loadRootFeed`, `RelationChecker`, `QualitySuggester`,
`LandingFlows`) and matches them exactly - no second bug was found there by re-reading the code (this was NOT
re-verified live; see section 11).

Per the coordinator's own note ("the product owner explicitly asked for example values that help"), the sample is now
ALSO shown directly on the matrix cell itself - a third line (`renderCellLines`, CSS class `c3`), "Dupont
&rarr; DUPONT", visible without selecting anything - never only in the inspector any more
(`MatrixHtmlTest.cellShowsTheSampleOnItsOwnThirdLine`).

### 10.6 Close returns to the wizard

Reported separately, same branch: the Mapping matrix's own Close button did not return to the wizard. Fixed
with the module's existing, already-proven mechanism (`FeedDesignerUserService` already uses it for the Feed
designer) - see `FactoryHtml`'s own Javadoc for the full token/return contract:

- `ProjectWizardUserService#write` already arms `.mdmf-feed-designer-link` with `FactoryHtml
  .originCaptureScript` so a "Designer" link's own Close comes back to the wizard; the selector now also
  matches `.mdmf-feed-matrix-link` (the exact class `WizardPage#renderFeed` already gives the "Mapping matrix"
  link), so clicking it records the wizard's own current URL into `sessionStorage` right before navigating
  away.
- `ebx.ui.MappingMatrixUserService#write` now calls `FactoryHtml.originReturnScript(user, wizardUrl)` (login first since wave 13: the token is `mdmf:<login>:origin`; unconditionally, on
  every render) - it rewires this screen's OWN Close button to read that token back and navigate to it instead
  of EBX's plain close action.
- The token survives this screen's own in-page postbacks (entity switch, accept/reject, bulk) with no extra
  work needed: none of them navigates away from the Matrix's own document at all (EBX re-renders the SAME pane
  in place), and `sessionStorage` is untouched by that - `originReturnScript()` re-running on every render is
  simply idempotent (it only rewires the DOM Close button it finds, or waits for one via a `MutationObserver`
  if the shell has not rendered it yet).
- The inspector's own new "Open this feed in the Designer" link (10.3's sibling, section 4's original
  `core.FeedDesignerLink#url` on the feed's `designerUrl`, computed in `ebx.MappingMatrixReader#readFeed` -
  only for a ROOT feed, the only kind that link accepts) is a full-page (`target=_top`) departure with NO
  return token of its own (opening the Designer from the Matrix does not arm a "come back to the Matrix"
  token - out of scope for this lot, the coordinator only asked for the leave-prompt fix). It only gets the
  voluntary-leave marking: `pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdmf-matrix-designer-link"))`,
  so clicking it never triggers EBX's "leave site?" prompt.

## 11. What was never run

Nothing in this feature, INCLUDING the `fix/mapping-matrix-live` lot (section 10), was opened in a browser or
exercised against a live EBX instance from this worktree - the task explicitly forbids running
`tools/install.sh` or touching Tomcat from here. Every fix in section 10 was derived by READING the reported
symptoms against the code (CLAUDE.md &sect;9's own documented rule about `<script>` tags explains 10.1
completely; the coordinator's own diagnosis pointed straight at 10.2/10.3/10.6) and verified only by compiling
and by the static rendering assertions below - not by clicking through the deployed page. What WAS verified:

- `python3 tools/build_xsd.py` regenerates `MdmFactory.xsd` byte-for-byte identically to the committed file
  (`tools/test_local.sh`'s own `diff -q` check passes) and `xmllint --noout` accepts it.
- The whole module (`ebx-mdm-factory/build.sh`) compiles clean against the server's `lib/*` jars plus
  `on-ebx-agent.jar`/`on-ebx-mapping.jar`.
- Every JUnit-free test class under `src/test/java` runs green, `tools/test_local.sh` exits 0 ("all green"),
  166 test classes total (unchanged - every lot so far, including this one, added test METHODS to the existing
  `MatrixComputeTest`/`MatrixHtmlTest` classes, sections 9.5/10, never new classes), including
  `MatrixComputeTest`, `MatrixHtmlTest` and `ProposalReviewsCommentTest`. New for section 10:
  `renderHasNoScriptTagAndScriptHasTheHandlers` (asserts no `<script` tag anywhere and that every handler
  function the HTML's inline `onclick`s call is actually defined in `MatrixHtml#script()`'s own output),
  `overviewCurvesStartAndEndAtTheCardEdges` (curve path start/end coordinates match the card CSS edges
  exactly), `sourceCardsShowTheirLandingTableNames`, `cellShowsTheSampleOnItsOwnThirdLine`, and
  `chipPositionsDoNotCollideAcrossEntityCounts` (2, 5 and 8 sources, per the task's own spread).
- The rendered page HTML/JS was reviewed by hand for consistency (button ids matching hidden field ids,
  `data-cell` JSON escaping, CSS class names matching between the renderer and the inline `<style>` block) but
  never rendered in an actual browser DOM - a real click-through, of either the original screen or this fix
  lot, was out of reach without deploying, which this task explicitly forbids
  (`tools/install.sh` must never be run from this worktree). In particular: the `sessionStorage`-based
  Close-return mechanism (10.6) is the SAME code path `FeedDesignerUserService` already uses successfully
  live, reused verbatim - but the specific claim "Close still returns to the wizard after an in-page postback
  on THIS screen" was reasoned through (10.6's own explanation of why postbacks never touch `sessionStorage`),
  never watched happen in a real browser.

## A rule that empties its samples (EMPTIED_BY_RULE, 2026-09-26)

Found on the PersVol3 volume test: a street rule `regexExtract('^99\s(.*)')`, copied from a value shape, emptied
1 641 of 1 651 CRM addresses, and the matrix offered it in "Accept the N unflagged proposals" like any other cell.
The cell sample now runs the mapping on every filled value of the first 20 landing rows (not only the first one).
When half or more of them come out empty, the cell is flagged `EMPTIED_BY_RULE` ("this rule empties 9 of the 10
sampled values"). The same text shows in the cell and in the inspector, and the bulk accepts skip the cell like any
other flagged one. A `constant(...)` pipeline never raises this flag. The decision is `AttentionRules#emptiedByRule`
(pure, unit-tested). The sample is measured for every cell, including during a bulk accept that reads the project
without an active entity; it is only displayed for the active entity. A REJECTED mapping is not sampled.

## 12. Derived rows: a matching key has no source link (fix/matrix-derived-key-rows, 2026-09-27)

the product owner, on the Personnes matrix: `firstNameKey` / `lastNameKey` read "0 / 2 source links", "not provided by
this source" under each source and "none proposed" under Rules and checks, with Accept/Reject - "should these be
validated? Aren't they technical artefacts automatically added?"

They are `StagingAttribute` rows with `derivation = FOLD_KEY` (`quality.CrossSystemFold`, proposed by the
deterministic quality pass when two sources write the same text differently): computed at load time by
`ebx.StagingLoader` from the entity's own `firstName` / `lastName` on every staging row, whichever feed wrote it,
and never fed by an `AttributeMapping`. They are NOT the technical key (`sourceSystem` / `sourceId`, which follows
its entity - `staging.TechnicalKeyRule`): a matching key keeps its own decision, because only an ACCEPTED one is
generated and used by the matching plan (`MatchPlanLlmSuggester`, `MatchPlanSimulationRunner`), and a rejected one
is never re-proposed. The proposal review already labelled the row "Matching key of firstName"; the matrix could
not, because `MatrixModel.Attr` did not carry the derivation.

What changed, matrix package only (no model change, no restart beyond the class reload):

- `MatrixModel.Attr` gains `derivation` / `derivedFrom` (the source attribute's NAME, resolved by
  `ebx.MappingMatrixReader` by id - a REJECTED source row is still resolved) and `isDerived()`; a new status
  `MatrixModel.DERIVED` that `MatrixCompute.effectiveStatus` answers for every feed of such a row (never stored
  on a cell).
- `MatrixHtml`: the row chip reads "Matching key of firstName" (`mappingMatrix.flag.derived.<derivation>`,
  `VALID_VALUE` and `SCRIPT` have their own wording, anything else falls back to `OTHER`); no coverage counter
  ("no source link: computed at load" instead); ONE cell spanning every source column (`grid-column:span N`,
  violet dashed, "computed at load / from firstName, accents stripped, upper case"), selectable - the inspector
  explains what the key is for and what accepting or rejecting it does; the Rules column reads "not applicable".
  Accept/Reject stay exactly as before (`MatrixCompute.canDecideAttribute` unchanged).
- `MatrixCompute`: `planEntityBulk` / `planFeedBulk` skip a derived row (a sweep never accepts a matching key
  blindly); `entityStats` still counts it as one item to decide while PROPOSED, so "Load the staging" waits for it
  like the wizard's own gate does. (Superseded for the matching key itself by section 13: a `FOLD_KEY` now goes with the sweeps.)
- Tests: `MatrixHtmlTest.derivedKeyRowRendersOneComputedCellInsteadOfNotProvidedPerSource`,
  `MatrixComputeTest.aMatchingKeyGoesWithTheSweepButAComputedFieldDoesNot` (was `derivedAttributeIsNeverSweptButStillCountsAsToDecide`).

## 13. The "left to decide" list, the walk-through and the pinned inspector (feature/matrix-review-queue, 2026-09-30)

Measured on PersVol9: the footer said "77 items still to decide ... decide them in the inspector"; after "Accept the 69
unflagged proposals", 8 items remained and nothing on screen said where they were (the count is project-wide, the grid
shows one business object, "Only what's left to do" only dimmed cells, the inspector sat below the whole grid on the source
key). The 8: three matching keys (`MatrixCompute.planEntityBulk` skipped every derived attribute), one script-computed
field with its derivation suggestion, three checks below the 60 % bar.

- **One list, one function.** `MatrixCompute.stillToDecide(project, threshold)` returns every item the footer counts, in
  reading order (per business object: the object, its source links, then field by field: the field, its field links, its
  guards; then the guards with no field). `itemsStillToDecide` is its size, `readyToLoad` is `size == 0`. (The old
  `proposed + attention` sum counted a flagged PROPOSED cell twice; the list holds it once.) Each item carries a kind
  (Business object / Field / Matching key / Computed field / Source link / Field link / Rule / Check), a selection key and the
  reason the bulk button did not take it (`REASON_*`: takenByTheButton, below the bar with its confidence, needs your eye with
  the cell's own reason, computed field, business object / field first, else "needs your decision").
- **Matching keys go with the bulk.** `planEntityBulk` / `planFeedBulk` accept a `FOLD_KEY` attribute under the usual threshold
  rule: accepting a key only adds a technical column, forgetting it silently loses matches (PersVol9: `firstName` shared 0
  values across sources raw, 60 with the key). It follows its source field: never taken when that field is flagged,
  rejected or itself left waiting (a later Reject of the field only reaches a key still PROPOSED). `SCRIPT` / `VALID_VALUE`
  derivations stay a person's choice. Rejecting a field now rejects its still-PROPOSED matching keys
  (`ProposalReviews.cascadeFoldKeysOfAttribute`, `MappingFollowRule.foldKeyFollowsRejection`: reject only, a decided key is never
  touched); before this nothing carried the rejection over.
- **The panel** (`#mdmmQueue`): the footer's count ("5 items need you", "The others go with the button.") opens it. Items the
  button takes are listed but hidden behind "Also show the N that go with the button". A line's buttons post the same actions as
  everywhere (`ACCEPT_*` / `REJECT_*` through `submitAction` -> `MappingMatrixUserService#handle`); an `EXTERNAL_SERVICE` check
  gets Set aside + Reject (`SET_ASIDE_GUARD` -> `WizardData#setCapabilitiesAside`, the wizard card's write; a set-aside
  suggestion is not read into the matrix at all, so it is not counted). A click on the line opens the item's tab if needed
  (postback: `mdmmEntity` and `mdmmSelected` are set client-side BEFORE the submit - setupObjectContext runs after the event),
  selects the cell / row header / source-link column / guard chip, `scrollIntoView`s it and fills the inspector. A computed
  field's guard has no chip in the grid: the line carries its guard JSON and the ordinary guard inspector shows it. The panel
  mode (`""`, `open`, `all`) is the hidden field `mdmmQueue`.
- **Walk-through.** The bar above the inspector has Previous / "k of N" / Next over the same list, crossing tabs. After a single
  decision the service selects the next item: `handle` keeps the keys that followed the selected one in the LAST rendered list
  (`renderedQueueKeys`, on the service instance), `write` picks the first of them still to decide in the freshly read model, else
  the first of the list; an item still listed (refused decision) stays selected (`MatrixCompute.nextAfterDecision`). On first
  load, and when the posted selection belongs to another tab, the selection is the first item of the open object.
- **Pinned inspector.** The panel, bar, inspector and footer form one `.mdmmDock`, `position:sticky; bottom:60px` (clear of EBX's
  fixed Close bar, like `#mdmm`'s own padding); the panel is absolute inside it, above the footer. No ancestor of the dock may set
  `overflow`. Not yet checked inside a real EBX pane.
- **Tab counters and the filter.** Each tab shows the number left in its object (hidden at 0). "Only what's left to do" hides a
  field row (a `display:contents` wrapper per row, class `mdmmRowDone`) unless the field, one of its links or one of its guards is
  still to decide (`MatrixCompute.rowHasWork`), or a mandatory field is not covered by some source.
  `MatrixComputeTest.derivedAttributeIsNeverSweptButStillCountsAsToDecide`.

### Integration with sec 14 (2026-09-30, `int/matrix-queue-computed`)

- The function of a computed field still `PROPOSED` is decided with the field (sec 14): the list shows ONE line for the
  pair (`MatrixCompute.decidedWithItsField`), so on PersVol9 the leftovers read 7, then 4 once the matching keys went
  with the button - not 8 and 5.
- A "Computed field" line, and the walk reaching it, open the computed cell (what it computes) instead of the generic
  item view; that cell keeps the walk's position (`qkey` maps `derived|<id>` to `attr|<id>`).
- The walk and each group's count follow what the list shows: the items the button takes are walked and counted only
  once "Also show the N that go with the button" is ticked.
- The disabled "Load the staging" link carries the same words as the footer's count as its reason, and is readable on
  the dark footer.

## 14. A computed field says what it computes (fix/matrix-computed-field, 2026-09-30)

Project PersVol9, `phoneE164` (`StagingAttribute.derivation = SCRIPT`, fed at load by `Fasttrack.lib.phone.toE164`
through a `CapabilitySuggestion` of nature `SCRIPT_DERIVATION`): the matrix said "computed at load / from phone, by a
published library function" and "not applicable" under Rules and checks. Nobody could see WHAT was computed, and with
`country` left blank the 313 national-form numbers of source ECOM (for example `0162059624`) come out EMPTY. The
suggestion was counted as "still to decide" with no button anywhere. Matrix package, reader and cascade only; no model
change (no XSD, no restart beyond the class reload).

- **Cell** (a SCRIPT field whose function is known; FOLD_KEY / VALID_VALUE keep their text): line 1 stays "computed at
  load", line 2 is `toE164(phone, country = "") -> the E.164 string`, built by `mappingmatrix.MatrixComputed` from the
  suggestion's `targetFunction` + `binding` (a string = a field of the entity, `{"literal": x}` = a constant, `""` shown as
  `country = ""` in the call and as "blank (constant)" in the inspector) and the first clause of the library's own `@return`.
- **Rules and checks cell**: a chip "Script - toE164" (`MatrixComputed#chipLabel`), selectable, carrying the SAME `data-cell`
  JSON as the merged cell, so both open the same inspector. The script wraps `mdmmSelect` / `mdmmSelectGuard` (new
  `selComputed` function, lines inserted before the restore-selection line; the existing handlers are untouched).
- **Inspector**, in this order: what is computed (first sentence of the function's doc comment, the call, one line per argument
  `name -> field x | constant "y" | blank (constant)` followed by that argument's `@param` text), when it is empty (the
  `@return` text), measured, why it was proposed (the suggestion's rationale), then the decision.
- **Where the function's doc comes from: route (iii), the library source read at render time.** (i) Nothing stores it: the
  capability pass (`CapabilityLlmSuggester#buildScriptInventory`) reads `get_script(includeSource)` and parses
  `ScriptInventory.parseExportedFunctions`, but the doc only reaches the prompt (joined on one line, cut at 600 characters,
  so a long `@param` list loses its `@return`) and is written nowhere. (ii) `CapabilitySuggestion.explanation` is NOT free: it
  is empty at PROPOSED time but `DerivationApplier` writes it at apply time (`writeApplied`) and clears it on remove - reusing it
  would be overwritten by the applier. So `ebx.LibraryDocs` calls the scripting MCP server the Factory already uses
  (`McpServers.client(session, factory, SCRIPTING, 10 s timeout)`, `get_script(scriptIdePath = targetLibrary)`) and
  `capability.FunctionDoc` parses the `/** ... */` block directly above `export function <exact name>(`. `capability.LibraryDocCache`
  keeps the source per library: no call within 60 s, then ONE metadata call (`includeSource:false` answers `revisionId`), the
  source again only when the revision changed; a failure is remembered for 60 s (a dead server is not retried on every click) and a
  good entry is served stale rather than replaced by it. If no source is reachable the section says "The description of the
  function ... could not be read" and keeps the rationale.
- **Measured** (`ebx.ComputedFieldMeasure`): nothing is run (EBX Script cannot be executed from a render), the computed field is, since
  lot T1 (2026-10-04, `docs/SCRIPT-DERIVATION.md` sec 10), a STORED field written by two table triggers, so `Adaptation#get` reads what they stored (it was a
  value-function field that EBX computed on read before; a row not yet backfilled reads as empty). Before the entity's first staging load
  (`StagingEntity.lastLoadAt` empty) the section reads "Measured after the first staging load." and nothing else. Loaded but the
  suggestion not `APPLIED` (the field is then a plain empty field of the model): "Not measured: the function is not applied to the
  staging model yet" - an empty plain field would say nothing about the function. Otherwise, per source system of the project: two
  counts on indexed fields (`./sourceSystem='X'`, then `and osd:is-not-null(./<field read>)`, `RequestResult#getSize`) and the
  function evaluated on at most 500 filled rows in key order (a computed field cannot be filtered or indexed - EBX SQL makes it
  SELECT-only - so a full count would be one script run per row). The line says "on the first 500 of 6 000 filled values" when it is
  a sample, plus one real value before -> after of each kind (converted / empty). Cost: per source 2 indexed counts + at most 500
  script evaluations, only for the entity on screen, cached 5 minutes under (dataset, entity, field, `lastLoadAt`, suggestion
  status). Never a number that was not read.
- **Decision**: one Accept / Reject of the FIELD. Reject reuses the existing `ProposalReviews#cascadeGuardsOfAttribute` (a field's
  rejection already rejects its `PROPOSED` capability suggestions, `SCRIPT_DERIVATION` included). Accept is new:
  `ProposalReviews#decide(..., pCarryQualityRules, pCarryScriptDerivation)` (8 arguments; the 7-argument form delegates with `false`,
  so the wizard and the review screen behave as before) also accepts the field's `PROPOSED` `SCRIPT_DERIVATION` suggestion in the
  same procedure (`acceptProposedDerivationsOfAttribute`, modelled on `acceptProposedRulesOfAttribute`); a `SCRIPT_CONSTRAINT` or
  `EXTERNAL_SERVICE` on the same field is NOT carried. Status only, like any accept of a suggestion outside the review screen:
  nothing is applied to a model (`DerivationApplier` and its Apply button stay where they were). `MappingMatrixUserService`
  `ACCEPT_ATTRIBUTE` passes the flag (a no-op for any field without such a suggestion). A function still `PROPOSED` under an
  already decided field (an older row) keeps its own Accept / Reject in the inspector and on the review screen / wizard step 4.
- **One item, not two**: `MatrixCompute#entityGuardStats` no longer counts the `SCRIPT_DERIVATION` guard of a `PROPOSED` computed
  field as proposed (`decidedWithItsField`; it still counts as a check), so `itemsStillToDecide` reads 1 for the pair and 0 once the
  field is accepted. A sweep still never takes a derived row (unchanged).
- Tests: `FunctionDocTest`, `LibraryDocCacheTest`, `MatrixComputedTest`, `ComputedFieldMeasureTest`,
  `ProposalReviewsDerivationTest`. The read of the staging table and the cascade procedure need a live EBX and were not run.

## 15. UX lot U6 (2026-10-13): one counter, optional suggestions grouped, scope lines

See `docs/UX-MATRIX-QUEUE.md`. In short: the header line, the footer and the tabs read one `MatrixCounter.Tally`; look-up suggestions of external services are no longer items of the "left to decide" list (sec 13) but folded groups per field, counted apart; "Only what's left to do" opens by default once something is decided; the per-source-link bulk is a secondary action; the screen's links take the perspective route when drawn.

## 16. Cost of a read (audit F2-100, 2026-10-24)

`MappingMatrixReader.read` is called by step 4 of the wizard (the "N points need your attention" figure), by the Validation desk on every render, and by the matrix up to three times per click. It used to scan the shared `MappingStep` table twice per mapping (cleaning steps of the sample, then the code lists; the table has no index outside its primary key) and to measure a sample per mapping. Now:

- **One sweep of `MappingStep` per read** (`MappingStepIndex`), grouped by mapping in memory; the code lists and the cleaning steps of the samples (alternatives included) come from it. `StagingLoader.readCleaningSteps(MappingStepIndex, mappingId)` is the variant that takes it; the per-mapping form stays for the loaders.
- **The model is kept** (`MatrixReadCache`, 24 entries, 5 minutes) under a key made of the project, the user, the locale, the entity whose samples are shown, and the modification date of the 11 Factory tables read (`Project`, `ProjectSource`, `LandingTable`, `LandingColumn`, `StagingEntity`, `StagingAttribute`, `TransferFeed`, `AttributeMapping`, `MappingStep`, `QualityRule`, `CapabilitySuggestion`). A decision, a load or any write to one of them gives a new key. The landing tables a sample reads, and the staging table of the open entity (computed fields), are listed with the entry and their date is re-checked on every hit.
- **A sample is measured again only when something it depends on changed** (landing table date, the mapping's pipeline and cleaning steps, the user): `SAMPLE_CACHE`. The samples are still taken on every path, because the "emptied by rule", "order unknown" and "conversion fails" flags they feed decide what the bulk buttons take and what the counters say; leaving them out of the wizard or the desk would make the figures disagree with the matrix again (UX lot U6). What the cache removes is the repetition, not the flags.
- `MatrixCounter` was already computed from the in-memory model, with no query of its own; it reads the cached one.
- DEBUG line in the kernel log: `[mdm-factory] mapping matrix for <project> read in N ms, M mappings, K steps` (and `served from the cache in N ms` on a hit). Not measured on a real project yet.
