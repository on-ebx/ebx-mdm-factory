package com.on.ebx.factory.ebx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.intake.IntakeChainOutcome;
import com.on.ebx.factory.intake.IntakeDecision;
import com.on.ebx.factory.intake.IntakePending;
import com.on.ebx.factory.intake.IntakeReadiness;
import com.on.ebx.factory.intake.IntakeSettings;
import com.on.ebx.factory.intake.IntakeStatus;
import com.on.ebx.factory.intake.IntakeSwitchRules;
import com.on.ebx.factory.transfer.IncrementalTransfer;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.directory.DirectoryHandler;

/**
 * Automatic intake lot (docs/AUTOMATIC-INTAKE.md): THE facade the wizard screens and the REST routes both call - one entry point per thing a person or an
 * application can do, with the authorisation of each stated here and nowhere else.
 * <ul>
 * <li>{@link #status} - whoever may open the project (this class does not check; the caller's own access to the project applies). Cheap: no row counting, no
 * query beyond primary-key reads and the project's few small tables; the engine's own in-memory picture and its cached counts.</li>
 * <li>{@link #saveSettings}, {@link #switchOn}, {@link #pause}, {@link #resume}, {@link #switchOff}, {@link #processNow} - an administrator, or a holder of the role of the
 * factory setting {@code pipelineLauncherRole} ({@link #mayOperate}), the same rule as the pipeline gate ({@code workflow.PipelineLauncher}).</li>
 * <li>{@link #batchComplete} - any authenticated user with WRITE access to that application's landing dataset ({@link #mayWriteLanding}): an application's technical
 * account must be able to call it without being an administrator.</li>
 * </ul>
 * Every failure is an {@link OperationException} with a sentence a person can read. <b>Nothing here has run live yet.</b>
 */
