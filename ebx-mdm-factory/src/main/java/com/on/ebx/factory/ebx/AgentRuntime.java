package com.on.ebx.factory.ebx;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.LlmChat;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.LlmException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Builds and runs an {@link AgentLoop} for the factory: reads {@code FactorySettings} (which connector, the
 * step/token budgets), loads the {@code llm} connector of ebx-connectors as data (never as a Java
 * dependency - see the knowledge base's "couplage par la donnée" rule), builds the matching {@link
 * LlmClient}, and runs the loop with whatever {@link ToolSet} the caller built (local tools only, an MCP
 * {@link ToolSet}, or both - this class does not know or care which phase it is serving). Every run is
 * traced: one {@code AgentRun} row created before the loop starts (status {@code RUNNING}) and updated once
 * it ends, one {@code AgentStep} row per model turn and per tool call (including a refused one), each row
 * written in its OWN procedure - a rollback of the loop's own work (a caller's later write that fails) must
 * never take the trace down with it, and a listener write failure must fail the run loudly rather than leave
 * a silent gap in the trace (see {@link #writeStep}).
 * <p>
 * The connector's API key never appears in a log line, an exception message reported past this class, or any
 * row: only the {@link LlmClient} headers carry it, and {@link LlmClient} never logs its own headers.
 * <p>
 * <b>Wave 11 (audit F-26)</b>: this class no longer reads Connectors' tables itself. The multi-turn loop above gets its connection from
 * {@link AgentLlmConnector#load} (the ONE class that reads the key; the Connectors contract {@code ebx-connectors.sibling} 1.0.0 has no
 * several-turn or tool operation - {@code docs/CONNECTORS-COUPLING.md} section 6 says what 1.1 would need), while what the contract covers
 * does not come through here any more when Connectors offers it ({@code ebx.factory.connectors.via}, {@link ConnectorsContractClient}):
 * {@link #ask} (one prompt, no tool), {@link #testLlm(Session, Adaptation)} and {@link #llmConnectors(Repository, Session)}.
 * <p>
 * <b>Wave 12</b>: the multi-turn loop no longer reads the connector's key when Connectors offers contract 1.1.0 with {@code llm.chat} for the connection:
 * {@link AgentRoute#resolve} decides the way (contract chat, run as {@code pSession}, or the direct fallback of {@link AgentLlmConnector#load}), refuses up front a connection
 * whose {@code maxTokens} is below what a pass needs, and logs once which way is taken; see {@link AgentRoute} and {@code docs/CONNECTORS-COUPLING.md}.
 * <p>
 * <b>429/503 retry, 2026-09-25</b> (project {@code PersVol2}): {@link #run} retries a whole {@code
 * AgentLoop.run(...)} call when it ends {@code FAILED} on a transient LLM failure, and {@link #testLlm}
 * retries its own direct {@code LlmClient.send} call the same way - see {@link LlmRetryPolicy}'s own
 * Javadoc for the policy (429/503 only, the provider's own stated delay when it states one, at most 4
 * retries and 60s of cumulative waiting) and for why a whole run, not a single internal step, is the only
 * retry unit {@code AgentLoop} (a {@code final} class of the separate {@code on-ebx-agent} repository)
 * actually exposes.
 */
public final class AgentRuntime
{
	private static final int DEFAULT_MAX_TOKENS = 4096;
	private static final int DEFAULT_MAX_STEPS = 30;
	private static final int DEFAULT_MAX_TOTAL_TOKENS = 300000;
	/**
	 * {@code AgentLoop.Budget} has a wall-clock limit too, but {@code FactorySettings} has no field for it yet
	 * (only a step count and a token count): a fixed, generous ceiling is enough for this lot, since a run
	 * this long would already have hit one of the other two budgets well before this one matters.
	 */
	private static final long WALL_CLOCK_BUDGET_MS = 600000L;

	// AgentRun.goal / AgentRun.outcome / AgentStep.arguments / AgentStep.result: keep in step with build_xsd.py
	private static final int GOAL_MAX_LENGTH = 8000;
	static final int OUTCOME_MAX_LENGTH = 8000;
	private static final int ARGUMENTS_MAX_LENGTH = 100000;
	private static final int RESULT_MAX_LENGTH = 100000;

	private AgentRuntime()
	{
	}

	// ------------------------------------------------------------------------------------------------- pure parts

	/** Everything {@link #settingsFor} needs, already read out of the connector row and its properties as plain values - no EBX type. */
	public static final class ConnectorInfo
	{
		public final String baseUrl;
		/** The {@code provider} property, or null/blank/"auto" to detect it from the base URL. */
		public final String providerOverride;
		/** The {@code model} property, or null to use the provider's default. */
		public final String model;
		/** {@code NONE} / {@code API_KEY_HEADER} / {@code BEARER} (anything else is treated like {@code BEARER}), upper-cased by the caller. */
		public final String authType;
		public final String apiKeyHeader;
		/** Already decrypted/dereferenced; null when the connector needs no key ({@code authType=NONE}). */
		public final String apiKey;
		/** The {@code openai.maxTokensParam} property, or null for {@link LlmClient#DEFAULT_OPENAI_MAX_TOKENS_PARAM}. */
		public final String openAiMaxTokensParam;
		/** The {@code openai.reasoningEffort} property ({@code reasoning_effort} of OpenAI-shaped requests), or null/blank = not sent (2026-09-26). */
		public final String openAiReasoningEffort;
		/** The {@code azure.apiVersion} property, or null for a built-in default. */
		public final String azureApiVersion;
		/** The {@code anthropic.version} property, or null for a built-in default. */
		public final String anthropicVersion;
		/** The {@code maxTokens} property, or null to fall back to {@link #DEFAULT_MAX_TOKENS}. */
		public final Integer maxTokens;
		public final int connectTimeoutMs;
		public final int readTimeoutMs;

		public ConnectorInfo(final String pBaseUrl, final String pProviderOverride, final String pModel, final String pAuthType, final String pApiKeyHeader,
			final String pApiKey, final String pOpenAiMaxTokensParam, final String pAzureApiVersion, final String pAnthropicVersion, final Integer pMaxTokens,
			final int pConnectTimeoutMs, final int pReadTimeoutMs)
		{
			this(pBaseUrl, pProviderOverride, pModel, pAuthType, pApiKeyHeader, pApiKey, pOpenAiMaxTokensParam, pAzureApiVersion, pAnthropicVersion, pMaxTokens,
				pConnectTimeoutMs, pReadTimeoutMs, null);
		}

		/** Same as the 12-argument constructor, plus the {@code openai.reasoningEffort} property (null = not sent). */
		public ConnectorInfo(final String pBaseUrl, final String pProviderOverride, final String pModel, final String pAuthType, final String pApiKeyHeader,
			final String pApiKey, final String pOpenAiMaxTokensParam, final String pAzureApiVersion, final String pAnthropicVersion, final Integer pMaxTokens,
			final int pConnectTimeoutMs, final int pReadTimeoutMs, final String pOpenAiReasoningEffort)
		{
			this.baseUrl = pBaseUrl;
			this.providerOverride = pProviderOverride;
			this.model = pModel;
			this.authType = pAuthType;
			this.apiKeyHeader = pApiKeyHeader;
			this.apiKey = pApiKey;
			this.openAiMaxTokensParam = pOpenAiMaxTokensParam;
			this.openAiReasoningEffort = pOpenAiReasoningEffort;
			this.azureApiVersion = pAzureApiVersion;
			this.anthropicVersion = pAnthropicVersion;
			this.maxTokens = pMaxTokens;
			this.connectTimeoutMs = pConnectTimeoutMs;
			this.readTimeoutMs = pReadTimeoutMs;
		}
	}

	/** What {@link #settingsFor} decided: everything {@link LlmClient}'s 9-argument constructor needs. */
	public static final class ProviderSettings
	{
		public final String provider;
		public final LlmClient.Format format;
		public final String url;
		public final Map<String, String> headers;
		public final String model;
		public final int maxTokens;
		public final String openAiMaxTokensParam;
		/** {@code reasoning_effort} for an OpenAI-shaped provider, null = not sent (always null for {@link LlmClient.Format#ANTHROPIC}). */
		public final String openAiReasoningEffort;
		public final int connectTimeoutMs;
		public final int readTimeoutMs;

		ProviderSettings(final String pProvider, final LlmClient.Format pFormat, final String pUrl, final Map<String, String> pHeaders, final String pModel,
			final int pMaxTokens, final String pOpenAiMaxTokensParam, final String pOpenAiReasoningEffort, final int pConnectTimeoutMs, final int pReadTimeoutMs)
		{
			this.provider = pProvider;
			this.format = pFormat;
			this.url = pUrl;
			this.headers = pHeaders;
			this.model = pModel;
			this.maxTokens = pMaxTokens;
			this.openAiMaxTokensParam = pOpenAiMaxTokensParam;
			this.openAiReasoningEffort = pOpenAiReasoningEffort;
			this.connectTimeoutMs = pConnectTimeoutMs;
			this.readTimeoutMs = pReadTimeoutMs;
		}

		public LlmClient buildClient()
		{
			return new LlmClient(this.format, this.url, this.headers, this.model, this.maxTokens, this.connectTimeoutMs, this.readTimeoutMs,
				this.openAiMaxTokensParam, this.openAiReasoningEffort);
		}
	}

	/**
	 * Pure: connector properties -&gt; {@link LlmClient} settings. Two provider families only, per the plan of
	 * this lot: {@code anthropic} ({@link LlmClient.Format#ANTHROPIC}), and everything OpenAI-shaped -
	 * {@code openai} (the default when nothing else matches), {@code azure} (deployment path + api-version
	 * query string), {@code mistral}, {@code ollama} (its OpenAI-compatible endpoint, per {@link
	 * LlmClient}'s own Javadoc - not its native {@code /api/chat}, which has no tool-use turn shaped like
	 * {@link LlmClient.Format#OPENAI_CHAT} expects). Gemini/Google (detected the same way the sibling
	 * catalog module's {@code LlmTagging} detects it) is refused outright: not supported by the MDM Factory
	 * agent in this lot.
	 *
	 * @throws IllegalStateException with a message fit to surface to a caller, for a blank base URL, an
	 * unsupported provider, or a Gemini/Google endpoint.
	 */
	public static ProviderSettings settingsFor(final ConnectorInfo pInfo)
	{
		if (pInfo.baseUrl == null || pInfo.baseUrl.trim().isEmpty())
		{
			throw new IllegalStateException("the language model connector has no base URL");
		}
		String base = pInfo.baseUrl.trim();
		while (base.endsWith("/"))
		{
			base = base.substring(0, base.length() - 1);
		}
		final String lower = base.toLowerCase(Locale.ENGLISH);
		if (lower.contains("generativelanguage.googleapis.com") || lower.contains("aiplatform.googleapis.com"))
		{
			throw new IllegalStateException(
				"Gemini/Google connectors are not supported by the MDM Factory agent in this lot: use an Anthropic or an OpenAI-shaped connector (OpenAI, Azure OpenAI, Mistral, Ollama)");
		}
		final String declared = pInfo.providerOverride == null ? "" : pInfo.providerOverride.trim().toLowerCase(Locale.ENGLISH);
		final String provider;
		if (!declared.isEmpty() && !"auto".equals(declared))
		{
			provider = declared;
		}
		else if (lower.contains("anthropic.com"))
		{
			provider = "anthropic";
		}
		else if (lower.contains("openai.azure.com") || lower.contains("cognitiveservices.azure.com"))
		{
			provider = "azure";
		}
		else if (lower.contains("mistral.ai"))
		{
			provider = "mistral";
		}
		else if (lower.contains(":11434") || lower.contains("ollama"))
		{
			provider = "ollama";
		}
		else
		{
			provider = "openai";
		}
		if ("google".equals(provider) || "gemini".equals(provider) || "azure-ai".equals(provider) || "vertex".equals(provider))
		{
			throw new IllegalStateException("provider '" + provider
				+ "' is not supported by the MDM Factory agent in this lot: Anthropic or OpenAI-shaped (OpenAI, Azure OpenAI, Mistral, Ollama) only");
		}

		final String model = pInfo.model == null || pInfo.model.trim().isEmpty() ? AgentRuntime.defaultModel(provider) : pInfo.model.trim();
		final int maxTokens = pInfo.maxTokens == null || pInfo.maxTokens.intValue() <= 0 ? AgentRuntime.DEFAULT_MAX_TOKENS : pInfo.maxTokens.intValue();
		final String authType = pInfo.authType == null ? "" : pInfo.authType.trim().toUpperCase(Locale.ENGLISH);
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		final String url;
		final LlmClient.Format format;
		if ("anthropic".equals(provider))
		{
			format = LlmClient.Format.ANTHROPIC;
			if (pInfo.apiKey != null)
			{
				headers.put("API_KEY_HEADER".equals(authType) && AgentRuntime.hasText(pInfo.apiKeyHeader) ? pInfo.apiKeyHeader.trim() : "x-api-key", pInfo.apiKey);
			}
			headers.put("anthropic-version", AgentRuntime.hasText(pInfo.anthropicVersion) ? pInfo.anthropicVersion.trim() : "2023-06-01");
			url = AgentRuntime.joinPath(base, "/v1/messages");
		}
		else
		{
			format = LlmClient.Format.OPENAI_CHAT;
			if (pInfo.apiKey != null)
			{
				if ("API_KEY_HEADER".equals(authType))
				{
					headers.put(AgentRuntime.hasText(pInfo.apiKeyHeader) ? pInfo.apiKeyHeader.trim() : "api-key", pInfo.apiKey);
				}
				else
				{
					headers.put("Authorization", "Bearer " + pInfo.apiKey);
				}
			}
			if ("azure".equals(provider))
			{
				final String version = AgentRuntime.hasText(pInfo.azureApiVersion) ? pInfo.azureApiVersion.trim() : "2024-10-21";
				url = base + "/openai/deployments/" + AgentRuntime.urlEncode(model) + "/chat/completions?api-version=" + AgentRuntime.urlEncode(version);
			}
			else
			{
				// openai, mistral, ollama (its OpenAI-compatible endpoint): the one path every OpenAI-chat-completions-shaped provider answers on
				url = AgentRuntime.joinPath(base, "/v1/chat/completions");
			}
		}
		final String openAiParam = AgentRuntime.hasText(pInfo.openAiMaxTokensParam) ? pInfo.openAiMaxTokensParam.trim() : LlmClient.DEFAULT_OPENAI_MAX_TOKENS_PARAM;
		final String reasoningEffort = format == LlmClient.Format.OPENAI_CHAT && AgentRuntime.hasText(pInfo.openAiReasoningEffort) ? pInfo.openAiReasoningEffort.trim() : null;
		return new ProviderSettings(provider, format, url, headers, model, maxTokens, openAiParam, reasoningEffort, pInfo.connectTimeoutMs, pInfo.readTimeoutMs);
	}

	private static String defaultModel(final String pProvider)
	{
		if ("anthropic".equals(pProvider))
		{
			return "claude-sonnet-5";
		}
		if ("mistral".equals(pProvider))
		{
			return "mistral-small-latest";
		}
		return "ollama".equals(pProvider) ? "llama3.2" : "gpt-4o-mini";
	}

	private static boolean hasText(final String pValue)
	{
		return pValue != null && !pValue.trim().isEmpty();
	}

	/**
	 * {@code pBase + pPath}, without doubling a version segment ({@code /v1}) {@code pBase} already ends
	 * with - the same rule the sibling catalog module's {@code LlmTagging} follows, so a connector whose base
	 * URL is entered either as {@code https://api.openai.com} or as {@code https://api.openai.com/v1} both
	 * resolve to the same, correct endpoint.
	 */
	private static String joinPath(final String pBase, final String pPath)
	{
		final int slash = pPath.indexOf('/', 1);
		return slash > 0 && pBase.endsWith(pPath.substring(0, slash)) ? pBase + pPath.substring(slash) : pBase + pPath;
	}

	private static String urlEncode(final String pValue)
	{
		try
		{
			return URLEncoder.encode(pValue, "UTF-8");
		}
		catch (final UnsupportedEncodingException ex)
		{
			// UTF-8 is guaranteed available on every JVM; unreachable in practice
			return pValue;
		}
	}

	/**
	 * The {@code llm} block of {@code /status}: whether the language model's answers are being recorded ({@code -Debx.factory.llm.record.dir}) and/or replayed
	 * ({@code -Debx.factory.llm.replay.dir}) right now, with the folders and the number of files in them. See {@link LlmRecordStore}.
	 */
	public static ObjectNode recordReplayStatus()
	{
		return LlmRecordStore.statusJson();
	}

	/** Pure: {@link AgentLoop.Status} -&gt; {@code AgentRun.status} - the two vocabularies use the same three names on purpose. */
	public static String statusOf(final AgentLoop.Status pStatus)
	{
		return pStatus.name();
	}

	// -------------------------------------------------------------------------------------------------- the run

	/** What one run produced, for the caller to fold into its own answer. */
	public static final class Outcome
	{
		public final String runId;
		public final AgentLoop.Status status;
		/**
		 * LLM ROUND TRIPS, not tool calls - exactly what {@code AgentLoop.Budget.maxSteps} (a caller's own
		 * {@code FactorySettings.maxAgentSteps}) counts against, and what a {@code "step budget of N exceeded"}
		 * {@link #detail} refers to. {@code AgentLoop} lets several tool calls share ONE turn (see its own
		 * Javadoc) - a caller reporting cost to a person should show this figure, not {@link #toolCalls}, next
		 * to that budget (coordinator review round 3, 2026-09-21: a real run's expensive path was hard to see
		 * because nothing separated "how many round trips" from "how many tool calls").
		 */
		public final int steps;
		public final int inputTokens;
		public final int outputTokens;
		public final int refusedToolCalls;
		/** Every tool call actually attempted across the whole run, successful or refused - {@link #refusedToolCalls} is the subset of this that were refused. Counted locally from {@link AgentLoop.Listener#onToolCall}, since {@code AgentLoop.Result} itself only ever counted the refused subset. */
		public final int toolCalls;
		public final String text;
		public final String detail;

		Outcome(final String pRunId, final AgentLoop.Result pResult, final int pToolCalls)
		{
			this.runId = pRunId;
			this.status = pResult.status;
			this.steps = pResult.steps;
			this.inputTokens = pResult.inputTokens;
			this.outputTokens = pResult.outputTokens;
			this.refusedToolCalls = pResult.refusedToolCalls;
			this.toolCalls = pToolCalls;
			this.text = pResult.text;
			this.detail = pResult.detail;
		}

		/**
		 * Builds an {@link Outcome} directly from its own fields, with no {@link AgentLoop.Result} at all -
		 * {@code AgentLoop.Result}'s own constructor is package-private to {@code com.on.ebx.agent} (the shared
		 * {@code on-ebx-agent} library, out of this repository's scope to change - see {@code
		 * ProseAnswerRecovery}'s own Javadoc), so a test of this package's own orchestration logic (whether a
		 * follow-up run is attempted, whether its counts are combined correctly) cannot otherwise build a fake
		 * {@link Outcome} without a live {@code AgentRuntime.run} call. Package-private: production code always
		 * goes through the other constructor, driven by a real {@link AgentLoop} run.
		 */
		Outcome(final String pRunId, final AgentLoop.Status pStatus, final int pSteps, final int pInputTokens, final int pOutputTokens,
			final int pRefusedToolCalls, final int pToolCalls, final String pText, final String pDetail)
		{
			this.runId = pRunId;
			this.status = pStatus;
			this.steps = pSteps;
			this.inputTokens = pInputTokens;
			this.outputTokens = pOutputTokens;
			this.refusedToolCalls = pRefusedToolCalls;
			this.toolCalls = pToolCalls;
			this.text = pText;
			this.detail = pDetail;
		}
	}

	/**
	 * Reads the settings, loads the connector, builds the client, and runs {@code pTools} through an {@link
	 * AgentLoop} - the caller decides what tools that is (local only, MCP only, or both); this class does not
	 * know or care which phase it is serving. Every EBX write (the {@code AgentRun} row, and one {@code
	 * AgentStep} row per model turn / tool call) happens in its own procedure, with the rights of {@code
	 * pSession}.
	 *
	 * @param pProjectId {@code Project} occurrence id, or null for a run not tied to one project.
	 */
	public static Outcome run(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pPhase, final String pGoal,
		final String pSystemPrompt, final String pUserTask, final ToolSet pTools) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.llmNotConfigured("MDM Factory settings: the DEFAULT row is missing");
		}
		final String connectorId = settings.getString(FactoryPaths.Settings.LLM_CONNECTOR);
		if (connectorId == null || connectorId.trim().isEmpty())
		{
			throw EngineRefusal.llmNotConfigured("no language model connector is set in the MDM Factory settings");
		}
		final int maxSteps = AgentRuntime.positiveOr(settings.get(FactoryPaths.Settings.MAX_AGENT_STEPS), AgentRuntime.DEFAULT_MAX_STEPS);
		final int maxTotalTokens = AgentRuntime.positiveOr(settings.get(FactoryPaths.Settings.MAX_AGENT_TOKENS), AgentRuntime.DEFAULT_MAX_TOTAL_TOKENS);

		// wave 12: llm.chat of the Connectors contract when ebx-connectors offers it for this connection (the key is then never read), else the module's own client
		final AgentRoute.Plan plan = AgentRoute.resolve(pSession, connectorId.trim());
		if (plan.kind == AgentRoute.Kind.REFUSED)
		{
			AgentRoute.logOnce(plan);
			throw plan.refusal;
		}
		AgentRoute.logOnce(plan);
		final LlmChat client;
		final AgentRoute.Guard guard;
		final String modelName;
		final LlmReplayChat replayChat;
		if (plan.kind == AgentRoute.Kind.CONTRACT)
		{
			// the Factory's own allow-list of language model hosts still applies (only the connection's base URL is read, never its key), as for llm.ask
			AgentRuntime.requireAllowedDestination(pFactory.getHome().getRepository(), connectorId.trim());
			guard = AgentRoute.chat(pSession, plan);
			modelName = plan.connection.model.isEmpty() ? null : plan.connection.model;
			// test cycle (lot F4): record / replay of the answers, see LlmRecordStore (a pass-through unless -Debx.factory.llm.record.dir / replay.dir is set)
			final Map<String, Object> digestParams = new LinkedHashMap<String, Object>();
			digestParams.put("maxTokens", Integer.valueOf(plan.asked));
			replayChat = new LlmReplayChat(guard, plan.connection.provider, modelName, digestParams);
		}
		else
		{
			// the fallback: the ONE place of the agent loop that reads the connection's row and decrypts its key (AgentLlmConnector)
			final ProviderSettings providerSettings = AgentRuntime.directSettings(pFactory.getHome().getRepository(), connectorId.trim());
			// security review (2026-09-24): the API key never goes in clear text off this machine (HTTPS, or a local model)
			CredentialPolicy.requireLlmDestination(providerSettings.url);
			guard = null;
			modelName = providerSettings.model;
			final Map<String, Object> digestParams = new LinkedHashMap<String, Object>();
			digestParams.put("maxTokens", Integer.valueOf(providerSettings.maxTokens));
			digestParams.put("reasoningEffort", providerSettings.openAiReasoningEffort);
			replayChat = new LlmReplayChat(providerSettings.buildClient(), providerSettings.provider, providerSettings.model, digestParams);
		}
		client = replayChat;

		final String runId = AgentRuntime.begin(pSession, pFactory, pProjectId, pPhase, pGoal, connectorId.trim(), modelName);

		final int[] stepIndex = { 0 };
		// separate from stepIndex (that one is a TRACE row counter, mixing turns and tool calls on purpose so
		// AgentStep rows read in order) - this one is the actual count of tool calls attempted, for Outcome#toolCalls
		final int[] toolCallCount = { 0 };
		final AgentLoop.Listener listener = new AgentLoop.Listener()
		{
			@Override
			public void onTurn(final int pStep, final LlmClient.Turn pTurn)
			{
				stepIndex[0]++;
				// test cycle (lot F4): a turn answered from the replay store says so in the trace
				final String replayedFrom = replayChat.takeReplayMarker();
				AgentRuntime.writeStep(pSession, pFactory, runId, stepIndex[0], "MODEL", null, null, replayedFrom == null ? null : "replayed from " + replayedFrom, pTurn.text, false, 0L);
			}

			@Override
			public void onToolCall(final String pServer, final String pTool, final JsonNode pArguments, final String pResultText, final boolean pIsError,
				final long pDurationMs)
			{
				stepIndex[0]++;
				toolCallCount[0]++;
				AgentRuntime.writeStep(pSession, pFactory, runId, stepIndex[0], pServer == null ? "REFUSED" : "TOOL", pServer, pTool,
					pArguments == null ? null : pArguments.toString(), pResultText, pIsError, pDurationMs);
			}
		};

		final AgentLoop loop = new AgentLoop(new ConversationCompactor.Chat(client), pTools, new AgentLoop.Budget(maxSteps, maxTotalTokens, AgentRuntime.WALL_CLOCK_BUDGET_MS), listener);
		AgentLoop.Result result = AgentRuntime.runLoop(pSession, pFactory, runId, loop, pSystemPrompt, pUserTask);

		// 429/503 retry, 2026-09-25 (project PersVol2) - see LlmRetryPolicy's own Javadoc for why a WHOLE fresh
		// run, not a single internal call, is the retry unit this library's shape leaves available: AgentLoop
		// caught the LlmException itself and already gave up (result.status == FAILED, result.detail carries
		// the "HTTP <status> ..." message) by the time control returns here.
		int attempt = 0;
		long waitedMs = 0L;
		while (result.status == AgentLoop.Status.FAILED && LlmRetryPolicy.isRetryableMessage(result.detail))
		{
			attempt++;
			final long delayMs = LlmRetryPolicy.nextDelayMs(attempt, result.detail);
			if (!LlmRetryPolicy.shouldRetry(attempt, waitedMs, delayMs))
			{
				break;
			}
			LoggingCategory.getKernel().warn("[mdm-factory] agent run " + runId + ": transient LLM failure, retrying the run (attempt " + attempt + "/"
				+ LlmRetryPolicy.MAX_RETRIES + ", waiting " + delayMs + "ms): " + result.detail);
			AgentRuntime.sleep(delayMs);
			waitedMs += delayMs;
			// a retried run starts a fresh conversation over the SAME tools, whose session already holds what the
			// interrupted attempt proposed: say so, or the model spends the new run re-proposing it one refusal at a time
			result = AgentRuntime.runLoop(pSession, pFactory, runId, loop, pSystemPrompt, pUserTask + "\n\nNOTE: an earlier attempt of this same run was interrupted after "
				+ result.steps + " step(s) by a temporary limit of the language model provider. Everything it proposed through the tools stands: a "
				+ "tool answers when something already exists - do not propose it again, continue with what is still missing.");
		}

		String outcomeText = result.detail == null ? result.text : (result.text == null || result.text.isEmpty() ? "" : result.text + " ") + "(" + result.detail + ")";
		final String replaySummary = replayChat.summary();
		if (replaySummary != null)
		{
			outcomeText = (outcomeText == null || outcomeText.isEmpty() ? "" : outcomeText + " ") + replaySummary;
		}
		// the model the provider reported (the contract's guard), else the one a replayed answer was recorded with
		final String reportedModel = guard != null && guard.model() != null ? guard.model() : replayChat.replayedModel();
		final EngineRefusal failure = guard == null || result.status != AgentLoop.Status.FAILED ? null : guard.failure();
		if (failure != null)
		{
			// a cut answer, a connection Connectors cannot use, a caller without the right: the pass is refused with the coded sentence, not left as a failed run to interpret
			AgentRuntime.end(pSession, pFactory, runId, AgentLoop.Status.FAILED, result.steps, BigDecimal.valueOf(result.inputTokens), BigDecimal.valueOf(result.outputTokens),
				failure.getMessage(), reportedModel);
			throw failure;
		}
		AgentRuntime.end(pSession, pFactory, runId, result.status, result.steps, BigDecimal.valueOf(result.inputTokens), BigDecimal.valueOf(result.outputTokens),
			outcomeText, reportedModel);
		return new Outcome(runId, result, toolCallCount[0]);
	}

	/**
	 * The fallback of the agent loop (wave 12): reads the connection and its key ({@link AgentLlmConnector#load}) and decides the client's settings. Reached only when
	 * {@link AgentRoute#resolve} chose {@link AgentRoute.Kind#DIRECT}, and by the direct connectivity test.
	 */
	private static ProviderSettings directSettings(final Repository pRepository, final String pConnectorId) throws OperationException
	{
		final ConnectorInfo connectorInfo = AgentLlmConnector.load(pRepository, pConnectorId);
		try
		{
			return AgentRuntime.settingsFor(connectorInfo);
		}
		catch (final IllegalStateException ex)
		{
			throw EngineRefusal.coded("external.llmConnector", "language model connector " + pConnectorId + ": " + ex.getMessage(), pConnectorId);
		}
	}

	/**
	 * {@code AgentLoop#run} with the guarantee that the {@code AgentRun} row ends: the loop reports a model or tool failure as a FAILED result, but a bug of its own
	 * (a failed {@code AgentStep} write, a {@code LinkageError}) is thrown, and the row would stay RUNNING until the next restart marks it INTERRUPTED. The row is ended
	 * FAILED with the reason and the exception still reaches the caller.
	 */
	private static AgentLoop.Result runLoop(final Session pSession, final Adaptation pFactory, final String pRunId, final AgentLoop pLoop, final String pSystemPrompt,
		final String pUserTask)
	{
		try
		{
			return pLoop.run(pSystemPrompt, pUserTask);
		}
		catch (final RuntimeException | Error ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] agent run " + pRunId + " failed unexpectedly: " + ex, ex);
			AgentRuntime.end(pSession, pFactory, pRunId, AgentLoop.Status.FAILED, 0, BigDecimal.ZERO, BigDecimal.ZERO,
				"Unexpected error: " + ex.getClass().getName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
			throw ex;
		}
	}

	/** {@code Thread.sleep}, restoring the interrupt flag rather than swallowing it - {@link LlmRetryPolicy}'s own delay is always a few seconds at most, never worth a checked-exception ceremony at every call site. */
	private static void sleep(final long pMs)
	{
		try
		{
			Thread.sleep(pMs);
		}
		catch (final InterruptedException ex)
		{
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * {@code pClient.send(...)}, retried per {@link LlmRetryPolicy} on a 429/503 - a genuine single-call retry,
	 * unlike {@link #run}'s own (the only place this class owns the {@code LlmClient.send} call site directly
	 * rather than handing it to {@code AgentLoop}; see {@link LlmRetryPolicy}'s own Javadoc). Used today only by
	 * {@link #testLlm} (one tiny, cheap connectivity check), so a transient rate limit never turns a "test
	 * connection" click into a false negative.
	 */
	private static LlmClient.Turn sendWithRetry(final LlmClient pClient, final String pSystem, final List<LlmClient.ToolSpec> pTools,
		final List<LlmClient.Message> pMessages) throws LlmException
	{
		int attempt = 0;
		long waitedMs = 0L;
		while (true)
		{
			try
			{
				return pClient.send(pSystem, pTools, pMessages);
			}
			catch (final LlmException ex)
			{
				if (!LlmRetryPolicy.isRetryableStatus(ex.getStatus()))
				{
					throw ex;
				}
				attempt++;
				final long delayMs = LlmRetryPolicy.nextDelayMs(attempt, ex.getMessage());
				if (!LlmRetryPolicy.shouldRetry(attempt, waitedMs, delayMs))
				{
					throw ex;
				}
				LoggingCategory.getKernel().warn("[mdm-factory] LLM connectivity check: transient failure, retrying (attempt " + attempt + "/"
					+ LlmRetryPolicy.MAX_RETRIES + ", waiting " + delayMs + "ms): " + ex.getMessage());
				AgentRuntime.sleep(delayMs);
				waitedMs += delayMs;
			}
		}
	}

	private static int positiveOr(final Object pValue, final int pDefault)
	{
		return pValue instanceof Number && ((Number) pValue).intValue() > 0 ? ((Number) pValue).intValue() : pDefault;
	}

	// ------------------------------------------------------------------------------------------ one question, no tool

	/** Reads the base URL of a connection (null = unreadable): {@link AgentLlmConnector#baseUrlOf} in production, a stand-in in a test. */
	interface BaseUrlReader
	{
		String baseUrl(String pConnectorId);
	}

	/**
	 * The Factory's own rules on WHERE a prompt may go still apply when the call goes through the Connectors contract, which never shows this module the URL:
	 * when the optional allow-list {@code -Debx.factory.llm.allowedHosts} is set ({@link CredentialPolicy#ALLOWED_LLM_HOSTS_PROPERTY}), the base URL of the connection about
	 * to be asked goes through {@link CredentialPolicy#requireLlmDestination} (the same coded refusals as the direct path) BEFORE {@code llm.ask}; a URL that cannot be read
	 * is refused (the list is a restriction: it fails closed). With the list unset nothing is checked here (Connectors applies its own egress rules).
	 */
	static void requireAllowedDestination(final String pConnectorId, final BaseUrlReader pReader) throws OperationException
	{
		final String allowList = System.getProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY);
		if (allowList == null || allowList.trim().isEmpty())
		{
			return;
		}
		final String baseUrl = pReader.baseUrl(pConnectorId);
		if (baseUrl == null)
		{
			throw EngineRefusal.coded("external.llmConnector", "language model connector " + pConnectorId + ": its base URL could not be read, so it cannot be checked against the allow-list -D"
				+ CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY, pConnectorId);
		}
		CredentialPolicy.requireLlmDestination(baseUrl);
	}

	/** {@link #requireAllowedDestination(String, BaseUrlReader)} reading the URL from the connection row of {@code pRepository}. */
	static void requireAllowedDestination(final Repository pRepository, final String pConnectorId) throws OperationException
	{
		AgentRuntime.requireAllowedDestination(pConnectorId, new BaseUrlReader()
		{
			@Override
			public String baseUrl(final String pId)
			{
				return AgentLlmConnector.baseUrlOf(pRepository, pId);
			}
		});
	}

	/** The destination check, then {@code llm.ask} (retried per policy): the one way a call through the contract is made, so the check can never be skipped. */
	static LlmContract.Answer askGuarded(final Session pSession, final String pConnectorId, final BaseUrlReader pReader, final String pSystem, final String pPrompt,
		final int pMaxTokens, final boolean pJson, final int pTimeoutMs) throws OperationException, ConnectorsContractClient.Refusal
	{
		AgentRuntime.requireAllowedDestination(pConnectorId, pReader);
		return LlmContract.askWithRetry(pSession, pConnectorId, pSystem, pPrompt, pMaxTokens, pJson, pTimeoutMs);
	}

	/** What {@link #ask} produced: the answer's text and the trace row it was recorded under. */
	public static final class AskOutcome
	{
		public final String runId;
		public final String text;
		public final String model;
		public final int inputTokens;
		public final int outputTokens;
		public final boolean truncated;

		AskOutcome(final String pRunId, final LlmContract.Answer pAnswer)
		{
			this.runId = pRunId;
			this.text = pAnswer.text;
			this.model = pAnswer.model;
			this.inputTokens = pAnswer.inputTokens;
			this.outputTokens = pAnswer.outputTokens;
			this.truncated = pAnswer.truncated;
		}
	}

	/**
	 * Whether a one-question pass ({@link #ask}) can go through the Connectors contract now: a session to run as and a compatible contract
	 * ({@code ebx.factory.connectors.via}). {@code false} = use the earlier way, a one-tool {@link #run}. Throws a coded refusal with {@code via=contract} and no
	 * compatible contract (nothing falls back then).
	 */
	public static boolean canAsk(final Session pSession) throws OperationException
	{
		return pSession != null && ConnectorsContractClient.useContract();
	}

	/**
	 * Wave 11 (audit F-26): ONE prompt to the language model connection of the settings through the Connectors contract ({@code llm.ask}: Connectors
	 * asks with the connection's own client and stored key, this module never reads the key), for a pass that needs no tool and no second turn. Recorded
	 * like {@link #run}: one {@code AgentRun} row (RUNNING before the call, ended after it, the model and token counts as the provider reported them) and
	 * one {@code MODEL} {@code AgentStep}, each written in its own procedure. A transient provider failure (429/503) is retried per {@link LlmRetryPolicy};
	 * any other refusal ends the row FAILED and is thrown as the coded engine message of {@link ConnectorsContractClient#engineRefusal}. A model that
	 * stopped at {@code pMaxTokens} is {@link AskOutcome#truncated}: the caller decides (a cut JSON answer is never a usable one).
	 *
	 * @param pJson whether a JSON answer is expected (Connectors then uses the provider's native JSON mode where it has one)
	 */
	public static AskOutcome ask(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pPhase, final String pGoal, final String pSystemPrompt,
		final String pPrompt, final int pMaxTokens, final boolean pJson, final int pTimeoutMs) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.llmNotConfigured("MDM Factory settings: the DEFAULT row is missing");
		}
		final String connectorId = settings.getString(FactoryPaths.Settings.LLM_CONNECTOR);
		if (connectorId == null || connectorId.trim().isEmpty())
		{
			throw EngineRefusal.llmNotConfigured("no language model connector is set in the MDM Factory settings");
		}
		// the Factory's allow-list of language model hosts still applies (refused before any row is written, as on the direct path)
		AgentRuntime.requireAllowedDestination(pFactory.getHome().getRepository(), connectorId.trim());
		final String runId = AgentRuntime.begin(pSession, pFactory, pProjectId, pPhase, pGoal, connectorId.trim(), null);
		final LlmContract.Answer answer;
		final String replayedFrom;
		try
		{
			// test cycle (lot F4): from the replay store when it holds this question, recorded when a record directory is set (a pass-through otherwise)
			final String connection = connectorId.trim();
			final LlmRecordStore.AskResult asked = LlmRecordStore.ask(connection, pSystemPrompt, pPrompt, pMaxTokens, pJson, new LlmRecordStore.AskCall()
			{
				@Override
				public LlmContract.Answer call() throws ConnectorsContractClient.Refusal
				{
					return LlmContract.askWithRetry(pSession, connection, pSystemPrompt, pPrompt, pMaxTokens, pJson, pTimeoutMs);
				}
			});
			answer = asked.answer;
			replayedFrom = asked.replayedFrom;
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			final EngineRefusal refusal = ConnectorsContractClient.engineRefusal(ex, "llm.ask", connectorId.trim());
			AgentRuntime.end(pSession, pFactory, runId, AgentLoop.Status.FAILED, 0, BigDecimal.ZERO, BigDecimal.ZERO, refusal.getMessage());
			throw refusal;
		}
		catch (final RuntimeException | Error ex)
		{
			AgentRuntime.end(pSession, pFactory, runId, AgentLoop.Status.FAILED, 0, BigDecimal.ZERO, BigDecimal.ZERO,
				"Unexpected error: " + ex.getClass().getName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
			throw ex;
		}
		AgentRuntime.writeStep(pSession, pFactory, runId, 1, "MODEL", null, null, replayedFrom == null ? null : "replayed from " + replayedFrom, answer.text, false, answer.durationMs);
		final String truncatedNote = answer.truncated ? "(the model stopped at the token limit of " + pMaxTokens + ")" : null;
		final String replayNote = replayedFrom == null ? null : "[LLM replay: answered from " + replayedFrom + "]";
		AgentRuntime.end(pSession, pFactory, runId, answer.truncated ? AgentLoop.Status.BUDGET : AgentLoop.Status.DONE, 1, BigDecimal.valueOf(answer.inputTokens),
			BigDecimal.valueOf(answer.outputTokens), truncatedNote == null ? replayNote : replayNote == null ? truncatedNote : truncatedNote + " " + replayNote, answer.model);
		return new AskOutcome(runId, answer);
	}

	// --------------------------------------------------------------------------------------------- connector test

	/** The prompt of {@link #testLlm}: one tiny turn, no tool, whatever the model answers counts as reachable. */
	static final String TEST_SYSTEM = "You are a connectivity check. Reply with the single word OK.";
	static final String TEST_USER = "ping";
	/** Enough for a reasoning model to still emit a word; the call costs a few dozen tokens. */
	static final int TEST_MAX_TOKENS = 64;
	/** The model's own time limit for the connectivity check through the contract (a click on "Test the connection" never waits longer). */
	static final int TEST_TIMEOUT_MS = 60000;

	/**
	 * The connection screen's language model line ({@code McpServers.test}): {@code {configured:false}} when no
	 * connector is set (rules only, not a failure); otherwise one real call answering {@code connector, provider,
	 * model, ok, elapsedMs} plus {@code answer} (the reply, shortened) or {@code problem}. Never throws, never
	 * returns the API key. Wave 11: through the Connectors contract ({@code llm.ask}, as {@code pSession}) when ebx-connectors offers one
	 * ({@link ConnectorsContractClient}), so the key is never read here; otherwise ({@code via=direct}, an older Connectors, no
	 * session) {@link #testLlmDirect}.
	 */
	public static ObjectNode testLlm(final Session pSession, final Adaptation pFactory)
	{
		final ObjectNode out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		final Adaptation settings = pFactory == null ? null
			: pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final String connectorId = settings == null ? null : settings.getString(FactoryPaths.Settings.LLM_CONNECTOR);
		if (connectorId == null || connectorId.trim().isEmpty())
		{
			out.put("configured", false);
			return out;
		}
		if (pSession != null)
		{
			final long started = System.nanoTime();
			try
			{
				if (ConnectorsContractClient.useContract())
				{
					out.put("configured", true);
					out.put("connector", connectorId.trim());
					out.put("via", "contract");
					try
					{
						final Repository repository = pFactory.getHome().getRepository();
						final LlmContract.Answer answer = AgentRuntime.askGuarded(pSession, connectorId.trim(), new BaseUrlReader()
						{
							@Override
							public String baseUrl(final String pId)
							{
								return AgentLlmConnector.baseUrlOf(repository, pId);
							}
						}, AgentRuntime.TEST_SYSTEM, AgentRuntime.TEST_USER, AgentRuntime.TEST_MAX_TOKENS, false, AgentRuntime.TEST_TIMEOUT_MS);
						out.put("provider", answer.provider);
						out.put("model", answer.model);
						out.put("ok", true);
						out.put("answer", McpServers.shorten(answer.text.trim().isEmpty() ? "(empty reply)" : answer.text.trim()));
						// wave 12: the agent loop's own needs of this connection (its answer size); a connection that cannot serve a pass is not "ok"
						AgentRuntime.putAgentCheck(out, AgentRoute.resolve(pSession, connectorId.trim()));
						out.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
						return out;
					}
					catch (final ConnectorsContractClient.Refusal ex)
					{
						if (!ConnectorsContractClient.mayFallBack(ex))
						{
							out.put("ok", false);
							out.put("problem", ConnectorsContractClient.engineRefusal(ex, "llm.ask", connectorId.trim()).getMessage());
							out.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
							return out;
						}
						// the contract went away since the check: the earlier way below
						out.removeAll();
					}
				}
			}
			catch (final OperationException ex)
			{
				// via=contract and no compatible contract (nothing falls back), or the destination was refused by the allow-list
				out.put("configured", true);
				out.put("connector", connectorId.trim());
				out.put("ok", false);
				out.put("problem", ex.getMessage());
				out.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
				return out;
			}
		}
		return AgentRuntime.testLlmDirect(pFactory);
	}

	/** The language model connection chosen in the factory settings of {@code pFactory} (trimmed); null when none (or no dataset). */
	public static String chosenConnection(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory == null ? null
			: pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final String id = settings == null ? null : settings.getString(FactoryPaths.Settings.LLM_CONNECTOR);
		return id == null || id.trim().isEmpty() ? null : id.trim();
	}

	/**
	 * Wave 12: what the connectivity test says about the agent loop's way ({@code agentRoute}: contract / direct / refused) and the connection's answer size
	 * ({@code maxTokens} against {@code neededMaxTokens}). The prompt itself got an answer, but a connection that would refuse every pass (answers too short, a refused
	 * way) is reported as a problem with the sentence that says what to change.
	 */
	static void putAgentCheck(final ObjectNode pOut, final AgentRoute.Plan pPlan)
	{
		pOut.put("agentRoute", pPlan.kind.name().toLowerCase(Locale.ENGLISH));
		pOut.put("neededMaxTokens", pPlan.needed);
		if (pPlan.connection != null && pPlan.connection.maxTokens > 0)
		{
			pOut.put("maxTokens", pPlan.connection.maxTokens);
		}
		if (pPlan.refusal != null)
		{
			pOut.put("ok", false);
			pOut.put("problem", pPlan.refusal.getMessage());
		}
	}

	/** {@link #testLlm(Session, Adaptation)} the earlier way: reads the connection and its key through {@link AgentLlmConnector}. */
	public static ObjectNode testLlm(final Adaptation pFactory)
	{
		return AgentRuntime.testLlm(null, pFactory);
	}

	/**
	 * The earlier way of the connectivity check: one real call through the same {@link AgentLlmConnector#load} / {@link #settingsFor} /
	 * {@link LlmClient} path as every agent run.
	 */
	static ObjectNode testLlmDirect(final Adaptation pFactory)
	{
		final ObjectNode out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		final Adaptation settings = pFactory == null ? null
			: pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final String connectorId = settings == null ? null : settings.getString(FactoryPaths.Settings.LLM_CONNECTOR);
		if (connectorId == null || connectorId.trim().isEmpty())
		{
			out.put("configured", false);
			return out;
		}
		out.put("configured", true);
		out.put("connector", connectorId.trim());
		final long started = System.nanoTime();
		try
		{
			final ProviderSettings provider = AgentRuntime.settingsFor(AgentLlmConnector.load(pFactory.getHome().getRepository(), connectorId.trim()));
			CredentialPolicy.requireLlmDestination(provider.url);
			out.put("provider", provider.provider);
			out.put("model", provider.model);
			final LlmClient client = new LlmClient(provider.format, provider.url, provider.headers, provider.model, AgentRuntime.TEST_MAX_TOKENS,
				provider.connectTimeoutMs, provider.readTimeoutMs, provider.openAiMaxTokensParam, provider.openAiReasoningEffort);
			final LlmClient.Turn turn = AgentRuntime.sendWithRetry(client, AgentRuntime.TEST_SYSTEM, new ArrayList<LlmClient.ToolSpec>(),
				java.util.Collections.singletonList(LlmClient.Message.user(AgentRuntime.TEST_USER)));
			out.put("ok", true);
			out.put("answer", McpServers.shorten(turn.text == null || turn.text.trim().isEmpty() ? "(empty reply)" : turn.text.trim()));
		}
		catch (final OperationException ex)
		{
			out.put("ok", false);
			out.put("problem", ex.getMessage());
		}
		catch (final IllegalStateException ex)
		{
			out.put("ok", false);
			out.put("problem", "language model connector " + connectorId.trim() + ": " + ex.getMessage());
		}
		catch (final LlmException ex)
		{
			out.put("ok", false);
			out.put("problem", McpServers.shorten(ex.getMessage())); // already reads "HTTP <status> from <url>: <body>"
		}
		catch (final RuntimeException ex)
		{
			out.put("ok", false);
			out.put("problem", McpServers.shorten(String.valueOf(ex)));
		}
		out.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
		return out;
	}

	// --------------------------------------------------------------------------------------------- connector read

	/** One connector of type llm, for the Connexion screen's picker. */
	public static final class LlmConnectorOption
	{
		public final String id;
		public final String label;
		public final String provider;
		public final String model;
		public final boolean active;
		/** Wave 12: the most a call to this connection gets ({@code llm.connectors} 1.1.0 {@code maxTokens}); 0 = not known (the direct read, or a contract 1.0). */
		public final int maxTokens;
		/** Wave 12: whether {@code llm.chat} serves this connection's provider ({@code llm.connectors} 1.1.0 {@code chat}; false = not offered or not known). */
		public final boolean chat;

		LlmConnectorOption(final String pId, final String pLabel, final String pProvider, final String pModel, final boolean pActive)
		{
			this(pId, pLabel, pProvider, pModel, pActive, 0, false);
		}

		LlmConnectorOption(final String pId, final String pLabel, final String pProvider, final String pModel, final boolean pActive, final int pMaxTokens,
			final boolean pChat)
		{
			this.maxTokens = pMaxTokens;
			this.chat = pChat;
			this.id = pId;
			this.label = pLabel == null || pLabel.trim().isEmpty() ? pId : pLabel.trim();
			this.provider = pProvider == null ? "" : pProvider;
			this.model = pModel == null ? "" : pModel;
			this.active = pActive;
		}
	}

	/**
	 * Every connector of type llm in ebx-connectors, sorted active first then by label, with its provider and model - through the contract
	 * ({@code llm.connectors}, wave 11) when ebx-connectors offers one and {@code pSession} is given, else read as data
	 * ({@link AgentLlmConnector#listDirect}). Empty when ebx-connectors is not installed. Never reads nor returns the API key.
	 */
	public static List<LlmConnectorOption> llmConnectors(final Repository pRepository, final Session pSession)
	{
		if (pSession != null)
		{
			try
			{
				if (ConnectorsContractClient.useContract())
				{
					return LlmContract.connectors(pSession);
				}
			}
			catch (final ConnectorsContractClient.Refusal ex)
			{
				if (!ConnectorsContractClient.mayFallBack(ex))
				{
					LoggingCategory.getKernel().warn("[mdm-factory] the list of language model connections could not be read through the Connectors contract: " + ex.getMessage());
					return new ArrayList<LlmConnectorOption>();
				}
			}
			catch (final OperationException ex)
			{
				// via=contract and no compatible contract: nothing falls back, the picker stays empty (the connection screen shows the contract's state)
				LoggingCategory.getKernel().warn("[mdm-factory] the list of language model connections: " + ex.getMessage());
				return new ArrayList<LlmConnectorOption>();
			}
		}
		return AgentLlmConnector.listDirect(pRepository);
	}

	/** {@link #llmConnectors(Repository, Session)} without a session: always the direct read (nobody to run the contract as). */
	public static List<LlmConnectorOption> llmConnectors(final Repository pRepository)
	{
		return AgentLlmConnector.listDirect(pRepository);
	}

	// --------------------------------------------------------------------------------------------- EBX bookkeeping

	/** The id of the last row {@link #begin} wrote on this thread, so a caller whose model call threw can still find its run. */
	private static final ThreadLocal<String> LAST_BEGUN = new ThreadLocal<String>();

	/** Forgets {@link #lastBegunRunId} on this thread, before a call whose run the caller may need to find. */
	static void forgetLastBegunRun()
	{
		AgentRuntime.LAST_BEGUN.remove();
	}

	/** The id of the last {@code AgentRun} row begun on this thread since {@link #forgetLastBegunRun}, or null. */
	static String lastBegunRunId()
	{
		return AgentRuntime.LAST_BEGUN.get();
	}

	/** Written ahead of the loop: it must survive whatever the loop does next. @return the new row's id */
	static String begin(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pPhase, final String pGoal,
		final String pConnector, final String pModel) throws OperationException
	{
		final AdaptationTable runs = pFactory.getTable(FactoryPaths.AGENT_RUN);
		// lot C1: the launcher of a background job, not the system session it runs with
		final String startedBy = Actor.current(pSession).userId;
		final String goal = TextLimits.truncate(pGoal, AgentRuntime.GOAL_MAX_LENGTH);
		final String[] holder = new String[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(runs);
				if (pProjectId != null)
				{
					value.setValue(pProjectId, FactoryPaths.AgentRun.PROJECT);
				}
				value.setValue(pPhase, FactoryPaths.AgentRun.PHASE);
				if (goal != null)
				{
					value.setValue(goal, FactoryPaths.AgentRun.GOAL);
				}
				value.setValue(pConnector, FactoryPaths.AgentRun.LLM_CONNECTOR);
				if (pModel != null)
				{
					value.setValue(pModel, FactoryPaths.AgentRun.MODEL);
				}
				value.setValue("RUNNING", FactoryPaths.AgentRun.STATUS);
				if (startedBy != null)
				{
					value.setValue(startedBy, FactoryPaths.AgentRun.STARTED_BY);
				}
				value.setValue(new Date(), FactoryPaths.AgentRun.STARTED_AT);
				holder[0] = pContext.doCreateOccurrence(value, runs).getOccurrencePrimaryKey().format();
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.agentRunStart", "Could not record the start of the agent run: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		AgentRuntime.LAST_BEGUN.set(holder[0]);
		return holder[0];
	}

	/**
	 * Written after the loop is over, in its own procedure so a failure elsewhere never takes this record
	 * down with it. Best-effort like {@code PipelineSteps.end}: the run already happened, a bookkeeping
	 * failure here must not be reported as if the run itself had failed.
	 */
	private static void end(final Session pSession, final Adaptation pFactory, final String pRunId, final AgentLoop.Status pStatus, final int pSteps,
		final BigDecimal pInputTokens, final BigDecimal pOutputTokens, final String pOutcome)
	{
		AgentRuntime.end(pSession, pFactory, pRunId, pStatus, pSteps, pInputTokens, pOutputTokens, pOutcome, null);
	}

	/** {@link #end} that also records the model the provider reported ({@code pModel}, null = leave the row as {@link #begin} wrote it). */
	static void end(final Session pSession, final Adaptation pFactory, final String pRunId, final AgentLoop.Status pStatus, final int pSteps,
		final BigDecimal pInputTokens, final BigDecimal pOutputTokens, final String pOutcome, final String pModel)
	{
		final AdaptationTable runs = pFactory.getTable(FactoryPaths.AGENT_RUN);
		final String outcome = TextLimits.truncate(pOutcome, AgentRuntime.OUTCOME_MAX_LENGTH);
		final String statusCode = AgentRuntime.statusOf(pStatus);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = runs.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRunId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(statusCode, FactoryPaths.AgentRun.STATUS);
				value.setValue(Integer.valueOf(pSteps), FactoryPaths.AgentRun.STEPS);
				value.setValue(pInputTokens, FactoryPaths.AgentRun.INPUT_TOKENS);
				value.setValue(pOutputTokens, FactoryPaths.AgentRun.OUTPUT_TOKENS);
				value.setValue(new Date(), FactoryPaths.AgentRun.ENDED_AT);
				if (pModel != null && !pModel.isEmpty())
				{
					value.setValue(pModel, FactoryPaths.AgentRun.MODEL);
				}
				if (outcome != null)
				{
					value.setValue(outcome, FactoryPaths.AgentRun.OUTCOME);
				}
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not record the end of agent run " + pRunId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/**
	 * One {@code AgentStep} row per model turn / tool call, in its own procedure. Deliberately throws an
	 * unchecked exception on failure: {@link AgentLoop.Listener} declares no {@code throws}, and per {@link
	 * AgentLoop}'s own contract a {@link RuntimeException} out of the listener ends the run {@code FAILED}
	 * rather than silently dropping a step from the trace - a write failure here must be loud, not swallowed.
	 */
	static void writeStep(final Session pSession, final Adaptation pFactory, final String pRunId, final int pIndex, final String pKind,
		final String pServer, final String pTool, final String pArguments, final String pResult, final boolean pError, final long pDurationMs)
	{
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.AGENT_STEP);
		final String arguments = TextLimits.truncate(pArguments, AgentRuntime.ARGUMENTS_MAX_LENGTH);
		final String resultText = TextLimits.truncate(pResult, AgentRuntime.RESULT_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(steps);
				value.setValue(pRunId, FactoryPaths.AgentStep.RUN);
				value.setValue(Integer.valueOf(pIndex), FactoryPaths.AgentStep.STEP_INDEX);
				value.setValue(pKind, FactoryPaths.AgentStep.KIND);
				if (pServer != null)
				{
					value.setValue(pServer, FactoryPaths.AgentStep.SERVER);
				}
				if (pTool != null)
				{
					value.setValue(pTool, FactoryPaths.AgentStep.TOOL);
				}
				if (arguments != null)
				{
					value.setValue(arguments, FactoryPaths.AgentStep.ARGUMENTS);
				}
				if (resultText != null)
				{
					value.setValue(resultText, FactoryPaths.AgentStep.RESULT);
				}
				value.setValue(Boolean.valueOf(pError), FactoryPaths.AgentStep.ERROR);
				value.setValue(Integer.valueOf((int) pDurationMs), FactoryPaths.AgentStep.DURATION_MS);
				value.setValue(new Date(), FactoryPaths.AgentStep.OCCURRED_AT);
				pContext.doCreateOccurrence(value, steps);
			}
		});
		if (result.hasFailed())
		{
			throw new IllegalStateException("could not record agent step " + pIndex + " of run " + pRunId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}
}
