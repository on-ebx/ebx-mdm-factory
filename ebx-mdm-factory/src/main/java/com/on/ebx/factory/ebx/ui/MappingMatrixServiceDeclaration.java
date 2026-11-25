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
 * The "Mapping matrix" screen (docs/MAPPING-MATRIX.md): one project, one tab per staging entity, one
 * attribute &times; feed matrix - the graphical review surface for validating the whole landing -&gt;
 * staging mapping of a project across every source at once, complementing the attribute-by-attribute
 * Proposal review screen and the one-feed-at-a-time Feed designer. {@code OnTableView} on {@link
 * FactoryPaths#PROJECT}, the same choice and the same reason as {@link FeedDesignerServiceDeclaration}/
 * {@link ProjectWizardServiceDeclaration}: there is no fixed record to select, so the project (and the
 * optional entity to open on) travel as {@code project}/{@code entity} input parameters, resolved by
 * {@link MappingMatrixUserService#setupObjectContext} exactly as {@code FeedDesignerUserService} resolves
 * its own {@code feed} parameter.
 * <p>
 * Entry points ({@code core.MappingMatrixLink#url}): a "Mapping matrix" button next to each entity's feeds
 * on the wizard's step 4 ({@code wizard.WizardPage#renderFeed}) - never the toolbar/perspective menu,
 * same reasoning as {@link FeedDesignerServiceDeclaration}'s own Javadoc.
 */
public final class MappingMatrixServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "MappingMatrix";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(MappingMatrixServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new MappingMatrixUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.addInputParameter("project", Messages.getInfo(MappingMatrixServiceDeclaration.class, "mappingMatrix.param.project.label"),
			Messages.getInfo(MappingMatrixServiceDeclaration.class, "mappingMatrix.param.project.description"));
		pContext.addInputParameter("entity", Messages.getInfo(MappingMatrixServiceDeclaration.class, "mappingMatrix.param.entity.label"),
			Messages.getInfo(MappingMatrixServiceDeclaration.class, "mappingMatrix.param.entity.description"));
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT);
		// security review (2026-09-24): a design tool that writes models, mappings and configurations, and can send
		// project data to the language model - administrators only (the handlers re-check it server-side)
		// audit F-02: DESIGN (FactoryRights.service; the handlers re-check each action server-side)
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(MappingMatrixServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(MappingMatrixServiceDeclaration.class, "mappingMatrix.label"));
		pContext.setDescription(Messages.getInfo(MappingMatrixServiceDeclaration.class, "mappingMatrix.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return MappingMatrixServiceDeclaration.SERVICE_KEY;
	}
}
