package com.on.ebx.factory.ebx;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.project.ProjectDeletionPlan.SourceFacts;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Whose is the {@code ebx-connectors} mapping a {@code LandingTable.inboundMapping} id names? (Fix of 2026-10-03: two projects
 * reading the same source system shared one mapping, so an id stored on a project's table could name the OTHER project's flow;
 * a generation then rewrote it and a project deletion would have deleted it.) A mapping belongs to a landing table when it
 * writes into that table: its {@code dataspace}, {@code dataset} and {@code table} (the path) are the project's landing dataspace,
 * the source's landing dataset and {@code /root/<table>}. One {@code connectors_mapping_get} per id is the whole evidence; this
 * class reads it, {@code LandingFlows} (generation) and {@code ProjectDeleter}/{@code StepResetter} (deletion plans) decide.
 */
final class LandingFlowOwnership
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private LandingFlowOwnership()
	{
	}

	/** What {@code connectors_mapping_get} answered for one id. */
	static final class Mapping
	{
		/** The mapping no longer exists (Connectors' own "No mapping '<id>'"). */
		final boolean gone;
		final String dataspace;
		final String dataset;
		final String table;

		Mapping(final boolean pGone, final String pDataspace, final String pDataset, final String pTable)
		{
			this.gone = pGone;
			this.dataspace = pDataspace;
			this.dataset = pDataset;
			this.table = pTable;
		}

		/** True when this mapping writes into exactly the given landing table. */
		boolean writesInto(final String pDataspace, final String pDataset, final String pTablePath)
		{
			return !this.gone && LandingFlowOwnership.same(this.dataspace, pDataspace) && LandingFlowOwnership.same(this.dataset, pDataset)
				&& LandingFlowOwnership.same(this.table, pTablePath);
		}
	}

	private static boolean same(final String pA, final String pB)
	{
		return pA != null && pA.equals(pB);
	}

	/**
	 * Reads one mapping. An absent mapping answers {@code gone}; any other failure (transport, refused permission, unexpected
	 * answer) throws, never reads as "gone": a flow that cannot be checked is never taken for the caller's own.
	 */
	static Mapping read(final McpClient pClient, final String pMappingId) throws McpException, OperationException
	{
		final JsonNode json = LandingFlowOwnership.answer(pClient, pMappingId);
		if (json == null)
		{
			return new Mapping(true, null, null, null);
		}
		if (!json.hasNonNull("dataspace"))
		{
			throw OperationException.createError("connectors_mapping_get " + pMappingId + " did not answer a dataspace: " + json);
		}
		return new Mapping(false, json.path("dataspace").asText(null), json.path("dataset").asText(null), json.path("table").asText(null));
	}

	/**
	 * The raw {@code connectors_mapping_get} document of one id, or {@code null} when the mapping no longer exists (Connectors' own "No
	 * mapping '<id>'"). Any other failure throws. Shared with {@link DistributionFlowOwnership}, whose outbound mappings may name no dataspace.
	 */
	static JsonNode answer(final McpClient pClient, final String pMappingId) throws McpException, OperationException
	{
		final ObjectNode args = LandingFlowOwnership.MAPPER.createObjectNode();
		args.put("mapping", pMappingId);
		final McpClient.ToolResult result;
		try
		{
			result = pClient.callTool("connectors_mapping_get", args);
		}
		catch (final McpException ex)
		{
			if (GeneratedObjectDeleter.thrownMeansAbsent(ex.getMessage(), GeneratedObjectDeleter.MAPPING_ABSENT_MARKER))
			{
				return null;
			}
			throw ex;
		}
		if (result.error)
		{
			final String text = result.json != null && result.json.hasNonNull("error") ? result.json.path("error").asText() : String.valueOf(result.text);
			// connectors_mapping_get: "No mapping '<id>'" (ConnectorsTools), the one sentence that says the mapping is not there
			if (GeneratedObjectDeleter.thrownMeansAbsent(text, GeneratedObjectDeleter.MAPPING_ABSENT_MARKER + "'"))
			{
				return null;
			}
			throw OperationException.createError("connectors_mapping_get " + pMappingId + " failed: " + text);
		}
		// an answer with no JSON document is an empty one: the callers refuse it for their own missing field
		return result.json == null ? LandingFlowOwnership.MAPPER.createObjectNode() : result.json;
	}

	/**
	 * The dataspace each id writes into, for a deletion plan's facts: {@link SourceFacts#MAPPING_GONE} for an absent mapping,
	 * {@link SourceFacts#MAPPING_UNREADABLE} for one that could not be read (including the whole Connectors server being
	 * unreachable) - the plan refuses the latter. Nothing is called when there is no id (a project without flows needs no Connectors).
	 */
	static Map<String, String> dataspaces(final Session pSession, final Adaptation pFactory, final Collection<String> pMappingIds)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pMappingIds.isEmpty())
		{
			return out;
		}
		McpClient client = null;
		try
		{
			client = McpServers.connectors(pSession, pFactory);
		}
		catch (final OperationException ex)
		{
			client = null;
		}
		for (final String id : pMappingIds)
		{
			String dataspace = SourceFacts.MAPPING_UNREADABLE;
			if (client != null)
			{
				try
				{
					final Mapping mapping = LandingFlowOwnership.read(client, id);
					dataspace = mapping.gone ? SourceFacts.MAPPING_GONE : mapping.dataspace;
				}
				catch (final McpException ex)
				{
					dataspace = SourceFacts.MAPPING_UNREADABLE;
				}
				catch (final OperationException ex)
				{
					dataspace = SourceFacts.MAPPING_UNREADABLE;
				}
			}
			out.put(id, dataspace);
		}
		return out;
	}
}
