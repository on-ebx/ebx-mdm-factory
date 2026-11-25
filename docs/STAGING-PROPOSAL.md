# Staging proposal redesign

## Lot 0 - shared base

Written 2026-09-24 on branch `feature/staging-proposal-base` (worktree `ebx-mdm-factory-sp-base`), from
the task brief: the staging step is being redesigned so one click on "Propose the staging" produces the
structure, the field links, the quality rules and the script checks together, reviewed in the Mapping
matrix (a new "Rules and checks" column) or the Feed designer. This lot is the SHARED base other lots
(run orchestration, matrix UI, wizard UI, designer UI) compile against - a small, stable, documented API,
no UI, no orchestration. Nothing in this lot changes any screen (`wizard/*`, `mappingmatrix/*`,
`ebx/ui/*`, `Messages_*.properties` were not touched, per the task brief).

### 1. Column profiling: `ebx.LandingColumnProfiler` + `landing.ColumnProfileSql`

`LandingColumn.fillRate`/`distinctCount`/`maxLength` exist in the model but are measured only for a live
database source scanned by the catalog - a sample-file source (project Personnes) never gets them, they
read `null`. `LandingColumnProfiler.profile(Session, Adaptation pFactory, String pProjectCode, boolean
pOnlyMissing)` measures them against the real landing data:

- One EBX SQL aggregate query per `LandingTable` (never one per column): `COUNT(*)` plus, per column,
  `COUNT(CASE WHEN <col> IS NOT NULL AND <col> <> '' THEN 1 END)`, the same shape `COUNT(DISTINCT CASE
  WHEN ... END)`, and `MAX(CASE WHEN ... THEN CHAR_LENGTH(<col>) END)`. "Filled" means non-null AND
  non-empty - the same convention `landing.RelationCheck` already established for this module's other
  generated-SQL feature (a landing column is always `string`, an empty source value lands as `''`, never
  distinguishable from "no value" by the landing loader's own bare `TO_VARCHAR` cast - see
  `landing.LandingFlow#mapping`).
- Every identifier is always double-quoted (EBX SQL reserved words like `ORDER`/`GROUP`/`COLUMN`/`DAY`),
  the same defence `landing.RelationCheck#build` already applies.
