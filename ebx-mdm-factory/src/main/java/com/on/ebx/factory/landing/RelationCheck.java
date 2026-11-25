package com.on.ebx.factory.landing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Builds, and interprets the answer of, the SQL anti-join that checks one {@code LandingRelation} against
 * the values actually loaded: distinct child values, distinct orphan values (a child value with no
 * matching parent row), and whether each parent key is referenced by at most one child row (the
 * {@code ONE_TO_ONE}/{@code MANY_TO_ONE} split). Pure: no EBX class - {@code com.on.ebx.factory.ebx}'s
 * {@code RelationChecker} is the thin layer that runs the generated SQL with
 * {@code com.orchestranetworks.query.Query}/{@code QueryBuilder} (or falls back to a record-by-record scan)
 * and writes the result back onto the {@code LandingRelation} row.
 * <p>
 * The statement shape below is exactly what {@code docs/SOURCE-RELATIONS.md} &sect;5 validated live against
 * this instance's own EBX SQL engine (a {@code LEFT JOIN} plus {@code COUNT(DISTINCT CASE WHEN ... END)},
 * cross-checked with {@code NOT IN}, plus a {@code FETCH NEXT n ROWS ONLY} sample) and, separately, live
 * against the real landing tables this class was built for
 * ({@code Companies_Landing/CRM}'s {@code CONTACT.ACCOUNT_ID -> ACCOUNT} and {@code ACCOUNT}'s own
 * self-reference {@code PARENT_ACCOUNT_ID -> ACCOUNT}): both single- and self-referencing joins on plain
 * quoted text columns compile and run exactly as generated here. A landing column is always {@code string}
 * (the landing zone imposes no type), so "no value" only ever means {@code NULL} or the empty string - live
 * checked too: an all-{@code NULL} source column ({@code ACCOUNT.PARENT_ACCOUNT_ID}, 41 of 52 rows) lands as
 * SQL {@code NULL}, never {@code ''} (the per-column landing SQL casts with a bare {@code TO_VARCHAR}, never
 * {@code COALESCE}s an individual column - only {@code _sourceId}/{@code _rowHash} do that, see
 * {@link LandingFlow#mapping}); an empty-string source value would land as {@code ''} the same way, so a
 * check for "a real child value" must exclude both, not just {@code IS NULL}.
 * <p>
 * <b>Identifiers</b> - the landing table and field names this is called with, never a raw source name -
 * come from {@link LandingModel#xmlName} (letters, digits, underscore) but may still collide with an EBX
 * SQL reserved word ({@code ORDER}, {@code GROUP}, {@code COLUMN}, even the plain-looking {@code DAY}: see
 * the knowledge base). Every identifier is therefore <b>always</b> double-quoted, unconditionally - the
 * same "never guess from a fixed reserved-word list, just always quote" rule
 * {@code LandingFlow.fromClause}/{@code quoteIdentifier} already applies to the landing SQL itself - and an
 * identifier that itself contains a double quote is refused outright rather than embedded unsafely.
 */
public final class RelationCheck
{
	/** {@code FETCH NEXT n ROWS ONLY} on the orphan sample: enough for a human-readable message, never a full dump. */
	public static final int SAMPLE_SIZE = 10;

	/** Confidence floor/ceiling {@link #adjustConfidence} clamps to either direction: never proposed at 0 (dead) or 100 (unquestionable) from an automated check alone. */
	private static final int MIN_CONFIDENCE = 5;
	private static final int MAX_CONFIDENCE = 99;

	/** Proposal confidence adjustment for a {@link Outcome#CONFIRMED} check: no orphan found. */
	private static final int CONFIRMED_DELTA = 15;
	/** Proposal confidence adjustment for a {@link Outcome#WEAK} check: more than 5% of the distinct child values are orphaned. */
	private static final int WEAK_DELTA = -30;

	private RelationCheck()
	{
	}

	// --------------------------------------------------------------------------------------------------- SQL

	/** The two statements {@link #build} generates: one row of aggregates, and a bounded sample of orphan values. */
	public static final class Statements
	{
		public final String countSql;
		public final String sampleSql;

		Statements(final String pCountSql, final String pSampleSql)
		{
			this.countSql = pCountSql;
			this.sampleSql = pSampleSql;
		}
	}

	/**
	 * The anti-join for one relation, {@code pChildColumns[i]} paired with {@code pParentColumns[i]} (key
	 * order, as {@code LandingRelation.fromColumns}/{@code toColumns} already carry it): {@link
	 * Statements#countSql} answers, in one row, {@code CHILD_ROWS} (rows with a real value in every child
	 * column), {@code DISTINCT_CHILD} (distinct such values) and {@code ORPHAN_CHILD} (of those, how many
	 * have no matching parent row) - {@code CHILD_ROWS} vs {@code DISTINCT_CHILD} is what {@link #interpret}
	 * reads the cardinality off, in the same pass, no second statement needed for that part.
	 * {@link Statements#sampleSql} answers up to {@link #SAMPLE_SIZE} distinct orphan values, for the
	 * message. A composite key is joined column by column ({@code AND}) and counted as one distinct value
	 * via {@code ||} concatenation (confirmed live: EBX SQL has no multi-column {@code COUNT(DISTINCT a, b)})
	 * - every column is already guaranteed non-null/non-empty by the same {@code WHERE} both statements
	 * share, so the concatenation needs no {@code COALESCE} the way {@code LandingFlow}'s own
	 * {@code _sourceId}/{@code _rowHash} concatenation does (there, some columns may legitimately be absent
	 * from the key).
	 */
	public static Statements build(final String pChildTable, final List<String> pChildColumns, final String pParentTable, final List<String> pParentColumns)
	{
		RelationCheck.requireQuotable(pChildTable, "Child table");
		RelationCheck.requireQuotable(pParentTable, "Parent table");
		if (pChildColumns == null || pChildColumns.isEmpty())
		{
			throw new IllegalArgumentException("At least one child column is required");
		}
		if (pParentColumns == null || pParentColumns.size() != pChildColumns.size())
		{
			throw new IllegalArgumentException("Child columns (" + pChildColumns.size() + ") and parent columns ("
				+ (pParentColumns == null ? 0 : pParentColumns.size()) + ") must be the same size, in key order");
		}
		for (final String column : pChildColumns)
		{
			RelationCheck.requireQuotable(column, "Child column");
		}
		for (final String column : pParentColumns)
		{
			RelationCheck.requireQuotable(column, "Parent column");
		}

		// fixed aliases: a landing table/field name can never equal them unquoted-and-bare the way a real
		// column reference could, since every reference to an actual name is always quoted (see the class
		// Javadoc) - "c"/"p" are plain new alias tokens, never resolved against the catalog the way a bare
		// column name would be, so no collision is possible even if a landing table happened to be named "c".
		final String childAlias = "c";
		final String parentAlias = "p";
		final StringBuilder join = new StringBuilder();
		final StringBuilder notNull = new StringBuilder();
		final StringBuilder combo = new StringBuilder();
		for (int i = 0; i < pChildColumns.size(); i++)
		{
			final String childExpr = childAlias + "." + RelationCheck.quote(pChildColumns.get(i));
			final String parentExpr = parentAlias + "." + RelationCheck.quote(pParentColumns.get(i));
			if (i > 0)
			{
				join.append(" AND ");
				notNull.append(" AND ");
				combo.append(" || '|' || ");
			}
			join.append(childExpr).append(" = ").append(parentExpr);
			notNull.append(childExpr).append(" IS NOT NULL AND ").append(childExpr).append(" <> ''");
			combo.append(childExpr);
		}
		// checking the FIRST parent column alone for NULL is enough to tell "no matching row" apart from "a
		// matched row whose parent column happens to be NULL": a match only ever exists when the join
		// predicate held, which requires that very column to equal a non-null, non-empty child value (SQL
		// equality is never true against NULL) - so a NULL here can only mean the LEFT JOIN found nothing.
		final String parentUnmatched = parentAlias + "." + RelationCheck.quote(pParentColumns.get(0)) + " IS NULL";
		final String from =
			RelationCheck.quote(pChildTable) + " " + childAlias + " LEFT JOIN " + RelationCheck.quote(pParentTable) + " " + parentAlias + " ON " + join;

		final String countSql = "SELECT COUNT(*) AS CHILD_ROWS, COUNT(DISTINCT " + combo + ") AS DISTINCT_CHILD, COUNT(DISTINCT CASE WHEN " + parentUnmatched
			+ " THEN " + combo + " END) AS ORPHAN_CHILD FROM " + from + " WHERE " + notNull;
		final String sampleSql = "SELECT DISTINCT " + combo + " AS ORPHAN_VALUE FROM " + from + " WHERE " + notNull + " AND " + parentUnmatched + " ORDER BY "
			+ combo + " FETCH NEXT " + RelationCheck.SAMPLE_SIZE + " ROWS ONLY";
		return new Statements(countSql, sampleSql);
	}

	/**
	 * Refuses a null/blank identifier outright, and one containing a double quote (it could otherwise close
	 * the quoting early and inject arbitrary SQL into the generated statement) - never reached in practice
	 * for a name that actually came from {@code LandingModel.xmlName} ({@code [A-Za-z0-9_]} only), but this
	 * is the last line of defence if it is ever called with something else.
	 */
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
		return "\"" + pIdentifier.replace("\"", "\"\"") + "\""; // a quote inside a name is doubled (security review, 2026-09-24)
	}

	// --------------------------------------------------------------------------------------------------- interpretation

	public enum Outcome
	{
		/** No orphan at all. */
		CONFIRMED,
		/** Orphans exist but are at most 5% of the distinct child values. */
		MOSTLY,
		/** More than 5% of the distinct child values are orphaned. */
		WEAK,
		/** No real (non-null, non-empty) child value at all: nothing can be said either way. */
		EMPTY
	}

	public static final class Result
	{
		public final Outcome outcome;
		/** {@code ONE_TO_ONE}/{@code MANY_TO_ONE}; {@code null} for {@link Outcome#EMPTY} - nothing to assert, the caller leaves the field as it was. */
		public final String cardinality;
		/** Signed, unclamped: {@link #CONFIRMED_DELTA}, {@link #WEAK_DELTA} or 0 (MOSTLY/EMPTY propose no change). Clamp with {@link #adjustConfidence}. */
		public final int confidenceDelta;
		public final String message;

		Result(final Outcome pOutcome, final String pCardinality, final int pConfidenceDelta, final String pMessage)
		{
			this.outcome = pOutcome;
			this.cardinality = pCardinality;
			this.confidenceDelta = pConfidenceDelta;
			this.message = pMessage;
		}
	}

	/**
	 * Turns the three figures {@link Statements#countSql} answers, plus the sample {@link
	 * Statements#sampleSql} answers, into an {@link Outcome}, a cardinality, a proposed (unclamped)
	 * confidence delta and an English message. Pure: takes plain numbers, not a {@code Tuple} - {@code
	 * RelationChecker} reads the query answer into these first.
	 */
	public static Result interpret(final long pDistinctChildValues, final long pChildRows, final long pOrphanValues, final List<String> pOrphanSample)
	{
		if (pDistinctChildValues <= 0)
		{
			return new Result(Outcome.EMPTY, null, 0, "No child value: nothing can be said.");
		}
		// each distinct child value used by exactly one row -> each parent key is referenced by at most one
		// child row (ONE_TO_ONE); any repeat among the child rows -> MANY_TO_ONE. Both counts come from the
		// same countSql row, already filtered to real (non-null, non-empty) values by its own WHERE clause.
		final String cardinality = pChildRows == pDistinctChildValues ? "ONE_TO_ONE" : "MANY_TO_ONE";
		if (pOrphanValues <= 0)
		{
			return new Result(Outcome.CONFIRMED, cardinality, RelationCheck.CONFIRMED_DELTA, pDistinctChildValues + " distinct value(s), 0 orphan(s).");
		}
		// <= 5%, integer-only: pOrphanValues / pDistinctChildValues <= 0.05  <=>  20 * pOrphanValues <= pDistinctChildValues
		final boolean mostly = pOrphanValues * 20L <= pDistinctChildValues;
		final String message = pDistinctChildValues + " distinct value(s), " + pOrphanValues + " orphan(s) (" + RelationCheck.percent(pOrphanValues, pDistinctChildValues)
			+ "%)" + RelationCheck.sampleText(pOrphanSample) + ".";
		return mostly ? new Result(Outcome.MOSTLY, cardinality, 0, message) : new Result(Outcome.WEAK, cardinality, RelationCheck.WEAK_DELTA, message);
	}

	private static String percent(final long pOrphanValues, final long pDistinctChildValues)
	{
		return BigDecimal.valueOf(pOrphanValues).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(pDistinctChildValues), 2, RoundingMode.HALF_UP)
			.toPlainString();
	}

	private static String sampleText(final List<String> pOrphanSample)
	{
		if (pOrphanSample == null || pOrphanSample.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(": e.g. ");
		for (int i = 0; i < pOrphanSample.size(); i++)
		{
			if (i > 0)
			{
				out.append(", ");
			}
			out.append(pOrphanSample.get(i));
		}
		return out.toString();
	}

	/** {@code pCurrent + pDelta}, clamped to {@link #MIN_CONFIDENCE}/{@link #MAX_CONFIDENCE} either way (only one bound ever actually bites, given {@link #CONFIRMED_DELTA}/{@link #WEAK_DELTA}'s signs, but clamping both is harmless and simpler than picking). */
	public static int adjustConfidence(final int pCurrent, final int pDelta)
	{
		final int result = pCurrent + pDelta;
		if (result > RelationCheck.MAX_CONFIDENCE)
		{
			return RelationCheck.MAX_CONFIDENCE;
		}
		if (result < RelationCheck.MIN_CONFIDENCE)
		{
			return RelationCheck.MIN_CONFIDENCE;
		}
		return result;
	}
}
