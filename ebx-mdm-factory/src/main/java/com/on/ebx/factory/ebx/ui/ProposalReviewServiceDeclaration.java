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
 * The "Revue des propositions" screen (PLAN.md screen 3): one project's proposals from all five
 * proposal tables, as uniform lines to accept or reject. A toolbar action on {@code Project} and on
 * each of the five proposal tables (the project is then resolved from whatever record is selected,
 * see {@link ProposalReviews#projectOfRecord}), and a perspective action on its own (no record
 * selected: {@link ProposalReviewUserService} then shows the project selector). {@code
 * OnTableView}, like the data-catalog module's {@code ProductExplorerServiceDeclaration} - not
 * {@code OnRecord} like {@code TagReview} - because this screen must also open with nothing selected.
 */
public final class ProposalReviewServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "ProposalReview";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(ProposalReviewServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new ProposalReviewUserService();
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
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT, FactoryPaths.LANDING_RELATION, FactoryPaths.STAGING_ENTITY, FactoryPaths.STAGING_ATTRIBUTE,
			FactoryPaths.ATTRIBUTE_MAPPING, FactoryPaths.MATCH_PLAN);
		// audit F-02: shown to and opened by holders of VIEW (FactoryRights.service); deciding asks STEWARD, applying DESIGN (re-checked per action)
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(ProposalReviewServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(ProposalReviewServiceDeclaration.class, "review.label"));
		pContext.setDescription(Messages.getInfo(ProposalReviewServiceDeclaration.class, "review.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return ProposalReviewServiceDeclaration.SERVICE_KEY;
	}
}
