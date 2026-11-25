# Feed designer: a graphical view of a `TransferFeed`'s mapping

Built 2026-09-23 on branch `feature/feed-designer` (worktree `ebx-mdm-factory-feed-designer`), from the
product owner's request the same day: "a graphical representation would help a lot, like the one we have
in Connectors". Nothing here was run against a live EBX instance (no tool that writes to the instance was
available while building it - see the final report for what still needs Tomcat). Everything below was
either read from the named source file or verified statically (compiled, unit-tested, or rendered to a
string and inspected).

## 1. What was ported, from where

The whole visual mechanism - two trees, an SVG canvas of curved links, a transformation node per rule, an
inspector with editable pipeline steps, a table view, zoom, fullscreen, hide-unmapped, a dirty marker - is
a FORK of ebx-connectors' own graphical mapping designer:

- `com.on.ebx.connect.ebx.ui.MappingDesignerUserService` (Java, the page-writing `UserService`)
- `www/common/jscripts/connect/mapping-designer.js` (533 lines, ES5)
- `www/common/stylesheets/connect/mapping-designer.css` (171 lines, classes prefixed `ftmd`)

as they stood on 2026-09-23 in `~/Dev/ebx-connectors`. Every new file's own header comment names its
origin file and date, per the task brief. Classes/ids are renamed with the prefix `mdfd` (Connectors used
`ftmd`) so the two designers never collide if both modules are ever active on the same page.

The port is a genuine fork, not a shared library: a Connectors mapping and a Factory feed are different
enough (see section 2) that trying to parameterise ONE implementation over both would have made the
Connectors file harder to read for no benefit to either module. `transfer/TransformationCatalogue`
(section 3) is the one piece deliberately built to be usable by both sides of a boundary (Java and
JavaScript), because pipeline syntax genuinely IS shared: a Factory `AttributeMapping.pipeline` and a
Connectors `MappingRule.transformation` are executed by the exact same `MappingEngine.compute`.

## 2. What was dropped, and why

A `TransferFeed` is not a Connectors mapping, so several things Connectors' designer offers have no
equivalent here and were removed rather than left to render nonsense:

