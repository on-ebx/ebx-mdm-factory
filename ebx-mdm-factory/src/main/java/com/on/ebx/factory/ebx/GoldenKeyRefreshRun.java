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
 * Volume-operations lot 8a (2026-09-28, task 1): wraps {@link MatchingConfigurator#refreshGoldenKeys} as a job
 * of {@link FactoryJobQueue} (resource {@link FactoryJobs#MATCHING} - the same resource {@link
 * MatchingConfigureRun}/{@link MatchingRunRun} already claim, so a golden-key refresh never overlaps a
 * configure/run job or the matching workflow bean of the same project). Unlike those two, {@code
 * matching/refresh-golden-keys} had NO job wrapper at all before this lot: {@code rest.FactoryResource}'s route
 * called {@link MatchingConfigurator#refreshGoldenKeys} directly on the Tomcat request thread, once per
 * promoted entity, each entity's own {@link GoldenKeyRefresher#refresh} already streamed/batched (lot 2a) but
 * still a real hold on the request thread for a project with many entities or a very large master table.
 * <p>
 * This class changes nothing about what is refreshed - {@link MatchingConfigurator#refreshGoldenKeys} is
 * called verbatim, unmodified. Not verified live (no EBX instance reachable this session).
 */
public final class GoldenKeyRefreshRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "GOLDEN_KEY_REFRESH_RUN";

	private GoldenKeyRefreshRun()
	{
	}

	/** Queues the refresh (resource MATCHING) and returns at once - never refused, same as {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName) throws OperationException
	{
		final Adaptation project = GoldenKeyRefreshRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = GoldenKeyRefreshRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("entity", pEntityName == null ? "" : pEntityName);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), GoldenKeyRefreshRun.STEP, params);
		final ObjectNode out = GoldenKeyRefreshRun.MAPPER.createObjectNode();
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
			final String entity = pParams.path("entity").asText("");
			final ObjectNode result = MatchingConfigurator.refreshGoldenKeys(pJobSession, pFactory, projectCode, entity.isEmpty() ? null : entity);
			final int refreshed = result.path("refreshed").size();
			final int refused = result.path("refused").size();
			return refreshed + " entit" + (refreshed == 1 ? "y" : "ies") + " refreshed, " + refused + " refused.";
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
