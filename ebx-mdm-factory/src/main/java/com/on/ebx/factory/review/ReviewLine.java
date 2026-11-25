package com.on.ebx.factory.review;

/**
 * One proposal of the project, normalised to the shape the review screen shows regardless of which
 * of the five source tables it came from (see {@code ProposalReviews} for the EBX-bound reader that
 * builds these). Every field the thin reader can resolve (foreign-key labels included) is already
 * plain text here: this class and everything that consumes it ({@link ReviewData}, {@link ReviewPage})
 * never touch EBX, so they are unit-tested without it.
 */
public final class ReviewLine
{
	/** {@code LandingColumn.sourceKey} is a boolean, not a proposal row: it is deliberately not one of these natures (see PLAN.md / MdmFactory.xsd). */
	public static final String RELATION = "RELATION";
	public static final String ENTITY = "ENTITY";
	public static final String ATTRIBUTE = "ATTRIBUTE";
	public static final String MAPPING = "MAPPING";
	public static final String MATCH = "MATCH";
	/** A {@code MappingStep} - one pre-parse cleanup step of a mapping's pipeline (review-quality lot, task B). */
	public static final String MAPPING_STEP = "MAPPING_STEP";
	/** A {@code QualityRule} - a validation rule for the staging model (review-quality lot, task B). */
	public static final String QUALITY = "QUALITY";
	/** A {@code CapabilitySuggestion} of nature {@code SCRIPT_CONSTRAINT} - an existing published library function the language model suggests positioning on the model (capability-suggestion lot). */
	public static final String SCRIPT_CONSTRAINT = "SCRIPT_CONSTRAINT";
	/** A {@code CapabilitySuggestion} of nature {@code EXTERNAL_SERVICE} - an existing connector operation the language model suggests a person look into (capability-suggestion lot). */
	public static final String EXTERNAL_SERVICE = "EXTERNAL_SERVICE";
	/** A {@code CapabilitySuggestion} of nature {@code SCRIPT_DERIVATION} - an existing published library function whose return value becomes a NEW computed attribute, stored and kept up to date by two table triggers (script-backed-derivation lot, 2026-09-23, reworked by lot T1, 2026-10-04 - {@code quality.ScriptDerivation}). Accepting one applies it directly, the same "acceptance IS the trigger" rule {@link #SCRIPT_CONSTRAINT} already follows - see {@code ebx.DerivationApplier}. */
	public static final String SCRIPT_DERIVATION = "SCRIPT_DERIVATION";
	/** A {@code TransferFeed} - one node of the landing-to-staging mapping tree (target-mapping lot 3, docs/TARGET-MAPPING.md sec 4.3): reviewed like an attribute or a mapping, since it is what actually loads the entity's rows. Accept/reject cascades to its {@code PROPOSED} mappings, the same way an entity's own decision cascades onto its feeds (lot 1, {@code ebx.ProposalReviews#cascadeFeeds}). */
	public static final String FEED = "FEED";
	/** A {@code Difference} - one difference between a chosen accelerator pack and the project's need (lot C1-B, docs/LOT-C1.md sec 5): decided in the CENTRALISATION wizard's Proposal step through the same decide path as every other nature. */
	public static final String DIFFERENCE = "DIFFERENCE";

	public static final String PROPOSED = "PROPOSED";
	public static final String ACCEPTED = "ACCEPTED";
	public static final String REJECTED = "REJECTED";
	public static final String APPLIED = "APPLIED";
	/** Reject/repropose lot (2026-09-22), {@link #MATCH} only: the current plan was set aside while a new one is requested from the language model - still shown, still acceptable, never a dead end. See {@code MATCH_PLAN_STATUS} in {@code tools/build_xsd.py}. */
	public static final String SUPERSEDED = "SUPERSEDED";
	/**
	 * Staging-still-to-decide lot (2026-09-27, project ret), {@code CapabilitySuggestion} only: "not refused, not
	 * applied, kept for later" - what a person chooses for an {@link #EXTERNAL_SERVICE} suggestion that cannot run
	 * yet (connector secret missing, query still to write). Never pending (it no longer counts as "still to
	 * decide"), never applied; Accept/Reject stay possible later ({@code DecisionActions} offers both for any
	 * status it does not know specifically). See {@code CAPABILITY_STATUS} in {@code tools/build_xsd.py}.
	 */
	public static final String SET_ASIDE = "SET_ASIDE";

