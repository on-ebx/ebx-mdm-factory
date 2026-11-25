# Wizard step 6 - "Matching results" block

Implements the lower half of `docs/mockups/wizard-step6-matching.dc.html`: everything below the existing
plan cards and Configure/Run buttons (`wizard.WizardPage#renderStep6`, unchanged). Renders once per master
entity whose `MatchPlan` is `ACCEPTED` or `APPLIED`.

Code map:

- `matching.MatchStats` (pure, unit-tested: `MatchStatsTest`) - KPI computation from plain metadataset rows,
  the source band, per-field comparison/strength/hint, pair sort/filter/bulk-eligibility.
- `matching.MatchResultsView` (pure) - the HTML fragment, plus `script()` (client-side tab/pair switching,
  no postback - injected once by `ebx.ui.ProjectWizardUserService#script`, same technique as
  `review.MatchPlanView#treeScript()`).
- `matching.MatchResultsMessages` - `wizard.step6.results.*` keys of the shared bundle.
- `ebx.MatchingResultsBuilder` - the thin EBX reader that turns one entity's real data into a
  `MatchResultsView.Input`.
- `wizard.WizardPage#renderStep6` - one `MatchResultsView.render(...)` call per accepted/applied plan, at the
  very end of the section.
- `ebx.ui.ProjectWizardUserService` - builds `model.matchResults` on every render (best-effort per entity,
  a failure never takes down the rest of step 6), and dispatches `MATCH_PAIR_MERGE`/`MATCH_PAIR_REJECT`/
  `MATCH_PAIR_BULK_MERGE` - all three go through the **already-existing**
  `ebx.RecordLineageReader#decideSuspect` (itself `ebx.MameSuspectDecision#accept`/`#reject`, which already
  handles the "accept can itself merge" behaviour and the `Xref.goldenKey` refresh). No new write path was
  created for this lot.

## Where each mockup block maps to real data

