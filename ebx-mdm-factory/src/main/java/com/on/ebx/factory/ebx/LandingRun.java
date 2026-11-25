package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.KeyCertainty;
import com.on.ebx.factory.landing.KeyEvidence;
import com.on.ebx.factory.landing.KeyStudyReport;
import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.LandingChainOutcome;
import com.on.ebx.factory.landing.LandingKeyRules;
import com.on.ebx.factory.landing.LandingParallelism;
import com.on.ebx.factory.landing.LandingRunProgress;
import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.landing.ValidatedKey;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.wizard.PartialFailures;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * Sources+landing redesign, lot LANDING (2026-09-24, docs/SOURCES-LANDING-LANDING.md): the one-click
 * "Prepare the landing" button. Jobs-queue lot (2026-09-24): the run is now two job kinds of
 * {@link FactoryJobQueue} (resource {@link FactoryJobs#LANDING}) instead of its own daemon executor - see
 * {@code ebx.StagingLoadRun}'s own Javadoc for the shape every migrated {@code *Run} class now shares.
 * {@link #STEP} is the main run ({@link #HANDLER}); {@link #RESUME_STEP} ({@link #RESUME_HANDLER}) is one
 * table's continuation after a person resolves a {@code WAITING} key - both write into the SAME parent
 * {@code PipelineStep} row, and the queue's one-job-per-resource rule is what now guarantees only one of
 * them ever touches that row at a time (the old design let a resume's own {@code Runnable} run concurrently
 * with the main run's, both parsing/rewriting the row's {@code detail} independently - a real race the
 * queue removes for free).
 * <p>
 * Progress is a {@link LandingRunProgress} grid: one row per SELECTED {@code LandingTable} of the project,
 * four columns ({@link LandingRunProgress#KEY}/{@link LandingRunProgress#TABLE}/{@link
 * LandingRunProgress#FLOW}/{@link LandingRunProgress#LOAD}).
 * <ul>
 * <li>{@link LandingRunProgress#KEY} runs per TABLE, independently: a table whose key is already {@code
 * USER}/{@code SOURCE} (or whose landing was already generated - a key can no longer change once it left
 * {@code PROPOSED}, see {@code WizardData.setTableKey}) passes at once; otherwise {@link KeyStudy#study} runs
 * (the language-model half of it gated by {@code pUseLlm}, still administrators-only and connector-gated
 * underneath - see {@code KeyStudy}'s own new overload) and {@link KeyCertainty} decides whether the
 * proposal is certain enough to {@link KeyStudy#apply} on the spot or must leave the table {@link
 * LandingRunProgress#WAITING} for a person (the wizard's key chooser, {@code LANDING_KEY_APPLY}).</li>
 * <li>{@link LandingRunProgress#TABLE} runs per SOURCE ({@code LandingGenerator#generate} builds one landing
 * model/dataset for every selected table of that source in one call - it is not per-table, and this lot does
 * not duplicate it): attempted once every table of that source has LEFT {@link LandingRunProgress#TODO} in
 * {@link LandingRunProgress#KEY} (settled {@code DONE}/{@code FAILED}/{@link LandingRunProgress#WAITING}) -
 * a {@code WAITING} table does not block it, since the key only shapes the flow, not the table structure.</li>
 * <li>{@link LandingRunProgress#FLOW}/{@link LandingRunProgress#LOAD} ALSO run per SOURCE ({@code
 * LandingFlows#generate}/{@code #load} are themselves source-wide batch calls over every eligible table of
 * that source - see the class Javadoc's own "what was NOT achieved" note in {@code
 * docs/SOURCES-LANDING-LANDING.md} for why true per-table independence within one source was not built:
 * duplicating {@code LandingFlows}' internals was explicitly out of scope). They are attempted only once
 * EVERY table of that source has a RESOLVED key (no table of the source left {@link
 * LandingRunProgress#WAITING} or {@link LandingRunProgress#TODO}) - so a single waiting table in a
 * five-table source holds up that source's flow/load, but never another source's.</li>
 * </ul>
 * <b>Pause/Resume</b>: once every source has been walked (a person's job, per source, is a single pass - see
 * {@link #HANDLER}), the main job ends; if any table is left {@link LandingRunProgress#WAITING}, the parent
 * {@code PipelineStep} stays {@code RUNNING} with the grid as-is - no thread, no job, waits on it (see
 * {@link #maybeCloseStep}). Once a person applies a key to a {@code WAITING} table ({@link
 * #applyKeyAndResume}), that table's {@link LandingRunProgress#KEY} cell settles {@code DONE} at once
 * (synchronously, on the caller's own session - the write is small and needs to be reflected before the page
 * re-renders) and a {@link #RESUME_STEP} job is enqueued for the rest of that table's source (TABLE if not
 * already built, then FLOW, then LOAD) - reusing the SAME parent step, never a new one, so the grid the
 * wizard already rendered simply keeps filling in.
 * <p>
 * <b>Stop</b>: {@link #stop} cancels whatever job (of either kind) currently holds the project's {@link
 * FactoryJobs#LANDING} resource - {@link FactoryJobs#cancel} - checked between sources/tables (never inside
 * one - a table/source already running finishes) via {@link com.on.ebx.factory.ebx.FactoryJobs.Handle
 * FactoryJobs.Handle#cancelled()}. A stopped run ends its {@code PipelineStep} {@code DONE} (not {@code
 * FAILED}: stopping is a person's deliberate choice, not an error) with a message naming why ({@link
 * com.on.ebx.factory.ebx.FactoryJobs.Handle#stopReason() Handle#stopReason()}); every cell still {@code TODO}
 * at that point is marked {@link LandingRunProgress#SKIPPED} - never left {@code TODO} forever (see {@code
 * LandingRunProgress#isOver}'s own contract: nothing may stay {@code TODO}/{@code RUNNING} once nobody is
 * ever coming back to it). When the run is instead found PAUSED (the {@code PipelineStep} row is {@code
 * RUNNING}, waiting on a key, with no live job - nothing to cancel), {@link #stop} ends the step itself: the
 * old design left that state {@code RUNNING} forever (a known bug - there was no job to cancel and nothing
 * else ever closed the row).
 * <p>
 * <b>Restart</b>: {@link FactoryJobs#start} marks every {@code RUNNING} {@code PipelineStep} row {@code
 * INTERRUPTED} at module start - a run paused across a server restart (waiting on a key) is interrupted like
 * any other, not specially preserved; the person relaunches from the wizard.
 */
public final class LandingRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "LANDING_RUN";
	public static final String RESUME_STEP = "LANDING_RUN_RESUME";

	private LandingRun()
	{
	}

	// ---------------------------------------------------------------------------------------------------- start

	/**
	 * Queues the run ({@link FactoryJobQueue}, resource {@link FactoryJobs#LANDING}) and returns at once: never
	 * refused for "already running" any more - a run already queued/running for the project's LANDING resource
	 * simply goes first, exactly like {@code ebx.StagingLoadRun#start}. Still refused (thrown, not a {@code
	 * started=false} reply - a genuine precondition, unrelated to concurrency) when the project has no selected
	 * landing table at all: the wizard's own {@code generateLanding} gate is supposed to prevent that click in
	 * the first place.
	 * <p>
	 * {@code pSession} is read only here, at enqueue time: the job itself runs with the queue's SYSTEM session
	 * (see {@link FactoryJobQueue}'s own Javadoc), so anything the run needs from the CALLER's session -
	 * whether they may use the language model ({@code Profile.ADMINISTRATOR}) - is resolved once, now, and
	 * travels in the job's params.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pUseLlm) throws OperationException
	{
		return LandingRun.start(pSession, pFactory, pProjectCode, pUseLlm, false);
	}

	/**
	 * Same, with the volume wave 2 (lot V2-E, 2026-10-03) "Full refresh" switch: {@code pFullRefresh} true makes the load of this run
	 * ignore the watermark of every table read through a change column ({@link LandingFlows#load(Session, Adaptation, String,
	 * com.on.ebx.factory.landing.ProgressNote, FactoryJobs.Handle, boolean)}); without it a re-run reads only the rows changed since
	 * each table's last successful load.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pUseLlm, final boolean pFullRefresh)
		throws OperationException
	{
		return LandingRun.start(pSession, pFactory, pProjectCode, pUseLlm, pFullRefresh, null);
	}

	/**
	 * UX lot D (2026-10-14): same, restricted to {@code pOnlyTableIds} (null or empty = every ticked table). "Retry the 2 failed tables": the run's grid, the
	 * KEY and FLOW stages and the verdict concern only these tables. The TABLE stage (the landing model of the application) and the LOAD stage are per
	 * application by construction ({@code LandingGenerator}, {@code LandingFlows#load}): the other tables of the same application are generated and read
	 * again with them, which costs one incremental read for a table with a change column and is harmless for the others. An id that is no longer a ticked
	 * table of the project is ignored; when none is left the run is refused like an empty selection.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pUseLlm, final boolean pFullRefresh,
		final List<String> pOnlyTableIds) throws OperationException
	{
		return LandingRun.start(pSession, pFactory, pProjectCode, pUseLlm, pFullRefresh, pOnlyTableIds, null);
	}

	/**
	 * Landing-walkthrough lot (2026-10-16): same, with the language model limited to the tables of the applications whose ProjectSource ids are in {@code
	 * pLlmSourceIds} (null = every table when {@code pUseLlm}, the former behaviour). The walkthrough asks the person per application ("Yes, 25 rows of this table
	 * are sent to the language model"): an application that did not say yes never has a row sent, whatever the others chose.
	 */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pUseLlm, final boolean pFullRefresh,
		final List<String> pOnlyTableIds, final List<String> pLlmSourceIds) throws OperationException
	{
		final Adaptation project = LandingRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final List<WizardSnapshotTable> tables = LandingRun.only(LandingRun.selectedTables(pFactory, projectId, pSession), pOnlyTableIds);
		if (tables.isEmpty())
		{
			throw EngineRefusal.coded("missing.noLandingTable", "Project " + pProjectCode + " has no selected landing table: pick applications and tables first.", pProjectCode);
		}
		final ObjectNode params = LandingRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("useLlm", pUseLlm);
		params.put("fullRefresh", pFullRefresh);
		if (pLlmSourceIds != null)
		{
			final com.fasterxml.jackson.databind.node.ArrayNode llmSources = params.putArray("llmSourceIds");
			for (final String id : pLlmSourceIds)
			{
				llmSources.add(id);
			}
		}
		if (pOnlyTableIds != null && !pOnlyTableIds.isEmpty())
		{
			final com.fasterxml.jackson.databind.node.ArrayNode only = params.putArray("onlyTableIds");
			for (final String id : pOnlyTableIds)
			{
				only.add(id);
			}
		}
		// audit F-02: "may use the language model" = the person holds DESIGN (was: is an administrator); the field keeps its name in queued jobs
		params.put("administrator", FactoryAccess.hasInPerson(pSession, com.on.ebx.factory.core.FactoryRight.DESIGN));
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, projectId, LandingRun.STEP, params);
		final ObjectNode out = LandingRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The main run's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final boolean useLlm = pParams.path("useLlm").asBoolean(false);
			final boolean fullRefresh = pParams.path("fullRefresh").asBoolean(false);
			final boolean administrator = pParams.path("administrator").asBoolean(false);
			final java.util.Set<String> llmSourceIds = pParams.has("llmSourceIds") ? new java.util.HashSet<String>() : null;
			if (llmSourceIds != null)
			{
				for (final JsonNode id : pParams.path("llmSourceIds"))
				{
					llmSourceIds.add(id.asText(""));
				}
			}
			final List<String> onlyTableIds = new java.util.ArrayList<String>();
			for (final JsonNode id : pParams.path("onlyTableIds"))
			{
				onlyTableIds.add(id.asText(""));
			}
			final List<WizardSnapshotTable> tables = LandingRun.only(LandingRun.selectedTables(pFactory, pProjectId, pJobSession), onlyTableIds);
			if (tables.isEmpty())
			{
				// re-checked at run time (selection may have changed since the click that enqueued this job) -
				// no PipelineStep is created for nothing to report on
				throw new IllegalStateException("Project " + projectCode + " has no selected landing table any more.");
			}
			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, LandingRun.STEP, "");
			FactoryJobs.attach(pHandle, stepId);
			final LandingRunProgress progress = LandingRunProgress.newRun(LandingRun.refsOf(tables));
			// live-progress lot (2026-09-26): every change is written at once, a stage's start included - the
			// screen reads this detail every few seconds (GET .../jobs, progressJson)
			progress.onChange(() -> LandingRun.updateDetail(pJobSession, pFactory, stepId, progress));
			progress.note(RunTexts.runAccepted(tables.size(), LandingRun.groupBySource(tables).size()).stored());
			final String message = LandingRun.runMainStages(pJobSession, pFactory, pProjectId, projectCode, stepId, tables, useLlm, llmSourceIds, administrator, fullRefresh, pHandle, progress);
			LandingRun.judgeOutcome(pFactory, stepId, message, pHandle);
			return message;
		}
	};

	/**
	 * Current state of the latest run of {@code pProjectCode} (if any) - what the wizard's "while running"/
	 * "review"/"done" screens poll. Never throws for "no run yet": {@code exists=false}.
	 * <p>
	 * {@code running} covers three cases: a job (of either kind) still {@code QUEUED}, the {@code PipelineStep}
	 * row {@code RUNNING} with a job of it actually {@code live} ({@link FactoryJobs#live}), or the row {@code
	 * RUNNING} with no live job because the run is legitimately PAUSED on a {@code WAITING} key - the wizard
	 * keeps showing the grid/banner/Stop button through all three, not just while a job happens to be live.
	 * <p>
	 * Live bug fix (coordinator, 2026-09-24, bug 6), kept from before the jobs-queue lot: while the step is
	 * {@code RUNNING}, this first reconciles the persisted progress grid against each table's own real {@code
	 * LandingTable.status} ({@link #reconcileWithActualTableState}) before reporting anything, because the grid
	 * is only a SNAPSHOT the run itself wrote: a table built/loaded through the Advanced disclosure's own
	 * "Generate the landing" button (still fully usable in parallel with a one-click run) changes the real EBX
	 * data without telling this run's progress anything. Jobs-queue lot addition: this reconciliation is now
	 * SKIPPED whenever a job of this project's {@link FactoryJobs#LANDING} resource is live - it is the only
	 * other writer of this row's {@code detail}, and letting two writers race again is exactly what moving to
	 * the queue was meant to end. {@code pSession} may be null (a caller with no write access, e.g. a read-only
	 * probe) - reconciliation is then simply skipped and the persisted, possibly-stale grid is reported as-is.
	 */
	public static ObjectNode status(final Session pSession, final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = LandingRun.MAPPER.createObjectNode();
		final Adaptation project = LandingRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			out.put("exists", false);
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation job = LandingRun.latestJob(pFactory, projectId);
		final boolean queued = job != null && FactoryJobQueue.QUEUED.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		Adaptation row = LandingRun.latestRow(pFactory, projectId);
		if (row == null && !queued)
		{
			out.put("exists", false);
			return out;
		}
		out.put("exists", true);
		if (row == null)
		{
			// queued, but its PipelineStep has not been created yet (the job has not been dispatched)
			out.put("status", FactoryJobQueue.QUEUED);
			out.put("running", true);
			out.put("queued", true);
			out.put("jobId", job.getOccurrencePrimaryKey().format());
			out.put("stale", false);
			out.put("message", "Queued: waiting for the landing of this project to be free.");
			out.put("detailJson", "");
			out.put("totalStages", 0);
			out.put("doneStages", 0);
			out.put("hasWaiting", false);
			out.put("fullyDone", false);
			return out;
		}
		String status = row.getString(FactoryPaths.PipelineStep.STATUS);
		boolean rowRunning = "RUNNING".equals(status);
		LandingRunProgress progress = LandingRunProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL));
		final boolean live = FactoryJobs.live(projectId, FactoryJobs.LANDING) != null;
		if (rowRunning && pSession != null && !live && LandingRun.reconcileWithActualTableState(pSession, pFactory, row.getOccurrencePrimaryKey().format(), progress))
		{
			// reconciliation may have just rewritten status/message/detail (PipelineSteps.end, when the
			// reconciled grid turned out to be fully over) - re-read rather than report the pre-reconcile row
			row = pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(row.getOccurrencePrimaryKey());
			status = row.getString(FactoryPaths.PipelineStep.STATUS);
			rowRunning = "RUNNING".equals(status);
			progress = LandingRunProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL));
		}
		final boolean running = queued || rowRunning && (live || progress.hasWaiting());
		out.put("status", queued ? FactoryJobQueue.QUEUED : status);
		out.put("running", running);
		out.put("queued", queued);
		if (job != null)
		{
			out.put("jobId", job.getOccurrencePrimaryKey().format());
		}
		out.put("stale", false);
		final String message = row.getString(FactoryPaths.PipelineStep.MESSAGE);
		if (message != null)
		{
			out.put("message", message);
		}
		final String detail = row.getString(FactoryPaths.PipelineStep.DETAIL);
		out.put("detailJson", detail == null ? "" : EngineMessageCodec.stripMarkers(detail)); // run panel i18n (2026-10-17): REST readers get the English, no code markers
		out.put("totalStages", progress.totalStageCount());
		out.put("doneStages", progress.doneStageCount());
		out.put("hasWaiting", progress.hasWaiting());
		out.put("fullyDone", progress.isFullyDone());
		return out;
	}

	/**
	 * Live bug fix (coordinator, 2026-09-24, bug 6): settles a table's cells from its own real {@code
	 * LandingTable.status} when that status is further along than what the grid currently shows - never
	 * regresses a cell already {@link LandingRunProgress#DONE}/{@link LandingRunProgress#FAILED} (a
	 * reconciled outcome never overwrites an already-recorded one, good or bad). A table actually {@code
	 * LOADED} settles all four cells (its key was necessarily resolved for that to have happened, however this
	 * run's own grid still describes it); one merely {@code GENERATED} settles only KEY/TABLE. Persists the
	 * correction and, if the whole run is now over (nothing left {@code TODO}/{@code RUNNING}/{@code
	 * WAITING}), closes the step exactly like {@link #maybeCloseStep} would - a paused run whose every table
	 * is now loaded is OVER, not a permanent "1 table waits for you". Returns whether anything changed.
	 */
	private static boolean reconcileWithActualTableState(final Session pSession, final Adaptation pFactory, final String pStepId,
		final LandingRunProgress pProgress)
	{
		boolean changed = false;
		for (final LandingRunProgress.TableProgress table : pProgress.tables())
		{
			final Adaptation tableRow = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(table.id));
			if (tableRow == null)
			{
				continue;
			}
			final String actualStatus = tableRow.getString(FactoryPaths.LandingTable.STATUS);
			if ("LOADED".equals(actualStatus))
			{
				changed |= LandingRun.reconcileDone(pProgress, table.id, LandingRunProgress.KEY, RunTexts.keyConfirmedLoaded().stored());
				changed |= LandingRun.reconcileDone(pProgress, table.id, LandingRunProgress.TABLE, RunTexts.builtLoaded().stored());
				changed |= LandingRun.reconcileDone(pProgress, table.id, LandingRunProgress.FLOW, RunTexts.createdLoaded().stored());
				changed |= LandingRun.reconcileDone(pProgress, table.id, LandingRunProgress.LOAD, RunTexts.loaded().stored());
			}
			else if ("GENERATED".equals(actualStatus))
			{
				// A table is built per SOURCE, even while one of its tables still waits for its key: GENERATED says
				// nothing about the key. Settling KEY here on a keyless table hid the key chooser and closed the run
				// with FLOW/LOAD waiting forever (project ret, 2026-09-26).
				// A WAITING key is settled by a person through the chooser (applyKeyAndResume, which also resumes FLOW/LOAD), never from here: key columns on a built
				// table can be the proposal a planning job stored, and settling the cell hid the chooser and closed the run with FLOW/LOAD waiting forever (project
				// TiersB2CRetestStaging, 2026-10-17; LandingKeyRules.reconcileMayConfirmKey).
				if (LandingKeyRules.reconcileMayConfirmKey(LandingRun.hasKey(pFactory, table.id), pProgress.table(table.id).cell(LandingRunProgress.KEY).state))
				{
					changed |= LandingRun.reconcileDone(pProgress, table.id, LandingRunProgress.KEY, RunTexts.keyConfirmedBuilt().stored());
				}
				changed |= LandingRun.reconcileDone(pProgress, table.id, LandingRunProgress.TABLE, RunTexts.built().stored());
			}
		}
		if (!changed)
		{
			return false;
		}
		LandingRun.updateDetail(pSession, pFactory, pStepId, pProgress);
		if (!pProgress.hasWaiting() && pProgress.isOver())
		{
			final boolean anyFailed = LandingRun.anyFailed(pProgress);
			PipelineSteps.end(pSession, pFactory, pStepId, !anyFailed, TextLimits.truncate(LandingChainOutcome.finalMessage(pProgress), 300), pProgress.toJson());
		}
		return true;
	}

	/** Whether at least one {@code LandingColumn} of the table is flagged as part of its source key. */
	private static boolean hasKey(final Adaptation pFactory, final String pTableId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pTableId));
		try
		{
			for (Adaptation column = rows.nextAdaptation(); column != null; column = rows.nextAdaptation())
			{
				if (Boolean.TRUE.equals(column.get(FactoryPaths.LandingColumn.SOURCE_KEY)))
				{
					return true;
				}
			}
			return false;
		}
		finally
		{
			rows.close();
		}
	}

	/** One cell settled {@code DONE} by {@link #reconcileWithActualTableState} - a no-op (returns false) once the cell is already {@code DONE}/{@code FAILED}. */
	private static boolean reconcileDone(final LandingRunProgress pProgress, final String pTableId, final String pStage, final String pMessage)
	{
		final LandingRunProgress.TableProgress table = pProgress.table(pTableId);
		if (table == null)
		{
			return false;
		}
		final String state = table.cell(pStage).state;
		if (LandingRunProgress.DONE.equals(state) || LandingRunProgress.FAILED.equals(state))
		{
			return false;
		}
		pProgress.finish(pTableId, pStage, true, pMessage);
		return true;
	}

	// --------------------------------------------------------------------------------------------------- resume

	/**
	 * A person chose the key of a {@code WAITING} table (the wizard's key chooser): writes it ({@code USER}
	 * origin, {@code WizardData.setTableKey}) and settles that table's {@link LandingRunProgress#KEY} cell
	 * synchronously, on the CALLER's own session - both are small, fast writes the page needs reflected at
	 * once, unlike the rest of the run. The table's source's remaining stages (TABLE if not already built,
	 * then FLOW, then LOAD) are then queued as a {@link #RESUME_STEP} job, reusing the SAME parent step.
	 */
	public static ObjectNode applyKeyAndResume(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTableId,
		final String pColumnsCsv) throws OperationException
	{
		final Adaptation tableRow = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTableId));
		if (tableRow != null && LandingKeyRules.firstChoiceAllowed(tableRow.getString(FactoryPaths.LandingTable.STATUS), tableRow.getString(FactoryPaths.LandingTable.LAST_LOAD_ID)))
		{
			// built while it waited for its key (the TABLE stage is per source) and never loaded: no landing row
			// carries a _sourceId computed from an old key yet, so the key can still be written as a first choice -
			// whether or not the table already holds a PROPOSED key (a planning job's, origin RULE): that is the waiting
			// table of the 2026-10-17 live case, whose chooser used to refuse with "its key can no longer be changed"
			WizardData.writeTableKey(pSession, pFactory, pTableId, pColumnsCsv, "USER", 100, KeyTexts.chosen().stored());
		}
		else
		{
			WizardData.setTableKey(pSession, pFactory, pTableId, pColumnsCsv);
		}

		final Adaptation project = LandingRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation row = LandingRun.latestRow(pFactory, projectId);
		final ObjectNode out = LandingRun.MAPPER.createObjectNode();
		if (row == null)
		{
			out.put("resumed", false);
			out.put("reason", "No landing run found for this project.");
			return out;
		}
		final String stepId = row.getOccurrencePrimaryKey().format();
		final LandingRunProgress progress = LandingRunProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL));
		if (progress.table(pTableId) == null)
		{
			out.put("resumed", false);
			out.put("reason", "This table is not part of the current landing run.");
			return out;
		}
		progress.finish(pTableId, LandingRunProgress.KEY, true, RunTexts.keyChosen().stored());
		LandingRun.updateDetail(pSession, pFactory, stepId, progress);

		final ObjectNode params = LandingRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("tableId", pTableId);
		params.put("stepId", stepId);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, projectId, LandingRun.RESUME_STEP, params);
		out.put("resumed", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The resume job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler RESUME_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String tableId = pParams.path("tableId").asText("");
			final String stepId = pParams.path("stepId").asText("");
			if (stepId.isEmpty())
			{
				throw new IllegalStateException("Resume job for project " + projectCode + " carries no parent step id.");
			}
			FactoryJobs.attach(pHandle, stepId);
			final Adaptation row = pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(stepId));
			if (row == null || !"RUNNING".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
			{
				// the run this resume belonged to already ended (stopped, or settled) by the time this job got
				// its turn - nothing left to continue
				return RunTexts.resumeGone().stored();
			}
			final LandingRunProgress progress = LandingRunProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL));
			progress.onChange(() -> LandingRun.updateDetail(pJobSession, pFactory, stepId, progress));
			final List<WizardSnapshotTable> tables = LandingRun.selectedTables(pFactory, pProjectId, pJobSession);
			WizardSnapshotTable resumedTable = null;
			for (final WizardSnapshotTable table : tables)
			{
				if (table.id.equals(tableId))
				{
					resumedTable = table;
					break;
				}
			}
			if (resumedTable == null)
			{
				// the table is no longer selected (deselected between the click and this job's turn) - nothing
				// more to build for it, but the run as a whole may now be over
				return LandingRun.closeAndJudge(pJobSession, pFactory, pProjectId, stepId, pHandle);
			}
			final String projectSourceId = resumedTable.projectSourceId;
			final List<WizardSnapshotTable> sourceTables = LandingRun.tablesOfSource(tables, projectSourceId);
			// same order as the main run's own per-source stages, in case TABLE never settled for this source
			// (see runTableStageIfNeeded's own Javadoc) - never assume the middle stage already ran
			if (!pHandle.cancelled())
			{
				LandingRun.runTableStageIfNeeded(pJobSession, pFactory, stepId, projectSourceId, sourceTables, progress);
			}
			if (!pHandle.cancelled())
			{
				LandingRun.runFlowForTable(pJobSession, pFactory, stepId, projectSourceId, resumedTable, progress);
			}
			if (!pHandle.cancelled())
			{
				LandingRun.runLoadForSourceIfReady(pJobSession, pFactory, stepId, projectSourceId, sourceTables, progress, pHandle, false);
			}
			return LandingRun.closeAndJudge(pJobSession, pFactory, pProjectId, stepId, pHandle);
		}
	};

	/**
	 * Cancels whatever job (of either {@link #STEP} or {@link #RESUME_STEP}) currently holds the project's
	 * {@link FactoryJobs#LANDING} resource - the job itself checks {@link
	 * com.on.ebx.factory.ebx.FactoryJobs.Handle#cancelled() Handle#cancelled()} between stages and ends there,
	 * closing the parent step {@code DONE} with a "stopped" message (see this class's own Javadoc). When the
	 * resource is NOT live - the run is instead PAUSED, waiting on a key, {@code PipelineStep} row still {@code
	 * RUNNING} with nothing to cancel - this ends that row itself: the old design left this state {@code
	 * RUNNING} forever (a known bug, since nothing was ever coming back to close it without a resume).
	 */
	public static ObjectNode stop(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = LandingRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final ObjectNode out = LandingRun.MAPPER.createObjectNode();
		final FactoryJobs.Handle live = FactoryJobs.live(projectId, FactoryJobs.LANDING);
		if (live != null)
		{
			FactoryJobs.cancel(projectId, live.kind, LandingRun.actorOf(pSession));
			out.put("stopRequested", true);
			return out;
		}
		// no live job: either nothing is running, or the run is paused on a WAITING key - only the second case
		// needs anything done here
		final Adaptation row = LandingRun.latestRow(pFactory, projectId);
		if (row != null && "RUNNING".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
		{
			final LandingRunProgress progress = LandingRunProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL));
			for (final LandingRunProgress.TableProgress table : progress.tables())
			{
				for (final String stage : LandingRunProgress.STAGES)
				{
					final String state = table.cell(stage).state;
					if (LandingRunProgress.TODO.equals(state) || LandingRunProgress.RUNNING.equals(state))
					{
						progress.skip(table.id, stage, RunTexts.stoppedByPerson().stored());
					}
				}
			}
			PipelineSteps.end(pSession, pFactory, row.getOccurrencePrimaryKey().format(), true, "Landing run stopped by a person.", progress.toJson());
		}
		out.put("stopRequested", true);
		return out;
	}

	private static String actorOf(final Session pSession)
	{
		final Actor actor = Actor.current(pSession);
		return actor.userId;
	}

	// ------------------------------------------------------------------------------------------------- the run itself

	private static String runMainStages(final Session pJobSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode,
		final String pStepId, final List<WizardSnapshotTable> pTables, final boolean pUseLlm, final java.util.Set<String> pLlmSourceIds, final boolean pAdministrator, final boolean pFullRefresh,
		final FactoryJobs.Handle pHandle, final LandingRunProgress pProgress)
	{
		LandingLoadPool pool = null;
		try
		{
			final Map<String, List<WizardSnapshotTable>> bySource = LandingRun.groupBySource(pTables);
			// lot L2 (2026-10-12): the LOAD of a source goes to a pool of at most mdm.factory.landing.parallelSources threads while this thread goes on
			// with the next source's key/model/flows (those stay on this one thread: docs/VOLUME.md "Lot L2"). A bound of 1, or a single source, keeps
			// the load inline - the sequential behaviour of before the lot.
			final int bound = LandingParallelism.bound();
			if (LandingParallelism.usesPool(bySource.size(), bound))
			{
				pool = LandingLoadPool.open(bound);
				pProgress.note(RunTexts.parallel(bound, LandingParallelism.PROPERTY).stored());
			}
			for (final Map.Entry<String, List<WizardSnapshotTable>> entry : bySource.entrySet())
			{
				if (pHandle.cancelled())
				{
					break;
				}
				final String projectSourceId = entry.getKey();
				final List<WizardSnapshotTable> sourceTables = entry.getValue();
				// bug fixed live 2026-09-24 (project Vague1, bug 8): every stage call below already catches
				// OperationException/RuntimeException on its OWN table/source (runKeyStage, runTableStage,
				// runFlowForTable, runLoadForSourceIfReady all do - see their own Javadocs), so a WAITING table
				// was never actually the cause of a stall (WAITING is a normal, settled per-table outcome the
				// loop already continues past, exactly as this class' Javadoc always documented). What COULD
				// still take the whole run down mid-loop is anything those inner catches do not cover - an
				// Error (not an Exception; see CLAUDE.md's VerifyError precedent) or any other Throwable
				// escaping deep inside one source's processing - which used to propagate straight past every
				// remaining source in this for loop AND past maybeCloseStep below (only reachable via a normal
				// return from the try, never after an Error skips the catch entirely), leaving the step stuck
				// RUNNING forever with every later source's tables permanently TODO ("never even started") and
				// no visible failure anywhere. This per-source try/catch(Throwable) is the actual fix: ONE
				// source's catastrophic failure marks only ITS OWN tables FAILED and moves on to the next
				// source - never blocks a source that has nothing to do with it.
				try
				{
					// (1) KEY, per table
					for (final WizardSnapshotTable table : sourceTables)
					{
						if (pHandle.cancelled())
						{
							break;
						}
						LandingRun.runKeyStage(pJobSession, pFactory, table, pProgress, pUseLlm && (pLlmSourceIds == null || pLlmSourceIds.contains(projectSourceId)), pAdministrator);
						LandingRun.updateDetail(pJobSession, pFactory, pStepId, pProgress);
					}
					if (pHandle.cancelled())
					{
						break;
					}

					// (2) TABLE, per source - attempted once no table of this source is left TODO in KEY
					LandingRun.runTableStage(pJobSession, pFactory, projectSourceId, sourceTables, pProgress);
					LandingRun.updateDetail(pJobSession, pFactory, pStepId, pProgress);

					// (3) FLOW, per TABLE (LandingFlows.generateOne, lot KEY) - a table whose key resolved gets its
					// flow at once, never waiting on a sibling table of the same source that is still WAITING. A
					// table whose TABLE stage already failed has its FLOW cell SKIPPED by runTableStage itself
					// (still TODO here only in the ordinary, non-failed case).
					for (final WizardSnapshotTable table : sourceTables)
					{
						if (pHandle.cancelled())
						{
							break;
						}
						if (!LandingRunProgress.TODO.equals(pProgress.table(table.id).cell(LandingRunProgress.FLOW).state))
						{
							continue; // already settled by runTableStage's own failure branch
						}
						final String keyState = pProgress.table(table.id).cell(LandingRunProgress.KEY).state;
						if (LandingRunProgress.WAITING.equals(keyState))
						{
							// coordinator follow-up round 3 (2026-09-24): WAITING, not SKIPPED - this FLOW will still
							// run, the moment the key resolves (applyKeyAndResume), it is not permanently skipped.
							pProgress.waitingStage(table.id, LandingRunProgress.FLOW, RunTexts.waitsForKey().stored());
						}
						else if (LandingRunProgress.FAILED.equals(keyState))
						{
							pProgress.skip(table.id, LandingRunProgress.FLOW, RunTexts.keyFailedNoFlow().stored());
						}
						else
						{
							LandingRun.runFlowForTable(pJobSession, pFactory, pStepId, projectSourceId, table, pProgress);
						}
					}
					LandingRun.updateDetail(pJobSession, pFactory, pStepId, pProgress);
					if (pHandle.cancelled())
					{
						break;
					}

					// (4) LOAD, per SOURCE - LandingFlows has no per-table load entry point, see
					// runLoadForSourceIfReady's own Javadoc for exactly why. Inline for a bound of 1; else handed to the pool (lot L2), this thread
					// going on at once with the next source. The load of one source reports only into ITS tables' cells, so two loads never touch the
					// same cell; a failure of one is settled by its own task and never reaches the others.
					if (pool == null)
					{
						LandingRun.runLoadForSourceIfReady(pJobSession, pFactory, pStepId, projectSourceId, sourceTables, pProgress, pHandle, pFullRefresh);
					}
					else
					{
						LandingRun.submitLoad(pool, pJobSession, pFactory, pStepId, pProjectCode, projectSourceId, sourceTables, pProgress, pHandle, pFullRefresh);
					}
				}
				catch (final Throwable ex) // see this block's own comment above - deliberately Throwable, not Exception
				{
					LandingRun.failSource(pJobSession, pFactory, pStepId, pProjectCode, projectSourceId, sourceTables, pProgress, ex);
				}
			}
		}
		catch (final Exception ex) // deliberately broad - a job with nobody watching it must never let its own bug go unrecorded
		{
			LoggingCategory.getKernel().error("[mdm-factory] landing run failed for project " + pProjectCode + ": " + ex, ex);
		}
		finally
		{
			if (pool != null)
			{
				// every load handed to the pool settles (done, failed or stopped) before the step is judged below; a Stop reaches them through
				// pHandle, which each load polls
				pool.awaitAll();
				pool.close();
			}
		}
		return LandingRun.maybeCloseStep(pJobSession, pFactory, pProjectId, pStepId, pHandle);
	}

	/**
	 * Lot L2: hands the LOAD of one source to {@code pPool}. The task settles its own source in every case: a cancelled run before the task got a thread
	 * leaves the LOAD cells {@code TODO} (the closing of the step marks them skipped, as for a source never reached), and anything the load throws -
	 * an {@link Error} included - fails only this source's unsettled cells ({@link #failSource}), the other sources' loads going on.
	 */
	private static void submitLoad(final LandingLoadPool pPool, final Session pJobSession, final Adaptation pFactory, final String pStepId, final String pProjectCode,
		final String pProjectSourceId, final List<WizardSnapshotTable> pSourceTables, final LandingRunProgress pProgress, final FactoryJobs.Handle pHandle,
		final boolean pFullRefresh)
	{
		pPool.submit(new java.util.concurrent.Callable<Void>()
		{
			@Override
			public Void call()
			{
				try
				{
					if (!pHandle.cancelled())
					{
						LandingRun.runLoadForSourceIfReady(pJobSession, pFactory, pStepId, pProjectSourceId, pSourceTables, pProgress, pHandle, pFullRefresh);
					}
				}
				catch (final Throwable ex) // same posture as the per-source catch of runMainStages: one source's catastrophe settles that source only
				{
					LandingRun.failSource(pJobSession, pFactory, pStepId, pProjectCode, pProjectSourceId, pSourceTables, pProgress, ex);
				}
				return null;
			}
		});
	}

	/** Marks every cell of the source's tables still {@code TODO}/{@code RUNNING} as failed ("Unexpected error on this application") and logs the cause. */
	private static void failSource(final Session pJobSession, final Adaptation pFactory, final String pStepId, final String pProjectCode,
		final String pProjectSourceId, final List<WizardSnapshotTable> pSourceTables, final LandingRunProgress pProgress, final Throwable pCause)
	{
		LoggingCategory.getKernel().error("[mdm-factory] landing run: source " + pProjectSourceId + " of project " + pProjectCode
			+ " failed catastrophically, moving on to the next source: " + pCause, pCause);
		final String message = RunTexts.unexpected().stored();
		for (final WizardSnapshotTable table : pSourceTables)
		{
			for (final String stage : LandingRunProgress.STAGES)
			{
				final String state = pProgress.table(table.id).cell(stage).state;
				if (LandingRunProgress.TODO.equals(state) || LandingRunProgress.RUNNING.equals(state))
				{
					pProgress.finish(table.id, stage, false, message);
				}
			}
		}
		LandingRun.updateDetail(pJobSession, pFactory, pStepId, pProgress);
	}

	/**
	 * Ends the parent step once nothing is left {@code TODO}/{@code RUNNING} AND no table is {@code WAITING} -
	 * otherwise leaves it {@code RUNNING} so the wizard keeps polling/showing the banner (see the class
	 * Javadoc). Jobs-queue lot: called at the end of BOTH {@link #HANDLER} and {@link #RESUME_HANDLER} - it is
	 * the one place that decides the job's own final message, returned to the caller so the queue can record it
	 * on the {@code FactoryJob} row too (prefixed with "Stopped (...)." by {@link FactoryJobQueue} itself when
	 * {@code pHandle} was cancelled - see {@code ebx.StagingLoadRun}'s own equivalent for the same convention).
	 */
	private static String maybeCloseStep(final Session pJobSession, final Adaptation pFactory, final String pProjectId, final String pStepId,
		final FactoryJobs.Handle pHandle)
	{
		final Adaptation row = pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
		if (row == null || !"RUNNING".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
		{
			return RunTexts.stepGone().stored();
		}
		final LandingRunProgress progress = LandingRunProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL));
		if (pHandle.cancelled())
		{
			// never leave a cell TODO forever once nobody is coming back to it - see LandingRunProgress.isOver's own
			// contract (WAITING is left as-is on a stop, same as before this lot: isOver() already treats it as
			// settled, and a stopped run's step closes regardless of it via the unconditional end below)
			for (final LandingRunProgress.TableProgress table : progress.tables())
			{
				for (final String stage : LandingRunProgress.STAGES)
				{
					final String state = table.cell(stage).state;
					if (LandingRunProgress.TODO.equals(state))
					{
						progress.skip(table.id, stage, RunTexts.stopped(pHandle.stopReason()).stored());
					}
				}
			}
			// the step keeps the plain English sentence (REST readers of the pipeline steps); the job message carries the code too
			final KeyTexts.Text stopped = RunTexts.runStopped(pHandle.stopReason());
			PipelineSteps.endStopped(pJobSession, pFactory, pStepId, stopped.english, progress.toJson());
			return stopped.stored();
		}
		if (progress.hasWaiting() || !progress.isOver())
		{
			LandingRun.updateDetail(pJobSession, pFactory, pStepId, progress); // keep RUNNING, just persist the latest snapshot
			return (progress.hasWaiting() ? RunTexts.runPaused() : RunTexts.runLeftRunning()).stored();
		}
		final boolean anyFailed = LandingRun.anyFailed(progress);
		// lot L2: names the applications that failed (the sources settle independently now)
		final KeyTexts.Text finished = LandingChainOutcome.finalText(progress);
		PipelineSteps.end(pJobSession, pFactory, pStepId, !anyFailed, TextLimits.truncate(finished.english, 300), progress.toJson());
		return finished.stored();
	}

	/** {@link #maybeCloseStep}, then {@link #judgeOutcome}: what both handlers end with. */
	private static String closeAndJudge(final Session pJobSession, final Adaptation pFactory, final String pProjectId, final String pStepId, final FactoryJobs.Handle pHandle)
		throws FactoryJobQueue.ExpectedFailure, FactoryJobQueue.PartialOutcome
	{
		final String message = LandingRun.maybeCloseStep(pJobSession, pFactory, pProjectId, pStepId, pHandle);
		LandingRun.judgeOutcome(pFactory, pStepId, message, pHandle);
		return message;
	}

	/**
	 * What the job is stored as when the run ended with failed tables. UX lot C2 made a run where EVERY application failed end FAILED; lot D (2026-10-14)
	 * judges the TABLES, not the applications, and adds the middle case: a run that lost every table ends FAILED (red "Failed", nothing was loaded); a run
	 * that lost some and loaded the others ends {@link FactoryJobQueue#DONE_WITH_FAILURES} with the failed tables and their reasons in the job's detail
	 * ({@link FactoryJobQueue.PartialOutcome}) - it used to be a green DONE whose sentence named the applications, and the next step saw a success. A
	 * stopped run, or one still paused on a key, is left as it is.
	 */
	private static void judgeOutcome(final Adaptation pFactory, final String pStepId, final String pMessage, final FactoryJobs.Handle pHandle)
		throws FactoryJobQueue.ExpectedFailure, FactoryJobQueue.PartialOutcome
	{
		if (pHandle.cancelled())
		{
			return;
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.PIPELINE_STEP).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
		if (row == null || !"FAILED".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
		{
			return;
		}
		final LandingRunProgress progress = LandingRunProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL));
		if (progress.hasWaiting())
		{
			return;
		}
		final PartialFailures.Failures failures = PartialFailures.fromLanding(progress);
		if (PartialFailures.allFailed(failures))
		{
			throw new FactoryJobQueue.ExpectedFailure(pMessage);
		}
		if (PartialFailures.isPartial(failures))
		{
			throw new FactoryJobQueue.PartialOutcome(PartialFailures.landingText(failures).stored(), failures); // run panel i18n (2026-10-17): English + code
		}
	}

	/** The tables of {@code pTables} whose id is in {@code pOnlyIds}, in their order; all of them when {@code pOnlyIds} is null or empty. */
	private static List<WizardSnapshotTable> only(final List<WizardSnapshotTable> pTables, final List<String> pOnlyIds)
	{
		if (pOnlyIds == null || pOnlyIds.isEmpty())
		{
			return pTables;
		}
		final List<WizardSnapshotTable> out = new java.util.ArrayList<WizardSnapshotTable>();
		for (final WizardSnapshotTable table : pTables)
		{
			if (pOnlyIds.contains(table.id))
			{
				out.add(table);
			}
		}
		return out;
	}

	private static boolean anyFailed(final LandingRunProgress pProgress)
	{
		for (final LandingRunProgress.TableProgress table : pProgress.tables())
		{
			for (final LandingRunProgress.Cell cell : table.cells())
			{
				if (LandingRunProgress.FAILED.equals(cell.state))
				{
					return true;
				}
			}
		}
		return false;
	}

	// --------------------------------------------------------------------------------------------------- KEY stage

	private static void runKeyStage(final Session pJobSession, final Adaptation pFactory, final WizardSnapshotTable pTable, final LandingRunProgress pProgress,
		final boolean pUseLlm, final boolean pAdministrator)
	{
		pProgress.start(pTable.id, LandingRunProgress.KEY, RunTexts.lookingKey(pUseLlm && pAdministrator).stored());
		try
		{
			if (!"PROPOSED".equals(pTable.status))
			{
				if (pTable.keyColumns == null || pTable.keyColumns.trim().isEmpty())
				{
					// built without a key (its source was built while it waited): a person still has to choose one,
					// and applyKeyAndResume accepts it on a GENERATED table that was never loaded
					pProgress.waiting(pTable.id, RunTexts.noKeyYet().stored());
					return;
				}
				// left PROPOSED already: WizardData.setTableKey refuses to change it any more, so whatever key it has now is final -
				// but "final" is exactly "settled" (key-confidence lot, 2026-09-28, project DeducedTest3, live incident): a
				// USER/SOURCE/DEDUCED key that already went through this same table (its status left PROPOSED some run ago)
				// can still carry a stale keyConfidence/keyRationale from before the fix above existed - self-heal it here too.
				LandingRun.selfHealSettledConfidence(pJobSession, pFactory, pTable);
				pProgress.finish(pTable.id, LandingRunProgress.KEY, true, LandingRun.keySummary(pTable.keyColumns, pTable.keyOrigin));
				return;
			}
			// fix lot (2026-09-26, defect 11): a key a person already confirmed must never be re-derived by
			// this automatic stage - USER/SOURCE were already covered here; KeyCertainty.alreadySettled also
			// recognises a deduced-scan-confirmed key (origin DEDUCED since round 5's fix - see that method's
			// own Javadoc). Fix lot (2026-09-27, project DeducedTest3): this short-circuit relies on
			// pTable.keyOrigin actually carrying DEDUCED, which in turn depends on WizardData.originRank
			// knowing about it - it did not, and a DEDUCED-only key aggregated to "" and fell straight
			// through to a full re-study; see WizardData#strongerOrigin's own Javadoc for that half of the fix.
			if (KeyCertainty.alreadySettled(pTable.keyOrigin, !pTable.keyColumns.isEmpty(), pTable.keyConfidence))
			{
				// key-confidence lot (2026-09-28, project DeducedTest3, live incident): a settled key must always
				// read its origin's canonical confidence/rationale - self-heals a stale value an EARLIER faulty
				// run left on the table (here: the very incident the fix above corrects, from before it was fixed -
				// a low-confidence study proposal recorded by WizardData.recordKeyProposal because this short-
				// circuit did not fire yet).
				LandingRun.selfHealSettledConfidence(pJobSession, pFactory, pTable);
				pProgress.finish(pTable.id, LandingRunProgress.KEY, true, LandingRun.keySummary(pTable.keyColumns, pTable.keyOrigin));
				return;
			}

			// Landing key validated in the walkthrough (2026-10-17, project TiersB2CRetestStaging): the person validated this application's page, key included
			// ("deduced - 60 % - checked at the load"). That key counts as accepted - the run measures what it can and FAILS the cell if a measure proves it not unique,
			// but never parks the table in WAITING for a key the person already accepted (FLOW and LOAD then waited for ever).
			if (pTable.landingValidated && LandingRun.runValidatedKey(pJobSession, pFactory, pTable, pProgress))
			{
				return;
			}

			final KeyStudyReport report = KeyStudy.study(pJobSession, pFactory, pTable.id, pUseLlm, pAdministrator);
			if (report.proposal == null)
			{
				pProgress.waiting(pTable.id, RunTexts.noColumnCertain().stored());
				return;
			}
			// composite-key lot (landing.KeyCertainty, merged 2026-09-24): uniqueTogether already accounts for
			// emptiness (a row with an empty component is "not unique together"), so no separate "never empty"
			// check is needed here any more - see that class's own Javadoc.
			// volume-guards lot (2026-09-28, task item 4): only worth an extra round trip against the source when
			// the sample already looks unique for a RULE/LLM proposal - KeyCertainty.certain would read false
			// without it regardless of what the source says (SOURCE/USER/DEDUCED never need it at all).
			final Boolean uniqueAtSource = Boolean.TRUE.equals(report.proposalUniqueInSample)
				&& ("RULE".equals(report.proposal.origin) || "LLM".equals(report.proposal.origin))
					? KeySourceUniqueness.verify(pJobSession, pFactory, pTable.id, report.proposal.columns)
					: null;
			final boolean certain = KeyCertainty.certain(report.proposal.origin, report.proposal.confidence, report.proposalUniqueInSample, uniqueAtSource);
			if (certain)
			{
				KeyStudy.apply(pJobSession, pFactory, report);
				pProgress.finish(pTable.id, LandingRunProgress.KEY, true, LandingRun.keySummary(report.proposal.columnsCsv(), report.proposal.origin));
			}
			else
			{
				// kept on the table (confidence + rationale, never the key columns): without it the table still
				// showed the planner's "no key, confidence 0" while the model had proposed a key (project ret, 2026-09-26)
				final KeyTexts.Text proposed = RunTexts.keySummary(report.proposal.columnsCsv(), report.proposal.origin);
				final KeyTexts.Text reason = RunTexts.waitReason(LandingRun.waitReasonCode(report, uniqueAtSource), report.proposal.confidence, uniqueAtSource);
				// 2026-10-07: the key chooser opens on this very proposal (pre-ticked), no second study and no second model call
				KeyStudyRun.keepRunProposal(pTable.id, report, System.currentTimeMillis());
				try
				{
					WizardData.recordKeyProposal(pJobSession, pFactory, pTable.id, report.proposal.confidence,
						// key rationale i18n (2026-10-17): the same English sentence as before, followed by its code, so the wizard words it in the viewer's language
						KeyTexts.pending(LandingRun.waitReasonCode(report, uniqueAtSource), report.proposal.confidence, uniqueAtSource,
							report.proposal.columnsCsv().replace(",", "+"), report.proposal.origin, report.proposal.rationale).stored());
				}
				catch (final OperationException ex)
				{
					// informational write only: the table waits for a person either way
					LoggingCategory.getKernel().warn("[mdm-factory] could not keep the key proposal on landing table " + pTable.id + ": " + ex.getMessage());
				}
				pProgress.waiting(pTable.id, RunTexts.keyWait(proposed, reason).stored());
			}
		}
		catch (final OperationException ex)
		{
			pProgress.finish(pTable.id, LandingRunProgress.KEY, false, LandingRun.failureText(ex));
		}
		catch (final RuntimeException ex) // a job with nobody watching it must never let one table's bug take the run down
		{
			pProgress.finish(pTable.id, LandingRunProgress.KEY, false, LandingRun.failureText(ex));
		}
	}

	/**
	 * Landing key validated in the walkthrough (2026-10-17): settles the KEY cell of a table whose application the person validated and whose key is a deduction
	 * ({@link ValidatedKey#covers}). Reads the 25-row sample (the rule-only study, never the language model: the person's validation is the decision, not a model's) and
	 * measures the CURRENT key on it ({@link KeyEvidence#uniqueTogether}); asks the whole source ({@code KeySourceUniqueness}) unless the sample already proved the key
	 * not unique. {@link ValidatedKey#decide} then accepts it (cell DONE, key unchanged: origin and confidence stay as the person saw them) or fails the cell with the
	 * reason. A sample that cannot be read does not block the key - the cell says the uniqueness was not measured.
	 *
	 * @return true when the cell was settled here (the caller returns); false when the validation does not concern this table
	 */
	private static boolean runValidatedKey(final Session pJobSession, final Adaptation pFactory, final WizardSnapshotTable pTable, final LandingRunProgress pProgress)
		throws OperationException
	{
		final boolean hasKey = pTable.keyColumns != null && !pTable.keyColumns.trim().isEmpty();
		if (!ValidatedKey.covers(pTable.keyOrigin, pTable.keyConfidence, hasKey))
		{
			return false;
		}
		final KeyStudyReport report = KeyStudy.study(pJobSession, pFactory, pTable.id, false, false);
		final List<String> keyColumns = LandingKeyChangeRun.splitColumns(pTable.keyColumns);
		final Boolean uniqueInSample = KeyEvidence.uniqueTogether(report.sampleColumns, report.sampleRows, keyColumns);
		final Boolean uniqueAtSource = ValidatedKey.askSource(uniqueInSample) ? KeySourceUniqueness.verify(pJobSession, pFactory, pTable.id, keyColumns) : null;
		final ValidatedKey.Verdict verdict = ValidatedKey.decide(true, pTable.keyOrigin, pTable.keyConfidence, hasKey, uniqueInSample, uniqueAtSource);
		if (verdict.accepted())
		{
			pProgress.finish(pTable.id, LandingRunProgress.KEY, true, RunTexts.keyValidated(pTable.keyColumns, verdict.measured).stored());
			return true;
		}
		if (verdict.failed())
		{
			pProgress.finish(pTable.id, LandingRunProgress.KEY, false, RunTexts.keyValidatedNotUnique(pTable.keyColumns, ValidatedKey.NOT_UNIQUE_SOURCE.equals(verdict.kind)).stored());
			return true;
		}
		return false;
	}

	/**
	 * Key-confidence lot (2026-09-28, project DeducedTest3, live incident): wherever this stage finds the
	 * table's key already settled - whether because it is {@link KeyCertainty#alreadySettled} while still
	 * {@code PROPOSED}, or because the table simply left {@code PROPOSED} some run ago and its key is final
	 * either way - the table's {@code keyConfidence}/{@code keyRationale} must read the origin's canonical
	 * value, never a stale figure an earlier faulty pass left behind (the live incident: {@code
	 * WizardData.recordKeyProposal} once wrote a study proposal's low confidence onto these same two tables,
	 * back when {@code WizardData#originRank} did not yet know {@code DEDUCED} - &sect;14 of {@code
	 * docs/DEDUCED-SCAN.md} - and by the time that was fixed, the tables had already moved on to {@code
	 * LOADED}, past the {@code alreadySettled} short-circuit that would otherwise have corrected them going
	 * forward). {@link KeyCertainty#staleSettledCorrection} is the pure decision (returns {@code null} for a
	 * {@code RULE}/{@code LLM} origin, or once the table already carries the canonical value): only the write
	 * below touches EBX, and only when there is something to correct.
	 */
	private static void selfHealSettledConfidence(final Session pJobSession, final Adaptation pFactory, final WizardSnapshotTable pTable)
	{
		final KeyCertainty.Settled settled = KeyCertainty.staleSettledCorrection(pTable.keyOrigin, pTable.keyConfidence, pTable.keyRationale);
		if (settled == null)
		{
			return;
		}
		try
		{
			WizardData.settleKeyConfidence(pJobSession, pFactory, pTable.id, settled.confidence, settled.stored());
		}
		catch (final OperationException ex)
		{
			// informational self-heal only: the table's key stands settled either way
			LoggingCategory.getKernel().warn("[mdm-factory] could not settle key confidence on landing table " + pTable.id + ": " + ex.getMessage());
		}
	}

	/** Run panel i18n (2026-10-17): "customerId (chosen by a person)" as a code plus its arguments (English text followed by the marker). */
	private static String keySummary(final String pColumnsCsv, final String pOrigin)
	{
		return RunTexts.keySummary(pColumnsCsv, pOrigin).stored();
	}

	/** Key rationale i18n (2026-10-17): the {@code KeyTexts.WHY_*} code of {@link #waitReason}. */
	static String waitReasonCode(final KeyStudyReport pReport, final Boolean pUniqueAtSource)
	{
		if (pReport.proposalUniqueInSample == null)
		{
			return KeyTexts.WHY_NO_EVIDENCE;
		}
		if (!pReport.proposalUniqueInSample.booleanValue())
		{
			return KeyTexts.WHY_NOT_UNIQUE;
		}
		if ("LLM".equals(pReport.proposal.origin))
		{
			return KeyTexts.WHY_LLM;
		}
		return Boolean.FALSE.equals(pUniqueAtSource) ? KeyTexts.WHY_SAMPLE_ONLY : KeyTexts.WHY_NONE_CERTAIN;
	}

	/** The English waiting reason (plain text, no marker): what the code {@link #waitReasonCode} says. */
	static String waitReason(final KeyStudyReport pReport, final Boolean pUniqueAtSource)
	{
		return RunTexts.waitReason(LandingRun.waitReasonCode(pReport, pUniqueAtSource), pReport.proposal == null ? 0 : pReport.proposal.confidence, pUniqueAtSource).english;
	}

	// ------------------------------------------------------------------------------------------------- TABLE stage

	/**
	 * Live bug fix (coordinator, 2026-09-24, bug 2): only used to catch {@link OperationException} - a
	 * {@link RuntimeException} out of {@link LandingGenerator#generate} (an unwrapped EBX/connector error, not
	 * one of this module's own {@code OperationException}s) propagated straight past this method, past its
	 * caller's per-source loop, into the run's own outer catch-and-log - which never persists the {@code
	 * RUNNING} state {@link LandingRunProgress#start} had only set IN MEMORY, so the table's TABLE cell stayed
	 * {@code TODO} forever in what actually got written to {@code PipelineStep.detail}, and every later stage/
	 * source of that same run silently never ran either. Same "a job with nobody watching it must never let one
	 * table's bug take the run down" rule {@link #runFlowForTable} already follows - now applied here too.
	 */
	private static void runTableStage(final Session pJobSession, final Adaptation pFactory, final String pProjectSourceId, final List<WizardSnapshotTable> pTables,
		final LandingRunProgress pProgress)
	{
		final List<String> ids = LandingRun.idsOf(pTables);
		pProgress.startAll(ids, LandingRunProgress.TABLE, RunTexts.buildingTables().stored());
		try
		{
			LandingGenerator.generate(pJobSession, pFactory, pProjectSourceId, pText -> pProgress.activityAll(ids, LandingRunProgress.TABLE, pText));
			for (final WizardSnapshotTable table : pTables)
			{
				pProgress.finish(table.id, LandingRunProgress.TABLE, true, RunTexts.built().stored());
			}
		}
		catch (final OperationException ex)
		{
			final String message = LandingRun.failureText(ex);
			for (final WizardSnapshotTable table : pTables)
			{
				pProgress.finish(table.id, LandingRunProgress.TABLE, false, message);
				pProgress.skip(table.id, LandingRunProgress.FLOW, RunTexts.tableFailedNoFlow().stored());
				pProgress.skip(table.id, LandingRunProgress.LOAD, RunTexts.tableFailedNoLoad().stored());
			}
		}
		catch (final RuntimeException ex) // see this method's own Javadoc - the reason this catch was added
		{
			final String message = LandingRun.failureText(ex);
			for (final WizardSnapshotTable table : pTables)
			{
				pProgress.finish(table.id, LandingRunProgress.TABLE, false, message);
				pProgress.skip(table.id, LandingRunProgress.FLOW, RunTexts.tableFailedNoFlow().stored());
				pProgress.skip(table.id, LandingRunProgress.LOAD, RunTexts.tableFailedNoLoad().stored());
			}
		}
	}

	/**
	 * Resume-only guard (live bug fix, coordinator, 2026-09-24, bug 2): a resume must run every stage still
	 * {@code TODO} for the resumed table's source, in order - TABLE, then FLOW, then LOAD - never assume TABLE
	 * already ran just because the main run normally reaches it. Runs {@link #runTableStage} for the WHOLE
	 * source (same granularity the main run always used) only when at least one table of that source still
	 * shows {@link LandingRunProgress#TODO} on {@link LandingRunProgress#TABLE}; a source that already settled
	 * TABLE (the ordinary case) is left untouched, so a resume never pays for a needless rebuild.
	 */
	private static void runTableStageIfNeeded(final Session pJobSession, final Adaptation pFactory, final String pStepId, final String pProjectSourceId,
		final List<WizardSnapshotTable> pSourceTables, final LandingRunProgress pProgress)
	{
		boolean needed = false;
		for (final WizardSnapshotTable table : pSourceTables)
		{
			final LandingRunProgress.TableProgress row = pProgress.table(table.id);
			if (row != null && LandingRunProgress.TODO.equals(row.cell(LandingRunProgress.TABLE).state))
			{
				needed = true;
				break;
			}
		}
		if (!needed)
		{
			return;
		}
		LandingRun.runTableStage(pJobSession, pFactory, pProjectSourceId, pSourceTables, pProgress);
		LandingRun.updateDetail(pJobSession, pFactory, pStepId, pProgress);
	}

	// ----------------------------------------------------------------------------------------------- FLOW/LOAD stage

	/**
	 * Per-table-flow lot (2026-09-24): FLOW for exactly ONE table, via {@code LandingFlows.generateOne} (lot
	 * KEY) - the whole point of this method is that it does NOT wait for, or touch, any sibling table of the
	 * same source. Runs for a table as soon as ITS OWN key has resolved (called from {@link #runMainStages}
	 * right after {@link #runTableStage}, and from {@link #RESUME_HANDLER} once a person confirms a WAITING
	 * table's key) - a sibling still {@code WAITING} never delays it. The failure/success this reports is
	 * {@code generateOne}'s own per-table answer ({@code status}/{@code error}), never a batch count - so,
	 * unlike the old source-wide {@code LandingFlows.generate}, a FLOW failure here is always attributable to
	 * the one table it actually happened on.
	 */
	private static void runFlowForTable(final Session pJobSession, final Adaptation pFactory, final String pStepId, final String pProjectSourceId,
		final WizardSnapshotTable pTable, final LandingRunProgress pProgress)
	{
		pProgress.start(pTable.id, LandingRunProgress.FLOW, RunTexts.creatingFlow().stored());
		try
		{
			final ObjectNode result = LandingFlows.generateOne(pJobSession, pFactory, pProjectSourceId, pTable.id);
			final boolean ok = "DONE".equals(result.path("status").asText());
			final String message = ok ? RunTexts.created().stored() : LandingRun.friendlyMessage(result.path("error").asText("Flow generation failed."));
			pProgress.finish(pTable.id, LandingRunProgress.FLOW, ok, message);
		}
		catch (final OperationException ex)
		{
			pProgress.finish(pTable.id, LandingRunProgress.FLOW, false, LandingRun.failureText(ex));
		}
		catch (final RuntimeException ex) // a job with nobody watching it must never let one table's bug take the run down
		{
			pProgress.finish(pTable.id, LandingRunProgress.FLOW, false, LandingRun.failureText(ex));
		}
		LandingRun.updateDetail(pJobSession, pFactory, pStepId, pProgress);
	}

	/**
	 * LOAD, per SOURCE - unlike FLOW above, {@code LandingFlows} has no per-table load entry point: {@code
	 * LandingFlows.load(session, factory, projectSourceId)} is the only method, one {@code connectors_sync}
	 * batch call per eligible table of the WHOLE source inside ONE {@code LANDING_LOAD} {@code PipelineStep}
	 * (see that method's own Javadoc) - there is no {@code loadOne} the way {@code generateOne} exists for
	 * FLOW. So LOAD keeps the source-level wait this class always had: attempted only once every table of
	 * {@code pTables} has left {@link LandingRunProgress#WAITING} in {@link LandingRunProgress#KEY} - a
	 * single table still waiting on its key holds up its whole source's load, exactly as it holds up the
	 * (equally source-wide) landing model/dataset step. A partial batch failure (some tables ok, some not)
	 * is still not distinguished per table here, for the same reason it never was: {@code LandingFlows.load}
	 * reports a failed COUNT, not which table, without parsing its own {@code PipelineStep.detail} back open.
	 * <p>
	 * Idempotent on which cells it touches: only a table whose LOAD cell is still {@link
	 * LandingRunProgress#TODO} is written here, so calling this twice for the same source (once from the
	 * main run, once from a later resume) never re-skips an already-settled cell.
	 * <p>
	 * Volume lot 7c (2026-09-28, "asynchronous landing load"): {@code pHandle} is threaded through to {@link
	 * LandingFlows#load} so its Stop button reaches a table's own run WHILE it is running (polled every few
	 * seconds), not only at THIS method's own call sites (between stages/sources), which is all the
	 * cancellation granularity this class had before this lot.
	 */
	private static void runLoadForSourceIfReady(final Session pJobSession, final Adaptation pFactory, final String pStepId, final String pProjectSourceId,
		final List<WizardSnapshotTable> pTables, final LandingRunProgress pProgress, final FactoryJobs.Handle pHandle, final boolean pFullRefresh)
	{
		boolean anyWaiting = false;
		for (final WizardSnapshotTable table : pTables)
		{
			if (LandingRunProgress.WAITING.equals(pProgress.table(table.id).cell(LandingRunProgress.KEY).state))
			{
				anyWaiting = true;
				break;
			}
		}
		if (anyWaiting)
		{
			for (final WizardSnapshotTable table : pTables)
			{
				if (LandingRunProgress.TODO.equals(pProgress.table(table.id).cell(LandingRunProgress.LOAD).state))
				{
					// coordinator follow-up round 3 (2026-09-24): WAITING, not SKIPPED - this LOAD will still run
					// once every table of the source has resolved its key, it is not permanently skipped.
					pProgress.waitingStage(table.id, LandingRunProgress.LOAD, RunTexts.waitsForKey().stored());
				}
			}
			LandingRun.updateDetail(pJobSession, pFactory, pStepId, pProgress);
			return;
		}
		final List<String> ids = LandingRun.idsOf(pTables);
		pProgress.startAll(ids, LandingRunProgress.LOAD, RunTexts.loadingData().stored());
		try
		{
			final ObjectNode result = LandingFlows.load(pJobSession, pFactory, pProjectSourceId, pText -> pProgress.activityAll(ids, LandingRunProgress.LOAD, pText),
				pHandle, pFullRefresh);
			// volume lot 7c (2026-09-28): a third status, STOPPED, alongside the pre-existing DONE/FAILED -
			// LandingFlows.load's own Stop handling reached this source's load, so the summary reads "Stopped
			// (...)." rather than a generic failure count.
			final boolean stopped = "STOPPED".equals(result.path("status").asText(""));
			final int failed = result.path("failed").asInt(0);
			final boolean loadOk = failed == 0 && !stopped;
			// LandingFlows.load's own ObjectNode carries a table/failed count, not a row count (that lives per-table on
			// LandingTable.lastLoadRows, written by writeLoadOutcome) - the wizard's own review screen reads THAT for the
			// per-table row count once the run is over, so this summary stays a count-of-tables line, never a row count.
			// bug fixed live 2026-09-24 (project Vague1, bug 7a): "N of M table(s) failed." always read "table(s)"
			// whatever N was, and carried no reason at all - real pluralization on the FAILED count (not the
			// total), plus the first failing table's own reason (LandingFlows.firstFailureReason).
			final String summary = loadOk ? RunTexts.loaded().stored()
				: stopped ? RunTexts.stopped(pHandle == null || pHandle.stopReason() == null ? "job stopped" : pHandle.stopReason()).stored()
					: RunTexts.loadFailed(failed, result.path("tables").asInt(pTables.size()), result.path("firstFailureReason").asText("")).stored();
			for (final WizardSnapshotTable table : pTables)
			{
				pProgress.finish(table.id, LandingRunProgress.LOAD, loadOk, summary);
			}
		}
		catch (final OperationException ex)
		{
			final String message = LandingRun.failureText(ex);
			for (final WizardSnapshotTable table : pTables)
			{
				pProgress.finish(table.id, LandingRunProgress.LOAD, false, message);
			}
		}
		catch (final RuntimeException ex) // live bug fix (coordinator, 2026-09-24, bug 2) - see runTableStage's own Javadoc for why this catch matters
		{
			final String message = LandingRun.failureText(ex);
			for (final WizardSnapshotTable table : pTables)
			{
				pProgress.finish(table.id, LandingRunProgress.LOAD, false, message);
			}
		}
		LandingRun.updateDetail(pJobSession, pFactory, pStepId, pProgress);
	}

	/**
	 * Live bug fix (coordinator, 2026-09-24, bug 2): {@link LandingGenerator}/{@link LandingFlows} raise
	 * messages such as {@code "... run POST /sources/13/landing/flows first"} - correct and useful for the
	 * REST caller those two classes were written for, but a person reading this grid never issues a REST
	 * call, and a raw route plus an internal id ("Project source 13 has no landing dataset yet.") reads as an
	 * error from a different, more technical product. Recognises the fixed message shapes those two classes
	 * actually raise and rewrites them in plain words; anything else (a genuine EBX/connector error with no
	 * route in it, which is most of them) passes through unchanged.
	 */
	private static String friendlyMessage(final String pMessage)
	{
		if (pMessage == null)
		{
			return "";
		}
		if (pMessage.contains("has no selected, planned landing table"))
		{
			return RunTexts.noPlannedTable().stored();
		}
		if (pMessage.contains("has no landing dataspace yet") || pMessage.contains("has no landing dataset yet"))
		{
			return RunTexts.tableNotBuilt().stored();
		}
		if (pMessage.contains("has no landing table with a flow"))
		{
			return RunTexts.flowNotCreated().stored();
		}
		// a message of this same "explanation: run POST ..." shape this class does not specifically recognise -
		// strip the route defensively rather than let it leak through unrecognised
		final int cut = pMessage.indexOf(": run POST ");
		return TextLimits.truncate(cut < 0 ? pMessage : pMessage.substring(0, cut) + ".", 300);
	}

	// ------------------------------------------------------------------------------------------------------ detail

	private static void updateDetail(final Session pJobSession, final Adaptation pFactory, final String pStepId, final LandingRunProgress pProgress)
	{
		// lot L2: several threads report into the one grid - the JSON is built and stored by one of them at a time, so the last write is the newest state
		synchronized (pProgress.writeLock())
		{
			LandingRun.writeDetail(pJobSession, pFactory, pStepId, pProgress);
		}
	}

	private static void writeDetail(final Session pJobSession, final Adaptation pFactory, final String pStepId, final LandingRunProgress pProgress)
	{
		final String detail = TextLimits.truncate(pProgress.toJson(), TextLimits.DETAIL_MAX_LENGTH);
		// best-effort partial update of the parent step's detail only - same posture as PipelineSteps.end/StagingLoadRun.updateDetail
		final com.onwbp.adaptation.AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final com.orchestranetworks.service.ProcedureResult result = com.orchestranetworks.service.ProgrammaticService.createForSession(pJobSession, pFactory.getHome())
			.execute(new com.orchestranetworks.service.Procedure()
			{
				@Override
				public void execute(final com.orchestranetworks.service.ProcedureContext pContext) throws Exception
				{
					final Adaptation row = steps.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pStepId));
					if (row == null)
					{
						return;
					}
					final com.orchestranetworks.service.ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(detail, FactoryPaths.PipelineStep.DETAIL);
					pContext.doModifyContent(row, value);
				}
			});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel()
				.error("[mdm-factory] could not update the landing run progress of step " + pStepId + ": " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
	}

	// -------------------------------------------------------------------------------------------------------- lookups

	private static List<String> idsOf(final List<WizardSnapshotTable> pTables)
	{
		final List<String> out = new ArrayList<String>();
		for (final WizardSnapshotTable table : pTables)
		{
			out.add(table.id);
		}
		return out;
	}

	/**
	 * Run panel i18n (2026-10-17): the text a failed cell keeps for an exception. The three fixed shapes {@link #friendlyMessage} rewrites are codes of {@link RunTexts}; a
	 * refusal the Factory itself raised with a message code keeps that code (the wizard words it in the viewer's language); anything else (an EBX or connector error) is
	 * free text and stays as it is.
	 */
	private static String failureText(final Throwable pFailure)
	{
		final String friendly = LandingRun.friendlyMessage(ErrorText.of(pFailure));
		if (EngineMessageCodec.decode(friendly).coded())
		{
			return friendly;
		}
		final EngineRefusal coded = EngineRefusal.find(pFailure);
		return coded == null ? friendly : coded.stored(friendly, 300);
	}

	/** Finished runs looked at for {@link #typicalDurations}: enough for a stable median, few enough to stay cheap. */
	private static final int HISTORY_RUNS = 10;

	/**
	 * Live-progress lot (2026-09-26): the typical duration of each stage on THIS instance, from the last finished
	 * runs of every project ({@link LandingRunProgress#typicalDurations}) - it gives the running screen its
	 * "about N min left". A run written before this lot has no times and simply adds no sample.
	 */
	public static Map<String, Long> typicalDurations(final Adaptation pFactory)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP)
			.createRequestResult("./step=" + CatalogReader.quote(LandingRun.STEP));
		final List<Adaptation> finished = new ArrayList<Adaptation>();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!"RUNNING".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
				{
					finished.add(row);
				}
			}
		}
		finally
		{
			rows.close();
		}
		finished.sort((a, b) -> {
			final Date da = (Date) a.get(FactoryPaths.PipelineStep.STARTED_AT);
			final Date db = (Date) b.get(FactoryPaths.PipelineStep.STARTED_AT);
			return db == null ? -1 : da == null ? 1 : db.compareTo(da);
		});
		final List<LandingRunProgress> runs = new ArrayList<LandingRunProgress>();
		for (final Adaptation row : finished.subList(0, Math.min(LandingRun.HISTORY_RUNS, finished.size())))
		{
			runs.add(LandingRunProgress.parse(row.getString(FactoryPaths.PipelineStep.DETAIL)));
		}
		return LandingRunProgress.typicalDurations(runs);
	}

	private static Adaptation latestRow(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(LandingRun.STEP) + " and " + PipelineSteps.scopeEquals("./scope", ""));
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

	/** The most recent {@code FactoryJob} row of either {@link #STEP} or {@link #RESUME_STEP} for the project - {@code FactoryJobQueue.latest} only knows one kind at a time. */
	private static Adaptation latestJob(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.FACTORY_JOB).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and (./kind=" + CatalogReader.quote(LandingRun.STEP) + " or ./kind="
				+ CatalogReader.quote(LandingRun.RESUME_STEP) + ")");
		try
		{
			Adaptation latest = null;
			long latestId = -1L;
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

	/** One selected landing table's identity - read once at the start of a run/resume, never re-derived with raw {@code FactoryPaths} queries (the snapshot is already the one source of truth the rest of the wizard renders from). */
	private static final class WizardSnapshotTable
	{
		final String id;
		final String tableName;
		final String status;
		final String keyColumns;
		final String keyOrigin;
		/** {@code LandingTable.keyConfidence}, kept on the snapshot for display and for {@link KeyCertainty#certain}'s
		 * {@code LLM}-threshold check. No longer needed to tell a deduced-scan-confirmed key apart from a
		 * genuine {@code LLM} proposal since round 5's fix gave the former its own origin,
		 * {@code DEDUCED} - see {@link KeyCertainty#alreadySettled}. */
		final int keyConfidence;
		/** {@code LandingTable.keyRationale}, kept on the snapshot for the key-confidence lot's self-heal decision
		 * ({@link KeyCertainty#staleSettledCorrection}) - reading whether the table already carries the canonical
		 * rationale for its origin, not only the canonical confidence. */
		final String keyRationale;
		final String projectSourceId;
		final String sourceSystem;
		/** {@code ProjectSource.landingValidated} of the table's application when the run (or resume) read it: the person validated the page at step 3, key included ({@link ValidatedKey}). */
		final boolean landingValidated;

		WizardSnapshotTable(final String pId, final String pTableName, final String pStatus, final String pKeyColumns, final String pKeyOrigin,
			final int pKeyConfidence, final String pKeyRationale, final String pProjectSourceId, final String pSourceSystem, final boolean pLandingValidated)
		{
			this.landingValidated = pLandingValidated;
			this.id = pId;
			this.tableName = pTableName;
			this.status = pStatus;
			this.keyColumns = pKeyColumns;
			this.keyOrigin = pKeyOrigin;
			this.keyConfidence = pKeyConfidence;
			this.keyRationale = pKeyRationale;
			this.projectSourceId = pProjectSourceId;
			this.sourceSystem = pSourceSystem;
		}
	}

	/** Jobs-queue lot: called both at enqueue time (caller session, for the "no table selected" precondition) and inside the job (job session, to run against the current data). */
	private static List<WizardSnapshotTable> selectedTables(final Adaptation pFactory, final String pProjectId, final Session pSession)
	{
		final WizardSnapshot snapshot = WizardData.readSnapshot(pFactory, pProjectId, pSession);
		final List<WizardSnapshotTable> out = new ArrayList<WizardSnapshotTable>();
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (!table.selected)
				{
					continue;
				}
				out.add(new WizardSnapshotTable(table.id, table.tableName, table.status, table.keyColumns, table.keyOrigin, table.keyConfidence,
					table.keyRationale, source.id, source.sourceSystem, source.landingValidated));
			}
		}
		return out;
	}

	private static List<WizardSnapshotTable> tablesOfSource(final List<WizardSnapshotTable> pTables, final String pProjectSourceId)
	{
		final List<WizardSnapshotTable> out = new ArrayList<WizardSnapshotTable>();
		for (final WizardSnapshotTable table : pTables)
		{
			if (table.projectSourceId.equals(pProjectSourceId))
			{
				out.add(table);
			}
		}
		return out;
	}

	private static Map<String, List<WizardSnapshotTable>> groupBySource(final List<WizardSnapshotTable> pTables)
	{
		final Map<String, List<WizardSnapshotTable>> out = new LinkedHashMap<String, List<WizardSnapshotTable>>();
		for (final WizardSnapshotTable table : pTables)
		{
			List<WizardSnapshotTable> list = out.get(table.projectSourceId);
			if (list == null)
			{
				list = new ArrayList<WizardSnapshotTable>();
				out.put(table.projectSourceId, list);
			}
			list.add(table);
		}
		return out;
	}

	private static List<LandingRunProgress.TableRef> refsOf(final List<WizardSnapshotTable> pTables)
	{
		final List<LandingRunProgress.TableRef> out = new ArrayList<LandingRunProgress.TableRef>();
		for (final WizardSnapshotTable table : pTables)
		{
			out.add(new LandingRunProgress.TableRef(table.id, table.tableName, table.sourceSystem));
		}
		return out;
	}
}
