# Staging polish, lot H5 (2026-10-17, `fix/staging-polish`, from `main` a11e0517)

Three polish items left over from lots C, D, E and G2. Parallel lots (H1 preflight, H2 e2e scripts, H3 code transcoding, H4 refusal classes) were not touched.

## 1. Name-aware TITLE_CASE (`quality/CleaningSteps#titleCase`)
Before: lower-case the whole value, capitalise after white space, hyphen, apostrophe, parenthesis or slash. `MCDONALD` gave `Mcdonald`, `D'ARTAGNAN` gave `D'Artagnan` in the middle of a name,
`DE LA FONTAINE` gave `De La Fontaine`. One function, so the three callers agree by construction: the normalisation pass (proposal and its `applyKind` counts), the verifier (`StepChainCheck`,
`RegexSampleCheck`, `CrossSystemFold`, the matrix reader) and the loader (`FeedRules`, `StagingLoader`) all go through `CleaningSteps.applyStep`.

Rules, decided per WORD (cut at white space, hyphens/dashes, slash, parentheses, comma, dot) and per PART (an apostrophe, straight, typographic or modifier, cuts a word into parts):
1. `JEAN-PIERRE` -> `Jean-Pierre`; `J.R. SMITH` -> `J.R. Smith`; `O'BRIEN` -> `O'Brien`; `N'GUYEN` -> `N'Guyen`.
2. A part with a digit is kept as it is (`3EME`, `B2B`, `12a`).
3. A part that already mixes capitals and lower case is kept as it is (`McDonald`, `MacDonald`, `DiCaprio`, `De`). This is the profile's own rule ("mixed, left as it is"): only all-capitals
   and all-lower-case parts move, so a column of mostly capitals no longer destroys the internal capitals a few rows carry.
4. Otherwise lower-case (`Locale.ROOT`, string-level so Greek final sigma is right) and give the first LETTER its title-case form by code point (`ELODIE` -> `Elodie`, accents kept,
   supplementary-plane letters handled).
5. **Mc** prefix: `MCDONALD` -> `McDonald` (at least three letters after `Mc`, letters only; `MCA` stays `Mca`). **Only Mc**: `Mac` is not safe (`MACHIN`, `MACON`, `MACRON`), so `MACDONALD` ->
   `Macdonald`; a source that wants `MacDonald` writes it that way and rule 3 keeps it.
6. **Particles** `de du des del le la van von der den di da dos`, lower case when the word is neither the first nor the last word of the value (`JEAN DE LA FONTAINE` -> `Jean de la Fontaine`,
   `LUDWIG VAN BEETHOVEN` -> `Ludwig van Beethoven`, `SAINT-JEAN-DE-LUZ` -> `Saint-Jean-de-Luz`, `RUE DU COMMERCE` -> `Rue du Commerce`); the first word stays capitalised (`DE GAULLE` -> `De Gaulle`,
   `LA ROCHELLE` -> `La Rochelle`), and so does the last (`THI LE` -> `Thi Le`).
7. **Elisions** `d'` and `l'` follow the same list: lower case after the first word, `D'`/`L'` as the first word (`CHARLES D'ARTAGNAN` -> `Charles d'Artagnan`, `D'ARTAGNAN` -> `D'Artagnan`,
   `RUE DE L'EGLISE` -> `Rue de l'Eglise`, `L'OREAL` -> `L'Oreal`). `O'` is not an elision: always capital. An elision is allowed as the last word of a value (it always has its noun after it).

Documented limits: a surname particle in the middle of a name is indistinguishable from the French article (`NGUYEN LE HUNG` -> `Nguyen le Hung`); the list is nobiliary typography, it is not
keyed on any column name or domain. `NormalisationProfiler.caseOf` still classes `Jean de la Fontaine` as "mixed" (a lower-case word starts a run), which only affects the pass's deviation
tally, not what the step writes; left alone (H3 works in the pass).
Tests: `TitleCaseNamesTest` (the distinct names, cities and addresses of the B2C retest files, every rule above, idempotence, Turkish default locale, Greek sigma, a supplementary-plane letter),
`CleaningStepsTest`, `NormalisationPassTest`, `ProposalWordingTest` unchanged and green.

## 2. Volume cap and read-once on the proposals' column reads
Before: every proposal read, per mapped column, one `GROUP BY` over the WHOLE landing table (`QualitySuggester.distinctValues`) and, for most of them, a `COUNT(*)` + `COUNT(DISTINCT)` over it
(`exactAggregates`): the normalisation pass, the staging pass (shapes, the emptiness probe, `verifyTyped`), the capability pass (values, fill, phone mix) and the quality passes each asked again.
At 1.4 million rows times forty columns that is minutes of kernel time per proposal (CLAUDE.md sec 2: `GROUP BY` under a filter 2-6 s, `COUNT(DISTINCT)` 4.5-9 s).

**Guard** (`quality.ScanGuard`, pure; `ebx.ColumnReads`, the EBX half). JVM properties, read at each call (no restart):
- `mdm.factory.profile.maxRowsFullScan`, default **200000**: a landing table with more rows is read on a sample; 0 or less switches the guard off (every read is whole, the table is not even counted).
- `mdm.factory.profile.sampleRows`, default **50000** (never above the first bound): the rows read from such a table.
The sample is the FIRST N rows of the table in its own order (primary key order): deterministic, the same rows at every read of an unchanged table, and bounded by construction (a `RequestResult` iteration
stopped after N rows, ~20 microseconds a row). A random or hash sampling was not used: the Request API has no server-side sampling, the EBX SQL reference of the instance (`get_query_syntax`, read 2026-10-17) lists no `TABLESAMPLE`, a `WHERE` on a hash scans the table, and
a `GROUP BY` on a sub-select with `LIMIT` might be pushed below the aggregate by the planner but nothing in the dialect reference promises it (and CLAUDE.md sec 2 lists the planner traps), so only the iteration is certainly bounded. **Not measured live** (this lot may not deploy): the row count uses `RequestResult.getSize()` once
per table and modification date; the first-rows order of `createRequestResult(null)` is the primary-key order per the Javadoc of `Request` (no sort criteria), to be confirmed on the first run at volume.
A sample of the first rows can be biased when the key follows load order (e.g. one source loaded first): the verdicts say "a sample of N rows", never "the column".

