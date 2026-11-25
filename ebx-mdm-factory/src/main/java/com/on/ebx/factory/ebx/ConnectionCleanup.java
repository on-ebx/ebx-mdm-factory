package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.project.ConnectionProvenance;
import com.on.ebx.factory.project.ProjectDeletionPlan.ConnectionFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.ConnectionReasons;
import com.on.ebx.factory.project.ProjectDeletionPlan.MappingRef;
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
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.Session;

/**
 * Lot C (2026-10-17, "deleting a project must also remove the sample-file connections and catalogue sources it created"): the EBX-facing side of the
 * {@code connections} option of {@link ProjectDeleter}. {@code project.ProjectDeletionPlan} (pure) decides what is eligible from the
 * {@link ConnectionFacts} this class gathers; this class also executes the two deletions. The proof that the factory created an object is the project's
 * own {@code PipelineStep} rows ({@link ConnectionProvenance}): a connection or a catalogue source with no such proof is never touched, a connection
 * another project's application uses is never touched, and anything still referenced is kept and said so.
 * <p>
 * <b>Which path deletes what</b> (read in the sibling modules' sources on {@code main}, 2026-10-17):
 * <ul>
 * <li>Connectors has NO tool to delete a connection (the MCP tools and the sibling contract 1.2.0 offer {@code flows.delete} / {@code connectors_mapping_delete}
 * for mappings only), so the Connectors flows still go through {@code connectors_mapping_delete} ({@link GeneratedObjectDeleter#deleteMapping}, whose
 * trigger cascades the rules, inputs and outbound states), and the connection row, with the rows that reference it, is deleted by a {@code Procedure} on
 * the {@code Connectors} dataset - the dataset {@code SampleSourceImporter} already writes the row into;</li>
 * <li>the data catalog has no deletion tool either: the catalogue source, its assets and columns and the rows that point at them are deleted by
 * {@code Procedure}s on its dataset, children before parents (EBX leaves a dangling foreign key when a referenced row is deleted, it never cascades).
 * Its own flows ({@code Catalog scan: ID}, ...) are Connectors mappings, deleted first.</li>
 * </ul>
 * Everything a deletion does not remove but that still references the object (a data product on the assets, a publication on the connection, a
 * mapping of somebody else) is a BLOCKER: re-read at execution time, it turns the item into {@code KEPT_IN_USE} (never a failure: the project's own rows
 * are then still deleted, and the answer says what stays).
 */
final class ConnectionCleanup
{
	/** Audit F-02 (catalog reads): why this class reads the catalogue without the person's session. */
	static final String READ_REASON = "project deletion: finds the catalogue source the factory created for the project, its assets and columns and what still uses them; nothing is"
		+ " shown to anybody, the deletion itself runs on the job's session and EBX checks it";

	// ebx-connectors' own home/dataset/tables - same read-as-data rule as SampleSourceImporter (this module never depends on ebx-connectors' jar)
	private static final HomeKey CONNECTORS_HOME_KEY = HomeKey.forBranchName("Connectors");
	private static final String CONNECTORS_DATASET = "Connectors";
	static final Path CONNECTOR = Path.parse("/root/Connector");
	private static final Path MAPPING = Path.parse("/root/Mapping");
	static final Path C_TYPE = Path.parse("./type");
	static final Path C_LABEL = Path.parse("./label");
	private static final Path M_LABEL = Path.parse("./label");
	static final Path S_CONNECTOR = Path.parse("./connector");
	/** Connectors rows deleted with the connection, by their {@code connector} field. */
	private static final String[][] CONNECTION_OWNED = { { "/root/ConnectorProperty", "connector" }, { "/root/SchemaRefreshState", "connector" },
		{ "/root/FieldProvenance", "connector" }, { "/root/ConnectorOperationalMetrics", "connector" } };
	/** Connectors rows that reference the connection and are NOT deleted with it: {table, field, what}. */
	private static final String[][] CONNECTION_BLOCKERS = { { "/root/Publication", "connector", "publication(s)" }, { "/root/Conflict", "proposedConnector", "conflict(s) proposed from it" } };
	// staging integration (2026-10-17, live HTTP 500 on GET deletion/plan): /root/AccessRight has no defaultLlmConnector field on Connectors 1.2 (profile,
	// right, comment only) - the predicate threw. Removed; every field read below is now checked to exist first (fieldOrNull).

