package com.on.ebx.factory.core;

import java.util.HashSet;
import java.util.Set;

/** JUnit-free: run the main, a failure throws. Which pauses a load left behind are orphans, and what the start of the module does with each. */
public final class LoadPauseRuleTest
{
	public static void main(final String[] pArgs)
	{
		LoadPauseRuleTest.orphanMeansNoLiveLoadHoldsTheToken();
		LoadPauseRuleTest.aLiveOwnerIsNeverTouched();
		LoadPauseRuleTest.anOrphanIsLiftedOnlyWhileStillPaused();
		LoadPauseRuleTest.aThingSomebodyAlreadyChangedOnlyLosesItsRow();
		LoadPauseRuleTest.anUnreadableStateIsRetriedNotLifted();
		LoadPauseRuleTest.targetObservation();
		LoadPauseRuleTest.matchingObservation();
		System.out.println("LoadPauseRuleTest OK");
	}

	private static void orphanMeansNoLiveLoadHoldsTheToken()
	{
		final Set<String> live = new HashSet<String>();
		live.add("A");
		LoadPauseRuleTest.check("live token is not an orphan", !LoadPauseRule.isOrphan("A", live));
		LoadPauseRuleTest.check("another token is an orphan", LoadPauseRule.isOrphan("B", live));
		LoadPauseRuleTest.check("after a restart nothing is live", LoadPauseRule.isOrphan("A", new HashSet<String>()));
		LoadPauseRuleTest.check("no token, no owner", LoadPauseRule.isOrphan(null, live) && LoadPauseRule.isOrphan("", live));
		LoadPauseRuleTest.check("null set", LoadPauseRule.isOrphan("A", null));
	}

	private static void aLiveOwnerIsNeverTouched()
	{
		for (final LoadPauseRule.Observation observation : LoadPauseRule.Observation.values())
		{
			LoadPauseRuleTest.check("alive + " + observation, LoadPauseRule.decide(true, observation) == LoadPauseRule.Action.KEEP);
		}
	}

	private static void anOrphanIsLiftedOnlyWhileStillPaused()
	{
		LoadPauseRuleTest.check("paused orphan is resumed", LoadPauseRule.decide(false, LoadPauseRule.Observation.PAUSED) == LoadPauseRule.Action.RESUME);
	}

	private static void aThingSomebodyAlreadyChangedOnlyLosesItsRow()
	{
		LoadPauseRuleTest.check("not paused: only the row goes", LoadPauseRule.decide(false, LoadPauseRule.Observation.NOT_PAUSED) == LoadPauseRule.Action.DROP);
		LoadPauseRuleTest.check("gone: only the row goes", LoadPauseRule.decide(false, LoadPauseRule.Observation.GONE) == LoadPauseRule.Action.DROP);
	}

	private static void anUnreadableStateIsRetriedNotLifted()
	{
		LoadPauseRuleTest.check("unknown is kept", LoadPauseRule.decide(false, LoadPauseRule.Observation.UNKNOWN) == LoadPauseRule.Action.KEEP);
		LoadPauseRuleTest.check("null observation is unknown", LoadPauseRule.decide(false, null) == LoadPauseRule.Action.KEEP);
	}

	private static void targetObservation()
	{
		LoadPauseRuleTest.check("missing target", LoadPauseRule.observeTarget(false, null) == LoadPauseRule.Observation.GONE);
		LoadPauseRuleTest.check("PAUSED", LoadPauseRule.observeTarget(true, "PAUSED") == LoadPauseRule.Observation.PAUSED);
		// a target a person turned off (or never armed) must never be re-armed by the recovery
		LoadPauseRuleTest.check("DISABLED is not paused", LoadPauseRule.observeTarget(true, "DISABLED") == LoadPauseRule.Observation.NOT_PAUSED);
		LoadPauseRuleTest.check("ARMED is not paused", LoadPauseRule.observeTarget(true, "ARMED") == LoadPauseRule.Observation.NOT_PAUSED);
		LoadPauseRuleTest.check("DRAFT is not paused", LoadPauseRule.observeTarget(true, "DRAFT") == LoadPauseRule.Observation.NOT_PAUSED);
	}

	private static void matchingObservation()
	{
		LoadPauseRuleTest.check("unreadable answer", LoadPauseRule.observeMatching(false, null, null) == LoadPauseRule.Observation.UNKNOWN);
		LoadPauseRuleTest.check("paused flag", LoadPauseRule.observeMatching(true, "ENABLED", Boolean.TRUE) == LoadPauseRule.Observation.PAUSED);
		LoadPauseRuleTest.check("state PAUSED", LoadPauseRule.observeMatching(true, "paused", null) == LoadPauseRule.Observation.PAUSED);
		LoadPauseRuleTest.check("running table", LoadPauseRule.observeMatching(true, "ENABLED", Boolean.FALSE) == LoadPauseRule.Observation.NOT_PAUSED);
		LoadPauseRuleTest.check("disabled table", LoadPauseRule.observeMatching(true, "DISABLED", null) == LoadPauseRule.Observation.NOT_PAUSED);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
