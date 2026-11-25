package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.distribution.MatchingStateSweep.DeletedGolden;
import com.on.ebx.factory.distribution.MatchingStateSweep.Input;
import com.on.ebx.factory.distribution.MatchingStateSweep.LedgerEntry;
import com.on.ebx.factory.distribution.MatchingStateSweep.MergedRow;
import com.on.ebx.factory.distribution.MatchingStateSweep.OutEvent;
import com.on.ebx.factory.distribution.MatchingStateSweep.Result;
import com.on.ebx.factory.distribution.MatchingStateSweep.StateRow;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. Fixtures are metadataset rows (RecordMetadata states) and ledger entries. */
public final class MatchingStateSweepTest
{
	public static void main(final String[] pArgs)
	{
		MatchingStateSweepTest.mergeOfTwoRetiresBoth();
		MatchingStateSweepTest.autoMergeOfThreeIntoOne();
		MatchingStateSweepTest.noticeAlreadyAnnouncedByTheWatchIsNotRepeated();
		MatchingStateSweepTest.unmergeRetiresTheGoldenAndReinstatesTheMembers();
		MatchingStateSweepTest.unmergedMemberStillPendingGetsNothingYet();
		MatchingStateSweepTest.secondSweepWritesNothing();
		MatchingStateSweepTest.memberMergedBeforeTheBaselineIsNeverRetired();
		MatchingStateSweepTest.mergeAboveTheWatchMarkIsLeftToTheWatch();
		MatchingStateSweepTest.legacyTargetWithoutBaselineAnnouncesNothingOnAMergeId();
		MatchingStateSweepTest.skippedNoticeIsQueuedAgain();
		MatchingStateSweepTest.reMergeAfterReinstatementIsRetiredAgain();
		MatchingStateSweepTest.retireModeNoneEmitsNothing();
		MatchingStateSweepTest.undoneMergeSwitchOffKeepsMembersRetired();
		MatchingStateSweepTest.sweptMarkAdvancesButNeverBackwards();
		MatchingStateSweepTest.reinstateCandidatesAreRetiredKeysThatAreNeitherMergedNorDeletedGoldens();
		MatchingStateSweepTest.goldenOfTheBaselineMergeDeletedAfterTheFirstFullSendIsRetired();
		MatchingStateSweepTest.sameGoldenWithoutAFirstFullSendIsNotRetired();
		MatchingStateSweepTest.goldenAlreadyRetiredInTheLedgerIsNotRetiredAgain();
		MatchingStateSweepTest.memberUnderTheBaselineStillGetsNoRetireAfterAFirstFullSend();
		MatchingStateSweepTest.goldenAboveTheFloorIsRetiredAsBefore();
		MatchingStateSweepTest.goldenWithoutMergeIdFollowsTheFirstFullSend();
		System.out.println("MatchingStateSweepTest OK");
	}

	private static Input input(final long pBaseline, final Long pSwept, final long pWatchLast)
	{
		final Input in = new Input();
		in.baseline = Long.valueOf(pBaseline);
		in.swept = pSwept;
		in.watchLast = pWatchLast;
		return in;
	}

	private static LedgerEntry retired(final long pAt, final Long pMergeId)
	{
		final LedgerEntry entry = new LedgerEntry();
		entry.retireAt = pAt;
		entry.retireMergeId = pMergeId;
		return entry;
	}

	private static OutEvent find(final Result pResult, final String pChange, final String pKey)
	{
		for (final OutEvent event : pResult.events)
		{
			if (event.change.equals(pChange) && event.recordKey.equals(pKey))
			{
				return event;
			}
		}
		return null;
	}

	/** The incident of 2026-09-27: 347 and 527 merged into the new golden 692 by merge 5, nothing queued for them. */
	private static void mergeOfTwoRetiresBoth()
	{
		final Input in = MatchingStateSweepTest.input(3L, null, 5L);
		in.merged.add(new MergedRow("347", "692", Long.valueOf(5L)));
		in.merged.add(new MergedRow("527", "692", Long.valueOf(5L)));
		final Result result = MatchingStateSweep.decide(in);
		MatchingStateSweepTest.check("two events", result.events.size() == 2);
		final OutEvent a = MatchingStateSweepTest.find(result, "RETIRE", "347");
		MatchingStateSweepTest.check("347 retired", a != null);
		MatchingStateSweepTest.check("into the golden", "692".equals(a.survivorKey));
		MatchingStateSweepTest.check("reason MERGE", "MERGE".equals(a.reason));
		MatchingStateSweepTest.check("merge id 5", a.mergeId != null && a.mergeId.longValue() == 5L);
		MatchingStateSweepTest.check("527 retired", MatchingStateSweepTest.find(result, "RETIRE", "527") != null);
		MatchingStateSweepTest.check("count", result.retired == 2 && result.goldensRetired == 0 && result.reinstated == 0);
	}

