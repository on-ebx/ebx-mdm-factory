package com.on.ebx.factory.landing;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link LoadOutcomeRule} (volume-guards lot, 2026-09-28, task item 1). */
public final class LoadOutcomeRuleTest
{
	public static void main(final String[] pArgs)
	{
		LoadOutcomeRuleTest.checkTruncatedWins();
		LoadOutcomeRuleTest.checkAvailableExceedsCount();
		LoadOutcomeRuleTest.checkTruncatedAndAvailableBothAbsentFallsBackToCatalog();
		LoadOutcomeRuleTest.checkNoSourceRowCountSkipsTheComparison();
		LoadOutcomeRuleTest.checkExactBoundaryOfTheTolerance();
		LoadOutcomeRuleTest.checkTruncatedFalseDoesNotSuppressTheCatalogCheck();
		System.out.println("LoadOutcomeRuleTest OK");
	}

	private static void checkTruncatedWins()
	{
		// truncated=true fails even when the row count would otherwise look perfectly fine (count == sourceRowCount)
		final LoadOutcomeRule.Decision decision = LoadOutcomeRule.decide(Boolean.TRUE, null, 100, 100);
		LoadOutcomeRuleTest.check("truncated=true is FAILED regardless of the row count", decision.level == LoadOutcomeRule.Level.FAILED);
		LoadOutcomeRuleTest.check("reason names truncated: " + decision.reason, decision.reason.contains("truncated"));
	}

	private static void checkAvailableExceedsCount()
	{
		final LoadOutcomeRule.Decision decision = LoadOutcomeRule.decide(null, Long.valueOf(500), 100, 0);
		LoadOutcomeRuleTest.check("available > count is FAILED", decision.level == LoadOutcomeRule.Level.FAILED);
		LoadOutcomeRuleTest.check("reason names both numbers: " + decision.reason, decision.reason.contains("500") && decision.reason.contains("100"));

		final LoadOutcomeRule.Decision equalIsFine = LoadOutcomeRule.decide(null, Long.valueOf(100), 100, 0);
		LoadOutcomeRuleTest.check("available == count is not a failure on its own", equalIsFine.level == LoadOutcomeRule.Level.OK);

		final LoadOutcomeRule.Decision lessIsFine = LoadOutcomeRule.decide(null, Long.valueOf(80), 100, 0);
		LoadOutcomeRuleTest.check("available < count (more read than the source last reported available - a plausible race) is not a failure",
			lessIsFine.level == LoadOutcomeRule.Level.OK);
	}

	/** No truncated/available at all (an older Connectors build): falls back to the sourceRowCount comparison. */
	private static void checkTruncatedAndAvailableBothAbsentFallsBackToCatalog()
	{
		final LoadOutcomeRule.Decision failed = LoadOutcomeRule.decide(null, null, 80, 100);
		LoadOutcomeRuleTest.check("80 of 100 (80%) falls below the 95% tolerance: FAILED", failed.level == LoadOutcomeRule.Level.FAILED);

		final LoadOutcomeRule.Decision warn = LoadOutcomeRule.decide(null, null, 96, 100);
		LoadOutcomeRuleTest.check("96 of 100 (96%) is within tolerance: WARN, not FAILED", warn.level == LoadOutcomeRule.Level.WARN);

		final LoadOutcomeRule.Decision ok = LoadOutcomeRule.decide(null, null, 100, 100);
		LoadOutcomeRuleTest.check("count == sourceRowCount: OK, no reason at all", ok.level == LoadOutcomeRule.Level.OK && ok.reason == null);

		final LoadOutcomeRule.Decision grew = LoadOutcomeRule.decide(null, null, 150, 100);
		LoadOutcomeRuleTest.check("count > sourceRowCount (the source grew since the last scan): OK, never a guess in the other direction",
			grew.level == LoadOutcomeRule.Level.OK);
	}

	/** sourceRowCount 0 (never profiled) or negative: the comparison is skipped entirely, never guessed. */
	private static void checkNoSourceRowCountSkipsTheComparison()
	{
		LoadOutcomeRuleTest.check("sourceRowCount 0: OK even for a tiny count", LoadOutcomeRule.decide(null, null, 1, 0).level == LoadOutcomeRule.Level.OK);
		LoadOutcomeRuleTest.check("sourceRowCount negative (defensive): OK, never guessed", LoadOutcomeRule.decide(null, null, 1, -5).level == LoadOutcomeRule.Level.OK);
	}

	/** Exactly at the 95% boundary: the task brief's own wording ("FAILED when count < 0.95 * sourceRowCount") is a strict less-than, so exactly 95% is WARN, not FAILED. */
	private static void checkExactBoundaryOfTheTolerance()
	{
		final LoadOutcomeRule.Decision exactBoundary = LoadOutcomeRule.decide(null, null, 95, 100);
		LoadOutcomeRuleTest.check("exactly 95%: WARN (strict less-than for FAILED), not FAILED", exactBoundary.level == LoadOutcomeRule.Level.WARN);

		final LoadOutcomeRule.Decision justBelow = LoadOutcomeRule.decide(null, null, 94, 100);
		LoadOutcomeRuleTest.check("just below 95%: FAILED", justBelow.level == LoadOutcomeRule.Level.FAILED);
	}

	/** An explicit truncated=false must not suppress the catalog fallback check - only a present TRUE short-circuits, per decide's own precedence. */
	private static void checkTruncatedFalseDoesNotSuppressTheCatalogCheck()
	{
		final LoadOutcomeRule.Decision decision = LoadOutcomeRule.decide(Boolean.FALSE, null, 80, 100);
		LoadOutcomeRuleTest.check("truncated=false still lets the row-count shortfall fail the load", decision.level == LoadOutcomeRule.Level.FAILED);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
