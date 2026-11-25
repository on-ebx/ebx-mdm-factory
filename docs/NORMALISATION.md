# Normalisation and data quality between landing and staging — research and design

Research plus a design recommendation, written 2026-09-20 on branch `feature/quality-study`
(worktree `ebx-mdm-factory-quality-study`), answering the product owner's request of 2026-09-20 evening
recorded in `PLAN.md` ("Normalisation / qualité entre landing et staging"): a third family of
staging proposals, alongside entity/attribute suggestion and mapping, covering deterministic
cleanup rules, LLM-assisted column typing, and quality controls on the staging model. **No
production code in this document or this branch.**

**Evidence tags** on every factual claim, same convention as `docs/SOURCE-RELATIONS.md` (the prior
study on this codebase, read first as a style reference):

- **READ `path:line`** — read directly in this worktree's source, another repo's source, or a
  generated-schema script.
- **READ (live)** — a real, read-only `data-access` MCP call against the running EBX instance in
  this session (`execute_query`, `describe_table`, `get_query_schema`, `find_dataspace`,
  `list_tables` — no write tool used anywhere in this study).
- **VERIFIED (javap/unzip)** — a class or jar-contents fact checked directly against the exact jar
  path the task gave (`.../ebx-ps-fasttrack-server/lib/commons-validator.jar`,
  `commons-lang3.jar`).
- **INFERRED** — a conclusion not directly read or measured; reasoned from the above.

Repositories/paths read: this worktree (`ebx-mdm-factory`, branch `feature/quality-study`);
`~/Dev/ebx-data-catalog` (`ebx-data-catalog/src/main/resources/com/on/ebx/catalog/catalog-config.json`,
`ebx-data-catalog/src/main/webapp/WEB-INF/ebx/schemas/DataCatalog.xsd`); the two jars named in the
task; the internal EBX MCP guide. Live queries ran against dataspace
`Companies_Landing` (datasets `ERP`, `CRM`), the target `Companies` model, `Geographies`,
`Currencies`. **No write tool, no connector call, no LLM call was made.** Company data (`SUPPLIER`,
`ACCOUNT`) is quoted directly below — real company names/numbers, not personal data. `CONTACT`
(people) is described only as shapes and counts, per the task's rule; no name, email or phone value
from `CONTACT` appears anywhere in this document.

---

## Recommendation (summary)

1. **A third proposal family, but landing on the existing `AttributeMapping` row rather than
   replacing it (§1, §3).** `AttributeMapping.transformation` keeps its current, narrow job
   unchanged — the final, type-driven parse of a cleaned string into the staging attribute's type
   (`TRIM`/`PARSE_DATE`/... as today; the other agent's `transfer/Transformations` contract is not
   touched). A new child table, **`MappingStep`**, holds an ordered **pre-parse cleanup pipeline**
   (trim runs, empty-to-null, case, phone/country/currency/identifier normalisation) that runs
   *before* that final parse, one row per step, each independently `PROPOSED`/`ACCEPTED`/
   `REJECTED`/`APPLIED` with its own origin/confidence/rationale — the same review granularity every
   other proposal table in this codebase already has (§3).
2. **A new `QualityRule` table** for what should become a staging **validation** control (§2),
   scoped to an attribute or an entity, with a `severity` that says where it is enforced: a real EBX
   model facet (pattern, foreign key), or an explicit transfer-engine check (reject or warn) for
   whatever EBX cannot enforce reliably — the split is measured, not assumed: EBX's static
   enumeration facet is **silently non-blocking** (CLAUDE.md §2, confirmed by this study's own
   reading of the same file), so a closed list backed by a real reference table (Country, Currency,
   the target model's own `LegalForm`) is recommended over a bare enumeration wherever one exists.
3. **Real, measured defects on the actual `Companies_Landing` data** (§1) are concentrated in
   exactly three places, all confirmed live: `ACCOUNT.BILLING_COUNTRY` (16 spellings over 52 rows,
   only 36 resolve by exact reference-table match), `SUPPLIER.LEGAL_FORM` (11 country-specific
   abbreviations plus 6 blanks, over 55 rows), and **two duplicate-looking supplier pairs plus one
   near-duplicate** already sitting in the same table (`BNP PARIBAS`/`B.N.P. PARIBAS`,
   `SOCIETE GENERALE`/`STE GENERALE`, `HSBC HOLDINGS`/`HSBC HLDGS` — same VAT/registration number,
   different spelling). Whitespace and case defects, which the task's rule list leads with, are
   **not present** in this demo data (measured 0 of 55/52/89 on every checked column) — a real,
   reported negative finding, not an assumption.
4. **The LLM never sees a landing value.** Every rule below is a pure function of the landing
   column's *values* (read live, in EBX, by the factory's own Java) or the catalog's *tags*/profile
   (metadata) — consistent with `PLAN.md`'s own standing rule ("vers le LLM: métadonnées par
   défaut"). The LLM's job stays exactly where `PLAN.md` already scoped it: which rule *families*
   apply to a column whose catalog tag is missing, and canonical entity/attribute naming — never
   picking a transformation from a value it was shown (§4).
5. **Reference data already exists and should be reused, not re-embedded** (§5): `Geographies/Country`
   (ISO 3166-1, English + French names, dial code), `Currencies/Currency` (ISO 4217), and — read live
   on the target `Companies` model itself — `LegalForm` (ISO 20275, country + abbreviation) and
   `IdentifierType` (a `pattern` field already carrying LEI/SIREN/SIRET/VAT/BIC/MIC/WIKIDATA regexes).
   A normalisation rule should look these up, not carry its own copy.
6. **Lot plan**: §7, six lots ordered by payoff/cost, none requiring a restart or a connector change.

---

## 1. Real defects, measured live, and the rule catalogue

### 1.0 What was measured, and how

Every count below is a **live** `execute_query` `COUNT`/`GROUP BY` against `Companies_Landing`
(`ERP.SUPPLIER`, 55 rows; `CRM.ACCOUNT`, 52 rows; `CRM.CONTACT`, 89 rows — the exact row counts
`PLAN.md` itself records for this load). EBX SQL has no regex function reachable from `execute_query`
(`get_query_syntax`, READ (live): no `REGEXP`/`SIMILAR TO`-backed matcher is listed as CONFIRMED, and
`LIKE` is a plain substring/wildcard match), so every "how many rows would this rule touch" figure
below is either a `LIKE`/length/`TRIM`/`UPPER` count or a `GROUP BY` distinct-value enumeration —
exactly what the task asked for ("check which functions EBX SQL offers with `get_query_syntax`").
One EBX SQL gotcha found in passing, same family as CLAUDE.md §2's `day`: **`pattern` is a reserved
word** — `SELECT Code, Label, pattern FROM IdentifierType` answers a bare `INVALID`, `SELECT Code,
Label, "pattern" FROM IdentifierType` works. Not previously documented for this dialect.

### 1.1 Whitespace (trim, inner runs, non-breaking space)

**Detects**: `value <> TRIM(BOTH ' ' FROM value)` (leading/trailing) or two consecutive spaces
inside (inner runs; not separately measured below — no `REPLACE`/regex available from SQL to count
it directly, see §1.0). **Transformation**: `TRIM` (existing enum value) for the outer case; a new
`COLLAPSE_SPACES` step for inner runs (§3). **Argument**: none. **Confidence**: `100 * (rows where
value ≠ trimmed) / (rows with a non-null value)` when > 0; when the measured count is 0, this is
reported as a **negative finding**, not proposed at all — proposing a no-op rule with fabricated
confidence would be worse than silence. **Rationale sentence**: `"N of M values had leading or
trailing spaces."` **Lossy**: no.

**Measured (READ (live))**, `SUPPLIER` (55 rows): `NAME1`, `NAME2`, `STREET`, `CITY`, `POSTAL_CODE`,
`VAT_NO`, `IBAN`, `PHONE` all **0/55** with leading/trailing whitespace. `ACCOUNT` (52 rows):
`ACCOUNT_NAME` **0/52**. `CONTACT` (89 rows, shape only): `EMAIL`, and `FIRST_NAME`/`LAST_NAME`
together, **0/89**. **Inner spaces** (measured via `LIKE '% %'`, a weaker but SQL-expressible proxy —
true for a real inner run but also for a single legitimate embedded space, so this is an
upper-bound/candidate count, not a precise defect count): `SUPPLIER.REGISTRATION_NO` **5/55**
(concrete values below, §1.6); `SUPPLIER.VAT_NO`/`IBAN`/`LEI` **0/55**; `ACCOUNT.SIREN` **1/52**.
**Conclusion**: this demo's whitespace hygiene is already clean on every column checked — the rule
stays in the catalogue (real source systems are not this clean, and the task asked for it
unconditionally) but is not a rule this particular load would visibly benefit from. Non-breaking
space (U+00A0) specifically was **not tested** — EBX SQL's `LIKE`/`TRIM` take a literal pattern
string and no NBSP character was typed into a query in this session; call this **untested**, not
"absent".

### 1.2 Case

**Detects/argues**: the task asks to argue for or against upper for codes vs. title case for names.
**Measured (READ (live))**: `SUPPLIER.NAME1` is **55/55 already ALL CAPS**; `SUPPLIER.CITY` is
**55/55 already ALL CAPS** too. `ACCOUNT.ACCOUNT_NAME` is the opposite: **48/52 mixed (natural)
case**, only 4/52 all-caps. This is not a same-table defect in either source — it is a
**cross-source style difference** (ERP exports upper-case company names as a matter of house style;
the CRM keeps natural typing, including diacritics: `Crédit Agricole`, `Société Générale`). **Recommendation:
against** a blanket `UPPER` on name/address free text — it destroys real information (`Crédit
Agricole` and `CREDIT AGRICOLE` both lose nothing meaningful by comparison later, but a name column
forced upper-case is harder to read in the golden record and in any downstream document that prints
it, and "title-casing" an all-caps acronym-heavy name like `BNP PARIBAS` or `ING` produces worse
output than leaving it alone: naive title-case turns `ING` into `Ing`, `HSBC` into `Hsbc`). **For**:
`UPPER` on genuinely coded columns whose values are already conventionally upper (`VAT_NO`, `BIC`,
`IBAN`, `CURRENCY`, `COUNTRY` codes) — safe because they are already uppercase in this data (0-row
impact, see below) and defends against a future load that isn't. One real case defect was found,
not in a name column: **`SUPPLIER.LEI`, 1/55 rows lowercase** (`n4jdfkkh2ftd8rkfxo39`, supplier
`V100450`/CIC) — ISO 17442 requires upper-case, and the LEI check-digit algorithm (ISO 7064 MOD
97-10) is defined over the upper-cased, letter-to-number-expanded string, so a lowercase LEI is a
real shape defect, not a style choice. **Confidence formula**: `100 * (rows where value ≠
UPPER(value) AND column is coded, not free text)`. **Lossy**: no for a coded column; **yes** for a
name column carrying case-sensitive information (a very small number of real company names are
legitimately mixed-case, e.g. `eBay` — none observed in this data, but the rule must not be
`autoApply` for a name column for that reason).

