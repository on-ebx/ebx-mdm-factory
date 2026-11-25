package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.List;

/**
 * Distribution lot 1 (engine, SPEC-distribution.md "Real time"): "several events on one record in a burst
 * -&gt; one send". Pure - no EBX class. {@code ebx.DistributionDispatcher} groups the PENDING rows of one
 * drain pass by {@code (target, tablePath, recordKey)} and hands each group here before sending anything:
 * a record modified three times before the dispatcher wakes up must reach the target once, with the net
 * effect of the three changes, not three separate calls.
 * <p>
 * The netting rule (a small state machine over the ordered sequence of changes):
 * <ul>
 * <li>{@code CREATE} then any number of {@code MODIFY} stays {@code CREATE} - the target never saw the
 * record, so it still needs the full create, not a modify of something that (from the target's point of
 * view) does not exist.</li>
 * <li>{@code CREATE} then {@code DELETE} nets to {@code NONE} - created and deleted within the same
 * unsent burst, the target never needs to hear about it at all.</li>
 * <li>{@code MODIFY} (record already known to be live) then {@code MODIFY} stays {@code MODIFY}.</li>
 * <li>{@code MODIFY} then {@code DELETE} nets to {@code DELETE}.</li>
 * <li>{@code RETIRE} is terminal (a merge or an unmerge-delete, CLAUDE.md sec 8): once it appears, it wins
 * over everything that came before it in the same burst.</li>
 * <li>{@code DELETE} followed by another change (a key reused after a hard delete - not expected in normal
 * operation, since a real EBX record delete removes the row a later create would reuse) restarts the state
 * machine at that later change, so the net effect is simply whatever the last-standing change series says.</li>
 * </ul>
 * Every input event id is classified: the one whose own {@code change} equals the net {@link Coalesced#effectiveChange}
 * and sorts last is the {@link Coalesced#survivingId} actually sent; every other id (including all of them
 * when the net effect is {@code NONE}) is folded away and should be marked {@code SKIPPED}, never resent.
 * <p>
 * <b>Volume-operations lot 8b (2026-09-28, task 2): why {@code DistributionOutbox.Event}'s primary key stays
 * the auto-increment {@code id}, not a natural key of {@code (target, tablePath, recordKey)}.</b> This class
 * is exactly the evidence against it: {@link #coalesce} exists BECAUSE several PENDING rows sharing that exact
 * triple legitimately coexist within one drain pass (a record saved three times before the dispatcher wakes
 * up produces three rows, grouped and netted here), and {@link #idsSupersededBySend} exists because they also
 * coexist ACROSS passes (a row still in retry backoff alongside a fresh event for the same record - {@code
 * ebx.DistributionDispatcher}'s own round-5 fix). A primary key can only ever hold one row per key value, so
 * either shape would be structurally impossible under a natural key: an insert would have to become an upsert,
 * silently collapsing a {@code CREATE} row into a later {@code MODIFY} row's own {@code change} value (or vice
 * versa) - exactly the netting this class does on PURPOSE, but done wrong (by whichever event happens to write
 * last, not by the ordered state machine above) and with the ordering information ({@code sequence}, read off
 * the auto-increment id) destroyed in the same stroke. See {@code docs/DISTRIBUTION.md} for the full
 * evaluation and {@code ebx.DistributionDispatcher}'s own Javadoc for why the resulting per-record read stays
 * bounded anyway without a secondary index.
 */
public final class EventCoalescer
{
	private EventCoalescer()
	{
	}

	public static final String CREATE = "CREATE";
	public static final String MODIFY = "MODIFY";
	public static final String DELETE = "DELETE";
	public static final String RETIRE = "RETIRE";
	/** Not one of {@code DistributionEvent.change}'s own enumeration values: means "nothing to send", every input id is skipped. */
	public static final String NONE = "NONE";

