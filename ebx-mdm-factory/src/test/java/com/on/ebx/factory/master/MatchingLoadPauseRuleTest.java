package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. Lot MP (2026-10-04): the pure decisions of the matching pause during a master load and of its catch-up. */
public final class MatchingLoadPauseRuleTest
{
	public static void main(final String[] pArgs)
	{
		MatchingLoadPauseRuleTest.triggerIsRealtimeOnlyForTheLiteral();
		MatchingLoadPauseRuleTest.pauseOnlyAnEnabledRunningTable();
		MatchingLoadPauseRuleTest.catchUpModeFollowsTheCountAndTheBound();
		MatchingLoadPauseRuleTest.chunksCutInOrder();
		MatchingLoadPauseRuleTest.selectionByModificationTime();
		MatchingLoadPauseRuleTest.selectionOfNeverMatchedRecords();
		MatchingLoadPauseRuleTest.selectionSkipsMergedAndKeepsUnknownTimes();
		MatchingLoadPauseRuleTest.selectionWithoutMetadataUsesTimeOnly();
		MatchingLoadPauseRuleTest.selectionBeyondTheBoundKeepsCountingButStopsStoring();
		System.out.println("MatchingLoadPauseRuleTest OK");
	}

	private static void triggerIsRealtimeOnlyForTheLiteral()
	{
		MatchingLoadPauseRuleTest.check("REALTIME", MatchingLoadPauseRule.isRealtime("REALTIME"));
		MatchingLoadPauseRuleTest.check("lower case and spaces", MatchingLoadPauseRule.isRealtime(" realtime "));
		MatchingLoadPauseRuleTest.check("MANUAL", !MatchingLoadPauseRule.isRealtime("MANUAL"));
		MatchingLoadPauseRuleTest.check("null (a model without the field)", !MatchingLoadPauseRule.isRealtime(null));
		MatchingLoadPauseRuleTest.check("empty", !MatchingLoadPauseRule.isRealtime(""));
		MatchingLoadPauseRuleTest.check("another value", !MatchingLoadPauseRule.isRealtime("ON_UPDATE"));
	}

	private static void pauseOnlyAnEnabledRunningTable()
	{
		MatchingLoadPauseRuleTest.check("ENABLED, not paused: pause", MatchingLoadPauseRule.decide("ENABLED", Boolean.FALSE) == MatchingLoadPauseRule.Action.PAUSE);
		MatchingLoadPauseRuleTest.check("ENABLED, paused flag absent: pause", MatchingLoadPauseRule.decide("ENABLED", null) == MatchingLoadPauseRule.Action.PAUSE);
		MatchingLoadPauseRuleTest.check("PAUSED state: left alone", MatchingLoadPauseRule.decide("PAUSED", Boolean.TRUE) == MatchingLoadPauseRule.Action.ALREADY_PAUSED);
		MatchingLoadPauseRuleTest.check("paused flag alone: left alone", MatchingLoadPauseRule.decide("ENABLED", Boolean.TRUE) == MatchingLoadPauseRule.Action.ALREADY_PAUSED);
		MatchingLoadPauseRuleTest.check("PAUSED state alone: left alone", MatchingLoadPauseRule.decide("PAUSED", null) == MatchingLoadPauseRule.Action.ALREADY_PAUSED);
		MatchingLoadPauseRuleTest.check("DISABLED: nothing to pause", MatchingLoadPauseRule.decide("DISABLED", Boolean.FALSE) == MatchingLoadPauseRule.Action.NOT_ENABLED);
		MatchingLoadPauseRuleTest.check("unreadable status: nothing to pause", MatchingLoadPauseRule.decide(null, null) == MatchingLoadPauseRule.Action.NOT_ENABLED);
	}

	private static void catchUpModeFollowsTheCountAndTheBound()
	{
		MatchingLoadPauseRuleTest.check("nothing", MatchingLoadPauseRule.catchUpMode(0, false) == MatchingLoadPauseRule.CatchUpMode.NONE);
		MatchingLoadPauseRuleTest.check("some", MatchingLoadPauseRule.catchUpMode(12, false) == MatchingLoadPauseRule.CatchUpMode.ENUMERATED);
		MatchingLoadPauseRuleTest.check("overflow", MatchingLoadPauseRule.catchUpMode(634295, true) == MatchingLoadPauseRule.CatchUpMode.WHOLE_TABLE);
	}

	private static void chunksCutInOrder()
	{
		final List<String> keys = new ArrayList<String>();
		for (int i = 1; i <= 450; i++)
		{
			keys.add(String.valueOf(i));
		}
		final List<List<String>> chunks = MatchingLoadPauseRule.chunks(keys, MatchingLoadPauseRule.CATCHUP_CHUNK_SIZE);
		MatchingLoadPauseRuleTest.check("three chunks: " + chunks.size(), chunks.size() == 3);
		MatchingLoadPauseRuleTest.check("200/200/50", chunks.get(0).size() == 200 && chunks.get(1).size() == 200 && chunks.get(2).size() == 50);
		MatchingLoadPauseRuleTest.check("order kept", "1".equals(chunks.get(0).get(0)) && "201".equals(chunks.get(1).get(0)) && "450".equals(chunks.get(2).get(49)));
		MatchingLoadPauseRuleTest.check("empty list: no chunk", MatchingLoadPauseRule.chunks(new ArrayList<String>(), 200).isEmpty());
		try
		{
			MatchingLoadPauseRule.chunks(keys, 0);
			throw new AssertionError("a zero chunk size must be refused");
		}
		catch (final IllegalArgumentException expected)
		{
			// as wanted
		}
	}