### 1.3 Empty-ish values to null

**Detects**: value case-insensitively equal to a small sentinel set (`N/A`, `NA`, `-`, `?`, `NULL`,
`NONE`, `UNKNOWN`) or the empty string. **Measured (READ (live))**: `SUPPLIER.NAME2` — **0/55**
sentinel hits (6 truly blank rows already read as SQL `NULL`/`''`, not a sentinel string).
`ACCOUNT.WEBSITE` — **0/52** sentinel hits. **A genuine trap, found live and worth recording as the
headline caution for this rule**: `SUPPLIER.LEGAL_FORM` has the literal value `NA` on 2 rows
(`V100350` CITIBANK, `V100380` JPMORGAN CHASE BANK) — **not** a null-like sentinel here: `N.A.`
("National Association") is the real, legally correct legal-form abbreviation for a US
nationally-chartered bank (the same rows' `COUNTRY` is `US`). A blind, global `NA → null` dictionary
would silently destroy a real, correct value. **Recommendation**: this rule must be scoped per
column (and ideally per source country), never applied as one global sentinel list across every
text column of every table — the confidence/rationale must say explicitly which sentinel matched
and on which column, so a reviewer scoped to `LEGAL_FORM` for a US-heavy source can catch exactly
this case before accepting it. **Confidence**: `100 * (matched rows) / (non-null rows)`, but capped
lower (e.g. never `autoApply`) for any column also proposed as an identifier/code column, precisely
because of the `LEGAL_FORM`/`NA` collision. **Rationale**: `"N value(s) equal to a known empty-ish
sentinel ('X'); review before accepting on a column where the sentinel could be a real code."`
**Lossy**: no (turns noise into a true null) — **except** in the false-positive case just described,
where it is destructive; hence the caution above.

### 1.4 Dates in mixed formats

**Detects**: values that do not match one consistent format. **Measured (READ (live))**:
`SUPPLIER.CREATED_ON`/`CHANGED_AT`: **0/55** non-standard (uniformly `yyyy-MM-dd` and
`yyyy-MM-dd'T'HH:mm:ss`). `ACCOUNT.CREATED_DATE`/`LAST_MODIFIED_DATE`: **0/52** — an
earlier pattern check in this session flagged `CREATED_DATE` as 52/52 "non-standard" using a
date-only `LIKE` pattern; sampling the actual values (READ (live)) showed the flag was a **false
positive from a wrong pattern**, not a real defect — `CREATED_DATE` is a full ISO-8601 timestamp
(`2020-12-24T17:35:17`), same shape as `LAST_MODIFIED_DATE`, not a bare date. Corrected here rather
than silently dropped, per the task's own emphasis on separating READ from assumption. **Why dates
are this clean**: `PLAN.md`'s own landing-flow design already explains it — every Snowflake landing
column is generated `TO_VARCHAR`'d explicitly "sinon nombres et dates sont reformatés" (READ
`PLAN.md`, "Flux de landing"), which is exactly what keeps every date column in one canonical format
for a SQL source. **Recommendation**: keep the rule in the catalogue (`PARSE_DATE`, already
`AttributeMapping`'s existing enum value) — it is the one the type-driven mapping already proposes
for every `DATE`/`TIMESTAMP` column (`StagingRules.java:572-575`, READ), confidence 90 per that
existing rule — but note it is **presently a no-op on this data** for the reason above, and its real
value shows up on a non-SQL landing source (file, Kafka, webhook) where `TO_VARCHAR` pushdown is not
available — `PLAN.md`'s own "à faire dans Connectors" section already flags this gap for `hash()`/
`now()`/`concat()` transformations on non-SQL landings; date-format heterogeneity is the same kind of
gap.

### 1.5 Decimals with mixed separators

**Detects/transformation**: unchanged from `AttributeMapping`'s existing `PARSE_DECIMAL` (READ
`MdmFactory.xsd:2601-2606`; `StagingRules.argumentFor`, READ `StagingRules.java:593-596`, already
proposes `.` as the separator argument for `INTEGER`/`DECIMAL` columns). **Measured**: neither
`SUPPLIER` nor `ACCOUNT` has a column whose catalog-inferred type is `DECIMAL`/`INTEGER` among the
columns read for this study (`PAYMENT_TERMS` reads as free text, e.g. `NET30`-shaped, not parsed as
a number by the landing plan — not independently confirmed by a live type check here, **untested**
on this specific column). **Conclusion**: no live evidence either way on this dataset; the rule and
its existing mapping-level mechanics are kept as designed, confidence formula unchanged (already
90, type-driven).

### 1.6 Identifiers: normalise separators/case, then validate a check digit where one exists

This is the richest rule family in the real data, and the one where "normalise" and "validate" are
genuinely two different steps (feeding §2 directly).

**`SUPPLIER.REGISTRATION_NO` (55 rows) mixes at least six different national schemes in one column**
(READ (live), full sample): plain 9-digit French SIREN (`552120222`), Swiss UID with dots
(`CHE-106.831.974`), UK Companies House 8-digit with leading zeros (`00617987`), German
Handelsregister with a space (`HRB 32000`), a French SIREN written with inner spaces
(`775 670 284`, `652 051 178` — 5 rows total have an inner space, confirmed by the `LIKE '% %'`
count in §1.1), an Australian-shaped ABN with two inner spaces (`007 457 141`), a Luxembourg RCS
number (`B91985`), and two clearly wrong-looking values (`33209m`, `122119m` — a trailing lowercase
letter on what should be a numeric Austrian Firmenbuch/company number; not independently confirmed
against the real Austrian registry format in this session, flagged as a likely defect, not
confirmed). **Recommendation**: this column cannot get one shape rule — the transformation must key
off `COUNTRY` (already on the same row) to pick which normalisation/check applies, exactly the same
per-country dispatch `LegalForm`/`IdentifierType` already model on the target `Companies` schema
(§5). Rule: strip separators (spaces, dots, dashes) and, only for the countries where a check-digit
algorithm is known (SIREN: France, Luhn mod 10 per `IdentifierType.SIREN` pattern `[0-9]{9}`, READ
(live)), validate it.

**`SUPPLIER.VAT_NO`**: 29/55 blank; of the 26 present, every one seen (READ (live) sample of 55)
matches the shape `FR` + 11 digits, consistent with the catalog's own `VAT_NUMBER` tag regex (READ
`catalog-config.json`, `VAT_NUMBER.detectionPattern`, has a full per-EU-country alternation
including `FR[0-9A-Z]{2}[0-9]{9}`) and with the much looser generic pattern already stored on the
target model, `IdentifierType.VAT.pattern = [A-Z]{2}[A-Z0-9]{2,13}` (READ (live)). No shape defect
measured on the present values. **Recommendation**: normalise (strip spaces, upper the country
prefix) then validate against the catalog's precise per-country regex, not the target model's loose
generic one — the catalog's is measurably stricter and already exists.

**`SUPPLIER.LEI`**: 22/55 blank, 1/55 lower-case (the defect in §1.2). Shape otherwise consistent
with ISO 17442 (20 chars, `[A-Z0-9]{18}[0-9]{2}` — same as `IdentifierType.LEI.pattern`, READ
(live)). **Recommendation**: `UPPER` first (a normalisation step), then check-digit validate — see
§2 on why the checksum itself is a *validation* concern, not a normalisation one (it cannot silently
"fix" a wrong checksum).

**`SUPPLIER.IBAN`/`BIC`**: 8/55 IBAN blank, 0 shape defects measured (`LIKE '% %'` = 0, i.e. no
grouped-with-spaces IBANs in this load, unlike a typical bank-statement export); `BIC` length is
**0/55 outside {8,11}** (measured live) — both already clean in this data. Kept in the catalogue
(mod-97 for IBAN, shape check for BIC) for the general case; not currently exercised by this load.

**`ACCOUNT.SIREN`**: 31/52 blank, 21 distinct non-blank values, 1/52 with an inner space, 1/52 not
9 characters long (READ (live) aggregate — the two may or may not be the same row; not
individually inspected further in this pass). **Recommendation**: same strip-then-validate rule as
`SUPPLIER.REGISTRATION_NO`'s French branch, `IdentifierType.SIREN.pattern = [0-9]{9}` (READ (live))
plus a Luhn check-digit (`org.apache.commons.validator.routines.checkdigit.LuhnCheckDigit`,
VERIFIED (unzip) present in `commons-validator.jar` — SIREN is checked with a variant of Luhn;
whether `LuhnCheckDigit`'s exact weighting matches INSEE's SIREN algorithm was **not verified** in
this read-only session, flagged for the implementing lot).

**Duplicates, found as a side effect of reading `REGISTRATION_NO`/`VAT_NO`/`IBAN`/`BIC` together —
not asked for by name in the rule catalogue, but squarely inside the task's "duplicates across ERP
and CRM" ask, and concrete enough to record exactly**: three `SUPPLIER` row pairs share the same
`VAT_NO`/`REGISTRATION_NO`/`BIC` under a differently-abbreviated name (READ (live)):

| Row A | Row B | Shared | Different |
|---|---|---|---|
| `V100150` "BNP PARIBAS" | `V109010` "B.N.P. PARIBAS" | `VAT_NO FR76662042449`, `REGISTRATION_NO 662042449`, `BIC BNPAFRPP` | `IBAN` differs, `LEI` blank on B |
| `V100160` "SOCIETE GENERALE" | `V109020` "STE GENERALE" | `REGISTRATION_NO 552120222`, `IBAN`, `BIC` — **identical on every shared field** | `VAT_NO` blank on B, name abbreviated |
| `V100170` "HSBC HOLDINGS" | `V109030` "HSBC HLDGS" | `REGISTRATION_NO 00617987`, `LEI MLU0ZO3ML4LN2LL2TL39`, `IBAN`, `BIC` — **identical on every shared field** | name abbreviated only |

This is not a normalisation-rule finding in the strict sense (no single-column transform "fixes"
two rows into one) — it is evidence that the **identifier normalisation rules above feed Match and
Merge directly**: once `REGISTRATION_NO`/`VAT_NO`/`LEI` are normalised to one canonical shape per
row, an exact-match matching key on the normalised identifier would catch these three pairs with no
fuzzy logic at all. Recorded here because the task asked to "quote real examples" of duplicates, and
because it is the clearest evidence in this dataset that normalisation is a **precondition** for
matching quality, not a parallel, unrelated concern.

