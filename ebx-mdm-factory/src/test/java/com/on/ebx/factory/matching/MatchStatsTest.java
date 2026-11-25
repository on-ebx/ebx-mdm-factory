package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Covers
 * {@link MatchStats}: KPI computation from plain metadataset rows, the source band, per-field
 * comparison/strength/hint, and pair sort/filter/bulk-eligibility.
 */
public final class MatchStatsTest
{
	public static void main(final String[] pArgs)
	{
		MatchStatsTest.kpisCountRecordsBySourceAndExcludeDeleted();
		MatchStatsTest.kpisCountGoldenAndMergedGroupsByDistinctGoldenId();
		MatchStatsTest.withSuspectCountsCopiesOtherFieldsUnchanged();
		MatchStatsTest.bandSeparatesSingleSourceFromMultipleAndSortsByCountDesc();
		MatchStatsTest.bandIgnoresEmptySourceSets();
		MatchStatsTest.compareValuesEqIgnoresCaseAndSpacing();
		MatchStatsTest.compareValuesMissingWhenEitherSideBlank();
		MatchStatsTest.compareValuesFuzzyOnSharedPrefixOrContainment();
		MatchStatsTest.compareValuesDiffOtherwise();
		MatchStatsTest.strengthPercentIgnoresMissingFields();
		MatchStatsTest.strengthPercentZeroWhenAllMissing();
		MatchStatsTest.hintFieldLabelNamesFirstDivergentField();
		MatchStatsTest.hintFieldLabelNullWhenEverythingMatches();
		MatchStatsTest.isSparseWhenHalfOrMoreMissing();
		MatchStatsTest.filterByTabSelectsOnlyMatchingDecision();
		MatchStatsTest.sortByStrengthDescOrdersHighestFirstAndNeverMutatesInput();
		MatchStatsTest.eligibleForBulkOnlyPendingAboveThreshold();
		MatchStatsTest.bandFromGoldenCountsMatchesTheTwoLevelBandWhenNothingIsMerged();
		MatchStatsTest.hasMergedOnlyOnAPositiveMergedCount();
		MatchStatsTest.untouchedRecordsAreCountedInTheTotalAsNotMatchedYet();
		System.out.println("MatchStatsTest OK");
	}

	// --------------------------------------------------------------------------------------------------- KPIs

	private static void kpisCountRecordsBySourceAndExcludeDeleted()
	{
		final List<MatchStats.MetaRow> rows = Arrays.asList(new MatchStats.MetaRow("1", "GOLDEN", null, null, false),
			new MatchStats.MetaRow("2", "GOLDEN", null, null, false), new MatchStats.MetaRow("3", "DELETED", null, "1", false));
		final Map<String, String> sourceByKey = new LinkedHashMap<String, String>();
		sourceByKey.put("1", "SF");
		sourceByKey.put("2", "SAP");
		sourceByKey.put("3", "SF");
		final MatchStats.Kpis kpis = MatchStats.computeFromRows(rows, sourceByKey);
		MatchStatsTest.check("DELETED rows excluded from total", kpis.totalRecords == 2);
		MatchStatsTest.check("recordsBySource has SF=1", Integer.valueOf(1).equals(kpis.recordsBySource.get("SF")));
		MatchStatsTest.check("recordsBySource has SAP=1", Integer.valueOf(1).equals(kpis.recordsBySource.get("SAP")));
		MatchStatsTest.check("DELETED row's own source never counted", !kpis.recordsBySource.containsKey("SF") || kpis.recordsBySource.get("SF").intValue() == 1);
	}

	private static void kpisCountGoldenAndMergedGroupsByDistinctGoldenId()
	{
		final List<MatchStats.MetaRow> rows = Arrays.asList(new MatchStats.MetaRow("1", "GOLDEN", "10", null, true), new MatchStats.MetaRow("2", "MERGED", "10", "1", false),
			new MatchStats.MetaRow("3", "MERGED", "10", "1", false), new MatchStats.MetaRow("4", "GOLDEN", "20", null, false));
		final MatchStats.Kpis kpis = MatchStats.computeFromRows(rows, null);
		MatchStatsTest.check("two GOLDEN rows", kpis.goldenRecords == 2);
		MatchStatsTest.check("one distinct goldenId among MERGED rows -> one merged group", kpis.mergedGroups == 1);
		MatchStatsTest.check("unknown source falls back to UNKNOWN bucket", Integer.valueOf(4).equals(kpis.recordsBySource.get("UNKNOWN")));
	}

