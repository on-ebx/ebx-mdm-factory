package com.on.ebx.factory.ebx;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.distribution.ScriptValidateReport;
import com.orchestranetworks.service.Session;
import com.onwbp.adaptation.Adaptation;

/**
 * The scripting IDE's validate-only route (lot S3, 2026-10-18): {@code POST <base>/ebx-ide/rest/v1/script/validate/<idePath>} with
 * {@code {"source":...,"parameters":[],"openApi":""}}. It saves nothing, and unlike {@code update_script} it answers what the compiler really
 * said - a report with lines, or a bare HTTP 500 when the compiler itself crashes (the case that {@code update_script} wraps as
 * {@code INVALID_DATA_MODEL "Revision history ... could not be loaded"}). Called only AFTER an {@code update_script} refusal, to put that
 * answer in the install result; it never throws and never decides anything.
 * <p>
 * Same credentials as the MCP clients of the module ({@link McpServers#authHeaders}): the session user's token, or the agent account.
 * NOT measured through a token cookie (the route was measured with Basic authentication only): an HTTP 401/403 here is reported as such.
 */
final class ScriptValidateRoute
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int READ_TIMEOUT_MS = 60000;

	private ScriptValidateRoute()
	{
	}

	/**
	 * Audit F2-10: each segment of the idePath percent-encoded (a space, '#', '?' or '%' in an entity name would otherwise cut or corrupt the URL), the '/' between
	 * segments kept. Package-private for the test.
	 */
	static String encodeIdePath(final String pScriptIdePath)
	{
		final String[] segments = pScriptIdePath.split("/", -1);
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < segments.length; i++)
		{
			if (i > 0)
			{
				out.append('/');
			}
			final byte[] bytes = segments[i].getBytes(StandardCharsets.UTF_8);
			for (final byte b : bytes)
			{
				final int c = b & 0xFF;
				if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~' || c == '@')
				{
					out.append((char) c);
				}
				else
				{
					out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
				}
			}
		}
		return out.toString();
	}

	/** One sentence: HTTP code, report, first failing statement. Never throws. */
	static String describe(final Session pSession, final Adaptation pFactory, final String pScriptIdePath, final String pSource)
	{
		HttpURLConnection connection = null;
		try
		{
			final Map<String, String> headers = McpServers.authHeaders(pSession, pFactory);
			final String url = LocalEndpoint.trimSlashes(LocalEndpoint.resolve(pFactory).baseUrl) + "/ebx-ide/rest/v1/script/validate/" + ScriptValidateRoute.encodeIdePath(pScriptIdePath);
			final ObjectNode body = ScriptValidateRoute.MAPPER.createObjectNode();
			body.put("source", pSource);
			body.putArray("parameters");
			body.put("openApi", "");
			connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setConnectTimeout(ScriptValidateRoute.CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(ScriptValidateRoute.READ_TIMEOUT_MS);
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			for (final Map.Entry<String, String> header : headers.entrySet())
			{
				connection.setRequestProperty(header.getKey(), header.getValue());
			}
			final OutputStream out = connection.getOutputStream();
			try
			{
				out.write(ScriptValidateRoute.MAPPER.writeValueAsBytes(body));
			}
			finally
			{
				out.close();
			}
			final int code = connection.getResponseCode();
			final InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
			final String answer = in == null ? "" : ScriptValidateRoute.read(in);
			return ScriptValidateReport.describe(code, answer, pSource);
		}
		catch (final Exception ex)
		{
			return "validate-only IDE route could not be called: " + ex;
		}
		finally
		{
			if (connection != null)
			{
				connection.disconnect();
			}
		}
	}

	private static String read(final InputStream pIn) throws java.io.IOException
	{
		try
		{
			final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
			final byte[] chunk = new byte[4096];
			int n;
			while ((n = pIn.read(chunk)) > 0 && buffer.size() < 200000)
			{
				buffer.write(chunk, 0, n);
			}
			return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
		}
		finally
		{
			pIn.close();
		}
	}
}