	// the data catalog's tables (module ebx-data-catalog), by path
	static final Path SOURCE = Path.parse("/root/Source");
	private static final Path ASSET = Path.parse("/root/Asset");
	private static final Path COLUMN = Path.parse("/root/Column");
	/** Catalogue rows deleted with the source's assets: {table, field}; by asset key. */
	private static final String[][] BY_ASSET = { { "/root/LineageEdge", "fromAsset" }, { "/root/LineageEdge", "toAsset" }, { "/root/ProfileSnapshot", "asset" },
		{ "/root/ColumnSample", "asset" }, { "/root/Alert", "asset" }, { "/root/Job", "asset" } };
	/** Catalogue rows deleted with the source's columns: {table, field}; by column key. */
	private static final String[][] BY_COLUMN = { { "/root/LineageEdge", "fromColumn" }, { "/root/LineageEdge", "toColumn" }, { "/root/TagSuggestion", "column" },
		{ "/root/TermSuggestion", "column" } };
	/** Catalogue objects that use the source or its assets and are NOT deleted with them: {table, field, scope (S = source id, A = asset key, C = column key), what}. */
	private static final String[][] CATALOG_BLOCKERS = { { "/root/ProcessWorkflow", "source", "S", "workflow launcher(s) scoped to it" },
		{ "/root/OutputPort", "targetSource", "S", "data product output port(s) targeting it" }, { "/root/DataProduct", "assets", "A", "data product(s) including its assets" },
		{ "/root/AccessRequest", "asset", "A", "access request(s) on its assets" }, { "/root/ProductField", "sourceColumn", "C", "data product field(s) fed by its columns" } };
	/** Values per OR predicate. */
	private static final int CHUNK = 40;

	private ConnectionCleanup()
	{
	}

	// ======================================================================================================== gathering

	/**
	 * The connections and catalogue sources the project has proof the factory wrote, with everything the plan needs to decide. Empty (and nothing else is
	 * read) for a project that imported no sample file and onboarded no catalogue source through the sources job.
	 */
	static List<ConnectionFacts> gather(final Adaptation pFactory, final String pProjectId)
	{
		final List<ConnectionProvenance.StepRow> steps = new ArrayList<ConnectionProvenance.StepRow>();
		final RequestResult stepRows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and (./step="
			+ CatalogReader.quote(ConnectionProvenance.STEP_CONNECTION) + " or ./step=" + CatalogReader.quote(ConnectionProvenance.STEP_SOURCES_RUN) + ")");
		try
		{
			for (Adaptation row = stepRows.nextAdaptation(); row != null; row = stepRows.nextAdaptation())
			{
				steps.add(new ConnectionProvenance.StepRow(row.getString(FactoryPaths.PipelineStep.STEP), row.getString(FactoryPaths.PipelineStep.SCOPE),
					row.getString(FactoryPaths.PipelineStep.DETAIL)));
			}
		}
		finally
		{
			stepRows.close();
		}
		final List<ConnectionFacts> out = new ArrayList<ConnectionFacts>();
		final Map<String, ConnectionProvenance.Evidence> evidence = ConnectionProvenance.read(steps);
		if (evidence.isEmpty())
		{
			return out;
		}
		final Repository repository = pFactory.getHome().getRepository();
		final Adaptation connectors = ConnectionCleanup.connectorsDataset(repository);
		final Adaptation catalog = CatalogReader.dataset(repository);
		final CatalogReader.Reader reader = ConnectionCleanup.reader();
		for (final ConnectionProvenance.Evidence one : evidence.values())
		{
			if (!one.isCandidate())
			{
				continue;
			}
			final ConnectionFacts facts = new ConnectionFacts(one.connectionId);
			facts.connectionStepSeen = one.connectionSeen;
			facts.connectionCreatedByFactory = one.connectionCreated;
			facts.catalogSourceCreatedByFactory = one.catalogSourceCreated;
			facts.catalogSourceId = one.catalogSourceCreated ? one.catalogSourceId : "";
			ConnectionCleanup.readConnection(connectors, facts);
			ConnectionCleanup.readCatalog(reader, catalog, facts);
			ConnectionCleanup.readOtherProjects(pFactory, pProjectId, facts);
			out.add(facts);
		}
		return out;
	}

