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
 * The "Assistant de projet" wizard: a toolbar and perspective action on the {@code Project} table
 * (PLAN.md screen 1) - "New project (wizard)" with nothing selected (a blank draft, step 1), or on a
 * selected {@code Project} row to resume it (opens on the first step that still has work to do, see
 * {@code WizardRules#suggestedStep}). {@code OnTableView}, same choice and the same reason as {@link
 * ProposalReviewServiceDeclaration}: it must also open with nothing selected. Audit F-02: shown to and opened by
 * holders of the MDM Factory right VIEW ({@code core.FactoryRights.service}); below DESIGN it renders read only, and
 * each action asks its own right again in {@link ProjectWizardUserService#handle} ({@code core.FactoryRights.wizardAction}).
 */
public final class ProjectWizardServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "ProjectWizard";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(ProjectWizardServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new ProjectWizardUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
		// project explorer lot (2026-09-23): a card's "Wizard" link (FactoryLinks#wizardUrl) reaches
		// ProjectWizardUserService#setupObjectContext through this INPUT parameter - same technique and same
		// reasoning as RecordLineageServiceDeclaration's own project/entity/key parameters (its own Javadoc
		// explains why the /ebx/ web component route passes a plain query parameter through where an ebx-ui
		// route would silently drop it).
		pContext.addInputParameter("project", Messages.getInfo(ProjectWizardServiceDeclaration.class, "wizard.param.project.label"),
			Messages.getInfo(ProjectWizardServiceDeclaration.class, "wizard.param.project.description"));
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT);
		// security review (2026-09-24): a design tool that writes models, mappings and configurations, and can send
		// project data to the language model - administrators only (the handlers re-check it server-side)
		// audit F-02: VIEW opens the wizard read only, each action asks its own right (FactoryRights.service; the handlers re-check each action server-side)
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(ProjectWizardServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(ProjectWizardServiceDeclaration.class, "wizard.label"));
		pContext.setDescription(Messages.getInfo(ProjectWizardServiceDeclaration.class, "wizard.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return ProjectWizardServiceDeclaration.SERVICE_KEY;
	}
}
