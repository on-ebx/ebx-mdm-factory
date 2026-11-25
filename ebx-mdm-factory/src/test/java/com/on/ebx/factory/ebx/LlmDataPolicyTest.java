package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. {@link LlmDataPolicy} (lot B, 2026-09-24). */
public final class LlmDataPolicyTest
{
	public static void main(final String[] pArgs)
	{
		final LlmDataPolicy shapesMax = new LlmDataPolicy(LlmDataPolicy.Level.SHAPES_ONLY, null);
		LlmDataPolicyTest.check("a project asking for values gets shapes under a SHAPES_ONLY instance", shapesMax.levelFor("SAMPLE_VALUES") == LlmDataPolicy.Level.SHAPES_ONLY);
		LlmDataPolicyTest.check("no project choice: shapes", shapesMax.levelFor((String) null) == LlmDataPolicy.Level.SHAPES_ONLY);
		final LlmDataPolicy valuesMax = new LlmDataPolicy(LlmDataPolicy.Level.SAMPLE_VALUES, null);
		LlmDataPolicyTest.check("values allowed and asked", valuesMax.levelFor("SAMPLE_VALUES") == LlmDataPolicy.Level.SAMPLE_VALUES);
		LlmDataPolicyTest.check("values allowed, not asked", valuesMax.levelFor("SHAPES_ONLY") == LlmDataPolicy.Level.SHAPES_ONLY);
		final LlmDataPolicy noneMax = new LlmDataPolicy(LlmDataPolicy.Level.NONE, null);
		LlmDataPolicyTest.check("NONE wins over everything", noneMax.levelFor("SAMPLE_VALUES") == LlmDataPolicy.Level.NONE);
		LlmDataPolicyTest.check("an unreadable setting fails closed", LlmDataPolicy.level("garbage", LlmDataPolicy.Level.SAMPLE_VALUES) == LlmDataPolicy.Level.NONE);

		for (final String name : new String[] { "Password", "user_passwd", "API_KEY", "IBAN", "bic", "account_bic", "SSN", "DateOfBirth", "date_naissance",
			"HealthStatus", "Salaire_brut", "nir" })
		{
			LlmDataPolicyTest.check(name + " is sensitive by default", shapesMax.isSensitive(name));
		}
		for (final String name : new String[] { "PublicationDate", "Public", "CustomerName", "Email", "Dernier", "Mounir", "City" })
		{
			LlmDataPolicyTest.check(name + " is not sensitive by default", !shapesMax.isSensitive(name));
		}
		final LlmDataPolicy custom = new LlmDataPolicy(LlmDataPolicy.Level.SHAPES_ONLY, "email*, *phone*\nlast?name");
		LlmDataPolicyTest.check("a filled setting replaces the defaults", custom.isSensitive("EmailAddress") && custom.isSensitive("mobile_phone") && !custom.isSensitive("IBAN"));
		LlmDataPolicyTest.check("only * is a wildcard", !custom.isSensitive("lastName") && custom.isSensitive("last?name"));
		// an internal, confidential model: an administrator may lift everything explicitly
		final LlmDataPolicy internal = new LlmDataPolicy(LlmDataPolicy.Level.SAMPLE_VALUES, "none");
		LlmDataPolicyTest.check("NONE clears the sensitive list", !internal.isSensitive("Password") && !internal.isSensitive("IBAN"));
		LlmDataPolicyTest.check("and real values may go when the project asks", internal.levelFor("SAMPLE_VALUES") == LlmDataPolicy.Level.SAMPLE_VALUES);
		System.out.println("LlmDataPolicyTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
