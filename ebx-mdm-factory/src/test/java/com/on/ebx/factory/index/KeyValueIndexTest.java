package com.on.ebx.factory.index;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * JUnit-free: run the main, a failure throws. {@link KeyValueIndex} (lot IDX, 2026-10-12): the {@code Map<String, String>} contract the {@code Xref}, merged and
 * {@code RecordMetadata} indexes need - get / contains / absent, a value that is a number, a text, or nothing, overwrite, the build-time duplicate counter, null keys,
 * lookups by a precomputed digest, {@code clear}, a size hint, and a random run against a {@code HashMap}.
 */
public final class KeyValueIndexTest
{
	public static void main(final String[] pArgs)
	{
		KeyValueIndexTest.getContainsAbsent();
		KeyValueIndexTest.valuesOfEveryShape();
		KeyValueIndexTest.overwriteAndAdd();
		KeyValueIndexTest.nullKeys();
		KeyValueIndexTest.lookupByPrecomputedDigest();
		KeyValueIndexTest.clearAndSizeHint();
		KeyValueIndexTest.randomRunAgainstAHashMap();
		System.out.println("KeyValueIndexTest OK");
	}

	private static void getContainsAbsent()
	{
		final KeyValueIndex index = new KeyValueIndex(10L);
		index.add("VOL1MCRM|C000123", "9001");
		DigestMapAssert.check("hit", "9001".equals(index.get("VOL1MCRM|C000123")) && index.contains("VOL1MCRM|C000123"));
		DigestMapAssert.check("miss", index.get("VOL1MCRM|C000124") == null && !index.contains("VOL1MCRM|C000124"));
		DigestMapAssert.check("size", index.size() == 1 && index.duplicates() == 0L);
	}

	private static void valuesOfEveryShape()
	{
		final KeyValueIndex index = new KeyValueIndex(10L);
		index.add("n", "42");
		index.add("text", "a|b");
		index.add("zero", "0");
		index.add("none", "");
		index.add("nullvalue", null);
		index.add("leading", "0042");
		DigestMapAssert.check("number", "42".equals(index.get("n")));
		DigestMapAssert.check("text", "a|b".equals(index.get("text")));
		DigestMapAssert.check("zero", "0".equals(index.get("zero")));
		DigestMapAssert.check("a leading zero stays", "0042".equals(index.get("leading")));
		DigestMapAssert.check("an empty value reads null but the key is there", index.get("none") == null && index.contains("none"));
		DigestMapAssert.check("a null value likewise", index.get("nullvalue") == null && index.contains("nullvalue"));
	}

	private static void overwriteAndAdd()
	{
		final KeyValueIndex index = new KeyValueIndex(10L);
		DigestMapAssert.check("add of a new key", index.add("k", "1"));
		DigestMapAssert.check("add of the same key is refused and counted", !index.add("k", "2") && index.duplicates() == 1L);
		DigestMapAssert.check("the first value is kept", "1".equals(index.get("k")));
		index.put("k", "3");
		DigestMapAssert.check("put overwrites", "3".equals(index.get("k")) && index.size() == 1 && index.duplicates() == 1L);
		index.put("new", "4");
		DigestMapAssert.check("put inserts", "4".equals(index.get("new")) && index.size() == 2);
	}

	private static void nullKeys()
	{
		final KeyValueIndex index = new KeyValueIndex(10L);
		DigestMapAssert.check("add(null) is ignored", index.add(null, "1") && index.size() == 0);
		index.put(null, "1");
		DigestMapAssert.check("put(null) is ignored", index.size() == 0);
		DigestMapAssert.check("get(null) is null", index.get((String) null) == null && !index.contains((String) null));
	}

	/** The digest round trip: the lookup by KeyDigest answers what the lookup by text does. */
	private static void lookupByPrecomputedDigest()
	{
		final KeyValueIndex index = new KeyValueIndex(100L);
		for (int i = 0; i < 100; i++)
		{
			index.add("S|" + i, Integer.toString(i * 3));
		}
		for (int i = 0; i < 100; i++)
		{
			final KeyDigest digest = KeyDigest.of("S|" + i);
			DigestMapAssert.check("by digest " + i, index.contains(digest) && Integer.toString(i * 3).equals(index.get(digest)) && index.get("S|" + i).equals(index.get(digest)));
		}
		DigestMapAssert.check("absent by digest", !index.contains(KeyDigest.of("S|100")) && index.get(KeyDigest.of("S|100")) == null);
	}

	private static void clearAndSizeHint()
	{
		final KeyValueIndex index = new KeyValueIndex(0L);
		index.ensureCapacity(50000L);
		for (int i = 0; i < 50000; i++)
		{
			index.add("H|" + i, "x" + (i % 7));
		}
		DigestMapAssert.check("filled", index.size() == 50000 && "x3".equals(index.get("H|10")));
		final long bytes = index.estimatedBytes();
		DigestMapAssert.check("about 30 bytes a row with a handful of distinct text values", bytes < 50000L * 32L);
		index.clear();
		DigestMapAssert.check("cleared", index.size() == 0 && index.get("H|10") == null && index.duplicates() == 0L);
		DigestMapAssert.check("usable after clear", index.add("H|10", "5") && "5".equals(index.get("H|10")));
	}

	private static void randomRunAgainstAHashMap()
	{
		final Random random = new Random(42L);
		final KeyValueIndex index = new KeyValueIndex(100L);
		final Map<String, String> expected = new HashMap<String, String>();
		for (int op = 0; op < 300000; op++)
		{
			final String key = "K|" + random.nextInt(40000);
			if (random.nextInt(3) == 0)
			{
				final String want = expected.get(key);
				DigestMapAssert.check("lookup " + key, want == null ? !index.contains(key) : want.equals(index.get(key)));
			}
			else
			{
				final int kind = random.nextInt(3);
				final String value = kind == 0 ? Long.toString(random.nextInt(1000000)) : kind == 1 ? "G|" + random.nextInt(50) : Long.toString(random.nextInt(1000)) + "0";
				index.put(key, value);
				expected.put(key, value);
			}
		}
		DigestMapAssert.check("same size", index.size() == expected.size());
		for (final Map.Entry<String, String> entry : expected.entrySet())
		{
			DigestMapAssert.check("final " + entry.getKey(), entry.getValue().equals(index.get(entry.getKey())));
		}
	}

	private static final class DigestMapAssert
	{
		static void check(final String pMessage, final boolean pCondition)
		{
			if (!pCondition)
			{
				throw new AssertionError(pMessage);
			}
		}
	}
}
