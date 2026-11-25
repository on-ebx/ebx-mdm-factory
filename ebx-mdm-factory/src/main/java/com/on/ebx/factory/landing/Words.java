package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a source identifier into lower-case words, so {@link RelationRule} can compare names word by word
 * instead of as flattened letters (losing every word boundary would let {@code SUBACCOUNT_ID} pass for a
 * reference to a table keyed {@code ACCOUNT_ID}, since the flattened forms happen to share a tail).
 * <ul>
 * <li>Every non-alphanumeric character is a separator, dropped: {@code BILL_TO_ACCOUNT_ID} -&gt; bill, to,
 * account, id.</li>
 * <li>Inside a run of letters/digits, a lower-to-upper transition starts a new word:
 * {@code parentAccountId} -&gt; parent, account, id.</li>
 * <li>A run of capitals stays one word up to, but not including, the last capital of the run when that
 * capital starts a following lower-case word - the usual acronym reading: {@code VATNumber} -&gt; vat,
 * number (not v, a, t, number).</li>
 * <li>A digit stays attached to the word it follows: {@code LINE1} -&gt; line1 (one word, not line, 1).</li>
 * </ul>
 * Pure: no EBX class. {@link SourceKeyRule} keeps its own, separate, separator-blind flattening
 * ({@code letters}) - it only ever tests whole-name equality, where losing word boundaries is harmless;
 * this class exists because {@link RelationRule}'s suffix rule is not safe without them.
 * <p>
 * Public: {@code com.on.ebx.factory.staging.StagingRules} reuses the same word split to build entity and
 * attribute names from landing table/column names (PascalCase, camelCase), rather than re-deriving its
 * own, slightly different, notion of a word boundary.
 */
public final class Words
{
	private Words()
	{
	}

	public static List<String> of(final String pName)
	{
		final List<String> out = new ArrayList<String>();
		if (pName == null)
		{
			return out;
		}
		final int length = pName.length();
		final StringBuilder word = new StringBuilder();
		for (int i = 0; i < length; i++)
		{
			final char c = pName.charAt(i);
			if (!Character.isLetterOrDigit(c))
			{
				Words.flush(out, word);
				continue;
			}
			if (word.length() > 0 && Words.isBoundary(word.charAt(word.length() - 1), c, i + 1 < length ? pName.charAt(i + 1) : '\0'))
			{
				Words.flush(out, word);
			}
			word.append(c);
		}
		Words.flush(out, word);
		return out;
	}

	/** A new word starts at {@code pCurrent} when it follows a lower-case letter, or when it is the last capital of an acronym run (the next
	 * character starts a lower-case word). Never before a digit: a digit stays attached to what precedes it. */
	private static boolean isBoundary(final char pPrevious, final char pCurrent, final char pNext)
	{
		if (!Character.isUpperCase(pCurrent))
		{
			return false;
		}
		if (Character.isLowerCase(pPrevious))
		{
			return true;
		}
		return Character.isUpperCase(pPrevious) && Character.isLowerCase(pNext);
	}

	private static void flush(final List<String> pOut, final StringBuilder pWord)
	{
		if (pWord.length() > 0)
		{
			pOut.add(pWord.toString().toLowerCase(Locale.ROOT));
			pWord.setLength(0);
		}
	}
}
