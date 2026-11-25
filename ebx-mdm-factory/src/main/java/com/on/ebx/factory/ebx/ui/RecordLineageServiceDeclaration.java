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
 * The "Lineage de l'enregistrement" / "Record lineage" screen (PLAN.md lot 4, mockup screen 5): given a
 * project, a promoted master entity and a formatted master key, shows the record's journey (source system
 * &rarr; landing row &rarr; staging row, per contributing source), a field-by-field contribution table, and
 * the record's Match and Merge state. {@code OnTableView} on {@link FactoryPaths#PROJECT}, the same choice
 * and the same reason as {@link ProposalReviewServiceDeclaration}/{@link ProjectWizardServiceDeclaration}:
 * it must also open with nothing selected (a perspective action, or a deep link with no {@code Project}
 * record in play at all).
 * <p>
 * <b>{@code project}/{@code entity}/{@code key} are declared as INPUT parameters</b> so the documented deep
 * link (see {@code docs/RECORD-LINEAGE.md}, "Screen": {@code .../ebx/?branch=MdmFactory&instance=MdmFactory
 * &xpath=/root/Project&service=RecordLineage&project=...&entity=...&key=...}) actually reaches {@link
 * RecordLineageUserService#setupObjectContext} - CLAUDE.md's own note that ebx-ui drops a PLAIN query
 * parameter of a user service is about the {@code ebx-ui} routes specifically; the {@code /ebx/} web
 * component route this link uses passes them through as the service's own launch parameters instead
 * (verified live for other services on this instance's perspective, `docs/*-PERSPECTIVE-*` notes), which
 * {@link com.orchestranetworks.userservice.UserServiceRequest#getParameter(String)} then reads. The master
 * model is DMA-generated (not owned by this module), so it cannot carry a toolbar/record service of its own
 * pointing here directly - the deep link is the only way in from a master record, until the factory can
 * declare one through the data-model tooling (see this lot's own report for what that would take).
 */
public final class RecordLineageServiceDeclaration implements UserServiceDeclaration.OnTableView
{
	public static final String SERVICE_NAME = "RecordLineage";
	public static final ServiceKey SERVICE_KEY = ServiceKey.forName(RecordLineageServiceDeclaration.SERVICE_NAME);

	@Override
	public UserService<TableViewEntitySelection> createUserService()
	{
		return new RecordLineageUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		pContext.setAvailableAsToolbarAction(true);
		pContext.setAvailableAsPerspectiveAction(true);
		pContext.addInputParameter("project", Messages.getInfo(RecordLineageServiceDeclaration.class, "lineage.param.project.label"),
			Messages.getInfo(RecordLineageServiceDeclaration.class, "lineage.param.project.description"));
		pContext.addInputParameter("entity", Messages.getInfo(RecordLineageServiceDeclaration.class, "lineage.param.entity.label"),
			Messages.getInfo(RecordLineageServiceDeclaration.class, "lineage.param.entity.description"));
		pContext.addInputParameter("key", Messages.getInfo(RecordLineageServiceDeclaration.class, "lineage.param.key.label"),
			Messages.getInfo(RecordLineageServiceDeclaration.class, "lineage.param.key.description"));
	}

	@Override
	public void defineActivation(final ActivationContextOnTableView pContext)
	{
		pContext.includeSchemaNodesMatching(FactoryPaths.PROJECT);
		// audit F-02: shown to and opened by holders of VIEW (FactoryRights.service), deny by default
		pContext.setPermissionRule(com.on.ebx.factory.ebx.FactoryAccess.<com.orchestranetworks.ui.selection.TableViewEntitySelection> rule(com.on.ebx.factory.core.FactoryRights.service(RecordLineageServiceDeclaration.SERVICE_NAME)));
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setLabel(Messages.getInfo(RecordLineageServiceDeclaration.class, "lineage.label"));
		pContext.setDescription(Messages.getInfo(RecordLineageServiceDeclaration.class, "lineage.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return RecordLineageServiceDeclaration.SERVICE_KEY;
	}
}
