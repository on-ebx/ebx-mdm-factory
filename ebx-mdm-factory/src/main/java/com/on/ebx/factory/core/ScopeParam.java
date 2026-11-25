package com.on.ebx.factory.core;

/**
 * 2026-10-12 (fix/scope-value-and-landing-selected): the strict reading of a {@code scope} parameter. Measured live that day:
 * {@code POST /projects/{code}/matching/run?scope=full} (the value {@code tools/itest/scenarios.py} sent) ran an INCREMENTAL run, because
 * the route only tested {@code "all".equalsIgnoreCase(scope)} and read every other word as the default - a typo silently chose the lighter
 * read. A route, a workflow bean or a task now accepts the words it documents and refuses anything else, naming the accepted ones.
 * Pure: no EBX class, unit-tested ({@code ScopeParamTest}).
 */
public final class ScopeParam
{
	private ScopeParam()
	{
	}

	/**
	 * The canonical (lower case) accepted word for {@code pValue}, compared case-insensitively with the surrounding blanks ignored;
	 * {@code null} when the value is null or blank (the caller applies its own default). Anything else is refused.
	 *
	 * @throws IllegalArgumentException when the value is not blank and is none of {@code pAccepted}; the message names the accepted words
	 */
	public static String parse(final String pValue, final String... pAccepted)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return null;
		}
		final String value = pValue.trim();
		for (final String accepted : pAccepted)
		{
			if (accepted.equalsIgnoreCase(value))
			{
				return accepted;
			}
		}
		throw new IllegalArgumentException(ScopeParam.refusal(value, pAccepted));
	}

	/** The sentence naming the refused value and the accepted ones: {@code Unknown scope 'full': accepted values are 'incremental' and 'all'.} */
	public static String refusal(final String pValue, final String... pAccepted)
	{
		return "Unknown scope '" + (pValue == null ? "" : pValue.length() <= 40 ? pValue : pValue.substring(0, 40) + "...") + "': accepted values are " + ScopeParam.quoted(pAccepted) + ".";
	}

	/** {@code 'a'}, {@code 'a' and 'b'}, {@code 'a', 'b' and 'c'}. */
	public static String quoted(final String... pAccepted)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pAccepted.length; i++)
		{
			if (i > 0)
			{
				out.append(i == pAccepted.length - 1 ? " and " : ", ");
			}
			out.append('\'').append(pAccepted[i]).append('\'');
		}
		return out.toString();
	}
}
