package com.on.ebx.factory.delivery;

/** JUnit-free: run the main, a failure throws. */
public final class OriginRuleTest
{
	public static void main(final String[] pArgs)
	{
		OriginRuleTest.check("same instance: local", !OriginRule.isDelivered("a1", "a1"));
		OriginRuleTest.check("different instance: delivered", OriginRule.isDelivered("a1", "b2"));
		OriginRuleTest.check("unstamped project (older row): local", !OriginRule.isDelivered(null, "b2") && !OriginRule.isDelivered("  ", "b2"));
		OriginRuleTest.check("unstamped instance (settings row not yet completed): local, never a refusal by accident", !OriginRule.isDelivered("a1", null));
		OriginRuleTest.check("surrounding blanks ignored", !OriginRule.isDelivered(" a1 ", "a1"));
		OriginRuleTest.check("the refusal names the project and its origin",
			OriginRule.refusal("Companies", "a1").startsWith("Project Companies was delivered from instance a1"));
		System.out.println("OriginRuleTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
