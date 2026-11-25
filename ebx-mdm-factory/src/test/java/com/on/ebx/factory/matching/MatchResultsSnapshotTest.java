package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws. Covers {@link MatchResultsSnapshot} (wave 2, lot V2-A): the JSON round trip of the
 * computed part of the matching-results block, its size budget (a JSON is never cut), the staleness rule, the detail merge that
 * keeps the run row's other members, the local patch applied when pairs are decided, the corrected pending-pairs totals - and
 * the three states of {@link MatchResultsView} that read from it.
 */
public final class MatchResultsSnapshotTest
{
	public static void main(final String[] pArgs)
	{
		MatchResultsSnapshotTest.roundTripKeepsEveryComputedMember();
		MatchResultsSnapshotTest.unmatchedCountSurvivesTheRoundTripAndIsStatedOnTheScreen();
		MatchResultsSnapshotTest.wrongVersionOrGarbageDecodesToNull();
		MatchResultsSnapshotTest.budgetDropsDetailThenPairsAndStaysParseable();
		MatchResultsSnapshotTest.longValuesAreCut();
		MatchResultsSnapshotTest.stateRule();
		MatchResultsSnapshotTest.mergeIntoDetailKeepsOtherMembersAndReplacesOldResults();
		MatchResultsSnapshotTest.mergeIntoDetailReplacesNonJsonDetail();
		MatchResultsSnapshotTest.sizeWithoutSnapshotIgnoresTheResultsMember();
		MatchResultsSnapshotTest.acceptRemovesThePairAndEveryPairOfTheMergedRecord();
		MatchResultsSnapshotTest.rejectAddsADifferentLineAndKeepsOtherPairs();
		MatchResultsSnapshotTest.pendingTotalNeverGoesBelowZero();
		MatchResultsSnapshotTest.totalsArePairsNotGoldensNorTwiceThePairs();
		MatchResultsSnapshotTest.viewMissingShowsNoticeAndNoFigures();
		MatchResultsSnapshotTest.viewStaleShowsNoticeAndFigures();
		MatchResultsSnapshotTest.viewRefreshingHidesTheButton();
		MatchResultsSnapshotTest.viewDeferredPairsWritesAPlaceholderInsteadOfCards();
		System.out.println("MatchResultsSnapshotTest OK");
	}

	// ------------------------------------------------------------------------------------------------ builders

	private static MatchStats.SuspectPair pair(final int pIndex)
	{
		final List<MatchStats.FieldComparison> fields = new ArrayList<MatchStats.FieldComparison>();
		fields.add(new MatchStats.FieldComparison("lastName", "Martin " + pIndex, "Martin " + pIndex, MatchStats.EQ));
		fields.add(new MatchStats.FieldComparison("firstName", "Jean-Baptiste", "Jean Baptiste", MatchStats.FUZZY));
		fields.add(new MatchStats.FieldComparison("phone", "", "0102030405", MatchStats.MISSING));
		return new MatchStats.SuspectPair((1000 + pIndex) + "|" + (5000 + pIndex), String.valueOf(1000 + pIndex), String.valueOf(5000 + pIndex), "Martin " + pIndex,
			"Martin " + pIndex, "ERP", "CRM", fields, MatchStats.PENDING);
	}