| Mockup block | Source |
| --- | --- |
| Compact plan summary, tree, "Adresse: no plan of its own" | **Not duplicated** - the existing plan card (task brief: "keep it reachable") already shows the tree/evidence/simulation/survivorship behind its own fold, and `ReviewLine#proposition` already is the one-line tier summary ("N tier(s) - suspect only"). This lot adds nothing here - see "Simplifications" below for why the "related table follows its parent" line specifically was left out too. |
| "Last run" line | `WizardSnapshot.MatchPlanInfo#runStep` (already read by `ebx.WizardData#readMatchPlans`, the latest `MATCHING_RUN` `PipelineStep` for this entity) - `status`/`message`, no new EBX read. |
| KPI cards (records/golden/merged/pending/distinct) | `MatchStats.computeFromRows` over a full scan of the add-on's `RecordMetadata` metadataset table (`ebx.MatchingResultsBuilder#scanMetadata`) for records/golden/merged; `pendingSuspectPairs` is the REAL count of suspect records returned while building the pair list (every golden with `hasSuspects=true`, via `ebx.MameGroups`, already-existing); `distinctPairs` is a real count of the metadataset's `FalsePositiveDecision` table rows. |
| Source-system breakdown under "records" | `Xref.sourceSystem` per `Xref.masterKey`, scoped to this project/table (`ebx.MatchingResultsBuilder#readSourceSystems`, same query shape as `ebx.GoldenKeyRefresher`). |
| "Where golden records come from" band | `MatchStats.computeBand` over the sourceSystem set of each golden (its own source plus every `MERGED` member's source, from the same `Xref` read) - one segment per system that is the ONLY source of at least one golden, plus one `MULTIPLE` segment. Colours: `lineage.SourceColorPalette` (made `public` for this lot - see below), by band order (not the project's own trust-rank order, to avoid a second EBX read - documented simplification). |
| "Quel niveau du plan a décidé" (which tier decided) | **Not implemented.** No public API returns a per-tier count (CLAUDE.md sec 8, `ebx.MameMatchingApi`'s own Javadoc: no getter for matched/suspected/isolated/group counts exists on `MatchingProcess`) - inventing one would violate the task brief's own "omit the count rather than invent it". |
| Suspect queue - list, tabs, counts | `ebx.MatchingResultsBuilder` builds every pending pair (golden × its suspects, from `MameGroups`, already capped at 50 per group) up to `MAX_PENDING_PAIRS_SHOWN` (60); `MatchStats.sortByStrengthDesc` orders them. |
| Suspect queue - per-field comparison table | `MatchStats.compareFields`/`compareValues` on the entity's own non-technical attributes (`StagingAttribute` names, which are 1:1 the master field names - `master.MasterModel`), read off both `Adaptation`s with the CLAUDE.md sec 9 guarded-`get` idiom. **Not the add-on's own algorithm** - see "Simplifications". |
| Suspect queue - decision path / per-field engine score | `ebx.MameMatchingApi#evaluatePair` (`MatchingOperations.executeDecisionTree`, already existing, never previously wired into the wizard) - called for the first `MAX_DETAIL_PAIRS` (30) pending pairs only, wrapped in the `LinkageError`/`RuntimeException` guard its own Javadoc requires the CALLER to provide. Policy code = the entity's own name (`plan.entityName`), the SAME convention `ebx.MatchPlanSimulationRunner#simulate` already uses for a real, non-preview policy (`master.MatchingPolicyTranslator` publishes a plan's policy under its entity's name). |
| "Golden record if merged" preview | **Deliberately not built** - see "Simplifications". |
| Decide buttons (merge/reject/later) | `MATCH_PAIR_MERGE`/`MATCH_PAIR_REJECT` -> `RecordLineageReader.decideSuspect` (existing). "Decide later" is a pure client-side close of the detail panel (no state, matches the mockup's own ephemeral `LATER` state, which the mockup never persists either). |
| Bulk "Merge the N pairs >= threshold" | `MatchStats.eligibleForBulk` on a FRESH re-read of the entity's pending pairs (never the possibly-stale last render), then one `decideSuspect(..., accept=true)` per eligible pair, `MATCH_PAIR_BULK_MERGE` action, reports ok/refused counts. |
| Merged tab | Metadataset rows grouped by `goldenId` (state `MERGED`) - golden + member keys/sources, capped at 60 groups. No "Undo" button (see below). |
| Different tab | The metadataset's own `FalsePositiveDecision` rows (`sourceId`/`targetId`/`user`/`decisionDate`), capped at 100, read as-is - no reverse lookup back to a record label (a scope-down: see below). |

## Simplifications (and why)

- **No "golden record preview" panel.** No add-on Java API was found (this session, read-only MCP/`javap`
  only - no live server access per task constraint) that exposes the merge policy's per-field SURVIVOR
  function to plain code inside a `UserService`. `match-merge`'s own MCP tools (`get_matching_config`) can
  read the policy's chains, but that is a design-time tool, not something `ebx.MatchingResultsBuilder` can
  call from inside EBX at render time. Per the task brief's own explicit fallback ("show the two values and
  the rule name only - never fake a winner"), this implementation goes one step further and shows only the
  two raw values (already in the per-field comparison table) plus one explanatory sentence
  (`results.detail.survivorNote`) - no rule name either, since the rule name is not reliably obtainable the
  same way. A person can judge survivorship directly from the two values shown; the real, final values are
  visible on the master record once an actual merge runs.
- **Per-field verdict (Same/Similar/Different/Missing) is a plain textual proxy, not the add-on's own
  fuzzy-matching algorithm.** `ebx.MameMatchingApi`'s own class Javadoc states the real per-field score's
  unit (0-1 vs 0-100) was never confirmed live. Rather than silently guess a conversion, `MatchStats`
  computes its own cheap, always-correct (by construction, since it is defined by this code) comparison:
  trimmed/case-insensitive equality, then containment/shared-prefix for "similar". The real engine score IS
  still shown, separately, in the decision-path block for the first 30 pairs (`evaluatePair`) - never
  substituted for the proxy, never mixed into it.
- **"Which tier decided" band is omitted entirely** (see table above) - no public count exists.
- **No per-entity "related table follows its parent" line.** The task brief allowed this only "when that is
  really how the project's merge policy is configured (relatedTables/alignForeignKeys)" - no runtime API
  was found (within the time available) to read a merge policy's `relatedTables` from inside a
  `UserService` (same class of problem as the golden-record preview above: the `match-merge` MCP server's
  `get_matching_config` is a design-time tool). Rather than guess from `WizardSnapshot.RelationInfo` (a
  LANDING-level relation, not necessarily the same thing as a configured `alignForeignKeys` merge
  relationship - conflating the two would be exactly the kind of invented fact the task brief warns
  against), this line is left out. The existing plan card already explains an entity's own matching (or
  its `wizard.step6.plan.reviewPointer`/message when it has none).
- **Merged-group "Undo" is a text hint, not a link or a button** - CLAUDE.md sec 8: an Unmerge leaves the
  auto-created golden as a physical row with metadataset state `DELETED`, and the task brief explicitly
  says the wizard must never do this itself. `results.group.manageHint` points at the add-on's own Manage
  group screen (Administration) instead - no reliable direct URL to it was found within scope (the same
  `ServiceKey`/toolbar research CLAUDE.md sec 9 documents as version- and instance-specific), so this is
  plain text, not a broken link.
- **Distinct-pair rows show raw source/target ids, not resolved record titles.** Resolving each id back to
  a live `Adaptation` (for a nicer name) would double the EBX reads of an already-read-only tab for
  marginal benefit; the raw ids are still exact and traceable.
- **Band colours use the KPI band's own count order, not the project's declared trust-rank order** that
  `lineage.SourceColorPalette`'s PRIMARY strategy (`colorForOrdinal`) is designed for - reading the trust
  rank would need a second EBX call this lot's read budget did not allow for. Colours are still stable for
  a given render and drawn from the same 12-hue palette; only their MAPPING to a specific source can differ
  from the lineage screen's own mapping for the same project. Documented here as a known inconsistency, not
  silently accepted.
- **`ebx.MatchingResultsBuilder` is a best-effort block inside `ProjectWizardUserService.model()`**: a
  `RuntimeException` building one entity's results is swallowed (no block rendered for that entity, the rest
  of step 6 - and every other entity's own results block - stays fully usable). No exception detail is
  surfaced to the page in that case; `kernel.log` would carry it via EBX's own default exception logging at
  whatever level the JAX-RS/UserService layer already applies (not added specially by this lot).

## A real finding: `RecordMetadata.state`/`goldenId` are not plain strings

Measured live (read-only `data-access` MCP, 2026-09-24, `Companies_Master`/`Master_Company_MDS`/
`RecordMetadata`, the only Master zone this instance currently has - project Personnes has no Master zone
yet, only its Landing): `describe_table` shows `state` as `xs:int` with a STATIC enumeration, and
`preview_records` confirms the raw stored value is the ordinal (`"state":{"key":1}`, not `"GOLDEN"`).
`goldenId` is a foreign key to the business table, previewed as `{"key":"324"}` - reading it with a plain
`Adaptation.get(path)` + `String.valueOf(...)` (my first draft) would NOT have produced the plain formatted
key text `MameGroups`/`PrimaryKey.parseString` expect. Fixed before this lot's tests were declared green:
`ebx.MatchingResultsBuilder#stateName` maps the ordinal to the add-on's own `State` name (`UNSET`=0,
`GOLDEN`=1, `MERGED`=2, `PENDING_APPROVAL`=3, `DELETED`=4 - CLAUDE.md sec 8 plus a `javap` check on
`com.orchestranetworks.addon.mame.model.State`'s declaration order), and `groupId`/`goldenId` are read with
`Adaptation.getString(path)` (the SAME method `ebx.GoldenKeyRefresher` already uses successfully for this
exact `goldenId` field), not a raw `get()`. This is exactly the kind of "an internal API found by `javap`/
inspection is build-specific, re-verify against the real data" lesson CLAUDE.md sec 13 already warns about -
worth calling out here since it would have made EVERY KPI/tab in this block silently wrong (every
`"GOLDEN".equals(row.state)` check would simply never match an ordinal `1`), with zero exception anywhere.

## `rejectSuspects` persistence across future matching runs

**Found, not measured live** (task constraint: read-only MCP only, no running server touched this session).
`javap` on `ebx-addon-mame`'s own deployed classes (a real, already-installed build under
`~/Fasttrack/Test 6.3.0 Test/.../webapps/ebx-addon-mame/WEB-INF/classes`) shows:

