package com.on.ebx.factory.matching;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. The matching results block cut down to one person's EBX permissions ({@link MatchResultsMask}, 2026-10-14). */
public final class MatchResultsMaskTest
{
	public static void main(final String[] pArgs)
	{
		final MatchResultsView.Input stored = MatchResultsMaskTest.stored();

		// everything visible: the same block, figures included
		final MatchResultsView.Input all = MatchResultsMask.apply(stored, Arrays.asList("name", "city"), MatchResultsMaskTest.seen(null, null));
		MatchResultsMaskTest.check("all pairs", all.pendingPairs.size() == 2 && all.pendingPairsTotal == 7 && all.pendingPairs.get(0) == stored.pendingPairs.get(0));
		MatchResultsMaskTest.check("figures kept", all.kpis == stored.kpis && all.band.size() == 1 && all.goldensWithSuspects == 4);
		MatchResultsMaskTest.check("groups and distinct pairs kept", all.mergedGroups.size() == 1 && all.mergedGroups.get(0).memberKeys.size() == 2
			&& all.distinctPairs.size() == 1);

		// record 3 hidden: its pair, its group membership and its distinct pair disappear; the figures go
		final MatchResultsView.Input r3 = MatchResultsMask.apply(stored, Arrays.asList("name", "city"), MatchResultsMaskTest.seen(Collections.singleton("3"), null));
		MatchResultsMaskTest.check("pair with a hidden record left out", r3.pendingPairs.size() == 1 && "p1".equals(r3.pendingPairs.get(0).id));
		MatchResultsMaskTest.check("total drops by the pairs left out", r3.pendingPairsTotal == 6);
		MatchResultsMaskTest.check("tree detail follows its pair", r3.treeDetail.size() == 1 && r3.treeDetail.containsKey("p1"));
		MatchResultsMaskTest.check("hidden member left out of its group", r3.mergedGroups.get(0).memberKeys.equals(Collections.singletonList("2"))
			&& r3.mergedGroups.get(0).memberSources.equals(Collections.singletonList("CRM")));
		MatchResultsMaskTest.check("distinct pair with a hidden record left out", r3.distinctPairs.isEmpty());
		MatchResultsMaskTest.check("whole-table figures left out", r3.kpis == null && r3.band.isEmpty() && r3.goldensWithSuspects == 0L);
		MatchResultsMaskTest.check("the stored block is untouched", stored.pendingPairs.size() == 2 && stored.mergedGroups.get(0).memberKeys.size() == 2);

		// golden hidden: its group goes
		final MatchResultsView.Input g = MatchResultsMask.apply(stored, Arrays.asList("name", "city"), MatchResultsMaskTest.seen(Collections.singleton("1"), null));
		MatchResultsMaskTest.check("group of a hidden golden left out, and its pairs", g.mergedGroups.isEmpty() && g.pendingPairs.isEmpty());

		// field "city" hidden on record 2: blank on that side, verdict missing, label replaced by the key
		final MatchResultsView.Input f = MatchResultsMask.apply(stored, Arrays.asList("name", "city"),
			MatchResultsMaskTest.seen(null, Collections.singleton("2|city")));
		final MatchStats.SuspectPair p1 = f.pendingPairs.get(0);
		MatchResultsMaskTest.check("hidden value blank, visible side kept", "".equals(p1.fields.get(1).right) && "Paris".equals(p1.fields.get(1).left));
		MatchResultsMaskTest.check("verdict no longer tells equality", MatchStats.MISSING.equals(p1.fields.get(1).verdict) && MatchStats.EQ.equals(p1.fields.get(0).verdict));
		MatchResultsMaskTest.check("label of the record with a hidden compared field is its key", "2".equals(p1.suspectLabel) && "Alpha (1)".equals(p1.goldenLabel));
		MatchResultsMaskTest.check("a field hidden is still 'something hidden': figures go", f.kpis == null);

		// source system hidden on the golden
		final MatchResultsView.Input s = MatchResultsMask.apply(stored, Arrays.asList("name", "city"),
			MatchResultsMaskTest.seen(null, Collections.singleton("1|" + MatchResultsMask.SOURCE_FIELD)));
		MatchResultsMaskTest.check("hidden source blank on pair and group", "".equals(s.pendingPairs.get(0).goldenSource) && "".equals(s.mergedGroups.get(0).goldenSource)
			&& "Alpha".equals(s.mergedGroups.get(0).goldenLabel));
		// unknown title fields: a group's golden shows its key
		final MatchResultsView.Input u = MatchResultsMask.apply(stored, null, MatchResultsMaskTest.seen(null, null));
		MatchResultsMaskTest.check("no title fields known: the key", "1".equals(u.mergedGroups.get(0).goldenLabel));
		System.out.println("MatchResultsMaskTest OK");
	}

	private static MatchResultsView.Input stored()
	{
		final MatchResultsView.Input in = new MatchResultsView.Input();
		in.entityName = "Person";
		in.pendingPairs.add(new MatchStats.SuspectPair("p1", "1", "2", "Alpha (1)", "Alpha (2)", "ERP", "CRM", Arrays.asList(
			new MatchStats.FieldComparison("name", "Alpha", "Alpha", MatchStats.EQ), new MatchStats.FieldComparison("city", "Paris", "Paris", MatchStats.EQ)), null));
		in.pendingPairs.add(new MatchStats.SuspectPair("p2", "1", "3", "Alpha (1)", "Alfa (3)", "ERP", "WEB", Arrays.asList(
			new MatchStats.FieldComparison("name", "Alpha", "Alfa", MatchStats.FUZZY)), null));
		in.pendingPairsTotal = 7;
		in.treeDetail = new LinkedHashMap<String, MatchResultsView.TreeDetail>();
		in.treeDetail.put("p1", null);
		in.treeDetail.put("p2", null);
		in.mergedGroups.add(new MatchResultsView.GroupSummary("1", "Alpha", "ERP", Arrays.asList("CRM", "WEB"), Arrays.asList("2", "3")));
		in.distinctPairs.add(new MatchResultsView.DistinctPair("2", "3", "alice", "2026-10-01"));
		in.kpis = null;
		in.band.add(null);
		in.goldensWithSuspects = 4;
		return in;
	}

	/** Visible unless the key is in {@code pHiddenRecords} or "key|field" in {@code pHiddenFields}. */
	private static MatchResultsMask.Visibility seen(final Set<String> pHiddenRecords, final Set<String> pHiddenFields)
	{
		final Set<String> records = pHiddenRecords == null ? new HashSet<String>() : pHiddenRecords;
		final Set<String> fields = pHiddenFields == null ? new HashSet<String>() : pHiddenFields;
		return new MatchResultsMask.Visibility()
		{
			@Override
			public boolean record(final String pKey)
			{
				return !records.contains(pKey);
			}

			@Override
			public boolean field(final String pKey, final String pFieldName)
			{
				return !fields.contains(pKey + "|" + pFieldName);
			}
		};
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
