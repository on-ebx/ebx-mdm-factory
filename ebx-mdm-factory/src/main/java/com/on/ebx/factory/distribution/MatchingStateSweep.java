package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lot S2 (2026-10-18, retire notices on merge): the PURE decision of the state sweep - no EBX class, unit-tested ({@code MatchingStateSweepTest}).
 * <p>
 * <b>Why it exists next to {@link MergeLogWatch}.</b> The watch reads the merge LOG (one {@code MergingProcess} row per merge) and the members tied to
 * a merge id by {@code RecordMetadata.mergingProcessId}. That is exact for a new merge, but it cannot see three things, all of them left to the
 * metadataset's STATE by the add-on (CLAUDE.md sec 8): (1) after an unmerge the former members go back to GOLDEN with {@code mergingProcessId} reset
 * to 0 (measured 2026-09-23), so the members of an undone merge can no longer be found by the merge id and are never told they live again; (2) a
 * member that comes back PENDING_APPROVAL and only later GOLDEN is never revisited; (3) a merge the watch announced but whose notice was skipped
 * (target paused at dispatch time) or lost with the watermark. The sweep compares the metadataset's CURRENT states with what the target has been told
 * (the "ledger": outbox rows still to send plus the history of what was sent) and queues what is missing. It never looks at the merge log, only at
 * states, and it is idempotent: a notice it queues is in the ledger at the next sweep.
 * <p>
 * <b>The rules</b> (per master table, per target):
 * <ol>
 * <li><b>R1 - merged record without a notice.</b> A record in state MERGED whose last announcement is not a RETIRE (none, or a CREATE of an earlier
 * reinstatement after it) gets {@code RETIRE(key, mergedInto=goldenKey, MERGE, mergeId)} when it was PUBLISHED: its merge id is above the target's
 * {@code baseline} (the highest merge id when the target started being watched - a record merged under it was excluded from, or never part of, the
 * first full send, so the target never held it as live and there is nothing to retire) and above what a previous sweep already compared
 * ({@code swept}), or the ledger proves it was published (a RETIRE for it that was never delivered - SKIPPED or finally FAILED - or an earlier
 * reinstatement). A merge id above {@code watchLast} is left to the watch, which is about to announce it (no double notice).</li>
 * <li><b>R2 - auto-created golden deleted by an unmerge.</b> A record in state DELETED with {@code autoCreated} (CLAUDE.md sec 8: the golden's row
 * stays) gets {@code RETIRE(key, no survivor, UNMERGE, mergeId)} unless the ledger already holds it retired, when ANY of these holds: (a) the
 * ledger proves it was published (same proof as R1); (b) its merge id is above the floor ({@code swept}, else {@code baseline}) and not above
 * {@code watchLast} - the merge log watch announced that golden's CREATE; (c) the target made its FIRST FULL SEND ({@code firstFullSendDone}) and the
 * golden's merge id is at or under the {@code baseline} (or it has no merge id): such a golden existed as GOLDEN when the first full send ran, which
 * sends GOLDEN rows, so the target holds it and its deletion must be announced (measured 2026-10-18, scenario run 4: golden 539 of merge 1 =
 * baseline, unmerged after the first send, never retired under the member publication rule). A target armed WITHOUT a first full send ("Armer sans
 * cela") published nothing at arming, so (c) does not apply to it. This differs on purpose from R1: a MERGED member under the baseline was never
 * a GOLDEN row of that first send.</li>
 * <li><b>R3 - former member live again.</b> A record whose last announcement is a RETIRE and whose state is now GOLDEN (and that is not an
 * unmerge-deleted golden) gets {@code CREATE(key, UNMERGE)} - the target then holds it as live again - when the target's {@code retireUndoneMerges}
 * is true. A member still PENDING_APPROVAL gets nothing yet; it is a candidate again at every sweep until it is GOLDEN.</li>
 * </ol>
 * {@code retireMode=NONE} emits nothing (the marks still advance, like the watch). Pure functions of their inputs: the same input twice gives the same
 * events, and an input whose ledger already holds the first run's events gives none.
 */
public final class MatchingStateSweep
{
	public static final String CHANGE_RETIRE = EventCoalescer.RETIRE;
	public static final String CHANGE_CREATE = EventCoalescer.CREATE;
	public static final String REASON_MERGE = MergeLogWatch.REASON_MERGE;
	public static final String REASON_UNMERGE = MergeLogWatch.REASON_UNMERGE;

	private MatchingStateSweep()
	{
	}

	/** One {@code RecordMetadata} row in state MERGED. */
	public static final class MergedRow
	{
		public final String key;
		public final String goldenKey;
		/** {@code mergingProcessId}; null when the row carries none. */
		public final Long mergeId;

		public MergedRow(final String pKey, final String pGoldenKey, final Long pMergeId)
		{
			this.key = pKey;
			this.goldenKey = pGoldenKey;
			this.mergeId = pMergeId;
		}
	}

	/** One {@code RecordMetadata} row in state DELETED with {@code autoCreated} (an auto-created golden an unmerge left in the table). */
	public static final class DeletedGolden
	{
		public final String key;
		public final Long mergeId;

		public DeletedGolden(final String pKey, final Long pMergeId)
		{
			this.key = pKey;
			this.mergeId = pMergeId;
		}
	}

	/** What the target has been told about one record, read from the outbox (rows still to send) and the history (rows sent). Mutable: the reader fills it in. */
	public static final class LedgerEntry
	{
		/** Epoch ms of the latest RETIRE that is announced (still queued, or sent); -1 when none. */
		public long retireAt = -1L;
		public Long retireMergeId;
		/** A RETIRE was queued for it but never delivered (SKIPPED, or FAILED for good). */
		public boolean retireNotDelivered;
		/** Epoch ms of the latest CREATE made by a reinstatement (reason UNMERGE) that is announced; -1 when none. */
		public long reinstateAt = -1L;

		/** The last announcement is a RETIRE: the target holds this record as retired. */
		public boolean holdsRetired()
		{
			return this.retireAt >= 0L && this.retireAt >= this.reinstateAt;
		}
	}

	/** The current {@code RecordMetadata.state} of a record, as {@link RetireRules}' vocabulary. */
	public static final class StateRow
	{
		public final String state;

		public StateRow(final String pState)
		{
			this.state = pState;
		}
	}

	/** One table's inputs. */
	public static final class Input
	{
		public String retireMode = "NOTICE";
		public boolean retireUndoneMerges = true;
		/** The target's baseline for this table; null when unknown (a field written before this lot): nothing is announced on the strength of a merge id. */
		public Long baseline;
		/** The highest merge id a previous sweep compared; null = never swept. */
		public Long swept;
		/** The watch's watermark ({@code last}) after its pass of this tick. */
		public long watchLast;
		/**
		 * True when the target recorded a first full send ({@code DistributionTarget.firstFullSendRunId} non-empty); false when it was armed without one.
		 * Only R2 reads it: the first full send published every GOLDEN row, so a deleted auto-created golden at or under the baseline was published.
		 */
		public boolean firstFullSendDone;
		public List<MergedRow> merged = new ArrayList<MergedRow>();
		public List<DeletedGolden> deletedGoldens = new ArrayList<DeletedGolden>();
		public Map<String, LedgerEntry> ledger = Collections.emptyMap();
		/** States of the keys {@link #reinstateCandidates} named (absent key = the record has no metadata row). */
		public Map<String, StateRow> candidateStates = Collections.emptyMap();
	}

	/** One event to queue. */
	public static final class OutEvent
	{
		public final String change;
		public final String recordKey;
		public final String survivorKey;
		public final String reason;
		/** Null when unknown. */
		public final Long mergeId;

		OutEvent(final String pChange, final String pRecordKey, final String pSurvivorKey, final String pReason, final Long pMergeId)
		{
			this.change = pChange;
			this.recordKey = pRecordKey;
			this.survivorKey = pSurvivorKey;
			this.reason = pReason;
			this.mergeId = pMergeId;
		}
	}

	/** {@link #decide}'s result. */
	public static final class Result
	{
		public final List<OutEvent> events;
		public final int retired;
		public final int goldensRetired;
		public final int reinstated;
		/** The {@code swept} mark to store once the events are written. */
		public final long newSwept;

		Result(final List<OutEvent> pEvents, final int pRetired, final int pGoldensRetired, final int pReinstated, final long pNewSwept)
		{
			this.events = pEvents;
			this.retired = pRetired;
			this.goldensRetired = pGoldensRetired;
			this.reinstated = pReinstated;
			this.newSwept = pNewSwept;
		}
	}

	/**
	 * The keys the reader must look up in {@code RecordMetadata} before {@link #decide}: those the target holds as retired and that are neither
	 * merged nor unmerge-deleted goldens now - the former members of an undone merge, or records whose metadata is gone.
	 */
	public static Set<String> reinstateCandidates(final Map<String, LedgerEntry> pLedger, final Set<String> pMergedKeys, final Set<String> pDeletedGoldenKeys)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final Map.Entry<String, LedgerEntry> entry : pLedger.entrySet())
		{
			if (entry.getValue().holdsRetired() && !pMergedKeys.contains(entry.getKey()) && !pDeletedGoldenKeys.contains(entry.getKey()))
			{
				out.add(entry.getKey());
			}
		}
		return out;
	}

	public static Result decide(final Input pInput)
	{
		final boolean emit = !"NONE".equals(pInput.retireMode);
		final List<OutEvent> events = new ArrayList<OutEvent>();
		int retired = 0;
		int goldensRetired = 0;
		int reinstated = 0;
		final Set<String> mergedKeys = new LinkedHashSet<String>();
		final Set<String> deletedKeys = new LinkedHashSet<String>();

		for (final MergedRow row : pInput.merged)
		{
			mergedKeys.add(row.key);
			if (emit && MatchingStateSweep.needsRetire(pInput, pInput.ledger.get(row.key), row.mergeId))
			{
				events.add(new OutEvent(MatchingStateSweep.CHANGE_RETIRE, row.key, blankToNull(row.goldenKey), MatchingStateSweep.REASON_MERGE, row.mergeId));
				retired++;
			}
		}
		for (final DeletedGolden golden : pInput.deletedGoldens)
		{
			deletedKeys.add(golden.key);
			if (emit && MatchingStateSweep.needsRetireGolden(pInput, pInput.ledger.get(golden.key), golden.mergeId))
			{
				events.add(new OutEvent(MatchingStateSweep.CHANGE_RETIRE, golden.key, null, MatchingStateSweep.REASON_UNMERGE, golden.mergeId));
				goldensRetired++;
			}
		}
		if (emit && pInput.retireUndoneMerges)
		{
			for (final Map.Entry<String, LedgerEntry> entry : pInput.ledger.entrySet())
			{
				final String key = entry.getKey();
				if (!entry.getValue().holdsRetired() || mergedKeys.contains(key) || deletedKeys.contains(key))
				{
					continue;
				}
				final StateRow state = pInput.candidateStates.get(key);
				if (state != null && RetireRules.STATE_GOLDEN.equals(state.state))
				{
					events.add(new OutEvent(MatchingStateSweep.CHANGE_CREATE, key, null, MatchingStateSweep.REASON_UNMERGE, entry.getValue().retireMergeId));
					reinstated++;
				}
			}
		}
		final long previous = pInput.swept == null ? 0L : pInput.swept.longValue();
		return new Result(events, retired, goldensRetired, reinstated, Math.max(previous, pInput.watchLast));
	}

	/**
	 * R2's test for a deleted auto-created golden (see the class Javadoc): not held as retired, and ledger-proven, or announced by the watch (merge id
	 * above the floor), or published by the first full send (first full send done and merge id at or under the baseline, or unknown).
	 */
	private static boolean needsRetireGolden(final Input pInput, final LedgerEntry pLedger, final Long pMergeId)
	{
		if (pLedger != null && pLedger.holdsRetired())
		{
			return false;
		}
		if (MatchingStateSweep.needsRetire(pInput, pLedger, pMergeId))
		{
			return true;
		}
		if (!pInput.firstFullSendDone)
		{
			return false;
		}
		if (pMergeId == null)
		{
			return true; // the first full send was made and nothing dates the golden: it was GOLDEN then, as far as anything shows - announce
		}
		final long ceiling = pInput.baseline != null ? pInput.baseline.longValue() : pInput.swept != null ? pInput.swept.longValue() : pInput.watchLast;
		return pMergeId.longValue() <= ceiling;
	}

	/** R1's test (and half of R2's): the target does not hold the record as retired, and the record was published as a live row (see the class Javadoc). */
	private static boolean needsRetire(final Input pInput, final LedgerEntry pLedger, final Long pMergeId)
	{
		if (pLedger != null && pLedger.holdsRetired())
		{
			return false;
		}
		final boolean proven = pLedger != null && (pLedger.retireNotDelivered || pLedger.reinstateAt >= 0L);
		if (proven)
		{
			return true;
		}
		if (pMergeId == null)
		{
			return false; // no merge id to compare with the baseline: nothing proves the target ever held it
		}
		final long floor = pInput.swept != null ? pInput.swept.longValue() : pInput.baseline != null ? pInput.baseline.longValue() : pInput.watchLast;
		return pMergeId.longValue() > floor && pMergeId.longValue() <= pInput.watchLast;
	}

	private static String blankToNull(final String pText)
	{
		return pText == null || pText.trim().isEmpty() ? null : pText;
	}
}
