package com.on.ebx.factory.intake;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free: {@link IntakeDecision}. Times are plain milliseconds. */
public final class IntakeDecisionTest
{
	private static final IntakeSettings DEFAULTS = IntakeSettings.defaults();
	private static final long T0 = 1_000_000L;

	/** Counts what the test says and remembers which sources it was asked about. */
	private static final class Counter implements IntakeDecision.RowCounter
	{
		private final long rows;
		final List<String> asked = new ArrayList<String>();

		Counter(final long pRows)
		{
			this.rows = pRows;
		}

		@Override
		public long count(final String pSourceSystem)
		{
			this.asked.add(pSourceSystem);
			return this.rows;
		}
	}

	public static void main(final String[] pArgs)
	{
		IntakeDecisionTest.nothingWaits();
		IntakeDecisionTest.quietPeriodThenLaunch();
		IntakeDecisionTest.maxWaitBeatsAMassLoad();
		IntakeDecisionTest.minRowsKeepsWaitingUntilMaxWait();
		IntakeDecisionTest.batchCompleteLaunchesAtOnce();
		IntakeDecisionTest.manualLaunchesEverySource();
		IntakeDecisionTest.zeroRowsClearsInsteadOfLaunching();
		IntakeDecisionTest.neverLaunchesWhenNotOnOrRunning();
		IntakeDecisionTest.countOnlyWhenDue();
		IntakeDecisionTest.severalSourcesAndReasonPriority();
		IntakeDecisionTest.sweepKeepsItsLabel();
		System.out.println("IntakeDecisionTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static IntakeDecision.SourceInput source(final String pName, final IntakePending pPending, final IntakeSettings pSettings)
	{
		return new IntakeDecision.SourceInput(pName, pPending, pSettings);
	}

	private static IntakePending waiting(final long pSince, final long pLast)
	{
		return IntakePending.empty().observe(Long.valueOf(pLast), pSince);
	}

	private static IntakeDecision.Result decide(final long pNow, final boolean pRunning, final boolean pManual, final Counter pCounter, final IntakeDecision.SourceInput... pSources)
	{
		return IntakeDecision.decide(pNow, "ON", pRunning, pManual, Arrays.asList(pSources), pCounter);
	}

	private static void nothingWaits()
	{
		final Counter counter = new Counter(5);
		final IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0, false, false, counter, IntakeDecisionTest.source("CRM", IntakePending.empty(), IntakeDecisionTest.DEFAULTS));
		check(r.action == IntakeDecision.Action.NOTHING && r.views.get(0).state == IntakeDecision.SourceState.IDLE, "idle");
		check(counter.asked.isEmpty(), "never counts when nothing waits");
		check(r.views.get(0).dueAtMs == null && r.views.get(0).latestAtMs == null, "no due time when idle");
	}

	private static void quietPeriodThenLaunch()
	{
		// rows since T0, last row at T0+10 s: due at T0+70 s (60 s quiet), latest T0+600 s
		final IntakeDecision.SourceInput crm = IntakeDecisionTest.source("CRM", IntakeDecisionTest.waiting(IntakeDecisionTest.T0, IntakeDecisionTest.T0 + 10_000L), IntakeDecisionTest.DEFAULTS);
		final Counter counter = new Counter(312);
		IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 20_000L, false, false, counter, crm);
		check(r.action == IntakeDecision.Action.WAIT && r.nextDueAtMs.longValue() == IntakeDecisionTest.T0 + 70_000L, "wait until last row + 60 s: " + r.nextDueAtMs);
		check(r.views.get(0).state == IntakeDecision.SourceState.RECEIVING, "receiving");
		check(r.views.get(0).latestAtMs.longValue() == IntakeDecisionTest.T0 + 600_000L, "latest = since + 10 min");
		check(counter.asked.isEmpty(), "not due: not counted");
		r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 69_999L, false, false, counter, crm);
		check(r.action == IntakeDecision.Action.WAIT, "1 ms early");
		r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 70_000L, false, false, counter, crm);
		check(r.action == IntakeDecision.Action.LAUNCH && "QUIET".equals(r.reason) && r.rowsWaiting == 312L && r.launchSources.equals(Arrays.asList("CRM")), "launch at the quiet instant");
		check(r.views.get(0).state == IntakeDecision.SourceState.DUE, "due");
		check(counter.asked.equals(Arrays.asList("CRM")), "counted once, when due");
	}

	private static void maxWaitBeatsAMassLoad()
	{
		// rows keep arriving: last row 1 s ago, but waiting since 10 min
		final IntakeDecision.SourceInput crm = IntakeDecisionTest.source("CRM", IntakeDecisionTest.waiting(IntakeDecisionTest.T0, IntakeDecisionTest.T0 + 599_000L), IntakeDecisionTest.DEFAULTS);
		IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 600_000L, false, false, new Counter(1_000_000), crm);
		check(r.action == IntakeDecision.Action.LAUNCH && "MAX_WAIT".equals(r.reason), "max wait: " + r.reason);
		r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 599_999L, false, false, new Counter(1_000_000), crm);
		check(r.action == IntakeDecision.Action.WAIT && r.nextDueAtMs.longValue() == IntakeDecisionTest.T0 + 600_000L, "wait for the longest wait, not the quiet period: " + r.nextDueAtMs);
	}

	private static void minRowsKeepsWaitingUntilMaxWait()
	{
		final IntakeSettings five = IntakeSettings.of(null, null, Integer.valueOf(5), null);
		final IntakeDecision.SourceInput crm = IntakeDecisionTest.source("CRM", IntakeDecisionTest.waiting(IntakeDecisionTest.T0, IntakeDecisionTest.T0), five);
		IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 60_000L, false, false, new Counter(3), crm);
		check(r.action == IntakeDecision.Action.WAIT && r.nextDueAtMs.longValue() == IntakeDecisionTest.T0 + 600_000L, "3 rows < 5: wait for the longest wait");
		check(r.views.get(0).rowsWaiting.longValue() == 3L && r.views.get(0).state == IntakeDecision.SourceState.RECEIVING, "the view keeps the count");
		r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 60_000L, false, false, new Counter(5), crm);
		check(r.action == IntakeDecision.Action.LAUNCH, "5 rows: enough");
		r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 600_000L, false, false, new Counter(1), crm);
		check(r.action == IntakeDecision.Action.LAUNCH && "MAX_WAIT".equals(r.reason), "the longest wait launches with one row");
	}

	private static void batchCompleteLaunchesAtOnce()
	{
		final IntakePending pending = IntakeDecisionTest.waiting(IntakeDecisionTest.T0, IntakeDecisionTest.T0).withBatchComplete(IntakeDecisionTest.T0 + 1000L);
		final IntakeSettings five = IntakeSettings.of(null, null, Integer.valueOf(5), null);
		final IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 1000L, false, false, new Counter(1), IntakeDecisionTest.source("CRM", pending, five));
		check(r.action == IntakeDecision.Action.LAUNCH && "BATCH_COMPLETE".equals(r.reason), "batch complete launches at once, even under the minimum, with one row");
		// a batch-complete call with nothing at NEW: nothing to do
		final IntakeDecision.Result none = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 1000L, false, false, new Counter(0), IntakeDecisionTest.source("CRM", pending, five));
		check(none.action == IntakeDecision.Action.NOTHING && none.clearedSources.equals(Arrays.asList("CRM")), "no row: cleared");
	}

	private static void manualLaunchesEverySource()
	{
		final Counter counter = new Counter(2);
		final IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0, false, true, counter, IntakeDecisionTest.source("CRM", IntakePending.empty(), IntakeDecisionTest.DEFAULTS),
			IntakeDecisionTest.source("SAP", IntakePending.empty(), IntakeDecisionTest.DEFAULTS));
		check(r.action == IntakeDecision.Action.LAUNCH && "MANUAL".equals(r.reason) && r.launchSources.size() == 2 && r.rowsWaiting == 4L, "manual counts and launches every source");
		final IntakeDecision.Result empty = IntakeDecisionTest.decide(IntakeDecisionTest.T0, false, true, new Counter(0), IntakeDecisionTest.source("CRM", IntakePending.empty(), IntakeDecisionTest.DEFAULTS));
		check(empty.action == IntakeDecision.Action.NOTHING && empty.clearedSources.size() == 1, "manual with no row: nothing to do");
	}

	private static void zeroRowsClearsInsteadOfLaunching()
	{
		final IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 70_000L, false, false, new Counter(0),
			IntakeDecisionTest.source("CRM", IntakeDecisionTest.waiting(IntakeDecisionTest.T0, IntakeDecisionTest.T0), IntakeDecisionTest.DEFAULTS));
		check(r.action == IntakeDecision.Action.NOTHING && r.clearedSources.equals(Arrays.asList("CRM")) && r.views.get(0).state == IntakeDecision.SourceState.IDLE, "zero rows at NEW: pending cleared");
	}

	private static void neverLaunchesWhenNotOnOrRunning()
	{
		final IntakeDecision.SourceInput crm = IntakeDecisionTest.source("CRM", IntakeDecisionTest.waiting(IntakeDecisionTest.T0, IntakeDecisionTest.T0), IntakeDecisionTest.DEFAULTS);
		final Counter counter = new Counter(9);
		IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 700_000L, true, false, counter, crm);
		check(r.action == IntakeDecision.Action.NOTHING && counter.asked.isEmpty(), "a chain runs: nothing, nothing counted");
		check(r.views.get(0).state == IntakeDecision.SourceState.DUE, "but the view says due");
		for (final String state : new String[] { "OFF", "PAUSED", null, "" })
		{
			r = IntakeDecision.decide(IntakeDecisionTest.T0 + 700_000L, state, false, true, Arrays.asList(crm), counter);
			check(r.action == IntakeDecision.Action.NOTHING && counter.asked.isEmpty(), "state " + state + ": nothing, even for a manual request");
		}
	}

	private static void countOnlyWhenDue()
	{
		final Counter counter = new Counter(1);
		IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 1000L, false, false, counter, IntakeDecisionTest.source("A", IntakeDecisionTest.waiting(IntakeDecisionTest.T0, IntakeDecisionTest.T0), IntakeDecisionTest.DEFAULTS),
			IntakeDecisionTest.source("B", IntakePending.empty(), IntakeDecisionTest.DEFAULTS));
		check(counter.asked.isEmpty(), "waiting but not due, and idle: no count");
	}

	private static void severalSourcesAndReasonPriority()
	{
		final IntakeSettings quick = IntakeSettings.of(Integer.valueOf(5), null, null, null);
		final IntakeDecision.SourceInput a = IntakeDecisionTest.source("A", IntakeDecisionTest.waiting(IntakeDecisionTest.T0, IntakeDecisionTest.T0 + 595_000L), IntakeDecisionTest.DEFAULTS);
		final IntakeDecision.SourceInput b = IntakeDecisionTest.source("B", IntakeDecisionTest.waiting(IntakeDecisionTest.T0 + 500_000L, IntakeDecisionTest.T0 + 500_000L), quick);
		final Counter counter = new Counter(10);
		final IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 600_000L, false, false, counter, a, b);
		check(r.action == IntakeDecision.Action.LAUNCH && "MAX_WAIT".equals(r.reason) && r.launchSources.size() == 2 && r.rowsWaiting == 20L, "A is at its longest wait, B quiet: the chain takes both, MAX_WAIT wins: " + r.reason);
		check("MANUAL".equals(IntakeDecision.strongest(Arrays.asList("QUIET", "BATCH_COMPLETE", "MANUAL"))), "priority");
		check("BATCH_COMPLETE".equals(IntakeDecision.strongest(Arrays.asList("QUIET", "BATCH_COMPLETE"))), "priority 2");
		// one due and counted, one not due: only the due one is in the chain's source list
		final IntakeDecision.Result part = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 520_000L, false, false, new Counter(4), a, b);
		check(part.action == IntakeDecision.Action.LAUNCH && part.launchSources.equals(Arrays.asList("B")) && part.nextDueAtMs == null, "only B is due");
	}

	private static void sweepKeepsItsLabel()
	{
		final IntakePending swept = IntakePending.empty().counted(10L, IntakeDecisionTest.T0, IntakePending.Origin.SWEEP);
		final IntakeDecision.Result r = IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 60_000L, false, false, new Counter(10), IntakeDecisionTest.source("CRM", swept, IntakeDecisionTest.DEFAULTS));
		check(r.action == IntakeDecision.Action.LAUNCH && "SWEEP".equals(r.reason), "a sweep finding waits the quiet period too, and is labelled SWEEP: " + r.reason);
		final IntakePending recount = IntakePending.empty().counted(10L, IntakeDecisionTest.T0, IntakePending.Origin.RECOUNT);
		check("QUIET".equals(IntakeDecisionTest.decide(IntakeDecisionTest.T0 + 60_000L, false, false, new Counter(10), IntakeDecisionTest.source("CRM", recount, IntakeDecisionTest.DEFAULTS)).reason), "a recount is a normal quiet start");
	}
}