	private static MatchResultsView.Input input(final int pPairs)
	{
		final MatchResultsView.Input in = new MatchResultsView.Input();
		in.entityId = "Personne";
		in.entityName = "Personne";
		final Map<String, Integer> bySource = new LinkedHashMap<String, Integer>();
		bySource.put("ERP", 19200);
		bySource.put("CRM", 22990);
		in.kpis = new MatchStats.Kpis(42190, bySource, 42190, 0, 33374, 2);
		in.goldensWithSuspects = 46663;
		in.band = new ArrayList<MatchStats.BandSegment>(Arrays.asList(new MatchStats.BandSegment("CRM", 22990), new MatchStats.BandSegment("ERP", 19200)));
		in.colorBySource = new LinkedHashMap<String, String>();
		in.colorBySource.put("ERP", "#1a73e8");
		in.colorBySource.put("CRM", "#188038");
		in.treeDetail = new LinkedHashMap<String, MatchResultsView.TreeDetail>();
		for (int i = 0; i < pPairs; i++)
		{
			final MatchStats.SuspectPair pair = MatchResultsSnapshotTest.pair(i);
			in.pendingPairs.add(pair);
			in.treeDetail.put(pair.id, new MatchResultsView.TreeDetail(true, null, Arrays.asList("Root email", "Names"), "Names",
				Arrays.asList(new MatchResultsView.EngineFieldScore("lastName", 0.93))));
		}
		in.pendingPairsTotal = 33374;
		in.maxDetailPairs = 30;
		in.mergedGroups.add(new MatchResultsView.GroupSummary("7", "Dupont", "ERP", Arrays.asList("ERP", "CRM"), Arrays.asList("7", "9")));
		in.distinctPairs.add(new MatchResultsView.DistinctPair("11", "12", "admin", "2026-09-29T10:00:00Z"));
		in.distinctPairs.add(new MatchResultsView.DistinctPair("13", "14", null, null));
		return in;
	}

	// ------------------------------------------------------------------------------------------------ round trip