**Sampled evidence says so.** `ColumnQuery.sampledRows` / `ExactAggregates.sampledRows` (0 = whole table); `NormalisationProfile.sampleRows` (`markRowSample`: also `sampled = true`, and NO code list is claimed
from a row sample); `Detectors.propose(..., sampleRows)` rationale "Measured on a sample of 50,000 rows (sample)." with the sampled confidence cap; `NormalisationPass` step rationale "(measured on a sample of N
rows)"; `TransformVerifier.verify(..., sampleRows)` sentence "(on a sample of N rows)" (was "(most frequent values)"); `ValueEvidence.sampleRows` and the `ConstraintSanity` refusal "(a sample of N rows; 0 % of its
rows)"; the quality pass' column JSON carries `sampleRows`. Capability `AttributeInfo`: rows = the counted total, fill rate from the sample, distinct count unknown (null) on a sample.

**Callers.** All reads go through `distinctValues` (guarded and cached for every caller: normalisation, staging shapes / `LandingRuleProbe` / `verifyTyped`, capability values and phone mix, quality passes).
`QualitySuggester.boundedAggregates` (new) is used by the proposal passes that read a RATE (normalisation `readColumn`, capability fill and derivation fill); `exactAggregates` stays exact for the quality pass'
`UNIQUE` / fill rules (an exact claim cannot rest on a sample; its examples come from the guarded `distinctValues` and are flagged sampled as soon as the exact distinct count exceeds what was read).

**Read once** (`quality.ReadCache`, pure, bounded): `ColumnReads.DISTINCT` / `AGGREGATES` / `ROWS`, key = user | dataspace | dataset | table | column [| scope], stamp =
`AdaptationTable.getLastModificationDate()` (any write to the table makes an entry unusable; an unknown date never caches), TTL 10 minutes, 400,000 values in all with least-recently-used eviction, so nothing
outlives the run in heap. One read of the 2000 most frequent values answers the 200 of the shapes and the 50 of the probe (`ColumnQuery.coversCap` / `withCap`: a longer top-N list holds the shorter one; a
list shorter than its cap holds every distinct value, so it answers any cap).

Tests: `ScanGuardTest` (bounds, decision at/above the bound, uncounted table, guard off, live property, wording, sampled proposal wording), `ReadCacheTest` (read once, new modification date, unknown date, TTL,
LRU by weight, oversize), `ColumnReadsTest` (cap reuse, sample flags, aggregates of a sample), `ProfileRowSampleTest` (profile, no code list, case step, verifier), `ConstraintSanityTest` (sample wording).
Properties documented here only (JVM `-D`, like `mdm.factory.masterLoad.*`); no XSD or bundle change.

## 3. One source for the counts of step 4 and the Mapping matrix
Lot C noted that step 4's "waiting" counts the cleaning steps (and, before lot C, the optional look-ups) while the matrix does not, so "13 points / 68 others" on step 4 could disagree with the matrix.
Lot C already made the line quote the matrix' `needYou` and `acceptAll`, but kept a fallback that subtracted from `Step4Counts.waiting()` when `acceptAll` was unknown, and the rule still took `waiting`
as a parameter. Now (`MatrixCounter.Tally` is THE source):
- `WizardRules.step4MatrixNote(needYou, acceptAll)` replaces both `step4ReviewNote` overloads: no `waiting` input, no subtraction; an unknown figure (either < 0, the matrix could not be read) says nothing.
- The kind of sentence comes from the rule the matrix header uses: new `MatrixCounter.headlineOf(needYou, itemsWithTheButton)` (`Tally.headline()` calls it too): NEED_YOU -> "N points need your attention,
  the other M go with one click" (M = `acceptAll`, the footer button's N); ONLY_BUTTON -> "Nothing flagged: one click accepts everything"; NOTHING_LEFT -> no line (the matrix itself says "Nothing left to
  decide"; step 4 used to say "one click accepts everything" with nothing to accept).
- `WizardPage.Model#matrixFigures(Tally)` is the one way the figures reach the page (the service and the tests both use it; `null` = unknown).
- `Step4Counts` is unchanged and still decides the primary block (REVIEW / BUILD / CONTINUE) and the "N proposals waiting for review" line of the side cards: those are labelled as proposals of every
  nature, not as "points", and are not quoted in the sentence.
Test `Step4MatrixCountsTest`: four fixtures (UxCrmSf2 shape 17 + 99, PersVol9-like with eight items, the same all open, a project with nothing flagged and 40 for the button, plus a project with nothing left),
the matrix is RENDERED (header, footer count, footer button) and the wizard step 4 is RENDERED, and the figures read back from the two HTML pages are compared (14 comparisons); a proposal of the wizard's
own tally is then decided to show the sentence does not follow it. `WizardC3StepsTest` and `StagingScreensTest` follow the new rule.

Not done / to decide: step 4's gate (`step4Primary`) still reads `Step4Counts.waiting()` (cleaning steps included), so a project whose only open items are PROPOSED cleaning steps stays in "Review the
proposal" while the matrix reads "Nothing left to decide. You can build and load" (its sweep decides the cleaning steps with the bulk click). The sentence no longer lies about it (it says nothing); changing
the gate would change when "Build and load" appears and is a product decision.
