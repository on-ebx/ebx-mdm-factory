# Distribution of golden records — lot 1 "Engine"

Written 2026-09-24 on branch `feature/distribution-engine` (worktree
`ebx-mdm-factory-distribution-engine`), from `SPEC-distribution.md`; revised the same day after a
coordinator review of the first commit (§10). Scope: the engine only — no wizard screen (a separate lot 3
builds it on top of the REST API documented here). This lot was written and compiled/tested locally; **no
EBX instance was reachable during the session**, so nothing described below was run against a live
repository. Every claim about live EBX/Connectors behaviour that was not measured here is marked "not
verified" and traced back to its source (CLAUDE.md, `list_operations`, `get_syntax_guide`, or a working
call site elsewhere in this codebase it was modelled on).

## 1. Data model

Two new tables in `MdmFactory.xsd` (`tools/build_xsd.py`, `FactoryPaths.java`):

- **`DistributionTarget`** (`/root/DistributionTarget`) — one Connectors connection + write operation per
  project, receiving its golden records. Fields: `connector`, `operation`, `connectorsMappingIds` (one
  `tablePath=mappingId` pair per master table, comma-separated — parsed/formatted by
  `distribution.TargetMappingList`, never split by hand), `mode` (`REALTIME`/`ON_DEMAND`/`SCHEDULED`),
  `cron`, `scope` (`CHANGED`/`ALL`, ON_DEMAND/SCHEDULED only), `approval`, `watchedFields`,
  `includeCrosswalk`, `includePending`, `state` (`DRAFT`/`WAITING`/`ARMED`/`PAUSED`/`DISABLED`),
  `armedViaOverride`, `firstFullSendRunId`, `lastSendAt`/`lastStatus`/`lastMessage`, `scheduleKey`,
  `launcherName`.
- **`DistributionEvent`** (`/root/DistributionEvent`) — the outbox a REALTIME target's table trigger
  writes into. Fields: `project`, `target`, `tablePath`, `recordKey`, `change`
  (`CREATE`/`MODIFY`/`DELETE`/`RETIRE`), `status` (`PENDING`/`SENT`/`FAILED`/`SKIPPED`), `attempts`,
  `createdAt`, `sentAt`, `message`, and, added in the coordinator-review pass: `survivorKey` (the golden
  record a `RETIRE` was merged into — §5.3) and `nextAttemptAt` (when an automatic retry is next due —
  §6).

## 2. Pure package `com.on.ebx.factory.distribution`

No EBX class, unit-tested directly (`tools/test_local.sh`):

- **`DistributionArmingGate`** — the arming gate. `canSendFirstFull(Readiness)` gates the button that
  starts a scope-ALL first full send (landing loaded from every source, master built, first matching run
  finished); `stateAfterFirstFullSend(boolean)`/`stateForOverride()` say what state results afterwards
  (or from the admin "arm without it" override). `mayQueueRealtimeEvent(state)` is the one-line check the
  generated trigger's SQL predicate mirrors (`state = 'ARMED'`).
- **`EventCoalescer`** — nets several events on one record from one drain pass into one effective change
  (`CREATE`+`MODIFY`→`CREATE`, `CREATE`+`DELETE`→`NONE`, `MODIFY`+`DELETE`→`DELETE`, anything+`RETIRE`→
  `RETIRE`), with every non-surviving id marked for `SKIPPED`.
- **`RetireRules`** — the retire decision from three plain MAME facts (state, golden key, auto-created) —
  see CLAUDE.md §8 for the two situations it covers (a `MERGED` record, or an unmerge-deleted
  auto-created golden). Wired into the generated trigger body (state comparison) and the dispatcher
  (retire-notice construction) — see §5.3.
- **`TargetMappingList`** — parses/formats `DistributionTarget.connectorsMappingIds`.
- **`TargetValidator`** — validates a target's `connector`/`operation`/`mode`/`cron`/`scope` combination
  before it is ever saved (EBX's own static enumeration is non-blocking — CLAUDE.md §2 — so a bad `mode`
  would otherwise sit silently until a send is attempted).
- **`RetryBackoff`** — exponential/capped backoff policy, wired into the dispatcher's automatic retry loop
  (§6).
