package com.on.ebx.factory.ebx.ui;

import com.orchestranetworks.ui.selection.RecordEntitySelection;

/**
 * Decides, for every single record-form/table-view display or execution check across the WHOLE
 * repository, whether {@code RecordLineageOnRecordServiceDeclaration}'s action ("Record lineage" on a
 * master record) is offered - the product owner's own hard requirement (coordinator review, 2026-09-22): visible
 * ONLY when the record being looked at sits on a table that is some project's own PROMOTED master zone.
 * <p>
 * <b>Now a thin subclass of {@link MasterZonePermissionRule}</b> (2026-09-22, toolbar module-level services
 * lot): every line of the actual decision - selection resolution, the {@code getStringName()} vs {@code
 * toString()} fact, {@link com.on.ebx.factory.ebx.MasterZoneIndex#isMasterZoneTable}, the fail-CLOSED catch
 * - moved to that shared, generic base class (a same-day follow-up briefly reused it for two more {@code
 * OnTableView} toolbar services, later removed - see that base class's own Javadoc). This class itself is
 * kept, unchanged in name and behaviour, purely so {@code
 * RecordLineageOnRecordServiceDeclaration.defineActivation}'s own {@code new
 * RecordLineageOnRecordPermissionRule()} call site needs no edit - see {@link MasterZonePermissionRule}'s own
 * Javadoc for every fact this class used to document directly.
 */
final class RecordLineageOnRecordPermissionRule extends MasterZonePermissionRule<RecordEntitySelection>
{
	/** Audit F-02: the master-zone decision of the base class, for holders of VIEW only (deny by default). */
	@Override
	public com.orchestranetworks.userservice.permission.UserServicePermission getPermission(
		final com.orchestranetworks.userservice.permission.ServicePermissionRuleContext<RecordEntitySelection> pContext)
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(pContext.getSession(),
			com.on.ebx.factory.core.FactoryRights.service(RecordLineageOnRecordServiceDeclaration.SERVICE_NAME)))
		{
			return com.orchestranetworks.userservice.permission.UserServicePermission.getDisabled();
		}
		return super.getPermission(pContext);
	}
}
