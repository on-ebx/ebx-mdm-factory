# Where DECLARED source relations come from — a study

Research of 2026-09-20, branch `feature/source-fk-study`, revised the same day after coordinator
review corrected an error in §5 (see the note at the top of §5). Read-only throughout: no file outside
this worktree was modified, no `connectors_call` / `connectors_sync` / `connectors_catalog_source` /
any write tool was invoked; `~/Fasttrack` was read (never written) via `javap` against the given
`ebx-lib.jar` path and, for the revision, the shipped `onHelp` documentation under
`~/Fasttrack/Test 6.3.0/...`, both explicitly authorized. This document is research plus a design
recommendation; it contains no production code.

**Evidence tags**, used on every factual claim:

- **READ `path:line`** — read directly in the named repository's source, doc, or generated-schema
  script, in this session.
- **READ (live)** — a real, read-only `data-access` MCP call against the running `DataCatalog`
  dataspace in this session (no write tool used).
- **VERIFIED (javap)** — a class/interface signature checked with `javap` directly against
  `.../ebx-ps-fasttrack-server/lib/ebx-lib.jar` (the exact path given in the task) or against the
  local JDK (`java.sql.*`, a JDK-standard interface, not EBX-specific).
- **INFERRED** — a conclusion not present in any of the three repos or confirmed live; for a vendor
  platform fact, the vendor documentation page title relied on is named, and it was **not**
  independently re-verified against a live Snowflake/Databricks account (the task forbade the calls
  that would do that safely).

Repositories read: `~/Dev/ebx-connectors` (module `ebx-connectors`), `~/Dev/ebx-data-catalog`
(module `ebx-data-catalog`), and this worktree (`ebx-mdm-factory`, branch `feature/source-fk-study`
off `main`). the internal EBX MCP guide §14 was read first, as pointed to by
the task.

A companion branch `feature/landing-relations` exists (`git branch -a` in this worktree) but, at the
time of this study, `git rev-parse feature/landing-relations` equals `git rev-parse
feature/source-fk-study` — the other agent has not yet committed anything. Section 3's design is
therefore proposed from scratch, not read from that branch.

---

## Recommendation (summary)

1. **SQL-family connectors (§1).** Add foreign-key reading as three small, independent changes,
   ranked by payoff/cost: (a) one shared change in `JdbcConnector.catalog()` — `getImportedKeys` next
   to the `getPrimaryKeys` call already there — covers PostgreSQL, MySQL, Oracle, SQL Server, H2, DB2
   and every other plain-JDBC connector at once; (b) one new optional `Dialect.foreignKeysSql(...)`
   method (same shape as the already-optional `usageSql()`/`lineageSql()`) implemented for Snowflake
   and Databricks, costing **one extra SQL statement per scan**, not per table; (c) nothing changes in
   how connectors *read* the columns — only how they read the constraints.
2. **Non-SQL sources (§2).** Salesforce already reads `referenceTo` — promote it from free-text
   `remarks` into a structured field, no new API call. OData/SAP has no `catalog` operation at all
   today: this is a new capability, not a small addition — flagged as a separate, larger gap.
3. **Catalog storage (§3).** Add a new `Relation` table, not `Column` fields, not a `LineageEdge`
   reuse. `Column` fields cannot express a composite key faithfully; `LineageEdge` already overloads
   the word `DECLARED` for something else entirely (a data-flow-definition origin, not a schema
   constraint) and is shaped for observed/declared **data movement**, not an at-rest **structural**
   fact — reusing it would either collide on vocabulary or require the same composite-key grouping
   machinery a dedicated table gives for free.
4. **Factory (§4).** Add `CatalogReader.Relation` + a `relations()` reader, following the exact
   graceful-degradation idiom `CatalogReader.java` already uses for every other field.
5. **Value-based inference (§5, revised).** A Java-callable equivalent of EBX Script's `db.createQuery`
   *does* exist — `com.orchestranetworks.query.{Query,QueryBuilder,QueryResult,Tuple}` and
   `Adaptation.createQuery(String)`/`createQueryBuilder()` (the original version of this document said
   otherwise; that was a missed package, corrected below). Run the orphan check as **one SQL anti-join
   per relation, inside EBX**, on the loaded landing tables — validated live in this session
   (`LEFT JOIN` + `COUNT(DISTINCT CASE WHEN ... END)` + a `FETCH NEXT 10 ROWS ONLY` sample). The
   two-pass `RequestResult`/`HashSet` scan is now a documented fallback, and source pushdown an
   optional pre-load check.
6. **Lot plan**: §6.

---

## 1. SQL-family connectors: how PK/columns are read today, and the smallest FK addition

### 1.1 The shared, hardcoded gap

Snowflake, Databricks and every plain-JDBC connector produce the same document shape
(`CatalogOperations.java:20-27`, the class javadoc). Two of the three paths already read primary keys
correctly; **one path — the one shared by Snowflake and Databricks — never does**:

`CatalogOperations.catalog()` builds each column with
`c.put("primaryKey", false);` — READ `CatalogOperations.java:286`, unconditional, no branch. This is
the method both `SnowflakeConnector` (`SnowflakeConnector.java:172`, `case "catalog": return
com.on.ebx.connect.core.CatalogOperations.catalog(runner, dialect, pInputs, schema);`) and
`DatabricksConnector` (`DatabricksConnector.java:181`, same call shape) delegate to. Confirmed **READ
(live)**: every column of the real `SNOW_DEMO` scan of `SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER`
(catalog `Source.id = SNOW_DEMO`, `Asset.id = 495`, matching `PLAN.md`'s own note about assets
495-497) has `primaryKey: false`, including `SUPPLIER_ID` — a column plainly named as the table's key.
Neither Snowflake nor Databricks reads *any* key metadata today, primary or foreign.

By contrast, `JdbcConnector.catalog()` (the generic path used by PostgreSQL, MySQL, Oracle, SQL
Server and every other `JdbcConnector` subclass with no override — confirmed by grep: none of
`PostgreSqlConnector.java`, `MySqlConnector.java`, `OracleDatabaseConnector.java`,
`MicrosoftSqlConnectorBase.java` override `catalog()` or call `CatalogOperations`) already calls
`DatabaseMetaData.getPrimaryKeys` per table and sets `primaryKey` correctly:

```
final ResultSet pk = meta.getPrimaryKeys(this.usesCatalogMetadata() ? table.schema : null,
    this.usesCatalogMetadata() ? null : table.schema, table.name);
...
c.put("primaryKey", keys.contains(name));
```
READ `JdbcConnector.java:1023-1025,1047`.

No connector anywhere in `ebx-connectors` calls `getImportedKeys`, `getExportedKeys` or
`getCrossReference` (grepped `*.java` under `connectors/`: zero matches) — the task's stated "known
fact" (only the `ebx` connector emits FKs, as lineage) is confirmed for every SQL-family connector too.

### 1.2 Snowflake

- `catalog`/`profile`/`sample`/`usage`/`lineage` all go through
  `com.on.ebx.connect.core.CatalogOperations` with a `SnowflakeConnector.SnowflakeDialect`
  (`SnowflakeConnector.java:157-178,234-308`).
- `schemasSql()` (256), `tablesSql()` (259-263), `columnsSql()` (265-269) read
  `INFORMATION_SCHEMA.{SCHEMATA,TABLES,COLUMNS}` of the connector's own database, or of `pDatabase`
  when a schema is requested as `DB.SCHEMA` (`infoSchema()`, 248-252).
- `usageSql()`/`lineageSql()` (271-284) read `SNOWFLAKE.ACCOUNT_USAGE.ACCESS_HISTORY`, which the
  class javadoc says "needs the IMPORTED PRIVILEGES on the SNOWFLAKE database and lags by up to 3
  hours" — READ `SnowflakeConnector.java:230-232`.
- **Smallest addition**: a new optional `Dialect.foreignKeysSql(String pDatabase, List<String>
  pSchemas)` method, following the exact pattern already used for the two other optional methods
  (`usageSql()`/`lineageSql()`, both documented "null when unsupported" — READ
  `CatalogOperations.java:61,64`), implemented in `SnowflakeDialect` as a plain `SELECT` joining
  Snowflake's `INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS`, `TABLE_CONSTRAINTS` and
  `KEY_COLUMN_USAGE` views — INFERRED, citing Snowflake documentation "Snowflake Information
  Schema" / "REFERENTIAL_CONSTRAINTS view" / "TABLE_CONSTRAINTS view" / "KEY_COLUMN_USAGE view"; not
  re-verified against a live account. Snowflake keys are **declared but not enforced** (the task's own
  framing, consistent with Snowflake's documented constraint model — INFERRED, "Snowflake
  documentation: Constraints").
- **Cost**: `CatalogOperations.catalog()` already loops `CatalogOperations.byDatabase(schemas)` and
  issues exactly one `tablesSql` + one `columnsSql` call **per database group**, not per table (READ
  `CatalogOperations.java:243-291`). A `foreignKeysSql()` call fits the same loop: **one extra SQL
  statement per database in the schema filter**, typically one per scan — far cheaper than the
  per-table shape the generic-JDBC path needs (§1.4).
- **Privilege**: `INFORMATION_SCHEMA` visibility mirrors the same object-level privilege `TABLES`/
  `COLUMNS` already require — unlike `ACCOUNT_USAGE`, which needs the elevated `IMPORTED PRIVILEGES`
  grant the code comment above already flags as a special case. INFERRED (no elevated grant needed),
  not measured live.
- **Composite keys**: `KEY_COLUMN_USAGE.ORDINAL_POSITION` (source side) joined to the referenced
  constraint's `POSITION_IN_UNIQUE_CONSTRAINT` orders composite-key columns — INFERRED, same Snowflake
  Information Schema documentation. This still needs the rows grouped by `CONSTRAINT_NAME`
  downstream; a bare `Column.primaryKey`-style boolean cannot carry that grouping (feeds §3).
- Alternative considered: `SHOW IMPORTED KEYS IN SCHEMA <schema>` (INFERRED, Snowflake documentation
  "SHOW IMPORTED KEYS"). Rejected as the primary recommendation: every other `Dialect` method returns
  a plain `SELECT` string handed to the same `SqlRunner.run(String)` (READ
  `CatalogOperations.java:41-44`); a `SHOW` command returns a different result shape and would need
  its own execution path or a `RESULT_SCAN()` wrapper — more new code for the same information the
  `INFORMATION_SCHEMA` views already give as plain `SELECT`s.

### 1.3 Databricks (Unity Catalog)

- Same delegation shape: `DatabricksConnector.java:166-183` builds a `SqlRunner` and a
  `DatabricksDialect`, calling the same shared `CatalogOperations` methods.
- `schemasSql()`/`tablesSql()`/`columnsSql()` (`DatabricksConnector.java:269-284`) read
  `information_schema.{schemata,tables,columns}` of the connector's `catalog` property (Unity
  Catalog's three-level `catalog.schema.table` namespace).
- `usageSql()`/`lineageSql()` (287-298) read `system.access.table_lineage` /
  `system.access.column_lineage`, documented as needing "the system schema enabled and SELECT on it"
  — READ `DatabricksConnector.java:240-241`.
- **Smallest addition**: the same `foreignKeysSql()` extension, reading Unity Catalog's own
  `information_schema.table_constraints` + `information_schema.key_column_usage` +
  `information_schema.constraint_column_usage` — INFERRED, citing Databricks documentation
  "Information schema" (the `table_constraints`/`key_column_usage`/`constraint_column_usage` tables)
  and "Declare primary key and foreign key relationships" (Unity Catalog constraints are declared via
  `ALTER TABLE ... ADD CONSTRAINT`, and are **`NOT ENFORCED`** by default — same INFERRED source).
  Not re-verified against a live workspace.
- **Cost**: same shape as Snowflake — one extra SQL statement per catalog group, not per table.
- **Caveat (INFERRED)**: a Unity Catalog PK/FK constraint only exists if someone explicitly ran
  `ALTER TABLE ... ADD CONSTRAINT`. Most lakehouse tables written by Spark/dbt pipelines will have
  none declared even where a real relationship exists — for Databricks sources, value-based inference
  (§5) is likely to matter at least as much as constraint reading, arguably more so than for a
  traditional RDBMS.

### 1.4 Generic JDBC (PostgreSQL, MySQL, Oracle, SQL Server, and the rest)

- `JdbcConnector.catalog()` (`JdbcConnector.java:998-1090`) is the single shared implementation.
  Confirmed no subclass overrides it for PostgreSQL, MySQL, Oracle, or the Microsoft SQL Server base
  class (grepped `enrichCatalogTable|usesCatalogMetadata|extends JdbcConnector` across those four
  files — only `SqlServerConnector.java:11-14` overrides the separate, additive
  `enrichCatalogTable` hook, not `catalog()` itself).
- **Smallest addition**: right after the existing `meta.getPrimaryKeys(...)` block
  (`JdbcConnector.java:1023-1026`), add one `meta.getImportedKeys(catalog, schema, table)` call per
  table and emit the rows as a `foreignKeys` array on the table JSON. **One shared change in the base
  class benefits every JDBC-family connector at once** — this is the single highest-leverage change
  in this whole study.
- **Verified (javap, JDK)**: `java.sql.DatabaseMetaData.getImportedKeys(String, String, String)`
  exists, alongside `getPrimaryKeys`/`getExportedKeys`/`getCrossReference` (all already `abstract`
  methods on the same interface `JdbcConnector.java:1023` already calls). The JDBC standard exposes
  no schema-wide "all foreign keys" call — `getImportedKeys`, `getExportedKeys` and
  `getCrossReference` are all scoped to at most one table (or one table pair); this is a property of
  the JDBC API itself, not something a driver could work around.
- **Composite keys**: unlike the Snowflake/Databricks `INFORMATION_SCHEMA` route, the JDBC
  `ResultSet` columns for `getImportedKeys` include `KEY_SEQ` (ordinal position within the key) and
  `FK_NAME` (the constraint name) as part of the JDBC 4.x contract — rows are already naturally
  groupable by `FK_NAME` with `KEY_SEQ` giving column order, with no extra join needed. This is a
  documented part of the `java.sql.DatabaseMetaData.getImportedKeys` contract; not independently
  re-verified column-by-column here beyond the method signature itself.
- **Cost**: today's `catalog()` already pays 2 metadata round trips per table
  (`getPrimaryKeys` + `getColumns`, `JdbcConnector.java:1023,1028`); adding `getImportedKeys` makes it
  3 — the same order of magnitude the operation already pays, not a new cost regime. For a
  many-hundred-table schema this is the more expensive shape compared to Snowflake/Databricks'
  one-call-per-database-group approach, but it is the only mechanism the JDBC standard offers.
- **SQL Server** additionally has `enrichCatalogTable` → `SqlServerMetadata.read` (`SqlServerConnector.java:11-14`,
  `SqlServerMetadata.java`), an established, already-shipping pattern for *vendor-specific* extras
  beyond plain JDBC metadata (there it reads `sys.extended_properties` and
  `sys.dm_db_index_usage_stats`, with a `status` enum — `VISIBLE_METADATA_ONLY` / `ACCESS_DENIED` /
  `UNAVAILABLE` / `LIMIT_EXCEEDED` — and a `sourceMetadataWarnings` array for graceful degradation).
  If the team ever wants constraint names or `ON DELETE`/`ON UPDATE` rules beyond what
  `getImportedKeys`'s own `FK_NAME`/`UPDATE_RULE`/`DELETE_RULE` columns already give, this hook — not
  a new mechanism — is where it goes. Not required for the base FK-reading addition above.
- PostgreSQL, MySQL, Oracle: no vendor-specific hook exists today; nothing repo-specific to add beyond
  the one shared `JdbcConnector.catalog()` change.

---

## 2. Non-SQL sources: what's already read, what's dropped

### 2.1 Salesforce — already reads it, drops it as text

`SalesforceCatalog.catalog()` reads each field's `referenceTo` (a Salesforce **array**: a
lookup/master-detail field can be polymorphic, pointing at more than one possible sObject) directly
off the same `describe()` call already used for everything else, and appends it to free-text
`remarks`:

