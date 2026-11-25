package com.on.ebx.factory.ebx;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.index.KeyValueIndex;
import com.on.ebx.factory.master.GoldenKeyScanPlan;
import com.on.ebx.factory.master.MameMetadataContract;
import com.on.ebx.factory.master.MameMetadataset;
import com.on.ebx.factory.master.ScanBudget;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;

/**
 * <b>The only class of this module allowed to read the Match and Merge add-on's own metadataset tables
 * directly</b> (refactor, 2026-09-24, CLAUDE.md &sect;9 "use the PUBLIC Match and Merge Java API wherever it
 * can do the job"). Every other EBX-facing class that needs a record's state, group, or golden now goes
 * through {@link MameRecordState}/{@link MameGroups}/{@link MameSuspectDecision} (the add-on's public API,
 * {@code com.orchestranetworks.addon.mame.common.util.metadata.MetadataHelper} and {@code
 * com.orchestranetworks.addon.mame.service.*}). The three reads gathered here have no public equivalent as of
 * ON EBX 6.3.0-dev:
 * <ul>
 * <li>{@link #readMergingProcessId} - {@code RecordMetadata.mergingProcessId}, the golden's own merge-run
 * identifier. No accessor exists on {@code MetadataHelper} or {@code Group}/{@code MergeProcess} for it -
 * {@code MergeProcess} (returned by a live {@code mergeGroup} call, {@link MameSuspectDecision}) only exposes
 * the resulting golden record, not a re-readable process id for a group examined later.</li>
 * <li>{@link #readSuspectedProcessId} - {@code Suspected.matchingProcessId}, which matching RUN produced a
 * given (anchor, suspect) pair. {@code Group}/{@code GroupOperations} expose the current suspect list, not
 * which run proposed a specific pair, nor a score.</li>
 * <li>{@link #resolveMergeValueLineageTable} - the {@code MergeValueLineageCompact} table {@code
 * FieldMergeLineageSource} reads for field-by-field, post-merge survivorship (which contributing record's
 * value was retained for each field, and by what rule). No public API returns per-field merge lineage at all -
 * {@code MergeProcess}/{@code Group} answer only the resulting golden record, never a field-level trace.</li>
 * </ul>
 * <b>An evolution request covering all three was sent to the editor</b> (lineage per field, merge history of a
 * golden, suspect run details/score) - until ON EBX ships a public accessor for any of them, these reads stay
 * here, isolated from the rest of the module the same way {@link MameRecordState}/{@link MameGroups} isolate
 * the public API calls. They depend on the metadataset's own naming convention ({@link MameMetadataset}, one
 * per business TABLE, measured live 2026-09-22) and on its UNDOCUMENTED internal table/field layout (read
 * live off a real instance, not from any published schema) - both could change on a future add-on build with
 * no warning. Every method here degrades to null/false on any failure (missing metadataset, missing table,
 * missing row, wrong field type), never an exception reaching a caller - the same discipline {@link
 * MameRecordState}/{@link MameGroups} already apply to the public-API reads.
 * <p>
 * <b>A fourth read, added by master-load lot V3-M2 (2026-10-04)</b>: {@link #readMergedFunctionalIds}, the keys of every MERGED record of a
 * table in ONE streaming request - what {@code ebx.MasterLoader} used to ask {@code MetadataHelper.getState} for once per staging row (a
 * random read of the master record, then of the metadataset). It reads the same fact ({@code RecordMetadata.state}, the add-on's own
 * enumeration key 2 = {@code MERGED}) from the same place the public call reads it, in bulk.
 * <p>
 * <p>
 * <b>Lot V3-M3 (2026-10-04)</b> adds the golden record's key to the fourth read ({@link #readMergedGoldens}) and a sixth read, {@link #readRecordMetadataIndex} (every row's state and golden).
 * <p>
 * <b>A fifth read, added by lot MP (2026-10-04)</b>: {@link #readRecordMetadataKeys}, the {@code functionalId} of every {@code RecordMetadata} row (and which are
 * MERGED) in ONE streaming request - what the catch-up after a matching pause compares the master table with to find the records never matched.
 * <p>
 * Callers: {@code ebx.RecordLineageReader} (merging-process id for the lineage screen and for {@code
 * FieldMergeLineageSource}, a suspect's own matching-process id for the group display) and {@code
 * FieldMergeLineageSource} itself (the compact table, resolved here rather than duplicating the metadataset
 * lookup - the reflective bean reading of its rows stays in {@code FieldMergeLineageSource}, only the
 * table/row lookup moved).
 */
