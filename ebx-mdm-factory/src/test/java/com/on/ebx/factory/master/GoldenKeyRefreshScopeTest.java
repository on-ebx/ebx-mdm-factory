package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class GoldenKeyRefreshScopeTest
{
	public static void main(final String[] pArgs)
	{
		GoldenKeyRefreshScopeTest.nullKeys_null();
		GoldenKeyRefreshScopeTest.emptyKeys_null();
		GoldenKeyRefreshScopeTest.blankKeysOnly_null();
		GoldenKeyRefreshScopeTest.oneKey_predicate();
		GoldenKeyRefreshScopeTest.severalKeys_orPredicate_blanksFiltered();
		GoldenKeyRefreshScopeTest.keyWithQuote_escaped();
		GoldenKeyRefreshScopeTest.keyWithWhitespace_trimmed();
		System.out.println("GoldenKeyRefreshScopeTest OK");
	}

	private static void nullKeys_null()
	{
		GoldenKeyRefreshScopeTest.check("null keys -> null predicate", GoldenKeyRefreshScope.xpathPredicate(null) == null);
	}

	private static void emptyKeys_null()
	{
		GoldenKeyRefreshScopeTest.check("empty keys -> null predicate", GoldenKeyRefreshScope.xpathPredicate(Collections.<String>emptySet()) == null);
	}

	private static void blankKeysOnly_null()
	{
		GoldenKeyRefreshScopeTest.check("blank-only keys -> null predicate",
			GoldenKeyRefreshScope.xpathPredicate(Arrays.asList("", "   ", null)) == null);
	}

	private static void oneKey_predicate()
	{
		final Set<String> keys = new LinkedHashSet<String>();
		keys.add("183");
		GoldenKeyRefreshScopeTest.check("single-key predicate", "(./masterKey='183')".equals(GoldenKeyRefreshScope.xpathPredicate(keys)));
	}

	private static void severalKeys_orPredicate_blanksFiltered()
	{
		final Set<String> keys = new LinkedHashSet<String>();
		keys.add("183");
		keys.add("141");
		keys.add("");
		keys.add("322");
		GoldenKeyRefreshScopeTest.check("or-predicate over the three real keys, blank filtered out",
			"(./masterKey='183' or ./masterKey='141' or ./masterKey='322')".equals(GoldenKeyRefreshScope.xpathPredicate(keys)));
	}

	private static void keyWithQuote_escaped()
	{
		final Set<String> keys = new LinkedHashSet<String>();
		keys.add("it's-odd");
		GoldenKeyRefreshScopeTest.check("apostrophe doubled", "(./masterKey='it''s-odd')".equals(GoldenKeyRefreshScope.xpathPredicate(keys)));
	}

	private static void keyWithWhitespace_trimmed()
	{
		final Set<String> keys = new LinkedHashSet<String>();
		keys.add("  183  ");
		GoldenKeyRefreshScopeTest.check("trimmed before quoting", "(./masterKey='183')".equals(GoldenKeyRefreshScope.xpathPredicate(keys)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
