package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.intake.IntakeChainOutcome;
import com.on.ebx.factory.intake.IntakeDecision;
import com.on.ebx.factory.intake.IntakePending;
import com.on.ebx.factory.intake.IntakeStatus;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.transfer.IncrementalTransfer;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.workflow.DataContext;
import com.orchestranetworks.workflow.ProcessInstance;
import com.orchestranetworks.workflow.ProcessInstanceHistory;
import com.orchestranetworks.workflow.ProcessInstanceKey;
import com.orchestranetworks.workflow.ProcessInstanceStep;
import com.orchestranetworks.workflow.PublishedProcessKey;
import com.orchestranetworks.workflow.WorkflowEngine;

/**
 * Automatic intake lot (docs/AUTOMATIC-INTAKE.md): the engine. ONE daemon thread for the module's life ({@code ebx-mdm-factory-intake}, a
 * {@link ScheduledExecutorService}, a tick every {@link #TICK_SECONDS} s, the shape of {@link DistributionDispatcher}), started in
 * {@code FactoryModuleRegistration#handleRepositoryStartup} with its own system-user session and stopped in {@code handleRepositoryShutdown}. A failure to start never
 * takes module startup down; one project's failure never stops the tick for the others; nothing ever kills the scheduler (every pass is caught).
 * <p>
 * <b>The queue is the landing itself</b> (a row at {@code _status} NEW or empty); the landing triggers ({@link IntakeTriggerInstaller}) only wake the engine up by
 * touching a signal row. Each tick, for every project with {@code intakeState = ON}: (1) close the chains whose workflow instance ended ({@link #followChains}); (2)
 * read the signal of each source (primary-key lookups, tiny) into the in-memory {@link IntakePending}; (3) ask the pure {@link IntakeDecision}; (4) only when a source
 * is due, count its NEW rows ({@code IncrementalTransfer.NEW_SCOPE_PREDICATE}) and launch ONE chain - an instance of {@code MdmFactoryPipeline} in incremental
 * mode, ONE at a time per project (the running chain is the project's RUNNING {@code IntakeChain} row, so a restart loses nothing).
 * <p>
 * <b>Robustness</b> (all in this class): (a) at startup, and after EVERY chain ends, one re-count of the NEW rows per ON project - rows that arrived during a chain,
 * or inside the trigger's 5-second throttle window, are never stranded; (b) a safety sweep every {@link #SWEEP_SECONDS} s re-counts the sources the signal showed
 * nothing for (reason {@code SWEEP}) - it covers lost triggers (a rebuilt landing model); (c) the in-memory state is rebuildable: nothing breaks if the JVM restarts
 * mid-chain - a RUNNING row whose instance is finished or gone is closed at the next tick (its result read from the workflow history, else from the
 * {@code PipelineStep} rows whose {@code via} names the chain).
 * <p>
 * <b>Not verified live</b> (nothing in this lot has run in EBX): the whole class. Verified by {@code javap} (6.3.0-rc2): {@code WorkflowEngine.isProcessInstanceRunning},
 * {@code getProcessInstance}, {@code getProcessInstanceHistory}, {@code getProcessInstanceKeysForDataContextVariable}, {@code ProcessInstance.isInError},
 * {@code ProcessInstanceHistory.getSteps/getTerminationType}, {@code ProcessInstanceStep.getDataContextAfter}, {@code DataContextReadOnly.isVariableDefined/getVariableString}.
 * Whether a completed instance stays readable (history cleaning settings) is NOT assumed: the {@code PipelineStep} fallback exists for that.
 */