- `fillRate` is a **percentage** (0-100, `BigDecimal`, 2 decimals) - confirmed from the model's own field
  label (`Fill rate (%)`) and from every existing consumer (`ebx.QualitySuggester`'s own
  `fillRatePercent` field, `ebx.StagingSuggester`'s raw `BigDecimal` read), not guessed.
- The pure SQL text/interpretation lives in `landing.ColumnProfileSql` (`build`/`interpret`), no EBX
  type - the same `landing.RelationCheck`/`ebx.RelationChecker` split this module already keeps for its
  other generated-SQL feature. `ebx.LandingColumnProfiler` only resolves the project/sources/tables,
  runs the query (`Adaptation.createQuery`/`Query<Tuple>`/`QueryResult<Tuple>`, the same API
  `ebx.RelationChecker` already uses) and writes the result back, one `Procedure` per table.
- `pOnlyMissing`: `true` skips a table's query entirely when every one of its columns already has a
  non-null `fillRate` (nothing to do), and otherwise still measures the table but only overwrites a
  column whose stored `fillRate` is currently null - an already-profiled column is never silently
  reset. `false` always measures and always overwrites every selected table.
- Never throws for one bad table (a table whose landing dataset cannot be resolved, or whose generated
  query the EBX SQL engine refuses): the failure is caught, logged to `System.err` (this module has no
  logging framework anywhere to plug into - checked; every existing class either returns a status field
  or accumulates an error list of its own, no `Logger` is used anywhere in `ebx-mdm-factory`), and the
  run continues with the next table. Returns the number of `LandingColumn` rows actually written.

**What was NOT verified live**: the `CHAR_LENGTH` function name. The EBX instance was unreachable for
the whole of this lot (`mcp__data-access` answered `ECONNREFUSED`) - `CHAR_LENGTH` was chosen as the
ANSI SQL standard name (EBX SQL runs on Apache Calcite, whose standard operator table has always
supported it in this project's other observations - CLAUDE.md &sect;8's own Calcite stack-trace note),
but no prior class in this module ever ran a `LENGTH`/`CHAR_LENGTH` query against this instance's EBX
SQL engine to confirm which name it accepts. If it turns out unsupported, the failure degrades cleanly
(caught per table, `maxLength` simply stays unmeasured) rather than crashing the run - but re-verify
before trusting a `maxLength` value. `landing.ColumnProfileSql`'s own Javadoc flags this the same way.

### 2. Guards: `guard.Guard` + `ebx.GuardReader`

A "guard" is a `QualityRule` row or a `CapabilitySuggestion` row of nature `SCRIPT_CONSTRAINT`/
`EXTERNAL_SERVICE`/`SCRIPT_DERIVATION` - the four natures `review.ReviewLine` already names for exactly
this. `guard.Guard` is a pure, immutable value normalising either shape: `id`, `nature`, `entityId`,
`attributeId` (nullable - entity-level), `kind` (the `QualityRule.kind`, or the short function name for
a script guard, or the connector operation for `EXTERNAL_SERVICE`), `argument` (`QualityRule.argument`
only - a capability guard's own "argument" is `binding`, deliberately not folded in here), `severity`
(meaningful for `QUALITY`/`SCRIPT_CONSTRAINT` only, `null` otherwise - "no default value where it means
nothing", the same rule the XSD's own field comments already state), `transferPolicy` (`SCRIPT_CONSTRAINT`
+ zone STAGING only), `status`/`origin`/`confidence`/`rationale`, and `valuesSeen`/`valuesFailing`/
`examples` (`QUALITY` only today). `Guard#label()` is a short, purely computed display string: `"<kind>
<argument digest>"` for `QUALITY` (argument truncated with `landing.TextLimits#truncate`, the same
helper other message-length-bounded fields already use), `"<kind>"` verbatim for the other three natures
(the short function name, e.g. `"phone.isValid"`, is already built by the reader - see below).

`ebx.GuardReader.read(Adaptation pFactory, String pProjectCode)` returns `Map<String attributeId,
List<Guard>>`, with `GuardReader.ENTITY_LEVEL` (`""`, documented on the field) as the key for every
entity-level guard of the project (a real `StagingAttribute` id is never empty, so this key never
collides). `QualityRule` has no direct `project` field, only `entity`, so it is read per `StagingEntity`
of the project (same loop shape `ebx.ProposalReviews#addQualityRuleLines` already uses);
`CapabilitySuggestion` has its own `project` foreign key and is read in ONE query for the whole project.
A `REJECTED`/`SUPERSEDED` row is skipped (task brief); every other status is kept - a reviewer needs to
see an already-`ACCEPTED`/`APPLIED` guard too, not only open proposals. The short function name
(`"phone.isValid"`) is built with `ebx.CapabilityApplier#aliasOf` directly (both classes are in package
`ebx`, so no duplication is needed - unlike a genuine pure/impure boundary such as
`transfer.TransformationCatalogue`'s own JS/Java pipeline-parsing duplication).

Field paths and the row shape deliberately mirror `ebx.ProposalReviews#addQualityRuleLines`/
`#addCapabilitySuggestionLines` (same `FactoryPaths` constants, never redeclared) - but `GuardReader`
does not build the review screen's own verbose "proposition" wording (`review.ProposalWording`): a
`Guard` carries the raw fields, so the Mapping matrix / Feed designer can render their own compact
presentation.

### 3. Guard writes: `ebx.GuardWrites`

`GuardWrites.setSeverity(Session, Adaptation pFactory, String pNature, String pId, String pSeverity)`
dispatches by nature to the write that already exists rather than re-implementing it:
`WizardData.setQualityRuleSeverity` for `QUALITY` (`WARN`/`REJECT`/`MODEL_CONSTRAINT`, refuses an
`APPLIED` row), `WizardData.setCapabilityTransferPolicy` for `SCRIPT_CONSTRAINT` (blank/`WARN`/`REJECT`
`transferPolicy`, read live by `ebx.MasterLoader` at every transfer - CLAUDE.md &sect;15). Any other
nature is refused outright (`EXTERNAL_SERVICE`/`SCRIPT_DERIVATION` carry no severity concept at all - a
real `Guard` of either nature always reads `severity == null`).

**One deliberate difference from the task brief's literal wording**, called out here rather than
silently decided: the brief asks to "refuse an APPLIED row (same rule as `ReviewRules.canDecide`)" for
both cases. `WizardData.setQualityRuleSeverity` already does this. `WizardData.setCapabilityTransferPolicy`
deliberately does **not** - its own Javadoc (quality-script-rule lot, 2026-09-23) explains why:
`transferPolicy` is read LIVE at every transfer, never baked into the published script body, so changing
it after the constraint is `APPLIED` is the normal case, not an edge one. Overriding that
already-shipped, already-reasoned product decision to force a blanket refusal here would silently break
the one case it was written for. `GuardWrites` keeps each delegate's own rule rather than imposing a new
one on top - and exposes `GuardWrites.isApplied(Adaptation, String pNature, String pId)` separately, so a
caller that DOES want the stricter behaviour for a `SCRIPT_CONSTRAINT` guard can check it first and
refuse client-side before calling `setSeverity`. Accept/reject stay in `ebx.ProposalReviews#decide`, not
duplicated here, per the task brief.

### 4. Attention rules: `staging.AttentionRules` (pure)

Chosen package: `staging`, not `review` - this is about the shape of a landing-to-staging mapping
itself (alongside `staging.StagingRules`/`staging.MappingFollowRule`/`staging.TechnicalKeyRule`, the
module's other pure rule engines over the mapping), not about the `ReviewLine` decision workflow
(`review` is about accept/reject/cascade of an already-built proposal).

Three flags, computed from `AttentionRules.MappingInput` (attribute id, landing column id, entity id,
status, `fillRatePercent`, `distinctCount`, `rowCount` - plain numbers, no EBX type):

- `CONSTANT_COLUMN` - the column holds one single distinct value over at least
  `AttentionRules.MIN_FILLED_ROWS_FOR_CONSTANT` (10) filled rows.
- `EMPTY_COLUMN` - zero filled rows.
- `DUPLICATE_FIELD` - within one entity, a landing column feeds two or more attributes through
  non-`REJECTED` mappings. The FIRST attribute to claim a shared column (in the caller's own input
  order) is its "owner" and is never flagged for it; an attribute is flagged only when EVERY one of its
  own columns is an already-owned, shared column (an attribute with at least one column nobody else
  uses is never flagged, whatever else it also shares) - this reads exactly as the task brief's own
  worked example (SAP `StreetName` feeding both `mailingStreet` and `streetName`: the later-claiming
  attribute is flagged, never both).

**Domain-free by construction** (PLAN.md, 2026-09-20: "domain knowledge comes from the language model,
Java only measures") - nothing here reads a column or attribute NAME. `AttentionRulesTest` proves this
directly: every rule is exercised twice, once with a "people" shape (SAP `BusinessPartnerCategory`,
`MobilePhone`, `StreetName`/`mailingStreet` - the task brief's own examples) and once with an unrelated
"retail article" shape (`unitOfMeasure`, `discontinuedReason`, `packSize`/`displaySize`), producing the
same flags for the same numbers.

### 5. Model change: `AttributeMapping.alternativeTo`

`tools/build_xsd.py`: `AttributeMapping` gained `alternativeTo`, an optional self-referencing foreign
key to `/root/AttributeMapping` ("this proposed mapping is suggested as a replacement for that one").
`MdmFactory.xsd` was regenerated with `python3 tools/build_xsd.py` (never hand-edited) -
`tools/test_local.sh`'s own "generated XSD matches committed XSD" `diff -q` check passes.
`core.FactoryPaths.AttributeMapping.ALTERNATIVE_TO` was added alongside the table's other field
constants. **Nothing writes this field in this lot** - a later lot (run orchestration/matrix UI) is
expected to set and read it, per the task brief.

### 6. Tests

`guard.GuardTest` and `staging.AttentionRulesTest`, JUnit-free (`main`, a failure throws - the same
convention every other test class of this module follows). `GuardTest` covers `Guard#label()`: the
kind+argument-digest shape and its truncation for `QUALITY`, the kind-verbatim shape for
`SCRIPT_CONSTRAINT`/`SCRIPT_DERIVATION`/`EXTERNAL_SERVICE`, and the empty-kind fallback.
`AttentionRulesTest` covers every one of the three flags, the `REJECTED`-mapping and never-profiled-column
degrade-gracefully cases, and `DUPLICATE_FIELD`'s ownership/independent-column/entity-scoping rules -
each with two unrelated domains (see section 4). `ebx.GuardReader`/`ebx.GuardWrites`/
`ebx.LandingColumnProfiler` are EBX-bound and have no unit test in this lot (same posture the rest of
this module already takes for its own EBX-bound readers/writers - `ebx.RelationChecker`,
`ebx.LandingPreview`, etc. carry no unit test either, only their pure counterparts do); they were
compiled and read carefully, not exercised against a live instance (see "What was NOT run live" below).

### 7. What was verified, and how

- `python3 tools/build_xsd.py` regenerates `MdmFactory.xsd` byte-for-byte identically to the committed
  file (`tools/test_local.sh`'s own `diff -q` check) and `xmllint --noout` accepts it.
- **The whole module compiles clean against this lot's new/changed files** - `ebx-mdm-factory/build.sh`
  with `EBX_LIB_DIR` pointed at the `ebx-ps-fasttrack-server/lib` of the `Organization` Fasttrack
  workspace (the path the task gave, `.../Fasttrack/Organization/Workspace/ebx-ps-fasttrack/
  ebx-ps-fasttrack-server/lib`, has `ebx-lib.jar` and was used as given) reports exactly 10 errors, every
  one of them in `ebx/MameMatchingApi.java` (9 errors, a missing
  `com.orchestranetworks.addon.mame.service.decisiontree.DecisionTreeExecutionResult` class and a
  missing `MatchingOperations.executeDecisionTree` method) and `ebx/MameSuspectDecision.java` (1 error, a
  missing `Group.acceptSuspectsAndMergeGroups(Set)` method) - **both files this lot never touched**
  (`git status` confirms), both pre-existing on `main` before this branch was created. None of this
  lot's 6 new/changed files raise a single error. This is a genuine, pre-existing environment gap, not
  a regression introduced here: every `ebx-addon-mame-core.jar` on this machine was checked (24 Fasttrack
  workspaces, `find $HOME/Fasttrack -iname ebx-addon-mame-core.jar`) and NONE of them
  declares the `decisiontree` package or that `Group` method - the module's own existing test suite
  already depends on both classes (`ebx/MatchingActivationGuardTest.java`,
  `ebx/MameMatchingApiTest.java`, `ebx/MatchPlanSimulationRunnerScoreMatchTest.java`,
  `ebx/MameSuspectDecisionTest.java`, `lineage/RecordLineagePageTest.java`,
  `workflow/MatchingRunTaskBeanTest.java`), so `tools/test_local.sh` cannot currently reach the test-run
  step for ANYONE on this machine, regardless of this lot. This blocks `tools/test_local.sh` from
  finishing green end-to-end in this session - out of scope to fix here (a different addon jar version is
  needed, and neither `MameMatchingApi.java` nor `MameSuspectDecision.java` are files this lot may touch).
- **Every pure class this lot adds compiles and its tests run green in isolation**, bypassing the
  unrelated blocker above: `javac` against just `guard/Guard.java`, `staging/AttentionRules.java`,
  their two test classes, `review/ReviewLine.java` (and its own transitive pure dependencies) and
  `landing/TextLimits.java`, run with `java -ea` - `GuardTest OK`, `AttentionRulesTest OK`.
- `ebx.GuardReader`/`ebx.GuardWrites`/`ebx.LandingColumnProfiler` (and the pure `landing.ColumnProfileSql`)
  were confirmed error-free by the full-module compile above (zero errors attributed to any of them,
  among the module's ~260 other source files) - not run against a live instance.

### 8. What was NOT run live

The EBX instance was unreachable for this whole lot (`mcp__data-access__find_dataspace` answered
`ECONNREFUSED`) - nothing here was exercised against real data:

- `ebx.LandingColumnProfiler.profile` was never run against a real landing dataset (Personnes or
  otherwise). The `CHAR_LENGTH` function name is unverified (section 1).
- `ebx.GuardReader.read` was never run against the real `MdmFactory`/`Personnes` project data - the
  field paths and status filtering were checked by reading `ebx.ProposalReviews`'s own already-live-used
  code for the exact same tables, not by a live query.
- `ebx.GuardWrites.setSeverity`/`isApplied` were never called against a live row - `WizardData`'s own
  two delegate methods are already used live by the wizard (per PLAN.md's lot history), so the risk here
  is limited to the dispatch/refusal logic added on top, which was only read and compiled.
- Per the task rules, no write-capable tool was used against the instance and `tools/install.sh` was
  never run.

## Lot "matrix" - the Mapping matrix becomes the main review screen

Written 2026-09-24 on branch `feature/staging-proposal-matrix` (worktree `ebx-mdm-factory-sp-matrix`), from
the task brief (`docs/mockups/staging-proposal/Matrix.dc.html`, the validated spec = the mapping-matrix
mockup already implemented by `mappingmatrix.*`/`ebx.MappingMatrixReader`/`ebx.ui.MappingMatrixUserService`,
`docs/MAPPING-MATRIX.md`, plus six deltas). Builds on lot 0's shared base (`guard.Guard`/`ebx.GuardReader`/
`ebx.GuardWrites`/`staging.AttentionRules`/`AttributeMapping.alternativeTo`) - nothing in lot 0 was changed
here. Two other lots ("run", "designer") worked in parallel on their own files; this lot never touched
`ebx.ProposalReviews`, `wizard/*` or `feeddesigner/*`, and relies on `ProposalReviews.decide`'s own
cascade for `AttributeMapping.alternativeTo` (lot "run"'s contract) exactly as instructed.

### 1. "Rules and checks" column (delta 1)

`MatrixModel.Attr` gained `guards: List<Guard>` and `MatrixModel.Entity` gained `entityGuards: List<Guard>`
(no attribute of its own - `ebx.GuardReader.ENTITY_LEVEL`), read ONCE per project in
`MappingMatrixReader#read` (`GuardReader.read(pFactory, projectCode)`) and grouped onto the right
attribute/entity while building the entity tree - reusing `guard.Guard` directly rather than inventing a
parallel shape, since it is already pure and already carries every field the column needs (kind, argument,
severity, transferPolicy, status, origin, confidence, rationale, valuesSeen/valuesFailing/examples).

`MatrixHtml` renders one more grid column ("Rules and checks", a `NEW` tag) with one chip per guard of the
row's attribute (`mdmmGuardHead`/`mdmmGuards`/`mdmmGChip`), or "none proposed"; entity-level guards render as
a small line under the corner header. Clicking a chip selects it exactly like a cell (`mdmmSelectGuard`,
mirroring `mdmmSelect`) and fills the inspector from a hand-built `data-guard` JSON blob
(`MatrixHtml#guardJson`, same convention as `#cellJson`): kind label ("Quality rule" / "Check from the
script library" / "Computed field" for SCRIPT_DERIVATION), what it checks (a localized sentence built from
`Guard#kind`/`#argument`, `mappingMatrix.guards.what.*`), status/origin/confidence/rationale, measured
evidence (`valuesSeen`/`valuesFailing` when present, else `examples`), a "When a value fails" `<select>`
(WARN/REJECT) for QUALITY/SCRIPT_CONSTRAINT only (`MatrixCompute#guardHasSeverityControl`) posting
`SET_GUARD_SEVERITY`, and Accept/Reject (`mdmmGuardDecide`) when `MatrixCompute#canDecideGuard` (PROPOSED).
EXTERNAL_SERVICE/SCRIPT_DERIVATION show no severity control, per the task brief. Every decision goes through
the SAME `ProposalReviews.decide(session, factory, guard.nature, guard.id, ...)` the matrix already uses for
every other nature - `ebx.ui.MappingMatrixUserService#handle`'s new `ACCEPT_GUARD`/`REJECT_GUARD` actions
carry the guard's own nature as `mdmmRowId2` (the same two-row-id convention
`ACCEPT_ATTR_THEN_MAPPING` already established), and `SET_GUARD_SEVERITY` dispatches to
`ebx.GuardWrites#setSeverity` (never a second write path).

### 2. "Needs your eye" (delta 2)

`MatrixModel.Cell` gained `attentionFlag`/`attentionReason` (baked in by the reader, `""` = not flagged) and
a `isFlagged()` helper. `ebx.MappingMatrixReader#computeAttentionFlags` runs `staging.AttentionRules.detect`
ONCE per entity, across every feed's non-rejected `AttributeMapping` rows (`DUPLICATE_FIELD` needs the whole
entity's picture, per `AttentionRules`' own class Javadoc), fed with `LandingColumn.fillRate`/`distinctCount`
and the owning `LandingTable.lastLoadRows` (`docs/STAGING-PROPOSAL.md`'s own lot 0 section 1 names these as
the fill-rate/distinct-count/row-count signal) - keyed by `attributeId + "\u0001" + landingColumnId` so the
flag lands on the exact cell it belongs to, with no post-hoc rebuild of the otherwise-immutable Feed/Cell
tree (the key map is computed BEFORE any `Cell` is built, in one extra pass over the same `AttributeMapping`
rows `readFeed` reads anyway).

A flagged cell renders the mockup's own "attn" tone (amber dashed, `MatrixHtml`'s `.mdmmCell.attn`) instead
of its ACCEPTED/PROPOSED/REJECTED tone, with the reason as its inspector's own first paragraph
(`mappingMatrix.attention.constant`/`.empty`/`.duplicate`, `landing.TextLimits`-free short sentences built
in `MappingMatrixReader#attentionReasonText`). The field row gets a "Needs your eye" chip
(`MatrixCompute#attributeFlagged`: true when ANY of the attribute's own cells, any source, is flagged) and
the header/overview counters gained an `attention` counter (`MatrixCompute.FeedStats#attention`, summed the
same way `accepted`/`proposed`/`rejected` already are through `feedStats`/`entityStats`/`statsBySource`).
When a column was never profiled (`fillRate == null`), `AttentionRules.MappingInput#filledRows()` returns
null and `AttentionRules.detect` never fires for that row - no flag, per the task brief ("no misleading
flag").

### 3. Suggested fix (delta 3)

`MatrixModel.Cell` gained `alternative: Alternative` (mappingId, landing column, pipeline steps, sample,
rationale) - `ebx.MappingMatrixReader#readAlternative` looks up the first PROPOSED `AttributeMapping` whose
`alternativeTo` names the cell's own mapping id, resolves its column/pipeline/sample the SAME way the cell's
own data is resolved (reusing `#resolvedPipeline`/`#splitPipeline`/`#sample`, never a second copy), and
degrades to `null` on any failure (a suggestion is a nice-to-have, never worth breaking the cell over). The
inspector shows it ("Suggested: use `<column>` instead", the pipeline, sample before/after, rationale) with
a primary button (`mdmmAcceptAlternative`) that posts `ACCEPT_ALTERNATIVE` = an ordinary MAPPING accept
through `ProposalReviews.decide` - lot "run"'s own cascade (accepting a mapping with `alternativeTo` rejects
the mapping it replaces) does the rest; this lot never touches that logic, only calls `decide` exactly as it
already does for every other mapping accept.

For a field flagged `EMPTY_COLUMN` on every source that covers it (`MatrixCompute#attributeEmptyEverywhere`)
the inspector offers "Remove the field" (`mdmmRemoveField`, reuses the existing `REJECT_ATTRIBUTE` action -
no new write path). For `DUPLICATE_FIELD` the inspector offers "Remove the N duplicate fields"
(`MatrixCompute#duplicateFieldAttributeIds`, `mdmmRemoveDuplicates` -> `REMOVE_DUPLICATES`,
`MappingMatrixUserService#removeDuplicates` re-reads the entity fresh and rejects every attribute the helper
names, through `ProposalReviews.decide` in a loop - the same "re-read fresh, never trust what was rendered"
rule every other decision on this screen already follows). Every one of these re-checks `canEdit()` the same
way `handle()` already does for every action (delta 3's three fix buttons are ordinary actions dispatched
from `handle`, not a special path).

### 4. Bulk "accept what is not flagged" (delta 4)

`MatrixCompute#planEntityBulk`/`#planFeedBulk` now skip a FLAGGED attribute entirely (never the attribute,
never its guards, never its mappings - `MatrixCompute#attributeFlagged`), and fold each non-flagged
attribute's own PROPOSED guards into the plan right after that attribute (before the feed/mapping rows,
matching the task brief's "after their field"); entity-level guards (no attribute of its own, so never
"flagged" the same way) are always included. The threshold behaviour is unchanged - every row still goes
through the SAME `WizardRules#bulkEligibility` (`MatrixCompute#addIfEligible`), so "accept what is not
flagged" COMBINES with the existing confidence threshold: a non-flagged row below the threshold is still
left out and counted (`BulkPlan#leftOut`/`#lowestLeftOutConfidence`), exactly as before this lot.

`MatrixCompute#planProjectBulk` concatenates every entity's own `planEntityBulk` - the footer's new
"Accept the N unflagged proposals" button (`mdmmBulkProject` -> `BULK_PROJECT`,
`MappingMatrixUserService#bulkProject` re-reads the WHOLE project fresh before planning, never trusts what
was rendered). `MatrixCompute#readyToLoad` gates the footer's "Load the staging ->" link: enabled only when
every entity's own `entityStats.proposed`/`.attention` are both zero AND every entity's own
`entityGuardStats.proposed` is zero - a SHORTCUT this screen offers, not the wizard's real gate (the task
brief's own wording); the link reuses the exact same Close button
`ebx.ui.FactoryHtml#originReturnScript` already rewires to return to the wizard (`mdmmLoadStaging` finds
`.ebx_ActionsForward`'s own last button and clicks it) rather than re-deriving a second "go back to the
wizard" mechanism - a deliberate simplification, named here: it does not itself trigger a "load the
staging" action, it only navigates back to the wizard step 4/5, which keeps its own real gate.

### 5. Guidance (delta 5)

A three-step banner (`MatrixHtml#renderGuidance`, `mdmmGuide`) with live counts from
`MatrixCompute.ProjectSummary`/`#summarize` (business objects, sources, field links = `Cell#isData()` count,
rules = QUALITY guard count, checks = the other three natures, and "attention points" = one per distinct
(entity, attribute) pair with at least one flagged cell - a FIELD-level count, not a per-cell or per-ISSUE
one; **documented simplification**: the validated mockup's own worked example groups by issue ("a suspicious
link, an empty field, duplicate fields" = 3), which this lot cannot reconstruct without inventing an
issue-clustering algorithm the task brief never asked for - the field-level count is a defensible
approximation, flagged here rather than silently claimed as exact), a one-sentence summary
(`mappingMatrix.summary`) and the glossary line (`mappingMatrix.glossary`, using the vocabulary rule's own
"business object"/"source link"/"field"/"rule / check" terms).

Every English string `MatrixHtml#script()`'s interaction JS used to hard-code (delta 5's own explicit ask)
now comes from the bundle: `script(Locale)` builds one small JS object literal (`var T={...}`) from
`mappingMatrix.js.*` keys at render time (`MatrixHtml#jsStringsObject`), and every place the script used to
concatenate a literal English string now reads `T.xxx` instead - verified directly
(`MatrixHtmlTest#scriptStringsComeFromTheBundleNotHardcodedEnglish`: the French script never contains the
English `'Accept'` string and does contain `'Accepter'`). The renamed vocabulary itself (business object /
source link / field / rules and checks) was applied to every NEW string this lot added; the ~70 pre-existing
`mappingMatrix.*` keys from the mapping-matrix lot (entity/attribute/feed wording) were **not** swept and
renamed - out of scope for this lot's own time budget, named here as a known gap rather than silently left
inconsistent.

### 6. Mode switch Matrix | Designer (delta 6)

A plain segmented control (`MatrixHtml#renderModeSwitch`, `.mdmmSeg`) next to the title: "Matrix" (always
"on", this screen), "Designer" - `core.FeedDesignerLink#url` on the active entity's own ROOT feed (the only
kind that link accepts, `MatrixModel.Feed` class Javadoc; a `parentFeedId == null` feed). Same "no return
token, voluntary departure only" simplification the inspector's own "Open this feed in the Designer" link
already carries (fix/mapping-matrix-live sec 10.6) - the class `mdmf-matrix-designer-link` is armed the same
way by `ebx.ui.MappingMatrixUserService#write`'s existing `FactoryHtml.voluntaryLeaveScript` call, no new
wiring needed. When the active entity has no root feed at all (e.g. no feed yet), the control shows a plain,
non-clickable "Designer" label instead of a dead link.

### 7. Files changed / added

`mappingmatrix/MatrixModel.java` (`Attr.guards`, `Entity.entityGuards`, `Cell.attentionFlag`/`attentionReason`/
`alternative`, new `Alternative` class - every addition backward-compatible via delegating constructors, so
every pre-existing call site in this module and its tests kept compiling unchanged), `mappingmatrix/
MatrixCompute.java` (`attributeFlagged`/`attributeEmptyEverywhere`/`duplicateFieldAttributeIds`,
`GuardStats`/`guardStats`/`entityGuardStats`/`canDecideGuard`/`guardHasSeverityControl`, `planEntityBulk`/
`planFeedBulk` skip-flagged-fold-in-guards, `planProjectBulk`, `readyToLoad`, `ProjectSummary`/`summarize`),
`mappingmatrix/MatrixHtml.java` (guard column/chips/inspector, attention tone/reason, suggested-fix
rendering, guidance banner, footer bar, mode switch, `script(Locale)` + `jsStringsObject`),
`ebx/MappingMatrixReader.java` (guards read via `GuardReader`, `computeAttentionFlags`, `readAlternative`,
`LandingTable.lastLoadRows` map, `doubleOrNull`/`longOrNull` helpers), `ebx/ui/MappingMatrixUserService.java`
(`ACCEPT_GUARD`/`REJECT_GUARD`/`SET_GUARD_SEVERITY`/`ACCEPT_ALTERNATIVE`/`REMOVE_DUPLICATES`/`BULK_PROJECT`
actions, `setGuardSeverity`/`removeDuplicates`/`bulkProject`), `Messages_en/fr.properties` (one contiguous
`mappingMatrix.*` block per file, right after the existing block). Tests extended, not replaced:
`MatrixComputeTest` gained `attributeFlaggedWhenAnyCellIsFlagged`,
`entityBulkSkipsAFlaggedFieldEntirelyAndFoldsInItsGuards`, `feedBulkAlsoSkipsFlaggedFields`,
`guardStatsCountsByStatusAndNature`, `planProjectBulkConcatenatesEveryEntity`,
`readyToLoadIsFalseUntilNothingIsProposedOrFlagged`, `duplicateAndEmptyEverywhereHelpers`; `MatrixHtmlTest`
gained `flaggedCellGetsAttentionToneAndRowChip`, `guardColumnRendersChipsOrNoneProposed`,
`suggestedFixDataIsEmbeddedOnTheFlaggedCell`, `scriptStringsComeFromTheBundleNotHardcodedEnglish`.

### 8. What was verified, and how

`tools/test_local.sh` (`ON_EBX_AGENT_JAR`/`ON_EBX_MAPPING_JAR` pointed at this machine's own build outputs,
`EBX_LIB_DIR` left at its default) - the whole module compiles clean against this lot's changed files (no
error attributed to any of them among the module's ~260+ source files) and every JUnit-free test class runs
green, "all green" including every test named above. `Messages_en/fr.properties` pass the script's own
non-ASCII check (every accented/special character written as `\uXXXX`, including `·`/`→` - caught
and fixed once during this lot: an editing pass had written the literal UTF-8 characters instead of the
escape sequence, which `tools/test_local.sh`'s own ASCII check exists specifically to catch).

### 9. What was NOT run live

Nothing in this lot was opened in a browser or exercised against a live EBX instance - the task forbids
`tools/install.sh`/Tomcat from this worktree. In particular, never verified live:

- `ebx.MappingMatrixReader#computeAttentionFlags`/`#readAlternative`/the `GuardReader.read` call against the
  real `MdmFactory`/`Personnes` project data - the field paths and the "one pass before building any cell"
  key-matching scheme were only read and compiled, not run against real `LandingColumn.fillRate`/
  `distinctCount`/`LandingTable.lastLoadRows` values (which lot 0's own section 8 already flagged as
  unmeasured - `ebx.LandingColumnProfiler.profile` was never run live either, so this lot's own attention
  flags depend on numbers nobody has confirmed are non-null on the real project yet).
- The guard chip's severity `<select>` posting `SET_GUARD_SEVERITY` -> `ebx.GuardWrites#setSeverity` -> the
  underlying `WizardData` writes: those two `WizardData` methods are already used live by the wizard (lot
  0's own section 3), but the dispatch/JS-select wiring added here was only compiled and read.
- The mode-switch Designer link and the footer's "Load the staging" Close-button reuse: both build on
  already-live-proven mechanisms (`core.FeedDesignerLink`, `FactoryHtml.originReturnScript`) reused verbatim,
  but neither was clicked in a real browser from this screen.
- Whether the guidance banner's field-level "attention points" count reads as expected against the real
  Personnes project (2 sources, 2 entities, the task brief's own worked example) was reasoned through, not
  observed - the count's own definition is a documented approximation (section 5 above).

### 12. Vocabulary sweep (2026-09-24, same branch, coordinator: "the core of the product owner's complaint")

Section 5 above only swept the LOT's OWN new `mappingMatrix.*` keys; the ~70 pre-existing keys from the
mapping-matrix lot still said "entity"/"attribute"/"feed" in their VALUES. Fixed here, same branch: every
user-facing VALUE of every `mappingMatrix.*` key, in both `Messages_en.properties` and
`Messages_fr.properties`, now says "business object" (FR "objet métier"), "field" (FR "champ") and
"source link" (FR "lien de source") - **key NAMES are untouched** (`mappingMatrix.entity.accept`,
`.attribute.accept`, `.feed.accept`, `mappingMatrix.bulk.entity`, `.bulk.feed`, `mappingMatrix.param.entity.*`
all still spelled with the old technical name, only their VALUE changed), so no Java code, no
`MatrixMessages.get(...)`/`Messages.get(...)` call site, needed to change. The technical word is now kept in
exactly ONE place per file, `mappingMatrix.glossary` - `"Business object (entity) ... Source link (feed) ...
Field (attribute) ..."` / the FR equivalent with `(entité)`/`(flux)`/`(attribut)` - satisfying the common
brief's own "keep the technical word once in a glossary line" (the validated mockup's own glossary sentence
never shows the technical word at all - the common brief's instruction, not the mockup, drove this).

23 distinct EN keys and 18 distinct FR keys had their value changed (the FR count is lower because the
"staging-proposal matrix lot" block's own newer keys - `.attention.*`, `.guards.*`, `.mode.*`, `.summary`,
`.guide.*`, `.footer.*`, most of `.js.*` - already used the right vocabulary when section 1-6 above first
wrote them; only `mappingMatrix.js.acceptEntityFirst`/`.acceptAttrThenMapping`/`.feedRejected`/
`.openDesigner`/`.confidence` and `.glossary` needed a second pass there). `MatrixHtml`/
`MappingMatrixUserService` were re-audited for hard-coded English text outside the bundle: none was found in
`MappingMatrixUserService` (every user-facing string already goes through `Messages.get`); `MatrixHtml` had
none either past the earlier `script(Locale)`/`jsStringsObject` work (section 5), except two latent spacing
bugs found and fixed while auditing - `mappingMatrix.js.feedRejected`/`.confidence`/`.suggestedUse` had relied
on a TRAILING space in the `.properties` VALUE to separate the label from the following text; an earlier edit
of this same lot had silently lost those trailing spaces (a `.properties`-file editing tool trimmed them),
and Java's own `Properties`/`ResourceBundle` loader strips a LEADING space right after `=` too (by the
format's own spec) - so the fix moves the separating space into the JS concatenation itself
(`T.feedRejected+' '+data.feedComment`, etc., `MatrixHtml#script`) rather than relying on invisible
trailing/leading whitespace in a properties value ever surviving an edit.

Verified: `tools/test_local.sh` with
`ON_EBX_AGENT_JAR=$HOME/Dev/wt/ebx-connectors-deploy/ebx-connectors/dist/on-ebx-agent.jar`
(no `EBX_LIB_DIR`, the script's own default) and
`ON_EBX_MAPPING_JAR=$HOME/Dev/wt/ebx-connectors-mapping-jar/ebx-connectors/dist/on-ebx-mapping.jar`
(needed because `ebx.MappingMatrixReader`/`ebx.StagingLoader` import `com.on.ebx.connect.core.*` from
`on-ebx-mapping.jar`, which is not under `server/lib` and has no default path in the script - without it the
compile step fails immediately with "on-ebx-mapping.jar not found: run build-mapping.sh in the
ebx-connectors repository, or set ON_EBX_MAPPING_JAR", before reaching any test) - "all green". Not run live:
same posture as the rest of this lot (section 9 above) - the renamed wording was read, never seen rendered
in a browser.

## Lot "designer" - the Feed designer becomes the second review mode

Written 2026-09-24 on branch `feature/staging-proposal-designer` (worktree
`ebx-mdm-factory-sp-designer`), against lot 0 above and the validated mockup
`docs/mockups/staging-proposal/Designer.dc.html`: rule/check badges on the designer's target tree, a field
inspector with immediate guard/suggested-fix decisions, red-dashed "needs your eye" links, green-dashed
suggested-fix links, and a Matrix/Designer mode-switch control - the designer's five deltas over the
already-shipped Feed designer (`docs/FEED-DESIGNER.md`). Full detail, including what was and was not
verified, lives in **`docs/FEED-DESIGNER.md` sec 10** (kept there rather than duplicated here, since it is
a change to one already-documented screen, the same convention the "designer proposed-targets" bug fix
earlier in that file already follows) - this section is only the index entry the other lots' own sections
of this file already give one another.

Files touched: `ebx/ui/FeedDesignerUserService.java` (guards/attention/suggestions in the model, the new
`onDecide` one-shot handler, the Matrix link), `www/common/jscripts/factory/feed-designer.js`,
`www/common/stylesheets/factory/feed-designer.css`, a new pure package `designer/` (`DesignerModel` +
`DesignerModelTest` - severity-option choices and the rule-vs-check classification, the one piece of new
logic worth its own unit test per the task brief), `FeedDesignerUserServiceTest` (unchanged - its one pure
method was not touched by this lot), and this lot's own `designer.*` block in `Messages_en.properties`/
`Messages_fr.properties`. Relies entirely on lot "run"'s extension of `ebx.ProposalReviews#decide` for both
named cascades (a rejected field's `PROPOSED` rules/checks; an accepted suggested-fix mapping rejecting the
one it replaces via `AttributeMapping.alternativeTo`) - neither is reimplemented here. Did not touch
`wizard/*`, `mappingmatrix/*`, or any file outside the list above. `tools/test_local.sh` runs green
end-to-end, including the two new `designer.DesignerModelTest` cases; nothing was run against a live EBX
instance this session (read-only `data-access` only, per the task brief).

## Lot run - one-click proposal run + wizard step 4 redesign

Written 2026-09-24 on branch `feature/staging-proposal-run` (worktree `ebx-mdm-factory-sp-run`), from the
common brief and lot 0's shared base. Builds the background orchestrator behind "Propose the staging" and
redesigns the wizard's step 4 around it, per `docs/mockups/staging-proposal/Main.dc.html`/`Running.dc.html`.

### 1. What, in one paragraph

`ebx.StagingProposalRun.start(...)` launches, on a small daemon executor, one background run per project:
the rule pass, the language-model structure pass (chained when its gate allows), `LandingColumnProfiler`,
a new anomaly pass (`staging.AnomalyLlmPass` + `ebx.AnomalyLlmSuggester`), and the quality-rule/capability
language-model passes now scoped over the freshly-PROPOSED structure (`staging.MappingScope.PROPOSAL`,
new). Progress is a `staging.ProposalProgress` JSON written into the parent `PipelineStep`
(`STAGING_PROPOSAL`) as each stage starts and ends; `ebx.StagingProposalRun.status(...)` reads it back.
The wizard's step 4 (`wizard.WizardPage`) gained an intro/running/done sequence in front of the
pre-existing per-pass buttons and entity cards, which move under an "Advanced" `<details>` disclosure
(kept working, not replaced). `ebx.ProposalReviews.decide` gained four cascades the task brief asked for:
rejecting a field also rejects its own PROPOSED quality rules/capability suggestions; rejecting a mapping
rejects its own PROPOSED `MappingStep`s; rejecting a business object sweeps its non-technical fields too
(each then cascaded the same way); accepting a mapping with `alternativeTo` rejects the mapping it
replaces. The structure language-model pass (`staging.StagingLlmPass`) now requires a genuine per-field/
per-rule rationale and confidence instead of copying the owning entity's/feed's onto every one of its
children.

### 2. API surface

- `staging.ProposalProgress` - pure. `newRun()` (six stages, all `TODO`), `start`/`finish`/`skip`,
  `isOver()`, `toJson()`/`parse(String)` (never throws - garbage/null/blank all come back as `newRun()`'s
  own shape). Stage keys `STRUCTURE_RULE`/`STRUCTURE_LLM`/`PROFILE`/`ANOMALY`/`QUALITY`/`CAPABILITY`,
  states `TODO`/`RUNNING`/`DONE`/`FAILED`/`SKIPPED`.
- `staging.MappingScope` - pure enum, `ACCEPTED_ONLY`/`PROPOSAL`, `includes(status)`.
  `ebx.QualitySuggester.readAcceptedMappings(factory, projectId)` is now a thin `ACCEPTED_ONLY` wrapper
  over a new `readMappings(factory, projectId, scope)`; `ebx.QualityLlmSuggester.suggest`/
  `ebx.CapabilityLlmSuggester.suggest` each gained a `MappingScope` overload (the old signature keeps
  `ACCEPTED_ONLY`, unchanged for every existing caller/REST route).
- `staging.AnomalyLlmPass` + `ebx.AnomalyLlmSuggester.suggest(session, factory, projectCode)` - one
  `AgentRuntime` call per flagged business object (never per flag), tool `propose_replacement` only (no
  "remove" tool: the task brief's "do not write anything, the UI offers reject" is honoured by the model
  simply not calling the tool for that flag). A candidate replacement column is measured before it is ever
  offered to the model (`ebx.AnomalyLlmSuggester.unusedColumnsOf`: excludes the flagged column itself,
  every column already used by ANY non-rejected mapping of the project, and any column with a null/zero
  fill rate), so "measure the proposal before writing it" is enforced by construction, not by a runtime
  check on the model's own answer. A replacement is written as a NEW `AttributeMapping` (origin `LLM`,
  status `PROPOSED`, same `feed` as the flagged mapping, `alternativeTo` = the flagged mapping's id) -
  the flagged mapping itself is never touched. `AttentionRules.DUPLICATE_FIELD` flags are counted
  (`AnomalyLlmSuggester.Result#duplicateFieldFlags`) but never sent to the model - no useful "replacement"
  question to ask about a shared column.
- `ebx.StagingProposalRun` - `start`/`status`, both EBX-bound. See section 3 for the session question.

### 3. The open question: does a captured `Session` survive its own HTTP request?

Every other language-model pass in this module runs synchronously inside the postback that starts it.
This lot is the first to keep a `Session` past the end of its request (the background `Runnable` closes
over the caller's `Session`, per the task brief - no agent account, matching `connectors-in-jvm`). **This
was not verified against a live EBX instance** (no write-capable/live tool was used this session, and the
brief's own read-only-MCP rule would not have let a background thread be exercised anyway). Two lines of
defence instead of a live check: (a) the whole background body is one `try/finally` that always ends the
parent `PipelineStep` - a session EBX refuses outright is expected to fail on the very first EBX call
inside `runInBackground`, caught by the outermost `catch (Exception)`, which marks `STRUCTURE_RULE`
`FAILED` with the exception's own message rather than hanging with no error anywhere (the CLAUDE.md
section 13 "stuck workflow instance" failure mode this lot deliberately tries not to reproduce); (b) a
parent step still `RUNNING` past `StagingProposalRun.STALE_AFTER_MINUTES` (30) is reported `stale` by
`status` and a fresh `start` for the same project is allowed to supersede it (the stale row is closed
`FAILED` first, synchronously, before the new run begins). If the session turns out NOT to survive on the
real instance, every stage would show `FAILED` with whatever exception EBX throws on the first call after
the request ends - loud, not silent, but this specific behaviour needs a live run to actually confirm.

### 4. Module startup/shutdown

`StagingProposalRun.EXECUTOR` is a lazily-constructed `Executors.newCachedThreadPool` with a daemon
`ThreadFactory` (threads named `ebx-mdm-factory-staging-proposal-N`) - deliberately NOT wired into
`ebx.FactoryModuleRegistration`'s `handleRepositoryStartup`/any shutdown hook, to avoid touching a file
every other staging-proposal lot also needs (merge-conflict risk across three parallel worktrees). Daemon
threads mean the JVM/Tomcat can stop without an explicit `executor.shutdown()` - a mid-run background task
is simply abandoned (no different from a Tomcat restart mid-transaction anywhere else in this module); the
NEXT `start()` for that project sees the old row `stale` (once 30 minutes have passed) or, if restarted
sooner, still `RUNNING` and refuses a concurrent run until either the 30 minutes pass or the row is
corrected by hand. A real graceful-shutdown hook (`executor.shutdownNow()` on `handleRepositoryShutdown`,
if `ModuleRegistrationListener` has one) would be a small, safe follow-up once the three staging-proposal
worktrees have merged and `FactoryModuleRegistration.java` is no longer contended.

### 5. Wizard UI (`wizard.WizardPage`/`ebx.ui.ProjectWizardUserService`, `wizard.js`, `wizard.css`)

- Intro (no run yet): plain-language explanation, the three-item list of what one run proposes, a
  glossary line, and the primary "Propose the staging" button with three checkboxes (structure always on
  and disabled-checked; quality; checks) - `WizardPage.renderProposalRun`/`renderProposalRunForm`.
- Running: `WizardPage.renderProposalRunning` - the six stages with state/duration/one-line summary, an
  overall progress bar, "you can leave this page". The wizard root renders
  `data-mdmf-proposal-running="true"` only in this state; `wizard.js` reads it once at load and, if set,
  resubmits the form after 5s with a harmless no-op action (`STAGING_PROPOSAL_POLL`, handled server-side
  by simply returning - `model()` always reads `StagingProposalRun.status` fresh) - a full page reload
  each time, no client-side interval/cancellation logic needed since the next reload re-decides whether to
  poll again. No "Stop" button - not required by the task brief; not built.
- Done (a run exists and is not running): `WizardPage.renderProposalDone` - a summary line with counts
  (business objects/fields from `WizardSnapshot.stagingEntities`, SUPERSEDED excluded, same rule the
  entity cards already applied; field links/quality rules/checks from the SAME `proposedCounts` map the
  "waiting for review" header already reads - one source of truth, nothing re-measured) plus the anomaly
  stage's own one-line summary as "points that need your eye" (a real, measured count from
  `AnomalyLlmSuggester.Result`, not a number invented at render time), and two buttons: "Review in the
  Matrix" (`core.MappingMatrixLink.url` for the FIRST non-SUPERSEDED business object) and "Review in the
  Designer" (`core.FeedDesignerLink.url` for that entity's first ROOT feed) - both empty/hidden when there
  is no entity yet. A smaller "Run again" button (same form, same checkboxes) sits below.
- Everything the pre-existing screen offered (`STAGING_SUGGEST`/`STAGING_SUGGEST_LLM`, the entity cards,
  the threshold-based bulk accept/reject, the separate quality/capability suggest buttons) is unchanged in
  behaviour, only moved inside `<details class="mdmf-disclosure"><summary>Advanced</summary>...`.
- Vocabulary: new user-facing text uses "business object"/"field"/"source link"/"rules and checks" per the
  common brief; the technical words stay in the glossary line and in code/Javadoc.
- Gate reuse, a deliberate simplification: `STAGING_PROPOSAL_RUN`'s button reuses
  `WizardRules.ActionGates#suggestStaging` (the same "is there a landing to work over" gate the old
  primary button used) rather than adding a new `WizardRules` gate - no new pure gate function needed
  since the run's own per-stage LLM/admin gating happens inside `StagingProposalRun` itself (mirroring
  `WizardRules.actionGates(...).suggestStagingLlm` for stage (b) specifically, a plain
  administrator+connector check for stages (d)/(e)/(f)). `WizardRulesTest` was not extended, since no new
  `WizardRules` function was added - the task brief's "WizardRules gates must stay pure and tested" is
  satisfied by leaving the existing, already-tested gates alone rather than duplicating their logic.

### 6. What was verified, and how

`ON_EBX_AGENT_JAR=.../on-ebx-agent.jar ON_EBX_MAPPING_JAR=.../on-ebx-mapping.jar tools/test_local.sh`
(both jars built in sibling worktrees, per this session's own setup - the shared worktree the common
brief names for the agent jar did not have the mapping jar, so a second sibling worktree's `dist/` was
used for it) ends **"all green"**: the XSD regenerates identically, every `.properties` file stays plain
ASCII, no CSS file lost a comment-balance check, the whole module (~1060 classes) compiles clean, and
every `*Test.java` under `src/test` runs, including the ones this lot adds/extends -
`ProposalProgressTest` (new: stage transitions, `isOver`, JSON round-trip, `parse` on null/blank/garbage)
and `AnomalyLlmPassTest` (new: the `propose_replacement` tool's happy path and every refusal - unknown
attribute, a column not in the given unused list, a blank rationale, confidence clamping - and that the
rendered user prompt actually names the flagged attribute/kind/unused column). No existing test needed
a behaviour change.

### 7. What was NOT run live

No live EBX instance was reachable/used this session (read-only MCP tools only, per the task rules, and
this lot's own work is write-capable by nature - a background orchestrator, cascading decisions - so even
the read-only tools would not have exercised it meaningfully). In particular, never run against real data:

- The session-survives-a-background-thread question itself (section 3) - the single biggest unknown of
  this lot.
- `ebx.AnomalyLlmSuggester` end to end - whether the model, given real flagged columns and real unused-
  column shapes, actually proposes sensible replacements (the worked example in the task brief, SAP
  `BusinessPartnerCategory`/`Sex`, was used to shape the prompt and the test fixtures, never run for real).
- `ebx.StagingProposalRun`'s full six-stage chain on a real project (Personnes or otherwise) - stage
  sequencing, skip reasons, and the parent step's `detail` JSON were only exercised through
  `ProposalProgressTest`'s pure model, never through a real run.
- The four `ProposalReviews.decide` cascades added in section 1 (guards-of-a-rejected-field, mapping-
  steps-of-a-rejected-mapping, fields-of-a-rejected-entity, alternativeTo-rejects-the-original) - read and
  compiled against the existing cascade machinery (`MappingFollowRule`, `TechnicalKeyRule`,
  `GuardReader`'s own field-path conventions), never exercised against live rows.
- The wizard's new step 4 screens (intro/running/done, the polling reload, the Advanced disclosure) -
  never rendered in a browser or driven through a live Tomcat; only `WizardPageTest`/`WizardMessagesTest`/
  `WizardStyleDiagramTest` (pure, string-level) exercise any of this file.
- The per-field-rationale change to `staging.StagingLlmPass` - the schema/prompt/parsing changes compile
  and the existing `StagingLlmPassTest` still passes (it does not assert on per-field rationale text,
  since that test predates this lot), but no real language-model call was made to see whether it actually
  follows the new per-field instruction rather than repeating itself across fields anyway.

### 8. Guesses and judgement calls worth flagging

- Per-field rationale/confidence are validated by falling back to the entity's/feed's own value when the
  model leaves one blank, rather than refusing the whole `propose_target_entity`/`propose_feed` call the
  way a blank ENTITY-level rationale is refused - a deliberate choice to avoid a new failure mode that
  could not be tested live in this session; "never repeat the parent's rationale" is enforced by the
  schema/prompt wording and by no longer defaulting to it in the happy path, not by a hard runtime block.
- `ebx.AnomalyLlmSuggester` caps the unused-column list offered to the model at 30 per flag - a defensive
  bound on prompt size, not a product rule; not measured against a real wide table.
- The "done" screen's counts intentionally reuse `WizardSnapshot.proposedCounts` (mapping/quality/script-
  constraint) rather than re-deriving anything from `ProposalProgress`'s own stage summaries, so they stay
  exactly in step with the pre-existing "N proposal(s) waiting for review" header - but this means the
  count is "proposals currently PROPOSED project-wide", not strictly "written by the last run" (a prior
  run's still-undecided proposals are included too). Not flagged as a bug, just not literally what the
  mockup's wording might imply.

## Live fixes (branch `fix/staging-proposal-live`, 2026-09-24)

Bugs found running the redesign LIVE on project Personnes, fixed on this branch (`tools/test_local.sh`
green after each). Bugs 1-4 measured directly; 5-8 from the coordinator's own live observations passed
through mid-session.

1. **Landing row count read from three different places.** `ebx.AnomalyLlmSuggester`/`ebx.ui.
   FeedDesignerUserService` read `LandingTable.sourceRowCount` (populated only by a live-database catalog
   scan - null for a sample-file source), `ebx.MappingMatrixReader` read `LandingTable.lastLoadRows` alone -
   the three silently disagreed, so `staging.AttentionRules` never fired on Personnes (sourceRowCount null).
   New `ebx.LandingRowCounts.rowCountOf(Adaptation)` (thin EBX wrapper) delegates to pure `landing.
   RowCountFallback.pick(lastLoadRows, sourceRowCount)` - `lastLoadRows` first (right once a source has
   loaded, sample-file included; no new field, see its own Javadoc for why persisting the profiler's own
   `COUNT(*)` back to it would be redundant), `sourceRowCount` as the only fallback for a live-database
   source profiled before its first load. Used in all three readers. `landing.RowCountFallbackTest` (pure).
2. **Raw message key shown.** `ebx.StagingProposalRun`'s STRUCTURE_LLM skip passed `gates.suggestStagingLlm.
   reasonKey` (a `wizard.gate.*` bundle key) straight into `ProposalProgress.Stage#summary`, which every
   other call site fills with already-formatted plain text (its own contract) - the run screen rendered the
   raw key. Resolved at WRITE time with the launching session's own locale (`WizardMessages.get`, the same
   utility every `reasonKey` elsewhere in the wizard already goes through) - not at render time, since
   `WizardPage`'s stage rendering only ever escapes and prints `stage.summary` already, for every other
   stage. `WizardMessagesTest.proposalRunSkipReasonKeyResolvesInBothLocales`.
3. **Auto-refresh while RUNNING.** The 5s self-poll (`wizard.js`, `data-mdmf-proposal-running`) was already
   correctly emitted and wired - verified statically. What was missing: a RUNNING stage showed no elapsed
   time at all (only DONE/FAILED, which carry `endedAt`, showed a duration) - the same "did it actually do
   anything" confusion `wizard.track.runningSince` already fixes for a project-level pipeline step, reused
   verbatim in `WizardPage#renderProposalRunning` for a RUNNING stage. `WizardPageTest.
   runningProposalStageShowsElapsedMinutesAndArmsThePoll`.
4. **PROPOSAL scope over-included SUPERSEDED/REJECTED entities.** Measured live: the QUALITY pass ran over 4
   business objects instead of 2 - the 2 SUPERSEDED rule-pass entities `StagingLlmSuggester`'s canonical
   merge had already absorbed (status written even though `StagingEntity.status`'s own XSD enum never
   declares `SUPERSEDED` - a non-blocking static enumeration, per the knowledge base) still got 6/3 PROPOSED
   `QualityRule`s each, two wasted ~30k-token agent runs. New `staging.MappingScope#eligible(mappingStatus,
   entityStatus, attributeStatus, feedStatus)`: the mapping's own status via the pre-existing `#includes`,
   plus, for `PROPOSAL` only, entity/attribute must not be REJECTED/SUPERSEDED and the feed must not be
   REJECTED - `ACCEPTED_ONLY`'s behaviour is unchanged (task brief). Applied in `ebx.QualitySuggester.
   readMappings` (covers `QualityLlmSuggester` and `CapabilityLlmSuggester`, both already routed through it)
   and directly in `ebx.AnomalyLlmSuggester`'s own separate reader (no `MappingScope` parameter of its own -
   always PROPOSAL-like). `staging.MappingScopeTest` (pure): entity SUPERSEDED, attribute REJECTED, feed
   REJECTED, `ACCEPTED_ONLY` unchanged.
5. **Flagged cell indistinguishable from a plain proposed cell.** `MatrixHtml#toneClass` mapped every
   PROPOSED cell to tone `warn`, styled identically (amber, dashed) to the `attn` tone a flagged cell also
   got - a flagged `PROPOSED` cell (class `mdmmCell attn todo`) looked exactly like an ordinary one, only the
   inspector showed why. `toneClass` now returns a new `prop` tone for a plain PROPOSED cell, styled neutral
   white/grey per the mockup's own `.cell.prop` (`.attn` keeps the amber-dashed look, now genuinely
   distinct); a flagged cell's second line now shows its `attentionReason` (already precomputed, localized)
   instead of the pipeline, so the reason is visible in the grid, not only in the inspector.
   `MatrixHtmlTest.rendersOneMatrixColumnPerFeedWithToneClasses` updated (`mdmmCell prop`).
6. **Footer/banner attention counts disagreed.** The footer's "N flagged proposal(s) left" used `BulkPlan.
   leftOut`, which only ever counts a PROPOSED row skipped for being BELOW THE CONFIDENCE THRESHOLD
   (`MatrixCompute#addIfEligible`) - a flagged (`AttentionRules`) attribute is skipped even earlier in
   `planEntityBulk` and never reaches `addIfEligible`, so it was never counted there, while the guidance
   banner's `ProjectSummary#attentionPoints` (same `MatrixCompute.summarize`) correctly counted it. The two
   sets are disjoint by construction (a flagged attribute's mappings are skipped entirely before
   `addIfEligible` ever runs on them), so `renderFooter` now sums both - everything genuinely left for a
   person to decide one at a time, whichever reason put it there.
7. **Overview chips overlap.** `MatrixCompute#chipT(entityIndex, entityCount)` gave every SOURCE feeding the
   same entity the identical `t` (hence the identical x) - measured live on Personnes (2 sources, 2
   entities): both Personne curves' chips at the same x, the Adresse ones overlapping. New 4-arg overload
   `chipT(entityIndex, entityCount, sourceIndex, sourceCount)` additionally spreads each source's chip within
   its entity's own band (jitter capped at 35% of the spacing between two consecutive entities' base `t`, so
   the total per-entity spread never exceeds 70% of that spacing - never bleeds into a neighbouring entity's
   band). `MatrixComputeTest.chipPositionsSpreadAcrossSourcesOfTheSameEntity` (2/5 sources x 2/6 entities:
   every pair gets its own x, and stays closer to its own entity's base `t` than to any other entity's).
8. **Capability pass duplicated a quality rule.** Measured live: for `Personne.email` the quality pass
   proposed a PATTERN rule AND the capability pass proposed `Fasttrack.lib.validator.email.isValid` as a
   SCRIPT_CONSTRAINT - the same check twice (same for `phone`). Root cause: `ebx.CapabilityLlmSuggester.
   addQualityRules` only ever showed ACCEPTED-or-APPLIED rules to the model, but under `MappingScope.
   PROPOSAL` the QUALITY stage (which runs first, per `ebx.StagingProposalRun`'s own stage order) had already
   written its own PROPOSED rule on the same field - invisible to the capability pass. `addQualityRules` now
   takes the same `MappingScope` and uses `pScope.includes(status)`, so a PROPOSED rule is visible under
   `PROPOSAL` (and `ACCEPTED_ONLY` is unchanged, same discipline as bug 4) - the prompt's own label changed
   from "Accepted quality rules" to "Quality rules already on this field (accepted or proposed)" to match.
   `capability.CapabilityLlmPass#systemPrompt` gained a generic (no domain example) instruction: never
   propose a SCRIPT_CONSTRAINT checking the same thing an existing rule on that field already checks, unless
   it says explicitly what the new check adds that the existing one cannot.
   `CapabilityLlmPassTest.systemPromptInstructsNotToDuplicateAnExistingQualityRule` (pure - `CapabilityLlmPass`
   has no EBX class).

**What was NOT run live on this branch**: nothing here was exercised against the real EBX instance or a
browser (same posture as every other lot) - the fixes were verified by `tools/test_local.sh` (compiles clean,
every test green) and by reading the exact code paths the coordinator's live measurements pointed at.

## Lot M8b - the model proposes the child-key attribute; steps must not break their own pipeline (2026-10-04)

The staging pass gained a fifth tool, `propose_child_keys` (contract in `docs/TARGET-MAPPING.md` sec 4.2 and 19.1, who proposes the attribute in `docs/MATCHING.md` sec 50.8), and two guards on the order of a mapping's
steps and its pipeline (`docs/NORMALISATION.md`, "Case changes and the order of steps"; `docs/TARGET-MAPPING.md` sec 19.2). Review, matrix, "still to decide" and the staging model needed no change: the proposal is an ordinary
`PROPOSED` attribute row with `derivation = CHILD_KEYS`, already worded "Keys of the <child> records" by lot M3.

## A landing table out of scope is out of the staging passes (branch `fix/scope-value-and-landing-selected`, 2026-10-12)

`LandingTable.selected` ("In scope?", default true) already took a table out of the landing generation (`LandingGenerator`), the landing flows and load (`LandingFlows`), the column profiling, the distribution and the intake. The two staging passes read every `LandingTable` row, so a table set to `selected = false` was still counted and still got a rule entity: measured live on project Vol100kN, a stray table `INTAKE_TEST_20261006` of the CRM source set to false before `POST /projects/{code}/staging/suggest` still gave `landingTables: 6` and a rule entity `IntakeTest20261006`, which the language-model pass then had to reason about.

Now `ebx.StagingSuggester` (rule pass) and `ebx.StagingLlmSuggester` (model pass) skip a table whose flag is explicitly `false` (`staging.LandingScope.isInScope`; null, a row written before the field existed, stays in scope). Consequences, all by construction of what the passes read:

- no rule entity, attribute or mapping is proposed for it, and it is not in the landing inventory shown to the model; the relations that name it are dropped with it;
- both answers carry `landingTablesOutOfScope` next to `landingTables` (which counts the tables read);
- a rule entity already written for the table by an earlier run is NOT deleted (nothing in the passes deletes) but the model pass no longer offers it (`LandingScope.keepInScope`); undo it from the review screen or with the step reset;
- if every table of the project is out of scope the passes answer the usual "no landing table yet" refusal.

In the wizard the tick can still only be changed while the table is `PROPOSED` (`table.scopeFrozen` afterwards), so a table taken out of scope later is done through Data Services, as on Vol100kN. Not run live.


## Lot M7 - an entity no landing column feeds is refused, skipped and said (branch `fix/staging-entity-without-feed`, 2026-10-18)

**Measured live** (Factory 1.2.0-263, project `E2EMASTER20261017235630`, `tools/e2e/b2c_master.sh` variant plain, read back over Data Services
`BMdmFactory/MdmFactory/root/...`): the staging language-model pass proposed `ContactEmail` (`StagingEntity` 269, origin LLM, confidence 89, label
"Adresse électronique de contact", rationale "Une adresse électronique constitue un moyen de contact distinct...") with four attributes (`StagingAttribute`
3723-3726: `sourceSystem`, `sourceId`, `client` = the reference to the parent 265, `email`, all origin LLM, confidence 96-100) and **not one `AttributeMapping`**
(`?filter=./attribute='3726'` and `'3725'`: 0 rows) and **no `TransferFeed`** (`?filter=./entity='269'`: 0 rows). The bulk "accept every proposal at 60 % or
above" accepted the entity and its attributes (89 and 96-100 are well above the bar); the preflight reported nothing; the build created its table; the load wrote
0 rows - the plan is built from the accepted mappings, so the entity was simply not in it: no counters, no `lastLoadMessage`, status left `APPLIED`; the master build
created an empty master table for it and the matching step wrote an inert member policy for it. The scenario's own check "ContactEmail: rows written" failed - on the
model's variance, not on the engine. The `email` attribute carried no "no source" note either: `ProposalChecks` skipped the attributes of an entity without a feed
("reported elsewhere", i.e. only in the pass's answer, which nobody reads before clicking Accept all).

**Three rules, all structural** (an entity must be fed by at least one landing column; the technical key `sourceSystem`/`sourceId` and the link to the parent never
have a mapping by design and do not count; no domain word anywhere):

1. **Proposal time** (`staging.StagingLlmPass`, `staging.ProposalChecks`, `ebx.StagingLlmSuggester`). The entity tool cannot refuse at `propose_target_entity`
   (the feeds come after), so the refusal is given where the pass can see the whole run: the follow-up turn and the record step.
   - `Session.entitiesNotYetFed()` (before finalizing) names every target entity of the run no feed reads; `StagingLlmSuggester` gives the model one more turn on
     them, with the tables and columns it already asked about (`followUpPrompt(session, tables, columns, entities)`): "You proposed these target entities and fed NONE
     of their attributes from any landing column: X. An entity no landing column feeds is never built - it is refused as it stands. For each one: **map at least one
     attribute of X to a landing column, or do not propose it**; ... If you keep it without a feed, call note with the entity name and the reason". The system prompt
     states the same rule in one sentence (`StagingLlmPass.noFeedRefusal(name)` is the shared wording).
   - Record step: `ProposalChecks.review(entities, feeds, tables, words, notes)` adds the finding `NO_FEED` on a NEW entity no feed reads: its confidence is capped at
     `NO_FEED_CONFIDENCE_CAP` = 20 (under the matrix's 60 and the wizard's threshold), its rationale starts with `stagingProposal.check.noFeed` -
     en "To check: no source column feeds this entity, nothing would be loaded into it - map at least one attribute of {0} to a landing column, or do not propose it
     (suggestion: reject it).", fr "À vérifier : aucune colonne source n’alimente cette entité, rien n’y serait chargé - associez au moins un attribut de {0} à une
     colonne de landing, ou ne la proposez pas (proposition : la refuser)." - followed, when a note of the run names the entity, by that note (the model's reason), then
     the model's own rationale. Its business attributes get the existing `NO_SOURCE` sentence and cap (nothing fills them); the link to the parent is left alone.
     `Session.reviewModelling` puts the flagged copy back under its name, so the writer stores the flagged row. The entity stays PROPOSED with its reason, for a person.
     The pass's message now names the entities ("... 1 entity(ies) without a feed (ContactEmail: no landing column feeds them, left proposed with the refusal on the
     row, never accepted in bulk) ...").
2. **Decisions** (`ebx.StagingBulkDecideRun.sweep`, the one sweep behind the wizard's Accept all, the matrix's bulk button and `POST /projects/{code}/staging/decide`;
   `review.ReviewData` for the Proposal review screen). On an ACCEPT, a PROPOSED entity none of whose attributes has a live mapping (not REJECTED, not SUPERSEDED) is
   skipped whole - entity, attributes, mappings, inline lines - whatever its confidence, and the job sentence (the step-4 banner) says "**ContactEmail skipped: no source
   column feeds it**" (several: "A, B skipped: no source column feeds them"), next to the other skips. A REJECT sweeps it as before. The review screen's "Accept
   everything above N %" skips the same entities and their attribute lines (`ReviewData.entitiesWithoutFeed(allLines)`, read off the whole project's lines, not the
   filtered ones) and its preview says `review.bulk.noFeedSkipped.one/.many` ("{0} skipped: no source column feeds it." / "{0} ignorée : aucune colonne source ne
   l’alimente."). A single click by a person on the entity's own card is still allowed: the preflight then warns.
3. **Preflight and load** (`ebx.StagingPreflight`, `staging.PreflightCheck.noFeed`, `PreflightFinding.NO_FEED`, `wizard.PreflightHtml`; `ebx.StagingLoader`). An
   ACCEPTED/APPLIED entity that no accepted mapping feeds is a WARNING of the report (never blocking, the build goes on): `wizard.step4.preflight.issue.noFeed` -
   en "Nothing will be loaded into {0}: no accepted mapping feeds it - map at least one attribute to a landing column and accept the mapping, or reject the entity.",
   fr "Rien ne sera chargé dans {0} : aucun lien accepté ne l’alimente - associez au moins un attribut à une colonne de landing et acceptez le lien, ou refusez
   l’entité." The load writes on such an entity's row `lastLoadAt` = now, every counter 0 and `lastLoadMessage` = `StagingLoader.NO_FEED_MESSAGE` = "No feed: no
   accepted mapping feeds this entity, nothing was loaded into it. Map at least one attribute to a landing column and accept the mapping, or reject the entity." (plus
   a note in the build's journal and `entitiesWithoutFeed` in the step detail), instead of nothing.

**Scenario** (`tools/e2e`): `e2e_lib.no_feed(entity)` reads the "No feed" head of the row's message; `b2c_scenario.verify` prints "<entity>: no feed (nothing loaded,
said on the row)" as an INFO line instead of the "rows written" / "0 TransferReject" checks for such an entity, so the scenario measures the engine, not the model's
variance; the flagged-queue policy's regex also reads "(proposition : la refuser)". The stand-in gained the defect `nofeed` (an accepted `ContactEmail` with no mapping,
the row saying "No feed: ..."), shown by `b2c_selftest.py` to give exit 0 and the INFO line. An entity the sweep skipped stays PROPOSED and is then rejected by the
flagged-queue policy ("To check: ... (suggestion: reject it)"), so it is never built in the automated scenario.

**Tests**: `ProposalChecksTest.anEntityNoColumnFeedsIsFlaggedForRejection`, `StagingLlmPassTest.anEntityWithoutFeedIsRefusedAtTheRecordStep`,
`StagingBulkDecideRunTest` (the skip, the sentence, reject unaffected), `ReviewDataTest.bulkAcceptCandidatesSkipAnEntityNoSourceColumnFeeds`,
`PreflightCheckTest.anEntityNoMappingFeedsIsAWarningNeverBlocking`, `PreflightHtmlTest.anEntityNoMappingFeedsRendersAsAWarningInBothLanguages` (the bundle-alignment
test covers the new preflight key), `b2c_selftest.py` (`nofeed`). Not run live: the live instance was only read; the master build and the matching step were not
changed (an entity the sweep skips never reaches them; one a person accepts by hand still does, with the warning and the row message as its trace).


## Lot M15 - a column only a superseded entity read is read by nobody (branch `fix/superseded-columns-coverage`, 2026-10-18)

**Measured live** (Factory 1.2.0-285, project `E2EMASTER20261018090711`, read back over Data Services `BMdmFactory/MdmFactory/root/...`): the staging language-model pass unified ECOM, STORES
and LOYALTY into `Client` (`StagingEntity` 326, origin LLM, `memberEntities` 323-325) and SUPERSEDED the three per-source entities. `LandingColumn` 6129 (`TELEPHONE` of STORES, filled on 234 of
234 tickets) was mapped only to attribute 4416 of the superseded `MagasinsVentes...`; ECOM's `mobile` (mapping 4467) and LOYALTY's `telephone` (4478) fed `Client.mobile`. Result:
`mobile` and `mobileE164` empty on the 234 STORES rows of the staging and the master, no phone key for STORES in the matching plan, 679 false suspect pairs on the address.

**What the live rows say about the follow-up turn** (AgentRun 554/555): the follow-up DID list `STORES...: MAGASIN, DATE_ACHAT, TELEPHONE, MONTANT_TTC` (goal line of run 555), so the pass
did not count the superseded entity's mapping as a read (`Session.readsForUnreadColumns` already excludes rule, REJECTED and SUPERSEDED entities - `StagingLlmPassTest.unreadColumnsPersVol5`).
The model answered (outcome of run 555): "TELEPHONE reste également non repris : le flux Client de cette table existe déjà et ne peut pas être reproposé pour lui ajouter une règle". That was
true: `propose_feed` refused a second call for a (entity, table) pair already proposed in the run ("was already proposed in this run"), so the follow-up asked for something the tool could not do.
The `note` it then wrote made the column "left out on purpose", i.e. nothing flagged it on the entity's row.

**Four structural changes** (no domain word anywhere):

1. **A second `propose_feed` for a feed already proposed in the run adds the rules it brings** (`StagingLlmPass.Session#proposeFeed`): the call goes through the same validation as a feed
   (columns, pipelines, probes, conversion check); rules for attributes the feed already maps are ignored; when nothing new is left the call is refused with the old words ("was already
   proposed in this run, with a rule for every attribute of this call"). The answer says "extended with N rule(s) for A, B; its other rules stand". A feed of an EARLIER run still cannot be
   extended (its rows are written; the answer stays "already exists ... nothing to do"). The system prompt says it.
2. **Coverage rule** (`Session#columnsLeftBehind`, `StagingLlmPass.LeftBehind`): for each landing table a target entity reads through a ROOT feed (first entity in creation order) and that has a
   per-table (rule) entity, the filled columns that entity's live mappings read (`RuleEntityInfo.readColumns`, read by `StagingLlmSuggester.readRuleEntity` from its non-rejected mappings,
   children included, `constant(...)` excluded; null = unknown = nothing claimed) that no LIVE feed reads (`liveReadByTable`: this run's feeds and the non-rejected feeds of a prior entity that
   is neither rule, REJECTED nor SUPERSEDED) and that no note names (table and column as whole words). The follow-up turn lists them in their own paragraph, each column once (taken out of the
   unread-columns paragraph): "columns fed to the superseded entity X and not to its canonical Y, in <table key>: ... For each column: map it to an attribute of its canonical entity (call
   propose_feed again with the same entity and landing table - the rule is added to the feed you already proposed ...), or call note with the table key and the column name written out in full and
   the reason it is left out." Asked under the same fill-rate bar as the unread columns (`UNREAD_COLUMN_FOLLOW_UP_FILL_RATE`); the goal line of the agent run says "columns fed to a superseded entity
   and not to its canonical Client (SalesRule): ...". The run outcome has `columnsLeftBehind` (and the follow-up's own).
3. **Record step** (`ProposalChecks.SUPERSEDED_COLUMN_LEFT_BEHIND`): a NEW canonical entity with columns still left behind (after the follow-up, notes taken into account) gets its confidence capped at
   `LEFT_BEHIND_CONFIDENCE_CAP` = 40 (under the matrix's 60 and the wizard's bar; not 20 like `NO_FEED`: the entity is fed and sound, the proposal is to look at the columns, not to reject it - the
   sentence ends "(suggestion: check them before accepting)", so the e2e flagged-queue policy, which rejects only "(suggestion: reject it)", accepts it) and its rationale starts with
   `stagingProposal.check.supersededColumnLeftBehind` - en "To check: columns that fed {2} (replaced by {0}) are no longer read by any entity: {1} - map each one to an attribute of {0}, or say why it
   is left out (suggestion: check them before accepting)." ({1} = "STORES.TELEPHONE, STORES.MAGASIN": source system and column), fr "À vérifier : des colonnes qui alimentaient {2} (entité remplacée par {0})
   ne sont plus lues par aucune entité : {1} - associez chacune à un attribut de {0}, ou dites pourquoi elle est laissée de côté (proposition : les vérifier avant d’accepter)." Its attributes are untouched. An adopted entity of an earlier run is not rewritten.
4. **Preflight** (`PreflightCheck.sourcesToLookAt` / `notFedBySource`, `PreflightFinding.NOT_FED_BY_SOURCE`, `ebx.StagingPreflight.unmappedColumns`, `wizard.PreflightHtml`): a WARNING, never blocking.
   An attribute counts as "fed by" the sources of its accepted links when EVERY link of it reads phone-like values (the measured `NormalisationProfile.isPhoneLike`, no name read; an attribute with one
   unmeasured link is not claimed). A source that feeds the entity without feeding that attribute, and has a filled landing text column (<= 40 characters, fill rate > 0, not a key, not read by ANY
   accepted mapping) whose measured values are phone-like, is named: en "{0}: fed by {1}, not by {2} although {2}.{3} is filled ({4} of {5}) — map it or say why." ("Client.mobile: fed by ECOM and
   LOYALTY, not by STORES although STORES.TELEPHONE is filled (234 of 234) — map it or say why."), fr "{0} : alimenté par {1}, pas par {2} bien que {2}.{3} soit renseignée ({4} sur {5}) — associez
   cette colonne ou dites pourquoi." Nothing is read from the landing when no attribute qualifies; the columns read are those of the (entity, source) pairs worth a look, through the cached column reads.
   The only value family measured today is the phone (a date or a number column cannot be told from another one by its shape: a sale date is not a birth date).

**Scenario** (`tools/e2e`): `e2e_lib.phone_source_systems` / `phone_by_source` / `sources_missing_phone` and the check "phone reaches the person entity from every source whose landing holds a phone
column" in `b2c_scenario.verify_person` (FAIL when a source with rows in the person entity has not one filled phone although its landing column - named like `PHONE_COLUMNS`, fill rate above 0 or
unknown - exists; a source with no row in the entity is not judged here). The stand-in gained the defect `phonegap` (the STORES phone mapped to nothing, no STORES row with a phone: the E.164
check, which counts only rows with a phone, passes; the new check fails with "STORES: 234 row(s) ... none with a phone"). `b2c_selftest.py`: unit checks of the three functions and `("phonegap", ...)`.

**Tests**: `StagingLlmPassTest.aColumnOnlyASupersededEntityReadIsLeftBehind`, `aFeedProposedAgainInTheRunGainsTheRulesItLacked`; `ProposalChecksTest.columnsLeftBehindBySupersededEntitiesAreFlaggedOnTheCanonical`;
`PreflightCheckTest.anAttributeTheOtherSourcesFeedIsSaidWhenASourceLeavesItsFilledColumnOut`; `PreflightHtmlTest.anAttributeNotFedByASourceRendersInBothLanguages` (bundle alignment covers the new
keys, appended under "# superseded columns coverage (2026-10-18)"). Not run live: the live instance was only read; `StagingPreflight.unmappedColumns` (the EBX-side reader) and
`StagingLlmSuggester.readRuleEntity` compile and follow the neighbouring readers, but ran in no test.
