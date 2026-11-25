package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.distribution.MergeLogWatch.Decision;
import com.on.ebx.factory.distribution.MergeLogWatch.Member;
import com.on.ebx.factory.distribution.MergeLogWatch.MergeRow;
import com.on.ebx.factory.distribution.MergeLogWatch.OutEvent;
import com.on.ebx.factory.distribution.MergeLogWatch.Watermark;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class MergeLogWatchTest
{
	public static void main(final String[] pArgs)
	{
		MergeLogWatchTest.firstPassInitialisesWithoutEmitting();
		MergeLogWatchTest.newMergeRetiresEachMergedMember();
		MergeLogWatchTest.memberWithNoGoldenKeyRetiresWithNoSurvivor();
		MergeLogWatchTest.undoneMergeRetiresGoldenAndReinstatesMembers();
		MergeLogWatchTest.undoneMergePendingApprovalMemberGetsNothing();
		MergeLogWatchTest.retireModeNoneEmitsNothingButAdvances();
		MergeLogWatchTest.watermarkAndUndoneSetAdvance();
		MergeLogWatchTest.sameInputTwiceIsIdempotent();
		MergeLogWatchTest.newAndAlreadyUndoneMergeNetsToNothing();
		MergeLogWatchTest.failedOutboxWriteKeepsTheWatermarkOfTablesWithEvents();
		MergeLogWatchTest.writtenOutboxRowsLetTheWatermarkAdvance();
		MergeLogWatchTest.watermarkChangeDetection();
		System.out.println("MergeLogWatchTest OK");
	}

	private static void firstPassInitialisesWithoutEmitting()
	{
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, false), new MergeRow(2L, true));
		final Decision decision = MergeLogWatch.decide(Watermark.none(), rows, Collections.<Long, List<Member>>emptyMap(), "NOTICE", true);
		MergeLogWatchTest.check("first pass emits nothing", decision.events.isEmpty());
		MergeLogWatchTest.check("first pass sets lastMergeId to the current max", decision.newWatermark.lastMergeId == 2L);
		MergeLogWatchTest.check("first pass records id 2 as already undone", decision.newWatermark.undoneMergeIds.contains(Long.valueOf(2L)));
		MergeLogWatchTest.check("first pass does not record id 1 as undone", !decision.newWatermark.undoneMergeIds.contains(Long.valueOf(1L)));
	}

	private static void newMergeRetiresEachMergedMember()
	{
		final Watermark watermark = new Watermark(0L, Collections.<Long>emptySet());
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, false));
		final Map<Long, List<Member>> members = new HashMap<Long, List<Member>>();
		members.put(Long.valueOf(1L), MergeLogWatchTest.members(
			new Member("347", RetireRules.STATE_MERGED, "692", false),
			new Member("527", RetireRules.STATE_MERGED, "692", false)));
		final Decision decision = MergeLogWatch.decide(watermark, rows, members, "NOTICE", true);
		MergeLogWatchTest.check("two RETIRE events", decision.events.size() == 2);
		for (final OutEvent event : decision.events)
		{
			MergeLogWatchTest.check("change is RETIRE", MergeLogWatch.CHANGE_RETIRE.equals(event.change));
			MergeLogWatchTest.check("survivor is the golden", "692".equals(event.survivorKey));
			MergeLogWatchTest.check("reason is MERGE", MergeLogWatch.REASON_MERGE.equals(event.reason));
			MergeLogWatchTest.check("mergeId is 1", event.mergeId == 1L);
		}
		MergeLogWatchTest.check("watermark advances to 1", decision.newWatermark.lastMergeId == 1L);
	}

	private static void memberWithNoGoldenKeyRetiresWithNoSurvivor()
	{
		final Watermark watermark = new Watermark(0L, Collections.<Long>emptySet());
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, false));
		final Map<Long, List<Member>> members = new HashMap<Long, List<Member>>();
		members.put(Long.valueOf(1L), MergeLogWatchTest.members(new Member("347", RetireRules.STATE_MERGED, null, false)));
		final Decision decision = MergeLogWatch.decide(watermark, rows, members, "NOTICE", true);
		MergeLogWatchTest.check("one RETIRE event", decision.events.size() == 1);
		MergeLogWatchTest.check("no survivor known", decision.events.get(0).survivorKey == null);
	}

	private static void undoneMergeRetiresGoldenAndReinstatesMembers()
	{
		// watermark already knows merge 1 (from an earlier pass, not undone yet)
		final Watermark watermark = new Watermark(1L, Collections.<Long>emptySet());
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, true));
		final Map<Long, List<Member>> members = new HashMap<Long, List<Member>>();
		members.put(Long.valueOf(1L), MergeLogWatchTest.members(
			new Member("692", RetireRules.STATE_DELETED, null, true),
			new Member("347", RetireRules.STATE_GOLDEN, null, false),
			new Member("527", RetireRules.STATE_GOLDEN, null, false)));
		final Decision decision = MergeLogWatch.decide(watermark, rows, members, "NOTICE", true);
		MergeLogWatchTest.check("three events: one RETIRE + two CREATE", decision.events.size() == 3);
		int retireCount = 0;
		int createCount = 0;
		for (final OutEvent event : decision.events)
		{
			if (MergeLogWatch.CHANGE_RETIRE.equals(event.change))
			{
				retireCount++;
				MergeLogWatchTest.check("golden retires with no survivor", event.survivorKey == null);
				MergeLogWatchTest.check("golden retire record is 692", "692".equals(event.recordKey));
				MergeLogWatchTest.check("reason is UNMERGE", MergeLogWatch.REASON_UNMERGE.equals(event.reason));
			}
			else if (MergeLogWatch.CHANGE_CREATE.equals(event.change))
			{
				createCount++;
				MergeLogWatchTest.check("reinstated member has no survivor field", event.survivorKey == null);
			}
		}
		MergeLogWatchTest.check("exactly one RETIRE", retireCount == 1);
		MergeLogWatchTest.check("exactly two CREATE", createCount == 2);
		MergeLogWatchTest.check("undone id recorded", decision.newWatermark.undoneMergeIds.contains(Long.valueOf(1L)));
	}

	private static void undoneMergePendingApprovalMemberGetsNothing()
	{
		final Watermark watermark = new Watermark(1L, Collections.<Long>emptySet());
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, true));
		final Map<Long, List<Member>> members = new HashMap<Long, List<Member>>();
		members.put(Long.valueOf(1L), MergeLogWatchTest.members(
			new Member("692", RetireRules.STATE_DELETED, null, true),
			new Member("347", RetireRules.STATE_PENDING_APPROVAL, null, false)));
		final Decision decision = MergeLogWatch.decide(watermark, rows, members, "NOTICE", true);
		MergeLogWatchTest.check("only the golden's RETIRE, nothing for the pending member", decision.events.size() == 1);
		MergeLogWatchTest.check("it is the RETIRE", MergeLogWatch.CHANGE_RETIRE.equals(decision.events.get(0).change));
	}

	private static void retireModeNoneEmitsNothingButAdvances()
	{
		final Watermark watermark = new Watermark(0L, Collections.<Long>emptySet());
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, false));
		final Map<Long, List<Member>> members = new HashMap<Long, List<Member>>();
		members.put(Long.valueOf(1L), MergeLogWatchTest.members(new Member("347", RetireRules.STATE_MERGED, "692", false)));
		final Decision decision = MergeLogWatch.decide(watermark, rows, members, "NONE", true);
		MergeLogWatchTest.check("NONE emits nothing", decision.events.isEmpty());
		MergeLogWatchTest.check("but still advances the watermark", decision.newWatermark.lastMergeId == 1L);
	}

	private static void watermarkAndUndoneSetAdvance()
	{
		final Watermark watermark = new Watermark(3L, MergeLogWatchTest.set(1L));
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, true), new MergeRow(2L, false), new MergeRow(4L, false), new MergeRow(5L, true));
		final Map<Long, List<Member>> members = new HashMap<Long, List<Member>>();
		members.put(Long.valueOf(4L), MergeLogWatchTest.members(new Member("a", RetireRules.STATE_MERGED, "g", false)));
		members.put(Long.valueOf(5L), MergeLogWatchTest.members(new Member("g2", RetireRules.STATE_DELETED, null, true)));
		final Decision decision = MergeLogWatch.decide(watermark, rows, members, "NOTICE", true);
		// id 1 is < watermark.last (3) and already known undone -> ignored; id 2 is < watermark.last, not
		// undone -> ignored (already accounted for); id 4 is new -> one RETIRE; id 5 is new AND undone -> nets
		// to nothing (see class Javadoc) but is still recorded as undone.
		MergeLogWatchTest.check("only id 4's RETIRE fires", decision.events.size() == 1);
		MergeLogWatchTest.check("mergeId 4", decision.events.get(0).mergeId == 4L);
		MergeLogWatchTest.check("watermark advances to the max id seen (5)", decision.newWatermark.lastMergeId == 5L);
		MergeLogWatchTest.check("id 1 still known undone", decision.newWatermark.undoneMergeIds.contains(Long.valueOf(1L)));
		MergeLogWatchTest.check("id 5 now known undone", decision.newWatermark.undoneMergeIds.contains(Long.valueOf(5L)));
	}

	private static void sameInputTwiceIsIdempotent()
	{
		final Watermark watermark = new Watermark(0L, Collections.<Long>emptySet());
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, false));
		final Map<Long, List<Member>> members = new HashMap<Long, List<Member>>();
		members.put(Long.valueOf(1L), MergeLogWatchTest.members(new Member("347", RetireRules.STATE_MERGED, "692", false)));
		final Decision first = MergeLogWatch.decide(watermark, rows, members, "NOTICE", true);
		MergeLogWatchTest.check("first call emits one event", first.events.size() == 1);
		final Decision second = MergeLogWatch.decide(first.newWatermark, rows, members, "NOTICE", true);
		MergeLogWatchTest.check("second call with the same rows emits nothing", second.events.isEmpty());
		MergeLogWatchTest.check("watermark unchanged", second.newWatermark.lastMergeId == first.newWatermark.lastMergeId);
	}

	private static void newAndAlreadyUndoneMergeNetsToNothing()
	{
		final Watermark watermark = new Watermark(0L, Collections.<Long>emptySet());
		final List<MergeRow> rows = MergeLogWatchTest.rows(new MergeRow(1L, true));
		final Map<Long, List<Member>> members = new HashMap<Long, List<Member>>();
		members.put(Long.valueOf(1L), MergeLogWatchTest.members(new Member("g", RetireRules.STATE_DELETED, null, true)));
		final Decision decision = MergeLogWatch.decide(watermark, rows, members, "NOTICE", true);
		MergeLogWatchTest.check("a merge never announced and already undone emits nothing", decision.events.isEmpty());
		MergeLogWatchTest.check("watermark still advances", decision.newWatermark.lastMergeId == 1L);
		MergeLogWatchTest.check("id recorded as undone so a later pass never re-derives it", decision.newWatermark.undoneMergeIds.contains(Long.valueOf(1L)));
	}

	// ---------------------------------------------------------------------------------------------- helpers

	/** Two tables; A has events and B has none. The outbox write failed: A stays at its stored watermark, B still advances, a table never stored stays absent. */
	private static void failedOutboxWriteKeepsTheWatermarkOfTablesWithEvents()
	{
		final Map<String, Watermark> stored = new HashMap<String, Watermark>();
		stored.put("/root/A", new Watermark(5L, Collections.<Long>emptySet()));
		stored.put("/root/B", new Watermark(7L, Collections.<Long>emptySet()));
		final Map<String, Watermark> decided = new HashMap<String, Watermark>();
		decided.put("/root/A", new Watermark(9L, Collections.singleton(Long.valueOf(8L))));
		decided.put("/root/B", new Watermark(8L, Collections.<Long>emptySet()));
		decided.put("/root/C", new Watermark(3L, Collections.<Long>emptySet()));
		final Set<String> withEvents = new HashSet<String>();
		withEvents.add("/root/A");
		final Map<String, Watermark> toPersist = MergeLogWatch.watermarksToPersist(stored, decided, withEvents, false);
		MergeLogWatchTest.check("the table with events stays where it was: " + toPersist.get("/root/A").lastMergeId, toPersist.get("/root/A").lastMergeId == 5L
			&& toPersist.get("/root/A").undoneMergeIds.isEmpty());
		MergeLogWatchTest.check("a table without events still advances", toPersist.get("/root/B").lastMergeId == 8L);
		MergeLogWatchTest.check("a table learned for the first time is kept", toPersist.get("/root/C").lastMergeId == 3L);
		MergeLogWatchTest.check("the input maps are not modified", decided.get("/root/A").lastMergeId == 9L && stored.get("/root/A").lastMergeId == 5L);

		final Map<String, Watermark> firstTime = MergeLogWatch.watermarksToPersist(new HashMap<String, Watermark>(), decided, withEvents, false);
		MergeLogWatchTest.check("a table with events but no stored watermark is not invented", !firstTime.containsKey("/root/A") && firstTime.containsKey("/root/B"));
	}

	private static void writtenOutboxRowsLetTheWatermarkAdvance()
	{
		final Map<String, Watermark> stored = new HashMap<String, Watermark>();
		stored.put("/root/A", new Watermark(5L, Collections.<Long>emptySet()));
		final Map<String, Watermark> decided = new HashMap<String, Watermark>();
		decided.put("/root/A", new Watermark(9L, Collections.<Long>emptySet()));
		final Set<String> withEvents = new HashSet<String>();
		withEvents.add("/root/A");
		MergeLogWatchTest.check("rows written: the decided watermark is persisted", MergeLogWatch.watermarksToPersist(stored, decided, withEvents, true).get("/root/A").lastMergeId == 9L);
	}

	private static void watermarkChangeDetection()
	{
		final Map<String, Watermark> stored = new HashMap<String, Watermark>();
		stored.put("/root/A", new Watermark(5L, Collections.<Long>emptySet()));
		MergeLogWatchTest.check("same watermark: nothing to write", !MergeLogWatch.watermarksChanged(stored, new HashMap<String, Watermark>(stored)));
		final Map<String, Watermark> moved = new HashMap<String, Watermark>();
		moved.put("/root/A", new Watermark(6L, Collections.<Long>emptySet()));
		MergeLogWatchTest.check("a higher merge id is a change", MergeLogWatch.watermarksChanged(stored, moved));
		final Map<String, Watermark> undone = new HashMap<String, Watermark>();
		undone.put("/root/A", new Watermark(5L, Collections.singleton(Long.valueOf(4L))));
		MergeLogWatchTest.check("a newly undone merge is a change", MergeLogWatch.watermarksChanged(stored, undone));
		final Map<String, Watermark> added = new HashMap<String, Watermark>(stored);
		added.put("/root/B", new Watermark(1L, Collections.<Long>emptySet()));
		MergeLogWatchTest.check("a table never stored is a change", MergeLogWatch.watermarksChanged(stored, added));
	}

	private static List<MergeRow> rows(final MergeRow... pRows)
	{
		final List<MergeRow> out = new ArrayList<MergeRow>();
		for (final MergeRow row : pRows)
		{
			out.add(row);
		}
		return out;
	}

	private static List<Member> members(final Member... pMembers)
	{
		final List<Member> out = new ArrayList<Member>();
		for (final Member member : pMembers)
		{
			out.add(member);
		}
		return out;
	}

	private static Set<Long> set(final long... pIds)
	{
		final Set<Long> out = new HashSet<Long>();
		for (final long id : pIds)
		{
			out.add(Long.valueOf(id));
		}
		return out;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
