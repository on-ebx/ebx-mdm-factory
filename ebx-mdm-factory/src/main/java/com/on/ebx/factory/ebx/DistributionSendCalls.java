package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.distribution.DistributionBatches;
import com.on.ebx.factory.distribution.SendRuns;
import com.on.ebx.factory.distribution.SyncOutcome;
import com.on.ebx.factory.landing.LoadDeadline;
import com.on.ebx.factory.landing.RunStateOutcome;
import com.orchestranetworks.service.OperationException;

/**
 * Wave 13 (2026-10-12, "asynchronous distribution send"): the Connectors calls of ONE round of a distribution send - one {@code connectors_sync}
 * of one mapping, followed to its end. {@code DistributionRunner.send} runs rounds of it (a {@code changed} send asks again while a round is cut).
 * <p>
 * Measured live the same day (Vol100k, target 3, scope {@code all}): a synchronous {@code connectors_sync} of 88,991 addresses lasted 9 min 36
 * and was cut by the Factory's fixed 300 s read timeout while Connectors went on sending. The landing load already avoids this
 * ({@code LandingFlows}, "Volume lot 7c"): it starts the run with {@code async: true}, reads the run id and polls {@code connectors_run_get} every
 * {@link LandingFlows#LOAD_POLL_INTERVAL_MS} to the run's terminal state, checking the job for a stop between polls. This class does the same for a
 * send:
 * <ol>
 * <li>{@link #round} starts the call with {@code async: true} through the ORDINARY client - the call answers at once, so no long read timeout is
 * needed - and hands the run id to the {@link Recorder} (the step's detail, where the double-send guard and a person find it);</li>
 * <li>it polls {@code connectors_run_get} to a terminal state and reads it with {@link SendRuns#terminal} into a {@link SyncOutcome.Result}, which
 * is exactly what the caller already reads;</li>
 * <li>a stop asked of the job ({@link FactoryJobs.Handle#cancelled}) between two polls calls {@code connectors_run_stop} on the run, reads the run
 * once more for what it had reached, and ends the round {@link Round#stoppedByHandle};</li>
 * <li><b>an older Connectors that ignores {@code async}</b> answers the complete synchronous shape: it is read directly, as before;</li>
 * <li><b>a Connectors that refuses {@code async} for a scope send</b> ("async only applies to an inbound feed", the Connectors of 2026-10-12)
 * started nothing: the round is asked again synchronously with the client of {@link ClientSource#syncClient()} - the one with the read timeout of
 * {@link DistributionBatches#sendTimeoutMs} - and every later round of this send goes straight there. Nothing can be stopped in that mode: the call
 * ends when Connectors ends it.</li>
 * </ol>
 * No EBX repository is touched here, so the whole flow is tested with a scripted {@link McpClient.Transport} ({@code DistributionSendCallsTest}).
 */
