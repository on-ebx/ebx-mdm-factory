package com.on.ebx.factory.quality;

import java.util.Locale;

/**
 * Staging polish (2026-10-17, docs/UX-STAGING-POLISH.md sec 2): the volume guard of the proposal passes' column reads. A proposal reads, for every mapped
 * column, the distinct values of its landing column (one {@code GROUP BY} over the whole landing table) and its exact aggregates (a {@code COUNT(DISTINCT)}
 * over the whole table): fine on 5,000 rows, minutes of kernel time on 1.4 million rows times the forty mapped columns. Above {@link #maxRowsFullScan()}
 * rows the read is bounded to the first {@link #sampleRows()} rows of the table (primary key order - deterministic, the same rows at every read of an
 * unchanged table) and every figure drawn from it says it is a sample ({@link #sampleWords}).
 * <p>
 * Pure: the EBX side ({@code ebx.ColumnReads}) counts the rows and reads, this class only decides and words. Both bounds are JVM properties read at each
 * call (a change needs no restart): {@value #PROPERTY_MAX_ROWS} (default {@value #DEFAULT_MAX_ROWS}; 0 or less switches the guard OFF, every read is full) and
 * {@value #PROPERTY_SAMPLE_ROWS} (default {@value #DEFAULT_SAMPLE_ROWS}, never above the first bound).
 */
public final class ScanGuard
{
	/** JVM property: the row count of a landing table above which a column is read on a sample. */
	public static final String PROPERTY_MAX_ROWS = "mdm.factory.profile.maxRowsFullScan";
	public static final long DEFAULT_MAX_ROWS = 200000L;
	/** JVM property: the rows read when a table is above the bound. */
	public static final String PROPERTY_SAMPLE_ROWS = "mdm.factory.profile.sampleRows";
	public static final long DEFAULT_SAMPLE_ROWS = 50000L;

	private ScanGuard()
	{
	}

	/** What to do with one table: read it whole, or its first {@link #rowsToRead} rows. */
	public static final class Decision
	{
		/** True when only a sample is read. */
		public final boolean sampled;
		/** The rows to read when {@link #sampled}; {@link Long#MAX_VALUE} otherwise. */
		public final long rowsToRead;
		/** The table's row count, or -1 when it could not be counted. */
		public final long tableRows;

		Decision(final boolean pSampled, final long pRowsToRead, final long pTableRows)
		{
			this.sampled = pSampled;
			this.rowsToRead = pRowsToRead;
			this.tableRows = pTableRows;
		}
	}

	/** The bound from a property value: the default when missing or not a whole number; 0 or less = guard off. */
	public static long maxRowsFullScan(final String pPropertyValue)
	{
		return ScanGuard.parse(pPropertyValue, ScanGuard.DEFAULT_MAX_ROWS);
	}

	/** The bound in force now. */
	public static long maxRowsFullScan()
	{
		return ScanGuard.maxRowsFullScan(System.getProperty(ScanGuard.PROPERTY_MAX_ROWS));
	}

	/** The sample size from a property value: the default when missing, not a whole number or not positive; never above {@code pMaxRowsFullScan} when that bound is on. */
	public static long sampleRows(final String pPropertyValue, final long pMaxRowsFullScan)
	{
		long rows = ScanGuard.parse(pPropertyValue, ScanGuard.DEFAULT_SAMPLE_ROWS);
		if (rows <= 0L)
		{
			rows = ScanGuard.DEFAULT_SAMPLE_ROWS;
		}
		return pMaxRowsFullScan > 0L && rows > pMaxRowsFullScan ? pMaxRowsFullScan : rows;
	}

	/** The sample size in force now. */
	public static long sampleRows()
	{
		return ScanGuard.sampleRows(System.getProperty(ScanGuard.PROPERTY_SAMPLE_ROWS), ScanGuard.maxRowsFullScan());
	}

	/** The decision for a table of {@code pTableRows} rows (negative = could not be counted: read a sample, the safe side) under the bounds in force now. */
	public static Decision decide(final long pTableRows)
	{
		final long max = ScanGuard.maxRowsFullScan();
		return ScanGuard.decide(pTableRows, max, ScanGuard.sampleRows(System.getProperty(ScanGuard.PROPERTY_SAMPLE_ROWS), max));
	}

	/** Pure form of {@link #decide(long)}: explicit bounds, a test's entry point. */
	public static Decision decide(final long pTableRows, final long pMaxRowsFullScan, final long pSampleRows)
	{
		if (pMaxRowsFullScan <= 0L)
		{
			return new Decision(false, Long.MAX_VALUE, pTableRows); // guard off
		}
		if (pTableRows >= 0L && pTableRows <= pMaxRowsFullScan)
		{
			return new Decision(false, Long.MAX_VALUE, pTableRows);
		}
		final long sample = pSampleRows <= 0L ? ScanGuard.DEFAULT_SAMPLE_ROWS : pSampleRows;
		return new Decision(true, pTableRows >= 0L ? Math.min(sample, pTableRows) : sample, pTableRows);
	}

	/** {@code "a sample of 50,000 rows"} - the words every verdict drawn from a sample uses. */
	public static String sampleWords(final long pSampleRows)
	{
		return "a sample of " + String.format(Locale.ROOT, "%,d", Long.valueOf(pSampleRows)) + " rows";
	}

	private static long parse(final String pValue, final long pDefault)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return pDefault;
		}
		try
		{
			return Long.parseLong(pValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}
}