	private static void roundTripKeepsEveryComputedMember()
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.input(3);
		final String json = MatchResultsSnapshot.encode(in, 123456789L, false);
		final MatchResultsSnapshot.Decoded d = MatchResultsSnapshot.decode(json);
		MatchResultsSnapshotTest.check("decoded", d != null);
		MatchResultsSnapshotTest.check("computedAt", d.computedAt == 123456789L);
		MatchResultsSnapshotTest.check("not stale", !d.stale);
		final MatchResultsView.Input out = d.input;
		MatchResultsSnapshotTest.check("kpis total", out.kpis.totalRecords == 42190 && out.kpis.goldenRecords == 42190);
		MatchResultsSnapshotTest.check("kpis pending/distinct/merged", out.kpis.pendingSuspectPairs == 33374 && out.kpis.distinctPairs == 2 && out.kpis.mergedGroups == 0);
		MatchResultsSnapshotTest.check("kpis by source, in order", new ArrayList<String>(out.kpis.recordsBySource.keySet()).equals(Arrays.asList("ERP", "CRM"))
			&& out.kpis.recordsBySource.get("CRM").intValue() == 22990);
		MatchResultsSnapshotTest.check("goldens with suspects kept apart from pairs", out.goldensWithSuspects == 46663 && out.pendingPairsTotal == 33374);
		MatchResultsSnapshotTest.check("band", out.band.size() == 2 && "CRM".equals(out.band.get(0).label) && out.band.get(0).count == 22990);
		MatchResultsSnapshotTest.check("colours", "#1a73e8".equals(out.colorBySource.get("ERP")));
		MatchResultsSnapshotTest.check("pairs", out.pendingPairs.size() == 3);
		final MatchStats.SuspectPair first = out.pendingPairs.get(0);
		MatchResultsSnapshotTest.check("pair identity", "1000|5000".equals(first.id) && "1000".equals(first.goldenKey) && "5000".equals(first.suspectKey));
		MatchResultsSnapshotTest.check("pair labels and sources", "Martin 0".equals(first.goldenLabel) && "ERP".equals(first.goldenSource) && "CRM".equals(first.suspectSource));
		MatchResultsSnapshotTest.check("pair decision", MatchStats.PENDING.equals(first.decision));
		MatchResultsSnapshotTest.check("pair fields", first.fields.size() == 3 && MatchStats.FUZZY.equals(first.fields.get(1).verdict) && "".equals(first.fields.get(2).left)
			&& "0102030405".equals(first.fields.get(2).right));
		MatchResultsSnapshotTest.check("max detail", out.maxDetailPairs == 30);
		final MatchResultsView.TreeDetail tree = out.treeDetail.get("1000|5000");
		MatchResultsSnapshotTest.check("tree detail", tree != null && tree.available && tree.nodeNames.size() == 2 && "Names".equals(tree.lastNodeName)
			&& tree.fieldScores.size() == 1 && tree.fieldScores.get(0).score == 0.93 && tree.unavailableReason == null);
		MatchResultsSnapshotTest.check("merged", out.mergedGroups.size() == 1 && "Dupont".equals(out.mergedGroups.get(0).goldenLabel) && out.mergedGroups.get(0).memberKeys.size() == 2);
		MatchResultsSnapshotTest.check("distinct", out.distinctPairs.size() == 2 && "admin".equals(out.distinctPairs.get(0).decidedBy) && out.distinctPairs.get(1).decidedBy == null);
		// a second round trip is byte-identical: nothing is lost or reordered
		MatchResultsSnapshotTest.check("stable re-encode", json.equals(MatchResultsSnapshot.encode(out, 123456789L, false)));
		MatchResultsSnapshotTest.check("stale flag survives", MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1L, true)).stale);
	}

	/** Lot M4 (2026-10-03, defect 3): the not-matched-yet count is stored, read back, and said on the screen only when it is not zero. */
	private static void unmatchedCountSurvivesTheRoundTripAndIsStatedOnTheScreen()
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.input(0);
		in.kpis = new MatchStats.Kpis(576692, new LinkedHashMap<String, Integer>(), 153937, 10, 20, 3, 245100);
		final MatchResultsSnapshot.Decoded d = MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1L, false));
		MatchResultsSnapshotTest.check("unmatched kept", d != null && d.input.kpis.unmatchedRecords == 245100 && d.input.kpis.totalRecords == 576692);
		final String html = MatchResultsView.render(d.input, java.util.Locale.ENGLISH);
		MatchResultsSnapshotTest.check("the screen says how many were not matched yet", html.contains("245100 of the 576692 records have not been through matching yet"));
		final MatchResultsView.Input none = MatchResultsSnapshotTest.input(0);
		MatchResultsSnapshotTest.check("no sentence when everything went through matching", !MatchResultsView.render(none, java.util.Locale.ENGLISH).contains("have not been through matching yet"));
		// a snapshot stored before this lot has no member: reads 0, never fails
		final String old = MatchResultsSnapshot.encode(none, 1L, false).replace(",\"unmatched\":0", "");
		MatchResultsSnapshotTest.check("older snapshot decodes", MatchResultsSnapshot.decode(old) != null && MatchResultsSnapshot.decode(old).input.kpis.unmatchedRecords == 0);
	}

	private static void wrongVersionOrGarbageDecodesToNull()
	{
		MatchResultsSnapshotTest.check("garbage", MatchResultsSnapshot.decode("not json {") == null);
		MatchResultsSnapshotTest.check("empty", MatchResultsSnapshot.decode("") == null && MatchResultsSnapshot.decode(null) == null);
		MatchResultsSnapshotTest.check("other version", MatchResultsSnapshot.decode("{\"v\":99,\"at\":1}") == null);
		MatchResultsSnapshotTest.check("no version", MatchResultsSnapshot.decode("{\"at\":1}") == null);
		MatchResultsSnapshotTest.check("detail without results", MatchResultsSnapshot.fromDetail("{\"scopeMode\":\"ALL\"}") == null);
		MatchResultsSnapshotTest.check("detail not json", MatchResultsSnapshot.fromDetail("Matching failed") == null);
		MatchResultsSnapshotTest.check("detail empty", MatchResultsSnapshot.fromDetail("") == null && MatchResultsSnapshot.fromDetail(null) == null);
	}

	private static void budgetDropsDetailThenPairsAndStaysParseable()
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.input(60);
		final String full = MatchResultsSnapshot.encode(in, 1L, false, 10000000);
		MatchResultsSnapshotTest.check("full has the detail", full.contains("\"nodes\""));
		// budget just under the full size: the per-pair decision-tree detail goes first, every pair stays
		final String noTree = MatchResultsSnapshot.encode(in, 1L, false, full.length() - 1);
		MatchResultsSnapshotTest.check("tree dropped first", !noTree.contains("\"nodes\"") && noTree.length() < full.length());
		MatchResultsSnapshotTest.check("all pairs kept when only the tree goes", MatchResultsSnapshot.decode(noTree).input.pendingPairs.size() == 60);
		// a much smaller budget: pairs are halved until it fits, totals kept, still valid JSON
		final int budget = noTree.length() / 4;
		final String small = MatchResultsSnapshot.encode(in, 1L, false, budget);
		MatchResultsSnapshotTest.check("fits the budget", small.length() <= budget);
		final MatchResultsSnapshot.Decoded d = MatchResultsSnapshot.decode(small);
		MatchResultsSnapshotTest.check("still parseable", d != null);
		MatchResultsSnapshotTest.check("fewer pairs", d.input.pendingPairs.size() < 60);
		MatchResultsSnapshotTest.check("totals kept, so 'N more not shown' stays right", d.input.pendingPairsTotal == 33374 && d.input.kpis.pendingSuspectPairs == 33374);
		MatchResultsSnapshotTest.check("tree only for pairs kept", d.input.treeDetail.size() <= d.input.pendingPairs.size());
		// the default budget leaves room in a 100 000-character column
		MatchResultsSnapshotTest.check("default budget below the column cap", MatchResultsSnapshot.DEFAULT_BUDGET_CHARS < 100000 - 20000);
		MatchResultsSnapshotTest.check("60 pairs of 3 fields fit the default budget whole", MatchResultsSnapshot.encode(in, 1L, false).length() <= MatchResultsSnapshot.DEFAULT_BUDGET_CHARS);
	}

	private static void longValuesAreCut()
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.input(1);
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 500; i++)
		{
			longText.append('x');
		}
		in.pendingPairs.set(0, new MatchStats.SuspectPair("1|2", "1", "2", longText.toString(), "b", "ERP", "CRM",
			Arrays.asList(new MatchStats.FieldComparison("notes", longText.toString(), "y", MatchStats.DIFF)), MatchStats.PENDING));
		final MatchStats.SuspectPair back = MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1L, false)).input.pendingPairs.get(0);
		MatchResultsSnapshotTest.check("label cut", back.goldenLabel.length() == MatchResultsSnapshot.MAX_VALUE_CHARS && back.goldenLabel.endsWith("…"));
		MatchResultsSnapshotTest.check("value cut", back.fields.get(0).left.length() == MatchResultsSnapshot.MAX_VALUE_CHARS);
		MatchResultsSnapshotTest.check("short value untouched", "y".equals(back.fields.get(0).right));
	}

	// ------------------------------------------------------------------------------------------------ state rule

	private static void stateRule()
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.input(1);
		final MatchResultsSnapshot.Decoded fresh = MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1000L, false));
		final MatchResultsSnapshot.Decoded stale = MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1000L, true));
		MatchResultsSnapshotTest.check("nothing stored = missing", MatchResultsSnapshot.MISSING.equals(MatchResultsSnapshot.state(null, 0)));
		MatchResultsSnapshotTest.check("stored, no master load known = fresh", MatchResultsSnapshot.FRESH.equals(MatchResultsSnapshot.state(fresh, 0)));
		MatchResultsSnapshotTest.check("computed after the last master load = fresh", MatchResultsSnapshot.FRESH.equals(MatchResultsSnapshot.state(fresh, 999L)));
		MatchResultsSnapshotTest.check("computed exactly at the load start = fresh", MatchResultsSnapshot.FRESH.equals(MatchResultsSnapshot.state(fresh, 1000L)));
		MatchResultsSnapshotTest.check("computed before the last master load = stale", MatchResultsSnapshot.STALE.equals(MatchResultsSnapshot.state(fresh, 1001L)));
		MatchResultsSnapshotTest.check("flagged by a decision = stale", MatchResultsSnapshot.STALE.equals(MatchResultsSnapshot.state(stale, 0)));
		final MatchResultsSnapshot.Decoded unknownTime = MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 0L, false));
		MatchResultsSnapshotTest.check("no computation time: the load rule never fires", MatchResultsSnapshot.FRESH.equals(MatchResultsSnapshot.state(unknownTime, 5000L)));
	}

	// ------------------------------------------------------------------------------------------------ detail merge

	private static void mergeIntoDetailKeepsOtherMembersAndReplacesOldResults()
	{
		final String snapshot = MatchResultsSnapshot.encode(MatchResultsSnapshotTest.input(1), 1L, false);
		final String detail = "{\"scopeMode\":\"ALL\",\"process\":{\"status\":\"DONE\"},\"results\":{\"v\":1,\"old\":true}}";
		final String merged = MatchResultsSnapshot.mergeIntoDetail(detail, snapshot);
		MatchResultsSnapshotTest.check("other members kept", merged.contains("\"scopeMode\":\"ALL\"") && merged.contains("\"process\":{\"status\":\"DONE\"}"));
		MatchResultsSnapshotTest.check("old results replaced", !merged.contains("\"old\""));
		final MatchResultsSnapshot.Decoded d = MatchResultsSnapshot.fromDetail(merged);
		MatchResultsSnapshotTest.check("results readable from the merged detail", d != null && d.input.pendingPairsTotal == 33374);
		// merging twice does not grow or duplicate
		MatchResultsSnapshotTest.check("idempotent", merged.equals(MatchResultsSnapshot.mergeIntoDetail(merged, snapshot)));
	}

	private static void mergeIntoDetailReplacesNonJsonDetail()
	{
		final String snapshot = MatchResultsSnapshot.encode(MatchResultsSnapshotTest.input(1), 1L, false);
		MatchResultsSnapshotTest.check("blank detail", MatchResultsSnapshot.fromDetail(MatchResultsSnapshot.mergeIntoDetail("", snapshot)) != null);
		MatchResultsSnapshotTest.check("null detail", MatchResultsSnapshot.fromDetail(MatchResultsSnapshot.mergeIntoDetail(null, snapshot)) != null);
		MatchResultsSnapshotTest.check("text detail", MatchResultsSnapshot.fromDetail(MatchResultsSnapshot.mergeIntoDetail("some failure text", snapshot)) != null);
		MatchResultsSnapshotTest.check("json array detail", MatchResultsSnapshot.fromDetail(MatchResultsSnapshot.mergeIntoDetail("[1,2]", snapshot)) != null);
	}

	private static void sizeWithoutSnapshotIgnoresTheResultsMember()
	{
		final String snapshot = MatchResultsSnapshot.encode(MatchResultsSnapshotTest.input(10), 1L, false);
		final String merged = MatchResultsSnapshot.mergeIntoDetail("{\"a\":1}", snapshot);
		MatchResultsSnapshotTest.check("only the other members count", MatchResultsSnapshot.sizeWithoutSnapshot(merged) == "{\"a\":1}".length());
		MatchResultsSnapshotTest.check("blank", MatchResultsSnapshot.sizeWithoutSnapshot("") == 0 && MatchResultsSnapshot.sizeWithoutSnapshot("text") == 0);
	}

	// ------------------------------------------------------------------------------------------------ decisions

	private static MatchResultsSnapshot.Decoded decoded(final int pPairs)
	{
		return MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(MatchResultsSnapshotTest.input(pPairs), 1000L, false));
	}

	private static void acceptRemovesThePairAndEveryPairOfTheMergedRecord()
	{
		final MatchResultsSnapshot.Decoded d = MatchResultsSnapshotTest.decoded(3);
		// pair 1000|5000 accepted; another pair (2001 with 5000) shares the merged record 5000 and must leave too
		d.input.pendingPairs.add(new MatchStats.SuspectPair("2001|5000", "2001", "5000", "x", "y", "ERP", "CRM", new ArrayList<MatchStats.FieldComparison>(), MatchStats.PENDING));
		final MatchResultsSnapshot.Decoded patched = MatchResultsSnapshot.applyDecisions(d, Arrays.asList(new MatchResultsSnapshot.Decision("1000", "5000", true, "admin", "now")));
		final List<String> ids = new ArrayList<String>();
		for (final MatchStats.SuspectPair pair : patched.input.pendingPairs)
		{
			ids.add(pair.id);
		}
		MatchResultsSnapshotTest.check("decided pair and the other pair of the merged record gone, the rest kept", ids.equals(Arrays.asList("1001|5001", "1002|5002")));
		MatchResultsSnapshotTest.check("their tree detail gone too", !patched.input.treeDetail.containsKey("1000|5000") && patched.input.treeDetail.containsKey("1001|5001"));
		MatchResultsSnapshotTest.check("flagged stale", patched.stale);
		MatchResultsSnapshotTest.check("pending total and KPI drop by one decision", patched.input.pendingPairsTotal == 33373 && patched.input.kpis.pendingSuspectPairs == 33373);
		MatchResultsSnapshotTest.check("nothing guessed about merged groups or records", patched.input.kpis.mergedGroups == 0 && patched.input.kpis.totalRecords == 42190
			&& patched.input.mergedGroups.size() == 1 && patched.input.distinctPairs.size() == 2);
		MatchResultsSnapshotTest.check("computation time kept (the refresh will renew it)", patched.computedAt == 1000L);
		MatchResultsSnapshotTest.check("the input snapshot is not mutated", d.input.pendingPairsTotal == 33374 && !d.stale);
	}

	private static void rejectAddsADifferentLineAndKeepsOtherPairs()
	{
		final MatchResultsSnapshot.Decoded d = MatchResultsSnapshotTest.decoded(3);
		// orientation does not matter: the decision names the suspect first
		final MatchResultsSnapshot.Decoded patched = MatchResultsSnapshot.applyDecisions(d,
			Arrays.asList(new MatchResultsSnapshot.Decision("5001", "1001", false, "admin", "2026-09-30T10:00:00Z")));
		MatchResultsSnapshotTest.check("the pair left in either orientation", patched.input.pendingPairs.size() == 2 && !patched.input.treeDetail.containsKey("1001|5001"));
		MatchResultsSnapshotTest.check("different count and line", patched.input.kpis.distinctPairs == 3 && patched.input.distinctPairs.size() == 3
			&& "5001".equals(patched.input.distinctPairs.get(0).sourceId) && "admin".equals(patched.input.distinctPairs.get(0).decidedBy));
		MatchResultsSnapshotTest.check("a rejection merges nothing: the other pairs stay", patched.input.pendingPairs.get(0).id.equals("1000|5000"));
	}

	private static void pendingTotalNeverGoesBelowZero()
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.input(1);
		in.pendingPairsTotal = 1;
		in.kpis = new MatchStats.Kpis(10, new LinkedHashMap<String, Integer>(), 10, 0, 1, 0);
		final MatchResultsSnapshot.Decoded d = MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1L, false));
		final MatchResultsSnapshot.Decoded patched = MatchResultsSnapshot.applyDecisions(d, Arrays.asList(new MatchResultsSnapshot.Decision("1000", "5000", true, "a", "t"),
			new MatchResultsSnapshot.Decision("8", "9", true, "a", "t"), new MatchResultsSnapshot.Decision("10", "11", false, "a", "t")));
		MatchResultsSnapshotTest.check("floor at zero", patched.input.pendingPairsTotal == 0 && patched.input.kpis.pendingSuspectPairs == 0);
		MatchResultsSnapshotTest.check("a rejection still counts as different", patched.input.kpis.distinctPairs == 1);
	}

	/**
	 * The live figures of 2026-09-30 (Vol100k, {@code Personne}): 33 374 rows in {@code Suspected} = 33 374 distinct pairs, 46 663
	 * goldens flagged, {@code SUM(suspectedCount)} 66 748 = 2 x the pairs. The block shows the pairs; the goldens are kept apart.
	 */
	private static void totalsArePairsNotGoldensNorTwiceThePairs()
	{
		final int pairs = 33374;
		final long goldensFlagged = 46663;
		final long suspectedCountSum = 66748;
		MatchResultsSnapshotTest.check("the sum counts each pair from both records", suspectedCountSum == 2L * pairs);
		final MatchResultsView.Input in = MatchResultsSnapshotTest.input(0);
		in.pendingPairsTotal = pairs;
		in.goldensWithSuspects = goldensFlagged;
		in.kpis = new MatchStats.Kpis(57568, new LinkedHashMap<String, Integer>(), 57568, 0, pairs, 0);
		final String html = MatchResultsView.render(in, Locale.ENGLISH);
		MatchResultsSnapshotTest.check("the KPI card shows the pairs", html.contains("<span class=\"mdmf-match-kpi-val\">33374</span>"));
		MatchResultsSnapshotTest.check("neither the goldens nor twice the pairs is shown as pairs", !html.contains(">46663<") && !html.contains(">66748<"));
		final MatchResultsSnapshot.Decoded back = MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1L, false));
		MatchResultsSnapshotTest.check("both figures survive storage, each under its own name", back.input.pendingPairsTotal == pairs && back.input.goldensWithSuspects == goldensFlagged);
	}

	// ------------------------------------------------------------------------------------------------ view states

	private static MatchResultsView.Input viewInput(final String pState)
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.input(2);
		in.snapshotState = pState;
		in.canDecide = true;
		return in;
	}

	private static void viewMissingShowsNoticeAndNoFigures()
	{
		final MatchResultsView.Input in = new MatchResultsView.Input();
		in.entityId = "Personne";
		in.entityName = "Personne";
		in.snapshotState = MatchResultsSnapshot.MISSING;
		in.canDecide = true;
		final String html = MatchResultsView.render(in, Locale.ENGLISH);
		MatchResultsSnapshotTest.check("notice", html.contains("mdmf-match-snapshot-notice") && html.contains("not stored yet"));
		MatchResultsSnapshotTest.check("refresh button for this entity", html.contains("data-mdmf-action=\"MATCH_RESULTS_REFRESH\"") && html.contains("data-mdmf-id=\"Personne\""));
		MatchResultsSnapshotTest.check("no KPI, no queue", !html.contains("mdmf-match-kpis") && !html.contains("mdmf-match-queue"));
		in.canDecide = false;
		MatchResultsSnapshotTest.check("button disabled for a non-administrator", MatchResultsView.render(in, Locale.ENGLISH).contains("MATCH_RESULTS_REFRESH\" data-mdmf-id=\"Personne\" disabled"));
	}

	private static void viewStaleShowsNoticeAndFigures()
	{
		final String html = MatchResultsView.render(MatchResultsSnapshotTest.viewInput(MatchResultsSnapshot.STALE), Locale.ENGLISH);
		MatchResultsSnapshotTest.check("notice with the button", html.contains("mdmf-match-snapshot-notice") && html.contains("MATCH_RESULTS_REFRESH"));
		MatchResultsSnapshotTest.check("figures and cards still shown", html.contains("mdmf-match-kpis") && html.contains("mdmf-match-pair-card"));
		final String fresh = MatchResultsView.render(MatchResultsSnapshotTest.viewInput(MatchResultsSnapshot.FRESH), Locale.ENGLISH);
		MatchResultsSnapshotTest.check("a fresh block has no notice", !fresh.contains("mdmf-match-snapshot-notice") && !fresh.contains("MATCH_RESULTS_REFRESH"));
	}

	private static void viewRefreshingHidesTheButton()
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.viewInput(MatchResultsSnapshot.STALE);
		in.refreshRunning = true;
		final String html = MatchResultsView.render(in, Locale.ENGLISH);
		MatchResultsSnapshotTest.check("says it is being refreshed, offers no second refresh", html.contains("being refreshed") && !html.contains("MATCH_RESULTS_REFRESH"));
	}

	private static void viewDeferredPairsWritesAPlaceholderInsteadOfCards()
	{
		final MatchResultsView.Input in = MatchResultsSnapshotTest.viewInput(MatchResultsSnapshot.FRESH);
		final String full = MatchResultsView.render(in, Locale.ENGLISH);
		in.deferPairs = true;
		final String light = MatchResultsView.render(in, Locale.ENGLISH);
		MatchResultsSnapshotTest.check("full render has the cards", full.contains("mdmf-match-pair-card") && full.contains("MATCH_GROUP_MERGE") && full.contains("MATCH_GROUP_REJECT"));
		MatchResultsSnapshotTest.check("deferred render has none of them", !light.contains("mdmf-match-pair-card") && !light.contains("MATCH_GROUP_MERGE") && !light.contains("MATCH_GROUP_REJECT"));
		MatchResultsSnapshotTest.check("placeholder wizard.js can find, with its fallback button", light.contains("data-mdmf-deferred-step=\"6\"") && light.contains("data-mdmf-action=\"STEP_VIEW\""));
		MatchResultsSnapshotTest.check("KPIs and tab counts stay", light.contains("mdmf-match-kpis") && light.contains("mdmf-match-tabs"));
		MatchResultsSnapshotTest.check("much lighter", light.length() * 2 < full.length());
		in.pendingPairs.clear();
		MatchResultsSnapshotTest.check("no pairs: no placeholder either", !MatchResultsView.render(in, Locale.ENGLISH).contains("data-mdmf-deferred-step"));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
