package com.on.ebx.factory.review;

/** JUnit-free: run the main, a failure throws. */
public final class DecisionActionsTest
{
	public static void main(final String[] pArgs)
	{
		DecisionActionsTest.proposedOffersBoth();
		DecisionActionsTest.differenceIsAPlainAcceptRejectLineAndOnlyAProposedOneIsEditable();
		DecisionActionsTest.acceptedOffersOnlyReject();
		DecisionActionsTest.rejectedOffersOnlyAccept();
		DecisionActionsTest.appliedOffersNeither();
		DecisionActionsTest.unknownStatusDefaultsToBoth();
		DecisionActionsTest.appliedScriptConstraintOffersRemove();
		DecisionActionsTest.appliedOtherNatureStillOffersNothing();
		DecisionActionsTest.nonAppliedScriptConstraintBehavesLikeGenericOverload();
		DecisionActionsTest.acceptedNotAppliedScriptConstraintOffersApply();
		DecisionActionsTest.acceptedOtherNatureStillOffersOnlyReject();
		DecisionActionsTest.decidableReflectsAcceptRejectOrApplyOnly();
		DecisionActionsTest.appliedScriptConstraintStaleOffersReapplyToo();
		DecisionActionsTest.appliedScriptConstraintCurrentOffersNoReapply();
		DecisionActionsTest.staleFlagIgnoredForEveryOtherStatusOrNature();
		DecisionActionsTest.threeArgOverloadMatchesTwoArgWhenNotStale();
		DecisionActionsTest.reapplyNeverMakesALineDecidable();
		DecisionActionsTest.matchProposedOffersAcceptReproposeAndConfirmedReject();
		DecisionActionsTest.matchSupersededBehavesLikeProposed();
		DecisionActionsTest.matchAcceptedOffersReproposeAndConfirmedRejectNotAccept();
		DecisionActionsTest.matchRejectedOffersAcceptAndReproposeNotReject();
		DecisionActionsTest.matchAppliedOffersNothing();
		DecisionActionsTest.matchConfirmedRejectNeverMakesALineDecidableForReject();
		DecisionActionsTest.otherNaturesNeverOfferReproposeOrConfirmReject();

		System.out.println("DecisionActionsTest OK");
	}

	/** Lot C1-B: the nature DIFFERENCE follows the generic status rule; "Edit" exists for a still-PROPOSED difference only. */
	private static void differenceIsAPlainAcceptRejectLineAndOnlyAProposedOneIsEditable()
	{
		final String nature = ReviewLine.DIFFERENCE;
		DecisionActionsTest.check("PROPOSED offers both", DecisionActions.forStatus(ReviewLine.PROPOSED, nature).accept && DecisionActions.forStatus(ReviewLine.PROPOSED, nature).reject);
		DecisionActionsTest.check("ACCEPTED offers only Reject", !DecisionActions.forStatus(ReviewLine.ACCEPTED, nature).accept && DecisionActions.forStatus(ReviewLine.ACCEPTED, nature).reject);
		DecisionActionsTest.check("REJECTED offers only Accept", DecisionActions.forStatus(ReviewLine.REJECTED, nature).accept && !DecisionActions.forStatus(ReviewLine.REJECTED, nature).reject);
		DecisionActionsTest.check("APPLIED offers nothing", !DecisionActions.forStatus(ReviewLine.APPLIED, nature).decidable());
		DecisionActionsTest.check("no apply, remove, measure or repropose on a difference", !DecisionActions.forStatus(ReviewLine.PROPOSED, nature).apply
			&& !DecisionActions.forStatus(ReviewLine.PROPOSED, nature).remove && !DecisionActions.forStatus(ReviewLine.PROPOSED, nature).repropose);
		DecisionActionsTest.check("a PROPOSED difference is editable", DecisionActions.editable(ReviewLine.PROPOSED, nature));
		DecisionActionsTest.check("a decided difference is not", !DecisionActions.editable(ReviewLine.ACCEPTED, nature) && !DecisionActions.editable(ReviewLine.REJECTED, nature)
			&& !DecisionActions.editable(ReviewLine.APPLIED, nature));
		DecisionActionsTest.check("no other nature is", !DecisionActions.editable(ReviewLine.PROPOSED, ReviewLine.ATTRIBUTE) && !DecisionActions.editable(ReviewLine.PROPOSED, ReviewLine.MATCH));
	}

