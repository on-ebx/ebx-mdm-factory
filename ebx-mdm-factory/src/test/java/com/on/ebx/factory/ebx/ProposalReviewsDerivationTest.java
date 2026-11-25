package com.on.ebx.factory.ebx;

import com.on.ebx.factory.review.ReviewLine;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * fix/matrix-computed-field: {@link ProposalReviews#derivationFollowsField} is the pure rule behind the ACCEPT half of
 * the field/function cascade (the REJECT half is the existing {@code cascadeGuardsOfAttribute}); the procedure that writes
 * needs a live EBX session and is exercised only by hand.
 */
public final class ProposalReviewsDerivationTest
{
	public static void main(final String[] pArgs)
	{
		ProposalReviewsDerivationTest.onlyAProposedScriptDerivationFollowsItsField();
		System.out.println("ProposalReviewsDerivationTest OK");
	}

	private static void onlyAProposedScriptDerivationFollowsItsField()
	{
		ProposalReviewsDerivationTest.check("a PROPOSED SCRIPT_DERIVATION follows", ProposalReviews.derivationFollowsField(ReviewLine.SCRIPT_DERIVATION, ReviewLine.PROPOSED));
		ProposalReviewsDerivationTest.check("an ACCEPTED one is never touched", !ProposalReviews.derivationFollowsField(ReviewLine.SCRIPT_DERIVATION, ReviewLine.ACCEPTED));
		ProposalReviewsDerivationTest.check("an APPLIED one is never touched", !ProposalReviews.derivationFollowsField(ReviewLine.SCRIPT_DERIVATION, ReviewLine.APPLIED));
		ProposalReviewsDerivationTest.check("a REJECTED one is never re-opened", !ProposalReviews.derivationFollowsField(ReviewLine.SCRIPT_DERIVATION, ReviewLine.REJECTED));
		ProposalReviewsDerivationTest.check("a SCRIPT_CONSTRAINT is NOT carried (accepting one applies it to a model: a decision of its own)",
			!ProposalReviews.derivationFollowsField(ReviewLine.SCRIPT_CONSTRAINT, ReviewLine.PROPOSED));
		ProposalReviewsDerivationTest.check("an EXTERNAL_SERVICE is NOT carried", !ProposalReviews.derivationFollowsField(ReviewLine.EXTERNAL_SERVICE, ReviewLine.PROPOSED));
		ProposalReviewsDerivationTest.check("null nature/status never follow", !ProposalReviews.derivationFollowsField(null, null));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
