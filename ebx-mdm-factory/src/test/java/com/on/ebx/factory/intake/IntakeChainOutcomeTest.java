package com.on.ebx.factory.intake;

/** JUnit-free: {@link IntakeChainOutcome} and {@link IntakePending}. */
public final class IntakeChainOutcomeTest
{
	public static void main(final String[] pArgs)
	{
		IntakeChainOutcomeTest.statuses();
		IntakeChainOutcomeTest.pauseAndReview();
		IntakeChainOutcomeTest.pendingTracksTheSignal();
		IntakeChainOutcomeTest.totalRejectIsNotAStopUnlessTheThresholdSaysSo();
		System.out.println("IntakeChainOutcomeTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	/** The four cases of a total reject / partial reject against the project's own threshold, plus the edges. */
	private static void totalRejectIsNotAStopUnlessTheThresholdSaysSo()
	{
		// no threshold + partial rejects
		check("DONE_WITH_REJECTS".equals(IntakeChainOutcome.statusOf(false, null, 100, 30, false)), "no threshold, partial rejects: held back, not stopped");
		check(!IntakeChainOutcome.pausesIntake(IntakeChainOutcome.statusOf(false, null, 100, 30, false)), "and the intake carries on");
		// no threshold + total reject (the workflow routed to the stop node: stagingStopped = true)
		check("DONE_WITH_REJECTS".equals(IntakeChainOutcome.statusOf(false, null, 1, 1, true)), "no threshold, total reject: DONE_WITH_REJECTS, whatever stagingStopped says");
		check(!IntakeChainOutcome.pausesIntake(IntakeChainOutcome.statusOf(false, null, 1, 1, true)), "and it does not pause");
		check(IntakeChainOutcome.opensReview(IntakeChainOutcome.statusOf(false, null, 1, 1, true), 1, true), "the review opens");
		// threshold reached
		check("STOPPED".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 1204, 72, true)), "threshold 5, 72/1204: stopped");
		check("STOPPED".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 1, 1, true)), "threshold 5, total reject (100 %): stopped");
		check(IntakeChainOutcome.pausesIntake(IntakeChainOutcome.statusOf(false, Integer.valueOf(99), 7, 7, true)), "threshold 99, total reject: stopped and paused");
		// threshold not reached; exactly at the threshold stops ("at least")
		check("STOPPED".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 1000, 50, true)), "threshold 5, exactly 5 %: reached, stopped");
		check("STOPPED".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 100, 5, true)), "threshold 5, 5 of 100: stopped");
		check("DONE_WITH_REJECTS".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 100, 4, false)), "threshold 5, 4 of 100: not reached");
		check("DONE_WITH_REJECTS".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 1000, 49, false)), "threshold 5, 4.9 %: not reached");
		check("DONE".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 1000, 0, false)), "nothing held back: DONE");
		// edges: "at least" (a threshold of 100 stops only on a total reject); counts unknown
		check("STOPPED".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(100), 3, 3, true)), "threshold 100, total reject: stopped");
		check("DONE_WITH_REJECTS".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(100), 100, 99, false)), "threshold 100, 99 of 100: not stopped");
		check("STOPPED".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(1), 100, 1, true)), "threshold 1, 1 of 100: stopped");
		check("DONE".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(1), 100, 0, false)), "threshold 1, nothing held back: DONE, never a stop");
		check("STOPPED".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 0, 4, true)), "counts unknown + a threshold: what the workflow decided");
		check("DONE_WITH_REJECTS".equals(IntakeChainOutcome.statusOf(false, Integer.valueOf(5), 0, 4, false)), "counts unknown, workflow did not stop");
		check("FAILED".equals(IntakeChainOutcome.statusOf(true, Integer.valueOf(5), 10, 10, true)), "a failure still wins");
		// a FAILED staging step that is only a total reject
		check(IntakeChainOutcome.isTotalRejectStep(true, 3, 3, "x"), "counts say total reject");
		check(!IntakeChainOutcome.isTotalRejectStep(true, 3, 2, "boom"), "partial: a FAILED step is a real failure");
		check(IntakeChainOutcome.isTotalRejectStep(false, 0, 0, "Staged 0. Every source row read was rejected, nothing was written: see the TransferReject rows."), "history gone: the step's own sentence");
		check(!IntakeChainOutcome.isTotalRejectStep(false, 0, 0, null) && !IntakeChainOutcome.isTotalRejectStep(false, 0, 0, "connection refused"), "no evidence: failure");
	}

	private static void statuses()
	{
		check("DONE".equals(IntakeChainOutcome.status(false, false, 0)), "clean");
		check("DONE_WITH_REJECTS".equals(IntakeChainOutcome.status(false, false, 3)), "rejects without a stop");
		check("STOPPED".equals(IntakeChainOutcome.status(false, true, 40)), "stopped");
		check("STOPPED".equals(IntakeChainOutcome.status(false, true, 0)), "stopped wins even with no count read");
		check("FAILED".equals(IntakeChainOutcome.status(true, true, 40)), "failed wins over stopped");
		check("FAILED".equals(IntakeChainOutcome.status(true, false, 0)), "failed");
	}

	private static void pauseAndReview()
	{
		check(IntakeChainOutcome.pausesIntake("STOPPED") && IntakeChainOutcome.pausesIntake("FAILED"), "stopped/failed pause");
		check(!IntakeChainOutcome.pausesIntake("DONE") && !IntakeChainOutcome.pausesIntake("DONE_WITH_REJECTS"), "done does not pause");
		check("STOPPED:6".equals(IntakeChainOutcome.pauseReason("STOPPED", 1204, 72, null)), IntakeChainOutcome.pauseReason("STOPPED", 1204, 72, null));
		check(IntakeChainOutcome.pauseReason("FAILED", 0, 0, null).equals("FAILED:"), "failed reason");
		check(IntakeChainOutcome.pauseReason("FAILED", 0, 0, "boom").equals("FAILED:boom"), "failed reason with message");
		check(IntakeChainOutcome.pauseReason("FAILED", 0, 0, new String(new char[500]).replace('\0', 'x')).length() == 307, "long message cut");
		check(IntakeChainOutcome.opensReview("DONE_WITH_REJECTS", 3, true), "review opens");
		check(!IntakeChainOutcome.opensReview("DONE_WITH_REJECTS", 3, false), "not asked");
		check(!IntakeChainOutcome.opensReview("DONE", 0, true), "nothing held back");
		check(IntakeChainOutcome.opensReview("STOPPED", 72, true), "a stopped chain holds rows back: review");
		check(!IntakeChainOutcome.opensReview("FAILED", 3, true), "a failed chain has no trustworthy count");
	}

	private static void pendingTracksTheSignal()
	{
		IntakePending p = IntakePending.empty();
		check(!p.isPending(), "empty");
		check(p.observe(null, 1000L) == p, "no signal changes nothing");
		p = p.observe(Long.valueOf(5000L), 6000L);
		check(p.isPending() && p.pendingSinceMs.longValue() == 6000L && p.lastRowMs.longValue() == 5000L, "first signal starts the period at 'now'");
		p = p.observe(Long.valueOf(9000L), 10000L);
		check(p.pendingSinceMs.longValue() == 6000L && p.lastRowMs.longValue() == 9000L, "later signal moves the last row only");
		p = p.observe(Long.valueOf(8000L), 11000L);
		check(p.lastRowMs.longValue() == 9000L, "an older signal never moves it back");
		final IntakePending consumed = p.launched(Long.valueOf(9000L));
		check(!consumed.isPending() && consumed.consumedSignalMs == 9000L, "launch consumes the signal");
		check(consumed.observe(Long.valueOf(9000L), 12000L) == consumed, "the consumed signal is not new rows");
		check(consumed.observe(Long.valueOf(9001L), 12000L).isPending(), "a newer signal is");
		// a recount that finds rows when a signal says nothing
		final IntakePending counted = consumed.counted(40L, 20000L, IntakePending.Origin.SWEEP);
		check(counted.isPending() && counted.origin == IntakePending.Origin.SWEEP && counted.pendingSinceMs.longValue() == 20000L, "sweep finds rows");
		check(counted.counted(40L, 30000L, IntakePending.Origin.RECOUNT).pendingSinceMs.longValue() == 20000L, "a second count keeps the period");
		check(!counted.counted(0L, 30000L, IntakePending.Origin.SWEEP).isPending(), "zero rows clears");
		final IntakePending batch = IntakePending.empty().withBatchComplete(500L);
		check(batch.isPending() && batch.batchComplete, "batch complete is pending and complete");
		check(!batch.launched(null).batchComplete, "launch clears the flag");
	}
}
