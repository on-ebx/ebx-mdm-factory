# Staging test fixes, lot D - a deterministic, verified normalisation pass

Written 2026-10-17, branch `fix/staging-normalisation` (worktree `ebx-mdm-factory-stg-normalise`, from main `4a8806c0`).
Source: the live test of the staging step on project TiersB2CRetestLanding (`test-staging-b2c-2026-10-17/COMPTE-RENDU.md`).
Sibling lots: A (`fix/staging-proposal-quality`: computed fields, modelling, labels), B (`fix/staging-load-rejects`: loader and
rejects), C (`fix/staging-screens`: wizard step 4, matrix UI).

## 1. What was measured

Every staging proposal came from the language model: 40 links with transforms, 3 cleaning steps, 15 rules, 11 controls; the rule
pass proposed none. The module already owns a catalogue of verified cleaning steps (`MappingStep.kind`, each checked on the
landing values: `valuesSeen` / `valuesChanged` / `examples`), but only `quality.Detectors` (trim, collapse, empty-to-null) proposed
any, and only in the optional quality stage. Three consequences, all in the report:

- `date_naissance` written `10/06/1963` was mapped with `isodate()` (a step that only cuts an ISO text): the matrix showed
  `10/06/1963 -> 10/06/1963`, EBX refused the 140 rows of the loyalty source at the load, and the link sat at 48 % outside the
  points the matrix flagged.
- the language model's only step, a `STRIP_CHARS` with argument ` .+()-` on the mobile phone, strips the `+`; a pipeline `digits()` did
  the same: `+33 6 46 86 05 23` became `33646860523`.
- the model never saw how the dates were written: the default sensitive-column list of the data policy (`*birth*`, `*naissance*`)
  keeps even the SHAPES of such a column from it.

## 2. Design

Nothing here reads a column or attribute name, a tag or a business vocabulary (standing rule, CLAUDE.md sec 15). Every decision is
a regular expression over the VALUES, or the structure of the proposal (which sources feed the same attribute).

```
 staging/NormalisationProfile   value object: counts per column                        (pure)
 staging/NormalisationProfiler  values + row counts -> profile                         (pure)
 staging/NormalisationPass      profile -> catalogue steps, case across sources,       (pure)
                                reference transcoding, codes to align
 staging/TransformVerifier      chain + sample + target type -> OK / FIXED / FAILING   (pure, engine injected)
                                / AMBIGUOUS / UNVERIFIABLE
 staging/CheckNote              the "Value check" suffix of a rationale                (pure)
 ebx/NormalisationSuggester     reads, writes MappingStep rows, fixes / flags mappings (EBX)
 ebx/NormalisationHooks         the three call points of the proposal run              (EBX)
```

### 2.1 The value profile (`NormalisationProfiler`)

Input: the distinct values of the landing column with their row counts, the bounded sample (2000 distinct values) that
`QualitySuggester.distinctValues` already reads inside EBX - no extra connector call, nothing leaves the instance. Output, all
weighted by rows:

| measure | how |
|---|---|
| spaces | rows with a leading / trailing space; rows with a run of spaces or a non-breaking space |
| empty-ish placeholders | the fixed list of `Detectors` (`N/A`, `-`, `?`, `NULL`...) |
| letter case | UPPER / LOWER / TITLE (every word starts with a capital) / MIXED, per row; digits-only rows have none |
| accents | rows `CleaningSteps.stripAccents` would change |
| dates | ISO, ISO date-time, `a/b/yyyy`, `a.b.yyyy`, `a-b-yyyy` (with or without a time), `yyyy/MM/dd`, `yyyyMMdd` (1900-2100); an impossible date is no date. For the `a/b/yyyy` families the **order evidence**: rows only valid day-first, only valid month-first, valid both ways |
| numbers | integer, decimal dot, decimal comma, thousand separators (dot+comma, comma+dot, space) |
| phones | phone-like shape (7 to 15 digits, separators, no letter; a bare digit run counts only when it starts with 0), rows with `+`, with `00`, national `0`, the separator characters found |
| booleans | universal words (true/false/1/0/yes/no/oui/non/vrai/faux/...) |
| repeated codes | at most 30 distinct values, each repeated at least twice on average, not sampled |

