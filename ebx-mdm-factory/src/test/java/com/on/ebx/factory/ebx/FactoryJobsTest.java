package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. The in-memory part of {@link FactoryJobs} (lot C1, 2026-09-24). */
public final class FactoryJobsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final FactoryJobs.Handle staging = FactoryJobs.claim("P1", FactoryJobs.STAGING, "STAGING_LOAD_RUN", "alice");
		FactoryJobsTest.check("a free resource is claimed", staging != null);
		FactoryJobsTest.check("the same resource is refused at once", FactoryJobs.claim("P1", FactoryJobs.STAGING, "STAGING_LOAD_RUN", "bob") == null);
		final FactoryJobs.Handle landing = FactoryJobs.claim("P1", FactoryJobs.LANDING, "LANDING_RUN", "bob");
		FactoryJobsTest.check("another resource of the same project runs alongside", landing != null);
		FactoryJobsTest.check("another project is independent", FactoryJobs.claim("P2", FactoryJobs.STAGING, "STAGING_LOAD_RUN", "bob") != null);
		FactoryJobsTest.check("both jobs of P1 are listed", FactoryJobs.liveForProject("P1").size() == 2);
		FactoryJobsTest.check("the busy message names the holder", FactoryJobs.busyMessage(FactoryJobs.live("P1", FactoryJobs.STAGING)).contains("alice"));

		FactoryJobsTest.check("cancel by kind touches only that job", FactoryJobs.cancel("P1", "LANDING_RUN", "carol") && landing.cancelled() && !staging.cancelled());
		FactoryJobsTest.check("the stop reason names who", landing.stopReason().contains("carol"));

		FactoryJobs.release(staging);
		FactoryJobsTest.check("a released resource is free again", FactoryJobs.claim("P1", FactoryJobs.STAGING, "STAGING_LOAD_RUN", "bob") != null);

		try
		{
			FactoryJobs.awaitClaim("P1", FactoryJobs.LANDING, "LANDING_RUN", "wf", 10L);
			FactoryJobsTest.check("waiting on a held resource must time out", false);
		}
		catch (final com.orchestranetworks.service.OperationException expected)
		{
			FactoryJobsTest.check("the timeout says who holds it: " + expected.getMessage(), expected.getMessage().contains("bob"));
		}
		FactoryJobs.release(landing);
		FactoryJobsTest.check("awaitClaim takes a free resource", FactoryJobs.awaitClaim("P1", FactoryJobs.LANDING, "LANDING_RUN", "wf", 10L) != null);

		final FactoryJobs.Handle old = FactoryJobs.claim("P3", FactoryJobs.MASTER, "MASTER_BUILD_RUN", "dave");
		old.deadline = 1L;
		FactoryJobs.enforceDeadlines(System.currentTimeMillis());
		FactoryJobsTest.check("the watchdog stops a job past its deadline", old.cancelled() && old.stopReason().contains("time limit"));

		// volume-guards lot (2026-09-28, task item 5): a job that keeps checking in must never be cancelled for
		// merely running a long time - only a job that stops checking in for the whole window is.
		FactoryJobsTest.checkinKeepsAliveWhileHungJobHitsTheDeadline();
		System.out.println("FactoryJobsTest OK");
	}

	/** {@link FactoryJobs.Handle#touch()}, exercised through the only public entry point that calls it, {@link FactoryJobs.Handle#cancelled()}. */
	private static void checkinKeepsAliveWhileHungJobHitsTheDeadline() throws Exception
	{
		final FactoryJobs.Handle checkingIn = FactoryJobs.claim("P4", FactoryJobs.LANDING, "LANDING_RUN", "erin");
		final FactoryJobs.Handle hung = FactoryJobs.claim("P4", FactoryJobs.STAGING, "STAGING_LOAD_RUN", "erin");
		try
		{
			// both start with a deliberately short window: enforceDeadlines would stop either one at once if it
			// never checked in again.
			checkingIn.deadline = System.currentTimeMillis() + 50L;
			hung.deadline = System.currentTimeMillis() + 50L;
			// "checkingIn" keeps calling cancelled() - the only thing every real *Run body already does between
			// stages/batches/rows - which must keep pushing its own deadline forward from now.
			for (int i = 0; i < 5; i++)
			{
				Thread.sleep(30L);
				FactoryJobsTest.check("still not cancelled while it keeps checking in", !checkingIn.cancelled());
			}
			// "hung" never checks in again after its short window: it is the case a genuine hang must still be caught.
			Thread.sleep(80L);
			FactoryJobs.enforceDeadlines(System.currentTimeMillis());
			FactoryJobsTest.check("a job that never checks in again is still stopped", hung.cancelled() && hung.stopReason().contains("time limit"));
			FactoryJobsTest.check("a job that kept checking in is unaffected by the same sweep", !checkingIn.cancelled());
		}
		finally
		{
			FactoryJobs.release(checkingIn);
			FactoryJobs.release(hung);
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
