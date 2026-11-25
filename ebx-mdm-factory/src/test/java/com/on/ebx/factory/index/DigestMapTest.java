package com.on.ebx.factory.index;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * JUnit-free: run the main, a failure throws. {@link DigestMap} (lot IDX, 2026-10-12): put / get / absent, many keys in a tiny table so that probing wraps around, the
 * build-time duplicate guard, growth (and no growth when the table was sized from the row count), {@code ensureCapacity}, {@code clear}, the reserved pair, width 0,
 * and a long random run against a {@code HashMap}.
 */
public final class DigestMapTest
{
	public static void main(final String[] pArgs)
	{
		DigestMapTest.putGetAbsent();
		DigestMapTest.digestsThatLandInTheSameSlotStayApart();
		DigestMapTest.probingWrapsAroundTheEndOfTheTable();
		DigestMapTest.addUniqueCountsAcollision();
		DigestMapTest.growsFromNothingAndKeepsEverything();
		DigestMapTest.aTableSizedFromTheRowCountNeverGrows();
		DigestMapTest.ensureCapacityOnAFilledMap();
		DigestMapTest.clearEmptiesTheMap();
		DigestMapTest.theReservedPairAndWidthZero();
		DigestMapTest.randomRunAgainstAHashMap();
		System.out.println("DigestMapTest OK");
	}

	private static void putGetAbsent()
	{
		final DigestMap map = new DigestMap(2, 10L);
		final int slot = map.slotFor(KeyDigest.of("A|1"));
		map.set(slot, 0, 11L);
		map.set(slot, 1, -22L);
		DigestMapTest.check("created", map.lastCreated() && map.size() == 1);
		final int found = map.find("A|1");
		DigestMapTest.check("found", found == slot && map.get(found, 0) == 11L && map.get(found, 1) == -22L);
		DigestMapTest.check("absent", map.find("A|2") == -1);
		final int again = map.slotFor(KeyDigest.of("A|1"));
		DigestMapTest.check("overwrite reuses the slot, does not create", again == slot && !map.lastCreated() && map.size() == 1);
		map.set(again, 0, 99L);
		DigestMapTest.check("overwritten", map.get(map.find("A|1"), 0) == 99L && map.get(map.find("A|1"), 1) == -22L);
	}

	/** Same top bits (same home slot), different low word: two keys, two entries. */
	private static void digestsThatLandInTheSameSlotStayApart()
	{
		final DigestMap map = new DigestMap(1, 100L);
		final long hi = 0x1234567800000000L;
		for (int i = 1; i <= 20; i++)
		{
			final int slot = map.slotFor(hi, i);
			map.set(slot, 0, i * 100L);
		}
		DigestMapTest.check("20 entries", map.size() == 20);
		for (int i = 1; i <= 20; i++)
		{
			DigestMapTest.check("entry " + i, map.get(map.find(hi, i), 0) == i * 100L);
		}
		DigestMapTest.check("the same hi with another lo is absent", map.find(hi, 21L) == -1);
		DigestMapTest.check("the same lo with another hi is absent", map.find(hi + (1L << 40), 3L) == -1);
	}

	/** A table of 16 slots filled to its limit, homes near the end: the probe must wrap to slot 0 and still find everything and stop on an empty slot. */
	private static void probingWrapsAroundTheEndOfTheTable()
	{
		final DigestMap map = new DigestMap(1, 12L, 85);
		final int capacity = map.capacity();
		final long nearTheEnd = 0xFFFFFFFE00000000L; // home slot: the last one
		for (int i = 1; i <= 12; i++)
		{
			map.set(map.slotFor(nearTheEnd, i), 0, i);
		}
		DigestMapTest.check("capacity unchanged", map.capacity() == capacity);
		for (int i = 1; i <= 12; i++)
		{
			DigestMapTest.check("wrapped entry " + i, map.get(map.find(nearTheEnd, i), 0) == i);
		}
		DigestMapTest.check("absent key terminates", map.find(nearTheEnd, 99L) == -1);
	}

	private static void addUniqueCountsAcollision()
	{
		final DigestMap map = new DigestMap(1, 10L);
		final int first = map.addUnique(5L, 6L);
		map.set(first, 0, 1L);
		DigestMapTest.check("first is new", first >= 0 && map.duplicates() == 0L);
		final int second = map.addUnique(5L, 6L);
		DigestMapTest.check("the same digest again is refused and counted", second == -1 && map.duplicates() == 1L && map.size() == 1);
		DigestMapTest.check("the first entry is untouched", map.get(map.find(5L, 6L), 0) == 1L);
		DigestMapTest.check("a different digest is accepted", map.addUnique(5L, 7L) >= 0 && map.duplicates() == 1L);
		map.addUnique(5L, 6L);
		DigestMapTest.check("counted again", map.duplicates() == 2L);
	}

