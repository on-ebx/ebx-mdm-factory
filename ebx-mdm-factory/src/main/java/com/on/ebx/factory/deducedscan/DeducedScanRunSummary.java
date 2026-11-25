package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.List;

/**
 * Fix lot (2026-09-27, round 5, task item 2): the deduced-scan job's own end message ({@code
 * ebx.DeducedScanRun}'s {@code outcome.summary} - also the {@code PipelineStep} row's message, and what a
 * card falls back to before {@link com.on.ebx.factory.wizard.DeducedScanCardSummary} has fresher counts to
 * read) used to always read "N table(s) deduced, waiting for confirmation." - even a RESCAN that reconciled
 * every asset back to a person's earlier {@code CONFIRMED}/{@code LEFT_OUT} decision ({@code
 * AssetReconciliation}) still said "waiting for confirmation", which was simply false: nothing needed a
 * person's attention. This builds the message from what the run actually did to each table, not one frozen
 * phrase for every outcome.
 * <p>
 * Pure decision logic only - {@code ebx.DeducedScanRun} tallies one {@link Counts} bucket per table as it
 * writes it ({@code ebx.DeducedScanCatalogWriter.Result}, which now says whether a write created a new
 * asset, reopened an existing one whose columns changed, or left an unchanged one's decision exactly as it
 * was) and calls {@link #build} once at the end of the run.
 */
public final class DeducedScanRunSummary
{
	private DeducedScanRunSummary()
	{
	}

	/**
	 * Per-table tallies of one run - the four mutually exclusive outcomes {@code
	 * DeducedScanCatalogWriter#write} can produce for a single table, see each field's own Javadoc.
	 */
	public static final class Counts
	{
		/** Brand new {@code Asset} row - no earlier deduced scan had ever written a matching recipe. */
		public int created;
		/** An existing asset whose columns did not change and was already {@code CONFIRMED} or {@code LEFT_OUT} - its decision survives untouched, nothing for a person to do. */
		public int keptDecided;
		/** An existing asset whose columns did not change and was still {@code TO_CONFIRM} - unchanged, but a person still has not decided it (same as {@link #created} from a "needs a decision" point of view, just not a NEW table). */
		public int keptToConfirm;
		/** An existing asset whose columns changed - reset to {@code TO_CONFIRM} regardless of what it was before (see {@link AssetReconciliation}), and a person must look at it again. */
		public int reopened;

		public int total()
		{
			return this.created + this.keptDecided + this.keptToConfirm + this.reopened;
		}
	}

	/**
	 * @param pCounts this run's per-table tallies (see {@link Counts}) - {@link Counts#total()} is assumed
	 *        &gt; 0 (the caller already ends the run differently, before ever calling this, when nothing at
	 *        all was written)
	 * @param pProblems the same free-text problem list {@code DeducedScanRun} already appends to every
	 *        outcome summary (sampling/naming failures for the tables it could not write) - appended
	 *        verbatim, never parsed; may be null or empty
	 */
	public static String build(final Counts pCounts, final String pProblems)
	{
		final int toConfirm = pCounts.created + pCounts.keptToConfirm;
		final StringBuilder out = new StringBuilder();
		// the plain, single-outcome cases read exactly like the run's own pre-fix wording (a fresh, first-ever
		// scan of a connection never mixes outcomes) or its natural opposite (a rescan that changed nothing at
		// all); the general, multi-part sentence below only kicks in once a run actually mixes outcomes, which
		// only a rescan of a connection whose shape partially changed ever does.
		if (pCounts.reopened == 0 && pCounts.keptDecided == 0 && toConfirm > 0)
		{
			out.append(toConfirm).append(" table(s) deduced, waiting for confirmation.");
		}
		else if (pCounts.reopened == 0 && toConfirm == 0 && pCounts.keptDecided > 0)
		{
			out.append(pCounts.keptDecided).append(" table(s) up to date, decisions kept.");
		}
		else if (pCounts.created == 0 && pCounts.keptToConfirm == 0 && pCounts.keptDecided == 0 && pCounts.reopened > 0)
		{
			out.append(pCounts.reopened).append(" table(s) changed: to confirm again (columns changed).");
		}
		else
		{
			final List<String> parts = new ArrayList<String>();
			if (pCounts.created > 0)
			{
				parts.add(pCounts.created + " new table(s) to confirm");
			}
			if (pCounts.keptToConfirm > 0)
			{
				parts.add(pCounts.keptToConfirm + " still waiting for confirmation");
			}
			if (pCounts.reopened > 0)
			{
				parts.add(pCounts.reopened + " changed: to confirm again (columns changed)");
			}
			if (pCounts.keptDecided > 0)
			{
				parts.add(pCounts.keptDecided + " kept");
			}
			for (int i = 0; i < parts.size(); i++)
			{
				if (i > 0)
				{
					out.append(", ");
				}
				out.append(parts.get(i));
			}
			out.append('.');
		}
		if (pProblems != null && pProblems.length() > 0)
		{
			out.append(' ').append(pProblems);
		}
		return out.toString();
	}
}