### 1.7 Transcoding to a reference list: legal form

**Detects**: low-cardinality free text with a known target vocabulary. **Measured (READ (live))**,
`SUPPLIER.LEGAL_FORM` distinct values over 55 rows: `SA` (23), `AG` (6), *blank* (6), `PLC` (5),
`SE` (3), `SPA` (3), `CORP` (2), `NA` (2 — see §1.3's caution), `NV` (2), `INC` (1), `SCA` (1),
`SNC` (1) — **11 distinct abbreviations, 49/55 rows non-blank**. **Transcode target**: the target
`Companies` model's own `LegalForm` reference table (ISO 20275, READ (live) below, §5) — **but the
join key is `(Country, Abbreviation)`, not `Abbreviation` alone**: `SELECT COUNT(*), COUNT(DISTINCT
Abbreviation) FROM LegalForm WHERE Abbreviation = 'S.A.'` returns **12 rows, 1 distinct
abbreviation** (READ (live)) — the same abbreviation string is reused by different countries (and
punctuated differently: France's is stored as `SA`, not `S.A.` — `SELECT ... WHERE
Country.Alpha2Code='FR' AND Abbreviation='SA'` returns exactly one row, `QVPB` "Société anonyme",
READ (live)). **Recommendation**: `TRANSCODE`'s existing inline-JSON argument shape
(`{"SA":"..."}`, READ `MdmFactory.xsd:2633`) is too narrow for this rule — it has no room for a
second key. Propose a new `MappingStep` transformation, `TRANSCODE_REFERENCE`, whose argument names
a reference table + the columns to join on (here: `LegalForm`, `(Country=<this row's own COUNTRY
column>, Abbreviation=<this column>)` → `Code`) rather than embedding a copy of the mapping inline
(§3, §5). **Confidence**: `100 * (rows whose (country, abbreviation) pair resolves to exactly one
LegalForm.Code) / (non-blank rows)` — computable in one query once §5's join is written; not
computed here since it needs the row-level `(COUNTRY, LEGAL_FORM)` pairs cross-referenced against
`LegalForm`, which needs a per-project script rather than one ad hoc query (both tables are read
live individually above; the join was reasoned, not executed as a single live SQL statement, since
`LegalForm` and `SUPPLIER` are on different dataspaces and the `execute_query` MCP tool queries one
dataspace/dataset pair at a time — cross-dataset joins need `QueryBuilder.addDataset`, a Java-only
capability, see §5). **Lossy**: no if the join resolves; a non-resolving row must not be silently
dropped or nulled — report it as a rule-generation warning as `StagingRules.SkippedRelation`
already does for the same kind of case (READ `StagingRules.java:222-237`).

### 1.8 Transcoding to a reference list: country

**The single most impactful rule measured in this whole study.** `ACCOUNT.BILLING_COUNTRY`
(READ (live), full `GROUP BY`, 52 rows): **16 distinct spellings** — `France` (14), `USA` (6), `Fr`
(5), `United States` (4), `Canada` (3), `U.S.` (3), `UK` (3), `Deutschland` (2), `FRANCE` (2),
`Italia` (2), `Spain` (2), `Suisse` (2), `China` (1), `Great Britain` (1), `Holland` (1),
`Netherlands` (1). **Zero of these 52 rows are already an ISO 3166-1 alpha-2 code** — this rule
would touch **52/52 rows**, a 100% hit rate, the highest of any rule measured in this study. By
direct contrast, `SUPPLIER.COUNTRY` is **already** ISO alpha-2 on all 55 rows (`FR`, `GB`, `US`,
`ES`, `IT`, `AT`, `CA`, `CH`, `DE`, `NL`, `AU`, `LU` — READ (live) `GROUP BY`) — the same rule
proposed on `SUPPLIER.COUNTRY` would be a correct **no-op**, confidence still computed, count 0,
and — per §1.1's principle — not actually proposed, since proposing a rule that changes nothing
adds review burden for no value.

**Coverage measured against the real reference table, not assumed**: cross-checking the 16 spellings
against `Geographies/Country` (`Alpha2Code`/`Alpha3Code`/`Name`/`NameFR`, READ (live), two targeted
queries) gives an exact split:

- **Direct match, no synonym dictionary needed** (case-insensitive exact match on `Name`, `NameFR`,
  `Alpha2Code`, or `Alpha3Code`): `France`/`FRANCE` (`Name`), `Fr` (`Alpha2Code = FR`), `USA`
  (`Alpha3Code = USA`), `Canada` (`Name`), `Suisse` (`NameFR`), `China` (`Name`), `Spain` (`Name`),
  `Netherlands` (`Name`) — **8 spellings, 36 of 52 rows (69%)**.
- **No match in `Geographies/Country` at all — needs a synonym dictionary or the LLM's metadata-only
  pass (§4)**: `United States` (`Country.Name` is stored as `"United States of America"`, not
  `"United States"` — an exact-match rule misses it), `U.S.`, `UK` (the real ISO2 is `GB`; `UK` is
  not an official alpha-2/alpha-3/English/French name in this table), `Deutschland` (German
  endonym), `Italia` (Italian endonym), `Great Britain` — **6 spellings, 16 of 52 rows (31%)**.

**Rule, in two tiers**: (1) exact match against `Country.Alpha2Code`/`Alpha3Code`/`Name`/`NameFR`
(case-insensitive) — confidence 95, deterministic, no synonym list to maintain; (2) a small,
explicit synonym dictionary for the common English/national-language aliases a real source will
keep producing (`UK`→`GB`, `USA`/`U.S.`/`United States`→`US`, `Holland`→`NL`, plus each country's own
endonym — `Deutschland`→`DE`, `Italia`→`IT`, `España`→`ES`, `Suisse`/`Schweiz`/`Svizzera`→`CH`) —
confidence 85, and this dictionary is exactly the kind of small, low-risk lookup table the LLM can
usefully *propose the contents of* once (§4), reviewed once, then run deterministically forever
after — not re-derived by a model on every load. **Argument**: `ISO2` (the fixed target code
system — the task's own rule list says "→ ISO2", never ambiguous). **Lossy**: no. **Rationale**:
`"N of M values already match a country name/code exactly; K more match a known alias."`

### 1.9 Currency

**Detects/target**: ISO 4217. **Measured (READ (live))**: `SUPPLIER.CURRENCY`, 6 distinct values,
**all already valid ISO 4217 alpha codes** (`EUR` 40, `GBP` 5, `USD` 5, `CAD` 2, `CHF` 2, `AUD` 1) —
**0/55 rows would be touched**, another true no-op, reported rather than assumed. No currency
column was found on `ACCOUNT`/`CONTACT`. **Recommendation**: keep the rule (a validating check
against `Currencies/Currency.AlphaCode`, READ (live) below, §5) since a currency column existing
elsewhere in a future source is exactly the case it exists for; not proposed on this load.

### 1.10 Booleans in words

**Measured (READ (live))**: `SUPPLIER.PURCHASING_BLOCK`/`DELETION_FLAG` are already the strings
`"true"`/`"false"` (54/1 split on each) — already what `PARSE_BOOLEAN` expects, `TRANSFORM` chosen
as `PARSE_BOOLEAN` by the existing type-driven rule (`StagingRules.transformationFor`, READ
`StagingRules.java:568-571`) is a correct no-op. `CONTACT.HAS_OPTED_OUT_OF_EMAIL` likewise already
`"true"`/`"false"` (77/12). **No `O/N`, `Y/N`, or `1/0` boolean was found anywhere in this load.**
**Recommendation**: keep the rule (the task explicitly asks for it, and every real ERP export this
factory will meet is not this clean) but do not assume a fixed value-word mapping — derive the
`argument` from the column's own two most frequent distinct values via the catalog's profile
(`ProfileSnapshot`, §5) rather than hard-coding `O`/`N`/`Y`/`1`/`0`, so a source that spells it
`Oui`/`Non` or `Vrai`/`Faux` is handled the same way.

### 1.11 Email, URL, phone, postal code (brief — no catalog tag for two of these)

- **Email**: `CONTACT.EMAIL`, shape-only per the task's privacy rule. **1 of 89 non-blank email
  values contains no `@`** (READ (live), `COUNT(CASE WHEN EMAIL LIKE '%@%' ...)`), the rest all
  already lower-case with no leading/trailing whitespace. **Rule**: lower-case (no-op on 88/89) +
  shape validate against the catalog's own `EMAIL` tag regex (`[^@\s]+@[^@\s]+\.[A-Za-z]{2,}`, READ
  `catalog-config.json`) or `org.apache.commons.validator.routines.EmailValidator` (VERIFIED
  (unzip), present). The 1 malformed value is exactly what §2's severity model below calls a
  candidate `REJECT`, not a silent fix — there is no safe way to "repair" a missing `@`.
- **URL**: `ACCOUNT.WEBSITE`, 47/52 present, **47/47 (100%) missing an `http(s)://` scheme** (READ
  (live)). **Gap worth flagging explicitly**: the catalog's `Tag` table has **no `URL` tag at all**
  (READ `catalog-config.json` — full 58-tag list enumerated in this session; `EMAIL`, `IBAN`, `VAT`,
  `LEI`, `PHONE`, `POSTAL_ADDRESS`, `COMPANY_REGISTRATION`, `BANK_ACCOUNT`, `GTIN`, `NATIONAL_ID` all
  exist, `URL`/`WEBSITE`/`COUNTRY`/`CURRENCY` do not). A `WEBSITE` column is only findable by its
  **name** (`nameHints`-style matching the factory would have to do itself) or by the LLM's
  metadata-only pass — not by a catalog tag, unlike email/phone/IBAN/VAT. `UrlValidator`
  (`org.apache.commons.validator.routines`, VERIFIED (unzip)) can both add the default scheme and
  validate shape.
- **Phone → E.164**: `SUPPLIER.PHONE`, 39/55 present, **39/39 already start with `+`**, 0 without
  (READ (live)); internally space-separated (`+33 1 99 00 05 87`), not compact. `CONTACT.PHONE`
  (shape only): 68/89 present, likewise **68/68 already `+`-prefixed**. **No missing-country-code
  defect was found in this load** — fortunate, but the rule (strip separators to a compact E.164
  string; when `+` is absent, default from the row's own country column via
  `Geographies/Country.OtherCodes.Dial`, READ (live), §5) is kept exactly as the task specifies,
  since a real source is not guaranteed to be this clean, and there is no dedicated phone-number
  Java library on the server classpath (confirmed absent below, §5.3) — only the EBX Script
  libraries `Fasttrack.lib.phone`/`Fasttrack.lib.validator`, not callable from this Java module
  (§5.3).
- **Postal code per country**: no dedicated per-country postal-code pattern table was found on
  `Geographies` or `Companies` in this session (`Geographies` has `Country`/`Subdivision`/
  `BusinessRegion` only, READ (live) `list_tables`) — flagged as a real gap: the rule from the
  task's own list ("postal code per country") has **no reference table to validate against** on this
  instance today, unlike identifiers (`IdentifierType.pattern`) or legal forms (`LegalForm`). Not
  designed further here; recorded as an open question (§8).

---

## 2. Validation (quality) rules: constraint vs. transfer-engine reject vs. warning

### 2.1 The severity model, grounded in a real, already-documented EBX behaviour

CLAUDE.md §2 states plainly, and this study re-read it as the starting point rather than
re-deriving it: EBX constraints split into two failure modes on `db.saveRecord`/an equivalent Java
save — **blocking** (mandatory, max length, unknown foreign key: the whole save throws) and
**non-blocking** (a static enumeration: an out-of-list value is *silently accepted and stored*, the
record just turns invalid in the validation report, and a script cannot even read the legal values
to pre-check). This single fact drives the whole severity model below — a rule's *kind* decides
where it can live safely:

| Kind | Where it lives | Why |
|---|---|---|
| Mandatory | Model facet (`mandatory` + `minOccurs=1`) | **Blocking** — already how `StagingModel.mandatoryOps` works today (READ `StagingModel.java:448-455`). No change needed. |
| Max length | Model facet (`maxLength`) | **Blocking** — already how `StagingModel.maxLengthOp` works today (READ `StagingModel.java:439-445`). No change needed. |
| Shape/pattern (identifier, email, E.164 phone) | Model facet (`xs:pattern` restriction), applied the same way `maxLength` is (`apply_data_model_patch`, one `set_element_property`-style op) | **Likely blocking**, the same mechanism as `maxLength` — a plain XSD restriction facet, not an `osd:` enumeration. **Not verified live in this read-only session** (no write call was made): flagged as the one item in this table to confirm with a real `apply_data_model_patch` call before relying on it, since `PLAN.md`'s own "constats" note only mentions `mandatory`/`maxLength`/enumerations as what `apply_data_model_patch` is used for today, not `pattern` by name. |
| Closed list **with** a real reference table (country, currency, legal form) | **Foreign key** (`osd:tableRef`) to that reference table, not an enumeration | **Blocking** — CLAUDE.md §2 lists "unknown foreign key" among the blocking failures, unlike enumeration. Where a real reference table already exists (§5), prefer it over a copy-pasted enumeration for this reason alone, independent of maintenance cost. |
| Closed list **without** a reference table (e.g. `ACCOUNT.RATING`: Hot/Warm/Cold/blank, `ACCOUNT_TYPE`: Customer/Prospect/Partner) | Either a plain `xs:enumeration` treated as **advisory only** (never assume EBX rejects a bad value), or an explicit **transfer-engine pre-check** before save | The enumeration facet cannot be trusted to block — see above. The transfer engine, being Java (not EBX Script), *can* read a schema node's enumeration facet directly through the standard schema/constraint API (unlike EBX Script, which CLAUDE.md §2 says cannot) — **INFERRED, not verified live** (no such call was made in this read-only session) — so a Java pre-check before `saveRecord` is the reliable way to turn this into a real reject, mirroring CLAUDE.md §2's own advice to pre-validate a foreign key the same way. |
| Check digit (LEI, IBAN mod-97, SIREN Luhn) | **Transfer-engine check only** — no XSD facet expresses a checksum | REJECT (a wrong checksum is not a formatting difference, it is a wrong value) or WARN, decided per identifier type (see below). |
| Duplicate-looking value across sources (§1.6) | **Not a staging-model concern at all** — Match and Merge's job | Recorded here only so it is not accidentally re-invented as a staging uniqueness constraint; `sourceSystem`/`sourceId` is already the staging primary key, deliberately not a cross-source business key (READ `StagingRules.java:446-449` docstring). |

### 2.2 REJECT vs WARN, and why they must differ per identifier type

Not every check-digit failure deserves the same severity. A wrong IBAN check digit means the bank
transfer will fail outright — reject the row (or at minimum the `BankAccount` it would create) with
a specific reason. A wrong SIREN check digit on a supplier record whose name/address/VAT otherwise
look plausible is much more likely to be a typo worth flagging for human review than a reason to
drop the whole company record — warn, keep the row, keep the bad value visible with `_rejectReason`
(the field `PLAN.md`'s own "Zones" table already reserves for exactly this, READ `PLAN.md`, "Zones",
`Staging` row: "rejets conservés avec `_rejectReason`"). **Recommendation**: severity is a field on
`QualityRule` (`BLOCKING_FACET` / `REJECT` / `WARN`), set per rule instance, not hard-coded per
identifier type — a reviewer decides, the same review-before-anything-is-generated principle
`PLAN.md` states for every other proposal family ("Rien n'est généré depuis une ligne qui n'est pas
acceptée", `MdmFactory.xsd:2714-2718`, READ).

### 2.3 `QualityRule` — proposed shape

A new top-level table, same conventions as every other proposal table in this codebase (`id`,
`origin`/`confidence`/`rationale`/`status` — READ the identical four-field block repeated on
`StagingEntity`, `StagingAttribute`, `AttributeMapping`, `MdmFactory.xsd:1669-1750`,
`1953-2027`, `2646-2750`):

| field | shape | notes |
|---|---|---|
| `entity` | FK → `StagingEntity` | scope anchor, mandatory |
| `attribute` | FK → `StagingAttribute`, **nullable** | null = entity-level rule (a cross-column check, e.g. "end date ≥ start date" — not measured in this dataset, kept generic) |
| `kind` | enum: `MANDATORY` / `PATTERN` / `REFERENCE` / `ENUMERATION` / `CHECK_DIGIT` / `UNIQUE` / `CUSTOM` | `MANDATORY` here is only for the (rare) case a quality pass wants to *propose* mandatory where `StagingRules`' own rule 3 did not (e.g. a profile that later shows 100% fill); the common case stays exactly where it is today, on `StagingAttribute.mandatory`. |
| `parameter` | text, `osd:text`, generous max length | the pattern, the reference table path, or the identifier-type code (`LEI`/`SIREN`/`IBAN`/...) driving which check-digit algorithm the transfer engine runs |
| `severity` | enum: `BLOCKING_FACET` / `REJECT` / `WARN` | see §2.1/§2.2 |
| `message` | text | shown to the person reviewing a rejected/warned row, and reused as the quality-report line label (§6) |
| `origin`, `confidence`, `rationale`, `status` | identical to every other proposal table | same review workflow, same `ReviewLine` nature (`QUALITY`, a fifth value added to the existing `RELATION`/`ENTITY`/`ATTRIBUTE`/`MAPPING`/`MATCH` set, READ `ReviewLine.java:14-18`) |

---

## 3. Storing a pipeline: comparing the three options, and the recommendation

### 3.1 The three options as posed by the task

**(a) A `steps` multi-valued group on `AttributeMapping`.** Rejected: a repeatable *group* (not a
table) has no natural way to carry its own `origin`/`confidence`/`rationale`/`status` per element
that the review screen can address individually — every other proposal in this codebase is
reviewed as **one row = one decision** (`ReviewLine`, READ `ReviewLine.java`, `nature` +
`rowId` pointing at exactly one table row). A group's occurrences are not separately addressable
rows for that purpose without becoming a table in all but name.

**(b) A child table, `MappingStep`.** Recommended — see §3.2.

**(c) A JSON pipeline in `argument`.** Rejected, for the same reason `docs/SOURCE-RELATIONS.md`
rejected reusing `LineageEdge` for a differently-shaped concept (§3.2c there): it collides with
`PLAN.md`'s own foundational principle, quoted verbatim, "Le projet est une donnée... donc
rejouable, comparable, exportable" (READ `PLAN.md`, "Principes") — an opaque JSON blob inside one
`osd:text` field is not comparable or queryable by EBX SQL the way a row is (no `GROUP BY
transformation`, no per-step review, no per-step confidence), cannot be reviewed step-by-step in the
existing flat `ReviewLine` screen without the review UI parsing and re-serializing JSON on every
edit, and reintroduces exactly the fragility CLAUDE.md §3 warns about for scripts and bundles more
generally: "nothing validates that a reference... actually exists" — a JSON pipeline step naming a
target attribute or reference table by string has no `osd:tableRef`, so a typo validates and
publishes clean and only fails at transfer time.

### 3.2 Recommendation: `MappingStep`, and why `AttributeMapping` is not touched

**`AttributeMapping` keeps its current, narrow contract exactly as it is today** — `transformation`
+ `argument` is the **final**, type-driven step that turns a (by then already-cleaned) string into
the staging attribute's typed value (`TRIM`/`PARSE_DATE`/`PARSE_DECIMAL`/`PARSE_BOOLEAN`/... — READ
`MdmFactory.xsd:2560-2627`). This is deliberate: the task states "another agent is writing the
transfer engine that EXECUTES transformations (`transfer/Transformations`, pure)" against today's
contract, and this study is read-only/design-only — changing `AttributeMapping`'s own meaning out
from under that work is exactly the kind of cross-branch collision `docs/SOURCE-RELATIONS.md`
flagged for `LineageEdge.origin`/`DECLARED` (§8, open questions there). Keeping the existing field
alone gives the transfer engine nothing new to learn about a mapping that does not have any quality
steps.

**A new table, `MappingStep`**, holds an **ordered pre-parse cleanup pipeline** that runs on the raw
landing string *before* `AttributeMapping.transformation`'s own parse:

| field | shape | notes |
|---|---|---|
| `id` | id | |
| `mapping` | FK → `AttributeMapping`, mandatory | the pipeline this step belongs to |
| `position` | int, mandatory | execution order, ascending; `AttributeMapping.transformation` always runs **after** every `MappingStep` of the same mapping, by construction — no position value needs to be reserved for it |
| `transformation` | enum, **new vocabulary**, distinct from `AttributeMapping.transformation`'s (see §3.3) | deliberately not reusing the same restriction, so adding a step kind never risks widening what the transfer engine's existing, already-shipped `AttributeMapping` switch has to handle |
| `argument` | text, same shape convention as `AttributeMapping.argument` | e.g. the sentinel list for `EMPTY_TO_NULL`, the reference-table path for `TRANSCODE_REFERENCE`, the identifier-type code for `NORMALIZE_IDENTIFIER` |
| `origin`, `confidence`, `rationale`, `status` | identical four-field block | one step is accepted/rejected on its own — e.g. a reviewer can accept "trim" and "empty-to-null" on a column while rejecting a proposed (and wrong) legal-form transcode on the same column, without touching the mapping's own final parse step at all |

**Scope of a mapping's steps (lot M6, 2026-10-04)**: a mapping's `MappingStep`s act on THAT mapping's value only. The
loader gives each field with steps its own cleaned alias of the source column and leaves the shared column raw, so the
steps of one mapping never change what another mapping of the same column reads (`docs/TARGET-MAPPING.md` §3.3 step 1,
`transfer.FeedRules`). Found on Vol100k / Vol1m: `UPPER` on `lastName` emptied `firstName` on every ECOM row.

**Case changes and the order of steps (lot M8b, 2026-10-04)**: the steps of a mapping run on the raw text BEFORE the mapping's own pipeline (`AttributeMapping.pipeline`,
`docs/TARGET-MAPPING.md` sec 3.3 step 1), and a pipeline runs left to right. A step that changes letter case (`UPPER`, `LOWER`, `NORMALISE_IDENTIFIER`, which upper-cases; in a pipeline `upper`, `lower`,
`capitalize`) in front of a `regex(...)` that reads letter case (a case class `\p{Lu}`, `\p{Ll}`, `\p{Upper}`, `\p{Lower}`..., or an ASCII range `[A-Z]`, `[a-z]`) changes what the pattern sees. Measured on
Vol1m: `UPPER` on the last-name mapping made the pattern, which tells the token in capitals from the token holding a lower-case letter, return the first token on 153,937 rows (the first-name mapping on the same
column was fine since lot M6). The correct form puts the case change at the END of the pipeline: `regex(...)|upper`. Two refusals, one structural test (`quality.CaseOrderRule`, pure, unit-tested, no value read,
the pipeline cut the way Connectors cuts it):
- `QualityLlmPass`, `propose_mapping_step`: a step of a case-changing kind on a column whose mapping has such a regex is refused, whatever the number of mappings (the step would be written to each), with the
  explanation and the fix. The column's mapping pipelines are shown to the model under it ("Mapping pipeline (runs AFTER the cleaning steps, on their result)"), the order rule is in the system prompt and in the
  `kind` description of the tool.
