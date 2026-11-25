# UX lot U6 - Mapping matrix review: one counter, grouped suggestions, one gesture

Branch `feature/ux-matrix-queue` (from `main` a4a74dc0), 2026-10-13. Part of the wizard UX plan
(`ux-factory-wizard-2026-10-13/RAPPORT.md` sections 2.8, 3.3, 3.4; screenshots `img/29`..`33`). **Coded, tests green, NOT deployed, NOT
seen running.** Nothing here touches the wizard page, its js or css; the matrix screen (`mappingmatrix/*`, `ebx.ui.MappingMatrixUserService`)
and its message keys (`mappingMatrix.*`) only, plus one small addition to `guard.Guard` / `ebx.GuardReader` (see "Shared files").

## What was measured (UxCrmSf, 3 entities, 2 sources)

- The header said "4 points need your attention" while the queue said "20 items need you"; after "Accept the 82 proposals at 60 % or above" the
  queue said 16 and the header still 4; at the end the header said "1 point" next to "Nothing flagged is left". Cause: the header counted every
  FLAGGED FIELD of the project whatever its decision (`MatrixCompute.summarize` -> `attentionPoints`), the footer counted `stillToDecide`.
- Of the 16 leftovers, 8 were suggested look-ups through external services (geocode on `Adresse.street` x3 with three providers, postalCode,
  city, country, `Personne.city`, a search "not ready because its required authentication secret is missing"), each decided one by one with a
  server round trip, with the same weight as a flagged field and a third state ("Set aside").
- Five accept scopes on one screen; "Only what's left to do" not the default after a bulk accept; "Load the staging" linked `ui/advanced/...`.

## 1. One counter

`mappingmatrix.MatrixCounter` (new, pure, `MatrixCounterTest`): `Tally` = `needYou` (items the bulk button does not take and that are not
optional) / `withButton` (items it takes) / `optionalSuggestions` / `optionalFields` / `flaggedFields`. It is computed once per render, in
`MatrixHtml.render`, from the two lists the panel is drawn from (`MatrixCompute.stillToDecide`, `MatrixCompute.optionalServices`), and handed
to the header (`renderGuidance`), the footer (`renderFooter`) and the panel (`renderQueuePanel`). Header "N points need your attention" and
footer "N items need you" are therefore the same N (`needYou`); the tab counters add up to `stillToDecide()`.

