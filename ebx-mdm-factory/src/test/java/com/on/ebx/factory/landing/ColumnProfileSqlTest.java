package com.on.ebx.factory.landing;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link ColumnProfileSql} (volume-guards lot, 2026-09-28, task item 7). */
public final class ColumnProfileSqlTest
{
	public static void main(final String[] pArgs)
	{
		ColumnProfileSqlTest.unboundedBuildHasNoLimitAndKeepsTheOldFromClause();
		ColumnProfileSqlTest.boundedBuildWrapsAPlainLimitSubquery();
		ColumnProfileSqlTest.negativeOrZeroLimitMeansUnbounded();
		ColumnProfileSqlTest.interpretUnaffectedByTheSampleLimit();
		ColumnProfileSqlTest.sampledOnlyWhenTheCapWasActuallyReached();
		System.out.println("ColumnProfileSqlTest OK");
	}

	/** {@link ColumnProfileSql#build(String, List)} (no limit argument) is unchanged: plain FROM, no subquery. */
	private static void unboundedBuildHasNoLimitAndKeepsTheOldFromClause()
	{
		final ColumnProfileSql.Statement statement = ColumnProfileSql.build("MyTable", Arrays.asList(new ColumnProfileSql.ColumnRef("C1", "name")));
		ColumnProfileSqlTest.check("no LIMIT in the generated SQL: " + statement.sql, !statement.sql.toUpperCase(java.util.Locale.ROOT).contains("LIMIT"));
		ColumnProfileSqlTest.check("plain FROM \"MyTable\", not a subquery: " + statement.sql, statement.sql.endsWith("FROM \"MyTable\""));
		ColumnProfileSqlTest.check("sampleLimit reads 0 (unbounded)", statement.sampleLimit == 0);
	}

	/** Volume-guards lot (2026-09-28, task item 7): a positive limit wraps the FROM in a bounded subquery, re-aliased to the same quoted table name so the bare column references keep resolving. */
	private static void boundedBuildWrapsAPlainLimitSubquery()
	{
		final ColumnProfileSql.Statement statement = ColumnProfileSql.build("MyTable", Arrays.asList(new ColumnProfileSql.ColumnRef("C1", "name")), 50000);
		ColumnProfileSqlTest.check("sampleLimit reads back exactly what was asked", statement.sampleLimit == 50000);
		ColumnProfileSqlTest.check("FROM is a LIMIT-bounded subquery: " + statement.sql,
			statement.sql.endsWith("FROM (SELECT * FROM \"MyTable\" LIMIT 50000) AS \"MyTable\""));
		// the per-column aggregate expressions (built before the FROM clause) are untouched by the sampling -
		// same guard/alias shape as the unbounded form, just a different table.
		ColumnProfileSqlTest.check("column aggregate expressions unchanged: " + statement.sql,
			statement.sql.contains("COUNT(CASE WHEN \"name\" IS NOT NULL AND \"name\" <> '' THEN 1 END) AS F0"));
	}

	private static void negativeOrZeroLimitMeansUnbounded()
	{
		final List<ColumnProfileSql.ColumnRef> columns = Arrays.asList(new ColumnProfileSql.ColumnRef("C1", "name"));
		ColumnProfileSqlTest.check("0 means unbounded", ColumnProfileSql.build("T", columns, 0).sampleLimit == 0);
		ColumnProfileSqlTest.check("a negative value is floored to 0 (unbounded), never a negative LIMIT",
			ColumnProfileSql.build("T", columns, -5).sampleLimit == 0);
		ColumnProfileSqlTest.check("0 produces the same plain FROM as the no-limit overload",
			ColumnProfileSql.build("T", columns, 0).sql.equals(ColumnProfileSql.build("T", columns).sql));
	}

	/** {@link ColumnProfileSql#interpret} reads the same F/D/L/ROW_COUNT aliases whatever the sample limit was - the answer shape is identical, only the population it was computed over differs. */
	private static void interpretUnaffectedByTheSampleLimit()
	{
		final ColumnProfileSql.Statement statement = ColumnProfileSql.build("T", Arrays.asList(new ColumnProfileSql.ColumnRef("C1", "name")), 1000);
		final Map<String, Object> row = new HashMap<String, Object>();
		row.put("ROW_COUNT", Long.valueOf(1000));
		row.put("F0", Long.valueOf(900));
		row.put("D0", Long.valueOf(50));
		row.put("L0", Integer.valueOf(40));
		final List<ColumnProfileSql.ColumnProfile> profiles = ColumnProfileSql.interpret(statement, row);
		ColumnProfileSqlTest.check("one profile", profiles.size() == 1);
		final ColumnProfileSql.ColumnProfile profile = profiles.get(0);
		ColumnProfileSqlTest.check("fill rate computed over the SAMPLE's own row count (90%)", profile.fillRatePercent.intValue() == 90);
		ColumnProfileSqlTest.check("distinct count read through", profile.distinctCount == 50);
		ColumnProfileSqlTest.check("max length read through", profile.maxLength.intValue() == 40);
	}

	/**
	 * {@link ColumnProfileSql#sampled}: true only when the answer's own ROW_COUNT reached the configured limit
	 * (there may be more rows beyond it) - a table that turned out smaller than the cap is NOT "sampled", it
	 * was profiled in full.
	 */
	private static void sampledOnlyWhenTheCapWasActuallyReached()
	{
		final ColumnProfileSql.Statement bounded = ColumnProfileSql.build("T", Arrays.asList(new ColumnProfileSql.ColumnRef("C1", "name")), 1000);
		ColumnProfileSqlTest.check("row count == limit: sampled (there may be more)", ColumnProfileSql.sampled(bounded, 1000L));
		ColumnProfileSqlTest.check("row count > limit (should not happen with a real LIMIT, but never misreported as NOT sampled)",
			ColumnProfileSql.sampled(bounded, 1500L));
		ColumnProfileSqlTest.check("row count < limit: the whole table fit under the cap - NOT sampled", !ColumnProfileSql.sampled(bounded, 400L));

		final ColumnProfileSql.Statement unbounded = ColumnProfileSql.build("T", Arrays.asList(new ColumnProfileSql.ColumnRef("C1", "name")));
		ColumnProfileSqlTest.check("unbounded statement is never reported as sampled, whatever the row count", !ColumnProfileSql.sampled(unbounded, 999999L));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
