# The master (golden) zone — research and design

Research plus a design recommendation, written 2026-09-20 on branch `feature/master-study` (worktree
`ebx-mdm-factory-master-study`), answering the product owner's day-one ask ("montrer, pour chaque
enregistrement, comment il a été construit") for the last missing zone: matching, merge, and
record-level lineage on top of the staging zone that is already loaded and real. **No production code
in this document or on this branch.**

**Evidence tags** on every factual claim, the same convention `docs/SOURCE-RELATIONS.md` and
`docs/NORMALISATION.md` already established on this codebase (read first, as instructed, for style and
for facts this document builds on):

- **READ `path:line`** — read directly in this worktree's Java source or `tools/build_xsd.py`, quoted
  verbatim where it matters.
- **READ (live)** — a real, read-only MCP call against the running instance in this session
  (`mcp__match-merge__*`, `mcp__data-access__*`), with the actual JSON answer quoted or summarized
  faithfully. Nothing was written, generated-and-persisted, activated, patched, or run.
- **INFERRED** — a conclusion not directly read or measured; reasoned from the READ facts, flagged as
  a recommendation or a design choice, not a fact.

**A mid-session outage, disclosed for the record even though it resolved**: partway through this
investigation the EBX instance/MCP gateway became unreachable for a stretch (every `mcp__data-access__*`
and `mcp__match-merge__*` call answered `Unable to connect. Is the computer able to access the url?`,
across more than ten retries). Connectivity came back before the document was finalized, and
`profile_table_for_matching` on the real `Companies_Staging`/`Staging`/`Company` table — the specific
live call question 4 asks for — **was successfully run** (§4.1) once it did. The outage window is kept
visible in a few places below only where it genuinely limited what could be read (§8), not as a blanket
excuse; everything question 4 asked for is answered with real data.

Repositories/paths read: this worktree (`ebx-mdm-factory`, branch `feature/master-study`); no other
repo. `PLAN.md` (in full), `docs/LANDING-FLOWS.md`, `docs/SOURCE-RELATIONS.md`,
`docs/NORMALISATION.md`, `docs/mockups/mdm-factory.html`; the internal EBX MCP guide
§8 (read in full, as instructed) and §13. Live queries ran against dataspace `Companies` (dataset
`Companies` and the MAME metadataset `Companies_Companies_MDS`), dataspace `consolidation` (dataset
`consolidation`), and dataspace `Companies_Staging` (dataset `Staging`, table `/root/Company`, §4.1) —
the last one only after a temporary mid-session outage resolved. A background research agent read
`ebx-mdm-factory/src/main/java/com/on/ebx/factory/{staging,ebx,transfer,review}/*.java` and
`tools/build_xsd.py` in full, read-only, in this same worktree; its findings are folded in below with
the same `path:line` citations it produced, cross-checked by direct `Read`/grep where noted.

---

## Recommendation (summary)

1. **Where matching happens (§1): inside the master table itself, source and golden records
   coexisting in the same business table — never staging.** This is not a style choice; it is how the
   add-on is built, confirmed live on the real `Companies` model: `RecordMetadata.functionalId` and
   `RecordMetadata.goldenId` are both foreign keys into `/root/Companies` itself (the *same* table), and
   `activate_matching` patches that table's own schema to add the linked fields
   (`Linked_Field_GroupId`/`_State`/`_AutoCreated`/`_GoldenId`) projecting the metadata straight onto the
   business record. There is no MAME mechanism to match candidates that live in one table and write
   goldens into another — "match in staging, promote only goldens" is not how the add-on works, and is
   **rejected** as an option.
2. **GREENFIELD master model**: one table per accepted canonical `StagingEntity` (the same shape
   `StagingModel`/`StagingGenerator` already build for staging), but with a **plain auto-increment
   primary key**, never `sourceSystem`+`sourceId` — because a golden record the add-on creates has no
   source system of its own, and the real `Companies` table's own key (`Identification/Id`,
   `autoIncrement=true`) confirms this is exactly how the add-on expects a matched table to be keyed
   (§2). `sourceSystem`/`sourceId` move to `Xref`, which already carries them (§3).
3. **The member/canonical redirection is the one genuinely new mechanical piece (§2).**
   `Contact.accountIdRef` in staging points at `Account` (a rule-proposed *member* entity that is never
   promoted past staging), not at `Company` (the canonical entity Match and Merge actually runs on).
   Because `Company`'s own staging rows are keyed by their *member's own* `sourceSystem`/`sourceId`
   (confirmed on the real load: 107 `Company` rows = 55 from `Supplier` + 52 from `Account`, each still
   carrying its originating `sourceSystem`/`sourceId` — `PLAN.md`, lot 3), a master-side foreign key
   from `Contact` to `Company` can reuse the *identical* key value already computed for
   `Contact.accountIdRef`, just retargeted at `Company`'s table instead of `Account`'s. This is a
   transfer-plan rule, not new state: redirect every `StagingAttribute.referenceEntity` that names a
   *member* of some canonical entity to that canonical entity instead, at master-transfer time.
4. **Staging → master transfer (§3)**: insert as a new source record when `Xref.masterKey` is empty;
   look the existing record up by that key and update it otherwise — but **check its `RecordMetadata`
   state first**. The real `Companies` table's own `tableSettings.modifyMergedRecords` is currently
   `false` (READ (live)), meaning an update to an already-merged record is refused by the add-on today;
   this is a real, load-bearing constraint on the reload design, flagged for live confirmation (§3, §8).
   `Xref` needs **no new field** for "the current golden" — `RecordMetadata.goldenId`, read live at
   render time through `functionalId = Xref.masterKey`, already is that answer, and caching it in `Xref`
   would go stale the moment a later merge run changes it.
5. **Generating the matching configuration (§4)**: the exact ordered MCP call chain, each call classified
   read-only vs. write-and-forbidden-in-this-session by its own contract text; the real, live
   `Companies` decision tree (exact LEI, exact registration-number+country, fuzzy name+address at
   threshold 90 routed to **Suspect**, never auto-`Match`) is quoted in full as the concrete reference
   shape a generated `MatchPlan` should imitate. **`profile_table_for_matching` was run live on the real
   `Company` staging table (107 rows, §4.1) and surfaces a real, concrete gap: the canonical `Company`
   entity carries neither `LegalEntityIdentifier` nor `registrationNumber` today — only `vatNo` (44%
   filled), `name`, `city`, `country`, `legalForm`** — so only one of the three already-known duplicate
   pairs (§4.1) is catchable by the same high-precision exact-match tiers the real `Companies` policy
   uses; the other two need the fuzzy name/address tier, or the missing identifiers added to `Company`
   first.
6. **Record-level lineage (§5)**: **the add-on already exposes true field-by-field provenance** —
   `MergeValueLineageCompact` (READ (live) schema: `mergingProcessId`, `sourceField`, and an `Items[]`
   list of `{fieldPath, recordId, sourceIndex, goldenIndex, mergeFunction, condition, customValue,
   sourceValue, manualModified, modifiedByDataSteward}`). **The factory's planned `FieldProvenance`
   table is therefore redundant and should be dropped**, in favor of reading `Xref` → `RecordMetadata`
   (`functionalId = masterKey`) → `MergeValueLineageCompact` (`mergingProcessId`) directly. This exactly
   matches the field the product-owner-approved mockup (`docs/mockups/mdm-factory.html`) already draws.
