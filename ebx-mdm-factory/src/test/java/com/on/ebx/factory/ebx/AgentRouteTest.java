package com.on.ebx.factory.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.orchestranetworks.service.Session;

/**
 * JUnit-free: run the main, a failure throws. Wave 12: the agent loop's way to the model ({@link AgentRoute}) against a fake MBean that plays the Connectors contract
 * {@code ebx-connectors.sibling} 1.1.0 (with {@code llm.chat}) and 1.0.0 (without): the choice contract / direct / refused, the refusal up front when the connection's
 * {@code maxTokens} is below what a pass needs (no {@code llm.chat} call at all), a capped answer that was cut, the other refusals of the contract as coded messages, a two-turn
 * tool exchange through {@code ContractLlmChat} and {@code AgentLoop}, the status and the connectivity test's fields.
 */
public final class AgentRouteTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String CONNECTION = "OPENAI_MAIN";

	/** The contract MBean's shape: the attributes and operations of ebx-connectors' {@code InJvmContractMBean}. */
	public interface ContractMBean
	{
		String getContractName();

		String getContractVersion();

		String describe();

		String handle(Object pSession, String pMessage);
	}

	private static final class Call
	{
		final Object session;
		final String method;
		final JsonNode params;

		Call(final Object pSession, final String pMethod, final JsonNode pParams)
		{
			this.session = pSession;
			this.method = pMethod;
			this.params = pParams;
		}
	}

	/** Plays the contract: the connections {@code llm.connectors} lists, and the scripted answers of {@code llm.chat} (each a result, or {@code error:TYPE:message}). */
	private static final class FakeContract implements ContractMBean
	{
		volatile String version = "1.1.0";
		/** The {@code connectors} array of {@code llm.connectors}. */
		volatile String connectors = AgentRouteTest.connectors(AgentRouteTest.CONNECTION, 8192, true, true);
		final List<String> chatAnswers = new ArrayList<String>();
		final List<Call> calls = new ArrayList<Call>();

		@Override
		public String getContractName()
		{
			return ConnectorsContractClient.EXPECTED_NAME;
		}

		@Override
		public String getContractVersion()
		{
			return this.version;
		}

		@Override
		public String describe()
		{
			return "{\"name\":\"" + ConnectorsContractClient.EXPECTED_NAME + "\",\"version\":\"" + this.version + "\"}";
		}

		@Override
		public String handle(final Object pSession, final String pMessage)
		{
			try
			{
				final JsonNode request = AgentRouteTest.MAPPER.readTree(pMessage);
				final String method = request.path("method").asText();
				synchronized (this.calls)
				{
					this.calls.add(new Call(pSession, method, request.path("params")));
				}
				if ("contract.describe".equals(method))
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + this.describe() + "}";
				}
				if ("llm.connectors".equals(method))
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"connectors\":" + this.connectors + ",\"default\":\"" + AgentRouteTest.CONNECTION + "\"}}";
				}
				if ("llm.chat".equals(method))
				{
					final String answer;
					synchronized (this.chatAnswers)
					{
						answer = this.chatAnswers.isEmpty() ? null : this.chatAnswers.remove(0);
					}
					if (answer == null)
					{
						return this.error("INTERNAL", "no scripted answer");
					}
					if (answer.startsWith("error:"))
					{
						final String[] parts = answer.split(":", 3);
						return this.error(parts[1], parts[2]);
					}
					return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + answer + "}";
				}
				return this.error("UNKNOWN_OPERATION", "unknown operation " + method);
			}
			catch (final Exception ex)
			{
				return "not json";
			}
		}

		private String error(final String pType, final String pMessage)
		{
			return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32020,\"message\":\"" + pMessage + "\",\"data\":{\"type\":\"" + pType + "\"}}}";
		}

		List<Call> callsOf(final String pMethod)
		{
			final List<Call> out = new ArrayList<Call>();
			synchronized (this.calls)
			{
				for (final Call call : this.calls)
				{
					if (call.method.equals(pMethod))
					{
						out.add(call);
					}
				}
			}
			return out;
		}
	}

	private static String connectors(final String pId, final int pMaxTokens, final boolean pChat, final boolean pActive)
	{
		return "[{\"id\":\"" + pId + "\",\"label\":\"Main model\",\"provider\":\"openai\",\"model\":\"gpt-test\",\"maxTokens\":" + pMaxTokens + ",\"chat\":" + pChat + ",\"active\":"
			+ pActive + ",\"secretSet\":true,\"usable\":true}]";
	}

	/** A {@code llm.chat} result. */
	private static String turn(final String pContent, final String pToolCallJson, final String pStop, final int pApplied, final boolean pCapped)
	{
		return "{\"message\":{\"role\":\"assistant\",\"content\":\"" + pContent + "\",\"toolCalls\":[" + (pToolCallJson == null ? "" : pToolCallJson) + "]},\"stopReason\":\"" + pStop
			+ "\",\"usage\":{\"inputTokens\":100,\"outputTokens\":20,\"totalTokens\":120},\"model\":\"gpt-reported\",\"provider\":\"openai\",\"connector\":\"" + AgentRouteTest.CONNECTION
			+ "\",\"maxTokensApplied\":" + pApplied + ",\"capped\":" + pCapped + ",\"durationMs\":5}";
	}

	private static Session session()
	{
		return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
			{
				return "toString".equals(pMethod.getName()) ? "fake-session" : null;
			}
		});
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final MBeanServer server = MBeanServerFactory.newMBeanServer();
		ConnectorsContractClient.useServerForTest(server);
		final String savedVia = System.getProperty(ConnectorsContractClient.PROPERTY_VIA);
		final String savedMax = System.getProperty(AgentRoute.PROPERTY_MAX_TOKENS);
		try
		{
			System.clearProperty(ConnectorsContractClient.PROPERTY_VIA);
			System.clearProperty(AgentRoute.PROPERTY_MAX_TOKENS);
			AgentRouteTest.settings();
			AgentRouteTest.optionsCarryMaxTokensAndChat();
			AgentRouteTest.decisionTable();
			final FakeContract fake = new FakeContract();
			final ObjectName name = new ObjectName(ConnectorsContractClient.OBJECT_NAME);
			server.registerMBean(new StandardMBean(fake, ContractMBean.class), name);
			try
			{
				AgentRouteTest.absentContract();
				AgentRouteTest.contractOnePointZero(fake);
				AgentRouteTest.routeThroughTheMBean(fake);
				AgentRouteTest.maxTokensTooLowIsRefusedBeforeAnyCall(fake);
				AgentRouteTest.twoTurnToolExchange(fake);
				AgentRouteTest.cappedAndCut(fake);
				AgentRouteTest.cappedButNotCut(fake);
				AgentRouteTest.otherRefusals(fake);
				AgentRouteTest.statusAndConnectivityFields(fake);
				AgentRouteTest.loggedOnce(fake);
			}
			finally
			{
				server.unregisterMBean(name);
			}
			ConnectorsBridge.stop();
		}
		finally
		{
			ConnectorsContractClient.useServerForTest(null);
			AgentRouteTest.restore(ConnectorsContractClient.PROPERTY_VIA, savedVia);
			AgentRouteTest.restore(AgentRoute.PROPERTY_MAX_TOKENS, savedMax);
		}
		System.out.println("AgentRouteTest OK");
	}

	private static void restore(final String pName, final String pValue)
	{
		if (pValue == null)
		{
			System.clearProperty(pName);
		}
		else
		{
			System.setProperty(pName, pValue);
		}
	}

	// ------------------------------------------------------------------------------------------------------ pure

	private static void settings()
	{
		check("4096 by default", AgentRoute.neededMaxTokens() == 4096);
		System.setProperty(AgentRoute.PROPERTY_MAX_TOKENS, " 8000 ");
		check("the property sets what a pass needs", AgentRoute.neededMaxTokens() == 8000);
		System.setProperty(AgentRoute.PROPERTY_MAX_TOKENS, "12");
		check("below the lowest sensible value: the default", AgentRoute.neededMaxTokens() == 4096);
		System.setProperty(AgentRoute.PROPERTY_MAX_TOKENS, "abc");
		check("not a number: the default", AgentRoute.neededMaxTokens() == 4096);
		System.clearProperty(AgentRoute.PROPERTY_MAX_TOKENS);
		check("no turn timeout by default (the connection's own)", AgentRoute.turnTimeoutMs() == null);
		System.setProperty(AgentRoute.PROPERTY_TURN_TIMEOUT_MS, "90000");
		check("turn timeout from the property", AgentRoute.turnTimeoutMs() != null && AgentRoute.turnTimeoutMs().intValue() == 90000);
		System.setProperty(AgentRoute.PROPERTY_TURN_TIMEOUT_MS, "5");
		check("outside the contract's bounds: not set", AgentRoute.turnTimeoutMs() == null);
		System.clearProperty(AgentRoute.PROPERTY_TURN_TIMEOUT_MS);
	}

	private static void optionsCarryMaxTokensAndChat() throws Exception
	{
		final ObjectNode result = (ObjectNode) AgentRouteTest.MAPPER.readTree("{\"connectors\":" + AgentRouteTest.connectors("A", 1024, true, true) + "}");
		final AgentRuntime.LlmConnectorOption option = LlmContract.optionsOf(result).get(0);
		check("maxTokens of llm.connectors", option.maxTokens == 1024 && option.chat);
		final ObjectNode older = (ObjectNode) AgentRouteTest.MAPPER.readTree("{\"connectors\":[{\"id\":\"B\",\"label\":\"B\",\"provider\":\"openai\",\"model\":\"m\",\"active\":true}]}");
		final AgentRuntime.LlmConnectorOption old = LlmContract.optionsOf(older).get(0);
		check("a contract 1.0 answer has neither: 0 and false", old.maxTokens == 0 && !old.chat);
	}

	private static ConnectorsContractClient.Status ready(final String pVersion)
	{
		return new ConnectorsContractClient.Status(ConnectorsContractClient.State.READY, ConnectorsContractClient.EXPECTED_NAME, pVersion, null);
	}

	private static AgentRoute.ConnectionsReader reader(final String pConnectorsJson)
	{
		return new AgentRoute.ConnectionsReader()
		{
			@Override
			public List<AgentRuntime.LlmConnectorOption> read() throws ConnectorsContractClient.Refusal
			{
				try
				{
					return LlmContract.optionsOf((ObjectNode) AgentRouteTest.MAPPER.readTree("{\"connectors\":" + pConnectorsJson + "}"));
				}
				catch (final java.io.IOException ex)
				{
					throw new IllegalStateException(ex);
				}
			}
		};
	}

	private static AgentRoute.ConnectionsReader neverRead()
	{
		return new AgentRoute.ConnectionsReader()
		{
			@Override
			public List<AgentRuntime.LlmConnectorOption> read()
			{
				throw new AssertionError("llm.connectors must not be read here");
			}
		};
	}

	/** The choice, with no MBean at all: the contract's state, the way asked for and the connection's description are given. */
	private static void decisionTable()
	{
		final Session session = AgentRouteTest.session();
		final ConnectorsContractClient.Via auto = ConnectorsContractClient.Via.AUTO;
		final ConnectorsContractClient.Via contract = ConnectorsContractClient.Via.CONTRACT;
		final String id = AgentRouteTest.CONNECTION;

		AgentRoute.Plan plan = AgentRoute.resolve(session, id, ConnectorsContractClient.Via.DIRECT, null, AgentRouteTest.neverRead());
		check("via=direct: direct, the contract is not even looked at", plan.kind == AgentRoute.Kind.DIRECT && plan.why.contains("direct"));
		plan = AgentRoute.resolve(null, id, auto, null, AgentRouteTest.neverRead());
		check("no session: direct", plan.kind == AgentRoute.Kind.DIRECT && plan.why.contains("no session"));

		final ConnectorsContractClient.Status absent = new ConnectorsContractClient.Status(ConnectorsContractClient.State.ABSENT, null, null, "no MBean");
		plan = AgentRoute.resolve(session, id, auto, absent, AgentRouteTest.neverRead());
		check("no contract, auto: direct", plan.kind == AgentRoute.Kind.DIRECT && plan.why.contains("no MBean"));
		plan = AgentRoute.resolve(session, id, contract, absent, AgentRouteTest.neverRead());
		check("no contract, via=contract: refused, coded", plan.kind == AgentRoute.Kind.REFUSED && "engine.external.connectorsContractUnavailable".equals(plan.refusal.code()));

		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.0.0"), AgentRouteTest.neverRead());
		check("contract 1.0.0 has no llm.chat: direct, nothing read from the contract", plan.kind == AgentRoute.Kind.DIRECT && plan.why.contains("1.0.0") && plan.why.contains("1.1.0"));
		plan = AgentRoute.resolve(session, id, contract, AgentRouteTest.ready("1.0.0"), AgentRouteTest.neverRead());
		check("contract 1.0.0, via=contract: refused", plan.kind == AgentRoute.Kind.REFUSED && "engine.external.connectorsContractUnavailable".equals(plan.refusal.code())
			&& plan.refusal.getMessage().contains("1.1.0"));
		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("2.0.0"), AgentRouteTest.neverRead());
		check("another major: direct", plan.kind == AgentRoute.Kind.DIRECT);

		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.1.0"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 8192, true, true)));
		check("1.1.0, chat served, maxTokens 8192: contract, asks the connection's own 8192", plan.kind == AgentRoute.Kind.CONTRACT && plan.asked == 8192 && plan.refusal == null
			&& Boolean.TRUE.equals(plan.maxTokensOk()));
		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.3.2"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 4096, true, true)));
		check("a later minor is fine, exactly the needed value passes and is asked", plan.kind == AgentRoute.Kind.CONTRACT && plan.asked == 4096);
		plan = AgentRoute.resolve(session, id + " ", auto, AgentRouteTest.ready("1.1.0"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 0, true, true)));
		check("a connection that reports no maxTokens: contract, asks what is needed, nothing to compare", plan.kind == AgentRoute.Kind.CONTRACT && plan.asked == 4096 && plan.maxTokensOk() == null);

		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.1.0"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 1024, true, true)));
		check("maxTokens 1024 < 4096: refused", plan.kind == AgentRoute.Kind.REFUSED && "engine.llm.maxTokensTooLow".equals(plan.refusal.code()) && Boolean.FALSE.equals(plan.maxTokensOk()));
		check("the message names the connection and the value to set", plan.refusal.getMessage().contains("Main model") && plan.refusal.getMessage().contains("at least 4096")
			&& plan.refusal.getMessage().contains("1024"));
		check("English sentence", plan.refusal.sentence(Locale.ENGLISH).contains("Main model") && plan.refusal.sentence(Locale.ENGLISH).contains("1024")
			&& plan.refusal.sentence(Locale.ENGLISH).contains("4096"));
		check("French sentence", plan.refusal.sentence(Locale.FRENCH).contains("Main model") && plan.refusal.sentence(Locale.FRENCH).contains("4096")
			&& !plan.refusal.sentence(Locale.FRENCH).equals(plan.refusal.sentence(Locale.ENGLISH)));

		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.1.0"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 8192, false, true)));
		check("a connection llm.chat does not serve (Google): direct", plan.kind == AgentRoute.Kind.DIRECT && plan.why.contains("llm.chat does not serve"));
		plan = AgentRoute.resolve(session, id, contract, AgentRouteTest.ready("1.1.0"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 8192, false, true)));
		check("... refused with via=contract", plan.kind == AgentRoute.Kind.REFUSED && "engine.external.connectorsLlmUnusable".equals(plan.refusal.code()));

		plan = AgentRoute.resolve(session, "ELSEWHERE", auto, AgentRouteTest.ready("1.1.0"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 8192, true, true)));
		check("a connection the contract does not list: the same refusal as the direct way", plan.kind == AgentRoute.Kind.REFUSED && "engine.llm.missing".equals(plan.refusal.code()));
		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.1.0"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 8192, true, false)));
		check("an inactive connection: refused like the direct way", plan.kind == AgentRoute.Kind.REFUSED && "engine.llm.inactive".equals(plan.refusal.code()));

		System.setProperty(AgentRoute.PROPERTY_MAX_TOKENS, "16000");
		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.1.0"), AgentRouteTest.reader(AgentRouteTest.connectors(id, 8192, true, true)));
		check("what a pass needs is a setting: 16000 > 8192 refused, the sentence says 16000",
			plan.kind == AgentRoute.Kind.REFUSED && plan.refusal.getMessage().contains("at least 16000"));
		System.clearProperty(AgentRoute.PROPERTY_MAX_TOKENS);

		final AgentRoute.ConnectionsReader denied = new AgentRoute.ConnectionsReader()
		{
			@Override
			public List<AgentRuntime.LlmConnectorOption> read() throws ConnectorsContractClient.Refusal
			{
				throw new ConnectorsContractClient.Refusal(ConnectorsContractClient.ACCESS_DENIED, "no READ right");
			}
		};
		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.1.0"), denied);
		check("a caller without the Connectors right: refused, coded", plan.kind == AgentRoute.Kind.REFUSED && "engine.external.connectorsContractDenied".equals(plan.refusal.code()));
		final AgentRoute.ConnectionsReader gone = new AgentRoute.ConnectionsReader()
		{
			@Override
			public List<AgentRuntime.LlmConnectorOption> read() throws ConnectorsContractClient.Refusal
			{
				throw new ConnectorsContractClient.Refusal(ConnectorsContractClient.DISABLED, "switched off since");
			}
		};
		plan = AgentRoute.resolve(session, id, auto, AgentRouteTest.ready("1.1.0"), gone);
		check("a contract switched off since the check: auto falls back", plan.kind == AgentRoute.Kind.DIRECT);
		plan = AgentRoute.resolve(session, id, contract, AgentRouteTest.ready("1.1.0"), gone);
		check("... never with via=contract", plan.kind == AgentRoute.Kind.REFUSED);
	}

	// ----------------------------------------------------------------------------------- with the fake MBean

	private static void absentContract()
	{
		// the fake is registered by now; the "absent" case of the live check is covered by ConnectorsContractClientTest. Here: the live status of the registered fake.
		check("the fake is READY", ConnectorsContractClient.status().ready());
	}

	private static void contractOnePointZero(final FakeContract pFake)
	{
		pFake.version = "1.0.0";
		final int before = pFake.callsOf("llm.connectors").size();
		final AgentRoute.Plan plan = AgentRoute.resolve(AgentRouteTest.session(), AgentRouteTest.CONNECTION);
		check("contract 1.0.0 offered: the fallback, as before wave 12", plan.kind == AgentRoute.Kind.DIRECT);
		check("... without a call to llm.connectors or llm.chat", pFake.callsOf("llm.connectors").size() == before && pFake.callsOf("llm.chat").isEmpty());
		pFake.version = "1.1.0";
	}

	private static void routeThroughTheMBean(final FakeContract pFake)
	{
		final Session session = AgentRouteTest.session();
		final AgentRoute.Plan plan = AgentRoute.resolve(session, AgentRouteTest.CONNECTION);
		check("1.1.0 + chat + 8192: the contract, " + plan.why, plan.kind == AgentRoute.Kind.CONTRACT && plan.asked == 8192);
		final List<Call> reads = pFake.callsOf("llm.connectors");
		check("llm.connectors was read as the pass's own session", reads.get(reads.size() - 1).session == session);
		check("nothing was sent to the model by choosing", pFake.callsOf("llm.chat").isEmpty());
		System.setProperty(ConnectorsContractClient.PROPERTY_VIA, "direct");
		final int count = pFake.calls.size();
		final AgentRoute.Plan direct = AgentRoute.resolve(session, AgentRouteTest.CONNECTION);
		check("via=direct: direct and the contract is never called", direct.kind == AgentRoute.Kind.DIRECT && pFake.calls.size() == count);
		System.clearProperty(ConnectorsContractClient.PROPERTY_VIA);
	}

	private static void maxTokensTooLowIsRefusedBeforeAnyCall(final FakeContract pFake)
	{
		pFake.connectors = AgentRouteTest.connectors(AgentRouteTest.CONNECTION, 1024, true, true);
		final AgentRoute.Plan plan = AgentRoute.resolve(AgentRouteTest.session(), AgentRouteTest.CONNECTION);
		check("connection maxTokens 1024: refused up front", plan.kind == AgentRoute.Kind.REFUSED && "engine.llm.maxTokensTooLow".equals(plan.refusal.code()));
		check("no llm.chat call", pFake.callsOf("llm.chat").isEmpty());
		pFake.connectors = AgentRouteTest.connectors(AgentRouteTest.CONNECTION, 8192, true, true);
	}

	private static ToolSet tools(final AtomicInteger pCalls) throws McpException
	{
		final LocalTool propose = new LocalTool("propose_entity", "Records a proposal", AgentRouteTest.MAPPER.createObjectNode().put("type", "object"), new LocalTool.Handler()
		{
			@Override
			public LocalTool.Result handle(final JsonNode pArguments)
			{
				return LocalTool.Result.ok("recorded #" + pCalls.incrementAndGet() + " for " + pArguments.path("entity").asText());
			}
		});
		final Map<String, ToolSet.LocalGroup> groups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		groups.put("factory", new ToolSet.LocalGroup(Collections.singletonList(propose)));
		return ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), groups);
	}

	private static AgentLoop.Budget budget()
	{
		return new AgentLoop.Budget(10, 1000000, 60000L);
	}

	/** One tool call, its result fed back, a final answer: two {@code llm.chat} calls through ContractLlmChat, the tool run in this module. */
	private static void twoTurnToolExchange(final FakeContract pFake) throws Exception
	{
		final Session session = AgentRouteTest.session();
		final AgentRoute.Plan plan = AgentRoute.resolve(session, AgentRouteTest.CONNECTION);
		final AgentRoute.Guard guard = AgentRoute.chat(session, plan);
		pFake.chatAnswers.clear();
		pFake.chatAnswers.add(AgentRouteTest.turn("I propose one", "{\"id\":\"call-1\",\"name\":\"factory__propose_entity\",\"arguments\":{\"entity\":\"Person\"}}", "TOOL_USE", 8192, false));
		pFake.chatAnswers.add(AgentRouteTest.turn("All done", null, "END", 8192, false));
		final AtomicInteger toolRuns = new AtomicInteger();
		final int chatsBefore = pFake.callsOf("llm.chat").size();
		final List<String> turns = new ArrayList<String>();
		final AgentLoop.Result result = new AgentLoop(guard, AgentRouteTest.tools(toolRuns), AgentRouteTest.budget(), new AgentLoop.Listener()
		{
			@Override
			public void onTurn(final int pStep, final com.on.ebx.agent.LlmClient.Turn pTurn)
			{
				turns.add(pTurn.text);
			}

			@Override
			public void onToolCall(final String pServer, final String pTool, final JsonNode pArguments, final String pResultText, final boolean pIsError, final long pDurationMs)
			{
				turns.add("tool:" + pTool + ":" + pResultText);
			}
		}).run("system prompt", "task");
		check("DONE with the final answer: " + result.status + " " + result.detail, result.status == AgentLoop.Status.DONE && "All done".equals(result.text));
		check("the tool ran once, in this module", toolRuns.get() == 1);
		check("the listener saw the model turn, the tool call, the last turn: " + turns, turns.size() == 3 && turns.get(1).startsWith("tool:propose_entity:recorded #1 for Person"));
		check("input and output tokens added up over both turns", result.inputTokens == 200 && result.outputTokens == 40 && result.steps == 2);
		final List<Call> chats = pFake.callsOf("llm.chat");
		check("two llm.chat calls", chats.size() - chatsBefore == 2);
		final Call first = chats.get(chats.size() - 2);
		final Call second = chats.get(chats.size() - 1);
		check("the pass's own session reaches Connectors", first.session == session && second.session == session);
		check("the connection and the answer size are sent: " + first.params, AgentRouteTest.CONNECTION.equals(first.params.path("connector").asText()) && first.params.path("maxTokens").asInt() == 8192
			&& first.params.path("system").asText().endsWith("system prompt"));
		check("the tools are described", first.params.path("tools").path(0).path("name").asText().equals("factory__propose_entity"));
		final JsonNode messages = second.params.path("messages");
		check("the second call carries the whole conversation: user, assistant with its tool call, the tool's result",
			messages.size() == 3 && "user".equals(messages.path(0).path("role").asText()) && "assistant".equals(messages.path(1).path("role").asText())
				&& "call-1".equals(messages.path(1).path("toolCalls").path(0).path("id").asText()) && "tool".equals(messages.path(2).path("role").asText())
				&& "call-1".equals(messages.path(2).path("toolCallId").asText()) && messages.path(2).path("content").asText().startsWith("recorded #1"));
		check("the guard knows the model the provider reported", "gpt-reported".equals(guard.model()));
		check("no failure to report", guard.failure() == null && !guard.capped());
	}

	/** A cut answer: asked more than the connection gives (capped) AND stopped at the cap. The pass ends with the answer-size message. */
	private static void cappedAndCut(final FakeContract pFake) throws Exception
	{
		final Session session = AgentRouteTest.session();
		final AgentRoute.Plan plan = AgentRoute.resolve(session, AgentRouteTest.CONNECTION);
		final AgentRoute.Guard guard = AgentRoute.chat(session, plan);
		pFake.chatAnswers.clear();
		pFake.chatAnswers.add(AgentRouteTest.turn("{\\\"half", null, "MAX_TOKENS", 1024, true));
		final AtomicInteger toolRuns = new AtomicInteger();
		final AgentLoop.Result result = new AgentLoop(guard, AgentRouteTest.tools(toolRuns), AgentRouteTest.budget(), null).run("s", "t");
		check("a cut answer ends the loop FAILED: " + result.status, result.status == AgentLoop.Status.FAILED && result.detail.contains("1024"));
		final EngineRefusal failure = guard.failure();
		check("the pass is refused with the answer-size message", failure != null && "engine.llm.maxTokensTooLow".equals(failure.code()));
		check("it names the connection and the value to set (en)", failure.sentence(Locale.ENGLISH).contains("Main model") && failure.sentence(Locale.ENGLISH).contains("1024")
			&& failure.sentence(Locale.ENGLISH).contains("4096"));
		check("... and (fr)", failure.sentence(Locale.FRENCH).contains("Main model") && failure.sentence(Locale.FRENCH).contains("4096"));
		check("the tool never ran on a half answer", toolRuns.get() == 0 && pFake.chatAnswers.isEmpty());
		check("capped is remembered", guard.capped());
	}

	/** Capped but complete (the model stopped by itself under the cap): not a failure. */
	private static void cappedButNotCut(final FakeContract pFake) throws Exception
	{
		final Session session = AgentRouteTest.session();
		final AgentRoute.Guard guard = AgentRoute.chat(session, AgentRoute.resolve(session, AgentRouteTest.CONNECTION));
		pFake.chatAnswers.clear();
		pFake.chatAnswers.add(AgentRouteTest.turn("short answer", null, "END", 2048, true));
		final AgentLoop.Result result = new AgentLoop(guard, AgentRouteTest.tools(new AtomicInteger()), AgentRouteTest.budget(), null).run("s", "t");
		check("DONE", result.status == AgentLoop.Status.DONE && "short answer".equals(result.text));
		check("capped is reported, nothing to refuse", guard.capped() && guard.failure() == null);
	}

	private static void otherRefusals(final FakeContract pFake) throws Exception
	{
		final Session session = AgentRouteTest.session();
		final String[][] coded = { { "ACCESS_DENIED", "engine.external.connectorsContractDenied" }, { "NO_DEFAULT_LLM", "engine.external.connectorsLlmUnusable" },
			{ "CONNECTOR_UNUSABLE", "engine.external.connectorsLlmUnusable" }, { "NOT_FOUND", "engine.external.connectorsLlmUnusable" },
			{ "NOT_SUPPORTED", "engine.external.connectorsLlmUnusable" }, { "DISABLED", "engine.external.connectorsContractUnavailable" } };
		for (final String[] one : coded)
		{
			final AgentRoute.Guard guard = AgentRoute.chat(session, AgentRoute.resolve(session, AgentRouteTest.CONNECTION));
			pFake.chatAnswers.clear();
			pFake.chatAnswers.add("error:" + one[0] + ":the contract says no");
			final AgentLoop.Result result = new AgentLoop(guard, AgentRouteTest.tools(new AtomicInteger()), AgentRouteTest.budget(), null).run("s", "t");
			final EngineRefusal failure = guard.failure();
			check(one[0] + ": FAILED and coded as " + one[1] + ", got " + (failure == null ? null : failure.code()),
				result.status == AgentLoop.Status.FAILED && failure != null && one[1].equals(failure.code()));
			check(one[0] + ": the contract's own words are kept", failure.getMessage().contains("the contract says no"));
		}
		// a provider limit: an ordinary failed call, retried by AgentRuntime.run under LlmRetryPolicy (the detail still says HTTP 429)
		AgentRoute.Guard guard = AgentRoute.chat(session, AgentRoute.resolve(session, AgentRouteTest.CONNECTION));
		pFake.chatAnswers.clear();
		pFake.chatAnswers.add("error:LLM_FAILED:HTTP 429 from the provider: Please try again in 2s");
		AgentLoop.Result result = new AgentLoop(guard, AgentRouteTest.tools(new AtomicInteger()), AgentRouteTest.budget(), null).run("s", "t");
		check("LLM_FAILED stays a FAILED outcome, not a refusal", result.status == AgentLoop.Status.FAILED && guard.failure() == null);
		check("and AgentRuntime.run's 429/503 retry still recognises it: " + result.detail, LlmRetryPolicy.isRetryableMessage(result.detail));
		pFake.chatAnswers.clear();
		pFake.chatAnswers.add("error:TIMEOUT:the model took too long");
		result = new AgentLoop(guard, AgentRouteTest.tools(new AtomicInteger()), AgentRouteTest.budget(), null).run("s", "t");
		check("TIMEOUT likewise", result.status == AgentLoop.Status.FAILED && guard.failure() == null && result.detail.contains("TIMEOUT"));
		// a success after a failure clears it
		pFake.chatAnswers.clear();
		pFake.chatAnswers.add("error:ACCESS_DENIED:no");
		pFake.chatAnswers.add(AgentRouteTest.turn("ok", null, "END", 8192, false));
		guard = AgentRoute.chat(session, AgentRoute.resolve(session, AgentRouteTest.CONNECTION));
		new AgentLoop(guard, AgentRouteTest.tools(new AtomicInteger()), AgentRouteTest.budget(), null).run("s", "t");
		check("a refusal is remembered", guard.failure() != null);
		result = new AgentLoop(guard, AgentRouteTest.tools(new AtomicInteger()), AgentRouteTest.budget(), null).run("s", "t");
		check("... until a turn is answered", result.status == AgentLoop.Status.DONE && guard.failure() == null);
	}

	private static void statusAndConnectivityFields(final FakeContract pFake)
	{
		final Session session = AgentRouteTest.session();
		ObjectNode json = AgentRoute.statusJson(session, AgentRouteTest.CONNECTION);
		check("status: contract route, the connection's maxTokens checked: " + json, "contract".equals(json.path("route").asText()) && json.path("maxTokens").asInt() == 8192
			&& json.path("neededMaxTokens").asInt() == 4096 && json.path("maxTokensOk").asBoolean(false) && json.path("chat").asBoolean(false)
			&& AgentRouteTest.CONNECTION.equals(json.path("connector").asText()) && !json.has("problem"));
		pFake.connectors = AgentRouteTest.connectors(AgentRouteTest.CONNECTION, 1024, true, true);
		json = AgentRoute.statusJson(session, AgentRouteTest.CONNECTION);
		check("status: too low is said, with the sentence: " + json, "refused".equals(json.path("route").asText()) && json.has("maxTokensOk") && !json.path("maxTokensOk").asBoolean(true)
			&& json.path("problem").asText().contains("at least 4096"));
		// the connectivity test
		final ObjectNode test = AgentRouteTest.MAPPER.createObjectNode();
		test.put("ok", true);
		AgentRuntime.putAgentCheck(test, AgentRoute.resolve(session, AgentRouteTest.CONNECTION));
		check("connectivity test: the answer came but the connection cannot serve a pass: not ok, the sentence says what to set: " + test, !test.path("ok").asBoolean(true)
			&& test.path("problem").asText().contains("at least 4096") && test.path("maxTokens").asInt() == 1024 && test.path("neededMaxTokens").asInt() == 4096
			&& "refused".equals(test.path("agentRoute").asText()));
		pFake.connectors = AgentRouteTest.connectors(AgentRouteTest.CONNECTION, 8192, true, true);
		final ObjectNode fine = AgentRouteTest.MAPPER.createObjectNode();
		fine.put("ok", true);
		AgentRuntime.putAgentCheck(fine, AgentRoute.resolve(session, AgentRouteTest.CONNECTION));
		check("connectivity test, connection fine: still ok, route and sizes reported: " + fine, fine.path("ok").asBoolean(false) && !fine.has("problem")
			&& "contract".equals(fine.path("agentRoute").asText()) && fine.path("maxTokens").asInt() == 8192);
		check("no connection chosen", "none".equals(AgentRoute.statusJson(session, null).path("route").asText()));
		pFake.version = "1.0.0";
		json = AgentRoute.statusJson(session, AgentRouteTest.CONNECTION);
		check("status with contract 1.0.0: direct, and says why: " + json, "direct".equals(json.path("route").asText()) && json.path("why").asText().contains("1.1.0")
			&& !json.has("maxTokensOk"));
		pFake.version = "1.1.0";
	}

	private static void loggedOnce(final FakeContract pFake)
	{
		final Session session = AgentRouteTest.session();
		AgentRoute.forgetLoggedForTest();
		final AgentRoute.Plan contract = AgentRoute.resolve(session, AgentRouteTest.CONNECTION);
		AgentRoute.logOnce(contract);
		final String first = AgentRoute.lastLoggedForTest();
		check("the choice is logged: " + first, first != null && first.startsWith("CONTRACT|" + AgentRouteTest.CONNECTION));
		AgentRoute.logOnce(contract);
		check("the same choice is not logged again", first.equals(AgentRoute.lastLoggedForTest()));
		pFake.version = "1.0.0";
		AgentRoute.logOnce(AgentRoute.resolve(session, AgentRouteTest.CONNECTION));
		check("another choice is: " + AgentRoute.lastLoggedForTest(), AgentRoute.lastLoggedForTest().startsWith("DIRECT|"));
		pFake.version = "1.1.0";
	}
}
