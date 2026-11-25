package com.on.ebx.factory.quality;

/**
 * Turns a real value into a value-blind SHAPE: every letter becomes {@code A} (upper case) or {@code a}
 * (lower case), every digit becomes {@code 9}, and everything else - spaces, punctuation, the "before -&gt;
 * after" arrow a caller may have already joined in - is kept exactly as written. A run of more than 4
 * identical shape characters (a long stretch of digits or letters with no separator - an IBAN's digit block,
 * a long identifier, a name) is collapsed to {@code <char>(<count>)} so the evidence still shows roughly how
 * long the run was without spelling out that many repeated placeholder characters for no reason; a run of 4
 * or fewer is left exactly as produced (see {@code CleaningStepsTest}/the class's own {@code ShapesTest} for
 * the exact IBAN grouping example this preserves untouched).
 * <p>
 * <b>Why this exists (coordinator review, 2026-09-20, of the first cut of this lot)</b>: the first version
 * decided whether to show a real value or a redacted one from a GUESSED list of catalog-tag / table-name
 * substrings - a heuristic that, missed once, puts a real name, e-mail, phone number or identifier into a
 * proposals table, a review screen, and any export of either. This class makes the decision safe BY
 * CONSTRUCTION instead: {@link Detectors}/{@link QualityDetectors} decide whether a value is shown at all
 * from the STEP/RULE KIND alone (a fixed, reviewed allow-list of kinds whose values are reference vocabulary
 * by nature - a country name, an ISO currency code, a legal-form abbreviation, a fixed empty-ish sentinel
 * token; docs/NORMALISATION.md has the exact list and the reasoning) - and for every other kind, the value
 * never leaves this class un-shaped, whatever a catalog tag does or does not say. Pure: no EBX class.
 */
public final class Shapes
{
	/** A run of the same shape character longer than this many characters is collapsed to a count instead of spelled out. */
	private static final int COLLAPSE_THRESHOLD = 4;

	private Shapes()
	{
	}

	/** {@code null} in, {@code null} out (nothing to shape); never throws on any input otherwise. */
	public static String of(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final StringBuilder raw = new StringBuilder(pValue.length());
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			if (Character.isDigit(c))
			{
				raw.append('9');
			}
			else if (Character.isUpperCase(c))
			{
				raw.append('A');
			}
			else if (Character.isLowerCase(c) || Character.isLetter(c))
			{
				// security review (2026-09-24): a letter WITHOUT case (Chinese, Japanese, Arabic, Hebrew, Thai...) is
				// still a letter - it used to fall through to the verbatim branch below, so values written in those
				// scripts reached the language model unmasked
				raw.append('a');
			}
			else if (Character.isSurrogate(c))
			{
				raw.append('a'); // half of a supplementary character (rare CJK, emoji): never passed through verbatim
			}
			else
			{
				raw.append(c); // punctuation, space, symbol: kept exactly as written (the shape IS the separators)
			}
		}
		return Shapes.collapseRuns(raw.toString());
	}

	/** "before -&gt; after" of two already-real values, shaped independently (never the joined string as a whole, so the arrow itself is never mistaken for part of either value). */
	public static String pair(final String pBefore, final String pAfter)
	{
		return Shapes.of(pBefore) + " -> " + Shapes.of(pAfter);
	}

	/** A run of the same {@code A}/{@code a}/{@code 9} character longer than {@link #COLLAPSE_THRESHOLD} becomes {@code <char>(<runLength>)}; punctuation/space runs, and any run of {@link #COLLAPSE_THRESHOLD} or fewer, are left exactly as produced by {@link #of}. */
	private static String collapseRuns(final String pShape)
	{
		final StringBuilder out = new StringBuilder(pShape.length());
		final int length = pShape.length();
		int i = 0;
		while (i < length)
		{
			final char c = pShape.charAt(i);
			int j = i;
			while (j < length && pShape.charAt(j) == c)
			{
				j++;
			}
			final int runLength = j - i;
			if ((c == 'A' || c == 'a' || c == '9') && runLength > Shapes.COLLAPSE_THRESHOLD)
			{
				out.append(c).append('(').append(runLength).append(')');
			}
			else
			{
				for (int k = 0; k < runLength; k++)
				{
					out.append(c);
				}
			}
			i = j;
		}
		return out.toString();
	}
}