	private static void proposedOffersBoth()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.PROPOSED);
		DecisionActionsTest.check("PROPOSED offers Accept", actions.accept);
		DecisionActionsTest.check("PROPOSED offers Reject", actions.reject);
	}

	/** The exact defect reported live: an ACCEPTED row must never still offer "Accept". */
	private static void acceptedOffersOnlyReject()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.ACCEPTED);
		DecisionActionsTest.check("ACCEPTED does not offer Accept again", !actions.accept);
		DecisionActionsTest.check("ACCEPTED still offers Reject", actions.reject);
	}

	private static void rejectedOffersOnlyAccept()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.REJECTED);
		DecisionActionsTest.check("REJECTED offers Accept", actions.accept);
		DecisionActionsTest.check("REJECTED does not offer Reject again", !actions.reject);
	}

	private static void appliedOffersNeither()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.APPLIED);
		DecisionActionsTest.check("APPLIED offers no Accept", !actions.accept);
		DecisionActionsTest.check("APPLIED offers no Reject", !actions.reject);
	}

	private static void unknownStatusDefaultsToBoth()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus("SOMETHING_ELSE");
		DecisionActionsTest.check("an unexpected status is not silently locked out: both offered, same as the original behaviour", actions.accept && actions.reject);
	}

	/** Apply lot (2026-09-21): the ONE case the nature-aware overload treats differently - Remove AND Measure again, no Accept/Reject (coordinator review: bounded automatic measurement needs an on-demand re-run). */
	private static void appliedScriptConstraintOffersRemove()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.SCRIPT_CONSTRAINT);
		DecisionActionsTest.check("APPLIED SCRIPT_CONSTRAINT offers Remove", actions.remove);
		DecisionActionsTest.check("APPLIED SCRIPT_CONSTRAINT offers Measure again", actions.measureAgain);
		DecisionActionsTest.check("... and nothing else", !actions.accept && !actions.reject);
	}

	private static void appliedOtherNatureStillOffersNothing()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.EXTERNAL_SERVICE);
		DecisionActionsTest.check("APPLIED of another nature offers no Remove", !actions.remove);
		DecisionActionsTest.check("APPLIED of another nature offers no Measure again", !actions.measureAgain);
		DecisionActionsTest.check("... and nothing else either, same as the generic overload", !actions.accept && !actions.reject);
	}

	private static void nonAppliedScriptConstraintBehavesLikeGenericOverload()
	{
		final DecisionActions.Actions proposed = DecisionActions.forStatus(ReviewLine.PROPOSED, ReviewLine.SCRIPT_CONSTRAINT);
		DecisionActionsTest.check("PROPOSED SCRIPT_CONSTRAINT still offers both, never Remove/Measure/Apply", proposed.accept && proposed.reject && !proposed.remove
			&& !proposed.measureAgain && !proposed.apply);
	}

	/**
	 * fix-apply-ux (2026-09-21): an ACCEPTED SCRIPT_CONSTRAINT not (yet) APPLIED - accepted before this lot
	 * was deployed, or left over from a failed apply attempt - now has a way forward: Apply, alongside Reject
	 * (never plain Accept - acceptance itself already triggers apply on a still-PROPOSED line).
	 */
	private static void acceptedNotAppliedScriptConstraintOffersApply()
	{
		final DecisionActions.Actions accepted = DecisionActions.forStatus(ReviewLine.ACCEPTED, ReviewLine.SCRIPT_CONSTRAINT);
		DecisionActionsTest.check("offers Apply", accepted.apply);
		DecisionActionsTest.check("still offers Reject", accepted.reject);
		DecisionActionsTest.check("never plain Accept, Remove or Measure again", !accepted.accept && !accepted.remove && !accepted.measureAgain);
	}

	/** Every other nature's ACCEPTED line is unaffected - the generic overload still offers only Reject. */
	private static void acceptedOtherNatureStillOffersOnlyReject()
	{
		final DecisionActions.Actions accepted = DecisionActions.forStatus(ReviewLine.ACCEPTED, ReviewLine.EXTERNAL_SERVICE);
		DecisionActionsTest.check("no Apply for a different nature", !accepted.apply);
		DecisionActionsTest.check("still offers Reject only", !accepted.accept && accepted.reject);
	}

	/** fix-apply-ux (2026-09-21), item 3: {@code Actions.decidable()} - the selection bar's own checkbox-eligibility test - is true for accept/reject/apply but never for remove/measureAgain alone (an already-APPLIED SCRIPT_CONSTRAINT line, single-row-only actions). */
	private static void decidableReflectsAcceptRejectOrApplyOnly()
	{
		DecisionActionsTest.check("PROPOSED (accept+reject) is decidable", DecisionActions.forStatus(ReviewLine.PROPOSED).decidable());
		DecisionActionsTest.check("ACCEPTED (reject only) is decidable", DecisionActions.forStatus(ReviewLine.ACCEPTED).decidable());
		DecisionActionsTest.check("REJECTED (accept only) is decidable", DecisionActions.forStatus(ReviewLine.REJECTED).decidable());
		DecisionActionsTest.check("APPLIED (nothing) is not decidable", !DecisionActions.forStatus(ReviewLine.APPLIED).decidable());
		DecisionActionsTest.check("ACCEPTED SCRIPT_CONSTRAINT (reject+apply) is decidable",
			DecisionActions.forStatus(ReviewLine.ACCEPTED, ReviewLine.SCRIPT_CONSTRAINT).decidable());
		DecisionActionsTest.check("APPLIED SCRIPT_CONSTRAINT (remove+measureAgain only) is NOT decidable - single-row-only actions",
			!DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.SCRIPT_CONSTRAINT).decidable());
	}

	/** Re-apply lot (2026-09-21): the ONE case the staleness-aware overload treats differently - Re-apply joins Remove/Measure again, never Accept/Reject/plain Apply. */
	private static void appliedScriptConstraintStaleOffersReapplyToo()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.SCRIPT_CONSTRAINT, true);
		DecisionActionsTest.check("offers Re-apply", actions.reapply);
		DecisionActionsTest.check("still offers Remove", actions.remove);
		DecisionActionsTest.check("still offers Measure again", actions.measureAgain);
		DecisionActionsTest.check("never Accept/Reject/plain Apply", !actions.accept && !actions.reject && !actions.apply);
	}

	/** A CURRENT (not stale) APPLIED SCRIPT_CONSTRAINT line behaves exactly like the two-argument overload - no Re-apply button. */
	private static void appliedScriptConstraintCurrentOffersNoReapply()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.SCRIPT_CONSTRAINT, false);
		DecisionActionsTest.check("no Re-apply", !actions.reapply);
		DecisionActionsTest.check("still offers Remove and Measure again", actions.remove && actions.measureAgain);
	}

	/** {@code pStale} is only ever consulted for APPLIED SCRIPT_CONSTRAINT - every other combination ignores it entirely, whichever value is passed. */
	private static void staleFlagIgnoredForEveryOtherStatusOrNature()
	{
		DecisionActionsTest.check("PROPOSED SCRIPT_CONSTRAINT, stale=true: unaffected",
			!DecisionActions.forStatus(ReviewLine.PROPOSED, ReviewLine.SCRIPT_CONSTRAINT, true).reapply);
		DecisionActionsTest.check("ACCEPTED SCRIPT_CONSTRAINT, stale=true: unaffected (offers Apply, not Reapply)",
			DecisionActions.forStatus(ReviewLine.ACCEPTED, ReviewLine.SCRIPT_CONSTRAINT, true).apply
				&& !DecisionActions.forStatus(ReviewLine.ACCEPTED, ReviewLine.SCRIPT_CONSTRAINT, true).reapply);
		DecisionActionsTest.check("APPLIED EXTERNAL_SERVICE, stale=true: unaffected",
			!DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.EXTERNAL_SERVICE, true).reapply);
	}

	private static void threeArgOverloadMatchesTwoArgWhenNotStale()
	{
		final DecisionActions.Actions twoArg = DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.SCRIPT_CONSTRAINT);
		final DecisionActions.Actions threeArgNotStale = DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.SCRIPT_CONSTRAINT, false);
		DecisionActionsTest.check("same remove", twoArg.remove == threeArgNotStale.remove);
		DecisionActionsTest.check("same measureAgain", twoArg.measureAgain == threeArgNotStale.measureAgain);
		DecisionActionsTest.check("same reapply (both false)", twoArg.reapply == threeArgNotStale.reapply);
	}

	/** Re-apply lot (2026-09-21), task brief's own instruction: Re-apply is single-row-only, never part of the multi-select batch. */
	private static void reapplyNeverMakesALineDecidable()
	{
		DecisionActionsTest.check("a stale APPLIED SCRIPT_CONSTRAINT line is still NOT decidable",
			!DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.SCRIPT_CONSTRAINT, true).decidable());
	}

	/**
	 * Reject/repropose lot (2026-09-22): a MATCH line is not like the other four natures - PROPOSED offers
	 * Accept, "Propose another" (repropose) AND a CONFIRMED "No matching for this entity" (confirmReject),
	 * never a plain unconfirmed Reject.
	 */
	private static void matchProposedOffersAcceptReproposeAndConfirmedReject()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.PROPOSED, ReviewLine.MATCH);
		DecisionActionsTest.check("MATCH PROPOSED offers Accept", actions.accept);
		DecisionActionsTest.check("MATCH PROPOSED offers reject (No matching)", actions.reject);
		DecisionActionsTest.check("... which requires confirmation", actions.confirmReject);
		DecisionActionsTest.check("MATCH PROPOSED offers Propose another", actions.repropose);
	}

	/** SUPERSEDED ("waiting for a new proposal") is not a decision - treated exactly like PROPOSED. */
	private static void matchSupersededBehavesLikeProposed()
	{
		final DecisionActions.Actions superseded = DecisionActions.forStatus(ReviewLine.SUPERSEDED, ReviewLine.MATCH);
		final DecisionActions.Actions proposed = DecisionActions.forStatus(ReviewLine.PROPOSED, ReviewLine.MATCH);
		DecisionActionsTest.check("same accept", superseded.accept == proposed.accept);
		DecisionActionsTest.check("same reject", superseded.reject == proposed.reject);
		DecisionActionsTest.check("same confirmReject", superseded.confirmReject == proposed.confirmReject);
		DecisionActionsTest.check("same repropose", superseded.repropose == proposed.repropose);
	}

	/** An ACCEPTED MATCH plan is not final: still offers a confirmed "No matching" and "Propose another", never plain Accept again. */
	private static void matchAcceptedOffersReproposeAndConfirmedRejectNotAccept()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.ACCEPTED, ReviewLine.MATCH);
		DecisionActionsTest.check("no plain Accept", !actions.accept);
		DecisionActionsTest.check("offers confirmed reject", actions.reject && actions.confirmReject);
		DecisionActionsTest.check("offers Propose another", actions.repropose);
	}

	/** The decision to exclude an entity is reversible: a REJECTED MATCH line offers Accept and Propose another, never a second "No matching" reject. */
	private static void matchRejectedOffersAcceptAndReproposeNotReject()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.REJECTED, ReviewLine.MATCH);
		DecisionActionsTest.check("offers Accept", actions.accept);
		DecisionActionsTest.check("offers Propose another", actions.repropose);
		DecisionActionsTest.check("no second reject", !actions.reject);
		DecisionActionsTest.check("confirmReject is false when reject itself is false", !actions.confirmReject);
	}

	private static void matchAppliedOffersNothing()
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(ReviewLine.APPLIED, ReviewLine.MATCH);
		DecisionActionsTest.check("MATCH APPLIED offers nothing", !actions.accept && !actions.reject && !actions.repropose);
	}

	/** The selection bar has no per-row confirmation dialog: a reject that needs confirmation must never make a line bulk-decidable through reject alone. */
	private static void matchConfirmedRejectNeverMakesALineDecidableForReject()
	{
		final DecisionActions.Actions proposed = DecisionActions.forStatus(ReviewLine.PROPOSED, ReviewLine.MATCH);
		DecisionActionsTest.check("still decidable overall (Accept alone is enough)", proposed.decidable());
		final DecisionActions.Actions accepted = DecisionActions.forStatus(ReviewLine.ACCEPTED, ReviewLine.MATCH);
		DecisionActionsTest.check("ACCEPTED MATCH (confirmReject only, no accept/apply) is NOT decidable", !accepted.decidable());
	}

	/** repropose/confirmReject are MATCH-only - every other nature's Actions never sets either, whatever the status. */
	private static void otherNaturesNeverOfferReproposeOrConfirmReject()
	{
		for (final String status : new String[] { ReviewLine.PROPOSED, ReviewLine.ACCEPTED, ReviewLine.REJECTED, ReviewLine.APPLIED })
		{
			for (final String nature : new String[] { ReviewLine.RELATION, ReviewLine.ENTITY, ReviewLine.ATTRIBUTE, ReviewLine.MAPPING, ReviewLine.MAPPING_STEP,
				ReviewLine.QUALITY, ReviewLine.SCRIPT_CONSTRAINT, ReviewLine.EXTERNAL_SERVICE })
			{
				final DecisionActions.Actions actions = DecisionActions.forStatus(status, nature);
				DecisionActionsTest.check(nature + "/" + status + " never offers repropose", !actions.repropose);
				DecisionActionsTest.check(nature + "/" + status + " never requires reject confirmation", !actions.confirmReject);
			}
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
