package com.on.ebx.factory.ebx;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.orchestranetworks.schema.Path;

/**
 * JUnit-free: run the main, a failure throws. F3-03 (rule part), F3-06 (reading part), F3-02 (production review, 2026-11-12): who may write the deciding fields of
 * an agent proposal and the activation fields of an agent definition, and who reads the values copied from master records.
 */
public final class AgentTableRulesTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		AgentTableRulesTest.checkProposalIsNotAStewardTable();
		AgentTableRulesTest.checkAuthorityFieldsAreModuleOnly();
		AgentTableRulesTest.checkEveryProposalFieldIsDecided();
		AgentTableRulesTest.checkDefinitionActivationFields();
		AgentTableRulesTest.checkValueReading();
		AgentTableRulesTest.check("the activation key field is hidden from every session, a person's or the system's, and only the platform passes",
			HiddenFieldRule.hidden(true) && !HiddenFieldRule.hidden(false) && "/root/FactorySettings/agentActivationKey".equals(FactoryPaths.SETTINGS
				.add(FactoryPaths.Settings.AGENT_ACTIVATION_KEY).format()));
		System.out.println("AgentTableRulesTest OK");
	}

	private static Set<String> formats(final Path[] pPaths)
	{
		final Set<String> out = new HashSet<String>();
		for (final Path one : pPaths)
		{
			out.add(one.format());
		}
		return out;
	}

	private static void checkProposalIsNotAStewardTable()
	{
		AgentTableRulesTest.check("AgentProposal is no longer written by a steward", !AgentTableRulesTest.formats(FactoryRightAccessRule.STEWARD_TABLES).contains("/root/AgentProposal"));
		AgentTableRulesTest.check("AgentProposal is not written by a designer either", !AgentTableRulesTest.formats(FactoryRightAccessRule.DESIGN_TABLES).contains("/root/AgentProposal"));
		AgentTableRulesTest.check("AgentProposal is a module-written table", AgentTableRulesTest.formats(FactoryRightAccessRule.MODULE_WRITTEN_TABLES).contains("/root/AgentProposal"));
		// the table rule is the administrator-writes one: a steward (and a designer) only read
		AgentTableRulesTest.check("a steward reads the table, read only", "r".equals(FactoryRightAccessRule.decide(FactoryRight.STEWARD, FactoryRight.ADMINISTRATOR)));
		AgentTableRulesTest.check("a designer reads the table, read only", "r".equals(FactoryRightAccessRule.decide(FactoryRight.DESIGN, FactoryRight.ADMINISTRATOR)));
		AgentTableRulesTest.check("a viewer reads the table, read only", "r".equals(FactoryRightAccessRule.decide(FactoryRight.VIEW, FactoryRight.ADMINISTRATOR)));
		AgentTableRulesTest.check("nobody without a right sees the table", "hidden".equals(FactoryRightAccessRule.decide(null, FactoryRight.ADMINISTRATOR)));
		AgentTableRulesTest.check("the administrator may tidy rows", "rw".equals(FactoryRightAccessRule.decide(FactoryRight.ADMINISTRATOR, FactoryRight.ADMINISTRATOR)));
	}

	private static void checkAuthorityFieldsAreModuleOnly()
	{
		final Set<String> locked = AgentTableRulesTest.formats(FactoryRightAccessRule.PROPOSAL_AUTHORITY_FIELDS);
		for (final String field : new String[] { "./tool", "./action", "./arguments", "./target", "./status", "./applyAfterApproval" })
		{
			AgentTableRulesTest.check("the deciding field " + field + " is closed to a person", locked.contains(field));
		}
		AgentTableRulesTest.check("a person with a steward session cannot write a closed field", !ModuleOnlyFieldRule.writable(false, false));
		AgentTableRulesTest.check("the administrator is no exception (platform = no session only)", !ModuleOnlyFieldRule.writable(false, false));
		AgentTableRulesTest.check("the platform and the system user write", ModuleOnlyFieldRule.writable(true, false) && ModuleOnlyFieldRule.writable(false, true));
	}

	/** A field added to the table later must be put in a list: closed, value-hidden, or declared free here. */
	private static void checkEveryProposalFieldIsDecided() throws Exception
	{
		final Set<String> decided = new HashSet<String>(AgentTableRulesTest.formats(FactoryRightAccessRule.PROPOSAL_AUTHORITY_FIELDS));
		decided.addAll(AgentTableRulesTest.formats(AgentProposalReadRule.VALUE_FIELDS));
		decided.add("./id");
		decided.add("./confidence");
		final Set<String> declared = new HashSet<String>();
		for (final Field field : FactoryPaths.AgentProposal.class.getDeclaredFields())
		{
			if (Modifier.isStatic(field.getModifiers()) && field.getType() == Path.class)
			{
				declared.add(((Path) field.get(null)).format());
			}
		}
		AgentTableRulesTest.check("every AgentProposal field has a decision: " + declared + " vs " + decided, decided.equals(declared));
	}

	private static void checkDefinitionActivationFields()
	{
		final Set<String> locked = AgentTableRulesTest.formats(FactoryRightAccessRule.DEFINITION_MODULE_FIELDS);
		AgentTableRulesTest.check("enabledBy is closed to everybody", locked.contains("./enabledBy"));
		AgentTableRulesTest.check("enabled and lastRunAt are closed too", locked.contains("./enabled") && locked.contains("./lastRunAt"));
		AgentTableRulesTest.check("the absolute node path of the closed field", "/root/AgentDefinition/enabledBy".equals(FactoryPaths.AGENT_DEFINITION
			.add(FactoryPaths.AgentDefinition.ENABLED_BY).format()));
		AgentTableRulesTest.check("the definition stays a design table", AgentTableRulesTest.formats(FactoryRightAccessRule.DESIGN_TABLES).contains("/root/AgentDefinition"));
	}

	private static void checkValueReading()
	{
		AgentTableRulesTest.check("a viewer cannot read the values", !AgentProposalReadRule.mayRead(FactoryRight.VIEW, false, false));
		AgentTableRulesTest.check("a person with no right cannot", !AgentProposalReadRule.mayRead(null, false, false));
		AgentTableRulesTest.check("a steward can", AgentProposalReadRule.mayRead(FactoryRight.STEWARD, false, false));
		AgentTableRulesTest.check("a designer can", AgentProposalReadRule.mayRead(FactoryRight.DESIGN, false, false));
		AgentTableRulesTest.check("the owner profile of the agent can, with the VIEW right only", AgentProposalReadRule.mayRead(FactoryRight.VIEW, false, true));
		AgentTableRulesTest.check("an administrator can", AgentProposalReadRule.mayRead(null, true, false));
		final Set<String> hidden = AgentTableRulesTest.formats(AgentProposalReadRule.VALUE_FIELDS);
		AgentTableRulesTest.check("arguments (the value), rationale, application message (before and after) and target are the value fields",
			hidden.contains("./arguments") && hidden.contains("./rationale") && hidden.contains("./applyMessage") && hidden.contains("./target"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
