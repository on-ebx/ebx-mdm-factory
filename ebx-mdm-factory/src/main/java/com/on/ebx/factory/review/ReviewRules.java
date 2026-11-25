package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.List;

/**
 * Consistency rules of the proposal review, independent of EBX: whether a decision is allowed, and
 * what a decision proposes as a side effect. Pure: no EBX class, unit-tested as is. The EBX-bound
 * writer ({@code ProposalReviews.decide}) re-reads the CURRENT row (and whatever it points to) fresh
 * for every decision and calls these methods again - never trusts what the page last rendered - then
 * only writes when the result says {@link Decision#allowed}.
 * <p>
 * Rules enforced here (PLAN.md, "engine applies" / consistency rules of the review screen):
 * <ul>
 * <li>a line already {@link ReviewLine#APPLIED} cannot be decided again ({@link #canDecide});</li>
 * <li>accepting a {@code StagingAttribute} whose entity is not accepted is refused, naming the entity
 * ({@link #canAcceptStagingAttribute});</li>
 * <li>accepting an {@code AttributeMapping} requires its attribute to be accepted
 * ({@link #canAcceptAttributeMapping});</li>
 * <li>a {@code StagingAttribute} with a {@code referenceEntity} can only be accepted when the
 * referenced entity is accepted, and - when the reference came from an accepted-or-proposed relation,
 * per the rule engine's own "accept the relation first" rationale ({@code StagingRules}) - when that
 * relation is accepted too ({@link #canAcceptStagingAttribute});</li>
 * <li>rejecting an entity never forces rejecting its attributes/mappings: it only proposes the ones
 * still open, for the screen to ask about ({@link #cascadeCandidates}).</li>
 * <li>a {@code StagingAttribute} whose {@code primaryKey} is true (the technical key, {@code
 * sourceSystem}/{@code sourceId}) has no decision of its own: any direct Accept/Reject attempt on it is
 * refused, whatever its current status ({@link #canDecide(String, boolean)}) - it follows its entity's own
 * decision instead, cascaded elsewhere ({@code ProposalReviews.decide}, {@code StagingSuggester}'s
 * backfill for a row already on the instance; see {@code com.on.ebx.factory.staging.TechnicalKeyRule},
 * added after a coordinator review, 2026-09-20, of a live refusal: "nobody thinks of accepting sourceSystem
 * and sourceId, and they should not have to").</li>
 * </ul>
 */
public final class ReviewRules
{
	private ReviewRules()
	{
	}

	/** The result of a consistency check: either the decision may proceed, or {@link #reason} says why not (for the notice shown to the user). */
	public static final class Decision
	{
		public final boolean allowed;
		public final String reason;
		/** Staging last points (2026-10-17): the bundle key and arguments of {@link #reason} when the refusal can be worded in the viewer's language ({@code ReviewMessages}); null otherwise. */
		public final String reasonKey;
		public final Object[] reasonArgs;

		private Decision(final boolean pAllowed, final String pReason)
		{
			this(pAllowed, pReason, null, null);
		}

		private Decision(final boolean pAllowed, final String pReason, final String pReasonKey, final Object[] pReasonArgs)
		{
			this.allowed = pAllowed;
			this.reason = pReason;
			this.reasonKey = pReasonKey;
			this.reasonArgs = pReasonArgs;
		}

		static Decision ok()
		{
			return new Decision(true, null);
		}

		static Decision refuse(final String pReason)
		{
			return new Decision(false, pReason);
		}

		static Decision refuse(final String pReason, final String pReasonKey, final Object... pReasonArgs)
		{
			return new Decision(false, pReason, pReasonKey, pReasonArgs);
		}
	}

	private static boolean isAcceptedLike(final String pStatus)
	{
		return ReviewLine.ACCEPTED.equals(pStatus) || ReviewLine.APPLIED.equals(pStatus);
	}

	/** A line already {@code APPLIED} is done: the engine already used it, so no further decision changes it. Re-deciding ACCEPTED &lt;-&gt; REJECTED is always otherwise allowed. */
	public static Decision canDecide(final String pCurrentStatus)
	{
		if (ReviewLine.APPLIED.equals(pCurrentStatus))
		{
			return Decision.refuse("Already applied: cannot be changed any more.");
		}
		return Decision.ok();
	}