final class MameMetadatasetReads
{
	private MameMetadatasetReads()
	{
	}

	/** The add-on's metadataset itself (the dataset, not a table of it) - see {@link MameMetadataset}'s own Javadoc for the naming convention. Null (never an exception) whenever the dataspace, the metadataset, or the naming inputs are unavailable. */
	private static Adaptation resolveMetadataset(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath)
	{
		try
		{
			final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
			final String name = MameMetadataset.name(pMasterDatasetName, pMasterTablePath);
			return home == null || name == null ? null : home.findAdaptationOrNull(AdaptationName.forName(name));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * One table of the add-on's metadataset ({@code RecordMetadata}, {@code Suspected}, {@code
	 * MergeValueLineageCompact}, ...) - a plain {@code Adaptation}/{@code AdaptationTable} read, no add-on
	 * class involved, so it never throws {@link LinkageError}; a missing metadataset or table degrades to
	 * null, never an error.
	 */
	private static AdaptationTable resolveMetadatasetTable(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath, final String pTableName)
	{
		try
		{
			// audit F-27: no metadataset = matching never activated (quiet); a metadataset whose table or fields are not the expected ones is reported loudly
			return MameMetadataGuard.table(MameMetadatasetReads.resolveMetadataset(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath), pTableName);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * {@code RecordMetadata.mergingProcessId}, keyed by {@code functionalId} (the record's own formatted
	 * primary key) - best-effort: null whenever the metadataset/table is unavailable, no row exists, or the
	 * value is not a number.
	 */
	static Integer readMergingProcessId(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pMasterTablePath,
		final String pFunctionalId)
	{
		if (pFunctionalId == null)
		{
			return null;
		}
		final AdaptationTable table = MameMetadatasetReads.resolveMetadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath,
			"RecordMetadata");
		if (table == null)
		{
			return null;
		}
		try
		{
			final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pFunctionalId));
			if (row == null)
			{
				return null;
			}
			final Object raw = MameMetadatasetReads.safeGet(row, "mergingProcessId");
			return raw instanceof Number ? Integer.valueOf(((Number) raw).intValue()) : null;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * Best-effort {@code Suspected.matchingProcessId} for the (anchor, suspect) pair - {@code
	 * MetadataTemplate.xsd} (read 2026-09-22, `webapps/ebx-addon-mame/WEB-INF/ebx/schema/metadata/
	 * MetadataTemplate.xsd`) gives {@code Suspected{id, sourceId, targetId, matchingProcessId}}, all plain
	 * strings/int - but WHICH of {@code sourceId}/{@code targetId} names the anchor vs. the suspect was never
	 * confirmed live (this session could not reach the instance): both orderings are tried, first match wins.
	 * Returns null (never an exception) whenever the metadataset/table is unavailable, no row matches either
	 * ordering, or the lookup fails. There is no {@code score} field on {@code Suspected} at all (confirmed
	 * from the same XSD read) - never attached.
	 */
	static String readSuspectedProcessId(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pMasterTablePath,
		final String pAnchorKey, final String pSuspectKey)
	{
		final AdaptationTable table = MameMetadatasetReads.resolveMetadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, "Suspected");
		if (table == null || table.getTableOccurrenceRootNode() == null)
		{
			return null;
		}
		try
		{
			final String predicate = "(./sourceId=" + CatalogReader.quote(pAnchorKey) + " and ./targetId=" + CatalogReader.quote(pSuspectKey) + ") or (./sourceId="
				+ CatalogReader.quote(pSuspectKey) + " and ./targetId=" + CatalogReader.quote(pAnchorKey) + ")";
			final RequestResult rows = table.createRequestResult(predicate);
			try
			{
				final Adaptation row = rows.nextAdaptation();
				if (row == null)
				{
					return null;
				}
				final Object raw = MameMetadatasetReads.safeGet(row, "matchingProcessId");
				return raw == null ? null : String.valueOf(raw);
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * The {@code MergeValueLineageCompact} table of the golden's own metadataset - {@code
	 * FieldMergeLineageSource#read} still reads its rows/{@code Items[]} reflectively (see that class' own
	 * Javadoc for why), only the table lookup itself moved here. Null when the metadataset/table is
	 * unavailable.
	 */
	static AdaptationTable resolveMergeValueLineageTable(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath)
	{
		return MameMetadatasetReads.resolveMetadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, "MergeValueLineageCompact");
	}

	/** {@code RecordMetadata.state} of a merged record: the add-on's {@code State} enumeration order, {@code UNSET}=0 {@code GOLDEN}=1 {@code MERGED}=2 {@code PENDING_APPROVAL}=3 {@code DELETED}=4 (MetadataTemplate.xsd, measured 2026-09-22/23). */
	private static final int STATE_MERGED = MameMetadataContract.STATE_MERGED;

	/** The scan of {@link #readMergedFunctionalIds} calls this every few thousand rows with the number of rows read so far; false stops it. */
	interface ScanTick
	{
		boolean keepGoing(long pRowsRead);
	}

	private static final int TICK_EVERY = 20000;

	/**
	 * The {@code functionalId} (the master record's formatted primary key) of every record of the table whose state is {@code MERGED}, read in ONE
	 * streaming request of the metadataset's {@code RecordMetadata} table restricted by {@code ./state=2}. Lot V3-M2 (2026-10-04): this replaces, for a
	 * master load, the per-row {@code MetadataHelper.getState} - which is exactly "the state of this record is MERGED", and nothing else counts as merged
	 * away (a DELETED record - an unmerged golden - or a PENDING_APPROVAL one is loaded as before).
	 * <p>
	 * Lot V3-M3 (2026-10-04): the keys of {@link #readMergedGoldens}, which reads the golden record's key in the same request - same contract (null when the
	 * metadataset, the table or one of the two fields does not exist, the scan failed or {@code pTick} stopped it; an empty set is a real answer).
	 */
	static Set<String> readMergedFunctionalIds(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath, final ScanTick pTick)
	{
		final Map<String, String> merged = MameMetadatasetReads.readMergedGoldens(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, pTick);
		return merged == null ? null : merged.keySet();
	}

	/**
	 * Lot V3-M3 (2026-10-04, docs/VOLUME.md "Lot V3-M3"): every MERGED record of the table AND the golden record it was merged into, {@code functionalId -> goldenId},
	 * in the same single streaming request as {@link #readMergedFunctionalIds} ({@code RecordMetadata} restricted by {@code ./state=2}; if the predicate is
	 * refused the table is read whole and filtered here on the same value). It answers what a master load asked the add-on for LIVE once per merged parent
	 * ({@code MetadataHelper.getTargetRecord}: a master record lookup, then the metadataset row) - 417,841 random reads, 370 s, on Vol1mF's addresses.
	 * <p>
	 * The value is the golden's formatted key, trimmed, or the empty string when the row has no {@code goldenId} (should not happen: the caller then asks the
	 * add-on for that record). The strings are shared between the records of a group (one instance per distinct golden key). A missing {@code goldenId}
	 * field is not a failure: the keys are still returned, every value empty. Null - never an exception - when the metadataset, the table, {@code functionalId}
	 * or {@code state} does not exist, when the scan failed, or when {@code pTick} stopped it. An empty map is a real answer ("nothing merged").
	 */
	static Map<String, String> readMergedGoldens(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath, final ScanTick pTick)
	{
		final Map<String, String> out = new HashMap<String, String>();
		final Map<String, String> shared = new HashMap<String, String>(); // one String per distinct golden key
		final boolean done = MameMetadatasetReads.readMerged(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, pTick, new MergedOut()
		{
			@Override
			public void expect(final long pRows)
			{
			}

			@Override
			public void add(final String pKey, final String pGolden)
			{
				out.put(pKey, MameMetadatasetReads.share(shared, pGolden));
			}

			@Override
			public void clear()
			{
				out.clear();
			}
		});
		return done ? out : null;
	}

	/**
	 * Lot IDX (2026-10-12, docs/VOLUME.md "Lot IDX - compact indexes"): the same request and the same answer as {@link #readMergedGoldens}, held as a {@link
	 * KeyValueIndex} (a 128-bit digest and one {@code long} per merged record, about 30 bytes, against about 90 plus 50 per distinct golden for the {@code HashMap}).
	 * Null - as {@link #readMergedGoldens} - when the table cannot be read or {@code pTick} stopped the scan, and ALSO when two merged keys had the same
	 * digest ({@code KeyValueIndex#duplicates}, logged): an index that cannot tell two records apart is not used, the caller keeps the per-row add-on check.
	 */
	static KeyValueIndex readMergedIndex(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pMasterTablePath,
		final ScanTick pTick)
	{
		final KeyValueIndex out = new KeyValueIndex(0L);
		final boolean done = MameMetadatasetReads.readMerged(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, pTick, new MergedOut()
		{
			@Override
			public void expect(final long pRows)
			{
				out.ensureCapacity(pRows);
			}

			@Override
			public void add(final String pKey, final String pGolden)
			{
				out.add(pKey, pGolden);
			}

			@Override
			public void clear()
			{
				out.clear();
			}
		});
		if (!done)
		{
			return null;
		}
		if (out.duplicates() > 0L)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().warn("[mdm-factory] " + pMasterTablePath + ": " + out.duplicates()
				+ " merged keys share a 128-bit digest with another one: the merged index is not used, the add-on is asked per record");
			return null;
		}
		return out;
	}

