package com.on.ebx.factory.core;

/** JUnit-free: run the main, a failure throws. {@link ScopeParam} (2026-10-12): an unknown scope word is refused, never read as the default. */
public final class ScopeParamTest
{
	public static void main(final String[] pArgs)
	{
		check("all", "all".equals(ScopeParam.parse("all", "incremental", "all")));
		check("any case, padded", "all".equals(ScopeParam.parse("  ALL ", "incremental", "all")));
		check("the default word itself", "incremental".equals(ScopeParam.parse("Incremental", "incremental", "all")));
		check("null is 'not given'", ScopeParam.parse(null, "incremental", "all") == null);
		check("blank is 'not given'", ScopeParam.parse("   ", "incremental", "all") == null);
		check("empty is 'not given'", ScopeParam.parse("", "new", "all") == null);

		// the live bug: matching/run?scope=full was read as incremental
		final String full = refused("full", "incremental", "all");
		check("'full' is refused on matching/run", full != null);
		check("the refusal names the value", full.contains("'full'"));
		check("the refusal names both accepted values", full.contains("'incremental' and 'all'"));
		check("a typo is refused", refused("alll", "new", "all") != null);
		check("'everything' is refused", refused("everything", "new", "all") != null);
		check("'all' is not a master scope", refused("all", "full", "changed") != null);
		check("'new' is not a distribution scope", refused("new", "changed", "all") != null);
		check("three words are listed with a comma", ScopeParam.quoted("a", "b", "c").equals("'a', 'b' and 'c'"));
		check("one word", ScopeParam.quoted("a").equals("'a'"));
		check("a very long value is cut in the sentence", ScopeParam.refusal(new String(new char[500]).replace('\0', 'x'), "a").length() < 120);
		System.out.println("ScopeParamTest OK");
	}

	private static String refused(final String pValue, final String... pAccepted)
	{
		try
		{
			ScopeParam.parse(pValue, pAccepted);
			return null;
		}
		catch (final IllegalArgumentException ex)
		{
			return ex.getMessage();
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new IllegalStateException("ScopeParamTest: " + pWhat);
		}
	}
}