final class DistributionSendCalls
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Where a started run is written down (the send step's detail), the moment its id is known. */
	interface Recorder
	{
		void runStarted(String pRunId);
	}

	/** The client of the synchronous fallback, built only when it is needed (the HTTP route builds a connection). */
	interface ClientSource
	{
		McpClient syncClient() throws OperationException;
	}

	/** What one round produced. */
	static final class Round
	{
		final SyncOutcome.Result outcome;
		/** True when the job itself asked to stop and the run was stopped: the send ends STOPPED, not FAILED. */
		final boolean stoppedByHandle;
		/** True when the round ran as a followed asynchronous run; false for a synchronous answer. */
		final boolean async;

		Round(final SyncOutcome.Result pOutcome, final boolean pStoppedByHandle, final boolean pAsync)
		{
			this.outcome = pOutcome;
			this.stoppedByHandle = pStoppedByHandle;
			this.async = pAsync;
		}
	}

	private final McpClient client;
	private final ClientSource syncSource;
	private final FactoryJobs.Handle handle;
	private final long pollIntervalMs;
	private final long maxWaitMs;
	private boolean asyncSupported = true;
	private McpClient syncClient;

	DistributionSendCalls(final McpClient pClient, final ClientSource pSyncSource, final FactoryJobs.Handle pHandle)
	{
		this(pClient, pSyncSource, pHandle, LandingFlows.LOAD_POLL_INTERVAL_MS, LoadDeadline.maxMillis());
	}

	DistributionSendCalls(final McpClient pClient, final ClientSource pSyncSource, final FactoryJobs.Handle pHandle, final long pPollIntervalMs, final long pMaxWaitMs)
	{
		this.client = pClient;
		this.syncSource = pSyncSource;
		this.handle = pHandle;
		this.pollIntervalMs = pPollIntervalMs;
		this.maxWaitMs = pMaxWaitMs;
	}

	/** Whether the rounds of this send run as followed asynchronous runs (false once Connectors has refused {@code async} for it). */
	boolean asyncSupported()
	{
		return this.asyncSupported;
	}

	/** True when the job asked to stop (a no-op for a send run without a job). */
	boolean stopAsked()
	{
		return this.handle != null && this.handle.cancelled();
	}

	/**
	 * Runs one {@code connectors_sync} of {@code pArgs} (mapping, scope, dryRun, max) to its end. {@code pTableDetail} receives what a person needs to
	 * follow the run: {@code connectorsRunId}, {@code async}, the last poll, the stop.
	 */
	Round round(final ObjectNode pArgs, final ObjectNode pTableDetail, final Recorder pRecorder) throws McpException, OperationException
	{
		if (this.asyncSupported)
		{
			final ObjectNode args = pArgs.deepCopy();
			args.put("async", true);
			McpClient.ToolResult start = null;
			try
			{
				start = this.client.callTool("connectors_sync", args);
			}
			catch (final McpException ex)
			{
				if (!SendRuns.asyncRefused(ex.getMessage()))
				{
					throw ex;
				}
			}
			if (start == null || start.error && SendRuns.asyncRefused(DistributionConfigurator.errorText(start)))
			{
				// nothing was started: Connectors does not run a scope send asynchronously. Say so once, then send the way the Factory always did.
				this.asyncSupported = false;
				pTableDetail.put("asyncRefused", true);
			}
			else if (!start.error && SendRuns.isAsyncStart(start.json))
			{
				return this.follow(start.json, pTableDetail, pRecorder);
			}
			else
			{
				// an older Connectors ignored async (or the call failed for another reason): the answer is the complete synchronous one
				pTableDetail.put("async", false);
				return new Round(SyncOutcome.read(start.error, DistributionConfigurator.errorText(start), start.json), false, false);
			}
		}
		if (this.syncClient == null)
		{
			this.syncClient = this.syncSource.syncClient();
		}
		pTableDetail.put("async", false);
		final McpClient.ToolResult sync = this.syncClient.callTool("connectors_sync", pArgs);
		return new Round(SyncOutcome.read(sync.error, DistributionConfigurator.errorText(sync), sync.json), false, false);
	}

	private Round follow(final JsonNode pStartAnswer, final ObjectNode pTableDetail, final Recorder pRecorder) throws McpException, OperationException
	{
		final String runId = RunStateOutcome.runId(pStartAnswer);
		pTableDetail.put("async", true);
		pTableDetail.put("connectorsRunId", runId);
		pRecorder.runStarted(runId);
		final long started = System.currentTimeMillis();
		while (true)
		{
			if (LoadDeadline.expired(started, System.currentTimeMillis(), this.maxWaitMs))
			{
				final boolean stopAsked = this.stopRun(runId, pTableDetail);
				final JsonNode execution = this.tryRunGet(runId);
				throw EngineRefusal.coded("external.sendRunTimeout",
					"The Connectors run " + runId + " was still running after " + (System.currentTimeMillis() - started) / 60000L + " minute(s) (limit " + this.maxWaitMs / 60000L
						+ ", JVM property " + LoadDeadline.PROPERTY + "), " + RunStateOutcome.processedSoFar(execution) + " record(s) reached; "
						+ (stopAsked ? "Connectors was asked to stop the run" : "stopping the run FAILED, check it in Connectors") + ".",
					runId);
			}
			if (this.stopAsked())
			{
				this.stopRun(runId, pTableDetail);
				return this.stoppedRound(runId, pTableDetail);
			}
			try
			{
				Thread.sleep(this.pollIntervalMs);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				this.stopRun(runId, pTableDetail);
				return new Round(new SyncOutcome.Result(false, 0L, "Send interrupted while waiting for Connectors run " + runId + "; the run was asked to stop.", runId, false), true,
					true);
			}
			final ObjectNode getArgs = MAPPER.createObjectNode();
			getArgs.put("runId", runId);
			getArgs.put("callsMax", 0);
			final McpClient.ToolResult run = this.client.callTool("connectors_run_get", getArgs);
			if (run.error)
			{
				throw EngineRefusal.coded("external.pollSendRun", "connectors_run_get refused while following run " + runId + ": " + DistributionConfigurator.errorText(run), runId);
			}
			final JsonNode execution = run.json == null ? null : run.json.path("execution");
			if (execution == null || execution.isMissingNode())
			{
				continue; // the run row is written before connectors_sync answers; kept polling defensively, the limit above ends it
			}
			final String status = execution.path("status").asText("");
			if (!RunStateOutcome.isTerminal(status))
			{
				pTableDetail.put("rowsSoFar", RunStateOutcome.processedSoFar(execution));
				continue;
			}
			pTableDetail.put("runStatus", status);
			return new Round(SendRuns.terminal(run.json, runId), false, true);
		}
	}

	/** How many times a stopped run is read to see it reach its final state ({@link #pollIntervalMs} apart: a minute at the live 5 s interval). */
	static final int STOP_WAIT_POLLS = 12;

	/**
	 * The round of a run the job asked to stop. {@code connectors_run_stop} only asks: the run ends at its next batch, and its final counters are
	 * written then - read right after the request they still said 0 (Vol100k, 2026-10-12: "stopped after 0 row(s)" for a run that had sent 13000).
	 * So the run is read until it is terminal (at most {@link #STOP_WAIT_POLLS} reads) and its {@code execution{}} counters are the round's;
	 * {@code stopPending} on the table detail says the run was still going on the last read, the count is then what it had reached so far.
	 */
	private Round stoppedRound(final String pRunId, final ObjectNode pTableDetail)
	{
		JsonNode execution = this.tryRunGet(pRunId);
		for (int polls = 0; polls < STOP_WAIT_POLLS && (execution == null || !RunStateOutcome.isTerminal(execution.path("status").asText(""))); polls++)
		{
			try
			{
				Thread.sleep(this.pollIntervalMs);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				break;
			}
			execution = this.tryRunGet(pRunId);
		}
		final boolean terminal = execution != null && RunStateOutcome.isTerminal(execution.path("status").asText(""));
		if (terminal)
		{
			pTableDetail.put("runStatus", execution.path("status").asText(""));
		}
		else
		{
			pTableDetail.put("stopPending", true);
		}
		final long rows = RunStateOutcome.processedSoFar(execution);
		final long unchanged = execution == null ? 0L : execution.path("noChange").asLong(0);
		final long sent = rows - unchanged;
		pTableDetail.put("rowsSoFar", rows);
		return new Round(new SyncOutcome.Result(false, rows, sent, unchanged,
			DistributionBatches.stoppedMessage(this.handle.stopReason(), sent, unchanged, !terminal) + " Connectors run " + pRunId + (terminal ? " stopped." : " was asked to stop."),
			pRunId, false), true, true);
	}

	/** Best-effort: a failed stop call is recorded on the detail, never thrown - the run may still stop on its own. True when Connectors accepted the stop. */
	boolean stopRun(final String pRunId, final ObjectNode pTableDetail)
	{
		return DistributionSendCalls.stopRun(this.client, pRunId, pTableDetail);
	}

	static boolean stopRun(final McpClient pClient, final String pRunId, final ObjectNode pTableDetail)
	{
		try
		{
			final ObjectNode args = MAPPER.createObjectNode();
			args.put("runId", pRunId);
			final McpClient.ToolResult stop = pClient.callTool("connectors_run_stop", args);
			pTableDetail.put("stopRequested", !stop.error);
			return !stop.error;
		}
		catch (final McpException ex)
		{
			pTableDetail.put("stopError", ex.getMessage());
			return false;
		}
	}

	/** One {@code connectors_run_get} for the state of a run; null (never thrown) when even this fails. */
	private JsonNode tryRunGet(final String pRunId)
	{
		return DistributionSendCalls.tryRunGet(this.client, pRunId);
	}

	static JsonNode tryRunGet(final McpClient pClient, final String pRunId)
	{
		try
		{
			final ObjectNode args = MAPPER.createObjectNode();
			args.put("runId", pRunId);
			args.put("callsMax", 0);
			final McpClient.ToolResult run = pClient.callTool("connectors_run_get", args);
			return run.error || run.json == null ? null : run.json.path("execution");
		}
		catch (final McpException ex)
		{
			return null;
		}
	}
}