	/** Where the merged scan puts what it reads: a {@code Map} (the older callers) or a compact index. */
	private interface MergedOut
	{
		/** The request will deliver about {@code pRows} rows (a hint, called once per attempt). */
		void expect(long pRows);

		/** {@code pGolden} is the trimmed golden key, empty when the row has none. */
		void add(String pKey, String pGolden);

		void clear();
	}

	/** @return false when the table cannot be read, the scan failed both ways, or {@code pTick} stopped it */
	private static boolean readMerged(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pMasterTablePath,
		final ScanTick pTick, final MergedOut pOut)
	{
		final AdaptationTable table = MameMetadatasetReads.resolveMetadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, "RecordMetadata");
		if (table == null)
		{
			return false;
		}
		final boolean hasGolden;
		try
		{
			if (table.getTableOccurrenceRootNode() == null || table.getTableOccurrenceRootNode().getNode(Path.parse("./functionalId")) == null
				|| table.getTableOccurrenceRootNode().getNode(Path.parse("./state")) == null)
			{
				return false;
			}
			hasGolden = table.getTableOccurrenceRootNode().getNode(Path.parse("./goldenId")) != null;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
		Boolean outcome = MameMetadatasetReads.scanMerged(table, "./state=" + MameMetadatasetReads.STATE_MERGED, false, hasGolden, pTick, pOut);
		if (outcome == null) // the predicate (or the request) was refused: the whole table, filtered here
		{
			pOut.clear();
			outcome = MameMetadatasetReads.scanMerged(table, null, true, hasGolden, pTick, pOut);
		}
		return outcome != null && outcome.booleanValue();
	}

