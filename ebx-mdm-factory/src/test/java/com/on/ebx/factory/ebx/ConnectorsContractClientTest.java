package com.on.ebx.factory.ebx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.landing.LandingFlow;
import com.orchestranetworks.service.Session;

/**
 * JUnit-free: run the main, a failure throws. Wave 11 (audit F-26): {@link ConnectorsContractClient} against a fake MBean that plays
 * the Connectors contract {@code ebx-connectors.sibling} 1.0.0 (same ObjectName, attributes and {@code handle} signature, on a private MBean
 * server): contract present / absent / incompatible / switched off / refusing, the three ways ({@code ebx.factory.connectors.via}), the typed
 * errors mapped to coded engine messages (both languages), the single-turn LLM operations, the flow operations and the landing flow written
 * through the contract (and the rename that stays on the tool).
 */
public final class ConnectorsContractClientTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The contract MBean's shape: the attributes and operations of ebx-connectors' {@code InJvmContractMBean}. */
	public interface ContractMBean
	{
		String getContractName();

		String getContractVersion();

		String describe();

		String handle(Object pSession, String pMessage);
	}

	/** One recorded request. */
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

	/** An answer script entry: a result, or a typed error. */
	private interface Handler
	{
		JsonNode answer(JsonNode pParams) throws Failure;
	}

	private static final class Failure extends Exception
	{
		private static final long serialVersionUID = 1L;
		final String type;

		Failure(final String pType, final String pMessage)
		{
			super(pMessage);
			this.type = pType;
		}
	}

	/** Plays the contract. */
	private static final class FakeContract implements ContractMBean
	{
		volatile String name = ConnectorsContractClient.EXPECTED_NAME;
		volatile String version = "1.0.0";
		volatile boolean disabled;
		final Map<String, Handler> script = new LinkedHashMap<String, Handler>();
		final List<Call> calls = new ArrayList<Call>();

		@Override
		public String getContractName()
		{
			return this.name;
		}

		@Override
		public String getContractVersion()
		{
			return this.version;
		}

		@Override
		public String describe()
		{
			return "{\"name\":\"" + this.name + "\",\"version\":\"" + this.version + "\"}";
		}

		@Override
		public String handle(final Object pSession, final String pMessage)
		{
			try
			{
				final JsonNode request = ConnectorsContractClientTest.MAPPER.readTree(pMessage);
				final String method = request.path("method").asText();
				final JsonNode params = request.path("params");
				synchronized (this.calls)
				{
					this.calls.add(new Call(pSession, method, params));
				}
				if (this.disabled)
				{
					return this.error(-32002, "DISABLED", "the contract is switched off");
				}
				if ("contract.describe".equals(method))
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + this.describe() + "}";
				}
				if (pSession == null)
				{
					return this.error(-32001, "SESSION_REQUIRED", "a session is required");
				}
				final Handler handler = this.script.get(method);
				if (handler == null)
				{
					return this.error(-32601, "UNKNOWN_OPERATION", "unknown operation " + method);
				}
				try
				{
					return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + handler.answer(params) + "}";
				}
				catch (final Failure ex)
				{
					return this.error(-32020, ex.type, ex.getMessage());
				}
			}
			catch (final Exception ex)
			{
				return "not json";
			}
		}

		private String error(final int pCode, final String pType, final String pMessage)
		{
			return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + pCode + ",\"message\":\"" + pMessage + "\",\"data\":{\"type\":\"" + pType + "\"}}}";
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

	private static JsonNode json(final String pText)
	{
		try
		{
			return ConnectorsContractClientTest.MAPPER.readTree(pText);
		}
		catch (final java.io.IOException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final MBeanServer server = MBeanServerFactory.newMBeanServer();
		ConnectorsContractClient.useServerForTest(server);
		final String savedVia = System.getProperty(ConnectorsContractClient.PROPERTY_VIA);
		try
		{
			ConnectorsContractClientTest.pureRules();
			ConnectorsContractClientTest.absent();
			final FakeContract fake = new FakeContract();
			final ObjectName name = new ObjectName(ConnectorsContractClient.OBJECT_NAME);
			server.registerMBean(new StandardMBean(fake, ContractMBean.class), name);
			try
			{
				ConnectorsContractClientTest.present(fake);
				ConnectorsContractClientTest.waysOfWorking(fake);
				ConnectorsContractClientTest.incompatible(fake);
				ConnectorsContractClientTest.switchedOff(fake);
				ConnectorsContractClientTest.refusals(fake);
				ConnectorsContractClientTest.llmOperations(fake);
				ConnectorsContractClientTest.flowOperations(fake);
				ConnectorsContractClientTest.landingFlowThroughTheContract(fake);
				ConnectorsContractClientTest.distributionAndDataProductThroughTheContract(fake);
				ConnectorsContractClientTest.allowListStillApplies(fake);
				ConnectorsContractClientTest.statusLoggedOnce(fake, server);
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
			if (savedVia == null)
			{
				System.clearProperty(ConnectorsContractClient.PROPERTY_VIA);
			}
			else
			{
				System.setProperty(ConnectorsContractClient.PROPERTY_VIA, savedVia);
			}
		}
		System.out.println("ConnectorsContractClientTest OK");
	}

	// ------------------------------------------------------------------------------------------------------ pure

	private static void pureRules()
	{
		check("auto by default", ConnectorsContractClient.Via.parse(null) == ConnectorsContractClient.Via.AUTO);
		check("auto for an unknown word", ConnectorsContractClient.Via.parse("whatever") == ConnectorsContractClient.Via.AUTO);
		check("contract, any case and spaces", ConnectorsContractClient.Via.parse(" Contract ") == ConnectorsContractClient.Via.CONTRACT);
		check("direct", ConnectorsContractClient.Via.parse("direct") == ConnectorsContractClient.Via.DIRECT);

		check("same version", ConnectorsContractClient.compatible("1.0.0", "1.0.0"));
		check("a newer minor is compatible", ConnectorsContractClient.compatible("1.1.0", "1.0.0"));
		check("a newer patch is compatible", ConnectorsContractClient.compatible("1.0.3", "1.0.0"));
		check("an older minor is not", !ConnectorsContractClient.compatible("1.0.0", "1.1.0"));
		check("an older patch is not", !ConnectorsContractClient.compatible("1.1.0", "1.1.2"));
		check("another major is not, newer", !ConnectorsContractClient.compatible("2.0.0", "1.0.0"));
		check("another major is not, older", !ConnectorsContractClient.compatible("0.9.9", "1.0.0"));
		check("not three numbers is not", !ConnectorsContractClient.compatible("1.0", "1.0.0") && !ConnectorsContractClient.compatible("1.x.0", "1.0.0")
			&& !ConnectorsContractClient.compatible(null, "1.0.0") && !ConnectorsContractClient.compatible("1.0.0-rc1", "1.0.0"));
	}

	// ---------------------------------------------------------------------------------------------------- absent

	private static void absent() throws Exception
	{
		System.clearProperty(ConnectorsContractClient.PROPERTY_VIA);
		final ConnectorsContractClient.Status status = ConnectorsContractClient.status();
		check("no MBean: ABSENT", status.state == ConnectorsContractClient.State.ABSENT && !status.ready() && status.detail.contains("ebx-connectors"));
		check("auto: nothing to use, the earlier way", !ConnectorsContractClient.useContract());
		try
		{
			ConnectorsContractClient.call(session(), "llm.ask", null);
			check("a call without the contract must be refused", false);
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			check("the refusal is ABSENT: " + ex.type, ConnectorsContractClient.ABSENT.equals(ex.type));
			check("and the earlier way may take over (auto)", ConnectorsContractClient.mayFallBack(ex));
		}
		check("the screens' status says so", "ABSENT".equals(ConnectorsContractClient.statusJson().path("state").asText())
			&& !ConnectorsContractClient.statusJson().path("active").asBoolean(true));
		System.setProperty(ConnectorsContractClient.PROPERTY_VIA, "contract");
		try
		{
			ConnectorsContractClient.useContract();
			check("via=contract without a contract must refuse, never fall back", false);
		}
		catch (final EngineRefusal ex)
		{
			check("coded refusal: " + ex.code(), "engine.external.connectorsContractUnavailable".equals(ex.code()));
			check("worded in both languages", ex.sentence(Locale.ENGLISH).contains("Connectors") && ex.sentence(Locale.FRENCH).contains("Connectors")
				&& !ex.sentence(Locale.ENGLISH).equals(ex.sentence(Locale.FRENCH)));
			check("and the English engine text keeps the property name", ex.getMessage().contains(ConnectorsContractClient.PROPERTY_VIA));
		}
		check("via=contract: a refusal is not a reason to fall back", !ConnectorsContractClient.mayFallBack(new ConnectorsContractClient.Refusal(ConnectorsContractClient.ABSENT, "x")));
		System.clearProperty(ConnectorsContractClient.PROPERTY_VIA);
	}

	// --------------------------------------------------------------------------------------------------- present

	private static void present(final FakeContract pFake) throws Exception
	{
		final ConnectorsContractClient.Status status = ConnectorsContractClient.status();
		check("registered and compatible: READY " + status.detail, status.ready() && "1.0.0".equals(status.offered) && ConnectorsContractClient.EXPECTED_NAME.equals(status.name));
		check("the probe is contract.describe, with no session", pFake.callsOf("contract.describe").size() >= 1 && pFake.callsOf("contract.describe").get(0).session == null);
		check("auto uses the contract", ConnectorsContractClient.useContract());
		final ObjectNode json = ConnectorsContractClient.statusJson();
		check("the status JSON: " + json, "auto".equals(json.path("via").asText()) && "READY".equals(json.path("state").asText()) && "1.0.0".equals(json.path("offered").asText())
			&& "1.0.0".equals(json.path("writtenFor").asText()) && json.path("active").asBoolean(false) && !json.has("detail"));
		check("a newer minor of the contract is still ready", ConnectorsContractClientTest.versioned(pFake, "1.4.2").ready());
		pFake.version = "1.0.0";
	}

	private static ConnectorsContractClient.Status versioned(final FakeContract pFake, final String pVersion)
	{
		pFake.version = pVersion;
		return ConnectorsContractClient.status();
	}

	private static void waysOfWorking(final FakeContract pFake)
	{
		System.setProperty(ConnectorsContractClient.PROPERTY_VIA, "direct");
		check("direct: the contract is never used, even when present", !ConnectorsContractClientTest.use());
		check("direct: the status says it is not active", !ConnectorsContractClient.statusJson().path("active").asBoolean(true));
		System.setProperty(ConnectorsContractClient.PROPERTY_VIA, "contract");
		check("contract: used when present", ConnectorsContractClientTest.use());
		System.clearProperty(ConnectorsContractClient.PROPERTY_VIA);
		check("auto: used when present", ConnectorsContractClientTest.use());
	}

	private static boolean use()
	{
		try
		{
			return ConnectorsContractClient.useContract();
		}
		catch (final EngineRefusal ex)
		{
			throw new AssertionError(ex.getMessage());
		}
	}

	// ----------------------------------------------------------------------------------------------- incompatible

	private static void incompatible(final FakeContract pFake) throws Exception
	{
		pFake.version = "2.0.0";
		check("another major: INCOMPATIBLE", ConnectorsContractClient.status().state == ConnectorsContractClient.State.INCOMPATIBLE);
		check("auto: the earlier way", !ConnectorsContractClientTest.use());
		System.setProperty(ConnectorsContractClient.PROPERTY_VIA, "contract");
		try
		{
			ConnectorsContractClient.useContract();
			check("via=contract with an incompatible contract must refuse", false);
		}
		catch (final EngineRefusal ex)
		{
			check("coded, names the versions: " + ex.getMessage(), "engine.external.connectorsContractUnavailable".equals(ex.code()) && ex.getMessage().contains("2.0.0")
				&& ex.getMessage().contains("1.0.0"));
		}
		System.clearProperty(ConnectorsContractClient.PROPERTY_VIA);
		pFake.version = "0.9.0";
		check("an older contract than this module needs: INCOMPATIBLE", ConnectorsContractClient.status().state == ConnectorsContractClient.State.INCOMPATIBLE);
		pFake.version = "1.0.0";
		pFake.name = "something.else";
		check("another contract name: INCOMPATIBLE", ConnectorsContractClient.status().state == ConnectorsContractClient.State.INCOMPATIBLE);
		pFake.name = ConnectorsContractClient.EXPECTED_NAME;
		check("back to READY", ConnectorsContractClient.status().ready());
	}

	// ----------------------------------------------------------------------------------------------- switched off

	private static void switchedOff(final FakeContract pFake) throws Exception
	{
		pFake.disabled = true;
		final ConnectorsContractClient.Status status = ConnectorsContractClient.status();
		check("registered but switched off by Connectors: DISABLED, " + status.state, status.state == ConnectorsContractClient.State.DISABLED
			&& status.detail.contains("ebx.connectors.contract"));
		check("auto: the earlier way", !ConnectorsContractClientTest.use());
		try
		{
			ConnectorsContractClient.call(session(), "llm.connectors", null);
			check("a call to a disabled contract must be refused", false);
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			check("DISABLED, and auto falls back on it", ConnectorsContractClient.DISABLED.equals(ex.type) && ConnectorsContractClient.mayFallBack(ex));
		}
		pFake.disabled = false;
		check("on again", ConnectorsContractClient.status().ready());
	}

	// ------------------------------------------------------------------------------------------------- refusals

	private static void refusals(final FakeContract pFake) throws Exception
	{
		final String[][] cases = { { "ACCESS_DENIED", "engine.external.connectorsContractDenied" }, { "SESSION_REQUIRED", "engine.external.connectorsContractDenied" },
			{ "NO_DEFAULT_LLM", "engine.external.connectorsLlmUnusable" }, { "CONNECTOR_UNUSABLE", "engine.external.connectorsLlmUnusable" },
			{ "NOT_LLM", "engine.external.connectorsLlmUnusable" }, { "NOT_FOUND", "engine.external.connectorsLlmUnusable" }, { "LLM_FAILED", "engine.external.connectorsLlmFailed" },
			{ "TIMEOUT", "engine.external.connectorsLlmFailed" }, { "OPERATION_FAILED", "engine.external.connectorsContractFailed" },
			{ "INTERNAL", "engine.external.connectorsContractFailed" }, { "SOMETHING_NEW", "engine.external.connectorsContractFailed" },
			{ "ABSENT", "engine.external.connectorsContractUnavailable" }, { "DISABLED", "engine.external.connectorsContractUnavailable" },
			{ "INCOMPATIBLE", "engine.external.connectorsContractUnavailable" } };
		for (final String[] one : cases)
		{
			final EngineRefusal refusal = ConnectorsContractClient.engineRefusal(new ConnectorsContractClient.Refusal(one[0], "the reason"), "llm.ask", "LLM1");
			check(one[0] + " -> " + refusal.code(), one[1].equals(refusal.code()));
			check(one[0] + ": the English engine text carries the contract's message", refusal.getMessage().contains("the reason") && refusal.getMessage().contains(one[0]));
			final String en = refusal.sentence(Locale.ENGLISH);
			final String fr = refusal.sentence(Locale.FRENCH);
			check(one[0] + ": a sentence in both languages, no raw key or placeholder: " + en + " / " + fr, !en.startsWith("engine.") && !fr.startsWith("engine.") && !en.contains("{") && !fr.contains("{")
				&& !en.equals(fr));
		}
		check("a flow that is ambiguous says which key", ConnectorsContractClient.engineRefusal(new ConnectorsContractClient.Refusal("AMBIGUOUS_KEY", "two"), "flows.put", "Landing: P S T")
			.sentence(Locale.ENGLISH).contains("Landing: P S T"));
		check("NOT_FOUND of a flow operation is a failure of the request, not an unusable model", "engine.external.connectorsContractFailed"
			.equals(ConnectorsContractClient.engineRefusal(new ConnectorsContractClient.Refusal("NOT_FOUND", "no flow"), "flows.run", "k").code()));

		// through the real exchange: a typed error of the fake comes back as the same Refusal
		pFake.script.put("llm.ask", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("ACCESS_DENIED", "no RUN right");
			}
		});
		try
		{
			ConnectorsContractClient.call(session(), "llm.ask", null);
			check("a refusal of the contract must reach the caller", false);
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			check("typed: " + ex.type + " / " + ex.getMessage(), "ACCESS_DENIED".equals(ex.type) && "no RUN right".equals(ex.getMessage()) && !ConnectorsContractClient.mayFallBack(ex));
		}
		try
		{
			ConnectorsContractClient.call(session(), "no.such.operation", null);
			check("an unknown operation must be refused", false);
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			check("UNKNOWN_OPERATION is an error of the request, never a fallback: " + ex.type, "UNKNOWN_OPERATION".equals(ex.type) && !ConnectorsContractClient.mayFallBack(ex));
		}
		try
		{
			ConnectorsContractClient.call(null, "llm.connectors", null);
			check("no session: SESSION_REQUIRED", false);
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			check("SESSION_REQUIRED: " + ex.type, "SESSION_REQUIRED".equals(ex.type));
		}
		pFake.script.clear();
	}

	// -------------------------------------------------------------------------------------------- llm operations

	private static void llmOperations(final FakeContract pFake) throws Exception
	{
		final Session session = ConnectorsContractClientTest.session();
		pFake.calls.clear();
		pFake.script.put("llm.ask", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json("{\"text\":\"{\\\"message\\\":\\\"hi\\\"}\",\"model\":\"gpt-x\",\"provider\":\"openai\",\"connector\":\"" + pParams.path("connector").asText()
					+ "\",\"usage\":{\"inputTokens\":12,\"outputTokens\":3,\"totalTokens\":15},\"truncated\":false,\"durationMs\":42}");
			}
		});
		final LlmContract.Answer answer = LlmContract.ask(session, "LLM1", "be short", "say hi", 1024, true, 120000);
		check("the answer: text, model, tokens", "{\"message\":\"hi\"}".equals(answer.text) && "gpt-x".equals(answer.model) && answer.inputTokens == 12 && answer.outputTokens == 3
			&& !answer.truncated && "LLM1".equals(answer.connector) && answer.durationMs == 42L);
		final Call sent = pFake.callsOf("llm.ask").get(0);
		check("run as the caller's session", sent.session == session);
		check("parameters: connector, system, prompt, maxTokens, json, timeoutMs: " + sent.params, "LLM1".equals(sent.params.path("connector").asText())
			&& "be short".equals(sent.params.path("system").asText()) && "say hi".equals(sent.params.path("prompt").asText()) && sent.params.path("maxTokens").asInt() == 1024
			&& sent.params.path("json").asBoolean(false) && sent.params.path("timeoutMs").asInt() == 120000 && sent.params.size() == 6);

		final ObjectNode bounded = LlmContract.askParams(null, "  ", "p", 999999, false, 5);
		check("no connector and no system are left out, the bounds of the contract are kept: " + bounded, !bounded.has("connector") && !bounded.has("system")
			&& bounded.path("maxTokens").asInt() == 65536 && bounded.path("timeoutMs").asInt() == LlmContract.MIN_TIMEOUT_MS && !bounded.path("json").asBoolean(true));
		check("the lower bound of maxTokens", LlmContract.askParams("A", null, "p", 0, false, 700000).path("maxTokens").asInt() == 1
			&& LlmContract.askParams("A", null, "p", 10, false, 700000).path("timeoutMs").asInt() == LlmContract.MAX_TIMEOUT_MS);
		check("a truncated answer is reported as such", LlmContract.answerOf(ConnectorsContractClientTest.json("{\"text\":\"{\\\"a\\\"\",\"truncated\":true}")).truncated
			&& LlmContract.answerOf(ConnectorsContractClientTest.json("{\"text\":\"x\"}")).inputTokens == 0);

		// the provider says 429: retried after the delay it states, then answered
		final int[] failures = { 0 };
		pFake.script.put("llm.ask", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				if (failures[0]++ < 1)
				{
					throw new Failure("LLM_FAILED", "HTTP 429 from the provider: rate limit, try again in 0.1s");
				}
				return ConnectorsContractClientTest.json("{\"text\":\"OK\",\"model\":\"m\",\"provider\":\"p\",\"connector\":\"LLM1\",\"truncated\":false}");
			}
		});
		check("a 429 is retried under the policy of the direct way", "OK".equals(LlmContract.askWithRetry(session, "LLM1", null, "ping", 64, false, 60000).text) && failures[0] == 2);
		pFake.script.put("llm.ask", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				failures[0]++;
				throw new Failure("LLM_FAILED", "HTTP 401 from the provider: bad key");
			}
		});
		failures[0] = 0;
		try
		{
			LlmContract.askWithRetry(session, "LLM1", null, "ping", 64, false, 60000);
			check("a 401 must not be retried", false);
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			check("not retried: one call", failures[0] == 1 && "LLM_FAILED".equals(ex.type));
		}
		pFake.script.put("llm.ask", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("NO_DEFAULT_LLM", "name one");
			}
		});
		try
		{
			LlmContract.askWithRetry(session, null, null, "ping", 64, false, 60000);
			check("no default connection must be refused", false);
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			check("NO_DEFAULT_LLM", "NO_DEFAULT_LLM".equals(ex.type));
		}

		pFake.script.put("llm.connectors", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json("{\"connectors\":[{\"id\":\"B\",\"label\":\"zeta\",\"provider\":\"anthropic\",\"model\":\"claude\",\"active\":true,\"secretSet\":true,\"usable\":true},"
					+ "{\"id\":\"OFF\",\"label\":\"alpha off\",\"provider\":\"openai\",\"model\":\"gpt\",\"active\":false,\"secretSet\":false,\"usable\":false,\"problem\":\"inactive\"},"
					+ "{\"id\":\"A\",\"label\":\"\",\"provider\":\"ollama\",\"model\":\"llama\",\"active\":true,\"secretSet\":true,\"usable\":true}],\"default\":\"B\",\"defaultSource\":\"property\"}");
			}
		});
		final List<AgentRuntime.LlmConnectorOption> options = LlmContract.connectors(session);
		check("three options", options.size() == 3);
		check("active first, then by label (a blank label shows the id): " + options.get(0).id + "," + options.get(1).id + "," + options.get(2).id,
			"A".equals(options.get(0).id) && "B".equals(options.get(1).id) && "OFF".equals(options.get(2).id));
		check("provider, model and the switched-off flag come through", "anthropic".equals(options.get(1).provider) && "claude".equals(options.get(1).model) && options.get(1).active
			&& !options.get(2).active && "A".equals(options.get(0).label));
		check("no key anywhere in what was asked or answered", !pFake.callsOf("llm.connectors").get(0).params.toString().toLowerCase(Locale.ENGLISH).contains("key"));

		// the picker through AgentRuntime: the contract with a session, the direct read (empty here, no repository) never reached
		check("llmConnectors(session) uses the contract", AgentRuntime.llmConnectors(null, session).size() == 3);
		pFake.script.put("llm.connectors", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("ACCESS_DENIED", "no READ right");
			}
		});
		check("a refusal leaves the picker empty instead of reading Connectors' tables behind the contract's back", AgentRuntime.llmConnectors(null, session).isEmpty());
		pFake.script.clear();
	}

	// ------------------------------------------------------------------------------------------- flow operations

	private static void flowOperations(final FakeContract pFake) throws Exception
	{
		final Session session = ConnectorsContractClientTest.session();
		pFake.calls.clear();
		final ObjectNode mapping = (ObjectNode) ConnectorsContractClientTest.json("{\"connector\":\"C\",\"operation\":\"read\",\"direction\":\"INBOUND\",\"label\":\" Landing: P S T \","
			+ "\"dataset\":\"D\",\"tablePath\":\"/root/T\",\"id\":\"346\",\"updateById\":true,\"inputs\":{\"a\":\"b\"},\"rules\":[{\"sourcePath\":\"x\",\"targetPath\":\"./x\"}],"
			+ "\"children\":[{\"label\":\"kid\",\"tablePath\":\"/root/K\",\"id\":\"9\",\"parent\":\"346\"}]}");
		check("the key is the trimmed label", "Landing: P S T".equals(ContractFlows.keyOf(mapping)) && ContractFlows.keyOf((ObjectNode) ConnectorsContractClientTest.json("{\"label\":\" \"}")) == null);
		final ObjectNode flow = ContractFlows.flowOf(mapping);
		check("the flow document has no id, updateById or parent, at any level: " + flow, !flow.has("id") && !flow.has("updateById") && !flow.path("children").get(0).has("id")
			&& !flow.path("children").get(0).has("parent") && flow.has("rules") && flow.has("inputs"));
		check("and the caller's document is untouched", mapping.has("id") && mapping.path("children").get(0).has("parent"));

		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json(pParams.path("dryRun").asBoolean(false) ? "{\"mappingId\":null,\"created\":true,\"dryRun\":true}"
					: "{\"key\":\"" + pParams.path("key").asText() + "\",\"mappingId\":346,\"created\":false,\"dryRun\":false}");
			}
		});
		final JsonNode dry = ContractFlows.put(session, mapping, true);
		final JsonNode real = ContractFlows.put(session, mapping, false);
		check("a dry run of a new flow has no id, the real one has", dry.path("mappingId").isNull() && "346".equals(real.path("mappingId").asText()));
		final List<Call> puts = pFake.callsOf("flows.put");
		check("two puts under the same key, dryRun then real", puts.size() == 2 && "Landing: P S T".equals(puts.get(0).params.path("key").asText())
			&& puts.get(0).params.path("dryRun").asBoolean(false) && !puts.get(1).params.path("dryRun").asBoolean(true));
		check("the flow sent carries the label too (it equals the key, as the contract demands)", " Landing: P S T ".equals(puts.get(1).params.path("flow").path("label").asText()) || "Landing: P S T".equals(puts.get(1).params.path("flow").path("label").asText()));

		pFake.script.put("flows.get", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json("Landing: P S T".equals(pParams.path("key").asText())
					? "{\"key\":\"Landing: P S T\",\"found\":true,\"mappingId\":346,\"flow\":{\"rules\":[{\"sourcePath\":\"a\",\"targetPath\":\"./a\"}]}}" : "{\"key\":\"" + pParams.path("key").asText() + "\",\"found\":false}");
			}
		});
		check("flows.get: the id of a flow that exists", "346".equals(ContractFlows.idOf(session, "Landing: P S T")));
		check("flows.get: none", ContractFlows.idOf(session, "nothing") == null && ContractFlows.flow(session, "nothing") == null);
		check("flows.get: the document", ContractFlows.flow(session, "Landing: P S T").path("rules").size() == 1);
		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("AMBIGUOUS_KEY", "more than one root mapping is labelled");
			}
		});
		try
		{
			ContractFlows.put(session, mapping, false);
			check("an ambiguous key must be refused", false);
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			check("AMBIGUOUS_KEY", "AMBIGUOUS_KEY".equals(ex.type));
		}
		pFake.script.clear();
	}

	// --------------------------------------------------------------------------------- a landing flow, end to end

	private static LandingFlow.Table landingTable(final String pOwnedId)
	{
		final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
		columns.add(new LandingFlow.Column("CONTACT_ID", "CONTACT_ID", true));
		columns.add(new LandingFlow.Column("NAME", "NAME", false));
		return new LandingFlow.Table("Vol100kCRM", "h2", "VOL100KCRM", "PUBLIC.CONTACT", "Vol100k_Landing", "VOL100KCRM", "CONTACT", columns).forProject("Vol100k", pOwnedId);
	}

	/** A scripted Connectors MCP server for the tool way: answers connectors_mapping_upsert / delete. */
	private static McpClient toolClient(final List<String> pCalls, final List<JsonNode> pArguments)
	{
		return new McpClient(new McpClient.Transport()
		{
			@Override
			public String exchange(final String pRequest) throws McpException
			{
				try
				{
					final JsonNode message = ConnectorsContractClientTest.MAPPER.readTree(pRequest);
					final int id = message.path("id").asInt();
					final String method = message.path("method").asText();
					if (method.startsWith("notifications/"))
					{
						return "";
					}
					if ("initialize".equals(method))
					{
						return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"serverInfo\":{\"name\":\"ebx-connectors\"}}}";
					}
					final String tool = message.path("params").path("name").asText();
					pCalls.add(tool);
					pArguments.add(message.path("params").path("arguments"));
					final String body = "connectors_mapping_upsert".equals(tool) ? "{\"mapping\":\"500\",\"created\":true}" : "{\"deleted\":true}";
					return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":" + ConnectorsContractClientTest.MAPPER.writeValueAsString(body)
						+ "}],\"structuredContent\":" + body + ",\"isError\":false}}";
				}
				catch (final java.io.IOException ex)
				{
					throw new McpException(ex.getMessage(), ex);
				}
			}

			@Override
			public String describe()
			{
				return "fake tools";
			}
		});
	}

	private static void landingFlowThroughTheContract(final FakeContract pFake) throws Exception
	{
		final Session session = ConnectorsContractClientTest.session();
		pFake.calls.clear();
		final String label = "Landing: Vol100k VOL100KCRM CONTACT";
		final String[] designated = { "346" };
		pFake.script.put("flows.get", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json(designated[0] == null ? "{\"key\":\"x\",\"found\":false}" : "{\"key\":\"x\",\"found\":true,\"mappingId\":" + designated[0] + ",\"flow\":{}}");
			}
		});
		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json(pParams.path("dryRun").asBoolean(false) ? "{\"mappingId\":346,\"created\":false,\"dryRun\":true}" : "{\"mappingId\":346,\"created\":false,\"dryRun\":false}");
			}
		});
		final List<String> toolCalls = new ArrayList<String>();
		final List<JsonNode> toolArguments = new ArrayList<JsonNode>();

		// 1. the table owns 346 and the label designates 346: updated in place through the contract, no tool
		final ObjectNode detail = MAPPER.createObjectNode();
		final String id = LandingFlows.upsertFlow(session, ConnectorsContractClientTest.toolClient(toolCalls, toolArguments), ConnectorsContractClientTest.landingTable("346"), detail);
		check("the flow of the same label is updated in place: " + id, "346".equals(id));
		check("by the contract, not by the tool", toolCalls.isEmpty() && pFake.callsOf("flows.put").size() == 2 && "contract".equals(detail.path("flowVia").asText()));
		final Call real = pFake.callsOf("flows.put").get(1);
		check("the key is the label the Factory always gave it: " + real.params.path("key").asText(), label.equals(real.params.path("key").asText()));
		check("the document has no id or updateById (the key designates the mapping)", !real.params.path("flow").has("id") && !real.params.path("flow").has("updateById")
			&& "CONTACT".equals(real.params.path("flow").path("tablePath").asText().replace("/root/", "")));
		check("one flows.get to see the label designates the table's own mapping", pFake.callsOf("flows.get").size() == 1);

		// 2. a table that owns nothing: no flows.get at all, flows.put creates or finds by label
		pFake.calls.clear();
		final String created = LandingFlows.upsertFlow(session, ConnectorsContractClientTest.toolClient(toolCalls, toolArguments), ConnectorsContractClientTest.landingTable(""), MAPPER.createObjectNode());
		check("a new table's flow is put by the contract", "346".equals(created) && pFake.callsOf("flows.get").isEmpty() && toolCalls.isEmpty());

		// 3. the table owns 346 but the label designates ANOTHER mapping (or none: a renamed project): the rename in place stays on the tool
		pFake.calls.clear();
		designated[0] = null;
		final ObjectNode renamed = MAPPER.createObjectNode();
		final String viaTool = LandingFlows.upsertFlow(session, ConnectorsContractClientTest.toolClient(toolCalls, toolArguments), ConnectorsContractClientTest.landingTable("346"), renamed);
		check("the rename of the table's own mapping goes by the tool: " + toolCalls, "500".equals(viaTool) && toolCalls.contains("connectors_mapping_upsert")
			&& pFake.callsOf("flows.put").isEmpty());
		check("and the tool's document still carries id and updateById", "346".equals(toolArguments.get(0).path("mapping").path("id").asText()) && toolArguments.get(0).path("mapping").path("updateById").asBoolean(false));
		check("the detail says why", renamed.path("flowVia").asText().startsWith("tool"));

		// 4. the same table through no session / via=direct: the tool, as before the contract
		pFake.calls.clear();
		toolCalls.clear();
		toolArguments.clear();
		designated[0] = "346";
		check("no session: the tool", "500".equals(LandingFlows.upsertFlow(null, ConnectorsContractClientTest.toolClient(toolCalls, toolArguments), ConnectorsContractClientTest.landingTable("346"),
			MAPPER.createObjectNode())) && pFake.calls.isEmpty());
		System.setProperty(ConnectorsContractClient.PROPERTY_VIA, "direct");
		toolCalls.clear();
		check("via=direct: the tool, the contract is never called", "500".equals(LandingFlows.upsertFlow(session, ConnectorsContractClientTest.toolClient(toolCalls, toolArguments),
			ConnectorsContractClientTest.landingTable("346"), MAPPER.createObjectNode())) && pFake.calls.isEmpty() && toolCalls.contains("connectors_mapping_upsert"));
		System.clearProperty(ConnectorsContractClient.PROPERTY_VIA);

		// 5. Connectors refuses the flow: the same coded sentence as the tool's refusal, no fallback to the tool
		pFake.calls.clear();
		toolCalls.clear();
		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("OPERATION_FAILED", "unknown connector 'Nope'");
			}
		});
		try
		{
			LandingFlows.upsertFlow(session, ConnectorsContractClientTest.toolClient(toolCalls, toolArguments), ConnectorsContractClientTest.landingTable("346"), MAPPER.createObjectNode());
			check("a refused flow must fail the generation", false);
		}
		catch (final EngineRefusal ex)
		{
			check("coded like the tool's refusal: " + ex.code(), "engine.external.writeMapping".equals(ex.code()) && ex.getMessage().contains("unknown connector"));
			check("and the tool was not tried behind the refusal", toolCalls.isEmpty());
		}
		// 6. the contract went away between the check and the call (auto): the tool takes over for this write
		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("DISABLED", "switched off");
			}
		});
		toolCalls.clear();
		final ObjectNode lost = MAPPER.createObjectNode();
		check("DISABLED under auto: the tool takes over", "500".equals(LandingFlows.upsertFlow(session, ConnectorsContractClientTest.toolClient(toolCalls, toolArguments),
			ConnectorsContractClientTest.landingTable("346"), lost)) && toolCalls.contains("connectors_mapping_upsert") && lost.path("flowVia").asText().contains("DISABLED"));
		pFake.script.clear();
	}

	// -------------------------------------------------------------------- distribution and data product flows

	private static void distributionAndDataProductThroughTheContract(final FakeContract pFake) throws Exception
	{
		final Session session = ConnectorsContractClientTest.session();
		pFake.calls.clear();
		final String label = "Distribution: target 4 /root/Person";
		check("the label of a distribution table is its flow key", label.equals(DistributionConfigurator.label("4", "/root/Person")));
		final ObjectNode mapping = (ObjectNode) ConnectorsContractClientTest.json("{\"connector\":\"Kafka\",\"operation\":\"publish\",\"direction\":\"OUTBOUND\",\"dataset\":\"Master\","
			+ "\"tablePath\":\"/root/Person\",\"label\":\"" + label + "\",\"inputs\":{\"mergedRecords\":\"exclude\"},\"rules\":[{\"sourcePath\":\"./id\",\"targetPath\":\"id\"}]}");
		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json(pParams.path("dryRun").asBoolean(false) ? "{\"mappingId\":null,\"created\":true,\"dryRun\":true}" : "{\"mappingId\":302,\"created\":true,\"dryRun\":false}");
			}
		});
		final ObjectNode detail = MAPPER.createObjectNode();
		check("a distribution mapping is put under its label: 302", "302".equals(DistributionConfigurator.upsertThroughContract(session, mapping, "/root/Person", detail)));
		check("dry run then real, the detail says so", pFake.callsOf("flows.put").size() == 2 && detail.path("dryRunOk").asBoolean(false) && detail.path("upsertOk").asBoolean(false)
			&& label.equals(pFake.callsOf("flows.put").get(1).params.path("key").asText()));
		check("a connector changed in the document is a plain update through the contract: the flow carries the new connector", "Kafka".equals(pFake.callsOf("flows.put").get(1).params.path("flow").path("connector").asText()));

		pFake.script.put("flows.get", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json(label.equals(pParams.path("key").asText())
					? "{\"found\":true,\"mappingId\":302,\"flow\":{\"rules\":[{\"sourcePath\":\"./id\",\"targetPath\":\"id\",\"transformation\":\"\",\"policy\":\"ALWAYS\"},"
						+ "{\"sourcePath\":\"./x\",\"targetPath\":\"handAdded\",\"transformation\":\"upper\",\"policy\":\"IF_EMPTY\"},{\"sourcePath\":\"./y\"}]}}" : "{\"found\":false}");
			}
		});
		check("the stored mapping 302 is the one the label designates", DistributionConfigurator.designatedByLabel(session, "302", "4", "/root/Person")
			&& !DistributionConfigurator.designatedByLabel(session, "301", "4", "/root/Person") && !DistributionConfigurator.designatedByLabel(session, "", "4", "/root/Person")
			&& !DistributionConfigurator.designatedByLabel(session, "302", "5", "/root/Person"));
		final List<com.on.ebx.factory.distribution.DistributionMappingRules.Rule> rules = DistributionConfigurator.readExistingRules(session, null, "302", label);
		check("the rules of the flow are read through the contract (no tool client needed): " + rules.size(), rules.size() == 2 && "handAdded".equals(rules.get(1).targetPath)
			&& "upper".equals(rules.get(1).transformation) && "IF_EMPTY".equals(rules.get(1).policy) && "id".equals(rules.get(0).targetPath));

		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("OPERATION_FAILED", "unknown connector");
			}
		});
		try
		{
			DistributionConfigurator.upsertThroughContract(session, mapping, "/root/Person", MAPPER.createObjectNode());
			check("a refused mapping must fail", false);
		}
		catch (final EngineRefusal ex)
		{
			check("coded like the tool's refusal: " + ex.code(), "engine.external.writeMapping".equals(ex.code()) && ex.getMessage().contains("unknown connector"));
		}
		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("ACCESS_DENIED", "no MANAGE right");
			}
		});
		try
		{
			DistributionConfigurator.upsertThroughContract(session, mapping, "/root/Person", MAPPER.createObjectNode());
			check("a right refusal must fail", false);
		}
		catch (final EngineRefusal ex)
		{
			check("the right is named by its own code, not by the mapping's: " + ex.code(), "engine.external.connectorsContractDenied".equals(ex.code()));
		}
		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams) throws Failure
			{
				throw new Failure("DISABLED", "switched off");
			}
		});
		check("a contract gone under auto: null, the caller goes by the tool", DistributionConfigurator.upsertThroughContract(session, mapping, "/root/Person", MAPPER.createObjectNode()) == null);

		// the data product: one root mapping per table, key = its label
		pFake.calls.clear();
		final ObjectNode product = (ObjectNode) ConnectorsContractClientTest.json("{\"connector\":\"SNOW\",\"operation\":\"insert\",\"direction\":\"OUTBOUND\",\"dataspace\":\"M\",\"dataset\":\"M\","
			+ "\"tablePath\":\"/root/Company\",\"label\":\"Data product CO360 - company\",\"active\":true,\"inputs\":{\"table\":\"company\"},\"rules\":[]}");
		pFake.script.put("flows.put", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json("{\"mappingId\":77,\"created\":false,\"dryRun\":" + pParams.path("dryRun").asBoolean(false) + "}");
			}
		});
		check("a data product table is put as a flow", "77".equals(DataProductPublication.putThroughContract(session, product, "company"))
			&& "Data product CO360 - company".equals(pFake.callsOf("flows.put").get(0).params.path("key").asText()));
		pFake.script.clear();
	}

	// ------------------------------------------------------------- the LLM host allow-list through the contract

	private static void allowListStillApplies(final FakeContract pFake) throws Exception
	{
		final Session session = ConnectorsContractClientTest.session();
		final String saved = System.getProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY);
		final String[] urls = { "https://api.openai.com/v1" };
		final int[] reads = { 0 };
		final AgentRuntime.BaseUrlReader reader = new AgentRuntime.BaseUrlReader()
		{
			@Override
			public String baseUrl(final String pId)
			{
				reads[0]++;
				return urls[0];
			}
		};
		pFake.calls.clear();
		pFake.script.put("llm.ask", new Handler()
		{
			@Override
			public JsonNode answer(final JsonNode pParams)
			{
				return ConnectorsContractClientTest.json("{\"text\":\"OK\",\"model\":\"m\",\"provider\":\"p\",\"connector\":\"LLM1\",\"truncated\":false}");
			}
		});
		try
		{
			// list set, host allowed: the call is made
			System.setProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY, "api.openai.com, *.mistral.ai");
			check("an allowed host is asked", "OK".equals(AgentRuntime.askGuarded(session, "LLM1", reader, "s", "ping", 64, false, 60000).text) && reads[0] == 1
				&& pFake.callsOf("llm.ask").size() == 1);

			// list set, host refused: the same coded refusal as the direct path, and llm.ask is never called
			urls[0] = "https://evil.example.com/v1";
			pFake.calls.clear();
			try
			{
				AgentRuntime.askGuarded(session, "LLM1", reader, "s", "ping", 64, false, 60000);
				check("a host outside the allow-list must be refused", false);
			}
			catch (final EngineRefusal ex)
			{
				check("coded like the direct path: " + ex.code(), "engine.conflict.llmHostNotAllowed".equals(ex.code()) && ex.getMessage().contains("evil.example.com")
					&& ex.getMessage().contains(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY));
			}
			check("llm.ask was not called", pFake.callsOf("llm.ask").isEmpty());
			// clear text to an allowed host is refused too, as on the direct path
			System.setProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY, "model.corp.example");
			urls[0] = "http://model.corp.example/v1";
			try
			{
				AgentRuntime.requireAllowedDestination("LLM1", reader);
				check("plain HTTP to another machine must be refused", false);
			}
			catch (final EngineRefusal ex)
			{
				check("clear text: " + ex.code(), "engine.conflict.llmClearText".equals(ex.code()));
			}
			// list set, URL unreadable: fails closed
			urls[0] = null;
			try
			{
				AgentRuntime.askGuarded(session, "LLM1", reader, "s", "ping", 64, false, 60000);
				check("an unreadable URL under an allow-list must be refused", false);
			}
			catch (final EngineRefusal ex)
			{
				check("fails closed: " + ex.code(), "engine.external.llmConnector".equals(ex.code()));
			}
			check("and still no llm.ask", pFake.callsOf("llm.ask").isEmpty());

			// list unset (or blank): nothing is read or checked, the call is made whatever the host
			for (final String unset : new String[] { null, "  " })
			{
				if (unset == null)
				{
					System.clearProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY);
				}
				else
				{
					System.setProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY, unset);
				}
				urls[0] = "http://anything.example.com";
				reads[0] = 0;
				pFake.calls.clear();
				check("list unset: asked as built, no URL read", "OK".equals(AgentRuntime.askGuarded(session, "LLM1", reader, "s", "ping", 64, false, 60000).text) && reads[0] == 0
					&& pFake.callsOf("llm.ask").size() == 1);
			}
		}
		finally
		{
			if (saved == null)
			{
				System.clearProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY);
			}
			else
			{
				System.setProperty(CredentialPolicy.ALLOWED_LLM_HOSTS_PROPERTY, saved);
			}
			pFake.script.clear();
		}
	}

	// ----------------------------------------------------------------------------------------------- logged once

	private static void statusLoggedOnce(final FakeContract pFake, final MBeanServer pServer)
	{
		ConnectorsContractClient.useServerForTest(pServer); // forgets what was logged
		ConnectorsContractClient.status();
		final String first = ConnectorsContractClient.lastLoggedForTest();
		ConnectorsContractClient.status();
		ConnectorsContractClient.statusJson();
		check("the same outcome is not logged again: " + first, first != null && first.equals(ConnectorsContractClient.lastLoggedForTest()) && first.startsWith("READY|1.0.0|"));
		pFake.version = "2.0.0";
		ConnectorsContractClient.status();
		check("a new outcome is", ConnectorsContractClient.lastLoggedForTest().startsWith("INCOMPATIBLE|2.0.0|"));
		pFake.version = "1.0.0";
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
