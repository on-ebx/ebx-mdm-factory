package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.Session;

/**
 * Audit F-02: the Factory rights applied to the rows of the MdmFactory dataset themselves, so that the EBX screens and Data
 * Services follow the same model as the module's own entry points. A session that holds the write right of the table may
 * write (as far as the dataspace permissions also allow), a VIEW holder only reads, a session with no Factory right sees
 * nothing. The administrator and the system user always write.
 * <p>
 * An access rule only RESTRICTS: a designer or a steward still needs read-write on the dataspace {@code MdmFactory} in the
 * EBX permissions, because most of the factory's own writes run with the person's session (docs/OPERATIONS.md section 4).
 * The tables that keep their own rule are not listed here: settings, language-model logs and access rights
 * (administrators only), favorites (own rows), the job queue (read only to everybody but an administrator).
 * <p>
 * The STEWARD tables are the ones a steward's decisions write: the statuses of the proposals (relations, entities,
 * attributes, mappings, feeds, mapping steps, quality rules, capabilities, match plans), the stored matching results
 * (a PipelineStep detail) and the cross-references. A record-level rule cannot tell a status from the rest of the row:
 * a steward who edits these tables by hand outside the factory screens can change more than a status (documented residual).
 */
public final class FactoryRightAccessRule implements AccessRule
{
	static final UserMessage READ_ONLY = UserMessage.createInfo("MDM Factory: your right does not cover changing this table.");

	/** Tables whose rows a STEWARD may write. */
	static final Path[] STEWARD_TABLES = { FactoryPaths.LANDING_RELATION, FactoryPaths.STAGING_ENTITY, FactoryPaths.STAGING_ATTRIBUTE,
		FactoryPaths.ATTRIBUTE_MAPPING, FactoryPaths.TRANSFER_FEED, FactoryPaths.MAPPING_STEP, FactoryPaths.QUALITY_RULE, FactoryPaths.CAPABILITY_SUGGESTION,
		FactoryPaths.MATCH_PLAN, FactoryPaths.PIPELINE_STEP, FactoryPaths.XREF,
		// source drift lot (2026-10-30): a steward decides a schema change; applying it needs DESIGN (the route)
		FactoryPaths.SOURCE_DRIFT };
	/**
	 * Production review of 2026-11-12 (F3-03): the tables whose rows the module writes itself and an administrator may only tidy. An agent's proposal is the
	 * authority at the review (what will be run, on which record, with which value): a steward who could edit it would have the owner of the agent run what the
	 * steward wrote. The person decides through the route or the review task; the fields that decide are closed for everybody ({@link ModuleOnlyFieldRule}).
	 */
	static final Path[] MODULE_WRITTEN_TABLES = { FactoryPaths.AGENT_PROPOSAL };
	/** The fields of {@code AgentProposal} that decide what is run: only the module writes them. */
	static final Path[] PROPOSAL_AUTHORITY_FIELDS = { FactoryPaths.AgentProposal.TOOL, FactoryPaths.AgentProposal.ACTION, FactoryPaths.AgentProposal.ARGUMENTS,
		FactoryPaths.AgentProposal.TARGET, FactoryPaths.AgentProposal.STATUS, FactoryPaths.AgentProposal.APPLY_AFTER_APPROVAL, FactoryPaths.AgentProposal.RUN,
		FactoryPaths.AgentProposal.AGENT, FactoryPaths.AgentProposal.PROJECT, FactoryPaths.AgentProposal.DECIDED_BY, FactoryPaths.AgentProposal.DECIDED_AT };
	/**
	 * Production review of 2026-11-12 (F3-02): the fields of {@code AgentDefinition} that say who an agent acts for and whether it runs: set by the enable and
	 * disable routes ({@link AgentIdentity}) and by the runner, never typed.
	 */
	static final Path[] DEFINITION_MODULE_FIELDS = { FactoryPaths.AgentDefinition.ENABLED, FactoryPaths.AgentDefinition.ENABLED_BY, FactoryPaths.AgentDefinition.LAST_RUN_AT };
	/** Tables whose rows only a DESIGN holder may write. */
	static final Path[] DESIGN_TABLES = { FactoryPaths.PROJECT, FactoryPaths.PROJECT_SOURCE, FactoryPaths.LANDING_TABLE, FactoryPaths.LANDING_COLUMN,
		FactoryPaths.REFERENCE_BINDING, FactoryPaths.TRANSFER_REJECT, FactoryPaths.DISTRIBUTION_TARGET, FactoryPaths.DISTRIBUTION_EVENT, FactoryPaths.LOAD_PAUSE,
		FactoryPaths.INTAKE_CHAIN,
		// lot C1-B: the CENTRALISATION path - the chosen packs, the differences (decided from the wizard, a DESIGN action) and what the generation wrote
		FactoryPaths.PROJECT_PACK, FactoryPaths.DIFFERENCE, FactoryPaths.DOMAIN_OBJECT,
		// agentic framework lot (2026-10-30): declaring an agent (its tools, its autonomy) is a design decision
		FactoryPaths.AGENT_DEFINITION };

	private final FactoryRight write;

	FactoryRightAccessRule(final FactoryRight pWrite)
	{
		this.write = pWrite;
	}

	/** Pure: what a holder of {@code pGranted} (null = no right) gets on a table written with {@code pWrite}: "rw", "r" or "hidden". */
	static String decide(final FactoryRight pGranted, final FactoryRight pWrite)
	{
		if (FactoryRight.allows(pGranted, pWrite))
		{
			return "rw";
		}
		return pGranted != null && pGranted.covers(FactoryRight.VIEW) ? "r" : "hidden";
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		if (pSession == null)
		{
			return AccessPermission.getReadWrite(); // the platform itself (a trigger context, a startup procedure)
		}
		final String decided = FactoryRightAccessRule.decide(FactoryAccess.granted(pSession), this.write);
		return "rw".equals(decided) ? AccessPermission.getReadWrite()
			: "r".equals(decided) ? AccessPermission.getReadOnly(FactoryRightAccessRule.READ_ONLY) : AccessPermission.getHidden();
	}
}
