package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.MatchingConfigurator;

/**
 * JUnit-free: run the main, a failure throws (same convention as {@code
 * com.on.ebx.factory.ebx.MameMatchingApiTest}). Covers ONLY {@link MatchingRunTaskBean}'s two pure static
 * helpers, {@link MatchingRunTaskBean#isScopeAll} and {@link MatchingRunTaskBean#parseMaxWaitMs} -
 * {@link MatchingRunTaskBean#executeScript} itself needs a live EBX {@code Session}/{@code Repository}
 * (via a real {@code ScriptTaskBeanContext}) and is out of reach for {@code tools/test_local.sh}, same
 * discipline already used for every other EBX-dependent class in this module.
 */
public final class MatchingRunTaskBeanTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		MatchingRunTaskBeanTest.isScopeAll_null_isIncremental();
		MatchingRunTaskBeanTest.isScopeAll_blank_isIncremental();
		MatchingRunTaskBeanTest.isScopeAll_incremental_isIncremental();
		MatchingRunTaskBeanTest.isScopeAll_allCaseInsensitive_isAll();
		MatchingRunTaskBeanTest.isScopeAll_garbage_isRefused();
		MatchingRunTaskBeanTest.parseMaxWaitMs_null_usesDefault();
		MatchingRunTaskBeanTest.parseMaxWaitMs_blank_usesDefault();
		MatchingRunTaskBeanTest.parseMaxWaitMs_valid_isParsed();
		MatchingRunTaskBeanTest.parseMaxWaitMs_negative_isParsedAsIs();
		MatchingRunTaskBeanTest.parseMaxWaitMs_nonNumeric_usesDefault();
		System.out.println("MatchingRunTaskBeanTest OK");
	}

	private static void isScopeAll_null_isIncremental()
	{
		MatchingRunTaskBeanTest.check("null scope is incremental", !MatchingRunTaskBean.isScopeAll(null));
	}

	private static void isScopeAll_blank_isIncremental()
	{
		MatchingRunTaskBeanTest.check("blank scope is incremental", !MatchingRunTaskBean.isScopeAll("   "));
	}

	private static void isScopeAll_incremental_isIncremental()
	{
		MatchingRunTaskBeanTest.check("'incremental' scope is incremental", !MatchingRunTaskBean.isScopeAll("incremental"));
	}

	private static void isScopeAll_allCaseInsensitive_isAll()
	{
		MatchingRunTaskBeanTest.check("'all' scope is all", MatchingRunTaskBean.isScopeAll("all"));
		MatchingRunTaskBeanTest.check("'ALL' scope is all (case-insensitive, matching FactoryResource's equalsIgnoreCase)", MatchingRunTaskBean.isScopeAll("ALL"));
		MatchingRunTaskBeanTest.check("' All ' scope is all (trimmed)", MatchingRunTaskBean.isScopeAll(" All "));
	}

	private static void isScopeAll_garbage_isRefused()
	{
		boolean refused = false;
		try
		{
			MatchingRunTaskBean.isScopeAll("full");
		}
		catch (final IllegalArgumentException ex)
		{
			refused = ex.getMessage().contains("'incremental' and 'all'");
		}
		MatchingRunTaskBeanTest.check("an unknown scope ('full') is refused naming the two accepted values, not read as incremental", refused);
	}

	private static void parseMaxWaitMs_null_usesDefault()
	{
		MatchingRunTaskBeanTest.check("null maxWaitMs uses the default", MatchingRunTaskBean.parseMaxWaitMs(null) == MatchingConfigurator.DEFAULT_MAX_WAIT_MS);
	}

	private static void parseMaxWaitMs_blank_usesDefault()
	{
		MatchingRunTaskBeanTest.check("blank maxWaitMs uses the default", MatchingRunTaskBean.parseMaxWaitMs("   ") == MatchingConfigurator.DEFAULT_MAX_WAIT_MS);
	}

	private static void parseMaxWaitMs_valid_isParsed()
	{
		MatchingRunTaskBeanTest.check("a valid number is parsed as-is", MatchingRunTaskBean.parseMaxWaitMs("60000") == 60000L);
		MatchingRunTaskBeanTest.check("a valid number with surrounding whitespace is parsed", MatchingRunTaskBean.parseMaxWaitMs(" 1500 ") == 1500L);
	}

	private static void parseMaxWaitMs_negative_isParsedAsIs()
	{
		// no range validation here, same as the REST route's own bare pMaxWaitMs.longValue() - a caller
		// that hands a negative number gets exactly that value, MatchingConfigurator.run decides what to do with it
		MatchingRunTaskBeanTest.check("a negative number is parsed as-is, not defaulted", MatchingRunTaskBean.parseMaxWaitMs("-1") == -1L);
	}

	private static void parseMaxWaitMs_nonNumeric_usesDefault()
	{
		MatchingRunTaskBeanTest.check("a non-numeric string uses the default", MatchingRunTaskBean.parseMaxWaitMs("soon") == MatchingConfigurator.DEFAULT_MAX_WAIT_MS);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