	/** @return true when the scan completed, false when {@code pTick} stopped it, null when it failed (the caller may retry another way) */
	private static Boolean scanMerged(final AdaptationTable pTable, final String pPredicate, final boolean pFilterHere, final boolean pHasGolden, final ScanTick pTick,
		final MergedOut pOut)
	{
		final Path functionalId = Path.parse("./functionalId");
		final Path state = Path.parse("./state");
		final Path goldenId = Path.parse("./goldenId");
		try
		{
			final RequestResult rows = pTable.createRequestResult(pPredicate);
			try
			{
				if (!pFilterHere)
				{
					try
					{
						pOut.expect(rows.getSize()); // the restricted request knows its size: the index is sized once, with no rehash
					}
					catch (final RuntimeException ex)
					{
						// a hint only
					}
				}
				long count = 0;
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					if (++count % MameMetadatasetReads.TICK_EVERY == 0 && pTick != null && !pTick.keepGoing(count))
					{
						return Boolean.FALSE;
					}
					if (pFilterHere)
					{
						final Object raw = row.get(state);
						if (!(raw instanceof Number) || ((Number) raw).intValue() != MameMetadatasetReads.STATE_MERGED)
						{
							continue;
						}
					}
					final String key = row.getString(functionalId);
					if (key != null)
					{
						pOut.add(key, pHasGolden ? MameMetadatasetReads.goldenText(row.get(goldenId)) : "");
					}
				}
			}
			finally
			{
				rows.close();
			}
			return Boolean.TRUE;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** {@code RecordMetadata.goldenId} as the trimmed formatted key; the empty string for null/blank (a number, if the add-on stores one, is written without exponent). */
	static String goldenText(final Object pRaw)
	{
		if (pRaw == null)
		{
			return "";
		}
		final String text = pRaw instanceof java.math.BigDecimal ? ((java.math.BigDecimal) pRaw).toPlainString() : String.valueOf(pRaw);
		return text.trim();
	}

	private static String share(final Map<String, String> pShared, final String pValue)
	{
		if (pValue.isEmpty())
		{
			return "";
		}
		final String known = pShared.get(pValue);
		if (known != null)
		{
			return known;
		}
		pShared.put(pValue, pValue);
		return pValue;
	}

	/**
	 * Lot V3-M3 (2026-10-04): what {@link #readRecordMetadataIndex} answers - either {@link #index} (the whole {@code RecordMetadata} table as {@code
	 * functionalId -> encoded state}, see {@code master.GoldenKeyScanPlan}) or, when it could not be built, {@link #reason} (the caller then keeps its per-record path).
	 */
	static final class MetadataIndex
	{
		final KeyValueIndex index;
		final String reason;
		final long rows;

		MetadataIndex(final KeyValueIndex pIndex, final String pReason, final long pRows)
		{
			this.index = pIndex;
			this.reason = pReason;
			this.rows = pRows;
		}
	}

	/**
	 * Lot V3-M3 (2026-10-04): ONE streaming request of the whole {@code RecordMetadata} table, {@code functionalId -> } state and golden of the record, encoded as
	 * {@code master.GoldenKeyScanPlan} does (a few bytes per row: the state itself is not kept, only whether the record is MERGED and into what). What {@code
	 * GoldenKeyRefresher} used to ask the add-on for once per record ({@code MetadataHelper.getState}, then {@code getTargetRecord}). A row with no {@code state}
	 * is left out (the add-on's "no metadata"). The table is refused (reason, no index) above {@code pMaxRows} rows, and when it, {@code functionalId} or {@code
	 * state} does not exist, the scan failed, or {@code pTick} stopped it (reason says which). Never throws.
	 */
	static MetadataIndex readRecordMetadataIndex(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath, final long pMaxRows, final ScanTick pTick)
	{
		final AdaptationTable table = MameMetadatasetReads.resolveMetadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, "RecordMetadata");
		if (table == null)
		{
			return new MetadataIndex(null, "the add-on's RecordMetadata table of " + pMasterTablePath + " does not exist" + MameMetadataGuard.reasonSuffix(), 0L);
		}
		final Path functionalId = Path.parse("./functionalId");
		final Path state = Path.parse("./state");
		final Path goldenId = Path.parse("./goldenId");
		try
		{
			if (table.getTableOccurrenceRootNode() == null || table.getTableOccurrenceRootNode().getNode(functionalId) == null
				|| table.getTableOccurrenceRootNode().getNode(state) == null)
			{
				return new MetadataIndex(null, "RecordMetadata has no functionalId/state field", 0L);
			}
			final boolean hasGolden = table.getTableOccurrenceRootNode().getNode(goldenId) != null;
			final RequestResult rows = table.createRequestResult(null);
			try
			{
				final long size = rows.getSize();
				final String refusal = ScanBudget.goldenRefreshRefusal(pMaxRows, size);
				if (refusal != null)
				{
					return new MetadataIndex(null, refusal, size);
				}
				final KeyValueIndex index = new KeyValueIndex(size); // lot IDX: a 128-bit digest and one long a record, sized once
				long count = 0;
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					if (++count % MameMetadatasetReads.TICK_EVERY == 0 && pTick != null && !pTick.keepGoing(count))
					{
						return new MetadataIndex(null, "stopped", count);
					}
					final String key = row.getString(functionalId);
					final Object rawState = row.get(state);
					if (key == null || !(rawState instanceof Number))
					{
						continue;
					}
					final int stateValue = ((Number) rawState).intValue();
					final String golden = stateValue == MameMetadatasetReads.STATE_MERGED && hasGolden ? MameMetadatasetReads.goldenText(row.get(goldenId)) : "";
					index.add(key, GoldenKeyScanPlan.encode(stateValue, golden));
				}
				if (index.duplicates() > 0L)
				{
					// two functionalIds with one 128-bit digest (or one functionalId twice): this index cannot tell the records apart, the per-record path runs
					return new MetadataIndex(null, index.duplicates() + " RecordMetadata keys share a 128-bit digest with another one", count);
				}
				return new MetadataIndex(index, null, count);
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return new MetadataIndex(null, "RecordMetadata could not be read: " + ex, 0L);
		}
	}

