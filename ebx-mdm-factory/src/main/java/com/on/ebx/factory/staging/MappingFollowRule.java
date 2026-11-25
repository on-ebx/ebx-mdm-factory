package com.on.ebx.factory.staging;

/**
 * A mapping FOLLOWS its attribute unless a person already decided otherwise. Coordinator review, 2026-09-20,
 * after the live refusal on project {@code Companies}: 62 {@code AttributeMapping} rows still {@code
 * PROPOSED} (Supplier 22, Account 17, Contact 11, Company 12) even though every one of their attributes is
 * {@code APPLIED} - "nobody thinks of accepting 62 individual mapping lines one by one, and for a
 * rule-proposed attribute the mapping is not a separate decision at all: 'supplierId comes from
 * SUPPLIER.SUPPLIER_ID, trimmed' is part of the same proposal. For a canonical (LLM) attribute the mappings
 * ARE the proposal (the alignment)."
 * <p>
 * Same shape as {@link TechnicalKeyRule} (a technical-key attribute follows its entity) - a DISTINCT rule
 * rather than a reuse of that one, because the ELIGIBILITY differs: a technical key has NO decision of its
 * own, ever, regardless of origin, while a mapping keeps its OWN Accept/Reject (a reviewer may refuse one
 * source of a multi-source alignment without touching the others). Only a mapping that is still {@code
 * PROPOSED}, of origin {@code RULE} or {@code LLM}, is even eligible to follow its attribute - never a
 * person's own {@code USER} mapping, and never one a person already decided (accepted OR rejected): "a
 * mapping a person REJECTED stays rejected."
 * <p>
 * Used by: {@code ProposalReviews.decide} (cascades a fresh attribute decision onto its eligible mappings in
 * the same procedure, mirroring how it cascades an entity decision onto its technical key), and {@code
 * StagingSuggester} (a backfill, for a mapping row already on the instance whose attribute was decided
 * before this rule existed, or decided by a path that does not cascade - exactly the live {@code Companies}
 * case that triggered this).
 */
public final class MappingFollowRule
{
	private MappingFollowRule()
	{
	}

	/**
	 * Whether a mapping is even a candidate to follow its attribute at all: still {@code PROPOSED}
	 * (an already-decided mapping, by anyone, is never re-touched), and proposed by the rules or the
	 * model (never a person's own {@code USER} mapping).
	 */
	public static boolean isEligible(final String pMappingStatus, final String pMappingOrigin)
	{
		return "PROPOSED".equals(pMappingStatus) && ("RULE".equals(pMappingOrigin) || "LLM".equals(pMappingOrigin));
	}

	/**
	 * The status an eligible mapping should take once its attribute's own status is {@code pAttributeStatus}
	 * - the exact same cascade {@link TechnicalKeyRule#cascadedStatus} already defines for a technical key
	 * following its entity ({@code ACCEPTED}/{@code APPLIED} both cascade to {@code ACCEPTED}, never
	 * speculatively {@code APPLIED} - that stays {@code StagingGenerator}'s own mark, applied to the mapping
	 * only once the attribute itself is actually marked {@code APPLIED} at generation time; {@code REJECTED}
	 * cascades to {@code REJECTED}; {@code PROPOSED} has nothing to cascade to yet, null) - delegated rather
	 * than re-derived, since the two rules happen to need the identical mapping of statuses.
	 */
	public static String cascadedStatus(final String pAttributeStatus)
	{
		return TechnicalKeyRule.cascadedStatus(pAttributeStatus);
	}

	/**
	 * Whether a derived attribute follows the REJECTION of the field it is derived from: a matching key ({@code
	 * FOLD_KEY}) that nobody decided yet ({@code PROPOSED}). A key is a technical column computed from its source
	 * field, so once the field is refused there is nothing to key - left {@code PROPOSED} it would sit in the
	 * "still to decide" list forever (mapping matrix review queue lot, 2026-09-30). Reject only, and only a key
	 * still waiting: an accepted or rejected key is a decision a person (or a sweep) already made, never touched.
	 * Other derivations (script, valid value) are a person's own choice and do not follow.
	 */
	public static boolean foldKeyFollowsRejection(final String pDerivation, final String pKeyStatus)
	{
		return "FOLD_KEY".equals(pDerivation) && "PROPOSED".equals(pKeyStatus);
	}
}
