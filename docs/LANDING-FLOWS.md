# Landing flows via ebx-connectors — design notes

Investigation of 2026-09-20, branch `feature/landing-flows`. Every claim below is tagged **READ**
(with `path:line`, quoted where it matters) or **INFERRED** (a design conclusion drawn from the READ
facts, not itself present in the code). Two kinds of READ evidence are used: source/doc files in
`~/Dev/ebx-connectors` (file:line), and live, read-only MCP calls against the running instance
(`connectors_mapping_get`, `connectors_describe`, `connectors_mappings_list` — tagged "READ (live)").
Nothing was written, synced, upserted, deleted or published on the live instance to produce this
document.

Primary sources: `~/Dev/ebx-connectors/ebx-connectors/GUIDE.md` (referenced as `GUIDE.md:<line>`),
`~/Dev/ebx-connectors/ebx-connectors/docs/REFERENCE.md` (`REFERENCE.md:<line>`), and
`~/Dev/ebx-connectors/ebx-connectors/src/main/java/com/on/ebx/connect/...` (class-relative paths).

---

## Recommended design (summary)

For each `LandingTable`, the factory should generate **one root INBOUND mapping** on the Snowflake
connector's `query` operation, with:

- **`label`** deterministic and globally unique (e.g. `"Landing: <sourceSystem> <sourceObject>"`; since 2026-10-03 the factory writes
  `"Landing: <projectCode> <sourceSystem> <table>"`, see `docs/VOLUME.md` "two projects on one source system") —
  this, together with `connector`/`operation`/`direction`/`tablePath`, is the *only* thing
  `connectors_mapping_upsert` uses to decide create-vs-update, so a fixed label is what makes
  re-running the generator idempotent (§1).
- **`itemsPath = "rows"`** — the shape the Snowflake `query` operation actually returns (§2).
- **`keyPredicate = "./_sourceId='${SOURCE_ID}'"`** and **`createIfMissing = true`** — this is the
  one thing that must **not** be copied from the two live worked examples (mappings 200/204): those
  two mappings run with an *empty* `keyPredicate`, which means every row **always creates a new
  record**, never updates one (§1, §2). Landing needs real upsert-by-`_sourceId` semantics, so the
  generator must set `keyPredicate` explicitly.
