package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * Who may decide an agent's proposal from a screen or the REST route (agentic framework lot, 2026-10-30): an administrator, or a holder of the agent's owner
 * profile. The route itself already asks STEWARD; this adds the agent's own reviewer, the same rule the escalation workflow applies ({@link AgentReviews#close}).
 */
public final class AgentReviewAccess
{
	private AgentReviewAccess()
	{
	}

	public static boolean mayDecide(final Session pSession, final Adaptation pFactory, final Adaptation pProposal)
	{
		if (pSession == null || pProposal == null)
		{
			return false;
		}
		if (pSession.isUserInRole(Profile.ADMINISTRATOR))
		{
			return true;
		}
		final Adaptation definition = AgentFramework.definition(pFactory, pProposal.getString(FactoryPaths.AgentProposal.AGENT));
		final String owner = definition == null ? null : AgentEscalations.ownerProfile(definition.getString(FactoryPaths.AgentDefinition.OWNER_PROFILE));
		return owner != null && AgentFramework.holds(pFactory.getHome().getRepository(), pSession.getUserReference(), owner);
	}
}
