package com.on.ebx.factory.intake;

/** JUnit-free: {@link IntakeSwitchRules}. */
public final class IntakeSwitchRulesTest
{
	public static void main(final String[] pArgs)
	{
		check("allowed when no chain runs", IntakeSwitchRules.refuseSwitchOff(false) == null);
		final String refusal = IntakeSwitchRules.refuseSwitchOff(true);
		check("refused while a chain runs", refusal != null);
		check("and the sentence says what to do", refusal.contains("pause") && refusal.contains("wait") && refusal.contains("switch it off"));
		System.out.println("IntakeSwitchRulesTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
