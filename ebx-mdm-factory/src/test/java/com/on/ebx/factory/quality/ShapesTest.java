package com.on.ebx.factory.quality;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ShapesTest
{
	public static void main(final String[] pArgs)
	{
		ShapesTest.nullIsNull();
		ShapesTest.lettersWithoutCaseAreMasked();
		ShapesTest.theExactIbanExampleFromTheCoordinatorReview();
		ShapesTest.aRunOfFourOrFewerIsNeverCollapsed();
		ShapesTest.aRunOfMoreThanFourCollapsesWithItsCount();
		ShapesTest.punctuationAndSpacesAreAlwaysKeptVerbatim();
		ShapesTest.mixedCaseLettersShapeIndependently();
		ShapesTest.pairShapesBothSidesIndependently();
		ShapesTest.noOriginalLetterOrDigitSurvivesForAnEmail();
		ShapesTest.noOriginalLetterOrDigitSurvivesForAPhoneNumber();
		ShapesTest.noOriginalLetterOrDigitSurvivesForAnIban();
		ShapesTest.noOriginalLetterOrDigitSurvivesForAPersonLikeName();
		System.out.println("ShapesTest OK");
	}

	private static void nullIsNull()
	{
		ShapesTest.check("null in, null out", Shapes.of(null) == null);
	}

	/** The exact example the coordinator review gave: an IBAN grouped in 4s never collapses, since no run exceeds the threshold. */
	private static void theExactIbanExampleFromTheCoordinatorReview()
	{
		final String shaped = Shapes.of("FR76 3000 6000 0112 3456 7890 189");
		ShapesTest.check("FR76 3000 6000 0112 3456 7890 189 -> AA99 9999 9999 9999 9999 9999 999: got " + shaped,
			"AA99 9999 9999 9999 9999 9999 999".equals(shaped));
	}

	private static void aRunOfFourOrFewerIsNeverCollapsed()
	{
		ShapesTest.check("4 digits: not collapsed", "9999".equals(Shapes.of("1234")));
		ShapesTest.check("4 upper letters: not collapsed", "AAAA".equals(Shapes.of("ABCD")));
	}

	private static void aRunOfMoreThanFourCollapsesWithItsCount()
	{
		ShapesTest.check("5 digits collapse: " + Shapes.of("12345"), "9(5)".equals(Shapes.of("12345")));
		ShapesTest.check("9 digits (a SIREN shape) collapse: " + Shapes.of("552120222"), "9(9)".equals(Shapes.of("552120222")));
		ShapesTest.check("a 20-character LEI-shaped run of letters collapses: " + Shapes.of("AAAAAAAAAAAAAAAAAAAA"),
			"A(20)".equals(Shapes.of("AAAAAAAAAAAAAAAAAAAA")));
	}

	private static void punctuationAndSpacesAreAlwaysKeptVerbatim()
	{
		ShapesTest.check("dashes and dots kept: " + Shapes.of("CHE-106.831.974"), "AAA-999.999.999".equals(Shapes.of("CHE-106.831.974")));
		ShapesTest.check("an at-sign is kept: " + Shapes.of("a@b"), "a@a".equals(Shapes.of("a@b")));
	}

	private static void mixedCaseLettersShapeIndependently()
	{
		ShapesTest.check("upper and lower map to different markers: " + Shapes.of("AbCd"), "AaAa".equals(Shapes.of("AbCd")));
	}

	private static void pairShapesBothSidesIndependently()
	{
		// leading/trailing spaces are kept verbatim by Shapes.of (it never trims) - exactly 4 letters either
		// side of them, so neither run collapses (the threshold is a run longer than 4)
		final String shaped = Shapes.pair(" ACME ", "ACME");
		ShapesTest.check("both sides shaped, joined by the arrow: " + shaped, " AAAA  -> AAAA".equals(shaped));
	}

	// --------------------------------------------------------------------------------------------- safety invariant
	//
	// The property the coordinator review asked to be tested directly (task instruction 3): for a realistic
	// personal value (an e-mail, a phone number, an IBAN, a person-like name), the shaped text must contain no
	// letter or digit of the ORIGINAL value except the shape markers themselves (A/a/9) or the digits of a
	// collapse count's own "(<n>)" annotation - which are never derived from, or a substring of, the original.
	// Checked by stripping every A/a/9 marker and every "(<digits>)" collapse annotation from the shaped text,
	// and every Unicode letter/digit from the original text, and requiring what is left - punctuation and
	// spaces, in the same order - to be identical. Test inputs below deliberately contain no parentheses, so
	// stripping "(<digits>)" never removes a punctuation character that was genuinely part of the input.

	private static void noOriginalLetterOrDigitSurvivesForAnEmail()
	{
		ShapesTest.assertOnlyPunctuationAndSpaceSurvive("jane.doe@example.com");
	}

	private static void noOriginalLetterOrDigitSurvivesForAPhoneNumber()
	{
		ShapesTest.assertOnlyPunctuationAndSpaceSurvive("+33 6 12 34 56 78");
	}

	private static void noOriginalLetterOrDigitSurvivesForAnIban()
	{
		ShapesTest.assertOnlyPunctuationAndSpaceSurvive("FR7630006000011234567890189");
	}

	private static void noOriginalLetterOrDigitSurvivesForAPersonLikeName()
	{
		ShapesTest.assertOnlyPunctuationAndSpaceSurvive("Jean-Baptiste Moreau");
	}

	private static void assertOnlyPunctuationAndSpaceSurvive(final String pOriginal)
	{
		final String shaped = Shapes.of(pOriginal);
		// collapse-count annotations first (their digits are a NEW count, not the original's own digits), then the shape markers themselves
		final String strippedShaped = shaped.replaceAll("\\([0-9]+\\)", "").replaceAll("[Aa9]", "");
		final String strippedOriginal = pOriginal.replaceAll("[\\p{L}\\p{N}]", "");
		ShapesTest.check(pOriginal + " -> " + shaped + " : only punctuation/space of the original survives, in order (expected '" + strippedOriginal
			+ "', got '" + strippedShaped + "')", strippedShaped.equals(strippedOriginal));
		// and, redundantly but concretely: the exact original string is never a substring of its own shape
		ShapesTest.check(pOriginal + ": the real value itself never appears in its own shape", !shaped.contains(pOriginal));
	}

	/** security review (2026-09-24): Chinese, Arabic, Hebrew... letters have no case and used to pass through verbatim. */
	private static void lettersWithoutCaseAreMasked()
	{
		ShapesTest.check("Chinese is masked: " + Shapes.of("\u5317\u4EAC"), "aa".equals(Shapes.of("\u5317\u4EAC")));
		ShapesTest.check("Arabic is masked: " + Shapes.of("\u0645\u062D\u0645\u062F"), "aaaa".equals(Shapes.of("\u0645\u062D\u0645\u062F")));
		ShapesTest.check("no Hebrew letter survives", Shapes.of("\u05D3\u05D5\u05D3 12").indexOf('\u05D3') < 0);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
