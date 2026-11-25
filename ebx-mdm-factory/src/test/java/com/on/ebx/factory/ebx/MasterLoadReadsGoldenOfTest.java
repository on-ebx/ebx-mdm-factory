package com.on.ebx.factory.ebx;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.index.KeyValueIndex;
import com.on.ebx.factory.master.SurvivingRecord;

/**
 * JUnit-free: run the main, a failure throws. {@link MasterLoadReads#goldenOfMerged} (lot V3-M3, 2026-10-04): the golden of a merged parent answered from the merged
 * read, under the staleness rule of {@code knownNotMerged}, and followed hop by hop by {@link SurvivingRecord} the way {@code MasterLoader.ParentKeys} does.
 */
public final class MasterLoadReadsGoldenOfTest
{
	public static void main(final String[] pArgs)
	{
		MasterLoadReadsGoldenOfTest.answersFromTheMergedRead();
		MasterLoadReadsGoldenOfTest.aWriteTurnsTheIndexOffUntilTheReRead();
		MasterLoadReadsGoldenOfTest.keysOnlyReReadDropsTheGoldens();
		MasterLoadReadsGoldenOfTest.theChainIsFollowedThroughTheIndexAndLiveIsAskedOnlyWhenItCannotAnswer();
		System.out.println("MasterLoadReadsGoldenOfTest OK");
	}

	private static Map<String, String> merged(final String... pKeyGolden)
	{
		final Map<String, String> merged = new HashMap<String, String>();
		for (int i = 0; i < pKeyGolden.length; i += 2)
		{
			merged.put(pKeyGolden[i], pKeyGolden[i + 1]);
		}
		return merged;
	}

	private static KeyValueIndex index(final Map<String, String> pMerged)
	{
		final KeyValueIndex index = new KeyValueIndex(pMerged.size());
		for (final Map.Entry<String, String> entry : pMerged.entrySet())
		{
			index.add(entry.getKey(), entry.getValue());
		}
		return index;
	}

	private static MasterLoadReads reads(final Map<String, String> pMerged)
	{
		final MasterLoadReads reads = MasterLoadReads.lookup("Personne", "test");
		reads.applyMerged(MasterLoadReadsGoldenOfTest.index(pMerged));
		return reads;
	}

	private static void answersFromTheMergedRead()
	{
		final MasterLoadReads reads = MasterLoadReadsGoldenOfTest.reads(MasterLoadReadsGoldenOfTest.merged("12", "40", "7", ""));
		MasterLoadReadsGoldenOfTest.check("a merged record: its golden", "40".equals(reads.goldenOfMerged("12")));
		MasterLoadReadsGoldenOfTest.check("a golden (not merged): the index says nothing about a golden", reads.goldenOfMerged("40") == null);
		MasterLoadReadsGoldenOfTest.check("a golden is known not merged", reads.knownNotMerged("40"));
		MasterLoadReadsGoldenOfTest.check("a merged record with an empty goldenId: ask the add-on", reads.goldenOfMerged("7") == null && !reads.knownNotMerged("7"));
		MasterLoadReadsGoldenOfTest.check("LOOKUP mode: unknown", MasterLoadReads.lookup("X", "r").goldenOfMerged("12") == null);
	}

	private static void aWriteTurnsTheIndexOffUntilTheReRead()
	{
		final MasterLoadReads reads = MasterLoadReadsGoldenOfTest.reads(MasterLoadReadsGoldenOfTest.merged("12", "40"));
		reads.recordWritten("S|1", "1");
		MasterLoadReadsGoldenOfTest.check("after a write: live answer only", reads.goldenOfMerged("12") == null);
		// the re-read goes through applyMerged (private) in production; the keys-only entry point shares the counter reset
		final Map<String, String> fresh = MasterLoadReadsGoldenOfTest.merged("12", "41");
		reads.applyMerged(MasterLoadReadsGoldenOfTest.index(fresh));
		reads.applyRefreshedMergedKeys(fresh.keySet(), 5L);
		MasterLoadReadsGoldenOfTest.check("keys-only re-read: the counter restarts, no golden to answer from", reads.knownNotMerged("1") && reads.goldenOfMerged("12") == null);
	}

	private static void keysOnlyReReadDropsTheGoldens()
	{
		final MasterLoadReads reads = MasterLoadReadsGoldenOfTest.reads(MasterLoadReadsGoldenOfTest.merged("12", "40"));
		final Set<String> keys = new java.util.HashSet<String>();
		keys.add("12");
		reads.applyRefreshedMergedKeys(keys, 1L);
		MasterLoadReadsGoldenOfTest.check("goldens dropped with the keys-only set", reads.mergedRead() && reads.isMerged("12") && reads.goldenOfMerged("12") == null);
	}

	/** What {@code ParentKeys.resolve} does with it: index first, live only when the index cannot answer. */
	private static void theChainIsFollowedThroughTheIndexAndLiveIsAskedOnlyWhenItCannotAnswer()
	{
		final MasterLoadReads reads = MasterLoadReadsGoldenOfTest.reads(MasterLoadReadsGoldenOfTest.merged("12", "40", "40", "55", "9", ""));
		final int[] byIndex = new int[1];
		final int[] live = new int[1];
		final SurvivingRecord.GoldenOf goldenOf = new SurvivingRecord.GoldenOf()
		{
			@Override
			public String goldenOf(final String pKey)
			{
				if (reads.knownNotMerged(pKey))
				{
					byIndex[0]++;
					return null;
				}
				final String golden = reads.goldenOfMerged(pKey);
				if (golden != null)
				{
					byIndex[0]++;
					return golden;
				}
				live[0]++;
				return "live-" + pKey;
			}
		};
		MasterLoadReadsGoldenOfTest.check("12 -> 40 -> 55, 55 known not merged", "55".equals(SurvivingRecord.follow("12", goldenOf)));
		MasterLoadReadsGoldenOfTest.check("three hops, all by the index", byIndex[0] == 3 && live[0] == 0);
		SurvivingRecord.follow("9", goldenOf);
		MasterLoadReadsGoldenOfTest.check("an empty goldenId goes live", live[0] >= 1);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
