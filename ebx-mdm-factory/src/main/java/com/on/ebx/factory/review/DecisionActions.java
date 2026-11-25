package com.on.ebx.factory.review;

/**
 * Which actions a decision cell shows for one line, from its status alone - shared by {@link ReviewPage}
 * and the wizard's staging step ({@code com.on.ebx.factory.wizard.WizardPage}) so the two screens cannot
 * drift, put next to {@link ReviewRules} per the coordinator's own instruction. Product owner, 2026-09-21,
 * on a live screenshot: "the Accepted tag seems to be set on attributes, but I can still 'Accept' them" -
 * the previous rendering showed BOTH buttons on every non-APPLIED, non-technical-key row regardless of its
 * CURRENT status, so an already-{@code ACCEPTED} row still offered "Accept" (a no-op that only looked like
 * one) and an already-{@code REJECTED} row still offered "Reject".
 * <p>
 * Pure: derives only which controls to draw; {@link ReviewRules}/{@code ProposalReviews} still own
 * whether a transition is actually allowed once clicked - this class must never offer a button for a
 * transition the write path would refuse (an {@code APPLIED} line gets neither).
 * <p>
 * <b>{@code ACCEPTED} offers only Reject, not an "undo back to PROPOSED"</b> - checked against the write
 * path before deciding this, per the coordinator's own instruction: {@code ProposalReviews.decide} writes
 * whatever target status its caller passes with no semantic check on the value itself, so a revert to
 * {@code PROPOSED} is MECHANICALLY possible, but no UI action, message key, or test anywhere treats
 * {@code PROPOSED} as a valid decision target - it is the absence of a decision, not one. Inventing an
 * "Undo" action here would be a new, untested status transition invented in the UI layer alone. Left as a
 * follow-up for a real rule change (see the module's final report).
 * <p>
 * <b>{@link ReviewLine#MATCH} is not like the other four natures (reject/repropose lot, 2026-09-22,
 * decided with the product owner at 13:20 - see PLAN.md).</b> There is one matching plan per entity, and without one
 * there is no matching at all for it - {@code ebx.MatchingConfigurator.configure} simply lists the entity
 * as excluded. A plain "Reject" is therefore a dead end for every OTHER nature (the row just stops being
 * considered) but would be actively harmful here, so a MATCH line offers three actions instead of two:
 * <ul>
 * <li><b>Accept</b> - unchanged, offered on {@link ReviewLine#PROPOSED}/{@link ReviewLine#SUPERSEDED}/
 * {@link ReviewLine#REJECTED} (accepting the still-present plan of an entity someone earlier decided
 * should have none), never on {@link ReviewLine#ACCEPTED} itself or {@link ReviewLine#APPLIED};</li>
 * <li><b>Propose another</b> ({@link Actions#repropose}) - the default alternative to Accept, offered on
 * every status except {@link ReviewLine#APPLIED}: asks the language model for a different plan, never
 * discarding the current one first (see {@code ebx.MatchPlanLlmSuggester}'s own Javadoc for the
 * PROPOSED/SUPERSEDED-back-to-PROPOSED transitions this drives) - "the previous plan stays acceptable at
 * every moment" is the whole point, so this is available even on a {@link ReviewLine#REJECTED} line (a
 * decision to exclude the entity is itself reversible);</li>
 * <li><b>No matching for this entity</b> ({@link Actions#reject}, {@link Actions#confirmReject} true) - the
 * only true "reject": writes {@link ReviewLine#REJECTED} with the reviewer's comment, offered on every
 * status except {@link ReviewLine#REJECTED} itself and {@link ReviewLine#APPLIED} - a second click/checkbox
 * confirmation is mandatory (see {@code ReviewPage}'s own rendering), since this one action genuinely has a
 * product consequence the other four natures' plain reject never has.</li>
 * </ul>
 * {@link ReviewLine#SUPERSEDED} is treated exactly like {@link ReviewLine#PROPOSED} here - "waiting for a
 * new proposal" is not a decided state, so the plan underneath must stay just as actionable.
 */
public final class DecisionActions
{
	private DecisionActions()
	{
	}

