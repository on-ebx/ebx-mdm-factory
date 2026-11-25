package com.on.ebx.factory.mappingmatrix;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * UX lot U6 (docs/UX-MATRIX-QUEUE.md): the ONE number of the Mapping matrix. Measured on project UxCrmSf the header said "4 points need your attention"
 * while the queue at the bottom said "20 items need you", then "16" with the header unchanged, and finally "1 point" next to "Nothing flagged is left":
 * the header counted every flagged field of the project whatever its decision (a stale scope), the footer counted the still-to-decide list.
 * <p>
 * Both now read ONE {@link Tally}, computed on every render from the SAME two lists the queue panel is drawn from
 * ({@link MatrixCompute#stillToDecide} and {@link MatrixCompute#optionalServices}), so the header line, the step boxes, the footer and the panel cannot
 * disagree. Pure, no EBX class, unit-tested ({@code MatrixCounterTest}).
 * <ul>
 * <li>{@link Tally#needYou}: items the "Accept the N proposals" button does NOT take and that are not optional - what a person must decide.</li>
 * <li>{@link Tally#withButton}: items the button takes (listed in the panel behind "Also show...").</li>
 * <li>{@link Tally#optionalSuggestions}: look-up suggestions of external services, grouped per field in the panel; never in {@link Tally#needYou}, never a
 * reason for "Load the staging" to stay closed.</li>
 * <li>{@link Tally#flaggedFields}: distinct fields still waiting AND flagged "needs your eye" - the "1 - Decide the flagged points" box counts these only.</li>
 * </ul>
 */
public final class MatrixCounter
{
	private MatrixCounter()
	{
	}

	/** What the first line of the screen says. */
	public enum Headline
	{
		/** {@link Tally#needYou} items need a person. */
		NEED_YOU,
		/** Nothing needs a person, but {@link Tally#withButton} items wait for the bulk button. */
		ONLY_BUTTON,
		/** Nothing left to decide (optional suggestions aside). */
		NOTHING_LEFT
	}

	public static final class Tally
	{
		public final int needYou;
		public final int withButton;
		public final int optionalSuggestions;
		public final int optionalFields;
		public final int flaggedFields;
		/**
		 * Staging-screens lot (2026-10-17): the N of the button "Accept the N proposals" - the size of the bulk plan the button runs ({@link MatrixCompute#planProjectBulk}).
		 * It was a second count: the footer button drew N from the plan, the header from the item list ({@link #withButton}), and step 4 of the wizard from its own
		 * snapshot tally ("the other 96" next to "Accept the 93"). Every screen that says how many proposals one click takes reads THIS field.
		 */
		public final int acceptAll;

		Tally(final int pNeedYou, final int pWithButton, final int pOptionalSuggestions, final int pOptionalFields, final int pFlaggedFields, final int pAcceptAll)
		{
			this.needYou = pNeedYou;
			this.withButton = pWithButton;
			this.optionalSuggestions = pOptionalSuggestions;
			this.optionalFields = pOptionalFields;
			this.flaggedFields = pFlaggedFields;
			this.acceptAll = pAcceptAll;
		}

		/** Everything that is not optional and still open: what keeps "Load the staging" closed. */
		public int stillToDecide()
		{
			return this.needYou + this.withButton;
		}

		/** True when nothing but optional suggestions remains - the step 2 box of the header reads "done". */
		public boolean allDone()
		{
			return this.stillToDecide() == 0;
		}

		public Headline headline()
		{
			return MatrixCounter.headlineOf(this.needYou, this.withButton);
		}
	}

	/**
	 * Staging polish (2026-10-17, docs/UX-STAGING-POLISH.md sec 3): THE rule that turns the two figures of a {@link Tally} into the sentence kind. The matrix header
	 * ({@link Tally#headline}) and step 4 of the wizard ({@code WizardRules#step4MatrixNote}, fed with {@link Tally#needYou} and {@link Tally#acceptAll}) both call it,
	 * so the two screens cannot pick different sentences for the same project. {@code pItemsWithTheButton}: {@link Tally#withButton} or {@link Tally#acceptAll}
	 * (they are empty exactly together, {@code MatrixCounterTest}).
	 */
	public static Headline headlineOf(final int pNeedYou, final int pItemsWithTheButton)
	{
		if (pNeedYou > 0)
		{
			return Headline.NEED_YOU;
		}
		return pItemsWithTheButton > 0 ? Headline.ONLY_BUTTON : Headline.NOTHING_LEFT;
	}

	/** The tally of one render when the bulk plan is not at hand: {@link Tally#acceptAll} is then the number of items the button takes ({@link Tally#withButton}). */
	public static Tally of(final List<MatrixCompute.StillItem> pBlocking, final List<MatrixCompute.ServiceGroup> pGroups)
	{
		return MatrixCounter.of(pBlocking, pGroups, -1);
	}

	/** The tally of one render: {@code pBlocking} is {@link MatrixCompute#stillToDecide}, {@code pGroups} {@link MatrixCompute#optionalServices}, {@code pAcceptAll} the size of the bulk plan (negative = use the items). */
	public static Tally of(final List<MatrixCompute.StillItem> pBlocking, final List<MatrixCompute.ServiceGroup> pGroups, final int pAcceptAll)
	{
		int needYou = 0;
		int withButton = 0;
		final Set<String> flagged = new HashSet<String>();
		for (final MatrixCompute.StillItem item : pBlocking)
		{
			if (item.takenByTheButton())
			{
				withButton++;
			}
			else
			{
				needYou++;
			}
			if (MatrixCompute.REASON_ATTENTION.equals(item.reason) && !item.attributeId.isEmpty())
			{
				flagged.add(item.entityId + "|" + item.attributeId);
			}
		}
		return new Tally(needYou, withButton, MatrixCompute.optionalSuggestionCount(pGroups), pGroups.size(), flagged.size(), pAcceptAll < 0 ? withButton : pAcceptAll);
	}

	/**
	 * UX lot C3 (docs/UX-C3-STEPS-1-4.md): the figure of the matrix header, for a screen that does not draw the matrix - step 4 of the wizard reads "N points
	 * need your attention" from it, so the wizard's button and the matrix header are the SAME number by construction (same threshold as the matrix' default).
	 */
	public static int needYou(final MatrixModel.Project pProject)
	{
		return MatrixCounter.of(pProject, MatrixCompute.DEFAULT_BULK_THRESHOLD).needYou;
	}

	/** Convenience for callers holding the project only (the service's own checks, the wizard's step 4, the tests): the same tally the matrix draws, bulk plan included. */
	public static Tally of(final MatrixModel.Project pProject, final int pBulkThreshold)
	{
		return MatrixCounter.of(MatrixCompute.stillToDecide(pProject, pBulkThreshold), MatrixCompute.optionalServices(pProject),
			MatrixCompute.planProjectBulk(pProject, pBulkThreshold).toDecide.size());
	}

	/** {@link #of(MatrixModel.Project, int)} at the matrix's default threshold - what step 4 of the wizard quotes. */
	public static Tally of(final MatrixModel.Project pProject)
	{
		return MatrixCounter.of(pProject, MatrixCompute.DEFAULT_BULK_THRESHOLD);
	}
}
