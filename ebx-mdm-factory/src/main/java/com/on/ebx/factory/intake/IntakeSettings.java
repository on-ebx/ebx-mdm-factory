package com.on.ebx.factory.intake;

/**
 * Automatic intake lot (2026-10-05, docs/AUTOMATIC-INTAKE.md): the per-application settings of the intake and their validation. Pure.
 * <p>
 * A stored empty value means "the default", so rows written before this lot behave as the defaults ({@link #of}); a stored value out of range (edited
 * by hand in the table) is clamped on READ, never thrown on - the engine must not stop for one bad cell. {@link #validate} is what {@code ebx.Intake#saveSettings}
 * applies to what a person TYPES: it refuses instead of clamping.
 */
public final class IntakeSettings
{
	public static final int DEFAULT_QUIET_SECONDS = 60;
	public static final int MIN_QUIET_SECONDS = 5;
	public static final int MAX_QUIET_SECONDS = 3600;
	public static final int DEFAULT_MAX_WAIT_MINUTES = 10;
	public static final int MIN_MAX_WAIT_MINUTES = 1;
	public static final int MAX_MAX_WAIT_MINUTES = 1440;
	public static final int DEFAULT_MIN_ROWS = 1;
	public static final int MIN_PERCENT = 1;
	public static final int MAX_PERCENT = 100;

	public final int quietSeconds;
	public final int maxWaitMinutes;
	public final int minRows;
	public final boolean batchSignal;

	private IntakeSettings(final int pQuietSeconds, final int pMaxWaitMinutes, final int pMinRows, final boolean pBatchSignal)
	{
		this.quietSeconds = pQuietSeconds;
		this.maxWaitMinutes = pMaxWaitMinutes;
		this.minRows = pMinRows;
		this.batchSignal = pBatchSignal;
	}

	/** The defaults: 60 s of quiet, 10 minutes at the latest, one row. */
	public static IntakeSettings defaults()
	{
		return new IntakeSettings(IntakeSettings.DEFAULT_QUIET_SECONDS, IntakeSettings.DEFAULT_MAX_WAIT_MINUTES, IntakeSettings.DEFAULT_MIN_ROWS, false);
	}

	/**
	 * What the engine uses from stored (possibly empty or out-of-range) cells: empty is the default, a value out of range is clamped, and the longest wait is
	 * never below the quiet period (rounded up to whole minutes) - otherwise the quiet period could never elapse.
	 */
	public static IntakeSettings of(final Integer pQuietSeconds, final Integer pMaxWaitMinutes, final Integer pMinRows, final Boolean pBatchSignal)
	{
		final int quiet = IntakeSettings.clamp(pQuietSeconds, IntakeSettings.DEFAULT_QUIET_SECONDS, IntakeSettings.MIN_QUIET_SECONDS, IntakeSettings.MAX_QUIET_SECONDS);
		int maxWait = IntakeSettings.clamp(pMaxWaitMinutes, IntakeSettings.DEFAULT_MAX_WAIT_MINUTES, IntakeSettings.MIN_MAX_WAIT_MINUTES,
			IntakeSettings.MAX_MAX_WAIT_MINUTES);
		final int quietMinutes = (quiet + 59) / 60;
		if (maxWait < quietMinutes)
		{
			maxWait = Math.min(IntakeSettings.MAX_MAX_WAIT_MINUTES, quietMinutes);
		}
		final int minRows = pMinRows == null ? IntakeSettings.DEFAULT_MIN_ROWS : Math.max(1, pMinRows.intValue());
		return new IntakeSettings(quiet, maxWait, minRows, Boolean.TRUE.equals(pBatchSignal));
	}

	private static int clamp(final Integer pValue, final int pDefault, final int pMin, final int pMax)
	{
		if (pValue == null)
		{
			return pDefault;
		}
		return Math.max(pMin, Math.min(pMax, pValue.intValue()));
	}

	/**
	 * What a person typed: null when acceptable, else the reason (English, one sentence). Quiet 5-3600 s, longest wait 1-1440 min and not below the quiet period,
	 * minimum rows at least 1. An empty value (null) is always acceptable: it means the default.
	 */
	public static String validate(final Integer pQuietSeconds, final Integer pMaxWaitMinutes, final Integer pMinRows)
	{
		if (pQuietSeconds != null && (pQuietSeconds.intValue() < IntakeSettings.MIN_QUIET_SECONDS || pQuietSeconds.intValue() > IntakeSettings.MAX_QUIET_SECONDS))
		{
			return "The quiet period must be between " + IntakeSettings.MIN_QUIET_SECONDS + " and " + IntakeSettings.MAX_QUIET_SECONDS + " seconds.";
		}
		if (pMaxWaitMinutes != null && (pMaxWaitMinutes.intValue() < IntakeSettings.MIN_MAX_WAIT_MINUTES || pMaxWaitMinutes.intValue() > IntakeSettings.MAX_MAX_WAIT_MINUTES))
		{
			return "The longest wait must be between " + IntakeSettings.MIN_MAX_WAIT_MINUTES + " and " + IntakeSettings.MAX_MAX_WAIT_MINUTES + " minutes.";
		}
		if (pMinRows != null && pMinRows.intValue() < 1)
		{
			return "The minimum number of rows must be at least 1.";
		}
		final int quiet = pQuietSeconds == null ? IntakeSettings.DEFAULT_QUIET_SECONDS : pQuietSeconds.intValue();
		final int maxWait = pMaxWaitMinutes == null ? IntakeSettings.DEFAULT_MAX_WAIT_MINUTES : pMaxWaitMinutes.intValue();
		if (maxWait * 60 < quiet)
		{
			return "The longest wait (" + maxWait + " min) must not be shorter than the quiet period (" + quiet + " s).";
		}
		return null;
	}

	/** The project's stop threshold as typed: null when acceptable (empty = never stop, otherwise 1-100). */
	public static String validatePercent(final Integer pPercent)
	{
		if (pPercent != null && (pPercent.intValue() < IntakeSettings.MIN_PERCENT || pPercent.intValue() > IntakeSettings.MAX_PERCENT))
		{
			return "The stop threshold must be between " + IntakeSettings.MIN_PERCENT + " and " + IntakeSettings.MAX_PERCENT + " percent, or empty (never stop).";
		}
		return null;
	}
}
