package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Wave 13 (2026-10-12): {@code POST /projects/{code}/distribution/targets/{targetId}/stop} - stops the send of a target.
 * <ol>
 * <li>Every QUEUED or RUNNING first-full / send-now job of the target is cancelled through the job queue ({@link FactoryJobQueue#cancel}); a
 * RUNNING one notices at its next poll (a few seconds), stops its Connectors run with {@code connectors_run_stop} and ends the send STOPPED
 * ({@link DistributionSendCalls}).</li>
 * <li>Every Connectors run the target's latest send step and {@code firstFullSendRunId} record ({@link DistributionRunner#recordedRunIds}) that
 * {@code connectors_run_get} still reports RUNNING is stopped directly - the case the job queue cannot see: the Factory was restarted, the job is
 * gone, the run went on.</li>
 * </ol>
 * Connectors stops a run at its next batch boundary, never mid-batch, so records of the current batch still go out. A send that Connectors ran
 * synchronously (no {@code async} for a scope send on the Connectors of 2026-10-12) has no run to stop: the cancelled job ends the send after the
 * call it is waiting for, before the next mapping.
 */
public final class DistributionSendStop
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DistributionSendStop()
	{
	}

	/** @return {@code {targetId, stopped, jobsCancelled[], runsStopped[], message}}; {@code stopped} is false when nothing was running. */
	public static ObjectNode stop(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId) throws OperationException
	{
		final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation target = DistributionConfigurator.findTarget(pFactory, pTargetId);
		if (target == null || !projectId.equals(target.getString(FactoryPaths.DistributionTarget.PROJECT)))
		{
			throw EngineRefusal.coded("invalid.targetNotOfProject", "Distribution target " + pTargetId + " does not belong to this project", pTargetId);
		}
		final ObjectNode out = MAPPER.createObjectNode();
		out.put("targetId", pTargetId);
		final ArrayNode jobs = out.putArray("jobsCancelled");
		for (final String jobId : DistributionSendRun.activeJobIds(pFactory, projectId, pTargetId))
		{
			if (FactoryJobQueue.cancel(pSession, pFactory, jobId))
			{
				jobs.add(jobId);
			}
		}
		final ArrayNode runs = out.putArray("runsStopped");
		final List<String> recorded = DistributionRunner.recordedRunIds(pFactory, projectId, pTargetId);
		if (!recorded.isEmpty())
		{
			try
			{
				final McpClient client = McpServers.connectors(pSession, pFactory);
				for (final String runId : recorded)
				{
					if (DistributionRunner.runIsRunning(client, runId) && DistributionSendCalls.stopRun(client, runId, MAPPER.createObjectNode()))
					{
						runs.add(runId);
					}
				}
			}
			catch (final OperationException ex)
			{
				out.put("runLookupError", ex.getMessage());
			}
		}
		final boolean stopped = jobs.size() > 0 || runs.size() > 0;
		out.put("stopped", stopped);
		out.put("message", DistributionSendStop.message(pTargetId, names(jobs), names(runs)));
		return out;
	}

	/** The sentence the route answers: what was asked to stop, or that nothing was running. Pure. */
	static String message(final String pTargetId, final List<String> pJobs, final List<String> pRuns)
	{
		if (pJobs.isEmpty() && pRuns.isEmpty())
		{
			return "No send of target " + pTargetId + " is running.";
		}
		final StringBuilder message = new StringBuilder("Asked to stop the send of target ").append(pTargetId).append(':');
		if (!pJobs.isEmpty())
		{
			message.append(" job(s) ").append(String.join(", ", pJobs));
		}
		if (!pRuns.isEmpty())
		{
			message.append(pJobs.isEmpty() ? "" : ";").append(" Connectors run(s) ").append(String.join(", ", pRuns)).append(" (stopped at the next batch)");
		}
		return message.append('.').toString();
	}

	private static List<String> names(final ArrayNode pArray)
	{
		final List<String> names = new ArrayList<String>();
		for (final com.fasterxml.jackson.databind.JsonNode node : pArray)
		{
			names.add(node.asText());
		}
		return names;
	}
}
