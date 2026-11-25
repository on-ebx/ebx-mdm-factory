package com.on.ebx.factory.landing;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free: run the main, a failure throws. */
public final class SchemaFilterTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> empty = Collections.<String>emptyList();
		final List<String> blankOnly = Arrays.asList(" ", "", null);

		SchemaFilterTest.check("empty filter keeps any schema", SchemaFilter.keeps(empty, "TPCH_SF1"));
		SchemaFilterTest.check("empty filter keeps a null schema too", SchemaFilter.keeps(empty, null));
		SchemaFilterTest.check("a blank-only filter behaves like an empty one", SchemaFilter.keeps(blankOnly, "TPCH_SF1"));
		SchemaFilterTest.check("a non-empty filter never keeps a null schema", !SchemaFilter.keeps(Arrays.asList("TPCH_SF1"), null));

		SchemaFilterTest.check("a bare entry matches the bare schema of a single-database source", SchemaFilter.keeps(Arrays.asList("TPCH_SF1"), "TPCH_SF1"));
		SchemaFilterTest.check("a bare entry matches the trailing segment of a database-qualified schema",
			SchemaFilter.keeps(Arrays.asList("ERP_SOURCE"), "SNOWFLAKE_LEARNING_DB.ERP_SOURCE"));
		SchemaFilterTest.check("case does not matter", SchemaFilter.keeps(Arrays.asList("erp_source"), "SNOWFLAKE_LEARNING_DB.ERP_SOURCE"));
		SchemaFilterTest.check("a fully qualified entry matches the same database and schema",
			SchemaFilter.keeps(Arrays.asList("SNOWFLAKE_LEARNING_DB.ERP_SOURCE"), "SNOWFLAKE_LEARNING_DB.ERP_SOURCE"));

		SchemaFilterTest.check("segments are whole, not a partial word: LEARNING_DB.ERP_SOURCE does not match SNOWFLAKE_LEARNING_DB.ERP_SOURCE",
			!SchemaFilter.keeps(Arrays.asList("LEARNING_DB.ERP_SOURCE"), "SNOWFLAKE_LEARNING_DB.ERP_SOURCE"));
		SchemaFilterTest.check("a fully qualified entry naming a different database does not match",
			!SchemaFilter.keeps(Arrays.asList("OTHER_DB.ERP_SOURCE"), "SNOWFLAKE_LEARNING_DB.ERP_SOURCE"));
		SchemaFilterTest.check("an entry with more segments than the schema never matches",
			!SchemaFilter.keeps(Arrays.asList("A.B.ERP_SOURCE"), "SNOWFLAKE_LEARNING_DB.ERP_SOURCE"));
		SchemaFilterTest.check("an unrelated bare entry does not match", !SchemaFilter.keeps(Arrays.asList("CRM_SOURCE"), "SNOWFLAKE_LEARNING_DB.ERP_SOURCE"));

		// report(): unmatched entries
		final List<String> scanned = Arrays.asList("SNOWFLAKE_LEARNING_DB.ERP_SOURCE", "SNOWFLAKE_LEARNING_DB.CRM_SOURCE", "TPCH_SF1");
		SchemaFilter.Report r = SchemaFilter.report(Arrays.asList("ERP_SOURCE", "DOES_NOT_EXIST"), scanned);
		SchemaFilterTest.check("a matching entry is not reported unmatched", !r.unmatched.contains("ERP_SOURCE"));
		SchemaFilterTest.check("a typo'd entry is reported unmatched", r.unmatched.contains("DOES_NOT_EXIST"));
		SchemaFilterTest.check("no ambiguity when every entry matches exactly one schema", r.ambiguous.isEmpty());

		// report(): ambiguity across databases sharing a bare schema name
		final List<String> scannedTwoDatabases = Arrays.asList("DB1.ERP_SOURCE", "DB2.ERP_SOURCE");
		r = SchemaFilter.report(Arrays.asList("ERP_SOURCE"), scannedTwoDatabases);
		SchemaFilterTest.check("an entry matching two databases is reported ambiguous, not unmatched", r.unmatched.isEmpty() && r.ambiguous.size() == 1);
		SchemaFilterTest.check("the ambiguous entry carries the schemas it matched",
			r.ambiguous.get(0).entry.equals("ERP_SOURCE") && r.ambiguous.get(0).schemas.containsAll(scannedTwoDatabases) && r.ambiguous.get(0).schemas.size() == 2);
		SchemaFilterTest.check("both schemas are still kept despite the ambiguity", SchemaFilter.keeps(Arrays.asList("ERP_SOURCE"), "DB1.ERP_SOURCE")
			&& SchemaFilter.keeps(Arrays.asList("ERP_SOURCE"), "DB2.ERP_SOURCE"));

		// report(): a fully qualified entry disambiguates
		r = SchemaFilter.report(Arrays.asList("DB1.ERP_SOURCE"), scannedTwoDatabases);
		SchemaFilterTest.check("a fully qualified entry matches only its own database: no ambiguity", r.ambiguous.isEmpty() && r.unmatched.isEmpty());

		// report(): blank entries are ignored, never reported
		r = SchemaFilter.report(Arrays.asList(" ", "ERP_SOURCE"), scanned);
		SchemaFilterTest.check("a blank entry is not reported at all", !r.unmatched.contains(" ") && r.unmatched.size() + r.ambiguous.size() <= 1);

		System.out.println("SchemaFilterTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