	/**
	 * Which buttons a decision cell shows for one line; {@link #accept}/{@link #reject}/{@link #remove}/
	 * {@link #measureAgain}/{@link #apply}/{@link #reapply} all false means "chip only, no action" (an
	 * {@code APPLIED} line of any nature other than {@code SCRIPT_CONSTRAINT}, which {@link #forStatus(String,
	 * String)} alone can offer Remove/Measure-again for, and {@link #forStatus(String, String, boolean)} can
	 * additionally offer Re-apply for - see those overloads' own Javadoc).
	 */
	public static final class Actions
	{
		public final boolean accept;
		public final boolean reject;
		public final boolean remove;
		/** {@code POST .../capabilities/{id}/measure} on demand - coordinator review, apply lot 2026-09-21 (bounded automatic measurement, re-runnable regardless of size). Only ever true alongside {@link #remove} (an APPLIED SCRIPT_CONSTRAINT offers both). */
		public final boolean measureAgain;
		/** {@code CapabilityApplier.applyAll} for a SCRIPT_CONSTRAINT line that is {@code ACCEPTED} but NOT (yet) {@code APPLIED} - fix-apply-ux (2026-09-21): a real gap ("accepted before this lot was deployed", or any failed apply attempt) with no way forward in the screen otherwise. Only ever true alongside {@link #reject} - never alongside {@link #accept} (a SCRIPT_CONSTRAINT is never offered plain Accept: acceptance itself already triggers apply on a still-PROPOSED line). */
		public final boolean apply;
		/**
		 * Re-apply lot (2026-09-21): the SAME {@code CapabilityApplier.apply}/{@code applyAll} call as
		 * {@link #apply} (re-applying an already-{@code APPLIED} row is not skipped - {@code
		 * docs/CAPABILITY-APPLY.md} section 2), offered on an {@code APPLIED SCRIPT_CONSTRAINT} line whose
		 * live form {@code capability.ConstraintStaleness} reports as stale - alongside {@link #remove}/
		 * {@link #measureAgain}, never alongside {@link #accept}/{@link #reject}/{@link #apply}. Deliberately
		 * kept OUT of {@link #decidable()}: single-row-only, like {@link #remove}/{@link #measureAgain} - the
		 * task brief's own instruction ("join the batch actions only if the existing mechanism makes that
		 * trivial; otherwise leave it single-line") - the multi-select checkbox/batch machinery has no notion
		 * of "stale" at all, and bolting one on for a single button was judged not worth the added surface.
		 */
		public final boolean reapply;
		/** Reject/repropose lot (2026-09-22), {@link ReviewLine#MATCH} only - see this class' own Javadoc: "Propose another". Never true alongside {@link #reapply}/{@link #remove}/{@link #measureAgain} (those are SCRIPT_CONSTRAINT-only, mutually exclusive by nature). */
		public final boolean repropose;
		/** Reject/repropose lot (2026-09-22): true only when {@link #reject} is itself the MATCH-only "No matching for this entity" action, which the page must not submit without an explicit second confirmation (see this class' own Javadoc). False for every ordinary reject, on every other nature. */
		public final boolean confirmReject;

		private Actions(final boolean pAccept, final boolean pReject, final boolean pRemove, final boolean pMeasureAgain, final boolean pApply)
		{
			this(pAccept, pReject, pRemove, pMeasureAgain, pApply, false);
		}

		private Actions(final boolean pAccept, final boolean pReject, final boolean pRemove, final boolean pMeasureAgain, final boolean pApply,
			final boolean pReapply)
		{
			this(pAccept, pReject, pRemove, pMeasureAgain, pApply, pReapply, false, false);
		}

		private Actions(final boolean pAccept, final boolean pReject, final boolean pRemove, final boolean pMeasureAgain, final boolean pApply,
			final boolean pReapply, final boolean pRepropose, final boolean pConfirmReject)
		{
			this.accept = pAccept;
			this.reject = pReject;
			this.remove = pRemove;
			this.measureAgain = pMeasureAgain;
			this.apply = pApply;
			this.reapply = pReapply;
			this.repropose = pRepropose;
			this.confirmReject = pConfirmReject;
		}

		/**
		 * fix-apply-ux (2026-09-21), item 3: whether this line can take a multi-select checkbox at all -
		 * {@link #accept}, {@link #reject} or {@link #apply} (never {@link #remove}/{@link #measureAgain}
		 * alone - those are single-row-only actions on an already-{@code APPLIED} line, not offered by the
		 * selection bar). Reject/repropose lot (2026-09-22): a {@link #reject} that needs {@link
		 * #confirmReject} does NOT count towards this - the selection bar has no per-row confirmation dialog,
		 * so "No matching for this entity" is only ever reachable through the single-row button. {@link
		 * #repropose} never makes a line decidable either (it needs its own inline comment box, same
		 * single-row-only reasoning as {@link #reapply}).
		 */
		public boolean decidable()
		{
			return this.accept || (this.reject && !this.confirmReject) || this.apply;
		}
	}

