package com.on.ebx.factory.ebx.ui;

/**
 * JUnit-free, like the sibling tests of this package: run the main, a failure throws. Exercises {@link
 * FeedDesignerUserService#isEligibleTargetStatus}, {@link FeedDesignerUserService#isValidFieldName} and
 * {@link FeedDesignerUserService#isKnownDataType} without any EBX type - the only pure decisions this class
 * makes, package-visible for this reason alone (everything else needs a live {@code Adaptation}/{@code
 * Session}).
 * <p>
 * <b>What this guards against (bug fixed 2026-09-24, project Personnes, entity Personne)</b>: the Target
 * pane and the save-time attribute lookup used to keep only {@code ACCEPTED}/{@code APPLIED} {@code
 * StagingAttribute} rows - "no real field yet in the generated staging table". On a project where every
 * attribute was still {@code PROPOSED} this left the whole Target pane empty and every rule's link
 * dangling at the pane's top-right corner (no port to anchor to - {@code feed-designer.js}'s own {@code
 * anchor} fallback). {@link FeedDesignerUserService#isEligibleTargetStatus} now decides this in one place,
 * shared by both {@link FeedDesignerUserService#attributesByName} (save-time resolution) and the Target
 * field list built by {@link FeedDesignerUserService#feedModel} - every status test below is a property
 * that would have failed under that bug (a {@code PROPOSED} status rejected) and holds now.
 * <p>
 * <b>"Add a target field" lot (docs/FEED-DESIGNER.md):</b> {@link FeedDesignerUserService#isValidFieldName}/
 * {@link FeedDesignerUserService#isKnownDataType} are what {@link FeedDesignerUserService#onAddTargetField}
 * checks BEFORE ever writing a new {@code StagingAttribute} - the exact same {@code maxLen=80, pattern=
 * '[A-Za-z][A-Za-z0-9_]*'} facet the XSD itself enforces (tools/build_xsd.py) and the fixed six-value {@code
 * dataType} enum, so a malformed request is refused with a clear message instead of EBX's own generic
 * blocking-constraint failure.
 */
public final class FeedDesignerUserServiceTest
{
	public static void main(final String[] pArgs)
	{
		FeedDesignerUserServiceTest.proposedIsEligible();
		FeedDesignerUserServiceTest.acceptedIsEligible();
		FeedDesignerUserServiceTest.appliedIsEligible();
		FeedDesignerUserServiceTest.rejectedIsNotEligible();
		FeedDesignerUserServiceTest.supersededIsNotEligible();
		FeedDesignerUserServiceTest.emptyOrUnknownStatusIsEligible();
		FeedDesignerUserServiceTest.validFieldNames();
		FeedDesignerUserServiceTest.invalidFieldNames();
		FeedDesignerUserServiceTest.knownDataTypes();
		FeedDesignerUserServiceTest.unknownDataTypes();
		System.out.println("FeedDesignerUserServiceTest OK");
	}

	/**
	 * "Add a target field" lot: every name {@code StagingAttribute.name}'s own XSD facet accepts
	 * (tools/build_xsd.py: {@code maxLen=80, pattern='[A-Za-z][A-Za-z0-9_]*'}) - a single letter, a mix of
	 * letters/digits/underscore, and a name exactly at the 80-character limit.
	 */
	private static void validFieldNames()
	{
		FeedDesignerUserServiceTest.check("a single letter is valid", FeedDesignerUserService.isValidFieldName("a"));
		FeedDesignerUserServiceTest.check("letters, digits and underscore are valid", FeedDesignerUserService.isValidFieldName("customerName_2"));
		final StringBuilder eighty = new StringBuilder("a");
		while (eighty.length() < 80)
		{
			eighty.append('a');
		}
		FeedDesignerUserServiceTest.check("exactly 80 characters is valid", FeedDesignerUserService.isValidFieldName(eighty.toString()));
	}

