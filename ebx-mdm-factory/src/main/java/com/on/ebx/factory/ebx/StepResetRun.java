package com.on.ebx.factory.ebx;

import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Volume-operations lot 8a (2026-09-28, task 2): {@link StepResetter#reset} as a WHOLE-PROJECT job of {@link
 * FactoryJobQueue} ({@link FactoryJobQueue#registerWholeProject}, {@link FactoryJobs#claimAll}) - before this
 * lot the wizard/REST "start over from here" action ran {@link StepResetter#resetHoldingProject} synchronously
 * on the caller's own thread, inside {@link FactoryJobQueue#exclusively} (a blocking, all-resources-at-once
 * claim that REFUSED outright if any other job of the project was active). This class keeps the exact same
 * underlying work ({@link StepResetter#resetHoldingProject}, called directly - never through {@code
 * exclusively}, which would deadlock on the claim this job's own {@link FactoryJobs#claimAll} already holds -
 * see that method's own comment) but runs it on the job pool instead: nothing is refused any more, a request
 * for a busy project simply queues and starts once every resource frees up, cancellable between its own
 * per-item deletions the same way every other job is (though {@link StepResetter}'s own item loop has no
 * mid-loop cancellation check today - see "What is not verified" in this lot's own {@code docs/VOLUME.md}
 * section for that gap).
 * <p>
 * {@link StepResetter#plan} is UNTOUCHED and stays synchronous (it only counts - task brief's own instruction).
 * {@code rest.FactoryResource}'s confirm/plan route keeps calling it directly; only the actual reset moved.
 */
public final class StepResetRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "STEP_RESET_RUN";

	private StepResetRun()
	{
	}

	/** Queues the reset (whole project) and returns at once - never refused, same "nothing is refused" posture as every other job kind. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pFrom, final String pConfirm)
		throws OperationException
	{
		final Adaptation project = StepResetRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = StepResetRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("from", pFrom == null ? "" : pFrom);
		params.put("confirm", pConfirm == null ? "" : pConfirm);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), StepResetRun.STEP, params);
		final ObjectNode out = StepResetRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/**
	 * Volume-operations lot 8b (2026-09-28, task 3): current state of the latest {@code STEP_RESET_RUN} job of
	 * this project FOR THIS STAGE (any status), same shape as {@code StagingLoadRun#status} - the wizard's own
	 * {@code STEP_RESET_CONFIRM} handler used to call {@link StepResetter#reset} directly and read its return
	 * value synchronously; now it enqueues via {@link #start} and polls this instead. The rich report ({@code
	 * from}, {@code rowsDeleted[]}, {@code xrefCleared}, {@code stepsReset}) survives the async move because,
	 * unlike a project DELETION, a step reset never removes the project itself - {@code StepResetter}'s own
	 * {@code PipelineStep} audit row ({@code step="STEP_RESET"}, {@code scope=<stage>}) is still there to read
	 * once the job ends.
	 */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectCode, final String pStage)
	{
		final ObjectNode out = StepResetRun.MAPPER.createObjectNode();
		final Adaptation project = StepResetRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			out.put("exists", false);
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation job = FactoryJobQueue.latest(pFactory, projectId, StepResetRun.STEP);
		final boolean queued = job != null && FactoryJobQueue.QUEUED.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		final boolean running = queued || job != null && FactoryJobQueue.RUNNING.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		out.put("exists", job != null);
		out.put("queued", queued);
		out.put("running", running);
		if (!running)
		{
			// the job (if any) is no longer live - read the audit row it left behind, if the reset actually ran
			final Adaptation stepRow = StepResetRun.latestStepRow(pFactory, projectId, pStage);
			if (stepRow != null)
			{
				out.put("status", stepRow.getString(FactoryPaths.PipelineStep.STATUS));
				final String message = stepRow.getString(FactoryPaths.PipelineStep.MESSAGE);
				out.put("message", message == null ? "" : EngineMessageCodec.decode(message).raw); // wave 10: never the code marker
				final String detail = stepRow.getString(FactoryPaths.PipelineStep.DETAIL);
				out.put("detailJson", detail == null ? "" : detail);
			}
			else if (job != null)
			{
				// the job ended (FAILED/CANCELLED) before StepResetter.resetHoldingProject ever wrote its own
				// audit row (a refused confirm, an unexpected exception) - the job's own message is all there is
				out.put("status", job.getString(FactoryPaths.FactoryJob.STATUS));
				final String message = job.getString(FactoryPaths.FactoryJob.MESSAGE);
				out.put("message", message == null ? "" : message);
				out.put("detailJson", "");
			}
		}
		return out;
	}

	/** The most recently STARTED {@code STEP_RESET} PipelineStep row of the project for this exact stage - same "latest by startedAt" idiom as {@code StagingLoadRun#latestRow}. */
	private static Adaptation latestStepRow(final Adaptation pFactory, final String pProjectId, final String pStage)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote("STEP_RESET") + " and " + PipelineSteps.scopeEquals("./scope", pStage));
		try
		{
			Adaptation latest = null;
			java.util.Date latestStartedAt = null;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final java.util.Date startedAt = (java.util.Date) row.get(FactoryPaths.PipelineStep.STARTED_AT);
				if (latest == null || startedAt != null && (latestStartedAt == null || startedAt.after(latestStartedAt)))
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

	/** The job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String from = pParams.path("from").asText("");
			final String confirm = pParams.path("confirm").asText("");
			final ObjectNode result = StepResetter.resetHoldingProject(pJobSession, pFactory, projectCode, from, confirm.isEmpty() ? null : confirm);
			return "Reset of " + projectCode + " from " + result.path("from").asText(from) + ": " + result.path("rowsDeleted") + ", " + result.path("stepsReset")
				.asInt(0) + " step(s) reset.";
		}
	};

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
