package com.on.ebx.factory.rejects;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The CSV export of the filtered rejects (audit F-38): comma-separated, RFC 4180 quoting, one header line, dates as {@code
 * yyyy-MM-dd'T'HH:mm:ss}. A cell that starts with {@code = + - @}, a tab or a carriage return is prefixed with an apostrophe so a
 * spreadsheet never runs it as a formula (reasons and source ids are text written by the engine from source data). Pure.
 */
public final class RejectCsv
{
	private RejectCsv()
	{
	}

	/** The header, in the order {@link #line} writes. */
	public static String header()
	{
		return "entity,stage,sourceSystem,sourceId,landingTable,loadId,reasons,rejectedAt,status,reviewedBy,reviewedAt,reviewComment\r\n";
	}

	public static String line(final RejectRow pRow)
	{
		final StringBuilder sb = new StringBuilder(160);
		RejectCsv.cell(sb, pRow.entityName.isEmpty() ? pRow.entityId : pRow.entityName).append(',');
		RejectCsv.cell(sb, pRow.stage).append(',');
		RejectCsv.cell(sb, pRow.sourceSystem).append(',');
		RejectCsv.cell(sb, pRow.sourceId).append(',');
		RejectCsv.cell(sb, pRow.landingTable).append(',');
		RejectCsv.cell(sb, pRow.loadId).append(',');
		// staging load rejects (2026-10-17): the English sentences, no marker, no double escaping - the same text REST and logs read
		RejectCsv.cell(sb, RejectText.unescapeEntities(RejectReasons.english(pRow.reasons))).append(',');
		RejectCsv.cell(sb, RejectCsv.date(pRow.rejectedAt)).append(',');
		RejectCsv.cell(sb, pRow.status()).append(',');
		RejectCsv.cell(sb, pRow.reviewedBy).append(',');
		RejectCsv.cell(sb, RejectCsv.date(pRow.reviewedAt)).append(',');
		RejectCsv.cell(sb, pRow.reviewComment).append("\r\n");
		return sb.toString();
	}

	/** The whole file for a list of rows (tests; the engine streams {@link #header} and {@link #line}). */
	public static String of(final List<RejectRow> pRows)
	{
		final StringBuilder sb = new StringBuilder(RejectCsv.header());
		for (final RejectRow row : pRows)
		{
			sb.append(RejectCsv.line(row));
		}
		return sb.toString();
	}

	/** The download name: {@code transfer-rejects-<project code, only letters digits . _ ->-<yyyyMMdd>.csv}. */
	public static String fileName(final String pProjectCode, final Date pNow)
	{
		final String code = (pProjectCode == null ? "" : pProjectCode).replaceAll("[^A-Za-z0-9._-]", "_");
		return "transfer-rejects-" + (code.isEmpty() ? "project" : code) + "-" + new SimpleDateFormat("yyyyMMdd").format(pNow) + ".csv";
	}

	static String date(final Date pDate)
	{
		return pDate == null ? "" : new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(pDate);
	}

	static StringBuilder cell(final StringBuilder pOut, final String pValue)
	{
		String value = pValue == null ? "" : pValue;
		if (!value.isEmpty())
		{
			final char first = value.charAt(0);
			if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r')
			{
				value = "'" + value;
			}
		}
		if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
		{
			return pOut.append('"').append(value.replace("\"", "\"\"")).append('"');
		}
		return pOut.append(value);
	}
}
