# UX lot U4 - one primary button per step (2026-10-13)

Source: the coordinator's replay of the wizard on project `UxCrmSf` (`ux-factory-wizard-2026-10-13/RAPPORT.md` sections 2.2, 2.7, 2.9, 3.4; screenshots
img/02b, 03, 24b, 28b, 34b, 37b). Branch `feature/ux-primary-buttons`, from main e232c9b4 (wave A merged). Status: coded, tests green, NOT deployed, NOT seen running.
No XSD change.

## 1. Step 1 - the button above the fold

- The label comes first, the code second and is **proposed from the label as the person types** (`WizardRules.projectCodeFromLabel`, mirrored in `wizard.js`,
  block "project code from the label"; checked equal on 13 labels, accents, digits, 50-character labels). Once the person edits the code the label stops
  overwriting it; the code is a read-only field once the project exists (unchanged), and then the label no longer drives it.
- Style cards: the two real styles (Consolidation, Centralisation) are two compact cards side by side (diagram capped at 96 px, title, headline); each card's
  facts and "when to choose it" are folded in a `<details>` inside it (a `<details>` inside the `<label>` does not pick the radio). Registry and Coexistence
  ("Coming later") sit behind one small line "Other styles, coming later". The axis intro paragraph is gone, the axis line and its two captions stay.
