package com.on.ebx.factory.ebx;

import java.util.List;

/** JUnit-free: run the main, a failure throws. Audit F-35: the pure parts of the queued "Change the key..." job. */
public final class LandingKeyChangeRunTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> columns = LandingKeyChangeRun.splitColumns(" a, b ,,c ,");
		LandingKeyChangeRunTest.check("trimmed, blanks dropped: " + columns, columns.size() == 3 && "a".equals(columns.get(0)) && "b".equals(columns.get(1)) && "c".equals(columns.get(2)));
		LandingKeyChangeRunTest.check("null is an empty list", LandingKeyChangeRun.splitColumns(null).isEmpty());
		LandingKeyChangeRunTest.check("blank is an empty list", LandingKeyChangeRun.splitColumns("  ").isEmpty());
		LandingKeyChangeRunTest.check("table named", LandingKeyChangeRun.paramsNameTable("{\"projectCode\":\"P\",\"tableId\":\"12\",\"columns\":\"a\"}", "12"));
		LandingKeyChangeRunTest.check("another table", !LandingKeyChangeRun.paramsNameTable("{\"tableId\":\"12\"}", "2"));
		LandingKeyChangeRunTest.check("12 is not 112", !LandingKeyChangeRun.paramsNameTable("{\"tableId\":\"112\"}", "12"));
		LandingKeyChangeRunTest.check("null params", !LandingKeyChangeRun.paramsNameTable(null, "12"));
		LandingKeyChangeRunTest.check("garbage params", !LandingKeyChangeRun.paramsNameTable("{nope", "12"));
		LandingKeyChangeRunTest.check("kind", "LANDING_KEY_CHANGE_RUN".equals(LandingKeyChangeRun.STEP));
		System.out.println("LandingKeyChangeRunTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
