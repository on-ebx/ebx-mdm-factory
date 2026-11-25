package com.on.ebx.factory.intake;

/**
 * Automatic intake lot: the "stop the chain at staging when too many rows are held back" decision. Integer maths only, no EBX class. Used by
 * {@code workflow.StagingLoadTaskBean} (from the loader's own {@code read}/{@code rejected} totals) and by {@code ebx.IntakeEngine} (to word the pause reason).
 */
public final class RejectThreshold
{
	private RejectThreshold()
	{
	}

	/**
	 * True when AT LEAST {@code pPercent} percent of the rows read were held back: {@code rejected * 100 >= percent * read}. No threshold (null) never stops;
	 * nothing read (0 or less) never stops (no division, no verdict on an empty run); no row held back never stops (a threshold of 1 with 0 rejected is not a stop);
	 * exactly at the threshold DOES stop ("at least"). So a threshold of 5 stops at 5 of 100, and a threshold of 100 stops only when every row read is held back.
	 */
	public static boolean reached(final long pRead, final long pRejected, final Integer pPercent)
	{
		if (pPercent == null || pPercent.intValue() <= 0 || pRead <= 0 || pRejected <= 0)
		{
			return false;
		}
		return pRejected * 100L >= pPercent.longValue() * pRead;
	}

	/** The share held back as a whole percent, rounded half up; 0 when nothing was read. For messages only: never decide with it. */
	public static int roundedPercent(final long pRead, final long pRejected)
	{
		if (pRead <= 0 || pRejected <= 0)
		{
			return 0;
		}
		return (int) ((pRejected * 100L + pRead / 2L) / pRead);
	}

	/** Parses a workflow input such as {@code "5"} or {@code ""}: null when empty or not a whole number between 1 and 100 (an unreadable threshold never stops anything, and the engine validated what it passes). */
	public static Integer parse(final String pText)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final int value = Integer.parseInt(pText.trim());
			return value >= IntakeSettings.MIN_PERCENT && value <= IntakeSettings.MAX_PERCENT ? Integer.valueOf(value) : null;
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}
}
