package com.on.ebx.factory.master;

/** JUnit-free: run the main, a failure throws. {@link ScanBudget} (lot V3-M2, 2026-10-04). */
public final class ScanBudgetTest
{
	public static void main(final String[] pArgs)
	{
		ScanBudgetTest.check("no property: the default", ScanBudget.parse(null) == ScanBudget.DEFAULT_MAX_INDEXED_ROWS);
		ScanBudgetTest.check("blank: the default", ScanBudget.parse("  ") == ScanBudget.DEFAULT_MAX_INDEXED_ROWS);
		ScanBudgetTest.check("not a number: the default", ScanBudget.parse("many") == ScanBudget.DEFAULT_MAX_INDEXED_ROWS);
		ScanBudgetTest.check("a number", ScanBudget.parse(" 1200 ") == 1200L);
		ScanBudgetTest.check("zero is kept (disables)", ScanBudget.parse("0") == 0L);
		ScanBudgetTest.check("default is 15,000,000 (lot IDX)", ScanBudget.DEFAULT_MAX_INDEXED_ROWS == 15000000L);

		ScanBudgetTest.check("within the bound", ScanBudget.refusal(1000L, 1000L, 10L) == null);
		ScanBudgetTest.check("exactly at the bound on both tables", ScanBudget.refusal(1000L, 1000L, 1000L) == null);
		ScanBudgetTest.check("an empty entity is always within", ScanBudget.refusal(1L, 0L, 0L) == null);
		ScanBudgetTest.check("zero disables, whatever the sizes", ScanBudget.refusal(0L, 0L, 0L) != null);
		ScanBudgetTest.check("negative disables", ScanBudget.refusal(-1L, 1L, 1L) != null);
		ScanBudgetTest.check("too many staging rows", ScanBudget.refusal(1000L, 1001L, 5L).contains("staging"));
		ScanBudgetTest.check("too many master rows", ScanBudget.refusal(1000L, 5L, 1001L).contains("master"));
		ScanBudgetTest.check("the reason names the property", ScanBudget.refusal(0L, 1L, 1L).contains(ScanBudget.PROPERTY));

		// lot V3-S2: the staging load's own bound, one table
		ScanBudgetTest.check("the staging property is its own", !ScanBudget.STAGING_PROPERTY.equals(ScanBudget.PROPERTY)
			&& ScanBudget.STAGING_PROPERTY.equals("mdm.factory.stagingLoad.maxIndexedRows"));
		ScanBudgetTest.check("one table within the bound", ScanBudget.refusal(ScanBudget.STAGING_PROPERTY, 1000L, 1000L) == null);
		ScanBudgetTest.check("one table over the bound", ScanBudget.refusal(ScanBudget.STAGING_PROPERTY, 1000L, 1001L).contains(ScanBudget.STAGING_PROPERTY));
		ScanBudgetTest.check("zero disables the staging indexes", ScanBudget.refusal(ScanBudget.STAGING_PROPERTY, 0L, 0L).contains("disabled"));

		// lot V3-M3: the golden-key refresh's bound, one table (RecordMetadata)
		ScanBudgetTest.check("the refresh property is its own", ScanBudget.GOLDEN_REFRESH_PROPERTY.equals("mdm.factory.goldenRefresh.maxIndexedRows"));
		ScanBudgetTest.check("refresh: within the bound", ScanBudget.goldenRefreshRefusal(1000L, 1000L) == null);
		ScanBudgetTest.check("refresh: over the bound", ScanBudget.goldenRefreshRefusal(1000L, 1001L).contains(ScanBudget.GOLDEN_REFRESH_PROPERTY));
		ScanBudgetTest.check("refresh: zero disables", ScanBudget.goldenRefreshRefusal(0L, 0L).contains("disabled"));
		ScanBudgetTest.check("refresh: the default bound applies without a property", ScanBudget.currentGoldenRefresh() == ScanBudget.DEFAULT_MAX_INDEXED_ROWS
			|| System.getProperty(ScanBudget.GOLDEN_REFRESH_PROPERTY) != null);
		System.out.println("ScanBudgetTest OK");
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
