package com.on.ebx.factory.landing;

/**
 * Whether a landing table's key can be trusted without a person's review, given who proposed it and what
 * the sample said about it (composite-key lot, 2026-09-24 - lot LANDING uses this to decide whether a table
 * with a proposed key goes straight through or waits for someone to look at it). Never deduced from the
 * source data itself - only from the origin the key already carries ({@code LandingColumn.sourceKeyOrigin})
 * and {@link KeyEvidence#uniqueTogether}'s own verdict on the CURRENT sample, both already measured
 * elsewhere. Pure: no EBX class.
 * <ul>
 * <li>{@code SOURCE} - the catalog's own declared primary key (an EBX or database source stating its real
 * key: {@code CatalogReader.Column#primaryKey}). Always certain: a sample cannot second-guess what the
 * source itself declares.</li>
 * <li>{@code USER} - a person chose it. Always certain, by definition - a human decision is never
 * overridden by a pass (the rule every proposal of this module follows).</li>
 * <li>{@code DEDUCED} - a deduced-scan table's key, proposed by the language model during the scan and
 * confirmed by a person via board 2e ({@code LandingPlanner}'s own choice of origin, fix lot 2026-09-27,
 * round 5 - it used to be written as plain {@code LLM} with confidence forced to exactly 100, a fragile
 * signature this class had to special-case; {@code LandingColumn.sourceKeyOrigin} now carries {@code
 * DEDUCED} directly). Always certain, same reasoning as {@code USER}: a person already looked at it and
 * confirmed it, so neither this method nor {@link #alreadySettled} may ever second-guess it again.</li>
 * <li>{@code RULE} - the naming-rule guess ({@link SourceKeyRule}). Certain only when the sample shows the
 * key unique together with no empty value, AND {@code ebx.KeySourceUniqueness} confirms it unique across
 * the WHOLE source (see {@link #certain}'s own {@code pUniqueAtSource} parameter, volume-guards lot,
 * 2026-09-28) - "evidence, not proof" is no longer enough for the sample alone to auto-apply a key at
 * volume: a 25-row sample being unique is not the same claim as the source's millions of rows being
 * unique, and only the source-wide check can tell the two apart.</li>
 * <li>{@code LLM} - the language-model proposal ({@code KeyLlmPass}). <b>Never certain</b> since 2026-10-07
 * (the product owner's decision on audit F-28): it is a suggestion a person confirms, whatever its confidence and even
 * when the sample and the whole source show it unique - a hostile or misleading column could steer the model
 * toward a unique column that is not the business identifier, and only a person can tell. The run keeps the
 * proposal ({@code ebx.KeyStudyRun#keepRunProposal}) and the key chooser opens on it, pre-ticked, so
 * confirming it is one click. {@link #LLM_CONFIDENCE_MIN} is now only what the waiting message calls a
 * confident proposal.</li>
 * <li>anything else (an unrecognised or blank origin) - never certain: the safe default when the origin
 * itself cannot be read.</li>
 * </ul>
 */
public final class KeyCertainty
{
	/** {@code LLM}-origin confidence (0-100) from which the waiting message presents the proposal as a confident suggestion (never applied on its own, see the class Javadoc). */
	public static final int LLM_CONFIDENCE_MIN = 90;

	private KeyCertainty()
	{
	}