	/** An automatic run merged 3 records into one golden (lot M series): one RETIRE per member, none for the golden itself. */
	private static void autoMergeOfThreeIntoOne()
	{
		final Input in = MatchingStateSweepTest.input(3L, Long.valueOf(3L), 9L);
		in.merged.add(new MergedRow("a", "G1", Long.valueOf(9L)));
		in.merged.add(new MergedRow("b", "G1", Long.valueOf(9L)));
		in.merged.add(new MergedRow("c", "G1", Long.valueOf(9L)));
		final Result result = MatchingStateSweep.decide(in);
		MatchingStateSweepTest.check("three retires", result.events.size() == 3 && result.retired == 3);
		MatchingStateSweepTest.check("none for the golden", MatchingStateSweepTest.find(result, "RETIRE", "G1") == null);
	}

	private static void noticeAlreadyAnnouncedByTheWatchIsNotRepeated()
	{
		final Input in = MatchingStateSweepTest.input(3L, null, 5L);
		in.merged.add(new MergedRow("347", "692", Long.valueOf(5L)));
		in.ledger = new HashMap<String, LedgerEntry>();
		in.ledger.put("347", MatchingStateSweepTest.retired(1000L, Long.valueOf(5L)));
		MatchingStateSweepTest.check("nothing", MatchingStateSweep.decide(in).events.isEmpty());
	}

	/** 692 was auto-created by merge 5 and deleted by the unmerge; 347 and 527 are GOLDEN again (their merge id was reset). */
	private static void unmergeRetiresTheGoldenAndReinstatesTheMembers()
	{
		// merge 5 is above what the previous sweep compared (4) and under the watch mark (6)
		final Input in = MatchingStateSweepTest.input(3L, Long.valueOf(4L), 6L);
		in.deletedGoldens.add(new DeletedGolden("692", Long.valueOf(5L)));
		in.ledger = new HashMap<String, LedgerEntry>();
		in.ledger.put("347", MatchingStateSweepTest.retired(1000L, Long.valueOf(5L)));
		in.ledger.put("527", MatchingStateSweepTest.retired(1000L, Long.valueOf(5L)));
		in.candidateStates = new HashMap<String, StateRow>();
		in.candidateStates.put("347", new StateRow(RetireRules.STATE_GOLDEN));
		in.candidateStates.put("527", new StateRow(RetireRules.STATE_GOLDEN));
		final Result result = MatchingStateSweep.decide(in);
		final OutEvent golden = MatchingStateSweepTest.find(result, "RETIRE", "692");
		MatchingStateSweepTest.check("the golden is retired", golden != null);
		MatchingStateSweepTest.check("with no survivor", golden.survivorKey == null);
		MatchingStateSweepTest.check("reason UNMERGE", "UNMERGE".equals(golden.reason));
		final OutEvent member = MatchingStateSweepTest.find(result, "CREATE", "347");
		MatchingStateSweepTest.check("347 live again", member != null && "UNMERGE".equals(member.reason));
		MatchingStateSweepTest.check("with the merge id it was retired by", member.mergeId != null && member.mergeId.longValue() == 5L);
		MatchingStateSweepTest.check("527 live again", MatchingStateSweepTest.find(result, "CREATE", "527") != null);
		MatchingStateSweepTest.check("counts", result.goldensRetired == 1 && result.reinstated == 2 && result.retired == 0);
	}

	private static void unmergedMemberStillPendingGetsNothingYet()
	{
		final Input in = MatchingStateSweepTest.input(3L, Long.valueOf(5L), 6L);
		in.ledger = new HashMap<String, LedgerEntry>();
		in.ledger.put("347", MatchingStateSweepTest.retired(1000L, Long.valueOf(5L)));
		in.candidateStates = new HashMap<String, StateRow>();
		in.candidateStates.put("347", new StateRow(RetireRules.STATE_PENDING_APPROVAL));
		MatchingStateSweepTest.check("pending: nothing", MatchingStateSweep.decide(in).events.isEmpty());
		in.candidateStates.put("347", new StateRow(RetireRules.STATE_GOLDEN));
		MatchingStateSweepTest.check("golden at a later sweep: reinstated", MatchingStateSweepTest.find(MatchingStateSweep.decide(in), "CREATE", "347") != null);
	}

