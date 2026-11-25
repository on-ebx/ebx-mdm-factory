package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. A staging load whose every row was rejected is FAILED, as the master load already is; nothing else changes. */
public final class StagingLoadOutcomeTest
{
	public static void main(final String[] pArgs)
	{
		StagingLoadOutcomeTest.check("every row rejected: FAILED", "FAILED".equals(StagingLoader.resultStatus(false, 196, 196)));
		StagingLoadOutcomeTest.check("some rejected, the rest written: DONE", "DONE".equals(StagingLoader.resultStatus(false, 100, 40)));
		StagingLoadOutcomeTest.check("nothing read (scope new, nothing new): DONE", "DONE".equals(StagingLoader.resultStatus(false, 0, 0)));
		StagingLoadOutcomeTest.check("all unchanged (scope all, nothing to write): DONE", "DONE".equals(StagingLoader.resultStatus(false, 50, 0)));
		StagingLoadOutcomeTest.check("a stop wins over a failure the partial counts would suggest", "STOPPED".equals(StagingLoader.resultStatus(true, 10, 10)));
		StagingLoadOutcomeTest.check("a stop with good counts: STOPPED", "STOPPED".equals(StagingLoader.resultStatus(true, 10, 0)));
		// staging load rejects (2026-10-17): a child entity counts the rows it builds as read, so the verdict looks at what was written
		StagingLoadOutcomeTest.check("every root row rejected, their children rejected with them: FAILED", StagingLoader.isTotalFailure(280, 280, 0, 0, 0));
		StagingLoadOutcomeTest.check("every root row rejected, the children empty (read, neither written nor rejected): still FAILED", StagingLoader.isTotalFailure(280, 140, 0, 0, 0));
		StagingLoadOutcomeTest.check("roots written, their children rejected: not a failure (it used to read 100 of 100 rejected)", !StagingLoader.isTotalFailure(200, 100, 100, 0, 0));
		StagingLoadOutcomeTest.check("nothing written but some rows unchanged: not a failure", !StagingLoader.isTotalFailure(50, 2, 0, 48, 0));
		StagingLoadOutcomeTest.check("nothing read: not a failure", !StagingLoader.isTotalFailure(0, 0, 0, 0, 0));
		StagingLoadOutcomeTest.check("nothing rejected: not a failure", !StagingLoader.isTotalFailure(10, 0, 0, 0, 0));
		StagingLoadOutcomeTest.check("the verdict gives the status", "FAILED".equals(StagingLoader.resultStatus(false, true)) && "DONE".equals(StagingLoader.resultStatus(false, false))
			&& "STOPPED".equals(StagingLoader.resultStatus(true, true)));
		System.out.println("StagingLoadOutcomeTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
