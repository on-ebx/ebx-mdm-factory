package com.on.ebx.factory.ebx;

/**
 * JUnit-free, like the rest of this package's tests. Exercises the job-engine lot C3 (2026-09-24) pure helper
 * {@link MatchingRunRun#message} without a live repository.
 */
public final class MatchingRunRunTest
{
	public static void main(final String[] pArgs)
	{
		MatchingRunRunTest.normalRunReportsTheThreeCounts();
		MatchingRunRunTest.stoppedRunNamesTheReasonAndWhatWasNotStarted();
		MatchingRunRunTest.singularWordingForExactlyOneEntity();
		MatchingRunRunTest.stoppedRunWithAnUnconfirmedAddonStopSaysTheProcessIsStillRunning();
		MatchingRunRunTest.failedEntityMakesTheJobSentenceNameAndCountIt();
		MatchingRunRunTest.childObjectsWithoutAPlanAreNamedNotCountedAsNotApplied();

		System.out.println("MatchingRunRunTest OK");
	}

	/** Lot M2 (2026-10-17): a child that follows its parent is not "not applied". */
	private static void childObjectsWithoutAPlanAreNamedNotCountedAsNotApplied()
	{
		final String base = MatchingRunRun.message(false, null, 2, 0, 0, 0);
		MatchingRunRunTest.check("nothing to add: unchanged", base.equals(MatchingRunRun.withFollows(base, 0)));
		MatchingRunRunTest.check("one child", MatchingRunRun.withFollows(base, 1).equals(base + " 1 child object has no plan of its own: its records follow the matching of the parent."));
		MatchingRunRunTest.check("two children", MatchingRunRun.withFollows(base, 2).contains("2 child objects have no plan of their own: their records follow the matching of the parent.") && base.contains("0 not applied"));
	}

	/** Lot M4 (2026-10-03, defect 1): "stopped" must not pass for "the add-on stopped" when it did not confirm. */
	private static void stoppedRunWithAnUnconfirmedAddonStopSaysTheProcessIsStillRunning()
	{
		final String message = MatchingRunRun.message(true, "stopped by admin", 1, 0, 0, 1, 1);
		MatchingRunRunTest.check("warns that the add-on's process is still running: " + message,
			message.contains("the add-on's process is still running on 1 entity") && message.contains("cannot be stopped from here"));
		MatchingRunRunTest.check("no warning when every stop was confirmed",
			!MatchingRunRun.message(true, "stopped by admin", 1, 0, 0, 1, 0).contains("still running"));
		MatchingRunRunTest.check("no warning on a normal run", !MatchingRunRun.message(false, null, 1, 0, 0, 0, 0).contains("still running"));
	}

	/** Lot MT (2026-10-04, Vol1mF): "2 entities run, 0 not applied, 0 refused" was the sentence of a run whose Personne process FAILED. */
	private static void failedEntityMakesTheJobSentenceNameAndCountIt()
	{
		final java.util.List<String> failed = java.util.Collections.singletonList("Personne - [MatchingManager] The automatic merge process failed for the Marco-Luc group");
		final String message = MatchingRunRun.message(false, null, 1, 0, 0, 0, 0, failed);
		MatchingRunRunTest.check("counts and names the failed entity: " + message,
			message.startsWith("1 of 2 entities failed: Personne - [MatchingManager] The automatic merge process failed") && message.contains("1 entity run, 0 not applied, 0 refused."));
		MatchingRunRunTest.check("no failure: the older sentence, unchanged",
			"2 entities run, 0 not applied, 0 refused.".equals(MatchingRunRun.message(false, null, 2, 0, 0, 0, 0, java.util.Collections.<String>emptyList())));
		final String stopped = MatchingRunRun.message(true, "stopped by admin", 1, 0, 0, 1, 0, failed);
		MatchingRunRunTest.check("a stopped run stays a stop sentence, naming the failure after it: " + stopped,
			stopped.startsWith("Matching run stopped (stopped by admin)") && stopped.contains("had already failed: Personne -"));
	}

	private static void normalRunReportsTheThreeCounts()
	{
		final String message = MatchingRunRun.message(false, null, 4, 1, 0, 0);
		MatchingRunRunTest.check("plural wording, all three counts present", "4 entities run, 1 not applied, 0 refused.".equals(message));
	}

	private static void stoppedRunNamesTheReasonAndWhatWasNotStarted()
	{
		final String message = MatchingRunRun.message(true, "stopped by philippe", 1, 0, 0, 3);
		MatchingRunRunTest.check("stop reason and stoppedBefore count both present",
			message.contains("stopped by philippe") && message.contains("1 entity run before the stop") && message.contains("3 not started"));
	}

	private static void singularWordingForExactlyOneEntity()
	{
		MatchingRunRunTest.check("singular, normal run", "1 entity run, 0 not applied, 0 refused.".equals(MatchingRunRun.message(false, null, 1, 0, 0, 0)));
		MatchingRunRunTest.check("singular, stopped run",
			MatchingRunRun.message(true, "the time limit", 1, 0, 0, 0).contains("1 entity run before the stop"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new IllegalStateException("MatchingRunRunTest failed: " + pWhat);
		}
	}
}