	/** Feeding the events back into the ledger (what the outbox / history do) makes the next sweep empty. */
	private static void secondSweepWritesNothing()
	{
		final Input in = MatchingStateSweepTest.input(3L, null, 6L);
		in.merged.add(new MergedRow("a", "G1", Long.valueOf(6L)));
		in.merged.add(new MergedRow("b", "G1", Long.valueOf(6L)));
		in.deletedGoldens.add(new DeletedGolden("G0", Long.valueOf(4L)));
		in.ledger = new HashMap<String, LedgerEntry>();
		in.ledger.put("x", MatchingStateSweepTest.retired(1000L, Long.valueOf(4L)));
		in.candidateStates = new HashMap<String, StateRow>();
		in.candidateStates.put("x", new StateRow(RetireRules.STATE_GOLDEN));
		final Result first = MatchingStateSweep.decide(in);
		MatchingStateSweepTest.check("first sweep writes retire a, retire b, retire G0, create x", first.events.size() == 4);
		long clock = 2000L;
		for (final OutEvent event : first.events)
		{
			LedgerEntry entry = in.ledger.get(event.recordKey);
			if (entry == null)
			{
				entry = new LedgerEntry();
				in.ledger.put(event.recordKey, entry);
			}
			if ("RETIRE".equals(event.change))
			{
				entry.retireAt = clock++;
			}
			else
			{
				entry.reinstateAt = clock++;
			}
		}
		in.swept = Long.valueOf(first.newSwept);
		MatchingStateSweepTest.check("second sweep writes nothing", MatchingStateSweep.decide(in).events.isEmpty());
		// and not either when the marks were lost (swept null): the ledger alone settles it
		in.swept = null;
		MatchingStateSweepTest.check("ledger alone: still nothing", MatchingStateSweep.decide(in).events.isEmpty());
	}

	private static void memberMergedBeforeTheBaselineIsNeverRetired()
	{
		final Input in = MatchingStateSweepTest.input(10L, null, 12L);
		in.merged.add(new MergedRow("old", "G9", Long.valueOf(7L)));
		in.deletedGoldens.add(new DeletedGolden("G8", Long.valueOf(10L)));
		in.merged.add(new MergedRow("nomerge", "G9", null));
		MatchingStateSweepTest.check("merged under the baseline / without a merge id: nothing", MatchingStateSweep.decide(in).events.isEmpty());
		in.merged.add(new MergedRow("new", "G9", Long.valueOf(11L)));
		final Result result = MatchingStateSweep.decide(in);
		MatchingStateSweepTest.check("one merged after the baseline: retired", result.events.size() == 1 && MatchingStateSweepTest.find(result, "RETIRE", "new") != null);
	}

	private static void mergeAboveTheWatchMarkIsLeftToTheWatch()
	{
		final Input in = MatchingStateSweepTest.input(3L, Long.valueOf(5L), 5L);
		in.merged.add(new MergedRow("fresh", "G1", Long.valueOf(6L)));
		MatchingStateSweepTest.check("merge 6 > watch mark 5: the watch announces it", MatchingStateSweep.decide(in).events.isEmpty());
	}

	private static void legacyTargetWithoutBaselineAnnouncesNothingOnAMergeId()
	{
		final Input in = new Input();
		in.watchLast = 8L;
		in.merged.add(new MergedRow("a", "G1", Long.valueOf(7L)));
		in.deletedGoldens.add(new DeletedGolden("G0", Long.valueOf(6L)));
		MatchingStateSweepTest.check("no baseline, never swept: nothing retroactive", MatchingStateSweep.decide(in).events.isEmpty());
		MatchingStateSweepTest.check("and the mark moves to the watch mark", MatchingStateSweep.decide(in).newSwept == 8L);
	}

