package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orchestranetworks.service.Session;

/**
 * Wave 11 (audit F-26): the flow operations of the Connectors contract ({@code flows.get}, {@code flows.put}) as this module writes its
 * mappings. A flow is identified by its KEY, which is the root mapping's label as the Connectors screens show it - the labels this module
 * already gives its mappings ({@code Landing: <project> <source> <table>}, {@code Distribution: target <id> <table>},
 * {@code Data product <code> - <table>}) are the keys, so a flow written before the contract is found and updated in place, never duplicated.
 * The document is the mapping document of {@code connectors_mapping_upsert} without {@code id}, {@code updateById} and {@code parent} (the
 * contract refuses them: the key designates the mapping).
 */
final class ContractFlows
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ContractFlows()
	{
	}

	/** Pure: the key of a mapping document - its label, trimmed; null when it has none. */
	static String keyOf(final ObjectNode pMapping)
	{
		final String label = pMapping.path("label").asText("").trim();
		return label.isEmpty() ? null : label;
	}

	/** Pure: the flow document of a mapping document - a copy without the identity fields the contract refuses ({@code id}, {@code updateById}, {@code parent}), at every level. */
	static ObjectNode flowOf(final ObjectNode pMapping)
	{
		final ObjectNode flow = pMapping.deepCopy();
		ContractFlows.strip(flow);
		return flow;
	}

	private static void strip(final ObjectNode pNode)
	{
		pNode.remove("id");
		pNode.remove("updateById");
		pNode.remove("parent");
		final JsonNode children = pNode.get("children");
		if (children != null && children.isArray())
		{
			for (final JsonNode child : children)
			{
				if (child.isObject())
				{
					ContractFlows.strip((ObjectNode) child);
				}
			}
		}
	}

	/** {@code flows.get}: the mapping id of the flow of {@code pKey}, or null when there is none. */
	static String idOf(final Session pSession, final String pKey) throws ConnectorsContractClient.Refusal
	{
		final ObjectNode params = ContractFlows.MAPPER.createObjectNode();
		params.put("key", pKey);
		final JsonNode result = ConnectorsContractClient.call(pSession, "flows.get", params);
		return result.path("found").asBoolean(false) && result.hasNonNull("mappingId") ? result.path("mappingId").asText() : null;
	}

	/** {@code flows.get}: the flow document of {@code pKey} (the document {@link #put} accepts), or null when there is none. */
	static JsonNode flow(final Session pSession, final String pKey) throws ConnectorsContractClient.Refusal
	{
		final ObjectNode params = ContractFlows.MAPPER.createObjectNode();
		params.put("key", pKey);
		final JsonNode result = ConnectorsContractClient.call(pSession, "flows.get", params);
		return result.path("found").asBoolean(false) ? result.path("flow") : null;
	}

	/** {@code flows.put} of {@code pMapping} under its own label ({@link #keyOf}); the contract's answer ({@code mappingId} is null on a dry run of a new flow). */
	static JsonNode put(final Session pSession, final ObjectNode pMapping, final boolean pDryRun) throws ConnectorsContractClient.Refusal
	{
		final String key = ContractFlows.keyOf(pMapping);
		if (key == null)
		{
			throw new ConnectorsContractClient.Refusal(ConnectorsContractClient.OPERATION_FAILED, "a flow needs a label: it is the flow's key");
		}
		final ObjectNode params = ContractFlows.MAPPER.createObjectNode();
		params.put("key", key);
		params.set("flow", ContractFlows.flowOf(pMapping));
		params.put("dryRun", pDryRun);
		return ConnectorsContractClient.call(pSession, "flows.put", params);
	}
}
