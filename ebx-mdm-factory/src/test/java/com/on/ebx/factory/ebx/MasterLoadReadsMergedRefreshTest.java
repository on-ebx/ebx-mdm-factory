package com.on.ebx.factory.ebx;

import java.util.HashSet;
import java.util.Set;

/**
 * JUnit-free: run the main, a failure throws. The pure part of the merged-keys refresh of {@link MasterLoadReads} (lot V3-M2 fix, 2026-10-04): when
 * {@link MasterLoadReads#knownNotMerged} answers from the pre-built keys, and how a re-read restarts the counter it tests. The re-read itself (one
 * metadataset request) needs a running instance and is not covered here.
 */
public final class MasterLoadReadsMergedRefreshTest
{
	public static void main(final String[] pArgs)
	{
		MasterLoadReadsMergedRefreshTest.aWriteTurnsTheLiveCheckOnUntilTheKeysAreReadAgain();
		MasterLoadReadsMergedRefreshTest.aFailedReReadKeepsTheLiveCheck();
		MasterLoadReadsMergedRefreshTest.aLaterWriteCountsAgainAndWritesKeepTheirTotal();
		System.out.println("MasterLoadReadsMergedRefreshTest OK");
	}

	private static MasterLoadReads reads(final String... pMerged)
	{
		final MasterLoadReads reads = MasterLoadReads.lookup("Personne", "test");
		final com.on.ebx.factory.index.KeyValueIndex merged = new com.on.ebx.factory.index.KeyValueIndex(pMerged.length);
		for (final String key : pMerged)
		{
			merged.add(key, "");
		}
		reads.applyMerged(merged);
		return reads;
	}

	private static void aWriteTurnsTheLiveCheckOnUntilTheKeysAreReadAgain()
	{
		final MasterLoadReads reads = MasterLoadReadsMergedRefreshTest.reads("7");
		MasterLoadReadsMergedRefreshTest.check("nothing written: 1 is known not merged", reads.knownNotMerged("1"));
		MasterLoadReadsMergedRefreshTest.check("nothing written: 7 is merged, so not 'known not merged'", !reads.knownNotMerged("7"));
		reads.recordWritten("S|1", "1");
		MasterLoadReadsMergedRefreshTest.check("after a write: live check again", !reads.knownNotMerged("1"));
		final Set<String> fresh = new HashSet<String>();
		fresh.add("7");
		fresh.add("2"); // the add-on merged record 2 as a side effect of the write
		reads.applyRefreshedMergedKeys(fresh, 12L);
		MasterLoadReadsMergedRefreshTest.check("after the re-read: 1 known not merged again", reads.knownNotMerged("1"));
		MasterLoadReadsMergedRefreshTest.check("after the re-read: the record merged by the write is seen", !reads.knownNotMerged("2"));
		MasterLoadReadsMergedRefreshTest.check("refresh recorded", reads.mergedRefreshed && reads.mergedRefreshMs == 12L && reads.mergedRefreshRows == 2L);
	}

	private static void aFailedReReadKeepsTheLiveCheck()
	{
		final MasterLoadReads reads = MasterLoadReadsMergedRefreshTest.reads();
		reads.recordWritten("S|1", "1");
		reads.applyRefreshedMergedKeys(null, 3L);
		MasterLoadReadsMergedRefreshTest.check("a null re-read changes nothing: live check", !reads.knownNotMerged("1"));
		MasterLoadReadsMergedRefreshTest.check("a null re-read is not reported", !reads.mergedRefreshed);
		// an empty set is a real answer ("nothing merged"), not a failure
		reads.applyRefreshedMergedKeys(new HashSet<String>(), 3L);
		MasterLoadReadsMergedRefreshTest.check("an empty re-read is a real answer", reads.knownNotMerged("1"));
	}

	private static void aLaterWriteCountsAgainAndWritesKeepTheirTotal()
	{
		final MasterLoadReads reads = MasterLoadReadsMergedRefreshTest.reads();
		reads.recordWritten("S|1", "1");
		reads.recordWritten("S|2", "2");
		reads.applyRefreshedMergedKeys(new HashSet<String>(), 1L);
		reads.recordWritten("S|3", "3"); // a pass 2 patch of a self-referencing entity
		MasterLoadReadsMergedRefreshTest.check("a write after the re-read: live check again", !reads.knownNotMerged("1"));
		MasterLoadReadsMergedRefreshTest.check("writes keeps counting for the step detail", reads.writes == 3L);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