`NormalisationProfile#factsLine` renders it for the language model as one `, measured: ...` sentence per column: counts and format
names only; the codes only at the data policy's `SAMPLE_VALUES` level.

### 2.2 The deterministic pass (`NormalisationPass`), origin `RULE`

Per mapped column of a text attribute:

- `TRIM`, `COLLAPSE_SPACES`, `EMPTY_TO_NULL`: `quality.Detectors#propose` (the single-column rules the quality pass already owns, now
  available before the model passes).
- Phone-like column: `STRIP_CHARS` of the separators found - **never the `+`**. The E.164 normalisation stays with the computed field
  (`Fasttrack.lib.phone.toE164`), which needs the kept `+`.
- Letter case across sources: for an attribute fed by two sources or more, the case most rows use (at least half the letter-bearing
  rows, strictly more than the runner-up) becomes the common one: `UPPER`, `LOWER` or the new `TITLE_CASE`. A mapping gets the step
  when it deviates (for a title-case target: when it is written in capitals or in lower case, so a `van der Berg` of a title-case
  source is never touched). Never in front of a pipeline whose regex reads letter case (`CaseOrderRule`), never on a pipeline that
  already changes case, never with a transcoding, never when the mapping already has a case step. Measured on the B2C files: tickets
  last names (capitals) get `TITLE_CASE`, web and loyalty (title case) none; loyalty e-mails (109 lower, 31 upper) get `LOWER`.
- Reference transcoding: only when a configured `ReferenceBinding` (flat lookup) resolves at least 95 % of the column's rows and
  would rewrite some - `COUNTRY_TO_ISO2` / `CURRENCY_TO_ISO` / `TRANSCODE_REFERENCE`. No reference, no step.
- Codes to align: the short code lists of two sources that feed one attribute and share fewer than half of their codes (each has codes
  the other lacks, compared after the steps above and `CleaningSteps.normalizeKey`) are **flagged** for a person - counts only,
  no mapping invented. Two samples of the same population (first names) overlap and are not flagged.

Every row is written `PROPOSED`, origin `RULE`, rationale starting with `Normalisation: ` (`NormalisationPass.MARKER`), measured
confidence (mechanical 100, case 90 / 80, phone 95, transcoding = coverage; capped at 80 when only a sample was read), evidence
`valuesSeen` / `valuesChanged` / `valuesUnresolved` and `examples` as SHAPES (`Aaaa -> AAAA`), never a value. An own row still
`PROPOSED` is refreshed or withdrawn on a re-run; a row a person decided on is never touched. The marker also exempts these rows
from `QualitySuggester`'s withdrawal sweep (an `UPPER` / `LOWER` / `STRIP_CHARS` row of origin `RULE` is otherwise swept as the
leftover of a removed heuristic).

### 2.3 The verifier (`TransformVerifier`)

For every `PROPOSED` mapping - whoever wrote it - the chain is run on the sample **as the loader runs it**: the mapping's non-rejected
steps in position order (`CleaningSteps.applyPipeline`), then its pipeline - or the pipeline its type-driven transformation
translates to (`PipelineCompat`) - through `Transformations.apply`, the engine of `on-ebx-mapping.jar`.

- A typed target (`DATE`, `TIMESTAMP`, `DECIMAL`, `INTEGER`, `BOOLEAN`) must receive its xs lexical form (`isValidFor`); a value that
  comes out empty, throws, or comes out in another form fails.
- A phone-like column keeps its leading `+` whatever the target.
- On failure the profile's candidates are tried (`dateCandidates`, number variants shown by the profile, a `map` of the boolean
  vocabulary found, the pipeline without its `digits` step) and the chain is **replaced only by a candidate that converts every
  non-empty sampled value** (`FIXED`; written to `AttributeMapping.pipeline`, which overrides the type-driven transformation).
- `a/b/yyyy` is day-first only when some value has a first number above 12, month-first only when some has a second number above 12;
  a mixture gives no candidate; when neither is provable the candidate is `AMBIGUOUS`: **not applied**, the link is flagged.
- Otherwise the link is flagged (`FAILING`): its confidence is set to the **measured success rate** and never above 59 (one under
  the 60 % bar of "accept all", `MatrixCompute.DEFAULT_BULK_THRESHOLD - 1`), and a note is appended to its rationale behind
  ` || Value check` with the reason (counts and pipelines, no value) and the confidence it had (`[was 85]`). A later run that verifies
  clean removes the note and restores the confidence (`CheckNote`).
