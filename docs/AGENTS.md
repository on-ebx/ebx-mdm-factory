# Agents: a governed agentic framework (lot A, 2026-10-30)

> **Closed by default (production review 2026-11-12).** The governed agents are off on every instance: they open only with the JVM property
> `-Debx.factory.agents.enabled=true` (`docs/OPERATIONS.md` section 3.1; `GET /status` shows `featureSwitches.agents.enabled`). Closed, no agent runs (by hand, on an
> event or on a schedule), no proposal is decided or applied, switching an agent on is refused with `engine.missing.agentsClosed` (HTTP 409); switching it off
> and reading stay possible. Who an agent acts for is signed by the server when a designer calls `POST /agents/{code}/enable`; it is never a value typed
> into the row. What is still missing before a customer opens them is listed in "What is not done" below and in `docs/RELEASE-NOTES.md`.

An **agent** is a declared, bounded use of the language model on the data of one project: it reads a list of items (suspect
pairs, validation findings), asks the model once for a decision per item, and then, depending on the autonomy its owner gave
it, applies the decision, waits for an approval, or only suggests it. Every run is recorded with the person it acted for. A run started by a person acts
for that person; an event or a schedule acts for the person who switched the agent on, whose login the server signed with the agent's settings (see
"Identity"). Every action is checked against that person's EBX permissions and the agent's allow-list before it is applied.

First cut: two example behaviours (data steward on Match and Merge suspects, quality corrections on validation findings), the
runner, the review workflow, the triggers and four REST routes. No screen of its own yet: an agent is declared by editing a row of the `AgentDefinition` table in
the MDM Factory dataset (holders of the Factory right DESIGN, with write access to the dataset in EBX), switched on and off through two routes, and its proposals
are reviewed in the workflow task and decided by a route; the proposal lines themselves are read only.

## The model

| Table | Who sees it | What it holds |
|---|---|---|
| `AgentDefinition` | read: VIEW and above; written: DESIGN (Factory right). Primary key `code`. | One agent: its code, label, purpose, behaviour (`kind`), project, entity, zone, trigger, allowed tools, autonomy, confidence threshold, owner profile, max items, extra instructions, and `enabled` / `enabledBy` / `lastRunAt`, which are **read only for everybody, administrators included** (the enable and disable routes and the runner write them). |
| `AgentRun` (existing) | administrators | One run: the existing columns (phase `AGENT:<code>`, model, tokens, cost, status) plus `agent`, `actingFor`, `via`, `inputs` (what the model was shown, after the data policy), `decision` (the parsed answer), `confidence` (the lowest of the run), `applied`, `escalated`, `refused`, `escalation` (the workflow process instance, or why there is none). |
| `AgentStep` (existing) | administrators | The model call (`AGENT_RUN`) and one `TOOL` step per action applied. |
| `AgentProposal` | read: VIEW and above, but the values copied from master records (`arguments`, `rationale`, `applyMessage`, `target`) are hidden from anyone who is not an administrator, a holder of STEWARD or above, or the holder of the agent's owner profile; written: **by the module only** (read only to every Factory role; an administrator may still delete rows) | One decision of a run that a person must or may see: tool, target, action, arguments, confidence, rationale, status (`PROPOSED`, `ACCEPTED`, `REJECTED`, `APPLIED`, `FAILED`), `applyAfterApproval`, `decidedBy`, `decidedAt`, `applyMessage`. The deciding fields (tool, action, arguments, target, status, `applyAfterApproval`, run, agent, project, `decidedBy`, `decidedAt`) are read only for everybody; a person decides through the route or the review task, not by editing the line. |

`AgentDefinition` fields that matter:

- **kind**: `STEWARD_MATCH` or `QUALITY_FIX` (the behaviour; a new behaviour is one class implementing
  `AgentFramework.Behaviour` and one line in `AgentFramework.behaviour`).
- **trigger**: `MANUAL`, `EVENT` (with `triggerEvent`: `MATCHING_RUN_DONE`, `MASTER_LOAD_DONE`, `STAGING_LOAD_DONE`) or
  `SCHEDULE` (with `scheduleMinutes`, never below 5).
- **allowedTools**: the allow-list. Notation: `factory:suspect.decide`, `factory:record.correct` (the two built-in actions),
  `<mcp-server>/<tool>` (one MCP tool), `<mcp-server>/*` (every tool of one server), `connectors:<flow>` or `connectors:*`.
  The family before `:` or `/` is case-insensitive. A decision always names one tool, never a pattern. **An empty list
  allows nothing**: every decision is then recorded as refused.