	/** {@link #RELATION}/{@link #ENTITY}/{@link #ATTRIBUTE}/{@link #MAPPING}/{@link #MATCH}. */
	public final String nature;
	/** Primary key of the underlying row (LandingRelation/StagingEntity/StagingAttribute/AttributeMapping/MatchPlan), for a decision to target. */
	public final String rowId;
	/** Human-readable element, e.g. {@code CONTACT.ACCOUNT_ID}, {@code Contact.accountId}, {@code Supplier}. */
	public final String element;
	/** What is proposed, e.g. "Foreign key to ACCOUNT", "New entity", "TRIM". */
	public final String proposition;
	/** {@code RULE}/{@code LLM}/{@code USER}, plus {@code DECLARED} for a relation known at the source. */
	public final String origin;
	/** 0-100, or null when the line carries none (a line entered directly by a user). */
	public final Integer confidence;
	public final String rationale;
	/** {@link #PROPOSED}/{@link #ACCEPTED}/{@link #REJECTED}/{@link #APPLIED}. */
	public final String status;
	/**
	 * {@code StagingAttribute.businessKey}: the SOURCE's own key ({@code contactId}...). Only meaningful for {@link #ATTRIBUTE} - false
	 * for every other nature. Distinct from {@link #primaryKey} (the entity's technical key) since the two notions were split apart
	 * (PLAN.md, 2026-09-20 staging pass, "deux notions distinctes") - before that, this single flag was true on both and the badge had to
	 * guess which one from {@link #element}'s attribute name; it now reads the real field instead.
	 */
	public final boolean businessKey;
	/** {@code StagingAttribute.primaryKey}: the entity's own technical identity ({@code sourceSystem}/{@code sourceId}). Only meaningful for {@link #ATTRIBUTE}. */
	public final boolean primaryKey;
	/**
	 * {@code CapabilitySuggestion.explanation} (apply lot, 2026-09-21) - null except for an {@link #APPLIED}
	 * {@link #SCRIPT_CONSTRAINT} line, whose reviewer will never read the script source (product owner's own
	 * decision): the plain-language description of what the applied constraint does.
	 */
	public final String explanation;
	/**
	 * The measured effect of an APPLIED {@link #SCRIPT_CONSTRAINT} line, already rendered to plain text (e.g.
	 * {@code "303 record(s) checked, 4 reported"}) by {@code review.ProposalWording} - null otherwise. Pure,
	 * pre-formatted text, the same discipline every other proposition/rationale field in this class follows.
	 */
	public final String measuredEffect;
	/**
	 * Re-apply lot (2026-09-21): true only for an {@link #APPLIED} {@link #SCRIPT_CONSTRAINT} line whose live
	 * form no longer matches what applying the row again would generate ({@code capability.ConstraintStaleness}
	 * - the pure decision), false for every other line (including a {@code SCRIPT_CONSTRAINT} line of any
	 * other status, and every other nature). {@link #staleReason} is null exactly when this is false.
	 */
	public final boolean stale;
	/** {@code capability.ConstraintStaleness.Result#describe()} - plain words, for the "Out of date" marker's tooltip; null when {@link #stale} is false. */
	public final String staleReason;
	/**
	 * review-match-plan lot (2026-09-22): {@code MatchPlan.requirements} - only ever non-null for a {@link #MATCH}
	 * line, read by {@code review.ReviewPage} to build the expanded body via {@code review.MatchPlanView}. Null
	 * for every other nature.
	 */
	public final String matchRequirementsJson;
	/** {@code MatchPlan.evidence} - {@code master.MatchPlanEvidence}'s own shape; null for every other nature, and for a {@link #MATCH} plan written before this field existed. */
	public final String matchEvidenceJson;
	/** {@code MatchPlan.autoMerge} - meaningless (false) for every other nature. */
	public final boolean matchAutoMerge;
	/**
	 * Reject/repropose lot (2026-09-22), {@link #MATCH} only: {@code MatchPlan.reviewComment} on a {@link
	 * #REJECTED} or {@link #SUPERSEDED} line - null/empty for every other line, and for a MATCH line that
	 * carries no comment. Deliberately NOT read by {@code ProposalReviews.readMatchPlans} itself (that method
	 * is being extended by another lot at the same time, for a different field - {@code evidence} - see
	 * PLAN.md 2026-09-22 13:00): {@link #withMatchExtras} lets a caller attach it afterwards from its own,
	 * separate, single-row lookup instead, so the two lots never touch the same constructor call.
	 */
	public final String reviewComment;
	/**
	 * Reject/repropose lot (2026-09-22), {@link #MATCH} only: {@code MatchPlan.message} - the SYSTEM's own
	 * note on the last "Propose another" attempt ("the model proposed the same plan again", or the reason a
	 * pass failed/returned nothing), distinct from {@link #reviewComment} (the REVIEWER's own words, kept as
	 * history) and from {@link #rationale} (the model's explanation for the plan currently on the row). Null/
	 * empty otherwise. Attached the same way as {@link #reviewComment} - see {@link #withMatchExtras}.
	 */
	public final String matchNote;
	/**
	 * Survivorship-review lot (2026-09-22), {@link #MATCH} only: everything section 4 "Survivance" needs beyond
	 * {@link #matchRequirementsJson}/{@link #matchEvidenceJson} - source cards (wizard trust rank/description/
	 * record count), the entity's source-key field name(s), and its derived matching-key fields. Null for every
	 * other nature, and for a MATCH line this attach-after-the-fact step has not reached yet - {@code
	 * review.ReviewPage} then passes {@code MatchSurvivorshipBlock.emptyInput()} down, same degrade-gracefully
	 * rule {@link MatchPlanView.Input}'s own constructor already applies. Attached the same way as {@link
	 * #reviewComment}/{@link #matchNote} - see {@link #withSurvivorshipInput} - by {@code
	 * ebx.ProposalReviews#attachSurvivorshipInputs}, deliberately not folded into {@code ProposalReviews.lines}
	 * itself (same "a concurrent/later lot attaches its own field separately" convention {@link
	 * #withMatchExtras}'s own Javadoc already established).
	 */
	public final MatchSurvivorshipBlock.Input survivorshipInput;

