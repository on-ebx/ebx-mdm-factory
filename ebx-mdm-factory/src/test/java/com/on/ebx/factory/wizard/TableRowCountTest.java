package com.on.ebx.factory.wizard;

/** JUnit-free, like {@code ApplicationStagesTest}: run the main, a failure throws. Exercises {@link TableRowCount#of}. */
public final class TableRowCountTest
{
	public static void main(final String[] pArgs)
	{
		TableRowCountTest.profiledCountWinsEvenWhenLastLoadIsBigger();
		TableRowCountTest.profiledRealZeroIsKeptAsZeroNotFallenBackTo();
		TableRowCountTest.unprofiledFallsBackToLastLoadRows();
		TableRowCountTest.unprofiledAndNeverLoadedIsUnknown();

		System.out.println("TableRowCountTest OK");
	}

	// the PERSONNES_SALESFORCE bug itself, reproduced: sourceRowCount unknown (the catalog never profiled a
	// sample-file source), lastLoadRows real (180) - the fix's whole point.
	private static void unprofiledFallsBackToLastLoadRows()
	{
		final TableRowCount rc = TableRowCount.of(false, 0L, 180L);
		TableRowCountTest.check("known", rc.known);
		TableRowCountTest.check("value is the load's own count", rc.value == 180L);
	}

	private static void profiledCountWinsEvenWhenLastLoadIsBigger()
	{
		final TableRowCount rc = TableRowCount.of(true, 42L, 9999L);
		TableRowCountTest.check("known", rc.known);
		TableRowCountTest.check("value is the profiled count, not the load's", rc.value == 42L);
	}

	// a real profiled zero (the catalog genuinely counted zero rows) must stay zero and KNOWN - never
	// silently swapped for a load count, and never confused with "unknown".
	private static void profiledRealZeroIsKeptAsZeroNotFallenBackTo()
	{
		final TableRowCount rc = TableRowCount.of(true, 0L, 500L);
		TableRowCountTest.check("known", rc.known);
		TableRowCountTest.check("value is the real profiled zero", rc.value == 0L);
	}

	private static void unprofiledAndNeverLoadedIsUnknown()
	{
		final TableRowCount rc = TableRowCount.of(false, 0L, 0L);
		TableRowCountTest.check("not known", !rc.known);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