	private static final Actions BOTH = new Actions(true, true, false, false, false);
	private static final Actions ACCEPT_ONLY = new Actions(true, false, false, false, false);
	private static final Actions REJECT_ONLY = new Actions(false, true, false, false, false);
	private static final Actions NONE = new Actions(false, false, false, false, false);
	private static final Actions REMOVE_AND_MEASURE = new Actions(false, false, true, true, false);
	private static final Actions REJECT_AND_APPLY = new Actions(false, true, false, false, true);
	/** script-backed-derivation lot (2026-09-23): an {@code APPLIED SCRIPT_DERIVATION} line offers Remove only - no Measure again (a derivation never flags a record, there is nothing to measure - {@code quality.ScriptDerivation}'s own Javadoc) and no Re-apply/staleness tracking yet (deliberately out of scope this lot, {@code docs/SCRIPT-DERIVATION.md}). */
	private static final Actions REMOVE_ONLY = new Actions(false, false, true, false, false);
	/** Re-apply lot (2026-09-21): the SAME Remove/Measure again as {@link #REMOVE_AND_MEASURE}, plus Re-apply - an APPLIED SCRIPT_CONSTRAINT line whose live form is stale. */
	private static final Actions REMOVE_MEASURE_AND_REAPPLY = new Actions(false, false, true, true, false, true);
	/** Reject/repropose lot (2026-09-22), {@link ReviewLine#MATCH} only: {@link ReviewLine#PROPOSED}/{@link ReviewLine#SUPERSEDED} - Accept, "No matching for this entity" (confirmed), Propose another. */
	private static final Actions MATCH_UNDECIDED = new Actions(true, true, false, false, false, false, true, true);
	/** Reject/repropose lot (2026-09-22): {@link ReviewLine#ACCEPTED} MATCH line - no plain Accept (already accepted, same convention as every other nature), but "No matching for this entity" and Propose another both stay available - nothing about a matching plan is final before {@code configure} applies it. */
	private static final Actions MATCH_ACCEPTED = new Actions(false, true, false, false, false, false, true, true);
	/** Reject/repropose lot (2026-09-22): {@link ReviewLine#REJECTED} MATCH line - the decision is reversible: Accept the plan that is still there, or ask for a different one. No second "No matching" (already there). */
	private static final Actions MATCH_REJECTED = new Actions(true, false, false, false, false, false, true, false);

	/**
	 * @param pStatus {@link ReviewLine#PROPOSED}/{@link ReviewLine#ACCEPTED}/{@link ReviewLine#REJECTED}/
	 *        {@link ReviewLine#APPLIED}. This is about a line's ordinary accept/reject decision only - a
	 *        technical-key attribute (no decision of its own, {@code TechnicalKeyRule}) is a separate,
	 *        earlier check both callers already make before reaching this method: it renders its own
	 *        "follows the entity" chip instead, alone (never also this method's status chip - "show the
	 *        status once", the coordinator's own words).
	 */
	public static Actions forStatus(final String pStatus)
	{
		if (ReviewLine.ACCEPTED.equals(pStatus))
		{
			return DecisionActions.REJECT_ONLY;
		}
		if (ReviewLine.REJECTED.equals(pStatus))
		{
			return DecisionActions.ACCEPT_ONLY;
		}
		if (ReviewLine.APPLIED.equals(pStatus))
		{
			return DecisionActions.NONE;
		}
		// PROPOSED, or any other/unexpected value: the safe default is to offer the full decision, same as
		// the original unconditional rendering did for everything that was not APPLIED
		return DecisionActions.BOTH;
	}

