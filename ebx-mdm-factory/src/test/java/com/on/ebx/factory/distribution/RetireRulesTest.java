package com.on.ebx.factory.distribution;

import com.on.ebx.factory.distribution.RetireRules.Outcome;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class RetireRulesTest
{
	public static void main(final String[] pArgs)
	{
		RetireRulesTest.mergedRetiresToItsGolden();
		RetireRulesTest.mergedWithNoGoldenKeyRetiresWithNoSurvivor();
		RetireRulesTest.unmergeDeletedAutoCreatedRetiresWithNoSurvivor();
		RetireRulesTest.deletedButNotAutoCreatedDoesNotRetire();
		RetireRulesTest.goldenDoesNotRetire();
		RetireRulesTest.pendingApprovalDoesNotRetire();
		RetireRulesTest.unsetDoesNotRetire();
		System.out.println("RetireRulesTest OK");
	}

	private static void mergedRetiresToItsGolden()
	{
		final Outcome o = RetireRules.evaluate(RetireRules.STATE_MERGED, "322", false);
		RetireRulesTest.check("MERGED retires", o.retire);
		RetireRulesTest.check("survivor is the golden key", "322".equals(o.survivorKey));
	}

	private static void mergedWithNoGoldenKeyRetiresWithNoSurvivor()
	{
		final Outcome o = RetireRules.evaluate(RetireRules.STATE_MERGED, null, false);
		RetireRulesTest.check("MERGED with no golden key still retires", o.retire);
		RetireRulesTest.check("no survivor known", o.survivorKey == null);
	}

	private static void unmergeDeletedAutoCreatedRetiresWithNoSurvivor()
	{
		final Outcome o = RetireRules.evaluate(RetireRules.STATE_DELETED, null, true);
		RetireRulesTest.check("an unmerge-deleted auto-created golden retires", o.retire);
		RetireRulesTest.check("with no survivor", o.survivorKey == null);
	}

	private static void deletedButNotAutoCreatedDoesNotRetire()
	{
		final Outcome o = RetireRules.evaluate(RetireRules.STATE_DELETED, null, false);
		RetireRulesTest.check("DELETED on a record that was never auto-created is not a distribution retire", !o.retire);
	}

	private static void goldenDoesNotRetire()
	{
		RetireRulesTest.check("GOLDEN never retires", !RetireRules.evaluate(RetireRules.STATE_GOLDEN, null, false).retire);
	}

	private static void pendingApprovalDoesNotRetire()
	{
		RetireRulesTest.check("PENDING_APPROVAL never retires", !RetireRules.evaluate(RetireRules.STATE_PENDING_APPROVAL, null, false).retire);
	}

	private static void unsetDoesNotRetire()
	{
		RetireRulesTest.check("UNSET never retires", !RetireRules.evaluate(RetireRules.STATE_UNSET, null, false).retire);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
