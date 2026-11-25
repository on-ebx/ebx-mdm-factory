package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. The startup self-test of {@link SessionToken} (audit F-16): what its one ERROR line says, and that a check with nothing to mint for never throws. */
public final class SessionTokenStartupTest
{
	public static void main(final String[] pArgs)
	{
		SessionTokenStartupTest.check("nothing checked yet", SessionToken.startupMintable() == null);

		final String forbidden = SessionToken.startupFailureMessage("java.lang.NoSuchMethodError: createToken", false);
		SessionTokenStartupTest.check("it names the cause", forbidden.contains("NoSuchMethodError: createToken"));
		SessionTokenStartupTest.check("fallback forbidden: the consequence is that every MCP call fails", forbidden.contains("will FAIL") && forbidden.contains("/ebx-assistant/mcp/v1/"));
		final String allowed = SessionToken.startupFailureMessage("EBX returned an empty token", true);
		SessionTokenStartupTest.check("fallback allowed: the consequence is the agent account, not a failure", allowed.contains("AGENT ACCOUNT") && !allowed.contains("will FAIL"));
		SessionTokenStartupTest.check("both point at the likely cause and the setting", forbidden.contains("JsonWebTokenHandler") && forbidden.contains("agentFallback") && allowed.contains("agentFallback"));

		// no repository and no session: the check must record "not mintable" and never throw (it runs inside module startup)
		SessionToken.startupCheck(null, null);
		SessionTokenStartupTest.check("a check with nothing to mint for records false", Boolean.FALSE.equals(SessionToken.startupMintable()));
		System.out.println("SessionTokenStartupTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
