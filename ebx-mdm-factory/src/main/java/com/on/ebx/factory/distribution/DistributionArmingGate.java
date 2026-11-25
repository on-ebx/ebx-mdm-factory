package com.on.ebx.factory.distribution;

/**
 * Distribution lot 1 (engine, SPEC-distribution.md "Arming gate"): pure decision of whether a REALTIME
 * target's first full send may be started, and what state the target lands in afterwards. No EBX class -
 * the caller ({@code ebx.DistributionRunner}/{@code rest.FactoryResource}) reads the project's own pipeline
 * bookkeeping ({@code PipelineStep} rows) into a {@link Readiness} and this class alone decides.
 * <p>
 * Per the spec: "REALTIME target arms only when landing loaded from every source, master built, first
 * matching run finished, and a first full send (one batch, scope ALL) succeeded; an 'Arm without it'
 * override exists (admin)." This class splits that sentence in two: {@link #canSendFirstFull} gates the
 * button that starts the batch, {@link #stateAfterFirstFullSend}/{@link #stateForOverride} say what state
 * results once a send attempt (or an override) has actually happened. The target's own {@code state} field
 * is the only place "WAITING" (attempted and failed, or never attempted) is remembered - this class is
 * stateless and never reads or writes it.
 */
public final class DistributionArmingGate
{
	private DistributionArmingGate()
	{
	}

	/** Every project pipeline fact the gate needs, read by the caller from {@code PipelineStep}/{@code Project}/{@code MatchPlan}. */
	public static final class Readiness
	{
		public final boolean landingLoadedFromEverySource;
		public final boolean masterBuilt;
		public final boolean firstMatchingRunFinished;
		/**
		 * Distribution round 4 (2026-09-26, task brief item 3, DistribTest live incident): true when the
		 * project has NO matching plan at all (every entity skipped for lack of a validated identifier, both
		 * the rule and the language-model pass) - see {@link #matchingSatisfied}. Defaults to {@code false} on
		 * the 3-arg constructor, so every caller that never learned about this fact keeps the old, stricter
		 * behaviour.
		 */
		public final boolean noMatchingPlanned;

		public Readiness(final boolean pLandingLoadedFromEverySource, final boolean pMasterBuilt, final boolean pFirstMatchingRunFinished)
		{
			this(pLandingLoadedFromEverySource, pMasterBuilt, pFirstMatchingRunFinished, false);
		}

		public Readiness(final boolean pLandingLoadedFromEverySource, final boolean pMasterBuilt, final boolean pFirstMatchingRunFinished,
			final boolean pNoMatchingPlanned)
		{
			this.landingLoadedFromEverySource = pLandingLoadedFromEverySource;
			this.masterBuilt = pMasterBuilt;
			this.firstMatchingRunFinished = pFirstMatchingRunFinished;
			this.noMatchingPlanned = pNoMatchingPlanned;
		}

		/** A project may legitimately have no matching plan to run at all - see {@link #noMatchingPlanned}'s own Javadoc. */
		public boolean matchingSatisfied()
		{
			return this.firstMatchingRunFinished || this.noMatchingPlanned;
		}

		public boolean pipelineReady()
		{
			return this.landingLoadedFromEverySource && this.masterBuilt && this.matchingSatisfied();
		}
	}

	/** Whether an action may proceed, with a sentence to show when it may not. */
	public static final class Decision
	{
		public final boolean allowed;
		/** Empty when {@link #allowed} is true. */
		public final String reason;

		Decision(final boolean pAllowed, final String pReason)
		{
			this.allowed = pAllowed;
			this.reason = pReason == null ? "" : pReason;
		}
	}

	/** May a first full send (Connectors scope ALL) be started for this target right now? */
	public static Decision canSendFirstFull(final Readiness pReadiness)
	{
		if (pReadiness == null)
		{
			return new Decision(false, "The project's pipeline state could not be read.");
		}
		if (!pReadiness.landingLoadedFromEverySource)
		{
			return new Decision(false, "The landing has not been loaded from every source of the project yet.");
		}
		if (!pReadiness.masterBuilt)
		{
			return new Decision(false, "The master (MDM) zone has not been built yet.");
		}
		if (!pReadiness.matchingSatisfied())
		{
			return new Decision(false, "The first matching run has not finished yet.");
		}
		return new Decision(true, "");
	}

	/** The target's state once a first-full-send batch has actually run (succeeded or not). */
	public static String stateAfterFirstFullSend(final boolean pSucceeded)
	{
		return pSucceeded ? com.on.ebx.factory.core.FactoryPaths.DistributionTarget.STATE_ARMED
			: com.on.ebx.factory.core.FactoryPaths.DistributionTarget.STATE_WAITING;
	}

	/** The target's state when an administrator arms it without a successful first full send. Always {@code ARMED}: {@code DistributionTarget.armedViaOverride} is what records that this path was taken. */
	public static String stateForOverride()
	{
		return com.on.ebx.factory.core.FactoryPaths.DistributionTarget.STATE_ARMED;
	}

	/**
	 * Whether a REALTIME target's table trigger is allowed to queue an event: {@code state} must be exactly
	 * {@code ARMED} - {@code DRAFT}/{@code WAITING} (never sent a first full batch), {@code PAUSED} (a full
	 * pipeline reload is running - {@code ebx.DistributionPause}) and {@code DISABLED} all skip.
	 */
	public static boolean mayQueueRealtimeEvent(final String pTargetState)
	{
		return com.on.ebx.factory.core.FactoryPaths.DistributionTarget.STATE_ARMED.equals(pTargetState);
	}
}
