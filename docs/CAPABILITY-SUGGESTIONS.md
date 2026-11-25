# Capability suggestions: existing scripts and existing services, proposed by the configured LLM

Written 2026-09-21, branch `feature/capability-suggestions`, implementing the product owner's request recorded
in this lot's task brief: does the factory also look at whether interesting SCRIPTS already exist to check
quality (phone/IBAN validation...), or possible ENRICHMENTS by calling connectors among what is already
available? The answer this lot builds: a **suggestion pass only**. Nothing is executed, no script is attached
to a model, no connector is called on data - the language model SUGGESTS positioning an existing script on the
model (staging or MDM), and SUGGESTS that an existing connector operation could be interesting; a person reviews
and decides; a later, out-of-scope "apply" step (not built in this lot) would do the actual attaching/wiring.
Same standing principle as the rest of this project (`PLAN.md`, "Principes"): no business knowledge in Java -
nothing decides from a column/attribute NAME, only from structure (types, lengths, what already exists).

---

## 1. What was built

- `capability.ScriptInventory` / `capability.ServiceInventory` (pure): parse the `scripting` and
  `ebx-connectors` MCP servers' own JSON responses into plain shapes.
- `capability.CapabilityLlmPass` (pure): the language-model proposer, same "propose, the factory checks what
  is structurally checkable, a person reviews" discipline as `quality.QualityLlmPass` and
  `master.MatchPlanLlmPass` - see section 4 for exactly what it checks.
- `ebx.CapabilityLlmSuggester`: builds the two inventories over MCP (once per route call), reads a project's
  accepted staging entities, runs `CapabilityLlmPass` through `ebx.AgentRuntime` **once per entity** (section
  3), writes `CapabilitySuggestion` rows.
- `ebx.McpServers.SCRIPTING` (`"scripting"`, path segment matches the CLAUDE.md finding: NOT `ebx-scripting`)
  added to `McpServers.OPTIONAL` and to the connection test, reported but never lowering `ready`.
- Table `CapabilitySuggestion` (`tools/build_xsd.py`), `PipelineStep.step` value `CAPABILITY_SUGGEST_LLM`.
- Route `POST /rest/v1/projects/{code}/capabilities/suggest-llm[?entity=<name>][&maxPromptChars=<n>]`
  (`rest.FactoryResource`, right after the matching routes).
- Review screen: two new natures, `SCRIPT_CONSTRAINT` and `EXTERNAL_SERVICE`, wired through the existing
  generic `ReviewLine`/`ReviewPage`/`ProposalReviews` machinery - no new rendering branch, the same pattern
  commit `589d22a` established for `MAPPING_STEP`/`QUALITY`.

Nothing in this lot has been run against a live EBX instance or a real language model provider - the task
brief's own instruction ("never call a live EBX instance, a connector or a real LLM"). Every fact about the
MCP servers' real shapes below is either taken directly from the task brief's own measurements (2026-09-21),
from the coordinator's own live captures the same day (section 0), or marked "not verified".

---

## 0. Coordinator review, 2026-09-21: two blocking fixes from real captures

The first cut of this lot guessed at `get_script`'s and `connectors_describe`'s real shapes (both explicitly
flagged "not verified" in the original report). The coordinator captured both live and found two blocking
defects, now fixed:

1. **`connectors_describe` was called with the wrong argument, in a way that CALLS THE THIRD-PARTY SERVICE.**
   The real parameters are `connector` (required), `operation` (optional), `inputs` (optional) - and the
   tool's own description says passing `operation` makes Connectors describe "the fields of the documents it
   returns or accepts (calls the service's catalog or a sample)". The first cut passed `operation` per call,
   which would have called every candidate operation on every real run. Fixed: `ServiceInventory
   .describeRequestArgs` builds `{"connector": id}` ONLY, called ONCE PER CONNECTOR (not per operation, not
   per entity) - `ServiceInventoryTest.describeRequestArgsIsConnectorOnly` fails if the built request ever
   carries `operation` or `inputs`, the exact test the coordinator asked for. `ServiceInventory` was rewritten
   around the REAL response shape (section 2.2) - no `inputs`/`outputs`/`required` keys exist at all in the
   real answer; the earlier `OperationShape`/`parseDescribe` guessed a shape that never existed.
2. **`get_script`'s real shape** (captured for `library/Fasttrack/lib/iban`, called with `{"scriptIdePath":
   ..., "includeSource": true}` - the parameter is `scriptIdePath`, not `idePath`) exposes `data.source`
   (confirmed, other guessed field names dropped), `data.name` (the library's own IMPORT name, e.g. `Fasttrack
   .lib.iban` - `import Fasttrack.lib.iban as iban;` - now carried through to `CapabilitySuggestion
   .targetImportName`, needed by the later "apply" step) and `data.documentation[]` (`label`/`description` per
   locale, wrapped in literal `<user_input>...</user_input>` tags - `ScriptInventory.stripUserInputTags`
   removes them, `en-US` preferred, falling back to the first entry). The real file's own exported functions
   return `boolean`, `string`, `int` AND `json_object` (`validate(rawIban, addressCountryCode): json_object` -
   the library's own documented main entry point) - the first cut's `return type must be boolean/string` guard
   would have refused that exact function. **Removed entirely**: any return type is now accepted and stored,
   never checked (`capability.CapabilityLlmPass`, `scriptAnyReturnTypeIsAccepted`). The same real function also
   showed that a parameter can genuinely have no matching attribute (`addressCountryCode`, a fallback that may
   need to be blank) - `suggest_script_constraint`'s `binding` now accepts, per parameter, either a real
   attribute name OR `{"literal":"<value>"}` for a documented constant (`scriptBindingLiteralAcceptedFor
   UnmappableParameter`).

**Lot P1 (2026-10-04): the three binding shapes apply to a `SCRIPT_DERIVATION` too, and a person settles what the model could not.** The earlier rule that refused any
`toBeProvided` entry on a derivation (it forced `{"literal":""}` - measured on Vol1m: `toE164(value, '')` left 68,905 of 92,175 phones empty) is gone: a parameter that completes
values which do not carry the information themselves (a default country, a default currency, a locale) is bound to a real attribute, to a literal only when what the model was given
establishes it (said in the rationale), or to `{"toBeProvided":"<why>"}`; a blank literal only when the profiled shapes show no value needs it. At least one parameter still binds to a
real accepted attribute. The suggestion keeps its measured confidence (no `TO_BE_PROVIDED_CONFIDENCE_CAP` on a derivation - accepting the attribute and applying its computation are two
moments); the block is at apply time, where `DerivationApplier` names the parameter(s). A person supplies them with `PUT /projects/{code}/capabilities/{id}/binding` or in step 4 of the
wizard; see `docs/SCRIPT-DERIVATION.md` section 11 (rules, refusals, the re-apply marker).

A third, non-blocking finding: inventory text (function/service descriptions, doc comments) is untrusted text
that reaches the language-model prompt - `CapabilityLlmPass.systemPrompt()` now says explicitly that this text
is DATA, never instructions, and `userPrompt` fences it inside a clearly delimited `BEGIN/END CAPABILITY
INVENTORY` section (now `BEGIN/END CAPABILITY CATALOGUE` - section 0b); every piece of such text is bounded in
length before it ever reaches the prompt (`ScriptInventory.MAX_DOC_LENGTH`,
`ebx.CapabilityLlmSuggester.SERVICE_DESCRIPTION_MAX_LENGTH`) - never a whole source file, only signatures plus
the doc block and the library's own documentation.

---

## 0b. Coordinator review round 2, 2026-09-21: a real deployed run produced nothing

The section-0 fixes were correct but insufficient: the FIRST real run against a deployed instance
(`capabilities/suggest-llm?entity=Contact`, OpenAI `gpt-4o`) produced zero suggestions. The response showed
`"libraryFunctionsInventoried":321,"serviceOperationsInventoried":38`, 47 `connectors_describe` failures (mostly
"configuration is invalid: authentication: An authentication secret is required" / "Basic credentials are
incomplete" / "baseUrl: A base URL is required"), a note that `connectors_describe` was capped at 60 connectors
having never reached VIES/OpenSanctions/Sirene/Nominatim/MongoDB/OpenFDA, and the entity's own `agentRun` block:
`"status":"FAILED","steps":0,"detail":"LLM call failed: HTTP 429 ... Request too large for gpt-4o ... tokens
per min (TPM): Limit 30000, Requested 30391"` - yet the route still answered `"ok":true`. Four fixes, branch
`fix/capability-inventory`:

