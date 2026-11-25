package com.on.ebx.factory.wizard;

import java.util.List;

/** JUnit-free: run the main, a failure throws. */
public final class DeducedScanCardSummaryTest
{
	public static void main(final String[] pArgs)
	{
		// all confirmed - the DEMOCRM live case (defect 14)
		List<DeducedScanCardSummary.Part> parts = DeducedScanCardSummary.parts(2, 0, 0);
		DeducedScanCardSummaryTest.check("one part only", parts.size() == 1);
		DeducedScanCardSummaryTest.check("it is CONFIRMED", DeducedScanCardSummary.CONFIRMED.equals(parts.get(0).kind));
		DeducedScanCardSummaryTest.check("count is 2", parts.get(0).count == 2);

		// all left out
		parts = DeducedScanCardSummary.parts(0, 3, 0);
		DeducedScanCardSummaryTest.check("one part only", parts.size() == 1);
		DeducedScanCardSummaryTest.check("it is LEFT_OUT", DeducedScanCardSummary.LEFT_OUT.equals(parts.get(0).kind));

		// nothing decided yet - only WAITING, same wording family as the original run summary
		parts = DeducedScanCardSummary.parts(0, 0, 2);
		DeducedScanCardSummaryTest.check("one part only", parts.size() == 1);
		DeducedScanCardSummaryTest.check("it is WAITING", DeducedScanCardSummary.WAITING.equals(parts.get(0).kind));

		// mixed: confirmed + left out, order is confirmed first, left out second, no waiting part at all
		parts = DeducedScanCardSummary.parts(1, 1, 0);
		DeducedScanCardSummaryTest.check("two parts", parts.size() == 2);
		DeducedScanCardSummaryTest.check("confirmed first", DeducedScanCardSummary.CONFIRMED.equals(parts.get(0).kind));
		DeducedScanCardSummaryTest.check("left out second", DeducedScanCardSummary.LEFT_OUT.equals(parts.get(1).kind));

		// mixed: some decided, some still waiting
		parts = DeducedScanCardSummary.parts(1, 0, 1);
		DeducedScanCardSummaryTest.check("two parts", parts.size() == 2);
		DeducedScanCardSummaryTest.check("confirmed first", DeducedScanCardSummary.CONFIRMED.equals(parts.get(0).kind));
		DeducedScanCardSummaryTest.check("waiting last", DeducedScanCardSummary.WAITING.equals(parts.get(1).kind));

		// all three at once, always in the same order
		parts = DeducedScanCardSummary.parts(1, 1, 1);
		DeducedScanCardSummaryTest.check("three parts", parts.size() == 3);
		DeducedScanCardSummaryTest.check("confirmed, left out, waiting in that order",
			DeducedScanCardSummary.CONFIRMED.equals(parts.get(0).kind) && DeducedScanCardSummary.LEFT_OUT.equals(parts.get(1).kind)
				&& DeducedScanCardSummary.WAITING.equals(parts.get(2).kind));

		// no decision data at all (an older run, or a source with no deduced asset) - empty, caller must fall back
		parts = DeducedScanCardSummary.parts(0, 0, 0);
		DeducedScanCardSummaryTest.check("empty when every count is zero", parts.isEmpty());

		System.out.println("DeducedScanCardSummaryTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
