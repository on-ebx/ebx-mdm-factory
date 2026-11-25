package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.landing.LoadDeadline;
import com.on.ebx.factory.landing.RunStateOutcome;

/**
 * F2-05: one {@code connectors_sync} of an inbound feed (a catalog scan, the sample flow) started with {@code async: true} and followed with
 * {@code connectors_run_get} to its terminal state, the model of {@code LandingFlows.awaitLoad} and of the distribution send. The start answers
 * at once, so the call no longer lives under the fixed 300 s read timeout of the bridge - a scan of 10,000 tables is not cut while Connectors
 * goes on - and the bridge thread is held only for the length of one poll.
 * <p>
 * The answer is rebuilt in the shape of a SYNCHRONOUS {@code connectors_sync} answer ({@code status, count, ok, errors, truncated, available}), so
 * the callers' own reading ({@code LandingFlow.loadOutcome}, {@code SampleClassification.outcome}) is unchanged. A Connectors that ignores
 * {@code async} answers the complete shape at once and is returned as it is. A run that ends STOPPED or INTERRUPTED, or that outlives
 * {@link LoadDeadline}, is a {@link McpException} naming the run: Connectors was asked to stop it, and the person is told to look at it.
 * The job {@code pHandle} (null for a caller with none) is checked between polls: a Stop asks Connectors to stop the run at its next batch boundary.
 */
final class ConnectorsRunFollower
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ConnectorsRunFollower()
	{
	}

	/**
	 * @param pArgs the {@code connectors_sync} arguments, without {@code async} (set here)
	 * @param pPollMs milliseconds between two {@code connectors_run_get}
	 * @param pLimitMs the longest the run may stay RUNNING ({@link LoadDeadline#maxMillis()} in production)
	 * @return the answer in the synchronous shape; {@code error} true when Connectors refused the start
	 */
	static Answer sync(final McpClient pClient, final ObjectNode pArgs, final FactoryJobs.Handle pHandle, final long pPollMs, final long pLimitMs) throws McpException
	{
		final ObjectNode args = pArgs.deepCopy();
		args.put("async", true);
		final McpClient.ToolResult start = pClient.callTool("connectors_sync", args);
		if (start.error)
		{
			return new Answer(true, start.text, start.json, null);
		}
		if (!RunStateOutcome.isAsyncStart(start.json))
		{
			return new Answer(false, start.text, start.json, null);
		}
		final String runId = RunStateOutcome.runId(start.json);
		final long started = System.currentTimeMillis();
		while (true)
		{
			if (LoadDeadline.expired(started, System.currentTimeMillis(), pLimitMs))
			{
				final boolean stopAsked = ConnectorsRunFollower.stop(pClient, runId);
				throw new McpException(0, "Connectors run " + runId + " was still running after " + (System.currentTimeMillis() - started) / 60000L
					+ " minute(s) (limit " + pLimitMs / 60000L + ", JVM property " + LoadDeadline.PROPERTY + "); "
					+ (stopAsked ? "Connectors was asked to stop it" : "stopping it FAILED, check it in Connectors") + " (connectors_run_get).");
			}
			if (pHandle != null && pHandle.cancelled())
			{
				ConnectorsRunFollower.stop(pClient, runId);
				throw new McpException(0, "Stopped (" + pHandle.stopReason() + "): Connectors was asked to stop run " + runId + " at its next batch boundary (connectors_run_get).");
			}
			try
			{
				Thread.sleep(pPollMs);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				throw new McpException("Interrupted while waiting for Connectors run " + runId + ": it may still be running (connectors_run_get).", ex);
			}
			final ObjectNode getArgs = MAPPER.createObjectNode();
			getArgs.put("runId", runId);
			final McpClient.ToolResult run = pClient.callTool("connectors_run_get", getArgs);
			if (run.error)
			{
				throw new McpException(0, "connectors_run_get refused while following run " + runId + ": " + run.text);
			}
			final JsonNode execution = run.json == null ? null : run.json.path("execution");
			if (execution == null || execution.isMissingNode())
			{
				continue;
			}
			final String status = execution.path("status").asText("");
			if (!RunStateOutcome.isTerminal(status))
			{
				continue;
			}
			if ("STOPPED".equals(status) || "INTERRUPTED".equals(status))
			{
				throw new McpException(0, "Connectors run " + runId + " ended " + status + " after " + RunStateOutcome.processedSoFar(execution)
					+ " item(s) (connectors_run_get).");
			}
			return new Answer(false, run.text, ConnectorsRunFollower.syncShape(execution, runId), runId);
		}
	}

	/** A terminal {@code execution{}} as a synchronous {@code connectors_sync} answer. */
	static ObjectNode syncShape(final JsonNode pExecution, final String pRunId)
	{
		final ObjectNode out = MAPPER.createObjectNode();
		final long count = RunStateOutcome.processedSoFar(pExecution);
		final long errors = RunStateOutcome.errorsSoFar(pExecution);
		final String status = pExecution.path("status").asText("");
		out.put("status", "DONE".equals(status) ? "OK" : status);
		out.put("count", count);
		out.put("ok", Math.max(0L, count - errors));
		out.put("errors", errors);
		if (pExecution.hasNonNull("truncated"))
		{
			out.put("truncated", pExecution.path("truncated").asBoolean());
		}
		if (pExecution.hasNonNull("available"))
		{
			out.put("available", pExecution.path("available").asLong());
		}
		out.put("runId", pRunId);
		out.put("async", true);
		return out;
	}

	private static boolean stop(final McpClient pClient, final String pRunId)
	{
		try
		{
			final ObjectNode args = MAPPER.createObjectNode();
			args.put("runId", pRunId);
			return !pClient.callTool("connectors_run_stop", args).error;
		}
		catch (final McpException ex)
		{
			return false;
		}
	}

	/** What {@link #sync} gives back: the answer as a synchronous one would be, and the run id when it was followed. */
	static final class Answer
	{
		final boolean error;
		final String text;
		final JsonNode json;
		final String runId;

		Answer(final boolean pError, final String pText, final JsonNode pJson, final String pRunId)
		{
			this.error = pError;
			this.text = pText;
			this.json = pJson;
			this.runId = pRunId;
		}
	}
}
