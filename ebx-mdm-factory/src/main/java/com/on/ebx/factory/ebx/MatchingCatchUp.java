package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MatchingCatchUpSelection;
import com.on.ebx.factory.master.MatchingLoadPauseRule;
import com.on.ebx.factory.master.MatchingRunScope;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot MP (2026-10-04): the matching run that follows a master load which paused the add-on's matching on a table ({@link MatchingLoadPause}). A record written to
 * a PAUSED table was not matched - by the load, or by anyone else meanwhile (a data steward in the UI, another feed) - so once the matching is resumed this
 * finds those records and runs the matching on them:
 * <ol>
 * <li><b>The set</b>: every master record whose EBX last-modification time is at or after the instant the pause started, plus every record with no row in the
 * add-on's metadataset ({@code RecordMetadata}: never matched), a record merged away excluded - ONE streaming read of the master table and ONE of the
 * metadataset's functional ids, no per-row lookup ({@link MatchingCatchUpSelection} holds the pure rule).</li>
 * <li><b>The run</b>: nothing when the set is empty; up to {@link MatchingLoadPauseRule#CATCHUP_ENUMERATED_MAX} records, scoped runs on explicit key predicates,
 * {@link MatchingLoadPauseRule#CATCHUP_CHUNK_SIZE} keys each ({@link MatchingRunScope#all} with {@code pWholeTableBelongsToProject=false}, the numeric key rendered
 * unquoted - lot R9); above that, ONE run on the whole table (the existing "all" scope): a bulk load touched too much to enumerate. Every run is {@link
 * MameMatchingApi#launchScoped}, the call {@code MatchingConfigurator.runInternal} makes, and {@link GoldenKeyRefresher#refresh} follows, so {@code Xref.goldenKey}
 * is refreshed the same way.</li>
 * </ol>
 * <b>Why it does not call {@code MatchingConfigurator}</b>: its per-entity code is the body of one long loop inside {@code runInternal} (every entity, the
 * scope derived from {@code _loadId} or "all", one {@code MATCHING_RUN} step per entity feeding the wizard's results block) - there is no entry point taking an
 * entity and a predicate, and that class belongs to another lot. The few lines this needs are copied here (the primary-key field of the table). The catch-up is
 * not a {@code MATCHING_RUN} step either: it is recorded in the {@code MASTER_LOAD} step's detail ({@code matchingPause}), and the wizard's matching results
 * refresh at the next matching run.
 * <p>
 * Never throws: a catch-up that fails is reported in the returned node (status {@code FAILED} and why) and never fails the load that already succeeded.
 */
final class MatchingCatchUp
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int TICK_EVERY = 20000;

	private MatchingCatchUp()
	{
	}

	static ObjectNode run(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityName, final Repository pRepository,
		final String pMasterDataspaceKey, final String pMasterDatasetName, final Date pPauseStartedAt, final FactoryJobs.Handle pHandle)
	{
		final ObjectNode out = MatchingCatchUp.MAPPER.createObjectNode();
		final String tablePath = MasterModel.tablePath(pEntityName);
		try
		{
			final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
			final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pMasterDatasetName));
			if (dataset == null)
			{
				throw EngineRefusal.coded("missing.masterDatasetPath", "Master dataset " + pMasterDataspaceKey + "/" + pMasterDatasetName + " could not be found.", pMasterDataspaceKey + "/" + pMasterDatasetName);
			}
			final AdaptationTable table = dataset.getTable(Path.parse(tablePath));

			// 1. the set - one read of the metadataset's keys, one of the master table
			final boolean[] stopped = new boolean[1];
			final MameMetadatasetReads.ScanTick tick = MatchingCatchUp.tick(pHandle, stopped);
			final long start = System.currentTimeMillis();
			final MameMetadatasetReads.RecordMetadataKeys known = MameMetadatasetReads.readRecordMetadataKeys(pRepository, pMasterDataspaceKey, pMasterDatasetName, tablePath,
				tick);
			if (stopped[0])
			{
				return MatchingCatchUp.stopped(out);
			}
			final MatchingCatchUpSelection selection = new MatchingCatchUpSelection(pPauseStartedAt.getTime(), known == null ? null : known.all,
				known == null ? null : known.merged, MatchingLoadPauseRule.CATCHUP_ENUMERATED_MAX);
			if (!MatchingCatchUp.scanMaster(table, selection, tick))
			{
				return MatchingCatchUp.stopped(out);
			}
			out.put("scanMs", System.currentTimeMillis() - start);
			out.put("masterRecordsRead", selection.scanned());
			out.put("selected", selection.selected());
			out.put("modifiedSincePause", selection.modifiedInWindow());
			if (selection.neverMatchedKnown())
			{
				out.put("neverMatched", selection.neverMatched());
			}
			out.put("skippedMerged", selection.skippedMerged());
			final MatchingLoadPauseRule.CatchUpMode mode = selection.mode();
			out.put("mode", mode.name());
			if (!selection.neverMatchedKnown())
			{
				out.put("note", "The add-on's metadataset could not be read: only the records modified since the pause were selected, never-matched ones were not looked for.");
			}
			if (mode == MatchingLoadPauseRule.CatchUpMode.NONE)
			{
				out.put("status", "NOTHING_TO_MATCH");
				return out;
			}

			// 2. the run(s)
			final String primaryKeyField = MatchingCatchUp.primaryKeyFieldOf(table, tablePath);
			final long maxWaitMs = pHandle != null ? MatchingConfigurator.UNBOUNDED_WAIT_MS : MatchingConfigurator.DEFAULT_MAX_WAIT_MS;
			final ArrayNode runs = out.putArray("runs");
			boolean anyDone = false;
			String failure = null;
			boolean wasStopped = false;
			if (mode == MatchingLoadPauseRule.CatchUpMode.ENUMERATED)
			{
				for (final List<String> chunk : MatchingLoadPauseRule.chunks(selection.keys(), MatchingLoadPauseRule.CATCHUP_CHUNK_SIZE))
				{
					if (pHandle != null && pHandle.cancelled())
					{
						wasStopped = true;
						break;
					}
					final MatchingRunScope.Decision scope = MatchingRunScope.all(chunk, false, primaryKeyField, MatchingRunScope.KeyType.NUMERIC);
					failure = MatchingCatchUp.launch(pSession, table, scope, pEntityName, chunk.size(), maxWaitMs, pHandle, runs);
					anyDone |= failure == null;
					if (failure != null)
					{
						break;
					}
				}
			}
			else
			{
				// too many records to enumerate: the existing "all" scope, osd:is-not-null(./<pk>) - the master dataspace of a project belongs to that project alone
				final MatchingRunScope.Decision scope = MatchingRunScope.all(selection.keys(), true, primaryKeyField, MatchingRunScope.KeyType.NUMERIC);
				failure = MatchingCatchUp.launch(pSession, table, scope, pEntityName, selection.selected(), maxWaitMs, pHandle, runs);
				anyDone = failure == null;
			}
			out.put("runCount", runs.size());
			if (anyDone)
			{
				final GoldenKeyRefresher.Result refresh = GoldenKeyRefresher.refresh(pSession, pFactory, pProjectId, tablePath, pMasterDataspaceKey, pMasterDatasetName);
				refresh.describe(out.putObject("goldenKeyRefresh")); // lot V3-M3: + scanMs, readMode (the refresh now reads by scans)
			}
			if (failure != null)
			{
				out.put("status", "FAILED");
				out.put("error", failure);
			}
			else
			{
				out.put("status", wasStopped ? "STOPPED" : "DONE");
				if (wasStopped)
				{
					out.put("note", "Stopped before every run was launched: the records of the runs not launched stay unmatched.");
				}
			}
			return out;
		}
		catch (final OperationException ex)
		{
			return MatchingCatchUp.failed(out, pEntityName, ex.getMessage(), ex);
		}
		catch (final RuntimeException ex)
		{
			return MatchingCatchUp.failed(out, pEntityName, ex.getClass().getName() + ": " + ex.getMessage(), ex);
		}
		catch (final LinkageError ex)
		{
			return MatchingCatchUp.failed(out, pEntityName, "The Match and Merge Java API is not available on this instance (add-on not installed?): " + ex, ex);
		}
	}

	/** One scoped run; null when it finished DONE, else the reason it did not (the loop stops there: the add-on refuses a second run while one is still going). */
	private static String launch(final Session pSession, final AdaptationTable pTable, final MatchingRunScope.Decision pScope, final String pEntityName,
		final long pRecords, final long pMaxWaitMs, final FactoryJobs.Handle pHandle, final ArrayNode pRuns)
	{
		final ObjectNode run = pRuns.addObject();
		run.put("records", pRecords);
		if (!pScope.scoped)
		{
			run.put("status", "REFUSED");
			run.put("error", pScope.refusal);
			return pScope.refusal;
		}
		run.put("predicate", pScope.xpathPredicate.length() > 300 ? pScope.xpathPredicate.substring(0, 300) + "..." : pScope.xpathPredicate);
		try
		{
			final MameMatchingApi.RunResult result = MameMatchingApi.launchScoped(pSession, pTable, pScope.xpathPredicate, pEntityName, pMaxWaitMs, pHandle);
			run.put("status", result.status);
			run.put("durationMs", result.durationMs);
			if (!result.done())
			{
				final String why = "the matching run ended " + result.status + (pHandle != null && pHandle.cancelled() ? " (stopped)" : "");
				run.put("error", why);
				return why;
			}
			return null;
		}
		catch (final OperationException ex)
		{
			run.put("status", "REFUSED");
			run.put("error", ex.getMessage());
			return ex.getMessage();
		}
	}

	private static boolean scanMaster(final AdaptationTable pTable, final MatchingCatchUpSelection pSelection, final MameMetadatasetReads.ScanTick pTick)
	{
		final RequestResult rows = pTable.createRequestResult(null);
		try
		{
			long count = 0;
			for (Adaptation record = rows.nextAdaptation(); record != null; record = rows.nextAdaptation())
			{
				final Date modified = record.getTimeOfLastModification();
				pSelection.accept(record.getOccurrencePrimaryKey().format(), modified == null ? -1L : modified.getTime());
				if (++count % MatchingCatchUp.TICK_EVERY == 0 && !pTick.keepGoing(count))
				{
					return false;
				}
			}
			return true;
		}
		finally
		{
			rows.close();
		}
	}

	private static MameMetadatasetReads.ScanTick tick(final FactoryJobs.Handle pHandle, final boolean[] pStopped)
	{
		return new MameMetadatasetReads.ScanTick()
		{
			@Override
			public boolean keepGoing(final long pRowsRead)
			{
				if (pHandle != null && pHandle.cancelled())
				{
					pStopped[0] = true;
					return false;
				}
				return true;
			}
		};
	}

	private static ObjectNode stopped(final ObjectNode pOut)
	{
		pOut.put("status", "STOPPED");
		pOut.put("note", "Stopped while looking for the records to match: nothing was matched.");
		return pOut;
	}

	private static ObjectNode failed(final ObjectNode pOut, final String pEntityName, final String pMessage, final Throwable pCause)
	{
		LoggingCategory.getKernel().error("[mdm-factory] matching catch-up after the master load failed for entity " + pEntityName + ": " + pMessage, pCause);
		pOut.put("status", "FAILED");
		pOut.put("error", pMessage);
		return pOut;
	}

	/** The first field of the table's primary key, relative to the record (copied from {@code MatchingConfigurator}, where it is private). */
	private static String primaryKeyFieldOf(final AdaptationTable pMasterTable, final String pTablePath) throws OperationException
	{
		final Path[] primaryKeySpec = pMasterTable == null ? null : pMasterTable.getPrimaryKeySpec();
		if (primaryKeySpec == null || primaryKeySpec.length == 0)
		{
			throw OperationException.createError("Could not read the primary key of " + pTablePath + ": getPrimaryKeySpec() answered nothing.");
		}
		final String formatted = primaryKeySpec[0].format();
		return formatted.startsWith("/") ? formatted.substring(1) : formatted;
	}
}
