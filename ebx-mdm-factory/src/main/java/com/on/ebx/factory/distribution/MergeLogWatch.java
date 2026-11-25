package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPEC-retire.md "Merge-log watch": the PURE decision at the heart of "retire notices from the merge log" -
 * no EBX class, unit-tested directly ({@code MergeLogWatchTest}). The measured problem this replaces
 * (SPEC-retire.md's own header): {@code DistributionTriggerBody}'s {@code afterModify} comparison of
 * {@code Linked_Field_State} never fires for a merged/unmerged record at all - a merge only touches the
 * add-on's metadataset, never the master row's own save, so no trigger of any kind runs for 347/527 in the
 * measured incident. This class instead watches the metadataset's OWN merge log directly (one
 * {@code MergingProcess} row per merge/unmerge, {@code RecordMetadata} rows carrying each record's state)
 * from the dispatcher side, where a background pass can read across records freely - see {@code
 * ebx.MergeLogWatcher} for the EBX-facing counterpart that feeds this class from the metadataset tables
 * (CLAUDE.md sec 8, {@code ebx.MameMetadatasetReads}' own direct-read pattern) and turns its {@link OutEvent}s
 * into {@code DistributionOutbox.Event} rows.
 * <p>
 * <b>Two situations, mirroring {@link RetireRules}' own two MAME cases</b>:
 * <ul>
 * <li>A NEW merge (a {@code MergingProcess} row whose id is greater than the watermark's own {@code
 * lastMergeId}, and not itself flagged {@code undone}): one {@link OutEvent} per member of that merge whose
 * {@code state} is {@link RetireRules#STATE_MERGED} - {@link RetireRules#evaluate} decides the survivor
 * exactly as it already does for a single record (reused, not reimplemented).</li>
 * <li>An UNDONE merge (a merge id already known from an earlier pass - not itself new in this same pass -
 * whose {@code undone} flag now reads true where the watermark had not yet seen it): one {@link OutEvent}
 * retiring the merge's own auto-created golden (found by {@code autoCreated=true} among the merge's members,
 * {@link RetireRules#evaluate} with {@link RetireRules#STATE_DELETED}/{@code autoCreated=true} - no survivor),
 * plus, when {@code pRetireUndoneMerges} is true, one {@code CREATE} {@link OutEvent} per OTHER member of
 * that merge whose state has gone back to {@link RetireRules#STATE_GOLDEN} - a member still {@link
 * RetireRules#STATE_PENDING_APPROVAL} gets nothing yet (SPEC-retire.md: "document the gap" - the next
 * matching run or a person's decision eventually makes it golden, and a later pass or the ordinary trigger
 * picks that up).</li>
 * </ul>
 * <b>A merge id that is BOTH new in this pass AND already flagged {@code undone}</b> (the merge and its
 * undo both happened between two watch passes, so neither side was ever announced) nets to nothing - the
 * members were never told they were retired, so there is nothing to reinstate either; this mirrors {@code
 * EventCoalescer}'s own {@code CREATE+DELETE -&gt; NONE} netting for the very same reason. Only the watermark
 * advances.
 * <p>
 * <b>{@code pRetireMode=NONE}</b>: the watch still classifies every row and advances the watermark/undone-set
 * exactly as normal - it simply never adds anything to the returned {@link Decision#events} list. {@code
 * TOMBSTONE} is NOT special-cased here at all (SPEC-retire.md: "the field and the refusal path only" - no
 * Kafka connector support exists yet): a {@code TOMBSTONE} target still gets ordinary events from this class,
 * exactly like {@code NOTICE}; deciding what a {@code TOMBSTONE} target's dispatch actually sends (today:
 * NOTICE, with a message saying so) is {@code ebx.DistributionDispatcher}'s job at send time, not this
 * class's.
 * <p>
 * <b>Idempotence</b>: calling {@link #decide} twice with the SAME merge-log rows and the watermark this
 * method itself just returned produces an empty {@link Decision#events} the second time - every row is
 * either not new any more or its {@code undone} flag is already in the returned watermark's own set.
 */
public final class MergeLogWatch
{
	public static final String CHANGE_RETIRE = EventCoalescer.RETIRE;
	public static final String CHANGE_CREATE = EventCoalescer.CREATE;
	public static final String REASON_MERGE = "MERGE";
	public static final String REASON_UNMERGE = "UNMERGE";

	private MergeLogWatch()
	{
	}

	/** One table's watermark: the highest {@code MergingProcess.id} already accounted for, and every merge id already known to be {@code undone} (so a later pass never re-emits its UNMERGE events). Immutable. */
	public static final class Watermark
	{
		public final long lastMergeId;
		public final Set<Long> undoneMergeIds;

		public Watermark(final long pLastMergeId, final Set<Long> pUndoneMergeIds)
		{
			this.lastMergeId = pLastMergeId;
			this.undoneMergeIds = pUndoneMergeIds == null ? Collections.<Long>emptySet() : Collections.unmodifiableSet(new LinkedHashSet<Long>(pUndoneMergeIds));
		}

		/** No watermark stored yet for this table - {@link #decide} treats this specially (initialise, emit nothing). */
		public static Watermark none()
		{
			return null;
		}
	}

	/** One row of the add-on's {@code MergingProcess} table (CLAUDE.md sec 8: {@code id}, {@code isUnmerged}). */
	public static final class MergeRow
	{
		public final long mergeId;
		public final boolean undone;

		public MergeRow(final long pMergeId, final boolean pUndone)
		{
			this.mergeId = pMergeId;
			this.undone = pUndone;
		}
	}

	/** One {@code RecordMetadata} row tied to a merge by {@code mergingProcessId} - either an ordinary member (state MERGED/GOLDEN/PENDING_APPROVAL) or the merge's own auto-created golden (state DELETED once unmerged, {@code autoCreated=true}). */
	public static final class Member
	{
		public final String recordKey;
		/** One of {@link RetireRules}'s own state constants. */
		public final String state;
		/** The record's current golden key ({@code RecordMetadata.goldenId}) - only meaningful when {@code state} is {@link RetireRules#STATE_MERGED}. */
		public final String goldenKey;
		public final boolean autoCreated;

		public Member(final String pRecordKey, final String pState, final String pGoldenKey, final boolean pAutoCreated)
		{
			this.recordKey = pRecordKey;
			this.state = pState;
			this.goldenKey = pGoldenKey;
			this.autoCreated = pAutoCreated;
		}
	}

	/** One event this watch decided to emit - {@code ebx.MergeLogWatcher} turns each into a {@code DistributionOutbox.Event} row. */
	public static final class OutEvent
	{
		/** {@link #CHANGE_RETIRE} or {@link #CHANGE_CREATE}. */
		public final String change;
		public final String recordKey;
		/** {@link #CHANGE_RETIRE} only: the golden this record was merged into; null when there is none (unmerge-deleted golden, or a merged member with no known golden key). */
		public final String survivorKey;
		/** {@link #REASON_MERGE} or {@link #REASON_UNMERGE}. */
		public final String reason;
		public final long mergeId;

		OutEvent(final String pChange, final String pRecordKey, final String pSurvivorKey, final String pReason, final long pMergeId)
		{
			this.change = pChange;
			this.recordKey = pRecordKey;
			this.survivorKey = pSurvivorKey;
			this.reason = pReason;
			this.mergeId = pMergeId;
		}
	}

	/** {@link #decide}'s own result: what to send, and the watermark to store afterwards (unconditionally - even an all-NONE pass returns an advanced watermark when new rows were seen). */
	public static final class Decision
	{
		public final List<OutEvent> events;
		public final Watermark newWatermark;

		Decision(final List<OutEvent> pEvents, final Watermark pNewWatermark)
		{
			this.events = pEvents;
			this.newWatermark = pNewWatermark;
		}
	}

	/**
	 * The watermarks to persist after one watch pass. The events of every table are written in ONE transaction, so when it
	 * failed none of them exists: each table that has events keeps its STORED watermark, so the next pass decides the same
	 * events again and retries the write. Advancing it anyway would lose those notices for good (a merge older than the
	 * watermark is never looked at again). A table without events advances either way - nothing waits on it, and leaving it
	 * would make a first-pass table re-learn its baseline and miss what happens in between.
	 *
	 * @param pStored what the target holds now (a table never watched is absent)
	 * @param pDecided the watermark {@link #decide} produced for each table watched in this pass
	 * @param pTablesWithEvents the tables {@link #decide} produced at least one event for
	 * @param pEventsWritten whether the outbox rows were really written (true too when there was nothing to write)
	 */
	public static Map<String, Watermark> watermarksToPersist(final Map<String, Watermark> pStored, final Map<String, Watermark> pDecided,
		final Set<String> pTablesWithEvents, final boolean pEventsWritten)
	{
		final Map<String, Watermark> out = new LinkedHashMap<String, Watermark>(pDecided);
		if (!pEventsWritten)
		{
			for (final String tablePath : pTablesWithEvents)
			{
				final Watermark stored = pStored.get(tablePath);
				if (stored == null)
				{
					out.remove(tablePath);
				}
				else
				{
					out.put(tablePath, stored);
				}
			}
		}
		return out;
	}

	/** Whether {@code pToPersist} differs from {@code pStored} - a pass that learned nothing new writes nothing. */
	public static boolean watermarksChanged(final Map<String, Watermark> pStored, final Map<String, Watermark> pToPersist)
	{
		for (final Map.Entry<String, Watermark> entry : pToPersist.entrySet())
		{
			final Watermark stored = pStored.get(entry.getKey());
			if (stored == null || stored.lastMergeId != entry.getValue().lastMergeId || !stored.undoneMergeIds.equals(entry.getValue().undoneMergeIds))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param pWatermark the table's current watermark, or {@code null} when this table has never been watched
	 * before for this target (first pass: initialises to the current max merge id, emits nothing - merges
	 * older than the arming were never live records to begin with, SPEC-retire.md "mergedRecords=exclude on
	 * the first full send").
	 * @param pMergeRows every {@code MergingProcess} row currently known for this table's metadataset (need
	 * not be sorted; a table with none yet is an empty list, never null).
	 * @param pMembersByMergeId the {@code RecordMetadata} rows tied to each merge id THIS CALL might need
	 * (missing/null entries are treated as "no members known" - defensive, never thrown on).
	 * @param pRetireMode {@code NOTICE}/{@code TOMBSTONE}/{@code NONE} (see class Javadoc: only {@code NONE}
	 * changes anything here, by suppressing every event).
	 * @param pRetireUndoneMerges whether an undone merge's reinstated members get a {@code CREATE} event too.
	 */
	public static Decision decide(final Watermark pWatermark, final List<MergeRow> pMergeRows, final Map<Long, List<Member>> pMembersByMergeId,
		final String pRetireMode, final boolean pRetireUndoneMerges)
	{
		final List<MergeRow> rows = pMergeRows == null ? Collections.<MergeRow>emptyList() : pMergeRows;
		if (pWatermark == null)
		{
			// first pass: never emit for merges that predate this target ever being watched - just learn
			// where the log currently stands (both the max id and every id already undone, so a later pass
			// never mistakes an already-old undo for a "newly flipped" one).
			long maxId = 0L;
			final Set<Long> undoneNow = new LinkedHashSet<Long>();
			for (final MergeRow row : rows)
			{
				maxId = Math.max(maxId, row.mergeId);
				if (row.undone)
				{
					undoneNow.add(Long.valueOf(row.mergeId));
				}
			}
			return new Decision(Collections.<OutEvent>emptyList(), new Watermark(maxId, undoneNow));
		}

		final boolean emit = !MergeLogWatch.isRetireModeNone(pRetireMode);
		final List<OutEvent> events = new ArrayList<OutEvent>();
		long newLast = pWatermark.lastMergeId;
		final Set<Long> newUndone = new LinkedHashSet<Long>(pWatermark.undoneMergeIds);

		for (final MergeRow row : rows)
		{
			final Long idBoxed = Long.valueOf(row.mergeId);
			final boolean isNew = row.mergeId > pWatermark.lastMergeId;
			final boolean wasKnownUndone = pWatermark.undoneMergeIds.contains(idBoxed);
			final boolean newlyUndone = row.undone && !wasKnownUndone;

			if (newlyUndone)
			{
				if (!isNew)
				{
					// known merge, now observed undone for the first time: retire the golden, reinstate members.
					final List<Member> members = MergeLogWatch.membersOf(pMembersByMergeId, row.mergeId);
					events.addAll(MergeLogWatch.undoneEvents(members, row.mergeId, pRetireUndoneMerges));
				}
				// isNew && newlyUndone: the merge and its undo both happened, unobserved, between two passes -
				// never announced, nothing to retire or reinstate (see class Javadoc).
				newUndone.add(idBoxed);
			}
			else if (isNew)
			{
				// row.undone is either false, or true-but-already-known (impossible here since newlyUndone
				// would have been true in that case) - so this is a plain new merge.
				if (!row.undone)
				{
					final List<Member> members = MergeLogWatch.membersOf(pMembersByMergeId, row.mergeId);
					events.addAll(MergeLogWatch.mergeEvents(members, row.mergeId));
				}
			}
			// else: neither new nor newly undone - already accounted for in an earlier pass, idempotent no-op.
			newLast = Math.max(newLast, row.mergeId);
		}

		final Watermark newWatermark = new Watermark(newLast, newUndone);
		return new Decision(emit ? events : Collections.<OutEvent>emptyList(), newWatermark);
	}

	private static List<Member> membersOf(final Map<Long, List<Member>> pMembersByMergeId, final long pMergeId)
	{
		if (pMembersByMergeId == null)
		{
			return Collections.emptyList();
		}
		final List<Member> members = pMembersByMergeId.get(Long.valueOf(pMergeId));
		return members == null ? Collections.<Member>emptyList() : members;
	}

	/** New-merge case: one RETIRE per member whose state is MERGED - {@link RetireRules} decides the survivor. */
	private static List<OutEvent> mergeEvents(final List<Member> pMembers, final long pMergeId)
	{
		final List<OutEvent> out = new ArrayList<OutEvent>();
		for (final Member member : pMembers)
		{
			if (!RetireRules.STATE_MERGED.equals(member.state))
			{
				continue;
			}
			final RetireRules.Outcome outcome = RetireRules.evaluate(member.state, member.goldenKey, member.autoCreated);
			if (outcome.retire)
			{
				out.add(new OutEvent(MergeLogWatch.CHANGE_RETIRE, member.recordKey, outcome.survivorKey, MergeLogWatch.REASON_MERGE, pMergeId));
			}
		}
		return out;
	}

	/** Undone-merge case: retire the auto-created golden (no survivor), then CREATE every reinstated (now GOLDEN) member when {@code pRetireUndoneMerges}. A member still PENDING_APPROVAL gets nothing (documented gap, SPEC-retire.md). */
	private static List<OutEvent> undoneEvents(final List<Member> pMembers, final long pMergeId, final boolean pRetireUndoneMerges)
	{
		final List<OutEvent> out = new ArrayList<OutEvent>();
		for (final Member member : pMembers)
		{
			if (member.autoCreated)
			{
				final RetireRules.Outcome outcome = RetireRules.evaluate(RetireRules.STATE_DELETED, null, true);
				if (outcome.retire)
				{
					out.add(new OutEvent(MergeLogWatch.CHANGE_RETIRE, member.recordKey, outcome.survivorKey, MergeLogWatch.REASON_UNMERGE, pMergeId));
				}
			}
			else if (pRetireUndoneMerges && RetireRules.STATE_GOLDEN.equals(member.state))
			{
				out.add(new OutEvent(MergeLogWatch.CHANGE_CREATE, member.recordKey, null, MergeLogWatch.REASON_UNMERGE, pMergeId));
			}
			// PENDING_APPROVAL (or any other state): nothing yet - documented gap, SPEC-retire.md.
		}
		return out;
	}

	/** Small local helper so {@link #decide} reads as one sentence - avoids a hard dependency on any particular enum constant class for "NONE" other than the plain string the target's own field stores ({@code FactoryPaths.DistributionTarget.RETIRE_MODE_NONE} on the EBX side). */
	private static boolean isRetireModeNone(final String pRetireMode)
	{
		return "NONE".equals(pRetireMode);
	}
}
