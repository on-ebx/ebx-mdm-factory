package com.on.ebx.factory.ebx;

import com.on.ebx.factory.landing.KeyPrimeRunner;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.project.ConnectionProvenance;
import com.on.ebx.factory.wizard.NewTableSelection;
import com.on.ebx.factory.wizard.SourceOnboarding;
import com.on.ebx.factory.wizard.SourcesProgress;
import com.on.ebx.factory.wizard.WizardRules;
import com.on.ebx.factory.wizard.JobOutcome;
import com.on.ebx.factory.landing.RunTexts;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Sources-landing lot SOURCES (2026-09-24, docs/SOURCES-LANDING-SOURCES.md): the background pass behind
 * "Connect a live system" - runs {@link SourceOnboarder#onboardAll} for a connection an application card is
 * waiting on. Job-engine lot C1 (2026-09-24): moved onto {@link FactoryJobQueue} - EACH connector id a person
 * queues (via {@link #start}/{@link #startById}) becomes its OWN {@code FactoryJob} of kind {@link #STEP},
 * resource {@link FactoryJobs#SOURCES}. Jobs of that resource of one project run one after another, FIFO, by
 * construction (the queue only ever claims the resource for one job at a time) - this replaces the private
 * executor and the single growing {@link SourcesProgress} ledger this class used to keep on one shared parent
 * {@code PipelineStep} row (read-modify-write races between {@link #start} appending and the background loop
 * re-reading, and an entry appended after the loop's last read never running - both gone now that every
 * request is its own durable row instead of a field inside another row's JSON). An identical still-{@code
 * QUEUED} request (same project, same connector id/hints/locale) is folded into it by {@link
 * FactoryJobQueue#enqueue} itself - queuing the same connector twice runs it once.
 * <p>
 * What one job's work actually does is entirely {@link SourceOnboarder#onboardAll} - THIS class only decides
 * WHEN to call it and records the outcome; the six-step diagnosis, the write calls ({@code
 * connectors_catalog_source(apply=true)}, {@code connectors_sync}), and every gate/guard around them stay
 * exactly as {@code SourceOnboarder}/{@code SourceOnboarding} already wrote them. A connector whose diagnosis
 * is not {@link SourceOnboarding.Diagnosis#ready} after {@code onboardAll} ran (a step came back {@code
 * BLOCKED} - a person must act) ends its OWN job {@code FAILED} with that reason as the message - which only
 * means "this automatic pass could not finish it alone", never a hard error: the live per-application card
 * re-diagnoses on every render ({@code wizard.ApplicationStages#from}) and shows the real reason, independent
 * of this job's own outcome. This mirrors the meaning {@link SourcesProgress#FAILED} already carried for
 * exactly this case, on the same job/step-level status the sibling jobs ({@code StagingLoadRun}, {@code
 * MasterBuildRun}) also use for "this pass did not fully complete".
 * <p>
 * {@link #status} rebuilds the wizard's "applications in progress" cards from the project's {@link
 * FactoryPaths.FactoryJob} rows of kind {@link #STEP} - the CURRENT batch, meaning every job still {@link
 * FactoryJobQueue#active active} plus every one that ended in the last {@link #RECENT_MINUTES} minutes (a
 * simple, explainable window: a person watching the screen keeps seeing a just-finished card for a few
 * minutes, an old one from a previous session does not linger forever). A {@link FactoryJobQueue#CANCELLED}
 * job is left out of that window entirely - the Review screen's "Remove" ({@link #cancel}) is meant to make
 * the card disappear, not show a lingering "cancelled" badge, the same effect {@link SourcesProgress#remove}
 * used to have.
 */
public final class SourcesRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "SOURCES_RUN";
	/** How long a DONE/FAILED job of this kind still counts as part of "the current batch" for {@link #status}. */
	static final long RECENT_MINUTES = 15L;

	private SourcesRun()
	{
	}

	/** Same as {@link #start(Session, Adaptation, String, String, String, int)} with no code/trust-rank hint - the guided-connection path (no such fields exist there, {@code ebx.SourcesRun}'s auto-add-on-ready always auto-suggests the code). */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pConnectorId) throws OperationException
	{
		return SourcesRun.start(pSession, pFactory, pProjectCode, pConnectorId, "", 100);
	}

	/**
	 * Queues {@code pConnectorId} as one job for the background onboarding pass of {@code pProjectCode} - never
	 * refused, see {@link StagingLoadRun#start}'s own Javadoc for why (the queue, not this method, decides when
	 * it actually runs). {@code pCodeHint}/{@code pTrustRankHint} are the sample-files table's own per-file
	 * code/trust-rank fields (blank/100 from the guided-connection path, which has no such fields) - carried in
	 * the job's own {@code params} and read back by {@link #addApplicationIfReady} once this connection reaches
	 * {@code ready}.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pConnectorId, final String pCodeHint,
		final int pTrustRankHint) throws OperationException
	{
		final Adaptation project = SourcesRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		return SourcesRun.startForProjectId(pSession, pFactory, project.getOccurrencePrimaryKey().format(), pConnectorId, pCodeHint, pTrustRankHint);
	}

	/** Same as {@link #start(Session, Adaptation, String, String, String, int)}, but the caller already has the project's OCCURRENCE id rather than its code - {@code SampleSourceImporter} is the one caller in this position (it works off {@code ProjectSource.project}, an id, throughout). */
	public static ObjectNode startById(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pConnectorId, final String pCodeHint,
		final int pTrustRankHint) throws OperationException
	{
		return SourcesRun.startForProjectId(pSession, pFactory, pProjectId, pConnectorId, pCodeHint, pTrustRankHint);
	}

	/**
	 * Builds the job's {@code params} and hands it to {@link FactoryJobQueue#enqueue} - the caller's session is
	 * read HERE ONLY (its locale, captured into {@code params}): the job itself runs later, with the system
	 * session, inside {@code Actor.as} for whoever queued it (see {@link FactoryJobs}'s own class Javadoc) -
	 * there is no caller session left by the time the work actually runs, so anything the work needs FROM the
	 * caller (here: the locale an error message should come back in - {@link #localize}) has to travel as data,
	 * not as a live object.
	 */
	private static ObjectNode startForProjectId(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pConnectorId, final String pCodeHint,
		final int pTrustRankHint) throws OperationException
	{
		// audit F-02 (catalog reads): the job reads the catalog on the system session (schemas, landing plan) and copies what
		// it finds into the project - queued only for a person who can see the catalog
		CatalogReader.requireVisible(pSession, CatalogReader.dataset(pFactory.getHome().getRepository()), null, "the data catalog's sources");
		final ObjectNode params = SourcesRun.MAPPER.createObjectNode();
		params.put("connectorId", pConnectorId);
		params.put("codeHint", pCodeHint == null ? "" : pCodeHint);
		params.put("trustRankHint", pTrustRankHint);
		params.put("locale", pSession == null || pSession.getLocale() == null ? "" : pSession.getLocale().toLanguageTag());
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, SourcesRun.STEP, params);
		final ObjectNode out = SourcesRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered with the queue at module start ({@link FactoryJobKinds}). One connector, start to finish - no inner loop, no shared ledger: {@link FactoryJobQueue} is the queue now. */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String connectorId = pParams.path("connectorId").asText("");
			final String codeHint = pParams.path("codeHint").asText("");
			final int trustRankHint = pParams.path("trustRankHint").asInt(100);
			final Locale locale = SourcesRun.locale(pJobSession, pParams.path("locale").asText(""));

			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, SourcesRun.STEP, connectorId);
			FactoryJobs.attach(pHandle, stepId);

			String summary;
			boolean ready;
			// lot C (2026-10-17): what this chain wrote into the catalogue is the proof a project deletion needs (ConnectionProvenance); it is noted even when a
			// later step throws, hence the holder rather than the returned facts
			final SourceOnboarder.Created created = new SourceOnboarder.Created();
			final KeyPrimeRunner.Outcome[] primed = new KeyPrimeRunner.Outcome[1];
			try
			{
				final SourceOnboarding.Facts facts = SourceOnboarder.onboardAll(pJobSession, pFactory, connectorId, created);
				final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
				ready = diagnosis.ready;
				summary = ready ? SourcesRun.addApplicationIfReady(pJobSession, pFactory, pProjectId, connectorId, diagnosis, codeHint, trustRankHint, locale,
					// UX lot U2 (2026-10-13): the tables a live system holds start unticked (the person keeps the ones they want); a sample file IS the tables the person chose
					// (lot C1: the one rule, NewTableSelection)
					NewTableSelection.selectNew(facts.connectorType), pHandle, primed)
					: SourcesRun.firstUnfinishedSummary(diagnosis);
			}
			catch (final Exception ex) // deliberately broad - this connector's own failure is recorded as its outcome, never left to crash the dispatcher
			{
				ready = false;
				summary = TextLimits.truncate(ErrorText.of(ex), 500);
			}
			PipelineSteps.end(pJobSession, pFactory, stepId, ready, summary, ConnectionProvenance.sourcesRunDetail(connectorId, created.sourceId, created.sourceCreated));
			if (!ready)
			{
				// same convention as StagingLoadRun/MasterBuildRun: a stage (here, the only stage) that did not
				// finish fails the JOB too - the wizard never reads this job-level FAILED as a hard error, only
				// the per-connector message (see this class's own Javadoc on why "BLOCKED" is not an error).
				throw new FactoryJobQueue.ExpectedFailure(summary);
			}
			final FactoryJobQueue.PartialOutcome partial = JobOutcomes.keyPrimeOutcome(primed[0], summary); // F2-15: keys that could not be primed make the job PARTIAL
			if (partial != null)
			{
				throw partial;
			}
			return summary;
		}
	};

	/**
	 * Current state of the batch of connector jobs {@code pProjectCode} is running or just finished - what the
	 * wizard's "preparing" cards poll. One entry per {@code FactoryJob} row of kind {@link #STEP} for this
	 * project, in the window this class's own Javadoc describes ({@link FactoryJobQueue#active} plus recent
	 * finished ones) - oldest first, same reading order the old {@link SourcesProgress#entries} gave. Never
	 * throws for "no run yet": an empty {@code entries} array, {@code running=false}.
	 */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = SourcesRun.MAPPER.createObjectNode();
		final Adaptation project = SourcesRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			out.put("exists", false);
			out.put("running", false);
			out.put("stale", false);
			out.putArray("entries");
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final List<Adaptation> jobs = SourcesRun.batchJobs(pFactory, projectId);
		boolean running = false;
		final ArrayNode entries = out.putArray("entries");
		for (final Adaptation job : jobs)
		{
			final String status = job.getString(FactoryPaths.FactoryJob.STATUS);
			if (FactoryJobQueue.QUEUED.equals(status) || FactoryJobQueue.RUNNING.equals(status))
			{
				running = true;
			}
			final JsonNode params = SourcesRun.readParams(job);
			final String connectorId = params.path("connectorId").asText("");
			if (connectorId.isEmpty())
			{
				continue;
			}
			final ObjectNode entryOut = entries.addObject();
			entryOut.put("connectorId", connectorId);
			entryOut.put("state", SourcesRun.entryState(status));
			final Date startedAt = (Date) job.get(FactoryPaths.FactoryJob.STARTED_AT);
			if (startedAt != null)
			{
				entryOut.put("startedAt", startedAt.getTime());
			}
			final Date endedAt = (Date) job.get(FactoryPaths.FactoryJob.ENDED_AT);
			if (endedAt != null)
			{
				entryOut.put("endedAt", endedAt.getTime());
			}
			final String message = job.getString(FactoryPaths.FactoryJob.MESSAGE);
			if (message != null)
			{
				entryOut.put("summary", EngineMessageCodec.decode(message).raw); // wave 10: never the code marker
			}
		}
		out.put("exists", !jobs.isEmpty());
		out.put("running", running);
		// lot C1: no more "stale after 30 minutes" - a job past its time limit is stopped by FactoryJobs' own
		// watchdog, and an orphan RUNNING row cannot survive a restart (FactoryJobQueue.start interrupts it).
		out.put("stale", false);
		return out;
	}

	/**
	 * Sources-landing lot SOURCES (2026-09-24): the Review screen's "Remove" on a still-stuck application. A
	 * no-op (not an error) when there is no matching job, or it is not {@code QUEUED}/{@code RUNNING} - "Remove"
	 * on a card the poll already moved past is harmless, never a stale-state error, same rule the class's own
	 * previous {@link SourcesProgress#remove}-based version followed.
	 */
	public static void cancel(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pConnectorId)
	{
		final Adaptation project = SourcesRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			return;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		for (final Adaptation job : FactoryJobQueue.active(pFactory, projectId))
		{
			if (!SourcesRun.STEP.equals(job.getString(FactoryPaths.FactoryJob.KIND)))
			{
				continue;
			}
			final JsonNode params = SourcesRun.readParams(job);
			if (!pConnectorId.equals(params.path("connectorId").asText("")))
			{
				continue;
			}
			final String jobId = job.getOccurrencePrimaryKey().format();
			final String status = job.getString(FactoryPaths.FactoryJob.STATUS);
			try
			{
				if (FactoryJobQueue.QUEUED.equals(status))
				{
					FactoryJobQueue.cancel(pSession, pFactory, jobId);
				}
				else if (FactoryJobQueue.RUNNING.equals(status))
				{
					// exactly one job of this kind/resource can be RUNNING for the project at a time (FIFO per
					// resource, FactoryJobs#claim) - a match on connectorId AND RUNNING means this IS that job.
					FactoryJobs.cancel(projectId, SourcesRun.STEP, SourcesRun.actorId(pSession));
				}
			}
			catch (final OperationException ex) // best-effort: same "never refuses" posture as the old version
			{
				LoggingCategory.getKernel().error("[mdm-factory] could not cancel sources-run job " + jobId + " of connector " + pConnectorId + ": " + ex, ex);
			}
			return; // at most one QUEUED/RUNNING job per connector id (enqueue folds duplicates)
		}
	}

	private static String actorId(final Session pSession)
	{
		final Actor explicit = Actor.explicit();
		if (explicit != null)
		{
			return explicit.userId;
		}
		return pSession == null || pSession.getUserReference() == null ? null : pSession.getUserReference().getUserId();
	}

	// ------------------------------------------------------------------------------------------------------- batch

	/** {@link FactoryJobQueue#active} of this project filtered to {@link #STEP}, plus every {@link #STEP} job of this project that ended in the last {@link #RECENT_MINUTES} minutes - oldest first. */
	private static List<Adaptation> batchJobs(final Adaptation pFactory, final String pProjectId)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		for (final Adaptation job : FactoryJobQueue.active(pFactory, pProjectId))
		{
			if (SourcesRun.STEP.equals(job.getString(FactoryPaths.FactoryJob.KIND)))
			{
				out.add(job);
			}
		}
		final long cutoff = System.currentTimeMillis() - SourcesRun.RECENT_MINUTES * 60000L;
		final RequestResult rows = pFactory.getTable(FactoryPaths.FACTORY_JOB).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./kind=" + CatalogReader.quote(SourcesRun.STEP)
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
		try
		{
			final String raw = pJob.getString(FactoryPaths.FactoryJob.PARAMS);
			return SourcesRun.MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
		}
		catch (final Exception ex) // a corrupted/unreadable params blob must never break the poll
		{
			return SourcesRun.MAPPER.createObjectNode();
		}
	}

	/**
	 * {@code FactoryJob}/{@code FactoryJobQueue} status -&gt; the wizard's own vocabulary ({@link
	 * SourcesProgress#WAITING}/{@link SourcesProgress#RUNNING}/{@link SourcesProgress#DONE}/{@link
	 * SourcesProgress#FAILED} - {@code wizard.sources.run.state.*} message keys, {@code WizardPage}). {@link
	 * FactoryJobQueue#CANCELLED} never reaches here - {@link #batchJobs} only selects QUEUED/RUNNING/DONE/FAILED.
	 * Package-visible, same reason as {@link #uniqueCode}: {@code SourcesRunJobMappingTest} exercises it
	 * directly, no live repository needed.
	 */
	static String entryState(final String pJobStatus)
	{
		if (FactoryJobQueue.QUEUED.equals(pJobStatus))
		{
			return SourcesProgress.WAITING;
		}
		if (FactoryJobQueue.RUNNING.equals(pJobStatus))
		{
			return SourcesProgress.RUNNING;
		}
		if (JobOutcome.completed(pJobStatus))
		{
			return SourcesProgress.DONE;
		}
		return SourcesProgress.FAILED;
	}

	// ------------------------------------------------------------------------------------------------ the outcome

	private static String firstUnfinishedSummary(final SourceOnboarding.Diagnosis pDiagnosis)
	{
		for (final SourceOnboarding.Step step : pDiagnosis.steps)
		{
			if (step.state != SourceOnboarding.State.DONE)
			{
				return "Not finished automatically: " + step.key + " needs attention.";
			}
		}
		return "Not finished automatically.";
	}

	/**
	 * Auto-pick-on-ready: what {@code SOURCE_ONBOARD_ALL} used to do synchronously on the request thread the
	 * moment its diagnosis turned {@code ready} (pre-fill the "add an application" form and let a person click
	 * submit) is now done for real, directly, here, on the job's own thread - there is no person watching a
	 * form to submit. Guard: {@link SampleSourceImporter#applicationUsing} (the SAME dedup rule the
	 * sample-files path already used) - if another application of this project already uses this catalog
	 * source, nothing is written, the summary just says so. Otherwise builds a {@link WizardRules.SourceDraft}
	 * (schemas from {@link WizardData#schemasOf}, {@code sourceSystem} = {@code pCodeHint} when non-empty -
	 * the sample-files table's own typed code - else auto-suggested from the first schema name via {@link
	 * WizardRules#suggestSourceSystemCode}, de-duplicated against every code already used in the project with
	 * a numeric suffix; trust rank = {@code pTrustRankHint}) and calls {@link WizardData#addSource}.
	 *
	 * @return the one-line summary for this job's outcome - never throws itself (a write failure is caught and
	 *         turned into the summary text) so the caller's own broad catch is only for truly unexpected errors,
	 *         not this method's own expected failure modes.
	 */
	private static String addApplicationIfReady(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pConnectorId,
		final SourceOnboarding.Diagnosis pDiagnosis, final String pCodeHint, final int pTrustRankHint, final Locale pLocale,
		final boolean pSelectNewTables, final FactoryJobs.Handle pHandle, final KeyPrimeRunner.Outcome[] pPrimed)
	{
		final String existingSourceSystem = SampleSourceImporter.applicationUsing(pFactory, pProjectId, pDiagnosis.pickSourceId);
		if (existingSourceSystem != null)
		{
			return "Ready: source " + pDiagnosis.pickSourceId + " - already used by application " + existingSourceSystem + ", not added again.";
		}

		final WizardRules.SourceDraft draft = new WizardRules.SourceDraft();
		draft.catalogSourceId = pDiagnosis.pickSourceId;
		draft.connector = pConnectorId;
		// audit F-02 (catalog reads): the job's system session (queued only for a person who can see the catalog, see startForProjectId)
		final List<String> schemas = WizardData.schemasOf(CatalogReader.Reader.of(pSession), pFactory, pDiagnosis.pickSourceId);
		draft.schemas.addAll(schemas);
		final java.util.Set<String> existingCodes = WizardData.existingSourceSystemCodes(pFactory, pProjectId);
		if (pCodeHint != null && !pCodeHint.trim().isEmpty())
		{
			draft.sourceSystem = SourcesRun.uniqueCode(pCodeHint.trim(), existingCodes);
		}
		else
		{
			// volume-test bug (project PersVol, 2026-09-24): a database exposing a single, generically-named
			// schema (H2's default "PUBLIC") proposed that same code for every one of the project's sources -
			// WizardRules.proposeApplicationCode falls back to a connection-id-derived code in that case (and
			// de-duplicates the result itself, same convention as SourcesRun.uniqueCode below).
			draft.sourceSystem = WizardRules.proposeApplicationCode(schemas.isEmpty() ? pConnectorId : schemas.get(0), pConnectorId, existingCodes);
		}
		// same report: sources queued one after another all kept trustRank at the field's own default (100)
		// unless a caller passed an explicit hint, so "when two applications disagree" never reflected the
		// order they were actually added in - propose the next distinct rank when no explicit hint was given.
		draft.trustRank = pTrustRankHint != 100 ? pTrustRankHint : WizardRules.nextTrustRank(WizardData.existingTrustRanks(pFactory, pProjectId));

		final List<WizardRules.Problem> problems = WizardRules.sourceProblems(draft, existingCodes);
		if (!problems.isEmpty())
		{
			// defensive: uniqueCode above should already make this unreachable for the "taken" problem, and
			// every other field is built by this method itself - kept as a hard stop rather than a silent write
			// of something WizardRules itself would refuse, same discipline as SampleSourceImporter's own check
			return "Ready: source " + pDiagnosis.pickSourceId + " - could not be added automatically (" + problems.get(0).key + ").";
		}
		final String newSourceId;
		try
		{
			newSourceId = WizardData.addSource(pSession, pFactory, pProjectId, draft, pLocale);
		}
		catch (final OperationException ex)
		{
			return "Ready: source " + pDiagnosis.pickSourceId + " - could not be added automatically: " + ex.getMessage();
		}
		// task brief item 2 (Review screen): a landing table needs a row to write its "in scope?" tick into -
		// run the (idempotent) landing plan right away so the Review screen has something to show the moment
		// this application appears there, rather than an application with zero tables until someone happens to
		// open step 3. A failure here does not undo the just-added application - it only means the Review
		// screen's table list stays empty until a person re-runs the plan from step 3.
		try
		{
			LandingPlanner.plan(pSession, pFactory, newSourceId, pSelectNewTables);
			// landing leftovers (2026-10-17): a key from the naming rule for the tables the source gave no profile for (sample files); never fails the job
			pPrimed[0] = KeyPrimer.prime(pSession, pFactory, newSourceId, pHandle);
		}
		catch (final OperationException ex)
		{
			return RunTexts.sourceReady(draft.sourceSystem, pDiagnosis.pickSourceId, String.valueOf(ex.getMessage())).stored();
		}
		return RunTexts.sourceReady(draft.sourceSystem, pDiagnosis.pickSourceId, null).stored();
	}

	/**
	 * {@code pBaseCode} if it is not already in {@code pExistingCodes}, else {@code pBaseCode + "_2"}, {@code
	 * "_3"}, ... - capped at {@code WizardRules.CODE_MAX} so a long base code plus a suffix never itself
	 * becomes invalid. Never returns a code already in {@code pExistingCodes} (an unbounded project could in
	 * theory exhaust every suffix up to a stopping point; this method stops at 999 and returns the base code
	 * truncated with that suffix regardless - astronomically unlikely to collide with a REAL existing code as
	 * well, and {@link WizardRules#sourceProblems} is still checked by the caller as a final guard).
	 * Package-visible (not pure enough to live in {@code wizard.*} - {@code WizardRules.CODE_MAX} is the only
	 * EBX-free dependency, but this class as a whole is EBX-bound) so {@code SourcesRunUniqueCodeTest} can
	 * exercise it directly without a live repository.
	 */
	static String uniqueCode(final String pBaseCode, final java.util.Set<String> pExistingCodes)
	{
		String base = pBaseCode == null || pBaseCode.trim().isEmpty() ? "SRC" : pBaseCode.trim();
		if (base.length() > WizardRules.CODE_MAX)
		{
			base = base.substring(0, WizardRules.CODE_MAX);
		}
		if (!pExistingCodes.contains(base))
		{
			return base;
		}
		for (int suffixNum = 2; suffixNum < 1000; suffixNum++)
		{
			final String suffix = "_" + suffixNum;
			final int keep = Math.max(1, WizardRules.CODE_MAX - suffix.length());
			final String candidate = (base.length() > keep ? base.substring(0, keep) : base) + suffix;
			if (!pExistingCodes.contains(candidate))
			{
				return candidate;
			}
		}
		return base + "_999";
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	// --------------------------------------------------------------------------------------------- locale carry

	/**
	 * job-session-unwrapped fix (2026-09-25): replaces a previous {@code localize} method, which wrapped the
	 * job's real session in a hand-made fake {@link Session} just to fake {@link Session#getLocale()} - EBX
	 * internals (at least the ebx-connectors in-JVM bridge, {@link ConnectorsBridge#exchange}, which hands the
	 * session object to another module's MBean) cast a {@link Session} to the kernel's own concrete
	 * implementation rather than only calling the interface, so a hand-made {@code implements Session} wrapper
	 * around the job's real session broke with a {@code ClassCastException} the first time such a call was
	 * reached (live, project DeducedTest, 2026-09-25: {@code DEDUCED_SCAN} failed in 60 ms on exactly this).
	 * The job's own EBX/MCP calls now always use {@code pJobSession} unchanged; only the LOCALE a failure
	 * message should come back in travels separately, as a plain {@link Locale} resolved here from the job's
	 * captured {@code "locale"} param (see {@link #startForProjectId}), falling back to {@code pJobSession}'s
	 * own locale when the param is blank/unparsable (no session at enqueue time, or a pre-lot-C1 job resumed
	 * from a restart with no locale in its params). Passed explicitly to whichever callee needs it only to
	 * format a user-facing message ({@link WizardData#addSource}, {@link DeducedScanCatalog#ensureSource},
	 * {@link DeducedScanCatalogWriter#write} - each has a {@code Locale} overload for exactly this).
	 */
	static Locale locale(final Session pJobSession, final String pLanguageTag)
	{
		if (pLanguageTag != null && !pLanguageTag.trim().isEmpty())
		{
			final Locale parsed = Locale.forLanguageTag(pLanguageTag);
			if (parsed != null)
			{
				return parsed;
			}
		}
		return pJobSession == null || pJobSession.getLocale() == null ? Locale.ENGLISH : pJobSession.getLocale();
	}
}