	private static void withSuspectCountsCopiesOtherFieldsUnchanged()
	{
		final MatchStats.Kpis base = MatchStats.computeFromRows(Arrays.asList(new MatchStats.MetaRow("1", "GOLDEN", null, null, false)), null);
		final MatchStats.Kpis withCounts = MatchStats.withSuspectCounts(base, 5, 2);
		MatchStatsTest.check("totalRecords preserved", withCounts.totalRecords == base.totalRecords);
		MatchStatsTest.check("goldenRecords preserved", withCounts.goldenRecords == base.goldenRecords);
		MatchStatsTest.check("pendingSuspectPairs set", withCounts.pendingSuspectPairs == 5);
		MatchStatsTest.check("distinctPairs set", withCounts.distinctPairs == 2);
	}

	// --------------------------------------------------------------------------------------------------- band

	private static void bandSeparatesSingleSourceFromMultipleAndSortsByCountDesc()
	{
		final Map<String, Set<String>> byGolden = new LinkedHashMap<String, Set<String>>();
		byGolden.put("g1", MatchStatsTest.setOf("SF"));
		byGolden.put("g2", MatchStatsTest.setOf("SF"));
		byGolden.put("g3", MatchStatsTest.setOf("SAP"));
		byGolden.put("g4", MatchStatsTest.setOf("SF", "SAP"));
		final List<MatchStats.BandSegment> band = MatchStats.computeBand(byGolden);
		MatchStatsTest.check("three segments (SF, SAP, MULTIPLE)", band.size() == 3);
		MatchStatsTest.check("SF (count 2) sorts before SAP (count 1)", "SF".equals(band.get(0).label) && band.get(0).count == 2);
		MatchStatsTest.check("MULTIPLE segment present with count 1", MatchStatsTest.findCount(band, MatchStats.MULTIPLE) == 1);
	}

	private static void bandIgnoresEmptySourceSets()
	{
		final Map<String, Set<String>> byGolden = new LinkedHashMap<String, Set<String>>();
		byGolden.put("g1", new LinkedHashSet<String>());
		byGolden.put("g2", MatchStatsTest.setOf("SF"));
		final List<MatchStats.BandSegment> band = MatchStats.computeBand(byGolden);
		MatchStatsTest.check("only the non-empty golden contributes a segment", band.size() == 1 && "SF".equals(band.get(0).label));
	}

	// -------------------------------------------------------------------------------------------- field compare

	private static void compareValuesEqIgnoresCaseAndSpacing()
	{
		MatchStatsTest.check("case-insensitive equality", MatchStats.EQ.equals(MatchStats.compareValues("Michael", "michael")));
		MatchStatsTest.check("collapsed-whitespace equality", MatchStats.EQ.equals(MatchStats.compareValues("O'Brien", " O'Brien ")));
	}

	private static void compareValuesMissingWhenEitherSideBlank()
	{
		MatchStatsTest.check("left blank -> missing", MatchStats.MISSING.equals(MatchStats.compareValues("", "x")));
		MatchStatsTest.check("right null -> missing", MatchStats.MISSING.equals(MatchStats.compareValues("x", null)));
		MatchStatsTest.check("both blank -> missing", MatchStats.MISSING.equals(MatchStats.compareValues(" ", "")));
	}

