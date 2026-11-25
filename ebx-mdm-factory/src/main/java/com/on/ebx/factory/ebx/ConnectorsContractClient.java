package com.on.ebx.factory.ebx;

import java.lang.management.ManagementFactory;
import java.util.Locale;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * Wave 11 (audit F-26): the ONE class of this module that speaks the versioned in-JVM contract {@code ebx-connectors.sibling} of
 * ebx-connectors (its {@code docs/CONTRACT.md}: platform MBean {@code com.on.ebx.connectors:type=Contract}, JSON-RPC text, the caller's
 * {@link Session} as an {@code Object}). What the contract covers - one-turn questions to a language model ({@code llm.ask}), the list of
 * language model connections ({@code llm.connectors}), the flows ({@code flows.get/put/delete/run}) - goes through here instead of
 * reading Connectors' tables, decrypting a connector's key or writing mapping rows by path; the list of what is still coupled is
 * {@code docs/CONNECTORS-COUPLING.md}.
 * <p>
 * <b>Which way a call goes</b> is the JVM property {@code ebx.factory.connectors.via}: {@code auto} (default: the contract when ebx-connectors offers a
 * compatible one, else the earlier way - the direct reads and the MCP tools), {@code contract} (the contract only: a Connectors without a compatible one
 * is a coded refusal, nothing falls back) or {@code direct} (the earlier way, the contract is never called). The check is one read of the MBean's
 * two attributes ({@link #status}, cheap, no session) done at module start and again whenever a screen asks, because Connectors may register its MBean
 * after this module starts; each distinct outcome is logged ONCE (a WARN for a problem, an INFO when the contract is found) and the current one is
 * on the administrator's {@code /status}.
 * <p>
 * A refusal of the contract is a {@link Refusal} whose {@link Refusal#type} is the {@code error.data.type} of Connectors (an unknown type counts as
 * {@code OPERATION_FAILED}, as the contract says); {@link #engineRefusal} turns it into the coded engine message a person reads in their language.
 */
public final class ConnectorsContractClient
{
	public static final String OBJECT_NAME = "com.on.ebx.connectors:type=Contract";
	/** The contract version this module was written for (CONTRACT.md "Versioning": same major, minor.patch at least this). */
	public static final String WRITTEN_FOR = "1.0.0";
	public static final String EXPECTED_NAME = "ebx-connectors.sibling";
	public static final String PROPERTY_VIA = "ebx.factory.connectors.via";

	/** The types of a {@link Refusal}: the contract's own {@code error.data.type}, plus the three this client raises itself ({@link #ABSENT}, {@link #INCOMPATIBLE}, {@link #INTERNAL}). */
	public static final String ABSENT = "ABSENT";
	public static final String INCOMPATIBLE = "INCOMPATIBLE";
	public static final String DISABLED = "DISABLED";
	public static final String SESSION_REQUIRED = "SESSION_REQUIRED";
	public static final String ACCESS_DENIED = "ACCESS_DENIED";
	public static final String NOT_FOUND = "NOT_FOUND";
	public static final String AMBIGUOUS_KEY = "AMBIGUOUS_KEY";
	public static final String CONNECTOR_UNUSABLE = "CONNECTOR_UNUSABLE";
	public static final String NO_DEFAULT_LLM = "NO_DEFAULT_LLM";
	public static final String NOT_LLM = "NOT_LLM";
	public static final String LLM_FAILED = "LLM_FAILED";
	public static final String TIMEOUT = "TIMEOUT";
	/** 1.1.0: {@code llm.chat} does not serve the connection's provider (Google). */
	public static final String NOT_SUPPORTED = "NOT_SUPPORTED";
	public static final String OPERATION_FAILED = "OPERATION_FAILED";
	public static final String INTERNAL = "INTERNAL";

	/** The probe of {@link #status} is an in-JVM call that answers at once: a few seconds is plenty. */
	private static final int PROBE_TIMEOUT_MS = 5000;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Test hook: the MBean server to talk to (null = the platform one). */
	private static volatile MBeanServer serverOverride;
	/** The last outcome logged, so that one outcome is logged once (not at every call). */
	private static volatile String lastLogged;

	private ConnectorsContractClient()
	{
	}

	// ----------------------------------------------------------------------------------------------------- the way

	/** {@code ebx.factory.connectors.via}. */
	public enum Via
	{
		AUTO, CONTRACT, DIRECT;

		/** Pure: the value of the property; anything else than the three words (blank included) is {@link #AUTO}. */
		public static Via parse(final String pValue)
		{
			final String value = pValue == null ? "" : pValue.trim().toLowerCase(Locale.ENGLISH);
			if ("contract".equals(value))
			{
				return CONTRACT;
			}
			return "direct".equals(value) ? DIRECT : AUTO;
		}
	}

	public static Via via()
	{
		return Via.parse(System.getProperty(ConnectorsContractClient.PROPERTY_VIA));
	}

	/** What the check found. */
	public enum State
	{
		/** A compatible contract is registered. */
		READY,
		/** No contract MBean: Connectors is not started, or older than the contract. */
		ABSENT,
		/** Registered, but another name or an incompatible version. */
		INCOMPATIBLE,
		/** Registered and compatible, but switched off in Connectors (-Debx.connectors.contract=false: every request answers DISABLED). */
		DISABLED,
		/** Registered, but its attributes could not be read. */
		UNREADABLE
	}

	/** The outcome of one check ({@link #status}). */
	public static final class Status
	{
		public final State state;
		public final String name;
		public final String offered;
		/** A sentence for a log or a screen when the state is not {@link State#READY}; null when it is. */
		public final String detail;

		Status(final State pState, final String pName, final String pOffered, final String pDetail)
		{
			this.state = pState;
			this.name = pName;
			this.offered = pOffered;
			this.detail = pDetail;
		}

		public boolean ready()
		{
			return this.state == State.READY;
		}
	}

	/** A refusal of the contract (or of the way to it): {@link #type} is the contract's {@code error.data.type}, or {@link #ABSENT} / {@link #INTERNAL} for this client's own. */
	public static final class Refusal extends Exception
	{
		private static final long serialVersionUID = 1L;
		public final String type;

		public Refusal(final String pType, final String pMessage)
		{
			super(pMessage);
			this.type = pType == null || pType.trim().isEmpty() ? ConnectorsContractClient.OPERATION_FAILED : pType;
		}
	}

	// --------------------------------------------------------------------------------------------------- the check

	/** Pure: the contract's compatibility rule - same major, minor.patch at least the needed one; anything but three numbers is incompatible. */
	public static boolean compatible(final String pOffered, final String pNeeded)
	{
		final int[] offered = ConnectorsContractClient.numbers(pOffered);
		final int[] needed = ConnectorsContractClient.numbers(pNeeded);
		if (offered == null || needed == null || offered[0] != needed[0])
		{
			return false;
		}
		return offered[1] > needed[1] || offered[1] == needed[1] && offered[2] >= needed[2];
	}

	private static int[] numbers(final String pVersion)
	{
		if (pVersion == null)
		{
			return null;
		}
		final String[] parts = pVersion.trim().split("\\.");
		if (parts.length != 3)
		{
			return null;
		}
		try
		{
			return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}

	private static MBeanServer server()
	{
		final MBeanServer override = ConnectorsContractClient.serverOverride;
		return override == null ? ManagementFactory.getPlatformMBeanServer() : override;
	}

	/**
	 * Reads the contract MBean's name and version, no session needed. Logs the outcome once per distinct outcome. Never throws. Cheap enough to call
	 * from a screen; it does not call {@code describe} (the version is the whole compatibility rule).
	 */
	public static Status status()
	{
		final Status status = ConnectorsContractClient.read();
		ConnectorsContractClient.logOnce(status);
		return status;
	}

	private static Status read()
	{
		try
		{
			final ObjectName name = new ObjectName(ConnectorsContractClient.OBJECT_NAME);
			final MBeanServer server = ConnectorsContractClient.server();
			if (!server.isRegistered(name))
			{
				return new Status(State.ABSENT, null, null,
					"ebx-connectors does not offer its contract (not started yet, or older than the contract): no MBean " + ConnectorsContractClient.OBJECT_NAME);
			}
			final String contractName = String.valueOf(server.getAttribute(name, "ContractName"));
			final String version = String.valueOf(server.getAttribute(name, "ContractVersion"));
			if (!ConnectorsContractClient.EXPECTED_NAME.equals(contractName))
			{
				return new Status(State.INCOMPATIBLE, contractName, version,
					"ebx-connectors offers the contract " + contractName + ", this module speaks " + ConnectorsContractClient.EXPECTED_NAME);
			}
			if (!ConnectorsContractClient.compatible(version, ConnectorsContractClient.WRITTEN_FOR))
			{
				return new Status(State.INCOMPATIBLE, contractName, version, "ebx-connectors offers contract " + version
					+ ", this module needs the same major version and at least " + ConnectorsContractClient.WRITTEN_FOR);
			}
			try
			{
				// contract.describe needs no session and is answered DISABLED when Connectors switched the contract off (-Debx.connectors.contract=false)
				ConnectorsContractClient.call(null, "contract.describe", null, ConnectorsContractClient.PROBE_TIMEOUT_MS);
			}
			catch (final Refusal ex)
			{
				if (DISABLED.equals(ex.type))
				{
					return new Status(State.DISABLED, contractName, version, "ebx-connectors switched its contract off (JVM property ebx.connectors.contract=false)");
				}
				return new Status(State.UNREADABLE, contractName, version, "the contract of ebx-connectors does not answer contract.describe: " + ex.getMessage());
			}
			return new Status(State.READY, contractName, version, null);
		}
		catch (final Throwable ex)
		{
			return new Status(State.UNREADABLE, null, null, "the contract of ebx-connectors could not be read: " + ex);
		}
	}

	private static void logOnce(final Status pStatus)
	{
		final String key = pStatus.state + "|" + pStatus.offered + "|" + ConnectorsContractClient.via();
		if (key.equals(ConnectorsContractClient.lastLogged))
		{
			return;
		}
		ConnectorsContractClient.lastLogged = key;
		final String line;
		if (pStatus.ready())
		{
			line = "[mdm-factory] ebx-connectors contract " + pStatus.name + " " + pStatus.offered + " found (written for " + ConnectorsContractClient.WRITTEN_FOR + ", "
				+ ConnectorsContractClient.PROPERTY_VIA + "=" + ConnectorsContractClient.via().name().toLowerCase(Locale.ENGLISH) + "): " + ConnectorsContractClient.wayText(pStatus);
		}
		else
		{
			line = "[mdm-factory] ebx-connectors contract not usable (" + pStatus.state + "): " + pStatus.detail + ". " + ConnectorsContractClient.wayText(pStatus);
		}
		try
		{
			if (pStatus.ready())
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
			// a logging failure must never take a screen or the start of the module down
		}
	}

	/** One sentence: which way the calls the contract covers go, given the way asked for and the state. */
	private static String wayText(final Status pStatus)
	{
		final Via via = ConnectorsContractClient.via();
		if (via == Via.DIRECT)
		{
			return "The contract is switched off for this module: its calls read Connectors directly.";
		}
		if (pStatus.ready())
		{
			return "Language model calls, the list of language model connections and the flows go through the contract.";
		}
		return via == Via.CONTRACT ? "Via=contract: those calls are refused until Connectors offers a compatible contract."
			: "Those calls fall back to reading Connectors directly (the way before the contract).";
	}

	/** {@code {via, state, contract, offered, writtenFor, detail?, active, way}}: what the administrator's {@code /status} shows. */
	public static ObjectNode statusJson()
	{
		final Status status = ConnectorsContractClient.status();
		final ObjectNode out = ConnectorsContractClient.MAPPER.createObjectNode();
		out.put("via", ConnectorsContractClient.via().name().toLowerCase(Locale.ENGLISH));
		out.put("state", status.state.name());
		if (status.name != null)
		{
			out.put("contract", status.name);
		}
		if (status.offered != null)
		{
			out.put("offered", status.offered);
		}
		out.put("writtenFor", ConnectorsContractClient.WRITTEN_FOR);
		if (status.detail != null)
		{
			out.put("detail", status.detail);
		}
		out.put("active", ConnectorsContractClient.via() != Via.DIRECT && status.ready());
		out.put("way", ConnectorsContractClient.wayText(status));
		return out;
	}

	// ------------------------------------------------------------------------------------------------ the decision

	/**
	 * Whether the calls the contract covers go through it now: {@code direct} never, {@code auto} when the contract is ready, {@code contract} when it is
	 * ready - and then a refusal when it is not (the way was demanded, nothing falls back).
	 *
	 * @throws EngineRefusal (coded {@code external.connectorsContractUnavailable}) with {@code via=contract} and no compatible contract
	 */
	public static boolean useContract() throws EngineRefusal
	{
		final Via via = ConnectorsContractClient.via();
		if (via == Via.DIRECT)
		{
			return false;
		}
		final Status status = ConnectorsContractClient.status();
		if (status.ready())
		{
			return true;
		}
		if (via == Via.CONTRACT)
		{
			throw EngineRefusal.coded("external.connectorsContractUnavailable", "ebx-connectors does not offer a compatible contract: " + status.detail + " ("
				+ ConnectorsContractClient.PROPERTY_VIA + "=contract refuses to fall back)", status.detail);
		}
		return false;
	}

	/**
	 * True when a call that failed with {@code pRefusal} may be done the earlier way: only with {@code via=auto}, and only for a refusal that says the
	 * contract itself is not there (switched off with {@code -Debx.connectors.contract=false}, gone since the check) - never for a refusal about the
	 * request (a right, an unusable connection, a model that failed), which the earlier way would only repeat.
	 */
	public static boolean mayFallBack(final Refusal pRefusal)
	{
		if (ConnectorsContractClient.via() != Via.AUTO)
		{
			return false;
		}
		final String type = pRefusal.type;
		return ABSENT.equals(type) || DISABLED.equals(type) || INCOMPATIBLE.equals(type);
	}

	// ------------------------------------------------------------------------------------------------------ calls

	/** One operation of the contract, answered as its {@code result}; a refusal of the contract (or of the way to it) is a {@link Refusal}. Waits at most {@link McpServers#readTimeoutMs()}. */
	public static JsonNode call(final Session pSession, final String pOperation, final ObjectNode pParams) throws Refusal
	{
		return ConnectorsContractClient.call(pSession, pOperation, pParams, McpServers.readTimeoutMs());
	}

	/** {@link #call(Session, String, ObjectNode)} under its own timeout (the contract's own {@code timeoutMs}, for {@code llm.ask}, should be a little shorter than this one). */
	public static JsonNode call(final Session pSession, final String pOperation, final ObjectNode pParams, final int pTimeoutMs) throws Refusal
	{
		final ObjectNode request = ConnectorsContractClient.MAPPER.createObjectNode();
		request.put("jsonrpc", "2.0");
		request.put("id", 1);
		request.put("method", pOperation);
		request.set("params", pParams == null ? ConnectorsContractClient.MAPPER.createObjectNode() : pParams);
		final String text;
		try
		{
			text = ConnectorsBridge.exchange(ConnectorsContractClient.serverOverride, ConnectorsContractClient.OBJECT_NAME, pSession, request.toString(), pTimeoutMs);
		}
		catch (final McpException ex)
		{
			final String message = ex.getMessage() == null ? String.valueOf(ex) : ex.getMessage();
			throw new Refusal(message.contains("not registered") ? ABSENT : INTERNAL, message);
		}
		final JsonNode answer;
		try
		{
			answer = ConnectorsContractClient.MAPPER.readTree(text == null ? "" : text);
		}
		catch (final java.io.IOException ex)
		{
			// X3-08: a document beyond a Jackson read bound (MDMF-JSON-1) is told from a malformed one (MDMF-JSON-2); neither is read as "nothing"
			throw new Refusal(INTERNAL, com.on.ebx.factory.core.JsonLimits.refusal(ex, "the answer of the ebx-connectors contract"));
		}
		catch (final Exception ex)
		{
			throw new Refusal(INTERNAL, "the contract of ebx-connectors answered text that is not JSON: " + ex.getMessage());
		}
		if (answer == null || answer.isMissingNode() || answer.isNull())
		{
			throw new Refusal(INTERNAL, "the contract of ebx-connectors answered nothing");
		}
		if (answer.has("error"))
		{
			final JsonNode error = answer.path("error");
			throw new Refusal(error.path("data").path("type").asText(OPERATION_FAILED), error.path("message").asText("the contract refused " + pOperation));
		}
		return answer.path("result");
	}

	// ------------------------------------------------------------------------------------- the agent's chat

	/**
	 * Wave 12: the way {@code ContractLlmChat} (the agent library) reaches the contract: through {@link ConnectorsBridge#exchange}, so a Connectors that never
	 * answers can pin only the bridge's few threads and a turn waits at most {@code pOuterTimeoutMs} (a little more than the model's own {@code timeoutMs}), and so a
	 * test's private MBean server is honoured. An unregistered MBean is reported the way the library expects ({@code InstanceNotFoundException}: refusal type {@code ABSENT}).
	 */
	static com.on.ebx.agent.ContractLlmChat.Transport chatTransport(final int pOuterTimeoutMs)
	{
		return new com.on.ebx.agent.ContractLlmChat.Transport()
		{
			@Override
			public String handle(final Object pSession, final String pMessage) throws Exception
			{
				try
				{
					return ConnectorsBridge.exchange(ConnectorsContractClient.serverOverride, ConnectorsContractClient.OBJECT_NAME, (Session) pSession, pMessage, pOuterTimeoutMs);
				}
				catch (final McpException ex)
				{
					if (ex.getMessage() != null && ex.getMessage().contains("not registered"))
					{
						throw new javax.management.InstanceNotFoundException(ConnectorsContractClient.OBJECT_NAME);
					}
					throw ex;
				}
			}
		};
	}

	// ------------------------------------------------------------------------------------------- engine messages

	/**
	 * The coded engine refusal for {@code pRefusal} of {@code pOperation}: the English text carries the contract's own message (for a log, a REST
	 * answer, the "Details" fold), the bundle sentence says what a person can do. {@code pWhat} names the thing asked about in the sentence of a
	 * flow that is ambiguous (the flow key; may be blank).
	 */
	public static EngineRefusal engineRefusal(final Refusal pRefusal, final String pOperation, final String pWhat)
	{
		final String what = pWhat == null || pWhat.trim().isEmpty() ? pOperation : pWhat.trim();
		final String english = "Connectors contract " + pOperation + " refused (" + pRefusal.type + "): " + pRefusal.getMessage();
		final String type = pRefusal.type;
		if (ABSENT.equals(type) || INCOMPATIBLE.equals(type) || DISABLED.equals(type))
		{
			return EngineRefusal.coded("external.connectorsContractUnavailable", english, pRefusal.getMessage());
		}
		if (ACCESS_DENIED.equals(type) || SESSION_REQUIRED.equals(type))
		{
			return EngineRefusal.coded("external.connectorsContractDenied", english, pOperation);
		}
		if (NO_DEFAULT_LLM.equals(type) || CONNECTOR_UNUSABLE.equals(type) || NOT_LLM.equals(type) || NOT_FOUND.equals(type) && pOperation.startsWith("llm.")
			|| NOT_SUPPORTED.equals(type) && pOperation.startsWith("llm."))
		{
			return EngineRefusal.coded("external.connectorsLlmUnusable", english, pRefusal.getMessage());
		}
		if (LLM_FAILED.equals(type) || TIMEOUT.equals(type))
		{
			return EngineRefusal.coded("external.connectorsLlmFailed", english, pRefusal.getMessage());
		}
		if (AMBIGUOUS_KEY.equals(type))
		{
			return EngineRefusal.coded("conflict.connectorsFlowAmbiguous", english, what);
		}
		return EngineRefusal.coded("external.connectorsContractFailed", english, pOperation, pRefusal.getMessage());
	}

	// ----------------------------------------------------------------------------------------------- test support

	/** Test hook: talk to this MBean server (null = the platform one) and forget what was logged. */
	static void useServerForTest(final MBeanServer pServer)
	{
		ConnectorsContractClient.serverOverride = pServer;
		ConnectorsContractClient.lastLogged = null;
	}

	/** Test hook: the last outcome logged ({@code state|version|via}), null before any. */
	static String lastLoggedForTest()
	{
		return ConnectorsContractClient.lastLogged;
	}
}
