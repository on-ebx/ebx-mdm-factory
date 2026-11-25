package com.on.ebx.factory.staging;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;

/**
 * Staging normalisation lot D (2026-10-17): the generic per-column value profiler. Pure: reads the distinct values of
 * a landing column with their row counts (the bounded sample {@code ebx.QualitySuggester#distinctValues} already
 * reads - no extra connector call) and fills a {@link NormalisationProfile}: white-space defects, empty-ish
 * placeholders, letter case, accents, the date pattern(s) with the day/month order evidence, number shapes (decimal
 * comma or dot, thousand separators), phone-like shape (leading {@code +}, {@code 00}, national {@code 0},
 * separators), boolean words and a short list of repeated codes.
 * <p>
 * <b>Domain-free by construction</b> (the project's standing rule, CLAUDE.md sec 15: suggestions are language-model
 * first and the module never hard-codes a business domain): every decision here is a regular expression over the
 * VALUE - never a column name, a tag or a word list of a business vocabulary. The only word lists are the universal
 * ones: the empty-ish tokens {@code Detectors} already owns, and the boolean words {@code PipelineCompat} already
 * maps.
 * <p>
 * <b>Day first or month first</b>: a date written {@code a/b/yyyy} is day-first when some value has {@code a} above
 * 12, month-first when some has {@code b} above 12; when both happen the column is a mixture and no single pattern
 * converts it; when neither happens the order cannot be proven and every pattern derived from it carries {@code
 * certain = false} (the verifier then flags the link instead of guessing).
 */
public final class NormalisationProfiler
{
	public static final String ISO = "ISO";
	public static final String ISO_DT = "ISO_DT";
	public static final String DMY_SLASH = "DMY/";
	public static final String DMY_DOT = "DMY.";
	public static final String DMY_DASH = "DMY-";
	public static final String YMD_SLASH = "YMD/";
	public static final String YMD_DOT = "YMD.";
	public static final String COMPACT = "COMPACT";
	/**
	 * B2B test (2026-10-23): an OData v2 JSON date, {@code /Date(1598227200000)/} (epoch milliseconds, UTC; an optional {@code +hhmm} / {@code -hhmm} offset that does
	 * not change the date part). SAP S/4HANA's Business Partner {@code CreationDate} is written so; nothing read it as a date, the model proposed {@code date(dd/MM/yyyy)} and
	 * the value check measured 0 valid. Converted by the Connectors step {@code epochdate} (2026-10-23).
	 */
	public static final String ODATA = "ODATA";
	/** Suffix of a family whose values carry a time part. */
	public static final String TIME_SUFFIX = "+T";

