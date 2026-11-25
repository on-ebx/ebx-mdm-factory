# Lot H2 - the B2C end-to-end scenario (landing + staging), automated

Written 2026-10-17, branch `feature/e2e-b2c-scenario` (worktree `ebx-mdm-factory-evo-e2e`, from `main` a11e0517).
Status: coded, `tools/test_local.sh` green (including the scenario's own self-test), **NOT run against a live instance** - the scenario writes (projects,
Connectors connections, dataspaces, models) and its first live run is the coordinator's, after install.

It replaces the manual screenshot retests of 2026-10-17 (`test-landing-b2c-2026-10-17`, `test-staging-b2c-2026-10-17/retest`, `retest2`): the same three
files, the same sequence of gestures, the same expected outcomes, driven through REST and Data Services and asserted by a script.

## Run it

```
EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_scenario.sh [--cleanup] [--through 1..6] [--report FILE] [--keep-files]
tools/e2e/b2c_scenario.sh --selftest     # assertion logic + the whole scenario against a stand-in factory on 127.0.0.1 (no instance)
tools/e2e/b2c_scenario.sh --dry-run      # generates and checks the three files, prints the plan and the routes, sends nothing
```

- Python 3 standard library only (`b2c_scenario.sh` is a thin guard around `b2c_scenario.py`); it reuses `tools/itest/client.py` (HTTP with Basic auth,
  Data Services client, the "only touch what carries the run's prefix" guard).
- Credentials come from the environment only, travel in an `Authorization` header and are never printed or written to the report. An `http://` URL is
  refused for a host that is not `localhost`/`127.0.0.1`/`*.localhost`/`*.test` unless `E2E_ALLOW_INSECURE_HTTP=1`; `E2E_CACERT=<pem>` for a private CA.
- Every mutating request must name the run's project code (`E2EB2C<yyyymmddhhmmss>`) in its path or body, or be one of the few record paths the scenario
  read first from a query filtered on that project (the guard raises `SafetyError` before sending anything else).
