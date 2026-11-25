package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.CatalogRightMessages;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ChangeColumnRule;
import com.on.ebx.factory.landing.ColumnRefresh;
import com.on.ebx.factory.landing.SchemaDrift;
import com.on.ebx.factory.landing.LandingModel;
import com.on.ebx.factory.landing.RelationRule;
import com.on.ebx.factory.landing.SchemaFilter;
import com.on.ebx.factory.landing.FlattenedDocument;
import com.on.ebx.factory.landing.SourceKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Plans the landing of one source of a project: reads the scanned tables of the catalog source (within
 * the schemas of the application) and writes a {@code LandingTable} and its {@code LandingColumn} rows for
 * each, names and source key proposed. Nothing is generated in EBX yet: the plan is data a person can
 * correct (scope, key columns) before the landing model is built from it.
 * <p>
 * Run again, it only adds what is new. A table already planned keeps its name and its rows, whatever the
 * user changed on them; one already generated whose scanned columns no longer match turns {@code DRIFTED}.
 * Acts with the rights of the session it is given.
 * <p>
 * {@code schemaFilter} is matched against the catalog's own schema names by {@link
 * com.on.ebx.factory.landing.SchemaFilter}, not by exact equality: the catalog stores a schema bare or
 * database-qualified depending on how the source was scanned. So that a plan that finds nothing explains
 * itself instead of coming back silently empty, the answer also carries {@code scannedSchemas} (every
 * schema name the catalog actually holds for the source), {@code unmatchedSchemas} (filter entries that
 * matched none of them) and {@code ambiguousSchemas} (filter entries that matched more than one, with the
 * schemas each one matched).
 * <p>
 * Once the tables are planned, {@link RelationRule} runs over every {@code LandingTable} of the project
 * source - new ones just written and ones from an earlier run alike, read back from their
 * {@code LandingColumn} rows - and its proposals are written as {@code LandingRelation} rows (origin
 * {@code RULE}, status {@code PROPOSED}). Idempotent like the rest of the plan: a relation already on file
 * for the same (from table, from columns, to table) is left alone whatever its current status, so a
 * rejected one never comes back. The answer's {@code relations}, {@code relationsSkipped} and
 * {@code loadOrder} report what was proposed, why some columns were not, and the parents-first order to
 * load the tables in.
 */
