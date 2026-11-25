package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnTableView;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;
import com.orchestranetworks.userservice.permission.ServicePermissionRule;
import com.orchestranetworks.userservice.permission.ServicePermissionRuleContext;
import com.orchestranetworks.userservice.permission.UserServicePermission;

/**
 * The "Connexion" screen: the factory's one agent account and the language model connector, entered once,
 * with a test button. Registered on the {@code FactorySettings} table view (a toolbar and a perspective
 * action, like the catalog's dashboard services), administrators only — the button writes the account
 * credentials and runs with the agent account's own rights ({@code McpServers.test}), the same restriction
 * {@code FactoryResource} already applies to {@code GET /connection/test}. {@link FactoryConnectionUserService}
 * re-checks the role: a permission rule only hides the action, it does not stop a direct request.
 */
public final class FactoryConnectionServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "FactoryConnection";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new FactoryConnectionUserService();
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
		pContext.includeSchemaNodesMatching(FactoryPaths.SETTINGS);
		// audit F-02: administrators only (agent account, credentials, language-model connector) (FactoryRights.service; the handlers re-check each action server-side)
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(FactoryConnectionServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(FactoryConnectionServiceDeclaration.class, "connection.label"));
		pContext.setDescription(Messages.getInfo(FactoryConnectionServiceDeclaration.class, "connection.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return SERVICE_KEY;
	}
}
