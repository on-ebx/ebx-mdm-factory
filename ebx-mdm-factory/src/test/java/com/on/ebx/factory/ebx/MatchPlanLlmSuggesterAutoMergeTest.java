package com.on.ebx.factory.ebx;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. Covers {@link
 * MatchPlanLlmSuggester#autoMergeForWrite} - automerge-toggle lot (2026-09-23), task 2: a brand-new row never
 * inherits {@code true} (a human decision only, never proposed by the language model - {@code
 * docs/MATCHING.md} &sect;3.4); an existing row keeps whatever a person set from the review screen's own
 * toggle (task 1, {@code ebx.ProposalReviews#setMatchPlanAutoMerge}), regardless of what the language model's
 * pass is rewriting on that row. Same package as {@link MatchPlanLlmSuggester} on purpose, to reach its
 * package-private static directly - the same convention {@link MatchPlanLlmSuggesterFilterTest} already
 * follows.
 */
public final class MatchPlanLlmSuggesterAutoMergeTest
{
	public static void main(final String[] pArgs)
	{
		MatchPlanLlmSuggesterAutoMergeTest.newRow_alwaysFalse_evenWhenExistingWasTrue();
		MatchPlanLlmSuggesterAutoMergeTest.existingRow_previouslyTrue_staysTrue();
		MatchPlanLlmSuggesterAutoMergeTest.existingRow_previouslyFalse_staysFalse();
		System.out.println("MatchPlanLlmSuggesterAutoMergeTest OK");
	}

	private static void newRow_alwaysFalse_evenWhenExistingWasTrue()
	{
		MatchPlanLlmSuggesterAutoMergeTest.check("new row ignores a true existing value", !MatchPlanLlmSuggester.autoMergeForWrite(true, true));
		MatchPlanLlmSuggesterAutoMergeTest.check("new row stays false", !MatchPlanLlmSuggester.autoMergeForWrite(true, false));
	}

	private static void existingRow_previouslyTrue_staysTrue()
	{
		MatchPlanLlmSuggesterAutoMergeTest.check("a human's own 'on' choice survives a re-propose", MatchPlanLlmSuggester.autoMergeForWrite(false, true));
	}

	private static void existingRow_previouslyFalse_staysFalse()
	{
		MatchPlanLlmSuggesterAutoMergeTest.check("still false when it was never turned on", !MatchPlanLlmSuggester.autoMergeForWrite(false, false));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
