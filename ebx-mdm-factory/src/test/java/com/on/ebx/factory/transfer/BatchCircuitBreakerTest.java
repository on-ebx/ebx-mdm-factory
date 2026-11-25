package com.on.ebx.factory.transfer;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link BatchCircuitBreaker} (volume-guards lot, 2026-09-28, task item 6). */
public final class BatchCircuitBreakerTest
{
	public static void main(final String[] pArgs)
	{
		BatchCircuitBreakerTest.tripsAfterThreeConsecutiveFullFailures();
		BatchCircuitBreakerTest.aSuccessfulBatchResetsTheStreak();
		BatchCircuitBreakerTest.anEmptyBatchIsNeverCountedEitherWay();
		BatchCircuitBreakerTest.onceTrippedStaysTrippedAndNeverReportsATripAgain();
		BatchCircuitBreakerTest.configurableThreshold();
		BatchCircuitBreakerTest.thresholdFlooredAtOne();
		System.out.println("BatchCircuitBreakerTest OK");
	}

	/** The task brief's own default: K = 3 consecutive fully-failed batches trips it, exactly on the third. */
	private static void tripsAfterThreeConsecutiveFullFailures()
	{
		final BatchCircuitBreaker breaker = new BatchCircuitBreaker();
		BatchCircuitBreakerTest.check("first fully-failed batch: not tripped yet", !breaker.report(false, true));
		BatchCircuitBreakerTest.check("second: still not tripped", !breaker.report(false, true));
		BatchCircuitBreakerTest.check("not tripped before the third", !breaker.isTripped());
		BatchCircuitBreakerTest.check("third consecutive fully-failed batch trips it", breaker.report(false, true));
		BatchCircuitBreakerTest.check("isTripped now true", breaker.isTripped());
		BatchCircuitBreakerTest.check("consecutiveFailures reads 3", breaker.consecutiveFailures() == 3);
	}

	/** A batch that wrote at least one row (a partial success, exactly what bisection is FOR) resets the streak - never accumulates across unrelated isolated failures. */
	private static void aSuccessfulBatchResetsTheStreak()
	{
		final BatchCircuitBreaker breaker = new BatchCircuitBreaker();
		BatchCircuitBreakerTest.check("fail 1", !breaker.report(false, true));
		BatchCircuitBreakerTest.check("fail 2", !breaker.report(false, true));
		// a batch that wrote SOMETHING (pBatchWroteNothing=false) resets the streak, even though it still had some rejects
		BatchCircuitBreakerTest.check("a batch that wrote at least one row resets the streak", !breaker.report(false, false));
		BatchCircuitBreakerTest.check("fail 1 again after the reset", !breaker.report(false, true));
		BatchCircuitBreakerTest.check("fail 2 again", !breaker.report(false, true));
		BatchCircuitBreakerTest.check("never reached 3 in a row: not tripped", !breaker.isTripped());
		BatchCircuitBreakerTest.check("third in a row (this time uninterrupted) finally trips it", breaker.report(false, true));
	}

	/** An empty batch (nothing to commit at all) is never reported as a failure OR a success - it must not affect the streak either way. */
	private static void anEmptyBatchIsNeverCountedEitherWay()
	{
		final BatchCircuitBreaker breaker = new BatchCircuitBreaker();
		BatchCircuitBreakerTest.check("fail 1", !breaker.report(false, true));
		BatchCircuitBreakerTest.check("fail 2", !breaker.report(false, true));
		BatchCircuitBreakerTest.check("an empty flush in between does not reset (and is never itself a trip)", !breaker.report(true, false));
		BatchCircuitBreakerTest.check("the streak survived the empty flush: the third real failure still trips it", breaker.report(false, true));
	}

	/** Once tripped, the breaker never reports a second trip - the caller acts on it once and aborts. */
	private static void onceTrippedStaysTrippedAndNeverReportsATripAgain()
	{
		final BatchCircuitBreaker breaker = new BatchCircuitBreaker(1);
		BatchCircuitBreakerTest.check("trips on the very first fully-failed batch with threshold 1", breaker.report(false, true));
		BatchCircuitBreakerTest.check("a second call never reports true again, even for another failure", !breaker.report(false, true));
		BatchCircuitBreakerTest.check("stays tripped", breaker.isTripped());
	}

	private static void configurableThreshold()
	{
		final BatchCircuitBreaker breaker = new BatchCircuitBreaker(5);
		for (int i = 0; i < 4; i++)
		{
			BatchCircuitBreakerTest.check("not tripped before the configured threshold (attempt " + i + ")", !breaker.report(false, true));
		}
		BatchCircuitBreakerTest.check("trips exactly on the 5th consecutive failure", breaker.report(false, true));
	}

	/** A non-positive threshold is floored at 1 - a breaker that can never trip would defeat the whole point. */
	private static void thresholdFlooredAtOne()
	{
		final BatchCircuitBreaker breaker = new BatchCircuitBreaker(0);
		BatchCircuitBreakerTest.check("threshold 0 is floored at 1: trips on the very first failure", breaker.report(false, true));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
