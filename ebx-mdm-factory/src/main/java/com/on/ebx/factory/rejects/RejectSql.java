package com.on.ebx.factory.rejects;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The EBX SQL of the transfer-reject review (audit F-38, wave 10): everything is computed by the database - a page, a count, the
 * summary per entity, stage and status - instead of reading every reject of a project and sorting it in memory (the old Cockpit).
 * Pure strings, no EBX type, so a test checks the text. Dialect facts, measured on the instance's {@code data-access} SQL tool
 * (2026-10-07): {@code COALESCE}, {@code CASE}, {@code LIKE ... ESCAPE}, {@code ORDER BY ... OFFSET ... FETCH NEXT} are accepted; a
 * foreign key is read through the target's own key ({@code t."project"."code"}, {@code t."entity"."id"}); the table name is quoted.
 * <p>
 * A row written before the review fields existed has {@code acknowledged} null: every test goes through {@code COALESCE(..., false)}.
 * Values are inlined as escaped literals (a quote is doubled, {@code %} and {@code _} escaped in a LIKE), never concatenated raw.
 */
public final class RejectSql
{
	private RejectSql()
	{
	}

	private static final String FROM = " FROM \"TransferReject\" t";
	private static final String IS_RESOLVED = "COALESCE(t.\"resolved\", false) = true";
	private static final String NOT_RESOLVED = "COALESCE(t.\"resolved\", false) = false";
	private static final String IS_ACKNOWLEDGED = "COALESCE(t.\"acknowledged\", false) = true";
	private static final String NOT_ACKNOWLEDGED = "COALESCE(t.\"acknowledged\", false) = false";
	private static final String OPEN = "(" + RejectSql.NOT_RESOLVED + " AND " + RejectSql.NOT_ACKNOWLEDGED + ")";
	private static final String ACKNOWLEDGED = "(" + RejectSql.NOT_RESOLVED + " AND " + RejectSql.IS_ACKNOWLEDGED + ")";

	/** The columns of {@link #page}, in the order {@code RejectReview} reads them (aliases are upper case, as the other readers use). */
	public static final String[] COLUMNS = { "ENT", "STG", "SYS", "SID", "LDT", "LID", "RSN", "RAT", "RES", "ACK", "RBY", "RVA", "RCM", "CAT", "RCD", "DET" };

	/** A SQL string literal. */
	public static String literal(final String pValue)
	{
		return "'" + (pValue == null ? "" : pValue.replace("'", "''")) + "'";
	}

	/** A LIKE pattern matching {@code pText} anywhere, case-insensitively, the text itself taken literally ({@code ESCAPE '\'}); lower-cased here, compared with {@code LOWER(column)}. */
	static String containsPattern(final String pText)
	{
		final String lower = pText.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return RejectSql.literal("%" + lower + "%") + " ESCAPE '\\'";
	}

	private static String timestamp(final Date pDate)
	{
		return "TIMESTAMP '" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(pDate) + "'";
	}

	/** The WHERE condition (without the word) of one project and one filter; every clause refers to the alias {@code t}. */
	public static String where(final String pProjectCode, final RejectFilter pFilter)
	{
		final StringBuilder sb = new StringBuilder("t.\"project\".\"code\" = ").append(RejectSql.literal(pProjectCode));
		if (pFilter == null)
		{
			return sb.toString();
		}
		if (!pFilter.entityId.isEmpty())
		{
			sb.append(" AND t.\"entity\".\"id\" = ").append(RejectSql.literal(pFilter.entityId));
		}
		if (!pFilter.stage.isEmpty())
		{
			sb.append(" AND t.\"stage\" = ").append(RejectSql.literal(pFilter.stage));
		}
		if (RejectFilter.OPEN.equals(pFilter.status))
		{
			sb.append(" AND ").append(RejectSql.OPEN);
		}
		else if (RejectFilter.ACKNOWLEDGED.equals(pFilter.status))
		{
			sb.append(" AND ").append(RejectSql.ACKNOWLEDGED);
		}
		else if (RejectFilter.RESOLVED.equals(pFilter.status))
		{
			sb.append(" AND ").append(RejectSql.IS_RESOLVED);
		}
		if (RejectFilter.UNCODED.equals(pFilter.reasonCode))
		{
			sb.append(" AND t.\"reasonCode\" IS NULL");
		}
		else if (!pFilter.reasonCode.isEmpty())
		{
			sb.append(" AND t.\"reasonCode\" = ").append(RejectSql.literal(pFilter.reasonCode));
		}
		if (!pFilter.reasonContains.isEmpty())
		{
			sb.append(" AND LOWER(t.\"reasons\") LIKE ").append(RejectSql.containsPattern(pFilter.reasonContains));
		}
		if (pFilter.from != null)
		{
			sb.append(" AND t.\"rejectedAt\" >= ").append(RejectSql.timestamp(pFilter.from));
		}
		if (pFilter.to != null)
		{
			sb.append(" AND t.\"rejectedAt\" < ").append(RejectSql.timestamp(pFilter.toExclusive()));
		}
		return sb.toString();
	}

