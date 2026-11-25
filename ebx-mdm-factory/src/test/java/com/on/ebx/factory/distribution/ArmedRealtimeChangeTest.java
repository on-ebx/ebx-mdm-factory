package com.on.ebx.factory.distribution;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class ArmedRealtimeChangeTest
{
	private static final String REALTIME = "REALTIME";
	private static final String ON_DEMAND = "ON_DEMAND";
	private static final String ARMED = "ARMED";
	private static final String PAUSED = "PAUSED";
	private static final String DRAFT = "DRAFT";

	public static void main(final String[] pArgs)
	{
		ArmedRealtimeChangeTest.countsOnlyRealtimeAndArmedTogether();
		ArmedRealtimeChangeTest.noneWhenNeitherSideCounts();
		ArmedRealtimeChangeTest.noneWhenBothSidesCount();
		ArmedRealtimeChangeTest.armOnPauseToArmed();
		ArmedRealtimeChangeTest.armOnModeSwitchToRealtimeWhileAlreadyArmed();
		ArmedRealtimeChangeTest.disarmOnArmedToPaused();
		ArmedRealtimeChangeTest.disarmOnModeSwitchAwayFromRealtimeWhileArmed();
		ArmedRealtimeChangeTest.noneOnDraftToWaitingNeverCounts();
		System.out.println("ArmedRealtimeChangeTest OK");
	}

	private static void countsOnlyRealtimeAndArmedTogether()
	{
		ArmedRealtimeChangeTest.check("realtime+armed counts", ArmedRealtimeChange.counts(REALTIME, ARMED));
		ArmedRealtimeChangeTest.check("on_demand+armed does not count", !ArmedRealtimeChange.counts(ON_DEMAND, ARMED));
		ArmedRealtimeChangeTest.check("realtime+paused does not count", !ArmedRealtimeChange.counts(REALTIME, PAUSED));
		ArmedRealtimeChangeTest.check("null mode/state does not count", !ArmedRealtimeChange.counts(null, null));
	}

	private static void noneWhenNeitherSideCounts()
	{
		ArmedRealtimeChangeTest.check("draft -> waiting, never realtime: NONE",
			ArmedRealtimeChange.directionOf(ON_DEMAND, DRAFT, ON_DEMAND, "WAITING") == ArmedRealtimeChange.Direction.NONE);
	}

	private static void noneWhenBothSidesCount()
	{
		// an edit that leaves an already-armed realtime target armed (e.g. renaming its connector) changes nothing
		ArmedRealtimeChangeTest.check("armed realtime -> armed realtime: NONE",
			ArmedRealtimeChange.directionOf(REALTIME, ARMED, REALTIME, ARMED) == ArmedRealtimeChange.Direction.NONE);
	}

	private static void armOnPauseToArmed()
	{
		// DistributionPause.resume: PAUSED -> ARMED, mode unchanged
		ArmedRealtimeChangeTest.check("paused -> armed (realtime): ARM", ArmedRealtimeChange.directionOf(REALTIME, PAUSED, REALTIME, ARMED) == ArmedRealtimeChange.Direction.ARM);
	}

	private static void armOnModeSwitchToRealtimeWhileAlreadyArmed()
	{
		// DistributionReader.upsertTarget: mode switched to REALTIME while state was already ARMED (edge case)
		ArmedRealtimeChangeTest.check("armed, on_demand -> realtime: ARM", ArmedRealtimeChange.directionOf(ON_DEMAND, ARMED, REALTIME, ARMED) == ArmedRealtimeChange.Direction.ARM);
	}

	private static void disarmOnArmedToPaused()
	{
		// DistributionPause.pause
		ArmedRealtimeChangeTest.check("armed -> paused (realtime): DISARM",
			ArmedRealtimeChange.directionOf(REALTIME, ARMED, REALTIME, PAUSED) == ArmedRealtimeChange.Direction.DISARM);
	}

	private static void disarmOnModeSwitchAwayFromRealtimeWhileArmed()
	{
		ArmedRealtimeChangeTest.check("armed, realtime -> on_demand: DISARM",
			ArmedRealtimeChange.directionOf(REALTIME, ARMED, ON_DEMAND, ARMED) == ArmedRealtimeChange.Direction.DISARM);
	}

	private static void noneOnDraftToWaitingNeverCounts()
	{
		ArmedRealtimeChangeTest.check("draft -> disabled, on_demand throughout: NONE",
			ArmedRealtimeChange.directionOf(ON_DEMAND, DRAFT, ON_DEMAND, "DISABLED") == ArmedRealtimeChange.Direction.NONE);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