	public ReviewLine(final String pNature, final String pRowId, final String pElement, final String pProposition, final String pOrigin,
		final Integer pConfidence, final String pRationale, final String pStatus, final boolean pBusinessKey, final boolean pPrimaryKey)
	{
		this(pNature, pRowId, pElement, pProposition, pOrigin, pConfidence, pRationale, pStatus, pBusinessKey, pPrimaryKey, null, null);
	}

	/** See {@link #explanation}/{@link #measuredEffect}; every other caller keeps using the 10-argument constructor above, which passes null for both; {@link #stale}/{@link #staleReason} default to false/null (see the 14-argument constructor below, Re-apply lot). */
	public ReviewLine(final String pNature, final String pRowId, final String pElement, final String pProposition, final String pOrigin,
		final Integer pConfidence, final String pRationale, final String pStatus, final boolean pBusinessKey, final boolean pPrimaryKey, final String pExplanation,
		final String pMeasuredEffect)
	{
		this(pNature, pRowId, pElement, pProposition, pOrigin, pConfidence, pRationale, pStatus, pBusinessKey, pPrimaryKey, pExplanation, pMeasuredEffect, false,
			null);
	}

	/** See {@link #stale}/{@link #staleReason} (Re-apply lot, 2026-09-21); every other caller keeps using this constructor, which passes null/null/false/null/null for every MATCH-specific field below (see the full-form constructor, review-match-plan + reject/repropose lots). */
	public ReviewLine(final String pNature, final String pRowId, final String pElement, final String pProposition, final String pOrigin,
		final Integer pConfidence, final String pRationale, final String pStatus, final boolean pBusinessKey, final boolean pPrimaryKey, final String pExplanation,
		final String pMeasuredEffect, final boolean pStale, final String pStaleReason)
	{
		this(pNature, pRowId, pElement, pProposition, pOrigin, pConfidence, pRationale, pStatus, pBusinessKey, pPrimaryKey, pExplanation, pMeasuredEffect, pStale,
			pStaleReason, null, null, false, null, null);
	}

	/** Convenience for every caller built before the survivorship-review lot (2026-09-22, no survivorship input to give) - equivalent to passing null. See {@link #matchRequirementsJson}/{@link #matchEvidenceJson}/{@link #matchAutoMerge} (review-match-plan lot, 2026-09-22) and {@link #reviewComment}/{@link #matchNote} (reject/repropose lot, 2026-09-22). */
	public ReviewLine(final String pNature, final String pRowId, final String pElement, final String pProposition, final String pOrigin,
		final Integer pConfidence, final String pRationale, final String pStatus, final boolean pBusinessKey, final boolean pPrimaryKey, final String pExplanation,
		final String pMeasuredEffect, final boolean pStale, final String pStaleReason, final String pMatchRequirementsJson, final String pMatchEvidenceJson,
		final boolean pMatchAutoMerge, final String pReviewComment, final String pMatchNote)
	{
		this(pNature, pRowId, pElement, pProposition, pOrigin, pConfidence, pRationale, pStatus, pBusinessKey, pPrimaryKey, pExplanation, pMeasuredEffect, pStale,
			pStaleReason, pMatchRequirementsJson, pMatchEvidenceJson, pMatchAutoMerge, pReviewComment, pMatchNote, null);
	}