	/**
	 * Volume-guards lot (2026-09-28, task item 4, "landing key applied only if unique at the source"): a fifth
	 * parameter, {@code pUniqueAtSource}, added on top of the pre-existing sample check - a {@code RULE}/{@code
	 * LLM} key must now be confirmed unique against the WHOLE source, not merely {@code KeyStudy.SAMPLE_MAX}
	 * (25) sample rows, before it is auto-applied with nobody looking. {@code
	 * ebx.KeySourceUniqueness} computes it: {@code TRUE} for a SQL source where {@code COUNT(DISTINCT key
	 * expr) == COUNT(*)} over the real table, or a bounded file/recipe source read in full with the same
	 * check done in Java; {@code FALSE} when that same check finds a collision; {@code null} when it could
	 * not be run at all (an unsupported connector type, a source too large to read in full, a transport
	 * failure) - {@code null} is treated exactly like {@code FALSE} here (never assumed true), per the task
	 * brief's own "not verifiable -&gt; not certain -&gt; the chooser is shown".
	 *
	 * @param pOrigin {@code LandingColumn.sourceKeyOrigin} ({@code SOURCE}/{@code RULE}/{@code LLM}/{@code USER}/{@code DEDUCED}) of the table's key
	 * @param pConfidence {@code LandingTable.keyConfidence}, 0-100
	 * @param pUniqueInSample {@link KeyEvidence#uniqueTogether} on the CURRENT sample and the CURRENT key columns; null when it could not be told (no sample row, or a key column the sample does not have) - treated the same as false, never assumed true
	 * @param pUniqueAtSource {@code ebx.KeySourceUniqueness}'s verdict against the WHOLE source - see this method's own Javadoc above. Ignored for {@code SOURCE}/{@code USER}/{@code DEDUCED} (always certain regardless, unchanged from before this lot).
	 */
	public static boolean certain(final String pOrigin, final int pConfidence, final Boolean pUniqueInSample, final Boolean pUniqueAtSource)
	{
		if ("SOURCE".equals(pOrigin) || "USER".equals(pOrigin) || "DEDUCED".equals(pOrigin))
		{
			return true;
		}
		if (!Boolean.TRUE.equals(pUniqueInSample) || !Boolean.TRUE.equals(pUniqueAtSource))
		{
			return false;
		}
		// LLM: a suggestion, never applied without a person (2026-10-07) - see the class Javadoc
		return "RULE".equals(pOrigin);
	}

	/**
	 * Fix lot (2026-09-26, defect 11, project DeducedTest2): whether the "find each key" stage of "Prepare the
	 * landing" ({@code ebx.LandingRun#runKeyStage}) must skip calling the model AT ALL for this table's CURRENT
	 * key - never re-derive a key a person already confirmed. {@code USER}/{@code SOURCE} were already skipped
	 * there; a deduced-scan-confirmed key was the gap this fixed.
	 * <p>
	 * <b>Fix lot (2026-09-27, round 5, task item 3):</b> that gap used to be closed with a fragile signature -
	 * a deduced-and-confirmed key carried plain {@code LLM} origin with confidence forced to exactly 100 by
	 * {@code LandingPlanner}, indistinguishable in this method's own vocabulary from a genuine, still-uncertain
	 * language-model proposal that also happened to score 100. {@code LandingColumn.sourceKeyOrigin} now has
	 * its own {@code DEDUCED} value ({@code tools/build_xsd.py}'s {@code KEY_ORIGIN} vocabulary) - checked here
	 * the same way as {@code USER}/{@code SOURCE}, with no confidence check at all: settled the moment a
	 * person confirmed it, whatever its confidence reads. The old {@code LLM}+confidence-100 signature is
	 * dropped outright, not kept as a fallback: a plain {@code LLM} proposal (genuine or at confidence 100)
	 * is never settled by this method any more, only re-verified like any other {@code LLM} key - the only
	 * loss is one avoidable model call on a row still carrying the STALE pre-fix signature (a deduced-and-
	 * confirmed key written before this fix), and only until the next "Prepare the landing" plans that table
	 * again: planning is idempotent and rewrites {@code sourceKeyOrigin} to {@code DEDUCED} for such a row
	 * (see {@code LandingPlanner}) - no migration of existing rows was written for this fix. {@code
	 * pConfidence} is kept in the signature for call-site parity with {@link #certain}, but no longer read
	 * here.
	 */
	public static boolean alreadySettled(final String pOrigin, final boolean pHasKeyColumns, final int pConfidence)
	{
		if (!pHasKeyColumns)
		{
			return false;
		}
		return "USER".equals(pOrigin) || "SOURCE".equals(pOrigin) || "DEDUCED".equals(pOrigin);
	}

