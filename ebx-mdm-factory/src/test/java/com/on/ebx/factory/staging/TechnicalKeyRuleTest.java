package com.on.ebx.factory.staging;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class TechnicalKeyRuleTest
{
	public static void main(final String[] pArgs)
	{
		TechnicalKeyRuleTest.isAcceptedLikeOnlyAcceptedOrApplied();
		TechnicalKeyRuleTest.cascadedStatusFromEntityStatus();
		System.out.println("TechnicalKeyRuleTest OK");
	}

	private static void isAcceptedLikeOnlyAcceptedOrApplied()
	{
		TechnicalKeyRuleTest.check("ACCEPTED is accepted-like", TechnicalKeyRule.isAcceptedLike("ACCEPTED"));
		TechnicalKeyRuleTest.check("APPLIED is accepted-like", TechnicalKeyRule.isAcceptedLike("APPLIED"));
		TechnicalKeyRuleTest.check("PROPOSED is not", !TechnicalKeyRule.isAcceptedLike("PROPOSED"));
		TechnicalKeyRuleTest.check("REJECTED is not", !TechnicalKeyRule.isAcceptedLike("REJECTED"));
		TechnicalKeyRuleTest.check("null is not", !TechnicalKeyRule.isAcceptedLike(null));
	}

	/** The exact cascade rule the coordinator asked for: ACCEPTED/APPLIED -> ACCEPTED (never speculatively APPLIED), REJECTED -> REJECTED, PROPOSED -> nothing yet. */
	private static void cascadedStatusFromEntityStatus()
	{
		TechnicalKeyRuleTest.check("entity ACCEPTED -> attribute ACCEPTED", "ACCEPTED".equals(TechnicalKeyRule.cascadedStatus("ACCEPTED")));
		TechnicalKeyRuleTest.check("entity APPLIED (already generated) -> attribute ACCEPTED, not APPLIED - StagingGenerator alone marks APPLIED",
			"ACCEPTED".equals(TechnicalKeyRule.cascadedStatus("APPLIED")));
		TechnicalKeyRuleTest.check("entity REJECTED -> attribute REJECTED", "REJECTED".equals(TechnicalKeyRule.cascadedStatus("REJECTED")));
		TechnicalKeyRuleTest.check("entity still PROPOSED -> nothing to cascade yet", TechnicalKeyRule.cascadedStatus("PROPOSED") == null);
		TechnicalKeyRuleTest.check("null entity status -> nothing to cascade", TechnicalKeyRule.cascadedStatus(null) == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
