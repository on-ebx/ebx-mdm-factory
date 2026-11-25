package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Fix/derivation-apply-backfill (2026-10-17, lot G1, item 1). Live defect: the backfill of an applied derivation ran inside the
 * apply job (kind {@code CAPABILITY_APPLY_STAGING}) and answered "Another run is using the staging of this project (CAPABILITY_APPLY_STAGING, started by admin ...)" - the claim
 * of its own job. The pass must re-enter a claim held by the calling thread (the apply job, the staging load that auto-applies) and still refuse another run's.
 */
public final class DerivationBackfillClaimTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		DerivationBackfillClaimTest.theApplyJobsOwnClaimIsReentered();
		DerivationBackfillClaimTest.theLoadsOwnClaimIsReentered();
		DerivationBackfillClaimTest.anotherRunIsStillRefused();
		DerivationBackfillClaimTest.aFreeResourceIsClaimed();
		System.out.println("DerivationBackfillClaimTest OK");
	}

	private static void theApplyJobsOwnClaimIsReentered()
	{
		final FactoryJobs.Handle job = FactoryJobs.claim("G1a", FactoryJobs.STAGING, CapabilityApplyRun.APPLY_STAGING, "admin");
		try
		{
			DerivationBackfillClaimTest.check("the apply job's claim on this thread is re-entered",
				DerivationBackfillPass.claimDecision(FactoryJobs.live("G1a", FactoryJobs.STAGING), Thread.currentThread()) == EngineClaim.Decision.REENTER);
		}
		finally
		{
			FactoryJobs.release(job);
		}
	}

	private static void theLoadsOwnClaimIsReentered()
	{
		final FactoryJobs.Handle load = FactoryJobs.claim("G1b", FactoryJobs.STAGING, "STAGING_LOAD_DIRECT", "admin");
		try
		{
			DerivationBackfillClaimTest.check("the load that auto-applies holds the claim on this thread",
				DerivationBackfillPass.claimDecision(FactoryJobs.live("G1b", FactoryJobs.STAGING), Thread.currentThread()) == EngineClaim.Decision.REENTER);
		}
		finally
		{
			FactoryJobs.release(load);
		}
	}

	private static void anotherRunIsStillRefused() throws Exception
	{
		final FactoryJobs.Handle job = FactoryJobs.claim("G1c", FactoryJobs.STAGING, CapabilityApplyRun.APPLY_STAGING, "admin");
		final EngineClaim.Decision[] seen = new EngineClaim.Decision[1];
		final Thread other = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				seen[0] = DerivationBackfillPass.claimDecision(FactoryJobs.live("G1c", FactoryJobs.STAGING), Thread.currentThread());
			}
		});
		try
		{
			other.start();
			other.join();
			DerivationBackfillClaimTest.check("a claim held by another thread keeps the pass out", seen[0] == EngineClaim.Decision.REFUSE);
		}
		finally
		{
			FactoryJobs.release(job);
		}
	}

	private static void aFreeResourceIsClaimed()
	{
		DerivationBackfillClaimTest.check("free", DerivationBackfillPass.claimDecision(FactoryJobs.live("G1d", FactoryJobs.STAGING), Thread.currentThread()) == EngineClaim.Decision.ACQUIRE);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
