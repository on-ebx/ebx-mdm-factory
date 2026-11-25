# MDM Factory — Distribution: retire notices from the merge log (spec, 2026-09-27)

> **Status note, 2026-11-12 (release-docs lot).** This is the design spec of 2026-09-27, kept for the reasoning. What it describes was built:
> `distribution/MergeLogWatch.java`, `ebx/MergeLogWatcher.java` and `distribution/RetireRules.java` exist, and the behaviour that ships is the one
> `docs/DISTRIBUTION.md` documents (read that file, not this one, for what the code does). The paths and the canvas link the first version of this
> spec gave pointed at a temporary folder of the author's machine and are not part of the repository; the boards are in
> `docs/mockups/wizard-flow/` (`Retire.dc.html`, `RetireHistory.dc.html`, `RetireContract.dc.html`). Anything below that disagrees with
> `docs/DISTRIBUTION.md` or the code is out of date.

Validated mockup (2026-09-27): third row of the wizard-flow canvas, boards `Retire.dc.html`, `RetireHistory.dc.html`, `RetireContract.dc.html`
in `docs/mockups/wizard-flow/`.

## The measured problem (RC2, project Personnes, 2026-09-27 23:19)

Accepting suspect 527 into the group of 347 (Match and Merge, `manageGroup/acceptSuspects`) merged at once: golden
692 (`sourceSystem=MDM`) created, 347 and 527 `MERGED` with `goldenId=692` in the metadataset
(`Master_Personne_MDS/RecordMetadata`), addresses 1 and 181 re-pointed to 692. The armed REALTIME target received
`CREATE Personne 692`, `MODIFY Adresse 1`, `MODIFY Adresse 181` — and **nothing about 347 or 527**. A consumer now
holds three live Pierre Lefevre.

Why: `docs/DISTRIBUTION.md` §5.3 detects a retire in the `afterModify` trigger by comparing
`Linked_Field_State` before/after. That field is a **linked field** (a view on the metadataset); a merge writes the
metadataset and never touches the merged rows, so `afterModify` never runs for them. The trigger stays for what it
sees (CREATE/MODIFY/DELETE of a golden); the merge log covers what it cannot.

## Design

### Merge-log watch (engine, dispatcher side)
- `distribution.MergeLogWatch` — PURE (no EBX class), unit-tested: given the watermark (last merge id seen + the
  set of merge ids known undone), the merge-log rows (`id`, `undone` flag) and, for a merge id, its members
  (`recordKey`, `state`, `goldenKey`, `autoCreated`), it decides the events to write:
  - new merge id (> watermark, not undone): one `RETIRE` per member with `state=MERGED` and `goldenKey` set
    (`survivorKey = goldenKey`); the golden itself is NOT written by the watch (its `CREATE`/`MODIFY` comes from the
    trigger). Members with `goldenKey` empty → `RETIRE` without survivor (`RetireRules` already says so).
  - merge id whose `undone` flag flipped to true (present in the new read, absent from the known-undone set):
    `RETIRE` of the merge's golden (the record with `autoCreated=true` in that group / `mergingProcessId`) with NO
    survivor; and, when the target's `retireUndoneMerges` is true, one `CREATE` per former member whose state is
    back to `GOLDEN` (state 1). Members still `PENDING_APPROVAL` (state 3) get nothing until they are golden
    (the next matching run or decision makes them golden → the trigger or a later watch pass handles it; document
    the gap).
  - new watermark = max merge id seen; known-undone = the ids read undone.
- `ebx.MergeLogWatcher` — EBX-facing: for one ARMED REALTIME target and each master table of its project that
  has a metadataset (`<dataset>_<table>_MDS` in the master dataspace, CLAUDE.md §8), reads
  `/root/MergingProcess` (id, `isUnmerged`, date, user) and `/root/RecordMetadata` (`functionalId`, `state`,
  `goldenId`, `mergingProcessId`, `autoCreated`) directly (same repository, Data-Services-free, no MCP call; the
  Factory already reads these tables — see `RecordLineageReader`/`MameGroups`, and the public
  `MetadataHelper` when the add-on classes are present, guarded by `LinkageError` like `MameGroups`), applies
  `MergeLogWatch`, writes the resulting rows into the project's `DistributionOutbox` (`/root/Event`: `target`,
  `tablePath`, `recordKey`, `change`, `survivorKey`, `status=PENDING`, `createdAt`) in one `Procedure` with all
  privileges (same session as the dispatcher), and stores the new watermark on the target.
