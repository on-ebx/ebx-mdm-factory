# What project data may reach the language model

Lot B of the production-readiness plan, 2026-09-24. Implemented by `ebx.LlmDataPolicy`.

## The three levels

| Level | What each column contributes to a prompt |
|---|---|
| `NONE` | name, type, fill rate, distinct count, uniqueness - no value, no shape, no example |
| `SHAPES_ONLY` (default) | the above plus the SHAPES of values: letters to `a`/`A` (any script, caseless letters included), digits to `9`, separators kept, long runs collapsed |
| `SAMPLE_VALUES` | the above plus a capped sample of real values (quality: at most 50 distinct per column; key study: 3 per column and 25 rows, cut to 40 characters; matching: the most frequent value and token) |

Two settings decide the level:

- `Settings.llmValueSharingMax` (instance, default `SHAPES_ONLY`): the most any project may share. An unreadable value
  fails closed to `NONE`.
- `Project.llmValueSharing` (project, default `SHAPES_ONLY`): what the project asks for.

The effective level is the more restrictive of the two. It governs the quality pass (`QualityLlmSuggester`, including
the `measure` tool's non-conforming examples and the before/after examples of a proposed cleaning step), the key study
(`KeyStudy` / `KeyLlmPass`), the matching plan (`MatchPlanLlmSuggester`) and the deduced scan's "name and key" pass
(`NameKeyPass`: the example of each column - nothing under `NONE`, its shape under `SHAPES_ONLY`, the cut value under
`SAMPLE_VALUES`). The staging, anomaly and capability passes never send values, whatever the level; their value
SHAPES (capability pass attribute profile, staging column shapes and rule-probe refusals) are sent under `SHAPES_ONLY`
and above only, never under `NONE`, and never for a sensitive column.

## Sensitive columns

`Settings.llmSensitiveColumns`: column-name patterns (`*` wildcard, case-insensitive, separated by commas, semicolons or
lines). A column whose name - staging attribute, source column or field - matches never reaches the model, whatever the
level: the quality pass leaves it to the deterministic rules, the key study shows only its counts (and `*` for a filled
cell), the matching plan never shows its frequent value. Empty setting: a built-in list (passwords, secrets, tokens, API
keys, IBAN and BIC, social security and NIR, passports, card numbers and CVV, birth dates, health, salary). Filling the
setting REPLACES that list. E-mail and phone columns are not in it on purpose (their format is what quality rules check);
add `*mail*, *phone*` for a stricter client.

## An internal, confidential model

Sending real data is possible, never the default. For a model hosted inside the company (Ollama, vLLM, a private
endpoint under the company's own confidentiality terms), an administrator sets:

1. `Settings.llmValueSharingMax = SAMPLE_VALUES`;
2. the project's `llmValueSharing = SAMPLE_VALUES`;
3. optionally `Settings.llmSensitiveColumns = NONE` - no sensitive column at all (empty keeps the built-in list);
4. if the model is reached over plain HTTP on the internal network, its host in `-Debx.factory.trustedLlmHosts=<host>[,<host>]`
   (otherwise HTTPS, or a model on this machine, is required).

The samples stay capped (they are there to show patterns, and prompts have a size); the whole table is never sent.

## What does not change

Column names, types and counts always go: they are what the model reasons on. The model's own answers and tool results
are stored in `AgentRun` / `AgentStep`, readable by administrators only (security review, 2026-09-24). The provider is
reached over HTTPS only (or a model on this machine).

## The governed agents (closed by default; added 2026-11-12, read from the code of 1.4.1)

The agents (`-Debx.factory.agents.enabled=true`, `docs/AGENTS.md`) are CLOSED by default: while closed they send nothing to any model. When an administrator opens them:

- **To whom.** The same language model connection as the other passes: the one chosen in the settings (`AgentRuntime.chosenConnection`), reached through the `llm.chat` of the Connectors contract (Connectors sends with the connection's own client and stored key; this module never reads the key) or, as the fallback, directly with the
  connection read here (`AgentRoute`). The provider is whoever that connection points to; the rules of "An internal, confidential model" above apply unchanged (HTTPS, or a trusted host).
- **What.** For each run: the agent's `purpose` and `instructions` (typed by a designer), the project's data policy level, and for each item the agent has to decide:
  - the quality agent (`QualityFixAgent`): the flagged field's name and type, the validation message and severity of the finding, the field's value and up to eight other fields of the same master record;
  - the data steward agent (`StewardMatchAgent`): the two suspect records, with the verdict and the values of each compared field.
  A value goes through `AgentPrompts.share`: a column matched by `Settings.llmSensitiveColumns` always shows `*` when filled; under `NONE` every filled value shows `*`; under `SHAPES_ONLY` (default) its shape (letters to `a`/`A`, digits to `9`); under `SAMPLE_VALUES` the value itself, cut at 200 characters.
- **What is NOT covered by the level.** The validation message of a finding (it can quote the value it rejects) is sent whatever the level (finding F3-05, open): do not open the agents on a project whose validation messages quote sensitive values. Column names, types and the agent's instructions always go.
- **For whom.** An item is shown to the model only if the person the agent acts for may read the field and the record in EBX (hidden items are counted, not sent).
- **What is stored.** The model's turns and tool results are in `AgentRun` / `AgentStep` (administrators only) and the proposals in `AgentProposal` (values hidden from anyone who cannot decide). The proposal lines copy values from master records: that is why a backup leaves those fields out while the agents are closed (docs/PROJECT-BACKUP.md), and why the purge and the project deletion remove them.

## Where a project sets it: wizard step 1 (2026-09-28)

Until this date the two project fields (`Project.llmValueSharing`, `Project.llmSampleSize`) could only be edited on
the Project record form. Step 1 of the project wizard (`WizardPage.renderLlmSharingCards`, shown both when a project
is created and when an existing one is re-opened on step 1) now carries a "What may reach the language model" control,
three radio cards in the same pattern as the staging-mode cards: **Nothing from the data** (`NONE`, added to the
project's enumeration by `tools/build_xsd.py` this day - the data policy already parsed it), **Shapes only**
(`SHAPES_ONLY`, default) and **Sample values** (`SAMPLE_VALUES`). The sample size (1 to 50, default 20) shows only
under Sample values. A plain-language line under the cards states the consequence of the checked level; for Sample
values it names the size and the configured connector's label (`WizardSnapshot.llmConnectorLabel`). Both fields are
posted (`mdmfLlmSharingRadio`, `mdmfLlmSampleSize`) and saved by the existing `SAVE_PROJECT` action
(`WizardData.saveProject`, create and update alike).

The instance cap is never silent: a card above `Settings.llmValueSharingMax` is disabled with a "Capped by the
instance" chip, the checked card is always the effective level (`WizardRules.effectiveLlmLevel`), and when the
stored or posted choice is above the cap a warning notice says "Capped by the instance setting to <level>: this
project had asked for <level>...". Server-side, `WizardRules.projectProblems(draft, codes, own, cap)` refuses a level
above the cap (`wizard.error.llmSharingCapped`) rather than lowering it - a forged post lands on the same rule. The
disclosure lines of steps 3, 4 and 6 keep reading `ProjectInfo.llmDataPolicyLevel`, i.e. the effective level, so
they follow this choice. Pure tests: `WizardPageTest.llmSharing*`, `WizardRulesTest.llmLevels*` /
`projectProblemsRefuseALevelAboveTheInstanceCap` / `projectProblemsCheckTheSampleSizeOnlyForSampleValues`.
