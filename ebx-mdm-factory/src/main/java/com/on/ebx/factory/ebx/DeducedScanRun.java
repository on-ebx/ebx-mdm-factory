package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.deducedscan.ColumnMeasurer;
import com.on.ebx.factory.deducedscan.DeducedScanRunSummary;
import com.on.ebx.factory.deducedscan.OperationCatalog;
import com.on.ebx.factory.deducedscan.ReadRecipe;
import com.on.ebx.factory.deducedscan.TableProposal;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.wizard.DeducedPlanRule;
import com.on.ebx.factory.wizard.DeducedScanCards;
import com.on.ebx.factory.wizard.DeducedScanEligibility;
import com.on.ebx.factory.wizard.DeducedScanProgress;
import com.on.ebx.factory.wizard.JobOutcome;
import com.on.ebx.factory.wizard.NewTableSelection;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Deduced-scan lot (2026-09-24, read-recipe-contract.md sec "Why"/3): background pass behind board 2d - for a
 * {@code ProjectSource} of a project whose connection has NO catalog operation ({@link DeducedScanOperations}),
 * runs the four stages (How to list / Read a sample / Measure / Name and key) and writes the confirmed-pending
 * tables into the data catalog. Job-engine lot C-deduced (2026-09-24): moved onto {@link FactoryJobQueue} -
 * EACH {@code ProjectSource} occurrence id a person queues (via {@link #start}) becomes its OWN {@code
 * FactoryJob} of kind {@link #STEP}, resource {@link FactoryJobs#SOURCES} (this pass is, like {@link
 * SourcesRun}, a discovery/onboarding step over a project's connections - it does not itself write landing or
 * staging structures, only the data catalog's own tables and a single foreign key back on {@code
 * ProjectSource.catalogSource}; the {@code LANDING}/{@code STAGING} resources stay reserved for
 * {@code LandingRun}/{@code StagingLoadRun}). This replaces the private executor and the single growing {@code
 * PipelineStep} row the pre-lot-C version kept for a whole batch of queued sources (same read-modify-write race
 * {@code SourcesRun}'s own Javadoc describes - see that class for the design this one follows in every respect
 * except the per-entry work itself, which here still walks FOUR sub-stages (via {@link DeducedScanProgress
 * #stage}) instead of a single start/finish pair).
 * <p>
 * The four-stage progress of ONE job is kept exactly as before - a {@link DeducedScanProgress} JSON blob - but
 * now lives in THAT JOB's own {@code PipelineStep} row ({@link PipelineSteps#begin}/{@link PipelineSteps#end},
 * {@code scope} = the {@code ProjectSource} id) instead of a shared row a growing queue used to append to. The
 * blob holds exactly ONE entry (this job's own {@code ProjectSource} id) rather than a growing list - {@link
 * #status} rebuilds the wizard's multi-entry view by reading every job of this kind for the project (the same
 * {@link FactoryJobQueue#active active-plus-recent} window {@code SourcesRun} uses) and, for each one that has
 * already reached its own {@code PipelineStep} (via {@link FactoryJobs#attach}), lifting that step's one-entry
 * JSON straight out - no re-serialisation, no dependency on {@link DeducedScanProgress}'s own JSON shape beyond
 * what {@link DeducedScanProgress#parse}/{@code #toJson} already guarantee. A job still {@code QUEUED}, or
 * {@code RUNNING} but not yet far enough to have written its first stage, has no step yet - its entry is
 * synthesised from the {@code FactoryJob} row's own status alone ({@link #entryState}). This keeps {@code
 * ProjectWizardUserService}/{@code WizardPage}'s own contract - {@code detailJson} parses with {@link
 * DeducedScanProgress#parse} exactly as before - unchanged.
 */
public final class DeducedScanRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "DEDUCED_SCAN";
	/** How long a DONE/FAILED job of this kind still counts as part of "the current batch" for {@link #status} - same window as {@link SourcesRun#RECENT_MINUTES}. */
	static final long RECENT_MINUTES = 15L;

	private DeducedScanRun()
	{
	}

	/**
	 * Queues {@code pProjectSourceId} as one job for a deduced-scan pass - never refused, see {@link
	 * SourcesRun#start}'s own Javadoc for why (the queue, not this method, decides when it actually runs). The
	 * caller ({@code ebx.ui.ProjectWizardUserService}) picks which sources to offer here - typically every
	 * {@code ProjectSource} of the project whose connection answered {@code hasCatalogOperation=false} on its
	 * own card (board 2d shows one card per such application). An identical still-{@code QUEUED} request (same
	 * project, same source id) is folded into it by {@link FactoryJobQueue#enqueue} itself.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectSourceId) throws OperationException
	{
		// audit F-02 (catalog reads): the job writes and reads the catalog on the system session, and the person then decides
		// the deduced tables (board 2e, read under their session) - queued only for a person who can see the catalog
		CatalogReader.requireVisible(pSession, CatalogReader.dataset(pFactory.getHome().getRepository()), null, "the data catalog's sources");
		final ObjectNode params = DeducedScanRun.MAPPER.createObjectNode();
		params.put("projectSourceId", pProjectSourceId);
		params.put("locale", pSession == null || pSession.getLocale() == null ? "" : pSession.getLocale().toLanguageTag());
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, DeducedScanRun.STEP, params);
		final ObjectNode out = DeducedScanRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered with the queue at module start ({@link FactoryJobKinds}). One source, start to finish - no inner loop over several queued sources, no shared ledger: {@link FactoryJobQueue} is the queue now. */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectSourceId = pParams.path("projectSourceId").asText("");
			final Locale locale = SourcesRun.locale(pJobSession, pParams.path("locale").asText(""));

			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, DeducedScanRun.STEP, projectSourceId);
			FactoryJobs.attach(pHandle, stepId);

			DeducedScanProgress progress = DeducedScanProgress.newRun(Collections.singletonList(projectSourceId));
			progress.start(projectSourceId, System.currentTimeMillis());
			DeducedScanRun.writeDetail(pJobSession, pFactory, stepId, progress);

			Outcome outcome;
			try
			{
				outcome = DeducedScanRun.runOneSource(pJobSession, pFactory, pProjectId, stepId, projectSourceId, pHandle, locale);
			}
			catch (final Exception ex) // deliberately broad - this source's own failure is recorded as its outcome, never left to crash the dispatcher
			{
				outcome = new Outcome();
				outcome.state = DeducedScanProgress.FAILED;
				outcome.summary = TextLimits.truncate(ErrorText.of(ex), 500);
			}

			final Adaptation freshRow = DeducedScanRun.rowById(pFactory, stepId);
			progress = freshRow == null ? progress : DeducedScanProgress.parse(freshRow.getString(FactoryPaths.PipelineStep.DETAIL));
			progress.finish(projectSourceId, outcome.state, outcome.summary, System.currentTimeMillis(), outcome.tablesWritten);
			final String finalDetail = progress.toJson();
			// fix lot (2026-09-26): SKIPPED is an EXPECTED outcome (an enrichment-only connection, or one whose
			// official scan already covers it - see runOneSource) - never FAILED, on either the PipelineStep or
			// the FactoryJob. Only genuine FAILED still fails both.
			if (pHandle.cancelled())
			{
				PipelineSteps.endStopped(pJobSession, pFactory, stepId, outcome.summary, finalDetail);
			}
			else if (DeducedScanProgress.SKIPPED.equals(outcome.state))
			{
				PipelineSteps.endSkipped(pJobSession, pFactory, stepId, outcome.summary, finalDetail);
			}
			else
			{
				PipelineSteps.end(pJobSession, pFactory, stepId, DeducedScanProgress.DONE.equals(outcome.state), outcome.summary, finalDetail);
			}

			if (pHandle.cancelled())
			{
				// return normally (never throw) so the queue's own post-run check marks the JOB itself
				// CANCELLED rather than FAILED - see this class's own Javadoc; the entry-level state above
				// still reads FAILED/whatever runOneSource left it at, which is fine: the per-stage cards are
				// a different, finer-grained view than the jobs banner's own Stop/Cancel outcome.
				return outcome.summary;
			}
			if (DeducedScanProgress.FAILED.equals(outcome.state))
			{
				// same convention as SourcesRun/StagingLoadRun/MasterBuildRun: a pass that genuinely failed fails
				// the JOB too - the wizard never reads this job-level FAILED as a hard error, only the per-entry
				// message. SKIPPED/DONE both end the job normally (fix lot, 2026-09-26): an enrichment-only
				// connection is a real, successful answer, not a failure to retry.
				throw new FactoryJobQueue.ExpectedFailure(outcome.summary);
			}
			return outcome.summary;
		}
	};

	/**
	 * Current state of the batch of deduced-scan jobs {@code pProjectId} is running or just finished - what the
	 * wizard's board-2d cards poll. One entry per {@code FactoryJob} row of kind {@link #STEP} for this project,
	 * in the window this class's own Javadoc describes ({@link FactoryJobQueue#active} plus recent finished
	 * ones) - oldest first, same reading order the old growing-queue version gave. Never throws for "no run
	 * yet": an empty {@code entries} array, {@code running=false}. {@code detailJson} keeps the exact shape
	 * {@link DeducedScanProgress#parse} expects - {@code ProjectWizardUserService} reads it unchanged.
	 */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectId)
	{
		final ObjectNode out = DeducedScanRun.MAPPER.createObjectNode();
		final List<Adaptation> jobs = DeducedScanRun.batchJobs(pFactory, pProjectId);
		boolean running = false;
		final ArrayNode entries = DeducedScanRun.MAPPER.createArrayNode();
		// UX lot C4 (2026-10-14): one entry per SOURCE - the latest run of it. Every run used to stack its own card (three for DemoCRM, measured live);
		// the older finished runs of the same source collapse into the latest. `running` still reads every job.
		final List<Adaptation> runJobs = new ArrayList<Adaptation>();
		final List<String> runSources = new ArrayList<String>();
		for (final Adaptation job : jobs)
		{
			final String status = job.getString(FactoryPaths.FactoryJob.STATUS);
			if (FactoryJobQueue.QUEUED.equals(status) || FactoryJobQueue.RUNNING.equals(status))
			{
				running = true;
			}
			final String projectSourceId = DeducedScanRun.readParams(job).path("projectSourceId").asText("");
			if (projectSourceId.isEmpty())
			{
				continue;
			}
			runJobs.add(job);
			runSources.add(projectSourceId);
		}
		for (final Integer index : DeducedScanCards.latestIndexes(runSources))
		{
			final Adaptation job = runJobs.get(index.intValue());
			entries.add(DeducedScanRun.entryNode(pFactory, job, runSources.get(index.intValue()), job.getString(FactoryPaths.FactoryJob.STATUS)));
		}
		final ObjectNode detail = DeducedScanRun.MAPPER.createObjectNode();
		detail.set("entries", entries);
		out.put("exists", !jobs.isEmpty());
		out.put("running", running);
		// lot C-deduced: no more "stale after 30 minutes" - a job past its time limit is stopped by FactoryJobs'
		// own watchdog, and an orphan RUNNING row cannot survive a restart (FactoryJobQueue.start interrupts it).
		out.put("stale", false);
		out.put("detailJson", detail.toString());
		return out;
	}

	/**
	 * This job's own entry, straight out of its {@code PipelineStep} row's {@code detail} JSON when it has
	 * reached one ({@link FactoryPaths.FactoryJob#STEP_ID} set) - synthesised from the {@code FactoryJob} row's
	 * own status alone otherwise (still {@code QUEUED}, or {@code RUNNING} but not yet far enough along to have
	 * called {@link PipelineSteps#begin}).
	 */
	private static ObjectNode entryNode(final Adaptation pFactory, final Adaptation pJob, final String pProjectSourceId, final String pJobStatus)
	{
		final String stepId = pJob.getString(FactoryPaths.FactoryJob.STEP_ID);
		if (stepId != null && !stepId.isEmpty())
		{
			final Adaptation step = DeducedScanRun.rowById(pFactory, stepId);
			if (step != null)
			{
				final JsonNode raw = DeducedScanRun.parseJson(step.getString(FactoryPaths.PipelineStep.DETAIL));
				for (final JsonNode entryNode : raw.path("entries"))
				{
					if (pProjectSourceId.equals(entryNode.path("connectorId").asText("")) && entryNode.isObject())
					{
						return (ObjectNode) entryNode;
					}
				}
			}
		}
		final ObjectNode fallback = DeducedScanRun.MAPPER.createObjectNode();
		fallback.put("connectorId", pProjectSourceId);
		fallback.put("state", DeducedScanRun.entryState(pJobStatus));
		return fallback;
	}

	private static JsonNode parseJson(final String pJson)
	{
		try
		{
			return DeducedScanRun.MAPPER.readTree(pJson == null || pJson.isEmpty() ? "{}" : pJson);
		}
		catch (final Exception ex) // a corrupted/unreadable detail blob must never break the poll
		{
			return DeducedScanRun.MAPPER.createObjectNode();
		}
	}

	/**
	 * {@code FactoryJob} status -&gt; {@link DeducedScanProgress}'s own entry-state vocabulary ({@link
	 * DeducedScanProgress#WAITING}/{@link DeducedScanProgress#RUNNING}/{@link DeducedScanProgress#DONE}/{@link
	 * DeducedScanProgress#FAILED} - same literal strings as {@link FactoryJobQueue}'s own QUEUED/RUNNING/DONE/
	 * FAILED except QUEUED -&gt; WAITING, same mapping {@link SourcesRun#entryState} uses for its own wizard
	 * vocabulary). Only used for a job that has not reached its own {@code PipelineStep} yet - see {@link
	 * #entryNode}; a job whose step exists carries its REAL business state (which can also be {@link
	 * DeducedScanProgress#SKIPPED}, a value this mapping never produces since a {@code FactoryJob} itself has no
	 * such status). Package-visible so {@code DeducedScanRunJobMappingTest} exercises it directly, no live
	 * repository needed - same convention as {@link SourcesRun#entryState}.
	 */
	static String entryState(final String pJobStatus)
	{
		if (FactoryJobQueue.QUEUED.equals(pJobStatus))
		{
			return DeducedScanProgress.WAITING;
		}
		if (FactoryJobQueue.RUNNING.equals(pJobStatus))
		{
			return DeducedScanProgress.RUNNING;
		}
		if (JobOutcome.completed(pJobStatus))
		{
			return DeducedScanProgress.DONE;
		}
		return DeducedScanProgress.FAILED;
	}

	// ------------------------------------------------------------------------------------------------------- batch

	/** {@link FactoryJobQueue#active} of this project filtered to {@link #STEP}, plus every {@link #STEP} job of this project that ended in the last {@link #RECENT_MINUTES} minutes - oldest first. Same shape as {@link SourcesRun#batchJobs}. */
	private static List<Adaptation> batchJobs(final Adaptation pFactory, final String pProjectId)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		for (final Adaptation job : FactoryJobQueue.active(pFactory, pProjectId))
		{
			if (DeducedScanRun.STEP.equals(job.getString(FactoryPaths.FactoryJob.KIND)))
			{
				out.add(job);
			}
		}
		final long cutoff = System.currentTimeMillis() - DeducedScanRun.RECENT_MINUTES * 60000L;
		final RequestResult rows = pFactory.getTable(FactoryPaths.FACTORY_JOB).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./kind=" + CatalogReader.quote(DeducedScanRun.STEP)
				+ " and (./status=" + CatalogReader.quote(FactoryJobQueue.DONE) + " or ./status=" + CatalogReader.quote(FactoryJobQueue.DONE_WITH_FAILURES) + " or ./status="
				+ CatalogReader.quote(FactoryJobQueue.FAILED) + ")");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Date endedAt = (Date) row.get(FactoryPaths.FactoryJob.ENDED_AT);
				if (endedAt != null && endedAt.getTime() >= cutoff)
				{
					out.add(row);
				}
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

	private static JsonNode readParams(final Adaptation pJob)
	{
		final String raw = pJob.getString(FactoryPaths.FactoryJob.PARAMS);
		return DeducedScanRun.parseJson(raw);
	}

	// ------------------------------------------------------------------------------------------------ the run itself

	private static final class Outcome
	{
		String state;
		String summary;
		List<String> tablesWritten = new ArrayList<String>();
	}

	/** One {@code ProjectSource}'s full pipeline - see class Javadoc for the four stages. {@code pHandle.cancelled()} is checked between stages - see this class's own Javadoc on why the HANDLER always returns normally rather than throwing when it is. */
	private static Outcome runOneSource(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pStepId, final String pProjectSourceId,
		final FactoryJobs.Handle pHandle, final Locale pLocale) throws OperationException
	{
		final Outcome outcome = new Outcome();
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final Adaptation sourceRow = sources.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		if (sourceRow == null)
		{
			outcome.state = DeducedScanProgress.FAILED;
			outcome.summary = "Source " + pProjectSourceId + " not found.";
			return outcome;
		}
		final String connectorId = sourceRow.getString(FactoryPaths.ProjectSource.CONNECTOR);
		final String catalogSourceId = sourceRow.getString(FactoryPaths.ProjectSource.CATALOG_SOURCE);
		if (connectorId == null || connectorId.trim().isEmpty())
		{
			outcome.state = DeducedScanProgress.FAILED;
			outcome.summary = "This source names no connector.";
			return outcome;
		}
		if (pHandle.cancelled())
		{
			outcome.state = DeducedScanProgress.FAILED;
			outcome.summary = "Stopped (" + pHandle.stopReason() + ") before it started.";
			return outcome;
		}

		// stage LIST
		DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.LIST, DeducedScanProgress.RUNNING, "");
		final DeducedScanOperations.Described described = DeducedScanOperations.describe(pSession, pFactory, connectorId);
		// the same rule the wizard's Review card reads before offering the button (WizardData#attachDeducedScanEligibility)
		final DeducedScanEligibility eligibility = DeducedScanEligibility.of(described.error, described.hasCatalogOperation);
		if (eligibility == DeducedScanEligibility.UNREACHABLE)
		{
			DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.LIST, DeducedScanProgress.FAILED, described.error);
			outcome.state = DeducedScanProgress.FAILED;
			outcome.summary = described.error;
			return outcome;
		}
		if (eligibility == DeducedScanEligibility.OFFICIAL_SCAN)
		{
			DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.LIST, DeducedScanProgress.SKIPPED,
				"This connection has an official scan.");
			outcome.state = DeducedScanProgress.SKIPPED;
			outcome.summary = "This connection has an official scan (a catalog operation): use the regular source onboarding, not a deduced scan.";
			return outcome;
		}
		final List<OperationCatalog.OperationInfo> readOnlyOperations = OperationCatalog.readOnlyOperations(described.operations);
		final List<OperationCatalog.ListChoice> chosen = DeducedScanLlmSuggester.chooseListOperations(pSession, pFactory, pProjectId, connectorId, readOnlyOperations);
		if (chosen.isEmpty())
		{
			final String why = OperationCatalog.hasNoReadOnlyOperation(readOnlyOperations) ? "No read-only operation at all."
				: "No listing operation found: this connection looks up one record at a time (an enrichment, not a source).";
			DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.LIST, DeducedScanProgress.SKIPPED, why);
			outcome.state = DeducedScanProgress.SKIPPED;
			outcome.summary = why;
			return outcome;
		}
		final StringBuilder chosenNames = new StringBuilder();
		for (final OperationCatalog.ListChoice choice : chosen)
		{
			if (chosenNames.length() > 0)
			{
				chosenNames.append(", ");
			}
			chosenNames.append(choice.operation);
		}
		// Bug fix 2026-09-26 (contract point 3, found live on OpenFDA/PipelineStep 405): a choice the model
		// made is not necessarily CALLABLE - an operation whose required input(s) it left blank (deviceSearch
		// needing 'brand', here) reads as a search, not a listing, and must not be sampled. Filter BEFORE the
		// stage-DONE message below so the message itself already reflects what will really be sampled.
		final OperationCatalog.CallabilityResult callability = OperationCatalog.filterCallable(chosen, readOnlyOperations);
		if (callability.callable.isEmpty())
		{
			final String why = "No listing operation could actually be called: " + String.join("; ", callability.notCallableReasons)
				+ " (an enrichment, not a source).";
			DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.LIST, DeducedScanProgress.SKIPPED, why);
			outcome.state = DeducedScanProgress.SKIPPED;
			outcome.summary = why;
			return outcome;
		}
		final List<OperationCatalog.ListChoice> callableChoices = callability.callable;
		final StringBuilder chosenNamesFinal = new StringBuilder("the model chose " + chosenNames);
		if (!callability.notCallableReasons.isEmpty())
		{
			chosenNamesFinal.append(" (not callable: ").append(String.join("; ", callability.notCallableReasons)).append(")");
		}
		DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.LIST, DeducedScanProgress.DONE,
			chosenNamesFinal.toString());

		if (pHandle.cancelled())
		{
			outcome.state = DeducedScanProgress.FAILED;
			outcome.summary = "Stopped (" + pHandle.stopReason() + ") after choosing how to list.";
			return outcome;
		}

		// stages SAMPLE / MEASURE / NAME, per chosen operation - each one candidate table
		DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.SAMPLE, DeducedScanProgress.RUNNING, "");
		DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.MEASURE, DeducedScanProgress.RUNNING, "");
		DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.NAME, DeducedScanProgress.RUNNING, "");

		final com.on.ebx.agent.McpClient client = McpServers.connectors(pSession, pFactory);
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		// DeducedScanCatalog.ensureSource's own Javadoc: a connection with no catalog operation never goes
		// through the official onboarding path that would otherwise create ProjectSource.catalogSource - find
		// or create one now, and persist it back so board 2e's pending-tables read (keyed on catalogSourceId)
		// finds it, and so a second deduced scan of the same connection reuses the same catalog Source row.
		String resolvedCatalogSourceId = catalogSourceId;
		if (resolvedCatalogSourceId == null || resolvedCatalogSourceId.trim().isEmpty())
		{
			if (catalog == null)
			{
				outcome.state = DeducedScanProgress.FAILED;
				outcome.summary = "The data catalog is not reachable: cannot record a deduced table without it.";
				return outcome;
			}
			resolvedCatalogSourceId = DeducedScanCatalog.ensureSource(pSession, catalog, connectorId, connectorId, pLocale);
			DeducedScanRun.setCatalogSource(pSession, pFactory, pProjectSourceId, resolvedCatalogSourceId);
		}
		final String catalogSourceIdFinal = resolvedCatalogSourceId;
		int sampledOk = 0;
		int namedOk = 0;
		int written = 0;
		boolean stoppedEarly = false;
		final StringBuilder problems = new StringBuilder();
		// fix lot (2026-09-27, round 5, task item 2): tallied per table as it is written, from
		// DeducedScanCatalogWriter.Result's own outcome flags, so the run's end message (below) reflects what
		// actually happened to each table instead of one frozen "waiting for confirmation" phrase.
		final DeducedScanRunSummary.Counts writeCounts = new DeducedScanRunSummary.Counts();
		for (final OperationCatalog.ListChoice choice : callableChoices)
		{
			if (pHandle.cancelled())
			{
				stoppedEarly = true;
				break;
			}
			final DeducedScanSample.Result sample = DeducedScanSample.sample(client, connectorId, choice);
			if (sample.error != null || sample.rows.isEmpty())
			{
				problems.append(choice.operation).append(": ").append(sample.error == null ? "no row read" : sample.error).append("; ");
				continue;
			}
			sampledOk++;
			final List<ColumnMeasurer.Measured> measured = ColumnMeasurer.measure(sample.rows);
			final TableProposal proposal = DeducedScanLlmSuggester.proposeTable(pSession, pFactory, pProjectId, choice.operation, measured);
			if (proposal == null)
			{
				problems.append(choice.operation).append(": the model did not name a table; ");
				continue;
			}
			namedOk++;
			if (catalog == null)
			{
				problems.append(proposal.tableName).append(": the data catalog is not reachable, nothing written; ");
				continue;
			}
			final ReadRecipe recipe = new ReadRecipe(connectorId, choice.operation, choice.inputs, sample.itemsPath);
			final DeducedScanCatalogWriter.Result writeResult = DeducedScanCatalogWriter.write(pSession, catalog, catalogSourceIdFinal, proposal, measured, recipe, pLocale);
			if (writeResult.keptOfficial)
			{
				// audit D-07: the key source:table already names a table of an official scan of this source - never overwritten by a sample
				problems.append(proposal.tableName).append(": already in the catalog from an official scan of this source, left as it is; ");
				continue;
			}
			if (writeResult.otherRecipeReconciled)
			{
				// audit D-07: same source + same table name = one asset, whatever operation read it
				problems.append(proposal.tableName).append(": another read of the source (").append(choice.operation)
					.append(") named this same table - kept as one table, read the latest way, to confirm again; ");
			}
			written++;
			outcome.tablesWritten.add(proposal.tableName);
			if (!writeResult.scanFieldsWritten)
			{
				problems.append(proposal.tableName)
					.append(": written, but this catalog has no scan-state fields yet - confirm it in the catalog directly; ");
			}
			if (writeResult.created)
			{
				writeCounts.created++;
			}
			else if (writeResult.reopened)
			{
				writeCounts.reopened++;
			}
			else if ("CONFIRMED".equals(writeResult.keptState) || "LEFT_OUT".equals(writeResult.keptState))
			{
				writeCounts.keptDecided++;
			}
			else
			{
				// unchanged and still TO_CONFIRM (or scanFieldsWritten is false, in which case keptState is
				// always blank here too - the writer never reconciles against a catalog that has no scanState
				// field to reconcile FROM, see DeducedScanCatalogWriter's own scanFieldsExist guard) - either way,
				// this table still needs a person's decision exactly like a brand new one.
				writeCounts.keptToConfirm++;
			}
		}
		DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.SAMPLE, DeducedScanProgress.DONE,
			sampledOk + "/" + callableChoices.size() + " sampled, 25 rows each, read only");
		DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.MEASURE, DeducedScanProgress.DONE, "types, filling, uniqueness");
		DeducedScanRun.stage(pSession, pFactory, pStepId, pProjectSourceId, DeducedScanProgress.NAME, DeducedScanProgress.DONE, namedOk + "/" + callableChoices.size() + " table(s) named");

		if (stoppedEarly)
		{
			outcome.state = DeducedScanProgress.FAILED;
			outcome.summary = "Stopped (" + pHandle.stopReason() + "). " + written + " table(s) deduced before stopping." + (problems.length() > 0 ? " " + problems : "");
			return outcome;
		}
		if (written == 0)
		{
			outcome.state = DeducedScanProgress.FAILED;
			outcome.summary = "No table could be deduced. " + problems;
			return outcome;
		}
		outcome.state = DeducedScanProgress.DONE;
		// fix lot (2026-09-27, round 5, task item 2): built from what this run actually did to each table
		// (writeCounts), not a single frozen phrase - see DeducedScanRunSummary's own Javadoc.
		outcome.summary = DeducedScanRunSummary.build(writeCounts, problems.toString());
		// UX lot C4 (2026-10-14): every table already decided (a rescan of an application confirmed in an earlier project) - nothing will ever
		// trigger the plan, so it runs here, in the job, whether or not the person stays on the page
		final String planned = DeducedScanRun.planIfAllDecided(pSession, pFactory, pProjectId, catalog, catalogSourceIdFinal);
		if (planned != null)
		{
			outcome.summary = problems.length() > 0 ? planned + " " + problems : planned;
		}
		return outcome;
	}

	/**
	 * UX lot C4 (2026-10-14): when every table found from the data of {@code pCatalogSourceId} is decided ({@link DeducedPlanRule#allDecided}), plans the
	 * landing of the project source(s) of THIS project that use that catalog source - tables arrive unticked unless the connection is a sample file
	 * ({@link NewTableSelection#selectNew}). The plan only ever adds: a table planned earlier is left as it is. Returns the job's end message
	 * ("N tables found, ready to choose.") when tables were added to a source that had none, null when the run's own summary stands (a table still to
	 * confirm, nothing planned, or nothing new). A failed plan is said in the message instead of the run failing: the tables ARE found.
	 */
	private static String planIfAllDecided(final Session pSession, final Adaptation pFactory, final String pProjectId, final Adaptation pCatalog, final String pCatalogSourceId)
	{
		if (pCatalog == null || pCatalogSourceId == null || pCatalogSourceId.trim().isEmpty())
		{
			return null;
		}
		// the job's own session: the person who queued it can see the catalog (CatalogReader.requireVisible in start)
		final DeducedScanCatalog.DecisionCounts counts = DeducedScanCatalog.decisionCounts(CatalogReader.Reader.of(pSession), pCatalog, pCatalogSourceId);
		if (!DeducedPlanRule.allDecided(counts.confirmed, counts.leftOut, counts.toConfirm))
		{
			return null;
		}
		final List<String> sourceIds = new ArrayList<String>();
		final List<String> connectorIds = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./catalogSource=" + CatalogReader.quote(pCatalogSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				sourceIds.add(row.getOccurrencePrimaryKey().format());
				connectorIds.add(row.getString(FactoryPaths.ProjectSource.CONNECTOR));
			}
		}
		finally
		{
			rows.close();
		}
		int added = 0;
		for (int i = 0; i < sourceIds.size(); i++)
		{
			try
			{
				final boolean hadTables = DeducedScanRun.hasPlannedTables(pFactory, sourceIds.get(i));
				final String type = WizardData.connectorTypeOf(pFactory.getHome().getRepository(), connectorIds.get(i));
				final int planned = LandingPlanner.plan(pSession, pFactory, sourceIds.get(i), NewTableSelection.selectNew(type)).path("planned").asInt(0);
				if (!hadTables)
				{
					added += planned;
				}
			}
			catch (final OperationException ex)
			{
				LoggingCategory.getKernel().warn("[mdm-factory] landing plan at the end of the deduced scan failed for project source " + sourceIds.get(i) + ": " + ex.getMessage());
				return "The tables were found but could not be listed: " + TextLimits.truncate(ErrorText.of(ex), 300) + " Press \"List its tables\" to try again.";
			}
		}
		return added > 0 ? DeducedPlanRule.readyMessage(added) : null;
	}

	private static boolean hasPlannedTables(final Adaptation pFactory, final String pProjectSourceId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	private static void stage(final Session pSession, final Adaptation pFactory, final String pStepId, final String pEntryKey, final String pStageKey,
		final String pState, final String pSummary)
	{
		final Adaptation row = DeducedScanRun.rowById(pFactory, pStepId);
		if (row == null)
		{
			return;
		}
		final DeducedScanProgress progress = DeducedScanProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL));
		progress.stage(pEntryKey, pStageKey, pState, pSummary);
		DeducedScanRun.writeDetail(pSession, pFactory, pStepId, progress);
	}

	/** Best-effort partial update of {@code ProjectSource.catalogSource} only - see the "resolvedCatalogSourceId" block in {@link #runOneSource}'s own comment. Logged, not thrown: a failure here only means the NEXT deduced scan of this connection creates another catalog Source row instead of reusing this one - harmless, not a reason to fail the whole run. */
	private static void setCatalogSource(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final String pCatalogSourceId)
	{
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = sources.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pCatalogSourceId, FactoryPaths.ProjectSource.CATALOG_SOURCE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel()
				.error("[mdm-factory] could not record the deduced-scan catalog source of ProjectSource " + pProjectSourceId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static void writeDetail(final Session pSession, final Adaptation pFactory, final String pStepId, final DeducedScanProgress pProgress)
	{
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final String detail = TextLimits.truncate(pProgress.toJson(), TextLimits.DETAIL_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = steps.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(detail, FactoryPaths.PipelineStep.DETAIL);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not update the deduced-scan progress of step " + pStepId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static Adaptation rowById(final Adaptation pFactory, final String pStepId)
	{
		return pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
	}
}
