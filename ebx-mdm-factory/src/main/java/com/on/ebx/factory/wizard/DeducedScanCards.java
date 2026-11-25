package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UX lot C4 (2026-10-14): which progress cards of the deduced scan the wizard shows. Seen live on project UxCrmSf3: each run of the same application
 * stacked its own card (three "DEMOCRM ... 2 tables confirmed. found" below the application card). Two rules: only the LATEST run of a source is
 * shown (the older finished runs of that source collapse into it), and once that run is finished the card is merged into the application's own
 * card (the stage chips move to a fold "How the tables were found"). Pure, tested in {@code DeducedScanCardsTest}.
 */
public final class DeducedScanCards
{
	private DeducedScanCards()
	{
	}

	/**
	 * The positions to keep in a list of runs given oldest first: the last run of each source, in the order of those last runs.
	 *
	 * @param pSourceIds the source of each run, oldest first
	 */
	public static List<Integer> latestIndexes(final List<String> pSourceIds)
	{
		final Map<String, Integer> last = new HashMap<String, Integer>();
		for (int i = 0; i < pSourceIds.size(); i++)
		{
			last.put(pSourceIds.get(i), Integer.valueOf(i));
		}
		final List<Integer> out = new ArrayList<Integer>();
		for (int i = 0; i < pSourceIds.size(); i++)
		{
			if (last.get(pSourceIds.get(i)).intValue() == i)
			{
				out.add(Integer.valueOf(i));
			}
		}
		return out;
	}

	/**
	 * Whether a run's progress card is merged into the application's card: the run is finished with its tables found ({@code DONE}) and the source
	 * already has an application card. A run that failed or was skipped keeps its own card (it carries the reason and "Remove"); a running one too.
	 */
	public static boolean mergedIntoApplicationCard(final String pEntryState, final boolean pHasApplicationCard)
	{
		return pHasApplicationCard && DeducedScanProgress.DONE.equals(pEntryState);
	}
}
