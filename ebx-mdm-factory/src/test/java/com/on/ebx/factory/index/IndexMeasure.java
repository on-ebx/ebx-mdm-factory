package com.on.ebx.factory.index;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.transfer.RowDigest;

/**
 * Lot IDX (2026-10-12) measurement harness - NOT a unit test (its name does not end in Test, so {@code tools/test_local.sh} does not run it); {@code tools/index_measure.sh}
 * compiles and runs it, one JVM per line. No EBX class is involved: it builds the OLD structure (the {@code HashMap}s the loaders used before lot IDX) or the NEW one
 * (the compact indexes) from the same synthetic keys and prints, for each, the heap it retains after a full GC, the build time, and the time of a lookup that hits and
 * of one that misses.
 * <p>
 * Usage: {@code IndexMeasure <kind> <old|new> <rows>}, kind = {@code xref} (staging key -> master key), {@code master} (master key -> row digest), {@code merged} (a third
 * of the rows merged, 40 members to a golden, golden strings shared as the old {@code share} did), {@code staging} (staging key -> one layout's 128-bit digest).
 * <p>
 * The key shapes are those of Vol1mF: staging keys {@code VOL1MCRM|C0000123}, master keys a 7-digit decimal. The build includes what the scan does per row besides the
 * insert (making the key strings, and for {@code master} the SHA-256 {@link RowDigest} of five fields, which costs the same in both columns).
 */
public final class IndexMeasure
{
	private static Object retained; // keeps the structure alive across the measurement

	public static void main(final String[] pArgs) throws Exception
	{
		final String kind = pArgs[0];
		final boolean compact = "new".equals(pArgs[1]);
		final int n = Integer.parseInt(pArgs[2]);

		final long before = IndexMeasure.usedAfterGc();
		final long startNs = System.nanoTime();
		IndexMeasure.build(kind, compact, n);
		final long buildMs = (System.nanoTime() - startNs) / 1000000L;
		final long after = IndexMeasure.usedAfterGc();
		final long held = after - before;

		// lookups: hits then misses, over keys made beforehand (so the key construction is not timed)
		final int probes = Math.min(n, 1000000);
		final String[] hitKeys = new String[probes];
		final String[] missKeys = new String[probes];
		for (int i = 0; i < probes; i++)
		{
			final int row = (int) ((i * 7919L) % n);
			hitKeys[i] = IndexMeasure.lookupKey(kind, row);
			missKeys[i] = IndexMeasure.lookupKey(kind, n + row + 1);
		}
		final long hitNs = IndexMeasure.timeLookups(kind, compact, hitKeys, true);
		final long hitNs2 = IndexMeasure.timeLookups(kind, compact, hitKeys, true); // warmed
		final long missNs = IndexMeasure.timeLookups(kind, compact, missKeys, false);
		final long missNs2 = IndexMeasure.timeLookups(kind, compact, missKeys, false);
		System.out.println(String.format("%-8s %-4s rows=%-9d heapMB=%8.1f bytesPerRow=%6.1f buildMs=%6d hitNs=%5d missNs=%5d", kind, pArgs[1], Integer.valueOf(n),
			Double.valueOf(held / 1048576.0), Double.valueOf((double) held / n), Long.valueOf(buildMs), Long.valueOf(Math.min(hitNs, hitNs2) / probes),
			Long.valueOf(Math.min(missNs, missNs2) / probes)));
		if (IndexMeasure.retained == null)
		{
			throw new IllegalStateException();
		}
	}

	private static String stagingKey(final int pRow)
	{
		return "VOL1MCRM|C" + String.format("%07d", Integer.valueOf(pRow));
	}

	private static String masterKey(final int pRow)
	{
		return Integer.toString(5000000 + pRow);
	}

	private static String lookupKey(final String pKind, final int pRow)
	{
		return "xref".equals(pKind) || "staging".equals(pKind) ? IndexMeasure.stagingKey(pRow) : IndexMeasure.masterKey(pRow);
	}

	private static RowDigest digestOf(final int pRow)
	{
		return RowDigest.of(new Object[] { "Nom" + pRow, "Prenom" + (pRow % 977), "75000", Integer.valueOf(pRow % 100), Boolean.TRUE }, null);
	}

