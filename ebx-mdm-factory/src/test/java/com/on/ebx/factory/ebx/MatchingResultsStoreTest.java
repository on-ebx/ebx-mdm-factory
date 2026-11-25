package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Covers the pure parts of {@link MatchingResultsStore} (wave 2, lot V2-A): the
 * compared-field rule shared by the stored pairs and the bulk merge, and the per-project epoch the summary cache keys on.
 */
public final class MatchingResultsStoreTest
{
	public static void main(final String[] pArgs)
	{
		MatchingResultsStoreTest.comparedFieldRule();
		MatchingResultsStoreTest.epochStartsAtZeroAndOnlyGrowsPerProject();
		System.out.println("MatchingResultsStoreTest OK");
	}

	private static void comparedFieldRule()
	{
		// the rule the wizard applied to its own snapshot: not the technical identity, not the source's business key, not a reference
		MatchingResultsStoreTest.check("an ordinary attribute is compared", MatchingResultsStore.isCompared(false, false, false));
		MatchingResultsStoreTest.check("the technical identity is not", !MatchingResultsStore.isCompared(true, false, false));
		MatchingResultsStoreTest.check("the source's business key is not", !MatchingResultsStore.isCompared(false, true, false));
		MatchingResultsStoreTest.check("a reference to another entity is not", !MatchingResultsStore.isCompared(false, false, true));
		MatchingResultsStoreTest.check("any combination of those is not", !MatchingResultsStore.isCompared(true, true, true));
	}

	private static void epochStartsAtZeroAndOnlyGrowsPerProject()
	{
		final String a = "epoch-test-project-A";
		final String b = "epoch-test-project-B";
		MatchingResultsStoreTest.check("unknown project reads zero", MatchingResultsStore.epoch(a) == 0L);
		MatchingResultsStore.bump(a);
		MatchingResultsStore.bump(a);
		MatchingResultsStoreTest.check("two bumps", MatchingResultsStore.epoch(a) == 2L);
		MatchingResultsStoreTest.check("another project is untouched", MatchingResultsStore.epoch(b) == 0L);
		MatchingResultsStore.bump(b);
		MatchingResultsStoreTest.check("each project has its own counter", MatchingResultsStore.epoch(a) == 2L && MatchingResultsStore.epoch(b) == 1L);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