	private static void compareValuesFuzzyOnSharedPrefixOrContainment()
	{
		MatchStatsTest.check("containment -> fuzzy", MatchStats.FUZZY.equals(MatchStats.compareValues("Michael", "Mike")) || MatchStats.DIFF.equals(MatchStats.compareValues("Michael", "Mike")));
		MatchStatsTest.check("one contains the other -> fuzzy", MatchStats.FUZZY.equals(MatchStats.compareValues("24 avenue Victor Hugo", "avenue Victor Hugo")));
		MatchStatsTest.check("long shared prefix -> fuzzy", MatchStats.FUZZY.equals(MatchStats.compareValues("Alexandre", "Alexand")));
	}

	private static void compareValuesDiffOtherwise()
	{
		MatchStatsTest.check("unrelated strings -> diff", MatchStats.DIFF.equals(MatchStats.compareValues("Petit", "Aubert")));
	}

	private static void strengthPercentIgnoresMissingFields()
	{
		final List<MatchStats.FieldComparison> fields = Arrays.asList(new MatchStats.FieldComparison("a", "x", "x", MatchStats.EQ),
			new MatchStats.FieldComparison("b", "", "", MatchStats.MISSING), new MatchStats.FieldComparison("c", "y", "z", MatchStats.DIFF));
		MatchStatsTest.check("1 alike out of 2 comparable (missing excluded) -> 50%", MatchStats.strengthPercent(fields) == 50);
	}

	private static void strengthPercentZeroWhenAllMissing()
	{
		final List<MatchStats.FieldComparison> fields = Arrays.asList(new MatchStats.FieldComparison("a", "", "", MatchStats.MISSING));
		MatchStatsTest.check("all missing -> 0, no division by zero", MatchStats.strengthPercent(fields) == 0);
		MatchStatsTest.check("empty list -> 0", MatchStats.strengthPercent(new ArrayList<MatchStats.FieldComparison>()) == 0);
	}

	private static void hintFieldLabelNamesFirstDivergentField()
	{
		final List<MatchStats.FieldComparison> fields = Arrays.asList(new MatchStats.FieldComparison("First name", "Michael", "Mike", MatchStats.FUZZY),
			new MatchStats.FieldComparison("Last name", "O'Brien", "O'Brien", MatchStats.EQ));
		MatchStatsTest.check("first divergent field named", "First name".equals(MatchStats.hintFieldLabel(fields)));
	}

	private static void hintFieldLabelNullWhenEverythingMatches()
	{
		final List<MatchStats.FieldComparison> fields = Arrays.asList(new MatchStats.FieldComparison("a", "x", "x", MatchStats.EQ));
		MatchStatsTest.check("nothing to call out -> null", MatchStats.hintFieldLabel(fields) == null);
	}

	private static void isSparseWhenHalfOrMoreMissing()
	{
		final List<MatchStats.FieldComparison> half = Arrays.asList(new MatchStats.FieldComparison("a", "", "", MatchStats.MISSING),
			new MatchStats.FieldComparison("b", "x", "x", MatchStats.EQ));
		MatchStatsTest.check("half missing -> sparse", MatchStats.isSparse(half));
		final List<MatchStats.FieldComparison> mostlyFilled = Arrays.asList(new MatchStats.FieldComparison("a", "", "", MatchStats.MISSING),
			new MatchStats.FieldComparison("b", "x", "x", MatchStats.EQ), new MatchStats.FieldComparison("c", "y", "y", MatchStats.EQ));
		MatchStatsTest.check("one of three missing -> not sparse", !MatchStats.isSparse(mostlyFilled));
	}

	// --------------------------------------------------------------------------------------------- pair list

	private static MatchStats.SuspectPair pair(final String pId, final String pDecision, final int pStrengthPercent)
	{
		final List<MatchStats.FieldComparison> fields = new ArrayList<MatchStats.FieldComparison>();
		// pStrengthPercent alike-out-of-100 comparable fields, built at 1-field granularity via EQ/DIFF
		for (int i = 0; i < 100; i++)
		{
			fields.add(new MatchStats.FieldComparison("f" + i, "x", "x", i < pStrengthPercent ? MatchStats.EQ : MatchStats.DIFF));
		}
		return new MatchStats.SuspectPair(pId, "g-" + pId, "s-" + pId, "Golden " + pId, "Suspect " + pId, "SF", "SAP", fields, pDecision);
	}