	static void readConnection(final Adaptation pConnectors, final ConnectionFacts pFacts)
	{
		if (pConnectors == null)
		{
			return;
		}
		final AdaptationTable connectorTable = pConnectors.getTable(ConnectionCleanup.CONNECTOR);
		final Adaptation row = connectorTable.lookupAdaptationByPrimaryKey(connectorTable.computePrimaryKey(new Object[] { pFacts.connectionId }));
		pFacts.connectionExists = row != null;
		pFacts.connectionType = row == null || row.getString(ConnectionCleanup.C_TYPE) == null ? "" : row.getString(ConnectionCleanup.C_TYPE);
		for (final MappingRef mapping : ConnectionCleanup.mappingsOf(pConnectors, pFacts.connectionId))
		{
			if (ConnectionProvenance.isCatalogFlowLabel(mapping.label, pFacts.catalogSourceId))
			{
				pFacts.catalogFlows.add(mapping);
			}
			else
			{
				pFacts.otherMappings.add(mapping);
			}
		}
		for (final String[] blocker : ConnectionCleanup.CONNECTION_BLOCKERS)
		{
			final int n = ConnectionCleanup.count(pConnectors, blocker[0], blocker[1], pFacts.connectionId);
			if (n > 0)
			{
				pFacts.connectionBlockers.add(n + " " + blocker[2]);
			}
		}
	}

