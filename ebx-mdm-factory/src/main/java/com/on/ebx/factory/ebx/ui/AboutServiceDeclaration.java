package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnTableView;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/**
 * The "About" screen: the module's version and a link to its documentation. A toolbar and perspective action on the
 * {@code Project} table like the other screens, open to everyone who holds any Factory right ({@code FactoryRights.service}:
 * VIEW); it shows no secret, and {@link AboutUserService} adds the jar hashes for administrators only.
 */
public final class AboutServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "About";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(AboutServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new AboutUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT);
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(AboutServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(AboutServiceDeclaration.class, "about.label"));
		pContext.setDescription(Messages.getInfo(AboutServiceDeclaration.class, "about.description.service"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return AboutServiceDeclaration.SERVICE_KEY;
	}
}
