package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.List;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Covers the
 * Reference search box's own override rule (coordinator report, 2026-09-21: "Je ne vois pas les Ids
 * des choses a revoir dans l'ecran") - see {@link ReviewFilter#matches} and {@link ReviewReference}.
 */
public final class ReviewFilterTest
{
	public static void main(final String[] pArgs)
	{
		ReviewFilterTest.blankReferenceFallsBackToOrdinaryFiltering();
		ReviewFilterTest.fullyQualifiedReferenceOverridesTheStatusFilter();
		ReviewFilterTest.fullyQualifiedReferenceOverridesEveryFilterDimension();
		ReviewFilterTest.fullyQualifiedReferenceIsToleratedInSpaceAndCaseForm();
		ReviewFilterTest.fullyQualifiedReferenceNeverMatchesTheWrongNatureOrId();
		ReviewFilterTest.bareNumberCombinedWithNatureFilterResolves();
		ReviewFilterTest.bareNumberWithNatureAllMatchesNothing();
		ReviewFilterTest.bareNumberNeverMatchesADifferentNatureEvenWithTheSameId();
		ReviewFilterTest.referenceOverrideReachableThroughReviewData();
		System.out.println("ReviewFilterTest OK");
	}

	private static ReviewLine mappingStep(final String pRowId, final String pStatus)
	{
		return new ReviewLine(ReviewLine.MAPPING_STEP, pRowId, "ACCOUNT.ACCOUNT_TYPE -> Company.legalForm", "TRIM", "RULE", Integer.valueOf(90), "why", pStatus, false,
			false);
	}

	private static void blankReferenceFallsBackToOrdinaryFiltering()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.PROPOSED, 0, "  ");
		final ReviewLine proposed = ReviewFilterTest.mappingStep("8", ReviewLine.PROPOSED);
		final ReviewLine accepted = ReviewFilterTest.mappingStep("8", ReviewLine.ACCEPTED);
		ReviewFilterTest.check("blank reference: ordinary status filter still applies (PROPOSED matches)", filter.matches(proposed));
		ReviewFilterTest.check("blank reference: ordinary status filter still applies (ACCEPTED excluded)", !filter.matches(accepted));
	}

	/** The real captured miss: a coordinator typing STEP-8 must find it even though the screen is on Status = Pending and the line is ACCEPTED. */
	private static void fullyQualifiedReferenceOverridesTheStatusFilter()
	{
		final ReviewFilter pending = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.PROPOSED, 0, "STEP-8");
		final ReviewLine acceptedStep8 = ReviewFilterTest.mappingStep("8", ReviewLine.ACCEPTED);
		ReviewFilterTest.check("STEP-8 shows the line although the filter is Status=Pending and the line is ACCEPTED", pending.matches(acceptedStep8));
	}

	/** "whatever the other filters say" (task brief) - nature, origin and the confidence floor are bypassed too, not only status. */
	private static void fullyQualifiedReferenceOverridesEveryFilterDimension()
	{
		final ReviewFilter narrow = new ReviewFilter(ReviewLine.ENTITY, "LLM", ReviewLine.APPLIED, 95, "STEP-8");
		final ReviewLine line = new ReviewLine(ReviewLine.MAPPING_STEP, "8", "x", "y", "RULE", Integer.valueOf(1), "why", ReviewLine.PROPOSED, false, false);
		ReviewFilterTest.check("nature=ENTITY/origin=LLM/status=APPLIED/minConfidence=95 all disagree with the line, the reference still wins", narrow.matches(line));
	}

	private static void fullyQualifiedReferenceIsToleratedInSpaceAndCaseForm()
	{
		final ReviewLine line = ReviewFilterTest.mappingStep("8", ReviewLine.PROPOSED);
		ReviewFilterTest.check("'step 8'", new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0, "step 8").matches(line));
		ReviewFilterTest.check("'step8'", new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0, "step8").matches(line));
		ReviewFilterTest.check("'Step-8'", new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0, "Step-8").matches(line));
	}

	private static void fullyQualifiedReferenceNeverMatchesTheWrongNatureOrId()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0, "STEP-8");
		ReviewFilterTest.check("STEP-8 does not match STEP-9", !filter.matches(ReviewFilterTest.mappingStep("9", ReviewLine.PROPOSED)));
		final ReviewLine mapping8 = new ReviewLine(ReviewLine.MAPPING, "8", "x", "y", "RULE", Integer.valueOf(1), "why", ReviewLine.PROPOSED, false, false);
		ReviewFilterTest.check("STEP-8 does not match MAP-8 (same row id, different nature/prefix)", !filter.matches(mapping8));
	}

	/** Task brief: "a bare 8 combined with the Nature filter" - a bare number alone cannot resolve a nature, so it needs the Nature filter narrowed to one. */
	private static void bareNumberCombinedWithNatureFilterResolves()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewLine.MAPPING_STEP, ReviewFilter.ALL, ReviewLine.APPLIED, 0, "8");
		ReviewFilterTest.check("bare '8' + Nature=MAPPING_STEP finds STEP-8 despite Status=APPLIED filter",
			filter.matches(ReviewFilterTest.mappingStep("8", ReviewLine.PROPOSED)));
	}

	private static void bareNumberWithNatureAllMatchesNothing()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewFilter.ALL, 0, "8");
		ReviewFilterTest.check("bare '8' with Nature=ALL is ambiguous: matches nothing", !filter.matches(ReviewFilterTest.mappingStep("8", ReviewLine.PROPOSED)));
	}

	private static void bareNumberNeverMatchesADifferentNatureEvenWithTheSameId()
	{
		final ReviewFilter filter = new ReviewFilter(ReviewLine.MAPPING_STEP, ReviewFilter.ALL, ReviewFilter.ALL, 0, "8");
		final ReviewLine mapping8 = new ReviewLine(ReviewLine.MAPPING, "8", "x", "y", "RULE", Integer.valueOf(1), "why", ReviewLine.PROPOSED, false, false);
		ReviewFilterTest.check("bare '8' + Nature=MAPPING_STEP never matches a MAPPING row of the same id", !filter.matches(mapping8));
	}

	/** Sanity: {@link ReviewData#filter} (the entry point every real caller uses) reaches the same reference override through the ordinary path. */
	private static void referenceOverrideReachableThroughReviewData()
	{
		final List<ReviewLine> lines = new ArrayList<ReviewLine>();
		lines.add(ReviewFilterTest.mappingStep("8", ReviewLine.ACCEPTED));
		lines.add(new ReviewLine(ReviewLine.ENTITY, "e1", "Supplier", "New entity", "LLM", Integer.valueOf(84), "why", ReviewLine.PROPOSED, false, false));
		final List<ReviewLine> visible = ReviewData.filter(lines, new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.PROPOSED, 0, "STEP-8"));
		ReviewFilterTest.check("only the referenced line shows, not the unrelated PROPOSED one", visible.size() == 1 && "8".equals(visible.get(0).rowId));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
