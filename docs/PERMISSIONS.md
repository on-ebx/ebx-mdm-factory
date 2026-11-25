# MDM Factory: EBX permissions on every screen

Second pass, written 2026-10-14 on branch `fix/permissions-remaining` (worktree `ebx-mdm-factory-perms2`), after the product owner's rule of the
same day: **EBX permissions are respected in every case; public Javadoc APIs only**. The first pass (the project explorer) is
`docs/PROJECT-EXPLORER-PERMISSIONS.md`; the grid shared by the explorers is `explorer-designer/PERMISSIONS.md` (EBX MCP folder).
**Written and tested offline only** (`tools/test_local.sh` green); not installed, never seen running.

## Two kinds of code, one rule each

| Kind | Examples | View |
|---|---|---|
| User-facing: a screen, an AJAX or GET answer, a REST route a person calls | Wizard, Cockpit, Delete, Feed designer, Mapping matrix, Proposal review, Transfer rejects, Record lineage, Connection, `/rest/v1/...` | the person's: `ebx.UserView.of(session)` |
| Engine: a background job, a workflow step, an installer, the intake engine | `*Run`, `FactoryJobQueue`, `MasterLoader`, `IntakeEngine`... | everything, as before: `UserView.ENGINE` (null session or the system user) |

`ebx.UserView` (public APIs only, each verified with `javap` on the RC `ebx-lib.jar`):

- rows: `Request.setSession(session)` ("the returned records will be filtered using the access rules on records"); a hidden table,
  or a filter/sort reading a hidden node (`QueryPermissionException`), gives no row;
- one record: `lookupAdaptationByPrimaryKey` then `SessionPermissions.getAdaptationAccessPermission(record)`; hidden = `null`, the
  same answer as an unknown or malformed key;
- fields: `getNodeAccessPermission(node, dataset)` (cached per view), then `getNodeAccessPermission(node, record)` for the
  record-dependent rules `setSession` does not apply - at most `UserView.MAX_RECORD_FIELD_CHECKS` (50,000) record checks per view,
  beyond which only the dataset-level rules apply (logged once);
- actions: `isReadWrite()` on the record, `getOccurrenceActionPermissionToDelete`, `getTableActionPermissionToCreateRootOccurrence`,
  `getTableActionPermissionForService` / `getNodeActionPermissionForService` (through `ProjectAccess.serviceOnTable/OnRecord`).

