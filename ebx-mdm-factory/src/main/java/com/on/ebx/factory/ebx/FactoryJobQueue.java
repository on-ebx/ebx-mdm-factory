package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.wizard.JobOutcome;
import com.on.ebx.factory.wizard.PartialFailures;
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
import com.orchestranetworks.service.UserReference;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The durable queue in front of {@link FactoryJobs} (lot C1, 2026-09-24 - the product owner: "une pile d'évènements plutôt qu'un
 * gros verrou"). Every request - a click in the wizard, a REST call, a workflow, a schedule - becomes a {@code FactoryJob}
 * row, QUEUED, with its kind, its parameters and who asked. A dispatcher starts the oldest QUEUED job whose resource of
 * the project is free (FIFO per resource; different resources of one project, and different projects, run side by side
 * up to {@code jobsMaxParallel}). Nothing is refused: a request for a busy resource simply waits its turn. A request
 * identical to one still QUEUED (same project, kind and parameters) is folded into it. On a restart QUEUED jobs resume;
 * a job that was RUNNING becomes INTERRUPTED. A QUEUED job can be cancelled outright, a RUNNING one is asked to stop.
 * <p>
 * The work of a kind is a {@link Handler} registered at module start ({@link #register}) - the {@code *Run} classes
 * register theirs. It runs on a pool thread with the system session, inside {@link Actor#as} for the requester.
 */
public final class FactoryJobQueue
{
	public static final String QUEUED = "QUEUED";
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";
	public static final String CANCELLED = "CANCELLED";
	/** UX lot D: some of the work was done and some failed; the items that failed are in the row's detail. See {@link PartialOutcome}. */
	public static final String DONE_WITH_FAILURES = PartialFailures.STATUS;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The work of one job kind. Returns the job's final message; throws to fail it. */
	public interface Handler
	{
		String run(Session pJobSession, FactoryJobs.Handle pHandle, Adaptation pFactory, String pProjectId, JsonNode pParams) throws Exception;
	}

	private static final class Registration
	{
		final String resource;
		final Handler handler;
		/** Volume-operations lot 8a (2026-09-28, task 2): true for a kind registered via {@link #registerWholeProject} - {@link #tryStart} then claims every resource of the project ({@link FactoryJobs#claimAll}), not just {@link #resource} (which reads {@link FactoryJobs#PROJECT} for display only). */
		final boolean wholeProject;
		/** Audit F-02: the Factory right the caller must hold to queue (or cancel) a job of this kind - checked by {@link #enqueue}. */
		final FactoryRight right;
		/** Audit F-02: the pipeline launcher role may queue it too ({@code FactoryAccess#mayOperatePipeline}). */
		volatile boolean pipelineOperator;
		/** Audit F-49: an identical request is folded into a RUNNING job too (a double click), not only into a QUEUED one. False for a kind whose request means "again, with what changed since" ({@link #doNotFoldIntoRunning}). */
		volatile boolean foldIntoRunning = true;

		Registration(final String pResource, final Handler pHandler, final boolean pWholeProject, final FactoryRight pRight)
		{
			this.resource = pResource;
			this.handler = pHandler;
			this.wholeProject = pWholeProject;
			this.right = pRight == null ? FactoryRight.ADMINISTRATOR : pRight;
		}
	}

	private static final Map<String, Registration> HANDLERS = new ConcurrentHashMap<String, Registration>();
	private static volatile Adaptation factory;
	private static volatile Session systemSession;
	private static volatile ScheduledExecutorService dispatcher;

	private FactoryJobQueue()
	{
	}

	/**
	 * Declares the work of a kind, the resource it writes and (audit F-02) the Factory right that queues it. Called at module
	 * start, before {@link #start}. The job itself then runs with the system session: the right is checked once, when it is queued.
	 */
	public static void register(final String pKind, final String pResource, final Handler pHandler, final FactoryRight pRight)
	{
		FactoryJobQueue.HANDLERS.put(pKind, new Registration(pResource, pHandler, false, pRight));
	}

	/** Audit F-02: a holder of the pipeline launcher role may queue this kind too (the intake's "Read now"). After its registration. */
	static void allowPipelineOperator(final String pKind)
	{
		final Registration registration = FactoryJobQueue.HANDLERS.get(pKind);
		if (registration == null)
		{
			throw new IllegalStateException("unknown job kind " + pKind);
		}
		registration.pipelineOperator = true;
	}

	/**
	 * Audit F-49: a request identical to a RUNNING job of the kind is normally the same click twice and is folded into it, like one identical to a QUEUED
	 * job. A kind whose request is raised BY A CHANGE (the refresh of the stored matching results after a decision: the decision made while a refresh runs
	 * is not in that refresh) must queue behind the running job instead. After its registration.
	 */
	static void doNotFoldIntoRunning(final String pKind)
	{
		final Registration registration = FactoryJobQueue.HANDLERS.get(pKind);
		if (registration == null)
		{
			throw new IllegalStateException("unknown job kind " + pKind);
		}
		registration.foldIntoRunning = false;
	}

	/** Audit F-49: whether a request for {@code pKind} folds into a RUNNING job (true unless {@link #doNotFoldIntoRunning}); false for an unknown kind. */
	static boolean foldsIntoRunning(final String pKind)
	{
		final Registration registration = pKind == null ? null : FactoryJobQueue.HANDLERS.get(pKind);
		return registration != null && registration.foldIntoRunning;
	}

	/** Audit F-02: the right that queues a kind; {@link FactoryRight#ADMINISTRATOR} for a kind nobody registered. */
	public static FactoryRight rightOf(final String pKind)
	{
		final Registration registration = pKind == null ? null : FactoryJobQueue.HANDLERS.get(pKind);
		return registration == null ? FactoryRight.ADMINISTRATOR : registration.right;
	}

	/** Audit F-02: may this session queue (or cancel) a job of this kind? The system user always may. */
	public static boolean mayQueue(final Session pSession, final Adaptation pFactory, final String pKind)
	{
		final Registration registration = pKind == null ? null : FactoryJobQueue.HANDLERS.get(pKind);
		if (registration == null)
		{
			return FactoryAccess.has(pSession, FactoryRight.ADMINISTRATOR);
		}
		return FactoryAccess.has(pSession, registration.right) || registration.pipelineOperator && FactoryAccess.mayOperatePipeline(pSession, pFactory);
	}

	/**
	 * F2-03: does the user a job row names ({@code requestedBy}) STILL hold the right its kind asks? The row is data, not proof: a restored archive or a
	 * future writer of the table can name anybody, and the job runs with the system session. The user of the module's own system session (queued by the
	 * engine itself) is trusted; an unknown kind or an empty/unknown user is refused.
	 */
	static boolean launcherMayRun(final Repository pRepository, final Adaptation pFactory, final String pKind, final String pRequestedBy)
	{
		final Registration registration = pKind == null ? null : FactoryJobQueue.HANDLERS.get(pKind);
		if (registration == null || pRequestedBy == null || pRequestedBy.trim().isEmpty())
		{
			return false;
		}
		final Session system = FactoryJobQueue.systemSession;
		if (system != null && system.getUserReference() != null && pRequestedBy.equals(system.getUserReference().getUserId()))
		{
			return true;
		}
		final UserReference user = UserReference.forUser(pRequestedBy);
		final boolean operates = registration.pipelineOperator && FactoryAccess.userMayOperatePipeline(pRepository, user, pFactory);
		return FactoryAccess.mayRun(FactoryAccess.grantedTo(pRepository, user), registration.right, registration.pipelineOperator, operates);
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 2): declares a kind that must hold the WHOLE project (every
	 * resource, {@link FactoryJobs#claimAll}) - a step reset or a project deletion, the exact work {@link
	 * #exclusively} used to run synchronously on the caller's own thread. The job row's own {@code resource}
	 * field reads {@link FactoryJobs#PROJECT}.
	 */
	public static void registerWholeProject(final String pKind, final Handler pHandler, final FactoryRight pRight)
	{
		FactoryJobQueue.HANDLERS.put(pKind, new Registration(FactoryJobs.PROJECT, pHandler, true, pRight));
	}

	/** The resource a kind writes, or null when the kind is unknown. */
	public static String resourceOf(final String pKind)
	{
		final Registration registration = FactoryJobQueue.HANDLERS.get(pKind);
		return registration == null ? null : registration.resource;
	}

	// ======================================================================================================= lifecycle

	/** Module start (after {@link FactoryJobs#start}): RUNNING jobs become INTERRUPTED, QUEUED ones resume, the dispatcher ticks every 2 s. */
	public static synchronized void start(final Session pSystemSession, final Adaptation pFactory)
	{
		FactoryJobQueue.factory = pFactory;
		FactoryJobQueue.systemSession = pSystemSession;
		FactoryJobQueue.maybeQueued = true; // rows the previous run left QUEUED resume now
		if (pFactory != null)
		{
			FactoryJobQueue.markAll(pFactory, FactoryJobQueue.RUNNING, FactoryJobs.INTERRUPTED, "Interrupted: the server restarted during this job.");
		}
		FactoryJobQueue.dispatcher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-job-dispatcher");
				thread.setDaemon(true);
				return thread;
			}
		});
		FactoryJobQueue.dispatcher.scheduleWithFixedDelay(new Runnable()
		{
			@Override
			public void run()
			{
				FactoryJobQueue.tick();
			}
		}, 2L, 2L, TimeUnit.SECONDS);
	}

	public static synchronized void stop()
	{
		if (FactoryJobQueue.dispatcher != null)
		{
			FactoryJobQueue.dispatcher.shutdownNow();
			FactoryJobQueue.dispatcher = null;
		}
	}

	// ========================================================================================================== enqueue

	/** What {@link #enqueue} did. */
	public static final class Queued
	{
		public final String jobId;
		/** True when an identical QUEUED request - or, audit F-49, a RUNNING one - absorbed this one. */
		public final boolean merged;
		/** 1 = next to start on its resource. */
		public final int position;

		Queued(final String pJobId, final boolean pMerged, final int pPosition)
		{
			this.jobId = pJobId;
			this.merged = pMerged;
			this.position = pPosition;
		}
	}

	/** Adds a request to the queue (or folds it into an identical QUEUED or RUNNING one), then nudges the dispatcher. */
	private static final Object ENQUEUE_LOCK = new Object();

	public static Queued enqueue(final Session pCaller, final Adaptation pFactory, final String pProjectId, final String pKind, final ObjectNode pParams)
		throws OperationException
	{
		final Registration registration = FactoryJobQueue.HANDLERS.get(pKind);
		if (registration == null)
		{
			throw EngineRefusal.coded("invalid.jobKind", "Unknown background job kind " + pKind + ".", pKind);
		}
		// audit F-02: the one place every background job is queued from - the caller's right is checked here, since the job then
		// runs with the system session for the user the row names (FactoryJobAccessRule keeps the rows out of anybody else's reach)
		if (!FactoryJobQueue.mayQueue(pCaller, pFactory, pKind))
		{
			throw EngineRefusal.accessDenied("queuing a " + pKind + " job", registration.right); // audit F-39: localized in the wizard
		}
		final String params = pParams == null ? "{}" : pParams.toString();
		final AdaptationTable jobs = pFactory.getTable(FactoryPaths.FACTORY_JOB);
		// the look-for-an-identical-job (QUEUED, or - audit F-49 - RUNNING) then insert must not interleave with another enqueue, or two identical
		// clicks both miss each other and queue twice. Its own lock, not the dispatcher's: tick() writes rows too, and a
		// caller already inside an EBX transaction must never wait for a lock the dispatcher holds while writing.
		final String[] id = new String[1];
		synchronized (FactoryJobQueue.ENQUEUE_LOCK)
		{
			final String existing = FactoryJobQueue.findIdentical(jobs, pProjectId, pKind, params, registration.foldIntoRunning, registration.resource);
			if (existing != null)
			{
				FactoryJobQueue.maybeQueued = true;
				return new Queued(existing, true, FactoryJobQueue.position(jobs, pProjectId, registration.resource, existing));
			}
			final Actor actor = Actor.current(pCaller);
			final ProcedureResult result = ProgrammaticService.createForSession(pCaller, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true); // the queue is the factory's own bookkeeping, like PipelineStep
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(jobs);
					value.setValue(pProjectId, FactoryPaths.FactoryJob.PROJECT);
					value.setValue(registration.resource, FactoryPaths.FactoryJob.RESOURCE);
					value.setValue(pKind, FactoryPaths.FactoryJob.KIND);
					value.setValue(params, FactoryPaths.FactoryJob.PARAMS);
					value.setValue(FactoryJobQueue.QUEUED, FactoryPaths.FactoryJob.STATUS);
					value.setValue(actor.userId, FactoryPaths.FactoryJob.REQUESTED_BY);
					value.setValue(actor.via, FactoryPaths.FactoryJob.VIA);
					value.setValue(new Date(), FactoryPaths.FactoryJob.REQUESTED_AT);
					id[0] = pContext.doCreateOccurrence(value, jobs).getOccurrencePrimaryKey().format();
				}
			});
			if (result.hasFailed())
			{
				throw EngineRefusal.queueFailed(pKind, "Could not queue the " + pKind + " job: " + result.getExceptionFullMessage(Locale.ENGLISH));
			}
		}
		FactoryJobQueue.maybeQueued = true;
		FactoryJobQueue.nudge();
		return new Queued(id[0], false, FactoryJobQueue.position(jobs, pProjectId, registration.resource, id[0]));
	}

	/** Cancels a QUEUED job outright, or asks a RUNNING one to stop. False when the job is already over. */
	public static boolean cancel(final Session pCaller, final Adaptation pFactory, final String pJobId) throws OperationException
	{
		final Adaptation row = pFactory.getTable(FactoryPaths.FACTORY_JOB).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pJobId));
		if (row == null)
		{
			return false;
		}
		final String status = row.getString(FactoryPaths.FactoryJob.STATUS);
		// audit F-02: whoever may queue a job of this kind may cancel it
		final String kind = row.getString(FactoryPaths.FactoryJob.KIND);
		if (!FactoryJobQueue.mayQueue(pCaller, pFactory, kind))
		{
			throw EngineRefusal.accessDenied("cancelling a " + kind + " job", FactoryJobQueue.rightOf(kind));
		}
		final String by = Actor.current(pCaller).userId;
		if (FactoryJobQueue.QUEUED.equals(status))
		{
			FactoryJobQueue.finish(pFactory, pJobId, FactoryJobQueue.CANCELLED, "Cancelled before it started, by " + by + ".");
			return true;
		}
		if (FactoryJobQueue.RUNNING.equals(status))
		{
			return FactoryJobs.cancel(row.getString(FactoryPaths.FactoryJob.PROJECT), row.getString(FactoryPaths.FactoryJob.KIND), by);
		}
		return false;
	}

	/** The latest job of {@code pKind} for the project (any status), or null - the {@code *Run} classes' status reads it. */
	public static Adaptation latest(final Adaptation pFactory, final String pProjectId, final String pKind)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.FACTORY_JOB).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./kind=" + CatalogReader.quote(pKind));
		try
		{
			Adaptation latest = null;
			long latestId = -1;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final long id = FactoryJobQueue.idOf(row);
				if (id > latestId)
				{
					latest = row;
					latestId = id;
				}
			}
			return latest;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Runs {@code pWork} on the caller's thread while holding EVERY resource of the project - for destructive work (a
	 * step reset, a project deletion) that must never overlap a job. Refused with a sentence when a job of the project
	 * is queued or running, or when a resource is held (a workflow bean waiting in {@link FactoryJobs#awaitClaim}
	 * included); while it runs, the dispatcher leaves the project's queued jobs where they are.
	 */
	public static <T> T exclusively(final Adaptation pFactory, final String pProjectId, final String pKind, final Session pSession,
		final Actor.Work<T, OperationException> pWork) throws OperationException
	{
		final List<Adaptation> pending = FactoryJobQueue.active(pFactory, pProjectId);
		if (!pending.isEmpty())
		{
			final StringBuilder kinds = new StringBuilder();
			final List<String> kindList = new ArrayList<String>();
			for (final Adaptation row : pending)
			{
				kinds.append(kinds.length() == 0 ? "" : ", ").append(row.getString(FactoryPaths.FactoryJob.KIND)).append(' ')
					.append(row.getString(FactoryPaths.FactoryJob.STATUS).toLowerCase(java.util.Locale.ROOT));
				kindList.add(row.getString(FactoryPaths.FactoryJob.KIND));
			}
			throw EngineRefusal.jobsPending(pending.size() + " job(s) of this project are waiting or running (" + kinds
				+ "). Wait for them to finish, or cancel them, then retry.", kindList);
		}
		final String launcher = Actor.current(pSession).userId;
		final List<FactoryJobs.Handle> held = new ArrayList<FactoryJobs.Handle>();
		try
		{
			for (final String resource : FactoryJobs.RESOURCES)
			{
				final FactoryJobs.Handle handle = FactoryJobs.claim(pProjectId, resource, pKind, launcher);
				if (handle == null)
				{
					final FactoryJobs.Handle live = FactoryJobs.live(pProjectId, resource);
					throw live == null ? EngineRefusal.justStarted() : EngineRefusal.busy(live);
				}
				held.add(handle);
			}
			return pWork.run();
		}
		finally
		{
			for (final FactoryJobs.Handle handle : held)
			{
				FactoryJobs.release(handle);
			}
			FactoryJobQueue.nudge();
		}
	}

	/** Every QUEUED or RUNNING job of the project, oldest first - the status route. */
	public static List<Adaptation> active(final Adaptation pFactory, final String pProjectId)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.FACTORY_JOB).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and (./status='QUEUED' or ./status='RUNNING')");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		out.sort(new java.util.Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pA, final Adaptation pB)
			{
				return Long.compare(FactoryJobQueue.idOf(pA), FactoryJobQueue.idOf(pB));
			}
		});
		return out;
	}

	// ======================================================================================================= dispatcher

	private static void nudge()
	{
		final ScheduledExecutorService current = FactoryJobQueue.dispatcher;
		if (current != null)
		{
			current.execute(new Runnable()
			{
				@Override
				public void run()
				{
					FactoryJobQueue.tick();
				}
			});
		}
	}

	/**
	 * Audit F-46: whether a QUEUED row may exist. The dispatcher used to query {@code FactoryJob} for QUEUED rows every 2 s, forever, on a table that keeps 90 days of
	 * history; it now reads only when this is set - by {@link #enqueue}, by {@link #start}, and by a read that found a QUEUED row (it may be waiting for a resource or a
	 * worker, so the next tick looks again, as before) - or when {@link #safetyPollMillis} have passed since the last read (a row written by another node or by hand).
	 */
	private static volatile boolean maybeQueued = true;
	private static volatile long lastReadAt;
	/** JVM property: seconds between two reads of the queue when nothing signalled a new job. Default 30; 0 reads at every tick (the behaviour before audit F-46); absent, negative or not a number = default. */
	static final String SAFETY_POLL_PROPERTY = "ebx.factory.jobs.safetyPollSeconds";
	static final long DEFAULT_SAFETY_POLL_SECONDS = 30L;

	/** Pure: whether the dispatcher reads the queue at {@code pNow}. A clock that went backwards reads as "long ago". */
	static boolean shouldRead(final boolean pMaybeQueued, final long pNow, final long pLastReadAt, final long pSafetyPollMillis)
	{
		return pMaybeQueued || pNow < pLastReadAt || pNow - pLastReadAt >= pSafetyPollMillis;
	}

	/** {@link #SAFETY_POLL_PROPERTY} in milliseconds. */
	static long safetyPollMillis(final String pPropertyValue)
	{
		if (pPropertyValue != null)
		{
			try
			{
				final long seconds = Long.parseLong(pPropertyValue.trim());
				if (seconds >= 0L)
				{
					return seconds * 1000L;
				}
			}
			catch (final NumberFormatException ex)
			{
				// falls through to the default
			}
		}
		return FactoryJobQueue.DEFAULT_SAFETY_POLL_SECONDS * 1000L;
	}

	/** Starts every QUEUED job whose resource is free, oldest first. Never throws. */
	static synchronized void tick()
	{
		final Adaptation currentFactory = FactoryJobQueue.factory;
		final Session session = FactoryJobQueue.systemSession;
		if (currentFactory == null || session == null)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (!FactoryJobQueue.shouldRead(FactoryJobQueue.maybeQueued, now, FactoryJobQueue.lastReadAt, FactoryJobQueue.safetyPollMillis(System.getProperty(FactoryJobQueue.SAFETY_POLL_PROPERTY))))
		{
			return;
		}
		// cleared BEFORE the read: an enqueue made while the rows are being read sets it again, and its row is looked at by the next tick
		FactoryJobQueue.maybeQueued = false;
		FactoryJobQueue.lastReadAt = now;
		try
		{
			final List<Adaptation> queued = new ArrayList<Adaptation>();
			final RequestResult rows = currentFactory.getTable(FactoryPaths.FACTORY_JOB).createRequestResult("./status='QUEUED'");
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					queued.add(row);
				}
			}
			finally
			{
				rows.close();
			}
			if (!queued.isEmpty())
			{
				FactoryJobQueue.maybeQueued = true; // some may stay QUEUED (resource busy, no free worker): look again at the next tick
			}
			queued.sort(new java.util.Comparator<Adaptation>()
			{
				@Override
				public int compare(final Adaptation pA, final Adaptation pB)
				{
					return Long.compare(FactoryJobQueue.idOf(pA), FactoryJobQueue.idOf(pB));
				}
			});
			for (final Adaptation row : queued)
			{
				try
				{
					FactoryJobQueue.tryStart(currentFactory, row);
				}
				catch (final Throwable ex) // one broken row must not keep the others from starting
				{
					LoggingCategory.getKernel().error("[mdm-factory] job dispatcher could not start job " + row.getOccurrencePrimaryKey().format() + ": " + ex, ex);
				}
			}
		}
		catch (final Throwable ex) // an Error (LinkageError after an upgrade...) must not end the periodic task: a scheduled task that throws is never run again
		{
			FactoryJobQueue.maybeQueued = true; // the read did not complete: try again at the next tick
			LoggingCategory.getKernel().error("[mdm-factory] job dispatcher tick failed: " + ex, ex);
		}
	}

	private static void tryStart(final Adaptation pFactory, final Adaptation pRow)
	{
		final String jobId = pRow.getOccurrencePrimaryKey().format();
		final String kind = pRow.getString(FactoryPaths.FactoryJob.KIND);
		final String projectId = pRow.getString(FactoryPaths.FactoryJob.PROJECT);
		final Registration registration = FactoryJobQueue.HANDLERS.get(kind);
		if (registration == null)
		{
			FactoryJobQueue.finish(pFactory, jobId, FactoryJobQueue.FAILED, "No handler registered for job kind " + kind + ".");
			return;
		}
		// F2-03: the row names who asked, nothing proves they still may (a restored archive, another writer of the table): the job runs with the system
		// session, so the authorisation is checked again here, with the right of the kind - the same table of rights as the queuing
		if (!FactoryJobQueue.launcherMayRun(pFactory.getHome().getRepository(), pFactory, kind, pRow.getString(FactoryPaths.FactoryJob.REQUESTED_BY)))
		{
			FactoryJobQueue.finish(pFactory, jobId, FactoryJobQueue.FAILED, FactoryAccess.refusal("running a " + kind + " job for " + pRow.getString(FactoryPaths.FactoryJob.REQUESTED_BY)
				+ " (launcher no longer allowed)", registration.right));
			return;
		}
		// audit F-37: only a job that can start NOW is claimed and marked RUNNING - with every pool thread busy it would read "running" in
		// the banner and have its time limit counted while it waits; it stays QUEUED and is looked at again on the next tick
		if (!FactoryJobs.mayStart(kind))
		{
			return;
		}
		// volume-operations lot 8a (2026-09-28, task 2): a whole-project kind claims every resource at once
		// (FactoryJobs.claimAll) - null the same way an ordinary single-resource claim() is null when busy, so
		// the "keeps its place in the queue, retried next tick" behaviour below is identical either way.
		final FactoryJobs.Handle handle = registration.wholeProject
			? FactoryJobs.claimAll(projectId, kind, pRow.getString(FactoryPaths.FactoryJob.REQUESTED_BY))
			: FactoryJobs.claim(projectId, registration.resource, kind, pRow.getString(FactoryPaths.FactoryJob.REQUESTED_BY));
		if (handle == null)
		{
			return; // the resource is busy: the job keeps its place in the queue
		}
		final JsonNode params;
		try
		{
			final String raw = pRow.getString(FactoryPaths.FactoryJob.PARAMS);
			params = FactoryJobQueue.MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
		}
		catch (final Exception ex)
		{
			FactoryJobs.release(handle);
			FactoryJobQueue.finish(pFactory, jobId, FactoryJobQueue.FAILED, "Unreadable parameters: " + ErrorText.of(ex));
			return;
		}
		if (!FactoryJobQueue.markRunning(pFactory, jobId))
		{
			FactoryJobs.release(handle);
			return; // cancelled meanwhile
		}
		try
		{
			FactoryJobs.submit(handle, new FactoryJobs.Body()
			{
				@Override
				public void run(final Session pJobSession, final FactoryJobs.Handle pHandle) throws Exception
				{
					final Outcome outcome;
					FactoryJobQueue.CURRENT_JOB.set(jobId);
					try
					{
						outcome = FactoryJobQueue.runHandler(registration.handler, pJobSession, pHandle, pFactory, projectId, params, jobId, kind);
					}
					finally
					{
						FactoryJobQueue.CURRENT_JOB.remove();
					}
					try
					{
						FactoryJobQueue.finish(pFactory, jobId, outcome.status, outcome.message, outcome.detail);
						if (pHandle.stepId() != null)
						{
							FactoryJobQueue.setStep(pFactory, jobId, pHandle.stepId());
						}
					}
					finally
					{
						FactoryJobQueue.nudge(); // the resource is free again: the next job may start
					}
					if (outcome.threadDeath != null)
					{
						throw outcome.threadDeath; // only after the row is closed: the thread was asked to die, let it
					}
				}
			});
		}
		catch (final Throwable ex) // the pool refused the job (not started, shut down): its row was just set RUNNING and nobody would ever end it
		{
			LoggingCategory.getKernel().error("[mdm-factory] job " + jobId + " (" + kind + ") could not be started: " + ex, ex);
			FactoryJobs.release(handle); // a no-op when submit already released it
			FactoryJobQueue.finish(pFactory, jobId, FactoryJobQueue.FAILED, "Could not be started: " + ex);
		}
	}

	/** How a handler ended: the status and message the job row takes, and a {@code ThreadDeath} to rethrow once the row is closed. */
	static final class Outcome
	{
		final String status;
		final String message;
		final ThreadDeath threadDeath;
		/** UX lot D: the JSON of the items that failed ({@link PartialFailures#toJson}), null unless the status is {@link FactoryJobQueue#DONE_WITH_FAILURES}. */
		final String detail;

		Outcome(final String pStatus, final String pMessage, final ThreadDeath pThreadDeath)
		{
			this(pStatus, pMessage, pThreadDeath, null);
		}

		Outcome(final String pStatus, final String pMessage, final ThreadDeath pThreadDeath, final String pDetail)
		{
			this.status = pStatus;
			this.message = pMessage;
			this.threadDeath = pThreadDeath;
			this.detail = pDetail;
		}
	}

	/**
	 * Runs a handler and turns whatever it does into an {@link Outcome} - never throws. An {@code Error} (a {@code
	 * LinkageError} or {@code VerifyError} after an upgrade, a {@code StackOverflowError}, an {@code OutOfMemoryError})
	 * ends the job FAILED like an exception does: left uncaught it would leave the row RUNNING for good, and the delete
	 * and reset guards refuse every project that has a RUNNING row.
	 */
	static Outcome runHandler(final Handler pHandler, final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory,
		final String pProjectId, final JsonNode pParams, final String pJobId, final String pKind)
	{
		try
		{
			String status = FactoryJobQueue.DONE;
			String message = pHandler.run(pJobSession, pHandle, pFactory, pProjectId, pParams);
			if (pHandle.cancelled())
			{
				status = FactoryJobQueue.CANCELLED;
				// the handler's own sentence already says it stopped; add the reason only when it said nothing
				message = message == null || message.isEmpty() ? com.on.ebx.factory.landing.RunTexts.stopped(pHandle.stopReason()).stored() : message;
			}
			final String stepFailure = FactoryJobQueue.DONE.equals(status) ? FactoryJobQueue.failedStepMessage(pFactory, pHandle, pKind) : null;
			if (stepFailure != null)
			{
				return new Outcome(FactoryJobQueue.FAILED, stepFailure, null);
			}
			if (FactoryJobQueue.DONE.equals(status))
			{
				// agentic framework lot (2026-10-30): the end of a matching run, a master or a staging load wakes the agents listening to it (queued, never run here)
				AgentRunJob.fire(pJobSession, pFactory, pProjectId, AgentRunJob.eventAfter(pKind));
			}
			return new Outcome(status, message, null);
		}
		catch (final PartialOutcome ex) // UX lot D: the work was done for some items and failed for others - a real status, not a success with a sentence
		{
			LoggingCategory.getKernel().info("[mdm-factory] job " + pJobId + " (" + pKind + ") ended with failures: " + ex.getMessage());
			return new Outcome(FactoryJobQueue.DONE_WITH_FAILURES, EngineRefusal.storedText(ex, TextLimits.MESSAGE_MAX_LENGTH), null, ex.detail);
		}
		catch (final ExpectedFailure ex) // a routine outcome (e.g. a source that needs a person): no stack trace
		{
			final String message = ErrorText.of(ex);
			LoggingCategory.getKernel().info("[mdm-factory] job " + pJobId + " (" + pKind + ") ended without success: " + message);
			return new Outcome(FactoryJobQueue.FAILED, EngineRefusal.storedText(ex, TextLimits.MESSAGE_MAX_LENGTH), null); // wave 10: with its code, for the screens
		}
		catch (final Exception ex) // deliberately broad: the job's own failure is its outcome
		{
			LoggingCategory.getKernel().error("[mdm-factory] job " + pJobId + " (" + pKind + ") failed: " + ex, ex);
			return new Outcome(FactoryJobQueue.FAILED, EngineRefusal.storedText(ex, TextLimits.MESSAGE_MAX_LENGTH), null); // wave 10: with its code, for the screens
		}
		catch (final Throwable ex) // an Error: same outcome, named by its class (an Error often has no message)
		{
			try
			{
				LoggingCategory.getKernel().error("[mdm-factory] job " + pJobId + " (" + pKind + ") died on " + ex, ex);
			}
			catch (final Throwable logFailure) // logging may itself fail under an OutOfMemoryError; the row must still be closed
			{
				ex.addSuppressed(logFailure);
			}
			return new Outcome(FactoryJobQueue.FAILED, ex.getClass().getName() + (ex.getMessage() == null || ex.getMessage().trim().isEmpty() ? "" : ": " + ex.getMessage()),
				ex instanceof ThreadDeath ? (ThreadDeath) ex : null);
		}
	}

	/**
	 * UX lot C2 (2026-10-14): for the kinds of {@link JobOutcome#stepGuarded}, the message of the job's own {@code PipelineStep} when that step ended FAILED
	 * although the handler returned normally - the job must not read "Done" next to a failed step (the landing run was the measured case; the staging, master and
	 * matching handlers already throw, this keeps it true if one of them stops doing so). Null otherwise, and on any read problem (the row keeps what the handler said).
	 */
	private static String failedStepMessage(final Adaptation pFactory, final FactoryJobs.Handle pHandle, final String pKind)
	{
		if (!JobOutcome.stepGuarded(pKind) || pHandle == null || pHandle.stepId() == null)
		{
			return null;
		}
		try
		{
			final Adaptation step = pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pHandle.stepId()));
			if (step == null || !FactoryJobQueue.FAILED.equals(step.getString(FactoryPaths.PipelineStep.STATUS)))
			{
				return null;
			}
			final String message = step.getString(FactoryPaths.PipelineStep.MESSAGE);
			return message == null || message.trim().isEmpty() ? "The step ended with a failure." : message;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	// ========================================================================================================= writes

	private static boolean markRunning(final Adaptation pFactory, final String pJobId)
	{
		final boolean[] done = new boolean[1];
		FactoryJobQueue.write(pFactory, pJobId, new RowWrite()
		{
			@Override
			public void apply(final Adaptation pRow, final ValueContextForUpdate pValue)
			{
				if (!FactoryJobQueue.QUEUED.equals(pRow.getString(FactoryPaths.FactoryJob.STATUS)))
				{
					return;
				}
				pValue.setValue(FactoryJobQueue.RUNNING, FactoryPaths.FactoryJob.STATUS);
				pValue.setValue(new Date(), FactoryPaths.FactoryJob.STARTED_AT);
				done[0] = true;
			}
		});
		return done[0];
	}

	/** The id of the job whose handler runs on this thread, or null - a handler that must record something on its own row ({@link #replaceParams}) asks for it. */
	private static final ThreadLocal<String> CURRENT_JOB = new ThreadLocal<String>();

	static String currentJobId()
	{
		return FactoryJobQueue.CURRENT_JOB.get();
	}

	/** Validation desk lot L3b: replaces the parameters of a job (the model's answers are added to them when it ends). Logged, never thrown. */
	static void replaceParams(final Adaptation pFactory, final String pJobId, final String pParamsJson)
	{
		FactoryJobQueue.write(pFactory, pJobId, new RowWrite()
		{
			@Override
			public void apply(final Adaptation pRow, final ValueContextForUpdate pValue)
			{
				pValue.setValue(pParamsJson, FactoryPaths.FactoryJob.PARAMS);
			}
		});
	}

	static void finish(final Adaptation pFactory, final String pJobId, final String pStatus, final String pMessage)
	{
		FactoryJobQueue.finish(pFactory, pJobId, pStatus, pMessage, null);
	}

	/** Same, with the JSON of the items that failed for a {@link #DONE_WITH_FAILURES} job (null leaves the detail empty). */
	static void finish(final Adaptation pFactory, final String pJobId, final String pStatus, final String pMessage, final String pDetail)
	{
		FactoryJobQueue.write(pFactory, pJobId, new RowWrite()
		{
			@Override
			public void apply(final Adaptation pRow, final ValueContextForUpdate pValue)
			{
				pValue.setValue(pStatus, FactoryPaths.FactoryJob.STATUS);
				pValue.setValue(new Date(), FactoryPaths.FactoryJob.ENDED_AT);
				pValue.setValue(pMessage == null ? null : TextLimits.truncate(pMessage, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.FactoryJob.MESSAGE);
				pValue.setValue(pDetail, FactoryPaths.FactoryJob.DETAIL);
			}
		});
	}

	private static void setStep(final Adaptation pFactory, final String pJobId, final String pStepId)
	{
		FactoryJobQueue.write(pFactory, pJobId, new RowWrite()
		{
			@Override
			public void apply(final Adaptation pRow, final ValueContextForUpdate pValue)
			{
				pValue.setValue(pStepId, FactoryPaths.FactoryJob.STEP_ID);
			}
		});
	}

	private interface RowWrite
	{
		void apply(Adaptation pRow, ValueContextForUpdate pValue);
	}

	/** One row, in its own procedure, with the system session (the queue is the factory's own bookkeeping). Logged, never thrown. */
	private static void write(final Adaptation pFactory, final String pJobId, final RowWrite pWrite)
	{
		final Session session = FactoryJobQueue.systemSession;
		if (session == null)
		{
			return;
		}
		final AdaptationTable jobs = pFactory.getTable(FactoryPaths.FACTORY_JOB);
		final ProcedureResult result = ProgrammaticService.createForSession(session, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final Adaptation row = jobs.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pJobId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				pWrite.apply(row, value);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not update job " + pJobId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static void markAll(final Adaptation pFactory, final String pFrom, final String pTo, final String pMessage)
	{
		final List<String> ids = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.FACTORY_JOB).createRequestResult("./status=" + CatalogReader.quote(pFrom));
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
		for (final String id : ids)
		{
			FactoryJobQueue.finish(pFactory, id, pTo, pMessage);
		}
	}

	// ======================================================================================================== helpers

	/** Audit F-49: whether a stored row of this status absorbs an identical request - QUEUED always, RUNNING when the kind folds into running jobs. */
	static boolean absorbs(final String pStatus, final boolean pFoldIntoRunning)
	{
		return FactoryJobQueue.QUEUED.equals(pStatus) || pFoldIntoRunning && FactoryJobQueue.RUNNING.equals(pStatus);
	}

	/** Whether the job of {@code pKind} that holds {@code pResource} of the project has been asked to stop (Stop pressed, time limit, shutdown). */
	static boolean stopping(final String pProjectId, final String pResource, final String pKind)
	{
		final FactoryJobs.Handle live = FactoryJobs.live(pProjectId, pResource);
		return live != null && pKind.equals(live.kind) && live.cancelled();
	}

	/** Whether a stored parameter string ({@code null} = none) is the request's own. */
	static boolean sameParams(final String pStored, final String pRequested)
	{
		return pRequested.equals(pStored == null ? "{}" : pStored);
	}

	/** The id of a QUEUED (or, audit F-49 and when {@code pFoldIntoRunning}, RUNNING) job of the project with the same kind and parameters, or null. A QUEUED one is preferred: it has not started, so it will see everything this request meant. */
	private static String findIdentical(final AdaptationTable pJobs, final String pProjectId, final String pKind, final String pParams, final boolean pFoldIntoRunning,
		final String pResource)
	{
		final RequestResult rows = pJobs.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./kind=" + CatalogReader.quote(pKind)
			+ " and (./status='QUEUED'" + (pFoldIntoRunning ? " or ./status='RUNNING'" : "") + ")");
		try
		{
			String running = null;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (FactoryJobQueue.sameParams(row.getString(FactoryPaths.FactoryJob.PARAMS), pParams))
				{
					final String status = row.getString(FactoryPaths.FactoryJob.STATUS);
					if (FactoryJobQueue.QUEUED.equals(status))
					{
						return row.getOccurrencePrimaryKey().format();
					}
					// a running job that was asked to stop is on its way out: a request made after the Stop is a new run, not a duplicate of it
					if (FactoryJobQueue.absorbs(status, pFoldIntoRunning) && !FactoryJobQueue.stopping(pProjectId, pResource, pKind))
					{
						running = row.getOccurrencePrimaryKey().format();
					}
				}
			}
			return running;
		}
		finally
		{
			rows.close();
		}
	}

	/** 1-based place of the job among the QUEUED/RUNNING jobs of the same resource of the project. */
	private static int position(final AdaptationTable pJobs, final String pProjectId, final String pResource, final String pJobId)
	{
		final long id = Long.parseLong(pJobId);
		int ahead = 0;
		final RequestResult rows = pJobs.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./resource=" + CatalogReader.quote(pResource)
			+ " and (./status='QUEUED' or ./status='RUNNING')");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (FactoryJobQueue.idOf(row) < id)
				{
					ahead++;
				}
			}
		}
		finally
		{
			rows.close();
		}
		return ahead + 1;
	}

	static long idOf(final Adaptation pRow)
	{
		final Object id = pRow.get(FactoryPaths.FactoryJob.ID);
		return id instanceof Number ? ((Number) id).longValue() : -1L;
	}

	/**
	 * UX lot D (2026-10-14): thrown by a {@link Handler} that has DONE its work for some items and failed for others (a landing run where 2 of 4 tables
	 * failed, a configuration that refused 2 objects while it configured the others). The job ends {@link #DONE_WITH_FAILURES} - neither the green DONE it
	 * used to store with a sentence nobody could act on, nor a FAILED that would read as "nothing happened" - with the message and the items that failed
	 * ({@link PartialFailures#toJson}) in its row. A checked exception on purpose, like {@link ExpectedFailure}: the handler's signature already allows it, and
	 * it cannot be forgotten on the way up. Nothing is rolled back: the work that succeeded stays.
	 */
	public static final class PartialOutcome extends Exception
	{
		private static final long serialVersionUID = 1L;
		final String detail;

		public PartialOutcome(final String pMessage, final PartialFailures.Failures pFailures)
		{
			super(pMessage);
			this.detail = PartialFailures.toJson(pFailures);
		}
	}

	/**
	 * Thrown by a {@link Handler} for a failure that is an expected outcome rather than a fault - the job ends
	 * {@code FAILED} with the message, logged on one line instead of an error with its stack trace.
	 */
	public static final class ExpectedFailure extends Exception
	{
		private static final long serialVersionUID = 1L;

		public ExpectedFailure(final String pMessage)
		{
			super(pMessage);
		}
	}
}
