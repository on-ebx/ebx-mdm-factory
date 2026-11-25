package com.on.ebx.factory.rejects;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** JUnit-free: run the main, a failure throws. Audit F-38 (wave 10): the filter, the SQL text, the checkbox identity and the CSV of the transfer-reject review. */
public final class RejectReviewLogicTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		RejectReviewLogicTest.filter();
		RejectReviewLogicTest.sql();
		RejectReviewLogicTest.ref();
		RejectReviewLogicTest.csv();
		RejectReviewLogicTest.kindsOfReason();
		System.out.println("RejectReviewLogicTest OK");
	}

	private static void filter() throws Exception
	{
		RejectReviewLogicTest.check("a row with null flags is open", RejectFilter.OPEN.equals(RejectFilter.statusOf(null, null)));
		RejectReviewLogicTest.check("acknowledged", RejectFilter.ACKNOWLEDGED.equals(RejectFilter.statusOf(Boolean.FALSE, Boolean.TRUE)));
		RejectReviewLogicTest.check("resolved wins over acknowledged", RejectFilter.RESOLVED.equals(RejectFilter.statusOf(Boolean.TRUE, Boolean.TRUE)));
		final RejectFilter f = RejectFilter.parse(" 12 ", "MASTER", "OPEN", "  bad value ", "2026-10-01", "2026-10-07");
		RejectReviewLogicTest.check("fields read", "12".equals(f.entityId) && "MASTER".equals(f.stage) && "OPEN".equals(f.status) && "bad value".equals(f.reasonContains));
		RejectReviewLogicTest.check("dates read", "2026-10-01".equals(RejectFilter.dayText(f.from)) && "2026-10-07".equals(RejectFilter.dayText(f.to)));
		RejectReviewLogicTest.check("the last day is inclusive: the bound is the next day", "2026-10-08".equals(RejectFilter.dayText(f.toExclusive())));
		final RejectFilter bad = RejectFilter.parse(null, "OTHER", "WEIRD", null, "31/12/2026", "2026-02-31");
		RejectReviewLogicTest.check("out-of-vocabulary values mean any", bad.isEmpty() && bad.stage.isEmpty() && bad.status.isEmpty() && bad.from == null && bad.to == null);
		RejectReviewLogicTest.check("an empty filter is empty", new RejectFilter().isEmpty());
	}

	private static void sql()
	{
		final RejectFilter none = new RejectFilter();
		final String all = RejectSql.where("P1", none);
		RejectReviewLogicTest.check("project only: " + all, "t.\"project\".\"code\" = 'P1'".equals(all));
		RejectReviewLogicTest.check("a quote in the project code is doubled", RejectSql.where("it's", none).contains("'it''s'"));
		final RejectFilter f = new RejectFilter();
		f.entityId = "7";
		f.stage = "STAGING";
		f.status = RejectFilter.OPEN;
		f.reasonContains = "100%_x'\\";
		f.from = RejectFilter.parse("", "", "", "", "2026-10-01", "").from;
		f.to = RejectFilter.parse("", "", "", "", "", "2026-10-07").to;
		final String w = RejectSql.where("P1", f);
		RejectReviewLogicTest.check("entity: " + w, w.contains("t.\"entity\".\"id\" = '7'"));
		RejectReviewLogicTest.check("stage", w.contains("t.\"stage\" = 'STAGING'"));
		RejectReviewLogicTest.check("open means not resolved and not acknowledged, null-safe",
			w.contains("(COALESCE(t.\"resolved\", false) = false AND COALESCE(t.\"acknowledged\", false) = false)"));
		RejectReviewLogicTest.check("LIKE escapes % _ and the quote, with ESCAPE: " + w, w.contains("LIKE '%100\\%\\_x''\\\\%' ESCAPE '\\'"));
		RejectReviewLogicTest.check("from is inclusive", w.contains("t.\"rejectedAt\" >= TIMESTAMP '2026-10-01 00:00:00'"));
		RejectReviewLogicTest.check("to is the start of the next day, exclusive", w.contains("t.\"rejectedAt\" < TIMESTAMP '2026-10-08 00:00:00'"));
		final RejectFilter ack = new RejectFilter();
		ack.status = RejectFilter.ACKNOWLEDGED;
		RejectReviewLogicTest.check("acknowledged status", RejectSql.where("P", ack).contains("COALESCE(t.\"acknowledged\", false) = true"));
		final RejectFilter res = new RejectFilter();
		res.status = RejectFilter.RESOLVED;
		RejectReviewLogicTest.check("resolved status", RejectSql.where("P", res).endsWith("COALESCE(t.\"resolved\", false) = true"));
		final String page = RejectSql.page("P1", none, 100, 50);
		RejectReviewLogicTest.check("a page is ordered, stable and bounded by the database: " + page,
			page.contains("ORDER BY t.\"rejectedAt\" DESC, t.\"entity\".\"id\", t.\"stage\", t.\"sourceSystem\", t.\"sourceId\" OFFSET 100 ROWS FETCH NEXT 50 ROWS ONLY"));
		for (final String alias : RejectSql.COLUMNS)
		{
			RejectReviewLogicTest.check("page selects " + alias, page.contains(" AS " + alias));
		}
		RejectReviewLogicTest.check("negative offset and zero limit are clamped", RejectSql.page("P", none, -5, 0).contains("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
		RejectReviewLogicTest.check("count is an aggregate", RejectSql.count("P", none).startsWith("SELECT COUNT(*) AS N FROM \"TransferReject\" t WHERE "));
		final String summary = RejectSql.summary("P");
		RejectReviewLogicTest.check("the summary is ONE aggregate grouped by entity and stage: " + summary,
			summary.contains("GROUP BY t.\"entity\".\"id\", t.\"stage\"") && summary.contains("AS OPN") && summary.contains("AS ACK") && summary.contains("AS RSV"));
		RejectReviewLogicTest.check("keys are the four key parts", RejectSql.keys("P", none, null, 10).startsWith("SELECT t.\"entity\".\"id\" AS ENT, t.\"stage\" AS STG, t.\"sourceSystem\" AS SYS, t.\"sourceId\" AS SID"));
	}

	private static void ref()
	{
		final RejectRef ref = new RejectRef("12", "STAGING", "ERP", "a|b%cé d");
		final RejectRef back = RejectRef.decode(ref.encode());
		RejectReviewLogicTest.check("round trip with a pipe, a percent, an accent and a space", back != null && "12".equals(back.entityId) && "STAGING".equals(back.stage)
			&& "ERP".equals(back.sourceSystem) && "a|b%cé d".equals(back.sourceId));
		RejectReviewLogicTest.check("garbage is no reference", RejectRef.decode("x|y") == null && RejectRef.decode(null) == null);
		RejectReviewLogicTest.check("a source id of a pipe encodes to one part", ref.encode().split("\\|", -1).length == 4);
	}

	private static void csv() throws Exception
	{
		final RejectRow row = new RejectRow();
		row.entityName = "Supplier";
		row.stage = "STAGING";
		row.sourceSystem = "ERP";
		row.sourceId = "=cmd|' /C calc'!A0";
		row.reasons = "bad \"value\", too long\nsecond line";
		row.rejectedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-10-07 09:05:03");
		row.acknowledged = true;
		row.reviewedBy = "admin";
		row.reviewComment = "-1 known";
		final List<RejectRow> rows = new ArrayList<RejectRow>();
		rows.add(row);
		final String text = RejectCsv.of(rows);
		RejectReviewLogicTest.check("header", text.startsWith("entity,stage,sourceSystem,sourceId,landingTable,loadId,reasons,rejectedAt,status,reviewedBy,reviewedAt,reviewComment\r\n"));
		RejectReviewLogicTest.check("a formula is neutralised: " + text, text.contains(",'=cmd|' /C calc'!A0,"));
		RejectReviewLogicTest.check("quotes and newlines are RFC 4180 quoted", text.contains("\"bad \"\"value\"\", too long\nsecond line\""));
		RejectReviewLogicTest.check("date and status", text.contains("2026-10-07T09:05:03,ACKNOWLEDGED,admin,"));
		RejectReviewLogicTest.check("a leading minus is neutralised too", text.contains(",'-1 known\r\n"));
		RejectReviewLogicTest.check("one line per row", text.split("\r\n", -1).length == 3 || text.endsWith("\r\n"));
		RejectReviewLogicTest.check("file name keeps only safe characters", RejectCsv.fileName("my code/../x", new Date(0L)).matches("transfer-rejects-my_code_.._x-\\d{8}\\.csv"));
	}

	/** Staging load rejects (2026-10-17): the filter by one kind of reason, the groups query, and the CSV in plain English. */
	private static void kindsOfReason()
	{
		final RejectFilter coded = RejectFilter.parse("7", "", "", "", "", "", "convert.date|birthDate|date_naissance");
		RejectReviewLogicTest.check("the kind of reason is read", "convert.date|birthDate|date_naissance".equals(coded.reasonCode) && !coded.isEmpty());
		RejectReviewLogicTest.check("it filters on the reasonCode column, quoted", RejectSql.where("P", coded).contains("t.\"reasonCode\" = 'convert.date|birthDate|date_naissance'"));
		final RejectFilter quote = RejectFilter.parse("", "", "", "", "", "", "it's");
		RejectReviewLogicTest.check("a quote in a code is doubled", RejectSql.where("P", quote).contains("t.\"reasonCode\" = 'it''s'"));
		final RejectFilter uncoded = RejectFilter.parse("", "", "", "", "", "", RejectFilter.UNCODED);
		RejectReviewLogicTest.check("the uncoded marker means no code at all", RejectSql.where("P", uncoded).contains("t.\"reasonCode\" IS NULL") && !RejectSql.where("P", uncoded).contains("= '-'"));
		RejectReviewLogicTest.check("six-argument parse is unchanged", RejectFilter.parse("", "", "", "", "", "").reasonCode.isEmpty());
		RejectReviewLogicTest.check("a code too long to be one is ignored", RejectFilter.parse("", "", "", "", "", "", new String(new char[300]).replace('\0', 'x')).reasonCode.isEmpty());
		final String groups = RejectSql.groups("P1", coded, 10);
		RejectReviewLogicTest.check("groups: by entity and kind, biggest first, one row of each read along: " + groups, groups.startsWith("SELECT t.\"entity\".\"id\" AS ENT, t.\"reasonCode\" AS RCD, COUNT(*) AS N, ") && groups.contains("MIN(t.\"reasons\") AS RSN")
			&& groups.contains("GROUP BY t.\"entity\".\"id\", t.\"reasonCode\" ORDER BY SUM(CASE WHEN COALESCE(t.\"resolved\", false) = false THEN 1 ELSE 0 END) DESC, COUNT(*) DESC")
			&& groups.endsWith("OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY"));
		// staging last points (2026-10-17): open / acknowledged / resolved per group, groups with rows left to look at first
		RejectReviewLogicTest.check("groups carry their open, acknowledged and resolved counts: " + groups, groups.contains("AS OPN") && groups.contains("AS ACK") && groups.contains("AS RSV")
			&& groups.contains("COALESCE(t.\"resolved\", false) = true THEN 1 ELSE 0 END) AS RSV"));
		RejectReviewLogicTest.check("the groups follow the filter", groups.contains("t.\"entity\".\"id\" = '7'") && groups.contains("t.\"reasonCode\" = 'convert.date|birthDate|date_naissance'"));
		RejectReviewLogicTest.check("the groups are capped but never below one", RejectSql.groups("P", null, 0).endsWith("FETCH NEXT 1 ROWS ONLY"));
		RejectReviewLogicTest.check("the number of groups is one count over the same grouping", RejectSql.groupCount("P1", null).startsWith("SELECT COUNT(*) AS N FROM (SELECT ")
			&& RejectSql.groupCount("P1", null).endsWith("GROUP BY t.\"entity\".\"id\", t.\"reasonCode\") g"));
		final String page = RejectSql.page("P1", null, 0, 50);
		RejectReviewLogicTest.check("the page reads the cause, the kind and the raw detail", page.contains("t.\"category\" AS CAT") && page.contains("t.\"reasonCode\" AS RCD") && page.contains("t.\"detail\" AS DET"));
		final RejectRow row = new RejectRow();
		row.entityName = "Customer";
		row.stage = "STAGING";
		row.sourceSystem = "LOYALTY";
		row.sourceId = "FID1";
		row.reasons = "birthDate (source column date_naissance): '10/06/1963' is not a valid date (expected yyyy-MM-dd). [mdmf:rejects.reason.convert.date|s:birthDate|s:date_naissance|s:10%2F06%2F1963]";
		final String line = RejectCsv.line(row);
		RejectReviewLogicTest.check("the CSV reads plain English without the marker: " + line, line.contains("is not a valid date (expected yyyy-MM-dd).,") && !line.contains("mdmf:"));
		row.reasons = "EBX refused the save: Unexpected &gt; Caused by";
		RejectReviewLogicTest.check("and no double escaping from an older row", RejectCsv.line(row).contains("Unexpected > Caused by"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
