package com.on.ebx.factory.review;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ReviewReferenceTest
{
	public static void main(final String[] pArgs)
	{
		ReviewReferenceTest.formatBuildsTheCoordinatorsOwnExamples();
		ReviewReferenceTest.formatIsNullForAnUnknownNatureOrABlankRowId();
		ReviewReferenceTest.tooltipNamesTheTableAndTheKey();
		ReviewReferenceTest.tooltipIsNullForAnUnknownNature();
		ReviewReferenceTest.normalizeIsCaseAndSeparatorInsensitive();
		ReviewReferenceTest.normalizeOfNullIsEmptyNeverNull();
		ReviewReferenceTest.isNumericTrueOnlyForAllDigits();
		System.out.println("ReviewReferenceTest OK");
	}

	/** The exact reference examples the coordinator asked for (task brief, 2026-09-21). */
	private static void formatBuildsTheCoordinatorsOwnExamples()
	{
		ReviewReferenceTest.check("AttributeMapping -> MAP-63", "MAP-63".equals(ReviewReference.format(ReviewLine.MAPPING, "63")));
		ReviewReferenceTest.check("MappingStep -> STEP-8", "STEP-8".equals(ReviewReference.format(ReviewLine.MAPPING_STEP, "8")));
		ReviewReferenceTest.check("QualityRule -> RULE-12", "RULE-12".equals(ReviewReference.format(ReviewLine.QUALITY, "12")));
		ReviewReferenceTest.check("StagingAttribute -> ATTR-36", "ATTR-36".equals(ReviewReference.format(ReviewLine.ATTRIBUTE, "36")));
		ReviewReferenceTest.check("StagingEntity -> ENT-4", "ENT-4".equals(ReviewReference.format(ReviewLine.ENTITY, "4")));
		ReviewReferenceTest.check("LandingRelation -> REL-2", "REL-2".equals(ReviewReference.format(ReviewLine.RELATION, "2")));
		ReviewReferenceTest.check("SCRIPT_CONSTRAINT -> SCRIPT-6", "SCRIPT-6".equals(ReviewReference.format(ReviewLine.SCRIPT_CONSTRAINT, "6")));
		ReviewReferenceTest.check("EXTERNAL_SERVICE -> SVC-9", "SVC-9".equals(ReviewReference.format(ReviewLine.EXTERNAL_SERVICE, "9")));
		ReviewReferenceTest.check("MatchPlan -> MATCH-1", "MATCH-1".equals(ReviewReference.format(ReviewLine.MATCH, "1")));
		// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3)
		ReviewReferenceTest.check("TransferFeed -> FEED-5", "FEED-5".equals(ReviewReference.format(ReviewLine.FEED, "5")));
	}

	private static void formatIsNullForAnUnknownNatureOrABlankRowId()
	{
		ReviewReferenceTest.check("unknown nature -> null", ReviewReference.format("NOT_A_NATURE", "1") == null);
		ReviewReferenceTest.check("null row id -> null", ReviewReference.format(ReviewLine.MAPPING, null) == null);
		ReviewReferenceTest.check("blank row id -> null", ReviewReference.format(ReviewLine.MAPPING, "   ") == null);
	}

	private static void tooltipNamesTheTableAndTheKey()
	{
		ReviewReferenceTest.check("MappingStep 8", "MappingStep 8".equals(ReviewReference.tooltip(ReviewLine.MAPPING_STEP, "8")));
		ReviewReferenceTest.check("AttributeMapping 63", "AttributeMapping 63".equals(ReviewReference.tooltip(ReviewLine.MAPPING, "63")));
		ReviewReferenceTest.check("CapabilitySuggestion 6 for SCRIPT_CONSTRAINT", "CapabilitySuggestion 6".equals(ReviewReference.tooltip(ReviewLine.SCRIPT_CONSTRAINT, "6")));
		ReviewReferenceTest.check("CapabilitySuggestion 9 for EXTERNAL_SERVICE too - same underlying table",
			"CapabilitySuggestion 9".equals(ReviewReference.tooltip(ReviewLine.EXTERNAL_SERVICE, "9")));
		ReviewReferenceTest.check("TransferFeed 5", "TransferFeed 5".equals(ReviewReference.tooltip(ReviewLine.FEED, "5")));
	}

	private static void tooltipIsNullForAnUnknownNature()
	{
		ReviewReferenceTest.check("unknown nature -> null tooltip", ReviewReference.tooltip("NOT_A_NATURE", "1") == null);
	}

	/** {@code STEP-8}, {@code step 8} and {@code STEP8} must all compare equal - the tolerant forms the search box accepts. */
	private static void normalizeIsCaseAndSeparatorInsensitive()
	{
		final String canonical = ReviewReference.normalize("STEP-8");
		ReviewReferenceTest.check("STEP-8 normalizes to STEP8", "STEP8".equals(canonical));
		ReviewReferenceTest.check("'step 8' normalizes the same", canonical.equals(ReviewReference.normalize("step 8")));
		ReviewReferenceTest.check("'STEP8' normalizes the same", canonical.equals(ReviewReference.normalize("STEP8")));
		ReviewReferenceTest.check("'Step-8' normalizes the same", canonical.equals(ReviewReference.normalize("Step-8")));
	}

	private static void normalizeOfNullIsEmptyNeverNull()
	{
		ReviewReferenceTest.check("normalize(null) is empty, not null", "".equals(ReviewReference.normalize(null)));
	}

	private static void isNumericTrueOnlyForAllDigits()
	{
		ReviewReferenceTest.check("'8' is numeric", ReviewReference.isNumeric("8"));
		ReviewReferenceTest.check("'63' is numeric", ReviewReference.isNumeric("63"));
		ReviewReferenceTest.check("'STEP8' is not numeric", !ReviewReference.isNumeric("STEP8"));
		ReviewReferenceTest.check("empty is not numeric", !ReviewReference.isNumeric(""));
		ReviewReferenceTest.check("null is not numeric", !ReviewReference.isNumeric(null));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
