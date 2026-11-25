package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.core.CatalogKeys;
import com.on.ebx.factory.deducedscan.CatalogSourceId;
import com.on.ebx.factory.deducedscan.ReadRecipe;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Deduced-scan lot (2026-09-24, board 2e): a dedicated, small read of a deduced-scanned application's own
 * pending tables - kept separate from {@link CatalogReader#assets}/{@link CatalogReader.Asset} (the generic,
 * heavily-reused structure/column reader every other lot's landing plan goes through) rather than growing
 * that class further: the confirm screen needs the {@code scan*} fields and the raw {@code readRecipe} on
 * EVERY row it lists, which {@link CatalogReader.Asset} does not carry at all (see {@link
 * CatalogReader#scanInfo}, added for the single-asset case the landing snapshot needs).
 */
public final class DeducedScanCatalog
{
	private static final Path ASSET = Path.parse("/root/Asset");
	private static final Path COLUMN = Path.parse("/root/Column");
	private static final Path SOURCE = Path.parse("/root/Source");

	/** Audit F-02 (catalog reads): why {@link #ensureSource} reads the sources without the person's session. */
	static final String ENSURE_SOURCE_REASON = "find-or-create of the catalog Source of a connection: decides whether to create and picks an id unique among every"
		+ " source, hidden ones included; nothing is shown, the creation runs under the caller's session (the catalog asks ADMINISTER)";
	/** Audit F-02 (catalog reads): why {@link #rekey} reads without the person's session. */
	static final String REKEY_REASON = "inside a rename (or a deduced-scan rewrite) that already holds the asset: must see every key of the source to refuse"
		+ " a collision, and every column of the asset to move them all; the writes run under the caller's session";

	private DeducedScanCatalog()
	{
	}

	/**
	 * Deduced-scan lot (2026-09-24): {@code ProjectSource.catalogSource} is set by the guided/official
	 * onboarding path ({@code ebx.SourceOnboarder}), which a connection with no catalog operation never
	 * reaches (its own diagnosis stops at the "has a catalog operation" step - see {@code
	 * SourceOnboarder#facts}). A deduced-scanned application therefore often has NO catalog {@code Source} row
	 * yet: this finds one already keyed on {@code pConnectorId} (a second deduced scan of the same connection
	 * reuses it) or creates a minimal one (label/connector = {@code pConnectorId}, {@code active=true}) -
	 * exactly enough for {@code Asset.source} to point at, never a substitute for a real official-scan Source
	 * (no {@code lastScanStatus}/{@code platform} beyond what is given here).
	 * <p>
	 * Fix lot (2026-09-25): {@code Source.id} is a plain MANDATORY string (max 40, e.g. {@code "DWH_PROD"},
	 * {@code "PERSONSSALESFORCESAMPLE"}), never an auto-increment - the live bug this fix addresses is
	 * exactly this create leaving it unset ("Cannot create a new record because primary key has unset
	 * element(s): [osd:is-null(./id)]"). The id is now derived the SAME way the official path
	 * ({@code connectors_catalog_source}'s own {@code CatalogDetection#sourceIdFor}) names a brand new
	 * Source - {@link CatalogSourceId#forConnector} is a verbatim port of that algorithm - and disambiguated
	 * against every {@code Source.id} already in the catalog ({@link CatalogSourceId#unique}) so two
	 * connectors that reduce to the same base id never collide.
	 */
	public static String ensureSource(final Session pSession, final Adaptation pCatalog, final String pConnectorId, final String pLabel) throws OperationException
	{
		return DeducedScanCatalog.ensureSource(pSession, pCatalog, pConnectorId, pLabel, pSession == null ? null : pSession.getLocale());
	}

	/**
	 * Same as {@link #ensureSource(Session, Adaptation, String, String)}, with the message locale given
	 * explicitly rather than read from {@code pSession} - job-session-unwrapped fix (2026-09-25): a job runs
	 * with the SYSTEM session (never wrapped to fake its locale, see {@code ebx.SourcesRun}'s own class
	 * Javadoc on why), so a caller that must report a failure in the launcher's own locale has to pass it here
	 * instead of relying on {@code pSession.getLocale()}. {@code pLocale} null falls back to
	 * {@code java.util.Locale.ENGLISH}, same as the no-locale overload.
	 */
	public static String ensureSource(final Session pSession, final Adaptation pCatalog, final String pConnectorId, final String pLabel, final java.util.Locale pLocale)
		throws OperationException
	{
		final AdaptationTable sourceTable = pCatalog.getTable(DeducedScanCatalog.SOURCE);
		// audit F-02 (catalog reads): both reads below decide the write (find or create, and an id unique among EVERY
		// source, hidden ones included); nothing they find is shown, and the creation itself runs under pSession
		final CatalogReader.Reader decision = CatalogReader.Reader.system(DeducedScanCatalog.ENSURE_SOURCE_REASON);
		final CatalogReader.Rows existing = CatalogReader.rows(decision, sourceTable, "./connector=" + CatalogReader.quote(pConnectorId));
		try
		{
			final Adaptation found = existing.next();
			if (found != null)
			{
				return found.getOccurrencePrimaryKey().format();
			}
		}
		finally
		{
			existing.close();
		}
		final Set<String> existingIds = new HashSet<String>();
		final CatalogReader.Rows everyRow = CatalogReader.rows(decision, sourceTable, null);
		try
		{
			for (Adaptation row = everyRow.next(); row != null; row = everyRow.next())
			{
				existingIds.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			everyRow.close();
		}
		final String newId = CatalogSourceId.unique(pConnectorId, existingIds);
		final String[] idHolder = new String[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pCatalog.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(sourceTable);
				value.setValue(newId, Path.parse("./id"));
				value.setValue(pLabel == null || pLabel.trim().isEmpty() ? pConnectorId : pLabel, Path.parse("./label"));
				value.setValue("OTHER", Path.parse("./platform"));
				value.setValue(pConnectorId, Path.parse("./connector"));
				value.setValue(Boolean.TRUE, Path.parse("./active"));
				idHolder[0] = pContext.doCreateOccurrence(value, sourceTable).getOccurrencePrimaryKey().format();
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveFailed", result.getExceptionFullMessage(pLocale == null ? java.util.Locale.ENGLISH : pLocale));
		}
		return idHolder[0];
	}

	public static final class PendingColumn
	{
		public String name = "";
		public String dataType = "";
		public boolean primaryKey;
		public int position;
		/** {@code Column.lastDistinctCount}, reused verbatim (contract: "reuse whatever Column already has") - -1 when unknown. */
		public int distinct = -1;
		/** {@code Column.sampleFilled} - -1 when the field does not exist yet on this catalog, or was never written. */
		public int filled = -1;
		/** {@code Column.sampleExample} - empty when the field does not exist yet, or the column was never filled in the sample. */
		public String example = "";
		/** Fix lot (2026-09-26): the number of rows the sample ACTUALLY read (one sample per table - the same figure
		 * for every column of it), read from the owning {@link PendingAsset#sampleSize} - was hardcoded to {@code 25}
		 * (the requested cap, {@code DeducedScanSample#MAX_ITEMS}), wrong whenever the source held fewer rows than
		 * that (a 10-row table showed "FILLED 10/25" instead of "10/10"). 0 when unknown. */
		public int sampleSize;
	}

	public static final class PendingAsset
	{
		/** {@code Asset.assetKey} (audit D-07): {@code source:qualifiedName}, what Confirm / Leave it out / Rename post back. */
		public String key = "";
		public String tableName = "";
		/** {@code TO_CONFIRM} only, per this class's own contract - see {@link #pendingAssets}. */
		public String scanState = "";
		public int scanConfidence;
		public String scanRationale = "";
		public ReadRecipe readRecipe = ReadRecipe.parse(null);
		/** Fix lot (2026-09-26): {@code Asset.rowCount} - the real number of rows the deduced scan's sample read
		 * (see {@code ebx.DeducedScanCatalogWriter}), 0 when unknown (an older row this fix never touched). */
		public int sampleSize;
		public final List<PendingColumn> columns = new ArrayList<PendingColumn>();
	}

	/**
	 * Every {@code Asset} of {@code pSourceId} still {@code scanState=TO_CONFIRM} (board 2e's own scope -
	 * CONFIRMED/LEFT_OUT tables are the landing step's business from then on, not this screen's). Empty (never
	 * throws) on a catalog without the {@code scan*} fields yet, or with none pending.
	 */
	public static List<PendingAsset> pendingAssets(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		return DeducedScanCatalog.cached("pending", pReader, pCatalog, pSourceId, new Loader<List<PendingAsset>>()
		{
			@Override
			public List<PendingAsset> load()
			{
				return DeducedScanCatalog.readPendingAssets(pReader, pCatalog, pSourceId);
			}
		});
	}

	private static List<PendingAsset> readPendingAssets(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		final List<PendingAsset> out = new ArrayList<PendingAsset>();
		if (pCatalog == null || pSourceId == null || pSourceId.trim().isEmpty())
		{
			return out;
		}
		if (pCatalog.getTable(DeducedScanCatalog.ASSET).getTableOccurrenceRootNode().getNode(Path.parse("./scanState")) == null)
		{
			return out;
		}
		final CatalogReader.Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(DeducedScanCatalog.ASSET),
			"./source=" + CatalogReader.quote(pSourceId) + " and ./scanState='TO_CONFIRM'");
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				final PendingAsset asset = new PendingAsset();
				asset.key = CatalogReader.assetKeyOf(row);
				asset.tableName = String.valueOf(row.get(Path.parse("./qualifiedName")));
				asset.scanState = String.valueOf(row.get(Path.parse("./scanState")));
				final Object confidence = row.get(Path.parse("./scanConfidence"));
				asset.scanConfidence = confidence instanceof Number ? ((Number) confidence).intValue() : 0;
				asset.scanRationale = String.valueOf(row.get(Path.parse("./scanRationale")));
				asset.readRecipe = ReadRecipe.parse(String.valueOf(row.get(Path.parse("./readRecipe"))));
				final Object rowCount = row.get(Path.parse("./rowCount"));
				asset.sampleSize = rowCount instanceof Number ? ((Number) rowCount).intValue() : 0;
				DeducedScanCatalog.columns(pReader, pCatalog, asset);
				out.add(asset);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Fix lot (2026-09-26, defect 9, project DeducedTest2): a second deduced scan of a connection wrote a
	 * SECOND {@code Asset} for the same table (same recipe: connector/operation/inputs/itemsPath) because
	 * nothing ever looked for one first - {@code Source} DEMOCRM ended up with {@code Company} (561) AND
	 * {@code Company} (571), matched on nothing at all. This is the missing lookup: every {@code Asset} of
	 * {@code pSourceId} that carries a {@code readRecipe} (any {@code scanState} - CONFIRMED/LEFT_OUT included,
	 * not just {@code TO_CONFIRM}) is parsed back into a {@link ReadRecipe} and compared to {@code pRecipe} with
	 * {@link ReadRecipe#equals} - never by table name, which a rename or a differently-worded model answer can
	 * change between two runs (DEDUCED-SCAN.md item 11). Null (never throws) when the catalog does not have the
	 * {@code readRecipe} field yet, or no asset of this source matches.
	 */
	public static ExistingAsset findByRecipe(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pSourceId, final ReadRecipe pRecipe)
	{
		if (pCatalog == null || pSourceId == null || pSourceId.trim().isEmpty())
		{
			return null;
		}
		final AdaptationTable assetTable = pCatalog.getTable(DeducedScanCatalog.ASSET);
		if (assetTable.getTableOccurrenceRootNode().getNode(Path.parse("./readRecipe")) == null)
		{
			return null;
		}
		final CatalogReader.Rows rows = CatalogReader.rows(pReader, assetTable,
			"./source=" + CatalogReader.quote(pSourceId) + " and not(osd:is-null(./readRecipe)) and not(./readRecipe='')");
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				final ReadRecipe existingRecipe = ReadRecipe.parse(String.valueOf(row.get(Path.parse("./readRecipe"))));
				if (!pRecipe.equals(existingRecipe))
				{
					continue;
				}
				return DeducedScanCatalog.existing(pReader, pCatalog, row);
			}
		}
		finally
		{
			rows.close();
		}
		return null;
	}

	/**
	 * Audit D-07: the {@code Asset} of {@code pAssetKey} ({@code source:tableName}), whatever its recipe - null when there
	 * is none. Since the natural key, "same source + same table name" IS the same asset: a deduced scan that names a table
	 * already in the catalog (another operation, another recipe, or an official scan of the same source) reconciles with
	 * that row instead of creating a second one, which the key would refuse anyway.
	 */
	public static ExistingAsset findByKey(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final Adaptation row = CatalogReader.assetRow(pReader, pCatalog, pAssetKey);
		return row == null ? null : DeducedScanCatalog.existing(pReader, pCatalog, row);
	}

	private static ExistingAsset existing(final CatalogReader.Reader pReader, final Adaptation pCatalog, final Adaptation pRow)
	{
		final ExistingAsset found = new ExistingAsset();
		found.key = CatalogReader.assetKeyOf(pRow);
		final String qualifiedName = pRow.getString(Path.parse("./qualifiedName"));
		found.qualifiedName = qualifiedName == null ? "" : qualifiedName;
		final Path scanState = Path.parse("./scanState");
		final String state = pRow.getSchemaNode().getNode(scanState) == null ? null : pRow.getString(scanState);
		found.scanState = state == null ? "" : state;
		final Path scanOrigin = Path.parse("./scanOrigin");
		final String origin = pRow.getSchemaNode().getNode(scanOrigin) == null ? null : pRow.getString(scanOrigin);
		found.scanOrigin = origin == null ? "" : origin;
		final Path readRecipe = Path.parse("./readRecipe");
		found.readRecipe = pRow.getSchemaNode().getNode(readRecipe) == null ? ReadRecipe.parse(null) : ReadRecipe.parse(pRow.getString(readRecipe));
		final CatalogReader.Rows columnRows = CatalogReader.rows(pReader, pCatalog.getTable(DeducedScanCatalog.COLUMN), "./asset=" + CatalogReader.quote(found.key));
		try
		{
			for (Adaptation columnRow = columnRows.next(); columnRow != null; columnRow = columnRows.next())
			{
				found.columnNames.add(String.valueOf(columnRow.get(Path.parse("./name"))));
				found.columnPrimaryKey.put(String.valueOf(columnRow.get(Path.parse("./name"))), Boolean.TRUE.equals(columnRow.get(Path.parse("./primaryKey"))));
			}
		}
		finally
		{
			columnRows.close();
		}
		return found;
	}

	/** One {@code Asset} row already matching a fresh recipe (see {@link #findByRecipe}) or the same key ({@link #findByKey}) - enough for the writer to update it instead of creating a duplicate. */
	public static final class ExistingAsset
	{
		/** {@code Asset.assetKey}. */
		public String key = "";
		/** {@code Asset.qualifiedName} - the name in the key, unless the row was renamed before audit D-07. */
		public String qualifiedName = "";
		/** {@code Asset.scanOrigin}: {@code DEDUCED}, {@code OFFICIAL}, or {@code ""} (a row of an official scan written before the field). */
		public String scanOrigin = "";
		/** {@code Asset.readRecipe} as stored (a blank recipe when none). */
		public ReadRecipe readRecipe = ReadRecipe.parse(null);
		/** Whatever the row's {@code scanState} was BEFORE this reconciliation - CONFIRMED/LEFT_OUT/TO_CONFIRM. */
		public String scanState = "";
		public final List<String> columnNames = new ArrayList<String>();
		/** Column name -&gt; its previous {@code primaryKey} flag - carried forward when the columns did not change (see {@code deducedscan.AssetReconciliation}), so a person's already-confirmed key survives a re-scan untouched. */
		public final java.util.Map<String, Boolean> columnPrimaryKey = new java.util.HashMap<String, Boolean>();
	}

	/**
	 * Fix lot (2026-09-26, defect 14): the current decision (CONFIRMED/LEFT_OUT/still TO_CONFIRM) of every
	 * {@code scanOrigin=DEDUCED} {@code Asset} of {@code pSourceId} - what board 2d's own pipeline card end
	 * line renders once the run is {@code DONE}, instead of the frozen "N table(s) deduced, waiting for
	 * confirmation." text the run itself wrote (wrong forever after, once a person actually decides). All-zero
	 * (never throws) when the catalog does not have {@code scanOrigin} yet, or this source has no deduced
	 * asset at all - the caller then falls back to the legacy static text.
	 */
	public static DecisionCounts decisionCounts(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		return DeducedScanCatalog.cached("counts", pReader, pCatalog, pSourceId, new Loader<DecisionCounts>()
		{
			@Override
			public DecisionCounts load()
			{
				return DeducedScanCatalog.readDecisionCounts(pReader, pCatalog, pSourceId);
			}
		});
	}

	private static DecisionCounts readDecisionCounts(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		final DecisionCounts out = new DecisionCounts();
		if (pCatalog == null || pSourceId == null || pSourceId.trim().isEmpty())
		{
			return out;
		}
		final AdaptationTable assetTable = pCatalog.getTable(DeducedScanCatalog.ASSET);
		if (assetTable.getTableOccurrenceRootNode().getNode(Path.parse("./scanOrigin")) == null)
		{
			return out;
		}
		final CatalogReader.Rows rows = CatalogReader.rows(pReader, assetTable, "./source=" + CatalogReader.quote(pSourceId) + " and ./scanOrigin='DEDUCED'");
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				final String state = String.valueOf(row.get(Path.parse("./scanState")));
				if ("CONFIRMED".equals(state))
				{
					out.confirmed++;
				}
				else if ("LEFT_OUT".equals(state))
				{
					out.leftOut++;
				}
				else
				{
					out.toConfirm++;
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Plain per-decision counts of one application's deduced tables - see {@link #decisionCounts}. */
	public static final class DecisionCounts
	{
		public int confirmed;
		public int leftOut;
		public int toConfirm;

		public int total()
		{
			return this.confirmed + this.leftOut + this.toConfirm;
		}
	}

	/** Fix lot (2026-09-26): {@code Asset.source} (the catalog {@code Source} id) of one asset, by key - null if the
	 * asset does not exist. Lets a caller that only has an {@code Asset} key (a board 2e Confirm/Leave-out click)
	 * find which application's landing plan to re-run once every deduced table of it is decided. */
	public static String assetSource(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pAssetKey)
	{
		final Adaptation row = CatalogReader.assetRow(pReader, pCatalog, pAssetKey);
		return row == null ? null : row.getString(Path.parse("./source"));
	}

	/**
	 * Audit D-07: moves an {@code Asset} and its {@code Column} rows to a new natural key, inside the caller's procedure -
	 * what a rename of a deduced table is now, since the primary key {@code assetKey = source:qualifiedName} cannot be
	 * modified in place and an asset whose name and key disagree would be found by no lookup ({@code source:name}) and
	 * duplicated by the next scan. The asset row is copied with every value ({@code getContextForNewOccurrence(row,
	 * table)}), then {@code assetKey}, {@code qualifiedName} (and {@code physicalName} when {@code pAlsoPhysicalName})
	 * are set; each column is copied the same way with {@code asset} and {@code columnKey = newKey.name}; the old columns
	 * and the old asset are deleted. Nothing else is moved: a profile snapshot, a tag suggestion or a lineage edge that
	 * already pointed at the old key keeps pointing at it (a deduced table waiting for confirmation has none - the catalog
	 * flows that write them run on confirmed, official assets).
	 *
	 * @return the asset row under its new key
	 * @throws OperationException when another asset already holds the new key (the person picked the name of a table
	 *         the catalog already has for this source)
	 */
	static Adaptation rekey(final ProcedureContext pContext, final Adaptation pCatalog, final Adaptation pOldRow, final String pNewName, final boolean pAlsoPhysicalName)
		throws OperationException
	{
		final AdaptationTable assetTable = pCatalog.getTable(DeducedScanCatalog.ASSET);
		final AdaptationTable columnTable = pCatalog.getTable(DeducedScanCatalog.COLUMN);
		final String oldKey = CatalogReader.assetKeyOf(pOldRow);
		final String newKey = CatalogKeys.assetKey(pOldRow.getString(Path.parse("./source")), pNewName);
		if (newKey == null)
		{
			throw EngineRefusal.coded("invalid.tableNeedsName", "A table needs a name.");
		}
		if (newKey.length() > CatalogKeys.ASSET_KEY_MAX)
		{
			throw EngineRefusal.coded("invalid.catalogKeyTooLong", "The name '" + pNewName + "' is too long for a catalog key (" + CatalogKeys.ASSET_KEY_MAX + " characters with the source).", pNewName, CatalogKeys.ASSET_KEY_MAX);
		}
		if (newKey.equals(oldKey))
		{
			return pOldRow;
		}
		final CatalogReader.Reader decision = CatalogReader.Reader.system(DeducedScanCatalog.REKEY_REASON);
		if (CatalogReader.assetRow(decision, pCatalog, newKey) != null)
		{
			throw EngineRefusal.coded("conflict.catalogNameTaken", "The catalog already has a table '" + pNewName + "' for this source: choose another name, or leave this one out.", pNewName);
		}
		final ValueContextForUpdate assetValue = pContext.getContextForNewOccurrence(pOldRow, assetTable);
		assetValue.setValue(newKey, Path.parse("./assetKey"));
		assetValue.setValue(pNewName, Path.parse("./qualifiedName"));
		if (pAlsoPhysicalName)
		{
			assetValue.setValue(pNewName, Path.parse("./physicalName"));
		}
		final Adaptation newRow = pContext.doCreateOccurrence(assetValue, assetTable);
		final List<Adaptation> oldColumns = new ArrayList<Adaptation>();
		final CatalogReader.Rows rows = CatalogReader.rows(decision, columnTable, "./asset=" + CatalogReader.quote(oldKey));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				oldColumns.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		for (final Adaptation oldColumn : oldColumns)
		{
			final ValueContextForUpdate columnValue = pContext.getContextForNewOccurrence(oldColumn, columnTable);
			columnValue.setValue(newKey, Path.parse("./asset"));
			columnValue.setValue(CatalogKeys.columnKey(newKey, oldColumn.getString(Path.parse("./name"))), Path.parse("./columnKey"));
			pContext.doCreateOccurrence(columnValue, columnTable);
		}
		for (final Adaptation oldColumn : oldColumns)
		{
			pContext.doDelete(oldColumn.getAdaptationName(), false);
		}
		pContext.doDelete(pOldRow.getAdaptationName(), false);
		return newRow;
	}

	private static void columns(final CatalogReader.Reader pReader, final Adaptation pCatalog, final PendingAsset pAsset)
	{
		final AdaptationTable columnTable = pCatalog.getTable(DeducedScanCatalog.COLUMN);
		final boolean sampleFieldsExist = columnTable.getTableOccurrenceRootNode().getNode(Path.parse("./sampleFilled")) != null;
		final CatalogReader.Rows rows = CatalogReader.rows(pReader, columnTable, "./asset=" + CatalogReader.quote(pAsset.key));
		final List<PendingColumn> collected = new ArrayList<PendingColumn>();
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				final PendingColumn column = new PendingColumn();
				column.name = String.valueOf(row.get(Path.parse("./name")));
				column.dataType = String.valueOf(row.get(Path.parse("./dataType")));
				column.primaryKey = Boolean.TRUE.equals(row.get(Path.parse("./primaryKey")));
				final Object position = row.get(Path.parse("./position"));
				column.position = position instanceof Number ? ((Number) position).intValue() : Integer.MAX_VALUE;
				final Object distinct = row.get(Path.parse("./lastDistinctCount"));
				column.distinct = distinct instanceof Number ? ((Number) distinct).intValue() : -1;
				if (sampleFieldsExist)
				{
					final Object filled = row.get(Path.parse("./sampleFilled"));
					column.filled = filled instanceof Number ? ((Number) filled).intValue() : -1;
					final Object example = row.get(Path.parse("./sampleExample"));
					column.example = example == null ? "" : String.valueOf(example);
				}
				column.sampleSize = pAsset.sampleSize;
				collected.add(column);
			}
		}
		finally
		{
			rows.close();
		}
		java.util.Collections.sort(collected, new Comparator<PendingColumn>()
		{
			@Override
			public int compare(final PendingColumn pLeft, final PendingColumn pRight)
			{
				return Integer.compare(pLeft.position, pRight.position);
			}
		});
		pAsset.columns.addAll(collected);
	}

	// ------------------------------------------------------------------------------------------------------ F2-107: one scan per source, not one per render

	/** One read of the catalog, run only on a cache miss. */
	interface Loader<T>
	{
		T load();
	}

	/** How long a cached answer is trusted even when the tables' dates did not move (a person's catalog right can change without touching them). */
	static final long CACHE_MILLIS = 30000L;
	private static final int CACHE_MAX = 400;
	private static final java.util.Map<String, Cached> CACHE = new java.util.LinkedHashMap<String, Cached>();
	/** How many loads the cache has run - what a test counts. */
	static volatile int cacheLoads;

	private static final class Cached
	{
		final String stamp;
		final long at;
		final Object value;
		final boolean restricted;

		Cached(final String pStamp, final long pAt, final Object pValue, final boolean pRestricted)
		{
			this.stamp = pStamp;
			this.at = pAt;
			this.value = pValue;
			this.restricted = pRestricted;
		}
	}

	/**
	 * F2-107 (robustness audit 2026-10-23): every render of the wizard scanned the catalog's {@code Asset} table twice per application (the decision counts, then the pending
	 * tables with their columns) - a predicate on non-key fields, so a catalog of tens of thousands of assets slowed every step. The answer of one reader for one application is now kept
	 * under the person (the catalog's rules filter what each one sees: a person's cache entry is never served to another) and the last-modification dates of the {@code Asset} and
	 * {@code Column} tables: a write to either re-reads at once, and an entry older than {@link #CACHE_MILLIS} is re-read anyway. A read that found rows hidden from the person
	 * marks the reader restricted; a cache hit replays that mark. No date available: no cache.
	 */
	private static <T> T cached(final String pKind, final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pSourceId, final Loader<T> pLoader)
	{
		final String who = pReader.isSystem() ? "system" : pReader.session() == null || pReader.session().getUserReference() == null ? null : pReader.session().getUserReference().getUserId();
		final String stamp = pCatalog == null || pSourceId == null || who == null ? null : DeducedScanCatalog.stamp(pCatalog);
		return DeducedScanCatalog.cached(pKind + "|" + who + "|" + pSourceId, stamp, System.currentTimeMillis(), pReader, pLoader);
	}

	/** Pure apart from the cache itself: the lookup, with the clock and the table stamp given. */
	@SuppressWarnings("unchecked")
	static <T> T cached(final String pKey, final String pStamp, final long pNow, final CatalogReader.Reader pReader, final Loader<T> pLoader)
	{
		if (pStamp == null)
		{
			DeducedScanCatalog.cacheLoads++;
			return pLoader.load();
		}
		synchronized (DeducedScanCatalog.CACHE)
		{
			final Cached hit = DeducedScanCatalog.CACHE.get(pKey);
			if (hit != null && hit.stamp.equals(pStamp) && pNow - hit.at <= DeducedScanCatalog.CACHE_MILLIS)
			{
				if (hit.restricted)
				{
					pReader.markRestricted();
				}
				return (T) hit.value;
			}
		}
		final boolean before = pReader.restricted();
		DeducedScanCatalog.cacheLoads++;
		final T value = pLoader.load();
		final boolean restricted = pReader.restricted() && !before;
		synchronized (DeducedScanCatalog.CACHE)
		{
			DeducedScanCatalog.CACHE.remove(pKey);
			DeducedScanCatalog.CACHE.put(pKey, new Cached(pStamp, pNow, value, restricted));
			while (DeducedScanCatalog.CACHE.size() > DeducedScanCatalog.CACHE_MAX)
			{
				final java.util.Iterator<String> oldest = DeducedScanCatalog.CACHE.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
		}
		return value;
	}

	/** The last-modification dates of the tables a scan reads, or null when either is unknown. */
	private static String stamp(final Adaptation pCatalog)
	{
		try
		{
			final java.util.Date assets = pCatalog.getTable(DeducedScanCatalog.ASSET).getLastModificationDate();
			final java.util.Date columns = pCatalog.getTable(DeducedScanCatalog.COLUMN).getLastModificationDate();
			return assets == null || columns == null ? null : assets.getTime() + "/" + columns.getTime();
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Forgets every cached answer - for a test. */
	static void clearCache()
	{
		synchronized (DeducedScanCatalog.CACHE)
		{
			DeducedScanCatalog.CACHE.clear();
		}
	}
}
