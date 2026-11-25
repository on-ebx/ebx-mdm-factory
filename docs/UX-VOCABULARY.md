# UX lot U5 - vocabulary, glossaries, developer notes out of the screens, distribution list (wizard), 2026-10-13

Branch `feature/ux-vocabulary`, from `main` e232c9b4 (wave A: U1, U2, U3, U6). Source: `ux-factory-wizard-2026-10-13/RAPPORT.md` sections 2.6, 2.12, 3.5
(project UxCrmSf). Status: coded, tests green, NOT deployed, NOT seen running. No XSD change.

## What was wrong (measured 2026-10-13)

- Step 3's key chip and the landing's done view said "proposed by the language model during a deduced scan, confirmed by a person"; the job label of the
  step-2 plan job read "Planning the landing"; messages named "agent run 254, DONE", the workflow model `MdmFactoryPipeline`, "deduced table".
- "Words used in this step" listed only some of the words the step's own text uses.
- Distribution listed 24 write-capable connections flat (ActiveMQ, the H2 of a demo, three test Kafkas, the project's own sources) and said "Distribution is
  optional: skip it" under the list.

## What was built

1. **Vocabulary sweep (EN + FR bundles).** Key list below. `WizardPageVocabularyTest.noInternalWordInTheUserMessages` now scans every `wizard.*` and
   `mappingMatrix.*` value of both bundles for: deduced scan, official scan, landing plan, agent run (and the French "execution de l'agent"), MdmFactoryPipeline,
   Xref, goldenKey, capabilities/, mockup, validated detail, "(module ebx-". The development note "Validated detail screens", the "NOT filtered ... 9 times
   slower" volume warnings and the raw add-on log text were already moved by lot U1 (Details folds); the `/projects/{code}/capabilities/139/apply` string is not in
   the bundles (see "not done").
2. **Glossaries.** Each step's "Words used in this step" now covers the words its own text uses (only the lists passed to `renderGlossaryCard` /
   `renderGlossaryTerms` changed, plus new `.term` / `.def` keys): step 2 application, connection, catalog, scan, table, key, trust order (+ sample file in the empty
   state, + code once an application is ready); step 4 business object, field, source link, rule, check, set aside, held back, staging (the sentence shown before the
   first proposal, `wizard.step4.run.intro.glossary`, was rewritten with the same words and no internal synonym: "staging entity", "attribute", "feed" are gone); step 5 master,
   golden record, cross-reference; step 6 matching plan, candidate search, sure match, to decide, golden record; step 7 (Distribution) target, connection, armed,
   data product, retire notice, cross-reference; step 8 (Summary) gets a glossary card for the first time: open point, automatic intake, schedule. Step 3 keeps its
   one-sentence glossary (landing, key, load, flow: the words of its text).
3. **Distribution in sections** (`wizard.DistributionConnectionGroups`, pure; `WizardPage.renderStep7` + `renderDistributionTargetList` + `renderDistributionConnectionRow`):
   "Distribution is optional: skip it and come back later." is the first line of the step (an info notice above the list; the bottom keeps only the "Next: summary"
   button). The title counts the connections that can receive records. Sections: **Likely targets** (write operation, not a source of this project, not test / H2 /
   sample; a connection that already carries a target of this project is always here, first), **Sources of this project** (connections of the project's applications,
   write-capable, flagged by a "source of this project" chip, with a hint), **Other connections** folded (H2 and sample-file connection types, and a label or id that
   contains the whole word test / demo / sample / H2), **N connections without a write operation** folded as before. Alphabetical inside a section.
4. **Landing done.** `renderLandingRunDone`: a key whose origin is DEDUCED (landing key origin and catalog scan origin) no longer shows the origin chip; it shows U2's
   own sentence (`keyLabel`, same keys `wizard.review.key.deduced`, `.by`, `.on`) after the key chips, without the closing colon ("Key proposed by the language model,
   confirmed by <user> on <date>" / "confirmed earlier"). Other origins keep their chip. The generic chip text (still used by the other four places that show a key
   origin) is now "proposed by the language model, confirmed earlier".

## Changed keys (old -> new; FR changed alike unless noted)