	/**
	 * What {@link #readRecordMetadataKeys} read in ONE pass of {@code RecordMetadata}: the {@code functionalId} of every row ({@code all} - a record NOT in it
	 * was never matched) and, among them, the ones whose state is {@code MERGED} ({@code merged} - no longer a pivot).
	 */
	static final class RecordMetadataKeys
	{
		final Set<String> all;
		final Set<String> merged;

		RecordMetadataKeys(final Set<String> pAll, final Set<String> pMerged)
		{
			this.all = pAll;
			this.merged = pMerged;
		}
	}

	/**
	 * Lot MP (2026-10-04): one streaming request of the metadataset's {@code RecordMetadata}, reading each row's {@code functionalId} and {@code state} - the
	 * known keys the catch-up after a matching pause compares the master table with (a master record without a row there was never matched). Null - never an
	 * exception - when the metadataset, the table or {@code functionalId} does not exist, when the scan failed, or when {@code pTick} stopped it ({@code
	 * state} missing only leaves {@code merged} empty).
	 */
	static RecordMetadataKeys readRecordMetadataKeys(final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName,
		final String pMasterTablePath, final ScanTick pTick)
	{
		final AdaptationTable table = MameMetadatasetReads.resolveMetadatasetTable(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, "RecordMetadata");
		if (table == null)
		{
			return null;
		}
		final Path functionalId = Path.parse("./functionalId");
		final Path state = Path.parse("./state");
		try
		{
			if (table.getTableOccurrenceRootNode() == null || table.getTableOccurrenceRootNode().getNode(functionalId) == null)
			{
				return null;
			}
			final boolean hasState = table.getTableOccurrenceRootNode().getNode(state) != null;
			final Set<String> all = new HashSet<String>();
			final Set<String> merged = new HashSet<String>();
			final RequestResult rows = table.createRequestResult(null);
			try
			{
				long count = 0;
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					if (++count % MameMetadatasetReads.TICK_EVERY == 0 && pTick != null && !pTick.keepGoing(count))
					{
						return null;
					}
					final String key = row.getString(functionalId);
					if (key == null)
					{
						continue;
					}
					all.add(key);
					if (hasState)
					{
						final Object raw = row.get(state);
						if (raw instanceof Number && ((Number) raw).intValue() == MameMetadatasetReads.STATE_MERGED)
						{
							merged.add(key);
						}
					}
				}
			}
			finally
			{
				rows.close();
			}
			return new RecordMetadataKeys(all, merged);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Same reflection-free scalar-field read {@code ebx.RecordLineageReader} used before this refactor - a plain {@code Adaptation.get(Path)} on an ordinary (non-{@code osd:class}) field, never a guess. Null on any failure. */
	private static Object safeGet(final Adaptation pRecord, final String pFieldName)
	{
		try
		{
			return pRecord.getSchemaNode().getNode(Path.parse("./" + pFieldName)) == null ? null : pRecord.get(Path.parse("./" + pFieldName));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}
}
