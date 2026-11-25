package com.on.ebx.factory.distribution;

import java.util.List;

/**
 * Distribution lot 1 (engine, coordinator review 2026-09-24, item 1): the exact escaping/joining algorithm
 * {@code com.onwbp.adaptation.PrimaryKey} uses to format a composite key - {@code \} doubled first, then
 * {@code |} escaped, fields joined by an unescaped {@code |} (CLAUDE.md sec 9: "Never build or split a
 * composite primary key as a string" - EBX escapes a {@code |} inside a value with {@code \} and a
 * {@code \} with {@code \\}). Pure - no EBX class, unit-tested directly, including a value that itself
 * contains a pipe.
 * <p>
 * <b>This algorithm is documentation and a fallback, not what the generated trigger runs.</b> The trigger
 * body ({@code DistributionTriggerBody}) uses EBX's OWN {@code core.complex.formatPrimaryKey} on a typed
 * primary-key value built from the record's actual key fields - guaranteed byte-for-byte compatible with
 * {@code PrimaryKey.parseString} on the Java side, since it IS that same class, and avoids reproducing
 * fragile double-escaped regex text inside a generated EBX-Script string (CLAUDE.md sec 2's own repeated
 * escaping traps). This class exists so the algorithm itself is written down, tested, and available to any
 * future caller that ever needs to build or verify a formatted key from Java rather than from EBX Script.
 */
public final class PrimaryKeyFormat
{
	private PrimaryKeyFormat()
	{
	}

	/** Escapes one key part: backslash first, then the separator - order matters (escaping the separator first would double-escape a backslash that precedes it). */
	public static String escape(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		return pValue.replace("\\", "\\\\").replace("|", "\\|");
	}

	/** Joins already-{@link #escape}d parts with the separator {@code |}. Never call on un-escaped parts. */
	public static String format(final List<String> pValues)
	{
		final StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (final String value : pValues)
		{
			if (!first)
			{
				sb.append('|');
			}
			first = false;
			sb.append(PrimaryKeyFormat.escape(value));
		}
		return sb.toString();
	}
}
