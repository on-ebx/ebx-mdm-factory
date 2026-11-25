# Domain accelerators for the Centralisation style

Design note, 2026-10-07, branch `docs/centralisation-accelerators`. **Status: design only, to be validated. Nothing coded, nothing measured.**
It details lots C1-C3 of the "other styles" plan (`PLAN.md`, "Style d'implémentation") for the Centralisation style: data is entered in the hub,
and the model comes from a specification, not from sources.

## 1. Principle (decided 2026-10-07)

- An accelerator **pack** is the starting point, **not a fixed template**. The language model takes the chosen pack and the customer's
  specification, then proposes an adapted model: what it keeps from the pack, what it adds, what it removes or renames. A person decides
  every difference, the same way staging proposals are decided today.
- "Going further" than the pack is expected: new tables, fields, rules, workflow steps, roles. The pack is material for the model, not a limit.
- The Factory's rule still holds: **suggestions come from the language model, never from Java heuristics per domain**. Java holds the
  catalogue of packs and building blocks, checks what the model proposes and generates EBX objects. It does not decide what a domain contains.
- **Start from what exists, then adapt it.** Packs, building blocks and workflow types are accelerators, not finished projects. The model is
  told they exist and starts from the closest one rather than from a blank page, then adapts it to the specification as far as needed:
  fields renamed, added or removed, a workflow step added, roles changed. Every adaptation is a difference the person decides (section 4).
  Two kinds of pieces, adapted in two ways:
  - **Copied pieces** (pack content, building blocks, workflow types): the project gets its own copy at generation, and that copy is freely
    editable. Changing it never affects another project or the library.
  - **Shared pieces** (published script libraries): a library is shared by every model that imports it, and a publication cannot be withdrawn
    (CLAUDE.md §3). So it is not edited per project. It is **wrapped**: the project's own rule calls the library and adds its own logic
    (for example `phone.isValid` plus "a mobile number is required"). If the change is general, it goes into a new version of the
    library, as a decision of the library's owner.

## 2. What the library contains

### 2.1 Cross-domain building blocks

These are groups or child tables added to any table of a generated model. Each block carries its own fields, its script constraints or
triggers, and its labels.

| Block | Content | Existing basis on the instance |
|---|---|---|
| Postal address | structured address, country as a foreign key to an ISO reference, composed address computed by trigger, optional geocoding | Companies (address, map pane), Material triggers |
| Contacts | E.164 phone, e-mail, URL, with script constraints | `Fasttrack.lib.phone`, `Fasttrack.lib.validator.*` |
| Audit | created on/by, changed on/by, a forged creation date written back | Material `beforeCreate` / `beforeModify` triggers |
| Life cycle | status (draft, in review, active, blocked, archived), effective dates, blocking reason | to build |
| External identifiers | child table type / value / issuer (SIREN, SIRET, LEI, VAT, GTIN, DUNS), check-digit constraint | Companies `CompanyIdentifier`, GLEIF and recherche-entreprises lookups |
| Translations | localized labels on reference data | to build |
| Attachments | DAMA media field, delivered with its drive and `digitalAssetComponent` (CLAUDE.md §11) | Material `MaterialDocument` |
| Hierarchy | parent/child on the same table, cycle guard, computed path | to build |
| Completeness | scripted function field: fill rate and "ready to publish" | Companies e-invoicing `readiness` |

### 2.2 Domain packs

| Pack | Core | Documented options | Existing basis |
|---|---|---|---|
| Shared reference data | countries, currencies, languages, units, incoterms, payment terms, legal forms, preloaded | translations | to build (prerequisite of every other pack) |
| Organisation / Company (B2B) | company, identifiers, addresses | group hierarchy, e-invoicing, bank accounts, GLEIF/INSEE enrichment | Companies |
| Person / B2C customer | person, contacts, addresses, sensitive columns flagged | GDPR consents (purpose, channel, date, proof) | `B2CCustomers`, Personnes / PersVol tests |
| Supplier | supplier, bank data (IBAN/BIC checked), payment terms | evaluation, compliance documents with expiry | to build |
| Item / Material | basic view, units and conversions, classification | per-organisation views (purchasing, sales, stock), documents, tender | Material (MARA structure) |
| Commercial product | product range hierarchy, attributes per family | media, distribution channels | to build |
| Site / Point of sale | site, opening hours, geolocation, market | brands | PointOfSale |
| Employee / Organisation | org chart, cost centres, dated assignments | | HumanResources |
| Asset / Equipment | serial number, location, functional hierarchy | maintenance cycle | to build |
| Medical device | UDI, risk class | EUDAMED link | EUDAMED model |
| Finance | chart of accounts, legal entities, local-to-group mapping, dated validity | | to build |

### 2.3 Workflow types

Each type is parameterised per table at generation: the table, the roles, the steps to keep, and the labels of the accept and reject
buttons. The workflow-modeling MCP server builds and publishes them, as Data Services already does for what that server cannot write
(CLAUDE.md §7).

1. **Creation request with review**: child dataspace, then creation step (`ebx-root-1.0@creation`, output `created`), then review, then
   merge and close, with a rework loop. Basis: `CompanyCreationApproval`, `MaterialCreationRequest`.
2. **Search before create**: `ebx-addon-mame@SearchBeforeCreate`, then route on `Action` (create, update, cancel). Choosing an existing
   record turns the creation into an extension.
3. **Change request**: shows before and after, and asks for review only when a sensitive attribute changed (IBAN, legal name); other changes
   apply directly.
4. **Organisational extension**: an existing record extended to a new organisation, one review per organisation through parallel
   sub-workflows.
