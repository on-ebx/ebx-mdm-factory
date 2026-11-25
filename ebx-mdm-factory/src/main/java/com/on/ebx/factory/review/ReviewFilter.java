package com.on.ebx.factory.review;

/**
 * The four filters the review screen offers (PLAN.md screen 3 / mockup screen 4): nature, origin,
 * status - default PROPOSED only, per the "nothing is generated from a line that is not accepted"
 * principle, the review screen's job is to work the queue of what is still pending - and a minimum
 * confidence. {@code null} or {@code "ALL"} on {@link #nature}/{@link #origin}/{@link #status} means
 * no filtering on that dimension.
 */
public final class ReviewFilter
{
	public static final String ALL = "ALL";

	public final String nature;
	public final String origin;
	public final String status;
	public final int minConfidence;
	/**
	 * The Reference search box's own posted value (coordinator report, 2026-09-21: "Je ne vois pas
	 * les Ids des choses a revoir dans l'ecran") - null/blank means no reference lookup is active,
	 * and every other field behaves exactly as before this was added. Non-blank, it names ONE line
	 * ({@link ReviewReference#format}) and {@link #matches} shows it whatever nature/origin/status/
	 * minConfidence say - a reference lookup must not be hidden by {@code Status: Pending}.
	 */
	public final String reference;

	public ReviewFilter(final String pNature, final String pOrigin, final String pStatus, final int pMinConfidence)
	{
		this(pNature, pOrigin, pStatus, pMinConfidence, null);
	}

	public ReviewFilter(final String pNature, final String pOrigin, final String pStatus, final int pMinConfidence, final String pReference)
	{
		this.nature = pNature;
		this.origin = pOrigin;
		this.status = pStatus;
		this.minConfidence = pMinConfidence;
		this.reference = pReference;
	}

	/** The screen's own default: every nature and origin, status PROPOSED only, no confidence floor, no reference lookup. */
	public static ReviewFilter defaultFilter()
	{
		return new ReviewFilter(ReviewFilter.ALL, ReviewFilter.ALL, ReviewLine.PROPOSED, 0);
	}

	boolean matches(final ReviewLine pLine)
	{
		if (!ReviewFilter.isBlank(this.reference))
		{
			return ReviewFilter.matchesReference(pLine, this.reference, this.nature);
		}
		if (!ReviewFilter.isAll(this.nature) && !this.nature.equals(pLine.nature))
		{
			return false;
		}
		if (!ReviewFilter.isAll(this.origin) && !this.origin.equals(pLine.origin))
		{
			return false;
		}
		if (!ReviewFilter.isAll(this.status) && !this.status.equals(pLine.status) && !ReviewFilter.isUnappliedConstraintUnderPending(this.status, pLine))
		{
			return false;
		}
		final int confidence = pLine.confidence == null ? 0 : pLine.confidence.intValue();
		return confidence >= this.minConfidence;
	}

	/**
	 * A fully-qualified reference ({@code STEP-8}, tolerant of case/spacing - {@link
	 * ReviewReference#normalize}) names exactly one line and shows it whatever nature/origin/status/
	 * minConfidence say. A BARE number ({@code 8}) has no prefix of its own to resolve a nature from -
	 * it is only meaningful once the Nature filter narrows to one (task brief: "a bare 8 combined
	 * with the Nature filter"); with Nature=ALL, a bare number matches nothing (ambiguous across
	 * every table's own auto-increment keys).
	 */
	private static boolean matchesReference(final ReviewLine pLine, final String pReference, final String pFilterNature)
	{
		final String query = ReviewReference.normalize(pReference);
		if (query.isEmpty())
		{
			return false;
		}
		final String lineReference = ReviewReference.normalize(ReviewReference.format(pLine.nature, pLine.rowId));
		if (!lineReference.isEmpty() && query.equals(lineReference))
		{
			return true;
		}
		if (ReviewReference.isNumeric(query) && !ReviewFilter.isAll(pFilterNature) && pFilterNature.equals(pLine.nature))
		{
			return query.equals(ReviewReference.normalize(pLine.rowId));
		}
		return false;
	}

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 6: an ACCEPTED {@link ReviewLine#SCRIPT_CONSTRAINT}
	 * line is not yet on the model - its apply either failed (leaving an {@code applyMessage}, surfaced in
	 * {@link ReviewLine#rationale} by {@code ProposalReviews}) or was simply never run - so it reads as
	 * still-pending work for the reviewer, exactly the queue the default/Pending filter exists to show. Real
	 * captured miss: a product owner could not find such a line because the screen opens on Status = Pending
	 * ({@link ReviewLine#PROPOSED} only) and an ACCEPTED row never matched that filter under the ordinary
	 * rule. Deliberately narrow to the {@link ReviewLine#PROPOSED} filter only - filtering explicitly on
	 * {@link ReviewLine#ACCEPTED} still shows exactly the true ACCEPTED lines, unclouded by this rule.
	 */
	private static boolean isUnappliedConstraintUnderPending(final String pFilterStatus, final ReviewLine pLine)
	{
		return ReviewLine.PROPOSED.equals(pFilterStatus) && ReviewLine.SCRIPT_CONSTRAINT.equals(pLine.nature) && ReviewLine.ACCEPTED.equals(pLine.status);
	}

	/** Null, empty (an unset posted parameter - see {@code ProposalReviewUserService.param}) or the literal {@link #ALL} all mean "no filter on this dimension". */
	private static boolean isAll(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() || ReviewFilter.ALL.equals(pValue);
	}

	/** Null or empty (an unset posted parameter, or nothing typed in the Reference box) - {@link #reference} has no {@code ALL} pseudo-value of its own, unlike nature/origin/status. */
	private static boolean isBlank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
