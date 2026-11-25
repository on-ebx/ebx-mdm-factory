package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * Production review of 2026-11-12 (F3-06, reading part): the fields of {@code AgentProposal} that carry values copied from the master records (the arguments with
 * the proposed value, the model's rationale, the application message with the value before and after, the record the proposal targets) are hidden from a person
 * who cannot decide the proposal. Those who can: an administrator, a holder of the Factory right STEWARD or above, the holder of the agent's owner profile (the
 * reviewer of the escalation workflow). A person with the VIEW right still sees the line (agent, status, confidence), never the values.
 */
public final class AgentProposalReadRule implements AccessRule
{
	/** The fields hidden from those who cannot decide (relative to the table record). */
	static final com.orchestranetworks.schema.Path[] VALUE_FIELDS = { FactoryPaths.AgentProposal.ARGUMENTS, FactoryPaths.AgentProposal.RATIONALE,
		FactoryPaths.AgentProposal.APPLY_MESSAGE, FactoryPaths.AgentProposal.TARGET };

	/** Pure: whether a person may read the values of a proposal. */
	static boolean mayRead(final FactoryRight pGranted, final boolean pAdministrator, final boolean pOwnerOfAgent)
	{
		return pAdministrator || pOwnerOfAgent || FactoryRight.allows(pGranted, FactoryRight.STEWARD);
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		if (pSession == null || pSession.isSystemUser())
		{
			return AccessPermission.getReadWrite(); // the platform and the module's own sessions
		}
		final boolean administrator = pSession.isUserInRole(Profile.ADMINISTRATOR);
		final FactoryRight granted = FactoryAccess.granted(pSession);
		boolean owner = false;
		if (!administrator && !FactoryRight.allows(granted, FactoryRight.STEWARD) && pRecord != null)
		{
			final Adaptation dataset = pRecord.getContainerTable().getContainerAdaptation();
			final Adaptation definition = AgentFramework.definition(dataset, pRecord.getString(FactoryPaths.AgentProposal.AGENT));
			final String profile = definition == null ? null : AgentEscalations.ownerProfile(definition.getString(FactoryPaths.AgentDefinition.OWNER_PROFILE));
			owner = AgentFramework.holds(dataset.getHome().getRepository(), pSession.getUserReference(), profile);
		}
		return AgentProposalReadRule.mayRead(granted, administrator, owner) ? AccessPermission.getReadWrite() : AccessPermission.getHidden();
	}
}
