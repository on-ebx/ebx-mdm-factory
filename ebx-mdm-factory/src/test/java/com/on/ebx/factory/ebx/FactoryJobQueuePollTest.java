package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. When the dispatcher reads the FactoryJob table (audit F-46). */
public final class FactoryJobQueuePollTest
{
	public static void main(final String[] pArgs)
	{
		final long safety = 30000L;
		FactoryJobQueuePollTest.check("a signalled job is read at once", FactoryJobQueue.shouldRead(true, 1000L, 999L, safety));
		FactoryJobQueuePollTest.check("nothing signalled, just read: not read", !FactoryJobQueue.shouldRead(false, 3000L, 1000L, safety));
		FactoryJobQueuePollTest.check("nothing signalled, 2 s later again: not read", !FactoryJobQueue.shouldRead(false, 5000L, 3000L, safety));
		FactoryJobQueuePollTest.check("nothing signalled, just under the safety net: not read", !FactoryJobQueue.shouldRead(false, 30999L, 1000L, safety));
		FactoryJobQueuePollTest.check("nothing signalled, at the safety net: read", FactoryJobQueue.shouldRead(false, 31000L, 1000L, safety));
		FactoryJobQueuePollTest.check("nothing signalled, far past the safety net: read", FactoryJobQueue.shouldRead(false, 900000L, 1000L, safety));
		FactoryJobQueuePollTest.check("a clock that went back reads rather than waits", FactoryJobQueue.shouldRead(false, 500L, 1000L, safety));
		FactoryJobQueuePollTest.check("the very first tick reads (the flag starts set, the last read at 0)", FactoryJobQueue.shouldRead(true, 2000L, 0L, safety));
		FactoryJobQueuePollTest.check("a safety net of 0 reads at every tick, as before F-46", FactoryJobQueue.shouldRead(false, 3000L, 3000L, 0L) && FactoryJobQueue.shouldRead(false, 5000L, 3000L, 0L));

		FactoryJobQueuePollTest.check("default 30 s", FactoryJobQueue.safetyPollMillis(null) == 30000L);
		FactoryJobQueuePollTest.check("a value in seconds", FactoryJobQueue.safetyPollMillis("10") == 10000L && FactoryJobQueue.safetyPollMillis(" 120 ") == 120000L);
		FactoryJobQueuePollTest.check("0 means every tick", FactoryJobQueue.safetyPollMillis("0") == 0L);
		FactoryJobQueuePollTest.check("negative = default", FactoryJobQueue.safetyPollMillis("-5") == 30000L);
		FactoryJobQueuePollTest.check("garbage = default", FactoryJobQueue.safetyPollMillis("often") == 30000L && FactoryJobQueue.safetyPollMillis("") == 30000L);
		FactoryJobQueuePollTest.check("the property name", "ebx.factory.jobs.safetyPollSeconds".equals(FactoryJobQueue.SAFETY_POLL_PROPERTY));

		// the tick before the module has started has nothing to read and must not throw
		FactoryJobQueue.tick();
		System.out.println("FactoryJobQueuePollTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