- `StagingLlmPass`, `propose_feed`: a pipeline that changes case in front of such a regex is refused ("put the case change at the end: regex(...)|upper"); rule (7) of the system prompt and the `pipeline` description say so.
The test is deliberately conservative in one direction: `lower|regex([a-z]+)` would be harmless and is refused too, the fix costs nothing.

**A step that empties the mapping's regex (same lot)**: when the column has ONE mapping with a pipeline holding a `regex(...)` (`ColumnInfo.pipelineSampleExact`; an attribute fed by several mappings has the union of
their values as evidence, which is no sample of any one pipeline), `QualityLlmPass` applies, in Java (`quality.RegexSampleCheck`, `java.util.regex`, the semantics of the Connectors step: `find()`, group 1 when the
pattern has a group, else group 0, or the group named by the second argument), the column's non-rejected steps, then the proposed one, then the pipeline up to its last regex, to the sample values the pass already
holds (the evidence of the column, in Java whatever the value-sharing mode). A step that turns a pipeline that yielded a value on the sample into one that yields nothing on every non-empty sample value is refused. A pipeline
that already yielded nothing without the step is not the step's fault and does not refuse it; a pipeline the check cannot reproduce exactly (a step other than `trim`/`upper`/`lower`/`regex` in front of the regex, an invalid
pattern) is skipped silently. The refusal quotes two inputs that give nothing according to the project's value-sharing mode (`llmValueSharing`): the values in `SAMPLE_VALUES`, their shapes in `SHAPES_ONLY`, none in `NONE`.
Counts are over the distinct values of the sample. No new read of landing data.

