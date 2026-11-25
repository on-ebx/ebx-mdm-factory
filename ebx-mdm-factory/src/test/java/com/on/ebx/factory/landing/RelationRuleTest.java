package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class RelationRuleTest
{
	public static void main(final String[] pArgs)
	{
		RelationRuleTest.realExample();
		RelationRuleTest.noOtherTable();
		RelationRuleTest.neverProposesKeyColumnToItself();
		RelationRuleTest.ambiguityIsReportedNotGuessed();
		RelationRuleTest.compositeKeyTargetIsSkippedWithAReason();
		RelationRuleTest.emptyKeyTargetIsSkippedWithAReason();
		RelationRuleTest.incompatibleTypesAreNeverProposed();
		RelationRuleTest.aCoincidentalTailOfLettersIsNotAWordBoundaryMatch();
		RelationRuleTest.camelCaseSelfReferenceAcrossAWordBoundary();
		RelationRuleTest.multiWordPrefixBeforeTheKeyIsAMatch();
		RelationRuleTest.loadOrderParentsFirstSelfReferenceIgnored();
		RelationRuleTest.loadOrderReportsACycleInsteadOfLooping();
		RelationRuleTest.ownKeyNamedLikeAnotherTablesKeyIsNeverProposedEitherDirection();
		System.out.println("RelationRuleTest OK");
	}

	/** The real Snowflake demo (PLAN.md, 2026-09-20): CRM_SOURCE.CONTACT.ACCOUNT_ID -> ACCOUNT, and ACCOUNT's own PARENT_ACCOUNT_ID -> itself. */
	private static void realExample()
	{
		final RelationRule.Table account = new RelationRule.Table("ACCOUNT", Arrays.asList("ACCOUNT_ID"),
			Arrays.asList(RelationRuleTest.col("ACCOUNT_ID", "INTEGER"), RelationRuleTest.col("PARENT_ACCOUNT_ID", "INTEGER"), RelationRuleTest.col("NAME", "STRING")));
		final RelationRule.Table contact = new RelationRule.Table("CONTACT", Arrays.asList("CONTACT_ID"),
			Arrays.asList(RelationRuleTest.col("CONTACT_ID", "INTEGER"), RelationRuleTest.col("ACCOUNT_ID", "INTEGER"), RelationRuleTest.col("EMAIL", "STRING")));

		final RelationRule.Result result = RelationRule.propose(Arrays.asList(account, contact));
		RelationRuleTest.check("two proposals, no skips: " + result.proposals.size() + "/" + result.skipped.size(), result.proposals.size() == 2 && result.skipped.isEmpty());

		final RelationRule.Proposal contactToAccount = RelationRuleTest.find(result.proposals, "CONTACT", "ACCOUNT_ID");
		RelationRuleTest.check("CONTACT.ACCOUNT_ID -> ACCOUNT.ACCOUNT_ID", contactToAccount != null && "ACCOUNT".equals(contactToAccount.toTable)
			&& contactToAccount.toColumns.equals(Arrays.asList("ACCOUNT_ID")));
		RelationRuleTest.check("named exactly like the key: confidence 80", contactToAccount.confidence == 80);
		RelationRuleTest.check("not a self reference", !contactToAccount.selfReference);
		RelationRuleTest.check("rationale names the column and the key", contactToAccount.rationale.contains("ACCOUNT_ID") && contactToAccount.rationale.contains("ACCOUNT"));

		final RelationRule.Proposal parentAccount = RelationRuleTest.find(result.proposals, "ACCOUNT", "PARENT_ACCOUNT_ID");
		RelationRuleTest.check("ACCOUNT.PARENT_ACCOUNT_ID -> ACCOUNT.ACCOUNT_ID", parentAccount != null && "ACCOUNT".equals(parentAccount.toTable)
			&& parentAccount.toColumns.equals(Arrays.asList("ACCOUNT_ID")));
		RelationRuleTest.check("ends with the key after a separator: confidence 65", parentAccount.confidence == 65);
		RelationRuleTest.check("a table referencing itself is a self reference", parentAccount.selfReference);
	}

	/** ERP_SOURCE.SUPPLIER has no relation (PLAN.md): alone in its project source, nothing to point to. */
	private static void noOtherTable()
	{
		final RelationRule.Table supplier = new RelationRule.Table("SUPPLIER", Arrays.asList("SUPPLIER_ID"),
			Arrays.asList(RelationRuleTest.col("SUPPLIER_ID", "INTEGER"), RelationRuleTest.col("NAME1", "STRING")));
		final RelationRule.Result result = RelationRule.propose(Collections.singletonList(supplier));
		RelationRuleTest.check("nothing to propose alone", result.proposals.isEmpty() && result.skipped.isEmpty());
	}

	private static void neverProposesKeyColumnToItself()
	{
		final RelationRule.Table account = new RelationRule.Table("ACCOUNT", Arrays.asList("ACCOUNT_ID"),
			Arrays.asList(RelationRuleTest.col("ACCOUNT_ID", "INTEGER")));
		final RelationRule.Table contact = new RelationRule.Table("CONTACT", Arrays.asList("CONTACT_ID"),
			Arrays.asList(RelationRuleTest.col("CONTACT_ID", "INTEGER")));
		final RelationRule.Result result = RelationRule.propose(Arrays.asList(account, contact));
		RelationRuleTest.check("a table's own key column is never proposed as a relation to itself", result.proposals.isEmpty());
	}

	/**
	 * A.ACCOUNT_ID and B.PARENT_ACCOUNT_ID are two ordinary, unambiguous keys, each with a real word separator
	 * of its own (B's own PARENT_ACCOUNT_ID column ends with A's key at a real word boundary and is proposed,
	 * confidence 65, its one real side effect). But CHILD.GRAND_PARENT_ACCOUNT_ID ends with BOTH of them at once,
	 * word for word (it ends with the words "parent, account, id" - B's whole key - and, inside that, with
	 * "account, id" - A's whole key, too): a genuine word-boundary tie at the same confidence, kept as none,
	 * reported instead of guessed.
	 */
	private static void ambiguityIsReportedNotGuessed()
	{
		final RelationRule.Table a = new RelationRule.Table("A", Arrays.asList("ACCOUNT_ID"), Arrays.asList(RelationRuleTest.col("ACCOUNT_ID", "INTEGER")));
		final RelationRule.Table b = new RelationRule.Table("B", Arrays.asList("PARENT_ACCOUNT_ID"), Arrays.asList(RelationRuleTest.col("PARENT_ACCOUNT_ID", "INTEGER")));
		final RelationRule.Table child = new RelationRule.Table("CHILD", Arrays.asList("ID"),
			Arrays.asList(RelationRuleTest.col("ID", "INTEGER"), RelationRuleTest.col("GRAND_PARENT_ACCOUNT_ID", "INTEGER")));

		final RelationRule.Result result = RelationRule.propose(Arrays.asList(a, b, child));
		RelationRuleTest.check("only B's own key column is proposed (to A), nothing for the tied column: " + result.proposals.size(), result.proposals.size() == 1);
		RelationRuleTest.check("B.PARENT_ACCOUNT_ID -> A.ACCOUNT_ID", "B".equals(result.proposals.get(0).fromTable) && "A".equals(result.proposals.get(0).toTable));
		RelationRuleTest.check("exactly one skip, for CHILD.GRAND_PARENT_ACCOUNT_ID",
			result.skipped.size() == 1 && "CHILD".equals(result.skipped.get(0).table) && "GRAND_PARENT_ACCOUNT_ID".equals(result.skipped.get(0).column));
		RelationRuleTest.check("the skip reason names the ambiguity", result.skipped.get(0).reason.toLowerCase(java.util.Locale.ROOT).contains("ambiguous"));
	}

	/** A column named like the identifier of a table whose key is composite: not a bare-ID match, reported instead of silently dropped. */
	private static void compositeKeyTargetIsSkippedWithAReason()
	{
		final RelationRule.Table order = new RelationRule.Table("ORDER", Arrays.asList("ORDER_ID", "LINE"),
			Arrays.asList(RelationRuleTest.col("ORDER_ID", "INTEGER"), RelationRuleTest.col("LINE", "INTEGER")));
		final RelationRule.Table shipment = new RelationRule.Table("SHIPMENT", Arrays.asList("SHIPMENT_ID"),
			Arrays.asList(RelationRuleTest.col("SHIPMENT_ID", "INTEGER"), RelationRuleTest.col("ORDER_ID", "INTEGER")));

		final RelationRule.Result result = RelationRule.propose(Arrays.asList(order, shipment));
		RelationRuleTest.check("no proposal to a composite key", result.proposals.isEmpty());
		RelationRuleTest.check("one skip, explaining the composite key", result.skipped.size() == 1 && result.skipped.get(0).reason.contains("composite key"));
	}

	/** A column named like the identifier of a table with no declared key at all. */
	private static void emptyKeyTargetIsSkippedWithAReason()
	{
		final RelationRule.Table log = new RelationRule.Table("LOG", new ArrayList<String>(), Arrays.asList(RelationRuleTest.col("MESSAGE", "STRING")));
		final RelationRule.Table event = new RelationRule.Table("EVENT", Arrays.asList("EVENT_ID"),
			Arrays.asList(RelationRuleTest.col("EVENT_ID", "INTEGER"), RelationRuleTest.col("LOG_ID", "INTEGER")));

		final RelationRule.Result result = RelationRule.propose(Arrays.asList(log, event));
		RelationRuleTest.check("no proposal to a table with no known key", result.proposals.isEmpty());
		RelationRuleTest.check("one skip, explaining there is no known key", result.skipped.size() == 1 && result.skipped.get(0).reason.contains("no known key"));
	}

	/** A column named exactly like a key, but the two are known to hold incompatible data: never proposed, and not worth a skip entry either. */
	private static void incompatibleTypesAreNeverProposed()
	{
		final RelationRule.Table account = new RelationRule.Table("ACCOUNT", Arrays.asList("ACCOUNT_ID"), Arrays.asList(RelationRuleTest.col("ACCOUNT_ID", "INTEGER")));
		final RelationRule.Table note = new RelationRule.Table("NOTE", Arrays.asList("NOTE_ID"),
			Arrays.asList(RelationRuleTest.col("NOTE_ID", "INTEGER"), RelationRuleTest.col("ACCOUNT_ID", "STRING")));

		final RelationRule.Result result = RelationRule.propose(Arrays.asList(account, note));
		RelationRuleTest.check("STRING vs INTEGER: never proposed", result.proposals.isEmpty());
		RelationRuleTest.check("not reported as a skip either: it just isn't a match", result.skipped.isEmpty());
	}

	/**
	 * Flattened to letters, "SUBACCOUNT_ID" ends with "accountid" exactly like "ACCOUNT_ID" does - but SUBACCOUNT
	 * is one word (no separator splits SUB from ACCOUNT), so word by word the column is [subaccount, id], which
	 * does not end with the key's [account, id] at a word boundary. Must never be proposed.
	 */
	private static void aCoincidentalTailOfLettersIsNotAWordBoundaryMatch()
	{
		final RelationRule.Table account = new RelationRule.Table("ACCOUNT", Arrays.asList("ACCOUNT_ID"), Arrays.asList(RelationRuleTest.col("ACCOUNT_ID", "INTEGER")));
		final RelationRule.Table other = new RelationRule.Table("OTHER", Arrays.asList("OTHER_ID"),
			Arrays.asList(RelationRuleTest.col("OTHER_ID", "INTEGER"), RelationRuleTest.col("SUBACCOUNT_ID", "INTEGER")));
		final RelationRule.Result result = RelationRule.propose(Arrays.asList(account, other));
		RelationRuleTest.check("SUBACCOUNT_ID is not a reference to ACCOUNT", RelationRuleTest.find(result.proposals, "OTHER", "SUBACCOUNT_ID") == null);
		RelationRuleTest.check("not even a skip: it just isn't a match", result.skipped.isEmpty());

		// same trap the other way round: DISCOUNT_ID does not end with COUNT_ID at a word boundary either
		final RelationRule.Table count = new RelationRule.Table("COUNT", Arrays.asList("COUNT_ID"), Arrays.asList(RelationRuleTest.col("COUNT_ID", "INTEGER")));
		final RelationRule.Table sale = new RelationRule.Table("SALE", Arrays.asList("SALE_ID"),
			Arrays.asList(RelationRuleTest.col("SALE_ID", "INTEGER"), RelationRuleTest.col("DISCOUNT_ID", "INTEGER")));
		final RelationRule.Result result2 = RelationRule.propose(Arrays.asList(count, sale));
		RelationRuleTest.check("DISCOUNT_ID is not a reference to COUNT", RelationRuleTest.find(result2.proposals, "SALE", "DISCOUNT_ID") == null);
		RelationRuleTest.check("not even a skip: it just isn't a match", result2.skipped.isEmpty());
	}

	/** camelCase word boundaries work the same as snake_case ones, including for a self reference within the same table. */
	private static void camelCaseSelfReferenceAcrossAWordBoundary()
	{
		final RelationRule.Table accountTable = new RelationRule.Table("Account", Arrays.asList("accountId"),
			Arrays.asList(RelationRuleTest.col("accountId", "INTEGER"), RelationRuleTest.col("parentAccountId", "INTEGER")));
		final RelationRule.Result result = RelationRule.propose(Collections.singletonList(accountTable));
		final RelationRule.Proposal proposal = RelationRuleTest.find(result.proposals, "Account", "parentAccountId");
		RelationRuleTest.check("parentAccountId -> Account.accountId is proposed", proposal != null && "Account".equals(proposal.toTable) && proposal.toColumns.equals(Arrays.asList("accountId")));
		RelationRuleTest.check("ends with the key after a camelCase boundary: confidence 65", proposal.confidence == 65);
		RelationRuleTest.check("a table referencing itself is a self reference", proposal.selfReference);
	}

	/** More than one word in front of the key is still a match, as long as the key's own words are the tail. */
	private static void multiWordPrefixBeforeTheKeyIsAMatch()
	{
		final RelationRule.Table account = new RelationRule.Table("Account", Arrays.asList("ACCOUNT_ID"), Arrays.asList(RelationRuleTest.col("ACCOUNT_ID", "INTEGER")));
		final RelationRule.Table invoice = new RelationRule.Table("Invoice", Arrays.asList("INVOICE_ID"),
			Arrays.asList(RelationRuleTest.col("INVOICE_ID", "INTEGER"), RelationRuleTest.col("BILL_TO_ACCOUNT_ID", "INTEGER")));
		final RelationRule.Result result = RelationRule.propose(Arrays.asList(account, invoice));
		final RelationRule.Proposal proposal = RelationRuleTest.find(result.proposals, "Invoice", "BILL_TO_ACCOUNT_ID");
		RelationRuleTest.check("BILL_TO_ACCOUNT_ID -> Account.ACCOUNT_ID is proposed", proposal != null && "Account".equals(proposal.toTable));
		RelationRuleTest.check("confidence 65, not a self reference", proposal.confidence == 65 && !proposal.selfReference);
	}

	private static void loadOrderParentsFirstSelfReferenceIgnored()
	{
		final List<String> tables = Arrays.asList("ACCOUNT", "CONTACT");
		final List<RelationRule.Edge> edges = Arrays.asList(new RelationRule.Edge("CONTACT", "ACCOUNT", false), new RelationRule.Edge("ACCOUNT", "ACCOUNT", true));
		final RelationRule.LoadOrder order = RelationRule.loadOrder(tables, edges);
		RelationRuleTest.check("parent before child: " + order.order, order.order.equals(Arrays.asList("ACCOUNT", "CONTACT")));
		RelationRuleTest.check("no cycle", order.cycle.isEmpty());
	}

	private static void loadOrderReportsACycleInsteadOfLooping()
	{
		final List<String> tables = Arrays.asList("X", "A", "B");
		final List<RelationRule.Edge> edges = Arrays.asList(new RelationRule.Edge("A", "B", false), new RelationRule.Edge("B", "A", false));
		final RelationRule.LoadOrder order = RelationRule.loadOrder(tables, edges);
		RelationRuleTest.check("what could be placed is placed: " + order.order, order.order.equals(Arrays.asList("X")));
		RelationRuleTest.check("the rest is reported as blocked by a cycle, not looped over: " + order.cycle, order.cycle.equals(Arrays.asList("A", "B")));
	}

	/**
	 * Live bug (2026-09-26, project DeducedTest, deduced scan of DemoCRM): {@code Company} and {@code Contacts}
	 * each have their OWN key column happening to be named "id" - the naming rule used to propose a relation to
	 * EACH OTHER at confidence 80 (rule 1, exact name match), which then made {@link RelationRule#loadOrder}
	 * report a cycle. Neither direction is real: {@code Contacts.properties.company} (not modelled here - it is
	 * not name-matched by any rule, see the class Javadoc) holds the actual foreign key, {@code Company.id} and
	 * {@code Contacts.id} are each their own table's independent identifier. Fixed: a table's own entire
	 * single-column key is never proposed as a reference to another table's identically-named key.
	 */
	private static void ownKeyNamedLikeAnotherTablesKeyIsNeverProposedEitherDirection()
	{
		final RelationRule.Table company = new RelationRule.Table("Company", Arrays.asList("id"),
			Arrays.asList(RelationRuleTest.col("id", "STRING"), RelationRuleTest.col("properties.name", "STRING")));
		final RelationRule.Table contacts = new RelationRule.Table("Contacts", Arrays.asList("id"),
			Arrays.asList(RelationRuleTest.col("id", "STRING"), RelationRuleTest.col("properties.company", "STRING")));

		final RelationRule.Result result = RelationRule.propose(Arrays.asList(company, contacts));
		RelationRuleTest.check("no proposal either direction: " + result.proposals.size(), result.proposals.isEmpty());
		RelationRuleTest.check("nothing to report as skipped either", result.skipped.isEmpty());

		final RelationRule.LoadOrder order = RelationRule.loadOrder(Arrays.asList("Company", "Contacts"), Collections.<RelationRule.Edge>emptyList());
		RelationRuleTest.check("no cycle", order.cycle.isEmpty());
		RelationRuleTest.check("both tables ordered", order.order.equals(Arrays.asList("Company", "Contacts")));
	}

	private static RelationRule.Column col(final String pName, final String pDataType)
	{
		return new RelationRule.Column(pName, pDataType);
	}

	private static RelationRule.Proposal find(final List<RelationRule.Proposal> pProposals, final String pFromTable, final String pFromColumn)
	{
		for (final RelationRule.Proposal proposal : pProposals)
		{
			if (proposal.fromTable.equals(pFromTable) && proposal.fromColumns.equals(Collections.singletonList(pFromColumn)))
			{
				return proposal;
			}
		}
		return null;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
