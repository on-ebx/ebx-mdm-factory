package com.on.ebx.factory.rejects;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * What the transfer-reject review screen filters on (audit F-38, wave 10): entity, stage, status, a word of the reasons and a
 * date range on {@code rejectedAt}. Pure (no EBX type): {@link RejectSql} turns it into the WHERE clause of an EBX SQL query and
 * the screen fills it from its form fields with {@link #parse}; an empty or unknown value means "any".
 * <p>
 * <b>Status.</b> {@link #OPEN}: not resolved and not acknowledged (a person has still to look at it). {@link #ACKNOWLEDGED}: not
 * resolved, a steward set it aside with a comment. {@link #RESOLVED}: a later load wrote the row ({@code resolved = true}); it
 * wins over an acknowledgement.
 */
public final class RejectFilter
{
	public static final String OPEN = "OPEN";
	public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
	public static final String RESOLVED = "RESOLVED";
	/** The {@link #reasonCode} of the rejects that carry no code. */
	public static final String UNCODED = "-";

	/** Formatted primary key of the {@code StagingEntity}; empty = every entity. */
	public String entityId = "";
	/** {@code STAGING} or {@code MASTER}; empty = both. */
	public String stage = "";
	/** {@link #OPEN}, {@link #ACKNOWLEDGED} or {@link #RESOLVED}; empty = any. */
	public String status = "";
	/** A text searched (case-insensitive, literally) in the reasons; empty = none. */
	public String reasonContains = "";
	/**
	 * Staging load rejects (2026-10-17): one KIND of reason - the {@code reasonCode} signature a summary group stands for ({@link RejectReason#signature}); {@link #UNCODED} for the rejects
	 * written before reasons were coded; empty = any.
	 */
	public String reasonCode = "";
	/** First day, inclusive; null = no lower bound. */
	public Date from;
	/** Last day, INCLUSIVE (the SQL bound is the start of the next day); null = no upper bound. */
	public Date to;

	/** The status of one row, from its two flags (null counts as false: a row written before the review fields existed). */
	public static String statusOf(final Boolean pResolved, final Boolean pAcknowledged)
	{
		if (Boolean.TRUE.equals(pResolved))
		{
			return RejectFilter.RESOLVED;
		}
		return Boolean.TRUE.equals(pAcknowledged) ? RejectFilter.ACKNOWLEDGED : RejectFilter.OPEN;
	}

	/** Reads the five form values; anything out of vocabulary or unreadable is ignored (= any), never an error. Dates are {@code yyyy-MM-dd}. */
	public static RejectFilter parse(final String pEntityId, final String pStage, final String pStatus, final String pReason, final String pFrom, final String pTo)
	{
		return RejectFilter.parse(pEntityId, pStage, pStatus, pReason, pFrom, pTo, "");
	}

	/** {@link #parse(String, String, String, String, String, String)} with the kind of reason ({@link #reasonCode}). */
	public static RejectFilter parse(final String pEntityId, final String pStage, final String pStatus, final String pReason, final String pFrom, final String pTo,
		final String pReasonCode)
	{
		final RejectFilter out = new RejectFilter();
		out.reasonCode = RejectFilter.trim(pReasonCode);
		if (out.reasonCode.length() > 255)
		{
			out.reasonCode = "";
		}
		out.entityId = RejectFilter.trim(pEntityId);
		final String stage = RejectFilter.trim(pStage);
		out.stage = "STAGING".equals(stage) || "MASTER".equals(stage) ? stage : "";
		final String status = RejectFilter.trim(pStatus);
		out.status = RejectFilter.OPEN.equals(status) || RejectFilter.ACKNOWLEDGED.equals(status) || RejectFilter.RESOLVED.equals(status) ? status : "";
		out.reasonContains = RejectFilter.trim(pReason);
		out.from = RejectFilter.day(pFrom);
		out.to = RejectFilter.day(pTo);
		return out;
	}

	/** True when no criterion is set. */
	public boolean isEmpty()
	{
		return this.entityId.isEmpty() && this.stage.isEmpty() && this.status.isEmpty() && this.reasonContains.isEmpty() && this.reasonCode.isEmpty() && this.from == null
			&& this.to == null;
	}

	/** {@code yyyy-MM-dd} of a bound, "" for null - what the form shows again. */
	public static String dayText(final Date pDate)
	{
		return pDate == null ? "" : new SimpleDateFormat("yyyy-MM-dd").format(pDate);
	}

	/** The instant {@link #to} stands for in a SQL range: the start of the day after it. */
	public Date toExclusive()
	{
		if (this.to == null)
		{
			return null;
		}
		final Calendar calendar = Calendar.getInstance();
		calendar.setTime(this.to);
		calendar.add(Calendar.DAY_OF_MONTH, 1);
		return calendar.getTime();
	}

	private static Date day(final String pText)
	{
		final String text = RejectFilter.trim(pText);
		if (text.isEmpty())
		{
			return null;
		}
		final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
		format.setLenient(false);
		try
		{
			return format.parse(text);
		}
		catch (final ParseException ex)
		{
			return null;
		}
	}

	private static String trim(final String pValue)
	{
		return pValue == null ? "" : pValue.trim();
	}
}
