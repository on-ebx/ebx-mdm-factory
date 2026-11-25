package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.orchestranetworks.service.LoggingCategory;

/**
 * Lot L2 (2026-10-12, docs/VOLUME.md "Lot L2 - parallel landing loads"): the threads that copy several sources of one project into the landing at the
 * same time. One pool per chain of sources, opened with the bound {@code mdm.factory.landing.parallelSources} ({@code landing.LandingParallelism}) and
 * closed when the chain ends; every pool is also known here so the module can stop them all at shutdown ({@link #stopAll}, called by
 * {@code FactoryModuleRegistration}).
 * <p>
 * Threads are daemon threads named {@code mdm-factory-landing-N} (N counts for the whole JVM). A task runs as the same {@link Actor} as the thread that
 * submitted it - the actor is thread-bound ({@link Actor#as}), and the steps a load records must keep saying who launched the run, not "system".
 * <p>
 * What a task may share and may not: the {@code Session} (the queue's system session is already used by several jobs at once), the {@code Adaptation}
 * of the factory dataset and the {@code LandingRunProgress} (thread-safe since this lot) are shared; an MCP client is NOT ({@link McpServers#client}
 * "Not shared between threads") - {@code LandingFlows.load} creates its own at each call, so each task has its own. Nothing here claims a project
 * resource: the tasks run inside the job (or the workflow step) that already holds LANDING.
 */
public final class LandingLoadPool
{
	private static final AtomicInteger THREAD_COUNT = new AtomicInteger();
	private static final List<LandingLoadPool> OPEN = new ArrayList<LandingLoadPool>();

	private final ThreadPoolExecutor executor;
	private final List<Future<?>> submitted = new ArrayList<Future<?>>();

	private LandingLoadPool(final int pThreads)
	{
		this.executor = new ThreadPoolExecutor(pThreads, pThreads, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "mdm-factory-landing-" + LandingLoadPool.THREAD_COUNT.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			}
		});
	}

	/** Opens a pool of {@code pThreads} threads (at least 1). The caller closes it with {@link #close} in a {@code finally}. */
	public static LandingLoadPool open(final int pThreads)
	{
		final LandingLoadPool pool = new LandingLoadPool(Math.max(1, pThreads));
		synchronized (LandingLoadPool.OPEN)
		{
			LandingLoadPool.OPEN.add(pool);
		}
		return pool;
	}

	/** Queues {@code pWork}; it starts when one of the pool's threads is free (FIFO), under the actor of the calling thread. */
	public <T> Future<T> submit(final Callable<T> pWork)
	{
		final Actor actor = Actor.explicit();
		final Callable<T> task = actor == null ? pWork : new Callable<T>()
		{
			@Override
			public T call() throws Exception
			{
				return Actor.as(actor.userId, actor.via, new Actor.Work<T, Exception>()
				{
					@Override
					public T run() throws Exception
					{
						return pWork.call();
					}
				});
			}
		};
		final Future<T> future = this.executor.submit(task);
		synchronized (this.submitted)
		{
			this.submitted.add(future);
		}
		return future;
	}

	/**
	 * Waits until everything submitted so far has ended (done, failed or cancelled). If the calling thread is interrupted (the module stops, the job is
	 * killed) the pool is shut down at once and the interruption is kept for the caller. A task's exception is the task's business (the chain's tasks
	 * catch their own): it is logged here and never rethrown.
	 */
	public void awaitAll()
	{
		final List<Future<?>> snapshot;
		synchronized (this.submitted)
		{
			snapshot = new ArrayList<Future<?>>(this.submitted);
		}
		for (final Future<?> future : snapshot)
		{
			try
			{
				future.get();
			}
			catch (final InterruptedException ex)
			{
				this.executor.shutdownNow();
				Thread.currentThread().interrupt();
				return;
			}
			catch (final ExecutionException ex)
			{
				LoggingCategory.getKernel().error("[mdm-factory] a landing load task ended with an uncaught error: " + ex.getCause(), ex.getCause());
			}
			catch (final java.util.concurrent.CancellationException ex)
			{
				// the pool was shut down under it: nothing more to wait for
			}
		}
	}

	/** Shuts the pool down (the tasks already queued are dropped only when the pool was interrupted, see {@link #awaitAll}) and forgets it. */
	public void close()
	{
		this.executor.shutdown();
		synchronized (LandingLoadPool.OPEN)
		{
			LandingLoadPool.OPEN.remove(this);
		}
	}

	/** Module shutdown: every pool still open stops now, running tasks are interrupted. */
	public static void stopAll()
	{
		final List<LandingLoadPool> pools;
		synchronized (LandingLoadPool.OPEN)
		{
			pools = new ArrayList<LandingLoadPool>(LandingLoadPool.OPEN);
			LandingLoadPool.OPEN.clear();
		}
		for (final LandingLoadPool pool : pools)
		{
			final ExecutorService executor = pool.executor;
			executor.shutdownNow();
		}
	}
}
