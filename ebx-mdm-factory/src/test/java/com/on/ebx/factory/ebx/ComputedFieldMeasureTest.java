package com.on.ebx.factory.ebx;

import com.on.ebx.factory.mappingmatrix.MatrixModel;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * fix/matrix-computed-field: the counting of {@link ComputedFieldMeasure.Tally} (what the function did to the rows read,
 * the first value of each kind kept as the example) and the two "nothing to measure" answers that need no repository.
 * The read of the staging table itself needs a live EBX session and is exercised only by hand.
 */
public final class ComputedFieldMeasureTest
{
	public static void main(final String[] pArgs)
	{
		ComputedFieldMeasureTest.tallyCountsConvertedAndEmptyAndKeepsTheFirstExampleOfEach();
		ComputedFieldMeasureTest.aBlankResultCountsAsEmpty();
		ComputedFieldMeasureTest.beforeTheFirstLoadNothingIsMeasured();
		ComputedFieldMeasureTest.untilTheFunctionIsAppliedNothingIsMeasured();
		System.out.println("ComputedFieldMeasureTest OK");
	}

	private static void tallyCountsConvertedAndEmptyAndKeepsTheFirstExampleOfEach()
	{
		final ComputedFieldMeasure.Tally tally = new ComputedFieldMeasure.Tally();
		tally.add("0162059624", "");
		tally.add("+33 6 12 34 56 78", "+33612345678");
		tally.add("0712345678", null);
		tally.add("+49 30 123456", "+4930123456");
		final MatrixModel.SourceCount count = tally.toCount("ECOM", 400, 350);
		ComputedFieldMeasureTest.check("four rows read", count.sampled == 4 && count.total == 400 && count.filled == 350);
		ComputedFieldMeasureTest.check("two converted, two empty", count.converted == 2 && count.empty == 2);
		ComputedFieldMeasureTest.check("first converted example", "+33 6 12 34 56 78".equals(count.convertedBefore) && "+33612345678".equals(count.convertedAfter));
		ComputedFieldMeasureTest.check("first empty example", "0162059624".equals(count.emptyBefore));
		ComputedFieldMeasureTest.check("no row read: no example, never an invented one",
			new ComputedFieldMeasure.Tally().toCount("X", 0, 0).convertedBefore == null && new ComputedFieldMeasure.Tally().toCount("X", 0, 0).emptyBefore == null);
	}

	private static void aBlankResultCountsAsEmpty()
	{
		final ComputedFieldMeasure.Tally tally = new ComputedFieldMeasure.Tally();
		tally.add("x", "   ");
		ComputedFieldMeasureTest.check("whitespace only is empty", tally.empty == 1 && tally.converted == 0);
	}

	private static void beforeTheFirstLoadNothingIsMeasured()
	{
		final MatrixModel.Measurement measurement = ComputedFieldMeasure.measure(null, "S", "D", "Personne", null, "APPLIED", "phone", "phoneE164", java.util.Arrays.asList("CRM"));
		ComputedFieldMeasureTest.check("lastLoadAt empty: NOT_LOADED, no repository touched", MatrixModel.Measurement.NOT_LOADED.equals(measurement.state) && measurement.sources.isEmpty());
	}

	private static void untilTheFunctionIsAppliedNothingIsMeasured()
	{
		final MatrixModel.Measurement measurement =
			ComputedFieldMeasure.measure(null, "S", "D", "Personne", new java.util.Date(), "ACCEPTED", "phone", "phoneE164", java.util.Arrays.asList("CRM"));
		ComputedFieldMeasureTest.check("loaded but not APPLIED: NOT_APPLIED - an empty plain field would measure nothing about the function",
			MatrixModel.Measurement.NOT_APPLIED.equals(measurement.state) && measurement.sources.isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
