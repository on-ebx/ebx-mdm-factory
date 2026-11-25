# The B2B end-to-end scenario (landing + staging), automated

Written 2026-10-22, branch `feature/e2e-b2b-scenario` (worktree `ebx-mdm-factory-e2e-b2b`, from `main`).
Status: coded, `tools/test_local.sh` green (the scenario's own self-test included), **NOT run against a live instance** - the scenario writes (project,
Connectors connections, dataspaces, models, a staging load) and its first live run is the coordinator's, after install.

It is the twin of the B2C scenario (`docs/E2E-B2C.md`): the same steps 0-6, the same plumbing (`tools/itest/client.py`, `tools/e2e/e2e_lib.py`, the
`b2c_scenario.py` `Scenario` class it subclasses), the same safety guard and report, with other files and other assertions. Where the two behave the same, this
document says so and does not repeat the B2C one.

## Run it

```
EBX_URL=http://localhost:8080 EBX_USER=<administrator> EBX_PASSWORD=<password> tools/e2e/b2b_scenario.sh [--cleanup] [--through 1..6] [--report FILE] [--keep-files]
tools/e2e/b2b_scenario.sh --cleanup-project E2EB2B<yyyymmddhhmmss>    # deletes the project an earlier run left in place
tools/e2e/b2b_scenario.sh --selftest      # generator invariants, assertion logic, the whole scenario against a stand-in factory on 127.0.0.1 (no instance)
tools/e2e/b2b_scenario.sh --dry-run       # generates the three files and the ground truth, prints the counts and the overlaps, the plan and the routes; sends nothing
tools/e2e/gen_b2b.py OUT_DIR [--sap-envelope odata|results|array] [--web-envelope submissions|array]   # the files alone, and truth_b2b.json
```

- Python 3 standard library only. Credentials come from the environment only, travel in an `Authorization` header, are never printed or written to the report. An
  `http://` URL is refused for a host that is not local unless `E2E_ALLOW_INSECURE_HTTP=1`; `E2E_CACERT=<pem>` for a private CA.
- Every mutating request must name the run's project code (`E2EB2B<yyyymmddhhmmss>`) in its path or body, or be one of the few record paths the scenario read first from
  a query filtered on that project: anything else raises `SafetyError` before a byte is sent (the guard of `tools/itest/client.py`, unchanged).
- Exit codes: `0` every check passed, `1` at least one FAIL, `2` environment/arguments, `3` a route the scenario needs does not exist on this build.
- Environment knobs: all those of the B2C scenario (`E2E_POLL`, `E2E_THRESHOLD`, `E2E_TIMEOUT_*`, `E2E_OUT_DIR`, `EBX_MODULE`...), plus `E2E_SAP_ENVELOPE`
  (`odata` by default, `results` or `array` to flatten the SAP envelope, see "What is unsure") and `E2E_WEB_ENVELOPE` (`submissions` by default, or `array`).
- Report: the PASS/FAIL table on the console and `e2e-out/b2b-<project>.json` (`details.groundTruth`, `details.validationDesk`, `details.entities`, `details.landing`,
  `details.rowsPerEntity`, `details.rejects`...), and the ground truth of the files next to it, `e2e-out/b2b-truth-<project>.json`.
- Whole run about 10 minutes against a live instance, like B2C. The project and its three Connectors connections are left in place without `--cleanup` (the
  "LEFT BEHIND" list says which).

## The data (`tools/e2e/gen_b2b.py`, seeded, byte for byte the same on every run)

Every company is invented (a brand of syllables + a sector word + a legal form; the self-test checks that no generated name contains a well-known brand), every
SIREN is a random Luhn-valid number, every person of the web form an invented first name and surname.

