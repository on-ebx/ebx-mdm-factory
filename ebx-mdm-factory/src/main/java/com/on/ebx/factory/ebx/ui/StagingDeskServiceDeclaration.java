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
 * The "Validation desk" screen (docs/STAGING-DESK.md): the fourth view of step 4 of the wizard, next to the wizard card, the Mapping matrix and the Feed designer - the
 * decisions of the staging regrouped by what the person actually decides. {@code OnTableView} on {@link FactoryPaths#PROJECT}, the same choice and the same reason as
 * {@link MappingMatrixServiceDeclaration}: there is no fixed record to select, so the project (and the optional business object to open on) travel as {@code project}/
 * {@code entity} input parameters ({@code core.StagingDeskLink}), resolved by {@link StagingDeskUserService#setupObjectContext}.
 * <p>
 * Entry points: the "Validation desk" link next to "Open in the matrix" in step 4 of the wizard, and the view switch in the header of the Mapping matrix - never the toolbar
 * or the perspective menu, same reasoning as {@link MappingMatrixServiceDeclaration}. Registered in {@code FactorySchemaExtensions} like the matrix.
 */
public final class StagingDeskServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "StagingDesk";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(StagingDeskServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new StagingDeskUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.addInputParameter("project", Messages.getInfo(StagingDeskServiceDeclaration.class, "stagingDesk.param.project.label"),
			Messages.getInfo(StagingDeskServiceDeclaration.class, "stagingDesk.param.project.description"));
		pContext.addInputParameter("entity", Messages.getInfo(StagingDeskServiceDeclaration.class, "stagingDesk.param.entity.label"),
			Messages.getInfo(StagingDeskServiceDeclaration.class, "stagingDesk.param.entity.description"));
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT);
		// the desk decides what the matrix decides (same writes), so it asks the same right, DESIGN (FactoryRights.service; the handlers re-check each action server-side)
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(StagingDeskServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(StagingDeskServiceDeclaration.class, "stagingDesk.label"));
		pContext.setDescription(Messages.getInfo(StagingDeskServiceDeclaration.class, "stagingDesk.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return StagingDeskServiceDeclaration.SERVICE_KEY;
	}
}
