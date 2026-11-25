package com.on.ebx.factory.staging;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.quality.Detectors;

/**
 * Lot F2b (2026-10-19, staging proposal): the SPAN of a date column - the earliest and the latest month its values fall in, and how many rows fall in the last 24 months - measured
 * on the distinct values the shape profiler already reads (no second read), and the comparison of the spans of the columns that feed ONE date attribute.
 * <p>
 * <b>Why</b> (run 8 of 2026-10-19, project E2EMASTER20261019203130): {@code Personne.birthDate} was fed from the two sources' real birth dates AND from the ticket table's purchase date
 * (2026-01..2026-10): 234 purchase dates in a birth-date attribute. The inventory gave a date column's FORMAT but not its SPAN, and nothing compared the sources feeding one attribute.
 * <p>
 * <b>Facts only.</b> Nothing here says that a column "is" a birth date or a purchase date, or which source is wrong: the spans are counted, and when the spans of the columns feeding one
 * attribute fall in groups that do not overlap, the Factory says so with the numbers and asks the model to fix the mapping or to say in a note why they belong together.
 * <p>
 * <b>Precision</b>: month. A date written {@code dd/MM/yyyy} or {@code MM/dd/yyyy} whose order the column's own values do not prove (no value has a first part above 12, or none a second part
 * above 12, or both do) is kept at the YEAR - guessing the order would invent a month; the span then reads {@code 1950..2006}. Pure; the clock is a parameter.
 */
public final class DateSpans
{
	/** A column is a date column when at least this share of its filled rows parse as a date (the profiler's own dominance bar). */
	public static final int MIN_DATE_PERCENT = NormalisationProfile.DOMINANT_PERCENT;
	/** The window of {@code N rows in the last 24 months}. */
	public static final int RECENT_MONTHS = 24;
	/** Years outside this range are not dates here (a compact 8-digit number is far more often something else). */
	static final int MIN_YEAR = 1800;
	static final int MAX_YEAR = 2200;
	/** Distinct values read per column by the shape profiler ({@code StagingLlmSuggester}); a column with more distinct values is measured on its most frequent ones. */
	public static final int VALUES_READ_CAP = 200;

