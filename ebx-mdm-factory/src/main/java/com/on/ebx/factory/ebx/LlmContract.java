package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestranetworks.service.Session;

/**
 * Wave 11 (audit F-26): the language model operations of the Connectors contract ({@code llm.connectors}, {@code llm.ask}) as this module
 * uses them - no key, no URL, no table of Connectors is read here: Connectors asks the model with the connection's own client and stored key and
 * answers text and token counts. One question, one answer, no tool: what needs several turns or tools stays on {@link AgentRuntime#run}
 * (contract 1.0.0 has no such operation; see {@code docs/CONNECTORS-COUPLING.md}).
 */
final class LlmContract
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** The contract's own bounds of {@code llm.ask.timeoutMs}. */
	static final int MIN_TIMEOUT_MS = 1000;
	static final int MAX_TIMEOUT_MS = 600000;
	/** The call waits a little longer than the model: the contract answers {@code TIMEOUT} itself at {@code timeoutMs}. */
	private static final int OUTER_MARGIN_MS = 15000;

	private LlmContract()
	{
	}

	/** What {@code llm.ask} answered. */
	static final class Answer
	{
		final String text;
		final String model;
		final String provider;
		final String connector;
		final int inputTokens;
		final int outputTokens;
		final boolean truncated;
		final long durationMs;

		Answer(final String pText, final String pModel, final String pProvider, final String pConnector, final int pInputTokens, final int pOutputTokens,
			final boolean pTruncated, final long pDurationMs)
		{
			this.text = pText == null ? "" : pText;
			this.model = pModel == null ? "" : pModel;
			this.provider = pProvider == null ? "" : pProvider;
			this.connector = pConnector == null ? "" : pConnector;
			this.inputTokens = pInputTokens;
			this.outputTokens = pOutputTokens;
			this.truncated = pTruncated;
			this.durationMs = pDurationMs;
		}
	}

	/** Pure: the {@code llm.ask} parameters; a blank connector or system is left out (the contract then takes its default and the connection's own system prompt). */
	static ObjectNode askParams(final String pConnector, final String pSystem, final String pPrompt, final int pMaxTokens, final boolean pJson, final int pTimeoutMs)
	{
		final ObjectNode params = LlmContract.MAPPER.createObjectNode();
		if (pConnector != null && !pConnector.trim().isEmpty())
		{
			params.put("connector", pConnector.trim());
		}
		if (pSystem != null && !pSystem.trim().isEmpty())
		{
			params.put("system", pSystem);
		}
		params.put("prompt", pPrompt);
		params.put("maxTokens", Math.max(1, Math.min(65536, pMaxTokens)));
		params.put("json", pJson);
		params.put("timeoutMs", Math.max(LlmContract.MIN_TIMEOUT_MS, Math.min(LlmContract.MAX_TIMEOUT_MS, pTimeoutMs)));
		return params;
	}

	/** Pure: {@code llm.ask}'s result as an {@link Answer}. */
	static Answer answerOf(final JsonNode pResult)
	{
		final JsonNode usage = pResult.path("usage");
		return new Answer(pResult.path("text").asText(""), pResult.path("model").asText(""), pResult.path("provider").asText(""), pResult.path("connector").asText(""),
			usage.path("inputTokens").asInt(0), usage.path("outputTokens").asInt(0), pResult.path("truncated").asBoolean(false), pResult.path("durationMs").asLong(0L));
	}

	/** One prompt to the connection {@code pConnector} (null = the contract's default) as {@code pSession}; {@code pTimeoutMs} is the model's own time limit. */
	static Answer ask(final Session pSession, final String pConnector, final String pSystem, final String pPrompt, final int pMaxTokens, final boolean pJson, final int pTimeoutMs)
		throws ConnectorsContractClient.Refusal
	{
		final ObjectNode params = LlmContract.askParams(pConnector, pSystem, pPrompt, pMaxTokens, pJson, pTimeoutMs);
		return LlmContract.answerOf(ConnectorsContractClient.call(pSession, "llm.ask", params, params.path("timeoutMs").asInt() + LlmContract.OUTER_MARGIN_MS));
	}

	/**
	 * {@link #ask} retried per {@link LlmRetryPolicy} on a transient failure of the provider (429/503: {@code LLM_FAILED} carrying the HTTP status in its message) -
	 * the same policy the direct way applies to its own call, a genuine single-call retry. Anything else is thrown at once.
	 */
	static Answer askWithRetry(final Session pSession, final String pConnector, final String pSystem, final String pPrompt, final int pMaxTokens, final boolean pJson,
		final int pTimeoutMs) throws ConnectorsContractClient.Refusal
	{
		int attempt = 0;
		long waitedMs = 0L;
		while (true)
		{
			try
			{
				return LlmContract.ask(pSession, pConnector, pSystem, pPrompt, pMaxTokens, pJson, pTimeoutMs);
			}
			catch (final ConnectorsContractClient.Refusal ex)
			{
				if (!ConnectorsContractClient.LLM_FAILED.equals(ex.type) || !LlmRetryPolicy.isRetryableMessage(ex.getMessage()))
				{
					throw ex;
				}
				attempt++;
				final long delayMs = LlmRetryPolicy.nextDelayMs(attempt, ex.getMessage());
				if (!LlmRetryPolicy.shouldRetry(attempt, waitedMs, delayMs))
				{
					throw ex;
				}
				try
				{
					Thread.sleep(delayMs);
				}
				catch (final InterruptedException interrupted)
				{
					Thread.currentThread().interrupt();
					throw ex;
				}
				waitedMs += delayMs;
			}
		}
	}

	/** Pure: {@code llm.connectors}'s result as the picker's options (active first, then by label - the order of the direct read). */
	static List<AgentRuntime.LlmConnectorOption> optionsOf(final JsonNode pResult)
	{
		final List<AgentRuntime.LlmConnectorOption> out = new ArrayList<AgentRuntime.LlmConnectorOption>();
		for (final JsonNode connector : pResult.path("connectors"))
		{
			final String id = connector.path("id").asText("");
			if (!id.isEmpty())
			{
				out.add(new AgentRuntime.LlmConnectorOption(id, connector.path("label").asText(""), connector.path("provider").asText(""), connector.path("model").asText(""),
					connector.path("active").asBoolean(true), connector.path("maxTokens").asInt(0), connector.path("chat").asBoolean(false)));
			}
		}
		out.sort(new java.util.Comparator<AgentRuntime.LlmConnectorOption>()
		{
			@Override
			public int compare(final AgentRuntime.LlmConnectorOption pA, final AgentRuntime.LlmConnectorOption pB)
			{
				if (pA.active != pB.active)
				{
					return pA.active ? -1 : 1;
				}
				return pA.label.compareToIgnoreCase(pB.label);
			}
		});
		return out;
	}

	/** {@code llm.connectors} as the picker's options. */
	static List<AgentRuntime.LlmConnectorOption> connectors(final Session pSession) throws ConnectorsContractClient.Refusal
	{
		return LlmContract.optionsOf(ConnectorsContractClient.call(pSession, "llm.connectors", null));
	}
}