| Removed | Because |
|---|---|
| INBOUND/OUTBOUND direction switch | A feed always reads FROM a landing table INTO a staging entity - there is no reverse direction to switch to. |
| Response-root field | Only meaningful for an outbound mapping's response document; a feed has none. |
| "Service call" section (inputs, sample load, test) | A feed's source is a landing table ROW already staged in EBX, never a live external service call computed per record. |
| Outbound response section | Same reason as the response-root field. |
| "Create a sub-object mapping" (Connectors' own children) | A feed tree's parent/child structure is entirely fixed by `TransferFeed.parentFeed` rows, set by the rule pass or the language-model pass (docs/TARGET-MAPPING.md sec 4.1/4.2) or by a person through the wizard's review screen - never created ad hoc from this page. |

What was ADDED instead, with no Connectors equivalent:

- A feed selector bar across the top, listing the WHOLE tree rooted at the feed the page was opened on
  (always a root feed - see section 4): the root first, then every child feed indented, each showing its
  own entity name (`"Personne <- SALESFORCE sample"` for the root, `"Adresse (child)"` for a child, since
  a child always reads the SAME landing table as its parent - docs/TARGET-MAPPING.md sec 4.2's own
  "a child feed naming a DIFFERENT landing table than its parent feed" refusal). Clicking a feed switches
  the two trees, the canvas and the inspector to that feed's own rules, client-side, no round trip.
- A child feed's own header: its `parentLinkField` (read-only) and its `sourceIdPipeline` (editable,
  meaningful for both a root and a child feed per `TransferFeed`'s own Javadoc).
- The child's own link attribute (the `StagingAttribute` whose `referenceEntity` is the parent entity) is
  rendered on the right, greyed out, with no drop target and no "port" circle at all - "set by the engine"
  (the loader computes and writes it itself, docs/TARGET-MAPPING.md sec 3.3 step 6; a person or the
  language model never chooses its value). A target-side TECHNICAL (primary-key) attribute is locked the
  same way, for the same reason (`sourceSystem`/`sourceId` are never mapped by hand either). Both share one
  CSS/JS state, `mdfdLocked`, so the drag/drop and click handlers only need one gate.
- A read-only mode: no drag, no inspector edits, when the CURRENT feed's `status` is `REJECTED`, or when
  the whole page is read-only (`window.mdfdModel.readOnly`, set when the session's own EBX write
  permission on the feed's record answers no - `Session.getPermissions().getAdaptationAccessPermission`,
  same signature `ebx.RecordLineageReader`'s own `canReadWrite` already uses).

## 3. `transfer/TransformationCatalogue` (lot A)

Pure Java (no EBX type, no `on-ebx-mapping.jar` class), a hand-kept mirror of
`com.on.ebx.connect.core.Transformations`'s 35 registered names (README of
`~/Dev/wt/ebx-connectors-mapping-jar`, "Mapping engine jar" section), MINUS the seven that resolve against
live EBX data or the current moment - `fk`, `lookup`, `golden`, `mdmState`, `runId`, `now`, `hash` - which
a person or a language model designing a feed can never meaningfully author blind (docs/TARGET-MAPPING.md
sec 4.2's own correction: "not six, but seven"). The remaining 28 entries each carry their exact argument
syntax and a one-line hint in English and French.

`parsePipeline`/`joinSteps` are a byte-for-byte Java port of the page script's own `stepsOf`/`joinSteps`
(quote tracking, paren-depth tracking, so `join('; ')` or `regex('\d{2,4}')` never get mis-split on their
own embedded `,`/`|`) - unit-tested with the exact same cases both sides need to agree on (a quoted comma,
a quoted pipe, an empty middle step, a dropped trailing empty step). Java and JavaScript must never read
the same pipeline text two different ways; if either implementation ever changes, the other's test would
need to change with it - the two are not wired together (there is no `on-ebx-mapping.jar` in the browser),
only kept in step by discipline and this shared test.

## 4. The hidden-input contract

The page's model, `window.mdfdModel`, is one JSON object written by `FeedDesignerUserService.model()` -
see that method's own Javadoc and `feed-designer.js`'s header comment for the exact field-by-field shape
(`feeds[]`, each with `sourceFields`/`targetFields`/`rules`, `transformations[]`, `labels[]`,
`entityName`, `activeFeedId`, `readOnly`).

Two hidden inputs travel back to the server on Save, both keyed by feed id (`TransferFeed`'s own row id,
a plain numeric string) so every feed of the tree is saved in one submit, not one per feed:

- `mdfdRulesInput` = `{"<feedId>": [{"id":"<mappingId or 0>","source":"<landingColumn fieldName or empty>","target":"<attribute name>","transformation":"<pipeline text>"}, ...], ...}`
- `mdfdSourceIdPipelinesInput` = `{"<feedId>": "<pipeline text, possibly empty>", ...}`

`source` is empty for a constant rule (`+ constant`, per Connectors' own convention - `addRule('', target,
'constant()')`). A rule's `id` travels for display only: the SERVER diffs by TARGET ATTRIBUTE NAME, not by
this id (see section 5) - the same "one rule per output field" identity Connectors' own designer already
relies on when re-pointing an existing rule by dropping on an already-mapped target.

## 5. Save semantics (`FeedDesignerUserService.onSave`/`saveFeedRules`)

Per feed of the tree, independently (one feed's failure never rolls back another's):

1. Read every `AttributeMapping` row of the feed, keyed by its target attribute's NAME.
2. For every posted rule (also keyed by target NAME, after dropping one that names the feed's own link
   attribute - defence in depth, the client already refuses to create one):
   - **not in the stored map** -> a NEW `AttributeMapping` row, `origin = USER`, `status = ACCEPTED` (a
     person drew it, so it needs no further review), `pipeline` set from the posted transformation text,
     `feed` set to this feed. A constant rule (empty `source`) still needs SOME `landingColumn` (the field
     stays mandatory, docs/TARGET-MAPPING.md sec 2.2) - any column of the feed's own table is used, since a
     `constant(...)` pipeline step ignores its input entirely (README, "Mapping engine jar" table); which
     column it names has no effect on the value written. Documented here as a known, deliberate limitation
     rather than left unexplained.
   - **present, with a changed pipeline text or a changed source column** -> updated in place, the SAME
     `PROPOSED`-or-`USER` editable-in-place gate `WizardData.setMappingPipeline`/`setFeedSourceIdPipeline`
     already enforce for every other in-place edit this module offers (refused otherwise - a row already
     `ACCEPTED`/`REJECTED`/`APPLIED` by rule or language-model origin keeps its stored pipeline).
   - **present, unchanged** -> untouched.
3. Every STORED row whose target is no longer among the posted rules (the reviewer deleted the link on
   screen):
   - `origin = USER` -> deleted outright (nobody else's decision to preserve).
   - `origin = RULE`/`LLM` -> `status` set to `REJECTED` (never deleted - a proposal's history is kept,
     exactly like every other decision in this module: `ProposalReviews.decide`).
4. `TransferFeed.sourceIdPipeline`, per feed: when the posted value differs from the stored one,
   `WizardData.setFeedSourceIdPipeline` is called (reused verbatim, same guard as above).

The response is one message naming how many rows changed in total (created + updated + rejected +
deleted, across every feed of the tree), or the first failure's message if any feed's write failed.

## 6. Entry points and the return trip

- Wizard step 4: a "Designer" link on each ROOT feed's card only (`wizard.WizardPage#renderFeed`) - a
  child feed's tree opens from its ROOT's designer, so a child never gets its own link. The URL comes from
  `core.FeedDesignerLink#url` (`ProjectWizardUserService#model` sets `FeedInfo.designerUrl`, `WizardPage`
  itself stays pure and never builds it).
- The Proposal review screen: the same link on a `FEED` line whose `element` text does not carry the
  " (child of " suffix `ebx.ProposalReviews#addFeedLines` appends for a child (i.e. a root feed again).
- Both render `<a target="_top">` (never a modal iframe - CLAUDE.md sec 9), the same technique
  `ProjectExplorerPage`'s own Wizard/Cockpit links already use.
- Close returns to wherever the link was clicked from: both source pages arm their own
  `.mdmf-feed-designer-link` anchors with `FactoryHtml.originCaptureScript` before navigating away: the
  designer's own Close reads that back with `FactoryHtml.originReturnScript` (the SAME sessionStorage
  mechanism the project explorer already uses for its Wizard/Cockpit links) and navigates there instead of
  the plain EBX close action, which would otherwise land on the native `Project` table.

## 7. What was verified statically, and what still needs Tomcat

Verified without a live instance:

- The whole module compiles and every existing + new unit test passes (`tools/test_local.sh`).
- `TransformationCatalogueTest`: the 28-name catalogue, the 7 exclusions, and `parsePipeline`/`joinSteps`
  against the exact cases the page script's `stepsOf` needs to agree on.
- `feed-designer.js`/`feed-designer.css` parse cleanly (`jjs -scripting`, a full ES5 parse - a syntax error
  anywhere in the file would have failed this) and the CSS's `/*`/`*/` comment markers balance
  (`tools/test_local.sh`'s own check, the same one that caught a real bug in `lineage.css` earlier this
  project).
- `WizardPageTest#feedTreeRendersRootAndIndentedChildFeed` (extended): a root feed's card renders the
  Designer link with `target="_top"`, and a child feed's card renders none.

Needs a real Tomcat + browser to confirm (none of the tools available while building this could deploy or
drive one):

- The actual drag-and-drop, canvas layout, and inspector interactions in a real browser (only their
  *source code* was reviewed and syntax-checked, never executed against a DOM).
- That `FeedDesignerUserService.setupObjectContext`'s one-time `feed` launch-parameter resolution survives
  a real postback the way `ProjectWizardUserService`'s own `project` parameter does (CLAUDE.md sec 9: this
  is a documented EBX behaviour this lot relies on but could not itself re-verify).
- The permission check behind `window.mdfdModel.readOnly` (`Session.getPermissions().
  getAdaptationAccessPermission(...).isReadWrite()`) against a real non-administrator session.
- The origin-capture/return round trip (wizard or review -> designer -> Close) in an actual browser tab.
- That a saved rule's `pipeline` text, once loaded back through `MappingEngine.compute` by the real
  loader, produces the value a person expected when drawing it.

## 8. One item intentionally left as a TODO, not silently dropped

The prompt catalogue the language-model pass (`staging/StagingLlmPass`) uses to describe allowed
transformations to the model is a SEPARATE, fixed system-prompt text (docs/TARGET-MAPPING.md sec 4.2,
"the transformation catalogue... lives in the SYSTEM prompt"), maintained by whoever owns that file - not
touched here (that file was explicitly off-limits for this lot, being edited in parallel by another
agent). `transfer/TransformationCatalogue` (this lot) and that system-prompt text now both describe the
same 28 allowed names independently; unifying them into one source of truth (the prompt built FROM
`TransformationCatalogue.entries()`, rather than a hand-kept parallel list) is worth doing once both lots
have landed, so the two catalogues can never quietly drift apart. Left as a one-line note here rather than
a change to `staging/StagingLlmPass.java`, per the task brief.

