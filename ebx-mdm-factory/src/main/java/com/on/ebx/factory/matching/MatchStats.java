package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure computations behind wizard step 6's "Matching results" block (task brief, wizard-matching-results
 * lot, 2026-09-24): KPI counts from plain metadataset rows, the "where golden records come from" band, and
 * the suspect-pair list's own strength/hint/sort/filter/bulk-eligibility rules. No EBX class anywhere in
 * this file - unit-tested without an EBX runtime, the same discipline every other pure model in this module
 * follows ({@code lineage.RecordLineage}, {@code review.ReviewRules}, ...).
 * <p>
 * <b>Deliberately NOT the add-on's own matching score.</b> {@code ebx.MameMatchingApi}'s own class Javadoc
 * (`TreeEvaluation.FieldScore#score`, read before writing this class) states plainly that the unit of the
 * engine's real per-field score (0-1 or 0-100) was never verified live. Rather than guess a conversion that
 * could silently be wrong, {@link #compareFields} computes a plain, cheap, ALWAYS-testable proxy - trimmed,
 * case/space-insensitive equality vs. a shared-prefix/substring heuristic - and {@link #strengthPercent}
 * aggregates it into "how many of the compared fields look alike" (0-100), labelled everywhere as a "match
 * strength" indicator, never as "the score". The real per-field verdicts/scores the add-on's own decision
 * tree computed for a pair ({@code MameMatchingApi.TreeEvaluation}) are shown ALONGSIDE this proxy on the
 * detail panel (see {@code ebx.MatchingResultsBuilder}), never substituted for it - see
 * {@code docs/WIZARD-MATCHING-RESULTS.md} for the full reasoning.
 */
public final class MatchStats
{
	private MatchStats()
	{
	}

	// ------------------------------------------------------------------------------------------------------ KPIs

	/** One {@code RecordMetadata} row, reduced to the plain fields this class needs - built by {@code ebx.MatchingResultsBuilder}, never an EBX {@code Adaptation}. */
	public static final class MetaRow
	{
		public final String functionalId;
		/** {@code UNSET}/{@code GOLDEN}/{@code MERGED}/{@code PENDING_APPROVAL}/{@code DELETED} - the add-on's own {@code State} name. */
		public final String state;
		public final String groupId;
		public final String goldenId;
		public final boolean hasSuspects;

		public MetaRow(final String pFunctionalId, final String pState, final String pGroupId, final String pGoldenId, final boolean pHasSuspects)
		{
			this.functionalId = pFunctionalId;
			this.state = pState;
			this.groupId = pGroupId;
			this.goldenId = pGoldenId;
			this.hasSuspects = pHasSuspects;
		}
	}

	public static final class Kpis
	{
		/** Records in master, {@code DELETED} rows excluded everywhere (CLAUDE.md sec 8: a DELETED row is a physical remnant of an Unmerge, not a live record). */
		public final int totalRecords;
		/** Same total, broken down by source system - key is whatever the caller passed (a source code, or {@link #MULTIPLE_SOURCES}/{@link #UNKNOWN_SOURCE} for a record this class could not attribute to exactly one system). Insertion order preserved. */
		public final Map<String, Integer> recordsBySource;
		public final int goldenRecords;
		/** Distinct {@code goldenId} values among {@code MERGED} rows - each one is a golden with at least one member merged into it, i.e. a real group of 2+. */
		public final int mergedGroups;
		/** Real count, supplied by the caller (a metadataset scan alone cannot size a group's own suspect list - see {@code ebx.MatchingResultsBuilder}). */
		public final int pendingSuspectPairs;
		/** Real count of {@code FalsePositiveDecision} rows for this table, supplied by the caller. */
		public final int distinctPairs;
		/**
		 * Lot M4 (2026-10-03, defect 3): records that have not been through matching yet - state {@code UNSET}, or no metadata at all
		 * (a NULL {@code Linked_Field_State}). Part of {@link #totalRecords} (the table's row count, DELETED rows aside): after an
		 * incremental run the pivots are only the last loaded batch, every older record keeps this state until a run covers it.
		 */
		public final int unmatchedRecords;

		public Kpis(final int pTotalRecords, final Map<String, Integer> pRecordsBySource, final int pGoldenRecords, final int pMergedGroups, final int pPendingSuspectPairs,
			final int pDistinctPairs)
		{
			this(pTotalRecords, pRecordsBySource, pGoldenRecords, pMergedGroups, pPendingSuspectPairs, pDistinctPairs, 0);
		}

		public Kpis(final int pTotalRecords, final Map<String, Integer> pRecordsBySource, final int pGoldenRecords, final int pMergedGroups, final int pPendingSuspectPairs,
			final int pDistinctPairs, final int pUnmatchedRecords)
		{
			this.unmatchedRecords = pUnmatchedRecords;
			this.totalRecords = pTotalRecords;
			this.recordsBySource = pRecordsBySource == null ? Collections.<String, Integer>emptyMap() : pRecordsBySource;
			this.goldenRecords = pGoldenRecords;
			this.mergedGroups = pMergedGroups;
			this.pendingSuspectPairs = pPendingSuspectPairs;
			this.distinctPairs = pDistinctPairs;
		}
	}

	/**
	 * KPIs computable from a plain metadataset scan alone - {@code totalRecords}/{@code recordsBySource}/
	 * {@code goldenRecords}/{@code mergedGroups}. {@code pendingSuspectPairs}/{@code distinctPairs} are not
	 * derivable from {@code pRows} alone (a {@code hasSuspects} flag says "at least one", never how many) -
	 * the caller ({@code ebx.MatchingResultsBuilder}) fills them in from a real group/false-positive-table
	 * count via {@link #withSuspectCounts}.
	 */
	public static Kpis computeFromRows(final List<MetaRow> pRows, final Map<String, String> pSourceSystemByFunctionalId)
	{
		final Map<String, Integer> bySource = new LinkedHashMap<String, Integer>();
		int total = 0;
		int golden = 0;
		final java.util.Set<String> mergedGoldenIds = new java.util.LinkedHashSet<String>();
		if (pRows != null)
		{
			for (final MetaRow row : pRows)
			{
				if ("DELETED".equals(row.state))
				{
					continue;
				}
				total++;
				final String source = pSourceSystemByFunctionalId == null ? null : pSourceSystemByFunctionalId.get(row.functionalId);
				final String key = source == null || source.trim().isEmpty() ? "UNKNOWN" : source.trim();
				final Integer current = bySource.get(key);
				bySource.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
				if ("GOLDEN".equals(row.state))
				{
					golden++;
				}
				if ("MERGED".equals(row.state) && row.goldenId != null && !row.goldenId.trim().isEmpty())
				{
					mergedGoldenIds.add(row.goldenId.trim());
				}
			}
		}
		return new Kpis(total, bySource, golden, mergedGoldenIds.size(), 0, 0);
	}

	/** Copies {@code pKpis} with the two counts a metadataset scan alone cannot size - see {@link #computeFromRows}'s own Javadoc. */
	public static Kpis withSuspectCounts(final Kpis pKpis, final int pPendingSuspectPairs, final int pDistinctPairs)
	{
		return new Kpis(pKpis.totalRecords, pKpis.recordsBySource, pKpis.goldenRecords, pKpis.mergedGroups, pPendingSuspectPairs, pDistinctPairs, pKpis.unmatchedRecords);
	}

	/**
	 * One (state, source system, row count) aggregate - the shape a single {@code GROUP BY "Linked_Field_State",
	 * "sourceSystem"} SQL query returns (lot volume-4, 2026-09-28, {@code ebx.MatchingResultsBuilder}): bounded by
	 * the number of DISTINCT (state, source) combinations actually present (a handful, even on a master table of
	 * millions of rows), never by row count - the scalable replacement for {@link #computeFromRows}'s per-record
	 * {@link MetaRow} list on a table too large to materialize into Java objects one record at a time. {@code
	 * state} is the add-on's own state NAME ({@code GOLDEN}/{@code MERGED}/...), already resolved from the raw
	 * ordinal by the caller (same ordinal table {@link MetaRow}'s own callers use) - this class stays free of that
	 * mapping, same discipline as everywhere else in this file.
	 */
	public static final class StateSourceCount
	{
		public final String state;
		/** Raw, as read off the master table's own {@code sourceSystem} column - may be null/blank (folded to {@code UNKNOWN} by {@link #fromStateSourceCounts}, same convention {@link #computeFromRows} already applies). */
		public final String source;
		public final long count;

		public StateSourceCount(final String pState, final String pSource, final long pCount)
		{
			this.state = pState;
			this.source = pSource;
			this.count = pCount;
		}
	}

	/**
	 * The SQL-aggregate sibling of {@link #computeFromRows}: builds the same {@code totalRecords}/{@code
	 * recordsBySource}/{@code goldenRecords} KPIs from a handful of {@link StateSourceCount} rows (a {@code GROUP
	 * BY} over the master table, DELETED rows included (lot M4: the SQL no longer filters on the state, a NULL state - a record never
	 * touched by matching - would be dropped by {@code <> 4}) and dropped here, same "never trust a caller silently changed" discipline the rest of
	 * this class applies) instead of one {@link MetaRow} per record. {@code pMergedGroups}/{@code
	 * pPendingSuspectPairs}/{@code pDistinctPairs} are real counts the caller already measured some other way
	 * (a distinct-goldenId SQL count, a {@code SUM(suspectedCount)}, a {@code FalsePositiveDecision} row count) -
	 * same role {@link #withSuspectCounts}'s own two parameters play, just supplied up front here since none of
	 * the three are derivable from {@code pRows} either.
	 */
	public static Kpis fromStateSourceCounts(final List<StateSourceCount> pRows, final int pMergedGroups, final int pPendingSuspectPairs, final int pDistinctPairs)
	{
		final Map<String, Integer> bySource = new LinkedHashMap<String, Integer>();
		int total = 0;
		int golden = 0;
		int unmatched = 0;
		if (pRows != null)
		{
			for (final StateSourceCount row : pRows)
			{
				if (row == null || "DELETED".equals(row.state))
				{
					continue;
				}
				final int count = (int) row.count;
				total += count;
				// lot M4 (defect 3): a record the add-on never touched has a NULL state (or UNSET) - it is part of the table, and counted as such
				if (row.state == null || "UNSET".equals(row.state))
				{
					unmatched += count;
				}
				final String key = row.source == null || row.source.trim().isEmpty() ? "UNKNOWN" : row.source.trim();
				final Integer current = bySource.get(key);
				bySource.put(key, Integer.valueOf((current == null ? 0 : current.intValue()) + count));
				if ("GOLDEN".equals(row.state))
				{
					golden += count;
				}
			}
		}
		return new Kpis(total, bySource, golden, pMergedGroups, pPendingSuspectPairs, pDistinctPairs, unmatched);
	}

	// --------------------------------------------------------------------------------------------- source band

	public static final class BandSegment
	{
		/** A single source code, or {@link #MULTIPLE} when this segment groups every golden known from more than one system. */
		public final String label;
		public final int count;

		public BandSegment(final String pLabel, final int pCount)
		{
			this.label = pLabel;
			this.count = pCount;
		}
	}

	public static final String MULTIPLE = "MULTIPLE";

	/**
	 * "D'ou viennent les golden records" (task item 4): one segment per source system that contributed to at
	 * least one golden ALONE, plus one {@link #MULTIPLE} segment for every golden known from 2+ systems.
	 * Sorted by count descending, ties broken alphabetically (stable, testable) - the caller assigns colours
	 * by looking each label up in the project's own declared source order ({@code lineage.SourceColorPalette}),
	 * {@link #MULTIPLE} always getting the reserved golden/accent colour.
	 *
	 * @param pSourceSystemsByGoldenKey one entry per golden record that has at least one contributing source
	 *            known (a plain member+golden-itself source-system union) - an empty set for a key is ignored
	 *            (nothing to attribute).
	 */
	public static List<BandSegment> computeBand(final Map<String, java.util.Set<String>> pSourceSystemsByGoldenKey)
	{
		final Map<String, Integer> counts = new TreeMap<String, Integer>();
		int multiple = 0;
		if (pSourceSystemsByGoldenKey != null)
		{
			for (final java.util.Set<String> systems : pSourceSystemsByGoldenKey.values())
			{
				if (systems == null || systems.isEmpty())
				{
					continue;
				}
				if (systems.size() == 1)
				{
					final String only = systems.iterator().next();
					final Integer current = counts.get(only);
					counts.put(only, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
				}
				else
				{
					multiple++;
				}
			}
		}
		return MatchStats.assembleBand(counts, multiple);
	}

	/**
	 * The SQL-aggregate sibling of {@link #computeBand}: the same band, from a {@code label -> count} map already
	 * bucketed server-side (lot volume-4, 2026-09-28, {@code ebx.MatchingResultsBuilder}'s two-level {@code GROUP
	 * BY} - one golden per row, {@link #MULTIPLE}'s own count already summed rather than derived from one {@code
	 * Set<String>} per golden) instead of one {@code Set<String>} per golden record kept in a Java map - the
	 * scalable replacement on a master table too large to hold "every golden's own contributing-source set" in
	 * memory at once. A {@link #MULTIPLE} entry in {@code pCountsByLabel} is folded into the running multiple
	 * total rather than treated as an ordinary label (same "always last, never sorted in" rule {@link
	 * #computeBand} already applies); every other label is summed by key first (a caller may legitimately submit
	 * more than one row for the same label - see {@code ebx.MatchingResultsBuilder}'s own query, which does not).
	 */
	public static List<BandSegment> bandFromLabelCounts(final Map<String, Integer> pCountsByLabel)
	{
		final Map<String, Integer> counts = new TreeMap<String, Integer>();
		int multiple = 0;
		if (pCountsByLabel != null)
		{
			for (final Map.Entry<String, Integer> entry : pCountsByLabel.entrySet())
			{
				if (entry.getKey() == null || entry.getValue() == null)
				{
					continue;
				}
				if (MatchStats.MULTIPLE.equals(entry.getKey()))
				{
					multiple += entry.getValue().intValue();
				}
				else
				{
					final Integer current = counts.get(entry.getKey());
					counts.put(entry.getKey(), Integer.valueOf((current == null ? 0 : current.intValue()) + entry.getValue().intValue()));
				}
			}
		}
		return MatchStats.assembleBand(counts, multiple);
	}

	/**
	 * Wave 2 (2026-09-30): the band when NO record of the table is MERGED - every golden is then its own group of
	 * one, so its source is simply its own {@code sourceSystem} and the band is the GOLDEN rows' per-source counts,
	 * with no second statement over the master table (the two-level band {@code GROUP BY} measured as one of the
	 * three whole-table aggregates behind a 32-46 s wizard render at 100 000 rows). Same semantics as the SQL band
	 * query on that data: only state GOLDEN, a null/blank source is dropped (the SQL's {@code MIN(sourceSystem)} is
	 * null for such a group and the reader skips a null label). Caller must have checked that no row of state
	 * {@code MERGED} exists ({@link #hasMerged}); with merged rows this would under-report MULTIPLE.
	 */
	public static List<BandSegment> bandFromGoldenCounts(final List<StateSourceCount> pRows)
	{
		final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		if (pRows != null)
		{
			for (final StateSourceCount row : pRows)
			{
				if (row == null || !"GOLDEN".equals(row.state) || row.source == null || row.source.trim().isEmpty())
				{
					continue;
				}
				final String key = row.source.trim();
				final Integer current = counts.get(key);
				counts.put(key, Integer.valueOf((current == null ? 0 : current.intValue()) + (int) row.count));
			}
		}
		return MatchStats.bandFromLabelCounts(counts);
	}

	/** True when at least one aggregate row is in state MERGED with a positive count - the guard for {@link #bandFromGoldenCounts} and for skipping the merged-groups statements. */
	public static boolean hasMerged(final List<StateSourceCount> pRows)
	{
		if (pRows != null)
		{
			for (final StateSourceCount row : pRows)
			{
				if (row != null && "MERGED".equals(row.state) && row.count > 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Shared sort/assemble tail of {@link #computeBand}/{@link #bandFromLabelCounts}: descending count, alphabetical tie-break, {@link #MULTIPLE} always last regardless of its own count. */
	private static List<BandSegment> assembleBand(final Map<String, Integer> pCounts, final int pMultiple)
	{
		final List<BandSegment> out = new ArrayList<BandSegment>();
		for (final Map.Entry<String, Integer> entry : pCounts.entrySet())
		{
			out.add(new BandSegment(entry.getKey(), entry.getValue().intValue()));
		}
		Collections.sort(out, new Comparator<BandSegment>()
		{
			@Override
			public int compare(final BandSegment pA, final BandSegment pB)
			{
				return pB.count != pA.count ? pB.count - pA.count : pA.label.compareTo(pB.label);
			}
		});
		if (pMultiple > 0)
		{
			out.add(new BandSegment(MatchStats.MULTIPLE, pMultiple));
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------ pair fields

	public static final class FieldComparison
	{
		/** The text shown for the field: the attribute's label (the master field name when it has none). */
		public final String label;
		/** The master field this row is read from (the key of the plan when the row stands for a derived key); what the permissions pass checks. Equal to {@link #label} for a row stored before the labels existed. */
		public final String name;
		/** Never null - blank means "no value" (rendered as an empty/italic placeholder by the view, never as a literal "vide" here - EBX-free, localization stays in the view/messages layer). */
		public final String left;
		public final String right;
		/** The raw field a derived key stands for ({@code telephone} under {@code telephoneE164}): shown as a secondary line; empty when the row is not a derived key. */
		public final String rawLeft;
		public final String rawRight;
		/** The master field {@link #rawLeft}/{@link #rawRight} are read from; empty when there is none. */
		public final String rawName;
		/** True when the row is shown at a glance on the pair card (a field the plan compares, an identity field, a child summary); the others only in "Compare field by field". */
		public final boolean glance;
		/** One of {@link #EQ}/{@link #FUZZY}/{@link #DIFF}/{@link #MISSING}. */
		public final String verdict;

		public FieldComparison(final String pLabel, final String pLeft, final String pRight, final String pVerdict)
		{
			this(pLabel, pLabel, pLeft, pRight, "", "", "", true, pVerdict);
		}

		public FieldComparison(final String pLabel, final String pName, final String pLeft, final String pRight, final String pRawLeft, final String pRawRight,
			final String pRawName, final boolean pGlance, final String pVerdict)
		{
			this.label = pLabel;
			this.name = pName == null || pName.isEmpty() ? pLabel : pName;
			this.left = pLeft == null ? "" : pLeft;
			this.right = pRight == null ? "" : pRight;
			this.rawLeft = pRawLeft == null ? "" : pRawLeft;
			this.rawRight = pRawRight == null ? "" : pRawRight;
			this.rawName = pRawName == null ? "" : pRawName;
			this.glance = pGlance;
			this.verdict = pVerdict;
		}

		/** The same row with its values blanked on one or both sides (a permission), the verdict then {@link MatchStats#MISSING}. */
		public FieldComparison hidden(final boolean pLeftShown, final boolean pRightShown, final boolean pRawLeftShown, final boolean pRawRightShown)
		{
			return new FieldComparison(this.label, this.name, pLeftShown ? this.left : "", pRightShown ? this.right : "", pRawLeftShown ? this.rawLeft : "",
				pRawRightShown ? this.rawRight : "", this.rawName, this.glance, MatchStats.MISSING);
		}
	}

	/** The {@link FieldComparison#name} prefix of a row that summarises the CHILD rows of the record (an address): {@code $child:<Entity>}. Not a field of the record, never counted in the strength. */
	public static final String CHILD_PREFIX = "$child:";

	public static final String EQ = "eq";
	public static final String FUZZY = "fuzzy";
	public static final String DIFF = "diff";
	public static final String MISSING = "missing";

	/**
	 * One field's verdict - a plain textual proxy, NOT the add-on's own algorithm/threshold (class Javadoc):
	 * {@link #MISSING} when either side is blank, {@link #EQ} when the trimmed, case/space-insensitive values
	 * are identical, {@link #FUZZY} when one contains the other or they share a common prefix at least 60% as
	 * long as the shorter value, {@link #DIFF} otherwise.
	 */
	public static String compareValues(final String pLeft, final String pRight)
	{
		final String left = MatchStats.normalize(pLeft);
		final String right = MatchStats.normalize(pRight);
		if (left.isEmpty() || right.isEmpty())
		{
			return MatchStats.MISSING;
		}
		if (left.equals(right))
		{
			return MatchStats.EQ;
		}
		if (left.contains(right) || right.contains(left))
		{
			return MatchStats.FUZZY;
		}
		final int shorter = Math.min(left.length(), right.length());
		int commonPrefix = 0;
		while (commonPrefix < shorter && left.charAt(commonPrefix) == right.charAt(commonPrefix))
		{
			commonPrefix++;
		}
		return shorter > 0 && commonPrefix >= (shorter * 6) / 10 ? MatchStats.FUZZY : MatchStats.DIFF;
	}

	static String normalize(final String pValue)
	{
		return pValue == null ? "" : pValue.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	/** One {@link FieldComparison} per (label, left, right) triple - convenience so the caller never calls {@link #compareValues} by hand for every field. */
	public static List<FieldComparison> compareFields(final List<String> pLabels, final List<String> pLeftValues, final List<String> pRightValues)
	{
		final List<FieldComparison> out = new ArrayList<FieldComparison>();
		if (pLabels == null)
		{
			return out;
		}
		for (int i = 0; i < pLabels.size(); i++)
		{
			final String left = pLeftValues != null && i < pLeftValues.size() ? pLeftValues.get(i) : null;
			final String right = pRightValues != null && i < pRightValues.size() ? pRightValues.get(i) : null;
			out.add(new FieldComparison(pLabels.get(i), left, right, MatchStats.compareValues(left, right)));
		}
		return out;
	}

	/** 0-100: the share of non-{@link #MISSING} fields that are {@link #EQ} or {@link #FUZZY} - the "match strength" proxy, see class Javadoc. 0 when every field is missing (never divides by zero). */
	public static int strengthPercent(final List<FieldComparison> pFields)
	{
		if (pFields == null || pFields.isEmpty())
		{
			return 0;
		}
		int comparable = 0;
		int alike = 0;
		for (final FieldComparison field : pFields)
		{
			if (MatchStats.MISSING.equals(field.verdict) || field.name.startsWith(MatchStats.CHILD_PREFIX))
			{
				continue;
			}
			comparable++;
			if (MatchStats.EQ.equals(field.verdict) || MatchStats.FUZZY.equals(field.verdict))
			{
				alike++;
			}
		}
		return comparable == 0 ? 0 : (alike * 100) / comparable;
	}

	/**
	 * A short, plain-word hint for the pair list row - names the first field found {@link #DIFF} or
	 * {@link #FUZZY} (in the caller's own field order, which is the plan's own field order), or a generic
	 * "sparse record" hint when at least half the fields are {@link #MISSING}, or an empty string when every
	 * compared field is a plain {@link #EQ} (nothing to call out).
	 */
	public static String hintFieldLabel(final List<FieldComparison> pFields)
	{
		if (pFields == null || pFields.isEmpty())
		{
			return null;
		}
		for (final FieldComparison field : pFields)
		{
			if (MatchStats.FUZZY.equals(field.verdict) || MatchStats.DIFF.equals(field.verdict))
			{
				return field.label;
			}
		}
		return null;
	}

	/** True when at least half of {@code pFields} are {@link #MISSING} - the "sparse record" hint case {@link #hintFieldLabel} itself deliberately leaves to the caller (two different message keys, not two return shapes from one method). */
	public static boolean isSparse(final List<FieldComparison> pFields)
	{
		if (pFields == null || pFields.isEmpty())
		{
			return false;
		}
		int missing = 0;
		for (final FieldComparison field : pFields)
		{
			if (MatchStats.MISSING.equals(field.verdict))
			{
				missing++;
			}
		}
		return missing * 2 >= pFields.size();
	}

	// ------------------------------------------------------------------------------------------------- pair list

	public static final String PENDING = "PENDING";
	public static final String MERGED = "MERGED";
	public static final String DISTINCT = "DISTINCT";

	public static final class SuspectPair
	{
		public final String id;
		public final String goldenKey;
		public final String suspectKey;
		/** The record named in full (first and last name of a person, the name of a company): what the plan's name fields give, else the record's own title. */
		public final String goldenLabel;
		public final String suspectLabel;
		public final String goldenSource;
		public final String suspectSource;
		/** The record's key in its source system ({@code sourceId}); empty when unknown (a pair stored before the card showed it). */
		public final String goldenSourceId;
		public final String suspectSourceId;
		public final List<FieldComparison> fields;
		/** {@link #PENDING}/{@link #MERGED}/{@link #DISTINCT} - the pair's OWN decision, distinct from which tab it is currently rendered under. */
		public final String decision;

		public SuspectPair(final String pId, final String pGoldenKey, final String pSuspectKey, final String pGoldenLabel, final String pSuspectLabel,
			final String pGoldenSource, final String pSuspectSource, final List<FieldComparison> pFields, final String pDecision)
		{
			this(pId, pGoldenKey, pSuspectKey, pGoldenLabel, pSuspectLabel, pGoldenSource, pSuspectSource, "", "", pFields, pDecision);
		}

		public SuspectPair(final String pId, final String pGoldenKey, final String pSuspectKey, final String pGoldenLabel, final String pSuspectLabel,
			final String pGoldenSource, final String pSuspectSource, final String pGoldenSourceId, final String pSuspectSourceId, final List<FieldComparison> pFields,
			final String pDecision)
		{
			this.id = pId;
			this.goldenKey = pGoldenKey;
			this.suspectKey = pSuspectKey;
			this.goldenLabel = pGoldenLabel;
			this.suspectLabel = pSuspectLabel;
			this.goldenSource = pGoldenSource;
			this.suspectSource = pSuspectSource;
			this.goldenSourceId = pGoldenSourceId == null ? "" : pGoldenSourceId;
			this.suspectSourceId = pSuspectSourceId == null ? "" : pSuspectSourceId;
			this.fields = pFields == null ? Collections.<FieldComparison>emptyList() : pFields;
			this.decision = pDecision == null ? MatchStats.PENDING : pDecision;
		}
	}

	// ------------------------------------------------------------------------------------------------- suspect groups

	/** One record of a group: a column of the card. */
	public static final class GroupMember
	{
		public final String key;
		public final String source;
		/** The record's key in its source system; empty when unknown. */
		public final String sourceId;
		/** The record named in full. */
		public final String label;
		/** The record is a golden record already (it has records merged into it): flagged in its column header. */
		public final boolean golden;

		public GroupMember(final String pKey, final String pSource, final String pSourceId, final String pLabel, final boolean pGolden)
		{
			this.key = pKey;
			this.source = pSource == null ? "" : pSource;
			this.sourceId = pSourceId == null ? "" : pSourceId;
			this.label = pLabel == null ? "" : pLabel;
			this.golden = pGolden;
		}
	}

	/** One row of a group card: a field the plan compares (or an identity field, or a child summary), with one value per member, in the order of {@link SuspectGroup#members}. */
	public static final class GroupRow
	{
		public final String label;
		/** The master field read (the derived key when the row stands for one); {@link #CHILD_PREFIX}+entity for a child summary. */
		public final String name;
		/** The raw field under a derived key; empty otherwise. */
		public final String rawName;
		/** Shown at a glance (the card's table); the others only in "Compare field by field". */
		public final boolean glance;
		public final List<String> values;
		public final List<String> raws;

		public GroupRow(final String pLabel, final String pName, final String pRawName, final boolean pGlance, final List<String> pValues, final List<String> pRaws)
		{
			this.label = pLabel;
			this.name = pName == null || pName.isEmpty() ? pLabel : pName;
			this.rawName = pRawName == null ? "" : pRawName;
			this.glance = pGlance;
			this.values = pValues == null ? new ArrayList<String>() : pValues;
			this.raws = pRaws == null ? new ArrayList<String>() : pRaws;
		}

		/** The value of member {@code pIndex}, never null. */
		public String value(final int pIndex)
		{
			return pIndex < this.values.size() && this.values.get(pIndex) != null ? this.values.get(pIndex) : "";
		}

		public String raw(final int pIndex)
		{
			return pIndex < this.raws.size() && this.raws.get(pIndex) != null ? this.raws.get(pIndex) : "";
		}

		/** The value most members hold (ties: the first one in column order); empty when no member holds one. Compared as {@link #normalize} does. */
		public String majority()
		{
			final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
			for (final String value : this.values)
			{
				final String normal = MatchStats.normalize(value);
				if (!normal.isEmpty())
				{
					counts.put(normal, Integer.valueOf(counts.containsKey(normal) ? counts.get(normal).intValue() + 1 : 1));
				}
			}
			String best = "";
			int bestCount = 0;
			for (final Map.Entry<String, Integer> entry : counts.entrySet())
			{
				if (entry.getValue().intValue() > bestCount)
				{
					best = entry.getKey();
					bestCount = entry.getValue().intValue();
				}
			}
			return best;
		}

		/** {@link #MISSING} (no member holds a value), {@link #EQ} (every filled member holds the same), else {@link #DIFF}. */
		public String verdict()
		{
			final Set<String> distinct = new java.util.LinkedHashSet<String>();
			for (final String value : this.values)
			{
				final String normal = MatchStats.normalize(value);
				if (!normal.isEmpty())
				{
					distinct.add(normal);
				}
			}
			return distinct.isEmpty() ? MatchStats.MISSING : distinct.size() == 1 ? MatchStats.EQ : MatchStats.DIFF;
		}
	}

	/**
	 * The suspects of one real-world object, as one card: the connected component of the suspect pairs (plus the golden record some of them are merged into), one
	 * column per record, one row per field. Decided as a whole: merged, kept apart, or merged without the records a person set aside.
	 */
	public static final class SuspectGroup
	{
		/** A stable id: the smallest member key. */
		public final String id;
		public final List<GroupMember> members;
		public final List<GroupRow> rows;
		/** The suspect pairs of the group as the add-on stores them: {@code [sourceId, targetId]} master keys. */
		public final List<String[]> pairs;
		/** The decision-tree nodes (by the engine's name) that produced the pairs, in the order met, each once. */
		public final List<String> nodes;

		public SuspectGroup(final String pId, final List<GroupMember> pMembers, final List<GroupRow> pRows, final List<String[]> pPairs, final List<String> pNodes)
		{
			this.id = pId;
			this.members = pMembers;
			this.rows = pRows;
			this.pairs = pPairs;
			this.nodes = pNodes == null ? new ArrayList<String>() : pNodes;
		}

		/** The name of the group: the label of its first member that has one. */
		public String label()
		{
			for (final GroupMember member : this.members)
			{
				if (!member.label.isEmpty())
				{
					return member.label;
				}
			}
			return this.id;
		}

		/** The two records of an old stored pair, as a group of two - a pair is just the two-column case. */
		public static SuspectGroup ofPair(final SuspectPair pPair)
		{
			final List<GroupMember> members = new ArrayList<GroupMember>();
			members.add(new GroupMember(pPair.goldenKey, pPair.goldenSource, pPair.goldenSourceId, pPair.goldenLabel, false));
			members.add(new GroupMember(pPair.suspectKey, pPair.suspectSource, pPair.suspectSourceId, pPair.suspectLabel, false));
			final List<GroupRow> rows = new ArrayList<GroupRow>();
			for (final FieldComparison field : pPair.fields)
			{
				rows.add(new GroupRow(field.label, field.name, field.rawName, field.glance, new ArrayList<String>(java.util.Arrays.asList(field.left, field.right)),
					new ArrayList<String>(java.util.Arrays.asList(field.rawLeft, field.rawRight))));
			}
			final List<String[]> pairs = new ArrayList<String[]>();
			pairs.add(new String[] { pPair.goldenKey, pPair.suspectKey });
			return new SuspectGroup(pPair.goldenKey.compareTo(pPair.suspectKey) <= 0 ? pPair.goldenKey : pPair.suspectKey, members, rows, pairs, new ArrayList<String>());
		}
	}

	/** Every pair whose {@link SuspectPair#decision} equals {@code pTab} ({@link #PENDING}/{@link #MERGED}/{@link #DISTINCT}) - order preserved. */
	public static List<SuspectPair> filterByTab(final List<SuspectPair> pPairs, final String pTab)
	{
		final List<SuspectPair> out = new ArrayList<SuspectPair>();
		if (pPairs == null)
		{
			return out;
		}
		for (final SuspectPair pair : pPairs)
		{
			if (pair.decision.equals(pTab))
			{
				out.add(pair);
			}
		}
		return out;
	}

	/** {@code pPairs} sorted by {@link #strengthPercent(List)} descending, stable on ties (original order preserved) - never mutates the input list. */
	public static List<SuspectPair> sortByStrengthDesc(final List<SuspectPair> pPairs)
	{
		final List<SuspectPair> out = new ArrayList<SuspectPair>(pPairs == null ? Collections.<SuspectPair>emptyList() : pPairs);
		Collections.sort(out, new Comparator<SuspectPair>()
		{
			@Override
			public int compare(final SuspectPair pA, final SuspectPair pB)
			{
				return MatchStats.strengthPercent(pB.fields) - MatchStats.strengthPercent(pA.fields);
			}
		});
		return out;
	}

	/** Every {@link #PENDING} pair of {@code pPairs} whose {@link #strengthPercent} is at least {@code pThresholdPercent} - the bulk-merge eligibility set (task item 5, "Merge the N pairs >= threshold"). */
	public static List<SuspectPair> eligibleForBulk(final List<SuspectPair> pPairs, final int pThresholdPercent)
	{
		final List<SuspectPair> out = new ArrayList<SuspectPair>();
		if (pPairs == null)
		{
			return out;
		}
		for (final SuspectPair pair : pPairs)
		{
			if (MatchStats.PENDING.equals(pair.decision) && MatchStats.strengthPercent(pair.fields) >= pThresholdPercent)
			{
				out.add(pair);
			}
		}
		return out;
	}
}
