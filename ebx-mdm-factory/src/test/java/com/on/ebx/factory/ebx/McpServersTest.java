package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;

/**
 * JUnit-free: run the main, a failure throws. The pure parts of {@link McpServers} (mcp-preflight-probe lot,
 * 2026-09-23): every known server has a probe, the preflight summary names each failing server with its own
 * sentence and skips the healthy ones, the end points are derived from the base URL as before, and ({@code
 * isReadTimeout}, mcp-preflight-retry lot, 2026-09-25 - volume-test bug, project PersVol) only a genuine
 * {@code SocketTimeoutException} is ever flagged as retryable.
 */
public final class McpServersTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		McpServersTest.everyRequiredAndOptionalServerHasAProbe();
		McpServersTest.probesAreReadOnlyOneRowCalls();
		McpServersTest.summaryNamesEachFailingServerAndSkipsHealthyOnes();
		McpServersTest.summaryWithoutProblemTextSaysUnknown();
		McpServersTest.urlIsDerivedFromTheBaseUrl();
		McpServersTest.shortenIsOneLineAndBounded();
		McpServersTest.isReadTimeoutTrueOnlyForSocketTimeoutCause();
		McpServersTest.isReadTimeoutFalseForConnectionRefused();
		McpServersTest.isReadTimeoutFalseForHttpRefusal();
		McpServersTest.isReadTimeoutFalseForNull();
		McpServersTest.preflightProbeTimeoutMs_defaultsWhenPropertyUnset();
		McpServersTest.preflightProbeTimeoutMs_readsThePropertyAtCallTime();
		McpServersTest.secretReferences();
		McpServersTest.authProbeNeverRelaysACookie();
		System.out.println("McpServersTest OK");
	}

	/** {@code file:} is read only under {@code -Debx.factory.secretsDir} (outside, "..", a symlink out, or the property unset: refused); {@code env:} only for variables of {@code secretsEnvAllowed}, never a Java system property (audit F2-04). */
	private static void secretReferences()
	{
		try
		{
			final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("mdmf-secrets").toRealPath();
			final java.nio.file.Path outside = java.nio.file.Files.createTempDirectory("mdmf-outside").toRealPath();
			final java.nio.file.Path inside = dir.resolve("key");
			java.nio.file.Files.write(inside, "s3cret\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			final java.nio.file.Path elsewhere = outside.resolve("other");
			java.nio.file.Files.write(elsewhere, "other".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			java.nio.file.Files.createSymbolicLink(dir.resolve("link"), elsewhere);

			System.setProperty(McpServers.SECRETS_DIR_PROPERTY, dir.toString());
			McpServersTest.check("a file inside the secrets directory is read", "s3cret".equals(McpServers.secret("file:" + inside)));
			McpServersTest.check("a file outside it is refused", McpServers.secret("file:" + elsewhere) == null);
			McpServersTest.check("a .. path out of it is refused", McpServers.secret("file:" + dir + "/../" + outside.getFileName() + "/other") == null);
			McpServersTest.check("a symlink out of it is refused", McpServers.secret("file:" + dir.resolve("link")) == null);
			System.clearProperty(McpServers.SECRETS_DIR_PROPERTY);

			// F2-04: with no property, nothing is read, and the refusal names the property to set
			McpServersTest.check("unset: file: is refused", McpServers.secret("file:" + elsewhere) == null && McpServers.secret("file:" + inside) == null);
			final String refusal = McpServers.secretRefusal("file:" + inside);
			McpServersTest.check("the refusal names the property", refusal != null && refusal.contains(McpServers.SECRETS_DIR_PROPERTY));
			System.setProperty(McpServers.SECRETS_DIR_PROPERTY, dir.resolve("absent").toString());
			McpServersTest.check("a secrets directory that does not exist confines nothing: refused", McpServers.secret("file:" + inside) == null);
			System.clearProperty(McpServers.SECRETS_DIR_PROPERTY);

			System.clearProperty(McpServers.SECRETS_ENV_ALLOWED_PROPERTY);
			McpServersTest.check("env: is refused when no variable is listed", McpServers.secret("env:PATH") == null);
			final String envRefusal = McpServers.secretRefusal("env:PATH");
			McpServersTest.check("and names its property", envRefusal != null && envRefusal.contains(McpServers.SECRETS_ENV_ALLOWED_PROPERTY));
			System.setProperty(McpServers.SECRETS_ENV_ALLOWED_PROPERTY, "HOME, MDMF_OTHER");
			McpServersTest.check("env: of a listed variable is read", System.getenv("HOME").equals(McpServers.secret("env:HOME")));
			McpServersTest.check("env: of an unlisted variable is refused", McpServers.secret("env:PATH") == null);
			System.clearProperty(McpServers.SECRETS_ENV_ALLOWED_PROPERTY);
			System.setProperty("mdmf.test.only.property", "from-a-system-property");
			System.setProperty(McpServers.SECRETS_ENV_ALLOWED_PROPERTY, "mdmf.test.only.property");
			McpServersTest.check("env: never reads a Java system property", McpServers.secret("env:mdmf.test.only.property") == null);
			System.clearProperty(McpServers.SECRETS_ENV_ALLOWED_PROPERTY);
			McpServersTest.check("a plain value is untouched", "plain".equals(McpServers.secret("plain")));
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError("fixture: " + ex);
		}
	}

	/** F2-09: no probe mode forwards a request cookie or uses a token without a session id; the only cookie is the one minted for the probe. */
	private static void authProbeNeverRelaysACookie()
	{
		final java.util.Map<String, java.util.Map<String, String>> modes = AuthProbe.modes("tok");
		McpServersTest.check("no cookie-forward mode", !modes.containsKey("cookie-forward") && !modes.containsKey("jwt-cookie-min"));
		for (final java.util.Map.Entry<String, java.util.Map<String, String>> mode : modes.entrySet())
		{
			if (mode.getValue() != null)
			{
				for (final String value : mode.getValue().values())
				{
					McpServersTest.check("every credential is the minted token", value.endsWith("tok"));
				}
			}
		}
		McpServersTest.check("no minted token, no token mode", AuthProbe.modes(null).size() == 1);
	}

	/** lot R12 (2026-09-27): {@link McpServers#preflightProbeTimeoutMs()} defaults to 5000ms when the property is not set. */
	private static void preflightProbeTimeoutMs_defaultsWhenPropertyUnset()
	{
		System.clearProperty("ebx.factory.preflightTimeoutMs");
		McpServersTest.check("default is 5000ms", McpServers.preflightProbeTimeoutMs() == 5000);
	}

	/** Read fresh on every call (never cached in a {@code static final}), so a test can override it at runtime - the "configurable" knob the task brief asks for. */
	private static void preflightProbeTimeoutMs_readsThePropertyAtCallTime()
	{
		System.setProperty("ebx.factory.preflightTimeoutMs", "1234");
		try
		{
			McpServersTest.check("the system property overrides the default", McpServers.preflightProbeTimeoutMs() == 1234);
		}
		finally
		{
			System.clearProperty("ebx.factory.preflightTimeoutMs");
		}
	}

	private static void everyRequiredAndOptionalServerHasAProbe()
	{
		for (final String server : McpServers.REQUIRED)
		{
			McpServersTest.check("a probe for required server " + server, McpServers.PROBES.get(server) != null && McpServers.PROBES.get(server).tool != null);
		}
		for (final String server : McpServers.OPTIONAL)
		{
			McpServersTest.check("a probe for optional server " + server, McpServers.PROBES.get(server) != null && McpServers.PROBES.get(server).tool != null);
		}
	}

	/** The probe tools are the read-only listings measured on the servers; a write tool here would make every connection test a side effect. */
	private static void probesAreReadOnlyOneRowCalls()
	{
		// lot R14 (2026-09-28): list_data_models used to be the probe, but it is proportional to the instance's own
		// DMA model count (measured 6-79s live) - a probe must be constant-time, see McpServers.PROBES' own Javadoc.
		McpServersTest.check("data-model probe is constant-time", "data_model_guide".equals(McpServers.PROBES.get(McpServers.DATA_MODEL).tool));
		McpServersTest.check("match-merge probe", "matching_config_schema".equals(McpServers.PROBES.get(McpServers.MATCH_MERGE).tool));
		McpServersTest.check("data-access probe, one row", "list_dataspaces".equals(McpServers.PROBES.get(McpServers.DATA_ACCESS).tool)
			&& McpServers.PROBES.get(McpServers.DATA_ACCESS).arguments.path("limit").asInt() == 1);
		McpServersTest.check("connectors probe", "connectors_list".equals(McpServers.PROBES.get(McpServers.CONNECTORS).tool));
		McpServersTest.check("scripting probe, IDE root, one row", "list_scripts".equals(McpServers.PROBES.get(McpServers.SCRIPTING).tool)
			&& McpServers.PROBES.get(McpServers.SCRIPTING).arguments.has("folderIdePath") && McpServers.PROBES.get(McpServers.SCRIPTING).arguments.path("limit").asInt() == 1);
	}

	private static void summaryNamesEachFailingServerAndSkipsHealthyOnes()
	{
		final ArrayNode servers = McpServersTest.MAPPER.createArrayNode();
		McpServersTest.line(servers, "data-model", true, null);
		McpServersTest.line(servers, "match-merge", false, "The agent account was refused (HTTP 401).");
		McpServersTest.line(servers, "ebx-connectors", false, "The server answers but offers no tool: it is not enabled in Administration > MCP servers, or not for this account.");
		final String summary = McpServers.summary(servers);
		McpServersTest.check("the healthy server is not named: " + summary, summary.indexOf("data-model") < 0);
		McpServersTest.check("each failing server is named with its own sentence: " + summary,
			summary.indexOf("match-merge: The agent account was refused (HTTP 401).") >= 0 && summary.indexOf("ebx-connectors: The server answers but offers no tool") >= 0);
		McpServersTest.check("the summary says the step did not run: " + summary, summary.startsWith("MCP server(s) not usable before the step ran: "));
	}

	private static void summaryWithoutProblemTextSaysUnknown()
	{
		final ArrayNode servers = McpServersTest.MAPPER.createArrayNode();
		McpServersTest.line(servers, "data-access", false, null);
		McpServersTest.check("a failing line without text still yields a sentence", McpServers.summary(servers).indexOf("data-access: unknown problem") >= 0);
	}

	private static void urlIsDerivedFromTheBaseUrl()
	{
		McpServersTest.check("EBX server under ebx-assistant", "http://h:8080/ebx-assistant/mcp/v1/data-model".equals(McpServers.url("http://h:8080/", McpServers.DATA_MODEL)));
		McpServersTest.check("scripting uses the bare 'scripting' segment", McpServers.url("http://h:8080", McpServers.SCRIPTING).endsWith("/mcp/v1/scripting"));
		McpServersTest.check("connectors has its own endpoint", "http://h:8080/ebx-connectors/rest/mcp".equals(McpServers.url("http://h:8080", McpServers.CONNECTORS)));
		McpServersTest.check("match-merge resolves to the add-on's current segment", "http://h:8080/ebx-assistant/mcp/v1/match-merge-admin".equals(McpServers.url("http://h:8080", McpServers.MATCH_MERGE)));
		System.setProperty(McpServers.MATCH_MERGE_SEGMENT_PROPERTY, "match-merge");
		try
		{
			McpServersTest.check("the property forces the match-merge segment", McpServers.url("http://h:8080", McpServers.MATCH_MERGE).endsWith("/mcp/v1/match-merge"));
		}
		finally
		{
			System.clearProperty(McpServers.MATCH_MERGE_SEGMENT_PROPERTY);
		}
		McpServersTest.check("data-access is not required: no class calls it", !java.util.Arrays.asList(McpServers.REQUIRED).contains(McpServers.DATA_ACCESS));
	}

	private static void shortenIsOneLineAndBounded()
	{
		McpServersTest.check("null text is named", "(no message)".equals(McpServers.shorten(null)));
		McpServersTest.check("line breaks are folded", McpServers.shorten("a\nb\r\nc").indexOf('\n') < 0);
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 400; i++)
		{
			longText.append('x');
		}
		McpServersTest.check("a long refusal is cut at 300 characters plus an ellipsis", McpServers.shorten(longText.toString()).length() == 303);
	}

	/**
	 * The actual bug fix: {@code McpClient}'s HTTP transport wraps any {@code IOException} from the exchange
	 * the same way ({@code McpException(String, Throwable)}, code 0) - the cause is the only way to tell a
	 * read timeout (worth one retry with a longer timeout) from anything else.
	 */
	private static void isReadTimeoutTrueOnlyForSocketTimeoutCause()
	{
		final McpException ex = new McpException("MCP server unreachable at http://h:8080/x: java.net.SocketTimeoutException: Read timed out",
			new java.net.SocketTimeoutException("Read timed out"));
		McpServersTest.check("a SocketTimeoutException cause is flagged retryable", McpServers.isReadTimeout(ex));
	}

	/** A connection refused is an IOException too (same wrapping shape, code 0) but must never be retried. */
	private static void isReadTimeoutFalseForConnectionRefused()
	{
		final McpException ex = new McpException("MCP server unreachable at http://h:8080/x: java.net.ConnectException: Connection refused",
			new java.net.ConnectException("Connection refused"));
		McpServersTest.check("a ConnectException cause is never flagged retryable", !McpServers.isReadTimeout(ex));
	}

	/** An explicit HTTP refusal (401/403/...) carries no cause at all (the {@code (int, String)} constructor) - must never be retried either. */
	private static void isReadTimeoutFalseForHttpRefusal()
	{
		final McpException ex = new McpException(401, "HTTP 401 from http://h:8080/x");
		McpServersTest.check("an HTTP-coded refusal is never flagged retryable", !McpServers.isReadTimeout(ex));
	}

	private static void isReadTimeoutFalseForNull()
	{
		McpServersTest.check("a null exception is never flagged retryable", !McpServers.isReadTimeout(null));
	}

	private static void line(final ArrayNode pServers, final String pServer, final boolean pOk, final String pProblem)
	{
		final ObjectNode line = pServers.addObject();
		line.put("server", pServer);
		line.put("ok", pOk);
		if (pProblem != null)
		{
			line.put("problem", pProblem);
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
