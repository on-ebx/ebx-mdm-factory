# Sources + landing redesign - lot KEY (composite source keys)

Composite-key lot, 2026-09-24, branch `feature/landing-composite-key`. Backend only - lot LANDING builds
the screens (the wizard's step 3 composite-key builder, the "Change the key..." button) and calls the API
documented here. Four independent pieces, matching the task brief's four numbered items.

## 1. Escaping in `_sourceId`

### Before

`landing.LandingFlow.mapping` built `_sourceId`, with a declared key of 2+ columns, as the plain
`COALESCE(expr,'') || '|' || COALESCE(expr,'') || ...` concatenation of the key columns' text values -
`landing.LandingFlow.concatCoalesce`. A `'|'` genuinely present in one column's value was indistinguishable
from the separator: `A_ID = "A|B", B_ID = "C"` and `A_ID = "A", B_ID = "B|C"` both produced `_sourceId =
"A|B|C"` - two different source rows silently landing on the same EBX record (the second load overwriting
the first, forever, with no error anywhere).

### After (SQL path - Snowflake today, any future `SqlDialect`)

A key of **2 or more columns** now escapes each part BEFORE the `COALESCE`/`'|'`-join: `landing.SqlDialect`
gained `escapeKeyPart(String)`, implemented per dialect (`SnowflakeDialect.escapeKeyPart`, using
`REPLACE(REPLACE(expr, CHR(92), CHR(92)||CHR(92)), CHR(124), CHR(92)||CHR(124))` - backslash doubled first,
then `'|'` escaped with a leading backslash, the same convention EBX's own `PrimaryKey` uses for a
composite key's string form, CLAUDE.md §9). `CHR(92)`/`CHR(124)` (backslash/pipe) rather than the
characters written directly in a SQL string literal, because Snowflake treats a backslash as an escape
character INSIDE a single-quoted string constant by default - a literal one-backslash search/replace
argument cannot be written reliably as `'\'` without itself needing escaping.

With the example above: `A_ID = "A|B"` escapes to `"A\|B"`, joined as `"A\|B|C"`; `A_ID = "A", B_ID = "B|C"`
escapes to `"A|B\|C"`... wait - only `A_ID`/`B_ID` are escaped independently, so `A_ID="A"` stays `"A"`,
`B_ID="B|C"` escapes to `"B\|C"`, joined as `"A|B\|C"`. The two `_sourceId` values are now `"A\|B|C"` and
`"A|B\|C"` - different, no collision. `LandingFlowTest.checkCompositeKeyEscapingAvoidsCollision` proves the
same thing at the string-manipulation level (mirroring the SQL's own REPLACE semantics in Java), and
`checkSourceIdKeyFallback` asserts the exact generated SQL text.

**`_rowHash` is NOT escaped** - it is never looked up by a `keyPredicate` string-equality match, only
compared for "did this row's content change", so the collision this lot fixes does not apply to it. The
"every key column NULL" `'#'`-prefixed row-hash fallback (unchanged from the earlier lot) is also
unaffected: that `CASE WHEN ... IS NULL` condition reads the RAW (unescaped) column expressions, since
NULL-ness is unaffected by escaping.

### Why NOT a single-column key

Escaping is applied **only when the declared key has 2 or more columns**. With exactly one key column
there is no `'|'` separator to protect against - nothing else is concatenated onto it - so escaping it
would only change the `_sourceId` already computed for every value that happens to contain `'|'` or `'\'`,
with zero benefit (no collision it could ever cause). Restricting the change to composite keys is what
keeps every single-column-key table's `_sourceId` (and therefore every existing landing row's identity)
untouched by this lot.

### What existing rows this changes

**Only landing tables with a DECLARED KEY OF 2+ COLUMNS, and only the rows among them whose key column
values contain a literal `'|'` or `'\'`.** Every other row (single-column key, no key at all, or a
composite key whose values never contain those two characters) computes the exact same `_sourceId` as
before - `COALESCE`/escaping is a no-op on text with neither character.

For the rows that DO change: the landing table's flow must be regenerated (`POST
/sources/{id}/landing/flows?force=true`, or the table's own `LANDING_LOAD` step re-run after a `force`
regenerate - `connectors_mapping_upsert` is idempotent on the mapping's label, so this safely overwrites
the mapping already in place) and then **reloaded** (`connectors_sync`). Until reloaded, the OLD,
unescaped `_sourceId` stays on the existing EBX record; after a reload, the row is looked up by its NEW,
escaped `_sourceId`, does not match the old record, and a fresh record is created - the old one is left
behind as an orphan (same "landing is a raw copy" reasoning `ebx.LandingKeyChange`, item 4, relies on: nothing
downstream should treat a landing row as durable identity across a key change). No migration script is
provided: a project with a composite key on values that can genuinely contain `'|'` or `'\'` should
regenerate and reload after upgrading, the same way any other flow-generation change is rolled out
(`docs/LANDING-FLOWS.md`'s own `_status` `IF_EMPTY` -> `ALWAYS` change is the precedent).

### The file-feed (non-SQL) gap - deliberately NOT fixed here

A feed-based table (`sample-file`/`sftp`/`s3`/`azure-blob`/`gcs`/`ftp` - `Table#fileFeed`) builds
`_sourceId` with the `concat(path1,path2,...)` rule transformation instead of SQL. `concat` has **no
per-part escaping**, and cannot be given one without a Connectors code change: it resolves each argument
path directly against the document root (`ebx-connectors`' `Transformations.java`,
`register("concat", ...)`) and joins the raw values with a fixed `'|'`, bypassing any prior
pipeline/transformation stage entirely (a rule's own `sourcePath` is not consulted by `concat` - it reads
its own arguments fresh). So a composite key on a feed-based source is still exposed to the exact
collision this lot fixes on the SQL path.

**Where the fix belongs**: `ebx-connectors`' `Transformations.registerBuiltins()`, `register("concat", ...)`
- either (a) escape each piece (backslash doubled, then `'|'` escaped) before joining it, the smallest and
most consistent fix, since every `concat` caller gets it automatically, not only landing; or (b) extend
`concat` to accept a per-argument transformation suffix so a caller could compose `replace(...)` in front of
it. Not attempted in this lot - it is out of scope for `ebx-mdm-factory` (a different repository, `~/Dev/ebx-connectors`,
which this lot's brief said to read but never modify). Made visible in code: `LandingFlow.FILE_FEED_KEY_ESCAPE_GAP`
(a named, greppable marker constant) and the full explanation in `LandingFlow.feedMapping`'s own Javadoc.

## 2. Measuring a partially-empty composite key - `landing.KeyEvidence`

No behaviour change to the existing "every key column NULL -> `'#'`||rowHash" fallback (§1 of `docs/LANDING-FLOWS.md`,
unchanged). This item adds MEASUREMENT: `KeyEvidence.Combination` and `KeyEvidence.combination(List<String>
pSampleColumns, List<List<String>> pRows, List<String> pColumns)`, pure, no EBX class.

For ANY candidate set of columns (not necessarily the table's proposed or current key - lot LANDING's
composite-key builder calls this live "as you add or remove a column"), on the SAME 25-row sample the key
study already reads (`ebx.KeyStudy`/`ebx.LandingPreview`):

- `rowsInSample` - the sample size;
- `distinctTogether` - distinct combined values among every row (a fully-empty combination still counts as
  one distinct value);
- `rowsAllEmpty` - rows where EVERY part of the combination is empty (or the column is not in the sample at
  all - treated as always empty, never an exception);
- `rowsPartlyEmpty` - rows where SOME but not all parts are empty;
- `unique()` - the same true/false `KeyEvidence.uniqueTogether` already gives, derived from the same four
  figures (`rowsInSample > 0 && rowsAllEmpty == 0 && rowsPartlyEmpty == 0 && distinctTogether == rowsInSample`).

Also added: `KeyEvidence.sampleJson(List<String> pColumns, List<List<String>> pRows)` - the sample as a
compact JSON array of rows, string values only, `[{"col1":"v1",...}, ...]`, `pColumns` aligned
POSITIONALLY with `pRows` exactly like `analyse`/`combination` read them (the sample's own full column
list, not a filter). Lets a client-side script (lot LANDING's builder) recompute `combination` for a set of
columns the person is still trying out, without a round trip to the server for every checkbox click - the
same 25-row/40-character-per-value bound as the rest of the key study, so the payload embedded in the page
stays small.

Tested in `landing/KeyEvidenceTest`: unique/non-unique/partly-empty/fully-empty/missing-column/empty-list
cases, and `sampleJson`'s exact shape including the short-row (trailing key omitted, not null) case.

## 3. Origin `SOURCE` - a key declared by the source

- `tools/build_xsd.py`: `KEY_ORIGIN = ORIGIN + [('SOURCE', 'Declared by the source', 'Déclarée par la
  source')]`, used ONLY by `LandingColumn.sourceKeyOrigin` (`F('sourceKeyOrigin', ..., enum=KEY_ORIGIN)`) -
  the plain `ORIGIN` list (and `RELATION_ORIGIN`, `LandingRelation`'s own extension of it) are untouched, so
  no other origin field on any other table gained a value it didn't ask for. Additive: the regenerated
  `MdmFactory.xsd` differs by exactly the one new enumeration value + its two-locale label (`tools/test_local.sh`'s
  XSD-diff check passed with this change in place - a Tomcat restart is needed to pick it up, no other migration).
- `ebx.LandingPlanner`: when `SourceKeyRule.propose` returns the DECLARED key (every one of `key.columns`
  came from a `Candidate` whose `declaredKey` was true - `CatalogReader.Column#primaryKey`, itself fed from
  the catalog's `Column.primaryKey`), each of those columns' `LandingColumn.sourceKeyOrigin` is now written
  `SOURCE` instead of `RULE`; confidence stays 100 (unchanged - `SourceKeyRule.propose`'s own rationale,
  "Primary key declared at the source."). A column in the naming-rule-guessed key still gets `RULE`, exactly
  as before. `scanned.primaryKey` is read per column, not inferred once for the whole table: exact, because
  `SourceKeyRule.propose` returns EITHER an all-declared list OR an all-guessed list, never a mix.
- Messages: `wizard.step3.keyOrigin.SOURCE` = "declared by the source" / "déclarée par la source", inserted
  right after `wizard.step3.keyStudy.sample=...` in both `Messages_en.properties`/`Messages_fr.properties`
  (before `keyOrigin.RULE`, per the brief - `keyOrigin.USER` is left for another lot to extend after).
- `landing.KeyCertainty.certain(String pOrigin, int pConfidence, Boolean pUniqueInSample)`: `SOURCE` and
  `USER` are always certain; `RULE` is certain only when the CURRENT sample shows the key unique together
  with no empty value; `LLM` needed that same sample condition AND confidence >= `LLM_CONFIDENCE_MIN` (90) - since
  2026-10-07 an `LLM` key is never certain (a suggestion a person confirms);
  anything else (unrecognised/blank origin) is never certain. Pure, tested (`landing/KeyCertaintyTest`) -
  lot LANDING is expected to use this to decide whether a table with a key goes straight through the
  pipeline or waits for a person to look at it (not wired into any pipeline step by this lot).

## 4. Changing the key after the landing is loaded - `ebx.LandingKeyChange`

```java
LandingKeyChange.Result r = LandingKeyChange.change(session, factory, projectCode, landingTableId, columns);
// r.ok, r.message (null on success), r.rowsDeleted
```

Refuses (a `Result` with `ok=false`, a `message`, `rowsDeleted=0`, NO write at all) when: the table is not
found; the table's project source does not belong to `pProjectCode`; the table's `status` is neither
`GENERATED` nor `LOADED` (a `PROPOSED` table should use the ordinary key picker,
`WizardData.setTableKey`/`ebx.KeyStudy.apply`, which is exactly what it is for); or the requested column
list is empty, blank, or names a column the table does not have (`landing.LandingKeyChangeRules.validate`,
pure, tested in `landing/LandingKeyChangeRulesTest` - column matching is CASE-SENSITIVE, the same convention
`WizardData.setTableKey` already uses).

On success, in order:

1. **Writes the new key** - `WizardData.writeTableKey` (a NEW package-private method, added rather than
   changing `WizardData.setTableKey`'s body or its "must still be PROPOSED" refusal - both stay exactly as
   they were for their existing callers, the wizard's key picker and `ebx.KeyStudy.apply`). Origin `USER`,
   confidence 100, rationale "Key changed after load: a person picked a new key once the landing table
   already existed."
2. **Deletes every record of the landing table itself** - resolved in the project's landing
   dataspace/dataset the same way `QualitySuggester.landingTableRef`/`StagingLoader` already do
   (`HomeKey.forBranchName` + `findAdaptationOrNull` + `dataset.getTable(Path.parse("/root/" + tableName))`),
   deleted with `ProcedureContext.doDelete` inside a `Procedure` run with `dataset.getHome()` (not
   `pFactory`'s own home - the landing dataset is a different dataspace). A landing row is a raw copy of the
   source, never authored data - a reload recreates it from scratch, so there is nothing to preserve across
   a key change. Returns the count deleted (`rowsDeleted` - 0, not an error, when the dataset/table cannot be
   resolved at all, e.g. never generated).
3. **Regenerates the table's `ebx-connectors` flow** - `LandingFlows.generateOne(session, factory,
   projectSourceId, landingTableId)` (a NEW public method on `LandingFlows`, reusing every private helper
   `generate()` itself uses - `source`, `project`, `flowTables`, `describeConnector`, `upsertFlow`,
   `writeFlowSuccess`/`writeFlowFailure` - narrowed to exactly one table, not a copy of `generate`'s body).
   Deliberately outside the `LANDING_FLOWS` `PipelineStep` (that step means "the whole project source's
   flows, generated once" - a single table's on-demand regeneration after a key change is a different kind
   of event).

**Does NOT reload** (no `connectors_sync` call) - the caller (lot LANDING's "Change the key..." button)
decides when, or whether, to spend a real load run on it.

## Tests and what is unverified

`tools/test_local.sh` green (XSD-diff check included): `landing/LandingFlowTest` (extended -
`checkSourceIdKeyFallback` now asserts the escaped SQL text for a 2-column composite key and the unescaped
single-key/no-key cases; new `checkCompositeKeyEscapingAvoidsCollision` proves two different key
combinations no longer produce the same joined string once escaped, and that backslash-then-pipe escapes in
the right order), `landing/KeyEvidenceTest` (extended - `combination`/`sampleJson`), new
`landing/LandingKeyChangeRulesTest`, new `landing/KeyCertaintyTest`.

**Nothing here has run under Tomcat or against a live EBX instance.** No `connectors_mapping_upsert`,
`connectors_sync`, or EBX write of any kind was called this session (the brief restricts this lot to
read-only MCP tools, and no read tool was used either, since no live investigation was needed beyond the
docs already in the repo). Specifically unverified:

- that Snowflake's `CHR`/`REPLACE`/string-literal-escaping behaviour is exactly as described (a real
  Snowflake account was not queried this session - the reasoning follows Snowflake's own published "String
  Constants" documentation, cited in `SqlDialect.escapeKeyPart`'s Javadoc, not a live probe);
- that a real composite-key landing table, reloaded after this change, actually produces the two different,
  non-colliding `_sourceId` values the SQL text implies (`connectors_sync` was never called);
- `ebx.LandingKeyChange.change`'s full sequence end to end (write key -> delete rows -> regenerate flow) -
  only its pieces are separately proven correct (`WizardData.writeTableKey`'s body is identical, minus the
  refusal, to the already-live `setTableKey`; `LandingFlows.generateOne` reuses already-live helpers
  unchanged; the delete pattern mirrors `StagingLoader`'s already-live one) - the class itself was never
  exercised against a live EBX instance;
- lot LANDING's own screens (the composite-key builder, "Change the key..." button) - out of scope for this
  lot by the brief ("no screen work beyond the one message key").
