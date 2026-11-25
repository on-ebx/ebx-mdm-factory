package com.on.ebx.factory.quality;

import java.util.List;

/**
 * Vocabulary and EXECUTOR for {@code QualityRule} (validation - see {@link Detectors} for the cleanup-step
 * counterpart, {@code MappingStep}). Pure: no EBX class.
 * <p>
 * <b>Domain-heuristics removal (2026-09-21, product owner review):</b> every family that decided WHICH check
 * to run from a column NAME is gone - {@code identifierTypeOf} (a column literally named {@code SIREN}/
 * {@code SIRET}/{@code IBAN}/{@code LEI} got a check digit proposed for it), the BIC shape check (name-hinted
 * on "bic"), and the country/currency {@code REFERENCE} proposal (name-hinted on "country"/"currency"). Those
 * only ever made sense for party/company data.
 * <p>
 * <b>{@code UNIQUE} - the last kind this class itself still proposed - moved to the language model too
 * (coordinator review, 2026-09-21): "&gt;= 90% distinct" fires on any free-text column at all (a product
 * description, a person's name) - whether uniqueness is EXPECTED on a given column is business knowledge, not
 * a statistic a Java rule can infer from shape alone.</b> This class therefore proposes NOTHING on its own any
 * more: {@link #RULE_PROPOSABLE_KINDS} is empty, so a {@code RULE}-origin, still-{@code PROPOSED} {@code
 * UNIQUE} row from before this change is withdrawn by {@code ebx.QualitySuggester}'s sweep exactly like every
 * other removed heuristic's leftovers (never a row a person already decided on). {@code UNIQUE} stays fully
 * available through {@code quality.QualityLlmPass}'s {@code propose_quality_rule} tool, MEASURED against the
 * EXACT duplicate count of the real data (never the capped sample - see that class's own Javadoc).
 * <p>
 * <b>This class's kind vocabulary ({@link #MANDATORY}/{@link #PATTERN}/{@link #REFERENCE}/{@link
 * #CHECK_DIGIT}/{@link #UNIQUE}) and its executor entry point ({@link #isCheckDigitValid}, used by {@code
 * QualityRuleCheck} at transfer time and by {@code QualityLlmPass}'s own {@code measure} tool) are
 * UNCHANGED</b> - a person's earlier decision, or a language-model proposal accepted under {@code
 * QualityLlmPass}, still runs exactly as before, including the four legacy {@code CHECK_DIGIT} argument names
 * kept forever ({@link #LEGACY_SIREN}/{@link #LEGACY_SIRET}/{@link #LEGACY_IBAN}/{@link #LEGACY_LEI}).
 */
public final class QualityDetectors
{
	public static final String MANDATORY = "MANDATORY";
	public static final String PATTERN = "PATTERN";
	public static final String REFERENCE = "REFERENCE";
	public static final String CHECK_DIGIT = "CHECK_DIGIT";
	public static final String UNIQUE = "UNIQUE";

	public static final String MODEL_CONSTRAINT = "MODEL_CONSTRAINT";
	public static final String REJECT = "REJECT";
	public static final String WARN = "WARN";

