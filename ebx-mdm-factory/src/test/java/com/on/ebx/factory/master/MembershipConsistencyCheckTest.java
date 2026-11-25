package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.master.MembershipConsistencyCheck.EntityInfo;
import com.on.ebx.factory.master.MembershipConsistencyCheck.Result;

/** JUnit-free: run the main, a failure throws. */
public final class MembershipConsistencyCheckTest
{
	public static void main(final String[] pArgs)
	{
		MembershipConsistencyCheckTest.theLiveIncidentShape_flaggedWithoutMembers();
		MembershipConsistencyCheckTest.backfillMakesTheRefusalDisappear();
		MembershipConsistencyCheckTest.noOverlapIsConsistent();
		MembershipConsistencyCheckTest.ruleOriginNeverFlagged();
		MembershipConsistencyCheckTest.emptyLandingTablesNeverFlagged();
		System.out.println("MembershipConsistencyCheckTest OK");
	}

	/** The exact live incident: Company (LLM), mapped from SUPPLIER and ACCOUNT, memberEntities empty - both overlapping entities named. */
	private static void theLiveIncidentShape_flaggedWithoutMembers()
	{
		final EntityInfo supplier = new EntityInfo("Supplier", "RULE", false, MembershipConsistencyCheckTest.set("SUPPLIER"));
		final EntityInfo account = new EntityInfo("Account", "RULE", false, MembershipConsistencyCheckTest.set("ACCOUNT"));
		final EntityInfo contact = new EntityInfo("Contact", "RULE", false, MembershipConsistencyCheckTest.set("CONTACT"));
		final EntityInfo company = new EntityInfo("Company", "LLM", false, MembershipConsistencyCheckTest.set("SUPPLIER", "ACCOUNT"));

		final Result result = MembershipConsistencyCheck.check(Arrays.asList(supplier, account, contact, company));
		MembershipConsistencyCheckTest.check("inconsistent", !result.consistent);
		MembershipConsistencyCheckTest.check("exactly one refusal, for Company", result.refusals.size() == 1 && result.refusals.get(0).startsWith("Company:"));
		MembershipConsistencyCheckTest.check("names Supplier", result.refusals.get(0).contains("Supplier"));
		MembershipConsistencyCheckTest.check("names Account", result.refusals.get(0).contains("Account"));
		MembershipConsistencyCheckTest.check("Contact never mentioned (no overlap)", !result.refusals.get(0).contains("Contact"));
	}

	/** The exact design requirement: once memberEntities is backfilled (hasMembers=true), the same shape is no longer flagged. */
	private static void backfillMakesTheRefusalDisappear()
	{
		final EntityInfo supplier = new EntityInfo("Supplier", "RULE", false, MembershipConsistencyCheckTest.set("SUPPLIER"));
		final EntityInfo account = new EntityInfo("Account", "RULE", false, MembershipConsistencyCheckTest.set("ACCOUNT"));
		final EntityInfo company = new EntityInfo("Company", "LLM", true, MembershipConsistencyCheckTest.set("SUPPLIER", "ACCOUNT"));

		final Result result = MembershipConsistencyCheck.check(Arrays.asList(supplier, account, company));
		MembershipConsistencyCheckTest.check("consistent once memberEntities is set", result.consistent);
		MembershipConsistencyCheckTest.check("no refusals", result.refusals.isEmpty());
	}

	private static void noOverlapIsConsistent()
	{
		final EntityInfo supplier = new EntityInfo("Supplier", "RULE", false, MembershipConsistencyCheckTest.set("SUPPLIER"));
		final EntityInfo contact = new EntityInfo("Contact", "RULE", false, MembershipConsistencyCheckTest.set("CONTACT"));
		final EntityInfo company = new EntityInfo("Company", "LLM", false, MembershipConsistencyCheckTest.set("COMPANY_MASTER_SOURCE"));

		final Result result = MembershipConsistencyCheck.check(Arrays.asList(supplier, contact, company));
		MembershipConsistencyCheckTest.check("no landing-table overlap: consistent", result.consistent);
	}

	/** A RULE-origin entity is never flagged, whatever its own landing-table overlap looks like: only an LLM entity claims to unify others. */
	private static void ruleOriginNeverFlagged()
	{
		final EntityInfo supplier = new EntityInfo("Supplier", "RULE", false, MembershipConsistencyCheckTest.set("SUPPLIER"));
		final EntityInfo supplierAlias = new EntityInfo("SupplierAlias", "RULE", false, MembershipConsistencyCheckTest.set("SUPPLIER"));

		final Result result = MembershipConsistencyCheck.check(Arrays.asList(supplier, supplierAlias));
		MembershipConsistencyCheckTest.check("both RULE-origin, overlapping, but neither flagged", result.consistent);
	}

	/** An LLM entity with no mapped landing table yet (nothing accepted) has nothing to compare - never flagged. */
	private static void emptyLandingTablesNeverFlagged()
	{
		final EntityInfo supplier = new EntityInfo("Supplier", "RULE", false, MembershipConsistencyCheckTest.set("SUPPLIER"));
		final EntityInfo company = new EntityInfo("Company", "LLM", false, Collections.<String>emptySet());

		final Result result = MembershipConsistencyCheck.check(Arrays.asList(supplier, company));
		MembershipConsistencyCheckTest.check("no landing tables at all: consistent", result.consistent);
	}

	private static Set<String> set(final String... pValues)
	{
		return new LinkedHashSet<String>(Arrays.asList(pValues));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