	/**
	 * Apply lot (2026-09-21): the {@code SCRIPT_CONSTRAINT}-specific overload - an {@code APPLIED} line of
	 * THIS nature offers Remove (product owner's decision: a person can take an applied constraint back off
	 * the model) AND Measure again (coordinator review: the automatic measurement is bounded, this is the way
	 * to see the real effect on demand regardless of size, or to refresh a stale one); {@code APPLIED} of
	 * every other nature (the plain {@link #forStatus(String)} overload) still offers nothing, unchanged.
	 * Every other status behaves exactly like the generic overload - accepting a SCRIPT_CONSTRAINT applies it
	 * directly (this class draws no button for that: acceptance IS the trigger, see {@code
	 * ebx.ui.ProposalReviewUserService}), so PROPOSED/REJECTED still only ever offer Accept/Reject.
	 */
	public static Actions forStatus(final String pStatus, final String pNature)
	{
		// lot C1-B: a DIFFERENCE is an ordinary accept/reject line (no apply, no measure): the plain status rule, said once here so nothing below can catch it
		if (ReviewLine.DIFFERENCE.equals(pNature))
		{
			return DecisionActions.forStatus(pStatus);
		}
		if (ReviewLine.APPLIED.equals(pStatus) && ReviewLine.SCRIPT_CONSTRAINT.equals(pNature))
		{
			return DecisionActions.REMOVE_AND_MEASURE;
		}
		// fix-apply-ux (2026-09-21): an ACCEPTED SCRIPT_CONSTRAINT that is not (yet) APPLIED had no way forward
		// in the screen - true for real, either accepted before this lot was deployed, or left over from a
		// failed apply attempt (ebx.CapabilityApplier reverts the row to ACCEPTED on any phase-A failure)
		if (ReviewLine.ACCEPTED.equals(pStatus) && ReviewLine.SCRIPT_CONSTRAINT.equals(pNature))
		{
			return DecisionActions.REJECT_AND_APPLY;
		}
		// script-backed-derivation lot (2026-09-23): the exact same two states as SCRIPT_CONSTRAINT above -
		// APPLIED offers Remove (no Measure again, see REMOVE_ONLY's own Javadoc), ACCEPTED-not-yet-applied
		// (a failed apply attempt, ebx.DerivationApplier reverts to ACCEPTED on any failure) offers a retry
		// Apply alongside Reject - acceptance itself is still the trigger, this class draws no separate
		// button for the normal path (ebx.ui.ProposalReviewUserService#decideOne).
		if (ReviewLine.APPLIED.equals(pStatus) && ReviewLine.SCRIPT_DERIVATION.equals(pNature))
		{
			return DecisionActions.REMOVE_ONLY;
		}
		if (ReviewLine.ACCEPTED.equals(pStatus) && ReviewLine.SCRIPT_DERIVATION.equals(pNature))
		{
			return DecisionActions.REJECT_AND_APPLY;
		}
		// reject/repropose lot (2026-09-22): MATCH is not like the other four natures - see this class' own
		// Javadoc for why a plain Reject would be a dead end here. SUPERSEDED is deliberately folded into the
		// same bucket as PROPOSED: "waiting for a new proposal" is not a decided state.
		if (ReviewLine.MATCH.equals(pNature))
		{
			if (ReviewLine.APPLIED.equals(pStatus))
			{
				return DecisionActions.NONE;
			}
			if (ReviewLine.ACCEPTED.equals(pStatus))
			{
				return DecisionActions.MATCH_ACCEPTED;
			}
			if (ReviewLine.REJECTED.equals(pStatus))
			{
				return DecisionActions.MATCH_REJECTED;
			}
			// PROPOSED, SUPERSEDED, or any other/unexpected value
			return DecisionActions.MATCH_UNDECIDED;
		}
		return DecisionActions.forStatus(pStatus);
	}

	/**
	 * Lot C1-B: whether a line offers "Edit" next to Accept/Reject - a {@link ReviewLine#DIFFERENCE} still {@link ReviewLine#PROPOSED}
	 * (its JSON detail is replaced by the person before the line is accepted). Nothing else is editable this way.
	 */
	public static boolean editable(final String pStatus, final String pNature)
	{
		return ReviewLine.DIFFERENCE.equals(pNature) && ReviewLine.PROPOSED.equals(pStatus);
	}

	/**
	 * Re-apply lot (2026-09-21): the STALENESS-aware overload - an {@code APPLIED SCRIPT_CONSTRAINT} line
	 * whose live form {@code capability.ConstraintStaleness} reports as stale offers Re-apply alongside
	 * Remove/Measure again; the SAME line when current still offers exactly {@link #REMOVE_AND_MEASURE},
	 * unchanged. Every other status/nature combination behaves exactly like {@link #forStatus(String, String)}
	 * - {@code pStale} is only ever consulted for the one case it can change anything. Kept as a THIRD
	 * overload rather than a new required parameter on the existing one: every call site that does not (yet)
	 * know a line's staleness - the select-cell/decidable checks, the multi-select preview - keeps calling the
	 * two-argument overload unchanged, exactly the task brief's own "keep the diff minimal and local to the
	 * capability line's action cell" instruction; only {@code ReviewPage}'s own row-rendering call site (where
	 * the Re-apply button is actually drawn) needs this one.
	 */
	public static Actions forStatus(final String pStatus, final String pNature, final boolean pStale)
	{
		if (pStale && ReviewLine.APPLIED.equals(pStatus) && ReviewLine.SCRIPT_CONSTRAINT.equals(pNature))
		{
			return DecisionActions.REMOVE_MEASURE_AND_REAPPLY;
		}
		return DecisionActions.forStatus(pStatus, pNature);
	}
}