	private static Set<String> set(final String... pKeys)
	{
		return new HashSet<String>(Arrays.asList(pKeys));
	}

	private static void selectionByModificationTime()
	{
		final MatchingCatchUpSelection selection = new MatchingCatchUpSelection(1000L, MatchingLoadPauseRuleTest.set("1", "2", "3"), null, 10);
		MatchingLoadPauseRuleTest.check("modified before the pause: not selected", !selection.accept("1", 999L));
		MatchingLoadPauseRuleTest.check("modified exactly at the pause: selected", selection.accept("2", 1000L));
		MatchingLoadPauseRuleTest.check("modified after the pause: selected", selection.accept("3", 5000L));
		MatchingLoadPauseRuleTest.check("selected 2", selection.selected() == 2 && selection.modifiedInWindow() == 2 && selection.neverMatched() == 0);
		MatchingLoadPauseRuleTest.check("keys in order", selection.keys().equals(Arrays.asList("2", "3")));
		MatchingLoadPauseRuleTest.check("enumerated", selection.mode() == MatchingLoadPauseRule.CatchUpMode.ENUMERATED && !selection.overflow());
		MatchingLoadPauseRuleTest.check("scanned 3", selection.scanned() == 3);
	}

	private static void selectionOfNeverMatchedRecords()
	{
		final MatchingCatchUpSelection selection = new MatchingCatchUpSelection(1000L, MatchingLoadPauseRuleTest.set("1"), null, 10);
		MatchingLoadPauseRuleTest.check("known and old: not selected", !selection.accept("1", 10L));
		MatchingLoadPauseRuleTest.check("unknown to the metadataset and old: selected", selection.accept("2", 10L));
		MatchingLoadPauseRuleTest.check("unknown and modified: selected once, counted in both", selection.accept("3", 2000L));
		MatchingLoadPauseRuleTest.check("counts", selection.selected() == 2 && selection.neverMatched() == 2 && selection.modifiedInWindow() == 1);
	}

	private static void selectionSkipsMergedAndKeepsUnknownTimes()
	{
		final MatchingCatchUpSelection selection = new MatchingCatchUpSelection(1000L, MatchingLoadPauseRuleTest.set("1", "2"), MatchingLoadPauseRuleTest.set("1"), 10);
		MatchingLoadPauseRuleTest.check("a merged record is never a pivot, even modified", !selection.accept("1", 5000L));
		MatchingLoadPauseRuleTest.check("unknown time: selected", selection.accept("2", -1L));
		MatchingLoadPauseRuleTest.check("merged counted", selection.skippedMerged() == 1 && selection.selected() == 1);
	}

	private static void selectionWithoutMetadataUsesTimeOnly()
	{
		final MatchingCatchUpSelection selection = new MatchingCatchUpSelection(1000L, null, null, 10);
		MatchingLoadPauseRuleTest.check("old record, metadata unknown: not selected", !selection.accept("1", 10L));
		MatchingLoadPauseRuleTest.check("new record: selected", selection.accept("2", 1500L));
		MatchingLoadPauseRuleTest.check("the never-matched criterion is reported as not applied", !selection.neverMatchedKnown() && selection.neverMatched() == 0);
	}

	private static void selectionBeyondTheBoundKeepsCountingButStopsStoring()
	{
		final MatchingCatchUpSelection selection = new MatchingCatchUpSelection(0L, MatchingLoadPauseRuleTest.set(), null, 3);
		for (int i = 1; i <= 5; i++)
		{
			selection.accept(String.valueOf(i), 100L);
		}
		MatchingLoadPauseRuleTest.check("counted 5", selection.selected() == 5);
		MatchingLoadPauseRuleTest.check("stored 3", selection.keys().size() == 3);
		MatchingLoadPauseRuleTest.check("overflow: whole table", selection.overflow() && selection.mode() == MatchingLoadPauseRule.CatchUpMode.WHOLE_TABLE);
		final MatchingCatchUpSelection atTheBound = new MatchingCatchUpSelection(0L, MatchingLoadPauseRuleTest.set(), null, 3);
		for (int i = 1; i <= 3; i++)
		{
			atTheBound.accept(String.valueOf(i), 100L);
		}
		MatchingLoadPauseRuleTest.check("exactly the bound: still enumerated", !atTheBound.overflow() && atTheBound.mode() == MatchingLoadPauseRule.CatchUpMode.ENUMERATED);
		MatchingLoadPauseRuleTest.check("empty selection: none", new MatchingCatchUpSelection(0L, null, null, 3).mode() == MatchingLoadPauseRule.CatchUpMode.NONE);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
