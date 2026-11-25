# Per-project backup and restore (audit F-30, wave 10)

What it is: every row the MDM Factory holds for ONE project, as one JSON file, and the way back into an instance where
that project does not exist. It is NOT a backup of the generated EBX objects and does not replace the repository backup
(`docs/UPGRADE.md` section 3).

## Backup

`GET /ebx-mdm-factory/rest/v1/projects/{code}/backup[?exclude=Xref,TransferReject]` (administrator; also the Cockpit's
"Download the backup" link, which is the same GET with the browser's EBX session). The answer is streamed (`Content-Disposition`:
`project-backup-<code>-<yyyyMMdd-HHmmss>.json`); `exclude` leaves volume-scale tables out (`Xref`, `TransferReject` and
`DistributionEvent` can hold millions of rows; `Project` is never left out).

Format (`backup/BackupFormat.java`), `formatVersion` 1: `format`, `formatVersion`, `project`, `createdAt`, `createdBy`, `module`
(version, commit), `excluded`, `tables` (each `{table, path, rows: [{key, values}]}`, parents before children), `rowCounts`,
`references`, `notIncluded`, and `complete: true` as the last field. A reader refuses another `format` and a newer
`formatVersion`. Which fields a table holds comes from the schema (stored, non-computed, non-association fields), so a field
added later is in the next backup; a field named like a password, secret, key or token is never exported.

Tables (`backup/BackupTables.java`, in the order they are written and restored): `Project`, `ProjectSource`, `LandingTable`,
`LandingColumn`, `LandingRelation`, `StagingEntity`, `StagingAttribute`, `TransferFeed`, `AttributeMapping`, `MappingStep`,
`QualityRule`, `CapabilitySuggestion`, `MatchPlan`, `PipelineStep`, `FactoryJob` (finished jobs only), `AgentRun`, `AgentStep`,
`DistributionTarget`, `DistributionEvent`, `IntakeChain`, `Xref`, `TransferReject`. A test fails when a table is added to the model
and is neither in this list nor on the list of what a backup never holds (`FactorySettings`, `AccessRight`, `ReferenceBinding`,
`Favorite`, `LoadPause`).

**What the backup does not contain** (said in the archive, `notIncluded`): the instance-wide settings and rights, the per-user
favorites, transient state (queued or running jobs, load pauses), the data of the landing, staging and master datasets, and every
EBX object the Factory generated. These are listed under `references` (taken from the rows: the landing/staging/master dataspaces
and datasets, the landing models and publications, the Connectors mappings of the landing flows and of the distribution targets,
the matching and merge policy codes, and the staging and master model names computed from the project code), so the person who
holds the file knows what to rebuild or to move with the delivery archive of EBX Staging (`docs/DELIVERY.md`).

A successful backup is recorded as a `PROJECT_BACKUP` step of the project (user, time, row count).

## Restore

`POST /ebx-mdm-factory/rest/v1/projects/{code}/restore?dryRun=true|false` with the file as the body
(`Content-Type: application/json`, and `X-Requested-With` or an `Authorization` header, as every state-changing route;
administrator). **`dryRun` defaults to `true`**: nothing is written unless `dryRun=false` is passed.

    curl -u admin:*** -H 'X-Requested-With: curl' -H 'Content-Type: application/json' --data-binary @backup.json \
         'http://host:8080/ebx-mdm-factory/rest/v1/projects/Companies/restore'                 # rehearsal
    curl ... 'http://host:8080/ebx-mdm-factory/rest/v1/projects/Companies/restore?dryRun=false'  # for real

- The project must NOT exist on the instance (otherwise refused: nothing is merged or overwritten; delete it first with the
  deletion screen) and the file must be of the same project code.
- The dry run reads the whole file and reports, per table, the rows that would be written, the references that would be
  renumbered, the warnings (an unknown field, a reference to a row that is not in the file) and the `references` and `notIncluded`
  of the archive; `ok: false` with `errors` when the file cannot be restored (wrong format, a value of the wrong type, no
  `complete` marker: a download cut short). It is the only check of a file before it is used.
- **Renumbering**: the auto-increment `id` of the file is not kept (another project of the instance may use it): each row
  gets a new id, every foreign key and `FactoryJob.stepId` is rewritten, a reference to a row written later is written at the
  end. A text that quotes an id (a step's JSON `detail`) is not rewritten.
- **Nothing comes back running**: the automatic intake is OFF, distribution targets are DISABLED (re-arm on purpose), the
  schedule key of a target is dropped, steps and runs that were RUNNING become INTERRUPTED, a running intake chain FAILED.
- **Failure**: the write is batches of 500 rows, not one transaction (a project can hold millions of rows). Any failure, or a file
  without its `complete` marker, deletes everything the restore wrote (the project did not exist, so every row keyed by it is the
  restore's) and answers 409 with the first error; a refused restore (project exists, wrong file) writes and deletes nothing.
- **Bounds (1.4.2, F2-11)**: the archive is read as a stream and refused (`MDMF-RESTORE-1`, rolled back like any failure) beyond `ebx.factory.restore.maxBytes` (2 GiB by default) or
  `ebx.factory.restore.maxRows` (5,000,000); the values in force are `restoreBounds` in `GET /rest/v1/status`. An archive of a project in a style that is not open (Centralisation, "coming soon")
  is refused too (`MDMF-STYLE-3`), nothing written.
- **Master values (1.4.2, F3-06)**: while the governed agents are closed, `AgentProposal.{arguments, rationale, applyMessage, target}` and `AgentRun.inputs` are left out of the archive and listed under `valuesOmitted`.
- A restore never starts a task (F2-03): a backup leaves out QUEUED and RUNNING jobs, and an archive edited by hand that carries one has
  the row restored as `INTERRUPTED` with `requestedBy` emptied (`RestoreAdjust`); the dispatcher also re-checks the requester's right
  before it runs any row.
- A restore is recorded as a `PROJECT_RESTORE` step (user, time, rows). The restored project then needs its generated objects:
  rebuild them with the wizard, or import the delivery archive first.

## Audit trail: who launched what

Already recorded before this wave: `FactoryJob.requestedBy` / `via`, `PipelineStep.startedBy` / `via`, `AgentRun.startedBy`,
`LoadPause.pausedBy`, and the tracking text of the writes of the loaders (`mdm-factory:<step id>:...`, where the step carries
the user). Added: `IntakeChain.startedBy` (the person the engine runs the chain for: the one who switched the intake on),
`TransferReject.reviewedBy` / `reviewedAt` / `reviewComment`, the `PROJECT_BACKUP` / `PROJECT_RESTORE` steps, and
`setExecutionInformation("mdm-factory:reject-review:...")` / `("mdm-factory:project-restore:<code>:<user>")` on the writes of
the review and of the restore. The Factory tables keep no EBX history (the audit's finding stands for the other tables): the
steps and jobs are purged by the retention, and are editable by an administrator.

## Not done

Restoring the generated objects, merging into a project that exists, restoring under another project code, and a button to
restore (the project does not exist, so no project screen can host it): REST only.

**Agents (F3-10, 2026-11-12).** `AgentRun`, `AgentStep`, `AgentProposal` and `SourceDrift` are in the backup. `AgentDefinition` is not, on purpose, and `notIncluded` says so: its key is a code
shared by the whole instance, and an enabled definition restored by itself would start running again on the target. Declare the agents again after a restore.