- "Create the project" follows the cards. "Staging mode" and "What may reach the language model" are under "Advanced options" **below** the button, closed; one
  sentence under the button gives what applies if nothing is touched ("Unless you change it under Advanced options - staging mode: no target yet...; what may
  reach the language model: **Shapes only**."; the level follows the checked radio, `wizard.js`). The fold opens by itself on a refused value of one of its fields
  or when the stored choice is above the instance cap (`WizardRules.step1AdvancedOpen`). Controls keep their names/ids, so they post folded or not.
- Once the project exists the primary button is "Continue to Sources" (`GOTO_STEP`, the step after the project in the style's own list) and "Save" is secondary.
- The bottom **Next is hidden until the project exists** (`WizardRules.nextButtonVisible`).

## 2. Every step - exactly one primary button

- **Next is a plain (not green) button on every step**: the step's call to action is the green one (`renderNav`). Before: a green Next beside the step's own green button.
- **Item-level Accept / Apply of the "decisions left" card** are outlined green (`mdmf-btn-accept`), not solid: a card of 20 items used to show 20 green buttons.
  The Advanced buttons of step 4 (`STAGING_SUGGEST`, `STAGING_GENERATE`, `STAGING_LOAD`) are plain as well.
- **"Clean this step and the following ones"** (`renderStepReset`) is now the last thing of the step (steps 3, 4, 5, 6: the call moved to the end of each `render`,
  one line each, step 6 included), `mdmf-btn-danger` with a red outline and a one-line warning next to it (`wizard.stepReset.warning`). The confirmation panel that
  opens in place is unchanged.

## 3. Step 4 - the state decides (`WizardRules.step4Primary`, pure, `WizardPrimaryButtonsTest`)

| State | What is on the page |
|---|---|
| `PROPOSE` (nothing proposed, or every object rejected) | the intro card, then the primary block: **Propose the staging**; the three checkboxes folded under "Options"; one sentence ("When it ends you review the proposal here; Build and load appears once the review is done"); if everything was rejected, a line saying so |
| `RUNNING` | the live list (U3), nothing else of the primary block |
| `REVIEW` (Step4Counts.waiting() > 0) | **Review N points** (opens the Mapping matrix, `mdmf-feed-matrix-link`; without a matrix link it points at the per-object cards and their fold opens), Designer link secondary, once the staging was loaded a plain "Continue to the master"; sentence "After the review, Build and load appears here." Under "Advanced": "Build and load with what is accepted" (plain, with the existing "what is undecided stays out" note), the recompute-every-row option (after a first build) and "Run again" (the proposal form, secondary) |
| `BUILD` (nothing left, an accepted object, staging not loaded) | **Build and load** (green), Matrix and Designer links secondary, the build's one-sentence description; if the build is refused (not an administrator) the button is disabled with ONE reason |
| `LOADING` | the live panel of the build, no button of the step |
| `CONTINUE` (nothing left, staging loaded) | **Continue to the master** (green); "Build again", Matrix, Designer secondary; the sentence names the derivations still to apply when there are some |

The number in "Review N points" is `Step4Counts.waiting()` - the same number as the "N proposals waiting for review" line and the "Your decisions" card (U3). It is not the
matrix's own counter (U6 counts what the matrix queue holds, which leaves out the optional service suggestions): both should be read on the live project before
the numbers are called equal.

Blocks that are no longer written until their moment: the "Fill the shared structure" panel (title, lead, greyed button and its two excuse lines, now the primary block
in `BUILD`), the grid of the last build (only when a build ran), "Continue to the master" with its blocked reason (now only a button, in `CONTINUE`, or secondary in `REVIEW`
once loaded).

**Apply**: an accepted derivation is applied only once the staging model exists (`WizardRules.stagingExists`, `STAGING_DATASET` done). Before that the card shows no
Apply button and says "Will be applied after the first build" (lead `wizard.step4.still.toApply.leadBeforeBuild`); the parameter inputs and "Save parameters" stay.

Moved, content untouched (U3's blocks): the "Proposal ready for review" counts block (it lost its two review buttons, which are in the primary block now) and the
"decisions left" card come *under* the primary block instead of above the build block; the card's "toApply" group changed as described. `Step4Counts`, `RecentTasks`,
`renderJobsBanner` and the Summary were not touched.

## 4. The step rail

A closed step's `<li>` carries `title`, `data-mdmf-blocked-note` and `tabindex=0`; its (disabled) button takes no pointer event (`wizard.css`), so the hover title and the
click land on the `<li>`; `wizard.js` writes the reason in the one-line note under the rail (`#mdmfRailNote`, `role=status`) on click or Enter/Space, and hides it
when a step opens. `WizardRules.railBlockedNote(StepState)` is the pure rule (same key as the hover). The reason is the existing `wizard.blocked.*` text.

## What was verified by test

`WizardPrimaryButtonsTest` (new): project code cases; the step 4 rule (11 combinations and from a snapshot, SUPERSEDED excluded); rail and Next rules; step 1 before/after
the project (one `mdmf-btn-primary`, order label/code, the two cards vs the folded ones above the button, Advanced below the button and closed, sentence, Next hidden and never green;
fold opens on a refused value or a cap); step 4 in each of the six states (exactly one visible primary, its label and target, secondary links, sentences, no greyed button, no excuse line,
one set of checkbox ids); the Apply gating; the reset button last in steps 3-6 with its warning; the rail's note. Existing tests updated: `WizardPageTest` (blocked `<li>` has
attributes, the build title panel is gone, the early build, the card position, `derivationSnapshot` has a staging), `AccelPagesTest` (blocked `<li>`). The JS code proposal was
run under JavaScriptCore against the Java method on 13 labels (equal). Harness: see PLAN.md.

## Not done / not verified

- **Not measured: that the button is above the fold at 1440x900.** The estimate from the screenshot geometry (topbar and title about 270 px, fields card about 200 px with a two-row
  description, axis 40 px, cards about 230 px, consequence line about 40 px, button block about 90 px) is about 870-900 px of content under the 100 px header: tight. If it does not
  fit, the next cuts are the consequence sentence under the cards, or the description field on the same row as the label and the code.
- The other steps' primary blocks (2, 3, 5, 6, 7) were not audited for "exactly one green button": only Next (global) and step 4 were changed. Step 3's "Continue to staging", step 5's
  and step 6's own buttons are as the other lots left them.
- Keyboard: a closed step is reachable by Tab and answers Enter/Space; the disabled "Next" has no click note (only the rail has one, as the brief asks).
- The Advanced per-object cards of step 4 keep their own Accept buttons (green, folded); the items in the "decisions left" card are outlined now.
- Unsaved edits of step 1's fields are not saved by "Continue to Sources" (same as the old Next): "Save" is one click away, secondary.

## What the coordinator must check live (screenshots to compare: img/02b, 03, 24b, 28b, 34b, 37b)

1. Step 1 at 1440x900, no scroll: label, code (typing "Ux Crm Sf" fills `UxCrmSf`; editing the code stops it following), description, two cards, "Create the project" visible; "Other styles,
   coming later" opens to Registry/Coexistence; "Advanced options" closed with the one-sentence defaults, opens to the two controls, the sentence follows the language-model radio; "Next" absent.
   After creating: the button reads "Continue to Sources", "Save" is plain, "Next" is there and plain.
2. A cards "Details" click toggles the facts and does not change the selected card.
3. Step 4 before the proposal: only "Propose the staging" is green, "Options" holds the checkboxes, no "Fill the shared structure" block. While it runs: the live list. After: "Review N points"
   (N equals "N proposals waiting for review"), the Matrix opens on the first object; Designer secondary.
4. After deciding everything in the matrix and returning: "Build and load" is the green button, Matrix/Designer secondary, the "decisions left" card (if any) shows "Will be applied after the first
   build" and no Apply; after the build the Apply buttons appear (outlined) and "Continue to the master" is green.
5. A project with points left after a build: "Review N points" green, "Continue to the master" plain.
6. "Clean this step and the following ones" at the bottom of steps 3-6, red outline, warning line; the confirmation still opens in place.
7. Rail: hover a closed step shows the reason (title), click shows the line under the rail, opening any step hides it; keyboard Tab/Enter on a closed step.
8. Weight: `WizardPageWeightTest` is green; look at step 1 and 4 page size on a real project.
