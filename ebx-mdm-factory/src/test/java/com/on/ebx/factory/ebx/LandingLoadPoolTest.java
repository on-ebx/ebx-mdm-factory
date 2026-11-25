package com.on.ebx.factory.ebx;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Lot L2 (2026-10-12): the pool of parallel landing loads - bound, thread names, the actor carried over, shutdown. JUnit-free. */
public final class LandingLoadPoolTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		LandingLoadPoolTest.neverMoreRunningThanTheBound();
		LandingLoadPoolTest.threadsAreNamedAndDaemon();
		LandingLoadPoolTest.theActorOfTheSubmitterIsKept();
		LandingLoadPoolTest.aFailingTaskDoesNotStopTheOthers();
		LandingLoadPoolTest.stopAllInterruptsRunningTasks();
		System.out.println("LandingLoadPoolTest OK");
	}

	private static void neverMoreRunningThanTheBound() throws Exception
	{
		final LandingLoadPool pool = LandingLoadPool.open(2);
		final AtomicInteger running = new AtomicInteger();
		final AtomicInteger peak = new AtomicInteger();
		final AtomicInteger finished = new AtomicInteger();
		try
		{
			for (int i = 0; i < 6; i++)
			{
				pool.submit(new Callable<Void>()
				{
					@Override
					public Void call() throws Exception
					{
						final int now = running.incrementAndGet();
						peak.accumulateAndGet(now, Math::max);
						Thread.sleep(40);
						running.decrementAndGet();
						finished.incrementAndGet();
						return null;
					}
				});
			}
			pool.awaitAll();
		}
		finally
		{
			pool.close();
		}
		LandingLoadPoolTest.check("all six ran", finished.get() == 6);
		LandingLoadPoolTest.check("never more than 2 at once (peak " + peak.get() + ")", peak.get() <= 2);
		LandingLoadPoolTest.check("and it did use both threads (peak " + peak.get() + ")", peak.get() == 2);
	}

	private static void threadsAreNamedAndDaemon() throws Exception
	{
		final LandingLoadPool pool = LandingLoadPool.open(1);
		try
		{
			final Future<String> name = pool.submit(new Callable<String>()
			{
				@Override
				public String call()
				{
					return Thread.currentThread().getName() + "|" + Thread.currentThread().isDaemon();
				}
			});
			final String[] parts = name.get().split("\\|");
			LandingLoadPoolTest.check("named mdm-factory-landing-N: " + parts[0], parts[0].matches("mdm-factory-landing-\\d+"));
			LandingLoadPoolTest.check("daemon", "true".equals(parts[1]));
		}
		finally
		{
			pool.close();
		}
	}

	private static void theActorOfTheSubmitterIsKept() throws Exception
	{
		final LandingLoadPool pool = LandingLoadPool.open(2);
		try
		{
			final String seen = Actor.as("alice", "workflow 42", new Actor.Work<String, Exception>()
			{
				@Override
				public String run() throws Exception
				{
					return pool.submit(new Callable<String>()
					{
						@Override
						public String call()
						{
							final Actor actor = Actor.explicit();
							return actor == null ? "none" : actor.userId + "/" + actor.via;
						}
					}).get();
				}
			});
			LandingLoadPoolTest.check("the pool thread acts as the launcher: " + seen, "alice/workflow 42".equals(seen));
			final String none = pool.submit(new Callable<String>()
			{
				@Override
				public String call()
				{
					return Actor.explicit() == null ? "none" : "set";
				}
			}).get();
			LandingLoadPoolTest.check("no actor submitted, none set (the session's user acts)", "none".equals(none));
		}
		finally
		{
			pool.close();
		}
	}

	private static void aFailingTaskDoesNotStopTheOthers() throws Exception
	{
		final LandingLoadPool pool = LandingLoadPool.open(2);
		final AtomicInteger ok = new AtomicInteger();
		try
		{
			pool.submit(new Callable<Void>()
			{
				@Override
				public Void call()
				{
					throw new IllegalStateException("boom");
				}
			});
			for (int i = 0; i < 3; i++)
			{
				pool.submit(new Callable<Void>()
				{
					@Override
					public Void call()
					{
						ok.incrementAndGet();
						return null;
					}
				});
			}
			pool.awaitAll(); // logs the failure, does not throw
		}
		finally
		{
			pool.close();
		}
		LandingLoadPoolTest.check("the three others ran", ok.get() == 3);
	}

	private static void stopAllInterruptsRunningTasks() throws Exception
	{
		final LandingLoadPool pool = LandingLoadPool.open(1);
		final CountDownLatch started = new CountDownLatch(1);
		final AtomicReference<Boolean> interrupted = new AtomicReference<Boolean>(Boolean.FALSE);
		final Future<Void> future = pool.submit(new Callable<Void>()
		{
			@Override
			public Void call()
			{
				started.countDown();
				try
				{
					Thread.sleep(60000);
				}
				catch (final InterruptedException ex)
				{
					interrupted.set(Boolean.TRUE);
				}
				return null;
			}
		});
		started.await();
		LandingLoadPool.stopAll();
		future.get(5, java.util.concurrent.TimeUnit.SECONDS);
		LandingLoadPoolTest.check("module shutdown interrupts a running load", interrupted.get().booleanValue());
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
