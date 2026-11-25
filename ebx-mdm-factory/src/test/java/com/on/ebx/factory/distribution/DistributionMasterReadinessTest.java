package com.on.ebx.factory.distribution;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class DistributionMasterReadinessTest
{
	public static void main(final String[] pArgs)
	{
		DistributionMasterReadinessTest.stepDoneAloneIsReady();
		DistributionMasterReadinessTest.evidenceAloneIsReady();
		DistributionMasterReadinessTest.neitherIsNotReady();
		DistributionMasterReadinessTest.bothIsReady();
		System.out.println("DistributionMasterReadinessTest OK");
	}

	/** The ordinary case: the MASTER_LOAD PipelineStep reports DONE. */
	private static void stepDoneAloneIsReady()
	{
		DistributionMasterReadinessTest.check("step DONE alone is ready", DistributionMasterReadiness.ready(true, false));
	}

	/** DistribTest live incident, 2026-09-25: the step row is missing/unmatched, but the master zone genuinely holds data. */
	private static void evidenceAloneIsReady()
	{
		DistributionMasterReadinessTest.check("direct master-zone evidence alone is ready", DistributionMasterReadiness.ready(false, true));
	}

	private static void neitherIsNotReady()
	{
		DistributionMasterReadinessTest.check("neither signal is not ready", !DistributionMasterReadiness.ready(false, false));
	}

	private static void bothIsReady()
	{
		DistributionMasterReadinessTest.check("both signals is ready", DistributionMasterReadiness.ready(true, true));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
