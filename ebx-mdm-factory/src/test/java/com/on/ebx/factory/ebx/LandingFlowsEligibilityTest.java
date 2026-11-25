package com.on.ebx.factory.ebx;

/**
 * JUnit-free, like the rest of this package's tests. Covers {@link LandingFlows#isFlowEligibleStatus} - the
 * pure rule behind {@code FLOW_ELIGIBLE_STATUSES}, extracted from {@code LandingFlows.flowTables} for exactly
 * this test. Volume-test bug (project PersVol, 2026-09-24): after a landing run where every FLOW cell
 * failed, the {@code LandingTable} rows stayed status FAILED and a retry of {@code POST .../landing/flows}
 * answered 409 "no generated landing table selected" even though the landing model and dataset already
 * existed - see {@link LandingFlows#FLOW_ELIGIBLE_STATUSES}'s own Javadoc for why FAILED always means "the
 * flow or the load failed, never the model/dataset step" and is therefore safe to retry.
 */
public final class LandingFlowsEligibilityTest
{
	public static void main(final String[] pArgs)
	{
		LandingFlowsEligibilityTest.generatedLoadedDriftedAreEligible();
		LandingFlowsEligibilityTest.failedIsNowEligibleAgain();
		LandingFlowsEligibilityTest.proposedStaysIneligible();
		LandingFlowsEligibilityTest.unknownOrNullStatusIsIneligible();

		System.out.println("LandingFlowsEligibilityTest OK");
	}

	private static void generatedLoadedDriftedAreEligible()
	{
		LandingFlowsEligibilityTest.check("GENERATED is eligible", LandingFlows.isFlowEligibleStatus("GENERATED"));
		LandingFlowsEligibilityTest.check("LOADED is eligible", LandingFlows.isFlowEligibleStatus("LOADED"));
		LandingFlowsEligibilityTest.check("DRIFTED is eligible", LandingFlows.isFlowEligibleStatus("DRIFTED"));
	}

	/**
	 * The actual bug fix: a table whose model/dataset exist and whose only failure was the flow (or load)
	 * step must be eligible for the flow step again, without a person first having to re-run the landing
	 * plan and model/dataset steps as a workaround.
	 */
	private static void failedIsNowEligibleAgain()
	{
		LandingFlowsEligibilityTest.check("FAILED is eligible again (flow/load failure, model/dataset already exist)", LandingFlows.isFlowEligibleStatus("FAILED"));
	}

	/**
	 * A table whose MODEL step itself failed never reaches an individual FAILED {@code LandingTable} row -
	 * generation stops before any per-table write-back and the row is left at its PROPOSED default (see
	 * {@code FLOW_ELIGIBLE_STATUSES}'s own Javadoc) - so PROPOSED staying excluded is what keeps such a table
	 * ineligible, not a special case of FAILED.
	 */
	private static void proposedStaysIneligible()
	{
		LandingFlowsEligibilityTest.check("PROPOSED (no EBX table yet, or the model step never wrote this row back) stays ineligible",
			!LandingFlows.isFlowEligibleStatus("PROPOSED"));
	}

	private static void unknownOrNullStatusIsIneligible()
	{
		LandingFlowsEligibilityTest.check("an unrecognised status is not eligible", !LandingFlows.isFlowEligibleStatus("SOMETHING_ELSE"));
		LandingFlowsEligibilityTest.check("a null status is not eligible", !LandingFlows.isFlowEligibleStatus(null));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