**Review screen**: one `ReviewLine` per step (nature `MAPPING_STEP`, a sixth `ReviewLine.nature`
value alongside the `QUALITY` one from §2.3), grouped under its parent mapping the same way
`StagingAttribute` lines already group under their entity today (READ `ReviewData.java` — a
`Counters`/grouping class already exists for exactly this kind of nesting). **A mapping with zero
steps behaves exactly as today** — nothing new to migrate, nothing new for an old mapping row to
carry.

**Transfer-engine consumption**: `transfer/Transformations` (the other agent's module, not read in
this study beyond confirming it does not exist yet — `find . -iname '*transfer*'` in this worktree
returns nothing outside worktree directory names) would read, per `AttributeMapping`, its ordered
`MappingStep`s where `status = ACCEPTED` or `APPLIED` (same status gate every other applied-proposal
consumer already uses, e.g. `StagingModel`'s own "only `ACCEPTED`/`APPLIED` rows", READ
`StagingModel.java:17`), apply them in `position` order to the raw string, then hand the result to
its existing `AttributeMapping.transformation` logic unchanged.

### 3.3 `MappingStep.transformation` — proposed new enum, separate from `AttributeMapping`'s

| value | argument | maps to §1 rule |
|---|---|---|
| `TRIM` | — | §1.1 (kept for parity, distinct enum from `AttributeMapping.TRIM` even though same effect, so the two tables never need to share a restriction) |
| `COLLAPSE_SPACES` | — | §1.1, inner runs |
| `EMPTY_TO_NULL` | pipe-delimited sentinel list | §1.3 |
| `UPPER` / `LOWER` | — | §1.2 |
| `NORMALIZE_PHONE` | default-country column name or literal ISO2 | §1.11 |
| `NORMALIZE_COUNTRY` | target code system (`ISO2`, fixed per §1.8) | §1.8 |
| `NORMALIZE_CURRENCY` | — (always ISO 4217) | §1.9 |
| `NORMALIZE_IDENTIFIER` | identifier-type code (`LEI`/`SIREN`/`SIRET`/`IBAN`/`BIC`/`VAT`) | §1.6, the strip/upper half only — checksum validation is a `QualityRule`, §2, not a silent "fix" |
| `TRANSCODE_REFERENCE` | `<reference table path>#<join columns>→<target column>` | §1.7, §1.8's tier-2 alias dictionary |
| `STRIP_ACCENTS` | — | for a CODE or IDENTIFIER only (a person or the language model may still choose it explicitly) — **never for a displayed value** (name, city, address): since 2026-09-22 a displayed attribute that needs cross-system matching gets a derived matching-key attribute instead (§3.3 bis), never a step that rewrites the value in place; `org.apache.commons.lang3.StringUtils.stripAccents` VERIFIED (unzip) present |

**Implemented 2026-09-21, branch `feature/cleaning-strip-accents` (worktree `ebx-mdm-factory-strip-accents`),
deviating from this row in one respect and adding a structural detector this document did not design:**

- **`commons-lang3.StringUtils.stripAccents` was NOT used**, despite being VERIFIED present on the server
  classpath (row above, and §5.3) — §5.3's own finding that `commons-lang3.jar` is **not currently on
  `ebx-mdm-factory`'s own classpath** (no `.classpath` entry) would have made this the module's first
  external dependency, for one method. `quality.CleaningSteps.stripAccents` instead hand-rolls the same
  result with only the JDK: `java.text.Normalizer.normalize(v, Form.NFD)` then dropping every
  `Character.NON_SPACING_MARK`, PLUS a small explicit table for the Latin letters NFD does not decompose
  (`ß`, `Æ`/`æ`, `Œ`/`œ`, `Ø`/`ø`, `Đ`/`đ`, `Ł`/`ł`, `Þ`/`þ`) — a case `StringUtils.stripAccents` is not
  guaranteed to cover either (its own Javadoc only promises "decomposable" characters). Case is never
  changed (composes with `UPPER`); a non-Latin script is left untouched apart from its own combining marks
  — deliberately not a transliteration.
- **The cleanup-step catalogue's own `Detectors` (§1) cannot propose `STRIP_ACCENTS` on its own** — every
  `propose*` method there measures ONE landing column against itself, and the defect this step exists for
  is only visible ACROSS the mappings that feed one staging attribute (§1.2's own cross-source finding:
  `SUPPLIER.NAME1` is all-caps, `ACCOUNT.ACCOUNT_NAME` is natural case AND carries diacritics — `Crédit
  Agricole` next to `CREDIT AGRICOLE`). A new pure sibling class, `quality.CrossSystemFold`, fills this gap
  structurally: for a text attribute fed by two or more mappings whose landing tables resolve to two or
  more distinct `ProjectSource.sourceSystem` values, it counts how many distinct values are shared ACROSS
  those mappings' own (capped, most-frequent-first) samples before and after folding (strip accents, then
  upper-case), and proposes `STRIP_ACCENTS` then `UPPER` — on EVERY mapping of the attribute, never only
  one — exactly when `sharedAfter > sharedBefore`. No column name or catalog tag is read; the gate and the
  evidence are both pure functions of measured values and of the project's own mapping wiring (which
  systems feed which attribute), matching this document's own §4 boundary ("the LLM never sees a landing
  value" cuts both ways: neither does this deterministic detector need to). `ebx.QualitySuggester` runs
  this pass once per `quality/suggest` call, after its existing per-mapping loop (so `TRIM`/`COLLAPSE_SPACES`
  already written for a mapping give the right `position` for the two new steps to land after).
- **Not run against live EBX or a live language model** in this lot (worktree isolation, no instance
  reachable) — `tools/test_local.sh` (XSD regeneration diffed against the committed one, full compile, every
  `*Test.java`) is green, including a fixture reproducing the exact CRM/ERP `Paris`/`Zürich` vs
  `PARIS`/`ZURICH` case. The XSD gained one enumeration value (`MappingStep.kind = STRIP_ACCENTS`) —
  additive, but the running model only picks it up on the **next Tomcat start** (CLAUDE.md §9: a data-model
  XSD change needs a restart; nothing about this change is hot-reloadable).

