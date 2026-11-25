package com.on.ebx.factory.intake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Automatic intake lot (docs/AUTOMATIC-INTAKE.md): the pure decision "start a chain now, or wait, and until when" the engine takes at each tick for one ON project.
 * No EBX class, no clock of its own (the caller passes "now"), unit-tested.
 * <p>
 * Rules, per source with something waiting ({@link IntakePending#isPending}) or when the person pressed "Process now":
 * <ul>
 * <li>manual ("Process now"): due at once, reason {@code MANUAL};</li>
 * <li>the application said its batch is complete (REST call, or the Factory's own read ended): due at once, reason {@code BATCH_COMPLETE};</li>
 * <li>otherwise due at the EARLIER of "last row + quiet period" (reason {@code QUIET}, or {@code SWEEP} when the knowledge came from the safety sweep) and
 * "waiting since + longest wait" (reason {@code MAX_WAIT}).</li>
 * </ul>
 * Only when a source is due is the number of NEW rows asked ({@link RowCounter}: it costs a predicate count on the landing table): zero rows means there is nothing
 * to do (the pending state is to be cleared); fewer rows than the source's minimum keeps waiting for the longest wait ({@code QUIET}/{@code SWEEP} only: the longest
 * wait, a manual request and a batch-complete call launch with at least one row). Nothing ever starts when the intake is not ON or a chain already runs
 * (one chain at a time per project): the views are still computed so the status can show what is waiting.
 */
public final class IntakeDecision
{
	public static final String STATE_ON = "ON";

	public static final String REASON_QUIET = "QUIET";
	public static final String REASON_MAX_WAIT = "MAX_WAIT";
	public static final String REASON_BATCH_COMPLETE = "BATCH_COMPLETE";
	public static final String REASON_MANUAL = "MANUAL";
	public static final String REASON_SWEEP = "SWEEP";

	public enum Action
	{
		/** Nothing waits, the intake is not ON, or a chain runs. */
		NOTHING,
		/** Something waits but is not due yet (or too small): look again at {@link Result#nextDueAtMs}. */
		WAIT,
		/** Start one chain now. */
		LAUNCH
	}

	/** What the UI shows per source; {@code RUNNING} is added by the caller while a chain runs. */
	public enum SourceState
	{
		IDLE, RECEIVING, DUE
	}

	/** Counts the rows at {@code _status} NEW (or empty) of one source's landing tables: only called for a source that is due. */
	public interface RowCounter
	{
		long count(String pSourceSystem);
	}

	public static final class SourceInput
	{
		public final String sourceSystem;
		public final IntakePending pending;
		public final IntakeSettings settings;

		public SourceInput(final String pSourceSystem, final IntakePending pPending, final IntakeSettings pSettings)
		{
			this.sourceSystem = pSourceSystem;
			this.pending = pPending;
			this.settings = pSettings;
		}
	}

	public static final class SourceView
	{
		public final String sourceSystem;
		public final SourceState state;
		/** When the source becomes due (epoch ms): null when nothing waits. */
		public final Long dueAtMs;
		/** Waiting since + longest wait (epoch ms): the instant a chain starts at the latest; null when nothing waits. */
		public final Long latestAtMs;
		/** The reason the source starts (or would start) a chain; null when nothing waits. */
		public final String reason;
		/** The number of rows counted when the source was due; null when it was not counted. */
		public final Long rowsWaiting;

		SourceView(final String pSourceSystem, final SourceState pState, final Long pDueAtMs, final Long pLatestAtMs, final String pReason, final Long pRowsWaiting)
		{
			this.sourceSystem = pSourceSystem;
			this.state = pState;
			this.dueAtMs = pDueAtMs;
			this.latestAtMs = pLatestAtMs;
			this.reason = pReason;
			this.rowsWaiting = pRowsWaiting;
		}
	}

	public static final class Result
	{
		public final Action action;
		/** {@link Action#LAUNCH} only: the chain's reason. */
		public final String reason;
		/** {@link Action#LAUNCH} only: the rows counted over every due source. */
		public final long rowsWaiting;
		/** {@link Action#LAUNCH} only: the sources that were due with rows waiting, in input order. */
		public final List<String> launchSources;
		/** Sources that were due but had no row at NEW: their pending state is to be cleared. */
		public final List<String> clearedSources;
		/** {@link Action#WAIT}: the earliest instant something becomes due (epoch ms). */
		public final Long nextDueAtMs;
		public final List<SourceView> views;

		Result(final Action pAction, final String pReason, final long pRowsWaiting, final List<String> pLaunchSources, final List<String> pClearedSources,
			final Long pNextDueAtMs, final List<SourceView> pViews)
		{
			this.action = pAction;
			this.reason = pReason;
			this.rowsWaiting = pRowsWaiting;
			this.launchSources = Collections.unmodifiableList(pLaunchSources);
			this.clearedSources = Collections.unmodifiableList(pClearedSources);
			this.nextDueAtMs = pNextDueAtMs;
			this.views = Collections.unmodifiableList(pViews);
		}
	}

	private IntakeDecision()
	{
	}

	/**
	 * @param pNowMs the current time (epoch ms)
	 * @param pIntakeState {@code Project.intakeState}: only {@code ON} can launch
	 * @param pChainRunning a chain of this project is running
	 * @param pManual "Process now" was requested and not served yet
	 * @param pCounter asked only for a source that is due
	 */
	public static Result decide(final long pNowMs, final String pIntakeState, final boolean pChainRunning, final boolean pManual, final List<SourceInput> pSources,
		final RowCounter pCounter)
	{
		final boolean mayLaunch = IntakeDecision.STATE_ON.equals(pIntakeState) && !pChainRunning;
		final List<SourceView> views = new ArrayList<SourceView>();
		final List<String> cleared = new ArrayList<String>();
		final Map<String, Long> counts = new LinkedHashMap<String, Long>();
		final List<String> launch = new ArrayList<String>();
		final Map<String, String> launchReasons = new LinkedHashMap<String, String>();
		Long nextDue = null;
		long rows = 0L;

		for (final SourceInput source : pSources)
		{
			final IntakePending pending = source.pending;
			final boolean active = pending.isPending() || pManual;
			if (!active)
			{
				views.add(new SourceView(source.sourceSystem, SourceState.IDLE, null, null, null, null));
				continue;
			}
			final long since = pending.pendingSinceMs == null ? pNowMs : pending.pendingSinceMs.longValue();
			final long last = pending.lastRowMs == null ? since : pending.lastRowMs.longValue();
			final long quietDue = last + source.settings.quietSeconds * 1000L;
			final long maxDue = since + source.settings.maxWaitMinutes * 60000L;
			long due;
			String reason;
			if (pManual)
			{
				due = pNowMs;
				reason = IntakeDecision.REASON_MANUAL;
			}
			else if (pending.batchComplete)
			{
				due = pNowMs;
				reason = IntakeDecision.REASON_BATCH_COMPLETE;
			}
			else if (quietDue <= maxDue)
			{
				due = quietDue;
				reason = pending.origin == IntakePending.Origin.SWEEP ? IntakeDecision.REASON_SWEEP : IntakeDecision.REASON_QUIET;
			}
			else
			{
				due = maxDue;
				reason = IntakeDecision.REASON_MAX_WAIT;
			}
			final Long latest = pending.pendingSinceMs == null ? null : Long.valueOf(maxDue);
			if (pNowMs < due || !mayLaunch)
			{
				views.add(new SourceView(source.sourceSystem, pNowMs >= due ? SourceState.DUE : SourceState.RECEIVING, Long.valueOf(due), latest, reason, null));
				if (mayLaunch)
				{
					nextDue = IntakeDecision.min(nextDue, due);
				}
				continue;
			}
			final long count = pCounter.count(source.sourceSystem);
			counts.put(source.sourceSystem, Long.valueOf(count));
			if (count <= 0L)
			{
				cleared.add(source.sourceSystem);
				views.add(new SourceView(source.sourceSystem, SourceState.IDLE, null, null, null, Long.valueOf(0L)));
				continue;
			}
			final boolean soft = IntakeDecision.REASON_QUIET.equals(reason) || IntakeDecision.REASON_SWEEP.equals(reason);
			if (soft && count < source.settings.minRows)
			{
				if (pNowMs >= maxDue)
				{
					reason = IntakeDecision.REASON_MAX_WAIT;
				}
				else
				{
					views.add(new SourceView(source.sourceSystem, SourceState.RECEIVING, Long.valueOf(maxDue), latest, reason, Long.valueOf(count)));
					nextDue = IntakeDecision.min(nextDue, maxDue);
					continue;
				}
			}
			launch.add(source.sourceSystem);
			launchReasons.put(source.sourceSystem, reason);
			rows += count;
			views.add(new SourceView(source.sourceSystem, SourceState.DUE, Long.valueOf(due), latest, reason, Long.valueOf(count)));
		}

		if (!launch.isEmpty())
		{
			return new Result(Action.LAUNCH, IntakeDecision.strongest(launchReasons.values()), rows, launch, cleared, null, views);
		}
		if (nextDue != null)
		{
			return new Result(Action.WAIT, null, 0L, launch, cleared, nextDue, views);
		}
		return new Result(Action.NOTHING, null, 0L, launch, cleared, null, views);
	}

	private static Long min(final Long pCurrent, final long pCandidate)
	{
		return pCurrent == null || pCandidate < pCurrent.longValue() ? Long.valueOf(pCandidate) : pCurrent;
	}

	/** MANUAL beats BATCH_COMPLETE beats MAX_WAIT beats SWEEP beats QUIET: the reason a person would give for the chain. */
	static String strongest(final Iterable<String> pReasons)
	{
		final String[] order = { IntakeDecision.REASON_MANUAL, IntakeDecision.REASON_BATCH_COMPLETE, IntakeDecision.REASON_MAX_WAIT, IntakeDecision.REASON_SWEEP,
			IntakeDecision.REASON_QUIET };
		for (final String wanted : order)
		{
			for (final String reason : pReasons)
			{
				if (wanted.equals(reason))
				{
					return wanted;
				}
			}
		}
		return IntakeDecision.REASON_QUIET;
	}
}
