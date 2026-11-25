package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. {@link FactoryJobLimits} (audit F-37): when a QUEUED job may be claimed and marked RUNNING. */
public final class FactoryJobLimitsTest
{
	public static void main(final String[] pArgs)
	{
		FactoryJobLimitsTest.check("no property: unlimited", FactoryJobLimits.heavyLimit(null) == 0);
		FactoryJobLimitsTest.check("a number is read", FactoryJobLimits.heavyLimit(" 2 ") == 2);
		FactoryJobLimitsTest.check("a typo or a negative value means unlimited, as before the limit existed", FactoryJobLimits.heavyLimit("two") == 0 && FactoryJobLimits.heavyLimit("-1") == 0);

		FactoryJobLimitsTest.check("a free worker starts a light job", FactoryJobLimits.mayStart(4, 3, false, 0, 0));
		FactoryJobLimitsTest.check("every worker busy: the job stays QUEUED", !FactoryJobLimits.mayStart(4, 4, false, 0, 0));
		FactoryJobLimitsTest.check("more in flight than workers (a reduced pool) stays QUEUED", !FactoryJobLimits.mayStart(2, 5, false, 0, 0));
		FactoryJobLimitsTest.check("every worker busy also holds back a heavy job under its limit", !FactoryJobLimits.mayStart(4, 4, true, 0, 2));

		FactoryJobLimitsTest.check("unlimited heavy jobs: the default changes nothing", FactoryJobLimits.mayStart(4, 3, true, 3, 0));
		FactoryJobLimitsTest.check("a heavy job under the limit starts", FactoryJobLimits.mayStart(4, 1, true, 1, 2));
		FactoryJobLimitsTest.check("a heavy job at the limit waits although workers are free", !FactoryJobLimits.mayStart(4, 2, true, 2, 2));
		FactoryJobLimitsTest.check("a light job is not held by the heavy limit", FactoryJobLimits.mayStart(4, 2, false, 2, 2));

		// the in-memory side: a kind is heavy once registered, and the default (no property) lets a free pool start it
		FactoryJobs.registerHeavy("HEAVY_TEST_KIND");
		FactoryJobLimitsTest.check("a registered kind is heavy", FactoryJobs.isHeavy("HEAVY_TEST_KIND") && !FactoryJobs.isHeavy("LIGHT_TEST_KIND"));
		FactoryJobLimitsTest.check("a fresh engine (nothing in flight) may start a job", FactoryJobs.mayStart("LIGHT_TEST_KIND") && FactoryJobs.mayStart("HEAVY_TEST_KIND"));

		// a job waiting for a thread is never cancelled by the watchdog, its deadline starts with its body
		final FactoryJobs.Handle waiting = FactoryJobs.claim("PLIM", FactoryJobs.MASTER, "MASTER_BUILD_RUN", "dave");
		waiting.submitted = true;
		waiting.deadline = 1L;
		FactoryJobs.enforceDeadlines(System.currentTimeMillis());
		FactoryJobLimitsTest.check("a submitted job that has not started is left alone by the watchdog", waiting.stopReason() == null);
		waiting.started = true;
		waiting.deadline = 1L;
		FactoryJobs.enforceDeadlines(System.currentTimeMillis());
		FactoryJobLimitsTest.check("once started it is under the time limit again", waiting.cancelled() && waiting.stopReason().contains("time limit"));
		FactoryJobs.release(waiting);
		System.out.println("FactoryJobLimitsTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
