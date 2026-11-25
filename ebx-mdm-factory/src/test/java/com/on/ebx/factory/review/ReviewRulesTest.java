package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ReviewRulesTest
{
	public static void main(final String[] pArgs)
	{
		ReviewRulesTest.appliedCannotBeDecidedAgain();
		ReviewRulesTest.reDecidingAcceptedRejectedIsAllowed();
		ReviewRulesTest.attributeRefusedWhenEntityNotAccepted();
		ReviewRulesTest.attributeAllowedWhenEntityAccepted();
		ReviewRulesTest.attributeRefusedWhenReferenceEntityNotAccepted();
		ReviewRulesTest.attributeRefusedWhenRelationNotAccepted();
		ReviewRulesTest.attributeAllowedWhenRelationAccepted();
		ReviewRulesTest.attributeIgnoresRelationWhenReferenceNotFromOne();
		ReviewRulesTest.mappingRefusedWhenAttributeNotAccepted();
		ReviewRulesTest.mappingAllowedWhenAttributeAccepted();
		ReviewRulesTest.mappingAllowedWhenAttributeApplied();
		ReviewRulesTest.cascadeExcludesAppliedAndAlreadyRejected();
		ReviewRulesTest.cascadeEmptyWhenNothingOpen();
		ReviewRulesTest.llmCanonicalAttributeReferenceNeedsOnlyItsEntityAccepted();
		ReviewRulesTest.acceptingAnAttributeNeverLooksAtItsMappings();
		ReviewRulesTest.technicalKeyCannotBeDecidedDirectlyWhateverItsStatus();
		ReviewRulesTest.ordinaryAttributeStillGovernedByTheAppliedRule();
		ReviewRulesTest.attributeMappingWarningOnlyWhenAcceptedAndEveryMappingRejected();
		ReviewRulesTest.attributeMappingWarningNeverForADerivedAttribute();
		ReviewRulesTest.shouldApplyAfterAcceptOnlyForScriptConstraintMovedToAccepted();
		ReviewRulesTest.idsToApplyAfterAcceptOnlyCollectsThisRoundsSuccessfulScriptConstraintAccepts();
		System.out.println("ReviewRulesTest OK");
	}

	private static void appliedCannotBeDecidedAgain()
	{
		final ReviewRules.Decision decision = ReviewRules.canDecide(ReviewLine.APPLIED);
		ReviewRulesTest.check("APPLIED cannot be decided again", !decision.allowed);
		ReviewRulesTest.check("refusal names the reason", decision.reason != null && decision.reason.toLowerCase().contains("applied"));
	}

	private static void reDecidingAcceptedRejectedIsAllowed()
	{
		ReviewRulesTest.check("re-deciding an ACCEPTED line is allowed", ReviewRules.canDecide(ReviewLine.ACCEPTED).allowed);
		ReviewRulesTest.check("re-deciding a REJECTED line is allowed", ReviewRules.canDecide(ReviewLine.REJECTED).allowed);
		ReviewRulesTest.check("deciding a PROPOSED line is allowed", ReviewRules.canDecide(ReviewLine.PROPOSED).allowed);
	}

	private static void attributeRefusedWhenEntityNotAccepted()
	{
		final ReviewRules.Decision decision = ReviewRules.canAcceptStagingAttribute(ReviewLine.PROPOSED, "Supplier", null, null, null);
		ReviewRulesTest.check("refused: entity not accepted", !decision.allowed);
		ReviewRulesTest.check("names the entity", decision.reason.contains("Supplier"));
	}

	private static void attributeAllowedWhenEntityAccepted()
	{
		ReviewRulesTest.check("allowed: entity accepted, no reference", ReviewRules.canAcceptStagingAttribute(ReviewLine.ACCEPTED, "Supplier", null, null, null).allowed);
		ReviewRulesTest.check("allowed: entity already applied (was accepted before)",
			ReviewRules.canAcceptStagingAttribute(ReviewLine.APPLIED, "Supplier", null, null, null).allowed);
	}

	private static void attributeRefusedWhenReferenceEntityNotAccepted()
	{
		final ReviewRules.Decision decision = ReviewRules.canAcceptStagingAttribute(ReviewLine.ACCEPTED, "Contact", "Account", ReviewLine.PROPOSED, null);
		ReviewRulesTest.check("refused: referenced entity not accepted", !decision.allowed);
		ReviewRulesTest.check("names the referenced entity", decision.reason.contains("Account"));
	}

	private static void attributeRefusedWhenRelationNotAccepted()
	{
		// exactly the rule engine's own case: StagingRules.applyRelation's rationale says "accept the relation first" when the relation is only PROPOSED
		final ReviewRules.Decision decision = ReviewRules.canAcceptStagingAttribute(ReviewLine.ACCEPTED, "Contact", "Account", ReviewLine.ACCEPTED, ReviewLine.PROPOSED);
		ReviewRulesTest.check("refused: the relation behind the reference is still only proposed", !decision.allowed);
		ReviewRulesTest.check("says to accept the relation first", decision.reason.toLowerCase().contains("relation"));
	}

	private static void attributeAllowedWhenRelationAccepted()
	{
		final ReviewRules.Decision decision = ReviewRules.canAcceptStagingAttribute(ReviewLine.ACCEPTED, "Contact", "Account", ReviewLine.ACCEPTED, ReviewLine.ACCEPTED);
		ReviewRulesTest.check("allowed: entity, referenced entity and relation all accepted", decision.allowed);
	}

	private static void attributeIgnoresRelationWhenReferenceNotFromOne()
	{
		// null relation status: a reference entered by a user or proposed by the model directly, not carried over from a LandingRelation
		final ReviewRules.Decision decision = ReviewRules.canAcceptStagingAttribute(ReviewLine.ACCEPTED, "Contact", "Account", ReviewLine.ACCEPTED, null);
		ReviewRulesTest.check("allowed: no relation involved, only the two entity statuses matter", decision.allowed);
	}

	private static void mappingRefusedWhenAttributeNotAccepted()
	{
		final ReviewRules.Decision decision = ReviewRules.canAcceptAttributeMapping(ReviewLine.PROPOSED, "legalName");
		ReviewRulesTest.check("refused: attribute not accepted", !decision.allowed);
		ReviewRulesTest.check("names the attribute", decision.reason.contains("legalName"));
	}

	private static void mappingAllowedWhenAttributeAccepted()
	{
		ReviewRulesTest.check("allowed: attribute accepted", ReviewRules.canAcceptAttributeMapping(ReviewLine.ACCEPTED, "legalName").allowed);
	}

	private static void mappingAllowedWhenAttributeApplied()
	{
		ReviewRulesTest.check("allowed: attribute already applied", ReviewRules.canAcceptAttributeMapping(ReviewLine.APPLIED, "legalName").allowed);
	}

	private static void cascadeExcludesAppliedAndAlreadyRejected()
	{
		final List<ReviewRules.AffectedRow> rows = Arrays.asList(new ReviewRules.AffectedRow(ReviewLine.ATTRIBUTE, "1", "supplierId", ReviewLine.PROPOSED),
			new ReviewRules.AffectedRow(ReviewLine.ATTRIBUTE, "2", "legalName", ReviewLine.ACCEPTED),
			new ReviewRules.AffectedRow(ReviewLine.ATTRIBUTE, "3", "vatNumber", ReviewLine.APPLIED),
			new ReviewRules.AffectedRow(ReviewLine.MAPPING, "4", "legalName mapping", ReviewLine.REJECTED));
		final List<ReviewRules.AffectedRow> candidates = ReviewRules.cascadeCandidates(rows);
		ReviewRulesTest.check("PROPOSED and ACCEPTED rows are candidates, APPLIED and already-REJECTED are not", candidates.size() == 2);
		ReviewRulesTest.check("candidate 1 is the PROPOSED row", "1".equals(candidates.get(0).rowId));
		ReviewRulesTest.check("candidate 2 is the ACCEPTED row", "2".equals(candidates.get(1).rowId));
	}

	private static void cascadeEmptyWhenNothingOpen()
	{
		final List<ReviewRules.AffectedRow> rows = new ArrayList<ReviewRules.AffectedRow>();
		rows.add(new ReviewRules.AffectedRow(ReviewLine.ATTRIBUTE, "1", "x", ReviewLine.APPLIED));
		rows.add(new ReviewRules.AffectedRow(ReviewLine.ATTRIBUTE, "2", "y", ReviewLine.REJECTED));
		ReviewRulesTest.check("nothing left to cascade onto", ReviewRules.cascadeCandidates(rows).isEmpty());
	}

	/**
	 * {@code StagingLlmPass}'s canonical attributes (origin LLM) never set {@code referenceEntity} at all today - the model only aligns
	 * columns, it does not propose foreign keys - but if a canonical attribute ever does carry a reference, it was never deduced from a
	 * {@code LandingRelation} (only rule 6 sets {@code fromRelation}), so the relation half of the rule must not apply: entity plus
	 * referenced-entity acceptance is the whole requirement, exactly {@link #attributeIgnoresRelationWhenReferenceNotFromOne} again, named
	 * for this specific origin per the coordinator's request (2026-09-20 review of the fromRelation fix).
	 */
	private static void llmCanonicalAttributeReferenceNeedsOnlyItsEntityAccepted()
	{
		final ReviewRules.Decision decision = ReviewRules.canAcceptStagingAttribute(ReviewLine.ACCEPTED, "Company", "Account", ReviewLine.ACCEPTED, null);
		ReviewRulesTest.check("a canonical (LLM) attribute's reference, having no fromRelation, needs only its two entities accepted", decision.allowed);
	}

	/** Accepting a StagingAttribute must not require its AttributeMapping rows accepted first - a canonical attribute can have several, one per source it aligns. */
	private static void acceptingAnAttributeNeverLooksAtItsMappings()
	{
		// canAcceptStagingAttribute takes no mapping status at all: nothing about any AttributeMapping row can block this decision
		final ReviewRules.Decision decision = ReviewRules.canAcceptStagingAttribute(ReviewLine.ACCEPTED, "Company", null, null, null);
		ReviewRulesTest.check("accepted on entity status alone, regardless of how many (unaccepted) mappings feed it", decision.allowed);
	}

	/**
	 * The coordinator's rule after the live refusal on {@code Companies}: {@code sourceSystem}/{@code sourceId} have no decision of their
	 * own, whatever their stored status - PROPOSED (never touched yet), ACCEPTED (already cascaded), or even APPLIED - the refusal message
	 * is the same "follows its entity" one throughout, not the generic "already applied" one.
	 */
	private static void technicalKeyCannotBeDecidedDirectlyWhateverItsStatus()
	{
		for (final String status : new String[] { ReviewLine.PROPOSED, ReviewLine.ACCEPTED, ReviewLine.REJECTED, ReviewLine.APPLIED })
		{
			final ReviewRules.Decision decision = ReviewRules.canDecide(status, true);
			ReviewRulesTest.check("a technical-key attribute (status " + status + ") cannot be decided directly", !decision.allowed);
			ReviewRulesTest.check("the refusal says it follows its entity, not a generic 'already applied'",
				decision.reason != null && decision.reason.toLowerCase().contains("entity"));
		}
	}

	/** The two-argument overload defers to the ordinary rule for a non-primary-key attribute: only APPLIED blocks it. */
	private static void ordinaryAttributeStillGovernedByTheAppliedRule()
	{
		ReviewRulesTest.check("a plain attribute PROPOSED can be decided", ReviewRules.canDecide(ReviewLine.PROPOSED, false).allowed);
		ReviewRulesTest.check("a plain attribute ACCEPTED can be re-decided", ReviewRules.canDecide(ReviewLine.ACCEPTED, false).allowed);
		final ReviewRules.Decision applied = ReviewRules.canDecide(ReviewLine.APPLIED, false);
		ReviewRulesTest.check("a plain attribute APPLIED is refused by the ordinary rule", !applied.allowed && applied.reason.toLowerCase().contains("applied"));
	}

	/**
	 * {@link ReviewRules#attributeMappingWarning}: the exact contradiction a coordinator review asked to surface (2026-09-20) - an
	 * ACCEPTED (or APPLIED) attribute whose every mapping is REJECTED, or that has none at all, has nothing left to feed it. Never a
	 * warning when the attribute is not accepted-like, when at least one mapping is still able to feed it (PROPOSED/ACCEPTED/APPLIED),
	 * or when the attribute is the technical key (which never has a mapping of its own - not a contradiction).
	 */
	private static void attributeMappingWarningOnlyWhenAcceptedAndEveryMappingRejected()
	{
		ReviewRulesTest.check("PROPOSED attribute: no warning regardless of its mappings",
			ReviewRules.attributeMappingWarning(ReviewLine.PROPOSED, false, Arrays.asList(ReviewLine.REJECTED)) == null);
		ReviewRulesTest.check("ACCEPTED attribute, every mapping REJECTED: warns",
			ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, Arrays.asList(ReviewLine.REJECTED, ReviewLine.REJECTED)) != null);
		ReviewRulesTest.check("APPLIED attribute, every mapping REJECTED: warns too",
			ReviewRules.attributeMappingWarning(ReviewLine.APPLIED, false, Arrays.asList(ReviewLine.REJECTED)) != null);
		ReviewRulesTest.check("ACCEPTED attribute, no mapping at all: warns (nothing feeds it either way)",
			ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, new ArrayList<String>()) != null);
		ReviewRulesTest.check("ACCEPTED attribute, one mapping still PROPOSED: no warning, it can still feed it",
			ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, Arrays.asList(ReviewLine.REJECTED, ReviewLine.PROPOSED)) == null);
		ReviewRulesTest.check("ACCEPTED attribute, one mapping ACCEPTED among rejected ones: no warning",
			ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, Arrays.asList(ReviewLine.REJECTED, ReviewLine.ACCEPTED)) == null);
		ReviewRulesTest.check("the technical key never warns: it never has a mapping of its own, that is normal, not a contradiction",
			ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, true, new ArrayList<String>()) == null);
	}

	/**
	 * {@link ReviewRules#attributeMappingWarning(String, boolean, boolean, List)} (2026-09-22): a derived
	 * ({@code FOLD_KEY}) attribute never has a mapping of its own either, the same STRUCTURAL reason a
	 * primary-key attribute never warns - without this guard every accepted derived attribute would show
	 * "Accepted, but no mapping feeds it." forever, a false alarm for something working exactly as designed.
	 * The 3-arg overload (existing callers/tests) still delegates to {@code pDerived = false}, unaffected.
	 */
	private static void attributeMappingWarningNeverForADerivedAttribute()
	{
		ReviewRulesTest.check("ACCEPTED derived attribute, no mapping at all: no warning (structural, like a primary key)",
			ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, true, new ArrayList<String>()) == null);
		ReviewRulesTest.check("APPLIED derived attribute, every mapping REJECTED (should never happen, but still no warning)",
			ReviewRules.attributeMappingWarning(ReviewLine.APPLIED, false, true, Arrays.asList(ReviewLine.REJECTED)) == null);
		ReviewRulesTest.check("the SAME attribute, pDerived=false, still warns: proves the guard is what changed the answer",
			ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, false, new ArrayList<String>()) != null);
		ReviewRulesTest.check("3-arg overload unchanged (pDerived defaults to false)",
			ReviewRules.attributeMappingWarning(ReviewLine.ACCEPTED, false, new ArrayList<String>()) != null);
	}

	/**
	 * fix-bulk-accept-applies (2026-09-21): {@code shouldApplyAfterAccept} is true ONLY for a
	 * {@code SCRIPT_CONSTRAINT} row moved to {@code ACCEPTED} - never for another nature, and never for a
	 * {@code SCRIPT_CONSTRAINT} row moved to {@code REJECTED} (rejecting never applies anything).
	 */
	private static void shouldApplyAfterAcceptOnlyForScriptConstraintMovedToAccepted()
	{
		ReviewRulesTest.check("SCRIPT_CONSTRAINT moved to ACCEPTED: applies",
			ReviewRules.shouldApplyAfterAccept(ReviewLine.ACCEPTED, ReviewLine.SCRIPT_CONSTRAINT));
		ReviewRulesTest.check("SCRIPT_CONSTRAINT moved to REJECTED: never applies",
			!ReviewRules.shouldApplyAfterAccept(ReviewLine.REJECTED, ReviewLine.SCRIPT_CONSTRAINT));
		ReviewRulesTest.check("lot M5: SCRIPT_DERIVATION moved to ACCEPTED: applies too (a bulk accept used to leave it unapplied)",
			ReviewRules.shouldApplyAfterAccept(ReviewLine.ACCEPTED, ReviewLine.SCRIPT_DERIVATION));
		ReviewRulesTest.check("lot M5: SCRIPT_DERIVATION moved to REJECTED: never applies",
			!ReviewRules.shouldApplyAfterAccept(ReviewLine.REJECTED, ReviewLine.SCRIPT_DERIVATION));
		ReviewRulesTest.check("ATTRIBUTE moved to ACCEPTED: never applies (only the script natures do)",
			!ReviewRules.shouldApplyAfterAccept(ReviewLine.ACCEPTED, ReviewLine.ATTRIBUTE));
		ReviewRulesTest.check("EXTERNAL_SERVICE moved to ACCEPTED: never applies (decision-only nature)",
			!ReviewRules.shouldApplyAfterAccept(ReviewLine.ACCEPTED, ReviewLine.EXTERNAL_SERVICE));
	}

	/**
	 * fix-bulk-accept-applies (2026-09-21): {@code idsToApplyAfterAccept} collects ONLY the ids that are
	 * BOTH in {@code pSucceededIds} (this round's own successful decisions) AND eligible per
	 * {@link #shouldApplyAfterAcceptOnlyForScriptConstraintMovedToAccepted} - a SCRIPT_CONSTRAINT row NOT
	 * in the succeeded set (refused, or simply not part of this batch) is never collected even though its
	 * nature alone would qualify; a non-SCRIPT_CONSTRAINT row that DID succeed is never collected either;
	 * a REJECT-target batch collects nothing at all, whatever succeeded.
	 */
	private static void idsToApplyAfterAcceptOnlyCollectsThisRoundsSuccessfulScriptConstraintAccepts()
	{
		final List<ReviewRules.AffectedRow> rows = Arrays.asList(
			new ReviewRules.AffectedRow(ReviewLine.SCRIPT_CONSTRAINT, "c1", "Contact.phone", ReviewLine.PROPOSED),
			new ReviewRules.AffectedRow(ReviewLine.SCRIPT_CONSTRAINT, "c2", "Contact.iban", ReviewLine.PROPOSED),
			new ReviewRules.AffectedRow(ReviewLine.ATTRIBUTE, "a1", "Supplier.legalName", ReviewLine.PROPOSED));
		// c1 and a1 succeeded this round, c2 was refused (e.g. a stale row) - c2 must never be collected even
		// though it is a SCRIPT_CONSTRAINT, because this round did NOT actually move it to ACCEPTED
		final java.util.Set<String> succeeded = new java.util.LinkedHashSet<String>(Arrays.asList("c1", "a1"));
		final List<String> toApply = ReviewRules.idsToApplyAfterAccept(rows, succeeded, ReviewLine.ACCEPTED);
		ReviewRulesTest.check("only c1 collected: exactly one id", toApply.size() == 1);
		ReviewRulesTest.check("c1 (succeeded, SCRIPT_CONSTRAINT) is the one collected", toApply.contains("c1"));
		ReviewRulesTest.check("c2 (SCRIPT_CONSTRAINT but refused, not in succeeded) is NOT collected", !toApply.contains("c2"));
		ReviewRulesTest.check("a1 (succeeded but not SCRIPT_CONSTRAINT) is NOT collected", !toApply.contains("a1"));
		final List<String> nothingOnReject = ReviewRules.idsToApplyAfterAccept(rows, succeeded, ReviewLine.REJECTED);
		ReviewRulesTest.check("a REJECT-target batch collects nothing at all, whatever succeeded", nothingOnReject.isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