- Exit codes: `0` every check passed, `1` at least one FAIL, `2` environment/arguments, `3` a route the scenario needs does not exist on this build.
- `--cleanup` deletes the project through the existing deletion route (`GET deletion/plan`, checked to name only this run's objects, then `POST delete`
  with matching, flows, dataspaces and models ticked). The three Connectors connections and catalogue sources the upload creates may or may not be removed by the
  project deletion, depending on the build: after the deletion the connections of the run (their ids carry the run's `e2e<mmddhhmmss>` suffix) that are STILL listed are reported under "LEFT BEHIND" (see "Hard cases" below).
- Report: a PASS/FAIL table on the console and `e2e-out/b2c-<project>.json` (`E2E_OUT_DIR` or `--report` to move it): summary, every check with its detail,
  the jobs (id, status, seconds, message), the decisions made by the policy below, which routes were present, the build's version and commit, seconds per step.

Environment knobs (all optional): `EBX_MODULE` (default `ebx-mdm-factory`), `E2E_POLL` (seconds between polls, default 5), `E2E_THRESHOLD` (bulk accept bar, 60),
and the bounded waits in seconds - `E2E_TIMEOUT_READY` 300, `_LANDING` 600, `_PROPOSAL` 1500, `_DECIDE` 900, `_BUILD` 1200, `_SYNC` 1500 (the synchronous
model build and constraint apply), `_DELETE` 1200. A wait that expires fails with "timed out after N s waiting for <what>" and names the job to cancel.

Expected durations (from the manual retests): upload + scan + ready ~1 min, **landing ~1 min** (12 steps), **proposal ~4 min** (rule pass, language-model
passes, profiling, quality and capability passes), bulk accept and decisions ~1 min, model build + load ~1-2 min. Whole run about 10 minutes, plus the
deletion with `--cleanup`.

## The sequence and what it asserts

| Step | Does | Asserts |
|---|---|---|
| 0 | `GET /status`, `GET /connection/test`, probes every route it needs with a project code that does not exist | module up, MCP servers ready, no route missing (exit 3 otherwise, naming them); the optional preflight route (lot H1) is noted |
| 1 | Data Services `POST Project` (mode GREENFIELD, style CONSOLIDATION, language model value sharing SHAPES_ONLY, like the wizard's step 1) | project readable with style Consolidation |
| 2 | regenerates the three files with the unchanged seeded `gen_b2c.py` (names suffixed `_e2e<mmddhhmmss>` so each run has fresh connections), `POST sources/files` (codes ECOM / STORES / LOYALTY, trust ranks 100/90/80), waits for the three applications | the fixtures themselves: 160 / 234 / 140 rows, JSON envelope `{"customers":[...]}`, `;` and `,` separators; three applications with landing tables and columns |
| 3 | per application, validates the landing as a person does (below), `POST landing/run` (one launch), polls the job | job `DONE`; **one** `LANDING_RUN` and no `LANDING_RUN_RESUME`; each table `LOADED` with exactly 160 / 234 / 140 `lastLoadRows` |
| 4 | `POST staging/propose` (language model on as the instance is configured; quality and capability passes on), polls | job `DONE`; no rule entity (origin RULE) left `PROPOSED`; one entity whose `memberEntities` hold the 3 rule entities and whose feeds read the 3 landing tables; the birth-date links carry no failing value check (a "Value check" note that is not "fixed") and the loyalty one reads `dd/MM/yyyy`; no mapping step or pipeline step on a phone link drops a leading `+` |
| 5 | `POST staging/decide` (accept at >= threshold), then the flagged queue by policy, `defaultCountry`, apply accepted script constraints | bulk job `DONE`; nothing left `PROPOSED`; no parameter-less derivation; accepted constraints applied, or left ACCEPTED with the reason on the row (`applyMessage`; empty = FAIL, lot M4); the `+` check again on the accepted state |
| 6 | optional preflight, `POST staging/generate`, `POST staging/load?scope=all`, polls, reads the staging tables over Data Services | per built entity: rows in the table and written by the last load, **0 `TransferReject`**; person entity 534 rows; **300 birth dates**; web phones keep their `+`; the E.164 derived field filled on >= 95 % of rows with a phone and every value starts with `+`; every name-key derived field filled on >= 95 % of rows with a name |

### Landing keys (step 3)

For each application the scenario reads the key the factory suggested (`LandingColumn.sourceKey`, `LandingTable.keyConfidence`). ECOM (`customerId`) and
LOYALTY (`card_number`): the suggestion is **validated as it stands** when it is exactly the expected column, otherwise the expected column is chosen. STORES
(`NUM_TICKET`) is **always chosen explicitly** (the factory first suggested `DATE_ACHAT`): `sourceKey` / `sourceKeyOrigin = USER` on the columns,
`keyConfidence` 100, exactly what `WizardData.setTableKey` writes. Then `ProjectSource.landingValidated` is set (user, time), which is what step 3's
"validate this application" does and what the run reads (`ValidatedKey`). All of this is Data Services writes on the Factory dataset; no route is needed.

### The flagged queue (step 5) - the documented policy

After the bulk accept (`staging/decide`, every PROPOSED line at 60 % or above, nothing below), `GET staging/review` lists what is left. Each line is decided
by `e2e_lib.decision_for`:

1. an `EXTERNAL_SERVICE` suggestion (an optional look-up through a connector) is **rejected**: it needs a connector set-up that is not part of this scenario;
2. a line whose rationale **starts with a check of the proposal itself that proposes rejecting** ("To check: ... (suggestion: reject it)" / "A verifier : ...
   (proposition : le refuser)", the two attribute findings of lot A: duplicate of another field, no source column) is **rejected**;
3. everything else is **accepted**.

Decisions go in dependency order (entity, attribute, feed, mapping, step, rule, derivation, constraint) through `POST staging/decisions` (the same
`ProposalReviews.decide` as a button, with its rule check and cascades); up to four rounds, stopping when a round makes no progress. Lines the rules refuse are
counted and listed in the report (`decisions.refusedByRules`). A value check ("|| Value check") never proposes anything and is not read as a rejection.
The accepted/rejected counts and the policy sentence per nature are in the JSON report.

`defaultCountry`: every derivation suggestion not rejected whose binding still has a `toBeProvided` parameter named `defaultCountry` gets `FR` through
`PUT capabilities/{id}/binding`. Any other outstanding parameter is only reported (it would leave that derivation empty, which the step 6 measures show).

## Routes used

Existing: `GET status`, `GET connection/test`, `POST projects/{code}/sources/files`, `GET projects/{code}/jobs`, `PUT projects/{code}/capabilities/{id}/binding`,
`POST projects/{code}/capabilities/apply`, `POST projects/{code}/staging/generate`, `POST projects/{code}/staging/load`, `GET projects/{code}/deletion/plan`,
`POST projects/{code}/delete`; Data Services on the Factory dataset (`Project` create; reads of `FactorySettings`, `ProjectSource`, `LandingTable`,
`LandingColumn`, `StagingEntity`, `StagingAttribute`, `TransferFeed`, `AttributeMapping`, `MappingStep`, `CapabilitySuggestion`, `FactoryJob`,
`TransferReject`; writes of `LandingColumn`, `LandingTable`, `ProjectSource` for the key and its validation) and on the project's staging dataset (reads).

**Added by this lot (five thin routes in `rest/FactoryResource.java`, one contiguous block, rights in their own `FactoryRights.route(...)` statement):**
the wizard had buttons for these but no REST route.

| Route | Right | Wraps |
|---|---|---|
| `POST /projects/{code}/landing/run?useLlm=false&fullRefresh=false` -> 202 + job | DESIGN | `LandingRun.start` (step 3's "Prepare the landing") |
| `POST /projects/{code}/staging/propose?quality=true&capability=true` -> 202 + job | DESIGN | `StagingProposalRun.start` (step 4's "Propose the staging") |
| `POST /projects/{code}/staging/decide?accept=true&threshold=60&includeBelowThreshold=false&entity=` -> 202 + job | DESIGN | `StagingBulkDecideRun.start` (the matrix' "Accept the N proposals at 60 % or above") |
| `GET /projects/{code}/staging/review?status=PROPOSED` | VIEW | `ProposalReviews.lines` read with the caller's view: `{lines:[{nature,id,element,proposition,origin,confidence,rationale,status}], counts}` |
| `POST /projects/{code}/staging/decisions` body `{"decisions":[{"nature","id","status":"ACCEPTED"\|"REJECTED"}]}` (<= 200) | DESIGN | `ProposalReviews.decide` per line; refuses a row that is not a line of the project |

`tools/test_local.sh` covers them structurally (`RestRouteRightsTest`: each route starts with its gate and its right is decided) and compiles them; they were
not exercised against EBX. The scenario's step 0 probes each one with a project code that does not exist: the module answers `404 {"error":"Project ... not
found"}`, a build without the route answers anything else, and the run stops with exit 3 listing the missing routes.

**Lot H1 (`staging/preflight`)**: step 6 calls `GET /projects/{code}/staging/preflight` (then `POST`) when the route exists and is skipped with a note when it
answers a missing-route 404. The answer's shape is not known here: the step fails on a non-200, or on a JSON body with `ready: false` / `ok: false`; adapt
`Scenario.preflight` to H1's real field names at merge time.

## How it is tested without an instance

`tools/e2e/b2c_selftest.py` (run by `tools/test_local.sh`, or `b2c_scenario.sh --selftest`):

- the fixtures (`gen_b2c.py` copied unchanged from the manual test): counts, envelope, separators, dd/MM/yyyy dates, `+` on web phones, the data URL format;
- the assertion logic on canned data: checks that propose rejecting (English and French, and the ones that must not), failing value checks, pipelines and steps
  that drop a `+`, person entity detection, `toBeProvided`, fill rates, the missing-route test, the bounded poll with a fake clock, the report table;
- the whole scenario against `b2c_standin.py`, an in-memory stand-in of the routes and tables shaped after project `TiersB2CRetestFinal`: a good run
  (every check passes, exit 0, with cleanup, with `--through 3`) and eight switchable defects, each shown to turn the intended check to FAIL: rule entity left alone, loyalty birth
  date read as ISO (two checks), a step stripping the `+`, no E.164 attribute, a TransferReject, a short landing load, a second job needed to finish the landing.
  The stand-in is not a model of EBX: it proves the scenario's logic and wiring, not the instance's behaviour.
- lot M7 (2026-10-18): an accepted entity no accepted mapping feeds (stand-in defect `nofeed`, an `ACCEPTED` `ContactEmail` with no mapping, the load saying "No feed:
  ..." on its row) is reported by step 6 as an INFO line ("ContactEmail: no feed (nothing loaded, said on the row)"), not as a failed "rows written" - the engine did what
  the decisions said; measured on `E2EMASTER20261017235630`, where the model's variance produced such an entity and the scenario failed on it. See
  `docs/STAGING-PROPOSAL.md`, "Lot M7".

Lot S4 (2026-10-18, after the first live run) adds to the stand-in: `_change` on every message, the CLI's deprecation notice as the first line of every read (parsed by the same `e2e_lib.parse_console_output`
as the real reader), the retire notices of a merge (default, as since lot S2; `ds_noretire` is the old gap), the unmerge route with its sweep (RETIRE of the golden with reason UNMERGE, a record for each member back
to GOLDEN) and the member table's `Linked_Field_State`. Variants that are not defects: `ds_nochange` (a build before lot S2), `ds_unmergebare` / `ds_unmergedouble` (the add-on wants another path form),
`mm_unmergepending` (members back PENDING_APPROVAL), `ds_unmergenoretire` (a METRIC, no FAIL), `mm_membercopies` (the cascade merge's golden copies, the INFO line says the figure is expected). Defects turned to FAIL:
`ds_unmergesilent` (nothing arrives after the unmerge), `ds_livemerged` (the live-only guard), `ds_mergedpub` on a member table; `ds_unmergerefused` and `ds_unmergenoop` end in INFO + SKIP.

## What is unsure (to look at on the first live run)

- **Data Services writes on `LandingColumn`, `LandingTable` and `ProjectSource`** (key flags, `landingValidated`): assumed open to an administrator; the scenario
  names the refused row if not. The value `USER` for `sourceKeyOrigin` is the one `setTableKey` writes.
- **Shapes of Data Services answers** (foreign keys, enumerations, multi-valued `memberEntities`): normalised defensively (`e2e_lib.scalar/text/as_list`) from
  what the MCP reader shows, not from a recorded REST answer.
- **Application matching** relies on the landing table name starting with the file stem (`ECOM_Clients_e2e...`), as in the retests (`ECOM_Clients_v5`).
- **The person entity** is the non-superseded entity with the most `memberEntities` (>= 3); its attributes are found through the mappings that read the
  birth, phone and name source columns, its E.164 field as the `SCRIPT`-derived attribute of the phone attribute, its name keys as attributes derived from a
  name attribute (or a computed attribute whose name contains "name" and "key"). If the language model proposes none of them, the step 6 check FAILS ("not
  proposed"): it did in every manual run, and a project without them loses its matching keys.
- **`PUT binding` on an ACCEPTED derivation**: the lot P1 rules may refuse some states; the failure is reported with the route's sentence.
- **Accepted script constraints** are applied by `capabilities/apply` (the bulk job applies the ones it accepts itself; single decisions do not). A derivation
  is applied by the load, as in the retests.
- **Entity table in the staging dataset** is `/root/<entity name>` (`targetTable` when set).
- The scenario does not turn the instance's language model on or off ("as configured"); with no connector the proposal runs its rule pass only and the
  unification check fails, which is the right answer.

---

# Hard cases - `tools/e2e/b2c_hard.sh` (lot A, 2026-10-17)

Branch `feature/e2e-hard-cases` (worktree `ebx-mdm-factory-e2e-hard`, from `main` 54eab261). Status: coded, `tools/test_local.sh` green (the scenario's own self-test included), **NOT run against a live
instance**: it writes, and its first run is the coordinator's, after install. It exercises the paths the nominal run never reached - code transcoding, the preflight (lot H1), the "build anyway" path,
the reject reasons and categories (lots B and H4), the cause sentence of the step 4 banner, the rebuild after the fix, and a value longer than its field.

```
EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_hard.sh [--cleanup] [--through 1..7] [--report FILE] [--keep-files]
tools/e2e/b2c_hard.sh --cleanup-project E2EHARD<yyyymmddhhmmss>   # deletes the project an earlier run left in place, reports what is left behind
tools/e2e/b2c_hard.sh --selftest                                   # the logic and the whole scenario against the stand-in factory (hard mode), no instance
tools/e2e/b2c_hard.sh --dry-run                                    # generates and checks the four files, prints the plan and the routes, sends nothing
```

Same environment variables, exit codes (0 / 1 / 2 / 3), bounded waits (`E2E_TIMEOUT_*`, `E2E_POLL`), safety guard (every mutating request names the run's project code, here `E2EHARD<stamp>`), report
(`e2e-out/b2c-hard-<project>.json`, with `details`: the code tables the model produced, the preflight counts, the rejects by reason code and category, the causes) and PASS/FAIL table as the
nominal run (`b2c_hard.py` is a subclass of its `Scenario`). **The project is LEFT IN PLACE by default** (no `--cleanup`): the run ends with its code and the addresses of the wizard (step 4 and step 3) and
of the reject review, for a visual pass; `--cleanup-project CODE` removes it later.

## The 4th source

`gen_b2c.py` now also writes `CRM_Contacts.csv` (150 rows, `,` separator), generated AFTER the three nominal files from its own seed - the three files are byte for byte what they always were, and the
nominal scenario ignores the fourth. It is about the same people with other codes: `civility` MR / MRS / MISS (the loyalty file has `civilite` M. / Mme), `tier` L1 / L2 / L3 (the loyalty file has
`niveau` Bronze / Argent / Or), `birth` written `yyyyMMdd`, `phone`, and a `comment` whose every 12th value (13 of 150) is 600 to 640 characters long, the others at most a short sentence. Upload code `CRM`,
trust rank 70, key `contact_id`. Expected rows: 160 / 234 / 140 / 150, 684 person rows, 450 birth dates.

## The sequence and what it asserts

Steps 1-5 are the nominal ones (project, upload of FOUR files, landing with the CRM key chosen explicitly, proposal, decisions), with the nominal checks unchanged - the four sources feed one person entity,
loyalty reads `dd/MM/yyyy`, no step drops a `+`, nothing left to decide - plus:

| Step | Asserts | Strict or tolerant |
|---|---|---|
| 4 | CRM birth date is read as `yyyyMMdd` (no failing value check) | strict (the safety net is the baseline preflight below) |
| 4 | per concept (civility, tier): a live `MappingStep` of kind `TRANSCODE_REFERENCE` carries a code table on the mappings that read the code columns | strict; entity and field names are not read, the columns are |
| 4 | per table: EVERY observed code of its source has a target, or the step is flagged (confidence <= 59) with a reason (its own rationale or the mapping's note) | strict on the codes (the generator is seeded: LOYALTY `M.`/`Mme`, `Bronze`/`Argent`/`Or`; CRM `MR`/`MRS`/`MISS`, `L1`/`L2`/`L3`), tolerant on who gets a table: a source that already writes the common list needs none |
| 4 | the two sources end on ONE common list (a source without a table must already be in it) | judged only when no table is flagged; then reported as INFO |
| 5 | those steps are ACCEPTED / APPLIED after the decisions | strict |
| 6 | **baseline preflight** (`POST staging/preflight`): no link the model proposed is blocking except the long comment; if one is, it is a FAIL and its fix is applied so the run goes on | strict |
| 6 | **bad link forced**: every accepted loyalty `date_naissance` mapping gets `pipeline = isodate()` | - |
| 6 | preflight `blocking = true`; the finding of that mapping has a blocking `CONVERSION`/`LOST` issue of **at least 140 rows**, proposes the fix `trim|date(dd/MM/yyyy,yyyy-MM-dd)` (compared without spaces or case), and names the pipeline `isodate()` (it judged the edit) | strict (engine behaviour) |
| 6 | `staging/generate`, then `POST staging/load?scope=all` WITHOUT build-anyway: the job message is the preflight's "Nothing to do: the build was not started"; the person table has 0 row and `TransferReject` has 0 row | strict |
| 6 | `POST staging/load?scope=all&buildAnyway=true`: the job runs (not the "not started" message) | strict |
| 6 | **rejects**: exactly 140 open STAGING rejects on the person entity with reason code `convert.date|<field>|date_naissance`, all of category `EBX_REFUSAL`; no loyalty row in the person table, every other source loaded | strict |
| 6 | **children**: if the model built a child entity under a loyalty person feed (found through `TransferFeed.parentFeed`): at least 140 open rejects of category `PARENT_REJECTED` on it, all with the cause `parent|convert.date|...`, and no loyalty row in its table. No such entity: SKIP with the reason (the cascade is not exercised by that run) | strict when the entity exists |
| 6 | **banner**: `GET rejects/causes` answers the very counts and sentence of the step 4 banner: `EBX_REFUSAL` >= 140, `PARENT_REJECTED` >= 140 (when a child exists), the sentence says EBX refused, gives the number, does not blame a rule; its counts equal those of the open STAGING rejects read from `TransferReject` | strict |
| 6 | **long comment** (the `comment` field, with its `maxLength` read from the attribute): either the preflight reports `TOO_LONG` as blocking, or the load refuses the values with `tooLong` (both are reported in the detail: "preflight: blocking / silent; load: N tooLong reject(s)"); and the load refuses **exactly** the values longer than the field (13 for 255) with reason code `tooLong`. No comment field, no `maxLength`, or a `maxLength` that holds the longest value (640): SKIP with the reason | the either/or is the tolerant one, the count is strict |
| 7 | `POST staging/preflight?apply=*`: `fix.applied >= 1`, no stale, no missing; the mapping reads `trim|date(dd/MM/yyyy,yyyy-MM-dd)` and keeps its status; the new check no longer blocks that link; nothing but the long comment still blocks | strict |
| 7 | rebuild (`scope=all`; with `buildAnyway=true` only when the long comments still block, otherwise plain - which also proves the check lets a clean build through): the job runs | strict |
| 7 | every reject of that link (`convert.date`, and the `parent|convert.date` of its children) is resolved or gone (none open); the only open rejects left are the `tooLong` ones | strict |
| 7 | the person table holds 684 minus the too long CRM rows, 140 of them from LOYALTY; the child table holds the loyalty children again; the banner counts follow the open rejects (and the sentence is empty once nothing is held back) | strict |
| 7 | the nominal person checks on the final table (birth dates filled, `+` kept, E.164, name keys) with the numbers adjusted for the rows the long comments keep out | as nominal |
| 7 | every stored value of the civility and tier attributes is a code of the common list (only when the tables are complete and aligned) | strict |

The two ways a person or a build can reach "build anyway" on this instance: the wizard's button (`STAGING_LOAD_RUN_ANYWAY`) and, new with this lot, the query parameter `buildAnyway=true` on `POST staging/load`.
The edit of the mapping's pipeline has no REST route (the Mapping matrix is a user service): **the scenario writes `AttributeMapping.pipeline` through Data Services, a documented TEST-ONLY shortcut** - it is the
very field the matrix' edit and the preflight fix write, and the status is left alone.

## Routes added by this lot (thin, rights-registered, `RestRouteRightsTest` covers them)

| Route | Right | Wraps |
|---|---|---|
| `POST /projects/{code}/staging/load?...&buildAnyway=true` (new query parameter of an existing route, same right DESIGN) | DESIGN | `StagingLoadRun.start(..., allRows, buildAnyway)`: the wizard's "Build anyway". Ignored with `sync=true` (that path never made the check) |
| `GET /projects/{code}/rejects/causes?stage=STAGING\|MASTER` | VIEW | `ebx.RejectCauseReader` + `rejects.RejectCauses`: `{stage, total, counts{category:n}, sentence, causePhrase, blamesARule, entities{name:{...}}}`, the sentence in English; read with the caller's session |

## "LEFT BEHIND" now reports what is really left (both scenarios)

The upload creates three (four) Connectors connections and catalogue sources. Whether the project deletion removes them depends on the build (a lot changes project deletion to do it), so `--cleanup` no longer
claims they stay: after the deletion it reads `GET /sources/onboarding/connections` and lists, as left behind, only the connections of the run (matched by the ids the upload answered and by the run's
`e2e<mmddhhmmss>` suffix) that are STILL listed, with their catalogue source id. If the listing cannot be read it says so ("could not check whether the deletion removed it") instead of guessing. The deletion
plan is checked as before, except that an item is accepted as the run's when its name carries the project code, the run's suffix or one of the upload's ids (a plan that also lists connections, e.g. `CONNECTION
e2eXXXXXXXXXX`, is not refused), and the `generated=` options sent are the four known ones plus any other `option` the plan's own items carry. Without `--cleanup` the connections are listed together with the project.

## How it is tested without an instance

`tools/e2e/b2c_selftest.py` (run by `tools/test_local.sh`) gains: the fourth file and the unchanged three; the new pure logic (`e2e_lib`: code keys, code tables, gaps, alignment, preflight findings, rejects
selection, banner problems, the page addresses); and the whole hard scenario against `b2c_standin.py` in hard mode (a 4th source, code tables, the preflight routes, a child entity, the build with and without
"build anyway", rejects with reason codes and categories, the causes route, connections): a good run (70 checks pass, the project and the addresses printed, four connections left behind), the tolerated shapes (a
table flagged at 59 with its reason, no child entity -> SKIP, a preflight that does not see the long comments but the load does, a model that keeps no comment field) and twelve defects (fourteen guarding checks), each shown to turn
the intended check to FAIL (`nocodes`, `codegap`, `misaligned`, `crmiso`, `pfblind`, `nofix`, `nobuildanyway`, `orphans`, `stuck`, `toolongloaded`, `bannerrule`, ...); cleanup with and without a deletion that removes the
connections, without the listing route, `--cleanup-project`, `--through`, `--dry-run`. The stand-in is not a model of EBX: it proves the scenario's logic and wiring, not the instance's behaviour.

Lot S4 (2026-10-18, after the first live run) adds to the stand-in: `_change` on every message, the CLI's deprecation notice as the first line of every read (parsed by the same `e2e_lib.parse_console_output`
as the real reader), the retire notices of a merge (default, as since lot S2; `ds_noretire` is the old gap), the unmerge route with its sweep (RETIRE of the golden with reason UNMERGE, a record for each member back
to GOLDEN) and the member table's `Linked_Field_State`. Variants that are not defects: `ds_nochange` (a build before lot S2), `ds_unmergebare` / `ds_unmergedouble` (the add-on wants another path form),
`mm_unmergepending` (members back PENDING_APPROVAL), `ds_unmergenoretire` (a METRIC, no FAIL), `mm_membercopies` (the cascade merge's golden copies, the INFO line says the figure is expected). Defects turned to FAIL:
`ds_unmergesilent` (nothing arrives after the unmerge), `ds_livemerged` (the live-only guard), `ds_mergedpub` on a member table; `ds_unmergerefused` and `ds_unmergenoop` end in INFO + SKIP.

## What is unsure (to look at on the first live run)

- **Everything about a live run is unmeasured**: the language model's real code tables (shape, who gets a table, confidences), whether it puts the CRM columns on the same attribute as the loyalty ones, whether it keeps
  the `comment` column and with which `maxLength` (the staging default is 255), and whether it builds a child entity under the loyalty feed (without one the cascade is SKIPped).
- **Job status of the stopped build**: the check expects the job to end `DONE` (or `DONE_WITH_FAILURES`) with the "build was not started" sentence in its message (the step is recorded SKIPPED, the handler returns the
  sentence); if the job queue reports it differently, adapt `build_was_blocked` / the status test in `build_blocked_then_anyway`.
- **`TransferReject` fields over Data Services** (`category`, `reasonCode`, `resolved`, `entity`, `sourceSystem`) are read like the nominal run reads `entity`; `reasonCode` is expected as `code|field|column`
  (`RejectReason.signature`), a child's as `parent|<parent's signature>`.
- **Counts are exact on purpose** (140 loyalty rejects, 13 too long): they hold while the preflight reads every distinct value (under 2000 per column) and the fixtures stay seeded. A reject that EBX
  classifies as something other than `convert.date` (the nominal retests saw `EBX_REFUSAL` / `convert.date`) shows in the detail with the open rejects by reason code.
- **The pipeline edit is a Data Services write**; if the Factory dataset refuses it for the administrator the step fails with the refused row named.
- **`--cleanup-project`** finds the connections by the run's suffix in their id or label; the connection ids the upload answers are not known to a later invocation.

---

# Master and matching - `tools/e2e/b2c_master.sh` (lot E2E-master, 2026-10-17)

Branch `feature/e2e-master` (worktree `ebx-mdm-factory-e2e-master`, from `main` fd9f6662). Status: coded, `tools/test_local.sh` green (the scenario's own self-test included), **NOT run against a live
instance**: it writes (project, master model and dataset, a matching configuration and a matching run), and its first run is the coordinator's, after merge and install.

It carries the nominal B2C scenario (steps 1-6: project, three files, landing, staging proposal, decisions, staging build and load - every nominal check unchanged) through the **master** (wizard step 5)
and the **matching** (wizard step 6), and **measures the matching against a ground truth**: the honest number, not a green bar.

```
EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_master.sh [--variants plain|simulate-first|both] [--cleanup] [--through 1..11] [--report FILE] [--keep-files] [--auto-merge] [--distribute]
tools/e2e/b2c_master.sh --cleanup-project E2EMASTER<yyyymmddhhmmss>   # deletes a project an earlier run left in place
tools/e2e/b2c_master.sh --selftest      # the logic and the whole scenario (both variants, every defect) against the stand-in factory, no instance
tools/e2e/b2c_master.sh --dry-run       # generates the files and the ground truth, prints the truth's numbers, the plan and the routes; sends nothing
```

Same environment variables, exit codes (0 / 1 / 2 / 3), bounded waits, safety guard (every mutating request names the run's project code, here `E2EMASTER<stamp>`), PASS/FAIL table and JSON report
(`e2e-out/b2c-master-<project>.json`, with `details`: the plans and their leaves, the master counts, the quality measures, false-merge examples, the golden check) as the other scenarios. **The project is
LEFT IN PLACE by default**: the run ends with the addresses of steps 5 and 6 of the wizard. New knobs: `E2E_TIMEOUT_MASTER` 1800, `_MATCH_PROPOSE` 1500, `_MATCH_CONFIGURE` 900, `_MATCH_RUN` 1800,
`_ACTIVATION` 120, `_METADATA` 90; `E2E_MATCH_SCOPE` (`all` by default, or `incremental`: "Lancer (dernier lot chargé)").

**Expected durations**: steps 1-6 about 10 minutes (as the nominal run), master build about 1 minute (54 s on the manual run), matching proposal 1-3 minutes (the language-model pass), configure under 1
minute, **the matching run is the unknown** - a minute for a few hundred records, several minutes if the add-on is slow (the wait is bounded at 30 minutes). With `--variants both` (the default) the whole
sequence runs twice, in two projects: about 50 minutes in all.

## The two variants

Found by hand on 2026-10-17: after the plan card's **Simulate**, **Configure** refuses the entity ("generate_merge_policy answered a soft miss: INVALID_PARAMS Merge policy Client__preview is already the
manual-merge policy for this table"), and Run stays disabled. The scenario therefore runs two variants, each in its own project, each reporting its own verdict:

| Variant | Does | Verdict check |
|---|---|---|
| `plain` | accept the plan, **configure** | `configure without a simulation: the person entity is configured (plan APPLIED)` |
| `simulate-first` | accept the plan, **simulate** it (the card's button: it writes the `<Entity>__preview` policy on a plan not applied yet), then **configure** | `configure after a simulation: the person entity is configured (plan APPLIED)` |

The defect is **not worked around**: if the configure after a simulation refuses, that check is a FAIL carrying the job's sentence and the plan's message, the run stops there (no run, no quality measure -
exactly what the wizard offers a person) and the final line reads `VARIANTS: plain PASS; simulate-first FAIL`. Both variants also check that **no preview policy is left on the table** after the configure.

Lot M4 (2026-10-18, `docs/MATCHING.md` section 59): after the configure, **every member table of the person table's business object** (`get_business_object_config`, a read tool) must hold a matching policy,
a default naming one of them, and a merge policy - `every member table of the business object holds a default matching policy and a merge policy (...)`. The add-on refuses the run of the main table
otherwise ("The default matching policy is not defined"). A model with no child entity says "none" and checks nothing.

## The ground truth

`tools/e2e/gen_b2c.py` writes, after the four files and without drawing a random number (the four files are byte for byte what they were: the self-test pins their sha1), `truth_b2c.json`:
`{"people": 220, "rows": [{"app", "key", "person"}...]}` - for every source row (`customerId` / `NUM_TICKET` / `card_number` / `contact_id`) the index of the person it was drawn from. 684 rows, 217 distinct
people. Two rows of one person are a **true pair**, within a source (several tickets of a person) or across sources; among the three sources the scenario loads (534 records) the truth knows the pairs to find
(`--dry-run` prints them: records, people, true pairs, within / across). The CRM file is in the truth but not loaded by this scenario (it is the hard-cases scenario's fourth source).

## What each step asserts and measures

| Step | Does | Asserts / measures |
|---|---|---|
| 7 master | `POST master/build` (the page's one-click run: tables, copy, matching hand-over; **route added**), waits | job `DONE`; `Project.masterDataspace/masterDataset` set; per promoted entity **master rows = staging rows minus the open MASTER rejects**; **0 open MASTER rejects** (otherwise listed by reason code, with the banner's cause sentence); **lineage**: one `Xref` row per staged person row, each `masterKey` pointing to an existing master record |
| 8 matching | `POST matching/propose` (rules, then the language model as the instance is configured; **route added**), accept the plan lines through `staging/decisions`, [simulate], `POST matching/configure`, `POST matching/run?scope=all`, then reads the metadataset | a plan exists for the person entity; **METRIC auto-match leaves** in the proposed plan (leaves of the decision tree that end in MATCH - a plan with none can only send duplicates to a steward; reported as SKIP with the reason when 0); plan `ACCEPTED` then `APPLIED`; the configure job `DONE` and no accepted plan left; on the **match-merge MCP server** (`tools/call get_matching_activation_status`, `get_matching_config`, read tools only, the only ones the scenario can call) **state ENABLED and `metadatasetExists` true** (polled up to 2 minutes), a policy exists on the person table and no preview policy is left; the run job `DONE`; the metadataset `<dataset>_<table>_MDS` holds a `RecordMetadata` row per master record and `Suspected` is readable |
| 9 quality | joins the master person records (`sourceSystem` / `sourceId`) to the truth | every master record maps back to a source row of the files; the METRIC lines below; the golden record check |

### The METRIC lines (step 9) and their initial targets

Clusters are read from the metadataset: records of one `groupId`, or pointing to the same live golden (`goldenId`); an unmerged (`DELETED`) golden connects nothing. Measure **(a) merged only** uses those
clusters; measure **(b) merged + suspects** adds each pair of the `Suspected` table as an edge. Counting is by pairs (precision = true pairs found / pairs found, recall = true pairs found / true pairs
among the records present).

| METRIC | Verdict | Target (initial, to be tuned with real runs) |
|---|---|---|
| false merges: merged groups holding records of 2+ people | **FAIL above 0** | 0 - two customers made one is the worst defect |
| precision, merged only | FAIL below 0.50, SKIP when nothing was merged | 0.98 |
| recall, merged only | FAIL below 0.50 **only if the accepted plan can merge by itself** (a MATCH leaf and `autoMerge` on); otherwise SKIP with the reason | 0.80 |
| precision, merged + suspects | FAIL below 0.50 | 0.98 |
| recall, merged + suspects | FAIL below 0.50 | 0.80 |
| suspect chains: clusters of merged + suspects holding 2+ people (INFO, lot M10b) | **never a FAIL**; count and largest cluster printed | 0 - a few false suspect pairs chain two people; the precision of the suspects judges them |
| golden records created by the add-on / records merged into a golden (INFO, lot M10b) | not judged; the figure that shows an automatic merge happened | > 0 with `--auto-merge` and a MATCH leaf |

The table shows the value in each name (`METRIC recall, merged + suspects = 0.874 (floor 0.50)`), the detail has the pair counts, and the report has the full numbers (`details.quality`: pairs found / right /
true, F1, clusters, singletons, largest cluster, auto-created goldens, metadata states) and up to 10 false-merge examples (records with application, key and person). **A value between the floor and the
target is a number to look at, not a failure**: the floors are low on purpose so that a run reports the truth. Expect, on the first live runs: the realistic measure is (b) - the first manual run showed a
plan whose every path ends at a data steward (0 auto-match, all duplicates suspects), so (a) is empty by design and its recall is not judged.

### Golden record check

For three people (the ones present in the most applications, then the most records; deterministic) the run prints, for the eye, the golden record's survived values (first and last name, e-mail, phones,
birth date) and each member's values - or "no golden: these records were not merged" (and whether they are suspects of each other). Survivorship is printed, not judged; the lines are also in
`details.goldenCheck`.

### Option `--auto-merge` (lot M10b, 2026-10-18)

The accepted plan keeps `autoMerge = false` (the proposal never decides it), so its 1-3 MATCH leaves are configured as SUSPECTED, nothing merges by itself and the "merged only" measures stay SKIP.
`b2c_master.sh --auto-merge` exercises the other path: after the plans are accepted and **before** the simulation / configure, the run turns automatic merge on for the person plan **the way the wizard
does**. The plan card's checkbox "Merge confirmed matches automatically" posts to the review user service, which calls `ProposalReviews.setMatchPlanAutoMerge`: ONE field written on the `MatchPlan` row
(`autoMerge`, nothing else), refused unless the plan is PROPOSED or ACCEPTED. There is **no REST route** for it, so the scenario makes the same single-field write through Data Services on the Factory dataset:
`PUT .../MdmFactory/root/MatchPlan/{id}` `{"content": {"autoMerge": {"content": true}}}` (the plan row was read first from a query filtered on the project: the safety guard allows exactly that record). It checks
the write by reading the plan back (`automatic merge turned on for the person plan (MatchPlan.autoMerge = true, the write the wizard's toggle makes)`), and after the configure that the APPLIED plan still has it
on (`MatchingConfigurator` compares the stored plan and rewrites the policy, so Configure is what carries the setting to the add-on). Then the "merged only" precision / recall are judged
(`auto_expected` = a MATCH leaf and autoMerge on), and the golden records are counted:

```
METRIC golden records created by the add-on = N (autoCreated); records merged into a golden = M (state MERGED)
```

(INFO lines, printed with and without the option; without it the line says "automatic merge was not turned on".) A plan with no MATCH leaf is written like the UI would and said
("automatic merge is on but the plan has no MATCH leaf"); its merged recall stays SKIP. The default run (suspects only) is unchanged. The option is the same for both variants. Report: `details.autoMerge`
(`requested`, `route`, `matchLeaves`, `plan`) and `details.quality.autoMerge`.

### Golden records created by the add-on are not "records without a source" (lot M11, 2026-10-18)

**Measured.** With `--auto-merge` the add-on created 142 golden records (`RecordMetadata.autoCreated = true`). They are not source-less: the policy stamps `autoCreateNewGoldenSource` ("MDM") and a
generated key (`FID5979509493`) on them, so "every master record maps back to a source row of the files" failed with "142 with a source that is not in the truth".

**Now.** Step 9 reads the ids of every `autoCreated` metadata row (`master_lib.auto_created_ids`, any state: an unmerged golden is `DELETED` but still a row of the table) and `master_records` puts those
rows with the goldens, next to the rows with no source at all. The check judges only the real records; the goldens are an information line, `N golden records created by the add-on, not source rows`.
They were already right in the measures: a golden is never a record (it is not in the truth), its cluster is its `MERGED` members (they point to it by `goldenId`, `labels`), so the pairs, the persons
present and the false-merge count are unchanged; `METRIC golden records created by the add-on` keeps counting the `GOLDEN` ones. Stand-in: the auto-created golden now carries `MDM` and a generated key
(as live); `mm_goldennosource` keeps the older shape (NOT a defect).

### False merges and suspect chains (lot M10b)

The old check "records grouped (merged + suspects): N clusters for P people (target: clusters >= people)" failed on a run with **0 false merge**: with suspects, a few false suspect pairs chain two people into
one cluster. It is split in two readings of "a cluster that mixes people":

| Name | Built from | Verdict |
|---|---|---|
| **false merges** | MERGED pairs only (same group / same live golden) | **FAIL above 0** (unchanged line `METRIC false merges (merged groups holding 2+ people) = N (target 0)`) |
| **suspect chains** | merged + `Suspected` pairs | **INFO** `METRIC suspect chains = N (clusters of merged + suspects mixing 2+ people; largest K record(s); not judged)`; never a FAIL on its own - the precision of the merged + suspects measure judges the suspects |

`details.quality` keeps the figures: `falseMerges {clusters, falsePairs, largestCluster}`, `suspectChains {clusters, largestCluster, falsePairs, clustersTotal, singletons, people}`, the examples of both
(`falseMergeExamples`, `suspectChainExamples`), `autoCreatedGoldens`, `mergedRecords`.

**False pairs, for a person to characterise from the log.** The quality step prints up to 20 pairs found together that come from two different people (merged pairs first, then the two ends of false
`Suspected` rows), each with both records' `sourceSystem` / `sourceId` (and master id, truth person) and the business columns they hold equal (what made the add-on pair them), read from the master rows already
fetched:

```
note  false pairs: 3 (both records, then the business fields they hold equal)
note    suspect  ECOM/W123 (id 41, person 17)  <->  STORES/T9087 (id 212, person 88) | equal: lastName=Martin, birthDate=1980-04-02
```

The list (`details.quality.falsePairs`) and its total (`falsePairsTotal`) are in the JSON report too.

## Routes used / added

Existing: those of the nominal run plus `POST matching/configure`, `POST matching/run`, `GET matching/activity`, `GET rejects/causes?stage=MASTER`, `GET sources/onboarding/connections`; Data Services reads of
`Xref`, `MatchPlan`, `TransferReject` (factory dataset), of the master person table and of its `RecordMetadata` / `Suspected` (the project's master dataspace); the match-merge MCP endpoint
(`/ebx-assistant/mcp/v1/match-merge`, `tools/call`, the two read tools).

**Added by this lot (three thin routes in `rest/FactoryResource.java`, rights in `core/FactoryRights.java`, covered by `RestRouteRightsTest`):**

| Route | Right | Wraps |
|---|---|---|
| `POST /projects/{code}/master/build?scope=` -> 202 + job | DESIGN | `MasterBuildRun.start(..., STAGES, scope)`: step 5's one-click "Build the master" (the older `master/generate` and `master/load` run one stage each) |
| `POST /projects/{code}/matching/propose?useLlm=true` -> 202 + job | DESIGN | `SuggestRulesRun.startMatching`: step 6's "Propose the matching plans"; the model pass is allowed as the page decides it (`snapshot.designer && snapshot.llmConfigured`), `useLlm=false` forbids it |
| `POST /projects/{code}/matching/simulate-plan?plan=<MatchPlan id>` | DESIGN | `MatchPlanSimulationRunner.simulate`: the plan card's Simulate button; answers `{entity, plan, pivots, pairs, verdicts, onPreviewPolicy, policyCode, failure}` |

`client.Http.request` (tools/itest/client.py) gained an optional `headers` argument (the MCP call needs `Accept: application/json, text/event-stream`).

## How it is tested without an instance

`tools/e2e/b2c_master_selftest.py` (run by `b2c_selftest.py`, i.e. by `tools/test_local.sh`): the four files' sha1 (unchanged), the truth (deterministic, every file key present, true pairs within and
across sources), the clustering and the measures on canned data (perfect, one false merge, nothing grouped, golden / members / deleted golden / suspects), the plan leaves, the MCP answer parsing; then the
whole scenario against `b2c_standin.py` in master mode: a good run of each variant (all METRIC lines at 1.000), a plan that never merges by itself (merged recall SKIP, merged + suspects measured), the
**preview defect reproduced** (`mm_previewblocks`: plain PASS, simulate-first FAIL and stops), twelve defects each shown to turn its guarding check to FAIL, the missing-route exit 3, `--through`,
cleanup, the dry run, both variants through `main()`. Lot M10b adds: the plan with a MATCH leaf and autoMerge false (stand-in `mm_automergeoff`, the live state) run without the option (nothing merged,
recall SKIP) and with it (both variants: the write made and read back, merged precision / recall 1.000, goldens counted, the applied plan keeps it), a write the add-on ignores (`mm_automergeignored`: merged
recall FAIL), a plan with no MATCH leaf, the **suspect chain** (`mm_suspectchain`: one false suspect pair, exit 0, INFO line, the pair printed and in the report), the false merge (FAIL, merged pairs listed, at most
20), the pure logic of `false_pairs` / `equal_fields` / `largestMixedCluster`, and `main()` with `--auto-merge`. The stand-in groups records by phone: it proves the scenario's logic and wiring, not the add-on's behaviour.

Lot S4 (2026-10-18, after the first live run) adds to the stand-in: `_change` on every message, the CLI's deprecation notice as the first line of every read (parsed by the same `e2e_lib.parse_console_output`
as the real reader), the retire notices of a merge (default, as since lot S2; `ds_noretire` is the old gap), the unmerge route with its sweep (RETIRE of the golden with reason UNMERGE, a record for each member back
to GOLDEN) and the member table's `Linked_Field_State`. Variants that are not defects: `ds_nochange` (a build before lot S2), `ds_unmergebare` / `ds_unmergedouble` (the add-on wants another path form),
`mm_unmergepending` (members back PENDING_APPROVAL), `ds_unmergenoretire` (a METRIC, no FAIL), `mm_membercopies` (the cascade merge's golden copies, the INFO line says the figure is expected). Defects turned to FAIL:
`ds_unmergesilent` (nothing arrives after the unmerge), `ds_livemerged` (the live-only guard), `ds_mergedpub` on a member table; `ds_unmergerefused` and `ds_unmergenoop` end in INFO + SKIP.

## What is unsure (to look at on the first live run)

- **Match and Merge timing and shape**: whether the run job returns at the end of the add-on's process (it polls to the process's terminal state, `MatchingRunRun`), and how long. `RecordMetadata` is
  read after the job with a bounded wait (90 s) for a row per master record. The `Suspected` table is read as `sourceId` / `targetId` (master keys), as `MatchingResultsBuilder` does; the Data Services
  shapes of `state` (enumeration), `goldenId` (foreign key) are normalised defensively (`e2e_lib.scalar`).
- **CLAUDE.md section 8 cases**: state `ENABLED` with `metadatasetExists` false is a FAIL of the activation check (the polling covers the boot race); the scenario never calls `activate_matching` (a write
  tool) to repair it. `AMBIGUOUS_TABLE` is answered by retrying with `urn:ebx:publication:<master dataspace>`.
- **Scope**: the run uses `scope=all` so that every record is a pivot and the measure is complete; the wizard's default button is the incremental scope. `E2E_MATCH_SCOPE=incremental` reproduces that.
- **Plans of other objects** (an address or purchase entity with its own plan) are accepted and configured too, like the page's "accept the proposals"; only the person entity is measured. A refusal on
  another object shows as the configure-job check failing, with the job's sentence.
- **Master sourceSystem**: master rows are joined to the truth through `ProjectSource.sourceSystem` (or the application code); a master row with a source that is not in the truth fails "every master
  record maps back". The CRM source is not loaded here.
- **The language-model pass** is "as configured": with no connector the proposal runs its rules only, and a person plan may then not exist (the check says so).
- **`--auto-merge` live (lot M10b)**: not run on an instance yet. It assumes Data Services lets an administrator `PUT` `MatchPlan.autoMerge` (the UI writes it as the session user through a `Procedure`; the scenario
  names the refusal otherwise), and that Configure after the write rewrites the add-on's policy to merge MATCH leaves automatically. The add-on's behaviour on the merge itself (the two product defects of
  `CLAUDE.md` section 8: cascade merge, `constant` survivors) is exactly what the "merged only" metrics and the golden counts are there to show.
- **Quality is unmeasured live**: the floors and targets above are initial values, chosen before any live number existed.


# Distribution and summary - steps 10 and 11, `--distribute` (lot S1, 2026-10-18)

Branch `feat/e2e-distribution` (worktree `ebx-mdm-factory-s1-e2e-dist`, from `main` cb4d228f). Status: coded, `tools/test_local.sh` green (the scenario's own self-test included), **NOT run against a live
instance**: the local Kafka broker is down (a person starts it) and the lot touches nothing but `tools/e2e/*` and this file - no Java, nothing installed.

**Round 2 (lot S4, 2026-10-18, branch `fix/e2e-distribution-round2`).** The first live run (Factory 1.2.0-304, project `E2EMASTER20261018181651`, `--auto-merge --distribute`) showed the chain working (first full send
980 rows / 3 mappings in 20 s, ARMED, 212 messages = 212 golden persons, none of the 487 merged members, a Data Services PUT seen as one new message 14 s later) and four FAILs that were the scenario's, not the Factory's:
(1) `_change` (lot S2's `UPSERT` / `RETIRE` / `DELETE`) counted as a foreign column - technical fields starting with `_` are expected, and retire and delete notices are recognised by `_change` and counted apart;
(2) the CLI's own `--property is deprecated` line counted as "1 message that is not JSON" - the reader now asks `--formatter-property` and never counts a line with no TAB; (3) the merge test SKIPped because
`--auto-merge` leaves no suspect pair - the retire path is now measured through the add-on's unmerge REST; (4) the member table `Adresse` (534 messages for 1068 rows) is explained by `Linked_Field_State`. A new
FAIL guards lot S2's live-only sends after the real-time write. Details under "The checks"; the self-test covers each point.

`b2c_master.sh` goes on after the quality step with two steps (the code is in `tools/e2e/b2c_distribution.py`, mixed into `MasterScenario`):

```
EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2c_master.sh --distribute [--auto-merge] [--variants plain] [--cleanup] [--through 1..11]
E2E_KAFKA_BOOTSTRAP=localhost:9082   # the broker: the Connectors connection's address AND the CLI's --bootstrap-server
E2E_KAFKA_HOME=<the broker's folder>  # bin/kafka-topics.sh and bin/kafka-console-consumer.sh (default: the local KRaft 4.2 broker, ~/kafka_2.13-4.2.0)
E2E_KAFKA_IDLE_MS=10000              # the console consumer stops after this long without a message
```

| Step | Without `--distribute` | With it |
|---|---|---|
| 10 distribution (wizard step 7) | one SKIP line "not requested" | the sequence below |
| 11 summary (wizard step 8) | runs (read only; the distribution card must be empty) | runs (the target must be listed REALTIME and ARMED) |

`--through` now goes to 11 (default 11). Without `--distribute` nothing else changes in a run: no route beyond the old ones is required, nothing is written to Connectors or to the broker.

## A broker that is down

Detected twice, never failing and never waiting long: at step 0 (a socket to `E2E_KAFKA_BOOTSTRAP`, 3 s, then `kafka-topics.sh --list`, 30 s - an early warning before the 40 minutes of matching) and again at the start
of step 10 (the broker may have come up in between). When it does not answer, step 10 is ONE `SKIP` line ("SKIPPED, not failed: no Kafka broker answers at localhost:9082 ..."), nothing is created, `details.distribution` of the
report says `skipped`, and step 11 runs with no target. A missing CLI (`E2E_KAFKA_HOME` wrong) is skipped the same way.

## Step 10 - what it does, route by route

| | Does | Route / body |
|---|---|---|
| a | the run's Kafka connection | Data Services `POST BConnectors/Connectors/root/Connector` `{id: "Kafka<project code>", label, category: MESSAGING, type: kafka, baseUrl: <bootstrap>, authType: NONE, trust: 50, active: true, description}` then `POST .../ConnectorProperty` x4: `envelope=false`, `group.id=ebx-mdm-<code lower>`, `key.field=id`, `topic=<code lower>` (the same properties as the existing `KafkaMdmDistribTest`, whose `topic` is the prefix the Factory builds each table's topic from). The id is deterministic so that `--cleanup-project` finds it. No secret is written. Then `GET distribution/connections` (polled up to 30 s): the connection must be listed with a write operation (`publish`) |
| b | a REALTIME target | `POST /projects/{code}/distribution/targets` `{connector, operation: "publish", mode: "REALTIME", cron: "", scope: "", approval: false, watchedFields: "", includeCrosswalk: false, includePending: false, retireMode: "NOTICE", retireUndoneMerges: true}` -> `{status: DONE, id}`; `GET .../targets` must read it back REALTIME, DRAFT, on the connection |
| c | the mappings | `POST .../targets/{id}/configure` -> `{status: DONE, connectorsMappingIds, tableDestinations}` (read back from `GET targets`) |
| d | the triggers | `POST .../targets/{id}/install-triggers?modelName=<code>_Master` (synchronous, up to 25 min) |
| e | the first full send | `POST .../targets/{id}/first-full-send` -> 202 + job, polled like every other step; the target must then be ARMED with a `firstFullSendRunId` |
| f | the topics | `kafka-console-consumer.sh --from-beginning --formatter-property print.key=true --formatter-property key.separator=<TAB> --timeout-ms <idle>` per table topic (`--property` on an older CLI that refuses `--formatter-property`; lot S4); the master person table and its `RecordMetadata` / `Suspected` are re-read at that moment |
| g | real time | `PUT <master person table>/{id}` with one free-text field (a comment / civility / name field, the last three characters become `E2E`), then the topic is read until one new message for that key shows (60 s), and once more after a settle time (15 s) to see a duplicate |
| h | the merge gap | `POST /ebx-addon-mame/rest/v1/manageGroup/acceptSuspects` `{dataspace: "B<master dataspace>", dataset, tablePath, groupId, recordPrimaryKeys: [<source>]}` on one suspect pair of two source goldens, if the run left one |
| g2 | the live-only guard (lot S4) | after the real-time write: every topic is judged again (the person table by its metadataset, a member table by its own `Linked_Field_State`); a RECORD message whose key is MERGED or DELETED now is a FAIL |
| h2 | the retire path (lot S4) | the true pairs the run did not merge are listed (INFO); then `DELETE /ebx-addon-mame/rest/v1/mergingProcesses/B<master dataspace>/<dataset>/root/<person table>/<golden id>` unmerges an add-on golden that has merged members (the smallest group), the metadataset is polled until the golden is DELETED (120 s), and the person topic is read for up to 90 s |
| i | stop | `POST .../targets/{id}/stop` (then, at the very end of the run, `POST .../disarm`, see below) |

### The checks (names are substrings of the lines)

- `Kafka connection created in Connectors (...)`, `the new connection is a candidate for a target, with a write operation (publish)`, `distribution target created on the new connection (REALTIME, state DRAFT)`.
- `configure: the outbound mappings are written`, `configure: one outbound mapping per master table (...)`, `one topic per table: N tables, N distinct topics`, `the topics are named after the project (<code lower>.<table>)`.
- `install-triggers: the real-time triggers are installed on the master model <code>_Master`, `first full send of the golden records ended DONE`, `the target is ARMED after the first full send (firstFullSendRunId recorded)`.
- On the person topic (the numbers come from the metadataset: GOLDEN, or no metadata row, is expected; MERGED and DELETED are not; PENDING_APPROVAL is neither required nor forbidden):
  `<topic>: one message per golden record (N)` (golden records with no message, records sent twice, messages that are not JSON: up to 20 ids listed), `no message for a merged member or an unmerged golden`,
  `every key is the id of a master record`, `the key of every message is the id the message carries`, `the fields of 3 sampled golden records equal the master row` (an add-on golden first, when there is one),
  `only records of <table> on the topic (one topic per table)` (a field that is no column of the table shows another table's record; **a field whose name starts with `_` is technical and never foreign** - `_change`, lot S2).
  `INFO METRIC messages on <topic> = N (...)` prints the figures, split by `_change`.
- **What a message is (lot S2's payload convention, read from `_change`, lot S4).** `_change = UPSERT` (or none, or any other value) is a RECORD message; `RETIRE` is a retire notice
  (`{_change, id, retired: true, retiredTo?, reason?, mergeId?, at}`); `DELETE` is a delete notice (`{_change, id, deleted: true, at}`). Only record messages count for the golden-only rules
  (one per golden, none for a MERGED member or a DELETED golden); notices are counted apart in the METRIC line (`N record message(s) (_change: UPSERT n), r retire notice(s), d delete notice(s), j not JSON`).
  A message with no `_change` (a build before lot S2) is a retire notice when it says `retired: true`, a record otherwise.
- **A line of the CLI is never a message (lot S4).** The CLI 4.x prints `Option --property is deprecated and will be removed in a future version. Use --formatter-property instead.` on its stdout, and
  the first live run counted it as "1 message that is not JSON". With `print.key=true` every message line is `key<TAB>value`, so a line with no TAB is a notice of the CLI and is dropped
  (`e2e_lib.split_console_output`). The key printing is asked with `--formatter-property`; an older CLI that answers "not a recognized option" is read again with `--property`.
- **Other master tables** (a member table of the business object, e.g. `Adresse`): `the table <T> has its own messages` (FAIL when the table has rows and the topic none). When the rows carry `Linked_Field_State`
  (the add-on's state field) the golden rule is judged from it - `no message for a merged or deleted row of <T>` - and the INFO line reads
  `METRIC messages on <topic> = N for R master rows of <T>: expected, the MERGED rows are not sent`, with the rows by state (`R row(s) by Linked_Field_State: g GOLDEN, m MERGED, ...; N message(s)`): the cascade merge
  gives a member table golden copies of its rows and leaves the originals MERGED, so 534 messages for 1068 rows (second live run, 534 GOLDEN + 534 MERGED) is the expected figure, not an odd one. A table with no
  state column keeps the old INFO (not judged).
- `real time: a golden record modified through Data Services reaches the topic as exactly one new message` (the record is a source golden, alone, not in a suspect pair; its `DistributionEvent` is listed in the detail) and
  `INFO METRIC real-time latency = about N s ...`.
- `live-only sends: after the real-time write no topic holds a record message for a MERGED or DELETED record` (lot S4, regression guard for lot S2's live-only sends): a **FAIL**, not an INFO, when a topic holds a
  record message (not a retire or delete notice) whose key is a record Match and Merge holds as MERGED or DELETED at that moment. It is judged on every topic that has a state to judge by.
- `merge two suspects: the new golden record is sent (golden record G of A + B)` - **the one judged part of the merge test**: FAIL only when the golden record the merge created has no message (or differs from its master row).
  `INFO METRIC merge notifications: golden message = yes; retire notices for the 2 merged members = k of 2; data messages re-sent for them = j` is a **measured fact, never judged**. What to expect since lot S2:
  golden yes, retire notices 2 of 2 (reason MERGE; before the lot, 0 of 2 - the gap measured on 2026-09-27). After an `--auto-merge` run no suspect pair is left (everything merged): the part is a `SKIP` that says
  so, and the retire path is measured through the unmerge below. If the add-on accepts the suspect without merging it, or refuses the request, the part is `INFO` + `SKIP` (nothing to notify).
- **The true pairs the run did not merge (lot S4).** `INFO METRIC true pairs the run did not merge = N people with 2+ source records still GOLDEN (k of them have a record in a suspect pair)` lists them (person, record
  ids, the ids that are an end of a suspect pair), from the ground truth. The add-on's REST merges only the records of one group (`acceptSuspects` on a suspect pair); a true pair with no suspect link has no group,
  so no route merges it - the list is for reading, the retire test does not use it.
- `unmerge a golden record: the topic receives notices (the retire of the golden, the members coming back)` (lot S4). A golden the add-on created (`autoCreated`, state GOLDEN) with MERGED members is picked (the
  smallest group, then the smallest id) and unmerged with the add-on's `unMerge` REST: **`DELETE /ebx-addon-mame/rest/v1/mergingProcesses/{dataspace}/{dataset}/{pathSegments}`** - found in the add-on's OpenAPI
  (`GET /ebx-addon-mame/rest/v1`, operation `unMerge`) and read by `javap` in `MergingProcessesController`: the path segments are the table path and, last, the record's primary key (at least three: `root`, `<Table>`, `<key>`),
  and the record is the **golden** (the same record the Actions › Unmerge of the Manage group screen takes). The scenario tries `B<dataspace>/<dataset>/root/<Table>/<golden id>`, then the dataspace without its B,
  then the table path written with a double slash (`//root/<Table>/<id>`), and reports which one the add-on accepted (`details.distribution.unmerge.route`). If all three are refused the part is `INFO` (every attempt
  with its HTTP status) + `SKIP`, with the reason. After the call the metadataset is polled until the golden is DELETED (state 4; CLAUDE.md section 8: the members go back to GOLDEN, or PENDING_APPROVAL, with the
  golden's row left in the table), then the person topic is read for up to 90 s (the Factory's state sweep runs at most once a minute per target). **Only "nothing at all arrived" is a FAIL.** The counts are
  `INFO METRIC unmerge notifications: RETIRE of the deleted golden = n (reason UNMERGE); UPSERT for the members back = u of m GOLDEN member(s); retire notices for members = ...; record message re-sent for the deleted golden = ...`
  (expected per lot S2: 1 RETIRE with reason UNMERGE and no `retiredTo`, one UPSERT per member that is GOLDEN again; a member that comes back PENDING_APPROVAL gets nothing until it is GOLDEN), and one INFO line per
  other topic with its new messages (a business object's member tables may follow the unmerge). Step 11 then says that the figures changed and does not compare them with step 8.
- `stop: the target's sends are stopped` (HTTP 200 and the answer's shape: `stopped` is false when nothing was running, which is the normal case after a finished send).

The target stays ARMED while step 11 reads the summary; **at the end of the run** (`finish`) it is disarmed (`POST .../disarm`, state DISABLED) so that a project left in place sends nothing more to the broker, and a
`the target is disarmed at the end of the run` line says so. With `--cleanup` the project is deleted instead.

## Step 11 - the summary

The wizard's last step is **drawn on the server** (`WizardPage#renderStep7` fed by `ProjectWizardUserService#buildSummary`); no REST route returns it, and ebx-ui refuses Basic authentication, so the scenario cannot read the
rendered figures. It reads the facts the summary is built from, with the summary's own definitions (`WizardSummaryReader`, `GoldenRecordsReader`, `WizardSummaryData`), by Data Services, and checks them against what it measured:

| Summary block | Read | Checked against |
|---|---|---|
| Landing zone | `LandingTable.lastLoadRows` | the 534 rows of the three files |
| Staging zone | `StagingEntity.lastLoadWritten + lastLoadUnchanged` per entity | the rows of each staging table (step 7) |
| Open points | `TransferReject` not resolved and not acknowledged | 0 |
| Master zone | master rows of the person table minus the DELETED ones | rows built (step 7) + goldens the add-on created and kept |
| Golden records | `Linked_Field_State` of the business table, as the summary counts it | `RecordMetadata` GOLDEN rows (skipped when the rows carry no such column) |
| Matching card | golden records and `Suspected` pairs | what step 8 and 9 measured - unless step 10 changed them (a modified record, a merge): then an INFO line says so |
| Distribute card | `GET distribution/targets` | with `--distribute`: one target, REALTIME, ARMED (so no "not armed" open point), last send DONE with a date; without: no target |
| Last full run | `PipelineStep` | `LANDING_LOAD`, `STAGING_LOAD`, `MASTER_LOAD`, `MATCHING_RUN` each have a completed, DONE run (started and ended): otherwise the journal draws no bar for it |

To check the rendered numbers themselves the Factory would need a read route for `WizardSummaryData.Output` (see "Factory gaps" below).

## Cleanup

`--cleanup` and `--cleanup-project` also remove what step 10 made. Before the project deletion the run's `DistributionTarget` rows are read (their Connectors mapping ids are the names of the deletion plan's `DISTRIBUTION_FLOW`
items, so the plan check accepts exactly those); the Factory's deletion removes the flows, the target rows and the events. After it: a `DistributionTarget` / `DistributionEvent` row of the project that is still there is
a **Factory gap**, printed as such and deleted by Data Services; then the connection's `ConnectorProperty` rows and the `Connector` row are deleted by Data Services (the Factory has no evidence it created that connection,
so the plan cannot name it as a `CONNECTION` item). A connection that cannot be deleted (a mapping still uses it) is a FAIL and a LEFT BEHIND line. **The project's topics are deleted too** (lot F4, 2026-10-19):
after the project deletion, `kafka-topics.sh --delete --topic <name>` for each topic the project's targets named and each one the broker lists under the project's prefix - and ONLY a name that starts with
`<project code lower case>.` (`E2E_KAFKA_HOME`, `E2E_KAFKA_BOOTSTRAP`); a name read from a target row that does not start with it is never deleted and is reported. `--keep-topics` leaves them (LEFT BEHIND says so), a
project whose deletion did not complete keeps them, and a broker that does not answer leaves them with the reason (a SKIP, not a failure). A deletion the broker refuses is a FAIL and a LEFT BEHIND line. Without a
cleanup the connection, the target and the topics are left with the project and listed.

## How it is tested without Kafka

`b2c_standin.py` gains a `FakeKafka` (a broker in memory whose `reader` and `probe` are handed to the scenario through its two injection points, the `topic_reader` and `broker_probe` entries of its arguments), the
Connectors dataset (Connector, ConnectorProperty), the distribution routes, the add-on's `acceptSuspects` and (lot S4) `unMerge`, `DISTRIBUTION_FLOW` items in the deletion plan, `PipelineStep` rows and the `Linked_Field_State` column of the person
master rows. `b2c_master_selftest.py` (run by `b2c_selftest.py`, i.e. by `tools/test_local.sh`) tests: the console-consumer output parser, the broker probe and the topic reader (with a fake `run`), the golden-only audit, the
value comparison, the pickers; the good runs (merging by itself, and with suspects left so the merge test runs); the broker down (SKIP, nothing written, no topic read); the defects `ds_mergedpub`, `ds_missinggolden`,
`ds_dupmsg`, `ds_wrongkey`, `ds_onetopic`, `ds_rtmissed`, `ds_rtdouble`, `ds_sendfails`, `ds_notarmed`, `ds_configfails`, `ds_notriggers`, `ds_noconnection`, `ds_nogoldenmsg`, `ds_nojournal`, each shown to turn the check that
guards it to FAIL; the merge-test variants (`ds_noretire` = the gap before lot S2, `ds_acceptnomerge`, `ds_acceptnoB`, `ds_acceptrefused`); a build without the routes (exit 3 only with `--distribute`); `--through 9` / `10`; and the cleanup
(`ds_targetsleft`, `ds_flowsleft`, `--cleanup-project` in a second process). The stand-in proves the logic and the wiring, not the Kafka CLI, the Connectors behaviour or the add-on.

Lot S4 (2026-10-18, after the first live run) adds to the stand-in: `_change` on every message, the CLI's deprecation notice as the first line of every read (parsed by the same `e2e_lib.parse_console_output`
as the real reader), the retire notices of a merge (default, as since lot S2; `ds_noretire` is the old gap), the unmerge route with its sweep (RETIRE of the golden with reason UNMERGE, a record for each member back
to GOLDEN) and the member table's `Linked_Field_State`. Variants that are not defects: `ds_nochange` (a build before lot S2), `ds_unmergebare` / `ds_unmergedouble` (the add-on wants another path form),
`mm_unmergepending` (members back PENDING_APPROVAL), `ds_unmergenoretire` (a METRIC, no FAIL), `mm_membercopies` (the cascade merge's golden copies, the INFO line says the figure is expected). Defects turned to FAIL:
`ds_unmergesilent` (nothing arrives after the unmerge), `ds_livemerged` (the live-only guard), `ds_mergedpub` on a member table; `ds_unmergerefused` and `ds_unmergenoop` end in INFO + SKIP.

## What is unsure (to look at on the first live run)

- **The Kafka CLI**: the output format (`key<TAB>value`) and the exit status (1 after the idle timeout, the normal end) were confirmed on the CLI 4.2 by the first live run (2026-10-18). `E2E_KAFKA_IDLE_MS` is the knob.
  The `--formatter-property` flag was read on the live topics by hand (212 + 1 messages, no notice); the fallback to `--property` for an older CLI is tested with a fake `run` only.
- **The connection written by Data Services**: only `id` and `type` are mandatory in the Connectors model, the other fields mirror the `KafkaMdmDistribTest` row (whose `password` / `apiKey` read `TO_BE_SET`; they are not written
  here). Whether Connectors lists such a connection as a candidate at once is the first check (30 s).
- **`acceptSuspects`**: the request is the add-on's OpenAPI `ActionGroupRequest` (`dataspace`, `dataset`, `tablePath`, `groupId`, `recordPrimaryKeys`); the `B` prefix of the dataspace is assumed (as for `matchingProcesses`),
  with one retry without it on 400 / 404 / 500; whether the REST accept merges on the spot (the Java `acceptSuspectsAndMergeGroups` does) is measured, not assumed.
- **The retire notices of a merge**: only measured when a suspect pair is left (never after `--auto-merge`); the expectation printed is lot S2's (2 of 2, reason MERGE).
- **The unmerge route (lot S4)**: found in the OpenAPI and the bytecode, **not yet called live** (the lot is read-only against the instance). Which of the three path forms the add-on accepts is the first thing the next live run
  will say; whether the unmerge of the person table's golden also unmerges the member table's golden copies (cascade) is read from the INFO lines of the other topics.
- **The real-time write** changes a free-text field of a golden record through Data Services; if the policy matches on that field and runs on update, the inline matching may move a suspect (the merge test avoids that record).
- **Orders of magnitude**: a CLI read costs about 10 s of idle time per topic and per poll, so step 10 adds several minutes to a run.

## Factory gaps seen from here (nothing was changed on the Factory side)

- **No route creates a Kafka connection for a target**: the wizard picks an existing one, so the scenario writes the `Connector` / `ConnectorProperty` rows itself by Data Services - and, for the same reason, the
  deletion plan cannot know it (no `CONNECTION` item): the scenario deletes it.
- **`install-triggers` is a synchronous request** (it publishes a data model: minutes); the wizard has a job for it (`DistributionSetupRun`) but REST has none, so a client must hold a long request.
- **The summary has no data route**: only the HTML. A `GET /projects/{code}/summary` returning `WizardSummaryData.Output` (zones, golden count, open points, journal, distribution rows) would let step 11 check the
  rendered numbers instead of the facts they are built from.
- **A merge by Match and Merge notifies nobody through a trigger** (section 5.3): the merge-log watch and, since lot S2, the state sweep cover it; the `METRIC merge notifications` and `METRIC unmerge notifications` lines report what arrives.

# Shorter test cycle (lot F4, 2026-10-19)

A full master run costs 9-12 minutes, two thirds of it in language model calls. Two tools cut that without changing a single check of the scenario.

## Resume a project: `--from-project CODE --from-step N`

```
tools/e2e/b2c_master.sh --from-project E2EMASTER<yyyymmddhhmmss> --from-step N [--variants plain|simulate-first] [--through M] [--distribute] [--cleanup] ...
```

`N` is a step as the log prints it (`== step N: ...`), 2 to 11 - typically **7** (master build), **8** (matching) or **10** (distribution). The scenario does not create a project: it loads the existing one (the
project a previous run left in place), rebuilds what steps 1..N-1 leave in memory from the Factory's tables (`ProjectSource`, `LandingTable`, `LandingColumn`, `StagingEntity`, `TransferFeed`, `StagingAttribute`,
`AttributeMapping`, `MatchPlan`, the staging, master and metadataset tables, `DistributionTarget`) and from the fixtures regenerated with the same seed (`gen_b2c.py` is deterministic, the self-test pins its bytes;
that is where the ground truth comes from), SKIPs steps 1..N-1 in the table (`resumed from project ... at step N`) and runs N.. exactly as a whole run does. One variant only: `plain`, or `--variants
simulate-first` (`both` is refused). `--keep-files` keeps the regenerated fixtures as usual.

A project that is not in the state step N starts from is **refused** - exit 2, "RESUME REFUSED: ..." naming what is missing, nothing posted. What each step asks of the project:

| from step | the project must have |
| --- | --- |
| 2 | no source yet (step 2 uploads the files) |
| 3 | the three applications with their landing table and columns; no job running |
| 4 | the three landing tables LOADED with their row counts |
| 5 | a person entity unifying the sources (step 4) |
| 6 | an ACCEPTED or APPLIED entity (step 5) |
| 7 | a staging dataset whose person table has rows (step 6) |
| 8 | a master dataset whose person table has rows (step 7) |
| 9, 10, 11 | an APPLIED person plan and a metadataset with a row per master record (step 8) |
| 10 | and no `Kafka<code>` connection or distribution target: step 10 creates them (a project whose step 10 ran is refused) |
| 11 | and no distribution target: step 11 judges that card against the run's own step 10 |

On a resume at 10 or 11 the step-9 reading of the metadataset states (what step 11 compares the golden count with) is taken from the loader, so step 11 judges the same thing as in a whole run. The loader uses the same reads as the steps themselves; it was exercised against the stand-in factory only (the build is not installed), never against a live instance.

## Record / replay of the language model's answers

See `docs/OPERATIONS.md` (the three properties) and `LlmRecordStore` / `LlmReplayChat`. With `-Debx.factory.llm.record.dir=<dir>` on the instance's JVM every answer is stored; with `-Debx.factory.llm.replay.dir=<dir>`
the same request (provider, model, parameters, system prompt, tools, whole conversation) is answered from the store, no call to the model. A project run with record on, resumed with replay on from the same
project, replays the passes that are re-run (matching proposal, quality, ...) in seconds. A request that embeds something that differs between runs (a project code with a time stamp) is a new digest: replay
a run against the SAME project. A replayed turn shows in the `AgentStep` row (`arguments` = `replayed from <file>`) and the run's outcome ends with `[LLM replay: n of m turn(s) answered from <dir>]`;
`GET /status` carries `llm.replay` / `llm.record`.

