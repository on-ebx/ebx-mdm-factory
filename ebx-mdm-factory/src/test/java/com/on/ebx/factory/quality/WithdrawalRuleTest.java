package com.on.ebx.factory.quality;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class WithdrawalRuleTest
{
	public static void main(final String[] pArgs)
	{
		WithdrawalRuleTest.coveredAlwaysKept();
		WithdrawalRuleTest.uncoveredProposedIsWithdrawn();
		WithdrawalRuleTest.uncoveredDecidedIsWarnedNeverDeleted();
		WithdrawalRuleTest.nonRuleOriginIsAlwaysKeptRegardlessOfStatusOrCoverage();
		WithdrawalRuleTest.ruleOriginBehavesExactlyLikeThePlainOverload();
		System.out.println("WithdrawalRuleTest OK");
	}

	private static void coveredAlwaysKept()
	{
		WithdrawalRuleTest.check("covered PROPOSED is kept", WithdrawalRule.decide("PROPOSED", true) == WithdrawalRule.Action.KEEP);
		WithdrawalRuleTest.check("covered ACCEPTED is kept", WithdrawalRule.decide("ACCEPTED", true) == WithdrawalRule.Action.KEEP);
		WithdrawalRuleTest.check("covered REJECTED is kept", WithdrawalRule.decide("REJECTED", true) == WithdrawalRule.Action.KEEP);
		WithdrawalRuleTest.check("covered APPLIED is kept", WithdrawalRule.decide("APPLIED", true) == WithdrawalRule.Action.KEEP);
	}

	/** The coordinator's exact case: a PROPOSED rule/step whose only supporting mapping got rejected - nobody decided on it, so it is withdrawn (deleted), not left to mislead a future review. */
	private static void uncoveredProposedIsWithdrawn()
	{
		WithdrawalRuleTest.check("uncovered PROPOSED is withdrawn", WithdrawalRule.decide("PROPOSED", false) == WithdrawalRule.Action.WITHDRAW);
	}

	/** A person's own decision is never silently undone or deleted - only flagged, whatever the status. */
	private static void uncoveredDecidedIsWarnedNeverDeleted()
	{
		WithdrawalRuleTest.check("uncovered ACCEPTED is warned, not withdrawn", WithdrawalRule.decide("ACCEPTED", false) == WithdrawalRule.Action.WARN);
		WithdrawalRuleTest.check("uncovered REJECTED is warned, not withdrawn", WithdrawalRule.decide("REJECTED", false) == WithdrawalRule.Action.WARN);
		WithdrawalRuleTest.check("uncovered APPLIED is warned, not withdrawn", WithdrawalRule.decide("APPLIED", false) == WithdrawalRule.Action.WARN);
	}

	/**
	 * The coordinator's fix, 2026-09-22, before merge - the exact bug: {@code
	 * ebx.QualitySuggester.withdrawStaleFoldKeyAttributes} must never delete or flag a person's ({@code USER})
	 * or the language model's ({@code LLM}) own derived {@code StagingAttribute}, whatever a deterministic
	 * fold re-measurement says. {@code KEEP} regardless of status, regardless of coverage - the uncovered-
	 * PROPOSED case (which would otherwise WITHDRAW, per {@link #uncoveredProposedIsWithdrawn}) is the one
	 * that actually matters: that is the exact silent-deletion bug this overload exists to prevent.
	 */
	private static void nonRuleOriginIsAlwaysKeptRegardlessOfStatusOrCoverage()
	{
		WithdrawalRuleTest.check("USER, uncovered, PROPOSED: kept, not withdrawn - the exact bug fixed",
			WithdrawalRule.decide("PROPOSED", false, "USER") == WithdrawalRule.Action.KEEP);
		WithdrawalRuleTest.check("LLM, uncovered, PROPOSED: kept, not withdrawn",
			WithdrawalRule.decide("PROPOSED", false, "LLM") == WithdrawalRule.Action.KEEP);
		WithdrawalRuleTest.check("USER, uncovered, ACCEPTED: kept, never flagged either",
			WithdrawalRule.decide("ACCEPTED", false, "USER") == WithdrawalRule.Action.KEEP);
		WithdrawalRuleTest.check("LLM, uncovered, APPLIED: kept, never flagged either",
			WithdrawalRule.decide("APPLIED", false, "LLM") == WithdrawalRule.Action.KEEP);
		WithdrawalRuleTest.check("USER, covered, PROPOSED: kept too (covered would already say KEEP, but the origin gate must not break the happy path)",
			WithdrawalRule.decide("PROPOSED", true, "USER") == WithdrawalRule.Action.KEEP);
		WithdrawalRuleTest.check("blank/null origin reads as non-RULE too: kept", WithdrawalRule.decide("PROPOSED", false, "") == WithdrawalRule.Action.KEEP
			&& WithdrawalRule.decide("PROPOSED", false, null) == WithdrawalRule.Action.KEEP);
	}

	/** A {@code RULE}-origin row must behave EXACTLY like the plain 2-arg {@link WithdrawalRule#decide(String, boolean)} - the origin gate only ever REMOVES an action for a non-RULE row, never changes one for a RULE row. */
	private static void ruleOriginBehavesExactlyLikeThePlainOverload()
	{
		WithdrawalRuleTest.check("RULE, covered, PROPOSED: KEEP, same as plain overload",
			WithdrawalRule.decide("PROPOSED", true, "RULE") == WithdrawalRule.decide("PROPOSED", true));
		WithdrawalRuleTest.check("RULE, uncovered, PROPOSED: WITHDRAW, same as plain overload",
			WithdrawalRule.decide("PROPOSED", false, "RULE") == WithdrawalRule.decide("PROPOSED", false)
				&& WithdrawalRule.decide("PROPOSED", false, "RULE") == WithdrawalRule.Action.WITHDRAW);
		WithdrawalRuleTest.check("RULE, uncovered, ACCEPTED: WARN, same as plain overload",
			WithdrawalRule.decide("ACCEPTED", false, "RULE") == WithdrawalRule.decide("ACCEPTED", false)
				&& WithdrawalRule.decide("ACCEPTED", false, "RULE") == WithdrawalRule.Action.WARN);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