```
public final class com.orchestranetworks.addon.mame.model.metadata.FalsePositiveDecision
    extends AbstractBaseModel {
  ... getSourceId/getTargetId/getUser/getDecisionDate ...
}
public final class com.orchestranetworks.addon.mame.common.util.FalsePositiveDecisionUtil {
  public static String getFalsePositiveDecisionId(String, String);
}
public final class com.orchestranetworks.addon.mame.service.matching.storage.FalsePositiveDecisionCache {
  public boolean isFullLoad();
  public boolean isPresent(String, String);
  public FalsePositiveDecisionCache(Adaptation, int);
  public FalsePositiveDecisionCache(Adaptation);
}
```

A dedicated cache class, keyed by the two record ids, with an `isPresent(source, target)` lookup, living in
`service.matching.storage` (the same package family the matching engine itself uses) is strong circumstantial
evidence that a later matching run consults this cache before re-proposing a pair - exactly what the mockup's
own text claims ("cette paire ne sera plus proposée aux prochaines exécutions"). This was NOT observed running
end to end (no live matching run was triggered this session). The wording chosen for
`results.decide.gate`/the "Different people" button deliberately does NOT repeat the mockup's confident claim
outright; `wizard.step6.results.decide.reject`'s own label just says "Different records" with no persistence
claim attached, so the UI never asserts more than what was actually confirmed. If a future session verifies
this live (reject a pair, re-run matching, confirm it is not re-proposed), the wording can be strengthened.

