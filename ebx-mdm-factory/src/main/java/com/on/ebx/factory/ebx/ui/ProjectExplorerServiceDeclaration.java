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
 * The "Project explorer" screen (PLAN.md screen 6): a faceted overview of every project, same look and
 * behaviour as the connector explorer / data marketplace ({@link FacetedPage}). {@code OnTableView} on
 * {@link FactoryPaths#PROJECT}, the same choice and the same reason as {@link ProjectWizardServiceDeclaration}/
 * {@link ProposalReviewServiceDeclaration}: it is a perspective/toolbar entry with nothing necessarily
 * selected, and unlike the wizard or proposal review it never needs a specific record at all - it always shows
 * the whole table (the read of every {@code Project} row goes through {@code selection.getAllRecords()} in
 * {@link ProjectExplorerUserService}, not the filtered table-view selection, precisely because this screen IS
 * the overview and must never silently hide a project behind a leftover native filter).
 * <p>
 * Set as the perspective's new default "Projects" entry (PLAN.md "Decisions prises", 2026-09-23): see
 * {@code MdmFactory.perspective.json} and {@code docs/PROJECT-EXPLORER.md} for what happens to an
 * already-installed perspective at the next start.
 */
public final class ProjectExplorerServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "ProjectExplorer";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(ProjectExplorerServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new ProjectExplorerUserService();
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
		// audit F-02: shown to and opened by holders of VIEW (FactoryRights.service), deny by default
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(ProjectExplorerServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(ProjectExplorerServiceDeclaration.class, "explorer.label"));
		pContext.setDescription(Messages.getInfo(ProjectExplorerServiceDeclaration.class, "explorer.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return ProjectExplorerServiceDeclaration.SERVICE_KEY;
	}
}