	private static final Pattern ISO = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ].*)?$");
	private static final Pattern YMD = Pattern.compile("^(\\d{4})[/.](\\d{1,2})[/.](\\d{1,2})$");
	private static final Pattern DMY = Pattern.compile("^(\\d{1,2})([/.\\-])(\\d{1,2})\\2(\\d{4})(?:[ T].*)?$");
	private static final Pattern COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
	private static final Pattern JAVA_DATE = Pattern.compile("^\\w{3} (\\w{3}) \\d{1,2} [\\d:]{8} \\S+ (\\d{4})$");
	private static final String MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec";
	/** 2026-10-23 (B2B test): an OData v2 JSON date, epoch milliseconds in UTC (the offset of /Date(ms+hhmm)/ does not change the month). */
	private static final Pattern ODATA = Pattern.compile("^/Date\\((-?\\d{1,15})(?:[+-]\\d{4})?\\)/$");

	private DateSpans()
	{
	}

	/** The months a column's dates fall in, from {@link #measure}. */
	public static final class Span
	{
		/** {@code year * 12 + month - 1} of the earliest value (a year-only value counts from January). */
		public final int minMonth;
		/** Same for the latest (a year-only value counts to December). */
		public final int maxMonth;
		/** False when some value was kept at the year (a day/month order the column does not prove). */
		public final boolean monthPrecision;
		/** Rows whose value parsed as a date. */
		public final long filledRows;
		/** Rows whose date is within the last {@value #RECENT_MONTHS} months (up to the present month); when the order of a value is unknown, only if every reading is. */
		public final long recentRows;
		/** False when the figures are drawn from the most frequent distinct values only (a column with more distinct values than were read, or a sampled table). */
		public final boolean complete;
		/** Distinct values the span covers. */
		public final int valuesRead;

		Span(final int pMinMonth, final int pMaxMonth, final boolean pMonthPrecision, final long pFilledRows, final long pRecentRows, final boolean pComplete, final int pValuesRead)
		{
			this.minMonth = pMinMonth;
			this.maxMonth = pMaxMonth;
			this.monthPrecision = pMonthPrecision;
			this.filledRows = pFilledRows;
			this.recentRows = pRecentRows;
			this.complete = pComplete;
			this.valuesRead = pValuesRead;
		}

		/** {@code 1951-03..2005-12}, or {@code 1950..2006} at year precision. */
		public String range()
		{
			return this.monthPrecision ? DateSpans.monthText(this.minMonth) + ".." + DateSpans.monthText(this.maxMonth) : this.minMonth / 12 + ".." + this.maxMonth / 12;
		}

		/**
		 * What the inventory carries beside a date column: {@code values from 1951-03 to 2005-12 (filled rows: 160)}, then {@code , 234 rows in the last 24 months} when above 0; a column read on
		 * its most frequent values only says so.
		 */
		public String inventoryText()
		{
			final String from = this.monthPrecision ? DateSpans.monthText(this.minMonth) : String.valueOf(this.minMonth / 12);
			final String to = this.monthPrecision ? DateSpans.monthText(this.maxMonth) : String.valueOf(this.maxMonth / 12);
			final StringBuilder out = new StringBuilder("values from ").append(from).append(" to ").append(to).append(" (filled rows: ").append(this.filledRows);
			if (!this.complete)
			{
				out.append("; measured on the ").append(this.valuesRead).append(" most frequent distinct values");
			}
			out.append(')');
			if (this.recentRows > 0L)
			{
				out.append(", ").append(this.recentRows).append(" row").append(this.recentRows == 1L ? "" : "s").append(" in the last ").append(DateSpans.RECENT_MONTHS).append(" months");
			}
			return out.toString();
		}

		/** Whether the two spans share a month (a year-only span covers its whole year). */
		public boolean overlaps(final Span pOther)
		{
			return this.minMonth <= pOther.maxMonth && pOther.minMonth <= this.maxMonth;
		}
	}

	private static String monthText(final int pMonths)
	{
		return String.format(java.util.Locale.ROOT, "%04d-%02d", Integer.valueOf(pMonths / 12), Integer.valueOf(pMonths % 12 + 1));
	}

	// ---------------------------------------------------------------------------------------------------- measure

	/** One value read: its candidate months (one when the month is known, two when a day/month order is not proven) and whether it is at year precision. */
	private static final class Parsed
	{
		final int year;
		/** Month (1-12) read first-part-as-day; -1 when none (ISO...). */
		final int dayFirstMonth;
		final int monthFirstMonth;
		final int knownMonth;

		Parsed(final int pYear, final int pKnownMonth, final int pDayFirstMonth, final int pMonthFirstMonth)
		{
			this.year = pYear;
			this.knownMonth = pKnownMonth;
			this.dayFirstMonth = pDayFirstMonth;
			this.monthFirstMonth = pMonthFirstMonth;
		}
	}

	/**
	 * The span of a column from its distinct values with their row counts; null when the column is not a date column (fewer than {@value #MIN_DATE_PERCENT}% of its filled rows parse as a
	 * date) or nothing is filled.
	 *
	 * @param pPartial true when the values are not all the distinct values of the column (more distinct values than were read, or a table read on a sample)
	 * @param pToday the date "the last 24 months" is counted back from
	 */
	public static Span measure(final List<Detectors.ValueCount> pValues, final boolean pPartial, final LocalDate pToday)
	{
		if (pValues == null)
		{
			return null;
		}
		long filled = 0L;
		long parsedRows = 0L;
		boolean dayFirstProven = false;
		boolean monthFirstProven = false;
		final List<Parsed> parsed = new ArrayList<Parsed>();
		final List<Long> rows = new ArrayList<Long>();
		for (final Detectors.ValueCount entry : pValues)
		{
			if (entry.value == null || entry.value.trim().isEmpty() || entry.count <= 0L)
			{
				continue;
			}
			filled += entry.count;
			final String value = entry.value.trim();
			final Parsed one = DateSpans.parse(value);
			if (one == null)
			{
				continue;
			}
			parsedRows += entry.count;
			parsed.add(one);
			rows.add(Long.valueOf(entry.count));
			if (one.knownMonth < 0)
			{
				// a value valid in ONE order only proves that order for the column
				dayFirstProven |= one.dayFirstMonth > 0 && one.monthFirstMonth < 0;
				monthFirstProven |= one.monthFirstMonth > 0 && one.dayFirstMonth < 0;
			}
		}
		if (filled == 0L || parsedRows * 100L < filled * DateSpans.MIN_DATE_PERCENT)
		{
			return null;
		}
		// the order of the ambiguous values: proven day-first, proven month-first, or unknown (year precision)
		final boolean orderKnown = dayFirstProven != monthFirstProven;
		boolean monthPrecision = true;
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		long recent = 0L;
		final int todayMonth = pToday.getYear() * 12 + pToday.getMonthValue() - 1;
		for (int i = 0; i < parsed.size(); i++)
		{
			final Parsed one = parsed.get(i);
			int lo;
			int hi;
			boolean inWindow;
			if (one.knownMonth > 0)
			{
				lo = one.year * 12 + one.knownMonth - 1;
				hi = lo;
				inWindow = DateSpans.recent(lo, todayMonth);
			}
			else if (orderKnown)
			{
				// day/month order proven by the column's own values: the month is the second part (day first) or the first (month first)
				final int month = dayFirstProven ? one.dayFirstMonth : one.monthFirstMonth;
				if (month < 0)
				{
					continue; // not a valid date in the proven order: not counted
				}
				lo = one.year * 12 + month - 1;
				hi = lo;
				inWindow = DateSpans.recent(lo, todayMonth);
			}
			else
			{
				monthPrecision = false;
				lo = one.year * 12;
				hi = one.year * 12 + 11;
				// year precision: counted as recent only when the whole year is
				inWindow = DateSpans.recent(lo, todayMonth) && DateSpans.recent(hi, todayMonth);
			}
			min = Math.min(min, lo);
			max = Math.max(max, hi);
			if (inWindow)
			{
				recent += rows.get(i).longValue();
			}
		}
		if (min == Integer.MAX_VALUE)
		{
			return null;
		}
		long counted = 0L;
		for (int i = 0; i < parsed.size(); i++)
		{
			counted += rows.get(i).longValue();
		}
		return new Span(min, max, monthPrecision, counted, recent, !pPartial, parsed.size());
	}

	private static boolean recent(final int pMonth, final int pTodayMonth)
	{
		return pMonth <= pTodayMonth && pMonth >= pTodayMonth - DateSpans.RECENT_MONTHS;
	}

	/** One value as a date; null when it is not one. */
	private static Parsed parse(final String pValue)
	{
		Matcher matcher = DateSpans.ODATA.matcher(pValue);
		if (matcher.matches())
		{
			final java.time.LocalDate day = java.time.Instant.ofEpochMilli(Long.parseLong(matcher.group(1))).atZone(java.time.ZoneOffset.UTC).toLocalDate();
			return DateSpans.known(day.getYear(), day.getMonthValue(), day.getDayOfMonth());
		}
		matcher = DateSpans.ISO.matcher(pValue);
		if (matcher.matches())
		{
			return DateSpans.known(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
		}
		matcher = DateSpans.YMD.matcher(pValue);
		if (matcher.matches())
		{
			return DateSpans.known(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
		}
		matcher = DateSpans.COMPACT.matcher(pValue);
		if (matcher.matches())
		{
			return DateSpans.known(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
		}
		matcher = DateSpans.DMY.matcher(pValue);
		if (matcher.matches())
		{
			final int a = Integer.parseInt(matcher.group(1));
			final int b = Integer.parseInt(matcher.group(3));
			final int year = Integer.parseInt(matcher.group(4));
			if (year < DateSpans.MIN_YEAR || year > DateSpans.MAX_YEAR)
			{
				return null;
			}
			final boolean dayFirst = DateSpans.validDate(year, b, a);
			final boolean monthFirst = DateSpans.validDate(year, a, b);
			if (!dayFirst && !monthFirst)
			{
				return null;
			}
			if (dayFirst && monthFirst && a == b)
			{
				return new Parsed(year, a, -1, -1); // 05/05/yyyy: the same month either way
			}
			return new Parsed(year, -1, dayFirst ? b : -1, monthFirst ? a : -1);
		}
		matcher = DateSpans.JAVA_DATE.matcher(pValue);
		if (matcher.matches())
		{
			final int at = DateSpans.MONTHS.indexOf(matcher.group(1));
			final int year = Integer.parseInt(matcher.group(2));
			if (at >= 0 && at % 3 == 0 && year >= DateSpans.MIN_YEAR && year <= DateSpans.MAX_YEAR)
			{
				return new Parsed(year, at / 3 + 1, -1, -1);
			}
		}
		return null;
	}

	private static Parsed known(final int pYear, final int pMonth, final int pDay)
	{
		return DateSpans.validDate(pYear, pMonth, pDay) ? new Parsed(pYear, pMonth, -1, -1) : null;
	}

	private static boolean validDate(final int pYear, final int pMonth, final int pDay)
	{
		if (pYear < DateSpans.MIN_YEAR || pYear > DateSpans.MAX_YEAR || pMonth < 1 || pMonth > 12 || pDay < 1)
		{
			return false;
		}
		return pDay <= java.time.YearMonth.of(pYear, pMonth).lengthOfMonth();
	}

	// ----------------------------------------------------------------------------------- one attribute, several sources

	/** One column feeding a date attribute, with its span. */
	public static final class Source
	{
		public final String landingTableKey;
		public final String column;
		public final Span span;

		Source(final String pLandingTableKey, final String pColumn, final Span pSpan)
		{
			this.landingTableKey = pLandingTableKey;
			this.column = pColumn;
			this.span = pSpan;
		}

		/** {@code ECOM.customers.birth_date}. */
		public String name()
		{
			return this.landingTableKey + "." + this.column;
		}

		/** {@code ECOM.customers.birth_date (1951-03..2005-12, 160 rows)}. */
		public String describe()
		{
			return this.name() + " (" + this.span.range() + ", " + this.span.filledRows + " row" + (this.span.filledRows == 1L ? "" : "s") + ")";
		}
	}

	/**
	 * A date attribute fed by several columns whose spans do not all overlap: the sources in feed order and the groups of mutually overlapping spans (connected by overlap), each group a list of
	 * indexes into {@link #sources}. Two or more groups = the same attribute would hold dates from separate periods.
	 */
	public static final class Disjoint
	{
		public final String entity;
		public final String attribute;
		public final List<Source> sources;
		public final List<List<Integer>> groups;

		Disjoint(final String pEntity, final String pAttribute, final List<Source> pSources, final List<List<Integer>> pGroups)
		{
			this.entity = pEntity;
			this.attribute = pAttribute;
			this.sources = pSources;
			this.groups = pGroups;
		}

		/** {@code ECOM.customers.birth_date (1951-03..2005-12, 160 rows), LOYALTY.x.d (1950..2006, 140 rows), STORES.t.DATE_ACHAT (2026-01..2026-10, 234 rows)}. */
		public String sourcesText()
		{
			final List<String> parts = new ArrayList<String>();
			for (final Source source : this.sources)
			{
				parts.add(source.describe());
			}
			return String.join(", ", parts);
		}

		/** {@code [ECOM.customers.birth_date, LOYALTY.x.d] and [STORES.t.DATE_ACHAT]}: the groups of overlapping spans. */
		public String groupsText()
		{
			return this.groupsText("and");
		}

		/** The groups joined by the given word (the viewer's language). */
		public String groupsText(final String pAnd)
		{
			final List<String> parts = new ArrayList<String>();
			for (final List<Integer> group : this.groups)
			{
				final List<String> names = new ArrayList<String>();
				for (final Integer index : group)
				{
					names.add(this.sources.get(index.intValue()).name());
				}
				parts.add("[" + String.join(", ", names) + "]");
			}
			return String.join(" " + pAnd + " ", parts);
		}

		/** The sentence of the follow-up turn: the spans, then the fact that they fall in groups that do not overlap. A fact and a request, no verdict. */
		public String followUpText()
		{
			return this.entity + "." + this.attribute + " is fed from " + this.sourcesText() + ": the spans fall in " + this.groups.size() + " groups that do not overlap each other ("
				+ this.groupsText() + ") - the same attribute would hold dates from separate periods. Fix the mapping (propose_feed again on the same entity and table with the rule for "
				+ this.attribute + " replaced, or sent with \"remove\": true to drop it), or say in a note why they belong together.";
		}
	}

	/** One rule of the proposal, reduced to what this check reads: entity, attribute, the feed's table, the column. */
	public static final class Rule
	{
		public final String entity;
		public final String attribute;
		public final String landingTableKey;
		public final String column;

		public Rule(final String pEntity, final String pAttribute, final String pLandingTableKey, final String pColumn)
		{
			this.entity = pEntity;
			this.attribute = pAttribute;
			this.landingTableKey = pLandingTableKey;
			this.column = pColumn;
		}
	}

	/**
	 * The date attributes fed by several columns whose spans do not overlap. For each (entity, attribute) of {@code pDateAttributes} ({@code "Entity|attribute"} of the attributes typed {@code DATE}
	 * or {@code TIMESTAMP}) fed by at least two columns that each have a span: the spans are grouped by overlap; two groups or more = a {@link Disjoint}. Overlapping spans are silent, a column
	 * without span (not a date column, not read) is left out, and a string attribute is never looked at.
	 */
	public static List<Disjoint> disjoint(final java.util.Set<String> pDateAttributes, final List<Rule> pRules, final Map<String, StagingRules.LandingTableInfo> pTables)
	{
		final Map<String, List<Source>> byAttribute = new LinkedHashMap<String, List<Source>>();
		for (final Rule rule : pRules)
		{
			final String key = rule.entity + "|" + rule.attribute;
			if (!pDateAttributes.contains(key))
			{
				continue;
			}
			final StagingRules.LandingTableInfo table = pTables.get(rule.landingTableKey);
			if (table == null)
			{
				continue;
			}
			for (final StagingRules.LandingColumnInfo column : table.columns)
			{
				if (column.fieldName.equals(rule.column) && column.dateSpan != null)
				{
					List<Source> sources = byAttribute.get(key);
					if (sources == null)
					{
						sources = new ArrayList<Source>();
						byAttribute.put(key, sources);
					}
					boolean seen = false;
					for (final Source source : sources)
					{
						seen |= source.landingTableKey.equals(rule.landingTableKey) && source.column.equals(rule.column);
					}
					if (!seen)
					{
						sources.add(new Source(rule.landingTableKey, rule.column, column.dateSpan));
					}
				}
			}
		}
		final List<Disjoint> out = new ArrayList<Disjoint>();
		for (final Map.Entry<String, List<Source>> entry : byAttribute.entrySet())
		{
			final List<Source> sources = entry.getValue();
			if (sources.size() < 2)
			{
				continue;
			}
			final List<List<Integer>> groups = DateSpans.groupsByOverlap(sources);
			if (groups.size() >= 2)
			{
				final int bar = entry.getKey().indexOf('|');
				out.add(new Disjoint(entry.getKey().substring(0, bar), entry.getKey().substring(bar + 1), sources, groups));
			}
		}
		return out;
	}

	/** Connected components of the "spans overlap" relation, each group in source order, groups in order of their first source. */
	private static List<List<Integer>> groupsByOverlap(final List<Source> pSources)
	{
		final int n = pSources.size();
		final int[] group = new int[n];
		for (int i = 0; i < n; i++)
		{
			group[i] = i;
		}
		boolean changed = true;
		while (changed)
		{
			changed = false;
			for (int i = 0; i < n; i++)
			{
				for (int j = i + 1; j < n; j++)
				{
					if (group[i] != group[j] && pSources.get(i).span.overlaps(pSources.get(j).span))
					{
						final int keep = Math.min(group[i], group[j]);
						final int drop = Math.max(group[i], group[j]);
						for (int k = 0; k < n; k++)
						{
							if (group[k] == drop)
							{
								group[k] = keep;
							}
						}
						changed = true;
					}
				}
			}
		}
		final Map<Integer, List<Integer>> byGroup = new LinkedHashMap<Integer, List<Integer>>();
		for (int i = 0; i < n; i++)
		{
			List<Integer> list = byGroup.get(Integer.valueOf(group[i]));
			if (list == null)
			{
				list = new ArrayList<Integer>();
				byGroup.put(Integer.valueOf(group[i]), list);
			}
			list.add(Integer.valueOf(i));
		}
		return new ArrayList<List<Integer>>(byGroup.values());
	}
}