	/**
	 * Round 6 (2026-09-26, task brief - DistribTest live incident, rows 6/7): the representative event's own
	 * eventual fate, as {@code ebx.DistributionDispatcher} learns it after actually attempting the send - what
	 * {@link #supersededMessage} needs to decide the history line (if any) for every id folded into that
	 * representative, whether by {@link #coalesce}'s same-pass netting or by {@link #idsSupersededBySend}'s
	 * cross-pass check.
	 */
	public static final String OUTCOME_SENT = "SENT";
	public static final String OUTCOME_SKIPPED = "SKIPPED";
	public static final String OUTCOME_FAILED_EXHAUSTED = "FAILED_EXHAUSTED";
	public static final String OUTCOME_FAILED_RETRY = "FAILED_RETRY";

	/** {@link #coalesce} netted a whole burst to {@link #NONE} (e.g. {@code CREATE}+{@code DELETE}): nothing was ever sent, so there is no representative id to name - every input id gets this message instead of "superseded by event &lt;id&gt;". */
	public static final String NETTED_TO_NONE_MESSAGE = "netted to no effect in the same burst (created and deleted before it was ever sent)";

	/** One PENDING {@code DistributionEvent} row, as much as this class needs to know about it. */
	public static final class Event
	{
		public final String id;
		public final String change;
		/** Ordering within the burst - the row's own creation order (e.g. its auto-increment id as a long, or a monotonic counter); ties broken by input order. */
		public final long sequence;

		public Event(final String pId, final String pChange, final long pSequence)
		{
			this.id = pId;
			this.change = pChange;
			this.sequence = pSequence;
		}
	}

	/** The result of netting one burst: what to actually send, and which input ids to mark {@code SKIPPED}/{@code SENT}. */
	public static final class Coalesced
	{
		/** {@link #CREATE}/{@link #MODIFY}/{@link #DELETE}/{@link #RETIRE}, or {@link #NONE} when nothing should be sent. */
		public final String effectiveChange;
		/** Every input id, in the order given. */
		public final List<String> allIds;
		/** The id of the event actually sent; null when {@link #effectiveChange} is {@link #NONE} (every id is skipped). */
		public final String survivingId;

		Coalesced(final String pEffectiveChange, final List<String> pAllIds, final String pSurvivingId)
		{
			this.effectiveChange = pEffectiveChange;
			this.allIds = pAllIds;
			this.survivingId = pSurvivingId;
		}

		/** Every id except {@link #survivingId} - to be marked {@code SKIPPED} (or every id, when {@link #survivingId} is null). */
		public List<String> idsToSkip()
		{
			final List<String> out = new ArrayList<String>();
			for (final String id : this.allIds)
			{
				if (!id.equals(this.survivingId))
				{
					out.add(id);
				}
			}
			return out;
		}
	}

	/** {@code pEvents} must all be for the same {@code (target, tablePath, recordKey)}; need not be pre-sorted. Throws {@link IllegalArgumentException} on an empty or null list - there is nothing to coalesce. */
	public static Coalesced coalesce(final List<Event> pEvents)
	{
		if (pEvents == null || pEvents.isEmpty())
		{
			throw new IllegalArgumentException("EventCoalescer.coalesce: at least one event is required");
		}
		final List<Event> sorted = new ArrayList<Event>(pEvents);
		java.util.Collections.sort(sorted, new java.util.Comparator<Event>()
		{
			@Override
			public int compare(final Event pA, final Event pB)
			{
				return Long.compare(pA.sequence, pB.sequence);
			}
		});
		String state = null;
		Event lastOfState = null;
		for (final Event event : sorted)
		{
			state = EventCoalescer.next(state, event.change);
			if (EventCoalescer.NONE.equals(state))
			{
				lastOfState = null;
			}
			else
			{
				lastOfState = event;
			}
		}
		final List<String> allIds = new ArrayList<String>();
		for (final Event event : sorted)
		{
			allIds.add(event.id);
		}
		if (EventCoalescer.NONE.equals(state) || lastOfState == null)
		{
			return new Coalesced(EventCoalescer.NONE, allIds, null);
		}
		return new Coalesced(state, allIds, lastOfState.id);
	}