- **`PrimaryKeyFormat`** — the `|`-separator/`\`-escape algorithm `com.onwbp.adaptation.PrimaryKey` itself
  uses (CLAUDE.md §9), written down and unit-tested (including a value containing a pipe) as reference and
  fallback. **Not what the generated trigger actually runs** — the trigger uses EBX's own
  `core.complex.formatPrimaryKey` instead (§5.1), which is guaranteed to agree with this algorithm since
  it IS the same class; this pure class exists for documentation and for any future Java-side caller that
  needs to reproduce or verify the format without a live EBX call.
- **`DistributionTriggerBody`** — builds the EBX-Script source of a master table's trigger (see §5).

## 3. EBX-bound classes, `com.on.ebx.factory.ebx`

- **`DistributionConfigurator`** — creates/updates one Connectors OUTBOUND mapping per master table of a
  project for a target (`connectors_mapping_upsert`, dry run then real — same calling convention as
  `LandingFlows.upsertFlow`), `inputs.mergedRecords=exclude` always, `crosswalk=true` when the target
  asks for it. Master tables = the project's `StagingEntity` rows whose status is accepted-like and whose
  `targetTable` is set (the same population `MasterGenerator` promotes). Writes
  `DistributionTarget.connectorsMappingIds` back. The document is built by `mappingDocument(...)` and always
  names the master dataspace (see "The mapping document always names the master dataspace" at the end).
- **`DistributionRunner`** — `sendFirstFull` (scope ALL, gated by `DistributionArmingGate`, arms or waits
  the target), `armWithoutFirstFullSend` (admin override), `sendNow` (ON_DEMAND/SCHEDULED/manual, scope
  `changed`/`all` via `connectors_sync`). `readiness(...)` reads the project's own pipeline state
  (`ProjectSource.status`, `PipelineStep` rows) for the gate.
- **`DistributionTriggerInstaller`** — declares (`set_script_trigger`, scope `table`, types `afterCreate`/
  `afterModify`/`afterDelete`), authors (`update_script`) and publishes (`publish_data_model`) the three
  triggers on every master table of a project, mirroring `CapabilityApplier`'s declare→author→publish
  sequence for script constraints (CLAUDE.md §5). Before generating a table's bodies, it calls
  `describe_table` (`DistributionTriggerInstaller.describeTable`, coordinator-review addition) to read the
  table's REAL primary-key field names and whether it carries the Match-and-Merge technical fields
  `Linked_Field_State`/`GoldenId` — never assumes a single `id` field (falls back to `["id"]` only when
  `describe_table` fails outright or flags nothing, which every table this module generates would still
  satisfy).
- **`DistributionConnections`** (coordinator-review addition) — `connectors_list` + `connectors_describe`
  per connector, classifying each operation as write-capable or not for the target picker (§7).
- **`DistributionDispatcher`** — one module-owned daemon thread (started in
  `FactoryModuleRegistration.handleRepositoryStartup`, stopped in the new `handleRepositoryShutdown`
  override — CLAUDE.md §10's "never pins a stopped class loader"), draining due `PENDING` events every 10
  seconds: groups by `(target, tablePath, recordKey)`, coalesces, **re-reads the master record's current
  committed state** before deciding what to send (§5.2), sends the surviving row (`connectors_sync` for an
  ordinary change, a direct `connectors_call` retire notice for `RETIRE` — §5.3), and marks
  `SENT`/`FAILED`/`SKIPPED`, with an automatic backoff retry on `FAILED` (§6).
- **`DistributionPause`** — pauses every currently-`ARMED` REALTIME target of a project, and resumes them
  with a `changed`-scope catch-up send. Hooked into `StagingLoader.load` (only when `scope=all`),
  `MasterLoader.load` (always), `MatchingConfigurator.run` (only when `scope=all`) — each of those methods
  was renamed to a package-private `*Internal` and given a thin public wrapper doing
  pause → call `*Internal` → resume in a `finally`, so the original (large) method bodies were not
  touched.
- **`DistributionScheduler`** — creates the workflow launcher row (Data Services REST,
  `Bworkflow_launchers/workflow_launchers_1.0`) and, for a `SCHEDULED` target, the Scheduler REST API
  schedule (`EBX|SchedulerWorkflowLaunch` + `workflowLauncherName` parameter). Both calls run as the
  caller's own session (`SessionToken`/`LocalEndpoint`), never a stored credential of their own.
- **`DistributionReader`** — the plain CRUD behind the REST routes that is not Connectors/data-model
  orchestration: list/upsert targets, set state (disarm), list/replay events.

## 4. Workflow / bean / scheduling

- **`DistributionTaskBean`** (`com.on.ebx.factory.workflow`) — sends one target, or every non-REALTIME
  target of a project when `targetId` is blank, via `DistributionRunner.sendNow`. Declared in
  `module.xml` (library mode, bilingual documentation, same shape as `MasterLoadTaskBean`).
- **`tools/workflow/MdmFactoryDistribution.json`** — `projectCode`/`targetId`/`scope`/`approval` in,
  `resultJson`/`failed`/`status` out. An `approval=true` branch opens a user task "Approve the send"
  (`ebx-root-1.0@default` on the target record) before the send; `approval=false` sends directly. Never
  imported/published/run against a live instance in this session.
- Deleting a project deletes its targets, their `LoadPause` rows and their Connectors outbound mappings (the `flows` option, ownership by label `Distribution: target <id> ` and master dataspace), never a scheduler schedule or launcher they name: `docs/PROJECT-DELETION.md`, "Distribution targets".
- Scheduling: `DistributionScheduler.createLauncher` then, for `SCHEDULED` targets,
  `.createSchedule` — see the class's own "not verified" note for the exact endpoint shapes.

## 5. Real-time triggers — what the generated EBX Script does and does not do

Each master table gets three triggers (`DistributionTriggerBody.body`). Every one:

1. Builds `recordId` from the table's REAL primary-key fields (§5.1) — never assumes `id` alone.
2. Looks up the Factory's own dataspace/dataset (`db.findDataspace`/`db.findDataset`, names read from
   `FactoryPaths.DATASPACE`/`DATASET` at generation time) and queries `DistributionTarget` for REALTIME
   targets of this project that are `ARMED` (since lot S3 through `db.createQuery(text)` + `db.addDataset` +
   `db.setParameter`, never a typed `sql` literal — Round 9; the `references model "MdmFactory" from module
   "ebx-mdm-factory"` reference stays, valid because the module packages exactly one XSD).
3. For each matching target, writes one `DistributionEvent` row in a SEPARATE transaction, on the Factory
   dataspace (§5.2 — **not** the same transaction as the record's own save, unlike what the first version
   of this doc claimed).
4. *(Removed by lot F3, 2026-10-19.)* The `afterModify` body used to compare `Linked_Field_State` and may write `change=RETIRE`;
   no generated body references a Match and Merge field any more (see "Lot F3" at the end of this file). Retire notices are §5.3's merge-log watch and the state sweep.
5. Never calls the target itself — no `core.http` anywhere in the generated body.

### 5.1 Composite primary keys (coordinator review item 1)

The first version of this trigger assumed every master table's key was the single field `id` — true for
every table `MasterGenerator` builds today, but the coordinator asked for it to be read from the schema
rather than assumed, so a future or hand-built master table with a different key shape is not silently
mis-keyed. `DistributionTriggerInstaller.describeTable` reads the table's real primary-key field names via
`describe_table` (defensively — the exact JSON flag key was not confirmed live, so it checks several
plausible ones and falls back to `["id"]` when none matches or the call fails). The trigger then builds:

```
var pk := cp.primaryKeyOf<master.Company>();
pk.id := _ebx.record.id;              // one line per primary-key field, in order
const recordId := cp.formatPrimaryKey(pk);
```

This uses EBX's OWN `core.complex.formatPrimaryKey`, not a hand-built pipe-joined string — guaranteed to
agree with `PrimaryKey.parseString` on the Java side (the dispatcher's `masterRecordExists`, §5.2) because
it comes from the same class (CLAUDE.md §9). `distribution.PrimaryKeyFormat` documents and unit-tests the
underlying `|`-separator/`\`-escape algorithm as a reference, but the trigger itself never reproduces it by
hand in generated script text.

### 5.2 Transaction semantics (coordinator review item 2) — corrected claim

**The first version of this doc was wrong**: it said a rolled-back save leaves no event because the event
write was "in the same transaction". It is not — `execute transaction tr on factoryDs` opens a transaction
on the FACTORY dataspace, a different dataspace than the master record's own save, and EBX has no
cross-dataspace shared-transaction mechanism to reach for instead. So:

- A save that ultimately **rolls back** can still leave a committed `DistributionEvent` row.
- Conversely (much less likely in practice, but not impossible) the event's own transaction could fail
  after the record's save already committed.

**The design is kept** (there is no alternative that shares the transaction), but the dispatcher no longer
trusts an event's `change` blindly — `DistributionDispatcher.masterRecordExists` re-reads the master
record's CURRENT committed state directly (same repository, no MCP call) before sending:

- `CREATE`/`MODIFY` whose record **no longer exists** at dispatch time → `SKIPPED` (the save never
  committed, or the record was since deleted).
- `DELETE` whose record **still exists** at dispatch time → `SKIPPED` (the delete never committed, or the
  record was recreated).
- `RETIRE` is sent regardless of plain existence — CLAUDE.md §8: an unmerge-deleted golden's row still
  physically exists, so existence alone cannot tell a retire apart from a live record.

This makes the dispatcher idempotent and truthful about what it actually sends, independent of whichever
of the event row and the record save happened to commit.

### 5.3 Retire notices — now from the merge log, not from the trigger (rewritten 2026-09-27, SPEC-retire.md)

**Rounds 2–3 of this section were wrong about the mechanism, and it was measured live before being fixed**
(project Personnes, RC2, 2026-09-27 23:19): accepting suspect 527 into golden 692's group produced **zero**
trigger-written `DistributionEvent` rows for either 347 or 527 — an armed REALTIME target received `CREATE
692`, `MODIFY` of the two addresses, and nothing at all about the two records that had just become MERGED. A
consumer was left holding three live copies of the same person.

**Why**: a merge or an unmerge only ever writes the Match and Merge add-on's own metadataset
(`RecordMetadata`/`MergingProcess`) — it never touches the merged/unmerged row's own value. So the
`afterModify` trigger, which can only ever fire on an actual save of the master row itself, simply never runs
for the record that got merged or unmerged. The `Linked_Field_State` before/after comparison
`DistributionTriggerBody.retireDetectionBlock` built was kept in the generated script for a while as harmless; **lot F3 (2026-10-19) removed it** because
it was not harmless (a deleted matching configuration made the whole master model stop compiling, see "Lot F3" at the end of this file). It was never how a merge's own
retire notice gets produced.

**The actual mechanism is a merge-log watch, run by the dispatcher, not the trigger**:

- **`distribution.MergeLogWatch`** (pure, no EBX import, unit-tested — `MergeLogWatchTest`) is hand​ed, per
  master table: the table's watermark (the highest `MergingProcess.id` already accounted for, plus every
  merge id already known to be `undone`), every current `MergingProcess` row (`id`, `isUnmerged`), and, for
  a merge id it needs to act on, that merge's `RecordMetadata` members (`functionalId`/`state`/`goldenId`/
  `autoCreated`). It decides:
  - a **new** merge (id above the watermark, not undone): one `RETIRE` per member whose state is `MERGED`
    (`distribution.RetireRules` supplies the survivor — the member's own `goldenId`; blank when the member
    carries none);
  - an **undone** merge (a merge id already known from an earlier pass, newly observed `isUnmerged=true`):
    one `RETIRE` of the merge's own auto-created golden (found by `autoCreated=true`, no survivor —
    `RetireRules`'s `DELETED`+`autoCreated` case), plus, when `retireUndoneMerges` is true, one `CREATE` per
    OTHER member now back to `GOLDEN` — a member still `PENDING_APPROVAL` gets nothing (documented gap: the
    next matching run or a person's decision eventually makes it golden, and a later pass or the ordinary
    trigger picks that up from there);
  - a merge that is both new AND already undone in the same pass (never announced either way) nets to
    nothing, same family as `EventCoalescer`'s own `CREATE+DELETE → NONE`;
  - `retireMode=NONE`: the watch still classifies every row and advances the watermark/undone-set exactly as
    normal, it just never emits an event.
- **`ebx.MergeLogWatcher`** is the EBX-facing counterpart: for each master table a target sends through, it
  resolves the table's metadataset the same way `MameMetadatasetReads` already does (`<dataset>_<table>_MDS`
  in the master dataspace, CLAUDE.md §8 — no add-on Java class involved, so a table with no matching, or
  matching configured but never activated, is skipped silently, never an error), reads `MergingProcess`
  (full scan — bounded by the number of merges, not the business table's own size) and, only for candidate
  merge ids, `RecordMetadata` filtered by `mergingProcessId`, feeds `MergeLogWatch.decide`, writes the
  resulting rows into the project's `DistributionOutbox` in one `Procedure` (all privileges, same
  session/home as every other outbox write), and — only once that write has been attempted — persists the
  advanced watermark onto the new `DistributionTarget.mergeLogWatermark` field (JSON, one entry per table
  path, e.g. `{"/root/Personne":{"last":1,"undone":[]}}`). This is the "at-least-once" contract SPEC-retire.md
  asks for: a crash between the two re-emits the same RETIREs on the next pass, which the dispatcher's own
  existence/coalescing checks already tolerate.
- **Where it runs**: `DistributionDispatcher.drainTarget` calls `MergeLogWatcher.watch` **before** draining
  the outbox's existing PENDING rows, so a merge's RETIREs are queued in the same pass as the golden's own
  ordinary trigger-written CREATE. **Ordering within that same pass**: every group whose coalesced effective
  change is not `RETIRE` is drained before every `RETIRE` group (a plain partition on `EventCoalescer.coalesce`'s
  own result, computed once for ordering and again, harmlessly, inside `drainGroup`) — so a golden's own
  `CREATE`/`MODIFY` always goes out before the retire notices of the records that fed it.
- **First pass / catch-up**: a table with no watermark yet initialises `last` to the current max
  `MergingProcess.id` **without emitting anything** (merges that predate the target ever being watched were
  never live records to that target in the first place — same reasoning as `mergedRecords=exclude` on a
  first full send); a target re-armed after a pause resumes from wherever its own watermark was left, so
  merges made while paused ARE caught up and sent.
- **Retire mode**: `DistributionTarget.retireMode` (`NOTICE`/`TOMBSTONE`/`NONE`, default `NOTICE`) and
  `retireUndoneMerges` (boolean, default true). `TOMBSTONE` is Kafka-only and **not implemented** — the
  Connectors lot that would let the Kafka connector actually send a null-value publish with headers has not
  shipped — so `ebx.DistributionConfigurator.configure` refuses to configure a target whose `retireMode` is
  `TOMBSTONE`, naming the connector type in the refusal message. If a target somehow still reaches dispatch
  with `TOMBSTONE` (its mode changed after a successful Configure), `DistributionDispatcher` sends an
  ordinary `NOTICE` document instead and records that fallback in the event's own history message — a retire
  is never silently dropped over an unsupported mode. `NONE` is also enforced a second time at dispatch (not
  only upstream in the watch), in case some other path (the legacy trigger comparison) ever produces a
  RETIRE for a `NONE` target: it is marked `SKIPPED` with a named reason.
- **The retire document** (`DistributionDispatcher.sendRetireNotice`, still a **direct**
  `connectors_call(connector, operation, allowWrite=true, ...)` bypassing the mapping's own
  `mergedRecords=exclude` filter) is now `{id, retired: true, retiredTo?, reason?, mergeId?, at}` —
  `reason` (`MERGE`/`UNMERGE`) and `mergeId` (the `MergingProcess.id`) come from the two new
  `DistributionOutbox.Event`/`DistributionEvent` fields the merge-log watch alone ever sets (blank for a
  RETIRE the legacy trigger comparison might still produce), `at` is a fresh UTC instant of the send itself
  (not the outbox row's own `createdAt`, which is when the merge/unmerge happened, not when it was sent).
  The call now also carries `inputs` (`topic`/`key`) exactly the way `DistributionConfigurator.upsertMapping`
  configured the table's own OUTBOUND mapping to read them, read from `DistributionTarget.tableDestinations`
  — before this fix the retire call passed no `inputs` at all, so on a per-table-topic (Kafka) target every
  retire notice would have published on the connector's default topic instead of the table's own one. The
  document shape itself remains this codebase's own invention, not confirmed against any real target
  connector's expected retire payload.
- **Coordinator review fix (2026-09-28), measured live on RC2**: a mapping's `inputs` are TEMPLATES resolved
  PER RECORD by Connectors' own `Expressions.resolve` — only `${...}` placeholders are evaluated, so the bare
  field name `"id"` this section used to describe as the mapping's `key` input was never resolved at all, and
  every Kafka message (retire or not) carried the LITERAL key `id` on every message. Fixed to the template
  `${id}` on the mapping side (`DistributionConfigurator.DEFAULT_KEY_TEMPLATE`) and to the record's own key
  VALUE on the direct `connectors_call` retire path (which has no record for a template to resolve against) —
  a mapping written by an earlier version of this class is silently corrected the next time Configure runs.

**Lot S2 (2026-10-18) completes this section** - the merge-log watch cannot see an unmerge's former members (their `mergingProcessId` is reset), a
member that comes back `PENDING_APPROVAL` and only later `GOLDEN`, or a notice that was skipped. A state sweep covers those, the retire document gained
`_change`, and the first full send now leaves merged records out: see "Round 8 (lot S2)" at the end of this file.

**Not verified live**: the exact `MergingProcess`/`RecordMetadata` field layout used by `MergeLogWatcher` is
read from `MetadataTemplate.xsd` on this instance's server project plus the live facts recorded in
SPEC-retire.md (2026-09-27, above), not re-read from a running server in this session — no EBX instance was
reachable while writing this pass, only `tools/test_local.sh` (all green, `MergeLogWatchTest` included). The
whole engine path (watch → outbox → dispatcher → `connectors_call`) has not been exercised end to end against
a live Match and Merge group.

### What is still NOT implemented (documented scope reduction)

- **`DistributionTarget.watchedFields`** is stored but not enforced: every armed REALTIME target still
  receives every `CREATE`/`MODIFY` of its project's master tables unfiltered (a per-target field diff
  needs comparing `_ebx.previousRecord` against every watched field per TARGET, not just the one
  MAME-state comparison item 3 added — deferred).
- **Cost of the per-row query while paused** — see §6's closing note.
- **`DistributionTarget.retireMode=TOMBSTONE`** is refused at configure time (§5.3) — the Kafka connector
  does not yet support a tombstone (null-value) publish with headers; that is a separate Connectors-repository
  lot (SPEC-retire.md's own "Connectors lot"), not started here.
- **"Remove duplicates" and a record merged then deleted for good** — not measured on RC2 (SPEC-retire.md's
  own "Not in this lot").

## 6. Dispatcher and retry

- **Automatic backoff retry (coordinator review item 4, now wired up)**: a failed send goes back to
  `PENDING` with `DistributionEvent.nextAttemptAt` set from `distribution.RetryBackoff.delaySeconds`
  (exponential, capped) — the dispatcher's own PENDING scan skips a row whose `nextAttemptAt` is still in
  the future, so it is naturally picked up again once due. Past `RetryBackoff.MAX_ATTEMPTS` the event is
  left `FAILED` for good (`nextAttemptAt` cleared) — only a person's manual replay
  (`POST .../distribution/events/replay`) resets it to `PENDING` from there.
- The dispatcher is a single daemon thread, polling every 10 seconds; a project with many armed REALTIME
  targets and a high write rate has not been load-tested (no live instance was reachable). The retry delay
  granularity is therefore also effectively floored at 10 seconds (a row due mid-interval waits for the
  next pass).
- **Per-row query cost while a full reload is paused (coordinator review item 6)**: `DistributionPause`
  only stops NEW events from being queued (by moving the target out of `ARMED`) — it does **not** disable
  the trigger itself. Every row a full reload saves while paused still runs the trigger's own SQL query
  against the Factory dataset (`select ... from factory."DistributionTarget" ... where state = 'ARMED'`),
  finds no `ARMED` REALTIME target (since `DistributionPause` moved them to `PAUSED` first), and returns
  having written nothing. This is one small cross-dataspace query PER SAVED ROW of the reload, for every
  master table carrying the trigger — its cost was not measured (no live instance), and on a very large
  full reload (CLAUDE.md §15's own million-row volumes) it is a real, currently-unmeasured tax on top of
  the reload itself, separate from the trigger's ordinary per-save cost when not paused.

## 7. REST API (admin-only, like every other write route in `FactoryResource`)

Base `/ebx-mdm-factory/rest/v1/projects/{code}/distribution`:

| Method | Path | Body / query | Response |
| --- | --- | --- | --- |
| `GET` | `/targets` | — | `{targets: [ {id, connector, operation, connectorsMappingIds, mode, cron, scope, approval, watchedFields, includeCrosswalk, includePending, state, armedViaOverride, firstFullSendRunId, lastSendAt, lastStatus, lastMessage, scheduleKey, launcherName} ]}` |
| `GET` | `/connections` | — | `{connections: [ {connector, label, type, hasWriteOperation, writeOperations: [...], readOperations: [...], unknownOperations: [...]} ]}` — coordinator review item 5; see `DistributionConnections`' own "not verified" note on the write/read classification |
| `POST` | `/targets` | JSON body: `{id?, connector, operation, mode, cron, scope, approval, watchedFields, includeCrosswalk, includePending}` — validated by `TargetValidator` before saving | `{status, id}` |
| `POST` | `/targets/{targetId}/configure` | — | `DistributionConfigurator.configure` result (`{status, connectorsMappingIds, detail}`) |
| `POST` | `/targets/{targetId}/install-triggers?modelName=<master model publication name>` | — | `DistributionTriggerInstaller.install` result |
| `POST` | `/targets/{targetId}/first-full-send` | — | `DistributionRunner.sendFirstFull` result (`{status, runId, connectorsRunIds, rows, message, targetState, detail}`; `runId` is the Factory's step, `connectorsRunIds` the Connectors runs of the mappings) |
| `POST` | `/targets/{targetId}/arm-override` | — | `{status, targetState, message}` |
| `POST` | `/targets/{targetId}/disarm` | — | `{status, state}` |
| `POST` | `/targets/{targetId}/send?scope=changed\|all` | — | `DistributionRunner.sendNow` result |
| `POST` | `/targets/{targetId}/stop` | — | wave 13, administrators: `{targetId, stopped, jobsCancelled[], runsStopped[], message}` — see "Following and stopping a send" |
| `GET` | `/events?targetId=&status=&limit=50` | — | `{events: [ {id, target, tablePath, recordKey, change, status, attempts, message} ]}` |
| `POST` | `/events/replay?targetId=&eventId=` | — | `{status, replayed}` (blank `eventId`: every FAILED event of the target, or of the project when `targetId` is also blank) |

## 8. What to verify live, in order, after deployment

1. `python3 tools/build_xsd.py` then compare against the committed XSD (should be a no-op) — done in this
   session, `tools/test_local.sh` already confirms it stays in sync.
2. Create a project, get it through landing/staging/master/matching once (existing lots).
3. `POST /distribution/targets` with a real connector+write-operation pair, then
   `POST .../configure` — check the Connectors module (`connectors_mapping_get`) that the two mappings
   (`mergedRecords=exclude`, `crosswalk` per the flag) were actually created, not just that the route
   answered `DONE`.
4. `POST .../install-triggers?modelName=<publication>` — then, in the DMA, inspect the master model's
   table triggers directly (`list_script_table_triggers` if available, or the DMA UI) to confirm:
   - the deduced `scriptIdePath` guess resolved to the real path (`DistributionTriggerInstaller`'s
     fallback, a `list_scripts` search of the table's folder, is meant to catch a wrong guess, but this
     was never exercised);
   - `describe_table`'s primary-key flag was actually read correctly — check `tableDetail.primaryKeyFields`
     in the route's own response against the table's real key, and specifically test a table with a
     genuinely composite key if one exists, not just the common single-`id` case;
   - whether the table carries `Linked_Field_State`/`GoldenId` was detected correctly
     (`tableDetail.hasLinkedFieldState`/`hasGoldenId`) — compare against a table with Match and Merge
     activated and one without.
5. Create/modify/delete a master record with the target `ARMED` — check a `DistributionEvent` row
   appears (or does not, when `DRAFT`/`WAITING`/`PAUSED`/`DISABLED`), that its `recordKey` is exactly what
   `PrimaryKey.parseString` on the Java side expects (round-trip it through `masterRecordExists` by hand
   if needed), and that the dispatcher sends it and marks it `SENT`.
6. Deliberately roll back a save on an armed table (or kill the transaction) and confirm the resulting
   stale `DistributionEvent` is marked `SKIPPED` by the dispatcher, not sent — this is the behaviour §5.2
   now depends on instead of the (incorrect) "same transaction" assumption.
7. Merge two records (or trigger an unmerge) on a table with Match and Merge activated, with an armed
   target — confirm a `RETIRE` event appears with the expected `survivorKey`, and that
   `connectors_call(..., allowWrite=true)` actually reaches the connector with the hand-built document
   shape (§5.3) — this whole path is unverified.
8. Force a send failure (e.g. point a target's mapping at a broken connector) and confirm the event goes
   back to `PENDING` with a `nextAttemptAt` in the future, is skipped until then, retried automatically,
   and eventually left `FAILED` past `RetryBackoff.MAX_ATTEMPTS` (§6).
9. `GET .../distribution/connections` against a connector with a known write operation (e.g. an upsert)
   and one with only reads — confirm `hasWriteOperation`/`writeOperations`/`readOperations` classify them
   correctly; `DistributionConnections.looksWriteCapable`'s heuristic was never checked against a real
   `connectors_describe` answer.
10. `POST .../first-full-send` on a project whose pipeline is NOT fully ready — must be refused with a
    named reason (`DistributionArmingGate.canSendFirstFull`), not silently attempted.
11. Run a full pipeline reload (`master/load`, `matching/run?scope=all`) with an `ARMED` REALTIME target —
    confirm it goes `PAUSED` during the reload and a `changed`-scope catch-up send runs at the end
    (`DistributionPause`); if practical, measure the per-row cost the trigger's own paused-no-op query adds
    (§6's closing note — never measured).
12. `DistributionScheduler.createLauncher`/`.createSchedule` against the real Data Services / Scheduler
    REST API paths — the exact paths in this class are transcribed from a prior session's own note
    (CLAUDE.md §7/§2), not re-read from a live `GET` here.
13. Import `tools/workflow/MdmFactoryDistribution.json` (`workflow_model_import`), publish it, launch it
    with `approval=true` and confirm the user task opens on the target record, then `approval=false` and
    confirm it sends directly.

## 9. Deviations from SPEC-distribution.md, and why

- `connectorsMappingIds` is documented in the spec simply as "CSV" — implemented as
  `"tablePath=mappingId,..."` pairs rather than a bare id list, because a target can (and typically does)
  cover several master tables and the dispatcher/runner need to resolve a specific table's mapping id,
  not just know that some mappings exist.
- `watchedFields` is stored but not enforced on a `MODIFY` — see §5's closing "still not implemented" note
  (RETIRE production, the OTHER half of the original scope reduction, is now implemented — §5.3).
- No message bundle was created: this lot has no wizard/user-facing screen (explicitly out of scope), so
  every user-visible string it introduces (table/field labels and documentation in `MdmFactory.xsd`, the
  workflow model's own labels, `module.xml`'s bean documentation) is already bilingual inline, following
  the same convention the rest of this model already uses for non-wizard tables.

## 10. Coordinator review (2026-09-24), applied in this pass

Six items raised on the first commit (`9d9cbe4`), addressed in a follow-up commit on the same branch:

1. **Composite primary key** — fixed. The trigger no longer assumes `id`; it reads the table's real
   primary-key fields via `describe_table` and builds the key with EBX's own
   `core.complex.formatPrimaryKey` (§5.1). `distribution.PrimaryKeyFormat` documents/tests the underlying
   escape algorithm as a reference, with a unit test covering a value containing a pipe.
2. **Transaction semantics** — the wrong "rolled-back saves leave no event" claim is removed; the design
   is kept (no cross-dataspace shared transaction exists in EBX Script) and the dispatcher is made
   idempotent instead, re-reading the master record's current state before sending (§5.2).
3. **Retire notices** — now produced automatically from a plain `Linked_Field_State` comparison in
   `afterModify`, no MAME Java hook needed; the dispatcher sends them via a direct `connectors_call`
   carrying the survivor golden id (§5.3).
4. **Automatic retry** — wired up with `distribution.RetryBackoff` and a new `nextAttemptAt` field (§6).
5. **Candidate connections route** — added (`GET .../distribution/connections`, §7), backed by the new
   `DistributionConnections` class.
6. **Per-row query cost while paused** — documented as unmeasured (§6's closing note); not fixed (nothing
   to fix — it is inherent to the design, only its cost was previously left unstated).

`tools/test_local.sh`: green after every change in this pass (new/changed pure tests:
`PrimaryKeyFormatTest`, an expanded `DistributionTriggerBodyTest` covering composite keys and retire
detection). Nothing in this pass was run against a live instance either — every fix above is still subject
to §8's live-verification list, which was expanded accordingly (items 6, 7, 8, 9 are new).

## 11. Round 3 (2026-09-26) — real live compile errors, and the outbox moves out of MdmFactory

Fixes measured against the dedicated test project **DistribTest** (master dataspace `DistribTest_Master`,
dataset `Master`, table `/root/Sample`, key `id`), worktree `ebx-mdm-factory-distribution-live-3`, branch
`fix/distribution-live-3`. "Install triggers" (§3's `DistributionTriggerInstaller`) had, for the first time,
actually reached EBX's real compiler — `PipelineStep 395` recorded three verbatim errors on
`data-model/DistribTest_Master/root/Sample/@trigger/DistributionAfterCreate`:

1. `19:92: Invalid SQL expression - Cannot apply '=' to arguments of type FKTYPE(/root/Project …) = FKTYPE(…)`
   — `DistributionTarget.project` is a foreign key (`tools/build_xsd.py`: `fk='/root/Project'`), so
   `t.project = ?` compares an FK column to a bound parameter, not a scalar (CLAUDE.md §2, "A foreign-key
   column in SQL is a record"). **Fixed**: `t.project.code = ?` (`Project`'s primary key is `code`).
2. `28:11: This type of script cannot create transactions` — a **table trigger** script (unlike a REST
   service or workflow script) cannot open `execute transaction` **at all**, under any circumstance. This
   is categorical, not a matter of scope: the round-1/2 design's `execute transaction tr on factoryDs as
   system`, meant to reach the `MdmFactory` dataspace from inside the trigger, was never going to work — the
   round-1/2 doc's "separate transaction, different dataspace, so a rolled-back save can still leave a
   committed event" analysis (§5.2 above) was itself built on a false premise: no transaction to
   `MdmFactory` can ever be opened from a table trigger, committed or not.
3. `32:19` / `33:18`: `Expression of type 'string' cannot be assigned to variable of type '...primarykeyof
   factory.Project' / '...factory.DistributionTarget'` — a knock-on parse artefact of trying to write a
   plain string into what the compiler still resolved as FK-typed fields; moot once the outbox record below
   carries no FK fields at all.

