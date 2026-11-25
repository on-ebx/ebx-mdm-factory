package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers the two pure
 * SQL-text builders {@link MatchPlanSimulationRunner#sharedKeyValuesSql}/{@link
 * MatchPlanSimulationRunner#nearMissGroupsSql} added by the review-match-plan-simulation-probes lot
 * (2026-09-22) - the same discipline {@code CrossSystemMeasuresTest} already applies to {@link
 * CrossSystemMeasures#isQuotable}/{@code quoteIdentifier}: no live {@code Session}/{@code Adaptation}
 * needed, since building the statement text is a pure function of its arguments.
 * <p>
 * {@code sharedKeyValuesSql} is a straight restoration of the pre-lot statement removed by commit c08a5b1
 * (`git log -p` on {@code MatchPlanSimulationRunner.java}), coordinator-verified live 2026-09-21 (see that
 * method's own Javadoc) - this test pins the EXACT text so a future edit cannot silently drift from the
 * verified shape. {@code nearMissGroupsSql} is NEW this lot and was never itself run live - this test only
 * proves the text it emits, not that the engine accepts it (see {@code MatchPlanSimulationRunner}'s own
 * class Javadoc, "never exercised against a live instance").
 */
public final class MatchPlanSimulationRunnerSqlTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanSimulationRunnerSqlTest.sharedKeyValuesSql_exactTextForCompanyNameKey();
		MatchPlanSimulationRunnerSqlTest.nearMissGroupsSql_exactTextForCompanyCountryCityKeyNameKey();
		MatchPlanSimulationRunnerSqlTest.nearMissGroupsSql_singleExactField();
		MatchPlanSimulationRunnerSqlTest.nearMissGroupsSql_threeExactFieldsGeneralisesTheColumnList();
		MatchPlanSimulationRunnerSqlTest.sqlStringLiteral_doublesEmbeddedQuote();
		MatchPlanSimulationRunnerSqlTest.comboKey_joinsValuesWithSeparatorAndDiffersOnOrderOrContent();
		System.out.println("MatchPlanSimulationRunnerSqlTest OK");
	}

	/**
	 * The exact statement text reported to the coordinator for {@code Company}/{@code nameKey} - see class
	 * Javadoc. Pinning this string is the whole point of the test: a future refactor that changes the derived
	 * table shape, the {@code HAVING} clause, or the identifier quoting would be caught here even though
	 * nothing here can run the statement against a live EBX SQL engine.
	 */
	private static void sharedKeyValuesSql_exactTextForCompanyNameKey()
	{
		final String sql = MatchPlanSimulationRunner.sharedKeyValuesSql("Company", "nameKey");
		MatchPlanSimulationRunnerSqlTest.check("exact statement text: " + sql, ("SELECT a.\"nameKey\" AS V FROM \"Company\" a WHERE a.\"nameKey\" IS NOT NULL "
			+ "GROUP BY a.\"nameKey\" HAVING COUNT(DISTINCT a.\"sourceSystem\") > 1 FETCH FIRST 100 ROWS ONLY").equals(sql));
	}

	/** The live plan's own shape (task brief): {@code country}/{@code cityKey} exact fields, {@code nameKey} the identifying key, entity {@code Company}. */
	private static void nearMissGroupsSql_exactTextForCompanyCountryCityKeyNameKey()
	{
		final List<String> exactFields = Arrays.asList("country", "cityKey");
		final String sql = MatchPlanSimulationRunner.nearMissGroupsSql("Company", exactFields, "nameKey");
		MatchPlanSimulationRunnerSqlTest.check("exact statement text: " + sql,
			("SELECT a.\"country\" AS G0, a.\"cityKey\" AS G1 FROM \"Company\" a WHERE a.\"country\" IS NOT NULL AND a.\"cityKey\" IS NOT NULL "
				+ "AND a.\"nameKey\" IS NOT NULL GROUP BY a.\"country\", a.\"cityKey\" HAVING COUNT(DISTINCT a.\"sourceSystem\") > 1 "
				+ "AND COUNT(DISTINCT a.\"nameKey\") > 1 FETCH FIRST 100 ROWS ONLY").equals(sql));
	}

	/** A single-exact-field plan (the simplest shape {@code MatchPlanRules}' own EXACT-tier-only plans could reach this probe with) - no stray comma, no {@code G1}. */
	private static void nearMissGroupsSql_singleExactField()
	{
		final String sql = MatchPlanSimulationRunner.nearMissGroupsSql("Supplier", Arrays.asList("country"), "vatNoKey");
		MatchPlanSimulationRunnerSqlTest.check("exact statement text: " + sql,
			("SELECT a.\"country\" AS G0 FROM \"Supplier\" a WHERE a.\"country\" IS NOT NULL AND a.\"vatNoKey\" IS NOT NULL GROUP BY a.\"country\" "
				+ "HAVING COUNT(DISTINCT a.\"sourceSystem\") > 1 AND COUNT(DISTINCT a.\"vatNoKey\") > 1 FETCH FIRST 100 ROWS ONLY").equals(sql));
	}

	/** Three exact fields - proves the column list/GROUP BY/WHERE generalise past the two-field live case (G0/G1/G2, two commas, three {@code IS NOT NULL} guards). */
	private static void nearMissGroupsSql_threeExactFieldsGeneralisesTheColumnList()
	{
		final String sql = MatchPlanSimulationRunner.nearMissGroupsSql("Company", new ArrayList<String>(Arrays.asList("country", "cityKey", "postalCode")),
			"nameKey");
		MatchPlanSimulationRunnerSqlTest.check("three-column SELECT: " + sql, sql.startsWith(
			"SELECT a.\"country\" AS G0, a.\"cityKey\" AS G1, a.\"postalCode\" AS G2 FROM \"Company\" a WHERE "));
		MatchPlanSimulationRunnerSqlTest.check("all three fields guarded IS NOT NULL: " + sql,
			sql.contains("a.\"country\" IS NOT NULL AND a.\"cityKey\" IS NOT NULL AND a.\"postalCode\" IS NOT NULL AND a.\"nameKey\" IS NOT NULL"));
		MatchPlanSimulationRunnerSqlTest.check("three-column GROUP BY: " + sql, sql.contains("GROUP BY a.\"country\", a.\"cityKey\", a.\"postalCode\""));
		MatchPlanSimulationRunnerSqlTest.check("both HAVING clauses present, then a FETCH FIRST bound (lot volume-4): " + sql, sql.endsWith(
			"HAVING COUNT(DISTINCT a.\"sourceSystem\") > 1 AND COUNT(DISTINCT a.\"nameKey\") > 1 FETCH FIRST 100 ROWS ONLY"));
	}

	/** Lot volume-4 (2026-09-28): the shared SQL string-literal escaping {@link MatchPlanSimulationRunner#rowsByFieldValue}/{@link MatchPlanSimulationRunner#rowsByFieldCombo} both build their {@code IN (...)}/{@code =} clauses with - same doubling rule as {@code ebx.CatalogReader#quote}'s XPath literal. */
	private static void sqlStringLiteral_doublesEmbeddedQuote()
	{
		MatchPlanSimulationRunnerSqlTest.check("plain value quoted", "'BPCE'".equals(MatchPlanSimulationRunner.sqlStringLiteral("BPCE")));
		MatchPlanSimulationRunnerSqlTest.check("embedded quote doubled", "'O''Brien'".equals(MatchPlanSimulationRunner.sqlStringLiteral("O'Brien")));
		MatchPlanSimulationRunnerSqlTest.check("null renders as an empty literal", "''".equals(MatchPlanSimulationRunner.sqlStringLiteral(null)));
	}

	/** Lot volume-4 (2026-09-28): {@link MatchPlanSimulationRunner#comboKey} must join every value in order (so it matches a {@link MatchPlanSimulationRunner#nearMissGroups} group's own column order) and differ on either the content or the number of values. */
	private static void comboKey_joinsValuesWithSeparatorAndDiffersOnOrderOrContent()
	{
		final String key = MatchPlanSimulationRunner.comboKey(new String[] { "FR", "PARIS" });
		MatchPlanSimulationRunnerSqlTest.check("both values present in order: " + key, key.startsWith("FR") && key.endsWith("PARIS") && key.length() > "FRPARIS".length());
		MatchPlanSimulationRunnerSqlTest.check("same values, same key", key.equals(MatchPlanSimulationRunner.comboKey(new String[] { "FR", "PARIS" })));
		MatchPlanSimulationRunnerSqlTest.check("different values, different key",
			!key.equals(MatchPlanSimulationRunner.comboKey(new String[] { "PARIS", "FR" })));
		MatchPlanSimulationRunnerSqlTest.check("single value has no separator", "FR".equals(MatchPlanSimulationRunner.comboKey(new String[] { "FR" })));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
