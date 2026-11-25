package com.on.ebx.factory.ebx;

import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.workflow.ProcessLauncher;
import com.orchestranetworks.workflow.PublishedProcessKey;
import com.orchestranetworks.workflow.WorkflowEngine;

/**
 * Opens the review of an agent's pending decisions (agentic framework lot, 2026-10-30): one instance of the {@code MdmFactoryAgentEscalation} workflow
 * ({@code tools/workflow/MdmFactoryAgentEscalation.json}) per run that left decisions to review. Its user task is offered to the agent's OWNER PROFILE by
 * {@code workflow.AgentEscalationUserTask} (the profile is a value of the data context, which a static model cannot name); when the task is accepted or
 * rejected, {@code workflow.AgentDecisionTaskBean} decides the run's {@code AgentProposal} lines. Launched through {@link WorkflowLaunches} (the workflow
 * engine's class loader around {@code launchProcess}, CLAUDE.md &sect;10). Public API only.
 */
public final class AgentEscalations
{
	public static final String PUBLICATION_NAME = "MdmFactoryAgentEscalation";

	private AgentEscalations()
	{
	}

	/** Launches the review of run {@code pRunId}; returns the instance key. Throws when the model is not published or the owner profile is not a profile. */
	static String launch(final Session pSession, final com.onwbp.adaptation.Adaptation pFactory, final String pRunId, final String pAgentCode, final String pOwnerProfile,
		final int pPending) throws OperationException
	{
		final String owner = AgentEscalations.ownerProfile(pOwnerProfile);
		if (owner == null)
		{
			throw EngineRefusal.coded("invalid.agentOwner", "Agent " + pAgentCode + ": the owner profile '" + pOwnerProfile + "' is not an EBX profile (R<role>, U<user>, B<built-in>).",
				pAgentCode, String.valueOf(pOwnerProfile));
		}
		if (!PipelineWorkflowLauncher.isPublished(pFactory.getHome().getRepository(), AgentEscalations.PUBLICATION_NAME))
		{
			throw EngineRefusal.coded("missing.agentEscalationModel", "The workflow " + AgentEscalations.PUBLICATION_NAME + " is not published on this instance.",
				AgentEscalations.PUBLICATION_NAME);
		}
		final WorkflowEngine engine = WorkflowEngine.getFromRepository(pFactory.getHome().getRepository(), pSession);
		final ProcessLauncher launcher = engine.getProcessLauncher(PublishedProcessKey.forName(AgentEscalations.PUBLICATION_NAME));
		AgentEscalations.setIfDeclared(launcher, "agentRun", pRunId);
		AgentEscalations.setIfDeclared(launcher, "agentCode", pAgentCode);
		AgentEscalations.setIfDeclared(launcher, "ownerProfile", owner);
		launcher.setLabel(com.onwbp.base.text.UserMessage.createInfo("Agent " + pAgentCode + ": " + pPending + " decision(s) to review (run " + pRunId + ")"));
		return WorkflowLaunches.launch(launcher).format();
	}

	/**
	 * The owner profile as stored ({@code R<role>}, {@code U<user>}, {@code B<built-in>}); a value {@code Profile.parse} refuses is retried as a role name, like
	 * the workflow modeling tools do. Null when unusable. Write the prefix: a bare role name that starts with R, U or B is read as a prefixed code.
	 */
	static String ownerProfile(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return null;
		}
		final String value = pValue.trim();
		for (final String code : new String[] { value, "R" + value })
		{
			try
			{
				return Profile.parse(code).format();
			}
			catch (final RuntimeException ex)
			{
				// next reading
			}
		}
		return null;
	}

	private static void setIfDeclared(final ProcessLauncher pLauncher, final String pName, final String pValue)
	{
		if (pLauncher.isDefinedAsInputParameter(pName))
		{
			pLauncher.setInputParameter(pName, pValue);
		}
	}
}