**Design change (forced by error 2, not a preference): the outbox now lives IN the project's own master
dataspace**, as a second dataset next to `Master`, in a NEW packaged model
`DistributionOutbox.xsd` (one table, `Event`: `id`, `target` — a plain STRING, deliberately **not** a
foreign key, since it is written from a dataspace that cannot reach `MdmFactory`'s tables at all —
`tablePath`, `recordKey`, `change`, `survivorKey`, `status`, `attempts`, `createdAt`, `nextAttemptAt`,
`sentAt`, `message`). `ebx.DistributionOutboxInstaller.ensure` creates it idempotently
(`ProgrammaticService`/`doCreateRoot`, the exact same pattern `FactoryInstaller.install` already uses for
the `MdmFactory` dataset itself — CLAUDE.md §12), called from `DistributionTriggerInstaller.install` before
any trigger is authored. The trigger reaches it with `db.findDataset(_ebx.dataspace, 'DistributionOutbox')`
and writes through its own **ambient** `_ebx.transaction` — no `execute transaction` anywhere in the
generated body any more. Reading `MdmFactory.DistributionTarget` (a different dataspace) is untouched and
still fine: that is a plain `DATABASE_READ`, not a transaction, and every table-trigger type has that
capability.

**This makes the earlier "rolled-back saves can still leave a committed event" claim moot, in the good
direction**: the outbox write now happens through the trigger's OWN transaction context
(`_ebx.transaction`), not a separate one — so unlike the round-1/2 design, there is no longer a structural
reason to expect an event for a save that never committed. Whether EBX actually treats `_ebx.transaction`'s
effect on a *different dataset of the same dataspace* as commit-atomic with the record's own save was **not
verified live** (see "Live verification actually performed" below) — the dispatcher's existing
re-check-the-record's-current-state discipline (§5.2) is kept regardless, as a safety net, not removed.

**Live verification actually performed, and its exact scope:**

- `get_syntax_guide(triggerAfterCreate)` and `get_api_help` on `core.data.recordOf`/`findDataset`/
  `findDataspace` were read first, confirming the type's capability list (`REMOTE_REQUESTS, DATABASE_READ,
  DATABASE_WRITE, IMPURE, SCRIPT_LIBRARY_ACCESS`) and the exact signatures used above.
- The corrected FK comparison (`t.project.code = ?`) plus a `db.findDataset(_ebx.dataspace, 'Master')` +
  `db.recordOf(_ebx.transaction, db.findTable<master.Sample>(...))` **probe** (targeting the dataspace's
  own EXISTING `Master` dataset by explicit name, since `DistribTest_Master` has no second dataset yet to
  prove genuine cross-*dataset* reach) was saved via `update_script` on the real
  `DistribTest_Master/root/Sample/@trigger/DistributionAfterCreate` script and compiled **clean**
  (`validation.isSuccess: true`, `errors: []`). This establishes that "no `execute transaction`, write
  directly through `_ebx.transaction` into a table reached via `db.findDataset(_ebx.dataspace, name)`" is
  accepted by the compiler for a table trigger.
- The REAL final body — identical to what `DistributionTriggerBody.body(...)` now generates, including
  `references model "DistributionOutbox" from module "ebx-mdm-factory" as outbox;` — was also saved via
  `update_script` on the same script. It answered exactly **one** error:
  `Packaged model 'DistributionOutbox' not found in module 'ebx-mdm-factory'` (line 6). This is the expected,
  single remaining blocker: `DistributionOutbox.xsd` is new in this pass and the module has not been
  rebuilt/redeployed (§12's Tomcat-restart rule). Every other part of the body — the FK fix, the
  `_ebx.transaction`-only write, the outbox dataset lookup, `db.findTable<outbox.Event>`,
  `db.recordOf`/`saveRecord` — compiled without complaint once the packaged-model reference is set aside;
  the single isolated error is strong evidence the design is otherwise sound.
- **Not verified, and cannot be without deploying**: whether `db.findDataset(_ebx.dataspace,
  'DistributionOutbox')` and the typed `outbox.Event` write actually succeed at RUNTIME once the model is
  packaged — `DistribTest_Master` has no second dataset today, and creating one plus firing the trigger by
  creating a `Sample` record were both outside this pass' single-script write permission. **Exact steps to
  close this out after redeploying**: (1) restart Tomcat so the new `DistributionOutbox.xsd` is packaged;
  (2) call `POST .../distribution/targets/{id}/install-triggers` on `DistribTest` again — this both creates
  the `DistributionOutbox` dataset in `DistribTest_Master` (via `DistributionOutboxInstaller.ensure`, if it
  is not there yet) and republishes the triggers with the real `outbox.Event` reference, which should now
  resolve; (3) create one `Sample` record with the target `ARMED`; (4) inspect
  `DistribTest_Master`/`DistributionOutbox`/`/root/Event` directly (Data Services or `data-access`) for a
  new `PENDING` row; (5) wait one dispatcher pass (10s) and confirm the row is gone and a `SENT`/`FAILED`
  row appeared in `MdmFactory`'s `DistributionEvent` history table instead.

**`DistributionEvent` in MdmFactory: kept, repurposed as pure terminal history/audit — never drained
again.** Justification: it is exactly what the wizard's step-8 "Distribute" card, the Cockpit's events list
and the REST `GET .../distribution/events` route already read (`DistributionReader.listEvents`,
`rest.FactoryResource`) — removing it would mean rebuilding that whole read surface against a
per-project dataset instead of one central table, for no benefit (the events list is explicitly a
cross-project Cockpit concern, so a single MdmFactory-side table is the right shape for it; the outbox, by
contrast, has to be per-project because a table trigger cannot reach MdmFactory at all). `ebx.
DistributionDispatcher` now writes ONE history row per TERMINAL outcome only (`SENT`, `SKIPPED`, or
`FAILED` past `RetryBackoff.MAX_ATTEMPTS`) — a failed attempt still under the retry budget updates the
OUTBOX row in place (status stays `PENDING`, `attempts`/`nextAttemptAt` advanced) and writes no history yet.
`DistributionReader.replayEvents` changed to match: since nothing reads `DistributionEvent.status` any more,
flipping a history row back to `PENDING` would be a silent no-op; replay now inserts a **fresh** `PENDING`
row into the project's own outbox dataset instead (same target/tablePath/recordKey/change/survivorKey), and
leaves the original history row as the record of the earlier failure.

**Configure: stale mapping on a changed connector/operation, now cleaned up.** Live incident on
`DistribTest`: target 1 moved from connector `Kafka` to `KafkaMdmDistribTest`; the next "Configure" created
mapping 302 and left mapping 300 (on the old connector, still `active`) dangling — because
`connectors_mapping_upsert` identifies a mapping by `(connector, operation, direction, dataset, tablePath,
label)` TOGETHER, and this class' label is keyed by `targetId`+`tablePath` only, so a connector/operation
change makes the next upsert create a brand-new mapping under a different identity rather than update the
old one. `DistributionConfigurator.deleteIfConnectorOrOperationChanged` now reads each table's existing
mapping (`connectors_mapping_get`) before re-upserting it and deletes it first (`connectors_mapping_delete`)
when its stored `connector`/`operation` no longer matches the target's current ones — **not verified live**:
`connectors_mapping_get`'s top-level `connector`/`operation` field names were inferred from the same
response shape `readExistingRules` already reads `rules`/`inputs`/`children` from, never independently
confirmed.

**Deployment note (unchanged rule, restated because this pass adds a new packaged XSD)**: `DistributionOutbox.xsd`
is a new file under `WEB-INF/ebx/schemas/` — like any module.xml/schema change, it needs a full Tomcat
restart before `references model "DistributionOutbox" from module "ebx-mdm-factory"` resolves anywhere
(CLAUDE.md §9/§10). Nothing in this pass was deployed or installed (`tools/install.sh` was not run).

## Round 4 (2026-09-26): live findings on DistribTest, and the "false success" fix

Real-time chain confirmed working end to end on `DistribTest` (master dataspace `DistribTest_Master`,
dataset `Master`, table `/root/Sample`; outbox dataset `DistributionOutbox` in the same dataspace; target 1
= connector `KafkaMdmDistribTest` operation `publish`, REALTIME, ARMED by administrator override; Connectors
mapping 302): Install triggers declared/authored/published all 3 script triggers; editing record id 1
(title) wrote a `MODIFY`/`PENDING` outbox row through the trigger's own `_ebx.transaction`; roughly 20
seconds later the dispatcher drained it, deleted the outbox row and wrote a `DistributionEvent` history row;
Connectors ran the publish. This is the first live confirmation of the whole path described in the "Live
verification actually performed" section above.

### 1. BLOCKER fixed: `connectors_sync` false success

**Symptom, measured live**: the target Kafka broker was down. `connectors_log_search` (mapping 302) showed
the real outcome: `status: ERROR, message: "Kafka publish to mdm.distribtest.person failed:
org.apache.kafka.common.errors.TimeoutException: Topic mdm.distribtest.person not present in metadata after
10000 ms.", runId: R20260926-133426-0004`. The Factory's own `DistributionEvent` row for that same send read
`status SENT, attempts 1, message null` - a clean false success.

