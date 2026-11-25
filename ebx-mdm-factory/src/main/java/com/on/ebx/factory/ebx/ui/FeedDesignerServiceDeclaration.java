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
 * The graphical "Feed designer" (docs/FEED-DESIGNER.md), in the spirit of ebx-connectors' own mapping
 * designer: landing columns on the left, staging attributes on the right, one link per {@code
 * AttributeMapping} rule. {@code OnTableView} on {@link FactoryPaths#PROJECT}, the SAME choice and the
 * same reason as {@link ProjectWizardServiceDeclaration}: there is no fixed record to select (a
 * {@code TransferFeed} row is not itself a natural EBX record-selection target from a toolbar), so the
 * feed to open travels as the {@code feed} input parameter, resolved by {@link
 * FeedDesignerUserService#setupObjectContext} exactly as {@code ProjectWizardUserService} resolves its
 * own {@code project} one ({@link AbstractRecordLineageUserService#param}).
 * <p>
 * Unlike {@link ProjectWizardServiceDeclaration}, this service is NOT offered as a toolbar or
 * perspective action ({@link #declareWebComponent} only declares the input parameter): opened with no
 * feed at all, it has nothing useful to show (there is no "blank" feed to design), so the only two
 * entry points are the wizard's step 4 ("Designer" button on a root feed,
 * {@code wizard.WizardPage#renderFeed}) and the Proposal review screen (a {@code FEED} line,
 * {@code review.ReviewPage}) - both built from {@link com.on.ebx.factory.core.FeedDesignerLink#url}.
 */
public final class FeedDesignerServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "FeedDesigner";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(FeedDesignerServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new FeedDesignerUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.addInputParameter("feed", Messages.getInfo(FeedDesignerServiceDeclaration.class, "feedDesigner.param.feed.label"),
			Messages.getInfo(FeedDesignerServiceDeclaration.class, "feedDesigner.param.feed.description"));
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT);
		// security review (2026-09-24): a design tool that writes models, mappings and configurations, and can send
		// project data to the language model - administrators only (the handlers re-check it server-side)
		// audit F-02: DESIGN (FactoryRights.service; the handlers re-check each action server-side)
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(FeedDesignerServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(FeedDesignerServiceDeclaration.class, "feedDesigner.label"));
		pContext.setDescription(Messages.getInfo(FeedDesignerServiceDeclaration.class, "feedDesigner.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return FeedDesignerServiceDeclaration.SERVICE_KEY;
	}
}
