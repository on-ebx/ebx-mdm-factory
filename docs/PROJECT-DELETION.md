# Project deletion (PLAN.md screen 7)

Written 2026-09-23 on branch `feature/project-deletion` (worktree `ebx-mdm-factory-project-deletion`),
answering the product owner's request the same day: *"Il faut pouvoir supprimer un projet et ses objets liés, et
proposer aussi de supprimer ce qui a été généré (dataspaces, datasets, modèles, règles de matching)."*
**Written, never deployed, never seen running** — no EBX instance was touched for this whole lot (no MCP
write call, no `install.sh`, no Tomcat restart); only read-only MCP tools were consulted while writing this
document (to confirm a tool's own contract text, never to change anything). `tools/test_local.sh` is green
(XSD regeneration diff — unchanged, no XSD edit in this lot —, ASCII/CSS-comment checks, compile, full test
suite including the four new test classes this lot adds: `ProjectDeletionPlanTest`, `ProjectDeletePageTest`,
plus the extended `ProjectExplorerPageTest`/`FactoryLinksTest`).

**Sibling lot, `docs/STEP-RESET.md` (2026-09-23, branch `feature/step-reset`): "Clean this step and the
following ones", one step short of a full project deletion.** Where this screen removes the whole project,
step reset puts wizard steps 3-6 (Landing/Staging/Master/Matching) back to zero from a chosen stage onward,
keeping the project itself and its chosen sources. It reuses this lot's own mechanics rather than
duplicating them: `ebx.GeneratedObjectDeleter` (the four per-kind deletion calls — matching config,
Connectors flow, dataspace, data model) was extracted out of `ebx.ProjectDeleter` with no behaviour change so
`ebx.StepResetter` calls the exact same code, and `project.StepResetPlan` reuses `ProjectDeletionPlan.Facts`/
`SourceFacts`/`MatchingFacts` as its own input rather than re-deriving them. One deliberate difference: an
ineligible generated object REFUSES the whole step reset (never just leaves a checkbox unchecked, as it does
here) — a stage cannot be called "clean" while a generated object nobody can prove the factory owns is left
in place. See `docs/STEP-RESET.md` for the full per-stage table and every guardrail.

## What it is

Two parts, always in this order, matching the product owner's own framing exactly:

1. **Optional** — clean up what the factory generated for the project: its matching configuration, its
   Connectors flows, its dataspaces (master/staging/landing), its data models (master/staging/per-source
   landing), and (lot C, 2026-10-17) the sample-file connections and catalogue sources it created. The user ticks any
   combination of five checkboxes (`matching`/`flows`/`dataspaces`/`models`/`connections`).
   The `flows` option covers the inbound mapping of every landing table **and the outbound mapping of every
   table of every distribution target** of the project (see "Distribution targets" below). The `connections` option
   is described in "Connections and catalogue sources" below.
2. **Always** — delete the project's own rows in the factory dataset (`Project` and every table that reaches
   it by foreign key, per `tools/build_xsd.py`).

If any ticked generated-object deletion fails, the whole call stops **before** part 2, so nothing of the
project's own rows is ever touched while a generated object might still be reachable through them. A retry
recomputes the plan fresh from the live state every time — an object a previous partial run already removed
is reported `SKIPPED_ABSENT`, never a failure, so the same call can simply be repeated until it succeeds.

## Files

- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/project/ProjectDeletionPlan.java` — the pure planning
  class (no EBX type anywhere): takes a `Facts` value the EBX-facing class gathers, decides which generated
  objects are eligible and why not otherwise, in the fixed execution order, and validates a delete call
  (`confirm`, unknown options, the `matching`-before-`dataspaces` dependency). Unit-tested,
  `ProjectDeletionPlanTest` — every eligibility rule, the shared landing dataspace with only one source
  recording `created: true`, both `validate` refusals, the fully-eligible-GREENFIELD-project happy path and
  its exact item order.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ProjectDeleter.java` — the EBX-facing class:
  `plan(factory, code)` gathers the live evidence into `ProjectDeletionPlan.Facts` and returns it as JSON;
  `delete(session, factory, code, options, confirm)` re-gathers, validates, executes every ticked eligible
  item (matching → flows → dataspaces → data models, in that order), and, only if nothing failed, deletes
  the project's own rows in one `Procedure`, children before parents.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/rest/FactoryResource.java` — `GET
  /projects/{code}/deletion/plan` and `POST /projects/{code}/delete?confirm=<code>&generated=...`, both
  documented in the class's own route Javadoc right after `/master/reset`.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProjectDeleteServiceDeclaration.java` /
  `ProjectDeleteUserService.java` / `ProjectDeletePage.java` — the screen: `OnTableView` on `/root/Project`,
  service name `ProjectDelete`, administrators only (a permission rule on the toolbar/perspective action,
  re-checked inside the event handler — a permission rule only hides the action, it does not stop a direct
  request, the same posture `FactoryConnectionServiceDeclaration` already takes). `ProjectDeletePage` is pure
  (a `Model`/`Item` pair of plain value classes, `static String render(Model)`), tested by
  `ProjectDeletePageTest` without any EBX type.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ConnectionCleanup.java` / `project/ConnectionProvenance.java` — lot C, see "Connections and catalogue sources".
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/FactoryLinks.java` — two new links,
  `deleteUrl(code)` (same `project` launch-parameter technique as `wizardUrl`) and `explorerUrl()` (the
  "back to the explorer" link on the delete screen's own report). `FactoryLinksTest` extended.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/ui/ProjectExplorerPage.java` /
  `ProjectExplorerUserService.java` — a third card button, "Delete" (`mdmc-btn mdmc-btn-danger`), shown only
  when `Model.canDelete` (`session.isUserInRole(Profile.ADMINISTRATOR)`, resolved in `setupDisplay` — `write`
  itself has no session, so the boolean is carried on a new instance field rather than re-resolved there).
  `ProjectExplorerPageTest` extended (button present for an admin, absent otherwise; the `target="_top"` link
  count check corrected in passing — it had never actually counted the "New project" button, so both branches
  of that check were off by one before this lot touched them).
- `ebx-mdm-factory/src/main/webapp/www/common/stylesheets/factory/factory.css` — the `.mdmf-delete-*` rules
  (option blocks, item lines, per-outcome colouring) under a new section at the end of the file, reusing the
  existing `--good`/`--bad`/`--line` tokens, no new colour introduced.
- `ebx-mdm-factory/src/main/webapp/www/common/stylesheets/factory/explorer.css` — `.mdmc-btn-danger` (the
  `--bad`/`--bad-soft` variant of the existing `.mdmc-btn`).
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/FactorySchemaExtensions.java` — registers
  `ProjectDeleteServiceDeclaration`.
- Message bundles: every `delete.*` key plus `explorer.card.delete`, in both `Messages_en.properties` and
  `Messages_fr.properties` (French written first-class, not translated as an afterthought — see CLAUDE.md's
  own note on this convention). Every accented character is a `\uXXXX` escape, checked by
  `tools/test_local.sh`'s ASCII scan.
- `ebx-mdm-factory/src/main/java/com/on/ebx/factory/ebx/LandingGenerator.java` /
  `StagingGenerator.java` — `landingSuffix`/`stagingSuffix` made package-visible (not `private`), the same
  change `MasterGenerator.masterSuffix` already had, so `ProjectDeleter` can compute the exact dataspace name
  the factory would itself generate — the same technique `master.MasterResetEligibility` already relies on
  for its own "is this a foreign name" check, generalised here to all three zones.

## Eligibility rules, and why (CLAUDE.md's own facts behind each one)

Every rule is checked in `ProjectDeletionPlan` (pure, unit-tested), in this order, per item:

1. **A blank stored name is not an "ineligible" item — it is simply absent from the plan.** Nothing was ever
   generated for that zone/model/mapping, so there is nothing to describe.
2. **A delivered project** (`delivery.OriginRule.isDelivered`) — every generated item is ineligible: *"remove
   it on the origin instance, or by hand"*, the same posture the delivery lot already established for every
   `suggest*` route (decisions are taken where the project was designed, never re-derived on a target).
3. **A stored name that is not the exact name this factory would itself compute** — ineligible, the same
   guard `master.MasterResetEligibility` already applies to a master-zone reset: never delete a hand-typed or
   foreign dataspace just because it happens to be attached to this project's own record.
4. **No `PipelineStep` evidence that the factory actually ran the step** (and, for a dataspace, that it
   *created* it rather than merely found one already there under a matching name — the exact
   `dataspace.created == true` detail-JSON shape every generator here writes, `docs/MASTER-ZONE.md`'s own
   "reset" section) — ineligible, refusing to guess ownership from a name alone.
5. **A master zone (dataspace or data model) of a `TARGET_EXISTS` project** — ineligible: that zone belongs to
   the target instance, never the factory's to delete.
6. **A matching configuration** — eligible only when its table's master-dataspace item is *itself* eligible
   **and** the table is actually configured (a live `get_matching_config` probe). CLAUDE.md §8: a matching
   configuration owns a metadataset *inside* the master dataspace — deleting the dataspace out from under a
   live configuration would orphan that metadataset, so `ProjectDeletionPlan.validate` additionally refuses
   `dataspaces` without `matching` whenever the plan holds an eligible, configured matching item.

7. **A distribution flow** (`DISTRIBUTION_FLOW`, under the `flows` option; fix of 2026-10-12). Measured live
   that day: projects Personnes and DistribTest had been deleted through this screen, yet their
   `DistributionTarget` rows 1 and 2 (`project` is a required foreign key to `Project`, which EBX let go and
   left dangling) and their Connectors OUTBOUND mappings 302, 314 and 316 (`connectorsMappingIds` =
   `/root/Personne=314,/root/Adresse=316`) survived, with 1,071 `OutboundState`, 7 `MappingInput` and 46
   `MappingRule` rows; a later send resolved one of those orphan mappings by mistake. One item per mapping id
   in the target's `connectorsMappingIds` (read with `distribution.TargetMappingList.parse`, never split by
   hand). A mapping is the project's own only when **both** hold: its label starts with
   `Distribution: target <id> ` (the label `DistributionConfigurator.label` writes; the trailing space keeps
   target 1 apart from target 10) **and** its dataspace, when it names one, is the project's stored master
   dataspace (a mapping written before 2026-10-12 names none: the label alone then decides). Otherwise it is
   listed as ineligible, `foreign` or `shared or foreign` with both names; a mapping that could not be read is
   ineligible too (`ProjectDeletionPlan.distributionFlowReason`, pure). A delivered project's items are
   ineligible with the delivered reason, like every other generated item. A mapping already gone is
   eligible and reported `SKIPPED_ABSENT` (same marker as a landing flow, `no mapping <id>`).

## Connections and catalogue sources (lot C, 2026-10-17)

Written 2026-10-17 on branch `fix/project-deletion-connections` (worktree `ebx-mdm-factory-del-conn`, from main `54eab261`).
**Measured before the change, read only:** every automated run of the e2e scenario and every real project built from uploaded
sample files left three Connectors connections (`EcomClientsE2e1017183516`, `MagasinsVentesE2e1017183516`,
`FideliteMembresE2e1017183516`, type `sample-file`) and their three catalogue sources (`ECOMCLIENTSE2E1017183516`...) behind after
`POST /projects/{code}/delete`: 18 sources of that family on the instance that day. One such source holds 1 asset, 13 columns,
2 catalogue flows (`Catalog scan: ID`, mappings 550/551 with their child `Catalog scan columns: ID`), and its connection 13
`FieldProvenance` rows and 1 `ConnectorOperationalMetrics` row (no `ConnectorProperty`, no `SchemaRefreshState`, no `Publication`).

### How the factory knows it created them (no XSD field)

Nothing new in the model: the proof is the project's own `PipelineStep` rows, the same shape as `dataspace.created == true`
(`project/ConnectionProvenance`, pure, `ConnectionProvenanceTest`):

| Step | Scope | Written by | `detail` |
|---|---|---|---|
| `SOURCE_CONNECTION` | the connection id | `SampleSourceImporter`, right after it writes the Connectors row of an uploaded file | `{"connection":{"id","type","created"}}` - `created: true` when the row did not exist, `false` when the importer found one (same file name uploaded before) |
| `SOURCES_RUN` | the connection id | the sources job that makes the connection a ready catalogue source (`SourcesRun`) | `{"connection":{"id"},"catalogSource":{"id","created"}}` - `created: true` only when `connectors_catalog_source(apply=true)` answered `action: CREATE, applied: true` (`SourceOnboarder.Created`, filled even when a later step throws) |

A step of any status counts (a job that created the source and then failed to scan it must still be cleaned up). A step with no
readable detail proves nothing - steps written before this lot, a crash before the detail was written - and the object is then
**kept**, never guessed to be the factory's. `SOURCE_CONNECTION` is not in the `PipelineStep.step` enumeration of the XSD, like
`SOURCES_RUN` (a static enumeration is non-blocking, section 2 of CLAUDE.md); it has a label (`step.SOURCE_CONNECTION`), so
`StepLabelsTest` stays green. **No XSD change, no restart needed for the model.**

Not covered by a trace, therefore always kept: connections and sources of projects created before this lot; a catalogue source
registered from the step 2 panel's own "Create the catalogue source" button (`OnboardStepRun`), the REST `/sources/onboarding/*` routes
and the deduced scan's `DeducedScanCatalog.ensureSource` (none of them goes through `SourcesRun`). Cleaning the leftovers of
earlier runs is a by-hand job (list below).

### The two new kinds, and when each is offered

`Kind.CATALOG_SOURCE` and `Kind.CONNECTION`, option `connections`, listed LAST in the plan (the flows that run on a connection, and a
catalogue source's own flows, must be gone first). Only objects with a trace are listed: a connection the person picked from the
existing catalogue has no `SOURCE_CONNECTION` step and is not mentioned at all. Rules, in the order they are checked
(`ProjectDeletionPlan.catalogSourceItem` / `connectionItem`, wording in `ProjectDeletionPlan.ConnectionReasons`, pinned by
`ProjectDeletionConnectionsTest`):

1. a delivered project: every item ineligible (as everywhere);
2. **a catalogue source** is eligible only when the factory created it AND created its connection too (a source created on a
   connection that was picked, or that the importer merely found, is kept: other people may rely on its scan);
3. a source whose `connector` is no longer the connection (changed by hand) is kept;
4. **shared across projects**: any `ProjectSource` of another project whose `connector` is the connection or whose `catalogSource` is
   the source keeps both ("shared: project Alpha also uses this connection");
5. something still uses the source and is NOT removed with it: a workflow launcher scoped to it, an output port targeting it, a data
   product including its assets, an access request on them, a product field fed by its columns - kept, naming the count;
6. **a connection** is eligible only when its step says `created: true`, it is a `sample-file` one (the only type the factory ever
   creates), no other catalogue source names it, its own source item is not kept, every mapping still running on it is removed by this
   very plan (an eligible `LANDING_FLOW` / `DISTRIBUTION_FLOW` item of the same id) and no `Publication`, `Conflict` or `AccessRight`
   default-model row references it.

A connection waiting for a flow of the `flows` option is eligible and carries `needsFlows`; `validate` then refuses `connections`
without `flows` ("select 'flows' too"), like `dataspaces` without `matching`.

### What deletes them, and why

Connectors has **no tool and no contract call that deletes a connection** (read in the sibling's `main`, 2026-10-17:
`ConnectorsTools` offers `connectors_mapping_delete` only; contract 1.2.0 offers `flows.delete`, which is by mapping too). The data
catalog has no deletion tool at all. So, as the repository already writes into both modules' datasets (`SampleSourceImporter`
creates the connection row, `DeducedScanCatalog` creates sources), `ebx.ConnectionCleanup` deletes by `Procedure`, in batches of
1,000 through `VolumeBatchOps`, and the catalogue's own flows go through the supported tool:

1. `CATALOG_SOURCE`: re-read live; **kept** (`KEPT_IN_USE`, not a failure) if the source now names another connection or something
   uses it; then each catalogue flow of the source (a mapping of the connection labelled `Catalog <kind>: <source id>`, the id compared
   exactly) through `connectors_mapping_delete` (`GeneratedObjectDeleter.deleteMapping`, absent only on Connectors' own `no mapping`
   wording); then, children first (EBX never cascades a delete), lineage edges, profile snapshots, column samples, alerts, jobs, tag and
   term suggestions, columns, assets, the source row;
2. `CONNECTION`: re-read live; **kept** if a mapping, a catalogue source or a publication/conflict/access right still names it, or it is
   not a `sample-file` one; otherwise its `ConnectorProperty`, `SchemaRefreshState`, `FieldProvenance` and `ConnectorOperationalMetrics`
   rows, then the connection row. The sync log is kept, as for flows.

Already gone is `SKIPPED_ABSENT` (no row found - there is no foreign wording to match, the rows are looked up directly). Each item is
reported like the others: `generated[]` of the synchronous answer carries `kind`, `name`, `outcome` (`DELETED`, `SKIPPED_ABSENT`,
`KEPT_INELIGIBLE`, `KEPT_IN_USE`, `FAILED`) and `message`; the report screen has a label for each outcome; the background job's one-line
message ends with " Kept: ..." for anything `KEPT_IN_USE`, and the kernel log has a WARN for it. Reads of the catalogue go through the
system reader declared in `CatalogReadSites` (one new site); the coupling to the sibling modules is listed in
`CONNECTORS-COUPLING.md`. Needs the `ebx-connectors` MCP server (preflighted for `flows` and `connections`).

### Leftovers of earlier runs (by hand)

Nothing before this lot left a trace, so the screen cannot tell a sample-file connection it created from a seed
(`PersonsSalesforceSample`, `SampleFile`) or from one still in use. Remove by hand, after checking each is used by no project:
the connections `*E2e<digits>` and their catalogue sources `*E2E<digits>` (Administration - Connectors; the data catalog's
Sources), mappings `Catalog scan[ columns]: *E2E<digits>` first.

## Distribution targets: what goes with the project, and what does not

Part 2 (the project's own rows) now also deletes, in the same transaction as the other bounded tables, the
project's `DistributionTarget` rows and its `LoadPause` rows of kind `DISTRIBUTION_TARGET` (plain text key,
no foreign key). Its `DistributionEvent` rows already went before, with the volume tables (they point at a
target, so the target can only go after them). Both counts appear in `rowCounts`/`rowsDeleted`. The rows
are deleted whether or not `flows` was ticked, exactly like the landing tables' own rows: ticking `flows`
is what removes the mappings, and a mapping left ineligible is left in Connectors with nothing in the
Factory pointing at it any more. `connectors_mapping_delete` cascades the mapping's rules, inputs,
sub-object mappings, **checkpoints and outbound states** (`ConfigTransfer.cascade` in Connectors, read
2026-10-12 on the shared checkout; the tool's own note says so), and keeps provenance and conflict rows
without the mapping reference and the sync log.

**Not removed, reported:** a SCHEDULED target may name a Scheduler REST schedule (`scheduleKey`) and a workflow
launcher row (`launcherName`). No code in this module deletes either (`DistributionScheduler` only creates
them and nothing calls it), so the delete answer carries `schedulerLeftBehind` (one line per target) and the
kernel log a WARN: delete the schedule (`DELETE` on the Scheduler REST API) and the launcher by hand. The
outbox dataset (`DistributionOutbox`) lives in the master dataspace and goes with it under `dataspaces`.
A step reset does not touch targets or their mappings: they are configuration of the project that stays valid
when a stage is regenerated (the mappings name the master dataspace, which is recreated under the same name).

## Execution order, and why

Match and Merge business objects (lot M18), then matching configurations, then Connectors flows (landing, then distribution), then dataspaces (master, staging, landing), then data models
(master, staging, per-source landing) — exactly `Plan#items`' own order, which is also the order `delete`
attempts them in. Matching goes first for the metadataset-orphaning reason above. Dataspaces go before data
models for a related, if softer, reason: a dataspace deletion removes the *datasets* built on a data model,
but **`delete_data_model` does not delete the DMA publication object itself** — measured live in
`docs/MASTER-ZONE.md` §10 (`ebx.MasterReset`'s own Javadoc quotes the `data-model` server's own contract text
in full: *"Deleting the model does NOT delete any of [the datasets built on it] ... it only orphans them"*).
Concretely: if a project is regenerated later under the exact same code, the new `master/generate` builds a
model under the identical computed name and **re-attaches to the surviving publication object** rather than
creating a fresh one — the same "half-deleted" situation `docs/MASTER-ZONE.md` §10/§11 already documents and
already has a repair path for (`master.MasterFacetRepair`), not a new failure mode this lot introduces.

**`delete_matching_config` is four non-transactional steps, per its own tool contract** (read, not re-derived
here) — a call can leave an orphaned metadataset behind if it is interrupted partway; `ProjectDeleter` reads
`metadatasetDeleted`/`metadatasetOrphaned` off the answer and reports either honestly rather than assuming
success cleaned up everything.

**The Connectors mapping delete keeps the sync log.** `connectors_mapping_delete` removes the mapping row
itself; nothing in this lot touches `connectors_log_search`'s own history of past runs — a deleted project's
sync history simply becomes orphaned-but-harmless log rows, the same way a manually-deleted mapping already
behaves outside this module.

**A mapping that is already gone answers `no mapping <id>`, not "not found" (measured 2026-09-28).** Project
PersVol4's deletion stopped on its four `LANDING_FLOW` items (mappings 292, 294, 296, 298, all verified absent
with `connectors_mapping_get`): `ConfigTransfer.deleteMapping` on the Connectors side reports
`{"error": "no mapping 292"}`, and `GeneratedObjectDeleter.looksAbsent` only recognised "not found" /
"does not exist" / "unknown" — so a flow whose mapping had been removed by hand (or by a step reset) was a
FAILURE that blocked the whole project, under the very message promising the opposite. The matcher now
accepts the Connectors wording too (its read tools say `No mapping '292'`); `GeneratedObjectDeleterTest`
pins both, plus two texts that must stay failures. `StepResetter` shares the helper, so a step reset of the
flows behaves the same.

**A data model that is already gone is a THROWN error on 6.3.0-rc2, not an error result (measured
2026-09-29).** Once the flows passed, the same PersVol4 deletion stopped on its five models: the `data-model`
server raises a JSON-RPC error (`Tool invocation failed: Data model not found: 'PersVol4_Master'. Call
list_data_models ...`), the client throws `McpException`, and the per-item `catch` reported every thrown
error as a failure without ever reading its text. `GeneratedObjectDeleter.deleteDataModel` and
`deleteMapping` now catch the exception themselves and classify it with `fromThrown`, against ONE exact
marker per tool (`data model not found`, `no mapping `). The marker is deliberately narrower than
`looksAbsent`: a thrown error also covers "MCP server not found", "Unknown tool" and "Access denied", and
reading one of those as "already gone" would delete the project's rows while its models still exist — the
test pins those three as failures.

## The two routes

- `GET /projects/{code}/deletion/plan` — read-only, administrators only (it probes `get_matching_config` with
  the factory's one agent account, like every route that reaches an MCP server): the row counts of the project's own factory rows, plus one item per generated object with
  its eligibility and (if ineligible) why.
- `POST /projects/{code}/delete?confirm=<code>&generated=matching,flows,dataspaces,models,connections` — administrators
  only, the same restriction `/master/reset` already has (it acts with the factory's one agent account on the
  MCP servers and performs irreversible repository-level deletions). `generated` is optional and
  comma-separated (`matching`, `flows`, `dataspaces`, `models`, `connections`); blank/omitted deletes none of the generated objects, only the project's own rows.
  `confirm` must equal the project code exactly, the same ceremony `/master/reset?confirm=` already uses.

## The screen

A toolbar/perspective action on `/root/Project` (`ProjectDelete`), or reached with a `project` launch
parameter from the explorer's new "Delete" card button — the same technique `ProjectWizardServiceDeclaration`
already uses for its own `project` parameter (a `/ebx/` web-component route passes a plain query parameter
through where an `ebx-ui` route would silently drop it, CLAUDE.md's own note). Plan mode shows the project's
own row counts (always deleted, no checkbox), one block per option with a checkbox pre-checked when the block
holds at least one eligible item and disabled when it holds none, each item listed — eligible with its
detail, ineligible greyed out with its reason — then a "type the project code" confirm field. Once a delete
has actually run, the same page switches to report mode: the same row counts (now "deleted"), the same items
carrying the outcome the delete call actually recorded, and a link back to the project explorer; the submit
button is withheld from that point on (the service instance survives the round trip that renders the report,
so re-submitting would just fail loudly against a project that no longer exists).

## Seen running (2026-09-23, 11:34, main 77c546d installed on the Test 6.3.0 instance)

A throwaway project `ScratchDel` (one source, the `PersonsSalesforceSample` connector, landing planned,
generated and its flow written - never staged) was deleted **from the screen** by the product owner with the three
offered options ticked (`flows`, `dataspaces`, `models`; `matching` was empty and disabled, as expected for a
project with no master zone), after typing the project code. Verified right after, read-only:

- `GET /projects/ScratchDel/deletion/plan` before the delete listed exactly `LANDING_FLOW 239`,
  `LANDING_DATASPACE ScratchDel_Landing` and `LANDING_MODEL ScratchDel_Landing_SALESFORCE`, all eligible, and
  25 factory rows (18 `LandingColumn`, 1 `LandingTable`, 1 `ProjectSource`, 3 `PipelineStep`, 1 `Project`, the
  rest 0);
- the refusals answer 409 with their reason: a wrong `confirm`, an unknown `generated` value (`datasets`), an
  unknown project code;
- after the delete: the `Project` row answers 404 over Data Services, every scoped table reads 0 rows and the
  `/status` counts went back to what they were before the project was created; `kernel.log` shows EBX's own
  `Changing state of branch "ScratchDel_Landing" to deleted.` / `... version ... to deleted.` followed by the
  factory's summary line with `rowsDeleted={... LandingColumn=18, LandingTable=1, ProjectSource=1,
  PipelineStep=3, Project=1}`; `list_data_models` no longer lists `ScratchDel_Landing_SALESFORCE`;
  `connectors_mapping_get(239)` answers `No mapping '239'`; the report screen showed the three outcomes and the
  explorer link.

### Second run, the `matching` option (2026-09-23, 11:50, same instance)

A second throwaway project `ScratchMatch` (the two Persons sample sources, SALESFORCE and SAP) was driven
through the whole chain over REST - landing (plan, generate, flows, load for both sources), `staging/suggest`,
every proposal accepted by a Data Services `PUT` on `status`, `staging/generate` + `load` (345 rows),
`master/generate` + `load` (two promoted tables), `matching/suggest-llm?entity=SalesforceSample`, the
`MatchPlan` accepted the same way, `matching/configure` (activation `ENABLED`, metadataset
`Master_SalesforceSample_MDS` present, checked before the delete). Then
`POST /projects/ScratchMatch/delete?confirm=ScratchMatch&generated=matching,flows,dataspaces,models`:

- the plan listed 11 items, all eligible except `MATCHING_CONFIG /root/SapSample` ("no matching configuration
  found ... nothing to delete" - the live `get_matching_config` soft miss, as designed);
- `generated=dataspaces,models` alone was refused first (409, "select 'matching' too, so the configuration is
  removed first") - the metadataset dependency rule, live;
- the delete answered 200 with `MATCHING_CONFIG /root/SalesforceSample -> DELETED "matching configuration and
  its metadataset deleted"` (so `delete_matching_config`'s `metadatasetDeleted` does sit where `MameAnswer.field`
  reads it), the SAP one `KEPT_INELIGIBLE`, both flows (245, 241), the three dataspaces and the four data
  models `DELETED`, and 504 factory rows deleted (345 `Xref`, 43 `StagingAttribute`, 39 `AttributeMapping`,
  39 `LandingColumn`, 20 `PipelineStep`, 11 `AgentStep`, ...);
- verified after: every dataset URL of the three dataspaces and the metadataset answer 404, `kernel.log` shows
  the six "Changing state of branch/version ... to deleted" lines in the master, staging, landing order, the
  add-on's `TableConfiguration` table no longer mentions the project, `get_matching_config(/root/SalesforceSample)`
  answers `CONFIG_NOT_FOUND`, `list_data_models` lists none of the four models, both mappings answer "No mapping",
  and the factory's `/status` counts are back to their pre-test values.

One defect found on the way, unrelated to deletion and left to its own session: `master/generate` refuses a
promoted entity carrying an accepted attribute named `id` (the Salesforce record id) because the master model
adds its own auto-increment key `id` (`SubTypeAlreadyExistsException ... AspectKey[id]`); the test rejected that
attribute to move on.

## Matching configurations of business-object members (lot W13, 2026-10-12)

Measured on the scratch model W13BO (RC3): `delete_matching_config(table, dataModel)` on a table that belongs to a Match and Merge business object is REFUSED
(`INVALID_PARAMS "Table /root/Personne is part of a business object; use the business-object tools instead"`), and it stays refused even after the business object is
deleted from the data model and the model republished - the add-on keeps its own business-object record (`TableConfiguration` rows of the model remain in
`Bebx-addon-mame/ebx-addon-mame-configuration`), and the match-merge server has no business-object delete tool. What the deletion does:
- `GeneratedObjectDeleter.deleteMatchingConfig` recognises exactly that phrase ("is part of a business object", `isBusinessObjectMemberRefusal`) and answers the outcome
  `KEPT_BUSINESS_OBJECT` with the reason code `engine.external.matchingConfigKeptBusinessObject` (EN/FR sentence: the configuration of the table stays in place, the
  metadatasets go with the master dataspace, the rows must be removed in the add-on's administration screen). It is neither `DELETED` nor `FAILED`: the plan goes on
  and the project's rows are removed as usual. The line of the answer carries `outcome: KEPT_BUSINESS_OBJECT`, the message and `reasonCode`. Any other refusal stays a failure.
- When `models` or `dataspaces` is ticked (project deletion), or the master model or dataspace was deleted (step cleanup), and at least one configuration was kept, the answer carries
  `orphanedMatchingConfiguration`: the rows of those tables are orphaned in the add-on's dataset `Bebx-addon-mame/ebx-addon-mame-configuration`, table `/root/TableConfiguration`
  (filter on `dataModel = urn:ebx:publication:<master model>`), to remove in the add-on's administration screen. The note is also a WARN in `kernel.log`.
- This lot only reports: nothing is written into the add-on's dataset (no Data Services delete). **Superseded by lot M18 (below): the deletion now removes the
  business-object configuration itself, before the members' configurations.**

## Match and Merge business objects: the add-on's own rows (lot M18, 2026-10-18, branch `fix/deletion-business-object-config`)

**The gap.** Measured 2026-10-18 14:10 after 25 test projects were deleted with this route (`matching,flows,dataspaces,models,connections`, every job DONE, dataspaces
and models gone): the add-on's configuration dataset (`Bebx-addon-mame/ebx-addon-mame-configuration`) still held 17 `/root/BusinessObjectConfiguration` rows and 38
`/root/TableConfiguration` rows (36 of them linked to a business object by `FKBusinessObject`) for the deleted publications (`dataModel =
urn:ebx:publication:E2EMASTER…_Master`). A business-object configuration whose model is gone breaks the add-on at the next Tomcat start
(`AbstractMameRegisterAddon.migrateMergePolicyDateField`, NPE, the add-on does not register - CLAUDE.md section 8). The `MATCHING_CONFIG` items ran
`delete_matching_config`, which the add-on refuses on the MAIN table of a business object ("use the administration screen") and which never touches the business-object row.

**What the deletion does now** (option `matching`; exact calls, in this order; `ProjectDeleter` executes `Plan#items` in the plan's own order):

1. `MATCHING_BUSINESS_OBJECT` (one item per business object of the master publication, name = the main table, e.g. `/root/Client`, detail `Business object of Client:
   Client, Adresse. ...`), `GeneratedObjectDeleter.deleteBusinessObjectConfig` -> `MameConfigRows.unlinkAndDelete`, no MCP call, the Java API with the caller's session on the
   add-on's dataset: (a) for every `TableConfiguration` whose `FKBusinessObject` is the business object's id, one `Procedure` per batch sets `FKBusinessObject` to null
   (what the 36 Data Services `PUT`s did by hand); the unlink is read back (no link may remain, else the row is NOT deleted); (b) one `Procedure` deletes the
   `BusinessObjectConfiguration` row (17 `DELETE`s by hand); read back. The `TableConfiguration` rows are never deleted here: deleting them by Data Services leaves
   their hidden merge policies pointing to nothing and the next boot fails in `MergeRelationService` (measured).
2. `MATCHING_CONFIG` (one item per promoted master table that has a configuration): `delete_matching_config(table, dataModel = urn:ebx:publication:<model>)` on the
   `match-merge` server. The member is now a plain table, so the add-on removes its `TableConfiguration` row, its hidden merge policy and its metadataset. **The MAIN table of the object is
   refused as long as the published model declares the object: see "Second round" below - the object is removed from the model, the model republished, and the call repeated once.** This needs the
   master model: the business-object item and the member items come BEFORE the dataspace and model items in the plan (`ProjectDeletionPlan.build`; pinned by
   `ProjectDeletionBusinessObjectTest` and `ProjectDeleterKindsTest`).
3. Connectors flows, master / staging / landing dataspaces, master / staging / landing models, connections (unchanged).
4. The read-back: when `matching` was ticked, the answer carries `matchingConfigRowsLeft` = `{businessObjectConfigurations, tableConfigurations, rows[]}` read from the
   add-on's dataset for the master publication; when anything is left the answer's `orphanedMatchingConfiguration` says how many rows and which routes clean them, and a
   WARN goes to `kernel.log`. The live rows are the truth, not the outcomes of steps 1-2.

**Guards.** A business-object item that FAILS (a write refused, a link still there after the unlink) keeps the master dataspace and the master model (`KEPT_INELIGIBLE`
with the reason) so the retry still has the model the add-on needs; the call stops before the project's own rows, as for any failure. The business-object item is eligible
like the master zone it belongs to (a `TARGET_EXISTS` project, a delivered project, a master dataset the factory has no proof it created: kept). `validate` refuses
`dataspaces` without `matching` when an eligible business-object item exists, as for an eligible member configuration. The kind is labelled in the screen (`delete.kind.MATCHING_BUSINESS_OBJECT`,
EN/FR). A member whose `delete_matching_config` is STILL refused with "is part of a business object" stays `KEPT_BUSINESS_OBJECT` (the W13 branch above) and is reported by the read-back.

### Second round: the MAIN table's configuration survived (measured live 2026-10-18 15:56, Factory 1.2.0-296)

Two projects (E2EMASTER20261018134344 and ...134921, FactoryJob 778/779) deleted with `tools/e2e/b2c_master.sh --cleanup-project`: the business-object part worked (`add-on dataset:
business object configuration 27 deleted after unlinking 2 table configuration(s)`) and the members' configurations went (`ebx-addon-mame.log`: `Delete merge policies of table config Id: 142`,
then 144; 145, 147), but `/root/Personne` (table configurations 143 and 146) stayed, there was NO `Delete merge policies of table config` line for it, the job ended DONE with the
WARN "still holds 0 business-object configuration row(s) and 1 table configuration row(s)", and the e2e check failed. The job row told nothing (`FactoryJob.detail` is empty for a DONE job;
the per-item outcomes lived only in the synchronous route's JSON).

**Cause** (read by `javap -c` / `-v` on `MameConfigWriter.rejectIfBusinessObjectTable`, add-on 6.3.0.5-RC3; the item for `/root/Personne` was planned and executed - `get_matching_config` is
answered for a business-object main table, checked on Vol1mR_Master - and the tool answered its business-object refusal, which the deletion reads as `KEPT_BUSINESS_OBJECT`, a DONE job,
not a failure): `delete_matching_config` refuses a table when `SchemaNode.getBusinessObjectModel() != null` **or** when its stored configuration still names a business object. The first
condition is true for the MAIN table of an object as long as the PUBLISHED DATA MODEL declares that object (`data-model` `get_data_model_business_objects` lists `PersonneBO` on
`/root/Personne`); it does not read the add-on's rows. So it is not a cache or a delay: no re-read and no retry can change it. Members only needed their `FKBusinessObject` cleared.
It is not the hidden-metadataset case either (the processor is never reached).

**Fix.** `GeneratedObjectDeleter.deleteMatchingConfigUndeclaringBusinessObject`, used by the project deletion's `MATCHING_CONFIG` case: `delete_matching_config`; if (and only if) the answer is the
business-object refusal, `execute_query get_data_model_business_objects` finds the object rooted on that table, `execute_operation delete_business_object {"name"}` removes it from the model,
`publish_data_model` republishes the model, and `delete_matching_config` is called again - once. The first answer is kept in the outcome's message trail; a table the model declares no object
for keeps the first answer; a failure of the removal or of the republication is a FAILED item (the call stops before the project's rows, and a retry finds the item again because the table
configuration is still there). A step reset never undeclares (it keeps the model). The model loses its business-object declaration: harmless once the matching configurations are deleted, and a
later master generation declares it again (idempotent).

**Reading a failure next time.** Each matching item's outcome and the add-on's own answer (the raw tool text, clipped) are written to `kernel.log` (`project X deletion: MATCHING_CONFIG /root/Personne
-> KEPT_BUSINESS_OBJECT: ... | add-on answer: {...}`), to the synchronous route's `generated[].answer`, and to the job's message (`Matching: business object /root/Personne DELETED; /root/Adresse DELETED;
/root/Personne KEPT_BUSINESS_OBJECT (<answer>)`, then `The add-on's dataset still holds N business-object and M table configuration row(s)` when the read-back finds some).

### Third round: the main table's configuration last (measured live 2026-10-18 22:17, lot S9, branch `fix/deletion-matching-config-order`)

On a master model whose real-time target installed the distribution triggers (`DistributionAfterModify` reads `Linked_Field_State`), deleting the MAIN table's configuration made the model fail to recompile ("Cannot resolve symbol 'Linked_Field_State'"): the add-on answered `TABLE_NOT_FOUND` "The configuration was deleted but table /root/Personne no longer resolves after the schema reset." for it, and `Table not found: /root/Adresse` for every configuration deleted after it, which stayed behind as orphans (E2EMASTER20261018193244 and ...200348). Two changes. (1) `ProjectDeletionPlan.matchingInDeletionOrder` plans the `MATCHING_CONFIG` items after the business object, every table that is not the main table of a business object first (path order), the main tables last. (2) `GeneratedObjectDeleter.isConfigurationDeletedButTableUnresolved` classifies that add-on answer as DELETED (reason = the add-on's text), pinned to the message prefix `The configuration was deleted but table `, never to the bare code: a plain `Table not found` stays `SKIPPED_ABSENT`. When configurations still stay behind that way, the closing WARN now names the likely cause (the distribution triggers reading `Linked_Field_State`).

#### Fourth round (lot F3, 2026-10-19): the triggers no longer depend on the configuration

The generated distribution triggers (`DistributionTriggerBody`) now reference no Match and Merge field at all (no `Linked_Field_State`, no `GoldenId`): they only enqueue {table, key, change}, and the live/not-live decision is the dispatcher's, in Java, from the metadataset. Deleting a table's matching configuration therefore can no longer stop the master model compiling, wherever it happens: project deletion, `StepResetter` ("reset matching" / the domain reset), `OrphanMatchingConfigCleanup`, or an administrator in the add-on's screen. `StepResetter` and `OrphanMatchingConfigCleanup` need and have no trigger removal. The `DISTRIBUTION_TRIGGERS` item below stays as it is (harmless, and it keeps the model clean for a project that is going away); a project whose triggers were installed by the older generator keeps the old (fragile) bodies until "Install triggers" is run again, which now rewrites exactly the bodies that differ.

#### Third round, part two: the distribution triggers go first (`DISTRIBUTION_TRIGGERS`, lot S9)

The order alone is not enough: `DistributionTriggerInstaller` declares its three script triggers (`DistributionAfterCreate`, `DistributionAfterModify`, `DistributionAfterDelete`) on EVERY promoted master table, and `detectMameFields` makes each read `Linked_Field_State` wherever the table carries it, so the member tables' triggers break the compile just as the main table's do (the inert member configurations of lot M8 give them the field). A new plan item `DISTRIBUTION_TRIGGERS` (option `matching`, named after the master model, eligible like the master zone) is therefore planned FIRST, before the business object and every `MATCHING_CONFIG`, and appears only when the project has a `DISTRIBUTION_TRIGGER_INSTALL` step of any status (a failed install may have declared some); its tables are the promoted master tables the installer used. It runs, per table and per name, `unset_element_property(data_model_name, element_path, property="triggers", item=<name>)` (the call shape of the intake and derivation removals; never without `item`, which would drop the Java, derivation and intake triggers too), then ONE `publish_data_model`. Not declared = `SKIPPED_ABSENT`: the refusal reads like `ForeignWording.DATA_MODEL_ALREADY_REMOVED` (UNVERIFIED wording) or the scripting server's `get_script` positively says the script does not exist, or the model is gone (`data model not found`); anything else is a failure, and then the matching configurations, the business object and the master model and dataspace are KEPT (`KEPT_INELIGIBLE`) for the retry. Not verified live.

### The orphan sweep: rows already left behind

`GET /matching/orphans[?publication=<name>]` (read-only) and `POST /matching/orphans/delete` (both ADMINISTRATOR, `core/FactoryRights`; `ebx/OrphanMatchingConfigCleanup`,
pure rules in `project/MatchingConfigRows`). They need the `data-model` server (`get_publication` says whether the model is there); the deletion also needs `match-merge`.

- A publication whose model is GONE (`get_publication`: `found=false`) is listed on its own; one whose model still exists is listed only when named with `?publication=`.
  A model the server cannot answer for is `UNKNOWN`: listed when named, never acted on. Rows whose `dataModel` is not `urn:ebx:publication:` (module XSDs) are ignored.
- Answer: `{"publications":[{"publication","model":"GONE|EXISTS|UNKNOWN","ownedByLiveProject","eligible","action","reason","businessObjects":[{"id","mainTable"}],
  "tableConfigurations":[{"id","table","businessObject"}],"tableConfigurationsKept"}],"count","eligibleCount","confirmWith","publication","rowsRead":{...}}`.
- Eligible: model GONE with at least one business-object row (`UNLINK_AND_DELETE_BUSINESS_OBJECTS`: unlink, delete the row; the table configurations of a vanished model STAY -
  they are harmless without a business-object row, deleting them breaks the next start; they remain listed); or named, model EXISTS, not the master model
  (`<code>_Master`) or domain model (`<code>_Domain`) of a live Factory project (`UNLINK_DELETE_AND_REMOVE_TABLE_CONFIGS`: the same unlink and delete, then
  `delete_matching_config` on each table configuration, which needs the model). A publication with only table configurations and no model is listed, not eligible.
- `POST /matching/orphans/delete` body `{"publications":["E2EMASTER20261018115006_Master", ...],"confirm":"<n>"}` (bare names, 1 to 100): re-reads live, `confirm` must be
  exactly the number of the named publications that are eligible NOW (else 409, nothing changed); answers `{"results":[{"publication","result":"DELETED|KEPT|ABSENT|FAILED",
  "reason"|"message","rowsLeft"}],"cleaned","kept","absent","failed"}` and logs each to `kernel.log` (`orphan matching configuration cleanup by <user>`). Synchronous.
- The e2e cleanup (`tools/e2e/b2c_scenario.py`, `--cleanup` and `--cleanup-project`) ends with `GET /matching/orphans?publication=<project master model>`: **FAIL** when the
  add-on still holds a business-object or table-configuration row of the project's publication (the report's left-behind list names it); `SKIP` (never PASS) on a
  build without the route. The same cleanup now accepts `MATCHING_CONFIG` / `MATCHING_BUSINESS_OBJECT` items named after master TABLES (`/root/Client`): before, the
  "plan touches only this run's objects" guard would have refused to delete any project that had a matching item.

### Not verified (lot M18)

Written, unit-tested (`MatchingConfigRowsTest`: the classifier on a fixture shaped like the live dataset; `ProjectDeletionBusinessObjectTest`: the item, its order before the
members, the dataspaces and the models; `MameConfigRowsSourceTest` and `ProjectDeleterKindsTest`: the add-on writer deletes only a business-object row and unlinks only
`FKBusinessObject`, the guard, the read-back) and run in the e2e self-test against the stand-in factory. **Never run against EBX**: nothing was written into the live instance
(the 2026-10-18 cleanup of the 17 + 36 rows had been done by hand). Unproven live: that the Java `doModifyContent` of `FKBusinessObject = null` and the `doDelete` as the
caller's session are accepted the way the Data Services calls were (same permissions, same triggers expected); that `delete_matching_config` really accepts a member and
the MAIN table once the business-object row is gone (the task states it; CLAUDE.md measured it refused only while the row existed); `get_publication` answering `found=false`
for a deleted publication was read live on 2026-10-18 (`E2EMASTER20261018115006_Master`: `PUBLICATION_NOT_FOUND`, `get_data_model`: `DATA_MODEL_NOT_FOUND`). The 38 orphan
`TableConfiguration` rows already on the instance stay (the sweep keeps them on purpose). `StepResetter` (step cleanup) was not changed: its matching reset keeps the model,
so no row is orphaned, but a business-object member there is still `KEPT_BUSINESS_OBJECT`.

## What was not verified

Lot C (connections and catalogue sources): written and unit-tested only (selection rules, provenance reading, wording, option and
outcome labels); `ConnectionCleanup` was never run against a live instance - no import, no deletion, no Tomcat restart in that
lot. The queries it builds (`./connector=` on `Mapping`/`FieldProvenance`, `./asset=` OR-chains on the catalogue tables) follow the
shapes the rest of the module already uses; the first real run is the check.

The "already absent" branches (a second delete of an object removed by hand) and a partial failure stopping
before the factory rows: both reasoned from `ebx.MasterReset`'s proven techniques, never provoked live —
until 2026-09-28, when the flow branch was provoked for real by PersVol4 and turned out broken (see "A
mapping that is already gone" above); the data-model branch was provoked the next day by the same project and was
broken too (thrown error, see above); the dataspace and matching-configuration absent branches remain
unprovoked.

## Orphan connections (lot of 2026-10-17, branch `feature/orphan-connection-cleanup`)

Projects deleted before the `connections` option existed left sample-file connections and catalogue sources behind, with no `SOURCE_CONNECTION` /
`SOURCES_RUN` step to prove the factory made them (the steps went with the project). This lot removes them on request, administrator only, in two routes
(`rest/FactoryResource`, rights in `core/FactoryRights`, both ADMINISTRATOR). No XSD change, no new table, no new bundle key (the answers are plain English like
the other REST routes; `RestRouteRightsTest` covers both routes).

**No second implementation.** `ebx/OrphanConnectionCleanup` (EBX side) and `project/OrphanConnections` (pure rules) call the project deletion's code:
`ConnectionCleanup.readConnection` / `readCatalog` / `sourceIdsNaming` to gather (every blocker it knows), `ProjectDeletionPlan.catalogSourceItem` /
`connectionItem` to judge (the same sentences), and `ConnectionCleanup.deleteCatalogSource` (catalogue flows by `connectors_mapping_delete`, then the rows
that point at the assets and columns, columns, assets, the source) then `deleteConnection` (the dependent Connectors rows, then the connection) to delete. For
an orphan the connection counts as the factory's own (`OrphanConnections.claim`): nobody else is left to claim it.

### What is an orphan

A Connectors connection of type `sample-file` that NOTHING of a live project references: no `ProjectSource.connector`; no `ProjectSource.catalogSource` whose
catalogue source names it; no `SOURCE_CONNECTION` / `SOURCES_RUN` step of an EXISTING project (a step with an unreadable detail still counts, by its scope);
no `DistributionTarget.connector`. Other connection types are never read. The seed `SampleFile` connection of ebx-connectors (`local://sample-file`) is listed
as kept, never deleted. Eligible = the plan's rules say so: the catalogue source (the only one naming the connection) is not used by a data product, access
request, workflow launcher, output port or product field; no mapping other than the source's own catalogue flows (`Catalog <kind>: <source id>`) runs on the
connection; no publication, conflict references it; not re-pointed by hand. Two or more catalogue sources naming a connection keep it (which one is its own is
not decidable).

### `GET /connections/orphans[?namePattern=E2e]`

Read-only. `namePattern` is a plain, case-insensitive substring (never a regex) tested on the connection id, its label and its catalogue source ids.
Answer:

```
{ "orphans": [ { "id", "label", "type": "sample-file", "created", "lastModified" (ISO, from the Connectors row),
                 "catalogSource": "<id>"|null, "otherCatalogSources": [...], "assets", "columns", "catalogFlows": ["550","551"],
                 "blockers": ["mapping 700 (Landing: Ecom)", "2 publication(s)", "1 data product(s) including its assets", ...],
                 "eligible": true|false, "reason": null|"<why kept>" } ],
  "count", "eligibleCount", "sampleFileConnections", "referencedByProjects", "namePattern", "confirmWith": "<eligibleCount>" }
```

### `POST /connections/orphans/delete`

Body `{"ids":["EcomClientsE2e1017183516", ...], "confirm":"3"}` (1 to 500 ids). It re-gathers everything live (references included), then:

- `confirm` must be exactly the number of the ids sent that are eligible NOW (decimal digits). Otherwise `409` with both numbers, nothing deleted;
- deletes only those, per id: catalogue flows, catalogue source rows, then the connection rows (same order and code as the project deletion); needs the
  Connectors MCP server (preflight, `503` when it is not usable);
- answers `{"results":[{"id","result","reason"|"message"}], "deleted","kept","absent","failed"}` with `result` = `DELETED` | `KEPT` (with `reason`: referenced
  by a live project, not sample-file, a blocker, ...) | `ABSENT` (no such connection, or gone meanwhile) | `FAILED` (an error while deleting: what was deleted
  before it stays deleted, `message` says which step);
- logs every attempt to `kernel.log`: `[mdm-factory] orphan connection cleanup by <user>: connection X (catalogue source Y) -> DELETED: ...` (plus the lines
  `ConnectionCleanup` already writes for the source and the connection).

It runs synchronously on the request (each catalogue flow is one `connectors_mapping_delete` call): a few connections at a time is the intended use.

### Not verified

Written and unit-tested only (`OrphanConnectionsTest`: references, eligibility, name filter, body, confirm guard, rights; `OrphanConnectionCleanupSourceTest`:
no second implementation, confirm before the first deletion). The two routes were never called against a live instance, and `ConnectionCleanup`'s deletions
themselves were still unproven live when this lot was written (see "What was not verified"): the first listing is the check of the gathering, the first
deletion of one connection the check of the rest. `created` / `lastModified` come from the Connectors row's technical dates (`Adaptation.getTimeOfCreation()` /
`getTimeOfLastModification()`).
