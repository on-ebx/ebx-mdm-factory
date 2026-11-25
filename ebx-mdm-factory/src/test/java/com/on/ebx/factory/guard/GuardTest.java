package com.on.ebx.factory.guard;

import com.on.ebx.factory.review.ReviewLine;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class GuardTest
{
	public static void main(final String[] pArgs)
	{
		GuardTest.qualityLabelIsKindPlusArgumentDigest();
		GuardTest.qualityLabelIsKindAloneWhenArgumentIsEmpty();
		GuardTest.qualityLabelTruncatesALongArgument();
		GuardTest.scriptConstraintAndDerivationLabelIsTheKindVerbatim();
		GuardTest.externalServiceLabelIsTheKindVerbatim();
		GuardTest.emptyKindLabelsAsAQuestionMark();
		System.out.println("GuardTest OK");
	}

	private static Guard guard(final String pNature, final String pKind, final String pArgument)
	{
		return new Guard("g1", pNature, "e1", "a1", pKind, pArgument, null, null, "PROPOSED", "LLM", Integer.valueOf(80), "because", null, null, null);
	}

	private static void qualityLabelIsKindPlusArgumentDigest()
	{
		final Guard g = GuardTest.guard(ReviewLine.QUALITY, "PATTERN", "^[A-Z]{2}\\d{9}$");
		GuardTest.check("kind + argument", "PATTERN ^[A-Z]{2}\\d{9}$".equals(g.label()));
	}

	private static void qualityLabelIsKindAloneWhenArgumentIsEmpty()
	{
		final Guard g = GuardTest.guard(ReviewLine.QUALITY, "MANDATORY", "");
		GuardTest.check("kind alone", "MANDATORY".equals(g.label()));
		final Guard gNull = GuardTest.guard(ReviewLine.QUALITY, "MANDATORY", null);
		GuardTest.check("kind alone, null argument too", "MANDATORY".equals(gNull.label()));
	}

	private static void qualityLabelTruncatesALongArgument()
	{
		final String longArgument = "0123456789012345678901234567890123456789";
		final Guard g = GuardTest.guard(ReviewLine.QUALITY, "PATTERN", longArgument);
		final String label = g.label();
		GuardTest.check("label starts with the kind", label.startsWith("PATTERN "));
		GuardTest.check("label stays reasonably short (kind + a bounded digest, not the whole argument)", label.length() < ("PATTERN " + longArgument).length());
	}

	/** Task brief's own example: {@code phone.isValid} - built upstream by {@code ebx.GuardReader}, {@link Guard#label()} returns {@link Guard#kind} verbatim for these two natures. */
	private static void scriptConstraintAndDerivationLabelIsTheKindVerbatim()
	{
		final Guard constraint = GuardTest.guard(ReviewLine.SCRIPT_CONSTRAINT, "phone.isValid", "");
		GuardTest.check("SCRIPT_CONSTRAINT label is the short function name, argument ignored", "phone.isValid".equals(constraint.label()));
		final Guard derivation = GuardTest.guard(ReviewLine.SCRIPT_DERIVATION, "iban.checkDigit", "");
		GuardTest.check("SCRIPT_DERIVATION label is the short function name too", "iban.checkDigit".equals(derivation.label()));
	}

	private static void externalServiceLabelIsTheKindVerbatim()
	{
		final Guard g = GuardTest.guard(ReviewLine.EXTERNAL_SERVICE, "verifyAddress", "");
		GuardTest.check("EXTERNAL_SERVICE label is the operation name", "verifyAddress".equals(g.label()));
	}

	private static void emptyKindLabelsAsAQuestionMark()
	{
		final Guard g = GuardTest.guard(ReviewLine.QUALITY, "", "");
		GuardTest.check("an empty kind never produces an empty label", "?".equals(g.label()));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
