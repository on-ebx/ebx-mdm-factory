package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.landing.RunStateOutcome;

/**
 * Wave 13 (2026-10-12, "asynchronous distribution send"): the pure decisions around a distribution send that runs as a Connectors run
 * the Factory follows, instead of one long synchronous {@code connectors_sync} call. Kept apart from {@code ebx.DistributionSendCalls}
 * (the MCP calls, the sleep, the stop check) so each decision is unit-tested alone, the split {@code landing.RunStateOutcome} keeps for the
 * landing load - whose pure pieces ({@link RunStateOutcome#isAsyncStart}, {@link RunStateOutcome#isTerminal}, {@link
 * RunStateOutcome#processedSoFar}) this class reuses rather than copies.
 * <p>
 * <b>The contract this reads</b> (ebx-connectors {@code docs/REFERENCE.md} &sect;2.14.5, written for an inbound feed):
 * <ul>
 * <li>{@code connectors_sync(..., async: true)} answers at once {@code {runId, status: "RUNNING", mapping, async: true}} - no {@code count};
 * a Connectors that ignores {@code async} answers the complete synchronous shape {@link SyncOutcome#read} reads ({@link #isAsyncStart} tells
 * the two apart by shape, never by version);</li>
 * <li>{@code connectors_run_get(runId)} answers {@code execution{status, created, updated, noChange, errorCount, skipped, rejected,
 * conflicts, truncated, available, firstErrors, message, nextIndex}} - {@code status} RUNNING, then DONE / PARTIAL / ERROR / STOPPED /
 * INTERRUPTED.</li>
 * </ul>
 * <b>Measured against the Connectors of 2026-10-12: that module accepts {@code async} for an inbound feed only</b> - for a scope send it
 * refuses with {@code "async only applies to an inbound feed with no key, predicate or scope"} ({@link #asyncRefused}), and it keeps no
 * {@code execution{}} state for an outbound scope run. The Factory therefore asks for {@code async} first and, on that refusal, sends the
 * way it did before (one synchronous call, read timeout {@link DistributionBatches#sendTimeoutMs}); the day Connectors accepts it, the same
 * code follows the run. How the {@code execution{}} counters read for a send is {@link #terminal}.
 */
