package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Wave 2 (2026-09-30, lot V2-A): recomputes the stored matching results of wizard step 6 ({@link MatchingResultsStore}) as a job
 * of {@link FactoryJobQueue}, resource {@link FactoryJobs#MATCHING} - so it never overlaps a matching run or configure of the
 * same project, and never runs on an HTTP thread. Queued by {@link MatchingResultsStore#afterDecisions} after a pair is decided
 * (the stored figures were patched locally and flagged stale) and by the wizard's "Refresh results" button
 * ({@code MATCH_RESULTS_REFRESH}). A matching run does NOT queue it: {@code MatchingConfigurator.run} computes the results of each
 * entity it ran, inline, at the end of that entity's step.
 * <p>
 * Parameters: {@code projectCode}, {@code entity} (blank = every entity that has a finished {@code MATCHING_RUN} row). Identical
 * queued requests are folded into one by the queue ({@link FactoryJobQueue#enqueue}), so ten decisions in a row queue one refresh.
 * Not verified live (no way to run the module in the session that wrote it).
 */
public final class MatchingResultsRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "MATCHING_RESULTS_RUN";

	private MatchingResultsRun()
	{
	}

	/** Queues the refresh (resource MATCHING) and returns at once; {@code pEntityName} blank = every entity. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName) throws OperationException
	{
		final Adaptation project = MatchingResultsRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = MatchingResultsRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("entity", pEntityName == null ? "" : pEntityName);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), MatchingResultsRun.STEP, params);
		final ObjectNode out = MatchingResultsRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** True while a refresh of this project is queued or running - what the "Refresh results" button reads to say "being refreshed" instead of offering itself again. */
	public static boolean pending(final Adaptation pFactory, final String pProjectId)
	{
		final Adaptation job = FactoryJobQueue.latest(pFactory, pProjectId, MatchingResultsRun.STEP);
		if (job == null)
		{
			return false;
		}
		final String status = job.getString(FactoryPaths.FactoryJob.STATUS);
		return FactoryJobQueue.QUEUED.equals(status) || (FactoryJobQueue.RUNNING.equals(status) && FactoryJobs.isLive(pProjectId, MatchingResultsRun.STEP));
	}

	/** The job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String only = pParams.path("entity").asText("");
			int refreshed = 0;
			int skipped = 0;
			for (final String entityName : MatchingResultsRun.entityNames(pFactory, pProjectId, only))
			{
				if (pHandle.cancelled())
				{
					break;
				}
				final Adaptation row = MatchingResultsStore.latestRunRow(pFactory, pProjectId, entityName);
				if (row == null || !"DONE".equals(row.getString(FactoryPaths.PipelineStep.STATUS)))
				{
					skipped++; // never run, still running, or its last run did not finish: nothing to describe
					continue;
				}
				final String json = MatchingResultsStore.compute(pJobSession, pFactory, projectCode, pProjectId, entityName);
				if (json != null && MatchingResultsStore.store(pJobSession, pFactory, pProjectId, entityName, json))
				{
					refreshed++;
				}
				else
				{
					skipped++;
				}
			}
			return refreshed + " entit" + (refreshed == 1 ? "y" : "ies") + " refreshed, " + skipped + " skipped.";
		}
	};

	private static List<String> entityNames(final Adaptation pFactory, final String pProjectId, final String pOnly)
	{
		final List<String> out = new ArrayList<String>();
		if (pOnly != null && !pOnly.isEmpty())
		{
			out.add(pOnly);
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row.getString(FactoryPaths.StagingEntity.NAME));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
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
