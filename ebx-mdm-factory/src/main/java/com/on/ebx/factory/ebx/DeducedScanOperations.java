package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.deducedscan.OperationCatalog;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Deduced-scan lot (2026-09-24, read-recipe-contract.md sec 2): {@code connectors_describe(connector)} for
 * one connection, turned into {@link OperationCatalog.OperationInfo} - the {@code readOnly} flag contract sec
 * 2 adds to every operation, read defensively (a connector whose {@code ebx-connectors} has not been
 * upgraded yet - a SEPARATE module/lot - simply answers no {@code readOnly} field at all: {@link
 * #readOnlyFlag} then defaults to {@code false}, never {@code true} - see {@link OperationCatalog}'s own
 * Javadoc for why that is the safe direction). Same JSON-navigation style as {@code
 * DistributionConnections#operationsOf}/{@code LandingFlows} (tolerant of a couple of plausible key names,
 * never assumes one shape blindly).
 *
 * <p>Bug fix 2026-09-26, found live on project DeducedTest/connection OpenFDA (PipelineStep 405): the real
 * {@code connectors_describe} answer carries an operation's inputs as TWO string arrays, {@code
 * requiredInputs}/{@code optionalInputs} (plus {@code kind}/{@code description}) - this class used to look
 * only for a single {@code inputs}/{@code parameters}/{@code params} array of objects, which OpenFDA's answer
 * never had, so every operation read back with ZERO input names. The model was never told {@code udi} needs
 * {@code di}, {@code deviceSearch} needs {@code brand}, {@code drug} needs {@code ndc} - it picked all three
 * (each a single-record lookup) as "listings", and the job failed with a bare "No table could be deduced"
 * once every sample call rejected for the missing required input. {@link #legacyInputNamesOf}, the old {@code
 * inputs}/{@code parameters}/{@code params} lookup, is kept as a fallback ONLY for a connector whose {@code
 * connectors_describe} predates this shape (contract sec 2 is itself a separate, independently-upgraded
 * module) - see {@link OperationCatalog.OperationInfo}'s 3-arg legacy constructor.</p>
 */
