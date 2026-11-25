# Toolbars of the factory's own Project table

Written 2026-09-22 on branch `feature/project-toolbars` (worktree `ebx-mdm-factory-project-toolbars`),
implementing the product owner's decision of the same day, 16:40 (`PLAN.md` "Décisions prises"): Cockpit and
Proposal review are things done **before** the master table is generated — they make no sense on the
master tables, so they belong on the factory's own `Project` table and `Project` record form, as
toolbar buttons of the factory's module model `MdmFactory.xsd`. Removing them from the master tables
is a separate lot (`feature/master-toolbars` / `fix/master-toolbar-module-services`, see
`docs/MASTER-ZONE.md` §12) and is **not** touched here.

**Written, never deployed, never seen running** — this task ran under read-only MCP tools only
(`data-access`, `data-model` reads such as `describe_table`/`list_operations`); no
`execute_operation`, no `apply_data_model_patch`, no publish, no write against the live instance.
`tools/test_local.sh` is green (XSD regeneration diff, message-bundle ASCII check, CSS check,
compile, full test suite).

## What exists now

Three toolbars, declared in
`ebx-mdm-factory/src/main/webapp/WEB-INF/ebx/schemas/MdmFactory_toolbars.xml` and referenced from
`Project`'s `<osd:table>` in `MdmFactory.xsd`:

- **`FactoryProjectTableTop`** (`TABLE_VIEW_TOP`): the standard default part (Create, separator,
  "Actions" menu with the usual exclusions), then separator-prefixed **Cockpit**
  (`TEXT_AND_ICON_LEFT`, icon `fa-dashboard`) and **Proposal review** (`TEXT_AND_ICON_LEFT`, icon
  `fa-check-square-o`).
- **`FactoryProjectRow`** (`TABLE_VIEW_ROW`): no default part (a row toolbar decorates an
  already-visible record — no real example on this instance gives one a Create button or an Actions
  menu) — just the same two buttons, `ICON_ONLY`, separated.
- **`FactoryProjectRecordTop`** (`RECORD_VIEW_TOP`): same shape as the table-top toolbar (Create,
  Actions menu, then Cockpit and Proposal review), mirroring the convention the master-toolbars lot
  already established for its own table-top/record-top pair.

Both buttons carry **no `serviceParameters`** — see "Why bare service names work here" below.

## The module-file mechanism

A **module** (non-DMA) model declares its toolbars in a static XML file next to the XSD, named
`<xsd base name>_toolbars.xml` — `MdmFactory_toolbars.xml` next to `MdmFactory.xsd`. This is the same
row vocabulary CLAUDE.md §6 documents for a DMA model's `ebx-dma` dataset rows
(`toolbarNode`/`toolbar`/`actionButton`/`menuButton`/`separator`/`menuGroup`), just written here as
plain XML rather than as records of a technical dataspace. `tools/install.sh` rsyncs the whole module
directory, so this file deploys automatically with everything else under
`src/main/webapp/WEB-INF/ebx/schemas/` — no separate step.

Two shipped files on this instance were read in full and used as the canonical reference:

- `ebx-assistant/WEB-INF/ebx/schemas/mcp-servers-administration_1.0_toolbars.xml` — the exact shape
  of a `TABLE_VIEW_TOP` toolbar's default part (Create then Actions menu, each separator-prefixed),
  and of a `<osd:table><defaultView><toolbars><tabularViewTop>Name</tabularViewTop></toolbars>
  </defaultView></osd:table>` reference.
- `ebx-manager/WEB-INF/ebx/schemas/layoutDefinition_1.0_toolbars.xml` — confirms `<toolbars>` can
  name more than one location at once (`tabularViewTop` + `recordTop`) and the general id/parent/
  ordering convention.

Two more files, found while checking what a real `TABLE_VIEW_ROW` toolbar looks like (neither of
these two locations is illustrated by either file above), settled the row-toolbar shape used here:

- `ebx-ps-profiling-web/WEB-INF/ebx/schemas/profiling_result_toolbars.xml` (toolbar
  `indicatorResult`) — a `TABLE_VIEW_ROW` toolbar with a single `ICON_ONLY` action button and **no**
  Create/Actions default part at all.
- `ebx-cs-mima-web/WEB-INF/ebx/schemas/MigrationManager_toolbars.xml` (toolbar root `id=1`) — a
  toolbar with **two** action buttons and a plain `SEPARATOR` between them, confirming the separator
  convention also applies to a bare row toolbar with more than one button (used here between
  Cockpit and Proposal review on `FactoryProjectRow`).

Element order and locale spelling were copied exactly as measured, not guessed:

- Inside `<defaultView>`, `<toolbars>` comes **before** `<defaultViewForGroups>` — confirmed by
  grepping every `osd:table` block across this instance's own shipped XSDs that has both (e.g.
  `ebx-ps-profiling-web/WEB-INF/ebx/schemas/profiling_result.xsd`, several tables).
  `tools/xsd_emitter.py`'s `table()` now emits `<toolbars>` first when a table declares one, then the
  existing `<defaultViewForGroups>expanded</defaultViewForGroups>` — order otherwise unchanged, and
  a table with no `toolbars=` argument emits byte-identical output to before.
- Locales inside the toolbars **XML file** are written `en_US`/`fr_FR` (underscore) — that is what
  every shipped `*_toolbars.xml` on this instance actually writes, even though a live `ebx-dma`
  dataset read of the same kind of row uses `en-US`/`fr-FR` (hyphen; see `docs/MASTER-ZONE.md` §12.1
  for that live-read distinction). Copy what the shipped **file** does here — this is a file, not a
  dataset row.