	/** Full form - see {@link #survivorshipInput} (survivorship-review lot, 2026-09-22). */
	public ReviewLine(final String pNature, final String pRowId, final String pElement, final String pProposition, final String pOrigin,
		final Integer pConfidence, final String pRationale, final String pStatus, final boolean pBusinessKey, final boolean pPrimaryKey, final String pExplanation,
		final String pMeasuredEffect, final boolean pStale, final String pStaleReason, final String pMatchRequirementsJson, final String pMatchEvidenceJson,
		final boolean pMatchAutoMerge, final String pReviewComment, final String pMatchNote, final MatchSurvivorshipBlock.Input pSurvivorshipInput)
	{
		this.nature = pNature;
		this.rowId = pRowId;
		this.element = pElement;
		this.proposition = pProposition;
		this.origin = pOrigin;
		this.confidence = pConfidence;
		this.rationale = pRationale;
		this.status = pStatus;
		this.businessKey = pBusinessKey;
		this.primaryKey = pPrimaryKey;
		this.explanation = pExplanation;
		this.measuredEffect = pMeasuredEffect;
		this.stale = pStale;
		this.staleReason = pStaleReason;
		this.matchRequirementsJson = pMatchRequirementsJson;
		this.matchEvidenceJson = pMatchEvidenceJson;
		this.matchAutoMerge = pMatchAutoMerge;
		this.reviewComment = pReviewComment;
		this.matchNote = pMatchNote;
		this.survivorshipInput = pSurvivorshipInput;
	}

	/**
	 * A copy of {@code pLine} with {@link #reviewComment}/{@link #matchNote} set - the attach-after-the-fact
	 * hook this class' own Javadoc describes, used by {@code ebx.ui.ProposalReviewUserService} right after
	 * {@code ProposalReviews.lines} returns, for {@link #MATCH} lines only. Every other field, {@link
	 * #matchRequirementsJson}/{@link #matchEvidenceJson}/{@link #matchAutoMerge} included, is carried over
	 * unchanged from {@code pLine} - this only ever adds the two reject/repropose fields on top of whatever
	 * {@code ProposalReviews.readMatchPlans} already resolved. {@link #survivorshipInput} is carried over too
	 * (null unless {@link #withSurvivorshipInput} already ran) - the two attach steps are independent and may
	 * run in either order.
	 */
	public static ReviewLine withMatchExtras(final ReviewLine pLine, final String pReviewComment, final String pMatchNote)
	{
		return new ReviewLine(pLine.nature, pLine.rowId, pLine.element, pLine.proposition, pLine.origin, pLine.confidence, pLine.rationale, pLine.status,
			pLine.businessKey, pLine.primaryKey, pLine.explanation, pLine.measuredEffect, pLine.stale, pLine.staleReason, pLine.matchRequirementsJson,
			pLine.matchEvidenceJson, pLine.matchAutoMerge, pReviewComment, pMatchNote, pLine.survivorshipInput);
	}

	/**
	 * Survivorship-review lot (2026-09-22): a copy of {@code pLine} with {@link #survivorshipInput} set - the
	 * attach-after-the-fact hook {@code ebx.ProposalReviews#attachSurvivorshipInputs} uses, for {@link #MATCH}
	 * lines only, right after {@code ProposalReviews.lines} returns (same convention as {@link
	 * #withMatchExtras}, deliberately a separate step: building this input needs a live {@code Session} and,
	 * for the source cards' own record counts, the project's master dataset - neither is available inside
	 * {@code ProposalReviews.lines} itself). Every other field is carried over unchanged, {@link
	 * #reviewComment}/{@link #matchNote} included.
	 */
	public static ReviewLine withSurvivorshipInput(final ReviewLine pLine, final MatchSurvivorshipBlock.Input pSurvivorshipInput)
	{
		return new ReviewLine(pLine.nature, pLine.rowId, pLine.element, pLine.proposition, pLine.origin, pLine.confidence, pLine.rationale, pLine.status,
			pLine.businessKey, pLine.primaryKey, pLine.explanation, pLine.measuredEffect, pLine.stale, pLine.staleReason, pLine.matchRequirementsJson,
			pLine.matchEvidenceJson, pLine.matchAutoMerge, pLine.reviewComment, pLine.matchNote, pSurvivorshipInput);
	}
}
