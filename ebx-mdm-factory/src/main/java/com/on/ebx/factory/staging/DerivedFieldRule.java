package com.on.ebx.factory.staging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CrossSystemFold;
import com.on.ebx.factory.quality.QualityRuleCheck;
import com.on.ebx.factory.quality.ValidValueDerivation;

/**
 * Computes every derived field of one entity from a row's own already-planned fields, pulled out of {@code
 * ebx.StagingLoader.planRow} as its own pure seam, the same discipline every other pure/EBX split in this
 * codebase follows (that method is only testable through EBX otherwise). Pure: no EBX class.
 * <p>
 * <b>Two derivations, one call site.</b> {@code FOLD_KEY} (product-owner decision, 2026-09-22 - PLAN.md, "la
 * valeur stockee garde ses accents et sa casse"): {@link CleaningSteps#foldKey} of the source field, for
 * matching. {@code VALID_VALUE} (lot 1, 2026-09-23, {@code docs/MATCHING.md} &sect;23 - {@code
 * quality.ValidValueDerivation}'s own Javadoc for the full story): the source field's OWN value, unchanged,
 * when it passes every one of {@link Field#validityRules}, or {@code null} otherwise - a field a plain
 * {@code longest} merge-survivor rule can then use to always prefer a valid contributing value over an
 * invalid (or missing) one, without the add-on ever evaluating the rule itself (its merge-function vocabulary
 * has no conditional/predicate function - CLAUDE.md &sect;7). Both kinds are computed by the SAME {@link
 * #apply} call, AFTER every ordinary (mapping-driven) field, never instead of one: a derived field itself has
 * NO {@code AttributeMapping}, no cleaning pipeline and no {@code QualityRule} row of its own on the master
 * table, and is never iterated by the ordinary per-mapping loop.
 * <p>
 * {@link Field#sourceFieldName} names the STAGING field (not the landing column) a derived field reads
 * from - the value as this row's own mapping(s) already left it, whichever landing table fed it.
 * <p>
 * <b>"Missing" reads as {@code null}, by construction</b>: when {@code pFields} has no entry for a field's
 * {@link Field#sourceFieldName} (this row's feed never supplies it - {@code NullFillRule}'s own forced-null
 * default already seeded the map with {@code null} for exactly this case) or the entry itself is
 * {@code null}, both derivations simply read {@code null} in and produce {@code null} out - no special-casing
 * needed here, for either kind.
 */
public final class DerivedFieldRule
{
	private DerivedFieldRule()
	{
	}

	/**
	 * One derived field of one entity: which staging field it reads its source value from, which staging
	 * field it writes its result into, which derivation it computes, and (only for {@link
	 * ValidValueDerivation#DERIVATION_VALID_VALUE}) the source attribute's own accepted validity rules.
	 */
	public static final class Field
	{
		public final String sourceFieldName;
		public final String keyFieldName;
		/** {@code quality.CrossSystemFold#DERIVATION_FOLD_KEY} or {@link ValidValueDerivation#DERIVATION_VALID_VALUE}. */
		public final String derivation;
		/** Only ever non-empty for a {@link ValidValueDerivation#DERIVATION_VALID_VALUE} field - the source attribute's own accepted {@code REFERENCE}/{@code CHECK_DIGIT}/{@code PATTERN} rules, as read by {@code ebx.StagingLoader#readQualityRules} - never re-derived here (pure: this class has no EBX access of its own). */
		public final List<QualityRuleCheck.Rule> validityRules;

		/** Convenience for a {@code FOLD_KEY} field (every caller built before lot 1) - equivalent to passing {@code quality.CrossSystemFold.DERIVATION_FOLD_KEY} and an empty rule list. */
		public Field(final String pSourceFieldName, final String pKeyFieldName)
		{
			this(pSourceFieldName, pKeyFieldName, CrossSystemFold.DERIVATION_FOLD_KEY, Collections.<QualityRuleCheck.Rule>emptyList());
		}

		/** Full form - see {@link #derivation}/{@link #validityRules} (lot 1, 2026-09-23). */
		public Field(final String pSourceFieldName, final String pKeyFieldName, final String pDerivation, final List<QualityRuleCheck.Rule> pValidityRules)
		{
			this.sourceFieldName = pSourceFieldName;
			this.keyFieldName = pKeyFieldName;
			this.derivation = pDerivation;
			this.validityRules = pValidityRules == null ? Collections.<QualityRuleCheck.Rule>emptyList() : pValidityRules;
		}
	}

