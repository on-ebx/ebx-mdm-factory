package com.on.ebx.factory.intake;

/** JUnit-free (run the main; a failure throws): {@link IntakeSettings} defaults, clamping on read, refusal on typing, and {@link RejectThreshold}. */
public final class IntakeSettingsTest
{
	public static void main(final String[] pArgs)
	{
		IntakeSettingsTest.defaultsForEmpty();
		IntakeSettingsTest.clampOnRead();
		IntakeSettingsTest.validateOnTyping();
		IntakeSettingsTest.percent();
		IntakeSettingsTest.rejectThresholdBoundary();
		System.out.println("IntakeSettingsTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static void defaultsForEmpty()
	{
		final IntakeSettings s = IntakeSettings.of(null, null, null, null);
		check(s.quietSeconds == 60 && s.maxWaitMinutes == 10 && s.minRows == 1 && !s.batchSignal, "empty cells are the defaults");
		final IntakeSettings d = IntakeSettings.defaults();
		check(d.quietSeconds == 60 && d.maxWaitMinutes == 10 && d.minRows == 1, "defaults()");
		final IntakeSettings custom = IntakeSettings.of(Integer.valueOf(30), Integer.valueOf(20), Integer.valueOf(5), Boolean.TRUE);
		check(custom.quietSeconds == 30 && custom.maxWaitMinutes == 20 && custom.minRows == 5 && custom.batchSignal, "stored values are kept");
	}

	private static void clampOnRead()
	{
		check(IntakeSettings.of(Integer.valueOf(1), null, null, null).quietSeconds == 5, "quiet below 5 is clamped up");
		check(IntakeSettings.of(Integer.valueOf(99999), null, null, null).quietSeconds == 3600, "quiet above 3600 is clamped down");
		check(IntakeSettings.of(null, Integer.valueOf(0), null, null).maxWaitMinutes == 1, "max wait below 1 is clamped up");
		check(IntakeSettings.of(null, Integer.valueOf(99999), null, null).maxWaitMinutes == 1440, "max wait above 1440 is clamped down");
		check(IntakeSettings.of(null, null, Integer.valueOf(-4), null).minRows == 1, "min rows below 1 is 1");
		// the longest wait is never below the quiet period (rounded up to minutes), else the quiet period could never elapse
		final IntakeSettings s = IntakeSettings.of(Integer.valueOf(3600), Integer.valueOf(10), null, null);
		check(s.maxWaitMinutes == 60, "max wait raised to the quiet period: " + s.maxWaitMinutes);
		check(IntakeSettings.of(Integer.valueOf(61), Integer.valueOf(1), null, null).maxWaitMinutes == 2, "61 s needs 2 minutes");
	}

	private static void validateOnTyping()
	{
		check(IntakeSettings.validate(null, null, null) == null, "all empty is fine");
		check(IntakeSettings.validate(Integer.valueOf(5), Integer.valueOf(1), Integer.valueOf(1)) == null, "lower bounds are fine");
		check(IntakeSettings.validate(Integer.valueOf(3600), Integer.valueOf(1440), Integer.valueOf(1000)) == null, "upper bounds are fine");
		check(IntakeSettings.validate(Integer.valueOf(4), null, null) != null, "quiet 4 refused");
		check(IntakeSettings.validate(Integer.valueOf(3601), null, null) != null, "quiet 3601 refused");
		check(IntakeSettings.validate(null, Integer.valueOf(0), null) != null, "max wait 0 refused");
		check(IntakeSettings.validate(null, Integer.valueOf(1441), null) != null, "max wait 1441 refused");
		check(IntakeSettings.validate(null, null, Integer.valueOf(0)) != null, "min rows 0 refused");
		check(IntakeSettings.validate(Integer.valueOf(300), Integer.valueOf(4), null) != null, "max wait (4 min) shorter than quiet (300 s) refused");
		check(IntakeSettings.validate(Integer.valueOf(300), Integer.valueOf(5), null) == null, "max wait equal to the quiet period is fine");
		check(IntakeSettings.validate(Integer.valueOf(900), null, null) != null, "a quiet period over the DEFAULT max wait is refused too");
	}

	private static void percent()
	{
		check(IntakeSettings.validatePercent(null) == null, "empty = never stop");
		check(IntakeSettings.validatePercent(Integer.valueOf(1)) == null && IntakeSettings.validatePercent(Integer.valueOf(100)) == null, "1 and 100 fine");
		check(IntakeSettings.validatePercent(Integer.valueOf(0)) != null && IntakeSettings.validatePercent(Integer.valueOf(101)) != null, "0 and 101 refused");
	}

	private static void rejectThresholdBoundary()
	{
		check(RejectThreshold.reached(100, 5, Integer.valueOf(5)), "5 of 100 at threshold 5: exactly at the threshold stops");
		check(!RejectThreshold.reached(100, 4, Integer.valueOf(5)), "4 of 100 at threshold 5: below, does not stop");
		check(RejectThreshold.reached(100, 6, Integer.valueOf(5)), "one above stops");
		check(RejectThreshold.reached(100, 100, Integer.valueOf(100)), "100 of 100 at threshold 100: a total reject stops");
		check(!RejectThreshold.reached(100, 99, Integer.valueOf(100)), "99 of 100 at threshold 100: does not stop");
		check(!RejectThreshold.reached(100, 0, Integer.valueOf(1)), "0 of 100 at threshold 1: nothing held back never stops");
		check(RejectThreshold.reached(100, 1, Integer.valueOf(1)), "1 of 100 at threshold 1 stops");
		check(!RejectThreshold.reached(0, 0, Integer.valueOf(1)), "read 0 at threshold 1 never stops");
		check(!RejectThreshold.reached(100, 100, null), "no threshold never stops");
		check(!RejectThreshold.reached(0, 0, Integer.valueOf(5)), "read 0 never stops");
		check(!RejectThreshold.reached(0, 7, Integer.valueOf(5)), "read 0 never stops, whatever rejected says");
		check(!RejectThreshold.reached(100, 0, Integer.valueOf(1)), "nothing rejected never stops");
		check(RejectThreshold.reached(1204, 72, Integer.valueOf(5)), "72 of 1204 is 5.98 percent: above 5");
		check(!RejectThreshold.reached(1204, 60, Integer.valueOf(5)), "60 of 1204 is exactly 4.98: below");
		check(RejectThreshold.reached(1000, 50, Integer.valueOf(5)), "50 of 1000 is exactly 5: reached");
		check(!RejectThreshold.reached(1000, 49, Integer.valueOf(5)), "49 of 1000 is 4.9: below");
		check(RejectThreshold.reached(1000, 51, Integer.valueOf(5)), "51 of 1000 is above");
		check(RejectThreshold.reached(3, 1, Integer.valueOf(33)), "1 of 3 reaches 33 percent");
		check(!RejectThreshold.reached(3, 1, Integer.valueOf(34)), "1 of 3 does not reach 34 percent");
		check(RejectThreshold.reached(Long.MAX_VALUE / 1000, Long.MAX_VALUE / 1000, Integer.valueOf(100)), "a total reject reaches 100 percent, no overflow on big counts");
		check(RejectThreshold.roundedPercent(1204, 72) == 6, "rounded 6");
		check(RejectThreshold.roundedPercent(0, 5) == 0, "no division by zero");
		check(RejectThreshold.parse("5").intValue() == 5 && RejectThreshold.parse(" 12 ").intValue() == 12, "parse");
		check(RejectThreshold.parse("") == null && RejectThreshold.parse(null) == null && RejectThreshold.parse("abc") == null && RejectThreshold.parse("0") == null
			&& RejectThreshold.parse("101") == null, "parse refuses anything else");
	}
}
