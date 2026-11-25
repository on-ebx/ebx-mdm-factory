# UX lot C2 - job outcomes and step 6 sequencing (2026-10-14)

Branch `fix/ux-c2-matching-jobs`, from `main` b5c71760 (every UX lot). Source: the coordinator's live replay of 2026-10-13 on project UxCrmSf2
(`ux-factory-wizard-2026-10-13/AVANT-APRES.html`, "Ce que le rejeu a montre en plus" points 4, 5, 6; screenshots `img-after/a17-step3-landing-done.png`,
`a38b-step6-results-full.png`). Status: coded, tests green, NOT deployed, NOT seen running.

## 1. A job that ended with failures is no longer a green "Done"

Measured: the `LANDING_RUN` step ended FAILED ("Landing run finished with failures on: SALESFORCE, DEMOCRM.") while the job row was DONE, so Recent tasks showed a
green "Done" and the end-of-task toast said "Preparing the landing: finished.". Cause: `LandingRun`'s handler returned the step's sentence; the queue stores DONE for
any normal return. The staging build, master build and matching run already throw (so their jobs end FAILED); the landing and the matching configuration did not.

Two layers, both pure and tested:

- **Stored status.** `LandingChainOutcome.allSourcesFailed(progress)` (every application of the grid has a failed cell). `LandingRun.HANDLER` and
  `RESUME_HANDLER` call `failWhenEveryApplicationFailed` after closing the step: when the step is FAILED, the run was not stopped and every application failed, the
  handler throws `FactoryJobQueue.ExpectedFailure(<the step's sentence>)` - the job ends FAILED (red "Failed" in Recent tasks, red "failed" toast), no stack trace in
  the log. This is the measured case (both applications failed). A generic guard in `FactoryJobQueue#runHandler` (`failedStepMessage`) also turns a DONE return
  into FAILED, with the step's message, for the kinds in `JobOutcome.STEP_GUARDED` (`STAGING_LOAD_RUN`, `MASTER_BUILD_RUN`, `MATCHING_RUN`, `MATCHING_CONFIGURE`)
  when their `PipelineStep` is FAILED: today their handlers already throw, so it is a safety net, not a behaviour change.
- **Displayed status.** `wizard/JobOutcome.display(kind, status, message)`: a DONE job whose sentence reports failures is shown as `PARTIAL` - amber "Finished with
  failures" (`wizard.jobs.status.PARTIAL`, row class `mdmf-recent-partial`, toast class `mdmf-toast-partial`, `wizard.jobnotice.partial`). Rules: the message contains
  "finished with failures" (the landing, when only SOME applications failed - the job stays DONE and loses nothing), or, for `MATCHING_CONFIGURE`/`MATCHING_RUN`, "N refused"
  with N > 0 ("1 entity configured, 2 refused, 0 not accepted." was also green). Also applies to rows stored before this lot, which cannot be corrected in the data.
  `jobnotice.js` mirrors the rule (`partial(job)`); `RecentTasks.row` uses it for the label and the CSS class. "Nothing to do" keeps winning on its own prefix.
- The staging/master/matching "FAILED" display needs nothing new: a FAILED job already renders red.

Not touched: a run whose step is DONE but whose message says "2 not applied" stays plain DONE (the results rows below now say which objects were not applied).

## 2. Step 6: Run waits for Configure, and an object not applied has its own row

- While a Configure job is QUEUED or RUNNING (`MatchingActivity` kind CONFIGURE, `isLive()` - the same source as the live panel), both Run buttons are disabled and
  the line under the Run text reads "Configuring N objects... Run is available when they are done." (`wizard.step6.run.configuring.one|other`, N = accepted and
  applied plans; `id="mdmfStep6Configuring"`; FR too). It replaces the usual "Some accepted plans are not configured yet" excuse for that time; the admin-only
  refusal is kept. When the job ends the page re-renders (jobs banner), the reason disappears, and the usual excuse line says what is left to configure.
  `WizardPage.step6ConfiguringCount(Model)` (package-visible, tested). The Configure/Run jobs share the queue resource MATCHING, so a Run clicked anyway would still run
  AFTER the Configure; the lock is for the person, so they do not launch a run on the half-configured state.
- After a finished Run, every ACCEPTED plan that has no entity step in the run (what the run called "not applied") is listed in the live table with "Not run" and
  "Not configured yet: Configure first." (`wizard.step6.result.notConfigured`, `wizard.step6.live.state.NOTRUN`), instead of being absent. After a Configure, no such rows.

## 3. "Golden records" block no longer prints the raw run text

`ProjectWizardUserService` used to build `"Last run: DONE, <raw step message>"` for `MatchResultsView.Input.lastRunText`. It now builds the same one-sentence summary as the
table row (`MatchingResultRow.of(...).sentence`, "Last run: 30 of 81 records compared, no pair to decide, nothing merged") and puts `MatchingResultRow.details` (the raw text,
including the candidate-search warning) in `Input.lastRunDetails`, rendered by `MatchResultsView` under the same "Details" fold (`mdmf-ma-details`). When the live table
right above shows that very run for the entity (kind RUN, finished, entity present) the paragraph is not written at all (it would repeat the row). Key
`wizard.step6.results.lastRun` is now `Last run: {0}` (was `{0}, {1}`), new `wizard.step6.results.lastRun.details`.

## Tests

`JobOutcomeTest` (new: the measured messages - landing failure, success sentences, `0 refused`, configure/run refusals, other statuses, guarded kinds),
`LandingChainOutcomeTest` (`allSourcesFailed`: both applications of the measured run, one of three, none), `RecentTasksTest` (amber "Finished with failures", red FAILED
with its sentence, green success, French label), `WizardPageMatchingStepTest` (Run disabled + "Configuring 3 objects..." while Configure runs/queued, back after, nothing when
all configured; two "Not configured yet" rows after a run of one object, one when another ran, none after a Configure; golden block: one sentence, raw text once under Details).

## Not done / limits

- `FactoryJobQueue#runHandler`'s guard (and the landing throw) are not unit-tested: they need an EBX repository; the pure classification they rest on is.
- A landing run where only SOME applications failed stays a DONE job (amber via the message). I did not add a stored PARTIAL status (the job table's status is a
  fixed enumeration; no XSD change in this lot).
- `wizard.js` was not changed: the live panel's poll only repaints entities it receives; the not-configured rows exist only in the server render (a finished run).
- The Run lock is client-side state at render time (a stale page can still click Run; the job then simply queues behind the Configure).

## Live checks for the coordinator (screenshots to compare: a17, a38b)

1. Force a landing where every application fails (e.g. both sources unreachable): Recent tasks shows red "Failed" with "Landing run finished with failures on: ...", toast
   "Preparing the landing: failed.". Fail only one application: amber "Finished with failures", amber toast.
2. Step 6, accept three plans, click Configure: Run (both buttons) disabled with "Configuring 3 objects..." until the job ends; then enabled, no reason.
3. Configure with 2 refused: its Recent-tasks row reads amber "Finished with failures".
4. Run with one plan applied and two only accepted: the results table lists the two others as "Not run - Not configured yet: Configure first.".
5. Golden records block: "Last run: 30 of 81 records compared, ..." and a "Details" fold holding the raw text; no "Xref.goldenKey" or 1-million-row warning visible without opening it,
   and no "Last run" paragraph at all right after a Run (the table above carries it).