	/**
	 * @param pFields one row's already-planned fields (every ordinary, mapping-driven field of this feed,
	 * already cleaned/transformed) - never mutated; a NEW map is returned.
	 * @param pDerivedFields the entity's derived fields to compute (already filtered by the caller to the ones
	 * that actually exist as a real field on the published staging table - {@code ebx.StagingLoader} applies
	 * the same {@code FieldAvailabilityRule} guard here as for every other field); empty or {@code null} is a
	 * pure pass-through.
	 * @param pLookups only read by a {@link ValidValueDerivation#DERIVATION_VALID_VALUE} field whose rules
	 * include a {@code REFERENCE} kind ({@link QualityRuleCheck#passes} needs it to resolve one) - ignored
	 * otherwise; {@code null} is safe (an unresolved REFERENCE rule then simply fails, the same "degrade, never
	 * throw" rule {@link QualityRuleCheck#passes} already applies).
	 * @return a new map: {@code pFields} plus one entry per {@link Field#keyFieldName}, each set per its own
	 * {@link Field#derivation} - {@link CleaningSteps#foldKey} of the row's own {@link Field#sourceFieldName}
	 * value for {@code FOLD_KEY}, that same value unchanged (or {@code null}) for {@code VALID_VALUE}.
	 */
	public static Map<String, Object> apply(final Map<String, Object> pFields, final List<Field> pDerivedFields, final CleaningSteps.Lookups pLookups)
	{
		final Map<String, Object> out = new LinkedHashMap<String, Object>(pFields == null ? new LinkedHashMap<String, Object>() : pFields);
		if (pDerivedFields == null)
		{
			return out;
		}
		for (final Field derived : pDerivedFields)
		{
			final Object sourceValue = out.get(derived.sourceFieldName);
			if (ValidValueDerivation.DERIVATION_VALID_VALUE.equals(derived.derivation))
			{
				out.put(derived.keyFieldName, DerivedFieldRule.validValue(sourceValue, derived.validityRules, pLookups));
			}
			else if (CrossSystemFold.DERIVATION_FOLD_KEY.equals(derived.derivation))
			{
				out.put(derived.keyFieldName, CleaningSteps.foldKey(sourceValue == null ? null : String.valueOf(sourceValue)));
			}
			// lot M5 (2026-10-04): any other derivation (SCRIPT - kept up to date by EBX triggers, or left empty while not applied; CHILD_KEYS - written by its
			// own post-pass; an unknown value) is NOT computed here. This branch used to be a plain "else": an unknown derivation was read as a
			// fold, which turned an unapplied SCRIPT attribute (phoneE164) into a copy of its source on every row.
		}
		return out;
	}

	/**
	 * Lot V3-S2 (2026-10-04): whether {@link #apply} puts an entry for this field's {@link Field#keyFieldName} in the map - the two derivations it computes
	 * ({@code FOLD_KEY}, {@code VALID_VALUE}). {@code ebx.StagingLoader} reads it to know, before any row is planned, which staging columns a feed's rows will
	 * carry (the layout of the scope {@code all} digests); {@code DerivedFieldRuleTest} pins it against {@link #apply} itself.
	 */
	public static boolean producesKey(final Field pField)
	{
		return ValidValueDerivation.DERIVATION_VALID_VALUE.equals(pField.derivation) || CrossSystemFold.DERIVATION_FOLD_KEY.equals(pField.derivation);
	}

	/** {@code pSourceValue} unchanged when it passes EVERY one of {@code pRules} (the same "every declared rule must agree" reading {@link QualityRuleCheck} applies elsewhere - see this class' own Javadoc); {@code null} when it fails any one of them, is itself {@code null}, or {@code pRules} is empty (nothing to pass, so nothing to confirm - never a guessed "valid"). */
	private static Object validValue(final Object pSourceValue, final List<QualityRuleCheck.Rule> pRules, final CleaningSteps.Lookups pLookups)
	{
		if (pSourceValue == null || pRules == null || pRules.isEmpty())
		{
			return null;
		}
		for (final QualityRuleCheck.Rule rule : pRules)
		{
			if (!QualityRuleCheck.passes(rule, pSourceValue, pLookups))
			{
				return null;
			}
		}
		return pSourceValue;
	}
}
