package com.on.ebx.factory.wizard;

/**
 * JUnit-free: run the main, a failure throws. UX lot C2 (2026-10-14): how a finished job is shown, on the messages measured on UxCrmSf2 (2026-10-13).
 */
public final class JobOutcomeTest
{
	public static void main(final String[] pArgs)
	{
		JobOutcomeTest.theMeasuredLandingFailureIsNotGreen();
		JobOutcomeTest.plainSuccessStaysDone();
		JobOutcomeTest.configureAndRunRefusalsAreFlagged();
		JobOutcomeTest.otherStatusesAreUntouched();
		JobOutcomeTest.nothingToDoStillWins();
		JobOutcomeTest.guardedKinds();
		JobOutcomeTest.theStoredStatusIsTheTruth();
		System.out.println("JobOutcomeTest OK");
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static void theMeasuredLandingFailureIsNotGreen()
	{
		final String shown = JobOutcome.display("LANDING_RUN", "DONE", "Landing run finished with failures on: SALESFORCE, DEMOCRM.");
		JobOutcomeTest.check(JobOutcome.PARTIAL.equals(shown), "a DONE job whose sentence reports failures is PARTIAL, got " + shown);
		JobOutcomeTest.check(JobOutcome.legacyReportsFailures("LANDING_RESUME", "Landing run finished with failures on: SAP."), "any kind, same sentence");
		JobOutcomeTest.check("FAILED".equals(JobOutcome.display("LANDING_RUN", "FAILED", "Landing run finished with failures on: SALESFORCE, DEMOCRM.")), "a stored FAILED stays FAILED");
	}

	private static void plainSuccessStaysDone()
	{
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("LANDING_RUN", "DONE", "Landing run finished: 16 of 16 stages done.")), "the measured success");
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("STAGING_LOAD_RUN", "DONE", "Staging build finished.")), "staging");
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("MASTER_BUILD_RUN", "DONE", "Master build finished.")), "master");
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("MATCHING_RUN", "DONE", "2 entities run, 0 not applied, 0 refused.")), "0 refused is not a failure");
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("MATCHING_CONFIGURE", "DONE", "3 entities configured, 0 refused, 0 not accepted.")), "0 refused, configure");
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("X", "DONE", null)) && "DONE".equals(JobOutcome.display("X", "DONE", "")), "no message");
	}

	private static void configureAndRunRefusalsAreFlagged()
	{
		JobOutcomeTest.check(JobOutcome.PARTIAL.equals(JobOutcome.display("MATCHING_CONFIGURE", "DONE", "1 entity configured, 2 refused, 0 not accepted.")), "measured configure message");
		JobOutcomeTest.check(JobOutcome.PARTIAL.equals(JobOutcome.display("MATCHING_RUN", "DONE", "1 entity run, 0 not applied, 2 refused.")), "run with refusals");
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("MATCHING_RUN", "DONE", "1 entity run, 2 not applied, 0 refused.")), "the measured run: not applied is shown by the results rows, not a failure");
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("STAGING_BULK_DECIDE", "DONE", "Bulk: 3 merged, 2 refused.")), "'refused' of another kind is not read as a failure");
		JobOutcomeTest.check(JobOutcome.PARTIAL.equals(JobOutcome.display("MATCHING_RUN", "DONE", "1 entity run, 0 not applied, 12 refused.")), "two digits");
	}

	private static void otherStatusesAreUntouched()
	{
		for (final String status : new String[] { "FAILED", "CANCELLED", "INTERRUPTED", "QUEUED", "RUNNING", "" })
		{
			JobOutcomeTest.check(status.equals(JobOutcome.display("LANDING_RUN", status, "Landing run finished with failures on: A.")), status + " is shown as it is");
		}
		JobOutcomeTest.check("".equals(JobOutcome.display("X", null, "msg")), "null status");
	}

	private static void nothingToDoStillWins()
	{
		JobOutcomeTest.check(JobOutcome.NOTHING.equals(JobOutcome.display("LANDING_PLAN_RUN", "DONE", "Nothing to do: 0 table(s) to plan.")), "nothing to do");
	}

	/** UX lot D: a job stored DONE_WITH_FAILURES is shown amber whatever its sentence says; the sentence is only read for the DONE rows of before the lot. */
	private static void theStoredStatusIsTheTruth()
	{
		for (final String kind : new String[] { "LANDING_RUN", "MATCHING_CONFIGURE", "MATCHING_RUN", "ANY_KIND" })
		{
			JobOutcomeTest.check(JobOutcome.PARTIAL.equals(JobOutcome.display(kind, "DONE_WITH_FAILURES", "2 of 4 tables were not loaded (A, B).")), kind + ": stored with failures is amber");
			JobOutcomeTest.check(JobOutcome.PARTIAL.equals(JobOutcome.display(kind, PartialFailures.STATUS, null)), kind + ": no sentence needed");
		}
		JobOutcomeTest.check(JobOutcome.completed("DONE") && JobOutcome.completed("DONE_WITH_FAILURES") && !JobOutcome.completed("FAILED") && !JobOutcome.completed("CANCELLED")
			&& !JobOutcome.completed(null), "completed = DONE or DONE_WITH_FAILURES");
		// the rows written before the lot: DONE + the old sentence, still amber for history
		JobOutcomeTest.check(JobOutcome.PARTIAL.equals(JobOutcome.display("LANDING_RUN", "DONE", "Landing run finished with failures on: SALESFORCE.")), "history: old landing row");
		JobOutcomeTest.check(JobOutcome.PARTIAL.equals(JobOutcome.display("MATCHING_CONFIGURE", "DONE", "1 entity configured, 2 refused, 0 not accepted.")), "history: old configure row");
		// a plain success of today is not read for failures any more than before
		JobOutcomeTest.check("DONE".equals(JobOutcome.display("LANDING_RUN", "DONE", "Landing run finished: 16 of 16 stages done.")), "a success stays DONE");
	}

	private static void guardedKinds()
	{
		for (final String kind : new String[] { "STAGING_LOAD_RUN", "MASTER_BUILD_RUN", "MATCHING_RUN", "MATCHING_CONFIGURE" })
		{
			JobOutcomeTest.check(JobOutcome.stepGuarded(kind), kind + " ends FAILED with its step");
		}
		JobOutcomeTest.check(!JobOutcome.stepGuarded("LANDING_RUN") && !JobOutcome.stepGuarded(null) && !JobOutcome.stepGuarded("SOURCES_RUN"),
			"the landing is judged by its sources, the others are not step-backed this way");
	}
}
