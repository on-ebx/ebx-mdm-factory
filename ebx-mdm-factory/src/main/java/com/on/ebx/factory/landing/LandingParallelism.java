package com.on.ebx.factory.landing;

/**
 * Lot L2 (2026-10-12, docs/VOLUME.md "Lot L2 - parallel landing loads"): how many sources of one project may load their landing at the same time,
 * and the schedule a chain of sources follows for a given bound. Pure: no EBX class, unit-tested without a repository.
 * <p>
 * The chain of a project ({@code ebx.LandingRun}, the workflow bean {@code LandingLoadTaskBean}) walks the sources one after the other for
 * everything that is not the data copy (key, landing model, flows: the {@code data-model} and Connectors servers are driven by ONE thread, see the lot's
 * doc), and hands the data copy (LOAD) of each source to a pool of at most {@link #bound()} threads. A bound of 1 is the behaviour before the lot:
 * each source does all its steps, load included, before the next one starts.
 */
public final class LandingParallelism
{
	/** The JVM property that bounds the number of sources loading at once (docs/OPERATIONS.md, section 3.1). */
	public static final String PROPERTY = "mdm.factory.landing.parallelSources";
	public static final int DEFAULT = 2;
	/**
	 * Upper limit whatever the property says. The in-JVM Connectors bridge keeps 8 threads for every exchange of the module and REFUSES a ninth
	 * ("bridge is busy", {@code ConnectorsBridge.MAX_THREADS}); a load holds one only for the length of one call (it starts the run and polls), but
	 * the other jobs and the other chains use it too, so a chain never asks for more than half of them.
	 */
	public static final int MAX = 4;

	private LandingParallelism()
	{
	}

	/** The bound in force now: the property, read at each chain (no restart needed), clamped to 1..{@link #MAX}; absent or not a number = {@link #DEFAULT}. */
	public static int bound()
	{
		return LandingParallelism.parse(System.getProperty(LandingParallelism.PROPERTY));
	}

	/** Pure: {@code pValue} as a bound. Blank, not an integer = {@link #DEFAULT}; below 1 = 1 (sequential); above {@link #MAX} = {@link #MAX}. */
	public static int parse(final String pValue)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return LandingParallelism.DEFAULT;
		}
		final int value;
		try
		{
			value = Integer.parseInt(pValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return LandingParallelism.DEFAULT;
		}
		return Math.max(1, Math.min(LandingParallelism.MAX, value));
	}

	/** Pure: whether the loads of a chain of {@code pSources} sources go to a pool (more than one source AND a bound above 1) or stay inline on the chain's thread. */
	public static boolean usesPool(final int pSources, final int pBound)
	{
		return pBound > 1 && pSources > 1;
	}

	/** What {@link #schedule} computes: when each source's load starts and ends, in the unit of the inputs. */
	public static final class Schedule
	{
		public final long[] loadStart;
		public final long[] loadEnd;
		/** Time when the last load ends. */
		public final long makespan;
		/** The largest number of loads running at the same instant - never above the bound. */
		public final int maxConcurrentLoads;

		Schedule(final long[] pLoadStart, final long[] pLoadEnd, final long pMakespan, final int pMaxConcurrent)
		{
			this.loadStart = pLoadStart;
			this.loadEnd = pLoadEnd;
			this.makespan = pMakespan;
			this.maxConcurrentLoads = pMaxConcurrent;
		}
	}

	/**
	 * Pure: the schedule the chain follows for sources whose preparation (key, model, flows) takes {@code pPrepare[i]} and whose load takes
	 * {@code pLoad[i]}, in the order the chain walks them, for a bound of {@code pBound}.
	 * <ul>
	 * <li>Bound 1 (or one source): inline, source {@code i} prepares, loads, then source {@code i+1} starts - the sum of everything.</li>
	 * <li>Bound {@code n} &gt; 1: ONE thread prepares the sources one after the other (never two preparations at once) and submits the load of each as
	 * soon as its preparation ends; a load starts at once when fewer than {@code n} are running, else when the first running one ends (FIFO, like the
	 * pool's queue).</li>
	 * </ul>
	 * This is a model of the code's behaviour, not a measurement: it says what the bound can bring when the durations are what they were.
	 */
	public static Schedule schedule(final long[] pPrepare, final long[] pLoad, final int pBound)
	{
		final int n = pPrepare.length;
		final long[] start = new long[n];
		final long[] end = new long[n];
		if (!LandingParallelism.usesPool(n, pBound))
		{
			long clock = 0L;
			for (int i = 0; i < n; i++)
			{
				clock += pPrepare[i];
				start[i] = clock;
				clock += pLoad[i];
				end[i] = clock;
			}
			return new Schedule(start, end, clock, n == 0 ? 0 : 1);
		}
		final long[] slotFreeAt = new long[pBound];
		long prepared = 0L;
		long makespan = 0L;
		for (int i = 0; i < n; i++)
		{
			prepared += pPrepare[i];
			int slot = 0;
			for (int s = 1; s < pBound; s++)
			{
				if (slotFreeAt[s] < slotFreeAt[slot])
				{
					slot = s;
				}
			}
			start[i] = Math.max(prepared, slotFreeAt[slot]);
			end[i] = start[i] + pLoad[i];
			slotFreeAt[slot] = end[i];
			makespan = Math.max(makespan, end[i]);
		}
		// the chain's own thread is done after the last preparation, but the run is over when the last load ends
		makespan = Math.max(makespan, prepared);
		int maxConcurrent = 0;
		for (int i = 0; i < n; i++)
		{
			int running = 0;
			for (int j = 0; j < n; j++)
			{
				if (start[j] <= start[i] && start[i] < end[j])
				{
					running++;
				}
			}
			maxConcurrent = Math.max(maxConcurrent, running);
		}
		return new Schedule(start, end, makespan, maxConcurrent);
	}
}