**Root cause**: `DistributionRunner#send` and `DistributionDispatcher#drainGroup` both decided success/
failure purely from `McpClient.ToolResult#error` - the MCP tool call's own `isError` flag. Reading
`ebx-connectors`' source (`ConnectorsTools`/`McpEndpoint`/`SyncResult`, read-only, this session made no
Connectors changes) shows that flag is set from `data.has("error")` on the CALL's own JSON, which is only
true when the tool method itself THREW (`ConnectorException`/`IllegalArgumentException`/any `Exception`).
An ordinary `connectors_sync` run that reaches the target and fails THERE (a downed broker, a rejected
write) does not throw - `ConnectorService.get().syncRecord`/`sendScope` catch that failure into a
`SyncResult` with `Status.ERROR` and Connectors returns a completely normal, non-error JSON-RPC result whose
`structuredContent` carries the real outcome in `results[].status`/`errors[]` and the aggregate `ok`/
`errors`/`failed`/`partial`/`skipped`/`noData`/`status` fields `ConnectorsTools#putOutcome` computes. This
applies to BOTH `connectors_sync` answer shapes this module calls: `ConnectorsTools#results` (a scope batch
send, `DistributionRunner#send`, one call per mapping/table) and `ConnectorsTools#enrich` (a single record,
no `scope` - what `DistributionDispatcher#sendRecord` sends for a real-time event).

**Fix**: a new pure class, `distribution.SyncOutcome`, is now the ONLY place that decides whether a
`connectors_sync` call actually succeeded. It reads the top-level `errors` counter (zero = ok, matching the
literal JSON shapes read off `ebx-connectors`' source and reproduced in `SyncOutcomeTest`, including the
EXACT live-measured Kafka timeout message as a regression fixture) and, on failure, builds a message from
the failing `results[]` items' own `message`/`errors[]` text - never re-worded, and the `runId` is always
kept so a failure can be traced back to `connectors_log_search`/`connectors_replay`. Both
`DistributionRunner#send` (full/changed batch sends - now reports genuine per-table `ok`/`error`/`rows` and
never marks a first full send `DONE` when any mapping had errors, so `sendFirstFull` can no longer ARM a
target after a send that actually failed) and `DistributionDispatcher#drainGroup`'s non-retire branch (the
real-time per-record send, feeding `finishOrRetryFailed`'s retry/backoff) now go through `SyncOutcome.read`
instead of `ToolResult.error` alone. The RETIRE path (`sendRetireNotice`, `connectors_call` with
`allowWrite=true`) is UNCHANGED and deliberately excluded: that tool calls the connector directly, so a
broker/target failure DOES throw there and `ToolResult.error` is already the correct signal - confirmed by
reading `ConnectorsTools#call`, which has no `SyncResult` wrapping around `connector.execute(...)`.

Not verified live (no write access to the instance this session; Kafka broker state not under this
session's control): the fix's `SyncOutcomeTest` fixtures reproduce the exact live-measured JSON message and
the documented `ConnectorsTools`/`SyncResult` shapes read from source, but a fresh live run against a
downed broker to confirm the corrected `DistributionEvent`/target-state outcome was not repeated this
session - recommended as the first live check after redeploying this round's fix.

### 2. Fixed: "landing loaded from every source" checklist stuck unticked

**Symptom, measured live**: DistribTest's landing genuinely loaded (`PipelineStep LANDING_LOAD DONE "Loaded
1 landing table(s)"`, 180 rows), but `DistributionRunner#everySourceLoaded` required
`ProjectSource.status == "LOADED"` and the source (id 20) stayed `GENERATED` - the checklist item never
ticked.

**Fix**: a new pure class, `landing.LandingReadiness`, holds the ONE shared "every selected table is
loaded" rule; `wizard.WizardRules#allTickedTablesLoaded` (which already gated the Staging step's
reachability on exactly this fact, reading real `LandingTable.status` per table rather than the source's own
rolled-up status) now delegates to it, and `DistributionRunner#everySourceLoaded` was rewritten to read the
same per-table evidence (`LandingTable.selected`/`LandingTable.status` for every table of every
`ProjectSource` of the project) through the SAME shared class - not a third, independent rule.

### 3. Fixed: arming gate blocked forever when no matching plan exists

**Symptom**: DistribTest has one source; the rule pass skipped its entity for lack of a validated identifier
and the language-model pass skipped it too, so NO `MatchPlan` row was ever written. The arming gate's
"first matching run finished" requirement could then never be satisfied.

**Fix**: `DistributionArmingGate.Readiness` gained a fourth fact, `noMatchingPlanned` (true when the project
has zero `MatchPlan` rows across every promoted `StagingEntity` - `DistributionRunner#noMatchingPlanned`),
and a `matchingSatisfied()` method (`firstMatchingRunFinished || noMatchingPlanned`) that `canSendFirstFull`/
`pipelineReady` now check instead of `firstMatchingRunFinished` alone. The 3-arg `Readiness` constructor is
kept (defaults `noMatchingPlanned` to `false`, i.e. the old, stricter behaviour) so no other caller needed to
change. The wizard's 7b checklist shows a different line, "First matching run finished - or no matching
planned for this project" (`wizard.step7dist.arming.matching.none`), rendered as done when
`noMatchingPlanned` is true, whatever `firstMatchingRunFinished` says. The admin override is unchanged and
still available regardless.

### 4. Added: "Test the connection" button on 7b, with a documented limitation

`connectors_describe` - the same read-only check `ebx.SourceOnboarder#describeInto` already uses during
source onboarding - is now reachable from the target's own 7b setup panel
(`DISTRIBUTION_TARGET_TEST_CONNECTION` -> `DistributionConnections#testConnectionForTarget` ->
`#testConnection`), reporting whether the target's connector is found, active, and still has the operation
the target names.

**Documented limitation, live-measured**: `connectors_describe` only reads the connector's OWN stored
configuration - it never opens a network connection to the target. Live-measured the same day: it answered
cleanly on `KafkaMdmDistribTest` while the broker was actually down (the same incident behind item 1's
`connectors_sync` failure). No generic read-only Kafka probe exists in `ebx-connectors` to close that gap -
building one would mean actually writing to or reading from the topic, out of this module's scope ("do NOT
change Connectors"), so this was intentionally skipped rather than half-built. The button still catches
every OTHER failure a broken target can have (connector deleted, disabled, or missing the named operation);
its own success message says explicitly that it cannot confirm the target itself is reachable.

### Tests

New pure-class tests, run via `tools/test_local.sh`: `distribution.SyncOutcomeTest` (the exact live-measured
Kafka timeout JSON as a regression fixture, plus clean/partial/skipped/transport-error cases for both
`connectors_sync` answer shapes), `landing.LandingReadinessTest` (selected/unselected/partially-loaded
cases). `distribution.DistributionArmingGateTest` gained two cases for `noMatchingPlanned`. No EBX instance
write access this session (read-only: `data-access`/`connectors_log_search`/`connectors_mapping_get`/
`connectors_describe` reads only) - nothing in this round was deployed or installed.

### Round 4 live verification, recorded here (2026-09-26, worktree `ebx-mdm-factory-distribution-live-5`)

The round 4 pass above was written before its own live run finished; the actual numbers, for the record:
first full send on `DistribTest` moved **180 rows / 180 distinct keys** onto topic
`mdm.distribtest.person`. With the broker taken down, the next real-time send for a modified record went
`PENDING` carrying Connectors' own failure message and `runId` (the "false success" bug fixed above meant
this used to read a clean `SENT` instead). With the broker back, the same outbox row was retried and went
`SENT` on **attempt 3**, its message landing on the topic at **offset 182**.

## Round 5 (2026-09-26) - checklist honesty, cross-retry coalescing, retry cap

Fixes from a further live pass on `DistribTest` (target 1 = Kafka connector `KafkaMdmDistribTest`, REALTIME;
outbox dataset `DistributionOutbox`/`root/Event` in `DistribTest_Master`; history `DistributionEvent` in
MdmFactory), worktree `ebx-mdm-factory-distribution-live-5`, branch `fix/distribution-live-5`. No EBX
instance write access this session either - every change below is a Java-only fix plus pure-class tests
(`tools/test_local.sh`); nothing was deployed or installed.

