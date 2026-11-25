package com.on.ebx.factory.transfer;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link LandingTableFanout} (volume lot 3, 2026-09-28, task 1). */
public final class LandingTableFanoutTest
{
	public static void main(final String[] pArgs)
	{
		LandingTableFanoutTest.keyJoinsTheThreeParts();
		LandingTableFanoutTest.aSingleReaderIsNeverShared();
		LandingTableFanoutTest.twoReadersOfTheSameTableAreShared();
		LandingTableFanoutTest.eachTableCountedIndependently();
		LandingTableFanoutTest.emptyListCountsNothing();
		LandingTableFanoutTest.anUnknownKeyIsNeverShared();
		System.out.println("LandingTableFanoutTest OK");
	}

	private static void keyJoinsTheThreeParts()
	{
		LandingTableFanoutTest.check("key joins dataspace|dataset|table", "BErp|Erp|SUPPLIER".equals(LandingTableFanout.key("BErp", "Erp", "SUPPLIER")));
	}

	private static void aSingleReaderIsNeverShared()
	{
		final Map<String, Integer> counts = LandingTableFanout.countReaders(Collections.singletonList("k1"));
		LandingTableFanoutTest.check("one reader: not shared", !LandingTableFanout.isShared(counts, "k1"));
	}

	private static void twoReadersOfTheSameTableAreShared()
	{
		final Map<String, Integer> counts = LandingTableFanout.countReaders(Arrays.asList("k1", "k1"));
		LandingTableFanoutTest.check("two readers of the same table: shared", LandingTableFanout.isShared(counts, "k1"));
	}

	private static void eachTableCountedIndependently()
	{
		final Map<String, Integer> counts = LandingTableFanout.countReaders(Arrays.asList("k1", "k1", "k2", "k3", "k3"));
		LandingTableFanoutTest.check("k1 shared (2 readers)", LandingTableFanout.isShared(counts, "k1"));
		LandingTableFanoutTest.check("k2 not shared (1 reader)", !LandingTableFanout.isShared(counts, "k2"));
		LandingTableFanoutTest.check("k3 shared (2 readers)", LandingTableFanout.isShared(counts, "k3"));
	}

	private static void emptyListCountsNothing()
	{
		final Map<String, Integer> counts = LandingTableFanout.countReaders(Collections.<String>emptyList());
		LandingTableFanoutTest.check("nothing counted: empty map", counts.isEmpty());
		LandingTableFanoutTest.check("an absent key is never shared", !LandingTableFanout.isShared(counts, "anything"));
	}

	private static void anUnknownKeyIsNeverShared()
	{
		final Map<String, Integer> counts = LandingTableFanout.countReaders(Arrays.asList("k1", "k1", "k1"));
		LandingTableFanoutTest.check("a key never seen defaults to not shared, the safe (cheaper) path", !LandingTableFanout.isShared(counts, "k9"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
