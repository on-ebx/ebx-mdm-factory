package com.on.ebx.factory.quality;

/** Staging polish (2026-10-17): the volume guard's decision, bounds and wording. JUnit-free: run the main, a failure throws. */
public final class ScanGuardTest
{
	public static void main(final String[] pArgs)
	{
		ScanGuardTest.boundsFromProperties();
		ScanGuardTest.decision();
		ScanGuardTest.wording();
		ScanGuardTest.samplePropagatesToTheProposalsWording();
		System.out.println("ScanGuardTest OK");
	}

	private static void boundsFromProperties()
	{
		check("default full-scan bound", ScanGuard.maxRowsFullScan(null) == 200000L && ScanGuard.maxRowsFullScan("  ") == 200000L);
		check("a number is taken", ScanGuard.maxRowsFullScan("1000") == 1000L && ScanGuard.maxRowsFullScan(" 1000 ") == 1000L);
		check("not a number: the default", ScanGuard.maxRowsFullScan("lots") == 200000L);
		check("0 and negative switch the guard off", ScanGuard.maxRowsFullScan("0") == 0L && ScanGuard.maxRowsFullScan("-5") == -5L);
		check("default sample", ScanGuard.sampleRows(null, 200000L) == 50000L);
		check("a sample is never above the full-scan bound", ScanGuard.sampleRows("900000", 200000L) == 200000L);
		check("a sample is positive", ScanGuard.sampleRows("0", 200000L) == 50000L && ScanGuard.sampleRows("-1", 200000L) == 50000L);
		check("with the guard off the sample size is only parsed", ScanGuard.sampleRows("900000", 0L) == 900000L);
		check("the properties are read at each call", readsLive());
	}

	private static boolean readsLive()
	{
		final String old = System.getProperty(ScanGuard.PROPERTY_MAX_ROWS);
		try
		{
			System.setProperty(ScanGuard.PROPERTY_MAX_ROWS, "10");
			final boolean small = ScanGuard.decide(11L).sampled && !ScanGuard.decide(10L).sampled;
			System.setProperty(ScanGuard.PROPERTY_MAX_ROWS, "0");
			return small && !ScanGuard.decide(1000000000L).sampled;
		}
		finally
		{
			if (old == null)
			{
				System.clearProperty(ScanGuard.PROPERTY_MAX_ROWS);
			}
			else
			{
				System.setProperty(ScanGuard.PROPERTY_MAX_ROWS, old);
			}
		}
	}

	private static void decision()
	{
		ScanGuard.Decision d = ScanGuard.decide(5000L, 200000L, 50000L);
		check("a small table is read whole", !d.sampled && d.rowsToRead == Long.MAX_VALUE && d.tableRows == 5000L);
		d = ScanGuard.decide(200000L, 200000L, 50000L);
		check("exactly at the bound is still whole", !d.sampled);
		d = ScanGuard.decide(200001L, 200000L, 50000L);
		check("one row above the bound is a sample of the first rows", d.sampled && d.rowsToRead == 50000L && d.tableRows == 200001L);
		d = ScanGuard.decide(1465857L, 200000L, 50000L);
		check("1.4 million rows: 50,000", d.sampled && d.rowsToRead == 50000L);
		d = ScanGuard.decide(300L, 100L, 5000L);
		check("a sample never exceeds the table", d.sampled && d.rowsToRead == 300L);
		d = ScanGuard.decide(-1L, 200000L, 50000L);
		check("a table that cannot be counted is sampled (the safe side)", d.sampled && d.rowsToRead == 50000L && d.tableRows == -1L);
		d = ScanGuard.decide(5000000L, 0L, 50000L);
		check("guard off: whole", !d.sampled);
		d = ScanGuard.decide(5000000L, 200000L, 0L);
		check("a missing sample size falls back to the default", d.sampled && d.rowsToRead == 50000L);
	}

	private static void wording()
	{
		check("sample words", "a sample of 50,000 rows".equals(ScanGuard.sampleWords(50000L)));
		check("sample words, small", "a sample of 7 rows".equals(ScanGuard.sampleWords(7L)));
		check("the sentence of a proposal", " Measured on a sample of 50,000 rows (sample).".equals(Detectors.rowSampleSuffix(50000L)));
	}

	/** A row sample is always flagged sampled: the rationale says so and the confidence is capped, whatever the distinct counts say. */
	private static void samplePropagatesToTheProposalsWording()
	{
		final java.util.List<Detectors.ValueCount> values = java.util.Arrays.asList(new Detectors.ValueCount("  Paris", 40), new Detectors.ValueCount("Lyon", 60));
		final Detectors.ColumnContext column = new Detectors.ColumnContext("city", "city", "STRING", new java.util.ArrayList<String>());
		// 2 distinct read of 2 distinct exact: without a row sample nothing is flagged
		final java.util.List<Detectors.StepProposal> whole = Detectors.propose(values, column, 2L);
		check("whole column: a TRIM proposal, no sample wording", !whole.isEmpty() && !whole.get(0).rationale.contains("sample") && whole.get(0).confidence == 100);
		final java.util.List<Detectors.StepProposal> sampled = Detectors.propose(values, column, 2L, 50000L);
		check("row sample: the rationale names the rows", !sampled.isEmpty() && sampled.get(0).rationale.contains("a sample of 50,000 rows"));
		check("row sample: the confidence is capped", sampled.get(0).confidence < 100);
		check("row sample: not the most-frequent-values sentence", !sampled.get(0).rationale.contains("most frequent distinct"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError("ScanGuardTest: " + pWhat);
		}
	}
}