5. **Block / unblock / archive**: mandatory reason, dependency check before archiving.
6. **Multi-level approval**: parallel approvers (data, finance, compliance), decisions consolidated at the end.
7. **Periodic review / recertification**: a scheduled task launches through the workflow REST API (CLAUDE.md §2: no workflow launch from
   EBX Script), then the records due for review are reviewed. Basis: Material expiry alert.
8. **Suspect review**: queue of Match and Merge suspects, decided by a data steward.
9. **Bulk request**: a file imported into a workspace, validation report, one approval for the batch.

### 2.4 Script libraries

- **Validation**: phone, e-mail, IBAN/BIC, intra-EU VAT, SIREN/SIRET (Luhn), GTIN/EAN, LEI, postal codes per country. Phone and the
  commons-validator port exist; the rest is to write. Exported signatures must follow CLAUDE.md §2: `decimal` parameters, no collection
  return type.
- **Normalisation**: case and spaces, legal forms, match key (accents kept, decided 2026-09-22), composed address.
- **Enrichment**: GLEIF, recherche-entreprises, geocoding, through "External services" configurations. Outbound calls are capped at 500 ms or
  2 s per context (CLAUDE.md §2), so enrichment belongs in a workflow step, not in a trigger.
- **Computation**: completeness, next review date, derived status.
- **Notification**: a link that opens a work item (CLAUDE.md §7), message bundles in English and French.
- **REST services**: read a golden record, duplicate check before create for calling applications.

### 2.5 What every generated domain comes with

- a record form as a **Layout Designer layout** (module `ebx-layout-designer`, `~/Dev/ebx-layout-designer`: a JSON layout read by
  `ConfigurableForm` at each display, panels, `showIf`, blocks such as hierarchy, duplicates, links, record info, checklist, changes;
  editable in the Designer after generation, no restart) when that module is on the instance; otherwise a native custom form
  (`customLayout` extension of the DMA model, CLAUDE.md §5). The Designer is optional: the Factory stays installable without it;
- a perspective with three sections (Entry, My requests, Review), built with `ebx-perspective-admin`;
- roles (Requester, Reviewer, Data steward) and the matching dataspace and dataset permissions;
- a real-time matching policy for uniqueness at entry (mode 2 of the matching trigger modes);
- outbound flows to subscribers (Connectors outbound mappings), configured later in the Distribution step;
- an EBX Staging delivery archive (`docs/DELIVERY.md`).

## 3. Format of a pack

A pack is a versioned resource of the module, one folder per pack. It is data, not code.

```
accelerators/
  blocks/<block>/        block.json (fields, types, constraints, triggers declared), scripts/, labels
  packs/<pack>/
    pack.json            identity, version, description, prerequisite packs
    core.json            tables, fields, keys, relations (same vocabulary as the DMA patch operations)
    options/<option>.json  extension, when to propose it, what it adds
    guidance.md          domain knowledge given to the model: what the tables mean, usual pitfalls, typical customer variations
    workflows.json       workflow types used, per table, with their parameters
    matching.json        suggested match fields and decision-tree outline (sent to match-merge, never applied as is)
    reference-data/      rows preloaded with the pack
    layouts/<Table>.json record form of each table, in the Layout Designer's format (data, like the rest of the pack)
```

- `layouts/*.json` reference the fields by path, so an adaptation of the model (field renamed, added, removed) is followed by an
  adaptation of the layout, proposed in the same pass and decided like any other difference (section 4). When the model moved too far,
  the Factory regenerates a default layout (one panel per group, blocks for the associations) instead of patching the pack's.

- `core.json` and `options/*.json` use the operation vocabulary of `apply_data_model_patch` (`create_table`, `create_field`, `add_foreign_key`...).
  A proposal is therefore a list of operations that is diffed against the pack's own list.
- `guidance.md` is the main text the model reads. It explains the domain; it does not list rules to apply.
- The packs built on existing models (Companies, Material) are extracted from those models, then reviewed. They are not rewritten by hand.

## 4. Path through the wizard

Step 1 keeps the style choice. Choosing Centralisation replaces the source steps with the following ones.

