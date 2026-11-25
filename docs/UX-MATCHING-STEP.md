# UX lot U1 - Matching step (step 6 of the project wizard), 2026-10-13

Branch `feature/ux-nothing-to-do`, from `main` a4a74dc0. Source of the lot: `ux-factory-wizard-2026-10-13/RAPPORT.md` sections 2.11, 3.1, 3.4, 3.5
(project UxCrmSf, 3 accepted objects Organisation / Personne / Adresse, 81 rows). Status: coded, tests green, NOT deployed, NOT seen running.

## What was wrong (measured 2026-10-13)

- "Propose the matching plan" (rules) answered in 0 s "0 matching plans written, 0 refreshed, 3 entities skipped." and the page kept "No matching plan
  proposed yet." The reason (`MatchPlanRules`: no validated identifier) was in the job result's `skipped[]` and never shown; the way out was a secondary
  button nothing designated.
- The model pass said "refined for 3 entities" but wrote 2 plans: Organisation got no card, no line, no reason.
- A development note ("Validated detail screens ... review-match-plan mockups") sat in the side column.
- After accepting both plans "Configure at least one matching plan first." stayed next to an enabled Configure; after Configure "Accept at least one
  matching plan first." stayed next to an enabled Run; "Configure matching again to apply it" sat beside Save from the start.
- The results row for a failed object showed the log text of the add-on in red and a 400-word paragraph repeating "NOT filtered ... 9 times slower at 1
  million rows" on 81 rows; "0 pairs to decide" came without its scope.

## What was built

1. **One primary button, "Propose the matching plans"** (`MATCHING_SUGGEST`). The job (`SuggestRulesRun.MATCHING_HANDLER`) runs the rules, re-reads the
   project, and - when the requester holds DESIGN, a connector is configured, the gate of the model pass allows it and at least one accepted object has no
   plan - runs the language-model pass in the same job (same chaining as step 4, `SuggestRulesRun.STAGING_HANDLER`). `llmAllowed` travels in the job
   parameters like for the staging. "Refine with the language model" (`MATCHING_SUGGEST_LLM`) stays, under an "Advanced" disclosure inside the proposal card.
2. **Every accepted object gets a card.** `wizard.MatchingPlanCoverage` computes the gaps: accepted (or applied) objects that are not members of a canonical
   object and have no `MatchPlan` row (a rejected plan is a decision, not a gap). The "No plan for X" card says why in user words: rules ("no identifier with
   a check digit is accepted on it"), the model's own skip text (`notes[]` "X skipped: reason" of the `MATCHING_SUGGEST_LLM` step detail, which `WizardData`
   now reads for that one step), a refusal ("could not be stored: ..."), or "proposed nothing and gave no reason". Also what would unblock the rules, that the
   object stays out of matching until a plan is accepted, and "Ask the language model" when a connector is configured, the person may use it and the model
   was not asked yet. Cards are interleaved with the plan cards in the staging order; a yellow summary line above lists the objects without a plan.
   Gaps are shown once a pass ran (a plan exists, the model step exists, or "Recent tasks" lists a finished `MATCHING_SUGGEST_RULES`).
3. **`Nothing to do:` messages.** A matching-suggest job that writes no plan ends with a message starting `Nothing to do:` (constant
   `MatchingPlanCoverage.NOTHING_TO_DO`, deliberately not localized: it is the prefix the banner recognises; the French message keeps it) followed by one
   sentence per object and what to do next (ask the model / connect one). Same for the model-only job (`MatchingSuggestLlmRun`). When some plans were
   written, the usual sentence is followed by the objects still without a plan.