```
final JsonNode refs = f.path("referenceTo");
if (refs.isArray() && refs.size() > 0)
{
    remarks.append(" → ");
    for (int i = 0; i < refs.size(); i++) { remarks.append(i == 0 ? "" : ", ").append(refs.get(i).asText()); }
}
c.put("remarks", remarks.toString());
```
READ `SalesforceCatalog.java:192-202`. The `Id` field is correctly marked as the (single, always
present) primary key: `c.put("primaryKey", "id".equalsIgnoreCase(type));` — READ
`SalesforceCatalog.java:191`.

**Smallest addition**: write `referenceTo` into a real structured field (an array, for the polymorphic
case) alongside the existing remarks text — zero new API calls, the data is already in hand at
`SalesforceCatalog.java:193`. **Caveat**: schema-level discovery can only enumerate the *candidate*
target objects of a polymorphic reference; which one a given record actually points to needs the
record's own type-discriminator field (Salesforce's own metadata for this) or the record itself — not
resolvable from `describe()` alone. This is the concrete instance of "the referenced table not
scanned" edge case discussed in §3.

### 2.2 OData / SAP — no catalog operation at all

`SapODataConnector.java` (358 lines, `extends AbstractRestConnector`) has **no** `catalog` operation:
`grep -n "Operation.search(\"catalog\"" SapODataConnector.java` matches nothing, and the class is
neither a `JdbcConnector` subclass nor a `CatalogOperations` caller. OData/SAP objects are not in the
data catalog today, structurally or otherwise — not "relations are dropped", but "there is no
structure scan to drop them from". Building one would mean parsing the OData `$metadata` document
(CSDL XML: `EntityType`, `Property`, `NavigationProperty`, `ReferentialConstraint` elements) from
scratch — genuinely new code, not an extension of an existing catalog path, and out of scope for a
"smallest addition" answer. Flagged as a real gap, sized separately in §6.

### 2.3 The `ebx` connector — already has structured FKs, in two different places

Confirmed the task's framing precisely, with exact locations:

