package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.on.ebx.factory.transfer.TransformationCatalogue;

/**
 * Lot G2 (2026-10-17, docs/UX-STAGING-TEST-FIXES-G2.md): what the capability pass KNOWS, in Java, about the values an attribute will hold - kept apart
 * from the prompt, never shown to the model (the data policy's rule stands: the model sees shapes and counts, and only when the policy allows). Two
 * facts feed the deterministic guards of {@link ConstraintSanity}:
 * <ul>
 * <li>{@link #constantOnly}: every mapping feeding the attribute is a {@code constant(...)} pipeline - the attribute holds a closed set of texts the
 * proposal itself chose (measured: an {@code addressType} fed by {@code constant(HOME)} / {@code constant(BUYER)} got a nine-digit check);</li>
 * <li>{@link #values}: the distinct values with their row counts (the constants, or the sampled landing column of the mapping the attribute is read
 * from), against which a check whose verdict is computable in Java - a full-string regex - is measured.</li>
 * </ul>
 * Pure: no EBX type.
 */
public final class ValueEvidence
{
	/** One distinct value and how many rows hold it. */
	public static final class Weighted
	{
		public final String value;
		public final long count;

		public Weighted(final String pValue, final long pCount)
		{
			this.value = pValue;
			this.count = pCount;
		}
	}

	/** True when every mapping feeding the attribute is a constant. */
	public final boolean constantOnly;
	/** The distinct values (never null; may be empty when none could be read). */
	public final List<Weighted> values;
	/** Staging polish (2026-10-17): 0 when the values come from the whole table, else the rows of the sample they were read on ({@code quality.ScanGuard}). */
	public final long sampleRows;

	private ValueEvidence(final boolean pConstantOnly, final List<Weighted> pValues, final long pSampleRows)
	{
		this.constantOnly = pConstantOnly;
		this.values = pValues == null ? Collections.<Weighted>emptyList() : pValues;
		this.sampleRows = pSampleRows;
	}

	/** An attribute fed only by these constants. */
	public static ValueEvidence constants(final List<String> pConstants)
	{
		final Set<String> distinct = new LinkedHashSet<String>(pConstants == null ? Collections.<String>emptyList() : pConstants);
		final List<Weighted> values = new ArrayList<Weighted>();
		for (final String constant : distinct)
		{
			values.add(new Weighted(constant, 1L));
		}
		return new ValueEvidence(true, values, 0L);
	}

	/** An attribute read from a column: its sampled distinct values. */
	public static ValueEvidence sampled(final List<Weighted> pValues)
	{
		return new ValueEvidence(false, pValues, 0L);
	}

	/** An attribute read from a column, on the first {@code pSampleRows} rows of a table above the full-scan bound (0 or less: the whole table). */
	public static ValueEvidence sampled(final List<Weighted> pValues, final long pSampleRows)
	{
		return new ValueEvidence(false, pValues, Math.max(0L, pSampleRows));
	}

	/**
	 * The text a pipeline always outputs when its LAST step is {@code constant(...)} (quotes stripped), or null for any other pipeline - a constant
	 * followed by another step is not judged, its output is unknown here.
	 */
	public static String constantOf(final String pPipeline)
	{
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return null;
		}
		final List<TransformationCatalogue.Step> steps = TransformationCatalogue.parsePipeline(pPipeline);
		if (steps.isEmpty())
		{
			return null;
		}
		final TransformationCatalogue.Step last = steps.get(steps.size() - 1);
		if (last.name == null || !"constant".equals(last.name.trim().toLowerCase(Locale.ROOT)) || last.args == null)
		{
			return null;
		}
		String text = last.args.trim();
		if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"') && text.charAt(text.length() - 1) == text.charAt(0))
		{
			text = text.substring(1, text.length() - 1);
		}
		return text;
	}

	/** How many non-empty distinct values there are to judge a check on. */
	public int judgeable()
	{
		int count = 0;
		for (final Weighted weighted : this.values)
		{
			if (weighted.value != null && !weighted.value.isEmpty())
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * The share of rows, in percent (0-100, rounded down), whose value fully matches {@code pRegex} - the semantics of the library's {@code matches}
	 * (whole string; {@code (?i)} for the ignore-case variant). Null when there is nothing to judge or the pattern does not compile (a guard never
	 * claims what it could not measure).
	 */
	public Integer passRatePercent(final String pRegex, final boolean pIgnoreCase)
	{
		if (pRegex == null)
		{
			return null;
		}
		final Pattern pattern;
		try
		{
			pattern = Pattern.compile(pRegex, pIgnoreCase ? Pattern.CASE_INSENSITIVE : 0);
		}
		catch (final PatternSyntaxException ex)
		{
			return null;
		}
		long rows = 0L;
		long passing = 0L;
		for (final Weighted weighted : this.values)
		{
			if (weighted.value == null || weighted.value.isEmpty())
			{
				continue;
			}
			rows += weighted.count;
			if (pattern.matcher(weighted.value).matches())
			{
				passing += weighted.count;
			}
		}
		return rows == 0L ? null : Integer.valueOf((int) ((passing * 100L) / rows));
	}
}
