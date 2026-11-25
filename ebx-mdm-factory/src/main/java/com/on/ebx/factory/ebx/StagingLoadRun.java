package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
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
import com.on.ebx.factory.staging.RunProgress;
import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.wizard.EngineMessageCodec;
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
 * Wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/StagingLoad.dc.html): the one-click "Fill
 * the shared structure" button at the end of step 4 - chains {@code StagingGenerator.generate} (build/
 * publish the staging model, create the dataset) then {@code StagingLoader.load(..., true)} (read every
 * landing table, write every application's rows into staging) IN THE BACKGROUND, on a small daemon
 * executor, the exact same shape as {@link StagingProposalRun}: one parent {@code PipelineStep} ({@link
 * #STEP}) whose {@code detail} holds a {@code staging.RunProgress} JSON the wizard's "while running" screen
 * polls. Since lot C1 (2026-09-24) the run is a job of {@link FactoryJobQueue} (kind {@link #STEP}, resource STAGING).
 * <p>
 * <b>Why background, not a synchronous chain in one postback (task brief question):</b> {@code
 * StagingGenerator.generate} and {@code StagingLoader.load} both already run with the CALLER's own session
 * (no MCP agent-account call at all for the load itself), but {@code generate} DOES make several MCP calls
 * to the {@code data-model} server (build the model - one build, no rehearsal since wave 2 lot V2-C - then
 * publish it, then create the dataset) - the same family of calls {@code LandingGenerator}'s own model step
 * measured at 80-100 seconds per source (CLAUDE.md &sect;15, "Where the time goes in Generate the landing").
 * A project with several accepted business objects can therefore comfortably clear the board's own "about
 * two minutes" estimate (see {@code ebx.MasterBuildRun}'s sibling Javadoc for the same reasoning on step 5) -
 * well past what a single HTTP postback should hold open. Chaining it synchronously was only ever an option
 * for an action that ALREADY ran synchronously and stayed short; this one does not.
 * <p>
 * Two stages only ({@link #MODEL}, {@link #LOAD}) - unlike {@link StagingProposalRun}'s six, neither is ever
 * skipped: a person can only reach this button once {@code gates.generateStaging} is enabled (at least one
 * accepted business object), and {@code StagingLoader.load} itself decides per row what to reject, never
 * refusing the whole run. If {@link #MODEL} fails, {@link #LOAD} is still attempted (unlike the proposal
 * run's dependency graph) - a re-run after a transient model-build failure should not need two clicks when
 * one enqueues both anyway, and {@code StagingLoader.load} fails cleanly with its own clear message if the
 * model genuinely never got published.
 */
public final class StagingLoadRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "STAGING_LOAD_RUN";

	public static final String MODEL = "MODEL";
	public static final String LOAD = "LOAD";
	public static final List<String> STAGES = java.util.Collections.unmodifiableList(Arrays.asList(StagingLoadRun.MODEL, StagingLoadRun.LOAD));

	private StagingLoadRun()
	{
	}

	/**
	 * Queues the full run (both stages) (lot C1, {@link FactoryJobQueue}) and returns at once: nothing is refused - a
	 * run already queued for the project absorbs this request, and a run of the same resource (STAGING) in progress
	 * simply goes first.
	 * <p>
	 * Volume-guards lot (2026-09-28, task item 2): the LOAD stage's own scope defaults to {@code new}
	 * (incremental) - see {@link #start(Session, Adaptation, String, List, boolean)}'s own Javadoc for why.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return StagingLoadRun.start(pSession, pFactory, pProjectCode, StagingLoadRun.STAGES, false);
	}

	/**
	 * Lot C3 (2026-09-24): same job kind/handler/resource as {@link #start(Session, Adaptation, String)}, restricted
	 * to {@code pStages} - the wizard's "Advanced: run generate/load separately" buttons (STAGING_GENERATE/
	 * STAGING_LOAD in {@code ProjectWizardUserService}) reuse this one run rather than a parallel implementation, so
	 * a stand-alone generate and the one-click build+load correctly serialize on the STAGING resource instead of
	 * racing the same model. A request with a different {@code pStages} is a different job (the queue's own
	 * dedup compares the full params JSON), so "Build the tables" and "Load the data" queued back to back both run,
	 * in order, never folded into one another.
	 * <p>
	 * Volume-guards lot (2026-09-28, task item 2): defaults the LOAD stage's scope to {@code new} - see the
	 * {@code (..., boolean)} overload's own Javadoc.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<String> pStages)
		throws OperationException
	{
		return StagingLoadRun.start(pSession, pFactory, pProjectCode, pStages, false);
	}

	/**
	 * Volume-guards lot (2026-09-28, task item 2, "staging build job defaults to scope new"): {@code pAllRows}
	 * chooses the LOAD stage's own scope, same meaning as {@code StagingLoader.load}'s own {@code pAllRows} and
	 * the {@code /staging/load?scope=} REST route's {@code scope} query parameter (kept consistent with that
	 * route - {@code scope=new}/{@code false} is the default everywhere in this module now, {@code all}/{@code
	 * true} only on an explicit request). Before this lot this job ALWAYS ran {@code StagingLoader.load(...,
	 * true, ...)} - full re-transform of every landing row, every time - which disabled the incremental path
	 * ({@code ./_status='NEW'}/{@code _sourceHash} skip, {@code docs/INCREMENTAL-TRANSFER.md}) for the ONE
	 * button the wizard actually drives day to day. On a project with millions of already-transferred landing
	 * rows this re-read and re-wrote every one of them on every single "Fill the shared structure" click,
	 * whether or not anything new had landed. The wizard exposes the {@code true} choice as a separate,
	 * unchecked-by-default "Recompute every row" checkbox next to the build button (needed after a mapping/
	 * cleaning-step/derived-field change, or the first load right after {@code StagingGenerator.generate}
	 * regenerated the staging model) - see {@code WizardPage#renderStagingLoadRun}/{@code
	 * #renderStagingGenerateLoadAdvanced} and {@code ProjectWizardUserService#loadStagingData}/{@code
	 * #startStagingLoadRun}. A cheap, reliable "the model was just regenerated, default to all" signal was
	 * considered (a {@code PipelineStep} row for {@link #MODEL} newer than the LOAD stage's own last DONE row)
	 * and deliberately NOT wired in this lot: {@code StagingGenerator.generate} is additive only (new tables/
	 * fields on an unchanged one do not retroactively invalidate already-transferred rows), so "a MODEL step ran
	 * more recently than the last LOAD" is not actually equivalent to "every row needs re-transforming" and
	 * would silently turn ordinary incremental runs into full ones after any stand-alone "Build the tables"
	 * click - left to the explicit checkbox instead, and documented here as the reason, not merely deferred.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<String> pStages, final boolean pAllRows)
		throws OperationException
	{
		return StagingLoadRun.start(pSession, pFactory, pProjectCode, pStages, pAllRows, false);
	}

	/**
	 * Staging preflight lot H1 (2026-10-17): {@code pBuildAnyway} is the person's "Build anyway" - the run then does NOT make the check before the build that would otherwise stop
	 * it when rows would be refused (see {@link #preflight}). Only written into the job's parameters when true, so every ordinary run keeps the very parameters (and the very
	 * queue deduplication) it had before.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<String> pStages, final boolean pAllRows,
		final boolean pBuildAnyway) throws OperationException
	{
		final Adaptation project = StagingLoadRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = StagingLoadRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("allRows", pAllRows);
		if (pBuildAnyway)
		{
			params.put("buildAnyway", true);
		}
		final ArrayNode stagesNode = params.putArray("stages");
		for (final String stage : pStages == null || pStages.isEmpty() ? StagingLoadRun.STAGES : pStages)
		{
			stagesNode.add(stage);
		}
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), StagingLoadRun.STEP, params);
		final ObjectNode out = StagingLoadRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId,
			final JsonNode pParams) throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final List<String> stages = StagingLoadRun.readStages(pParams);
			// volume-guards lot (2026-09-28, task item 2): missing "allRows" (a job queued before this lot, still
			// sitting in the queue across a deploy) defaults to false (new/incremental) - never silently upgraded
			// to a full re-transform by a field it never posted.
			final boolean allRows = pParams.path("allRows").asBoolean(false);
			final boolean buildAnyway = pParams.path("buildAnyway").asBoolean(false);
			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, StagingLoadRun.STEP, "");
			FactoryJobs.attach(pHandle, stepId);
			StagingLoadRun.updateDetail(pJobSession, pFactory, stepId, RunProgress.newRun(StagingLoadRun.STAGES));
			return StagingLoadRun.runInBackground(pJobSession, pFactory, pProjectId, projectCode, stepId, pHandle, stages, allRows, buildAnyway);
		}
	};

	/** {@code pParams.stages}, or every stage (the full run) when the field is missing/empty - covers a job queued before lot C3. */
	private static List<String> readStages(final JsonNode pParams)
	{
		final JsonNode stagesNode = pParams.path("stages");
		if (!stagesNode.isArray() || stagesNode.size() == 0)
		{
			return StagingLoadRun.STAGES;
		}
		final List<String> out = new ArrayList<String>();
		for (final JsonNode stage : stagesNode)
		{
			out.add(stage.asText());
		}
		return out;
	}

	/** Current state of the latest run of {@code pProjectCode} (if any) - same shape as {@link StagingProposalRun#status}. Never throws for "no run yet": {@code exists=false}. */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = StagingLoadRun.MAPPER.createObjectNode();
		final Adaptation project = StagingLoadRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			out.put("exists", false);
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation job = FactoryJobQueue.latest(pFactory, projectId, StagingLoadRun.STEP);
		final boolean queued = job != null && FactoryJobQueue.QUEUED.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		final Adaptation row = StagingLoadRun.latestRow(pFactory, projectId);
		if (row == null && !queued)
		{
			out.put("exists", false);
			return out;
		}
		final String status = queued ? FactoryJobQueue.QUEUED : row.getString(FactoryPaths.PipelineStep.STATUS);
		// lot C1: RUNNING only while a job really runs it (an orphan row is INTERRUPTED at module start); QUEUED counts as running for the page
		final boolean running = queued || "RUNNING".equals(status) && FactoryJobs.isLive(projectId, StagingLoadRun.STEP);
		out.put("exists", true);
		out.put("status", status);
		out.put("running", running);
		out.put("queued", queued);
		if (job != null)
		{
			out.put("jobId", job.getOccurrencePrimaryKey().format());
		}
		out.put("stale", false);
		if (queued)
		{
			out.put("message", "Queued: waiting for the staging of this project to be free.");
			out.put("detailJson", "");
			return out;
		}
		final String message = row.getString(FactoryPaths.PipelineStep.MESSAGE);
		if (message != null)
		{
			out.put("message", message);
		}
		final String detail = row.getString(FactoryPaths.PipelineStep.DETAIL);
		out.put("detailJson", detail == null ? "" : detail);
		return out;
	}

	// ------------------------------------------------------------------------------------------------ the run itself

	private static String runInBackground(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode,
		final String pStepId, final FactoryJobs.Handle pHandle, final List<String> pStages, final boolean pAllRows, final boolean pBuildAnyway)
		throws FactoryJobQueue.PartialOutcome
	{
		final RunProgress progress = RunProgress.newRun(StagingLoadRun.STAGES);
		// staging-live lot (2026-09-27): every change is written at once - the running screen reads this detail
		// every few seconds through GET .../jobs (progressJson)
		progress.onChange(() -> StagingLoadRun.updateDetail(pSession, pFactory, pStepId, progress), RunProgress.LIVE_WRITE_INTERVAL_MS);
		progress.note(RunTexts.buildAccepted().stored());
		final boolean runModel = pStages.contains(StagingLoadRun.MODEL);
		final boolean runLoad = pStages.contains(StagingLoadRun.LOAD);
		try
		{
			// staging preflight lot H1 (2026-10-17): before the model is touched or a row is written
			if (runLoad)
			{
				final String blocked = StagingLoadRun.preflight(pSession, pFactory, pProjectCode, pStepId, pHandle, progress, pBuildAnyway);
				if (blocked != null)
				{
					return blocked;
				}
			}
			final boolean modelOk;
			if (runModel)
			{
				modelOk = StagingLoadRun.runStage(pSession, pFactory, pStepId, pHandle, progress, StagingLoadRun.MODEL, new Stage()
				{
					@Override
					public String run() throws OperationException
					{
						final ObjectNode result = StagingGenerator.generate(pSession, pFactory, pProjectCode, pText -> progress.activity(StagingLoadRun.MODEL, pText));
						return RunTexts.stagingModelAt(result.path("stagingDataspace").asText(""), result.path("stagingDataset").asText("")).stored();
					}
				});
			}
			else
			{
				// lot C3 (STAGING_LOAD alone): not a failure - the stand-alone build-tables button simply was not
				// asked for on this run.
				progress.skip(StagingLoadRun.MODEL, RunTexts.notRequested().stored());
				StagingLoadRun.updateDetail(pSession, pFactory, pStepId, progress);
				modelOk = true;
			}

			// Deliberately attempted even if MODEL failed - see this class's own Javadoc: a re-run after a
			// transient model-build failure only needs one click, and StagingLoader.load fails cleanly with
			// its own message when the model genuinely was not published.
			final boolean loadOk;
			final ObjectNode[] partialLoad = new ObjectNode[1];
			if (runLoad)
			{
				loadOk = StagingLoadRun.runStage(pSession, pFactory, pStepId, pHandle, progress, StagingLoadRun.LOAD, new Stage()
				{
					@Override
					public String run() throws OperationException
					{
						// volume-guards lot (2026-09-28, task item 2): pAllRows, not a hardcoded true - see #start's own Javadoc.
						// volume lot 3 (task 8): pHandle threaded through so a stop mid-load is honoured per batch,
						// not only between the MODEL/LOAD stages.
						final ObjectNode result = StagingLoader.load(pSession, pFactory, pProjectCode, pAllRows, StagingLoadRun.listener(progress), pHandle);
						partialLoad[0] = JobOutcomes.stagingPartial(result) ? result : null; // F2-06: a closing write that was refused is a PARTIAL job, not a green one
						// every row read was rejected: the STAGING_LOAD step ends FAILED, so this stage must not report a success the step contradicts
						if ("FAILED".equals(result.path("status").asText("")))
						{
							throw EngineRefusal.of("engine.allRowsRejected", "Every one of the " + result.path("read").asInt(0) + " source row(s) read was rejected, nothing was written: "
								+ "see the rejected rows for the reasons.", result.path("read").asInt(0));
						}
						// lot V3-S: a scope all load of unchanged data writes nothing - say how many rows it found up to date, never just "0 row(s) written"
						return RunTexts.rowsWritten(result.path("written").asInt(0), result.path("read").asInt(0), result.path("unchanged").asInt(0), result.path("rejected").asInt(0)).stored();
					}
				});
			}
			else
			{
				// lot C3 (STAGING_GENERATE alone): same "not requested" posture as MODEL above.
				progress.skip(StagingLoadRun.LOAD, RunTexts.notRequested().stored());
				StagingLoadRun.updateDetail(pSession, pFactory, pStepId, progress);
				loadOk = true;
			}

			// live bug fix (coordinator, 2026-09-24): runStage already swallows every exception into a FAILED
			// stage (by design - "one stage's own bug must never take the rest of the run down with it"), but
			// this method used to then unconditionally report the PARENT step "Staging build finished." with
			// success=true regardless of what runStage actually returned - so a run whose LOAD stage crashed
			// (a MalformedXPathPredicateException from StagingLoader.readMappings, seen live on Vague1) still
			// read as a clean, successful finish, with no failure recorded anywhere the wizard could show. The
			// parent step now reports FAILED, with a plain-language reason naming which stage(s) did not
			// complete, whenever either stage did not finish successfully.
			if (modelOk && loadOk)
			{
				// lot C3: a single-stage run (STAGING_GENERATE/STAGING_LOAD) reports that stage's own summary as
				// the job's result message, matching the sentence the synchronous action used to show - the
				// combined run keeps its own generic "finished" sentence, since it covers both.
				// staging screens (2026-10-17): the step's own message stays plain English (REST, cockpit); the job's message is the coded text the screens word in the viewer's language
				final String message;
				if (runModel && runLoad)
				{
					message = RunTexts.buildFinished().stored();
				}
				else
				{
					final RunProgress.Stage stage = progress.stage(runModel ? StagingLoadRun.MODEL : StagingLoadRun.LOAD);
					message = stage != null && stage.summary != null ? stage.summary : RunTexts.buildFinished().stored();
				}
				final FactoryJobQueue.PartialOutcome partial = JobOutcomes.stagingOutcome(partialLoad[0], EngineMessageCodec.decode(message).raw);
				if (partial != null)
				{
					PipelineSteps.endPartial(pSession, pFactory, pStepId, partial.getMessage(), progress.toJson());
					throw partial;
				}
				PipelineSteps.end(pSession, pFactory, pStepId, true, EngineMessageCodec.decode(message).raw, progress.toJson());
				return message;
			}
			else
			{
				final KeyTexts.Text ended = pHandle.cancelled() ? RunTexts.buildStopped(pHandle.stopReason()) : RunTexts.buildFailedStages(!modelOk, !loadOk);
				final String message = ended.stored();
				if (pHandle.cancelled())
				{
					PipelineSteps.endStopped(pSession, pFactory, pStepId, ended.english, progress.toJson());
				}
				else
				{
					PipelineSteps.end(pSession, pFactory, pStepId, false, ended.english, progress.toJson());
				}
				if (!pHandle.cancelled())
				{
					throw new IllegalStateException(message);
				}
				return message;
			}
		}
		catch (final FactoryJobQueue.PartialOutcome partial)
		{
			throw partial; // F2-06: already recorded on the step above; the job ends DONE_WITH_FAILURES
		}
		catch (final Exception ex) // deliberately broad - a background thread's own bug must never go unrecorded, see StagingProposalRun's own Javadoc
		{
			LoggingCategory.getKernel().error("[mdm-factory] staging load run failed for project " + pProjectCode + ": " + ex, ex);
			try
			{
				PipelineSteps.end(pSession, pFactory, pStepId, false, RunTexts.buildFailed(ex.getMessage()).english, progress.toJson());
			}
			catch (final Exception logged)
			{
				LoggingCategory.getKernel()
					.error("[mdm-factory] could not even record the failure of staging load run for project " + pProjectCode + ": " + logged, logged);
			}
			throw new IllegalStateException(RunTexts.buildFailed(ex.getMessage()).stored(), ex);
		}
	}

	/**
	 * Staging preflight lot H1 (2026-10-17, docs/STAGING-PREFLIGHT.md): the check before the build. Replays every ACCEPTED link on the landing values ({@link StagingPreflight}) and
	 * stores the report; when rows would be REFUSED the run ends here, before the model is touched and before any row is written - the step is recorded SKIPPED (neither done nor
	 * failed: the job reads "Nothing to do", amber) with the coded sentence, and the report shows at the top of step 4 with "Apply the fix" / "Build anyway". Warnings never stop it.
	 * <p>
	 * <b>Fails open</b>: a check that cannot run (an unreadable column, a bug) says so in the journal and the build goes on - the check is a safety net, never a new way for a
	 * build to be lost. A person's {@code pBuildAnyway} sets it aside altogether (the journal says so).
	 *
	 * @return the job's message when the build was stopped, null when it goes on
	 */
	private static String preflight(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pStepId, final FactoryJobs.Handle pHandle,
		final RunProgress pProgress, final boolean pBuildAnyway)
	{
		if (pBuildAnyway)
		{
			pProgress.note(RunTexts.preflightSetAside().stored());
			StagingLoadRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return null;
		}
		pProgress.note(RunTexts.preflightChecking().stored());
		StagingLoadRun.updateDetail(pSession, pFactory, pStepId, pProgress);
		final com.on.ebx.factory.staging.PreflightReport report;
		try
		{
			report = StagingPreflight.runAndStore(pSession, pFactory, pProjectCode, null, () -> pHandle.cancelled());
		}
		catch (final Exception ex) // fail open, see above
		{
			LoggingCategory.getKernel().warn("[mdm-factory] the check before the staging build of " + pProjectCode + " could not run: " + ex);
			pProgress.note(RunTexts.preflightUnavailable(TextLimits.truncate(ErrorText.of(ex), 200)).stored());
			StagingLoadRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return null;
		}
		if (!report.blocking())
		{
			pProgress.note(report.clean() ? RunTexts.preflightClean().stored() : RunTexts.preflightWarnings(report.warnings()).stored());
			StagingLoadRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return null;
		}
		final KeyTexts.Text ended = RunTexts.preflightBlocked(report.blockingRows(), report.blockingLinks());
		pProgress.note(ended.stored());
		PipelineSteps.endSkipped(pSession, pFactory, pStepId, ended.english, pProgress.toJson());
		return ended.stored();
	}

	/**
	 * Staging-live lot (2026-09-27): feeds the run's grid (one cell per business object and application, real
	 * rows after each batch) and says in the LOAD stage's activity which cell runs and where it stands.
	 */
	private static com.on.ebx.factory.staging.StagingLoadListener listener(final RunProgress pProgress)
	{
		return new com.on.ebx.factory.staging.StagingLoadListener()
		{
			@Override
			public void planned(final String pEntity, final String pSource)
			{
				pProgress.unitPlanned(pEntity, pSource);
			}

			@Override
			public void started(final String pEntity, final String pSource, final long pTotalRows)
			{
				pProgress.unitStart(pEntity, pSource, pTotalRows);
				if (pTotalRows >= 0L)
				{
					pProgress.activity(StagingLoadRun.LOAD, RunTexts.reading(pEntity, pSource, pTotalRows).stored());
				}
			}

			@Override
			public void progress(final String pEntity, final String pSource, final long pRead, final long pWritten, final long pHeld)
			{
				this.progress(pEntity, pSource, pRead, pWritten, 0L, pHeld);
			}

			@Override
			public void progress(final String pEntity, final String pSource, final long pRead, final long pWritten, final long pUnchanged, final long pHeld)
			{
				final RunProgress.Unit unit = pProgress.unit(pEntity, pSource);
				pProgress.unitProgress(pEntity, pSource, pRead, pWritten, pUnchanged, pHeld);
				if (unit != null && unit.total >= 0L)
				{
					pProgress.activity(StagingLoadRun.LOAD, RunTexts.loadProgress(pEntity, pSource, pRead, unit.total, pWritten, pUnchanged, pHeld).stored());
				}
			}

			@Override
			public void ended(final String pEntity, final String pSource, final boolean pOk)
			{
				pProgress.unitEnd(pEntity, pSource, pOk);
			}

			@Override
			public void note(final String pText)
			{
				pProgress.activity(StagingLoadRun.LOAD, pText);
			}
		};
	}

	private interface Stage
	{
		String run() throws OperationException;
	}

	private static boolean runStage(final Session pSession, final Adaptation pFactory, final String pStepId, final FactoryJobs.Handle pHandle,
		final RunProgress pProgress, final String pKey, final Stage pStage)
	{
		if (pHandle.cancelled())
		{
			// lot C1: a stop asked between two stages - the remaining stages are recorded as not run
			pProgress.skip(pKey, RunTexts.notRun(pHandle.stopReason()).stored());
			StagingLoadRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return false;
		}
		pProgress.start(pKey, System.currentTimeMillis());
		StagingLoadRun.updateDetail(pSession, pFactory, pStepId, pProgress);
		try
		{
			final String summary = pStage.run();
			// volume lot 3 (2026-09-28, task 8): a stop asked WHILE this stage was running (StagingLoader now
			// checks pHandle per BATCH, not only between stages) returns normally with a partial summary, never
			// an exception - checked here so it is never mistaken for a clean finish: this stage reports itself
			// FAILED (in this run's own accounting only - the parent step's own message says "stopped", not
			// "failed", via pHandle.cancelled() in runInBackground's own final check).
			final boolean stoppedDuringThisStage = pHandle.cancelled();
			pProgress.finish(pKey, !stoppedDuringThisStage, stoppedDuringThisStage ? RunTexts.stoppedMidRun(summary).stored() : summary, System.currentTimeMillis());
			StagingLoadRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return !stoppedDuringThisStage;
		}
		catch (final Exception ex) // deliberately broad - one stage's own bug must never take the rest of the run down with it
		{
			pProgress.finish(pKey, false, TextLimits.truncate(ErrorText.of(ex), 500), System.currentTimeMillis());
			StagingLoadRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return false;
		}
	}

	/** Best-effort partial update of the parent step's {@code detail} only - same posture as {@link PipelineSteps#end}, logged not thrown. */
	private static void updateDetail(final Session pSession, final Adaptation pFactory, final String pStepId, final RunProgress pProgress)
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
			LoggingCategory.getKernel().error("[mdm-factory] could not update the progress of step " + pStepId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/** The most recently STARTED {@link #STEP} row for {@code pProjectId} - project-wide scope (""), same idea as {@link StagingProposalRun#latestRow}. */
	private static Adaptation latestRow(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(StagingLoadRun.STEP) + " and "
				+ PipelineSteps.scopeEquals("./scope", ""));
		try
		{
			Adaptation latest = null;
			Date latestStartedAt = null;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Date startedAt = (Date) row.get(FactoryPaths.PipelineStep.STARTED_AT);
				if (latest == null || (startedAt != null && (latestStartedAt == null || startedAt.after(latestStartedAt))))
				{
					latest = row;
					latestStartedAt = startedAt;
				}
			}
			return latest;
		}
		finally
		{
			rows.close();
		}
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
}
