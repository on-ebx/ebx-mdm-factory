package com.on.ebx.factory.master;

import com.on.ebx.factory.master.MasterTargetDecision.Decision;

/** JUnit-free: run the main, a failure throws. */
public final class MasterTargetDecisionTest
{
	public static void main(final String[] pArgs)
	{
		Decision d = MasterTargetDecision.decide("COMPANIES360", null, null, "COMPANIES360_Master", "Master", false);
		MasterTargetDecisionTest.check("blank existing value: proceed", d.proceed);

		d = MasterTargetDecision.decide("COMPANIES360", "", "", "COMPANIES360_Master", "Master", false);
		MasterTargetDecisionTest.check("blank (empty string) existing value: proceed", d.proceed);

		d = MasterTargetDecision.decide("COMPANIES360", "COMPANIES360_Master", "Master", "COMPANIES360_Master", "Master", false);
		MasterTargetDecisionTest.check("already exactly the generated pair: proceed (idempotent)", d.proceed);

		d = MasterTargetDecision.decide("COMPANIES360", "TestMasterDataspace", "TestMasterDataset", "COMPANIES360_Master", "Master", false);
		MasterTargetDecisionTest.check("a foreign value, overwriteTarget not passed: refused", !d.proceed);
		MasterTargetDecisionTest.check("refusal names the project", d.refusal.contains("COMPANIES360"));
		MasterTargetDecisionTest.check("refusal names what was found", d.refusal.contains("TestMasterDataspace/TestMasterDataset"));
		MasterTargetDecisionTest.check("refusal names what would be generated", d.refusal.contains("COMPANIES360_Master/Master"));
		MasterTargetDecisionTest.check("refusal names the exact override", d.refusal.contains("overwriteTarget=true"));
		MasterTargetDecisionTest.check("refusal says only the two fields are replaced, nothing deleted",
			d.refusal.contains("FIELDS") && d.refusal.toLowerCase().contains("nothing is deleted"));

		d = MasterTargetDecision.decide("COMPANIES360", "TestMasterDataspace", "TestMasterDataset", "COMPANIES360_Master", "Master", true);
		MasterTargetDecisionTest.check("a foreign value, overwriteTarget=true: proceed", d.proceed);
		MasterTargetDecisionTest.check("no refusal when proceeding", d.refusal == null);

		System.out.println("MasterTargetDecisionTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