	private static void growsFromNothingAndKeepsEverything()
	{
		final DigestMap map = new DigestMap(2, 0L);
		final int initial = map.capacity();
		final int n = 200000;
		for (int i = 0; i < n; i++)
		{
			final int slot = map.slotFor(KeyDigest.of("G|" + i));
			map.set(slot, 0, i);
			map.set(slot, 1, ~i);
		}
		DigestMapTest.check("size", map.size() == n);
		DigestMapTest.check("it grew", map.capacity() > initial);
		DigestMapTest.check("still under the load limit", map.size() <= map.capacity() * DigestMap.DEFAULT_LOAD_PERCENT / 100);
		for (int i = 0; i < n; i += 7)
		{
			final int slot = map.find("G|" + i);
			DigestMapTest.check("kept " + i, slot >= 0 && map.get(slot, 0) == i && map.get(slot, 1) == ~i);
		}
		DigestMapTest.check("absent after growth", map.find("G|" + n) == -1);
	}

	private static void aTableSizedFromTheRowCountNeverGrows()
	{
		final int n = 100000;
		final DigestMap map = new DigestMap(1, n);
		final int capacity = map.capacity();
		for (int i = 0; i < n; i++)
		{
			map.set(map.slotFor(KeyDigest.of("P|" + i)), 0, i);
		}
		DigestMapTest.check("no rehash when sized from the row count", map.capacity() == capacity);
		DigestMapTest.check("capacity is about rows / load", capacity < n * 100L / DigestMap.DEFAULT_LOAD_PERCENT + 100L);
		DigestMapTest.check("bytes follow the table", map.bytes() >= 8L * 3L * capacity && map.bytes() < 8L * 3L * capacity + 64L);
	}

	private static void ensureCapacityOnAFilledMap()
	{
		final DigestMap map = new DigestMap(1, 10L);
		for (int i = 0; i < 8; i++)
		{
			map.set(map.slotFor(KeyDigest.of("E|" + i)), 0, i);
		}
		map.ensureCapacity(5000L);
		DigestMapTest.check("room made", map.capacity() >= 5000L * 100L / DigestMap.DEFAULT_LOAD_PERCENT);
		for (int i = 0; i < 8; i++)
		{
			DigestMapTest.check("kept " + i, map.get(map.find("E|" + i), 0) == i);
		}
		final int capacity = map.capacity();
		map.ensureCapacity(10L);
		DigestMapTest.check("never shrinks", map.capacity() == capacity);
	}

	private static void clearEmptiesTheMap()
	{
		final DigestMap map = new DigestMap(1, 10L);
		map.addUnique(1L, 2L);
		map.addUnique(1L, 2L);
		map.clear();
		DigestMapTest.check("empty", map.size() == 0 && map.find(1L, 2L) == -1 && map.duplicates() == 0L);
		DigestMapTest.check("usable again", map.addUnique(1L, 2L) >= 0);
	}

	private static void theReservedPairAndWidthZero()
	{
		final DigestMap set = new DigestMap(0, 10L);
		boolean refused = false;
		try
		{
			set.slotFor(0L, 0L);
		}
		catch (final IllegalArgumentException ex)
		{
			refused = true;
		}
		DigestMapTest.check("(0, 0) is refused", refused);
		DigestMapTest.check("find (0, 0) is simply absent", set.find(0L, 0L) == -1);
		set.slotFor(KeyDigest.of("S|1"));
		set.slotFor(KeyDigest.of("S|2"));
		DigestMapTest.check("a set of digests", set.size() == 2 && set.find("S|1") >= 0 && set.find("S|3") == -1 && set.width() == 0);
		final DigestMap values = new DigestMap(3, 4L);
		final int slot = values.slotFor(KeyDigest.of("V"));
		DigestMapTest.check("new values are zero", values.allZero(slot));
		values.set(slot, 2, 5L);
		DigestMapTest.check("not all zero any more", !values.allZero(slot));
	}

	private static void randomRunAgainstAHashMap()
	{
		final Random random = new Random(20261012L);
		final DigestMap map = new DigestMap(1, 1000L);
		final Map<Long, Long> expected = new HashMap<Long, Long>();
		for (int op = 0; op < 400000; op++)
		{
			final long id = random.nextInt(60000);
			final KeyDigest key = KeyDigest.of("R|" + id);
			if (random.nextInt(3) == 0)
			{
				final int slot = map.find(key);
				final Long want = expected.get(Long.valueOf(id));
				DigestMapTest.check("lookup " + id, want == null ? slot == -1 : slot >= 0 && map.get(slot, 0) == want.longValue());
			}
			else
			{
				final long value = random.nextLong();
				map.set(map.slotFor(key), 0, value);
				expected.put(Long.valueOf(id), Long.valueOf(value));
			}
		}
		DigestMapTest.check("same size", map.size() == expected.size());
		for (final Map.Entry<Long, Long> entry : expected.entrySet())
		{
			DigestMapTest.check("final " + entry.getKey(), map.get(map.find("R|" + entry.getKey()), 0) == entry.getValue().longValue());
		}
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
