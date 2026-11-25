package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.master.GoldenKeyRefreshDecision;
import com.on.ebx.factory.master.GoldenKeyScanPlan;
import com.on.ebx.factory.master.MameMetadataset;
import com.on.ebx.factory.master.ScanBudget;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.transfer.BatchRanges;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The one class that refreshes {@code Xref.goldenKey} (`docs/MATCHING.md` &sect;4, `docs/MASTER-ZONE.md`
 * &sect;3.4) from the add-on's own record state - every actual decision is {@link GoldenKeyRefreshDecision}
 * (pure, unit-tested); this class only isolates the EBX reads/writes around it, the same split {@code
 * MasterLoader}/{@code MasterUpsertDecision} already use.
 * <p>
 * <b>Public API, not a metadataset read (refactor, 2026-09-24, CLAUDE.md &sect;9 "use the public API
 * wherever it can do the job").</b> Until this fix the class looked a row up directly in the add-on's
 * {@code RecordMetadata} metadataset table by the master record's own formatted key and read its {@code
 * goldenId} field. That depended on {@code MameMetadataset}'s naming convention (itself only measured live
 * 2026-09-22) AND on {@code RecordMetadata}'s primary key format matching the master record's own - two
 * undocumented assumptions this table no longer needs. It now resolves the master record itself
 * ({@code masterTable.lookupAdaptationByPrimaryKey}, the same call {@code ebx.RecordLineageReader} already
 * uses for the identical lookup) and asks {@link MameRecordState#read} for its state and golden - {@code
 * MetadataHelper.getState}/{@code getTargetRecord}, the public API {@code MameRecordState} already wraps
 * for the sibling record-lineage reads. A record with no metadata at all ({@link
 * MameRecordState.Result#state} null, {@link MameRecordState.Result#addonPresent} true) or the add-on itself
 * missing ({@code addonPresent} false) both read as "no metadata" here - {@link GoldenKeyRefreshDecision}
 * then leaves an already-blank {@code goldenKey} alone and clears a stale one, never guesses, same as
 * before.
 * <p>
 * Called by {@code ebx.MatchingConfigurator#run} after the scoped matching-execution call, for every
 * {@code Xref} row of the project whose {@code masterTable} is the entity's own master table and whose
 * {@code masterKey} is set (a source record that actually reached master) - never for a row still awaiting
 * a master transfer.
 * <p>
 * <b>Two entry points, genuinely different strategies since volume lot 2a (2026-09-28, docs/VOLUME.md, task
 * 5) - the coordinator verified live that a declared {@code <index>} is a no-op on EBX 6.3 (javap,
 * {@code CfTableFunction.setIndex} discards the value), so the primary key is the only indexed access this
 * schema offers:</b>
 * <ul>
 * <li>{@link #refresh} (whole entity - the on-demand/scheduled project-wide refresh, and the post-matching-run
 * call) - since lot V3-M3 (below) it works by two scans and writes only what changes; the per-record path
 * described next is its fallback. That path stays a streamed predicate scan restricted to {@code project}/{@code masterTable} (there is no PK to
 * derive from "every row of this entity" - a genuine linear pass is the correct shape here) - but never
 * collects a {@code List<Adaptation>} any more: the scan collects lightweight PRIMARY KEY strings only, fully
 * closes, then commits fix-ups in batches of {@link #BATCH_SIZE} rows, each its own {@link Procedure}, so a
 * huge project's refresh commits incrementally instead of one all-or-nothing transaction.</li>
 * <li>{@link #refreshScoped} (a handful of specific master keys - the suspect, the anchor, and every record
 * that now shares the resulting golden, from {@code ebx.RecordLineageReader#decideSuspect}) no longer scans
 * {@code Xref} by a {@code masterKey}-in-scope predicate at all: each key's own {@code Xref} row is DERIVED
 * ({@link XrefByMasterRecord}) from the master record's own {@code sourceSystem}/{@code sourceId}, an indexed
 * PK lookup on the new natural key {@code (project, stagingTable, stagingKey)}.</li>
 * </ul>
 * <p>
 * <b>Lot V3-M3 (2026-10-04, docs/VOLUME.md "Lot V3-M3"): {@link #refresh} reads by scans, not per record.</b> Per record it cost one {@code Xref} lookup, one master
 * record lookup and one add-on state read - about 50 s on 576,692 rows with nothing to change, and the first refresh after the merges part of an 18 min 49 matching
 * run. Now: ONE streaming read of the add-on's {@code RecordMetadata} ({@link MameMetadatasetReads#readRecordMetadataIndex}: state and golden of every record, a
 * few bytes a row), then ONE streaming read of the entity's {@code Xref} rows, each decided in memory by {@link GoldenKeyScanPlan} (the same {@link
 * GoldenKeyRefreshDecision} as before); only the rows whose {@code goldenKey} differs are looked up by key and written, in batches. The result says which path ran
 * ({@code readMode} SCAN or PER_RECORD, {@code readReason} when it is the fallback) and how long the two reads took ({@code scanMs}). The per-record path stays when
 * the property {@value com.on.ebx.factory.master.ScanBudget#GOLDEN_REFRESH_PROPERTY} is 0 or below the metadata row count, when the add-on's metadataset cannot be
 * read, or when a scan fails - a failure costs speed, never the refresh. {@link #refreshScoped} is unchanged (a handful of keys: derived lookups are cheaper).
 */
final class GoldenKeyRefresher
{
	/** {@link #refresh}'s own commit granularity (task 5: "commit in Procedures of 1000 rows") - fixed, not {@code Settings.TRANSFER_BATCH_SIZE} (a different knob, for the transfer engine's own batches). */
	private static final int BATCH_SIZE = 1000;

	private GoldenKeyRefresher()
	{
	}

	static final class Result
	{
		final int set;
		final int cleared;
		final int unchanged;
		/** Lot V3-M3: milliseconds spent reading (the two scans, or the key scan of the per-record path); 0 for {@link #refreshScoped}. */
		final long scanMs;
		/** Lot V3-M3: {@code SCAN} (two streaming reads, writes only what changes) or {@code PER_RECORD} (the lookups of lot 2a); null for {@link #refreshScoped}. */
		final String readMode;
		/** Lot V3-M3: why the per-record path ran instead of the scans; null in SCAN mode. */
		final String readReason;
		/** Lot IDX (2026-10-12): estimated heap of the {@code RecordMetadata} index at its peak (the compact digest table); 0 on the per-record path and for {@link #refreshScoped}. */
		final long indexBytes;

		Result(final int pSet, final int pCleared, final int pUnchanged)
		{
			this(pSet, pCleared, pUnchanged, 0L, null, null);
		}

		Result(final int pSet, final int pCleared, final int pUnchanged, final long pScanMs, final String pReadMode, final String pReadReason)
		{
			this(pSet, pCleared, pUnchanged, pScanMs, pReadMode, pReadReason, 0L);
		}

		Result(final int pSet, final int pCleared, final int pUnchanged, final long pScanMs, final String pReadMode, final String pReadReason, final long pIndexBytes)
		{
			this.indexBytes = pIndexBytes;
			this.set = pSet;
			this.cleared = pCleared;
			this.unchanged = pUnchanged;
			this.scanMs = pScanMs;
			this.readMode = pReadMode;
			this.readReason = pReadReason;
		}

		private static Result add(final Result pA, final Result pB)
		{
			return new Result(pA.set + pB.set, pA.cleared + pB.cleared, pA.unchanged + pB.unchanged, pA.scanMs + pB.scanMs, pA.readMode, pA.readReason,
				Math.max(pA.indexBytes, pB.indexBytes));
		}

		/** The counters as the node every caller records ({@code set}, {@code cleared}, {@code unchanged}, and since lot V3-M3 {@code scanMs}, {@code readMode}, {@code readReason}). */
		ObjectNode describe(final ObjectNode pNode)
		{
			pNode.put("set", this.set);
			pNode.put("cleared", this.cleared);
			pNode.put("unchanged", this.unchanged);
			if (this.readMode != null)
			{
				pNode.put("scanMs", this.scanMs);
				pNode.put("readMode", this.readMode);
				if (this.indexBytes > 0L)
				{
					pNode.put("indexBytes", this.indexBytes);
				}
				if (this.readReason != null)
				{
					pNode.put("readReason", this.readReason);
				}
			}
			return pNode;
		}
	}

	/**
	 * Whole-entity refresh by scans (lot V3-M3, class Javadoc): the add-on's {@code RecordMetadata} read once into memory, the entity's {@code Xref} rows streamed
	 * once and decided against it, only the rows that change looked up and written (batches of {@link #BATCH_SIZE}, each its own {@link Procedure}). Falls back to
	 * {@link #refreshPerRecord} - same decision, one random read set per row - when the scans cannot run, and says why in {@link Result#readReason}.
	 */
	static Result refresh(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pMasterTablePath,
		final String pMasterDataspaceKey, final String pMasterDatasetName) throws OperationException
	{
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationTable masterTable = GoldenKeyRefresher.lookupMasterTable(repository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath);
		final AdaptationTable xrefTable = pFactory.getTable(FactoryPaths.XREF);
		final String predicate = GoldenKeyRefresher.xrefPredicate(pProjectId, pMasterTablePath);

		final long start = System.currentTimeMillis();
		String reason = null;
		GoldenKeyScanPlan plan = null;
		long indexBytes = 0L;
		final long max = ScanBudget.currentGoldenRefresh();
		if (max <= 0L)
		{
			reason = ScanBudget.goldenRefreshRefusal(max, 0L);
		}
		else if (masterTable == null)
		{
			reason = "the master table " + pMasterTablePath + " could not be resolved";
		}
		else if (!MameRecordState.get().read(null).addonPresent)
		{
			reason = "the Match and Merge Java API is not available";
		}
		else
		{
			final MameMetadatasetReads.MetadataIndex metadata = MameMetadatasetReads.readRecordMetadataIndex(repository, pMasterDataspaceKey, pMasterDatasetName,
				pMasterTablePath, max, null);
			if (metadata.index == null)
			{
				reason = metadata.reason;
			}
			else
			{
				plan = new GoldenKeyScanPlan(metadata.index);
				indexBytes = metadata.index.estimatedBytes();
			}
		}
		if (plan != null)
		{
			try
			{
				GoldenKeyRefresher.scanXref(xrefTable, predicate, plan);
			}
			catch (final RuntimeException ex)
			{
				LoggingCategory.getKernel().warn("[mdm-factory] golden-key refresh of " + pMasterTablePath + ": the Xref scan failed, using the per-record path: " + ex);
				reason = "the Xref scan failed: " + ex;
				plan = null;
			}
		}
		if (plan == null)
		{
			final Result perRecord = GoldenKeyRefresher.refreshPerRecord(pSession, pFactory, xrefTable, masterTable, predicate, pMasterTablePath);
			return new Result(perRecord.set, perRecord.cleared, perRecord.unchanged, perRecord.scanMs, "PER_RECORD", reason);
		}
		final long scanMs = System.currentTimeMillis() - start;

		int set = 0;
		int cleared = 0;
		for (final BatchRanges.Range range : BatchRanges.of(plan.writeCount(), GoldenKeyRefresher.BATCH_SIZE))
		{
			final Result batch = GoldenKeyRefresher.writeBatch(pSession, pFactory, xrefTable, plan, range.start, range.end, pMasterTablePath);
			set += batch.set;
			cleared += batch.cleared;
		}
		return new Result(set, cleared, plan.unchanged(), scanMs, "SCAN", null, indexBytes);
	}

	private static String xrefPredicate(final String pProjectId, final String pMasterTablePath)
	{
		return "./project=" + CatalogReader.quote(pProjectId) + " and ./masterTable=" + CatalogReader.quote(pMasterTablePath) + " and osd:is-not-null(./masterKey)";
	}

	/** The one streaming read of the entity's {@code Xref} rows: each is decided at once, nothing is kept for an unchanged row. */
	private static void scanXref(final AdaptationTable pXrefTable, final String pPredicate, final GoldenKeyScanPlan pPlan)
	{
		final RequestResult rows = pXrefTable.createRequestResult(pPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				pPlan.accept(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.Xref.MASTER_KEY), row.getString(FactoryPaths.Xref.GOLDEN_KEY));
			}
		}
		finally
		{
			rows.close();
		}
	}

	/** One commit of the writes {@code [pFrom, pTo)} of the plan: each row read by its key (only the rows that change are read at all), then set or cleared. */
	private static Result writeBatch(final Session pSession, final Adaptation pFactory, final AdaptationTable pXrefTable, final GoldenKeyScanPlan pPlan, final int pFrom,
		final int pTo, final String pMasterTablePath) throws OperationException
	{
		final int[] set = new int[1];
		final int[] cleared = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (int i = pFrom; i < pTo; i++)
				{
					final Adaptation xrefRow = pXrefTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pPlan.writeKey(i)));
					if (xrefRow == null)
					{
						continue; // deleted meanwhile - defensive
					}
					final String wanted = pPlan.writeValue(i);
					final ValueContextForUpdate value = pContext.getContext(xrefRow.getAdaptationName());
					value.setValue(wanted, FactoryPaths.Xref.GOLDEN_KEY);
					pContext.doModifyContent(xrefRow, value);
					if (wanted == null)
					{
						cleared[0]++;
					}
					else
					{
						set[0]++;
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.refreshGoldenKey", "Could not refresh Xref.goldenKey for " + pMasterTablePath + ": " + result.getExceptionFullMessage(Locale.ENGLISH), pMasterTablePath);
		}
		return new Result(set[0], cleared[0], 0);
	}

	/**
	 * The per-record path (lot 2a, class Javadoc): the fallback of {@link #refresh}. Never collects a {@code List<Adaptation>}: only PRIMARY KEY strings are held
	 * in memory, and the fix-up itself commits in batches of {@link #BATCH_SIZE}, each its own {@link Procedure}.
	 */
	private static Result refreshPerRecord(final Session pSession, final Adaptation pFactory, final AdaptationTable pXrefTable, final AdaptationTable pMasterTable,
		final String pPredicate, final String pMasterTablePath) throws OperationException
	{
		final long start = System.currentTimeMillis();
		final List<String> xrefKeys = new ArrayList<String>();
		final RequestResult rows = pXrefTable.createRequestResult(pPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				xrefKeys.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		final long scanMs = System.currentTimeMillis() - start;

		Result total = new Result(0, 0, 0);
		for (final BatchRanges.Range range : BatchRanges.of(xrefKeys.size(), GoldenKeyRefresher.BATCH_SIZE))
		{
			final List<String> batchKeys = xrefKeys.subList(range.start, range.end);
			total = Result.add(total, GoldenKeyRefresher.refreshBatch(pSession, pFactory, pXrefTable, pMasterTable, pMasterTablePath, batchKeys));
		}
		return new Result(total.set, total.cleared, total.unchanged, scanMs, "PER_RECORD", null);
	}

	/**
	 * Scoped variant of {@link #refresh} (class Javadoc, "Two entry points") - restricted to {@code
	 * pMasterKeys}, each resolved to its own {@code Xref} row by DERIVING the natural key from the master
	 * record itself ({@link XrefByMasterRecord}), never a predicate scan. Answers a zero {@link Result} when
	 * {@code pMasterKeys} is empty, or when the master/staging table cannot be resolved at all (matching not
	 * activated yet, or the project's staging zone is gone) - an empty/undeliverable scope must never fall back
	 * to the unscoped, whole-table {@link #refresh}.
	 */
	static Result refreshScoped(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pMasterTablePath,
		final String pMasterDataspaceKey, final String pMasterDatasetName, final Set<String> pMasterKeys) throws OperationException
	{
		if (pMasterKeys == null || pMasterKeys.isEmpty())
		{
			return new Result(0, 0, 0);
		}
		final AdaptationTable masterTable = GoldenKeyRefresher.lookupMasterTable(pFactory.getHome().getRepository(), pMasterDataspaceKey, pMasterDatasetName,
			pMasterTablePath);
		final String entityName = MameMetadataset.lastPathStep(pMasterTablePath);
		final AdaptationTable stagingTable = GoldenKeyRefresher.lookupStagingTable(pFactory, pProjectId, entityName);
		if (masterTable == null || stagingTable == null || entityName == null)
		{
			return new Result(0, 0, 0);
		}

		final AdaptationTable xrefTable = pFactory.getTable(FactoryPaths.XREF);
		final List<String> keys = new ArrayList<String>(pMasterKeys);
		final int[] set = new int[1];
		final int[] cleared = new int[1];
		final int[] unchanged = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final String masterKey : keys)
				{
					final Adaptation xrefRow = XrefByMasterRecord.lookup(pFactory, pProjectId, entityName, masterTable, stagingTable, masterKey);
					if (xrefRow == null)
					{
						continue; // no Xref row for this master key (e.g. an add-on auto-created golden) - nothing to refresh
					}
					final Adaptation masterRecord = masterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(masterKey));
					final MameRecordState.Result state = MameRecordState.get().read(masterRecord);
					final boolean hasMetadata = state.addonPresent && state.state != null;
					final String goldenId = "MERGED".equals(state.state) ? state.goldenKey : null;
					final String currentGoldenKey = xrefRow.getString(FactoryPaths.Xref.GOLDEN_KEY);
					final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(hasMetadata, goldenId, masterKey, currentGoldenKey);
					if (decision.action == GoldenKeyRefreshDecision.Action.NO_CHANGE)
					{
						unchanged[0]++;
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(xrefRow.getAdaptationName());
					value.setValue(decision.action == GoldenKeyRefreshDecision.Action.SET ? decision.newGoldenKey : null, FactoryPaths.Xref.GOLDEN_KEY);
					pContext.doModifyContent(xrefRow, value);
					if (decision.action == GoldenKeyRefreshDecision.Action.SET)
					{
						set[0]++;
					}
					else
					{
						cleared[0]++;
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.refreshGoldenKey", "Could not refresh Xref.goldenKey for " + pMasterTablePath + ": " + result.getExceptionFullMessage(Locale.ENGLISH), pMasterTablePath);
		}
		return new Result(set[0], cleared[0], unchanged[0]);
	}

	/** One {@link #BATCH_SIZE}-sized commit of {@link #refresh}'s own fix-up - re-reads every row fresh by primary key (the {@code RequestResult} that discovered {@code pBatchKeys} is long closed by the time this runs). */
	private static Result refreshBatch(final Session pSession, final Adaptation pFactory, final AdaptationTable pXrefTable, final AdaptationTable pMasterTable,
		final String pMasterTablePath, final List<String> pBatchKeys) throws OperationException
	{
		final int[] set = new int[1];
		final int[] cleared = new int[1];
		final int[] unchanged = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final String key : pBatchKeys)
				{
					final Adaptation xrefRow = pXrefTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key));
					if (xrefRow == null)
					{
						continue; // deleted meanwhile - defensive
					}
					final String masterKey = xrefRow.getString(FactoryPaths.Xref.MASTER_KEY);
					final Adaptation masterRecord = pMasterTable == null || masterKey == null ? null
						: pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(masterKey));
					final MameRecordState.Result state = MameRecordState.get().read(masterRecord);
					final boolean hasMetadata = state.addonPresent && state.state != null;
					final String goldenId = "MERGED".equals(state.state) ? state.goldenKey : null;
					final String currentGoldenKey = xrefRow.getString(FactoryPaths.Xref.GOLDEN_KEY);
					final GoldenKeyRefreshDecision.Decision decision = GoldenKeyRefreshDecision.decide(hasMetadata, goldenId, masterKey, currentGoldenKey);
					if (decision.action == GoldenKeyRefreshDecision.Action.NO_CHANGE)
					{
						unchanged[0]++;
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(xrefRow.getAdaptationName());
					value.setValue(decision.action == GoldenKeyRefreshDecision.Action.SET ? decision.newGoldenKey : null, FactoryPaths.Xref.GOLDEN_KEY);
					pContext.doModifyContent(xrefRow, value);
					if (decision.action == GoldenKeyRefreshDecision.Action.SET)
					{
						set[0]++;
					}
					else
					{
						cleared[0]++;
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.refreshGoldenKey", "Could not refresh Xref.goldenKey for " + pMasterTablePath + ": " + result.getExceptionFullMessage(Locale.ENGLISH), pMasterTablePath);
		}
		return new Result(set[0], cleared[0], unchanged[0]);
	}

	/**
	 * The master record's own business table - {@code masterDataset.getTable(masterTablePath)}, the same
	 * lookup {@code ebx.RecordLineageReader} already uses to resolve an anchor/suspect record. Null on any
	 * failure (dataset not found, table not yet published, ...) - every row then reads "no metadata" through
	 * {@link MameRecordState}, see class Javadoc.
	 */
	private static AdaptationTable lookupMasterTable(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath)
	{
		try
		{
			final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
			final Adaptation masterDataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pMasterDatasetName));
			return masterDataset == null ? null : masterDataset.getTable(Path.parse(pMasterTablePath));
		}
		catch (final RuntimeException ex)
		{
			return null; // dataset/table not found (or matching not activated yet) - every row then reads "no metadata", see class Javadoc
		}
	}

	/**
	 * The project's own STAGING table for {@code pEntityName} - needed only by {@link #refreshScoped} to derive
	 * an {@code Xref} primary key ({@link XrefByMasterRecord}). Null on any failure (project not found, no
	 * staging zone yet, staging dataset/table gone) - {@link #refreshScoped} then answers a zero {@link Result}
	 * rather than guessing.
	 */
	private static AdaptationTable lookupStagingTable(final Adaptation pFactory, final String pProjectId, final String pEntityName)
	{
		if (pEntityName == null)
		{
			return null;
		}
		try
		{
			final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
			if (project == null)
			{
				return null;
			}
			final String stagingDataspaceKey = project.getString(FactoryPaths.Project.STAGING_DATASPACE);
			final String stagingDatasetName = project.getString(FactoryPaths.Project.STAGING_DATASET);
			if (stagingDataspaceKey == null || stagingDataspaceKey.trim().isEmpty() || stagingDatasetName == null || stagingDatasetName.trim().isEmpty())
			{
				return null;
			}
			final Repository repository = pFactory.getHome().getRepository();
			final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(stagingDataspaceKey));
			final Adaptation stagingDataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(stagingDatasetName));
			return stagingDataset == null ? null : stagingDataset.getTable(Path.parse(StagingModel.tablePath(pEntityName)));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}
}
