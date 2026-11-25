package com.on.ebx.factory.landing;

/** JUnit-free: run the main, a failure throws. */
public final class LandingNamesTest
{
	public static void main(final String[] pArgs)
	{
		LandingNamesTest.check("model name", "ACME_Landing_ERP".equals(LandingNames.modelName("ACME", "ERP")));
		LandingNamesTest.check("dataspace key with a configured suffix", "ACME_LZ".equals(LandingNames.dataspaceKey("ACME", "_LZ")));
		LandingNamesTest.check("dataspace key falls back to _Landing when blank", "ACME_Landing".equals(LandingNames.dataspaceKey("ACME", " ")));
		LandingNamesTest.check("dataspace key falls back to _Landing when null", "ACME_Landing".equals(LandingNames.dataspaceKey("ACME", null)));
		System.out.println("LandingNamesTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
