package com.on.ebx.factory.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.sun.net.httpserver.HttpServer;

/**
 * JUnit-free. X3-08 (1.4.1): the read bounds of the module's JSON are the Jackson 2.18 defaults, they are told in {@code /status}, and a document beyond them is refused with a
 * code - never a {@code StackOverflowError}, never taken for "empty" or "absent". The deep documents are nested arrays: 1,001 levels is one past the bound of 2.18; on Jackson
 * 2.14.2 {@code readTree} reads them at any depth (measured to 1,000,000 levels, no error), which is why this test fails there ("must be refused").
 */
public final class JsonLimitsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		JsonLimitsTest.theBoundsInForceAreTheJacksonDefaultsAndAreReported();
		JsonLimitsTest.aMapperLikeTheModulesRefusesTooDeepAndTooLongWithABoundRefusal();
		JsonLimitsTest.refusalSentencesAreCodedAndTellBoundFromMalformed();
		JsonLimitsTest.anMcpAnswerNestedBeyondTheBoundIsAnMcpExceptionWithTheBoundAsCause();
		JsonLimitsTest.theSameThroughARealHttpExchange();
		System.out.println("JsonLimitsTest OK");
	}

	static String deep(final int pLevels)
	{
		final StringBuilder out = new StringBuilder(pLevels * 2);
		for (int i = 0; i < pLevels; i++)
		{
			out.append('[');
		}
		for (int i = 0; i < pLevels; i++)
		{
			out.append(']');
		}
		return out.toString();
	}

	private static void theBoundsInForceAreTheJacksonDefaultsAndAreReported()
	{
		final ObjectNode bounds = JsonLimits.statusJson();
		check("applied", bounds.path("applied").asBoolean(false));
		check("depth 1000", bounds.path("maxNestingDepth").asInt() == 1000);
		check("string 20 million", bounds.path("maxStringLength").asInt() == 20000000);
		check("number 1000", bounds.path("maxNumberLength").asInt() == 1000);
		check("name 50000", bounds.path("maxNameLength").asInt() == 50000);
		check("document unlimited (-1)", bounds.path("maxDocumentLength").asLong() == -1L);
		check("the code is told", JsonLimits.BOUND_CODE.equals(bounds.path("refusalCode").asText()));
		check("the version is told", !bounds.path("jacksonVersion").asText().isEmpty());
		// the module sets no override: nothing in the sources calls it
		check("no override anywhere", !JsonLimitsTest.sourcesContain("overrideDefaultStreamReadConstraints"));
	}

	private static boolean sourcesContain(final String pText)
	{
		final String dir = System.getProperty("mdmf.sourceDir");
		if (dir == null)
		{
			return false;
		}
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(java.nio.file.Paths.get(dir)))
		{
			for (final java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				if (!file.getFileName().toString().equals("JsonLimits.java") && new String(java.nio.file.Files.readAllBytes(file), StandardCharsets.UTF_8).contains(pText))
				{
					return true;
				}
			}
			return false;
		}
		catch (final IOException ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static void aMapperLikeTheModulesRefusesTooDeepAndTooLongWithABoundRefusal()
	{
		final ObjectMapper mapper = new ObjectMapper();
		try
		{
			check("1000 levels still read", mapper.readTree(JsonLimitsTest.deep(1000)).isArray());
		}
		catch (final IOException ex)
		{
			throw new AssertionError("1000 levels must read: " + ex);
		}
		for (final int levels : new int[] { 1001, 100000 })
		{
			try
			{
				mapper.readTree(JsonLimitsTest.deep(levels));
				throw new AssertionError(levels + " levels must be refused");
			}
			catch (final IOException ex)
			{
				check(levels + " levels: a bound refusal", JsonLimits.isBoundRefusal(ex));
			}
			catch (final StackOverflowError ex)
			{
				throw new AssertionError(levels + " levels overflowed the stack: this is not Jackson 2.15 or later");
			}
		}
		final StringBuilder longString = new StringBuilder(20000010).append("[\"");
		for (int i = 0; i < 20000001; i++)
		{
			longString.append('a');
		}
		longString.append("\"]");
		try
		{
			mapper.readTree(longString.toString());
			throw new AssertionError("a string of 20,000,001 characters must be refused");
		}
		catch (final IOException ex)
		{
			check("a long string: a bound refusal", JsonLimits.isBoundRefusal(ex));
		}
		try
		{
			mapper.readTree("[" + "1".repeat(1001) + "]");
			throw new AssertionError("a number of 1001 digits must be refused");
		}
		catch (final IOException ex)
		{
			check("a long number: a bound refusal", JsonLimits.isBoundRefusal(ex));
		}
	}

	private static void refusalSentencesAreCodedAndTellBoundFromMalformed()
	{
		try
		{
			new ObjectMapper().readTree(JsonLimitsTest.deep(1001));
			throw new AssertionError("must be refused");
		}
		catch (final IOException bound)
		{
			final String text = JsonLimits.refusal(bound, "the request body");
			check("bound code first: " + text, text.startsWith(JsonLimits.BOUND_CODE + ": the request body goes beyond"));
			check("says what to do and where", text.contains("Send a smaller or less nested document") && text.contains("readBounds"));
			check("wrapped cause is recognised too", JsonLimits.isBoundRefusal(new RuntimeException("x", bound)));
		}
		try
		{
			new ObjectMapper().readTree("{\"a\":");
			throw new AssertionError("must be refused");
		}
		catch (final IOException malformed)
		{
			final String text = JsonLimits.refusal(malformed, "the answer of the data-model server");
			check("malformed code: " + text, text.startsWith(JsonLimits.MALFORMED_CODE + ": the answer of the data-model server is not valid JSON"));
			check("a malformed document is not a bound refusal", !JsonLimits.isBoundRefusal(malformed));
		}
	}

	/** The MCP client of the agent library reads every tool answer with its own mapper (readTree): a too-deep answer must arrive as an McpException, not an Error or a null. */
	private static void anMcpAnswerNestedBeyondTheBoundIsAnMcpExceptionWithTheBoundAsCause()
	{
		final String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"deep\":" + JsonLimitsTest.deep(5000) + "}}";
		final McpClient client = new McpClient(new McpClient.Transport()
		{
			@Override
			public String exchange(final String pRequest) throws McpException
			{
				return body;
			}

			@Override
			public String describe()
			{
				return "stand-in";
			}
		});
		try
		{
			client.callTool("some_tool", new ObjectMapper().createObjectNode());
			throw new AssertionError("a too-deep answer must not be read as a result");
		}
		catch (final McpException ex)
		{
			// the agent jar of Connectors 1.4.2 names the read bound and says the server did answer (it used to say "Unreadable answer")
			check("the McpException names the read bound, not an unreachable server: " + ex.getMessage(),
				ex.getMessage() != null && ex.getMessage().contains("read bound") && ex.getMessage().contains("exceeds the maximum allowed"));
			check("and its cause is the bound refusal", JsonLimits.isBoundRefusal(ex));
		}
	}

	private static void theSameThroughARealHttpExchange() throws Exception
	{
		final byte[] deepBody = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + JsonLimitsTest.deep(5000) + "}").getBytes(StandardCharsets.UTF_8);
		final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/mcp", exchange ->
		{
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, deepBody.length);
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(deepBody);
			}
		});
		server.start();
		try
		{
			final McpClient client = new McpClient("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", new java.util.LinkedHashMap<String, String>(), 2000, 5000);
			try
			{
				client.initialize();
				throw new AssertionError("a too-deep HTTP answer must not be read as a result");
			}
			catch (final McpException ex)
			{
				check("HTTP path: an McpException, not an Error: " + ex.getMessage(), ex.getMessage() != null);
				// measured: the agent library's HTTP path words this "MCP server unreachable at <url>: StreamConstraintsException: Document nesting depth (1001) exceeds..." (on-ebx-agent.jar,
				// built by ebx-connectors; the Factory cannot change it). The bound refusal is the cause and the text names it; it is not read as an empty answer or an absent model.
				check("HTTP path: the cause is the bound refusal (" + ex.getMessage() + ")", JsonLimits.isBoundRefusal(ex));
				check("HTTP path: the text names the bound", ex.getMessage().contains("exceeds the maximum allowed"));
			}
		}
		finally
		{
			server.stop(0);
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
