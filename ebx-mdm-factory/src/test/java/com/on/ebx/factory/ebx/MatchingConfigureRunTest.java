package com.on.ebx.factory.ebx;

/**
 * JUnit-free, like the rest of this package's tests. Exercises the job-engine lot C3 (2026-09-24) pure helper
 * {@link MatchingConfigureRun#message} without a live repository.
 */
public final class MatchingConfigureRunTest
{
	public static void main(final String[] pArgs)
	{
		MatchingConfigureRunTest.normalRunReportsTheFourCounts();
		MatchingConfigureRunTest.stoppedRunNamesTheReasonAndWhatWasNotStarted();
		MatchingConfigureRunTest.singularWordingForExactlyOneEntity();
		MatchingConfigureRunTest.unchangedPlansAreCountedNotReadAsNotAccepted();
		MatchingConfigureRunTest.appliedPlanIsConfigurableAgain();
		MatchingConfigureRunTest.followsParentKeptAsIsAndPreviewLeftAreReportedNotCountedAsFailures();

		System.out.println("MatchingConfigureRunTest OK");
	}

	/** Lot M4 (2026-10-03, defect 2): the plans already up to date appear in the sentence. */
	private static void unchangedPlansAreCountedNotReadAsNotAccepted()
	{
		MatchingConfigureRunTest.check("unchanged clause shown when not zero",
			"0 entities configured, 2 unchanged (already up to date), 0 refused, 0 not accepted.".equals(MatchingConfigureRun.message(false, null, 0, 0, 0, 0, 2)));
		MatchingConfigureRunTest.check("older wording untouched when zero", "3 entities configured, 1 refused, 2 not accepted.".equals(MatchingConfigureRun.message(false, null, 3, 1, 2, 0, 0)));
	}

	/** Lot M2 (2026-10-17): the three notes a configure adds without turning them into failures. */
	private static void followsParentKeptAsIsAndPreviewLeftAreReportedNotCountedAsFailures()
	{
		final java.util.List<String> none = java.util.Collections.<String>emptyList();
		final String base = MatchingConfigureRun.message(false, null, 2, 0, 0, 0);
		MatchingConfigureRunTest.check("nothing to add: the sentence is unchanged", base.equals(MatchingConfigureRun.withNotes(base, none, none, none)));
		final String one = MatchingConfigureRun.withNotes(base, java.util.Collections.singletonList("Adresse follows Personne"), none, none);
		MatchingConfigureRunTest.check("one child: named, no plan of its own, and the failure count stays 0", one.startsWith("2 entities configured, 0 refused, 0 not accepted.")
			&& one.contains("1 child object has no plan of its own (Adresse follows Personne)") && one.contains("follow the parent"));
		final String two = MatchingConfigureRun.withNotes(base, java.util.Arrays.asList("Adresse follows Personne", "Tel follows Personne"), none, none);
		MatchingConfigureRunTest.check("two children: plural", two.contains("2 child objects have no plan of their own (Adresse follows Personne; Tel follows Personne)"));
		final String kept = MatchingConfigureRun.withNotes(base, none, java.util.Collections.singletonList("Adresse (child of Personne): own plan already configured, left as it is."), none);
		MatchingConfigureRunTest.check("a child's configured plan is reported as left as it is", kept.contains("left as it is") && kept.contains("0 refused"));
		final String left = MatchingConfigureRun.withNotes(base, none, none, java.util.Collections.singletonList(
			com.on.ebx.factory.master.ChildEntityMatching.previewLeftNote("Personne", "Personne__preview", "the table belongs to a business object")));
		MatchingConfigureRunTest.check("a preview that stays is named in the job message, with its reason", left.contains("Personne__preview") && left.contains("business object")
			&& left.contains("0 refused"));
		// lot M4 (2026-10-18): the inert policies written on the member tables of a business object are said, one sentence each, and are not failures
		final String member = com.on.ebx.factory.master.BusinessObjectMembers.writtenSentence("Adresse", "Personne",
			com.on.ebx.factory.master.BusinessObjectMembers.writesFor(new com.on.ebx.factory.master.BusinessObjectMembers.Member("/root/Adresse", none, none, null), "ONLY_DUPLICATES"), null);
		final String written = MatchingConfigureRun.withNotes(base, none, none, none, java.util.Collections.singletonList(member));
		MatchingConfigureRunTest.check("a member's policies are named in the job message, with the failure count unchanged", written.startsWith(base) && written.contains("Adresse (member of the business object of Personne)")
			&& written.contains("0 refused") && written.endsWith(member));
		MatchingConfigureRunTest.check("without member sentences the four-argument form is unchanged", base.equals(MatchingConfigureRun.withNotes(base, none, none, none, none)));
		// lot M6 (2026-10-18): without validation notes the five-argument form is unchanged (the note itself is covered by MatchingValidationTest)
		MatchingConfigureRunTest.check("without validation notes the five-argument form is unchanged", base.equals(MatchingConfigureRun.withNotes(base, none, none, none, none, none)));
	}

	/** Lot M4 (defect 2): ACCEPTED and APPLIED are configured, everything else is "not accepted". */
	private static void appliedPlanIsConfigurableAgain()
	{
		MatchingConfigureRunTest.check("ACCEPTED", MatchingConfigurator.isConfigurablePlanStatus("ACCEPTED"));
		MatchingConfigureRunTest.check("APPLIED (an edited requirements block must be re-read)", MatchingConfigurator.isConfigurablePlanStatus("APPLIED"));
		MatchingConfigureRunTest.check("PROPOSED is not", !MatchingConfigurator.isConfigurablePlanStatus("PROPOSED"));
		MatchingConfigureRunTest.check("REJECTED is not", !MatchingConfigurator.isConfigurablePlanStatus("REJECTED"));
		MatchingConfigureRunTest.check("SUPERSEDED is not", !MatchingConfigurator.isConfigurablePlanStatus("SUPERSEDED"));
		MatchingConfigureRunTest.check("null is not", !MatchingConfigurator.isConfigurablePlanStatus(null));
	}

	private static void normalRunReportsTheFourCounts()
	{
		final String message = MatchingConfigureRun.message(false, null, 3, 1, 2, 0);
		MatchingConfigureRunTest.check("plural wording, all three counts present",
			"3 entities configured, 1 refused, 2 not accepted.".equals(message));
	}

	private static void stoppedRunNamesTheReasonAndWhatWasNotStarted()
	{
		final String message = MatchingConfigureRun.message(true, "stopped by philippe", 2, 0, 0, 4);
		MatchingConfigureRunTest.check("stop reason and stoppedBefore count both present",
			message.contains("stopped by philippe") && message.contains("2 entities configured before the stop") && message.contains("4 not started"));
	}

	private static void singularWordingForExactlyOneEntity()
	{
		MatchingConfigureRunTest.check("singular, normal run", "1 entity configured, 0 refused, 0 not accepted.".equals(MatchingConfigureRun.message(false, null, 1, 0, 0, 0)));
		MatchingConfigureRunTest.check("singular, stopped run",
			MatchingConfigureRun.message(true, "the time limit", 1, 0, 0, 0).contains("1 entity configured before the stop"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new IllegalStateException("MatchingConfigureRunTest failed: " + pWhat);
		}
	}
}
