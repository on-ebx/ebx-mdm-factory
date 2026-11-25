package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Volume-operations lot 8a (2026-09-28, task 2): {@link MasterReset#reset} as a WHOLE-PROJECT job - same shape
 * as {@link StepResetRun}/{@link ProjectDeleteRun}, see {@link StepResetRun}'s own Javadoc for the reasoning
 * ({@link FactoryJobs#claimAll}, {@link MasterReset#resetHoldingProject} called directly rather than through
 * {@link FactoryJobQueue#exclusively}).
 */
public final class MasterResetRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "MASTER_RESET_RUN";

	private MasterResetRun()
	{
	}

	/** Queues the reset (whole project) and returns at once. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pConfirm) throws OperationException
	{
		final Adaptation project = MasterResetRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = MasterResetRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("confirm", pConfirm == null ? "" : pConfirm);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), MasterResetRun.STEP, params);
		final ObjectNode out = MasterResetRun.MAPPER.createObjectNode();
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
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String confirm = pParams.path("confirm").asText("");
			final ObjectNode result = MasterReset.resetHoldingProject(pJobSession, pFactory, projectCode, confirm.isEmpty() ? null : confirm);
			return "Master reset of " + projectCode + ": " + result.toString();
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
