package com.on.ebx.factory.ebx.ui;

/**
 * JUnit-free: run the main, a failure throws. Robustness audit F2-105: when the cockpit's aggregate of unresolved rejects fails, the breakdown comes from the sample of latest
 * rejects and the snapshot says so ({@code rejectSummaryPartial}); a normal run, or a fixture that simply has no aggregate, does not claim it.
 */
public final class CockpitRejectSummaryPartialTest
{
	public static void main(final String[] pArgs)
	{
		final CockpitData.Inputs failed = CockpitRejectSummaryPartialTest.inputs();
		failed.rejectSummaries = null;
		failed.rejectSummaryPartial = true;
		CockpitRejectSummaryPartialTest.check("a failed aggregate is flagged partial", CockpitData.compute(failed).rejectSummaryPartial);

		final CockpitData.Inputs fixture = CockpitRejectSummaryPartialTest.inputs();
		CockpitRejectSummaryPartialTest.check("no aggregate and no failure (old fixture) is not flagged", !CockpitData.compute(fixture).rejectSummaryPartial);

		final CockpitData.Inputs ok = CockpitRejectSummaryPartialTest.inputs();
		ok.rejectSummaries = new java.util.ArrayList<CockpitData.RejectSummary>();
		ok.rejectSummaries.add(new CockpitData.RejectSummary("Supplier", "STAGING", 4));
		ok.rejectSummaryPartial = true; // a stale flag never marks a real aggregate
		CockpitRejectSummaryPartialTest.check("a real aggregate is never partial", !CockpitData.compute(ok).rejectSummaryPartial);
		System.out.println("CockpitRejectSummaryPartialTest OK");
	}

	private static CockpitData.Inputs inputs()
	{
		final CockpitData.Inputs in = new CockpitData.Inputs();
		in.now = new java.util.Date();
		return in;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
