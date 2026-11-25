package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free: run the main, a failure throws. Exercises {@link DeducedPlanRule} (UX lot C4, 2026-10-14): when the tables found from the data are ready to be listed. */
public final class DeducedPlanRuleTest
{
	public static void main(final String[] pArgs)
	{
		DeducedPlanRuleTest.allDecidedRule();
		DeducedPlanRuleTest.theMeasuredDemoCrm();
		DeducedPlanRuleTest.listableFromTheCatalogsObjects();
		DeducedPlanRuleTest.jobMessage();
		System.out.println("DeducedPlanRuleTest OK");
	}

	private static void allDecidedRule()
	{
		DeducedPlanRuleTest.check("2 confirmed, none to confirm: plan at once", DeducedPlanRule.allDecided(2, 0, 0));
		DeducedPlanRuleTest.check("confirmed and left out: plan at once", DeducedPlanRule.allDecided(1, 1, 0));
		DeducedPlanRuleTest.check("only left out is still decided", DeducedPlanRule.allDecided(0, 3, 0));
		DeducedPlanRuleTest.check("one table to confirm: a person decides first", !DeducedPlanRule.allDecided(2, 0, 1));
		DeducedPlanRuleTest.check("nothing found: nothing to plan", !DeducedPlanRule.allDecided(0, 0, 0));
		DeducedPlanRuleTest.check("only tables to confirm", !DeducedPlanRule.allDecided(0, 0, 4));
	}

	/** Project UxCrmSf3: DemoCRM's Company and Contact CONFIRMED in an earlier project, no LandingTable yet. */
	private static void theMeasuredDemoCrm()
	{
		final List<UnplannedAsset> assets = Arrays.asList(new UnplannedAsset("Company", true, "CONFIRMED"), new UnplannedAsset("Contact", true, "CONFIRMED"));
		DeducedPlanRuleTest.check("DemoCRM is listable: the card offers 'List its tables'", DeducedPlanRule.listable(assets));
		DeducedPlanRuleTest.check("the job planned 2 tables: 2 tables found, ready to choose", "2 tables found, ready to choose.".equals(DeducedPlanRule.readyMessage(2)));
	}

	private static void listableFromTheCatalogsObjects()
	{
		DeducedPlanRuleTest.check("no object: not listable", !DeducedPlanRule.listable(new ArrayList<UnplannedAsset>()));
		DeducedPlanRuleTest.check("a table still to confirm: not listable",
			!DeducedPlanRule.listable(Arrays.asList(new UnplannedAsset("Company", true, "CONFIRMED"), new UnplannedAsset("Contact", true, "TO_CONFIRM"))));
		DeducedPlanRuleTest.check("objects of an official scan carry no state: never listable here",
			!DeducedPlanRule.listable(Arrays.asList(new UnplannedAsset("Account", true), new UnplannedAsset("Contact", true, ""))));
		DeducedPlanRuleTest.check("a decided table with no scanned column cannot be planned",
			!DeducedPlanRule.listable(Arrays.asList(new UnplannedAsset("Company", false, "CONFIRMED"))));
		DeducedPlanRuleTest.check("decided tables next to an object of an official scan: still listable",
			DeducedPlanRule.listable(Arrays.asList(new UnplannedAsset("Company", true, "LEFT_OUT"), new UnplannedAsset("Account", true))));
		DeducedPlanRuleTest.check("the 2-argument holder keeps its old meaning (no state)", "".equals(new UnplannedAsset("A", true).scanState));
	}

	private static void jobMessage()
	{
		DeducedPlanRuleTest.check("singular", "1 table found, ready to choose.".equals(DeducedPlanRule.readyMessage(1)));
		DeducedPlanRuleTest.check("17 tables", "17 tables found, ready to choose.".equals(DeducedPlanRule.readyMessage(17)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
