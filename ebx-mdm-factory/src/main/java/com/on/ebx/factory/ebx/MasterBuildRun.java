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
import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.master.MatchingPauseSummary;
import com.on.ebx.factory.staging.RunProgress;
import com.on.ebx.factory.landing.RunTexts;
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
 * Wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/MasterStart.dc.html + MasterDone.dc.html):
 * the one-click "Build the master" button of step 5 - same background-daemon shape as {@link
 * StagingLoadRun}/{@code StagingProposalRun}, three named stages matching the board's own "what the factory
 * does, in one run" cards:
 * <ol>
 * <li>{@link #TABLES} - {@code MasterGenerator.generate(..., false)}: builds/publishes the master model (one
 * table per promoted business object) and creates the dataset. This is ALSO where the model gets declared to
 * the Match and Merge add-on ({@code MasterGenerator}'s own {@code add_addon} call, "INERT until the model is
 * republished" - and this same call republishes it) - there is no separate, independently-callable step for
 * that half of the work, see {@link #MATCHING_READY}'s own note below.</li>
 * <li>{@link #COPY} - {@code MasterLoader.load(...)}: copies every promoted business object's staging rows
 * into the master, cross-references kept.</li>
 * <li>{@link #MATCHING_READY} - NOT a separate EBX/MCP call: {@link #TABLES} already declared the add-on on
 * the model (see above), so this stage is marked done right after {@link #TABLES} succeeds, its summary built
 * from {@link #TABLES}'s own result (the real {@code entitiesPromoted} count {@code MasterGenerator.generate}
 * returned - never a fabricated number). Actually PREPARING the tables to run a match (the metadataset,
 * trigger attach) is {@code match-merge}'s {@code activate_matching}, called from step 6's "Configure the
 * matching" ({@code MatchingConfigurator}) - not duplicated here on purpose, since running it before a person
 * has even reached the matching step would just be extra MCP calls for no benefit the wizard can show yet.
 * </ol>
 * <p>
 * <b>Why background (task brief question), same reasoning as {@link StagingLoadRun}:</b> {@link #TABLES}
 * makes several MCP calls to the {@code data-model} server (build/publish the model, declare the add-on,
 * create the dataset) - the board's OWN text says "About two minutes", which is consistent with the
 * model-build timings CLAUDE.md &sect;15 measured for the sibling landing-model step (80-100s) - well past a
 * single HTTP postback. {@link #COPY} runs with the caller's own session, no MCP call, like {@code
 * StagingLoader.load}.
 */
public final class MasterBuildRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "MASTER_BUILD_RUN";

	public static final String TABLES = "TABLES";
	public static final String COPY = "COPY";
	public static final String MATCHING_READY = "MATCHING_READY";
	public static final List<String> STAGES =
		java.util.Collections.unmodifiableList(Arrays.asList(MasterBuildRun.TABLES, MasterBuildRun.COPY, MasterBuildRun.MATCHING_READY));

	private MasterBuildRun()
	{
	}

	/** Queues the full run (all stages) (lot C1, {@link FactoryJobQueue}, resource MASTER) and returns at once - never refused, see {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return MasterBuildRun.start(pSession, pFactory, pProjectCode, MasterBuildRun.STAGES);
	}

	/**
	 * Lot C3 (2026-09-24), same idea as {@link StagingLoadRun#start(Session, Adaptation, String, List)}: the
	 * wizard's "Advanced: run generate/load separately" buttons (MASTER_GENERATE/MASTER_LOAD in {@code
	 * ProjectWizardUserService}) reuse this one run restricted to {@link #TABLES} or {@link #COPY} - so a
	 * stand-alone generate and the one-click "Build the master" serialize on the MASTER resource instead of racing
	 * the same model. {@link #MATCHING_READY} is never itself selectable: it is not a separate call (see this
	 * class's own Javadoc) and stays tied to {@link #TABLES}, whichever way {@link #TABLES} was reached.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<String> pStages)
		throws OperationException
	{
		return MasterBuildRun.start(pSession, pFactory, pProjectCode, pStages, null);
	}

	/**
	 * Lot M-INC (2026-10-12): the same with the master load's scope ({@code changed} / {@code full}, see {@link MasterLoader#load(Session, Adaptation, String, String)}). A blank
	 * scope is left out of the job's parameters, so the job is the very one an earlier build queued (and merges with an identical queued one) and the load takes the instance default.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final List<String> pStages, final String pScope)
		throws OperationException
	{
		final Adaptation project = MasterBuildRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = MasterBuildRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		if (pScope != null && !pScope.trim().isEmpty())
		{
			params.put("scope", pScope.trim());
		}
		final ArrayNode stagesNode = params.putArray("stages");
		for (final String stage : pStages == null || pStages.isEmpty() ? MasterBuildRun.STAGES : pStages)
		{
			stagesNode.add(stage);
		}
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), MasterBuildRun.STEP, params);
		final ObjectNode out = MasterBuildRun.MAPPER.createObjectNode();
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
			final List<String> stages = MasterBuildRun.readStages(pParams);
			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, MasterBuildRun.STEP, "");
			FactoryJobs.attach(pHandle, stepId);
			MasterBuildRun.updateDetail(pJobSession, pFactory, stepId, RunProgress.newRun(MasterBuildRun.STAGES));
			return MasterBuildRun.runInBackground(pJobSession, pFactory, pProjectId, projectCode, stepId, pHandle, stages, pParams.path("scope").asText(""));
		}
	};

	/** {@code pParams.stages}, or every stage (the full run) when the field is missing/empty - covers a job queued before lot C3. */
	private static List<String> readStages(final JsonNode pParams)
	{
		final JsonNode stagesNode = pParams.path("stages");
		if (!stagesNode.isArray() || stagesNode.size() == 0)
		{
			return MasterBuildRun.STAGES;
		}
		final List<String> out = new ArrayList<String>();
		for (final JsonNode stage : stagesNode)
		{
			out.add(stage.asText());
		}
		return out;
	}

	/** Same shape as {@link StagingLoadRun#status}. Never throws for "no run yet": {@code exists=false}. */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = MasterBuildRun.MAPPER.createObjectNode();
		final Adaptation project = MasterBuildRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			out.put("exists", false);
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation job = FactoryJobQueue.latest(pFactory, projectId, MasterBuildRun.STEP);
		final boolean queued = job != null && FactoryJobQueue.QUEUED.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		final Adaptation row = MasterBuildRun.latestRow(pFactory, projectId);
		if (row == null && !queued)
		{
			out.put("exists", false);
			return out;
		}
		final String status = queued ? FactoryJobQueue.QUEUED : row.getString(FactoryPaths.PipelineStep.STATUS);
		// lot C1: RUNNING only while a job really runs it; QUEUED counts as running for the page
		final boolean running = queued || "RUNNING".equals(status) && FactoryJobs.isLive(projectId, MasterBuildRun.STEP);
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
			out.put("message", RunTexts.masterQueued().english);
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
		final String pStepId, final FactoryJobs.Handle pHandle, final List<String> pStages, final String pScope)
	{
		final RunProgress progress = RunProgress.newRun(MasterBuildRun.STAGES);
		// master-live lot (2026-09-27): every change is written at once - step 5's running screen reads this detail
		// through GET .../jobs (progressJson) every few seconds, same as step 4's
		progress.onChange(() -> MasterBuildRun.updateDetail(pSession, pFactory, pStepId, progress), RunProgress.LIVE_WRITE_INTERVAL_MS);
		progress.note(RunTexts.buildAccepted().stored());
		final boolean runTables = pStages.contains(MasterBuildRun.TABLES);
		final boolean runCopy = pStages.contains(MasterBuildRun.COPY);
		final int[] entitiesPromotedCount = { 0 };
		try
		{
			final boolean tablesOk;
			if (runTables)
			{
				tablesOk = MasterBuildRun.runStage(pSession, pFactory, pStepId, pHandle, progress, MasterBuildRun.TABLES, new Stage()
				{
					@Override
					public String run() throws OperationException
					{
						final ObjectNode result = MasterGenerator.generate(pSession, pFactory, pProjectCode, false, pText -> progress.activity(MasterBuildRun.TABLES, pText));
						final JsonNode entities = result.path("entitiesPromoted");
						entitiesPromotedCount[0] = entities.isArray() ? entities.size() : 0;
						return RunTexts.masterTables(entitiesPromotedCount[0], result.path("masterDataspace").asText(""), result.path("masterDataset").asText("")).stored();
					}
				});
			}
			else
			{
				// lot C3 (MASTER_LOAD alone): not a failure - the stand-alone create-tables button simply was not
				// asked for on this run; the master tables are assumed to already exist from an earlier run.
				progress.skip(MasterBuildRun.TABLES, RunTexts.notRequested().stored());
				MasterBuildRun.updateDetail(pSession, pFactory, pStepId, progress);
				tablesOk = true;
			}

			boolean copyOk = true;
			if (runTables && !tablesOk)
			{
				copyOk = false;
				progress.skip(MasterBuildRun.COPY, RunTexts.masterNoTablesCopy().stored());
				progress.skip(MasterBuildRun.MATCHING_READY, RunTexts.masterNoTablesMatching().stored());
				MasterBuildRun.updateDetail(pSession, pFactory, pStepId, progress);
			}
			else if (!runCopy)
			{
				// lot C3 (MASTER_GENERATE alone): same "not requested" posture as TABLES above.
				progress.skip(MasterBuildRun.COPY, RunTexts.notRequested().stored());
				MasterBuildRun.matchingReady(pSession, pFactory, pStepId, progress, runTables, entitiesPromotedCount[0]);
			}
			else
			{
				copyOk = MasterBuildRun.runStage(pSession, pFactory, pStepId, pHandle, progress, MasterBuildRun.COPY, new Stage()
				{
					@Override
					public String run() throws OperationException
					{
						// volume lot 3 (2026-09-28, task 8): pHandle threaded through so a stop mid-load is
						// honoured per batch, not only between stages.
						final ObjectNode result = MasterLoader.load(pSession, pFactory, pProjectCode, MasterBuildRun.listener(progress), pHandle, pScope);
						// every row read was rejected (master.MasterLoadOutcome): the MASTER_LOAD step ends FAILED, so this stage must not report a success the step contradicts
						if ("FAILED".equals(result.path("status").asText("")))
						{
							throw EngineRefusal.of("engine.allRowsRejected", "Every one of the " + result.path("read").asInt(0) + " source row(s) read was rejected, nothing was written: "
								+ "see the rejected rows for the reasons.", result.path("read").asInt(0));
						}
						// lot V3-M (2026-10-04): "written" is what was really written; a row already holding every value is "unchanged"
						// lot M-INC (2026-10-12): the scope the load really ran in - and "nothing changed" when a changed load found an empty journal
						final String ranScope = result.path("scope").asText("full");
						final String copied = result.path("nothingChanged").asBoolean(false) ? RunTexts.masterNothingChanged().stored()
							: "full".equals(ranScope) ? RunTexts.rowsWritten(result.path("written").asInt(0), result.path("read").asInt(0), result.path("unchanged").asInt(0), result.path("rejected").asInt(0)).stored()
								: RunTexts.masterRowsWrittenScope(result.path("written").asInt(0), result.path("read").asInt(0), result.path("unchanged").asInt(0), result.path("rejected").asInt(0),
									ranScope, result.path("journalRows").asLong(0L)).stored();
						// lot MP (2026-10-04): a REALTIME project's load pauses the add-on's matching and catches up afterwards - a warning (a table left paused...) first, never silent
						final String pauseWarning = MatchingPauseSummary.warning(result.path("matchingPause"));
						final String pauseSentence = MatchingPauseSummary.sentence(result.path("matchingPause"));
						// the matching pause's own sentences are free text (they name tables and add-on states): joined to the coded summary, which is worded in the viewer's language
						String summary = copied;
						if (!pauseWarning.isEmpty())
						{
							summary = RunTexts.joined(pauseWarning, summary).stored();
						}
						if (!pauseSentence.isEmpty())
						{
							summary = RunTexts.joined(summary, pauseSentence).stored();
						}
						return summary;
					}
				});
				MasterBuildRun.matchingReady(pSession, pFactory, pStepId, progress, runTables, entitiesPromotedCount[0]);
			}

			// live bug fix (coordinator, 2026-09-24): same pattern found and fixed in ebx.StagingLoadRun - this
			// used to report the parent step "Master build finished." with success=true unconditionally,
			// ignoring tablesOk/copyOk entirely (MATCHING_READY is deliberately always true once TABLES
			// succeeded - see its own comment above - so it is not part of this check). A run whose TABLES or
			// COPY stage failed now reports FAILED with a plain-language reason instead of a false "finished".
			if (tablesOk && copyOk)
			{
				// lot C3: a single-stage run (MASTER_GENERATE/MASTER_LOAD) reports that stage's own summary as the
				// job's result message, matching the sentence the synchronous action used to show.
				// run texts i18n (2026-10-18): the step's own message stays plain English (REST, cockpit); the job's message is the coded text the screens word in the viewer's language
				final String message;
				if (runTables && runCopy)
				{
					message = RunTexts.masterBuildFinished().stored();
				}
				else
				{
					final RunProgress.Stage stage = progress.stage(runTables ? MasterBuildRun.TABLES : MasterBuildRun.COPY);
					message = stage != null && stage.summary != null ? stage.summary : RunTexts.masterBuildFinished().stored();
				}
				PipelineSteps.end(pSession, pFactory, pStepId, true, EngineMessageCodec.decode(message).raw, progress.toJson());
				return message;
			}
			else
			{
				final KeyTexts.Text ended = pHandle.cancelled() ? RunTexts.masterBuildStopped(pHandle.stopReason()) : RunTexts.masterBuildFailedStages(!tablesOk, !copyOk);
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
		catch (final Exception ex) // deliberately broad - see StagingProposalRun's own Javadoc for why
		{
			LoggingCategory.getKernel().error("[mdm-factory] master build run failed for project " + pProjectCode + ": " + ex, ex);
			try
			{
				PipelineSteps.end(pSession, pFactory, pStepId, false, RunTexts.masterBuildFailed(ex.getMessage()).english, progress.toJson());
			}
			catch (final Exception logged)
			{
				LoggingCategory.getKernel()
					.error("[mdm-factory] could not even record the failure of master build run for project " + pProjectCode + ": " + logged, logged);
			}
			throw new IllegalStateException(RunTexts.masterBuildFailed(ex.getMessage()).stored(), ex);
		}
	}

	/**
	 * Lot C3: {@link #MATCHING_READY} is not a separate EBX/MCP call (see this class's own Javadoc) - it is marked
	 * done right after {@link #TABLES}, whether {@link #TABLES} ran this pass and succeeded, or the run only asked
	 * for {@link #COPY} on tables that were already declared by an earlier run (nothing new to report, so it stays
	 * {@code SKIPPED} with "not requested" - see {@link #runInBackground}'s own callers of this method).
	 */
	private static void matchingReady(final Session pSession, final Adaptation pFactory, final String pStepId, final RunProgress pProgress,
		final boolean pTablesRanThisPass, final int pEntitiesPromotedCount)
	{
		if (pTablesRanThisPass)
		{
			pProgress.start(MasterBuildRun.MATCHING_READY, System.currentTimeMillis());
			pProgress.finish(MasterBuildRun.MATCHING_READY, true, RunTexts.masterDeclared(pEntitiesPromotedCount).stored(), System.currentTimeMillis());
		}
		else
		{
			pProgress.skip(MasterBuildRun.MATCHING_READY, RunTexts.notRequested().stored());
		}
		MasterBuildRun.updateDetail(pSession, pFactory, pStepId, pProgress);
	}

	private interface Stage
	{
		String run() throws OperationException;
	}

	/** Master-live lot (2026-09-27): feeds one COPY cell per business object with real rows after each batch, and says where the copy stands. */
	private static com.on.ebx.factory.staging.StagingLoadListener listener(final RunProgress pProgress)
	{
		return new com.on.ebx.factory.staging.StagingLoadListener()
		{
			@Override
			public void planned(final String pEntity, final String pCell)
			{
				pProgress.unitPlanned(pEntity, pCell);
			}

			@Override
			public void started(final String pEntity, final String pCell, final long pTotalRows)
			{
				pProgress.unitStart(pEntity, pCell, pTotalRows);
				pProgress.activity(MasterBuildRun.COPY, RunTexts.masterCopying(pEntity, pTotalRows).stored());
			}

			@Override
			public void progress(final String pEntity, final String pCell, final long pRead, final long pWritten, final long pHeld)
			{
				final RunProgress.Unit unit = pProgress.unit(pEntity, pCell);
				pProgress.unitProgress(pEntity, pCell, pRead, pWritten, pHeld);
				pProgress.activity(MasterBuildRun.COPY, RunTexts.masterProgress(pEntity, pRead, unit != null ? unit.total : -1L, pWritten, pHeld).stored());
			}

			@Override
			public void ended(final String pEntity, final String pCell, final boolean pOk)
			{
				pProgress.unitEnd(pEntity, pCell, pOk);
			}

			@Override
			public void note(final String pText)
			{
				pProgress.activity(MasterBuildRun.COPY, pText);
			}
		};
	}

	private static boolean runStage(final Session pSession, final Adaptation pFactory, final String pStepId, final FactoryJobs.Handle pHandle,
		final RunProgress pProgress, final String pKey, final Stage pStage)
	{
		if (pHandle.cancelled())
		{
			// lot C1: a stop asked between two stages - the remaining stages are recorded as not run
			pProgress.skip(pKey, RunTexts.notRun(pHandle.stopReason()).stored());
			MasterBuildRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return false;
		}
		pProgress.start(pKey, System.currentTimeMillis());
		MasterBuildRun.updateDetail(pSession, pFactory, pStepId, pProgress);
		try
		{
			final String summary = pStage.run();
			// volume lot 3 (2026-09-28, task 8): a stop asked WHILE this stage was running (MasterLoader now
			// checks pHandle per BATCH, not only between stages) returns normally with a partial summary, never
			// an exception - see ebx.StagingLoadRun#runStage's identical matching fix.
			final boolean stoppedDuringThisStage = pHandle.cancelled();
			pProgress.finish(pKey, !stoppedDuringThisStage, stoppedDuringThisStage ? RunTexts.stoppedMidRun(summary).stored() : summary, System.currentTimeMillis());
			MasterBuildRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return !stoppedDuringThisStage;
		}
		catch (final Exception ex) // deliberately broad - one stage's own bug must never take the rest of the run down with it
		{
			pProgress.finish(pKey, false, TextLimits.truncate(ErrorText.of(ex), 500), System.currentTimeMillis());
			MasterBuildRun.updateDetail(pSession, pFactory, pStepId, pProgress);
			return false;
		}
	}

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

	private static Adaptation latestRow(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(MasterBuildRun.STEP) + " and "
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
