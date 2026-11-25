package com.on.ebx.factory.master;

import com.on.ebx.factory.master.MasterResetEligibility.Decision;

/** JUnit-free: run the main, a failure throws. */
public final class MasterResetEligibilityTest
{
	private static final String CODE = "COMPANIES360";
	private static final String COMPUTED_DATASPACE = "COMPANIES360_Master";
	private static final String COMPUTED_DATASET = "Master";

	public static void main(final String[] pArgs)
	{
		MasterResetEligibilityTest.notGreenfieldRefused();
		MasterResetEligibilityTest.blankMasterZoneRefused();
		MasterResetEligibilityTest.foreignDataspaceNameRefused();
		MasterResetEligibilityTest.noStepEvidenceRefused();
		MasterResetEligibilityTest.datasetNotCreatedByFactoryRefused();
		MasterResetEligibilityTest.metadatasetExistsRefused();
		MasterResetEligibilityTest.missingConfirmRefused();
		MasterResetEligibilityTest.wrongConfirmRefused();
		MasterResetEligibilityTest.everythingAlignedAllowed();
		System.out.println("MasterResetEligibilityTest OK");
	}

	private static void notGreenfieldRefused()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "TARGET_EXISTS", COMPUTED_DATASPACE, COMPUTED_DATASET, COMPUTED_DATASPACE, COMPUTED_DATASET, true,
			true, false, COMPUTED_DATASPACE);
		MasterResetEligibilityTest.check("TARGET_EXISTS refused", !d.allowed);
		MasterResetEligibilityTest.check("names GREENFIELD", d.refusal.contains("GREENFIELD"));
	}

	private static void blankMasterZoneRefused()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "GREENFIELD", null, null, COMPUTED_DATASPACE, COMPUTED_DATASET, true, true, false,
			COMPUTED_DATASPACE);
		MasterResetEligibilityTest.check("blank master zone refused", !d.allowed);
		MasterResetEligibilityTest.check("says nothing to reset", d.refusal.contains("no master zone"));
	}

	/** The demo project's own hand-typed TestMasterDataspace/TestMasterDataset - never a reset candidate. */
	private static void foreignDataspaceNameRefused()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "GREENFIELD", "TestMasterDataspace", "TestMasterDataset", COMPUTED_DATASPACE, COMPUTED_DATASET,
			true, true, false, "TestMasterDataspace");
		MasterResetEligibilityTest.check("foreign name refused", !d.allowed);
		MasterResetEligibilityTest.check("names what was found and what would be generated",
			d.refusal.contains("TestMasterDataspace") && d.refusal.contains(COMPUTED_DATASPACE));
	}

	private static void noStepEvidenceRefused()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "GREENFIELD", COMPUTED_DATASPACE, COMPUTED_DATASET, COMPUTED_DATASPACE, COMPUTED_DATASET, false,
			false, false, COMPUTED_DATASPACE);
		MasterResetEligibilityTest.check("no step evidence refused", !d.allowed);
	}

	/** The dataspace name matches, but PipelineStep evidence says the factory only ever FOUND it, never created it. */
	private static void datasetNotCreatedByFactoryRefused()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "GREENFIELD", COMPUTED_DATASPACE, COMPUTED_DATASET, COMPUTED_DATASPACE, COMPUTED_DATASET, true,
			false, false, COMPUTED_DATASPACE);
		MasterResetEligibilityTest.check("not-created-by-factory refused", !d.allowed);
	}

	/**
	 * The caller ({@code ebx.MasterReset}) now passes {@code true} as soon as ANY promoted table has a
	 * metadataset, not one computed from the dataspace/dataset alone (2026-09-22, `master.MameMetadataset`
	 * fix) - this decision only ever sees the resulting boolean, so its own test still just exercises that,
	 * but the refusal text must no longer claim a single, specific (and wrong) metadataset name.
	 */
	private static void metadatasetExistsRefused()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "GREENFIELD", COMPUTED_DATASPACE, COMPUTED_DATASET, COMPUTED_DATASPACE, COMPUTED_DATASET, true,
			true, true, COMPUTED_DATASPACE);
		MasterResetEligibilityTest.check("metadataset exists: refused", !d.allowed);
		MasterResetEligibilityTest.check("mentions match/merge history", d.refusal.toLowerCase().contains("match"));
		MasterResetEligibilityTest.check("does not claim the old, wrong single metadataset name",
			!d.refusal.contains(COMPUTED_DATASPACE + "_" + COMPUTED_DATASET + "_MDS"));
	}

	private static void missingConfirmRefused()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "GREENFIELD", COMPUTED_DATASPACE, COMPUTED_DATASET, COMPUTED_DATASPACE, COMPUTED_DATASET, true,
			true, false, null);
		MasterResetEligibilityTest.check("missing confirm refused", !d.allowed);
		MasterResetEligibilityTest.check("tells the exact confirm value to pass", d.refusal.contains("confirm=" + COMPUTED_DATASPACE));
	}

	private static void wrongConfirmRefused()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "GREENFIELD", COMPUTED_DATASPACE, COMPUTED_DATASET, COMPUTED_DATASPACE, COMPUTED_DATASET, true,
			true, false, "SomethingElse");
		MasterResetEligibilityTest.check("wrong confirm refused", !d.allowed);
	}

	private static void everythingAlignedAllowed()
	{
		final Decision d = MasterResetEligibility.decide(CODE, "GREENFIELD", COMPUTED_DATASPACE, COMPUTED_DATASET, COMPUTED_DATASPACE, COMPUTED_DATASET, true,
			true, false, COMPUTED_DATASPACE);
		MasterResetEligibilityTest.check("allowed", d.allowed);
		MasterResetEligibilityTest.check("no refusal text", d.refusal == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
