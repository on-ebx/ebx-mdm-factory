# Quality/normalisation rules proposed by a language model

Written 2026-09-21 on branch `feature/quality-llm`, implementing the product owner's request recorded in the
task brief for this lot: make the language model the PROPOSER of normalisation/quality rules for ANY
master-data domain, not a set of Java heuristics that only ever worked for party/company data. No production
code in `docs/NORMALISATION.md` (2026-09-20) was reused as-is where it relied on a column name or a catalog
tag to decide WHICH rule applies - see "What was removed" below. Updated the same day after a coordinator
review found the first cut computed several gates/context numbers on a capped SAMPLE instead of the real,
exact data, and still let `quality/suggest`'s own deterministic pass propose `UNIQUE` from a bare statistic
with no business meaning - see sections 2, 3.1 and 9 for what changed in the correction round.

**Updated again the same day, branch `fix/quality-llm-coverage`, after the first REAL run** (OpenAI, project
Companies, `SHAPES_ONLY`): a single agent run given all 60 mapped columns of 4 entities in one call only ever
covered the first entity (6 rules, all on entity "Supplier", zero cleaning steps) before declaring itself
done, with budget and refusals both to spare. Coverage depended on the model's own stamina rather than on
anything the factory enforced - see section 1.1 for the fix (the factory now drives the iteration, one agent
run per entity) and section 3.2/5/9 for three further defects the same real run surfaced (a rule's stored
`examples` wrongly read as a before/after pair, a duplicate shape stored twice, and a single-format regex
accepted at 28% failure with no warning).

**Updated a third time the same day, branch `fix/quality-llm-turns`, after the per-entity fix ran for real**
(OpenAI, project Companies, `SAMPLE_VALUES`, 4 entities): the chain worked, but cost roughly the SQUARE of the
column count in input tokens (~480 000 for a 60-column demo) because the round-2 prompt's own "one at a time,
call note naming that column" instruction made the model call one tool per turn, mostly `note` - and every
turn re-sends the whole conversation so far. See section 1.2 for the fix (batched turns, a list-form `note`,
`turns` reported separately from `toolCalls`). The same real run also surfaced three data-correctness defects
in the sibling `capability`-suggestion pattern's shared "propose against measured evidence" discipline - a
duplicate `QualityRule` on an attribute fed by two mappings, a legacy check-digit name (`SIREN`) offered to
the model, and a `PATTERN` proposed on a `TIMESTAMP` column - see section 1.3 and 5 for the fixes, and section
1.3 again for a live product-owner decision (a genuine duplicate pair, one row kept `PROPOSED`, its twin
`REJECTED` to remove the duplicate - never to reject the rule itself) that shaped the final "does this already
exist" identity rule.

**Updated a fourth time, branch `fix/agent-prose-answer`, after a real run over entity `Contact` (OpenAI,
`gpt-4o`) ended `DONE` in 2 turns and recorded ZERO steps/rules**, even though its own final text correctly
described what it would propose - it never called `propose_mapping_step`/`propose_quality_rule`, only wrote
prose. See section 1.4 for the fix, shared with `capability`/`master`'s own LLM passes: one automatic
follow-up run on the same `Session` when this happens, quoting the model's own text and telling it plainly
that only a tool call is recorded; a run that still records nothing after that follow-up is reported as
`status: NO_OUTPUT`, never `DONE`.

---

## 1. What changed, in one paragraph

