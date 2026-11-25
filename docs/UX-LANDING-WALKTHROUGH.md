# UX lot - the Landing step as a walkthrough (wizard step 3)

2026-10-16/17, branch `feature/landing-per-application` (from `main` fb9e4653). Mockups: `docs/mockups/landing-walkthrough/` (project Zefz, real data).
Status: coded, `tools/test_local.sh` green, NOT deployed, NOT seen running. **The XSD changed (three fields on `ProjectSource`): a Tomcat restart is
needed at deployment** (module XSD, read at start). Until the restart the fields are absent from the model: the wizard reads "not validated" and the
validate action writes nothing, so the launch page stays closed - restart before testing.

## What changed

1. **Walkthrough** (`wizard/LandingWalk` rules, `wizard/LandingWalkPage` markup). Before any landing run exists, step 3 is a sub-rail (one entry per
   application in `ProjectSource` id order, then Launch; validated entries green with "N tables", the current one highlighted, validated and current ones
   clickable), then one page per application: tick, table, rows, key (chips + origin chip: declared / chosen by you / deduced with its % / none), "Why"
   (only facts: empty, not ticked at the Sources step, sample data set). Empty unticked tables are folded behind "See the N empty tables", unticked tables with
   rows are listed under "Not kept". Ticks travel with the page's buttons in one submit (`data-mdmf-ticks-scope`, same convention as step 2) and are written
   by the existing `WizardData.setTablesSelected`.
2. **Keys.** Deduced key: "Check now on 25 rows" (the existing key study; applied as the person's choice, origin USER) and "Choose another key..." (the
   existing chooser panel, now `renderKeyChooserPanel`). No key: a picker on the page (proposal ticked and tagged when a language-model key under 90 % is
   stored on the table, `Use <columns>` writes it with origin USER) and "Measure uniqueness on 25 rows" (the chooser). Validating without a key is allowed;
   the page says the table waits during the run.
3. **Language-model consent** per application page, only when a ticked table has a deduced or missing key (and DESIGN right + a configured connector):
   two radios, No by default. The run now receives the list of applications that said Yes (`LandingRun.start(..., pLlmSourceIds)`): an application that did not
   say yes never has a row sent, whatever the others chose. The choice is plain service state, kept for the run (lost when the dialog is closed).
4. **Persisted validation.** `ProjectSource.landingValidated` / `landingValidatedBy` / `landingValidatedAt` (`tools/build_xsd.py` -> `MdmFactory.xsd`).
   Cleared inside the same `Procedure` by every write of a tick or a key (`toggleTableSelected`, `setTablesSelected`, `setTableKey`, `writeTableKey`), so
   step 2 and any other path clear it too. Not cleared by the landing run's own key decisions, nor by a re-plan that adds tables.
5. **Launch page.** Table of every ticked table (application, table, rows, key + origin, "Modify"), then "Prepare the landing - N tables" (new action
   `LANDING_WALK_LAUNCH`), open only when every application is validated and one table is ticked; otherwise disabled with the reason and a link to the first
   application to validate. The former Advanced disclosure is renamed "Table-by-table settings (advanced)" and sits under it.
6. **After a run**: running / done / partial views unchanged, plus a quiet "Review the applications" (`LANDING_WALK_REVIEW`, validations kept) and a way back.
7. **Gate bug.** `WizardRules.actionGates(snapshot, admin)` is project-wide; `generateLanding` is enabled when ANY application has a ticked table. All callers that
   passed `sources.get(0)` use it.
8. **"Loading this step..." that never loaded.** Cause found: the step-2 button "Continue to the landing" shows step 3 client side and, when no tick changed, skips
   its submit (`data-mdmf-skip-if-clean`); since wave 11 step 3 is a placeholder, so nothing ever asked the server for it. Fixed in `wizard.js`: that branch calls
   `loadDeferredStep`; and a guard at load asks once for a visible placeholder (sessionStorage, keyed by user + project + step, never loops). The placeholder text
   says "This step is not shown yet." with a button "Show the <step> step"; the loading line replaces it only when the request is sent.

New actions (all `DESIGN`, the three navigation ones `VIEW`): `LANDING_WALK_GOTO/REVIEW/CLOSE/VALIDATE/STUDY/STUDY_APPLY/KEY_CHOOSE/KEY_APPLY/LAUNCH`.

## Deviations from the mockups
- The key picker of a table with no key is a list of ticks (composite-capable, proposal first and ticked), not radios + "Several columns together..."; the
  uniqueness measure is the existing chooser opened by "Measure uniqueness on 25 rows". Reason: no sample is read at each display.
- "Why" never carries a business reason (the mockup's "internal Salesforce accounts"): only empty / not ticked / sample data set.
- The language-model sentence says "25 rows of each table whose key is uncertain" (a page can hold several), not "of this table".
- The "Nouveau" chip and the right-hand cards are not reproduced as a column; the two cards sit under the page.
- Footer count is the server's (changes with the next submit); only Validate's enabled state follows the boxes live.

## Live checks after deployment (project Zefz)
1. Restart Tomcat (XSD). Open the wizard: step 3 shows the sub-rail Salesforce - Snowflake - PersonnesSapSample - Launch, not the one-click screen.
2. Salesforce with 0 of 17 ticked: Validate is disabled with "Tick at least one table, or remove the application."; tick 3 tables: it opens at once, click it.
3. Reload, reopen in another tab: the Salesforce entry is still green. Untick a table in step 2: it turns grey again.
4. Snowflake: the deduced key shows its %, "Check now on 25 rows" shows the study, "Choose another key..." opens the chooser. The language-model radios appear, No ticked.
5. SAP sample file: the proposed column is ticked, "Use BusinessPartner" saves it; validate without choosing is accepted.
6. Launch page: all rows, "Modify" opens the right application; the button is open only when all three are validated. The run starts even though the first
   application had 0 ticked tables at the time of the old bug. With No on every page, no language-model call in the jobs/logs.
7. After the run: "Review the applications" re-opens the walkthrough, validations kept.
8. Step 2, "Continue to the landing" with no change: step 3 loads by itself. Open the wizard with step 3 as the starting step: no "Loading..." left.

## 2026-10-17 display fixes

Seen live on project Zefz; three defects, `fix/landing-walk-display`.

1. **Applications are named by their connection, not their code.** `LandingWalk.name(snapshot, source)`: the connection's label, else the `ProjectSource.connector` id, else the
   source-system code (then the row id). The code (`DATA_PRODUCTS`) is small secondary text next to the page title (`Snowflake - DATA_PRODUCTS - application 2 of 3 - 11 tables read`).
   Two applications of a project on the same connection get the code in brackets in the sub-rail, the buttons, the launch page and the notices (`Snowflake (DATA_PRODUCTS)`).
2. **One lead.** While the walkthrough is shown the page head carries `wizard.walk.lead` only; `wizard.step3.lead` stays for the run, done and partial views.
3. **"Not kept" list.** Ordered: tables in the schemas of the ticked tables first, tables with a key (declared or deduced) before keyless ones, then by name. Tables of Snowflake's
   sample databases (schema `SNOWFLAKE_SAMPLE_DATA`, `TPCH_SF*`, `TPCDS_SF*` - by schema name only, `LandingWalk.isSampleDataSet`) are out of that list, in their own folded group last
   (`wizard.walk.sample.group`). The key actions ("Check now on 25 rows", "Choose another key...") appear on ticked rows only; an unticked row shows the key chip and origin.