## 9. Bug fix, 2026-09-24 (branch `fix/designer-proposed-targets`): a `PROPOSED` target emptied the whole pane

**Measured live on project Personnes, entity Personne, feed "Personne <- SALESFORCE sample".** The Target
pane showed "No field known yet" and every one of the feed's 9 rules had its link converge on the pane's
top-right corner. Cause: `FeedDesignerUserService` kept only `StagingAttribute` rows whose status is
`ACCEPTED` or `APPLIED` - "no real field yet in the generated staging table" (`staging.StagingModel#build`,
true for the GENERATED staging table, but irrelevant to whether a rule can name the attribute as its
target). On Personnes every attribute of `Personne`/`Adresse` was still `PROPOSED` (checked by SQL against
the live instance - the designer is precisely where a person reviews those proposals before deciding them,
so hiding them here made the screen unusable before that very decision could be made. Nothing here was run
against the instance while fixing it (no write-capable/deploy tool was available in this session - see the
end of this section for what still needs Tomcat); everything below was verified by reading the affected
source, compiling it, and running the module's unit tests / a syntax check.

**Server (`FeedDesignerUserService.java`).** A single new helper, `isEligibleTargetStatus(status)` (package-
visible for `FeedDesignerUserServiceTest`), replaces the old `"ACCEPTED".equals(status) ||
"APPLIED".equals(status)` test everywhere a `StagingAttribute` was filtered for the Target side: it returns
`true` for every status but `REJECTED` (and, for parity with the `SUPERSEDED` value other proposal-style
tables of this module carry, `SUPERSEDED` too - `StagingAttribute` never actually stores it today,
`tools/build_xsd.py`'s `proposal('attribute', ...)` call keeps the plain four-value `PROPOSED`/`ACCEPTED`/
`REJECTED`/`APPLIED` enum, but checking for it costs nothing and keeps the method correct if that ever
changes). Two call sites changed:
- `attributesByName` (the map `saveFeedRules` uses to resolve a posted rule's target attribute on Save) -
  without this fix, saving an edit to a rule pointing at a still-`PROPOSED` attribute would have silently
  dropped that one rule (`targetAttribute == null` -> `continue`, `FeedDesignerUserService.java` around line
  272), even though the client now happily lets a person draw it.
- `feedModel`'s `targetFields` loop (what actually reaches the browser) - now also sends the attribute's own
  `status` in the JSON, read by the client to render a "proposed" chip (see below) instead of silently
  omitting the row.

`linkAttributeName` (the lookup for a child feed's own link-to-parent attribute) was checked too and needed
no change: it never filtered by status in the first place, so it already found a still-`PROPOSED` link
attribute - it is created together with the rest of the entity by `propose_target_entity`
(docs/TARGET-MAPPING.md sec 4.2), so filtering it out was never the bug.

**What was deliberately NOT touched.** `WizardData.setMappingPipeline`/`setFeedSourceIdPipeline` - the
actual write-time gate behind every in-place edit this screen offers - refuse a rule/feed unless ITS OWN
`status` is `PROPOSED` or its `origin` is `USER` (`docs/TARGET-MAPPING.md` sec 4.3). That gate is about the
`AttributeMapping`/`TransferFeed` row's own decision, never about the STATUS OF THE ATTRIBUTE/ENTITY IT
POINTS AT, so it needed no change - broadening which target attributes are shown does not broaden what can
be written past review. Kept exactly as is.

**Client (`feed-designer.js`/`feed-designer.css`), two independent changes:**
1. A `PROPOSED` target row (`x.field.status === 'PROPOSED'`, right-hand side only) now renders with a small
   muted "proposed" chip (`mdfdProposedTag`/`mdfdProposed`) but is otherwise a perfectly normal, droppable
   row - never `mdfdLocked` (that class stays reserved for a technical/primary-key attribute or a child's
   own link attribute, "set by the engine", an entirely different reason to not be a rule target).
2. Independent robustness fix, not specific to `PROPOSED`: a rule can still name a field the CURRENT field
   list does not contain at all (an attribute rejected/superseded after the rule was drawn, a landing column
   removed, ...). Before this fix such a rule always fell back to `anchor()`'s "folded" pane-corner point -
   the exact dangling-curve symptom this bug report described, and (unlike the `PROPOSED` case above) a
   defect the target-side status filter alone can never fully rule out. Two new pure functions,
   `fieldExists(side, path)` and `isUnresolved(rule)`, gate both the canvas (`drawAll` - such a rule draws no
   node and no curve at all, resolved or not) and the table view (`renderTable` - the row is still shown,
   selectable, with the unresolved cell underlined and a tooltip, class `mdfdRowUnresolved`/
   `mdfdUnresolvedVal`). A small warning, "N rule(s) point to a field not shown here" (`designer.
   unresolvedWarning`, `#mdfdUnresolvedWarn`), appears in the toolbar whenever the count is non-zero. The
   canvas's own empty-state hint now shows whenever every rule is either absent or unresolved, not just when
   `rules.length` is zero (it used to stay hidden while 9 rules existed but not one of them could be drawn -
   itself a symptom of the very confusion this whole fix addresses).

**Table view / hide-unmapped, checked, no change needed.** `mappedPaths(side)` and the `hideUnmapped` filter
in `renderTree` both key off the rule's raw `source`/`target` PATH STRING, never off a field's status - they
already worked correctly for a `PROPOSED` target once that target is present in `fieldsOf('R')` at all (the
actual bug being fixed here). The table view (`renderTable`) lists every rule regardless of either field's
status; it only needed the `isUnresolved` gate above for the separate, pre-existing "field genuinely not in
the list" case.

**Verified (offline only - see the caveat above):**
- The whole module compiles (`ebx-mdm-factory/build.sh`) and every existing + new unit test passes
  (`tools/test_local.sh`), including the new `FeedDesignerUserServiceTest`
  (`isEligibleTargetStatus`: `PROPOSED`/`ACCEPTED`/`APPLIED`/empty/unknown eligible, `REJECTED`/`SUPERSEDED`
  not).
- `feed-designer.js` still parses cleanly (`jjs --compile-only`, a full ES5 parse) and
  `feed-designer.css`'s `/*`/`*/` comment markers still balance (the same checks `tools/test_local.sh` itself
  runs on every `factory/*.js`/`factory/*.css` file).
- The two new message keys exist in both `Messages_en.properties` and `Messages_fr.properties`, plain ASCII
  (no accents needed either way), matching every other `designer.*` key in the same files.

**Still needs Tomcat (same posture as section 7 above, nothing new here was any more reachable this
session):** the actual rendering of the "proposed" chip and the unresolved-row styling in a real browser: a
real drag onto a `PROPOSED` target actually creating/re-pointing an `AttributeMapping` row through a real
Save postback; and confirming against the live Personnes project specifically (SQL only, in this session)
that after this fix the Target pane for Personne/Adresse shows its attributes and the 9 existing rules
render as ordinary links rather than corner-converging ones.

## 10. Staging-proposal "designer" lot, 2026-09-24 (branch `feature/staging-proposal-designer`) - rules and
checks become the designer's second review mode

Written from the task brief and `docs/mockups/staging-proposal/Designer.dc.html` (the validated mockup):
the Feed designer gains rule/check badges, a field inspector with immediate guard/suggestion decisions,
flagged ("needs your eye") links, suggested-fix links, and a Matrix/Designer mode-switch control, so it
becomes the second review surface next to the Mapping matrix's own "Rules and checks" column
(`docs/MAPPING-MATRIX.md`). Built against lot 0's shared base (`guard.Guard`, `ebx.GuardReader`,
`ebx.GuardWrites`, `staging.AttentionRules`, `AttributeMapping.alternativeTo` - `docs/STAGING-PROPOSAL.md`)
and relies entirely on lot "run"'s extension of `ebx.ProposalReviews#decide` for the cascade rules named in
the task brief (rejecting a field cascades to its `PROPOSED` rules/checks; accepting a suggested-fix mapping
with `alternativeTo` set rejects the mapping it replaces) - neither cascade is reimplemented here.

### 10.1 Rule/check badges (delta 1)

`FeedDesignerUserService#model` now resolves the tree's project code once (`StagingEntity.project` ->
`Project.code` - the same key `ebx.GuardReader#read`/`core.MappingMatrixLink` already use) and calls
`GuardReader.read` once for the whole tree (every feed of one designer page shares one project). Each
target field's JSON (`feedModel`'s `targetFields` loop) gains a `guards[]` array - `id`, `nature`, `label`
(`Guard#label()`), `status`, `check` (`designer.DesignerModel#isCheckNature` - `false` for
`ReviewLine.QUALITY`, `true` for the three script/service natures, matching the mockup's `.gb.rule`/
`.gb.check` distinction), `severity`, `transferPolicy`, `rationale`, `severityOptions[]`
(`DesignerModel#severityOptions`, empty for a nature with no severity concept). `feed-designer.js#renderTree`
renders one small badge per guard after the field name; a `REJECTED`/`SUPERSEDED` guard never reaches this
array at all (`GuardReader.read`'s own filter), so no client-side status check is needed to "fade" it - the
task brief's "faded when REJECTED is excluded anyway" is true by construction, not by a CSS rule. Only
FIELD-level guards are attached (`GuardReader.ENTITY_LEVEL` guards are skipped) - this designer has no
entity-level display, unlike the Mapping matrix's own column.

### 10.2 Field inspector (delta 2)

Clicking a target field row now calls `feed-designer.js#selectField` (a new selection state, `selectedField`,
independent of `selected`/`selectedSuggestion` - `select`/`selectField`/`selectSuggestion` each clear the
other two) instead of jumping to the matching rule's inspector; a rule's own inspector is still reached by
clicking its curve or transformation node on the canvas (`select(i)`, unchanged). `renderFieldInspector`
builds a two-column `.mdfdFieldInspector`: left = the field's own name/type/status and every rule of the
CURRENT feed that feeds it (`rulesFeedingTarget`, the module's "source link" vocabulary in the label -
`designer.field.sourceLinks`); right = a `NEW`-tagged "Rules and checks on `<field>`" list, one card per
guard - its badge/label/status, a rationale line when present, a "When a value fails" control built from
`severityOptions` (blank means "not set" for `SCRIPT_CONSTRAINT`'s transfer policy), and Accept/Reject
buttons. `severityOptionLabel` picks the label set by nature: `QUALITY`'s `WARN`/`REJECT`/`MODEL_CONSTRAINT`
read as validation-severity wording (`designer.guard.severity.*`), while `SCRIPT_CONSTRAINT`'s identical
`WARN`/`REJECT` codes are a TRANSFER POLICY and read as the task brief's own wording - "Flag the record" /
"Stop the row before the master" (`designer.guard.flag`/`designer.guard.stop`) - same two enum values,
different meaning, different label.

Every guard decision posts through `feed-designer.js#postDecision` -> the hidden `#mdfdDecideSubmit` button
(a SECOND EBX submit button, `FeedDesignerUserService#setupDisplay`, hidden by `feed-designer.css`'s
`#mdfdDecideSubmit{display:none}` - CLAUDE.md sec 9, same one-shot mechanism `docs/MAPPING-MATRIX.md` sec 4
already uses for `mdmmSubmit`) driving four hidden fields (`mdfdDecideNature`/`Id`/`Action`/`Severity`) read
by the new `FeedDesignerUserService#onDecide` handler - never `onSave`, never the link-editing dirty cycle.
`onDecide` dispatches `SET_SEVERITY` to `ebx.GuardWrites#setSeverity` and `ACCEPT`/`REJECT` to
`ebx.ProposalReviews#decide` (nature `QUALITY`/`SCRIPT_CONSTRAINT`/`EXTERNAL_SERVICE`/`SCRIPT_DERIVATION` for
a guard, `MAPPING` for a suggested-fix link - section 10.4), re-checking `canEdit()` first, exactly like
`onSave` does.

**Never loses an unsaved link edit** (task brief's explicit requirement): `feed-designer.js` keeps the page's
dirty flag in a new hidden field, `mdfdDirtyInput` (synced alongside `mdfdRulesInput`/
`mdfdSourceIdPipelinesInput` in `sync()`), and `postDecision` refuses to even submit while `dirty` is true
(a plain `window.alert(designer.guard.dirty)`); `FeedDesignerUserService#onDecide` refuses again
server-side, fail-closed, if a hand-crafted request posts `mdfdDirtyInput=1` anyway. This lot's own choice,
called out per the task brief's "warn and keep them (or refuse the decision with a message) - say which you
chose": **refuse**, not warn-and-keep - `onDecide` has no way to re-apply a poster's in-page rule edits onto
the fresh model `write`/`model()` rebuilds from EBX after this postback (that model is always read fresh,
`ebx.ProposalReviews`'s own "never trust what was rendered before this submit" rule), so silently keeping
them would only show them once more before losing them on the very next postback - worse than an explicit
refusal asking the reviewer to save first.

### 10.3 Needs-your-eye links (delta 3)

`FeedDesignerUserService#attentionFlagsByTarget` builds `staging.AttentionRules.MappingInput` rows from THIS
FEED's own non-suggested `AttributeMapping` rows (`LandingColumn.fillRate`/`distinctCount`,
`LandingTable.sourceRowCount`), runs `AttentionRules.detect`, and keys the result by TARGET ATTRIBUTE NAME -
deliberately scoped to one feed, per the task brief's own wording ("for the feed's mappings"), not the whole
entity the way `AttentionRules.DUPLICATE_FIELD` is defined in general (a cross-feed duplicate is out of
scope here). A flagged rule's own JSON gains `attention` (`CONSTANT_COLUMN`/`EMPTY_COLUMN`/`DUPLICATE_FIELD`)
and the plain numbers behind it (`attentionFilledRows`/`attentionTotalRows` - never a formatted sentence,
per `AttentionRules`'s own "Java only measures" rule). `feed-designer.js#attentionReason` builds the
reader-facing sentence CLIENT-SIDE from those numbers and a fixed, domain-free label
(`designer.attention.constant={0} filled row(s)...` etc - no column/attribute name ever appears in the
template, satisfying PLAN.md's "domain knowledge comes from the language model, Java only measures" the same
way the pure `AttentionRules` class itself does). `drawAll` draws such a rule's link red-dashed
(`.mdfdAttn`) with its node re-skinned `.mdfdNodeBad` (a `!` in place of the gear icon, its tooltip carrying
the reason); the rule inspector shows the same reason in a small banner above the pipeline flow. No flag at
all when a column was never profiled (`AttentionRules.MappingInput#filledRows()`'s own null-propagation) -
exactly the task brief's "no flag when columns were never profiled".

### 10.4 Suggested-fix links (delta 4)

A `PROPOSED` `AttributeMapping` with `alternativeTo` set is excluded from the ordinary `rules` array
entirely - both in `feedModel` (so it never collides with the mapping it targets to replace: two rows can
legitimately name the SAME target attribute) and in `saveFeedRules`'s own `storedByTarget` diff (so the
link-editing Save can never silently clobber either row) - see `FeedDesignerUserService#hasAlternativeTo`,
checked at both call sites. Instead it lands in a new `suggestions[]` array: `id`, `source`, `target`,
`transformation`, `status`, `rationale` (`AttributeMapping.rationale`, reused verbatim - no new field), and
`replacesId`/`replacesSource` (the landing column name of the mapping `alternativeTo` points at).
`feed-designer.js` lays out suggestion nodes in the SAME vertical collision-avoidance pass as ordinary rule
nodes (one merged `placed` list, tagged `kind: 'rule'|'sug'`, so the two families never overlap on the
canvas) and draws them green-dashed (`.mdfdSug`) with a `+` node (`.mdfdNodeSug`), selectable independently
via `selectSuggestion`. Its own inspector (`renderSuggestionInspector`) shows the rationale, the replaced
column, the pipeline text, and "Apply the fix" / "Keep the current link" - both `postDecision('MAPPING', ...)`
calls (nature `MAPPING`, section 10.2's own mechanism), relying on lot "run"'s `ProposalReviews#decide`
cascade to reject the replaced mapping on Apply. **Not implemented, deliberately, per the task brief's own
"if the designer already has samples (else omit)":** the mockup's "sample before -> after" line - this
designer has never computed or carried live sample values (unlike the Mapping matrix's own
`MappingMatrixReader` sampling), so it is omitted rather than fabricated.

### 10.5 Matrix | Designer mode switch (delta 5)

`FeedDesignerUserService#feedModel` now sets `matrixUrl` per feed (`core.MappingMatrixLink#url`, this feed's
own `dataspaceKey`/`datasetName`/project code/entity id - empty when the project code could not be
resolved). `feed-designer.js#renderModeSwitch` renders a `.mdfdSeg` control mirroring the mockup's segmented
Matrix/Designer toggle - Designer always the active (non-link) segment on this page, Matrix a plain
`target="_top"` anchor (class `mdfd-designer-matrix-link`, marked voluntary-leave only via
`FactoryHtml.voluntaryLeaveScript` - the SAME posture `ebx.ui.MappingMatrixUserService` already gives its own
"Open this feed in the Designer" link, no origin-capture token of its own: a departure to a sibling review
mode is not a "return to the wizard" trip). The existing origin-capture/return handling (wizard/review ->
Designer -> Close) is untouched.

### 10.6 Vocabulary (delta 6)

Every new label reads "field" (not "attribute") and "source link" (not "feed"/"TransferFeed") in its
English/French text - `designer.field.sourceLinks`, `designer.field.inspector`, the guard/suggestion labels
- per the task brief's vocabulary table; the technical words stay in code comments and Javadoc only, never
in a label string.

### 10.7 Tests and what was verified, offline only

`DesignerModelTest` (new, `com.on.ebx.factory.designer`) covers `severityOptions` (the exact three arrays,
including that a caller mutating the returned array never corrupts the next call - `.clone()`) and
`isCheckNature` for every nature `Guard` can carry, plus an unknown nature failing open to "check".
`FeedDesignerUserServiceTest` is unchanged (its one pure decision, `isEligibleTargetStatus`, was not touched
by this lot). `tools/test_local.sh` - XSD/ASCII/CSS-comment checks, the full module compile, and every
JUnit-free test including the two above - runs green end to end. `feed-designer.js` parses cleanly
(`jjs --compile-only`) after every change; `feed-designer.css`'s comment markers balance.

**Nothing in this lot was run against a live EBX instance** (only read-only `data-access` MCP tools were
available this session, per the task brief) - in particular, unverified:
- The rule/check badges', field inspector's, attention links' and suggestion links' actual rendering and
  interaction in a real browser (reviewed and syntax-checked only).
- A real guard accept/reject/severity postback through `onDecide` - `ebx.GuardWrites#setSeverity` and
  `ebx.ProposalReviews#decide` are both already exercised live by other screens (the wizard, the Mapping
  matrix), so the risk here is scoped to the dispatch/refusal logic this lot adds on top, which was only
  read and compiled.
- Whether `AttributeMapping.alternativeTo` is ever actually SET on a real row - lot 0 explicitly left it
  unwritten ("a later lot... is expected to set and read it"); this lot READS it (`suggestions[]`) but has
  no data to read yet until the run/LLM lot writes it. The `suggestions` array and its UI were checked only
  against hand-built JSON in this session's own reading of the code, not against a live row.
- `staging.AttentionRules`'s own live behaviour was already verified by lot 0 against real column
  statistics elsewhere in the module; this lot's own addition (scoping the input list to one feed, keying
  the result by target name) was checked by reading `AttentionRulesTest`'s existing coverage and the
  `feedModel`/`attentionFlagsByTarget` code, not by a fresh live run.
- The Matrix/Designer mode-switch link's actual navigation and the Mapping matrix's own read of a feed
  whose designer this lot changed.

## 11. `feature/designer-align-add-field` lot, 2026-09-24: mode-switch alignment, node overlap, and adding a target field

Three problems from a live screenshot (entity Supplier &lt;- ERP SUPPLIER, 22 rules all `trim`, 25 target
fields). **Nothing here was run against a live EBX instance or a browser** - this worktree had no tool that
writes to the instance or drives Tomcat; everything below was verified by reading the affected source,
compiling the module and running its unit tests (`tools/test_local.sh`), and inspecting the generated HTML
markup by hand. Be honest about the gap: the actual pixel alignment, the drag-and-drop of a link onto a
freshly created field, and the postback round trip were never seen rendered.

### 11.1 Matrix | Designer segmented control (problem 1)

`renderModeSwitch()` (`feed-designer.js`) emits `<span class="mdfdSeg"><a>Matrix</a><span
class="mdfdOn">Designer</span></span>` (or two plain `<span>`s when `matrixUrl` is empty), while the
Diagram|Table control (`build()`) emits two `<button>`s in the same `.mdfdSeg` wrapper - `feed-designer.css`
only styled `.mdfdSeg a` and `.mdfdSeg button`/`.mdfdSeg button.mdfdOn`, so the bare `<span class="mdfdOn">`
had no padding/background/height at all and sat at a different baseline than "Matrix".

Fixed by replacing the two tag-specific rules with one selector covering every direct child regardless of
its tag (`.mdfdSeg>a,.mdfdSeg>button,.mdfdSeg>span`): identical `inline-flex`/`align-items:center`/height
(28px)/padding/line-height for all three tag kinds, and `.mdfdSeg>.mdfdOn` (button OR span) for the one
active-segment look, replacing the old `.mdfdSeg button.mdfdOn`-only rule. A `.mdfdSeg>*+*{border-left:...}`
rule adds the divider between segments that neither control drew explicitly before (asked for in the task
brief - checked, there was none). The Diagram|Table buttons keep the same padding/colors, now expressed
through the shared selector rather than a `button`-specific one, so they should look the same as before
(not re-measured in a browser - see the caveat above).

### 11.2 Canvas node overlap (problem 2)

Before this fix, `drawAll()` (`feed-designer.js`) gave every transformation node a fixed pitch of `NH=44 +
GAP=6 = 50px`, independent of a tree row's own height (`26px * --mdfd-scale`, `feed-designer.css`). With
about 7 rows fitting in the visible pane height, every node past the 7th hit the forward-pass's hard bottom
clamp (`if (yy + NH > bottom - 4) { yy = bottom - 4 - NH; }`) and landed on the SAME spot - the screenshot's
"two overlapping Transformation boxes, ~15 links converging on the bottom one".

Three changes, together:
- **A node is now one line** (`gear` + the pipeline text only - `trim`, `constant(...)`, etc.), its height
  set inline to `NH = max(16, round(26 * scale) - 4)` so it tracks the tree row height at any zoom level; the
  former "Transformation"/"Constant" heading text moved into the node's own `title` tooltip (already built,
  unchanged). `.mdfdNodeHead`/`.mdfdNodeBody` (two-line layout) are gone, replaced by one `.mdfdNodeLine`
  span; `.mdfdGear`/`.mdfdNodeLine` font sizes now scale with `--mdfd-scale` the same way `.mdfdType`/
  `.mdfdSampleV` already did, so a node never overflows its own (now much smaller) box at a low zoom level.
- **A node is centred on its TARGET row's `y`** (`b.y`), not the midpoint of source and target as before -
  its output-side link is then a straight horizontal line, and a 1:1 set of rules lines up node-per-row with
  ZERO push needed (the node pitch, `NH + GAP`, is slightly smaller than a row's own pitch by construction).
- **Overlap resolution is the classic two-pass "minimum gap" placement**, not a single forward pass with a
  hard bottom clamp: a forward pass keeps each center at least `NH + GAP` below the previous one (pushing a
  later node down when its ideal position is too close to an earlier one already pushed down); a backward
  pass then pulls an EARLIER center back up when the forward pass pushed a LATER one down more than the gap
  required. The result is a strictly increasing sequence of centers, always at least `NH + GAP` apart - no
  two nodes can ever land on the same spot, whatever the count. When there genuinely isn't enough vertical
  room for every node, they run past the pane's top/bottom instead of stacking (reachable by scrolling
  either tree, which re-triggers `drawAll` via the existing scroll listeners) - preferred over ever
  overlapping, per the task brief ("no two nodes may ever overlap").
- **A node whose relevant anchor(s) are entirely scrolled out of the visible pane is not drawn as a node at
  all** (`anchor().visible` false on both ends for a rule with a source, or on its one end for a
  source-less/constant rule): rather than clamping it to a useless corner box, it is simply skipped from the
  `placed` list. Chosen over "draw it anyway, clamped" (the brief's other allowed option) because a
  clamped-but-invisible-anchor node is exactly the failure this fix removes - keeping that behaviour for
  nodes and only removing it for the STACKING case would have re-introduced a smaller version of the same
  bug. Its link, if the rule has a node-worthy transformation, falls back to the plain source-to-target curve
  (the same `else if (pa)` branch a rename-only rule with no node already used) rather than disappearing
  outright - only the intermediate node box is skipped, not the connection itself.

Still needs a real Tomcat + browser to confirm (per the caveat above): the actual pixel layout at various
zoom levels, with the tree scrolled, with `hideUnmapped`/search filtering rows out, and in full-screen mode -
only the code path was reviewed, not seen rendered.

### 11.3 Adding a target field from the designer (problem 3, new feature)

A `+ Target field` button next to `+ Constant value` in the toolbar (`build()`, hidden in read-only mode -
same gate as the constant button) opens a small inline form (`.mdfdAddFieldForm`, styled like the existing
`.mdfdFeedMeta` bar - no modal, consistent with the rest of this page): name, label, type (a plain `<select>`
of the six `StagingAttribute.dataType` codes, `STRING`/`INTEGER`/`DECIMAL`/`BOOLEAN`/`DATE`/`TIMESTAMP`), and
mandatory. "Create" posts through a THIRD hidden one-shot EBX submit button, `#mdfdAddFieldSubmit`
(`FeedDesignerUserService#onAddTargetField`) - the same "posting a user service's own inputs" pattern
`#mdfdDecideSubmit`/`onDecide` already uses (CLAUDE.md sec 9), never folded into `onSave`'s link-editing/dirty
cycle.

**Refusals, checked client-side (immediate feedback) AND again server-side (the only one ever trusted),
in this order:**
1. The page is dirty (an unsaved link edit) - same reasoning as `onDecide`'s own Javadoc: this class re-reads
   the model fresh after the postback and has no way to re-apply a poster's pending edits onto it, so an
   unsaved edit must be saved first rather than silently discarded.
2. The session cannot write this feed's tree (`canEdit`, reused verbatim).
3. The name is empty, longer than 80 characters, or does not match `StagingAttribute.name`'s own XSD facet
   (`tools/build_xsd.py`: `maxLen=80, pattern='[A-Za-z][A-Za-z0-9_]*'`) - `FeedDesignerUserService
   .isValidFieldName`, a pure static method (package-visible for `FeedDesignerUserServiceTest`, same posture
   as `isEligibleTargetStatus`), covers this exactly.
4. The name collides, case-insensitively, with ANY existing `StagingAttribute` of the entity **regardless of
   that row's own status** (`attributeNamesOfEntity` - not `attributesByName`, which only lists eligible-
   target statuses): a name must be unique across the whole entity, and there is no unique constraint on
   `StagingAttribute.name` in the schema to lean on (`id`, auto-increment, is the table's actual primary
   key) - checked here by hand. The client's own check (`submitAddField`, `feed-designer.js`) is a courtesy
   only, scoped to `current.targetFields` (the eligible-status list the pane already shows), so it can miss a
   collision with a `REJECTED` attribute's name; the server's check never can.
5. The posted data type is not one of the six known codes (`FeedDesignerUserService.isKnownDataType`, another
   pure static method) - defence in depth, since the client only ever offers those six through a `<select>`.

**The created row**: `entity` = the current feed's entity (posted back as a new `entityId` field of
`feedModel`'s own JSON - never shown, only used for this round trip), `origin = USER`, `status = ACCEPTED` -
"a person created it" is itself the decision, the same convention `saveFeedRules` already uses for a
hand-drawn rule. After the round trip, `feedModel` re-reads every `StagingAttribute` of the entity from
scratch, so the new field appears in the Target pane wherever the table's own iteration order puts it (no
special sort is applied to `targetFields` today, and none was added for this lot - the task only asked that
the field appear, not at a particular position) and is immediately droppable, being an ordinary eligible-
status (`ACCEPTED`) row like any other.

**Generator finding - does a regeneration of an already-`APPLIED` entity pick up the new field? Yes.**
Evidence, read (not executed) this session:
- `TechnicalKeyRule.isAcceptedLike("ACCEPTED")` returns `true`
  (`ebx-mdm-factory/src/main/java/com/on/ebx/factory/staging/TechnicalKeyRule.java:29`) - the exact test
  `StagingModel.partition` uses to decide whether a non-key attribute counts as a "business attribute" for
  generation (`ebx-mdm-factory/src/main/java/com/on/ebx/factory/staging/StagingModel.java:302`, inside the
  `partition` method, lines 293-308). A brand-new `ACCEPTED` attribute is therefore included by `StagingModel
  .build`/`buildTable` the very next time it runs, exactly like any other accepted attribute.
- `StagingGenerator#generate` does not special-case "nothing changed": every call re-reads the live
  `StagingEntity`/`StagingAttribute` rows and re-runs `StagingModel.build`, then, for an entity whose model
  is already published (`decision.regenerate`, true once a live model exists -
  `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/StagingGenerator.java:322`), calls `runRegeneration`
  (`StagingGenerator.java:459`) instead of a fresh `build_data_model`.
- `runRegeneration` diffs the FULL freshly-built model (which now includes the new field) against the live
  model's existing tables with `StagingModelDiff.diff` (`StagingGenerator.java:463`) and applies only the
  `additive` side through one `apply_data_model_patch` + republish (`StagingGenerator.java:468-522`).
- A field present in the desired model but absent from the existing table is classified `additive` by
  `StagingModelDiff.diffExistingTable` - "new field" - with a `create_field` op
  (`ebx-mdm-factory/src/main/java/com/on/ebx/factory/staging/StagingModelDiff.java:373`, the `pAdditive.add(new
  Change(true, pTable.name, pTable.name + "." + field.name + ": new field."))` line; `create_field` itself is
  emitted at `StagingModelDiff.java:568`).
- So: a new `ACCEPTED` `StagingAttribute` on an already-generated entity is picked up by the next "Generate
  the staging" run as an ordinary additive change and applied/republished automatically, with **no extra
  screen or step needed** - a person only has to click "Generate the staging" again.

Because of this, `onAddTargetField`'s own success message doubles as the notice the task brief asked for:
`designer.targetField.created` = `Field "{0}" created. Generate the staging again to create this field in
the staging model.` (and the French equivalent) - a single composed message rather than a separate
`designer.targetField.regenerateNotice` label, since the two facts are always shown together and there is no
client-side rendering of this text (it is `this.message`, read straight off `Messages.get`, the same as
`designer.saved`/`designer.saveFailed` already are).

**What was deliberately not attempted**: no change to `StagingModel`/`StagingGenerator`/`StagingModelDiff`
themselves (out of scope for this lot, and unnecessary per the finding above); no attempt to pre-sort
`targetFields` by anything other than the table's own row order.

**Verified, offline only (see the caveat at the top of this section)**:
- The whole module compiles (`ebx-mdm-factory/build.sh`, via `tools/test_local.sh`) and every existing + new
  unit test passes, including `FeedDesignerUserServiceTest`'s four new cases (`isValidFieldName`:
  a single letter, letters/digits/underscore, exactly 80 characters valid; null/empty/leading-digit/leading-
  underscore/space/hyphen/81-characters invalid; `isKnownDataType`: the six codes known, null/empty/lower-
  case/an unrelated word not known).
- `feed-designer.js`/`feed-designer.css` still parse cleanly (`jjs --compile-only` / the CSS comment-balance
  check, both part of `tools/test_local.sh`).
- The two new hidden-field/message-bundle key sets exist in both `Messages_en.properties` and
  `Messages_fr.properties`, ISO-8859-1 with `\uXXXX` escapes for every accented character (matching the
  file's dominant convention elsewhere, even though the pre-existing `designer.*` block itself is
  inconsistent - mostly plain ASCII with the accents dropped).

**Still needs Tomcat + a browser** (nothing here was any more reachable this session): the actual rendering
and click-through of the new button/form/error messages; a real drag of a link onto a field created this
way; the full postback round trip (`onAddTargetField` writing a real `StagingAttribute` row through a live
`Procedure`/`Session`); and confirming the generator finding above by actually running "Generate the
staging" twice on a live project with a field added through this screen in between - the finding is read
from the source, not observed running.

## 12. `fix/designer-cosmetics` lot, 2026-09-24: add-field form layout, folded-anchor dangling links, toolbar wrap

Three more cosmetic defects seen live (entity Supplier, ~620px UserServicePane width). **Nothing here was
run against a live EBX instance or a browser either** (same posture as lot 11) - verified by reading the
source, `jjs --compile-only` on `feed-designer.js`, the CSS comment-balance check, and `tools/test_local.sh`
(full module compile + every JUnit-free/JUnit test, unchanged count, green). Only `feed-designer.js` and
`feed-designer.css` were touched; no Java, no message bundle.

**12.1 `+ Target field` form layout.** `build()`'s `.mdfdAddFieldForm` used to push a bare `<label>` next to
a bare `<input type=text>` inside one flex row with no wrapper - EBX's own stylesheet stretches a plain text
input to the full remaining width of its containing flex row, so the row wrapped as "Name [..........]
Label" / "[..........] Type" / "[select....] Mandatory Create Cancel", each label ending up far from its own
control. Fixed by wrapping each label+control in its own `<span class="mdfdAddFieldPair">`
(`white-space:nowrap`, so a pair never splits across the wrap) and giving the two text inputs and the select
explicit widths at `#mdfd .mdfdAddFieldForm input#mdfdAddFieldName`/`#mdfdAddFieldLabel`/`select
#mdfdAddFieldType` specificity (name 180px, label 220px, type auto) so they beat EBX's own rule. Create/
Cancel/the error line are now grouped in one trailing `<span class="mdfdAddFieldActions">`, unchanged
otherwise (ids, `submitAddField`/`closeAddFieldForm` wiring untouched).

**12.2 Dangling links when a filter hides one end of a rule.** `anchor(side, path)` returns the pane-top
"folded" fallback point (`{visible:false, folded:true}`) whenever `portOf` finds no rendered port for that
path - which happens both when a row is genuinely scrolled out of the visible pane (a real port element
exists, just clipped) AND when the source/target search filter or "Hide unmapped fields" currently hides
that row from its tree entirely (no port element in the DOM at all). `drawAll()`'s placement pass already
special-cased the first case (`!a.visible && !b.visible`, added in lot 11 for the node-overlap fix) but had
no equivalent check for the second - a rule with, say, its target hidden by a target-side filter still got a
node AND a link drawn from the folded pane-top point, which visually looked exactly like it pointed at
whatever row happened to sit there (the live report: filtering source on "NAME1" and target on "Designer"
drew NAME1's trim node linked to the unrelated "Designer test" row).

Fixed by checking `.folded` (not `.visible`) on both the node-placement pass and the separate link-drawing
loop (which recomputes `anchor()` independently of the placement pass and had no folded check at all): a
rule or suggestion with either end folded gets neither a node nor a link this render pass. This is
deliberately a different condition from `isUnresolved`/`fieldExists` (a field truly removed from the feed) -
a folded field still exists, is still in `rules`/the table view/the rule count, it is just not rendered right
now; clearing the filter (or unchecking "Hide unmapped fields") re-triggers `renderTree`+`drawAll` the same
way it already did, so the link/node reappear as soon as `portOf` finds a real port again - no new redraw
wiring was needed. The drag-to-create temp line (`tempPath`, driven by `mousedown` on an actual port element,
which by construction is never folded while being dragged from) and selection highlighting (`cls2`/`hot`/
`selected`, computed the same way, just now gated by the same skip) are unaffected.

**12.3 Toolbar wrap pushing zoom/fullscreen onto their own line.** `build()`'s main `.mdfdBar` (+ Constant
value, + Target field, unsaved marker, Hide unmapped, N rules, Diagram|Table, zoom, fullscreen) had enough
controls to wrap at the ~620px pane width, and the zoom control + fullscreen button - being last - always
landed alone on the wrapped second line. Moved both to the right end of `renderModeSwitch()`'s own
`.mdfdModeBar` (Matrix|Designer), which has much less content and plenty of spare width: added a
`.mdfdSp` spacer (the same flex-1 spacer class the main toolbar already used) between the Matrix|Designer
segment and the zoom/fullscreen group. Ids and click handlers (`#mdfdZoomIn`/`#mdfdZoomOut`/`#mdfdFullBtn`,
wired by `build()` after `root.innerHTML` is set) are unchanged - `build()` looks the elements up by id
regardless of which bar rendered them, and `renderModeSwitch()` is called before the main bar in `build()`'s
own `h.push` sequence, so the ids exist in the DOM by the time the wiring code runs. `renderModeSwitch()` now
always renders its bar (previously it returned `''` when `current` was null, i.e. an empty feed list) so the
zoom/fullscreen group is never silently dropped in that edge case either; the Matrix|Designer segment itself
stays conditional on `current`, unchanged. Fullscreen mode (`#mdfd.mdfdFull`) does not hide `.mdfdModeBar`,
so the moved controls keep showing there too - not re-verified in an actual browser, per the caveat above.

**Verified, offline only**: `tools/test_local.sh` green end to end, same test count as before this lot
(`FeedDesignerUserServiceTest` untouched - no Java changed); `feed-designer.js` parses cleanly (`jjs
--compile-only`); `feed-designer.css`'s comment markers balance.

**Still needs Tomcat + a browser**: the actual pixel alignment of the add-field form's pairs at the two
explicit input widths; that a search/hide-unmapped filter genuinely leaves the affected rule/suggestion
undrawn (node and link both) rather than drawn wrong, and that it reappears correctly the instant the filter
is cleared; that the toolbar no longer wraps at the real pane width, and that the zoom/fullscreen group reads
correctly at the right end of the Matrix|Designer bar in both normal and full-screen mode. Nothing here was
any more reachable this session than lot 11 was.
