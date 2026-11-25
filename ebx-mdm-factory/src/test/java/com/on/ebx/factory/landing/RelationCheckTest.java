package com.on.ebx.factory.landing;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class RelationCheckTest
{
	public static void main(final String[] pArgs)
	{
		RelationCheckTest.singleColumnSql();
		RelationCheckTest.compositeColumnSql();
		RelationCheckTest.selfReferenceSql();
		RelationCheckTest.everyIdentifierIsAlwaysQuoted();
		RelationCheckTest.aDoubleQuoteInAnIdentifierIsRefused();
		RelationCheckTest.blankIdentifiersAreRefused();
		RelationCheckTest.mismatchedColumnCountsAreRefused();
		RelationCheckTest.noChildColumnsIsRefused();
		RelationCheckTest.outcomeConfirmedManyToOne();
		RelationCheckTest.outcomeConfirmedOneToOne();
		RelationCheckTest.outcomeMostlyAtTheFivePercentBoundary();
		RelationCheckTest.outcomeWeakJustOverTheBoundary();
		RelationCheckTest.outcomeEmpty();
		RelationCheckTest.confidenceAdjustmentCapsAndFloors();
		RelationCheckTest.confidenceAdjustmentOrdinaryCase();
		System.out.println("RelationCheckTest OK");
	}

	// --------------------------------------------------------------------------------------------------- SQL shape

	/** The real Snowflake demo (PLAN.md, docs/SOURCE-RELATIONS.md §5, live-validated 2026-09-20): CRM_SOURCE.CONTACT.ACCOUNT_ID -> ACCOUNT.ACCOUNT_ID. */
	private static void singleColumnSql()
	{
		final RelationCheck.Statements statements = RelationCheck.build("CONTACT", Collections.singletonList("ACCOUNT_ID"), "ACCOUNT",
			Collections.singletonList("ACCOUNT_ID"));
		final String count = statements.countSql;
		RelationCheckTest.check("count sql: " + count, count.equals("SELECT COUNT(*) AS CHILD_ROWS, COUNT(DISTINCT c.\"ACCOUNT_ID\") AS DISTINCT_CHILD, "
			+ "COUNT(DISTINCT CASE WHEN p.\"ACCOUNT_ID\" IS NULL THEN c.\"ACCOUNT_ID\" END) AS ORPHAN_CHILD FROM \"CONTACT\" c LEFT JOIN \"ACCOUNT\" p "
			+ "ON c.\"ACCOUNT_ID\" = p.\"ACCOUNT_ID\" WHERE c.\"ACCOUNT_ID\" IS NOT NULL AND c.\"ACCOUNT_ID\" <> ''"));
		final String sample = statements.sampleSql;
		RelationCheckTest.check("sample sql: " + sample, sample.equals("SELECT DISTINCT c.\"ACCOUNT_ID\" AS ORPHAN_VALUE FROM \"CONTACT\" c LEFT JOIN \"ACCOUNT\" p "
			+ "ON c.\"ACCOUNT_ID\" = p.\"ACCOUNT_ID\" WHERE c.\"ACCOUNT_ID\" IS NOT NULL AND c.\"ACCOUNT_ID\" <> '' AND p.\"ACCOUNT_ID\" IS NULL "
			+ "ORDER BY c.\"ACCOUNT_ID\" FETCH NEXT 10 ROWS ONLY"));
	}

	/** A composite key: both columns AND-joined, and counted as one distinct value via || concatenation (no multi-column COUNT(DISTINCT a,b) in EBX SQL). */
	private static void compositeColumnSql()
	{
		final RelationCheck.Statements statements = RelationCheck.build("ORDER_LINE", Arrays.asList("ORDER_ID", "LINE_NO"), "ORDER_HEADER",
			Arrays.asList("ID", "SEQ"));
		final String count = statements.countSql;
		RelationCheckTest.check("both columns joined with AND: " + count,
			count.contains("ON c.\"ORDER_ID\" = p.\"ID\" AND c.\"LINE_NO\" = p.\"SEQ\""));
		RelationCheckTest.check("both columns required non-null/non-empty with AND: " + count, count.contains(
			"WHERE c.\"ORDER_ID\" IS NOT NULL AND c.\"ORDER_ID\" <> '' AND c.\"LINE_NO\" IS NOT NULL AND c.\"LINE_NO\" <> ''"));
		RelationCheckTest.check("distinct combo concatenates both columns: " + count,
			count.contains("COUNT(DISTINCT c.\"ORDER_ID\" || '|' || c.\"LINE_NO\")"));
		RelationCheckTest.check("unmatched test reads only the FIRST parent column: " + count, count.contains("CASE WHEN p.\"ID\" IS NULL THEN"));
		// table name itself is a SQL reserved word (ORDER): still just quoted like any other identifier, no special-casing
		RelationCheckTest.check("reserved-word-looking table name is quoted like any other: " + count, count.contains("FROM \"ORDER_LINE\" c LEFT JOIN \"ORDER_HEADER\" p"));
	}

	/** ACCOUNT.PARENT_ACCOUNT_ID -> ACCOUNT.ACCOUNT_ID: same table on both sides, distinguished only by alias - live-validated (docs/SOURCE-RELATIONS.md §5). */
	private static void selfReferenceSql()
	{
		final RelationCheck.Statements statements = RelationCheck.build("ACCOUNT", Collections.singletonList("PARENT_ACCOUNT_ID"), "ACCOUNT",
			Collections.singletonList("ACCOUNT_ID"));
		RelationCheckTest.check("self reference joins the same table under two aliases: " + statements.countSql,
			statements.countSql.contains("FROM \"ACCOUNT\" c LEFT JOIN \"ACCOUNT\" p ON c.\"PARENT_ACCOUNT_ID\" = p.\"ACCOUNT_ID\""));
	}

	/** No word list, no heuristic: an identifier is quoted unconditionally, reserved-looking or not (COLUMN, DAY, ORDER, GROUP - the knowledge base's own examples). */
	private static void everyIdentifierIsAlwaysQuoted()
	{
		final RelationCheck.Statements statements = RelationCheck.build("COLUMN", Collections.singletonList("DAY"), "GROUP", Collections.singletonList("ORDER"));
		RelationCheckTest.check("reserved words quoted unconditionally: " + statements.countSql,
			statements.countSql.contains("\"COLUMN\"") && statements.countSql.contains("\"DAY\"") && statements.countSql.contains("\"GROUP\"")
				&& statements.countSql.contains("\"ORDER\""));
	}

	private static void aDoubleQuoteInAnIdentifierIsRefused()
	{
		try
		{
			RelationCheck.build("EVIL\" OR 1=1 --", Collections.singletonList("X"), "T", Collections.singletonList("Y"));
			throw new AssertionError("a table name containing a double quote must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			RelationCheckTest.check("refusal names the offending value", ex.getMessage().contains("EVIL"));
		}
		try
		{
			RelationCheck.build("T", Collections.singletonList("X\""), "P", Collections.singletonList("Y"));
			throw new AssertionError("a column name containing a double quote must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			RelationCheckTest.check("refusal names the offending column", ex.getMessage().contains("X\""));
		}
	}

	private static void blankIdentifiersAreRefused()
	{
		try
		{
			RelationCheck.build("", Collections.singletonList("X"), "P", Collections.singletonList("Y"));
			throw new AssertionError("a blank table name must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			// expected
		}
		try
		{
			RelationCheck.build("T", Collections.singletonList("X"), null, Collections.singletonList("Y"));
			throw new AssertionError("a null parent table must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			// expected
		}
	}

	private static void mismatchedColumnCountsAreRefused()
	{
		try
		{
			RelationCheck.build("T", Arrays.asList("A", "B"), "P", Collections.singletonList("Y"));
			throw new AssertionError("child/parent column count mismatch must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			RelationCheckTest.check("refusal names both sizes", ex.getMessage().contains("2") && ex.getMessage().contains("1"));
		}
	}

	private static void noChildColumnsIsRefused()
	{
		try
		{
			RelationCheck.build("T", Collections.<String> emptyList(), "P", Collections.<String> emptyList());
			throw new AssertionError("no child column at all must be refused");
		}
		catch (final IllegalArgumentException ex)
		{
			// expected
		}
	}

	// --------------------------------------------------------------------------------------------------- outcomes

	/** The real CONTACT -> ACCOUNT figures, live-checked 2026-09-20: 52 distinct, 0 orphan, 89 child rows (89 != 52: MANY_TO_ONE, several contacts share an account). */
	private static void outcomeConfirmedManyToOne()
	{
		final RelationCheck.Result result = RelationCheck.interpret(52, 89, 0, Collections.<String> emptyList());
		RelationCheckTest.check("no orphan: CONFIRMED", result.outcome == RelationCheck.Outcome.CONFIRMED);
		RelationCheckTest.check("89 rows over 52 distinct values: MANY_TO_ONE", "MANY_TO_ONE".equals(result.cardinality));
		RelationCheckTest.check("CONFIRMED proposes +15", result.confidenceDelta == 15);
		RelationCheckTest.check("message: " + result.message, "52 distinct value(s), 0 orphan(s).".equals(result.message));
	}

	/** Every child row carries its own distinct value: each parent key is referenced by at most one child row -> ONE_TO_ONE. */
	private static void outcomeConfirmedOneToOne()
	{
		final RelationCheck.Result result = RelationCheck.interpret(5, 5, 0, Collections.<String> emptyList());
		RelationCheckTest.check("child rows == distinct child values: ONE_TO_ONE", "ONE_TO_ONE".equals(result.cardinality));
		RelationCheckTest.check("still CONFIRMED", result.outcome == RelationCheck.Outcome.CONFIRMED);
	}

	/** Exactly at the 5% boundary (2/52 = 3.8%, comfortably under; the boundary itself is exercised at 20*orphan == distinct). */
	private static void outcomeMostlyAtTheFivePercentBoundary()
	{
		// 20 * orphan == distinct: 1/20 == 5% exactly, still MOSTLY ("<=" 5%, not "<")
		final RelationCheck.Result atBoundary = RelationCheck.interpret(20, 20, 1, Arrays.asList("V1"));
		RelationCheckTest.check("exactly 5% is still MOSTLY, not WEAK", atBoundary.outcome == RelationCheck.Outcome.MOSTLY);
		RelationCheckTest.check("MOSTLY proposes no confidence change", atBoundary.confidenceDelta == 0);
		RelationCheckTest.check("message names the percent: " + atBoundary.message, atBoundary.message.contains("5.00%"));
		RelationCheckTest.check("message lists the sample: " + atBoundary.message, atBoundary.message.contains("e.g. V1"));

		final RelationCheck.Result comfortablyUnder = RelationCheck.interpret(52, 89, 2, Arrays.asList("A1", "A2"));
		RelationCheckTest.check("2/52 (3.8%) is MOSTLY", comfortablyUnder.outcome == RelationCheck.Outcome.MOSTLY);
	}

	/** Just over the boundary: 3/52 = 5.77% > 5% -> WEAK, -30, sample listed. */
	private static void outcomeWeakJustOverTheBoundary()
	{
		final RelationCheck.Result result = RelationCheck.interpret(52, 89, 3, Arrays.asList("A1", "A2", "A3"));
		RelationCheckTest.check("3/52 (5.77%) is WEAK, not MOSTLY", result.outcome == RelationCheck.Outcome.WEAK);
		RelationCheckTest.check("WEAK proposes -30", result.confidenceDelta == -30);
		RelationCheckTest.check("message names the percent: " + result.message, result.message.contains("5.77%"));
		RelationCheckTest.check("message lists the sample: " + result.message, result.message.contains("e.g. A1, A2, A3"));
		RelationCheckTest.check("cardinality still reported for WEAK", "MANY_TO_ONE".equals(result.cardinality));
	}

	/** No real (non-null, non-empty) child value at all: nothing can be said, cardinality is not asserted, no confidence change. */
	private static void outcomeEmpty()
	{
		final RelationCheck.Result result = RelationCheck.interpret(0, 0, 0, Collections.<String> emptyList());
		RelationCheckTest.check("EMPTY", result.outcome == RelationCheck.Outcome.EMPTY);
		RelationCheckTest.check("no cardinality asserted", result.cardinality == null);
		RelationCheckTest.check("no confidence change", result.confidenceDelta == 0);
	}

	// --------------------------------------------------------------------------------------------------- confidence

	private static void confidenceAdjustmentCapsAndFloors()
	{
		RelationCheckTest.check("+15 capped at 99, not 105", RelationCheck.adjustConfidence(90, 15) == 99);
		RelationCheckTest.check("84 + 15 == 99 exactly: not over-capped", RelationCheck.adjustConfidence(84, 15) == 99);
		RelationCheckTest.check("-30 floored at 5, not -10", RelationCheck.adjustConfidence(20, -30) == 5);
		RelationCheckTest.check("35 - 30 == 5 exactly: not over-floored", RelationCheck.adjustConfidence(35, -30) == 5);
	}

	private static void confidenceAdjustmentOrdinaryCase()
	{
		RelationCheckTest.check("80 + 15 == 95", RelationCheck.adjustConfidence(80, 15) == 95);
		RelationCheckTest.check("65 - 30 == 35", RelationCheck.adjustConfidence(65, -30) == 35);
		RelationCheckTest.check("unchanged with a zero delta", RelationCheck.adjustConfidence(70, 0) == 70);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
