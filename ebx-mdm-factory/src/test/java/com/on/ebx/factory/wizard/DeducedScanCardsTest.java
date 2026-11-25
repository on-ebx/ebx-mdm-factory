package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free: run the main, a failure throws. Exercises {@link DeducedScanCards} (UX lot C4, 2026-10-14): one progress card per source, merged into the application's card once finished. */
public final class DeducedScanCardsTest
{
	public static void main(final String[] pArgs)
	{
		// project UxCrmSf3: the same source ("93") scanned three times, another source ("94") once in between
		final List<Integer> kept = DeducedScanCards.latestIndexes(Arrays.asList("93", "94", "93", "93"));
		DeducedScanCardsTest.check("the latest run of each source, in the order of those runs: " + kept, kept.equals(Arrays.asList(Integer.valueOf(1), Integer.valueOf(3))));
		DeducedScanCardsTest.check("a single run is kept", DeducedScanCards.latestIndexes(Arrays.asList("93")).equals(Arrays.asList(Integer.valueOf(0))));
		DeducedScanCardsTest.check("no run, no card", DeducedScanCards.latestIndexes(new ArrayList<String>()).isEmpty());
		DeducedScanCardsTest.check("three runs of one source collapse to one", DeducedScanCards.latestIndexes(Arrays.asList("93", "93", "93")).size() == 1);

		DeducedScanCardsTest.check("finished with tables found, application card exists: merged", DeducedScanCards.mergedIntoApplicationCard(DeducedScanProgress.DONE, true));
		DeducedScanCardsTest.check("finished but no application card yet: keeps its own card", !DeducedScanCards.mergedIntoApplicationCard(DeducedScanProgress.DONE, false));
		for (final String state : new String[] { DeducedScanProgress.WAITING, DeducedScanProgress.RUNNING, DeducedScanProgress.FAILED, DeducedScanProgress.SKIPPED })
		{
			DeducedScanCardsTest.check(state + ": the run keeps its own card (progress, reason, Remove)", !DeducedScanCards.mergedIntoApplicationCard(state, true));
		}
		System.out.println("DeducedScanCardsTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