- `EbxCatalog.catalog()` already reads a **structured, column-level `foreignKey` object** from the
  EBX metamodel (`?includeMetamodel=true`, READ `EbxCatalog.java:300`):
  ```
  final JsonNode fk = f.get("foreignKey");
  if (fk != null && fk.isObject())
  {
      nativeType = "foreignKey -> " + (ds.isEmpty() ? "" : ds + "/") + (dataset.isEmpty() ? "" : dataset) + fk.path("tablePath").asText("");
  }
  ...
  if (fk != null && fk.isObject()) c.set("foreignKey", fk);
  ```
  READ `EbxCatalog.java:356-361,389`. It also reads the table's **whole primary-key array** (not just
  a boolean), `primaryKeyFields`, into `table.primaryKeys` — READ `EbxCatalog.java:324-330`. This is
  richer than every SQL-family connector's per-column boolean.
- This `foreignKey` object is **silently dropped by the catalog importer**: the `columns` mapping's
  rule list has no `foreignKey` entry (READ `CatalogFlows.java:156-168` — every rule enumerated:
  `name`, `columnKey`, `position`, `dataType`, `nativeType`, `nullable`, `primaryKey`, `expression`,
  `remarks`, `scannedAt`, `status`). `Column` genuinely has no foreign-key notion today, exactly as
  the task states.
- Separately, `EbxCatalog.lineage()` (READ `EbxCatalog.java:537-592`) turns the *same* `foreignKey`
  object into `LineageEdge` rows: `transformationType = IDENTITY`, `origin = PLATFORM`,
  `confidence = 100` (READ `EbxCatalog.java:583-587`). This **is** consumed by the catalog importer,
  via a `lineage` mapping whose description literally says, for the `ebx` type: `"Foreign keys between
  the tables of the data models of source " + pSourceId + " (one column edge per foreign key, origin
  PLATFORM)."` — READ `CatalogFlows.java:397-403` (description built at line 401).
- **Composite EBX primary keys are silently dropped from FK lineage.** `lineage()` only emits an edge
  when the target's `primaryKeys` array has exactly one entry:
  ```
  if (target == null || target.get("primaryKeys") == null || target.get("primaryKeys").size() != 1) continue;
  ```
  READ `EbxCatalog.java:570`. A foreign key pointing at a table with a composite primary key is
  silently skipped — no warning, no partial edge. This is a real, present-day limitation, not
  hypothetical.
- **READ (live) confirmation**: `LineageEdge` rows with `origin = PLATFORM` exist in the running
  `DataCatalog` dataset, e.g.
  `EBX_TEST:Geographies/Geographies/root/Country.Alpha2Code>EBX_TEST:Companies/Companies/root/Companies.Address/Country`
  (`transformationType: IDENTITY`) — `Companies.Address/Country` correctly resolves to
  `Geographies/Country`'s single-field primary key `Alpha2Code`. Several more such edges exist for
  `Currencies`, `LegalForm`, `RegistrationAuthority`, `Household`/`Address` — all single-column
  targets, consistent with the composite-key limitation above (none of these could be composite and
  still show up).

**Implication for §3/§6**: a `Relation`-table design can read the `ebx` connector's already-present
`column.foreignKey` object directly (a *new* mapping on the existing `catalog` document, not a new
connector capability) without touching `lineage()`'s already-working, already-verified-live behavior
at all — see §6, lot 4.

### 2.4 Explicitly out of scope

Kafka (`KafkaCatalog`, structure inferred from message content, no schema declaration — READ
`GUIDE.md:581-599`) and Power BI (`PowerBiConnector`, BI semantic-model lineage via Power Query/DAX,
not a row-level FK notion — READ `GUIDE.md:293-` §3.5) have no source-declared relation concept to
read. Not analyzed further; noted so their absence isn't mistaken for an oversight.

---

## 3. Where a relation should travel, and the smallest catalog storage change

### 3.1 The document shape

The new structure travels the same way `foreignKey` already does for the `ebx` connector
(`EbxCatalog.java:389`) and the way `primaryKeys` already does at the table level
(`EbxCatalog.java:324`): as a new array **on the `catalog` document**, either per-table
(`table.foreignKeys: [...]`, one entry per constraint, each with a repeatable `columns` list for
composite keys) or, for the JDBC/Snowflake/Databricks additions in §1, a flat top-level array keyed by
`assetKey`/`columnKey` the way `edges` already is for `lineage()`. Either is a small, additive change
to the JSON contract `CatalogOperations.java:20-27` already documents.

### 3.2 Storage options, evaluated

**(a) Fields on `Column`** (`referencesAsset`, `referencesColumn`). Cheapest: reuses the existing
`columns` mapping (`CatalogFlows.java:156-168`), no new table, no new XSD block. **Fails composite
keys outright**: a `Column` row is one physical column; a 2-column composite FK needs two `Column`
rows that must be read back *together* and re-associated by whatever reads them — nothing on `Column`
groups them unless a `constraintName`-like field is added, at which point half of a dedicated
constraint table has been reinvented anyway, just spread across N denormalized `Column` rows instead
of one row per constraint. Also awkward for an unscanned target: `referencesAsset` would need to be a
free-text qualified name (no `Asset` row may exist yet to point a real FK at), which is fine, but
still doesn't solve the composite-key problem. **Verdict**: acceptable only as an explicit,
documented single-column-only shortcut, not the primary design.

**(b) A new `Relation` table**, one row per **constraint** (not per column pair), with a repeatable
sub-list of column pairs. Composite keys are natural (the sub-list *is* the pair-ordering). An
unscanned target is handled the same way `Asset`/`Column` already handle "not yet resolved": keep both
a resolved FK (`fk='/root/Asset'`, nullable) *and* an always-set qualified-name text field, exactly the
pattern the task's own framing anticipates. Cross-schema/cross-database references need no special
handling: `qualifiedName` already carries the full `DB.SCHEMA.TABLE` (or `SCHEMA.TABLE`) prefix
end-to-end (`CatalogOperations.java:28-32`; confirmed live on `SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER`
above), so the same text carries through a `Relation` row's target-qualified-name field without
inventing new addressing. A re-scan that drops a key is handled the same lifecycle idiom `Asset`/
`Column` already use (`status: ACTIVE/DELETED`, `seen()` helper — READ `tools/build_xsd.py`, the
`] + seen() + [` idiom appears after every entity's own fields, e.g. `tools/build_xsd.py:395` for
`Column`).

