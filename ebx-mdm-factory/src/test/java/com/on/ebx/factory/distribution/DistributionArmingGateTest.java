package com.on.ebx.factory.distribution;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.DistributionArmingGate.Decision;
import com.on.ebx.factory.distribution.DistributionArmingGate.Readiness;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class DistributionArmingGateTest
{
	public static void main(final String[] pArgs)
	{
		DistributionArmingGateTest.readyPipelineAllowsFirstFullSend();
		DistributionArmingGateTest.missingLandingBlocksFirstFullSend();
		DistributionArmingGateTest.missingMasterBlocksFirstFullSend();
		DistributionArmingGateTest.missingMatchingBlocksFirstFullSend();
		DistributionArmingGateTest.noMatchingPlannedSkipsMatchingRequirement();
		DistributionArmingGateTest.noMatchingPlannedDoesNotOverrideAnUnfinishedButPlannedRun();
		DistributionArmingGateTest.succeededSendArms();
		DistributionArmingGateTest.failedSendWaits();
		DistributionArmingGateTest.overrideAlwaysArms();
		DistributionArmingGateTest.onlyArmedMayQueue();
		System.out.println("DistributionArmingGateTest OK");
	}

	private static void readyPipelineAllowsFirstFullSend()
	{
		final Decision d = DistributionArmingGate.canSendFirstFull(new Readiness(true, true, true));
		DistributionArmingGateTest.check("ready pipeline allows the first full send", d.allowed);
		DistributionArmingGateTest.check("no reason when allowed", d.reason.isEmpty());
	}

	private static void missingLandingBlocksFirstFullSend()
	{
		final Decision d = DistributionArmingGate.canSendFirstFull(new Readiness(false, true, true));
		DistributionArmingGateTest.check("missing landing blocks", !d.allowed);
		DistributionArmingGateTest.check("reason names landing", d.reason.toLowerCase(java.util.Locale.ENGLISH).contains("landing"));
	}

	private static void missingMasterBlocksFirstFullSend()
	{
		final Decision d = DistributionArmingGate.canSendFirstFull(new Readiness(true, false, true));
		DistributionArmingGateTest.check("missing master blocks", !d.allowed);
		DistributionArmingGateTest.check("reason names master", d.reason.toLowerCase(java.util.Locale.ENGLISH).contains("master"));
	}

	private static void missingMatchingBlocksFirstFullSend()
	{
		final Decision d = DistributionArmingGate.canSendFirstFull(new Readiness(true, true, false));
		DistributionArmingGateTest.check("missing matching blocks", !d.allowed);
		DistributionArmingGateTest.check("reason names matching", d.reason.toLowerCase(java.util.Locale.ENGLISH).contains("matching"));
	}

	/** Task brief item 3, DistribTest live incident: a project may legitimately have no matching plan at all - the requirement is dropped, not merely relaxed. */
	private static void noMatchingPlannedSkipsMatchingRequirement()
	{
		final Decision d = DistributionArmingGate.canSendFirstFull(new Readiness(true, true, false, true));
		DistributionArmingGateTest.check("no matching planned allows the first full send even though no run finished", d.allowed);
	}

	/** {@code noMatchingPlanned} is not a blanket override: a project that DOES have a plan still needs it run. */
	private static void noMatchingPlannedDoesNotOverrideAnUnfinishedButPlannedRun()
	{
		final Decision d = DistributionArmingGate.canSendFirstFull(new Readiness(true, true, false, false));
		DistributionArmingGateTest.check("the 4-arg constructor with noMatchingPlanned=false still blocks", !d.allowed);
	}

	private static void succeededSendArms()
	{
		DistributionArmingGateTest.check("a succeeded first full send arms",
			FactoryPaths.DistributionTarget.STATE_ARMED.equals(DistributionArmingGate.stateAfterFirstFullSend(true)));
	}

	private static void failedSendWaits()
	{
		DistributionArmingGateTest.check("a failed first full send waits",
			FactoryPaths.DistributionTarget.STATE_WAITING.equals(DistributionArmingGate.stateAfterFirstFullSend(false)));
	}

	private static void overrideAlwaysArms()
	{
		DistributionArmingGateTest.check("the admin override always arms",
			FactoryPaths.DistributionTarget.STATE_ARMED.equals(DistributionArmingGate.stateForOverride()));
	}

	private static void onlyArmedMayQueue()
	{
		DistributionArmingGateTest.check("ARMED may queue", DistributionArmingGate.mayQueueRealtimeEvent(FactoryPaths.DistributionTarget.STATE_ARMED));
		DistributionArmingGateTest.check("DRAFT may not queue", !DistributionArmingGate.mayQueueRealtimeEvent(FactoryPaths.DistributionTarget.STATE_DRAFT));
		DistributionArmingGateTest.check("WAITING may not queue", !DistributionArmingGate.mayQueueRealtimeEvent(FactoryPaths.DistributionTarget.STATE_WAITING));
		DistributionArmingGateTest.check("PAUSED may not queue", !DistributionArmingGate.mayQueueRealtimeEvent(FactoryPaths.DistributionTarget.STATE_PAUSED));
		DistributionArmingGateTest.check("DISABLED may not queue", !DistributionArmingGate.mayQueueRealtimeEvent(FactoryPaths.DistributionTarget.STATE_DISABLED));
		DistributionArmingGateTest.check("null may not queue", !DistributionArmingGate.mayQueueRealtimeEvent(null));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
