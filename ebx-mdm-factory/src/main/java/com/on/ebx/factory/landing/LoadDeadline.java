package com.on.ebx.factory.landing;

/**
 * The absolute time limit of ONE table's asynchronous landing load (audit F-20): {@code ebx.LandingFlows#awaitLoad} polls
 * {@code connectors_run_get} until the run reaches a terminal state, and every poll also checks the job in
 * ({@code FactoryJobs.Handle#cancelled} pushes the inactivity watchdog forward), so a Connectors run that stays RUNNING for
 * ever - a hung source, a lost worker - would keep this loop, its pool thread and the project's LANDING claim for ever.
 * <p>
 * The limit is a documented constant, not a {@code Settings} field (no model change): {@link #DEFAULT_MAX_MINUTES} per
 * table, 12 hours - well past a legitimate multi-hour load of one table (the platform holds hundreds of millions of rows),
 * far short of "never". Raise or lower it with the JVM property {@link #PROPERTY} (minutes, read at each load, so no restart is
 * needed once Tomcat's JVM options hold it); {@code 0} or a negative value is read as the default, never as "no limit".
 */
public final class LoadDeadline
{
	/** JVM property: the minutes one table's load may wait for its Connectors run before the run is stopped and the table fails. */
	public static final String PROPERTY = "ebx.factory.landingLoadMaxMinutes";
	public static final long DEFAULT_MAX_MINUTES = 12L * 60L;

	private LoadDeadline()
	{
	}

	/** The limit in milliseconds from the JVM property, or the default when it is absent, unreadable or not positive. */
	public static long maxMillis()
	{
		return LoadDeadline.maxMillis(System.getProperty(LoadDeadline.PROPERTY));
	}

	/** Same for an explicit property value (the test's entry point). */
	public static long maxMillis(final String pPropertyValue)
	{
		long minutes = LoadDeadline.DEFAULT_MAX_MINUTES;
		if (pPropertyValue != null)
		{
			try
			{
				final long parsed = Long.parseLong(pPropertyValue.trim());
				if (parsed > 0L)
				{
					minutes = parsed;
				}
			}
			catch (final NumberFormatException ex)
			{
				// an unreadable value falls back to the default: a typo must not turn the guard off
			}
		}
		return minutes * 60000L;
	}

	/** Whether the wait that started at {@code pStartedAt} has used up {@code pMaxMillis} at {@code pNow}. */
	public static boolean expired(final long pStartedAt, final long pNow, final long pMaxMillis)
	{
		return pMaxMillis > 0L && pNow - pStartedAt >= pMaxMillis;
	}

	/** The failure sentence of a table whose run was stopped for waiting too long: names the run and how long the load waited. */
	public static String message(final String pTableName, final String pRunId, final long pWaitedMillis, final long pLimitMillis, final long pRowsSoFar,
		final boolean pStopRequested)
	{
		final long minutes = Math.max(0L, pWaitedMillis / 60000L);
		return "Load of " + pTableName + " failed: Connectors run " + pRunId + " was still running after " + minutes + " minute(s) (limit " + (pLimitMillis / 60000L)
			+ ", JVM property " + LoadDeadline.PROPERTY + "), " + pRowsSoFar + " row(s) reached; "
			+ (pStopRequested ? "Connectors was asked to stop the run" : "stopping the run FAILED, check it in Connectors") + " - re-run the load to continue.";
	}
}