	/** Every mapping whose {@code connector} is {@code pConnectionId}: id and label. */
	private static List<MappingRef> mappingsOf(final Adaptation pConnectors, final String pConnectionId)
	{
		final List<MappingRef> out = new ArrayList<MappingRef>();
		final RequestResult rows = pConnectors.getTable(ConnectionCleanup.MAPPING).createRequestResult("./connector=" + CatalogReader.quote(pConnectionId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new MappingRef(row.getOccurrencePrimaryKey().format(), row.getString(ConnectionCleanup.M_LABEL)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static int count(final Adaptation pDataset, final String pTablePath, final String pField, final String pValue)
	{
		final AdaptationTable table = ConnectionCleanup.tableOrNull(pDataset, pTablePath);
		if (table == null)
		{
			return 0;
		}
		if (!ConnectionCleanup.hasField(table, pField))
		{
			return 0; // a field this version of the module does not have
		}
		final RequestResult rows = table.createRequestResult("./" + pField + "=" + CatalogReader.quote(pValue));
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	/** What the catalogue holds for the source the factory created (or, for a connection with none, which sources name the connection). */
	static void readCatalog(final CatalogReader.Reader pReader, final Adaptation pCatalog, final ConnectionFacts pFacts)
	{
		if (pCatalog == null)
		{
			return;
		}
		// the sources naming the connection: the one the factory created, and any other
		for (final String id : ConnectionCleanup.sourceIdsNaming(pReader, pCatalog, pFacts.connectionId))
		{
			if (!id.equals(pFacts.catalogSourceId))
			{
				pFacts.otherSourceIds.add(id);
			}
		}
		if (pFacts.catalogSourceId.isEmpty())
		{
			return;
		}
		final CatalogDetail detail = ConnectionCleanup.readCatalogDetail(pReader, pCatalog, pFacts.catalogSourceId);
		pFacts.catalogSourceExists = detail.sourceExists;
		pFacts.catalogSourceConnector = detail.sourceConnector;
		pFacts.assets = detail.assetKeys.size();
		pFacts.columns = detail.columnKeys.size();
		pFacts.catalogBlockers.addAll(detail.blockers);
	}

	/** The ids of the catalogue sources whose {@code connector} is {@code pConnectionId} (orphan-connections lot, 2026-10-17: shared by the project deletion, the connection deletion and the orphan listing). */
	static List<String> sourceIdsNaming(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pConnectionId)
	{
		final List<String> out = new ArrayList<String>();
		if (pCatalog == null)
		{
			return out;
		}
		final CatalogReader.Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(ConnectionCleanup.SOURCE), "./connector=" + CatalogReader.quote(pConnectionId));
		try
		{
			for (Adaptation row = rows.next(); row != null; row = rows.next())
			{
				out.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static void readOtherProjects(final Adaptation pFactory, final String pProjectId, final ConnectionFacts pFacts)
	{
		final Set<String> codes = new LinkedHashSet<String>();
		final List<String> predicates = new ArrayList<String>();
		predicates.add("./connector=" + CatalogReader.quote(pFacts.connectionId));
		if (!pFacts.catalogSourceId.isEmpty())
		{
			predicates.add("./catalogSource=" + CatalogReader.quote(pFacts.catalogSourceId));
		}
		for (final String predicate : predicates)
		{
			final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult(predicate);
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					final String projectKey = row.getString(FactoryPaths.ProjectSource.PROJECT);
					if (projectKey == null || projectKey.equals(pProjectId))
					{
						continue;
					}
					codes.add(ConnectionCleanup.projectCode(pFactory, projectKey));
				}
			}
			finally
			{
				rows.close();
			}
		}
		// another project that imported a file of the same name (its import FOUND this connection) may not have its application yet: its own import step says so
		final RequestResult imports = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./step=" + CatalogReader.quote(ConnectionProvenance.STEP_CONNECTION)
			+ " and ./scope=" + CatalogReader.quote(pFacts.connectionId));
		try
		{
			for (Adaptation row = imports.nextAdaptation(); row != null; row = imports.nextAdaptation())
			{
				final String projectKey = row.getString(FactoryPaths.PipelineStep.PROJECT);
				if (projectKey == null || projectKey.equals(pProjectId))
				{
					continue;
				}
				codes.add(ConnectionCleanup.projectCode(pFactory, projectKey));
			}
		}
		finally
		{
			imports.close();
		}
		pFacts.otherProjects.addAll(codes);
	}

	private static String projectCode(final Adaptation pFactory, final String pProjectKey)
	{
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectKey));
		final String code = project == null ? null : project.getString(FactoryPaths.Project.CODE);
		return code == null || code.isEmpty() ? pProjectKey : code;
	}

	// ----------------------------------------------------------------------------------------------- catalogue detail

	private static final class CatalogDetail
	{
		boolean sourceExists;
		String sourceConnector = "";
		final List<String> assetKeys = new ArrayList<String>();
		final List<String> columnKeys = new ArrayList<String>();
		final List<String> blockers = new ArrayList<String>();
	}

	private static CatalogDetail readCatalogDetail(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pSourceId)
	{
		final CatalogDetail detail = new CatalogDetail();
		final CatalogReader.Rows source = CatalogReader.rows(pReader, pCatalog.getTable(ConnectionCleanup.SOURCE), "./id=" + CatalogReader.quote(pSourceId));
		try
		{
			final Adaptation row = source.next();
			if (row != null)
			{
				detail.sourceExists = true;
				final String connector = row.getString(ConnectionCleanup.S_CONNECTOR);
				detail.sourceConnector = connector == null ? "" : connector;
			}
		}
		finally
		{
			source.close();
		}
		if (!detail.sourceExists)
		{
			return detail;
		}
		final CatalogReader.Rows assets = CatalogReader.rows(pReader, pCatalog.getTable(ConnectionCleanup.ASSET), "./source=" + CatalogReader.quote(pSourceId));
		try
		{
			for (Adaptation row = assets.next(); row != null; row = assets.next())
			{
				detail.assetKeys.add(CatalogReader.assetKeyOf(row));
			}
		}
		finally
		{
			assets.close();
		}
		for (final String predicate : ConnectionProvenance.orChunks("./asset", detail.assetKeys, ConnectionCleanup.CHUNK))
		{
			final CatalogReader.Rows columns = CatalogReader.rows(pReader, pCatalog.getTable(ConnectionCleanup.COLUMN), predicate);
			try
			{
				for (Adaptation row = columns.next(); row != null; row = columns.next())
				{
					detail.columnKeys.add(CatalogReader.columnKeyOf(row));
				}
			}
			finally
			{
				columns.close();
			}
		}
		for (final String[] blocker : ConnectionCleanup.CATALOG_BLOCKERS)
		{
			final AdaptationTable table = ConnectionCleanup.tableOrNull(pCatalog, blocker[0]);
			if (table == null)
			{
				continue;
			}
			final List<String> values = "S".equals(blocker[2]) ? java.util.Collections.singletonList(pSourceId) : "A".equals(blocker[2]) ? detail.assetKeys : detail.columnKeys;
			int n = 0;
			for (final String predicate : ConnectionProvenance.orChunks("./" + blocker[1], values, ConnectionCleanup.CHUNK))
			{
				final CatalogReader.Rows rows = CatalogReader.rows(pReader, table, predicate);
				try
				{
					while (rows.next() != null)
					{
						n++;
					}
				}
				finally
				{
					rows.close();
				}
			}
			if (n > 0)
			{
				detail.blockers.add(n + " " + blocker[3]);
			}
		}
		return detail;
	}

	// ======================================================================================================= executing

	/**
	 * Deletes the catalogue source the factory created: its own Connectors flows first (so no scan can run meanwhile), then the rows that point at its
	 * assets and columns, the columns, the assets and the source. Re-reads everything live: a source that has since been re-pointed at another connection,
	 * or that a data product now uses, is KEPT (not a failure). Already gone (no source row, no flow) is {@code SKIPPED_ABSENT}.
	 */
	static GeneratedObjectDeleter.Outcome deleteCatalogSource(final Session pSession, final Adaptation pFactory, final ConnectionFacts pFacts)
		throws OperationException, McpException
	{
		final Repository repository = pFactory.getHome().getRepository();
		final Adaptation connectors = ConnectionCleanup.connectorsDataset(repository);
		final Adaptation catalog = CatalogReader.dataset(repository);
		final String sourceId = pFacts.catalogSourceId;
		final CatalogReader.Reader reader = ConnectionCleanup.reader();
		final CatalogDetail detail = catalog == null ? new CatalogDetail() : ConnectionCleanup.readCatalogDetail(reader, catalog, sourceId);
		if (detail.sourceExists && !pFacts.connectionId.equals(detail.sourceConnector))
		{
			return GeneratedObjectDeleter.Outcome.keptInUse(
				ConnectionReasons.sourceNamesAnotherConnection(sourceId, detail.sourceConnector, pFacts.connectionId));
		}
		if (!detail.blockers.isEmpty())
		{
			return GeneratedObjectDeleter.Outcome.keptInUse(ConnectionReasons.stillUsed(detail.blockers));
		}
		// 1. the catalogue's own flows of the source: read live from Connectors, deleted through the supported tool (its trigger cascades rules, inputs, states)
		int flows = 0;
		if (connectors != null)
		{
			for (final MappingRef mapping : ConnectionCleanup.mappingsOf(connectors, pFacts.connectionId))
			{
				if (!ConnectionProvenance.isCatalogFlowLabel(mapping.label, sourceId))
				{
					continue;
				}
				final GeneratedObjectDeleter.Outcome outcome = GeneratedObjectDeleter.deleteMapping(pSession, pFactory, mapping.id);
				if ("FAILED".equals(outcome.result))
				{
					return GeneratedObjectDeleter.Outcome.failed("catalogue flow " + mapping.id + " (" + mapping.label + "): " + outcome.message);
				}
				if ("DELETED".equals(outcome.result))
				{
					flows++;
				}
			}
		}
		if (catalog == null || !detail.sourceExists)
		{
			return flows == 0 ? GeneratedObjectDeleter.Outcome.absent() : GeneratedObjectDeleter.Outcome.deleted(flows + " catalogue flow(s) deleted; the catalogue source itself was already gone");
		}
		// 2. the rows that point at the assets and columns, then columns, assets, the source
		final AdaptationHome home = catalog.getHome();
		final String context = "Project deletion: deleting the catalogue source " + sourceId + " failed";
		int others = 0;
		for (final String[] one : ConnectionCleanup.BY_COLUMN)
		{
			others += ConnectionCleanup.deleteWhere(pSession, home, catalog, one[0], one[1], detail.columnKeys, context);
		}
		for (final String[] one : ConnectionCleanup.BY_ASSET)
		{
			others += ConnectionCleanup.deleteWhere(pSession, home, catalog, one[0], one[1], detail.assetKeys, context);
		}
		final int columns = ConnectionCleanup.deleteWhere(pSession, home, catalog, ConnectionCleanup.COLUMN.format(), "asset", detail.assetKeys, context);
		final int assets = VolumeBatchOps.forEachBatched(pSession, home, catalog.getTable(ConnectionCleanup.ASSET), "./source=" + CatalogReader.quote(sourceId), context,
			ConnectionCleanup.DELETE);
		VolumeBatchOps.forEachBatched(pSession, home, catalog.getTable(ConnectionCleanup.SOURCE), "./id=" + CatalogReader.quote(sourceId), context, ConnectionCleanup.DELETE);
		LoggingCategory.getKernel().info("[mdm-factory] project deletion: catalogue source " + sourceId + " deleted (" + assets + " asset(s), " + columns + " column(s), " + others
			+ " other catalogue row(s), " + flows + " catalogue flow(s))");
		return GeneratedObjectDeleter.Outcome.deleted(assets + " asset(s), " + columns + " column(s), " + others + " other catalogue row(s) and " + flows + " catalogue flow(s) deleted");
	}

	/**
	 * Deletes the connection the factory created, with the Connectors rows that belong to it alone (properties, schema state, provenance, metrics). Re-reads
	 * live: a connection that is not a sample-file one, that a mapping or a catalogue source still uses, or that a publication, a conflict or an access right
	 * references, is KEPT (not a failure). Already gone is {@code SKIPPED_ABSENT}.
	 */
	static GeneratedObjectDeleter.Outcome deleteConnection(final Session pSession, final Adaptation pFactory, final ConnectionFacts pFacts) throws OperationException
	{
		final Repository repository = pFactory.getHome().getRepository();
		final Adaptation connectors = ConnectionCleanup.connectorsDataset(repository);
		if (connectors == null)
		{
			return GeneratedObjectDeleter.Outcome.absent();
		}
		final AdaptationTable connectorTable = connectors.getTable(ConnectionCleanup.CONNECTOR);
		final Adaptation row = connectorTable.lookupAdaptationByPrimaryKey(connectorTable.computePrimaryKey(new Object[] { pFacts.connectionId }));
		if (row == null)
		{
			return GeneratedObjectDeleter.Outcome.absent();
		}
		final String type = row.getString(ConnectionCleanup.C_TYPE);
		if (!"sample-file".equals(type))
		{
			return GeneratedObjectDeleter.Outcome.keptInUse(ConnectionReasons.connectionNotSampleFile(type == null ? "" : type));
		}
		final List<MappingRef> mappings = ConnectionCleanup.mappingsOf(connectors, pFacts.connectionId);
		if (!mappings.isEmpty())
		{
			return GeneratedObjectDeleter.Outcome.keptInUse(ConnectionReasons.mappingStillUses(mappings.get(0)) + (mappings.size() > 1 ? " (" + (mappings.size() - 1) + " more)" : ""));
		}
		final Adaptation catalog = CatalogReader.dataset(repository);
		if (catalog != null)
		{
			final List<String> sources = ConnectionCleanup.sourceIdsNaming(ConnectionCleanup.reader(), catalog, pFacts.connectionId);
			if (!sources.isEmpty())
			{
				return GeneratedObjectDeleter.Outcome.keptInUse("catalogue source(s) " + String.join(", ", sources) + " still name this connection: kept.");
			}
		}
		final List<String> blockers = new ArrayList<String>();
		for (final String[] blocker : ConnectionCleanup.CONNECTION_BLOCKERS)
		{
			final int n = ConnectionCleanup.count(connectors, blocker[0], blocker[1], pFacts.connectionId);
			if (n > 0)
			{
				blockers.add(n + " " + blocker[2]);
			}
		}
		if (!blockers.isEmpty())
		{
			return GeneratedObjectDeleter.Outcome.keptInUse(ConnectionReasons.stillUsed(blockers));
		}
		final AdaptationHome home = connectors.getHome();
		final String context = "Project deletion: deleting the connection " + pFacts.connectionId + " failed";
		final List<String> value = java.util.Collections.singletonList(pFacts.connectionId);
		int owned = 0;
		for (final String[] one : ConnectionCleanup.CONNECTION_OWNED)
		{
			owned += ConnectionCleanup.deleteWhere(pSession, home, connectors, one[0], one[1], value, context);
		}
		VolumeBatchOps.forEachBatched(pSession, home, connectorTable, "./id=" + CatalogReader.quote(pFacts.connectionId), context, ConnectionCleanup.DELETE);
		LoggingCategory.getKernel().info("[mdm-factory] project deletion: connection " + pFacts.connectionId + " deleted (" + owned + " dependent Connectors row(s))");
		return GeneratedObjectDeleter.Outcome.deleted(owned + " dependent Connectors row(s) deleted with it");
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private static final VolumeBatchOps.RowOp DELETE = new VolumeBatchOps.RowOp()
	{
		@Override
		public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
		{
			pContext.doDelete(pRow.getAdaptationName(), false);
		}
	};

	/** Deletes the rows of {@code pTablePath} whose {@code pField} is one of {@code pValues}, chunk by chunk; a table the dataset does not have is skipped. @return rows deleted */
	private static int deleteWhere(final Session pSession, final AdaptationHome pHome, final Adaptation pDataset, final String pTablePath, final String pField,
		final List<String> pValues, final String pContext) throws OperationException
	{
		final AdaptationTable table = ConnectionCleanup.tableOrNull(pDataset, pTablePath);
		if (table == null)
		{
			return 0;
		}
		if (!ConnectionCleanup.hasField(table, pField))
		{
			return 0;
		}
		int deleted = 0;
		for (final String predicate : ConnectionProvenance.orChunks("./" + pField, pValues, ConnectionCleanup.CHUNK))
		{
			deleted += VolumeBatchOps.forEachBatched(pSession, pHome, table, predicate, pContext, ConnectionCleanup.DELETE);
		}
		return deleted;
	}

	/** Whether the table's records have this field (a module version without it must never make a predicate throw). */
	private static boolean hasField(final AdaptationTable pTable, final String pField)
	{
		try
		{
			return pTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + pField)) != null;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	private static AdaptationTable tableOrNull(final Adaptation pDataset, final String pTablePath)
	{
		try
		{
			return pDataset.getTable(Path.parse(pTablePath));
		}
		catch (final RuntimeException ex)
		{
			return null; // a table this version of the module does not have
		}
	}

	static Adaptation connectorsDataset(final Repository pRepository)
	{
		final AdaptationHome home = pRepository.lookupHome(ConnectionCleanup.CONNECTORS_HOME_KEY);
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(ConnectionCleanup.CONNECTORS_DATASET));
	}

	static CatalogReader.Reader reader()
	{
		return CatalogReader.Reader.system(ConnectionCleanup.READ_REASON);
	}
}
