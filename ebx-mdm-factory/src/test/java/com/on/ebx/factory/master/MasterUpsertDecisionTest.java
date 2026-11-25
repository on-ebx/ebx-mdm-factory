package com.on.ebx.factory.master;

import com.on.ebx.factory.master.MasterUpsertDecision.Action;

/** JUnit-free: run the main, a failure throws. */
public final class MasterUpsertDecisionTest
{
	public static void main(final String[] pArgs)
	{
		MasterUpsertDecisionTest.check("no Xref.masterKey yet: INSERT", MasterUpsertDecision.decide(false, false) == Action.INSERT);
		// mergedAway is only ever consulted once hasMasterKey is true - still passed true here to make sure it is
		// simply ignored (a false hasMasterKey means "never reached master", merge state is moot)
		MasterUpsertDecisionTest.check("no Xref.masterKey yet, mergedAway ignored: still INSERT", MasterUpsertDecision.decide(false, true) == Action.INSERT);
		MasterUpsertDecisionTest.check("masterKey exists, not merged away: UPDATE", MasterUpsertDecision.decide(true, false) == Action.UPDATE);
		MasterUpsertDecisionTest.check("masterKey exists, merged away: SKIP_MERGED, never blindly overwritten", MasterUpsertDecision.decide(true, true) == Action.SKIP_MERGED);
		System.out.println("MasterUpsertDecisionTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