	private static void skippedNoticeIsQueuedAgain()
	{
		final Input in = MatchingStateSweepTest.input(3L, Long.valueOf(8L), 8L);
		in.merged.add(new MergedRow("347", "692", Long.valueOf(5L)));
		in.ledger = new HashMap<String, LedgerEntry>();
		final LedgerEntry skipped = new LedgerEntry();
		skipped.retireNotDelivered = true;
		in.ledger.put("347", skipped);
		MatchingStateSweepTest.check("a skipped notice is queued again, though under the swept mark", MatchingStateSweepTest.find(MatchingStateSweep.decide(in), "RETIRE", "347") != null);
	}

	private static void reMergeAfterReinstatementIsRetiredAgain()
	{
		final Input in = MatchingStateSweepTest.input(3L, Long.valueOf(8L), 8L);
		in.merged.add(new MergedRow("347", "700", Long.valueOf(7L)));
		in.ledger = new HashMap<String, LedgerEntry>();
		final LedgerEntry entry = MatchingStateSweepTest.retired(1000L, Long.valueOf(5L));
		entry.reinstateAt = 2000L;
		in.ledger.put("347", entry);
		final OutEvent event = MatchingStateSweepTest.find(MatchingStateSweep.decide(in), "RETIRE", "347");
		MatchingStateSweepTest.check("retired again, into the new golden", event != null && "700".equals(event.survivorKey));
	}

	private static void retireModeNoneEmitsNothing()
	{
		final Input in = MatchingStateSweepTest.input(3L, null, 5L);
		in.retireMode = "NONE";
		in.merged.add(new MergedRow("347", "692", Long.valueOf(5L)));
		in.deletedGoldens.add(new DeletedGolden("692", Long.valueOf(5L)));
		final Result result = MatchingStateSweep.decide(in);
		MatchingStateSweepTest.check("nothing emitted", result.events.isEmpty());
		MatchingStateSweepTest.check("the mark still advances", result.newSwept == 5L);
	}

	private static void undoneMergeSwitchOffKeepsMembersRetired()
	{
		final Input in = MatchingStateSweepTest.input(3L, Long.valueOf(5L), 6L);
		in.retireUndoneMerges = false;
		in.ledger = new HashMap<String, LedgerEntry>();
		in.ledger.put("347", MatchingStateSweepTest.retired(1000L, Long.valueOf(5L)));
		in.candidateStates = new HashMap<String, StateRow>();
		in.candidateStates.put("347", new StateRow(RetireRules.STATE_GOLDEN));
		MatchingStateSweepTest.check("no CREATE when the target does not want reinstatements", MatchingStateSweep.decide(in).events.isEmpty());
	}

	private static void sweptMarkAdvancesButNeverBackwards()
	{
		MatchingStateSweepTest.check("advances", MatchingStateSweep.decide(MatchingStateSweepTest.input(1L, Long.valueOf(2L), 9L)).newSwept == 9L);
		MatchingStateSweepTest.check("never backwards", MatchingStateSweep.decide(MatchingStateSweepTest.input(1L, Long.valueOf(12L), 9L)).newSwept == 12L);
	}

	private static void reinstateCandidatesAreRetiredKeysThatAreNeitherMergedNorDeletedGoldens()
	{
		final Map<String, LedgerEntry> ledger = new HashMap<String, LedgerEntry>();
		ledger.put("member", MatchingStateSweepTest.retired(10L, null));
		ledger.put("stillMerged", MatchingStateSweepTest.retired(10L, null));
		ledger.put("golden", MatchingStateSweepTest.retired(10L, null));
		final LedgerEntry live = MatchingStateSweepTest.retired(10L, null);
		live.reinstateAt = 20L;
		ledger.put("alreadyLive", live);
		final Set<String> merged = new HashSet<String>(Arrays.asList("stillMerged"));
		final Set<String> deleted = new HashSet<String>(Arrays.asList("golden"));
		final Set<String> candidates = MatchingStateSweep.reinstateCandidates(ledger, merged, deleted);
		final List<String> sorted = new ArrayList<String>(candidates);
		MatchingStateSweepTest.check("only the former member", sorted.size() == 1 && "member".equals(sorted.get(0)));
	}