1. **7b checklist: "First full send done" no longer ticks for an override-armed target.** Symptom: a target
   armed through the admin override (`armedViaOverride=true`, no `firstFullSendRunId`) showed the same green
   tick on "First full send done" as a target that had genuinely run one, because the line's condition was
   `state == ARMED` alone - true for both paths (`DistributionArmingGate.stateForOverride()` and
   `.stateAfterFirstFullSend(true)` both return `ARMED`). Fixed: `WizardPage.DistributionTargetRow` gained a
   `firstFullSendRunId` field (parsed in `ProjectWizardUserService.targetRow`, from the same JSON key
   `DistributionReader.listTargets` already writes - this field existed in the API response the whole time,
   just never reached the wizard's row model), and the checklist line now ticks on
   `!firstFullSendRunId.isEmpty()`, never on `state` alone. An override-armed target with no run id instead
   renders a third, distinct state - "Armed without a first full send" (`wizard.step7dist.arming.firstFullSend.override`,
   en/fr with the typographic apostrophe in `l'option`), styled with the same amber `mdmf-sc-needsPerson`
   class the checklist already uses for "needs a person", not the green `mdmf-sc-done` tick nor the plain grey
   `mdmf-sc-waiting`. A target that DID run a real first full send still ticks the plain line regardless of
   whether `armedViaOverride` also happens to be set (a person can look at a fully-armed target's history and
   see it succeeded on its own merits). Tests: `WizardPageTest.step7DistributionArmingChecklistTicksOnlyOnRealFirstFullSend`
   (override/no-run-id, real-run-id, and never-armed/waiting cases).

2. **Cross-retry coalescing: a fresh send now closes every other stale PENDING row for the same key,
   including one sitting in retry backoff.** Live symptom: outbox rows 4 and 5 were both `PENDING` for the
   same `(target, tablePath, recordKey)` - row 4 at attempt 7, backed off until 22:16, row 5 a fresh event.
   Row 5 was sent (the dispatcher's own per-row `nextAttemptAt` filter in `DistributionDispatcher#drainTarget`
   already keeps a due row from waiting on an older row's backoff - **that half of the requirement was
   already correct, confirmed by re-reading the code, not changed**), but row 4 was left behind to wake up
   later and re-send the exact state row 5 had just delivered - pure duplicate traffic, and worse for a
   `RETIRE`/`DELETE` mix it would have been outright wrong (it isn't, for the reason below). Fixed by
   extending the pure `EventCoalescer` with `idsSupersededBySend(sentChange, sentSequence,
   otherPendingEvents)`: given the change that was JUST sent and every OTHER still-pending event for the same
   key (the caller queries with NO `nextAttemptAt` filter, deliberately including backed-off rows), it returns
   the ids to close as superseded - a pending `CREATE`/`MODIFY` at or before the sent sequence, and ONLY when
   the send itself was a `CREATE`/`MODIFY` (never when the send was a `DELETE`/`RETIRE`, and a pending
   `DELETE`/`RETIRE` is never touched by this rule either way - each already has its own correct handling:
   `masterRecordExists` resolves a stale `DELETE` on its own next attempt, and `RetireRules` must always run
   for a `RETIRE`). Wired into `DistributionDispatcher.finishSent` (new `closeSupersededRows`, called only
   when the sent change is `CREATE`/`MODIFY`): it queries every other `PENDING` row of the same
   `(target, tablePath, recordKey)`, hands the qualifying ones to `idsSupersededBySend`, writes one `SKIPPED`
   history row per superseded id with message `"superseded by event <sent outbox row id>"` (a dedicated
   history row per id, not folded into the sent row's own entry - chosen so the audit trail still shows how
   many events actually arrived versus how many were sent, the same shape `finishSkipped` already uses for
   every other skip reason), then deletes those outbox rows. Tests:
   `EventCoalescerTest.supersededBySendClosesOlderModifyInBackoff` (the exact row-4/row-5 shape),
   `.supersededBySendNeverClosesDeleteOrRetire`, `.supersededBySendItselfIsDeleteOrRetireClosesNothing`,
   `.supersededBySendIgnoresLaterSequence`.

3. **Retry delay capped at 5 minutes, not 1 hour.** Live symptom: an outbox row already at attempt 7 carried
   a `nextAttemptAt` roughly 33 minutes after its previous attempt (`RetryBackoff`'s exponential delay,
   `BASE_SECONDS=15` doubling per attempt, was only capped at `MAX_DELAY_SECONDS=3600`) - too long a wait for
   a target that is often back within a couple of minutes, compounded by the dispatcher's own 10-second poll
   period. Fixed: `MAX_DELAY_SECONDS` lowered to `300` (5 minutes); `BASE_SECONDS` and `MAX_ATTEMPTS` (still 8)
   are unchanged and stay their own named constants - the retry BUDGET (how many attempts before
   `RetryBackoff.exhausted`/manual replay from the Cockpit) is a separate knob from the per-attempt delay cap,
   and nothing in this incident suggested 8 attempts was the wrong number, only that the tail of the curve
   grew too slow. Tests: `RetryBackoffTest.delayIsCappedAtFiveMinutes` (asserts the cap is exactly 300s and
   that attempt 7 - the live-measured one - already sits at the cap, not ~33 minutes),
   `.maxAttemptsBudgetUnchanged`; the pre-existing `delayIsCapped` was updated to the new 300s ceiling.

`tools/test_local.sh`: green after every change in this round, including the four new/expanded pure-class
tests above. No EBX instance was reachable this session (this repo has no git remote/instance access from
this worktree) - every fix here is Java-plus-tests only; the live-verification checklist in §8, plus a
dedicated re-run of the exact row-4/row-5 backoff scenario on `DistribTest`, remains the next live step.

## Round 6 (2026-09-26/27) - audit fix: coalesced-away rows silently deleted with no history

Fixes measured live on `DistribTest` (23:07) and `Personnes` (Match and Merge active on `/root/Personne`),
worktree `ebx-mdm-factory-distribution-live-6`, branch `fix/distribution-live-6`. No EBX instance write access
this session either - every fix below is Java-plus-tests only (`tools/test_local.sh`); nothing deployed or
installed.

### 1. BUG fixed: a same-pass coalesced-away outbox row left NO history row at all

**Root cause**: `DistributionDispatcher.drainGroup` used to call `EventCoalescer.coalesce(pEvents)` and
immediately `deleteOutboxRows(coalesced.idsToSkip())` - BEFORE the survivor (`coalesced.survivingId`) had even
been attempted, with a comment claiming these were "internal bookkeeping, never a real send attempt: delete
outright, no history row". That reasoning was wrong for the exact live shape row 5's own `closeSupersededRows`
(cross-pass superseding, round 5) already handles correctly: outbox row 6 (record 3, MODIFY) had failed once
and was `PENDING` in backoff; a second edit created row 7 (record 3, MODIFY) at 23:07:46. By the time the
dispatcher's next pass ran, row 6's own `nextAttemptAt` backoff (short at attempt 1 - `RetryBackoff`) had
already expired, so `drainTarget`'s `PENDING`-and-due query picked up BOTH rows into the SAME group - unlike
the round-5 scenario (row 4 still in backoff, excluded from the group, only caught by the cross-pass check).
`EventCoalescer.coalesce` correctly nets MODIFY+MODIFY to MODIFY with row 7 surviving, but the old code deleted
row 6 as part of `idsToSkip()` right there, before row 7's send was even attempted - and unconditionally, with
no history row. Row 7 was later sent fine (one message, correct state) - the bug was entirely about row 6's
own trace vanishing.

**Fix**: the two superseding paths - the same-pass `coalesce().idsToSkip()` and the cross-pass
`EventCoalescer.idsSupersededBySend` (round 5) - now go through ONE shared decision rule and ONE shared closer
(task brief: "prefer one code path for both cases"):

- `EventCoalescer.supersededMessage(representativeId, outcome)` (new, pure, unit-tested) decides the SKIPPED
  history message for an id folded into a representative, given the representative's own terminal outcome:
  `OUTCOME_SENT`/`OUTCOME_SKIPPED` -&gt; `"superseded by event <id>"` (unchanged wording); `OUTCOME_FAILED_EXHAUSTED`
  -&gt; `"superseded by event <id> (failed)"` (the folded ids share the representative's own exhausted fate rather
  than being left to silently revive); `OUTCOME_FAILED_RETRY` -&gt; `null`, meaning "not terminal yet, do nothing" -
  the folded ids are left exactly as they were, still `PENDING`, and are re-coalesced (or re-queried, for the
  cross-pass case) on the next pass once the representative's own fate is actually decided. This was the
  simplest correct rule considered for the "FAILED" case in the task brief ("use ... (failed) or keep them
  PENDING-linked"): staying PENDING while the representative retries, then getting one clean terminal message
  either way, needs no extra state and never risks writing off a folded id over a failure that might still
  resolve on a later attempt.
- `EventCoalescer.NETTED_TO_NONE_MESSAGE` covers the one case with NO representative at all: a burst that nets
  to `NONE` (e.g. `CREATE`+`DELETE` - nothing was ever going to be sent). This is itself a terminal outcome
  (there is nothing left to decide), so every id in the burst is closed immediately with this message.
- `ebx.DistributionDispatcher.closeSupersededIds` is the ONE method that turns a list of superseded ids plus a
  representative id/outcome into history rows (one SKIPPED row per id, via the new shared `writeFoldedHistory`)
  then deletes the outbox rows - used by `finishSent`/`finishSkipped`/`finishOrRetryFailed` for the SAME-PASS
  `coalesce().idsToSkip()` (`foldedIds`, threaded through `drainGroup` alongside a `Map&lt;id,change&gt;` built once
  from `pEvents`) and by the rewritten `closeCrossPassSuperseded` (was `closeSupersededRows`) for the CROSS-PASS
  `idsSupersededBySend` ids. `drainGroup` no longer deletes anything before the representative's send is even
  attempted - the `NONE` case is the only one still closed immediately, since it has no representative to wait
  for in the first place.
- **`createdAt` preserved**: `writeHistory` gained a `pCreatedAt` parameter - the representative's own history
  row still uses `new Date()` (when the outcome was decided, unchanged), but every FOLDED id's history row now
  carries that id's own ORIGINAL outbox `createdAt` (read via the new `createdAtOf`, before the row is deleted)
  instead of "now" - so the audit trail says when the underlying change actually happened, not when it was
  cleaned up. This was lost entirely before this fix (there was no history row to carry it).

Tests (`EventCoalescerTest`, all pure): `supersededMessageOnSentNamesTheRepresentative`,
`supersededMessageOnSkippedNamesTheRepresentative`, `supersededMessageOnFailedExhaustedSaysSo`,
`supersededMessageOnFailedRetryIsNullSoNothingIsClosedYet` (the core timing fix - asserts `null`, i.e. "leave
PENDING, don't close yet"), `nettedToNoneEveryIdIsSkippedWithTheSameMessage`. Not verified live this round (no
write access): the exact row-6/row-7 shape should be re-run on `DistribTest` after deploying this fix -
edit a record twice in quick succession with a target `ARMED` and confirm BOTH the `SENT` history row for the
surviving send AND a `SKIPPED "superseded by event <id>"` history row for the folded-away one appear, with the
folded row's `createdAt` matching when it was originally queued, not when the pass ran.

### 2. Fixed: "Install triggers" on a project's FIRST ever install (Personnes, PipelineStep 509)

**Symptom, measured live**: `update_script` on a JUST-declared trigger (`/root/Personne` `afterCreate`)
answered `INVALID_DATA_MODEL "Revision history for the data-model script could not be loaded: the data-model
script record is unavailable."`, even though `list_scripts` already listed the script and `get_script` returned
its metadata (a real `revisionId`). `compile_data_model` on `Personnes_Master` was checked as an alternative
cause and ruled out: it answers `compiled:true` with one pre-existing, TOLERATED error
(`interInstanceLink.schema.container.notExist` on `/root/Personne/Association_Reverse` - the add-on's own
metadataset association, unrelated to this module).

**Root cause**: on `DistribTest`, install had always worked because an EARLIER failed run had already declared
AND published the triggers before the current single-publish-at-the-end design was even written, so authoring
always ran against an already-published model. Personnes hit the true, previously-untested order requirement:
a data-model script's revision history is only backed by a PUBLISHED model - a script declared but never
published has no revision history yet for `update_script`'s embedded `validate_saved` to load, no matter that
`list_scripts`/`get_script` already see the (unpublished) declaration.

**Fix**: `DistributionTriggerInstaller.install` now runs in two explicit passes. Pass 1 (`declareOne`) declares
every trigger of every table, probing via a cheap `get_script(guessedPath)` call BEFORE each declaration
(`scriptAlreadyExists`) to tell a genuinely NEW declaration from a plain re-run of an already-installed,
already-published one. If (and only if) at least one trigger was newly declared, a `publish_data_model` runs
right there (`declarePublishOk`/`declarePublishSkipped` in the response detail) - a first-ever install, or a
new table added to an already-installed project, always gets this middle publish; a plain re-run of an
unchanged, already-installed project skips it (both `set_script_trigger` calls per re-run stay idempotent
no-ops, so no publish is needed to reach a stable state). Pass 2 (`authorOne`) then resolves the real script
path, reads it, authors the body and validates - now always AFTER the model has been published at least once
with the trigger declared. If authoring still fails after this, the exception message says explicitly that
this is a genuine authoring failure, not the first-install ordering issue.

Not verified live this round (no write access): re-run `install-triggers` on `Personnes` after deploying this
fix and confirm all three triggers author/validate clean; also confirm a SECOND run (no new tables) reports
`declarePublishSkipped: true`.

### 3. Fixed: `hasLinkedFieldState`/`hasGoldenId` always false via the DMA model - RETIRE branch never generated where it matters

**Symptom, measured live**: `install-triggers` reported `hasLinkedFieldState: false, hasGoldenId: false` for
`/root/Personne` even though Match and Merge was demonstrably active there (data-access: 117 rows with
`./Linked_Field_HasSuspects = true` on `Personnes_Master`/`Master`).

**Root cause**: `Linked_Field_State`/`GoldenId`/etc are added by the add-on to the master DATASET's own schema
at model-LOAD time (CLAUDE.md sec 8: "the business table itself exposes Linked_Field_State/GroupId/GoldenId/...
on every row") - they never exist on the DMA MODEL itself, which is all `describe_table` (the data-model
server) can see. So the old check could never detect them on ANY project, active matching or not - it wasn't a
Personnes-specific bug, just never noticed on `DistribTest` because that project has no matching configured on
its table at all (so `false` was also the correct answer there, by coincidence).

**Fix**: `DistributionTriggerInstaller.detectMameFields` reads the master DATASET's own schema directly in
Java - `AdaptationTable.getTableOccurrenceRootNode().getNode(Path.parse("./Linked_Field_State"))`/`"./GoldenId"`,
the exact same existence-check pattern `GoldenRecordsReader`/`StagingLoader`/`DeducedScanCatalogWriter` already
use elsewhere in this module (CLAUDE.md sec 9: `Adaptation.get(path)` throws on an undeclared path, it does not
return null - `getNode` is the safe existence check). `install()` now resolves the project's master DATASET
(`Project.masterDataset`, same `AdaptationHome.findAdaptationOrNull` pattern `DistributionDispatcher.drainTarget`
already uses) once, up front, and `describeTable` calls the new detector instead of reading these two flags off
`describe_table`'s answer (which still supplies the primary-key field names, unchanged - that part genuinely
does live on the DMA model).

**Whether a typed `_ebx.record.Linked_Field_State` reference compiles in a trigger script**: not verified this
round (no write access to author/validate a real trigger against a table with these fields present) - the
generated body already reads it defensively (`'' | _ebx.record.Linked_Field_State`, `DistributionTriggerBody`'s
own existing design, unchanged this round) specifically because the field's real declared type was never
confirmed live; if a live `validate_saved` on `Personnes` after this fix's deployment shows the typed reference
does NOT compile, the fallback the task brief suggested - reading the state from the DISPATCHER side via
`MetadataHelper.getState`/`getGroupId` when draining a MODIFY event, turning it into RETIRE there instead of in
the trigger - remains the documented alternative, not yet implemented (the generic `'' | value` string read is
expected to work per CLAUDE.md sec 9's own note that `Adaptation.get` on a declared field of unconfirmed type is
otherwise unremarkable, but this is a "verify after deploying" item, not a settled fact).

### 4. Added (task brief item C): one Kafka topic per master table, not one shared connector-level destination

**Symptom, measured live**: target 2 on `Personnes` (connector `KafkaMdmPersonnes`) sent BOTH `/root/Personne`
(mapping 314) and `/root/Adresse` (mapping 316) to the SAME topic `mdm.personnes.person`, both keyed by `id` -
records of two different tables mixed together on one topic. Read-only in `ebx-connectors`
(`KafkaConnector#publish`, ~line 197-230): the operation already accepts a PER-CALL destination as a mapping
input - `topic` (else the connector's own `topic` property) and `key` (else the connector's own `key.field`
property, a JSON path) - so no Connectors change was needed, only the Factory had to start passing one.

**Fix**:

- New field `DistributionTarget.tableDestinations` (XSD change - **needs a Tomcat restart** before it resolves
  anywhere, same rule as every other schema change in this module, CLAUDE.md sec 9/10): one
  `"tablePath=destination"` pair per master table, comma-separated, same encoding discipline as
  `connectorsMappingIds` - parsed/formatted with the EXISTING `distribution.TargetMappingList`, never a new
  hand-rolled splitter.
- `distribution.TableDestinationDefaults.proposeDestination(connectorTopicProperty, projectCode, tablePath)`
  (new, pure, unit-tested): `<connector's own topic property, or the project code lower-cased when it has
  none>.<table name lower-cased>` - e.g. a Kafka connector whose own `topic` property is `mdm.personnes`
  proposes `mdm.personnes.personne` for `/root/Personne` and `mdm.personnes.adresse` for `/root/Adresse`. This
  is only ever a STARTING POINT for a table that has no stored destination yet - a value already stored (typed
  by hand, or proposed by an earlier Configure) is never re-applied or overwritten.
- `DistributionConfigurator.configure` now calls `connectors_describe(connector)` once
  (`describeConnectorForDestinations`) to read the connector's `type` and `properties.topic`. Only when
  `type` equals `kafka` (case-INsensitive; kept to this one confirmed-supported type deliberately - "generic
  enough for a future JMS queue, but do not build JMS" reads as "don't hardcode Kafka assumptions elsewhere",
  not "build a second connector type now") does each table get a destination (stored value, or the proposed
  default) written into the mapping's `inputs.topic`, with `inputs.key` set to `"id"` (`DEFAULT_KEY_FIELD` -
  every master table this module builds carries this field, CLAUDE.md sec 9/`master.MasterGenerator`; a second
  `describe_table` call purely to re-derive it was not worth adding). The merged per-table map is written back
  to `DistributionTarget.tableDestinations` after Configure, same idempotent re-Configure pattern
  `connectorsMappingIds` already follows.
- 7b (`WizardPage.renderDistributionTableDestinations`, new): for a `kafka`-type connector, one editable
  "Table -&gt; topic" row per master table (`DistributionModel.masterTables`, populated in
  `ProjectWizardUserService.buildDistribution` from the same `MasterModel.builtEntities`/`tablePath` computation
  `DistributionConfigurator` already uses), prefilled from the target's own stored `tableDestinations` or the
  proposed default. Posted as parallel `mdmfDistTopicTable`/`mdmfDistTopicValue` arrays (same shape
  `mdmfProductTableChecked` already uses), zipped back into a map on Save
  (`ProjectWizardUserService`'s `Posted.distTableDestinations`) and written through
  `DistributionReader.upsertTarget`'s new partial `tableDestinations` field (present only when the panel
  actually rendered these rows - absent for any non-Kafka target, so its `tableDestinations` is never wiped).
  The OLD single-line connector-level "Sends to" hint (`renderDistributionDestinationHint`) is suppressed for a
  `kafka`-type connector (replaced by the per-table rows) and unchanged for every other connector.
  **Topic creation is explicitly out of scope** - the note under the rows says plainly that the topic must
  already exist on the broker, or the broker must be configured to auto-create it; this module never calls a
  topic-admin API.

Tests: `TableDestinationDefaultsTest` (pure default-naming rules: connector-topic-as-prefix, project-code
fallback, lower-casing, last-path-segment-only, blank-vs-null connector topic), `WizardPageTest
.step7DistributionPerTableDestinationsOnlyForKafkaConnectorType` (per-table rows only for `type=kafka`, stored
value wins over the default, the old single-line hint is suppressed for kafka and unchanged for an
untyped/other connector). Not verified live this round (no write access): `python3 tools/build_xsd.py` was
re-run and the committed XSD matches (confirmed by `tools/test_local.sh`'s own diff check); after redeploying
(Tomcat restart required for the new field), re-run `Configure` on a Kafka target with two or more master
tables and confirm two distinct topics are proposed/used (`connectors_mapping_get` on each table's mapping
should show a different `inputs.topic`), and that Personnes' own `/root/Personne`/`/root/Adresse` mappings stop
sharing one topic once reconfigured.

### 5. Investigated (task brief item D): "Test the connection" still cannot reach the real broker - confirmed no reachable entry point, no code change

**Read-only investigation of `ebx-connectors`** (no Connectors changes made, per the task brief and this
module's own standing rule): `KafkaConnector.testConnection` (~line 127-140) DOES exist and genuinely checks
the broker (`AdminClient.listTopics()`, read-only Kafka metadata) - but it is a method of the `Connector`
SPI interface, only ever invoked from `com.on.ebx.connect.core.ConnectorConnectionStateService` and
`com.on.ebx.connect.ebx.ui.ConnectorDiagnosticsAdapter`/`ConnectorDiagnosticsUserService` - a UI-only diagnostics
`UserService`, package `com.on.ebx.connect.ebx.ui`. Checked every other surface this module can reach:

- `ConnectorsTools` (the MCP tool surface `connectors_*` - the same JSON-RPC the in-JVM bridge `ConnectorsBridge`
  forwards to) declares no `connectors_test_connection`-style tool at all - grepped every `T_*` constant
  (`T_LIST`, `T_DESCRIBE`, `T_CALL`, `T_SYNC`, ... 20 tools total), none call `testConnection`.
  `DistributionConnections.testConnection` (this module's OWN existing 7b button, task brief round 4 item 4)
  already documents exactly this: `connectors_describe` only reads the connector's stored CONFIGURATION, never
  opens a network connection - confirmed again this round, not a new finding.
- No REST route exists for it either (`AbstractRestConnector.testConnection` is a different, unrelated generic
  HTTP-connector-base-class method of the same SPI name, not a route).
- The in-JVM bridge (`ConnectorsBridge`, `com.on.ebx.connectors:type=InJvmMcp`) only answers the SAME JSON-RPC
  messages `ConnectorsTools` recognizes - it is a transport, not a wider API surface, so it cannot reach
  `testConnection` either. And even if it could: `ConnectorConnectionStateService`/`KafkaConnector` are
  Connectors' OWN classes, loaded by Connectors' OWN webapp classloader - the Factory module cannot import them
  directly regardless (CLAUDE.md sec 10's "only ebx-lib.jar classes are shared" - `ConnectorsBridge`'s own
  Javadoc says the same).

**Conclusion, per the task brief's own fallback instruction ("if no entry point is reachable without changing
Connectors, say so and stop there for D")**: no such entry point exists without adding one to `ebx-connectors`
(a new MCP tool or REST route wrapping `Connector#testConnection`), which is out of this module's scope. No
code change made for item D. `DistributionConnections.testConnection`'s existing Javadoc already states this
limitation accurately (added in round 4, re-confirmed unchanged by this investigation) - the button still
catches every failure that check CAN see (connector deleted, disabled, or missing the operation), it simply
cannot confirm the target itself is reachable, and nothing here makes that claim falsely.

`tools/test_local.sh`: green after every change in this round (items 1-4; item 5 made no code change). No EBX
instance write access this session (this repo has no git remote/instance access from this worktree) - every fix
above is Java-plus-tests only; the live-verification notes under each item are the next live steps.

## Round 7 (lot 8b, 2026-09-28) - the Control-row short-circuit, an outbox key decision, and the reset/delete jobs

Written on branch `feature/volume-distribution` (worktree `ebx-mdm-factory-volume-8b`), from
`integration/volume-wave1` `c2ef47f2`. Full writeup: `docs/VOLUME.md`'s own "Lot 8b" section - this is the
targeted update to keep this file's own trigger/outbox description in step, not a duplicate.

**Sec 5's "Every one" list above is now missing a step - corrected here rather than rewriting the whole list**:
before step 2 (the SQL query against `factory."DistributionTarget"`), the trigger now reads a `Control` row of
the SAME per-project `DistributionOutbox` dataset (a new table next to `Event`, PK `project`, fields
`armedRealtimeTargets`/`updatedAt`) by primary key - no SQL - and returns at once when it is missing or the
count is `0`. This closes the exact cost §6's own "Per-row query cost while a full reload is paused" bullet
named: a full reload paused with every REALTIME target `PAUSED` now costs one primary-key lookup per saved row,
not one cross-dataspace SQL query. The old SQL fallback (§5's steps 2-3, unchanged) still runs whenever the
short-circuit does not return early - an armed target really might exist. `ebx.DistributionControlRow`
maintains the row from the same four write paths §11's own `ArmedTargetCache` mention (lot 8a) already touched
(`DistributionPause.setState`, `DistributionRunner`'s `writeTargetState`, `DistributionReader.setState`/
`.upsertTarget`), plus an unconditional recompute at `DistributionTriggerInstaller.install` time and at module
startup - see `docs/VOLUME.md`'s own Lot 8b section for the exact invariant and why the two write-path calls
are asymmetric (bump-before-commit for arming, refresh-after-commit for disarming).

**§1's table list gains one row**: `DistributionOutbox.xsd` (not `MdmFactory.xsd` - it was already documented
as a separate packaged model in §11) now has TWO tables, `Event` (unchanged) and `Control` (new, this lot).

**§2's `EventCoalescer`/`ArmedTargetCache` bullets, an outbox-key question closed**: evaluated whether
`DistributionOutbox.Event`'s primary key could become a natural `(target, tablePath, recordKey)` instead of
the auto-increment `id`, to let the dispatcher read by key instead of a predicate scan. **Not viable**:
`EventCoalescer.coalesce`/`idsSupersededBySend` both exist precisely because several PENDING rows sharing that
exact triple legitimately coexist (within one drain pass and across passes) - a primary key cannot hold two
rows of the same value, so the key stays `id`. See `EventCoalescer`'s own class Javadoc (lot 8b) for the full
argument. §6's own drain-cost bullets are unaffected: the dispatcher already reads by `target`+`status` only,
capped at `DRAIN_BATCH_CAP` (lot 8a) - nothing here changes that.

**§3's `DistributionReader`/`DistributionRunner`/`DistributionPause` bullets** now also each maintain the
Control row around their own state-writing `Procedure` (`DistributionControlRow.beforeStateChange`/
`.afterStateChange`), and `DistributionTriggerInstaller.install` calls `DistributionControlRow.recomputeOne`
right after `DistributionOutboxInstaller.ensure` - both additions, not behavior changes to what those classes
already did.

**Wizard/REST reset and delete now run as jobs, not on the request thread** - see `docs/VOLUME.md`'s Lot 8b
section 3 for the full account (§7's REST table itself is unaffected: `/reset`, `/delete`, `/master/reset`
already answered `202`/job-enqueued since lot 8a - this lot only switched the WIZARD's own UI callers off the
synchronous entry points, plus a note that project deletion's async report is necessarily less detailed than
the old synchronous one, since a successful delete removes the very `PipelineStep` rows a richer report would
need to survive from).

**§4's `DistributionTaskBean` bullet**: now claims `FactoryJobs.DISTRIBUTION` like every sibling bean (lot 8a
left it as the one exception; closed this round, one-line fix in `executeScript`).

Not verified live (same posture as every round above): the short-circuit was checked against `get_syntax_guide`/
`get_api_help` only, never compiled; the write-path asymmetry was reasoned through, never raced against a live
repository; the wizard/delete-screen job wiring was never exercised in a browser. `tools/test_local.sh` green.

## A pause that outlives its load

A staging load (scope all), a master load and a matching run (scope all) pause the project's armed REALTIME targets while they run, and a master load also pauses the Match and Merge matching of the
master tables (`docs/MASTER-ZONE.md`, "Matching paused during a load"). Both undo it from an in-memory list in a `finally`; a stop of the JVM in between used to leave the target `PAUSED` and the
matching paused for ever, indistinguishable from a pause an administrator set on purpose.

- **The ledger.** Table `LoadPause` of the MdmFactory model (new, additive: no primary key or existing field changed, no foreign key so that deleting a project is never blocked by it).
  `ebx.LoadPauseLedger` writes one row BEFORE each pause call (kind `DISTRIBUTION_TARGET` with the target's key, or `MATCHING_TABLE` with the master table path; the step that paused - `STAGING_LOAD`,
  `MASTER_LOAD` or `MATCHING_RUN` -, the launcher, the time, and the token of the load) and deletes it once the resume succeeded. A pause nobody asked the factory for - a person's - never gets a row, so it can never be lifted by the recovery. If the row cannot be written the thing is NOT paused (said in the log and,
  for the matching, in the step's `matchingPause` detail): a pause that cannot be recovered is worse than a target that keeps sending during the load.
- **The recovery.** `ebx.LoadPauseRecovery`, started by `FactoryModuleRegistration` at repository start on a thread of its own (the match-merge server and Connectors are not up yet at that point):
  first pass after 30 s, a thing that cannot be read is retried every 60 s for 30 passes. A row whose owner token no live load of this JVM holds (`core.LoadPauseRule`) is an orphan: still `PAUSED` =
  lifted (a target is set back to `ARMED` and, as at the end of a load, a `changed` catch-up send is attempted up to 5 times; a table gets `pauseMatching: false`, its records written during the load are NOT
  matched - run the matching on the whole table), no longer paused or gone = only the row is deleted (a target somebody turned `DISABLED` is never re-armed). Each lift is a WARN in `kernel.log`.
- **A failed resume is visible.** At the end of a load, `DistributionPause.resumeAndReport` reads the result of `resume` (it used to be discarded): a target not re-armed, or re-armed but whose catch-up
  send failed, is an ERROR in the log and a "WARNING: ..." in front of the load's step message, with the detail under `distributionResume`. The ledger row of a target that could not be re-armed stays, so
  the recovery handles it at the next start; the same for the matching (the warning now says so). A recovery that cannot lift a pause says so as an ERROR with the exact remedy and on the interrupted
  step's message.
- **Not verified on an instance**: the decisions (`LoadPauseRuleTest`), the report wording (`DistributionResumeReportTest`) and the owner registry (`LoadPauseLedgerTest`) are unit-tested; the rows, the
  thread and the calls to the add-on and to Connectors at start need a running EBX.

## The mapping document always names the master dataspace

The OUTBOUND mapping document that `DistributionConfigurator.mappingDocument` builds for each master table carries `dataspace` (the project's `Project.masterDataspace`, e.g. `Vol100k_Master`) next to
`dataset` (`Master`) and `tablePath`. A dataset NAME is not unique across the repository - every project's master dataset is called `Master` - and a flow written through the Connectors contract
(`flows.put`, wave 11) is stored exactly as sent. Without a dataspace, Connectors resolves the name at send time by walking the repository and takes the first dataspace holding such a dataset with the
table: measured live on 2026-10-12 (FT 6.3 RC, mapping 415 "Distribution: target 3 /root/Personne"), it picked `Personnes_Master`, an orphan of a deleted project, instead of `Vol100k_Master`, and 300
rows of the wrong project reached Kafka (each reported PARTIAL, since five fields exist only in the right project's master). Before wave 11 the same omission was masked by the Connectors installer's
boot-time repair, which guessed a dataspace the same way. A blank master dataspace is refused (`engine.missing.noMasterDataspace`) instead of being written. Targets configured before this fix hold
mappings without a dataspace: click Configure again on each of them (the flow of the same label is updated in place) before the next send.
(`DistributionMappingDocumentTest` pins the key.)

## A send that outlasts the Factory's patience (wave 13, 2026-10-12)

*The asynchronous send below ("Following and stopping a send") replaces the long read timeout for a Connectors that runs a scope send asynchronously. What this section describes is what remains for a Connectors that does not (the Connectors of 2026-10-12 refuses `async` for a scope send), and the reason the change was made.*

A full send of Vol100k (target 3, Kafka: 57,568 persons, then 88,991 addresses) ran as one `connectors_sync` call per mapping with `max` = `ebx.factory.distribution.maxRows` (100,000). The persons call took
about 4 minutes; the addresses call was cut by the Factory after exactly 300 s ("The in-JVM ebx-connectors bridge did not answer within 300 s (read timeout)") while Connectors kept sending: its run went
on to the end and Kafka received every row. The 300 s was the one read timeout of every Connectors call (`McpServers.READ_TIMEOUT_MS`), and a call whose work is proportional to its records (150-250 rows/s
measured) cannot be bounded by a constant.

- `DistributionRunner.send` (the first full send, "send now", the scheduled and paused-load sends, which all go through it) now builds its Connectors client with `McpServers.connectors(session, factory, timeoutMs)`,
  `timeoutMs` = `DistributionBatches.sendTimeoutMs(maxRows)` = 300 s + `maxRows` x `ebx.factory.distribution.perRowMs` (20 ms), capped by `ebx.factory.distribution.sendTimeoutMaxMs` (2 h). 100,000 rows give 2,300 s.
  The in-JVM bridge waits that long and the HTTP route reads that long. The step detail records it (`readTimeoutSeconds`). The contract (`ConnectorsContractClient`) carries no send (it serves flows, the model and the
  catalog calls); the per-record dispatcher (`DistributionDispatcher`) sends one key per call and keeps the ordinary timeout.
- If the timeout still elapses, the step, the target's `lastMessage` and the job say: "... did not answer within N s (read timeout): the Connectors run may still be sending; check the Connectors sync log of
  mapping <id> (connectors_run_get) before sending again." (`engine.external.sendTargetTimeout`). The Factory stopping to wait does not stop Connectors: look at the run (`connectors_run_get`, or the
  Connectors sync log filtered on the mapping) before starting another send, which would send the table twice.
- Not changed in this wave (closed by F2-05, 2026-10-24: the scan runs async and is followed, `connectors_publish` waits 300 s + rows x perRowMs, see OPERATIONS.md section 5 "Long Connectors calls"), was still on the fixed 300 s (`McpServers.connectors(session, factory)`) with a row-dependent work: the catalog scan `connectors_sync` of `SourceOnboarder` (`max` = `SCAN_MAX_TABLES`, 10000 tables) and the first publication `connectors_publish` of `DataProductPublication` (every row of every product table). The landing load (`LandingFlows`) starts its run with `async: true` and polls, so it is not exposed.

## Following and stopping a send (wave 13, 2026-10-12)

The first full send, "send now", the scheduled and the paused-load catch-up sends (all of them go through `DistributionRunner.send`) no longer hold one long synchronous `connectors_sync` call per mapping. They do what the
landing load does (`LandingFlows`, `docs/VOLUME.md` "Lot 7c"): start the run, read its id, and follow it. The calls of one round are in `ebx.DistributionSendCalls`, the decisions in `distribution.SendRuns` (both unit-tested
with a scripted Connectors).

1. **Start.** `connectors_sync` with `async: true` (mapping, `scope`, `dryRun: false`, `max` = `ebx.factory.distribution.maxRows`) on the ORDINARY Connectors client (300 s read timeout): the call answers at once with
   `{runId, status: "RUNNING", mapping, async: true}`. The run id is written into the send step's detail the moment it is known (`PipelineStep.detail` > `tables[].connectorsRunId`, with the last run ids in `tables[].runIds`),
   before the wait, so a person - and a restarted Factory - can find a run that is still going.
2. **Follow.** Every 5 seconds (`LandingFlows.LOAD_POLL_INTERVAL_MS`, the landing's interval) `connectors_run_get(runId, callsMax: 0)`. While `execution.status` is RUNNING the table's detail carries `rowsSoFar`. At a terminal state
   (DONE, PARTIAL, ERROR, STOPPED, INTERRUPTED) `execution{}` is read into the verdict the send always read (`SyncOutcome.Result`, by `SendRuns.terminal`): `rows` = `created + updated + noChange`, `truncated` =
   `execution.truncated`, plus (since the message fix of 2026-10-12) the two parts of `rows` kept apart: `sent` = `created + updated`, `unchanged` = `noChange`; DONE and PARTIAL are a success when `errorCount` is 0 (a PARTIAL that only means "cut at `max`" is a success with `truncated`), with errors, or ERROR / STOPPED / INTERRUPTED, a failure whose message names
   the first error (`firstErrors`, else `message`). Everything downstream is unchanged: a `changed` round that is still cut is asked again (`DistributionBatches.askAgain`), a cut `all` send ends FAILED with `cutMessage`.
   The wait has an absolute limit, the same as a landing load (`ebx.factory.landingLoadMaxMinutes`, 12 h): past it the run is stopped and the send fails (`engine.external.sendRunTimeout`).
3. **Stop.** Between two polls the job is asked whether it was told to stop (`FactoryJobs.Handle.cancelled()`, which also counts as the job's check-in). If it was, the Factory calls `connectors_run_stop(runId)` (Connectors stops at
   its next batch boundary, never mid-batch), reads the run until it is terminal (at most 12 reads, a minute at the 5 s interval: the stop only asks, the run's final counters are written when it ends - a single read right after the
   request said "after 0 row(s)" for a run that had sent 13000, Vol100k target 3, 2026-10-12) for the rows it had reached, and ends the send **STOPPED**, not FAILED. The message names the rows sent and what they mean:
   "Send stopped (stopped by admin) after 13000 row(s) sent; the rows sent keep their outbound state, a 'changed' send resumes after them." (If the run had not finished stopping after the 12 reads, the message adds that the count may be
   short and the detail carries `stopPending: true`.) The step, the target's `lastMessage` and the job carry that sentence; the result and each table's detail carry `sent` and `unchanged` next to `rows`: the step is `STOPPED`, `DistributionTarget.lastStatus` reads `STOPPED`, the job is
   recorded CANCELLED, and a first full send leaves the target WAITING with no `firstFullSendRunId`. A stop asked before the next mapping starts ends the send the same way.
4. **Older or stricter Connectors.** A Connectors that ignores `async` answers the complete synchronous shape (`count`, no `RUNNING` acknowledgement): it is read directly, exactly as before (`SendRuns.isAsyncStart` tells the two apart
   by shape, never by version). **A Connectors that refuses `async` for a scope send** - `"async only applies to an inbound feed with no key, predicate or scope"`, the answer of the Connectors measured on 2026-10-12, where
   `connectors_sync(async)` exists for an inbound feed only and an outbound scope run keeps no `execution{}` state and cannot be stopped - started nothing: the round is asked again synchronously on a client with the long read timeout
   (`DistributionBatches.sendTimeoutMs`, "A send that outlasts the Factory's patience" above), and the later rounds of that send go straight there (the detail says `asyncRefused: true`, `async: false`). In that mode there is no run
   to follow, the run id is only known when the call returns, and a stop ends the send after the call it is waiting for, before the next mapping.

**What the end message says.** `rows` keeps counting everything a round covered (what `DistributionBatches.askAgain` and the cut messages read), and `sent` / `unchanged` split it. A finished send says "Sent N row(s) across M mapping(s), scope=S"
when nothing was unchanged, and "Sent 0 row(s), 146559 unchanged, across 2 mapping(s), scope=changed" otherwise (a `changed` send where every record was unchanged used to end "Sent 146559 row(s)", 0 of them sent). For the synchronous answer
(an older Connectors, or the fallback above) the split is approximate: `unchanged` is the number of `results[]` items with status `NO_CHANGE` and `sent` is the rest of `count`, so records that were skipped, had no data or failed count as sent,
and a Connectors that omits `results[]` on a big batch reads all of it as sent. These sentences are plain step messages, not coded engine messages (no bundle key).

**What the run id is.** The Connectors run (`R<yyyyMMdd>-<HHmmss>-<n>`, the `runId` of the Connectors sync log: `connectors_run_get`, `connectors_log_search(runId=...)`, `connectors_replay`). A send has one per mapping and per
round; the step's detail keeps them, and `DistributionTarget.firstFullSendRunId` takes those of a SUCCESSFUL first full send (comma-separated, whole ids, at most 80 characters, the Factory's step id when none is known). The wizard
ticks "first full send done" on a non-empty value, so a failed or stopped first send leaves it empty, and it was a Factory step id before this wave. Nothing was added to the XSD.

**No double send.** Before a send starts (`DistributionRunner.refuseIfRunning`) the Factory reads the run ids recorded by the target's latest send step (any status: a Factory restart leaves the step INTERRUPTED and the Connectors
run going) and `firstFullSendRunId`, and asks `connectors_run_get` about each. A run Connectors still reports RUNNING refuses the send, before any step is written: "A send of target N is still running: run R; wait for it or stop it"
(`engine.conflict.sendStillRunning`, 409 on the send routes). A run Connectors no longer knows, or has no state for, does not block. The job queue already refuses a second queued or running send of the same target
(`DistributionSendRun.isActive`); the guard covers what the queue cannot see.

**Stopping from outside.** `POST /projects/{code}/distribution/targets/{targetId}/stop` (administrators): cancels the target's queued or running first-full / send-now jobs through the job queue, and stops with
`connectors_run_stop` every recorded run `connectors_run_get` still reports running (a run that outlived a restart of ON EBX, whose job is gone). Answers `{targetId, stopped, jobsCancelled[], runsStopped[], message}`;
`stopped: false` when nothing was running. `POST /jobs/{id}/cancel` on the send job does the first half. A run is stopped at its next batch: records of the batch in progress still go out.

**Not verified live.** Written and unit-tested against a scripted Connectors on 2026-10-12; no EBX instance was touched. The followed run needs a Connectors that accepts `async` for an outbound scope and keeps
`execution{}` for it (counters `created` / `updated` / `noChange` / `errorCount` / `truncated`, `firstErrors`): until it does, a send takes the synchronous path above. To check once it does: start a send of Vol100k target 3 and watch
`tables[].connectorsRunId` appear in the step's detail within seconds; stop it with the route and read `STOPPED`; send again while a run is going and read the 409.

## Round 8 (lot S2, 2026-10-18) - retire notices from the merge STATE, and what a message says

Worktree `ebx-mdm-factory-s2-retire`, branch `feat/distribution-retire-on-merge`. **No XSD changed** (`MdmFactory.xsd`, `DistributionOutbox.xsd`): everything new is stored in the
existing text field `DistributionTarget.mergeLogWatermark` (4000 characters), so there is no data-model restart; the module classes still need the usual Tomcat restart to be loaded. A mapping written
before this lot gets its `_change` rule at the next **Configure**.

### What was measured to be missing (read in the code, not yet seen live)
The merge-log watch of §5.3 already queues a RETIRE for every record of a NEW merge. Three situations it cannot see, because it works from the merge log and `RecordMetadata.mergingProcessId`:
1. **Former members of an undone merge.** CLAUDE.md §8 (measured 2026-09-23): after an unmerge the members are back to `GOLDEN` with `mergingProcessId` reset to 0, so `readMembers(./mergingProcessId=<merge>)` no longer finds
   them and the `CREATE` of `MergeLogWatch.undoneEvents` is never produced; a member that comes back `PENDING_APPROVAL` and is only later accepted is never revisited either.
2. **A notice that never arrived** (`SKIPPED` because the target was no longer armed at dispatch time) while the watermark has moved on.
3. **A merge older than the watermark's first pass.** The watch learnt "where the log stands" at its first dispatcher pass, which comes AFTER the first full send: a merge made while that send ran was older than the
   watermark and was never retired.
Two more things found while reading the send path (Connectors main, `OutboundSelector`, `ConnectorsTools.sync`):
4. **The first full send sent MERGED and DELETED records as live ones.** The mapping input `mergedRecords=exclude` the Factory writes is read by the data-product publication only; `connectors_sync` with a scope walks the whole table.
   (`DistributionTriggerBody`'s own text and §5.3 spoke of "`mergedRecords=exclude` on the first full send" - it never filtered.) The same hole existed for an ordinary save: a `MODIFY` of a record Match and Merge holds as MERGED
   sent it again as live.
5. **A `DELETE` event was never delivered**: it went to `connectors_sync(mapping, key)`, which answers "No record for key/predicate" for a record that is gone (retried 7 times, then `FAILED`).
Also found: the **history rows never carried `reason` / `mergeId`** (only the outbox row did), so the wizard's history column could not show "golden deleted by an undone merge" or "live again after an undone merge".
Note for the install: Connectors **main** passes the `document` of `connectors_call` to the connector; the `feat/connection-catalog` checkout of `~/Dev/ebx-connectors` still passes `null` (a retire notice would then reach Kafka as `{}`). Install from main.

### The state sweep (`MatchingStateSweep` pure, `ebx.MatchingStateSweeper` EBX side, `distribution.MergeLogState` the stored marks)
**When it runs.** (a) At the end of every matching run the Factory launches: `MatchingConfigurator.run` (all three callers: wizard job, workflow bean, REST) calls `DistributionDispatcher.requestSweep(projectId)` in a `finally`, which queues
a forced pass on the dispatcher's own thread (never overlapping a drain; the pass runs after a scope-all run has resumed the paused targets). (b) On the dispatcher tick of every ARMED REALTIME target - right after `MergeLogWatcher.watch`
and before the drain - at most once a minute per target (`MatchingStateSweeper.MIN_INTERVAL_MS`), which also covers merges and unmerges made by a steward in the add-on's screens or by REST.

**What it reads**, per master table of the target that has a metadataset (nothing for a table without one): (1) the `RecordMetadata` table's last modification date - unchanged since the last sweep and the sweep up to date means nothing else is read;
(2) ONE streaming request `./state=2` (key, `goldenId`, `mergingProcessId`) and ONE `./state=4 and ./autoCreated=true` (CLAUDE.md §8: 417,841 rows in 3.5 s); (3) the ledger = the project's outbox rows of this target and table (PENDING / FAILED-for-good
count as announced) plus the target's `DistributionEvent` history of the table restricted to RETIRE notices and `reason=UNMERGE` rows (SENT and FAILED-for-good announced, SKIPPED = never arrived); (4) one `RecordMetadata` lookup per record the
target holds as retired that is neither merged nor an unmerge-deleted golden.
**What it writes**: the missing notices as outbox rows (`ebx.MergeLogWatcher.writeOutboxRows`, one `Procedure`, same writer as the watch), then - only once they are committed (at-least-once) - the marks in `mergeLogWatermark`.

**The rules** (per table; `retireMode=NONE` emits nothing but the marks advance):
| | state now | the target holds | notice |
|---|---|---|---|
| R1 | `MERGED` | not "retired" (nothing, or a reinstatement after the retire) | `RETIRE(key, retiredTo=goldenId, reason MERGE, mergeId)` if published (below) |
| R2 | `DELETED` + `autoCreated` (an unmerge's golden) | not "retired" | `RETIRE(key, no retiredTo, reason UNMERGE, mergeId)` if published |
| R3 | `GOLDEN` | "retired" (last announcement is a RETIRE) | `CREATE(key, reason UNMERGE)` - the full record again; only when `retireUndoneMerges` |
`PENDING_APPROVAL` gets nothing and stays a candidate at every sweep until it is `GOLDEN`. A merge id above the watch's mark (`last`) is left to the watch (no double notice). A reinstated member merged again is retired again.

**The "published" rule (item 2 of the brief).** A record needs a RETIRE only if the target could have held it as live. The target's `baseline` for a table = the highest merge id **when the target started being watched**: since this lot,
`DistributionRunner.sendFirstFull` records it BEFORE the first full send reads a row (`MergeLogWatcher.captureBaseline`, REALTIME targets, best effort; an override arming records it at the watch's first pass). The first full send
leaves merged / deleted records out (item 4 below), so a record merged under the baseline was never published: **no notice**, and the same for a record whose `mergingProcessId` is unknown. A record merged above the baseline and above `swept` (the
highest merge id a previous sweep compared) is announced; so is any record whose ledger proves it was published (a RETIRE that never arrived, an earlier reinstatement). A target whose field was written before this lot has no baseline: the sweep announces
nothing on the strength of a merge id there (the watch's first-pass behaviour is kept), only the ledger-proven cases (R3 included).
**Idempotence**: a notice the sweep queues is a PENDING outbox row, then a SENT history row, which the next sweep finds in the ledger: a second sweep writes nothing (`MatchingStateSweepTest.secondSweepWritesNothing`, also with the marks lost).

### One payload convention (`distribution.NoticeDocument`)
The messages carry no Kafka header (a header needs a Connectors lot that has not shipped), so the change type is in the payload. Every message has `_change`:
| message | built by | document |
|---|---|---|
| CREATE and MODIFY | Connectors, from the mapping | the record's full current state + `"_change":"UPSERT"` (a mapping rule `./id -> _change`, `constant(UPSERT)`; **new**: absent before) |
| RETIRE | the Factory (`connectors_call`) | `{"_change":"RETIRE","id":K,"retired":true,"retiredTo":G?,"reason":"MERGE\|UNMERGE"?,"mergeId":N?,"at":ISO}` (`_change` **new**; the other members unchanged) |
| DELETE | the Factory (`connectors_call`) | `{"_change":"DELETE","id":K,"deleted":true,"at":ISO}` (**new**: a DELETE was never delivered) |
The Kafka key is the record's `id` for all of them. `UPSERT` and not `CREATE`/`MODIFY` because Connectors builds the document from the record at send time: the Factory cannot tell the consumer which of the two it was, and the message replaces
whatever the consumer holds for `id` either way. The brief's `key` / `mergedInto` names were not used: `id` / `retiredTo` already existed and a consumer may read them.

### The other changes
- **Live records only.** `connectors_sync` of the first full send and of every scheduled/send-now send passes `predicate = not(./Linked_Field_State = 2 or ./Linked_Field_State = 4)` (`GoldenOnlySends`) on a table that carries `Linked_Field_State`
  (the same rule as the publication's `mergedRecords=exclude`: MERGED and DELETED left out, GOLDEN / PENDING_APPROVAL / no metadata sent). The step detail records it per table (`predicate`). The dispatcher also skips a `CREATE`/`MODIFY` of a
  record that is MERGED/DELETED at dispatch time (history `SKIPPED`, "not a live record, a retire notice covers it").
- **History carries `reason` / `mergeId`** (`DistributionDispatcher.writeHistory`, via a per-group note), which the ledger and the wizard history read.
- **Surface.** Step 7 (REALTIME target, saved): the retire panel ends with "Retire notices sent: N - merge states last compared <time>" (`WizardPage.renderDistributionRetireSettings`; `DistributionReader.retireFacts`: count of RETIRE rows SENT in the
  target's history, counted up to 10,000 and shown "10000+", and the later of the stored sweep time and this JVM's last look); the summary step's Distribute card adds "Retire notices sent: N" to each REALTIME target's line. Bundles under
  `# distribution retire on merge (2026-10-18)`.

### Known limits (not hidden)
- `mergeLogWatermark` is a 4000-character field: the list `undone` of the watch grows by one id per unmerge (about 500 unmerges per target before a write would fail - logged, and the watch then retries the same notices); not changed here.
- The sweep reads the target's history with a predicate on non-key fields (one scan per table per changed sweep, rate-limited to a minute); on a history of millions of rows that is seconds. History older than the retention (`FactoryRetention`) is gone from the
  ledger: a merge older than `swept` is never re-announced for that reason (the `swept` mark protects it), only a notice that was SKIPPED and purged is lost.
- A member's `retiredTo` is the golden it was merged into; when that golden is itself merged later, the member's notice is not rewritten (the consumer follows the golden's own RETIRE).
- A DELETE notice uses the target's write operation like a RETIRE (a Kafka `publish`, a REST `upsert`): a connector with a dedicated delete operation is not used.
- Not exercised against a live merge / unmerge: no instance with a distribution target and Match and Merge data was reachable (the local instance holds one DRAFT target, no history). Unit-tested: `MatchingStateSweepTest` (merge, auto-merge of 3 into 1,
  unmerge, pending member, idempotence, never-published member, skipped notice, re-merge, NONE), `MergeLogStateTest`, `NoticeDocumentTest` (documents and predicate), `DistributionMappingRulesTest`.
- To verify after the restart (Personnes, REALTIME target ARMED, Configure run again): accept a suspect -> within a minute the topic shows `RETIRE` for both members; unmerge the group -> `RETIRE` of the golden (no `retiredTo`) then `CREATE`-as-UPSERT of the members
  (history rows `reason=UNMERGE`); a first full send on a table with merged records sends none of them.

## Round 9 (lot S3, 2026-10-18) - no typed `sql` literal in a generated data-model script

Worktree `ebx-mdm-factory-s3-trigger`, branch `fix/distribution-trigger-createquery`. No XSD changed; the module classes need the usual Tomcat restart, and **an already installed project keeps its old (crashing) trigger bodies until "Install triggers" runs again**.

### What was measured (EBX 6.3.0-rc3, Factory 1.2.0-298, first live run of step 7 of the e2e, project `E2EMASTER20261018171023`)
`install-triggers` failed: `update_script` of the generated `afterCreate` body on `/root/Personne` answered `INVALID_DATA_MODEL` ("Revision history ... could not be loaded" is the ScriptingMcp classifier's wording for ANY
runtime exception of the compiler), and the IDE's validate-only route (`POST /ebx-ide/rest/v1/script/validate/<idePath>`, body `{"source":...,"parameters":[],"openApi":""}`, saves nothing) answered **HTTP 500 with no message**.
Bisected statement by statement through that route on the draft `data-model/E2EMASTER20261018171023_Master/root/Personne/@trigger/DistributionAfterCreate`:
- compile (200): the `uses`/`references` declarations (MdmFactory, DistributionOutbox, the master model), `cp.primaryKeyOf<master.Personne>()`, the Control-row lookup, the Factory dataset lookups;
- crash (500): the TYPED literal `sql `select t.id as targetId from factory."DistributionTarget" t where t.project.code = ? and t.mode = ? and t.state = ?``;
- compile (200): the SAME statement as `db.createQuery('...')` + `db.addDataset(q, 'factory', factoryDataset)` + `db.setParameter`.
The coordinator then measured that it is not this shape: **EVERY typed `sql` literal crashes the compiler of a data-model trigger script on this build**, whatever the model - `factory."Project"` (simple field), `factory."DistributionTarget"` with or
without the FK path, `outbox."Event"`, and even the script's own model (`m."Personne"`): 500 each; `db.createQuery` always 200. (Consistent with CLAUDE.md §2: a typed literal makes the compiler build the full schema of the referenced model.)
**Rule, absolute: no typed `sql` literal anywhere in a generated data-model script** (trigger, constraint, function field).

### The fix
`DistributionTriggerBody.body` (all three trigger types, every master table) builds the target lookup as
```
var targetQuery := db.createQuery('select t.id as targetId from factory."DistributionTarget" t where t.project.code = ? and t.mode = ? and t.state = ?');
db.addDataset(targetQuery, 'factory', factoryDataset);
db.setParameter(targetQuery, 0i, '<project>'); db.setParameter(targetQuery, 1i, 'REALTIME'); db.setParameter(targetQuery, 2i, 'ARMED');
var results := db.executeQuery(targetQuery);
... const targetId := '' | db.field<int>(tuple, 'targetId');   // tuples of a dynamic query are untyped; the key is an xs:int
```
The Control-row short-circuit, the outbox write through `_ebx.transaction`, the `changeValue` / RETIRE detection are unchanged. Lot S2's `_change` convention is NOT in the trigger bodies (it is in the mapping and the dispatcher's notices), so nothing of S2 moved here.
Other generators checked (`grep` of back-quoted `sql` openers over all of `src/main`, tests, docs): the trigger body was the only generated data-model script that used a typed literal. `IntakeTriggerBody` (and its test, which forbids it), `MasterJournalTriggerBody` and the capability / constraint
generators (`ScriptGuard` even refuses a constraint body with a back quote) carry none.

### Proof - the 9 generated bodies through the validate-only route (live draft paths of `E2EMASTER20261018171023_Master`, nothing saved, 2026-10-18)
Every body = `DistributionTriggerBody.body(type, "E2EMASTER20261018171023", "/root/<T>", "<T>", "E2EMASTER20261018171023_Master", ["id"], false, false)` (all three tables have the single key `id`, no `Linked_Field_*`):
| table | afterCreate | afterModify | afterDelete |
|---|---|---|---|
| Personne | 200 `{"isSuccess":true,"messages":[]}` | 200 same | 200 same |
| Adresse | 200 same | 200 same | 200 same |
| AchatMagasin | 200 same | 200 same | 200 same |
Not covered by this proof: the RETIRE branch (the tables carry no `Linked_Field_State` yet), and RUNTIME behaviour of the query inside a trigger (compiles != runs): the check is the first record written into an ARMED target's table after the reinstall.

### The install route says what failed
When `update_script` refuses a trigger body with `INVALID_DATA_MODEL`, `DistributionTriggerInstaller.authorOne` now also calls the validate-only IDE route (`ebx.ScriptValidateRoute`, same credentials as the MCP clients: the session user's token, else the agent account; never throws)
and puts ITS answer - HTTP code, `isSuccess`, each message as `line:col: text`, and the first failing statement of the body when a line is named - in the thrown refusal text, in the trigger's `validateRoute` detail of the step result, and in `kernel.log` (`[mdm-factory] distribution trigger ...`). A bare 500 reads
"HTTP 500 with no readable report (the compiler crashed on this body...)". Pure reader: `distribution.ScriptValidateReport`. **Not measured:** the route with the token cookie (it was measured with Basic only); a 401/403 would be reported as such in the same sentence.
Tests (JUnit-free): `DistributionTriggerBodyTest` (no back quote in any of the 3 types x with/without retire; the createQuery / addDataset / setParameter / executeQuery order; `db.field` read), `ScriptValidateReportTest`, `DistributionTriggerInstallerRefusalTest`.

## Lot F3 (2026-10-19): the triggers no longer know Match and Merge

Branch `fix/distribution-triggers-no-linked-field`. Measured 2026-10-18 22:17 (6.3.0-rc3 + Match and Merge RC3): `DistributionAfterModify` read `Linked_Field_State`/`GoldenId`; deleting a table's matching configuration removed those fields, the whole master model stopped compiling
(`Trigger script 'DistributionAfterModify' has errors: Cannot resolve symbol 'Linked_Field_State'`) and the add-on answered "Table not found" for every table of the model. Lot S9 only ordered the project deletion around it; a step reset, the orphan sweep or an administrator in the add-on's screen still hit it.

- **The three bodies are now `{table, record key, CREATE|MODIFY|DELETE}` and nothing else**: no `Linked_Field_*`, no `GoldenId`, no `previousRecord`, `survivorKey` always empty, `RETIRE` never written (`DistributionTriggerBody.body` lost its two `pHas...` parameters; `DistributionTriggerBodyTest` asserts it for the 3 types and several key shapes). There was no "changed fields" filter in the generated body (the target's `watchedFields` is stored but nothing reads it), so none was kept.
- **Live or not is decided by the dispatcher, in Java** (`DistributionDispatcher`, unchanged logic, lot S2): a CREATE/MODIFY whose record Match and Merge holds as MERGED (2) or DELETED (4) at dispatch time is finished SKIPPED ("record is MERGED in Match and Merge at dispatch time - not a live record, a retire notice covers it"), with a history row; GOLDEN, PENDING_APPROVAL, a record without metadata and a table without the add-on are live (`GoldenOnlySends.isLive`). The state is read with a tolerant Java lookup (`./Linked_Field_State` absent = unknown = live), so it survives the configuration being deleted. RETIRE and the "live again" CREATE keep coming from `MergeLogWatch` and `MatchingStateSweep` (metadataset only), as before.
- **Consequence:** `StepResetter`, `OrphanMatchingConfigCleanup`, project deletion (S9's `DISTRIBUTION_TRIGGERS` item stays, it only keeps the model clean) and a manual deletion in the add-on's screen cannot break the compile any more. The wizard's `matchingNotActiveHint` is always false now (key kept for callers).
- **Repairing a project installed by the old generator:** `DistributionTriggerInstaller.install` ("Install triggers") reads each trigger with `get_script(includeSource=true)` and compares it with the generated text (`sameSource`: line endings and trailing blank lines ignored). Identical = `update_script` skipped (`unchanged: true`); different (every old `afterModify` of a table that carried `Linked_Field_State`, every freshly declared template) = rewritten and validated; `publish_data_model` always runs once at least one trigger is declared, unchanged bodies included (so a run whose publish failed is repaired by the next; `publishSkipped` only when no trigger is declared; `triggersWritten`/`triggersUnchanged` in the detail). Not run on a live instance (Tomcat stopped when this was written).
