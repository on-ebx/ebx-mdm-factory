package com.on.ebx.factory.quality;

import java.util.Arrays;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * Domain-heuristics removal (2026-09-21, product owner review): this class no longer proposes ANYTHING on its
 * own - {@code CHECK_DIGIT}/{@code PATTERN}/{@code REFERENCE} moved to the language model in an earlier round
 * of this same lot, and {@code UNIQUE} (the last kind still proposed by {@code QualityDetectors} itself) moved
 * there too in this round, since "mostly distinct" is a statistic no Java rule can turn into "uniqueness is
 * expected here" without business knowledge - see {@code QualityLlmPassTest} for the equivalent coverage of
 * {@code UNIQUE} now that it is measured from EXACT SQL aggregates, not the capped sample. What remains here:
 * {@link QualityDetectors#isCheckDigitValid} - the EXECUTOR, unchanged, still used by a row accepted before
 * this lot AND by the language model's own {@code measure} tool.
 */
public final class QualityDetectorsTest
{
	public static void main(final String[] pArgs)
	{
		QualityDetectorsTest.ruleProposableKindsIsEmpty();
		QualityDetectorsTest.executorLegacyIdentifierTypesStillWork();
		QualityDetectorsTest.executorGenericAlgorithmNamesWork();
		QualityDetectorsTest.executorUnknownTypeIsNeverValid();
		System.out.println("QualityDetectorsTest OK");
	}

	/** {@code ebx.QualitySuggester}'s withdrawal sweep relies on this being empty: nothing this class does deterministically is ever "still proposable" any more, so every RULE-origin QualityRule row (there was only ever UNIQUE) is swept if still PROPOSED, or flagged if already decided. */
	private static void ruleProposableKindsIsEmpty()
	{
		QualityDetectorsTest.check("RULE_PROPOSABLE_KINDS is empty", QualityDetectors.RULE_PROPOSABLE_KINDS.isEmpty());
	}

	/** {@link QualityDetectors#isCheckDigitValid} is the EXECUTOR: a row accepted before this lot, using the legacy identifier-type names (SIREN/SIRET/IBAN/LEI), must keep validating exactly as before. Real, live values (docs/NORMALISATION.md sec 1.6): 552120222/662042449 (genuine French SIREN), MLU0ZO3ML4LN2LL2TL39 (HSBC's real LEI). */
	private static void executorLegacyIdentifierTypesStillWork()
	{
		QualityDetectorsTest.check("real SIREN valid", QualityDetectors.isCheckDigitValid("SIREN", "552120222"));
		QualityDetectorsTest.check("real SIREN valid (2)", QualityDetectors.isCheckDigitValid("SIREN", "662042449"));
		QualityDetectorsTest.check("flipped last digit invalid", !QualityDetectors.isCheckDigitValid("SIREN", "552120223"));
		QualityDetectorsTest.check("real LEI valid, case-insensitive (normalised first)", QualityDetectors.isCheckDigitValid("LEI", "mlu0zo3ml4ln2ll2tl39"));
		QualityDetectorsTest.check("wrong-length LEI invalid", !QualityDetectors.isCheckDigitValid("LEI", "529900T8BM49AURSDO5"));
		QualityDetectorsTest.check("IBAN with separators still validates (stripped first)",
			QualityDetectors.isCheckDigitValid("IBAN", "FR14 2004 1010 0505 0001 3M02 606"));
	}

	/** The generic algorithm names a language-model proposal (or a person) uses from now on - same underlying algorithms, same real values. */
	private static void executorGenericAlgorithmNamesWork()
	{
		QualityDetectorsTest.check("LUHN accepts a real SIREN", QualityDetectors.isCheckDigitValid("LUHN", "552120222"));
		QualityDetectorsTest.check("MOD97_IBAN is the same check as legacy IBAN", QualityDetectors.isCheckDigitValid("MOD97_IBAN", "FR1420041010050500013M02606"));
		QualityDetectorsTest.check("ISO17442_LEI is the same check as legacy LEI", QualityDetectors.isCheckDigitValid("ISO17442_LEI", "MLU0ZO3ML4LN2LL2TL39"));
		QualityDetectorsTest.check("GS1_MOD10 accepts a real EAN-13", QualityDetectors.isCheckDigitValid("GS1_MOD10", "4006381333931"));
		QualityDetectorsTest.check("every algorithm name is listed", QualityDetectors.CHECK_DIGIT_ALGORITHMS.containsAll(
			Arrays.asList("LUHN", "MOD97_IBAN", "ISO17442_LEI", "GS1_MOD10", "SIREN", "SIRET", "IBAN", "LEI")));
	}

	private static void executorUnknownTypeIsNeverValid()
	{
		QualityDetectorsTest.check("an unrecognised type is simply never valid, never throws", !QualityDetectors.isCheckDigitValid("NOT_AN_ALGORITHM", "552120222"));
		QualityDetectorsTest.check("blank value is never valid", !QualityDetectors.isCheckDigitValid("SIREN", "  "));
		QualityDetectorsTest.check("null value is never valid", !QualityDetectors.isCheckDigitValid("SIREN", null));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
