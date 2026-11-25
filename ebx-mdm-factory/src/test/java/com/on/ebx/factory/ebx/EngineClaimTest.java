package com.on.ebx.factory.ebx;

import java.util.Arrays;

import com.orchestranetworks.service.OperationException;

/** JUnit-free: run the main, a failure throws. The claim an engine entry point takes (audit F-15): decision, re-entrance, refusal, rollback, hand-off to a pool thread. */
public final class EngineClaimTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		EngineClaimTest.decisions();
		EngineClaimTest.freeResourceIsClaimedAndReleased();
		EngineClaimTest.ownClaimIsReentered();
		EngineClaimTest.otherThreadIsRefused();
		EngineClaimTest.handedOffClaimIsNotTheClaimersAnyMore();
		EngineClaimTest.severalResourcesAreAllOrNothing();
		EngineClaimTest.zones();
		System.out.println("EngineClaimTest OK");
	}

	private static void decisions() throws Exception
	{
		final Thread current = Thread.currentThread();
		EngineClaimTest.check("a free resource is acquired", EngineClaim.decide(null, current) == EngineClaim.Decision.ACQUIRE);
		final FactoryJobs.Handle mine = FactoryJobs.claim("D1", FactoryJobs.MASTER, "K", "alice");
		EngineClaimTest.check("a claim of the calling thread is re-entered", EngineClaim.decide(mine, current) == EngineClaim.Decision.REENTER);
		final Thread[] other = new Thread[1];
		final EngineClaim.Decision[] seen = new EngineClaim.Decision[1];
		other[0] = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				seen[0] = EngineClaim.decide(mine, Thread.currentThread());
			}
		});
		other[0].start();
		other[0].join();
		EngineClaimTest.check("a claim of another thread is refused", seen[0] == EngineClaim.Decision.REFUSE);
		final FactoryJobs.Handle handed = EngineClaimTest.handedOff("D1b");
		EngineClaimTest.check("no thread owns a claim handed to the pool", EngineClaim.decide(handed, current) == EngineClaim.Decision.REFUSE);
		FactoryJobs.release(handed);
		FactoryJobs.release(mine);
	}

	private static void freeResourceIsClaimedAndReleased() throws Exception
	{
		final EngineClaim claim = EngineClaim.enterProject("E1", "alice", "MASTER_LOAD_DIRECT", FactoryJobs.MASTER);
		final FactoryJobs.Handle live = FactoryJobs.live("E1", FactoryJobs.MASTER);
		EngineClaimTest.check("the entry holds the resource while it runs", live != null && "MASTER_LOAD_DIRECT".equals(live.kind));
		EngineClaimTest.check("another resource of the project stays free", FactoryJobs.live("E1", FactoryJobs.STAGING) == null);
		claim.release();
		EngineClaimTest.check("the resource is free after the call", FactoryJobs.live("E1", FactoryJobs.MASTER) == null);
		claim.release(); // a second release is harmless
	}

	private static void ownClaimIsReentered() throws Exception
	{
		// the queued job's body, a workflow step and an interactive claim all hold the resource on the thread that then calls the engine
		final FactoryJobs.Handle job = FactoryJobs.claim("R1", FactoryJobs.MASTER, "MASTER_BUILD_RUN", "bob");
		final EngineClaim inner = EngineClaim.enterProject("R1", "bob", "MASTER_LOAD_DIRECT", FactoryJobs.MASTER);
		EngineClaimTest.check("the engine does not take a second claim", FactoryJobs.live("R1", FactoryJobs.MASTER) == job);
		inner.release();
		EngineClaimTest.check("leaving the engine never frees the job's own claim", FactoryJobs.live("R1", FactoryJobs.MASTER) == job);
		FactoryJobs.release(job);

		final FactoryJobs.Handle whole = FactoryJobs.claimAll("R2", "STEP_RESET", "bob");
		final EngineClaim wholeInner = EngineClaim.enterProject("R2", "bob", "STAGING_LOAD_DIRECT", FactoryJobs.STAGING, FactoryJobs.MASTER);
		EngineClaimTest.check("a whole-project claim covers every resource an engine needs", FactoryJobs.live("R2", FactoryJobs.STAGING) == whole);
		wholeInner.release();
		FactoryJobs.release(whole);
	}

	private static void otherThreadIsRefused() throws Exception
	{
		final FactoryJobs.Handle job = FactoryJobs.claim("O1", FactoryJobs.STAGING, "STAGING_LOAD_RUN", "carol");
		final String[] message = new String[1];
		final Thread rest = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					EngineClaim.enterProject("O1", "dave", "STAGING_LOAD_DIRECT", FactoryJobs.STAGING).release();
					message[0] = "";
				}
				catch (final OperationException ex)
				{
					message[0] = ex.getMessage();
				}
			}
		});
		rest.start();
		rest.join();
		EngineClaimTest.check("a synchronous call over a running job is refused", message[0] != null && message[0].startsWith("Another run is using the staging of this project"));
		EngineClaimTest.check("the refusal names the holder: " + message[0], message[0].contains("carol") && message[0].contains("STAGING_LOAD_RUN"));
		EngineClaimTest.check("the refused call took nothing from the job", FactoryJobs.live("O1", FactoryJobs.STAGING) == job);
		FactoryJobs.release(job);
	}

	private static void handedOffClaimIsNotTheClaimersAnyMore() throws Exception
	{
		// the wizard claims on a request thread, then submits to the pool: that request thread is then just another caller
		final FactoryJobs.Handle handed = EngineClaimTest.handedOff("H1");
		try
		{
			EngineClaim.enterProject("H1", "erin", "MASTER_LOAD_DIRECT", FactoryJobs.MASTER).release();
			EngineClaimTest.check("the claiming thread must be refused once the job is submitted", false);
		}
		catch (final OperationException expected)
		{
			EngineClaimTest.check("refused after the hand-off", expected.getMessage().contains("Another run is using"));
		}
		handed.owner = Thread.currentThread(); // what the pool thread does when the body starts
		EngineClaim.enterProject("H1", "erin", "MASTER_LOAD_DIRECT", FactoryJobs.MASTER).release();
		EngineClaimTest.check("the body's own thread re-enters", FactoryJobs.live("H1", FactoryJobs.MASTER) == handed);
		FactoryJobs.release(handed);
	}

	private static FactoryJobs.Handle handedOff(final String pProject)
	{
		final FactoryJobs.Handle handle = FactoryJobs.claim(pProject, FactoryJobs.MASTER, "MASTER_BUILD_RUN", "x");
		handle.owner = null; // what FactoryJobs.submit does before the pool takes the body
		return handle;
	}

	private static void severalResourcesAreAllOrNothing() throws Exception
	{
		final Thread[] holder = new Thread[1];
		final FactoryJobs.Handle[] masterHeld = new FactoryJobs.Handle[1];
		holder[0] = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				masterHeld[0] = FactoryJobs.claim("S1", FactoryJobs.MASTER, "MASTER_BUILD_RUN", "frank");
			}
		});
		holder[0].start();
		holder[0].join();
		try
		{
			EngineClaim.enterProject("S1", "gina", "CAPABILITY_APPLY_DIRECT", FactoryJobs.STAGING, FactoryJobs.MASTER);
			EngineClaimTest.check("a refused second resource must refuse the call", false);
		}
		catch (final OperationException expected)
		{
			EngineClaimTest.check("the busy resource is named: " + expected.getMessage(), expected.getMessage().contains("master of this project"));
		}
		EngineClaimTest.check("the staging claimed first is given back", FactoryJobs.live("S1", FactoryJobs.STAGING) == null);
		FactoryJobs.release(masterHeld[0]);
		final EngineClaim both = EngineClaim.enterProject("S1", "gina", "CAPABILITY_APPLY_DIRECT", FactoryJobs.STAGING, FactoryJobs.MASTER);
		EngineClaimTest.check("both are taken when free", FactoryJobs.live("S1", FactoryJobs.STAGING) != null && FactoryJobs.live("S1", FactoryJobs.MASTER) != null);
		both.release();
		EngineClaimTest.check("and both given back", FactoryJobs.live("S1", FactoryJobs.STAGING) == null && FactoryJobs.live("S1", FactoryJobs.MASTER) == null);
	}

	private static void zones()
	{
		EngineClaimTest.check("a staging row writes the staging", Arrays.equals(EngineClaim.resourcesOfZones(Arrays.asList("STAGING")), new String[] { FactoryJobs.STAGING }));
		EngineClaimTest.check("a master row writes the master", Arrays.equals(EngineClaim.resourcesOfZones(Arrays.asList("MASTER")), new String[] { FactoryJobs.MASTER }));
		EngineClaimTest.check("a row with no zone is read as staging, like the applier does", Arrays.equals(EngineClaim.resourcesOfZones(Arrays.asList("")), new String[] { FactoryJobs.STAGING }));
		EngineClaimTest.check("a mixed batch takes both, in a fixed order",
			Arrays.equals(EngineClaim.resourcesOfZones(Arrays.asList("MASTER", "STAGING", "MASTER")), new String[] { FactoryJobs.STAGING, FactoryJobs.MASTER }));
		EngineClaimTest.check("no row takes nothing", EngineClaim.resourcesOfZones(Arrays.<String>asList()).length == 0);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