**(c) Reuse `LineageEdge` with a new transformation/origin.** Rejected as the primary design, for a
concrete, present-day reason: `LineageEdge.origin` is an enumeration that **already** contains a value
literally spelled `DECLARED`, meaning *"Declared by a data flow definition (before or between runs)"*
— READ `tools/build_xsd.py:458-461` (full enum: `OPENLINEAGE`, `PLATFORM`, `DBT`, `MANUAL`,
`DECLARED`). A source-declared foreign key is a **structural fact that exists whether or not any job
ever moves data along it** — semantically distinct from every existing `origin` value, all of which
describe *observed or declared data movement*. Reusing the table would force a choice between (i)
overloading the existing `DECLARED` token with a second, unrelated meaning — a direct collision with
what the task's own `LandingRelation.origin = DECLARED` plan presumably intends, three repos deep —
or (ii) adding a new origin value into a table whose every consumer (`LineageJourneyPane.java`,
`DirectLineageGraph.java`, `Lineage.java` — grepped under `ebx-data-catalog/.../ebx/ui/`) renders it as
a data-flow edge, which a schema constraint is not. It also inherits the same composite-key gap unless
extended with a `constraintName` grouping field — at which point, again, most of a dedicated table's
design has been reinvented inside a table shaped for something else.

### 3.3 Recommendation

**New `Relation` table**, parallel to `Asset`/`Column`/`LineageEdge` (`tools/build_xsd.py:311,363,442`
are the three siblings it would join), with roughly:

| field | shape | why |
|---|---|---|
| `source` | fk `/root/Source` | scoping, same as every other catalog table |
| `toAsset` | fk `/root/Asset`, mandatory | the child/referencing table — always scanned, since its FK metadata is what was just read |
| `fromAsset` | fk `/root/Asset`, **nullable** | the parent/referenced table, when it was scanned |
| `fromAssetQualifiedName` | text, always set | the parent's qualified name even when `fromAsset` can't resolve (target not scanned) |
| `constraintName` | text, nullable | groups the column pairs of one constraint; Snowflake/Databricks/JDBC can all supply one |
| `columns` | repeatable sub-group `{fromColumn fk (nullable) / fromColumnName text / toColumn fk (nullable) / toColumnName text / position int}` | composite keys, same idiom as `LandingTable`'s existing `LandingColumn` sub-table (`MdmFactory.xsd:1076-1106`) |
| `enforced` | enum `YES` / `NO` / `UNKNOWN` | Snowflake/Databricks keys are declared-not-enforced; JDBC keys usually are enforced |
| `origin` | enum, **new vocabulary**, e.g. `SOURCE_DECLARED` / `VALUE_INFERRED` / `RULE` | deliberately **not** reusing the string `DECLARED` (§3.2c) |
| `status`, `firstSeen`, `lastSeen` | the existing lifecycle/`seen()` idiom | re-scans that drop a key turn the row `DELETED`, not silently vanish |

This costs more than option (a) — a new XSD block, a `CatalogPaths.RELATION` constant, a new
`CatalogFlows` mapping (same shape as the existing ones, `CatalogFlows.java:130-168`) or a dedicated
`EbxCatalog`-specific one reading the already-present `column.foreignKey` object directly — but it is
the only option that supports composite keys correctly, keeps structural facts out of the
data-flow-shaped `LineageEdge` table, and survives a re-scan the same way every other catalog entity
already does.

---

## 4. How the factory's `CatalogReader` would read it

`CatalogReader.java` already establishes the exact pattern to extend: plain public static data classes
(`Asset`, `Column`, no EBX types in their fields — READ `CatalogReader.java:46-70`), one static reader
method per entity (`assets()`, `columns()`, `latestProfiles()`), a `RequestResult` scan filtered by a
quoted XPath predicate (`"./source=" + CatalogReader.quote(pSourceId)"`, `"./asset=" +
CatalogReader.quote(...)"` — READ `CatalogReader.java:109,159,194,247`), and graceful degradation
against an older catalog via two small helpers:

```
private static Object value(final Adaptation pRow, final String pPath)
{
    final Path path = Path.parse(pPath);
    return pRow.getSchemaNode().getNode(path) == null ? null : pRow.get(path);
}
```
READ `CatalogReader.java:289-294` (and the twin `text()` at 296-300) — every field read this way
already tolerates a catalog that predates it, per the class's own javadoc: *"Without the catalog,
`dataset` is null and the factory says so instead of failing."* (READ `CatalogReader.java:29-30`).

**Addition**: a `CatalogReader.Relation` class (same shape as `Asset`/`Column`) and a
`relations(Adaptation pCatalog, String pSourceId, List<String> pSchemas)` method that: (1) checks the
*table* exists before querying it — `pCatalog.getTable(RELATION_PATH) == null` (or catching the same
`getSchemaNode().getNode(...) == null` pattern one level up, on the table path rather than a field
path) — since an older catalog simply won't have `/root/Relation` at all, not just a missing field;
(2) filters `./source=...` and `./status != 'DELETED'`, mirroring `isScannedTableOrView()`
(`CatalogReader.java:184-189`); (3) resolves each row's `fromAsset`/`toAsset` against the `assets()`
already read in the same call, the way `columns()` already resolves against `byId`
(`CatalogReader.java:193,222`).

**How the factory would use it**, concretely: join `Relation.toAsset`/`fromAsset` against
`LandingTable.catalogAsset` (an existing field, READ `MdmFactory.xsd:884-895`) to translate catalog
asset ids into `LandingTable` ids (audit D-07, 2026-10-06: the catalog's assets have no integer id any more -
`catalogAsset` is a string holding the asset key `source:qualifiedName`, and a foreign key to an asset holds that
same string, so the join is a plain string equality; a landing table planned before D-07 is re-pointed by the next
landing plan, `docs/UPGRADE.md` section 2.1), and each `Relation` column pair against `LandingColumn.sourceColumn`
the same way — i.e. a `LandingRelation` row (the table the other, not-yet-started branch is meant to
add) is a `Relation` row re-expressed in landing-table-id space. This is the same "plan is data,
rerunnable, adds only what's new" idiom `LandingPlanner.java` already establishes for `LandingTable`
itself (READ `LandingPlanner.java:31-40`, its own `DRIFTED` handling).

