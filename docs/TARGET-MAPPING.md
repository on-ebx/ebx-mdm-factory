# Target mapping: the staging IS the target model, fed by Connectors-style mappings

Design written 2026-09-23 on branch `feature/target-mapping` (worktree `ebx-mdm-factory-target-mapping`),
from a product-owner decision of the same day. Nothing in this document was run live; every claim about
existing code is READ from the named file, every claim about Connectors was READ in `~/Dev/ebx-connectors`.

## 0. The decision (the product owner, 2026-09-23)

- **The landing keeps the shape of each source.** Nothing changes there.
- **The staging is the TARGET model** - one model compatible with every source, a prerequisite of the
  project, never "one entity per landing table lightly renamed". A flat source (person + address in one
  row) and a two-table source (person, address with a foreign key) must both land in the SAME
  `Personne` + `Adresse` staging tables, each row keyed by `sourceSystem` + `sourceId`.
- **The landing -> staging transfer is configured with the mapping vocabulary of Connectors**
  (`ebx-connectors`, `com.on.ebx.connect.core.MappingDefinition` / `MappingRule` / `Transformations`):
  rule pipelines (`concat`, `substring`, `regex`, `map`, `lookup`, `constant`, `date`, `number`, ...),
  and a parent/child tree of feeds (one landing row can feed a parent entity AND its child entities).
- **The Factory's native loader stays** (batches, rejects with reasons, quality rules, cleaning steps,
  derived fields, null fill, incremental by hash, load order, `Xref`), and **delegates the VALUE
  computation to Connectors' engine used as a library** - never as real Connectors flows (one
  transaction per item there, and rejects readable only in the sync answer).

What the current code cannot do, and why this is needed (measured 2026-09-23 on
`transfer/Transformations.java`, `transfer/TransferPlan.java`, `ebx/StagingLoader.java`,
`staging/StagingLlmPass.java`): an `AttributeMapping` reads exactly ONE landing column through ONE of
nine transformations; a feed is the mechanical pair (entity, landing table); a canonical (LLM) attribute
is written without any `referenceEntity`; nothing can turn one landing row into a parent row plus child
rows. The two-source person/address example is therefore impossible today.

## 1. Facts about Connectors' engine (READ 2026-09-23)

- `core/MappingEngine.compute(MappingDefinition, JsonNode document, TransformationContext)` -> `Values`
  (target path -> XML-schema STRING, plus per-rule policy and an `errors` list): "transformations
  applied, nothing written". Pure enough for our use: the only EBX access is inside the transformations
  that need it (`fk`, `lookup`, `golden`, `mdmstate`), through the `TransformationContext`'s dataset/session.
- `MappingEngine.applyChildren(ProcedureContext, parent, document, parentRecord, ctx, SyncResult)`
  creates/updates child rows itself, matching on `childKeyFields` + `parentLinkField`, and links the child
  with the PARENT'S REAL primary key (`parentRecord.getOccurrencePrimaryKey().format()`). We do NOT use it:
  our child rows carry the staging identity (`sourceSystem`/`sourceId`), rejects, `Xref` and batches - we
  iterate child feeds ourselves and call `compute` per element (sec 3.3).
- `MappingDefinition` is built programmatically: constructor, `addRule(MappingRule)`, `addChild`,
  `setSubObject(collectionPath, parentLinkField, childKeyFields, ...)`, `setFeed(itemsPath, keyPredicate,
  createIfMissing)`. `MappingRule(position, sourcePath, targetPath, transformation, Policy, comment)`;
  `Policy` = `IF_EMPTY` / `ALWAYS` / `TRUSTED`. Several rules on one target: the first non-empty wins.
- `Transformations.apply(pipeline, rawValue, ctx)`: 35 registered names - `trim upper lower capitalize
  left right substring replace regex vatfr digits prefix suffix default constant nullifempty nullif
  isodate isodatetime date map join first count boolean number round fk lookup golden mdmstate hash now
  runid concat`. `concat(path1,path2,...)` resolves paths against `TransformationContext.getDocumentRoot()`
  (set by `compute`), fixed `|` separator, null piece -> "". `runid` calls `ConnectorService.currentRunId()`:
  the ONE dependency that drags the whole module in (sec 5, lot 0).
- Class closure of the engine (same-package references, measured by script): `MappingEngine`,
  `MappingDefinition` (+ `spi.SynchronizationStrategy`), `MappingRule`, `MappingResult`, `Transformations`,
  `Transformation`, `TransformationContext`, `Json`, `Xpath`, `SampleValueSafety`, `SyncResult`,
  `TrustOracle`, `Hashing`, `GoldenRecords`, `MameGoldenRecords` - all EBX-lib + Jackson only, except the
  `runid` call above. Small enough for a pure jar, exactly like `on-ebx-agent.jar` already extracted from
  the same repository (`ebx-connectors/build-agent.sh`, consumed by `tools/agent_jar.sh` here, listed in
  the Eclipse `.classpath` of the deployed copy as `src/main/webapp/WEB-INF/lib/on-ebx-agent.jar`).
- The Factory does NOT compile against Connectors today (no `com.on.ebx.connect` import; classpath =
  server lib + `WEB-INF/lib/*`, `tools/test_local.sh`). Two webapps = two classloaders: a copy of the
  engine classes in the Factory's `WEB-INF/lib` is invisible to Connectors' own, no conflict (same as the
  agent jar).
- The EBX connector has a `search` operation (table, XPath filter, pageSize): a landing table COULD be the
  source of a real Connectors inbound mapping - kept as the fallback only (sec 7).

## 2. Model changes (`tools/build_xsd.py` -> `MdmFactory.xsd`; every change ADDITIVE)

### 2.1 New table `/root/TransferFeed` - one node of a mapping tree