- A chain that reads another column, a live lookup or the clock (`concat`, `join`, `fk`, `lookup`, `golden`, `mdmstate`, `constant`,
  `now`...) is `UNVERIFIABLE` and left alone; a mapping of a collection feed is skipped.
- A `STRIP_CHARS` step of the language model (`PROPOSED`) that strips the `+` of a phone-like column is repaired: the `+` is removed
  from its argument and a note says so.

The Mapping matrix gets a live twin: `AttentionRules.CONVERSION_FAILS` ("needs your eye", reason "this rule gives N of the M sampled
values that the DATE field cannot store"), computed from the outputs of the sample the matrix already runs
(`MatrixModel.Sample#afterValues`, `TransformVerifier#invalidOutputs`). A flagged cell is already excluded from the matrix's
"accept the N proposals >= T %" (`MatrixCompute.feedStats`); the confidence cap does the same for the wizard's bulk sweep
(`StagingBulkDecideRun` decides by confidence only).

### 2.4 Where it runs

`StagingProposalRun` (three one-line calls, each appended to its stage's summary; no new stage, so the wizard's stage list is
unchanged):

1. after the rule pass (stage STRUCTURE_RULE): profile the landing, propose the steps on the rule-pass mappings, verify their transforms
   (a project without a language model ends here, already normalised);
2. after the language-model structure pass (STRUCTURE_LLM): the same over the links the model wrote - the case across sources can only
   be decided now that the model unified them; steps written for the rule mappings of a superseded entity are withdrawn;
3. after the quality language-model pass (QUALITY): its steps and their effect on each chain are verified; `endOfRun` drops the cache.

The three calls share one per-run cache of the landing columns read (`NormalisationSuggester.RunCache`, at most 400,000 distinct
values, expires after 30 minutes).

For the language model (point 4 of the brief): `StagingLlmSuggester#fillShapes` already reads a 200-value sample of every text column
the data policy allows; it now also fills `StagingRules.LandingColumnInfo#profileFacts` from it, which `StagingLlmPass#tokenCaseText`
appends to the column's line in the inventory and in `describe_landing_table`. So the model reads, for a date column, `date written
dd/MM/yyyy (100%) - convert it with trim|date(dd/MM/yyyy,yyyy-MM-dd), not isodate()`, and for a phone `never strip the leading +`, and that
the rule pass adds trim / collapse / empty-to-null itself. A sensitive column (the policy) gets no facts: the deterministic side reads it
anyway (nothing leaves the instance) and the verifier fixes the link afterwards - that is the case of `date_naissance`. The quality
language-model pass already lists a mapping's existing steps (`ExistingProposal`), so it sees the RULE steps written before it.

## 3. Files

New: `staging/NormalisationProfile`, `NormalisationProfiler`, `NormalisationPass`, `TransformVerifier`, `CheckNote`;
`ebx/NormalisationSuggester`, `NormalisationHooks`; tests `NormalisationProfilerTest`, `NormalisationPassTest`,
`TransformVerifierTest`, `CheckNoteTest`, `NormalisationHooksTest`.

Touched outside new classes (all small): `quality/CleaningSteps` (+ `TITLE_CASE`, `titleCase`, `Lookups#flatKinds`),
`quality/CaseOrderRule` (+ `TITLE_CASE` changes case), `review/ProposalWording` (+ `TITLE_CASE` wording), `tools/build_xsd.py` and
the generated `MdmFactory.xsd` (+ enumeration value `TITLE_CASE` of `MappingStep.kind`), `ebx/QualitySuggester` (withdrawal sweep keeps
an own row), `ebx/StagingProposalRun` (three calls), `ebx/StagingLlmSuggester` + `staging/StagingRules` + `staging/StagingLlmPass`
(`profileFacts`), `staging/AttentionRules` + `mappingmatrix/MatrixModel` + `ebx/MappingMatrixReader` (`CONVERSION_FAILS`),
`Messages_en` / `Messages_fr` (one block "staging normalisation (2026-10-17)" at the end), `CleaningStepsTest`, `ProposalWordingTest`,
`AttentionRulesTest`.

## 4. Deployment

**XSD change: restart needed** (one enumeration value, `MappingStep.kind` = `TITLE_CASE`; nothing else in the model). A static
enumeration is non-blocking in EBX, so a `TITLE_CASE` row written before the restart would be stored and flagged invalid rather than
refused - but install and restart before running a proposal. No new table, no new field.

## 5. Not done / to watch

- Measured on unit data and on the B2C files; **not yet run live** under Tomcat (nothing installed here, by instruction).
- The notes written into rationales are English (the convention of every RULE row; `ProposalWording` translates only the step words).
- `TITLE_CASE` is not a name-aware rule: a source written in capitals loses nothing it still had, but `MCDONALD` becomes `Mcdonald`.
- An ambiguous day/month order (nothing above 12) is flagged, never guessed. A column mixing ISO and `dd/MM/yyyy` is flagged: no single
  `date()` converts it.
- The verifier judges the sample (the 2000 most frequent distinct values); a rare bad value past the sample is found by the load.
- A mapping already `ACCEPTED` / `APPLIED` is not re-verified (only its live matrix flag).

---

# Lot E - a rule that does not convert must not cost the whole feed

Written 2026-10-17, branch `fix/staging-feed-conversion` (worktree `ebx-mdm-factory-stg-feedconv`, from main `e8026838`).
Source: the retest of project TiersB2CRetestStaging (AgentRun 310, entities 173-176) and four findings the coordinator added the same day.

## E.1 What was measured

- The staging language-model pass proposed the loyalty feed (`Fidelite_Membres_v4`) into `Personne` with `date_naissance` (text `dd/MM/yyyy`)
  mapped by `date(yyyy-MM-dd)`. `Session#proposeFeed` refused the WHOLE feed (the probe: the rule empties at least half of the sampled
  values), the model wrote that the loyalty feed "was not recorded" and stopped: the source stayed alone, unified with nothing.
- The model could not know the format: `date_naissance` is on the data policy's sensitive list (`*naissance*`), which withholds values AND shapes,
  and lot D gave it no profile facts for such a column.
- The `+` of the phone numbers was still stripped: the rule pass's `STRIP_CHARS` (110/111) kept it, then a model's `NORMALISE_IDENTIFIER` with
  argument ` .+-` (112/113) removed it. Lot D's repair only knew `STRIP_CHARS`, and the verifier judged the chain on values the steps had already
  cleaned, so the `+` was gone before it looked.
- The accepted derivation `phoneE164` (`toE164WithDefault`, `defaultCountry` = literal `""`) was empty on all 394 rows: the rationale said "all
  profiled numbers include a leading plus", true of the one mapping its shapes were read from, false of the store tickets (`06.01.31.09.18`).
- Case alignment chose capitals for the web `lastName` ("Andre" -> "ANDRE") because the ticket source, in capitals, had more rows.
- The capability suggester's texts were English on a French project.

## E.2 Design

### E.2.1 Item 1-2: correct, or flag - never refuse the feed (`StagingLlmPass.Session#typedOutcome`)

`RuleProbe` gets a default method `verifyTyped(table, column, pipeline, dataType)` (null by default: a session without a probe keeps every
earlier check). `ebx.StagingLlmSuggester.LandingRuleProbe` implements it with **`TransformVerifier.verify`** itself - the very check lot D runs after
the model - on the column's own distinct values (up to 2000 with their rows, read once per run, not the 50 values of the emptiness probe), through
`Transformations.apply`, with the column's measured `NormalisationProfile` for the candidates. Computed in Java: the session only receives the
verdict, rates and pipeline texts (counts and pipelines, never a value). Not gated by the data policy for that reason (nothing leaves the instance).

For a rule whose attribute is typed (`DATE`, `TIMESTAMP`, `DECIMAL`, `INTEGER`, `BOOLEAN`) and whose column is text or goes through a pipeline:

| verdict | what `proposeFeed` does |
|---|---|
| `OK` | records the rule as it is. The old guess `conversionRefusal` (a text column into a DATE with no `date(...)` in the pipeline text) no longer applies: the values were run. |
| `FIXED` (the profile gives a pipeline that converts 100 % of the non-empty sampled values) | **substitutes it** (e.g. `trim|date(dd/MM/yyyy,yyyy-MM-dd)`), keeps the confidence, appends the `Value check` note `fixed. <sentence>` (`CheckNote`, same as the verifier), and says in the tool answer: `CORRECTED rule birthDate <- date_naissance: 'date(yyyy-MM-dd)' gives a valid date ... for 0 of 140 rows. '<pipeline>' converts all of them.` |
| `FAILING` / `AMBIGUOUS` (nothing converts everything, or only a day/month order no value proves) | **records the feed WITH the rule, flagged**: pipeline untouched, confidence = `min(its confidence, measured rate, 59)` (59 = `MatrixCompute.DEFAULT_BULK_THRESHOLD - 1`), the old confidence in the note (`[was 85]`) so a clean re-check gives it back, the emptiness probe is skipped for it. The answer says `FLAGGED rule ...` and that the source is NOT dropped. |
| `UNVERIFIABLE`, `NOT_CHECKED`, or no probe / unreadable column | the earlier behaviour, refusals included |

Why flagged and not dropped (item 2): it is the verifier's own convention for a link nothing can fix (`FAILING`: confidence under the bulk bar + note), the
Mapping matrix queues it, "accept all" leaves it out, and the loader only runs ACCEPTED mappings - so nothing is loaded wrongly, while a rule left out would
silently lose the column and give the person nothing to look at. After the model, `NormalisationHooks` verifies the same mapping again: it finds the same
verdict and writes the same note (idempotent: the note is replaced, the `was` kept).

The structural refusals are untouched (unknown entity, parent feed, unknown attribute or column, regex rules, comma, case-order, lossy map, order-blind
split, identical outputs...). The outcome of a run carries `valueConversionDecisions` (and the step message repeats them).

### E.2.2 Item 3: format facts for a sensitive column

`NormalisationProfiler.promptFacts(values, exactDistinct, sensitive, showValues)` is the single place that decides what a column says to the model:
the whole `factsLine` for an ordinary column, **`formatFactsLine` for a sensitive one** - how its dates and numbers are WRITTEN (`date written
dd/MM/yyyy (100%) - convert it with trim|date(dd/MM/yyyy,yyyy-MM-dd), not isodate()`, `number written dec comma (100%)`) and nothing else: no value, no example,
no shape, no case, no phone shape, no list of codes. `StagingLlmSuggester#fillShapes` calls it for both (the shapes, token case and `shapes` list stay
withheld for a sensitive column). Under `NONE` nothing is read at all, as before. Tests pin it: every value of the sensitive date, amount and code columns
is searched for in the whole `userPrompt`. The data policy's Javadoc (`LlmDataPolicy`) states the exception.

### E.2.3 Item 4: the system prompt

One paragraph: a rule that is refused, corrected or flagged is never a reason to give up its source; fix that rule and go on with the feed and the next table;
a value check on a typed attribute may replace the pipeline or record the rule flagged. And one sentence explaining the `, measured: ...` note.

### E.2.4 Coordinator (a): the WHOLE chain of steps, on the raw values (`staging/StepChainCheck`)

Pure class: runs the mapping's steps in position order on the raw distinct values (`CleaningSteps.applyStep`) and reports, per step, the rows it saw, the
rows it changed and the rows whose leading `+` it dropped. `NormalisationSuggester` uses it (new stage before the verification, `reconcileChain`):

- the lot D repair now covers **`STRIP_CHARS` and `NORMALISE_IDENTIFIER`** (the `+` comes out of the argument) - run before the pass writes its own steps;
- a step still PROPOSED, not the pass's own, that **drops the `+`** of a phone-like column whatever its kind is **rejected with a note**;
- a step still PROPOSED, not the pass's own, that **changes nothing after the steps before it** is **withdrawn** the same way (status `REJECTED` with the note,
  never deleted: the row is the model's and a person can read why). Judged only when the whole column was read (a rare value past the sample might need it);
- a step a person decided (ACCEPTED/APPLIED) or the pass's own that drops the `+` flags the MAPPING (confidence under the bar, `Value check` note).

No XSD change: `MappingStep.status` already has `REJECTED`.

### E.2.5 Coordinator (c): case alignment never destroys case

`NormalisationPass.alignCase`: as soon as ONE source writes the field in title or mixed case (at least half of its letter-bearing rows), the common form is `TITLE_CASE`
whatever the row counts, and only the all-capitals / all-lower sources move to it (a title-case source is never touched). With no title or mixed case anywhere the
majority between capitals and lower case still decides as before (nothing is destroyed by aligning those; the e-mails of the real files still get `LOWER`).
Measured on the real B2C files with the ticket names multiplied by 50: tickets still get `TITLE_CASE`, web and loyalty none.

### E.2.6 Coordinator (b): the capability pass knows the numbers are national

- `NormalisationProfiler.phoneMix(profiles)` aggregates the phone shapes of ONE attribute over ALL the mappings that feed it (the old shapes came from one mapping):
  `phone-like across 3 sources: 38% written with an international prefix (+ or 00), 62% in national format (leading 0, no international prefix) - ... needs a default
  country, never a blank one`. Formats and shares only. `CapabilityLlmSuggester#toLlmEntity` computes it (not under `NONE`; a sensitive attribute gets the sentence, never a value)
  and `userPrompt` shows it as `Measured across every source feeding it:`.
- Deterministic check in `CapabilityLlmPass.Session#suggestScriptDerivation`: when a bound attribute is phone-like with national-format rows, a **blank literal** binding is
  refused with the three honest ways out (a country attribute of the entity, a literal the data establishes, `toBeProvided`). Without national numbers a blank stays valid.
  LLM-first: the model gets the fact first; the guard only catches the blank.
- Locale: `CapabilityLlmPass.systemPrompt(Locale)` asks for rationales, notes, `newAttributeLabel` and the user-facing `message` in the person's language (identifiers untouched);
  `CapabilityLlmSuggester.suggest(..., Locale)` is called by `StagingProposalRun` (the run's locale) and `CapabilitySuggestLlmRun` (the job's).

## E.3 Files

New: `staging/StepChainCheck`; tests `StepChainCheckTest`.
Touched: `staging/StagingLlmPass` (`RuleProbe#verifyTyped`, `Session#typedOutcome`/`conversionDecisions`, answer wording, system prompt), `staging/NormalisationProfile`
(`formatFactsLine`), `staging/NormalisationProfiler` (`promptFacts`, `PhoneMix`), `staging/NormalisationPass` (case), `ebx/StagingLlmSuggester` (`verifyTyped`, sensitive facts, outcome),
`ebx/NormalisationSuggester` (`reconcileChain`, `NORMALISE_IDENTIFIER` repair), `ebx/LlmDataPolicy` (Javadoc), `capability/CapabilityLlmPass` (facts, blank-default guard, locale),
`ebx/CapabilityLlmSuggester`, `ebx/StagingProposalRun`, `ebx/CapabilitySuggestLlmRun`; tests `StagingLlmPassTest`, `NormalisationPassTest`, `NormalisationProfilerTest`, `CapabilityLlmPassTest`.

## E.4 Deployment

**No XSD change, no restart for the data model** (static enumerations untouched; `REJECTED` already a `MappingStep.status`). Java changes: install and restart as for any class.
Nothing was installed or run live from this lot.

## E.5 Not done / to watch

- Not run under Tomcat. `LandingRuleProbe#verifyTyped` is covered by the same `TransformVerifier` tests and by a stub probe running the real Connectors engine, not by a live run.
- The typed check judges the 2000 most frequent distinct values; a rare bad value past the sample is found by the load. A model that proposes a rule on an attribute of an entity adopted from an
  earlier run (type unknown to the session) is not checked, as `conversionRefusal` never was.
- A corrected pipeline REPLACES the model's whole pipeline (as the verifier's `FIXED` does): a model chain doing something useful besides the conversion is lost.
- Redundant own steps (the pass's `STRIP_CHARS` created after a model's `NORMALISE_IDENTIFIER` that already strips the same separators) are left, to avoid create/withdraw churn.
- Case: "at least half the letter-bearing rows in title or mixed case" is the test that a source "carries" case; an e-mail column written in mixed case in one source would be sent to
  `TITLE_CASE` too - not met on the B2C files (e-mails are lower/upper).
- The blank-default guard is on `suggest_script_derivation` only, not on a constraint binding.
