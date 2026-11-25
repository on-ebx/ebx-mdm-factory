package com.on.ebx.factory.transfer;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Lot M6 (2026-10-04): the guard that would have caught the shared-column cleaning bug on its first load - a mapped
 * attribute whose SOURCE column is filled but whose staging value is empty on (nearly) every row of a feed is reported.
 * <p>
 * Pure: {@code ebx.StagingLoader} keeps one {@link Counter} per mapped field of a feed, updated inside the existing
 * per-row loop (three increments, no extra read: the source text is already in the row document, the staging value is the
 * xs string {@code MappingEngine} just computed), and calls {@link #report} per counter when the load ends.
 */
public final class FieldFillCheck
{
	/** A field counts as "empty on nearly every row" when at most this share (percent) of the rows where its source column is filled got a value. */
	static final int MAX_FILLED_PERCENT = 1;

	private FieldFillCheck()
	{
	}

	/** Per mapped field of one feed: rows seen, rows whose source column was filled, rows whose computed staging value is filled. */
	public static final class Counter
	{
		public final String attribute;
		public final String sourceColumn;
		public long rows;
		public long sourceFilled;
		public long filled;

		public Counter(final String pAttribute, final String pSourceColumn)
		{
			this.attribute = pAttribute;
			this.sourceColumn = pSourceColumn;
		}

		/** Counts one row: {@code pSourceText} is the raw landing text of the column (may be {@code null}), {@code pValue} the computed xs string (may be {@code null}). */
		public void count(final String pSourceText, final String pValue)
		{
			this.rows++;
			if (!FieldFillCheck.blank(pSourceText))
			{
				this.sourceFilled++;
			}
			if (!FieldFillCheck.blank(pValue))
			{
				this.filled++;
			}
		}
	}

	/** True when the source column was filled on at least one row and the attribute got a value on at most {@link #MAX_FILLED_PERCENT} percent of those rows. */
	public static boolean suspicious(final Counter pCounter)
	{
		return pCounter.sourceFilled > 0 && pCounter.filled * 100 <= pCounter.sourceFilled * FieldFillCheck.MAX_FILLED_PERCENT;
	}

	/**
	 * The one-line report, or {@code null} when the counter is not suspicious.
	 *
	 * @param pLandingTable the landing table the feed reads, prefixed to the source column in the text
	 */
	public static String report(final Counter pCounter, final String pLandingTable)
	{
		if (!FieldFillCheck.suspicious(pCounter))
		{
			return null;
		}
		final NumberFormat format = NumberFormat.getIntegerInstance(Locale.ENGLISH);
		return pCounter.attribute + ": " + format.format(pCounter.filled) + " of " + format.format(pCounter.rows) + " rows filled from " + pLandingTable + "."
			+ pCounter.sourceColumn + " (the source column is filled on " + format.format(pCounter.sourceFilled) + ") - check the mapping";
	}

	private static boolean blank(final String pText)
	{
		return pText == null || pText.trim().isEmpty();
	}
}
