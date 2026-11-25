package com.on.ebx.factory.ebx;

import com.orchestranetworks.schema.SchemaExtensions;
import com.orchestranetworks.schema.SchemaExtensionsContext;

/**
 * Registered by MdmFactory.xsd ({@code osd:extensions}). Services on the factory tables are declared
 * here rather than on the module so that an XSD change in development mode picks them up without a
 * Tomcat restart (the Java classes themselves still need one).
 */
public final class FactorySchemaExtensions implements SchemaExtensions
{
	@Override
	public void defineExtensions(final SchemaExtensionsContext pContext)
	{
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.FactoryConnectionServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.FactoryCockpitServiceDeclaration());
		// audit F-38 (wave 10): the steward's review of the transfer rejects of a project
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.TransferRejectReviewServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.ProposalReviewServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.ProjectWizardServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.RecordLineageServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.ProjectExplorerServiceDeclaration());
		// About page: version and documentation, open to every holder of a Factory right
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.AboutServiceDeclaration());
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.ProjectDeleteServiceDeclaration());
		// feed-designer lot (2026-09-23): graphical mapping designer, opened from the wizard's step 4 and
		// from a FEED review line (com.on.ebx.factory.core.FeedDesignerLink) - never from the toolbar/
		// perspective menu, see FeedDesignerServiceDeclaration's own Javadoc for why.
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.FeedDesignerServiceDeclaration());
		// mapping-matrix lot (2026-09-23, docs/MAPPING-MATRIX.md): attribute x feed review of a whole project
		// at once, opened from the wizard's step 4 - same "no toolbar/perspective menu entry" reasoning as
		// FeedDesignerServiceDeclaration just above.
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.MappingMatrixServiceDeclaration());
		// validation-desk lot L1 (2026-10-22, docs/STAGING-DESK.md): the same decisions regrouped by what the person decides - fourth view of step 4, same
		// "no toolbar/perspective menu entry" reasoning as the matrix just above
		pContext.registerUserService(new com.on.ebx.factory.ebx.ui.StagingDeskServiceDeclaration());
		// favorites of the project explorer: each user sees their own, nobody edits them by hand - same
		// registration ConnectorsSchemaExtensions does for ebx-connectors' own Favorite table
		pContext.setAccessRuleOnOccurrence(com.on.ebx.factory.core.FactoryPaths.FAVORITE, new FavoriteAccessRule());
		// security review (2026-09-24): settings and language-model logs, administrators only
		pContext.setAccessRuleOnOccurrence(com.on.ebx.factory.core.FactoryPaths.SETTINGS, new AdminOnlyAccessRule());
		pContext.setAccessRuleOnOccurrence(com.on.ebx.factory.core.FactoryPaths.AGENT_RUN, new AdminOnlyAccessRule());
		pContext.setAccessRuleOnOccurrence(com.on.ebx.factory.core.FactoryPaths.AGENT_STEP, new AdminOnlyAccessRule());
		// audit F-02: who may use the factory below the administrator - administrators only, like the settings
		pContext.setAccessRuleOnOccurrence(com.on.ebx.factory.core.FactoryPaths.ACCESS_RIGHT, new AdminOnlyAccessRule());
		// audit F-02: the other tables follow the Factory rights too (hidden without a right, read only for VIEW, written by
		// STEWARD or DESIGN as each table needs) - see FactoryRightAccessRule
		final FactoryRightAccessRule stewardWrites = new FactoryRightAccessRule(com.on.ebx.factory.core.FactoryRight.STEWARD);
		for (final com.orchestranetworks.schema.Path table : FactoryRightAccessRule.STEWARD_TABLES)
		{
			pContext.setAccessRuleOnOccurrence(table, stewardWrites);
		}
		final FactoryRightAccessRule designWrites = new FactoryRightAccessRule(com.on.ebx.factory.core.FactoryRight.DESIGN);
		for (final com.orchestranetworks.schema.Path table : FactoryRightAccessRule.DESIGN_TABLES)
		{
			pContext.setAccessRuleOnOccurrence(table, designWrites);
		}
		// production review 2026-11-12 (F3-02, F3-03, F3-06): the agent tables. A proposal is written by the module only (an administrator may tidy rows), its
		// deciding fields are closed for everybody, its values are hidden from those who cannot decide; who an agent acts for is signed by the enable route
		final FactoryRightAccessRule adminWrites = new FactoryRightAccessRule(com.on.ebx.factory.core.FactoryRight.ADMINISTRATOR);
		for (final com.orchestranetworks.schema.Path table : FactoryRightAccessRule.MODULE_WRITTEN_TABLES)
		{
			pContext.setAccessRuleOnOccurrence(table, adminWrites);
		}
		final ModuleOnlyFieldRule moduleOnly = new ModuleOnlyFieldRule();
		for (final com.orchestranetworks.schema.Path field : FactoryRightAccessRule.PROPOSAL_AUTHORITY_FIELDS)
		{
			pContext.setAccessRuleOnNode(com.on.ebx.factory.core.FactoryPaths.AGENT_PROPOSAL.add(field), moduleOnly);
			pContext.setAccessRuleForCreateOnNode(com.on.ebx.factory.core.FactoryPaths.AGENT_PROPOSAL.add(field), moduleOnly);
		}
		final AgentProposalReadRule proposalValues = new AgentProposalReadRule();
		for (final com.orchestranetworks.schema.Path field : AgentProposalReadRule.VALUE_FIELDS)
		{
			pContext.setAccessRuleOnNode(com.on.ebx.factory.core.FactoryPaths.AGENT_PROPOSAL.add(field), proposalValues);
		}
		for (final com.orchestranetworks.schema.Path field : FactoryRightAccessRule.DEFINITION_MODULE_FIELDS)
		{
			pContext.setAccessRuleOnNode(com.on.ebx.factory.core.FactoryPaths.AGENT_DEFINITION.add(field), moduleOnly);
			pContext.setAccessRuleForCreateOnNode(com.on.ebx.factory.core.FactoryPaths.AGENT_DEFINITION.add(field), moduleOnly);
		}
		// the HMAC key of the activations: hidden from every person, administrators included
		pContext.setAccessRuleOnNode(com.on.ebx.factory.core.FactoryPaths.SETTINGS.add(com.on.ebx.factory.core.FactoryPaths.Settings.AGENT_ACTIVATION_KEY), new HiddenFieldRule());
		// the style of a project is fixed at creation (WizardRules.styleMayBeSaved): read only on a saved project, and (1.4.2, F3-13) closed at creation too - only the wizard,
		// for an open style, writes it (ProjectStyleAccessRule), so a "coming soon" style cannot be created by Data Services or by the table's form
		final ProjectStyleAccessRule projectStyle = new ProjectStyleAccessRule();
		pContext.setAccessRuleOnNode(com.on.ebx.factory.core.FactoryPaths.PROJECT.add(com.on.ebx.factory.core.FactoryPaths.Project.STYLE), projectStyle);
		pContext.setAccessRuleForCreateOnNode(com.on.ebx.factory.core.FactoryPaths.PROJECT.add(com.on.ebx.factory.core.FactoryPaths.Project.STYLE), projectStyle);
		// the job queue is executed under the system session for the user a row names: read only to everybody but an
		// administrator, creation closed field by field (see FactoryJobAccessRule)
		final FactoryJobAccessRule jobRule = new FactoryJobAccessRule();
		pContext.setAccessRuleOnOccurrence(com.on.ebx.factory.core.FactoryPaths.FACTORY_JOB, jobRule);
		for (final com.orchestranetworks.schema.Path field : FactoryJobAccessRule.CREATE_LOCKED_FIELDS)
		{
			pContext.setAccessRuleForCreateOnNode(com.on.ebx.factory.core.FactoryPaths.FACTORY_JOB.add(field), jobRule);
		}
	}
}
