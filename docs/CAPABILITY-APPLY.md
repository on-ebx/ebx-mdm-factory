# Capability apply: attaching an accepted SCRIPT_CONSTRAINT to the model

Written 2026-09-21, branch `feature/capability-apply`, implementing the task brief's own instruction: the
suggestion pass (`docs/CAPABILITY-SUGGESTIONS.md`) only ever proposed; nothing was attached, nothing ran. This
lot builds the "apply" step that document's own section 8 sketched but explicitly left out of scope. Only
`SCRIPT_CONSTRAINT` is in scope - `EXTERNAL_SERVICE` stays decision-only, unchanged.

Product owner's decisions, not reopened here: **direct application** - accepting a `SCRIPT_CONSTRAINT` line
applies it immediately, no separate button ("he will not read script code"); an applied line shows (1) a
PLAIN-LANGUAGE EXPLANATION and (2) the MEASURED EFFECT on the data; default severity stays `WARN`
(non-blocking, unchanged from the suggestion lot); a **Remove** action takes the constraint back off the
model.

Nothing in this lot has run against a live EBX instance, a real language-model provider, or a real
constraint's validation report - the task brief's own instruction. The MCP facts below come from the
coordinator's own live captures on a scratch DMA model (build → set_script_constraint → author → publish →
list → unset_element_property → delete, all deleted afterwards); the Java validation-report facts come from
`javap` against the real `ebx-lib.jar` on this machine (no javadoc shipped for it here - see section 6).

---

## 0. Coordinator review round 2, 2026-09-21: four fixes before merge

A coordinator review of the first cut found four real gaps, all fixed in this same branch (still never run
against a live instance):

1. **BLOCKING - a `RuntimeException` from phase B (explanation/measurement) escaped BOTH original `catch`
   clauses** (`McpException`/`OperationException` only), so a correctly declared/authored/published constraint
   could be rolled back for a reason as trivial as an NPE, and a bare `RuntimeException` was caught by
   NEITHER clause at all - no rollback, the `PipelineStep` never ended, the row stayed `ACCEPTED` while the
   constraint was actually live and undocumented. Fixed by splitting `apply` into two phases (section 2) -
   phase A catches `Exception` broadly and rolls back; phase B (`applyPhaseB`) never rolls back and never
   fails the apply, whatever it catches.
2. **Bulk accept used to call `apply` once per row - a publish per constraint.** A `publish_data_model` takes
   tens of seconds to minutes; accepting ten constraints on the same model would have published it ten times.
   Fixed by `CapabilityApplier.applyAll`/`applyModelGroup` (section 2a): every requested suggestion is
   resolved first, grouped by TARGET MODEL, and each model gets exactly ONE publish for however many
   constraints declared successfully into it.
3. **`AdaptationTable.getValidationReport()` was called unconditionally** - on a genuinely large table this
   walks every validation item, with no bound at all. Fixed: bounded to `DEFAULT_MEASURE_MAX_RECORDS` (200 000)
   automatically (phase B skips and says so); `POST .../capabilities/{id}/measure` measures on demand
   regardless of size (section 6a).
4. **Attribution used path + severity only** - any other warning on the same field/record would have been
   counted. Fixed: when the constraint's own `msg.addWarning`/`addError` argument is (or starts with) a plain
   string literal, the exact rendered message is matched too (section 6b); records are now counted DISTINCT,
   never by validation-report item.

---

## 0a. `fix/capability-apply-ux`, 2026-09-21: two more coordinator follow-ups (items 1-2 of five)

Reported live after this lot's first deployment: an `ACCEPTED SCRIPT_CONSTRAINT` row with no way to retry a
failed apply except reject-and-re-accept (item 1), and - the real defect a live run surfaced - the message a
business user reads on a flagged record was `'phone' failed phone.isValid.`, straight developer jargon built
by `ConstraintBodyBuilder`'s own naming from the attribute/function identifiers (item 2). Both fixed on this
branch, still never run against a live instance.

**Item 1 - Apply action on an accepted-not-applied line.** `review.DecisionActions.forStatus(status, nature)`
(`ebx-mdm-factory/src/main/java/com/on/ebx/factory/review/DecisionActions.java:114-119`, new
`REJECT_AND_APPLY` constant) now offers Apply + Reject (never plain Accept, never Remove/Measure again) for
`ACCEPTED` + `SCRIPT_CONSTRAINT` specifically - the case an already-`APPLIED` row (Remove/Measure again) and
every other nature/status combination are both unaffected by. `review.ReviewPage` renders the button
(`data-mdmf-action="APPLY"`,
`ebx-mdm-factory/src/main/java/com/on/ebx/factory/review/ReviewPage.java`, rendered alongside Reject/Measure
again). `ebx.ui.ProposalReviewUserService` dispatches it
(`ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProposalReviewUserService.java`) through the exact
same `CapabilityApplier.apply(...)`/`applyAll(...)` call the post-accept auto-apply uses - `decideOne`'s own
apply-and-report logic was extracted into a shared `applyOne(UserServiceEventContext, String)` so the two
entry points cannot drift, and the new `applyOneDirectly` (reading the posted `mdmfId`) is the Apply button's
own handler. The synchronous "please wait" cue (section 7a) is armed for `action==='APPLY'` the same way it
already was for `ACCEPT`/`REMOVE`/`MEASURE`. `applyMessage` from a previous failed attempt was already shown
inline on an `ACCEPTED` row before this change (`review.capability.applyFailed`, section 1) - unchanged, now
covered by a dedicated test sitting right next to the new button.

**Item 2 - the message a business user reads now comes from the language model, not from Java-built
identifiers.** `suggest_script_constraint`'s schema gained a REQUIRED `message` string argument
(`ebx-mdm-factory/src/main/java/com/on/ebx/factory/capability/CapabilityLlmPass.java`, `SUGGEST_SCRIPT_
CONSTRAINT_SCHEMA`) - refused if missing/blank (`Session.suggestScriptConstraint`, lines 1250-1259), then run
through `CapabilityLlmPass.sanitizeUserMessage` (lines 158-179: trims, collapses internal whitespace to a
single space, truncates to `USER_MESSAGE_MAX_LENGTH` = 160 characters - never refused merely for being too
long or multi-line, only for being empty/unusable after sanitising). The system prompt
(`systemPrompt()`) now tells the model who reads this sentence and how to write it: a business user, plain
language, no code identifiers, ≤160 characters. Stored as a new additive schema field, `userMessage`
(`tools/build_xsd.py`, between `binding` and `severity`; `core.FactoryPaths.CapabilitySuggestion.USER_
MESSAGE`), written by `ebx.CapabilityLlmSuggester` alongside the row's other fields.

At apply time, `ebx.CapabilityApplier.declareAndAuthor` (lines 671-676) computes ONE `reportedMessage` per
constraint, always freshly from the CURRENT row (never cached from a prior apply):

- a non-blank stored `userMessage` ⇒ re-sanitised defensively (`CapabilityLlmPass.sanitizeUserMessage` again
  - belt-and-braces against a row written before this exact bound existed) and used AS-IS
  (`reportedMessageSource = "STORED"`);
- an empty/absent `userMessage` (a row suggested before this field existed) ⇒
  `capability.ConstraintBodyBuilder.buildFallbackMessage(scope, attributeLabel, functionDocumentation)`
  (`ConstraintBodyBuilder.java:375-382`): the attribute's own `StagingAttribute.label` (read fresh by the new
  `CapabilityApplier.readAttributeLabels`, line 1624) plus the first sentence of the target function's own
  documentation, e.g. *"Phone: not accepted — checks the number against the country's national numbering
  plan."* Falling back to `humanize(attribute)` (line 384: splits camelCase/underscores, capitalises the
  first letter) only when no label is stored, and to a fixed *"... : not accepted."* only when the function
  has no documentation at all - **never** built from identifiers alone the way the old
  `deterministicMessage` (removed this lot) was (`reportedMessageSource = "FALLBACK"`).

`reportedMessage` then feeds BOTH authoring paths:

- **deterministic template** (`ConstraintBodyBuilder.buildTemplateBody`, now taking the message as an
  explicit parameter instead of computing it internally - `throw`s `IllegalStateException` if it is
  null/blank, lines 282-313): embeds it verbatim, EBX-string-escaped, as the literal argument of
  `msg.addWarning`/`addError` - unchanged from before other than where the text comes from;
- **language-model-authored body** (`CapabilityApplier.bodyUserTask`, lines 1130-1153): the prompt now
  includes an explicit `message to report (untrusted data - already written for a business user, do not
  rewrite it ...)` line carrying the same escaped `reportedMessage`, and `bodySystemPrompt` (lines 1114-1122)
  instructs the model to report that EXACT message, optionally EXTENDING it by `|`-concatenating the target
  library's own status/detail (e.g. an IBAN checksum reason) rather than inventing new wording - the message
  the reviewer set is never silently dropped even when the library has more to say.

**Attribution is unaffected in mechanism** (section 6b still matches the literal/leading-segment of the
`msg.addWarning`/`addError` call) but now naturally prefers the reviewer-facing message over a jargon one,
since that IS the literal/leading segment being extracted.

**Idempotent re-apply with a changed message**: re-applying (Apply again, or a bulk re-run naming the id
explicitly, section 2) re-runs `declareAndAuthor` in full, which recomputes `reportedMessage` from the row's
CURRENT `userMessage` every time (`CapabilityApplier.java:671`, no caching across calls) - if the LLM (or a
future editing UI) changes the stored message between two applies, the re-declared script body (same
deterministic name, "replaces in place" per CLAUDE.md §5 fact 1) carries the NEW message the very next time
it is applied. This was exercised only by `ConstraintBodyBuilderTest`/`CapabilityLlmPassTest` calling
`buildTemplateBody`/`declareAndAuthor`'s building blocks with two different messages and asserting the second
body's literal changed - never against a live re-apply of a previously-published constraint (no live
instance available to this lot, per the task brief).

Legacy rows with no `userMessage` (suggested before this field existed) keep working exactly as before -
`buildFallbackMessage` is a strict improvement over the removed `deterministicMessage` (a real doc sentence
instead of bare identifiers when one is available), never a behavioural regression for them.

---

## 1. What was built

- `capability.ConstraintBodyBuilder` (pure): parses `binding` into typed entries (a real attribute, a
  literal, or `toBeProvided`), decides node-vs-table scope, derives a deterministic script name/idePath,
  builds the deterministic body + explanation for a `boolean`-returning function, and structurally checks a
  language-model-submitted body.
- `capability.ConstraintBodySubmission` / `capability.ConstraintExplanationSubmission` (pure): the retry/
  validation state behind the two local tools (`submit_constraint_body`, `submit_explanation`) the language
  model gets when a body needs authoring - kept out of the EBX-bound class specifically so the retry/budget
  logic is unit-tested without an `AgentLoop`/HTTP layer, the same split `capability.CapabilityLlmPass` /
  `ebx.CapabilityLlmSuggester` already established for the suggestion pass.
- `ebx.CapabilityApplier`: drives the `data-model`/`scripting` MCP servers and, for a non-boolean function,
  one or two bounded `AgentRuntime` runs; measures the effect in Java against the just-published model;
  writes the row. Two phases (section 2) and one-publish-per-model batching (section 2a), both added by the
  round-2 coordinator review.
- Routes (`rest.FactoryResource`, administrators only): `POST /projects/{code}/capabilities/{id}/apply
  [?measureMaxRecords=N]` (single), `POST /projects/{code}/capabilities/apply[?ids=a,b,c][&measureMaxRecords=N]`
  (bulk, round 2), `POST /projects/{code}/capabilities/{id}/remove`, `POST /projects/{code}/capabilities/{id}
  /measure` (round 2, on-demand, unbounded).