	/** The exact things a person could type that the XSD facet refuses - checked here so onAddTargetField refuses them with a clear message, before EBX's own generic blocking-constraint failure (CLAUDE.md sec 2). */
	private static void invalidFieldNames()
	{
		FeedDesignerUserServiceTest.check("null is invalid", !FeedDesignerUserService.isValidFieldName(null));
		FeedDesignerUserServiceTest.check("empty is invalid", !FeedDesignerUserService.isValidFieldName(""));
		FeedDesignerUserServiceTest.check("starting with a digit is invalid", !FeedDesignerUserService.isValidFieldName("2ndName"));
		FeedDesignerUserServiceTest.check("starting with an underscore is invalid", !FeedDesignerUserService.isValidFieldName("_name"));
		FeedDesignerUserServiceTest.check("a space is invalid", !FeedDesignerUserService.isValidFieldName("customer name"));
		FeedDesignerUserServiceTest.check("a hyphen is invalid", !FeedDesignerUserService.isValidFieldName("customer-name"));
		final StringBuilder eightyOne = new StringBuilder("a");
		while (eightyOne.length() < 81)
		{
			eightyOne.append('a');
		}
		FeedDesignerUserServiceTest.check("81 characters is too long", !FeedDesignerUserService.isValidFieldName(eightyOne.toString()));
	}

	/** {@code StagingAttribute.dataType}'s own enum, exactly (tools/build_xsd.py's {@code DATA_TYPE}). */
	private static void knownDataTypes()
	{
		FeedDesignerUserServiceTest.check("STRING is known", FeedDesignerUserService.isKnownDataType("STRING"));
		FeedDesignerUserServiceTest.check("INTEGER is known", FeedDesignerUserService.isKnownDataType("INTEGER"));
		FeedDesignerUserServiceTest.check("DECIMAL is known", FeedDesignerUserService.isKnownDataType("DECIMAL"));
		FeedDesignerUserServiceTest.check("BOOLEAN is known", FeedDesignerUserService.isKnownDataType("BOOLEAN"));
		FeedDesignerUserServiceTest.check("DATE is known", FeedDesignerUserService.isKnownDataType("DATE"));
		FeedDesignerUserServiceTest.check("TIMESTAMP is known", FeedDesignerUserService.isKnownDataType("TIMESTAMP"));
	}

	private static void unknownDataTypes()
	{
		FeedDesignerUserServiceTest.check("null is not known", !FeedDesignerUserService.isKnownDataType(null));
		FeedDesignerUserServiceTest.check("empty is not known", !FeedDesignerUserService.isKnownDataType(""));
		FeedDesignerUserServiceTest.check("lower-case is not known - the client's <select> only ever posts the upper-case codes",
			!FeedDesignerUserService.isKnownDataType("string"));
		FeedDesignerUserServiceTest.check("an unrelated word is not known", !FeedDesignerUserService.isKnownDataType("VARCHAR"));
	}

	/** The exact regression: a not-yet-decided attribute must still be a legitimate rule target. */
	private static void proposedIsEligible()
	{
		FeedDesignerUserServiceTest.check("PROPOSED is eligible", FeedDesignerUserService.isEligibleTargetStatus("PROPOSED"));
	}

	private static void acceptedIsEligible()
	{
		FeedDesignerUserServiceTest.check("ACCEPTED is eligible", FeedDesignerUserService.isEligibleTargetStatus("ACCEPTED"));
	}

	private static void appliedIsEligible()
	{
		FeedDesignerUserServiceTest.check("APPLIED is eligible", FeedDesignerUserService.isEligibleTargetStatus("APPLIED"));
	}

	private static void rejectedIsNotEligible()
	{
		FeedDesignerUserServiceTest.check("REJECTED is not eligible - nothing should ever be mapped onto a ruled-out attribute",
			!FeedDesignerUserService.isEligibleTargetStatus("REJECTED"));
	}

	/** {@code StagingAttribute} never actually stores this value today (tools/build_xsd.py keeps the plain four-value enum) - checked anyway, for parity with {@code StagingEntity}'s own vocabulary, and so this method stays correct if that ever changes. */
	private static void supersededIsNotEligible()
	{
		FeedDesignerUserServiceTest.check("SUPERSEDED is not eligible", !FeedDesignerUserService.isEligibleTargetStatus("SUPERSEDED"));
	}

	/** {@link FeedDesignerUserService#textOf} never returns {@code null} (empty string instead), and an unknown value should fail open rather than silently hide a target - only the two explicit ruled-out statuses are excluded. */
	private static void emptyOrUnknownStatusIsEligible()
	{
		FeedDesignerUserServiceTest.check("empty status is eligible", FeedDesignerUserService.isEligibleTargetStatus(""));
		FeedDesignerUserServiceTest.check("an unknown status is eligible (fail open, not closed)", FeedDesignerUserService.isEligibleTargetStatus("SOMETHING_ELSE"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
