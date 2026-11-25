package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.FeatureSwitches;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.workflow.CreationWorkItemSpec;
import com.orchestranetworks.workflow.UserTask;
import com.orchestranetworks.workflow.UserTaskCreationContext;
import com.orchestranetworks.workflow.UserTaskWorkItemCompletionContext;

/**
 * The user task of the agent review ({@code MdmFactoryAgentEscalation}, agentic framework lot 2026-10-30): offers ONE work item to the agent's owner profile,
 * read from the data context ({@code ownerProfile}) - a static model can only name fixed profiles - and, when it is completed, writes into the data context
 * who decided ({@code decidedBy}), whether the task was accepted ({@code decision}: {@code ACCEPTED}/{@code REJECTED}) and the comment, for
 * {@link AgentDecisionTaskBean}. Public API only ({@code UserTask}, {@code CreationWorkItemSpec.forOfferring}, javap-checked on 6.3.0-rc4).
 * <p>
 * The model still lists {@code Badministrator} as the static participant (publication requires one); this bean decides the actual offer. An unreadable
 * {@code ownerProfile} falls back to that static list ({@link UserTaskCreationContext#getProfiles()}), never to nobody.
 */
public final class AgentEscalationUserTask extends UserTask
{
	@Override
	public void handleCreate(final UserTaskCreationContext pContext) throws OperationException
	{
		Profile owner = null;
		try
		{
			final String value = pContext.getVariableString("ownerProfile");
			owner = value == null || value.trim().isEmpty() ? null : Profile.parse(value.trim());
		}
		catch (final RuntimeException ex)
		{
			owner = null;
		}
		if (owner != null)
		{
			pContext.createWorkItem(CreationWorkItemSpec.forOfferring(owner));
			return;
		}
		final java.util.List<Profile> fallback = new java.util.ArrayList<Profile>();
		for (final String code : pContext.getProfiles())
		{
			try
			{
				fallback.add(Profile.parse(code));
			}
			catch (final RuntimeException ex)
			{
				// skip an unreadable static participant
			}
		}
		pContext.createWorkItem(CreationWorkItemSpec.forOfferring(fallback.isEmpty() ? java.util.Collections.<Profile>singletonList(Profile.ADMINISTRATOR) : fallback));
	}

	@Override
	public void handleWorkItemCompletion(final UserTaskWorkItemCompletionContext pContext) throws OperationException
	{
		// production review 2026-11-12 (F3-08): a review cannot be completed while the agents are closed - the work item stays open and the person reads the refusal
		FeatureSwitches.requireAgents();
		final UserTask.WorkItem item = pContext.getCompletedWorkItem();
		if (item != null)
		{
			pContext.setVariableString("decision", AgentEscalationUserTask.decisionOf(item.isAccepted(), item.isRejected()));
			pContext.setVariableString("decidedBy", item.getUserReference() == null ? "" : item.getUserReference().getUserId());
			pContext.setVariableString("comment", item.getComment() == null ? "" : item.getComment());
		}
		pContext.completeUserTask();
	}

	/** Pure: the {@code decision} variable of a completed work item. Anything but an explicit accept is a rejection: nothing is applied by default. */
	static String decisionOf(final boolean pAccepted, final boolean pRejected)
	{
		return pAccepted && !pRejected ? "ACCEPTED" : "REJECTED";
	}
}
