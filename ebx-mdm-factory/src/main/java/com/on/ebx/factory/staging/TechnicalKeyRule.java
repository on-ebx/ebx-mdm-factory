package com.on.ebx.factory.staging;

/**
 * The technical key of a staging entity ({@code StagingAttribute.primaryKey = true}, i.e. {@code
 * sourceSystem}/{@code sourceId}) has NO decision of its own: it follows its entity's own decision,
 * unconditionally. Coordinator review, 2026-09-20, after the live refusal on project {@code Companies}
 * (entities {@code Account}/{@code Contact} accepted, one attribute of {@code Contact} accepted,
 * {@code staging/generate} still refused "Account: no accepted primary key attribute"): <i>"nobody thinks
 * of accepting sourceSystem and sourceId, and they should not have to: the technical key is not a proposal
 * about the business, it is what the engine needs."</i>
 * <p>
 * Pure, shared by every layer that needs this rule instead of each re-deriving it: {@link StagingModel}
 * (a primary-key attribute counts as included for generation purely from its entity's status - its own
 * stored status is never consulted), {@code ReviewRules} (refuses a direct Accept/Reject attempt on such a
 * line - the entity is the only thing a person decides), and the two EBX-bound writers that keep a
 * primary-key attribute's stored status coherent with its entity so the review screen never shows a stale
 * one: {@code ProposalReviews.decide} (cascades a fresh entity decision onto its primary-key attributes in
 * the same procedure) and {@code StagingSuggester} (a one-time backfill, via {@link #cascadedStatus}, for a
 * row already on the instance whose entity was decided before this rule existed - exactly the {@code
 * Account}/{@code Contact} case that triggered this).
 */
public final class TechnicalKeyRule
{
	private TechnicalKeyRule()
	{
	}

	/** {@code ACCEPTED} or {@code APPLIED}: the ordinary "is this proposal accepted" test, shared so every caller reads a status the same way. */
	public static boolean isAcceptedLike(final String pStatus)
	{
		return "ACCEPTED".equals(pStatus) || "APPLIED".equals(pStatus);
	}

	/**
	 * The status a primary-key attribute should carry once its entity's own status is {@code
	 * pEntityStatus}: {@code ACCEPTED} or {@code APPLIED} both cascade to {@code ACCEPTED} - not {@code
	 * APPLIED}, which stays exclusively {@code StagingGenerator}'s own mark at generation time, never
	 * backfilled speculatively onto a row that may not have been generated at all; {@code REJECTED}
	 * cascades to {@code REJECTED}; anything else (in particular {@code PROPOSED}, an entity not yet
	 * decided) has nothing to cascade to yet and this returns null.
	 */
	public static String cascadedStatus(final String pEntityStatus)
	{
		if ("ACCEPTED".equals(pEntityStatus) || "APPLIED".equals(pEntityStatus))
		{
			return "ACCEPTED";
		}
		if ("REJECTED".equals(pEntityStatus))
		{
			return "REJECTED";
		}
		return null;
	}
}