	/**
	 * The {@link ReviewLine#ATTRIBUTE}-specific overload: refuses unconditionally when {@code pPrimaryKey}
	 * is true - the technical key has no decision of its own, whatever its current status (see the class
	 * Javadoc) - before falling back to the ordinary {@link #canDecide(String)} check for every other
	 * attribute. Called instead of the one-argument overload only for {@code ATTRIBUTE} lines: no other
	 * nature's row can ever be {@code primaryKey}.
	 */
	public static Decision canDecide(final String pCurrentStatus, final boolean pPrimaryKey)
	{
		if (pPrimaryKey)
		{
			return Decision.refuse("The technical key follows its entity: accept or reject the entity instead.");
		}
		return ReviewRules.canDecide(pCurrentStatus);
	}

	/**
	 * @param pEntityStatus current status of the attribute's entity.
	 * @param pEntityName name of the attribute's entity, for the refusal message.
	 * @param pReferenceEntityName name of the entity {@code referenceEntity} points to, or null when the attribute is not a foreign key.
	 * @param pReferenceEntityStatus current status of that referenced entity; ignored when {@code pReferenceEntityName} is null.
	 * @param pRelationStatus current status of the {@code LandingRelation} the reference was derived from, or null when the reference did not come
	 *        from a relation (entered by a user or by the model, or {@code pReferenceEntityName} is null). See {@code ProposalReviews} for how this is
	 *        resolved from the rule engine's rationale text - the model has no explicit link from a {@code StagingAttribute} back to a
	 *        {@code LandingRelation}.
	 */
	public static Decision canAcceptStagingAttribute(final String pEntityStatus, final String pEntityName, final String pReferenceEntityName,
		final String pReferenceEntityStatus, final String pRelationStatus)
	{
		if (!ReviewRules.isAcceptedLike(pEntityStatus))
		{
			return Decision.refuse("Entity '" + pEntityName + "' is not accepted yet: accept it first.");
		}
		if (pReferenceEntityName != null)
		{
			if (!ReviewRules.isAcceptedLike(pReferenceEntityStatus))
			{
				return Decision.refuse("Referenced entity '" + pReferenceEntityName + "' is not accepted yet: accept it first.");
			}
			if (pRelationStatus != null && !ReviewRules.isAcceptedLike(pRelationStatus))
			{
				return Decision.refuse("This reference comes from a relation that is not accepted yet: accept the relation first.");
			}
		}
		return Decision.ok();
	}

	/** The most codes a refusal names; the others are counted. */
	private static final int CODES_NAMED = 6;

	/**
	 * Staging last points, item 3 (2026-10-17): a code table ({@code TRANSCODE_REFERENCE} with a table of its own) is accepted only when no observed code with rows lacks a
	 * target: accepted incomplete, it would leave those rows written in the source's own vocabulary beside the common list (live: Mr / Mrs "(to choose)", "140 rows of 140
	 * have no target code yet" - and Accept was available). The refusal says what to fill. Any other step passes. Reject is never asked about: it is not an accept.
	 */
	public static Decision canAcceptMappingStep(final String pKind, final String pArgument)
	{
		if (!com.on.ebx.factory.quality.CleaningSteps.TRANSCODE_REFERENCE.equalsIgnoreCase(pKind == null ? "" : pKind.trim()))
		{
			return Decision.ok();
		}
		final com.on.ebx.factory.quality.CodeMap map = com.on.ebx.factory.quality.CodeMap.parseCached(pArgument);
		if (map == null)
		{
			return Decision.ok();
		}
		final List<String> missing = map.codesWithoutTarget();
		if (missing.isEmpty())
		{
			return Decision.ok();
		}
		return Decision.refuse(ReviewMessages.get(java.util.Locale.ENGLISH, "review.refuse.codeTable", ReviewRules.codesText(missing)), "review.refuse.codeTable",
			ReviewRules.codesText(missing));
	}

	/**
	 * Lot M2 (2026-10-17, docs/MATCHING.md sect;56): the plan of a CHILD entity ({@code pParents} non-empty) may only send pairs to a steward - a plan holding a {@code MATCH} leaf is
	 * not accepted (nor is an automatic merge switched on for it), whatever the translation does with the leaf. Any other plan passes. Reject is never asked about.
	 */
	public static Decision canAcceptMatchPlan(final String pEntity, final List<String> pParents, final String pRequirementsJson)
	{
		final String refusal = com.on.ebx.factory.master.ChildEntityMatching.matchLeafRefusal(pEntity, pParents, pRequirementsJson);
		return refusal == null ? Decision.ok() : Decision.refuse(refusal);
	}