**Shared readers** read through `UserView.current()` (`UserView.select/byKey/get/string/values`): the engine's view everywhere, the
person's only inside a `UserView.scoped(view, ...)` (or `scopedRead`) that a screen opens around a **pure read** - never around a
write, a job or an MCP call. Readers converted: `WizardData` (the snapshot: `readSnapshot(UserView, ...)` is the screens' entry, the
old `readSnapshot(...)` stays the engine's), `AccelData`, `KeyedRows`, `PipelineStepIndex`, `ProposalReviews`, `MappingMatrixReader`,
`DistributionReader`, `DeliveryDomainReader`, `MatchingActivityReader`, `MasterBuildGridReader`, `IntakeReadinessReader`,
`StagingLoadGridReader`, `GoldenRecordsReader`, `ProjectMasterEntities`, `RejectReview.entityNames`. `FactoryJobStatus.of(…, viewer)`
and `recentTasks(…, viewer)` take the viewer explicitly; `WizardSummaryReader` takes a `UserView`.

**Decisions** are in `core.RecordGate` (pure, `RecordGateTest`): a record the person cannot see is answered exactly like a record that
does not exist; a reading action needs it visible; any other action (writes, queues a job, calls a language model about it) needs
read-write access on it; creating a project needs the creation right on `Project`. The Factory right (`FactoryAccess`,
`FactoryRights`) is still asked first: necessary, never sufficient.

**Writes.** The procedures of `WizardData`, `ProposalReviews`, the Feed designer and the Connection screen already ran with the
person's own session (EBX checks every row and field they write). Jobs run as the system user: they are only queued after the
checks above, for this person, on this project. Two module writes keep all privileges after a user-facing check, and say so in a
comment: the transfer-reject review bookkeeping (`RejectReview.write`: only rows the person sees) and the project deletion job
(after the record's delete right).

## Screens and routes

| Screen | Dataset / table | Record opened | Service permission | Reads | Writes |
|---|---|---|---|---|---|
| Wizard (`ProjectWizardUserService`) | `ProjectAccess.refusal` at display and on every postback | selected record, `project` URL parameter and posted `mdmfProjectId` opened with the view (hidden = unknown code, a new project starts as for an unknown code); re-checked at each render | table, at display and on every postback | `readSnapshot(view…)`, every reader of the page scoped; matching results masked (below); golden-record figures cached per user | `RecordGate.onWizardAction` before every action; posted `mdmfSourceId` / `mdmfTableId` dropped unless visible and of this project |
| Cockpit (`FactoryCockpitUserService`, record service) | refusal | `ProjectAccess.projectOpens` on EBX's record | on the record | every row and field through the view; job list with the viewer | none |
| Delete (`ProjectDeleteUserService`) | refusal | selected / URL / re-read at each use | table | label through the view | administrator AND delete right on the record, then the job |
| Feed designer | refusal | feed → entity → project, each with the view | table | page model scoped | project writable for save, decide, add a field (procedures with the person's session) |
| Mapping matrix | refusal | `project` URL parameter with the view, re-read on every submit and render | table | `MappingMatrixReader` scoped | dataset AND project writable |
| Proposal review | refusal | selected record, picker, re-read on every postback | table | lines, projects, labels scoped | project writable for any non-VIEW action |
| Transfer rejects (record service) | refusal | project record, also for the CSV download | on the record | SQL with the session (as before), entity names scoped | acknowledge / reopen skip rows the person cannot see |
| Record lineage (table and record services) | refusal on the factory dataset | project by key with the view (hidden = not found) | table / module record service on the master record | page scoped | `decideSuspect` (already checked the records and write access) |
| Connection | refusal | settings row with the view | table it was opened on | settings fields through the view | administrator AND settings row writable |
| REST `/projects/{code}/...` (58 routes) | `refused(key, code)`: dataset hidden, unknown and hidden project all 404 | by key with the view | - | GET: visible; matching activity, delivery domain, distribution targets/events scoped | other methods: record writable (403); delete: delete right; restore: creation right when the project does not exist yet |
| REST `/sources/{id}/...` (5) | `refusedSource`: the source and its project with the view | | | | project writable |
| REST `/jobs/{id}/cancel` | job row and its project with the view | | | | project writable |

### Record lineage and Match and Merge

- `RecordLineageReader`: a merged member or a suspect the caller cannot see is **left out** of the group (it used to be cited as
  `restricted: true`, which still said it existed and counted it); a contributing or suspect source whose master, landing or
  staging record is hidden is left out of the chains (it used to be a restricted lane with its system name). Hidden fields of
  visible records stay `restricted` as before (the record is visible, only the value is withheld).
- Step 6's matching results (stored by a job with the engine's view) go through `matching.MatchResultsMask` /
  `ebx.MatchResultsPermissions` at each display: pairs, "not the same" pairs and groups with a hidden record left out, hidden members
  dropped from their group, a hidden compared value blanked with its verdict set to "missing", a label that might quote a hidden
  field replaced by the key, a hidden source system blank, and the whole-table KPIs and band left out as soon as anything was hidden.
- The bulk merge of step 6 goes through `decideSuspect`, which refuses a pair with a hidden record (counted among "refused").

### Favorites

Decided by the product owner on 2026-10-14: kept as is. The person's own pins (`Favorite`, a technical table the module writes with all privileges) are
read by login; only their targets are filtered (first pass).

## Agents and source drift (closed by default; added in 1.4.2, read from the code of 1.4.1)

Both functions answer a coded refusal on every route and run nothing while their switch is closed (`-Debx.factory.agents.enabled`, `-Debx.factory.sourceDrift.enabled`, section 3.1 of the operator guide). When they are open,
the checks are, in order: the Factory right of the route (`core/FactoryRights`), the switch, the row visible with the CALLER's view, then the second pass on the row's PROJECT with the caller's own EBX permissions.

| Route | Factory right | Second pass | Also |
|---|---|---|---|
| `POST /agents/{code}/enable` | DESIGN | project visible AND writable | the caller must hold STEWARD; the server signs the caller's login with the agent's settings (`enabledBy`) |
| `POST /agents/{code}/disable` | STEWARD | project visible AND writable | allowed while the agents are closed |
| `POST /agents/{code}/run` | STEWARD | project visible AND writable | since 1.4.2 the agent must be switched on with a valid signed activation (`engine.conflict.agentNotActivated`); the run acts for the caller |
| `POST /agents/proposals/{id}/decide` | STEWARD | project visible; `AgentReviewAccess.mayDecide`: an administrator, or a holder of the agent's owner profile | an accepted line is applied with the REVIEWER's permissions |
| `GET /projects/{code}/drift` | STEWARD | project visible | read only, not behind the switch |
| `POST /drift/{id}/decide` | STEWARD | project visible (a read check: a steward has no write right on `Project`) | since 1.4.2 the row's `project` must be the project of its landing table (`engine.invalid.driftProjectMismatch`) and the pass runs on that real project |
| `POST /drift/{id}/apply` | DESIGN | project visible AND writable (same cross-check) | the landing model is patched with the designer's own session |

Tables: `AgentDefinition` is a DESIGN table; `enabled`, `enabledBy` and `lastRunAt` are read only for every person (`ModuleOnlyFieldRule`). `AgentProposal` is written by the module only (an administrator may tidy rows); its deciding fields
(`tool`, `action`, `arguments`, `target`, `status`, `applyAfterApproval`, `run`, `agent`, `project`, `decidedBy`, `decidedAt`) are read only for everybody, and its value fields (`arguments`, `rationale`, `applyMessage`, `target`) are hidden
from anyone who is neither an administrator, nor a STEWARD, nor a holder of the agent's owner profile (`AgentProposalReadRule`). `AgentRun` and `AgentStep` are administrator tables. `SourceDrift` is a STEWARD table (a steward can
still write `status`, `landingTable`, `applyMessage` on the row: open question, docs/DRIFT.md). The agents' activation key is hidden from everybody, administrators included.
`Project/style` is read only for every person: at creation only the wizard sets it, for an open style (`MDMF-STYLE-1`, `MDMF-STYLE-2`; operator guide, section 9.1).

## What stays approximate (file:line on the branch)

- Record-dependent field rules: beyond 5,000 projects in the explorer (`ebx/ui/ProjectAccess.java:55`, first pass, kept) and beyond
  50,000 record checks per screen read (`ebx/UserView.java:43`) only the dataset-level field rules apply to what is counted, searched
  or sorted; the record-dependent rules of the explorer's child tables are not evaluated (first pass).
- Uniqueness checks read every project code and every source code (`ebx/ui/ProjectWizardUserService.java:1757`, `:1811`, `:1824`):
  "this code is already used" can confirm that a hidden project or source has that code. A uniqueness check limited to what the
  person sees would let them create duplicates.
- Labels: a pair or group label of the matching results comes from `Adaptation.getLabel` or the compared fields; it is kept only when
  every compared field is visible, but a default-label expression may use other fields (`matching/MatchResultsMask.java`).
- Whole-table figures: the staging load grid, the held-back count and the golden-record figures are counted with the person's
  session (SQL `Query.setSession` / scoped reads) and cached per user; the matching KPIs are hidden rather than recounted. The
  queue position of a job (`ebx/FactoryJobStatus.java:90`) is counted among the jobs the person sees.
- Administrator-only screens that describe or carry everything of a project stay complete on purpose: the deletion plan
  (`ebx/ui/ProjectDeleteUserService.java:269`, REST `GET /projects/{code}/deletion/plan`), the step-reset plan and the backup
  (`rest/FactoryResource.java:1767`, `:1853`): the deletion and the reset act on every row, and a backup filtered by the
  administrator's permissions would restore a different project.
- Intake status (`ebx/Intake.java:148`, REST `GET /projects/{code}/intake`, wizard `ebx/ui/ProjectWizardUserService.java:4182`): the
  project is checked, but the figures are the intake engine's own picture (counts, signals), not recounted per person.
- Module settings read for behaviour, not shown as data (`ProjectOrigin`, `LlmDataPolicy`, the language-model connector): read with
  the engine's view.
- Ids posted for other rows than the project, a source or a landing table (an entity, a suggestion, a mapping in `mdmfId`) are not
  re-checked to belong to the project; the procedures that use them run with the person's session, so EBX refuses a hidden or
  read-only row, but the answer is an error text, not "unknown".
- The batch-complete call of an application (`POST /projects/{code}/intake/{source}:complete`) keeps its own gate (its write access
  to the landing dataset), not the project gate.
- The record-bound lineage action now needs the project visible to the person in the factory dataset: a person who sees a master
  record but not the factory's project gets "unexpected" instead of the lineage.

## Live test plan (after deployment)

Prepare on a scratch copy of the factory dataset (or the RC, never a demo dataset) a non-administrator `u1` with the Factory right
VIEW (then STEWARD, DESIGN) and a role `R1`. For every step, the assertion is the one of the first pass: nothing goes beyond what
EBX's standard views show `u1`.

1. **Hidden project.** A record rule hiding `P2` from `R1`. As `u1`: the explorer's links to `P2` are gone (first pass); paste the
   Wizard URL `...&project=P2`: "No project with code P2 was found - starting a new project instead"; the Cockpit and Transfer
   rejects record services on `P2` by URL: "No project with this key was found."; the Mapping matrix and Feed designer URLs: no
   project / unknown feed; `GET /ebx-mdm-factory/rest/v1/projects/P2/jobs`: 404 "Project P2 not found", exactly as `/projects/NOPE/jobs`.
2. **Read-only project.** `P1` read-only for `R1`, `u1` with DESIGN. Wizard: every action except navigation answers "You may read this
   project but not modify it"; Proposal review and Mapping matrix decisions refused; Feed designer Save refused;
   `POST /projects/P1/staging/suggest`: 403. With a read-write rule: they go through.
3. **No creation right.** Remove creation on `Project` for `R1`: "Save" of a new project in the wizard answers "You may not create a
   project in this dataset"; `POST /projects/NEW/restore?dryRun=true` answers 403.
4. **Hidden child rows.** Hide half of `P1`'s `PipelineStep` and `StagingEntity` rows: the Cockpit's activity and entities, the
   wizard's steps and the Summary's journal list only the visible half (compare with the native table views of `u1`).
5. **Hidden fields.** Hide `Project.label` and `StagingEntity.name` for `R1`: the wizard, Cockpit, review and lineage screens show the
   key instead / an empty name; hide `ProjectSource.sourceSystem`: no source codes anywhere.
6. **Master records.** A record rule hiding some master records of `P1`'s entity from `R1`. Step 6 as `u1`: the pairs and groups with a
   hidden record are absent, the KPIs and band are absent; Record lineage of a visible golden: hidden members and suspects absent
   (not "restricted"), member count without them; bulk merge counts the hidden pairs as refused and merges none of them.
7. **Transfer rejects.** Hide some `TransferReject` rows: the list and counts follow; post a hand-crafted `mdmfRjSel` naming a hidden
   row with ACK_SELECTED: nothing is written for it.
8. **Services.** Disable `ProjectWizard`, `FactoryCockpit`, `MappingMatrix`, `ProposalReview`, `TransferRejectReview`, `RecordLineage`
   for `R1` in the service permissions: each screen opened by URL answers "This screen is not enabled for you here."
9. **Dataset closed.** Hide the MdmFactory dataset for `R1`: every screen shows EBX's refusal; every REST project route answers 404.
10. **Engines unchanged.** As an administrator, run a full pipeline (landing → staging → master → matching → distribution) on a
    project after the rules of steps 1-8 are in place: every job, workflow step and the intake engine behave as before (they read with
    the engine's view).
11. **Caches.** Open the Summary as the administrator, then as `u1`: `u1`'s golden-record figures are their own (the cache key carries
    the user).