	/**
	 * Key-confidence lot (2026-09-28, project DeducedTest3, live incident): the canonical {@code
	 * LandingTable.keyConfidence}/{@code keyRationale} a settled key ({@link #alreadySettled}) must always
	 * read - so a stale value an earlier faulty pass left on the table (here: a study proposal's low
	 * confidence, written by {@code WizardData.recordKeyProposal} back when {@code WizardData#originRank}
	 * did not yet know {@code DEDUCED} and let a settled key fall through to a full re-study - &sect;14 of
	 * {@code docs/DEDUCED-SCAN.md}) can never survive: {@code ebx.LandingRun#runKeyStage} rewrites the table
	 * to this value whenever it finds the key already settled and the table does not already carry it.
	 * <p>
	 * The rationale text matches, word for word, what {@code ebx.LandingPlanner} and {@code
	 * WizardData#setTableKey}'s {@code USER} default already write at the moment the key is FIRST settled -
	 * this method does not invent a new convention, it only lets a later pass restate the same one. Returns
	 * {@code null} for any origin {@link #alreadySettled} does not recognise (there is nothing canonical to
	 * self-heal to for a {@code RULE}/{@code LLM}/unrecognised key - those keep whatever confidence the
	 * proposal that was actually applied carried).
	 */
	public static Settled settledConfidence(final String pOrigin)
	{
		if ("SOURCE".equals(pOrigin))
		{
			return new Settled(100, KeyTexts.declared());
		}
		if ("USER".equals(pOrigin))
		{
			return new Settled(100, KeyTexts.chosen());
		}
		if ("DEDUCED".equals(pOrigin))
		{
			return new Settled(100, KeyTexts.deduced());
		}
		return null;
	}

	/**
	 * The exact decision {@code ebx.LandingRun#runKeyStage} makes once it finds a table's key already {@link
	 * #alreadySettled}: whether to correct the table's stored {@code keyConfidence}/{@code keyRationale} to
	 * the canonical settled value, and to what. Pure, so the run's own decision can be tested without EBX -
	 * {@code runKeyStage} itself only supplies the CURRENT stored values and, if this returns non-null,
	 * writes them via {@code WizardData.settleKeyConfidence}.
	 *
	 * @param pOrigin the table's aggregated {@code keyOrigin} ({@code WizardSnapshotTable#keyOrigin})
	 * @param pConfidence the table's CURRENT {@code LandingTable.keyConfidence}
	 * @param pRationale the table's CURRENT {@code LandingTable.keyRationale} (never null in practice, but
	 * treated as {@code ""} here if it is)
	 * @return {@code null} when nothing needs writing (the origin is not settled, or the table already
	 * carries the canonical value) - otherwise the canonical value to write
	 */
	public static Settled staleSettledCorrection(final String pOrigin, final int pConfidence, final String pRationale)
	{
		final Settled canonical = KeyCertainty.settledConfidence(pOrigin);
		if (canonical == null)
		{
			return null;
		}
		// key rationale i18n (2026-10-17): the stored text may carry its code marker; it is the sentence that must be the canonical one
		final String rationale = pRationale == null ? "" : com.on.ebx.factory.wizard.EngineMessageCodec.decode(pRationale).raw;
		if (pConfidence == canonical.confidence && canonical.rationale.equals(rationale))
		{
			return null;
		}
		return canonical;
	}

	/** The confidence (0-100) and rationale a settled key's origin canonically carries - see {@link #settledConfidence}. */
	public static final class Settled
	{
		public final int confidence;
		/** The English sentence; {@link #stored()} is what is written. */
		public final String rationale;
		/** Key rationale i18n (2026-10-17): the same sentence as a code. */
		public final KeyTexts.Text text;

		Settled(final int pConfidence, final KeyTexts.Text pText)
		{
			this.confidence = pConfidence;
			this.text = pText;
			this.rationale = pText.english;
		}

		/** The English rationale followed by its code marker (key rationale i18n, 2026-10-17). */
		public String stored()
		{
			return this.text.stored();
		}
	}
}