public final class IntakeEngine
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The scheduler's period. */
	static final long TICK_SECONDS = 10L;
	/** The safety sweep: a count per source the signal showed nothing for. Measured cost of one count on a 230,722-row landing table: under 5 s - never more often. */
	static final long SWEEP_SECONDS = 300L;
	/** A RUNNING chain row that never got a workflow key (the JVM died between the two writes) is closed after this long. */
	private static final long ORPHAN_CHAIN_MS = 120_000L;

	/**
	 * How long a tick waits for {@code launchProcess} to return. The local Javadoc of {@code ProcessLauncher.launchProcess} does not say on which thread the first script
	 * task of the new instance runs (the module's own doc only says a script task bean "must not create any threads"): the launch therefore runs on its own thread
	 * ({@link #launchPool}), NOT on the tick thread, and the tick never waits longer than this for it.
	 */
	static final long LAUNCH_WAIT_SECONDS = 30L;

	private static volatile ScheduledExecutorService executor;
	/** The threads {@code launchProcess} runs on - never the tick thread (see {@link #LAUNCH_WAIT_SECONDS}). */
	private static volatile ExecutorService launchPool;
	/** Chains whose {@code launchProcess} call has not returned yet: their row has no workflow key yet, and that is not an orphan. */
	private static final java.util.Set<String> LAUNCHING = ConcurrentHashMap.newKeySet();
	private static volatile Repository repository;
	private static volatile Session systemSession;
	private static volatile LoggingCategory log;

	/** What the engine remembers per project, by project primary key. Rebuilt after a restart (a re-count). */
	static final class ProjectState
	{
		final Map<String, IntakePending> pending = new LinkedHashMap<String, IntakePending>();
		final Map<String, Long> signalMs = new LinkedHashMap<String, Long>();
		final Map<String, Long> counts = new LinkedHashMap<String, Long>();
		boolean needsRecount = true;
		boolean manual;
		boolean queuedAfterChain;
		long lastSweepMs;
		String reviewKey;
		String reviewWarning;
		String pipelineProblem;

		/** What the status path reads WITHOUT taking the lock the tick holds while it counts or launches: replaced by {@link #publish} (called under the lock). */
		volatile Snapshot snapshot = new Snapshot(new LinkedHashMap<String, IntakePending>(), new LinkedHashMap<String, Long>(), new LinkedHashMap<String, Long>(), false, null, null);

		IntakePending pendingOf(final String pSource)
		{
			final IntakePending p = this.pending.get(pSource);
			return p == null ? IntakePending.empty() : p;
		}

		/** Must be called while holding this object's lock. */
		void publish()
		{
			this.snapshot = new Snapshot(new LinkedHashMap<String, IntakePending>(this.pending), new LinkedHashMap<String, Long>(this.counts),
				new LinkedHashMap<String, Long>(this.signalMs), this.queuedAfterChain, this.reviewWarning, this.pipelineProblem);
		}

		IntakePending pendingIn(final String pSource)
		{
			final IntakePending p = this.snapshot.pending.get(pSource);
			return p == null ? IntakePending.empty() : p;
		}
	}

	/** An immutable copy of the engine's picture of one project, for the status. */
	static final class Snapshot
	{
		final Map<String, IntakePending> pending;
		final Map<String, Long> counts;
		final Map<String, Long> signals;
		final boolean queuedAfterChain;
		final String reviewWarning;
		final String pipelineProblem;

		Snapshot(final Map<String, IntakePending> pPending, final Map<String, Long> pCounts, final Map<String, Long> pSignals, final boolean pQueued,
			final String pReviewWarning, final String pPipelineProblem)
		{
			this.pending = pPending;
			this.counts = pCounts;
			this.signals = pSignals;
			this.queuedAfterChain = pQueued;
			this.reviewWarning = pReviewWarning;
			this.pipelineProblem = pPipelineProblem;
		}
	}

	private static final ConcurrentHashMap<String, ProjectState> STATES = new ConcurrentHashMap<String, ProjectState>();

	private IntakeEngine()
	{
	}

	// =============================================================================================================== lifecycle

	/** Starts the engine if it is not running. Idempotent. Never throws. */
	public static synchronized void start(final Repository pRepository, final Session pSystemSession, final LoggingCategory pLog)
	{
		if (IntakeEngine.executor != null)
		{
			return;
		}
		IntakeEngine.repository = pRepository;
		IntakeEngine.systemSession = pSystemSession;
		IntakeEngine.log = pLog;
		final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-intake");
				thread.setDaemon(true);
				return thread;
			}
		});
		scheduler.scheduleWithFixedDelay(new Runnable()
		{
			@Override
			public void run()
			{
				IntakeEngine.tickSafely();
			}
		}, IntakeEngine.TICK_SECONDS, IntakeEngine.TICK_SECONDS, TimeUnit.SECONDS);
		IntakeEngine.launchPool = Executors.newCachedThreadPool(new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-intake-launch");
				thread.setDaemon(true);
				return thread;
			}
		});
		IntakeEngine.executor = scheduler;
	}

	/** Stops the engine; called from {@code FactoryModuleRegistration#handleRepositoryShutdown} so the thread never outlives a stopped webapp class loader. Idempotent. */
	public static synchronized void stop()
	{
		if (IntakeEngine.executor != null)
		{
			IntakeEngine.executor.shutdownNow();
			IntakeEngine.executor = null;
		}
		if (IntakeEngine.launchPool != null)
		{
			IntakeEngine.launchPool.shutdownNow();
			IntakeEngine.launchPool = null;
		}
		IntakeEngine.LAUNCHING.clear();
		IntakeEngine.STATES.clear();
	}

	/** True while the engine thread exists in this JVM. */
	public static boolean isRunning()
	{
		return IntakeEngine.executor != null;
	}

	/** Asks for a pass right now (on the engine thread: passes never overlap). A no-op when the engine is not running. */
	static void requestTick()
	{
		final ScheduledExecutorService scheduler = IntakeEngine.executor;
		if (scheduler == null)
		{
			return;
		}
		try
		{
			scheduler.execute(new Runnable()
			{
				@Override
				public void run()
				{
					IntakeEngine.tickSafely();
				}
			});
		}
		catch (final RuntimeException ex)
		{
			// rejected while shutting down: nothing to do
		}
	}

	private static void tickSafely()
	{
		try
		{
			IntakeEngine.tick();
		}
		catch (final Throwable ex)
		{
			// nothing may kill the scheduler: a thrown Throwable from a task would cancel every later run
			if (IntakeEngine.log != null)
			{
				IntakeEngine.log.error("[mdm-factory] intake engine pass failed: " + ex, ex);
			}
		}
	}

	// =============================================================================================================== state access (facade, landing hook)

	static ProjectState state(final String pProjectId)
	{
		ProjectState state = IntakeEngine.STATES.get(pProjectId);
		if (state == null)
		{
			state = new ProjectState();
			final ProjectState previous = IntakeEngine.STATES.putIfAbsent(pProjectId, state);
			if (previous != null)
			{
				state = previous;
			}
		}
		return state;
	}

	/** The facade switched a project on or resumed it: rebuild its picture with a re-count at the next pass, and look now. */
	static void markRecount(final String pProjectId)
	{
		final ProjectState state = IntakeEngine.state(pProjectId);
		synchronized (state)
		{
			state.needsRecount = true;
			state.pipelineProblem = null;
			state.publish();
		}
		IntakeEngine.requestTick();
	}

	/** Switched off: forget everything. */
	static void forget(final String pProjectId)
	{
		IntakeEngine.STATES.remove(pProjectId);
	}

	/**
	 * "Process now": one chain is started at the next pass even when nothing is due. While a chain runs it is queued for right after it. Returns true when queued
	 * behind a running chain.
	 */
	static boolean processNow(final String pProjectId, final boolean pChainRunning)
	{
		final ProjectState state = IntakeEngine.state(pProjectId);
		synchronized (state)
		{
			if (pChainRunning)
			{
				state.queuedAfterChain = true;
				state.publish();
				return true;
			}
			state.manual = true;
		}
		IntakeEngine.requestTick();
		return false;
	}

	/** The application (or the Factory's own read) says a batch is complete: the source is due at once. A project that is not ON is not touched. */
	static boolean batchComplete(final String pProjectId, final String pSourceSystem)
	{
		final ProjectState state = IntakeEngine.state(pProjectId);
		synchronized (state)
		{
			state.pending.put(pSourceSystem, state.pendingOf(pSourceSystem).withBatchComplete(System.currentTimeMillis()));
			state.publish();
		}
		IntakeEngine.requestTick();
		return true;
	}

	/**
	 * Called by {@code LandingFlows.load} when a source's landing load ended without a failure: the Factory itself ran the read, so it knows when it ended and the
	 * chain starts right after it. Harmless when the intake is not ON (the state is only consulted by the tick, which skips such projects) and never throws.
	 */
	public static void sourceLoaded(final String pProjectId, final String pSourceSystem)
	{
		try
		{
			if (IntakeEngine.executor == null || pProjectId == null || pSourceSystem == null)
			{
				return;
			}
			final Adaptation factory = FactoryInstaller.dataset(IntakeEngine.repository);
			final Adaptation project = factory == null ? null
				: factory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(pProjectId));
			if (project == null || !FactoryPaths.Project.INTAKE_ON.equals(IntakeStore.state(project)))
			{
				return;
			}
			IntakeEngine.batchComplete(pProjectId, pSourceSystem);
		}
		catch (final RuntimeException ex)
		{
			// a notification: never fails the load that sent it
		}
	}

	// =============================================================================================================== the pass

	private static void tick()
	{
		final Repository repo = IntakeEngine.repository;
		final Session session = IntakeEngine.systemSession;
		final Adaptation factory = repo == null ? null : FactoryInstaller.dataset(repo);
		if (factory == null || session == null)
		{
			return;
		}
		try
		{
			IntakeEngine.followChains(session, factory);
		}
		catch (final RuntimeException ex)
		{
			IntakeEngine.log.error("[mdm-factory] intake: following the running chains failed: " + ex, ex);
		}
		final List<Adaptation> on = IntakeStore.projectsWithState(factory, FactoryPaths.Project.INTAKE_ON);
		final List<String> onIds = new ArrayList<String>();
		for (final Adaptation project : on)
		{
			onIds.add(project.getOccurrencePrimaryKey().format());
			try
			{
				IntakeEngine.tickProject(session, factory, project);
			}
			catch (final RuntimeException ex)
			{
				IntakeEngine.log.error("[mdm-factory] intake: project " + project.getOccurrencePrimaryKey().format() + " failed: " + ex, ex);
			}
			catch (final OperationException ex)
			{
				IntakeEngine.log.error("[mdm-factory] intake: project " + project.getOccurrencePrimaryKey().format() + " failed: " + ex.getMessage());
			}
		}
		for (final String id : new ArrayList<String>(IntakeEngine.STATES.keySet()))
		{
			if (!onIds.contains(id))
			{
				IntakeEngine.STATES.remove(id);
			}
		}
	}

	private static void tickProject(final Session pSession, final Adaptation pFactory, final Adaptation pProject) throws OperationException
	{
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final ProjectState state = IntakeEngine.state(projectId);
		final List<IntakeSources.Info> sources = IntakeSources.read(pFactory, pProject);
		final AdaptationHome landingHome = IntakeSources.landingHome(pFactory, pProject);
		final Adaptation signalDataset = landingHome == null ? null : landingHome.findAdaptationOrNull(com.onwbp.adaptation.AdaptationName.forName(FactoryPaths.INTAKE_SIGNAL_DATASET));
		final boolean running = !IntakeStore.runningChains(pFactory, projectId).isEmpty();
		final long now = System.currentTimeMillis();
		IntakeDecision.Result toLaunch = null;

		synchronized (state)
		{
			// (2) the signal of each source
			for (final IntakeSources.Info source : sources)
			{
				Long signal = null;
				if (signalDataset != null)
				{
					final Date last = IntakeSignalInstaller.lastRowAt(signalDataset, source.sourceSystem);
					signal = last == null ? null : Long.valueOf(last.getTime());
				}
				state.signalMs.put(source.sourceSystem, signal);
				state.pending.put(source.sourceSystem, state.pendingOf(source.sourceSystem).observe(signal, now));
			}
			state.publish();
			if (running)
			{
				// one chain at a time: rows that arrive meanwhile wait for the next one; the re-count after this chain is what finds the stragglers
				return;
			}
			// (a) startup / after a chain: re-count once
			if (state.needsRecount)
			{
				state.needsRecount = false;
				state.lastSweepMs = now;
				for (final IntakeSources.Info source : sources)
				{
					IntakeEngine.applyCount(state, source, landingHome, now, IntakePending.Origin.RECOUNT);
				}
				IntakeEngine.refreshHeldBack(pFactory, projectId);
				state.publish();
			}
			else if (now - state.lastSweepMs >= IntakeEngine.SWEEP_SECONDS * 1000L)
			{
				// (b) the slow safety sweep: only the sources the signal showed nothing for
				state.lastSweepMs = now;
				for (final IntakeSources.Info source : sources)
				{
					if (!state.pendingOf(source.sourceSystem).isPending())
					{
						IntakeEngine.applyCount(state, source, landingHome, now, IntakePending.Origin.SWEEP);
					}
				}
			}

			// (3) the decision; the count is asked only for a source that is due
			final List<IntakeDecision.SourceInput> inputs = new ArrayList<IntakeDecision.SourceInput>();
			final Map<String, IntakeSources.Info> byName = new LinkedHashMap<String, IntakeSources.Info>();
			for (final IntakeSources.Info source : sources)
			{
				inputs.add(new IntakeDecision.SourceInput(source.sourceSystem, state.pendingOf(source.sourceSystem), source.settings));
				byName.put(source.sourceSystem, source);
			}
			final boolean manual = state.manual;
			final IntakeDecision.Result decision = IntakeDecision.decide(now, FactoryPaths.Project.INTAKE_ON, false, manual, inputs, new IntakeDecision.RowCounter()
			{
				@Override
				public long count(final String pSourceSystem)
				{
					final long n = IntakeEngine.countNew(landingHome, byName.get(pSourceSystem));
					state.counts.put(pSourceSystem, Long.valueOf(n));
					return n;
				}
			});
			state.manual = false;
			for (final String cleared : decision.clearedSources)
			{
				state.pending.put(cleared, state.pendingOf(cleared).counted(0L, now, IntakePending.Origin.SIGNAL).launched(state.signalMs.get(cleared)));
			}
			state.publish();
			toLaunch = decision.action == IntakeDecision.Action.LAUNCH ? decision : null;
		}
		// the launch itself is NOT done under the project lock (it can take as long as the workflow engine takes to return)
		if (toLaunch != null)
		{
			IntakeEngine.launchChain(pSession, pFactory, pProject, state, toLaunch);
		}
	}

	/** Counts the NEW rows of one source and moves its pending state accordingly. */
	private static void applyCount(final ProjectState pState, final IntakeSources.Info pSource, final AdaptationHome pLandingHome, final long pNow, final IntakePending.Origin pOrigin)
	{
		final long count = IntakeEngine.countNew(pLandingHome, pSource);
		pState.counts.put(pSource.sourceSystem, Long.valueOf(count));
		IntakePending next = pState.pendingOf(pSource.sourceSystem).counted(count, pNow, pOrigin);
		if (count <= 0L)
		{
			next = next.launched(pState.signalMs.get(pSource.sourceSystem));
		}
		pState.pending.put(pSource.sourceSystem, next);
	}

	/**
	 * The rows at NEW (or empty) {@code _status} of all ticked landing tables of a source: a predicate count with {@code IncrementalTransfer.NEW_SCOPE_PREDICATE}, the very
	 * predicate the staging load reads with. A missing dataset or table counts 0. Costs a scan: only called when due, after a chain, at startup and in the sweep.
	 */
	static long countNew(final AdaptationHome pLandingHome, final IntakeSources.Info pSource)
	{
		if (pLandingHome == null || pSource == null)
		{
			return 0L;
		}
		final Adaptation dataset = IntakeSources.landingDataset(pLandingHome, pSource);
		if (dataset == null)
		{
			return 0L;
		}
		long total = 0L;
		for (final String tableName : pSource.tableNames)
		{
			try
			{
				final AdaptationTable table = dataset.getTable(Path.parse("/root/" + tableName));
				if (table == null)
				{
					continue;
				}
				final RequestResult rows = table.createRequestResult(IncrementalTransfer.NEW_SCOPE_PREDICATE);
				try
				{
					total += rows.getSize();
				}
				finally
				{
					rows.close();
				}
			}
			catch (final RuntimeException ex)
			{
				IntakeEngine.log.warn("[mdm-factory] intake: could not count the new rows of " + pSource.sourceSystem + "/" + tableName + ": " + ex);
			}
		}
		return total;
	}

	private static void refreshHeldBack(final Adaptation pFactory, final String pProjectId)
	{
		IntakeReadinessReader.heldBack(pFactory, pProjectId, true);
	}

	// =============================================================================================================== launching a chain

	/**
	 * Launches one chain. Called WITHOUT the project lock and the launch itself runs on {@link #launchPool}: nothing in the local documentation says whether the first
	 * script task of the new instance (here the staging load: the pipeline starts with {@code loadLanding=false}) runs in the launching thread before
	 * {@code launchProcess} returns, so the engine does not depend on it - if it did, only the launch thread would be busy for the whole staging load, the tick thread waits at
	 * most {@link #LAUNCH_WAIT_SECONDS} and goes on (other projects, closing chains, the status), and the chain row (RUNNING, id = token) already exists, so the project counts as
	 * having a chain running. The chain itself never needs the tick thread: its gate reads the {@code IntakeChain} row and the project, nothing of the engine's memory. A row
	 * whose launch has not returned is in {@link #LAUNCHING} and is not closed as an orphan.
	 */
	private static void launchChain(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final ProjectState pState, final IntakeDecision.Result pDecision)
	{
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final String code = pProject.getString(FactoryPaths.Project.CODE);
		final Repository repo = IntakeEngine.repository;
		final ExecutorService pool = IntakeEngine.launchPool;
		if (pool == null)
		{
			return;
		}
		if (!PipelineWorkflowLauncher.isPublished(repo))
		{
			IntakeEngine.setProblem(pState, com.on.ebx.factory.intake.IntakeStatus.Pipeline.NOT_PUBLISHED); // audit F2-108: a code the screen words, not an English sentence
			return;
		}
		if (!PipelineWorkflowLauncher.declaresIntake(repo, pSession))
		{
			IntakeEngine.setProblem(pState, com.on.ebx.factory.intake.IntakeStatus.Pipeline.PREDATES_INTAKE);
			return;
		}
		IntakeEngine.setProblem(pState, null);
		final String chainId = UUID.randomUUID().toString();
		try
		{
			// the row FIRST: it is the token the workflow carries (workflow.IntakeGate) and the proof a chain is running
			IntakeStore.createChain(pSession, pFactory, chainId, projectId, pDecision.reason, pDecision.launchSources, pDecision.rowsWaiting,
				IntakeStore.text(pProject, FactoryPaths.Project.INTAKE_ENABLED_BY)); // audit F-30: the chain runs FOR who switched the intake on
		}
		catch (final OperationException ex)
		{
			IntakeEngine.log.error("[mdm-factory] intake: could not record a chain for " + code + ": " + ex.getMessage());
			return;
		}
		final Integer percent = IntakeStore.stopRejectPercent(pProject);
		IntakeEngine.LAUNCHING.add(chainId);
		final Future<?> launch;
		try
		{
			launch = pool.submit(new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						final String key = PipelineWorkflowLauncher.launchIntake(repo, pSession, code, chainId, percent);
						final Map<Path, Object> values = new LinkedHashMap<Path, Object>();
						values.put(FactoryPaths.IntakeChain.WORKFLOW_KEY, key);
						IntakeStore.writeChain(pSession, pFactory, chainId, values);
					}
					catch (final OperationException ex)
					{
						IntakeEngine.failLaunch(pSession, pFactory, pProject, chainId, ex.getMessage());
					}
					catch (final RuntimeException ex)
					{
						IntakeEngine.failLaunch(pSession, pFactory, pProject, chainId, ex.toString());
					}
					finally
					{
						IntakeEngine.LAUNCHING.remove(chainId);
					}
				}
			});
		}
		catch (final RuntimeException ex)
		{
			IntakeEngine.LAUNCHING.remove(chainId);
			IntakeEngine.failLaunch(pSession, pFactory, pProject, chainId, ex.toString());
			return;
		}
		// what the chain will read is consumed from now on (a failed launch pauses the intake, and a resume re-counts)
		synchronized (pState)
		{
			for (final String source : pDecision.launchSources)
			{
				pState.pending.put(source, pState.pendingOf(source).launched(pState.signalMs.get(source)));
			}
			pState.publish();
		}
		IntakeEngine.log.info("[mdm-factory] intake: chain " + chainId + " launching for " + code + " (" + pDecision.reason + ", " + pDecision.rowsWaiting + " row(s) from "
			+ pDecision.launchSources + ")");
		try
		{
			launch.get(IntakeEngine.LAUNCH_WAIT_SECONDS, TimeUnit.SECONDS);
		}
		catch (final TimeoutException ex)
		{
			IntakeEngine.log.warn("[mdm-factory] intake: launchProcess of chain " + chainId + " has not returned after " + IntakeEngine.LAUNCH_WAIT_SECONDS
				+ " s (the workflow engine may be running the first steps in the launching thread); the tick goes on");
		}
		catch (final InterruptedException ex)
		{
			Thread.currentThread().interrupt();
		}
		catch (final java.util.concurrent.ExecutionException ex)
		{
			// the runnable catches what it can; anything else is logged by the scheduler's own handler
			IntakeEngine.log.error("[mdm-factory] intake: launch of chain " + chainId + " failed: " + ex.getCause());
		}
	}

	private static void setProblem(final ProjectState pState, final String pProblem)
	{
		synchronized (pState)
		{
			pState.pipelineProblem = pProblem;
			pState.publish();
		}
	}

	/** The workflow could not be launched: the chain is FAILED and the intake pauses (otherwise every tick would try again and write a chain row). */
	private static void failLaunch(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final String pChainId, final String pMessage)
	{
		final Map<Path, Object> values = new LinkedHashMap<Path, Object>();
		values.put(FactoryPaths.IntakeChain.STATUS, FactoryPaths.IntakeChain.STATUS_FAILED);
		values.put(FactoryPaths.IntakeChain.ENDED_AT, new Date());
		values.put(FactoryPaths.IntakeChain.MESSAGE, TextLimits.truncate("The workflow could not be launched: " + pMessage, TextLimits.MESSAGE_MAX_LENGTH));
		IntakeStore.writeChain(pSession, pFactory, pChainId, values);
		try
		{
			IntakeStore.setState(pSession, pFactory, pProject, FactoryPaths.Project.INTAKE_PAUSED, IntakeChainOutcome.launchFailedReason(pMessage));
		}
		catch (final OperationException ex)
		{
			IntakeEngine.log.error("[mdm-factory] intake: could not pause " + pProject.getOccurrencePrimaryKey().format() + ": " + ex.getMessage());
		}
	}

	// =============================================================================================================== following the chains

	/** What a finished chain's workflow instance left. */
	static final class ChainResult
	{
		boolean failed;
		String failureMessage;
		/** What the workflow's {@code stagingStopped} said: also true for a total reject with no threshold - NOT the status decision, see {@code IntakeChainOutcome#stoppedByThreshold}. */
		boolean stopped;
		/** A total reject seen only through the step's own sentence (history gone, counts unknown). */
		boolean totalRejectSeen;
		long read;
		long written;
		long unchanged;
		long rejected;
		/** Any evidence at all of what the staging load did. */
		boolean known;
	}

	private static void followChains(final Session pSession, final Adaptation pFactory)
	{
		for (final Adaptation chain : IntakeStore.runningChains(pFactory, null))
		{
			try
			{
				IntakeEngine.followChain(pSession, pFactory, chain);
			}
			catch (final RuntimeException ex)
			{
				IntakeEngine.log.error("[mdm-factory] intake: following chain " + chain.getOccurrencePrimaryKey().format() + " failed: " + ex, ex);
			}
		}
	}

	private static void followChain(final Session pSession, final Adaptation pFactory, final Adaptation pChain)
	{
		final String chainId = pChain.getOccurrencePrimaryKey().format();
		final String projectId = pChain.getString(FactoryPaths.IntakeChain.PROJECT);
		final Adaptation project = projectId == null ? null : pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(projectId));
		final String key = pChain.getString(FactoryPaths.IntakeChain.WORKFLOW_KEY);
		final WorkflowEngine engine = WorkflowEngine.getFromRepository(IntakeEngine.repository, pSession);
		ChainResult result = null;
		if (key == null || key.trim().isEmpty())
		{
			if (IntakeEngine.LAUNCHING.contains(chainId))
			{
				return; // launchProcess has not returned yet
			}
			if (System.currentTimeMillis() - IntakeStore.time(pChain, FactoryPaths.IntakeChain.STARTED_AT) < IntakeEngine.ORPHAN_CHAIN_MS)
			{
				return; // the launch is probably still in progress
			}
			result = new ChainResult();
			result.failed = true;
			result.failureMessage = "the workflow instance was never recorded (the server stopped while launching it)";
		}
		else
		{
			final ProcessInstanceKey instanceKey = ProcessInstanceKey.parse(key.trim());
			boolean running;
			try
			{
				running = engine.isProcessInstanceRunning(instanceKey);
			}
			catch (final RuntimeException ex)
			{
				running = false; // unknown to the engine: gone
			}
			if (running)
			{
				if (!IntakeEngine.inError(engine, instanceKey))
				{
					return;
				}
				result = new ChainResult();
				result.failed = true;
				result.failureMessage = "the workflow instance " + key + " is in error (see the workflow log); replay or terminate it, then resume the intake";
			}
			else
			{
				result = IntakeEngine.readResult(engine, pFactory, projectId, chainId, instanceKey);
			}
		}
		IntakeEngine.closeChain(pSession, pFactory, pChain, project, result);
	}

	private static boolean inError(final WorkflowEngine pEngine, final ProcessInstanceKey pKey)
	{
		try
		{
			final ProcessInstance instance = pEngine.getProcessInstance(pKey);
			return instance != null && instance.isInError();
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/**
	 * The outcome of an ended instance: the data-context variables of its history (the output variables the pipeline maps: {@code stagingWritten}, {@code stagingRejected},
	 * {@code stagingStopped}, {@code stagingResult}), else the {@code PipelineStep} rows whose {@code via} names the chain. A chain with no evidence at all, a forced
	 * termination, or a step that FAILED is a failure.
	 */
	static ChainResult readResult(final WorkflowEngine pEngine, final Adaptation pFactory, final String pProjectId, final String pChainId, final ProcessInstanceKey pKey)
	{
		final ChainResult result = new ChainResult();
		final Map<String, String> variables = new LinkedHashMap<String, String>();
		try
		{
			final ProcessInstanceHistory history = pEngine.getProcessInstanceHistory(pKey);
			for (final ProcessInstanceStep step : history.getSteps())
			{
				try
				{
					final DataContext after = step.getDataContextAfter();
					if (after == null)
					{
						continue;
					}
					for (final String name : new String[] { "stagingWritten", "stagingRejected", "stagingStopped", "stagingResult" })
					{
						if (after.isVariableDefined(name))
						{
							final String value = after.getVariableString(name);
							if (value != null && !value.trim().isEmpty())
							{
								variables.put(name, value);
							}
						}
					}
				}
				catch (final RuntimeException ex)
				{
					// a step whose context cannot be read: the others still count
				}
			}
			if (history.getTerminationType() == ProcessInstanceHistory.TerminationType.TERMINATED_FORCED)
			{
				result.failed = true;
				result.failureMessage = "the workflow instance was terminated by hand";
			}
		}
		catch (final OperationException ex)
		{
			// the history is gone (cleaned): the PipelineStep rows below are the fallback
		}
		catch (final RuntimeException ex)
		{
			// same
		}
		if (!variables.isEmpty())
		{
			result.known = true;
			result.rejected = IntakeEngine.longOf(variables.get("stagingRejected"));
			result.written = IntakeEngine.longOf(variables.get("stagingWritten"));
			result.stopped = "true".equalsIgnoreCase(variables.get("stagingStopped"));
			final JsonNode json = IntakeEngine.json(variables.get("stagingResult"));
			if (json != null)
			{
				result.read = json.path("read").asLong(0L);
				result.unchanged = json.path("unchanged").asLong(0L);
			}
		}
		IntakeEngine.readPipelineSteps(pFactory, pProjectId, pChainId, result);
		if (!result.known && !result.failed)
		{
			result.failed = true;
			result.failureMessage = "the chain ended without recording any staging result (see the workflow log)";
		}
		return result;
	}

	/** The {@code PipelineStep} rows of the chain ({@code via} contains {@code intake <chain id>}): a FAILED step fails the chain; the staging step's detail fills what the history did not. */
	private static void readPipelineSteps(final Adaptation pFactory, final String pProjectId, final String pChainId, final ChainResult pResult)
	{
		try
		{
			final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId)
				+ " and osd:contains-case-insensitive(./via," + CatalogReader.quote("intake " + pChainId) + ")");
			try
			{
				for (Adaptation step = rows.nextAdaptation(); step != null; step = rows.nextAdaptation())
				{
					final String status = step.getString(FactoryPaths.PipelineStep.STATUS);
					final String name = step.getString(FactoryPaths.PipelineStep.STEP);
					final String stepMessage = step.getString(FactoryPaths.PipelineStep.MESSAGE);
					if ("STAGING_LOAD".equals(name) && "FAILED".equals(status)
						&& com.on.ebx.factory.intake.IntakeChainOutcome.isTotalRejectStep(pResult.known, pResult.read, pResult.rejected, stepMessage))
					{
						// a total reject ends the loader's step FAILED (the audit's rule), but in an intake chain it is a stop at staging, not a failure of the chain
						pResult.totalRejectSeen = true;
						pResult.known = true;
					}
					else if ("FAILED".equals(status) && !pResult.failed)
					{
						pResult.failed = true;
						pResult.failureMessage = name + " failed: " + step.getString(FactoryPaths.PipelineStep.MESSAGE);
					}
					if ("STAGING_LOAD".equals(name) && "DONE".equals(status) && !pResult.known)
					{
						final JsonNode detail = IntakeEngine.json(step.getString(FactoryPaths.PipelineStep.DETAIL));
						if (detail != null && detail.has("rejected"))
						{
							pResult.known = true;
							pResult.read = detail.path("read").asLong(0L);
							pResult.written = detail.path("written").asLong(0L);
							pResult.unchanged = detail.path("unchanged").asLong(0L);
							pResult.rejected = detail.path("rejected").asLong(0L);
						}
						else
						{
							pResult.known = true; // the step ran; its counts are not in the detail
						}
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			// best-effort fallback
		}
	}

	private static void closeChain(final Session pSession, final Adaptation pFactory, final Adaptation pChain, final Adaptation pProject, final ChainResult pResult)
	{
		final String chainId = pChain.getOccurrencePrimaryKey().format();
		// STOPPED is decided from the counts and the project's own threshold, not from stagingStopped alone: a total reject with no threshold is DONE_WITH_REJECTS
		final long heldBack = pResult.rejected > 0 || !pResult.totalRejectSeen ? pResult.rejected : 1L;
		final Integer threshold = pProject == null ? null : IntakeStore.stopRejectPercent(pProject);
		final String status = IntakeChainOutcome.statusOf(pResult.failed, threshold, pResult.read, heldBack, pResult.stopped);
		final String message;
		if (IntakeChainOutcome.FAILED.equals(status))
		{
			message = "failed: " + pResult.failureMessage;
		}
		else if (IntakeChainOutcome.STOPPED.equals(status))
		{
			message = IntakeChainOutcome.pauseReason(status, pResult.read, pResult.rejected, null);
		}
		else if (pResult.stopped)
		{
			message = "every row read was held back: nothing was carried to the master" + (pResult.rejected > 0 ? " (" + pResult.rejected + " row(s))" : "");
		}
		else if (pResult.rejected > 0)
		{
			message = pResult.rejected + " row(s) held back";
		}
		else
		{
			message = null;
		}
		final Map<Path, Object> values = new LinkedHashMap<Path, Object>();
		values.put(FactoryPaths.IntakeChain.STATUS, status);
		values.put(FactoryPaths.IntakeChain.ENDED_AT, new Date());
		values.put(FactoryPaths.IntakeChain.WRITTEN, BigDecimal.valueOf(pResult.written));
		values.put(FactoryPaths.IntakeChain.UNCHANGED, BigDecimal.valueOf(pResult.unchanged));
		values.put(FactoryPaths.IntakeChain.REJECTED, BigDecimal.valueOf(pResult.rejected));
		values.put(FactoryPaths.IntakeChain.MESSAGE, message == null ? null : TextLimits.truncate(message, TextLimits.MESSAGE_MAX_LENGTH));
		IntakeStore.writeChain(pSession, pFactory, chainId, values);
		IntakeEngine.log.info("[mdm-factory] intake: chain " + chainId + " ended " + status + (message == null ? "" : " - " + message));
		if (pProject == null)
		{
			return;
		}
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final ProjectState state = IntakeEngine.state(projectId);
		synchronized (state)
		{
			state.needsRecount = true; // (a): rows that arrived during the chain, or inside the trigger's throttle window
			if (state.queuedAfterChain)
			{
				state.queuedAfterChain = false;
				state.manual = true;
			}
		}
		// a stopped or failed chain pauses the intake: otherwise the next trickle would push the held-back batch through
		if (IntakeChainOutcome.pausesIntake(status) && FactoryPaths.Project.INTAKE_ON.equals(IntakeStore.state(pProject)))
		{
			try
			{
				IntakeStore.setState(pSession, pFactory, pProject, FactoryPaths.Project.INTAKE_PAUSED,
					IntakeChainOutcome.pauseReason(status, pResult.read, pResult.rejected, pResult.failureMessage));
			}
			catch (final OperationException ex)
			{
				IntakeEngine.log.error("[mdm-factory] intake: could not pause " + projectId + ": " + ex.getMessage());
			}
		}
		if (IntakeChainOutcome.opensReview(status, heldBack, IntakeStore.reviewRejects(pProject)))
		{
			IntakeEngine.openReview(pSession, pProject, state);
		}
		IntakeReadinessReader.heldBack(pFactory, projectId, true);
		IntakeEngine.requestTick();
	}

	// =============================================================================================================== non-blocking review

	/**
	 * Opens the review of the rows held back ({@code MdmFactoryRejectReview}) unless one launched earlier still runs - found by the in-memory key and, after a restart, by
	 * {@code getProcessInstanceKeysForDataContextVariable} on {@code projectCode} (the engine answers ACTIVE instances). A missing publication is a warning on the status, the
	 * chain stays reported as it is.
	 */
	private static void openReview(final Session pSession, final Adaptation pProject, final ProjectState pState)
	{
		final String code = pProject.getString(FactoryPaths.Project.CODE);
		final Repository repo = IntakeEngine.repository;
		synchronized (pState)
		{
			try
			{
				if (!PipelineWorkflowLauncher.isPublished(repo, PipelineWorkflowLauncher.REVIEW_PUBLICATION_NAME))
				{
					pState.reviewWarning = IntakeStatus.REVIEW_NOT_PUBLISHED; // audit F2-108: a code the screen words
					return;
				}
				if (IntakeEngine.reviewRunning(pSession, code, pState, true))
				{
					return;
				}
				pState.reviewKey = PipelineWorkflowLauncher.launchRejectReview(repo, pSession, code);
				pState.reviewWarning = null;
				pState.publish();
			}
			catch (final OperationException ex)
			{
				pState.reviewWarning = "The review of the rows held back could not be opened: " + ex.getMessage();
			}
			catch (final RuntimeException ex)
			{
				pState.reviewWarning = "The review of the rows held back could not be opened: " + ex;
			}
		}
	}

	/** True when a review workflow of the project is running: the in-memory key always; with {@code pDeep} also a lookup by {@code projectCode} (after a restart; a query of the workflow tables, never on the status path). Never throws. */
	static boolean reviewRunning(final Session pSession, final String pProjectCode, final ProjectState pState, final boolean pDeep)
	{
		try
		{
			final WorkflowEngine engine = WorkflowEngine.getFromRepository(IntakeEngine.repository == null ? null : IntakeEngine.repository, pSession);
			if (pState != null && pState.reviewKey != null && engine.isProcessInstanceRunning(ProcessInstanceKey.parse(pState.reviewKey)))
			{
				return true;
			}
			if (!pDeep || !PipelineWorkflowLauncher.isPublished(IntakeEngine.repository, PipelineWorkflowLauncher.REVIEW_PUBLICATION_NAME))
			{
				return false;
			}
			for (final ProcessInstanceKey key : engine.getProcessInstanceKeysForDataContextVariable(
				PublishedProcessKey.forName(PipelineWorkflowLauncher.REVIEW_PUBLICATION_NAME), "projectCode", pProjectCode))
			{
				if (engine.isProcessInstanceRunning(key))
				{
					return true;
				}
			}
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
		return false;
	}

	// =============================================================================================================== helpers

	private static long longOf(final String pText)
	{
		try
		{
			return pText == null || pText.trim().isEmpty() ? 0L : Long.parseLong(pText.trim());
		}
		catch (final NumberFormatException ex)
		{
			return 0L;
		}
	}

	private static JsonNode json(final String pText)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return IntakeEngine.MAPPER.readTree(pText);
		}
		catch (final java.io.IOException ex)
		{
			return null;
		}
	}
}