	/**
	 * Round 5 (2026-09-26, task brief item 2, DistribTest live incident): {@link #coalesce} only nets events
	 * gathered into the SAME drain-pass group - it never sees a row the dispatcher excluded from that pass
	 * because it is still in retry backoff ({@code nextAttemptAt} in the future). Live symptom: outbox row 4
	 * (attempt 7, backed off ~30 minutes) and row 5 (a fresh event for the same record) were both PENDING; row
	 * 5 was sent on its own (it was due, row 4 was not, so {@code DistributionDispatcher#drainTarget}'s own
	 * per-row {@code nextAttemptAt} filter already keeps a fresh event from waiting on an older row's backoff -
	 * nothing to fix there), but row 4 was left to later wake up and re-send the same current state Connectors
	 * had already just been given, a pure duplicate.
	 * <p>
	 * This method is the other half: once a send of {@code pSentChange} (the group's own coalesced effective
	 * change) has ACTUALLY gone out, decide which OTHER still-pending events for the very same
	 * {@code (target, tablePath, recordKey)} - including ones excluded from this pass' group, like row 4 above
	 * - are now stale and may be closed without ever being sent themselves. The rule mirrors {@link #coalesce}'s
	 * own CREATE/MODIFY netting, generalised across passes: a {@code CREATE}/{@code MODIFY} send makes every
	 * OTHER pending {@code CREATE}/{@code MODIFY} event for the same key at or before it (by {@code sequence})
	 * redundant, since the send already reflects the record's current committed state (the dispatcher always
	 * re-reads it - {@code DistributionDispatcher#masterRecordExists}/{@code #sendRecord} never replays the
	 * outbox row's own stale payload). A {@code DELETE} or {@code RETIRE} is NEVER superseded this way, in
	 * either direction: a pending {@code DELETE} already gets resolved correctly on its own next attempt (the
	 * existing {@code masterRecordExists} check in {@code DistributionDispatcher#drainGroup} skips it once the
	 * record turns out to still exist), and a pending {@code RETIRE} must always run through {@code
	 * RetireRules} itself - closing either one here on nothing more than "a CREATE/MODIFY was sent" would be
	 * wrong. Likewise a {@code CREATE}/{@code MODIFY} sent AFTER a pending {@code DELETE}/{@code RETIRE} never
	 * closes it. And a send itself that is a {@code DELETE} or {@code RETIRE} closes nothing here at all -
	 * those have their own dedicated handling ({@code RetireRules}, the existence check) and this method
	 * returns an empty list immediately for them.
	 *
	 * @param pSentChange the change that was actually sent ({@link #coalesce}'s own {@code effectiveChange})
	 * @param pSentSequence the sent event's own sequence (its outbox row's ordering, e.g. auto-increment id)
	 * @param pOtherPendingEvents every OTHER still-pending event for the same {@code (target, tablePath,
	 * recordKey)} - the caller queries this independently of any {@code nextAttemptAt} filter, since a row in
	 * backoff (like row 4 above) is exactly the case this method exists to catch
	 * @return the ids to close as superseded (never sent, marked {@code SKIPPED}); empty when {@code
	 * pSentChange} is {@code DELETE}/{@code RETIRE} or nothing qualifies
	 */
	public static List<String> idsSupersededBySend(final String pSentChange, final long pSentSequence, final List<Event> pOtherPendingEvents)
	{
		final List<String> out = new ArrayList<String>();
		if (!EventCoalescer.CREATE.equals(pSentChange) && !EventCoalescer.MODIFY.equals(pSentChange))
		{
			return out;
		}
		if (pOtherPendingEvents == null)
		{
			return out;
		}
		for (final Event event : pOtherPendingEvents)
		{
			final boolean createOrModify = EventCoalescer.CREATE.equals(event.change) || EventCoalescer.MODIFY.equals(event.change);
			if (createOrModify && event.sequence <= pSentSequence)
			{
				out.add(event.id);
			}
		}
		return out;
	}