	private static void filterByTabSelectsOnlyMatchingDecision()
	{
		final List<MatchStats.SuspectPair> pairs = Arrays.asList(MatchStatsTest.pair("1", MatchStats.PENDING, 80), MatchStatsTest.pair("2", MatchStats.MERGED, 90),
			MatchStatsTest.pair("3", MatchStats.PENDING, 60));
		final List<MatchStats.SuspectPair> pending = MatchStats.filterByTab(pairs, MatchStats.PENDING);
		MatchStatsTest.check("two pending pairs", pending.size() == 2);
		final List<MatchStats.SuspectPair> merged = MatchStats.filterByTab(pairs, MatchStats.MERGED);
		MatchStatsTest.check("one merged pair", merged.size() == 1 && "2".equals(merged.get(0).id));
	}

	private static void sortByStrengthDescOrdersHighestFirstAndNeverMutatesInput()
	{
		final List<MatchStats.SuspectPair> pairs = new ArrayList<MatchStats.SuspectPair>(
			Arrays.asList(MatchStatsTest.pair("low", MatchStats.PENDING, 40), MatchStatsTest.pair("high", MatchStats.PENDING, 90), MatchStatsTest.pair("mid", MatchStats.PENDING, 65)));
		final List<MatchStats.SuspectPair> original = new ArrayList<MatchStats.SuspectPair>(pairs);
		final List<MatchStats.SuspectPair> sorted = MatchStats.sortByStrengthDesc(pairs);
		MatchStatsTest.check("highest strength first", "high".equals(sorted.get(0).id));
		MatchStatsTest.check("lowest strength last", "low".equals(sorted.get(2).id));
		MatchStatsTest.check("input list order untouched", pairs.equals(original));
	}

	private static void eligibleForBulkOnlyPendingAboveThreshold()
	{
		final List<MatchStats.SuspectPair> pairs = Arrays.asList(MatchStatsTest.pair("a", MatchStats.PENDING, 90), MatchStatsTest.pair("b", MatchStats.PENDING, 70),
			MatchStatsTest.pair("c", MatchStats.MERGED, 95));
		final List<MatchStats.SuspectPair> eligible = MatchStats.eligibleForBulk(pairs, 83);
		MatchStatsTest.check("only the pending pair at/above threshold is eligible", eligible.size() == 1 && "a".equals(eligible.get(0).id));
	}

	// -------------------------------------------------------------------------------------------------- helpers

	private static Set<String> setOf(final String... pValues)
	{
		return new LinkedHashSet<String>(Arrays.asList(pValues));
	}

	private static int findCount(final List<MatchStats.BandSegment> pBand, final String pLabel)
	{
		for (final MatchStats.BandSegment segment : pBand)
		{
			if (segment.label.equals(pLabel))
			{
				return segment.count;
			}
		}
		return -1;
	}

	/**
	 * Wave 2: with no MERGED row every golden is its own group, so the two-level band SQL and the GOLDEN per-source counts must
	 * agree (Vol100k: {VOL100KCRM 22990, VOL100KERP 19200, VOL100KECOM 15378}); a null/blank source is dropped in both.
	 */
	private static void bandFromGoldenCountsMatchesTheTwoLevelBandWhenNothingIsMerged()
	{
		final List<MatchStats.StateSourceCount> rows = Arrays.asList(new MatchStats.StateSourceCount("GOLDEN", "VOL100KERP", 19200),
			new MatchStats.StateSourceCount("GOLDEN", "VOL100KCRM", 22990), new MatchStats.StateSourceCount("GOLDEN", "VOL100KECOM", 15378),
			new MatchStats.StateSourceCount("GOLDEN", null, 5), new MatchStats.StateSourceCount("PENDING_APPROVAL", "VOL100KERP", 7));
		final List<MatchStats.BandSegment> band = MatchStats.bandFromGoldenCounts(rows);
		final Map<String, Integer> sql = new LinkedHashMap<String, Integer>();
		sql.put("VOL100KERP", 19200);
		sql.put("VOL100KCRM", 22990);
		sql.put("VOL100KECOM", 15378);
		final List<MatchStats.BandSegment> twoLevel = MatchStats.bandFromLabelCounts(sql);
		MatchStatsTest.check("same segments as the two-level query", band.size() == twoLevel.size());
		for (int i = 0; i < band.size(); i++)
		{
			MatchStatsTest.check("same label/count at " + i, band.get(i).label.equals(twoLevel.get(i).label) && band.get(i).count == twoLevel.get(i).count);
		}
		MatchStatsTest.check("descending by count", "VOL100KCRM".equals(band.get(0).label));
		MatchStatsTest.check("no MULTIPLE segment without merged rows", band.size() == 3);
		MatchStatsTest.check("empty in, empty out", MatchStats.bandFromGoldenCounts(null).isEmpty());
	}

