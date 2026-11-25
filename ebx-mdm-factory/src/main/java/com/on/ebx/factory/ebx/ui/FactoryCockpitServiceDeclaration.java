package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnRecord;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/**
 * The "Cockpit du projet" screen: registered on a single {@code Project} record, exactly like the
 * catalog's {@code ConnectorDiagnosticsServiceDeclaration} / {@code TagReviewServiceDeclaration}
 * ({@code UserServiceDeclaration.OnRecord}) — chosen over a table-level service with a manual project
 * selector because the catalog pattern already covers this case directly: select one {@code Project} row in
 * its table view, open "Cockpit" from that record's own toolbar. No separate selector UI to build or test.
 * <p>
 * Trade-off worth recording for the reviewer: an {@code OnRecord} service has no fixed record to point a
 * perspective menu entry at, so the packaged perspective's "Cockpit" entry (under Projets) targets the
 * {@code Project} table itself, the same target as its "Projets" entry — the cockpit of a specific project
 * is then one click away, from that record's toolbar, not from the menu directly. Read-only in this lot: no
 * button does anything other than close.
 */
public final class FactoryCockpitServiceDeclaration implements UserServiceDeclaration.OnRecord
{
	public static final String SERVICE_NAME = "FactoryCockpit";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(SERVICE_NAME);

	@Override
	public UserService<RecordEntitySelection> createUserService()
	{
		return new FactoryCockpitUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnRecord pContext)
	{
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT);
		// audit F-02: shown to and opened by holders of VIEW (FactoryRights.service), deny by default
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.RecordEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(FactoryCockpitServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(FactoryCockpitServiceDeclaration.class, "cockpit.label"));
		pContext.setDescription(Messages.getInfo(FactoryCockpitServiceDeclaration.class, "cockpit.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return SERVICE_KEY;
	}
}
