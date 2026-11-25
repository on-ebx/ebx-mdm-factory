package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Staging normalisation lot D (2026-10-17, docs/UX-STAGING-TEST-FIXES-D.md): what the values of ONE landing column
 * look like, measured, as counts only - the generic, data-driven value profile the normalisation pass proposes steps
 * from and the transform verifier proposes corrections from ({@link NormalisationProfiler} fills it). Pure: no EBX
 * class. <b>Nothing in it reads a column name, a tag or a business vocabulary</b>: a date pattern, a letter case, a
 * phone-like shape, a decimal comma and a short list of repeated codes are properties of the VALUES, true of a
 * customer file exactly as of a product file.
 * <p>
 * Every count is weighted by the number of rows that carry the value (the profiler reads distinct values with their
 * row counts); {@link #filled} is the number of non-blank rows read. {@link #sampled} tells that the distinct values
 * read were fewer than the column's exact distinct count (the profile then describes the most frequent ones).
 * <p>
 * Real values live in this object only where a decision needs them ({@link #codes}, the sentinel tokens): the first is
 * kept for the deterministic reference matching and shown to a language model only when the project's data policy
 * allows real values ({@link #factsLine}); the second is drawn from a fixed, published list of empty-ish tokens.
 */
public final class NormalisationProfile
{
	public static final String CASE_UPPER = "UPPER";
	public static final String CASE_LOWER = "LOWER";
	public static final String CASE_TITLE = "TITLE";
	public static final String CASE_MIXED = "MIXED";

	/** A column with at most this many distinct values, each repeated on average, is a list of codes. */
	public static final int MAX_CODE_DISTINCT = 30;
	/** Share of the non-blank values (percent) a shape must reach to be called the column's shape. */
	public static final int DOMINANT_PERCENT = 80;

	/** Non-blank rows read. */
	public long filled;
	/** Distinct non-blank values read. */
	public int distinctRead;
	/** The column's exact distinct count, or -1 when unknown. */
	public long exactDistinct = -1L;
	public boolean sampled;
	/**
	 * Staging polish (2026-10-17): 0 when the values come from the whole table; otherwise the values were read on the first N rows of a table above
	 * {@code mdm.factory.profile.maxRowsFullScan} ({@code quality.ScanGuard}), which {@link #sampled} also says. A profile of a row sample never claims a list
	 * of codes ({@link #codes} stays empty): a value that does not appear in the first rows may still exist.
	 */
	public long sampleRows;

	// -------------------------------------------------------------------------------------------------- spaces
	/** Rows whose value has a leading or trailing white space. */
	public long leadingTrailing;
	/** Rows whose value has two or more consecutive inner white spaces, or a non-breaking space. */
	public long innerRuns;

	/** Empty-ish tokens (from the fixed list of {@code Detectors}) found, with their rows. */
	public final Map<String, Long> sentinels = new LinkedHashMap<String, Long>();

	// ---------------------------------------------------------------------------------------------------- case
	public long upper;
	public long lower;
	public long title;
	public long mixed;
	/** Rows with no letter at all (digits, punctuation): no case to speak of. */
	public long letterless;
	/** Rows with an accented letter (a letter that {@code CleaningSteps#stripAccents} would change). */
	public long accented;

	// ---------------------------------------------------------------------------------------------------- dates
	/** Date family (see {@link NormalisationProfiler}) -&gt; rows. */
	public final Map<String, Long> dateFamilies = new LinkedHashMap<String, Long>();
	/** Date family -&gt; {day first only, month first only, ambiguous, invalid} rows, for the families written {@code a/b/yyyy}. */
	public final Map<String, long[]> dateOrder = new LinkedHashMap<String, long[]>();
	/** Date family -&gt; rows written without a leading zero ({@code 3/6/1963}). */
	public final Map<String, Long> dateUnpadded = new LinkedHashMap<String, Long>();
	/** Date family -&gt; rows with a time part that has seconds. */
	public final Map<String, Long> dateWithSeconds = new LinkedHashMap<String, Long>();

	// -------------------------------------------------------------------------------------------------- numbers
	/** Number shape ({@code INT}, {@code DEC_DOT}, {@code DEC_COMMA}, {@code THOUSAND_...}) -&gt; rows. */
	public final Map<String, Long> numberShapes = new LinkedHashMap<String, Long>();

	// --------------------------------------------------------------------------------------------------- phones
	public long phoneLike;
	public long phoneWithPlus;
	public long phoneWith00;
	public long phoneNationalZero;
	/** The separator characters found in phone-like values, in a fixed order, without the {@code +}. */
	public String phoneSeparators = "";

	// -------------------------------------------------------------------------------------------------- booleans
	public long boolTrue;
	public long boolFalse;
	public long boolOther;

	// ------------------------------------------------------------------------------------------------------ codes
	/** The values and their rows when the column is a short list of repeated codes ({@link #isCodeList}); otherwise empty. */
	public final Map<String, Long> codes = new LinkedHashMap<String, Long>();

	// ----------------------------------------------------------------------------------------------------- reads

	/** Marks this profile as drawn from the first {@code pSampleRows} rows of the table (0 or less: no change). */
	public NormalisationProfile markRowSample(final long pSampleRows)
	{
		if (pSampleRows > 0L)
		{
			this.sampleRows = pSampleRows;
			this.sampled = true;
			this.codes.clear();
		}
		return this;
	}

	public long letterBearing()
	{
		return this.upper + this.lower + this.title + this.mixed;
	}

	/** The dominant case ({@link #CASE_UPPER}...), or null when no kind reaches {@link #DOMINANT_PERCENT} of the letter-bearing rows. */
	public String dominantCase()
	{
		final long total = this.letterBearing();
		if (total == 0L)
		{
			return null;
		}
		final String[] names = { CASE_UPPER, CASE_LOWER, CASE_TITLE, CASE_MIXED };
		final long[] counts = { this.upper, this.lower, this.title, this.mixed };
		for (int i = 0; i < counts.length; i++)
		{
			if (counts[i] * 100L >= total * DOMINANT_PERCENT)
			{
				return names[i];
			}
		}
		return null;
	}

	/** Rows of the given case kind. */
	public long caseCount(final String pKind)
	{
		if (CASE_UPPER.equals(pKind))
		{
			return this.upper;
		}
		if (CASE_LOWER.equals(pKind))
		{
			return this.lower;
		}
		if (CASE_TITLE.equals(pKind))
		{
			return this.title;
		}
		return this.mixed;
	}

	/** True when most values hold letters (a column of digits or codes of digits has no case worth aligning). */
	public boolean mostlyLetters()
	{
		return this.filled > 0L && this.letterBearing() * 100L >= this.filled * DOMINANT_PERCENT;
	}

	public boolean isPhoneLike()
	{
		return this.filled > 0L && this.phoneLike * 100L >= this.filled * DOMINANT_PERCENT;
	}

	/** A short list of codes: at most {@link #MAX_CODE_DISTINCT} distinct values, each repeated on average at least twice. */
	public boolean isCodeList()
	{
		return !this.codes.isEmpty();
	}

	/** Rows that parse as some date family. */
	public long dateRows()
	{
		long total = 0L;
		for (final Long count : this.dateFamilies.values())
		{
			total += count.longValue();
		}
		return total;
	}

	public boolean isDateLike()
	{
		return this.filled > 0L && this.dateRows() * 100L >= this.filled * DOMINANT_PERCENT;
	}

	public long numberRows()
	{
		long total = 0L;
		for (final Long count : this.numberShapes.values())
		{
			total += count.longValue();
		}
		return total;
	}

	/** Rows that start with a plus sign followed by a digit. */
	public long leadingPlusRows()
	{
		return this.phoneWithPlus;
	}

	/** The date families, most frequent first. */
	public List<Map.Entry<String, Long>> datesByCount()
	{
		final List<Map.Entry<String, Long>> out = new ArrayList<Map.Entry<String, Long>>(this.dateFamilies.entrySet());
		Collections.sort(out, new java.util.Comparator<Map.Entry<String, Long>>()
		{
			@Override
			public int compare(final Map.Entry<String, Long> pLeft, final Map.Entry<String, Long> pRight)
			{
				return pRight.getValue().compareTo(pLeft.getValue());
			}
		});
		return out;
	}

	private static int percent(final long pPart, final long pTotal)
	{
		return pTotal <= 0L ? 0 : (int) Math.round(100.0 * pPart / pTotal);
	}

	/** {@code "date written dd/MM/yyyy (100%) - convert it with ..."}, or null when the column is not date-like. A FORMAT and percentages: no value. */
	private String dateFact()
	{
		if (!this.isDateLike())
		{
			return null;
		}
		final StringBuilder text = new StringBuilder("date written ");
		int shown = 0;
		for (final Map.Entry<String, Long> entry : this.datesByCount())
		{
			if (shown++ > 0)
			{
				text.append(" and ");
			}
			text.append(NormalisationProfiler.describeFamily(this, entry.getKey())).append(" (").append(percent(entry.getValue().longValue(), this.filled))
				.append("%)");
			if (shown >= 3)
			{
				break;
			}
		}
		final String pipeline = NormalisationProfiler.suggestedDatePipeline(this);
		if (pipeline != null)
		{
			text.append(" - convert it with ").append(pipeline).append(", not isodate() (which only cuts an ISO text)");
		}
		return text.toString();
	}

	/** {@code "number written decimal comma (100%)"}, or null when the column is not number-like. A FORMAT and percentages: no value. */
	private String numberFact()
	{
		if (this.numberRows() * 100L < this.filled * DOMINANT_PERCENT)
		{
			return null;
		}
		final StringBuilder text = new StringBuilder("number written ");
		int shown = 0;
		for (final Map.Entry<String, Long> entry : this.numberShapes.entrySet())
		{
			if (shown++ > 0)
			{
				text.append(" and ");
			}
			text.append(entry.getKey().toLowerCase(Locale.ROOT).replace('_', ' ')).append(" (").append(percent(entry.getValue().longValue(), this.filled)).append("%)");
		}
		return text.toString();
	}

	/**
	 * Staging feed-conversion lot E (2026-10-17): the only facts a column the data policy marks SENSITIVE may show the model - how a
	 * date or a number is WRITTEN ({@code ", measured: date written dd/MM/yyyy (100%) - convert it with ..."}), never a value, an example, a
	 * shape, a case, a phone shape or a list of codes. A format name and its share of the rows say nothing of any one row; without
	 * them the model cannot convert a birth date it cannot see (TiersB2CRetestStaging, loyalty {@code date_naissance}, dd/MM/yyyy).
	 * Empty when the column is neither date-like nor number-like.
	 */
	public String formatFactsLine()
	{
		if (this.filled == 0L)
		{
			return "";
		}
		final List<String> facts = new ArrayList<String>();
		final String dateFact = this.dateFact();
		if (dateFact != null)
		{
			facts.add(dateFact);
		}
		final String numberFact = this.numberFact();
		if (numberFact != null)
		{
			facts.add(numberFact);
		}
		if (facts.isEmpty())
		{
			return "";
		}
		return ", measured (format only): " + String.join("; ", facts);
	}

	/**
	 * The profile as the short, value-free sentence the language model reads next to a column ({@code ", formats: ..."}),
	 * empty when there is nothing worth saying. Counts, percentages and format names only - never a value; the short
	 * list of codes only when {@code pShowValues}.
	 */
	public String factsLine(final boolean pShowValues)
	{
		final List<String> facts = new ArrayList<String>();
		if (this.filled == 0L)
		{
			return "";
		}
		final String dateFact = this.dateFact();
		if (dateFact != null)
		{
			facts.add(dateFact);
		}
		final String numberFact = this.numberFact();
		if (numberFact != null)
		{
			facts.add(numberFact);
		}
		if (this.isPhoneLike())
		{
			final StringBuilder text = new StringBuilder("phone-like (");
			text.append(percent(this.phoneWithPlus, this.phoneLike)).append("% start with +, ").append(percent(this.phoneNationalZero, this.phoneLike))
				.append("% national format");
			if (!this.phoneSeparators.isEmpty())
			{
				text.append(", separators ").append(this.phoneSeparators.replace(" ", "NBSP"));
			}
			text.append("): never strip the leading + (digits() would), the rule pass already removes the separators");
			facts.add(text.toString());
		}
		if (this.mostlyLetters())
		{
			final StringBuilder text = new StringBuilder("case ");
			final String[] names = { CASE_UPPER, CASE_LOWER, CASE_TITLE, CASE_MIXED };
			int shown = 0;
			for (final String name : names)
			{
				final long count = this.caseCount(name);
				if (count == 0L)
				{
					continue;
				}
				if (shown++ > 0)
				{
					text.append(", ");
				}
				text.append(name.toLowerCase(Locale.ROOT)).append(' ').append(percent(count, this.letterBearing())).append('%');
			}
			facts.add(text.toString());
		}
		if (this.leadingTrailing > 0L || this.innerRuns > 0L)
		{
			facts.add("spaces to clean in " + percent(this.leadingTrailing + this.innerRuns, this.filled) + "% of the values (the rule pass adds trim/collapse)");
		}
		if (!this.sentinels.isEmpty())
		{
			long rows = 0L;
			for (final Long count : this.sentinels.values())
			{
				rows += count.longValue();
			}
			facts.add("empty-ish placeholders in " + percent(rows, this.filled) + "% of the values (the rule pass adds empty-to-null)");
		}
		if (this.isCodeList())
		{
			final StringBuilder text = new StringBuilder("short list of ").append(this.codes.size()).append(" repeated codes");
			if (pShowValues)
			{
				text.append(": ");
				int shown = 0;
				for (final String code : this.codes.keySet())
				{
					if (shown++ > 0)
					{
						text.append(", ");
					}
					text.append(code);
					if (shown >= 12)
					{
						break;
					}
				}
			}
			facts.add(text.toString());
		}
		if (facts.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(", measured: ");
		for (int i = 0; i < facts.size(); i++)
		{
			if (i > 0)
			{
				out.append("; ");
			}
			out.append(facts.get(i));
		}
		return out.toString();
	}
}
