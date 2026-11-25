package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Distribution lot 1 (engine, coordinator review 2026-09-24, item 5): "list candidate connections (with/
 * without a write operation)" - deferred out of the original commit (docs/DISTRIBUTION.md sec 7 called it
 * out explicitly), added back because the UI lot needs somewhere to start the target picker from.
 * <p>
 * {@code connectors_list} for every configured connector, then {@code connectors_describe(connector)} (no
 * {@code operation} argument) for each to read its operation list. A connector is reported
 * {@code hasWriteOperation: true} when at least one of its operations looks write-capable.
 * <p>
 * <b>Not verified live</b>: no EBX/Connectors instance was reachable while writing this class. The exact
 * JSON field {@code connectors_describe} uses to flag an operation as a write (as opposed to a read/search)
 * was not confirmed - {@link #looksWriteCapable} checks several plausible keys/values defensively and falls
 * back to including the operation as "unknown" (neither list) when none matches, so a wrong guess degrades
 * to an operation missing from both lists rather than a wrong classification. The wizard (lot 3) should
 * verify this against a real connector (one with an obvious write operation, e.g. an upsert) before relying
 * on {@code hasWriteOperation} to grey out a connection in the picker.
 * <p>
 * <b>Live-measured, 2026-09-25 (task brief item 4)</b>: {@code connectors_describe}'s real shape is a bare
 * object with a {@code properties} object alongside {@code operations[]} ({@code capability.ServiceInventory}'s
 * own Javadoc, captured live 2026-09-21 - {@code {"...,"properties":{},"operations":[...]}}). For a connector
 * whose destination is a connector-level property rather than the mapping's own {@code tablePath} (Kafka:
 * {@code topic}/{@code key.field}), {@code properties.topic}/{@code properties["key.field"]} are copied
 * through as {@code topic}/{@code keyField} so the wizard (7b) can show a read-only "Sends to" line instead of
 * a topic picker this module does not build (task brief: "Do NOT change Connectors").
 */
public final class DistributionConnections
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DistributionConnections()
	{
	}

	/** {@code {connections: [{connector, label, type, hasWriteOperation, writeOperations: [...], readOperations: [...], unknownOperations: [...], topic, keyField}]}} - {@code topic}/{@code keyField} blank for an ordinary connector, only set for one with a connector-level destination (Kafka). Inactive connectors ({@code active: false}) are left out entirely. */
	public static ObjectNode listCandidateConnections(final Session pSession, final Adaptation pFactory) throws OperationException
	{
		final ObjectNode out = DistributionConnections.MAPPER.createObjectNode();
		final ArrayNode connections = out.putArray("connections");
		try
		{
			final McpClient client = McpServers.connectors(pSession, pFactory);
			final ObjectNode listArgs = DistributionConnections.MAPPER.createObjectNode();
			final McpClient.ToolResult list = client.callTool("connectors_list", listArgs);
			if (list.error || list.json == null)
			{
				throw EngineRefusal.coded("external.listConnections", "connectors_list refused: " + DistributionConfigurator.errorText(list));
			}
			for (final JsonNode connectorNode : DistributionConnections.itemsOf(list.json))
			{
				final String connectorId = connectorNode.path("id").asText(connectorNode.path("connector").asText(null));
				if (connectorId == null)
				{
					continue;
				}
				// live bug fix (2026-09-25, DistribTest, task brief item 2): keep inactive connectors out - same
				// "active" field/default (true when absent) ebx.ServiceInventory already reads off connectors_list.
				if (!connectorNode.path("active").asBoolean(true))
				{
					continue;
				}
				final ObjectNode connectionOut = connections.addObject();
				connectionOut.put("connector", connectorId);
				connectionOut.put("label", connectorNode.path("label").asText(connectorId));
				connectionOut.put("type", connectorNode.path("type").asText(""));
				DistributionConnections.describeOperations(client, connectorId, connectionOut);
			}
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.coded("external.listConnections", "Could not list candidate connections: " + ex.getMessage());
		}
		return out;
	}

	private static void describeOperations(final McpClient pClient, final String pConnectorId, final ObjectNode pConnectionOut)
	{
		final ArrayNode writeOps = pConnectionOut.putArray("writeOperations");
		final ArrayNode readOps = pConnectionOut.putArray("readOperations");
		final ArrayNode unknownOps = pConnectionOut.putArray("unknownOperations");
		try
		{
			final ObjectNode args = DistributionConnections.MAPPER.createObjectNode();
			args.put("connector", pConnectorId);
			final McpClient.ToolResult describe = pClient.callTool("connectors_describe", args);
			if (describe.error || describe.json == null)
			{
				pConnectionOut.put("describeError", describe.text == null ? "(no message)" : describe.text);
				pConnectionOut.put("hasWriteOperation", false);
				return;
			}
			// task brief item 4: a connector-level destination (Kafka's own topic/key.field properties) - see
			// class Javadoc. Both blank when the connector has none (properties absent or without these keys).
			final JsonNode properties = describe.json.path("properties");
			pConnectionOut.put("topic", properties.path("topic").asText(""));
			pConnectionOut.put("keyField", properties.path("key.field").asText(""));
			for (final JsonNode operationNode : DistributionConnections.operationsOf(describe.json))
			{
				final String name = operationNode.path("name").asText(operationNode.path("operation").asText(null));
				if (name == null)
				{
					continue;
				}
				final Boolean writeCapable = DistributionConnections.looksWriteCapable(operationNode);
				if (Boolean.TRUE.equals(writeCapable))
				{
					writeOps.add(name);
				}
				else if (Boolean.FALSE.equals(writeCapable))
				{
					readOps.add(name);
				}
				else
				{
					unknownOps.add(name);
				}
			}
		}
		catch (final McpException ex)
		{
			pConnectionOut.put("describeError", ex.getMessage());
		}
		pConnectionOut.put("hasWriteOperation", writeOps.size() > 0);
	}

	/**
	 * Task brief round 4 item 4 (2026-09-26, DistribTest live incident): "Test the connection" button on the
	 * target's 7b setup panel - resolves the target's own connector/operation and delegates to
	 * {@link #testConnection}.
	 */
	public static ObjectNode testConnectionForTarget(final Session pSession, final Adaptation pFactory, final String pTargetId) throws OperationException
	{
		final Adaptation target = DistributionConfigurator.findTarget(pFactory, pTargetId);
		if (target == null)
		{
			throw EngineRefusal.coded("missing.target", "Distribution target " + pTargetId + " does not exist", pTargetId);
		}
		final String connector = target.getString(FactoryPaths.DistributionTarget.CONNECTOR);
		final String operation = target.getString(FactoryPaths.DistributionTarget.OPERATION);
		return DistributionConnections.testConnection(pSession, pFactory, connector, operation);
	}

	/**
	 * Reuses the SAME read-only {@code connectors_describe} call {@code ebx.SourceOnboarder#describeInto}
	 * already runs to test a connection during source onboarding - the only generic, read-only connection
	 * check this module has, and the one the task brief points at ("find the existing connection test in the
	 * wizard's Connection settings / onboarding").
	 * <p>
	 * <b>Known limitation, live-measured (DistribTest, 2026-09-26, task brief item 4):</b> {@code
	 * connectors_describe} only reads the connector's OWN stored configuration - it never opens a network
	 * connection to the target, so it answered cleanly (connector found, active, operation present) while the
	 * target Kafka broker was actually down (the same run whose {@code connectors_sync} failure is
	 * {@code SyncOutcome}'s own regression case). No generic read-only Kafka probe exists in {@code
	 * ebx-connectors} to close that gap - doing so would mean actually writing to or reading from the topic,
	 * which is out of this module's scope ("do NOT change Connectors") - so this check still catches every
	 * OTHER failure a broken target can have (the connector was deleted, disabled, or no longer has the write
	 * operation the target names), but a "Test connection: OK" here does NOT guarantee the broker/target
	 * itself is reachable.
	 */
	public static ObjectNode testConnection(final Session pSession, final Adaptation pFactory, final String pConnectorId, final String pOperation)
	{
		final ObjectNode out = DistributionConnections.MAPPER.createObjectNode();
		out.put("connector", pConnectorId == null ? "" : pConnectorId);
		if (pConnectorId == null || pConnectorId.trim().isEmpty())
		{
			out.put("ok", false);
			out.put("message", "This target has no connector configured yet.");
			return out;
		}
		try
		{
			final McpClient client;
			try
			{
				client = McpServers.connectors(pSession, pFactory);
			}
			catch (final OperationException ex)
			{
				out.put("ok", false);
				out.put("message", ex.getMessage());
				return out;
			}
			final ObjectNode args = DistributionConnections.MAPPER.createObjectNode();
			args.put("connector", pConnectorId);
			final McpClient.ToolResult describe = client.callTool("connectors_describe", args);
			if (describe.error || describe.json == null)
			{
				out.put("ok", false);
				out.put("message", DistributionConfigurator.errorText(describe));
				return out;
			}
			final boolean active = describe.json.path("active").asBoolean(true);
			boolean hasOperation = pOperation == null || pOperation.trim().isEmpty();
			for (final JsonNode operationNode : DistributionConnections.operationsOf(describe.json))
			{
				final String name = operationNode.path("name").asText(operationNode.path("operation").asText(null));
				if (pOperation != null && pOperation.equals(name))
				{
					hasOperation = true;
				}
			}
			out.put("active", active);
			out.put("hasOperation", hasOperation);
			if (!active)
			{
				out.put("ok", false);
				out.put("message", "Connector " + pConnectorId + " is not active.");
			}
			else if (!hasOperation)
			{
				out.put("ok", false);
				out.put("message", "Connector " + pConnectorId + " has no operation '" + pOperation + "'.");
			}
			else
			{
				out.put("ok", true);
				out.put("message", "Connector " + pConnectorId + " is configured and active. This check reads the connector's own "
					+ "configuration only - it cannot confirm the target (e.g. a Kafka broker) is actually reachable right now.");
			}
		}
		catch (final McpException ex)
		{
			out.put("ok", false);
			out.put("message", ex.getMessage());
		}
		return out;
	}

	/** {@code true}: looks like a write operation; {@code false}: looks like a read; {@code null}: could not tell (see class Javadoc). */
	private static Boolean looksWriteCapable(final JsonNode pOperationNode)
	{
		for (final String key : new String[] { "write", "isWrite", "writeCapable", "supportsWrite" })
		{
			if (pOperationNode.has(key))
			{
				return Boolean.valueOf(pOperationNode.path(key).asBoolean(false));
			}
		}
		final String direction = pOperationNode.path("direction").asText(pOperationNode.path("kind").asText(pOperationNode.path("type").asText("")));
		final String lower = direction.toLowerCase(java.util.Locale.ENGLISH);
		if (lower.contains("write") || lower.contains("upsert") || lower.contains("create") || lower.contains("update"))
		{
			return Boolean.TRUE;
		}
		if (lower.contains("read") || lower.contains("search") || lower.contains("query"))
		{
			return Boolean.FALSE;
		}
		return null;
	}

	private static Iterable<JsonNode> itemsOf(final JsonNode pRoot)
	{
		for (final String key : new String[] { "connectors", "items", "data" })
		{
			if (pRoot.has(key) && pRoot.path(key).isArray())
			{
				return pRoot.path(key);
			}
		}
		return pRoot.isArray() ? pRoot : java.util.Collections.<JsonNode>emptyList();
	}

	private static Iterable<JsonNode> operationsOf(final JsonNode pRoot)
	{
		for (final String key : new String[] { "operations", "items", "data" })
		{
			if (pRoot.has(key) && pRoot.path(key).isArray())
			{
				return pRoot.path(key);
			}
		}
		return java.util.Collections.<JsonNode>emptyList();
	}
}
