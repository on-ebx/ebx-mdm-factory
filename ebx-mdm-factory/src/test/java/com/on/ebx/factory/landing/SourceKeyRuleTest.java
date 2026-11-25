package com.on.ebx.factory.landing;

import java.math.BigDecimal;
import java.util.Arrays;

/** JUnit-free: run the main, a failure throws. */
public final class SourceKeyRuleTest
{
	public static void main(final String[] pArgs)
	{
		final BigDecimal zero = BigDecimal.ZERO;
		final BigDecimal one = BigDecimal.ONE;

		SourceKeyRule.Proposal p = SourceKeyRule.propose("ORDERS", Arrays.asList(SourceKeyRuleTest.c("ORDER_ID", true, null, null), SourceKeyRuleTest.c("LINE", true, null, null),
			SourceKeyRuleTest.c("ID", false, null, null)));
		SourceKeyRuleTest.check("the declared key wins, composite and in order", p.columns.equals(Arrays.asList("ORDER_ID", "LINE")) && p.confidence == 100);

		// the real Snowflake demo table: no declared key, never profiled
		p = SourceKeyRule.propose("SUPPLIER", Arrays.asList(SourceKeyRuleTest.c("SUPPLIER_ID", false, null, null), SourceKeyRuleTest.c("NAME1", false, null, null)));
		SourceKeyRuleTest.check("named like the table, no profile: proposed with a modest confidence", p.columns.equals(Arrays.asList("SUPPLIER_ID")) && p.confidence == 70);
		SourceKeyRuleTest.check("and the rationale asks for a profile", p.rationale.contains("Profile the table"));

		p = SourceKeyRule.propose("SUPPLIER", Arrays.asList(SourceKeyRuleTest.c("SUPPLIER_ID", false, zero, one)));
		SourceKeyRuleTest.check("confirmed by the profile", p.confidence == 95);

		p = SourceKeyRule.propose("CONTACT", Arrays.asList(SourceKeyRuleTest.c("ACCOUNT_ID", false, zero, one), SourceKeyRuleTest.c("CONTACT_ID", false, zero, one)));
		SourceKeyRuleTest.check("the identifier of another table is not the key", p.columns.equals(Arrays.asList("CONTACT_ID")));

		p = SourceKeyRule.propose("Accounts", Arrays.asList(SourceKeyRuleTest.c("accountId", false, null, null)));
		SourceKeyRuleTest.check("plural table, camelCase column", p.columns.equals(Arrays.asList("accountId")));

		p = SourceKeyRule.propose("SUPPLIER", Arrays.asList(SourceKeyRuleTest.c("SUPPLIER_ID", false, zero, new BigDecimal("0.98")), SourceKeyRuleTest.c("ID", false, zero, one)));
		SourceKeyRuleTest.check("duplicates refute the best name, the next one is taken", p.columns.equals(Arrays.asList("ID")) && p.confidence == 85);

		p = SourceKeyRule.propose("SUPPLIER", Arrays.asList(SourceKeyRuleTest.c("SUPPLIER_ID", false, new BigDecimal("0.01"), one)));
		SourceKeyRuleTest.check("nulls refute it, nothing is guessed", p.columns.isEmpty() && p.confidence == 0);

		p = SourceKeyRule.propose("EVENTS", Arrays.asList(SourceKeyRuleTest.c("TS", false, zero, one), SourceKeyRuleTest.c("PAYLOAD", false, null, null)));
		SourceKeyRuleTest.check("a unique column that is not named like an identifier is not proposed", p.columns.isEmpty());
		// landing test fixes (2026-10-17): ECOM_Clients holds customerId, the table is named after the file
		p = SourceKeyRule.propose("ECOM_Clients", Arrays.asList(SourceKeyRuleTest.c("customerId", false, zero, one), SourceKeyRuleTest.c("email", false, zero, one)));
		SourceKeyRuleTest.check("a single <x>Id column confirmed by the profile is proposed, moderately", p.columns.equals(Arrays.asList("customerId")) && p.confidence == 60 && p.confidence < 90);
		SourceKeyRuleTest.check("and the rationale says to confirm it", p.rationale.contains("Confirm it"));
		p = SourceKeyRule.propose("ECOM_Clients", Arrays.asList(SourceKeyRuleTest.c("customer_id", false, zero, one)));
		SourceKeyRuleTest.check("snake case", p.columns.equals(Arrays.asList("customer_id")));
		p = SourceKeyRule.propose("Members", Arrays.asList(SourceKeyRuleTest.c("card_number", false, zero, one)));
		SourceKeyRuleTest.check("<x>_number", p.columns.equals(Arrays.asList("card_number")));
		p = SourceKeyRule.propose("ECOM_Clients", Arrays.asList(SourceKeyRuleTest.c("customerId", false, null, null)));
		SourceKeyRuleTest.check("an unprofiled generic identifier is not proposed", p.columns.isEmpty());
		p = SourceKeyRule.propose("ECOM_Clients", Arrays.asList(SourceKeyRuleTest.c("customerId", false, zero, new BigDecimal("0.9"))));
		SourceKeyRuleTest.check("duplicates refute it", p.columns.isEmpty());
		p = SourceKeyRule.propose("ORDERS", Arrays.asList(SourceKeyRuleTest.c("customerId", false, zero, one), SourceKeyRuleTest.c("shipmentId", false, zero, one)));
		SourceKeyRuleTest.check("two candidates: nothing says which, none proposed", p.columns.isEmpty());
		p = SourceKeyRule.propose("Clients", Arrays.asList(SourceKeyRuleTest.c("clientId", false, zero, one), SourceKeyRuleTest.c("regionId", false, zero, one)));
		SourceKeyRuleTest.check("a plural table still prefers its own singular id", p.columns.equals(Arrays.asList("clientId")) && p.confidence == 95);
		p = SourceKeyRule.propose("Client", Arrays.asList(SourceKeyRuleTest.c("clientsId", false, zero, one)));
		SourceKeyRuleTest.check("singular table, plural column", p.columns.equals(Arrays.asList("clientsId")) && p.confidence == 95);
		SourceKeyRuleTest.check("paid and valid are not identifiers", !SourceKeyRule.isGenericIdentifierName("paid") && !SourceKeyRule.isGenericIdentifierName("valid"));
		System.out.println("SourceKeyRuleTest OK");
	}

	private static SourceKeyRule.Candidate c(final String pName, final boolean pKey, final BigDecimal pNullRate, final BigDecimal pUniqueRate)
	{
		return new SourceKeyRule.Candidate(pName, pKey, pNullRate, pUniqueRate);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
