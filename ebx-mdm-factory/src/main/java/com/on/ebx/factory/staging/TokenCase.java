package com.on.ebx.factory.staging;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The CASE LAYOUT of the multi-token values of a text column, as plain counts: how many values have an all-upper-case
 * first token, an all-upper-case last token, both, or neither. Pure and domain-free: it never says what a token is,
 * only where the capitals sit. A column whose values follow two layouts (PersVol7 volume test, 2026-09-28:
 * {@code ECOM.CUSTOMER.FULL_NAME} held {@code Aaaaaa Aaaaa} in 70% of rows and {@code AAAAAAAA Aaaaaaaa} in 30%) is
 * one whose values may be written in two orders - the model's rule must then read a token by its case, not by its
 * position ({@link StagingLlmPass#orderRefusal}). Counts and percentages only reach the model, never a value.
 */
public final class TokenCase
{
	/** A case layout followed by at least this share of the multi-token values is a POPULATION of its own - two of them make {@link #twoLayouts} true. */
	public static final int MIN_POPULATION_PERCENT = 15;

	/** Values counted (non-empty). */
	private long values;
	/** Values holding two tokens or more. */
	private long multiToken;
	/** Multi-token values whose first token is all upper case and last token is not. */
	private long firstUpperOnly;
	/** Multi-token values whose last token is all upper case and first token is not. */
	private long lastUpperOnly;
	/** Multi-token values whose first AND last tokens are all upper case. */
	private long bothUpper;
	/** Multi-token values with neither. */
	private long neitherUpper;
	/** Values (of any token count) whose every letter is upper case. */
	private long allUpper;
	/** Multi-token values whose every token is a word (letters, apostrophes, hyphens, dots - no digit, no other symbol). */
	private long alphabeticMulti;
	/** Sum of the token counts of the multi-token values. */
	private long tokenSum;

	public TokenCase()
	{
	}

	/** One measurement per value, each counted once. */
	public static TokenCase of(final List<String> pValues)
	{
		final TokenCase out = new TokenCase();
		if (pValues != null)
		{
			for (final String value : pValues)
			{
				out.add(value, 1L);
			}
		}
		return out;
	}

	/** Counts {@code pValue} {@code pCount} times (a distinct value with its frequency); a null or blank value is ignored. */
	public void add(final String pValue, final long pCount)
	{
		if (pValue == null || pValue.trim().isEmpty() || pCount <= 0)
		{
			return;
		}
		this.values += pCount;
		if (TokenCase.isAllUpper(pValue))
		{
			this.allUpper += pCount;
		}
		final List<String> tokens = TokenCase.tokens(pValue);
		if (tokens.size() < 2)
		{
			return;
		}
		this.multiToken += pCount;
		this.tokenSum += pCount * tokens.size();
		boolean alphabetic = true;
		for (final String token : tokens)
		{
			alphabetic &= TokenCase.isWord(token);
		}
		if (alphabetic)
		{
			this.alphabeticMulti += pCount;
		}
		final boolean first = TokenCase.isAllUpper(tokens.get(0));
		final boolean last = TokenCase.isAllUpper(tokens.get(tokens.size() - 1));
		if (first && last)
		{
			this.bothUpper += pCount;
		}
		else if (first)
		{
			this.firstUpperOnly += pCount;
		}
		else if (last)
		{
			this.lastUpperOnly += pCount;
		}
		else
		{
			this.neitherUpper += pCount;
		}
	}

	public long values()
	{
		return this.values;
	}

	public long multiToken()
	{
		return this.multiToken;
	}

	/** Share (0-100) of the multi-token values whose first token is all upper case (whatever the last one). */
	public int firstUpperPercent()
	{
		return this.percent(this.firstUpperOnly + this.bothUpper);
	}

	/** Share (0-100) of the multi-token values whose last token is all upper case (whatever the first one). */
	public int lastUpperPercent()
	{
		return this.percent(this.lastUpperOnly + this.bothUpper);
	}

	public int firstUpperOnlyPercent()
	{
		return this.percent(this.firstUpperOnly);
	}

	public int lastUpperOnlyPercent()
	{
		return this.percent(this.lastUpperOnly);
	}

	public int bothUpperPercent()
	{
		return this.percent(this.bothUpper);
	}

	public int neitherUpperPercent()
	{
		return this.percent(this.neitherUpper);
	}

	/** Share (0-100) of ALL the values (one token or more) whose every letter is upper case. */
	public int allUpperPercent()
	{
		return this.values == 0 ? 0 : (int) Math.round(100.0 * this.allUpper / this.values);
	}

	/** Whether at least half of the values hold two tokens or more - the only columns whose layout means anything. */
	public boolean mostlyMultiToken()
	{
		return this.values > 0 && this.multiToken * 2 >= this.values;
	}

	/**
	 * Whether the multi-token values follow TWO case layouts, each held by {@link #MIN_POPULATION_PERCENT} or more of
	 * them (and the column is {@link #mostlyMultiToken}): capitals on the first token in some values and on none in
	 * the others, on the first token here and the last there, everywhere and nowhere... Two layouts mean the values
	 * may be written in two orders; which order each layout follows is the model's call.
	 */
	public boolean twoLayouts()
	{
		if (!this.mostlyMultiToken())
		{
			return false;
		}
		int populations = 0;
		for (final long count : new long[] { this.firstUpperOnly, this.lastUpperOnly, this.bothUpper, this.neitherUpper })
		{
			if (this.percent(count) >= TokenCase.MIN_POPULATION_PERCENT)
			{
				populations++;
			}
		}
		return populations >= 2;
	}

	/** Share (0-100) of the multi-token values made only of words. */
	public int alphabeticPercent()
	{
		return this.percent(this.alphabeticMulti);
	}

	/** Mean token count of the multi-token values (0 when there is none). */
	public double meanTokens()
	{
		return this.multiToken == 0 ? 0.0 : (double) this.tokenSum / this.multiToken;
	}

	/**
	 * Whether the casing tells the parts of a multi-token value apart: an all-upper-case first token (and not the last),
	 * or last token (and not the first), in at least {@link #MIN_POPULATION_PERCENT} percent of the multi-token values.
	 * In a column where it does not (one layout everywhere, or only "both" / "neither"), nothing in the values says
	 * which part is which.
	 */
	public boolean casingSeparatesParts()
	{
		return this.firstUpperOnlyPercent() >= TokenCase.MIN_POPULATION_PERCENT || this.lastUpperOnlyPercent() >= TokenCase.MIN_POPULATION_PERCENT;
	}

	/**
	 * The ORDER-UNKNOWN case (lot {@code feat/name-order-unknown}, Vol100kN 2026-10-12: {@code LOYALTY.MEMBER.FULL_NAME},
	 * the whole name first-name-first in half the rows and last-name-first in the others, 92% one casing layout "Aaaa
	 * Aaaa", 8% all upper case): most values hold several words (at least {@link #ORDER_UNKNOWN_MIN_VALUES} of them, a
	 * mean of at most {@link #ORDER_UNKNOWN_MAX_MEAN_TOKENS} words each, {@link #ORDER_UNKNOWN_MIN_ALPHABETIC_PERCENT}
	 * percent or more made of words only - a street with a number or a free text is not one) and {@link
	 * #casingSeparatesParts} is false. Whatever order the values are written in, nothing measurable tells it, so a rule
	 * that reads a part by its position or its case is a guess on the rows written the other way. Domain-free: it says
	 * "parts", never what they are.
	 */
	public boolean orderUnknown()
	{
		return this.multiToken >= TokenCase.ORDER_UNKNOWN_MIN_VALUES && this.multiToken * 2 > this.values
			&& this.alphabeticPercent() >= TokenCase.ORDER_UNKNOWN_MIN_ALPHABETIC_PERCENT && this.meanTokens() <= TokenCase.ORDER_UNKNOWN_MAX_MEAN_TOKENS
			&& !this.casingSeparatesParts();
	}

	/** The casing layouts actually present, with their share: {@code neither the first nor the last token all upper case in 92%, the first and the last in 8%}. */
	public String renderLayouts()
	{
		final StringBuilder text = new StringBuilder();
		TokenCase.appendLayout(text, this.neitherUpperPercent(), "neither the first nor the last token all upper case");
		TokenCase.appendLayout(text, this.bothUpperPercent(), "the first and the last token all upper case");
		TokenCase.appendLayout(text, this.firstUpperOnlyPercent(), "only the first token all upper case");
		TokenCase.appendLayout(text, this.lastUpperOnlyPercent(), "only the last token all upper case");
		return text.toString();
	}

	/**
	 * What the staging pass tells the model about an {@link #orderUnknown} column (the exact text, pinned by the tests):
	 * {@code order of the parts cannot be inferred from casing: 94% of the values hold several words in one casing layout
	 * (neither ... in 92%, ... in 8%)}. Null when the order is not unknown.
	 */
	public String renderOrderEvidence()
	{
		if (!this.orderUnknown())
		{
			return null;
		}
		return "multi-token values in one casing layout, the order of the parts cannot be inferred from casing (" + this.renderLayouts() + ", mean "
			+ String.format(Locale.ROOT, "%.1f", this.meanTokens()) + " words per value): a rule that reads a part by its position or by its case is a guess "
			+ "for the rows written in the other order";
	}

	private static void appendLayout(final StringBuilder pText, final int pPercent, final String pLabel)
	{
		if (pPercent <= 0)
		{
			return;
		}
		if (pText.length() > 0)
		{
			pText.append(", ");
		}
		pText.append(pLabel).append(" in ").append(pPercent).append('%');
	}

	/** At least this many multi-token values, or {@link #orderUnknown} says nothing (a handful of values proves no layout). */
	public static final long ORDER_UNKNOWN_MIN_VALUES = 5;
	/** More words than this on average: free text, not a value made of a few parts. */
	public static final double ORDER_UNKNOWN_MAX_MEAN_TOKENS = 4.0;
	/** Share of the multi-token values made of words only (letters, apostrophes, hyphens, dots). */
	public static final int ORDER_UNKNOWN_MIN_ALPHABETIC_PERCENT = 80;

	/** A token made only of letters, apostrophes (straight or curly), hyphens and dots, with at least one letter. */
	static boolean isWord(final String pToken)
	{
		boolean letter = false;
		for (int i = 0; i < pToken.length(); i++)
		{
			final char c = pToken.charAt(i);
			if (Character.isLetter(c))
			{
				letter = true;
			}
			else if (c != '\'' && c != '\u2019' && c != '-' && c != '.')
			{
				return false;
			}
		}
		return letter;
	}

	/** The layout of one value: {@code first-only}, {@code last-only}, {@code both}, {@code neither}, or null for a value with fewer than two tokens. */
	public static String layoutOf(final String pValue)
	{
		final List<String> tokens = TokenCase.tokens(pValue);
		if (tokens.size() < 2)
		{
			return null;
		}
		final boolean first = TokenCase.isAllUpper(tokens.get(0));
		final boolean last = TokenCase.isAllUpper(tokens.get(tokens.size() - 1));
		if (first && last)
		{
			return "both";
		}
		return first ? "first-only" : (last ? "last-only" : "neither");
	}

	/** {@code first token all upper case in 34% of the 50 multi-token values, last token in 0%, both in 0%, neither in 66%}. */
	public String render()
	{
		return "first token all upper case in " + this.firstUpperOnlyPercent() + "% of the " + this.multiToken + " multi-token values, last token in "
			+ this.lastUpperOnlyPercent() + "%, both in " + this.bothUpperPercent() + "%, neither in " + this.neitherUpperPercent() + "%";
	}

	/** {@code all upper case in 34% of the 50 values} - and, when most values hold several tokens, {@link #render} after it: what a rule's OUTPUTS read as. */
	public String renderBrief()
	{
		final String brief = "all upper case in " + this.allUpperPercent() + "% of the " + this.values + " values";
		return this.mostlyMultiToken() ? brief + "; " + this.render() : brief;
	}

	/** Whitespace-separated tokens of a value, empty ones dropped. */
	public static List<String> tokens(final String pValue)
	{
		final List<String> out = new ArrayList<String>();
		if (pValue == null)
		{
			return out;
		}
		for (final String token : pValue.trim().split("\\s+"))
		{
			if (!token.isEmpty())
			{
				out.add(token);
			}
		}
		return out;
	}

	/** Whether every letter of {@code pToken} is upper case (a token without a letter is not). Accents are letters like any other. */
	public static boolean isAllUpper(final String pToken)
	{
		boolean letter = false;
		for (int i = 0; i < pToken.length(); i++)
		{
			final char c = pToken.charAt(i);
			if (Character.isLetter(c))
			{
				letter = true;
				if (!Character.isUpperCase(c))
				{
					return false;
				}
			}
		}
		return letter;
	}

	/** The letters and digits of a token, lower-cased, accents removed: what {@link StagingLlmPass#orderRefusal} compares an output with. */
	static String foldedLetters(final String pToken)
	{
		if (pToken == null)
		{
			return "";
		}
		return Normalizer.normalize(pToken, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	private int percent(final long pCount)
	{
		return this.multiToken == 0 ? 0 : (int) Math.round(100.0 * pCount / this.multiToken);
	}
}
