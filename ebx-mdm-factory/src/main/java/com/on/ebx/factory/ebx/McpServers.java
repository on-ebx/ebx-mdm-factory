package com.on.ebx.factory.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.security.PasswordTypeEncryption;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * The MCP servers of this EBX instance, reached with the one agent account of the factory settings:
 * the base URL, the user and the password are entered once, the end points are derived here. Every call
 * runs as that account, so what it may do is what the factory may do.
 * <p>
 * Two checks live here, both built from the same per-server step ({@link #testOne}):
 * <ul>
 * <li>{@link #test} - the "Connexion" screen and {@code GET /connection/test}: every required server, then
 * every optional one.</li>
 * <li>{@link #preflight} - the head of every REST route that acts on MCP: only the servers that route needs,
 * before any side effect (a dataspace created in Java, a model half-built), so a disabled server or a
 * revoked account is reported as such instead of surfacing as a failed pipeline step.</li>
 * </ul>
 * A step is: {@code initialize}, {@code tools/list}, then one cheap read-only <b>probe</b> call
 * ({@link #PROBES}). The tool list alone proves the server is enabled for the account; the probe proves the
 * account may actually call a tool of it (EBX honours the account's permissions per call, and a full tool
 * list does not rule out a refusal on the first call). The probe is a read: it proves nothing about write
 * rights (publishing a model, activating matching) - those still fail at the step that needs them.
 */
public final class McpServers
{
	/** Servers of EBX itself, under {@code <base>/ebx-assistant/mcp/v1/<name>}. */
	public static final String DATA_MODEL = "data-model";
	/**
	 * The Match and Merge server, by its logical name (the key of {@link #PROBES}, of the connection test lines and of every
	 * caller). Its path segment is resolved by {@link #matchMergeSegment}: the add-on registers it as {@code match-merge-admin}
	 * since the ebx-addons.jar of 2026-10-21 (RC, kernel.log), as {@code match-merge} before.
	 */
	public static final String MATCH_MERGE = "match-merge";
	/** The path segments the Match and Merge server has been registered under, the current one first. */
	static final String[] MATCH_MERGE_SEGMENTS = { "match-merge-admin", "match-merge" };
	/** A JVM property naming the segment to use, which skips the detection (for an instance with another name). */
	static final String MATCH_MERGE_SEGMENT_PROPERTY = "ebx.factory.matchMergeServer";
	/** The segment detected by {@link #detectMatchMerge}, null until a call succeeded; reset by a Tomcat restart only. */
	private static volatile String matchMergeDetected;
	public static final String DATA_ACCESS = "data-access";
	/**
	 * The scripting server - registered path segment is {@code scripting}, NOT {@code ebx-scripting}
	 * (CLAUDE.md &sect;1: "a server 'not found' is a path-segment problem", measured 2026-09-21). Optional
	 * (see {@link #OPTIONAL}): the capability-suggestion pass degrades cleanly (an empty script inventory,
	 * with a note why) when it is missing or not enabled for the agent account.
	 */
	public static final String SCRIPTING = "scripting";
	/**
	 * The workflow modeling server ({@code <base>/ebx-assistant/mcp/v1/workflow-modeling}; the {@code ebx-workflow-*} names answer "not found" on 6.3.0-dev,
	 * CLAUDE.md section 1). Optional (lot C1-D): the generation of a domain skips its workflow with a message when the server is not reachable.
	 */
	public static final String WORKFLOW_MODELING = "workflow-modeling";
	/** The endpoint of the ebx-connectors module, {@code <base>/ebx-connectors/rest/mcp}. */
	public static final String CONNECTORS = "ebx-connectors";

	/**
	 * What the factory needs, in the order the connection test reports them. {@link #DATA_ACCESS} is not in it: no
	 * class of the factory calls it (it reads its own tables through the Java API), and a server nobody calls must
	 * not block a step when it is disabled.
	 */
	public static final String[] REQUIRED = { DATA_MODEL, MATCH_MERGE, CONNECTORS };
	/**
	 * Servers the factory uses when reachable but degrades cleanly without (capability-suggestion inventories
	 * only, today): reported by {@link #test} after {@link #REQUIRED}, never counted against {@code ready}.
	 */
	public static final String[] OPTIONAL = { SCRIPTING, WORKFLOW_MODELING };

	/** A read-only tool call, cheap on every instance, that proves the agent account may call the server. */
	public static final class Probe
	{
		public final String tool;
		public final ObjectNode arguments;

		Probe(final String pTool, final ObjectNode pArguments)
		{
			this.tool = pTool;
			this.arguments = pArguments;
		}
	}

	/**
	 * One probe per known server, deliberately CONSTANT-TIME - a probe must prove the account may call a tool of
	 * the server, never take as long as the server's own busiest read.
	 * <p>
	 * lot R14 (2026-09-28, docs/MATCHING.md dated entry, live evidence, RC, 2026-09-28 10:46/10:49): the
	 * {@link #DATA_MODEL} probe used to be {@code list_data_models}, which enumerates every DMA model - on an
	 * instance where that took 6-79s, {@code POST /projects/{code}/matching/configure} answered a bare 503 ("MCP
	 * server(s) not usable ... SocketTimeoutException: Read timed out") at 5s, even though the server and account
	 * were perfectly healthy. {@link #DATA_MODEL}'s probe is now {@code data_model_guide} - static playbook text,
	 * no repository read at all, no argument, genuinely O(1) whatever the instance holds - while still proving the
	 * account may call a tool of the server at all (a disabled server or a revoked account fails it exactly the
	 * same way {@code list_data_models} would have). The other probes were already constant-time and are
	 * unchanged: {@code matching_config_schema} (static, but proves the add-on answers as this account),
	 * {@code list_dataspaces} limited to one row, {@code connectors_list}, {@code list_scripts} on the IDE root
	 * limited to one row. Tool names read off the servers' own {@code tools/list} on 6.3.0-dev, 2026-09-23.
	 */
	public static final Map<String, Probe> PROBES;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int CONNECT_TIMEOUT_MS = 5000;
	/** Building, compiling or publishing a data model is one call. */
	private static final int READ_TIMEOUT_MS = 300000;
	/** The read timeout of an ordinary call - what the in-JVM bridge is held to as well. */
	static int readTimeoutMs()
	{
		return McpServers.READ_TIMEOUT_MS;
	}

	/** A probe is a one-row read: a server that takes longer than this to answer it is not healthy. */
	private static final int PROBE_READ_TIMEOUT_MS = 30000;
	/**
	 * Read timeout for the ONE retry {@link #probe} allows itself on a probe call that timed out (volume-test
	 * bug, project PersVol, 2026-09-24): a server can still be genuinely warming up a few minutes after a
	 * Tomcat restart (first EBX Script class load, first Lucene index open, a DMA model's schema not yet
	 * compiled...) and answer a first read past {@link #PROBE_READ_TIMEOUT_MS} even though it is about to
	 * become healthy - the SAME call succeeded a minute later with no other change. Generous on purpose (a
	 * genuinely dead/misconfigured server still fails, just a little slower to report it) since this path is
	 * only reached once, only after a real {@code SocketTimeoutException} (never for a connection refused or
	 * an explicit HTTP 401/403 - see {@link #isReadTimeout}).
	 */
	private static final int PROBE_RETRY_READ_TIMEOUT_MS = 90000;
	/**
	 * lot R12 (2026-09-27, docs/MATCHING.md &sect;41, live evidence D): {@link #preflight} runs synchronously at
	 * the head of a REST route, BEFORE any side effect (a dataspace created in Java, a model half-built) - it must
	 * fail fast and explicitly on a slow or hung server rather than block the calling request thread for up to
	 * {@link #PROBE_READ_TIMEOUT_MS} + {@link #PROBE_RETRY_READ_TIMEOUT_MS} per server, the {@link #test}/
	 * connection-test screen's own generous "still warming up after a restart" allowance (volume-test lot,
	 * 2026-09-24 - appropriate for a person watching a connection-test screen, never for a route already
	 * mid-request). Measured live: a thread dump caught {@code POST /projects/{code}/matching/configure} stuck
	 * ~79s inside exactly that retry. Read once via {@link #preflightProbeTimeoutMs()} (not a {@code static
	 * final}) so a test can override the system property at runtime; the property name doubles as the
	 * "configurable" knob the task brief asks for, for an instance that genuinely needs more.
	 */
	private static final String PREFLIGHT_TIMEOUT_PROPERTY = "ebx.factory.preflightTimeoutMs";
	private static final int DEFAULT_PREFLIGHT_PROBE_TIMEOUT_MS = 5000;

	static
	{
		final Map<String, Probe> probes = new LinkedHashMap<String, Probe>();
		probes.put(McpServers.DATA_MODEL, new Probe("data_model_guide", McpServers.MAPPER.createObjectNode()));
		probes.put(McpServers.MATCH_MERGE, new Probe("matching_config_schema", McpServers.MAPPER.createObjectNode()));
		probes.put(McpServers.DATA_ACCESS, new Probe("list_dataspaces", McpServers.MAPPER.createObjectNode().put("limit", 1)));
		probes.put(McpServers.CONNECTORS, new Probe("connectors_list", McpServers.MAPPER.createObjectNode()));
		probes.put(McpServers.SCRIPTING, new Probe("list_scripts", McpServers.MAPPER.createObjectNode().put("folderIdePath", "").put("limit", 1)));
		// static text of the server, constant-time like the other probes
		probes.put(McpServers.WORKFLOW_MODELING, new Probe("workflow_model_schema", McpServers.MAPPER.createObjectNode()));
		PROBES = Collections.unmodifiableMap(probes);
	}

	private McpServers()
	{
	}

	/**
	 * {@link #preflight}'s own connect/read timeout, in milliseconds - see {@link #PREFLIGHT_TIMEOUT_PROPERTY}'s
	 * own Javadoc. Read fresh on every call (never cached in a {@code static final}) so
	 * {@code -D}{@value #PREFLIGHT_TIMEOUT_PROPERTY} can be set at runtime, including by a test. Package-visible
	 * and pure for {@code McpServersTest}.
	 */
	static int preflightProbeTimeoutMs()
	{
		return Integer.getInteger(McpServers.PREFLIGHT_TIMEOUT_PROPERTY, McpServers.DEFAULT_PREFLIGHT_PROBE_TIMEOUT_MS).intValue();
	}

	/** A client of one server, as the agent account. Not shared between threads. Prefer {@link #client(Session, Adaptation, String)}. */
	public static McpClient client(final Adaptation pFactory, final String pServer) throws OperationException
	{
		return McpServers.client(pFactory, pServer, McpServers.READ_TIMEOUT_MS);
	}

	/**
	 * A client of one server for {@code pSession} (lot 3, 2026-09-24): ebx-connectors through its in-JVM bridge
	 * ({@link #connectors}); EBX's own servers (data-model, match-merge, scripting) over HTTP to this node with an
	 * {@code EBXUID} cookie minted for the session's user ({@link SessionToken}) - no password, the call runs as that
	 * user. Falls back to the agent account when there is no session or the token cannot be minted. Not shared
	 * between threads.
	 */
	public static McpClient client(final Session pSession, final Adaptation pFactory, final String pServer) throws OperationException
	{
		return McpServers.client(pSession, pFactory, pServer, McpServers.READ_TIMEOUT_MS);
	}

	/**
	 * How {@link #client(Session, Adaptation, String)} reaches {@code pServer} for {@code pSession}: {@code in-jvm}
	 * (ebx-connectors' bridge), {@code session} (a token minted for the session's user) or {@code agent} (the stored
	 * account). Shown on the connection test.
	 */
	public static String mode(final Session pSession, final Adaptation pFactory, final String pServer)
	{
		if (McpServers.CONNECTORS.equals(pServer))
		{
			return "in-jvm".equals(McpServers.connectorsTransport(pSession)) ? "in-jvm" : "agent";
		}
		return SessionToken.cookie(pSession, pFactory == null ? null : pFactory.getHome().getRepository()) == null ? "agent" : "session";
	}

	/**
	 * A client of the ebx-connectors tools for {@code pSession}: through the in-JVM bridge ({@link ConnectorsBridge},
	 * as {@code pSession}, no HTTP, no password) when ebx-connectors has registered it and a session is at hand;
	 * otherwise over HTTP with the agent account, as {@link #client} always did.
	 */
	public static McpClient connectors(final Session pSession, final Adaptation pFactory) throws OperationException
	{
		return McpServers.client(pSession, pFactory, McpServers.CONNECTORS, McpServers.READ_TIMEOUT_MS);
	}

	/**
	 * {@link #connectors(Session, Adaptation)} under its own read timeout - for a call whose duration grows with the rows it handles (a
	 * distribution send: {@code DistributionBatches.sendTimeoutMs}). Both routes honour it: the in-JVM bridge waits that long and the HTTP route
	 * reads that long.
	 */
	public static McpClient connectors(final Session pSession, final Adaptation pFactory, final int pReadTimeoutMs) throws OperationException
	{
		return McpServers.client(pSession, pFactory, McpServers.CONNECTORS, pReadTimeoutMs);
	}

	/** {@code in-jvm} or {@code http}: how {@link #connectors} reaches ebx-connectors for {@code pSession}, recorded in the step details. */
	public static String connectorsTransport(final Session pSession)
	{
		return pSession != null && ConnectorsBridge.available() ? "in-jvm" : "http";
	}

	/** {@link #client(Session, Adaptation, String)} with its own read timeout - for a call made while a screen renders ({@code LibraryDocs}), which must never wait minutes. */
	public static McpClient client(final Session pSession, final Adaptation pFactory, final String pServer, final int pReadTimeoutMs) throws OperationException
	{
		if (McpServers.CONNECTORS.equals(pServer))
		{
			if ("in-jvm".equals(McpServers.connectorsTransport(pSession)))
			{
				return ConnectorsBridge.client(pSession, pReadTimeoutMs);
			}
			CredentialPolicy.checkFallback(pFactory, pServer, pSession == null ? "no session" : "the ebx-connectors in-JVM bridge is not registered");
			return McpServers.client(pFactory, pServer, pReadTimeoutMs);
		}
		final String cookie = SessionToken.cookie(pSession, pFactory.getHome().getRepository());
		if (cookie == null)
		{
			CredentialPolicy.checkFallback(pFactory, pServer, pSession == null ? "no session" : "no session token could be minted for " + pSession.getUserReference().getUserId());
			return McpServers.client(pFactory, pServer, pReadTimeoutMs);
		}
		final String baseUrl = LocalEndpoint.resolve(pFactory).baseUrl;
		CredentialPolicy.requireOwnNode(baseUrl);
		final java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
		headers.put("Cookie", cookie);
		if (McpServers.MATCH_MERGE.equals(pServer))
		{
			McpServers.detectMatchMerge(baseUrl, headers);
		}
		return new McpClient(McpServers.url(baseUrl, pServer), headers, McpServers.CONNECT_TIMEOUT_MS, pReadTimeoutMs);
	}

	/**
	 * The request headers that authenticate a plain HTTP call to this node's own EBX webapps (not an MCP endpoint) the way {@link #client(Session,
	 * Adaptation, String, int)} does: the session user's {@code EBXUID} cookie, else the agent account's Basic header. Only ever for this node
	 * ({@link CredentialPolicy#requireOwnNode}). Used by {@link ScriptValidateRoute}.
	 */
	static java.util.Map<String, String> authHeaders(final Session pSession, final Adaptation pFactory) throws OperationException
	{
		final java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
		CredentialPolicy.requireOwnNode(LocalEndpoint.resolve(pFactory).baseUrl);
		final String cookie = SessionToken.cookie(pSession, pFactory.getHome().getRepository());
		if (cookie != null)
		{
			headers.put("Cookie", cookie);
			return headers;
		}
		CredentialPolicy.checkFallback(pFactory, McpServers.SCRIPTING, pSession == null ? "no session" : "no session token could be minted");
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing");
		}
		final String user = settings.getString(FactoryPaths.Settings.AGENT_USER);
		McpServers.requireReadableSecret(settings.getString(FactoryPaths.Settings.AGENT_PASSWORD));
		final String password = McpServers.secret(settings.getString(FactoryPaths.Settings.AGENT_PASSWORD));
		if (user == null || user.trim().isEmpty() || password == null || password.isEmpty())
		{
			throw EngineRefusal.coded("missing.agentAccount", "MDM Factory settings: the agent account is not set");
		}
		headers.putAll(McpClient.basicHeaders(user.trim(), password));
		return headers;
	}

	private static McpClient client(final Adaptation pFactory, final String pServer, final int pReadTimeoutMs) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing");
		}
		final String user = settings.getString(FactoryPaths.Settings.AGENT_USER);
		McpServers.requireReadableSecret(settings.getString(FactoryPaths.Settings.AGENT_PASSWORD));
		final String password = McpServers.secret(settings.getString(FactoryPaths.Settings.AGENT_PASSWORD));
		if (user == null || user.trim().isEmpty() || password == null || password.isEmpty())
		{
			throw EngineRefusal.coded("missing.agentAccount", "MDM Factory settings: the agent account is not set (user and password, entered once for the whole process)");
		}
		final String baseUrl = LocalEndpoint.resolve(pFactory).baseUrl;
		CredentialPolicy.requireOwnNode(baseUrl);
		final java.util.Map<String, String> headers = McpClient.basicHeaders(user.trim(), password);
		if (McpServers.MATCH_MERGE.equals(pServer))
		{
			McpServers.detectMatchMerge(baseUrl, headers);
		}
		return new McpClient(McpServers.url(baseUrl, pServer), headers, McpServers.CONNECT_TIMEOUT_MS, pReadTimeoutMs);
	}

	/** The end point of one server under a base URL already resolved by {@link LocalEndpoint}. */
	static String url(final String pBaseUrl, final String pServer)
	{
		final String base = LocalEndpoint.trimSlashes(pBaseUrl);
		if (McpServers.CONNECTORS.equals(pServer))
		{
			return base + "/ebx-connectors/rest/mcp";
		}
		return base + "/ebx-assistant/mcp/v1/" + (McpServers.MATCH_MERGE.equals(pServer) ? McpServers.matchMergeSegment() : pServer);
	}

	/** The property when set, else the segment detected so far, else the current name ({@link #MATCH_MERGE_SEGMENTS}). */
	static String matchMergeSegment()
	{
		final String forced = System.getProperty(McpServers.MATCH_MERGE_SEGMENT_PROPERTY);
		if (forced != null && !forced.trim().isEmpty())
		{
			return forced.trim();
		}
		final String detected = McpServers.matchMergeDetected;
		return detected != null ? detected : McpServers.MATCH_MERGE_SEGMENTS[0];
	}

	/**
	 * Before the first Match and Merge client of this JVM: tries each of {@link #MATCH_MERGE_SEGMENTS} with {@code initialize}
	 * and keeps the first that is not "no server at this path" (HTTP 400 or 404). Does nothing when the property is set or a
	 * segment is known. Any other failure (credentials, timeout) is left to the real call, which reports it.
	 */
	private static void detectMatchMerge(final String pBaseUrl, final java.util.Map<String, String> pHeaders)
	{
		if (McpServers.matchMergeDetected != null || System.getProperty(McpServers.MATCH_MERGE_SEGMENT_PROPERTY) != null)
		{
			return;
		}
		final String base = LocalEndpoint.trimSlashes(pBaseUrl);
		for (final String segment : McpServers.MATCH_MERGE_SEGMENTS)
		{
			try
			{
				new McpClient(base + "/ebx-assistant/mcp/v1/" + segment, pHeaders, McpServers.CONNECT_TIMEOUT_MS, McpServers.CONNECT_TIMEOUT_MS).initialize();
				McpServers.matchMergeDetected = segment;
				return;
			}
			catch (final McpException ex)
			{
				if (ex.getCode() != 400 && ex.getCode() != 404)
				{
					return;
				}
			}
		}
	}

	/**
	 * One line per required server, then per optional server ({@link #OPTIONAL}): reachable, account
	 * accepted, number of tools, probe call answered. EBX answers an empty tool list, without any error, for a
	 * server that is not enabled in Administration &gt; MCP servers: that is reported as such, not as a
	 * success. An optional server's own {@code ok}/{@code problem} is still reported (so a person can see why
	 * the capability inventory it feeds is empty) but never lowers {@code ready} - it is marked
	 * {@code optional: true}.
	 */
	public static ObjectNode test(final Adaptation pFactory)
	{
		return McpServers.test(null, pFactory);
	}

	/**
	 * {@link #test} with the caller's session: every server is tested the way the factory reaches it
	 * ({@link #client(Session, Adaptation, String)}: ebx-connectors through its in-JVM bridge, EBX's own servers with
	 * a token minted for the session's user, the agent account only as a fallback) - each line's {@code transport}
	 * says which ({@link #mode}).
	 */
	public static ObjectNode test(final Session pSession, final Adaptation pFactory)
	{
		final ObjectNode out = McpServers.MAPPER.createObjectNode();
		McpServers.putEndpoint(pFactory, out);
		out.put("lineageNamespace", LineageNamespace.resolve(pFactory));
		out.put("agentFallback", CredentialPolicy.fallbackAllowed(pFactory) ? CredentialPolicy.ALLOWED : CredentialPolicy.FORBIDDEN);
		final ArrayNode servers = out.putArray("servers");
		boolean ready = true;
		for (final String server : McpServers.REQUIRED)
		{
			ready &= McpServers.testOne(pSession, pFactory, server, false, servers);
		}
		for (final String server : McpServers.OPTIONAL)
		{
			McpServers.testOne(pSession, pFactory, server, true, servers);
		}
		out.put("ready", ready);
		// the language model connector: reported, never counted in ready (the factory runs on its rules without one)
		out.set("llm", AgentRuntime.testLlm(pSession, pFactory));
		// wave 11 (audit F-26): which way the calls Connectors' contract covers go, and whether it is found
		final ObjectNode contract = ConnectorsContractClient.statusJson();
		// wave 12: the way the agent loop reaches the model (llm.chat of the contract or direct) and the check of the connection's maxTokens
		contract.set("agent", AgentRoute.statusJson(pSession, AgentRuntime.chosenConnection(pFactory)));
		out.set("connectorsContract", contract);
		return out;
	}

	/** {@code endpoint: {baseUrl, source, detail}}, or {@code endpoint: {problem}} when this node's address is unknown. */
	private static void putEndpoint(final Adaptation pFactory, final ObjectNode pOut)
	{
		final ObjectNode endpoint = pOut.putObject("endpoint");
		try
		{
			final LocalEndpoint.Resolved resolved = LocalEndpoint.resolve(pFactory);
			endpoint.put("baseUrl", resolved.baseUrl);
			endpoint.put("source", resolved.source.name());
			endpoint.put("detail", resolved.detail);
		}
		catch (final OperationException ex)
		{
			endpoint.put("problem", ex.getMessage());
		}
	}

	/**
	 * The same step as {@link #test}, on the named servers only, all treated as required: {@code ready} is
	 * false as soon as one of them fails, and {@code summary} then names each failing server with its
	 * problem, ready to be shown as-is. Called at the head of a route BEFORE it does anything, so it costs
	 * three short HTTP calls per server and nothing else.
	 */
	public static ObjectNode preflight(final Adaptation pFactory, final String... pServers)
	{
		return McpServers.preflight(null, pFactory, pServers);
	}

	/**
	 * {@link #preflight} with the caller's session, for a step that reaches ebx-connectors through {@link
	 * #connectors}.
	 * <p>
	 * lot R12 (2026-09-27, {@link #PREFLIGHT_TIMEOUT_PROPERTY}'s own Javadoc): the FIRST attempt at each server's
	 * probe uses {@link #preflightProbeTimeoutMs()} (5s by default), never the generous {@link
	 * #PROBE_READ_TIMEOUT_MS}/{@link #PROBE_RETRY_READ_TIMEOUT_MS} {@link #test} affords a person watching the
	 * connection-test screen - a route already mid-request needs a fast, explicit answer on a server that is
	 * genuinely unreachable, unauthorized, or missing.
	 * <p>
	 * lot R14 (2026-09-28, docs/MATCHING.md dated entry): that first, tight timeout is no longer the LAST word,
	 * though - {@link #probe} now distinguishes "unreachable/unauthorized" (a connection refused, an explicit
	 * HTTP 401/403/404, a malformed answer: never retried, refused at once, exactly as before) from "merely slow"
	 * (a genuine {@code SocketTimeoutException} on a probe this class chose specifically to be O(1) - {@link
	 * #PROBES}' own Javadoc): on that second kind ONLY, one retry with the SAME generous budget {@link #test}
	 * itself uses ({@link #PROBE_RETRY_READ_TIMEOUT_MS}) is allowed, restricted to exactly the servers this
	 * preflight call was asked to check (never every server on the instance) - if THAT answers, the server is
	 * reported {@code ok: true} with {@code probe.slowProbe: true} and the route proceeds; only a server that is
	 * still unusable after the one retry, or that failed for a non-timeout reason, blocks the route with the usual
	 * 503. This fixes a real regression the tight-timeout-only design introduced: {@link #DATA_MODEL}'s OLD probe
	 * ({@code list_data_models}, since replaced - see {@link #PROBES}) legitimately took 6-79s on one instance,
	 * and a hard 5s refusal made every {@code configure} call fail even though the server and account were both
	 * healthy - the wizard's own job path (no preflight) configured the very same project in 15s. Each line's own
	 * {@code elapsedMs} (set by {@link #testOne} regardless of outcome) is the per-server probe duration,
	 * including the retry when one happened.
	 */
	public static ObjectNode preflight(final Session pSession, final Adaptation pFactory, final String... pServers)
	{
		final ObjectNode out = McpServers.MAPPER.createObjectNode();
		final ArrayNode servers = out.putArray("servers");
		final int probeTimeoutMs = McpServers.preflightProbeTimeoutMs();
		boolean ready = true;
		for (final String server : pServers)
		{
			ready &= McpServers.testOne(pSession, pFactory, server, false, servers, probeTimeoutMs, true);
		}
		out.put("ready", ready);
		if (!ready)
		{
			out.put("summary", McpServers.summary(servers));
		}
		return out;
	}

	/**
	 * "MCP server(s) not usable: data-model: The agent account was refused (HTTP 401)." - one clause per
	 * failing line of a {@link #test}/{@link #preflight} answer. Package-private and pure for the test.
	 */
	static String summary(final JsonNode pServers)
	{
		final StringBuilder sb = new StringBuilder("MCP server(s) not usable before the step ran: ");
		boolean first = true;
		for (final JsonNode line : pServers)
		{
			if (line.path("ok").asBoolean(false))
			{
				continue;
			}
			if (!first)
			{
				sb.append(" ");
			}
			first = false;
			sb.append(line.path("server").asText()).append(": ").append(line.path("problem").asText("unknown problem"));
		}
		return sb.toString();
	}

	/** {@link #test}'s own call shape: the generous {@link #PROBE_READ_TIMEOUT_MS} timeout, retry-on-timeout allowed. */
	private static boolean testOne(final Session pSession, final Adaptation pFactory, final String pServer, final boolean pOptional, final ArrayNode pServers)
	{
		return McpServers.testOne(pSession, pFactory, pServer, pOptional, pServers, McpServers.PROBE_READ_TIMEOUT_MS, true);
	}

	/**
	 * {@code pProbeReadTimeoutMs}/{@code pAllowRetry}: {@link #test} passes the generous {@link
	 * #PROBE_READ_TIMEOUT_MS} with retry allowed (a person watching the connection-test screen can afford the
	 * wait); {@link #preflight} passes {@link #preflightProbeTimeoutMs()} (tight) - lot R14 (2026-09-28): ALSO
	 * with retry allowed, unlike lot R12's original {@code false} here, because a retry is now only ever spent on
	 * a genuine timeout ({@link #isReadTimeout}), never on an outright refusal, and a successful retry is reported
	 * as {@code slowProbe}, not silently treated the same as a fast answer - see {@link #preflight}'s own dated
	 * Javadoc for why the R12 fail-fast-always design regressed on a legitimately slow-but-healthy server.
	 * {@code line}'s own {@code elapsedMs} is always set, whichever path is taken - the per-server probe duration
	 * the task brief asks {@link #preflight} to record.
	 */
	private static boolean testOne(final Session pSession, final Adaptation pFactory, final String pServer, final boolean pOptional, final ArrayNode pServers,
		final int pProbeReadTimeoutMs, final boolean pAllowRetry)
	{
		final ObjectNode line = pServers.addObject();
		line.put("server", pServer);
		line.put("optional", pOptional);
		line.put("transport", McpServers.mode(pSession, pFactory, pServer));
		final long started = System.nanoTime();
		try
		{
			final McpClient client = McpServers.client(pSession, pFactory, pServer, pProbeReadTimeoutMs);
			final JsonNode info = client.initialize().path("serverInfo");
			final List<McpClient.Tool> tools = client.listTools();
			line.put("name", info.path("name").asText(pServer));
			line.put("tools", tools.size());
			if (tools.isEmpty())
			{
				line.put("ok", false);
				line.put("problem", "The server answers but offers no tool: it is not enabled in Administration > MCP servers, or not for this account.");
			}
			else
			{
				McpServers.probe(pSession, pFactory, client, pServer, tools, line, pAllowRetry);
			}
		}
		catch (final OperationException ex)
		{
			line.put("ok", false);
			line.put("problem", ex.getMessage());
		}
		catch (final McpException ex)
		{
			line.put("ok", false);
			line.put("problem", McpServers.describe(ex));
		}
		line.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
		return line.path("ok").asBoolean();
	}

	/**
	 * The probe call of one server, on a client that already answered {@code tools/list}: sets {@code ok} on
	 * the line and fills its {@code probe} object ({@code tool}, {@code ok}, {@code problem}, {@code
	 * elapsedMs}). A server this class knows no probe for (never the case for the constants above, kept for a
	 * caller naming a new server) is {@code ok} on its tool list alone, with {@code probe.tool} null.
	 * <p>
	 * Read-timeout retry lot (volume-test bug, project PersVol, 2026-09-24): when the probe call itself times
	 * out with a genuine {@code SocketTimeoutException} ({@link #isReadTimeout}), a fresh client is built with
	 * {@link #PROBE_RETRY_READ_TIMEOUT_MS} and the SAME probe call is retried exactly once before the server
	 * is declared unusable - a server can still be warming up a few minutes after a Tomcat restart and answer
	 * past {@link #PROBE_READ_TIMEOUT_MS} while genuinely about to become healthy (the same call succeeded a
	 * minute later with no other change). A connection refused, a malformed answer, or an explicit HTTP
	 * refusal (401/403/...) is never retried - only a real read timeout is.
	 * <p>
	 * {@code pAllowRetry} (lot R12, 2026-09-27; lot R14, 2026-09-28 - see {@link #preflight}'s own dated Javadoc):
	 * {@code true} for both {@link #test} and {@link #preflight} now - the two differ in the FIRST attempt's
	 * timeout ({@link #PROBE_READ_TIMEOUT_MS} vs. {@link #preflightProbeTimeoutMs()}), not in whether a genuine
	 * timeout gets one retry. When the retry succeeds, {@code out}/{@code pLine} carry {@code slowProbe: true} -
	 * the caller (only {@link #preflight} reads it today) proceeds instead of refusing, but the fact that the
	 * server was slow is never hidden.
	 */
	private static void probe(final Session pSession, final Adaptation pFactory, final McpClient pClient, final String pServer, final List<McpClient.Tool> pTools,
		final ObjectNode pLine, final boolean pAllowRetry)
	{
		final Probe probe = McpServers.PROBES.get(pServer);
		final ObjectNode out = pLine.putObject("probe");
		if (probe == null)
		{
			out.putNull("tool");
			out.put("ok", true);
			pLine.put("ok", true);
			return;
		}
		out.put("tool", probe.tool);
		final long started = System.nanoTime();
		final McpException[] timedOut = new McpException[1];
		String problem = McpServers.probeProblem(pClient, probe, pTools, timedOut);
		final boolean firstAttemptTimedOut = timedOut[0] != null;
		if (problem != null && firstAttemptTimedOut && pAllowRetry)
		{
			try
			{
				final McpClient retryClient = McpServers.client(pSession, pFactory, pServer, McpServers.PROBE_RETRY_READ_TIMEOUT_MS);
				problem = McpServers.probeProblem(retryClient, probe, pTools, new McpException[1]);
			}
			catch (final OperationException ex)
			{
				problem = ex.getMessage();
			}
		}
		out.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
		out.put("ok", problem == null);
		if (problem != null)
		{
			out.put("problem", problem);
			pLine.put("problem", problem);
		}
		else if (firstAttemptTimedOut)
		{
			// lot R14 (2026-09-28, docs/MATCHING.md dated entry): the first, tight attempt genuinely timed out
			// (never a connection refused or an explicit HTTP refusal - isReadTimeout is null-safe and only ever
			// true for a real SocketTimeoutException) but the retry, with the generous budget, answered - slow,
			// not unusable. Surfaced, never hidden, so a person reading the connection test or a refused route's
			// own servers array can tell "answered on the first try" from "needed the generous retry".
			out.put("slowProbe", true);
			pLine.put("slowProbe", true);
		}
		pLine.put("ok", problem == null);
	}

	/**
	 * Whether {@code pException} is a genuine read timeout ({@code java.net.SocketTimeoutException}), never a
	 * connection refused, a malformed answer, or an explicit HTTP refusal (401/403/...). {@link McpClient}'s
	 * own HTTP transport wraps any {@code IOException} from the exchange the same way ({@code
	 * McpException(String, Throwable)}, always {@code getCode() == 0}) - the underlying cause is the only way
	 * to tell a slow-but-alive server (worth one retry with a longer read timeout) from one that is actually
	 * unreachable (a retry would just waste the same time again) or refused outright (a retry changes
	 * nothing). Pure and package-visible for the test.
	 */
	static boolean isReadTimeout(final McpException pException)
	{
		return pException != null && pException.getCause() instanceof java.net.SocketTimeoutException;
	}

	/** Null when the probe answered; otherwise the sentence to show, naming the tool. {@code pTimedOut}, when non-null, has its single element set to the underlying {@link McpException} exactly when {@link #isReadTimeout} is true of it - the caller's signal to retry once with a longer read timeout. */
	private static String probeProblem(final McpClient pClient, final Probe pProbe, final List<McpClient.Tool> pTools, final McpException[] pTimedOut)
	{
		if (!McpServers.offers(pTools, pProbe.tool))
		{
			return "The server lists " + pTools.size() + " tool(s) but not '" + pProbe.tool + "': the server is enabled with a reduced tool set for this account, or its build differs from the one the factory was written against.";
		}
		try
		{
			final McpClient.ToolResult result = pClient.callTool(pProbe.tool, pProbe.arguments);
			if (result.error)
			{
				return "The account may list the tools but the call to '" + pProbe.tool + "' was refused: " + McpServers.shorten(result.text);
			}
			return null;
		}
		catch (final McpException ex)
		{
			if (pTimedOut != null && McpServers.isReadTimeout(ex))
			{
				pTimedOut[0] = ex;
			}
			return "The account may list the tools but the call to '" + pProbe.tool + "' failed: " + McpServers.describe(ex);
		}
	}

	static boolean offers(final List<McpClient.Tool> pTools, final String pTool)
	{
		for (final McpClient.Tool tool : pTools)
		{
			if (pTool.equals(tool.name))
			{
				return true;
			}
		}
		return false;
	}

	private static String describe(final McpException pException)
	{
		if (pException.getCode() == 401 || pException.getCode() == 403)
		{
			return "The credentials were refused (HTTP " + pException.getCode() + "): the agent account, or the session's user.";
		}
		if (pException.getCode() == 404 || pException.getCode() == 400)
		{
			return "No MCP server at this path (HTTP " + pException.getCode() + "): the module or add-on is not deployed on this instance, or the base URL is wrong.";
		}
		return pException.getMessage();
	}

	static String shorten(final String pText)
	{
		if (pText == null)
		{
			return "(no message)";
		}
		final String oneLine = pText.replace('\n', ' ').replace('\r', ' ').trim();
		return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
	}

	/** JVM property: the one directory a {@code file:} secret reference may point into. */
	static final String SECRETS_DIR_PROPERTY = "ebx.factory.secretsDir";

	/** JVM property: comma-separated names of the environment variables an {@code env:} secret reference may read. */
	static final String SECRETS_ENV_ALLOWED_PROPERTY = "ebx.factory.secretsEnvAllowed";

	/** The stored value, decrypted when it was entered through the encrypted editor (else as written by an import). */
	private static String decrypted(final String pStored)
	{
		try
		{
			return PasswordTypeEncryption.decrypt(pStored);
		}
		catch (final Throwable ex)
		{
			return pStored; // written in clear by an import
		}
	}

	/** The names listed in {@value #SECRETS_ENV_ALLOWED_PROPERTY} (empty when unset). */
	static java.util.Set<String> allowedEnvNames()
	{
		final java.util.Set<String> names = new java.util.LinkedHashSet<String>();
		final String raw = System.getProperty(McpServers.SECRETS_ENV_ALLOWED_PROPERTY);
		if (raw != null)
		{
			for (final String name : raw.split(","))
			{
				if (!name.trim().isEmpty())
				{
					names.add(name.trim());
				}
			}
		}
		return names;
	}

	/** The real path of {@value #SECRETS_DIR_PROPERTY}, or null when it is unset, blank or not an existing directory. */
	private static java.nio.file.Path secretsDir()
	{
		final String raw = System.getProperty(McpServers.SECRETS_DIR_PROPERTY);
		if (raw == null || raw.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final java.nio.file.Path dir = Paths.get(raw.trim()).toRealPath();
			return Files.isDirectory(dir) ? dir : null;
		}
		catch (final java.io.IOException | RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * Why a stored secret reference may not be read, or null when it may (or is no reference at all). Audit F2-04: nothing is read by default - {@code file:} needs
	 * {@value #SECRETS_DIR_PROPERTY} and a canonical path under it, {@code env:} needs the variable listed in {@value #SECRETS_ENV_ALLOWED_PROPERTY}. The text names the property to set.
	 */
	static String secretRefusal(final String pStored)
	{
		if (pStored == null)
		{
			return null;
		}
		final String value = McpServers.decrypted(pStored);
		if (value == null)
		{
			return null;
		}
		if (value.startsWith("env:"))
		{
			final String name = value.substring(4).trim();
			if (!McpServers.allowedEnvNames().contains(name))
			{
				return "the secret reference env:" + name + " is not allowed: list the variable in -D" + McpServers.SECRETS_ENV_ALLOWED_PROPERTY
					+ " (comma-separated names) and restart, or enter the secret itself";
			}
		}
		else if (value.startsWith("file:"))
		{
			final java.nio.file.Path dir = McpServers.secretsDir();
			if (dir == null)
			{
				return "a file: secret reference is not allowed: set -D" + McpServers.SECRETS_DIR_PROPERTY
					+ " to the existing directory that holds the secrets and restart, or enter the secret itself";
			}
			try
			{
				if (!Paths.get(value.substring(5).trim()).toRealPath().startsWith(dir))
				{
					return "the file: secret reference points outside -D" + McpServers.SECRETS_DIR_PROPERTY + " (" + dir + "): move the file there";
				}
			}
			catch (final java.io.IOException | RuntimeException ex)
			{
				return "the file: secret reference cannot be resolved under -D" + McpServers.SECRETS_DIR_PROPERTY + " (" + dir + "): check the path";
			}
		}
		return null;
	}

	/** Throws a coded refusal naming the property to set when the stored secret is a reference that may not be read. */
	static void requireReadableSecret(final String pStored) throws OperationException
	{
		final String refusal = McpServers.secretRefusal(pStored);
		if (refusal != null)
		{
			throw EngineRefusal.coded("secret.refused", refusal, refusal);
		}
	}

	/** The secret-reference settings for {@code /status}: what is configured, never a path to a secret or a value. */
	static ObjectNode secretsStatusJson()
	{
		final ObjectNode out = new ObjectMapper().createObjectNode();
		out.put("fileReferences", McpServers.secretsDir() != null ? "confined" : "refused");
		out.put("fileReferencesProperty", McpServers.SECRETS_DIR_PROPERTY);
		out.put("envReferences", McpServers.allowedEnvNames().size());
		out.put("envReferencesProperty", McpServers.SECRETS_ENV_ALLOWED_PROPERTY);
		return out;
	}

	/**
	 * The stored value decrypted; {@code env:NAME} and {@code file:/path} are read from the environment or the file only when {@link #secretRefusal} allows it, else null.
	 * Package-private (not {@code private}) so {@link AgentRuntime} reuses this exact decoding for the
	 * language model connector's own API key, instead of a second, slightly different copy of it.
	 */
	static String secret(final String pStored)
	{
		if (pStored == null)
		{
			return null;
		}
		if (McpServers.secretRefusal(pStored) != null)
		{
			return null;
		}
		final String value = McpServers.decrypted(pStored);
		if (value != null && value.startsWith("env:"))
		{
			return System.getenv(value.substring(4).trim());
		}
		if (value != null && value.startsWith("file:"))
		{
			try
			{
				return new String(Files.readAllBytes(Paths.get(value.substring(5).trim())), StandardCharsets.UTF_8).trim();
			}
			catch (final java.io.IOException ex)
			{
				return null;
			}
		}
		return value;
	}
}
