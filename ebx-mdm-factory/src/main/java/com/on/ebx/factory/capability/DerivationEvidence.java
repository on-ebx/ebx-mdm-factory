package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * fix/match-key-confidence (2026-09-26): the stored confidence of a {@code SCRIPT_DERIVATION} the model states
 * is a cross-system key ({@link CapabilityLlmPass.ProposedScriptDerivation#crossSystemKey}) - decided from
 * MEASURED coverage, not from the model's own number. Pure (no EBX class): {@code ebx.CapabilityLlmSuggester}
 * measures, this class decides.
 * <p>
 * <b>Why.</b> Volume test PersVol4: the derived key {@code phoneE164} was stored at 50%, so the Mapping
 * matrix's one-click "Accept the N unflagged proposals &gt;= 60%" left it (and its suggestion) out, while the
 * rule-proposed FOLD_KEY keys of the same entity ({@code quality.CrossSystemFold}, confidence 85) went through.
 * Without it the matching plan used neither phone nor birth date and person recall fell from 75% to 71%. A
 * key's worth for matching is a measurable fact - is its source filled on every system that feeds the entity -
 * not something the model can know better than the data.
 * <p>
 * <b>Rule.</b> When the model says {@code crossSystemKey}, at least two systems feed the entity, and EVERY
 * attribute the binding reads has at least one filled value on EVERY one of those systems, the stored
 * confidence is {@code max(model, }{@link #MEASURED_CROSS_SYSTEM_KEY_CONFIDENCE}{@code )} - the SAME level the
 * rule-proposed fold keys get, so both kinds of matching key are treated alike by every bulk accept. Otherwise
 * the model's number stands unchanged (never lowered by this class). The matrix's own safety net is untouched:
 * a flagged attribute or cell is still excluded from the one-click accept whatever its confidence ({@code
 * mappingmatrix.MatrixCompute#planEntityBulk}).
 * <p>
 * "Filled" means at least one non-blank value measured on that system's mapped landing column - coverage of
 * the source, not a promise that the library function will compute a value for every row (a value it cannot
 * canonicalise comes back null, which is the honest outcome for a matching key).
 */
public final class DerivationEvidence
{
	private DerivationEvidence()
	{
	}

	/** Same value as {@code quality.CrossSystemFold}'s own base confidence for a rule-proposed matching key - the two kinds of key are meant to be treated alike. */
	public static final int MEASURED_CROSS_SYSTEM_KEY_CONFIDENCE = 85;

	/** One measured (attribute, system) pair: the rows and non-blank values of that system's landing column mapped to the attribute. */
	public static final class SourceFill
	{
		public final String attribute;
		public final String system;
		public final long rows;
		public final long filled;

		public SourceFill(final String pAttribute, final String pSystem, final long pRows, final long pFilled)
		{
			this.attribute = pAttribute;
			this.system = pSystem;
			this.rows = pRows;
			this.filled = pFilled;
		}
	}

	/** The decided confidence, whether it was raised, and a one-sentence note for the stored rationale (null when there is nothing to say). */
	public static final class Decision
	{
		public final int confidence;
		public final boolean raised;
		public final String note;

		Decision(final int pConfidence, final boolean pRaised, final String pNote)
		{
			this.confidence = pConfidence;
			this.raised = pRaised;
			this.note = pNote;
		}
	}

	/**
	 * @param pModelConfidence the model's own number (already clamped 0-100).
	 * @param pCrossSystemKey the model's statement that the derivation is a cross-system key.
	 * @param pBoundAttributes every attribute the derivation's binding reads.
	 * @param pSystemsFeedingEntity every distinct source system with a mapping on the entity.
	 * @param pFills every measured (attribute, system) pair - several entries for one pair are summed.
	 */
	public static Decision decide(final int pModelConfidence, final boolean pCrossSystemKey, final List<String> pBoundAttributes,
		final Set<String> pSystemsFeedingEntity, final List<SourceFill> pFills)
	{
		if (!pCrossSystemKey)
		{
			return new Decision(pModelConfidence, false, null);
		}
		final Set<String> systems = pSystemsFeedingEntity == null ? Collections.<String>emptySet() : pSystemsFeedingEntity;
		if (systems.size() < 2)
		{
			return new Decision(pModelConfidence, false, "Cross-system key: only " + systems.size()
				+ " system feeds this entity, so there is no cross-system coverage to measure - the proposed confidence is kept.");
		}
		if (pBoundAttributes == null || pBoundAttributes.isEmpty())
		{
			return new Decision(pModelConfidence, false, null);
		}
		final List<String> gaps = new ArrayList<String>();
		for (final String attribute : new LinkedHashSet<String>(pBoundAttributes))
		{
			for (final String system : systems)
			{
				if (DerivationEvidence.filledOn(pFills, attribute, system) <= 0L)
				{
					gaps.add(attribute + " on " + system);
				}
			}
		}
		final String systemList = DerivationEvidence.join(new ArrayList<String>(systems));
		if (!gaps.isEmpty())
		{
			return new Decision(pModelConfidence, false, "Cross-system key: no measured value for " + DerivationEvidence.join(gaps)
				+ " - the proposed confidence is kept.");
		}
		final int confidence = Math.max(pModelConfidence, DerivationEvidence.MEASURED_CROSS_SYSTEM_KEY_CONFIDENCE);
		return new Decision(confidence, confidence != pModelConfidence, "Cross-system key: " + DerivationEvidence.join(new ArrayList<String>(
			new LinkedHashSet<String>(pBoundAttributes))) + " measured filled on every system feeding this entity (" + systemList + ") - confidence " + confidence
			+ "% set by the factory from that coverage (proposed " + pModelConfidence + "%).");
	}

	private static long filledOn(final List<SourceFill> pFills, final String pAttribute, final String pSystem)
	{
		long filled = 0L;
		if (pFills == null)
		{
			return filled;
		}
		for (final SourceFill fill : pFills)
		{
			if (pAttribute.equals(fill.attribute) && pSystem.equals(fill.system))
			{
				filled += fill.filled;
			}
		}
		return filled;
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append(value);
		}
		return out.toString();
	}
}
