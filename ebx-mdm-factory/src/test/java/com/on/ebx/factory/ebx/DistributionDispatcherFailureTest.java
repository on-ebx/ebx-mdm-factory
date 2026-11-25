package com.on.ebx.factory.ebx;

import com.on.ebx.factory.distribution.RetryBackoff;

/**
 * JUnit-free: run the main, a failure throws. What the dispatcher records for a send that THREW (any Throwable, not only the
 * two checked ones), and the retry plan that record feeds: a back-off that grows, then a stop at the attempt budget.
 */
public final class DistributionDispatcherFailureTest
{
	public static void main(final String[] pArgs)
	{
		DistributionDispatcherFailureTest.check("a message is kept", "no route".equals(DistributionDispatcher.failureText(new IllegalStateException("no route"))));
		DistributionDispatcherFailureTest.check("an Error without a message is named by its class",
			"java.lang.NoClassDefFoundError".equals(DistributionDispatcher.failureText(new NoClassDefFoundError())));
		DistributionDispatcherFailureTest.check("a blank message is replaced by the class", "java.lang.NullPointerException".equals(DistributionDispatcher.failureText(new NullPointerException("  "))));

		// a row whose send throws on every pass: one attempt counted per pass (what finishOrRetryFailed does), parked at the budget
		int attempts = 0;
		long previousDelay = 0L;
		boolean parked = false;
		int passes = 0;
		while (!parked && passes < 100)
		{
			passes++;
			attempts++;
			if (RetryBackoff.exhausted(attempts))
			{
				parked = true;
			}
			else
			{
				final long delay = RetryBackoff.delaySeconds(attempts);
				DistributionDispatcherFailureTest.check("the back-off never shrinks: " + previousDelay + " -> " + delay, delay >= previousDelay);
				DistributionDispatcherFailureTest.check("the first retry already waits (it was every 10 s before)", delay > 10L);
				previousDelay = delay;
			}
		}
		DistributionDispatcherFailureTest.check("the row is parked after exactly the budget: " + passes, parked && passes == RetryBackoff.MAX_ATTEMPTS);
		System.out.println("DistributionDispatcherFailureTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