## `executeDecisionTree` score unit

Still not measured live (same constraint) - `ebx.MameMatchingApi`'s own pre-existing Javadoc on
`FieldScore#score` already documents this precisely, unchanged by this lot: the raw value from
`DecisionTreeExecutionResult.ScoreField.getScore()` is shown as-is in the detail panel with no
multiplication/division, since neither a 0-1 nor a 0-100 assumption could be verified.

## What was compiled/tested but never click-tested

Everything in this lot: `matching.MatchStats`/`MatchResultsView` were run through the module's own
`tools/test_local.sh` (`MatchStatsTest` plus the full pre-existing suite, all green - see below) and compiled
cleanly against the real `ebx-lib.jar`/`ebx-addons.jar` server libraries, but no Tomcat instance was started
this session (task constraint: never run `tools/install.sh` or touch Tomcat), so:

- The rendered HTML/CSS/JS was never opened in a browser.
- `ebx.MatchingResultsBuilder`'s EBX reads (`RecordMetadata`/`FalsePositiveDecision`/`Xref` scans,
  `MameGroups`, `MameMatchingApi.evaluatePair`) were never run against a live instance through this
  `UserService` itself. Project Personnes has no Master zone yet at all (per the user's own memory, staging
  was never loaded) - only its Landing dataspace (`Personnes_Landing`) exists, so this lot's own target
  project cannot be exercised end to end yet. Read-only `data-access` MCP tools were instead used against
  `Companies_Master`/`Master_Company_MDS` (the one Master zone this instance currently has) to confirm the
  metadataset's real table/field shapes - which caught the `state`/`goldenId` type mismatch documented
  above before it shipped.
- `RecordLineageReader.decideSuspect` itself is pre-existing, already-tested code, reused as-is; only the
  NEW call sites (`MATCH_PAIR_MERGE`/`MATCH_PAIR_REJECT`/`MATCH_PAIR_BULK_MERGE` in
  `ebx.ui.ProjectWizardUserService`) are new and unexercised live.

## Deployment needs

- No XSD/data-model change (the block reads existing Factory tables - `PipelineStep`, `MatchPlan`, `Xref` -
  and the add-on's own metadataset; nothing new is written by this lot besides what
  `RecordLineageReader.decideSuspect`/`MameSuspectDecision` already write).
- A Tomcat restart is needed to pick up the new/changed `.class`/`.properties`/`.css`/`.js` files, per
  CLAUDE.md sec 10 (`backend.mode=development` reload does not reach a loaded `UserService` class or a
  changed resource bundle without a full restart) - same as every other lot in this module.
- `lineage.SourceColorPalette` changed from package-private to `public` (no behavioural change to any
  existing caller) so `ebx.MatchingResultsBuilder` (a different package) could reuse it - flagged here
  explicitly since it is the one change to a file outside `matching`/the new `ebx.MatchingResultsBuilder`
  besides the wiring in `wizard.WizardPage`/`ebx.ui.ProjectWizardUserService`/`wizard.js`/the shared message
  bundle/`wizard.css`.

## Tests

`ebx-mdm-factory/src/test/java/com/on/ebx/factory/matching/MatchStatsTest.java` - JUnit-free (`main` throws
on failure, same convention as every other test in this module): KPI computation from plain rows (DELETED
rows excluded, source breakdown, distinct-goldenId group counting), the source band (single-source vs.
multiple, sorted by count), field comparison (`eq`/`fuzzy`/`diff`/`missing`, case/space-insensitive),
strength percent (missing fields excluded, no division by zero), hint field selection, tab filtering, sort
stability/correctness, and bulk-eligibility. `tools/test_local.sh` (with `ON_EBX_AGENT_JAR`/
`ON_EBX_MAPPING_JAR` set) runs it alongside the full pre-existing suite - all green as of this lot.