| Application code | File | Shape | Rows | Key |
|---|---|---|---|---|
| `SAP` (trust 100) | `SAP_BusinessPartner.json` | SAP S/4HANA Business Partner, OData v2: `{"d": {"results": [...]}}`. `BusinessPartner` (10 digits, leading zeros), `BusinessPartnerCategory` "2", `BusinessPartnerGrouping`, `OrganizationBPName1` (legal name cut at 40 characters) / `OrganizationBPName2` (the rest, 20 partners), `SearchTerm1`, `Industry` (SAP key: MACH, CHEM, TRAN...), `LegalForm`, `CreationDate` as `/Date(ms)/`, nested `to_BusinessPartnerAddress.results[]` (1-2 addresses: `AddressID`, `StreetName`, `HouseNumber`, `PostalCode`, `CityName`, `Country` ISO2, `Region`; 199 in all) and `to_BusinessPartnerTax.results[]` (`FR1` = SIREN, `FR0` = French VAT, `DE0` / `ES0` / `IT0` / `BE0` = the VAT of the other countries) | 150 | `BusinessPartner` |
| `SALESFORCE` (trust 90) | `SF_Accounts.csv` | `,` separator, every field quoted. `Id` (18 characters, `001...`, with the real case checksum), `Name`, `BillingStreet` (number + street in one: "12 bd Voltaire"), `BillingPostalCode`, `BillingCity`, `BillingCountry` as a NAME ("France", "Germany"), `Phone` (mixed formats), `Website`, `Industry` (Salesforce picklist: Machinery, Transportation...), `NumberOfEmployees`, `AnnualRevenue`, `SIRET__c` (14 digits, 78 of 120 filled), `VAT_Number__c` (88 filled, some with spaces), `SAP_BP__c` (the SAP number, 36 rows = 30 %) | 120 | `Id` |
| `WEB` (trust 70) | `WEB_AccountRequests.json` | `{"submissions": [...]}`. `submissionId` (UUID), `submittedAt` (ISO with a timezone), `companyName` (typed freely), `siren` (typed: with spaces, 11 with a wrong checksum, 27 empty), `country` (free text: "FR", "france", "Deutschland"...), `contactFirstName`, `contactLastName`, `contactEmail` (the company's website domain for 80 %), `contactPhone`, `jobTitle`, `message`, `consentMarketing` (boolean), `utmSource` | 90 | `submissionId` |

### The overlaps (ground truth: `truth_b2b.json`, company -> keys in each source)

190 companies: **48 in the three sources, 40 in SAP + Salesforce, 14 in SAP + web, 12 in Salesforce + web, 48 only in SAP, 15 only in Salesforce, 13 only on the website
(prospects)**: 114 companies (60 %) are in at least two sources, 48 (25.3 %) in all three. Salesforce holds **5 pure duplicates** (two accounts for the same company), the
website **3** (the same company submitting twice): 150 + 120 + 90 = 360 rows. The same company is written differently in each source - legal form kept / dropped /
"S.A.S.", upper case, accents dropped, abbreviations (Sté, Ets, Cie), a typo now and then on the web -, the addresses differ slightly ("bd" for "boulevard", a missing house
number, the second SAP address used by Salesforce for 15 % of the multi-address companies), the SIREN is in SAP (tax FR1), in Salesforce's SIRET (its first nine digits) and in
the web form (typed). 85 % of the companies are French; the others (DE, ES, IT, BE) have no SIREN and a VAT number of their country (Italian: Luhn, Belgian: mod 97). French VAT
= `FR` + key + SIREN with the real key `(12 + 3 * (SIREN mod 97)) mod 97`.

The ground truth also lists, per company, its country, SIREN, SIRET, VAT and website domain, and (`summary.expectedIso2`) the ISO2 code every Salesforce and web key must end
with after the load.

## The sequence and what it asserts

Steps 0, 1, 2 (upload of the three files with codes SAP / SALESFORCE / WEB and trust ranks 100 / 90 / 70), 3 (landing: keys, one launch), 5 (policy) and 6 (preflight, generate,
load) are the B2C ones (`docs/E2E-B2C.md`, "The sequence"); the B2B assertions are:

| Step | Asserts |
|---|---|
| 2 | the generated files themselves: 150 / 120 / 90 rows, the keys unique, the SAP numbers, the Salesforce Ids, the columns |
| 3 | landing keys `BusinessPartner`, `Id`, `submissionId`: the suggestion is validated as it stands when it is exactly the expected column, otherwise the expected column is chosen (origin USER); **one** `LANDING_RUN`; each landing table `LOADED` with exactly 150 / 120 / 90 rows. INFO: what the SAP landing produced for the nested collections (below) |
| 4 | **one organisation entity** whose `memberEntities` hold the three rule entities and whose feeds read the three landing tables; no rule entity left PROPOSED; **an address entity** (a child of the organisation: its feeds sit under the organisation's feeds, or it is named after addresses) **fed by the SAP landing table and by the Salesforce one**; the **identifiers carried**: a live mapping reads the web `siren`, Salesforce's `SIRET__c` and `VAT_Number__c`; **country normalised**: the links that read `BillingCountry` (Salesforce) and `country` (web) have a `COUNTRY_TO_ISO2` or `TRANSCODE_REFERENCE` step; the **web SIREN cleaned**: its link has a step that removes the spaces (`NORMALISE_IDENTIFIER`, `STRIP_CHARS` with a space, digits only). INFO, never a failure: every entity with what feeds it and its attributes, the landing columns no live mapping reads, whether the web form feeds the address entity, what carries the SAP tax collection |
| 5 | bulk accept (`staging/decide`, threshold 60); **then, before any policy decision, `GET staging/review`: what is left by nature is written to the report** (console INFO lines, `details.validationDesk` with, per line, element, origin, confidence, rationale and the decision the policy will take) - it is what the Validation desk would show, for the coordinator to compare with the screen; then the B2C policy (`e2e_lib.decision_for`), `defaultCountry`, nothing left PROPOSED; the country and SIREN steps again, on the accepted state |
| 6 | per entity: rows written, **0 `TransferReject`** (otherwise FAIL with the reject reasons listed: `reason x count`); the organisation entity holds **360 rows** (150 SAP + 120 Salesforce + 90 web); the address entity holds one address per Salesforce account (**120**) and at least one per SAP partner (**>= 150**, INFO says whether every collection item became a row); the **web SIREN** is loaded for exactly the 63 submissions that typed one, all nine digits (spaces gone) and 52 Luhn-valid (the rest keep their wrong checksum - the load does not hide it); Salesforce **SIRET** loaded for the 78 rows that have one, all Luhn-valid; Salesforce **VAT** loaded for the 88 rows that have one (INFO: how many are French with a valid key, how many keep spaces); **every loaded address has an ISO2 country** (two capital letters); the country of the Salesforce and web rows is **the right code** (compared with the ground truth through the staging `sourceId`) |

### The Validation desk (step 5)

After the bulk accept the scenario reads `GET /projects/{code}/staging/review` and writes what remains, by nature, **before** it decides anything: the count, then up to
eight lines per nature with origin, confidence, rationale and the policy's decision. The whole list is `details.validationDesk` in the JSON report. The B2C policy then
decides (external look-ups rejected, a check that proposes rejecting rejected, the rest accepted).

### What the landing does with the SAP nested collections

The Factory documents no child landing table for a nested array: a landing table is one table per source file (`LandingTable` per `ProjectSource`), its columns the
source's columns, the nested objects flattened with dotted paths where the deduced scan does it, a nested array written as text (`docs/LANDING-FLOWS.md`, value table:
array -> comma-joined text, object -> its JSON text). The scenario therefore asserts what is certain (**one** landing table, **150 rows**: the collections must not
multiply the rows) and **reports** what the landing produced - the number of landing tables for the SAP file, its columns, the ones that mention the address and the tax
collections (`details.landing`). Whether the address entity then gets one row per collection item is checked in step 6 (`>= 150`, INFO with the 199 of the file).

## How it is tested without an instance

`tools/e2e/b2b_selftest.py` (run by `tools/test_local.sh`, or `b2b_scenario.sh --selftest`):

- **the generator**: deterministic byte for byte; 150 / 120 / 90; the three envelopes (and the two alternatives); SAP numbers, Name1 <= 40, `/Date(ms)/`, 1-2 addresses, the tax
  types, SIRENs Luhn-valid, SIRETs Luhn-valid and starting with the company's SIREN, French VAT keys (including the textbook `FR83404833048`), the Belgian mod 97 and Italian Luhn;
  18-character Salesforce Ids with their case checksum, quoted CSV, names of countries, `SAP_BP__c` = the SAP number of the same company on 36 rows; web UUIDs, ISO timestamps
  with a timezone, SIRENs with spaces / empty / wrong checksum, e-mail domains; the overlaps (60 % / 25 %, prospects, duplicates inside a source), every key of every file in
  exactly one company of the truth, the same company written differently, no well-known brand;
- **the assertion logic** on canned data: the SIREN-cleaning and country-step detectors, the address-entity finder, the ISO2 and country-against-truth measures, the desk
  summary, the OData hint, the broken-file detection;
- **the whole scenario against the stand-in factory** (`b2c_standin.FakeFactory(b2b=True)`, its B2B part in `b2b_standin.py`; the routes, tables, jobs, review and bulk accept
  are the B2C stand-in's, not duplicated): a good run (every check passes, exit 0), with cleanup, `--through 3`, the `results` / `array` envelopes, and **seventeen defects** (nineteen guarding checks),
  each shown to turn the intended check to FAIL: a rule entity left alone, an organisation that unifies two sources, SAP not feeding it, no address entity, an address entity not
  fed by Salesforce / by SAP, no country step, no SIREN cleaning step, no SIREN link, a SIREN loaded with spaces, a Salesforce country loaded as typed, a wrong country code, no SAP
  address row, a missing Salesforce address, a `TransferReject`, SAP landing one row (with the OData explanation printed), an organisation entity one row short. The stand-in is
  not a model of EBX: it proves the scenario's logic and wiring, not the instance's behaviour.

## What is unsure (to look at on the first live run)

- **The OData envelope is probably NOT unwrapped by the sample-file connector.** Read in `FileFormats.parse` of Connectors `main`: a JSON object is unwrapped when it has one of
  `rows`/`items`/`data`/`results`/`value`/`records` at its top level, or when it has exactly ONE property that is a non-empty array of objects (the other properties being
  pagination/status scalars - that is why `{"customers": [...]}` and `{"submissions": [...]}` work). `{"d": {"results": [...]}}` has one property that is an object, so the whole
  file is likely ONE row. If so, step 3 fails on `SAP: 150 rows loaded` and prints why; run again with **`E2E_SAP_ENVELOPE=results`** (`{"results": [...]}`, unwrapped) to see the
  rest of the scenario, and tell the Connectors owners that a real S/4HANA OData answer is not read (an evolution request: unwrap `d.results` / `d`).
- **How the nested SAP collections land.** Read from the code, not measured: the address and tax collections probably become JSON text columns (or dotted columns), not child
  rows. The Factory's staging layer may then be unable to give one address row per collection item (`>= 150` still holds if it reads the first address, or the whole text, once per
  partner); and the SAP tax collection (SIREN `FR1`, VAT `FR0`) may be carried by nothing readable - step 4 reports it (INFO), it does not fail on it.
- **Who feeds the address entity.** The scenario fails if the model proposes no address entity or leaves SAP or Salesforce out of it, because the brief says so. A model that keeps
  the address fields on the organisation entity (a legitimate choice) fails step 4 on the entity check - the INFO lines (entities, attributes, unread landing columns) show what it did
  instead. Same for the country: if it is mapped on the organisation entity and not on an address, the "right ISO2 code" check still runs (it follows the attributes fed by the
  `BillingCountry` and `country` columns), "every loaded address has an ISO2 country" is skipped.
- **The country step may be a different mechanism.** `COUNTRY_TO_ISO2` and `TRANSCODE_REFERENCE` are accepted; a model that proposes another step kind, or a pipeline function of
  another name, fails the step-level check even if the load then writes ISO2 - the load-level check is the one that matters (read both).
- **Column names are matched by their source name**, lower-cased: `siren`, `siret__c`, `vat_number__c`, `billingcountry` (Salesforce), `country` (web). A landing column that EBX
  renames (a cleaned identifier) would be missed - the check says "no live mapping reads the column".
- **Staging `sourceId` shape for child rows.** The country-against-truth comparison ties an address row to the ground truth by its `sourceId` (exact, or the part before the first `|`);
  if the address entity writes another shape the comparison says so (INFO) and only the ISO2 form is checked.
- **Exact counts** (360, 120, 63 / 52, 78, 88) hold while the files stay seeded and the Factory neither merges nor drops rows in staging; the web SIREN count assumes the cleaning step
  removes spaces only (a model that also rejects the wrong checksums would load 52 and fail the check on purpose - decide then whether that is the better behaviour).
- **Matching is out of scope**: the 190 companies of the ground truth are the material for a later master/matching scenario (the keys in each source are written for it); this one stops
  at the staging, like the B2C nominal run.
