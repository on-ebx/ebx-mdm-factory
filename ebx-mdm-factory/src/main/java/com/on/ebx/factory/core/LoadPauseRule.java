package com.on.ebx.factory.core;

import java.util.Set;

/**
 * What the module does, at its start, with a pause the factory applied during a load ({@code ebx.LoadPauseLedger} keeps one {@code LoadPause} row per pause).
 * Pure, so every branch is unit-tested without an instance.
 * <p>
 * The row proves that the FACTORY paused something, never that a person did: a target or a table somebody paused by hand never gets a row, so the recovery
 * can only ever lift what the factory itself paused. A row whose owner (the load that wrote it) is still alive is left alone; a row whose owner is gone
 * is an orphan, and what happens to it depends on what the pause looks like NOW: still paused = lift it, no longer paused (a person or a later run already
 * changed it) = only drop the row, never touch the thing, unreadable (the add-on or Connectors is not up yet) = keep the row and try again.
 */
public final class LoadPauseRule
{
	private LoadPauseRule()
	{
	}

	/** What the thing the row names looks like at this moment. */
	public enum Observation
	{
		/** Still paused: the pause the row recorded is still in force. */
		PAUSED,
		/** Exists and is not paused: somebody (or a later run) already changed it. */
		NOT_PAUSED,
		/** Does not exist any more (target deleted, project deleted). */
		GONE,
		/** Could not be read now. */
		UNKNOWN
	}

	public enum Action
	{
		/** Lift the pause, then delete the row. */
		RESUME,
		/** Delete the row only: there is nothing of ours left to undo. */
		DROP,
		/** Leave the row: its owner is alive, or the state could not be read yet. */
		KEEP
	}

	/** An owner token that no live load of this server holds belongs to a load that is gone (a stop, a crash, a restart). */
	public static boolean isOrphan(final String pOwnerToken, final Set<String> pLiveTokens)
	{
		return pOwnerToken == null || pOwnerToken.isEmpty() || pLiveTokens == null || !pLiveTokens.contains(pOwnerToken);
	}

	public static Action decide(final boolean pOwnerAlive, final Observation pObservation)
	{
		if (pOwnerAlive)
		{
			return Action.KEEP;
		}
		switch (pObservation == null ? Observation.UNKNOWN : pObservation)
		{
			case PAUSED:
				return Action.RESUME;
			case NOT_PAUSED:
			case GONE:
				return Action.DROP;
			default:
				return Action.KEEP;
		}
	}

	/** {@code pState} is the {@code DistributionTarget.state} read now, null when the row does not exist. */
	public static Observation observeTarget(final boolean pExists, final String pState)
	{
		if (!pExists)
		{
			return Observation.GONE;
		}
		return FactoryPaths.DistributionTarget.STATE_PAUSED.equals(pState) ? Observation.PAUSED : Observation.NOT_PAUSED;
	}

	/** {@code pAnswerRead} false when {@code get_matching_activation_status} could not be read; {@code pPausedField} is its {@code paused} field, null when absent. */
	public static Observation observeMatching(final boolean pAnswerRead, final String pState, final Boolean pPausedField)
	{
		if (!pAnswerRead)
		{
			return Observation.UNKNOWN;
		}
		if (Boolean.TRUE.equals(pPausedField) || "PAUSED".equalsIgnoreCase(pState == null ? "" : pState.trim()))
		{
			return Observation.PAUSED;
		}
		return Observation.NOT_PAUSED;
	}
}