| key | old (EN) | new (EN) |
| --- | --- | --- |
| `wizard.step3.keyOrigin.DEDUCED` | proposed by the language model during a deduced scan, confirmed by a person | proposed by the language model, confirmed earlier |
| `wizard.jobs.kind.LANDING_PLAN_RUN` | Planning the landing | Choosing the tables to load |
| `wizard.progress.plan` | Planning the landing - please wait. | Choosing the tables to load - please wait. |
| `wizard.step3.notice.planStarted` / `planQueued` | Planning the landing in the background... / ... was queued... | Choosing the tables to load in the background... / ... was queued... |
| `wizard.jobs.kind.DEDUCED_SCAN` | Deducing tables from a source | Finding the tables of an application from its data |
| `wizard.step3.changeColumn.hint.set` | ... a file or a deduced table is always reloaded in full | ... a file or a table found from the data is always reloaded in full |
| `wizard.onboard.step.scanFlows.done` | The catalog created the scan flows (mapping {0}). | The catalog set up the reading of this application. (placeholder dropped on purpose: a mapping number; `WizardMessagesTest.ARG_KEYS` lost its entry) |
| `wizard.step4.anomaly.doneMessage` | {0} (agent run {1}). | {0}. (placeholder {1} dropped on purpose: run number; the Java caller still passes it, MessageFormat ignores it) |
| `wizard.step6.run.summary` | {0} written, {1} refreshed, {2} unchanged (agent run {3}, {4}{5}). | {0} written, {1} refreshed, {2} unchanged{5}. ({3}, {4} dropped; {5} is ", stopped early" or empty) |
| `engine.invalid.deducedTable` | A deduced table needs a catalog source and a name. | A table found from the data needs a catalog source and a name. |
| `wizard.catalog.restricted` | ... columns, deduced tables, data product ... | ... columns, tables found from the data, data product ... |
| `wizard.step2.noCatalog` | The data catalog (module ebx-data-catalog) is not installed ... | The data catalog is not installed ... |
| `wizard.step7.gate.notPublished` | The MdmFactoryPipeline workflow model is not published on this instance. | The workflow that chains the loads is not published on this instance: ask an administrator to publish it. |
| `wizard.intake.ready.PIPELINE_MODEL.ok` / `.ko` | The (published) MdmFactoryPipeline workflow ... | The (published) workflow that chains the loads ... |
| `wizard.intake.one.text` | ... Each chain is an instance of the MdmFactoryPipeline workflow ... | ... Each chain is one run of the loads workflow ... |
| `wizard.step2.glossary.trustRank.term` / `.def` | Trust rank / which application wins when two of them disagree on a value. | Trust order / the order of the applications: when two disagree on a value, the one higher in the list wins. |
| `wizard.step4.run.intro.glossary` | Business object = staging entity. Field = attribute. Source link = the feed ... | Business object = a kind of record the master will hold. Field = one column of it. ... Held back = ... |
| `wizard.step7dist.targets.title.one` / `.other` | Systems - {0} found | {0} system(s) can receive golden records (counts the write-capable connections only) |
| `wizard.step7dist.connections.empty` | No Connectors connection with a write operation was found. | No connection able to receive records was found. Use + Add a connection to create one. |


New keys: `wizard.step2.glossary.{connection,catalog}.{term,def}`, `wizard.step4.glossary.{businessObject,field,sourceLink,rule,check,setAside}.{term,def}`,
`wizard.step6.glossary.candidateSearch.{term,def}`, `wizard.step7dist.glossary.retire.{term,def}`, `wizard.step8.glossary.{openPoint,intake,schedule}.{term,def}`,
`wizard.step7dist.group.{likely,sources,sources.hint,other.one,other.other}`, `wizard.step7dist.connections.sourceChip`. Key `wizard.step7dist.skip.hint` is reused
(now shown at the top). Keys `wizard.step4.glossary.staging` and `.heldBack` are unchanged.

## Tests

`WizardPageVocabularyTest` (new): banned words over both bundles; the new/changed keys exist in EN and FR with the same placeholders and no French apostrophe next to a
placeholder; `DistributionConnectionGroups` on the measured list (target first, project source apart, H2 / demo / test / sample folded, a connection with a target never
folded, whole-word rule: "Attestation", "Latest"); the Distribution page in EN and FR (notice before the list and said once, section order, chip, folded sections closed,
title count, Next button); the landing done view (U2's sentence with and without confirmer, no "deduced scan", a RULE key keeps its chip); glossary terms of steps 2, 7 and
the step-8 keys. `WizardMessagesTest` lost the `scanFlows.done` argument entry. Harness: see the report line.

## Not done / limits

- Engine messages stored by the engine in `PipelineStep.message` are Java strings, not bundle keys, and were left to the lots that own those classes (a message already stored
  keeps its text): `MatchingRunFailure` / `MatchingConfigurator` ("Xref.goldenKey refreshed / NOT refreshed"), `StagingLlmSuggester` ("agent run N"), `CapabilityLlmSuggester`
  ("agent run ended ..."), the REST route text of capability applies, `CandidateFilterCheck` (the "9 times slower" paragraph, folded by U1 but still written in English in
  the step's detail). `engine.external.*` / `engine.conflict.*` messages that name a "Connectors run {0}" (operator-facing) and `engine.unexpected.agentRunStart` stay.
- The `wizard.deduced.*` card keys (U2's screen) and `lineage.graphic.retained.deduced` ("retained (deduced)", not a wizard screen) were not touched.
- The "test connection" heuristic of the Distribution list reads the connector type and whole words of its label / id; a production connection labelled "test" is folded
  (still one click away), a test one with an ordinary label stays in Likely targets.
- The non-administrator view, other locales than EN/FR, and the layout (no CSS change: `mdmf-eyebrow`, `mdmf-disclosure`, `mdmf-chip-muted`) were not checked in a browser.

## What the coordinator must check live (screenshots to compare: img/23b, 51b)

1. Step 7 on UxCrmSf: the blue "Distribution is optional..." notice sits right under the lead, once; "N systems can receive golden records" then **Likely targets**,
   **Sources of this project** (Salesforce CRM with the chip), a folded "N other connections (test, demo or sample)", a folded "101 connections without a write operation";
   the Next: summary button at the bottom. A target set up on a "test" connection stays in Likely targets.
2. Step 3 done view for the Demo CRM tables: no origin chip "deduced scan"; "Key proposed by the language model, confirmed earlier / by <user> on <date>" after the key chips.
3. Banner of the step-2 plan job and the notice after "Find its tables": "Choosing the tables to load".
4. Step 8: a "Words used in this step" card (open point, automatic intake, schedule) under the journal; steps 2, 4 (after the first proposal), 5, 6, 7 side cards list the
   extra terms; the French pages likewise.
5. After a model pass of step 6 or a step-4 anomaly pass: the new task message has no "agent run N" (older stored messages keep theirs).
