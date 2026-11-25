package com.on.ebx.factory.ebx;

/**
 * The two pure decisions that keep {@link FactoryJobQueue} from starting a job it cannot run yet (audit F-37), apart from
 * the in-memory state they read so they are tested directly ({@code FactoryJobLimitsTest}).
 * <ul>
 * <li><b>A free worker.</b> The pool has a fixed number of threads and an unbounded queue: a job marked RUNNING while
 * every thread is busy would show "running" in the banner and have its time limit counted while it merely waits.</li>
 * <li><b>The instance-wide limit on heavy jobs</b> (matching runs, full loads - {@code FactoryJobKinds}). The
 * resource claim is per project, so several projects could otherwise saturate the processor with matching runs at
 * once. JVM property {@link #HEAVY_PROPERTY}: how many heavy jobs may run at the same moment across the instance;
 * absent, {@code 0}, negative or unreadable means no limit, which is what the engine did before.</li>
 * </ul>
 */
final class FactoryJobLimits
{
	/** JVM property: the most heavy jobs (matching runs, full loads, first full distribution) running at once on the instance; 0 or absent = unlimited. */
	static final String HEAVY_PROPERTY = "ebx.factory.maxHeavyJobs";

	private FactoryJobLimits()
	{
	}

	/** The limit from the JVM property; 0 means unlimited (also for a value that is absent, negative or not a number). */
	static int heavyLimit()
	{
		return FactoryJobLimits.heavyLimit(System.getProperty(FactoryJobLimits.HEAVY_PROPERTY));
	}

	static int heavyLimit(final String pPropertyValue)
	{
		if (pPropertyValue == null)
		{
			return 0;
		}
		try
		{
			return Math.max(0, Integer.parseInt(pPropertyValue.trim()));
		}
		catch (final NumberFormatException ex)
		{
			return 0;
		}
	}

	/**
	 * Whether a QUEUED job may be claimed and marked RUNNING now.
	 *
	 * @param pWorkers pool size
	 * @param pInFlight jobs submitted to the pool and not yet ended (running or waiting for a thread)
	 * @param pHeavyKind whether this job's kind is a heavy one
	 * @param pHeavyInFlight heavy jobs in flight now
	 * @param pHeavyLimit {@link #heavyLimit()}; 0 = unlimited
	 */
	static boolean mayStart(final int pWorkers, final int pInFlight, final boolean pHeavyKind, final int pHeavyInFlight, final int pHeavyLimit)
	{
		if (pInFlight >= pWorkers)
		{
			return false;
		}
		return !pHeavyKind || pHeavyLimit <= 0 || pHeavyInFlight < pHeavyLimit;
	}
}