	/** One page of rows, most recent first (the key columns break ties, so the paging is stable). {@code pOffset >= 0}, {@code 1 <= pLimit}. */
	public static String page(final String pProjectCode, final RejectFilter pFilter, final int pOffset, final int pLimit)
	{
		return "SELECT t.\"entity\".\"id\" AS ENT, t.\"stage\" AS STG, t.\"sourceSystem\" AS SYS, t.\"sourceId\" AS SID, t.\"landingTable\" AS LDT, t.\"loadId\" AS LID, "
			+ "t.\"reasons\" AS RSN, t.\"rejectedAt\" AS RAT, t.\"resolved\" AS RES, t.\"acknowledged\" AS ACK, t.\"reviewedBy\" AS RBY, t.\"reviewedAt\" AS RVA, "
			+ "t.\"reviewComment\" AS RCM, t.\"category\" AS CAT, t.\"reasonCode\" AS RCD, t.\"detail\" AS DET" + RejectSql.FROM + " WHERE " + RejectSql.where(pProjectCode, pFilter)
			+ " ORDER BY t.\"rejectedAt\" DESC, t.\"entity\".\"id\", t.\"stage\", t.\"sourceSystem\", t.\"sourceId\" OFFSET " + Math.max(0, pOffset) + " ROWS FETCH NEXT "
			+ Math.max(1, pLimit) + " ROWS ONLY";
	}

	/** The number of rows a filter matches. */
	public static String count(final String pProjectCode, final RejectFilter pFilter)
	{
		return "SELECT COUNT(*) AS N" + RejectSql.FROM + " WHERE " + RejectSql.where(pProjectCode, pFilter);
	}

	private static final String KEY_ENT = "t.\"entity\".\"id\"";
	private static final String KEY_ORDER = " ORDER BY t.\"entity\".\"id\", t.\"stage\", t.\"sourceSystem\", t.\"sourceId\"";

	/**
	 * F2-103: the condition "strictly after this key" in the key order (entity, stage, source system, source id) - the project is fixed by the WHERE, so it is not part of the
	 * comparison. Written as an OR of equality prefixes (no row-value comparison: the dialect's support was never measured). Empty text for no key (the first page).
	 */
	static String after(final RejectRef pAfter)
	{
		if (pAfter == null)
		{
			return "";
		}
		final String ent = RejectSql.KEY_ENT + " = " + RejectSql.literal(pAfter.entityId);
		final String stage = "t.\"stage\" = " + RejectSql.literal(pAfter.stage);
		final String sys = "t.\"sourceSystem\" = " + RejectSql.literal(pAfter.sourceSystem);
		return " AND ((" + RejectSql.KEY_ENT + " > " + RejectSql.literal(pAfter.entityId) + ")"
			+ " OR (" + ent + " AND t.\"stage\" > " + RejectSql.literal(pAfter.stage) + ")"
			+ " OR (" + ent + " AND " + stage + " AND t.\"sourceSystem\" > " + RejectSql.literal(pAfter.sourceSystem) + ")"
			+ " OR (" + ent + " AND " + stage + " AND " + sys + " AND t.\"sourceId\" > " + RejectSql.literal(pAfter.sourceId) + "))";
	}

	/**
	 * F2-103: one page of the KEYS of the rows a filter matches, by key order and strictly after {@code pAfter} (null = from the start) - no {@code OFFSET}, so a page costs the
	 * same at the millionth row as at the first (the old offset paging sorted and skipped everything before the page each time: quadratic). What a bulk action reads before it writes.
	 */
	public static String keys(final String pProjectCode, final RejectFilter pFilter, final RejectRef pAfter, final int pLimit)
	{
		return "SELECT t.\"entity\".\"id\" AS ENT, t.\"stage\" AS STG, t.\"sourceSystem\" AS SYS, t.\"sourceId\" AS SID" + RejectSql.FROM + " WHERE "
			+ RejectSql.where(pProjectCode, pFilter) + RejectSql.after(pAfter) + RejectSql.KEY_ORDER + " FETCH NEXT " + Math.max(1, pLimit) + " ROWS ONLY";
	}

	/** F2-103: the same keyset page with every column of {@link #COLUMNS} - the CSV export streams these, in key order. */
	public static String exportPage(final String pProjectCode, final RejectFilter pFilter, final RejectRef pAfter, final int pLimit)
	{
		return "SELECT t.\"entity\".\"id\" AS ENT, t.\"stage\" AS STG, t.\"sourceSystem\" AS SYS, t.\"sourceId\" AS SID, t.\"landingTable\" AS LDT, t.\"loadId\" AS LID, "
			+ "t.\"reasons\" AS RSN, t.\"rejectedAt\" AS RAT, t.\"resolved\" AS RES, t.\"acknowledged\" AS ACK, t.\"reviewedBy\" AS RBY, t.\"reviewedAt\" AS RVA, "
			+ "t.\"reviewComment\" AS RCM, t.\"category\" AS CAT, t.\"reasonCode\" AS RCD, t.\"detail\" AS DET" + RejectSql.FROM + " WHERE " + RejectSql.where(pProjectCode, pFilter)
			+ RejectSql.after(pAfter) + RejectSql.KEY_ORDER + " FETCH NEXT " + Math.max(1, pLimit) + " ROWS ONLY";
	}

