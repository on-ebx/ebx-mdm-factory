package com.on.ebx.factory.ebx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.MatchingStateSweep;
import com.on.ebx.factory.distribution.MatchingStateSweep.DeletedGolden;
import com.on.ebx.factory.distribution.MatchingStateSweep.LedgerEntry;
import com.on.ebx.factory.distribution.MatchingStateSweep.MergedRow;
import com.on.ebx.factory.distribution.MatchingStateSweep.OutEvent;
import com.on.ebx.factory.distribution.MatchingStateSweep.Result;
import com.on.ebx.factory.distribution.MatchingStateSweep.StateRow;
import com.on.ebx.factory.distribution.MergeLogState;
import com.on.ebx.factory.distribution.TargetMappingList;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * Lot S2 (2026-10-18, retire notices on merge): the EBX-facing side of {@link MatchingStateSweep} - compares the Match and Merge metadataset's CURRENT
 * states with what an ARMED REALTIME target has been told, and queues the missing RETIRE / reinstating CREATE notices in the project's outbox, where the
 * dispatcher's ordinary drain sends them. It runs on the dispatcher's thread, right after {@link MergeLogWatcher#watch} (so the watch's own fresh rows are
 * already in the ledger), at most once a minute per target, and at once when the Factory has just finished a matching run
 * ({@code DistributionDispatcher#requestSweep}).
 * <p>
 * <b>Reads</b> (per master table that has a metadataset, nothing when it has none): (1) the metadataset table's last modification date - when it has not
 * moved since the last sweep and the sweep is up to date, nothing else is read; (2) ONE streaming request on {@code RecordMetadata} restricted to
 * {@code ./state=2} (merged key, golden key, merge id) and ONE on {@code ./state=4 and ./autoCreated=true} (the goldens an unmerge left deleted) - the
 * shape CLAUDE.md sec 8 measured at 417,841 rows in 3.5 s; (3) the ledger: the project's outbox rows of this target and table (still to send), and the
 * target's {@code DistributionEvent} history rows of the table that are RETIRE notices or reinstating CREATEs; (4) one lookup of {@code RecordMetadata}
 * per record the target holds as retired and that is neither merged nor a deleted golden (the former members of an undone merge). <b>Writes</b>: the
 * notices, in one {@code Procedure} on the master dataspace's outbox (the same writer as the watch), then the marks ({@code swept}, the table stamp, the
 * time and the count) in the target's {@code mergeLogWatermark} - only once the notices are committed (at-least-once).
 * <p>
 * <b>Not verified live</b> against a running merge/unmerge in this session (no instance with a distribution target and Match and Merge data was
 * reachable); the pure rules are unit-tested, the field layout is the one {@code MergeLogWatcher} already reads.
 */
final class MatchingStateSweeper
{
	/** Between two sweeps of one target, unless forced (end of a matching run). */
	static final long MIN_INTERVAL_MS = 60000L;
	private static final Map<String, Long> LAST_CHECK = new ConcurrentHashMap<String, Long>();

	private MatchingStateSweeper()
	{
	}

	/** When the sweep last LOOKED at this target in this JVM (epoch ms; 0 = not since the module started) - a value read-only screens show beside the stored time of the last real sweep. */
	static long lastCheckMillis(final String pTargetId)
	{
		final Long value = MatchingStateSweeper.LAST_CHECK.get(pTargetId);
		return value == null ? 0L : value.longValue();
	}

	static void sweep(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final String pMasterDatasetName, final Adaptation pOutboxDataset,
		final String pTargetId, final boolean pForce, final LoggingCategory pLog)
	{
		if (pOutboxDataset == null || pMasterHome == null || pMasterDatasetName == null || pMasterDatasetName.trim().isEmpty())
		{
			return;
		}
		final long now = System.currentTimeMillis();
		final Long previous = MatchingStateSweeper.LAST_CHECK.get(pTargetId);
		if (!pForce && previous != null && now - previous.longValue() < MatchingStateSweeper.MIN_INTERVAL_MS)
		{
			return;
		}
		MatchingStateSweeper.LAST_CHECK.put(pTargetId, Long.valueOf(now));
		// the watch has just written its part: read the target again, not the row the pass started with
		final Adaptation target = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
		if (target == null)
		{
			return;
		}
		final String retireMode = target.getString(FactoryPaths.DistributionTarget.RETIRE_MODE);
		final boolean retireUndone = !Boolean.FALSE.equals(target.get(FactoryPaths.DistributionTarget.RETIRE_UNDONE_MERGES));
		final Map<String, String> tables = TargetMappingList.parse(target.getString(FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS));
		final MergeLogState state = MergeLogStateStore.read(target);
		final AdaptationTable outboxTable = pOutboxDataset.getTable(FactoryPaths.DistributionOutbox.EVENT);

		final List<MergeLogWatcher.PendingRow> toWrite = new ArrayList<MergeLogWatcher.PendingRow>();
		final Map<String, Long> newSwept = new LinkedHashMap<String, Long>();
		final Map<String, Long> newStamps = new LinkedHashMap<String, Long>();
		for (final String tablePath : tables.keySet())
		{
			try
			{
				final MergeLogState.TableMark mark = state.table(tablePath);
				final AdaptationTable recordMetadata = MergeLogWatcher.metadatasetTable(pMasterHome, pMasterDatasetName, tablePath, "RecordMetadata");
				if (mark == null || recordMetadata == null)
				{
					continue; // not watched yet (the watch learns it at its first pass) or no metadataset: nothing to compare
				}
				final Date modified = recordMetadata.getLastModificationDate();
				final long stamp = modified == null ? 0L : modified.getTime();
				final Long lastStamp = state.sweep().stamps.get(tablePath);
				final boolean upToDate = mark.swept != null && mark.swept.longValue() >= mark.last;
				if (!pForce && upToDate && lastStamp != null && lastStamp.longValue() == stamp)
				{
					continue; // nothing changed in the metadataset since the last sweep
				}
				final MatchingStateSweep.Input input = new MatchingStateSweep.Input();
				input.retireMode = retireMode;
				input.retireUndoneMerges = retireUndone;
				input.baseline = mark.baseline;
				input.swept = mark.swept;
				input.watchLast = mark.last;
				// R2: a first full send publishes every GOLDEN row, so a golden at or under the baseline was published (blank = armed without one)
				final String firstSendRun = target.getString(FactoryPaths.DistributionTarget.FIRST_FULL_SEND_RUN_ID);
				input.firstFullSendDone = firstSendRun != null && !firstSendRun.trim().isEmpty();
				final Set<String> mergedKeys = new LinkedHashSet<String>();
				final Set<String> deletedKeys = new LinkedHashSet<String>();
				MatchingStateSweeper.readMerged(recordMetadata, input.merged, mergedKeys);
				MatchingStateSweeper.readDeletedGoldens(recordMetadata, input.deletedGoldens, deletedKeys);
				input.ledger = MatchingStateSweeper.readLedger(pFactory, outboxTable, pTargetId, tablePath);
				final Set<String> candidates = MatchingStateSweep.reinstateCandidates(input.ledger, mergedKeys, deletedKeys);
				input.candidateStates = MatchingStateSweeper.readStates(recordMetadata, candidates);
				final Result result = MatchingStateSweep.decide(input);
				for (final OutEvent event : result.events)
				{
					toWrite.add(new MergeLogWatcher.PendingRow(tablePath, event.change, event.recordKey, event.survivorKey, event.reason,
						event.mergeId == null ? null : String.valueOf(event.mergeId)));
				}
				newSwept.put(tablePath, Long.valueOf(result.newSwept));
				newStamps.put(tablePath, Long.valueOf(stamp));
			}
			catch (final RuntimeException ex)
			{
				if (pLog != null)
				{
					pLog.error("[mdm-factory] state sweep: table " + tablePath + " of target " + pTargetId + " failed: " + ex, ex);
				}
			}
		}
		if (newSwept.isEmpty())
		{
			return;
		}
		// the marks only move past a state once its notices really are in the outbox
		if (!toWrite.isEmpty() && !MergeLogWatcher.writeOutboxRows(pSession, pMasterHome, pOutboxDataset, pTargetId, toWrite, pLog))
		{
			return;
		}
		final int queued = toWrite.size();
		final String at = Instant.ofEpochMilli(now).toString();
		MergeLogStateStore.update(pSession, pFactory, pTargetId, new MergeLogStateStore.Edit()
		{
			@Override
			public void apply(final MergeLogState pState)
			{
				for (final Map.Entry<String, Long> entry : newSwept.entrySet())
				{
					pState.tableOrCreate(entry.getKey()).swept = entry.getValue();
				}
				pState.sweep().stamps.putAll(newStamps);
				pState.sweep().at = at;
				pState.sweep().queued += queued;
			}
		}, pLog);
		if (queued > 0 && pLog != null)
		{
			pLog.info("[mdm-factory] state sweep: target " + pTargetId + " queued " + queued + " notice(s) the merge log watch had not (or could not) announce.");
		}
	}

	private static void readMerged(final AdaptationTable pRecordMetadata, final List<MergedRow> pOut, final Set<String> pKeys)
	{
		final RequestResult rows = pRecordMetadata.createRequestResult("./state=" + com.on.ebx.factory.master.MameMetadataContract.STATE_MERGED);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String key = row.getString(Path.parse("./functionalId"));
				if (key == null)
				{
					continue;
				}
				pOut.add(new MergedRow(key, row.getString(Path.parse("./goldenId")), MatchingStateSweeper.longOrNull(row.get(Path.parse("./mergingProcessId")))));
				pKeys.add(key);
			}
		}
		finally
		{
			rows.close();
		}
	}

	private static void readDeletedGoldens(final AdaptationTable pRecordMetadata, final List<DeletedGolden> pOut, final Set<String> pKeys)
	{
		final RequestResult rows = pRecordMetadata.createRequestResult("./state=" + com.on.ebx.factory.master.MameMetadataContract.STATE_DELETED + " and ./autoCreated=true");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String key = row.getString(Path.parse("./functionalId"));
				if (key == null)
				{
					continue;
				}
				pOut.add(new DeletedGolden(key, MatchingStateSweeper.longOrNull(row.get(Path.parse("./mergingProcessId")))));
				pKeys.add(key);
			}
		}
		finally
		{
			rows.close();
		}
	}

	/** A merge id of 0 (the add-on's "none", e.g. a member after an unmerge) is no merge id. */
	private static Long longOrNull(final Object pValue)
	{
		if (pValue instanceof Number && ((Number) pValue).longValue() > 0L)
		{
			return Long.valueOf(((Number) pValue).longValue());
		}
		return null;
	}

	/** The current state of each key, one lookup by the metadataset's primary key (its {@code functionalId} text, not parsed as a key of the business table). */
	private static Map<String, StateRow> readStates(final AdaptationTable pRecordMetadata, final Set<String> pKeys)
	{
		final Map<String, StateRow> out = new LinkedHashMap<String, StateRow>();
		for (final String key : pKeys)
		{
			final RequestResult rows = pRecordMetadata.createRequestResult("./functionalId=" + CatalogReader.quote(key));
			try
			{
				final Adaptation row = rows.nextAdaptation();
				if (row != null)
				{
					out.put(key, new StateRow(MergeLogWatcher.stateName(row.get(Path.parse("./state")))));
				}
			}
			finally
			{
				rows.close();
			}
		}
		return out;
	}

	/**
	 * What the target has been told about each record of the table: outbox rows still to send (PENDING, or FAILED for good - both are "announced"),
	 * then the history (SENT and FAILED-for-good = announced; SKIPPED = a notice that never arrived).
	 */
	private static Map<String, LedgerEntry> readLedger(final Adaptation pFactory, final AdaptationTable pOutboxTable, final String pTargetId, final String pTablePath)
	{
		final Map<String, LedgerEntry> ledger = new LinkedHashMap<String, LedgerEntry>();
		final RequestResult queued = pOutboxTable.createRequestResult("./target=" + CatalogReader.quote(pTargetId) + " and ./tablePath=" + CatalogReader.quote(pTablePath));
		try
		{
			for (Adaptation row = queued.nextAdaptation(); row != null; row = queued.nextAdaptation())
			{
				MatchingStateSweeper.note(ledger, row.getString(FactoryPaths.DistributionOutbox.RECORD_KEY), row.getString(FactoryPaths.DistributionOutbox.CHANGE),
					row.getString(FactoryPaths.DistributionOutbox.REASON), row.getString(FactoryPaths.DistributionOutbox.MERGE_ID),
					row.get(FactoryPaths.DistributionOutbox.CREATED_AT), true);
			}
		}
		finally
		{
			queued.close();
		}
		final RequestResult history = pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT).createRequestResult("./target=" + CatalogReader.quote(pTargetId)
			+ " and ./tablePath=" + CatalogReader.quote(pTablePath) + " and (./change='RETIRE' or ./reason='UNMERGE')");
		try
		{
			for (Adaptation row = history.nextAdaptation(); row != null; row = history.nextAdaptation())
			{
				// SENT is announced; so is a FAILED one (the retry budget is spent and the notice waits for a person's replay: queuing another every minute would defeat
				// the budget); only a SKIPPED one (the target was no longer armed at dispatch time, ...) never reached the target
				final String status = row.getString(FactoryPaths.DistributionEvent.STATUS);
				final boolean sent = !FactoryPaths.DistributionEvent.STATUS_SKIPPED.equals(status);
				MatchingStateSweeper.note(ledger, row.getString(FactoryPaths.DistributionEvent.RECORD_KEY), row.getString(FactoryPaths.DistributionEvent.CHANGE),
					row.getString(FactoryPaths.DistributionEvent.REASON), row.getString(FactoryPaths.DistributionEvent.MERGE_ID),
					row.get(FactoryPaths.DistributionEvent.CREATED_AT), sent);
			}
		}
		finally
		{
			history.close();
		}
		return ledger;
	}

	private static void note(final Map<String, LedgerEntry> pLedger, final String pKey, final String pChange, final String pReason, final String pMergeId,
		final Object pCreatedAt, final boolean pAnnounced)
	{
		if (pKey == null)
		{
			return;
		}
		final boolean retire = FactoryPaths.DistributionEvent.CHANGE_RETIRE.equals(pChange);
		final boolean reinstate = FactoryPaths.DistributionEvent.CHANGE_CREATE.equals(pChange) && FactoryPaths.DistributionEvent.REASON_UNMERGE.equals(pReason);
		if (!retire && !reinstate)
		{
			return;
		}
		LedgerEntry entry = pLedger.get(pKey);
		if (entry == null)
		{
			entry = new LedgerEntry();
			pLedger.put(pKey, entry);
		}
		final long at = pCreatedAt instanceof Date ? ((Date) pCreatedAt).getTime() : 0L;
		if (retire)
		{
			if (!pAnnounced)
			{
				entry.retireNotDelivered = true;
			}
			else if (at >= entry.retireAt)
			{
				entry.retireAt = at;
				entry.retireMergeId = MatchingStateSweeper.parseLong(pMergeId);
			}
		}
		else if (pAnnounced && at > entry.reinstateAt)
		{
			entry.reinstateAt = at;
		}
	}

	private static Long parseLong(final String pText)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return Long.valueOf(Long.parseLong(pText.trim()));
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}
}
