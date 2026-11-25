# UX lot C3 - step 1 above the fold, step 4 review figure (2026-10-14)

Source: two defects seen live on 2026-10-13 after the UX lots were deployed (`ux-factory-wizard-2026-10-13/AVANT-APRES.html`, "Ce que le rejeu a montre en plus" points 3 and 7;
screenshots `img-after/a02`, `a02b`, `a21b`, `a22`). Branch `fix/ux-c3-steps-1-4` from main b5c71760. Status: coded, tests green, NOT deployed, NOT seen running. No XSD change.

## 1. Step 1 - "Create the project" above the fold

Measured live before: page 1 782 px high, button at about 1 010 px at 1440x900 (EBX header 100 px). What changed (`WizardPage.renderStep1`, `renderImplementationStyles`,
`renderStyleCard`, `wizard.css` end of file):

- The intro is one line (`wizard.step1.lead`: "Name the project and say where the master data lives; the next steps do the rest."), the code hint is one line
  (`wizard.field.code.hint.proposed`: "Proposed from the label. Fixed once the project is created.").
- Label and code stay on one row; the **description is folded** under "Add a description (optional)" (`details.mdmf-step1-desc`): it opens by itself when it holds a text
  (project already created) or a refused value, and its textarea keeps its name, so it posts folded or not.
- The two style cards are one compact row: title, "Most common" chip, headline, "Details". **The drawing and the axis line "In the source applications -> In the MDM hub"
  now sit inside each card's Details** (order: axis, drawing, facts, when to choose). The axis went from a block of its own above the cards to the Details of each card
  (it belongs to the drawing it explains); "Other styles, coming later" stays a single line under the cards.
- The sentence "The wizard will build: landing, then staging..." (`.mdmf-style-consequence`, still computed from the selected style) is no longer a block above the button: it is
  the second line of the text beside the button, with the existing "Nothing is loaded yet..." line (once the project exists: beside "Continue to Sources").
- The vertical gap between the blocks of step 1 is 12 px (was 20).

Measured on a static render of the page (empty project, `WizardPage.render` + factory/review/wizard.css in headless Chrome, window 1440x900, a 100 px bar standing for the EBX
header): button top **845 px before, 690 px after** (page 1 265 -> 1 010 px). The live page was 65 px lower than this static render before the lot (1 010 vs 945: EBX's own
chrome and the real width), so the live estimate is about 755 px: under the 780 px target but not by much. **To be measured live.** If it does not fit: the next cuts are the
code hint line and the "Other styles, coming later" line.

## 2. Step 4 - "Review the proposal" and the figure the matrix shows

Measured live before: the button read "Review 116 points" (`Step4Counts.waiting()`, every proposal waiting) while the matrix header said "17 points need your attention"
(`MatrixCounter`: what a person must decide; the rest goes with the bulk button).

- The button now reads **"Review the proposal"** (`wizard.step4.primary.review.button`) and opens the matrix as before (without a matrix link it points at the cards).
- Under it, one bold line (`b.mdmf-review-note`): **"17 points need your attention, the other 99 go with one click"** (`...review.note.some.one/.other`; "N points need your
  attention" when nothing else waits: `...note.only.*`); when nothing needs attention: **"Nothing flagged: one click accepts everything"** (`...note.none`). French: "N points
  demandent votre attention, les M autres passent en un clic", "Rien n'est signale : un clic accepte tout".
- **The figure is the matrix's own**: `ProjectWizardUserService.model()` reads the project with `MappingMatrixReader.read` and takes `MatrixCounter.needYou(project)` (new, pure,
  = `MatrixCounter.of(project, DEFAULT_BULK_THRESHOLD).needYou`, i.e. the matrix header's N), into `WizardPage.Model.matrixNeedYou`. Only read when step 4 is in review and a
  matrix link exists; any failure leaves -1 and the button stands alone (no line). The "other M" = `Step4Counts.waiting() - N`, never negative (`WizardRules.step4ReviewNote`,
  pure): the matrix also files items the wizard's tally counts elsewhere, so when they differ the matrix figure wins and the rest is clamped at 0.
- Cost: one extra read of the matrix model on a render of a project in review (the same read the matrix does at every display, without samples). Not measured on a large project.

## Verified by test

`WizardC3StepsTest` (new): step 1 proxy (visible block elements before the button with closed folds not counted: 32 on main before the lot, 28 after; no `<svg>`, axis,
textarea or consequence above the button), the folds (description closed on a new project, open with a stored text or a refused value, the textarea still posts; Details holds axis,
drawing, facts in that order; one row of cards; controls keep their names), the consequence still there after the project exists; the pure review rule (116/17 -> 17 + 99, nothing
flagged, unknown, clamp, singular, French); **the UxCrmSf2 shape** (17 flagged links on accepted fields + 99 waiting fields = 116) gives `MatrixCounter.needYou == 17`, the matrix
header text "17 points need your attention", and the wizard sentence "17 points need your attention, the other 99 go with one click"; step 4 renders the button, the line, the
"nothing flagged" line and no line when unknown. Updated: `WizardPrimaryButtonsTest` (the button no longer carries a count). `WizardPageWeightTest` green.

## Not done / not verified

- Not seen in EBX: heights above are from a static render, not from the live frame; the real figure depends on the live width and on EBX chrome.
- The proxy test is coarse on purpose (it does not see pixels); the pixel measure is the headless-Chrome one above, not repeated by the harness.
- Step 4 re-reads the matrix on each render while in review; no cache. If that costs on a large project, cache by the staging tables' last modification date.
- The matrix header counts items per link and field (a flagged field with a flagged link counts twice); the figure is the matrix's, whatever the wizard's own tally.

## What the coordinator must check live

1. Step 1, new project, 1440x900, no scroll: "Create the project" visible (compare `a02`, `a02b`); "Add a description" opens a textarea, text typed there is saved with the project;
   each card's Details shows the axis line, the drawing, the facts; the sentence "The wizard will build..." beside the button; "Other styles, coming later" opens to the two others.
2. After creating: the description fold is open when a description was given.
3. Step 4 after a proposal (compare `a21b`, `a22`): "Review the proposal" and, under it, "N points need your attention, the other M go with one click" where N equals the matrix
   header's number and N + M equals "N proposals waiting for review"; after "Accept all" in the matrix and return: "Nothing flagged..." or the shorter line.
4. A project in review whose matrix cannot be read: the button alone, no line.
