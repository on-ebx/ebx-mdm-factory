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
 * The "Transfer rejects" review screen (audit F-38, wave 10): registered on one {@code Project} record like the Cockpit
 * ({@link FactoryCockpitServiceDeclaration}), opened from the Cockpit, from the wizard's load steps and from the Project record's own toolbar
 * entry. Read-only for a holder of VIEW; acknowledging and reopening re-check STEWARD in {@code ebx.RejectReview}.
 */
public final class TransferRejectReviewServiceDeclaration implements UserServiceDeclaration.OnRecord
{
	public static final String SERVICE_NAME = "TransferRejectReview";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(SERVICE_NAME);

	@Override
	public UserService<RecordEntitySelection> createUserService()
	{
		return new TransferRejectReviewUserService();
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
		// shown to and opened by holders of VIEW (FactoryRights.service), deny by default
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.RecordEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(TransferRejectReviewServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(TransferRejectReviewServiceDeclaration.class, "rejects.label"));
		pContext.setDescription(Messages.getInfo(TransferRejectReviewServiceDeclaration.class, "rejects.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return SERVICE_KEY;
	}
}