**Open interaction, not resolved by this study**: `SourceKeyRule` (READ `SourceKeyRule.java:8-14`,
*"The key declared at the source wins... Many platforms declare none (Snowflake does not enforce
one)... without a proposal the user picks the columns"*) proposes a table's **landing key**
independently of any `Relation`. A `Relation`'s target column is not guaranteed to be the column
`SourceKeyRule` proposes for that same table (Snowflake declares no enforced PK at all — a foreign key
could target any unique-ish column). Whether the factory should still build a real staging FK against
such a target, or downgrade it to an attribute-level hint, is a genuine open design question this
study surfaces but does not answer.

---

## 5. Value-based inference: what the factory can actually call from Java (REVISED)

> **Correction to the 2026-09-20 original of this section.** It claimed *"No Java-callable equivalent
> of EBX Script's `db.createQuery` exists in `ebx-lib.jar`"*. That was wrong, and coordinator review
> caught it. The original search looked for classes named `*Query*` under
> `com/orchestranetworks/{schema,instance,service}/` and concluded the only matches
> (`com.orchestranetworks.dsl.sql.*`, `com.orchestranetworks.dsl.bc.*`) were EBX Script compiler
> internals — true as far as it went, but the search never looked at `com/orchestranetworks/query/`,
> a small, separate package that turns out to be exactly the public Java query API, sitting one level
> up from where the search stopped. This revision replaces the section below in full.

**VERIFIED (javap, against the exact jar path given by the task)**:

```
$ unzip -l ebx-lib.jar | grep com/orchestranetworks/query/
  CteQueryNotImplementableException.class  QueryUtils.class  Tuple.class
  TooManyResultsRuntimeException.class     QueryResult.class  package-info.class
  QueryBuilder.class  QueryParseException.class  Query.class

$ javap com.onwbp.adaptation.Adaptation | grep -i query
  public default com.orchestranetworks.query.Query<com.orchestranetworks.query.Tuple> createQuery(java.lang.String);
  public abstract com.orchestranetworks.query.QueryBuilder createQueryBuilder();
```
Full signatures of the four classes that matter:
```
public interface Query<T> {
  QueryResult<T> getResult() throws QueryPermissionException;
  String explain();
  void setSession(Session); void setLocale(Locale);
  void setParameter(int, Object); void setFetchSize(int); void setIncludeOcculting(boolean);
}
public interface QueryBuilder {
  QueryBuilder addDataset(String, Adaptation);
  Query<Tuple> build(String);
  <T> Query<T> build(String, Class<T>);
}
public interface QueryResult<T> extends Iterable<T>, AutoCloseable {
  List<String> getFieldNames(); List<Class<?>> getFieldTypes(); void close();
}
public interface Tuple {
  <X> X get(String, Class<X>); Object get(String); <X> X get(int, Class<X>); Object get(int); Object[] toArray();
}
```

**READ**, the shipped Javadoc under the exact path the coordinator pointed at
(`.../ebx-ps-fasttrack-server/webapps/ebx-manager/www/common/html/onHelp/advanced/Java_API/com/orchestranetworks/query/{Query,QueryBuilder,QueryResult}.html`)
plus the SQL dialect guide at `.../onHelp/advanced/sql/sql_introduction.html`
("Documentation > Developer Guide > SQL in EBX®"):

- `Adaptation.createQuery(String)` runs a query scoped to that `Adaptation`'s own dataset (the
  single-dataset case). `dataset0.createQueryBuilder().addDataset("s1", dataset1)` registers a
  **second** dataset under an alias for a genuinely cross-dataset join — the shipped example is
  exactly this:
  ```java
  QueryBuilder builder = dataset0.createQueryBuilder().addDataset("s1", dataset1);
  Query<Tuple> query = builder.build("SELECT t1.c_custkey, t2.o_orderkey FROM customer t1 " +
      "INNER JOIN s1.orders t2 ON t1.c_custkey=t2.o_custkey.c_custkey");
  ```
  (the default dataset's own implicit alias is `_public`). **Two tables of the *same* dataset need no
  special addressing at all** — name both table entities in `FROM`/`JOIN`, nothing more; confirmed
  live below.
- `QueryResult<T> extends Iterable<T>, AutoCloseable`: *"A QueryResult instance must always be closed
  after its use"*, supports **one active iterator at a time**, is **not thread-safe**, and
  `getResult()`'s own javadoc states the same snapshot-isolation guarantee `RequestResult` gives:
  *"all internal persistence and indexing entities used in this process will utilize the same
  snapshot (the one at the moment [getResult] was executed)."* `Query.setFetchSize(int)` is *"a hint
  to the underlying database with the number of records to fetch at a time"* — i.e. **streamed/paged**
  from the database, the same execution shape `RequestResult` already has, not a full in-memory
  materialization.
- `QueryBuilder.build(String, Class<T>)` returns a bare typed value for a single-column result, no
  `Tuple` unwrapping — the shipped javadoc's own worked example is precisely an aggregate count:
  `Query<Long> query = queryBuilder.build("SELECT COUNT(*) FROM customer", Long.class);`.

**The EBX SQL dialect itself** (`sql_introduction.html`, cross-checked against the live
`get_query_syntax` reference the `data-access` MCP server ships — both agree) is read-only
(`SELECT`/`WITH` only), Calcite-based, and directly answers the task's specific questions:

- **Foreign-key-path joins vs. plain-column joins are genuinely different cases**, confirming the
  distinction the task asked to check. `CLAUDE.md` §2's already-known refusal (`i.company = ?` failing,
  needing `i.company.Identification.Id = ?`) is specific to an FK **struct** column: `get_query_syntax`
  states a foreign-key field is a `ROW` struct exposing only the target's PK node(s), comparability
  `NONE`, and `CtePlanVerifier` explicitly rejects `=`/`<>`/`<`/etc. on it — dereference to `fk.pk` or
  use `FK_AS_STRING(fk)` instead. A join on two **plain scalar (string) columns** — what a
  landing/staging anti-join needs — is ordinary SQL, unaffected by that restriction, and both
  `LEFT JOIN` and comma-join/`WHERE`-equality forms are supported.