- **autonomy**: `SUGGEST` (default, and what any unknown value reads as), `ACT_WITH_APPROVAL`, `ACT`.
- **confidenceThreshold**: 0..100, default 90.
- **ownerProfile**: the EBX profile that reviews the agent (`R<role>`, `U<user>`, `B<built-in>`; a bare role name is read as
  `R<name>`).
- **enabled / enabledBy**: not typed. `POST /agents/{code}/enable` (right DESIGN, plus write access to the agent's project in EBX; the caller must also hold STEWARD)
  writes `enabled = true` and, in `enabledBy`, the caller's login SIGNED by the server (an HMAC over the agent code, its project, kind, entity, zone, autonomy, threshold,
  max items, owner profile, allowed tools and instructions, keyed with `FactorySettings.agentActivationKey`, a secret the installer draws once and hides from every
  person). An event or a schedule acts for that person only while the signature verifies against the agent's CURRENT settings: changing the project, kind, autonomy,
  tools or instructions afterwards cancels the activation (switch it on again), and so does a value somebody wrote by hand. `POST /agents/{code}/disable` (right
  STEWARD) switches it off. An agent marked enabled whose activation does not verify is treated as not switched on, with one WARN per start naming it.

`AgentDefinition` is **not** in the project backup (its code is the key: restoring a project into another factory would
collide with that factory's own agents). `AgentProposal` is backed up as a child of its `AgentRun`.

## The runner (`ebx/AgentFramework.java`)

A run always happens in a background job of kind `AGENT_RUN` (resource DESIGN, like the other model passes), so a model call
never holds an HTTP thread.

1. **Who acts.** The job acts for one person (`Actor.current(session).userId`): the caller of `POST /agents/{code}/run`, or the person whose signed activation
   verifies for an event or a schedule (otherwise `engine.conflict.agentNotActivated`). That person must hold the Factory right STEWARD, re-checked when the job
   runs; otherwise the run is refused (`conflict.agentActorRight`). For an event or a schedule they must also still have write access to the agent's project in
   EBX (`conflict.agentProjectRight`). Their EBX permissions are read once with `Repository.createSessionPermissionsForUser` (public API). Closed
   (`ebx.factory.agents.enabled` not `true`), `AgentFramework.run` refuses before anything else.
2. **Gather.** The behaviour lists the items, at most `maxItems` (1..100, default 20), **keeping only what the acting person
   can see**: a suspect pair whose two records they can read, a validation finding on a field they can see.
3. **Data policy.** Values go to the model as the project's LLM data policy says (`docs/LLM-DATA-POLICY.md`): `SAMPLE_VALUES`
   shows them (cut to 200 characters), `SHAPES_ONLY` shows their shape, `NONE` shows only whether they are filled; a sensitive
   field always shows `*`. The items are fenced as data (`PromptData`), never instructions.
4. **Ask once.** Through the existing LLM path: `AgentRuntime.ask` (the `llm.ask` contract) when available, else
   `AgentRuntime.run` with a single local tool `submit_decisions` (the `AgentLlmConnector` path). No item means no model call:
   the run is recorded as "nothing to decide". A model call that fails (provider refusal, quota, timeout) leaves the run
   `FAILED` with the reason, still carrying the agent, the person and the inputs, and fails the job.
5. **Decide each answer** (`agent/AgentPolicy.java`, pure):

   | Situation | Disposition |
   |---|---|
   | the model abstained (`ABSTAIN`) | NO_ACTION (nothing recorded but the run) |
   | the tool is not on the allow-list, or the person's permissions do not allow the action | REFUSED (proposal `REJECTED`, never applied) |
   | autonomy `ACT` and confidence >= threshold | APPLY now |
   | autonomy `ACT` below the threshold, or `ACT_WITH_APPROVAL` | AWAIT_APPROVAL (proposal `PROPOSED`, applied once approved) |
   | autonomy `SUGGEST` | SUGGEST (proposal `PROPOSED`, never applied by the runner) |

   A decision without a confidence counts as 0: it never acts alone. A target the agent was not shown, or an action its
   behaviour does not know, is dropped. A `connectors:` call is never applied by the runner, at most suggested.
6. **Apply** (APPLY, or after approval): with the system session of the job, after the explicit permission check of step 5,
   and with the procedure's execution information set to `agent <code> for <user>` so the EBX history names both. A
   `TOOL` step and an `APPLIED` (or `FAILED`) proposal record the result.
7. **Escalate.** Any `PROPOSED` line launches one review task (below). If the workflow is not published or the owner profile
   is not valid, the proposals still wait in `AgentProposal` and the run's `escalation` says why.

### The two example behaviours

- **Data steward** (`STEWARD_MATCH`, `ebx/StewardMatchAgent.java`): the pending SUSPECT pairs of the agent's entity
  (`MatchingResultsBuilder.buildPendingPairs`), each with its field-by-field verdict. Actions `MERGE` / `DISTINCT`, tool
  `factory:suspect.decide`, applied through `RecordLineageReader.decideSuspect` (the same path as the steward's own button).
  Permitted only when both records are writable by the acting person.
- **Quality** (`QUALITY_FIX`, `ebx/QualityFixAgent.java`): the records of the entity (zone `MASTER` or `STAGING`) whose
  validation report has a WARNING or worse on a field (scan capped at 5,000 records), with the field type, message, value
  and up to 8 other fields for context. Action `CORRECT` with `{"value": ...}`, tool `factory:record.correct`, applied as a
  single-field modification after `parseXsString` checks the value. Permitted only when the field is writable by the acting
  person.

### Generic tools (not usable as written: F3-04)

The runner accepts the action `CALL` from the model for any behaviour, and a decision may name an MCP tool on the allow-list (`server/tool`, arguments
in `{"tool": ..., "args": {...}}`). **Do not rely on this path.** Neither example behaviour copies `tool` and `args` into the proposal's arguments, so an
application ends "Not a server/tool call"; and where it is reached, the allow-list is checked against the proposal's `tool` field while the tool that is run
is read from the arguments (the finding F3-04, open: one source for the tool, a check on the value that is run, and the `connectors` server refused there too). It
is applied only when the MCP client runs as the acting person (`McpServers.mode` = `session`, a token minted for them, CLAUDE.md §9); in `agent` mode (the
shared agent account) the call is refused. Since the proposal lines are now written by the module only, a person can no longer put an arbitrary tool into one.

## Identity

- A run's `actingFor` is the person; its `via` says how it started (`manual`, `event MATCHING_RUN_DONE`, `schedule`, `review`). For `event ...` and `schedule` the person
  is the one of the signed activation, never the text of a row; a manual run (`POST /agents/{code}/run`) acts for the caller but, since 1.4.2, needs the agent switched on with a valid signed activation (before, a disabled agent ran, in `ACT` mode too: F3-08 point 1).
- A job runs `Actor.as(actingFor, "agent <code> · <via>")`, so the job row, the logs and any minted token carry the person.
- Records changed by an agent carry `agent <code> for <user>` as the procedure's execution information.
- A proposal applied after a review is applied as the reviewer (`decidedBy`), with the reviewer's permissions.

## The review workflow (`tools/workflow/MdmFactoryAgentEscalation.json`)

Data context: `agentRun`, `agentCode`, `ownerProfile` (in), `decision`, `decidedBy`, `comment`, `resultJson` (out).

1. **User task** (`workflow/AgentEscalationUserTask`, a custom `UserTask`): the work item is offered to the profile in
   `ownerProfile` (a static workflow model cannot offer to a variable profile, hence the bean). It opens the `AgentProposal`
   rows of the run, which are read only. The reviewer refuses single lines through the decide route below (`POST /agents/proposals/{id}/decide?accept=false`; the task's
   text says so since 1.4.0), then **Approve** (the open lines are accepted, and those waiting for approval are applied with the reviewer's permissions) or **Reject all**.
   Closed, the task cannot be completed (the refusal is shown and the work item stays open). Known weakness (F3-104): the grid shows primary keys, not the records,
   and Approve applies every open line.
2. **Script task** (`workflow/AgentDecisionTaskBean`): `AgentReviews.close` checks the reviewer holds the owner profile, then
   decides every open line; a line that fails is reported and left as it was.

Launched by `AgentEscalations.launch` through `WorkflowLaunches.launch` (context class loader of `WorkflowEngine`,
CLAUDE.md §10). The script task bean is declared in `module.xml`; the user task class is not (EBX accepts only library beans there and refuses to start with a `UserTask` in the list): the model names it in `userTaskBean`. The model has to be imported and published like the other
`tools/workflow` models (publication name `MdmFactoryAgentEscalation`).

## Triggers

- **Manual**: `POST /agents/{code}/run`.
- **Event**: every successful job of kind `MATCHING_RUN`, `MASTER_BUILD_RUN` or `STAGING_LOAD_RUN` raises its event at the end
  of `FactoryJobQueue.runHandler`; each enabled agent of that project listening to it is queued, acting for the person of its signed activation (an agent whose
  activation does not verify is skipped, one WARN per start). Closed, the event does nothing and one INFO line per start says so. The hook never fails the job. A pipeline step launched by a workflow (`MatchingRunTaskBean`), not a job, raises no event
  in this first cut.
- **Schedule**: register `com.on.ebx.factory.scheduler.AgentScheduledTask` once as a Task Scheduler task (a `/tasks` row,
  never with `userReference`, then a schedule every 5 minutes; same procedure as `GoldenKeyRefreshScheduledTask`,
  `docs/MATCHING.md` §20.2). Each tick queues every enabled scheduled agent whose `scheduleMinutes` have passed and whose signed activation verifies,
  stamping `lastRunAt` first. The scheduler session must be allowed to queue jobs (an administrator). Closed, a tick does nothing (one INFO line per start).

## REST

| Route | Right | What it does |
|---|---|---|
| `POST /agents/{code}/enable` | DESIGN, the agents open, the definition visible to the caller, the agent's project visible and writable with the caller's own EBX permissions, and the caller holding STEWARD | Switches the agent on for the caller (signed activation); answers `{agent, enabled, actingFor}`. |
| `POST /agents/{code}/disable` | STEWARD, same project check; allowed while the agents are closed | Switches the agent off. |
| `POST /agents/{code}/run` | STEWARD, the agents open, the definition visible to the caller, the project visible and writable with the caller's permissions | Queues a run acting for the caller; answers `{started, jobId, merged}`. |
| `POST /agents/proposals/{id}/decide?accept=true\|false` | STEWARD, the agents open, the project visible to the caller, and an administrator or a holder of the agent's owner profile | Rejects, accepts, or (for a line waiting for approval) applies it as the caller with the caller's permissions. |

Closed agents answer HTTP 409 `engine.missing.agentsClosed` (it names the property). Other coded refusals of these routes: `engine.conflict.agentNotActivated`,
`conflict.agentActorRight`, `conflict.agentProjectRight`, `conflict.agentEnableByPerson`, `invalid.agentActivation`, `missing.agent`, `conflict.agentProposalDecided`.

## What is not done (open findings of the 2026-11-12 review, which is why the agents ship closed)

- No screen: no agent list, no proposal inbox outside the workflow task, no Cockpit tile (F3-104). `/status` says whether the agents are open, not what they did.
- Only the two built-in actions are applied; generic MCP calls do not work as written (F3-04); Connectors flows are only ever suggested.
- No per-agent budget and no memory of what was already proposed or refused: a scheduled agent asks the model again about the same items every run, up to 100
  items every 5 minutes, and a refused decision can be proposed again (F3-101, F3-07).
- Data sent to the model: the value of a flagged field follows the project's data policy, but the validation message is sent whatever the level, and
  `SAMPLE_VALUES` also sends up to eight other fields of the record; `docs/LLM-DATA-POLICY.md` now has a section on what the agents send and to whom (1.4.2); the validation message is still not governed by the level (F3-05).
- In `ACT` the only barrier before a write or a merge is the confidence the model declares; an empty corrected value is accepted; several decisions on one
  target are all applied; no undo for a merge (F3-07).
- At the review the agent's autonomy is not re-read and no digest of the line is kept (the line is now module-written, which closes the editing path of F3-03, not
  the rest).
- Events from workflow-launched pipelines are not raised.
- Existing `enabled` flags written before 1.4.0 are void: switch the agents on again (`docs/UPGRADE.md` section 12.3).

## Needs a live test on the RC

Done on the RC: `MdmFactoryAgentEscalation` imported and published, its work item offered to `Badministrator`; a
quality agent in `SUGGEST` on a staging entity with a value past its length (proposals, the review task, accept and reject through the
decide route, a second decision refused); Approve in the inbox, reaching `AgentDecisionTaskBean` (the line ACCEPTED, decided by the
reviewer); the same agent in `ACT` with threshold 0 (the correction applied, one `TOOL` step); an event agent on `STAGING_LOAD_DONE` (one run
after a staging load, acting for `enabledBy`); `llm.ask`; a failed model call (run `FAILED`, still found by agent).

**That run predates the correction wave of 2026-11-12** (closed switch, signed activation, module-written proposals). `tools/e2e/agents_drift_rc.py` has not been
re-run against it: it writes `enabled` / `enabledBy` through Data Services (scenario E), which is now refused, and needs `-Debx.factory.agents.enabled=true` on the
server and the `enable` route instead. The offline suite covers the pure parts (switches, activation, access rules, lifecycle) and is green; nothing of the new
identity path has been seen running on an instance.

Still to do:

- Reject all in the inbox; a work item offered to a role profile and to a `U<user>` profile.
- The EBX history line `agent <code> for <user>` on a record an agent changed.
- One steward agent in `SUGGEST` on a project with pending suspects.
- The steward agent in `ACT` with a low threshold: the merge is applied.
- A user without write access on the table: every decision must be `REJECTED` (refused), none applied.
- An event agent after a matching run job, and `AgentScheduledTask` registered with a schedule.
- The `AgentLlmConnector` fallback (`ebx.factory.connectors.via=direct`).