**Fixed 2026-09-21 (coordinator review, same branch), a real defect found on the same night's live demo:**
the first cut of `CrossSystemFold` measured the before/after coincidence count on RAW landing values — but
`Company.country` (CRM `Fr`/`France`/`Suisse`, ERP `FR`/`CH`) already carries an ACCEPTED step on EACH mapping
(`COUNTRY_TO_ISO2` on CRM, unscoped `TRANSCODE_REFERENCE` on ERP) that already resolves both to the same ISO
codes, verified in the master zone (`FR`/`FR`, `CH`/`CH`). Measured on raw text, folding case and accents still
looked like a "gain" (`Fr` folds to `FR`, which happens to already equal ERP's raw `FR`) purely by coincidence
— the pass would have proposed `STRIP_ACCENTS`/`UPPER` on both mappings, positioned AFTER the accepted steps
where they do nothing, with a rationale whose numbers describe text that no longer exists once those mappings
run for real. Fixed by measuring AFTER each mapping's own accepted pipeline, not before:

- `CrossSystemFold.applyAcceptedPipeline` runs a mapping's sampled distinct values through its own
  ACCEPTED-or-APPLIED `MappingStep`s (in `position` order) via `CleaningSteps.applyPipeline` — the SAME call
  `ebx.StagingLoader.planRow`'s "task D" hook makes for a real row; `StagingLoader.readCleaningSteps` (the
  loader's own reader) was widened from `private` to package-visible and reused verbatim rather than
  re-implemented, so "accepted pipeline" means exactly what the loader means by it. `ebx.QualitySuggester`
  calls `buildLookups` once per `quality/suggest` run (it had briefly stopped being called from `suggest`
  itself when the name-hinted proposers were removed) so an accepted `COUNTRY_TO_ISO2`/`CURRENCY_TO_ISO`/
  unscoped `TRANSCODE_REFERENCE` step resolves against the exact same reference tables the loader itself uses.
- **A SCOPED `TRANSCODE_REFERENCE` (e.g. a legal-form abbreviation scoped by country, §1.7) cannot be
  evaluated this way** — its row-level scope value only exists on the real landing row, never in a plain
  single-column distinct-value sample. `CrossSystemFold.hasUnsupportedScopedStep` (reusing `CleaningSteps
  .scopeFieldOf`, the same finder the loader itself uses) detects this and the whole ATTRIBUTE is skipped —
  never measured on a guessed `null` scope value that would silently under-resolve every row — with one line
  recorded in the route's own `crossSystemFoldNotMeasured` output array (`"<entity>.<attribute>: step
  TRANSCODE_REFERENCE cannot be evaluated here ..."`) so a missing proposal is visible, not silent.
- **The shared-value count is keyed by DISTINCT SOURCE SYSTEM, never by mapping index** (a second, independent
  defect the same review caught): two mappings of the SAME system whose values happen to fold onto the same
  key are not "shared across systems", however many of that one system's own columns carry it — fixed by
  grouping on `SystemValues.systemId` (a per-mapping sentinel when unresolved, so two blank systems can never
  spuriously match each other) rather than on the position of a mapping in the list.
- The rationale now states explicitly how many accepted steps each mapping's own pipeline already ran
  (`"CRM: 1 accepted step(s); ERP: 1 accepted step(s)"`) so a reviewer can tell the measurement reflects the
  data as actually loaded, not raw text.
- Tests added: the exact country fixture (measured correctly: no proposal; measured the old, wrong way: would
  have proposed — both asserted, so a regression back to raw-value measurement fails a test), the city fixture
  re-verified through an explicitly empty accepted pipeline, a mapping already carrying accepted
  `STRIP_ACCENTS`/`UPPER` yielding no new proposal on a re-run, and the same-system-two-mappings case that
  the mapping-index bug would have wrongly counted as cross-system evidence.

### 3.3 bis `StagingAttribute.derivedFrom`/`derivation` — matching keys replace the `STRIP_ACCENTS`/`UPPER` pair (2026-09-22)

**Product-owner decision, PLAN.md 2026-09-22**: the `STRIP_ACCENTS`+`UPPER` `MappingStep` pair §3.3 describes
above REWRITES the stored value — `Zürich` becomes `ZURICH`, `Straße` becomes `STRASSE`, `Crédit Agricole`
becomes `CREDIT AGRICOLE` in the golden record, because `ebx.StagingLoader.planRow` runs the accepted
pipeline on the raw landing value and stores the RESULT. That is a real loss of data quality for anything
DISPLAYED (a name, a city, an address), for a need that is purely about MATCHING (Match and Merge's exact
comparison / pre-clustering), never about storage. The fix: the stored value keeps its accents and case: the
factory derives a technical **matching key** attribute next to the display attribute, and the matching plan
(the other half of this lot, `docs/MATCHING.md`) uses the key for clustering/exact comparison while the
display attribute stays intact (and can still be compared with a fuzzy algorithm).

- **`StagingAttribute` gains two optional fields**: `derivedFrom` (a real foreign key to `/root/StagingAttribute`,
  the source attribute of the SAME entity — empty for an ordinary attribute) and `derivation` (an enumeration,
  one value so far: `FOLD_KEY`). A derived attribute is otherwise a perfectly ORDINARY `StagingAttribute` row
  — name `"<sourceName>Key"` (e.g. `cityKey`), `dataType` `STRING`, `maxLength` copied from the source (or
  empty if the source has none), `mandatory`/`businessKey`/`enumerated` all `false`, no `referenceEntity`, the
  usual proposal fields (`origin RULE`, `confidence`, `rationale`, `status PROPOSED`) and the SAME human
  review as any other staging attribute. `staging.StagingModel`/`master.MasterModel` need NO special case at
  all for it — they read every accepted `StagingAttribute` row the same way regardless of `derivation`, so a
  derived attribute flows staging → master exactly like any other field (verified: `StagingModel.AttributeInfo`
  deliberately carries no `derivedFrom`/`derivation` of its own — the pure model layer treats "derived" as
  entirely a loading concern, never a model-shape one).
- **The fold itself is ONE pure function, `quality.CleaningSteps.foldKey(String)`**: `null` in → `null`; trim;
  collapse any run of whitespace to a single space; `stripAccents`; `toUpperCase(Locale.ROOT)`; an empty result
  → `null`. Never throws. `quality.CrossSystemFold` (§3.3's own class) now measures its before/after
  coincidence count with exactly this function (previously it hand-rolled `stripAccents` then `UPPER`, with no
  trim/collapse step of its own) — and, when it finds a genuine gain (`sharedAfter > sharedBefore`, same
  measurement, still replayed through each mapping's ACCEPTED pipeline via `applyAcceptedPipeline`, same scoped-
  step exclusion), it now proposes ONE `CrossSystemFold.AttributeProposal` for the ATTRIBUTE (name
  `"<name>Key"`, `FOLD_KEY`) — never a pair of `MappingStep`s written to every mapping. `ebx.QualitySuggester`
  writes the `StagingAttribute` row from that proposal; idempotence (a `FOLD_KEY` attribute already derived
  from this source, in ANY status including `REJECTED`, is never re-proposed) and a name-collision guard are
  its own concern, EBX-bound and not unit-tested for that reason (same convention as every other EBX-bound
  generator in this codebase) — see `ebx.QualitySuggester.writeDerivedFoldKeyAttribute`'s own Javadoc.
- **At load time**, `ebx.StagingLoader.planRow` computes every derived field of the entity AFTER every
  ordinary (mapping-driven) field is computed, via a small pure seam pulled out for testability,
  `staging.DerivedFieldRule.apply`: `fields.put(<key field>, CleaningSteps.foldKey(String.valueOf(fields.get(<source
  field>))))`. A derived attribute has NO `AttributeMapping`, NO cleaning pipeline, NO quality rule of its own
  — it is never iterated by the mapping-driven loop at all. If the source value is missing on that row (this
  particular feed never supplies it, or it is genuinely blank), the key is `null`, by construction (`foldKey(null)
  == null`) — no special-casing needed.
- **`Detectors.RULE_PROPOSABLE_KINDS` no longer lists `STRIP_ACCENTS`/`UPPER`** (it briefly did, from
  2026-09-21 to 2026-09-22, specifically because `CrossSystemFold` used to write that pair) — removed
  deliberately: `ebx.QualitySuggester`'s withdrawal sweep treats a `RULE`-origin row of a kind no current RULE
  pass can ever re-propose the same way it treats one whose mapping was rejected (withdrawn if still
  `PROPOSED`, flagged if already decided). A leftover `STRIP_ACCENTS`/`UPPER` `MappingStep` from a build
  predating this decision is therefore automatically cleaned up (withdrawn) the next time `quality/suggest`
  runs if nobody ever decided on it, or FLAGGED for a reviewer's attention if it was already `ACCEPTED`/
  `APPLIED` — which is exactly the right signal, since an accepted one of those IS the pre-2026-09-22 data-
  quality bug this whole lot fixes.
- **Record lineage** (`docs/RECORD-LINEAGE.md`): a `FOLD_KEY` derived attribute is a TECHNICAL field, not a
  business value — excluded entirely from the field-by-field screen (`ebx.RecordLineageReader`), so a folded
  value like `ZURICH` never appears next to the `city` attribute it was built to leave untouched.
- **Never run live** in this lot (worktree isolation, no EBX instance reachable): `tools/test_local.sh` (XSD
  regeneration diffed against the committed one, full compile, every `*Test.java`) is green, including
  `CleaningStepsTest.foldKey`, a rewritten `CrossSystemFoldTest` (one `FOLD_KEY` proposal per attribute, the
  country fixture and the same-system fixture both still propose nothing), `DerivedFieldRuleTest` (the pure
  StagingLoader seam), `StagingModelDiffTest`/`MasterModelTest`/`MasterTransferPlanTest` (a derived field is
  carried as an ordinary field/additive change), `ProposalWordingTest` and `ReviewRulesTest` (the review
  screen's own wording and its "no mapping feeds it" false-alarm guard). The XSD gained two fields on
  `StagingAttribute` — additive, but the running model only picks them up on the next Tomcat start (CLAUDE.md
  §9). Idempotence/withdrawal for `FOLD_KEY` `StagingAttribute` rows, and the review screen's actual
  rendering, were never exercised against a live instance either.

### 3.4 Evidence redaction: what `MappingStep.examples`/`QualityRule.examples` store, and why

**Updated 2026-09-20 (coordinator review) — safe by construction, not by a guess.** The implementing lot's
first cut decided whether to show a real value or a redacted one from a *guessed* list of catalog-tag/
table-name substrings (`"EMAIL"`, `"CONTACT"`, `"NAME"`, …) — a heuristic that, missed once, puts a real
name, e-mail, phone number or identifier into a proposals table, a review screen, and any export of either.
The rule actually shipped instead: **the decision is made purely from the step/rule *kind*, a small, reviewed
allow-list, never from a tag.** A real value is stored only for `COUNTRY_TO_ISO2`, `CURRENCY_TO_ISO`,
`TRANSCODE_REFERENCE` (a country name, an ISO currency code, a legal-form abbreviation — closed, published
vocabularies, never a business value) and for the *matched sentinel token itself* of `EMPTY_TO_NULL` (e.g.
`"N/A"` — drawn from a small fixed list, never the real value it replaces). **Every other `MappingStep` kind
and every `QualityRule` kind** (`TRIM`, `COLLAPSE_SPACES`, `NORMALIZE_IDENTIFIER`, `CHECK_DIGIT`, `PATTERN`,
`UNIQUE`, `MANDATORY`, …) stores a **shape** instead — `Shapes.of`: each letter becomes `A`/`a` (case kept),
each digit becomes `9`, punctuation and spaces are kept exactly as written, and a run of more than 4 identical
shape characters collapses to `<char>(<count>)` (an IBAN's grouped digits, e.g. `FR76 3000 6000 0112 3456
7890 189`, stays fully spelled out at `AA99 9999 …` since no run exceeds 4; an ungrouped 9-digit SIREN
collapses to `9(9)`). `QualityRule.UNIQUE`'s duplicate-pair evidence adds one thing beyond a shape: the
landing `_sourceId`s of the rows involved — real business *keys*, never names, needed to look the rows up —
fetched by `ebx.QualitySuggester` and handed to the pure layer as plain data, never derived from a tag either.
The catalog-tag heuristic is not deleted outright but is no longer load-bearing for *whether a value is
shown*: it still gates *which rule families* a column is even considered for (e.g. `NORMALIZE_IDENTIFIER` only
on an identifier-tagged column), a much lower-stakes mistake than accidentally publishing a real value.

---

## 4. Where the LLM helps, and where it must not

`PLAN.md`'s own standing rule, restated verbatim because it is the entire boundary: *"Vers le LLM:
métadonnées par défaut; valeurs d'exemple seulement pour les colonnes non sensibles (tags du
catalogue)"* (READ `PLAN.md`, "Identités et droits"). Applied to normalisation specifically:

**Left to rules, never the model** (all of §1): every transformation above is a pure function of
values the factory itself reads *inside EBX*, or of catalog tags/profile figures — deterministic,
re-runnable, and — critically — the confidence numbers measured in §1 (0/55, 52/52, 36/52, 1/89...)
are exact counts a model would have no way to reproduce reliably from a metadata-only prompt. A
model asked "does this column have leading whitespace" without being shown values can only guess.

**Where the model is the only option, conservatively scoped**:

1. **Semantic typing when the catalog tag is missing.** §1.11 found this gap concretely: `WEBSITE`
   has no catalog tag at all (`URL` is not in the 58-tag list, READ `catalog-config.json`), so
   "this column is a URL, apply the URL rule" cannot come from a tag lookup — either a name-hint
   match the factory codes itself (cheap, deterministic, and arguably the better fix — see §8) or
   the LLM's metadata-only pass (column name `WEBSITE`, sibling column names, sample of the source
   table's other tags) proposing "this looks like a URL column, apply `NORMALIZE_URL`". Same
   pattern for `STREET`→address-line, `NAME1`→company-name, already the kind of judgement
   `PLAN.md`'s "Suggestion du staging" section reserves for the LLM ("quelles colonnes sont des
   adresses, des raisons sociales, des e-mails, et quelles règles leur appliquer").
2. **Which rule families apply.** Not "what should `NAME1` become" (a rule decides that once tagged)
   but "should this table's columns be scanned for the identifier family at all" — a coarse,
   low-stakes classification.
3. **Reference-list *contents*, proposed once, applied deterministically forever after.** §1.8's
   tier-2 country-alias dictionary (`UK`→`GB`, `Deutschland`→`DE`, ...) is exactly this shape: small,
   reviewable once in the existing review screen, then a plain lookup table with no further model
   involvement — never a live "ask the model every load" mechanism. Same for a locally-observed
   legal-form abbreviation that §1.7's reference join does not resolve (e.g. an abbreviation
   `LegalForm` genuinely does not have on file for that country) — the model can *propose* a mapping
   to review, never write one directly.

**Never**: choosing a transformation for a specific column from the column's *values* (that is what
§1's rules already do, deterministically, and the confidence numbers in this document prove they
can); validating a check digit (a fixed algorithm, not a judgement call); deciding a rule's
`severity` (§2 — a human, since a `REJECT` throws away real rows).

---

## 5. Reference data

### 5.1 What already exists on this instance (READ (live), `find_dataspace`/`list_tables`/`describe_table`)

- **`Geographies/Geographies`**: `/root/Country` (ISO 3166-1; primary key `Alpha2Code`; also carries
  `Alpha3Code`, `NumericCode`, `Name` (English), `NameFR` (French), and — directly useful for
  §1.11's phone rule — `OtherCodes/Dial`, the ITU-T E.164 country calling code), `/root/Subdivision`
  (ISO 3166-2), `/root/BusinessRegion`.
- **`Currencies/Currencies`**: `/root/Currency` (ISO 4217; primary key `AlphaCode`; also
  `NumericCode`, `Symbol`, `Label`, `Decimal` places, `Active`).
- **On the target `Companies` model itself** (dataspace `Companies`, dataset `Companies`, READ
  (live) `get_query_schema`): `/root/ReferenceData/LegalForm` (ISO 20275 — `Code`, `Name`,
  `TransliteratedName`, `Abbreviation`, `Country` (FK → `Country`), `Jurisdiction`, `Language`,
  `Status`); `/root/ReferenceData/RegistrationAuthority` (GLEIF RA list); `/root/ReferenceData/
  IdentifierType` (`Code`, `Label`/`LabelFR`, **`pattern`** — a regex already stored per identifier
  type: `LEI` → `[A-Z0-9]{18}[0-9]{2}`, `SIREN` → `[0-9]{9}`, `SIRET` → `[0-9]{14}`, `VAT` →
  `[A-Z]{2}[A-Z0-9]{2,13}`, `BIC` → `[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?`, `MIC` → `[A-Z0-9]{4}`,
  `WIKIDATA` → `Q[0-9]+`, `DUNS`/`OCID`/`TICKER`/`SPGLOBAL`/`QCC`/`NATIONAL_REG` with no pattern
  stored, READ (live) — full table read); `/root/ReferenceData/ActivitySector`, `/root/ReferenceData/
  NaceCode`. There is also a `consolidation` dataset (aliased `ds3` by `get_query_schema`) holding
  the target model's own `TableMapping`/`FieldMapping`/`MatchingCriteria` (Match and Merge
  consolidation config) — out of scope for this study but confirms the target model already has a
  mature mapping/rule vocabulary of its own that a "target already exists" project (`PLAN.md`,
  "Suggestion du staging") should read rather than duplicate.

### 5.2 How a normalisation rule should use them — recommendation

**Never embed a copy.** Every reference list in §1 (country, currency, legal form, identifier shape)
already exists on this instance in a form more authoritative than anything the factory would invent:
`IdentifierType.pattern` is measurably *stricter and more precise for some types, looser for others*
than the catalog's own tag regexes (§1.6's VAT comparison) — the factory should read whichever is
narrower for a given identifier, not pick one source dogmatically. Two consumption paths, both
needed:

1. **Project targets an existing model (`PLAN.md`'s "La cible existe" mode)**: the target model's
   own `LegalInformation.legalForm`/`Address.Country`/`LegalInformation.capitalCurrency` are
   *already* foreign keys to exactly these reference tables (READ (live) `get_query_schema` on
   `Companies` — every one of these three fields is listed `[FK -> LegalForm]`/`[FK -> Country]`/
   `[FK -> Currency]`). A normalisation rule targeting one of these attributes needs no new
   reference lookup of its own at all — it only needs to resolve the landing value to the target's
   *existing* primary key (`LegalForm.Code`, `Country.Alpha2Code`, `Currency.AlphaCode`) and let the
   existing foreign key do the enforcement (§2.1's "prefer a real FK" row).
2. **Greenfield project (no target model)** that still wants a country/currency/legal-form
   attribute: generate a **foreign key straight to `Geographies/Country`, `Currencies/Currency`, or
   the reused `LegalForm` table**, rather than copying rows into a project-local reference table —
   the same "couplage par la donnée" principle `PLAN.md` already states for the catalog and
   Connectors ("la factory lit les tables de Connectors et du catalogue, et se dégrade proprement si
   un module manque", READ `PLAN.md`, "Principes") applies equally to these reference datasets.
   **Mechanically**, a cross-dataspace FK from a project's own `Companies_Staging` dataset to
   `Geographies`/`Currencies` needs the same `osd:tableRef` facet EBX foreign keys always use
   (CLAUDE.md §5) — no special-casing beyond pointing `tablePath` at the external dataset; this was
   **not tested live** in this read-only session (no `apply_data_model_patch` call was made) and
   should be confirmed by the implementing lot, particularly whether `StagingModel`'s existing
   FK-building code (`StagingModel.java:317-344`, READ, references) already assumes the target is
   inside the *same* project dataset — reading the file shows it builds `tablePath` from
   `pReferenceEntity`'s own generated table, so **cross-dataset FK generation is new code, not
   something the existing `StagingModel` already does** (confirmed by reading, not inferred).

**Cross-dataset SQL, for the implementing lot's benefit**: this study could not run one live SQL
statement joining `Companies_Landing.ACCOUNT` against `Geographies.Country` — the `execute_query`
MCP tool takes exactly one `dataspace`/`dataset` pair (confirmed by its own parameter schema) — so
the country-coverage numbers in §1.8 were computed by reading both sides separately and joining in
this document, not in EBX. The real mechanism for a cross-dataset join **does** exist and is
documented in `docs/SOURCE-RELATIONS.md` (READ `SOURCE-RELATIONS.md`, §5, this study's own sibling
document): `Adaptation.createQueryBuilder().addDataset(alias, otherAdaptation)` from Java, or
`get_query_schema`'s own external-dataset aliasing (`ds1`/`ds2`/`ds3` seen live in §1.7/§5.1 above,
already resolved automatically by the MCP tool because `Companies` itself references those
datasets) — the transfer engine, running as Java against the real `Adaptation` API, has no such
one-dataspace-at-a-time limitation.

### 5.3 Validation libraries on the server classpath

VERIFIED (unzip/javap) against the exact path the task gave
(`.../ebx-ps-fasttrack-server/lib/commons-validator.jar`, `commons-lang3.jar`):

- **`commons-validator` `routines` package**: `IBANValidator` (mod-97, has a per-country
  `Validator[]` table, `IBANValidator.Validator` inner class — the exact tool for §1.6's IBAN
  check), `EmailValidator`, `UrlValidator`, `CreditCardValidator`, `CurrencyValidator` (wraps
  `java.util.Currency`/`BigDecimalValidator` — validates a *formatted amount* against a currency's
  locale conventions, **not** an ISO-4217-code-is-valid check; that check is simply
  `java.util.Currency.getInstance(code)` not throwing, a plain JDK call, not commons-validator's
  job), `InetAddressValidator`, `ISBNValidator`/`ISSNValidator`/`ISINValidator`, `DomainValidator`.
  A `routines.checkdigit` sub-package has the reusable primitives: `LuhnCheckDigit` (§1.6, SIREN),
  `IBANCheckDigit`, `ModulusCheckDigit`/`ModulusTenCheckDigit`/`ModulusCheckDigit(int)` (a generic
  ISO 7064-family base a `LEICheckDigit` could plausibly be built from — **no ready-made LEI
  validator exists in this jar**, confirmed by the full class listing; a check-digit implementation
  for LEI is new code, following the same mod-97 shape `IBANCheckDigit` already uses, INFERRED from
  the two algorithms being the same ISO 7064 family, not independently verified against a real LEI
  by running the code — none was executed in this read-only session). **No IBAN-country-specific
  VAT validator, no BIC-shape validator, no SIREN/SIRET-specific validator class** exist either —
  every one of those needs the *pattern* (from `IdentifierType`/catalog tags, §5.1) plus a generic
  check-digit primitive (`LuhnCheckDigit`, or a new small class for LEI/IBAN's shared algorithm),
  not a ready-made class.
- **`commons-lang3.StringUtils`**: `isBlank`, `normalizeSpace` (collapses inner whitespace runs —
  directly the `COLLAPSE_SPACES` step, §3.3), `stripAccents` (directly the `STRIP_ACCENTS` step).
- **No phone-number library** (e.g. libphonenumber) is present on this classpath — confirmed absent
  by the same `unzip -l` listing (no matching jar in the `lib/` directory at all, not merely absent
  from these two named jars). This matches the task's own framing: `Fasttrack.lib.phone` and
  `Fasttrack.lib.validator` are **EBX Script libraries**, not Java classes — `com.on.ebx.factory.*`
  is a plain Java web module (READ `ebx-mdm-factory/.classpath`, `build.sh` — `javac --release 8`
  against `server/lib/*.jar`, same convention CLAUDE.md §9 documents generally), and EBX Script
  cannot be called from Java the way Java can be called from EBX Script (there is no "call an EBX
  Script library from a servlet" API surfaced anywhere in this codebase or in CLAUDE.md).
  **Implication**: the factory's `NORMALIZE_PHONE` step (§3.3) either re-implements E.164
  normalisation itself in Java (the rule measured in §1.11 is simple enough — strip separators,
  default the country prefix from `Country.OtherCodes.Dial` — that a full phone-number library may
  not even be necessary for what this data actually needs) or the transfer engine calls out to the
  EBX Script library via **Data Services REST**, the same "couplage par la donnée" pattern already
  used for the catalog. **Not designed further here** — a genuine open question for the implementing
  lot, since it trades a small amount of new Java code against a live HTTP round-trip per value.
  `commons-validator.jar`/`commons-lang3.jar` are **not currently on `ebx-mdm-factory`'s own
  classpath** (READ `ebx-mdm-factory/.classpath` — neither jar is listed) — adding either requires
  an explicit `.classpath` entry, exactly the gotcha CLAUDE.md §10 documents ("a jar physically
  present in `lib/` is not automatically on the build path").

---

## 6. Quality report

**What the Cockpit should show, per the task**: per rule, rows fixed / rejected / warned; a trend
per load. **Where it lives**: a new table, `QualityOutcome`, written by the transfer engine once per
`(rule or step, load)` pair — the same "one procedure per line" discipline every other
`PipelineStep`-adjacent writer in this codebase already follows (READ, e.g., `PLAN.md`'s own note on
`LandingGenerator`: "chacune tracée dans `PipelineStep` dans sa propre procédure"). Proposed shape:

| field | shape | notes |
|---|---|---|
| `id` | id | |
| `project` | FK → `Project` | |
| `loadId` | string | matches `LandingTable.lastLoadId`'s own format (`L20260920-131535-1`, READ `PLAN.md`) so a report row and its landing load are directly cross-referenceable |
| `entity` | FK → `StagingEntity` | |
| `source` | polymorphic reference: either a `MappingStep` (a normalisation rule) or a `QualityRule` (a validation rule) — stored as two nullable FKs, exactly one set, same idiom as `StagingAttribute.referenceEntity`/`fromRelation` already using a nullable-FK-pair-style link (READ `MdmFactory.xsd`, `StagingAttribute`) | |
| `ruleLabel` | text, denormalised | survives the source row being edited or deleted later — the report must still read even if the rule that produced it changes |
| `fixedCount` / `changedCount` | int | rows a normalisation step actually altered (0 is a legitimate, informative value — as measured throughout §1, several rules are true no-ops on real loads, and the report should say so plainly rather than omit the row) |
| `rejectedCount` | int | rows a `REJECT`-severity check stopped |
| `warnedCount` | int | rows kept, flagged |
| `sampleMessage` | text | one representative message, for the Cockpit tile |
| `recordedAt` | dateTime | drives the "trend per load" chart |

**Cockpit**: a table grouped by rule (current load's counts) plus a per-rule sparkline/trend across
`recordedAt` — the same shape `PLAN.md` already describes the Cockpit's existing "dernière exécution
par étape" feature using (READ `PLAN.md`, lot 2's "Cockpit amélioré"), extended with one more grouped
section. Not designed pixel-by-pixel here — `docs/mockups/mdm-factory.html` is the existing,
the product owner-approved mockup vehicle for that (`PLAN.md`, lot 2), and this document is research/design,
not UI.

---

## 7. Lot plan

Sizes: **S** = a focused change in one or two existing files; **M** = a new table/class plus its
wiring; **L** = a new subsystem. Ordered so each lot leaves something reviewable/demoable on its own,
per the review-before-anything-is-generated principle this whole codebase already follows.

1. **[M] `MappingStep` + `QualityRule` tables in `MdmFactory.xsd`.** Pure schema work: two new
   tables (§3.2, §2.3), the `MappingStep.transformation` enum (§3.3), the `ReviewLine.nature`
   additions (`MAPPING_STEP`, `QUALITY`). Unblocks every later lot; no behaviour yet. Verify the
   `xs:pattern`-facet-is-blocking assumption (§2.1's one unverified row) with a real
   `apply_data_model_patch` call as part of this lot, before any rule depends on it.
2. **[M] A pure, unit-tested `QualityRules` class** (mirroring `StagingRules`'s own shape and
   docstring conventions, READ `StagingRules.java`), implementing §1's deterministic rules as pure
   functions over `CatalogReader`-shaped input (column name, inferred type, profile figures, catalog
   tags) — no EBX class, testable against fixtures built from this document's own measured numbers
   (0/55 whitespace, 52/52 country, 49/55 legal form, etc., as regression fixtures). Produces
   `MappingStep`/`QualityRule` proposal objects, written nowhere yet.
3. **[S] `CatalogReader` extension**: read `ProfileSnapshot`'s two-most-frequent-value figures if
   available (needed for §1.10's boolean-word derivation) — **not currently exposed by
   `CatalogReader`** (its `Column` class carries `nullRate`/`distinctCount`/`uniqueRate`/`maxLength`
   but no "top values", confirmed by reading `ProfileSnapshot`'s own field list, which itself has no
   top-value field either — `ProfileSnapshot` stores `minValue`/`maxValue`/`meanValue` etc. but never
   a most-frequent-value list, so §1.10's "derive from profile" idea needs either a new
   `ProfileSnapshot` field upstream in the catalog (out of scope here) or a direct, on-demand
   `GROUP BY`/`COUNT` over the landing table itself at proposal time, the same shape every §1 count
   in this document used — **recommend the latter**, no catalog change needed). Flag this
   correction explicitly to whoever implements lot 2, since §1.10 as written above assumes a profile
   field that does not exist.
4. **[S] `StagingWriter`/`StagingSuggester` wiring**: write `QualityRules`' output as
   `MappingStep`/`QualityRule` rows, `PROPOSED`/`RULE`, idempotent the same way `StagingSuggester`
   already is (`ALREADY_EXISTS`-style re-run safety, READ `PLAN.md`'s own note on it). Demoable at
   this point: run it against the real `Companies_Landing` data this study measured and confirm the
   proposal counts match this document's numbers exactly (a strong regression check, since the
   numbers are already known).
5. **[M] Review-screen extension**: `MAPPING_STEP`/`QUALITY` `ReviewLine` natures rendered and
   decided through the existing `ProposalReviews`/`ReviewRules` machinery (§2.3, §3.2) — reusing
   `ReviewRules.canDecide`'s existing cascade logic (a step under a not-yet-accepted mapping, a rule
   under a not-yet-accepted attribute, refused with a named reason, exactly the pattern already
   proven for `StagingAttribute`/`AttributeMapping`, READ `ReviewRules.java`).
6. **[M] `QualityOutcome` + Cockpit section** (§6), written by whichever module ends up owning
   `transfer/Transformations` — this lot is a coordination point with that other, separately-owned
   work, not something to build ahead of it; the table shape (§6) can be finalised and reviewed now
   so the transfer engine has a fixed target to write to from day one, rather than retrofitting a
   report table after the transfer engine already exists.

---

## 8. Open questions

- **Postal code per country** (§1.11): no reference table or pattern set exists on this instance
  today for this specific rule from the task's own list — genuinely unresourced, not merely
  unimplemented; needs either a new small reference table or an external pattern set, out of scope
  to design here.
- **`xs:pattern` as a blocking facet** (§2.1): reasoned by analogy to `maxLength`, not verified with
  a real `apply_data_model_patch` call in this read-only session — first thing to confirm in lot 1.
- **Java-side enumeration pre-check** (§2.1): reasoned as "Java, unlike EBX Script, can read a
  schema node's legal enumeration values directly" — INFERRED from the general shape of the EBX Java
  schema API described elsewhere in CLAUDE.md, not confirmed by reading the specific API class in
  this session (no write/schema-introspection call was made).
- **LEI check-digit algorithm**: ISO 17442 uses the same ISO 7064 MOD 97-10 family as IBAN
  (`IBANCheckDigit`, VERIFIED present); no ready-made `LEIValidator` exists in `commons-validator`
  (§5.3) — a from-scratch implementation following that same algorithm is INFERRED sound but not
  independently run against a real LEI checksum in this session.
- **SIREN's exact check-digit weighting** against `LuhnCheckDigit`'s (§1.6): not independently
  verified digit-by-digit in this session.
- **Cross-dataset FK generation** (§5.2): confirmed by reading that `StagingModel` does not do this
  today; the mechanics of adding it (an `osd:tableRef` to a dataset outside the project) were not
  tested live.
- **Phone normalisation implementation path** (§5.3): re-implement in Java vs. call the EBX Script
  `Fasttrack.lib.phone` library over Data Services REST — a real trade-off, not resolved here.
- **Non-breaking-space whitespace defects** (§1.1): untested, not confirmed absent.
- **`ACCOUNT.PAYMENT_TERMS`-shaped free text as a decimal source** (§1.5): no live evidence either
  way; the decimal-parsing rule's real-data impact on this project remains unmeasured.
