package com.on.ebx.factory.index;

/**
 * JUnit-free: run the main, a failure throws. {@link KeyCodec} (lot IDX, 2026-10-12): a canonical decimal is its own code and comes back digit for digit; everything else
 * that is not a canonical decimal (leading zero, sign, point, 19 digits, composite key, UUID) goes through the table and also round-trips exactly; null and the empty
 * string are NONE; a text is stored once.
 */
public final class KeyCodecTest
{
	public static void main(final String[] pArgs)
	{
		KeyCodecTest.canonicalNumbersAreTheirOwnCode();
		KeyCodecTest.everythingElseRoundTripsThroughTheTable();
		KeyCodecTest.noneAndDedupe();
		KeyCodecTest.aThousandMixedKeys();
		System.out.println("KeyCodecTest OK");
	}

	private static void canonicalNumbersAreTheirOwnCode()
	{
		final KeyCodec codec = new KeyCodec();
		for (final String text : new String[] { "0", "7", "42", "1000000", "123456789012345678", "999999999999999999" })
		{
			final long code = codec.encode(text);
			KeyCodecTest.check("'" + text + "' is a non-negative code", code >= 0L);
			KeyCodecTest.check("'" + text + "' round-trips", text.equals(codec.decode(code)));
		}
		KeyCodecTest.check("a number costs no table entry", codec.tableSize() == 0);
		KeyCodecTest.check("'42' is code 42", codec.encode("42") == 42L);
	}

	private static void everythingElseRoundTripsThroughTheTable()
	{
		final KeyCodec codec = new KeyCodec();
		// the keys that LOOK numeric but would not come back identically if parsed: each must keep its exact text
		final String[] texts = { "007", "00", "-5", "+5", "1.0", "1e3", " 7", "7 ", "1234567890123456789", "99999999999999999999", "a|b", "AE-AUH-001|DIOR",
			"3f2504e0-4f89-11d3-9a0c-0305e82c3301", "é", "0x10", "٣" };
		for (final String text : texts)
		{
			final long code = codec.encode(text);
			KeyCodecTest.check("'" + text + "' goes through the table (negative code)", code < 0L && code != KeyCodec.NONE);
			KeyCodecTest.check("'" + text + "' round-trips", text.equals(codec.decode(code)));
		}
		KeyCodecTest.check("table size", codec.tableSize() == texts.length);
	}

	private static void noneAndDedupe()
	{
		final KeyCodec codec = new KeyCodec();
		KeyCodecTest.check("null is NONE", codec.encode(null) == KeyCodec.NONE);
		KeyCodecTest.check("empty is NONE", codec.encode("") == KeyCodec.NONE);
		KeyCodecTest.check("NONE decodes to null", codec.decode(KeyCodec.NONE) == null);
		final long first = codec.encode("a|b");
		final long second = codec.encode(new String("a|b"));
		KeyCodecTest.check("a text is stored once", first == second && codec.tableSize() == 1);
		KeyCodecTest.check("the instance is shared", codec.decode(first) == codec.decode(second));
	}

	private static void aThousandMixedKeys()
	{
		final KeyCodec codec = new KeyCodec();
		final long[] codes = new long[3000];
		final String[] texts = new String[3000];
		for (int i = 0; i < texts.length; i++)
		{
			texts[i] = i % 3 == 0 ? Integer.toString(i * 7) : i % 3 == 1 ? "K" + i + "|" + (i % 5) : "0" + i;
			codes[i] = codec.encode(texts[i]);
		}
		for (int i = 0; i < texts.length; i++)
		{
			KeyCodecTest.check("key " + texts[i], texts[i].equals(codec.decode(codes[i])));
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
