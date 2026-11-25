package com.on.ebx.factory.ebx;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.AccessRuleForCreate;
import com.orchestranetworks.service.AccessRuleForCreateContext;
import com.orchestranetworks.service.Session;

/**
 * Production review of 2026-11-12 (F3-02, F3-03): a field only the module writes - the activation of an agent ({@code enabled}, {@code enabledBy},
 * {@code lastRunAt}), what a proposal decides ({@code tool}, {@code action}, {@code arguments}, {@code target}, {@code status}, {@code applyAfterApproval}), the
 * style of a project once created. Read only to EVERY person, the administrator included, in every screen, table view and Data Services call; the module writes
 * them from a procedure that disables permission checks, or from a system session, and is not stopped by this rule. Creation is closed field by field (a creation
 * rule on the table node itself puts the whole model in error, see {@link FactoryJobAccessRule}).
 */
public final class ModuleOnlyFieldRule implements AccessRule, AccessRuleForCreate
{
	static final UserMessage REASON = UserMessage.createInfo("MDM Factory: this value is set by the module itself (use the route or the review that sets it).");

	/** Pure: who may write such a field through a permission-checked path - only the platform or the system user. */
	static boolean writable(final boolean pPlatform, final boolean pSystemUser)
	{
		return pPlatform || pSystemUser;
	}

	private static boolean writable(final Session pSession)
	{
		return ModuleOnlyFieldRule.writable(pSession == null, pSession != null && pSession.isSystemUser());
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		return ModuleOnlyFieldRule.writable(pSession) ? AccessPermission.getReadWrite() : AccessPermission.getReadOnly(ModuleOnlyFieldRule.REASON);
	}

	@Override
	public AccessPermission getPermission(final AccessRuleForCreateContext pContext)
	{
		return ModuleOnlyFieldRule.writable(pContext.getSession()) ? AccessPermission.getReadWrite() : AccessPermission.getReadOnly(ModuleOnlyFieldRule.REASON);
	}
}
