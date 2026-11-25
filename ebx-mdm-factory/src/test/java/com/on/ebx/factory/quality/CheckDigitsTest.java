package com.on.ebx.factory.quality;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class CheckDigitsTest
{
	public static void main(final String[] pArgs)
	{
		CheckDigitsTest.luhnAcceptsRealSirenFromThisProjectsOwnLandingData();
		CheckDigitsTest.luhnAcceptsAKnownRealSiret();
		CheckDigitsTest.luhnRejectsAWrongCheckDigit();
		CheckDigitsTest.luhnRejectsNonDigitsAndBlank();
		CheckDigitsTest.ibanAcceptsTwoPublishedExamples();
		CheckDigitsTest.ibanRejectsAWrongCheckDigit();
		CheckDigitsTest.leiAcceptsARealPublishedLei();
		CheckDigitsTest.leiAcceptsAnotherRealLeiFromThisProjectsOwnLandingData();
		CheckDigitsTest.leiIsCaseInsensitive();
		CheckDigitsTest.leiRejectsWrongLength();
		CheckDigitsTest.gs1AcceptsTwoPublishedGtinExamples();
		CheckDigitsTest.gs1RejectsAFlippedCheckDigit();
		CheckDigitsTest.gs1RejectsNonDigitsAndTooShort();
		System.out.println("CheckDigitsTest OK");
	}

	/** 552120222 and 662042449: SUPPLIER.REGISTRATION_NO of two real rows in Companies_Landing/ERP.SUPPLIER (Societe Generale / BNP Paribas), read live 2026-09-20. */
	private static void luhnAcceptsRealSirenFromThisProjectsOwnLandingData()
	{
		CheckDigitsTest.check("552120222 (Societe Generale's real SIREN)", CheckDigits.isValidLuhn("552120222"));
		CheckDigitsTest.check("662042449 (BNP Paribas's real SIREN)", CheckDigits.isValidLuhn("662042449"));
	}

	/** 35600000000048: La Poste's headquarters SIRET, a commonly published example - independently verified with Python before being embedded here. */
	private static void luhnAcceptsAKnownRealSiret()
	{
		CheckDigitsTest.check("14-digit SIRET (La Poste)", CheckDigits.isValidLuhn("35600000000048"));
	}

	private static void luhnRejectsAWrongCheckDigit()
	{
		CheckDigitsTest.check("552120223 (last digit flipped) is not Luhn-valid", !CheckDigits.isValidLuhn("552120223"));
	}

	private static void luhnRejectsNonDigitsAndBlank()
	{
		CheckDigitsTest.check("a letter in the string is never valid", !CheckDigits.isValidLuhn("55212022X"));
		CheckDigitsTest.check("null is never valid", !CheckDigits.isValidLuhn(null));
		CheckDigitsTest.check("empty is never valid", !CheckDigits.isValidLuhn(""));
	}

	/** Two IBANs commonly published as valid examples - independently verified with Python before being embedded here. */
	private static void ibanAcceptsTwoPublishedExamples()
	{
		CheckDigitsTest.check("FR14 2004 1010 0505 0001 3M02 606", CheckDigits.isValidIban("FR1420041010050500013M02606"));
		CheckDigitsTest.check("FR76 3000 6000 0112 3456 7890 189", CheckDigits.isValidIban("FR7630006000011234567890189"));
	}

	private static void ibanRejectsAWrongCheckDigit()
	{
		CheckDigitsTest.check("an all-zero IBAN body is not valid", !CheckDigits.isValidIban("FR0000000000000000000000000"));
	}

	/** Bank of America's real, public LEI (a widely published example). */
	private static void leiAcceptsARealPublishedLei()
	{
		CheckDigitsTest.check("529900T8BM49AURSDO55", CheckDigits.isValidLei("529900T8BM49AURSDO55"));
	}

	/** SUPPLIER.LEI of HSBC's real row in Companies_Landing/ERP.SUPPLIER, read live 2026-09-20 (the same row docs/NORMALISATION.md sec 1.6/1.7 discusses as a duplicate-looking supplier pair). */
	private static void leiAcceptsAnotherRealLeiFromThisProjectsOwnLandingData()
	{
		CheckDigitsTest.check("MLU0ZO3ML4LN2LL2TL39 (HSBC's real LEI)", CheckDigits.isValidLei("MLU0ZO3ML4LN2LL2TL39"));
	}

	/** SUPPLIER.LEI of CIC's real row (docs/NORMALISATION.md sec 1.2: the one real lower-case-LEI defect measured on this data) - valid once upper-cased, exactly the NORMALISE_IDENTIFIER + CHECK_DIGIT pairing this lot proposes. */
	private static void leiIsCaseInsensitive()
	{
		CheckDigitsTest.check("lower-case LEI is still valid (upper-cased internally)", CheckDigits.isValidLei("n4jdfkkh2ftd8rkfxo39"));
	}

	private static void leiRejectsWrongLength()
	{
		CheckDigitsTest.check("19 characters is never a valid LEI", !CheckDigits.isValidLei("529900T8BM49AURSDO5"));
		CheckDigitsTest.check("null is never valid", !CheckDigits.isValidLei(null));
	}

	/**
	 * Two real, publicly documented barcodes (Wikipedia's own GTIN check-digit worked examples, independently
	 * cross-checked in Python before being embedded here, see {@link CheckDigits}'s own Javadoc): EAN-13
	 * "4006381333931" and UPC-A "036000291452" - added for the quality/LLM toolbox lot (2026-09-21) so the
	 * check-digit vocabulary is not oriented towards party/company identifiers only.
	 */
	private static void gs1AcceptsTwoPublishedGtinExamples()
	{
		CheckDigitsTest.check("EAN-13 4006381333931", CheckDigits.isValidGs1Mod10("4006381333931"));
		CheckDigitsTest.check("UPC-A 036000291452", CheckDigits.isValidGs1Mod10("036000291452"));
	}

	private static void gs1RejectsAFlippedCheckDigit()
	{
		CheckDigitsTest.check("EAN-13 with the check digit flipped is not valid", !CheckDigits.isValidGs1Mod10("4006381333932"));
		CheckDigitsTest.check("UPC-A with the check digit flipped is not valid", !CheckDigits.isValidGs1Mod10("036000291453"));
	}

	private static void gs1RejectsNonDigitsAndTooShort()
	{
		CheckDigitsTest.check("a letter in the string is never valid", !CheckDigits.isValidGs1Mod10("400638133393X"));
		CheckDigitsTest.check("a single character is never valid (no data digit to weigh)", !CheckDigits.isValidGs1Mod10("7"));
		CheckDigitsTest.check("null is never valid", !CheckDigits.isValidGs1Mod10(null));
		CheckDigitsTest.check("empty is never valid", !CheckDigits.isValidGs1Mod10(""));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
