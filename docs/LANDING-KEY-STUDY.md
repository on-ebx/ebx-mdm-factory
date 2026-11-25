# Landing key study — "Study the key"

Landing key-study lot, 2026-09-23. Product owner, on project Personnes, looking at the SAP table's key picker
(21 checkboxes, no proposal, confidence "—"): *"We should get some help from the LLM to identify the key, but
also being able to display a sample of data to help me making a choice."*

## What a person sees

Step 3 of the wizard, in the **Proposed key** cell of every landing table, next to *Update key*: a **Study the
key** button. One click, one round trip (a progress toast while it runs), and a panel opens under the table's
row with, top to bottom:

1. **The proposal** — the key columns, who proposed them (*proposed by the language model* / *proposed by the
   naming rule*), a confidence bar, the rationale, and whether those columns are unique together on the
   sample ("Unique together in the 25 sample row(s), no empty value — evidence, not proof"). Then **Apply this
   key**. On a table whose landing is already generated the panel still renders (it explains the key the
   landing was built on) but *Apply* is disabled with the reason.
2. **How the language model went** — steps and tokens, and the disclosure that the sample rows, values
   included, were sent to it; or why it was not asked (administrators only / no connector configured); or its
   failure text; or, when it answered in prose instead of calling its tool, what it said.
3. **The evidence, one row per column of the table** — type, the first three distinct sample values as code,
   `distinct / empty` counts, a tick when the column is unique in the sample, a tick when it is in the
   proposal (the row is highlighted), and a *current key* chip on the columns the key currently holds. A
   column the sample does not have says *not in the sample*.
4. **The sample rows** themselves (the same table as the *Preview* button, 25 rows instead of 5).

The **Confidence** cell of the row itself now also says who proposed the current key and folds its rationale
(*Show rationale*), for every table, studied or not.

Nothing is written by the study. *Apply this key* writes it; the picker's checkboxes and *Update key* remain the
way to choose by hand. A human decision is never overridden by a pass: the study only ever *proposes*.

## How it works

| Piece | Where | Pure? |
| --- | --- | --- |
| Sample | `ebx.LandingPreview.sample(factory, tableId, KeyStudy.SAMPLE_MAX = 25)` — the connector's own `read` operation through `connectors_call`, exactly the *Preview* path | no |
| Evidence | `landing.KeyEvidence.analyse(columns, rows)` → per column `rows`, `empty`, `distinct`, first 3 values (cut at 40 chars), `uniqueInSample()`, `nullRate()`/`uniqueRate()`; `uniqueTogether(sampleColumns, rows, keyColumns)` for a composite | yes |
| Rule | `landing.SourceKeyRule.propose(tableShortName, KeyEvidence.candidates(...))` — the plan's own rule, fed the sample's rates as the profile it asks for (no column flagged "declared at the source") | yes |
| Language model | `landing.KeyLlmPass` — `systemPrompt()`, `userPrompt(TableFacts)` (JSON: table, columns with type/nullability/evidence, current key and its origin, the sample rows), a `Session` exposing `propose_key {columns[], confidence, rationale}` and `note {text}` as `LocalTool`s | yes |
| Glue | `ebx.KeyStudy.study(session, factory, tableId)` builds a `landing.KeyStudyReport`; `KeyStudy.apply(session, factory, report)` writes it | no |
| Page | `wizard.WizardPage.renderKeyStudyRow` (+ `renderSampleTable`, shared with the preview) | yes |
| Service | `ProjectWizardUserService`: actions `TABLE_KEY_STUDY` / `TABLE_KEY_APPLY`, the report kept on the instance across the apply round trip like the preview rows | no |

The language-model pass runs through `AgentRuntime.run` (phase `KEY_STUDY`, traced as `AgentRun`/`AgentStep`
rows like every other pass) with the `Session`'s tool group as the only tools, then `ProseAnswerRecovery`'s one
follow-up when the run ended without calling `propose_key`. The model's proposal wins over the rule's when it
made one; with none, the rule's stands (when the rule itself found something); with neither, the panel says so
and the evidence table is what the person chooses from.

Gates, in order: not an administrator → `wizard.gate.adminOnly`; no `Settings.llmConnector` →
`wizard.gate.noLlm`. The sample and the evidence run for everyone; only the model is gated.

## What is stored

Two new fields on `LandingTable` (`tools/build_xsd.py`, regenerated `MdmFactory.xsd`): **`keyConfidence`**
(int, 0-100) and **`keyRationale`** (text, 2000). Written by:

- `LandingPlanner` at plan time (the rule's own figures — until this lot only folded into `message` as
  `"Source key: …"`, and the wizard showed a fixed 70 % for any non-empty key);
- `WizardData.setTableKey(session, factory, tableId, columnsCsv, origin, confidence, rationale)` — the new
  overload; the picker's *Update key* calls it with `USER`, 100, "Key chosen by a person in the wizard.", and
  *Apply this key* with the proposal's origin (`LLM` or `RULE`), confidence and rationale. Every column of the
  table gets the origin in `LandingColumn.sourceKeyOrigin`, unchecked ones included: the origin describes the
  key decision, not one column.

`WizardData.tableInfo` reads them back into `WizardSnapshot.TableInfo.keyConfidence` / `keyRationale` and
derives **`keyOrigin`** from the key columns' `sourceKeyOrigin` (USER wins over LLM over RULE). A table planned
before this lot has neither field: it falls back to 70 % and to the `"Source key: "` text of `message`.

The XSD change is additive (two optional fields on a module model): a Tomcat restart, nothing else.

## Why the model sees values

The staging pass sends metadata only. This pass sends the sample rows on purpose: telling a business-partner
number from a search term is exactly what the values show and the names hide (the SAP export's `BusinessPartner`
is not named like the table, so the naming rule has nothing to say about it — `KeyEvidenceTest` pins that
case). The page states it every time the model ran (`wizard.step3.keyStudy.llm.ran`), the sample is bounded
(25 rows, 40 characters per value) and the pass runs for administrators only, like every other one.

## Tests

- `landing/KeyEvidenceTest` — counts, trimming, ellipsis, a short row, case-insensitive lookup, composite
  uniqueness (true / false on a duplicate / false on an empty component / null when unknown), the rule fed by
  the sample (confirmed at 95 % for `PartnerId`, nothing for `BusinessPartner` in `Personnes_SAP_Sample`).
- `landing/KeyLlmPassTest` — the prompt carries values, evidence, the current key; the tool refuses an unknown
  column (naming the real ones), an empty list, an empty rationale; resolves spelling, de-duplicates, clamps
  confidence, replaces an earlier proposal; `note` recorded; `recordedNothing()`.
- `wizard/WizardPageTest.keyStudyPanelRendersProposalEvidenceAndApply` — the row's study button, origin and
  rationale fold; the panel's proposal, origin chip, 90 % bar, enabled/disabled *Apply*, uniqueness line,
  disclosure line, escaped code values, counts, *not in the sample*, highlighted proposed row, current-key chip,
  sample title; the no-proposal and skipped-model variants; no panel without a studied table.

Not yet seen running under Tomcat: the live sample of a real connector through the study, and a real
language-model run — the same `LandingPreview` and `AgentRuntime` paths the preview and the staging pass
already use.