	/** Legacy {@code CHECK_DIGIT} argument values, accepted forever so a row a person already accepted keeps executing exactly as before - never proposed by name any more (see the class Javadoc), only recognised on execution. */
	public static final String LEGACY_SIREN = "SIREN";
	public static final String LEGACY_SIRET = "SIRET";
	public static final String LEGACY_IBAN = "IBAN";
	public static final String LEGACY_LEI = "LEI";
	/** Generic {@code CHECK_DIGIT} algorithm names - the toolbox vocabulary a language model (or a person) picks from, domain-free by construction: none of these names a business identifier, only a published algorithm. See {@link CheckDigits}. */
	public static final String ALGORITHM_LUHN = "LUHN";
	public static final String ALGORITHM_MOD97_IBAN = "MOD97_IBAN";
	public static final String ALGORITHM_ISO17442_LEI = "ISO17442_LEI";
	public static final String ALGORITHM_GS1_MOD10 = "GS1_MOD10";
	/** Every {@code CHECK_DIGIT} argument this class's {@link #isCheckDigitValid} EXECUTOR understands - legacy names included, since a row a person accepted before 2026-09-21 must keep executing exactly as before. Never offer this list to a proposer (model or otherwise) - see {@link #CHECK_DIGIT_ALGORITHMS_PROPOSABLE}. */
	public static final List<String> CHECK_DIGIT_ALGORITHMS = java.util.Collections.unmodifiableList(java.util.Arrays.asList(QualityDetectors.ALGORITHM_LUHN,
		QualityDetectors.ALGORITHM_MOD97_IBAN, QualityDetectors.ALGORITHM_ISO17442_LEI, QualityDetectors.ALGORITHM_GS1_MOD10, QualityDetectors.LEGACY_SIREN,
		QualityDetectors.LEGACY_SIRET, QualityDetectors.LEGACY_IBAN, QualityDetectors.LEGACY_LEI));
	/**
	 * Generic algorithm names only - what a PROPOSER (the language model's {@code measure}/{@code
	 * propose_quality_rule} tools) may ever pick from. Coordinator review round 3, 2026-09-21: a real run
	 * proposed {@code CHECK_DIGIT(SIREN)} - the model picked a legacy name straight out of {@link
	 * #CHECK_DIGIT_ALGORITHMS}, because that was the only list {@code QualityLlmPass} ever offered it, and
	 * {@code SIREN} sat right there next to the generic names. A legacy name is an EXECUTION-time compatibility
	 * shim for a row decided before this lot (see the class Javadoc) - it was never meant to be choosable again.
	 * The fix is a second, narrower list, used ONLY by the proposer-facing validity checks; {@link
	 * #isCheckDigitValid} (the executor) keeps accepting {@link #CHECK_DIGIT_ALGORITHMS} in full, unchanged.
	 */
	public static final List<String> CHECK_DIGIT_ALGORITHMS_PROPOSABLE = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
		QualityDetectors.ALGORITHM_LUHN, QualityDetectors.ALGORITHM_MOD97_IBAN, QualityDetectors.ALGORITHM_ISO17442_LEI, QualityDetectors.ALGORITHM_GS1_MOD10));

	private static final String IDENTIFIER_SEPARATORS = " .-";
	/**
	 * Every {@code QualityRule.kind} this class can still propose on its own (origin {@code RULE}) - EMPTY
	 * since the 2026-09-21 removal of {@code UNIQUE} (see the class Javadoc): nothing here proposes anything
	 * any more. {@code ebx.QualitySuggester}'s withdrawal sweep uses this (alongside {@link
	 * Detectors#RULE_PROPOSABLE_KINDS}) to recognise a {@code RULE}-origin, still-{@code PROPOSED} row of a
	 * kind that will never be re-confirmed again, withdrawing it exactly like one whose mapping was later
	 * rejected. A row of origin {@code LLM}/{@code USER} is never subject to this check.
	 */
	public static final java.util.Set<String> RULE_PROPOSABLE_KINDS = java.util.Collections.emptySet();

	private QualityDetectors()
	{
	}

	// -------------------------------------------------------------------------------------------------- check digit

	/**
	 * Public entry point for EXECUTION (used by {@code QualityRuleCheck} at transfer time, and by {@code
	 * QualityLlmPass}'s own {@code measure} tool to test a hypothesis against the real values before proposing):
	 * the exact normalise-then-validate sequence a {@code CHECK_DIGIT} rule always uses (strip separators,
	 * upper-case, then {@link CheckDigits}). {@code pType} is one of {@link #CHECK_DIGIT_ALGORITHMS} - a
	 * generic algorithm name ({@link #ALGORITHM_LUHN}, {@link #ALGORITHM_MOD97_IBAN}, {@link
	 * #ALGORITHM_ISO17442_LEI}, {@link #ALGORITHM_GS1_MOD10}) or one of the four legacy identifier-type names
	 * kept for a row accepted before this lot ({@link #LEGACY_SIREN}/{@link #LEGACY_SIRET}/{@link
	 * #LEGACY_IBAN}/{@link #LEGACY_LEI}) - an unrecognised {@code pType} is simply never valid, never throws.
	 * {@code null}/blank input is never valid either (never called on one in practice - the caller only checks
	 * a non-blank typed value, mirroring {@code Transformations}'s own "empty is not a reject" rule one level
	 * up).
	 */
	public static boolean isCheckDigitValid(final String pType, final String pRawValue)
	{
		if (pRawValue == null || pRawValue.trim().isEmpty())
		{
			return false;
		}
		final String normalised = CleaningSteps.applyStep(CleaningSteps.NORMALISE_IDENTIFIER, QualityDetectors.IDENTIFIER_SEPARATORS, pRawValue, null, null).value;
		return QualityDetectors.checkDigitValid(pType, normalised);
	}

	private static boolean checkDigitValid(final String pType, final String pNormalisedValue)
	{
		if (QualityDetectors.LEGACY_SIREN.equals(pType))
		{
			return pNormalisedValue.length() == 9 && CheckDigits.isValidLuhn(pNormalisedValue);
		}
		if (QualityDetectors.LEGACY_SIRET.equals(pType))
		{
			return pNormalisedValue.length() == 14 && CheckDigits.isValidLuhn(pNormalisedValue);
		}
		if (QualityDetectors.LEGACY_IBAN.equals(pType) || QualityDetectors.ALGORITHM_MOD97_IBAN.equals(pType))
		{
			return CheckDigits.isValidIban(pNormalisedValue);
		}
		if (QualityDetectors.LEGACY_LEI.equals(pType) || QualityDetectors.ALGORITHM_ISO17442_LEI.equals(pType))
		{
			return pNormalisedValue.length() == 20 && CheckDigits.isValidLei(pNormalisedValue);
		}
		if (QualityDetectors.ALGORITHM_LUHN.equals(pType))
		{
			return CheckDigits.isValidLuhn(pNormalisedValue);
		}
		if (QualityDetectors.ALGORITHM_GS1_MOD10.equals(pType))
		{
			return CheckDigits.isValidGs1Mod10(pNormalisedValue);
		}
		return false;
	}
}