- **`NOT IN (subquery)` / `NOT EXISTS (subquery)` / `LEFT JOIN`**: all three listed CONFIRMED by
  `get_query_syntax` (exercised by EBX's own test suite) — only `RIGHT`/`FULL OUTER JOIN` are
  unsupported (optimizer limitation, not a refusal of joins generally).
- **`COUNT(DISTINCT ...)`**: CONFIRMED.
- **Quoting**: reserved words — table **or** field names — must be double-quoted; a **leading
  underscore does not** require quoting. The reserved-word set is the real SQL/Calcite keyword list,
  not a naive heuristic — confirmed live below with a genuinely surprising instance (the catalog's own
  table is *named* `Column`, and `COLUMN` is a SQL-standard reserved word) alongside a near-miss that
  turned out fine unquoted (`ProductField.key` — `KEY` is not reserved in this dialect). **Practical
  implication for generated landing/staging tables**: don't guess from a fixed word list — a
  reserved-word collision fails fast at compile time (`QueryParseException`, surfaced as `INVALID`
  before any row is read), so the safe rule is *"if unsure, compile it"*, exactly as `get_query_syntax`
  itself recommends.

**Live validation (READ (live), this session)**, against `DataCatalog`/`DataCatalog`,
`"Column".name` vs `Asset.physicalName` — the meaningless-but-valid anti-join shape the coordinator
suggested, run through the read-only `execute_query` MCP tool (which compiles before executing, the
same gate `QueryBuilder.build` itself has):

1. Unquoted table name refused — kept as the concrete illustration of the reserved-word point above:
   ```sql
   SELECT COUNT(*) FROM Column c, Asset a WHERE c.name = a.physicalName
   -- INVALID: the query is not valid EBX SQL.
   ```
   Quoting fixed it immediately:
   ```sql
   SELECT COUNT(*) FROM "Column" c, Asset a WHERE c.name = a.physicalName
   -- 29
   ```
2. The actual recommended shape — one statement, both counts:
   ```sql
   SELECT COUNT(DISTINCT c.name) AS distinct_child,
          COUNT(DISTINCT CASE WHEN a.id IS NULL THEN c.name END) AS orphan_child
   FROM "Column" c LEFT JOIN Asset a ON c.name = a.physicalName
   -- distinct_child = 1746, orphan_child = 1737
   ```
3. Cross-checked with `NOT IN`, same answer:
   ```sql
   SELECT COUNT(DISTINCT c.name) FROM "Column" c
   WHERE c.name NOT IN (SELECT a.physicalName FROM Asset a WHERE a.physicalName IS NOT NULL)
   -- 1737 — matches orphan_child above
   ```
4. A bounded sample of orphan values for a human-readable message:
   ```sql
   SELECT DISTINCT c.name FROM "Column" c LEFT JOIN Asset a ON c.name = a.physicalName
   WHERE a.id IS NULL ORDER BY c.name FETCH NEXT 10 ROWS ONLY
   -- _CONTRACT_VERSION, _EBX_KEY, _PUBLISHED_AT, _RUN_ID, abbreviatedName, Abbreviation,
   -- AboutMe, absoluteXpath, ACCOUNT_CODE, ACCOUNT_ID
   ```
5. Underscore-prefixed identifiers, unquoted, both as an alias and as a real column reference:
   ```sql
   SELECT 1 AS _x                                                      -- column comes back "_X"
   SELECT t._x FROM (SELECT 1 AS _x FROM "Column") t WHERE t._x = 1    -- resolves & filters, unquoted
   ```
6. A reserved-looking field that is not actually reserved — don't assume, compile it:
   ```sql
   SELECT COUNT(*) FROM ProductField p WHERE p.key = TRUE   -- unquoted "key" is fine — 1
   ```

**Recommendation (revised)**: run the orphan check **inside EBX, as one SQL anti-join per relation**,
on the loaded landing tables, via `Adaptation.createQuery`/`QueryBuilder` from the factory's own Java
code — the exact shape validated live above: `COUNT(DISTINCT child)`, `COUNT(DISTINCT CASE WHEN
parent IS NULL THEN child END)`, and a `FETCH NEXT 10 ROWS ONLY` sample of orphan values for the
message, all in one statement, one round trip, computed by EBX's own query engine rather than by the
factory pulling every row into a Java collection. This **replaces** the two-pass scan as the primary
mechanism:
- The two-pass `RequestResult`/`HashSet` scan (`AdaptationTable.createRequestResult`/`nextAdaptation`,
  VERIFIED via `javap` — `CatalogReader.java:109,159,194,247` already proves the pattern works in this
  codebase) becomes the **documented fallback**: if some generated anti-join is ever refused
  (`QueryParseException`/`INVALID`) for a construct this dialect doesn't support, the same question
  can still be answered record-by-record, slower but with no dependency on any particular SQL feature.
- Push-down to the **source** (a connector `query` operation, e.g. Snowflake, `Operation.search("query", ...)`,
  READ `SnowflakeConnector.java:54`; the same one-aggregate-query-per-table shape
  `CatalogOperations.profile()` already runs, READ `CatalogOperations.java:326-353`) remains useful,
  but only as an **optional pre-load** check — before a landing table exists, nothing inside EBX can
  be queried at all. Once a landing table exists, the in-EBX anti-join is simpler (no source round
  trip, no cross-platform SQL-dialect translation, uniform across every already-landed source type
  including non-SQL ones) and is now the default.
- **Not verified**: whether a Connectors `query`/`execute` call issued from Java module code is
  subject to the 500 ms/2 s outbound-HTTP timeout caps `CLAUDE.md` §2 documents for EBX Script — no
  live `query`/`execute` connector call was made in this session (write/execute tools were off-limits);
  this only matters for the now-optional pre-load pushdown path, not for the primary in-EBX
  recommendation above, which makes no outbound HTTP call at all.

---

## 6. Lot plan

Ordered by payoff/cost; sizes are S (a focused change in one existing file/method), M (a new
table/class plus its wiring), L (new subsystem).

1. **[connectors, S]** `JdbcConnector.catalog()`: add `getImportedKeys` next to the existing
   `getPrimaryKeys` call (`JdbcConnector.java:1023-1026`), emit a `foreignKeys` array per table. Unblocks
   declared-FK data for PostgreSQL, MySQL, Oracle, SQL Server, H2, DB2 and every other plain-JDBC
   connector at once — highest leverage per line changed in this whole study.
2. **[connectors, S]** `CatalogOperations.Dialect`: add optional `foreignKeysSql(database, schemas)`
   (mirrors `usageSql()`/`lineageSql()`, `CatalogOperations.java:61,64`); implement for
   `SnowflakeDialect` (`SnowflakeConnector.java:234`) and `DatabricksDialect`
   (`DatabricksConnector.java:243`) against each platform's `INFORMATION_SCHEMA` constraint views.
   Unblocks declared-FK data for the two lakehouse/warehouse platforms; one extra SQL statement per
   scan, not per table.
3. **[connectors, S]** `SalesforceCatalog`: promote the already-fetched `referenceTo`
   (`SalesforceCatalog.java:193-201`) into a structured field alongside the existing remarks text.
   Unblocks Salesforce lookup/master-detail relations; zero new API calls.
4. **[data-catalog, M]** New `Relation` table in `DataCatalog.xsd` (`tools/build_xsd.py`, alongside
   `Asset`/`Column`/`LineageEdge` at lines 311/363/442) + `CatalogPaths.RELATION` + a new
   `CatalogFlows` mapping consuming the `foreignKeys` arrays lots 1-2 add, plus a dedicated mapping (or
   extension of the existing `columns` one) reading the `ebx` connector's already-present
   `column.foreignKey` object (`EbxCatalog.java:389`) without touching `lineage()`'s already-working,
   already-verified-live behavior (§2.3). Unblocks structural storage with composite-key and
   unscanned-target support — the schema most other work in this plan depends on.
5. **[mdm-factory, S]** `CatalogReader.Relation` + `relations()` reader (§4), following the exact
   `value()`/`text()` graceful-degradation idiom already in `CatalogReader.java:289-300`. Testable
   against hand-built fixtures even before lots 1-4 land real data (the class has no EBX-only
   dependency in its public data shapes, mirroring `Asset`/`Column`).
6. **[mdm-factory, S]** In-EBX anti-join orphan-check helper (§5, revised): `Adaptation.createQuery`/
   `QueryBuilder`, the live-validated `LEFT JOIN` + `COUNT(DISTINCT CASE WHEN ... END)` shape, plus a
   `FETCH NEXT 10 ROWS ONLY` orphan-sample query for the report message. This is now the **primary**
   orphan-count mechanism (promoted from the original plan's connectors-pushdown-first ordering) —
   needs no Connectors change at all, only the SQL-generation logic in the factory. Depends on lot 4
   to know which columns to join on; needs the reserved-word-safe quoting rule from §5 baked into the
   SQL generator from day one, since a landing table can legitimately be named `Order`, `User`,
   `Column`, etc.
7. **[mdm-factory, S]** `RequestResult`/`HashSet` two-pass scan as the **documented fallback** for the
   rare case the generated anti-join is refused (§5) — small, since it reuses the already-proven
   `RequestResult` pattern `CatalogReader.java` already establishes; no new mechanism, just a
   fallback branch in the same helper as lot 6.
8. **[connectors, S]** (optional, lower priority than 6-7) Connector `query`-pushdown pre-load orphan
   estimate (§5) — same aggregate-SQL shape `CatalogOperations.profile()` already runs, useful before
   a landing table exists at all; not required once lot 6 exists, since post-landing checks no longer
   need it.
9. **[mdm-factory, M]** `LandingRelation` planning pass — the table the task says is already being
   added on the (currently empty) `feature/landing-relations` branch — consuming `relations()` (lot 5)
   + `LandingTable.catalogAsset`, with name-based `RULE` proposals as the fallback when lot 4 has
   nothing for a given pair, and lots 6-8's value checks as a confidence signal on either kind of
   proposal. This is the actual deliverable the rest of this plan exists to unblock; intentionally not
   designed further here since another session already owns it.
10. **[connectors, L]** OData/SAP `$metadata` (CSDL) parsing for a `catalog` operation on
    `SapODataConnector` at all (§2.2) — sized separately because it is new capability, not an extension
    of an existing one; lowest priority of this list unless an OData/SAP source is actually on the
    project roadmap.

---

## Open questions

- The `origin` vocabulary collision (`LineageEdge.origin` already has a value spelled `DECLARED` with
  an unrelated meaning, `tools/build_xsd.py:461`) needs a decision **before** `feature/landing-relations`
  names its own `LandingRelation.origin` enum, or the same collision repeats a third time.
- Every Snowflake/Databricks SQL text and privilege claim in §1.2-1.3 is INFERRED from vendor
  documentation titles, not measured against a live account — re-verify before implementing (this
  study was explicitly barred from the calls that would do that safely: no `connectors_call`,
  `connectors_sync`, or `connectors_catalog_source` was run).
- Whether a Connectors `query`/`execute` call from Java module code shares EBX Script's outbound-HTTP
  timeout caps is unverified in this session — now only relevant to §5's optional, lower-priority
  pre-load pushdown path (lot 8), not to the primary in-EBX anti-join recommendation, which makes no
  outbound HTTP call.
- The `SourceKeyRule`/`Relation`-target interaction (§4, end) — what the factory does when an FK's
  target column isn't the table's proposed source key — is a genuine open design question, not settled
  by this study.
- `JdbcConnector.getImportedKeys` per-table cost (§1.4) was reasoned by analogy to the two per-table
  calls already there; not measured on a real multi-hundred-table schema.
- §5's reserved-word finding was tested against two words (`COLUMN` reserved, `KEY` not) plus a
  leading underscore (not reserved) — not an exhaustive list. The lot-6 SQL generator should compile
  every generated identifier (`get_query_syntax`'s own "if unsure, compile it" rule) rather than rely
  on a hardcoded reserved-word table.
