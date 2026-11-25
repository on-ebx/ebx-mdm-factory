package com.on.ebx.factory.matching;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JUnit-free: run the main, a failure throws (same convention as every other test in this codebase).
 * <p>
 * Lot MT (2026-10-04): {@link AddonLogWindow} and {@link MatchingRunFailure}, on a sample of the real {@code ebx-addon-mame.log} line format
 * (project Vol1mF, 6.3.0-rc2).
 */
public final class AddonLogWindowTest
{
	private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
	private static final String BAD = "2026-10-04 15:59:48,218 CEST ERROR  log.wbp.ebx-addon-mame.com.orchestranetworks.addon.mame.auto_merge 6.3.0-rc2 "
		+ "[MatchingManager] The automatic merge process failed for the Marco-Luc group: Duplicate key: 418781. Index: ...,/root/CascadingRecords ...";

	public static void main(final String[] pArgs)
	{
		AddonLogWindowTest.picksTheErrorLineOfTheWindowAndStripsTheColumns();
		AddonLogWindowTest.linesBeforeTheWindowAreIgnored();
		AddonLogWindowTest.onlyErrorLevelsAndOnlyParsedLinesCount();
		AddonLogWindowTest.identicalMessagesAreListedOnceFirstErrorFirst();
		AddonLogWindowTest.reasonIsCappedAndCountsWhatDoesNotFit();
		AddonLogWindowTest.noErrorMeansNoReason();
		AddonLogWindowTest.logDirectoryFollowsTheEbxLogsDirectoryRule();
		AddonLogWindowTest.failureWording();
		System.out.println("AddonLogWindowTest OK");
	}

	private static long epoch(final int pHour, final int pMinute, final int pSecond)
	{
		return LocalDateTime.of(2026, 10, 4, pHour, pMinute, pSecond).atZone(AddonLogWindowTest.ZONE).toInstant().toEpochMilli();
	}

	private static void picksTheErrorLineOfTheWindowAndStripsTheColumns()
	{
		final List<String> lines = Arrays.asList(
			"2026-10-04 15:40:00,001 CEST INFO  log.wbp.ebx-addon-mame.x 6.3.0-rc2 [MatchingManager] started",
			AddonLogWindowTest.BAD,
			"java.lang.IllegalStateException: boom",
			"\tat com.foo.Bar.baz(Bar.java:10)");
		final List<String> messages = AddonLogWindow.errorMessages(lines, AddonLogWindowTest.epoch(15, 40, 0), AddonLogWindowTest.ZONE);
		AddonLogWindowTest.check("one message: " + messages, messages.size() == 1);
		AddonLogWindowTest.check("it starts after the category and version columns: " + messages.get(0), messages.get(0).startsWith("[MatchingManager] The automatic merge process failed for the Marco-Luc group: Duplicate key: 418781."));
		AddonLogWindowTest.check("the stack-trace lines are not taken", !messages.get(0).contains("IllegalStateException"));
	}

	private static void linesBeforeTheWindowAreIgnored()
	{
		// the run started at 16:00:00: the 15:59:48 error belongs to an earlier run (slack is 2 s)
		AddonLogWindowTest.check("an earlier error is not this run's reason",
			AddonLogWindow.errorMessages(Collections.singletonList(AddonLogWindowTest.BAD), AddonLogWindowTest.epoch(16, 0, 0), AddonLogWindowTest.ZONE).isEmpty());
		AddonLogWindowTest.check("an error written 1 s before the start is still taken (clock rounding slack)",
			AddonLogWindow.errorMessages(Collections.singletonList(AddonLogWindowTest.BAD), AddonLogWindowTest.epoch(15, 59, 49), AddonLogWindowTest.ZONE).size() == 1);
		AddonLogWindowTest.check("an error exactly at the start is taken",
			AddonLogWindow.errorMessages(Collections.singletonList(AddonLogWindowTest.BAD), AddonLogWindowTest.epoch(15, 59, 48), AddonLogWindowTest.ZONE).size() == 1);
	}

	private static void onlyErrorLevelsAndOnlyParsedLinesCount()
	{
		final List<String> lines = Arrays.asList(null, "", "garbage", "2026-10-04 15:59:50,000 CEST WARN  cat 6.3.0-rc2 [X] a warning",
			"2026-10-04 15:59:51,000 CEST FATAL cat 6.3.0-rc2 [X] a fatal one", "2026-13-45 15:59:50,000 CEST ERROR cat 6.3.0-rc2 [X] impossible date");
		final List<String> messages = AddonLogWindow.errorMessages(lines, AddonLogWindowTest.epoch(15, 0, 0), AddonLogWindowTest.ZONE);
		AddonLogWindowTest.check("only the FATAL line: " + messages, messages.size() == 1 && "[X] a fatal one".equals(messages.get(0)));
		AddonLogWindowTest.check("null list reads as empty", AddonLogWindow.errorMessages(null, 0L, null).isEmpty());
	}

	private static void identicalMessagesAreListedOnceFirstErrorFirst()
	{
		final String second = "2026-10-04 15:59:49,000 CEST ERROR  cat 6.3.0-rc2 [MatchingManager] second problem";
		final List<String> messages = AddonLogWindow.errorMessages(Arrays.asList(AddonLogWindowTest.BAD, second, AddonLogWindowTest.BAD), AddonLogWindowTest.epoch(15, 0, 0),
			AddonLogWindowTest.ZONE);
		AddonLogWindowTest.check("two distinct messages, log order: " + messages, messages.size() == 2 && messages.get(0).contains("Marco-Luc") && messages.get(1).contains("second problem"));
	}

