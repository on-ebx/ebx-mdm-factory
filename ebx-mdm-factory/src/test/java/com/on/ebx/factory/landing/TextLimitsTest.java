package com.on.ebx.factory.landing;

/** JUnit-free: run the main, a failure throws. */
public final class TextLimitsTest
{
	public static void main(final String[] pArgs)
	{
		TextLimitsTest.check("null stays null", TextLimits.truncate(null, 10) == null);
		TextLimitsTest.check("short text is untouched", "abc".equals(TextLimits.truncate("abc", 10)));
		TextLimitsTest.check("text exactly at the limit is untouched", "0123456789".equals(TextLimits.truncate("0123456789", 10)));

		final String truncated = TextLimits.truncate(TextLimitsTest.repeat('a', 100), 20);
		TextLimitsTest.check("truncated text fits the limit exactly: " + truncated.length(), truncated.length() == 20);
		TextLimitsTest.check("truncated text carries the marker", truncated.endsWith("...(truncated)"));

		final String tiny = TextLimits.truncate(TextLimitsTest.repeat('a', 100), 5);
		TextLimitsTest.check("a limit shorter than the marker never grows past it: " + tiny.length(), tiny.length() <= 5);
		TextLimitsTest.check("a limit of 0 gives the empty string", "".equals(TextLimits.truncate("abc", 0)));

		System.out.println("TextLimitsTest OK");
	}

	private static String repeat(final char pChar, final int pCount)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pCount; i++)
		{
			out.append(pChar);
		}
		return out.toString();
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