4. **Clean-up.** Side card "Validated detail screens" removed (keys deleted). One excuse line below the Run text, only while true: nothing accepted ->
   "Accept at least one matching plan first."; accepted, not configured -> "Configure first: ..."; some accepted after the last Configure -> "Some accepted
   plans are not configured yet..."; fully configured -> none. The "Configure matching again to apply it" hint beside Save only once a plan is applied.
   Volume warnings: `wizard.MatchingVolume` - inline from 50 000 rows (stored results of the entity when it has some, else the rows the project's landing
   tables loaded, an upper bound), otherwise under a "Details" disclosure: in the plan card the "AUTOMATIC MERGE IS OFF" note and the band lines "NOT filtered:
   ... cost / kept on purpose" move above the diagram into a fold; for a decided plan the candidate-search notice is folded. Shared review classes got
   opt-in overloads (`MatchPlanView.renderTree(input, fold)`, `renderCandidateSearchNotice(..., fold)`, `MatchPlanTreeDiagram.build(..., fold)`); the
   Proposal review screen still calls the old signatures and is unchanged.
5. **Results table.** `wizard.MatchingResultRow`: one sentence per object ("30 of 81 records compared, no pair to decide, nothing merged"; "Matching failed:
   the Match and Merge add-on stopped with an internal error. Details below."), columns Compared (pivots / total, read from the step's scope sentence, else
   from the stored results), Pairs to decide, Merged groups, Kept apart. The raw log text and the long explanations sit under a per-row "Details" fold; the
   candidate-search warning of the run is shown beside the sentence only on a big table. `wizard.js` mirrors the fold for the live polling (a failed or long
   message goes under Details).

## Tests

`MatchingPlanCoverageTest` (gaps, model notes/refusals read from the step detail, chain decision, `Nothing to do:` text, rules-ran detection),
`MatchingResultRowTest` (the measured messages), `MatchingVolumeTest` (threshold, row source), `WizardPageMatchingStepTest` (the page: cards for 3 objects
after the rules, the model's own words for Organisation, Ask button conditions, excuse lines through the three states, volume fold on 81 rows vs 1.2 M,
results row). Test harness line: see the report.

## Not done / limits

- No "No matching for this object" button on a gap card: that decision belongs to a plan row (`DECIDE_REJECT`), and an object without a plan is already out
  of the run. Creating a REJECTED stub plan was not tried.
- The rules' reason is always "no identifier with a check digit": it is the only reason `MatchPlanRules` skips an object for. A plan the rules built but
  `StoredJsonFit` refused ("refused[]") is not named by a rules gap card.
- "The rules ran" is read from "Recent tasks" (last 10 jobs) or a RULE plan: an old rule pass that scrolled out of the list and wrote nothing shows the
  empty notice again, which is still correct guidance (the button is right there).
- The row count for the volume rule of an entity without stored results is the project-wide landing total (an upper bound).
- The `Nothing to do:` prefix is English in both locales on purpose; the banner/Recent-tasks lot decides how it renders.
- `wizard.step6.live.col.*` keys of the live table are unchanged except the new `compared`; the JS fold mirrors the server rule without the sentence (the
  browser has no stored results).

## To check live (screenshots to compare: img/42b, 44b, 46b, 47, 48, 50b)

1. Fresh step 6 on a project with 3 accepted objects and a connector: ONE primary button "Propose the matching plans"; "Refine with the language model"
   only under Advanced. Click it: job in the banner; at the end the job message in Recent tasks, 2 plan cards plus a "No plan for Organisation" card with
   the model's text; no "Validated detail screens" card.
2. Same without a connector: after the click, 3 "No plan" cards with the rules' reason, job message `Nothing to do: ...` (amber once the banner lot is in).
3. Accept both plans: only "Configure first: ..." under the Run text, no "Configure at least one..." line. After Configure: no excuse; "Configure matching
   again to apply it" beside Save appears.
4. Plan cards on the 81-row project: "AUTOMATIC MERGE IS OFF" and "measured 9 times slower" under a "Details" fold above the diagram, not in the band.
5. Run matching: one sentence per object, columns Compared / Pairs / Merged / Kept apart; the failed object shows the "internal error" sentence, the raw
   `[MatchingManager]` text only after opening Details.