public final class LandingPlanner
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private LandingPlanner()
	{
	}

	public static ObjectNode plan(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		return LandingPlanner.plan(pSession, pFactory, pProjectSourceId, true);
	}

	/**
	 * UX lot U2 (2026-10-13): {@code pSelectNew = false} writes each table this plan ADDS unticked ({@code LandingTable.selected = false}), so
	 * step 2 shows what the source holds and the person keeps what they want (measured the same day: 17 Salesforce tables all ticked, 15 to
	 * untick one by one). A table planned earlier is never touched, ticked or not. Every other caller (REST, accelerators, the step-3 Plan
	 * button) keeps {@link #plan(Session, Adaptation, String)}'s ticked default.
	 */
	public static ObjectNode plan(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final boolean pSelectNew) throws OperationException
	{
		return LandingPlanner.plan(pSession, pFactory, pProjectSourceId, pSelectNew, false);
	}

	/**
	 * Landing test fixes (2026-10-17): {@code pRefreshProposed = true} (the "Scan again" of step 2) also brings the columns of every table of the application that is planned
	 * but not generated yet ({@code PROPOSED}) in line with the catalog's current scan: columns added, columns gone (and renamed ones, which are both), the person's tick and a
	 * key whose columns still exist kept, a key with a column gone dropped. A generated or loaded table keeps its drift handling, untouched by this.
	 */
	public static ObjectNode plan(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final boolean pSelectNew, final boolean pRefreshProposed)
		throws OperationException
	{
		final ObjectNode out = LandingPlanner.MAPPER.createObjectNode();
		final Adaptation source = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		if (source == null)
		{
			throw EngineRefusal.coded("missing.projectSource", "Project source " + pProjectSourceId + " not found", pProjectSourceId);
		}
		final String catalogSource = source.getString(FactoryPaths.ProjectSource.CATALOG_SOURCE);
		if (catalogSource == null || catalogSource.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noCatalogSource", "Project source " + pProjectSourceId + " names no catalog source: nothing to plan the landing from", pProjectSourceId);
		}
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			throw EngineRefusal.coded("missing.catalogModule", "The data catalog (module ebx-data-catalog) is not installed on this instance: the landing is planned from its scanned structures");
		}
		final List<String> schemas = new ArrayList<String>();
		final List<?> filter = source.getList(FactoryPaths.ProjectSource.SCHEMA_FILTER);
		if (filter != null)
		{
			for (final Object schema : filter)
			{
				schemas.add(String.valueOf(schema));
			}
		}
		// audit F-02 (catalog reads): planning copies the structure of the catalog's assets into the project, so it reads them
		// as the caller - the person (wizard, REST: their catalog right must let them see the assets) or the job's system
		// session (PLAN job, onboarding job: gated when queued, LandingSourceRun / SourcesRun). Hidden assets refuse the plan
		// with a message naming the right, never an empty plan that reads as "the source has no table".
		final CatalogReader.Reader reader = CatalogReader.Reader.of(pSession);
		final List<CatalogReader.Asset> assets = CatalogReader.assets(reader, catalog, catalogSource.trim(), schemas);
		// so an empty plan explains itself: which schemas the catalog actually has for this source, and which
		// filter entries (if any) matched none of them, or matched more than one (several databases sharing a name)
		final List<String> scannedSchemas = CatalogReader.distinctSchemas(reader, catalog, catalogSource.trim());
		if (assets.isEmpty() && reader.restricted())
		{
			throw EngineRefusal.coded("conflict.catalogHidden", CatalogRightMessages.hidden("the catalog assets of source " + catalogSource.trim()) + " The landing was not planned.");
		}
		final SchemaFilter.Report schemaFilterReport = SchemaFilter.report(schemas, scannedSchemas);

		// what earlier plans gave this source: names stay, rows are left alone
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final Map<String, Adaptation> planned = new HashMap<String, Adaptation>();
		final LandingModel.Builder names = new LandingModel.Builder();
		final RequestResult existing = tables.createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = existing.nextAdaptation(); row != null; row = existing.nextAdaptation())
			{
				planned.put(row.getString(FactoryPaths.LandingTable.SOURCE_OBJECT), row);
				names.reserve(row.getString(FactoryPaths.LandingTable.TABLE_NAME));
			}
		}
		finally
		{
			existing.close();
		}

		final ArrayNode report = out.putArray("tables");
		final int[] counts = new int[6]; // planned, columns, already there, drifted, catalogAsset re-pointed, refreshed
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final CatalogReader.Asset asset : assets)
				{
					final ObjectNode line = report.addObject();
					line.put("sourceObject", asset.qualifiedName);
					final Adaptation known = planned.get(asset.qualifiedName);
					if (known != null)
					{
						counts[2]++;
						line.put("tableName", known.getString(FactoryPaths.LandingTable.TABLE_NAME));
						// audit D-07: catalogAsset holds the asset KEY. A table planned before (EBX converted the old xs:int to
						// the digits of an id that no longer exists), or planned against an asset since re-keyed, gets the key of
						// the asset this plan matched it to (same source, qualifiedName = sourceObject) written back here.
						final Adaptation current = LandingPlanner.repointCatalogAsset(pContext, known, asset.key);
						if (current != known)
						{
							counts[4]++;
							line.put("catalogAssetRepointed", true);
						}
						line.put("outcome", LandingPlanner.drift(pContext, pFactory, current, asset) ? "DRIFTED" : "ALREADY_PLANNED");
						if ("DRIFTED".equals(line.path("outcome").asText()))
						{
							counts[3]++;
						}
						else if (pRefreshProposed && "PROPOSED".equals(current.getString(FactoryPaths.LandingTable.STATUS)) && !asset.columns.isEmpty()
							&& LandingPlanner.refreshColumns(pContext, pFactory, current, asset, line))
						{
							line.put("outcome", "REFRESHED");
							counts[5]++;
						}
						continue;
					}
					if (asset.columns.isEmpty())
					{
						line.put("outcome", "SKIPPED_NO_SCANNED_COLUMN");
						continue;
					}
					final List<String> columnNames = new ArrayList<String>();
					final List<SourceKeyRule.Candidate> candidates = new ArrayList<SourceKeyRule.Candidate>();
					for (final CatalogReader.Column column : asset.columns)
					{
						columnNames.add(column.name);
						candidates.add(new SourceKeyRule.Candidate(column.name, column.primaryKey, column.nullRate, column.uniqueRate));
					}
					final LandingModel.Table named = names.table(asset.qualifiedName, columnNames);
					final String[] parts = asset.qualifiedName.split("[.]");
					final SourceKeyRule.Proposal key = SourceKeyRule.propose(parts[parts.length - 1], candidates);
					// fix lot (2026-09-26): SourceKeyRule.propose has no notion of the catalog it read from, so a
					// declared key ALWAYS reads "Primary key declared at the source." - true for a genuine database
					// primary key, but also for a deduced-scan table's key once a person confirms it (Column.primaryKey
					// is set from the language model's own proposal, DeducedScanCatalogWriter). Told apart here, the
					// one place that DOES know which catalog the key came from (Asset.scanOrigin). key.confidence==100
					// here is not a signature to detect this case (that fragile trick was in KeyCertainty, since fixed,
					// see its own Javadoc) - it is simply what SourceKeyRule.propose always returns alongside a fully
					// declared key.columns list, so pairing it with scanOrigin=="DEDUCED" is exact: every column in
					// key.columns is one this deduced scan's own proposal marked primaryKey, none from the naming rule.
					final boolean deducedConfirmedKey = "DEDUCED".equals(asset.scanOrigin) && !key.columns.isEmpty() && key.confidence == 100;
					final String keyRationale = deducedConfirmedKey
						? com.on.ebx.factory.landing.KeyTexts.deduced().stored()
						: key.stored();

					final ValueContextForUpdate table = pContext.getContextForNewOccurrence(tables);
					table.setValue(pProjectSourceId, FactoryPaths.LandingTable.PROJECT_SOURCE);
					table.setValue(asset.key, FactoryPaths.LandingTable.CATALOG_ASSET);
					table.setValue(asset.qualifiedName, FactoryPaths.LandingTable.SOURCE_OBJECT);
					table.setValue(named.tableName, FactoryPaths.LandingTable.TABLE_NAME);
					if (!pSelectNew)
					{
						table.setValue(Boolean.FALSE, FactoryPaths.LandingTable.SELECTED);
					}
					table.setValue(Integer.valueOf(asset.columns.size()), FactoryPaths.LandingTable.COLUMN_COUNT);
					table.setValue(asset.rowCount, FactoryPaths.LandingTable.SOURCE_ROW_COUNT);
					// landing test fixes (2026-10-17): a file read as one document holding a list is flagged, not planned silently
					final boolean flattened = FlattenedDocument.looksFlattened(columnNames);
					table.setValue(com.on.ebx.factory.landing.KeyTexts.fit((flattened ? FlattenedDocument.MESSAGE + " " : "") + "Source key: " + keyRationale, com.on.ebx.factory.landing.TextLimits.MESSAGE_MAX_LENGTH),
						FactoryPaths.LandingTable.MESSAGE);
					// landing key-study lot (2026-09-23): the same figures as their own fields, read back by the wizard
					table.setValue(Integer.valueOf(key.confidence), FactoryPaths.LandingTable.KEY_CONFIDENCE);
					table.setValue(keyRationale, FactoryPaths.LandingTable.KEY_RATIONALE);
					// volume wave 2, lot V2-E (2026-10-03): a change column picked from the catalog's own column metadata, so
					// a reload reads only the rows changed since the last load - never for a file or a deduced table (their
					// flows are not generated SQL: the column would do nothing), and never from the data. A table planned
					// earlier is not touched here (a person's choice, "none" included, survives every re-plan).
					final ChangeColumnRule.Choice change = LandingPlanner.changeColumn(asset);
					if (change != null)
					{
						table.setValue(change.column, FactoryPaths.LandingTable.CHANGE_COLUMN);
						table.setValue(change.kind, FactoryPaths.LandingTable.CHANGE_COLUMN_KIND);
					}
					final String tableId = pContext.doCreateOccurrence(table, tables).getOccurrencePrimaryKey().format();

					final AdaptationTable columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
					for (int i = 0; i < asset.columns.size(); i++)
					{
						final CatalogReader.Column scanned = asset.columns.get(i);
						final ValueContextForUpdate column = pContext.getContextForNewOccurrence(columns);
						column.setValue(tableId, FactoryPaths.LandingColumn.LANDING_TABLE);
						column.setValue(Integer.valueOf(i + 1), FactoryPaths.LandingColumn.POSITION);
						column.setValue(scanned.name, FactoryPaths.LandingColumn.SOURCE_COLUMN);
						column.setValue(named.columns.get(i).fieldName, FactoryPaths.LandingColumn.FIELD_NAME);
						column.setValue(scanned.nativeType == null || scanned.nativeType.trim().isEmpty() ? scanned.dataType : scanned.nativeType, FactoryPaths.LandingColumn.SOURCE_DATA_TYPE);
						column.setValue(scanned.nullable, FactoryPaths.LandingColumn.SOURCE_NULLABLE);
						final boolean inKey = key.columns.contains(scanned.name);
						column.setValue(Boolean.valueOf(inKey), FactoryPaths.LandingColumn.SOURCE_KEY);
						if (inKey)
						{
							// SourceKeyRule.propose returns ONLY declared columns when any candidate is declaredKey (the
							// catalog's own Column.primaryKey - composite-key lot, 2026-09-24), so every column this
							// proposal put in the key is either ALL declared or ALL from the naming rule, never a mix:
							// reading scanned.primaryKey per column here is exact, not a guess. A deduced-scan-confirmed
							// key is NOT "declared by the source" (fix lot, 2026-09-26) - it now has its own enumeration
							// value, DEDUCED (fix lot, 2026-09-27, round 5, task item 3: KEY_ORIGIN in tools/build_xsd.py),
							// replacing the earlier reuse of plain LLM ("proposed by the language model") + a forced
							// confidence of exactly 100 as a fragile stand-in signature - see KeyCertainty's own Javadoc.
							column.setValue(deducedConfirmedKey ? "DEDUCED" : scanned.primaryKey ? "SOURCE" : "RULE", FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN);
						}
						if (scanned.nullRate != null)
						{
							column.setValue(BigDecimal.ONE.subtract(scanned.nullRate).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP), FactoryPaths.LandingColumn.FILL_RATE);
						}
						column.setValue(scanned.distinctCount, FactoryPaths.LandingColumn.DISTINCT_COUNT);
						column.setValue(scanned.maxLength, FactoryPaths.LandingColumn.MAX_LENGTH);
						column.setValue(LandingPlanner.inferredType(scanned.dataType), FactoryPaths.LandingColumn.INFERRED_TYPE);
						column.setValue(new ArrayList<String>(scanned.tags), FactoryPaths.LandingColumn.TAGS);
						pContext.doCreateOccurrence(column, columns);
						counts[1]++;
					}
					counts[0]++;
					line.put("tableName", named.tableName);
					line.put("outcome", "PLANNED");
					if (flattened)
					{
						line.put("warning", FlattenedDocument.MESSAGE);
					}
					line.put("columns", asset.columns.size());
					final ArrayNode keyColumns = line.putArray("sourceKey");
					for (final String name : key.columns)
					{
						keyColumns.add(name);
					}
					line.put("sourceKeyConfidence", key.confidence);
					if (change != null)
					{
						line.put("changeColumn", change.column);
						line.put("changeColumnKind", change.kind);
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.landingPlan", "Landing plan of project source " + pProjectSourceId + " failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pProjectSourceId);
		}
		out.put("catalogSource", catalogSource.trim());
		out.put("scannedTables", assets.size());
		out.put("planned", counts[0]);
		out.put("columnsPlanned", counts[1]);
		out.put("alreadyPlanned", counts[2]);
		out.put("drifted", counts[3]);
		out.put("catalogAssetRepointed", counts[4]);
		out.put("refreshed", counts[5]);
		final ArrayNode scannedSchemasNode = out.putArray("scannedSchemas");
		for (final String schema : scannedSchemas)
		{
			scannedSchemasNode.add(schema);
		}
		final ArrayNode unmatchedSchemasNode = out.putArray("unmatchedSchemas");
		for (final String entry : schemaFilterReport.unmatched)
		{
			unmatchedSchemasNode.add(entry);
		}
		final ArrayNode ambiguousSchemasNode = out.putArray("ambiguousSchemas");
		for (final SchemaFilter.Match match : schemaFilterReport.ambiguous)
		{
			final ObjectNode ambiguous = ambiguousSchemasNode.addObject();
			ambiguous.put("entry", match.entry);
			final ArrayNode matchedSchemas = ambiguous.putArray("schemas");
			for (final String schema : match.schemas)
			{
				matchedSchemas.add(schema);
			}
		}
		LandingPlanner.planRelations(pSession, pFactory, pProjectSourceId, out);
		return out;
	}

	/**
	 * The change column of a scanned table ({@link ChangeColumnRule}), {@code null} when none qualifies or when the table is
	 * not read through generated SQL: a file ({@code kind FILE}) and a deduced-scan table ({@code scanOrigin DEDUCED}) have their
	 * own feed or recipe flow, which never reads a watermark.
	 */
	static ChangeColumnRule.Choice changeColumn(final CatalogReader.Asset pAsset)
	{
		if ("FILE".equals(pAsset.kind) || "DEDUCED".equals(pAsset.scanOrigin))
		{
			return null;
		}
		final List<ChangeColumnRule.Candidate> candidates = new ArrayList<ChangeColumnRule.Candidate>();
		for (final CatalogReader.Column column : pAsset.columns)
		{
			candidates.add(new ChangeColumnRule.Candidate(column.name, LandingPlanner.inferredType(column.dataType), column.nullRate));
		}
		return ChangeColumnRule.choose(candidates);
	}

	/**
	 * Audit D-07: writes {@code pAssetKey} into {@code catalogAsset} when the stored value differs (empty, the digits of a
	 * pre-D-07 integer id, or an older key); answers the row as modified (what {@code doModifyContent} returns), or
	 * {@code pTable} itself when nothing needed writing.
	 */
	private static Adaptation repointCatalogAsset(final ProcedureContext pContext, final Adaptation pTable, final String pAssetKey) throws OperationException
	{
		if (pAssetKey == null || pAssetKey.isEmpty() || pAssetKey.equals(CatalogReader.storedAssetKey(pTable)))
		{
			return pTable;
		}
		final ValueContextForUpdate value = pContext.getContext(pTable.getAdaptationName());
		value.setValue(pAssetKey, FactoryPaths.LandingTable.CATALOG_ASSET);
		return pContext.doModifyContent(pTable, value);
	}

	/**
	 * Landing test fixes (2026-10-17): brings the {@code LandingColumn} rows of a {@code PROPOSED} table in line with the catalog's current columns
	 * ({@link ColumnRefresh}); answers whether anything changed, with the figures in {@code pLine} ({@code columnsBefore}, {@code columnsNow}, {@code columnsAdded},
	 * {@code columnsRemoved}, {@code keyDropped}, {@code summary}). Ticks are never touched; a key whose columns all remain stays as it is (a person's included); a key
	 * with a column gone is dropped and, like a table that had none, gets the naming rule's proposal over the new columns. Clears the application's validation when it changed something.
	 */
	private static boolean refreshColumns(final ProcedureContext pContext, final Adaptation pFactory, final Adaptation pTable, final CatalogReader.Asset pAsset, final ObjectNode pLine)
		throws OperationException
	{
		final String tableId = pTable.getOccurrencePrimaryKey().format();
		final List<Adaptation> rows = new ArrayList<Adaptation>();
		final RequestResult found = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(tableId));
		try
		{
			for (Adaptation row = found.nextAdaptation(); row != null; row = found.nextAdaptation())
			{
				rows.add(row);
			}
		}
		finally
		{
			found.close();
		}
		final List<ColumnRefresh.Existing> existing = new ArrayList<ColumnRefresh.Existing>();
		final List<String> takenFieldNames = new ArrayList<String>();
		final Map<String, Adaptation> rowByColumn = new HashMap<String, Adaptation>();
		for (final Adaptation row : rows)
		{
			final String name = row.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN);
			existing.add(new ColumnRefresh.Existing(name, row.getString(FactoryPaths.LandingColumn.FIELD_NAME), Boolean.TRUE.equals(row.get(FactoryPaths.LandingColumn.SOURCE_KEY))));
			takenFieldNames.add(row.getString(FactoryPaths.LandingColumn.FIELD_NAME));
			rowByColumn.put(name, row);
		}
		final List<String> scannedNames = new ArrayList<String>();
		for (final CatalogReader.Column column : pAsset.columns)
		{
			scannedNames.add(column.name);
		}
		final ColumnRefresh.Diff diff = ColumnRefresh.diff(existing, scannedNames);
		if (!diff.changed())
		{
			return false;
		}
		final String tableName = pTable.getString(FactoryPaths.LandingTable.TABLE_NAME);
		final AdaptationTable columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
		for (final String gone : diff.removed)
		{
			pContext.doDelete(rowByColumn.get(gone).getAdaptationName(), false);
			takenFieldNames.remove(rowByColumn.get(gone).getString(FactoryPaths.LandingColumn.FIELD_NAME));
		}
		final List<String> newFieldNames = ColumnRefresh.newFieldNames(takenFieldNames, diff.added);
		final Set<String> keyKept = new HashSet<String>(diff.keyBefore);
		if (diff.keyDropped)
		{
			keyKept.clear();
		}
		final List<SourceKeyRule.Candidate> candidates = new ArrayList<SourceKeyRule.Candidate>();
		for (final CatalogReader.Column column : pAsset.columns)
		{
			candidates.add(new SourceKeyRule.Candidate(column.name, column.primaryKey, column.nullRate, column.uniqueRate));
		}
		final String[] parts = pAsset.qualifiedName.split("[.]");
		SourceKeyRule.Proposal proposal = null;
		if (keyKept.isEmpty())
		{
			proposal = SourceKeyRule.propose(parts[parts.length - 1], candidates);
		}
		for (int i = 0; i < pAsset.columns.size(); i++)
		{
			final CatalogReader.Column scanned = pAsset.columns.get(i);
			final boolean isNew = diff.added.contains(scanned.name);
			final boolean inKey = keyKept.contains(scanned.name) || proposal != null && proposal.columns.contains(scanned.name);
			final Adaptation old = isNew ? null : rowByColumn.get(scanned.name);
			final ValueContextForUpdate value = isNew ? pContext.getContextForNewOccurrence(columns) : pContext.getContext(old.getAdaptationName());
			if (isNew)
			{
				value.setValue(tableId, FactoryPaths.LandingColumn.LANDING_TABLE);
				value.setValue(newFieldNames.get(diff.added.indexOf(scanned.name)), FactoryPaths.LandingColumn.FIELD_NAME);
				value.setValue(scanned.name, FactoryPaths.LandingColumn.SOURCE_COLUMN);
			}
			value.setValue(Integer.valueOf(i + 1), FactoryPaths.LandingColumn.POSITION);
			value.setValue(scanned.nativeType == null || scanned.nativeType.trim().isEmpty() ? scanned.dataType : scanned.nativeType, FactoryPaths.LandingColumn.SOURCE_DATA_TYPE);
			value.setValue(scanned.nullable, FactoryPaths.LandingColumn.SOURCE_NULLABLE);
			if (!keyKept.contains(scanned.name))
			{
				value.setValue(Boolean.valueOf(inKey), FactoryPaths.LandingColumn.SOURCE_KEY);
				value.setValue(inKey ? (scanned.primaryKey ? "SOURCE" : "RULE") : null, FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN);
			}
			if (scanned.nullRate != null)
			{
				value.setValue(BigDecimal.ONE.subtract(scanned.nullRate).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP), FactoryPaths.LandingColumn.FILL_RATE);
			}
			value.setValue(scanned.distinctCount, FactoryPaths.LandingColumn.DISTINCT_COUNT);
			value.setValue(scanned.maxLength, FactoryPaths.LandingColumn.MAX_LENGTH);
			value.setValue(LandingPlanner.inferredType(scanned.dataType), FactoryPaths.LandingColumn.INFERRED_TYPE);
			value.setValue(new ArrayList<String>(scanned.tags), FactoryPaths.LandingColumn.TAGS);
			if (isNew)
			{
				pContext.doCreateOccurrence(value, columns);
			}
			else
			{
				pContext.doModifyContent(old, value);
			}
		}
		final ValueContextForUpdate table = pContext.getContext(pTable.getAdaptationName());
		table.setValue(Integer.valueOf(pAsset.columns.size()), FactoryPaths.LandingTable.COLUMN_COUNT);
		table.setValue(pAsset.rowCount, FactoryPaths.LandingTable.SOURCE_ROW_COUNT);
		final String change = pTable.getString(FactoryPaths.LandingTable.CHANGE_COLUMN);
		if (change != null && !change.isEmpty() && !scannedNames.contains(change))
		{
			table.setValue(null, FactoryPaths.LandingTable.CHANGE_COLUMN);
			table.setValue(null, FactoryPaths.LandingTable.CHANGE_COLUMN_KIND);
		}
		String rationale = pTable.getString(FactoryPaths.LandingTable.KEY_RATIONALE);
		if (proposal != null)
		{
			rationale = proposal.stored();
			table.setValue(Integer.valueOf(proposal.confidence), FactoryPaths.LandingTable.KEY_CONFIDENCE);
			table.setValue(rationale, FactoryPaths.LandingTable.KEY_RATIONALE);
		}
		final boolean flattened = FlattenedDocument.looksFlattened(scannedNames);
		table.setValue(com.on.ebx.factory.landing.KeyTexts.fit((flattened ? FlattenedDocument.MESSAGE + " " : "") + "Source key: " + (rationale == null ? "" : rationale), com.on.ebx.factory.landing.TextLimits.MESSAGE_MAX_LENGTH),
			FactoryPaths.LandingTable.MESSAGE);
		pContext.doModifyContent(pTable, table);
		WizardData.clearLandingValidation(pContext, pFactory.getTable(FactoryPaths.PROJECT_SOURCE), pTable);
		pLine.put("columnsBefore", diff.columnsBefore);
		pLine.put("columnsNow", diff.columnsNow);
		final ArrayNode added = pLine.putArray("columnsAdded");
		for (final String name : diff.added)
		{
			added.add(name);
		}
		final ArrayNode removed = pLine.putArray("columnsRemoved");
		for (final String name : diff.removed)
		{
			removed.add(name);
		}
		pLine.put("keyDropped", diff.keyDropped);
		pLine.put("summary", ColumnRefresh.summary(tableName, diff));
		if (flattened)
		{
			pLine.put("warning", FlattenedDocument.MESSAGE);
		}
		return true;
	}

	/**
	 * A table the factory already generated no longer matches the source (a column added, removed or retyped): each change is recorded as a
	 * {@code SourceDrift} proposal ({@link SourceDrifts}, docs/DRIFT.md) and the table says so; nothing else changes until a person decides. A table
	 * already DRIFTED is compared again, so a later change is proposed too; a change a person rejected is never proposed again. Only when
	 * {@link FeatureSwitches#sourceDrift()} is on (F3-105): closed, the table is marked as in v1.3.0 and no row is recorded.
	 */
	private static boolean drift(final ProcedureContext pContext, final Adaptation pFactory, final Adaptation pTable, final CatalogReader.Asset pAsset) throws OperationException
	{
		final String status = pTable.getString(FactoryPaths.LandingTable.STATUS);
		if (!"GENERATED".equals(status) && !"LOADED".equals(status) && !"DRIFTED".equals(status))
		{
			return false;
		}
		if (!FeatureSwitches.sourceDrift())
		{
			// F3-105: source drift is closed on this instance, a plan behaves exactly as in v1.3.0 - a GENERATED or LOADED table whose columns were added
			// or removed turns DRIFTED with the v1.3.0 message; no SourceDrift row is written, a retyped column alone marks nothing, a DRIFTED table is left as is.
			if (!"GENERATED".equals(status) && !"LOADED".equals(status))
			{
				return false;
			}
			final String warning = SchemaDrift.closedMessage(SchemaDrift.diff(SourceDrifts.known(pFactory, pTable), SourceDrifts.scanned(pAsset)));
			if (warning == null)
			{
				return false;
			}
			final ValueContextForUpdate marked = pContext.getContext(pTable.getAdaptationName());
			marked.setValue("DRIFTED", FactoryPaths.LandingTable.STATUS);
			marked.setValue(warning, FactoryPaths.LandingTable.MESSAGE);
			pContext.doModifyContent(pTable, marked);
			return true;
		}
		final List<SchemaDrift.Change> changes = SchemaDrift.diff(SourceDrifts.known(pFactory, pTable), SourceDrifts.scanned(pAsset));
		if (!changes.isEmpty())
		{
			SourceDrifts.record(pContext, pFactory, pTable, changes, "DRIFTED".equals(status) ? null : status);
		}
		if (!SourceDrifts.hasOpen(pFactory, pTable.getOccurrencePrimaryKey().format()))
		{
			return false;
		}
		if (!"DRIFTED".equals(status) || !changes.isEmpty())
		{
			final ValueContextForUpdate value = pContext.getContext(pTable.getAdaptationName());
			value.setValue("DRIFTED", FactoryPaths.LandingTable.STATUS);
			value.setValue(com.on.ebx.factory.landing.TextLimits.truncate(SchemaDrift.summary(changes), com.on.ebx.factory.landing.TextLimits.MESSAGE_MAX_LENGTH),
				FactoryPaths.LandingTable.MESSAGE);
			pContext.doModifyContent(pTable, value);
		}
		return true;
	}

	/** The catalog's normalised type as a staging type; what has no equivalent stays text. */
	static String inferredType(final String pCatalogType)
	{
		final String type = pCatalogType == null ? "" : pCatalogType.trim().toUpperCase(Locale.ROOT);
		if ("INTEGER".equals(type) || "DECIMAL".equals(type) || "BOOLEAN".equals(type) || "DATE".equals(type) || "TIMESTAMP".equals(type))
		{
			return type;
		}
		return "STRING";
	}

	// --------------------------------------------------------------------------------------------------- relations

	/**
	 * Runs {@link RelationRule} over every {@code LandingTable} of the project source (read back from
	 * {@code LandingColumn} rows, whatever run planned each of them) and writes the proposals, idempotently,
	 * as {@code LandingRelation} rows; then computes the load order over what is now on file (status
	 * {@code ACCEPTED} or {@code PROPOSED}) and adds {@code relations}, {@code relationsSkipped} and
	 * {@code loadOrder} to {@code pOut}. Kept thin on purpose: the actual rule and the topological sort are
	 * pure and unit-tested in {@link RelationRule}; this method only reads the tables in, and writes the
	 * result back out.
	 */
	private static void planRelations(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final ObjectNode pOut) throws OperationException
	{
		// name used for RelationRule matching is the RAW last part of the source object (real database
		// semantics), not LandingTable.tableName (an EBX-safe, deduplicated identifier that can differ from it)
		final Map<String, String> tableIdByRawName = new LinkedHashMap<String, String>();
		final Map<String, String> rawNameByTableId = new HashMap<String, String>();
		final List<RelationRule.Table> tables = new ArrayList<RelationRule.Table>();

		final RequestResult tableRows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = tableRows.nextAdaptation(); row != null; row = tableRows.nextAdaptation())
			{
				final String tableId = row.getOccurrencePrimaryKey().format();
				final String sourceObject = row.getString(FactoryPaths.LandingTable.SOURCE_OBJECT);
				// LandingModel.baseNameOf strips a recognised file extension first (a file-connector asset's
				// sourceObject is a plain path like "sample.csv") so RelationRule matches on the file name, not
				// on its extension - same reasoning, same helper, as LandingModel.Builder.table's own naming.
				final String[] parts = LandingModel.baseNameOf(sourceObject).split("[.]");
				final String rawName = parts[parts.length - 1];
				// two source objects of the same project source ending in the same last segment is not expected
				// (they are one application's own schema); the first one found wins, deterministically, rather
				// than risk cross-wiring relations between two different tables under one name
				if (!tableIdByRawName.containsKey(rawName))
				{
					tableIdByRawName.put(rawName, tableId);
				}
				rawNameByTableId.put(tableId, rawName);
				tables.add(new RelationRule.Table(rawName, LandingPlanner.plannedKeyColumns(pFactory, tableId), LandingPlanner.plannedColumns(pFactory, tableId)));
			}
		}
		finally
		{
			tableRows.close();
		}

		final RelationRule.Result ruled = RelationRule.propose(tables);

		final AdaptationTable relationsTable = pFactory.getTable(FactoryPaths.LANDING_RELATION);
		final ArrayNode relationsNode = pOut.putArray("relations");
		final int[] written = new int[1];
		final ProcedureResult writeResult = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final RelationRule.Proposal proposal : ruled.proposals)
				{
					final String fromTableId = tableIdByRawName.get(proposal.fromTable);
					final String toTableId = tableIdByRawName.get(proposal.toTable);
					if (fromTableId == null || toTableId == null)
					{
						continue;
					}
					if (LandingPlanner.relationExists(pFactory, fromTableId, proposal.fromColumns, toTableId))
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(relationsTable);
					value.setValue(pProjectSourceId, FactoryPaths.LandingRelation.PROJECT_SOURCE);
					value.setValue(fromTableId, FactoryPaths.LandingRelation.FROM_TABLE);
					value.setValue(new ArrayList<String>(proposal.fromColumns), FactoryPaths.LandingRelation.FROM_COLUMNS);
					value.setValue(toTableId, FactoryPaths.LandingRelation.TO_TABLE);
					value.setValue(new ArrayList<String>(proposal.toColumns), FactoryPaths.LandingRelation.TO_COLUMNS);
					value.setValue(Boolean.valueOf(proposal.selfReference), FactoryPaths.LandingRelation.SELF_REFERENCE);
					value.setValue("RULE", FactoryPaths.LandingRelation.ORIGIN);
					value.setValue(Integer.valueOf(proposal.confidence), FactoryPaths.LandingRelation.CONFIDENCE);
					value.setValue(proposal.rationale, FactoryPaths.LandingRelation.RATIONALE);
					pContext.doCreateOccurrence(value, relationsTable);
					written[0]++;

					final ObjectNode line = relationsNode.addObject();
					line.put("from", proposal.fromTable);
					line.put("to", proposal.toTable);
					line.put("confidence", proposal.confidence);
				}
			}
		});
		if (writeResult.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveLandingRelations", "Writing landing relations of project source " + pProjectSourceId + " failed: " + writeResult.getExceptionFullMessage(Locale.ENGLISH), pProjectSourceId);
		}
		pOut.put("relationsProposed", written[0]);

		final ArrayNode skippedNode = pOut.putArray("relationsSkipped");
		for (final RelationRule.Skip skip : ruled.skipped)
		{
			final ObjectNode line = skippedNode.addObject();
			line.put("table", skip.table);
			line.put("column", skip.column);
			line.put("reason", skip.reason);
		}

		LandingPlanner.writeLoadOrder(pFactory, pProjectSourceId, tables, rawNameByTableId, pOut);
	}

	/** Every {@code ACCEPTED} or {@code PROPOSED} relation of the project source, turned into the parents-first load order. */
	private static void writeLoadOrder(final Adaptation pFactory, final String pProjectSourceId, final List<RelationRule.Table> pTables,
		final Map<String, String> pRawNameByTableId, final ObjectNode pOut)
	{
		final List<String> tableNames = new ArrayList<String>();
		for (final RelationRule.Table table : pTables)
		{
			tableNames.add(table.name);
		}
		final List<RelationRule.Edge> edges = new ArrayList<RelationRule.Edge>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_RELATION)
			.createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId) + " and (./status='ACCEPTED' or ./status='PROPOSED')");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String fromName = pRawNameByTableId.get(row.getString(FactoryPaths.LandingRelation.FROM_TABLE));
				final String toName = pRawNameByTableId.get(row.getString(FactoryPaths.LandingRelation.TO_TABLE));
				if (fromName == null || toName == null)
				{
					continue;
				}
				edges.add(new RelationRule.Edge(fromName, toName, Boolean.TRUE.equals(row.get(FactoryPaths.LandingRelation.SELF_REFERENCE))));
			}
		}
		finally
		{
			rows.close();
		}
		final RelationRule.LoadOrder loadOrder = RelationRule.loadOrder(tableNames, edges);
		final ObjectNode loadOrderNode = pOut.putObject("loadOrder");
		final ArrayNode orderNode = loadOrderNode.putArray("order");
		for (final String name : loadOrder.order)
		{
			orderNode.add(name);
		}
		final ArrayNode cycleNode = loadOrderNode.putArray("cycle");
		for (final String name : loadOrder.cycle)
		{
			cycleNode.add(name);
		}
	}

	/** Whether a {@code LandingRelation} already exists for this (from table, from columns, to table): left alone whatever its status. */
	private static boolean relationExists(final Adaptation pFactory, final String pFromTableId, final List<String> pFromColumns, final String pToTableId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_RELATION)
			.createRequestResult("./fromTable=" + CatalogReader.quote(pFromTableId) + " and ./toTable=" + CatalogReader.quote(pToTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final List<?> existing = row.getList(FactoryPaths.LandingRelation.FROM_COLUMNS);
				if (existing != null && LandingPlanner.sameColumns(existing, pFromColumns))
				{
					return true;
				}
			}
		}
		finally
		{
			rows.close();
		}
		return false;
	}

	private static boolean sameColumns(final List<?> pStored, final List<String> pColumns)
	{
		if (pStored.size() != pColumns.size())
		{
			return false;
		}
		for (int i = 0; i < pStored.size(); i++)
		{
			if (!String.valueOf(pStored.get(i)).equals(pColumns.get(i)))
			{
				return false;
			}
		}
		return true;
	}

	/** The source key columns of an already-planned {@code LandingTable}, in position order, read from its {@code LandingColumn} rows. */
	private static List<String> plannedKeyColumns(final Adaptation pFactory, final String pLandingTableId)
	{
		final List<String> keyColumns = new ArrayList<String>();
		for (final Adaptation column : LandingPlanner.orderedColumns(pFactory, pLandingTableId))
		{
			if (Boolean.TRUE.equals(column.get(FactoryPaths.LandingColumn.SOURCE_KEY)))
			{
				keyColumns.add(column.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN));
			}
		}
		return keyColumns;
	}

	/** The columns of an already-planned {@code LandingTable}, in position order, with the inferred type {@link #inferredType} already wrote. */
	private static List<RelationRule.Column> plannedColumns(final Adaptation pFactory, final String pLandingTableId)
	{
		final List<RelationRule.Column> columns = new ArrayList<RelationRule.Column>();
		for (final Adaptation column : LandingPlanner.orderedColumns(pFactory, pLandingTableId))
		{
			columns.add(new RelationRule.Column(column.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN), column.getString(FactoryPaths.LandingColumn.INFERRED_TYPE)));
		}
		return columns;
	}

	private static List<Adaptation> orderedColumns(final Adaptation pFactory, final String pLandingTableId)
	{
		final List<Adaptation> columns = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				columns.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(columns, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				final Integer left = (Integer) pLeft.get(FactoryPaths.LandingColumn.POSITION);
				final Integer right = (Integer) pRight.get(FactoryPaths.LandingColumn.POSITION);
				final int l = left == null ? Integer.MAX_VALUE : left.intValue();
				final int r = right == null ? Integer.MAX_VALUE : right.intValue();
				return l != r ? Integer.compare(l, r) : 0;
			}
		});
		return columns;
	}
}
