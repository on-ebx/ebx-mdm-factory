package com.on.ebx.factory.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lot IDX (2026-10-12): a key TEXT as one {@code long}, and back, exactly. It is how the compact indexes hold the VALUES that are keys (the master key an {@code
 * Xref} row points at, the golden record a merged record was folded into) when a consumer needs the actual text later: written into a foreign key of a child row,
 * used as the next hop of a merge chain, as the argument of a live read.
 * <p>
 * <b>The code.</b> A key that is a canonical non-negative decimal of at most 18 digits (no sign, no leading zero, {@code "0"} alone allowed: the formatted primary key
 * of every auto-increment master table) is its own value, {@code >= 0}; decoding writes the same digits back, so the round trip is exact. Anything else - a composite
 * key {@code a|b}, a UUID, a decimal with a point - goes into a per-codec table and is coded as {@code -(index + 1)}; the table keeps one {@code String} per DISTINCT
 * text, so the golden key shared by 40 merged records costs one string. {@link #NONE} stands for null and the empty string. A codec owned by an entity whose keys are
 * all numeric therefore costs 8 bytes a key; one whose keys are all text costs about what the {@code HashMap} it replaces did, never more than a string table.
 * <p>
 * Not thread safe for {@link #encode}; {@link #decode} only reads. One codec per index.
 */
public final class KeyCodec
{
	/** Null and the empty string. */
	public static final long NONE = Long.MIN_VALUE;

	private static final int MAX_DIGITS = 18;

	private final List<String> texts = new ArrayList<String>();
	private final Map<String, Long> codes = new HashMap<String, Long>();

	/** The code of {@code pText}: its own value when canonical decimal, a negative table code otherwise, {@link #NONE} for null/empty. */
	public long encode(final String pText)
	{
		if (pText == null || pText.isEmpty())
		{
			return KeyCodec.NONE;
		}
		final long number = KeyCodec.canonicalNumber(pText);
		if (number >= 0L)
		{
			return number;
		}
		final Long known = this.codes.get(pText);
		if (known != null)
		{
			return known.longValue();
		}
		final long code = -(this.texts.size() + 1L);
		this.texts.add(pText);
		this.codes.put(pText, Long.valueOf(code));
		return code;
	}

	/** The text {@code pCode} stands for; null for {@link #NONE}. */
	public String decode(final long pCode)
	{
		if (pCode == KeyCodec.NONE)
		{
			return null;
		}
		if (pCode >= 0L)
		{
			return Long.toString(pCode);
		}
		return this.texts.get((int) (-pCode - 1L));
	}

	/** Distinct texts that were not numbers (each costs a string and a map entry here). */
	public int tableSize()
	{
		return this.texts.size();
	}

	/** Rough bytes of the table (strings of 40 characters, a list slot, a map entry): the part of an index that is not in its flat array. */
	public long estimatedBytes()
	{
		long bytes = 0L;
		for (final String text : this.texts)
		{
			bytes += 56L + 2L * text.length() + 8L + 48L;
		}
		return bytes;
	}

	/** The value of {@code pText} when it is a canonical decimal of at most 18 digits, otherwise -1. */
	static long canonicalNumber(final String pText)
	{
		final int length = pText.length();
		if (length > KeyCodec.MAX_DIGITS || (length > 1 && pText.charAt(0) == '0'))
		{
			return -1L;
		}
		long value = 0L;
		for (int i = 0; i < length; i++)
		{
			final char c = pText.charAt(i);
			if (c < '0' || c > '9')
			{
				return -1L;
			}
			value = value * 10L + (c - '0');
		}
		return value;
	}
}