7. **TARGET_EXISTS on `Companies` specifically (§6)**: two genuinely separate discoveries. (a) The
   target model's own matching config is real, live, and already `ENABLED` — leave it untouched, as
   the task's own framing asks. (b) **A second, wholly independent, already-active ingestion pipeline
   was found on this instance**: EBX's own "Consolidation" mechanism (dataspace `consolidation`,
   tables `TableMapping`/`FieldMapping`/`MatchingCriteria`/`CrossRefFieldMapping`/`Source`/`Lineage`) is
   *already* feeding `/root/Companies` from two other datasets (`CompaniesFromERP`/`CompaniesFromCRM`),
   with 9 configured `TableMapping` rows, 745 populated `CrossRefCompanies` rows against 632 `Companies`
   records, and its own field-level `Lineage` table (before/after value, per field, per source). This is
   a **major open question for the product owner**, not resolved here: is the factory's new
   `Companies_Staging` pipeline meant to feed `Companies` *through* this existing Consolidation
   mechanism (reusing its `Xref`/lineage machinery wholesale, in the spirit of "the factory does not
   reimplement what EBX already does"), replace it, or run alongside it as a third, independent feed
   into the same table? See §6 and §8.
8. **Lot plan**: §7, seven lots, ordered so each leaves something reviewable/demoable on its own and
   names exactly what needs a live, supervised call before anything is generated for real.

---

## 1. Where matching happens, and on what table

### 1.1 The real `Companies` matching configuration (READ (live))

`get_matching_activation_status(table="/root/Companies")` →
```json
{"metadatasetExists": true, "paused": false, "published": true, "state": "ENABLED"}
```
`get_matching_config(table="/root/Companies")` returned a full, real, active configuration. Quoted in
full because it is the concrete shape question 4 should imitate (§4):

- **Decision tree** (three nested branches, evaluated top to bottom):
  1. `"Same LEI"` — `/Identification/LegalEntityIdentifier` exact, `minimumScore 100`, `NOT_MATCH` on
     any null → **`Match`**.
  2. `"Same registration number and country"` — `/LegalInformation/registrationNumber` exact **and**
     `/Address/Country` exact, both `minimumScore 100`, `NOT_MATCH` on null → **`Match`**.
  3. `"No LEI conflict"` (both LEIs null or equal, `MATCH` on both-null) →
     `"Similar name and address"` — an **Average** node (`minimumScore 90`) over
     `/Identification/Name` (`fuzzyFullText`, weight 3, `NOT_MATCH` on null),
     `/Address/Country` (`exact`, weight 1, `DO_NOTHING` on null),
     `/Address/City` (`fuzzyFullText`, weight 1), `/Address/Zipcode` (`exact`, weight 1) →
     **`Suspect`**, never `Match`.
- **Pre-clustering fields** (`matchingPolicies[].fields`, the candidate-gate/clustering layer, distinct
  from the decision tree's own per-node fields): `/Identification/Name` (weight 3),
  `/Identification/LegalEntityIdentifier`, `/LegalInformation/registrationNumber`, `/Address/Country`,
  `/Address/City`, `/Address/Zipcode` (weight 1 each).
- **Merge policy** `"Companies"`: `mode: "ONLY_DUPLICATES"` (auto-create a new golden only for a group
  that actually has duplicates, never for an untouched singleton — one of four modes,
  `DISABLED`/`DUPLICATES_AND_SINGLETONS`/`ONLY_DUPLICATES`/`ANY_DUPLICATES`, per
  `matching_config_schema`), `recordSelectionFunctionChain: [mostCompleted]`,
  `defaultFieldMergeFunctionChain: [longest]`, **`survivors: []`** (no per-field override configured
  today — every field falls back to the policy default, `longest`).
- **`relatedTables`** (all `alignForeignKeys: "MANUAL"`): `Cross references on companies`
  (`/root/TechnicalData/CrossRefCompanies`), `Roles`, **`Companies` itself** (the self-referencing
  `Parent`/`ultimateParent` FKs), `Bank accounts`, `Company addresses`, `Contacts`,
  `Company identifiers`, `Company relationships` — a live, concrete confirmation of the CLAUDE.md §8
  note that `generate_merge_policy` adds every inverse-FK table on its own, `MANUAL` by default.
- **`tableSettings`**: `modifyMergedRecords: false`, `afterModifyMergedRecords: "BOTH"`,
  `pauseMatching: false`, `hideMergedRecords: false`, `viewMode: "HORIZONTAL"`,
  `defaultMatchingPolicy: "Companies"`. **`modifyMergedRecords: false` is load-bearing for §3.**
- **`trustedSources`**: `sourceField: null` (no field-level trust source column configured),
  `tableTrustedSourceList: []` (no table-wide ranking set), but the **`sources` catalogue itself is
  already populated**: `Data Steward decision` (built-in), `ERP_AMER`, `ERP_APAC`, `ERP_EU`,
  `GROUP_ERP`, `MDM`, `POS_ENRICH` (all custom, `builtIn: false`) — a live example of the exact shape
  (`{code, description, builtIn}`) `set_trusted_sources` expects, and evidence that a source-code
  vocabulary can exist without an active ranking yet.

### 1.2 The technical (MAME metadataset) tables that make this concrete (READ (live), `describe_table`)

Dataset `Companies_Companies_MDS` (the "metadataset" `get_matching_activation_status.metadatasetExists`
refers to), all in dataspace `Companies`:

- **`/root/RecordMetadata`** — PK `functionalId` (FK → `/root/Companies`); `groupId` (decimal, "which
  group each record has been placed in"); `state` (int, static enumeration, "state of the record after
  the last match or merge operation"); `autoCreated` (boolean, "automatically created during a match or
  merge operation"); `isolated` (boolean); **`goldenId`** (FK → `/root/Companies`, nullable, "the target
  record into which the record has been merged during the latest merge process"); `hasSuspects`
  (computed boolean); `suspectedCount`; `matchingProcessId`; `mergingProcessId`. **One row per business
  record**: `count_records_in_table` on `RecordMetadata` = 632, on `Companies` itself = 632 too (READ
  (live)) — every record, golden or source, has exactly one metadata row.
- **`/root/Matched`** — `id`, `sourceId`, `targetId` (both plain strings — functional-id values, not
  FKs), no process id.
- **`/root/Suspected`** — same shape plus `matchingProcessId` (FK-typed int, mandatory).
- **`/root/MergeValueLineageCompact`** — see §5; this is the field-level provenance answer.
- Also present but not read in this session (connectivity lost first): `MatchingProcess`, `Decision`,
  `FalsePositiveDecision`, `MergingProcess`, `MatchingSelection`, `ReadyToMergeGroup`,
  `CascadingRecords`, `CleansingRecords` — flagged in §8 as needing a read before the lineage screen's
  "match group and score" element can be built (the score itself is presumably in `MatchingProcess` or
  `Decision`, neither read live this session).

**`RecordMetadata.functionalId`/`goldenId` both targeting `/root/Companies`, and `Companies` carrying
its own `Linked_Field_*` projections of that same metadata (READ (live), `describe_table` on
`/root/Companies`: `Linked_Field_GroupId`, `Linked_Field_State`, `Linked_Field_AutoCreated`,
`Linked_Field_GoldenId`, `Linked_Field_FunctionalId`, `Linked_Field_HasSuspects`, each a
`linkedField`/`valueFunction` through an `Association_Reverse` `tableRefInverse` association to
`RecordMetadata`) is the direct, live proof for the recommendation above: matching is configured,
activated, and rendered entirely in terms of ONE business table.** `activate_matching` is what adds
`Association_Reverse` and the six `Linked_Field_*` nodes to the model — a GREENFIELD master-model
generator does not need to build any of this by hand; it only needs to generate the plain business
table and then call `activate_matching` on it.

### 1.3 Why "match in staging, promote only goldens" does not fit the add-on

No MAME tool or config surface reads candidates from one table and writes golden records into a
different one. `get_matching_config`/`generate_matching_policy`/`generate_merge_policy` are all scoped
to exactly one `table` (optionally disambiguated by `dataModel`); a `businessObject` scope
(`get_business_object_config`) aggregates **member tables of the same business object**, not
"candidate table" + "output table" pairs. Tried live: `get_business_object_config(businessObject="/root/Companies")`
returned a `members` array containing exactly one entry — the same `MameConfig` `get_matching_config`
already returned for `/root/Companies` itself — with the top-level `businessObject` fields
(`id: null, published: false, mainTable: "/root/Companies"`) mostly unset. **READ (live), not fully
resolved**: this reads as "no real multi-table business object is configured for `Companies` today,
and the call degrades to echoing the main table's own config as its sole member" rather than a genuine
error; not independently confirmed beyond what the JSON shows. Either way, it does not offer a
staging-table-as-member, master-table-as-output shape. **Recommendation stands**: matching runs inside
the master table; staging's job ends at handing the master table clean, typed, normalized candidate
rows (via the transfer in §3).

---

## 2. The master model in GREENFIELD

### 2.1 What the master table is built from, and what changes vs. staging

`StagingModel.java` already builds exactly the tree-plus-patch-ops shape a master-model generator
needs, so this is a **reuse, not a rewrite**, with two deliberate differences:

- **Business attributes**: the accepted `StagingAttribute` rows of the canonical entity, same as
  staging (`staging/StagingModel.java`, `AttributeInfo`, READ by the research agent, `:92-120`).
  **Includes a `FOLD_KEY` derived attribute exactly like any other** (2026-09-22, `docs/NORMALISATION.md`
  §3.3 bis: a technical matching key next to a display attribute, e.g. `cityKey` next to `city`) —
  `MasterModel.AttributeInfo` deliberately carries no `derivedFrom`/`derivation` field of its own, so the
  master builder has no special case to get wrong: the field is promoted (same name, `STRING`,
  `maxLength` copied, never mandatory), `ebx.MasterTransferPlan` plans it as an ordinary `FieldPlan`, and
  `ebx.MasterLoader` copies the already-folded staging value across like any other field — verified by
  reading `MasterModel`/`MasterTransferPlan`/`MasterLoader`/`MasterGenerator`, none of which enumerates
  attributes "that have an accepted mapping" (a derived attribute never has one) anywhere in the build
  path; the one place that DOES read `AttributeMapping` (`MasterGenerator.readLandingTableNames`,
  membership-overlap detection only) simply sees an empty contribution from a derived attribute, which is
  harmless there.
- **A business attribute named like one of the table's own fields is promoted as `<name>_src`** (§13,
  2026-09-23, `master.MasterFieldNameRule`): `id`, `sourceSystem`, `sourceId`, `_loadId`, `_receivedAt` are
  compared case-insensitively against every accepted business attribute; the Salesforce `Id` attribute of a
  sample-built entity becomes the master field `Id_src`, and `MasterModel.FieldInfo` carries both names so the
  transfer plan and loader follow.
- **Primary key: plain auto-increment `id`, not `sourceSystem`+`sourceId`.** Staging's PK is fixed
  `sourceSystem` before `sourceId` (`StagingModel.sortPrimaryKeyFirst`, `:357-390`, READ) because every
  staging row **is** a source row. A master table cannot use that key: a golden record the add-on
  creates (`RecordMetadata.autoCreated = true`) has no single source system, and `Companies` itself
  proves the add-on's own expectation — `Identification/Id`, `type: "int"`, `autoIncrement: true`,
  is the table's sole primary key (READ (live), `describe_table`). **`sourceSystem`/`sourceId` still
  matter, but they move to `Xref`** (already carrying them, unconditionally, `min=1` on both —
  `tools/build_xsd.py:427-428`), not to the master table's own key.
- **Technical bookkeeping fields (`_loadId`, `_receivedAt`, `_status`, `_rejectReason`) do not belong
  on a master table.** They exist to track a landing/staging row's own pipeline state; a master record
  is the add-on's object, governed by `RecordMetadata` instead (§1.2). Carrying them into master would
  either duplicate `RecordMetadata`'s own bookkeeping or, worse, collide with fields the add-on's own
  activation patches onto the table (`Linked_Field_*`, §1.2) — do not generate them.
- **What the add-on itself adds — do not build by hand**: `activate_matching` (a write tool, not called
  this session, but its effect is directly observable live on `Companies`) patches the target model to
  add an inverse `Association_Reverse` to `RecordMetadata` plus six `Linked_Field_*` projections
  (§1.2). A GREENFIELD generator's job stops at a plain business table (fields + a real primary key);
  activation does the rest.

### 2.2 Keying: how the add-on keys a newly created golden

**Not a UUID, not a separate id space — the same auto-increment sequence as every other record of the
table.** `RecordMetadata.goldenId` and `.functionalId` are both plain foreign keys into `Companies`
(READ (live), §1.2); nothing in the schema or the config distinguishes a golden's key range from a
source record's. `mergePolicies[].mode = "ONLY_DUPLICATES"` (§1.1) means: for a group with only one
record (no duplicate found), nothing new is created and that single record stays as-is; for a group
with duplicates, a new record is auto-created (`autoCreated = true`) to hold the merged result **only
if** `recordSelectionFunctionChain`/`mode` calls for a new row rather than reusing one of the existing
group members as the survivor — **this distinction (does `ONLY_DUPLICATES` always create a brand-new
row, or can an existing group member become the golden with `autoCreated = false`?) was not resolved
live this session** (would need a real match run to observe, forbidden here) — flagged in §8.

### 2.3 Child entities: `Contact` → `Company`, through the member redirection

**The concrete problem, from the real load (`PLAN.md`, lot 3)**: staging's `Contact.accountIdRef` is a
foreign key to `Account` (format `system|id`), because that is the relation `LandingRelation` actually
found (`CONTACT.ACCOUNT_ID → ACCOUNT`, confidence 80, accepted). But `Account` is a rule-proposed
*member* entity of the canonical `Company` (`StagingEntity.memberEntities`, `staging/StagingModel.java`
`:130-131`, `:228-241`, READ by the research agent) — it is **not** promoted to master on its own
(`StagingEntity.targetTable` stays empty for a member; only the canonical entity's row is meant to
reach master, per the very note `StagingModel` itself already leaves for a human: `"... and its member
... are both accepted: both tables are generated; decide which one feeds the master."`). So a naive
master-side FK from `Contact` to `Account`'s master table would point nowhere, because `Account` has no
master table.

**The resolution, and why it is cheap**: the real `Company` staging table's own rows are keyed by their
*originating member's own* `sourceSystem`/`sourceId` — confirmed on the real load: 107 `Company` rows
= 55 fed by `Supplier` (ERP) + 52 fed by `Account` (CRM), *each row keeping the member's own source
key* (`PLAN.md`, lot 3: "`Company` (entité canonique du LLM ...) ... chaque ligne gardant son
`sourceSystem` + `sourceId`"), and `TransferPlan`'s own class Javadoc confirms this is deliberate design,
not an accident: *"a canonical ... entity is fed by one Feed per member ... each landing row of each
member becomes its own staging row in the same staging table, keyed by that row's own
sourceSystem/sourceId (unification across sources happens later, in Match and Merge)"*
(`transfer/TransferPlan.java:19-26`, READ by the research agent). Because a CRM `Account` row and its
corresponding `Company` staging row share the **identical** `(sourceSystem, sourceId)` pair, a master
FK from `Contact` to `Company` needs no new lookup — it is the same `(sourceSystem, sourceId)` value
`Contact.accountIdRef` already carries, only resolved against `Company`'s table instead of `Account`'s.

**Recommendation (design, not yet code)**: at master-transfer time, for every `StagingAttribute` whose
`referenceEntity` names a *member* entity (i.e. appears in some other entity's `memberEntities`),
redirect the foreign key's *target table* to that member's canonical entity, while keeping the exact
same key value already computed. This is a rule added to the transfer-planning step (the sibling of
`TransferPlan.loadOrder`/`ForeignKeyPlan`, READ by the research agent — `transfer/TransferPlan.java`
already has the `ForeignKeyPlan` shape to extend, not invent), not a new schema field.

### 2.4 Member entities once the canonical exists: not promoted, staging-only

Confirmed by the schema itself: `StagingEntity.targetTable` (`tools/build_xsd.py:282`, "Path of the
table of the master model this entity feeds") is the field a generator reads to decide whether an
entity reaches master at all. **Recommendation**: leave `targetTable` empty on `Supplier`/`Account`
(and any other rule-proposed member absorbed into a canonical entity); set it only on the canonical
entity (`Company`). A member's staging table keeps existing — it is still the input the canonical
entity's transfer reads from (§2.3's redirection needs it) and still the natural place to inspect "what
did the ERP side originally say" without going through master at all — but nothing writes it onward.

---

## 3. Staging → master transfer

### 3.1 Insert / update semantics by `(sourceSystem, sourceId)`

**No existing code reads or writes this transfer yet** — the research agent's exhaustive grep of
`src/main/java` for `master`/`golden` (case-sensitive, both cases) found only schema-level placeholders
and Javadoc notes marking this as "a later lot" (`ebx/StagingLoader.java:741`,
`ebx/StagingSuggester.java:36,70`); nothing computes, inserts, or updates a master record today.
**Recommendation, mirroring `StagingLoader`'s own proven shape** (`ebx/StagingLoader.java:539-570`,
READ by the research agent, §"upsert by primary key" above) but adapted for the key difference in §2.2:

1. For each accepted, staged row of an entity with `targetTable` set, look up its `Xref` row by
   `(project, stagingTable, stagingKey)` — the exact idempotency key `StagingLoader.writeXref` already
   uses (`ebx/StagingLoader.java:759-771`, READ by the research agent).
2. **`Xref.masterKey` empty** → this staging row has never reached master. Create a new record in the
   master table (a plain `doCreateOccurrence`, letting the table's own `autoIncrement` PK assign the
   id — no `computePrimaryKey` call needed here, unlike staging, since there is no composite key to
   compute). Write the new record's own key back into `Xref.masterTable`/`masterKey`.
3. **`Xref.masterKey` set** → read that record's `RecordMetadata` (`functionalId = masterKey`) to
   learn its `state`/`goldenId`. If it is **not** merged (no `goldenId`, or a `state` meaning "still a
   standalone/source record" — the exact `state` enumeration values were not read live this session,
   flagged in §8), a plain `doModifyContent` update is safe, exactly like staging's own upsert. **If it
   already carries a `goldenId` (it was merged into something else)**: the real `Companies` config has
   `tableSettings.modifyMergedRecords: false` (§1.1) — **on this instance, today, updating that record
   directly is expected to be refused by the add-on.** This is the one behavior this document could not
   verify with a real write (forbidden in a read-only session, and the outage would have blocked it
   anyway): **recommend a live, supervised test** — reload the same `(sourceSystem, sourceId)` after it
   has been merged once, and record whether EBX throws, what message, and whether `state` reports
   `MERGED` in a human-readable way (`RecordMetadata.state`'s own enumeration was not read this
   session). Until then, the transfer engine should catch that specific failure mode explicitly (not
   let it look like a generic write error) and either skip the row with a clear message, or, if the
   product owner enables `modifyMergedRecords`, decide between `KEEP_RECORDS_IN_GROUP` and
   `CHANGE_TO_UNSET` (the two non-`BOTH` values of `afterModifyMergedRecords`) as a project-level
   setting.

### 3.2 Deletes at source

No mechanism anywhere in this codebase or in the add-on (per `docs/LANDING-FLOWS.md` §1.6, gap #7)
deletes a record because its source row disappeared — landing itself has no "delete missing" path for
a root inbound mapping. **Recommendation**: do not delete master records either; instead, a future lot
could flag a `Xref` row whose staging row is no longer refreshed by the latest `loadId` as "stale" (the
same idea `LANDING-FLOWS.md` already proposes for landing, one level up) and let a data steward decide
— deleting a merged golden's contributing source is exactly the kind of decision `PLAN.md`'s own
principle ("le LLM propose, l'humain accepte") reserves for a person, not a batch job.

### 3.3 Rejects

**`TransferReject.stage` already has a `MASTER` enum value, unused today** (`tools/build_xsd.py:443-445`,
READ: `('STAGING', 'Landing to staging', ...), ('MASTER', 'Staging to master', ...)`, with the Javadoc-
style `desc_en` explicitly naming "staging to master" as "a later lot"). **Recommendation**: reuse the
table as-is — no schema change needed, write `TransferReject` rows with `stage = "MASTER"` for a row an
FK-redirection (§2.3) cannot resolve (an orphaned `Account`/`Company` pair, the same "unknown parent"
shape `StagingLoader.planRow` already produces for staging, `:390-422`, READ by the research agent) or
for a row refused because its master record is merged (§3.1) and no policy for that case is configured
yet.

### 3.4 `Xref` evolution

**No new field needed for "the source record" vs. "its current golden."** `Xref.masterTable`/`masterKey`
already exist, already optional (`tools/build_xsd.py:420-421`, confirmed `min=1` absent, with the
class's own comment explaining exactly why: *"masterTable/masterKey are NOT mandatory ... StagingLoader
writes one Xref row per staged row, at STAGING_LOAD time, when no master record exists yet - only the
master-load lot ... fills these in."*), and they should be filled by the master-load step of §3.1 as the
record's **own** key (the row it actually is, which may itself be a merged-away source record). The
"current golden" is not a fact `Xref` should cache: `RecordMetadata.goldenId`, read live through
`functionalId = Xref.masterKey` at render time, already answers it, and stays correct across every later
merge/unmerge without the lineage screen (or anything else) needing to keep a second copy in sync. This
is the same "don't duplicate state the platform already tracks" principle `docs/SOURCE-RELATIONS.md`
§3.2(c) applied to `LineageEdge.origin`.

---

## 4. Generating the matching configuration

### 4.1 The real profile of `Company` (READ (live))

`profile_table_for_matching(table="/root/Company", dataspace="Companies_Staging", dataset="Staging")`
— run successfully (after an earlier retry window during a temporary EBX/MCP outage, disclosed above)
— returned, in full, `rowCount: 107` (matches `PLAN.md`'s own load report exactly) and, per field
(`provenance` omitted below where it is simply `EXACT` throughout — every statistic on this small,
fully-loaded table is exact, never sampled):

| field | fillRate | cardinality | most frequent value (count) | most frequent token (doc freq) |
|---|---|---|---|---|
| `/sourceSystem` | 1.0 | 2 | `ERP` (55) | — (`NOT_SUPPORTED`) |
| `/sourceId` | 1.0 | 107 (all unique) | `0011ZIkEiAlf3FyFjt` (1) | — |
| `/supplierId` | 1.0 | 107 (all unique) | `0011ZIkEiAlf3FyFjt` (1) | `v109030` (1) |
| `/name` | 1.0 | **103** | `BPCE` (2) | `bank` (23) |
| `/legalForm` | 0.944 | 14 | **`Customer` (24)** | `customer` (24) |
| `/vatNo` | **0.439** | 46 | `FR76662042449` (2) | `fr76662042449` (2) |
| `/city` | 0.991 | 51 | `PARIS` (18) | `paris` (33) |
| `/country` | 1.0 | **28** | `FR` (27) | `fr` (32) |
| `/_loadId` | 1.0 | 2 | `L20260920-133440-1` (55) | — |
| `/_receivedAt` | 1.0 | 2 | `Sun Sep 20 15:34:40 CEST 2026` (55) | — |
| `/_status` | 1.0 | 1 | `STAGED` (107) | `staged` (107) |
| `/_rejectReason` | 0.0 | 0 | — | — |

**This is the complete field list — 12 fields, matching `PLAN.md`'s own note on `Company`'s regenerated
shape ("12 field(s)") exactly.** Three real findings follow directly from these numbers, none guessed:

1. **`Company` today carries neither `LegalEntityIdentifier` nor `registrationNumber`** — the two fields
   the real, live `Companies` decision tree (§1.1) uses for its two highest-precision, `Match`-terminal
   tiers. Only `vatNo` (44% filled), `name`, `city`, `country`, `legalForm` exist. **Concretely**: of the
   three already-known duplicate pairs (§ below), only `BNP PARIBAS`/`B.N.P. PARIBAS` shares a value in a
   field `Company` actually has (`vatNo`); `SOCIETE GENERALE`/`STE GENERALE` and `HSBC HOLDINGS`/`HSBC
   HLDGS` share `REGISTRATION_NO`/`LEI`, neither present on `Company` — those two pairs would only be
   caught by the fuzzy name/address tier, not by an exact-match tier the way the real `Companies` model
   catches its own equivalents today. **Recommendation**: either accept a weaker matching policy for
   `Company` than `Companies`'s own, or send `registrationNumber`/`LEI` back through the staging review
   (they exist on the `Supplier` member entity already) before generating the policy.
2. **`/legalForm`'s most frequent value is `"Customer"` (24/107 = 22%), not a legal form at all** — a
   real, concrete data-quality defect on the canonical entity, not a hypothesis: the CRM `Account` side
   appears to have fed something like an account-type/rating field into the `legalForm` attribute
   (`ACCOUNT_TYPE`/`RATING`-shaped, per `docs/NORMALISATION.md`'s own mention of `ACCOUNT.RATING` /
   `ACCOUNT_TYPE` as closed-list columns with no reference table), not the ERP-side `LEGAL_FORM`
   abbreviation (`SA`/`AG`/`PLC`/... per `docs/NORMALISATION.md` §1.7). **`legalForm` should not be used
   as a matching field until this mapping is fixed** — it would actively create false matches (every
   `Customer`-valued row scoring as "same legal form" against every other).
3. **`/country` has 28 distinct raw values over 107 rows** (vs. `Companies`' own already-normalized
   `Address/Country`, always ISO alpha-2) — direct, live confirmation, on the canonical entity itself,
   of the same defect `docs/NORMALISATION.md` measured on raw `ACCOUNT.BILLING_COUNTRY` (16 spellings
   over 52 rows): `Company.country` inherits the mess unchanged. `/city`'s own most-frequent-value
   (`PARIS`, 18) vs. most-frequent-token (`paris`, 33 — a case-insensitive count) shows the same
   cross-source case inconsistency `docs/NORMALISATION.md` §1.2 already found between ERP (all-caps) and
   CRM (natural case). **Both `country` and `city` must go through `docs/NORMALISATION.md`'s
   `MappingStep` normalisation before being usable as reliable matching fields** — using them raw would
   reproduce the exact under-matching risk that study already measured.

**Substitute/corroborating evidence** (READ, not on `Company` itself): the sibling
`docs/NORMALISATION.md` study ran live queries against the real `Companies_Staging` **landing** data
(the raw source rows `Company` is built from) and found, consistent with the profile above:
- `SUPPLIER.VAT_NO`: 26/55 non-blank, all shape `FR` + 11 digits; `SUPPLIER.LEI`: 33/55 non-blank, ISO
  17442 shape, 1 lower-case defect; `SUPPLIER.REGISTRATION_NO`: present on all 55, but mixes at least
  six different national schemes in one column (§1.6 of that study) — **a raw exact-match on this
  column, unnormalised, would under-match badly**.
- `ACCOUNT.BILLING_COUNTRY`: 16 distinct spellings over 52 rows, 0/52 already ISO alpha-2 — by direct
  contrast `SUPPLIER.COUNTRY` is already ISO alpha-2 on all 55 rows. **A `Company` row fed by `Account`
  and a `Company` row fed by `Supplier` will have differently-shaped country values until the
  normalisation lot (a `MappingStep`, `docs/NORMALISATION.md` §3) runs** — the real `Companies` decision
  tree's own `Address/Country` field (used both as an exact-match tier-2 predicate and as a fuzzy-tier
  weight) would misfire on unnormalised `Company.country` values today.
- **Three real duplicate-looking supplier pairs already found** (`docs/NORMALISATION.md` §1.6): `BNP
  PARIBAS`/`B.N.P. PARIBAS` (identical `VAT_NO`/`REGISTRATION_NO`/`BIC`), `SOCIETE GENERALE`/`STE
  GENERALE` (identical `REGISTRATION_NO`/`IBAN`/`BIC`), `HSBC HOLDINGS`/`HSBC HLDGS` (identical
  `REGISTRATION_NO`/`LEI`/`IBAN`/`BIC`) — **these three pairs are the concrete, already-known test case
  for the tier-1 exact-match branches of the real `Companies` decision tree** (`Same LEI` /
  `Same registration number and country`, §1.1): two of the three pairs already share every relevant
  identifier field byte-for-byte (no normalisation needed to catch them); the `BNP PARIBAS` pair needs
  `VAT_NO` populated on the blank side, or falls through to the fuzzy `Similar name and address` tier at
  threshold 90.

**Recommendation**: normalisation (`docs/NORMALISATION.md`'s `MappingStep` lot) is a genuine
**dependency**, not a nice-to-have, for matching quality on `Company` — run it before generating the
policy, not after, and use `docs/NORMALISATION.md`'s own findings (country, legal form, identifier
normalisation) plus the three concrete defects measured directly on `Company` above (missing LEI/
registration number, mismapped `legalForm`, unnormalized `country`/`city`) as the requirement source for
`MatchPlan.requirements`. A first, realistic `requirements` sentence for `Company`, given what it
actually has today: *"Same normalised VAT number; or, if VAT is missing or differs, similar normalised
legal name and matching normalised country/city."* — deliberately dropping `legalForm` entirely and
never claiming LEI/registration-number precision the entity does not yet carry.

### 4.2 The ordered MCP call chain, each classified

| # | Call | Classification | Notes |
|---|---|---|---|
| 1 | `profile_table_for_matching(table, dataspace, dataset)` | **Read-only** (its own description: "Analyze table data ... Returns each terminal field with its type, default search strategy, and: rowCount, fillRate, cardinality, mostFrequentValue, mostFrequentToken"). Nothing persisted. | **Run live, in full, on `Company` — §4.1.** |
| 2 | `matching_config_schema(table)` | **Read-only** (returns the schema + `fieldCapabilities` for the table; no write). READ (live) already run, in full, §1's decision tree shape and `fieldCapabilities` are from this call on `/root/Companies`. | Run again on the actual `Company` master/staging table once it exists, to see its own `fieldCapabilities`. |
| 3 | `generate_matching_policy(table, requirements, ...)` | **Write / forbidden this session.** Its own field doc says plainly: *"`generate_matching_policy` writes one policy, so it can only link to a merge policy the table already stores"* (`matching_config_schema` → `mergePolicyCode` description) — "writes" is explicit persistence, not a proposal-only return. Correctly not called. | `requirements` = the plain-language sentence from `MatchPlan.requirements`; `docs/NORMALISATION.md`'s findings (§4.1) should shape it, e.g. "same LEI, or same normalised registration number and country, or similar normalised legal name and normalised address." |
| 4 | `generate_merge_policy(table, relatedTables, ...)` | **Write / forbidden this session**, same reasoning as #3 (the tool name itself is `generate_*`, and CLAUDE.md §8's own prior finding — "adds every inverse-FK table as a related table on its own" — describes a *persisting* side effect, consistent with `apply` semantics). | Per CLAUDE.md §8: pass **no** `relatedTables` and fix `alignForeignKeys` afterwards — a table listed explicitly ends up duplicated. |
| 5 | `validate_matching_config(table)` | **Read-only** ("structural/behavioral validation with codes, paths, hints" — a report, no write verb in its description). | Run after 3–4, before 6. |
| 6 | `patch_matching_config(table, ops=[set_merge_policy_link, set_execution_mode, ...])` | **Write / forbidden this session** (delta-update tool, explicitly state-changing). | Wire `mergePolicyCode`, and (per §4.1's dependency) do **not** set thresholds below what §4.1's real duplicate pairs need to still match once normalised. |
| 7 | `set_trusted_sources(table, sources, tableTrustedSourceList)` | **Write / forbidden this session** (a `set_*` tool, by name and by the schema's own description "Configure the most-trusted-source functions"). | `sources[].code` = each accepted `ProjectSource.sourceSystem`; `tableTrustedSourceList` order = ascending `ProjectSource.trustRank` ("lower wins" per `ProjectSource.trustRank`'s own doc, `tools/build_xsd.py:178-179`) — matches MAME's own "index 0 = most trusted" convention exactly, no inversion needed. |
| 8 | `activate_matching(table)` | **Write / forbidden this session** ("prepare metadataset, enable" — explicitly a state change). | Per CLAUDE.md §8: needs no restart on a DMA publication; does on a module XSD until the model reloads. A GREENFIELD master model is generated via `build_data_model`/DMA the same way staging already is (`StagingGenerator`), so no restart should be needed — **not independently verified this session.** |
| 9 | (not an MCP tool) `POST` the matching-execution REST endpoint, `recordPks` scoped | **Write, out of MCP scope entirely** — `matching_guide(topic="threshold-tuning")` states this explicitly: *"Running a match is not one of the MAME MCP tools -- it is the matching-execution REST endpoint, called with a recordPks list."* An empty/omitted `recordPks` **discards every existing match/suspect verdict of the table, across every policy configured on it**, not just the one being run — a real, sharp-edged hazard on `Companies`, which already has a live, production-looking config. **The master-load lot must always scope this to the just-transferred batch's own record keys**, never run it bare against `Companies`. | This is `PipelineStep.step = "MATCHING_RUN"`, already a defined enum value with zero driving code (research agent's grep). |

### 4.3 `MatchPlan.autoMerge` and SUSPECT routing by default

**Recommendation, directly modeled on the real `Companies` policy (§1.1)**: default
`MatchPlan.autoMerge = false` (already the schema default, `tools/build_xsd.py:352-353`) for every
generated policy — every tier that is not a 100%-confidence exact match (LEI, or registration number +
country) should terminate in `Suspect`, exactly as the real, live `Companies` tree already does for its
fuzzy name/address tier. This is not a generic best practice invented for this document; it is the
literal shape already running in production on this instance, and it matches
`docs/mockups/mdm-factory.html`'s own approved lineage example (`"décision : fusion automatique"` shown
only for the high-confidence path, a `0,93` score labelled with its own field-by-field breakdown).

---

## 5. Record-level lineage

### 5.1 Field-by-field provenance: the add-on already has it

**`MergeValueLineageCompact` (READ (live), `describe_table`, dataspace `Companies`, dataset
`Companies_Companies_MDS`, `/root/MergeValueLineageCompact`)**:

```
id (int, PK, autoIncrement)
mergingProcessId (int, mandatory)
sourceField (string, optional)
Items[] (repeatable group):
    fieldPath, recordId, sourceIndex (int), goldenIndex (int),
    mergeFunction, condition, customValue, sourceValue (all string),
    manualModified (boolean), modifiedByDataSteward (boolean)
```

This is a direct, live, unambiguous answer to question 5's "does the add-on expose per-field provenance
of the surviving value": **yes.** For a given merge (`mergingProcessId`), and a given field
(`sourceField`/`Items[].fieldPath`), each `Items` entry names the contributing **record**
(`recordId`), whether it was the one whose value survived (`sourceIndex`/`goldenIndex` — matching when
that source became the golden's value for the field), which merge function decided it
(`mergeFunction`/`condition`), the actual value considered (`sourceValue`) vs. any manual override
(`customValue`), and whether a person changed it by hand afterward (`manualModified`,
`modifiedByDataSteward`). **This is exactly the "champ par champ — quelle source a gagné" table the
approved mockup already draws** (`docs/mockups/mdm-factory.html`, §5, quoted: `Name | Acme Industries
SAS | CRM · Account | Source la plus fiable pour le nom commercial | ERP : « ACME INDUSTRIES »`).

**Recommendation: drop the planned `FieldProvenance` table from `MdmFactory.xsd`** (listed in `PLAN.md`,
"Modèle de données de la factory") — it would duplicate what `MergeValueLineageCompact` already
computes and stores, correctly, per merge run. The lineage screen reads it directly instead of
recomputing anything.

### 5.2 The full join, end to end

For one master record `M`:
1. **Source system + raw row**: `Xref` rows where `masterKey = M` (there can be more than one — every
   source record that was ever merged into `M`, plus `M`'s own row if it is itself a source record) give
   `sourceSystem`, `sourceId`, `landingDataset`, `landingTable`, `landingKey`, `loadId`.
2. **Landing row** (`_loadId`, `_receivedAt`): read directly from `landingDataset`/`landingTable`/`landingKey`.
3. **Staging row and its cleaning steps**: `Xref.stagingTable`/`stagingKey`, joined to the staging row
   itself, plus (once `docs/NORMALISATION.md`'s lot lands) the `MappingStep` chain and
   `AttributeMapping.transformation` that produced each value.
4. **Match group and score**: `RecordMetadata.groupId` (via `functionalId = M`), then `Matched`/
   `Suspected` rows naming `M` as `sourceId` or `targetId`. **The actual numeric score and per-field
   breakdown were not confirmed live this session** — `MatchingProcess`/`Decision` were listed by
   `list_tables` but not `describe_table`d before the outage; this is the one structural gap in the join
   chain, flagged in §8.
5. **Golden, field by field**: `MergeValueLineageCompact` filtered by `M`'s `RecordMetadata.mergingProcessId`
   (§5.1).

### 5.3 A second, independent lineage mechanism, found on this instance

The pre-existing "Consolidation" module (§6.2) has its **own** `Lineage` table (dataspace
`consolidation`, `/root/Lineage`, READ (live) `describe_table`), tracking, per modification: `informations`
(`id`, `user`, `operationDateTime`), `target` (`record`, `dataSpace`, `dataSet`, `table`, `pk`, `field`),
`values` (`sourceValue`, `oldValue`, `newValue`), `source` (`source` FK, `record`, `dataSpace`, `dataSet`,
`table`, `pk`, `field`) — table description: *"Track all modifications done in the consolidated tables
configured."* This is a **pre-merge, field-level, before/after** lineage (who wrote what, from where,
overwriting what), complementary to MAME's own **post-merge, per-merge-run** `MergeValueLineageCompact`.
**Not yet reconciled with the factory's own design in this document** — see §6, §8.

---

## 6. TARGET_EXISTS specifics for `Companies`

### 6.1 What the factory would need — mapping, groups, and FKs to reference datasets

Confirmed live, `describe_table(/root/Companies)`: the model is deep and heavily grouped
(`Identification`, `AdditionalInformation`, `Address`, `BillingInformation`, `LegalInformation`,
`Contact`, `Lifecycle`, `Governance` — 8 top-level groups, 75 fields total), with real foreign keys to
reference datasets outside the model's own dataspace: `Address/Country` and `LegalInformation/jurisdiction`
→ `BGeographies/Geographies/root/Country`; `LegalInformation/capitalCurrency` and
`LegalInformation/revenueCurrency` → `BCurrencies/Currencies/root/Currency`;
`LegalInformation/legalForm` → `/root/ReferenceData/LegalForm` (in-model); `AdditionalInformation/naceCode`
→ `/root/ReferenceData/NaceCode` (in-model). This matches `docs/NORMALISATION.md` §5.2's own
recommendation exactly (reuse the target's *existing* FKs — resolve the landing value to the target's own
PK, e.g. `Country.Alpha2Code`, `Currency.AlphaCode`, `LegalForm.Code` — never re-embed a copy) — no new
design needed here, it is confirmed already correct.

**Every top-level group is itself flagged `mandatory`** (`"constraints":[{"type":"mandatory", ...,
"message":"Field 'Legal information' is mandatory."}]` on `LegalInformation`, `Address`,
`BillingInformation`, etc.) — **not independently verified whether this has any real effect on a
non-repeating complex group** (structurally, a fixed-shape EBX record always has every group node
present; "mandatory" on a group commonly has no functional consequence distinct from its leaf fields'
own constraints, but this was not tested live). Flagged in §8 as a low-risk item to confirm before
assuming it changes anything about how a transfer writes into `Companies`.

Two real `pattern` facets exist on the target model and must be respected by whatever writes into it:
`LegalInformation/isin` (`[A-Z]{2}[A-Z0-9]{9}[0-9]`) and `Contact/website`
(`https?://.+` — directly the `NORMALIZE_URL` gap `docs/NORMALISATION.md` §1.11 already flagged: 47/47
`ACCOUNT.WEBSITE` values are missing the scheme and would fail this pattern unmodified) and
`Contact/email` (`[^@\s]+@[^@\s]+\.[^@\s]+`).

### 6.2 A second, already-active pipeline into `Companies` — the risk

**READ (live), `list_tables`/`describe_table`/`preview_records` on dataspace `consolidation`, dataset
`consolidation`**: a distinct, already-configured, already-populated mechanism (not Match and Merge,
not this factory) is feeding `/root/Companies` today:

- **`TableMapping`** (9 rows, `count_records_in_table`): each row names a `source` (dataspace/dataset/
  table + a `Source` FK for priority/name) and a `target` (dataspace/dataset/table via a
  `TableConsolidated` FK), plus `rule.consolidateNull`, `activation.enableLineage`, and
  `crossReferences.{dataset, table}` — this **is** an Xref-generation mechanism, built into EBX,
  already wired.
- Two rows (`preview_records`, READ (live)) feed `/root/Companies` from `/root/Entity` of
  `BCompaniesFromCRM/CompaniesFromCRM` (source code `CRM`, priority 2) and
  `BCompaniesFromERP/CompaniesFromERP` (source code `ERP`, priority 3), both with
  `activation.enableLineage: true` and `crossReferences.table = /root/TechnicalData/CrossRefCompanies`.
  A third row does the same for `/root/Role` from the ERP side.
- **`CrossRefCompanies`** (745 rows against 632 `Companies` records, `count_records_in_table`, READ
  (live)) is this mechanism's own cross-reference table — `company` (FK), `source` (FK → `Source`),
  `dataspaceNameSource`/`datasetNameSource`/`tablePath`/`pkRecordSource`/`recordSource` — structurally
  almost identical to what the factory's own `Xref` already is for landing/staging.
- **`Lineage`** (§5.3) is this same mechanism's field-level before/after tracker.

**This is a genuinely important open question this document does not resolve** (see §8): the factory's
new `Companies_Staging`/`Staging`/`Company` pipeline (107 rows, from `Companies_Landing`'s Snowflake
`ERP_SOURCE`/`CRM_SOURCE`) is a **third**, currently disconnected route toward the same `Companies`
table that this Consolidation mechanism already feeds from two **other** datasets
(`CompaniesFromERP`/`CompaniesFromCRM`) it does not touch. Before building a bespoke
`StagingLoader`-into-master engine for TARGET_EXISTS (§3), the product owner should decide whether the
factory should instead **generate a `TableMapping` row** pointing at `Companies_Staging`/`Staging` and
let this already-proven, already-active EBX mechanism do the insert/update + cross-reference + lineage
work — directly in the spirit of `PLAN.md`'s own stated principle, *"La factory n'implémente pas ce
qu'EBX sait déjà faire."* This was not explored further here (no write call was made, and reconciling
two live source pipelines into the same table needs the product owner's explicit direction, not an
agent's guess) — flagged as the single highest-value open question in this whole document.

### 6.3 What is risky

- Writing into `Companies` at all, from a second source, without understanding whether the existing
  `TableMapping` rows' `rule.consolidateNull`/priority logic already accounts for (or conflicts with)
  a third feed.
- The existing matching/merge config (§1.1) must be left untouched, per the task's own framing — and,
  concretely, per `tableSettings.modifyMergedRecords: false` (§3.1), a naive transfer that tries to
  update an already-merged record risks an outright refusal, not silent corruption — a real but
  contained risk.
- `CrossRefCompanies`'s `dataspaceNameSource`/`datasetNameSource` fields are free-text strings, not the
  factory's `Xref` shape — reusing this table (§6.2) rather than the factory's own `Xref` would mean two
  different lineage tables exist for the same target model, one per pipeline, unless the product owner
  picks one.

---

## 7. Lot plan

Ordered so each lot is reviewable/demoable on its own, per this codebase's own standing principle
("rien n'est généré depuis une ligne qui n'est pas acceptée"). Sizes: **S** = a focused change in one or
two files; **M** = a new class/generator plus its wiring; **L** = a new subsystem.

1. **[S] Schema decision, no new tables needed.** `Project.masterDataspace`/`masterDataset`,
   `StagingEntity.targetTable`, `MatchPlan`, `Xref.masterTable`/`masterKey`, `TransferReject.stage =
   MASTER`, and `PipelineStep.step ∈ {MASTER_LOAD, MATCHING_CONFIG, MATCHING_RUN}` **all already exist**
   in `MdmFactory.xsd`, unused (§ research agent's grep). The only schema change this document
   recommends is **removing the planned `FieldProvenance` table** from `PLAN.md`'s "Modèle de données"
   list (§5.1) — nothing to build, one line to delete from the plan.
2. **[M] Master model generator (GREENFIELD)**, mirroring `StagingGenerator`'s proven shape
   (`get_data_model` → `build_data_model` dry-run → build → `apply_data_model_patch` → publish →
   `execute_operation(create_dataset)`), but: PK = auto-increment `id` (§2.1), no technical bookkeeping
   fields, and the member→canonical FK redirection rule (§2.3) added to the transfer-planning step, not
   the model generator itself. Unblocks: a real master table exists for a GREENFIELD project. **Verify
   live, before trusting**: does `activate_matching` need a restart on a freshly-generated DMA-published
   model, the way a module XSD would (CLAUDE.md §8) — likely not, but not tested.
3. **[S/M] Master transfer engine (`MASTER_LOAD`)**, `StagingLoader`'s shape adapted per §3.1: insert on
   empty `Xref.masterKey`, else read `RecordMetadata` and update-or-reject depending on merge state.
   **Depends on §6.2's open question being resolved first for TARGET_EXISTS** — GREENFIELD can proceed
   without it (a freshly generated master table has no competing pipeline). **Verify live, supervised,
   before trusting**: the exact behavior of updating an already-merged record with
   `modifyMergedRecords: false` (§3.1) — one real reload, watched.
4. **[S] Matching-config generation (`MATCHING_CONFIG`)**, the ordered call chain of §4.2, driven by
   `MatchPlan.requirements`/`autoMerge`, writing `matchingPolicy`/`mergePolicy` names back onto
   `MatchPlan`. Also: wire `ReviewData` to actually populate `nature = MATCH` review lines from
   `MatchPlan` — confirmed live-in-code that `ReviewPage.NATURES` already lists `ReviewLine.MATCH`
   (`review/ReviewPage.java:209`) but `ReviewData`/`ReviewRules` never read `MatchPlan` at all (grepped,
   zero hits) — the review screen's own vocabulary is ready, the data plumbing is not. **Before
   generating a real policy**: send `registrationNumber`/`LEI` back through staging review onto `Company`
   (§4.1, finding 1) and fix the `legalForm` mismapping (§4.1, finding 2) — both are staging-side fixes,
   not master-zone code, but both directly gate how good a policy `generate_matching_policy` can produce.
5. **[S] Matching run (`MATCHING_RUN`)**, the matching-execution REST endpoint (§4.2, item 9), always
   scoped to the just-transferred batch's `recordPks` — never an unscoped run against a table that
   already has a live config elsewhere (`Companies` itself is a hazard example: an unscoped run would
   discard its own, already-production, decision tree's existing verdicts).
6. **[M] Lineage screen**, the join chain of §5.2, reading `Xref` → landing/staging rows →
   `RecordMetadata` → `Matched`/`Suspected` → `MergeValueLineageCompact` — no new storage, per §5.1's
   recommendation to drop `FieldProvenance`. The mockup (`docs/mockups/mdm-factory.html` §5) is already
   approved and matches this join's real output shape closely; build against it. **Read `MatchingProcess`/
   `Decision` (§5.2 step 4) before this lot starts** — the score/breakdown display needs their real
   shape, not read live this session.
7. **[S] Cockpit master-zone section**, replacing the "always empty" placeholder
   (`ebx/ui/CockpitPage.java:213-215`, confirmed live-in-code: *"master: always reported empty in this
   lot"*) with real counts once lots 2–5 land.

---

## 8. Open questions

- **The second, already-active Consolidation pipeline into `Companies` (§6.2)** — the single most
  important open item in this document. Does the factory's own master-load reuse it, replace it, or run
  alongside it? Needs the product owner's decision, not an agent's guess; not explored further here on
  purpose (no write call was made toward it).
- **Whether `Company`'s missing `LegalEntityIdentifier`/`registrationNumber` (§4.1) should be added by
  sending the `Supplier` member's own accepted attributes back through the LLM/rule staging pass, or by
  hand** — not decided here; both are staging-review-screen actions, not master-zone code, but the
  choice affects how soon a real matching policy can reach the same precision as the existing `Companies`
  model's.
- **What `RecordMetadata.state`'s real enumeration values are**, and specifically whether a "merged
  away" source record is distinguishable, in a script-readable way, from every other state — the field
  was read as `"type":"int","enumeration":"static"` but its legal values were not enumerated live this
  session (would need `get_constraints`/a deeper schema read on the MAME metadataset, not attempted
  after the outage).
- **Whether `mergePolicies[].mode = "ONLY_DUPLICATES"` always auto-creates a brand-new golden record, or
  can promote an existing group member to golden with `autoCreated = false`** (§2.2) — not resolved
  live; would need a real match run to observe (a write, out of scope for this session regardless of the
  outage).
- **Exact refusal behavior of updating an already-merged record** given `tableSettings.modifyMergedRecords:
  false` on `Companies` (§3.1) — reasoned from the config field's own name and CLAUDE.md's general
  "blocking vs. non-blocking constraint" framing, not observed with a real write.
- **`MatchingProcess`/`Decision`/`FalsePositiveDecision`/`MergingProcess`/`MatchingSelection`/
  `ReadyToMergeGroup` table shapes** — listed by `list_tables` on the MAME metadataset, never
  `describe_table`d (connectivity lost before reaching them). The lineage screen's "match group and
  score" element (§5.2, step 4) needs at least `MatchingProcess`/`Decision` read first.
- **Whether a `mandatory` flag on a non-repeating complex group (`Companies`'s own top-level groups,
  §6.1) has any real functional effect** — reasoned as "probably none" by general EBX structure, not
  tested live.
- **`get_business_object_config`'s exact meaning when no real business object is configured** (§1.3) —
  the call succeeded with a single, self-referential "member" rather than a clean `BUSINESS_OBJECT_NOT_FOUND`;
  not fully understood from the read alone.
- **The `CrossRefCompanies`/`Lineage` (Consolidation module) vs. the factory's own `Xref` — whether these
  should ever be the same table** (§6.3) — a direct consequence of the open question above, not decided
  here.

---

## 9. Incident, 2026-09-21: the first real `master/generate` + `master/load` promoted the wrong entities

**READ (live), coordinator's own measurement, 2026-09-21 00:05, `data-access`**: `PipelineStep` 34
(`MASTER_MODEL`), 35 (`MASTER_DATASET`), 36 (`MASTER_LOAD`) all `DONE`, the last one reporting "Transferred
303 of 303". `Companies_Master/Master` held **four** tables — `Supplier` (27 fields), `Account` (22),
`Contact` (17), `Company` (11) — instead of the two this project's acceptance decisions call for (`Company`,
`Contact`; `Supplier`/`Account` are members of the accepted canonical entity `Company` and must stay staging-
only, §2.4). `Company` held 107 rows (CRM ids 1-52, ERP 53-107) **and** the same 107 records existed again as
`Supplier` (55) + `Account` (52); `Xref` carried `masterTable = /root/Supplier` and `/root/Account` rows.

**Root cause (READ, live + code)**: `StagingEntity` id 4, `Company` (origin `LLM`, status `APPLIED`), had
`memberEntities` empty on all 5 of its rows, although its `rationale` still read `"Unifies Supplier, Account:
..."`. `StagingEntity.memberEntities` (a structured foreign-key list) was added 2026-09-20, *after* `Company`
had already been proposed and accepted; the only code that ever backfilled an old-style rationale into the
structured field was `StagingLlmSuggester.suggest` (`ebx/StagingLlmSuggester.java`, the paid, admin-only
language-model pass) — and nobody had reason to call it again on a project whose staging was already
generated and loaded. `master.MasterModel` trusts `memberEntities` alone, by design (§2.3: "prose gets
truncated, translated and edited; logic must not depend on it") — with it empty, `MasterModel.build` had no
way to know `Supplier`/`Account` were members of `Company`, and promoted all three. `staging.StagingLoader`
was unaffected (it works from `AttributeMapping` rows directly, never from `memberEntities`), which is why
`Company`'s own 107 rows were correct even while the master zone was wrong.

### The fix

1. **The backfill is no longer tied to one paid, opt-in entry point.** `ebx.MemberEntitiesBackfill` (new)
   extracts the exact logic `StagingLlmSuggester` already had (`StagingLlmPass.parseMembers` on the rationale,
   persisted only when every parsed name resolves to a real `RULE`-origin entity of the project — the same
   safety as before, unchanged) into a small, self-contained, idempotent pass with no MCP call and no language
   model, cheap enough to run on every call that reads `StagingEntity` for a membership-sensitive decision:
   `StagingSuggester.suggest` (reports a new `memberEntitiesBackfilled` counter, alongside its existing
   `technicalKeysCreated`/`mappingsAligned` catch-ups), `StagingLlmSuggester.suggest` (still the only place a
   *new* canonical entity is proposed), `StagingGenerator.generate`, `MasterGenerator.generate`, and
   `MasterLoader.load` (which independently recomputes `MasterModel.Result` to plan the transfer, so it needs
   current membership just as much as generation does).
2. **Structural evidence beats text — `master.MembershipConsistencyCheck` (new, pure, tested).** Even after the
   backfill runs, a row it could not fix (a hand-typed canonical entity, or one naming something that no
   longer exists) can still look, structurally, like an unresolved unification: an `LLM`-origin accepted
   entity whose own attribute mappings draw from a landing table some *other* accepted entity is also fed
   from, while `memberEntities` is empty. `ebx.MasterGenerator` now runs this check right after the backfill,
   before building anything, and **refuses** with a message naming the entity and every overlapping one,
   rather than silently promoting it. Running the backfill first is exactly what makes the refusal disappear
   on this project — `MembershipConsistencyCheckTest` asserts both directions.
3. **`POST /projects/{code}/master/reset?confirm=<masterDataspace>` (new, administrators only)** — the actual
   recovery path, since dropping a table is NON-ADDITIVE and `master.MasterModelState`'s regeneration will
   rightly refuse to remove `Supplier`/`Account` on its own. `master.MasterResetEligibility` (pure, tested)
   gates it: `GREENFIELD` only; `Project.masterDataspace`/`masterDataset` must be *exactly* the names
   `master.MasterNames` would itself generate (never a foreign value — the demo project's own hand-typed
   `TestMasterDataspace`/`TestMasterDataset` is never a candidate); real `PipelineStep` evidence that
   `MASTER_MODEL` ran and `MASTER_DATASET`'s own detail recorded `dataspace.created == true` (not merely a
   name that happens to match); the add-on's own matching metadataset — one per promoted master TABLE, named
   `<dataset>_<table>_MDS` in the master dataspace, per `master.MameMetadataset` (fixed 2026-09-22 from an
   earlier, wrong `<dataspace>_<dataset>_MDS` guess; see `docs/RECORD-LINEAGE.md` "The metadataset naming
   convention") — must not exist for ANY promoted table (it doesn't, on this project — matching was never
   activated); and `confirm` must equal the master dataspace key exactly. `ebx.MasterReset` then:
   - closes and deletes the dataspace — `Repository.closeHome(AdaptationHome, Session)` then
     `Repository.deleteHome(AdaptationHome, Session)`, both real public abstract methods **confirmed by
     `javap` on `ebx-lib.jar`, 2026-09-21** (no Javadoc text available that way, only the signatures), called
     directly with the session the same way `ebx.MasterGenerator.runDatasetStep` already calls
     `Repository.createHome` outside any `Procedure`. **Unverified beyond the signatures**: whether
     `deleteHome` needs the home already closed (defensively closed first here, only if
     `AdaptationHome.isOpen()`) and whether it deletes immediately rather than requiring a separate
     `purgeHomes` — inferred from the Data Services REST route's own documented behaviour (CLAUDE.md §6:
     `POST .../{dataspace}:close` with `deleteHistoryOnClose=true&deleteDataOnClose=true` deletes a dataspace
     in one call, `204`), never independently confirmed by actually running it (forbidden this session).
   - deletes the DMA model — **READ (live), `mcp__data-model__list_operations(name="delete_data_model")`**:
     *"Delete the whole data model. Requires confirm=true."*, argument `confirm`, shape `"\"true\" to proceed;
     \"false\" or omitted reports the impact instead"*; its own contract text, quoted in full: *"Deleting the
     model does NOT delete any of [the datasets built on it] - they keep working, it only orphans them - but
     the source they can be evolved and republished from is gone."* — so the dataspace deletion above and this
     model deletion are genuinely two separate cleanups, both needed. Called via `execute_operation` on the
     `data-model` MCP server, soft-failed (recorded in the response, never aborts the reset) since by the time
     it runs the dataspace is already gone.
   - clears `Xref.masterTable`/`masterKey`/`goldenKey` for the project (landing/staging provenance fields
     untouched — a reset undoes the master transfer only, not the staging one), clears
     `Project.masterDataspace`/`masterDataset`, and moves every `DONE` `MASTER_MODEL`/`MASTER_DATASET`/
     `MASTER_LOAD` `PipelineStep` to a new status, `RESET` (added to the XSD enumeration, never deleted —
     history stays, but `PipelineSteps.isDone` must once again treat them as not done, so a fresh
     `master/generate` behaves exactly like a brand-new project).
4. **The `Company`/`Contact` shape was already covered by existing tests**, not newly added:
   `MasterModelTest.realDemo_companyAndContactPromoted_supplierAndAccountKeptInStaging` (`Company` unifies
   `Supplier`+`Account`, `Contact.accountIdRef` redirected from `Account` to `Company`) and
   `MasterTransferPlanTest.foreignKeyFieldPlanCarriesRedirectedTarget` (the redirected `FieldPlan` shape).
   `ebx.MasterLoader.planRow`'s Xref-based resolution of that redirected FK (the child's own staging `Ref`
   value looked up against `Xref(stagingTable = the redirected canonical entity, stagingKey = that same
   value)`) already existed in the codebase before this incident — the incident was entirely in *which
   entities get promoted*, not in how a promoted entity's foreign key is resolved once it is.

**Sequence to run after deployment** (administrators; `Companies` is the project this incident happened on):

```
POST /projects/Companies/staging/suggest         # backfills memberEntities as a side effect; confirm memberEntitiesBackfilled: 1
POST /projects/Companies/master/reset?confirm=Companies_Master
POST /projects/Companies/master/generate          # should now promote only Company, Contact
POST /projects/Companies/master/load
```

The reset step name (`Companies_Master`, the dataspace key `master.MasterNames.dataspaceKey` computes for this
project with the default `_Master` suffix) must be read off the project first if the suffix setting was ever
changed — `confirm` is checked for byte-for-byte equality, deliberately.

## 10. Incident, 2026-09-22: `master/generate` aborted mid-patch on `defaultLabel`

**MEASURED LIVE, 2026-09-22 12:09**: the very first real `POST /projects/Companies/master/generate` on a fresh
path (`state.found = NOTHING`) built `Companies_Master` (2 tables, 30 fields, compiled) and then called
`apply_data_model_patch` with 28 operations, which **failed at op index 14** —
`{"op":"set_element_property","arguments":{"element_path":"/root/Contact","property":"defaultLabel",...}}` →
`"Invalid property 'defaultLabel'. Allowed values are ... [no defaultLabel]"` — with `stoppedOnError: true`,
so ops 15–27 (the rest of the `Company` table's own facets, `autoIncrementStart`/`Step` included) were **never
applied**, the model was left unpublished, and `Project.masterDataspace` stayed empty. The op had been added
that same morning (`fix/lineage-record-title-and-menu`, see the 2026-09-22 `PLAN.md` entry) on the mistaken
assumption that a table's `osd:table/defaultLabel` was just another `set_element_property` facet like
`maxLength`/`mandatory` — it never validated against a real `apply_data_model_patch` call because no test
checked an emitted op's `property` against the tool's own allowed list, only against each op's own shape.
`set_element_property`'s real vocabulary is FIELD-level only; a table's default label has its own dedicated,
separately-discovered operation, `set_default_label` (`list_operations(name="set_default_label")`, read live
the same minute: category `element`, behavior `ADDITIVE_RETRYABLE`, args `element_path` + `label`), reached
through `execute_operation` — never `apply_data_model_patch`. The fix (branch
`fix/master-default-label-op`): `master.MasterModel` no longer emits any `defaultLabel` op at all — it exposes
the decision as plain data instead (`Result#defaultLabelByTable`, table path → label template); `ebx.
MasterGenerator` applies it itself, one `execute_operation(set_default_label)` call per genuinely NEW table
(same "not already existing" gate as the `id` field's autoIncrement facets), AFTER a successful patch/build
and BEFORE `publish_data_model` — and best-effort: a failure to set a label is purely cosmetic (EBX falls back
to the formatted primary key, and `lineage.RecordTitleRule`'s own read-time fallback already covers every
table regardless) and must never fail the generation itself. Because `set_default_label` is
`ADDITIVE_RETRYABLE`, the very next `master/generate` on this half-built model (`state.found = MODEL_ONLY`,
both tables already present and matching the promoted entities) re-runs the full (now-corrected) patch and the
label calls with no manual cleanup needed. A new pure test, `core.PatchOpVocabularyTest`, hard-codes both of
`apply_data_model_patch`'s vocabularies (op names, and `set_element_property`'s allowed properties) as measured
live this same day and asserts every operation `MasterModel`/`StagingModel`/`StagingModelDiff` emit stays
inside them — a deliberate tripwire for the next time a new facet is added without checking it live first.
**Never run live from this fix's own task** (no EBX access): the exact `execute_operation(set_default_label)`
call shape is asserted only against the tool's own read-only contract text, not against a real invocation.

## 11. Incident, 2026-09-22 12:09-12:39: the fixed `master/generate` still could not repair `Company`, and `master/load` reported success on a 100% rejection

**MEASURED LIVE, coordinator's own sequence, 12:09-12:39** (branch `fix/master-regeneration-completes-facets`,
worktree `ebx-mdm-factory-regen-facets`): with the &sect;10 fix deployed, a fresh `master/reset` +
`master/generate` at 12:09 still failed at op 14 the same way (the fix had not been deployed to the live
instance for that specific run - `Contact` fully patched, `Company` left with no auto-increment facet, model
unpublished). The very next `master/generate`, at 12:38, now on the fixed build, reported
`state: {found: MODEL_AND_PUBLICATION, regenerate: true}` and took the REGENERATE path - even though the 12:09
run had ended `FAILED`, never `DONE`. `master/load` at 12:39 then rejected all 196 of 196 rows with "primary
key has unset element(s): [osd:is-null(./id)]", yet the step and the route both reported `DONE`.

**Root cause, part 1 (why `found` was wrong): a DMA publication object survives `delete_data_model` and has no
delete operation of its own** - confirmed by reading the `data-model` server's own operation catalogue live
this session: `list_data_model_publications`/`set_publication_documentation`/`create_dataset`/versions/
export/`delete_data_model` is the complete list. The 2026-09-21 incident's own `master/reset` (&sect;9) had
already deleted the DMA model, but the publication row it was published under stayed on the server and
re-attached itself to the model `master/generate` rebuilt from `NOTHING` at 12:09 under the identical computed
name. `get_publication` at 12:38 therefore answered `found: true` with a real `version`, and
`ebx.MasterGenerator.runModelStep` read that alone as `MODEL_AND_PUBLICATION` - wrongly, since the 12:09 run
that actually built the CURRENT model content ended `FAILED`, not `DONE`.

**Root cause, part 2 (why REGENERATE did not fix `Company` even once routed there): the diff-based
regeneration path only ever applies the `id` field's auto-increment facets and the table's own `defaultLabel`
to a table it considers "genuinely new this run"** (`existingNames.contains(entityName) -> continue`,
&sect;10 above). `Company` already existed (half-built, from 12:09) at the top of the model, so this gate
skipped it - and `StagingModelDiff`'s own generic per-field diff engine has no concept of "auto-increment" or
"table-level defaultLabel" at all (it only ever compares `maxLength`/`mandatory`), so neither difference showed
up as an additive change either. `Company.id` stayed published with no `autoIncrementStart` facet, which is
exactly what `master/load` then hit.

**Root cause, part 3 (why the load's own failure was invisible): `ebx.MasterLoader.load` always ended the
`MASTER_LOAD` step `DONE` and always answered `"status":"DONE"`**, regardless of how many rows were actually
written - a load that transferred 0 of 196 rows was reported exactly like a load that transferred all of them.

**The fix, all three parts pure and unit-tested, `tools/test_local.sh` all green:**

1. `ebx.MasterGenerator.runModelStep` now additionally requires the factory's OWN bookkeeping
   (`PipelineSteps.isDone(..., "MASTER_MODEL", "")`) before trusting a publication the server reports -
   `ebx.MasterReset` moves every earlier `DONE` `MASTER_MODEL` step to `RESET`, so a `DONE` step can only mean
   the factory itself finished publishing since the project's last reset. Without it, the model is treated as
   `MODEL_ONLY` even though a publication was found, which routes to the FULL patch/publish path instead of the
   diff-based one - documented on `ebx.MasterReset`'s own Javadoc (it cannot delete the publication object;
   there is no operation for it).
2. New pure class `master.MasterFacetRepair.decide(existingTables, plannedModel)`: for every PROMOTED table
   already present on the live model (regardless of whether the diff considers it "new" this run), checks
   whether its `id` field's `autoIncrementStart` facet or its own `defaultLabel` is missing and, if so, appends
   the repair - reusing `MasterModel.Result#autoIncrementOps`/`#defaultLabelByTable`, the exact same values a
   fresh CREATE would use, so a repaired table ends up byte-for-byte the same as a table that was never
   half-built in the first place. `staging.StagingModelDiff.parseExisting` now also reads
   `constraints.autoIncrementStart` per field and `tableProperties.defaultLabel` per table (the second key
   name is this task's own literal specification; the first is inferred by analogy with the already-measured
   `constraints.mandatory`/`constraints.maxLength` keys read at the same call site, and fails safe if wrong -
   see that method's own Javadoc). `staging.StagingGenerator` has no equivalent gate to fix: staging's primary
   key is `sourceSystem`+`sourceId` (never auto-increment) and `StagingModel` has no `defaultLabelByTable`
   concept at all.
3. New pure class `master.MasterLoadOutcome.isTotalFailure(read, rejected)`: true only when at least one row
   was read and every single one was rejected - deliberately not `written == 0` alone, which would also fire on
   a run where every row was legitimately `SKIP_MERGED` (already merged elsewhere), a genuine success.
   `ebx.MasterLoader.load` now ends the `MASTER_LOAD` step `FAILED` (same message, same
   `firstRejectReasons`) and answers `"status":"FAILED"` in that case; a partial rejection still ends `DONE`
   with counts, unchanged. The Cockpit already renders a distinct chip for a `FAILED` `PipelineStep` - no UI
   change needed.

**Never run live from this fix's own task either** (no EBX access, same constraint as &sect;10): none of the
three fixes above were exercised against a real `master/generate`/`master/load` call. What the product owner should
verify once this reaches the live instance: (1) a `master/generate` on the exact half-built model this
incident left behind (`Company` with no auto-increment, no `defaultLabel`; `Contact` complete) repairs
`Company` in place without needing a `master/reset` first; (2) the repaired `Company.id` field actually
behaves as auto-increment on the next real insert; (3) a `master/load` re-run on the same 196 rows now
transfers them successfully; (4) the `constraints.autoIncrementStart` key name assumption in
`StagingModelDiff.parseExisting` is correct - if it is not, the practical consequence is simply a redundant
(harmless) `set_element_property` call every regeneration, never a missed repair or a corrupted facet.

## 12. Toolbars of the master tables (2026-09-22, the product owner's request)

Every promoted table of a project's master model now gets a table toolbar and a record toolbar giving direct
access to "Record lineage", the project's Cockpit, and Proposal review — today a steward only reaches Record
lineage through the record's generic Actions menu. Branch `feature/master-toolbars`, worktree
`ebx-mdm-factory-toolbars`, created on `main`. **Written, never deployed, never seen running** — this task ran
under read-only MCP tools only (`data-access`, `data-model` reads such as `list_operations`); no
`execute_operation`, no `apply_data_model_patch`, no publish, no write against the live instance.

### 12.1 Where a DMA model's toolbars live (READ (live))

Each DMA model is a dataset of the technical dataspace `ebx-dma` (branch key `ebx-dma`; dataset name = the
model name, e.g. `Companies_Master`), and its toolbars are rows of `/root/toolbarNode` of that dataset.
`ebx-dma` itself is filtered from `data-access` discovery, but every publication leaves a visible child
dataspace `DMA_<timestamp>` (parent `ebx-dma`) holding a copy of every model dataset — this is how the shape
below was read without ever touching `ebx-dma` directly.

**`describe_table(dataspace="DMA_2026-09-20T22:00:55.756", dataset="Companies", table="/root/toolbarNode")`** —
`id` (int, auto-increment, PK), `parent` (foreignKey to the same table, formatted as the parent's `id` as a
plain string — `{"key":"1"}` in a `preview_records` read), `type` (string enum: `TOOLBAR`, `ACTION_BUTTON`,
`MENU_BUTTON`, `SEPARATOR`, `GROUP_MENU_ITEM`, `ACTION_MENU_ITEM`, `SUB_MENU_ITEM`), `ordering` (int, null on a
`TOOLBAR` root), and one group per type — `toolbar{name, documentation{localizedDocumentations[]{locale,
label, description}}, location[]}`, `actionButton{service{serviceKey, target, modalSize, modalWidth,
modalHeight, resizable, serviceParameters[]{name,value}}, documentation, display{layout, icon{type,
reference}, highlightedButton}}`, `menuButton{documentation, display}`, `separator{width}`,
`menuGroup{documentation, type, serviceGroup{...}, menuBuilder{menuBuilderKey, excludedServiceGroups[],
excludedServices[], filteringPolicy}}`.

**One field type trap, found by precedent, not by a fresh incident**: `documentation/localizedDocumentations/
locale`'s Java-mapped type is `java.util.Locale`, not `String` — writing a plain `"en-US"` string throws
`IllegalArgumentException` deep inside the generated setter. Found the hard way in `ebx-perspective-admin`'s
`PerspectiveService.setLabel` (a different technical dataspace, `ebx-manager`, same `documentation`
group shape) and re-applied here on the strength of that precedent, never independently re-broken and
re-fixed against `ebx-dma` itself. `DmaToolbarWriter.locale(String)` is the one conversion point
(`Locale.forLanguageTag(tag)`).

**Real rows read (`preview_records` on the same dataset)**: root `TOOLBAR` at `TABLE_VIEW_TOP` ("Companies")
and one at `RECORD_VIEW_TOP` ("CompanyRecordToolbar"), each starting with an `@creation` `ACTION_BUTTON`
(`target=c`, `modalSize=l`, `resizable=false`, `ICON_ONLY`, icon `css`/`ebx_Add`), a `SEPARATOR` (`width=20`),
and a `MENU_BUTTON` "Actions" (`TEXT_ONLY`) whose one child is a `GROUP_MENU_ITEM` with
`menuGroup/type=MENU_BUILDER`, `menuBuilderKey=@actions_menu_with_separator`,
`excludedServices=[@inherit, @overwrite, @gridEditor, @workflowLauncher]`, `filteringPolicy=SMART_FILTERING`.
Every custom button after that is separator-prefixed. Locale keys in the dataset are `en-US`/`fr-FR` (the
shipped XML export of the same table shows `en_US`/`fr_FR` — use what `preview_records` shows, not the XML
export's own underscore spelling, for any future `data-access` read). This shape is reproduced verbatim by
`master.MasterToolbars.tableTop`/`recordTop` for the default part of the two new toolbars.

A **`TABLE_VIEW_ROW`** example was not present in the `Companies` model's own dataset (only `TABLE_VIEW_TOP`
and `RECORD_VIEW_TOP` roots exist there) — found instead in this instance's own shipped export
`.../ebx-ps-fasttrack-web/.../CompaniesForMohcine_toolbars.xml` (a static file on disk, read directly, no
MCP call, no write): a "Record row" toolbar with a single `ICON_ONLY` `ACTION_BUTTON` (`target=c`), no
default-part reproduction at all — a row toolbar decorates an already-visible record, it has no "create"
button of its own even in the real example. `master.MasterToolbars.tableRow` mirrors this exactly: one
Record lineage button, nothing else.

A recursive grep of every `*_toolbars.xml` export in the whole `ebx-ps-fasttrack` workspace found **no
example anywhere of `target=w` or `target=s`, and no example of `serviceParameters` at all** — every real
button on this instance targets `c` (current context). The web-component target value used here
(`"s"`) is therefore **not self-verified against a real example**; it is the coordinator's own
`javap -p -c` read of `com.orchestranetworks.ui.toolbar.internal.CtbBeanServiceTarget`
(`CURRENT_CONTEXT="c"`, `WEB_COMPONENT="s"`) — a correction issued mid-task after this lot's own first draft
had guessed `"w"`. Quoted here exactly as measured; never independently re-verified by this lot, and never
seen actually opening a Cockpit or Proposal review pop-up from a toolbar button.

### 12.2 `set_toolbars` — the operation contract (two shapes, by server build)

Category `customforms`, behavior `ADDITIVE_RETRYABLE`. **The contract changed between two builds of the
`data-model` server; the Factory sends the NEW one first and falls back to the OLD one once.**

**NEW contract — the `data-model` server read live 2026-10-12 (6.3.0-dev/rc build of that day;
`list_operations(name="set_toolbars")`).** Arguments: `element_path` (absolute `/root` path of a table, or of an
association field) OR `type_name` (a complex type defining table properties) — exactly one; `locations`
(required) one or more of `TABLE_VIEW_TOP, TABLE_VIEW_ROW, TILE_VIEW, RECORD_VIEW_TOP, HIERARCHICAL_VIEW_TOP,
ASSOCIATION_TOP, ASSOCIATION_ROW`, comma-separated, case-insensitive; `toolbar` (required) the name of an
existing toolbar (blank detaches at those locations). Each location holds ONE toolbar: attaching replaces what
was there and leaves the other locations alone, so the Factory makes **three calls per table**, one per toolbar
(the toolbar must declare the location it is bound to; `create_toolbar` already gives each root exactly one):

```
{"element_path":"/root/<Table>","locations":"TABLE_VIEW_TOP","toolbar":"MdmFactoryTableTop"}
{"element_path":"/root/<Table>","locations":"TABLE_VIEW_ROW","toolbar":"MdmFactoryTableRow"}
{"element_path":"/root/<Table>","locations":"RECORD_VIEW_TOP","toolbar":"MdmFactoryRecordTop"}
```

(each sent as `arguments_json` of `execute_operation(operation="set_toolbars")`).

**OLD contract — the build of 2026-09-22** (`element_path` + `tabular_view_top` / `tabular_view_row` /
`tile_view` / `record_top` / `hierarchy_view_top`, optional, blank clears, read-modify-write, all three in one
call). The NEW server refuses it with the text measured live 2026-10-12 10:16 on project Vol100kN, on every
table of `POST /projects/{code}/master/generate`:

> `Operation 'set_toolbars' does not take 'tabular_view_top', 'tabular_view_row', 'record_top'. It takes element_path, type_name, locations, toolbar; call list_operations with name='set_toolbars' for the full contract.`

Until 2026-10-12 the generator treated that refusal as cosmetic and went on, so the master zone had no Factory
toolbar (no Record lineage button). Since `fix/set-toolbars-contract`: `ebx.MasterGenerator.applyToolbars` sends the
NEW shape; if a call is refused with that "does not take" wording (`MasterToolbars.isArgumentShapeRefusal`, which
only recognises a wrong-argument-names refusal, never an unknown toolbar), it sends the OLD single call for that
table and for the rest of the run (`detail.toolbars.bindings[].contract` = `locations` or `legacy`). Any other
refusal stays per call, cosmetic, in the generation notes.

**Read-back instead of silent success.** After binding each table, `describe_table` is read
(`tableProperties.toolbars`: `tableViewTop`, `tableViewRow`, `recordViewTop`…, parsed tolerantly by
`MasterToolbars.boundToolbars`) and the generation notes carry, per table, e.g.
`Toolbars bound on /root/Person: TABLE_VIEW_TOP=MdmFactoryTableTop, TABLE_VIEW_ROW=MdmFactoryTableRow, RECORD_VIEW_TOP=MdmFactoryRecordTop.`
— or `… - NOT as expected: TABLE_VIEW_ROW (nothing bound).` / `none read back`. A failure stays cosmetic
(generation continues) and is reported as before. The `list_toolbars` listing is still attached to the step
detail. The toolbars render after `publish_data_model`, which the generator already does. **The describe_table
answer shape was not read live by this lot** (no instance access): if the parser finds no `toolbars` node, the
note says "none read back" — then read `describe_table` by hand and adjust `boundToolbars`.

> ⚠ A WRONG NAME FAILS SILENTLY (both contracts). If the named toolbar does not exist, or exists but is not
> available for the location you bound it to, EBX displays NO toolbar at that location and reports nothing — no
> validation finding, no compile error. This is why the read-back above exists.

`master.MasterToolbars.bindings()` / `setToolbarsArguments(tablePath, binding)` are the one place the three
toolbar names and locations are turned into calls (`legacySetToolbarsArguments` is the old shape);
`ebx.MasterGenerator.applyToolbars` never rebuilds this JSON inline, and `master.MasterToolbarsTest` guards the
argument names against the contract above (same spirit as `core.PatchOpVocabularyTest`, &sect;10).
`create_toolbar`/`list_toolbars`/`delete_toolbar` are not their own MCP tools — they run through
`execute_operation`/`execute_query`, same as `set_toolbars` itself.

### 12.3 Where the three toolbars are attached, and to what

Three toolbars per project (not per table — the same three serve every promoted table of that project's
master model; a DMA toolbar name only needs to be unique within its own model, and one project = one master
model), built by `master.MasterToolbars.build(moduleName, projectCode)`:

- **`MdmFactoryTableTop`** (`TABLE_VIEW_TOP`): default part, then separator-prefixed Record lineage, Cockpit,
  Proposal review, in that order (the product owner's own "above all Record lineage").
- **`MdmFactoryTableRow`** (`TABLE_VIEW_ROW`): one icon-only Record lineage button, nothing else.
- **`MdmFactoryRecordTop`** (`RECORD_VIEW_TOP`): default part, then separator-prefixed Record lineage,
  Cockpit — **never Proposal review** (reviewing staging proposals from an already-promoted golden record is
  out of scope; deliberately left off, `master.MasterToolbarsTest` asserts it is absent).

Record lineage is `<moduleName>@RecordLineageOnRecord` (`FactoryPaths.MODULE_NAME`, never a literal —
`RecordLineageOnRecordServiceDeclaration` is registered at MODULE level, `ServiceKey.forModuleServiceName`,
CLAUDE.md &sect;9/&sect;12), `target=c`.

**Cockpit and Proposal review need the FULL cross-model service key, not the bare name — corrected by the
coordinator, 2026-09-22, before merge.** This lot's own first draft wrote `serviceKey=FactoryCockpit`/
`ProposalReview` bare, reasoning (CLAUDE.md &sect;12) that a service declared by a model's own schema
extension is written bare — true, but only from WITHIN that same model. `FactoryCockpit`/`ProposalReview` are
declared by the FACTORY's schema (`MdmFactory.xsd`), while this toolbar lives on the MASTER model — a
different, DMA-generated schema entirely. Seen from there, a bare `FactoryCockpit` resolves against the
master model's own schema, which declares nothing under that name: the button would have silently not
appeared, or not launched. The correct cross-model key is exactly what `MdmFactory.perspective.json` itself
already writes for these same two services — `<moduleName>@<xsdPath>@<serviceName>`, e.g.
`ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@FactoryCockpit` — built by
`master.MasterToolbars.qualifiedServiceKey`, `<xsdPath>` reused from `core.FactoryPaths.MODEL_PATH` rather
than duplicated as a literal. The `serviceParameters`' own `service` value stays the BARE name
(`service=FactoryCockpit`/`service=ProposalReview`) — that parameter is read by the target user service
itself (`FactoryCockpitUserService`/`ProposalReviewUserService`), never resolved by EBX's own service lookup,
so it keeps the same bare spelling the perspective JSON and the service declarations themselves use.
`master.MasterToolbarsTest` now asserts the full qualified key on both buttons.

`serviceParameters` otherwise mirror `MdmFactory.perspective.json`'s own "Cockpit"/"Proposal review" entries
(`service`/`branch=MdmFactory`/`instance=MdmFactory`/`xpath`) — except `xpath` is scoped to THIS project's own
record, `/root/Project[./code='<projectCode>']` (XPath-quoted by doubling, the same convention as
`ebx.CatalogReader#quote`, re-derived inside `MasterToolbars` so it stays pure), rather than the bare
`/root/Project` the packaged perspective entry opens (which shows the project selector instead).

**One placement call this lot could not fully verify live**: whether an `OnRecord` service
(`RecordLineageOnRecord`) may be placed as a direct `ACTION_BUTTON` at `TABLE_VIEW_TOP` (as opposed to only
ever being reachable through the automatic "Actions" menu-builder there, or through the row/record-form
toolbars, which are unambiguously fine). `RecordLineageOnRecordServiceDeclaration`'s own Javadoc already
documents "the table form's own Actions menu, and the table view toolbar when exactly one record is
selected" as where EBX offers a `setAvailableAsToolbarAction(true)` `OnRecord` service — read as license to
place it directly in `MdmFactoryTableTop` too, but no real `*_toolbars.xml` export in this instance actually
does this (every real `OnRecord`-shaped example found is either the row toolbar's own per-record button, or a
service tucked inside the Actions menu-builder, never a standalone `TABLE_VIEW_TOP` button). If this
assumption is wrong, the practical consequence is small: the button simply does not render there (or renders
disabled with no row selected) — cosmetic, not a broken write. **Verify by opening a promoted table's own
table view once this is deployed.**

### 12.4 How the write happens

`ebx.DmaToolbarWriter.write(session, modelName, roots)` — the internal Java API, modelled directly on the
`ebx-perspective-admin` module's own `PerspectiveService` (`~/Dev/ebx-perspective-admin`, its own precedent
for writing a repeatable, non-table complex group as `java.util.List<Object>` via
`SchemaNode.createNewOccurrence()`/`executeWrite`, CLAUDE.md &sect;12): looks up `ebx-dma` by
`Repository.getDefault().lookupHome(HomeKey.forBranchName("ebx-dma"))`, the model's own dataset by
`AdaptationName.forName(modelName)`, then writes each row inside
`ProgrammaticService.createForSession(session, dmaHome).execute(new Procedure(){...})` — `id` is never set
(auto-increment), read back off the created `Adaptation` so a child's own `parent` field can be written as
that id formatted as a plain string (the same convention `ebx-perspective-admin`'s self-referencing
`menuItem.parent` field already uses successfully).

**Idempotent by toolbar NAME, not by content** — each of the three roots is looked up first by
`./type='TOOLBAR' and ./toolbar/name=<quoted>` (mirrors `master/generate`'s own general resumability); a root
already found existing is left completely alone, its children never re-read or reconciled. `set_toolbars`
binding, unlike the row write, is always re-applied to every promoted table on every `master/generate` call
regardless of whether anything else changed — it is documented `ADDITIVE_RETRYABLE`, and its own "wrong name
fails silently" trap (&sect;12.2) means there is no cheap way to tell "already correctly bound" from "silently
never bound" without re-reading `list_toolbars` and reasoning about it, which this lot does not attempt: it
only records the `list_toolbars` response in `detail` for a human to check.

**`ebx.MasterGenerator.applyToolbars`** is called right after `applyDefaultLabels`, before publishing — in the
fresh-build/`MODEL_ONLY`-resume CREATE path (`runModelStep`) this is unconditional, since that path always
publishes right after anyway. In `runRegeneration` it runs in BOTH the `UP_TO_DATE` and `REGENERATE` branches
(an explicit, deliberate choice to satisfy "always re-run `set_toolbars`" literally).

**A toolbar is part of the model DEFINITION — a dataset only sees it through a publication, the same rule as
every other model change here — corrected by the coordinator, 2026-09-22, before merge.** This lot's own
first draft let the `UP_TO_DATE` branch skip republishing after binding the toolbars, on the unstated
assumption that `set_toolbars` might take effect without one (unlike `add_addon`'s own explicit "INERT until
the model is republished" contract text). That assumption is never actually justified anywhere in this
codebase's own established pattern — every other model-definition write here (a table/field diff, the MAME
add-on declaration, `MasterFacetRepair`) is already treated as needing a republish, and there is no reason
`set_toolbars` would be the one exception. Fixed: `applyToolbars` now returns, alongside its notes, whether a
republish is needed (`DmaToolbarWriter.Result#anyCreated` OR at least one `set_toolbars` binding call
answering OK — simple on purpose, an OK binding does not by itself prove the bound NAMES actually changed,
but re-publishing a handful of extra times on an otherwise-idle project costs nothing and is far safer than
silently leaving a toolbar unbound). A small shared `republish(client, modelName, detail, detailKey)` helper
factors out the `publish_data_model` call/check both branches need; the `REGENERATE` branch calls it
unconditionally as before, and the `UP_TO_DATE` branch now calls it too whenever the flag is set — recording
`detail.toolbarsPublished=true` and a note ("Toolbars written/bound: model republished.") — while the step's
own `mode` still reports `UP_TO_DATE` (nothing about the diff/repair/add-on decision changes, only whether a
republish happens alongside it). Still **unverified live** like the rest of &sect;12: never actually seen
confirming that a toolbar written on an `UP_TO_DATE` pass reaches a real dataset after this republish.

Best effort throughout, the same contract as `applyDefaultLabels`: a toolbar write or binding failure is
recorded in `detail`/`notes`, never thrown — a steward can always still reach all three screens the older
ways (the record's generic Actions menu for Record lineage, the packaged `MdmFactory` perspective for Cockpit
and Proposal review, or a deep link), so this feature failing must never fail `master/generate` itself.

### 12.5 Idempotence and `master/reset`

Re-running `master/generate` (`MODEL_ONLY` resume, or `REGENERATE`) never duplicates a toolbar — the
name-based lookup in &sect;12.4 — and always re-binds `set_toolbars` on every promoted table, cheap and
harmless either way. `master/reset` deletes the whole master model (and, per &sect;10's own finding, the DMA
publication object can survive that regardless) — nothing extra needed for the toolbar rows specifically:
they live inside `ebx-dma/<model>`, the same dataset the rest of the model's own definition lives in, so
whatever `master/reset`/the next `master/generate` already does to that dataset covers them too. Not tested
live (no `master/reset` was run by this task).

### 12.6 What was NOT verified live, summarised (as of the original lot, 2026-09-22 before deployment)

Nothing in &sect;12 beyond the READ calls explicitly marked `(READ (live))` above was run against the live
instance — this task was constrained to read-only MCP tools throughout. In particular, never seen: a toolbar
row actually created in `ebx-dma`; a `set_toolbars` call actually executed; the republish this now triggers on
an `UP_TO_DATE` pass actually making a toolbar reach a real dataset; a "Record lineage"/"Cockpit"/
"Proposal review" button actually rendered on a real table or record view, in either toolbar location; the
full cross-model `serviceKey` (&sect;12.3) actually resolving and launching Cockpit/Proposal review from the
master model's own toolbar; the `target=s` web-component button actually opening its pop-up; the
XPath-predicate `xpath` parameter actually preselecting a project inside
`FactoryCockpitUserService`/`ProposalReviewUserService`. **Superseded by &sect;12.7-&sect;12.9 below** — most
of this list was in fact deployed and checked the same day.

### 12.7 Deployed 2026-09-22, 15:19–15:22 — what the product owner actually saw

the product owner deployed this lot and ran `master/generate` on `Companies` (mode `UP_TO_DATE`, since the model
already existed). Measured directly, not inferred:

- **The three toolbar roots were created** in `ebx-dma/Companies_Master` — ids 303 (`MdmFactoryTableTop`), 314
  (`MdmFactoryTableRow`), 316 (`MdmFactoryRecordTop`) — and **22 rows in total were read back exactly as
  designed** in the publication snapshot `DMA_2026-09-22T15:19:14.772`: the default part (`@creation`,
  separator, Actions menu) verbatim, then the three factory buttons with keys
  `ebx-mdm-factory@RecordLineageOnRecord` and (the then-current, cross-model design)
  `ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@FactoryCockpit`/`@ProposalReview`, target `s` on the
  latter two, `serviceParameters` (`service`/`branch`/`instance`/`xpath=/root/Project[./code='Companies']`),
  locales `en-US`/`fr-FR`, icons — all present and correctly formed.
- **`/root/Company` and `/root/Contact` were bound** to all three locations (`describe_table` →
  `tableProperties.toolbars` showed the binding), and **the model was republished** ("Toolbars written/bound:
  model republished" — the `UP_TO_DATE` branch's own conditional republish, &sect;12.4, fired as designed).
- **The admin session genuinely has write rights on `ebx-dma`** — the single biggest unverified assumption in
  `ebx.DmaToolbarWriter`'s own Javadoc (&sect;12.4) is now confirmed true on this instance.
- **"Record lineage" rendered AND WORKED** — from the table-top toolbar, the row toolbar, and the record form
  alike. This is the one button whose placement at `TABLE_VIEW_TOP` &sect;12.3 flagged as "the one call this
  class had to make without a live example" — confirmed correct.
- **"Cockpit" and "Proposal review" did NOT work** — see &sect;12.8.

### 12.8 The Cockpit/Proposal review failure — diagnosis and the module-level fix (same day, follow-up lot,
branch `fix/master-toolbar-module-services`, worktree `ebx-mdm-factory-toolbar-services`)

Clicking either button answered, in `kernel.log` at table-render time: *"No service with key
'ebx-mdm-factory@/WEB-INF/ebx/schemas/MdmFactory.xsd@FactoryCockpit' could be found"* (verbatim; the same
message with `@ProposalReview` for the other button) — and the click opened the plain data view of
`MdmFactory`/`/root/Project[./code='Companies']` instead of either intended screen (EBX's own fallback when a
toolbar button's service key cannot be resolved: it degrades to opening the `xpath`/`branch`/`instance`
service parameters as a generic record view).

**Diagnosis (coordinator)**: a toolbar of model X can only name X's OWN schema services (bare, `ServiceKey.
forName`) or MODULE-level services (`module@Service`, `ServiceKey.forModuleServiceName`) — never a
schema-extension service of a DIFFERENT model, whatever key form is used. `FactoryCockpit`/`ProposalReview`
are declared on the FACTORY's own schema (`MdmFactory.xsd`), while this toolbar lives on the MASTER model — a
different, DMA-generated schema entirely. The `<module>@<xsdPath>@<service>` form `MdmFactory.perspective.
json` itself uses for its own "Cockpit"/"Proposal review" menu entries is a PERSPECTIVE menu-entry key, valid
in that one context — it is not a real `ServiceKey` a toolbar button (or anything else) can resolve.

**The fix**: two new MODULE-level `OnTableView` services,
`ebx.ui.FactoryCockpitOnMasterServiceDeclaration`/`ProposalReviewOnMasterServiceDeclaration`, that resolve
their own project from the master table they were launched on and render the exact same Cockpit/Proposal
review screens — the identical pattern `RecordLineageOnRecordServiceDeclaration` already proved working in
&sect;12.7. `master.MasterToolbars` now writes both buttons exactly like the "Record lineage" one: bare
`<moduleName>@FactoryCockpitOnMaster`/`@ProposalReviewOnMaster`, `target=c`, no `serviceParameters` at all
(the project no longer travels as an `xpath` parameter — the target service resolves it itself). Full
mechanism — the generalised `ebx.ui.MasterZonePermissionRule<S extends TableEntitySelection>`, the
`MasterZoneIndex` triple→project-code cache extension, `ebx.ui.MasterZoneProjectResolution`, and how each new
user service reuses its existing sibling's rendering — is documented in `docs/RECORD-LINEAGE.md`'s own
"Cockpit and Proposal review get the same module-level treatment" section, not duplicated here.

**Not yet redeployed or seen running**: this follow-up lot ran under the same read-only-MCP-tools constraint
as the rest of &sect;12 — the fix has been written and unit-tested (`tools/test_local.sh` green) but not
checked against a real instance. Next for the product owner: redeploy, restart Tomcat (module-level registration,
CLAUDE.md &sect;9), re-run `master/generate` (or wait for the next one), and confirm the "Cockpit"/"Proposal
review" buttons on `Companies_Master`'s own toolbars now open the right screen, scoped to the right project,
instead of the generic data view.

### 12.9 `DmaToolbarWriter`'s idempotence rule changed: content-aware replace, not name-only

The original "idempotent by toolbar NAME" design (&sect;12.4/&sect;12.5) left a root found by name completely
alone, children never re-read — correct for a single write, but it meant the three roots &sect;12.7 already
wrote on `Companies_Master` (with the broken Cockpit/Proposal review buttons inside) would have kept serving
that broken content FOREVER: nothing about a later `master/generate` call would ever have touched them again,
since the toolbar NAMES (`MdmFactoryTableTop`, etc.) do not change between the old and new button design.

`ebx.DmaToolbarWriter.write` now reads an existing root back into a `master.ToolbarSubtreeDiff.ExistingNode`
tree and compares it against the definition — `type`, `ordering`, `actionButton/service/serviceKey`/`target`/
`serviceParameters` (name/value pairs, in order), and `menuGroup/menuBuilder/menuBuilderKey` only (never
documentation labels, icons, `modalSize`, or `resizable` — cosmetic, never the subject of this incident).
Identical (empty differences) still reports `EXISTS`, untouched, the original behaviour. Any difference
deletes the whole existing subtree — children first, then the root, `ProcedureContext.doDelete(AdaptationName,
boolean)` (confirmed by `javap` against `ebx-lib.jar` to delete a RECORD given its own
`Adaptation.getAdaptationName()`, not only a dataset given `AdaptationName.forName(...)` — the local Java API
doc's own wording, "Deletes the specified dataset or record") — and recreates it fresh from the definition,
inside the SAME procedure, reporting `REPLACED` with the exact difference lines. The replaced root's own `id`
changes; this is fine, `set_toolbars` binds by NAME (&sect;12.2), never by id, and is re-run on every promoted
table regardless.

The comparison logic (`master.ToolbarSubtreeDiff`) is pure, no EBX import, unit-tested
(`ToolbarSubtreeDiffTest`) without any running instance — the same discipline as `MasterToolbars` itself. The
read-back technique it is fed from (`DmaToolbarWriter.readSubtree`) is INFERRED, not measured live:
`Adaptation.getSchemaNode().getNode(path)` to resolve a repeatable group's sub-field `SchemaNode`s (CLAUDE.md
&sect;9's own "guard generic reads" technique, applied to a group rather than a scalar), then
`SchemaNode.executeRead(Object)` per occurrence — confirmed to exist by `javap` (symmetric with the
`executeWrite` calls the write path already used), never independently exercised against a real
`toolbarNode` row. This is the next thing to confirm once &sect;12.8's fix is deployed: does a `master/
generate` re-run on `Companies_Master` correctly detect and replace the three already-broken roots from
&sect;12.7, rather than (for instance) reporting spurious differences on an unrelated field this comparison
was never meant to touch.

### 12.10 Menu button icon-default incident, measured live 2026-09-22, 16:27–16:29 — fixed the same day

With §12.8's fix deployed and working (toolbars written, migrated by `replaceRoot`, bound, all three buttons
functional — the product owner's own confirmation), `kernel.log` still logged, on EVERY rendering of a master table or
record (4 lines seen at 16:29, unchanged since the very first version of `DmaToolbarWriter` at 15:20):
`ERROR log.kernel … Menu Button  is configured with an icon but it has no icon reference.`

**Diagnosis (read directly off the publication snapshot `DMA_2026-09-22T16:27:48.469`/`Companies_Master`/
`/root/toolbarNode` with `preview_records`, not guessed)**: the two `MENU_BUTTON` rows ("Actions", ids 328 and
339) carry `menuButton/display/icon/type = "css"` with `menuButton/display/icon/reference = null`.
`DmaToolbarWriter.createNode` never wrote anything under `./menuButton/display/icon/*` at all (only
`MB_DOCS`, `MB_LAYOUT`, `MB_HIGHLIGHTED`) — so the schema's own `icon/type` facet default (`"css"`, applied
the instant `getContextForNewOccurrence` instantiates the `display` group) silently survived onto every
`MENU_BUTTON` row this class ever wrote. The toolbars EBX's own UI creates (model `Companies`, row id 4 in the
visible snapshot `DMA_2026-09-20T22:00:55.756`, dataset `Companies`) have `icon/type = null` and
`reference = null` on their own "Actions" menu button, and the shipped
`webapps/ebx-assistant/WEB-INF/ebx/schemas/mcp-servers-administration_1.0_toolbars.xml` writes a menu button
`display` with `layout` + `highlightedButton` and NO `icon` element at all — confirming the bug was specific
to this writer, not an inherent property of a `MENU_BUTTON` row.

**Fix**: `DmaToolbarWriter.createNode`'s `MENU_BUTTON` branch now writes `pNode.iconType`/`pNode.iconReference`
unconditionally into `./menuButton/display/icon/type` and `.../reference`, right after the layout/highlighted
writes — the same unconditional-write pattern the `ACTION_BUTTON` branch already used for its own icon fields
(which is why that branch needed no change: it already calls `row.setValue(pNode.iconType, ...)` regardless of
whether the value is null, so a schema default can never survive there). Both fields are null today (no
menu-button definition in `MasterToolbars` sets them), so the explicit write stores `null` and overrides the
default.

**What the local Java API doc says about overriding a schema-defaulted value on a new occurrence**: the local
Javadoc of `ValueContextForUpdate.setValue(Object, Path)` (`onHelp/advanced/Java_API/…`, read 2026-09-22) does
not special-case a schema-defaulted value at all, and does not distinguish a brand-new occurrence from an
existing record — its only documented null-related failure is passing `AdaptationValue.INHERIT_VALUE` under a
terminal node (dataset-branch inheritance, unrelated here). `setValue(null, path)` to blank an optional field
is already an established pattern elsewhere in this codebase (`CapabilityApplier`, `MasterReset`), just never
before specifically on a field left at its just-instantiated group default. `setValueEnablingPrivilegeForNode`
is explicitly NOT the answer — its own Javadoc says it only affects permission evaluation for the write, not
value defaulting. Nothing in the doc or in this codebase's own precedent suggested the explicit-null write
would fail to stick; the fallback considered, if it somehow did, was writing the whole `display` group's value
via `SchemaNode.executeWrite` (the same group-level technique `localizedDocs` already uses) rather than per
leaf field.

**`ToolbarSubtreeDiff`** now also compares, for a `MENU_BUTTON` row only, the existing
`menuButton/display/icon/type` against the definition's own `iconType` (expected null) — `ExistingNode` gained
a `menuButtonIconType` field (a second constructor overload keeps every pre-existing 7-argument call site
compiling unchanged) and `DmaToolbarWriter.readSubtree` now reads it back. `ToolbarSubtreeDiffTest` gained two
cases: `menuButtonIconTypeDefaulted_reported` ("css" existing vs. null definition → reported) and
`menuButtonIconTypeNull_identical` (null vs. null → no difference). This is the one icon field the comparison
does NOT treat as cosmetic (every other icon/documentation/`modalSize`/`resizable` field stays out of scope,
class Javadoc) — a non-null value here is exactly the incident's own signal, not a styling choice.

**Expected on the next `master/generate` against `Companies_Master`** (not yet run — this fix, like the rest
of §12, was written under the same read-only-MCP-tools constraint): `MdmFactoryTableTop` and
`MdmFactoryRecordTop` — the two roots with an "Actions" `MENU_BUTTON` — report `REPLACED`, with a difference
line naming `menuButton icon type` (`expected null, found "css"`); `MdmFactoryTableRow` (no menu button at
all) reports `EXISTS`, unaffected. Once that regeneration runs, the four `kernel.log` lines should stop
appearing. **Never claim this was seen running** — it has not been.

### 12.11 Cockpit and Proposal review REMOVED from the master toolbars, the product owner's decision, 2026-09-22 16:40

Cockpit and Proposal review, added to the master toolbars at 15:19 and made to actually work as module-level
services by §12.7-§12.9, were removed again the same afternoon. the product owner's own reasoning: *"Cockpit et la
revue des suggestions sont des choses à faire en amont de la génération de la table master. Aucun intérêt de
les mettre là."* — both screens belong to the staging phase, before a master table even exists, so a button
for them on the master table itself never made sense; they move to the factory's own `Project` table/record
instead (a separate lot, not this one — see PLAN.md's "Barres d'outils du Projet" entry once it lands).

**What changed** (branch `fix/master-toolbars-lineage-only`, worktree `ebx-mdm-factory-lineage-only`):
`master.MasterToolbars` now writes ONLY "Record lineage" on all three toolbars — `MdmFactoryTableTop`:
Create, separator, Actions menu, separator, Record lineage (5 children, was 9); `MdmFactoryTableRow`:
Record lineage alone, unchanged; `MdmFactoryRecordTop`: Create, separator, Actions menu, separator, Record
lineage (5 children, was 7). The two module-level services `ebx.ui.FactoryCockpitOnMasterServiceDeclaration`/
`FactoryCockpitOnMasterUserService`/`ProposalReviewOnMasterServiceDeclaration`/
`ProposalReviewOnMasterUserService` and the `ebx.ui.MasterZoneProjectResolution` lookup they alone used are
deleted; their registration lines in `FactoryModuleRegistration.handleServiceRegistration` and their
`cockpit.onMaster.*`/`review.onMaster.*` message keys are gone. `ProposalReviewUserService` is `final` again
(the only reason it had been made non-final). `MasterZonePermissionRule` and `MasterZoneIndex`'s
triple-to-project-code map (`projectCodeOf`/`MasterZoneActivation.projectCodeFor`) are KEPT — generic,
tested, harmless, and `RecordLineageOnRecordPermissionRule` still uses the permission rule directly; the
project-code lookup currently has no caller (both classes' own Javadoc now says so).

**Migration on the next `master/generate`**: `MdmFactoryTableTop`/`MdmFactoryRecordTop` are found by name in
`ebx-dma/Companies_Master/root/toolbarNode`, read back, and compared against the new, narrower definition
(§12.9's content-aware `ToolbarSubtreeDiff`) — the existing rows have MORE children (9/7) than the new
definition (5/5), a child-count mismatch `ToolbarSubtreeDiff.differences` already reports generically (the
same code path as `differentChildCount_oneDifference_noCrash`, now also covered in the OTHER direction by
`ToolbarSubtreeDiffTest#existingSubtreeHasExtraChildren_reported_noCrash`, added this lot) — so both are
expected to report `REPLACED`, deleted and rewritten with only the Create/separator/Actions/separator/Record
lineage shape, and the model republished. `MdmFactoryTableRow` (already just Record lineage, unchanged
shape) is expected to report `EXISTS`, untouched. **Written, NOT deployed, never seen running** — this task
ran under `tools/test_local.sh` only, no MCP write tools, no live instance. A full Tomcat restart is required
before any of this takes effect, since module-level service registrations were removed
(`FactoryModuleRegistration`, CLAUDE.md §9).

## 13. Incident, 2026-09-23: `master/generate` refused a model whose staging entity carries an attribute named `Id`

**Seen live**, project `ScratchMatch` (staging entity `SalesforceSample`, built by rules from the
`PersonsSalesforceSample` connector's `sample.json`, catalog source `PERSONSSALESFORCESAMPLE`): `POST
/projects/ScratchMatch/master/generate` answered **409** — *"The master model of ScratchMatch does not compile
or is not valid (build_data_model dry run): SubTypeAlreadyExistsException: Type
[ComplexType[name=SalesforceSample] 0. id 1. sourceSystem 2. sourceId] has already a subType defined at
AspectKey[id]"*. The staging zone was fine: staging keys a table by `sourceSystem`+`sourceId` and adds no `id`
field, so a business attribute `Id` (the Salesforce record id, accepted like any other sampled attribute) built
and loaded without a word. The master table is different (§2.1): `MasterModel` declares its own auto-increment
primary key `id` first, and **EBX resolves field names case-insensitively for this check** — `Id` and `id` are
the same `AspectKey`, and the dry run refuses the whole model before a single table is written. The generator
had exactly the mechanism to survive it (`FieldInfo.stagingField` vs `masterField`, "identical unless a name
collision forced a suffix") but never used it for a plain field: only a generated `<attribute>Ref` field went
through `unique()`.

### 13.1 The rule — `master.MasterFieldNameRule` (pure, unit-tested)

A business (non-primary-key) attribute whose name equals, **case-insensitively**, one of the five fields every
master table carries on its own — `id`, `sourceSystem`, `sourceId`, `_loadId`, `_receivedAt`
(`MasterFieldNameRule.RESERVED`) — is promoted under **`<name>_src`** (the SOURCE system's own value of that
name: `Id` → `Id_src`, original casing kept). Deterministic: the same attribute list always yields the same
names, so nothing is stored. The improbable second-order clash (an entity that also carries an attribute
literally named `Id_src`) appends the suffix again until the candidate clashes with neither a reserved name nor
any sibling attribute of the entity, still deterministic, never an exception. A primary-key attribute
(`sourceSystem`/`sourceId`, `TechnicalKeyRule`) IS the master field of that name and is never renamed. A name
merely containing a reserved word (`identifier`, `siteId`, `idCard`) is untouched.

Renaming the attribute rather than the table's own key was the contained choice: `id` is what `Xref`, the
record links (`record:./id=…`), `GoldenKey*`, the record-lineage service and the add-on's own metadataset all
key on; a suffix on one business attribute touches only the readers listed below. Refusing with a message
naming the attribute was the fallback and was not needed.

### 13.2 What follows the rename, and how

- **`master.MasterModel.buildTable`** — every business attribute's field name goes through the rule; the
  `maxLength` op, the `FieldInfo` (`stagingField` = attribute name, `masterField` = renamed) and the
  `osd:table/defaultLabel` candidate list use the master name; the `<attribute>Ref` foreign-key field and
  `ForeignKeyInfo.attributeName` keep the attribute's own name. A `Result.notes` line —
  *"SalesforceSample.Id: promoted as master field Id_src - its name collides (case-insensitively) with the
  'id' field every master table carries; the transfer mapping and the master loader follow the rename."* —
  is what `master/generate` echoes in its response `notes` (it already copies `modelResult.notes` there).
- **`master.MasterTransferPlan` / `ebx.MasterLoader`** — no change: the plan is built from `FieldInfo`, the
  loader reads `stagingField` and writes `masterField` (`MasterTransferPlanTest#renamedReservedAttribute…`).
- **`ebx.RecordLineageReader`** — its field list is now MASTER field names (what the golden record's table,
  `MergeValueLineageCompact.fieldPath` and the lineage table show), with a `stagingFieldByMasterField` map for
  the staging/landing reads and the `AttributeMapping` origins; the title candidates (`titleFieldNames`) go
  through the same rule. Before this fix such a field simply vanished from the lineage (the `getNode` guard
  found no `Id` on the master table).
- **`cataloglineage.CatalogLineageBuilder`** — the master job's output field is the master name, its input
  field the staging attribute, with a transformation `description` saying why the two differ.
- **`ebx.MatchPlanLlmSuggester.toLlmEntity`** — the name the LLM sees and proposes fields by, the
  `profile_table_for_matching`/`fieldCapabilities` lookups and the `CrossSystemMeasures` SQL reads all use the
  master name (they all name master columns); the `StagingAttribute` lookups (`attributeId`, `derivedFrom`,
  `matchKeyByName`) keep the attribute's own name.

**No migration**: a colliding name never reached a published master table (the dry run refused it), so an
existing master zone is unaffected; regeneration (`StagingModelDiff`) diffs the tree, which already carries the
renamed field. **Not covered, deliberately**: a master-zone capability (`ebx.CapabilityApplier`, zone
`MASTER`) anchored on a renamed attribute would declare its constraint at `/root/<Entity>/<attribute>` — a
path the master table does not have; `set_script_constraint` refuses a wrong path at publication (CLAUDE.md
§5), so it fails loudly, not silently. A constraint on a source record id is not a realistic case; route it
through the rule if one ever appears.

Tests: `MasterFieldNameRuleTest` (reserved names, casing, sibling clash, nulls, the primary-key exemption of
`masterFieldNames`), `MasterModelTest` (the live `SalesforceSample`/`Id` case: `Id_src`, untouched `id`, no
duplicate name case-insensitively, `FieldInfo` mapping, the note; a renamed reference attribute still gets its
`idRef` foreign key; a renamed STRING field is a display candidate under its master name),
`MasterTransferPlanTest`, `CatalogLineageBuilderTest`. `tools/test_local.sh` green. **Written on
`fix/master-id-collision`, not deployed, not replayed on `ScratchMatch`.**


---

## 14. Script derivations also run on the master table (lot T2, 2026-10-04)

A staging attribute computed by a script derivation (`docs/SCRIPT-DERIVATION.md` sections 10-12) is a STORED field fed by two EBX-Script table triggers, `Derive<Attr>OnCreate` and
`Derive<Attr>OnModify`. In the master zone it used to be a plain copy of the staging value (`MasterLoader` copies it like any other field, §2.1), so nothing recomputed it when a data steward
edited the source attribute on a master record, when Match and Merge created a golden record whose source and derived values survive from different records, or when a record was created
directly in the master. Since lot T2 the SAME two triggers (same names, same library function) are declared on the master table of the entity when the master model has it and the table carries the
derived attribute and every attribute the binding reads under its own name (a `<name>_src` rename, §13, or an attribute not promoted, skips it with a recorded reason, never an error).

They are put there at the end of `DerivationApplier.apply` (a failure is reported, never undoes the staging apply), by `MasterGenerator` on the model draft BEFORE its own publish (one publish; only an
`UP_TO_DATE` regeneration republishes for them), and taken off by `DerivationApplier.remove`. The master bodies carry no "mandatory" shortcut - a master business attribute is never mandatory (§2.1) -
so a null source always yields a null derived value instead of a call with null.

**No backfill on the master, deliberately**: the master rows already hold the staging-computed value, and a mass rewrite would fire the add-on's own matching-on-update trigger row by row. (Written
when a master load still rewrote every non-merged row at every load; since lot V3-M, section 15, only a row whose value changed is rewritten, so the triggers fire on those alone.) For each rewritten row `beforeModify` compares old and new values and
recomputes only if a source changed, if the loader's derived value differs from the stored one, or if the new derived value is empty (the same empty value again). Details and the list of what only
a live instance can confirm: `docs/SCRIPT-DERIVATION.md` section 12.

## 15. The load writes only the rows that change (lot V3-M, 2026-10-04)

`ebx.MasterLoader` used to rewrite every staged row that already had a master record at every load, and then rewrite its `Xref` row too: a reload of 1,465,857 rows with nothing changed in
staging took 436 to 641 s (project Vol1m). Since lot V3-M a load reads the existing master record once per update row and compares each value it would write with the one stored
(`transfer.StoredValueEquality`: null = empty string, numbers by value, dates by time, lists element by element, anything unprovable counts as different). **A row whose every value is already stored is
not written** - no `doModifyContent` on the master, none on `Xref` - and is reported as `unchanged`; a row where at least one value differs is written exactly as before. No XSD change, no new technical
field on the master model.

- **`_loadId` / `_receivedAt` on a master row mean "the load that last changed this row"** (they are copied from the staging row when the row is written). An unchanged row keeps the pair of the load that
  last changed it - the same meaning a staging row's pair already has for a row `transfer.IncrementalTransfer` did not rewrite. They are no longer a "last seen by a load" stamp; the step's `unchanged`
  count is where "seen and found equal" is reported.
- **What does not change**: after a load the master holds the same business values as before. A value edited directly in the master differs from staging, so it is overwritten at the next load as it
  always was (whether it should be is a policy question this lot leaves alone); a MERGED record is skipped; a golden record created by Match and Merge has no staging row and is never touched; a staging
  row that fails a REJECT script rule is still rejected, whatever its master row holds.
- **Report**: `MASTER_LOAD` message "Wrote W row(s) from R source row(s) read (U unchanged, S skipped merged, J rejected, ...)", `unchanged` in `detail.entities[]` and in the REST answer; `written` is
  inserted + updated only. The wizard's Copy cell counts `written + unchanged`.
- **A load no longer re-fires the master's triggers on rows it did not change** (the Match and Merge add-on's table trigger, the lot T2 derivation triggers): only a written row reaches them.

Design, the equality rule, the pass 2 interplay and what remains to measure live: `docs/VOLUME.md`, section "Lot V3-M".

## Matching paused during a load (lot MP)

Lot MP, branch `feature/master-load-matching-pause`, 2026-10-04 - written, unit-tested, **not deployed, never run against an instance**.

**The measured defect** (project Vol1mF, 2026-10-04, EBX 6.3.0-rc2). A matching policy whose execution mode is "on update" makes the Match and Merge add-on run an inline matching at EVERY batch commit
of a master load (`handleBeforeTransactionCommit /root/Adresse` -> "Start clustering", about 50 ms per batch of 500). On the 8th batch of one load a clustering thread recovered the table's Lucene
index to an older transaction; from then on every read of that table failed ("requested transaction ... is in the future of the last transaction ...") until Tomcat was restarted - a product defect,
reported to the editor. With the matching PAUSED (`set_table_settings {"pauseMatching": true}`; `get_matching_activation_status` then answers `state: PAUSED, paused: true`) the same load of 634,295
updated rows ran to the end. The failed load also left its `MASTER_LOAD` step `RUNNING` for ever with no message: the failure came while READING the master table, outside the code path that ended
the step.

**The rule** (`ebx.MatchingLoadPause`, called by `MasterLoader.load(...)` next to `DistributionPause`; the pure decisions in `master.MatchingLoadPauseRule`):

- Only when `Project.matchingTrigger` is `REALTIME`. The field is read by its path `./matchingTrigger` through the record's schema node (lot MT adds it): a model without the field, a null or any other
  value is `MANUAL`. In `MANUAL` mode lot MT makes the policy's execution mode manual, a load triggers no inline matching, and this class does nothing at all - no call, no detail.
- The tables: the master tables of the project's built entities whose `MatchPlan` is `APPLIED` (what `MatchingConfigurator.runInternal` runs). Each one's `get_matching_activation_status` is read;
  only a table that is `ENABLED` and not paused is paused. A table already paused by someone else is left alone and **not** resumed by this load; a table that is not enabled has nothing to pause.
  `pauseStartedAt` (wall clock, taken before the first call) and the state of every table go in the `MASTER_LOAD` step's detail (`matchingPause`). If no match-merge client can be obtained, nothing
  is paused, the detail says so (`matchingPause.skipped`, and a warning in the message) and the load runs as it did before.
- After the load, in a `finally`, whatever happened (success, failure, stop), exactly the tables this load paused are resumed (`pauseMatching: false`, one retry). A resume that fails is reported
  FIRST in the step's message ("WARNING: matching of X is still PAUSED ... resume it with set_table_settings ...") and in the detail, logged as an error, and never masks the load's own exception;
  the table stays paused until someone resumes it (the pause is recorded in the `LoadPause` ledger, so a server start also lifts it: `docs/DISTRIBUTION.md`, "A pause that outlives its load"). The order is: load, matching resume + catch-up, then the distribution targets' resume (their CHANGED send must read the master after the matching).
- The `MASTER_LOAD` step itself now always ends: any `RuntimeException`/`Error` between its begin and its normal end ends it `FAILED` with the exception, the entity being loaded and the counters written
  so far (`StagingLoader` had the same hole and got the same catch). A stop requested by the user keeps its own `STOPPED` ending.

**The catch-up** (`ebx.MatchingCatchUp`, `master.MatchingCatchUpSelection`) - only after a load that ended `DONE` and a successful resume. Records written to a paused table were not matched, by this
load or by anyone else (a data steward in the UI, another feed). The set: every master record whose EBX last-modification time (`Adaptation.getTimeOfLastModification()`) is at or after `pauseStartedAt`,
plus every record with no row in the add-on's metadataset `RecordMetadata` (never matched); a record whose state is `MERGED` is not a pivot and is left out; a record with no readable time is selected.
ONE streaming read of the master table and ONE of the metadataset's functional ids (`MameMetadatasetReads.readRecordMetadataKeys`, same tick/stop handling as `MasterLoadReads`), no per-row lookup.
If the metadataset cannot be read, only the time criterion applies and the detail says so. The run:

| selected | run |
|---|---|
| 0 | nothing (`NOTHING_TO_MATCH`) |
| 1 to 5,000 (`CATCHUP_ENUMERATED_MAX`) | scoped runs on explicit key predicates, 200 keys each (`CATCHUP_CHUNK_SIZE`), through `MatchingRunScope.all(..., false, pk, NUMERIC)` - numeric keys unquoted (lot R9) |
| above 5,000 | ONE run on the whole table (the existing "all" scope, `osd:is-not-null(./id)`): a bulk load touched too much to enumerate |

Each run is `MameMatchingApi.launchScoped` with the policy code of the entity, the call `MatchingConfigurator.runInternal` makes; `GoldenKeyRefresher.refresh` follows, so `Xref.goldenKey` is refreshed the same way. The wait
is unbounded for a job (stop-aware), bounded by `DEFAULT_MAX_WAIT_MS` for a synchronous caller. A run that does not end `DONE` stops the loop (the add-on refuses a second run while one goes on).
A catch-up that fails (or is stopped) is reported - `matchingPause.tables[].catchUp.status` `FAILED`, a warning first in the step's message - and never fails the load that already succeeded.
The detail records what was done: `mode`, `selected`, `modifiedSincePause`, `neverMatched`, `skippedMerged`, `runCount`, the runs' status and duration, the golden-key refresh.

**Wiring.** The catch-up is a step of `MasterLoader.load(...)` itself (its `finally`), not a follow-up step of `MasterBuildRun`: the REST route and the workflow bean call the same method and get the same
protection. `MatchingConfigurator` is not called: its per-entity code is the body of one loop inside `runInternal` (every entity, the scope from `_loadId` or "all", one `MATCHING_RUN` step per entity
feeding the wizard's results block) and has no entry point for an entity and a predicate. The catch-up is therefore NOT a `MATCHING_RUN` step: it lives in the `MASTER_LOAD` step's detail
(`matchingPause`), the wizard's matching results block refreshes at the next matching run, and `MasterBuildRun`'s COPY stage summary carries the warning and the recap sentence.
`PipelineSteps.amendEnded` adds them to the already ended step (`master.StepAmendment`: merge a key into the detail, put the warning first in the message).

**A record edited by hand during the window.** A data steward who saves a master record while the table is paused gets no inline matching: the edit is a record "modified since the pause" and is matched by
the catch-up of that load - but only if the load that paused the table is the one running when they edit. An edit made while a table stays paused for another reason (a failed resume, a table paused by
a person) is not covered by anything; the next scope-"all" matching run covers it.

**Limits.**
- After a load that FAILED or was STOPPED the catch-up is not run (the step says so): the records written before the failure stay unmatched, and the next load's window starts later, so an UPDATED record
  of the failed load is not selected again (a never-matched one is). Run the matching on the whole table after such a load.
- A pause that fails (or a status that cannot be read) does not block the load: it runs unprotected, as before, with the warning in the message.
- `getTimeOfLastModification` is the EBX time of the record, not of a value: a record rewritten with the same values counts as modified (matched once too often, never missed).
- The catch-up costs two streaming reads per paused table even when nothing is to be matched (`scanMs` in the detail).

What only a live instance can confirm: see the final list in `PLAN.md`, entry "lot MP".

## 16. The load reads each table once (lot V3-M2, 2026-10-04)

Lot V3-M left a master reload with nothing changed at 199-263 s on Vol1m, all of it in three random reads per staging row (the `Xref` row, the master record, the add-on's state of the record) and one
more `Xref` lookup per foreign key of a child row. Since lot V3-M2 `ebx.MasterLoader` builds, before an entity's first row, three in-memory indexes by one streaming request each: `Xref`
(`stagingKey -> masterKey`), a digest of the compared values of every master record (`transfer.RowDigest`, the equivalence of `StoredValueEquality`), and the master keys the Match and Merge add-on
reports as `MERGED` (`RecordMetadata.state = 2`, the same fact `MetadataHelper.getState` answers). A row whose digest equals the stored one is `unchanged` with no read at all; a row that differs, an
entity above `mdm.factory.masterLoad.maxIndexedRows` rows (default 15,000,000 since lot IDX, was 5,000,000; 0 disables) and a failed scan all take the lot V3-M path (`readMode: LOOKUP`). A child entity resolves its foreign keys
through its parent's `Xref` index, which follows the writes; pass 2 reads the same index. Nothing the master holds after a load changes, no XSD change, no new technical field; the step detail gains
`readMode`, the index sizes and build times, `masterRecordReads` and `xrefLookups` per entity. Design, memory estimate and what remains to measure live: `docs/VOLUME.md`, section "Lot V3-M2".

## 17. The golden of a merged parent is read in the merged-records scan (lot V3-M3, 2026-10-04)

On Vol1mF a reload that wrote nothing took 389 s because Adresse asked the add-on LIVE for the golden record of each of the 417,841 distinct merged persons (370 s). Since lot V3-M3 the merged-records read of §16 also
reads each record's `goldenId` (the same single request of `RecordMetadata` restricted by `state = 2`) and `ParentKeys` answers each hop of a parent's merge chain from it, under the same rule as "known not merged": only while no
row was written to the parent's table since the read (`refreshMergedKeys` reads again after the entity's pass), otherwise, in LOOKUP mode, or for a merged record whose `goldenId` is empty, the add-on is asked as before.
The step detail's `goldenHopsByIndex` / `goldenHopsLive` (per entity) show which path answered; `referencesToGolden` keeps its meaning and an unchanged reload shows `goldenHopsLive: 0`. The `Xref.goldenKey` refresh that
follows a matching run reads the same metadataset by one scan instead of per record (`docs/MATCHING.md` §53). Design, memory and what to measure live: `docs/VOLUME.md`, section "Lot V3-M3".

## 18. The load can read only the staging rows that changed (lot M-INC, 2026-10-12)

Lots V3-M..V3-M3 made an unchanged reload cheap per row, but a load still reads every staging row (29-42 s for 1,465,857 rows on Vol1mF, linear). Since lot M-INC two generated script triggers on every promoted staging table,
`MasterJournalAfterCreate` / `MasterJournalAfterModify`, journal each created or modified staging record into a technical dataset of the project's staging dataspace (`MdmFactoryMasterJournal`, model `MasterJournal.xsd`), whoever wrote it
(staging loader, derivation triggers and backfill, a steward in the UI, Data Services). `POST /projects/{code}/master/load?scope=changed` (also the `scope` of `MasterLoadTaskBean`, variable `masterScope` of `MdmFactoryPipeline`)
then loads, per entity, only those rows plus the staging rows of the entity's open MASTER rejects, and deletes the journal rows it read once the whole load ended normally (a row written again meanwhile keeps its row: a change counter is
compared). `scope=full` (default; or `-Dmdm.factory.masterLoad.defaultScope`) is §2-§17 unchanged.

`changed` is a request, answered per entity: the entity is loaded in full - the step says why - when the journal does not exist yet or its triggers are missing (a regenerated staging model loses them; the load that installs them
is full), when no full load of the entity finished since recording started (or the last one did not: a full load disarms the entity when it starts and arms it when it ends), when what the load copies changed
(`master.PlanFingerprint`: plan fields, foreign keys, writable master fields, script rules), when the master table is empty, when the entity has deferred (self-reference) foreign keys, or when the journal holds more than
2,000,000 rows. Up to `-Dmdm.factory.masterLoad.journalLookupMax` (20,000) keys the staging rows are read by primary key and the entity runs without indexes (LOOKUP mode of §16: parents are resolved by an `Xref` lookup per
distinct parent, merged parents by the live add-on chain); beyond it the staging table is scanned once, filtered on the keys, with the indexes of §16-§17. An empty journal ends the load at once ("Nothing changed").

What a changed load leaves alone, on purpose: values edited directly on master records, the re-pointing of a child whose staging row did not change after its parent was merged or unmerged (the add-on's alignment or a full load does
that), the delta meaning of the script-rule counters. `-Dmdm.factory.masterLoad.journalEnabled=false` is the kill switch. Design, risks, properties and the list of what to check live: `docs/VOLUME.md`, section "Lot M-INC".

## 17. Outcomes that match what was written

A helper that runs a Procedure returns its failure to its caller, and the caller's reported outcome reflects it. Only the places where the reported outcome was wrong were changed; a write-back whose
failure changes nothing a person reads stays "log only".

- **A staging load whose every row was rejected is FAILED**, by the rule the master load has (`master.MasterLoadOutcome.isTotalFailure`: at least one row read, every one of them rejected - never "0 written"
  alone, so an all-unchanged or all-skipped run stays DONE). The `STAGING_LOAD` step ends `FAILED` with "Every source row read was rejected...", the result says `status: FAILED`, the workflow task bean
  `StagingLoadTaskBean` throws (the step lands in error, as `MasterLoadTaskBean` does), and the job stages of `StagingLoadRun` (LOAD) and `MasterBuildRun` (COPY) fail instead of reporting a success their own step
  contradicts. **Operator-visible**: a project whose landing data is entirely refused used to read "Staging build finished"; it now reads failed, with the reasons in `TransferReject`.
- **Deferred master references are no longer counted resolved before they are written** (`MasterLoader`, pass 2): a batch whose procedure fails moves its references from `deferredResolved` to
  `deferredUnresolved` (`master.DeferredFlushOutcome`), `deferredWriteFailedRows` appears in the entity detail and the result, and a WARNING comes first in the step message. The load still ends DONE (pass 1 is committed).
- **`CapabilityApplier` / `DerivationApplier`**: when the script constraint (or the two triggers) is live on the model but the suggestion row could not be set to APPLIED, the apply no longer answers
  `ok: true`. The capability apply answers `ok: false, declaredOnModel: true` and ends its step FAILED; the derivation apply throws and ends its step FAILED. Both say that the script is on the model and to apply again.
- **`LandingFlows`**: the mapping a flow created is no longer lost when recording it on the landing table fails: its id is in the table's step detail (`mappingId`), in the failure message and on the table's message; the
  table's flow reads FAILED, and the next generation finds the mapping again by its label and updates it in place.
- **`CatalogLineageDeclarer`** (a `SKIPPED` step row) and **`DistributionRunner.writeLastSend`**: the Procedure result is read. A SKIPPED row that could not be written ends the step FAILED and throws; a send whose target
  status could not be recorded says `lastSendNotRecorded` in its answer (the send itself and its step are unaffected).

Not changed: the 30-odd other "log only" Procedure results (counters, bookkeeping the screens do not depend on); `StagingLoader`'s own deferred-reference flush keeps no "resolved" counter, so it had no wrong figure to correct.

### Steps and agent runs always end

- `ebx.StepGuard` is the one place that decides what happens to a step whose body throws something its own handlers do not cover (an unhandled `OperationException`, a `RuntimeException`, an `Error` such as a
  `LinkageError` or a `VerifyError` after an upgrade): the step ends `FAILED` with the reason (`Unexpected error: <class>: <message>`), the stack goes to `kernel.log`, the exception is rethrown untouched. A step
  that already ended keeps its outcome (the guard reads the row first). The step bodies call `StepGuard.endUnexpected` from a `catch (RuntimeException | Error)` that rethrows; the bodies were not re-indented
  into closures, so no body changed behaviour. Added to `LandingGenerator` (model, dataset), `LandingFlows` (flows, load: one table's `RuntimeException` fails that table, an `Error` ends the step),
  `StagingGenerator` (model, dataset), `MasterGenerator` (model, dataset), `MasterReset`, `StagingSuggester`, `StagingLlmSuggester`, `MatchPlanLlmSuggester`, `QualitySuggester` (both tries),
  `AnomalyLlmSuggester`, `DistributionConfigurator` (which did not end its step on an `OperationException` either), `DistributionRunner.send`, `DistributionTriggerInstaller`. Not covered: `CapabilityLlmSuggester`
  (a file this lot did not touch) still ends its step on `OperationException`/`McpException` only.
- `AgentRun` rows: `AgentRuntime.runLoop` ends the row `FAILED` when the loop throws, and the module start marks every row still `RUNNING` as `INTERRUPTED` with an end date and a note (`ebx.AgentRunRecovery`, next to the
  recovery of steps and jobs). The enumeration of `AgentRun.status` gained the value `INTERRUPTED` (additive, `tools/build_xsd.py`). Since the history purge keeps every `RUNNING` row and deletes the others past
  the retention, those rows become purgeable.

## 19. The business objects of the master model (lot W13, 2026-10-12)

For a matching plan that compares a child field (`Adresse.city` on a `Personne` plan) the add-on 6.3.0.5-RC3 needs a business object on the master model (`docs/MATCHING.md` section 33.12).
`ebx.MasterGenerator` declares them on the draft, through the `data-model` server, right before the `publish_data_model` that carries the rest of the model - on a fresh build, on a regeneration, and
on the up-to-date branch (so a master generated before this lot gets them by running the Master step again; a write there republishes). For every promoted parent table that has promoted child tables
(`master.BusinessObjectPlan.plan`, read off `MasterModel.Result.foreignKeys`; a self-reference is not a child):

| Call (`data-model` server) | Arguments |
|---|---|
| `execute_query` `get_data_model_business_objects` and `describe_table` of the parent | what exists: the extension, the objects, the parent's fields and associations |
| `execute_operation` `enable_extension` | `{"extension":"businessObjects"}`, only when the model says it is not enabled |
| `create_field` | `/root/<Parent>/<children>` of type `string` (`adresses` for `Adresse`: the child name, first letter lower case, plural `s`; `_assoc`, `_assoc2` when the name is taken) |
| `set_association` | `field_path=/root/<Parent>/<children>`, `mode=tableRefInverse`, `fk_field_path=/root/<Child>/<fk field>` |
| `execute_operation` `create_business_object` | `{"name":"<Parent>BO","main_table":"/root/<Parent>"}` |
| `execute_operation` `add_business_object_relationship` | `{"business_object_name":"<Parent>BO","relationship_path":"/root/<Parent>/<children>"}` |

Idempotent (`BusinessObjectPlan.reconcile`): everything in place = no call; a half-done run resumes at the missing write; an association over the same foreign key is reused whatever its name; an object
on the parent table is reused whatever its name. A refused write ends that declaration (the notes say which and why) and the master is still delivered. A child with two foreign keys to the same parent
gets one relationship (the first), the other is listed in the step detail (`businessObjects[].skippedForeignKeys`). A master with no child entity declares nothing and makes no call. The step detail
keeps every call under `businessObjects[].calls`.