## Why bare service names work here — and why the master-toolbars lot could not use them

`FactoryCockpit` and `ProposalReview` are schema-extension services declared directly on
`MdmFactory.xsd` itself (`ebx.ui.FactoryCockpitServiceDeclaration`, `OnRecord`, and
`ebx.ui.ProposalReviewServiceDeclaration`, `OnTableView`, both `ServiceKey.forName(...)` — bare, no
module prefix, CLAUDE.md §9). CLAUDE.md §6 states the general rule: *a toolbar of model X can only
name X's own schema services (bare) or MODULE-level services (`module@Service`) — never a
schema-extension service of a DIFFERENT model.* This toolbar lives on `Project`, which is a table of
`MdmFactory.xsd` — the exact same model that declares both services — so the bare name
(`FactoryCockpit`, `ProposalReview`) is correct, and no `serviceParameters` are needed: `FactoryCockpit`
is activated directly on `/root/Project` (`OnRecord`) and `ProposalReview` on `/root/Project` among
others (`OnTableView`), so EBX resolves the launching record/selection from the toolbar's own
rendering context — exactly what `ProposalReviewServiceDeclaration`'s own class comment already
describes ("the project is then resolved from whatever record is selected").

This is the opposite of what the master-toolbars lot hit, the hard way, the same day
(`docs/MASTER-ZONE.md` §12.7–§12.8): a toolbar placed on a **master** model (a different,
DMA-generated schema) tried to name `FactoryCockpit`/`ProposalReview` — first bare, then with the
`<module>@<xsdPath>@<service>` form `MdmFactory.perspective.json` uses for its own menu entries — and
both failed, deployed and measured live: *"No service with key
'ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@FactoryCockpit' could be found"*, because that
form is a **perspective menu-entry key**, not a real `ServiceKey` a toolbar button can resolve, and a
schema-extension service of a different model is simply not nameable from a toolbar at all. That lot's
fix was two new MODULE-level services (`FactoryCockpitOnMaster`/`ProposalReviewOnMaster`). Placing
Cockpit and Proposal review on `Project` instead avoids that whole detour: same model, bare name,
first try.

## Verifying (once deployed)

1. `tools/install.sh`, then in Eclipse: *File → Import → Existing Projects* (first time) or F5.
2. Restart Tomcat. A module model's toolbars file is read when the model loads; per CLAUDE.md §9 a
   `backend.mode=development` reload only ever picks up an edited XSD/toolbars file on UI navigation
   with a session, and even then not reliably for every kind of definition change — a full restart is
   the safe way to see this.
3. Open `MdmFactory`/`MdmFactory`, table `/root/Project`. Expected: the table-view toolbar shows
   Create, the Actions menu, then **Cockpit** and **Proposal review** as labelled buttons; a row's own
   row-toolbar shows the same two as icon-only buttons; opening a `Project` record shows the same
   three-button-plus-menu layout at the top of the record form.
4. If a button is silently missing (not disabled — simply absent), re-check the toolbar name spelling
   first (`tools/build_xsd.py`'s `check_toolbar_names()` only catches a mismatch between the Python
   `TABLES` declaration and `MdmFactory_toolbars.xml`'s own `<toolbar><name>` values at build time — it
   cannot catch a typo inside the XML file that also exists in the Python side, and it cannot catch
   EBX silently failing to resolve a name it never disagrees with in either file, which is the trap
   this whole section exists to flag).

## The silent-mismatch trap, and the automated guard added here

A wrong or missing toolbar name is not an error anywhere — no validation finding, no compile error,
no publish failure, just an absent toolbar at that location (CLAUDE.md §6, and confirmed live for the
master-toolbars lot's own service-key mismatch, §12.8 above). `tools/build_xsd.py` now has
`check_toolbar_names()`, run first thing in `__main__` before the XSD is written: it collects every
toolbar name any `T(..., toolbars={...})` declaration references and every `<toolbar><name>` declared
in `MdmFactory_toolbars.xml`, and exits with a two-way diff (referenced-but-undeclared,
declared-but-unreferenced) on any mismatch. This catches a rename or a typo on either side at build
time — it cannot catch EBX itself failing to resolve a name that matches on both sides (step 4 above
is still the only way to catch that).

## What was not verified

Everything under "Verifying" above — this lot never had write access to the live instance. In
particular: that the module reload actually happens after a restart, that all three toolbars render
where expected, that the bare service names in fact resolve as reasoned above (asserted from CLAUDE.md
§6 and the negative case observed on the master-toolbars lot, not independently confirmed positive
here), and that `fa-dashboard`/`fa-check-square-o` render as the intended icons (both are plain
FontAwesome class names, same convention as every icon in the shipped reference files, but not checked
against this instance's actual FontAwesome version).

## Note, 2026-10-12: master-model toolbars are bound with the NEW `set_toolbars` contract

This document is about the factory's own module model (a static `_toolbars.xml`), which needs no `set_toolbars`.
The DMA toolbars of the generated MASTER models are bound by `ebx.MasterGenerator`: since 2026-10-12 one call per
toolbar with `element_path` + `locations` + `toolbar`, falling back to the older `tabular_view_top` /
`tabular_view_row` / `record_top` single call only on the server's "does not take" refusal, and read back with
`describe_table`. Contract, measured refusal text and what to check live: `docs/MASTER-ZONE.md` &sect;12.2.
