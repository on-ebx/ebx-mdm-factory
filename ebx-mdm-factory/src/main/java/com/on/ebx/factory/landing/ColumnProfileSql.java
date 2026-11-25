package com.on.ebx.factory.landing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds, and interprets the answer of, ONE EBX SQL aggregate query per landing table that measures every
 * one of its columns at once: {@code fillRate}/{@code distinctCount}/{@code maxLength} of {@code
 * LandingColumn} (measured null for a sample-file source, see {@code ebx.LandingColumnProfiler}'s own
 * Javadoc). Pure: no EBX class - {@code ebx.LandingColumnProfiler} is the thin layer that runs the
 * generated SQL with {@code com.orchestranetworks.query.Query}/{@code QueryResult} and writes the result
 * back onto each {@code LandingColumn} row, the exact same {@code landing.RelationCheck}/{@code
 * ebx.RelationChecker} split this module already keeps for its other generated-SQL feature.
 * <p>
 * <b>"Filled" means non-null AND non-empty</b> - a landing column is always {@code string} and an empty
 * source value lands as {@code ''}, never distinguishable from "no value" by intent (see {@code
 * landing.RelationCheck}'s own Javadoc for the same finding on the same landing tables, and the per-column
 * landing SQL's bare {@code TO_VARCHAR} cast, {@code landing.LandingFlow#mapping}, which never {@code
 * COALESCE}s an individual column): every aggregate below excludes both {@code IS NULL} and {@code = ''}
 * with the identical {@code CASE WHEN ... IS NOT NULL AND ... <> '' THEN ... END} guard {@code
 * RelationCheck#build} already uses, for consistency across the module's two generated-SQL features rather
 * than two different conventions for the same underlying question.
 * <p>
 * <b>Identifiers</b> - a landing table/field name never comes from raw source text, only from {@code
 * LandingModel.xmlName} (letters, digits, underscore - see {@code landing.LandingFlow}), but may still
 * collide with an EBX SQL reserved word ({@code ORDER}, {@code GROUP}, {@code COLUMN}, even the
 * plain-looking {@code DAY}: the knowledge base). Every identifier is therefore <b>always</b> double-quoted,
 * unconditionally, and one containing a double quote is refused rather than embedded unsafely - the same
 * rule {@link RelationCheck} already applies.
 * <p>
 * <b>String length function</b>: {@code CHAR_LENGTH} - the ANSI SQL standard name, chosen because it was
 * not possible to verify live against this instance's own EBX SQL engine in this lot (the instance was
 * unreachable while this was written - see {@code docs/STAGING-PROPOSAL.md}, "What was NOT run live").
 * {@code ebx.LandingColumnProfiler#profile} degrades a single table's failing query to a skip-and-log
 * (never throws), so a wrong function name here costs "no profile for this run" on the tables it affects,
 * not a hard failure - re-verify against a live instance before trusting {@code maxLength} values, and
 * switch to {@code LENGTH} here (one line) if {@code CHAR_LENGTH} turns out unsupported.
 * <p>
 * <b>Volume-guards lot (2026-09-28, task item 7, "column profiling on a bounded sample"):</b> {@link #build}
 * takes an optional {@code pSampleLimit} - positive, it profiles a subquery bounded to the first {@code
 * pSampleLimit} rows ({@code FROM (SELECT * FROM "table" LIMIT n) AS "table"}) instead of the whole landing
 * table, so a huge table (millions of rows) no longer pays a full-table {@code COUNT(DISTINCT ...)} per
 * column just to profile it. Confirmed live against this instance's own EBX SQL dialect reference
 * ({@code get_query_syntax}, 2026-09-28: "Paging: `LIMIT n` ✅") before writing this, rather than assumed -
 * no Java-side fallback (an unbounded read capped in Java with a per-column {@code HashSet}) is needed, since
 * the dialect supports the SQL form directly.
 */
public final class ColumnProfileSql
{
	private ColumnProfileSql()
	{
	}

	/** One column of the table being profiled: its {@code LandingColumn} row id, and the EBX field name the query reads. */
	public static final class ColumnRef
	{
		public final String columnId;
		public final String fieldName;

		public ColumnRef(final String pColumnId, final String pFieldName)
		{
			this.columnId = pColumnId;
			this.fieldName = pFieldName;
		}
	}

	/** The generated statement, plus {@link #columnIds} in the exact order its aliases ({@code F0}/{@code D0}/{@code L0}, {@code F1}/...) were assigned - what {@link #interpret} needs to read the answer back. */
	public static final class Statement
	{
		public final String sql;
		public final List<String> columnIds;
		/** Volume-guards lot (2026-09-28, task item 7): the {@code pSampleLimit} this statement was built with - 0 when unbounded (the whole table). What a caller compares {@code ROW_COUNT} against to tell "the cap was reached" (there may be more rows beyond it) from "the whole table fit under the cap anyway". */
		public final int sampleLimit;

		private Statement(final String pSql, final List<String> pColumnIds, final int pSampleLimit)
		{
			this.sql = pSql;
			this.columnIds = pColumnIds;
			this.sampleLimit = pSampleLimit;
		}
	}

	/**
	 * Same as {@link #build(String, List, int)} with no sample bound (0) - profiles the whole table, the
	 * pre-existing behaviour before the volume-guards lot.
	 */
	public static Statement build(final String pTableName, final List<ColumnRef> pColumns)
	{
		return ColumnProfileSql.build(pTableName, pColumns, 0);
	}

	/**
	 * One row, {@code COUNT(*)} plus, per column in {@code pColumns} order, a filled-count/distinct-count/
	 * max-length triple - never one query per column (the task brief: "ONE EBX SQL aggregate query per
	 * table"). Refuses (throws {@link IllegalArgumentException}) an empty column list or an unquotable
	 * identifier - the same defence-in-depth {@link RelationCheck#build} already applies, never reached in
	 * practice for a name that actually came from {@code LandingModel.xmlName}.
	 *
	 * @param pSampleLimit volume-guards lot (2026-09-28, task item 7): positive bounds the aggregate to the
	 *            first {@code pSampleLimit} rows of the table ({@code FROM (SELECT * FROM "table" LIMIT n) AS
	 *            "table"} - see class Javadoc); 0 or negative profiles the whole table (unbounded), the
	 *            pre-existing behaviour.
	 */
	public static Statement build(final String pTableName, final List<ColumnRef> pColumns, final int pSampleLimit)
	{
		ColumnProfileSql.requireQuotable(pTableName, "Table");
		if (pColumns == null || pColumns.isEmpty())
		{
			throw new IllegalArgumentException("At least one column is required");
		}
		final StringBuilder select = new StringBuilder("SELECT COUNT(*) AS ROW_COUNT");
		final List<String> columnIds = new ArrayList<String>();
		int i = 0;
		for (final ColumnRef column : pColumns)
		{
			ColumnProfileSql.requireQuotable(column.fieldName, "Column");
			final String col = ColumnProfileSql.quote(column.fieldName);
			final String guard = col + " IS NOT NULL AND " + col + " <> ''";
			select.append(", COUNT(CASE WHEN ").append(guard).append(" THEN 1 END) AS F").append(i);
			select.append(", COUNT(DISTINCT CASE WHEN ").append(guard).append(" THEN ").append(col).append(" END) AS D").append(i);
			select.append(", MAX(CASE WHEN ").append(guard).append(" THEN CHAR_LENGTH(").append(col).append(") END) AS L").append(i);
			columnIds.add(column.columnId);
			i++;
		}
		final String quotedTable = ColumnProfileSql.quote(pTableName);
		if (pSampleLimit > 0)
		{
			// bounded sample (task item 7): EBX SQL supports LIMIT directly (get_query_syntax, 2026-09-28) - no
			// Java-side fallback needed. The subquery is re-aliased to the SAME quoted table name so the column
			// references built above (bare, unqualified) keep resolving exactly as they did unbounded.
			select.append(" FROM (SELECT * FROM ").append(quotedTable).append(" LIMIT ").append(pSampleLimit).append(") AS ").append(quotedTable);
		}
		else
		{
			select.append(" FROM ").append(quotedTable);
		}
		return new Statement(select.toString(), columnIds, Math.max(pSampleLimit, 0));
	}

	/** The measured profile of one column: {@link #fillRatePercent} is 0-100 (rounded to 2 decimals), null only when {@code pRowCount} is 0 (nothing to divide by - a caller then leaves the stored value untouched rather than writing a meaningless 0). */
	public static final class ColumnProfile
	{
		public final String columnId;
		public final BigDecimal fillRatePercent;
		public final long distinctCount;
		/** Null when the column has no filled row at all (nothing to measure the length of). */
		public final Integer maxLength;

		private ColumnProfile(final String pColumnId, final BigDecimal pFillRatePercent, final long pDistinctCount, final Integer pMaxLength)
		{
			this.columnId = pColumnId;
			this.fillRatePercent = pFillRatePercent;
			this.distinctCount = pDistinctCount;
			this.maxLength = pMaxLength;
		}
	}

	/**
	 * Reads back one answer row of {@link #build}'s statement (already flattened to a plain {@code
	 * Map<String, Object>} of alias -&gt; raw JDBC value by the caller - see {@code
	 * ebx.LandingColumnProfiler#toMap} - so this stays free of any query-engine type, defensively coerced
	 * with {@link #asLong} exactly like {@code RelationChecker} already does for its own aggregate query,
	 * "not assumed to share [EBX Script's known Long-&gt;BigDecimal] coercion here without being able to
	 * write a probe row to confirm it live").
	 */
	public static List<ColumnProfile> interpret(final Statement pStatement, final Map<String, Object> pRow)
	{
		final List<ColumnProfile> out = new ArrayList<ColumnProfile>();
		final long rowCount = ColumnProfileSql.asLong(pRow.get("ROW_COUNT"));
		int i = 0;
		for (final String columnId : pStatement.columnIds)
		{
			final long filled = ColumnProfileSql.asLong(pRow.get("F" + i));
			final long distinct = ColumnProfileSql.asLong(pRow.get("D" + i));
			final Object rawMaxLength = pRow.get("L" + i);
			final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
			final BigDecimal fillRate = rowCount <= 0 ? null
				: BigDecimal.valueOf(filled).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(rowCount), 2, RoundingMode.HALF_UP);
			out.add(new ColumnProfile(columnId, fillRate, distinct, maxLength));
			i++;
		}
		return out;
	}

	/**
	 * Volume-guards lot (2026-09-28, task item 7): whether the sample cap was actually REACHED - i.e. there may
	 * be more rows beyond it that this profile never saw - as opposed to the whole table simply fitting under
	 * the cap anyway (in which case this profile IS the whole table, not a partial sample). {@code
	 * pRowCountInAnswer} is the same {@code ROW_COUNT} value {@link #interpret} already reads off the answer -
	 * callers that also call {@link #interpret} read it once and pass it here rather than re-deriving it.
	 */
	public static boolean sampled(final Statement pStatement, final long pRowCountInAnswer)
	{
		return pStatement.sampleLimit > 0 && pRowCountInAnswer >= pStatement.sampleLimit;
	}

	private static long asLong(final Object pValue)
	{
		if (pValue instanceof Number)
		{
			return ((Number) pValue).longValue();
		}
		return pValue == null ? 0L : Long.parseLong(String.valueOf(pValue));
	}

	// --------------------------------------------------------------------------------------------------- identifiers

	private static void requireQuotable(final String pIdentifier, final String pWhat)
	{
		if (pIdentifier == null || pIdentifier.trim().isEmpty())
		{
			throw new IllegalArgumentException(pWhat + " is required");
		}
		if (pIdentifier.indexOf('"') >= 0)
		{
			throw new IllegalArgumentException(pWhat + " '" + pIdentifier + "' contains a double quote: refused rather than embedded unsafely in generated SQL.");
		}
	}

	private static String quote(final String pIdentifier)
	{
		return "\"" + pIdentifier + "\"";
	}
}
