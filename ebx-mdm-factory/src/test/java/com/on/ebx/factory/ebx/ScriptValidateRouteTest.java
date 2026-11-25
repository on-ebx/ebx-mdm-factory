package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. Audit F2-10. */
public final class ScriptValidateRouteTest
{
	public static void main(final String[] pArgs)
	{
		ScriptValidateRouteTest.check("plain path unchanged", "data-model/Companies_Staging/root/Contact/phone/@nodeConstraint/Contact_validate".equals(
			ScriptValidateRoute.encodeIdePath("data-model/Companies_Staging/root/Contact/phone/@nodeConstraint/Contact_validate")));
		final String encoded = ScriptValidateRoute.encodeIdePath("data-model/M/root/Compte client #1/a?b%c/@trigger/X");
		ScriptValidateRouteTest.check("space, #, ? and % encoded, / kept: " + encoded, "data-model/M/root/Compte%20client%20%231/a%3Fb%25c/@trigger/X".equals(encoded));
		ScriptValidateRouteTest.check("accent encoded as UTF-8", "a/%C3%A9".equals(ScriptValidateRoute.encodeIdePath("a/\u00E9")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
