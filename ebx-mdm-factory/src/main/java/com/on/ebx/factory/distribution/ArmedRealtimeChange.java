package com.on.ebx.factory.distribution;

import com.on.ebx.factory.core.FactoryPaths;

/**
 * Volume-operations lot 8b (2026-09-28, task 1): pure decision of whether a single {@code DistributionTarget}
 * write could change whether its project has at least one ARMED REALTIME target - the fact {@code
 * ebx.DistributionControlRow}'s control row exists to answer cheaply for {@code distribution.DistributionTriggerBody}'s
 * generated trigger (see that class' own Javadoc for why the Java-side {@link ArmedTargetCache} of lot 8a cannot
 * be read from the trigger, and why a control ROW - read by the script with a plain primary-key lookup, no SQL -
 * is the alternative).
 * <p>
 * A target only ever counts toward "at least one ARMED REALTIME target" when its {@code mode} is {@code
 * REALTIME} AND its {@code state} is {@code ARMED} (the exact predicate the trigger's own SQL fallback and
 * {@code DistributionArmingGate#mayQueueRealtimeEvent} already use). Most writes to a target - renaming its
 * connector, changing scope, disarming an already-{@code DISABLED} target - never cross that boundary in either
 * direction, so {@link #directionOf} answers {@link Direction#NONE} for them and the caller skips the control-row
 * write (and its own live-count query) entirely.
 * <p>
 * <b>Why the two non-{@code NONE} directions are handled asymmetrically by the caller (documented here since
 * this class is what the caller's asymmetry is FOR):</b> the control row and the target's own {@code state} field
 * live in different dataspaces and can never be written in one transaction (same structural fact as the outbox
 * itself - {@code distribution.DistributionTriggerBody}'s own Javadoc). Whichever write happens first is briefly
 * out of step with the other:
 * <ul>
 * <li>{@link Direction#ARM} (a target that did NOT count starts counting): if the control row were updated
 * AFTER the target's own state commits, a record saved in that gap would read a STALE, too-low count and the
 * trigger would wrongly skip queuing a REALTIME event for a target that is, by then, genuinely armed - a real
 * lost event. So the control row must be bumped BEFORE the state change commits ({@code
 * DistributionControlRow#beforeStateChange}) - the live count queried at that point does not yet include this
 * target (its OLD state does not count), so the caller simply adds one.</li>
 * <li>{@link Direction#DISARM} (a target that counted stops counting): the opposite order is safe here - a
 * record saved in the gap before the control row is refreshed still finds a too-HIGH count and the trigger runs
 * its harmless SQL fallback, which then correctly finds no ARMED target (or one, if that target was not the only
 * one) and queues nothing wrong. So the control row is refreshed AFTER the state change commits ({@code
 * DistributionControlRow#afterStateChange}), by a plain fresh recount - no arithmetic needed, since the live
 * query already excludes the now-disarmed target.</li>
 * </ul>
 * Getting this backwards for either direction would trade a harmless extra query (over-counting) for a silently
 * dropped real-time event (under-counting) - the one outcome this whole lot exists to avoid.
 */
public final class ArmedRealtimeChange
{
	private ArmedRealtimeChange()
	{
	}

	public enum Direction
	{
		/** Neither the old nor the new (mode, state) pair counts as "ARMED REALTIME" - nothing to do. */
		NONE,
		/** The old pair did not count, the new one does - the control row must be bumped BEFORE the state commits. */
		ARM,
		/** The old pair counted, the new one does not - the control row must be refreshed AFTER the state commits. */
		DISARM
	}

	/** Whether {@code pMode}/{@code pState} together count toward a project's "at least one ARMED REALTIME target" fact. */
	public static boolean counts(final String pMode, final String pState)
	{
		return FactoryPaths.DistributionTarget.MODE_REALTIME.equals(pMode) && FactoryPaths.DistributionTarget.STATE_ARMED.equals(pState);
	}

	/** The direction of a target's (mode, state) transition, as far as the control row is concerned - see this class' own Javadoc for what each non-{@link Direction#NONE} value means for the caller. */
	public static Direction directionOf(final String pOldMode, final String pOldState, final String pNewMode, final String pNewState)
	{
		final boolean before = ArmedRealtimeChange.counts(pOldMode, pOldState);
		final boolean after = ArmedRealtimeChange.counts(pNewMode, pNewState);
		if (before == after)
		{
			return Direction.NONE;
		}
		return after ? Direction.ARM : Direction.DISARM;
	}
}
