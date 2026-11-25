package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.List;

/**
 * Fix lot (2026-09-26, defect 14, project DeducedTest2): the DEMOCRM deduced-scan pipeline card kept reading
 * "2 table(s) deduced, waiting for confirmation." even after both tables were confirmed (one of them already
 * loaded into the landing) - the run's own {@code entry.summary} is a snapshot frozen the moment the run
 * finished, never updated by a later Confirm/Leave-out click. This decides WHAT to render instead, from the
 * CURRENT decision counts of the application's deduced assets ({@code ebx.DeducedScanCatalog#decisionCounts})
 * - pure ordering/filtering only (confirmed, then left out, then waiting, each shown only when its count is
 * more than zero); {@code wizard.WizardPage} turns the ordered parts into localized, pluralized text
 * ("{0} table(s) confirmed", "{0} confirmed, {1} left out", ...).
 */
public final class DeducedScanCardSummary
{
	private DeducedScanCardSummary()
	{
	}

	/** One kind of decision. */
	public static final String CONFIRMED = "CONFIRMED";
	public static final String LEFT_OUT = "LEFT_OUT";
	public static final String WAITING = "WAITING";

	/** One part of the composed end line - a count of tables in one of the three decision states, never zero (see {@link #parts}). */
	public static final class Part
	{
		public final String kind;
		public final int count;

		Part(final String pKind, final int pCount)
		{
			this.kind = pKind;
			this.count = pCount;
		}
	}

	/**
	 * The ordered, non-zero parts to render - empty when every count is zero, which means "no deduced-scan
	 * decision data at all for this application" and the caller must fall back to the run's own frozen {@code
	 * entry.summary} text instead (this class never invents a message for that case).
	 */
	public static List<Part> parts(final int pConfirmed, final int pLeftOut, final int pToConfirm)
	{
		final List<Part> out = new ArrayList<Part>();
		if (pConfirmed > 0)
		{
			out.add(new Part(DeducedScanCardSummary.CONFIRMED, pConfirmed));
		}
		if (pLeftOut > 0)
		{
			out.add(new Part(DeducedScanCardSummary.LEFT_OUT, pLeftOut));
		}
		if (pToConfirm > 0)
		{
			out.add(new Part(DeducedScanCardSummary.WAITING, pToConfirm));
		}
		return out;
	}
}
