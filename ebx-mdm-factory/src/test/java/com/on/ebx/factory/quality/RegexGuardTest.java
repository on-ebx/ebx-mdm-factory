package com.on.ebx.factory.quality;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** JUnit-free: run the main, a failure throws. {@link RegexGuard}: the length cap, the catastrophic-shape refusal, the deadline. */
public final class RegexGuardTest
{
	public static void main(final String[] pArgs)
	{
		RegexGuardTest.lengthCap();
		RegexGuardTest.catastrophicShapesAreRefused();
		RegexGuardTest.ordinaryFormatsAreAccepted();
		RegexGuardTest.aRunawayMatchIsAbandoned();
		RegexGuardTest.normalMatchingIsUnchanged();
		System.out.println("RegexGuardTest OK");
	}

	private static void lengthCap()
	{
		final StringBuilder longest = new StringBuilder();
		for (int i = 0; i < RegexGuard.MAX_PATTERN_LENGTH; i++)
		{
			longest.append('a');
		}
		RegexGuardTest.check("a pattern of exactly the cap is accepted", RegexGuard.refusal(longest.toString()) == null);
		longest.append('a');
		RegexGuardTest.check("one more character is refused", RegexGuard.refusal(longest.toString()) != null);
		try
		{
			RegexGuard.compile(longest.toString());
			throw new AssertionError("compile accepted a too-long pattern");
		}
		catch (final RegexGuard.Refused ex)
		{
			RegexGuardTest.check("the refusal is a PatternSyntaxException with a clean message", ex instanceof PatternSyntaxException && ex.getMessage().contains("characters long"));
		}
	}

	private static void catastrophicShapesAreRefused()
	{
		for (final String pattern : new String[] { "(a+)+", "(a+)+$", "(.*)*", "(a|aa)+", "(\\w+\\s?)*", "(\\w+\\s?)+", "(a*)*", "(x+x+)+y", "(a+){2,}", "(?:a+)*", "(a{1,5})+",
			"(a+b+)*", "(a|b)*c", "((a+))+", "(?:FR|DE)+", "(a+$)+" })
		{
			RegexGuardTest.check(pattern + " is refused", RegexGuard.refusal(pattern) != null);
			try
			{
				RegexGuard.compile(pattern);
				throw new AssertionError(pattern + " compiled");
			}
			catch (final RegexGuard.Refused ex)
			{
				// expected
			}
		}
	}

	private static void ordinaryFormatsAreAccepted()
	{
		for (final String pattern : new String[] { "^[A-Z]{2}[0-9]{2}( ?[A-Z0-9]{4}){2,7}$", "^(\\d{3}[ -]?){2}\\d{4}$", "^\\+?(\\d[ .-]?){9,14}$",
			"[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?", "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", "^(FR|DE|IT)\\d+$", "\\(a+\\)+", "[(a+)+]", "^(a+)$", "(a+)?", "(ab){3}",
			"^\\d{5}(-\\d{4})?$", "(a{2})+", "^(?i)[a-z]+$", "(?<![A-Za-z0-9_])ab(?![A-Za-z0-9_])",
			// a repeated group whose body ends with a mandatory, non-repeated element (heuristic, see the class comment)
			"([a-z]+\\.)+", "([\\w-]+\\.)+", "(\\d{2}-)+", "^[\\w.+-]+@([\\w-]+\\.)+[\\w-]{2,}$", "^([A-Za-z0-9-]+\\.)+[a-z]{2,}$", "((a+)b)+", "(a+b)*", "(\\p{L}+,)+" })
		{
			RegexGuardTest.check(pattern + " is accepted: " + RegexGuard.refusal(pattern), RegexGuard.refusal(pattern) == null);
			RegexGuard.compile(pattern);
		}
		// a stored rule: only the wide length cap, whatever the shape; a pattern longer than a proposed one may be is still enforced
		RegexGuard.compileStored("(a+)+$");
		RegexGuard.compileStored("(?:FR|DE)+");
		RegexGuard.compileStored(new String(new char[RegexGuard.MAX_PATTERN_LENGTH + 1]).replace('\0', 'a'));
		try
		{
			RegexGuard.compileStored(new String(new char[RegexGuard.MAX_STORED_PATTERN_LENGTH + 1]).replace('\0', 'a'));
			throw new AssertionError("compileStored accepted a too-long pattern");
		}
		catch (final RegexGuard.Refused ex)
		{
			// expected
		}
		RegexGuardTest.check("null is not a refusal", RegexGuard.refusal(null) == null);
		try
		{
			RegexGuard.compile("[");
			throw new AssertionError("an invalid pattern compiled");
		}
		catch (final RegexGuard.Refused ex)
		{
			throw new AssertionError("an invalid pattern is the JDK's error, not a refusal");
		}
		catch (final PatternSyntaxException ex)
		{
			// expected: the JDK's own
		}
	}

	private static void aRunawayMatchIsAbandoned()
	{
		// a pattern the shape test does not see (a fixed outer count), run on a value that nearly matches: exponential in the JDK
		final Pattern evasive = Pattern.compile("^(.*a){28}$");
		final StringBuilder value = new StringBuilder();
		for (int i = 0; i < 40; i++)
		{
			value.append('a');
		}
		value.append('!');
		final long start = System.nanoTime();
		try
		{
			RegexGuard.matches(evasive, value.toString(), 100L);
			throw new AssertionError("a runaway match finished");
		}
		catch (final RegexGuard.MatchTimeout ex)
		{
			final long millis = (System.nanoTime() - start) / 1000000L;
			RegexGuardTest.check("abandoned within a second of a 100 ms budget (took " + millis + " ms)", millis < 1000L);
		}
		// the classic shape, bypassing compile()'s refusal on purpose: the deadline alone stops it (on a JDK that memoizes simple loops this one
		// may finish by itself - the assertion is then only that it never runs past the budget by much)
		final Pattern classic = Pattern.compile("(x+x+)+y");
		final StringBuilder xs = new StringBuilder();
		for (int i = 0; i < 60; i++)
		{
			xs.append('x');
		}
		final long classicStart = System.nanoTime();
		try
		{
			RegexGuard.matches(classic, xs.toString(), 100L);
		}
		catch (final RegexGuard.MatchTimeout ex)
		{
			// expected on most JDKs
		}
		RegexGuardTest.check("never runs on past the budget", (System.nanoTime() - classicStart) / 1000000L < 1000L);
	}

	private static void normalMatchingIsUnchanged()
	{
		final Pattern iban = RegexGuard.compile("^[A-Z]{2}[0-9]{2}( ?[A-Z0-9]{4}){2,7}$");
		RegexGuardTest.check("a conforming value matches", RegexGuard.matches(iban, "FR76 3000 6000 0112 3456 7890"));
		RegexGuardTest.check("a non-conforming value does not", !RegexGuard.matches(iban, "FR76 NOPE"));
		final java.util.regex.Matcher matcher = RegexGuard.matcher(Pattern.compile("^Dr (\\w+)"), "Dr Smith");
		RegexGuardTest.check("find() and group() read the value", matcher.find() && "Smith".equals(matcher.group(1)));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
