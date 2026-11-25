# Transfer rejects: the steward's review (audit F-38, wave 10)

A transfer (landing to staging, staging to master) refuses a row it cannot write and records it in `TransferReject`
(one row per project, entity, stage, source system and source id: a row rejected again updates the same reject). Until
wave 10 nothing let a person set one aside, comment it or export the list, and the unresolved ones were never purged.

## The screen

User service `TransferRejectReview`, opened on a `Project` record (Actions menu), from the Cockpit ("Review the transfer
rejects"), from the wizard's load steps (the "Review the rows" button and the open point of step 7).

- **Summary**: open / acknowledged / resolved per entity and stage, ONE aggregate SQL query for the whole project.
- **Filter**: entity, transfer (staging or master), status, a word of the reasons (case-insensitive, taken literally),
  first and last day of the rejection.
- **List**: 50 rows per page, most recent first; the count, the page and the CSV are SQL (`Adaptation.createQuery`, text in
  `rejects/RejectSql.java`): nothing reads every reject of a project into memory.
- **Acknowledge** (a comment is required): the ticked rows, or all the OPEN rows the filter matches (after a confirmation
  that says how many). The user and the time are recorded on the row (`reviewedBy`, `reviewedAt`, `reviewComment`) and in
  the kernel log.
- **Reopen**: the ticked rows, or all the ACKNOWLEDGED rows the filter matches; the comment is optional.
- **Export CSV**: everything the filter matches (not only the page), UTF-8 with a byte-order mark, a cell that starts with
  `= + - @` is prefixed with an apostrophe so a spreadsheet never runs it.

## Status and what each one does

| Status | Meaning | Purged by the retention? | Counted by the Cockpit tile and step 7? |
|---|---|---|---|
| OPEN | not resolved, not acknowledged | never | yes |
| ACKNOWLEDGED | a steward set it aside with a comment | yes, once older than `historyRetentionDays` | no |
| RESOLVED | a later load wrote the row | yes, once older than `historyRetentionDays` | no |

An acknowledged reject is set aside, not fixed: the row was NOT loaded, so the load grids of steps 4 and 5 keep counting it
as "held back". A new rejection of the same row (a later load, `StagingLoader` / `MasterLoader`) sets `acknowledged` back to
false (the last review stays on the row); a later successful load sets `resolved`, which wins.

## Rights

Reading, filtering and exporting need VIEW (the service's permission rule and the CSV request). Acknowledging and reopening
need STEWARD, asked again by `ebx.RejectReview` whoever calls it; the write runs with all privileges after that check, like the
other bookkeeping writes of the factory (the table itself stays DESIGN-write in the table view).

## Decision for the owner

Whether acknowledged rejects should instead stay out of the retention (kept as long as the project) was not decided: today they
age out with the resolved ones, as the brief asked.

## Coded reasons, groups and causes (2026-10-17)

A reject's reasons are codes (`rejects.RejectReason`), stored as English plus a marker and worded on the screen in the viewer's language; `TransferReject` carries the cause (`category`),
the grouping signature (`reasonCode`) and EBX's raw text (`detail`, folded). The screen groups identical reasons per entity and filters on one kind of reason; "Back" returns to the screen
it was opened from. A row whose parent was rejected is rejected with it ("parent rejected"). Details, tests and the integration point of the wizard: `docs/UX-STAGING-TEST-FIXES-B.md`.