	/** Open / acknowledged / resolved counts per (entity, stage) for the whole project, in ONE aggregate query. */
	public static String summary(final String pProjectCode)
	{
		return "SELECT t.\"entity\".\"id\" AS ENT, t.\"stage\" AS STG, "
			+ "SUM(CASE WHEN " + RejectSql.OPEN + " THEN 1 ELSE 0 END) AS OPN, "
			+ "SUM(CASE WHEN " + RejectSql.ACKNOWLEDGED + " THEN 1 ELSE 0 END) AS ACK, "
			+ "SUM(CASE WHEN " + RejectSql.IS_RESOLVED + " THEN 1 ELSE 0 END) AS RSV"
			+ RejectSql.FROM + " WHERE " + RejectSql.where(pProjectCode, null) + " GROUP BY t.\"entity\".\"id\", t.\"stage\" ORDER BY t.\"entity\".\"id\", t.\"stage\"";
	}

	/**
	 * Staging load rejects (2026-10-17): the unresolved rejects of one project and stage counted by entity and cause - what the wizard words "N rows set aside" from
	 * ({@code ebx.RejectCauseReader}). A row written before the {@code category} field existed counts as {@code OTHER}. Acknowledged rejects stay in (the row was not loaded:
	 * the same population as the "held back" number of the load grids).
	 */
	public static String causes(final String pProjectCode, final String pStage)
	{
		return "SELECT t.\"entity\".\"id\" AS ENT, COALESCE(t.\"category\", 'OTHER') AS CAT, COUNT(*) AS N" + RejectSql.FROM + " WHERE t.\"project\".\"code\" = "
			+ RejectSql.literal(pProjectCode) + " AND t.\"stage\" = " + RejectSql.literal(pStage) + " AND " + RejectSql.NOT_RESOLVED
			+ " GROUP BY t.\"entity\".\"id\", COALESCE(t.\"category\", 'OTHER') ORDER BY t.\"entity\".\"id\", COALESCE(t.\"category\", 'OTHER')";
	}

	/**
	 * Staging load rejects (2026-10-17): the rows a filter matches grouped by entity and KIND of reason ({@code reasonCode}), biggest group first, at most {@code pLimit}: the
	 * summary of "why". One row of each group is read along ({@code MIN}) for the wording and an example value. A group with no code (older rejects) has a null
	 * {@code RCD}. The same WHERE as the list, so the groups follow the filter. Staging last points (2026-10-17): the open / acknowledged / resolved counts of each group are read
	 * along (OPN, ACK, RSV) and the groups that still have unresolved rows come first.
	 */
	public static String groups(final String pProjectCode, final RejectFilter pFilter, final int pLimit)
	{
		return "SELECT t.\"entity\".\"id\" AS ENT, t.\"reasonCode\" AS RCD, COUNT(*) AS N, "
			+ "SUM(CASE WHEN " + RejectSql.OPEN + " THEN 1 ELSE 0 END) AS OPN, SUM(CASE WHEN " + RejectSql.ACKNOWLEDGED + " THEN 1 ELSE 0 END) AS ACK, "
			+ "SUM(CASE WHEN " + RejectSql.IS_RESOLVED + " THEN 1 ELSE 0 END) AS RSV, MIN(t.\"reasons\") AS RSN, MIN(t.\"detail\") AS DET, MIN(t.\"category\") AS CAT"
			// staging last points (2026-10-17): a group with rows still to look at comes before one whose rows were all resolved by a later load
			+ RejectSql.FROM + " WHERE " + RejectSql.where(pProjectCode, pFilter) + " GROUP BY t.\"entity\".\"id\", t.\"reasonCode\" ORDER BY SUM(CASE WHEN " + RejectSql.NOT_RESOLVED
			+ " THEN 1 ELSE 0 END) DESC, COUNT(*) DESC, t.\"entity\".\"id\", t.\"reasonCode\""
			+ " OFFSET 0 ROWS FETCH NEXT " + Math.max(1, pLimit) + " ROWS ONLY";
	}

	/** The number of distinct (entity, kind of reason) groups a filter matches - to say how many a capped summary left out. */
	public static String groupCount(final String pProjectCode, final RejectFilter pFilter)
	{
		return "SELECT COUNT(*) AS N FROM (SELECT t.\"entity\".\"id\" AS ENT, t.\"reasonCode\" AS RCD" + RejectSql.FROM + " WHERE " + RejectSql.where(pProjectCode, pFilter)
			+ " GROUP BY t.\"entity\".\"id\", t.\"reasonCode\") g";
	}
}
