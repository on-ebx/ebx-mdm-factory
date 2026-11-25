package com.on.ebx.factory.transfer;

/** JUnit-free: run the main, a failure throws. Lot M6 (2026-10-04): the "mapped but empty" guard's counters and message. */
public final class FieldFillCheckTest
{
	public static void main(final String[] pArgs)
	{
		FieldFillCheckTest.theMeasuredCaseIsReportedWithTheDocumentedWording();
		FieldFillCheckTest.aFilledAttributeIsNotReported();
		FieldFillCheckTest.aSourceColumnThatIsEmptyEverywhereIsNotReported();
		FieldFillCheckTest.nearlyEmptyIsReportedAndSomewhatFilledIsNot();
		System.out.println("FieldFillCheckTest OK");
	}

	private static FieldFillCheck.Counter counter(final long pRows, final long pSourceFilled, final long pFilled)
	{
		final FieldFillCheck.Counter counter = new FieldFillCheck.Counter("firstName", "FULL_NAME");
		for (long i = 0; i < pRows; i++)
		{
			counter.count(i < pSourceFilled ? "Aurelie Navret" : "  ", i < pFilled ? "Aurelie" : null);
		}
		return counter;
	}

	private static void theMeasuredCaseIsReportedWithTheDocumentedWording()
	{
		final String line = FieldFillCheck.report(FieldFillCheckTest.counter(153937, 153937, 0), "VOL1MECOM");
		FieldFillCheckTest.check("line: " + line, ("firstName: 0 of 153,937 rows filled from VOL1MECOM.FULL_NAME (the source column is filled on 153,937)"
			+ " - check the mapping").equals(line));
	}

	private static void aFilledAttributeIsNotReported()
	{
		FieldFillCheckTest.check("filled everywhere", FieldFillCheck.report(FieldFillCheckTest.counter(1000, 1000, 1000), "T") == null);
	}

	private static void aSourceColumnThatIsEmptyEverywhereIsNotReported()
	{
		FieldFillCheckTest.check("nothing to fill from", FieldFillCheck.report(FieldFillCheckTest.counter(1000, 0, 0), "T") == null);
		FieldFillCheckTest.check("no row at all", FieldFillCheck.report(FieldFillCheckTest.counter(0, 0, 0), "T") == null);
	}

	private static void nearlyEmptyIsReportedAndSomewhatFilledIsNot()
	{
		FieldFillCheckTest.check("1 of 1000 is nearly empty", FieldFillCheck.report(FieldFillCheckTest.counter(1000, 1000, 1), "T") != null);
		FieldFillCheckTest.check("20 of 1000 is not", FieldFillCheck.report(FieldFillCheckTest.counter(1000, 1000, 20), "T") == null);
		final FieldFillCheck.Counter counter = FieldFillCheckTest.counter(100, 60, 0);
		FieldFillCheckTest.check("only the rows with a source count: 0 of 60", FieldFillCheck.report(counter, "T").contains("is filled on 60"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