public final class SendRuns
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** The field {@code firstFullSendRunId} is {@code xs:string} of this maximum length ({@code tools/build_xsd.py}). */
	public static final int FIELD_MAX_LENGTH = 80;
	/** At most this many run ids are kept per table in a step's detail: a {@code changed} backlog of a thousand rounds must not make the detail unwritable. */
	public static final int RUN_IDS_KEPT = 20;

	private SendRuns()
	{
	}

	/** True when {@code pStartAnswer} is the asynchronous start acknowledgement, false for the complete synchronous answer of a Connectors that ignored {@code async}. */
	public static boolean isAsyncStart(final JsonNode pStartAnswer)
	{
		return RunStateOutcome.isAsyncStart(pStartAnswer);
	}

	/**
	 * True when {@code pMessage} is Connectors saying it does not run THIS kind of call asynchronously ("async only applies to an inbound feed
	 * with no key, predicate or scope"): nothing was started, the send can be asked again synchronously.
	 */
	public static boolean asyncRefused(final String pMessage)
	{
		if (pMessage == null)
		{
			return false;
		}
		final String lower = pMessage.toLowerCase(java.util.Locale.ENGLISH);
		return lower.contains("async only applies") || lower.contains("async") && lower.contains("inbound feed");
	}

	/** Whether a {@code connectors_run_get} answer reports the run as still running ({@code execution.status} RUNNING). */
	public static boolean isRunning(final JsonNode pRunGetAnswer)
	{
		return pRunGetAnswer != null && "RUNNING".equals(pRunGetAnswer.path("execution").path("status").asText(""));
	}

	/** {@code execution.status} of a {@code connectors_run_get} answer, or empty when it carries none. */
	public static String status(final JsonNode pRunGetAnswer)
	{
		return pRunGetAnswer == null ? "" : pRunGetAnswer.path("execution").path("status").asText("");
	}

	/**
	 * The verdict of a TERMINAL run, in the shape the send already reads ({@link SyncOutcome.Result}), from a {@code connectors_run_get}
	 * answer - so {@code DistributionBatches.askAgain}, the cut messages and the totals keep working unchanged.
	 * <ul>
	 * <li>{@code rows} = {@code created + updated + noChange} of {@code execution{}} (what the landing counts too); the synchronous answer's
	 * {@code count} is not in a run state, so this is derived. Next to it (wave 13) {@code sent} = {@code created + updated} and {@code unchanged} =
	 * {@code noChange}, so a {@code changed} send where every record was unchanged reads "0 sent, N unchanged", not "N sent". This holds for EVERY
	 * status, STOPPED included: the rows a stopped run had sent are in its final counters;</li>
	 * <li>{@code truncated} = {@code execution.truncated} (the run stopped at its {@code max} with more records left);</li>
	 * <li>DONE and PARTIAL are ok when {@code errorCount} is 0 - a PARTIAL that only means "cut at max" is a success with {@code truncated} set,
	 * like the synchronous answer ({@link SyncOutcome#read}); with errors they fail, the message naming the first one;</li>
	 * <li>ERROR, STOPPED and INTERRUPTED (the EBX server restarted while the run went) always fail.</li>
	 * </ul>
	 */
	public static SyncOutcome.Result terminal(final JsonNode pRunGetAnswer, final String pRunId)
	{
		final JsonNode execution = pRunGetAnswer == null ? MAPPER.createObjectNode() : pRunGetAnswer.path("execution");
		final String status = execution.path("status").asText("");
		final long rows = RunStateOutcome.processedSoFar(execution);
		final long errors = RunStateOutcome.errorsSoFar(execution);
		final long unchanged = execution.path("noChange").asLong(0);
		final long sent = rows - unchanged;
		final boolean truncated = execution.path("truncated").asBoolean(false);
		final String detail = SendRuns.detailSuffix(execution);
		final String run = pRunId == null ? "" : "Connectors run " + pRunId;
		if ("ERROR".equals(status))
		{
			return new SyncOutcome.Result(false, rows, sent, unchanged, run + " ended in ERROR after " + rows + " record(s), " + errors + " in error" + detail + ".", pRunId, truncated);
		}
		if ("STOPPED".equals(status))
		{
			return new SyncOutcome.Result(false, rows, sent, unchanged, run + " was stopped after " + sent + " record(s) sent" + (unchanged > 0L ? " (" + unchanged + " found unchanged)" : "")
				+ "; the records sent keep their outbound state, a 'changed' send resumes after them" + SendRuns.resumeHint(execution) + detail + ".", pRunId, truncated);
		}
		if ("INTERRUPTED".equals(status))
		{
			return new SyncOutcome.Result(false, rows, sent, unchanged, run + " was interrupted (the EBX server restarted while it was running) after " + rows + " record(s)"
				+ SendRuns.resumeHint(execution) + detail + ".", pRunId, truncated);
		}
		if (errors > 0)
		{
			return new SyncOutcome.Result(false, rows, sent, unchanged, run + " reported " + errors + " record(s) in error after " + sent + " sent" + detail + ".", pRunId, truncated);
		}
		return new SyncOutcome.Result(true, rows, sent, unchanged, "", pRunId, truncated);
	}

	/**
	 * The run ids a new send must check before it starts: the Connectors run recorded for each table of the target's latest send step
	 * ({@code detail.tables[].connectorsRunId}, the last round of the table - the earlier ones ended before it started), then the ids stored on the
	 * target ({@code firstFullSendRunId}, comma-separated). Distinct, in that order; blank and unreadable input give nothing.
	 */
	public static List<String> candidateRunIds(final String pStepDetailJson, final String pFirstFullSendRunId)
	{
		final Set<String> ids = new LinkedHashSet<String>();
		if (pStepDetailJson != null && !pStepDetailJson.trim().isEmpty())
		{
			try
			{
				for (final JsonNode table : MAPPER.readTree(pStepDetailJson).path("tables"))
				{
					final String id = table.path("connectorsRunId").asText("").trim();
					if (!id.isEmpty())
					{
						ids.add(id);
					}
				}
			}
			catch (final java.io.IOException ex)
			{
				// a detail cut at its size limit is not JSON: nothing to read from it, the stored ids below still count
			}
		}
		if (pFirstFullSendRunId != null)
		{
			for (final String part : pFirstFullSendRunId.split(","))
			{
				if (!part.trim().isEmpty())
				{
					ids.add(part.trim());
				}
			}
		}
		return new ArrayList<String>(ids);
	}

	/** Answers whether Connectors still reports a run as running: false for a run it no longer knows or cannot answer for. */
	public interface RunLookup
	{
		boolean isRunning(String pRunId);
	}

	/** The first of {@code pCandidates} that {@code pLookup} says is still running, or null: the double-send guard's decision. */
	public static String firstRunning(final List<String> pCandidates, final RunLookup pLookup)
	{
		for (final String id : pCandidates)
		{
			if (pLookup.isRunning(id))
			{
				return id;
			}
		}
		return null;
	}

	/**
	 * What {@code DistributionTarget.firstFullSendRunId} holds after a successful first full send: the Connectors run ids of its mappings, comma-
	 * separated, as many as fit {@link #FIELD_MAX_LENGTH} (whole ids only; the step's detail has them all); {@code pFallback} (the Factory step
	 * id) when there is none. Never empty when {@code pFallback} is not - the wizard ticks "first full send done" on a non-empty value.
	 */
	public static String fieldValue(final List<String> pRunIds, final String pFallback)
	{
		final StringBuilder value = new StringBuilder();
		for (final String id : pRunIds)
		{
			if (id == null || id.trim().isEmpty())
			{
				continue;
			}
			final int needed = (value.length() == 0 ? 0 : 1) + id.length();
			if (value.length() + needed > SendRuns.FIELD_MAX_LENGTH)
			{
				break;
			}
			if (value.length() > 0)
			{
				value.append(',');
			}
			value.append(id);
		}
		if (value.length() == 0)
		{
			return pFallback;
		}
		return value.toString();
	}

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
			final int newline = firstErrors.indexOf('\n');
			return ": " + (newline < 0 ? firstErrors : firstErrors.substring(0, newline));
		}
		final String message = pExecution.path("message").asText("");
		return message.isEmpty() ? "" : ": " + message;
	}
}
