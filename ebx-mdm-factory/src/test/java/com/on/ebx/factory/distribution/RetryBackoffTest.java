package com.on.ebx.factory.distribution;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class RetryBackoffTest
{
	public static void main(final String[] pArgs)
	{
		RetryBackoffTest.neverTriedSendsImmediately();
		RetryBackoffTest.delayGrowsWithAttempts();
		RetryBackoffTest.delayIsCapped();
		RetryBackoffTest.notDueBeforeItsDelay();
		RetryBackoffTest.dueOnceDelayElapsed();
		RetryBackoffTest.neverDueOnceExhausted();
		RetryBackoffTest.delayIsCappedAtFiveMinutes();
		RetryBackoffTest.maxAttemptsBudgetUnchanged();
		System.out.println("RetryBackoffTest OK");
	}

	private static void neverTriedSendsImmediately()
	{
		RetryBackoffTest.check("0 attempts -> 0 delay", RetryBackoff.delaySeconds(0) == 0L);
	}

	private static void delayGrowsWithAttempts()
	{
		final long d1 = RetryBackoff.delaySeconds(1);
		final long d2 = RetryBackoff.delaySeconds(2);
		final long d3 = RetryBackoff.delaySeconds(3);
		RetryBackoffTest.check("delay strictly grows: " + d1 + " " + d2 + " " + d3, d1 < d2 && d2 < d3);
	}

	private static void delayIsCapped()
	{
		RetryBackoffTest.check("delay never exceeds the cap", RetryBackoff.delaySeconds(50) <= 300L);
	}

	/**
	 * Round 5 (2026-09-26, task brief item 3, DistribTest live incident): the original 1-hour cap left a row
	 * at attempt 7 waiting roughly 33 minutes for its next try - too long for a target that is often back
	 * within a couple of minutes. The cap is now 5 minutes (300s), reached well before {@code MAX_ATTEMPTS}.
	 */
	private static void delayIsCappedAtFiveMinutes()
	{
		RetryBackoffTest.check("the cap is exactly 5 minutes", RetryBackoff.delaySeconds(50) == 300L);
		RetryBackoffTest.check("attempt 7 (the live-measured one) is already at the 5-minute cap, not ~33 minutes",
			RetryBackoff.delaySeconds(7) == 300L);
	}

	/** The retry BUDGET (how many attempts before manual replay) is a separate concern from the per-attempt delay cap, and was not changed by this round. */
	private static void maxAttemptsBudgetUnchanged()
	{
		RetryBackoffTest.check("MAX_ATTEMPTS is still 8", RetryBackoff.MAX_ATTEMPTS == 8);
	}

	private static void notDueBeforeItsDelay()
	{
		RetryBackoffTest.check("not due 1 second after a 1-attempt failure", !RetryBackoff.isDue(1, 1L));
	}

	private static void dueOnceDelayElapsed()
	{
		final long delay = RetryBackoff.delaySeconds(1);
		RetryBackoffTest.check("due once the delay has elapsed", RetryBackoff.isDue(1, delay));
	}

	private static void neverDueOnceExhausted()
	{
		RetryBackoffTest.check("exhausted at MAX_ATTEMPTS", RetryBackoff.exhausted(RetryBackoff.MAX_ATTEMPTS));
		RetryBackoffTest.check("never due once exhausted, however long", !RetryBackoff.isDue(RetryBackoff.MAX_ATTEMPTS, 999999L));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
