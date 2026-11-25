package com.on.ebx.factory.capability;

/**
 * What a FAILED apply of a {@code SCRIPT_DERIVATION} must undo, and what the row then says - pure (lot T2, 2026-10-04), the decision {@code
 * ebx.DerivationApplier} (staging) and {@code ebx.DerivationMasterTriggers} (master) both take. EBX-free so that it is unit-tested.
 * <p>
 * <b>The defect this closes (found during lot P1).</b> A failed RE-apply - the suggestion was already APPLIED and its triggers already published
 * before this round - used to be rolled back like a first apply: both trigger declarations were taken off the model DRAFT and the row went back to
 * ACCEPTED. The published triggers kept working, but the next {@code publish_data_model} of that model, for any reason at all, would have published
 * the draft without them: the derivation would have vanished silently. The rule is now:
 * <ul>
 * <li><b>first apply</b> (nothing was applied before): take both declarations off the draft by name, republish only when a publish had happened this
 * round, the row goes back to ACCEPTED (what it always did);</li>
 * <li><b>re-apply</b>: the declarations stay (they are what is published), the row stays APPLIED, the PREVIOUS bodies are written back to the scripting
 * draft (a half-authored new body must not be published by someone else's next publish), and the model is republished only when this round had
 * already published - then the previous bodies are what must become live again. When the previous bodies cannot be told apart (an unreadable stored
 * {@code scriptBody}) nothing is restored and a republish would change nothing, so none is made; the message says so.</li>
 * </ul>
 */
public final class DerivationApplyFailure
{
	private DerivationApplyFailure()
	{
	}

	/** What to do after a failed apply; see the class Javadoc. */
	public static final class Plan
	{
		/** Take both trigger declarations off the draft, by name (first apply only). */
		public final boolean removeDeclarations;
		/** Write the previous bodies back to the scripting draft (re-apply, when they are known). */
		public final boolean restoreBodies;
		/** Republish the model (only when this round had published). */
		public final boolean republish;
		/** The row keeps its APPLIED status (re-apply); otherwise it goes back to ACCEPTED. */
		public final boolean statusStaysApplied;

		Plan(final boolean pRemoveDeclarations, final boolean pRestoreBodies, final boolean pRepublish, final boolean pStatusStaysApplied)
		{
			this.removeDeclarations = pRemoveDeclarations;
			this.restoreBodies = pRestoreBodies;
			this.republish = pRepublish;
			this.statusStaysApplied = pStatusStaysApplied;
		}
	}

	/**
	 * @param pPreviouslyApplied the triggers were already declared and published before this round (the row was APPLIED; for the master: the triggers were already on the model)
	 * @param pPublishedThisRound a {@code publish_data_model} of this apply had already succeeded when it failed
	 * @param pPreviousBodiesKnown the two previous bodies could be read back ({@link DerivationBodyBuilder#splitStoredBodies}, or read from the scripting server)
	 */
	public static Plan decide(final boolean pPreviouslyApplied, final boolean pPublishedThisRound, final boolean pPreviousBodiesKnown)
	{
		if (!pPreviouslyApplied)
		{
			return new Plan(true, false, pPublishedThisRound, false);
		}
		return new Plan(false, pPreviousBodiesKnown, pPublishedThisRound && pPreviousBodiesKnown, true);
	}

	/**
	 * The {@code applyMessage} a failure leaves: the plain failure text for a row that goes back to ACCEPTED, the failure text followed by the re-apply
	 * marker ({@link DerivationParameters#failedReapplyMessage}) for a row that stays APPLIED. Always within {@link DerivationParameters#APPLY_MESSAGE_MAX}.
	 */
	public static String message(final boolean pStatusStaysApplied, final String pFailure)
	{
		if (pStatusStaysApplied)
		{
			return DerivationParameters.failedReapplyMessage(pFailure);
		}
		final String text = pFailure == null ? "" : pFailure;
		return text.length() > DerivationParameters.APPLY_MESSAGE_MAX ? text.substring(0, DerivationParameters.APPLY_MESSAGE_MAX) : text;
	}

	/**
	 * What to store when {@code prepare} refuses before the step begins (a parameter still to be provided, a vanished attribute): {@code null} when the
	 * row must not be touched (it is not an ACCEPTED or APPLIED derivation of this project - the refusal then concerns something else), else {@link
	 * #message} with the row's own status deciding whether the re-apply marker is kept (an APPLIED row's "apply again" must survive the refusal).
	 */
	public static String refusalMessage(final String pStatus, final String pRefusal)
	{
		if ("APPLIED".equals(pStatus))
		{
			return DerivationApplyFailure.message(true, pRefusal);
		}
		if ("ACCEPTED".equals(pStatus))
		{
			return DerivationApplyFailure.message(false, pRefusal);
		}
		return null;
	}
}
