package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.MergeLogState;
import com.on.ebx.factory.distribution.MergeLogWatch;
import com.on.ebx.factory.distribution.MergeLogWatch.Decision;
import com.on.ebx.factory.distribution.MergeLogWatch.Member;
import com.on.ebx.factory.distribution.MergeLogWatch.MergeRow;
import com.on.ebx.factory.distribution.MergeLogWatch.OutEvent;
import com.on.ebx.factory.distribution.MergeLogWatch.Watermark;
import com.on.ebx.factory.distribution.RetireRules;
import com.on.ebx.factory.distribution.TargetMappingList;
import com.on.ebx.factory.master.MameMetadataContract;
import com.on.ebx.factory.master.MameMetadataset;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * "retire notices from the merge log" (SPEC-retire.md "Merge-log watch"): the EBX-facing counterpart of the
 * pure {@link com.on.ebx.factory.distribution.MergeLogWatch} - reads the Match and Merge add-on's own
 * metadataset tables directly ({@code MergingProcess}, {@code RecordMetadata}), the same direct-read pattern
 * {@link MameMetadatasetReads} already established (no add-on Java class involved, so nothing here can throw
 * {@link LinkageError} - a missing/unactivated metadataset degrades to "nothing to watch for this table",
 * never an error), feeds them to {@code MergeLogWatch.decide}, writes the resulting RETIRE/CREATE rows into
 * the project's own {@code DistributionOutbox}, and only then persists the advanced watermark onto {@code
 * DistributionTarget.mergeLogWatermark}.
 * <p>
 * Called from {@code DistributionDispatcher.drainTarget} BEFORE that method drains the outbox's existing
 * PENDING rows (SPEC-retire.md: "so a merge's RETIREs are queued in the same pass as the golden's own trigger
 * event") - this is what replaces the {@code DistributionTriggerBody} {@code Linked_Field_State} comparison
 * that CLAUDE.md sec 8 and this lot's own measurement (2026-09-27, Personnes, suspect 527 into 347) showed
 * never actually fires for a merge (a merge only touches the metadataset, never the master row's own save, so
 * {@code afterModify} never runs for the merged rows at all).
 * <p>
 * <b>Field layout read here</b> ({@code MetadataTemplate.xsd} on this instance's server project, plus the
 * live facts recorded in SPEC-retire.md, 2026-09-27) - NOT re-verified against a running server in this
 * session:
 * <ul>
 * <li>{@code MergingProcess}: {@code id} (int, auto-increment), {@code isUnmerged} (boolean).</li>
 * <li>{@code RecordMetadata}: {@code functionalId} (string, the primary key - the record's own formatted
 * key), {@code state} (int enum: 0 UNSET, 1 GOLDEN, 2 MERGED, 3 PENDING_APPROVAL, 4 DELETED - CLAUDE.md sec 8's
 * own state codes), {@code autoCreated} (boolean), {@code goldenId} (string), {@code mergingProcessId} (int -
 * the {@code MergingProcess.id} this row is tied to, when it has ever taken part in a merge/unmerge).</li>
 * </ul>
 * <b>Bounded reads, with one documented exception</b>: {@link #readMembers} queries {@code RecordMetadata} by
 * {@code mergingProcessId} predicate, one merge id at a time, only for merge ids the watermark says need
 * attention (never a scan of the whole table). {@link #readMergeRows}, however, scans the WHOLE {@code
 * MergingProcess} table every pass (no predicate) - there is no field to filter on that would keep this
 * bounded to only the "interesting" rows without already knowing which ones are interesting (the very thing
 * this read establishes), so on a project with a very large history of merges this becomes an unbounded
 * per-pass cost; not measured, same "documented, not measured" caveat as {@code
 * docs/DISTRIBUTION.md}'s own per-row paused-trigger-query note.
 */
final class MergeLogWatcher
{
	private MergeLogWatcher()
	{
	}

	/**
	 * Runs the watch for one ARMED REALTIME target, over every master table it sends through (the keys of its
	 * own {@code connectorsMappingIds} - {@link TargetMappingList}). Writes any RETIRE/CREATE rows into {@code
	 * pOutboxDataset}'s {@code Event} table first (one {@code Procedure}, all privileges, same
	 * session/home as every other outbox write in {@code DistributionDispatcher}); only once that write
	 * SUCCEEDED does it persist the advanced watermark onto {@code DistributionTarget.mergeLogWatermark}
	 * (a separate {@code Procedure} against MdmFactory; a failed write leaves the watermark of the tables that have
	 * events where it was, so the next pass retries) - SPEC-retire.md's own "at-least-once" contract: a
	 * crash between the two simply re-emits the same events on the next pass.
	 */
	static void watch(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final String pMasterDatasetName,
		final Adaptation pOutboxDataset, final Adaptation pTarget, final String pTargetId, final LoggingCategory pLog)
	{
		if (pOutboxDataset == null || pMasterHome == null || pMasterDatasetName == null || pMasterDatasetName.trim().isEmpty())
		{
			return;
		}
		final String retireMode = pTarget.getString(FactoryPaths.DistributionTarget.RETIRE_MODE);
		final boolean retireUndone = !Boolean.FALSE.equals(pTarget.get(FactoryPaths.DistributionTarget.RETIRE_UNDONE_MERGES));
		final Map<String, String> tables = TargetMappingList.parse(pTarget.getString(FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS));
		if (tables.isEmpty())
		{
			return;
		}
		final MergeLogState storedState = MergeLogStateStore.read(pTarget);
		final Map<String, Watermark> watermarkByTable = new LinkedHashMap<String, Watermark>();
		for (final String storedTable : storedState.tables().keySet())
		{
			watermarkByTable.put(storedTable, storedState.watermark(storedTable));
		}
		final Map<String, Watermark> decidedWatermarkByTable = new LinkedHashMap<String, Watermark>(watermarkByTable);
		final List<PendingRow> toWrite = new ArrayList<PendingRow>();
		final Set<String> tablesWithEvents = new LinkedHashSet<String>();

		for (final String tablePath : tables.keySet())
		{
			try
			{
				final AdaptationTable mergingTable = MergeLogWatcher.metadatasetTable(pMasterHome, pMasterDatasetName, tablePath, "MergingProcess");
				final AdaptationTable recordMetadataTable = MergeLogWatcher.metadatasetTable(pMasterHome, pMasterDatasetName, tablePath, "RecordMetadata");
				if (mergingTable == null || recordMetadataTable == null)
				{
					continue; // no matching configured, or configured but never activated on this table yet (CLAUDE.md sec 8)
				}
				final List<MergeRow> mergeRows = MergeLogWatcher.readMergeRows(mergingTable);
				final Watermark watermark = watermarkByTable.get(tablePath);
				final Set<Long> candidateIds = MergeLogWatcher.candidateIds(watermark, mergeRows);
				final Map<Long, List<Member>> membersByMergeId = MergeLogWatcher.readMembers(recordMetadataTable, candidateIds);
				final Decision decision = MergeLogWatch.decide(watermark, mergeRows, membersByMergeId, retireMode, retireUndone);
				decidedWatermarkByTable.put(tablePath, decision.newWatermark);
				for (final OutEvent event : decision.events)
				{
					toWrite.add(new PendingRow(tablePath, event));
					tablesWithEvents.add(tablePath);
				}
			}
			catch (final RuntimeException ex)
			{
				if (pLog != null)
				{
					pLog.error("[mdm-factory] merge log watch: table " + tablePath + " of target " + pTargetId + " failed: " + ex, ex);
				}
			}
		}

		// the watermark only moves past a merge once its notices really are in the outbox: see MergeLogWatch.watermarksToPersist
		final boolean eventsWritten = toWrite.isEmpty() || MergeLogWatcher.writeOutboxRows(pSession, pMasterHome, pOutboxDataset, pTargetId, toWrite, pLog);
		final Map<String, Watermark> toPersist = MergeLogWatch.watermarksToPersist(watermarkByTable, decidedWatermarkByTable, tablesWithEvents, eventsWritten);
		if (MergeLogWatch.watermarksChanged(watermarkByTable, toPersist))
		{
			MergeLogStateStore.update(pSession, pFactory, pTargetId, new MergeLogStateStore.Edit()
			{
				@Override
				public void apply(final MergeLogState pState)
				{
					for (final Map.Entry<String, Watermark> entry : toPersist.entrySet())
					{
						pState.putWatermark(entry.getKey(), entry.getValue());
					}
				}
			}, pLog);
		}
	}

	/**
	 * Lot S2 (2026-10-18): records where the merge log stands for every table of the target BEFORE its first full send starts, as the target's
	 * {@code baseline} ({@link MergeLogState}). Without it the baseline is learnt at the watch's first pass, which comes after the send (and after the
	 * arming): a merge made while the send ran would be older than the watermark and never retired. A table that already has marks is left alone.
	 * Best effort - nothing here may stop the send.
	 */
	static void captureBaseline(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final String pMasterDatasetName,
		final Adaptation pTarget, final String pTargetId, final LoggingCategory pLog)
	{
		try
		{
			if (pMasterHome == null || pMasterDatasetName == null || pMasterDatasetName.trim().isEmpty())
			{
				return;
			}
			final Map<String, String> tables = TargetMappingList.parse(pTarget.getString(FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS));
			final MergeLogState stored = MergeLogStateStore.read(pTarget);
			final Map<String, Watermark> learnt = new LinkedHashMap<String, Watermark>();
			for (final String tablePath : tables.keySet())
			{
				if (stored.table(tablePath) != null)
				{
					continue;
				}
				final AdaptationTable mergingTable = MergeLogWatcher.metadatasetTable(pMasterHome, pMasterDatasetName, tablePath, "MergingProcess");
				if (mergingTable == null)
				{
					continue; // no matching on this table yet: the watch learns it at its first pass
				}
				learnt.put(tablePath, MergeLogWatch.decide(Watermark.none(), MergeLogWatcher.readMergeRows(mergingTable), null, "NOTICE", true).newWatermark);
			}
			if (!learnt.isEmpty())
			{
				MergeLogStateStore.update(pSession, pFactory, pTargetId, new MergeLogStateStore.Edit()
				{
					@Override
					public void apply(final MergeLogState pState)
					{
						for (final Map.Entry<String, Watermark> entry : learnt.entrySet())
						{
							if (pState.table(entry.getKey()) == null)
							{
								pState.putWatermark(entry.getKey(), entry.getValue());
							}
						}
					}
				}, pLog);
			}
		}
		catch (final RuntimeException ex)
		{
			if (pLog != null)
			{
				pLog.error("[mdm-factory] merge log baseline: target " + pTargetId + " could not record where the log stands before its first full send: " + ex, ex);
			}
		}
	}

	/** The candidate merge ids actually worth reading members for - see class Javadoc's "bounded reads" note. Mirrors (does not replace) {@code MergeLogWatch.decide}'s own new/newly-undone classification; harmless to over-include, {@code decide} is the sole source of truth for what is actually emitted. Empty when there is no watermark yet (a first pass never emits, so it never needs members either). */
	private static Set<Long> candidateIds(final Watermark pWatermark, final List<MergeRow> pMergeRows)
	{
		final Set<Long> out = new LinkedHashSet<Long>();
		if (pWatermark == null)
		{
			return out;
		}
		for (final MergeRow row : pMergeRows)
		{
			final Long id = Long.valueOf(row.mergeId);
			final boolean isNew = row.mergeId > pWatermark.lastMergeId;
			final boolean newlyUndone = row.undone && !pWatermark.undoneMergeIds.contains(id);
			if (isNew || newlyUndone)
			{
				out.add(id);
			}
		}
		return out;
	}

	/** One metadataset table ({@code MergingProcess}/{@code RecordMetadata}) for one master table - null (never an exception past this method) when the dataspace, the metadataset, or the table itself is unavailable, same discipline as {@link MameMetadatasetReads}. */
	static AdaptationTable metadatasetTable(final AdaptationHome pMasterHome, final String pMasterDatasetName, final String pTablePath, final String pTableLabel)
	{
		try
		{
			final String metadatasetName = MameMetadataset.name(pMasterDatasetName, pTablePath);
			if (metadatasetName == null)
			{
				return null;
			}
			final Adaptation metadataset = pMasterHome.findAdaptationOrNull(AdaptationName.forName(metadatasetName));
			// audit F-27: no metadataset = nothing to watch (quiet); a table or field that is not the expected one is reported loudly
			return MameMetadataGuard.table(metadataset, pTableLabel);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	static List<MergeRow> readMergeRows(final AdaptationTable pMergingTable)
	{
		final List<MergeRow> out = new ArrayList<MergeRow>();
		final RequestResult rows = pMergingTable.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object id = row.get(Path.parse("./id"));
				final Object undone = row.get(Path.parse("./isUnmerged"));
				if (id instanceof Number)
				{
					out.add(new MergeRow(((Number) id).longValue(), Boolean.TRUE.equals(undone)));
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** One {@code RequestResult} query per candidate merge id (predicate on {@code mergingProcessId}) - never a full-table scan of {@code RecordMetadata}, which can be as large as the business table itself. */
	private static Map<Long, List<Member>> readMembers(final AdaptationTable pRecordMetadataTable, final Set<Long> pCandidateIds)
	{
		final Map<Long, List<Member>> out = new LinkedHashMap<Long, List<Member>>();
		for (final Long mergeId : pCandidateIds)
		{
			final List<Member> members = new ArrayList<Member>();
			final RequestResult rows = pRecordMetadataTable.createRequestResult("./mergingProcessId=" + mergeId.longValue());
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					final String functionalId = row.getString(Path.parse("./functionalId"));
					final String state = MergeLogWatcher.stateName(row.get(Path.parse("./state")));
					final String goldenId = row.getString(Path.parse("./goldenId"));
					final Object autoCreated = row.get(Path.parse("./autoCreated"));
					if (functionalId != null)
					{
						members.add(new Member(functionalId, state, goldenId, Boolean.TRUE.equals(autoCreated)));
					}
				}
			}
			finally
			{
				rows.close();
			}
			out.put(mergeId, members);
		}
		return out;
	}

	/** {@code RecordMetadata.state}'s own numeric enum (0-4) to {@link RetireRules}'s plain string vocabulary - CLAUDE.md sec 8. Unknown/unreadable defaults to {@link RetireRules#STATE_UNSET} (never retires, never reinstates - the safe default). */
	static String stateName(final Object pRawState)
	{
		if (!(pRawState instanceof Number))
		{
			return RetireRules.STATE_UNSET;
		}
		switch (((Number) pRawState).intValue())
		{
			case MameMetadataContract.STATE_GOLDEN:
				return RetireRules.STATE_GOLDEN;
			case MameMetadataContract.STATE_MERGED:
				return RetireRules.STATE_MERGED;
			case MameMetadataContract.STATE_PENDING_APPROVAL:
				return RetireRules.STATE_PENDING_APPROVAL;
			case MameMetadataContract.STATE_DELETED:
				return RetireRules.STATE_DELETED;
			default:
				return RetireRules.STATE_UNSET;
		}
	}

	/** One outbox row still to write. Package-private: {@link MatchingStateSweeper} writes its notices through the same method. */
	static final class PendingRow
	{
		final String tablePath;
		final String change;
		final String recordKey;
		/** Null when none. */
		final String survivorKey;
		final String reason;
		/** The merge id as text; null when unknown. */
		final String mergeId;

		PendingRow(final String pTablePath, final String pChange, final String pRecordKey, final String pSurvivorKey, final String pReason, final String pMergeId)
		{
			this.tablePath = pTablePath;
			this.change = pChange;
			this.recordKey = pRecordKey;
			this.survivorKey = pSurvivorKey;
			this.reason = pReason;
			this.mergeId = pMergeId;
		}

		PendingRow(final String pTablePath, final OutEvent pEvent)
		{
			this(pTablePath, pEvent.change, pEvent.recordKey, pEvent.survivorKey, pEvent.reason, String.valueOf(pEvent.mergeId));
		}
	}

	/** True when the rows are in the outbox; false (logged) when the transaction failed, in which case none of them exists. */
	static boolean writeOutboxRows(final Session pSession, final AdaptationHome pMasterHome, final Adaptation pOutboxDataset, final String pTargetId,
		final List<PendingRow> pRows, final LoggingCategory pLog)
	{
		final AdaptationTable outboxTable = pOutboxDataset.getTable(FactoryPaths.DistributionOutbox.EVENT);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pMasterHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final Date now = new Date();
				for (final PendingRow pending : pRows)
				{
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(outboxTable);
					value.setValue(pTargetId, FactoryPaths.DistributionOutbox.TARGET);
					value.setValue(pending.tablePath, FactoryPaths.DistributionOutbox.TABLE_PATH);
					value.setValue(pending.recordKey, FactoryPaths.DistributionOutbox.RECORD_KEY);
					value.setValue(pending.change, FactoryPaths.DistributionOutbox.CHANGE);
					if (pending.survivorKey != null)
					{
						value.setValue(pending.survivorKey, FactoryPaths.DistributionOutbox.SURVIVOR_KEY);
					}
					value.setValue(FactoryPaths.DistributionOutbox.STATUS_PENDING, FactoryPaths.DistributionOutbox.STATUS);
					value.setValue(Integer.valueOf(0), FactoryPaths.DistributionOutbox.ATTEMPTS);
					value.setValue(now, FactoryPaths.DistributionOutbox.CREATED_AT);
					value.setValue(pending.reason, FactoryPaths.DistributionOutbox.REASON);
					if (pending.mergeId != null)
					{
						value.setValue(pending.mergeId, FactoryPaths.DistributionOutbox.MERGE_ID);
					}
					pContext.doCreateOccurrence(value, outboxTable);
				}
			}
		});
		if (result.hasFailed() && pLog != null)
		{
			pLog.error("[mdm-factory] merge log watch: could not write " + pRows.size() + " outbox row(s) for target " + pTargetId
				+ "; the marks stay where they were so the next pass retries: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		return !result.hasFailed();
	}
}
