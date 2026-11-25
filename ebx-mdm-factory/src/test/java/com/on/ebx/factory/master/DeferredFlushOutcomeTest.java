package com.on.ebx.factory.master;

/** JUnit-free: run the main, a failure throws. What a failed flush of deferred references does to a master load's counters and message. */
public final class DeferredFlushOutcomeTest
{
	public static void main(final String[] pArgs)
	{
		final long[] moved = DeferredFlushOutcome.moveToUnresolved(10L, 2L, 4L);
		DeferredFlushOutcomeTest.check("4 of 10 resolved were never written: 6 resolved, 6 unresolved", moved[0] == 6L && moved[1] == 6L);
		final long[] none = DeferredFlushOutcome.moveToUnresolved(10L, 2L, 0L);
		DeferredFlushOutcomeTest.check("nothing lost, nothing moved", none[0] == 10L && none[1] == 2L);
		final long[] capped = DeferredFlushOutcome.moveToUnresolved(3L, 0L, 9L);
		DeferredFlushOutcomeTest.check("never more than were counted resolved: 0 resolved, 3 unresolved", capped[0] == 0L && capped[1] == 3L);
		final long[] negative = DeferredFlushOutcome.moveToUnresolved(3L, 1L, -5L);
		DeferredFlushOutcomeTest.check("a negative loss moves nothing", negative[0] == 3L && negative[1] == 1L);

		DeferredFlushOutcomeTest.check("no failed row, no sentence", DeferredFlushOutcome.warning(0L, 0L, null).isEmpty());
		final String warning = DeferredFlushOutcome.warning(500L, 1200L, "lock timeout");
		DeferredFlushOutcomeTest.check("names rows, references and the reason: " + warning, warning.startsWith("WARNING:") && warning.contains("500 master row(s)")
			&& warning.contains("1200 reference(s)") && warning.contains("lock timeout"));
		DeferredFlushOutcomeTest.check("works without a reason", DeferredFlushOutcome.warning(1L, 1L, null).contains("1 master row(s)"));
		System.out.println("DeferredFlushOutcomeTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