- `Headline`: `NEED_YOU` ("N points need your attention"), `ONLY_BUTTON` (nothing needs you, N go with the button: "Nothing needs your
  attention; N items go with the button."), `NOTHING_LEFT` ("Nothing left to decide."). The footer says "Nothing left to decide. You can
  load the staging." with no count button; "Load the staging" is a real link.
- Header box "1 - Decide the flagged points" shows `flaggedFields`: distinct fields still waiting AND flagged (a decided flagged field no
  longer counts - the old figure kept counting it). At zero it reads a tick and "No flagged point left". Box "2 - Accept the rest in one
  click" reads a tick and "Done: nothing left to accept" when `allDone()` (needYou + withButton = 0); box 3 then says "Ready: load the staging below".

## 2. Look-up suggestions grouped per field, outside the count

An `EXTERNAL_SERVICE` guard still `PROPOSED` is no longer a `StillItem` (`MatrixCompute.isOptionalService`): `stillToDecide` and
`itemsStillToDecide` exclude it, so it never counts in "need you", the tab counters, the walk-through (Previous / Next), the row filter or
`readyToLoad`. `MatrixCompute.optionalServices(project)` returns one `ServiceGroup` per target field (per business object for a suggestion
with no field), reading order. The panel gets an "Optional suggestions" section after the list: ONE folded `<details>` line per group - "4
services could check Adresse.street", "2 of 4 ready to use", **Set aside all** and **Reject all** on the line - and inside the fold one line
per candidate: `provider . operation`, readiness ("ready, free", "ready, paid", "needs set-up: <why>", price words from `pricing`), Set aside and
Reject. A line is a `mdmmSItem`, not a `mdmmQItem` (outside the walk and the counts); clicking it still opens its chip in the grid.
The footer carries "+ N optional suggestions" in a button of its own (it opens the panel, also when nothing else is left).

Service side: new actions `SET_ASIDE_GUARDS` (one `WizardData.setCapabilitiesAside` write for the whole list, the wizard card's own write) and
`REJECT_GUARDS` (each id through `ProposalReviews.decide`), ids posted comma-joined in `mdmmRowId` (at most 100 taken). The per-line buttons
reuse `SET_ASIDE_GUARD` / `REJECT_GUARD`. Notices: "N suggestions set aside, M skipped." / "N suggestions rejected, M skipped.".

## 3. "Only what's left to do" as the default

`MatrixCompute.defaultOnlyTodo(project, itemsLeft)`: ON when at least one entity / field / source link / field link is no longer `PROPOSED`
(`hasDecidedProposal`) AND something is left (an empty grid would be a blank page). `MappingMatrixUserService` applies it on the first display
(no posted `mdmmOnlyTodo` yet) and forces it ON on the render that answers a bulk accept (`BULK_PROJECT` / `BULK_ENTITY` / `BULK_FEED`). The
person's own choice is remembered by the page: `MappingMatrixUserService.todoMemoryScript` opens the wave 13 store of the login
(`mdmfStore.forUser(login).local`, key `matrixTodo:<project code>`, i.e. `mdmf:<login>:matrixTodo:<project>` in the browser), `window.mdmmTodoRemember(on)`
is called by the toggle, a remembered value is applied at load (only when something is left), and a bulk accept writes ON back. A visual
preference, no business data; listed in `docs/OPERATIONS.md` 4.3.

## 4. Accept scopes

Kept: the global button at the bottom and the per-object button, each with ONE line under it saying what it covers
(`mappingMatrix.scope.project` / `.entity`: "Takes, in Personne only, each proposal at 60 % or above that is not flagged."). The per-source-link
"Accept N (>= 60%)" is now a small link-style secondary action of the column header (`mdmmBtnXs mdmmBtnLink`, scope in its tooltip,
`mappingMatrix.scope.feed`), never a primary button. Not changed: the entity / source link / field decisions (Accept the business object, Accept
the source link, per row) - those are decisions of ONE row, not scopes.

## 5. Links

The matrix script rewrites every `a[href*="/ui/advanced/data/"]` of the screen to `/ui/custom/<perspective>/data/` AT LOAD when the top window
is inside a perspective (the same rewrite the explorer and the cockpit do on their static links; `PROJECT-EXPLORER.md` "The three link forms"):
"Load the staging", "Back to the wizard", Matrix | Designer. Before, the rewrite only happened in a click-time listener, so the DOM held the
`ui/advanced` form (what was measured). Links added later (the inspector's Designer link) stay on the click-time listener.

## Shared files touched (minimal)

- `guard/Guard.java`: four optional fields (`serviceConnector`, `serviceReady`, `serviceReadinessDetail`, `servicePricing`) and a second
  constructor; the 15-argument one delegates, nothing else changes. `ebx/GuardReader.java`: the capability reader fills them for the
  `EXTERNAL_SERVICE` nature (`connectorId`, `ready`, `readinessDetail`, `pricing`). Without them the queue could not say "ready, free".
- `docs/OPERATIONS.md` 4.3: one table row for `matrixTodo`.

## Verified by test

`MatrixCounterTest` (new): the tally on the PersVol9-like and a services-only project; flagged fields only while waiting; header number ==
footer number == tally == sum of tab counters on four fixtures; zero state (header, footer, three boxes, load link, French); the folded group
(count, ids, readiness words, escaped detail, set-aside candidate absent); optional suggestions never keep the staging closed; default rule;
scope lines and the secondary per-link action; the load-time link rewrite. `MappingMatrixUserServiceTest` (new): id list split/cap, the
remember script (store, key, no read after a bulk). Updated: `MatrixComputeTest`, `MatrixHtmlTest`, `MatrixQueueFixtureTest` (the PersVol9-like
fixture now leaves 5 items, was 7, and 2 optional suggestions). Harness: `all green`.

## NOT done / not verified

- Nothing was run in a browser or against EBX: the `<details>` fold, the buttons inside its `<summary>` (they must not toggle it), the sticky
  dock with the taller panel, the stored-preference script and the load-time rewrite are code only.
- `ProposalReviews.decide(EXTERNAL_SERVICE, id, REJECTED)` for "Reject all" is the existing single-reject path; not run live.
- The matrix still offers per-source-link "Accept the source link" as a primary button in the column header (a decision, not a scope).
- French wording of the new strings was written without a native review.
- No change to the user guide or the wizard's own step 4 counters ("Proposal ready - 40 fields" vs "Your decisions - 31 fields": other lot).

## What the coordinator must check live (compare with `img/29`..`33`)

1. Open the matrix of UxCrmSf on a fresh proposal: header "N points..." and footer "N items need you" read the SAME N; accept the global
   bulk: both change together (compare to the 20 / 4 mismatch of `img/29`, 16 / 4 of `img/30`).
2. After the bulk the grid shows only rows with something left ("Only what's left to do" on); toggle it off, reload: it stays off for this
   login and project; another login or project is unaffected.
3. The 8 look-up suggestions show as ~5 folded lines under "Optional suggestions" ("N services could check Adresse.street" with "ready, free" /
   "needs set-up..."); "Set aside all" / "Reject all" act on the field's candidates; the footer shows "+ N optional suggestions"; none of them
   appears in "need you", in Previous / Next, or in a tab counter; "Load the staging" opens with only optional suggestions left.
4. At the end header and footer both read "Nothing left to decide", boxes 1 and 2 show ticks (compare `img/33`: "1 point" next to "Nothing flagged is left").
5. One line under the bottom button and under the object's button; the per-source-link button is a small underlined link.
6. Inspect `a[href]` of "Load the staging" / "Back to the wizard" inside the MdmFactory perspective: `/ebx-ui/ui/custom/MdmFactory/data/...`.
7. Buttons inside a `<summary>`: clicking "Set aside all" must act without also folding / unfolding the line.