| field | type | meaning |
|---|---|---|
| `id` | auto | |
| `entity` | FK `StagingEntity`, mandatory | the TARGET entity this feed writes |
| `landingTable` | FK `LandingTable`, mandatory | the landing table read (a child feed names the SAME table as its parent when it reads the parent's own row, `collectionPath` empty) |
| `parentFeed` | FK `TransferFeed`, optional | empty = root feed; set = child feed, one child row (or N, see `collectionPath`) per parent row |
| `collectionPath` | string 255, optional | JSON path, in the landing row document, of the array/object holding the child elements; EMPTY = the parent row itself is the one element (the flat-source case). Landing rows are flat today, so this stays empty everywhere for now; kept for parity with Connectors and for a future JSON-typed landing column |
| `parentLinkField` | string 80, child only, mandatory then | the staging FIELD of the child entity that holds the parent's staging key (`personneRef`) - the `<attribute>Ref` field `StagingModel.build` generates for the child's `referenceEntity` attribute; the loader writes `parentKey.format()` into it |
| `sourceIdPipeline` | string 500, optional | how the child's `sourceId` is computed from the landing row document, Connectors pipeline syntax, e.g. `concat(ID)\|suffix(#MAIN)`; EMPTY on a root feed = the landing row's own `_sourceId` (today's behaviour), EMPTY on a child feed = `<parent sourceId>#<position of the child among its siblings>` |
| `origin` / `confidence` / `rationale` / `status` | the `proposal(...)` block every proposal table has | reviewed like an attribute (natures in sec 4.3) |
| `mappings` | association `AttributeMapping/feed` | |

`sourceSystem` of every row of a feed = the landing table's `ProjectSource.sourceSystem` (unchanged).
The `_loadId` / `_receivedAt` / `_status` bookkeeping stays exactly as `StagingLoader.upsertOne` writes it.

### 2.2 `AttributeMapping` gains two fields; nothing existing changes meaning

| field | type | meaning |
|---|---|---|
| `feed` | FK `TransferFeed`, optional in the XSD (mandatory in practice after the backfill of sec 6) | the feed this rule belongs to. Today's implicit feed (entity, landing table of `landingColumn`) becomes explicit |
| `pipeline` | string 2000, optional | a Connectors rule pipeline. When NON-EMPTY it REPLACES `transformation` + `argument` entirely; the rule's `sourcePath` is `landingColumn.fieldName` and the pipeline may read OTHER columns of the same landing row through `concat(...)` |

`landingColumn` stays mandatory: the "primary" column of the rule (what cleaning steps apply to, what
the review screen shows, what lineage points at). `transformation`/`argument` stay the ONLY way for every
existing row and for the rule pass; `transfer/PipelineCompat` (pure, new) translates them to a pipeline
string at load time: `NONE` -> `` (empty), `TRIM` -> `trim`, `UPPER` -> `trim|upper`, `LOWER` -> `trim|lower`,
`PARSE_DATE(p)` -> `trim|date(p)` (check the real `date` argument syntax in `Transformations.java` and adapt:
the target is an xs string EBX types itself, so a `DATE` target wants `yyyy-MM-dd`, a `TIMESTAMP`
`yyyy-MM-dd'T'HH:mm:ss`), `PARSE_DECIMAL(sep)` -> `trim|replace(sep,.)|number` (only when `sep` != `.`),
`PARSE_BOOLEAN` -> `trim|boolean` (verify `boolean` accepts O/N, oui/non like ours did; if not, add those
words to Connectors' `boolean` in lot 0 - never a second boolean parser here), `TRANSCODE(json)` ->
`trim|map(...)` with `map`'s own argument syntax (READ it), `CONSTANT(v)` -> `constant(v)`. The compat
translation is unit-tested pair by pair against the OLD `transfer/Transformations` outputs on the same
inputs, then `transfer/Transformations` is DELETED (its `checkConstraints` moves to `PipelineCompat` or a
small `transfer/StringConstraints`; `checkTypeMatch` disappears - EBX types the xs string at write time,
and a bad shape is caught by `BatchRetry` as a per-row reject, like any other blocking constraint).

### 2.3 `StagingEntity.memberEntities` is kept, no longer the truth

The truth of "which landing tables feed this entity" is `TransferFeed`. `memberEntities` stays for the
existing canonical entities and for `MasterModel`'s promotion rule until lot 4 rewrites that rule as
"every ACCEPTED target entity is promoted" (a rule entity that was absorbed is `SUPERSEDED`, never
promoted, already today - `StagingLlmSuggester.supersedeMembers`).

## 3. The loader (`ebx/StagingLoader`, `transfer/TransferPlan`)

### 3.1 Plan

`TransferPlan.Feed` gains `feedId`, `parentFeedId`, `collectionPath`, `parentLinkField`,
`sourceIdPipeline`, `children: List<Feed>`; `FieldPlan` gains `pipeline` (already translated by
`PipelineCompat` when the row had none), `sourcePath` (the landing column's `fieldName`) and keeps
`dataType`, `maxLength`, `mandatory`, `foreignKeyField`, `targetEntityName`, `cleaningSteps`,
`qualityRules`. Grouping is no longer by the (entity, landing table) pair: it is the `TransferFeed` rows.
A mapping row without `feed` (pre-backfill) is grouped by the old pair into a synthetic root feed, so the
loader never refuses old data. Load order: unchanged (`RelationRule.loadOrder` over entities); within one
entity, root feeds first, then their children depth-first - a child entity's table is loaded AFTER its
parent entity's table by the existing entity order (the child's `referenceEntity` is the parent), so the
parent key resolution of sec 3.3 always finds the row.

### 3.2 One landing row -> one JSON document

`StagingLoader.loadFeed` already reads each landing row as an `Adaptation`. It now builds ONE
`ObjectNode` per row: every field of the landing table as its XML-schema string
(`Adaptation.getString`/`SchemaNode.formatToXsString`), keyed by `fieldName`; the technical fields
`_sourceId`, `_sourceSystem`, `_loadId`, `_receivedAt`, `_rowHash`, `_status` included. This is the
`document` every rule and every `concat` path resolves against - the same shape the EBX connector's
`search` answers, so a mapping written for the Factory is a valid Connectors mapping too.

### 3.3 Per row, per feed (replaces the `Transformations.apply` call in `planRow`)

1. Cleaning steps (unchanged, `quality.CleaningSteps.applyPipeline`) on the primary column's raw text;
   **per field since lot M6 (2026-10-04)**: the cleaned text is put into a COPY of the document under that
   FIELD'S OWN alias column (`transfer.FeedRules.cleanedColumn`, `__cleaned__<stagingField>`) and only that
   field's rule reads it (`FeedRules.ruleSourcePath`). The shared column keeps the raw text, so two mappings on
   one column never see each other's steps, and a `concat` naming the column reads the RAW text. Until M6 the
   cleaned text replaced the shared column itself: on the ECOM feed of Personne (`FULL_NAME` read by `firstName`
   and `lastName`, an `UPPER` step on `lastName` only) the first-name regex ran on `AURELIE-SOPHIE NAVRET` and
   `firstName` was empty on 100 % of the rows. Order per field is unchanged - steps, then the mapping's own
   pipeline - so a case-sensitive pipeline behind an `UPPER` step still sees upper case: that is the mapping's
   configuration (put the step after the pipeline, i.e. on the result, or drop it), not scope.
   **Guard**: the loader counts, per mapped field that reads a landing column, the rows seen, the rows whose source
   column is filled and the rows that got a value (`transfer.FieldFillCheck`, two increments in the loop above);
   at the end of the load a field filled on at most 1 % of the rows where its source column is filled is reported,
   one line per (entity, feed, attribute), in the `STAGING_LOAD` step message and detail (`attributesNotFilled`)
   and the load's JSON answer: `Personne.firstName: 0 of 153,937 rows filled from VOL1MECOM.FULL_NAME (the source
   column is filled on 153,937) - check the mapping`.
2. `MappingEngine.compute(feedDefinition, document, ctx)` ONCE per row per feed (not per field):
   `feedDefinition` is a `MappingDefinition` built ONCE per feed from its `FieldPlan`s (rule = position,
   `sourcePath` = column `fieldName`, `targetPath` = `./<stagingField>`, transformation = pipeline, policy
   `ALWAYS`); `ctx` = `new TransformationContext(stagingDataset, null, session)` built once per run (lets
   `lookup`/`fk` read reference tables of the staging dataset). `Values.getErrors()` -> reject reasons
   (naming the target field, as today). `Values.get("./field")` -> the xs string, `null`/empty = no value.
3. `PipelineCompat.checkConstraints(field, xs, maxLength, mandatory)` on the STRING (length in
   characters; mandatory = non-empty) - unchanged semantics, ahead of the write.
4. `QualityRuleCheck.passes(rule, value, lookups)` on the xs string (it already takes an `Object`; READ
   it and keep the numeric/date rules working on the string form, or parse there - never a second typing).
5. Foreign key of an ORDINARY attribute (`foreignKeyField`, `targetEntityName`): unchanged -
   `parentTable.computePrimaryKey(new Object[]{sourceSystem, keyText})`, orphan -> reject.
6. **Child feeds**: for each child feed of this feed, for each element (the row document itself when
   `collectionPath` is empty, else each element of the array/object at that path, exactly
   `MappingEngine.applyChild`'s own rule): a child document = the element, PLUS the parent document
   reachable as `parent` (put the parent `ObjectNode` under key `_parent` so a child pipeline can
   `concat(_parent.ID)`); `sourceId` = `sourceIdPipeline` applied on the child document
   (`Transformations.apply`), or the default of sec 2.1; `parentLinkField` = the PARENT ROW'S staging key
   `ownStagingTable.computePrimaryKey(new Object[]{sourceSystem, parentSourceId}).format()`; then steps
   1-5 on the child, then its own children. The child's `RowPlan` goes to the child entity's batch.
   A child whose `sourceId` computes to empty is a reject of the CHILD only, reason
   "`<entity>`: sourceId is empty (feed `<id>`)"; the parent row is still written.
   **Bug fix, 2026-09-23**: if the child feed ALSO carries an ordinary mapping of the same
   `<attribute>Ref` field (`foreignKeyField` equal to this feed's own `parentLinkField` - a redundant
   rule mapping the link-to-parent attribute from a landing column), step 5's lookup is skipped for
   that one field: the structural write above always wins, since the parent row it would look up is
   still buffered in this same batch, not yet written, on a first load (`TransferPlan.FieldPlan
   .structuralParentLink`; a ROOT feed's own mapping of the same attribute - the two-table-source
   case - is never affected, its `parentLinkField` is always null).
7. Derived fields, null fill, incremental hash, `Xref`, `_status`, batches, `BatchRetry`: unchanged. The
   write uses `ValueContextForUpdate.setValueFromXsString(xs, path)` for every mapped field instead of
   `setValue(typedObject)` (`upsertOne`); the technical fields keep `setValue`.

`_sourceHash`/incremental (`transfer/IncrementalTransfer`): a child row's incoming hash = the parent
landing row's `_rowHash` (same source version), so a changed parent rewrites its children and an
unchanged one skips them - unchanged rule, applied to the child's own staging row.

### 3.4 Rejects and counters

`TransferReject.entity` names the CHILD entity for a child reject; `sourceId` = the child's computed
`sourceId` (or the parent's when empty, with the reason saying so). `StagingEntity.lastLoad*` counted per
target entity as today. The load JSON gains `feeds[]` (`feedId`, `entity`, `landingTable`, `parent`, `read`,
`written`, `rejected`, `unchanged`).

## 4. Proposals

### 4.1 Rule pass (`staging/StagingRules`, `ebx/StagingSuggester`) - unchanged output + root feeds

Still one entity per landing table (the fallback when there is no language model, and the first proposal
a person sees). It now also writes ONE root `TransferFeed` per proposed entity (origin `RULE`, `PROPOSED`,
`sourceIdPipeline` empty) and sets `AttributeMapping.feed` on every mapping it creates. `MappingFollowRule`
gains: a `PROPOSED` feed follows its entity's decision exactly like a mapping follows its attribute.

### 4.2 Language-model pass (`staging/StagingLlmPass`, `ebx/StagingLlmSuggester`) - rewritten as "target model first"

Built target-mapping lot 2, 2026-09-23, on branch `feature/target-mapping-llm`. Four tools replace
`propose_canonical_entity`/`propose_canonical_attribute`: `propose_target_entity`, `propose_feed`, a new
read-only `describe_landing_table`, and `note` (unchanged). What follows is what was actually built, with
every point where the implementation differs from the paragraph above (as originally planned) called out
explicitly - nothing here was silently changed without a reason.

- `propose_target_entity`: one entity of the TARGET model. `parentEntity` + `parentLinkAttribute` declare a
  child entity (`Adresse` -> `Personne`, attribute `personne`), written as a `StagingAttribute` with
  `referenceEntity` = the parent (the ONE thing the old pass could never write) - `Session` computes the
  generated `<attribute>Ref` field name right here, via the new public `StagingModel.foreignKeyFieldName`
  (never a second derivation), and stores it on the in-memory entity as `parentLinkField` for `propose_feed`/
  the writer to reuse verbatim. Refused: an invalid (non-PascalCase) name; a name already used by a
  rule-proposed entity; a name already reviewed by a person; an empty attribute list; an invalid
  (non-camelCase) attribute name or a duplicate attribute name in the same call; an unknown `dataType`; an
  entity a child of itself; an unknown `parentEntity` (must be a target entity already proposed this run, or
  an existing one still pending review); `parentLinkAttribute` missing when `parentEntity` is given, or not
  one of the attributes declared in the SAME call; a business-key attribute on a child (its identity is the
  parent's key + role); an empty rationale. Proposing a name already proposed this run, or an existing one
  still `PROPOSED`, is answered informationally (not an error) with nothing written - unlike the retired
  pass, there is no `propose_target_attribute` companion tool to bolt more attributes onto an existing
  entity across runs, so re-proposing it is a genuine no-op, not an extension point.
- `propose_feed`: one feed. `parentFeed` names an earlier feed of THIS run by the pair `<entity>|<sourceSystem>.<table>`
  it was proposed for; the child's `TransferFeed.parentLinkField` is the entity's own `parentLinkField`,
  computed once by `propose_target_entity` (never asked to the model, never re-derived here). Refused:
  unknown entity (not proposed this run, nor an existing one still pending review - adopted lazily on first
  use, the same "fix F" posture the retired pass used for its own idempotence); unknown landing table; in
  COMPACT mode (see below), a table not yet read with `describe_landing_table`; an unknown parent feed
  reference; a `parentFeed` given for an entity with no `parentEntity` declared; a parent feed whose own
  entity is not this entity's declared parent; **a child feed naming a DIFFERENT landing table than its
  parent feed** - added during implementation, not in the original bullet list: landing rows are flat today
  (sec 2.1), so a child feed's element is always found WITHIN the parent row's own document (sec 3.3 step 6)
  - there is no mechanism yet for a child feed to read a second, independent table; that case (a genuine
  two-table source) is a ROOT feed with an ordinary reference-attribute mapping instead, exactly like
  `Variante` fed from its own `VARIANT` table in the example below; an empty `rules` list; a rule naming an
  attribute the entity does not declare, or a landing column not of `landingTable`; two rules for the same
  attribute in one call. **Bug fix, 2026-09-23 (project Personnes' first real run)**: a CHILD feed's own rule
  naming the entity's `parentLinkAttribute` (the link-to-parent attribute `StagingLoader` always sets from
  the parent row, sec 3.3 step 6) is DROPPED, not refused - a refusal would cost the model a turn over
  something harmless-looking; before this fix it silently made `StagingLoader` treat the structural link as
  an ordinary foreign key and reject every child row as an "unknown parent" on a first load. A `rules` list
  left empty by this drop alone (every rule named the link attribute) IS refused - propose at least one rule
  for a real attribute. A pipeline (rule or `sourceIdPipeline`) naming a transformation outside the given
  catalogue - the 35 registered Connectors step names, minus `fk`, `lookup`, `golden`, `mdmstate`, `runid`,
  `now`, `hash` (seven excluded, not six: the task brief that superseded the paragraph above also excludes
  `lookup`, which resolves against a live EBX table at RUN time exactly like `fk`/`golden`/`mdmstate` do - a
  proposal must never depend on that); a root feed's `sourceIdPipeline` referencing one of the landing
  table's own key columns (the root keeps `_sourceId`); a duplicate feed. **Duplicate detection is simplified
  from the literal (entity, landingTable, parentFeed) triple to the plain (entity, landingTable) pair**: at
  most one feed per pair, whatever its parent. `parentFeed` is itself addressed by that same pair string, so
  a second feed on the exact same pair under a different parent would make every later `parentFeed`
  reference to it ambiguous anyway - the pair-only rule was never exercised as a real limitation by any feed
  tree this lot needed to build, and keeps the addressing scheme unambiguous by construction.
- `describe_landing_table(table)`: read-only, the exact columns (name, type, key flag, tags, fill rate) of
  one landing table - the "expose per-table detail through a local tool instead of inlining everything" idea
  from the task brief, formalised as a fourth tool with its own JSON schema (not explicit in the original
  bullet list, which only named it as a technique). See "Two-stage inventory" below for when it is required.
- Finalisation - `Session.finalizeEntities()` (named to avoid overriding `java.lang.Object.finalize()`, which
  the brief's literal `Session.finalize()` would have silently done): computes, for every created entity,
  `memberEntities` (the rule-proposed entities whose landing table is read by at least one of THIS entity's
  own feeds - structural, from the feed tree, never asked to the model), and the two gap lists
  (`entitiesWithoutFeed()`, `tablesWithoutFeedOrNote()`, the latter checking whether a landing table's own
  key string appears in some `note` text). Never drops anything (unlike the retired pass's own
  `finalizeEntities`, which had to defend against a "padding trick" - a fabricated entity here has no way to
  look genuine without a feed reading a REAL landing table/column, so there is nothing to un-do after the
  fact): every gap is surfaced in `StagingLlmSuggester`'s own output/message for a person to act on.
- Two-stage inventory: `userPrompt` inlines every landing table's full column detail (name, type, key flag,
  tags, fill rate - metadata, never a value) while the rendered text stays under roughly 20 000 estimated
  tokens (`StagingLlmPass.INVENTORY_CHAR_BUDGET`, the same char-per-token estimate and the same lesson -
  `capability.CapabilityLlmPass`'s own "coordinator, 2026-09-21, blocking" incident); past that, it falls
  back to a COMPACT catalogue (table name, column count, key column names only) and `propose_feed` refuses a
  table it has not been `describe_landing_table`'d yet. `Session.isCompact`/`StagingLlmPass.isCompact` decide
  this identically from the SAME `ProjectInfo`, so the prompt and the session's own gate never disagree.
- Prompt: the transformation catalogue (fixed, one line per allowed step) lives in the SYSTEM prompt, not
  repeated per project, since it never changes; the two illustrative examples the brief asked for
  (persons/addresses, products/variants - never one domain alone) are also fixed system-prompt text, spelling
  out BOTH shapes for each domain where useful (the flat-table child feed AND the two-table root-feed
  alternative for the SAME target entities, in the persons/addresses example). The per-project user prompt
  carries the landing inventory, the relations between landing tables, and "what a rule pass would propose"
  (the rule-proposed entities, named for context and for the name-collision refusal - never their attributes,
  which the model does not need: it designs the target attributes itself). Grouped calls per turn, no prose
  answer (`ProseAnswerRecovery`, `AgentLoop`), three identical refusals in a row end the run
  (`Session.refuse`, unchanged mechanism from the retired pass).
- Writing (`StagingLlmSuggester`): `StagingEntity` (origin `LLM`, `PROPOSED`) with the two technical key
  attributes (`StagingRules.technicalAttributeProposals`, never re-derived) and every proposed
  `StagingAttribute`, `referenceEntity` set on the parent-link attribute; `TransferFeed` rows (`parentFeed`/
  `parentLinkField`/`sourceIdPipeline` set only on a child feed); `AttributeMapping` rows with `feed` set
  always, and `pipeline` set (transformation `NONE`) when the model gave one, else `transformation`/
  `argument` from `StagingRules.typeDrivenMapping` applied to the LANDING COLUMN's inferred type (never the
  attribute's own declared type - the rule pass's own convention). `memberEntities` on a BRAND NEW entity is
  written outright; on an entity ADOPTED from an earlier still-`PROPOSED` run (fed further this run, but not
  re-declared), it is MERGED (read-modify-write, union) into whatever the row already carries, since a new
  feed this run may cover a landing table - and therefore a rule entity - the earlier run never saw; either
  way every member is superseded (`supersedeMembers`, unchanged). Nothing writes on refusal; re-proposing an
  existing `PROPOSED` entity is idempotent (see above); `propose_feed` against it is a lazy, first-use
  adoption, so a re-run only ever adds what is missing (new feeds, or a brand new entity) - never a duplicate.

#### Prompt contract (tool schemas)

```
propose_target_entity(
  name: string (PascalCase),
  label: string,
  attributes: [{ name: string (camelCase), label: string,
                 dataType: STRING|INTEGER|DECIMAL|BOOLEAN|DATE|TIMESTAMP,
                 maxLength?: integer, mandatory?: boolean, businessKey?: boolean }],
  parentEntity?: string,
  parentLinkAttribute?: string,   // required when parentEntity is given; must name one of `attributes`
  confidence: integer (0-100),
  rationale: string
)

propose_feed(
  entity: string,
  landingTable: string,           // "<sourceSystem>.<table>", exactly as given in the catalogue
  parentFeed?: string,            // "<entity>|<sourceSystem>.<table>" of an earlier feed THIS run
  rules: [{ attribute: string, landingColumn: string, pipeline?: string }],
  sourceIdPipeline?: string,      // child feed only
  confidence: integer (0-100),
  rationale: string
)

propose_child_keys(             // lot M8b, sec 19
  entity: string,                 // the PARENT entity that carries the attribute
  name: string (camelCase),
  label?: string,
  childEntity: string,            // a child of `entity` (proposed with parentEntity = entity)
  fields: [string],               // attributes of the child that make ONE key, most selective first
  confidence: integer (0-100),
  rationale: string
)

describe_landing_table(
  table: string                   // "<sourceSystem>.<table>"
)

note(
  text: string
)
```

### 4.3 Review (`ebx/ProposalReviews`, `ebx/ui/ProposalReviewUserService`, wizard step 4)

New nature `FEED`: label "`<entity>` <- `<source> <landing table>`" (+ " as child of `<parent entity>`"),
detail = its rules as "`<column>` -> `<attribute>` (`pipeline`)". Accept/reject a feed cascades to its
`PROPOSED` mappings (`MappingFollowRule`, extended). The wizard's step-4 "staging" block shows, per target
entity, its feeds as a tree (root feed, child feeds indented), each with its mappings; "Load the staging"
is gated on: every accepted entity has at least one accepted feed, and a child feed's parent feed is
accepted. The attribute-centric mapping list of today stays (a mapping is still one row).

**Staging-focus lot (2026-09-23, branch `feature/wizard-staging-focus`, the product owner on project `A`: "il faut
simplifier l'ecran ... les entites superseded ... ne doivent pas apparaitre"):** a `StagingEntity` the
language-model pass superseded (`StagingLlmSuggester.supersedeMembers`, sec 4.2) is no longer rendered on
the wizard's step-4 "staging" block AT ALL - not even as the grayed-out card it used to be
(`WizardPage#renderStep4` skips it before calling `renderStagingEntity`; the dead `SUPERSEDED` branch and
the `mdmf-entity-superseded` CSS class were removed with it). It stays fully in `WizardSnapshot` and is
still shown, decidable, on the Proposal review screen (`ebx/ProposalReviews`/`ReviewPage`, a different
`ReviewLine`-based reader this lot never touched) for anyone who wants to bring it back. The "N
proposal(s) waiting for review" header (`WizardData#readProposedCounts`) and the page-wide/entity-scoped
bulk accept/reject sweeps (`ProjectWizardUserService#bulkStaging`) both skip a `SUPERSEDED` entity now too
- a hidden card's own leftover `PROPOSED` attributes/mappings/proposal lines are neither counted nor
decidable through a bulk action any more. An ACCEPTED/REJECTED member of a canonical entity (a person
already decided on it, so it is not `SUPERSEDED`) keeps its ordinary, visible card and its "member of
`<canonical>`" hint - only `SUPERSEDED` itself is hidden, never a decided member.
<br>Same lot, a second product report (a click on "Affiner avec le modele de langage" 15 seconds after
"Proposer le staging" had already chained it, sec 4.2's "staging-llm-chained lot", created a duplicate
canonical entity - `StagingLlmSuggester` itself still is not idempotent, `feature/staging-llm-rerun`'s job,
not this one): `WizardRules.actionGates.suggestStagingLlm` now also refuses a re-run while at least one
`StagingEntity` of `origin LLM` is still `PROPOSED` (`wizard.gate.llmProposalsPending`), re-opening once
every one of them is decided; `suggestStaging`'s own chaining already re-reads this same gate after the
rule pass, so it inherits the refusal for free, and `ProjectWizardUserService.suggestStagingLlm` (the
`STAGING_SUGGEST_LLM` action handler) recomputes the full gate server-side rather than trusting the
disabled button, the same defence-in-depth `applyCapabilityDirectly` already applies to its own gate.

## 5. Lots, files, order

| lot | repo / branch | what | files |
|---|---|---|---|
| 0 | `ebx-connectors`, `feature/mapping-jar` (worktree `~/Dev/wt/ebx-connectors-mapping-jar`) | `ebx-connectors/build-mapping.sh` -> `dist/on-ebx-mapping.jar` (`--release 8`, classpath = server `lib/*` for ebx-lib + Jackson; the closure of sec 1 and NOTHING else - the build fails on any other dependency, which is the point, like `build-agent.sh`). Decouple `runid`: `Transformations` reads a `RunIds` holder (new tiny class in `core`, a `ThreadLocal`/static supplier `ConnectorService` sets at `beginRun`), so the jar never references `ConnectorService`. `boolean`: accept `o`/`oui`/`non` too if it does not. Unit test with the existing test runner. README line under the agent jar's. | `core/Transformations.java`, `core/RunIds.java` (new), `core/ConnectorService.java` (one call), `build-mapping.sh` (new), `README.md` |
| 1 | `ebx-mdm-factory`, `feature/target-mapping` | Model (sec 2) + `tools/mapping_jar.sh` (twin of `agent_jar.sh`, called by `install.sh` and `test_local.sh`'s classpath already picks `WEB-INF/lib/*`) + `FactoryPaths` + `transfer/PipelineCompat` (pure, tested against the old outputs) + `TransferPlan` feeds tree (pure, tested) + `StagingLoader` (sec 3) + rule pass root feeds + backfill (sec 6) + `MappingFollowRule` for feeds. The Eclipse `.classpath` of the DEPLOYED copy needs the new jar line (`CLAUDE.md` sec 10): `install.sh` prints a reminder; the coordinator adds the line by hand once. | `tools/build_xsd.py`, `MdmFactory.xsd`, `core/FactoryPaths.java`, `transfer/*`, `ebx/StagingLoader.java`, `ebx/StagingSuggester.java`, `staging/StagingRules.java`, `staging/MappingFollowRule.java`, `tools/mapping_jar.sh`, `tools/install.sh`, `.classpath` note, tests |
| 2 | same repo, branch `feature/target-mapping-llm` off lot 1 - CLOSED, 2026-09-23 | sec 4.2 | `staging/StagingLlmPass.java`, `staging/StagingModel.java` (`foreignKeyFieldName` extracted), `staging/StagingRules.java` (`typeDrivenMapping` extracted), `ebx/StagingLlmSuggester.java`, `StagingLlmPassTest` (no message bundle: the run summary stays a plain English sentence, the existing convention for this suggester) |
| 3 | same repo, branch `feature/target-mapping-review` off lot 1 | sec 4.3 | `ebx/ProposalReviews.java`, `ebx/ProposalReviewUserService.java`, `ebx/ui/ProjectWizardUserService.java`, `wizard/WizardPage.java`, `Messages_*.properties` |
| 4 | later | `MasterModel` promotion rule = every accepted target entity; `memberEntities` retired; lineage (`RecordLineagePage`) shows the feed and the pipeline of each value; delivery (`docs/DELIVERY.md`) ships the jar | |

Lot 0 and lot 1 start together (lot 1's engine part needs the jar: until it exists, lot 1 does the
model/pure parts, then compiles against `~/Dev/wt/ebx-connectors-mapping-jar/ebx-connectors/dist/on-ebx-mapping.jar`
through `ON_EBX_MAPPING_JAR`). Lots 2 and 3 start when lot 1 is merged into `main`.

## 6. Migration of existing rows (lot 1, run by `staging/suggest` like `MemberEntitiesBackfill`)

`FeedBackfill`: for every `AttributeMapping` with an empty `feed`, find or create the root `TransferFeed`
(entity = the mapping's attribute's entity, landingTable = the mapping's column's table, status = the
entity's status when `ACCEPTED`/`APPLIED`, else `PROPOSED`, origin `RULE`, rationale "backfilled from
mapping <id>"), and set `feed`. Idempotent, counted in the answer (`feedsBackfilled`). The loader also
tolerates an empty `feed` (sec 3.1), so a load between the deployment and the first `staging/suggest`
still works.

## 7. Fallback, if lot 1's engine part proves wrong

Run REAL Connectors flows: the Factory writes one inbound mapping per feed (`connectors_mapping_upsert`,
connector = the instance's own EBX connector, operation `search` on the landing table, `children` for
child feeds - `landing/LandingFlow` already generates mapping documents this way for source -> landing)
and runs `connectors_sync`. Costs: one transaction per landing row, rejects only in the sync answer, no
`TransferReject`/`Xref`/quality rules/derived fields unless re-implemented as post-passes. Not chosen.

## 8. Open points (to settle while coding, never silently)

- `QualityRuleCheck.passes` on an xs string for a numeric/date rule (sec 3.3 step 4).
- The exact argument syntax of `date`, `map`, `boolean`, `number` in Connectors (lot 0 documents them in
  the jar's README section; lot 1's `PipelineCompat` follows).
- A `lookup`/`fk` pipeline needs the reference table in the STAGING dataset: our `ReferenceBinding` rows
  point at reference datasets elsewhere (`Geographies`, `Currencies`) - the `TransformationContext` dataset
  must then be the bound dataset, one context per binding. Out of scope for lot 1: `TRANSCODE_REFERENCE`
  stays a cleaning step (sec 3.3 step 1), `lookup` is documented as "reference tables of the staging
  dataset only".
- Whether `memberEntities` should be written by the LLM pass at all once lot 4 lands (sec 2.3).

## 9. Relance de la passe LLM (staging-llm-rerun, branch `feature/staging-llm-rerun`)

**The measured defect (2026-09-23, project `A`, dataset `MdmFactory`).** The staging LLM pass
(`ebx/StagingLlmSuggester` + `staging/StagingLlmPass`) ran twice, fifteen seconds apart (`PipelineStep` 227
then 228). The first run created `Person` (id 15, 14 attributes, root feeds 17 and 19 on landing tables 9
and 10) and the child `Adresse` (id 16, child feeds 18 and 20). On the SECOND run:

- `Person` was correctly adopted by its exact name (`Session.priorByName`), but two root feeds identical to
  the ones already there (21, 22: same entity, same landing tables 9 and 10) were recreated with their 26
  mappings - `propose_feed`'s own duplicate check only ever compared against feeds proposed *in the same
  run*, never against what an earlier run had already written.
- the child was reproposed under the name `Address` (id 17) instead of the existing `Adresse`: seven
  attributes strictly identical (city, country, person, postalCode, sourceId, sourceSystem, street) - name-
  only idempotence has nothing to say about a synonym or a translation of an existing entity.

**What changed, sec 4.2's design still holds - this is an ADDITIVE correction to `Session`/`StagingLlmSuggester`, not a rewrite:**

- `StagingLlmPass.ExistingFeed` (new): one `TransferFeed` row a project already has, read back as `{id,
  landingTableKey, parentFeedId, status}` - `landingTableKey` is the `"<sourceSystem>.<table>"` string the
  session's own catalogue already keys a table with, never a raw EBX id, so it compares directly against a
  `propose_feed` call's `landingTable` argument. `ExistingTargetEntity` gained a `feeds` list of these,
  populated by `StagingLlmSuggester.readExistingTargetEntity` (a `TransferFeed` query per prior entity) -
  `readRuleEntity` and it now share one `landingTableKeyOf` helper instead of each re-deriving the
  `LandingTable`/`ProjectSource` lookup.
- `Session.proposeFeed`: before validating a `parentFeed`/`rules`, it now looks up
  `priorFeedsByKey.get("<entity>|<landingTable>")` (built from every prior entity's `feeds` in the
  constructor). A hit with status `PROPOSED`/`ACCEPTED`/`APPLIED` answers `ok` - "already exists from an
  earlier run, nothing to do", telling the model not to repropose it - with NO `CreatedFeed` added, same
  "nothing to validate, nothing new to do" posture as the entity-level idempotent-adoption branch. A hit
  with status `REJECTED` is refused outright ("a person rejected this feed, do not propose it again"), the
  same posture as a `REJECTED` prior entity.
- A brand-new CHILD feed proposed this run can now attach to a ROOT feed an EARLIER run already created:
  `resolveParentFeedRef` resolves a `parentFeed` argument against `feedsByFeedKey` (this run) first, then
  falls back to `priorFeedsByKey` (never against a `REJECTED` prior feed, treated as unknown - a rejected
  feed is never a valid attachment point for a new child). `CreatedFeed` gained `parentFeedId` (the
  pre-existing parent's own persisted id) alongside the existing `parentFeedKey` (a same-run parent's key) -
  at most one of the two is ever set. `StagingLlmSuggester.write` sets `TransferFeed.PARENT_FEED` from
  whichever is non-null: `feedIdByFeedKey.get(parentFeedKey)` for a same-run parent, `parentFeedId` directly
  (already a real primary key, no lookup) for a prior one.
- `Session.proposeTargetEntity`: a new deterministic guard (`findSemanticDuplicate`) refuses a new entity
  whose business-attribute signature (attribute names lowercased, minus `sourceSystem`/`sourceId` and minus
  whichever attribute plays the parent-link role) exactly matches an existing target entity - this run's own
  earlier proposals, or the project's prior ones still `PROPOSED`/`ACCEPTED`/`APPLIED` - naming the existing
  one in the refusal ("'Address' has exactly the attributes of the existing entity 'Adresse': reuse
  'Adresse' (propose_feed on it), do not propose a synonym or a translation"). A second, independent
  comparison on the NORMALIZED name (lowercase, accents stripped, one trivial trailing 's' plural dropped)
  catches a same-language plural (`Adresse`/`Adresses`) the attribute check might miss on its own. **Neither
  check alone bridges a genuine cross-language spelling difference like `Address`/`Adresse`** (different
  letters, not a plural of one another - normalizing both still gives two different strings): what actually
  caught that exact pair in the live measurement is the attribute-signature comparison, since a true
  synonym/translation ends up describing the same business shape in practice. Both mechanisms are
  intentionally kept, tested independently (`StagingLlmPassTest.rerunRefusesDuplicateEntityByAttributes`/
  `rerunRefusesDuplicateEntityByNormalizedName`).
- `Session.finalizeEntities()`: a table or entity already covered by an EARLIER run's feed is no longer
  reported as a gap just because this run added nothing new to it - `tablesRead` is seeded from every prior
  entity's `feeds` before the main loop, and an adopted entity with no NEW feed this run falls back to its
  prior `ExistingTargetEntity.feeds` before being flagged `entitiesWithoutFeed`. Without this, a genuinely
  "nothing left to propose" re-run would report every landing table as unread, exactly backwards.
- `StagingLlmPass.userPrompt` gained an overload taking the prior entities list, rendering a "TARGET
  ENTITIES ALREADY PROPOSED IN AN EARLIER RUN" section (name, status, attributes, parent, and each feed with
  its landing table/status/root-or-child) AFTER the full-vs-compact inventory decision - it never counts
  against `INVENTORY_CHAR_BUDGET`, so `isCompact`/`Session`'s own `compact` flag stay exactly the
  landing-inventory-only decision they already were. `systemPrompt()` gained one paragraph telling the model
  to reuse those exact names, never a synonym or a translation, never repropose a listed feed, and to
  propose nothing and end with a `note` when they already cover everything.
- `StagingLlmSuggester.suggest`'s closing message now separates "already existed, left alone" from
  "created": `entitiesAlreadyExisted`/`feedsAlreadyExisted` are computed directly from the prior-entities
  list handed to the run (the project's state BEFORE this run - not a tally of idempotent no-ops, which
  would depend on whether the model even bothered calling the tools again for something already covered), so
  a genuinely empty re-run reads "0 target entity(ies) created, 0 feed(s) created, 2 entity(ies) and 4
  feed(s) already existed (left alone), 0 rule entity(ies) superseded (...)" instead of looking like nothing
  happened at all.

## 10. A child always reaches its parent (branch `fix/child-entity-parent-link`, 2026-09-24)

Seen on the live proposals of two projects, before any load: **Personnes** - Adresse fed from the SAP flat table by a
ROOT feed with no rule on `personne` (every SAP address an orphan), and the Salesforce child feed carrying
`sourceIdPipeline = constant('MAIN')` (every child the same `sourceId`, so every address collapsing onto one staging
row); **Vague1** - the Adresse feed a root feed too, its link attribute being `houseNumber`, mapped from a column.

`propose_feed` now refuses, for an entity declared with `parentEntity`:
- a ROOT feed on a table that already feeds the parent (it is the flat case: a CHILD feed of that parent feed);
- a ROOT feed while the parent has no feed at all yet (the parent's feeds come first, so the rule above can see them);
- a ROOT feed with no rule on the link attribute, or with the link mapped from the table's own key column;
- on a CHILD feed, a `sourceIdPipeline` that reads no column (leave it out: `<parent sourceId>#<n>` is the default).

The system prompt says the same and no longer suggests `constant('MAIN')` next to a `sourceIdPipeline`.

Master side, nothing new was needed for the auto-increment key itself: the staging link holds the parent's STAGING key
and `MasterLoader` turns it into the parent's master id through `Xref`. What was missing: once Match and Merge merges
that parent's source record away, a reload wrote the MERGED record's id again, undoing the add-on's own alignment of the
children on the golden. `MasterLoader.ParentKeys` now follows the merge chain to the surviving record
(`master.SurvivingRecord`, public API `MetadataHelper.getState`/`getTargetRecord`), counted `referencesToGolden` in the
load report. Not seen running yet: no child entity of any project has reached master so far.

## 11. Several children per row, value shapes, conversions, left-out tables (branch `feature/staging-pass-multi-child`, 2026-09-24)

Found by the PersVol volume test (`~/Claude/EBX MCP/mdm-volume-test/FINDINGS.md`, items 12-16): three H2 sources, a CRM
row with two addresses, an ERP partner with a text birth date `yyyymmdd` and an address table, a web shop with the full
name in one column.

- **`TransferFeed.variant`** (XSD, `FactoryPaths.TransferFeed.VARIANT`): the role of one of several children of the same
  entity in one landing row (MAILING, OTHER). `propose_feed` takes `variant` (child feed only, upper case, 40 chars) and
  then requires a `constant(<variant>)` rule on a discriminating attribute (addressType). A feed's address becomes
  `<entity>|<sourceSystem>.<table>~<variant>` (`StagingLlmPass.feedKey`), for the cross-run idempotence and for
  `parentFeed`. `StagingLoader` suffixes the child's sourceId with the variant (`<parent sourceId>#MAILING`) when
  `sourceIdPipeline` is empty - stabler than the sibling position, which moves when a sibling is added or rejected.
- **Value shapes in the prompt**: `StagingLlmSuggester.fillShapes` reads up to 200 distinct values of every TEXT landing
  column the `LlmDataPolicy` does not mark sensitive, and gives the model the three most frequent `quality.Shapes`
  (`9(8) (100%)`, `Aa(5) Aa(6) (70%)`) - never a value; nothing at all under policy `NONE`.
- **Conversion guard**: a rule copying a TEXT column into a DATE/TIMESTAMP attribute without `date(`/`isodate`/`constant(`
  is refused, the refusal quoting the column's shapes (the ERP birth date had been mapped with the type-driven TRIM:
  every row would have been refused by EBX).
- **Left-out tables**: `Session.tablesNotYetCovered()` is checked after the run; when a landing table is neither fed nor
  explained, the model gets ONE more turn (`followUpPrompt`, which recalls what the run already proposed), recorded
  as `followUpAgentRun` in the step detail. ECOM.CUSTOMER had been silently skipped.
- The system prompt gains rules (3) several children per row / type column into the same discriminator and (4)
  every source fed or explained, values converted (dates, full names and address lines split with `regex`), a value one
  source splits (house number) kept split for every source.

Not seen running yet (next: a fresh project on the same PersVol sources).

## 12. Empty child rows (branch `fix/empty-children-llm-retry`, 2026-09-25)

Found by the PersVol volume test (project `PersVol2`): a CHILD feed with `collectionPath` empty (sec 3.3 step 6)
runs once per PARENT row whether or not the source actually carries that child. Two `Adresse` child feeds
(variants MAILING and OTHER, each telling itself apart from its sibling with only `addressType <-
constant(...)`) on a CRM contact table with `MAILING_*`/`OTHER_*` columns: 737 contacts had no `OTHER_*` values
at all, yet `StagingLoader` still wrote 737 `Adresse` rows keyed `<parent sourceId>#OTHER`, carrying nothing but
the `addressType` constant, the technical identity fields and the structural link to the parent.

- **`transfer.EmptyChildRule.isEmpty(fields, values)`** (new, pure, unit-tested): true only when the feed has at
  least one NON-`constant(...)` mapped field AND every one of them is null/blank. A feed whose every field is a
  literal is never flagged - there is nothing "missing" to detect for it.
- **`ebx.StagingLoader.processRow`**: checked only for a CHILD row (`pParentKey != null`), right after the reject
  check and BEFORE `DerivedFieldRule.apply` (a derived default must never mask a genuinely empty child). A flagged
  row is neither written nor rejected - not an error, just uninteresting - and its own children are never
  computed (nothing to link them to).
- **Reload / already-written rows**: a flagged child whose staging key ALREADY exists (written by a run before
  this fix, or by a source version that used to carry this child) is left exactly as it is - deleting live
  staging data on a guess is not `processRow`'s call to make. It is only counted separately
  (`Counters.emptyChildrenStaleFound` / `FeedBuffers.emptyChildrenStaleFound`) so a person sees it and can decide;
  a brand-new empty child that is simply never written counts as `emptyChildrenSkipped`. Both totals are folded
  into the per-entity JSON (`entities[].emptyChildrenSkipped/emptyChildrenStaleFound`), the run-level JSON
  (`emptyChildrenSkipped`/`emptyChildrenStaleFound`), the `PipelineStep` end message, and each entity's
  `StagingEntity.lastLoadMessage`.
- Not seen running against real EBX yet (unit-tested only, `tools/test_local.sh`) - the next `PersVol2` load run
  is the live check.

## 13. Every proposed rule tried on real values (branch `feature/staging-rule-probe`, 2026-09-25)

PersVol3 volume test: the model wrote `regex(^99\s(.*))` for a street - it copied the value SHAPE "99" (any number) as a
literal - and emptied the street of 2 436 addresses; nothing had checked a rule against the data. `StagingLlmPass.RuleProbe`
(implemented by `StagingLlmSuggester.LandingRuleProbe`) runs each proposed pipeline, through the same Connectors
`Transformations.apply` `StagingLoader` uses, on the 50 most frequent distinct values of its landing column; a rule that
empties at least half of the filled values is refused with the counts and the input shapes ("a shape is not a literal").
Values stay in Java - only counts and shapes reach the model. A constant or a multi-column `concat` is not probed. The
dominant output shape per source system of each attribute is kept: when one source gives short codes (`AA`) and another
words (`Aa(5)`), the recorded feed's answer carries a harmony note (rule (6)) - a note, never a refusal.

## 14. A rule must not fold distinct values together (branch `fix/probe-lossy-map`, 2026-09-26)

PersVol4 volume test: the ERP country column held 8 ISO codes (FR 1 256, DE 211, IT 191, ES 184, GB 109, PT 100, NL 95,
BE 71) and, after the harmony note of section 13, the model wrote `map(FR=France;DE=Germany;*=Other)`. Nothing was
emptied, so the probe accepted it, and 750 of 2 217 addresses lost their country. Connectors' `map` (case-insensitive,
trimmed key) never keeps an unmatched value: it becomes the `*=` default, or null when there is none - a partial map is
lossy whatever its default.

`ProbeResult` now also carries the distinct non-empty inputs and outputs of the sample, the inputs folded onto a shared
output, and the inputs a `map(...)` does not list (measured by running the pipeline a second time with a sentinel `*=`
appended to each map step, `StagingLlmPass.withMapSentinel`). `StagingLlmPass.lossyRefusal`, run right after the
emptying check with the same refusal counting and circuit breaker, refuses:

- any `map(...)` a sampled value is not listed in - "list EVERY value, or drop map(...) and keep the source value";
- any other fold: at least 3 distinct inputs, at most half as many distinct outputs, and at least a third of the distinct
  inputs landing on an output that 3 or more UNRELATED inputs share. Two inputs are related when they hold the same
  letters and digits once case, accents, spacing, punctuation and order are ignored, so `upper` on `fr`/`FR`, a date
  format merging `2024-01-15` and `15/01/2024`, or a complete map sending `FR` and `France` to one form (two unrelated
  forms of one value) all pass; `DE`, `IT`, `ES`... -> `Other` does not.

The harmony note and rule (6) of the system prompt now ask for a COMPLETE map listing every value, or no harmonisation
at all (both forms kept). Counts only reach the model, never a value.

**2026-09-26, PersVol5 (branch `fix/probe-extraction-and-unread-scope`).** Many full names share a first name, so taking
the first (or last) word out of `ECOM.CUSTOMER.FULL_NAME` looks like a fold to the second rule. An EXTRACTION no longer
counts: `StagingLlmPass.isExtraction(input, output)` is true when the output's words (runs of letters or digits, case and
accents ignored) appear, in order, among the input's words - `Oliver` out of `Oliver Weber`, `2024` out of `15/01/2024`.
Such an input is left out of `foldedInputs` and never makes a bucket shared (`ProbeResult.extractedInputs` counts them);
`Other` is not a word of `IT`, and a first letter `P` is not a word of `Paris`, so the country catch-all and the
first-letter fold stay refused, and the map rule is unchanged. Consequence accepted: a regex keeping only the house
number of an address line is an extraction too - the probe cannot tell it from the intended attribute. The fold refusal
now names every count it measured (distinct in, distinct out, inputs on a shared output, extractions not counted).

What the live run actually refused, though, was not the fold (AgentRun 115): `regex(^\p{L}+)(0)` - the model's idea of a
group number - throws `PatternSyntaxException` on every value (Connectors takes the text between the first `(` and the
LAST `)` as the arguments), the emptying check reported "50 of 50 EMPTY ... a shape is not a literal", and the model,
told about shapes, resent the same syntax until the circuit breaker ended the turn and the ECOM source (799 persons and
their addresses) was left out. `ProbeResult` now carries `failed` and the first `failure` (a pattern syntax error or an
unknown step, text only - any other exception is reported by its class, since e.g. `NumberFormatException` quotes the
value), and `probeRefusal` says the pipeline is INVALID with the step syntax: `regex(pattern,group)`, the group as SECOND
argument, a pattern holding a comma quoted. The system prompt's own street example `regex(^\d+,?\s*(.*)$)` had the same
trap: Connectors splits arguments on unquoted commas, so it returned the house number (`12`), not the street; it is now
`regex('^\d+,?\s*(.*)$')` (checked through Connectors in `StagingLlmPassTest.extractionIsNotAFold`).

## 15. Filled columns no feed reads (branch `fix/staging-unmapped-groups`, 2026-09-26)

PersVol4 volume test: on `CRM.CONTACT` the model proposed the Personne feed and ONE Adresse child feed (variant MAILING)
and forgot the `OTHER_*` group (457 of 1194 rows filled); the left-out-table turn of sec 11 did not fire because the
table IS read, and 457 addresses were silently lost (PersVol3, same sources, had proposed both).

- **`StagingLlmPass.unreadFilledColumns(tablesByKey, readColumnsByTable)`** (pure, unit-tested): for every landing table
  some feed reads, the columns with a fill rate above 0 that no rule reads. Never a key column, a technical column
  (`_` prefix) or an unprofiled one. A `constant(...)` rule reads nothing; a column named inside a pipeline (a concat,
  a child's `sourceIdPipeline`) counts as read. Domain-free: Java never groups `OTHER_*` - it only lists columns.
- **`Session.unreadColumns(explained)`** feeds that function with this run's feeds and the earlier runs' non-rejected
  feeds (`ExistingFeed.readColumns`, read by `StagingLlmSuggester.readColumnsOfFeed` from PROPOSED/accepted
  `AttributeMapping` rows; a feed whose columns are unknown makes its table skipped rather than wrongly reported).
  A column counts as explained when a note names its table and its full field name as whole words
  (`StagingLlmPass.explainedByNote`).
- **Same follow-up turn as sec 11, never a second one**: it fires when a table is left out OR when one unexplained unread
  column is filled in `StagingLlmPass.UNREAD_COLUMN_FOLLOW_UP_FILL_RATE` (5) percent of rows or more. `followUpPrompt`
  lists, per table, the feeds that already read it (address, variant, column -> attribute) and the unread columns with
  type, fill rate and value shapes, and asks for a feed (typically another child variant) or a note naming every column.
- **Outcome**: the step detail carries `followUpAgentRun.unreadColumns`, `unreadFilledColumns` (still unread, not noted)
  and `columnsLeftOutOnPurpose` (noted); the `PipelineStep` message ends with "Filled columns no feed reads: ..." and
  "Left out on purpose: ...".

Unit-tested only (`tools/test_local.sh`); the live check is the next PersVol run.

**2026-09-26, PersVol5 (branch `fix/probe-extraction-and-unread-scope`).** `ERP.BP_ADDRESS.HOUSE_NUMBER` (filled in every
row) was read by no feed of the canonical `Adresse`, yet `followUpAgentRun.unreadColumns` and `unreadFilledColumns` were
both empty. Not a reader problem: rule-pass entities (origin RULE) never reach the session - `StagingLlmSuggester` hands
it origin=LLM entities only, and this run's own feeds are the model's. The cause was the order of the one-click run
(PipelineStep 455): `STRUCTURE_LLM` ran before `PROFILE` ("42 landing columns profiled"), so for the H2 sources every
`LandingColumn.fillRate` was still null (describe_landing_table showed no fill rate either), and `unreadFilledColumns`
never reports an unprofiled column. `StagingLlmSuggester.suggest` now calls `LandingColumnProfiler.profile(..., true)`
first (only the tables with a missing fill rate; never throws for one table), whoever calls it. Guard added all the
same: `Session.unreadColumns` ignores an earlier run's entity that bears a rule entity's name or is REJECTED or
SUPERSEDED. Tested with the PersVol5 shape (`unreadColumnsPersVol5`): HOUSE_NUMBER is asked in the same follow-up turn as
the forgotten `PERSVOLECOM.CUSTOMER`; with null fill rates nothing is reported (the live symptom). Side effect: the model
now sees fill rates in the first turn too.

## 16. One reference to the parent: the link attribute (branch `fix/child-link-single-reference`, 2026-09-26)

PersVol4 volume test: Adresse fed by two CHILD feeds (CRM `CONTACT`, ECOM `CUSTOMER`, `parentLinkField = personneRef`)
and one key-linked ROOT feed (ERP `BP_ADDRESS`). Read on the live rows before changing anything:

- `personne` is not a second attribute next to the link: it IS Adresse's `parentLinkAttribute` (the only
  `StagingAttribute` of the entity with `referenceEntity`, id 764 -> Personne 53). `StagingLlmSuggester` writes
  `referenceEntity` on the link attribute only, so the pass cannot create another reference attribute.
- `personneRef` is the foreign key field `StagingModel` generates for it (`foreignKeyFieldName`, `<attribute>Ref`);
  the child feeds' `parentLinkField` names the same field.
- How `personneRef` got filled for ERP (2217/2217): the root feed maps `BP_ID` (PARSE_DECIMAL) onto `personne`;
  `StagingLoader.processRow` writes the value into `personne` and, because the attribute has a foreign key field and the
  row has no parent key (`pParentKey == null`, so `structuralParentLink` does not win), resolves
  `computePrimaryKey(sourceSystem, value)` in the Personne staging table and writes that key into `personneRef`
  (an unknown parent rejects the row). For a CHILD row the loader writes the parent's staging key into
  `parentLinkField` and leaves `personne` empty. Result, measured in master the same day: `personne` 2217 ERP / 0 CRM /
  0 ECOM, `personneRef` 1194 CRM / 799 ECOM / 2217 ERP - every row.

So a root feed CAN set the link field by key, through the link attribute; nothing changes in the loader. What changes:
- `propose_target_entity` refuses, on a child entity, any other attribute that reads as a second reference to the
  parent (`StagingLlmPass.duplicatesParentLink`: named after the parent entity or the link attribute, optionally
  followed by Id/Ref/Key/Code/Fk/No/Number, e.g. `personneId`, `personne_ref`, `personneRef`). The refusal tells the
  model to map the parent's key onto the link attribute itself.
- The system prompt says the link attribute is the child's ONLY reference to its parent.

The matching side (the model was offered `personne`, not `personneRef`) is `MATCHING.md` sec 31.

## 17. A column written in two orders must not be split by position (branch `fix/staging-name-order`, 2026-09-28)

PersVol7 volume test (FINDINGS.md item 34): `ECOM.CUSTOMER.FULL_NAME` mixes "Oliver Weber" (First Last) with "ROUSSEAU
Stephane" / "MICHEL Celine" / "O'BRIEN Lea" (LAST First, the last name in capitals). The model proposed
`firstName <- regex(^([\p{L}'-]+)\s+[\p{L}'-]+$)` and `lastName <- regex(^[\p{L}'-]+\s+([\p{L}'-]+)$)` - always the
first, then the last token - and the probe let both through: nothing was emptied (sec 13), nothing folded (sec 14),
each output is a word of its input (extraction). Measured live on the landing table before changing anything: 799 rows,
237 (30%) with an all-upper-case first token, 0 with an all-upper-case last one; among the 50 most frequent distinct
values (the probe's sample) 17 (34%). In staging, 228 ECOM pairs crossed (firstName all capitals), 30 emptied, and the
name keys of those persons never met their CRM/ERP rows: 312 true duplicates missed with identical e-mail and address.

**The measurement (`staging/TokenCase`, pure, domain-free).** For the multi-token values of a text column, where the
capitals sit: the share whose FIRST token is all upper case and the last is not (`first-only`), whose LAST token only is
(`last-only`), `both`, `neither`. Two layouts (`twoLayouts()`) = at least half of the values hold two tokens or more AND
at least two of the four layouts each hold `TokenCase.MIN_POPULATION_PERCENT` (15) percent or more of them. It says
nothing about what a token is: the ERP column (`DUPONT JEAN`, 100% `both`) and a `Aaaa Aaaa` column are one layout each.

- **Column level**: `StagingLlmSuggester.fillShapes` measures it on the same 200-distinct-value sample as the shapes,
  weighted by frequency, into `LandingColumnInfo.tokenCase`; the catalogue, `describe_landing_table` and the follow-up
  turn render it after the shapes when most values are multi-token - `token case: first token all upper case in 34% of
  the 50 multi-token values, last token in 0%, both in 0%, neither in 66%`. The top three shapes never showed it: a
  full name's shapes vary with the name lengths.
- **Probe level**: `ProbeResult.of` now carries `inputTokenCase`, `outputTokenCase` (the rule's outputs: `all upper case
  in 34% of the 50 values`), and `positionByLayout` - for every input layout that is a population of the sample, the
  POSITION the rule read its output from (`FIRST` / `LAST` when two thirds or more of that layout's outputs are its
  first or last token, letters and digits compared with case and accents ignored, else `MIXED`), with the counts in
  `positionDetail` (`first-only: FIRST token in 17 of 17, neither: FIRST token in 33 of 33`).

**The refusal (`StagingLlmPass.orderRefusal`, run after the emptying and fold checks, same `refuse` path and circuit
breaker).** Refused when the inputs follow two layouts and every population had its output read from the SAME position
(`FIRST` everywhere or `LAST` everywhere). An order-aware rule (a different position per layout), a rule whose output is
not a token (`upper`, the whole value), and a one-layout column pass. The text gives every number above and the two
regex forms the Connectors catalogue can express - it has no conditional step (`Transformations.java`: trim, upper,
lower, capitalize, left, right, substring, replace, regex, map, default, constant...), but `regex(pattern[,group])`
runs a Java regex with `find()` and keeps the group's text, so lookaround and alternation carry the condition inside one
step: the token in capitals when the value has one, else the last token,
`regex((?:^(?=[\p{Lu}'-]+\s)|(?<=\s)(?=[\p{L}'-]+$))([\p{L}'-]+))`, and its complement, the token holding a lower-case
letter after an optional leading token in capitals, `regex(^(?:[\p{Lu}'-]+\s+)?([\p{L}'-]*\p{Ll}[\p{L}'-]*))`
(`StagingLlmPass.REGEX_CAPITALS_ELSE_LAST` / `REGEX_LOWER_AFTER_CAPITALS`, checked through the real jar in
`StagingLlmPassTest.orderBlindSplitRefusedOnce`: ROUSSEAU / Stephane, O'BRIEN / Lea, Weber / Oliver, Jean-Pierre kept
whole; no comma in either pattern, so no quoting needed; the apostrophe toggles Connectors' quote state but nothing
follows it). Which attribute the capitals token belongs to stays the model's call - the refusal says "the token written
in capitals", never a domain word. The refusal ends with the fallback: map the column WHOLE onto one attribute with no
regex and say so in a note; the values are then compared whole by the matching (the name keys are per attribute, so an
order-insensitive key was not an option).

**Once, then recorded.** The refusal fires ONCE per landing column (`Session.orderRefused`); a position-only rule sent
again on that column is kept, and the run outcome says so: `tokenOrderDecisions[]` in the step detail and "Columns
written in two case layouts: ..." at the end of the `PipelineStep` message, one entry per rule on a two-layout column -
`order-aware rule`, `kept whole` (no extraction, the fallback), or `order-blind rule kept after one refusal` - each with
the measured layout and positions. Rule (5) of the system prompt names the `token case` figures, the capitals-else-last
form and the refusal. Unit-tested only (`tools/test_local.sh`); the live check is the next PersVol run (a re-proposal
of ECOM on PersVol7 should now either carry the two order-aware regexes or keep FULL_NAME whole).

## 18. Two attributes fed from one column must receive different parts of it (branch `fix/probe-identical-outputs`, 2026-09-28)

PersVol8 volume test (agent run 195, PipelineStep 715, 14:46), two defects seen through the real Connectors engine
(`Transformations.apply`), `refusedToolCalls 0` on both:

- **(a) `ECOM.CUSTOMER.FULL_NAME`**: the model proposed the SAME pipeline - the capitals-else-last form of sec 17,
  `regex((?:^(?=[\p{Lu}'-]+\s)|(?<=\s)(?=[\p{L}'-]+$))([\p{L}'-]+))` - for BOTH `Personne.firstName` and
  `Personne.lastName`; both attributes received the last name (`Oliver Weber` -> `Weber`, `ROUSSEAU Stephane` ->
  `ROUSSEAU`). Each rule is order-aware, so sec 17 never fired; nothing compared two rules with each other.
- **(b) `CRM.CONTACT.MAILING_STREET` / `OTHER_STREET`**: `streetName <- regex(^\d+,?\s*(.*)$)` with an UNQUOTED comma.
  Connectors takes the text between the step's first `(` and its LAST `)`, splits it on every comma outside single
  quotes (a `'` toggles the quoted state, nothing escapes it), compiles the first argument and reads the second as
  the group number - so the pattern was `^\d+` and the rule returned the house number (`12 rue de la Paix` -> `12`),
  the same value as `houseNumber <- regex(^([\p{N}]+))`. The probe accepted it: the output is a part of the input (the
  extraction exemption of sec 14).

**Rule 1 - identical outputs (`StagingLlmPass.identicalOutputs` / `identicalRefusal`, pure).** `ProbeResult.of` now
keeps, in Java only, each filled sampled input -> its output (`outputByInput`, trimmed, null when emptied). Within one
`propose_feed` call, `Session` remembers per landing column the first probed rule (attribute, pipeline, result;
`probedInCall`, cleared at every call) and compares every later probed rule of ANOTHER attribute on the same column with
it: among the inputs both probes hold, the share whose outputs are the same non-empty text (case and accents count:
`Weber` and `WEBER` differ). At `IDENTICAL_OUTPUT_MIN_PERCENT` (80) percent or more, the second rule is refused, ONCE
per (feed, column, attribute) (`identicalRefused`), same `refuse` path and circuit breaker as the other refusals:
"on the real values of 'FULL_NAME' (never shown to you), pipeline X for attribute 'lastName' gives the same output as
the rule already proposed for attribute 'firstName' (Y) in 50 of 50 values (100%) - two attributes fed from one column
must receive DIFFERENT parts of it, with two DIFFERENT rules. Give 'lastName' a rule that extracts the other part (for a
value written in two case layouts: the token in capitals, else the last token: <form A> ; the token holding a lower-case
letter after an optional leading token in capitals: <form B> - one form per attribute, never the same for both), or map
the column whole onto ONE attribute and drop the rule of the other." Sent again unchanged, the rule is kept and the
decision recorded (`identicalOutputDecisions[]` in the step detail, "Two attributes given one output from one column:
..." at the end of the `PipelineStep` message). Only PROBED rules take part: a column mapped whole (no pipeline), a
constant or a multi-column concat is never compared. Two rules emptying everything are not "identical" (`probeRefusal`
has them first).

**Rule 2 - unquoted comma, BEFORE probing (`StagingLlmPass.unquotedCommaRefusal`, pure).** Connectors' own split is
mirrored, not reinterpreted: steps cut on `|` outside parentheses, a `regex` step's arguments between its first `(` and
last `)`, commas counted outside single quotes with the same toggle. `regex(pattern)` and `regex(pattern,n)` with an
integer `n` pass; any other comma - `{1,3}`, `[a,b]`, an alternative written with a comma - is refused by its 1-based
position in the pipeline: "the comma at position 11 splits the arguments of regex(): Connectors reads '^\d+' as the
pattern and what follows the comma as the group number, so pipeline '...' does not mean what it says. Quote the whole
pattern, regex('...'), or write the alternative without a comma." Checked on every rule pipeline (after the catalogue
and ASCII-class checks, before `probeRule`) and on `sourceIdPipeline`. Consequence of the mirror: a comma sitting
between two apostrophes of the pattern (`[\p{L}'-]+),\s+([\p{L}'-]`) is quoted for Connectors and passes here too.

**Prompt.** Rule (5) now names both forms by what they extract - "the token in capitals, else the last token" (form
A) and "the token holding a lower-case letter after an optional leading token in capitals" (form B) - "one form for one
attribute, the other form for the other", and states that two attributes fed from one column must use two DIFFERENT
rules extracting different parts (the same pipeline for both is refused). The sec 17 refusal labels the forms A / B
the same way and says one attribute takes A, the other B.

Checked through the real `on-ebx-mapping.jar` in `StagingLlmPassTest.identicalOutputsRefusedOnce` (same regex twice:
50 of 50, refused; form B against form A: 0 of 50, accepted; 8 of 10 refused, 7 of 10 not; the street pair unquoted 12
of 12) and `unquotedCommaRefusedBeforeProbe` (the probe is called once for the house-number rule and never for the
unquoted street rule; the quoted form is probed and recorded). Unit-tested only; the live check is the next PersVol
run.

## 19. A derived child-key attribute, and a pipeline that must not break itself (lot M8b, branch `feature/staging-child-keys-proposal`, 2026-10-04)

Both come from the 1-million-row volume projects Vol100k and Vol1m (`mdm-volume-test/vol1m/FINDINGS.md`, `docs/MATCHING.md` sec 50).

### 19.1 `propose_child_keys` - the model proposes the `CHILD_KEYS` attribute

Until this lot nothing proposed the `CHILD_KEYS` attribute (`docs/MATCHING.md` sec 50): on Vol1m `Personne.adresseKeys` was created by hand, and with it the matching recall went from 90.8 %
to 99.15 % at a precision of 99.84 %. `StagingLlmPass.Session` has a fifth tool, `propose_child_keys` (schema above). The model names the PARENT entity, the attribute, the CHILD entity and the ordered child
attributes that make one key; the wording of the contract (system prompt, tool list) says what it is for (finding two parent records that share a child record, the same address, with an exact comparison on
the parent table), when to propose it (a child entity that carries identifying data of its parent), which fields make a key (the ones that identify the child record, most selective first, never the link to the
parent), that it is a technical matching key (never an identifier of the parent, never a business attribute, nothing is mapped into it), and the measured gain in one sentence.

**Validation** (`staging.ChildKeysProposalRule`, pure, over `StagingModel.EntityInfo` built from the entities of the run and the project's earlier ones that a person did not reject; the same questions
`ebx.ChildKeysPass` asks at load time, with the wording of its refusals): the attribute name is a valid name and is not already on the parent; the child entity exists and is not the parent
("propose it first"); the argument parses with `ChildKeysDerivation.parse` (fields non-empty, valid names, no field twice); the child has exactly ONE attribute that refers to the parent; every field is an
attribute of the child. Three refusals that only make sense for a proposal: a field that is a technical identity attribute (`sourceSystem`/`sourceId`: identifies a row of the source, not the child record), a field that
is a reference to another entity (the link to the parent is the same for every child of one parent), a name taken on the parent. One key attribute per (parent, child) pair per run; a pair an earlier run already
covers (a `CHILD_KEYS` attribute that is not `REJECTED`, `ExistingTargetEntity.childKeysChildren`) is answered informationally. The circuit breaker counts a refusal for the same entity, name, child AND fields.
`ebx.ChildKeysPass.resolve` holds the load-time twin of the same checks over the same `EntityInfo` type; this lot did not touch it (another lot is changing that class) - both can delegate to one implementation.

**Written** by `StagingLlmSuggester.write`, after the entities and before the feeds, as a `StagingAttribute` of the parent (an entity of this run, or an earlier one found by name - it is not adopted): `derivation = CHILD_KEYS`,
`derivationArgument = {"child":"<child>","fields":[...]}` (no `encoding`: the default, opaque tokens, applies), `dataType STRING`, `maxLength 2000`, not mandatory, not a key, origin `LLM`, status `PROPOSED`, the model's
confidence and rationale. The `staging/suggest/llm` answer has `childKeysCreated` and the step message says how many.

**Review and matrix** need nothing new: the row is an ordinary `ATTRIBUTE` line (accept/reject = its status, counted in "still to decide" like any attribute), worded "Keys of the Adresse records" (lot M3), shown in the
matrix as one "computed at load" cell; `StagingModel` builds its field once `ACCEPTED`, `NullFillRule` and `DerivedFieldRule` leave it alone, `ChildKeysPass` fills it. What accepting it takes, in order: accept it (and the child
attributes it reads: a key part that is not accepted makes the load SKIP the attribute with the reason in the step detail), regenerate the staging (an additive field), load the staging (the post-pass runs the first time
whatever the load wrote: `derivationLastRunAt` is empty), regenerate the master (additive), load the master (it re-reads every staging row), regenerate the matching plan and configure. Nothing proposes the plan's use
of the attribute (`clusteringFields`, `relationClusteringWeights`): that is `MatchPlanLlmPass`'s lot.

### 19.2 A case change in front of a regex that reads case

The model proposed, for a source whose full name sits in one column, `firstName <- regex(^(?:[\p{Lu}'-]+\s+)?([\p{L}'-]*\p{Ll}[\p{L}'-]*))`, `lastName <- regex((?:^(?=[\p{Lu}'-]+\s)|(?<=\s)(?=[\p{L}'-]+$))([\p{L}'-]+))`
and, from the quality pass, an `UPPER` cleaning step on the `lastName` mapping. The step runs on the raw text BEFORE the mapping's pipeline (sec 3.3 step 1): every token became a token in capitals and the
pattern returned the FIRST token as the last name on 153,937 rows. The step comes from `QualityLlmPass` (`propose_mapping_step`), not from the staging pass, which only writes pipelines; both ends are guarded by
`quality.CaseOrderRule` (pure, structural: it reads text and kinds, never a value) - see `docs/NORMALISATION.md`, "Case changes and the order of steps".

### 19.3 Not done, on purpose

`propose_feed`'s probe (sec 13) already runs every proposed pipeline on real values with the Connectors engine and refuses a rule that empties at least half of the filled sample; nothing was added there. The measurement that
needed new code is the one a cleaning step needs (`quality.RegexSampleCheck`, `docs/NORMALISATION.md`): the staging pass proposes mappings with no steps, so there is nothing to apply in front of its regex.


## 20. A column whose parts may come in either order, with nothing to tell them apart (branch `feat/name-order-unknown`, 2026-10-12)

Vol100kN, four sources (measured live 2026-10-12): `LOYALTY.MEMBER.FULL_NAME` holds the whole name first-name-first in
half of the rows and last-name-first in the other half, with NO marker - 92% of the values are one casing layout
("Aaaa Aaaa"), 8% are all upper case. For the ECOM source (`FULL_NAME` with the surname in capitals in 30% of the rows) the
model had proposed the casing-based split of sec 17; it reused it for LOYALTY (`firstName = regex(<capitals else last>)`,
`lastName = regex(<lower-case token after optional capitals>)`, confidence 74), which silently takes the LAST token as the
first name whenever nothing is upper-cased - wrong on half the LOYALTY rows. Sec 17's check said nothing: its
`tokenOrderDecisions` only cover a column "written in two case layouts" (`TokenCase.twoLayouts`), and a column with ONE
layout and an unknowable order is not that. The mapping went through "accept all" unnoticed.

The order is genuinely not inferable from casing there. The two honest ways out are outside what Java can measure: a
cross-source check (the same person exists in another source with separate first and last fields and a shared e-mail or
phone: compare the split against it), or keeping the raw value and comparing with an order-independent key (the
`Fasttrack.lib.names` functions `sortedKey` / `sortedKeyOf`, which the capability pass already proposes as a derivation).
This lot makes the Factory SURFACE the case, in three places, and decides nothing.

**The measurement (`TokenCase.orderUnknown`, pure, domain-free).** On the sample the Factory already reads (200 distinct
values, weighted by frequency, `StagingLlmSuggester.fillShapes`): at least 5 multi-token values and more than half of the
values multi-token; at least 80% of the multi-token values made of words only (letters, apostrophes, hyphens, dots - a
street with a number or a code is not); at most 4 words per value on average (free text is not); AND the casing does not
tell the parts apart (`casingSeparatesParts` false: neither "only the first token all upper case" nor "only the last token
all upper case" reaches 15% of the multi-token values). One layout everywhere, or only "both" / "neither", is order-unknown;
the ECOM column (34% with a leading token in capitals) is not. Nothing reads a column name.

**1. Evidence to the model.** `StagingLlmPass.tokenCaseText` (used by the column line of the inventory, the follow-up turn
and `describe_landing_table`) appends, after `token case: ...`, `, ORDER UNKNOWN: ` and `TokenCase.renderOrderEvidence()`:

    multi-token values in one casing layout, the order of the parts cannot be inferred from casing (neither the first nor the
    last token all upper case in 92%, the first and the last token all upper case in 8%, mean 2.0 words per value): a rule
    that reads a part by its position or by its case is a guess for the rows written in the other order

Rule (5) of the system prompt now says what to do when a description says "ORDER UNKNOWN": EITHER map the whole value onto
ONE full-value attribute of the target entity (propose it if the entity lacks it) with no regex, leave the split to a later
step and compare with an order-independent key, OR split with the assumption stated in the rationale and a LOW confidence
(at most 40); and rule (4) no longer promises a split "through regex(...)" for every full-name column, only "when its
casing tells the parts apart". A target entity that already has separate first/last attributes fed by other sources may
still be split - the evidence only makes the uncertainty explicit.

**2. Check after the fact (`staging/PartOrder`, pure).** `propose_feed` already probes every rule on the real values. When
the column is `orderUnknown` and the rule's output is a strict, contiguous run of the input's tokens (case, accents and
punctuation ignored) in at least half of the multi-token sampled values (`PartOrder.splitsUnknownOrder`), the rule is NOT
refused (the model may split with a stated assumption) but recorded in `tokenOrderDecisions[]` - the same list the sec 17
decisions go to, hence the same step detail and the `PipelineStep` message, whose label now reads "Columns whose parts may
be written in either order (two case layouts, or casing that does not tell them apart): ...":

    Personne.lastName <- LOYALTY.MEMBER.FULL_NAME regex(...): order unknown (neither the first nor the last token all upper
    case in 92%, the first and the last token all upper case in 8%; the rule takes a part of the value in 48 of 48
    multi-token values, so the rows written in the other order are likely wrong - keep the full value and compare it with an
    order-independent key)

A column mapped whole records nothing. A column that is both two-layout and order-unknown (half "both", half "neither") is
refused once by sec 17 first, then recorded as order unknown.

**3. Review (Mapping matrix "Needs your eye").** `AttentionRules.ORDER_UNKNOWN_SPLIT`, decided by the pure
`AttentionRules.orderUnknownSplit(pipeline, orderUnknown, parts, multiTokenInputs)`: the pipeline holds a `regex(...)` step,
the sampled source values (the matrix's own 20-row sample, `MappingMatrixReader.sample`) are order-unknown, and the rule
turned at least half of the multi-token ones into a strict part. The cell gets the amber "attn" tone and the reason
`mappingMatrix.attention.orderUnknown` - "this rule splits a column whose order of parts cannot be inferred (N of M sampled
values): keep the full value and compare it with an order-independent key" - which is also the suggested fix. A flagged
cell is skipped by "Accept the N unflagged proposals" exactly like the other flags (`MatrixCompute.attributeFlagged`; tested
with `ORDER_UNKNOWN_SPLIT`). It is evaluated only when no other flag already sits on the cell.

**Deliberately left to the model and the person.** Which attribute a part belongs to; whether to keep the full value or split
with an assumption; the cross-source check against another source with separate fields and a shared e-mail or phone (a
possible later lot: it needs a join across sources the staging pass does not have at that point); building the full-value
attribute and the order-independent key (the capability pass already offers `nameMatchKey`). A regex over a multi-word
alphabetic column that is not a name (a two-word city) is flagged the same way - Java cannot tell, and a person dismisses it
in one click. Unit-tested only (`StagingLlmPassTest.orderUnknownIsSurfaced`, `AttentionRulesTest`, `MatrixComputeTest`); the
live check is a new `staging/suggest-llm` on Vol100kN.

## 21. A source that carries an identity but no identifier of it: facts and a numeric feedback (lot S10, branch `feat/staging-transactional-source-facts`, 2026-10-19)

**Measured** (B2C end-to-end, `tools/e2e/b2c_master.sh`): in 3 runs of 5 the model fed the person entity from the three sources
(534 rows); in 2 runs of 5 it kept STORES (234 ticket rows, key `NUM_TICKET`, no customer id, but NOM / PRENOM / TELEPHONE / EMAIL on
every row) out of it - 300 rows, 103 goldens instead of 165: the store-only customers never reach the master. Rule (10) already said
to feed an entity from such a table; the model had no figure showing what leaving it out costs.

**What the Factory now gives, never a decision** (the module's rule: facts, explained rules, a numeric feedback - no Java heuristic says
"this table feeds the person entity"):

1. *Inventory* (`StagingLlmPass.renderFull`/`renderCompact`/`describe_landing_table`): per table the rows of the LANDING table
   (`, 234 rows`) and the key as the Factory already knows it - names, who set it (`LandingColumn.sourceKeyOrigin`: declared by the
   source / guessed from the column names / proposed by the key study / set by a person / deduced), distinct values of a single key
   column over the rows, and - only on a table that has identity-tagged columns - `carries no identity tag`. Example:
   `- SHOP.Magasins_Ventes (9 columns, 234 rows, key: NUM_TICKET (set by a person; 234 distinct values over 234 rows; carries no identity tag))`.
2. *Identity columns*: for a column carrying a catalog identity tag (`IdentityFacts.Kind`: `EMAIL`, `PHONE`, `PERSON_NAME`,
   `PERSON_IDENTIFIER`, `NATIONAL_ID`, `NIR`, `PASSPORT_NUMBER`, `DRIVING_LICENCE`, `PERSONAL_TAX_ID`, `VAT_NUMBER`, `LEI`,
   `COMPANY_REGISTRATION` - the codes of `ebx-data-catalog`'s `catalog-config.json`, no name is read), `, identity: 89 distinct values over
   131 filled rows, repeated on 75 rows`. Values are compared in a normal form (e-mail lower case; phone = its last 9 digits, so 06.12.. and
   +33 6 12.. are one value). Measured by `ebx.IdentityReader`: ONE streaming pass per table that has an identity column (CLAUDE.md sec 15), each
   value folded to a 128-bit digest, nothing but counts kept or sent - which the LLM data policy allows at every level and for sensitive columns
   ("counts always go"). Bound: the volume guard of the fill rates (`quality.ScanGuard`: past `mdm.factory.profile.maxRowsFullScan` rows only the first
   `sampleRows`; the text then says `measured on a sample of N rows`); a table without identity column costs one cached row count; the read is cached
   per table state (`ColumnReads.IDENTITY`).
3. *System prompt*, right after rule (10): "A landing table whose rows each carry the identity attributes of an entity but hold no identifier of that
   entity is still a SOURCE of that entity: every such table left out of the entity's feeds leaves the records seen only in it out of the master."
4. *Feedback in the existing follow-up turn* (`followUpPrompt`, no second loop): for each table with identity columns that no entity holding the
   identity of the OTHER tables reads (`IdentityCoverage.gaps`: the holder is derived from the proposal - the entities fed by the identity columns of
   other tables - never from a word list; no holder, no gap): `SHOP.Magasins_Ventes: 234 rows carry identity attributes (tags ...; columns ...) but feed no
   entity that holds them; among them 10 distinct e-mails, 15 distinct phones are seen in no other source - they will be absent from the master. Feed them
   (a root feed of Client on SHOP.Magasins_Ventes, one row = one NUM_TICKET) or say in a note why not.` The "seen in no other source" figure is a set
   difference of digests across the tables of the project (`IdentityFacts.crossTable`), for the comparable kinds only (e-mail, phone, official identifier;
   a name or a source-internal number is not compared); not computed - and the text says so - when this table or another one carrying the kind was read on
   a sample.
5. *Open point at the review*: if the table still feeds nothing after the follow-up (the model noted it out, or did not answer), `ProposalChecks`
   `IDENTITY_SOURCE_UNFED` puts the same counts, in the viewer's language (`stagingProposal.check.identityUnfed*`, EN/FR), first in the rationale of the
   entity those tables feed - followed by the model's note about the table when it gave one - and caps that entity's confidence at 40, as lot M15 does,
   so the review step shows it and a bulk accept does not sweep it. Nothing is fed, rejected or dropped by the Factory; the proposal stands.

**Not done, on purpose.** Names are not compared across tables (a first and a last name in two columns, or a full name in one, do not give a comparable
value without a decision on how to combine them). No column is treated as an identity without a catalog tag: a landing built from files has no tags until
the catalog tags them (the live B2C landing columns of 2026-10-18 carry none), and the figures then simply do not appear. Unit-tested
(`IdentityFactsTest`, `StagingLlmPassTest`); the live check is a new `staging/suggest-llm` on a B2C project whose landing columns are tagged.

### 21.1 Where the tags come from, and sampling at onboarding (lot S10b, 2026-10-19)

**Measured** (ebx-data-catalog `main`, read with `git show`): a column gets a tag only when the catalogue's `Catalog sample: <SOURCE>` flow (an inbound `sample` mapping that
writes one `ColumnSample` per text column) has run: `ColumnSampleTrigger` calls `Tagging.classify`, which proposes tags from a regex on the sampled values (`EMAIL`, threshold 80,
auto-applied; `PHONE`, threshold 90, pending), from the `Tag` table's name hints (`PERSON_NAME`, `PERSON_IDENTIFIER`, `POSTAL_ADDRESS`...: origin `NAME`, confidence 60, pending), a
dictionary and a model; each is a `TagSuggestion` (`column`, `tag`, `origin`, `confidence`, `status` APPLIED / PENDING / ACCEPTED / REJECTED), and an APPLIED one is written to
`Column.tags`. The flow is declared by the catalogue's `SourceTrigger` when a source is registered, **only for a connection whose type offers a `sample` operation**. The Factory's
onboarding scan only ran `Catalog scan: <SOURCE>`, so a Factory source had no tag; and the `sample-file` connection (the B2C files) offers `read`, `list` and `catalog` only
(`SampleFileConnector`): for it the catalogue has no sample flow at all and nothing is ever tagged until ebx-connectors gives that connection a `sample` operation.

1. *Sampling after the scan* (`SourceOnboarder.runScan`, hence `OnboardStepRun.SCAN_HANDLER`, `SourceRescanRun` and `onboardAll`): after a scan that succeeded, `sampleForClassification` runs
   the connection's sample mapping the way the scan mapping is run (`connectors_mappings_list` for the `sample` / inbound / `/root/ColumnSample` mapping, then `connectors_sync` on its id,
   `max` 20000 columns). The Factory does not create the flow: the catalogue declares it, and a type that cannot run it gets none; the answer is one sentence appended to the scan message,
   `sampling for the catalogue's classification: 9 columns sampled` or `...: skipped: <reason>` (no sample operation / no `Catalog sample` flow / refused / failed). It never fails the scan.
   The sampled values stay in Connectors and the catalogue (blanked once classified); none goes to the language model.
2. *Suggestions as facts* (`CatalogReader.columnTagFacts`, `StagingLlmSuggester.attachCatalogTags`): at each proposal the applied tags and the `TagSuggestion` rows (tag, origin, confidence,
   status - never the free-text evidence) of the catalogue columns behind the landing columns are read live, as the person who runs the pass, and carried into `LandingColumnInfo`
   (`tags` stays the applied tags, `suggestions` the rest). The inventory renders them: `EMAIL (type STRING, tags: EMAIL (regex matched 100% of sampled values, applied), ...)`,
   `TELEPHONE (type STRING, suggested PHONE (regex matched 96% of sampled values, pending review), ...)`, `NOM (type STRING, suggested PERSON_NAME (name hint of the catalogue, pending review), ...)`.
3. *Identity-bearing* (`IdentityFacts.kindOf`): a column whose identity tag is applied OR suggested and not rejected. The vocabulary of identity tags is still the list of S10 (the
   catalogue's `Tag` codes `EMAIL`, `PHONE`, `PERSON_NAME`, `PERSON_IDENTIFIER`, `NATIONAL_ID`, `NIR`, `PASSPORT_NUMBER`, `DRIVING_LICENCE`, `PERSONAL_TAX_ID`, `VAT_NUMBER`, `LEI`,
   `COMPANY_REGISTRATION`): the catalogue's `Tag` has a `family` (`PERSONAL_DATA`) but it groups sensitive attributes that are not identities (`GENDER`, `HEALTH`...), so the family cannot
   replace the list. The follow-up line and the open point say how firm each tag is (`PHONE (suggested by the catalogue from its sampled values, pending review)`; EN/FR bundle keys
   `stagingProposal.identityBasis.*`).

## 22. Identity measured from value shapes, and the coverage of each entity (lot F2, branch `feat/staging-shape-facts`, 2026-10-19)

**The gap** (lot S10 / S10b, run 6 of 2026-10-19): the identity facts of section 21 exist only for a column the catalogue tagged or suggested. The catalogue's `PERSON_NAME` has
no regex (name hints only), its `PHONE` regex needs two separators (a `0612345678` stays untagged), and a source scanned before the catalogue sampled it has nothing: such a
column got no identity measure at all, so the model could not see what leaving its table out of an entity costs. The rule is unchanged: the Factory gives measured facts, explained
rules and numbers; it decides nothing (no Java heuristic that picks an entity, no per-domain word list).

1. *Shape facts, labelled as measures* (`staging.IdentityShapes`, filled by `StagingLlmSuggester.fillShapes` on the SAME distinct values the shape profiler already reads, 200 per
   text column, most frequent first - no second read). A text column is reported when a shape holds in enough of its filled rows, weighted by row count: **e-mail-shaped** (no white
   space, one `@` with something before it, a dot after it with something on both sides; 80 %, the catalogue's own `EMAIL` threshold), **phone-shaped** (optional `+`, digits and
   space `.` `-` `(` `)`, 8 to 15 digits; a value of digits only counts when it starts with `0`, as `NormalisationProfiler`'s phone measure does, and a value that profiler reads as a
   date never counts; 90 %, the catalogue's `PHONE` threshold) or **two-capitalised-words-shaped** (exactly two words of letters, apostrophes and hyphens, each starting with a
   capital; 90 %). The thresholds are measurement thresholds and the system prompt states them. A sensitive column is measured too (the share says how the values are written,
   never what they are); under data policy `NONE` no shape of any column goes to the model, so none is measured.
2. *Inventory*: `IdentityFacts.kindOf` (what makes a column identity-bearing) is now: the kind of its applied tags, else of a catalogue suggestion not rejected, else the kind of its
   measured shape - unless a steward rejected a suggestion of that very kind. The existing single streaming pass per table (`ebx.IdentityReader`, ScanGuard sample bound, digests
   only, `ColumnReads.IDENTITY` cache) therefore also reads these columns and the line is, on the STORES fixture of `tools/e2e/gen_b2c.py` (`TELEPHONE`, `EMAIL`):
   `- TELEPHONE (type STRING, fill rate 100%, identity-like by shape (phone-shaped in 100% of sampled values; no catalogue tag): 120 distinct values over 234 filled rows, repeated on 195 rows)` and
   `- EMAIL (type STRING, fill rate 55%, identity-like by shape (e-mail-shaped in 100% of sampled values; no catalogue tag): 89 distinct values over 131 filled rows, repeated on 75 rows)`.
   A tagged column's line is unchanged (`, identity: ...`); the "seen in no other source" figure is computed for a shape column exactly as for a tagged one (same kind, same normal form).
3. *Follow-up and open point*: `IdentityCoverage` counts a shape column as identity-bearing with the basis `SHAPES` ("measured from value shapes, no catalogue tag"); the line
   is `STORES.Magasins_Ventes: 234 rows carry identity attributes (measured from value shapes, no catalogue tag: TELEPHONE (phone-shaped in 100% of sampled values), EMAIL (e-mail-shaped in 100% of
   sampled values); columns TELEPHONE, EMAIL) but feed no entity that holds them; among them 10 distinct e-mails, 15 distinct phones are seen in no other source - ...`, followed once by a paragraph
   saying what "measured from value shapes" is (how the values look, not what the column is) and the thresholds. At step 4 the same basis reads `colonnes sans etiquette du catalogue, mesurees sur la forme des valeurs` /
   `columns without a catalogue tag, measured from value shapes` (`stagingProposal.identityBasis.SHAPES`).
4. *Coverage of each entity* (`IdentityCoverage.entityCoverage`, `Session.entityCoverage`): for each entity whose feeds read at least one identity-bearing column, "its kind of columns" are the
   identity kinds of the columns its feeds read; the landing tables carrying a column of one of those kinds are the tables that could hold its records. The follow-up turn (the same one, no second loop)
   lists, fenced: `Client is fed by 2 of the 3 landing tables that carry its kind of columns (e-mail, phone); it would receive 300 rows (ECOM.customers 160, LOYALTY.Fidelite_Membres 140;
   STORES.Magasins_Ventes 234 not fed).` and, when a table is not fed, the two answers (`propose_feed`, or `note` with the reason; either stands). An entity with no identity-bearing column in its feeds,
   or with fewer than two carrying tables, has no line (nothing to compare). A partially fed entity opens the follow-up turn on its own (like an identity gap); a fully fed one only rides on it.
   At step 4 `ProposalChecks` puts the same line in the rationale of each NEW entity (`stagingProposal.check.entityCoverage*`, EN/FR), after the identity open point and before the model's text.
   It is information: no finding, no confidence change.

**Not done, on purpose.** Names are still not compared across tables, and a single-word name (`NOM` = `DUPONT`) has no shape here. "Its kind of columns" is the identity kinds only: an entity read from
untagged address or product columns has no coverage line. A shape is never turned into a tag, never written to the catalogue, never decides a feed. Unit-tested (`IdentityShapesTest`, `IdentityFactsTest`).

### 22.1 The span of a date column, and one date attribute fed by sources that do not overlap (lot F2b, 2026-10-19)

**Measured** (run 8, project `E2EMASTER20261019203130`): `Personne.birthDate` was fed from ECOM and LOYALTY (real birth dates, 300 rows) AND from the ticket table's purchase date
(`DATE_ACHAT`, 2026-01..2026-10): the staging held 534 rows with a birth date, 234 of them purchase dates. The inventory gave a date column's FORMAT but not its SPAN, and nothing compared the
sources feeding one attribute. Facts only, never a decision:

1. *Inventory* (`staging.DateSpans`, filled by `StagingLlmSuggester.fillShapes` on the same distinct values the shape profiler reads - no new read): for a column typed DATE / TIMESTAMP, or a text
   column of which at least 80 % of the filled rows parse as a date (ISO, ISO date-time, `yyyy/MM/dd`, `dd/MM/yyyy`, compact `yyyyMMdd`), `, values from 1955-03 to 2004-11 (filled rows: 160)`, and
   `, 234 rows in the last 24 months` when above 0. Month precision; a `dd/MM/yyyy` / `MM/dd/yyyy` column whose own values do not prove the order (none has a first part above 12, or none a second
   part above 12, or both do) is kept at the YEAR (`values from 1950 to 2006`) - the order is never guessed. A column with more distinct values than the 200 read, or a sampled table, says
   `measured on the 200 most frequent distinct values`. **Data policy** (`LlmDataPolicy`): shapes are withheld for a sensitive column, but the span is a count-like fact (two months and a row count,
   never a day or a value) and is given for it too, as the format fact of lot E already is; under `NONE` nothing about the values is read, so no span. On the B2C fixture: `ECOM.birthDate`
   `values from 1955-03 to 2004-11 (filled rows: 160)`, `LOYALTY.date_naissance` `values from 1955-02 to 2004-11 (filled rows: 140)`, `STORES.DATE_ACHAT` `values from 2026-01 to 2026-09
   (filled rows: 234), 234 rows in the last 24 months`.
2. *Follow-up* (same turn, `Session.dateSpanDisjoints`): for each attribute typed DATE / TIMESTAMP that the proposal feeds from several columns with a span, the spans are grouped by overlap; two
   groups or more = a sentence with the spans, the rows and the groups, and the two answers (a corrected feed, or a note). A string attribute is never looked at; overlapping spans are silent. It opens the
   follow-up turn on its own, like an identity gap.
3. *A corrected feed replaces, never duplicates* (checked in `propose_feed`, the "second call on a feed of this run" branch): a rule for an attribute the feed already maps, from another column or with
   another pipeline, REPLACES the old rule; the same rule again changes nothing (refused as before); a new `"remove": true` on a rule drops that attribute's rule from the feed (refused when it would leave the
   feed with none - then the answer is a note - or when the attribute is not mapped by that feed). Before this lot a second call could only ADD rules for new attributes, so a wrong rule could not be corrected.
4. *Step 4*: the same fact is an open point on the entity (`ProposalChecks.DATE_SPANS_DISJOINT`, `stagingProposal.check.dateSpansDisjoint*`, EN/FR): the sentence leads the entity's rationale, with the spans
   and the rows, followed by the model's note when it gave one. No confidence change of its own.

**Not done, on purpose.** No attribute other than a date is compared across sources; a span is never turned into a verdict on which source is wrong; the order of an ambiguous day/month is not guessed.
Unit-tested (`DateSpansTest`).

## 23. Facts only a row shows: B2B sources (branch `feature/b2b-source-facts`, 2026-10-23)

**Measured** (live B2B test, project `E2EB2B20261023081141`, files of `tools/e2e/gen_b2b.py`: SAP Business Partner JSON 150 rows, Salesforce accounts CSV 120 rows, website
requests JSON 90 rows). The staging pass proposed six wrong things, each for want of a fact: `CreationDate` (`/Date(1598227200000)/`) converted with `date(dd/MM/yyyy)` (0 valid);
`siren` fed with SAP's tax number, which holds a SIREN for French partners and a VAT number for the others (25 staging rows with a VAT number in `siren`); Salesforce
`SAP_BP__c` (SAP keys on 36 rows) mapped to `siret`; only `OrganizationBPName2` (the end of a name SAP cuts at 40 characters) mapped to `name`; Salesforce `VAT_Number__c`
(`FR50676952278` and `FR 747 741 994 42`) given `upper()` only; and the preflight claimed SAP left `contactPhone` unfed because `to_BusinessPartnerTax_results_0__BusinessPartner`
(`0001690977`, the row's own key) looked like a phone. The rule is unchanged: Java measures, the fact goes to the model WITH the rule it calls for, the model proposes, a
person decides; no column name is read.

| # | Measured where | The fact the model reads (when it fires) |
|---|---|---|
| 1 | `NormalisationProfiler` (date family `ODATA`: `/Date(ms)/`, `/Date(ms+hhmm)/`, years 1900-2100), `DateSpans` (span of such a column) | `, measured: date written OData /Date(ms)/ (epoch milliseconds, UTC) (100%) - convert it with trim\|epochdate, not isodate() ...` - also given for a sensitive column (format only). `TransformVerifier` now FIXES `date(dd/MM/yyyy)` on such a column to `trim\|epochdate` (the profile's candidate, run by the real engine). |
| 2 | `RowFacts` shape mix + companion (`ebx.RowFactsReader`, one pass per landing table) | `, row facts: holds several identifier shapes: digits only on 125 rows, with letters on 25 rows (9(9) 125, AA9(9) 10, ...); the shape follows to_BusinessPartnerTax_results_0__BPTaxType of the same row (FR1 -> 9(9) 125; DE0 -> AA9(9) 10; ...): a column holding several shapes must not be mapped whole to an attribute of one shape - split it with a filtering step per target, e.g. regex('^[0-9]{9}$') for the digits-only one`. Codes named only under data policy SAMPLE_VALUES (`5 codes, each holding one kind` otherwise); shapes withheld for a sensitive column and under NONE. Connectors `regex` gives null on a non-match (read in `Transformations.java`, pinned by `TransformationsTest`). |
| 2b | `PreflightCheck.shapeOutliers` (step 4 preflight, `PreflightFinding.SHAPE_OUTLIERS`, warning) | `Organisation.siren: 25 of 150 values from SAP.<column> do not have the shape 9(9) that SALESFORCE and WEB give it in most values - split the column with a filtering step, or say why.` Only identifier-like outputs give a shape; an attribute of names, phones or dates is never reported. |
| 3 | `RowFacts.crossTable` (values equal, after a trim, to the key of a landing table of ANOTHER source system) | `, row facts: 36 of 36 filled values are keys of SAP.SAP_BusinessPartner (its key BusinessPartner): a column whose values are another source's keys is a cross-reference to that source - keep it as its own attribute (a reference to that source, named after it) or leave it out with a note; never map it to a business attribute`. The Factory's `Xref` is the matching's record cross-reference (staging -> golden), written by the master build, not a staging attribute: it does not fit a source-declared reference, so none is created. |
| 4 | `RowFacts` continuation pair: B filled only on rows where A reaches its maximum length L or L - 1, on at least half of them, at most half of A's rows reaching it | on B: `filled on 20 rows, every one a row where OrganizationBPName1 reaches its maximum length 40 (or 39, the space dropped at the cut; 20 such rows), empty on all the others: the continuation of a value its source cut at 40 characters - join the pair into ONE attribute with joinwidth(40,OrganizationBPName1,OrganizationBPName2), never map one half alone`; on A a short pointer. `concat` joins with `\|` and no fixed separator is right (the cut falls inside a word on rows of 40 characters, on a space on rows of 39): Connectors gained `joinwidth(width,path1,path2,...)` (a full-width piece is followed directly by the next, a shorter one by a space). |
| 5 | `NormalisationProfiler.zeroAfterInternationalPrefix` (also `IdentityShapes.phoneShaped`); `RowFacts` own key; `PreflightCheck.holdsTableKeys` (the table's key values, read once in `StagingPreflight`) | A value starting `000` (without `+`) is not a phone in any country: `00` is the international prefix and no country calling code starts with 0 (ITU-T E.164). The inventory says `equals the row's own key BusinessPartner on 150 of 150 filled rows: an identifier of the row repeated in a sub-record, not a business value - never map it to a business attribute`, and `notFedBySource` skips a column that holds its table's keys. |
| 6 | `RowFacts` separators: identifier-like values (letters and digits, half of them digits at least, compact form not a phone) written with inner spaces / `.` / `-` / `/` on some rows, and compact on others or in another source (a UUID column, hyphens everywhere, says nothing; `552 100 554` counts as digits only) | `, row facts: identifier-like values written with inner spaces or separators on 38 rows (AA 999 999 999 99 38), compact on 50 rows; SAP.SAP_BusinessPartner.to_..._BPTaxNumber writes the same compact shape AA9(11) with no separator: identifiers are compared without separators - remove them in the pipeline (replace(' ','') for spaces, or a regex that keeps letters and digits), a case change alone leaves the same identifier written two ways`. `replace(' ','')` parses (quoted space, empty quoted argument: `TransformationsTest`). No Java step strips anything. |

**On the real B2B files** (flattened as the landing does, `results_0`), the six facts fire on exactly the columns of the defects, plus the website's `siren` (29 of 63 values typed `999 999 999`: separators fact); nothing else of the three tables carries a row fact.

**Cost**: one more streaming pass per landing table (text columns), bounded by the volume guard (`quality.ScanGuard`) and by `RowFacts.ROW_CAP` (20,000) rows held; past
it the text says `measured on the first N rows`. Only counts, shapes and (policy permitting) companion codes leave the reader. **Connectors**: `epochdate` and `joinwidth`
are in the mapping engine jar (`on-ebx-mapping.jar`, re-pinned in `tools/embedded-jars.sha256`), in `TransformationCatalogue` (30 entries) and the prompt's catalogue.
**Not verified**: the live language-model pass on the B2B project (the facts are unit-tested on the B2B values: `RowFactsTest`, `NormalisationProfilerTest`, `TransformVerifierTest`,
`PreflightCheckTest`, `DateSpansTest`, `PreflightHtmlTest`).

**Live run of 2026-10-23 (project `E2EB2B20261023133606`, agent run 799) and follow-up (branch `feature/b2b-shape-split`)**: the OData date, the cut name, the
cross-reference and the separators were followed. The tax number was not: the model wrote one filter per kind, as asked, and the rule probe refused both
(`probeRefusal`: "leaves 48 of 50 filled values EMPTY"), so it left the column out and both identifiers were lost; its tax-type codes went to
`businessPartnerCategory` and `addressType`. Since then: (1) `probeRefusal` accepts a filter on identifier-like values (`RowFacts.identifierLike`) that keeps
ONE kind (digits only, or with letters) and empties only the OTHER kind (`identifierKinds`); one that also empties values of its own kind (the model's French-only
`^\p{Lu}{2}[0-9]{11}$`) is refused with the counts and a wider filter. (2) The mixed-shapes fact now says "must not be left out either", asks for TWO rules, one
per kind, and gives a lettered filter that covers every lettered shape (`RowFacts.letteredFilter`: `regex('^\p{L}{2}[\p{L}0-9]+$')`). (3) The companion code
column gets its own fact: its codes tell which kind of identifier the other column holds - a qualifier, never mapped to an unrelated attribute (a category, an
address type, a status). Tests: `RowFactsTest`, `StagingLlmPassTest.aFilterSplittingIdentifiersByKindIsAccepted`.
