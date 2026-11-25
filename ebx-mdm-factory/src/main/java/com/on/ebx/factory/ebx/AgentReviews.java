package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * The end of an agent review (agentic framework lot, 2026-10-30): what {@code workflow.AgentDecisionTaskBean} does once the owner accepted or rejected the
 * review task of a run. See that bean for the rules; the per-line decision is {@link AgentFramework#decideProposal}, the same one the REST route uses.
 */
public final class AgentReviews
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private AgentReviews()
	{
	}

	/**
	 * Decides the open lines of run {@code pRunId} for {@code pDecidedBy}: accepted task = every PROPOSED line accepted (and applied when the agent may act),
	 * plus the lines the reviewer set to ACCEPTED by hand; rejected task = every PROPOSED line rejected. Refused, with nothing changed, when the reviewer no
	 * longer holds the agent's owner profile.
	 */
	public static ObjectNode close(final Session pSession, final Adaptation pFactory, final String pRunId, final boolean pAccepted, final String pDecidedBy)
		throws OperationException
	{
		final ObjectNode out = AgentReviews.MAPPER.createObjectNode();
		out.put("run", pRunId);
		out.put("accepted", pAccepted);
		if (pRunId == null || pRunId.trim().isEmpty() || pDecidedBy == null || pDecidedBy.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.agentReviewer", "The agent review ended without a run or a reviewer; nothing was decided.");
		}
		final Adaptation run = pFactory.getTable(FactoryPaths.AGENT_RUN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRunId.trim()));
		final Adaptation definition = run == null ? null : AgentFramework.definition(pFactory, run.getString(FactoryPaths.AgentRun.AGENT));
		if (definition == null)
		{
			throw EngineRefusal.coded("missing.agent", "No agent is declared for run " + pRunId + ".", String.valueOf(pRunId));
		}
		final String owner = AgentEscalations.ownerProfile(definition.getString(FactoryPaths.AgentDefinition.OWNER_PROFILE));
		if (!AgentFramework.holds(pFactory.getHome().getRepository(), Profile.forUser(pDecidedBy.trim()), owner))
		{
			throw EngineRefusal.coded("conflict.agentReviewerProfile", pDecidedBy + " does not hold the owner profile " + owner + " of agent "
				+ definition.getString(FactoryPaths.AgentDefinition.CODE) + "; nothing was decided.", pDecidedBy, String.valueOf(owner));
		}
		final List<String> lines = new ArrayList<String>(AgentFramework.pendingOf(pFactory, pRunId.trim()));
		if (pAccepted)
		{
			lines.addAll(AgentFramework.acceptedToApplyOf(pFactory, pRunId.trim()));
		}
		final ArrayNode decided = out.putArray("lines");
		for (final String line : lines)
		{
			try
			{
				decided.add(AgentFramework.decideProposal(pSession, pFactory, line, pAccepted, pDecidedBy.trim()));
			}
			catch (final OperationException ex)
			{
				final ObjectNode failed = decided.addObject();
				failed.put("proposal", line);
				failed.put("status", "UNCHANGED");
				failed.put("message", ex.getMessage());
			}
		}
		return out;
	}
}