	/** The codes a refusal names: the first few, then how many more. */
	public static String codesText(final List<String> pCodes)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pCodes.size() && i < ReviewRules.CODES_NAMED; i++)
		{
			out.append(i > 0 ? ", " : "").append(pCodes.get(i));
		}
		if (pCodes.size() > ReviewRules.CODES_NAMED)
		{
			out.append(" (+").append(pCodes.size() - ReviewRules.CODES_NAMED).append(')');
		}
		return out.toString();
	}

	public static Decision canAcceptAttributeMapping(final String pAttributeStatus, final String pAttributeName)
	{
		if (!ReviewRules.isAcceptedLike(pAttributeStatus))
		{
			return Decision.refuse("Attribute '" + pAttributeName + "' is not accepted yet: accept it first.");
		}
		return Decision.ok();
	}

	/**
	 * A contradiction the reviewer must see, never something this rule blocks on (coordinator review,
	 * 2026-09-20): an attribute {@code ACCEPTED} or {@code APPLIED} whose every EXISTING mapping is {@code
	 * REJECTED} - or that has no mapping at all - has nothing left to feed it: a real EBX field with an
	 * accepted decision behind it, but no data path into it. Null (no warning) when the attribute is not
	 * accepted-like, when it is the technical key (a {@code primaryKey} attribute never has a mapping of its
	 * own at all - {@code sourceSystem}/{@code sourceId} are filled by the transfer directly, so an empty
	 * mapping list there is normal, not a contradiction), or when at least one mapping is still {@code
	 * PROPOSED}, {@code ACCEPTED} or {@code APPLIED} (still capable of feeding the field, or on its way to).
	 * Delegates to the 4-arg overload with {@code pDerived = false} - kept for the existing tests/callers that
	 * predate {@code StagingAttribute.derivation} (2026-09-22).
	 */
	public static String attributeMappingWarning(final String pAttributeStatus, final boolean pPrimaryKey, final List<String> pMappingStatuses)
	{
		return ReviewRules.attributeMappingWarning(pAttributeStatus, pPrimaryKey, false, pMappingStatuses);
	}

	/**
	 * @param pDerived {@code true} for a {@code FOLD_KEY} (or any future kind of) derived attribute - a
	 * SECOND, structural reason (added 2026-09-22, alongside {@code pPrimaryKey}) an empty mapping list is
	 * normal, not a contradiction: {@code quality.CrossSystemFold}/{@code ebx.QualitySuggester} never write an
	 * {@code AttributeMapping} for a derived attribute at all (it is computed from another attribute's own
	 * staging value, never fed by a landing column) - without this guard, EVERY accepted derived attribute
	 * would show "Accepted, but no mapping feeds it." forever, a false alarm for something working exactly as
	 * designed.
	 */
	public static String attributeMappingWarning(final String pAttributeStatus, final boolean pPrimaryKey, final boolean pDerived,
		final List<String> pMappingStatuses)
	{
		return ReviewRules.attributeMappingWarning(pAttributeStatus, pPrimaryKey, pDerived, false, pMappingStatuses);
	}

	/** The English sentence of the warning, which screens that word it in the person's language recognise (step 7 screen counts, 2026-10-18). */
	public static final String MAPPING_WARNING = "Accepted, but no mapping feeds it.";

	/**
	 * @param pLinkToEntity {@code true} when the attribute is a reference to another business object (a child's link to its parent, {@code StagingAttribute.referenceEntity}):
	 * a link with no mapping of its own at all is filled when the child is loaded through its parent's feed - lots M7 / M15 count a business object as fed by a live mapping on
	 * ANY attribute, "the technical key and the link to the parent never count" - so it is no gap. Measured 2026-10-18 on E2EMASTER20261018181651: {@code Adresse.personne}
	 * (a link to Personne, no mapping row) was an ERROR open point of the summary while the 1068 addresses were loaded. A link whose mappings are ALL rejected is still a gap
	 * (somebody removed what fed it).
	 */
	public static String attributeMappingWarning(final String pAttributeStatus, final boolean pPrimaryKey, final boolean pDerived, final boolean pLinkToEntity,
		final List<String> pMappingStatuses)
	{
		if (pPrimaryKey || pDerived || !ReviewRules.isAcceptedLike(pAttributeStatus))
		{
			return null;
		}
		if (pLinkToEntity && pMappingStatuses.isEmpty())
		{
			return null;
		}
		for (final String status : pMappingStatuses)
		{
			if (!ReviewLine.REJECTED.equals(status))
			{
				return null;
			}
		}
		return ReviewRules.MAPPING_WARNING;
	}

	/**
	 * A row affected by a bulk decision, carrying just enough for the confirmation panel to show it and
	 * for the writer to act on it afterwards: which table ({@link ReviewLine#nature}), its id, a label,
	 * and its current status. Used both for {@link #cascadeCandidates} (attributes/mappings under an
	 * entity being rejected) and for a bulk-accept preview (the EBX-bound writer builds the list either
	 * way; this class carries no EBX dependency).
	 */
	public static final class AffectedRow
	{
		public final String nature;
		public final String rowId;
		public final String label;
		public final String status;

		public AffectedRow(final String pNature, final String pRowId, final String pLabel, final String pStatus)
		{
			this.nature = pNature;
			this.rowId = pRowId;
			this.label = pLabel;
			this.status = pStatus;
		}
	}

	/**
	 * Rejecting an entity never forces anything on its attributes and mappings (PLAN.md: "the model
	 * proposes, a person accepts"): this only tells the caller which of them are still open to a decision
	 * - not {@code REJECTED} already and not {@code APPLIED} (an applied line is done; cascading onto it
	 * would be silently ignored by {@link #canDecide} anyway, so it is left out here instead of shown as
	 * an option that cannot work).
	 */
	public static List<AffectedRow> cascadeCandidates(final List<AffectedRow> pAttributesAndMappings)
	{
		final List<AffectedRow> out = new ArrayList<AffectedRow>();
		for (final AffectedRow row : pAttributesAndMappings)
		{
			if (!ReviewLine.REJECTED.equals(row.status) && !ReviewLine.APPLIED.equals(row.status))
			{
				out.add(row);
			}
		}
		return out;
	}

	/**
	 * fix-bulk-accept-applies (2026-09-21): whether a row a bulk/selected decision just moved to
	 * {@code pTargetStatus} should be queued to apply afterward - true only for a {@code SCRIPT_CONSTRAINT}
	 * (or, lot M5, {@code SCRIPT_DERIVATION}) row moved to {@link ReviewLine#ACCEPTED} (never {@code REJECTED}, and never a row this decision did
	 * NOT itself move - the EBX-bound {@code ProposalReviewUserService.confirmBulk} only ever calls this
	 * for a row whose {@code ProposalReviews.decideRef} just succeeded, this round, never for one accepted
	 * earlier). Product owner's own decision: acceptance applies directly, the same rule a single-row
	 * accept click already follows ({@code ProposalReviewUserService.decideOne}/{@code applyOne}) - kept
	 * here, pure, so the collection rule itself is unit-tested without EBX.
	 */
	public static boolean shouldApplyAfterAccept(final String pTargetStatus, final String pNature)
	{
		// lot M5 (2026-10-04): a SCRIPT_DERIVATION follows the same rule (a single accept click already applied it; a bulk accept left it
		// ACCEPTED and unapplied, its derived attribute then silently empty or, before this lot, a copy of its source)
		return ReviewLine.ACCEPTED.equals(pTargetStatus) && (ReviewLine.SCRIPT_CONSTRAINT.equals(pNature) || ReviewLine.SCRIPT_DERIVATION.equals(pNature));
	}

	/**
	 * {@link #shouldApplyAfterAccept}, applied to a whole batch: the {@code rowId}s of every
	 * {@code SUCCEEDED} row (per {@code pSucceededIds}, an id the caller already knows was actually
	 * decided, never inferred here) that should now be applied - the exact list
	 * {@code ProposalReviewUserService.confirmBulk} passes to {@code ebx.CapabilityApplier.applyAll}.
	 * {@code pSucceededIds} is a {@code Set} deliberately (not "the first N rows" or similar): a caller
	 * iterating {@code AffectedRow}s alongside a per-row success flag can build it with an ordinary loop,
	 * and this method stays a pure function of "which ids succeeded" plus "which rows are eligible",
	 * never touching iteration order or any EBX-bound success-reporting shape itself.
	 */
	public static List<String> idsToApplyAfterAccept(final List<AffectedRow> pRows, final java.util.Set<String> pSucceededIds, final String pTargetStatus)
	{
		final List<String> out = new ArrayList<String>();
		for (final AffectedRow row : pRows)
		{
			if (pSucceededIds.contains(row.rowId) && ReviewRules.shouldApplyAfterAccept(pTargetStatus, row.nature))
			{
				out.add(row.rowId);
			}
		}
		return out;
	}
}
