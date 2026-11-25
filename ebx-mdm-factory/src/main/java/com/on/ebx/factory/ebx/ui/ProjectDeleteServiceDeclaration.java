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
 * The "Delete project" screen: a toolbar/perspective action on a selected {@code Project} record, or reached
 * with a {@code project} launch parameter the same way {@link ProjectWizardServiceDeclaration}'s own input
 * parameter works (the explorer's own "Delete" card button, {@code FactoryLinks#deleteUrl}) - see
 * {@link ProjectWizardUserService#setupObjectContext}'s own Javadoc for why a {@code /ebx/} web-component
 * route needs a plain query parameter where an {@code ebx-ui} route would silently drop it (CLAUDE.md's own
 * note). {@code OnTableView}, the same choice as every other project-scoped screen of this module that must
 * also open from the explorer without an actual record selection.
 * <p>
 * Administrators only, the same restriction and the same reason as {@link FactoryConnectionServiceDeclaration}:
 * the delete action performs an irreversible repository-level deletion and acts with the factory's one agent
 * account on the MCP servers ({@code ebx.ProjectDeleter}, like {@code /master/reset}).
 * {@link ProjectDeleteUserService} re-checks the role: a permission rule only hides the action, it does not
 * stop a direct request.
 */
public final class ProjectDeleteServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "ProjectDelete";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(ProjectDeleteServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new ProjectDeleteUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
		pContext.addInputParameter("project", Messages.getInfo(ProjectDeleteServiceDeclaration.class, "delete.param.project.label"),
			Messages.getInfo(ProjectDeleteServiceDeclaration.class, "delete.param.project.description"));
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT);
		// audit F-02: administrators only (FactoryRights.service; the handlers re-check each action server-side)
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(ProjectDeleteServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(ProjectDeleteServiceDeclaration.class, "delete.label"));
		pContext.setDescription(Messages.getInfo(ProjectDeleteServiceDeclaration.class, "delete.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return ProjectDeleteServiceDeclaration.SERVICE_KEY;
	}
}