	private static void reasonIsCappedAndCountsWhatDoesNotFit()
	{
		final List<String> many = new ArrayList<String>();
		for (int i = 0; i < 20; i++)
		{
			many.add("error number " + i + " " + "x".repeat(60));
		}
		final String text = AddonLogWindow.reasonText(many, 600);
		AddonLogWindowTest.check("starts with the first error", text.startsWith("error number 0 "));
		AddonLogWindowTest.check("about 600 characters, not the whole log: " + text.length(), text.length() < 700);
		AddonLogWindowTest.check("says how many more there are: " + text, text.contains("more ERROR lines)"));
		final String single = AddonLogWindow.reasonText(Collections.singletonList("y".repeat(2000)), 600);
		AddonLogWindowTest.check("a single huge message is cut with an ellipsis", single.length() == 600 + 0 && single.endsWith("..."));
		AddonLogWindowTest.check("a short list has no suffix", "a | b".equals(AddonLogWindow.reasonText(Arrays.asList("a", "b"), 600)));
	}

	private static void noErrorMeansNoReason()
	{
		AddonLogWindowTest.check("null for an empty list", AddonLogWindow.reasonText(Collections.<String>emptyList(), 600) == null);
		AddonLogWindowTest.check("null for null", AddonLogWindow.reasonText(null, 600) == null);
	}

	private static void logDirectoryFollowsTheEbxLogsDirectoryRule()
	{
		AddonLogWindowTest.check("ebx.properties default: ${ebx.home}/ebxLog",
			new File("/home/ebx/ebxLog").equals(AddonLogWindow.resolveLogDirectory("${ebx.home}/ebxLog", "/home/ebx")));
		AddonLogWindowTest.check("a plain path is taken as is", new File("/var/log/ebx").equals(AddonLogWindow.resolveLogDirectory("/var/log/ebx", "/home/ebx")));
		AddonLogWindowTest.check("nothing set: <home>/ebxLog", new File("/home/ebx/ebxLog").equals(AddonLogWindow.resolveLogDirectory(null, "/home/ebx")));
		AddonLogWindowTest.check("blank property: <home>/ebxLog", new File("/home/ebx/ebxLog").equals(AddonLogWindow.resolveLogDirectory("  ", "/home/ebx")));
		AddonLogWindowTest.check("${ebx.home} with no home: unknown", AddonLogWindow.resolveLogDirectory("${ebx.home}/ebxLog", null) == null);
		AddonLogWindowTest.check("nothing known at all: unknown", AddonLogWindow.resolveLogDirectory(null, null) == null);
	}

	private static void failureWording()
	{
		AddonLogWindowTest.check("FAILED is a failure", MatchingRunFailure.isFailure("FAILED", false) && MatchingRunFailure.isFailure("FAILED", true));
		AddonLogWindowTest.check("CANCELLED nobody asked for is a failure", MatchingRunFailure.isFailure("CANCELLED", false));
		AddonLogWindowTest.check("CANCELLED by the user's stop is not", !MatchingRunFailure.isFailure("CANCELLED", true));
		AddonLogWindowTest.check("DONE, RUNNING and CANCELLING are not", !MatchingRunFailure.isFailure("DONE", false) && !MatchingRunFailure.isFailure("RUNNING", false)
			&& !MatchingRunFailure.isFailure("CANCELLING", false));

		final List<String> messages = AddonLogWindow.errorMessages(Collections.singletonList(AddonLogWindowTest.BAD), AddonLogWindowTest.epoch(15, 0, 0), AddonLogWindowTest.ZONE);
		final String reason = AddonLogWindow.reasonText(messages, AddonLogWindow.REASON_MAX_CHARS);
		final String step = MatchingRunFailure.stepMessage("Personne", "FAILED", 50.0, reason, "/home/ebx/ebxLog/ebx-addon-mame.log", "Scope: 576692 of 576692 records.");
		AddonLogWindowTest.check("says FAILED at 50.0 %: " + step, step.startsWith("Matching run on Personne FAILED: the add-on's process failed at 50.0 %"));
		AddonLogWindowTest.check("quotes the reason: " + step, step.contains("reason from ebx-addon-mame.log: [MatchingManager] The automatic merge process failed for the Marco-Luc group"));
		AddonLogWindowTest.check("never the timeout wording", !step.contains("still FAILED") && !step.contains("ms"));
		AddonLogWindowTest.check("keeps the golden key and scope sentences", step.contains("Xref.goldenKey NOT refreshed.") && step.endsWith("Scope: 576692 of 576692 records."));

		final String noReason = MatchingRunFailure.stepMessage("Personne", "FAILED", -1, null, "/home/ebx/ebxLog/ebx-addon-mame.log", "");
		AddonLogWindowTest.check("no reason: points at the log file: " + noReason, noReason.contains("completion rate unknown") && noReason.contains("see /home/ebx/ebxLog/ebx-addon-mame.log for the reason"));

		final String entry = MatchingRunFailure.entry("Personne", reason, "FAILED", 50.0);
		AddonLogWindowTest.check("entry: Entity - reason", entry.startsWith("Personne - [MatchingManager] The automatic merge"));
		AddonLogWindowTest.check("entry without a reason still says what happened", MatchingRunFailure.entry("Adresse", null, "FAILED", 12.5).equals("Adresse - failed at 12.5 %, see ebx-addon-mame.log"));
		final String job = MatchingRunFailure.jobMessage(Collections.singletonList(entry), 1, 0, 0);
		AddonLogWindowTest.check("job sentence counts and names: " + job, job.startsWith("1 of 2 entities failed: Personne - [MatchingManager]"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
