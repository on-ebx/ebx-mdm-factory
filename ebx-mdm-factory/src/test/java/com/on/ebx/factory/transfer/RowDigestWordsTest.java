package com.on.ebx.factory.transfer;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * JUnit-free: run the main, a failure throws. Lot IDX (2026-10-12): a stored {@link RowDigest} kept as {@code long} words ({@link RowDigest#storedWords}) and compared with
 * {@link RowDigest#sameAsWords} answers EXACTLY what {@link RowDigest#sameAs} answered on the object - digest round trip - including a deferred field left out of a planned
 * digest, an unknown digest on either side (never equal) and the all-zero words of "no digest known". Also {@link StagingRowDigest#same} refusing all-zero stored words.
 */
public final class RowDigestWordsTest
{
	public static void main(final String[] pArgs)
	{
		RowDigestWordsTest.roundTripWithoutOptionalFields();
		RowDigestWordsTest.roundTripWithDeferredFields();
		RowDigestWordsTest.unknownIsNeverEqual();
		RowDigestWordsTest.zeroWordsMeanNoDigest();
		RowDigestWordsTest.randomRowsAgreeWithTheObjectComparison();
		RowDigestWordsTest.stagingEntriesOfZeroNeverMatch();
		System.out.println("RowDigestWordsTest OK");
	}

	private static RowDigest of(final Object... pFixed)
	{
		return RowDigest.of(pFixed, null);
	}

	private static long[] words(final RowDigest pDigest, final int pOptional)
	{
		final long[] out = new long[RowDigest.storedWordCount(pOptional)];
		pDigest.storedWords(out, pOptional);
		return out;
	}

	private static void roundTripWithoutOptionalFields()
	{
		final RowDigest stored = RowDigestWordsTest.of("Dupont", "Paris", new BigDecimal("10.0"), Boolean.TRUE);
		final long[] words = RowDigestWordsTest.words(stored, 0);
		RowDigestWordsTest.check("2 words", words.length == 2 && (words[0] != 0L || words[1] != 0L));
		RowDigestWordsTest.check("the planned digest of the same values equals the stored words", RowDigestWordsTest.of("Dupont", "Paris", Integer.valueOf(10), Boolean.TRUE).sameAsWords(words));
		RowDigestWordsTest.check("a different value differs", !RowDigestWordsTest.of("Dupont", "Lyon", Integer.valueOf(10), Boolean.TRUE).sameAsWords(words));
		RowDigestWordsTest.check("agrees with sameAs", RowDigestWordsTest.of("Dupont", "Paris", Integer.valueOf(10), Boolean.TRUE).sameAs(stored));
	}

	private static void roundTripWithDeferredFields()
	{
		final RowDigest stored = RowDigest.of(new Object[] { "a", "b" }, new Object[] { "p1", "p2" });
		final long[] words = RowDigestWordsTest.words(stored, 2);
		RowDigestWordsTest.check("4 words", words.length == 4);
		RowDigestWordsTest.check("both deferred fields equal", RowDigest.of(new Object[] { "a", "b" }, new Object[] { "p1", "p2" }).sameAsWords(words));
		RowDigestWordsTest.check("a deferred field differs", !RowDigest.of(new Object[] { "a", "b" }, new Object[] { "p1", "other" }).sameAsWords(words));
		RowDigestWordsTest.check("a deferred field left out is not compared",
			RowDigest.of(new Object[] { "a", "b" }, new Object[] { "p1", RowDigest.OMITTED }).sameAsWords(words));
		RowDigestWordsTest.check("both left out: only the fixed fields count",
			RowDigest.of(new Object[] { "a", "b" }, new Object[] { RowDigest.OMITTED, RowDigest.OMITTED }).sameAsWords(words));
		RowDigestWordsTest.check("a fixed field differs even when the deferred ones are left out",
			!RowDigest.of(new Object[] { "a", "c" }, new Object[] { RowDigest.OMITTED, RowDigest.OMITTED }).sameAsWords(words));
		RowDigestWordsTest.check("words too short for the planned digest: not equal",
			!RowDigest.of(new Object[] { "a", "b" }, new Object[] { "p1", "p2" }).sameAsWords(Arrays.copyOf(words, 3)));
	}

	private static void unknownIsNeverEqual()
	{
		final RowDigest unknown = RowDigestWordsTest.of(new Object());
		RowDigestWordsTest.check("the object digest is unknown", unknown.isUnknown());
		final long[] unknownWords = RowDigestWordsTest.words(unknown, 0);
		RowDigestWordsTest.check("a stored unknown writes zeros", unknownWords[0] == 0L && unknownWords[1] == 0L);
		RowDigestWordsTest.check("a planned digest never equals unknown words", !RowDigestWordsTest.of("x").sameAsWords(unknownWords));
		RowDigestWordsTest.check("an unknown planned digest never equals real words", !unknown.sameAsWords(RowDigestWordsTest.words(RowDigestWordsTest.of("x"), 0)));
		RowDigestWordsTest.check("two unknowns are not equal", !unknown.sameAsWords(unknownWords));
	}

	private static void zeroWordsMeanNoDigest()
	{
		RowDigestWordsTest.check("null words", !RowDigestWordsTest.of("x").sameAsWords(null));
		RowDigestWordsTest.check("short words", !RowDigestWordsTest.of("x").sameAsWords(new long[1]));
		RowDigestWordsTest.check("all-zero words", !RowDigestWordsTest.of("x").sameAsWords(new long[2]));
		// a stored digest with fewer deferred hashes than the layout expects writes zeros rather than a wrong digest
		final RowDigest noOptional = RowDigestWordsTest.of("x");
		final long[] out = new long[4];
		Arrays.fill(out, 7L);
		noOptional.storedWords(out, 2);
		RowDigestWordsTest.check("a layout mismatch writes zeros", out[0] == 0L && out[1] == 0L && out[2] == 0L && out[3] == 0L);
	}

	/** The whole point: for any pair of rows, words and objects say the same thing. */
	private static void randomRowsAgreeWithTheObjectComparison()
	{
		final Random random = new Random(7L);
		final Object[] pool = { null, "", "a", "b", "10", Integer.valueOf(10), new BigDecimal("10.00"), Long.valueOf(11), Boolean.TRUE, Boolean.FALSE,
			Arrays.asList("x", "y"), Arrays.asList(), new java.util.Date(1000L), new java.sql.Timestamp(1000L) };
		int equal = 0;
		for (int i = 0; i < 20000; i++)
		{
			final int optional = random.nextInt(3);
			final Object[] fixedA = new Object[3];
			final Object[] fixedB = new Object[3];
			final Object[] optA = optional == 0 ? null : new Object[optional];
			final Object[] optB = optional == 0 ? null : new Object[optional];
			for (int f = 0; f < 3; f++)
			{
				fixedA[f] = pool[random.nextInt(pool.length)];
				fixedB[f] = random.nextInt(4) == 0 ? pool[random.nextInt(pool.length)] : fixedA[f];
			}
			for (int f = 0; f < optional; f++)
			{
				optA[f] = pool[random.nextInt(pool.length)];
				optB[f] = random.nextInt(3) == 0 ? RowDigest.OMITTED : random.nextInt(4) == 0 ? pool[random.nextInt(pool.length)] : optA[f];
			}
			final RowDigest stored = RowDigest.of(fixedA, optA);
			final RowDigest planned = RowDigest.of(fixedB, optB);
			final boolean viaObject = planned.sameAs(stored);
			final boolean viaWords = planned.sameAsWords(RowDigestWordsTest.words(stored, optional));
			if (viaObject != viaWords)
			{
				throw new AssertionError("row " + i + ": object " + viaObject + ", words " + viaWords);
			}
			if (viaObject)
			{
				equal++;
			}
		}
		RowDigestWordsTest.check("the run had equal pairs, so it is not vacuous (" + equal + ")", equal > 1000);
	}

	private static void stagingEntriesOfZeroNeverMatch()
	{
		final long[] planned = new long[] { 5L, 6L };
		RowDigestWordsTest.check("equal words match", StagingRowDigest.same(planned, new long[] { 5L, 6L }, 0));
		RowDigestWordsTest.check("zero stored words never match", !StagingRowDigest.same(new long[] { 0L, 0L }, new long[] { 0L, 0L }, 0));
		RowDigestWordsTest.check("zero for the second layout only", !StagingRowDigest.same(planned, new long[] { 5L, 6L, 0L, 0L }, 1));
		RowDigestWordsTest.check("the first layout of the same entry still matches", StagingRowDigest.same(planned, new long[] { 5L, 6L, 0L, 0L }, 0));
		final Map<String, Object> unused = new HashMap<String, Object>();
		RowDigestWordsTest.check("sanity", unused.isEmpty());
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
