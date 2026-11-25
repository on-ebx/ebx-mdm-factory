package com.on.ebx.factory.master;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MasterLoadOutcomeTest
{
	public static void main(final String[] pArgs)
	{
		MasterLoadOutcomeTest.everyReadRowRejected_isATotalFailure();
		MasterLoadOutcomeTest.partialRejection_isNotATotalFailure();
		MasterLoadOutcomeTest.noRowsReadAtAll_isNotATotalFailure();
		MasterLoadOutcomeTest.everyRowSkippedMerged_isNotATotalFailure();
		MasterLoadOutcomeTest.everyRowWritten_isNotATotalFailure();
		System.out.println("MasterLoadOutcomeTest OK");
	}

	/** The incident's own numbers, `docs/MASTER-ZONE.md` &sect;10: 196 read, 196 rejected, 0 written. */
	private static void everyReadRowRejected_isATotalFailure()
	{
		MasterLoadOutcomeTest.check("196 read, 196 rejected: total failure", MasterLoadOutcome.isTotalFailure(196, 196));
	}

	private static void partialRejection_isNotATotalFailure()
	{
		MasterLoadOutcomeTest.check("100 read, 40 rejected, 60 written: not a total failure - stays DONE with counts, as before",
			!MasterLoadOutcome.isTotalFailure(100, 40));
	}

	private static void noRowsReadAtAll_isNotATotalFailure()
	{
		MasterLoadOutcomeTest.check("0 read: an empty run is not a failure", !MasterLoadOutcome.isTotalFailure(0, 0));
	}

	/**
	 * The case {@code written == 0} alone would have wrongly flagged: every row legitimately SKIP_MERGED (already
	 * merged into a golden elsewhere), so nothing is written and nothing is rejected either - a genuine success.
	 */
	private static void everyRowSkippedMerged_isNotATotalFailure()
	{
		MasterLoadOutcomeTest.check("50 read, 0 rejected (all skipped as already-merged): not a failure", !MasterLoadOutcome.isTotalFailure(50, 0));
	}

	private static void everyRowWritten_isNotATotalFailure()
	{
		MasterLoadOutcomeTest.check("196 read, 0 rejected: not a failure", !MasterLoadOutcome.isTotalFailure(196, 0));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
