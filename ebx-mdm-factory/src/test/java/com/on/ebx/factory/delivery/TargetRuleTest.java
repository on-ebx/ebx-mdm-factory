package com.on.ebx.factory.delivery;

/** JUnit-free: run the main, a failure throws. */
public final class TargetRuleTest
{
	public static void main(final String[] pArgs)
	{
		TargetRuleTest.check("TARGET refuses creation", !TargetRule.projectCreationAllowed("TARGET"));
		TargetRuleTest.check("DEVELOPMENT allows creation", TargetRule.projectCreationAllowed("DEVELOPMENT"));
		TargetRuleTest.check("null role (unstamped settings row) allows creation", TargetRule.projectCreationAllowed(null));
		TargetRuleTest.check("blank role allows creation", TargetRule.projectCreationAllowed("  "));
		TargetRuleTest.check("surrounding blanks around TARGET still refuse", !TargetRule.projectCreationAllowed(" TARGET "));
		TargetRuleTest.check("any other value allows creation, never a silent refusal on a typo", TargetRule.projectCreationAllowed("target"));
		TargetRuleTest.check("the refusal names EBX Staging as the arrival path", TargetRule.refusal().indexOf("EBX Staging") >= 0);
		System.out.println("TargetRuleTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
