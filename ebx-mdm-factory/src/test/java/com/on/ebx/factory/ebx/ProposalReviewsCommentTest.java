package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.review.ReviewLine;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * Mapping-matrix lot (docs/MAPPING-MATRIX.md): {@link ProposalReviews#reviewCommentApplies(String)} and
 * {@link ProposalReviews#reviewCommentField(String)} are the two pure, package-private decisions behind
 * {@code ProposalReviews#decideInProcedure}'s reviewComment write/clear for a MAPPING or FEED row - the
 * rest of that method needs a live EBX session and is exercised only by hand against the running instance
 * (never by this offline test suite).
 */
public final class ProposalReviewsCommentTest
{
	public static void main(final String[] pArgs)
	{
		ProposalReviewsCommentTest.appliesOnlyToMappingAndFeed();
		ProposalReviewsCommentTest.fieldMatchesNature();
		System.out.println("ProposalReviewsCommentTest OK");
	}

	private static void appliesOnlyToMappingAndFeed()
	{
		ProposalReviewsCommentTest.check("MAPPING carries a reviewComment", ProposalReviews.reviewCommentApplies(ReviewLine.MAPPING));
		ProposalReviewsCommentTest.check("FEED carries a reviewComment", ProposalReviews.reviewCommentApplies(ReviewLine.FEED));
		ProposalReviewsCommentTest.check("MATCH has its own, separate write path (matchPlanExtras) - not this one",
			!ProposalReviews.reviewCommentApplies(ReviewLine.MATCH));
		ProposalReviewsCommentTest.check("ENTITY never carries a reviewComment", !ProposalReviews.reviewCommentApplies(ReviewLine.ENTITY));
		ProposalReviewsCommentTest.check("ATTRIBUTE never carries a reviewComment", !ProposalReviews.reviewCommentApplies(ReviewLine.ATTRIBUTE));
		ProposalReviewsCommentTest.check("null nature never applies", !ProposalReviews.reviewCommentApplies(null));
	}

	private static void fieldMatchesNature()
	{
		ProposalReviewsCommentTest.check("MAPPING -> AttributeMapping.reviewComment",
			FactoryPaths.AttributeMapping.REVIEW_COMMENT.equals(ProposalReviews.reviewCommentField(ReviewLine.MAPPING)));
		ProposalReviewsCommentTest.check("FEED -> TransferFeed.reviewComment",
			FactoryPaths.TransferFeed.REVIEW_COMMENT.equals(ProposalReviews.reviewCommentField(ReviewLine.FEED)));
		ProposalReviewsCommentTest.check("a nature with no reviewComment field resolves to null", ProposalReviews.reviewCommentField(ReviewLine.MATCH) == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
