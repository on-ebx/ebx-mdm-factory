package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. F2-06: a closing write of the staging load that failed is never a DONE step, and the message names what was not written. */
public final class StagingEndWritesTest
{
	public static void main(final String[] pArgs)
	{
		final StagingEndWrites clean = new StagingEndWrites();
		StagingEndWritesTest.check("nothing failed: DONE", "DONE".equals(clean.status(false, false)) && !clean.any() && clean.message().isEmpty());

		final StagingEndWrites refs = new StagingEndWrites();
		refs.referencesNotWritten("./parentRef", 500, "foreign key refused");
		StagingEndWritesTest.check("references not written: PARTIAL, never DONE", "PARTIAL".equals(refs.status(false, false)));
		StagingEndWritesTest.check("code, count and field in the message", refs.message().contains("STAGING_END_REFERENCES_NOT_WRITTEN") && refs.message().contains("500")
			&& refs.message().contains("./parentRef") && refs.message().contains("scope all"));
		StagingEndWritesTest.check("counted", refs.referenceRows() == 500 && refs.landingRows() == 0);

		final StagingEndWrites landing = new StagingEndWrites();
		landing.landingStatusNotWritten("CUSTOMER", 40, "boom");
		StagingEndWritesTest.check("landing status not written: PARTIAL", "PARTIAL".equals(landing.status(false, false)));
		StagingEndWritesTest.check("its own code, the table and what to run", landing.message().contains("STAGING_END_LANDING_STATUS_NOT_WRITTEN")
			&& landing.message().contains("CUSTOMER") && landing.message().contains("run the staging load again"));

		StagingEndWritesTest.check("a total failure stays FAILED", "FAILED".equals(refs.status(false, true)));
		StagingEndWritesTest.check("a stop stays STOPPED", "STOPPED".equals(refs.status(true, false)));

		final StagingEndWrites many = new StagingEndWrites();
		for (int i = 0; i < 6; i++)
		{
			many.landingStatusNotWritten("T" + i, 1, null);
		}
		StagingEndWritesTest.check("long lists are cut with a count", many.message().contains("and 3 more") && many.landingRows() == 6);
		System.out.println("StagingEndWritesTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