	/** Measured 2026-10-18 (scenario run 4): merge 1 made golden 539 (members 8, 245); the first full send ran; the unmerge deleted 539, members PENDING. */
	private static void goldenOfTheBaselineMergeDeletedAfterTheFirstFullSendIsRetired()
	{
		final Input in = MatchingStateSweepTest.input(1L, Long.valueOf(1L), 1L);
		in.firstFullSendDone = true;
		in.deletedGoldens.add(new DeletedGolden("539", Long.valueOf(1L)));
		// members 8 and 245 are PENDING_APPROVAL: no MERGED row for them, nothing in the ledger
		final Result result = MatchingStateSweep.decide(in);
		MatchingStateSweepTest.check("exactly one event", result.events.size() == 1);
		final OutEvent event = MatchingStateSweepTest.find(result, "RETIRE", "539");
		MatchingStateSweepTest.check("539 retired", event != null);
		MatchingStateSweepTest.check("no survivor", event.survivorKey == null);
		MatchingStateSweepTest.check("reason UNMERGE", "UNMERGE".equals(event.reason));
		MatchingStateSweepTest.check("merge id 1", event.mergeId != null && event.mergeId.longValue() == 1L);
		MatchingStateSweepTest.check("counts", result.goldensRetired == 1 && result.retired == 0 && result.reinstated == 0);
	}

	private static void sameGoldenWithoutAFirstFullSendIsNotRetired()
	{
		final Input in = MatchingStateSweepTest.input(1L, Long.valueOf(1L), 1L);
		in.firstFullSendDone = false;
		in.deletedGoldens.add(new DeletedGolden("539", Long.valueOf(1L)));
		MatchingStateSweepTest.check("armed without a first full send: nothing", MatchingStateSweep.decide(in).events.isEmpty());
	}

	private static void goldenAlreadyRetiredInTheLedgerIsNotRetiredAgain()
	{
		final Input in = MatchingStateSweepTest.input(1L, Long.valueOf(1L), 1L);
		in.firstFullSendDone = true;
		in.deletedGoldens.add(new DeletedGolden("539", Long.valueOf(1L)));
		in.ledger = new HashMap<String, LedgerEntry>();
		in.ledger.put("539", MatchingStateSweepTest.retired(1000L, Long.valueOf(1L)));
		MatchingStateSweepTest.check("idempotent", MatchingStateSweep.decide(in).events.isEmpty());
	}

	private static void memberUnderTheBaselineStillGetsNoRetireAfterAFirstFullSend()
	{
		final Input in = MatchingStateSweepTest.input(1L, Long.valueOf(1L), 1L);
		in.firstFullSendDone = true;
		in.merged.add(new MergedRow("8", "539", Long.valueOf(1L)));
		in.merged.add(new MergedRow("245", "539", Long.valueOf(1L)));
		MatchingStateSweepTest.check("R1 unchanged: members merged under the baseline were never live rows", MatchingStateSweep.decide(in).events.isEmpty());
	}

	private static void goldenAboveTheFloorIsRetiredAsBefore()
	{
		final Input in = MatchingStateSweepTest.input(3L, Long.valueOf(4L), 6L);
		in.deletedGoldens.add(new DeletedGolden("692", Long.valueOf(5L)));
		MatchingStateSweepTest.check("without a first full send", MatchingStateSweepTest.find(MatchingStateSweep.decide(in), "RETIRE", "692") != null);
		in.firstFullSendDone = true;
		final Result result = MatchingStateSweep.decide(in);
		MatchingStateSweepTest.check("and with one: still a single notice", result.events.size() == 1 && result.goldensRetired == 1);
		// a golden above the watch mark is the watch's
		final Input above = MatchingStateSweepTest.input(3L, Long.valueOf(4L), 6L);
		above.firstFullSendDone = true;
		above.deletedGoldens.add(new DeletedGolden("700", Long.valueOf(7L)));
		MatchingStateSweepTest.check("above the watch mark: left to the watch", MatchingStateSweep.decide(above).events.isEmpty());
	}

	private static void goldenWithoutMergeIdFollowsTheFirstFullSend()
	{
		final Input in = MatchingStateSweepTest.input(1L, Long.valueOf(1L), 1L);
		in.deletedGoldens.add(new DeletedGolden("G", null));
		MatchingStateSweepTest.check("no first full send: nothing", MatchingStateSweep.decide(in).events.isEmpty());
		in.firstFullSendDone = true;
		final OutEvent event = MatchingStateSweepTest.find(MatchingStateSweep.decide(in), "RETIRE", "G");
		MatchingStateSweepTest.check("first full send done: retired, merge id null", event != null && event.mergeId == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("MatchingStateSweepTest: " + pWhat);
		}
	}
}
