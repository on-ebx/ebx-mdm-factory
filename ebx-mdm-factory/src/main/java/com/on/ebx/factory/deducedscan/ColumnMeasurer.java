package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deduced-scan lot (2026-09-24): "Measure" stage of the read-recipe-contract.md deduced scan - pure,
 * Java-only measuring of a sample of {@link Flatten}-ed rows (task brief: "Java measures per column: type
 * (text / number / date and time / yes-no), filled count, distinct count, one example"; product rule
 * "domain knowledge from the LLM only, Java only measures - no test on a column/table name"). Type inference
 * looks only at the VALUES seen for a column, never at its name.
 */
public final class ColumnMeasurer
{
	/** {@code Column}'s own {@code dataType} vocabulary is free text in this catalog (see {@code CatalogReader.Column#dataType}), but the wizard's board 2e table shows exactly these four words. */
	public static final String TEXT = "text";
	public static final String NUMBER = "number";
	public static final String DATE_TIME = "date and time";
	public static final String YES_NO = "yes / no";

	private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");
	private static final Pattern DATE_TIME_PATTERN = Pattern
		.compile("\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?)?");
	private static final Set<String> BOOLEAN_VALUES = new LinkedHashSet<String>(java.util.Arrays.asList("true", "false"));

	private ColumnMeasurer()
	{
	}

	/** One measured column - immutable, position order is the caller's own (first-seen column order across the sample, see {@link #measure}). */
	public static final class Measured
	{
		public final String name;
		public final String type;
		public final int filled;
		public final int sampleSize;
		public final int distinct;
		/** First non-empty value seen, verbatim (never truncated here - the caller/wizard truncates for display if it wants to). Empty string if every value was empty/missing. */
		public final String example;

		public Measured(final String pName, final String pType, final int pFilled, final int pSampleSize, final int pDistinct, final String pExample)
		{
			this.name = pName;
			this.type = pType;
			this.filled = pFilled;
			this.sampleSize = pSampleSize;
			this.distinct = pDistinct;
			this.example = pExample == null ? "" : pExample;
		}
	}

	/**
	 * Measures every column across {@code pRows} (each already {@link Flatten}-ed): the ordered UNION of every
	 * row's own keys (a row missing a key some other row has is simply not "filled" for it - same convention
	 * {@code LandingPreview} already uses for its preview table), never assumes every row has the same shape.
	 * An empty {@code pRows} answers an empty list, never throws.
	 */
	public static List<Measured> measure(final List<Map<String, String>> pRows)
	{
		final List<String> columnOrder = new ArrayList<String>();
		final Set<String> seen = new LinkedHashSet<String>();
		for (final Map<String, String> row : pRows)
		{
			for (final String column : row.keySet())
			{
				if (seen.add(column))
				{
					columnOrder.add(column);
				}
			}
		}
		final List<Measured> out = new ArrayList<Measured>();
		for (final String column : columnOrder)
		{
			out.add(ColumnMeasurer.measureOne(column, pRows));
		}
		return out;
	}

	private static Measured measureOne(final String pColumn, final List<Map<String, String>> pRows)
	{
		int filled = 0;
		final Set<String> distinctValues = new LinkedHashSet<String>();
		String example = "";
		boolean allNumber = true;
		boolean allDateTime = true;
		boolean allBoolean = true;
		for (final Map<String, String> row : pRows)
		{
			final String raw = row.get(pColumn);
			final String value = raw == null ? "" : raw.trim();
			if (value.isEmpty())
			{
				continue;
			}
			filled++;
			distinctValues.add(value);
			if (example.isEmpty())
			{
				example = raw;
			}
			if (allNumber && !ColumnMeasurer.NUMBER_PATTERN.matcher(value).matches())
			{
				allNumber = false;
			}
			if (allDateTime && !ColumnMeasurer.DATE_TIME_PATTERN.matcher(value).matches())
			{
				allDateTime = false;
			}
			if (allBoolean && !ColumnMeasurer.BOOLEAN_VALUES.contains(value.toLowerCase(java.util.Locale.ROOT)))
			{
				allBoolean = false;
			}
		}
		final String type = filled == 0 ? ColumnMeasurer.TEXT : ColumnMeasurer.typeOf(allBoolean, allDateTime, allNumber);
		return new Measured(pColumn, type, filled, pRows.size(), distinctValues.size(), example);
	}

	/** Priority when several would match (an all-"0"/"1" column reads NUMBER first if BOOLEAN_VALUES did not exclude digits - it does, "0"/"1" are not in {@link #BOOLEAN_VALUES}, only true/false words are): yes/no, then date and time, then number, else text. */
	private static String typeOf(final boolean pAllBoolean, final boolean pAllDateTime, final boolean pAllNumber)
	{
		if (pAllBoolean)
		{
			return ColumnMeasurer.YES_NO;
		}
		if (pAllDateTime)
		{
			return ColumnMeasurer.DATE_TIME;
		}
		if (pAllNumber)
		{
			return ColumnMeasurer.NUMBER;
		}
		return ColumnMeasurer.TEXT;
	}
}
