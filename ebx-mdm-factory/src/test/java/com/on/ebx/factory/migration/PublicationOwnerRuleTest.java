package com.on.ebx.factory.migration;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class PublicationOwnerRuleTest
{
	public static void main(final String[] pArgs)
	{
		PublicationOwnerRuleTest.check("an owner already set is kept",
			PublicationOwnerRule.decide("PersVol7_Master", "PersVol7_Master") == PublicationOwnerRule.Action.KEEP);
		PublicationOwnerRuleTest.check("a different owner is never overwritten",
			PublicationOwnerRule.decide("Other", "Personnes_Master") == PublicationOwnerRule.Action.KEEP);
		PublicationOwnerRuleTest.check("an empty owner takes the linked model",
			PublicationOwnerRule.decide(null, "Personnes_Master") == PublicationOwnerRule.Action.SET);
		PublicationOwnerRuleTest.check("a blank owner counts as empty",
			PublicationOwnerRule.decide("  ", "Personnes_Master") == PublicationOwnerRule.Action.SET);
		PublicationOwnerRuleTest.check("no linked model name: left alone",
			PublicationOwnerRule.decide(null, null) == PublicationOwnerRule.Action.UNKNOWN);
		System.out.println("PublicationOwnerRuleTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