- Watermark storage: new `DistributionTarget.mergeLogWatermark` (text, JSON `{"/root/Personne":{"last":1,"undone":[]}}`
  per table path), written only after the outbox rows are committed (at-least-once: a crash between the two
  re-emits the same RETIREs, which coalesce/are harmless).
- Where it runs: `DistributionDispatcher.drainTarget`, BEFORE draining the outbox, so a merge's RETIREs are queued
  in the same pass as the golden's own trigger event. First pass on a target that has no watermark yet: initialise
  `last` to the current max merge id WITHOUT emitting anything (merges older than the arming were never sent as
  live records: `mergedRecords=exclude` on the first full send). Same rule when a target is (re)armed after a
  pause: catch-up = everything after the stored watermark, so merges made while paused ARE sent on resume.
- Ordering inside one merge: the dispatcher already groups per record and coalesces; add the rule that in one
  pass, for one target, `CREATE`/`MODIFY` rows are sent before `RETIRE` rows (golden first, then its notices),
  `RETIRE` before the `MODIFY` of other tables is not required.
- Retire mode per target: new `DistributionTarget.retireMode` enum `NOTICE` (default) | `TOMBSTONE` | `NONE`, and
  `retireUndoneMerges` boolean (default true). `NONE`: the watch still advances the watermark, writes nothing.
  `TOMBSTONE`: Kafka only — needs the Connectors lot below; until the Kafka connector supports it, the configurator
  refuses `TOMBSTONE` with a clear message naming the connector type (never silently downgrade to NOTICE).

### The retire message
`DistributionDispatcher.sendRetireNotice` document becomes
`{ "id": <recordKey>, "retired": true, "retiredTo": <survivorKey, absent when none>, "reason": "MERGE"|"UNMERGE",
  "mergeId": <merging process id, absent when unknown>, "at": <ISO-8601 UTC of the event> }`
sent through `connectors_call(connector, operation, allowWrite=true, document, inputs)` with the target's
per-table inputs (topic, key = id) exactly as a normal send uses them — today the retire call passes no
`inputs`, so on a per-table topic target it would publish on the connector's default topic: fix that. `reason`
and `mergeId` need two new outbox/event fields: `DistributionOutbox.Event.reason` (string, optional) and
`mergeId` (string, optional); mirror them on `DistributionEvent` (history) so the history shows "merged into 692 ·
merge 1". A trigger-made RETIRE (afterDelete of a golden deleted for good) keeps `reason=DELETE`? No: a plain delete
stays `DELETE`. Only the watch writes `reason`.

### History and UI (UI lot, after the engine lands)
- Step 7 real-time card: the dashed block of `Retire.dc.html` (what a merge sends — static explanation, the
  three-way `retireMode` radio, the undone-merges checkbox); wording as on the board, en/fr.
- Target history: `RETIRE` rows render "merged into <survivor> · merge <id>" / "golden deleted by an unmerge · no
  replacing id" (`RetireHistory.dc.html`); reinstated members are plain `CREATE` rows with the detail "live again
  after an undone merge".
- `docs/DISTRIBUTION.md` §5.3 rewritten to this design (keep the measured facts), mockup copies in
  `docs/mockups/wizard-flow/`, `PLAN.md` line.

### Connectors lot (separate repo `~/Dev/ebx-connectors`, in parallel)
Kafka `publish`: accept optional inputs `headers` (JSON object of string→string, written as Kafka record headers)
and `tombstone=true` (send a null value for the key; refuse when no key is resolvable). Unit-tested where the
producer is mockable; no behaviour change when the inputs are absent. Then the Factory's `TOMBSTONE` mode sends
`key=id, tombstone=true, headers={mdm-change: RETIRE, mdm-retired-to: <survivor>}`, and the `NOTICE` mode adds
headers `mdm-change=RETIRE, mdm-table, mdm-project` when the connector type is kafka.

## Not in this lot (documented as open)
- "Remove duplicates" and a record merged then deleted for good: to measure on RC2 before promising.
- `watchedFields` filtering (unchanged, still not enforced).

## Acceptance (to be measured live on RC2, project Personnes, target 2)
1. Accept another suspect pair (e.g. 348 + 528): Kafka receives, in this order, `CREATE <golden>`, `RETIRE 348`,
   `RETIRE 528` (documents as above), then the address `MODIFY`s; the history shows the two RETIRE rows.
2. Unmerge that group: `RETIRE <golden>` without survivor, then `CREATE 348`, `CREATE 528` once they are golden.
3. A target with `retireMode=NONE` receives only the golden; its watermark still advances.
4. Restarting Tomcat between the merge and the dispatcher pass loses nothing (watermark written after the rows).
