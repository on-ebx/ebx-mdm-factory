package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.List;

import com.on.ebx.factory.quality.Detectors;

/** JUnit-free: run the main, a failure throws. The value shapes the staging pass shows the model (PersVol volume test, 2026-09-24). */
public final class StagingLlmSuggesterShapesTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> dates = StagingLlmSuggester.topShapes(Arrays.asList(new Detectors.ValueCount("19790907", null, 3),
			new Detectors.ValueCount("20010228", null, 1)));
		StagingLlmSuggesterShapesTest.check("two yyyyMMdd values share one shape, 100%", dates.equals(Arrays.asList("9(8) (100%)")));

		final List<String> names = StagingLlmSuggester.topShapes(Arrays.asList(new Detectors.ValueCount("Pierre Lefevre", null, 7),
			new Detectors.ValueCount("LEFEVRE Pierre", null, 3), new Detectors.ValueCount(null, null, 50)));
		StagingLlmSuggesterShapesTest.check("both name orders are told apart, most frequent first, nulls ignored",
			names.size() == 2 && names.get(0).endsWith("(70%)") && names.get(1).endsWith("(30%)") && !names.get(0).contains("Pierre"));

		final List<String> many = StagingLlmSuggester.topShapes(Arrays.asList(new Detectors.ValueCount("1", null, 4), new Detectors.ValueCount("a", null, 3),
			new Detectors.ValueCount("A", null, 2), new Detectors.ValueCount("-", null, 1)));
		StagingLlmSuggesterShapesTest.check("at most three shapes", many.size() == 3);
		System.out.println("StagingLlmSuggesterShapesTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