- Review screen: accepting a `SCRIPT_CONSTRAINT` line calls `CapabilityApplier.apply` directly
  (`ebx.ui.ProposalReviewUserService#decideOne`) - the accept click IS the trigger, no new button. An
  `APPLIED` `SCRIPT_CONSTRAINT` line shows its explanation and measured effect and offers Remove AND Measure
  again (round 2) instead of the generic "cannot be changed any more" chip
  (`review.DecisionActions#forStatus(String, String)`, a nature-aware overload - every other nature/status
  combination is unchanged). An `ACCEPTED` `SCRIPT_CONSTRAINT` that is NOT (yet) applied offers Apply +
  Reject instead (`fix/capability-apply-ux` item 1, section 0a). A failed apply attempt leaves the line
  `ACCEPTED` with `applyMessage` appended to the rationale (`review.capability.applyFailed`); a phase-B-only
  shortfall on an otherwise `APPLIED` line reads with different wording (`review.capability.applyWarning`,
  round 2 - "Note: ...", never "Apply failed"). A bulk-accept confirmation naming any `SCRIPT_CONSTRAINT`
  candidate says how many constraints go to which model and that one publication per model follows (round 2,
  `ProposalReviews.capabilityModelCounts`). Because the whole request is synchronous and a publish can take a
  minute or more, clicking Accept (on a `SCRIPT_CONSTRAINT` line)/Apply/Remove/Measure again/a bulk-accept
  confirmation shows an immediate "please wait" notice client-side, before the real postback fires (round 2,
  `ProposalReviewUserService.PAGE_JS`'s own `applyingCue`) - see section 7a.
- Schema (additive - `tools/build_xsd.py`): fourteen fields on `CapabilitySuggestion` (`scriptName`,
  `constraintScope`, `appliedModel`, `appliedElementPath`, `appliedScriptIdePath`, `scriptBody`,
  `explanation`, `recordsChecked`, `recordsReported`, `offendingShapes`, `measuredAt`, `appliedAt`,
  `applyMessage`, and - `fix/capability-apply-ux` item 2, section 0a - `userMessage`) plus `PipelineStep.step`
  values `CAPABILITY_APPLY`/`CAPABILITY_REMOVE`. `git diff` of the regenerated XSD touches exactly these,
  nothing else moved.

---

## 2. Sequence - two phases (`ebx.CapabilityApplier`, reshaped by the round-2 coordinator review)

Traced as one `PipelineStep` per constraint (`CAPABILITY_APPLY`, scope = the suggestion's own row id) via the
shared `ebx.PipelineSteps` (package-private, reused directly - `CapabilityApplier` lives in the same `ebx`
package), begun as soon as the constraint's own model is known to exist (or fails to) and ended once that
constraint's own phase A + phase B are both over.

**`prepare`** (no MCP call) resolves the project, the suggestion row (checked against the project and the
entity), the target model name (`staging.StagingNames.modelName`/`master.MasterNames.modelName`, from
`CapabilitySuggestion.zone`), the binding, and the scope/name - refused outright, before any MCP call, when:
the suggestion is not `SCRIPT_CONSTRAINT`; its status is not `ACCEPTED` or `APPLIED`; its binding still has a
`toBeProvided` parameter; a binding-bound attribute (or the fallback `attribute`) is not (or no longer) an
accepted attribute of the entity. A `prepare` failure is recorded as that one suggestion's own failed result;
it never touches the others.

**Phase A** (`applyModelGroup`, per constraint, inside one model group - section 2a):

1. **Verify the target exists**: `get_data_model(data_model_name, deep=true)` (fetched ONCE per model, shared
   by every constraint of the group) - refused with a clear message naming what to run first ("generate the
   staging/master zone first", "this entity may not be promoted to the master zone yet", "run staging/generate
   first") when the model, the table, or (for a node constraint) the field is missing. Reuses
   `staging.StagingModelDiff.parseExisting`/`ExistingTable.field` (widened to `public` for this lot) rather
   than a second JSON reader for the same `tables[]` shape.
2. **Declare** (`declareAndAuthor`): `data-model.execute_operation(set_script_constraint)` with
   `{element_path, name, type: ConstraintOnNode|ConstraintOnTable, scope: node|table}` (CLAUDE.md §5, fact 1).
   Refused outright if the response's own `publishSafe` is `false` (the model lacks EBX's script component -
   the declaration would be silently dropped at publish). From here on, `p.declared = true`: any later
   failure THIS RUN triggers rollback (section 5).
3. **Author**: re-fetch the target library's source (`scripting.get_script`, `includeSource: true`) and parse
   its exported functions (`capability.ScriptInventory.parseExportedFunctions`, already built for the
   suggestion lot - reused, not duplicated) to find the function's current parameter list/return type/doc -
   the `CapabilitySuggestion` row itself never stored these, only `targetLibrary`/`targetFunction`/
   `targetImportName`. `boolean` return ⇒ `ConstraintBodyBuilder.buildTemplateBody` (deterministic, no LLM
   call). Anything else ⇒ one `AgentRuntime.run` with a single local tool, `submit_constraint_body`, bounded
   to `CapabilityApplier.MAX_LLM_ATTEMPTS` (3) accepted calls (section 4). The resulting source is written
   with `scripting.update_script({scriptIdePath, source, revisionId, confirmReplaceMetadata: true})` -
   `revisionId` read fresh from `get_script` on the DECLARED skeleton's own idePath, right before the write
   (CLAUDE.md §5, fact 3: `update_script` needs the current revision). Refused if `data.success` or
   `data.validation.isSuccess` is not `true`.
4. **Publish** - ONCE for the whole model group, only after every constraint's own declare+author has been
   attempted (section 2a).
5. **Verify authored**: `execute_query(list_script_node_constraints|list_script_table_constraints)`, matching
   the just-declared `scriptName` and checking its own `authored` field (CLAUDE.md §5, fact 7) - per
   constraint, after the group's own publish succeeded.

**A `Exception` (not only `OperationException`/`McpException`) anywhere in phase A** - the round-2 fix -
triggers rollback for that ONE constraint (section 5) and ends its own `PipelineStep` in failure; the rest of
its group, and every other group, continue unaffected.

**Phase B** (`applyPhaseB`, per constraint that made it through phase A - round-2 fix, its own section 0/2
item 1):

6. **Explanation**: `boolean` return ⇒ `ConstraintBodyBuilder.buildTemplateExplanation` (deterministic,
   cannot fail). Other return type ⇒ a SEPARATE `AgentRuntime.run`, given ONLY the final validated source and
   the function's own documentation (NOT the suggestion's rationale - the task brief's own instruction, "so a
   mismatch with the accepted suggestion is visible") - if THIS fails, `fallbackExplanation` (a deterministic
   minimum: which function, which attribute(s), warns-or-blocks) is used instead, and the shortfall is noted.
7. **Measure**: section 6/6a - bounded automatically, always attempted, a shortfall (too large, or the
   measurement call itself failing) is noted, never fatal.
8. **Write** the row `APPLIED` with every applied-* field, `scriptBody`, `explanation` (real or fallback),
   whatever measurement succeeded (or none), `measuredAt`/`appliedAt`, and `applyMessage` set to the JOINED
   phase-B warnings (or cleared, when both succeeded in full) - **never a rollback, never a failed apply**:
   phase A already proved the constraint is correctly declared, authored and published.

Re-applying an already-`APPLIED` row is NOT skipped: the whole sequence runs again for it (safe - "same name
replaces in place", CLAUDE.md §5, fact 1) and the row is re-measured. This is what "re-checks the model"
means in the task brief - not a true no-op, but idempotent in the sense that the end state is the same
whether the row was `ACCEPTED` or already `APPLIED` going in.

---

## 2a. Batching - one publish per model (round-2 fix)

`CapabilityApplier.applyAll(session, factory, projectCode, ids)` (`apply(...)` for a single id is a
one-element call of the same path, throwing on that one result's own failure - the pre-batching contract,
unchanged for single-id callers) - `applyModelGroup` per target model:

1. Every requested id is `prepare`d first (no MCP call); a `prepare` failure is recorded immediately, the id
   drops out of every later step.
2. The prepared constraints are grouped by `modelName` (`LinkedHashMap`, insertion order preserved).
3. Per model group: `get_data_model` ONCE (shared `tables[]` for every constraint's own existence check);
   declare+author each constraint in turn, each in its own `try`/`catch(Exception)` - a failure here rolls
   back and reports THAT constraint alone (`rollback(..., published=false, ...)`: nothing has been published
   yet this round, so only the declaration itself is removed, no republish).
4. If at least one constraint in the group declared+authored successfully: exactly ONE
   `publish_data_model(modelName)` call for the whole group.
   - **Publish fails**: every constraint that WAS declared this round is rolled back (declaration removed,
     still no republish - the group's own publish never actually took effect) and reported failed with the
     SAME message ("publish_data_model failed for `<model>`: ...").
   - **Publish succeeds**: each constraint is verified (`authored:true`) individually; a verify failure for
     ONE constraint rolls back and republishes for THAT constraint ALONE (`item=<its own scriptName>` only
     ever touches its own entry of the model's constraint list - the siblings that published successfully
     alongside it are untouched) - a second `publish_data_model` call, unavoidable for correctness in this one
     failure path (the coordinator's own instruction: "correctness over optimization" here).
5. Every constraint that passed phase A runs phase B (section 2) independently; nothing in phase B can affect
   another constraint of the same group.

The bulk route's own response (`POST /projects/{code}/capabilities/apply`) never throws for a per-suggestion
failure - `{"results":[{"suggestion","ok","status"|"error",...}, ...], "applied":M, "failed":K}`. `ids`
omitted/empty defaults to every `ACCEPTED SCRIPT_CONSTRAINT` of the project not yet `APPLIED` (an already
`APPLIED` row is only re-applied by naming its id explicitly - re-checking a live constraint is a deliberate
act, not a side effect of "apply everything", `defaultAcceptedIds`).

---

## 3. Naming and scope (`ConstraintBodyBuilder`)

- **Scope** (CLAUDE.md §5, fact 5): the binding's distinct ATTRIBUTE-kind entries (never literal/
  toBeProvided) are counted. Exactly one ⇒ `NODE` on that field (`setDependencyToLocalNode()` is honest).
  Zero or more than one ⇒ `TABLE` - EXCEPT zero with the suggestion's own top-level `attribute` set, which
  still resolves to `NODE` on that attribute (a constraint genuinely about one field even though none of its
  *arguments* happen to read another one, e.g. a literal-only check against a fixed pattern).
- **Name**: `<Entity>_<Function>` (TABLE) or `<Entity>_<Function>_<Attribute>` (NODE), sanitised to
  `[A-Za-z][A-Za-z0-9_]*`, truncated to 64 characters (the schema's own `maxLength`) with a leading `C` and no
  trailing underscore if truncation would otherwise break either rule. Deterministic: the same
  `{entity, function, scope, attribute}` always yields the same name, so re-applying (or re-accepting an
  identical target after an earlier reject) replaces the same declared constraint in place rather than
  accumulating dead ones.
- **Element path**: `/root/<Entity>` (TABLE) or `/root/<Entity>/<Attribute>` (NODE) - what `set_script_
  constraint`/`unset_element_property` act on.
- **idePath**: `data-model/<Model>/root/<Entity>/<Attribute>/@nodeConstraint/<Name>` or
  `data-model/<Model>/root/<Entity>/@constraint/<Name>` (CLAUDE.md §5, fact 2's own deducible pattern).

---

## 4. Body authoring

**Deterministic template** (`boolean`-returning function only): a null guard
(`if isNull(record.<attr>) then return;`) on every DISTINCT bound attribute the caller says is not
`StagingAttribute.mandatory` (an absent/unknown mandatory flag is treated as "guard it", the safe default),
then `if not <alias>.<function>(<args>) then begin msg.add(Warning|Error)('<message>'); end`, `setup()`
calling `v.setDependencyToLocalNode()` for a NODE constraint (empty for TABLE, matching the raw skeleton's
own template - CLAUDE.md §5, fact 2). A literal argument renders as a bare number/boolean when the
parameter's declared type says so and the value actually parses as one, a backslash-escaped string literal
(CLAUDE.md §2 - `'it\'s'`, never doubled quotes) otherwise.

**The message text (`fix/capability-apply-ux` item 2, section 0a supersedes the original "a short neutral
sentence ... no business vocabulary invented by Java" task-brief wording)**: `buildTemplateBody` no longer
computes it - it takes `reportedMessage` as a caller-supplied parameter and throws `IllegalStateException` if
it is null/blank (`ConstraintBodyBuilder.java:282-313`). The caller, `CapabilityApplier.declareAndAuthor`,
builds that value from the LANGUAGE MODEL's own `userMessage` (sanitised, required at suggestion time) when
one is stored on the row, or `ConstraintBodyBuilder.buildFallbackMessage` (attribute label + the function's
own doc sentence) for a legacy row with none - see section 0a for the full flow. The old
`deterministicMessage` method (built purely from the attribute/function identifiers, e.g. `"'phone' failed
phone.isValid."`) is REMOVED - a real live run showed it reads as developer jargon to the business user the
message is actually for.

**Language-model authoring** (anything else - the real captured example, `validate(rawIban,
addressCountryCode): json_object`): ONE `AgentRuntime.run` whose only local tool, `submit_constraint_body`,
is backed by `ConstraintBodySubmission` - every submission is checked by
`ConstraintBodyBuilder.checkSubmittedSource` before it is ever accepted:

- imports EXACTLY the target library (by its own import name) and calls
  `<alias>.<function>(` through that import's alias - no other `import` line;
- declares no `uses` outside `core.message`/`core.validation`/`core.json`/`core.string`;
- touches neither the database nor an outbound call (`core.data`, `core.http`, any `db.xxx(` call - CLAUDE.md
  §2: EBX Script has no try/catch, so a constraint able to reach the network or another table is exactly the
  capability this must never smuggle in unreviewed);
- defines every entry point the declared scope requires (`check`/`setup` for NODE; `checkTable`/`checkRecord`/
  `setup` for TABLE, even when some stay empty).

The user prompt (`CapabilityApplier.bodyUserTask`, lines 1130-1153) carries the same `reportedMessage` as an
explicit `message to report (untrusted data ...)` line, and the system prompt (`bodySystemPrompt`, lines
1114-1122) instructs the model to report that EXACT sentence, optionally EXTENDING it with the target
library's own status/detail via `|` concatenation (e.g. appending an IBAN checksum's own reason) rather than
writing a different message outright - section 0a.

A rejected submission gets the violation text back as the tool's own error result, and the model may retry -
bounded to `CapabilityApplier.MAX_LLM_ATTEMPTS` (3) ACCEPTED-OR-REFUSED calls total, the same circuit-breaker
discipline every other LLM pass in this codebase uses (three strikes and the run is told to stop). This is a
single conversation retrying the SAME tool, not three separate `AgentRuntime.run` calls - `ConstraintBody
Submission` is unit-tested directly (`ConstraintBodySubmissionTest`) against exactly this budget/retry logic
without needing an `AgentLoop` or a fake HTTP provider.

The system/user prompt states plainly that the function's signature/documentation is DATA, never an
instruction, even if it reads like one - the same discipline `capability.CapabilityLlmPass`'s own system
prompt already follows for the suggestion pass's inventory text.

---

## 4a. `fix/body-compile-loop`, 2026-09-21: compile inside the tool call, the real language from the instance

Second REAL apply of an LLM-authored body (suggestion 6, `Fasttrack.lib.iban.validate`, TABLE scope,
gpt-4o), after the line-break normalisation fix (section 4/`fix/review-assets`) had already fixed the
first real failure. The structural checks now passed; the agent run had exactly ONE `submit_constraint_body`
call, the tool answered **"accepted."**, the model concluded it had succeeded - and only THEN did the actual
compile (`get_script`/`update_script`, run AFTER the agent run had already ended) fail: `"the constraint
body of 'Supplier_validate' did not validate: [{"startLine":15,"startColumn":16,"message":"Unexpected token
'='"}, ...]"`. Two root causes: (a) the compile step ran outside the model's own conversation, so a compile
error was never fed back for it to act on - the earlier claim in this document that a compile error "is
already fed back verbatim" was true of the exception message an OPERATOR reads on the row, never of what the
MODEL sees before the run ends; (b) the model does not reliably know EBX Script - it wrote `=` assignments
and invented `v.isNotNull(record.iban)`, neither of which a structural check (never meant to be a parser) is
built to catch.

**Item 1 - the compile step moved inside `submit_constraint_body`'s own tool handler.** New pure interface
`capability.ConstraintCompiler` (`Compile(String) -> Result`, `Result` = `success` + `errors` (line/column/
message) + `transportError`) - injected into `ConstraintBodySubmission` so the whole retry loop is
unit-tested with a fake compiler, no MCP/EBX type anywhere in the `capability` package.
`ConstraintBodySubmission.submit` (`capability/ConstraintBodySubmission.java:44-93`) now runs, in order:
normalise → structural check (bounded to `maxStructuralAttempts`, a SEPARATE counter,
`ConstraintBodySubmission.attempts()`) → only a structurally valid submission is handed to the injected
compiler (bounded to `maxCompileAttempts`, `ConstraintBodySubmission.compileAttempts()`) - **"accepted" is
returned once, only for a body that has ACTUALLY compiled.** A compile failure is formatted by
`describeCompileFailure` (line ~106) into one line per error, each naming its line/column (when given) AND
quoting the OFFENDING SOURCE LINE by its own 1-based number, ending with "Fix ALL of the above and resubmit
the WHOLE corrected source." - the real captured defect (a compile error the model never even saw) cannot
recur structurally, since there is no path from a failed compile to "accepted" any more.

The real compiler, `ebx.CapabilityApplier.McpConstraintCompiler` (`ebx/CapabilityApplier.java`, private
nested class near `declareAndAuthor`), is the ONLY class allowed to call `get_script`/`update_script` -
exactly the same two calls `declareAndAuthor` used to make once, AFTER the agent run, now made from INSIDE
the tool handler, once per real attempt, each recorded in the step detail as its own `compileAttempt<N>`
entry (never overwriting a previous one, unlike the old single `"update"` key). The DETERMINISTIC template
path (`boolean`-returning functions) uses the exact same compiler, called ONCE, un-retried, and throws on
failure - a template compile failure is a bug in `ConstraintBodyBuilder` itself, never something an LLM
retry could fix.

**Final failure (compile budget exhausted, or structural budget exhausted) is UNCHANGED behaviour**:
`runLlmBodyAuthoring` still throws `OperationException` when `!submission.succeeded()`
(`ebx/CapabilityApplier.java`, near the end of that method) - the existing `applyModelGroup` `catch (Exception
ex)` around `declareAndAuthor` (section 5) still does exactly what it always did: `rollback` removes the
declaration (with `item`, CLAUDE.md §5 fact 8), nothing is published (the group's own publish only happens
after every constraint's declare+author has been attempted), and the row stays `ACCEPTED` with `applyMessage`
naming the last error. Nothing about the rollback/failure-reporting path itself changed - only what happens
BEFORE the eventual throw.

**Item 2 - the real language, from the instance, not from memory.** `ebx.CapabilityApplier.fetchSyntaxGuide`
calls the scripting server's `get_syntax_guide` with `scriptType` = `constraintOnNode`/`constraintOnTable`
(from the constraint's own scope) and `sections: "all"`. Read defensively per the coordinator's own
instruction: the answer is plain MARKDOWN TEXT through this codebase's MCP client, not a JSON object - a
`data` field that is itself a STRING is preferred (a server wrapping it in `structuredContent`), the tool
result's own plain text content is the fallback, never an assumption that either shape is present. Bounded
by the new pure `capability.SyntaxGuideBudget` (`DEFAULT_CHAR_BUDGET` = 20 000 characters, **a judgment call,
not a measured limit - no live instance was available to this lot to see the real guide's actual size**,
documented in the class's own Javadoc for a future session with a live instance to revisit): splits the
document on markdown headings, ALWAYS keeps the preamble and any section whose heading names the script type
(case-insensitive) WHOLE regardless of size, then keeps as many of the remaining ("general") sections, in
original order, as fit - dropping the first one that does not fit, and everything after it, WHOLE, never a
partial section or a cut sentence. How many characters were available/sent, and whether anything was trimmed,
is recorded in the step detail (`syntaxGuideCharsAvailable`/`syntaxGuideCharsSent`/`syntaxGuideTrimmed`). If
the scripting server is unreachable (or answers nothing usable), `fetchSyntaxGuide` catches it, records
`syntaxGuideError`, and the run continues with items 3-4 alone - never fails the apply over reference
material.

**Items 3-4 - hard facts and two proven bodies, stated verbatim.** Moved into a new pure class,
`capability.ConstraintBodyPrompt.systemPrompt(ScopeDecision, syntaxGuideText)` (no EBX/MCP type - the whole
system prompt used to be built entirely from static text plus the two arguments it already had, so nothing
stopped it living in `capability` and being unit-tested directly, `ConstraintBodyPromptTest`). States, as
plain facts the model must follow exactly (all verified on this instance, CLAUDE.md §2/§5): assignment/
declaration is `:=`, never `=` (`=`/`<>` are comparisons); boolean operators `and`/`or`/`not`; the null test is
the built-in `isNull(x)` - **explicitly**, "there is NO 'isNotNull', no 'v.isNotNull'" (the exact invention the
second real failure made); string concatenation `|`; single-quoted string literals with backslash escapes;
`value.length` is a property; no try/catch; never index a list with a literal; every entry point of the
required template stays defined even when empty; `begin ... end` blocks; `return;` leaves a procedure; the
four proven `uses ... as <alias>;` header lines verbatim. Then the two proven example bodies (the NODE/boolean
phone example, and the TABLE/`json_object` IBAN example with its status read via `json.getString`), followed
by scope-specific entry-point instructions (NODE: `check`+`setup` with `setDependencyToLocalNode()`; TABLE:
`checkTable`(empty)/`checkRecord`(logic)/`setup`(empty, explicitly **not** calling
`setDependencyToLocalNode()`, "that call is NODE scope only")) - `bodyUserTask` (`ebx/CapabilityApplier.java`)
is unchanged, still the one carrying the actual function signature/documentation/binding/message as data.

**Item 5 - the explanation now genuinely explains the COMPILED source, by construction.** `p.source` is only
ever assigned from `submission.source()` (LLM path) or after a successful one-shot compile (template path) -
`applyPhaseB`'s own `runLlmExplanation(..., p.source, ...)` call is textually unchanged, but what it receives
can no longer be anything other than a body that has actually compiled, since `declareAndAuthor` cannot reach
phase B without first returning a compiled `p.source` (or throwing, which skips phase B entirely, section 5).

**Not verified**: no live re-run of this exact suggestion (or `get_syntax_guide` against a real scripting
server) was available to this lot - the compile-retry loop, the defensive `get_syntax_guide` response parsing,
and the trimming behaviour on a REAL guide document (whose actual size and heading structure are both
unknown) are all exercised only against fake/synthetic fixtures. Whether `uses core.message;` without an
alias is genuinely an EBX compile error remains unconfirmed either way - deliberately left to the compiler,
never hard-coded as a structural rule (section 4's own item-2 note, unchanged).

---

## 4b. `fix/node-scope-multi-attribute`, 2026-09-21: every constraint is NODE, always - a coordinator design rule proven wrong live

Third REAL apply, this time of the LLM-authored body `fix/body-compile-loop` (section 4a) fixed the compile
loop for (suggestion 6, `Fasttrack.lib.iban.validate`) - it compiled on attempt 1, `Supplier_validate`
authored `true`. Deployed and left running, it surfaced a DIFFERENT, deeper bug: exactly 1 of 47 staging
IBANs was invalid (supplier V100110, MOD-97 remainder 8) - visible only when the product owner manually
opened that one record, while the factory's own dataset-wide measurement reported `recordsReported: 0`. The
instance's own `get_syntax_guide(constraintOnTable)` states verbatim why: a TABLE constraint's message is
attached to the TABLE's validation report only when reported from `checkTable`; the same call from
`checkRecord` is attached to the RECORD being validated, and `checkRecord` only fires when ONE record is
validated (a form) - never on a whole-dataset pass. This document's own section 3 (and `ConstraintBodyBuilder
.chooseScope`'s pre-this-round Javadoc) had it backwards: "several bound attributes ⇒ TABLE constraint with
`checkRecord`" reads as "runs on every record" but does the opposite - a TABLE constraint with all its real
logic in `checkRecord` and an empty `checkTable` (exactly what this codebase used to generate) validates
NOTHING on a whole-dataset pass.

**The fix, proven live on a scratch model (declared, compiled, published, then deleted) before being applied
here:** a `SCRIPT_CONSTRAINT` is now ALWAYS a NODE constraint, on its ANCHOR attribute - never TABLE. Several
bound attributes no longer change the SCOPE; they change `setup()`, which now declares an EXPLICIT dependency
on every distinct bound attribute (anchor included) via `core.validation.addDependencyToModify(path)`
(absolute path, `/root/<Table>/<field>`; API help, verbatim: "declare that the validation result of the
current constraint depends on modifications of the field specified by its path ... must be called from the
procedure setup") instead of the single-field `setDependencyToLocalNode()`.

**Item 1 - `chooseScope` never returns TABLE any more** (`capability/ConstraintBodyBuilder.java:200-205`).
`Scope.TABLE` stays in the enum (`remove`/`measure` still read a PRE-EXISTING row's own stored
`constraintScope` to act on a constraint that is still live as a TABLE one until migrated - item 2 below), but
nothing on the apply path produces one going forward. New `dependencyAttributes(ScopeDecision)`
(`:213-229`) - anchor first, then every distinct bound attribute - and `buildSetupBlock(ScopeDecision,
entityName)` (`:243-262`) - `v.setDependencyToLocalNode();` at exactly one dependency, one
`v.addDependencyToModify(...)` call per dependency otherwise - are the two new pure building blocks both
authoring paths now share. **The factory writes `setup()` itself in BOTH paths, never trusting the model's
own** (the coordinator's own instruction, "pick one, justify, test" between overwrite-vs-reject): the
deterministic TEMPLATE path (`buildTemplateBody`, `:440-498`) embeds `buildSetupBlock`'s output directly and
now THROWS `IllegalStateException` if handed a TABLE `ScopeDecision` (defensive - `chooseScope` cannot produce
one, so reaching this branch means a caller reconstructed a `ScopeDecision` by hand); the LLM path
(`capability/ConstraintBodySubmission.submit`, `:102-151`) lets the model write its own `setup()` as a
placeholder (Example 1 in the prompt shows one), then OVERWRITES it verbatim with
`ConstraintBodyBuilder.replaceSetupBlock` (`:276-330`, a small `begin`/`end` depth counter - not a real
parser, tracks nested blocks so an inner `if ... begin ... end` inside the model's own `setup()` is never
mistaken for the outer block's own close) BEFORE the result is ever compiled. **Chose overwrite over "refuse a
submission whose setup differs from the expected one"** (`ConstraintBodySubmission`'s own class Javadoc,
`:14-51`, numbered item 1): computing the exact expected `addDependencyToModify` list correctly requires
knowing every distinct bound attribute, which is exactly the kind of bookkeeping a model is more likely to
get wrong (and would then spend a whole compile/structural attempt discovering) than Java is, from data it
already has - overwriting removes an entire class of retries rather than diagnosing them.

**Item 2 - migrating what is already applied as TABLE, exposed as the same Apply route.**
`ebx.CapabilityApplier.prepare` (`:344-451`) reads a row's own STORED `constraintScope` (never its current
`status`) and, when it reads `TABLE` with all three of `appliedModel`/`appliedElementPath`/`scriptName`
non-empty, builds a `TableMigration` (`:329-...`) carried on the `PreparedConstraint`. `declareAndAuthor`
(`:721-734`) removes the old TABLE declaration FIRST, via the exact same `removeDeclaration` helper
`rollback` already used (`unset_element_property`, `item`=scriptName, CLAUDE.md §5 fact 8), tolerant of
`looksAlreadyRemoved` (a retry after a migration that already removed it finds nothing there any more, and
that is not an error) - then declares the new NODE constraint as normal. Both changes land in the SAME model
draft, so `applyModelGroup`'s own single `publish_data_model` call (unchanged, section 2a) publishes the
removal and the new declaration together, in ONE publication - never two. If the removal itself fails for a
real reason, this throws BEFORE `p.declared` is ever set, so `rollback` has nothing to undo and the row stays
exactly as it was (still live as the OLD table constraint) - safe to retry. **"Say so in the response"**: a
migrated run's result JSON carries `"migratedFromTable": true` (`applyPhaseB`, `:983-989`) and the
`PipelineSteps.end` message names it explicitly ("... (migrated from a TABLE constraint)").

**Item 3 - null-guard the anchor only, not every bound attribute, in the LLM path.** The prompt
(`capability/ConstraintBodyPrompt.systemPrompt`, no longer taking a `ScopeDecision` argument at all - every
call site is NODE now) states: guard ONLY the anchor attribute for null; pass every OTHER bound attribute to
the function as-is UNLESS the function's own documentation says a null there is not accepted. Names the real
captured mistake explicitly - the deployed body guarded a second attribute (`country`) whose own doc says a
null there "disables the comparison", silently skipping every record where only that field was empty (an IBAN
with no country address was never checked). **Deliberately scoped to the LLM prompt only** - the deterministic
TEMPLATE path's existing guard logic (`buildTemplateBody`, every non-mandatory bound attribute gets a null
guard) was left UNCHANGED: item 3 is a prompt-writing instruction for a model that can read a function's doc
and use judgment about "unless the doc says otherwise" - Java code has no such judgment to apply generically,
and the template's existing behaviour is already correct/tested for every case actually exercised.

**Item 4 - the record-facing message, written by the model itself when the row has none.** The earlier
fallback (`buildFallbackMessage`, pre-this-round) echoed the function's own raw documentation SENTENCE -
unusable for a doc reading "Validates an IBAN and compares its country with the one of the postal address.
Never raises: inspect 'valid' first, then 'status'." Two changes: (a) `buildFallbackMessage` (now 2-arg,
`capability/ConstraintBodyBuilder.java:515-519`) is simplified to `"<label>: value not accepted."` and nothing
else, used ONLY by the deterministic TEMPLATE path (which has no model to write anything better); (b) the LLM
path's `submit_constraint_body` tool gets a conditional REQUIRED `message` argument
(`ebx.CapabilityApplier.runLlmBodyAuthoring`, `:1300-1359`, `modelMustWriteMessage = pStoredReportedMessage ==
null`) when the row has no stored `userMessage` - validated/sanitised the SAME way
`suggest_script_constraint`'s own `message` argument already is (`CapabilityLlmPass.sanitizeUserMessage`,
required/single-line/bounded to 160 characters, reused directly - `ConstraintBodySubmission.submit`,
`:117-133`). On success, `p.llmWrittenUserMessage` is backfilled onto the row's own `userMessage`
(`CapabilityApplier.writeApplied`, new `pLlmWrittenUserMessage` parameter, `:1707-1756`) so a LATER
re-apply/measure of the same row reads a real stored value instead of re-deriving a fallback every time.

**Item 5 - measurement, unchanged mechanism, new fixture.** `measureEffect`'s own `expectedPath` computation
(section 6) already correctly reads `Path.parse("./" + scope.attribute)` for NODE scope - no production code
change was needed here, since every constraint is NODE now the NODE branch is simply the only one exercised
going forward. Added a fixture-level test of the prefix-match attribution for the exact shape this fix
produces - a message followed by an appended status, `"The IBAN is not valid (" | status | ")."` -
`ConstraintBodyBuilderTest.extractReportedMessageConcatenationWithStatusSuffixIsPrefixMatchOnly`. Expected on
the real data after migration: `recordsChecked 55, recordsReported 1`, the offending IBAN's own shape
(`AA99…`) - not independently re-measured this round (no live instance access), see section 8.

**Item 6 - failed/unapplied-apply lines were invisible behind the default filter.** The product owner could
not find suggestion 6's own row: the review screen opens on Status = Pending (`ReviewFilter.defaultFilter()`,
status `PROPOSED`), but the row was `ACCEPTED` with an `applyMessage` (or, for a row never even attempted,
simply `ACCEPTED` with none) - neither matches a literal `PROPOSED` filter. `ReviewFilter.matches`
(`review/ReviewFilter.java:33-49`) now treats an `ACCEPTED` `SCRIPT_CONSTRAINT` line as ALSO matching the
Pending filter specifically (`isUnappliedConstraintUnderPending`, `:51-63` - deliberately narrow to the
`PROPOSED` filter value only, so filtering explicitly on `ACCEPTED` still shows exactly the true `ACCEPTED`
lines, unclouded by this rule). `ReviewData.unappliedConstraintCount` (`review/ReviewData.java`, new method) 
counts every such line over the WHOLE project (never a filtered subset, the same discipline `counters`
already follows); `ReviewPage.renderUnappliedConstraintsBanner` (`review/ReviewPage.java`, called from
`render` right after the confirmation block) shows a banner - "N accepted constraint(s) are not on the model
yet" (`review.capability.unapplied.banner`/`.link`, `Messages_en/fr.properties`) - with a one-click link
setting BOTH `data-mdmf-filter-nature=SCRIPT_CONSTRAINT` and `data-mdmf-filter-status=ACCEPTED` on the same
button (the page's existing JS handler already reads both attributes independently off any clicked element,
`ProposalReviewUserService.PAGE_JS` lines ~804/808 - no JS change needed).

**Tests**: `ConstraintBodyBuilderTest` (rewritten `chooseScope*`/`buildFallbackMessage*`/`templateBody*`
tests for always-NODE, plus new `dependencyAttributes*`/`buildSetupBlock*`/`replaceSetupBlock*` coverage and
the item-5 prefix-match fixture), `ConstraintBodySubmissionTest` (re-cast TABLE fixtures as NODE-with-two-
attributes; new setup-overwrite/message-required/message-truncation coverage), `ConstraintBodyPromptTest`
(rewritten for the single-argument `systemPrompt`, new always-NODE/null-guard-anchor/message-writing
assertions, the old TABLE-scope test removed), `ReviewDataTest`/`ReviewPageTest` (new Pending-filter-includes-
unapplied-constraint and banner-presence/absence coverage). `bash tools/test_local.sh` green, full suite.

**Not verified**: see section 8's own new bullet for this fix.

---

## 4c. `fix/setup-alias`, 2026-09-21: the overwrite must not assume the model declared `uses core.validation as v;`

Blocking gap found at review, one hour after `fix/node-scope-multi-attribute` (section 4b) merged: a real body
gpt-4o wrote and compiled on the instance had exactly these headers - `uses core.message as msg;`, `uses
core.json as json;`, `import Fasttrack.lib.iban as iban;` - **no `core.validation` line at all**, because its
own (model-written, about-to-be-discarded) `setup()` was empty and never needed one. `buildSetupBlock`
(section 4b) hardcoded the alias `v` into every overwritten `setup()`; against exactly this source the
overwrite would fail to compile on a line the model never wrote, and the compiler's own error (an undeclared
unit `v`) would make no sense to it fed back into the retry loop.

**Fixed deterministically, in the same place as the overwrite, pure and tested**: new
`capability.ConstraintBodyBuilder.resolveValidationAlias(String)` (`ConstraintBodyBuilder.java:946`) - called
from `ConstraintBodySubmission.submit` (`ConstraintBodySubmission.java:150-156`, right before
`buildSetupBlock`/`replaceSetupBlock`) - reads the MODEL's own submitted (already-normalised) source and
returns a `ValidationAliasResolution` (`alias` + possibly-adjusted `source`), exactly the three cases the
coordinator specified:

1. `uses core.validation as <alias>;` already present - reuse THAT alias, source unchanged.
2. A BARE `uses core.validation;` (no `as <alias>`) present - REPLACE that one line in place with an aliased
   version - never insert a second line alongside it, which would be a duplicate declaration.
3. No `core.validation` line at all - INSERT `uses core.validation as <alias>;`
   (`insertUsesCoreValidationLine`, `:1009`) right after the last existing `uses ...;` line, before any
   `import` - falling back to right before the first `import` when there is no `uses` line either, and to
   prepending when there is neither (defensive only).

The alias for cases 2-3 is picked by `pickUnusedAlias` (`:983`): the first of `v`, `validation`,
`validation2`, `validation3`, ... not already claimed by another `uses`/`import` alias anywhere in the source
(`usedAliases`, `:966`, reusing the exact same `IMPORT_LINE`/`USES_LINE` patterns `checkSubmittedSource`
itself reads) - so a model that happened to alias an IMPORT `v` (or a different `uses` unit `v`) never
collides with the alias this method picks. `buildSetupBlock` (`:253`) now takes the alias as an explicit
third parameter, called through everywhere instead of the literal `"v."`; the deterministic TEMPLATE path
(`buildTemplateBody`, call site at `:510`) is **unchanged in behaviour** - it always emits `uses core.validation
as v;` itself unconditionally, so it always passes the literal `"v"`, never `resolveValidationAlias` (that
method exists only for the LLM path, whose submitted source is not under this codebase's control).

**Tests**: `ConstraintBodyBuilderTest` - `resolveValidationAliasReusesAnExistingAliasedLine` (case 1),
`resolveValidationAliasInsertsALineForTheRealCapturedHeaderSetWithNoValidationImport` (case 3, the coordinator's
own real header set - inserted line, both `addDependencyToModify` calls through the inserted alias, model's own
body untouched), `resolveValidationAliasReplacesABareUsesLineWithNoAliasInPlaceNeverDuplicating` (case 2),
`resolveValidationAliasPicksAnotherAliasWhenVIsAlreadyAnImportAlias` (`v` taken -> `validation`),
`resolveValidationAliasPicksValidation2WhenBothVAndValidationAreTaken` (both taken -> `validation2`),
`buildSetupBlockUsesTheGivenAliasNotHardcodedV`. `ConstraintBodySubmissionTest` -
`submitInsertsAValidationAliasLineWhenTheModelDeclaredNone`/`submitReusesAnExistingValidationAliasEndToEnd`
(both end to end through `submit`); `GOOD_SOURCE` and the compile-failure-line-number fixture were both given
their own `uses core.validation as v;` line so the many OTHER tests asserting `source()` equals a fixture
verbatim (or a hardcoded compiler line number) keep holding - the alias-insertion behaviour itself is covered
by its own dedicated fixtures/tests, never conflated with those. `bash tools/test_local.sh` green, full suite.

**Not verified**: no live re-run of the real suggestion-6 body (or of a body genuinely missing `core.validation`)
against the live instance this round - the insertion/replacement/reuse logic is proven only against synthetic
fixtures built to match the coordinator's own captured header set, never independently re-submitted to a live
`update_script` call.

---

## 5. Failure handling and rollback

**Round-2 fix (section 0, item 1): `catch (Exception ex)`, not `catch (OperationException | McpException ex)`.**
The original two-catch-clause code let a bare `RuntimeException` (an NPE, an unchecked error from a library
call) escape both, so it never triggered rollback, never ended the `PipelineStep`, and left the row `ACCEPTED`
while the constraint was silently live on the model. Every phase-A `catch` in `applyModelGroup` now catches
`Exception`.

Any exception in phase A AFTER the declaration (`p.declared = true`) triggers `CapabilityApplier.rollback`:

1. `unset_element_property({data_model_name, element_path, property: javaConstraints|tableConstraints, item:
   <scriptName>})` - `item` is passed on every call (CLAUDE.md §5, fact 8: **without it the whole list is
   wiped**, Java constraints included).
2. Republish `data_model_name` - **only when a publish had already genuinely succeeded THIS ROUND** (the
   group's own publish, section 2a). A failure before that point (declare/author/validate, or the group's own
   publish itself failing) never made this constraint's declaration part of a published model, so there is
   nothing to take back out of it - republishing then would be a no-op at best, and an unnecessary live call
   at worst. A verify (`authored:true`) failure AFTER the group's own publish DID succeed is the one case
   where a republish is genuinely needed, scoped to that one constraint alone (section 2a, point 4).

Rollback itself is best-effort (wrapped in its own `try`/`catch`, failures recorded in the step's own
`detail.rollbackError`, never allowed to hide the ORIGINAL failure from the caller). The suggestion row's own
`status` reverts to (or stays) `ACCEPTED`, `applyMessage` carries the real reason, truncated to 2000
characters (the field's own `maxLength`).

**Phase B never rolls back and never fails the apply** (round-2 fix, section 0 item 1): by the time phase B
runs, the constraint is correctly declared, authored and published - an explanation or measurement shortfall
is recorded in `applyMessage` on an otherwise `APPLIED` row (`review.capability.applyWarning`, "Note: ..."),
never `review.capability.applyFailed` ("Apply failed: ...") - the review screen distinguishes the two
(section 1).

---

## 6. Measured effect - the Java validation-report API, and what is and is not verified

Per the task brief's own instruction: "no MCP tool reads a validation report: read it in Java ... find the
API by javap/local Javadoc in ebx-lib.jar". **No javadoc for `ebx-lib.jar` is shipped on this machine** (only
an older, unrelated add-on javadoc zip was found under a 6.0.13 FastTrack install) - every signature below
comes from `javap -p` against the real `ebx-lib.jar` of the `ebx-ps-fasttrack-server` project, 2026-09-21.

- `com.onwbp.adaptation.AdaptationTable` has its own `getValidationReport()` (no-argument), scoped to exactly
  one table - used in preference to `Adaptation.getValidationReport(boolean, boolean)`/
  `getValidationReportForNode(SchemaNode, boolean, boolean)` (both exist, both take undocumented booleans):
  the no-argument overload needs no guess at what those flags mean.
- `com.orchestranetworks.service.ValidationReport.getItemsOfSeverity(Severity)` returns a streaming
  `ValidationReportItemIterator` (`AutoCloseable`, never a materialised list - bounded memory even on a huge
  table, see below for the time bound).
- `ValidationReportItem.getSubjectForAdaptation()` (interface `ValidationReportItemSubjectForAdaptation`)
  gives `getAdaptation()` (the failing record) and `getPathInAdaptation()` (a `Path`, RELATIVE to that
  record - the same relative-path convention `FactoryPaths`' own `./code`-shaped constants use everywhere
  else in this codebase).
- `com.onwbp.base.text.Severity.WARNING`/`.ERROR` are public static fields; `Path.SELF` (verified live,
  formats to `"."`, same as `Path.parse(".")`) is the record-root path a TABLE constraint's own
  `checkRecord`-raised message reports against.

**Attribution - how a report item is matched to THIS constraint, and its limits (round-2 fix, section 0 item
4).** `set_script_constraint` declares no name/id anywhere the `ValidationReportItem`/`SchemaFacet` interfaces
expose (per `javap`, `SchemaFacet` has only XSD-facet flags - `isFacetPattern`, `isFacetMaxLength`, etc. -
nothing that reads as "this is a script/Java constraint named X"). Path (`./<attribute>` for NODE, `Path.SELF`
for TABLE) and severity are ALWAYS matched first - but path+severity alone would count any OTHER
warning/error on the same field/record, so `ConstraintBodyBuilder.extractReportedMessage(scriptBody,
severity)` is also run on the constraint's own stored source, looking for the string literal argument of its
`msg.addWarning`/`addError` call:

- a **whole literal** (`msg.addWarning('IBAN failed validation.');` - true of EVERY template body, and of an
  LLM body that happens to write one) ⇒ `MessageAttribution.exact = true` - the item's own rendered message
  (`item.getMessage().formatMessage(locale)`, `UserMessage.formatMessage(Locale)` found by `javap`) must
  `.equals()` it;
- a **concatenation's leading segment** (`msg.addWarning('IBAN failed: ' | record.iban);`) ⇒ `exact = false` -
  the rendered message must `.startsWith()` it;
- **nothing extractable** (the argument is a variable or a function call, e.g. a built message string) ⇒
  attribution falls back to path+severity alone, `MeasuredEffect.approximate = true`, surfaced in
  `applyMessage` ("attribution approximate: recordsReported may include unrelated warnings on the same
  field/record") - never a hard failure, and never silently hidden.

**Records are counted DISTINCT (by primary key, a `LinkedHashSet`), never by validation-report ITEM** (round-2
fix) - a hypothetical second matching item on the same record no longer inflates the count.

Still UNVERIFIED against a live report: whether EBX genuinely reports a TABLE constraint's `checkRecord`-
raised message with a root/self path (as opposed to, say, `getSubjectForTable()` instead, or no path at
all), and whether `UserMessage.formatMessage(Locale)`'s own rendering (locale substitution, any surrounding
decoration EBX might add) matches the literal text byte-for-byte the way this attribution assumes - neither
was observed live (no EBX instance in this lot).

**Large tables (round-2 fix, section 0 item 3, see also section 6a)**: `AdaptationTable.getValidationReport()`
walks the whole table's validation state - `measureEffect` is now called with a size bound
(`DEFAULT_MEASURE_MAX_RECORDS = 200000`) for every AUTOMATIC (phase B) call; a table over that bound is NOT
scanned (`MeasuredEffect.skipped = true`, every other field meaningless) and phase B notes it in
`applyMessage` with the `/measure` route to use instead. Even within the bound, the iterator is streamed
(`AutoCloseable`, never a materialised list) and only up to 5 value SHAPES are kept (`offendingShapes` -
`quality.Shapes.of`, never a real value); `recordsChecked` comes from `RequestResult.getSize()` (a plain COUNT
query, documented as such in `measureEffect`'s own Javadoc); if it throws, the sentinel `-1` is used (never
blocks the report itself). Neither call was measured for cost on a real large table - still the open question,
now at least bounded rather than unconditional.

---

## 6a. Bounded automatic measurement, and `/measure` for on demand (round-2 fix, section 0 item 3)

`CapabilityApplier.measureEffect`'s new `pForced` parameter: `false` (phase B, automatic) skips scanning
above `pMaxRecords` records (the route's own `measureMaxRecords` query parameter, default
`DEFAULT_MEASURE_MAX_RECORDS` = 200 000) - `applyPhaseB` then stores NOTHING for the measurement fields (all
null, exactly as if never measured - never a misleading partial count) and notes the skip, with the
suggestion's own id, in `applyMessage`. `true` (`POST /projects/{code}/capabilities/{id}/measure`) never
skips, whatever the table's size - `CapabilityApplier.measure` rebuilds the scope (`NODE`/`TABLE`, the
attribute name parsed back out of the row's own `appliedElementPath`) and the message attribution (from the
row's own stored `scriptBody` - works identically for a template or an LLM body, since both are read the same
way) directly from the `APPLIED` row, with NO re-declaration/re-publish - a pure read against the live,
already-published model, followed by ONE write of only the four measurement fields
(`recordsChecked`/`recordsReported`/`offendingShapes`/`measuredAt` - `writeMeasured`, never touching
`status`/`appliedAt`/`scriptBody`/`explanation`). Not traced as a `PipelineStep` (a lightweight, repeatable
read+write, unlike apply/remove's own model-changing sequence) - a deliberate scope decision, not an
oversight; a later lot could add one if the omission proves to matter.

The review screen offers this as "Measure again" on every `APPLIED SCRIPT_CONSTRAINT` line
(`review.DecisionActions.Actions.measureAgain`, always alongside Remove) - both the way to see the real effect
of a constraint whose automatic measurement was skipped for being too large, and the way to refresh a stale
count after the underlying data has changed.

---

## 6b. `fix/measure-observability`, 2026-09-21: per-record measurement, and making the gap observable

Migration (section 4b/4c) worked for real on the live instance - `constraintScope: NODE`, `migratedFromTable:
true`, the table constraint gone, a null guard on the anchor only, two `addDependencyToModify` calls read back
on the model. But the very next `measuredEffect` was still `{"recordsChecked":55,"recordsReported":0,
"approximate":false}` while the ground truth was ONE offending record (supplier V100110, a MOD-97 remainder
of 8, computed offline) - the product owner saw the warning on that record's own form with the EARLIER
version. `validation.log` showed a genuinely fresh validation pass ("Validation done in 916 ms ... table
/root/Supplier") at the exact second of the measurement. Two rounds of guessing (section 6's own "still
unverified" bullets) had not found the cause - this fix makes it OBSERVABLE instead.

**javap findings (`ebx-lib.jar`, the same jar as section 6, re-checked 2026-09-21) - three questions asked,
answered as follows:**

1. **Does `AdaptationTable.getValidationReport()` include record-level items, or only table-level ones - and
   what do the two undocumented boolean overloads look like?** `AdaptationTable` (interface) exposes FOUR
   overloads: `getValidationReport()`, `getValidationReport(ValidationSpec)`, `getValidationReport(boolean)`,
   `getValidationReport(boolean, boolean)`. **`Adaptation`** (the interface a single RECORD occurrence also
   implements) exposes the IDENTICAL four-overload family, PLUS `getValidationReportForNode(SchemaNode, ...)`
   (also four shapes: no-arg, `ValidationSpec`, one boolean, two booleans) - so a genuine PER-RECORD validation
   entry point exists, confirmed by signature. **What javap could NOT answer**: both `AdaptationTable` and
   `Adaptation` are pure interfaces (no method bodies to disassemble), and no CONCRETE implementing class was
   found anywhere in `ebx-lib.jar` - every plausible candidate under `com/onwbp/adaptation/` (`AtoCfInstance`,
   `CfContent`, `RepositoryBridge`, `SessionImpl`, and the whole `Ato*`/`Cf*` family) was checked with `javap
   -p` and none declares `getValidationReport` itself; the real implementation is internal/server-side and not
   shipped in this client-facing jar. **So whether the table-level report genuinely omits record-level items
   could NOT be proven from static analysis alone** - this question is answered by the FIX below, not by
   javap.
2. **How is a field-level item's path formatted?** `ValidationReportItemSubjectForAdaptation.getPathInAdaptation()`
   returns a `com.orchestranetworks.schema.Path`, RELATIVE to `getAdaptation()` (the item's own subject
   record) - unchanged from section 6's own finding, and it matches `measureEffect`'s own `expectedPath =
   Path.parse("./" + attribute).format()` exactly (both `./iban`-shaped). No evidence the path format itself
   was ever the mismatch.
3. **`getItemsOfSeverity(Severity)` vs an "or more" variant, and which is called?** Both exist on
   `ValidationReport`: `getItemsOfSeverity(Severity)` (exact) and `getItemsOfSeverityOrMore(Severity)`. The
   previous code called the EXACT-severity one; the new diagnostics (below) call `getItemsOfSeverityOrMore
   (Severity.INFO)` instead - `INFO` is the LEAST severe of the four (`FATAL > ERROR > WARNING > INFO`,
   `Severity.java`'s own four constants), so "or more" from `INFO` means "every severity, unfiltered" - this
   is how `itemsSeen`/`itemsBySeverity` (item 1 below) see everything regardless of severity, while the actual
   match/count logic still gates on the exact expected severity (`MeasurementMatch.onExpectedPath`, string-label
   compared, item 3 below).

Two more signatures found along the way, useful for `subjectKind` (item 1): `ValidationReportItem` also has
`getSubjectForTable()` (a DISTINCT interface, `ValidationReportItemSubjectForTable`: `getAdaptationTable()`,
`getXPathPredicateOfRecords()`, `getRecords()` - a table-shaped subject, separate from the per-record one);
and `Adaptation.isTableOccurrence()` tells a RECORD occurrence apart from a dataset root, for an item whose
subject IS a `ValidationReportItemSubjectForAdaptation`.

**Item 3's decision**: `javap` could not conclusively prove or disprove that the table-level report omits
record items (no implementation bytecode to inspect) - but the live symptom (product owner sees the warning on
the individual record's own form; the Java measurement against the WHOLE table does not) plus the confirmed
existence of a genuine per-record `Adaptation.getValidationReport()` API were enough circumstantial evidence to
switch measurement strategy rather than keep guessing at the table-level path. `ebx.CapabilityApplier
.measureEffect` (`:1799` onward) now iterates the table's OWN records via a streamed `RequestResult` (bounded
by `pMaxRecords`, same bound as before, closed in `finally`) and reads each record's OWN `getValidationReport()`
- never the table-level one. **Cost, explicitly documented in the method's own Javadoc**: this is now O(records)
individual validation passes (one `getValidationReport()` call per record touched) instead of the previous
O(1) table-wide pass - `DEFAULT_MEASURE_MAX_RECORDS` (200 000, section 6a) was chosen for the OLD cost model
and has NOT been re-measured against this one (see this section's own "not verified" note).

**Item 1 - observability, unconditional, regardless of which hypothesis turns out to be right.** `MeasuredEffect`
(`ebx/CapabilityApplier.java`) carries a new `diagnostics` field (`MeasureDiagnostics`), never persisted onto
the `CapabilitySuggestion` row and never rendered on the review screen (nothing here reaches `ReviewLine`/
`ProposalWording`, which only ever read the four STORED measurement fields) - stored in the apply step's own
detail (`p.detail.set("measureDiagnostics", ...)`, `applyPhaseB`) and returned as a top-level `diagnostics`
field by the `/measure` route (`CapabilityApplier.measure`):

- `itemsSeen` - every item this measurement walked, of every severity (`getItemsOfSeverityOrMore(Severity.INFO)`,
  per finding 3 above).
- `itemsBySeverity` - `itemsSeen`, broken down by `Severity.toParsableString()` (`"FATAL"`/`"ERROR"`/
  `"WARNING"`/`"INFO"`).
- `itemsOnExpectedPath` - of `itemsSeen`, how many were of the constraint's own severity AND on its own
  `expectedPath` (`MeasurementMatch.onExpectedPath`, before any message check).
- `itemsMatchingMessage` - of `itemsOnExpectedPath`, how many ALSO matched the constraint's own message
  (`MeasurementMatch.messageMatches`) - the fully-attributed count, what `recordsReported` is actually built
  from (distinct by record primary key, unchanged rule from section 6).
- `unmatchedSample` - up to 8 items NOT attributed to the constraint, each `{severity, subjectPath (formatted
  exactly as EBX gives it - the table's own path for a table subject, the record-relative path otherwise),
  subjectKind ("record"/"dataset"/"table"/"unknown", `ebx.CapabilityApplier.describeSubject`), messageStart
  (first 100 characters of the item's own rendered message, in the SAME locale used for matching),
  expectedPath, expectedMessagePrefix}` - **no real field VALUE ever appears here** (this constraint's own
  messages never carry one; `messageStart` is truncated regardless, a blanket precaution, never conditional on
  content).

**Pure parts extracted and tested** (`capability.MeasurementMatch`, new file, no EBX class - the same
`capability.*`/`ebx.*` split every other class in this package follows): `onExpectedPath(itemSeverityLabel,
expectedSeverityLabel, subjectPath, expectedPath)` (string-label severity comparison, replacing the previous
`item.isOfSeverity(Severity)` EBX-object call - the label is what `itemsBySeverity` already stores anyway, so
this both makes the gate testable AND keeps severity representation consistent everywhere in the diagnostics),
`messageMatches(MessageAttribution, renderedMessage)` (the exact/prefix rule, unchanged in behaviour from
section 6, just extracted), `shouldSample(currentSize, cap)`, `truncate(text, maxLength)`. `describeSubject`
itself stays in `ebx.CapabilityApplier` (it touches `ValidationReportItem`/`Adaptation`, real EBX types) but is
now a small pure-shaped helper (`ValidationReportItem` in, `{kind, path}` out) rather than inlined in the loop.

**Tests**: `MeasurementMatchTest` (new) - both gates (severity+path match/mismatch, exact/prefix message
match including the real captured concatenation-with-status-suffix shape, null-attribution/null-rendered
defensive cases), the sample cap boundary, and truncation (short/long/exactly-at-boundary/null). `bash
tools/test_local.sh` green, full suite - `ebx.CapabilityApplier` itself has no direct test (EBX-bound, like
every other generator in this package, section 8's own established caveat).

**Not verified**: no live re-run against the real suggestion-6/V100110 case was possible this round (no live
MCP access) - whether the per-record strategy actually reports the ONE offending record now, what the
`diagnostics.unmatchedSample` of the OLD table-level path would have shown (the fix was made before a live
before/after comparison could be captured), and the real per-record cost on a table of meaningful size are all
unconfirmed. `DEFAULT_MEASURE_MAX_RECORDS` (200 000) has not been reconsidered for the new O(records) cost
model - a future session with live access should measure actual latency per record and revisit the bound if
it proves too high.

---

## 7. Removal - idempotent (round-2 fix)

`ebx.CapabilityApplier.remove`: reads the row's own `appliedModel`/`appliedElementPath`/`scriptName`/
`constraintScope` (never recomputed - the row is the source of truth for what was actually declared), two
SEPARATE steps, each with its own `catch(Exception)` and its own distinct message (round-2 fix - the
coordinator's own instruction to say exactly which of the two states the model is left in):

1. `unset_element_property` with `item` set (same mandatory-`item` rule as rollback). **Idempotent**: a
   result that `looksAlreadyRemoved` (a text heuristic on the tool's own error - `contains("not found")`,
   `"does not exist"`, `"no such"`, `"unknown item"`, case-insensitive - UNVERIFIED, the coordinator's own
   framing: "an item matching nothing THROWS on the server", never captured live in this lot) is treated as
   success (`detail.alreadyRemoved = true`), not a failure - a retried Remove after a previous call's own
   removal-step succeeded but republish-step failed must not itself fail here. Any OTHER failure here means
   NOTHING changed: the row stays `APPLIED` (`writeApplyMessageOnly` never touches status), `applyMessage`
   says "could not remove the declaration from the model draft ... nothing changed, the constraint is still
   live", and this call throws.
2. Republish `data_model_name` unconditionally (the constraint WAS live in the draft one way or the other, so
   taking it out always needs a real republish to matter). **If step 1 succeeded but this fails**: the row
   STAYS `APPLIED` (not reverted - `writeApplyMessageOnly` again) with `applyMessage` stating precisely that
   state - "constraint removed from the model draft, but the publication could not be updated ... the
   published model still carries it: retry Remove" - so a retry is both safe (step 1 is idempotent) and
   necessary (the published model is the one that actually matters).

Only once BOTH steps succeed does the row revert to `ACCEPTED` with every applied-* / measured-effect field
cleared (`explanation`, `scriptBody`, `recordsChecked`, `recordsReported`, `offendingShapes`, `measuredAt`,
`appliedAt`, `applyMessage` all set to null in the same write, `writeRemoved`).

---

## 7a. The request is synchronous - what the UI shows while it runs (round-2, "confirm file:line")

Accepting/removing/re-measuring a `SCRIPT_CONSTRAINT` line, and confirming a bulk accept that includes one,
each call into `ebx.CapabilityApplier` from the SAME synchronous HTTP request the browser's form post makes -
there is no AJAX progress channel in this plain-HTML-posted-back screen (CLAUDE.md §9, "posting a user
service's own inputs": one real submit button, everything else a `type="button"`). Two things happen,
confirmed by file:line:

- **Immediately on click**, before the real postback fires:
  `ebx.ui.ProposalReviewUserService.PAGE_JS`'s `applyingCue` function (defined in the `PAGE_JS` string,
  `ebx.ui.ProposalReviewUserService.java:494`, wired into the click handler at `:512-513`) inserts a bold
  "Applying… this may publish the model and take a minute or more. Please wait." notice at the top of the
  page and disables every `.mdmf-btn` - for `ACCEPT` with `nature==='SCRIPT_CONSTRAINT'`, `REMOVE`, `MEASURE`,
  and `BULK_ACCEPT_CONFIRM` (the one bulk action that may include `SCRIPT_CONSTRAINT` candidates).
- **Once the postback completes** (the page re-renders, possibly a minute or more later): a specific result
  notice - `decideOne` (`:237-279`) sets `review.notice.applied`/`review.notice.applyFailed`; `removeOne`
  (`:282-295`) sets `review.notice.removed`/`review.notice.removeFailed`; `measureOne` (`:298-311`) sets
  `review.notice.measured`/`review.notice.measureFailed`; `confirmBulk` (existing, unchanged) sets
  `review.bulk.done`/`review.cascade.done`. A bulk-accept PREVIEW (before confirmation) already states the
  model-grouping/one-publish-per-model fact (`previewBulkAccept`, `:313-331`; `capabilityBulkSummary`,
  `:339-359`) so a reviewer knows what they are about to trigger before they click Confirm.

Both together are what the coordinator's own review asked to confirm: a visible "in progress" state (the
client-side cue - the server itself has no mid-request progress to report), then a clear result. Line numbers
are as of this lot's own last commit; re-check them if the file moves.

---

## 8. Not verified

- **Nothing in this lot ran against a live EBX instance** - every MCP call shape is the task brief's own
  measured facts (CLAUDE.md §5's last bullet), reused verbatim; every Java validation-report signature is
  `javap` output, never exercised against a real `ValidationReport`.
- **The attribution heuristic** (section 6) - `formatMessage(Locale)`'s own exact rendering, and whether a
  TABLE constraint's `checkRecord`-raised message truly carries a root/self path, remain unverified; a
  message-literal match narrows but does not eliminate the residual risk of two constraints on the same
  field/severity being conflated when NEITHER has an extractable literal (both then fall back to
  path+severity alone).
- **`looksAlreadyRemoved`'s text heuristic** (section 7) - "an item matching nothing THROWS on the server" is
  the coordinator's own framing, never captured live; the exact error text/shape of that throw is unknown, so
  the heuristic (`"not found"`/`"does not exist"`/`"no such"`/`"unknown item"`, case-insensitive) is a
  best-effort guess at recognisable phrasing, not a documented contract.
- **Whether a real language model, given the structural-check feedback loop, actually converges within 3
  attempts on a genuinely non-boolean function** - `ConstraintBodySubmissionTest` proves the retry/budget
  mechanism itself is correct against synthetic good/bad sources; whether a real provider's own output
  reliably passes `checkSubmittedSource` was never observed (no live LLM call, per this lot's own
  constraints).
- **The two boolean-argument `getValidationReport(boolean, boolean)`/`getValidationReportForNode(SchemaNode,
  boolean, boolean)` overloads' real semantics** - deliberately avoided rather than guessed; the no-argument
  overload was used instead. A later lot with access to real Javadoc (or a live instance to probe against)
  should confirm whether either overload's flags matter for this use case (e.g. forcing a fresh
  re-validation vs. reading a cached report).
- **Cost/latency of `AdaptationTable.getValidationReport()` and `RequestResult.getSize()` on a genuinely
  large table** - see section 6's own "large tables" note; the 200 000-record default bound is a reasonable
  guess, not a measurement, and `RequestResult.getSize()` itself (used both as the bound check and as
  `recordsChecked`) was never timed on a genuinely large table either.
- **Whether `set_script_constraint`'s `publishSafe` field is reported by every EBX 6.3 build**, or only the
  one the task brief's own coordinator measured against - `CapabilityApplier` refuses outright when it reads
  `false`, but treats a MISSING field as `true` (optimistic default, matching how every other tool-response
  boolean in this codebase's MCP callers is read: `asBoolean(true)`/`asBoolean(false)` per field, never a
  hard requirement that the key be present).
- **The one-publish-per-model batching path itself** (section 2a) - no direct unit test exercises
  `applyModelGroup`'s grouping/rollback/republish-on-verify-failure logic (it is EBX-bound, like every other
  generator in this package - `StagingGenerator`/`MasterGenerator` have no direct tests either, only their
  pure helpers do); correctness here rests on code review and the pure building blocks
  (`ConstraintBodyBuilder`, `ConstraintBodySubmission`/`ConstraintExplanationSubmission`) each being tested
  independently, never a live multi-constraint batch.
- **`fix/capability-apply-ux` items 1-2 (section 0a), specifically:**
  - the Apply button and its wait-cue are only asserted through `ReviewPageTest`'s rendered HTML string and
    `DecisionActionsTest`'s boolean flags - nobody has looked at the actual page (button placement/label
    alongside Reject, the wait cue's text while an Apply postback is in flight);
  - `Session.suggestScriptConstraint`'s new `message` refusal/`sanitizeUserMessage` bound (160 characters,
    single line) was never exercised against a real language-model provider's own phrasing habits - whether a
    model naturally writes a message that needs truncating, or one that is empty/unusable often enough to
    matter, is unknown;
  - `ConstraintBodyBuilder.buildFallbackMessage`'s output (`"<Label>: not accepted — <doc sentence>."`) was
    read by nobody but this lot's own tests - whether it reads naturally for a real function's real
    documentation (as opposed to the short synthetic doc strings the tests use) is unconfirmed;
  - the idempotent-re-apply-with-a-changed-message claim (section 0a) is proven only at the level of
    `buildTemplateBody`/`declareAndAuthor`'s pure building blocks producing a different literal for a
    different input message - never against an actual second `apply` call on a previously-published live
    constraint.
- **`fix/body-compile-loop` (section 4a), specifically:**
  - the whole compile-retry loop (`ConstraintBodySubmission` driving the injected `ConstraintCompiler`) is
    proven only against fake compilers (`ConstraintBodySubmissionTest`) - never a live `get_script`/
    `update_script` round trip, so `McpConstraintCompiler`'s own JSON-path reading
    (`data.validation.isSuccess`/`errors[].startLine`/`startColumn`/`message`) is unverified against the real
    shape a live server answers with (the shape used here is the coordinator's own captured example,
    `{"startLine":15,"startColumn":16,"message":"Unexpected token '='"}`, assumed representative but never
    independently confirmed for every error kind - e.g. an error with no position at all was never seen live,
    only handled defensively);
  - `get_syntax_guide` was never called against a live scripting server - the "plain markdown text, not a
    JSON object, sometimes a `data` string field" reading in `fetchSyntaxGuide` follows the coordinator's own
    description verbatim, but the real document's actual size, heading structure/levels, and whether
    `SyntaxGuideBudget`'s heading-based section split even matches how the guide is actually organised are
    all unknown - `DEFAULT_CHAR_BUDGET` (20 000 characters) is a documented guess, not a measurement (see the
    class's own Javadoc);
  - whether `uses core.message;` (no alias) is genuinely rejected by the compiler was not learned this round
    either - the second real failure's own errors were all about `=`/`isNotNull`, never the alias question;
    still deliberately left to the compiler, never hard-coded as a structural check;
  - the prompt's own two example bodies and hard-facts list were checked only via `ConstraintBodyPromptTest`
    asserting their TEXT is present in the assembled string - nobody has verified a real language model reads
    and correctly applies them, only that the human-authored content itself is there and well-formed.
- **`fix/node-scope-multi-attribute` (section 4b), specifically:**
  - the migration path (item 2 - remove the old TABLE declaration, declare the new NODE one, one publish) is
    proven only at the level of `declareAndAuthor`'s own logic and `PreparedConstraint.tableMigration`'s
    construction in `prepare` - never against the real ACCEPTED/TABLE row on the live instance (row 6,
    `Supplier_validate`) this fix was built to migrate; whether `unset_element_property` on a TABLE constraint
    that is genuinely still live behaves as `looksAlreadyRemoved` assumes remains a text heuristic, unverified
    live, same caveat section 7 already carries for the ordinary rollback path;
  - `addDependencyToModify`'s own runtime behaviour - whether a constraint declared this way genuinely
    re-validates on a change to a NON-anchor bound attribute, on this instance's actual EBX 6.3 build - rests
    on the coordinator's own live scratch-model proof (declared, compiled, published, then deleted) reported
    secondhand in this session, not independently re-verified by this lot;
  - the expected post-migration measurement (`recordsChecked 55, recordsReported 1`, IBAN shape `AA99…`) is
    stated as the coordinator's own expectation, not re-measured against the live instance this round - no
    live MCP access was available;
  - the model-writes-its-own-message path (item 4) was never exercised against a real language-model
    provider's own phrasing habits, the same caveat `fix/capability-apply-ux`'s equivalent bullet above
    already carries for the given-message case;
  - the review-screen banner (item 6) is asserted only through `ReviewPageTest`'s rendered HTML string -
    nobody has looked at the actual page (banner placement/wording, whether the one-click link's combined
    nature+status filter reads clearly next to the ordinary per-status filter buttons already on the page).
- **`fix/setup-alias` (section 4c)**: `resolveValidationAlias`'s three-way branch and `pickUnusedAlias`'s
  collision avoidance are proven only against synthetic fixtures built to match the coordinator's own
  captured header set and hand-constructed collision cases - no live `update_script` round trip against a
  body genuinely missing `core.validation` was made this round (no live MCP access), so whether the inserted
  line's exact placement (immediately after the last `uses` line) is what a real EBX compiler expects,
  syntactically, remains assumed from the language facts already documented elsewhere in this file, not
  independently re-confirmed.

---

## 9. `feature/capability-reapply`, 2026-09-21: closing (c) and (d), preparing (b)

Branch worked in an isolated worktree, never run against a live instance (same constraint as every prior lot
in this file). Closes PLAN.md's open items (c) "Re-apply on a stale APPLIED line" and (d) "the two phone
constraints still carry the old technical message"; prepares (b) with documentation only, no code.

### 9a. (c) - a pure staleness decision, and Re-apply reusing the existing Apply path verbatim

`capability.ConstraintStaleness` (new, pure, no EBX/MCP type): given an `AppliedFacts` value built entirely
from a `CapabilitySuggestion` row's own STORED fields (`constraintScope`, `appliedElementPath`, `scriptBody`,
`binding`, plus `targetImportName`/`targetLibrary`/`targetFunction`/`severity` resolved the same way
`CapabilityApplier.declareAndAuthor` already does), says whether the row is `CURRENT` or `STALE`, and why, in
plain words - never a full byte-for-byte re-render of what a re-apply would generate (that would need the
target function's parameter TYPES and every accepted attribute's MANDATORY flag, neither available without an
extra live MCP/entity-attribute lookup at review-render time - deliberately out of scope, see the class'
own Javadoc). Three structural checks, run in this order, each independent, all skipped once the first
already explains everything a Re-apply would fix:

1. **Scope**: a stored `constraintScope` other than `NODE` (the pre-migration TABLE shape, `fix/node-scope-
   multi-attribute`) is stale on its own - `"applied as a table constraint; the current form is a field
   constraint with explicit dependencies."` (the task brief's own example wording, produced verbatim).
2. **Dependencies**: `ConstraintBodyBuilder.chooseScope`/`dependencyAttributes` recompute, from the row's own
   CURRENT `binding`, which attributes a fresh apply would declare a dependency on - a textual search on the
   stored `scriptBody` for `addDependencyToModify('/root/<Entity>/<attribute>'` (or `setDependencyToLocalNode`
   when there is only one) per expected attribute; a missing one is `"the applied setup() does not declare a
   dependency on '<attribute>': a change to it will not re-run this check."` - this check is nature-agnostic:
   it works identically whether the body is the deterministic TEMPLATE or language-model-authored, since both
   embed the same `setup()` shape (`ConstraintBodyBuilder.buildSetupBlock`/the LLM path's own overwrite).
3. **Message**: `ConstraintBodyBuilder.extractReportedMessage` on the stored body, compared against the EXACT
   removed `deterministicMessage` shape for a NODE constraint (`"'" + attribute + "' failed " + alias + "." +
   function + "."`, recovered from git history, not guessed) - a match is `"the record-facing message is the
   old technical one: '<message>'."` A template body is not a separate case here: since it embeds its message
   the same way, the identical literal-extraction/comparison catches it too, with no need to know whether the
   body is template- or language-model-authored in the first place.

`ProposalReviews.addCapabilitySuggestionLines` computes this for every `APPLIED SCRIPT_CONSTRAINT` line
(alongside the existing explanation/measured-effect read) and carries it on a new `ReviewLine.stale`/
`staleReason` pair (14-argument constructor, every other caller unaffected - the two 10-/12-argument
constructors now delegate with `false`/`null`).

**The action itself is not new code**: re-applying an already-`APPLIED` row was already supported end to end
(`CapabilityApplier.prepare` accepts a current status of `ACCEPTED` OR `APPLIED`; section 2's own "same name
replaces in place" contract) - only the SCREEN never offered a way to trigger it again except by calling
`POST .../capabilities/{id}/apply` by hand. `review.DecisionActions` gets a third overload,
`forStatus(String, String, boolean)` - the two-argument overload (`renderSelectCell`, the multi-select
preview, the plain `decidable()` check) is UNCHANGED and untouched, on purpose: Re-apply is single-row-only,
never a batch action (the task brief's own instruction, "join the batch actions only if the existing
mechanism makes that trivial; otherwise leave it single-line" - the multi-select machinery has no notion of
staleness at all, and `Actions.decidable()` deliberately still excludes `reapply`, the same as `remove`/
`measureAgain`). Only `ReviewPage.renderRow`'s own decide-cell rendering was touched: a discreet `"Out of
date"` chip (`review.capability.stale`) right next to the status chip, its `title` tooltip carrying
`ConstraintStaleness.Result.describe()` verbatim, and a `Re-apply` button (`data-mdmf-action="REAPPLY"`)
alongside Remove/Measure again. `ebx.ui.ProposalReviewUserService` dispatches `REAPPLY` through the EXACT
SAME `applyOneDirectly` method `APPLY` already uses (one `if` clause, `"APPLY".equals(action) ||
"REAPPLY".equals(action)`) - reused, never forked, per the task brief's own instruction; `PAGE_JS`'s
`applyingCue` arms the same `"Applying the constraint..."` progress message for both.

**Tests**: `ConstraintStalenessTest` (table-scope row -> stale with the exact scope reason; a current NODE
row with a non-technical message -> current; the real captured phone message -> stale with the exact text;
an LLM-shaped body missing one of two dependencies -> stale, naming the missing field; the same body with
both dependencies declared -> current; two missing dependencies -> two reasons, both present in `describe()`;
a blank `appliedElementPath` never throws). `DecisionActionsTest` (the three-argument overload: stale offers
Re-apply alongside Remove/Measure again, current does not, every other status/nature ignores the flag
entirely, the two-/three-argument overloads agree when not stale, a stale line is still not `decidable()`).
`ReviewPageTest` (the marker and its escaped tooltip, the Re-apply button, and their absence on a current
line).

**Not verified**: nothing in this lot ran against a live instance. In particular: whether the two REAL
phone rows (`Contact_isValid_phone`/`Supplier_isValid_phone`) are correctly detected as stale by this exact
logic once (d)'s new fallback/LLM message path is deployed and their rows' actual stored `scriptBody`/
`severity`/`targetImportName` are read for real - the detection was proven only against a synthetic fixture
built to match the git-history-recovered technical-message shape, never against the two real rows themselves.

### 9b. (d) - the template path now tries a language model too, before the deterministic fallback

`ebx.CapabilityApplier.declareAndAuthor`'s boolean-TEMPLATE branch (a `boolean`-returning target function -
exactly the two phone rows' own shape, `Fasttrack.lib.phone.isValid`) used to go straight from "no stored
`userMessage`" to `ConstraintBodyBuilder.buildFallbackMessage` with no attempt at a language model at all -
the ONLY path in this codebase that never gave the model a chance to write the record-facing sentence, even
though the non-boolean LLM-authored path already required exactly that (`fix/node-scope-multi-attribute`
item 4). Message source order is now, for EVERY constraint regardless of return type:

1. the row's own stored `userMessage`, if any (sanitised again defensively, unchanged);
2. **new**: one one-shot `AgentRuntime.run` call (`CapabilityApplier.tryLlmMessage`/`runLlmMessage`, phase
   `CAPABILITY_APPLY_MESSAGE`) with a single local tool, `submit_constraint_message`, backed by the new pure
   `capability.ConstraintMessageSubmission` (sanitised the SAME way `suggest_script_constraint`'s own
   `message` argument is - `CapabilityLlmPass.sanitizeUserMessage`, single line, bounded to 160 characters,
   refused only when empty/unusable after sanitising, never for being merely long). Given ONLY the target
   function's own name/documentation and the attribute's own label (both DATA, never instructions) - no
   access to `core.data`/`core.http`/any MCP server, the same discipline the explanation pass already
   follows. On success, the answer is used as `reportedMessage` AND backfilled onto the row's own
   `userMessage` (`writeApplied`'s existing `pLlmWrittenUserMessage` plumbing, reused verbatim - already
   built for the non-boolean path, no change needed there) so it is asked only once;
3. **best-effort, never fatal**: no language model connector configured
   (`AgentRuntime.run`'s own "no language model connector is set in the MDM Factory settings" refusal) or any
   other failure/empty answer falls back to `ConstraintBodyBuilder.buildFallbackMessage(attributeLabel,
   attribute)` - `"<Label>: value not accepted."` - the exact fallback the non-boolean path already had no
   equivalent for needing (a template body has no model in the loop otherwise). **Deliberately NOT the
   task brief's own originally-sketched three-argument `buildFallbackMessage(scope, label, functionDoc)`
   echoing the function's documentation sentence** - that exact approach was already tried, shipped, and then
   explicitly REMOVED by `fix/node-scope-multi-attribute` item 4 (section 4b) after a real captured function
   doc ("Validates an IBAN and compares its country ... Never raises: inspect 'valid' first, then 'status'.")
   showed it reads as unusable developer text; the CURRENT two-argument `buildFallbackMessage` (label + fixed
   "value not accepted.") is what this lot builds on, per "match the surrounding code" - resurrecting the
   removed three-argument form would be reverting a decision made for a real, live-observed reason.

`reportedMessageSource` in the step detail now reads `STORED`/`LLM`/`FALLBACK` for the template path
(previously always `FALLBACK` when unset) and `STORED`/`LLM_AUTHORED` for the non-boolean path, unchanged.

**A template-applied row whose stored body still emits the technical form is `STALE` under (c)** (the exact
scenario section 9a's own message check targets) - `Re-apply` on `Contact_isValid_phone`/
`Supplier_isValid_phone` re-runs `declareAndAuthor` in full, which recomputes `reportedMessage` fresh (no
caching across calls, unchanged behaviour from `fix/capability-apply-ux`) - this time trying the language
model first, per the new order above.

**Escaping**: unchanged from before this lot - `ConstraintBodyBuilder.buildTemplateBody` already escapes
WHATEVER `reportedMessage` it is given with `ConstraintBodyBuilder.escapeEbxString` (backslash-style: `'` ->
`\'`, `\` -> `\\`, never doubled quotes - CLAUDE.md &sect;2, confirmed correct for a string LITERAL, as
opposed to a message-bundle label) before embedding it as the literal argument of `msg.addWarning`/
`addError`. `ConstraintBodyBuilderTest` already had `escapeEbxStringHandlesQuoteAndBackslash`, and
`templateBodyEmitsExactlyTheCallerSuppliedMessageEscaped` proves a caller-supplied message containing both an
apostrophe and a backslash is escaped correctly in the assembled body - both pre-existing, unchanged by this
lot; no new escaping code was written since the LLM-produced message is sanitised (plain text, no code
syntax) and flows through the exact same `buildTemplateBody` call as every other message, stored or
fallback.

**Tests**: `ConstraintMessageSubmissionTest` (new, mirroring `ConstraintExplanationSubmissionTest`) - a
non-empty message is accepted verbatim; empty/blank/null are refused; sanitisation trims and collapses
internal whitespace (`"  The   phone\nnumber is not valid.  "` -> `"The phone number is not valid."`); a
message over 160 characters is TRUNCATED, never refused.

**Not verified**: no live language-model call was made (no connector reachable from this lot, per the task
brief's own constraint) - whether a real provider, given only the function's name/documentation and the
attribute's label, reliably writes a short, business-readable, non-technical sentence was never observed;
the fallback-on-failure path (`tryLlmMessage` catching every `Exception`) is exercised only by the absence of
a live connector during any hypothetical local run, never by a genuine provider error captured live. Whether
the two real phone rows' `targetImportName` is stored as `Fasttrack.lib.phone` or the bare `phone` (the
`aliasOf` convention takes the last dotted segment either way, so the technical-message reconstruction in
`ConstraintStaleness` should hold regardless, but this was not read off the live rows to confirm).

### 9c. (b) - prepared, not decided: what the local documentation says, and the trial for tomorrow

No code change. Read `<host>/ebx-manager/www/*/html/onHelp/advanced/` locally (both the Java API reference
and the EBX Script developer guide) for `addDependencyToModify`/`setDependencyToLocalNode`/
`addDependencyToInsertDeleteAndModify` and the constraint-on-node lifecycle - PLAN.md's item (f) itself
already corrected an earlier agent's wrong belief that no local Javadoc exists; it does, and it answers this
question directly.

**What the documentation says, one short sentence per source, file path included:**

- `.../ebx-manager/www/common/html/onHelp/advanced/Java_API/com/orchestranetworks/schema/
  DependenciesDefinitionContext.html` (the Java interface `core.validation`'s three dependency modes - local/
  explicit/unknown - are built on): *"In EBX® user interface mode, the current constraint is always checked
  when user input is submitted."* - stated as a blanket rule, independent of which dependency mode is
  declared.
- `.../ebx-manager/www/common/html/onHelp/advanced/dsl/core_validationUnit.html` (the EBX Script `core.
  validation` unit reference, `disableAjaxPrevalidation()`'s own entry): *"the constraint will still be
  validated when the form is submitted or when loading the record in the view."* - this is the single most
  directly relevant sentence: it states plainly that loading a record's own form ALWAYS triggers validation,
  regardless of dependency mode (`disableAjaxPrevalidation` only turns off the DYNAMIC, per-keystroke AJAX
  re-check while a field is being edited - it does not touch the on-submit or on-load checks at all).
- Same file, `addDependencyToModify(path: string)`'s own entry: *"this method must be called from the
  procedure setup of the constraint otherwise it is ignored."* - confirms `ConstraintBodyBuilder
  .buildSetupBlock`'s placement (always inside `setup()`) is the only place this call is ever honoured;
  irrelevant elsewhere in the source is silently dropped, not an error.
- `.../ebx-manager/www/common/html/onHelp/advanced/dsl/constraint_validation.html`: *"Declaring constraint
  dependencies helps to minimize the number of calls to constraints during the validation process."* -
  frames every dependency mode as a PERFORMANCE optimisation over a large/expensive validation pass, never as
  a gate on whether an individual record's own form validates when opened or saved.

**Read together, the documentation's own position is that explicit dependencies should not be the cause of
the reported symptom** ("the message no longer shows when the record is OPENED in explicit-dependency mode -
it did with `checkRecord`") - `core_validationUnit.html`'s own wording states loading the record's view
always re-validates, with or without explicit dependencies declared. If the live behaviour genuinely differs
from this, the discrepancy is either an interaction this documentation does not cover (e.g. a cached
validation-report artefact left over from BEFORE the TABLE-to-NODE migration, section 4b/9a - a stale report
entry from the old `checkRecord` constraint that a fresh per-record validation on open has not yet
overwritten), or a genuine build-specific behaviour gap between the documented contract and this 6.3.0-dev
build - neither can be told apart without a live trial.

**The exact 3-step trial for tomorrow** (on `Supplier` V100110, the one known-invalid IBAN, migrated live to
NODE scope per section 4b):

1. **Open** the V100110 record's own form (navigate to it directly - do not edit anything first). Does the
   IBAN warning show immediately?
   - If YES: matches the documentation's "loading the record in the view" claim exactly - the earlier report
     was likely about a stale cached report from before the TABLE-to-NODE migration (section 4b), already
     resolved by that migration itself; no further code change needed for (b).
   - If NO: the documented "always validated ... when loading the record in the view" does not hold for this
     build/configuration as deployed - worth a support ticket or a deeper live investigation (e.g. whether
     the per-record validation report is being read from a cache that predates the migration); out of this
     lot's scope to fix blind.
2. **Edit** the `country`/address field the constraint's own `setup()` declares an explicit
   `addDependencyToModify` on (NOT the IBAN field itself), without submitting the form yet. Does the warning
   appear or update dynamically (AJAX prevalidation), before any submit?
   - If YES: AJAX prevalidation is working end to end for an explicit dependency on a field OTHER than the
     constraint's own anchor field - the documented default behaviour, confirmed live.
   - If NO: `disableAjaxPrevalidation()` is not called anywhere in this constraint's own `setup()` (confirmed
     by reading the stored `scriptBody` - `ConstraintBodyBuilder`'s own generated `setup()` never calls it),
     so a NO here would suggest AJAX prevalidation genuinely does not extend to a dependency on a NON-anchor
     field in this build - a real, reportable gap between the documented contract and the live behaviour,
     distinct from step 1's question.
3. **Save** the form (submit, with or without a change to the IBAN itself). Does the warning appear in the
   record's own validation state and/or the dataset's validation report?
   - Expected YES under every hypothesis: `DependenciesDefinitionContext.html`'s own blanket rule ("always
     checked when user input is submitted") does not depend on dependency mode at all - a NO here would be
     the most surprising outcome of the three, and would point at something specific to this constraint
     (a real compile/attachment problem) rather than a dependency-mode question at all.

---

## 10. Lot M5, 2026-10-04: apply and remove dispatch on the row's nature, over REST as on the screen

`ebx.CapabilityDispatcher` (with the pure `capability.CapabilityNatureRoute`) is the one place that picks the applier from the suggestion's own
`nature`: `SCRIPT_CONSTRAINT` to `CapabilityApplier`, `SCRIPT_DERIVATION` to `DerivationApplier`, anything else (`EXTERNAL_SERVICE`, unknown) a
refusal naming the nature. It is used by:

- `POST /projects/{code}/capabilities/{id}/apply` (data-model AND scripting MCP preflight; `measureMaxRecords` only matters to a constraint),
- `POST /projects/{code}/capabilities/apply[?ids=a,b,c]` - constraints together through `CapabilityApplier.applyAll` (one publish per model),
  derivations one at a time, a refused row answered as a failed result; with no `ids`, every `ACCEPTED` `SCRIPT_CONSTRAINT` and every `ACCEPTED`
  `SCRIPT_DERIVATION` of the project,
- `POST /projects/{code}/capabilities/{id}/remove`,
- the Proposal review screen (single apply, remove, "Apply selected", bulk accept) and the wizard (accept, Apply, Remove, bulk accept,
  `CapabilityApplyRun` job). Before this lot the screen decided from the posted `mdmfNature`; it now reads the row. `ReviewRules.shouldApplyAfterAccept`
  includes `SCRIPT_DERIVATION`, so a bulk accept applies it like a single click.

Applying a derivation needs the staging model to exist (`staging/generate` first) and the staging attribute to be accepted; the failure leaves the
row `ACCEPTED` with `applyMessage`. Example for project Vol1m, capability 107:
`curl -u admin:... -X POST "http://localhost:8080/ebx-mdm-factory/rest/v1/projects/Vol1m/capabilities/107/apply"`.

Lot T1 (2026-10-04) changed what a derivation apply does (two table triggers feeding a stored field instead of a function field, then a backfill of the rows
already there; `docs/SCRIPT-DERIVATION.md` section 10), and added `POST /projects/{code}/capabilities/{id}/backfill` next to these routes; the routing above is unchanged.