	/**
	 * Round 6 (2026-09-26, task brief): the ONE rule that decides the SKIPPED history message (if any) for an
	 * id folded away into {@code pRepresentativeId} - the id of the event actually attempted, whether by
	 * {@link #coalesce}'s own same-pass netting ({@code idsToSkip()}) or by {@link #idsSupersededBySend}'s
	 * cross-pass check. Both call sites in {@code ebx.DistributionDispatcher} go through this single method
	 * (task brief: "prefer one code path for both cases"), so the wording and the retry-safety rule below can
	 * never drift apart between them.
	 * <p>
	 * This is also the fix for the round-6 bug itself: the previous code deleted a folded id's outbox row
	 * outright, with no history row at all, as soon as the coalescing DECISION was made - before the
	 * representative had actually been attempted. A folded id must only be closed once the representative's
	 * own send has a known, terminal outcome:
	 * <ul>
	 * <li>{@link #OUTCOME_SENT} or {@link #OUTCOME_SKIPPED} - the representative's fate is settled and it will
	 * never be retried under this id, so every folded id is safely superseded by it:
	 * {@code "superseded by event <id>"} (the exact wording {@code closeSupersededRows} already used before
	 * this round, kept unchanged for anything already reading that text).</li>
	 * <li>{@link #OUTCOME_FAILED_EXHAUSTED} - the representative failed for good (past the retry budget); the
	 * folded ids share that fate rather than silently reviving on their own, so they are also closed, with a
	 * message that says so: {@code "superseded by event <id> (failed)"}.</li>
	 * <li>{@link #OUTCOME_FAILED_RETRY} - the representative failed but is still within its retry budget, i.e.
	 * NOT terminal yet: returns {@code null}, meaning "do not close these yet". The simplest correct rule here
	 * is to leave every folded id exactly as it was - still {@code PENDING}, untouched - so the next drain pass
	 * re-coalesces the same group (the folded ids are still there, nothing was lost) and re-evaluates once the
	 * representative's outcome is actually decided. The alternative (closing them now, on a "failed" outcome
	 * that might still turn into a later success) would risk permanently losing an id that turns out to have
	 * been the only trace of a change, for no benefit - the representative's own row is what carries the retry
	 * forward, not any of the folded ones.</li>
	 * </ul>
	 *
	 * @return the message to write on each folded id's SKIPPED history row, or {@code null} when they must be
	 * left PENDING for now (the representative has not reached a terminal outcome)
	 */
	public static String supersededMessage(final String pRepresentativeId, final String pOutcome)
	{
		if (EventCoalescer.OUTCOME_FAILED_RETRY.equals(pOutcome))
		{
			return null;
		}
		if (EventCoalescer.OUTCOME_FAILED_EXHAUSTED.equals(pOutcome))
		{
			return "superseded by event " + pRepresentativeId + " (failed)";
		}
		// OUTCOME_SENT, OUTCOME_SKIPPED (and defensively, anything else): the representative is settled.
		return "superseded by event " + pRepresentativeId;
	}

	/** One transition of the netting state machine described in this class' Javadoc. Package-private and pure so the eight cases are each directly unit-tested. */
	static String next(final String pState, final String pChange)
	{
		if (EventCoalescer.RETIRE.equals(pChange))
		{
			return EventCoalescer.RETIRE;
		}
		if (pState == null || EventCoalescer.NONE.equals(pState) || EventCoalescer.DELETE.equals(pState) || EventCoalescer.RETIRE.equals(pState))
		{
			// no state yet, or the previous state is terminal for this purpose: the new change starts a fresh series
			return pChange;
		}
		if (EventCoalescer.CREATE.equals(pState))
		{
			if (EventCoalescer.MODIFY.equals(pChange))
			{
				return EventCoalescer.CREATE;
			}
			if (EventCoalescer.DELETE.equals(pChange))
			{
				return EventCoalescer.NONE;
			}
			return pChange;
		}
		// pState is MODIFY
		return pChange;
	}
}
