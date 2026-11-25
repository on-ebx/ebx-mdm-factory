package com.on.ebx.factory.ebx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot 3 measurement (2026-09-24): which credentials the MCP servers of EBX itself (data-model, match-merge,
 * scripting - the ones no in-JVM bridge can reach) accept from this node, so the agent account can go. For each
 * server and each mode, one {@code initialize} + {@code tools/list} over HTTP to this node ({@link LocalEndpoint}):
 * <ul>
 * <li>{@code basic-agent} - today's way, the baseline;</li>
 * <li>{@code jwt-cookie-session} - an {@code EBXUID} cookie minted in this JVM for the caller's user by EBX's own
 * {@code JsonWebTokenHandler} (through {@link SessionToken}, the only class that names it) (internal API, found by {@code javap} on 6.3.0-dev: {@code createToken(user,
 * activitySeconds, sessionId, trustSeconds, sessionSeconds)} then {@code formatToken}) with a random session id;
 * 5-minute validity;</li>
 * <li>{@code jwt-bearer} / {@code jwt-ebx} - the same minted token in {@code Authorization: Bearer} /
 * {@code Authorization: EBX}.</li>
 * </ul>
 * Audit F2-09 (2026-10-24): the {@code cookie-forward} mode (the caller's own cookies relayed to the node) and the token minted without a session id are gone - both had done their
 * measuring work on 2026-09-24, and a relayed cookie has no place in a running module. The answer never contains a cookie or a token.
 * <ul>
 * </ul>
 * Read-only: nothing but {@code initialize}/{@code tools/list} is sent. No token, cookie or password is ever put
 * in the answer. An empty tool list is reported as such (EBX answers it for an identity the server is not enabled
 * for), not as a success.
 */
public final class AuthProbe
{
	static final String[] SERVERS = { McpServers.DATA_MODEL, McpServers.MATCH_MERGE, McpServers.SCRIPTING };
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int READ_TIMEOUT_MS = 30000;
	private static final int TOKEN_SECONDS = 300;

	private AuthProbe()
	{
	}

	/**
	 * The {@code asUser} mode (a token minted for another user) was removed once it had done its job, 2026-09-24: it
	 * proved the servers apply the token user's per-profile Server permissions (0 data-model tools for a non-admin
	 * with data-model closed to [all profiles], 29 for the admin), and minting for an arbitrary login is an
	 * impersonation surface that has no place in a running module, even read-only and admin-only.
	 */
	public static ObjectNode run(final Session pSession, final Adaptation pFactory)
	{
		final ObjectNode out = new ObjectMapper().createObjectNode();
		out.put("user", pSession.getUserReference().getUserId());
		final String baseUrl;
		try
		{
			baseUrl = LocalEndpoint.resolve(pFactory).baseUrl;
		}
		catch (final OperationException ex)
		{
			out.put("error", ex.getMessage());
			return out;
		}
		out.put("baseUrl", baseUrl);

		final ObjectNode minting = out.putObject("minting");
		String sessionCookie = null;
		try
		{
			sessionCookie = SessionToken.probeCookie(pSession, pFactory.getHome().getRepository(), AuthProbe.TOKEN_SECONDS);
			minting.put("ok", sessionCookie != null);
		}
		catch (final Throwable ex)
		{
			minting.put("ok", false);
			minting.put("problem", String.valueOf(ex));
		}
		final Map<String, Map<String, String>> modes = AuthProbe.modes(sessionCookie);

		final ArrayNode results = out.putArray("results");
		for (final String server : AuthProbe.SERVERS)
		{
			for (final Map.Entry<String, Map<String, String>> mode : modes.entrySet())
			{
				final ObjectNode line = results.addObject();
				line.put("server", server);
				line.put("mode", mode.getKey());
				final long started = System.nanoTime();
				try
				{
					final McpClient client = mode.getValue() == null ? McpServers.client(pFactory, server)
						: new McpClient(McpServers.url(baseUrl, server), mode.getValue(), AuthProbe.CONNECT_TIMEOUT_MS, AuthProbe.READ_TIMEOUT_MS);
					client.initialize();
					final List<McpClient.Tool> tools = client.listTools();
					line.put("ok", !tools.isEmpty());
					line.put("tools", tools.size());
					if (tools.isEmpty())
					{
						line.put("problem", "accepted, but an empty tool list (server not enabled for this identity)");
					}
				}
				catch (final McpException ex)
				{
					line.put("ok", false);
					line.put("status", ex.getCode());
					line.put("problem", McpServers.shorten(ex.getMessage()));
				}
				catch (final OperationException ex)
				{
					line.put("ok", false);
					line.put("problem", McpServers.shorten(ex.getMessage()));
				}
				line.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
			}
		}
		return out;
	}

	/** The authentication modes tried, by name; a null value means the configured agent account. Never one that relays a request cookie. */
	static Map<String, Map<String, String>> modes(final String pSessionCookie)
	{
		final Map<String, Map<String, String>> modes = new LinkedHashMap<String, Map<String, String>>();
		modes.put("basic-agent", null);
		final String token = SessionToken.tokenOf(pSessionCookie);
		if (token != null)
		{
			modes.put("jwt-cookie-session", AuthProbe.header("Cookie", pSessionCookie));
			modes.put("jwt-bearer", AuthProbe.header("Authorization", "Bearer " + token));
			modes.put("jwt-ebx", AuthProbe.header("Authorization", "EBX " + token));
		}
		return modes;
	}

	private static Map<String, String> header(final String pName, final String pValue)
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put(pName, pValue);
		return headers;
	}
}
