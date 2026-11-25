# Lot C1 — Centralisation, first lot (contract for the sub-lots)

Opened 2026-10-12 after the six measurements of `docs/ACCELERATORS.md` (sections 10-16). **Status: coded and integrated (see "Integration — done" at the end), tests green, NOT deployed, never run against EBX. Tomcat must NOT be restarted by this lot (the product owner's demo): code and `tools/test_local.sh` only.**

Scope = `ACCELERATORS.md` section 6 (reduced): the `reference-data` and `company` packs, the `audit` and `contacts` blocks, the
`creationWithReview` workflow type, the four-pass proposal, the generation of one domain, the wizard path of section 4. The pack
material is already in `src/main/resources/com/on/ebx/factory/accel/` (data only, see section 1).

Rules of the Factory that apply unchanged: the language model proposes and a person decides every line (`docs/LLM-DATA-POLICY.md`
for what is sent); suggestions come from the model, never from Java heuristics per domain; every generated object is traced in
`PipelineStep` so step reset and project deletion keep working; the XSD comes from `tools/build_xsd.py`; messages are ASCII
`.properties` in English and French; tests are JUnit-free `main`s run by `tools/test_local.sh`.

## 1. Pack material (done, resources)

```
accel/
  libraries.json           the published libraries a pack may wrap (name, signatures, what they cover)
  workflow-types.json      the workflow types the generator assembles (creationWithReview is the only one generated in C1)
  blocks/<key>/block.json  fields of the block, the group it adds, its scripts (bodies in scripts/*.ebx with ${table} ${group} placeholders)
  packs/<key>/pack.json    identity, version, requires{packs, modules, optionalModules}, blocks, options, mainTable, keywords
  packs/<key>/core.json    {ops: [...]} in the data-model patch vocabulary + blockPlacements + labels + defaultLabels
  packs/<key>/guidance.md  what the model reads first (meaning of the tables, typical variations, usual rules, what not to do)
  packs/<key>/workflows.json, matching.json, layouts/<Table>.json (Layout Designer format, model = ${publication})
  packs/reference-data/data/*.csv   rows preloaded with the pack
```

`core.json` ops are exactly the `apply_data_model_patch` operations (`create_table` takes `table_path`; `create_field` takes
`field_path`, `field_type`, `is_primary_key`; `set_element_property` takes `element_path`, `property`, `value`; `add_foreign_key`,
`set_association`, `create_group` as the server documents them — checked 2026-10-12 against the server's tool schemas: `create_group` takes `table_path` (the PARENT) + `group_name`; `set_association` takes
`field_path`, `mode` and `fk_field_path` (the foreign key in the other table); `add_foreign_key` takes `field_path` + `target_table_path`).

## 2. Sub-lots and their owners

| Sub-lot | Branch | Owns (no other sub-lot edits these) | Depends on |
|---|---|---|---|
| **A. Pack catalogue** | `feature/accel-catalog` | package `accel` (pure): `PackCatalog`, `Pack`, `Block`, `LibraryCatalog`, `WorkflowTypes`, `PackOps` (block → ops for a table, placeholders), their tests | nothing |
| **B. Data model + wizard path** | `feature/accel-wizard` | `tools/build_xsd.py` (tables `ProjectPack`, `Difference`, `DomainObject`), `core/FactoryPaths.java`, `wizard/*`, `ebx/WizardData.java`, `ebx/ui/ProjectWizardUserService.java`, `wizard.js`, `Messages_*` keys `wizard.accel.*` and `accel.*` | A's interfaces (section 3) |
| **C. Proposal passes** | `feature/accel-proposal` | `accel/AdaptationLlmPass` (pure), `accel/DifferenceCheck` (pure), `ebx/AdaptationProposalRun` (job), `ebx/DifferenceWrites`, `ebx/FactoryJobKinds` entry, `Messages_*` keys `accel.pass.*` | A, B's tables |
| **D. Domain generation** | `feature/accel-generate` | `ebx/DomainGenerator` and its steps, `ebx/WorkflowModelOps` (new, `workflow-modeling` client), `ebx/LayoutOps`, `ebx/ReferenceDataLoader`, `ebx/McpServers` (`WORKFLOW_MODELING` constant), `Messages_*` keys `accel.generate.*` | A, B's tables, decided differences from C |

Integration: each sub-lot is merged into `main` with `--no-ff` from a temporary worktree, A first, then B, then C and D. Conflicts in
`Messages_*` are append-only sections (`# ---- accel: <sub-lot>`); `FactoryJobKinds` and `McpServers` get one line each from C and D.

## 3. Interfaces (sub-lot A, used by B, C, D)

Package `com.on.ebx.factory.accel`, no EBX class, resources read from the class path through `PackCatalog.load()`.

```java
public final class PackCatalog {
  public static PackCatalog load();                       // reads accel/** from the class path, fails fast on a malformed file
  public List<Pack> packs();                              // sorted by key
  public Pack pack(String key);                           // null when absent
  public List<Block> blocks();  public Block block(String key);
  public LibraryCatalog libraries();                      // name -> signatures + covers
  public WorkflowTypes workflowTypes();
  public List<Pack> suggest(String need);                 // keyword hit on pack.keywords, labels, guidance; no model call (the model's own suggestion is sub-lot C's pass 0)
}
public final class Pack { key(), version(), label(Locale), description(Locale), requires() (packs, modules, optionalModules), blocks(), options(), mainTable(), keywords(), core() (PackCore), guidance(), workflows() (JsonNode), matching() (JsonNode), layouts() (Map<tablePath, JsonNode>), referenceData() (Map<tablePath, ReferenceTable{key, csv rows, column map}>) }
public final class PackCore { List<ObjectNode> ops(); List<BlockPlacement> blockPlacements(); Map<String, Map<String,String>> labels(); Map<String,String> defaultLabels(); List<String> tablePaths(); List<String> fieldPaths(); }
public final class Block { key(), version(), label(Locale), group(), fields() (name, type, maxLength, readOnly, label), scripts() (kind, type, name, field, body template, uses), layoutPanel() }
public final class PackOps {
  public static List<ObjectNode> opsForBlock(Block block, String tablePath, String groupName);   // create_group + create_field + set_element_property for the block's fields
  public static String scriptBody(Block block, String scriptName, String tableName, String groupName); // placeholders ${table} ${group} replaced
  public static List<ObjectNode> coreOps(PackCore core, Set<String> removedPaths, Map<String,ObjectNode> changedProperties); // the pack ops minus REMOVE differences, plus CHANGE differences applied
}
public final class LibraryCatalog { boolean knows(String function); Library library(String name); List<Library> all(); String promptText(); }
public final class WorkflowTypes { WorkflowType type(String key); List<WorkflowType> all(); String promptText(); }
```

Every `promptText()` is the compact text the proposal passes paste into their prompts (section 4): one line per entry, no JSON.

## 4. The four passes (sub-lot C)

`AdaptationLlmPass` (pure) builds, for a pass number and a context, the `system` and `prompt` strings, and parses an answer into
`List<Difference>`; `DifferenceCheck` (pure) validates a parsed difference against the pack, the blocks, the libraries and the
workflow types, and answers `Verdict{ok | refused(reason) | toPlace}`. `AdaptationProposalRun` (EBX) runs the passes one after
the other as a `FactoryJobQueue` job with one parent `PipelineStep` (progress JSON like `StagingProposalRun`), each pass through
`AgentRuntime.ask(..., json = true, timeoutMs = 120_000)`, with the `askOneField` pattern: a refused answer is re-asked once with the
reasons, a truncated answer counts as no answer. Rows are written `origin = LLM, status = PROPOSED` into `Difference`.

| Pass | Natures accepted | Input (besides `PromptData.SYSTEM_NOTICE` and the specification, fenced as data) | Item cap |
|---|---|---|---|
| 0 pack choice | — (answers `{"packs": [keys], "options": [keys], "why"}`) | the catalogue's one-line descriptions, the need | — |
| 1 model | ADD, CHANGE, REMOVE, USE_BLOCK | pack core as a compact listing, options, `guidance.md`, the blocks' one-liners | 20 |
| 2 rules | USE_LIBRARY, ADD (draft rule) | the model as decided after pass 1 (tables and fields), `LibraryCatalog.promptText()` | 12 |
| 3 forms | ADD/CHANGE on a layout | the decided model, the pack layout, the block panels; the answer is a Layout Designer JSON per table | 1 per table |
| 4 workflows | USE_WORKFLOW, ADD (role) | the decided model, `WorkflowTypes.promptText()` | 8 |

Measured on 2026-10-12 (ACCELERATORS.md section 16): one shot times out (Connectors read timeout 30 s), passes answer in 10-26 s;
pass 1 is weak without `guidance.md`. The person decides pass N before pass N+1 runs by default (`ReviewRules`-style gating: a
pass whose predecessor still has PROPOSED rows is not started unless the user asks "run all").

`DifferenceCheck` rules: a nature outside the pass is dropped (reason logged on the row as `rationale` prefix `[dropped: out of pass]`
— no: dropped rows are NOT written; the count is in the step detail); `where` must resolve to a path of the pack core or of an ADD
of the same answer, else `toPlace = true` (the row is written with `where` as given and `placeState = TO_PLACE`); a `USE_LIBRARY`
whose function is not in `LibraryCatalog` is refused; a `USE_BLOCK` whose block is unknown is refused; an `ADD` of a table/field whose
path already exists in the pack is turned into `CHANGE`; a `REMOVE` of the main table is refused.

## 5. Tables (sub-lot B, `tools/build_xsd.py`)

- `ProjectPack` (id, project FK, packKey, packVersion, options (list of strings), specification (text 20000), specificationKind
  TEXT/FILE/MODEL, chosenBy (LLM/USER), status) — one row per pack chosen for the project.
- `Difference` (id, project FK, pass int, nature enum ADD/CHANGE/REMOVE/USE_BLOCK/USE_LIBRARY/USE_WORKFLOW/KEEP, what, where (string 500),
  placeState PLACED/TO_PLACE, detail (text 20000 JSON), why (text 2000), packKey, blockKey, libraryFunction, workflowType,
  appliedObject (string 500), + `proposal()` fields origin/confidence/rationale/status (PROPOSED/ACCEPTED/REJECTED/APPLIED)).
- `DomainObject` (id, project FK, kind enum DATA_MODEL/PUBLICATION/DATASPACE/DATASET/SCRIPT/WORKFLOW_MODEL/WORKFLOW_PUBLICATION/
  LAYOUT/MATCHING_POLICY/PERMISSIONS/REFERENCE_ROWS, name, path, detail, createdOn, step FK PipelineStep) — what the generation wrote,
  for the summary, step reset and project deletion (`GeneratedObjectDeleter` learns the new kinds in sub-lot D).
- `Project`: `style` becomes selectable for CENTRALISATION (the three refusals of `WizardRules.projectProblems`, `WizardData.saveProject`,
  `WizardPage.renderStyleCard` are lifted for that value only); new fields `domainDataspace`, `domainDataset`, `domainModel`
  (strings) filled by the generation.

## 6. Wizard path (sub-lot B)

`WizardRules` gets a **step list per style**: `steps(style)` returning the ordered step ids; CONSOLIDATION keeps its 8 steps unchanged
(every existing test must still pass), CENTRALISATION gets `PROJECT, PACK, SPECIFICATION, PROPOSAL, GENERATION, SUMMARY`. `stepStates`,
`suggestedStep`, `actionGates`, `WizardPage.STEP_KEYS`, `renderSteps`, `renderNav` and the `render` switch read that list; `wizard.js`
loses its hard-coded "7". The three new screens are rendered by a new pure class `wizard/AccelPages.java` (so `WizardPage.java` does not
grow further), after the mockups `docs/mockups/accelerators/*.dc.html`:

- **Pack** (step 2): the need (textarea, saved on `ProjectPack.specification` with kind TEXT), "Suggest packs" (sub-lot C's pass 0, or
  `PackCatalog.suggest` when no model is reachable), the pack cards with checkboxes, the "why these" box, the counters card, "Continue".
- **Specification** (step 3): the text again (editable), the chosen packs and options, "Run the proposal" (starts the job of sub-lot C),
  the job progress.
- **Proposal** (step 4): one row per `Difference` with Accept / Edit / Reject (the wizard's existing `decide` path with nature
  `DIFFERENCE`, added to `ReviewLine` and `DecisionActions`), grouped by pass, KEEP rows collapsed, the "looks like something that exists"
  box when `DifferenceCheck` flagged it, "Generate the domain" enabled once no row is PROPOSED.
- **Generation** (step 5): the list of `DomainObject`s with their step status (sub-lot D), the draft rule held back, the traceability
  block (pack versions, decisions, who, when).
- **Summary** (step 6): the existing summary pattern.

## 7. Generation (sub-lot D)

`DomainGenerator.run(session, factory, projectId)` as a `FactoryJobQueue` job, steps in this order, each a `PipelineStep` and each
writing its `DomainObject` rows (measured recipes in ACCELERATORS.md sections 10-15):

1. **Dataspace** of the domain: a child of `Reference` named after the project code, created in Java as `MasterGenerator` does
   (never a dataset in `Reference` itself: a dataset created there leaks into every existing child dataspace, measured 2026-10-12).
2. **Data model**: `create_data_model`, then ONE `apply_data_model_patch` with `PackOps.coreOps(...)` + the blocks' ops + the ADD/CHANGE
   differences, `set_default_label` per table, `add_addon ebx-addon-mame` when the module is present, labels through
   `set_element_documentation`, `publish_data_model`, `create_dataset` in the domain dataspace.
3. **Reference rows**: `ReferenceDataLoader` writes the pack's CSVs into the dataset in a `Procedure` (column map of `pack.json`).
4. **Scripts**: blocks' triggers and constraints and the accepted USE_LIBRARY / ADD rules through `ScriptTriggerOps`-style declare +
   author; an ADD draft rule is authored but marked `DomainObject.detail = draft` and NOT counted as published until a person confirmed it
   (the wizard shows it held back); ONE `publish_data_model` at the end of this step.
5. **Form**: for each table with a layout (pack layout adapted by pass 3, or `audit`/`contacts` panels appended), write the `FormLayout`
   row of `LayoutDesigner/LayoutDesigner` in a `Procedure` (fields `model` = `urn:ebx:publication:<name>`, `table`, `active`, `layout`,
   `version`, `label`), then `execute_operation(set_form_layout, ConfigurableForm)` + `publish_data_model`. Skipped with a message when the
   Designer runtime is absent (`DomainGenerator` checks the class `com.on.ebx.layout.runtime.ConfigurableForm`); the Layout API lot of the
   Designer will replace the direct row write.
5b. **Roles** (`ACCEL_ROLES`, added after the first real run): every role of the plan is looked up in the built-in directory
   (`ebx-directory` / `/directory/roles`) and created by a `Procedure` when absent — `workflow_publication_publish` refuses a model whose
   role the directory does not know ("The role Purchasing does not exist", fourn1, 2026-10-12). A role is never removed by a reset or a
   project deletion (shared, users may hold it); its `DomainObject` row (`kind = ROLE`) says whether the project created it.
   A created role has no member: the first work item offered to it goes to the ERROR state ("The role [Purchasing] contains no
   user", measured on fourn1 2026-10-12) until users are assigned (Administration › Users and roles, or `directory/usersRoles`) and the
   step is replayed (`POST /workflows/{key}/actions/replayCurrentStep`). The step's message and the summary say so.
6. **Workflow**: `WorkflowModelOps.importAndPublish(session, factory, modelJson, name)` through the `workflow-modeling` MCP server
   (`McpServers.WORKFLOW_MODELING = "workflow-modeling"`, optional), the model built by `accel/WorkflowAssembler` (pure) from the
   `creationWithReview` parameters — the measured JSON of section 14 is the template (`tools/workflow/AccelCreationWithReview.template.json`),
   with the output mapping written `{"name": "created", "value": "<variable>"}`.
7. **Matching**: `MameCalls` `generate_matching_policy` with the pack's `matching.json` (fields re-pointed by the decided differences),
   `patch_matching_config set_execution_mode 3/3`, `activate_matching`; skipped when the add-on is absent.
8. **Permissions**: for the roles of the workflow (Requester, Reviewer, DataSteward → `R<role>`), `set_permission_value_access_default`
   FIRST (W / W / W), then `set_permission_table_default_actions`, `Beveryone = R`, read back with `get_data_model_permissions`, publish.
9. **Summary**: `Project.domain*` fields filled, `ProjectPack.status = GENERATED`.

**First real run done end to end on 2026-10-12 (project `fourn1`, 37 objects): docs/ACCELERATORS.md section 18 lists the six defects met and fixed.**

Every step is idempotent (`PipelineSteps.isDone`) and reset-able (`StepResetPlan` learns the new steps; `GeneratedObjectDeleter` learns
`DomainObject` kinds, with the exact "already absent" markers of each tool).

## 8. Tests expected

- A: catalogue loads, every pack and block file parses, every `core.json` op has a known op name and the argument names the server
  documents, every block script template has no unresolved placeholder after `scriptBody`, `suggest("supplier")` returns company.
- B: existing wizard tests green; `WizardRules.steps(CENTRALISATION)` has 6 steps; `stepStates` for both styles; `AccelPages` renders the
  three screens from a snapshot fixture; `projectProblems` accepts CENTRALISATION and still refuses COEXISTENCE/REGISTRY.
- C: `AdaptationLlmPass` prompts contain the fenced specification and the right catalogue per pass; `DifferenceCheck` on the measured
  answers of 2026-10-12 (section 16: `SupplierProfile`, `date.isValid`, approvals in pass 2) gives the expected verdicts; parsing tolerates
  fences and prose around the JSON.
- D: `WorkflowAssembler` output equals the template for the measured parameters; `PackOps.coreOps` with REMOVE/CHANGE; `ReferenceDataLoader`
  column mapping on the real CSVs; no EBX in the tests.

## 9. Out of C1

Search before create (type 2), the other workflow types, the Layout API contract (Designer lot), the initial load (Consolidation),
the other packs, dataspace/dataset permissions (manual step listed in the summary), a non-administrator run.

## Integration — done (branch feature/accel-integration, 2026-10-12)

The four sub-lots (A catalogue, B data model and wizard path, C proposal passes, D generation) were written in parallel and are now one whole.
`tools/test_local.sh` is green (with the two pinned jars copied into the gitignored `WEB-INF/lib`). Nothing deployed, no server touched, **nothing
here has run against EBX**: the sections above (1-9) stay the contract; this section is what was built.

**Classes.** `accel/` (pure): `PackCatalog`, `Pack`, `PackCore`, `Block`, `PackOps`, `LibraryCatalog`, `WorkflowTypes`, `ReferenceTable` (A);
`Difference`, `DetailShape`, `AdaptationContext`, `ModelPaths`, `AdaptationLlmPass`, `PackChoice`, `DifferenceCheck`, `ProposalProgress` (C);
`WorkflowAssembler`, `DomainPlan` (D). `ebx/`: `AdaptationProposalRun` (job `ACCEL_PROPOSAL`), `DifferenceWrites` (C); `DomainGenerator` (job `ACCEL_GENERATE_RUN`),
`DomainScripts`, `DomainObjectWrites`, `DomainInputs`, `ReferenceDataLoader`, `LayoutOps`, `WorkflowModelOps`, `DomainCleanup` (D); `AccelData`, `AccelWizard`, `AccelHooks`
(B, hooks wired); `wizard/AccelPages`; `project/DomainCleanupPlan`. Paths: `core/FactoryPaths` only (`PROJECT_PACK`, `DIFFERENCE`, `DOMAIN_OBJECT`, `ProjectPack.*`,
`Difference.*`, `DomainObject.*`, `Project.DOMAIN_*`); the stand-ins `AccelPaths` and `AccelGenPaths` are deleted.

**Wiring.**
- `AccelHooks.suggestPacks` -> `AdaptationProposalRun.suggestPacks`, answering `{"packs","options","why"}`; it WRITES NOTHING (the wizard's "Continue" saves the
  `ProjectPack` rows, status CHOSEN, `chosenBy` LLM when the person kept exactly the suggestion).
- `runProposal` -> `AdaptationProposalRun.queue(..., ALL)`; `runPass(pass, runAll)` -> `queue(..., pass, andAfter = runAll)` (new overload: that pass and the ones after it,
  without waiting for a decision, the passes before it left alone; a single pass still refuses to start while the previous one has PROPOSED lines); `generate` -> `DomainGenerator.queue`.
  A `{started:false, reasonKey, reasonArg}` answer becomes `EngineRefusal.of(reasonKey, ..., reasonArg)`: the keys `accel.pass.noModel`, `accel.pass.noPack`, `accel.pass.skip.waiting`
  are bundle keys, so the notice is localised. `AccelHooks.packCatalogue` maps `PackCatalog.load().packs()`.
- Job kinds `ACCEL_PROPOSAL` and `ACCEL_GENERATE_RUN` start with `ACCEL_`; both parent `PipelineStep.detail` JSON now carry `done`/`total` (4 stages; 9 steps) for the wizard's bar,
  next to their own `stages[]` / `steps[]`. Labels `wizard.jobs.kind.ACCEL_*` added (en, fr).
- Reset: `StepResetter` accepts `from=DOMAIN` (plan and reset, REST/`StepResetRun` included): all four `DomainCleanup` items are run (workflow publication, model, matching, dataspace,
  layouts, data model), and only when none failed the `ACCEL_*` steps go back to `RESET`, `Project.domain*` is cleared and the packs are CHOSEN again; `Difference` and the pack choice stay.
- Deletion: `ProjectDeleter` runs `DomainCleanup` (objects, then the `DomainObject` rows) BEFORE `deleteFactoryRows`, which then finds no `DomainObject` row and removes `Difference`
  and `ProjectPack`. A failed object stops the deletion before any row is touched. No double deletion.

**Mismatches found and how they were resolved.**
1. `ProjectPack.status`: C wrote `PROPOSED`, the XSD enumeration is `CHOSEN/GENERATED` (a value outside it would be stored and flagged). C's pack-suggestion rows are gone (suggestion writes
   nothing, see above); `readChosen` no longer filters on a status.
2. `Difference.what` is 300 characters in the XSD, C truncated at 500: now 300 (`DifferenceWrites.WHAT_MAX`).
3. `DomainObject.detail` of a script: D wrote JSON `{"draft":true,...}`, B's screen compared the text to `draft`: `AccelPages.isDraft` accepts both.
4. Nobody wrote `similarTo`, which the wizard shows ("looks like something that exists"): `AdaptationProposalRun` now calls `DifferenceCheck.looksLikeExisting` on every kept ADD and adds
   `similarTo` (`block audit`, `library ...`) through `DetailShape.withSimilar`.
5. `Difference.detail` shapes (below): C asked `type`, `wrap`, a bare layout object, no `kind` for a rule or a role; D read `fieldType`, `exactLength`/`message`, `detail.layout`, `kind`. One
   shape per nature is now documented on `accel/Difference`; C's prompts ask for it and `DetailShape.normalise` repairs the answer; D reads it and gained `group` and foreign-key (`target`)
   ADDs and skips an ADD whose `where` is not an absolute path (the measured pass 1 answer wrote prose there) with a note.
6. Progress JSON without `done`/`total` (B's reader): both writers now add them.
7. `StepResetter` not wired to `DomainCleanup`: wired (`from=DOMAIN`).
8. `AccelWizardTest` asserted the hooks were unfilled: it now checks the blocked-answer translation. `AdaptationLlmPassTest` follows the layout wrapping.
Field names of section 5 agreed everywhere (B's `FactoryPaths` = C's and D's assumptions); every enumeration value written (`nature`, `placeState`, `status`, `origin`, `kind`, `specificationKind`,
`chosenBy`) is in the XSD enumeration.

**Final shape of `Difference.detail` per nature** (also on the Javadoc of `accel/Difference`): ADD pass 1 `{kind: table|group|field, fieldType, maxLength, mandatory, target (FK), fields[{name,type,maxLength}]}`
or `{ops:[...]}`; ADD pass 2 `{kind:"rule", rule, draft:true}`; ADD pass 4 `{kind:"role", role, does}`; pass 3 `{layout:{...}}`; CHANGE `{property: mandatory|maxLength, value}`; USE_BLOCK `{group}`;
USE_LIBRARY `{exactLength?, message?}`; USE_WORKFLOW the type's parameters, flat (`requester`, `reviewers[]`, `mode`, `acceptLabel`, `rejectLabel`); any ADD may carry `similarTo`/`similarNote`.
`ProposalToPlanTest` feeds the measured answers (passes 1, 2, 4 of ACCELERATORS.md section 16) through `AdaptationLlmPass` and `DifferenceCheck` into `DomainPlan` and checks the ops, scripts, draft
rule, workflow and roles come out.

**Where it was measured vs only coded.** Everything below is unrun against EBX: the four passes against a model (answers measured 2026-10-12 by hand, never through `AgentRuntime`), the job queue
entries, `DomainGenerator` and all its tool calls (argument names read from the tool schemas, `create_group`/`set_association` argument names never confirmed with `list_operations`), the Layout Designer
`FormLayout` row write, `get_data_model_permissions` shape, workflow import/publish through `workflow-modeling` (and `agentAccessForAdmin/Inbox` on the generated model), the matching activation, the cleanup
tools' "already absent" wording, the DOMAIN reset, the wizard screens in a browser, the French texts. Known limits: only `creationWithReview` is generated; draft rules are authored but never published as
enforced; tables without a pack layout get no form; `USE_BLOCK` field adaptations (`addFields`) are ignored; `DomainObject` is not backed up (restore renumbers PipelineStep).

**First real run, check in this order:** (1) the Pack step suggests packs (model reachable, else keywords) and Continue creates `ProjectPack` rows CHOSEN; (2) pass 1 on `company` writes <= 20 rows,
none outside the enumerations, `placeState` right; (3) the Proposal step lists them with the "looks like" box and Accept/Edit work; (4) `ACCEL_MODEL`: the single `apply_data_model_patch` of the plan
applies (watch `create_group` / `add_foreign_key` argument names) and the model publishes; (5) scripts compile as `update_script` bodies (`validate_saved`); (6) the workflow imports and its
user-task output mapping is `{name: output, value: variable}`; (7) `from=DOMAIN` reset then a second generation.
