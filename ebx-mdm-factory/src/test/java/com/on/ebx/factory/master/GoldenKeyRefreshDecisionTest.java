package com.on.ebx.factory.master;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class GoldenKeyRefreshDecisionTest
{
	public static void main(final String[] pArgs)
	{
		GoldenKeyRefreshDecisionTest.noMetadataAtAll_currentlyBlank_noChange();
		GoldenKeyRefreshDecisionTest.noMetadataAtAll_currentlyStale_cleared();
		GoldenKeyRefreshDecisionTest.neverMerged_becomesOwnGoldenKey();
		GoldenKeyRefreshDecisionTest.neverMerged_alreadyOwnGoldenKey_noChange();
		GoldenKeyRefreshDecisionTest.mergedAway_setsTargetGoldenKey();
		GoldenKeyRefreshDecisionTest.mergedAway_alreadyCorrect_noChange();
		GoldenKeyRefreshDecisionTest.mergedAway_targetChanged_reSet();
		System.out.println("GoldenKeyRefreshDecisionTest OK");
	}

	private static void noMetadataAtAll_currentlyBlank_noChange()
	{
		final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(false, null, "42", null);
		GoldenKeyRefreshDecisionTest.check("no change", decision.action == GoldenKeyRefreshDecision.Action.NO_CHANGE);
	}

	private static void noMetadataAtAll_currentlyStale_cleared()
	{
		// e.g. after a hand-rolled cleanup that did not go through MasterReset - defensive, should not happen in practice
		final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(false, null, "42", "99");
		GoldenKeyRefreshDecisionTest.check("cleared", decision.action == GoldenKeyRefreshDecision.Action.CLEAR);
	}

	private static void neverMerged_becomesOwnGoldenKey()
	{
		final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(true, null, "42", null);
		GoldenKeyRefreshDecisionTest.check("set", decision.action == GoldenKeyRefreshDecision.Action.SET);
		GoldenKeyRefreshDecisionTest.check("own key", "42".equals(decision.newGoldenKey));
	}

	private static void neverMerged_alreadyOwnGoldenKey_noChange()
	{
		final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(true, "", "42", "42");
		GoldenKeyRefreshDecisionTest.check("no change", decision.action == GoldenKeyRefreshDecision.Action.NO_CHANGE);
	}

	private static void mergedAway_setsTargetGoldenKey()
	{
		final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(true, "108", "42", null);
		GoldenKeyRefreshDecisionTest.check("set", decision.action == GoldenKeyRefreshDecision.Action.SET);
		GoldenKeyRefreshDecisionTest.check("target key", "108".equals(decision.newGoldenKey));
	}

	private static void mergedAway_alreadyCorrect_noChange()
	{
		final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(true, "108", "42", "108");
		GoldenKeyRefreshDecisionTest.check("no change", decision.action == GoldenKeyRefreshDecision.Action.NO_CHANGE);
	}

	private static void mergedAway_targetChanged_reSet()
	{
		// a re-merge can change which record is golden - the refresh must follow it, not keep the stale target
		final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(true, "109", "42", "108");
		GoldenKeyRefreshDecisionTest.check("set", decision.action == GoldenKeyRefreshDecision.Action.SET);
		GoldenKeyRefreshDecisionTest.check("new target", "109".equals(decision.newGoldenKey));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
