package com.on.ebx.factory.landing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * Volume lot 7c (2026-09-28, "asynchronous landing load"): pure decisions around the new {@code
 * connectors_sync(async: true)} / {@code connectors_run_get} contract (ebx-connectors, branch {@code
 * integration/volume-wave1}, docs/REFERENCE.md &sect;2.14.5 - "Streamed read, summary answer, asynchronous
 * run"). Kept apart from {@code ebx.LandingFlows}'s poll loop (the actual MCP calls, {@code Thread.sleep},
 * a {@code FactoryJobs.Handle}) so the two genuinely new decisions this lot adds - "is this answer the
 * async-start acknowledgement or an already-complete synchronous one" and "what does a TERMINAL {@code
 * execution{}} object mean for a {@code LandingTable}" - are unit-tested directly, the same split {@link
 * LoadOutcomeRule} already keeps from {@link LandingFlow#loadOutcome}.
 * <p>
 * The two contracts this class reads, exactly as docs/REFERENCE.md &sect;2.14.5 documents them:
 * <ul>
 * <li>{@code connectors_sync(..., async: true)}'s own immediate answer - either the start acknowledgement
 * {@code {runId, status: "RUNNING", mapping, feed: true, async: true, nextStep}} (no {@code count} field at
 * all), or, on a Connectors build that does not know {@code async} and ignores it, the complete synchronous
 * answer {@code LandingFlow#loadOutcome} already reads (always carries {@code count}). {@link
 * #isAsyncStart} tells the two apart STRUCTURALLY - never from a version/build number, per this lot's own
 * instruction: an old build can only ever finish synchronously, so it can never answer {@code status:
 * "RUNNING"} with no {@code count} at all.</li>
 * <li>{@code connectors_run_get(runId)}'s {@code execution{}} object once its own {@code status} is
 * TERMINAL ({@link #isTerminal}): {@code status, mapping, nextIndex, startedAt, updatedAt, created,
 * updated, noChange, errorCount, skipped, rejected, conflicts, truncated, available, resultsOmitted,
 * firstErrors, message}. {@link #outcome} maps it onto the same {@link LandingFlow.LoadOutcome} shape
 * {@link LandingFlow#loadOutcome(JsonNode, long)} builds from a synchronous answer, per the task's own
 * field mapping: {@code count = created + updated + noChange}, {@code errors = errorCount}, {@code
 * truncated}/{@code available} read the same way and fed to the SAME {@link LoadOutcomeRule} a synchronous
 * answer uses.</li>
 * </ul>
 * Never call {@link #outcome} before {@link #isTerminal} says so - a {@code RUNNING} execution has no final
 * counters yet (its {@code created}/{@code updated}/... keep advancing); {@link #processedSoFar} /
 * {@link #errorsSoFar} are what a poll loop reads instead, while still waiting, to feed a live-progress note.
 */
public final class RunStateOutcome
{
	private RunStateOutcome()
	{
	}

	/**
	 * True when {@code pStartAnswer} is the async-start acknowledgement rather than an already-complete
	 * synchronous answer. An acknowledgement never carries {@code count} (the synchronous shape always does,
	 * whatever its {@code status}), so absence of {@code count} is checked first and is on its own sufficient;
	 * {@code runId} present and {@code status} exactly {@code "RUNNING"} are then required too, defensively,
	 * in case a future/older build's shape drifts in some other way not yet seen live.
	 */
	public static boolean isAsyncStart(final JsonNode pStartAnswer)
	{
		if (pStartAnswer == null || pStartAnswer.has("count"))
		{
			return false;
		}
		final String runId = pStartAnswer.path("runId").asText("");
		return !runId.isEmpty() && "RUNNING".equals(pStartAnswer.path("status").asText(""));
	}

	/** The run id to poll, from the async-start acknowledgement; null when {@code pStartAnswer} carries none. */
	public static String runId(final JsonNode pStartAnswer)
	{
		return pStartAnswer == null ? null : pStartAnswer.path("runId").asText(null);
	}

	/** Whether {@code connectors_run_get}'s {@code execution.status} is one a poll loop should stop on. */
	public static boolean isTerminal(final String pExecutionStatus)
	{
		return "DONE".equals(pExecutionStatus) || "PARTIAL".equals(pExecutionStatus) || "ERROR".equals(pExecutionStatus)
			|| "STOPPED".equals(pExecutionStatus) || "INTERRUPTED".equals(pExecutionStatus);
	}

	/** Rows the run has reached so far ({@code created + updated + noChange}) - safe to call on a still-RUNNING execution, or on {@code null} (0). */
	public static long processedSoFar(final JsonNode pExecution)
	{
		if (pExecution == null)
		{
			return 0L;
		}
		return pExecution.path("created").asLong(0) + pExecution.path("updated").asLong(0) + pExecution.path("noChange").asLong(0);
	}

	/** Errors the run has reached so far ({@code errorCount}) - safe on a still-RUNNING execution, or on {@code null} (0). */
	public static long errorsSoFar(final JsonNode pExecution)
	{
		return pExecution == null ? 0L : pExecution.path("errorCount").asLong(0);
	}

	/**
	 * Builds the same {@link LandingFlow.LoadOutcome} shape a synchronous answer builds
	 * ({@link LandingFlow#loadOutcome(JsonNode, long)}), from a TERMINAL {@code connectors_run_get} {@code
	 * execution{}} object instead. {@code DONE}/{@code PARTIAL} are treated as a load that happened (same
	 * posture the synchronous path already has for every status but {@code ERROR}) and go through the same
	 * {@link LoadOutcomeRule} truncated/available/row-count check a synchronous answer does; {@code ERROR}
	 * always fails; {@code STOPPED}/{@code INTERRUPTED} fail unconditionally too, with a message naming the
	 * resume point when {@code nextIndex} is known - re-running the load is the only recovery this lot
	 * implements (resuming automatically from {@code nextIndex} is explicitly left to a later lot).
	 *
	 * @param pSourceRowCount see {@link LandingFlow#loadOutcome(JsonNode, long)}'s own parameter.
	 */
	public static LandingFlow.LoadOutcome outcome(final JsonNode pExecution, final long pSourceRowCount)
	{
		return RunStateOutcome.outcome(pExecution, pSourceRowCount, false);
	}

	/**
	 * Same, for a run that may have read through a feed watermark (volume wave 2, lot V2-E, 2026-10-03): {@code
	 * pFullRefreshRequested} is whether the load asked for {@code fullRefresh=true} ({@code execution{}} does not carry it).
	 * Same two rules as {@link LandingFlow#loadOutcome(JsonNode, long, boolean)}: an incremental run that started from a
	 * checkpoint is judged on {@code truncated}/{@code available} only, never on how few rows came back, and a run
	 * that read nothing yet reports errors is a failure, not a clean load.
	 */
	public static LandingFlow.LoadOutcome outcome(final JsonNode pExecution, final long pSourceRowCount, final boolean pFullRefreshRequested)
	{
		final JsonNode execution = pExecution == null ? JsonNodeFactory.instance.objectNode() : pExecution;
		final String status = execution.path("status").asText("");
		final long rows = RunStateOutcome.processedSoFar(execution);
		final long errors = RunStateOutcome.errorsSoFar(execution);
		final String detail = RunStateOutcome.detailSuffix(execution);
		final WatermarkOutcome watermark = WatermarkOutcome.fromExecution(execution, pFullRefreshRequested);

		if ("ERROR".equals(status))
		{
			return new LandingFlow.LoadOutcome(false, rows, "Load failed: " + errors + " row(s) in error" + detail + ".", watermark);
		}
		if ("INTERRUPTED".equals(status))
		{
			return new LandingFlow.LoadOutcome(false, rows, "Load was interrupted (the EBX server restarted while it was running) after " + rows
				+ " row(s)" + RunStateOutcome.resumeHint(execution) + " - re-run the load to continue" + detail + ".");
		}
		if ("STOPPED".equals(status))
		{
			return new LandingFlow.LoadOutcome(false, rows,
				"Load was stopped after " + rows + " row(s)" + RunStateOutcome.resumeHint(execution) + " - re-run to continue" + detail + ".");
		}

		if (rows == 0 && errors > 0)
		{
			// nothing was read and the run still reports errors: never a clean "Loaded 0 row(s)" (the engine can end such a run
			// DONE/NO_DATA with its errors on the side) - fails like ERROR does
			return new LandingFlow.LoadOutcome(false, 0L, "Load failed: no row was read and " + errors + " error(s) were reported" + detail + ".", watermark);
		}

		final StringBuilder message = new StringBuilder("Loaded ").append(rows).append(" row(s)");
		if (errors > 0)
		{
			message.append(" (").append(errors).append(" error(s))").append(detail);
		}
		else if (rows == 0 && watermark.filtered())
		{
			message.append(" (no row changed at the source since the watermark)");
		}
		LandingFlow.appendWatermark(message, watermark);

		final Boolean truncated = execution.hasNonNull("truncated") ? Boolean.valueOf(execution.path("truncated").asBoolean()) : null;
		final Long available = execution.hasNonNull("available") ? Long.valueOf(execution.path("available").asLong()) : null;
		final LoadOutcomeRule.Decision decision = LoadOutcomeRule.decide(truncated, available, rows, watermark.skipped, watermark.filtered(), pSourceRowCount);
		if (decision.level == LoadOutcomeRule.Level.FAILED)
		{
			message.append(" - FAILED: ").append(decision.reason).append('.');
			return new LandingFlow.LoadOutcome(false, rows, message.toString(), watermark);
		}
		if (decision.level == LoadOutcomeRule.Level.WARN)
		{
			message.append(" - WARNING: ").append(decision.reason).append('.');
			return new LandingFlow.LoadOutcome(true, rows, message.toString(), watermark);
		}
		message.append('.');
		return new LandingFlow.LoadOutcome(true, rows, message.toString(), watermark);
	}

	/**
	 * A {@link LandingFlow.LoadOutcome} for a table whose own job {@code FactoryJobs.Handle} asked to stop
	 * MID-POLL (never a run that reached {@code STOPPED} on its own for some other reason - {@link #outcome}
	 * covers that case). Package-private construction of {@link LandingFlow.LoadOutcome} means {@code
	 * ebx.LandingFlows} (a different package) needs this factory rather than building one directly.
	 */
	public static LandingFlow.LoadOutcome stoppedByHandle(final String pStopReason, final long pRowsSoFar)
	{
		return new LandingFlow.LoadOutcome(false, pRowsSoFar, "Load stopped (" + pStopReason + ") after " + pRowsSoFar + " row(s) - re-run to continue.");
	}

	/** A {@link LandingFlow.LoadOutcome} for the poll loop's own wait being interrupted - the caller restores the thread's interrupt flag itself. */
	public static LandingFlow.LoadOutcome interrupted()
	{
		return new LandingFlow.LoadOutcome(false, 0, "Load interrupted while waiting for Connectors - re-run to continue.");
	}

	/** {@code " (resume point: item N)"} when {@code nextIndex} is known, else empty - never used to actually resume in this lot, only shown. */
	private static String resumeHint(final JsonNode pExecution)
	{
		return pExecution.hasNonNull("nextIndex") ? " (resume point: item " + pExecution.path("nextIndex").asLong() + ")" : "";
	}

	/** {@code ": <first line of firstErrors, else message>"}, or empty when neither is present. */
	private static String detailSuffix(final JsonNode pExecution)
	{
		final String firstErrors = pExecution.path("firstErrors").asText("");
		if (!firstErrors.isEmpty())
		{
			return ": " + RunStateOutcome.firstLine(firstErrors);
		}
		final String message = pExecution.path("message").asText("");
		return message.isEmpty() ? "" : ": " + message;
	}

	private static String firstLine(final String pText)
	{
		final int newline = pText.indexOf('\n');
		return newline < 0 ? pText : pText.substring(0, newline);
	}
}
