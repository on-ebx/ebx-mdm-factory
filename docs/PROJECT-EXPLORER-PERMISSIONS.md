# Project explorer: EBX permissions

Written 2026-10-14 on branch `feat/respect-ebx-permissions` (worktree `ebx-mdm-factory-perms`), after the product owner's rule of the
same day: **EBX permissions are respected in every case**. The grid is `explorer-designer/PERMISSIONS.md` (in French) in the
EBX MCP folder. **Written and tested offline only** (`tools/test_local.sh` green, `ProjectViewTest` added); not installed,
never seen running. Public Javadoc APIs only (`SessionPermissions`, `Request.setSession`).

## Principle

`ProjectIndex` is shared by every session and read without one: it is a cache of values, never an authority. Nothing of it
reaches a page without going through `ProjectAccess` (what EBX lets this user see and do, read with the user's own session) and
`ProjectView` (the user's own cards, pure, fixture-tested).

## Layers

| Layer | What the explorer does now | API |
|---|---|---|
| Dataspace, dataset, `Project` table | Hidden: the service shows a refusal ("Access to dataset "X" is not allowed.") instead of the explorer; every AJAX request (page, star) checks again and answers `{"error":"forbidden"}` | `getHomeAccessPermission`, `getAdaptationAccessPermission(dataset)`, `getNodeAccessPermission(tableNode, dataset)` |
| Project records | Only the records a `Request` carrying the session returns: pages, totals, facet counts, search, favorites, facet vocabularies | `Request.setSession` (keys-only read) |
| Child rows (sources, staging entities, pipeline steps) | Counts, source systems and latest step computed over the rows the session returns; when it returns every row, the shared counts are reused | `Request.setSession` on each table |
| Fields, dataset level | Each field a card shows, filters, searches or sorts on: hidden → blanked on the user's copy, its line, chip, facet and sort removed. A computed value disappears with any table or field it reads (sources: table + `project`; source systems: `sourceSystem`; staging: table + `project`; activity: table + `project` + `step` + `startedAt`) | `getNodeAccessPermission(node, dataset)` |
| Fields, record-dependent rules | Evaluated per visible project during the same read (eleven fields), up to 5,000 projects; beyond, only for the cards of the answered page | `getNodeAccessPermission(node, record)` |
| Search | Per field: a word must be found inside one field, so a blanked field can never make a card match | - |
| Status hidden | The card goes to a "Status not shown" group, never to the group of its real status | - |
| Wizard button | Table service permission | `getTableActionPermissionForService(ProjectWizard)` |
| Cockpit button | Record service permission, per card | `getNodeActionPermissionForService(FactoryCockpit, tableNode, record)` |
| Delete button | Administrator AND table service permission | `getTableActionPermissionForService(ProjectDelete)` |
| New project | DESIGN right AND wizard permission AND creation right on `Project` | `getTableActionPermissionToCreateRootOccurrence` |
| Star (favorite) | Adding a pin needs a project the session returns (else answered as an unknown project); removing one's own pin stays possible; pins on invisible projects are neither shown nor counted | visible set |

The explorer launches no workflow: `WorkflowEngine.isUserAuthorizedToLaunch` has nothing to gate here.

## Cache

Per user and dataset (`ProjectAccess.Snapshot`), tied to the `ProjectIndex` instance it was built from AND to 60 seconds: a
change of role or permission moves no table date, so a revoked right may show for up to one minute. At most 500 entries,
expired ones dropped first. Cost per user and per minute: one keys-only read of `Project`, `ProjectSource`, `StagingEntity` and
`PipelineStep` with the session, plus eleven node checks and one service check per project. A build over two seconds is logged
(`project explorer permissions of <user> read in N ms`): measure it on Vol100k before calling it done.

## What stays approximate

- Record-dependent field rules beyond 5,000 visible projects: the shown cards are masked, but the search, counts and sort of that
  answer may still use a value such a rule hides (logged once).
- Record-dependent field rules on the child tables' fields (`ProjectSource.sourceSystem`, `PipelineStep.step`...) are not
  evaluated: the dataset-level rules and the record access rules apply, not a rule that hides one field on one child row.
- The `Favorite` rows themselves (the user's own pins, a technical table the module writes with all privileges) are read without
  a session, by login; only their targets are filtered.
- The refusal text is the module's own (EBX's `AccessPermission` gives no public reason for a hidden permission).
- The links still carry the project key when the `code` field is hidden (EBX's own record links carry the key too); the Wizard,
  Cockpit and Delete screens must check their own record, see "Other screens" below.

## Test plan after deployment (live)

Prepare on the factory dataset (scratch copy or the RC, never a demo dataset), with a non-administrator user `u1` holding the
Factory right VIEW and a role `R1`:

1. **Record access rule.** Programmatic or DMA record rule hiding project `P2` from `R1`. As `u1`: `P2` is not in any group, not in
   the "N / M" counter, not found by its label or code, its owner and source systems are not facet values (unless another visible
   project carries them), a star on it never shows. Pin `P2` as admin for `u1`'s login (Data Services on `Favorite`): the "My
   favorites" count of `u1` does not include it. Compare with the native `Project` table view of `u1`: same projects.
2. **Field hidden for a role.** Hide `owner` for `R1` (dataset permissions or DMA). As `u1`: no owner line, no Owner facet,
   searching an owner's name finds nothing. Hide `ProjectSource.sourceSystem`: no source systems on cards, no Source system pill
   row, the count line stays. Hide the `PipelineStep` table: no activity line. Hide `createdAt`: no creation date, no "Creation
   date" sort.
3. **Record-dependent field rule.** A rule hiding `description` when `status = LIVE`. As `u1`: LIVE cards have no description and
   are not found by a word of it; DRAFT cards still are. Same check on `status`: the card sits in "Status not shown".
4. **Child row rule.** A record rule hiding half of a project's `ProjectSource` rows from `R1`: the card's source count is the
   visible half, as in the native `ProjectSource` view of `u1`.
5. **Services.** Remove `u1`'s right on the `ProjectWizard` service (service permissions): no Wizard button, no New project.
   Remove creation on `Project`: no New project. Disable `FactoryCockpit` for `R1` on some records: no Cockpit button there.
   `u1` (not admin) never sees Delete.
6. **Workflow.** A role without the launch right on any Factory publication: nothing to check in the explorer itself (no
   workflow button); check the Wizard and Cockpit screens instead (see below).
7. **Closed dataset.** Dataset permission "hidden" for `R1`: the explorer opens on the refusal notice; an AJAX URL replayed by
   hand answers `{"error":"forbidden"}`.
8. **Cache.** Revoke a right and reload at once: the old view may last up to 60 s, never longer.

Assertion for every step: no card, counter, search hit, facet value or button goes beyond what EBX's standard table view shows the
same user.

## Other screens

Covered by the second pass (2026-10-14, branch `fix/permissions-remaining`): the Wizard, Cockpit and Delete screens check the project
record the explorer's links open (a hidden project is answered like an unknown one), and every other screen, reader and REST route
listed here before follows the same rules. Layers, what stays approximate and the live test plan: `docs/PERMISSIONS.md`.