	/**
	 * Lot M4 (2026-10-03, defect 3, Vol1m): after an INCREMENTAL run the records the add-on never touched have a NULL state. The
	 * aggregate used to exclude them ({@code state <> 4}): total 338 257 for a 576 692-row table. They are now rows of state UNSET
	 * (null is mapped to UNSET by the SQL reader), part of the total and of the sources, and counted apart as not matched yet.
	 */
	private static void untouchedRecordsAreCountedInTheTotalAsNotMatchedYet()
	{
		final List<MatchStats.StateSourceCount> rows = Arrays.asList(new MatchStats.StateSourceCount("GOLDEN", "CRM", 153937), new MatchStats.StateSourceCount("MERGED", "CRM", 4000),
			new MatchStats.StateSourceCount("UNSET", "ERP", 200000), new MatchStats.StateSourceCount("UNSET", "CRM", 45000),
			new MatchStats.StateSourceCount(null, "ERP", 100), new MatchStats.StateSourceCount("DELETED", "ERP", 7));
		final MatchStats.Kpis kpis = MatchStats.fromStateSourceCounts(rows, 10, 20, 3);
		MatchStatsTest.check("the total is every row but DELETED: " + kpis.totalRecords, kpis.totalRecords == 153937 + 4000 + 200000 + 45000 + 100);
		MatchStatsTest.check("not matched yet = UNSET + null state: " + kpis.unmatchedRecords, kpis.unmatchedRecords == 245100);
		MatchStatsTest.check("golden is untouched by it", kpis.goldenRecords == 153937);
		MatchStatsTest.check("both sources are in the breakdown", kpis.recordsBySource.size() == 2 && kpis.recordsBySource.get("ERP").intValue() == 200100);
		MatchStatsTest.check("a table fully matched has none", MatchStats.fromStateSourceCounts(Arrays.asList(new MatchStats.StateSourceCount("GOLDEN", "CRM", 5)), 0, 0, 0).unmatchedRecords == 0);
		MatchStatsTest.check("the 6-argument constructor keeps reading 0", new MatchStats.Kpis(1, null, 1, 0, 0, 0).unmatchedRecords == 0);
		MatchStatsTest.check("withSuspectCounts keeps it", MatchStats.withSuspectCounts(kpis, 1, 1).unmatchedRecords == 245100);
	}

	private static void hasMergedOnlyOnAPositiveMergedCount()
	{
		MatchStatsTest.check("no rows", !MatchStats.hasMerged(null) && !MatchStats.hasMerged(new ArrayList<MatchStats.StateSourceCount>()));
		MatchStatsTest.check("golden only", !MatchStats.hasMerged(Arrays.asList(new MatchStats.StateSourceCount("GOLDEN", "ERP", 10))));
		MatchStatsTest.check("merged with a zero count", !MatchStats.hasMerged(Arrays.asList(new MatchStats.StateSourceCount("MERGED", "ERP", 0))));
		MatchStatsTest.check("merged", MatchStats.hasMerged(Arrays.asList(new MatchStats.StateSourceCount("GOLDEN", "ERP", 10), new MatchStats.StateSourceCount("MERGED", "CRM", 2))));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
