package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.core.CatalogKeys;
import com.on.ebx.factory.deducedscan.AssetReconciliation;
import com.on.ebx.factory.deducedscan.ColumnMeasurer;
import com.on.ebx.factory.deducedscan.ReadRecipe;
import com.on.ebx.factory.deducedscan.TableProposal;
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
 * Deduced-scan lot (2026-09-24, read-recipe-contract.md sec 1): writes ONE {@code Asset} row (+ its {@code
 * Column} rows) into the data catalog's own dataset ({@link CatalogReader#dataset}) for a table the "Name
 * and key" stage proposed - {@code scanOrigin=DEDUCED}, {@code scanState=TO_CONFIRM}, {@code readRecipe} the
 * exact JSON {@link ReadRecipe} builds. Writes across dataspaces the same way {@code ebx.PerspectiveInstaller}/
 * {@code ebx.DmaToolbarWriter} already do for their own technical datasets: {@code
 * ProgrammaticService.createForSession(pSession, catalogDataset.getHome())}, not the factory's own home.
 * <p>
 * <b>The new {@code Asset}/{@code Column} fields do not exist yet on the live instance</b> (task brief,
 * confirmed by {@link CatalogReader#scanInfo} needing its own schema-node guard to read them back): every
 * write of a {@code scan*}/{@code readRecipe} field here is ALSO guarded the same way (checked against the
 * table's own {@code getTableOccurrenceRootNode()} before {@code setValue} is even attempted) - on today's
 * catalog this writer still creates a perfectly usable {@code Asset}/{@code Column} row (qualifiedName,
 * schemaName, kind, columns, primaryKey), it just cannot mark it DEDUCED/TO_CONFIRM or carry its recipe until
 * the catalog model is upgraded. {@link Result#scanFieldsWritten} tells the caller which happened, so {@code
 * ebx.DeducedScanRun} can say so in its own progress summary rather than claiming a confirmation gate that
 * silently does nothing.
 * <p>
 * Audit D-07 (2026-10-06): the asset is keyed {@code assetKey = source:tableName} and each column {@code columnKey =
 * assetKey.name} ({@link CatalogKeys}), both written here. "Same source + same table name" is therefore the same asset:
 * the writer first looks the key up and reconciles with that row whatever recipe wrote it (a second operation naming
 * the same table, a re-scan whose recipe differs) - the latest recipe and columns replace the previous ones and the
 * asset goes back to {@code TO_CONFIRM} ({@link AssetReconciliation#reconcile(String, List, List, boolean)}); only when
 * the key is free does it fall back to the same-recipe lookup (an earlier run that named the table differently). A row
 * under that key that a deduced scan did not write (an official scan of the source) is left untouched
 * ({@link Result#keptOfficial}).
 */
public final class DeducedScanCatalogWriter
{
	private static final Path ASSET = Path.parse("/root/Asset");
	private static final Path COLUMN = Path.parse("/root/Column");

	/** Audit F-02 (catalog reads): why the writer reads the catalog without the person's session. */
	static final String READ_REASON = "the deduced-scan writer, run by the job queue on the system session: reconciles a fresh table with every asset the"
		+ " catalog already holds for the source (hidden ones included) so that a rescan never creates a duplicate; nothing it reads is shown";

	private DeducedScanCatalogWriter()
	{
	}

	public static final class Result
	{
		/** {@code Asset.assetKey} of the row written or reconciled ({@code source:tableName}, or the key of the same-recipe row kept under its own name). */
		public final String assetKey;
		/** False when the catalog does not have the {@code scan*}/{@code readRecipe} fields yet - see class Javadoc. */
		public final boolean scanFieldsWritten;
		/**
		 * Fix lot (2026-09-27, round 5, task item 2): which of the four outcomes {@link AssetReconciliation}
		 * (or the lack of any prior asset at all) produced for THIS table - {@code ebx.DeducedScanRun} tallies
		 * these into a {@code DeducedScanRunSummary.Counts} to build the run's own end message instead of one
		 * frozen phrase for every outcome. {@code true}/{@code true} is never a valid combination (created and
		 * reopened are mutually exclusive by construction, see {@link #write}).
		 */
		public final boolean created;
		/** True when an existing asset's columns changed and it was reset to {@code TO_CONFIRM} (see {@link AssetReconciliation}) - false for a new asset or an unchanged reconciliation. */
		public final boolean reopened;
		/** The existing asset's {@code scanState} BEFORE this write, ONLY when this was a reconciliation of an UNCHANGED asset (kept as-is, {@link #reopened} false) - {@code CONFIRMED}/{@code LEFT_OUT}/{@code TO_CONFIRM}; empty for a new asset ({@link #created}) or a reopened one (its previous state is not what matters there). */
		public final String keptState;
		/** Audit D-07: the key already held a row read another way (another recipe) - reconciled into it, the latest recipe kept. */
		public final boolean otherRecipeReconciled;
		/** Audit D-07: the key already held a row of an official scan of the source - left untouched, nothing written. */
		public final boolean keptOfficial;

		public Result(final String pAssetKey, final boolean pScanFieldsWritten)
		{
			this(pAssetKey, pScanFieldsWritten, true, false, "", false, false);
		}

		public Result(final String pAssetKey, final boolean pScanFieldsWritten, final boolean pCreated, final boolean pReopened, final String pKeptState)
		{
			this(pAssetKey, pScanFieldsWritten, pCreated, pReopened, pKeptState, false, false);
		}

		public Result(final String pAssetKey, final boolean pScanFieldsWritten, final boolean pCreated, final boolean pReopened, final String pKeptState,
			final boolean pOtherRecipeReconciled, final boolean pKeptOfficial)
		{
			this.assetKey = pAssetKey;
			this.scanFieldsWritten = pScanFieldsWritten;
			this.created = pCreated;
			this.reopened = pReopened;
			this.keptState = pKeptState == null ? "" : pKeptState;
			this.otherRecipeReconciled = pOtherRecipeReconciled;
			this.keptOfficial = pKeptOfficial;
		}
	}

	/**
	 * @param pSourceId the catalog {@code Source} row id ({@code ProjectSource.catalogSource})
	 * @param pProposal the confirmed table name + key columns (contract: the LLM's own proposal, validated by
	 *        {@link TableProposal#firstViolation} before this is ever called)
	 * @param pMeasured every measured column, in the order the sample gave them (becomes {@code Column.position})
	 * @param pRecipe how to read the whole table - written into {@code Asset.readRecipe} verbatim
	 */
	public static Result write(final Session pSession, final Adaptation pCatalog, final String pSourceId, final TableProposal pProposal,
		final List<ColumnMeasurer.Measured> pMeasured, final ReadRecipe pRecipe) throws OperationException
	{
		return DeducedScanCatalogWriter.write(pSession, pCatalog, pSourceId, pProposal, pMeasured, pRecipe, pSession == null ? null : pSession.getLocale());
	}

	/**
	 * Same as {@link #write(Session, Adaptation, String, TableProposal, List, ReadRecipe)}, with the message
	 * locale given explicitly rather than read from {@code pSession} - job-session-unwrapped fix (2026-09-25):
	 * a job runs with the SYSTEM session (never wrapped to fake its locale), so a caller that must report a
	 * failure in the launcher's own locale has to pass it here instead of relying on {@code
	 * pSession.getLocale()}. {@code pLocale} null falls back to {@link Locale#ENGLISH}, same as the no-locale
	 * overload.
	 */
	public static Result write(final Session pSession, final Adaptation pCatalog, final String pSourceId, final TableProposal pProposal,
		final List<ColumnMeasurer.Measured> pMeasured, final ReadRecipe pRecipe, final Locale pLocale) throws OperationException
	{
		final AdaptationTable assetTable = pCatalog.getTable(DeducedScanCatalogWriter.ASSET);
		final AdaptationTable columnTable = pCatalog.getTable(DeducedScanCatalogWriter.COLUMN);
		// audit F-02 (catalog reads): the writer reconciles with EVERY asset of the source, whoever may see it
		final CatalogReader.Reader reader = CatalogReader.Reader.system(DeducedScanCatalogWriter.READ_REASON);
		final boolean scanFieldsExist = assetTable.getTableOccurrenceRootNode().getNode(Path.parse("./scanOrigin")) != null;
		// fix lot (2026-09-26): Asset.physicalName is MANDATORY (DataCatalog.xsd, minOccurs=1) and was never
		// written here - every deduced Asset row was invalid the moment it was created. Read elsewhere in this
		// module unguarded (DataProductPublication), so it is a long-standing, already-live field: written
		// unconditionally, unlike the genuinely-new scan*/sample* fields below. firstSeen/lastSeen (also old,
		// foundational catalog fields - part of the default tabularView, unlike the 2026-09-24 scan* additions)
		// are filled the same way an official scan would, guarded here too. Asset.assetKey and Column.columnKey are
		// the primary keys since audit D-07: always written.
		final boolean firstLastSeenExist = assetTable.getTableOccurrenceRootNode().getNode(Path.parse("./firstSeen")) != null;
		final boolean columnFirstLastSeenExist = columnTable.getTableOccurrenceRootNode().getNode(Path.parse("./firstSeen")) != null;
		// contract sec 1: "reuse whatever Column already has, add nothing if an equivalent exists" -
		// ./lastDistinctCount already exists (CatalogReader.Column#distinctCount) and is reused verbatim for
		// the distinct count; ./sampleFilled and ./sampleExample have no existing equivalent (a rate is not a
		// count, and nothing at all names an example value), so those two are the only genuinely new Column
		// fields, guarded the same way as the Asset scan* fields above.
		final boolean sampleFieldsExist = columnTable.getTableOccurrenceRootNode().getNode(Path.parse("./sampleFilled")) != null;
		// fix lot (2026-09-26): the real number of rows the sample actually read (ColumnMeasurer.Measured#sampleSize,
		// same for every column of one asset - one sample per table, never per column) - was hardcoded to 0 before
		// this fix. Reused as Asset.rowCount (an official scan's own "how many rows" field): approximate for a
		// deduced scan (capped at DeducedScanSample.MAX_ITEMS), but genuinely measured, never a placeholder.
		final int sampleSize = pMeasured.isEmpty() ? 0 : pMeasured.get(0).sampleSize;
		final Date now = new Date();

		// fix lot (2026-09-26, defect 9): a second deduced scan of the SAME recipe (connector/operation/
		// inputs/itemsPath) must reconcile with whatever Asset it already wrote, never create a duplicate -
		// looked up BEFORE the procedure (a plain read, same "read outside, write inside" convention
		// DeducedScanCatalog#ensureSource already follows for its own Source lookup). Never searched at all
		// when the catalog does not have readRecipe yet (scanFieldsExist false) - degrades to the original
		// always-create behaviour, same as every other scan*-guarded branch in this class.
		// audit D-07: the natural key first - same source + same table name = the same asset, whatever recipe wrote it.
		final String assetKey = CatalogKeys.assetKey(pSourceId, pProposal.tableName);
		if (assetKey == null)
		{
			throw EngineRefusal.coded("invalid.deducedTable", "A deduced table needs a catalog source and a name: source '" + pSourceId + "', name '" + pProposal.tableName + "'.");
		}
		if (assetKey.length() > CatalogKeys.ASSET_KEY_MAX)
		{
			throw EngineRefusal.coded("invalid.catalogKeyTooLong", "The table name '" + pProposal.tableName + "' is too long for a catalog key (" + CatalogKeys.ASSET_KEY_MAX + " characters with the source).", pProposal.tableName, CatalogKeys.ASSET_KEY_MAX);
		}
		final DeducedScanCatalog.ExistingAsset byKey = DeducedScanCatalog.findByKey(reader, pCatalog, assetKey);
		if (byKey != null && (!scanFieldsExist || !"DEDUCED".equals(byKey.scanOrigin)))
		{
			// an official scan (or a catalog without scan fields) already holds this table: never overwritten by a sample
			return new Result(assetKey, scanFieldsExist, false, false, byKey.scanState, false, true);
		}
		final DeducedScanCatalog.ExistingAsset existing = byKey != null ? byKey : DeducedScanCatalog.findByRecipe(reader, pCatalog, pSourceId, pRecipe);
		final boolean otherRecipe = byKey != null && !pRecipe.equals(byKey.readRecipe);
		final List<String> newColumnNames = DeducedScanCatalogWriter.namesOf(pMeasured);
		final AssetReconciliation.Decision decision = existing == null ? null
			: AssetReconciliation.reconcile(existing.scanState, existing.columnNames, newColumnNames, otherRecipe);
		// found by recipe under another name, with changed columns: it takes the fresh name, i.e. the fresh key (free: byKey is null)
		final boolean rename = existing != null && byKey == null && decision.columnsChanged && !assetKey.equals(existing.key);

		final String[] assetKeyHolder = new String[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pCatalog.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				if (existing == null)
				{
					final ValueContextForUpdate assetValue = pContext.getContextForNewOccurrence(assetTable);
					assetValue.setValue(assetKey, Path.parse("./assetKey"));
					assetValue.setValue(pSourceId, Path.parse("./source"));
					assetValue.setValue(pProposal.tableName, Path.parse("./qualifiedName"));
					// mandatory (DataCatalog.xsd, minOccurs=1) - an official scan's own Connectors mapping fills it
					// from the same "name" the qualified name comes from (CatalogFlows: {"name","./physicalName",...,"ALWAYS"})
					assetValue.setValue(pProposal.tableName, Path.parse("./physicalName"));
					assetValue.setValue("", Path.parse("./schemaName"));
					assetValue.setValue("TABLE", Path.parse("./kind"));
					assetValue.setValue("ACTIVE", Path.parse("./status"));
					assetValue.setValue(BigDecimal.valueOf(sampleSize), Path.parse("./rowCount"));
					if (firstLastSeenExist)
					{
						assetValue.setValue(now, Path.parse("./lastSeen"));
						assetValue.setValue(now, Path.parse("./firstSeen"));
					}
					if (scanFieldsExist)
					{
						assetValue.setValue("DEDUCED", Path.parse("./scanOrigin"));
						assetValue.setValue("TO_CONFIRM", Path.parse("./scanState"));
						assetValue.setValue(Integer.valueOf(TableProposal.clampConfidence(pProposal.confidence)), Path.parse("./scanConfidence"));
						assetValue.setValue(pProposal.rationale, Path.parse("./scanRationale"));
						assetValue.setValue(pRecipe.toJson(), Path.parse("./readRecipe"));
					}
					pContext.doCreateOccurrence(assetValue, assetTable);
					assetKeyHolder[0] = assetKey;
				}
				else
				{
					// reconciliation (defect 9): update the SAME Asset row rather than creating a second one -
					// rowCount/lastSeen always refreshed; qualifiedName/physicalName/scanConfidence/scanRationale
					// only touched when the columns actually changed (decision.columnsChanged) - a CONFIRMED/
					// LEFT_OUT decision (and a person's own rename) otherwise survives a re-scan untouched, per
					// AssetReconciliation's own contract.
					// every previous Column row of this asset is replaced below - deleted first (read fresh inside the
					// transaction, never the read-only snapshot findByRecipe/findByKey collected before it started).
					final CatalogReader.Rows oldColumns = CatalogReader.rows(reader, columnTable, "./asset=" + CatalogReader.quote(existing.key));
					final List<Adaptation> toDelete = new ArrayList<Adaptation>();
					try
					{
						for (Adaptation oldColumn = oldColumns.next(); oldColumn != null; oldColumn = oldColumns.next())
						{
							toDelete.add(oldColumn);
						}
					}
					finally
					{
						oldColumns.close();
					}
					for (final Adaptation oldColumn : toDelete)
					{
						pContext.doDelete(oldColumn.getAdaptationName(), false);
					}
					Adaptation row = CatalogReader.assetRow(reader, pCatalog, existing.key);
					if (row == null)
					{
						throw EngineRefusal.coded("conflict.assetGone", "The catalog asset '" + existing.key + "' disappeared during the deduced scan.", existing.key);
					}
					if (rename)
					{
						// audit D-07: a new name is a new key - the row moves (DeducedScanCatalog.rekey), its columns are rewritten below
						row = DeducedScanCatalog.rekey(pContext, pCatalog, row, pProposal.tableName, true);
					}
					assetKeyHolder[0] = CatalogReader.assetKeyOf(row);
					final ValueContextForUpdate assetValue = pContext.getContext(row.getAdaptationName());
					assetValue.setValue(BigDecimal.valueOf(sampleSize), Path.parse("./rowCount"));
					if (firstLastSeenExist)
					{
						assetValue.setValue(now, Path.parse("./lastSeen"));
					}
					if (scanFieldsExist)
					{
						assetValue.setValue(decision.scanState, Path.parse("./scanState"));
						if (decision.columnsChanged)
						{
							// qualifiedName/physicalName: the name in the key (found by key: the same; renamed: set by rekey)
							assetValue.setValue(Integer.valueOf(TableProposal.clampConfidence(pProposal.confidence)), Path.parse("./scanConfidence"));
							assetValue.setValue(decision.reason, Path.parse("./scanRationale"));
						}
						// the recipe this scan read the table with - the same one (found by recipe), or the latest one (found by key)
						assetValue.setValue(pRecipe.toJson(), Path.parse("./readRecipe"));
					}
					pContext.doModifyContent(row, assetValue);
				}

				int position = 0;
				for (final ColumnMeasurer.Measured column : pMeasured)
				{
					position++;
					final ValueContextForUpdate columnValue = pContext.getContextForNewOccurrence(columnTable);
					columnValue.setValue(assetKeyHolder[0], Path.parse("./asset"));
					columnValue.setValue(column.name, Path.parse("./name"));
					columnValue.setValue(Integer.valueOf(position), Path.parse("./position"));
					columnValue.setValue(DeducedScanCatalogWriter.dataType(column.type), Path.parse("./dataType"));
					columnValue.setValue(column.type, Path.parse("./nativeType"));
					columnValue.setValue(Boolean.valueOf(column.filled < column.sampleSize), Path.parse("./nullable"));
					// reconciliation (defect 9): columns unchanged -> carry the PREVIOUS key selection forward (a
					// person may have chosen another key via the landing key chooser since - never clobbered by a
					// re-scan); columns changed, or no previous asset at all -> the fresh proposal, same as a
					// brand new asset.
					final boolean primaryKey = existing != null && decision != null && !decision.columnsChanged
						? Boolean.TRUE.equals(existing.columnPrimaryKey.get(column.name))
						: pProposal.keyColumns.contains(column.name);
					columnValue.setValue(Boolean.valueOf(primaryKey), Path.parse("./primaryKey"));
					columnValue.setValue("ACTIVE", Path.parse("./status"));
					columnValue.setValue(BigDecimal.valueOf(column.distinct), Path.parse("./lastDistinctCount"));
					if (sampleFieldsExist)
					{
						columnValue.setValue(Integer.valueOf(column.filled), Path.parse("./sampleFilled"));
						columnValue.setValue(column.example.length() > 200 ? column.example.substring(0, 200) : column.example, Path.parse("./sampleExample"));
					}
					if (columnFirstLastSeenExist)
					{
						columnValue.setValue(now, Path.parse("./lastSeen"));
						columnValue.setValue(now, Path.parse("./firstSeen"));
					}
					// audit D-07: the primary key, always written - "assetKey.name" from the asset's REAL key (the row's own,
					// which is source:qualifiedName for every row this writer creates or re-keys)
					columnValue.setValue(CatalogKeys.columnKey(assetKeyHolder[0], column.name), Path.parse("./columnKey"));
					pContext.doCreateOccurrence(columnValue, columnTable);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveFailed", result.getExceptionFullMessage(pLocale == null ? Locale.ENGLISH : pLocale));
		}
		// fix lot (2026-09-27, round 5, task item 2): tell the caller which outcome this write was, so it can
		// build an end-of-run message from what actually happened rather than a single frozen phrase - see
		// Result's own Javadoc. existing==null: a brand new asset. existing!=null && decision.columnsChanged:
		// reopened (back to TO_CONFIRM). existing!=null && !decision.columnsChanged: kept exactly as it was,
		// existing.scanState is what it was kept AT.
		return new Result(assetKeyHolder[0], scanFieldsExist, existing == null, existing != null && decision.columnsChanged,
			existing != null && !decision.columnsChanged ? existing.scanState : "", otherRecipe, false);
	}

	/** {@code Column.dataType} is free text (see {@code CatalogReader.Column#dataType}'s own Javadoc) - a small, stable vocabulary rather than {@link ColumnMeasurer}'s own display words (kept distinct on purpose: {@code dataType} is meant to be programmatically comparable across every kind of scan, official included). */
	private static String dataType(final String pMeasuredType)
	{
		if (ColumnMeasurer.NUMBER.equals(pMeasuredType))
		{
			return "NUMBER";
		}
		if (ColumnMeasurer.DATE_TIME.equals(pMeasuredType))
		{
			return "TIMESTAMP";
		}
		if (ColumnMeasurer.YES_NO.equals(pMeasuredType))
		{
			return "BOOLEAN";
		}
		return "TEXT";
	}

	/** Column names visible after {@link #write} - a small convenience for a caller that only wants names (used by {@code DeducedScanRun}'s own summary). Never used to skip re-measuring: names alone are never trusted for anything but display. */
	public static List<String> namesOf(final List<ColumnMeasurer.Measured> pMeasured)
	{
		final List<String> out = new ArrayList<String>();
		for (final ColumnMeasurer.Measured column : pMeasured)
		{
			out.add(column.name);
		}
		return out;
	}
}
