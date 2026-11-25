package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The factory's one background-job engine (lot C1, 2026-09-24), replacing the five private thread pools of the
 * {@code *Run} classes:
 * <ul>
 * <li><b>One job per RESOURCE of a project at a time</b> (SOURCES, LANDING, DESIGN, STAGING, MASTER, MATCHING - what the
 * job writes): {@link #claim} is an atomic put on an in-memory registry - two clicks never start two staging builds
 * (the old check-a-RUNNING-row-then-insert raced), while jobs on different resources of the same project run side by
 * side (a data hub loads while it designs). Interactive starts are refused with who is running what since when
 * ({@link #claim}); automated ones (workflow, scheduler) wait for the resource ({@link #awaitClaim}). The hub's
 * everyday flows (Connectors feeds, matching triggers, distribution) never go through this engine. The {@code PipelineStep} row stays the durable trace; the "stale after 30 minutes" rule is gone: a RUNNING
 * row with no live job ({@link #isLive}) is an orphan, not a slow run.</li>
 * <li><b>A bounded pool</b> ({@code Settings.jobsMaxParallel}, default 4, read at module start), stopped with the
 * module. A job waiting for a thread is queued, not refused.</li>
 * <li><b>A system session</b>, created at module start, never expiring with a person's web session. The work stays
 * attributed to its launcher: the job runs inside {@link Actor#as}, so every step it records says who started it,
 * and calls to EBX's own MCP servers carry a session token minted for the launcher ({@link SessionToken}), with the
 * launcher's rights. Launching is administrator-only anyway.</li>
 * <li><b>Stop and time limit</b>: {@link #cancel} sets the job's flag; the job checks it between stages
 * ({@link Handle#cancelled}). A watchdog cancels a job that has gone {@code Settings.jobMaxMinutes} (default 120)
 * WITHOUT A SINGLE CHECK-IN (volume-guards lot, 2026-09-28: {@link Handle#touch()}, called from every {@link
 * Handle#cancelled()} - i.e. every existing check-in point - pushes the deadline forward from now, so this is an
 * INACTIVITY timeout, not a cap on the job's total duration). The window starts when the body starts, never while the job
 * waits for a pool thread (audit F-37), and the dispatcher only starts a job when a thread is free ({@link #mayStart}).</li>
 * <li><b>Restart</b>: {@link #start} marks every PipelineStep row still RUNNING as INTERRUPTED ("the server restarted
 * during the run") - nothing can be running at module start, so no orphan survives a restart.</li>
 * </ul>
 * Single node: the registry is in memory, which fits an EBX repository served by one active node.
 */
public final class FactoryJobs
{
	public static final String INTERRUPTED = "INTERRUPTED";
	static final int DEFAULT_MAX_PARALLEL = 4;
	static final int DEFAULT_MAX_MINUTES = 120;

	/** A running (or queued) job. */
	public static final class Handle
	{
		public final String projectId;
		/** What the job writes, and so what it excludes: SOURCES, LANDING, DESIGN, STAGING, MASTER, MATCHING. */
		public final String resource;
		public final String kind;
		public final String launcher;
		public final long createdAt;
		/**
		 * Volume-guards lot (2026-09-28, task item 5): despite the name (kept for the test in this class - see
		 * {@link FactoryJobsTest} - and because it is still literally the instant {@link #enforceDeadlines} compares
		 * against), this is no longer fixed at job creation. {@link #touch()} pushes it forward from NOW every time
		 * the job checks in - see that method's own Javadoc for why {@link #cancelled()} calling it makes this an
		 * INACTIVITY deadline rather than a total-duration one, with no other call site anywhere in this module
		 * needing to change.
		 */
		volatile long deadline;
		/** Handed to the pool and not yet ended: until {@link #started}, the job may be waiting for a thread, and the watchdog leaves it alone (audit F-37). */
		volatile boolean submitted;
		volatile boolean started;
		/**
		 * The thread that holds the claim and runs its work (audit F-15): the thread that called {@link FactoryJobs#claim} until the job is
		 * {@link FactoryJobs#submit submitted}, then the pool thread running the body. An engine entry point ({@link EngineClaim}) called on that
		 * thread is the claimed work itself and goes on; called on any other thread it is a second run on the same resource and is refused.
		 * Null while the job waits for a pool thread: nobody owns it then.
		 */
		volatile Thread owner;
		volatile String stepId;
		volatile String stopReason;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		/** 0 (no limit, {@link #deadline} stays {@code Long.MAX_VALUE} forever) or the window {@link #touch()} re-applies from now on every check-in. */
		private final long maxMillis;

		Handle(final String pProjectId, final String pResource, final String pKind, final String pLauncher, final long pMaxMillis)
		{
			this.projectId = pProjectId;
			this.resource = pResource;
			this.kind = pKind;
			this.launcher = pLauncher;
			this.createdAt = System.currentTimeMillis();
			this.maxMillis = pMaxMillis;
			this.deadline = pMaxMillis > 0 ? this.createdAt + pMaxMillis : Long.MAX_VALUE;
			this.owner = Thread.currentThread();
		}

		/** Whether {@code pThread} is the thread that holds this claim and runs its work (see {@link #owner}). */
		boolean ownedBy(final Thread pThread)
		{
			return pThread != null && this.owner == pThread;
		}

		/**
		 * Volume-guards lot (2026-09-28, task item 5, "job deadline based on inactivity, not total duration"):
		 * pushes {@link #deadline} to {@code now + maxMillis} - called from {@link #cancelled()}, so EVERY existing
		 * check-in point in this module (every {@code *Run}/{@code *Configurator} body already calls {@code
		 * pHandle.cancelled()} "between stages", per this class' own original class Javadoc) counts as progress with
		 * no other file needing to change. Before this lot a legitimate multi-hour load (CLAUDE.md's own "EBX tient
		 * le volume" - a large batch genuinely takes hours, not minutes) was killed by {@code jobMaxMinutes} (default
		 * 120) measured from job START regardless of how much real work it was still doing; now only a job that
		 * checks in ZERO times for a full {@code jobMaxMinutes} window - hung, deadlocked, or a genuine infinite loop
		 * with no check-in at all - is ever stopped. A no-op when {@link #maxMillis} is 0 (no limit configured).
		 */
		void touch()
		{
			if (this.maxMillis > 0)
			{
				this.deadline = System.currentTimeMillis() + this.maxMillis;
			}
		}

		/**
		 * Whether a stop was asked (a person, or the time limit): the job checks this between stages and ends early.
		 * Also counts as a check-in ({@link #touch()}) - see that method's own Javadoc.
		 */
		public boolean cancelled()
		{
			this.touch();
			return this.cancelled.get();
		}

		/** Why the job was stopped, for its final message; null when it was not. */
		public String stopReason()
		{
			return this.stopReason;
		}

		/** The parent PipelineStep this job reports into, once {@link FactoryJobs#attach} was called. */
		public String stepId()
		{
			return this.stepId;
		}

		void cancel(final String pReason)
		{
			if (this.cancelled.compareAndSet(false, true))
			{
				this.stopReason = pReason;
			}
		}

		public boolean isStarted()
		{
			return this.started;
		}
	}

	/** The body of a job: runs on a pool thread, with the system session, inside {@link Actor#as} for its launcher. */
	public interface Body
	{
		void run(Session pJobSession, Handle pHandle) throws Exception;
	}

	private static final ConcurrentMap<String, Handle> LIVE = new ConcurrentHashMap<String, Handle>();
	private static volatile ExecutorService executor;
	private static volatile ScheduledExecutorService watchdog;
	private static volatile Session systemSession;
	private static volatile long maxMillis = FactoryJobs.DEFAULT_MAX_MINUTES * 60000L;
	private static volatile int poolSize = FactoryJobs.DEFAULT_MAX_PARALLEL;
	/** Jobs handed to the pool and not yet ended; one counter per started pool, so a job of a stopped pool never decrements the next one's. */
	private static volatile AtomicInteger inFlight = new AtomicInteger();
	private static final java.util.Set<String> HEAVY_KINDS = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	private FactoryJobs()
	{
	}

	// ======================================================================================================= lifecycle

	/** Module start: pool, watchdog, system session, and every orphan RUNNING step marked INTERRUPTED. */
	public static synchronized void start(final Repository pRepository, final Session pSystemSession, final Adaptation pFactory)
	{
		FactoryJobs.systemSession = pSystemSession;
		final Adaptation settings = pFactory == null ? null
			: pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final int parallel = FactoryJobs.positive(settings == null ? null : settings.get(FactoryPaths.Settings.JOBS_MAX_PARALLEL), FactoryJobs.DEFAULT_MAX_PARALLEL);
		final int minutes = FactoryJobs.positive(settings == null ? null : settings.get(FactoryPaths.Settings.JOB_MAX_MINUTES), FactoryJobs.DEFAULT_MAX_MINUTES);
		FactoryJobs.maxMillis = minutes * 60000L;
		FactoryJobs.poolSize = parallel;
		FactoryJobs.inFlight = new AtomicInteger();
		final AtomicInteger count = new AtomicInteger();
		final ThreadFactory threads = new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-job-" + count.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			}
		};
		FactoryJobs.executor = new ThreadPoolExecutor(parallel, parallel, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), threads);
		FactoryJobs.watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-job-watchdog");
				thread.setDaemon(true);
				return thread;
			}
		});
		FactoryJobs.watchdog.scheduleWithFixedDelay(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					FactoryJobs.enforceDeadlines(System.currentTimeMillis());
				}
				catch (final Throwable ex) // a scheduled task that throws is never run again: the time limit would silently stop being enforced
				{
					LoggingCategory.getKernel().error("[mdm-factory] job watchdog pass failed: " + ex, ex);
				}
			}
		}, 30L, 30L, TimeUnit.SECONDS);
		if (pFactory != null)
		{
			final int interrupted = FactoryJobs.interruptOrphans(pSystemSession, pFactory);
			if (interrupted > 0)
			{
				LoggingCategory.getKernel().info("[mdm-factory] " + interrupted + " pipeline step(s) left RUNNING by the previous server run marked INTERRUPTED.");
			}
		}
	}

	/** Module stop: running jobs are asked to stop, then the pool and the watchdog are shut down. */
	public static synchronized void stop()
	{
		for (final Handle handle : FactoryJobs.LIVE.values())
		{
			handle.cancel("the server is shutting down");
		}
		if (FactoryJobs.watchdog != null)
		{
			FactoryJobs.watchdog.shutdownNow();
			FactoryJobs.watchdog = null;
		}
		if (FactoryJobs.executor != null)
		{
			FactoryJobs.executor.shutdownNow();
			FactoryJobs.executor = null;
		}
		FactoryJobs.LIVE.clear();
	}

	// ============================================================================================================ jobs

	/** Resources: what a job writes. Two jobs of one project run together unless they share a resource. */
	public static final String SOURCES = "SOURCES";
	public static final String LANDING = "LANDING";
	public static final String DESIGN = "DESIGN";
	public static final String STAGING = "STAGING";
	public static final String MASTER = "MASTER";
	public static final String MATCHING = "MATCHING";
	/** Volume-operations lot 8a (2026-09-28, task 1): {@link DistributionSendRun}'s own resource - a first-full/send-now job never overlaps another send of the same project. */
	public static final String DISTRIBUTION = "DISTRIBUTION";
	/** Every resource, for work that must hold a whole project (a step reset, a project deletion). */
	static final String[] RESOURCES =
		{ FactoryJobs.SOURCES, FactoryJobs.LANDING, FactoryJobs.DESIGN, FactoryJobs.STAGING, FactoryJobs.MASTER, FactoryJobs.MATCHING, FactoryJobs.DISTRIBUTION };
	/**
	 * Volume-operations lot 8a (2026-09-28, task 2): the synthetic resource name a WHOLE-PROJECT job (a step
	 * reset, a project deletion) is registered and displayed under - {@link #claimAll} claims every entry of
	 * {@link #RESOURCES} AND this one, under the SAME {@link Handle} instance, so {@code
	 * FactoryJobStatus}'/{@code FactoryJobQueue}'s existing "read the row's own resource field, then {@link
	 * #live}" idiom keeps working verbatim for a whole-project job without needing to know it is special.
	 */
	public static final String PROJECT = "PROJECT";

	static String key(final String pProjectId, final String pResource)
	{
		return pProjectId + "|" + pResource;
	}

	/**
	 * Claims {@code pResource} of the project for a job of {@code pKind} - the interactive mode: null at once when a job
	 * already holds that resource (say who with {@link #live}). The claim must then be either {@link #submit submitted}
	 * or {@link #release released}. Other resources of the same project stay free: two applications load their landing
	 * while a proposal pass runs.
	 */
	public static Handle claim(final String pProjectId, final String pResource, final String pKind, final String pLauncher)
	{
		final Handle handle = new Handle(pProjectId, pResource, pKind, pLauncher, FactoryJobs.maxMillis);
		return FactoryJobs.LIVE.putIfAbsent(FactoryJobs.key(pProjectId, pResource), handle) == null ? handle : null;
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 2): claims EVERY resource of the project at once, for
	 * whole-project work (a step reset, a project deletion) that must never overlap any other job - the SAME
	 * work {@link FactoryJobQueue#exclusively} already did on the caller's own thread (before this lot, the
	 * only way to run this kind of work), now expressed as a single {@link Handle} the job queue can dispatch
	 * like any other. One {@link Handle} is stored under {@link #PROJECT} (what the job row displays as its
	 * {@code resource}, so {@code FactoryJobStatus}'s existing {@code live(project, row.resource)} idiom keeps
	 * working unchanged) AND every entry of {@link #RESOURCES} (so no single-resource job can start while this
	 * one runs) - the SAME instance every time, so {@link Handle#cancelled()}'s {@link Handle#touch()} call
	 * keeps ONE deadline alive, not six independent ones, and a {@link #cancel} by kind reaches it from any of
	 * its seven keys. Null when any of the seven is already held (the whole-project job stays QUEUED and
	 * retries on the dispatcher's next tick, same "nothing is refused" posture as every other job kind).
	 */
	public static Handle claimAll(final String pProjectId, final String pKind, final String pLauncher)
	{
		final Handle handle = new Handle(pProjectId, FactoryJobs.PROJECT, pKind, pLauncher, FactoryJobs.maxMillis);
		if (FactoryJobs.LIVE.putIfAbsent(FactoryJobs.key(pProjectId, FactoryJobs.PROJECT), handle) != null)
		{
			return null;
		}
		for (final String resource : FactoryJobs.RESOURCES)
		{
			if (FactoryJobs.LIVE.putIfAbsent(FactoryJobs.key(pProjectId, resource), handle) != null)
			{
				FactoryJobs.unclaim(handle); // rolls back every key claimed so far (a remove(key, value) no-ops on a key never/differently claimed)
				return null;
			}
		}
		return handle;
	}

	/**
	 * The automated mode (a workflow bean, a scheduled run): waits until the resource is free, up to {@code pMaxWaitMs},
	 * then claims it. Throws when the wait times out or the thread is interrupted - an automated run never runs over a
	 * person's, nor fails just because one is running.
	 */
	public static Handle awaitClaim(final String pProjectId, final String pResource, final String pKind, final String pLauncher, final long pMaxWaitMs)
		throws OperationException
	{
		final long until = System.currentTimeMillis() + Math.max(0L, pMaxWaitMs);
		while (true)
		{
			final Handle handle = FactoryJobs.claim(pProjectId, pResource, pKind, pLauncher);
			if (handle != null)
			{
				return handle;
			}
			if (System.currentTimeMillis() >= until)
			{
				final Handle busy = FactoryJobs.live(pProjectId, pResource);
				throw busy == null ? EngineRefusal.coded("conflict.claimFailed", "Could not claim " + pResource + " for " + pKind + ".", pResource, pKind) : EngineRefusal.busy(busy);
			}
			try
			{
				Thread.sleep(2000L);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				throw EngineRefusal.coded("conflict.waitInterrupted", "Interrupted while waiting for " + pResource + " of the project to be free.", pResource);
			}
		}
	}

	/** The job holding {@code pResource} of this project, or null. */
	public static Handle live(final String pProjectId, final String pResource)
	{
		return pProjectId == null ? null : FactoryJobs.LIVE.get(FactoryJobs.key(pProjectId, pResource));
	}

	/**
	 * Every live (or queued) job of this project - each {@link Handle} at most once, even a whole-project one
	 * ({@link #claimAll}) stored under several {@link #LIVE} keys at once (volume-operations lot 8a, task 2).
	 */
	public static List<Handle> liveForProject(final String pProjectId)
	{
		final List<Handle> out = new ArrayList<Handle>();
		final java.util.Set<Handle> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Handle, Boolean>());
		for (final Handle handle : FactoryJobs.LIVE.values())
		{
			if (handle.projectId.equals(pProjectId) && seen.add(handle))
			{
				out.add(handle);
			}
		}
		return out;
	}

	/** Whether a job of {@code pKind} is live for this project. */
	public static boolean isLive(final String pProjectId, final String pKind)
	{
		for (final Handle handle : FactoryJobs.liveForProject(pProjectId))
		{
			if (handle.kind.equals(pKind))
			{
				return true;
			}
		}
		return false;
	}

	/** A claim that will not be submitted after all (the parent step could not be written...). */
	public static void release(final Handle pHandle)
	{
		if (pHandle != null)
		{
			FactoryJobs.unclaim(pHandle);
		}
	}

	/**
	 * The actual {@link #LIVE} removal for BOTH claim shapes (volume-operations lot 8a, task 2): an ordinary
	 * single-resource {@link Handle} is removed under its own {@code (project, resource)} key only; a
	 * whole-project one ({@link #claimAll}, {@code resource == }{@link #PROJECT}) is removed under {@link
	 * #PROJECT} AND every entry of {@link #RESOURCES} - the exact set {@link #claimAll} stored it under. {@code
	 * ConcurrentMap.remove(key, value)} is a no-op when that key does not currently map to this exact instance,
	 * so calling this on a partially-claimed handle (a rollback mid-{@link #claimAll}) is always safe.
	 */
	private static void unclaim(final Handle pHandle)
	{
		if (FactoryJobs.PROJECT.equals(pHandle.resource))
		{
			FactoryJobs.LIVE.remove(FactoryJobs.key(pHandle.projectId, FactoryJobs.PROJECT), pHandle);
			for (final String resource : FactoryJobs.RESOURCES)
			{
				FactoryJobs.LIVE.remove(FactoryJobs.key(pHandle.projectId, resource), pHandle);
			}
		}
		else
		{
			FactoryJobs.LIVE.remove(FactoryJobs.key(pHandle.projectId, pHandle.resource), pHandle);
		}
	}

	/** Records the parent PipelineStep the job reports into (for {@link #cancel} messages and the status route). */
	public static void attach(final Handle pHandle, final String pStepId)
	{
		pHandle.stepId = pStepId;
	}

	/** Declares a job kind as heavy (a matching run, a full load): {@link FactoryJobLimits#HEAVY_PROPERTY} bounds how many run at once. Called at module start. */
	static void registerHeavy(final String pKind)
	{
		FactoryJobs.HEAVY_KINDS.add(pKind);
	}

	static boolean isHeavy(final String pKind)
	{
		return FactoryJobs.HEAVY_KINDS.contains(pKind);
	}

	/** Whether the dispatcher may claim and mark RUNNING a job of {@code pKind} now: a pool thread is free and the heavy-job limit is not reached. */
	static boolean mayStart(final String pKind)
	{
		final boolean heavy = FactoryJobs.isHeavy(pKind);
		return FactoryJobLimits.mayStart(FactoryJobs.poolSize, FactoryJobs.inFlight.get(), heavy, heavy ? FactoryJobs.heavyInFlight() : 0, FactoryJobLimits.heavyLimit());
	}

	/** Heavy jobs holding a resource now (claimed, waiting for a thread or running). */
	private static int heavyInFlight()
	{
		int count = 0;
		for (final Handle handle : FactoryJobs.LIVE.values())
		{
			if (FactoryJobs.isHeavy(handle.kind))
			{
				count++;
			}
		}
		return count;
	}

	/** Runs {@code pBody} on the pool for a claimed project; the claim is released when the body ends, whatever happens. */
	public static void submit(final Handle pHandle, final Body pBody)
	{
		final ExecutorService pool = FactoryJobs.executor;
		final Session session = FactoryJobs.systemSession;
		if (pool == null || session == null)
		{
			FactoryJobs.release(pHandle);
			throw new IllegalStateException("The MDM Factory job engine is not started (module not initialised).");
		}
		final AtomicInteger counter = FactoryJobs.inFlight;
		counter.incrementAndGet();
		pHandle.submitted = true;
		pHandle.owner = null; // the claiming thread hands the work to the pool: from now on only the pool thread running the body owns the claim (audit F-15)
		final Runnable task = new Runnable()
		{
			@Override
			public void run()
			{
				pHandle.owner = Thread.currentThread();
				pHandle.started = true;
				// the time limit starts here, not at the claim: the job may have waited for a thread
				pHandle.touch();
				try
				{
					Actor.as(pHandle.launcher, "job " + pHandle.kind, new Actor.Work<Void, Exception>()
					{
						@Override
						public Void run() throws Exception
						{
							pBody.run(session, pHandle);
							return null;
						}
					});
				}
				catch (final Throwable ex) // deliberately broad: a job's own bug must be logged, and never kill the pool thread
				{
					LoggingCategory.getKernel().error("[mdm-factory] job " + pHandle.kind + " of project " + pHandle.projectId + " failed: " + ex, ex);
				}
				finally
				{
					// volume-operations lot 8a (2026-09-28, task 2): unclaim, not a single-key remove - a
					// whole-project Handle (claimAll) must free every one of its seven keys here, or a step
					// reset/project deletion job would leak the other six forever.
					FactoryJobs.unclaim(pHandle);
					counter.decrementAndGet();
				}
			}
		};
		try
		{
			pool.submit(task);
		}
		catch (final Throwable ex) // the pool refused it (shut down): the task will never run and never decrement
		{
			counter.decrementAndGet();
			pHandle.submitted = false;
			throw ex;
		}
	}

	/** Asks the live job(s) of this project to stop - only those of {@code pKind} when it is given. False when none. */
	public static boolean cancel(final String pProjectId, final String pKind, final String pBy)
	{
		boolean any = false;
		for (final Handle handle : FactoryJobs.liveForProject(pProjectId))
		{
			if (pKind == null || handle.kind.equals(pKind))
			{
				handle.cancel("stopped by " + (pBy == null ? "a user" : pBy));
				any = true;
			}
		}
		return any;
	}

	/** Watchdog: every job past its deadline is asked to stop. Package-private for the test. */
	static void enforceDeadlines(final long pNow)
	{
		for (final Handle handle : FactoryJobs.LIVE.values())
		{
			if (handle.submitted && !handle.started)
			{
				continue; // waiting for a pool thread: the time limit starts when the body does (audit F-37)
			}
			if (pNow > handle.deadline)
			{
				handle.cancel("stopped after the time limit of " + (FactoryJobs.maxMillis / 60000L) + " minutes (setting jobMaxMinutes)");
			}
		}
	}

	/** One sentence for a refused start: who runs what since when. */
	public static String busyMessage(final Handle pLive)
	{
		final long minutes = Math.max(0L, (System.currentTimeMillis() - pLive.createdAt) / 60000L);
		// volume-operations lot 8a (2026-09-28, task 2): a whole-project claim (claimAll) reads oddly as "the
		// project of this project" with the generic phrasing below - say it plainly instead.
		final String what = FactoryJobs.PROJECT.equals(pLive.resource) ? "the whole project" : "the " + pLive.resource.toLowerCase(Locale.ROOT) + " of this project";
		return "Another run is using " + what + " (" + pLive.kind + ", started by " + (pLive.launcher == null ? "?" : pLive.launcher) + " "
			+ (minutes == 0 ? "less than a minute" : minutes + " minute(s)") + " ago" + (pLive.started ? "" : ", waiting for a free worker")
			+ "). Wait for it to finish, or stop it.";
	}

	// ======================================================================================================== recovery

	/** Every PipelineStep row still RUNNING becomes INTERRUPTED. Returns how many. */
	static int interruptOrphans(final Session pSession, final Adaptation pFactory)
	{
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final List<String> ids = new ArrayList<String>();
		final RequestResult rows = steps.createRequestResult("./status='RUNNING'");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				ids.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		if (ids.isEmpty())
		{
			return 0;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (final String id : ids)
				{
					final Adaptation row = steps.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(id));
					if (row == null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(FactoryJobs.INTERRUPTED, FactoryPaths.PipelineStep.STATUS);
					value.setValue(new Date(), FactoryPaths.PipelineStep.ENDED_AT);
					final String previous = row.getString(FactoryPaths.PipelineStep.MESSAGE);
					value.setValue(com.on.ebx.factory.landing.TextLimits.truncate("Interrupted: the server restarted during this step."
						+ (previous == null || previous.isEmpty() ? "" : " Last message: " + previous), com.on.ebx.factory.landing.TextLimits.MESSAGE_MAX_LENGTH),
						FactoryPaths.PipelineStep.MESSAGE);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not mark orphan RUNNING steps INTERRUPTED: " + result.getExceptionFullMessage(Locale.ENGLISH));
			return 0;
		}
		return ids.size();
	}

	private static int positive(final Object pValue, final int pDefault)
	{
		return pValue instanceof Number && ((Number) pValue).intValue() > 0 ? ((Number) pValue).intValue() : pDefault;
	}
}
