package com.on.ebx.factory.matching;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lot MT (2026-10-04): the reason a Match and Merge process FAILED, read from {@code <ebx home>/ebxLog/ebx-addon-mame.log}. WHY the log:
 * the add-on gives no reason through its Java API ({@code MatchingOperationProcess.getDetail()} throws a {@code NullPointerException}
 * once the process has finished - see {@code ebx.MameMatchingApi}); measured live on 6.3.0-rc2 (project Vol1mF) the reason is only an
 * {@code ERROR} line of that log, e.g.
 * <pre>2026-10-04 15:59:48,218 CEST ERROR  log.wbp.ebx-addon-mame.com.orchestranetworks.addon.mame.auto_merge 6.3.0-rc2 [MatchingManager] The automatic merge process failed for the Marco-Luc group: Duplicate key: 418781. Index: ...,/root/CascadingRecords ...</pre>
 * <p>
 * This class is the pure part, no file and no EBX class: {@link #errorMessages} picks, among the lines of a log, the {@code ERROR}/{@code FATAL}
 * lines written since a given instant (their message, after the category and version columns; stack-trace lines carry no timestamp and
 * are never taken; identical messages are listed once, the first {@code ERROR} line first), {@link #reasonText} joins them under a
 * character cap, {@link #resolveLogDirectory} applies EBX's own {@code ebx.logs.directory} rule. The file read is {@code ebx.AddonLog}.
 */
public final class AddonLogWindow
{
	/** The add-on's own log, in the EBX logs directory. */
	public static final String LOG_FILE_NAME = "ebx-addon-mame.log";

	/** The most characters a quoted reason ever has (the step message column is 2000 characters; the job message names several entities). */
	public static final int REASON_MAX_CHARS = 600;

	/** The window starts this long BEFORE the run: the add-on's clock tick, the log's millisecond rounding. */
	static final long START_SLACK_MS = 2000L;

	private static final Pattern LINE = Pattern
		.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})\\s+(\\S+)\\s+(ERROR|FATAL|WARN|INFO|DEBUG|TRACE)\\s+(\\S+)\\s+(\\S+)\\s+(.*)$");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

	private AddonLogWindow()
	{
	}

	/**
	 * The message of every {@code ERROR}/{@code FATAL} line stamped at or after {@code pStartEpochMs} (minus {@link #START_SLACK_MS}), in log
	 * order, identical messages once. The stamp is read as local time in {@code pZone} (the log writes the JVM's own zone, with its
	 * abbreviation, e.g. {@code CEST}, which is ignored). A line that does not parse (a stack-trace line, an unknown layout) is skipped.
	 * {@code null} lines read as empty. Never throws.
	 */
	public static List<String> errorMessages(final List<String> pLines, final long pStartEpochMs, final ZoneId pZone)
	{
		final Set<String> distinct = new LinkedHashSet<String>();
		if (pLines == null)
		{
			return new ArrayList<String>(distinct);
		}
		final ZoneId zone = pZone == null ? ZoneId.systemDefault() : pZone;
		final long from = pStartEpochMs - AddonLogWindow.START_SLACK_MS;
		for (final String line : pLines)
		{
			if (line == null)
			{
				continue;
			}
			final Matcher matcher = AddonLogWindow.LINE.matcher(line);
			if (!matcher.matches())
			{
				continue;
			}
			final String level = matcher.group(3);
			if (!"ERROR".equals(level) && !"FATAL".equals(level))
			{
				continue;
			}
			final long stamp;
			try
			{
				stamp = LocalDateTime.parse(matcher.group(1), AddonLogWindow.STAMP).atZone(zone).toInstant().toEpochMilli();
			}
			catch (final DateTimeParseException ex)
			{
				continue;
			}
			if (stamp < from)
			{
				continue;
			}
			final String message = matcher.group(6).trim();
			if (!message.isEmpty())
			{
				distinct.add(message);
			}
		}
		return new ArrayList<String>(distinct);
	}

	/**
	 * The quoted reason: the messages joined by {@code " | "}, the first {@code ERROR} first, cut at {@code pMaxChars} with an ellipsis; when
	 * some messages do not fit, the end says how many more there are. {@code null} when there is no message at all (the caller then says
	 * "see ebx-addon-mame.log").
	 */
	public static String reasonText(final List<String> pMessages, final int pMaxChars)
	{
		if (pMessages == null || pMessages.isEmpty())
		{
			return null;
		}
		final int max = Math.max(40, pMaxChars);
		final StringBuilder out = new StringBuilder();
		int shown = 0;
		for (final String message : pMessages)
		{
			final String separator = out.length() == 0 ? "" : " | ";
			if (out.length() + separator.length() + message.length() > max)
			{
				if (shown == 0)
				{
					out.append(message, 0, max - 3).append("...");
					shown = 1;
				}
				break;
			}
			out.append(separator).append(message);
			shown++;
		}
		final int more = pMessages.size() - shown;
		if (more > 0)
		{
			out.append(" (+").append(more).append(" more ERROR line").append(more == 1 ? "" : "s").append(")");
		}
		return out.toString();
	}

	/**
	 * EBX's logs directory: {@code ebx.logs.directory} (a JVM option or the {@code ebx.properties} value) when set, with {@code ${ebx.home}}
	 * replaced by the EBX home; otherwise {@code <ebx home>/ebxLog}, the default of {@code ebx.properties}. {@code null} when neither the
	 * property nor the home is known.
	 */
	public static File resolveLogDirectory(final String pLogsDirectoryProperty, final String pEbxHome)
	{
		final String home = pEbxHome == null || pEbxHome.trim().isEmpty() ? null : pEbxHome.trim();
		if (pLogsDirectoryProperty != null && !pLogsDirectoryProperty.trim().isEmpty())
		{
			String directory = pLogsDirectoryProperty.trim();
			if (directory.contains("${ebx.home}"))
			{
				if (home == null)
				{
					return null;
				}
				directory = directory.replace("${ebx.home}", home);
			}
			return new File(directory);
		}
		return home == null ? null : new File(home, "ebxLog");
	}
}