Before this lot, `quality/suggest` (`ebx.QualitySuggester`, no language model) proposed country/currency/
legal-form transcoding, SIREN/SIRET/IBAN/LEI check digits, and a BIC shape check - all decided by matching a
column's NAME or catalog TAG against a hard-coded list (`IDENTIFIER_TAG_HINTS`, `nameHints(...).contains
("country")`, a column literally called `SIREN`...). The product owner's verdict: "of little interest" -
those heuristics have no meaning outside party/company data. This lot removes every one of them and adds a
second route, `quality/suggest-llm`, where a language model proposes the same KINDS of rule (and more) from
metadata, and the factory MEASURES every proposal against the real distinct values before storing it. Without
a language model configured, `quality/suggest` still runs and still proposes something - just less, and only
what needs no domain knowledge at all: `TRIM`, `COLLAPSE_SPACES`, `EMPTY_TO_NULL` (all value-only cleanup).

**`UNIQUE` moved to the language model entirely** in the correction round (coordinator review, 2026-09-21):
the first cut kept a deterministic "≥ 90% of seen values distinct" `UNIQUE` proposal in `quality/suggest`
itself, but that statistic fires on ANY free-text column (a product description, a person's name) - whether
uniqueness is EXPECTED on a given column is business knowledge no Java rule can infer from shape alone.
`quality/suggest` now proposes only cleanup steps, never a `QualityRule`; `UNIQUE` is available exclusively
through `quality/suggest-llm`'s `propose_quality_rule` tool, MEASURED against the EXACT duplicate count of the
real data (see section 3.1).

---

## 1.1 Per-entity orchestration (coordinator review round 2, 2026-09-21)

**The factory drives the iteration, not the model.** `ebx.QualityLlmSuggester.suggest` no longer builds one
flat list of every mapped column across the whole project and runs a single agent call over all of it. It now
groups the project's ACCEPTED-or-APPLIED mappings by entity, then runs `runOneEntity(...)` **once per
accepted entity** (an entity with at least one mapped column), **sequentially**, each call scoped to only that
entity's own columns:

- A **fresh `QualityLlmPass.Session`** is built per entity, so the `measure` budget (10 calls/column, 100/run)
  and the refusal circuit breaker (section 5) apply **per entity**, not once for the whole project - an entity
  with many dirty columns no longer starves the entities after it of measure budget, and a circuit-breaker trip
  on one entity's column no longer ends the run for every other entity.
- **One entity's own failure never aborts the others.** `suggest`'s per-entity loop wraps `runOneEntity` in a
  `try`/`catch (Exception ...)` (not narrowly `OperationException` - an unexpected `RuntimeException` must not
  take the remaining entities down with it either); a caught failure is recorded as `status: "ERROR"` with the
  exception's message and the loop moves on to the next entity.
- **The route's JSON response gains one block per entity** (`entities: [...]`), each carrying `entity`,
  `agentRunId`, `status` (`AgentRuntime.statusOf`'s own vocabulary - `DONE`/`FAILED`/`BUDGET` - or the
  synthetic `ERROR` on a caught exception), `steps`, `inputTokens`, `outputTokens`, `refusedToolCalls`,
  `stepsCreated`, `rulesCreated`, `earlyStop`/`earlyStopReason` (the entity's own circuit breaker, section 5),
  `notes`, and `columnsWithoutProposalOrNote` (section 3.2) - plus project-wide totals
  (`entitiesTotal`/`entitiesOk`/`entitiesNotNormal`/`stepsCreated`/`rulesCreated`).
- **The single project-level `PipelineStep` (`QUALITY_SUGGEST_LLM`) is marked `DONE` only when EVERY entity's
  own run status is `DONE`.** An entity that errored, or whose own `AgentLoop` reported `FAILED`/`BUDGET`, is
  named (with its status) in the step's own message/detail (`"NOT DONE: <entity> (<status>), ..."`) so a
  reviewer sees exactly which entities need a re-run without reading a log.
- **Idempotence and "never touch a decided row" are unchanged**: each entity's own `Session` still refuses a
  proposal for a kind already present on a column (whatever its status/origin) before it ever reaches
  `write(...)` - nothing about the per-entity split changes what gets written, only how many agent calls it
  takes and how independently they fail.
- **An optional `entity=<name>` query parameter** (`POST /quality/suggest-llm?entity=Supplier`) restricts the
  whole call to one entity - validated against the project's own accepted-mapped entities (an unknown name is
  refused, naming the known ones in the error) - so a previous call's non-`DONE` entity can be re-run alone
  without re-spending tokens on every entity that already finished cleanly.

**Not independently testable end to end** (no live EBX, per this lot's own constraints): the loop itself lives
in `ebx.QualityLlmSuggester`, which touches EBX and cannot run in this JUnit-free/no-live-EBX harness. What
*is* tested, at the pure `quality.QualityLlmPass` layer, is the guarantee the whole fix rests on -
`QualityLlmPassTest.twoEntityRunsAreIndependentSecondStillGetsOwnRun()` scripts one entity's `Session`+
`AgentLoop` to trip its own circuit breaker (three repeated no-op refusals, producing nothing), then builds
and runs a **second, entirely separate** `Session`+`AgentLoop` pair for a second entity and asserts it
completes normally with its own proposals - proving that a fresh `Session` per entity is what the
independence actually rests on, since nothing is shared between the two calls.

---

## 1.2 Batched turns, a list-form `note`, `turns` vs `toolCalls` (coordinator review round 3, 2026-09-21)

The per-entity fix (section 1.1) bounds HOW MUCH one agent call covers, but not HOW MANY LLM round trips it
takes to cover it. The real run this section is named after (OpenAI, `SAMPLE_VALUES`, 4 entities) made close
to one tool call per turn - mostly `note`, since round 2's own prompt said "call note naming that column's
entity and attribute" for every column left alone - and **every turn re-sends the entire conversation so far**
(both wire formats are stateless, see `LlmClient`'s own class Javadoc), so cost grew roughly with the SQUARE
of the column count: ~480 000 input tokens for the 60-column demo alone.

**The library already supported batching several tool calls into one turn - nothing there needed a fix.**
Read directly in `~/Dev/ebx-connectors` (`com.on.ebx.agent`, built into `on-ebx-agent.jar`):
- `AgentLoop.run` (`AgentLoop.java:188-256`) iterates the WHOLE of `turn.toolCalls` inside one loop step
  before ever calling the model again - the method's own comment there (`AgentLoop.java:179-187`) states
  explicitly that "`turn.toolCalls` may hold several calls at once (both formats allow it)" and that a
  partially-answered batch is never possible. The step counter (`AgentLoop.Budget.maxSteps`, what a real run's
  own `"step budget of 30 exceeded"` refers to) advances once per LOOP ITERATION - i.e. once per LLM round
  trip - never once per tool call.
- `LlmClient.anthropicTurn` (`LlmClient.java:332-367`) parses EVERY `tool_use` block off `content`, not just
  the first; `LlmClient.openAiTurn` (`LlmClient.java:441-479`) does the same for every entry of
  `message.tool_calls`. Anthropic's Messages API needs no opt-in flag for several `tool_use` blocks in one
  turn (the model decides on its own); `LlmClient.openAiRequest` (`LlmClient.java:371-439`) never sets
  `parallel_tool_calls` in the request body at all, so the OpenAI-shaped wire format is left at the API's own
  default, which is `true` - nothing in this library was disabling it.

**So the whole fix is prompt-level**, in `QualityLlmPass.systemPrompt()`:
- A new paragraph tells the model explicitly that several tool calls in the same turn are expected and why it
  matters ("every round trip re-sends this entire conversation"), and lays out the shape: one turn for every
  `measure` call needed across every column, one turn for every `propose_mapping_step`/`propose_quality_rule`
  call, and ONE final `note` call for the whole run.
- `note`'s schema (`QualityLlmPass.NOTE_SCHEMA`) grew a `notes` array - `[{entity?, attribute, reason}, ...]`,
  one entry per column left alone - which is now the PREFERRED form the prompt asks for; the old single
  `{entity?, attribute?, text}` form is still accepted (never removed, so nothing already relying on it
  breaks), but the prompt no longer asks for it. `entity` is optional in a `notes` entry too:
  `Session#resolveColumnKey` falls back to matching `attribute` alone against every column of the run when it
  is unambiguous, which is the normal case (a real run's columns all share one entity - section 1.1). A
  malformed entry (no `attribute`, or no `reason`/`text`) is skipped, never fails the whole call - the other
  entries in the same batch are still recorded. Every reason is truncated to `QualityLlmPass
  .MAX_NOTE_REASON_LENGTH` (300 characters) before being stored.
- The prompt also tells the model a column already carrying an existing `PROPOSED`/`ACCEPTED`/`APPLIED` step
  or rule of a given kind needs no new `measure` call for that same kind - that context was already in the
  prompt (`Existing cleaning steps`/`Existing quality rules` per column), just never pointed at explicitly.

**`turns` (LLM round trips) is now reported separately from `toolCalls`** (every tool call attempted, whatever
the turn shape), so the expensive path is visible without reading raw `AgentStep` rows: `AgentRuntime.Outcome`
gained a `toolCalls` field (`AgentRuntime.java:341-352`), counted locally from `AgentLoop.Listener#onToolCall`
(`AgentRuntime.java:404-422` - a separate counter from the trace's own `stepIndex`, which deliberately mixes
turns and tool calls so `AgentStep` rows read in chronological order) since `AgentLoop.Result` itself never
counted the non-refused subset. The per-entity response block (section 1.1) now carries both `turns` and
`toolCalls` (`ebx.QualityLlmSuggester.suggest`) - `turns` is what the step budget (`FactorySettings
.maxAgentSteps`, default 30) actually counts against; `toolCalls` can be higher without costing more turns as
long as they land in the same batch.

**Tested** (`QualityLlmPassTest`, fake HTTP provider): `fiveToolCallsInOneTurnAllExecutedOneTurnCounted()`
scripts ONE turn carrying 5 independent tool calls (a `measure`, a step, two rules, and the single list-form
`note`) followed by one final text turn, and asserts all 5 ran and exactly 2 turns (`AgentLoop.Result.steps`)
were spent - proving the library-level batching claim directly rather than only citing the source.
`budgetExceededStillWritesAlreadyAcceptedProposals()` runs a 2-turn budget where each turn makes one accepted
`propose_*` call and neither turn ever reaches a plain-text end: the run ends `BUDGET`, and both proposals are
still in `Session.proposedSteps()`/`proposedRules()` - `AgentLoop.run` never rolls back a tool call's own
effect just because a later turn never happens, so `ebx.QualityLlmSuggester.write` still persists whatever was
accepted before the budget hit, regardless of the run's final status. `noteListFormRecordsSeveralColumnsInOneCall()`/
`noteListFormToleratesOneBadEntry()`/`noteEmptyCallIsRefused()` cover the new schema directly.

**Measured floor (coordinator's own item 4)**: a synthetic 22-column fixture shaped like the real run's own
Supplier entity (mixed STRING/DECIMAL/BOOLEAN/DATE/TIMESTAMP types, 8 shapes shown per column, a few columns
already carrying an existing step/rule) produces a **9 895-character** `userPrompt()` - see
`QualityLlmPassTest.twentyTwoColumnPromptSizeIsMeasuredAndReported()`, which prints the figure and asserts
every column is named exactly twice (the detail block, then the closing checklist - never a third time, which
would mean an attribute was genuinely duplicated, section 1.3's own bug). Shapes (capped at `MAX_SHAPES_SHOWN`
= 8) and, in `SAMPLE_VALUES` mode, top values (`Project.llmSampleSize`) were already bounded before this round
- this measurement is reported rather than acted on further, since nothing in it looked in need of trimming.

---

## 1.3 One attribute, one column - even when several mappings feed it (coordinator review round 3, 2026-09-21)

A real run (rows 28/29 of `QualityRule`) wrote the IDENTICAL rule twice: `Company.country` - `REFERENCE`,
argument `COUNTRY`, same severity/confidence - once for each of its two accepted mappings (an ERP source and
a CRM source). `QualityRule` is keyed on the ATTRIBUTE, not the mapping, so this was a genuine duplicate, not
two legitimate rows.

**Root cause, found by reading `ebx.QualityLlmSuggester.runOneEntity`'s previous version**: it built one
`ColumnInfo`/evidence entry PER MAPPING, both stored under the SAME `entity.attribute` map key - the second
mapping's own `Map.put` silently overwrote the first mapping's evidence entirely. The model was shown
`Company.country` as what looked like two separate columns (each with its own row/profile numbers from its
own source), but by the time either one was actually measured, `evidenceByKey.get("Company.country")` always
returned whichever mapping had been inserted LAST - both proposals ended up measured against the SAME
(last-inserted) mapping's evidence, which is exactly why both rows showed the identical `valuesSeen = 52` (one
mapping's own row count, never the other's 55): the first mapping's own evidence was silently unreachable for
the whole run, without any error or warning anywhere.

**Fixed by grouping mappings by `entity.attribute` BEFORE building any `ColumnInfo`** (`ebx.QualityLlmSuggester
.runOneEntity`, see its own Javadoc for the exact mechanism): an attribute fed by N accepted mappings is now
shown to the model ONCE, with every contributing source column named (`ERP_SOURCE.COUNTRY,
CRM_SOURCE.BILLING_COUNTRY`), and its evidence is the UNION of every mapping's own distinct values
(concatenated, then re-sorted by count since a union of two already-sorted lists is not itself sorted) and
EXACT aggregates (rows/filled/distinct SUMMED across mappings). Summing `exactDistinctCount` across mappings
is a documented, deliberate OVER-count when the same value genuinely appears in more than one source (no
cross-source dedup attempted this lot) - the same one-directional-trade-off discipline `proposeUnique`'s own
Javadoc already documents elsewhere in this file for a different reason.

**A `QualityRule` proposed against this union evidence is still written ONCE** (unchanged: one row per
attribute) - `write(...)` now takes any one of the attribute's mappings for the row's `entityId`/`attributeId`
(identical across every mapping of the same `StagingAttribute`). **A `MappingStep` stays PER MAPPING** -
cleaning is legitimately about one source column at a time - so `write(...)` now writes one `MappingStep` row
PER MAPPING of the attribute the model proposed a step for, all sharing the step's own measured kind/argument/
rationale (from the union) but each attached to its own `mapping.mappingId` and its own position in that
mapping's own cleaning chain. This is a deliberate simplification, not independently re-measured per mapping -
documented as a known limitation (section 9).

**Belt-and-suspenders same-run dedup**: even with the merge above, nothing stopped the model from proposing
the identical `(entity, attribute, kind, argument)` twice within one run by its own mistake (two different
batched turns, say) - `Session.proposeQualityRule` now also checks `this.rules` (what THIS run already
proposed) before accepting a new one, the same informational "already exists" treatment as a pre-existing row
(`QualityLlmPass.java`, `proposeQualityRule`'s same-run dedup loop).

**Identity is `(kind, argument)`, never `kind` alone - `QualityLlmPass.ExistingProposal` gained an `argument`
field** (`ebx.QualityLlmSuggester.existingRules`/`existingSteps` now read `QualityRule.ARGUMENT`/
`MappingStep.ARGUMENT` too): two `PATTERN` rows with two different regexes are two different hypotheses, not
a duplicate of each other. This surfaced a live decision while the fix was being written: the product owner
reviewed rows 28/29 and REJECTED 29 specifically to remove the duplicate - 28 stayed `PROPOSED`, the rule
itself was never rejected. The matching rule that decision requires:

- **A non-rejected match always wins**, checked across every existing row for the same `(kind, argument)`
  BEFORE ever considering a rejected one, regardless of which order EBX happens to return the rows in - the
  pair above must read as "already exists, status PROPOSED", never as "was rejected". Tested in both row
  orders (`existingPairOneProposedOneRejectedReadsAsAlreadyExists`/`existingPairOrderDoesNotMatter`).
- **Only when the ONLY match is REJECTED** does the model get told so explicitly ("was already proposed and
  REJECTED by a reviewer - do not repropose the exact same form"), informational, not a refusal counted
  toward the circuit breaker - a person's decision is not a mistake for the model to retry past
  (`onlyRejectedTwinTellsModelItWasRejected`).
- **A REJECTED row for a different argument never blocks a genuinely different hypothesis** on the same kind
  (`rejectedTwinNeverBlocksADifferentArgument`) - rejecting one regex says nothing about a different one.
- **Nothing is ever written to, or otherwise touched on, an existing row** in either case - the fix only
  changes what the LOOKUP returns before a NEW row would be created; a decided row (28 or 29) is never
  updated, matching this codebase's standing "deletion/decision changes are REST-only, never automatic" rule.

This section's own item B and C (a legacy check-digit name offered to the model, and `PATTERN` proposed on a
non-STRING column) are documented in section 5, where the corresponding tool behavior lives.

---

## 1.4 A `DONE` run that recorded nothing at all - the model answered in prose (coordinator review round 4, 2026-09-21)

A real run over entity `Contact` ended `DONE` in 2 turns / 18 092 tokens, and wrote ZERO `MappingStep`/
`QualityRule` rows. Reading the run's own steps showed why: turn 1 called tools normally; turn 2's own text
read "Based on the details, here is what I propose: 1. TRIM on Contact.email, 2. PATTERN on Contact.phone
matching..." - a perfectly correct, perfectly USELESS answer, because it was never a `propose_mapping_step`/
`propose_quality_rule` call. `AgentLoop` has no way to tell "the model described a proposal in English" from
"the model proposed nothing" - both end the same way, `stop_reason: end_turn`, `Status.DONE`.

**Fix: `ebx.ProseAnswerRecovery`, one shared helper for all three LLM passes** (`capability`, `quality`,
`master`/matching - see `docs/CAPABILITY-SUGGESTIONS.md`/`docs/MATCHING.md` for the identical mechanism
applied there). When a run ends `DONE` and the pass's own `Session` recorded literally nothing - for quality,
`proposedSteps().isEmpty() && proposedRules().isEmpty() && notes().isEmpty()` (`ebx.QualityLlmSuggester
.runOneEntity`) - ONE follow-up `AgentRuntime.run` call is made on the SAME `Session`/tools/system prompt,
with a new task that quotes the model's own previous text (bounded) and says plainly: nothing was recorded,
only a tool call is saved, record each item now or call `note` once explaining why, never answer in prose
again. The `Session` is a plain mutable Java object kept across both calls - whatever it already fetched/
measured/proposed survives; only the CONVERSATION resets, because `AgentLoop.run(String, String)` always
starts a brand-new one (`~/Dev/ebx-connectors/.../agent/AgentLoop.java:126-131` - there is no way to continue
a previous conversation in the shared library, so a fresh run with the quoted text is the only option it
offers). If the session still records nothing after that one follow-up, the entity's own `status` becomes
`NO_OUTPUT` (never `DONE`), `entitiesNotNormal` counts it, and `modelText` carries the model's own last words
(bounded) so a person can read what it actually said. Either way, `proseAnswerRecovered` (`true`/`false`) says
whether a follow-up was needed and worked; `turns`/`toolCalls`/token counts in the response are the SUM of the
first run and the follow-up, when one happened.

**System prompt**: `QualityLlmPass.systemPrompt()`'s own final paragraph used to invite exactly this failure
("... answer with a short final summary of what you proposed") - replaced with an explicit "a plain text
answer records NOTHING: every outcome ... must go through a tool call" sentence, and the required final `note`
call is now stated as being the wrap-up itself, not a prelude to a separate prose summary.

Tests: `ebx.ProseAnswerRecoveryTest` (the shared helper's own orchestration - fake outcomes/runner, no live
EBX) and `capability.CapabilityLlmPassTest#agentLoopRecoversFromProseOnlyAnswer` (the real mechanism, a fake
HTTP provider driving two separate `AgentLoop.run()` calls against one `Session`) cover the coordinator's
three required scenarios (prose once then recovered, prose twice then `NO_OUTPUT`, something already recorded
means no follow-up at all) at the shared-helper level - `quality.QualityLlmPassTest`'s own addition is limited
to the system-prompt wording, not a duplicate of the mechanism test.

---

## 2. What was removed, and why each one qualifies

| Removed | Was in | Decided from |
|---|---|---|
| `identifierTypeOf` | `QualityDetectors` | a column literally named `SIREN`/`SIRET`/`IBAN`/`LEI` |
| `IDENTIFIER_TAG_HINTS` / `hasIdentifierTag` | `Detectors` | catalog tags (`VAT`, `IBAN`, `LEI`, `REGISTRATION`, `NATIONAL_ID`, `BANK_ACCOUNT`, `GTIN`) |
| `proposeCountry` / `proposeCurrency` | `Detectors` | `nameHints(...).contains("country"/"currency")` |
| `proposeLegalFormTranscode` / `looksLikeLegalForm` | `Detectors` | the words "legal" and "form" in the column/attribute name |
| `proposeReference` (country/currency `REFERENCE` rule) | `QualityDetectors` | the same name hints as above |
| `proposePattern` (BIC shape) | `QualityDetectors` | the word "bic" in the column/attribute name |
| `proposeNormaliseIdentifier` | `Detectors` | its only gate was `hasIdentifierTag` - removed once that was |
| `findScopeColumn` / `nameHints` / `confidenceFromResolution` | `Detectors` | helpers only the above used |

**Rule of thumb applied everywhere**: a rule that decides *what to check* from a column's NAME or a business
VOCABULARY is gone; a rule that decides *whether to propose something* from the column's measured VALUES
alone, with no assumption about what the column means, stays. That is exactly `TRIM`/`COLLAPSE_SPACES`
(does trimming/collapsing change anything on this data) and `EMPTY_TO_NULL` (does a value equal one of a
small, universal, non-domain sentinel set - `N/A`, `-`, `?`...) - both unchanged by the correction round.
`quality.QualityDetectors` itself is now vocabulary/kind constants plus the `isCheckDigitValid` EXECUTOR only
(`RULE_PROPOSABLE_KINDS` is empty): it proposes nothing on its own any more, `UNIQUE` included (see above).

**Executors are unchanged.** `quality.CleaningSteps` (executes a `MappingStep`) and `quality.QualityRuleCheck`
(executes a `QualityRule`) never decided anything from a name - they only ever applied a `kind`/`argument`
someone already decided on. A row accepted by a person under the old heuristics keeps executing exactly as
before, including with the four legacy `CHECK_DIGIT` argument values (`SIREN`, `SIRET`, `IBAN`, `LEI` -
`QualityDetectors.LEGACY_*`).

---

## 3. Generic toolbox vocabulary

`MappingStep.kind` (unchanged set, `CleaningSteps`): `TRIM`, `COLLAPSE_SPACES`, `EMPTY_TO_NULL`, `UPPER`,
`LOWER`, `STRIP_CHARS`, `COUNTRY_TO_ISO2`, `CURRENCY_TO_ISO`, `TRANSCODE_REFERENCE`, `NORMALISE_IDENTIFIER`.

`QualityRule.kind` (unchanged set, `QualityDetectors`): `MANDATORY`, `PATTERN`, `REFERENCE`, `CHECK_DIGIT`,
`UNIQUE`. `QualityRule.severity`: `MODEL_CONSTRAINT` / `REJECT` / `WARN` - a language-model proposal may only
ever use `WARN` or `MODEL_CONSTRAINT`; `REJECT` is refused outright (`docs/NORMALISATION.md` sec 2.2's own
rule: a person raises the severity once they trust the data enough for that).

**Check-digit algorithms, named generically** (`QualityDetectors.CHECK_DIGIT_ALGORITHMS`):

| Name | Algorithm | Notes |
|---|---|---|
| `LUHN` | mod-10, alternating digit doubling | generic - any length |
| `MOD97_IBAN` | ISO/IEC 7064 MOD 97-10, first 4 chars moved to the end | same check as legacy `IBAN` |
| `ISO17442_LEI` | ISO/IEC 7064 MOD 97-10, no rearrangement, 20 chars | same check as legacy `LEI` |
| `GS1_MOD10` | GS1 general specification, weights alternating 3/1 | **new this lot** - GTIN-8/12/13/14, SSCC-18; NOT the same weighting as `LUHN` |
| `SIREN` / `SIRET` / `IBAN` / `LEI` | legacy identifier-type names | kept forever so a row accepted before this lot keeps executing; never proposed by name any more |

`GS1_MOD10` is implemented in `quality.CheckDigits.isValidGs1Mod10` and tested against two real, published
barcodes (Wikipedia's own GTIN worked examples): EAN-13 `4006381333931` and UPC-A `036000291452`, plus an
invalid variant of each (last digit flipped) - see `CheckDigitsTest`.

`REFERENCE` binds to a real `ReferenceBinding` row (`COUNTRY`/`CURRENCY`/`LEGAL_FORM`... whatever is
configured on the instance) by its `kind` - never a hardcoded set of reference kinds; the tool schema's enum
is built at runtime from what is actually configured (`ebx.QualityLlmSuggester.readReferenceBindings`).

---

## 3.1 Sample vs. exact: what every number is actually computed from

Added in the correction round (coordinator review, 2026-09-21): the first cut computed everything - the
`UNIQUE` near-uniqueness ratio, every context number shown to the model (rows, fill rate, distinct count),
every "N of M distinct values resolve/fail" evidence sentence - from `ebx.QualitySuggester.distinctValues`'s
own capped, most-frequent-first `GROUP BY` sample (`DEFAULT_MAX_DISTINCT_VALUES`). That is wrong at volume:
a highly-duplicated identifier's own duplicate values are exactly the ones a frequency-ordered sample
over-represents (they rank highest), while genuinely-unique long-tail values fall out of the cap entirely -
on a 1M-row column with 300 duplicated values, the top-200 sample could read "50% unique" (200 buckets / 400
seen) when the other 999,600 rows are all distinct singletons never seen by the sample at all. In this
codebase, volume is never a marginal concern (`CLAUDE.md`: "EBX tient des centaines de millions de lignes par
table").

**Fixed by separating SAMPLE from EXACT, explicitly, everywhere:**

- `ebx.QualitySuggester.exactAggregates` runs two small, O(1)-result SQL aggregate queries per mapped column
  over the WHOLE landing table - `COUNT(*)` (unfiltered, for `rows`) and, over the same non-blank `WHERE`
  clause `distinctValues` already uses, `COUNT(*)`/`COUNT(DISTINCT "field")` (for `filled`/`distinctCount`).
  `COUNT(DISTINCT ...)` is reachable from this EBX SQL dialect (measured, per the coordinator's own review
  note). Falls back to a full, UNCAPPED `RequestResult` scan on the same two exceptions (`QueryParseException`/
  `CteQueryNotImplementableException`) `distinctValues` already catches - exact by construction, so unlike
  `distinctValues`'s own fallback (a bounded approximation on purpose) this one must read every row.
- The `GROUP BY` sample cap (`DEFAULT_MAX_DISTINCT_VALUES`) was raised from 200 to 2000, so it is actually
  useful for `measure`/pattern-testing - it was never meant to BE the whole column, only a caller had been
  treating it that way. The prompt itself is unaffected: it only ever shows `MAX_SHAPES_SHOWN` (8) shapes and
  `Project.llmSampleSize` real values, regardless of the cap.
- **`UNIQUE` is the one kind whose verdict is EXACT**, never sample-derived: `duplicateRows = exactFilledRows -
  exactDistinctCount`. Only the ILLUSTRATIVE examples (which specific values look duplicated) still come from
  the sample - that part genuinely was fine, since a real duplicate (count > 1) always outranks every singleton
  in the frequency-ordered `GROUP BY`, so it is essentially guaranteed to appear in the sample when one exists.
- **`PATTERN`/`REFERENCE`/`CHECK_DIGIT` and the lookup-based `MappingStep` kinds stay sample-based BY DESIGN**
  - testing a regex/reference/check-digit against every row of a hundred-million-row table on every `measure`
    call is not something this pass does - but every evidence sentence and tool result now says so explicitly
    ("Measured on the N most frequent distinct value(s) out of M (sample).",
    `Detectors.sampledSuffix`/`QualityLlmPass.Session.sampled`) and the stored confidence is capped at 80
    (`Detectors.SAMPLED_MAX_CONFIDENCE`/`cappedConfidence`) whenever `exactDistinctCount > valuesRead`. This
    applies uniformly to the RULE pass's own `TRIM`/`COLLAPSE_SPACES`/`EMPTY_TO_NULL` evidence too (`Detectors
    .propose`'s 3-arg overload takes the exact distinct count) - not only to the language-model pass.
  - The authoritative verdict always remains the rule's own EXECUTION at transfer time, over every real row
    (`quality.QualityRuleCheck`) - a proposal's own confidence, sampled or not, is advisory for the reviewer,
    never a substitute for what actually happens once the rule runs for real.

---

## 3.2 Making coverage checkable within one run (coordinator review round 2, 2026-09-21)

Per-entity runs (section 1.1) bound HOW MUCH one agent call is asked to cover, but nothing stops a model from
still stopping early within one entity's own columns. Two changes make a gap visible without ever forcing the
model to keep going:

- **The user prompt lists the entity's columns explicitly and asks the model to go through them in order** -
  cleaning steps first (what must be normalised before comparison), then quality rules, and to call `note` on
  a column it deliberately leaves alone, naming why (`QualityLlmPass.userPrompt` ends with a checklist, one
  line per `entity.attribute`, built directly from `ProjectInfo.columns` - the same list the entity's own
  `Session` was constructed with, so the checklist and what the factory will actually check afterwards can
  never drift apart).
- **`note`'s schema grew two optional fields, `entity` and `attribute`** (`QualityLlmPass.NOTE_SCHEMA`) - a
  note naming a real column of the current run marks that column covered; a note with no entity/attribute, or
  naming a column that is not part of this run, is still recorded as free text (never rejected - the tool
  never second-guesses what the model chooses to leave alone) but marks nothing covered.
- **`QualityLlmPass.Session.columnsWithoutProposalOrNote()`** (pure, callable directly, `QualityLlmPassTest`
  exercises it standalone) computes, from the columns this Session was built with, which ones ended the run
  with neither a proposal (`ProposedStep`/`ProposedRule`) nor a `note` naming them, AND had no existing
  step/rule already on them before the run started (a column already decided on has nothing to report). The
  route surfaces this per entity as `columnsWithoutProposalOrNote` (section 1.1) - **informational only**:
  nothing in this lot forces the model to close the gap, refuses a run that leaves one, or blocks anything -
  it exists so a reviewer (or a re-run with `?entity=`) can see the gap instead of only ever seeing a rule
  count with no way to tell what was skipped.

---

## 4. What is sent to the language model, per project setting

`Project.llmValueSharing` (new field, default `SHAPES_ONLY`), a decision already taken by the product owner
and not reopened here: **what may be sent to the LLM is a per-project setting.**

- **`SHAPES_ONLY` (default)**: metadata only - entity/attribute/source-column names, data type, declared
  length, row count, fill rate, distinct count, and the SHAPES of the most frequent values with how often
  each shape occurs (`Shapes.of`: every letter becomes `A`/`a`, every digit becomes `9`, punctuation and
  spacing kept - "FR76 3000" reads as "AA99 9999"). No real value ever leaves EBX in this mode - not in the
  prompt, and not in a `measure` tool result either (`QualityLlmPass.Session.addValueExample`/`addStepExample`
  shape every non-conforming example unless the project is in `SAMPLE_VALUES` mode).
  `QualityLlmPassTest.sentinelNeverLeaksInShapesOnlyMode`/`agentLoopEndToEndNoSentinelLeak` prove this
  directly: a sentinel value planted in the fixture data is asserted absent from the system prompt, the user
  prompt, every `measure` result, and every HTTP request a fake provider actually received in a full
  `AgentLoop` run.
- **`SAMPLE_VALUES` (opt-in)**: in addition to the above, the top `Project.llmSampleSize` (default 20) most
  frequent distinct values of each column are shown in the prompt, and a `measure`/proposal result may show
  real non-conforming values instead of shapes - the project has explicitly accepted that real values reach
  the model.

Both fields live on `Project` (not `FactorySettings`): value sharing is a per-project decision (a project
over product data and a project over party data may reasonably choose differently), and the sample size is
tightly coupled to that same choice - keeping them together means one screen, one place to change either.

---

## 5. Tools

`quality.QualityLlmPass.Session` exposes four local tools (mirroring `staging.StagingLlmPass`'s own shape):

- **`measure`** (read-only): test a `PATTERN` (regex), a `REFERENCE` (one of the configured reference kinds),
  or a `CHECK_DIGIT` (one of the algorithm names above) against a column's real, pre-fetched distinct values.
  Returns counts and up to 5 non-conforming examples (shaped unless `SAMPLE_VALUES`), plus a sampling caveat
  when the column's sample does not cover its exact distinct count (see section 3.1). Nothing is stored.
  **Bounded** (coordinator review, 2026-09-21): at most 10 calls per column and 100 per run
  (`Session.MAX_MEASURE_CALLS_PER_COLUMN`/`MAX_MEASURE_CALLS_PER_RUN`), every call counted whether it succeeds
  or is refused - in `SHAPES_ONLY` mode, an unbounded regex-counting tool is effectively an oracle over the
  real values (a model could binary-search a pattern to exfiltrate a column's content one bit at a time,
  never seeing a value directly but learning it call by call); this is part of the `SHAPES_ONLY` guarantee,
  not a separate concern. **`PATTERN`/`CHECK_DIGIT` refused outright on a non-STRING column** (coordinator
  review round 3, 2026-09-21: a real run proposed `PATTERN` on a `TIMESTAMP` attribute - a regex or a
  check-digit algorithm is meaningless once the transfer has already parsed the value). Type-based, never
  name-based, via `quality.Detectors.isTextLike` (widened to package-visible for this - a blank/unknown type
  counts as STRING, the same convention `StagingModel`/`MasterModel` already apply elsewhere in this
  codebase). Same guard, same message, in `propose_quality_rule` below (a model can call it directly without
  measuring first). **`CHECK_DIGIT`'s own argument is checked against `QualityDetectors
  .CHECK_DIGIT_ALGORITHMS_PROPOSABLE`, not `CHECK_DIGIT_ALGORITHMS`** (coordinator review round 3: a real run
  proposed `CHECK_DIGIT(SIREN)` - a legacy, party-data-specific name that sat right there in the wider list
  this tool used to offer). The proposable list has the four generic algorithm names only; the wider list
  (legacy names included) stays exactly as it was for `isCheckDigitValid`, the EXECUTOR a row decided before
  2026-09-21 still runs through unchanged.
- **`propose_mapping_step`**: propose one `MappingStep`. Refused if it would change nothing on the measured
  data (unchanged principle from before this lot), or - for a lookup-based kind (`COUNTRY_TO_ISO2`/
  `CURRENCY_TO_ISO`/`TRANSCODE_REFERENCE`) - if fewer than 50% of measured values resolve
  (`STEP_MIN_RESOLUTION_PERCENT`). **One row per mapping of the target attribute** (coordinator review round
  3, section 1.3): an attribute fed by several accepted mappings is proposed on ONCE, but written as one
  `MappingStep` per mapping, since cleaning is legitimately about one source column at a time.
- **`propose_quality_rule`**: propose one `QualityRule`. Measured and refused per kind:
  - `PATTERN`: refused if more than 30% of measured values fail the regex (`PATTERN_MAX_FAILURE_PERCENT`,
    unchanged). **Coordinator review round 2, 2026-09-21**: a real run had accepted a PATTERN rule at 15 of 54
    (28%) failing - a single-format regex wrongly accepted on a genuinely multi-format column, technically
    under the 30% bar but not something that should pass quietly. Above `PATTERN_MULTI_FORMAT_WARNING_PERCENT`
    (10%) failure, BOTH the refusal message (still refused if over 30%) and the acceptance message (still
    accepted if under 30%) now explicitly say so and suggest either an alternation covering the dominant
    failing shapes, or `note` instead (`QualityLlmPass.multiFormatCaveat`). The **stored confidence is now
    capped at the conforming share** (`(seen - failing) * 100 / seen`) on top of the existing sampling cap - a
    pattern only 72% of values match can never be stored as "95% confident" whatever the model requested,
    closing the gap the real run's observed value of 83 happened to slip through before this was enforced.
  - `REFERENCE`: refused below 50% resolution for `WARN`, below 90% for `MODEL_CONSTRAINT`
    (`REFERENCE_MIN_RESOLUTION_FOR_WARN_PERCENT` / `..._FOR_CONSTRAINT_PERCENT` - the same 90% bar the old
    RULE pass used for the same severity).
  - `CHECK_DIGIT`: refused if more than 40% fail (`CHECK_DIGIT_MAX_FAILURE_PERCENT`).
  - `UNIQUE`: refused if no real duplicate is found among the measured values.
  - `MANDATORY`: refused unless the column's own profiled fill rate is at least 99.5%
    (`MANDATORY_MIN_FILL_RATE_PERCENT`), or refused outright if no fill-rate profile is available.
  - `severity = REJECT` is always refused (see above). **`MODEL_CONSTRAINT` on `PATTERN`/`CHECK_DIGIT` needs
    ZERO failing values measured over the FULL column, never a sample** (`Session.modelConstraintGuard`,
    coordinator review round 3, item D: asked to CONFIRM this was already enforced - it was not; only
    `REFERENCE` had any `MODEL_CONSTRAINT`-specific bar, its own 90% resolution threshold above, so a
    `PATTERN`/`CHECK_DIGIT` proposal at `MODEL_CONSTRAINT` with a nonzero failure count under the ordinary
    30%/40% WARN-level threshold would have been silently accepted before this fix).
  - **A rule's stored `examples` is one non-conforming SHAPE (or real value in `SAMPLE_VALUES` mode) per
    failing value, DEDUPLICATED** (`Session.addValueExample`) - **not** a "before → after" pair.
    **Coordinator review round 2, 2026-09-21**: the first cut of `proposePattern`/`proposeReference`/
    `proposeCheckDigit` called the same helper a `MappingStep` uses for its legitimate before/after pairs
    (`addStepExample(examples, kind, value, value)`), which read as a value joined to ITSELF (`"A99 9AA ->
    A99 9AA"`) - a concept that only makes sense for a `MappingStep`, where a value genuinely changes. It also
    was not deduplicated: the same real run had stored the identical failing shape twice. `addStepExample`
    (unchanged) is now used exclusively by `proposeMappingStep`.
  - **Identity for "does this already exist" is `(kind, argument)`, checked twice - once against what
    already existed before this run, once against what this run itself already proposed** (coordinator review
    round 3, section 1.3) - see that section for the full "non-rejected wins over rejected" rule.
- **`note`**: PREFERRED form is a `notes` list - `[{entity?, attribute, reason}, ...]`, ONE call for the whole
  run, one entry per column left alone (section 1.2). The legacy single `{entity?, attribute?, text}` form is
  still accepted. Naming a real column of this run (in either form) marks it covered in
  `columnsWithoutProposalOrNote` (section 3.2).

Every refusal message tells the model what to do next (measure a different algorithm, narrow the pattern,
propose `WARN` instead, drop it). A kind already present on a column - whatever its own `origin`/`status` -
is answered informationally ("already exists, status X"), never as an error and never re-proposed: this is
what makes a re-run idempotent, and what stops a decided (`ACCEPTED`/`REJECTED`/`APPLIED`) or `USER`-origin
row from ever being touched. A call refused three times in a row on the same `(entity, attribute, kind)` ends
the run outright (the same circuit breaker `StagingLlmPass` uses, for the same reason: neither `LocalTool`
nor `AgentLoop` offer a handler-driven way to stop early otherwise).

---

## 6. Prompts are domain-neutral

`QualityLlmPass.systemPrompt()` names no business domain and uses two deliberately unrelated worked examples
(the task's own suggestion): a product's GTIN column and a site's postal code column. `QualityLlmPassTest
.systemPromptIsDomainNeutral` asserts the prompt never mentions "Company", "SIREN", "LEI" or "legal form".

---

## 7. Route, tracing, guards

`POST /rest/v1/projects/{code}/quality/suggest-llm[?entity=Name]` (`ebx.QualityLlmSuggester`, wired right
after `quality/suggest` in `FactoryResource` to limit merge conflicts): administrators only (spends tokens
and uses the language model connector's key, same guard as `staging/suggest-llm`); answers a clear refusal
(via `ebx.AgentRuntime`'s own check) when no language model connector is configured on `FactorySettings`.
**Runs one agent call per accepted entity, sequentially** (section 1.1) - the optional `entity` query
parameter restricts the whole call to one entity (validated against the project's own accepted-mapped
entities). Traced as a single, project-level `PipelineStep` with `step = QUALITY_SUGGEST_LLM` (a new value,
distinct from the RULE pass's `QUALITY_SUGGEST`), `scope` empty (project-wide, like every other suggest
route) - `DONE` only when every entity's own run ended normally (section 1.1). Reads the exact same
ACCEPTED-or-APPLIED mapped columns `quality/suggest` reads, grouped by entity, with the same bounded
`GROUP BY` SAMPLE (`QualitySuggester.distinctValues`, capped at `DEFAULT_MAX_DISTINCT_VALUES` = 2000 distinct
values per column) PLUS the column's EXACT aggregates (`QualitySuggester.exactAggregates`, section 3.1) -
several of `QualitySuggester`'s reading/query helpers (`MappingRow`, `readAcceptedMappings`, `LandingTableRef`,
`landingTableRef`, `ColumnQuery`, `distinctValues`, `ExactAggregates`, `exactAggregates`) were widened to
package-visible so `QualityLlmSuggester` reuses them rather than duplicating the SQL-building/fallback logic;
`MappingRow` also grew four new fields (`fillRatePercent`, `observedMaxLength`, `distinctCount`,
`sourceRowCount`, all read once from `LandingColumn`/`LandingTable`) for the RAW profile the task asks for
("rows, fill rate, distinct count, min/max length") - but every context number actually SHOWN to the model
(`QualityLlmPass.ColumnInfo.rows`/`fillRatePercent`/`distinctCount`) is overwritten with the EXACT SQL figures
before the prompt is built, never the catalog's own coarser frozen profile (`observedMaxLength`, the longest
OBSERVED value length, is the one profile figure kept as-is - there is no cheap exact equivalent for it).

---

## 8. Clean-up of stale RULE proposals

A `MappingStep`/`QualityRule` row of origin `RULE`, still `PROPOSED`, produced by a heuristic this lot
removed, is withdrawn the next time `quality/suggest` runs - `quality.WithdrawalRule` (unchanged) already
deletes any still-`PROPOSED` row whose scope (the mapping, or the entity/attribute) is no longer "covered" by
the current pass; since the removed heuristics never propose that kind again, every such row is uncovered on
the very next run and is deleted, exactly the same mechanism that already handles a mapping later rejected
(`docs`'s own incident, 2026-09-21, `WithdrawalRule`'s Javadoc). A row a person already decided on
(`ACCEPTED`/`REJECTED`/`APPLIED`) is never deleted - only flagged (`rulesFlaggedStale`/`stepsFlaggedStale` in
the response) for a reviewer to see.

---

## 9. Known limitations of this lot

- **`UNIQUE`'s EXACT duplicate count treats a value's identity as a plain SQL `COUNT(DISTINCT)`** (raw-string
  comparison), not `NORMALISE_IDENTIFIER`-normalised the way the sample-based illustration groups values - two
  differently-punctuated spellings of the same identifier (`"FR76 662 042 449"` vs `"FR76662042449"`) would
  each count as their own distinct value in the EXACT aggregate, so the exact duplicate count can UNDER-count
  relative to a hypothetical normalised exact count. This is a deliberate, one-directional trade-off (a smaller
  reported count, never a larger/false one) documented here rather than fixed, since a normalised
  `COUNT(DISTINCT)` would need a SQL expression capable of the same normalisation `CleaningSteps
  .normalizeKey`/`NORMALISE_IDENTIFIER` does in Java - not attempted this lot.

- **Scoped `TRANSCODE_REFERENCE`** (a legal-form-shaped column keyed by a second, country-like column) is
  supported by the EXECUTOR (`CleaningSteps`, unchanged) and by the argument shape (`{"reference":"...",
  "scopeField":"..."}`), but the language-model pass's own `measure`/`propose_mapping_step` in this lot only
  exercises the UNSCOPED case (`{"reference":"..."}`, no `scopeField`) - a scoped proposal is refused with a
  message asking for an unscoped reference or a different kind. A future lot can extend `QualityLlmSuggester`
  to pre-fetch (value, scope) paired evidence for a column the model names as a candidate scope, the same way
  the old RULE pass did with `siblingColumns`/`findColumnNamed` (both removed in this lot as unused dead code
  once their only caller - the name-hinted legal-form family - was removed).
- **Entity-level `QualityRule`s** (a cross-column check, `attribute = null`) are not supported by this pass:
  every tool call requires a real `(entity, attribute)` pair with pre-fetched evidence to measure against.
- **`MANDATORY`'s fill-rate check uses the CATALOG's frozen profile** (`LandingColumn.fillRate`, read at
  landing-generation time), not a fresh count - the same figure `StagingRules`' own rule 3 already relies on
  elsewhere in this codebase, not a new source of staleness this lot introduces.
- **`columnsWithoutProposalOrNote` is informational only** (section 3.2): nothing enforces that a model closes
  the gap it reports, and it is computed purely from this run's own `Session` state - a column covered by an
  EARLIER entity run (not possible today, since entities don't share columns) or by a proposal this run itself
  refused (refused ≠ covered) is not specially handled beyond what "existing step/rule" already exempts.
- **The per-entity orchestration loop (`ebx.QualityLlmSuggester.suggest`/`runOneEntity`, section 1.1) has not
  been run against a live EBX instance** - only the pure `QualityLlmPass.Session`/`AgentLoop` layer it is built
  from is unit-tested (`QualityLlmPassTest.twoEntityRunsAreIndependentSecondStillGetsOwnRun`, among others).
  Nothing in this lot has been run against a real language model provider either - see the implementing
  session's own final report for what remains to be verified live, including the real first run's own original
  finding (4 entities, only 1 covered) actually being fixed by a live re-run.
- **A multi-mapping attribute's union `exactDistinctCount` is a deliberate OVER-count** when the same value
  genuinely appears in more than one of its sources (section 1.3) - summed, never cross-source-deduplicated.
  This is the opposite direction from `UNIQUE`'s own documented under-count above; both are one-directional,
  neither is fixed this lot, and a caller reading either number should know which way it can be wrong.
- **A `MappingStep` proposed for a multi-mapping attribute is written per mapping, but measured only ONCE,
  against the UNION evidence** (section 1.3) - not re-measured per mapping's own values. A step genuinely
  needed on one source's data but not the other's is still written to both (safe - re-applying a no-op step at
  transfer time does nothing - but not surfaced as a per-mapping distinction anywhere in the UI).
- **`propose_mapping_step`'s own "already exists" check stayed kind-only, not `(kind, argument)`** - unlike
  `propose_quality_rule` (section 1.3), which needed the argument specifically because of a live duplicate
  pair on a `QualityRule`. Extending the same discipline to `MappingStep` (e.g. two `TRANSCODE_REFERENCE`
  proposals with two different reference kinds) was judged out of this round's reported scope and left as a
  candidate for a future pass if the same duplicate pattern is ever seen on a `MappingStep`.
- **The turn-batching prompt (section 1.2) has not been run against a real language model provider** - a real
  provider may still choose to spread its calls across more turns than the prompt asks for (nothing in
  `AgentLoop`/`LlmClient` can force a model to batch, only ask it to); what is fixed and tested is that the
  library executes a batch correctly when the model does send one, and that the factory now measures and
  reports the actual turn/tool-call split either way.

## 10. Failure breakdown and conditions (Validation desk lot L4, 2026-10-22)

A rule that fails on correct data of another group (a five-digit postal code on foreign addresses) is a rule meant for one group. The factory MEASURES where a rule fails and the model decides what to do with it:
`ebx/FailureBreakdowns` reads the joint distinct counts of the column with the other attributes of the entity mapped from the same landing table (at most 8, at most 30 values each, never a sensitive one); `quality/FailureBreakdown`
groups the failing values by each and keeps the best separating one. The `measure` result carries it ("Where it fails, by the field 'countryCode' of the same rows: 48 of 335 value(s) fail, in 7 of 11 group(s). countryCode=CH: 13/13 fail; ...
no failure for countryCode in FR (247)."), the open rules listed under a column carry it too, and `propose_quality_rule` takes the optional `conditionAttribute` + `conditionValues`. Without real values shared (`SHAPES_ONLY`, `NONE`)
the prompt states the concentration without any value of the other field. The condition is validated (an attribute measured against this column, values measured in its groups, WARN, PATTERN/REFERENCE/CHECK_DIGIT) and the rule judged on the rows it
covers; a condition proposed for a rule that already exists, still PROPOSED and unconditional, updates that row. Stored on `QualityRule.failureBreakdown` / `conditionAttribute` / `conditionValues`; see docs/STAGING-DESK.md sec 8.