public final class DeducedScanOperations
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DeducedScanOperations()
	{
	}

	public static final class Described
	{
		public final List<OperationCatalog.OperationInfo> operations = new ArrayList<OperationCatalog.OperationInfo>();
		/**
		 * Whether an operation literally named {@code "catalog"} exists - the same detection {@code
		 * ebx.SourceOnboarder#catalogCapable}/{@code #describeInto} already use for "does this connection have
		 * an OFFICIAL scan" (task brief: "detect it from connectors_describe's operations - no test on the
		 * connector type name"). {@code ebx.DeducedScanRun} only ever runs a deduced scan when this is false -
		 * a connection WITH a catalog operation is the official-scan lot's own territory (contract sec "Why").
		 */
		public boolean hasCatalogOperation;
		/** Non-null on a {@code connectors_describe} failure - the caller reports it rather than treating an empty operation list as "no listing operation found". */
		public String error;
	}

	public static Described describe(final Session pSession, final Adaptation pFactory, final String pConnectorId)
	{
		final Described out = new Described();
		try
		{
			final McpClient client = McpServers.connectors(pSession, pFactory);
			final ObjectNode args = DeducedScanOperations.MAPPER.createObjectNode();
			args.put("connector", pConnectorId);
			final McpClient.ToolResult result = client.callTool("connectors_describe", args);
			if (result.error || result.json == null)
			{
				out.error = result.text == null || result.text.trim().isEmpty() ? "connectors_describe refused the request." : result.text.trim();
				return out;
			}
			DeducedScanOperations.parseInto(result.json, out);
		}
		catch (final OperationException ex)
		{
			out.error = ex.getMessage();
		}
		catch (final McpException ex)
		{
			out.error = ex.getMessage();
		}
		return out;
	}

	/**
	 * The pure part of {@link #describe} - JSON navigation only, no EBX/MCP dependency - split out so it can
	 * be exercised directly against a fixture (a {@code connectors_describe} answer captured verbatim, e.g.
	 * OpenFDA's own live shape measured 2026-09-26) without a live connection. Package-visible for {@code
	 * DeducedScanOperationsTest}.
	 */
	static void parseInto(final JsonNode pRoot, final Described pOut)
	{
		for (final JsonNode operationNode : DeducedScanOperations.operationsOf(pRoot))
		{
			final String name = operationNode.path("name").asText(operationNode.path("operation").asText(null));
			if (name == null)
			{
				continue;
			}
			if ("catalog".equals(name))
			{
				pOut.hasCatalogOperation = true;
			}
			List<String> requiredInputNames = DeducedScanOperations.stringArrayOf(operationNode, "requiredInputs");
			List<String> optionalInputNames = DeducedScanOperations.stringArrayOf(operationNode, "optionalInputs");
			if (requiredInputNames.isEmpty() && optionalInputNames.isEmpty())
			{
				// legacy shape (a connector whose connectors_describe predates requiredInputs/optionalInputs,
				// contract sec 2): every declared input read as OPTIONAL - see OperationInfo's 3-arg ctor Javadoc.
				optionalInputNames = DeducedScanOperations.legacyInputNamesOf(operationNode);
			}
			final String description = operationNode.path("description").asText(null);
			final String kind = operationNode.path("kind").asText(null);
			pOut.operations.add(new OperationCatalog.OperationInfo(name, DeducedScanOperations.readOnlyFlag(operationNode), requiredInputNames,
				optionalInputNames, description, kind, DeducedScanOperations.inputTypesOf(operationNode)));
		}
	}

	/**
	 * Audit F-17: the type a connector declares for an input, when it describes inputs as objects ({@code {"name": "limit", "type": "integer"}}) under any of the keys
	 * this class reads. The live shape measured so far (plain string arrays) carries none, and then no value is held to a type - the other rules of {@link
	 * com.on.ebx.factory.deducedscan.InputValueRules} still apply.
	 */
	private static java.util.Map<String, String> inputTypesOf(final JsonNode pOperationNode)
	{
		final java.util.Map<String, String> out = new java.util.LinkedHashMap<String, String>();
		for (final String key : new String[] { "requiredInputs", "optionalInputs", "inputs", "parameters", "params" })
		{
			if (!pOperationNode.has(key) || !pOperationNode.path(key).isArray())
			{
				continue;
			}
			for (final JsonNode entry : pOperationNode.path(key))
			{
				final String inputName = entry.path("name").asText(null);
				final String type = entry.path("type").asText(null);
				if (inputName != null && type != null && !type.trim().isEmpty())
				{
					out.put(inputName, type.trim());
				}
			}
		}
		return out;
	}

	/** {@code true} only on an explicit {@code "readOnly": true} - absent, non-boolean, or explicitly false are all NOT read-only. See class Javadoc. */
	private static boolean readOnlyFlag(final JsonNode pOperationNode)
	{
		return pOperationNode.has("readOnly") && pOperationNode.path("readOnly").isBoolean() && pOperationNode.path("readOnly").asBoolean(false);
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

	/** {@code requiredInputs}/{@code optionalInputs}: a plain JSON array of strings (the real, live shape - measured on OpenFDA 2026-09-26). Also tolerates an array of {@code {"name": "..."}} objects, same defensive style as {@link #legacyInputNamesOf}, in case a future connector shape nests them. */
	private static List<String> stringArrayOf(final JsonNode pOperationNode, final String pKey)
	{
		final List<String> out = new ArrayList<String>();
		if (pOperationNode.has(pKey) && pOperationNode.path(pKey).isArray())
		{
			for (final JsonNode entry : pOperationNode.path(pKey))
			{
				final String value = entry.isTextual() ? entry.asText() : entry.path("name").asText(null);
				if (value != null)
				{
					out.add(value);
				}
			}
		}
		return out;
	}

	/** Legacy fallback only (see this class's Javadoc "Bug fix 2026-09-26"): a connector whose {@code connectors_describe} answer predates {@code requiredInputs}/{@code optionalInputs} instead names its inputs under {@code inputs}/{@code parameters}/{@code params}, as an array of strings or of {@code {"name": "..."}} objects. */
	private static List<String> legacyInputNamesOf(final JsonNode pOperationNode)
	{
		final List<String> out = new ArrayList<String>();
		for (final String key : new String[] { "inputs", "parameters", "params" })
		{
			if (pOperationNode.has(key) && pOperationNode.path(key).isArray())
			{
				for (final JsonNode inputNode : pOperationNode.path(key))
				{
					final String inputName = inputNode.isTextual() ? inputNode.asText() : inputNode.path("name").asText(null);
					if (inputName != null)
					{
						out.add(inputName);
					}
				}
				return out;
			}
		}
		return out;
	}
}