public final class Intake
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int LATEST_CHAINS = 10;

	/** The REST path of the batch-complete call of one application (POST, no body). */
	public static String batchCompletePath(final String pProjectCode, final String pSourceSystem)
	{
		return "/ebx-mdm-factory/rest/v1/projects/" + pProjectCode + "/intake/" + pSourceSystem + ":complete";
	}

	private Intake()
	{
	}

	/** One application's settings as typed on the set-up screen: a null value means "the default" (the cell is emptied). */
	public static final class SourceSettings
	{
		public String sourceSystem;
		public Integer quietSeconds;
		public Integer maxWaitMinutes;
		public Integer minRows;
		public Boolean batchSignal;
	}

	// ================================================================================================================ access

	/**
	 * Audit F-02: a holder of the MDM Factory right DESIGN (administrators included), or of the role of setting
	 * {@code pipelineLauncherRole}; the engine's own system session too. Before F-02: an administrator or that role.
	 */
	public static boolean mayOperate(final Session pSession, final Adaptation pFactory)
	{
		return FactoryAccess.mayOperatePipeline(pSession, pFactory);
	}

	private static void requireOperator(final Session pSession, final Adaptation pFactory) throws OperationException
	{
		if (!Intake.mayOperate(pSession, pFactory))
		{
			throw EngineRefusal.coded("conflict.intakeRight", "Only a holder of the MDM Factory right DESIGN, or of the pipeline launcher role, may change the automatic intake.");
		}
	}

	/**
	 * Write access to the application's landing dataset: the caller may create rows in at least one ticked landing table
	 * ({@code SessionPermissions.getNodeAccessPermissionForCreate(tableNode, table).isReadWrite()}, javap-checked; the exact answer of a given permission set was not
	 * observed live). The system user passes.
	 */
	public static boolean mayWriteLanding(final Session pSession, final Adaptation pLandingDataset, final List<String> pTableNames)
	{
		if (pSession == null || pLandingDataset == null)
		{
			return false;
		}
		if (pSession.isSystemUser())
		{
			return true;
		}
		for (final String tableName : pTableNames)
		{
			final AdaptationTable table = pLandingDataset.getTable(Path.parse("/root/" + tableName));
			if (table != null && table.getTableOccurrenceRootNode() != null
				&& pSession.getPermissions().getNodeAccessPermissionForCreate(table.getTableOccurrenceRootNode(), table).isReadWrite())
			{
				return true;
			}
		}
		return false;
	}

	private static Adaptation project(final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = pProjectCode == null ? null : DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		return project;
	}

	// ================================================================================================================ status

	/**
	 * The full status of a project's automatic intake ({@code IntakeStatus}, contract in docs/AUTOMATIC-INTAKE.md). Safe to poll every few seconds: nothing is counted
	 * here (the engine's last counts are reported as they are), the only scans are the factory's own small tables; readiness is evaluated only while the intake is not ON.
	 */
	public static IntakeStatus status(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		final String projectId = project.getOccurrencePrimaryKey().format();
		final IntakeStatus status = new IntakeStatus();
		status.project = pProjectCode;
		status.state = IntakeStore.state(project);
		status.pauseReason = IntakeStore.text(project, FactoryPaths.Project.INTAKE_PAUSE_REASON);
		status.enabledBy = IntakeStore.text(project, FactoryPaths.Project.INTAKE_ENABLED_BY);
		final Object enabledAt = IntakeSources.valueOrNull(project, FactoryPaths.Project.INTAKE_ENABLED_AT);
		status.enabledAt = enabledAt instanceof Date ? Instant.ofEpochMilli(((Date) enabledAt).getTime()) : null;
		status.reviewRejects = IntakeStore.reviewRejects(project);
		status.stopRejectPercent = IntakeStore.stopRejectPercent(project);
		status.engineRunning = IntakeEngine.isRunning();
		status.mayOperate = Intake.mayOperate(pSession, pFactory);
		final long now = System.currentTimeMillis();
		status.serverTime = Instant.ofEpochMilli(now);

		final List<IntakeSources.Info> sources = IntakeSources.read(pFactory, project);
		final boolean on = FactoryPaths.Project.INTAKE_ON.equals(status.state);
		status.pipeline.published = PipelineWorkflowLauncher.isPublished(pFactory.getHome().getRepository());
		status.pipeline.declaresIntake = status.pipeline.published && PipelineWorkflowLauncher.declaresIntake(pFactory.getHome().getRepository(), pSession);
		// audit F2-108: a CODE, worded by the screen in the person's language (bundle keys wizard.intake.pipeline.problem.<code>), never an English
		// sentence naming a file of the repository
		status.pipeline.problemCode = status.pipeline.declaresIntake ? null
			: status.pipeline.published ? IntakeStatus.Pipeline.PREDATES_INTAKE : IntakeStatus.Pipeline.NOT_PUBLISHED;
		if (!on)
		{
			final IntakeReadiness.Result readiness = IntakeReadinessReader.evaluate(pSession, pFactory, project, sources);
			status.ready = readiness.ready;
			status.readiness.addAll(readiness.lines);
		}
		else
		{
			status.ready = true;
		}

		final List<IntakeStatus.Chain> chains = IntakeStore.latestChains(pFactory, projectId, Intake.LATEST_CHAINS);
		status.chains.addAll(chains);
		for (final IntakeStatus.Chain chain : chains)
		{
			if (FactoryPaths.IntakeChain.STATUS_RUNNING.equals(chain.status))
			{
				status.currentChain = chain;
				break;
			}
		}

		final IntakeEngine.ProjectState state = on ? IntakeEngine.state(projectId) : null;
		final List<IntakeDecision.SourceInput> inputs = new ArrayList<IntakeDecision.SourceInput>();
		final Map<String, Long> counts = new LinkedHashMap<String, Long>();
		final Map<String, Long> signals = new LinkedHashMap<String, Long>();
		Long heldBack = IntakeReadinessReader.heldBackCached(projectId);
		boolean queued = false;
		if (state != null)
		{
			// the engine's last published picture: no lock, so polling never waits for a count or a launch
			final IntakeEngine.Snapshot snapshot = state.snapshot;
			for (final IntakeSources.Info source : sources)
			{
				inputs.add(new IntakeDecision.SourceInput(source.sourceSystem, state.pendingIn(source.sourceSystem), source.settings));
				counts.put(source.sourceSystem, snapshot.counts.get(source.sourceSystem));
				signals.put(source.sourceSystem, snapshot.signals.get(source.sourceSystem));
			}
			queued = snapshot.queuedAfterChain;
			status.rejectReview.warning = snapshot.reviewWarning;
			if (snapshot.pipelineProblem != null)
			{
				status.pipeline.problemCode = snapshot.pipelineProblem; // a code (IntakeStatus.Pipeline), since F2-108
			}
		}
		else
		{
			for (final IntakeSources.Info source : sources)
			{
				inputs.add(new IntakeDecision.SourceInput(source.sourceSystem, IntakePending.empty(), source.settings));
			}
		}
		status.queuedAfterChain = queued;
		// chainRunning=true: the pure decision computes the views (due times, states) and never asks for a count
		final IntakeDecision.Result views = IntakeDecision.decide(now, FactoryPaths.Project.INTAKE_ON, true, false, inputs, new IntakeDecision.RowCounter()
		{
			@Override
			public long count(final String pSourceSystem)
			{
				return 0L;
			}
		});
		for (int i = 0; i < sources.size(); i++)
		{
			final IntakeSources.Info source = sources.get(i);
			final IntakeDecision.SourceView view = views.views.get(i);
			final IntakeStatus.Source out = new IntakeStatus.Source();
			out.sourceSystem = source.sourceSystem;
			out.label = source.sourceSystem;
			out.kind = source.connectors ? IntakeStatus.KIND_CONNECTORS : IntakeStatus.KIND_PUSHED;
			out.quietSeconds = source.settings.quietSeconds;
			out.maxWaitMinutes = source.settings.maxWaitMinutes;
			out.minRows = source.settings.minRows;
			out.batchSignal = source.settings.batchSignal;
			out.rowsWaiting = counts.get(source.sourceSystem);
			final Long signal = signals.get(source.sourceSystem);
			out.lastRowAt = signal == null ? null : Instant.ofEpochMilli(signal.longValue());
			final IntakePending pending = state == null ? null : state.pendingIn(source.sourceSystem);
			out.pendingSince = pending == null || pending.pendingSinceMs == null ? null : Instant.ofEpochMilli(pending.pendingSinceMs.longValue());
			out.dueAt = view.dueAtMs == null ? null : Instant.ofEpochMilli(view.dueAtMs.longValue());
			out.latestAt = view.latestAtMs == null ? null : Instant.ofEpochMilli(view.latestAtMs.longValue());
			out.reason = view.reason;
			out.state = status.currentChain != null && status.currentChain.sources.contains(source.sourceSystem) ? IntakeStatus.STATE_RUNNING : view.state.name();
			out.batchCompletePath = Intake.batchCompletePath(pProjectCode, source.sourceSystem);
			status.sources.add(out);
		}
		status.rejectReview.heldBack = heldBack;
		status.rejectReview.open = on && state != null && IntakeEngine.reviewRunning(pSession, pProjectCode, state, false);
		return status;
	}

	// ================================================================================================================ settings

	/**
	 * Replaces the intake settings: {@code pReviewRejects} (null: unchanged), the stop threshold ({@code pStopRejectPercent}: null = never stop, otherwise 1-100) and the
	 * given sources' four settings (a null value = the default; sources not listed are untouched). Validated first, nothing is written when anything is refused:
	 * quiet 5-3600 s, longest wait 1-1440 min and not below the quiet period, minimum rows at least 1, percent 1-100 or empty ({@link IntakeSettings#validate}).
	 * Allowed in every state. Returns the new status.
	 */
	public static IntakeStatus saveSettings(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Boolean pReviewRejects,
		final Integer pStopRejectPercent, final List<SourceSettings> pSources) throws OperationException
	{
		Intake.requireOperator(pSession, pFactory);
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		final String percentRefusal = IntakeSettings.validatePercent(pStopRejectPercent);
		if (percentRefusal != null)
		{
			throw EngineRefusal.coded("conflict.refused", percentRefusal);
		}
		final Map<String, Adaptation> byName = new LinkedHashMap<String, Adaptation>();
		final com.onwbp.adaptation.RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE)
			.createRequestResult("./project=" + CatalogReader.quote(project.getOccurrencePrimaryKey().format()));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				byName.put(row.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM), row);
			}
		}
		finally
		{
			rows.close();
		}
		if (pSources != null)
		{
			for (final SourceSettings settings : pSources)
			{
				if (settings == null || !byName.containsKey(settings.sourceSystem))
				{
					throw EngineRefusal.coded("missing.application", "Unknown application " + (settings == null ? null : settings.sourceSystem) + " in project " + pProjectCode, settings == null ? null : settings.sourceSystem, pProjectCode);
				}
				final String refusal = IntakeSettings.validate(settings.quietSeconds, settings.maxWaitMinutes, settings.minRows);
				if (refusal != null)
				{
					throw EngineRefusal.coded("conflict.refused", settings.sourceSystem + ": " + refusal);
				}
			}
		}
		final Map<Path, Object> projectValues = new LinkedHashMap<Path, Object>();
		if (pReviewRejects != null)
		{
			projectValues.put(FactoryPaths.Project.INTAKE_REVIEW_REJECTS, pReviewRejects);
		}
		projectValues.put(FactoryPaths.Project.INTAKE_STOP_REJECT_PERCENT, pStopRejectPercent);
		IntakeStore.writeProject(pSession, pFactory, project, projectValues);
		if (pSources != null)
		{
			for (final SourceSettings settings : pSources)
			{
				final Map<Path, Object> values = new LinkedHashMap<Path, Object>();
				values.put(FactoryPaths.ProjectSource.INTAKE_QUIET_SECONDS, settings.quietSeconds);
				values.put(FactoryPaths.ProjectSource.INTAKE_MAX_WAIT_MINUTES, settings.maxWaitMinutes);
				values.put(FactoryPaths.ProjectSource.INTAKE_MIN_ROWS, settings.minRows);
				values.put(FactoryPaths.ProjectSource.INTAKE_BATCH_SIGNAL, settings.batchSignal);
				IntakeStore.writeProject(pSession, pFactory, byName.get(settings.sourceSystem), values);
			}
		}
		return Intake.status(pSession, pFactory, pProjectCode);
	}

	// ================================================================================================================ the state machine

	/**
	 * OFF -&gt; ON. Refused unless the readiness gate passes (the published MdmFactoryPipeline declares the intake inputs, landing loaded from every application, staging and
	 * master loaded once in full) and the caller is an operator ({@link #mayOperate}). Then: creates the signal dataset and rows in the landing dataspace, installs the three
	 * triggers on every ticked landing table (through the CALLER'S session MCP clients, like the distribution installer: the data-model and scripting servers must be
	 * reachable and the caller allowed to use them - synchronous, one publish pair per application, it can take minutes), records {@code intakeEnabledBy} (the caller: the
	 * identity chains run as) and {@code intakeEnabledAt}, sets ON and asks the engine for an immediate re-count. Already ON: returns the status unchanged. PAUSED: use
	 * {@link #resume}. A failed install leaves the state OFF (the install is idempotent: call again).
	 */
	public static IntakeStatus switchOn(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		if (!Intake.prepareSwitchOn(pSession, pFactory, pProjectCode))
		{
			return Intake.status(pSession, pFactory, pProjectCode);
		}
		return Intake.runSwitchOn(pSession, pFactory, pProjectCode, pSession.getUserReference().getUserId(), null);
	}

	/**
	 * F2-102: the cheap half of {@link #switchOn}, made where the request arrives (the wizard queues the rest as a job, {@link IntakeSwitchRun}): the caller is an operator, the
	 * intake is OFF (not paused) and a person asks. Returns false when the intake is already ON (nothing to do), throws the usual coded refusals otherwise. Reads no
	 * landing table and calls no server.
	 */
	static boolean prepareSwitchOn(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		Intake.requireOperator(pSession, pFactory);
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		final String state = IntakeStore.state(project);
		if (FactoryPaths.Project.INTAKE_ON.equals(state))
		{
			return false;
		}
		if (FactoryPaths.Project.INTAKE_PAUSED.equals(state))
		{
			throw EngineRefusal.coded("conflict.intakePaused", "The intake of " + pProjectCode + " is paused: resume it instead.", pProjectCode);
		}
		if (pSession.isSystemUser() || pSession.getUserReference() == null)
		{
			throw EngineRefusal.coded("conflict.intakeNeedsPerson", "The intake must be switched on by a person: the chains run as the user who switched it on.");
		}
		return true;
	}

	/**
	 * F2-102: the long half of {@link #switchOn} - readiness, the trigger installation (one publish pair per application, it can take minutes), then ON recorded for
	 * {@code pEnabledBy} (the person who asked: the identity chains run as, never the job's system session). Idempotent: an installation cut short leaves the state OFF, and
	 * running this again regenerates every body and goes on; already ON answers the status unchanged. {@code pProgress} (may be null) is told after each source.
	 */
	static IntakeStatus runSwitchOn(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEnabledBy, final IntakeTriggerInstaller.Progress pProgress)
		throws OperationException
	{
		Intake.requireOperator(pSession, pFactory);
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		final String state = IntakeStore.state(project);
		if (FactoryPaths.Project.INTAKE_ON.equals(state))
		{
			return Intake.status(pSession, pFactory, pProjectCode);
		}
		if (FactoryPaths.Project.INTAKE_PAUSED.equals(state))
		{
			throw EngineRefusal.coded("conflict.intakePaused", "The intake of " + pProjectCode + " is paused: resume it instead.", pProjectCode);
		}
		if (pEnabledBy == null || pEnabledBy.trim().isEmpty())
		{
			throw EngineRefusal.coded("conflict.intakeNeedsPerson", "The intake must be switched on by a person: the chains run as the user who switched it on.");
		}
		final List<IntakeSources.Info> sources = IntakeSources.read(pFactory, project);
		final IntakeReadiness.Result readiness = IntakeReadinessReader.evaluate(pSession, pFactory, project, sources);
		if (!readiness.ready)
		{
			throw EngineRefusal.coded("conflict.intakeNotReady", "The automatic intake cannot be switched on yet: " + readiness.firstBlocker().text);
		}
		IntakeTriggerInstaller.install(pSession, pFactory, project, pProgress);
		final Map<Path, Object> values = new LinkedHashMap<Path, Object>();
		values.put(FactoryPaths.Project.INTAKE_STATE, FactoryPaths.Project.INTAKE_ON);
		values.put(FactoryPaths.Project.INTAKE_PAUSE_REASON, null);
		values.put(FactoryPaths.Project.INTAKE_ENABLED_BY, pEnabledBy);
		values.put(FactoryPaths.Project.INTAKE_ENABLED_AT, new Date());
		IntakeStore.writeProject(pSession, pFactory, project, values);
		IntakeEngine.markRecount(project.getOccurrencePrimaryKey().format());
		return Intake.status(pSession, pFactory, pProjectCode);
	}

	/** ON -&gt; PAUSED with the reason "paused by &lt;user&gt;". Nothing starts until {@link #resume}; the triggers stay installed. Any other state: refused. */
	public static IntakeStatus pause(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		Intake.requireOperator(pSession, pFactory);
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		if (!FactoryPaths.Project.INTAKE_ON.equals(IntakeStore.state(project)))
		{
			throw EngineRefusal.coded("conflict.intakeNotOn", "The intake of " + pProjectCode + " is not on.", pProjectCode);
		}
		final String who = pSession.getUserReference() == null ? "" : pSession.getUserReference().getUserId();
		IntakeStore.setState(pSession, pFactory, project, FactoryPaths.Project.INTAKE_PAUSED, IntakeChainOutcome.pausedByReason(who));
		return Intake.status(pSession, pFactory, pProjectCode);
	}

	/**
	 * PAUSED -&gt; ON: clears the pause reason and asks the engine for a re-count (the rows that landed meanwhile are found there). A paused intake whose workflow model is
	 * not usable any more is refused. Any other state: refused. {@code intakeEnabledBy} is NOT changed: the chains keep running as who switched it on.
	 */
	public static IntakeStatus resume(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		Intake.requireOperator(pSession, pFactory);
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		if (!FactoryPaths.Project.INTAKE_PAUSED.equals(IntakeStore.state(project)))
		{
			throw EngineRefusal.coded("conflict.intakeNotPaused", "The intake of " + pProjectCode + " is not paused.", pProjectCode);
		}
		if (!PipelineWorkflowLauncher.declaresIntake(pFactory.getHome().getRepository(), pSession))
		{
			throw EngineRefusal.coded("conflict.pipelineOutdated", "The published pipeline workflow predates automatic intake: publish the current model (see the operator guide).");
		}
		IntakeStore.setState(pSession, pFactory, project, FactoryPaths.Project.INTAKE_ON, null);
		IntakeEngine.markRecount(project.getOccurrencePrimaryKey().format());
		return Intake.status(pSession, pFactory, pProjectCode);
	}

	/**
	 * ON or PAUSED -&gt; OFF: removes the three triggers of every ticked landing table (never another trigger: {@code unset_element_property} with {@code item}, then one publish
	 * per landing model; a trigger or model already gone is only noted), clears the enabler and the pause reason, and makes the engine forget the project. The state is set
	 * OFF even when removing the triggers failed (a trigger that stays only writes a signal nobody reads and requeues changed rows - harmless; the failure is in the
	 * answer's log line). <b>Refused while the project has a RUNNING chain</b> ({@link IntakeSwitchRules}: "a chain is running: pause the intake, wait for it to end, then switch
	 * it off"): its master and matching steps are still to run. This can never lock anyone out - a pause is always allowed, and a chain whose workflow instance is stuck in
	 * error is closed FAILED by the engine ({@code IntakeEngine.followChain}). The forced OFF of a project deletion or a step reset ({@link #forceOff}) stays unconditional.
	 */
	public static IntakeStatus switchOff(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		if (!Intake.prepareSwitchOff(pSession, pFactory, pProjectCode))
		{
			return Intake.status(pSession, pFactory, pProjectCode);
		}
		return Intake.runSwitchOff(pSession, pFactory, pProjectCode);
	}

	/** F2-102: the cheap half of {@link #switchOff}, made in the request: operator, not already OFF, no RUNNING chain. False = already OFF. */
	static boolean prepareSwitchOff(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		Intake.requireOperator(pSession, pFactory);
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		if (FactoryPaths.Project.INTAKE_OFF.equals(IntakeStore.state(project)))
		{
			return false;
		}
		final String refusal = IntakeSwitchRules.refuseSwitchOff(!IntakeStore.runningChains(pFactory, project.getOccurrencePrimaryKey().format()).isEmpty());
		if (refusal != null)
		{
			throw EngineRefusal.coded("conflict.refused", refusal);
		}
		return true;
	}

	/** F2-102: the long half of {@link #switchOff} (the trigger removal, one publish per landing model); the checks are made again, a chain may have started since the request. */
	static IntakeStatus runSwitchOff(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		if (!Intake.prepareSwitchOff(pSession, pFactory, pProjectCode))
		{
			return Intake.status(pSession, pFactory, pProjectCode);
		}
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		try
		{
			final ObjectNode removal = IntakeTriggerInstaller.uninstall(pSession, pFactory, project);
			com.orchestranetworks.service.LoggingCategory.getKernel().info("[mdm-factory] intake switched off for " + pProjectCode + ": " + removal);
		}
		catch (final RuntimeException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] intake: removing the triggers of " + pProjectCode + " failed: " + ex, ex);
		}
		Intake.forceOff(pSession, pFactory, project);
		return Intake.status(pSession, pFactory, pProjectCode);
	}

	/**
	 * OFF without touching the triggers (they disappear with the landing objects): what a step reset of LANDING/STAGING/MASTER and a project deletion call, so the engine
	 * stops looking at a project whose landing is about to vanish. A no-op when it is already OFF. Never throws.
	 */
	static void forceOff(final Session pSession, final Adaptation pFactory, final Adaptation pProject)
	{
		try
		{
			if (!FactoryPaths.Project.INTAKE_OFF.equals(IntakeStore.state(pProject)))
			{
				final Map<Path, Object> values = new LinkedHashMap<Path, Object>();
				values.put(FactoryPaths.Project.INTAKE_STATE, FactoryPaths.Project.INTAKE_OFF);
				values.put(FactoryPaths.Project.INTAKE_PAUSE_REASON, null);
				values.put(FactoryPaths.Project.INTAKE_ENABLED_BY, null);
				values.put(FactoryPaths.Project.INTAKE_ENABLED_AT, null);
				IntakeStore.writeProject(pSession, pFactory, pProject, values);
			}
		}
		catch (final OperationException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] intake: could not switch the intake off for " + pProject.getOccurrencePrimaryKey().format() + ": " + ex.getMessage());
		}
		IntakeEngine.forget(pProject.getOccurrencePrimaryKey().format());
	}

	/**
	 * "Process now": a chain starts at the engine's next pass even when nothing is due (reason {@code MANUAL}). Still one chain at a time: while one runs, the request is
	 * queued and a chain starts right after it ({@code IntakeStatus.queuedAfterChain}). Only when the intake is ON (paused: resume first). Nothing at NEW: no chain.
	 */
	public static IntakeStatus processNow(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		Intake.requireOperator(pSession, pFactory);
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		if (!FactoryPaths.Project.INTAKE_ON.equals(IntakeStore.state(project)))
		{
			throw EngineRefusal.coded("conflict.intakeNotOn", "The intake of " + pProjectCode + " is not on" + (FactoryPaths.Project.INTAKE_PAUSED.equals(IntakeStore.state(project)) ? " (paused: resume it first)" : "") + ".", pProjectCode);
		}
		final boolean running = !IntakeStore.runningChains(pFactory, project.getOccurrencePrimaryKey().format()).isEmpty();
		IntakeEngine.processNow(project.getOccurrencePrimaryKey().format(), running);
		return Intake.status(pSession, pFactory, pProjectCode);
	}

	/**
	 * The application says its batch is complete: the source is due at once, the chain starts at the engine's next pass (at least one row waiting; the quiet period still
	 * applies if the call never comes). Allowed for any authenticated user with write access to that application's landing dataset ({@link #mayWriteLanding}); the
	 * intake not being ON is not an error ({@code accepted=false}, {@code intakeState} says why - an application can call it unconditionally). Answer:
	 * {@code {"project","sourceSystem","accepted","intakeState"}}.
	 */
	public static ObjectNode batchComplete(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSourceSystem) throws OperationException
	{
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		IntakeSources.Info found = null;
		for (final IntakeSources.Info source : IntakeSources.read(pFactory, project))
		{
			if (source.sourceSystem.equals(pSourceSystem))
			{
				found = source;
			}
		}
		if (found == null)
		{
			throw EngineRefusal.coded("missing.applicationInProject", "Project " + pProjectCode + " has no application " + pSourceSystem, pProjectCode, pSourceSystem);
		}
		final AdaptationHome landingHome = IntakeSources.landingHome(pFactory, project);
		final Adaptation landingDataset = IntakeSources.landingDataset(landingHome, found);
		if (!Intake.mayWriteLanding(pSession, landingDataset, found.tableNames))
		{
			throw EngineRefusal.coded("conflict.landingWriteRight", "Forbidden: the batch-complete call needs write access to the landing dataset of " + pSourceSystem + ".", pSourceSystem);
		}
		final String state = IntakeStore.state(project);
		final boolean accepted = FactoryPaths.Project.INTAKE_ON.equals(state);
		if (accepted)
		{
			IntakeEngine.batchComplete(project.getOccurrencePrimaryKey().format(), pSourceSystem);
		}
		final ObjectNode out = Intake.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("sourceSystem", pSourceSystem);
		out.put("accepted", accepted);
		out.put("intakeState", state);
		return out;
	}

	/**
	 * "Read now" of one application: queues a plain landing load of that source as a background job ({@code LandingSourceRun.startRead}: kind {@code LANDING_READ_RUN}, resource
	 * LANDING through {@code FactoryJobQueue.enqueue}, so the caller does not wait for the read - it returns at once with the job's id) - the same {@code LandingFlows.load} the
	 * landing step makes. When the load ends without a failure {@code LandingFlows.load} calls {@code IntakeEngine.sourceLoaded}, so the chain starts right after the read.
	 * Operator only ({@link #mayOperate}); the application must be a CONNECTORS one (a Connectors flow reads it - an application that pushes its own rows has nothing to read); the
	 * intake must not be OFF (ON or PAUSED: a paused intake reads, and the rows wait for the resume). Answer: {@code {"project","sourceSystem","started","jobId","merged","position"}}.
	 */
	public static ObjectNode readNow(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pSourceSystem) throws OperationException
	{
		Intake.requireOperator(pSession, pFactory);
		final Adaptation project = Intake.project(pFactory, pProjectCode);
		if (FactoryPaths.Project.INTAKE_OFF.equals(IntakeStore.state(project)))
		{
			throw EngineRefusal.coded("conflict.intakeOff", "The intake of " + pProjectCode + " is off: switch it on first.", pProjectCode);
		}
		IntakeSources.Info found = null;
		for (final IntakeSources.Info source : IntakeSources.read(pFactory, project))
		{
			if (source.sourceSystem.equals(pSourceSystem))
			{
				found = source;
			}
		}
		if (found == null)
		{
			throw EngineRefusal.coded("missing.applicationInProject", "Project " + pProjectCode + " has no application " + pSourceSystem, pProjectCode, pSourceSystem);
		}
		if (!found.connectors)
		{
			throw EngineRefusal.coded("invalid.notReadByFlow", pSourceSystem + " is not read by a Connectors flow: the application pushes its own rows, there is nothing to read.", pSourceSystem);
		}
		final ObjectNode queued = LandingSourceRun.startRead(pSession, pFactory, found.id);
		final ObjectNode out = Intake.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("sourceSystem", pSourceSystem);
		out.setAll(queued);
		return out;
	}

	/** The predicate the engine and the staging load count/read the rows waiting with (exposed for the screens that explain it). */
	public static String waitingPredicate()
	{
		return IncrementalTransfer.NEW_SCOPE_PREDICATE;
	}
}
