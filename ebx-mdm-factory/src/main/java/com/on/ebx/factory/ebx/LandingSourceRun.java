package com.on.ebx.factory.ebx;

import com.on.ebx.factory.landing.KeyPrimeRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.landing.ProgressNote;
import com.on.ebx.factory.wizard.PlanOutcomes;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Job-engine lot C3 (2026-09-24): the step-3 Advanced disclosure's own per-source "Plan"/"Generate" buttons -
 * the OLDER per-source landing path, still usable in parallel with the one-click {@link LandingRun} (see that
 * class's own Javadoc) - moved onto {@link FactoryJobQueue}, resource {@link FactoryJobs#LANDING}: the SAME
 * resource {@link LandingRun} itself uses, so a person clicking "Generate" on one application while a one-click
 * run (or another Advanced "Generate") is already working the project's landing simply waits its turn instead of
 * racing it - the very thing moving both paths onto {@link FactoryJobQueue} is meant to guarantee (queue FIFO
 * per resource, {@link FactoryJobs#claim}).
 * <ul>
 * <li>{@link #PLAN_STEP} - {@link LandingPlanner#plan}: fast (no MCP call, a read of the already-scanned catalog
 * plus writes), but still an EBX write with the calling session's own rights, so it is queued exactly like every
 * other write action here rather than kept on the request thread as a special case.</li>
 * <li>{@link #GENERATE_STEP} - {@link LandingGenerator#generate} + {@link LandingFlows#generate} (always {@code
 * force=true}, see the OLD {@code ProjectWizardUserService#generate}'s own Javadoc for why) + {@link
 * LandingFlows#load}, one after another: several MCP calls to the {@code data-model}/connectors servers -
 * CLAUDE.md &sect;15 measured the landing-model step alone at 80-100s, well past a single HTTP postback, the
 * same reasoning {@link StagingLoadRun}/{@link MasterBuildRun} already document for their own background moves.
 * Each of the three calls already opens and closes its OWN {@code PipelineStep} ({@code LANDING_MODEL}/{@code
 * LANDING_DATASET}, {@code LANDING_FLOWS}, {@code LANDING_LOAD} - see those classes' own Javadoc), so this job
 * does not wrap them in a further parent step of its own; {@link FactoryJobs.Handle#cancelled()} is checked
 * between the three calls so a stop asked mid-run does not start the next one.</li>
 * </ul>
 * A partial failure (some tables' flow/load failed while others succeeded - the same per-table count {@link
 * LandingFlows} always returned) ends the job with {@link FactoryJobQueue.ExpectedFailure} - a routine, expected
 * outcome (one log line), never a hard crash, exactly the {@code warn()}-not-{@code notice} distinction the OLD
 * synchronous {@code ProjectWizardUserService#generate} already drew.
 */
public final class LandingSourceRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String PLAN_STEP = "LANDING_PLAN_RUN";
	public static final String GENERATE_STEP = "LANDING_GENERATE_RUN";
	/**
	 * Automatic-intake lot (2026-10-06): a plain READ of one source - {@link LandingFlows#load} alone, no model or flow rebuild (what {@link #GENERATE_STEP} does first) - for
	 * the running screen's "Read now". Resource LANDING, like the two above and {@link LandingRun}: never concurrent with them for the same project.
	 */
	public static final String READ_STEP = "LANDING_READ_RUN";

	private LandingSourceRun()
	{
	}

	/** Queues {@code PLAN} (resource {@link FactoryJobs#LANDING}) and returns at once - never refused, see {@link StagingLoadRun#start}'s own Javadoc for why. */
	public static ObjectNode startPlan(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		return LandingSourceRun.start(pSession, pFactory, pProjectSourceId, LandingSourceRun.PLAN_STEP);
	}

	/**
	 * UX lot U2 (2026-10-13): {@code PLAN} for step 2's "Find its tables" - the same job, whose new tables start UNTICKED ({@link LandingPlanner#plan(Session,
	 * Adaptation, String, boolean)}) and whose result says why when it found nothing ({@link PlanOutcomes#jobMessage}). A distinct parameter, so it is
	 * never folded into a still-queued step-3 plan that would tick them.
	 */
	public static ObjectNode startFindTables(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		return LandingSourceRun.start(pSession, pFactory, pProjectSourceId, LandingSourceRun.PLAN_STEP, false);
	}

	/** Same as {@link #startPlan}, for {@code GENERATE}. */
	public static ObjectNode startGenerate(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		return LandingSourceRun.start(pSession, pFactory, pProjectSourceId, LandingSourceRun.GENERATE_STEP);
	}

	/** Same as {@link #startPlan}, for a plain {@code READ} (the landing load of that one source). */
	public static ObjectNode startRead(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		return LandingSourceRun.start(pSession, pFactory, pProjectSourceId, LandingSourceRun.READ_STEP);
	}

	private static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final String pKind) throws OperationException
	{
		return LandingSourceRun.start(pSession, pFactory, pProjectSourceId, pKind, true);
	}

	private static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final String pKind, final boolean pSelectNew)
		throws OperationException
	{
		final Adaptation source = pFactory.getTable(com.on.ebx.factory.core.FactoryPaths.PROJECT_SOURCE)
			.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		if (source == null)
		{
			throw EngineRefusal.coded("missing.projectSource", "Project source " + pProjectSourceId + " not found", pProjectSourceId);
		}
		final String projectId = source.getString(com.on.ebx.factory.core.FactoryPaths.ProjectSource.PROJECT);
		if (LandingSourceRun.PLAN_STEP.equals(pKind))
		{
			// audit F-02 (catalog reads): the PLAN job reads the catalog on the system session and copies the assets' structure
			// into the project - queued only for a person who can see those assets
			final String catalogSource = source.getString(com.on.ebx.factory.core.FactoryPaths.ProjectSource.CATALOG_SOURCE);
			CatalogReader.requireVisible(pSession, CatalogReader.dataset(pFactory.getHome().getRepository()), catalogSource,
				"the catalog assets of source " + (catalogSource == null ? "" : catalogSource.trim()));
		}
		final ObjectNode params = LandingSourceRun.MAPPER.createObjectNode();
		params.put("projectSourceId", pProjectSourceId);
		if (!pSelectNew)
		{
			params.put("selectNew", false);
		}
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, projectId, pKind, params);
		final ObjectNode out = LandingSourceRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** {@code PLAN}'s work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler PLAN_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectSourceId = pParams.path("projectSourceId").asText("");
			final ObjectNode result = LandingPlanner.plan(pJobSession, pFactory, projectSourceId, pParams.path("selectNew").asBoolean(true));
			// landing leftovers (2026-10-17): a key from the naming rule for the tables the source gave no profile for (sample files); never fails the job
			final KeyPrimeRunner.Outcome primed = KeyPrimer.prime(pJobSession, pFactory, projectSourceId, pHandle);
			final String message = LandingSourceRun.planMessage(result);
			final FactoryJobQueue.PartialOutcome partial = JobOutcomes.keyPrimeOutcome(primed, message); // F2-15: keys that could not be primed make the job PARTIAL
			if (partial != null)
			{
				throw partial;
			}
			return message;
		}
	};

	/** The job's result text for a planner answer: the counts, or "Nothing to do: ..." with the reasons ({@link PlanOutcomes#jobMessage}) when nothing was planned. Package-visible: {@code SourceRescanRun} ends with the same text. */
	static String planMessage(final ObjectNode pPlanResult)
	{
		final java.util.List<PlanOutcomes.Line> lines = new java.util.ArrayList<PlanOutcomes.Line>();
		for (final JsonNode line : pPlanResult.path("tables"))
		{
			lines.add(new PlanOutcomes.Line(line.path("sourceObject").asText(""), line.path("outcome").asText("")));
		}
		// landing test fixes (2026-10-17): tables planned earlier whose columns a rescan refreshed
		final java.util.List<String> refreshed = new java.util.ArrayList<String>();
		for (final JsonNode line : pPlanResult.path("tables"))
		{
			if (PlanOutcomes.OUTCOME_REFRESHED.equals(line.path("outcome").asText("")))
			{
				refreshed.add(line.path("summary").asText(line.path("sourceObject").asText("")));
			}
		}
		if (!refreshed.isEmpty())
		{
			return PlanOutcomes.refreshMessage(pPlanResult.path("planned").asInt(0), refreshed);
		}
		final java.util.List<String> unmatched = new java.util.ArrayList<String>();
		for (final JsonNode entry : pPlanResult.path("unmatchedSchemas"))
		{
			unmatched.add(entry.asText(""));
		}
		return PlanOutcomes.jobMessage(pPlanResult.path("planned").asInt(0), pPlanResult.path("alreadyPlanned").asInt(0), lines, unmatched);
	}

	/**
	 * {@code READ}'s work, registered with the queue at module start ({@code FactoryJobKinds}): the SAME call the landing step's load makes ({@code LandingRun} /
	 * {@code GENERATE_HANDLER}): {@code LandingFlows.load(session, factory, projectSourceId, note, handle)} - the job's own Handle reaches the load's Stop button. A successful end of
	 * that load tells the intake engine by itself ({@code IntakeEngine.sourceLoaded}), so the chain starts right after the read.
	 */
	static final FactoryJobQueue.Handler READ_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectSourceId = pParams.path("projectSourceId").asText("");
			final ObjectNode load = LandingFlows.load(pJobSession, pFactory, projectSourceId, ProgressNote.NONE, pHandle);
			if (load.path("failed").asInt(0) > 0)
			{
				throw new FactoryJobQueue.ExpectedFailure(load.path("failed").asInt(0) + " table(s) failed to load: " + load.path("firstFailureReason").asText("see the pipeline steps for detail") + ".");
			}
			return "STOPPED".equals(load.path("status").asText("")) ? "The read was stopped." : "Read " + load.path("tables").asInt(0) + " table(s).";
		}
	};

	/** {@code GENERATE}'s work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler GENERATE_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectSourceId = pParams.path("projectSourceId").asText("");
			LandingGenerator.generate(pJobSession, pFactory, projectSourceId);
			if (pHandle.cancelled())
			{
				// FactoryJobQueue itself prefixes the returned message with "Stopped (<reason>)." once it sees
				// pHandle.cancelled() true on return - see its own Javadoc - so this message only needs to say
				// how far the run actually got.
				return "The landing model was built.";
			}
			final ObjectNode flows = LandingFlows.generate(pJobSession, pFactory, projectSourceId, true);
			if (pHandle.cancelled())
			{
				return "The landing model was built and the flows were created.";
			}
			// volume lot 7c (2026-09-28): threads this job's own Handle through so its Stop button reaches a
			// table's load WHILE it is running (polled every few seconds), not only between the three calls
			// above as before this lot.
			final ObjectNode load = LandingFlows.load(pJobSession, pFactory, projectSourceId, ProgressNote.NONE, pHandle);
			final int failed = flows.path("failed").asInt(0) + load.path("failed").asInt(0);
			if (failed > 0)
			{
				throw new FactoryJobQueue.ExpectedFailure(failed + " table(s) failed while generating the landing - see the pipeline steps below for detail.");
			}
			return "Landing generated.";
		}
	};
}