- **`inputs.sql`** = a generated `SELECT` that: casts every source column to text explicitly
  (`TO_VARCHAR(...)`) so numeric/date columns are not silently reformatted by the engine's own
  number/date handling (§4); computes `_sourceId` as the key columns concatenated with a separator
  (or a row hash when there is no key) *in SQL*, because the rule-transformation pipeline has no
  concatenation primitive (§3); computes `_rowHash` the same way, in SQL, because there is no `hash`
  transformation either; computes `_receivedAt` from `CURRENT_TIMESTAMP()`, because there is no
  `now()` transformation; and embeds the load id the factory itself minted as a SQL string literal
  via a run-time input (`'${param.loadId}'`), because there is no way for a rule to read the
  Connectors run id (§3, and see "Gaps" #2).
- **Rules**: one per source column (`source = <alias>`, `target = ./<fieldName>`, no transformation,
  `policy = ALWAYS` — landing always reflects the latest raw copy), plus `SOURCE_ID → ./_sourceId`,
  `ROW_HASH → ./_rowHash`, `RECEIVED_AT → ./_receivedAt` (`transformation = isodatetime` to normalise
  Snowflake's timestamp text to the `YYYY-MM-DDTHH:MM:SS` EBX expects), `LOAD_ID → ./_loadId`, and two
  constant rules with an empty `source`: `constant(<sourceSystem>) → ./_sourceSystem` and
  `constant(NEW) → ./_status` (policy `IF_EMPTY`, so a reload does not reset a row a downstream step
  already moved past `NEW`).
- The factory must **always pass an explicit `max`** on `connectors_sync` — the module's own default
  is 200 rows, not "everything" (§2, §7, and the top item of "Gaps").

**Update, after the first real run (2026-09-20, mappings 220/222/224, Snowflake ERP/CRM sources)** — two
corrections to the design above, both implemented in `LandingFlow`/`LandingFlows`, not reflected in the
worked example or the table in §3 below (kept as originally written, as the investigation record):

- **`_receivedAt` is *not* `CURRENT_TIMESTAMP()` computed in Snowflake.** `CURRENT_TIMESTAMP()` runs in the
  Snowflake session's own time zone (`America/Los_Angeles` by default), and EBX stores the result as if it
  were server-local time with no conversion — a real defect seen live (rows read 06:15 for a 15:15 local
  load). The moment a row is received is the factory's business anyway, not the source's: it is now a
  second run-time input, `receivedAt`, minted once per table load by `LandingFlows.load` in the EBX
  server's own local time (`LandingFlow.receivedAt`, `yyyy-MM-dd'T'HH:mm:ss`, from the same `Clock` instance
  as the load id) and embedded the same way load id is: `'${param.receivedAt}' as RECEIVED_AT`. No
  `isodatetime` rule transformation needed (the text is already exact); no dialect method needed either
  (`SqlDialect.currentTimestampAsText` is gone).
- **`_sourceId` with a declared key falls back to a hash when every key column is `NULL`.** The plain
  concatenation described below (`COALESCE(...)` per key column, joined with `|`) degenerates to the same
  constant value for every row whose whole key is `NULL` (empty string for a single key column, `|`
  repeated for a composite one), collapsing them all onto one landing record — the first creating it, every
  later one silently updating it in place (`ConnectorService.importItem`, `ConnectorService.java:1034`: an
  empty *value* of `_sourceId` still yields a non-empty resolved predicate `./_sourceId=''`, looked up like
  any other value; only a literally empty *predicate string* is treated as "no lookup"). The generated SQL
  now reads `CASE WHEN <every key column> IS NULL THEN '#' || <row hash expression> ELSE <the
  concatenation> END` — plain SQL-92, computed in `LandingFlow.mapping`, no dialect method needed. The `#`
  prefix marks, for a later reader of `_sourceId`, that the value is not a real key (only the no-key-at-all
  case, still unprefixed, was already like this). A **partially** `NULL` composite key keeps the plain
  concatenation: it is still a real identity.

Complete example for `SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER`, key column `SUPPLIER_ID`, landing
dataspace `Demo_Landing`, one dataset per source system (`SNOWFLAKE_ERP`), as the factory would call
`connectors_mapping_upsert` (mandatory fields per §1: `connector`, `operation`, `direction`,
`dataset`, `tablePath`, `label` — READ `ConfigTransfer.java:285-292`):

```json
{
  "mapping": {
    "connector": "Snowflake",
    "operation": "query",
    "direction": "INBOUND",
    "label": "Landing: SNOWFLAKE_ERP SUPPLIER",
    "dataspace": "Demo_Landing",
    "dataset": "SNOWFLAKE_ERP",
    "tablePath": "/root/SUPPLIER",
    "active": true,
    "itemsPath": "rows",
    "keyPredicate": "./_sourceId='${SOURCE_ID}'",
    "createIfMissing": true,
    "inputs": {
      "sql": "select TO_VARCHAR(s.SUPPLIER_ID) as SUPPLIER_ID, TO_VARCHAR(s.NAME1) as NAME1, TO_VARCHAR(s.LEGAL_FORM) as LEGAL_FORM, TO_VARCHAR(s.REGISTRATION_NO) as REGISTRATION_NO, TO_VARCHAR(s.LEI) as LEI, TO_VARCHAR(s.STREET) as STREET, TO_VARCHAR(s.POSTAL_CODE) as POSTAL_CODE, TO_VARCHAR(s.CITY) as CITY, TO_VARCHAR(s.COUNTRY) as COUNTRY, TO_VARCHAR(s.PHONE) as PHONE, TO_VARCHAR(s.VAT_NO) as VAT_NO, TO_VARCHAR(s.BANK_NAME) as BANK_NAME, TO_VARCHAR(s.IBAN) as IBAN, TO_VARCHAR(s.BIC) as BIC, TO_VARCHAR(s.CURRENCY) as CURRENCY, TO_VARCHAR(s.DELETION_FLAG) as DELETION_FLAG, TO_VARCHAR(s.SUPPLIER_ID) as SOURCE_ID, '${param.loadId}' as LOAD_ID, TO_VARCHAR(CURRENT_TIMESTAMP()) as RECEIVED_AT, MD5(TO_VARCHAR(s.SUPPLIER_ID) || '|' || COALESCE(TO_VARCHAR(s.NAME1),'') || '|' || COALESCE(TO_VARCHAR(s.REGISTRATION_NO),'') || '|' || COALESCE(TO_VARCHAR(s.LEI),'')) as ROW_HASH from SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER s"
    },
    "rules": [
      { "position": 10, "sourcePath": "SOURCE_ID", "targetPath": "./_sourceId", "transformation": "", "policy": "ALWAYS" },
      { "position": 20, "sourcePath": "SUPPLIER_ID", "targetPath": "./SUPPLIER_ID", "transformation": "", "policy": "ALWAYS" },
      { "position": 30, "sourcePath": "NAME1", "targetPath": "./NAME1", "transformation": "", "policy": "ALWAYS" },
      { "position": 40, "sourcePath": "LEGAL_FORM", "targetPath": "./LEGAL_FORM", "transformation": "", "policy": "ALWAYS" },
      { "position": 50, "sourcePath": "REGISTRATION_NO", "targetPath": "./REGISTRATION_NO", "transformation": "", "policy": "ALWAYS" },
      { "position": 60, "sourcePath": "LEI", "targetPath": "./LEI", "transformation": "", "policy": "ALWAYS" },
      { "position": 70, "sourcePath": "STREET", "targetPath": "./STREET", "transformation": "", "policy": "ALWAYS" },
      { "position": 80, "sourcePath": "POSTAL_CODE", "targetPath": "./POSTAL_CODE", "transformation": "", "policy": "ALWAYS" },
      { "position": 90, "sourcePath": "CITY", "targetPath": "./CITY", "transformation": "", "policy": "ALWAYS" },
      { "position": 100, "sourcePath": "COUNTRY", "targetPath": "./COUNTRY", "transformation": "", "policy": "ALWAYS" },
      { "position": 110, "sourcePath": "PHONE", "targetPath": "./PHONE", "transformation": "", "policy": "ALWAYS" },
      { "position": 120, "sourcePath": "VAT_NO", "targetPath": "./VAT_NO", "transformation": "", "policy": "ALWAYS" },
      { "position": 130, "sourcePath": "BANK_NAME", "targetPath": "./BANK_NAME", "transformation": "", "policy": "ALWAYS" },
      { "position": 140, "sourcePath": "IBAN", "targetPath": "./IBAN", "transformation": "", "policy": "ALWAYS" },
      { "position": 150, "sourcePath": "BIC", "targetPath": "./BIC", "transformation": "", "policy": "ALWAYS" },
      { "position": 160, "sourcePath": "CURRENCY", "targetPath": "./CURRENCY", "transformation": "", "policy": "ALWAYS" },
      { "position": 170, "sourcePath": "DELETION_FLAG", "targetPath": "./DELETION_FLAG", "transformation": "", "policy": "ALWAYS" },
      { "position": 180, "sourcePath": "", "targetPath": "./_sourceSystem", "transformation": "constant(SNOWFLAKE_ERP)", "policy": "ALWAYS" },
      { "position": 190, "sourcePath": "LOAD_ID", "targetPath": "./_loadId", "transformation": "", "policy": "ALWAYS" },
      { "position": 200, "sourcePath": "RECEIVED_AT", "targetPath": "./_receivedAt", "transformation": "isodatetime", "policy": "ALWAYS" },
      { "position": 210, "sourcePath": "ROW_HASH", "targetPath": "./_rowHash", "transformation": "", "policy": "ALWAYS" },
      { "position": 220, "sourcePath": "", "targetPath": "./_status", "transformation": "constant(NEW)", "policy": "IF_EMPTY" }
    ]
  },
  "dryRun": true
}
```

then, once validated, `dryRun: false`, and a load run as:

```
connectors_sync(mapping="Landing: SNOWFLAKE_ERP SUPPLIER", inputs="loadId=L20260920-0001", max=1000000)
```

(`inputs` carries the factory's own load id — minted client-side, no code change needed — and `max`
must be set explicitly; see "Gaps" #1). The SQL positions in this example are illustrative; the
generator would emit one column + one rule per `LandingColumn` row, in `position` order, mechanically.

Everything above is **INFERRED** (a design built from the READ facts in §1–§7); the facts it rests on
are cited with file:line in those sections.

---

## 1. Shape of an inbound mapping

### 1.1 What `connectors_mapping_upsert` accepts

Entry point: MCP tool dispatch at `ebx/mcp/ConnectorsTools.java:260-264` (agent-read) calls
`ConfigTransfer.upsertMapping(session, repository, document, dryRun)`,
`ebx/ConfigTransfer.java:275-339`.

**Mandatory** — READ `ConfigTransfer.java:285-292`:
```java
for (final String required : new String[] { "connector", "operation", "direction", "dataset", "tablePath", "label" })
{
    if (pMapping == null || ConfigTransfer.text(pMapping, required) == null)
    {
        report.put("error", "mapping." + required + " is required (connector, operation, direction INBOUND|OUTBOUND, dataset, tablePath, label)");
        return report;
    }
}
```
`connector` must also name an existing `Connector` row (`ConfigTransfer.java:293-297`). Confirmed by
docs: `REFERENCE.md:3020` — "Requires `connector, operation, direction, dataset, tablePath, label`:
`mapping.<field> is required (...)`." and `REFERENCE.md:878`.

**Full optional field set** — READ `ConfigTransfer.java:41-45` (`MAPPING_FIELDS`, `RULE_FIELDS`),
cross-checked against `REFERENCE.md:2991` and the live `GUIDE.md:670-679` table:

```
connector, operation, direction, label, description, dataspace, dataset, tablePath, responseRoot,
active, collectionPath, parentLinkField, childKeyFields, childFilter, deleteMissing, itemsPath,
keyPredicate, createIfMissing, loadMode, checkpointStrategy, checkpointInput, checkpointValuePath,
checkpointInitialValue, deleteOperation, maxAttempts, retryDelayMinutes
```
plus top-level `inputs` (object `{name: expression}`) and `children` (array of nested mapping
documents, inheriting `connector`/`operation`/`direction`/`dataspace`/`dataset` from the parent when
omitted — `ConfigTransfer.java:630-653`), and a `feed {itemsPath, keyPredicate, createIfMissing}`
shorthand that is flattened onto the top level before persistence (`ConfigTransfer.java:656-680`,
confirmed `GUIDE.md:1289-1291`, `REFERENCE.md:3022`).

Field meanings relevant to landing (READ, `MappingDefinition.java`, cited by the research agent, and
`GUIDE.md:670-679`, `918-932`):
- `dataspace`/`dataset`/`tablePath` — where the mapping applies. An **empty `dataspace`** means
  "wherever the dataset exists" (`GUIDE.md:674`) — the factory should still set it explicitly to the
  landing dataspace, since a landing dataset name (one per source system) could in principle collide
  with something else.
- `responseRoot` — JSON path applied to the document *before* rules (`GUIDE.md:675`); not needed here
  since the Snowflake `query` operation returns `{columns, rows, count}` directly and `itemsPath`
  already selects `rows` (§2).
- `itemsPath`, `keyPredicate`, `createIfMissing` — the import-feed fields (§9 of `GUIDE.md`, lines
  917-932); see §1.2 below.

### 1.2 How a mapping is identified, and how idempotency actually works

READ — the create-vs-update decision, `ConfigTransfer.importMapping`, `ConfigTransfer.java:532-575`
(quoted by the research agent; matches `REFERENCE.md:3009-3011` exactly):

```java
if (pParentId == null)
{
    filter.append("osd:is-null(./parent) and ./connector=").append(quote(...))
        .append(" and ./operation=").append(quote(...))
        .append(" and ./direction=").append(quote(...))
        .append(" and ./tablePath=").append(quote(...));
}
else { filter.append("./parent=").append(quote(pParentId)); }
filter.append(" and ./label=").append(quote(label == null ? "" : label));
List<Adaptation> found = ConfigTransfer.rows(table, filter.toString());
if (found.isEmpty() && pParentId == null && text(pJson,"id") != null && pJson.has("updateById"))
{
    // fall back to updating that exact id
}
```

So: **the natural key for a root mapping is `(connector, operation, direction, tablePath, label)`**
(no parent) — READ, matches `REFERENCE.md:3009` and the field doc at `REFERENCE.md:3697`: "`label` ...
part of the matching key of configuration import/upsert (with connector, operation, direction and
tablePath)." **Calling `connectors_mapping_upsert` twice with the same five values updates the
existing mapping in place; it does not create a duplicate.** This is exactly what the factory needs
for idempotent (re-)generation: pick a deterministic `label` per `(sourceSystem, sourceObject)` (as in
the summary above) and re-issue the same upsert whenever `LandingPlanner`/a future `LandingGenerator`
runs again.

`id` is **server-assigned** (auto-increment) — READ, `Connectors.xsd:2693-2700` (quoted by the
research agent):
```xml
<xs:simpleType name="id">
    <xs:annotation><xs:appinfo><osd:autoIncrement/></xs:appinfo></xs:annotation>
    <xs:restriction base="xs:int"/>
</xs:simpleType>
```
`MAPPING_FIELDS` never includes `"id"`, so the caller cannot choose it on creation. The response
carries the assigned id (`REFERENCE.md:3025`: `{dryRun, notes, mapping (id or null when dry-run),
created, rulesWritten, childrenWritten, nextStep}`), which is what `LandingTable.inboundMapping`
should store — but **the factory's own idempotency must rest on the label, not on remembering the
id**, since the upsert call itself does not need (and does not require) the id to update in place.

**Label uniqueness caveat (READ, worth carrying into the design):** `label` is only unique *within*
the `(connector, operation, direction, tablePath)` tuple as far as upsert matching goes — the same
label on a *different* table creates a second, independent mapping. Tools that resolve "mapping id or
label" (`connectors_sync`, `connectors_mapping_get`, etc.) go through `Mappings.mappingNamed`,
`ebx/rest/Mappings.java:45-75`, which returns the **first** case-insensitive label match with **no
ambiguity check** (unlike the connector/operation disambiguation a few lines below it, which does
check `hits == 1` — `REFERENCE.md:147`, `MappingCompatibility`/`ConnectorRepository.mappingFor`).
**INFERRED**: the factory must make every generated label globally unique across the whole instance
(e.g. always include the table name, as in the example above), not just unique per source, to avoid
`connectors_sync("Landing: ...")` silently resolving to the wrong mapping if a label were ever reused.

### 1.3 The live worked examples (200/204) are NOT a template for landing's key/upsert behaviour

READ (live) — `connectors_mapping_get(mapping="200")` and `mapping="204"` (Snowflake ERP suppliers /
CRM accounts → Companies) return a `feed: true` and `createIfMissing: true` root mapping, but **no
`itemsPath` or `keyPredicate` key is present in the returned JSON at all** — every other optional
field that has a value (e.g. `responseRoot: ""`) is present, so their absence is read as "empty."

This matches a documented behaviour, READ `REFERENCE.md:4030`: "**Empty key predicate creates
rows** — `importDocument` does not require `isFeed()`: with a blank `keyPredicate` every item creates
a record (when `createIfMissing` is true)." And `REFERENCE.md:769-772` (`importItem`): "Predicate
empty → no lookup (item treated as new)."

**INFERRED**: mappings 200 and 204 are therefore **insert-only** — every Snowflake sync run creates a
*new* `Companies` record for every ERP supplier / CRM account row, with no attempt to find an existing
one. This is consistent with the memory note `snowflake-companies-demo-sources.md` ("une création par
flux n'a pas de métadonnées MAME, lancer le matching par REST") — these two mappings intentionally
rely on Match & Merge, run afterwards, to deduplicate. **Landing must not copy this pattern**: a
landing table is keyed on `_sourceId` and re-running the same load must update the same rows, not pile
up duplicates. The generator must set `keyPredicate = "./_sourceId='${SOURCE_ID}'"` explicitly (§1.1),
which mapping 200/204 deliberately do not.

### 1.4 Full vs. incremental — two unrelated mechanisms, both checked, neither wired up for landing yet

Two distinct things carry the name "incremental" in this module:

1. **Mapping-level, persisted, INBOUND-only checkpoint** — `loadMode` (`FULL`/`INCREMENTAL`),
   `checkpointStrategy`, `checkpointInput`, `checkpointValuePath`, `checkpointInitialValue`
   (`MAPPING_FIELDS`, §1.1). Validated before every run by `MappingSynchronizationPolicy.requireValid`
   — READ `REFERENCE.md:373-378`: "INCREMENTAL: mapping must be INBOUND ..., must have a strategy
   ..., and the strategy must be supported by the operation." **But READ `REFERENCE.md:933` and
   `:3713`: "in this build no run path instantiates [`SynchronizationCheckpointManager`] ...
   `ConnectorService` never injects or advances checkpoints. INCREMENTAL mappings are only validated
   and otherwise run like FULL."** So today, setting `loadMode=INCREMENTAL` on a landing mapping would
   pass validation but behave exactly like `FULL` — the checkpoint is never read or advanced. Also
   confirmed at `REFERENCE.md:4022`: "the checkpoint manager is not wired into any execution path."
2. **Per-run, ephemeral, OUTBOUND-only `scope=changed`/`scope=dataspaceDelta`** on `connectors_sync`
   (`GUIDE.md:943-965`) — unrelated to inbound landing; it compares a SHA-256 fingerprint of an
   *outbound* document against `OutboundState`, and only applies to OUTBOUND mappings.

**INFERRED**: for lot 1 (full load only, per `PLAN.md`'s own exit criterion), this is not blocking.
For a later "keep landing fresh incrementally" lot, the factory cannot rely on `loadMode=INCREMENTAL`
as it stands — it would have to roll its own high-watermark (e.g. a `since` run-time input embedded in
the generated SQL's `WHERE`, the same run-time-input mechanism used for `loadId` in §3) until
`SynchronizationCheckpointManager` is actually wired into `ConnectorService` (see "Gaps" #4).

### 1.5 How the target record's primary key is resolved (no dedicated "key rule")

READ — there is **no** key-rule field on `MappingDefinition`/`MappingRule`; `MappingRule` only has
`sourcePath`, `targetPath`, `transformation`, `policy`, `comment`. The record used for a feed item is
located purely via `keyPredicate` (an arbitrary XPath predicate, not necessarily the table's real
primary key) — `ConnectorService.java:1024-1040` (quoted by the research agent):
```java
final Adaptation existing = predicate.isEmpty() ? null : ConnectorService.locate(pSession, pTable, predicate);
if (existing == null && !pFeed.isCreateIfMissing())
{
    result.setStatus(SyncResult.Status.SKIPPED);
    result.setMessage("No record matches " + predicate + " and creation is off");
    return result;
}
```
Placeholders in `keyPredicate` are resolved against the **item's own JSON** (not the EBX record) —
`ConnectorService.java:1128-1144` (`resolvePredicate`), matching `REFERENCE.md:3711`: "XPath predicate
locating the target record of each imported item, with `${json.path}` placeholders resolved from the
item ... EBX rejections are wrapped as 'Key predicate rejected by EBX on <table>: <predicate>
(...)', with a hint to use `date-equal()` for date fields." For landing, `${SOURCE_ID}` in
`./_sourceId='${SOURCE_ID}'` reads the `SOURCE_ID` column alias of the current Snowflake row (§1.1's
example), so the predicate is a straightforward string-equality lookup on the table's declared primary
key field — no `date-equal()` concern since `_sourceId` is a plain string.

When no existing record matches and `createIfMissing=true`, a new occurrence is created and every rule
`targetPath` is staged with no policy check (`ConnectorService.java` `importItem`,
`REFERENCE.md:776`: "Create: all values written (no policy)"). Since a rule targets `./_sourceId`
directly (§1.1), the primary key is set explicitly by that rule on creation — the table's own
`osd:autoIncrement`, if it had one, is irrelevant here because `LandingModel.tree` (READ,
`landing/LandingModel.java:155`: `node.putArray("primaryKey").add(FactoryPaths.Landing.SOURCE_ID)`)
makes `_sourceId` a plain declared key, not an auto-increment id.

### 1.6 `deleteMissing` has no top-level (root-mapping) inbound equivalent

READ — `deleteMissing` is a **child (sub-object)** mapping field only (`MappingDefinition.java`, sole
use site `MappingEngine.applyChild`, per the research agent's exhaustive grep of
`isDeleteMissing`). There is no "delete EBX rows absent from this run's source" behaviour for a root
inbound mapping. **INFERRED**: not needed for lot 1 (landing is append/update, not full-refresh), but
if a future lot wants to flag landing rows no longer seen at the source (`_status = 'STALE'` or
similar), that logic has to be built by the factory itself (e.g. a follow-up query comparing
`_loadId` against the run's own load id, since nothing in Connectors does this automatically for a
root mapping) — see "Gaps" #7.

---

## 2. Which Snowflake operation reads a table, and how much it reads

### 2.1 Operation

READ (live) `connectors_describe(connector="Snowflake")`:
```
{"name":"query","kind":"SEARCH","description":"Run a SELECT (input sql, optional bindings b1..bn) and return its rows","requiredInputs":["sql"],"optionalInputs":[]}
```
This is the operation both live worked examples (200, 204) use, and the one recommended above. There
is no separate "read a whole table" operation with structured `table`/`schema`/`columns`/`where`
inputs for row data — those structured inputs (`source`, `schemas`, `tables`, `where`) belong to the
**data-catalog** operations (`catalog`, `profile`, `sample`, `usage`, `lineage`), which return
*metadata/statistics*, not row data — READ `REFERENCE.md:1743`.

READ — implementation, `SnowflakeConnector.java` (class javadoc, quoted by the research agent):
`"Snowflake through the SQL REST API ({@code POST /api/v2/statements}), no JDBC driver."` Confirmed
`REFERENCE.md:1733`: `calls POST /api/v2/statements, no JDBC driver`.

### 2.2 Paging / streaming and the row cap — the single most important fact for landing

READ — `SnowflakeConnector.java:139-141` (`execute`):
```java
if ("query".equals(pOperation) || "execute".equals(pOperation))
{
    return this.statement(AbstractRestConnector.require(pInputs, "sql"), SnowflakeConnector.bindings(pInputs), max);
}
```
and `max` is resolved just above, `SnowflakeConnector.java:136-138`:
```java
final int max = SnowflakeConnector.toInt(pInputs == null ? null : pInputs.get("max"),
    SnowflakeConnector.toInt(this.getConfig().getProperty("max.rows", "1000"), 1000));
```
So the connector's own `max` **input**, if present, overrides the connector property `max.rows`
(default `1000`, confirmed live via `connectors_describe`: `"max.rows":"1000"`, and
`REFERENCE.md:1734`).

Pagination **is** handled — READ `SnowflakeConnector.java` `collect()` (quoted by the research agent):
after the first response, further Snowflake API "partitions" are fetched (`?partition=1,2,3...`) via
`GET /api/v2/statements/{handle}?partition=p`, **but the loop stops as soon as `rows.size() >= max`**:
```java
for (int p = 1; p < partitions.size() && rows.size() < pMax; p++) { ... }
```
Confirmed `REFERENCE.md:1737`: "further partitions fetched with `?partition=p` until `max` rows." The
raw SQL is sent **as-is** — no `LIMIT` is injected (`statementBody()`: `body.put("statement", pSql);`,
`REFERENCE.md:1737`). Snowflake still computes/returns the full result server-side; only the client
truncates what it copies.

**The `max` that actually reaches the connector comes from two layers, and the *effective* default is
200, not 1000:** READ — `connectors_sync`'s own `max` input defaults to **200**,
`ebx/mcp/ConnectorsTools.java:277` (quoted by the research agent):
```java
integer(args, "max", 200)
```
and it is injected into the connector call's inputs only if not already present,
`ConnectorService.java:787-789`:
```java
if (!inputs.containsKey("max")) { inputs.put("max", String.valueOf(pMax)); }
```
So an unadorned `connectors_sync(mapping=...)` call caps a landing load at **200 rows**, silently — no
error, no truncation warning anywhere in the response (§5). **The factory must always pass an explicit
`max` on every landing sync call.**

### 2.3 Buffering — not streamed record-by-record

READ — the connector's own result is one in-memory Jackson `ArrayNode` (`rows`, filled up to `max` by
`appendRows()`, `SnowflakeConnector.java`, quoted by the research agent), returned as a single
`JsonNode` document. The import layer then copies it into a plain `ArrayList<JsonNode>` — READ
`ConnectorService.java:918-940` (`importDocument`, quoted by the research agent):
```java
final List<JsonNode> items = new ArrayList<JsonNode>();
...
for (final JsonNode item : array) { ... if (items.size() < pMax) { items.add(item); } }
```
**No streaming iterator exists anywhere in this path.** At the tool's ordinary bounds (200–10000) this
will not OOM; setting `max` to "the whole table" for a genuinely multi-million-row source would build
one multi-million-element `ArrayNode` + `ArrayList<JsonNode>` in memory before a single EBX write
happens. **INFERRED**: a very large landing table needs to be loaded in several bounded
`connectors_sync` calls (the generator would need to add its own pagination to the SQL, e.g. a
`WHERE <key> > '${param.after}'` driven by a run-time input, similar to the `loadId` embedding trick)
rather than one call with an enormous `max` — Connectors' `query` operation is not a substitute for a
true bulk/streaming load path.

---

## 3. Value expressions for the six technical fields

READ — the complete, exhaustive list of built-in rule transformations (`Transformations.java`,
confirmed by grepping every `Transformations.register(...)` call site, 29 names, and cross-checked
against the documented table `GUIDE.md:859-881` / `REFERENCE.md:401-429`):

```
trim, upper, lower, capitalize, left, right, substring, replace, regex, vatfr, digits, prefix,
suffix, default, constant, nullifempty, nullif, isodate, isodatetime, date, map, join, first, count,
boolean, number, round, fk, lookup
```

No `concat`, `hash`, `now`, `runid` or `uuid` transformation exists anywhere in the registry
(confirmed by an additional case-insensitive grep for `hash|concat|now|runid|uuid` across
`Transformations.java`, zero matches beyond the import list).

| Field | Supported today? | Evidence |
|---|---|---|
| (a) `_sourceSystem` — constant | **SUPPORTED** | `constant(v)` — READ `Transformations.java:419-426`: `return Transformations.arg(a, 0, null);` — discards the input, always returns its argument. Exactly the `constant(ACTIVE)` pattern seen live in mapping 200 (`./Lifecycle/status`). |
| (b) `_sourceId` — concatenation of several source columns | **NOT SUPPORTED** as a rule feature | A rule's `sourcePath` resolves exactly **one** JSON path (`Json.at(root, rule.getSourcePath())`, `MappingEngine.java:83`). The only list-oriented transformation is `join(sep)`, but READ `Transformations.java:515-546`: it only operates on a `List` already produced by a single `[*]` array projection of **one** field (`if (!(v instanceof List)) { return v; }`) — it cannot combine two independently-addressed fields. **Smallest fix**: `Transformation.apply(Object, List<String>, TransformationContext)` receives only the current value, never sibling paths of the same document — a real `concat(path1,path2,...)` needs `TransformationContext` extended with the document root (currently it only carries `dataset`, `record`, `session`, trust fields), threaded from `MappingEngine.compute` (`MappingEngine.java:68-110`, which already has `root` in scope) and registered as a new step in `Transformations.registerBuiltins()` (`Transformations.java:253-688`). **Workaround used in the design above**: build the concatenation in the connector's own SQL (`||` in Snowflake) — works for SQL-based connectors (Snowflake, Databricks, JDBC, SAP), not for Kafka/webhook/file/REST sources. |
| (c) `_loadId` — the run id or a load id | **NOT SUPPORTED** via any transformation or expression token | `TransformationContext` exposes only `dataset`, `record`, `session`, `oracle`/`sourceTrust`/`sourceConnector` (`TransformationContext.java`, quoted by the research agent) — no run id field. The run id itself lives only in a `ThreadLocal` inside `ConnectorService` (`beginRun()`/`currentRunId()`, `ConnectorService.java:52-91`) and is never passed into `MappingEngine`/`Transformations`. **Smallest fix**: add a `runId` accessor to `TransformationContext`, set it from `ConnectorService.currentRunId()` wherever a context is built, and register a zero-arg `runid()` step. **Workaround used in the design above**: the factory doesn't need the *Connectors* run id at all — it mints its **own** load id client-side, passes it as a run-time input (`connectors_sync(..., inputs="loadId=...")`), and the mapping's `sql` input **expression** (not a rule) embeds `${param.loadId}` as a literal in the generated SQL text, which Snowflake then returns as an ordinary column a normal rule maps to `./_loadId`. This composition is itself READ-supported: `GUIDE.md:786-824` describes exactly this input-expression mechanism (`${param.name}`, run-time inputs restricted to names an input expression actually reads, `GUIDE.md:815-824`), and `REFERENCE.md:3711` confirms feed items are "processed in its own transaction" independent of the mapping-level runId plumbing question. |
| (d) `_receivedAt` — current timestamp | **NOT SUPPORTED** | No `now()`/clock-reading step exists (same exhaustive list). Only `isodate`/`isodatetime` (reformat an *existing* string, `Transformations.java:444-465`) and `date` (reparse with `SimpleDateFormat`, `:466-481`). **Smallest fix**: register `now(format?)` in `Transformations.registerBuiltins()`. **Workaround**: `CURRENT_TIMESTAMP()` in the generated SQL, normalised on the EBX side with the existing `isodatetime` rule transformation (used in the design above) so the text matches the `YYYY-MM-DDTHH:MM:SS` EBX expects for an `xs:dateTime` field. |
| (e) `_rowHash` — hash of the row or of chosen columns | **NOT SUPPORTED** as a rule transformation | `Hashing.sha256(String)` genuinely exists (`Hashing.java:13-29`) but is **never registered** in `Transformations` (confirmed absent from the exhaustive list/grep above); its only call sites are internal (outbound-document fingerprinting for `scope=changed`, Kafka offset idempotency keys, OAuth/TLS/config fingerprints — none reachable from a rule). **Smallest fix**: `register("hash", (v,a,c) -> Hashing.sha256(Transformations.text(v)))` in `Transformations.registerBuiltins()` — this would hash the *pipeline's current value* (i.e., whatever `sourcePath` already resolved), not "the whole document"; hashing the *whole* row from a rule would need the same `TransformationContext`-root extension as (b). **Workaround**: `MD5(...)` (or another Snowflake hash function) over the concatenated key/relevant columns, computed in the generated SQL — same SQL-only-connector caveat as (b). |
| (f) `_status = 'NEW'` — fixed initial status | **SUPPORTED**, same mechanism as (a) | `constant(NEW)` with `policy = IF_EMPTY` so a later status change survives a reload. |

**INFERRED, overall**: for the Snowflake demo (and any SQL-capable connector — Databricks, JDBC, SAP),
none of (b)–(e) blocks lot 1: the generator can compute all four in the `sql` input text and let plain
rules carry the resulting columns across. The underlying **product gap** is architectural, not
tactical: `PLAN.md`'s own principle ("la factory... écrit des mappings Connectors" as a generic,
connector-agnostic action) does not actually hold for the four technical fields once a non-SQL source
(Kafka, webhook, a REST connector, a file) is in scope — those sources have no SQL to embed
`||`/`MD5`/`CURRENT_TIMESTAMP` into. See "Gaps" #2.

---

## 4. Type coercion (source value → landing `string` field)

READ — the conversion chain, in order:

**1. JSON → Java**, `MappingEngine.compute`, `MappingEngine.java:83-84`:
```java
final JsonNode sourceNode = rule.getSourcePath().isEmpty() ? null : Json.at(root, rule.getSourcePath());
final Object raw = sourceNode == null ? null : Json.toJava(sourceNode);
```
`Json.toJava`, READ `Json.java:167-197`:
```java
public static Object toJava(final JsonNode pNode)
{
    if (pNode == null || pNode.isNull() || pNode.isMissingNode()) { return null; }
    if (pNode.isTextual()) { return pNode.asText(); }
    if (pNode.isNumber()) { return pNode.decimalValue(); }
    if (pNode.isBoolean()) { return Boolean.valueOf(pNode.asBoolean()); }
    if (pNode.isArray()) { /* recurse into a List<Object> */ }
    return Json.write(pNode);   // object/VARIANT: real JSON serialization, not toString()
}
```

**2. Transformation pipeline** (empty for most landing columns) — each built-in step, when it touches
text, normalises via `Transformations.text(Object)`, READ `Transformations.java:128-156` (paraphrased
by the research agent): `null → null`; `BigDecimal → stripTrailingZeros().toPlainString()`; `List →`
items joined with `", "`; else `String.valueOf(pValue)`.

**3. Final string written**, `MappingEngine.asXsString`, READ `MappingEngine.java:498-509`:
```java
private static String asXsString(final Object pValue)
{
    if (pValue == null) { return null; }
    if (pValue instanceof Boolean) { return pValue.toString(); }
    return Transformations.text(pValue);
}
```
then written with `setValueFromXsString` (EBX parses it against the target field's type — a no-op
constraint check here since every landing field is `xs:string`).

| Source value | Java form | String actually written | Note |
|---|---|---|---|
| text | `String` | as-is | **No implicit trim anywhere** — only the explicit `trim` step strips whitespace. A landing column pulled without `trim` keeps leading/trailing spaces verbatim, which is arguably correct for a landing zone ("never refuses what the source sends") but should be a deliberate choice, not an oversight. |
| number | `BigDecimal` (`.decimalValue()`) | `stripTrailingZeros().toPlainString()` | **Lossy**: `100.00` → `"100"`, a padded code stored as a NUMBER loses leading zeros the moment it round-trips through `BigDecimal`. This is why the recommended design (§ summary) casts every source column to `VARCHAR`/`TO_VARCHAR` in the generated SQL — once the value arrives as Snowflake `TEXT`, `Json.toJava`'s `isTextual()` branch is taken and the value passes through unmodified. |
| boolean | `Boolean` | `"true"`/`"false"` | Via `Boolean.toString()`, direct. |
| array | `List<Object>` (recursive) | comma-joined (`", "`) unless a `join`/`first`/`count` step intervenes | Not expected for a landing column pulled from a flat SQL `SELECT`. |
| object/VARIANT (nested JSON, e.g. mapping 200/204's `object_construct(...)`-built columns) | already a `String` — real JSON text via `Json.write` (Jackson serialization) | unchanged | **Not** a Java `Map.toString()` dump — a genuine JSON string, which is exactly the right shape for a landing field that must preserve "whatever the source sent" even for a semi-structured column. |
| SQL `NULL` | `null` | rule **skipped**, field left untouched | READ `MappingEngine.java:86-95` (quoted by the research agent): `if (!sourceClear && (xs == null || xs.isEmpty())) { continue; }` — **"a mapping cannot clear a field"** (`REFERENCE.md:232`). The one exception (`allowsSourceLabelClear`, `MappingEngine.java:176-181`) is hard-coded to two fields of the data-catalog's own `/root/Asset`/`/root/Column` tables and irrelevant here. **Consequence for landing**: a source `NULL` on a re-load does **not** blank out a landing field that previously had a value from an earlier load of the same row — the old value survives. If the factory needs "a landing row is an exact mirror of the latest source row, NULLs included," this is a real limitation to design around (e.g. accept it, since `_rowHash` computed in SQL from the raw column values would still detect the source-side change even though the landing string field itself doesn't clear). |

---

## 5. After a run: what `connectors_sync` and `connectors_run_get` expose

### 5.1 `connectors_sync`

READ — no `rowsCreated`/`rowsUpdated`/`rowsRejected` field exists anywhere in the module; the model
has no such notion. Instead, every processed row gets one `SyncResult.Status` value:
`OK, NO_CHANGE, NO_DATA, SKIPPED, ERROR, DRY_RUN, PARTIAL`. For a feed run, the response (READ
`ebx/mcp/ConnectorsTools.java` `results()`/`putOutcome()`, quoted by the research agent, matching
`REFERENCE.md:3124`) is:
```json
{
  "runId": "...", "dryRun": false,
  "results": [ { "mapping": 217, "record": "...", "key": "...", "status": "OK", "message": "created ...", "errors": [...]? } ],
  "count": 1000, "ok": 950, "errors": 5, "failed": 3, "partial": 2, "skipped": 40, "noData": 5, "status": "PARTIAL"
}
```
**Created vs. updated is only recoverable from the free-text `message`** (`"created " + label + ...`
vs. `"updated " + label + ...`, `ConnectorService.java` `importItem`, quoted by the research agent) —
there is no structured boolean/enum distinguishing the two. **INFERRED**: `LandingTable.lastLoadRows`
should be filled from `count` (or `ok`, depending on whether the factory wants "rows processed" or
"rows that actually changed something"); rejects should be counted from `errors`/`failed`, with the
actual per-row reason read from each result's own `message`/`errors[]`, not from a top-level summary.

### 5.2 `connectors_run_get`

READ — `run()`, `ConnectorsTools.java:937-955` (quoted by the research agent):
```java
for (final Adaptation row : this.repo().logOfRun(pRunId)) { rows.add(ConnectorsTools.logJson(row)); ... }
out.put("count", rows.size()); out.put("errors", errors); out.put("replayable", errors > 0);
```
Per-call detail (`logJson`, `ConnectorsTools.java:1175-1191`): `{id, timestamp, mapping, connector,
operation, direction, record, key, status, message, runId, origin}` — no `changes`, `durationMs`, or
`dataspace`/`tablePath` (those exist on the underlying `SyncLog` row but are not surfaced by this
tool; `connectors_log_search` returns the same reduced field set). **Important limitation for landing
feeds specifically**: READ `REFERENCE.md:509` / confirmed `REFERENCE.md:2.9.2`/`2.14.3`: **"Feed items
... carry no runId and no mappingId, so they are not returned by `logOfRun`."** This means
`connectors_run_get(runId=...)` **will not list the individual imported rows of a landing feed run** —
only the feed's own header/error `SyncLog` rows (written on `NO_DATA`/exception,
`REFERENCE.md:2.14.1-2.14.2`) are logged with a runId. **INFERRED**: per-row reject detail for a
landing load must come from the `connectors_sync` response's own `results[]` array (§5.1), captured at
call time — not reconstructed afterwards via `connectors_run_get`, which is the wrong tool for this.

---

## 6. Lineage side effects

READ (exhaustively, GUIDE.md §3.7 lines 406-553, and independently confirmed with file:line by a
research agent reading `OpenLineageEmitter.java`/`CatalogDetection.java`/`ConnectorService.java`):

### 6.1 Whether a landing mapping is described to the catalog at all

**Two independent gates, both must be open:**
1. **`emit.inbound` on the `openlineage`-type sink connector — default `false`.** READ
   `OpenLineageEmitter.java` property default (research agent), confirmed `GUIDE.md:445`: "Properties
   of the sink: `emit.outbound` (default `true`), `emit.inbound` (default `false`: imports and
   enrichments described too, EBX becoming the output and the rules read the other way)." **A landing
   mapping is INBOUND, so by default it is emitted neither as a run event nor as a declared
   (pre-run) `JobEvent`** — `GUIDE.md:480`: "an inbound mapping is declared only with
   `emit.inbound=true`." The factory (or whoever configures the demo's `openlineage` sink connector)
   must set `emit.inbound=true` for landing flows to show up in the catalog at all.
2. **Not excluded as a "catalog flow".** READ `CatalogDetection.isCatalogFlow`,
   `core/CatalogDetection.java:171-190` (quoted by the research agent): a mapping is excluded only
   when its **own `dataspace`/`dataset` equals the verified `ebx-data-catalog` module's own dataset**
   (default `DataCatalog`/`DataCatalog`). A landing mapping's EBX side is `<Project>_Landing` /
   `<sourceSystem>`, never the catalog's own dataset, so **a landing mapping is never excluded by this
   rule** — it is included as soon as `emit.inbound=true` is set.

### 6.2 What the event contains

- **Job** = the mapping's own `namespace`+`name`. Namespace comes from the **sink connector's**
  `job.namespace` property (default `"ebx-connectors"`, shared by every mapping); name = the mapping's
  **`label`** (fallback `"mapping-<id>"` if blank) — READ `OpenLineageEmitter.java:231-233,409-412`
  (research agent), confirmed `GUIDE.md:421`. **This is another reason the generated `label` must be
  distinctive** (§1.2) — it is literally what a person sees as the flow's name in the catalog.
- **Output** (an INBOUND mapping's EBX side) = `<dataspace>/<dataset><tablePath>` — READ
  `OpenLineageEmitter.ebxName`, `core/OpenLineageEmitter.java:475-487` (research agent), confirmed
  `GUIDE.md:424`: `Companies/Companies/root/Companies`-style name. For the example above:
  `Demo_Landing/SNOWFLAKE_ERP/root/SUPPLIER`.
- **Input** (the connector's remote object, for an INBOUND mapping) — this is the part that most
  depends on how the mapping is built. READ `OpenLineageEmitter.datasetOf`,
  `core/OpenLineageEmitter.java:571-598` (research agent): first, any `OBJECT_INPUTS`-named input
  (`table`, `object`, `topic`, etc.) wins if present and template-free; **else, for a `query`/`sql`
  operation with no such input, the code parses the first `FROM schema.table` of the SQL text**
  (`tableOfSql`, `core/OpenLineageEmitter.java:515-543`, a bounded regex, 1–3 dot-separated
  identifiers, quotes/backticks stripped, a templated table not guessed); **else it falls back to the
  bare operation name (`"query"`)** — a generic, non-descriptive dataset name. Confirmed
  `GUIDE.md:530-534`: "Until 2026-09-15 the Snowflake and Databricks query feeds appeared as a table
  named `query`" (fixed as of that date). **For the landing SQL recommended in the summary
  (`... from SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER s`), the parser resolves cleanly** to
  `SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER` — a plain `FROM <ident>[.<ident>][.<ident>]` with no
  subquery in front. **INFERRED**: the generator must keep the `FROM` clause of the generated SQL
  simple and literal (no CTE-wrapped or dynamically-built table reference) specifically so this
  regex-based lineage naming keeps working; this is a real, if narrow, coupling between "how the SQL
  is generated" and "how clean the lineage looks."

### 6.3 Nothing mapping-level is required beyond a sensible SQL `FROM` and label

READ — the catalog **Source** a connector's lineage lands under is matched purely by **namespace**,
computed from the **connector's** base URL (`OpenLineageEmitter.namespaceOf`,
`core/OpenLineageEmitter.java:602-617`, research agent) — not by anything on the mapping. There is no
"sourceSystem" input or required label pattern for this matching; `connectors_catalog_source` /
`connectors_lineage_declare` operate per-connector, not per-mapping (§6 of GUIDE.md, `GUIDE.md:536-546`).
**One real, documented gotcha, not landing-specific but worth carrying over**: for `table`-style
operations, an *unqualified* `table` input gets silently prefixed with the connector's default
`schema` property, which can create a second, "junk" catalog asset next to the real one
(`PLAN.md:565-578`, `GUIDE.md:1003-1010`) — not applicable to the `query`/SQL-`FROM` naming path used
here, since that path only ever uses what the `FROM` clause actually says.

### 6.4 Exclusion

Confirmed (§6.1.2): only the catalog's own scan/profile/sample/usage/lineage mappings (whose EBX side
*is* the catalog's own dataset) are excluded, via `exclude.catalogFlows` (sink property, default
`true`) — `core/CatalogDetection.java:171-190`, `core/OpenLineageEmitter.java:743,1031-1037`, doc
`GUIDE.md:519-528`. **A landing mapping is not, and cannot be, excluded by this mechanism** — it is a
completely different code path from the one governing whether it is emitted at all (`emit.inbound`,
§6.1.1).

---

## 7. Performance facts (documented/code-confirmed only — no invented numbers)

READ — **one EBX transaction per imported row, not per batch, not per run.** `ConnectorService`
`importItem`, quoted by the research agent:
```java
final ProgrammaticService service = ProgrammaticService.createForSession(pSession, dataset.getHome());
...
final ProcedureResult procedureResult = service.execute(new Procedure() { ... });
```
called **inside** the `for (final JsonNode item : items)` loop — one `service.execute(...)` per item.
Confirmed twice in docs: `REFERENCE.md:924`: "Each record synchronisation and each feed item runs in
its own EBX procedure; `SyncLog`, provenance, conflicts and outbound state are written in further
separate procedures, so a failure writing them does not roll back the business change." and
`GUIDE.md:928`: "Each item is processed in its own transaction, then its sub-objects." Also
`REFERENCE.md:3711` (the `keyPredicate` field doc) repeats "each item is processed in its own
transaction."

READ — the **one** place real batching exists in this module is unrelated to the `query`/feed path:
`SnowflakeConnector.publishBatchSize()` (`core/SnowflakeConnector.java`, research agent, default 500)
governs the **outbound product-publication** (`TablePublisher`/`ProductPublisher`) bulk-insert path
(§10.2 of `GUIDE.md`), not `connectors_sync`/import feeds.

**Throughput**: **NOT DOCUMENTED.** An exhaustive grep of `GUIDE.md`, `REFERENCE.md` and `PLAN.md` for
"throughput", "rows/sec", "rows per", "per second", "per minute" (by the research agent) found no
rows/sec or rows/min figure for the Snowflake `query`/import path anywhere. The only concrete rate
figure in the docs is unrelated (Slack's own API rate limit, `GUIDE.md:649`). **`PLAN.md` itself
already lists this as an open item** (`PLAN.md:212`: "Débit d'un chargement landing massif par un flux
Connectors : à mesurer, pas à supposer" — "to be measured, not assumed") — this investigation did not
close that item; it confirms the code gives no reason to expect a specific number, and the one-
transaction-per-row architecture (above) means the real number depends entirely on the target EBX
instance's per-record write latency, multiplied by row count, with no batching to amortise it.
**INFERRED**: before committing to "landing loaded in under 10 minutes" (lot 1's exit criterion) for a
table bigger than a few thousand rows, a real timed run against the target instance is needed — this
cannot be predicted from the code alone.

---

## 8. Feed-based landing (non-SQL connectors)

Implemented 2026-09-22 (`feature/landing-feed-path`), closing "Gaps" #2 below for the connectors it
applies to: READ — `ebx-connectors` now registers `hash`, `now`, `runid` and `concat` as rule
transformations (`Transformations.java`), and the `AbstractFileConnector` family (`sample-file`,
`sftp`, `s3`, `azure-blob`, `gcs`, `ftp`) exposes a `read` operation (`{rows:[...], count}`, required
input `path`) alongside `catalog`/`list` — but never `query`, so none of these six can ever get a
`SqlDialect` (`LandingFlow.dialectFor` refuses every one of them by design, §2). `LandingFlow.mapping`
now builds one of two shapes for a `LandingTable`, decided per-table by a new `Table.fileFeed` flag:
the `query`/SQL shape described in §1–§7 above (unchanged), or the `read`/feed shape below.

### 8.1 How a table is routed to this path

`LandingFlows.generate` reads a connector's own `connectors_describe` answer once per `generate` call
(not once per table) and inspects its `operations[]`: if any operation is named `read` and its
`requiredInputs` include `path`, the connector is routed to the feed path
(`LandingFlows.isFileFeed`). **Deliberately structural, not a hardcoded list of connector type
strings** — a `sample-file`/`sftp`/`s3`/`azure-blob`/`gcs`/`ftp` table gets the feed mapping today,
and any future connector exposing the same `read`+`path` contract gets it automatically, with no
further `ebx-mdm-factory` change, the same way `LandingFlow.dialectFor` avoids hardcoding "which
types are SQL" anywhere beyond the `SqlDialect.supports` contract itself.

### 8.2 The feed mapping shape

`operation: "read"` (not `"query"`), `inputs.path` (not `inputs.sql`) set to the table's
`sourceObject` verbatim — a catalog-scanned file asset's `qualifiedName` (and so `sourceObject`) is
already the exact path `AbstractFileConnector.read` expects as its `path` input, e.g. `"sample.csv"`.
`itemsPath`/`keyPredicate`/`createIfMissing` are unchanged from the SQL shape. A feed row's own JSON
field names ARE the source column names directly — unlike the SQL path, which SELECTs into opaque
`C1`/`C2`/... aliases to dodge a reserved SQL word, a rule's `sourcePath` here is `column.sourceColumn`
verbatim, no aliasing needed.

The six technical rules that were SQL expressions become rule-transformation pipelines:
`_loadId → runid`, `_receivedAt → now`, `_rowHash → concat(col1,col2,...,colN)|hash` (every column, in
position order, mirroring the SQL path's `concatCoalesce` over all columns), `_sourceId →
concat(keyCol1,keyCol2,...)` when the table has a declared source key (plain concatenation, not
hashed — matches the SQL path's "normal" case) or `concat(col1,...,colN)|hash` when it does not (same
fallback as the SQL path's own no-key case). `_sourceSystem`/`_status` are unchanged (`constant(...)`
rules, identical in both shapes — `LandingFlow.addSourceSystemRule`/`addStatusRule`).

Complete example, a `sample-file` connector `"SampleFiles"`, source `sample.csv`, key column
`customerId`:

```json
{
  "mapping": {
    "connector": "SampleFiles",
    "operation": "read",
    "direction": "INBOUND",
    "label": "Landing: FILE_SRC Sample",
    "dataspace": "Demo_Landing",
    "dataset": "FILE_SRC_LANDING",
    "tablePath": "/root/Sample",
    "active": true,
    "itemsPath": "rows",
    "keyPredicate": "./_sourceId='${SOURCE_ID}'",
    "createIfMissing": true,
    "inputs": { "path": "sample.csv" },
    "rules": [
      { "sourcePath": "customerId", "targetPath": "./customerId", "transformation": "", "policy": "ALWAYS" },
      { "sourcePath": "name", "targetPath": "./name", "transformation": "", "policy": "ALWAYS" },
      { "sourcePath": "", "targetPath": "./_sourceSystem", "transformation": "constant(FILE_SRC)", "policy": "ALWAYS" },
      { "sourcePath": "", "targetPath": "./_loadId", "transformation": "runid", "policy": "ALWAYS" },
      { "sourcePath": "", "targetPath": "./_receivedAt", "transformation": "now", "policy": "ALWAYS" },
      { "sourcePath": "", "targetPath": "./_rowHash", "transformation": "concat(customerId,name)|hash", "policy": "ALWAYS" },
      { "sourcePath": "", "targetPath": "./_sourceId", "transformation": "concat(customerId)", "policy": "ALWAYS" },
      { "sourcePath": "", "targetPath": "./_status", "transformation": "constant(NEW)", "policy": "IF_EMPTY" }
    ]
  },
  "dryRun": true
}
```

(`LandingFlow.feedMapping`, verified against `LandingFlowTest.checkFeedMappingWithKey`/
`checkFeedMappingWithoutKey`.)

### 8.3 Two deliberate differences from the SQL path

Not oversights — both are documented limitations, and neither is this task's job to close:

- **No "every key column NULL" fallback.** The SQL path's `sourceIdExpr` (§ summary, "Update, after
  the first real run") falls back to `'#' || <row hash>` when every declared key column is `NULL` for
  a given row, so an entirely-null key does not collapse every such row onto one `_sourceId`. The
  rule-transformation pipeline has no conditional/branching primitive to build the same fallback
  with, so the feed path's `_sourceId` is unconditionally the plain concatenation when a key is
  declared — even on the rare row where every key column is `NULL`. A real fix needs a real
  conditional-transformation primitive in `ebx-connectors` (a new, separate gap, not added to the
  list below since it is speculative until a real feed source is seen hitting it).
- **`_loadId`/`_receivedAt` no longer carry the factory's own client-minted values.** The SQL path
  embeds `'${param.loadId}'`/`'${param.receivedAt}'` as literal text inside the generated SQL, which
  Snowflake then echoes back as an ordinary result column a rule maps — a mechanism that fundamentally
  requires a text template the connector evaluates server-side, which a file-read connector has no
  equivalent of (there is no SQL, and so nothing to embed a placeholder string into). `runid` gives
  every row of one sync run the same value (a real `ebx-connectors` run id — a different id, and a
  different format, from `LandingFlow.loadId`, but the same "one value per load" property); `now`
  reads server time at rule-evaluation time, near-identical across the rows of one run (unlike the SQL
  path's single value embedded once at query time, but close enough that no row's `_receivedAt`
  differs meaningfully from another's within one load). `LandingFlows.load`'s existing `loadId`/
  `receivedAt` minting and its `inputs = "loadId=...;receivedAt=..."` string on `connectors_sync` are
  **unchanged** — those two values simply go unread by a feed mapping now, which `connectors_sync`
  tolerates silently (an input the mapping's own rules don't reference is ignored, not an error — see
  §1 above on `connectors_sync`'s tolerance of unused inputs).

### 8.4 Landing-table naming: the file name, not the extension

A SQL source's `sourceObject`/`qualifiedName` is `DB.SCHEMA.TABLE`; both `LandingModel.Builder.table`
and `LandingPlanner`'s relation-naming logic name a table from its **last** dot-separated segment
(`TABLE`). A file connector's asset `qualifiedName` is a plain path, e.g. `sample.csv` — the same
"last dot-separated segment" logic would name the table `csv` (its extension), not `sample` (the
file), and two files sharing an extension (`orders.csv`, `customers.csv`) would collide onto the same
generated name before `LandingModel.unique` gets a chance to disambiguate them. `LandingModel.baseNameOf`
strips a recognised trailing file extension (`csv`, `tsv`, `json`, `ndjson`, `jsonl`, `txt` — the set
`sample-file`'s own upload widget accepts) from a qualified name before either call site's existing
last-segment logic runs; a genuine `DB.SCHEMA.TABLE` name is unaffected (`TABLE` is never one of the
six recognised extensions), so no existing SQL-source naming changes.

---

## Gaps in ebx-connectors, ordered by how blocking they are for the landing design

1. **`connectors_sync`'s effective default `max` is 200 rows, with no signal when it truncates.**
   (§2.2: `ebx/mcp/ConnectorsTools.java:277`, `integer(args, "max", 200)`, injected into the
   Snowflake connector's own `max` input which then bounds both the SQL-API partition fetching and
   the EBX import list — §2.2, §2.3.) Silent, and directly threatens the "landing loaded in under 10
   minutes" exit criterion for any table over 200 rows if the factory forgets to pass `max` explicitly.
   **Not really an ebx-connectors defect** (the parameter exists and is documented,
   `GUIDE.md:1188-1189`) — the smallest fix is entirely on the factory side: **always pass an explicit,
   generous `max`** on every landing `connectors_sync` call, and size it from
   `LandingTable.sourceRowCount` (already captured by `LandingPlanner`, `ebx/LandingPlanner.java:141`)
   rather than trusting the default.
2. **No connector-agnostic way to compute `_loadId`, `_receivedAt`, `_rowHash`, or a composite
   `_sourceId` from a rule** — no `runid()`, `now()`, `hash()`, or `concat()` transformation exists
   (§3, exhaustively confirmed against `Transformations.java`'s 29 registered names). A working
   SQL-embedding workaround exists for SQL-based connectors (Snowflake, Databricks, JDBC, SAP) and is
   used in the recommended design (§ summary), so **this does not block the Snowflake demo (lot 1)**.
   It does block the stated architectural principle that landing generation is a uniform, connector-
   agnostic function of catalog metadata (`PLAN.md`: "Générer un landing est une fonction pure des
   métadonnées du catalogue") — that principle silently stops holding for any future non-SQL source
   (Kafka, webhook, a REST connector, a file). Smallest fix, in order of value: register `hash(...)`
   (`Transformations.java`, wrapping the already-existing `Hashing.sha256`) and `now()` first — both
   are self-contained, no `TransformationContext` change needed; `runid()` next (needs
   `TransformationContext` to carry `ConnectorService.currentRunId()`); `concat(path1,path2,...)` is
   the largest change (needs `TransformationContext` to carry the document root, threaded through
   `MappingEngine.compute`/`buildOutbound`).
3. **No documented or measured throughput for the `query`/import-feed path, and no batching of EBX
   writes** (§7: one `ProgrammaticService.execute` per row, confirmed in code and twice in docs;
   `PLAN.md` itself flags this as unmeasured). Not fixable by configuration — either accept the
   per-row-transaction cost as a hard architectural fact of this module, or (larger, out of scope of
   "generate a mapping") build a bulk-insert path analogous to the existing outbound
   `TablePublisher`/`publish.batchSize` mechanism (§7) for inbound landing loads specifically.
   **INFERRED priority**: measure first (a timed `connectors_sync` run against a few-thousand-row
   Snowflake table) before deciding whether this needs a code change at all.
4. **`loadMode=INCREMENTAL`/checkpoint fields are modeled and validated but never executed** (§1.4:
   `REFERENCE.md:933`, "`ConnectorService` never injects or advances checkpoints"). Not blocking for
   lot 1 (full load only); blocks any later "load only what changed" landing lot unless (a) this is
   wired up in `ConnectorService`/`SynchronizationCheckpointManager`, or (b) the factory builds its own
   high-watermark via a run-time `${param.since}` input in the generated SQL (same mechanism as the
   `loadId` workaround, §3(c)) — the latter needs no ebx-connectors change and mirrors what is already
   proposed for `_loadId`.
5. **Numeric/date landing columns lose their exact source-text representation unless explicitly cast
   to `VARCHAR` in the generated SQL** (§4: `BigDecimal.stripTrailingZeros().toPlainString()` strips
   leading zeros and trailing decimal zeros; a `NUMBER`/`DATE`/`TIMESTAMP` column read without a
   `TO_VARCHAR(...)` wrapper is silently reformatted by the engine's own coercion, not preserved
   byte-for-byte). Not an ebx-connectors defect (the conversion is reasonable for a typed target
   field) but a hard requirement the SQL generator must honour for landing specifically, since a
   landing table's whole point is to mirror the source string-for-string. **Smallest fix**: entirely
   on the factory side — the SQL generator must wrap every non-text source column in `TO_VARCHAR(...)`
   (as done in the § summary example).
6. **A source `SQL NULL` never clears a previously-populated landing field on a re-load** (§4:
   `MappingEngine.java:86-95`, "a mapping cannot clear a field"; the one hard-coded exception is
   irrelevant to landing). Minor for lot 1 (first load has nothing to clear); worth documenting as a
   known limitation of the mirroring semantics, mitigated in the recommended design by the fact that
   `_rowHash` (computed in SQL from the raw column values, including NULLs via `COALESCE`) still
   changes when a source value becomes NULL, so downstream staging logic can detect the change even
   though the landing string field itself doesn't blank out.
7. **No root-mapping "delete rows missing from source" for inbound feeds** (§1.6) — only a child
   (sub-object) `deleteMissing` exists. Not needed for lot 1's append/update design; would need either
   a `deleteMissingAtSource`-style extension to `MappingDefinition`/`ConnectorService` (mirroring the
   existing child-table logic in `MappingEngine.applyChild`), or a factory-side follow-up query
   comparing each row's `_loadId` against the current run's load id to flag stale rows.
8. **Mapping `label` uniqueness is only enforced within `(connector, operation, direction,
   tablePath)`, and `Mappings.mappingNamed` silently resolves a duplicate label to whichever mapping
   it finds first**, with no ambiguity error (§1.2: `ebx/rest/Mappings.java:45-75`, contrasted with the
   `hits == 1` check in `ConnectorRepository.mappingFor` a few lines below it). Low risk if the
   factory disciplines itself to generate globally-unique labels (as recommended, § summary); the
   ebx-connectors-side fix would be to make `mappingNamed` raise on more than one match, matching the
   existing connector/operation disambiguation behaviour.

---

## 9. Read-recipe lot (2026-09-24) — the catalog's own recipe replaces per-connector-type generation

Branch `feature/landing-read-recipe`, worktree `ebx-mdm-factory-read-recipe`, from the shared contract
`read-recipe-contract.md` ("read recipe + deduced scan" lot, three agents in parallel: `ebx-data-catalog`
adds `Asset.readRecipe`/`scanOrigin`/`scanState` — contract section 1 —, `ebx-connectors` makes every
official catalog operation write a recipe and exposes `readOnly` on `connectors_describe` — section 2 —,
this repository, section 3, only the landing side). **Neither of the other two lots had landed on the live
instance yet at the time this was written** — `Asset` has no `readRecipe` field there today — so every
piece of this section was built and tested against the CONTRACT's fixed shape, not against a real scanned
recipe (the one live comparison available, project Vague1's hand-built Salesforce mapping 263, matches the
generated shape byte-for-byte — see below).

### 9.1 What changed

Rule decided with the product owner (contract's own "Why"): the landing generator no longer decides how to read a
table from the connector's TYPE (`LandingFlow.dialectFor`'s SQL dialects, `Table.fileFeed`'s file-connector
detection) — it turns whatever `readRecipe` the catalog already carries for the table's asset into a
Connectors mapping, mechanically. New pure class `landing.ReadRecipe`:

- `ReadRecipe.parse(String)` — the JSON shape (contract section 1): `{"connector","operation",
  "inputs":{...literal strings...},"itemsPath"}`. Refuses (never guesses) a blank/malformed document, a
  missing `connector`/`operation`, or a non-literal `inputs` value.
- `ReadRecipe.resolve(String pReadRecipeJson, String pScanState)` — the per-table decision (contract:
  "an asset without a recipe (or with `scanState != CONFIRMED`) -> that table FAILS with a clear
  message"), refined by the coordinator's own explicit preference for this lot (task brief): a table's
  `scanState` gates FIRST (`TO_CONFIRM` → "deduced table not confirmed yet", `LEFT_OUT` → a similar
  refusal, blank → treated `CONFIRMED` per the contract) — **then**, only once past that gate, a BLANK
  recipe (an asset scanned before the field existed, or the field itself not yet on this instance's
  catalog model) falls back to the legacy per-connector-type generator (`LEGACY_FALLBACK`) rather than
  failing outright, and a recipe that IS present but malformed FAILS (a broken recipe is a real,
  surprising state — never silently degraded to a possibly-wrong legacy guess).
- `LandingFlow.Table` gained a fifth, optional field, `recipe` (a new constructor overload; the two
  existing constructors are untouched, so every pre-existing caller/test keeps compiling and behaving
  exactly as before). `LandingFlow.mapping(Table)` now checks `pTable.recipe != null` FIRST: when set, it
  builds the mapping from `recipeMapping(Table, ReadRecipe)` and `connectorType`/`fileFeed` are ignored
  entirely (`dialectFor` is never reached, verified by `LandingFlowTest#checkRecipeMappingIgnoresConnectorTypeAndFileFeed`
  — a `connectorType` `dialectFor` would refuse still gets a mapping when a recipe is present).
- `recipeMapping` is structurally IDENTICAL to the existing `feedMapping` (§8) — same `hash`/`now`/
  `runid`/`concat` rule-transformation technical fields, same `feedKeyPredicate` — the only difference is
  that `connector`/`operation`/`inputs`/`itemsPath` come from the recipe instead of being hard-coded
  `"read"`/`inputs.path`. This was a deliberate reuse, not a rewrite: a recipe's operation answer is
  exactly the same shape a feed item already is (a flat JSON row, source path = the column name verbatim
  — contract: "source path = the column name as it appears in an item").
- `ebx.CatalogReader` gained `AssetRecipe`/`assetRecipe(Adaptation, int)`, reading `Asset.readRecipe`/
  `scanOrigin`/`scanState` the SAME defensive way every other optional field in that class already is
  (`CatalogReader.value`/`text`: a missing schema node answers `null`, never a thrown
  `PathAccessException` — CLAUDE.md section 9) — so calling this against today's live instance (no
  `readRecipe` field yet) answers an `AssetRecipe` with every string blank, i.e. exactly
  `LEGACY_FALLBACK`, not a crash.
- `ebx.LandingFlows.FlowTable` gained `catalogAssetId` (from `LandingTable.catalogAsset`, already written
  by `LandingPlanner`; since audit D-07, 2026-10-06, `catalogAssetKey`, the asset's natural key resolved by
  `CatalogReader.resolveAssetKey`); a new private `recipeEligibility(Adaptation, FlowTable)` resolves it once per
  table in both `generate` (the whole-source batch) and `generateOne` (lot KEY's per-table regeneration) —
  a `FAILED` eligibility is raised as an `OperationException` and lands in the SAME per-table
  catch/`writeFlowFailure` path every other table-level failure already uses, so the FLOW cell/step
  reporting (`ebx.LandingRun`, the "N of M table(s) failed" grid) needed no change to surface it. A
  `LEGACY_FALLBACK` table gets its `LandingTable.message` suffixed with `" WARN: legacy path: re-scan the
  source to get a read recipe."` (`writeFlowSuccess` gained a fourth, nullable `pWarn` parameter).

### 9.2 The SQL generator (now Snowflake + H2 + PostgreSQL) fallback — kept, and why

The contract allows removing it "IF removing them breaks tests" — it would break a large fraction of
`LandingFlowTest`'s existing pinned assertions (`checkMappingShapeWithKey`, `checkReservedWordColumn`,
`checkHostileColumnName`, `checkUnsafeIdentifiersRefused`, `checkSourceIdKeyFallback`,
`checkCompositeKeyEscapingAvoidsCollision`, `checkJdbcDialects`, …) and every caller that still constructs
a `Table` without a recipe. More importantly, the task brief's own explicit instruction overrides the
contract's stricter default ("an asset without a recipe … FAILS"): **"prefer: recipe first, then legacy
SQL/file path only when the asset has no recipe, with a WARN in the table message saying 'legacy path:
re-scan the source to get a read recipe'"** — implemented exactly as
`ReadRecipe.Eligibility.Kind.LEGACY_FALLBACK`. Kept: the SQL generator (`dialectFor`, whose `DIALECTS`
list grew from just `SnowflakeDialect` to `SnowflakeDialect`/`H2Dialect`/`PostgreSqlDialect` in
`d340dc9`/`bc63e4f`, merged into this branch from `main` after this lot's own work — §9.4's "Read-recipe
lot" text above predates that merge and originally only covered Snowflake) and the file-feed detection
(`Table.fileFeed`, `LandingFlows.isFileFeed`) are untouched, still reachable from `LandingFlow.mapping`
exactly as before (the recipe check in `mapping()` runs FIRST, unaffected by which dialects `dialectFor`
knows about), now reached only when a table's asset has no recipe. Once the `ebx-data-catalog`/
`ebx-connectors` lots land and every asset is (re)scanned with a recipe, this fallback becomes dead code
in practice but stays correct and tested, over every dialect it supports — removing it is a later,
separate cleanup once live data confirms no asset still needs it.

### 9.3 Reload

Contract item: "A landing reload of a source whose flows exist must REGENERATE its flows … pass force".
`ebx.ui.ProjectWizardUserService#generate` (the Advanced-disclosure "Generate the landing" button — the
per-source reload path; `lot LANDING`'s one-click run already calls the per-table `LandingFlows.generateOne`,
which never had a skip-if-done guard to begin with) called `LandingFlows.generate(..., false)` — changed to
`true`, so a repeated click always regenerates every table's flow from the CURRENT recipe/legacy logic
(the mapping upsert is idempotent on its label, so forcing safely overwrites what is already there — the
exact mechanism the bug this section's own opening paragraph, and `LandingFlow`'s own class Javadoc,
describe: "flows generated before a fix kept `./_sourceId='${SOURCE_ID}'` forever"). The REST route
(`FactoryResource`, `POST /sources/{id}/landing/flows?force=`) already threaded `pForce` from its caller
and needed no change.

### 9.4 Verified, and how

`tools/test_local.sh` — full module compile + every `*Test.java`, "all green", including the new
`ReadRecipeTest` (parse validation, `resolve`'s three-way branch: `RECIPE`/`LEGACY_FALLBACK`/`FAILED`,
`scanState` gating before the recipe is even looked at) and three new `LandingFlowTest` cases
(`checkRecipeMappingShape`, `checkRecipeMappingWithoutKeyHashesEverything`,
`checkRecipeMappingIgnoresConnectorTypeAndFileFeed`). `checkRecipeMappingShape`'s expected rule shapes
(`constant(SFDC)`/`runid`/`now`/`concat(...)|hash`/`concat(Id)`/`constant(NEW)`) were checked BY HAND
against the live, hand-built Salesforce mapping (project Vague1, `connectors_mapping_get(mapping="263")`,
read-only MCP call) and match exactly — the strongest available evidence this lot's mapping shape is
correct, short of a real recipe-driven run.

### 9.5 What was NOT run live, and every guess

- **No real `Asset.readRecipe` was ever read from a live instance** — the `ebx-data-catalog`/
  `ebx-connectors` lots that write it had not landed at the time of this work. `CatalogReader.assetRecipe`
  was exercised only against the pure `ReadRecipe` parser/resolver, never against a real `Asset` row with
  the field populated.
- **`generate`/`generateOne`'s new eligibility branch was read carefully, not run against Tomcat** — no
  live EBX instance was used write-capably this session (common brief's own read-only-MCP rule); only the
  one read-only `connectors_mapping_get(263)` call above.
- **Guess**: a malformed-but-present recipe FAILS rather than falling back to legacy — the contract's own
  wording doesn't cover this case explicitly (it only contrasts "no recipe" against "recipe present and
  valid"); judged the safer default (see 9.1), flagged here in case the coordinator wants malformed
  recipes to fall back too.
- **Guess**: `LEFT_OUT`'s failure message ("left out of the scan review: not a landing source") is not a
  literal quote from the contract (only `TO_CONFIRM`'s message is given verbatim there) — worded to match
  the contract's own tone; may need to be revised once the wizard's step-2 (deduced scan review) screens,
  built by the sibling lot, settle on their own wording for this state.
- **Guess**: `scanOrigin` plays no role in `ReadRecipe.resolve` beyond what `scanState` already encodes
  (an OFFICIAL asset is "always CONFIRMED" per the contract, so only a DEDUCED asset can ever reach
  `TO_CONFIRM`/`LEFT_OUT`) — the parameter was dropped from `resolve`'s signature rather than carried
  unused; if a future lot needs to message differently for "never scanned officially" vs. "deduced,
  pending", `CatalogReader.AssetRecipe.scanOrigin` is still read and available on the caller side.
- **The composite-key column-name-as-source-path rule was not specially re-tested for a recipe with
  DOTTED source paths** (contract: "nested objects are flattened with dotted paths … and the same dotted
  path is the Column name") — `LandingColumn.sourceColumn`/`LandingFlow.Column.sourceColumn` are opaque
  strings to this lot's code either way (used verbatim as a rule's `sourcePath`, contract-compliant by
  construction), so a dotted path needs no special handling here, but this was reasoned, not run against
  a real deduced-scan column.

### 9.6 Known gap (fix lot, 2026-09-25): a rule is generated for every landing column, even one the recipe never reads

Found live 2026-09-25, right after deploying the deduced-scan lot: `recipeMapping` (§9.1) generates one
rule per LANDING column — i.e. per `LandingColumn` row the plan already knows about — never per column the
recipe's `readRecipe.inputs`/`operation` actually returns. For a Salesforce table whose landing plan
includes the compound fields `BillingAddress`/`ShippingAddress` (present in the DESCRIBE-derived column
list, but absent from the recipe's own SOQL — contract section 2: "compound address/location fields
excluded, they fail in SOQL SELECT with their parts present"), the generated mapping still carries a rule
for each of them, with a source path that the feed's own items will never actually contain. Harmless in
practice (the rule simply reads an absent path and lands blank/no-op — `LandingFlow`'s per-column reads
already tolerate a missing key), but structurally wrong: the mapping documents columns the connector
provably never sends.

**Why this was left as-is rather than fixed**: the ONLY generic way to know which columns a recipe
actually reads is to parse `readRecipe.inputs`/the operation's own query text (SOQL, SQL, a file's header
row…) — and the contract is explicit that the landing side must not do this ("you cannot know them
generically - so do NOT parse SOQL/SQL", "no test on a column/table NAME or business word anywhere"; §2's
own "Product rules that stay" repeats it: "Java only measures"). The connector that WRITES the recipe is
the only place that legitimately knows its own query shape (contract section 2, "Salesforce -> its SOQL
query operation over every catalogued field (compound address/location fields excluded…)") - if the recipe
contract ever grows a declared `columns`/`fields` list (the set the operation actually returns, as opposed
to every column the catalog scanned), `recipeMapping` could filter its per-column rules against it; until
then, this is a documented, accepted gap, not a bug to chase in this module.

## What this investigation could not determine

- Whether `/root/Companies.id` (or any target table outside this module) is itself
  `osd:autoIncrement` — out of scope of `ebx-connectors`'s own source; not relevant to landing anyway
  since `_sourceId` is an explicitly-ruled key, not an auto-increment id (§1.5).
- A real throughput number for the `query`/import-feed path (§7) — genuinely undocumented; needs a
  timed run against the target instance, not a code read.
- Whether the UI/graphical designer (`MappingDesignerUserService`) imposes additional validation
  beyond what `connectors_mapping_upsert`/`ConfigTransfer` enforce — out of scope, since the factory
  only ever goes through the MCP/REST path.

## H2 and PostgreSQL sources (2026-09-24, PersVol volume test)

`LandingFlow.dialectFor` knew Snowflake only, so a JDBC source stopped at "No landing SQL generator for connector type
'h2'" after its landing model was built. `H2Dialect` (measured live against H2 2.1.212: `CAST(x AS VARCHAR)`,
`LOWER(RAWTOHEX(HASH('MD5', x)))` = the same lower-case hex MD5 as Snowflake's, `CHAR(92)`/`CHAR(124)`) and
`PostgreSqlDialect` (`postgresql`, `greenplum`: `MD5`, `CHR`; not run live yet) join it. SQL Server and MySQL stay
refused: the generated SQL joins with `||`, which neither speaks by default. The flow runs Connectors' JDBC `query`,
which needed its own fix for drivers that never report read-only mode (H2 over TCP): `ebx-connectors`
`fix/jdbc-query-readonly-tolerant`.

## Lot 8a (2026-09-28, volume-operations) — `provenance`/`unchangedIfEqual` on every landing mapping

`LandingFlow.mapping`/`feedMapping`/`recipeMapping` now set two mapping properties Connectors lot 5 added
(merged in the Connectors integration branch this Factory lot is deployed alongside):

- `"provenance": false` - skips writing a `FieldProvenance` row per changed field for this mapping's writes.
  CLAUDE.md sec 15's own measurement ("Where the time goes in Generate the landing") found
  `ConnectorRepository.recordProvenance` at roughly 2/3 of one load's total per-row cost (one XPath scan per
  changed field, plus one row created per field, on a 61,514-row `FieldProvenance` table that only grows).
  Nothing in this module reads a landing row's field-level provenance today (the `master.*` classes read
  `Xref`/`RecordMetadata`/the master row's own fields, never `FieldProvenance`) - turning it off for landing
  writes specifically removes a real, measured cost for a bookkeeping table nothing consumes.
- `"unchangedIfEqual": "./_rowHash"` - when the existing record's `_rowHash` already equals the incoming row's
  own (freshly recomputed) `_rowHash`, the row is `NO_CHANGE` and NOTHING is written - not even the two fields
  every landing mapping's rules always set unconditionally before this lot, `_loadId`/`_receivedAt` (both rule
  `policy: ALWAYS`, per `LandingFlow`'s own "2026-09-23, incremental-transfer lot" comment on the `_status`
  rule). **This changes what those two fields mean for an unchanged row**: before this lot, every reload
  (`connectors_sync`, whether or not the source data actually changed) refreshed `_loadId`/`_receivedAt` to
  the CURRENT run even for a row whose value was identical - `_loadId` therefore used to mean "the last run
  that TOUCHED this row", not "the last run that actually changed it". After this lot, an unchanged row keeps
  the `_loadId`/`_receivedAt` of whichever run last genuinely changed it (or created it) - a more honest
  reading of "when did this landing row's data last change", at the cost of losing "was this row re-confirmed
  present on the source as of the latest run" (a row's own presence is still confirmed by the source query/feed
  itself returning it at all - only the recorded moment stops advancing).
- **Incremental staging is unaffected, on purpose.** `_status` (`transfer.IncrementalTransfer`,
  `docs/INCREMENTAL-TRANSFER.md`) is written by a SEPARATE rule (`constant(NEW)`, `policy: ALWAYS` already,
  unchanged by this lot) that this Factory's own mapping generation controls independently of
  `unchangedIfEqual` - but `unchangedIfEqual`'s own `NO_CHANGE` verdict means the WHOLE row write is skipped,
  `_status` rule included: an unchanged row's `_status` is left exactly as the last real transfer left it
  (typically `TRANSFERRED`, if staging already picked it up) rather than being reset to `NEW` on every reload
  that finds it unchanged. This is the correct behaviour for incremental staging - a row nothing changed has
  nothing new to re-stage - but it is a real, deliberate difference from the pre-lot behaviour, where every
  reload reset every row's `_status` back to `NEW` whether or not anything about it had actually changed
  (relying entirely on `StagingLoader`'s own `_sourceHash` comparison, not on `_status`, to skip a genuinely
  unchanged row during transfer - see `docs/INCREMENTAL-TRANSFER.md`). The net effect for staging is the same
  either way (an unchanged row is still not re-transferred), but `_status` itself now more accurately reflects
  "has this row been staged since it last changed" instead of always reading `NEW` right after a reload.
- **Not verified live** (no EBX/Connectors instance reachable this session): the two property names and their
  exact semantics come from the Connectors lot 5 brief handed to this lot, never from a live
  `connectors_mapping_upsert`/`connectors_sync` probe. Verify after deployment: reload an unchanged source row
  twice and confirm no `FieldProvenance` rows are created for it and its `_loadId`/`_receivedAt` do not advance
  on the second run; change one column and confirm the row IS rewritten (including `_loadId`/`_receivedAt`)
  and stages normally.

## Lot 7c (2026-09-28, volume-async-landing) — the load call itself is now asynchronous, polled

`ebx.LandingFlows.load` (§5 above, and the "Recommended design" worked example at the top of this document)
used to call `connectors_sync(mapping, inputs, max)` synchronously and block until the whole table's copy
finished - fine at the row counts every earlier lot measured against, a real problem once a landing table
reaches hundreds of thousands to millions of rows (the same volume-scalability concern `docs/VOLUME.md`'s
earlier lots address for the rest of this module). `ebx-connectors`' own `integration/volume-wave1` branch adds
exactly the asynchronous contract this needed (volume lot 7a, "streamed read, summary answer, asynchronous
run", that repository's `docs/REFERENCE.md` &sect;2.14.5) - `LandingFlow.mapping`/`feedMapping`/`recipeMapping`
themselves are UNCHANGED by this lot (nothing about the mapping DOCUMENT needed to change, only how `load` runs
it), so every fact this document states about the generated `sql`/rules/technical fields still holds exactly
as written above.

**What changed, `ebx.LandingFlows.load`**:

- The `connectors_sync` call for each table now sets `"async": true`. Two possible answers, told apart
  structurally by `landing.RunStateOutcome.isAsyncStart` (never from a Connectors version/build number, per
  this lot's own instruction):
  - The async-start acknowledgement - `{runId, status: "RUNNING", mapping, feed: true, async: true, nextStep}`
    (no `count` field at all, the tell) - the normal case on a build carrying ebx-connectors' own volume lot 7a.
  - The complete, already-terminal synchronous answer this document's §5.1 already describes (always carries
    `count`) - what an OLDER Connectors build (one that does not know `async` and ignores it) still answers.
    `LandingFlow.loadOutcome` (unchanged) reads this exactly as it always has.
- On the async-start acknowledgement, `ebx.LandingFlows.awaitLoad` polls `connectors_run_get(runId)` every 5
  seconds (`LandingFlows.LOAD_POLL_INTERVAL_MS`, a plain constant) until its `execution.status` (ebx-connectors
  &sect;2.14.5: `RUNNING`/`DONE`/`PARTIAL`/`ERROR`/`STOPPED`/`INTERRUPTED`) is terminal. `execution{}`'s running
  counters (`created`/`updated`/`noChange`/`errorCount`) feed the landing wizard's live-progress note while
  still `RUNNING`; once terminal, `landing.RunStateOutcome.outcome` maps `execution{}` onto the SAME
  `LandingFlow.LoadOutcome` shape a synchronous answer produces - `count = created + updated + noChange`,
  `errors = errorCount`, `truncated`/`available` fed to the same `LoadOutcomeRule` (`docs/VOLUME.md` "Lot 1b"
  task 1) a synchronous answer already used, so a truncated/incomplete async load still fails the table exactly
  as a truncated synchronous one always did.
- **Cancellation reaches a table WHILE it is loading now**, not only between tables/stages: `load` gained a
  `FactoryJobs.Handle`-accepting overload, checked before starting a new table's run and, inside the poll loop,
  between polls of one already running. On a stop, `connectors_run_stop(runId)` is called (best-effort) and the
  whole `LANDING_LOAD` `PipelineStep` ends `STOPPED` (a third `status` value on `load`'s own returned
  `ObjectNode`, alongside the pre-existing `DONE`/`FAILED`) rather than starting the next table.
  `INTERRUPTED` (ebx-connectors' own `markInterruptedRunningRuns` - a server restart while the run was RUNNING)
  is mapped to `LandingTable` status `FAILED` with a message inviting a plain re-run; `nextIndex` (the resume
  point, when the terminal `execution{}` carries one) is recorded on the table's own `PipelineStep.detail` entry
  for a later lot to use - automatic resume was explicitly left out of this one.
- **`PipelineStep.detail` no longer stores the whole answer.** Before this lot the entire `connectors_sync`
  result (`results[]`, up to 1000 entries, included) was serialised per table into the SAME JSON document every
  OTHER table's own detail also lives in - truncated at `TextLimits.DETAIL_MAX_LENGTH` the moment a handful of
  tables pushed it past that, silently losing later tables' detail. `LandingFlows.summaryDetail` keeps every
  top-level scalar field (an array is dropped, never copied whole) plus at most 5 non-OK/non-NO_CHANGE error
  messages instead.
- **§5.1/§5.2 above (what a `connectors_sync`/`connectors_run_get` answer exposes) are otherwise unaffected** by
  this lot for the SYNCHRONOUS shape - only the NEW `execution{}` shape (§2.14.5 of ebx-connectors'
  `docs/REFERENCE.md`) is genuinely new material this document did not cover before. `connectors_call` (the
  landing preview/key-study/deduced-scan paths) is untouched - ebx-connectors' own volume lot 7a scope is
  `connectors_sync`'s feed-import path only.

**Not verified live** (no EBX/Connectors instance reachable this session, and Connectors access was read-only
MCP tools only per this lot's own instructions): the whole `async`/`execution{}` contract comes from
`ebx-connectors`' own `docs/REFERENCE.md` &sect;2.14.5, itself written without a live instance either - see
`docs/VOLUME.md` "Lot 7c"'s own "What was NOT verified" section for the full list. Verify after deployment: a
landing load on a table large enough to take more than one poll interval shows live progress and finishes with
the right row count; stopping it mid-load via the wizard's Stop button marks the `LANDING_LOAD` step `STOPPED`
and the table `FAILED` with a "re-run to continue" message; an older Connectors build (no `async` support)
still loads correctly through the unchanged synchronous fallback.
