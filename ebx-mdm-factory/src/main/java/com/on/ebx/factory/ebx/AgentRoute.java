package com.on.ebx.factory.ebx;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.ContractLlmChat;
import com.on.ebx.agent.LlmChat;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.LlmException;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * Wave 12: which way the agent loop ({@link AgentRuntime#run}) reaches the language model - and the checks that go with it.
 * <p>
 * <b>Contract</b> (the way when ebx-connectors offers contract 1.1.0 or a later 1.x AND the chosen connection's {@code chat} is true): every model turn is
 * an {@code llm.chat} of the Connectors contract ({@link ContractLlmChat}, as the session the pass already runs under): Connectors sends it with the connection's
 * own client and stored key, this module never reads nor decrypts the key. <b>Direct</b> (the way until wave 11, and still the fallback): {@link AgentLlmConnector#load}
 * reads the connection and its key, {@link LlmClient} calls the provider itself. Direct is taken for {@code ebx.factory.connectors.via=direct}, for a Connectors without
 * 1.1 (contract absent, 1.0.0, another major, switched off) and for a connection {@code llm.chat} does not serve (Google); each distinct choice is logged once.
 * With {@code via=contract} nothing falls back: a Connectors that cannot serve the chat is a coded refusal.
 * <p>
 * <b>The answer size.</b> Connectors never sends more than the connection's own {@code maxTokens} (1024 when the connection has none). The agent loop's turns carry a
 * proposal and its tool calls and need {@link #neededMaxTokens} (4096 by default, {@code ebx.factory.agent.maxTokens}): when {@code llm.connectors} says the connection
 * allows less, the pass is refused up front (no {@code llm.chat} call) with {@code engine.llm.maxTokensTooLow}, which names the connection and the value to set; an answer
 * that Connectors capped AND that stopped at the cap ({@code MAX_TOKENS}) ends the pass with the same message ({@link Guard}). The direct way keeps asking the connection's
 * own {@code maxTokens} property (4096 when unset), as before.
 */
public final class AgentRoute
{
	/** The answer size a pass needs from the connection (default {@link #DEFAULT_NEEDED_MAX_TOKENS}). */
	static final String PROPERTY_MAX_TOKENS = "ebx.factory.agent.maxTokens";
	/** The model's own time limit for one turn through the contract, in ms (default: the connection's own read timeout). */
	static final String PROPERTY_TURN_TIMEOUT_MS = "ebx.factory.agent.turnTimeoutMs";
	static final int DEFAULT_NEEDED_MAX_TOKENS = 4096;
	private static final int MIN_NEEDED_MAX_TOKENS = 256;
	private static final int MAX_NEEDED_MAX_TOKENS = 65536;
	/** The call waits a little longer than the model: the contract answers {@code TIMEOUT} itself at {@code timeoutMs}. */
	private static final int OUTER_MARGIN_MS = 15000;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** The last choice logged ({@code route|connection|why}), so that one choice is logged once (not at every pass). */
	private static volatile String lastLogged;

	private AgentRoute()
	{
	}

	enum Kind
	{
		/** {@code llm.chat} of the Connectors contract. */
		CONTRACT,
		/** The module builds its own {@link LlmClient} from the connection and its key (the fallback). */
		DIRECT,
		/** Neither: {@link Plan#refusal} says why. */
		REFUSED
	}

	/** What {@link #resolve} decided. */
	static final class Plan
	{
		final Kind kind;
		/** One English sentence: why this way (shown on {@code /status} and logged once). */
		final String why;
		final String connectorId;
		/** The connection as {@code llm.connectors} describes it; null when the contract was not asked. */
		final AgentRuntime.LlmConnectorOption connection;
		/** What a pass needs ({@link #neededMaxTokens}). */
		final int needed;
		/** The answer size asked of each turn through the contract (the connection's own value when it is higher than needed); 0 for the other ways. */
		final int asked;
		/** The coded refusal when {@link #kind} is {@link Kind#REFUSED}. */
		final EngineRefusal refusal;

		Plan(final Kind pKind, final String pWhy, final String pConnectorId, final AgentRuntime.LlmConnectorOption pConnection, final int pNeeded, final int pAsked,
			final EngineRefusal pRefusal)
		{
			this.kind = pKind;
			this.why = pWhy;
			this.connectorId = pConnectorId;
			this.connection = pConnection;
			this.needed = pNeeded;
			this.asked = pAsked;
			this.refusal = pRefusal;
		}

		/** True/false when the connection's {@code maxTokens} is known and compared with {@link #needed}; null when it is not known. */
		Boolean maxTokensOk()
		{
			return this.connection == null || this.connection.maxTokens <= 0 ? null : Boolean.valueOf(this.connection.maxTokens >= this.needed);
		}
	}

	/** Reads {@code llm.connectors}; a test's stand-in or {@link LlmContract#connectors}. */
	interface ConnectionsReader
	{
		List<AgentRuntime.LlmConnectorOption> read() throws ConnectorsContractClient.Refusal;
	}

	// ----------------------------------------------------------------------------------------------- the settings

	/** {@code ebx.factory.agent.maxTokens} (256..65536; anything else = {@link #DEFAULT_NEEDED_MAX_TOKENS}): the answer size a pass needs. */
	static int neededMaxTokens()
	{
		final String value = System.getProperty(AgentRoute.PROPERTY_MAX_TOKENS);
		if (value != null)
		{
			try
			{
				final int parsed = Integer.parseInt(value.trim());
				if (parsed >= AgentRoute.MIN_NEEDED_MAX_TOKENS && parsed <= AgentRoute.MAX_NEEDED_MAX_TOKENS)
				{
					return parsed;
				}
			}
			catch (final NumberFormatException ex)
			{
				// the default below
			}
		}
		return AgentRoute.DEFAULT_NEEDED_MAX_TOKENS;
	}

	/** {@code ebx.factory.agent.turnTimeoutMs} (1000..600000, the contract's bounds); null = not set: Connectors uses the connection's own read timeout. */
	static Integer turnTimeoutMs()
	{
		final String value = System.getProperty(AgentRoute.PROPERTY_TURN_TIMEOUT_MS);
		if (value != null)
		{
			try
			{
				final int parsed = Integer.parseInt(value.trim());
				if (parsed >= LlmContract.MIN_TIMEOUT_MS && parsed <= LlmContract.MAX_TIMEOUT_MS)
				{
					return Integer.valueOf(parsed);
				}
			}
			catch (final NumberFormatException ex)
			{
				// not set
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------- the decision

	/**
	 * Decides the way for a pass on the connection {@code pConnectorId} run as {@code pSession}. Never throws: a way that is refused comes back as {@link Kind#REFUSED}
	 * with its {@link Plan#refusal}. Reads the contract MBean's version and, when the chat is offered, {@code llm.connectors} (an in-JVM call).
	 */
	static Plan resolve(final Session pSession, final String pConnectorId)
	{
		final ConnectorsContractClient.Via via = ConnectorsContractClient.via();
		// direct (asked for, or nobody to run the contract as): the contract is not even looked at
		final ConnectorsContractClient.Status status = via == ConnectorsContractClient.Via.DIRECT || pSession == null ? null : ConnectorsContractClient.status();
		return AgentRoute.resolve(pSession, pConnectorId, via, status, new ConnectionsReader()
		{
			@Override
			public List<AgentRuntime.LlmConnectorOption> read() throws ConnectorsContractClient.Refusal
			{
				return LlmContract.connectors(pSession);
			}
		});
	}

	/** {@link #resolve(Session, String)} with the way asked for, the contract's state and the reader given (a test's). */
	static Plan resolve(final Session pSession, final String pConnectorId, final ConnectorsContractClient.Via pVia, final ConnectorsContractClient.Status pStatus,
		final ConnectionsReader pReader)
	{
		final String id = pConnectorId == null ? null : pConnectorId.trim();
		final int needed = AgentRoute.neededMaxTokens();
		if (pVia == ConnectorsContractClient.Via.DIRECT)
		{
			return AgentRoute.direct("the JVM property " + ConnectorsContractClient.PROPERTY_VIA + " is direct", id, needed);
		}
		if (pSession == null)
		{
			return AgentRoute.direct("there is no session to run the contract as", id, needed);
		}
		if (!pStatus.ready())
		{
			return AgentRoute.notServed(pVia, "ebx-connectors does not offer a compatible contract: " + pStatus.detail, id, needed, null);
		}
		final String chatProblem = ContractLlmChat.incompatibility(pStatus.offered);
		if (chatProblem != null)
		{
			return AgentRoute.notServed(pVia, chatProblem, id, needed, null);
		}
		final List<AgentRuntime.LlmConnectorOption> options;
		try
		{
			options = pReader.read();
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			// the contract is gone since the check: only "auto" falls back, and only for a refusal that says the contract itself is not there
			if (pVia == ConnectorsContractClient.Via.AUTO && (ConnectorsContractClient.ABSENT.equals(ex.type) || ConnectorsContractClient.DISABLED.equals(ex.type)
				|| ConnectorsContractClient.INCOMPATIBLE.equals(ex.type)))
			{
				return AgentRoute.direct("the contract of ebx-connectors went away: " + ex.getMessage(), id, needed);
			}
			return new Plan(Kind.REFUSED, "llm.connectors was refused (" + ex.type + "): " + ex.getMessage(), id, null, needed, 0,
				ConnectorsContractClient.engineRefusal(ex, "llm.connectors", id));
		}
		AgentRuntime.LlmConnectorOption connection = null;
		for (final AgentRuntime.LlmConnectorOption option : options)
		{
			if (option.id.equals(id))
			{
				connection = option;
				break;
			}
		}
		if (connection == null)
		{
			return new Plan(Kind.REFUSED, "ebx-connectors lists no language model connection " + id, id, null, needed, 0,
				EngineRefusal.llmConnectionUnusable("missing", String.valueOf(id), "no connector '" + id + "' in ebx-connectors"));
		}
		if (!connection.active)
		{
			return new Plan(Kind.REFUSED, "the connection " + id + " is inactive", id, connection, needed, 0,
				EngineRefusal.llmConnectionUnusable("inactive", String.valueOf(id), "connector " + id + " is inactive"));
		}
		if (!connection.chat)
		{
			return AgentRoute.notServed(pVia, "llm.chat does not serve the provider of the connection " + id + (connection.provider.isEmpty() ? "" : " (" + connection.provider + ")"),
				id, needed, connection);
		}
		if (connection.maxTokens > 0 && connection.maxTokens < needed)
		{
			final String english = AgentRoute.tooLowSentence(connection, connection.maxTokens, needed);
			return new Plan(Kind.REFUSED, english, id, connection, needed, 0, AgentRoute.tooLow(connection, connection.maxTokens, needed, english));
		}
		return new Plan(Kind.CONTRACT, "ebx-connectors offers contract " + pStatus.offered + " with llm.chat and the connection " + id + " is served", id, connection, needed,
			Math.max(needed, connection.maxTokens), null);
	}

	private static Plan direct(final String pWhy, final String pConnectorId, final int pNeeded)
	{
		return new Plan(Kind.DIRECT, pWhy, pConnectorId, null, pNeeded, 0, null);
	}

	/** The contract cannot serve the chat for {@code pWhy}: the fallback, except with {@code via=contract} (nothing falls back). */
	private static Plan notServed(final ConnectorsContractClient.Via pVia, final String pWhy, final String pConnectorId, final int pNeeded,
		final AgentRuntime.LlmConnectorOption pConnection)
	{
		if (pVia == ConnectorsContractClient.Via.CONTRACT)
		{
			final EngineRefusal refusal = pConnection != null
				? EngineRefusal.coded("external.connectorsLlmUnusable", "The agent's chat cannot go through the Connectors contract (" + ConnectorsContractClient.PROPERTY_VIA
					+ "=contract refuses to fall back): " + pWhy, pWhy)
				: EngineRefusal.coded("external.connectorsContractUnavailable", "The agent's chat cannot go through the Connectors contract (" + ConnectorsContractClient.PROPERTY_VIA
					+ "=contract refuses to fall back): " + pWhy, pWhy);
			return new Plan(Kind.REFUSED, pWhy, pConnectorId, pConnection, pNeeded, 0, refusal);
		}
		return new Plan(Kind.DIRECT, pWhy + ": the agent reads the connection and its key itself", pConnectorId, pConnection, pNeeded, 0, null);
	}

	private static String tooLowSentence(final AgentRuntime.LlmConnectorOption pConnection, final int pCurrent, final int pNeeded)
	{
		return "The language model connection " + pConnection.label + " (" + pConnection.id + ") allows answers of " + pCurrent + " tokens at most and the MDM Factory needs "
			+ pNeeded + ": raise maxTokens of connection " + pConnection.label + " to at least " + pNeeded;
	}

	private static EngineRefusal tooLow(final AgentRuntime.LlmConnectorOption pConnection, final int pCurrent, final int pNeeded, final String pEnglish)
	{
		return EngineRefusal.coded("llm.maxTokensTooLow", pEnglish, pConnection.label, String.valueOf(pCurrent), String.valueOf(pNeeded));
	}

	/** The refusal for an answer that stopped at the cap {@code pApplied} of the connection of {@code pPlan}. */
	static EngineRefusal cutRefusal(final Plan pPlan, final int pApplied)
	{
		final AgentRuntime.LlmConnectorOption connection = pPlan.connection != null ? pPlan.connection
			: new AgentRuntime.LlmConnectorOption(String.valueOf(pPlan.connectorId), String.valueOf(pPlan.connectorId), "", "", true);
		return AgentRoute.tooLow(connection, pApplied, pPlan.needed, AgentRoute.tooLowSentence(connection, pApplied, pPlan.needed)
			+ " (an answer of the model was cut at the limit)");
	}

	/** Logs the way taken, once per distinct way (a pass is not the place for one line each). */
	static void logOnce(final Plan pPlan)
	{
		final String key = pPlan.kind + "|" + pPlan.connectorId + "|" + pPlan.why;
		if (key.equals(AgentRoute.lastLogged))
		{
			return;
		}
		AgentRoute.lastLogged = key;
		try
		{
			final String line = "[mdm-factory] agent loop: " + (pPlan.kind == Kind.CONTRACT ? "llm.chat of the Connectors contract (the key is not read)"
				: pPlan.kind == Kind.DIRECT ? "direct (the module reads the connection and its key)" : "refused") + " for connection " + pPlan.connectorId + " - " + pPlan.why;
			if (pPlan.kind == Kind.CONTRACT)
			{
				LoggingCategory.getKernel().info(line);
			}
			else
			{
				LoggingCategory.getKernel().warn(line);
			}
		}
		catch (final Throwable ex)
		{
			// a logging failure must never take a pass down
		}
	}

	/** Test hook: forget what was logged. */
	static void forgetLoggedForTest()
	{
		AgentRoute.lastLogged = null;
	}

	/** Test hook: the last choice logged. */
	static String lastLoggedForTest()
	{
		return AgentRoute.lastLogged;
	}

	// --------------------------------------------------------------------------------------------- the chat

	/** The chat of {@code pPlan} (a {@link Kind#CONTRACT} one) as {@code pSession}, guarded ({@link Guard}). */
	static Guard chat(final Session pSession, final Plan pPlan)
	{
		final Integer timeout = AgentRoute.turnTimeoutMs();
		final int outer = Math.max(McpServers.readTimeoutMs(), (timeout == null ? 0 : timeout.intValue()) + AgentRoute.OUTER_MARGIN_MS);
		return new Guard(new ContractLlmChat(pSession, pPlan.connectorId, Integer.valueOf(pPlan.asked), timeout, false, ConnectorsContractClient.chatTransport(outer)), pPlan);
	}

	/**
	 * One {@link ContractLlmChat} for the whole pass, watched: remembers why a turn failed (the contract's {@code error.data.type}), which model answered, and ends the
	 * pass when a turn was cut at the connection's cap - a half-written tool call is never a proposal, and asking again would be cut the same way.
	 */
	static final class Guard implements LlmChat
	{
		private final ContractLlmChat inner;
		private final Plan plan;
		private volatile boolean cut;
		private volatile int cutAt;
		private volatile String refusalType;
		private volatile String refusalMessage;
		private volatile String model;
		private volatile boolean capped;

		Guard(final ContractLlmChat pInner, final Plan pPlan)
		{
			this.inner = pInner;
			this.plan = pPlan;
		}

		@Override
		public LlmClient.Turn send(final String pSystem, final List<LlmClient.ToolSpec> pTools, final List<LlmClient.Message> pMessages) throws LlmException
		{
			final LlmClient.Turn turn;
			try
			{
				turn = this.inner.send(pSystem, pTools, pMessages);
			}
			catch (final LlmException ex)
			{
				this.refusalType = this.inner.lastRefusal();
				final String message = ex.getMessage() == null ? "" : ex.getMessage();
				final int at = message.indexOf("): ");
				this.refusalMessage = at >= 0 ? message.substring(at + 3) : message;
				throw ex;
			}
			this.refusalType = null;
			if (this.inner.lastResult() != null)
			{
				final String reported = this.inner.lastResult().path("model").asText("");
				if (!reported.isEmpty())
				{
					this.model = reported;
				}
			}
			if (this.inner.lastCapped())
			{
				this.capped = true;
			}
			if (turn.stopReason == LlmClient.StopReason.MAX_TOKENS && this.inner.lastCapped())
			{
				this.cut = true;
				this.cutAt = this.inner.lastMaxTokensApplied();
				throw new LlmException(0, "the answer was cut at the " + this.cutAt + "-token limit of the connection (maxTokens)");
			}
			return turn;
		}

		/** The model the provider reported on the last answered turn; null before any. */
		String model()
		{
			return this.model;
		}

		/** True when some turn asked more than the connection allows (it got the connection's value). */
		boolean capped()
		{
			return this.capped;
		}

		/**
		 * The coded refusal a failed pass ends with, or null when the failure is an ordinary failed call ({@code LLM_FAILED}, {@code TIMEOUT}, a network problem: the loop's own
		 * FAILED outcome, retried per {@link LlmRetryPolicy}, is what callers handle). Cut at the cap: the answer-size message; the refusals about the request or the caller
		 * ({@code ACCESS_DENIED}, {@code NO_DEFAULT_LLM}, {@code CONNECTOR_UNUSABLE}, {@code NOT_FOUND}, {@code NOT_SUPPORTED}...): the contract's coded message.
		 */
		EngineRefusal failure()
		{
			if (this.cut)
			{
				return AgentRoute.cutRefusal(this.plan, this.cutAt);
			}
			final String type = this.refusalType;
			if (type == null)
			{
				return null;
			}
			if (ConnectorsContractClient.ACCESS_DENIED.equals(type) || ConnectorsContractClient.SESSION_REQUIRED.equals(type) || ConnectorsContractClient.NO_DEFAULT_LLM.equals(type)
				|| ConnectorsContractClient.CONNECTOR_UNUSABLE.equals(type) || ConnectorsContractClient.NOT_LLM.equals(type) || ConnectorsContractClient.NOT_FOUND.equals(type)
				|| ConnectorsContractClient.NOT_SUPPORTED.equals(type) || ConnectorsContractClient.ABSENT.equals(type) || ConnectorsContractClient.DISABLED.equals(type)
				|| ConnectorsContractClient.INCOMPATIBLE.equals(type))
			{
				return ConnectorsContractClient.engineRefusal(new ConnectorsContractClient.Refusal(type, this.refusalMessage), "llm.chat", this.plan.connectorId);
			}
			return null;
		}
	}

	// ----------------------------------------------------------------------------------------------- /status

	/**
	 * {@code {route, why, connector, provider?, model?, chat?, maxTokens?, neededMaxTokens, maxTokensOk?, problem?}}: the way the agent loop takes now, for the administrator's
	 * {@code /status} and the connection test. {@code route} is {@code contract} (llm.chat) or {@code direct} (the module reads the key) or {@code refused} (with
	 * {@code problem}); {@code maxTokensOk} is the check of the connection's own {@code maxTokens} against {@code neededMaxTokens} (absent when it is not known: the direct way,
	 * or a connection that reports none). Never throws.
	 */
	public static ObjectNode statusJson(final Session pSession, final String pConnectorId)
	{
		final ObjectNode out = AgentRoute.MAPPER.createObjectNode();
		if (pConnectorId == null || pConnectorId.trim().isEmpty())
		{
			out.put("route", "none");
			out.put("why", "no language model connection is chosen in the MDM Factory settings");
			out.put("neededMaxTokens", AgentRoute.neededMaxTokens());
			return out;
		}
		final Plan plan;
		try
		{
			plan = AgentRoute.resolve(pSession, pConnectorId);
		}
		catch (final RuntimeException ex)
		{
			out.put("route", "unknown");
			out.put("why", String.valueOf(ex));
			return out;
		}
		out.put("route", plan.kind.name().toLowerCase(Locale.ENGLISH));
		out.put("why", plan.why);
		out.put("connector", plan.connectorId);
		if (plan.connection != null)
		{
			out.put("provider", plan.connection.provider);
			out.put("model", plan.connection.model);
			out.put("chat", plan.connection.chat);
			if (plan.connection.maxTokens > 0)
			{
				out.put("maxTokens", plan.connection.maxTokens);
			}
		}
		out.put("neededMaxTokens", plan.needed);
		final Boolean ok = plan.maxTokensOk();
		if (ok != null)
		{
			out.put("maxTokensOk", ok.booleanValue());
		}
		if (plan.refusal != null)
		{
			out.put("problem", plan.refusal.getMessage());
		}
		return out;
	}
}
