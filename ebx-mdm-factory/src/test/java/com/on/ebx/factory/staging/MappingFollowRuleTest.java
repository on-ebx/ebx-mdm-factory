package com.on.ebx.factory.staging;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MappingFollowRuleTest
{
	public static void main(final String[] pArgs)
	{
		MappingFollowRuleTest.isEligibleOnlyProposedRuleOrLlm();
		MappingFollowRuleTest.cascadedStatusMatchesTechnicalKeyRule();
		MappingFollowRuleTest.onlyAnUndecidedMatchingKeyFollowsItsSourceFieldsRejection();
		System.out.println("MappingFollowRuleTest OK");
	}

	/** The exact live case: 62 PROPOSED mappings of origin RULE/LLM should be eligible; a USER mapping, or one already decided by anyone, never is. */
	private static void isEligibleOnlyProposedRuleOrLlm()
	{
		MappingFollowRuleTest.check("PROPOSED + RULE is eligible", MappingFollowRule.isEligible("PROPOSED", "RULE"));
		MappingFollowRuleTest.check("PROPOSED + LLM is eligible", MappingFollowRule.isEligible("PROPOSED", "LLM"));
		MappingFollowRuleTest.check("PROPOSED + USER is never eligible: a person's own mapping is never touched",
			!MappingFollowRule.isEligible("PROPOSED", "USER"));
		MappingFollowRuleTest.check("ACCEPTED + RULE is not eligible: already decided, nothing to follow",
			!MappingFollowRule.isEligible("ACCEPTED", "RULE"));
		MappingFollowRuleTest.check("REJECTED + RULE is not eligible: a mapping a person rejected stays rejected",
			!MappingFollowRule.isEligible("REJECTED", "RULE"));
		MappingFollowRuleTest.check("APPLIED + RULE is not eligible", !MappingFollowRule.isEligible("APPLIED", "RULE"));
		MappingFollowRuleTest.check("PROPOSED + null origin is not eligible", !MappingFollowRule.isEligible("PROPOSED", null));
		MappingFollowRuleTest.check("null status is not eligible", !MappingFollowRule.isEligible(null, "RULE"));
	}

	/** Delegated to TechnicalKeyRule.cascadedStatus - same table: ACCEPTED/APPLIED -> ACCEPTED, REJECTED -> REJECTED, PROPOSED -> null. */
	private static void cascadedStatusMatchesTechnicalKeyRule()
	{
		MappingFollowRuleTest.check("attribute ACCEPTED -> mapping ACCEPTED", "ACCEPTED".equals(MappingFollowRule.cascadedStatus("ACCEPTED")));
		MappingFollowRuleTest.check("attribute APPLIED (already generated) -> mapping ACCEPTED, not APPLIED - StagingGenerator alone marks APPLIED",
			"ACCEPTED".equals(MappingFollowRule.cascadedStatus("APPLIED")));
		MappingFollowRuleTest.check("attribute REJECTED -> mapping REJECTED", "REJECTED".equals(MappingFollowRule.cascadedStatus("REJECTED")));
		MappingFollowRuleTest.check("attribute still PROPOSED -> nothing to cascade yet", MappingFollowRule.cascadedStatus("PROPOSED") == null);
	}

	/** Mapping matrix review queue lot (2026-09-30): rejecting a field rejects its matching key - only while the key is still PROPOSED, never another derivation, never a decided key. */
	private static void onlyAnUndecidedMatchingKeyFollowsItsSourceFieldsRejection()
	{
		MappingFollowRuleTest.check("a PROPOSED matching key follows", MappingFollowRule.foldKeyFollowsRejection("FOLD_KEY", "PROPOSED"));
		MappingFollowRuleTest.check("an ACCEPTED matching key is never touched", !MappingFollowRule.foldKeyFollowsRejection("FOLD_KEY", "ACCEPTED"));
		MappingFollowRuleTest.check("a REJECTED matching key has nothing left to do", !MappingFollowRule.foldKeyFollowsRejection("FOLD_KEY", "REJECTED"));
		MappingFollowRuleTest.check("an APPLIED matching key is never touched", !MappingFollowRule.foldKeyFollowsRejection("FOLD_KEY", "APPLIED"));
		MappingFollowRuleTest.check("a computed (script) field is a person's own choice", !MappingFollowRule.foldKeyFollowsRejection("SCRIPT", "PROPOSED"));
		MappingFollowRuleTest.check("a valid-value field is a person's own choice", !MappingFollowRule.foldKeyFollowsRejection("VALID_VALUE", "PROPOSED"));
		MappingFollowRuleTest.check("an ordinary field (no derivation) is not a key", !MappingFollowRule.foldKeyFollowsRejection("", "PROPOSED"));
		MappingFollowRuleTest.check("null derivation is not a key", !MappingFollowRule.foldKeyFollowsRejection(null, "PROPOSED"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
