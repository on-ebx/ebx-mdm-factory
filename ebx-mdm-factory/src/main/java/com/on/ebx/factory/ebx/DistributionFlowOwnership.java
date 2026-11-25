package com.on.ebx.factory.ebx;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.project.ProjectDeletionPlan.TargetFlowFacts;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Whose is the OUTBOUND {@code ebx-connectors} mapping a {@code DistributionTarget.connectorsMappingIds} entry names? (Fix of 2026-10-12: the
 * targets of two deleted projects and their mappings 302, 314 and 316 were left behind, and a later send resolved one of them by mistake.) Two
 * pieces of evidence, both from one {@code connectors_mapping_get} per id: the {@code label} ({@code "Distribution: target <id> <tablePath>"},
 * written by {@code DistributionConfigurator.label}) and the {@code dataspace} the mapping reads, when it names one (a mapping written before
 * 2026-10-12 names none). The decision is {@code ProjectDeletionPlan.distributionFlowReason}, pure and tested; this class only reads.
 */
final class DistributionFlowOwnership
{
	private DistributionFlowOwnership()
	{
	}

	/**
	 * The evidence for each mapping id ({@code pTablePathById}: id to the table path of the target's entry). {@code GONE} for an absent mapping,
	 * {@code UNREADABLE} for one that could not be read, whatever the cause (the whole Connectors server unreachable included) - the plan refuses
	 * the latter. Nothing is called when there is no id (a project without targets needs no Connectors).
	 */
	static Map<String, TargetFlowFacts> read(final Session pSession, final Adaptation pFactory, final Map<String, String> pTablePathById)
	{
		final Map<String, TargetFlowFacts> out = new LinkedHashMap<String, TargetFlowFacts>();
		if (pTablePathById.isEmpty())
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
		for (final Map.Entry<String, String> entry : pTablePathById.entrySet())
		{
			out.put(entry.getKey(), DistributionFlowOwnership.one(client, entry.getKey(), entry.getValue()));
		}
		return out;
	}

	private static TargetFlowFacts one(final McpClient pClient, final String pMappingId, final String pTablePath)
	{
		if (pClient == null)
		{
			return new TargetFlowFacts(pMappingId, pTablePath, TargetFlowFacts.STATE_UNREADABLE, null, null);
		}
		try
		{
			final JsonNode json = LandingFlowOwnership.answer(pClient, pMappingId);
			if (json == null)
			{
				return new TargetFlowFacts(pMappingId, pTablePath, TargetFlowFacts.STATE_GONE, null, null);
			}
			if (!json.hasNonNull("label"))
			{
				return new TargetFlowFacts(pMappingId, pTablePath, TargetFlowFacts.STATE_UNREADABLE, null, null);
			}
			return new TargetFlowFacts(pMappingId, pTablePath, TargetFlowFacts.STATE_READ, json.path("label").asText(), json.path("dataspace").asText(null));
		}
		catch (final McpException ex)
		{
			return new TargetFlowFacts(pMappingId, pTablePath, TargetFlowFacts.STATE_UNREADABLE, null, null);
		}
		catch (final OperationException ex)
		{
			return new TargetFlowFacts(pMappingId, pTablePath, TargetFlowFacts.STATE_UNREADABLE, null, null);
		}
	}
}
