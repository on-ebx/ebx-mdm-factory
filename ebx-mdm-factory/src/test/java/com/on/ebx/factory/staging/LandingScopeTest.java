package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. {@link LandingScope} (2026-10-12): {@code LandingTable.selected = false} takes a table out of the staging passes. */
public final class LandingScopeTest
{
	public static void main(final String[] pArgs)
	{
		check("selected = true is in scope", LandingScope.isInScope(Boolean.TRUE));
		check("selected = false is out of scope", !LandingScope.isInScope(Boolean.FALSE));
		check("a row never given the flag (null) stays in scope", LandingScope.isInScope(null));

		// the live case (Vol100kN): the stray table of the CRM source set to selected = false
		final Set<String> inScope = new HashSet<String>(Arrays.asList("CRM.CUSTOMER", "ERP.SUPPLIER"));
		final StagingLlmPass.RuleEntityInfo customer = new StagingLlmPass.RuleEntityInfo("Customer", "CRM", "CUSTOMER");
		final StagingLlmPass.RuleEntityInfo supplier = new StagingLlmPass.RuleEntityInfo("Supplier", "ERP", "SUPPLIER");
		final StagingLlmPass.RuleEntityInfo stray = new StagingLlmPass.RuleEntityInfo("IntakeTest20261006", "CRM", "INTAKE_TEST_20261006");
		check("the rule entity of an in-scope table is kept", LandingScope.keepsRuleEntity(inScope, customer));
		check("the rule entity of the stray table is not offered to the model", !LandingScope.keepsRuleEntity(inScope, stray));
		check("a missing rule entity is not kept", !LandingScope.keepsRuleEntity(inScope, null));
		final List<StagingLlmPass.RuleEntityInfo> kept = LandingScope.keepInScope(inScope, Arrays.asList(customer, stray, supplier));
		check("keepInScope drops only the stray one", kept.size() == 2);
		check("keepInScope keeps the order", kept.get(0) == customer && kept.get(1) == supplier);
		check("nothing in scope keeps nothing", LandingScope.keepInScope(new HashSet<String>(), Arrays.asList(customer, stray)).isEmpty());

		System.out.println("LandingScopeTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new IllegalStateException("LandingScopeTest: " + pWhat);
		}
	}
}
