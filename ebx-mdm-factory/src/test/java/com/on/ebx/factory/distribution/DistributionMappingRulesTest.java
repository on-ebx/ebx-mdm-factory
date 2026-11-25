package com.on.ebx.factory.distribution;

import java.util.Arrays;
import java.util.List;

import com.on.ebx.factory.distribution.DistributionMappingRules.Rule;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class DistributionMappingRulesTest
{
	public static void main(final String[] pArgs)
	{
		DistributionMappingRulesTest.keyAndSourceSystemAlwaysPresent();
		DistributionMappingRulesTest.businessFieldsBecomePlainPassThroughRules();
		DistributionMappingRulesTest.mameAndBookkeepingFieldsExcluded();
		DistributionMappingRulesTest.duplicateBusinessFieldNotDuplicated();
		DistributionMappingRulesTest.noCrosswalkNoGoldenIdRule();
		DistributionMappingRulesTest.crosswalkAddsGoldenIdRule();
		DistributionMappingRulesTest.mergeKeepsHandAddedRule();
		DistributionMappingRulesTest.mergeOverwritesOwnTargetPath();
		DistributionMappingRulesTest.everyMappedRecordSaysUpsert();
		System.out.println("DistributionMappingRulesTest OK");
	}

	private static void keyAndSourceSystemAlwaysPresent()
	{
		final List<Rule> rules = DistributionMappingRules.build(Arrays.asList("name"), false, "/root/Company", "Companies_Master", "Master");
		DistributionMappingRulesTest.check("id rule present", DistributionMappingRulesTest.find(rules, "id") != null);
		final Rule id = DistributionMappingRulesTest.find(rules, "id");
		DistributionMappingRulesTest.check("id sourcePath is ./id", "./id".equals(id.sourcePath));
		DistributionMappingRulesTest.check("id policy ALWAYS", "ALWAYS".equals(id.policy));
		DistributionMappingRulesTest.check("sourceSystem rule present", DistributionMappingRulesTest.find(rules, "sourceSystem") != null);
		DistributionMappingRulesTest.check("sourceId rule present", DistributionMappingRulesTest.find(rules, "sourceId") != null);
	}

	private static void businessFieldsBecomePlainPassThroughRules()
	{
		final List<Rule> rules = DistributionMappingRules.build(Arrays.asList("name", "email"), false, "/root/Company", "Companies_Master", "Master");
		final Rule name = DistributionMappingRulesTest.find(rules, "name");
		DistributionMappingRulesTest.check("business field rule exists", name != null);
		DistributionMappingRulesTest.check("sourcePath is ./name", "./name".equals(name.sourcePath));
		DistributionMappingRulesTest.check("no transformation", "".equals(name.transformation));
		DistributionMappingRulesTest.check("policy ALWAYS", "ALWAYS".equals(name.policy));
		DistributionMappingRulesTest.check("email field rule exists too", DistributionMappingRulesTest.find(rules, "email") != null);
	}

	private static void mameAndBookkeepingFieldsExcluded()
	{
		final List<Rule> rules = DistributionMappingRules.build(
			Arrays.asList("name", "Linked_Field_State", "GoldenId", "GroupId", "HasSuspects", "AutoCreated", "FunctionalId", "_loadId", "_receivedAt"), false,
			"/root/Company", "Companies_Master", "Master");
		DistributionMappingRulesTest.check("only 'id'/'sourceSystem'/'sourceId'/'name' and the _change marker - 5 rules", rules.size() == 5);
		DistributionMappingRulesTest.check("Linked_Field_State excluded", DistributionMappingRulesTest.find(rules, "Linked_Field_State") == null);
		DistributionMappingRulesTest.check("GoldenId excluded (crosswalk off)", DistributionMappingRulesTest.find(rules, "GoldenId") == null);
		DistributionMappingRulesTest.check("_loadId excluded", DistributionMappingRulesTest.find(rules, "_loadId") == null);
		DistributionMappingRulesTest.check("_receivedAt excluded", DistributionMappingRulesTest.find(rules, "_receivedAt") == null);
	}

	private static void duplicateBusinessFieldNotDuplicated()
	{
		final List<Rule> rules = DistributionMappingRules.build(Arrays.asList("name", "name", "id", "sourceSystem"), false, "/root/Company", "Companies_Master", "Master");
		DistributionMappingRulesTest.check("id/sourceSystem/sourceId/name and the _change marker - exactly 5 rules, no duplicates", rules.size() == 5);
	}

	/** Lot S2: every message of a mapping carries the change type, a constant (Connectors sends the record's full current state). */
	private static void everyMappedRecordSaysUpsert()
	{
		final List<Rule> rules = DistributionMappingRules.build(Arrays.asList("name"), false, "/root/Company", "Companies_Master", "Master");
		final Rule change = DistributionMappingRulesTest.find(rules, "_change");
		DistributionMappingRulesTest.check("a _change rule exists", change != null);
		DistributionMappingRulesTest.check("it is the constant UPSERT", "constant(UPSERT)".equals(change.transformation));
		DistributionMappingRulesTest.check("it replaces an existing rule of the same target on merge",
			DistributionMappingRules.merge(rules, Arrays.asList(new Rule("./x", "_change", "constant(OLD)", "ALWAYS"))).size() == rules.size());
	}

	private static void noCrosswalkNoGoldenIdRule()
	{
		final List<Rule> rules = DistributionMappingRules.build(Arrays.asList("name"), false, "/root/Company", "Companies_Master", "Master");
		DistributionMappingRulesTest.check("no goldenId rule without crosswalk", DistributionMappingRulesTest.find(rules, "goldenId") == null);
	}

	private static void crosswalkAddsGoldenIdRule()
	{
		final List<Rule> rules = DistributionMappingRules.build(Arrays.asList("name"), true, "/root/Company", "Companies_Master", "Master");
		final Rule golden = DistributionMappingRulesTest.find(rules, "goldenId");
		DistributionMappingRulesTest.check("goldenId rule present with crosswalk", golden != null);
		DistributionMappingRulesTest.check("sourcePath is ./id", "./id".equals(golden.sourcePath));
		DistributionMappingRulesTest.check("transformation is golden(tablePath,dataspace,dataset)",
			"golden(/root/Company,Companies_Master,Master)".equals(golden.transformation));
	}

	private static void mergeKeepsHandAddedRule()
	{
		final List<Rule> generated = DistributionMappingRules.build(Arrays.asList("name"), false, "/root/Company", "Companies_Master", "Master");
		final Rule handAdded = new Rule("./region", "REGION_CODE", "", "IF_EMPTY");
		final List<Rule> merged = DistributionMappingRules.merge(generated, Arrays.asList(handAdded));
		DistributionMappingRulesTest.check("hand-added rule survives", DistributionMappingRulesTest.find(merged, "REGION_CODE") != null);
		DistributionMappingRulesTest.check("generated rules still present", DistributionMappingRulesTest.find(merged, "name") != null);
		DistributionMappingRulesTest.check("hand-added rule appended after generated ones", merged.get(merged.size() - 1) == handAdded);
	}

	private static void mergeOverwritesOwnTargetPath()
	{
		final List<Rule> generated = DistributionMappingRules.build(Arrays.asList("name"), false, "/root/Company", "Companies_Master", "Master");
		// an "existing" rule on a targetPath this class itself generates (e.g. read back from a mapping this
		// class configured before) must never survive twice - the generated one always wins.
		final Rule staleName = new Rule("./oldName", "name", "", "IF_EMPTY");
		final List<Rule> merged = DistributionMappingRules.merge(generated, Arrays.asList(staleName));
		int nameCount = 0;
		for (final Rule rule : merged)
		{
			if ("name".equals(rule.targetPath))
			{
				nameCount++;
			}
		}
		DistributionMappingRulesTest.check("exactly one 'name' rule after merge, the generated one", nameCount == 1);
		DistributionMappingRulesTest.check("it is the generated (ALWAYS) one, not the stale IF_EMPTY one", "ALWAYS".equals(DistributionMappingRulesTest.find(merged, "name").policy));
	}

	private static Rule find(final List<Rule> pRules, final String pTargetPath)
	{
		for (final Rule rule : pRules)
		{
			if (rule.targetPath.equals(pTargetPath))
			{
				return rule;
			}
		}
		return null;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
