package com.on.ebx.factory.ebx;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. Covers {@link
 * MatchPlanSuggester#autoMergeForWrite} - same pure rule and same reason as {@code
 * MatchPlanLlmSuggesterAutoMergeTest} (automerge-toggle lot, 2026-09-23, task 2): a brand-new row never
 * inherits {@code true}; an existing row keeps whatever a person set from the review screen's own toggle
 * (task 1). Same package as {@link MatchPlanSuggester} on purpose, to reach its package-private static
 * directly.
 */
public final class MatchPlanSuggesterAutoMergeTest
{
	public static void main(final String[] pArgs)
	{
		MatchPlanSuggesterAutoMergeTest.newRow_alwaysFalse_evenWhenExistingWasTrue();
		MatchPlanSuggesterAutoMergeTest.existingRow_previouslyTrue_staysTrue();
		MatchPlanSuggesterAutoMergeTest.existingRow_previouslyFalse_staysFalse();
		System.out.println("MatchPlanSuggesterAutoMergeTest OK");
	}

	private static void newRow_alwaysFalse_evenWhenExistingWasTrue()
	{
		MatchPlanSuggesterAutoMergeTest.check("new row ignores a true existing value", !MatchPlanSuggester.autoMergeForWrite(true, true));
		MatchPlanSuggesterAutoMergeTest.check("new row stays false", !MatchPlanSuggester.autoMergeForWrite(true, false));
	}

	private static void existingRow_previouslyTrue_staysTrue()
	{
		MatchPlanSuggesterAutoMergeTest.check("a human's own 'on' choice survives a rule re-run", MatchPlanSuggester.autoMergeForWrite(false, true));
	}

	private static void existingRow_previouslyFalse_staysFalse()
	{
		MatchPlanSuggesterAutoMergeTest.check("still false when it was never turned on", !MatchPlanSuggester.autoMergeForWrite(false, false));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
