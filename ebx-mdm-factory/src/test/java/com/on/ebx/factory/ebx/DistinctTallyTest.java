package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Audit F-50 (2026-10-06): the distinct-value tally of the exact-aggregates fallback scan is bounded and
 * says when it was ({@link QualitySuggester.DistinctTally}).
 */
public final class DistinctTallyTest
{
	public static void main(final String[] pArgs)
	{
		final QualitySuggester.DistinctTally small = new QualitySuggester.DistinctTally(3);
		for (final String value : new String[] { "a", "b", "a", "c", "b" })
		{
			small.add(value);
		}
		DistinctTallyTest.check("under the cap the count is exact", small.count() == 3 && !small.capReached());
		small.add("a");
		DistinctTallyTest.check("a value already known at the cap changes nothing", small.count() == 3 && !small.capReached());
		small.add("d");
		DistinctTallyTest.check("a new value past the cap is not kept and the cap is reported", small.count() == 3 && small.capReached());
		small.add("a");
		DistinctTallyTest.check("the report stays", small.capReached());
		final QualitySuggester.DistinctTally big = new QualitySuggester.DistinctTally();
		DistinctTallyTest.check("the default cap is the documented one", QualitySuggester.DistinctTally.CAP == 500000 && big.count() == 0 && !big.capReached());
		System.out.println("DistinctTallyTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("DistinctTallyTest: " + pWhat);
		}
	}
}
