package com.on.ebx.factory.landing;

/**
 * Landing key validated in the walkthrough (2026-10-17, project TiersB2CRetestStaging, fix/landing-validated-key): the decision the landing run's KEY stage
 * makes for a table whose application the person VALIDATED at step 3 and whose key is still a deduction (origin RULE, or a language-model proposal at or above the
 * certainty bar - exactly the keys {@link com.on.ebx.factory.wizard.LandingWalk#keyState} shows as "deduced - N % - checked at the load").
 * <p>
 * The walkthrough promises "checked at the load" for such a key. Until this class the run ignored the validation: {@link KeyCertainty#certain} wants the sample AND the
 * whole source to show the key unique, a sample file's source can rarely be verified, so the run put the table in WAITING and the person, who had validated the page and
 * launched, found FLOW and LOAD waiting for a key they had accepted. A validated key now counts as accepted by that person; the run still measures what it can and
 * <b>fails</b> the KEY cell, with the reason, when a measure PROVES the key is not unique - it never waits silently:
 * <ul>
 * <li>sample proves it not unique (a repeated value, or an empty one on some row): {@link #NOT_UNIQUE_SAMPLE};</li>
 * <li>the whole source proves it not unique (a collision found by {@code KeySourceUniqueness}): {@link #NOT_UNIQUE_SOURCE};</li>
 * <li>otherwise {@link #ACCEPT}, with whether the sample actually showed it unique ({@link Verdict#measured}) - a sample that could not be read does not block a key the
 * person validated, it is said so in the cell.</li>
 * </ul>
 * A table the validation does not concern (application not validated, no key columns, a key a person or the source already settled, a model proposal under the bar,
 * which the walkthrough shows as "no key") is {@link #NOT_APPLICABLE}: the run follows its ordinary rules. Pure: no EBX class.
 */
public final class ValidatedKey
{
	public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
	public static final String ACCEPT = "ACCEPT";
	public static final String NOT_UNIQUE_SAMPLE = "NOT_UNIQUE_SAMPLE";
	public static final String NOT_UNIQUE_SOURCE = "NOT_UNIQUE_SOURCE";

	private ValidatedKey()
	{
	}

	/** What the run does with the table's key. */
	public static final class Verdict
	{
		/** One of the constants above. */
		public final String kind;
		/** For {@link #ACCEPT}: the sample was read and showed the key unique (false: it could not be measured). */
		public final boolean measured;

		Verdict(final String pKind, final boolean pMeasured)
		{
			this.kind = pKind;
			this.measured = pMeasured;
		}

		public boolean accepted()
		{
			return ACCEPT.equals(this.kind);
		}

		public boolean failed()
		{
			return NOT_UNIQUE_SAMPLE.equals(this.kind) || NOT_UNIQUE_SOURCE.equals(this.kind);
		}
	}

	/**
	 * Whether the walkthrough's validation covers this key: key columns exist, the origin is not one a person or the source already settled ({@link
	 * KeyCertainty#alreadySettled}), and it is not a language-model proposal under the certainty bar (the walkthrough shows that one as "no key").
	 */
	public static boolean covers(final String pOrigin, final int pConfidence, final boolean pHasKeyColumns)
	{
		if (!pHasKeyColumns || KeyCertainty.alreadySettled(pOrigin, true, pConfidence))
		{
			return false;
		}
		return !("LLM".equals(pOrigin) && pConfidence < KeyCertainty.LLM_CONFIDENCE_MIN);
	}

	/**
	 * @param pValidated {@code ProjectSource.landingValidated} of the table's application
	 * @param pOrigin the table's aggregated key origin
	 * @param pConfidence {@code LandingTable.keyConfidence}
	 * @param pHasKeyColumns the table has key columns
	 * @param pUniqueInSample {@link KeyEvidence#uniqueTogether} of the CURRENT key on the sample; null when it could not be told
	 * @param pUniqueAtSource {@code KeySourceUniqueness}'s verdict; null when it could not be verified (or was not asked because the sample already proved the opposite)
	 */
	public static Verdict decide(final boolean pValidated, final String pOrigin, final int pConfidence, final boolean pHasKeyColumns, final Boolean pUniqueInSample,
		final Boolean pUniqueAtSource)
	{
		if (!pValidated || !ValidatedKey.covers(pOrigin, pConfidence, pHasKeyColumns))
		{
			return new Verdict(NOT_APPLICABLE, false);
		}
		if (Boolean.FALSE.equals(pUniqueInSample))
		{
			return new Verdict(NOT_UNIQUE_SAMPLE, false);
		}
		if (Boolean.FALSE.equals(pUniqueAtSource))
		{
			return new Verdict(NOT_UNIQUE_SOURCE, false);
		}
		return new Verdict(ACCEPT, Boolean.TRUE.equals(pUniqueInSample));
	}

	/** Whether the source-wide check is worth asking for: not when the sample already proved the key not unique. */
	public static boolean askSource(final Boolean pUniqueInSample)
	{
		return !Boolean.FALSE.equals(pUniqueInSample);
	}
}