	private static void build(final String pKind, final boolean pCompact, final int pRows)
	{
		if ("xref".equals(pKind))
		{
			if (pCompact)
			{
				final KeyValueIndex index = new KeyValueIndex(pRows);
				for (int i = 0; i < pRows; i++)
				{
					index.add(IndexMeasure.stagingKey(i), IndexMeasure.masterKey(i));
				}
				IndexMeasure.retained = index;
			}
			else
			{
				final Map<String, String> map = new HashMap<String, String>(MapSizing.capacity(pRows));
				for (int i = 0; i < pRows; i++)
				{
					map.put(IndexMeasure.stagingKey(i), IndexMeasure.masterKey(i));
				}
				IndexMeasure.retained = map;
			}
		}
		else if ("master".equals(pKind))
		{
			if (pCompact)
			{
				final DigestMap map = new DigestMap(RowDigest.storedWordCount(0), pRows);
				final long[] words = new long[2];
				for (int i = 0; i < pRows; i++)
				{
					final int slot = map.addUnique(KeyDigest.of(IndexMeasure.masterKey(i)));
					IndexMeasure.digestOf(i).storedWords(words, 0);
					map.set(slot, 0, words[0]);
					map.set(slot, 1, words[1]);
				}
				IndexMeasure.retained = map;
			}
			else
			{
				final Map<String, RowDigest> map = new HashMap<String, RowDigest>(MapSizing.capacity(pRows));
				for (int i = 0; i < pRows; i++)
				{
					map.put(IndexMeasure.masterKey(i), IndexMeasure.digestOf(i));
				}
				IndexMeasure.retained = map;
			}
		}
		else if ("merged".equals(pKind))
		{
			final int merged = pRows / 3;
			if (pCompact)
			{
				final KeyValueIndex index = new KeyValueIndex(merged);
				for (int i = 0; i < merged; i++)
				{
					index.add(IndexMeasure.masterKey(i), IndexMeasure.masterKey(merged + i / 40));
				}
				IndexMeasure.retained = index;
			}
			else
			{
				final Map<String, String> map = new HashMap<String, String>(MapSizing.capacity(merged));
				final Map<String, String> shared = new HashMap<String, String>();
				for (int i = 0; i < merged; i++)
				{
					final String golden = IndexMeasure.masterKey(merged + i / 40);
					final String known = shared.get(golden);
					map.put(IndexMeasure.masterKey(i), known != null ? known : golden);
					if (known == null)
					{
						shared.put(golden, golden);
					}
				}
				IndexMeasure.retained = map;
			}
		}
		else if ("staging".equals(pKind))
		{
			if (pCompact)
			{
				final DigestMap map = new DigestMap(2, pRows);
				for (int i = 0; i < pRows; i++)
				{
					final int slot = map.addUnique(KeyDigest.of(IndexMeasure.stagingKey(i)));
					map.set(slot, 0, i * 0x9E3779B97F4A7C15L);
					map.set(slot, 1, ~i * 0xC2B2AE3D27D4EB4FL);
				}
				IndexMeasure.retained = map;
			}
			else
			{
				final Map<String, long[]> map = new HashMap<String, long[]>(MapSizing.capacity(pRows));
				for (int i = 0; i < pRows; i++)
				{
					map.put(IndexMeasure.stagingKey(i), new long[] { i * 0x9E3779B97F4A7C15L, ~i * 0xC2B2AE3D27D4EB4FL });
				}
				IndexMeasure.retained = map;
			}
		}
		else
		{
			throw new IllegalArgumentException(pKind);
		}
	}

	@SuppressWarnings("unchecked")
	private static long timeLookups(final String pKind, final boolean pCompact, final String[] pKeys, final boolean pExpectHit)
	{
		final long start = System.nanoTime();
		long found = 0;
		if (pCompact)
		{
			for (final String key : pKeys)
			{
				final boolean hit = IndexMeasure.retained instanceof KeyValueIndex ? ((KeyValueIndex) IndexMeasure.retained).contains(key)
					: ((DigestMap) IndexMeasure.retained).find(key) >= 0;
				if (hit)
				{
					found++;
				}
			}
		}
		else
		{
			for (final String key : pKeys)
			{
				if (((Map<String, ?>) IndexMeasure.retained).get(key) != null)
				{
					found++;
				}
			}
		}
		final long ns = System.nanoTime() - start;
		if ("merged".equals(pKind))
		{
			return ns; // only a third of the keys are merged: the hit/miss split is not meaningful, the count is not checked
		}
		if (pExpectHit ? found != pKeys.length : found != 0)
		{
			throw new IllegalStateException("lookups found " + found + " of " + pKeys.length + " (expected " + (pExpectHit ? "all" : "none") + ")");
		}
		return ns;
	}

	private static long usedAfterGc()
	{
		for (int i = 0; i < 4; i++)
		{
			System.gc();
			try
			{
				Thread.sleep(150L);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
			}
		}
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
	}

	/** The sizing the loaders used for their HashMaps (load factor 0.75, no rehash). */
	private static final class MapSizing
	{
		static int capacity(final long pRows)
		{
			return (int) Math.min(pRows * 4L / 3L + 16L, 1L << 29);
		}
	}

	@SuppressWarnings("unused")
	private static List<String> unused()
	{
		return new ArrayList<String>();
	}
}
