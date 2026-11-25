package com.on.ebx.factory.index;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * JUnit-free: run the main, a failure throws. {@link KeyDigest} (lot IDX, 2026-10-12): deterministic, the first 128 bits of SHA-256 of the UTF-16 code units (checked
 * against an independent {@code MessageDigest} call), two keys that differ only by an unpaired surrogate stay different, the reserved empty-slot pair is never produced,
 * a long key grows the buffer, and the per-thread state gives the same answers from several threads.
 */
public final class KeyDigestTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		KeyDigestTest.matchesAnIndependentSha256();
		KeyDigestTest.deterministicAndDistinct();
		KeyDigestTest.unpairedSurrogatesDoNotCollapse();
		KeyDigestTest.theReservedPairIsNeverProduced();
		KeyDigestTest.longKeysAndTheEmptyKey();
		KeyDigestTest.severalThreadsAgree();
		System.out.println("KeyDigestTest OK");
	}

	private static void matchesAnIndependentSha256() throws Exception
	{
		for (final String key : new String[] { "abc", "VOL1MCRM|C000123", "", "élève|42" })
		{
			final byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-16BE"));
			long hi = 0L;
			long lo = 0L;
			for (int i = 0; i < 8; i++)
			{
				hi = (hi << 8) | (hash[i] & 0xFFL);
				lo = (lo << 8) | (hash[8 + i] & 0xFFL);
			}
			final KeyDigest digest = KeyDigest.of(key);
			KeyDigestTest.check("the first 128 bits of SHA-256 of '" + key + "'", digest.hi() == hi && digest.lo() == lo);
		}
	}

	private static void deterministicAndDistinct()
	{
		KeyDigestTest.check("same key, same digest", KeyDigest.of("A|1").equals(KeyDigest.of("A|1")));
		KeyDigestTest.check("different keys, different digests", !KeyDigest.of("A|1").equals(KeyDigest.of("A|2")));
		KeyDigestTest.check("the separator matters", !KeyDigest.of("A|1").equals(KeyDigest.of("A1")));
		final Set<KeyDigest> seen = new HashSet<KeyDigest>();
		for (int i = 0; i < 100000; i++)
		{
			seen.add(KeyDigest.of("SRC|" + i));
		}
		KeyDigestTest.check("100,000 keys, 100,000 digests", seen.size() == 100000);
	}

	private static void unpairedSurrogatesDoNotCollapse()
	{
		// getBytes("UTF-8") would turn both lone surrogates into '?' and make the three keys one
		final String question = "k?";
		final String high = "k\ud800";
		final String low = "k\udc00";
		KeyDigestTest.check("a lone high surrogate is not '?'", !KeyDigest.of(high).equals(KeyDigest.of(question)));
		KeyDigestTest.check("a lone low surrogate is not '?'", !KeyDigest.of(low).equals(KeyDigest.of(question)));
		KeyDigestTest.check("high and low surrogates differ", !KeyDigest.of(high).equals(KeyDigest.of(low)));
	}

	private static void theReservedPairIsNeverProduced()
	{
		final KeyDigest zero = KeyDigest.of(0L, 0L);
		KeyDigestTest.check("(0, 0) is stored as (0, 1)", zero.hi() == 0L && zero.lo() == 1L);
		KeyDigestTest.check("any other pair is kept", KeyDigest.of(5L, 0L).hi() == 5L && KeyDigest.of(0L, 7L).lo() == 7L);
	}

	private static void longKeysAndTheEmptyKey()
	{
		final StringBuilder longKey = new StringBuilder();
		for (int i = 0; i < 5000; i++)
		{
			longKey.append((char) ('a' + i % 26));
		}
		final KeyDigest a = KeyDigest.of(longKey.toString());
		final KeyDigest b = KeyDigest.of(longKey.toString() + "x");
		KeyDigestTest.check("a 5,000-character key hashes (the buffer grows) and differs from its extension", !a.equals(b));
		KeyDigestTest.check("a short key after a long one is unaffected by the stale buffer tail", KeyDigest.of("abc").equals(KeyDigest.of(new String("abc"))));
		KeyDigestTest.check("the empty key has a digest", KeyDigest.of("").hi() != 0L || KeyDigest.of("").lo() != 0L);
	}

	private static void severalThreadsAgree() throws Exception
	{
		final KeyDigest[] expected = new KeyDigest[1000];
		for (int i = 0; i < expected.length; i++)
		{
			expected[i] = KeyDigest.of("T|" + i);
		}
		final boolean[] ok = new boolean[] { true };
		final Thread[] threads = new Thread[4];
		for (int t = 0; t < threads.length; t++)
		{
			threads[t] = new Thread()
			{
				@Override
				public void run()
				{
					for (int round = 0; round < 20; round++)
					{
						for (int i = 0; i < expected.length; i++)
						{
							if (!expected[i].equals(KeyDigest.of("T|" + i)))
							{
								ok[0] = false;
							}
						}
					}
				}
			};
			threads[t].start();
		}
		for (final Thread thread : threads)
		{
			thread.join();
		}
		KeyDigestTest.check("four threads compute the same digests", ok[0]);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