1. **The inventory is no longer a context blob dumped into every prompt - it is retrievable by tools.** 321
   functions + 38 operations built a ~30k-token first request by themselves, refused outright by the provider's
   own per-minute limit, and would have been paid again in full on every turn even had it fit. Now:
   `CapabilityLlmPass.userPrompt` renders a COMPACT catalogue only - one line per `LibrarySummary` (idePath,
   import name, first sentence of documentation, exported-function count) and one line per `ConnectorSummary`
   (id, label, coarse readiness from `connectors_list` alone, its read/search operation names) - and two new
   read-only local tools, `get_library_functions(library)` / `get_service_details(connector)`, let the model
   drill into ONE library/connector's full detail on demand. Both are bounded (`Session.MAX_DETAIL_CALLS` = 8
   calls total per entity run, shared between the two tools, every call counted whether it succeeds or is
   refused) and `suggest_script_constraint`/`suggest_external_service` REFUSE a target whose library/connector
   was never fetched with the matching detail tool this run ("call get_library_functions/get_service_details
   first") - a guess can never slip through. The system prompt explicitly tells the model to batch several
   detail calls in the SAME turn rather than one at a time ("every turn resends the whole conversation, so
   fewer turns is cheaper and faster") - `AgentLoop.run` already executes every entry of one turn's
   `toolCalls` before sending the next request (read directly in `~/Dev/ebx-connectors`'s
   `com.on.ebx.agent.AgentLoop`, lines 179-256: the step/turn loop sends one HTTP request per turn, then runs
   ALL of that turn's tool calls before looping), so "budget counts turns, not individual tool calls" needed no
   code change there, only the prompt instruction plus a test proving it (`CapabilityLlmPassTest
   .agentLoopBatchesFiveToolCallsInOneTurn`: one scripted turn carries 5 `tool_use` blocks, only 2 HTTP requests
   are ever made, and all 5 calls' effects are provably present afterward). Both catalogues are still built
   ONCE per route call, never per entity (`ebx.CapabilityLlmSuggester.suggest`, lines ~171-176: `scriptInventory`/
   `functionsByLibrary`/`libraries` and `serviceCatalogue` are all built before the per-entity loop starts, and
   reused - via the same `Map`/`List` references and the same `ConnectorDescriber` - by every entity's
   `Session`).
2. **`connectors_describe` refuses a connector whose configuration is invalid - so it can never be the source
   of the inventory itself**, only a lazy, per-connector detail call. The compact connector catalogue is now
   built from `connectors_list` ALONE (`ebx.CapabilityLlmSuggester.buildServiceCatalogue`): a connector is
   offered when `active` and its TYPE has at least one read/search operation per `connectors_list`
   (`ServiceInventory.readSearchOperationNames`) - no `connectors_describe` call anywhere in this step. The
   live `connectors_describe({"connector": id})` call happens only inside `get_service_details`, only for the
   ONE connector the model actually asks about (`ebx.CapabilityLlmSuggester.describerFor`, the
   `CapabilityLlmPass.ConnectorDescriber` implementation) - and a refusal (invalid configuration, no secret, no
   base URL...) is NOT an error: it becomes `DescribeOutcome.notConfigured(reason)`, so the connector STAYS
   suggestable, with that exact reason as the written suggestion's own `readinessDetail` ("Melissa could verify
   phones, but no API key is set" - exactly what a person needs, never a dropped connector). When a describe
   call fails with no operations, `Session.getServiceDetails` falls back to the coarse operation NAMES already
   known from `connectors_list` (kind assumed `READ`, no description/required-inputs known) so the model can
   still see and suggest the connector (`CapabilityLlmPassTest
   .getServiceDetailsDescribeFailureFallsBackToCoarseNames`,
   `.serviceDescribeFailureStillAcceptsNotConfiguredSuggestion`). **The alphabetical cap of 60 connectors is
   gone entirely** - it used to spend itself describing unconfigured connectors early in the alphabet and never
   reached real ones later in it (VIES, OpenSanctions, Sirene, Nominatim...); describing is lazy and
   per-connector now, so there is nothing left to cap upfront. `ServiceInventory.describeRequestArgs` still
   never sends `operation`/`inputs` (section 0, unchanged, still unit-tested).
3. **`ok` now reflects the REAL outcome of the entity's agent run.** Before this fix, `entityOut.put("ok",
   true)` ran unconditionally after `write()` succeeded, regardless of `outcome.status` - a `FAILED`/`BUDGET`
   run (the 429 above) was still reported `"ok":true`. `ebx.CapabilityLlmSuggester.suggest` now computes `final
   boolean succeeded = outcome.status == AgentLoop.Status.DONE;` and sets `ok`/`error` from that; the route's
   own `PipelineStep` is `DONE` only if at least one entity's run genuinely finished `DONE` (`anySucceeded` is
   only ever set inside the `succeeded` branch). The suggestion rows a partially-completed run DID manage to
   write are still kept (`write()` stays unconditional, same principle `quality.QualityLlmPass` already
   documents) - only the reported `ok` status changed, not what gets recorded.
4. **A request too large for the provider is now refused LOCALLY, before ever being sent, with a message that
   survives intact.** The provider's own 429 body is shortened to 300 characters by the shared `on-ebx-agent`
   library (`LlmClient.shorten()`, `~/Dev/ebx-connectors`) - out of this repository's scope to change, and the
   reason the original 429 detail was cut at "t...". `ebx.CapabilityLlmSuggester.suggest` now estimates each
   entity's first request size as `systemPrompt.length() + userPrompt.length()` characters (chars/4 as a
   token proxy - simple, dependency-free, good enough to catch an oversized prompt before paying for a 429) and
   refuses locally, with a full untruncated message naming the real character/token counts, when it exceeds
   `DEFAULT_MAX_PROMPT_CHARS` (60000 characters, ~15000 estimated tokens) or the `maxPromptChars` query
   parameter - no schema change, a constant plus an optional query parameter on the existing route.

**Catalogue size, measured with real code** (not a guess - `CatalogueSizeEstimate`, a one-off ad hoc
computation run against the compiled classes, mirroring the real run's counts): a synthetic fixture of 27
libraries totalling 321 functions (the real run's own `libraryFunctionsInventoried`; the library count itself
was never reported by the real run, so 27 - about 12 functions/library - is this estimate's own assumption) and
105 connectors (the real run's connector count, ~3 read/search operations each), rendered through the real
`CapabilityLlmPass.systemPrompt()`/`userPrompt()` for one representative 10-attribute entity with profile
evidence and two already-proposed rows: **`systemPrompt` 4678 chars, `userPrompt` 20566 chars, 25244 chars
total (~6311 estimated tokens)** - comfortably under the new 60000-character default ceiling, and an order of
magnitude below the ~30k-token first request that triggered the original 429. The full compact catalogue for
321 functions/105 connectors is not itself sent at all after this fix; what actually reaches the prompt scales
with the ENTITY (its attribute count and existing suggestions), not with the size of the whole factory's
inventory - the structural reason this number stays small regardless of how many libraries/connectors exist.

---

## 0c. Coordinator review round 3, 2026-09-21: a `DONE` run recorded nothing - the model answered in prose

A second real deployed run (`capabilities/suggest-llm?entity=Contact`, `gpt-4o`) confirmed the round-2 fix
worked - `librariesInventoried: 43`, `libraryFunctionsInventoried: 321`, `connectorsInventoried: 99`, no
notes, no 429, run `DONE` in 2 turns / 18 092 tokens - and yet produced **0 suggestions, 0 notes**. Reading
`AgentStep`: turn 1 batched 4 tool calls correctly (`get_library_functions` twice, `get_service_details` once
- the lazy-describe/`NOT_CONFIGURED` path from round 2 worked live, "Melissa ... NOT CONFIGURED - {An
authentication secret is required}"); turn 2's own text read "Based on the details fetched, here are the
suggestions: 1. SCRIPT_CONSTRAINT ... 2. ... 3. EXTERNAL_SERVICE Melissa ..." - exactly the right content,
written as PROSE instead of calling `suggest_script_constraint`/`suggest_external_service`. Nothing was
recorded, and the response gave no hint of it (`ok:true`, same as any normal empty run).

**Fix: `ebx.ProseAnswerRecovery`, ONE shared helper used by all three LLM passes** in this module
(`capability`, `quality` - `docs/QUALITY-LLM.md` §1.4 - and `master`/matching - `docs/MATCHING.md` §4.2.2a).
When a run ends `DONE` and the pass's own `Session` recorded literally nothing this run - for capability,
`proposedScriptConstraints().isEmpty() && proposedServices().isEmpty() && notes().isEmpty()`
(`ebx.CapabilityLlmSuggester`, per-entity loop) - ONE follow-up `AgentRuntime.run` call is made on the SAME
`Session`/tools/system prompt, with a new task quoting the model's own previous text (bounded to
`ProseAnswerRecovery.QUOTED_TEXT_MAX_LENGTH`, 2000 characters) and stating plainly: nothing from the previous
answer was recorded, a plain text answer is never saved, only a tool call is; record each item now, or call
`note` once explaining why; do not answer in prose again.

**Why a fresh run, not a continued conversation**: `AgentLoop.run(String, String)` (the shared `on-ebx-agent`
library, `~/Dev/ebx-connectors/.../agent/AgentLoop.java:126-131`) always starts a BRAND NEW conversation - the
very first thing the method body does is build a fresh message list and add the one user task to it; there is
no overload that accepts or returns the conversation itself, so nothing in the library lets a caller append to
a previous run's history. A fresh run with the model's own text quoted into the new task is therefore not a
workaround, it is the only option the library exposes. It works because the SESSION - the tool-backing Java
object, not the conversation - is the same mutable object kept across both calls: whatever library/connector
details the first run already fetched, whatever its refusal counters already counted, survive into the
follow-up; only the turn-by-turn conversation resets. **Verified live via `agentLoopRecoversFromProseOnlyAnswer`**
(a real `AgentLoop` against a fake HTTP provider): the follow-up's own scripted first turn calls
`suggest_script_constraint` directly, with no `get_library_functions` call at all, and still succeeds - proof
the `fetchedLibraries` state genuinely carried over from the first `loop.run()` call.

**One follow-up only.** If the session still records nothing after it, the entity's own `status` becomes
`NO_OUTPUT` (never `DONE`, whatever the underlying `AgentLoop.Status` actually was), `ok` is `false`, the
route's own `PipelineStep` does not count this entity toward `DONE`, and a new `modelText` field carries the
model's own last words (bounded) so a person can read what it actually said. Either way, a new
`proseAnswerRecovered` boolean (`true`/`false`) says whether a follow-up was needed and whether it worked; the
entity's `agentRun.steps`/token counts become the SUM of the first run and the follow-up, when one happened
("its turns/tokens are added to the entity block", per the task brief).

**System prompt**: `CapabilityLlmPass.systemPrompt()`'s own closing line used to invite exactly this failure
("... answer with ONE short final summary - not a note per idea, one wrap-up") - replaced with an explicit "a
plain text answer records NOTHING: every outcome of this run ... must go through one of the tools above"
sentence, and the closing instruction now says to call `note` ONE final time with a list-shaped summary
instead of ending with a free-text answer.

**`ebx.AgentRuntime.Outcome` gained a second, package-private constructor** (`Outcome(String, AgentLoop.Status,
int, int, int, int, int, String, String)`) purely so `ebx.ProseAnswerRecoveryTest` can fabricate a fake
`Outcome` without a live `AgentRuntime.run` call - `AgentLoop.Result`'s own constructor is package-private to
`com.on.ebx.agent`, out of this repository's scope to change, so there was no other way to build one for a
pure orchestration test.

**Tests**: `ebx.ProseAnswerRecoveryTest` (the shared helper's own decision logic: recorded-something means no
follow-up, a non-`DONE` run means no follow-up, prose-once-then-recorded means `proseAnswerRecovered: true`,
prose-twice means `NO_OUTPUT` with the runner called exactly once, combined counters, the `followUpTask` text
itself) plus `CapabilityLlmPassTest#agentLoopRecoversFromProseOnlyAnswer` (the real mechanism, described
above) cover all three of the coordinator's required scenarios. **Not verified**: whether a real deployed
model, given the new system-prompt sentence, still ever needs the follow-up in practice - this fix was built
and tested against a fake HTTP provider only, per this branch's own constraints (no live EBX, no real LLM
call). Whether `LlmClient` could instead force tool use via a `tool_choice` parameter was checked in the
shared library and found absent (`LlmClient.anthropicRequest`/`openAiRequest`,
`~/Dev/ebx-connectors/.../agent/LlmClient.java:267-330` and `:371-...` - the request body never sets a
`tool_choice` key at all, for either provider format) - out of scope to add in this lot (the coordinator's own
instruction), so the follow-up-on-prose mechanism above is the only available mitigation today.

---

## 0d. Service-choice lot, 2026-09-21: anchoring, readiness-first, pricing (branch `feature/service-choice`)

A third real run (entity `Contact`) worked end to end - `proseAnswerRecovered: false`, 2 script constraints +
3 services written - but the WRITTEN rows exposed a different problem: one `EXTERNAL_SERVICE` row (VIES) had
NO attribute and an EMPTY binding, its own rationale reading "Although 'Contact' entities might not typically
have direct VAT number usage, exploring VIES ... might offer potential checks" - noise the model itself
disbelieved, structurally indistinguishable from a real suggestion since nothing checked it was anchored to
anything. Two Melissa rows (email/phone) had a real attribute but an EMPTY `binding` anyway. Every service row
carried `severity: WARN`, which means nothing for a nature that is never attached or run. Separately, the
product owner asked why a PAID connector (Melissa) was suggested when a FREE one (OpenStreetMap) existed for
the same kind of check - the model has no cost information at all today, so it cannot make that judgment
except by its own general knowledge.

**Fix 1: a service suggestion must be anchored** (`CapabilityLlmPass.Session#suggestExternalService`). Refused
outright when both `attribute` and `binding` are empty ("name the attribute(s) this service would read, or
drop the suggestion") - this is exactly what would have refused the real VIES row. A free-form `binding` label
still counts as anchoring even when the operation's own `requiredInputs` is empty/unknown (the model is not
limited to documented input names) - `serviceAnchoredByFreeInputLabelWithoutAttribute` proves this. When
`attribute` is given and `binding` is empty (the real Melissa rows' own shape), the factory AUTO-FILLS the
binding instead of refusing an otherwise well-anchored suggestion: under the operation's own single required
input when there is exactly one, else under a generic `value` key - and the tool result says so
("... (binding auto-filled by the factory: name=email)"), which a reviewer can see via the rationale/result
text captured in the `AgentStep` trace. `severity` is never stored for `EXTERNAL_SERVICE` any more - removed
the XSD-level `default='WARN'` on that field entirely (it only ever mattered for `SCRIPT_CONSTRAINT`, which
sets it explicitly regardless); a fresh `EXTERNAL_SERVICE` row now reads genuinely empty.

**Fix 2 + 3: readiness-first, then pricing, and never hidden - all decided in the PROMPT, never in Java.**
Per this codebase's standing principle (`PLAN.md`, "no business knowledge in Java"), the factory does not
compute "prefer the free geocoder" itself - it only RELAYS structured facts the model reasons over:
- `CapabilityLlmPass.ConnectorSummary`/`Session#getServiceDetails` already showed readiness (round 2); the
  wording is now the literal tokens `READY`/`NOT_CONFIGURED` (was "looks ready"/"configured and ready" prose)
  so the model, and a person reading a trace, sees the same vocabulary everywhere.
- **Pricing is new**: `ServiceInventory.ConnectorRef`/`ListResult` parse an OPTIONAL `pricing`
  (`FREE`/`FREEMIUM`/`PAID`/`UNKNOWN`, case-normalised, an unrecognised tag also degrading to `UNKNOWN` rather
  than failing) and `pricingNote` from `connectors_list`, per connector - the Connectors lot side of this pair
  of changes (another agent's own work, not built here). `ListResult.pricingReported` is true when AT LEAST
  ONE connector in the response carried a `pricing` key at all; when it is false, `ebx.CapabilityLlmSuggester
  .buildServiceCatalogue` adds one note to the response explaining the whole Connectors build predates pricing
  (never a hard failure either way - `pricingAbsentMeansUnknownAndNotReported`/`pricingPresentIsParsedAndStored`
  in `ServiceInventoryTest`). Pricing is sourced from `connectors_list` ONLY in this lot (never additionally
  parsed from `connectors_describe`, even though the task brief mentions both as places the Connectors lot may
  add it) - a deliberate simplification: pricing is a static attribute of the connector, already fully known
  before any describe call, and the existing architecture already treats `connectors_list` as the catalogue's
  one source of truth (round 2, section 0b) - see section 9 for what this leaves unverified.
- Shown in the compact catalogue line and in `get_service_details` (`CapabilityLlmPass.pricingText`, one
  shared renderer so the wording never drifts between the two - `"pricing PAID (per-lookup billing)"`).
  **Never a tool argument**: `pricing`/`pricingNote` are copied straight from the connector's own
  `ConnectorSummary` onto the written `CapabilitySuggestion` row at suggestion time - the same discipline
  `ready`/`readinessDetail` already followed (`servicePricingCopiedFromCatalogueOntoSuggestion`).
- The ORDERING RULE itself lives entirely in `CapabilityLlmPass.systemPrompt()`: prefer `READY` over
  `NOT_CONFIGURED`; among ready connectors, `FREE` before `FREEMIUM` before `PAID`; `UNKNOWN` is "neither
  preferred nor penalised" (verbatim task-brief wording, tested literally). A costlier/unconfigured connector
  is NEVER hidden - it may still be suggested as an explicit alternative when it genuinely adds something,
  naming the preferred connector in the new `alternativeTo` field (free text - a connector id or plain name,
  chosen over a strict cross-suggestion reference because suggestions are not written to EBX, and therefore
  have no real primary key, until the whole entity's agent run has finished - `alternativeTo` cannot point at
  an id that does not exist yet mid-run). `systemPromptStatesReadinessAndPricingOrderingRule` is the required
  "ordering rule rendered in the prompt" test.

**Schema**: three additive fields on `CapabilitySuggestion` (`tools/build_xsd.py`) - `pricing` (enum),
`pricingNote` (text), `alternativeTo` (text) - plus the `severity` default removal (not additive in the strict
sense, but scoped to one field's default value, not a structural change; see section 5). `git diff` of the
regenerated XSD touches exactly these four things: nothing else moved.

**Review screen**: section 7 - a service line now shows readiness and pricing at a glance
(`"Look into <operation> of <connector> (not configured, paid)"`), and `alternativeTo` is appended to the
rationale via a new message key when set.

---

## 0e. Coverage lot, 2026-09-21: `toBeProvided`, attribute-group coverage, idempotence (branch `feature/capability-coverage`)

A fourth real run (entity `Supplier`, pricing live per §0d) worked well on the two attribute groups it looked
at - `iban`/`phone` script constraints, `VIES.check`/`GLEIF.searchByRegistration` services - but two problems
surfaced in the WRITTEN rows, and a third in what was never written at all:

1. **`GLEIF.searchByRegistration`'s second required input, `registrationAuthority`, was force-bound to
   `legalForm` - a real but WRONG link** (a registration authority is not a legal form). The model's own note
   said as much ("Initially attempted an incorrect binding for GLEIF which was corrected using legalForm for
   registrationAuthority"): the guard "every REQUIRED input must be bound to an accepted attribute" left no
   other way to satisfy it, so the model forced one.
2. **The model spent its whole 8-call detail budget on 2 groups and stopped** - the address group (street/
   postalCode/city/country, with BAN/Nominatim READY and FREE), `GLEIF.lei` on the `lei` attribute, and
   anything email-like/`bic` were never even looked at, let alone proposed or explicitly skipped.
3. (Not itself broken, but explicitly asked to be CONFIRMED): a re-run must not duplicate rows 6-9, and a
   REJECTED row must not be re-proposed under the same target.

### Fix 1: a required input may be left `toBeProvided`

`CapabilityLlmPass.java:1151-1234` (`Session#suggestExternalService`) and `:1394-1461` (`Session
#resolveBinding`, the script side): every binding entry - a script parameter or a service input - now resolves
to one of THREE shapes, never two: a real accepted attribute name, `{"literal":"<value>"}` (unchanged), or
`{"toBeProvided":"<what is needed and why no attribute fits>"}` (new). **Literal is now ALSO valid for a
service input** - the earlier "services never take a literal" restriction (§0-§0d) is lifted; the task brief's
own wording lists all three shapes for `suggest_external_service`'s binding, not two.

- **The anchor rule stays exactly as `feature/service-choice` left it**: a literal or to-be-provided entry
  never anchors a suggestion by itself - `attributeBindings` (the real-attribute-only map) is what the anchor
  check and the auto-fill trigger both look at, never the other two maps. A suggestion whose EVERY input is
  `toBeProvided`/`literal`, with no `attribute` param and no real attribute link anywhere in `binding`, is
  still refused as unanchored (`serviceAllToBeProvidedWithNoAttributeIsStillUnanchored`).
- **The missing-required-input refusal now offers the third way explicitly** ("... a literal ..., or declared
  to-be-provided ({\"toBeProvided\":...}) - missing: ...") - the coordinator's own instruction: "so the model
  stops forcing a link" (`serviceMissingRequiredInputRefusalOffersToBeProvided`).
- **Confidence is capped, never the suggestion refused**: `CapabilityLlmPass.TO_BE_PROVIDED_CONFIDENCE_CAP =
  50` (`CapabilityLlmPass.java:132-141`, the one place this is decided) - the midpoint of the 0-100 scale,
  chosen so a suggestion admittedly missing part of its own wiring never reads as "certain" but is not buried
  under guess-level noise either. Applied on BOTH natures whenever ANY binding entry resolves to `toBeProvided`
  (a literal entry never caps it - a literal is a settled choice, not an open one).
- Type compatibility is unaffected: still checked for an attribute-bound script parameter, never for a literal
  or to-be-provided one (nothing to check against).

### Fix 2: coverage by attribute group - visible, never forced, never Java's own judgment

Per this codebase's standing principle (`PLAN.md`, "no business knowledge in Java"), the factory cannot know
what "an address" is - so it never groups attributes itself. Instead:

- **`systemPrompt()`/`userPrompt()` (`CapabilityLlmPass.java:250-320`, `:672-716`) now instruct the model to
  GROUP the entity's accepted attributes by nature, in its own words, FIRST** - one short line per group (e.g.
  "address: street, postalCode, city, country") - and to consider EACH group in turn against the catalogue
  before concluding, so a group is never silently skipped because an earlier one used the whole budget.
- **The `note` tool schema (`NOTE_SCHEMA`, `CapabilityLlmPass.java:211-222`) grew a `notes` array form**, one
  entry per group, each with `text` (what was proposed or why nothing fits), an optional `group` label (the
  model's own, never checked against anything), and an optional `attributes` list (the accepted attribute
  name(s) this note covers). The legacy single-`text` form still works (`noteLegacyTextFormStillWorks`) - same
  backward-compatible two-form pattern `quality.QualityLlmPass`'s own `NOTE_SCHEMA` already established, for
  the same reason (batching several notes into ONE call, rather than one call per group, matters a lot for
  cost - every turn resends the whole conversation).
- **`Session#attributesWithoutSuggestionOrNote()` (`CapabilityLlmPass.java`, new method) reports, informational
  only**, every accepted attribute covered by NEITHER a new suggestion THIS run NOR a note's own `attributes[]`
  THIS run - mirroring `quality.QualityLlmPass.Session#columnsWithoutProposalOrNote()`. Scoped to THIS run only
  (unlike the quality pass's own version): `ExistingSuggestion` carries no attribute of its own to check
  against, only a library/function or connector/operation target, so folding in prior-run coverage would need
  a schema/plumbing change this lot does not make - documented as a scope decision, not an oversight, in
  section 9. `ebx.CapabilityLlmSuggester` relays it as `entities[].attributesWithoutSuggestionOrNote` in the
  response, never enforced (a warning to read, not a reason to refuse).
- **`Session.MAX_DETAIL_CALLS` raised from 8 to 24** (`CapabilityLlmPass.java`, its own Javadoc has the full
  reasoning) - sized for a 25-attribute entity grouped into roughly 8-10 groups at ~2-3 detail calls per group.
  Worst-case added conversation size: every detail RESULT is small in practice (a library's signatures or a
  connector's operations, a few hundred to ~1000 characters each in this codebase's own fixtures) - 24 x 1000
  = ~24 000 characters (~6 000 estimated tokens) by the time the full budget is used, well under
  `ebx.CapabilityLlmSuggester.DEFAULT_MAX_PROMPT_CHARS` (60 000) - though that guard only checks the FIRST
  request, not every later turn; see section 9 for the residual risk. **Every detail-tool result now tells the
  model its own remaining budget** ("detail calls remaining this run: N of 24") - shared across both detail
  tools, so the model can pace itself across every group rather than discovering the cap by hitting it
  (`getLibraryFunctionsReportsRemainingBudget`).

### Fix 3: idempotence confirmed, and a REJECTED target is now named explicitly

No structural change was needed for "a re-run must not duplicate rows 6-9" - `readExisting`
(`ebx.CapabilityLlmSuggester.java:669-688`) already reads every `CapabilitySuggestion` row of the entity
regardless of status, and both `suggest_*` tools already check `this.entity.existing` before ever touching a
binding, so a duplicate target is caught before its (new) binding shape is even parsed - confirmed with the
new binding forms by `serviceReRunWithToBeProvidedTargetIsIdempotent` (a first "run" proposes a
`toBeProvided`-shaped suggestion, a second, FRESH `Session` built with that row as an `ExistingSuggestion` -
exactly what `ebx.CapabilityLlmSuggester` would hand the next route call - attempts the identical target and
is answered informationally, nothing duplicated).

**What DID change**: a target already `REJECTED` gets its own explicit wording now (`Session
.alreadyDecidedMessage`, `CapabilityLlmPass.java`) - "... was already proposed on ... and REJECTED by a
reviewer - do not repropose the exact same target" - rather than the generic "(status REJECTED) - nothing to
do" every other status still gets. Same rule `quality.QualityLlmPass` already follows for its own REJECTED
rows (`docs/QUALITY-LLM.md` §1.3), applied here for the first time.

---

## 2. Inventories

### 2.1 Scripts (`scripting` MCP server)

`ebx.CapabilityLlmSuggester.buildScriptInventory`:

1. `list_scripts(folderIdePath: "library")` directly - the root category's idePath is verified literally
   `"library"` (coordinator capture), so no root probe is needed any more (the first cut listed the root first
   to find it).
2. Recurses into every `script_package`/`isPackaged` folder under `library` (`list_scripts(folderIdePath:
   <folder>)`, following `nextCursor`, page size 100) - today `library/Fasttrack` -> `lib`/`validator` (a
   `script_package`) -> scripts (`PLAN.md`, coordinator capture). Bounded: `MAX_FOLDER_DEPTH` = 6,
   `MAX_LIBRARY_ITEMS_LISTED` = 500 - past either cap, a note is appended (`scriptInventoryNotes` in the
   response) and the walk stops; never silent.
3. For every real script found (not a folder), `get_script(scriptIdePath: ..., includeSource: true)` - the
   real parameter name (coordinator capture; the first cut used `idePath`, wrong) - bounded,
   `MAX_LIBRARY_SCRIPTS_FETCHED` = 150, same "note, never silent" rule.
4. `ScriptInventory.parseScript` reads `data.source` (only field tried now - see section 0), `data.name` (the
   import name) and `data.documentation[]` (tag-stripped, `en-US` preferred). `ScriptInventory
   .parseExportedFunctions` finds every `export function name(p: type, ...): returnType` line in the source
   (task brief: "Exported functions are lines ... in the source"; a plain, non-exported `function ...` line is
   never matched - coordinator: "ignore them") and `ScriptInventory.headerComment` recovers the comment
   immediately above it (task brief: "the header comment is the real documentation"; coordinator: "Each export
   is preceded by a `/** ... */` block with `@param`/`@return`: capture it as the function's doc" - `@param`/
   `@return` lines are kept as plain text, not specially parsed) - a run of `//` line comments, or a `/** ...
   */` block comment.

**A deliberately simple, single-line function pattern** - see `ScriptInventory`'s own Javadoc for what it does
NOT handle (a parameter list with a comma inside a generic type, e.g. `dict<string, string>`, would be split
wrong). Per CLAUDE.md's own EBX Script notes, an exported PARAMETER is always a scalar in practice (an
exported `int` parameter is itself illegal - only `decimal`/`string`/`boolean`/`date`/`dt` are real), so this
simplification is not expected to matter for a parameter; a RETURN type is never gated any more (section 0),
so its own shape does not need to be parse-safe the same way either.

### 2.2 Services (`ebx-connectors` MCP server)

Rewritten again in round 2 (section 0b, item 2) around a LAZY design - `connectors_describe` can refuse a
connector outright ("configuration is invalid"), so it cannot be the thing that builds the inventory.

**Compact catalogue, built once per route call, `connectors_list` ONLY** (`ebx.CapabilityLlmSuggester
.buildServiceCatalogue`): `connectors_list()` once - the real shape (task brief, measured 2026-09-21):
`{"types":[{"type","label","operations":["lei (read)","searchByName (search)","insert (write)", ...]}],
"connectors":[{"id","label","type","baseUrl","authType","active","trust","secretsSet"}],"count":105}`. A
connector becomes a `CapabilityLlmPass.ConnectorSummary` catalogue line when it is `active` and
`ServiceInventory.readSearchOperationNames` finds at least one `read`/`search` operation on its TYPE - no
`connectors_describe` call happens in this step at all, so an unconfigured connector is never silently dropped
here the way the round-1 cap dropped it later.

**Live detail, lazy, per connector, only when the model asks** (`Session.getServiceDetails` ->
`ebx.CapabilityLlmSuggester.describerFor`, the `CapabilityLlmPass.ConnectorDescriber` implementation):
`connectors_describe({"connector": id})` (never with `operation`/`inputs` - section 0, still unit-tested by
`ServiceInventoryTest.describeRequestArgsIsConnectorOnly`). The real BARE-object response (coordinator capture,
`{"connector":"VIES"}`):
```
{"id":"VIES","label":"VIES (EU VAT check)","type":"vies","typeLabel":"VIES (EU VAT validation)","baseUrl":"...",
 "authType":"NONE","trust":80,"active":true,"properties":{},
 "operations":[{"name":"check","kind":"READ","description":"Validate an EU VAT number (input vat, or countryCode + vatNumber)",
                "requiredInputs":[],"optionalInputs":[]}]}
```
`kind` is UPPER CASE here (`connectors_list`'s own coarse kind is lower case); there are no `inputs`/
`outputs`/`required` keys anywhere. `ServiceInventory.parseDescribeConnector` reads this directly (no
`success`/`data` envelope at all), filtered to `READ`/`SEARCH` operations
(`ServiceInventory.DescribedOperation.isCandidateKind`) before being wrapped into a
`CapabilityLlmPass.DescribeOutcome.configured(...)`. `description` (the operation's own documented purpose -
MUST reach the model, bounded by `SERVICE_DESCRIPTION_MAX_LENGTH`), `requiredInputs`/`optionalInputs` straight
from the server (possibly empty - genuinely means no input is required, not "unknown").

**A refusal is data, never an error** - "configuration is invalid: authentication: An authentication secret is
required" (the real deployed run's own capture, round 2) becomes `DescribeOutcome.notConfigured(reason)`: the
connector STAYS suggestable, `outcome.configured = false` and `outcome.reason` become the written suggestion's
own `ready`/`readinessDetail`. When the describe call fails with no operations known at all,
`Session.getServiceDetails` falls back to the coarse operation NAMES the catalogue already had from
`connectors_list` (kind assumed `READ`, no description/required-inputs known) so the model can still see and
suggest the connector rather than being left with nothing to look up. **No cap on how many connectors get
described** - describing is lazy and per-connector now (bounded only by the shared `Session.MAX_DETAIL_CALLS` =
8 detail calls per entity run, across both `get_library_functions` and `get_service_details`), so the earlier
alphabetical `MAX_DESCRIBE_CALLS` = 60 cap (which never reached VIES/OpenSanctions/Sirene/Nominatim - section
0b) is gone entirely.

**Outputs are unknown without calling the service** - there is no `outputs`/`returns` key in the real shape at
all, so nothing in this pass ever claims to know one; "which output could fill which attribute" is free text
in a suggestion's `rationale`, never something a structural guard checks (coordinator, section 0).

**Readiness** (`ConnectorRef.isReady`): `secretsSet || authType == null || "NONE".equalsIgnoreCase(authType)` -
a connector whose auth needs a secret it does not have is `ready = false`, but it is never hidden from the
inventory or the model: `CapabilityLlmPass.Session.suggestExternalService` still lets the model propose it
(with the readiness copied onto the row automatically, never decided by the model), per the task brief:
"`secretsSet:false` with `authType` != `NONE` means 'not ready to use': say so in the suggestion, do not hide
the connector."

### 2.3 Attribute evidence: exact profile numbers and value shapes (never a real value)

Per the task brief ("exact profile numbers and value shapes exactly as `QualityLlmSuggester` obtains them
(reuse its package-visible helpers by CALLING them)"), `ebx.CapabilityLlmSuggester.toLlmAttribute` gives each
accepted attribute that has an accepted landing mapping its EXACT row count / fill rate / distinct count
(`QualitySuggester.exactAggregates`) and its most-frequent value SHAPES (`QualitySuggester.distinctValues` +
`quality.Shapes.of`, capped at 5 per attribute) - calling `QualitySuggester`'s own package-visible helpers
directly (both classes live in the `ebx` package; no visibility change to `QualitySuggester` was needed, they
were already package-visible for `QualityLlmSuggester`'s sake) rather than duplicating the SQL-building/
fallback logic. Also carried: the `kind` (+ argument) of every ACCEPTED-or-APPLIED `MappingStep`/`QualityRule`
already on that attribute - the same evidence shape `ebx.MatchPlanLlmSuggester.cleaningSteps`/`qualityRules`
already reads, copied here per this codebase's "each caller keeps its own private reader" convention.

**Unlike `QualityLlmSuggester`/`MatchPlanLlmSuggester`, this pass has no `SAMPLE_VALUES` opt-in and never reads
`Project.llmValueSharing`**: it never shows a real value under any circumstance, in any mode - only shapes and
counts, always. An attribute with no accepted mapping (or whose landing table cannot be resolved) simply gets
no profile; this is never a hard failure, since a capability suggestion is still meaningful from structure
alone (name, type, length).

---

## 3. One agent run per accepted entity

`CapabilityLlmSuggester.suggest` iterates the project's ACCEPTED-or-APPLIED (`staging.TechnicalKeyRule
.isAcceptedLike`) staging entities and calls `AgentRuntime.run` **once per entity** - a separate `AgentRun`
row, a separate `CapabilityLlmPass.Session`, a separate prompt built from just that entity's attributes and
what is already proposed on it (both inventories are shared, built once). This mirrors the task brief's own
instruction, citing the same lesson learned on the quality-LLM lot ("a first real run of the quality pass
showed a model stops after the first entity when given everything at once", `docs/QUALITY-LLM.md`).

One entity's own failure (an `OperationException` from `AgentRuntime.run`, or from building its tools) is
caught and recorded in that entity's own block of the response (`entities[].ok` / `entities[].error`); it never
aborts the other entities' runs. The whole route is still traced as ONE `PipelineStep`
(`CAPABILITY_SUGGEST_LLM`), begun before the loop and ended after it.

**`ok` reflects the REAL agent-run outcome, not just "did write() not throw"** (round 2, section 0b, item 3):
`entities[].ok` is `true` only when that entity's `AgentRuntime.Outcome.status == AgentLoop.Status.DONE`; a
`FAILED` (e.g. the provider itself refused an oversized/rate-limited request) or `BUDGET` run is `ok:false`
with an `error` naming the status and the run's own `detail`. Any suggestion rows the run DID manage to write
before failing are still kept - `write()` runs unconditionally either way, same principle
`quality.QualityLlmPass` already documents (a cut-short run's partial, valid work is not thrown away). The
route's own `PipelineStep` is `DONE` only if at least one entity's run genuinely finished `DONE`.

**A request estimated too large for the provider is refused locally, before the LLM is ever called** (round 2,
item 4): `systemPrompt.length() + userPrompt.length()` (chars/4 as a token proxy) is compared against
`maxPromptChars` (query parameter) or `CapabilityLlmSuggester.DEFAULT_MAX_PROMPT_CHARS` (60000 characters) for
every entity, before its `AgentRuntime.run` call; over the ceiling, that entity's block gets `ok:false` and a
full, untruncated `error` naming the real character/token counts, and no LLM call is attempted for it - the
other entities are unaffected.

`?entity=<name>` restricts the run to one named entity (must itself be accepted-like, else a clear 409).

---

## 4. What the model may propose, and what is checked (structural only)

Five local tools: `get_library_functions` / `get_service_details` (read-only detail lookups, round 2 - section
0b, budget raised to 24 and self-reporting in section 0e), `suggest_script_constraint` and
`suggest_external_service`, plus `note` (a `{text, group, attributes}` list form since the coverage lot,
section 0e - same two-form pattern `quality.QualityLlmPass`'s own note tool uses) - same shape as every other
LLM pass in this codebase (`quality.QualityLlmPass`, `master.MatchPlanLlmPass`): a tool call is structurally
validated before it is ever recorded, a refusal tells the model exactly what to fix, and a kind already
proposed for the same target is answered informationally rather than duplicated. **No `measure` tool** - unlike
`quality.QualityLlmPass`, this pass never profiles column VALUES at all (nothing to measure against); every
guard here is checkable from metadata alone.

**New in round 2**: both `suggest_*` tools refuse a `libraryIdePath`/`connectorId` whose matching detail tool
(`get_library_functions`/`get_service_details`) was never called THIS run - "you have not looked at '...' yet
this run: call get_library_functions('...') first" - even when that library/connector genuinely exists in the
catalogue. This is what keeps a guess (proposing a function/operation the model never actually saw the real
signature/readiness for) structurally impossible, not just discouraged by the prompt.

### `suggest_script_constraint`

| Guard | What is checked |
|---|---|
| Attribute exists | `attribute` (when given) must be a real accepted attribute of the entity being processed |
| Zone valid | `zone` must be `STAGING` or `MASTER` |
| Zone reachable | `MASTER` is refused unless the entity is PROMOTED - computed by `master.MasterModel.buildFromStaging(...).builtEntities`, the exact same promotion logic `ebx.MatchPlanLlmSuggester` already reuses for its own matching pass, never re-derived here |
| Function exists | `libraryIdePath#functionName` must be an exact match in the script inventory |
| Arity | `binding` must name every parameter of the function, exactly once, no extra keys - each entry is an attribute name, `{"literal":"value"}`, or (coverage lot, 2026-09-21) `{"toBeProvided":"why no attribute fits"}` |
| Binding targets are real | an attribute-bound entry's value must be a real accepted attribute of the entity; a literal-bound entry (`{"literal":"..."}`) is accepted as-is, for a parameter no attribute maps to (coordinator, 2026-09-21: real case, `validate(rawIban, addressCountryCode)`); a `toBeProvided` entry is accepted as-is too, never force-bound to a wrong attribute just to fill it |
| Type compatibility | an attribute-bound entry's `dataType` must be compatible with its parameter's declared type - see the table below; an unparsed/unknown parameter type never blocks (nothing to check against); a literal or to-be-provided entry is never type-checked |
| Severity | `WARN` or `ERROR` only (the XSD's own enum) |
| **Message required** (`fix/capability-apply-ux` item 2, 2026-09-21) | `message` is a REQUIRED string: the short sentence a BUSINESS USER reads on a record this constraint flags - refused outright if missing/blank ("the short sentence a business user reads on the flagged record - not a developer, no code identifiers"), then run through `CapabilityLlmPass.sanitizeUserMessage` (trims, collapses internal whitespace, truncates to 160 characters - never refused merely for length or line breaks) and refused only if nothing readable survives that |
| **Confidence cap** (coverage lot) | a suggestion with ANY `toBeProvided` parameter has its stored confidence capped at `CapabilityLlmPass.TO_BE_PROVIDED_CONFIDENCE_CAP` (50) - never refused, just never presented as more than "worth a look" |
| Idempotent | a `(attribute-or-entity, library#function)` pair already suggested on this entity is answered informationally, never duplicated; one already `REJECTED` by a reviewer is told so explicitly (coverage lot: "do not repropose the exact same target"), the generic "nothing to do" wording is reserved for every other status |

**No return-type gate any more** (coordinator, 2026-09-21, section 0): an earlier revision refused anything
but `boolean`/`string`, which would have refused a real library's own documented entry point (`validate(...):
json_object`). The function's `returnType` is stored on the `FunctionRef`/prompt only, for a later, out-of-
scope "apply" step to judge - never checked by this pass.

**Why `message` exists** (`fix/capability-apply-ux` item 2 - see `docs/CAPABILITY-APPLY.md` section 0a for the
full apply-time flow): a real deployed run showed the constraint's own reported message, until then built by
Java purely from the attribute/function identifiers (`"'phone' failed phone.isValid."`), read as developer
jargon to the business user who actually sees it on a flagged record. `message` moves that authorship to the
language model, which has the entity/attribute/function context to write something readable - "Phone number
is not recognised: check the format.", not "'phone' failed phone.isValid.". It is stored verbatim (post-
sanitisation) as `CapabilitySuggestion.userMessage`, a new additive schema field, and is what
`ebx.CapabilityApplier` actually embeds in the constraint's `msg.addWarning`/`addError` call at apply time - a
row suggested before this field existed (`userMessage` empty) falls back to a Java-built sentence combining
the attribute's own label with the function's documentation, never to the bare identifiers the removed
`deterministicMessage` used.

Type compatibility table (`CapabilityLlmPass.TYPE_COMPATIBILITY`, a plain structural table - never business
vocabulary):

| Staging `dataType` | Compatible EBX Script parameter type(s) |
|---|---|
| `STRING` | `string` |
| `INTEGER` | `decimal` (CLAUDE.md: an exported parameter is always `decimal`, never `int`, however integral) |
| `DECIMAL` | `decimal` |
| `BOOLEAN` | `boolean` |
| `DATE` | `date`, `d` |
| `TIMESTAMP` | `datetime`, `dt`, `date` |

### `suggest_external_service`

| Guard | What is checked |
|---|---|
| Attribute exists | `attribute` (when given) must be a real accepted attribute; an attribute-bound entry's value too |
| Operation exists | `connectorId#operation` must be a described `READ`/`SEARCH` operation in the service inventory |
| **Anchored** (service-choice lot, 2026-09-21) | `attribute` and every attribute-LINK entry of `binding` cannot BOTH be empty - a suggestion naming no attribute at all is refused ("name the attribute(s) this service would read, or drop the suggestion"). A free-form `binding` label (not one of the operation's own `requiredInputs`) still anchors it. A `literal`/`toBeProvided` entry (coverage lot, 2026-09-21) does NOT anchor by itself - a suggestion whose every input is one of those two, with no `attribute` and no real attribute link, is still refused |
| **Auto-filled binding** (service-choice lot) | `attribute` given, `binding` empty -> the factory fills `binding` itself: under the operation's own single required input when there is exactly one, else under a generic `value` key - never refused for this alone. Noted on the tool result and, from there, indirectly on the record (the `rationale`/result text says "auto-filled") |
| Required inputs bound | every input in `requiredInputs` (straight from `connectors_describe`, possibly empty) must resolve to an attribute, a `{"literal":"value"}`, or (coverage lot, 2026-09-21) a `{"toBeProvided":"why no attribute fits"}` - checked AFTER auto-fill. An empty `requiredInputs` list genuinely means no input is required. The refusal for a missing one now names all three ways explicitly, so the model stops forcing a wrong attribute onto it (the real capture: GLEIF's `registrationAuthority` force-bound to `legalForm`) |
| Binding targets are real | an attribute-bound entry's value must be a real accepted attribute of the entity. **Literal is now ALSO valid for a service input** (coverage lot - the earlier "services never take a literal" restriction is lifted) |
| **Confidence cap** (coverage lot) | same rule as the script side: ANY `toBeProvided` entry caps stored confidence at 50; a literal entry never caps it (a settled choice, not an open one) |
| Idempotent | a `(attribute-or-entity, connectorId#operation)` pair already suggested is answered informationally; a `REJECTED` one gets the same explicit "do not repropose" wording the script side does |

Outputs are never part of a service binding (there is no `outputs` key in the real response - section 2.2):
which output could fill which attribute, if relevant, is free text in the `rationale`, never checked.

Readiness (`ready`/`readinessDetail`) is never decided by the model - it is copied straight from the
inventory onto the written row. **Pricing (`pricing`/`pricingNote`) works the same way** (service-choice lot,
2026-09-21, section 0d): copied from the connector's own `ConnectorSummary` at record time, never a tool
argument - the model reasons over what it is SHOWN (in the compact catalogue and in `get_service_details`),
the factory only relays. `alternativeTo` (optional, free text) IS a tool argument - the model names the
preferred READY/free connector when it suggests a costlier or unconfigured one anyway; stored verbatim,
never validated against another suggestion (there is no cross-suggestion id to check against mid-run - see
section 0d for why).

### Domain-neutral prompt

`CapabilityLlmPass.systemPrompt()` names no business domain; its two worked examples are a phone-number-format
validator library function and a company-registry lookup service - deliberately unrelated to each other and
to the party/company data this codebase's demo data happens to use, so the model (and a reader) does not
anchor on one business domain. `CapabilityLlmPassTest.systemPromptIsDomainNeutral` asserts the prompt never
mentions "Company", "SIREN", "LEI" or "legal form".

### Circuit breaker

Same mechanism as every other LLM pass in this codebase: three refusals in a row on the exact same
`(tool, attribute-or-entity, target)` key ends the run outright, forcing the model to its final summary rather
than spending its whole budget retrying one proposal.

---

## 5. Schema

Table `CapabilitySuggestion` (`tools/build_xsd.py`), one row per suggestion:

- `project` (fk), `entity` (fk `StagingEntity`), `attribute` (fk `StagingAttribute`, optional - empty for an
  entity-level/cross-column suggestion)
- `nature`: `SCRIPT_CONSTRAINT` | `EXTERNAL_SERVICE`
- `zone` (`STAGING` | `MASTER`, `SCRIPT_CONSTRAINT` only)
- `targetLibrary` / `targetFunction` (`SCRIPT_CONSTRAINT` only)
- `targetImportName` (`SCRIPT_CONSTRAINT` only, added after the coordinator review): the library's own import
  name (`get_script`'s `data.name`, e.g. `Fasttrack.lib.iban` - `import Fasttrack.lib.iban as iban;`), looked
  up from the inventory and never chosen by the model - needed by the later "apply" step
- `connectorId` / `connectorOperation` (`EXTERNAL_SERVICE` only)
- `binding` (JSON text): which accepted attribute(s), a documented literal `{"literal":"value"}`, or (coverage
  lot, 2026-09-21) a `{"toBeProvided":"why no attribute fits"}` declaration, feed which function parameter /
  operation input - the source of truth a later "apply" step would read; never executed by this pass. Outputs
  are never part of it (section 2.2) - free text in the rationale instead. No schema change for the third
  shape - it is still plain JSON text, same field, same `BINDING_MAX_LENGTH` truncation.
- `severity` (`WARN` | `ERROR`, `SCRIPT_CONSTRAINT` only) - **no XSD default any more** (service-choice lot,
  2026-09-21): it used to default to `WARN` at the schema level, so an `EXTERNAL_SERVICE` row - which never
  sets it - read `WARN` anyway, meaning nothing ("severity WARN is stored on the three service rows", real
  capture). Now genuinely empty unless the pass itself sets it (only the `SCRIPT_CONSTRAINT` write path does).
- `confidence` - unchanged field, but (coverage lot) capped at `CapabilityLlmPass.TO_BE_PROVIDED_CONFIDENCE_CAP`
  (50) whenever `binding` carries any `toBeProvided` entry, whatever the model itself requested
- `ready` / `readinessDetail` (`EXTERNAL_SERVICE` only)
- `pricing` (`FREE` | `FREEMIUM` | `PAID` | `UNKNOWN`, `EXTERNAL_SERVICE` only, added service-choice lot) /
  `pricingNote` (free text) - relayed from `connectors_list`, never a Java judgment of cost; `UNKNOWN` when
  the connector - or the whole Connectors build - never reported one
- `alternativeTo` (free text, `EXTERNAL_SERVICE` only, added service-choice lot) - the preferred READY/free
  connector this suggestion is offered as an alternative to, when applicable; empty when this suggestion IS
  the preferred choice
- the standard proposal fields (`origin`, `confidence`, `rationale`, `status`)

`PipelineStep.step` gained `CAPABILITY_SUGGEST_LLM`. `StagingEntity` gained an association tabular view
`capabilitySuggestions` (same convention as `qualityRules`/`matchPlans`).

---

## 6. Route

`POST /rest/v1/projects/{code}/capabilities/suggest-llm[?entity=<name>][&maxPromptChars=<n>]`
(`rest.FactoryResource`, right after the matching routes). Administrators only - it spends tokens and uses the
language model connector's key, the same guard every other `*-llm` route in this codebase uses.
`maxPromptChars` (round 2, item 4) is optional; omitted, `CapabilityLlmSuggester.DEFAULT_MAX_PROMPT_CHARS`
(60000 characters) applies. Response shape (round 2: `librariesInventoried`/`connectorsInventoried` replace the
old `serviceOperationsInventoried` - operation-level counts are no longer known upfront, only lazily per
connector; `maxPromptChars` echoes the effective ceiling; an entity block's `ok`/`error` now reflect the real
agent-run status, and a `promptChars` field appears when the local size guard refused the call. Round 3
(§0c): every entity block now also carries `proseAnswerRecovered` (`true`/`false`); `agentRun.steps`/token
counts are the SUM of the first run and its one follow-up, when one happened; a run that recorded nothing even
after that follow-up gets `status: "NO_OUTPUT"`, `ok: false`, and a bounded `modelText` field. Coverage lot
(§0e): every entity block also carries `attributesWithoutSuggestionOrNote` - accepted attributes this run
covered by neither a suggestion nor a note, informational only, never a reason to fail the entity):

```json
{
  "project": "...",
  "entitiesConsidered": 3,
  "librariesInventoried": 12,
  "libraryFunctionsInventoried": 87,
  "connectorsInventoried": 40,
  "maxPromptChars": 60000,
  "scriptInventoryNotes": ["..."],
  "serviceInventoryNotes": ["..."],
  "entities": [
    { "entity": "Product", "status": "DONE", "ok": true, "agentRun": {...}, "proseAnswerRecovered": false,
      "earlyStop": false, "scriptConstraintsCreated": 1, "servicesCreated": 1, "notes": [],
      "attributesWithoutSuggestionOrNote": [] },
    { "entity": "Supplier", "status": "DONE", "ok": true, "agentRun": {...}, "proseAnswerRecovered": false,
      "earlyStop": false, "scriptConstraintsCreated": 2, "servicesCreated": 2,
      "notes": ["address: no free-tier geocoder configured yet [street, postalCode, city, country]"],
      "attributesWithoutSuggestionOrNote": ["lei", "email"] },
    { "entity": "Contact", "status": "DONE", "ok": true, "agentRun": {...}, "proseAnswerRecovered": true,
      "earlyStop": false, "scriptConstraintsCreated": 2, "servicesCreated": 1, "notes": [],
      "attributesWithoutSuggestionOrNote": [] },
    { "entity": "Site", "status": "FAILED", "ok": false, "agentRun": {...}, "proseAnswerRecovered": false,
      "error": "agent run ended FAILED: LLM call failed: HTTP 429 ..." },
    { "entity": "Location", "ok": false, "promptChars": 71200,
      "error": "prompt too large for entity 'Location': ~71200 character(s) (~17800 estimated token(s)) exceeds the configured ceiling of 60000 character(s) (~15000 estimated token(s)) - no language-model call was attempted. ..." },
    { "entity": "Order", "status": "NO_OUTPUT", "ok": false, "agentRun": {...}, "proseAnswerRecovered": false,
      "error": "the model's final answer was prose, never a tool call, even after one follow-up: nothing was recorded for this entity.",
      "modelText": "Based on the details fetched, here is what I would propose: ..." }
  ],
  "scriptConstraintsCreated": 5,
  "servicesCreated": 3
}
```

---

## 7. Review screen

`review.ReviewLine.SCRIPT_CONSTRAINT` / `EXTERNAL_SERVICE` - two new natures read by
`ebx.ProposalReviews.addCapabilitySuggestionLines` (mirrors `addQualityRuleLines`), wired through the existing
`tablePathOf`/`statusOf`/`statusPathOf` dispatch, filter list (`review.ReviewPage.NATURES`) and Accept/Reject
machinery (`DecisionActions`/`ReviewFilter`/`ReviewData` needed NO change at all - they are already fully
generic over `ReviewLine.nature`/`.status`). `review.ProposalWording.scriptConstraintProposition`/
`externalServiceProposition` render the proposition in plain words (never raw JSON), `capabilityBinding` shows
the `binding` JSON as `"Binding: param=attribute, ..."` appended to the rationale. **Decision only - accept or
reject, nothing is attached or called from this screen**, exactly as the task brief asked.

**A service line shows readiness AND pricing** (service-choice lot, 2026-09-21, item 4):
`externalServiceProposition(connectorId, operation, ready, pricing)` now renders `"Look into <operation> of
<connector> (<readiness>[, <pricing>])"` - `ready`/`not configured`, then `free`/`freemium`/`paid` in lower
case, only when pricing is not `UNKNOWN` (task brief: "UNKNOWN is neither preferred nor penalised" - showing
it on every row would read as a judgment nothing actually made). ASCII punctuation throughout, matching every
other proposition in this file, rather than the task brief's own illustrative `"Melissa · phone - not
configured · paid"` (middle dot) - substance kept, punctuation normalized. `alternativeTo`, when set, is
appended to the rationale via a new `review.capability.alternativeTo` message key (mirrors the existing
`review.capability.notReady` pattern), so a reviewer sees which service was preferred without leaving the row.

**A `toBeProvided` binding entry reads as unfinished business, not a settled link** (coverage lot, 2026-09-21,
item 1): `capabilityBinding` renders it as `"registrationAuthority: to be provided - a registration authority
is not a legal form"` - deliberately NOT the `key=value`/`key=(literal) value` shape the other two entry kinds
use, so a reviewer's eye catches it as something still needing a person's input, in the rationale text
appended by the existing `capabilityBinding` call (no new rendering call site needed).

---

## 8. The later "apply" step (design only - out of scope for this lot)

## 8. The later "apply" step (design only - out of scope for this lot)

Not built. What an `ACCEPTED` row is for:

- **`SCRIPT_CONSTRAINT`**: `data-model.set_script_constraint` on the staging or master model (`element_path` =
  the attribute's absolute `/root/...` path or the entity's table path for an entity-level constraint, `type`
  = `ConstraintOnNode`/`ConstraintOnNull`/`ConstraintOnTable` depending on `scope`) declares the constraint on
  the model; a small EBX Script body is then authored through the `ebx-scripting` server (`import` the
  library, call the function with the arguments named by `binding`, return its boolean/string result as the
  constraint's verdict); `validate_saved`, then republish the model. CLAUDE.md's own note applies directly: a
  script `constraintOnNode` has `SCRIPT_LIBRARY_ACCESS` and can call a published library function.
- **`EXTERNAL_SERVICE`**: create an `ebx-connectors` enrichment mapping (`connectors_mapping_upsert` with the
  operation named by `connectorId`/`connectorOperation`, `binding` supplying the input mapping) - not a
  landing/staging flow, an enrichment call the way `docs/PLAN.md`'s own "connecteurs de normalisation" idea
  describes (execution on distinct values with a cache, output surfaced in review) - conception still to be
  validated by the product owner per `PLAN.md`'s own "À faire" list.

---

## 9. Not verified

`get_script` and `connectors_describe`'s real shapes were both captured live by the coordinator, 2026-09-21
(section 0) and this lot was corrected to match exactly - they are no longer guesses. What remains unverified:

- **Whether `list_scripts`' `isPackaged`/`type: "script_package"` is the complete signal for "this is a folder,
  recurse"** - taken from the task brief's own field list; a script whose own `type` happens to equal
  `"script_package"` without truly being a folder (unlikely, never seen) would be mis-walked.
- **The full whitelist of `list_scripts` root categories and whether every one of them nests scripts the way
  `library` does** - only `library` is ever walked; `data-model`/`rest`/`rolemapper`/`taskscheduler`/`workflow`
  are never scanned for exported functions, by design (the task brief's own example is a library function; a
  `constraintOnNode` can only import a published LIBRARY, not another script type - CLAUDE.md &sect;2).
- **Whether every connector type's `connectors_describe` answer matches the VIES shape exactly** (a bare
  object with `id`/`label`/`type`/`active`/`operations[]`) - captured for one connector (VIES); a connector
  type with a materially different describe shape (e.g. a nested `operations` structure) would simply parse
  as zero operations for it (`ServiceInventory.parseDescribeConnector` degrades to an empty list, never
  throws) - untested against a second, differently-shaped connector.
- **Whether `connectors_list`'s coarse per-type operation kind (`(read)`/`(search)`/`(write)`, lower case)
  always agrees with `connectors_describe`'s own per-operation `kind` (`READ`/`SEARCH`/`WRITE`, upper case)**
  for the same operation - the catalogue pre-filter (`readSearchOperationNames`) trusts the coarse list to
  decide whether a connector is worth OFFERING at all; if the two ever disagreed (a type-level operation marked
  `write` that a specific connector's own describe reports as `read`, or vice versa), the catalogue could omit a
  connector that would have had a real candidate operation, or offer one whose live describe turns out to have
  none - never a wrong PROPOSAL either way (the describe-time `isCandidateKind()` check inside `describerFor` is
  the one that actually gates what becomes a suggestable operation), only a possibly-missed connector or an
  empty-but-offered one.

**Round 2 (2026-09-21), still open:**

- **The real request-size ceiling was never confirmed against a live provider a second time.** The size guard
  (section 0b, item 4) is a local chars/4 estimate calibrated against ONE real 429 (`gpt-4o`, TPM limit 30000);
  a different model/provider's own tokenizer could estimate meaningfully differently from chars/4, so
  `DEFAULT_MAX_PROMPT_CHARS` = 60000 characters is a generous, never-precisely-calibrated safety margin, not a
  measured token-exact boundary - not re-verified live this round (task brief: no real LLM call).
  `CatalogueSizeEstimate`'s own 25244-character measurement (section 0b) used a synthetic fixture (27 libraries
  assumed for 321 functions - the real run never reported a library count) and one representative entity shape;
  a genuinely unusual entity (many more accepted attributes, or many already-proposed rows) could still exceed
  the ceiling, which is exactly what the local refusal is for.
- **Whether every connector's live `connectors_describe` answer, once the model actually asks for it, matches
  the "configuration is invalid: ..." shape the real deployed run captured** - only that one error family
  (authentication/baseUrl/basic-credentials) was seen; a differently-shaped failure (a timeout, a 5xx, a
  malformed JSON body) is still caught by the same `try { } catch (McpException ex)` in `describerFor` and
  turned into `DescribeOutcome.notConfigured(ex.getMessage())`, but the resulting reason text was never seen
  live for anything other than the "configuration is invalid" family.
- **Whether a real model actually follows the "batch detail calls in the same turn" instruction in practice** -
  `AgentLoop` itself provably executes a batched turn correctly when the model DOES batch
  (`agentLoopBatchesFiveToolCallsInOneTurn`), but nothing in this lot can verify whether `gpt-4o` (or another
  configured model) chooses to batch rather than calling tools one at a time when given this exact system
  prompt - a model that ignores the instruction would still work correctly, just spend more turns/budget than
  necessary.

**Service-choice lot (2026-09-21), still open** - built and tested against fixtures only, no live EBX, no
live Connectors call with real pricing data, no real LLM call (branch constraints):

- **`connectors_list`'s real `pricing`/`pricingNote` shape was never measured live** - the Connectors-side
  change is another agent's own work, landing in a separate repository; `ServiceInventory.parseConnectorsList`
  was written against the task brief's own field names (`pricing`, `pricingNote`, on the connector object,
  alongside `authType`/`secretsSet`) and degrades safely (`UNKNOWN`) on anything it does not recognise, but the
  exact key names/casing/placement in the real, deployed response are unconfirmed.
- **Pricing is read from `connectors_list` only, never from `connectors_describe`** - a deliberate
  simplification (section 0d) given the task brief mentions both as possible sources; if the real Connectors
  lot ends up putting pricing ONLY on the `connectors_describe` response and not on `connectors_list`, this
  lot's catalogue would show every connector as `UNKNOWN` regardless of what `connectors_describe` says (the
  fallback note would then fire for every run, itself become a signal something is wrong, but the actual
  pricing values would stay invisible until a follow-up lot reads `connectors_describe` too).
- **Whether a real model actually follows the readiness/pricing ordering rule, or reliably fills in
  `alternativeTo` when suggesting a lesser connector, was never observed live** - the rule is proven to be
  PRESENT in the prompt text (`systemPromptStatesReadinessAndPricingOrderingRule`) and the mechanism that
  would relay/store the model's choice is proven to work (`servicePricingCopiedFromCatalogueOntoSuggestion`,
  `serviceAlternativeToStoredWhenGiven`), but whether `gpt-4o` (or another configured model) actually reasons
  from pricing/readiness the way the prompt asks, on a real entity with a genuine free-vs-paid choice, is
  exactly the question a fourth real run would answer and this lot cannot.
- **The auto-fill message wording ("... (binding auto-filled by the factory: name=email)") was never seen in
  a real `AgentStep` trace** - proven correct against the fixtures in `CapabilityLlmPassTest`, but whether it
  reads clearly enough for a reviewer skimming a real trace (as opposed to a unit test's exact-string check)
  is a UX judgment nothing in this lot could make without a live run.

**Coverage lot (2026-09-21), still open** - built and tested against fixtures only, no live EBX, no real LLM
call (branch constraints):

- **Whether a real model actually groups attributes sensibly, uses `toBeProvided` instead of guessing when it
  should, and spends its now-larger detail budget across every group rather than a few, was never observed
  live** - the mechanisms are proven correct against fixtures (the grouping instruction is present in both
  prompts, `toBeProvided` is accepted/capped/refused exactly as specified, the remaining-budget message is
  shown), but whether a real deployed model (the same `gpt-4o` that produced the `Supplier` run this lot
  reacts to) actually behaves better with these changes is exactly what a fifth real run would show.
- **`Session#attributesWithoutSuggestionOrNote()` is scoped to THIS run only, unlike `quality.QualityLlmPass
  .Session#columnsWithoutProposalOrNote()`**, which also folds in pre-existing steps/rules - a deliberate scope
  decision (section 0e), not an oversight: `CapabilityLlmPass.ExistingSuggestion` carries no attribute field
  of its own (only a library/function or connector/operation target), so telling which attribute an EARLIER
  run's suggestion covered would need a new field threaded through `ebx.CapabilityLlmSuggester.readExisting`
  and the `CapabilitySuggestion` table itself - out of scope for this lot. In practice this means an attribute
  already covered by a prior run's suggestion can still show up in `attributesWithoutSuggestionOrNote` on a
  later run - a false-positive gap, informational only, never enforced, but worth fixing in a later lot if it
  proves noisy in practice.
- **`MAX_DETAIL_CALLS` = 24's own sizing (~24 000 characters worst case) was reasoned from THIS codebase's own
  fixture sizes, never measured against real `get_library_functions`/`get_service_details` output on the
  actual instance** - a library with many more exported functions, or a connector with a much longer
  operation description, could make a single detail result larger than assumed, and the existing
  `DEFAULT_MAX_PROMPT_CHARS` guard (section 0b) only checks the FIRST request, not a LATER turn after several
  detail calls have accumulated - a run that genuinely uses close to the full budget across many turns could
  still grow past 60 000 characters by its last turn without the guard ever catching it. Not fixed in this
  lot (the coordinator asked to raise the cap and document the worst case, not to add a new per-turn guard).
- **The REJECTED-aware wording ("was already proposed ... and REJECTED by a reviewer") was never seen against
  a real reviewer decision** - proven against fixtures mirroring the exact mechanism `quality.QualityLlmPass`
  already uses live, but this pass's own version of it has not itself been exercised on a real `CapabilitySuggestion`
  row a person actually rejected.
