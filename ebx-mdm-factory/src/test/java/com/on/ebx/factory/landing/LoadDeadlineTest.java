package com.on.ebx.factory.landing;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link LoadDeadline} (audit F-20). */
public final class LoadDeadlineTest
{
	public static void main(final String[] pArgs)
	{
		LoadDeadlineTest.check("no property reads the default of 12 hours", LoadDeadline.maxMillis(null) == 12L * 60L * 60000L);
		LoadDeadlineTest.check("a positive number of minutes is honoured", LoadDeadline.maxMillis(" 90 ") == 90L * 60000L);
		LoadDeadlineTest.check("zero is never 'no limit'", LoadDeadline.maxMillis("0") == LoadDeadline.DEFAULT_MAX_MINUTES * 60000L);
		LoadDeadlineTest.check("a negative value reads the default", LoadDeadline.maxMillis("-5") == LoadDeadline.DEFAULT_MAX_MINUTES * 60000L);
		LoadDeadlineTest.check("a typo reads the default, the guard stays on", LoadDeadline.maxMillis("two hours") == LoadDeadline.DEFAULT_MAX_MINUTES * 60000L);

		LoadDeadlineTest.check("not expired before the limit", !LoadDeadline.expired(1000L, 1000L + 59999L, 60000L));
		LoadDeadlineTest.check("expired at the limit", LoadDeadline.expired(1000L, 1000L + 60000L, 60000L));
		LoadDeadlineTest.check("expired well past the limit", LoadDeadline.expired(1000L, 1000L + 9999999L, 60000L));
		LoadDeadlineTest.check("a limit of zero never expires (not reachable through the property)", !LoadDeadline.expired(0L, Long.MAX_VALUE / 2, 0L));

		final String stopped = LoadDeadline.message("PERSON", "R-42", 725L * 60000L, 720L * 60000L, 1234L, true);
		LoadDeadlineTest.check("the message names the table, the run id, the wait and the limit",
			stopped.contains("PERSON") && stopped.contains("R-42") && stopped.contains("725 minute(s)") && stopped.contains("limit 720") && stopped.contains("1234 row(s)"));
		LoadDeadlineTest.check("it says the stop was asked", stopped.contains("asked to stop"));
		LoadDeadlineTest.check("it says when the stop itself failed", LoadDeadline.message("T", "R", 60000L, 60000L, 0L, false).contains("stopping the run FAILED"));
		System.out.println("LoadDeadlineTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
