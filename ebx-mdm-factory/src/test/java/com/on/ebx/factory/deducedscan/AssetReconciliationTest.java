package com.on.ebx.factory.deducedscan;

import java.util.Arrays;
import java.util.Collections;

/** JUnit-free: run the main, a failure throws. */
public final class AssetReconciliationTest
{
	public static void main(final String[] pArgs)
	{
		// same columns, whatever the order - a CONFIRMED decision survives untouched
		AssetReconciliation.Decision unchanged = AssetReconciliation.reconcile("CONFIRMED", Arrays.asList("id", "name"), Arrays.asList("name", "id"));
		AssetReconciliationTest.check("unchanged columns: not marked changed", !unchanged.columnsChanged);
		AssetReconciliationTest.check("unchanged columns: state kept", "CONFIRMED".equals(unchanged.scanState));
		AssetReconciliationTest.check("unchanged columns: no reason", unchanged.reason.isEmpty());

		// LEFT_OUT survives too - a person's decision is never reopened just because the source was re-scanned
		unchanged = AssetReconciliation.reconcile("LEFT_OUT", Arrays.asList("id"), Arrays.asList("id"));
		AssetReconciliationTest.check("LEFT_OUT kept when columns unchanged", "LEFT_OUT".equals(unchanged.scanState));

		// still-undecided stays TO_CONFIRM when nothing changed
		unchanged = AssetReconciliation.reconcile("TO_CONFIRM", Arrays.asList("id", "name"), Arrays.asList("id", "name"));
		AssetReconciliationTest.check("TO_CONFIRM kept when columns unchanged", "TO_CONFIRM".equals(unchanged.scanState));

		// a column was added - a CONFIRMED table must be looked at again
		AssetReconciliation.Decision changed = AssetReconciliation.reconcile("CONFIRMED", Arrays.asList("id", "name"), Arrays.asList("id", "name", "email"));
		AssetReconciliationTest.check("added column: marked changed", changed.columnsChanged);
		AssetReconciliationTest.check("added column: back to TO_CONFIRM", AssetReconciliation.TO_CONFIRM.equals(changed.scanState));
		AssetReconciliationTest.check("added column: reason names the previous state", changed.reason.contains("CONFIRMED"));

		// a column was removed - same rule
		changed = AssetReconciliation.reconcile("LEFT_OUT", Arrays.asList("id", "name"), Arrays.asList("id"));
		AssetReconciliationTest.check("removed column: back to TO_CONFIRM even from LEFT_OUT", AssetReconciliation.TO_CONFIRM.equals(changed.scanState));

		// no prior state recorded (a blank/legacy row) never crashes, defaults to TO_CONFIRM either way
		unchanged = AssetReconciliation.reconcile("", Collections.singletonList("id"), Collections.singletonList("id"));
		AssetReconciliationTest.check("blank prior state defaults to TO_CONFIRM when unchanged", AssetReconciliation.TO_CONFIRM.equals(unchanged.scanState));

		System.out.println("AssetReconciliationTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