	private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
	private static final Pattern ISO_DATETIME = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,]\\d+)?)?\\s*(?:Z|[+-]\\d{2}(?::?\\d{2})?)?$");
	private static final Pattern DMY = Pattern.compile("^(\\d{1,2})([/.\\-])(\\d{1,2})\\2(\\d{4})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$");
	private static final Pattern YMD = Pattern.compile("^(\\d{4})([/.])(\\d{1,2})\\2(\\d{1,2})$");
	private static final Pattern COMPACT_DATE = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
	private static final Pattern ODATA_DATE = Pattern.compile("^/Date\\((-?\\d{1,15})(?:[+-]\\d{4})?\\)/$");
	/** Epoch milliseconds of 1900-01-01 and 2100-12-31 (UTC): an OData value outside is not counted as a date. */
	private static final long ODATA_MIN_MILLIS = -2208988800000L;
	private static final long ODATA_MAX_MILLIS = 4133894400000L;

	private static final Pattern INT = Pattern.compile("^[+-]?\\d+$");
	private static final Pattern DEC_DOT = Pattern.compile("^[+-]?\\d*\\.\\d+$");
	private static final Pattern DEC_COMMA = Pattern.compile("^[+-]?\\d*,\\d+$");
	private static final Pattern THOUSAND_DOT_COMMA = Pattern.compile("^[+-]?\\d{1,3}(?:\\.\\d{3})+,\\d+$");
	private static final Pattern THOUSAND_COMMA_DOT = Pattern.compile("^[+-]?\\d{1,3}(?:,\\d{3})+\\.\\d+$");
	private static final Pattern THOUSAND_SPACE = Pattern.compile("^[+-]?\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})+(?:[.,]\\d+)?$");

	/** A phone-like value: an optional plus, then digits with a few separators; 7 to 15 digits, no letter. */
	private static final Pattern PHONE = Pattern.compile("^\\+?[\\d][\\d\\s().\\-/\\u00a0\\u202f]*$");
	private static final String PHONE_SEPARATOR_CHARS = " .-()/  ";

	private static final List<String> BOOL_TRUE = Arrays.asList("true", "1", "y", "yes", "o", "oui", "vrai", "t", "on");
	private static final List<String> BOOL_FALSE = Arrays.asList("false", "0", "n", "no", "non", "faux", "f", "off");

	private static final List<String> EMPTY_SENTINELS = Arrays.asList("N/A", "NA", "-", "?", "NULL", "NONE", "UNKNOWN", "N.C.", "N.D.");

	private NormalisationProfiler()
	{
	}

	// ------------------------------------------------------------------------------------------------------ profile

	/**
	 * @param pValues the distinct values of the column with their row counts (most frequent first, capped by the caller)
	 * @param pExactDistinct the column's exact distinct count, or a negative number when unknown
	 */
	public static NormalisationProfile profile(final List<Detectors.ValueCount> pValues, final long pExactDistinct)
	{
		final NormalisationProfile out = new NormalisationProfile();
		out.exactDistinct = pExactDistinct;
		final StringBuilder phoneSeparators = new StringBuilder();
		final java.util.LinkedHashMap<String, Long> distinctRows = new java.util.LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount entry : pValues == null ? new ArrayList<Detectors.ValueCount>() : pValues)
		{
			if (entry.value == null)
			{
				continue;
			}
			final String value = entry.value;
			final String trimmed = value.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final long rows = entry.count;
			out.filled += rows;
			out.distinctRead++;
			if (!trimmed.equals(value))
			{
				out.leadingTrailing += rows;
			}
			if (trimmed.indexOf(' ') >= 0 || trimmed.matches(".*\\s{2,}.*"))
			{
				out.innerRuns += rows;
			}
			for (final String sentinel : NormalisationProfiler.EMPTY_SENTINELS)
			{
				if (trimmed.equalsIgnoreCase(sentinel))
				{
					final Long seen = out.sentinels.get(sentinel);
					out.sentinels.put(sentinel, Long.valueOf((seen == null ? 0L : seen.longValue()) + rows));
					break;
				}
			}
			NormalisationProfiler.addCase(out, trimmed, rows);
			if (!CleaningSteps.stripAccents(trimmed).equals(trimmed))
			{
				out.accented += rows;
			}
			final boolean date = NormalisationProfiler.addDate(out, trimmed, rows);
			final boolean number = NormalisationProfiler.addNumber(out, trimmed, rows);
			// a bare run of digits (or +digits) is a number AND possibly a phone (a leading 0 or + tells it apart from an identifier, see addPhone)
			if (!date && (!number || NormalisationProfiler.INT.matcher(trimmed).matches()))
			{
				NormalisationProfiler.addPhone(out, trimmed, rows, phoneSeparators);
			}
			NormalisationProfiler.addBoolean(out, trimmed, rows);
			final Long seen = distinctRows.get(trimmed);
			distinctRows.put(trimmed, Long.valueOf((seen == null ? 0L : seen.longValue()) + rows));
		}
		out.sampled = pExactDistinct >= 0L && pExactDistinct > out.distinctRead;
		out.phoneSeparators = phoneSeparators.toString();
		if (!out.sampled && out.distinctRead >= 2 && out.distinctRead <= NormalisationProfile.MAX_CODE_DISTINCT && out.filled >= 2L * out.distinctRead)
		{
			out.codes.putAll(distinctRows);
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------------- case

	/** {@link NormalisationProfile#CASE_UPPER}... of one value, or null when it holds no letter. */
	public static String caseOf(final String pValue)
	{
		boolean anyUpper = false;
		boolean anyLower = false;
		boolean anyLetter = false;
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			if (Character.isUpperCase(c))
			{
				anyLetter = true;
				anyUpper = true;
			}
			else if (Character.isLowerCase(c))
			{
				anyLetter = true;
				anyLower = true;
			}
		}
		if (!anyLetter)
		{
			return null;
		}
		if (!anyLower)
		{
			return NormalisationProfile.CASE_UPPER;
		}
		if (!anyUpper)
		{
			return NormalisationProfile.CASE_LOWER;
		}
		// both cases present: title case when every run of letters starts with a capital and goes on in lower case
		boolean runStart = true;
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			if (!Character.isLetter(c))
			{
				runStart = true;
				continue;
			}
			if (!Character.isUpperCase(c) && !Character.isLowerCase(c))
			{
				runStart = false; // a letter without case (CJK...) neither starts nor breaks a title-case word
				continue;
			}
			if (runStart && !Character.isUpperCase(c) || !runStart && Character.isUpperCase(c))
			{
				return NormalisationProfile.CASE_MIXED;
			}
			runStart = false;
		}
		return NormalisationProfile.CASE_TITLE;
	}

	private static void addCase(final NormalisationProfile pOut, final String pValue, final long pRows)
	{
		final String kind = NormalisationProfiler.caseOf(pValue);
		if (kind == null)
		{
			pOut.letterless += pRows;
		}
		else if (NormalisationProfile.CASE_UPPER.equals(kind))
		{
			pOut.upper += pRows;
		}
		else if (NormalisationProfile.CASE_LOWER.equals(kind))
		{
			pOut.lower += pRows;
		}
		else if (NormalisationProfile.CASE_TITLE.equals(kind))
		{
			pOut.title += pRows;
		}
		else
		{
			pOut.mixed += pRows;
		}
	}

	// -------------------------------------------------------------------------------------------------------- dates

	private static void bump(final Map<String, Long> pMap, final String pKey, final long pRows)
	{
		final Long seen = pMap.get(pKey);
		pMap.put(pKey, Long.valueOf((seen == null ? 0L : seen.longValue()) + pRows));
	}

	private static boolean validDate(final int pYear, final int pMonth, final int pDay)
	{
		try
		{
			LocalDate.of(pYear, pMonth, pDay);
			return pYear >= 1000;
		}
		catch (final DateTimeException ex)
		{
			return false;
		}
	}

	private static boolean addDate(final NormalisationProfile pOut, final String pValue, final long pRows)
	{
		Matcher matcher = NormalisationProfiler.ODATA_DATE.matcher(pValue);
		if (matcher.matches())
		{
			final long millis = Long.parseLong(matcher.group(1));
			if (millis >= NormalisationProfiler.ODATA_MIN_MILLIS && millis <= NormalisationProfiler.ODATA_MAX_MILLIS)
			{
				NormalisationProfiler.bump(pOut.dateFamilies, ODATA, pRows);
			}
			return true;
		}
		matcher = NormalisationProfiler.ISO_DATE.matcher(pValue);
		if (matcher.matches())
		{
			if (NormalisationProfiler.validDate(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))))
			{
				NormalisationProfiler.bump(pOut.dateFamilies, ISO, pRows);
			}
			return true;
		}
		matcher = NormalisationProfiler.ISO_DATETIME.matcher(pValue);
		if (matcher.matches())
		{
			if (NormalisationProfiler.validDate(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))))
			{
				NormalisationProfiler.bump(pOut.dateFamilies, ISO_DT, pRows);
				if (matcher.group(6) != null)
				{
					NormalisationProfiler.bump(pOut.dateWithSeconds, ISO_DT, pRows);
				}
			}
			return true;
		}
		matcher = NormalisationProfiler.DMY.matcher(pValue);
		if (matcher.matches())
		{
			final int a = Integer.parseInt(matcher.group(1));
			final int b = Integer.parseInt(matcher.group(3));
			final int year = Integer.parseInt(matcher.group(4));
			final String separator = matcher.group(2);
			final boolean withTime = matcher.group(5) != null;
			final String family = (".".equals(separator) ? DMY_DOT : "-".equals(separator) ? DMY_DASH : DMY_SLASH) + (withTime ? TIME_SUFFIX : "");
			final boolean dayFirst = NormalisationProfiler.validDate(year, b, a);
			final boolean monthFirst = NormalisationProfiler.validDate(year, a, b);
			long[] order = pOut.dateOrder.get(family);
			if (order == null)
			{
				order = new long[4];
				pOut.dateOrder.put(family, order);
			}
			if (dayFirst && monthFirst)
			{
				order[2] += pRows;
			}
			else if (dayFirst)
			{
				order[0] += pRows;
			}
			else if (monthFirst)
			{
				order[1] += pRows;
			}
			else
			{
				order[3] += pRows;
				return true;
			}
			NormalisationProfiler.bump(pOut.dateFamilies, family, pRows);
			if (matcher.group(7) != null)
			{
				NormalisationProfiler.bump(pOut.dateWithSeconds, family, pRows);
			}
			return true;
		}
		matcher = NormalisationProfiler.YMD.matcher(pValue);
		if (matcher.matches())
		{
			if (NormalisationProfiler.validDate(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4))))
			{
				NormalisationProfiler.bump(pOut.dateFamilies, "/".equals(matcher.group(2)) ? YMD_SLASH : YMD_DOT, pRows);
			}
			return true;
		}
		matcher = NormalisationProfiler.COMPACT_DATE.matcher(pValue);
		if (matcher.matches())
		{
			final int year = Integer.parseInt(matcher.group(1));
			if (year >= 1900 && year <= 2100 && NormalisationProfiler.validDate(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))))
			{
				NormalisationProfiler.bump(pOut.dateFamilies, COMPACT, pRows);
			}
			return true;
		}
		return false;
	}

	/**
	 * What may be said to the language model about one column, as one sentence (or empty): the whole measured profile
	 * ({@link NormalisationProfile#factsLine}), or - for a column the data policy marks SENSITIVE - only how its dates and
	 * numbers are written ({@link NormalisationProfile#formatFactsLine}, lot E 2026-10-17). The single place that decides it,
	 * so a test can pin that no value of a sensitive column goes out.
	 */
	public static String promptFacts(final List<Detectors.ValueCount> pValues, final long pExactDistinct, final boolean pSensitive, final boolean pShowValues)
	{
		return NormalisationProfiler.promptFacts(pValues, pExactDistinct, 0L, pSensitive, pShowValues);
	}

	/** Staging polish (2026-10-17): the same for values read on the first {@code pSampleRows} rows of a big table (no code list is claimed from a row sample). */
	public static String promptFacts(final List<Detectors.ValueCount> pValues, final long pExactDistinct, final long pSampleRows, final boolean pSensitive,
		final boolean pShowValues)
	{
		final NormalisationProfile profile = NormalisationProfiler.profile(pValues, pExactDistinct).markRowSample(pSampleRows);
		return pSensitive ? profile.formatFactsLine() : profile.factsLine(pShowValues);
	}

	/** One way to turn a date family into the ISO form, with whether the day/month order is proven. */
	public static final class DateCandidate
	{
		/** A Connectors pipeline whose output is the ISO date ({@code yyyy-MM-dd}) or date-time ({@code yyyy-MM-dd'T'HH:mm:ss}). */
		public final String pipeline;
		public final String family;
		/** False when the pattern rests on an order no value proves (every value has day and month at or below 12). */
		public final boolean certain;
		public final long rows;

		DateCandidate(final String pPipeline, final String pFamily, final boolean pCertain, final long pRows)
		{
			this.pipeline = pPipeline;
			this.family = pFamily;
			this.certain = pCertain;
			this.rows = pRows;
		}
	}

	/**
	 * The pipelines that turn the profile's date families into the ISO form, most frequent family first; a family whose
	 * day/month order is mixed (some value proves day first, another month first) yields none.
	 *
	 * @param pTimestamp true for a date-time target ({@code yyyy-MM-dd'T'HH:mm:ss}), false for a date ({@code yyyy-MM-dd})
	 */
	public static List<DateCandidate> dateCandidates(final NormalisationProfile pProfile, final boolean pTimestamp)
	{
		final List<DateCandidate> out = new ArrayList<DateCandidate>();
		final String outPattern = pTimestamp ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd";
		for (final Map.Entry<String, Long> entry : pProfile.datesByCount())
		{
			final String family = entry.getKey();
			final long rows = entry.getValue().longValue();
			final boolean withTime = family.endsWith(TIME_SUFFIX);
			final String base = withTime ? family.substring(0, family.length() - TIME_SUFFIX.length()) : family;
			final String time = withTime ? NormalisationProfiler.timePattern(pProfile, family) : "";
			if (ODATA.equals(base))
			{
				// the milliseconds are UTC: epochdate formats them in UTC, the offset of /Date(ms+hhmm)/ does not change the date part
				out.add(new DateCandidate(pTimestamp ? "trim|epochdate(" + outPattern + ")" : "trim|epochdate", family, true, rows));
			}
			else if (ISO.equals(base))
			{
				out.add(new DateCandidate(pTimestamp ? "trim|date(yyyy-MM-dd," + outPattern + ")" : "trim", family, true, rows));
			}
			else if (ISO_DT.equals(base))
			{
				out.add(new DateCandidate(pTimestamp ? "trim|isodatetime" : "trim|isodate", family, true, rows));
			}
			else if (base.startsWith("DMY"))
			{
				final String separator = base.substring(3);
				final long[] order = pProfile.dateOrder.get(family);
				final long dayFirst = order == null ? 0L : order[0];
				final long monthFirst = order == null ? 0L : order[1];
				if (dayFirst > 0L && monthFirst > 0L)
				{
					continue; // a mixture of both orders: no single pattern converts it
				}
				final boolean month = monthFirst > 0L;
				final String datePattern = month ? "MM" + separator + "dd" + separator + "yyyy" : "dd" + separator + "MM" + separator + "yyyy";
				final boolean certain = dayFirst > 0L || monthFirst > 0L;
				out.add(new DateCandidate("trim|date(" + NormalisationProfiler.quoteIfNeeded(datePattern + time) + "," + outPattern + ")", family, certain, rows));
			}
			else if (YMD_SLASH.equals(base) || YMD_DOT.equals(base))
			{
				final String separator = base.substring(3);
				out.add(new DateCandidate("trim|date(yyyy" + separator + "MM" + separator + "dd," + outPattern + ")", family, true, rows));
			}
			else if (COMPACT.equals(base))
			{
				out.add(new DateCandidate("trim|date(yyyyMMdd," + outPattern + ")", family, true, rows));
			}
		}
		return out;
	}

	private static String timePattern(final NormalisationProfile pProfile, final String pFamily)
	{
		final Long withSeconds = pProfile.dateWithSeconds.get(pFamily);
		final Long all = pProfile.dateFamilies.get(pFamily);
		return all != null && withSeconds != null && withSeconds.longValue() == all.longValue() ? " HH:mm:ss" : " HH:mm";
	}

	private static String quoteIfNeeded(final String pPattern)
	{
		return pPattern.indexOf(' ') >= 0 ? "'" + pPattern + "'" : pPattern;
	}

	/** The pipeline {@link NormalisationProfile#factsLine} recommends for a date column: the first proven candidate that is not a mere ISO text, or null. */
	public static String suggestedDatePipeline(final NormalisationProfile pProfile)
	{
		for (final DateCandidate candidate : NormalisationProfiler.dateCandidates(pProfile, false))
		{
			if (candidate.certain && !ISO.equals(candidate.family) && !ISO_DT.equals(candidate.family))
			{
				return candidate.pipeline;
			}
		}
		return null;
	}

	/** A date family in words: {@code dd/MM/yyyy}, {@code dd/MM/yyyy or MM/dd/yyyy (order not provable)}. */
	public static String describeFamily(final NormalisationProfile pProfile, final String pFamily)
	{
		final boolean withTime = pFamily.endsWith(TIME_SUFFIX);
		final String base = withTime ? pFamily.substring(0, pFamily.length() - TIME_SUFFIX.length()) : pFamily;
		final String time = withTime ? " HH:mm" : "";
		if (ODATA.equals(base))
		{
			return "OData /Date(ms)/ (epoch milliseconds, UTC)";
		}
		if (ISO.equals(base))
		{
			return "yyyy-MM-dd";
		}
		if (ISO_DT.equals(base))
		{
			return "ISO date-time";
		}
		if (base.startsWith("DMY"))
		{
			final String separator = base.substring(3);
			final long[] order = pProfile.dateOrder.get(pFamily);
			final long dayFirst = order == null ? 0L : order[0];
			final long monthFirst = order == null ? 0L : order[1];
			if (dayFirst > 0L && monthFirst > 0L)
			{
				return "dd" + separator + "MM" + separator + "yyyy" + time + " and MM" + separator + "dd" + separator + "yyyy" + time + " mixed";
			}
			if (dayFirst > 0L)
			{
				return "dd" + separator + "MM" + separator + "yyyy" + time;
			}
			if (monthFirst > 0L)
			{
				return "MM" + separator + "dd" + separator + "yyyy" + time;
			}
			return "dd" + separator + "MM" + separator + "yyyy" + time + " or MM" + separator + "dd" + separator + "yyyy" + time + " (order not provable)";
		}
		if (YMD_SLASH.equals(base) || YMD_DOT.equals(base))
		{
			final String separator = base.substring(3);
			return "yyyy" + separator + "MM" + separator + "dd";
		}
		return "yyyyMMdd";
	}

	// ------------------------------------------------------------------------------------------------------ numbers

	private static boolean addNumber(final NormalisationProfile pOut, final String pValue, final long pRows)
	{
		final String shape;
		if (NormalisationProfiler.INT.matcher(pValue).matches())
		{
			shape = "INT";
		}
		else if (NormalisationProfiler.DEC_DOT.matcher(pValue).matches())
		{
			shape = "DEC_DOT";
		}
		else if (NormalisationProfiler.THOUSAND_DOT_COMMA.matcher(pValue).matches())
		{
			shape = "THOUSAND_DOT_COMMA";
		}
		else if (NormalisationProfiler.THOUSAND_COMMA_DOT.matcher(pValue).matches())
		{
			shape = "THOUSAND_COMMA_DOT";
		}
		else if (NormalisationProfiler.THOUSAND_SPACE.matcher(pValue).matches())
		{
			shape = "THOUSAND_SPACE";
		}
		else if (NormalisationProfiler.DEC_COMMA.matcher(pValue).matches())
		{
			shape = "DEC_COMMA";
		}
		else
		{
			return false;
		}
		NormalisationProfiler.bump(pOut.numberShapes, shape, pRows);
		return true;
	}

	// ------------------------------------------------------------------------------------------------------- phones

	private static void addPhone(final NormalisationProfile pOut, final String pValue, final long pRows, final StringBuilder pSeparators)
	{
		if (!NormalisationProfiler.PHONE.matcher(pValue).matches())
		{
			return;
		}
		int digits = 0;
		for (int i = 0; i < pValue.length(); i++)
		{
			if (Character.isDigit(pValue.charAt(i)))
			{
				digits++;
			}
		}
		if (digits < 7 || digits > 15)
		{
			return;
		}
		final boolean plus = pValue.charAt(0) == '+';
		final String bare = plus ? pValue.substring(1) : pValue;
		// a bare number made of digits only is far more often an identifier than a phone: it counts when it starts with a 0 or a 00
		final boolean digitsOnly = pValue.matches("\\d+");
		final boolean zeroStart = bare.startsWith("0");
		if (!plus && digitsOnly && !zeroStart)
		{
			return;
		}
		if (NormalisationProfiler.zeroAfterInternationalPrefix(pValue))
		{
			return;
		}
		pOut.phoneLike += pRows;
		if (plus)
		{
			pOut.phoneWithPlus += pRows;
		}
		else if (bare.startsWith("00"))
		{
			pOut.phoneWith00 += pRows;
		}
		else if (zeroStart)
		{
			pOut.phoneNationalZero += pRows;
		}
		for (int i = 0; i < bare.length(); i++)
		{
			final char c = bare.charAt(i);
			if (NormalisationProfiler.PHONE_SEPARATOR_CHARS.indexOf(c) >= 0 && pSeparators.indexOf(String.valueOf(c)) < 0)
			{
				pSeparators.append(c);
			}
		}
	}

	/**
	 * B2B test (2026-10-23): true when the value, read without a {@code +}, starts with {@code 000} - the international call prefix {@code 00} followed by a {@code 0}, while no
	 * country calling code starts with 0 (ITU-T E.164), and no national number starts with the international prefix itself. So such a value is not a phone number in any
	 * country: it is a zero-padded identifier ({@code 0001690977}, an SAP Business Partner number, was counted as a phone and made the preflight claim that SAP left a
	 * phone attribute unfed). The separators are ignored ({@code 00 01 69 09 77} is the same number).
	 */
	public static boolean zeroAfterInternationalPrefix(final String pValue)
	{
		if (pValue == null)
		{
			return false;
		}
		final String trimmed = pValue.trim();
		if (trimmed.startsWith("+"))
		{
			return false;
		}
		final StringBuilder digits = new StringBuilder();
		for (int i = 0; i < trimmed.length() && digits.length() < 3; i++)
		{
			final char c = trimmed.charAt(i);
			if (Character.isDigit(c))
			{
				digits.append(c);
			}
		}
		return "000".equals(digits.toString());
	}

	/**
	 * Staging feed-conversion lot E (2026-10-17, coordinator addition (b)): how the phone numbers of ONE attribute are written across ALL
	 * the sources that feed it. Measured live: a derivation {@code toE164WithDefault} was bound with a blank default country on the grounds
	 * that "all profiled numbers include a leading plus" - true of the one web-shop column the pass had looked at, false of the store
	 * tickets ({@code 06.01.31.09.18}, national). Counts and percentages only, no value.
	 */
	public static final class PhoneMix
	{
		/** Rows of the attribute across the profiled sources, non-blank. */
		public final long filled;
		/** Rows that look like a phone number. */
		public final long phoneRows;
		/** Of those, rows with an international prefix ({@code +} or {@code 00}). */
		public final long internationalRows;
		/** Of those, rows in national format (a leading 0 and no international prefix): they need a default country to be completed. */
		public final long nationalRows;
		public final int sources;

		PhoneMix(final long pFilled, final long pPhoneRows, final long pInternational, final long pNational, final int pSources)
		{
			this.filled = pFilled;
			this.phoneRows = pPhoneRows;
			this.internationalRows = pInternational;
			this.nationalRows = pNational;
			this.sources = pSources;
		}

		/** True when most of the rows are phone-like. */
		public boolean isPhoneLike()
		{
			return this.filled > 0L && this.phoneRows * 100L >= this.filled * NormalisationProfile.DOMINANT_PERCENT;
		}

		/** True when a phone-like attribute holds national-format numbers: a function that completes them needs a default country. */
		public boolean needsDefaultCountry()
		{
			return this.isPhoneLike() && this.nationalRows > 0L;
		}

		/** One sentence for the model, or empty when the attribute is not phone-like. A format and shares of rows, never a value. */
		public String sentence()
		{
			if (!this.isPhoneLike())
			{
				return "";
			}
			final StringBuilder out = new StringBuilder("phone-like across ").append(this.sources).append(this.sources == 1 ? " source: " : " sources: ");
			out.append(NormalisationProfiler.percent(this.internationalRows, this.phoneRows)).append("% written with an international prefix (+ or 00), ")
				.append(NormalisationProfiler.percent(this.nationalRows, this.phoneRows)).append("% in national format (leading 0, no international prefix)");
			if (this.nationalRows > 0L)
			{
				out.append(" - the national ones carry no country: a function that completes them needs a default country, never a blank one");
			}
			return out.append('.').toString();
		}
	}

	/** The mix of {@code pProfiles} (one per source column feeding the same attribute), skipping the ones with nothing read. */
	public static PhoneMix phoneMix(final List<NormalisationProfile> pProfiles)
	{
		long filled = 0L;
		long phone = 0L;
		long international = 0L;
		long national = 0L;
		int sources = 0;
		for (final NormalisationProfile profile : pProfiles == null ? new ArrayList<NormalisationProfile>() : pProfiles)
		{
			if (profile == null || profile.filled == 0L)
			{
				continue;
			}
			sources++;
			filled += profile.filled;
			phone += profile.phoneLike;
			international += profile.phoneWithPlus + profile.phoneWith00;
			national += profile.phoneNationalZero;
		}
		return new PhoneMix(filled, phone, international, national, sources);
	}

	private static int percent(final long pPart, final long pTotal)
	{
		return pTotal <= 0L ? 0 : (int) Math.round(100.0 * pPart / pTotal);
	}

	// ------------------------------------------------------------------------------------------------------ booleans

	/** {@code TRUE}, {@code FALSE} or null for a word that is neither. */
	public static Boolean booleanWord(final String pValue)
	{
		final String word = pValue == null ? "" : pValue.trim().toLowerCase(Locale.ROOT);
		if (NormalisationProfiler.BOOL_TRUE.contains(word))
		{
			return Boolean.TRUE;
		}
		if (NormalisationProfiler.BOOL_FALSE.contains(word))
		{
			return Boolean.FALSE;
		}
		return null;
	}

	private static void addBoolean(final NormalisationProfile pOut, final String pValue, final long pRows)
	{
		final Boolean word = NormalisationProfiler.booleanWord(pValue);
		if (word == null)
		{
			pOut.boolOther += pRows;
		}
		else if (word.booleanValue())
		{
			pOut.boolTrue += pRows;
		}
		else
		{
			pOut.boolFalse += pRows;
		}
	}
}
