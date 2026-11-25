package com.on.ebx.factory.review;

import java.util.Map;

/**
 * The review package's own read-side mirror of {@code master.MatchPlanEvidence.Field} - the SAME JSON
 * shape ({@code MatchPlan.evidence}), parsed independently by {@link MatchPlanView} rather than importing
 * the {@code master} package's class: every other class in this package touches only {@code java.*}/
 * {@code review.*} (see e.g. {@link ReviewPage}'s own class Javadoc), so this package stays free of every
 * domain package's classes and is unit-tested without any of them. JSON is the agreed contract between
 * the two independent readers/writers, documented in both classes' Javadocs and in {@code
 * docs/MATCHING.md} - the string constants below must keep the exact same wire values as {@code
 * master.MatchPlanEvidence}'s own constants.
 */
public final class MatchPlanFieldEvidence
{
	/** Mirrors {@code master.MatchPlanEvidence#ROLE_ANCHOR_FUZZY}. */
	public static final String ROLE_ANCHOR_FUZZY = "ANCHOR_FUZZY";
	/** Mirrors {@code master.MatchPlanEvidence#ROLE_SUPPORTING_EXACT}. */
	public static final String ROLE_SUPPORTING_EXACT = "SUPPORTING_EXACT";
	/** Mirrors {@code master.MatchPlanEvidence#ROLE_CLUSTERING}. */
	public static final String ROLE_CLUSTERING = "CLUSTERING";
	/** Mirrors {@code master.MatchPlanEvidence#ROLE_REJECTED}. */
	public static final String ROLE_REJECTED = "REJECTED";
	/** Mirrors {@code master.MatchPlanEvidence#ROLE_SURVIVORSHIP} (survivorship lot, 2026-09-22). */
	public static final String ROLE_SURVIVORSHIP = "SURVIVORSHIP";

	/** Mirrors {@code master.MatchPlanEvidence#VERDICT_OK}. */
	public static final String VERDICT_OK = "OK";
	/** Mirrors {@code master.MatchPlanEvidence#VERDICT_REFUSED}. */
	public static final String VERDICT_REFUSED = "REFUSED";
	/** Mirrors {@code master.MatchPlanEvidence#VERDICT_NOT_MEASURED}. */
	public static final String VERDICT_NOT_MEASURED = "NOT_MEASURED";

	public final String field;
	public final String role;
	public final Integer sharedAcrossSystems;
	public final Integer distinct;
	public final Map<String, Long> filledBySystem;
	public final String verdict;
	public final String note;
	/** Mirrors {@code master.MatchPlanEvidence.Field#confidence} (survivorship lot, 2026-09-22) - {@code "high"}/{@code "medium"}/{@code "low"}, null for every role but {@link #ROLE_SURVIVORSHIP}. */
	public final String confidence;
	/** Mirrors {@code master.MatchPlanEvidence.Field#validBySystem} (lot 1, 2026-09-23, quality-informed survivorship) - null when the field carries no validity rule, or was never measured. */
	public final Map<String, Validity> validBySystem;

	/** Convenience for every caller built before the survivorship lot (no confidence to give) - equivalent to passing null. */
	public MatchPlanFieldEvidence(final String pField, final String pRole, final Integer pSharedAcrossSystems, final Integer pDistinct,
		final Map<String, Long> pFilledBySystem, final String pVerdict, final String pNote)
	{
		this(pField, pRole, pSharedAcrossSystems, pDistinct, pFilledBySystem, pVerdict, pNote, null);
	}

	/** Convenience for every caller built before lot 1 (no per-system validity to give) - equivalent to passing null. */
	public MatchPlanFieldEvidence(final String pField, final String pRole, final Integer pSharedAcrossSystems, final Integer pDistinct,
		final Map<String, Long> pFilledBySystem, final String pVerdict, final String pNote, final String pConfidence)
	{
		this(pField, pRole, pSharedAcrossSystems, pDistinct, pFilledBySystem, pVerdict, pNote, pConfidence, null);
	}

	/** Full form - see {@link #confidence} and {@link #validBySystem} (lot 1, 2026-09-23). */
	public MatchPlanFieldEvidence(final String pField, final String pRole, final Integer pSharedAcrossSystems, final Integer pDistinct,
		final Map<String, Long> pFilledBySystem, final String pVerdict, final String pNote, final String pConfidence, final Map<String, Validity> pValidBySystem)
	{
		this.field = pField;
		this.role = pRole;
		this.sharedAcrossSystems = pSharedAcrossSystems;
		this.distinct = pDistinct;
		this.filledBySystem = pFilledBySystem;
		this.verdict = pVerdict;
		this.note = pNote == null ? "" : pNote;
		this.confidence = pConfidence;
		this.validBySystem = pValidBySystem;
	}

	/** Mirrors {@code master.MatchPlanEvidence.Validity} - one source system's own {@code checked}/{@code valid} totals. Lot 1, 2026-09-23. */
	public static final class Validity
	{
		public final long checked;
		public final long valid;

		public Validity(final long pChecked, final long pValid)
		{
			this.checked = pChecked;
			this.valid = pValid;
		}
	}
}
