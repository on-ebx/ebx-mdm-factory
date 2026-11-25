package com.on.ebx.factory.ebx;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * Rows only administrators may see or change (security review, 2026-09-24): the factory settings (the internal EBX
 * address, the agent account, the fallback policy, the launcher role) and the language-model run logs (AgentRun /
 * AgentStep, which may hold sample values and tool results). Anyone else, through the UI, Data Services or any other
 * session-checked access, sees nothing. The factory's own Java reads are not session-checked and keep working.
 */
public final class AdminOnlyAccessRule implements AccessRule
{
	static final UserMessage REASON = UserMessage.createInfo("MDM Factory: administrators only.");

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		return pSession != null && pSession.isUserInRole(Profile.ADMINISTRATOR) ? AccessPermission.getReadWrite() : AccessPermission.getHidden();
	}
}
