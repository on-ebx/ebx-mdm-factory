package com.on.ebx.factory.distribution;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Distribution round 4 fix (2026-09-26, live bug on DistribTest, task brief item 1 - "false success"):
 * {@code connectors_sync} answers WITHOUT a JSON-RPC/MCP tool error even when the actual publish failed - the
 * transport-level {@code McpClient.ToolResult#error} (the endpoint's own {@code isError}, read from
 * {@code data.has("error")} on the Connectors side - {@code ebx-connectors}'
 * {@code ConnectorsTools#invoke}/{@code McpEndpoint#handle}) is only set when the TOOL CALL ITSELF threw
 * ({@code ConnectorException}/{@code IllegalArgumentException}/any {@code Exception}). An ordinary
 * {@code connectors_sync} run that reaches the target and fails there (a downed Kafka broker, a rejected
 * write) does NOT throw: it returns a normal, non-error JSON-RPC result whose own
 * {@code structuredContent} carries the real per-record outcome in {@code SyncResult.Status}
 * ({@code OK}/{@code NO_CHANGE}/{@code NO_DATA}/{@code SKIPPED}/{@code ERROR}/{@code DRY_RUN}/{@code PARTIAL})
 * and the aggregate counters {@code ebx-connectors}' {@code ConnectorsTools#putOutcome} computes from them
 * ({@code ok}/{@code errors}/{@code failed}/{@code partial}/{@code skipped}/{@code noData}/{@code status}).
 * <p>
 * Measured live, 2026-09-26, DistribTest project, target {@code KafkaMdmDistribTest} (mapping 302, broker
 * down): {@code connectors_log_search} showed {@code status: ERROR, message: "Kafka publish to
 * mdm.distribtest.person failed: org.apache.kafka.common.errors.TimeoutException: Topic
 * mdm.distribtest.person not present in metadata after 10000 ms.", runId: R20260926-133426-0004} while the
 * {@code connectors_sync} MCP call that produced it answered a plain, non-error result - the Factory's own
 * {@code DistributionEvent} row for that same send read {@code status SENT, attempts 1, message null},
 * because {@code DistributionDispatcher}/{@code DistributionRunner} trusted {@code ToolResult.error} alone.
 * <p>
 * This class is now the ONLY place allowed to decide whether a {@code connectors_sync} call actually
 * succeeded - both callers ({@code DistributionRunner#send}, one call per mapping/table of a full or changed
 * batch; {@code DistributionDispatcher#drainGroup}'s per-record real-time send) go through {@link #read}
 * instead of reading {@code ToolResult.error} directly. It does NOT apply to {@code connectors_call} (used
 * for a RETIRE notice, {@code DistributionDispatcher#sendRetireNotice}): that tool calls the connector
 * directly and a broker/target failure DOES surface as a thrown {@code ConnectorException} there, so
 * {@code ToolResult.error} is already the right signal for it - see that method's own call site.
 * <p>
 * The JSON shape read here is shared by BOTH {@code connectors_sync} answer shapes this module calls -
 * {@code ConnectorsTools#results} (a batch send with an explicit {@code scope}, one item per record) and
 * {@code ConnectorsTools#enrich} (a single record, {@code key}/{@code predicate}, no {@code scope} - what
 * {@code DistributionDispatcher#sendRecord} sends): both build their top-level {@code ok}/{@code errors}/
 * {@code failed}/{@code partial}/{@code skipped}/{@code noData}/{@code status} fields through the SAME
 * {@code ConnectorsTools#putOutcome}, and both list per-record outcomes under {@code results[]}, each carrying
 * its own {@code status}/{@code message}/{@code errors[]} (read directly off {@code ebx-connectors}' source,
 * {@code ConnectorsTools.java} and {@code SyncResult.java}, 2026-09-26 - not a live capture of the JSON, since
 * this session was read-only against the instance; the shape is exact, not inferred).
 */
public final class SyncOutcome
{
	private SyncOutcome()
	{
	}

	/** The verdict this class computes for one {@code connectors_sync} tool call. */
	public static final class Result
	{
		/** True only when the call reached the target and {@code connectors_sync}'s own {@code errors} counter is zero - see {@link #read}. */
		public final boolean ok;
		/** {@code count} (batch send) or the number of {@code results[]} entries (single-record send) - a proxy for "records processed", never negative. */
		public final long rows;
		/**
		 * Wave 13 (2026-10-12): records of {@link #rows} that were actually SENT to the target (a run state's {@code created + updated}). A send that finds
		 * every record unchanged ({@code changed} scope) covers rows but sends none. For the synchronous answer ({@link #read}) this is
		 * {@code rows - unchanged}, i.e. APPROXIMATE: records Connectors counts neither as sent nor as unchanged (NO_DATA, SKIPPED, errors) are included.
		 */
		public final long sent;
		/**
		 * Wave 13: records of {@link #rows} Connectors found unchanged and did not send ({@code noChange} of a run state). For the synchronous answer
		 * ({@link #read}) it is the number of {@code results[]} items with status {@code NO_CHANGE} - APPROXIMATE: an older Connectors may cap or omit
		 * {@code results[]} on a big batch, and then reads 0 (the whole batch counts as sent).
		 */
		public final long unchanged;
		/** Empty when {@link #ok} is true. Connectors' own message/errors text, never re-worded. */
		public final String message;
		/** {@code connectors_sync}'s own {@code runId} (its {@code SyncLog} run, replayable with {@code connectors_replay}), or null when the call never reached Connectors (a transport/protocol failure). */
		public final String runId;
		/** {@code connectors_sync}'s {@code truncated}: the run stopped at its {@code max} with more records left (Connectors audit C-35). Absent from older Connectors builds, read as false. */
		public final boolean truncated;

		public Result(final boolean pOk, final long pRows, final String pMessage, final String pRunId)
		{
			this(pOk, pRows, pMessage, pRunId, false);
		}

		public Result(final boolean pOk, final long pRows, final String pMessage, final String pRunId, final boolean pTruncated)
		{
			this(pOk, pRows, pRows, 0L, pMessage, pRunId, pTruncated);
		}

		/** {@code rows} covers everything the round touched ({@code askAgain} and the cut messages read it); {@code sent} and {@code unchanged} are its two parts. */
		public Result(final boolean pOk, final long pRows, final long pSent, final long pUnchanged, final String pMessage, final String pRunId, final boolean pTruncated)
		{
			this.ok = pOk;
			this.rows = pRows;
			this.sent = pSent;
			this.unchanged = pUnchanged;
			this.message = pMessage == null ? "" : pMessage;
			this.runId = pRunId;
			this.truncated = pTruncated;
		}
	}

	/**
	 * @param pTransportError {@code McpClient.ToolResult#error} - a genuine JSON-RPC tool failure (the call
	 *        itself threw on the Connectors side: unknown mapping, malformed arguments, a connector
	 *        configuration error). This is NOT what flags an ordinary send failure - see class Javadoc.
	 * @param pTransportErrorText the text to use when {@code pTransportError} is true (the caller's own
	 *        {@code DistributionConfigurator#errorText}, computed unconditionally - harmless when unused).
	 * @param pJson {@code McpClient.ToolResult#json} - {@code connectors_sync}'s {@code structuredContent}.
	 */
	public static Result read(final boolean pTransportError, final String pTransportErrorText, final JsonNode pJson)
	{
		if (pTransportError)
		{
			return new Result(false, 0L, pTransportErrorText == null ? "(no message)" : pTransportErrorText, SyncOutcome.textOrNull(pJson, "runId"));
		}
		if (pJson == null)
		{
			return new Result(false, 0L, "connectors_sync answered no readable content", null);
		}
		final String runId = SyncOutcome.textOrNull(pJson, "runId");
		final int errors = pJson.path("errors").asInt(0);
		final long rows = pJson.has("count") ? pJson.path("count").asLong(0L) : pJson.path("results").size();
		final boolean truncated = pJson.path("truncated").asBoolean(false);
		long unchanged = 0L;
		for (final JsonNode item : pJson.path("results"))
		{
			if ("NO_CHANGE".equals(item.path("status").asText("")))
			{
				unchanged++;
			}
		}
		unchanged = Math.min(unchanged, rows);
		if (errors == 0)
		{
			return new Result(true, rows, rows - unchanged, unchanged, "", runId, truncated);
		}
		return new Result(false, rows, rows - unchanged, unchanged, SyncOutcome.failureMessage(pJson, errors), runId, truncated);
	}

	/** One clause per failed/partial item of {@code connectors_sync}'s own {@code results[]} array. */
	private static String failureMessage(final JsonNode pJson, final int pErrorCount)
	{
		final StringBuilder message = new StringBuilder();
		for (final JsonNode item : pJson.path("results"))
		{
			final String status = item.path("status").asText("");
			final boolean itemFailed = "ERROR".equals(status) || "PARTIAL".equals(status) || item.path("errors").size() > 0;
			if (!itemFailed)
			{
				continue;
			}
			if (message.length() > 0)
			{
				message.append(' ');
			}
			final String label = SyncOutcome.textOrNull(item, "key") != null ? item.path("key").asText()
				: SyncOutcome.textOrNull(item, "record") != null ? item.path("record").asText()
					: SyncOutcome.textOrNull(item, "mapping") != null ? item.path("mapping").asText() : "";
			if (!label.isEmpty())
			{
				message.append(label).append(": ");
			}
			message.append(SyncOutcome.itemMessage(item));
		}
		if (message.length() == 0)
		{
			message.append("connectors_sync reported ").append(pErrorCount).append(" failed item(s)");
		}
		return message.toString();
	}

	private static String itemMessage(final JsonNode pItem)
	{
		final String text = pItem.path("message").asText("");
		if (!text.isEmpty())
		{
			return text;
		}
		final JsonNode errors = pItem.path("errors");
		if (errors.size() > 0)
		{
			return errors.get(0).asText("");
		}
		return "ERROR";
	}

	private static String textOrNull(final JsonNode pNode, final String pField)
	{
		if (pNode == null)
		{
			return null;
		}
		final JsonNode value = pNode.get(pField);
		return value == null || value.isNull() ? null : value.asText();
	}
}
