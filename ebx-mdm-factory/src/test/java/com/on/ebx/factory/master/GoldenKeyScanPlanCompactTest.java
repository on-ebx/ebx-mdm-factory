package com.on.ebx.factory.master;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.on.ebx.factory.index.KeyValueIndex;

/**
 * JUnit-free: run the main, a failure throws. Lot IDX (2026-10-12): {@link GoldenKeyScanPlan} over the compact metadata index ({@link KeyValueIndex}, about 30 bytes a record)
 * decides every {@code Xref} row exactly as over the {@code HashMap} it replaces - all five states, merge chains (two hops, a cycle), a record with no metadata, a merged
 * record with no golden id, numeric and non-numeric keys - on 30,000 random records.
 */
public final class GoldenKeyScanPlanCompactTest
{
	public static void main(final String[] pArgs)
	{
		GoldenKeyScanPlanCompactTest.theSentinelsSurviveTheIndex();
		GoldenKeyScanPlanCompactTest.sameDecisionsAsTheMapOnRandomData();
		System.out.println("GoldenKeyScanPlanCompactTest OK");
	}

	private static void theSentinelsSurviveTheIndex()
	{
		final KeyValueIndex index = new KeyValueIndex(10L);
		index.add("1", GoldenKeyScanPlan.encode(1, null));
		index.add("2", GoldenKeyScanPlan.encode(2, "  "));
		index.add("3", GoldenKeyScanPlan.encode(2, "40"));
		index.add("4", GoldenKeyScanPlan.encode(2, "G|40"));
		check("PRESENT", GoldenKeyScanPlan.PRESENT.equals(index.get("1")));
		check("MERGED_NO_GOLDEN", GoldenKeyScanPlan.MERGED_NO_GOLDEN.equals(index.get("2")));
		check("numeric golden", "40".equals(index.get("3")));
		check("text golden", "G|40".equals(index.get("4")));
		final GoldenKeyScanPlan plan = new GoldenKeyScanPlan(index);
		final GoldenKeyRefreshDecision.Decision decision = plan.accept("x|3", "3", null);
		check("a merged record points at its golden", decision.action == GoldenKeyRefreshDecision.Action.SET && "40".equals(decision.newGoldenKey));
	}

	private static void sameDecisionsAsTheMapOnRandomData()
	{
		final Random random = new Random(11L);
		final int n = 30000;
		final Map<String, String> map = new HashMap<String, String>();
		final KeyValueIndex index = new KeyValueIndex(n);
		final String[] keys = new String[n];
		for (int i = 0; i < n; i++)
		{
			keys[i] = i % 10 == 0 ? "K|" + i : Integer.toString(100000 + i);
		}
		for (int i = 0; i < n; i++)
		{
			final int kind = random.nextInt(10);
			if (kind == 0)
			{
				continue; // no metadata at all
			}
			final int state = kind <= 3 ? 1 : kind == 4 ? 0 : kind == 5 ? 3 : kind == 6 ? 4 : 2;
			// a merged record's golden: another record (chains and cycles happen), a blank, or a key that is in no index
			final int g = random.nextInt(12);
			final String golden = g == 0 ? "" : g == 1 ? "missing-" + i : keys[random.nextInt(n)];
			final String encoded = GoldenKeyScanPlan.encode(state, golden);
			map.put(keys[i], encoded);
			index.add(keys[i], encoded);
		}
		final GoldenKeyScanPlan viaMap = new GoldenKeyScanPlan(map);
		final GoldenKeyScanPlan viaIndex = new GoldenKeyScanPlan(index);
		for (int i = 0; i < n; i++)
		{
			final String current = random.nextInt(3) == 0 ? null : random.nextInt(2) == 0 ? keys[random.nextInt(n)] : keys[i];
			final GoldenKeyRefreshDecision.Decision a = viaMap.accept("x|" + i, keys[i], current);
			final GoldenKeyRefreshDecision.Decision b = viaIndex.accept("x|" + i, keys[i], current);
			if (a.action != b.action || (a.newGoldenKey == null ? b.newGoldenKey != null : !a.newGoldenKey.equals(b.newGoldenKey)))
			{
				throw new AssertionError("record " + keys[i] + ": map " + a.action + "/" + a.newGoldenKey + ", index " + b.action + "/" + b.newGoldenKey);
			}
		}
		check("same counters", viaMap.set() == viaIndex.set() && viaMap.cleared() == viaIndex.cleared() && viaMap.unchanged() == viaIndex.unchanged());
		check("the run did something of every kind (" + viaMap.set() + "/" + viaMap.cleared() + "/" + viaMap.unchanged() + ")", viaMap.set() > 1000 && viaMap.cleared() > 100 && viaMap.unchanged() > 100);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