1. **Pack choice.** The user describes the need in a few lines. The model suggests one pack, or a combination (for example Company plus
   Supplier), and the options it would take. The user confirms or picks another pack, or skips the packs ("Skip the packs, start
   blank", 2026-10-14): a pack is an accelerator, never a requirement. With no pack the need is the only input; it is kept on the project
   (`Project.accelSpecification`, `Project.accelBlankStart`) and the passes design the whole model from it (every table is an ADD carrying
   its fields, blocks, libraries and workflow types of the catalogue still offered).
2. **Specification.** One of three forms: free text, a workbook (the Excel form planned in `PLAN.md`), or an existing model to take over.
   A specification is optional: the pack alone is a valid start.
3. **Adapted proposal.** The model receives the pack (core, options, guidance), the building blocks catalogue, the published libraries and
   the specification. It answers with a list of differences, each with a nature and a reason:
   - `KEEP`: pack element kept, shown collapsed;
   - `ADD`: table, field, rule, workflow step or role that the pack lacks;
   - `CHANGE`: renamed, retyped, made mandatory, moved;
   - `REMOVE`: pack element not needed;
   - `USE_BLOCK` / `USE_LIBRARY` / `USE_WORKFLOW`: an existing piece the model picked for a need of the specification, as is or with its
     own adaptations listed under it (a block field renamed, a workflow step added, a library call wrapped in a project rule). Each
     adaptation is decided like any other difference.
4. **Decision.** Each difference is accepted, rejected or edited, in a screen built like the staging proposal review. The differences from the
   pack are highlighted, so what is specific to the customer stands out.
5. **Generation.** In this order: DMA model (patch + publication), dataset, script bodies, workflows, perspective, form, matching (profile,
   then policy through match-merge, then activation), permissions. Each object is traced in `PipelineStep`, so step reset and project
   deletion work as they do for Consolidation.
6. **Optional initial load.** A one-off Consolidation from the customer's existing sources into the new master, with the existing landing,
   staging and master steps.

## 5. Guardrails on "going further"

| Free | Checked | Refused |
|---|---|---|
| add, change or remove tables, fields, relations, pack content included | a new validation rule is written by the model as a draft and reviewed by a person (`validate_draft`, never published unreviewed, CLAUDE.md §3); a rule that only needs more than a library does wraps the library | editing a published library for one project (it is wrapped instead, section 1) |
| adapt a building block: rename, add or drop fields, change its triggers | matching goes through match-merge (profile, generate, validate); the model's outline is input, not configuration | anything published before the person decided it |
| adapt a workflow type: add, remove or reorder steps, change roles, labels, notifications | every generated object must pass the EBX gate that exists for it (`validate_data_model`, `workflow_model_check_json`, `validate_matching_config`) | |
| combine packs | a workflow is described by its business steps and generated by the Factory, which writes the EBX details known to fail silently (output mapping order, `scriptClass`, agent access, CLAUDE.md §7) | |

- When an `ADD` looks like an existing block, option or library, the Java check points this out to the model once and asks whether it
  should start from that piece. The person sees both versions and may keep the new one: the point is to avoid rewriting by accident, not to
  forbid it.
- A difference that comes back from customer to customer becomes a candidate for the pack (new option, or core). This is a decision of the
  pack's owner, not an automatic rule.

## 6. First lot (reduced, 2026-10-12)

Kept small on purpose: the measurements of section 7 decide the rest.

1. **Shared reference data pack**, the prerequisite of every other pack.
2. **Audit and Contacts blocks**, already proven on Material and Companies (Postal address comes with the second lot: its
   geocoding and composed-address trigger depend on measurement 3).
3. **One workflow type: creation request with review** (type 1 of 2.3). Search before create (type 2) waits for measurement 1,
   since it only makes sense once uniqueness at entry works on a DMA table.
4. **Organisation / Company pack, reduced to what the `data-model` server can generate** (section 6a): core tables, fields, keys,
   foreign keys, script constraints and triggers, native custom form. The Java form, the toolbars and the cross-dataset
   associations of the current Companies model are NOT part of the pack.

Wizard steps 1 to 5 of section 4 are covered for this lot. The initial load (step 6), the other packs and the other workflow
types come after.

### 6a. What a pack cannot carry

A pack is generated through the `data-model`, `scripting`, `workflow-modeling`, `match-merge` and `ebx-perspective-admin` servers,
so it can only hold what those servers write. Out of a pack, by construction:

| Not in a pack | Why | What to do instead |
|---|---|---|
| Project Java (a `UIForm`, a `UserService`, a Java trigger written for this pack) | a pack ships no code of its own; the target instance has nothing to deploy | script triggers and constraints; the record form as a Layout Designer layout; a Java screen stays a separate module delivered with the pack's documentation |
| — but a pack MAY require **runtime modules** that exist independently of it (Layout Designer, Match and Merge, DAMA) | they are products or shared runtimes, not project code; `pack.json` lists them under `requires` | the generation checks them first and says which one is missing and what will be degraded (Layout Designer absent = native custom form instead of the pack's layout; Match and Merge absent = no uniqueness at entry) |
| `xpathLink` associations, associations to another dataset or dataspace | `set_association` accepts `tableRefInverse` only (CLAUDE.md §6) | model the link as a foreign key, or declare the association in the DMA UI after generation |
| Toolbars with buttons | the `data-model` server creates an empty toolbar root only (CLAUDE.md §6) | EBX's default toolbars; a custom toolbar is a post-generation step |
| Workflow steps outside the known node types (a custom `WaitTaskBean`, a Java user-task bean) | they need a `module.xml` declaration and a restart | the workflow types of 2.3, and a script task where logic is needed |
| Secrets of external services (GLEIF key…) | written through Data Services they are stored in clear and fail at call time (CLAUDE.md §2) | the pack names the external service; the secret is entered in the UI after generation |
| Per-profile dataset and dataspace permissions | no REST route (CLAUDE.md §9); the data-model server's access-rights operations are measurement 5 | generated when measurement 5 passes; UI until then |

A pack author who needs one of these writes it in the pack's `guidance.md` as a manual step, so the generation summary lists it.

### 6b. The proposal runs in several model passes

One pass with the whole pack, the blocks catalogue, the library signatures, the workflow types and the specification is too large
and too slow (the staging passes already take minutes per entity). The proposal of step 3 (section 4) is made in four passes,
each taking the decided result of the previous one as input:

1. **Model**: tables, fields, keys, relations, blocks (`ADD`, `CHANGE`, `REMOVE`, `USE_BLOCK`, `KEEP`). Input: pack core and
   options, blocks catalogue, specification.
2. **Rules and checks**: constraints, triggers, function fields (`USE_LIBRARY`, `ADD` of a draft rule). Input: the model as decided
   in pass 1, the published libraries with their signatures, the specification.
3. **Record forms** (decided 2026-10-12): one Layout Designer layout per table, proposed from the pack's `layouts/<Table>.json`, the
   decided model and the block catalogue (`layout_schema`); checked by `layout_validate` before it is shown; the technical associations
   of the add-ons are never placed. Input: the model as decided, the pack layout, the format and block catalogue.
4. **Workflows and roles**: workflow types with their parameters, roles, perspective sections (`USE_WORKFLOW`, `ADD`, `CHANGE`).
   Input: the model and rules as decided, the workflow types, the specification.

The person may decide pass 1 before pass 2 runs (the default), or let the four run and decide everything at once. A pass is
traced in `PipelineStep` and `AgentRun` like the staging passes, so a pass can be re-run alone after a decision changes its input.

## 7. Measurements before coding

None of these has been measured on a DMA model yet:

1. ~~Real-time matching (`BEFORE_CREATION` / `INLINE_MATCHING`) on a DMA publication table~~ — **measured 2026-10-12, passed with
   `INLINE_MATCHING` only** (section 10).
2. ~~A Layout Designer layout generated for a DMA model~~ — **measured 2026-10-12, passed** (section 11). Still open: a layout's
   blocks on generated associations (the probe has none), and a layout row written by the Factory itself.
3. ~~Script triggers and constraints declared by `set_script_trigger` / `set_script_constraint` on a model generated in one pass~~ —
   **measured 2026-10-12, passed** (section 13).
4. ~~Workflow types 1 and 2 generated for an arbitrary table through workflow-modeling~~ — **type 1 measured 2026-10-12, passed end
   to end** (section 14). Type 2 (Search before create) is not measured: the `SearchBeforeCreate` task is known from
   `MaterialCreationRequest` (CLAUDE.md §7) and only needs the policy of measurement 1.
5. ~~Permissions written by the data-model server's access-rights operations for the three roles~~ — **write path measured
   2026-10-12, passed, and seen applied to a non-administrator** (section 15).
6. ~~A language-model round trip on the Company pack plus a sample specification~~ — **measured 2026-10-12** (section 16): the
   one-shot prompt times out, the four-pass design of section 6b works, with a Java check needed on every answer.

## 8. Open questions

- Where packs live: inside the module (versioned with the Factory) or in a dataset of the Factory (editable without a release). The proposal
  is the module for the shipped packs, plus a dataset later for customer packs.
- ~~Whether a generated domain keeps a link to its pack's version~~ — decided 2026-10-12: the project records the pack versions and
  the decided differences (what the generation mockup's traceability block shows); a later pack version is proposed as differences,
  never applied.

## 9. Mockups (2026-10-12)

`docs/mockups/accelerators/` — three screens of the Centralisation path, in the wizard's own style, drawn as deltas of the existing
wizard (rail, cards, decision rows of the staging proposal review, "new" markers on what did not exist before):

- `PackChoice.dc.html` — step 2: the need in a few lines, the packs suggested by the model (two selected, "why these two"), the
  counters of what the selected packs bring.
- `ProposalReview.dc.html` — step 4: one row per difference (`USE BLOCK`, `USE LIBRARY`, `USE WORKFLOW`, `ADD`, `CHANGE`, `REMOVE`,
  `KEEP`), each with the model's reason and Accept / Edit / Reject; an adaptation of a block or a workflow type is a sub-line of its
  `USE` row; a wrapped library and a draft rule are shown as such; the "looks like something that exists" check is a box, not a refusal.
- `Generation.dc.html` — step 5: the eight generated objects through their EBX gates, the draft rule held back until read, the
  traceability block (pack versions, decisions, who, when).

The `.png` next to each file is its rendering (headless Chrome, 1280 px).

## 10. Measurement 1 — uniqueness at entry on a DMA table (2026-10-12, 6.3.0-rc3)

Scratch model `AccelProbe` (`/root/Supplier`: id auto-increment, name, siren, postcode, city, email), add-on `ebx-addon-mame` declared
through `add_addon`, published, dataset `AccelProbe` in `Reference` through `create_dataset`, policy `Supplier_entry` written with
`generate_matching_policy` (tree: SIREN exact → MATCHED; else name Jaro-Winkler ≥ 88 and postcode exact → SUSPECTED), `activate_matching`
→ `ENABLED`, metadataset created, no restart at any point. The whole set-up took about four minutes of tool calls. Records created
from the native "+" form of the table view, by an administrator.

| Mode (`matchingOnCreation` / `matchingOnUpdate`) | What happened on save |
|---|---|
| 2 = "Before creation" | **Nothing.** A second record with the same SIREN was saved with no screen; no engine line in `ebx-addon-mame.log`, metadataset empty. The add-on's own guide says this mode only lets "users search for potential duplicates before creating the record": it is the `Search before create` service in Actions → Match and Merge, not an interception of the form. |
| 3 = "Inline" | **The record is committed** (`CreateOccurrenceProcedure` 10:18:12.996 → commit 10:18:13.982), then the form is replaced by `service=ebx-addon-mame@InlineMatching&serviceMode=RECORD`: the record on top, below it "N potential matches have been found for this record" with a MATCH / SUSPECT badge per candidate, `Merge` (opens the 3-step manual merge wizard) and `Reject all duplicates`. Review shown ≈ 1.5 s after Save on a 3-row table. |

Facts for the generator:

- **Use mode 3 on creation AND on update.** Mode 2 is not "uniqueness at entry"; mode 1 (after submission) matches without any user screen.
- **The duplicate exists in the table while the user decides**: a reviewer who closes the page keeps two records. The creation workflow must
  therefore follow the review (the workflow's creation step, or a `Suspected` check in the next script step), or run the entry in the
  workflow's child dataspace.
- **`Reject all duplicates` writes one `FalsePositiveDecision` row per pair** (`sourceId` = the new record, `targetId` = the candidate) and makes
  the new record Golden in its own group; the candidates keep no metadata. **A rejected pair is never shown again**: when record 2 was later
  renamed to the exact name of record 1, the inline review listed record 1 (MATCH) but not record 3, whose pair had been rejected.
- **On update the review opens only when a field of the policy changes**: changing `city` (not a matching field) committed silently with no
  engine run; changing `name` opened the review. So a pack's policy must list every field the business considers identifying.
- Nothing is written in `RecordMetadata`/`Suspected` by the inline run itself; the metadataset fills on the user's decision. A screen that
  counts "pending duplicates" cannot rely on it for inline entries.
- Not measured: cost on a large table (the FastTrack volume notes give 0.05 ms per row for the trigger, the inline run is a candidate
  search), the behaviour through Data Services (no session in this measurement), and a non-administrator user.

The probe is kept for measurements 2 and 3 (layout, scripts on a generated model) and deleted after them (matching config, dataset
through the UI, then `delete_data_model`).

## 11. Measurement 2 — a Layout Designer form on a DMA model (2026-10-12, 6.3.0-rc3, Layout Designer 1.8.1)

On the probe of section 10: `execute_operation(set_form_layout, {"element_path": "/root/Supplier", "class_name":
"com.on.ebx.layout.runtime.ConfigurableForm"})` then `publish_data_model`. Result:

- **No restart at any point.** Right after the publication the record form was still EBX's standard one (no active layout yet — exactly
  what the Designer's INSTALL.md promises), the dataset kept working, nothing in `kernel.log`.
- The Designer opened on the table through its web component (`/ebx/?branch=Reference&instance=AccelProbe&xpath=/root/Supplier&service=ebx-layout-designer@LayoutDesigner`)
  with "Starting layout, built from the model" and the chip "DMA model: check the declaration"; one `Save` wrote the `FormLayout` row
  (`model = urn:ebx:publication:AccelProbe`, `table = /root/Supplier`), and **the next opening of a record showed the designed form**
  (tabs, panel "General"), no restart, no republication.
- **The starting layout built from the model includes the add-on's `Association_Reverse`** (the Match and Merge metadataset link) as a
  second tab. A layout generated by the Factory must leave out the add-on's technical associations (`Association_Reverse`, the
  `Linked_Field_*` fields) and only place the business ones.

Facts for the generator:

- A pack's `layouts/<Table>.json` is written as a row of `LayoutDesigner/LayoutDesigner/root/FormLayout` (`model`, `table`, `active`,
  `layout`, `version`, `label`): a plain table, so the Factory writes it like any other data (Data Services or a `Procedure`), then
  `set_form_layout` + `publish_data_model` on the model. Two tool calls and one row: no Designer click is needed.
- The Designer stays the editing screen after generation ("open in the Designer" on the generation mockup); `previousForm` lets the
  Designer put a previous `UIForm` back, so a pack built from Companies can keep the Java form as the fallback.
- The `model` key of the layout is the publication URN (`urn:ebx:publication:<name>`), i.e. the generated model's publication name:
  the Factory knows it before the layout is written.

## 12. Prerequisite: a layout service on the Layout Designer (decided 2026-10-12)

Measurement 2 showed the form generation is two tool calls and one row of `LayoutDesigner/FormLayout`. The Factory does not write
another module's table (rule of 2026-10-07: siblings talk through a versioned in-JVM contract), and a layout proposed by the language
model must be checked against the model before anyone sees it. The Designer therefore gets a `LayoutService` exposed two ways —
an in-JVM contract MBean for the Factory, a hand-rolled JSON-RPC endpoint `/ebx-layout-designer/rest/mcp` for agents (a custom module
cannot register a real EBX MCP server) — with these operations:

| Operation | Role |
|---|---|
| `layout_schema` | the format, the block and widget catalogue, the behaviours (what the Designer's palette knows) |
| `layout_starting` | the starting layout built from the model, as the Designer proposes it, without the add-ons' technical associations |
| `layout_validate` | paths resolved against the model, block keys, locales; a report, never a write |
| `layout_get` / `layout_put` | the `FormLayout` row: `layout`, `active`, `version` (incremented), `previousForm`, `label` |
| `layout_declare` | the declaration of `ConfigurableForm`: `set_form_layout` on a DMA model, the XSD on a module model (the Designer's Activation) |

The lot is opened in the Designer's repository (`docs/LAYOUT-API.md`, branch `feature/layout-api`). The Factory's generation step
"Record form" (mockup, section 9) calls `layout_validate` then `layout_put` then `layout_declare`; until the lot ships, the row is
written directly and the declaration goes through `set_form_layout`, as measured.

## 13. Measurement 3 — scripts declared, written and published in one pass (2026-10-12, 6.3.0-rc3)

On the probe: `execute_operation(set_script_trigger, {"element_path": "/root/Supplier", "name": "AuditOnCreate", "type": "beforeCreate",
"scope": "table"})` and `execute_operation(set_script_constraint, {"element_path": "/root/Supplier/siren", "name": "SirenCheck", "type":
"ConstraintOnNode", "scope": "node"})` (both `publishSafe: true`), then on the scripting server `get_script` (revision) + `update_script`
(`confirmReplaceMetadata: true`) for each body, **one** `publish_data_model`, no restart. Both bodies validated at the first attempt:

- the trigger fills `city` when the user left it empty (stands for the Audit block's stamps);
- the constraint **wraps a published library** as section 1 prescribes: the project rule (`siren.length <> 9` → `addError`) then
  `import Fasttrack.lib.validator.checkdigit.luhn as luhn; if not luhn.isValid(record.siren) then addWarning(...)`.

Seen in the form of the table (the Layout Designer form of measurement 2, which kept working through the republication):

- an 8-digit SIREN: `A SIREN has 9 digits.` under the field, "1 error" on top, **Save refused** — a constraint error blocks the UI save
  (a Data Services write would store the row and only flag it, CLAUDE.md §2);
- `123456780`: the warning `The SIREN check digit does not balance (Luhn).` shown, Save accepted, the record created with
  `city = UNKNOWN (set by trigger)` — trigger and library call both live right after the publication.

Also observed: with no candidate, the inline matching of measurement 1 made the new record Golden in its own group at once
(`RecordMetadata` row written); so the metadataset is empty only while a review is pending, not for every inline entry.

Facts for the generator: the three-call sequence per script (declare, write body, publish once at the end) is enough; the bodies can be
validated by `update_script` before the single publication, so a generation can stop before publishing when a body fails; the
Luhn library's signatures were read from its source (`get_api_help` does not know published libraries), which is what the
`USE_LIBRARY` catalogue must carry.

Measurements 1-3 are passed; the probe stays for 4 (workflow types on `/root/Supplier`) and 5 (permissions on `AccelProbe`).

## 14. Measurement 4 — workflow type 1 generated and run (2026-10-12, 6.3.0-rc3)

Model `AccelProbeSupplierCreation` written as the generator would write it, from the shape of `FournisseursCreationTiers` (the supplier
demo, 2026-10-10): 9 nodes — `ScriptTaskCreateBranchI18n` (child of `Reference`, params `initialBranch`, `label`, `description`,
`ensureBranchClosingOnWorkflowTermination`, output `newHome → workDataspace`), creation task (`ebx-root-1.0@creation`, inputs
`service=@creation`, `branch`, `instance`, `xpath`, output `{"name": "created", "value": "supplierXPath"}`), `ConditionIsLastUserTaskAccepted`,
review task (`@default`, `xpath=${supplierXPath}`, `firstCallDisplay=record`, buttons "Approve supplier" / "Send back"), condition,
`ScriptTaskMergeBranch`, `ScriptTaskCloseBranch`, correction task (`previousUserTaskIdReferenceForAllocation`), condition. Variables
`workDataspace` (inout) and `supplierXPath` (inout, default `-`). Launching quick + synchronous, every task offered to `Badministrator`
for the test, `create` and agent access to `Badministrator`.

`workflow_model_import` → `workflow_publication_publish` (NEW): accepted first time. Run from the launcher web component
(`/ebx/?branch=Reference&instance=AccelProbe&xpath=/root/Supplier&service=@workflowLauncher&publicationName=AccelProbeSupplierCreation`,
which lands back on the table, as known) then from the inbox:

1. "Enter the new supplier" (Take and start): the creation form of `/root/Supplier` in the work dataspace — **the Layout Designer form,
   the trigger (`city` filled) and the inline matching (record Golden at once) all ran in the child dataspace**, the metadataset having
   been fanned out to it. After `Save`, the "Submit for review" button showed (the task's buttons sit in the task bar, not in the form).
2. "Review the supplier" opened **on record 6**: the output mapping written as `{"name": "<service output>", "value": "<variable>"}` —
   the orientation CLAUDE.md §7 gives, opposite to the MCP's own documentation — is the working one, confirmed end to end.
3. "Approve supplier" → `ScriptTaskMergeBranch` ran as `ebx-systemUser`, record 6 is in `Reference` with its trigger value, the work
   dataspace is closed (not among Reference's children). Nothing in `workflow.log`.

Facts for the generator:

- The type-1 skeleton above is complete and parametric: table path, dataset, dataspace, reviewer profiles, labels. Two reviewers in
  sequence (the Purchasing → Finance of the mockup) are one more user task + condition pair; parallel reviewers are a `subWorkflow`
  node (CLAUDE.md §7).
- A domain generated with matching at entry (measurement 1) gets the duplicate review inside the workflow's child dataspace, and a
  cancelled request closes that dataspace: this answers the "duplicate exists while the user decides" point of section 10 for
  entries made through the workflow.
- `launching.synchronous: true` does not open the first task from the launcher URL (known); the one-click pattern is the Java
  `RequestLaunchService` of `material-model` or the work-item link. For the accelerators, the perspective's "New request" action should
  be a work-item link built after the launch, not the launcher URL.
- Not measured: a non-administrator requester (dataspace and dataset permissions of the child, CLAUDE.md §9's three locks), the
  "Send back" loop, and type 2.

## 15. Measurement 5 — model permissions written by the `data-model` server (2026-10-12, 6.3.0-rc3)

On the probe, three profiles as a pack would declare them (Requester = `RUser`, Reviewer = `RSteward`, everyone else read-only):

| Call | Result |
|---|---|
| `set_permission_value_access_default {profile: RUser, access: W}` | profile record created; `RW` / `R-` are refused — the flags are `W`, `R`, `H` |
| `set_permission_table_default_actions {profile: RUser, create_allowed: true, overwrite_allowed: true, delete_allowed: false, occult_allowed: false}` | ok |
| `set_permission_table_default_actions {profile: RSteward, create_allowed: false, overwrite_allowed: true, …}` | profile record created |
| `set_permission_value_access_specific {profile: RSteward, node_path: /root/Supplier/siren, access: R}` | ok |
| `set_permission_value_access_default {profile: Beveryone, access: R}` | profile record created |
| `execute_query(get_data_model_permissions)` | the three profiles read back as written — **except `RSteward.valueAccessDefault = "H"`** |
| `set_permission_value_access_default {profile: RSteward, access: W}` + `publish_data_model` | fixed, published, no restart |

The trap: **a profile record created by a table-actions call starts with value access `H` (hidden)**; the reviewer would have seen
nothing of the dataset. The generator writes the value access default for every profile it touches, first, then the actions and the
per-node overrides, then reads the whole set back before publishing — the same "re-read before claiming success" rule as the
matching configuration.

**Seen as the local test user `user` (no administrator role) right after the publication, no restart**: the table opened through
`/ebx/?branch=Reference&instance=AccelProbe&xpath=/root/Supplier` (the `ebx-ui/ui/advanced/...` route answered the generic error page,
as CLAUDE.md §9 says for a user without the advanced perspective — use `ui-node`), all six rows and every column readable, **no "+"**,
the record opened in the Layout Designer form **entirely read-only** with only a `Close` button: `Beveryone = R` and no create, exactly
the model permissions written. The user is in none of `RUser` / `RSteward`, so the write and the per-field override were not seen from
a role that holds them. Neither the dataspace lock nor the dataset lock stopped this user on `Reference` / `AccelProbe` (dataset owner
`Badministrator`): on this instance `Reference` is readable by everyone and the dataset inherits it, so the "three locks" apply to
dataspaces closed by their owner, not by default.

These are the model's own permissions (every dataset built on the publication). The other two locks a non-administrator meets —
dataspace and dataset permissions (CLAUDE.md §9) — have no MCP or REST route: for a generated domain they remain a manual step listed in
the generation summary, or a `Procedure` in the Factory (the same family as the perspective dataset of CLAUDE.md §12), which is not
designed yet. **Not measured**: what `RUser` and `RSteward` actually see and may do on the dataset (needs a non-administrator session
in a browser, plus those two manual locks opened for the test user).

## 16. Measurement 6 — the language-model round trip (2026-10-12, OpenAI connector of Connectors, model `gpt-6-luna`, no temperature)

Material: a Company pack core written by hand from the Companies model (5 tables, options listed, 4.7 KB with the block catalogue,
the library signatures, the workflow types and the supplier specification of the mockup), sent through `connectors_call(OpenAI,
complete)` with a system line asking for a JSON array of differences.

| Call | Prompt | Answer |
|---|---|---|
| One shot, all natures | 4.7 KB | **`Read timed out`** twice (the Connector row's `readTimeoutMs`, default 30 000 ms); a one-word prompt answers in 1.5 s |
| Pass 1, model only (≤ 10 items) | 2.6 KB | 17.9 s, 2 items |
| Pass 2, rules and checks (≤ 8 items) | 1.6 KB | 26.0 s, 7 items |
| Pass 3, workflows and roles (≤ 6 items) | 1.3 KB | 9.6 s, 5 items |

What came back, against what a person would want:

- **Pass 3 is right first time**: `creationWithReview{reviewers: [Purchasing, Finance], sequential}` with its labels, `changeRequest{sensitiveFields:
  [IBAN, BIC], reviewers: [Finance]}` for "every change of bank account must be re-approved", `periodicReview{dueField: expiryDate, daysBefore: 30}`
  for the compliance documents, two roles added. This is the mockup's proposal, produced from the specification alone.
- **Pass 2 is mostly right**: SIREN = `USE_LIBRARY luhn.isValid` **wrapped** with "exactly 9 digits" (the rule of measurement 3, which the
  model had not seen); IBAN = `USE_LIBRARY iban.isValid`; "BIC country must match the IBAN country" = `ADD` draft, correctly not claimed by a
  library. Two misses: `date.isValid` proposed on typed date fields (useless), and two approval items that belong to pass 3 (pass bleed).
- **Pass 1 is weak**: two items only — a separate `SupplierProfile` table for payment terms, incoterm and purchasing category where fields
  on Company (or a group) were expected, and the attachments block adapted with a document type and an expiry date (a defensible
  choice, but the `ComplianceDocument` child table the specification implies was not proposed). No audit block, no purchasing-category
  reference table, `where` written as prose, not as paths.

Facts for the generator:

- **Never one shot**: the Connectors default read timeout (30 s) is below what a complete answer takes on this model; the passes of
  section 6b stay under it, and the Factory's `llm.ask` carries `timeoutMs` for the day a pass grows. Raising `readTimeoutMs` on the LLM
  connector's row is the other lever.
- **A Java check after every pass is not optional**: out-of-scope natures are dropped (a pass answers only its natures), `where` is
  resolved to a real path or the item is marked "to place", a `USE_LIBRARY` whose function is not in the catalogue is refused, and a
  "wrapped" rule is shown as the library call plus the project's addition.
- **Pass 1 needs the pack's `guidance.md`** ("payment terms belong to the supplier, as fields of a Purchasing group", "a document with an
  expiry date is a child table"), the point of section 3: without it the model improvises the structure. Its item cap (10) was also too
  low for a model pass; 20 with one-line items fits the timeout.
- Cost of the three passes: 53 s of model time for a 5-table pack; the person decides 14 items. Acceptable for an interactive step, with
  the "running for N min" display the wizard already has.

## 17. Measurement 7 — cost of building the domain model (2026-10-12, 6.3.0-rc3, first real run of lot C1)

Same model (the `company` + `reference-data` packs of project `fourn1`, 13 tables), built two ways on the same instance:

| Route | Content | Wall clock |
|---|---|---|
| `create_data_model` + one `apply_data_model_patch` of 386 ops + `set_default_label` ×13 + `publish_data_model` + `create_dataset` (what `DomainGenerator` does) | 13 tables, ~100 fields, groups, foreign keys, associations, labels | 25 s (≈ 65 ms per op) |
| `build_data_model` (`data_model_name`, `model_json` as a JSON **string**, `dry_run: false`) | 13 tables, 91 fields, no foreign key, no label | **7.6 s** (≈ 73 ms per element); `get_data_model deep=true` read back 13/91; `delete_data_model` 0.1 s |

On the 6.3.0-dev build of 2026-09-23 `build_data_model` cost ≈ 0.95 s per element (one `CreateFieldProcedure` transaction each, §15 of
CLAUDE.md); on rc3 both routes are in the same range per element. The patch keeps the advantage of carrying keys, associations and labels
in one unit of work; `LandingGenerator` (which pays the build twice through `dry_run`) no longer has a performance reason to be rewritten.

## 18. First real run of lot C1 — project `fourn1`, 2026-10-12 (6.3.0-rc3, Factory d0449b1e)

Packs `company` + `reference-data`, Centralisation. Pack choice → 4 LLM passes (1 min 18 s) → 24 differences (12 accepted) →
generation: **37 objects written, 15 s for the last five steps once the earlier ones were done** — dataspace, model (386-op patch,
25 s), publication, dataset, 4,234 reference rows (2 s), 15 scripts declared + authored + published (51 s), 1 Layout Designer form,
4 directory roles (3 created), 1 workflow model + publication, 1 inline matching policy (ENABLED, metadataset present), model
permissions for 4 roles + everyone read-only. Six attempts were needed; each stopped on one defect, fixed, and the rerun resumed
from the failed step (every step is idempotent):

| # | Stopped at | Cause | Fix |
|---|---|---|---|
| 1 | ACCEL_GENERATE_RUN (0 s) | XPath `in (...)` in `DomainInputs` | `or` (7831a361) |
| 2 | ACCEL_MODEL | `set_association` without its host field in `company/core.json`; the failing op was not named | host `create_field` + the generator names the op (0d78ad14) |
| 3 | ACCEL_SCRIPTS | `scriptExists` read a `SCRIPT_NOT_FOUND` soft miss as "exists" | read `code` / `success` (dc675a47) |
| 4 | ACCEL_SCRIPTS | audit block: `core.date.now()` (a `date`) into `dateTime` fields | `core.datetime` (8b6b1e19) |
| 5 | ACCEL_SCRIPTS | contacts block: `anyURI` field is a `uri`, `url.isValid(string)` refused | `'' \| record.f` idiom (1d624888) |
| 6 | ACCEL_WORKFLOW | roles proposed by pass 4 never created in the directory: "The role Purchasing does not exist" | step `ACCEL_ROLES` (2c782d50) |

Resource-only fixes (4, 5) went live without a restart: `DomainGenerator` reloads `PackCatalog` at each run. The Java fix (6) needed
one coordinated restart (18:13–18:20). Two cosmetic defects seen on the generation page afterwards and fixed in the same branch: the
`ROLE` kind had no label, and every `ACCEL_*` task offered "Open step 8" (`RecentTasks.stepOf` now maps the proposal and the generation
to their own steps and shows the rail position). **The generated domain was then exercised end to end the same evening** (instance 303 of `fourn1_CompanyCreationRequest`, admin
holding the roles): creation task on the Layout Designer form in the work dataspace — the contacts constraints refused an e-mail
on a `.example` domain (error, Save refused) and warned on the website, the audit trigger stamped the record, the inline matching
run found no candidate (GOLDEN at once) — then "Submit for review", Purchasing and Finance approvals, merge into `fourn1_Domain`
(record #2 present with its audit stamp), work dataspace closed. Three more defects met on the way, all fixed in the same branch:
(7) administrators are not above the model's permissions extension — unlisted, admin fell to the everyone row and the creation
task answered "Access denied" (`ACCEL_PERMISSIONS` now writes a full `Badministrator` row); (8) a created role has no member, so
the first review work item went to the ERROR state until users were assigned and the step replayed (the step's message now says
so); (9) still open: the pack's `create_field` ops carry no `label`, so every field of the form shows its technical name
(`name`, `legalName`…, only the blocks' fields and the groups are labelled), and the review tasks' Accept/Reject labels are not
translated (`fr_FR: "Approve supplier"`) — both to fix in the pack and `WorkflowAssembler`.
Then: a `from=DOMAIN` reset and a second generation.

### 18a. Reset `from=DOMAIN` and second generation (2026-10-13)

The reset (`POST /projects/fourn1/reset?from=DOMAIN&confirm=fourn1`) failed twice (jobs 360, 361) on a **product defect**: after
`delete_matching_config` has removed the metadataset and reset its schema variant, `Repository.deleteHome` of the domain dataspace
throws `NullPointerException: Schema [… MetadataTemplate.xsd, referred dataset:Domain] is missing` in `RpReplicationDelete`
(the home's dataset cache still lists the deleted metadataset) and the home stays closed; a Tomcat restart cleared it and the same
reset then passed (job 366, 21 steps reset, every object gone, the four roles kept) —
`product-bugs/2026-10-13-delete-dataspace-after-delete-matching-config.md`. Two smaller findings: `workflow_publication_unpublish`
counted the COMPLETED instance 303 as "running" (`terminateActiveInstances=true` needed), and the reset's message reads
"Reset of fourn1 from DOMAIN: , 21 step(s) reset" (empty list before the comma). Open question for `DomainCleanupPlan`: deleting the
dataspace BEFORE the matching configuration would avoid the defect, if `delete_matching_config` then copes with a metadataset that is
already gone (`metadatasetOrphaned`) — to measure.

**Second generation, from a clean project: 1 min 09 s for the 37 objects** (dataspace 0 s, model 14 s, reference rows 1 s,
scripts 36 s, form 6 s, roles 0 s — all four already there —, workflow 2 s, matching 3 s, permissions 7 s), with the deployed fixes
verified: `Badministrator` row written, "Role" label, "Open step 5" on the generation tasks.
