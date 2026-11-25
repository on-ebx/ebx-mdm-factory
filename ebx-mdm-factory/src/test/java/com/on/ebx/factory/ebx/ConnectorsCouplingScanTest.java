package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JUnit-free: run the main, a failure throws. Wave 11 (audit F-26): a SOURCE SCAN that keeps the couplings to Connectors' internals where
 * {@code docs/CONNECTORS-COUPLING.md} says they are. The decryption of a connector's key is reachable from ONE class
 * ({@link AgentLlmConnector}); only that class reads the {@code /root/ConnectorProperty} table or the {@code ./apiKey} column; every Connectors tool
 * name used to write a mapping is in the classes the document lists. Wave 12: the key read is confined to the fallback of the agent loop and the direct
 * connectivity test (see {@link #keyReadIsConfinedToTheFallback}). Lot C (2026-10-17): {@code ConnectionCleanup} is the one other class that names the Connector table
 * and the property table, to delete a sample-file connection the factory created; it never reads a value ({@code ./apiKey} stays confined, checked below). A new coupling therefore fails here until it is documented (and, better, until it goes
 * through {@link ConnectorsContractClient}). The source folder comes from {@code -Dmdmf.sourceDir} ({@code tools/test_local.sh} sets it).
 */
public final class ConnectorsCouplingScanTest
{
	/** Files that may read the connector row by path (the document lists what each reads and what replaces it). */
	private static final List<String> MAY_READ_CONNECTOR_TABLE = Arrays.asList("AgentLlmConnector.java", "WizardData.java", "DeliveryDomainReader.java", "SampleSourceImporter.java",
		"ConnectionCleanup.java");

	public static void main(final String[] pArgs) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final List<Path> files;
		try (Stream<Path> walk = Files.walk(root))
		{
			files = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
		final List<String> problems = new ArrayList<String>();
		int connectorTableReaders = 0;
		for (final Path file : files)
		{
			final String name = file.getFileName().toString();
			final String source = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			if (source.contains("\"/root/ConnectorProperty\""))
			{
				// ConnectionCleanup (lot C, 2026-10-17) only DELETES the property rows of the sample-file connection it is deleting (never a value, never the key column)
				if (!"AgentLlmConnector.java".equals(name) && !"ConnectionCleanup.java".equals(name))
				{
					problems.add(name + " reads the connector properties of Connectors by path: only AgentLlmConnector may (the agent loop's connection read)");
				}
			}
			if (source.contains("\"./apiKey\""))
			{
				// DeliveryDomainReader only asks whether a secret is SET (the delivery pre-check); it never decrypts one
				if (!"AgentLlmConnector.java".equals(name) && !"DeliveryDomainReader.java".equals(name))
				{
					problems.add(name + " reads the API key column of Connectors: only AgentLlmConnector may (and DeliveryDomainReader, to know whether it is set)");
				}
				if (!"AgentLlmConnector.java".equals(name) && source.contains("McpServers.secret("))
				{
					problems.add(name + " decrypts a value and reads the API key column of Connectors");
				}
			}
			if (source.contains("\"/root/Connector\""))
			{
				connectorTableReaders++;
				if (!MAY_READ_CONNECTOR_TABLE.contains(name))
				{
					problems.add(name + " reads the Connector table by path: use the contract (ConnectorsContractClient) or list it in docs/CONNECTORS-COUPLING.md and in this test");
				}
			}
		}
		check("the scan found the connector table readers it knows: " + connectorTableReaders, connectorTableReaders == MAY_READ_CONNECTOR_TABLE.size());
		check("AgentRuntime no longer holds the key read", !new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/ebx/AgentRuntime.java")), StandardCharsets.UTF_8)
			.contains("McpServers.secret("));
		keyReadIsConfinedToTheFallback(root, files);
		check(problems.toString(), problems.isEmpty());
		System.out.println("ConnectorsCouplingScanTest OK");
	}

	/**
	 * Wave 12: the agent loop asks the model through {@code llm.chat} of the contract when Connectors offers it ({@link AgentRoute}), and the key read
	 * ({@code AgentLlmConnector.load}, the only code that decrypts a connector's key) is the fallback: it may be called ONLY from the two direct ways of
	 * {@code AgentRuntime} - {@code directSettings} (the agent loop's fallback) and {@code testLlmDirect} (the direct connectivity test) - and nothing of the contract way
	 * ({@code AgentRoute}, {@code LlmContract}, {@code ConnectorsContractClient}) may touch it or the key helpers.
	 */
	private static void keyReadIsConfinedToTheFallback(final Path pRoot, final List<Path> pFiles) throws IOException
	{
		final List<String> problems = new ArrayList<String>();
		int calls = 0;
		for (final Path file : pFiles)
		{
			final String name = file.getFileName().toString();
			final String source = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
			if (name.equals("AgentRoute.java") || name.equals("LlmContract.java") || name.equals("ConnectorsContractClient.java"))
			{
				if (source.contains("AgentLlmConnector") || source.contains("McpServers.secret(") || source.contains("\"./apiKey\""))
				{
					problems.add(name + " is the contract way: it must not read or decrypt a connector's key");
				}
			}
			int at = source.indexOf("AgentLlmConnector.load(");
			while (at >= 0)
			{
				calls++;
				if (!name.equals("AgentRuntime.java"))
				{
					problems.add(name + " calls AgentLlmConnector.load (the key read): only AgentRuntime's direct ways may");
				}
				else
				{
					final java.util.regex.Matcher method = java.util.regex.Pattern.compile("(?m)^\\t(?:public |private |protected )?(?:static )?(?:final )?[\\w<>\\[\\], .?]+ (\\w+)\\(")
						.matcher(source);
					String enclosing = null;
					while (method.find() && method.start() < at)
					{
						enclosing = method.group(1);
					}
					if (!"directSettings".equals(enclosing) && !"testLlmDirect".equals(enclosing))
					{
						problems.add("AgentRuntime." + enclosing + " calls AgentLlmConnector.load: only directSettings (agent loop fallback) and testLlmDirect may");
					}
				}
				at = source.indexOf("AgentLlmConnector.load(", at + 1);
			}
		}
		check("the key read is called from exactly the two direct ways of AgentRuntime: " + calls, calls == 2);
		check(problems.toString(), problems.isEmpty());
		final String runtime = stripComments(new String(Files.readAllBytes(pRoot.resolve("com/on/ebx/factory/ebx/AgentRuntime.java")), StandardCharsets.UTF_8));
		check("AgentRuntime.run decides the way first (AgentRoute.resolve) and builds the contract chat from it", runtime.contains("AgentRoute.resolve(pSession, connectorId.trim())")
			&& runtime.contains("AgentRoute.chat(pSession, plan)"));
	}

	/** Comments out of the way: a Javadoc may name a path it no longer reads. */
	private static String stripComments(final String pSource)
	{
		return pSource.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
