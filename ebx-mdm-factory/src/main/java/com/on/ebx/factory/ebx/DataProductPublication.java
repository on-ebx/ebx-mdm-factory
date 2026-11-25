package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.distribution.DistributionBatches;
import com.on.ebx.factory.core.CatalogRightMessages;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.product.ProductPlan;
import com.on.ebx.factory.master.MasterModel;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * SPEC-distribution.md &sect;7c / lot 2: publishes a project's golden records as a Data Catalog data
 * product with an output port on Snowflake. Three phases, all EBX-bound (the pure decision logic is
 * {@code distribution.product.ProductPlan}):
 * <ul>
 * <li>{@link #detect} - which warehouse connections are reachable, and the default plan (every master
 * table + its available crosswalks, ticked, as its own table).</li>
 * <li>{@link #plan} - re-runs {@code ProductPlan} on the browser's draft, server-side, no write (the
 * "dry run" the REST route's {@code ?dryRun=true} answers).</li>
 * <li>{@link #create} - writes the catalogue's {@code DataProduct}/{@code OutputPort} rows (as data, in
 * one {@code Procedure} running as the caller's own session - CLAUDE.md's "reads the ... dataset as
 * data" pattern, {@code ebx.CatalogReader}'s own Javadoc: this module has no compile dependency on
 * ebx-data-catalog, so its field paths are mirrored here, literally, from that module's own
 * {@code CatalogPaths} - read there 2026-09-24, not imported), one Connectors OUTBOUND root mapping per
 * publishable table ({@code connectors_mapping_upsert}, in-JVM bridge - a cross-references table is a
 * SECOND root mapping on its host master table, see {@code ProductPlan}'s own Javadoc), and the first
 * publication ({@code connectors_publish}). Idempotent throughout: re-running {@link #create} on the
 * same project/code updates what already exists rather than duplicating it.
 * </ul>
 * <p>
 * <b>Refresh is the data catalog's own SLA sweep, not a schedule this lot creates</b> (coordinator
 * review, 2026-09-24 - corrects a first version that wrote its own Scheduler REST schedule and workflow
 * launcher): {@code ebx-data-catalog}'s lot 26 already ships {@code PortSlaTask}/{@code PortSla}, a
 * scheduled sweep that republishes any {@code OutputPort} with {@code autoPublish=true} once its age
 * nears {@code maxLagMinutes}. {@link #writeCatalogue} only sets those two fields, from
 * {@code ProductPlan.maxLagMinutes(draft.refreshMode)} - see that method's own Javadoc for exactly what
 * "the real cadence is the sweep's own schedule, not verified live" means here.
 * <p>
 * <b>Never run live this session</b> (task constraint: read-only MCP tools only, no write, no
 * publication) - every write path below (the catalogue Procedure, the mapping upserts, the publish call)
 * was written against the sibling repositories' own source and the platform's documented contracts,
 * never exercised against a running instance. See {@code docs/DISTRIBUTION-PRODUCT.md} for the exact
 * verification steps this needs before it is trusted.
 */
public final class DataProductPublication
{
	private DataProductPublication()
	{
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// ============================================================================================ catalogue schema
	// Mirrors ebx-data-catalog's own CatalogPaths (read 2026-09-24, not imported - see class Javadoc). Keep in
	// step with that module if it ever renames one of these fields; nothing here compiles against it.

	private static final Path CAT_SOURCE = Path.parse("/root/Source");
	private static final Path CAT_ASSET = Path.parse("/root/Asset");
	private static final Path CAT_DATA_PRODUCT = Path.parse("/root/DataProduct");
	private static final Path CAT_OUTPUT_PORT = Path.parse("/root/OutputPort");

	private static final Path SOURCE_PLATFORM = Path.parse("./platform");

	private static final Path ASSET_SOURCE = Path.parse("./source");
	private static final Path ASSET_KIND = Path.parse("./kind");
	private static final Path ASSET_STATUS = Path.parse("./status");
	private static final Path ASSET_SCHEMA_NAME = Path.parse("./schemaName");
	private static final Path ASSET_PHYSICAL_NAME = Path.parse("./physicalName");
	private static final Path ASSET_QUALIFIED_NAME = Path.parse("./qualifiedName");
	private static final Path ASSET_BUSINESS_NAME = Path.parse("./businessName");
	private static final Path ASSET_ROW_COUNT = Path.parse("./rowCount");

	private static final Path COLUMN_ASSET = Path.parse("./asset");
	private static final Path COLUMN_NAME = Path.parse("./name");
	private static final Path COLUMN_DATA_TYPE = Path.parse("./dataType");
	private static final Path COLUMN_STATUS = Path.parse("./status");

	private static final Path PRODUCT_CODE = Path.parse("./code");
	private static final Path PRODUCT_NAME = Path.parse("./name");
	private static final Path PRODUCT_DESCRIPTION = Path.parse("./description");
	private static final Path PRODUCT_DOMAIN = Path.parse("./domain");
	private static final Path PRODUCT_OWNER = Path.parse("./owner");
	private static final Path PRODUCT_ASSETS = Path.parse("./assets");
	private static final Path PRODUCT_REFRESH_FREQUENCY = Path.parse("./refreshFrequency");
	private static final Path PRODUCT_TAGS = Path.parse("./tags");
	private static final Path PRODUCT_STATUS = Path.parse("./status");

	private static final Path PORT_CODE = Path.parse("./code");
	private static final Path PORT_NAME = Path.parse("./name");
	private static final Path PORT_DATA_PRODUCT = Path.parse("./dataProduct");
	private static final Path PORT_KIND = Path.parse("./kind");
	private static final Path PORT_CONTRACT_VERSION = Path.parse("./contractVersion");
	private static final Path PORT_TARGET_SOURCE = Path.parse("./targetSource");
	private static final Path PORT_TARGET_SCHEMA = Path.parse("./targetSchema");
	private static final Path PORT_CONNECTORS_MAPPINGS = Path.parse("./connectorsMappings");
	private static final Path PORT_AUTHORITATIVE = Path.parse("./authoritative");
	private static final Path PORT_ACCESS_MODE = Path.parse("./accessMode");
	private static final Path PORT_STATUS = Path.parse("./status");
	/** Lot 26 of ebx-data-catalog (read there, not imported): the SLA sweep task republishes an {@code autoPublish} port before it breaches {@code maxLagMinutes} - see the class Javadoc. */
	private static final Path PORT_MAX_LAG_MINUTES = Path.parse("./maxLagMinutes");
	private static final Path PORT_AUTO_PUBLISH = Path.parse("./autoPublish");

	// ==================================================================================================== detect

	/**
	 * Reachable warehouse connections, the project's candidate tables (master entities, their available
	 * crosswalks, and a short list of other catalogue EBX tables to offer under "Add assets"), and a
	 * default {@link ProductPlan.Draft} (every master table ticked as its own table, the first reachable
	 * Snowflake connection, no schedule).
	 */
	public static ObjectNode detect(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return DataProductPublication.detect(pSession, pFactory, pProjectCode, DataProductPublication.detectWarehouses(pSession, pFactory));
	}

	/**
	 * Same as {@link #detect(Session, Adaptation, String)} with the warehouse facts given by the caller (audit F-2-101, lot B3): no Connectors call and no probe of the customer's
	 * warehouse happens here - the candidates are EBX reads only. The wizard's display passes what {@link DistributionStepFacts} holds; the REST route keeps the live probe.
	 */
	public static ObjectNode detect(final Session pSession, final Adaptation pFactory, final String pProjectCode, final ObjectNode pWarehouses) throws OperationException
	{
		final Adaptation project = DataProductPublication.project(pFactory, pProjectCode);
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		final ObjectNode out = DataProductPublication.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.set("warehouses", pWarehouses);
		if (catalog == null)
		{
			out.put("catalogInstalled", false);
			out.putArray("candidates");
			out.putArray("addableCatalogAssets");
			return out;
		}
		out.put("catalogInstalled", true);
		final Candidates candidates = DataProductPublication.resolveCandidates(pSession, pFactory, catalog, project, pProjectCode);
		final ArrayNode candidatesJson = out.putArray("candidates");
		for (final ProductPlan.Candidate candidate : candidates.list)
		{
			candidatesJson.add(DataProductPublication.candidateJson(candidate));
		}
		final ArrayNode notesJson = out.putArray("notes");
		for (final String note : candidates.notes)
		{
			notesJson.add(note);
		}
		out.set("defaultDraft", DataProductPublication.defaultDraftJson(pProjectCode, candidates.list, out.path("warehouses")));
		out.put("catalogRestricted", candidates.restricted);
		return out;
	}

	/**
	 * The probe of one Snowflake connection ({@code connectors_call ... query "select 1"}): {@code {reachable, elapsedMs, detail}}. It wakes a suspended warehouse and costs
	 * credits, so it only ever runs on an explicit action (audit F-2-101) - never from a display.
	 */
	public static ObjectNode probeWarehouse(final Session pSession, final Adaptation pFactory, final String pConnectorId)
	{
		final ObjectNode out = DataProductPublication.MAPPER.createObjectNode();
		try
		{
			final McpClient client = McpServers.connectors(pSession, pFactory);
			final ObjectNode probeArgs = DataProductPublication.MAPPER.createObjectNode();
			probeArgs.put("connector", pConnectorId);
			probeArgs.put("operation", "query");
			probeArgs.putObject("inputs").put("sql", "select 1");
			final long started = System.nanoTime();
			final McpClient.ToolResult probeResult = client.callTool("connectors_call", probeArgs);
			final long elapsedMs = (System.nanoTime() - started) / 1000000L;
			final boolean failed = probeResult.error || probeResult.json != null && probeResult.json.hasNonNull("error");
			out.put("reachable", !failed);
			out.put("elapsedMs", elapsedMs);
			if (failed)
			{
				final String problem = probeResult.json != null && probeResult.json.hasNonNull("error") ? probeResult.json.path("error").asText() : probeResult.text;
				out.put("detail", "connectivity probe (query: select 1) failed: " + DataProductPublication.shorten(problem));
			}
			else
			{
				out.put("detail", "connectivity probe (query: select 1) answered in " + elapsedMs + " ms.");
			}
		}
		catch (final McpException ex)
		{
			out.put("reachable", false);
			out.put("detail", "connectors_call failed: " + DataProductPublication.shorten(ex.getMessage()));
		}
		catch (final OperationException ex)
		{
			out.put("reachable", false);
			out.put("detail", ex.getMessage());
		}
		return out;
	}

	private static ObjectNode detectWarehouses(final Session pSession, final Adaptation pFactory)
	{
		final ObjectNode out = DataProductPublication.MAPPER.createObjectNode();
		final ObjectNode snowflake = out.putObject("snowflake");
		try
		{
			final McpClient client = McpServers.connectors(pSession, pFactory);
			final ObjectNode listArgs = DataProductPublication.MAPPER.createObjectNode();
			listArgs.put("type", "snowflake");
			final McpClient.ToolResult listResult = client.callTool("connectors_list", listArgs);
			if (listResult.error || listResult.json == null)
			{
				snowflake.put("present", false);
				snowflake.put("detail", "connectors_list refused: " + DataProductPublication.shorten(listResult.text));
				return out;
			}
			final JsonNode connectors = listResult.json.isArray() ? listResult.json : listResult.json.path("connectors");
			String connectorId = null;
			for (final JsonNode connector : connectors)
			{
				if (Boolean.FALSE.equals(connector.path("active").isBoolean() ? Boolean.valueOf(connector.path("active").asBoolean()) : null))
				{
					continue;
				}
				connectorId = connector.path("id").asText(connector.path("connector").asText(null));
				if (connectorId != null && !connectorId.trim().isEmpty())
				{
					break;
				}
			}
			if (connectorId == null)
			{
				snowflake.put("present", false);
				snowflake.put("detail", "No active Snowflake connection is configured.");
				return out;
			}
			snowflake.put("present", true);
			snowflake.put("connectorId", connectorId);
			final ObjectNode probeArgs = DataProductPublication.MAPPER.createObjectNode();
			probeArgs.put("connector", connectorId);
			probeArgs.put("operation", "query");
			final ObjectNode probeInputs = probeArgs.putObject("inputs");
			probeInputs.put("sql", "select 1");
			final long started = System.nanoTime();
			final McpClient.ToolResult probeResult = client.callTool("connectors_call", probeArgs);
			final long elapsedMs = (System.nanoTime() - started) / 1000000L;
			final boolean failed = probeResult.error || probeResult.json != null && probeResult.json.hasNonNull("error");
			snowflake.put("reachable", !failed);
			snowflake.put("elapsedMs", elapsedMs);
			if (failed)
			{
				final String problem = probeResult.json != null && probeResult.json.hasNonNull("error") ? probeResult.json.path("error").asText()
					: probeResult.text;
				snowflake.put("detail", "connectivity probe (query: select 1) failed: " + DataProductPublication.shorten(problem));
			}
			else
			{
				snowflake.put("detail", "connectivity probe (query: select 1) answered in " + elapsedMs + " ms.");
			}
		}
		catch (final McpException ex)
		{
			snowflake.put("present", false);
			snowflake.put("detail", "connectors_list/connectors_call failed: " + DataProductPublication.shorten(ex.getMessage()));
		}
		catch (final OperationException ex)
		{
			snowflake.put("present", false);
			snowflake.put("detail", ex.getMessage());
		}
		final ObjectNode databricks = out.putObject("databricks");
		databricks.put("present", false);
		databricks.put("detail", "Not supported yet: ebx-connectors has no TablePublisher implementation for Databricks "
			+ "(checked 2026-09-24 - only SnowflakeConnector implements that interface).");
		return out;
	}

	// ============================================================================================== plan / create

	/** Server-side re-run of {@code ProductPlan.plan} on the browser's draft - no write, whatever {@code pDraftJson} says. */
	public static ObjectNode plan(final Session pSession, final Adaptation pFactory, final String pProjectCode, final JsonNode pDraftJson) throws OperationException
	{
		final Adaptation project = DataProductPublication.project(pFactory, pProjectCode);
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			throw EngineRefusal.coded("missing.catalogNotPackaged", "The data catalog is not installed or not the packaged model: a data product cannot be planned without it.");
		}
		final Candidates candidates = DataProductPublication.resolveCandidates(pSession, pFactory, catalog, project, pProjectCode);
		final ProductPlan.Draft draft = DataProductPublication.draftFromJson(pDraftJson);
		final ProductPlan.Result result = ProductPlan.plan(draft, candidates.list);
		final ObjectNode out = DataProductPublication.resultJson(result);
		final ArrayNode notesJson = out.putArray("candidateNotes");
		for (final String note : candidates.notes)
		{
			notesJson.add(note);
		}
		return out;
	}

	/**
	 * Writes the data product, idempotently. Re-plans first (never trusts the browser); answers the plan
	 * (with {@code write.success:false}) instead of writing anything when the plan carries a problem.
	 */
	public static ObjectNode create(final Session pSession, final Adaptation pFactory, final String pProjectCode, final JsonNode pDraftJson) throws OperationException
	{
		final Adaptation project = DataProductPublication.project(pFactory, pProjectCode);
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			throw EngineRefusal.coded("missing.catalogNotPackaged", "The data catalog is not installed or not the packaged model: a data product cannot be created without it.");
		}
		final Candidates candidates = DataProductPublication.resolveCandidates(pSession, pFactory, catalog, project, pProjectCode);
		final Map<String, ProductPlan.Candidate> byKey = new LinkedHashMap<String, ProductPlan.Candidate>();
		for (final ProductPlan.Candidate candidate : candidates.list)
		{
			byKey.put(candidate.key, candidate);
		}
		final ProductPlan.Draft draft = DataProductPublication.draftFromJson(pDraftJson);
		final ProductPlan.Result result = ProductPlan.plan(draft, candidates.list);
		final ObjectNode out = DataProductPublication.resultJson(result);
		if (!result.ok())
		{
			final ObjectNode write = out.putObject("write");
			write.put("success", false);
			write.put("message", "The plan has " + result.problems.size() + " problem(s): nothing was written.");
			return out;
		}

		// resolve each planned, non-nested EBX table to a catalogue Asset id - the write side never creates an
		// Asset row itself (that is the catalogue's own self-scan's job, CLAUDE.md's "PortCoverage" discipline)
		final Map<String, String> assetIdByPublishedName = new LinkedHashMap<String, String>();
		final List<String> missingAssets = new ArrayList<String>();
		// audit F-02 (catalog reads): the product names the assets the person sees; one their catalog right hides is said as such
		final CatalogReader.Reader assetReader = CatalogReader.Reader.of(pSession);
		for (final ProductPlan.PlannedTable table : result.tables)
		{
			if (table.nested || !table.ebxTable)
			{
				continue;
			}
			final ProductPlan.Candidate candidate = byKey.get(table.key);
			if (ProductPlan.SOURCE_CATALOG.equals(table.source))
			{
				assetIdByPublishedName.put(table.publishedName, table.key); // the key IS the asset id for a catalogue addition
				continue;
			}
			final String assetId = candidate == null ? null : DataProductPublication.findAssetId(assetReader, catalog, candidate.dataspace, candidate.dataset, candidate.tablePath);
			if (assetId == null)
			{
				missingAssets.add(table.publishedName + " (" + (candidate == null ? table.key : candidate.tablePath) + ")");
			}
			else
			{
				assetIdByPublishedName.put(table.publishedName, assetId);
			}
		}
		if (!missingAssets.isEmpty())
		{
			final ObjectNode write = out.putObject("write");
			write.put("success", false);
			write.put("message", assetReader.restricted() ? CatalogRightMessages.hidden("the catalog assets of " + DataProductPublication.join(missingAssets)) + " Nothing was written."
				: "Not yet catalogued as an EBX asset (wait for, or run, the data catalog's self-scan first): " + DataProductPublication.join(missingAssets));
			return out;
		}

		final ObjectNode write = out.putObject("write");
		try
		{
			DataProductPublication.writeCatalogue(pSession, catalog, draft, result, assetIdByPublishedName, write);
			if (!ProductPlan.TARGET_EBX_ONLY.equals(draft.target))
			{
				final List<String> mappingIds = DataProductPublication.writeMappings(pSession, pFactory, byKey, draft, result, write);
				DataProductPublication.updatePortMappings(pSession, catalog, draft.code, draft.target, mappingIds);
				DataProductPublication.publishFirstRun(pSession, pFactory, draft, mappingIds, DataProductPublication.publishedRows(result), write);
			}
			write.put("success", true);
		}
		catch (final OperationException ex)
		{
			write.put("success", false);
			write.put("message", ex.getMessage());
		}
		return out;
	}

	// =============================================================================================== candidates

	static final class Candidates
	{
		final List<ProductPlan.Candidate> list;
		final List<String> notes;
		/** Audit F-02 (catalog reads): the person's catalog right hid some of the catalog's assets. */
		boolean restricted;

		Candidates(final List<ProductPlan.Candidate> pList, final List<String> pNotes)
		{
			this.list = pList;
			this.notes = pNotes;
		}
	}

	private static Candidates resolveCandidates(final Session pSession, final Adaptation pFactory, final Adaptation pCatalog, final Adaptation pProject,
		final String pProjectCode) throws OperationException
	{
		final List<ProductPlan.Candidate> out = new ArrayList<ProductPlan.Candidate>();
		final List<String> notes = new ArrayList<String>();
		final String masterDataspace = pProject.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDataset = pProject.getString(FactoryPaths.Project.MASTER_DATASET);
		if (masterDataspace == null || masterDataspace.trim().isEmpty() || masterDataset == null || masterDataset.trim().isEmpty())
		{
			notes.add("The master zone has not been built yet: no master table can be offered.");
			return new Candidates(out, notes);
		}
		final ProjectMasterEntities.Result entities = ProjectMasterEntities.readEntities(pFactory, pProjectCode);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(entities.entities);
		final Set<String> built = new LinkedHashSet<String>(masterModel.builtEntities);
		final Map<String, List<MasterModel.FieldInfo>> fieldsByEntity = new LinkedHashMap<String, List<MasterModel.FieldInfo>>();
		for (final MasterModel.FieldInfo field : masterModel.fields)
		{
			List<MasterModel.FieldInfo> list = fieldsByEntity.get(field.entityName);
			if (list == null)
			{
				list = new ArrayList<MasterModel.FieldInfo>();
				fieldsByEntity.put(field.entityName, list);
			}
			list.add(field);
		}
		final Map<String, String> parentOf = new LinkedHashMap<String, String>();
		final Map<String, String> parentLinkFieldOf = new LinkedHashMap<String, String>();
		for (final MasterModel.ForeignKeyInfo fk : masterModel.foreignKeys)
		{
			if (fk.entityName.equals(fk.targetEntityName) || !built.contains(fk.entityName) || !built.contains(fk.targetEntityName) || parentOf.containsKey(fk.entityName))
			{
				continue; // self-reference, a target outside the master zone, or this child already has a parent picked
			}
			parentOf.put(fk.entityName, fk.targetEntityName);
			parentLinkFieldOf.put(fk.entityName, fk.fieldName);
		}

		final List<GoldenRecordsReader.EntitySpec> specs = new ArrayList<GoldenRecordsReader.EntitySpec>();
		for (final String name : masterModel.builtEntities)
		{
			final List<String> businessFields = new ArrayList<String>();
			for (final MasterModel.FieldInfo field : DataProductPublication.orEmpty(fieldsByEntity.get(name)))
			{
				businessFields.add(field.masterField);
			}
			specs.add(new GoldenRecordsReader.EntitySpec(name, MasterModel.tablePath(name), businessFields));
		}
		final GoldenRecordsReader.Result rowCounts = GoldenRecordsReader.read(pSession, pFactory.getHome().getRepository(), masterDataspace, masterDataset, specs);
		if (!rowCounts.matchingAvailable)
		{
			notes.add("Match and Merge is not active on this project's master tables yet: row counts below are every master record, not golden-only.");
		}

		for (final String name : masterModel.builtEntities)
		{
			final String tablePath = MasterModel.tablePath(name);
			final List<ProductPlan.Column> columns = new ArrayList<ProductPlan.Column>();
			columns.add(new ProductPlan.Column("GOLDEN_ID", "STRING", name + ".id"));
			for (final MasterModel.FieldInfo field : DataProductPublication.orEmpty(fieldsByEntity.get(name)))
			{
				columns.add(new ProductPlan.Column(ProductPlan.sanitize(field.masterField), DataProductPublication.orString(field.dataType), name + "." + field.masterField));
			}
			final Long rows = rowCounts.rowsByEntity.get(name);
			final boolean isChild = parentOf.containsKey(name);
			out.add(new ProductPlan.Candidate(ProductPlan.SOURCE_MASTER, name, name, "Project master", columns, rows == null ? -1L : rows.longValue(), isChild,
				parentOf.get(name), parentLinkFieldOf.get(name), true, masterDataspace, masterDataset, tablePath));
			if (!isChild)
			{
				out.add(DataProductPublication.crosswalkCandidate(name, tablePath, masterDataspace, masterDataset, rows == null ? -1L : rows.longValue()));
			}
		}

		// audit F-02 (catalog reads): "Add assets from the catalogue" lists the catalog's assets to the person: read as them
		final CatalogReader.Reader reader = CatalogReader.Reader.of(pSession);
		DataProductPublication.addCatalogueAssets(reader, pCatalog, out, notes);
		if (reader.restricted())
		{
			notes.add(CatalogRightMessages.hidden("the data catalog's assets (they are not offered under 'Add assets from the catalogue')"));
		}
		final Candidates candidates = new Candidates(out, notes);
		candidates.restricted = reader.restricted();
		return candidates;
	}

	/**
	 * A SECOND root mapping candidate on the SAME master table as {@code pEntityName} (coordinator review,
	 * 2026-09-24 - see {@code ProductPlan}'s own class Javadoc for why: the shared {@code Xref} table has
	 * no per-project filter in Connectors' outbound-mapping mechanism, but the master table itself already
	 * carries one row per source record with its own {@code sourceSystem}/{@code sourceId} and Match and
	 * Merge state, so a crosswalk needs no table of its own). Always offered for a root (non-child) master
	 * entity - unlike the first version of this method, it never depends on a separate row existing
	 * anywhere: {@link ProductPlan.Candidate#parentKey} names the host master entity so the write side
	 * ({@link #writeMappings}) knows which table to build the second mapping against.
	 */
	private static ProductPlan.Candidate crosswalkCandidate(final String pEntityName, final String pTablePath, final String pDataspace, final String pDataset,
		final long pRowCount)
	{
		final List<ProductPlan.Column> columns = new ArrayList<ProductPlan.Column>();
		columns.add(new ProductPlan.Column("GOLDEN_ID", "STRING", pEntityName + ".id"));
		columns.add(new ProductPlan.Column("SOURCE_SYSTEM", "STRING", pEntityName + ".sourceSystem"));
		columns.add(new ProductPlan.Column("SOURCE_ID", "STRING", pEntityName + ".sourceId"));
		columns.add(new ProductPlan.Column("MDM_STATE", "STRING", pEntityName + ".id"));
		return new ProductPlan.Candidate(ProductPlan.SOURCE_CROSSWALK, pEntityName + "__xref", "Cross-references", "Project matching", columns, pRowCount, false,
			pEntityName, null, true, pDataspace, pDataset, pTablePath);
	}

	/** Up to 200 other catalogue tables/views (not already offered as a master/crosswalk candidate) for "Add assets from the catalogue". */
	private static void addCatalogueAssets(final CatalogReader.Reader pReader, final Adaptation pCatalog, final List<ProductPlan.Candidate> pOut, final List<String> pNotes)
	{
		final Set<String> alreadyOffered = new LinkedHashSet<String>();
		for (final ProductPlan.Candidate candidate : pOut)
		{
			if (candidate.tablePath != null)
			{
				alreadyOffered.add(candidate.dataspace + "/" + candidate.dataset + candidate.tablePath);
			}
		}
		final AdaptationTable assets = pCatalog.getTable(DataProductPublication.CAT_ASSET);
		final AdaptationTable sources = pCatalog.getTable(DataProductPublication.CAT_SOURCE);
		final CatalogReader.Rows rows = CatalogReader.rows(pReader, assets, null);
		int offered = 0;
		try
		{
			for (Adaptation asset = rows.next(); asset != null && offered < 200; asset = rows.next())
			{
				final String status = asset.getString(DataProductPublication.ASSET_STATUS);
				final String kind = asset.getString(DataProductPublication.ASSET_KIND);
				if ("DELETED".equals(status) || kind != null && !"TABLE".equals(kind) && !"VIEW".equals(kind))
				{
					continue;
				}
				final String schemaName = asset.getString(DataProductPublication.ASSET_SCHEMA_NAME);
				final String physicalName = asset.getString(DataProductPublication.ASSET_PHYSICAL_NAME);
				final int slash = schemaName == null ? -1 : schemaName.indexOf('/');
				final String sourceId = asset.getString(DataProductPublication.ASSET_SOURCE);
				final Adaptation source = sourceId == null ? null : CatalogReader.visible(pReader, DataProductPublication.byId(sources, sourceId));
				final boolean ebxTable = source != null && "EBX".equals(source.getString(DataProductPublication.SOURCE_PLATFORM)) && slash > 0;
				final String dataspace = ebxTable ? schemaName.substring(0, slash) : null;
				final String dataset = ebxTable ? schemaName.substring(slash + 1) : null;
				final String tablePath = ebxTable && physicalName != null ? physicalName.startsWith("/") ? physicalName : "/" + physicalName : null;
				if (ebxTable && alreadyOffered.contains(dataspace + "/" + dataset + tablePath))
				{
					continue;
				}
				final String qualifiedName = asset.getString(DataProductPublication.ASSET_QUALIFIED_NAME);
				final String businessName = asset.getString(DataProductPublication.ASSET_BUSINESS_NAME);
				final String label = businessName != null && !businessName.trim().isEmpty() ? businessName.trim()
					: qualifiedName != null && !qualifiedName.trim().isEmpty() ? qualifiedName.trim() : asset.getOccurrencePrimaryKey().format();
				final Object rowCountValue = DataProductPublication.value(asset, DataProductPublication.ASSET_ROW_COUNT);
				final long rowCount = rowCountValue instanceof BigDecimal ? ((BigDecimal) rowCountValue).longValue() : -1L;
				final List<ProductPlan.Column> columns = DataProductPublication.assetColumns(pReader, pCatalog, asset.getOccurrencePrimaryKey().format());
				pOut.add(new ProductPlan.Candidate(ProductPlan.SOURCE_CATALOG, asset.getOccurrencePrimaryKey().format(), label,
					ebxTable ? "Catalogue - EBX" : "Catalogue - " + (source == null ? "?" : source.getString(DataProductPublication.SOURCE_PLATFORM)), columns,
					rowCount, false, null, null, ebxTable, dataspace, dataset, tablePath));
				offered++;
			}
		}
		finally
		{
			rows.close();
		}
		if (offered >= 200)
		{
			pNotes.add("More than 200 catalogue assets exist: only the first 200 are offered under 'Add assets from the catalogue'.");
		}
	}

	private static List<ProductPlan.Column> assetColumns(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pAssetId)
	{
		final List<ProductPlan.Column> out = new ArrayList<ProductPlan.Column>();
		final CatalogReader.Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(Path.parse("/root/Column")), "./asset=" + DataProductPublication.quote(pAssetId));
		try
		{
			for (Adaptation column = rows.next(); column != null; column = rows.next())
			{
				if ("DELETED".equals(column.getString(DataProductPublication.COLUMN_STATUS)))
				{
					continue;
				}
				final String name = column.getString(DataProductPublication.COLUMN_NAME);
				if (name == null || name.trim().isEmpty())
				{
					continue;
				}
				out.add(new ProductPlan.Column(ProductPlan.sanitize(name), DataProductPublication.mapCatalogDataType(column.getString(DataProductPublication.COLUMN_DATA_TYPE)),
					name));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** A rough, best-effort mapping from a catalogue's free-text native type to one of {@code ProductPublisher}'s buckets (STRING/INTEGER/DECIMAL/BOOLEAN/DATE/TIMESTAMP) - only used for the contract preview, never for the real Connectors rule (which reads the field's own EBX type). */
	static String mapCatalogDataType(final String pNativeType)
	{
		final String t = pNativeType == null ? "" : pNativeType.trim().toUpperCase(Locale.ROOT);
		if (t.contains("TIMESTAMP") || t.contains("DATETIME"))
		{
			return "TIMESTAMP";
		}
		if (t.contains("DATE"))
		{
			return "DATE";
		}
		if (t.contains("BOOL"))
		{
			return "BOOLEAN";
		}
		if (t.contains("INT"))
		{
			return "INTEGER";
		}
		if (t.contains("DEC") || t.contains("NUM") || t.contains("FLOAT") || t.contains("DOUBLE") || t.contains("REAL"))
		{
			return "DECIMAL";
		}
		return "STRING";
	}

	// ===================================================================================================== write

	private static void writeCatalogue(final Session pSession, final Adaptation pCatalog, final ProductPlan.Draft pDraft, final ProductPlan.Result pResult,
		final Map<String, String> pAssetIdByPublishedName, final ObjectNode pWrite) throws OperationException
	{
		final List<String> assetIds = new ArrayList<String>(new LinkedHashSet<String>(pAssetIdByPublishedName.values()));
		final boolean[] created = new boolean[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pCatalog.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final AdaptationTable products = pCatalog.getTable(DataProductPublication.CAT_DATA_PRODUCT);
				final Adaptation existing = DataProductPublication.byId(products, pDraft.code);
				final ValueContextForUpdate row = existing == null ? pContext.getContextForNewOccurrence(products) : pContext.getContext(existing.getAdaptationName());
				row.setValue(pDraft.code, DataProductPublication.PRODUCT_CODE);
				row.setValue(pDraft.productName, DataProductPublication.PRODUCT_NAME);
				row.setValue(DataProductPublication.orNull(pDraft.description), DataProductPublication.PRODUCT_DESCRIPTION);
				row.setValue(DataProductPublication.orNull(pDraft.domain), DataProductPublication.PRODUCT_DOMAIN);
				row.setValue(DataProductPublication.orNull(pDraft.owner), DataProductPublication.PRODUCT_OWNER);
				row.setValue(assetIds, DataProductPublication.PRODUCT_ASSETS);
				// pDraft.refreshMode is already one of ProductWizard.REFRESH's own enum values (ON_DEMAND/HOURLY/DAILY/WEEKLY) - no translation needed
				row.setValue(pDraft.refreshMode, DataProductPublication.PRODUCT_REFRESH_FREQUENCY);
				final List<String> tags = new ArrayList<String>();
				if (pDraft.marketplaceListing)
				{
					tags.add("MARKETPLACE_LISTING_REQUESTED"); // no dedicated catalogue field for this yet - see docs/DISTRIBUTION-PRODUCT.md
				}
				row.setValue(tags, DataProductPublication.PRODUCT_TAGS);
				if (existing == null)
				{
					row.setValue("DRAFT", DataProductPublication.PRODUCT_STATUS);
					pContext.doCreateOccurrence(row, products);
					created[0] = true;
				}
				else
				{
					pContext.doModifyContent(existing, row);
					created[0] = false;
				}

				if (!ProductPlan.TARGET_EBX_ONLY.equals(pDraft.target) && pCatalog.getSchemaNode().getNode(DataProductPublication.CAT_OUTPUT_PORT) != null)
				{
					final AdaptationTable ports = pCatalog.getTable(DataProductPublication.CAT_OUTPUT_PORT);
					final String portCode = pDraft.code + "." + pDraft.target;
					// OutputPort's primary key is the auto-increment 'id', NOT 'code' (ebx-data-catalog's tools/build_xsd.py,
					// T('OutputPort', ..., ['id'], ...)) - looked up by an XPath predicate on ./code, never PrimaryKey.parseString(portCode)
					final Adaptation existingPort = DataProductPublication.findPortByCode(ports, portCode);
					final ValueContextForUpdate port = existingPort == null ? pContext.getContextForNewOccurrence(ports)
						: pContext.getContext(existingPort.getAdaptationName());
					port.setValue(portCode, DataProductPublication.PORT_CODE);
					port.setValue(pDraft.productName + " (" + pDraft.target + ")", DataProductPublication.PORT_NAME);
					port.setValue(pDraft.code, DataProductPublication.PORT_DATA_PRODUCT);
					port.setValue("TABLE", DataProductPublication.PORT_KIND);
					port.setValue("1", DataProductPublication.PORT_CONTRACT_VERSION);
					port.setValue(pDraft.connectorId, DataProductPublication.PORT_TARGET_SOURCE);
					port.setValue(pDraft.schema, DataProductPublication.PORT_TARGET_SCHEMA);
					port.setValue(Boolean.TRUE, DataProductPublication.PORT_AUTHORITATIVE);
					port.setValue("OPEN", DataProductPublication.PORT_ACCESS_MODE);
					port.setValue("ACTIVE", DataProductPublication.PORT_STATUS);
					// refresh (coordinator review, 2026-09-24): maxLagMinutes/autoPublish only - the catalogue's own
					// PortSlaTask sweep does the republishing, see the class Javadoc
					final Integer maxLag = ProductPlan.maxLagMinutes(pDraft.refreshMode);
					port.setValue(maxLag, DataProductPublication.PORT_MAX_LAG_MINUTES);
					port.setValue(Boolean.valueOf(maxLag != null), DataProductPublication.PORT_AUTO_PUBLISH);
					if (existingPort == null)
					{
						pContext.doCreateOccurrence(port, ports);
					}
					else
					{
						pContext.doModifyContent(existingPort, port);
					}
				}
			}
		});
		if (result.hasFailed())
		{
			// audit F-02 (catalog reads): written under the person's session - a refusal of the catalog's rights names the right (OWN)
			throw EngineRefusal.coded("conflict.catalogWriteRefused", CatalogRightMessages.refusedOrRaw(CatalogRightMessages.CatalogWrite.PUBLISH_PRODUCT,
				"Writing the data product/output port failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH)));
		}
		pWrite.put("productCreated", created[0]);
		pWrite.put("productCode", pDraft.code);
		pWrite.put("assets", assetIds.size());
	}

	/** Updates the platform port's {@code connectorsMappings} CSV once the mapping ids are known - a second, small write (the ids do not exist yet when {@link #writeCatalogue} runs the port's own create/update). */
	private static void updatePortMappings(final Session pSession, final Adaptation pCatalog, final String pCode, final String pTarget, final List<String> pMappingIds)
		throws OperationException
	{
		if (pMappingIds.isEmpty() || pCatalog.getSchemaNode().getNode(DataProductPublication.CAT_OUTPUT_PORT) == null)
		{
			return;
		}
		final String portCode = pCode + "." + pTarget;
		final StringBuilder csv = new StringBuilder();
		for (final String id : pMappingIds)
		{
			csv.append(csv.length() > 0 ? "," : "").append(id);
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pCatalog.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final AdaptationTable ports = pCatalog.getTable(DataProductPublication.CAT_OUTPUT_PORT);
				final Adaptation port = DataProductPublication.findPortByCode(ports, portCode);
				if (port == null)
				{
					return;
				}
				final ValueContextForUpdate update = pContext.getContext(port.getAdaptationName());
				update.setValue(csv.toString(), DataProductPublication.PORT_CONNECTORS_MAPPINGS);
				pContext.doModifyContent(port, update);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("conflict.catalogWriteRefused", CatalogRightMessages.refusedOrRaw(CatalogRightMessages.CatalogWrite.PUBLISH_PRODUCT,
				"Writing the port's mapping list failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH)));
		}
	}

	/**
	 * One {@code connectors_mapping_upsert} per publishable table (never nested, never a non-EBX catalogue
	 * asset). A {@link ProductPlan#SOURCE_CROSSWALK} table is a SECOND root mapping on its host master's
	 * SAME {@code dataspace}/{@code dataset}/{@code tablePath} (never a table of its own - see
	 * {@code ProductPlan}'s class Javadoc), with input {@code crosswalk=true} and
	 * {@code golden(...)}/{@code mdmState(...)} rule transformations. A nested child becomes a sub-object
	 * of its parent's SAME call. Returns the root mapping ids, in table order.
	 */
	private static List<String> writeMappings(final Session pSession, final Adaptation pFactory, final Map<String, ProductPlan.Candidate> pByKey,
		final ProductPlan.Draft pDraft, final ProductPlan.Result pResult, final ObjectNode pWrite) throws OperationException
	{
		final McpClient client = McpServers.connectors(pSession, pFactory);
		final boolean contract = ConnectorsContractClient.useContract();
		final Map<String, ArrayNode> childrenByParent = new LinkedHashMap<String, ArrayNode>();
		final List<ProductPlan.PlannedTable> roots = new ArrayList<ProductPlan.PlannedTable>();
		for (final ProductPlan.PlannedTable table : pResult.tables)
		{
			if (table.nested)
			{
				continue; // handled below, folded into its parent's own document
			}
			if (!table.ebxTable)
			{
				continue;
			}
			roots.add(table);
		}
		for (final ProductPlan.PlannedTable table : pResult.tables)
		{
			if (!table.nested)
			{
				continue;
			}
			final ProductPlan.Candidate candidate = pByKey.get(table.key);
			final ObjectNode child = DataProductPublication.MAPPER.createObjectNode();
			child.put("tablePath", candidate.tablePath);
			child.put("label", table.label);
			child.put("collectionPath", table.publishedName == null ? ProductPlan.sanitize(table.label).toLowerCase(Locale.ROOT) : table.publishedName.toLowerCase(Locale.ROOT));
			child.put("parentLinkField", "./" + candidate.parentLinkField);
			final ArrayNode rules = child.putArray("rules");
			for (final ProductPlan.Column column : table.columns)
			{
				DataProductPublication.rule(rules, column, candidate, false);
			}
			ArrayNode siblings = childrenByParent.get(table.parentPublishedName);
			if (siblings == null)
			{
				siblings = DataProductPublication.MAPPER.createArrayNode();
				childrenByParent.put(table.parentPublishedName, siblings);
			}
			siblings.add(child);
		}

		final List<String> mappingIds = new ArrayList<String>();
		final ArrayNode mappingsReport = pWrite.putArray("mappings");
		for (final ProductPlan.PlannedTable table : roots)
		{
			final ProductPlan.Candidate candidate = pByKey.get(table.key);
			final boolean crosswalk = ProductPlan.SOURCE_CROSSWALK.equals(table.source);
			final ObjectNode mapping = DataProductPublication.MAPPER.createObjectNode();
			mapping.put("connector", pDraft.connectorId);
			mapping.put("operation", "insert");
			mapping.put("direction", "OUTBOUND");
			mapping.put("dataspace", candidate.dataspace);
			mapping.put("dataset", candidate.dataset);
			mapping.put("tablePath", candidate.tablePath);
			mapping.put("label", "Data product " + pDraft.code + " - " + table.publishedName);
			mapping.put("active", true);
			final ObjectNode inputs = mapping.putObject("inputs");
			inputs.put("table", table.publishedName);
			if (crosswalk)
			{
				// implies mergedRecords=include (every source record, not golden only) - ProductPublisher's own default
				inputs.put("crosswalk", "true");
			}
			final ArrayNode rules = mapping.putArray("rules");
			for (final ProductPlan.Column column : table.columns)
			{
				DataProductPublication.rule(rules, column, candidate, crosswalk);
			}
			final ArrayNode children = childrenByParent.get(table.publishedName);
			if (children != null)
			{
				mapping.set("children", children);
			}
			final ObjectNode tableReport = mappingsReport.addObject();
			tableReport.put("table", table.publishedName);
			// wave 11 (audit F-26): written as a flow through the Connectors contract when it is there (ebx.factory.connectors.via); the label is the key, so the
			// mapping an earlier publication wrote under the same label is updated in place
			if (contract)
			{
				final String viaContract = DataProductPublication.putThroughContract(pSession, mapping, table.publishedName);
				if (viaContract != null)
				{
					tableReport.put("mappingId", viaContract);
					tableReport.put("via", "contract");
					mappingIds.add(viaContract);
					continue;
				}
			}
			final ObjectNode args = DataProductPublication.MAPPER.createObjectNode();
			args.set("mapping", mapping);
			args.put("dryRun", true);
			final McpClient.ToolResult dry = DataProductPublication.call(client, "connectors_mapping_upsert", args);
			if (dry.error)
			{
				throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert (dry run) refused " + table.publishedName + ": " + DataProductPublication.errorText(dry), table.publishedName);
			}
			args.put("dryRun", false);
			final McpClient.ToolResult real = DataProductPublication.call(client, "connectors_mapping_upsert", args);
			if (real.error)
			{
				throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert could not write " + table.publishedName + ": " + DataProductPublication.errorText(real), table.publishedName);
			}
			final String mappingId = real.json == null ? null : real.json.path("mapping").asText(null);
			if (mappingId == null || mappingId.trim().isEmpty())
			{
				throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert did not answer a mapping id for " + table.publishedName + ": "
					+ DataProductPublication.shorten(real.text), table.publishedName);
			}
			tableReport.put("mappingId", mappingId);
			mappingIds.add(mappingId);
		}
		return mappingIds;
	}

	/**
	 * Wave 11: one root mapping written by the contract's {@code flows.put} (dry run, then real); its mapping id, or null when the contract went away or was switched
	 * off since the check and {@code via=auto} lets the caller go by the tool. A refusal of the flow itself is the same coded {@code external.writeMapping} sentence as the tool's.
	 */
	static String putThroughContract(final Session pSession, final ObjectNode pMapping, final String pPublishedName) throws OperationException
	{
		try
		{
			ContractFlows.put(pSession, pMapping, true);
			final JsonNode real = ContractFlows.put(pSession, pMapping, false);
			final String mappingId = real.hasNonNull("mappingId") ? real.path("mappingId").asText() : null;
			if (mappingId == null || mappingId.trim().isEmpty())
			{
				throw EngineRefusal.coded("external.writeMapping", "flows.put did not answer a mapping id for " + pPublishedName + ": " + DataProductPublication.shorten(real.toString()), pPublishedName);
			}
			return mappingId;
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			if (ConnectorsContractClient.mayFallBack(ex))
			{
				return null;
			}
			if (ConnectorsContractClient.OPERATION_FAILED.equals(ex.type))
			{
				throw EngineRefusal.coded("external.writeMapping", "flows.put refused " + pPublishedName + ": " + ex.getMessage(), pPublishedName);
			}
			throw ConnectorsContractClient.engineRefusal(ex, "flows.put", pMapping.path("label").asText(pPublishedName));
		}
	}

	/**
	 * One {@code rules[]} entry. For a {@link ProductPlan#SOURCE_CROSSWALK} table (coordinator review,
	 * 2026-09-24): {@code GOLDEN_ID}/{@code MDM_STATE} read the record's OWN key ({@code ./id}) through the
	 * {@code golden(...)}/{@code mdmState(...)} transformations (ebx-connectors' {@code core/GoldenRecords},
	 * {@code MameGoldenRecords} - read there, not imported: {@code golden(/root/Table[,dataspace,dataset])}
	 * resolves the formatted key it is given to that record's golden key, following a MERGED chain;
	 * {@code mdmState(...)} reports its Match and Merge state the same way); {@code SOURCE_SYSTEM}/
	 * {@code SOURCE_ID} are plain pass-through fields of the SAME master row ({@code MasterFieldNameRule.RESERVED}
	 * - every master table carries them, unrenamed). For any other table, {@code GOLDEN_ID} reads {@code ./id}
	 * and every other column reads the master/catalogue field its own {@link ProductPlan.Column#sourceField}
	 * names (the part after the first {@code .}, or the whole string when there is none - a catalogue asset's
	 * column name carries no dot).
	 */
	private static void rule(final ArrayNode pRules, final ProductPlan.Column pColumn, final ProductPlan.Candidate pCandidate, final boolean pCrosswalk)
	{
		final ObjectNode rule = pRules.addObject();
		if (pCrosswalk && "GOLDEN_ID".equals(pColumn.name))
		{
			rule.put("sourcePath", "./id");
			rule.put("targetPath", pColumn.name);
			rule.put("transformation", "golden(" + pCandidate.tablePath + "," + pCandidate.dataspace + "," + pCandidate.dataset + ")");
			return;
		}
		if (pCrosswalk && "MDM_STATE".equals(pColumn.name))
		{
			rule.put("sourcePath", "./id");
			rule.put("targetPath", pColumn.name);
			rule.put("transformation", "mdmstate(" + pCandidate.tablePath + "," + pCandidate.dataspace + "," + pCandidate.dataset + ")");
			return;
		}
		if (pCrosswalk && "SOURCE_SYSTEM".equals(pColumn.name))
		{
			rule.put("sourcePath", "./sourceSystem");
			rule.put("targetPath", pColumn.name);
			return;
		}
		if (pCrosswalk && "SOURCE_ID".equals(pColumn.name))
		{
			rule.put("sourcePath", "./sourceId");
			rule.put("targetPath", pColumn.name);
			return;
		}
		final String field = "GOLDEN_ID".equals(pColumn.name) ? "id" : pColumn.sourceField.substring(pColumn.sourceField.indexOf('.') + 1);
		rule.put("sourcePath", "./" + field);
		rule.put("targetPath", pColumn.name);
	}

	/** Launches the first publication ({@code connectors_publish}) over every root mapping written, unless there is none (e.g. every planned table was crosswalk-only and got blocked). Best-effort: a failure here is reported in {@code pWrite}, never thrown - the catalogue and mapping writes already succeeded and must not be rolled back by a publish-time problem. */
	private static void publishFirstRun(final Session pSession, final Adaptation pFactory, final ProductPlan.Draft pDraft, final List<String> pMappingIds,
		final List<Long> pRowCounts, final ObjectNode pWrite)
	{
		final ObjectNode publish = pWrite.putObject("firstPublication");
		if (pMappingIds.isEmpty())
		{
			publish.put("skipped", "No publishable mapping was created (every included table was non-EBX).");
			return;
		}
		// F2-05: connectors_publish takes no async (Connectors answers it in one call), so its read timeout grows with the rows like a distribution send
		// (300 s + rows x perRowMs, capped), and a second publication of the same product cannot start while the first one is still waited for
		if (!DataProductPublication.PUBLISHING.add(pDraft.code))
		{
			publish.put("ok", false);
			publish.put("code", "external.publishRunning");
			publish.put("detail", EngineRefusal.coded("external.publishRunning", "A publication of data product " + pDraft.code
				+ " is already running: wait for it to end (connectors_run_get, the Connectors sync log) before publishing again.", pDraft.code).getMessage());
			return;
		}
		try
		{
			final int timeoutMs = DataProductPublication.publishTimeoutMs(pRowCounts);
			publish.put("readTimeoutSeconds", timeoutMs / 1000);
			final McpClient client = McpServers.connectors(pSession, pFactory, timeoutMs);
			final ObjectNode args = DataProductPublication.MAPPER.createObjectNode();
			args.put("mappings", DataProductPublication.join(pMappingIds, ","));
			args.put("schema", pDraft.schema);
			args.put("product", pDraft.code);
			args.put("contractVersion", "1");
			args.put("dryRun", false);
			final McpClient.ToolResult result;
			try
			{
				result = client.callTool("connectors_publish", args);
			}
			catch (final McpException ex)
			{
				if (DistributionBatches.isReadTimeout(ex.getMessage()))
				{
					publish.put("ok", false);
					publish.put("code", "external.publishTimeout");
					publish.put("detail", EngineRefusal.coded("external.publishTimeout", ex.getMessage().trim()
						+ " Connectors may still be publishing data product " + pDraft.code + ": look at the Connectors sync log of its mappings (connectors_run_get) before publishing again.",
						pDraft.code).getMessage());
					return;
				}
				throw EngineRefusal.coded("external.toolFailed", "connectors_publish failed: " + DataProductPublication.shorten(ex.getMessage()), "connectors_publish");
			}
			publish.put("ok", !result.error);
			if (result.json != null)
			{
				publish.set("report", result.json);
			}
			else
			{
				publish.put("detail", DataProductPublication.shorten(result.text));
			}
		}
		catch (final OperationException ex)
		{
			publish.put("ok", false);
			publish.put("detail", ex.getMessage());
		}
		finally
		{
			DataProductPublication.PUBLISHING.remove(pDraft.code);
		}
	}

	/** Product codes whose {@code connectors_publish} is being waited for right now (F2-05: no second publication on top of a running one). */
	private static final java.util.Set<String> PUBLISHING = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

	/** The rows each root table of the plan will publish; a table whose count is unknown counts as a full distribution batch. */
	private static List<Long> publishedRows(final ProductPlan.Result pResult)
	{
		final List<Long> rows = new ArrayList<Long>();
		for (final ProductPlan.PlannedTable table : pResult.tables)
		{
			if (table.ebxTable && !table.nested)
			{
				rows.add(Long.valueOf(table.rowCount));
			}
		}
		return rows;
	}

	/**
	 * F2-05, pure: the read timeout of {@code connectors_publish} for tables of these row counts - the distribution send's rule
	 * ({@link DistributionBatches#sendTimeoutMs}: 300 s + rows x {@code perRowMs}, capped at {@code sendTimeoutMaxMs}, 2 h by default). A negative
	 * (unknown) count is taken as {@link DistributionBatches#DEFAULT_MAX_ROWS}.
	 */
	static int publishTimeoutMs(final List<Long> pRowCounts)
	{
		long rows = 0L;
		for (final Long count : pRowCounts)
		{
			rows += count == null || count.longValue() < 0L ? DistributionBatches.DEFAULT_MAX_ROWS : count.longValue();
		}
		return DistributionBatches.sendTimeoutMs((int) Math.min(rows, Integer.MAX_VALUE));
	}

	// ==================================================================================================== helpers

	/**
	 * Coordinator review (2026-09-24, step 8 "Distribute" card of the wizard's Summary, {@code
	 * WIZARD-DISTRIBUTION.md}): a cheap, catalogue-only read of the data product ALREADY created for this
	 * project, if any - no MCP round trip (unlike {@link #detect}, which also probes Snowflake reachability
	 * and is too heavy to call on every Summary render). {@code DataProduct}'s real primary key is {@code
	 * code} (confirmed by reading {@code ebx-data-catalog/tools/build_xsd.py} - see this class's own
	 * Javadoc), and {@link #create}/{@code #defaultDraftJson} both default a fresh product's code to {@code
	 * ProductPlan.sanitize(projectCode)} - the SAME convention this method looks up by. A product created
	 * under a hand-typed, different code (the 7c form lets a person override it) is therefore not found by
	 * this best-effort lookup; {@code exists:false} in that case, never a wrong product.
	 */
	public static ObjectNode existingForSummary(final Session pSession, final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = DataProductPublication.MAPPER.createObjectNode();
		out.put("exists", false);
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			return out;
		}
		final String code = ProductPlan.sanitize(pProjectCode);
		// audit F-02 (catalog reads): the Summary card shows the product: read as the person
		final CatalogReader.Reader reader = CatalogReader.Reader.of(pSession);
		final Adaptation product = CatalogReader.visible(reader, catalog.getTable(DataProductPublication.CAT_DATA_PRODUCT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(code)));
		if (product == null)
		{
			if (reader.restricted())
			{
				out.put("catalogRestricted", true);
			}
			return out;
		}
		out.put("exists", true);
		out.put("code", code);
		final String name = product.getString(DataProductPublication.PRODUCT_NAME);
		out.put("name", name == null || name.trim().isEmpty() ? code : name);
		final String refresh = product.getString(DataProductPublication.PRODUCT_REFRESH_FREQUENCY);
		out.put("refreshMode", refresh == null ? ProductPlan.REFRESH_ON_DEMAND : refresh);
		return out;
	}

	private static Adaptation project(final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectCode));
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " not found.", pProjectCode);
		}
		return project;
	}

	private static String findAssetId(final CatalogReader.Reader pReader, final Adaptation pCatalog, final String pDataspace, final String pDataset, final String pTablePath)
	{
		final String schemaName = pDataspace + "/" + pDataset;
		final CatalogReader.Rows rows = CatalogReader.rows(pReader, pCatalog.getTable(DataProductPublication.CAT_ASSET),
			"./schemaName=" + DataProductPublication.quote(schemaName) + " and ./physicalName=" + DataProductPublication.quote(pTablePath));
		try
		{
			final Adaptation row = rows.next();
			return row == null ? null : row.getOccurrencePrimaryKey().format();
		}
		finally
		{
			rows.close();
		}
	}

	/** OutputPort's primary key is the auto-increment {@code id}, not {@code code} (coordinator review, 2026-09-24 - see {@link #writeCatalogue}'s own comment): looked up by an XPath predicate on {@code ./code}, never {@code PrimaryKey.parseString}. */
	private static Adaptation findPortByCode(final AdaptationTable pPorts, final String pCode)
	{
		final RequestResult rows = pPorts.createRequestResult("./code=" + DataProductPublication.quote(pCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	private static Adaptation byId(final AdaptationTable pTable, final String pId)
	{
		try
		{
			return pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static Object value(final Adaptation pRow, final Path pPath)
	{
		return pRow.getSchemaNode().getNode(pPath) == null ? null : pRow.get(pPath);
	}

	private static ProductPlan.Draft draftFromJson(final JsonNode pJson)
	{
		final ProductPlan.Draft draft = new ProductPlan.Draft();
		if (pJson == null)
		{
			return draft;
		}
		draft.productName = pJson.path("productName").asText("");
		draft.code = pJson.path("code").asText("").trim().toUpperCase(Locale.ROOT);
		draft.owner = pJson.path("owner").asText("");
		draft.domain = pJson.path("domain").asText("");
		draft.description = pJson.path("description").asText("");
		draft.target = pJson.path("target").asText(ProductPlan.TARGET_EBX_ONLY).trim().toUpperCase(Locale.ROOT);
		draft.connectorId = pJson.path("connectorId").asText("");
		draft.schema = pJson.path("schema").asText("");
		draft.refreshMode = pJson.path("refreshMode").asText(ProductPlan.REFRESH_ON_DEMAND).trim().toUpperCase(Locale.ROOT);
		draft.marketplaceListing = pJson.path("marketplaceListing").asBoolean(false);
		for (final JsonNode tableNode : pJson.path("tables"))
		{
			draft.tables.add(new ProductPlan.TableChoice(tableNode.path("key").asText(""), tableNode.path("included").asBoolean(false),
				tableNode.path("nestMode").asText(ProductPlan.NEST_OWN)));
		}
		for (final JsonNode id : pJson.path("addedCatalogAssetIds"))
		{
			draft.addedCatalogAssetIds.add(id.asText());
		}
		return draft;
	}

	private static ObjectNode resultJson(final ProductPlan.Result pResult)
	{
		final ObjectNode out = DataProductPublication.MAPPER.createObjectNode();
		out.put("ok", pResult.ok());
		out.put("contractColumnCount", pResult.contractColumnCount);
		final ArrayNode tables = out.putArray("tables");
		for (final ProductPlan.PlannedTable table : pResult.tables)
		{
			final ObjectNode t = tables.addObject();
			t.put("source", table.source);
			t.put("key", table.key);
			t.put("label", table.label);
			t.put("publishedName", table.publishedName);
			t.put("nested", table.nested);
			t.put("parentPublishedName", table.parentPublishedName);
			t.put("rowCount", table.rowCount);
			t.put("ebxTable", table.ebxTable);
			final ArrayNode columns = t.putArray("columns");
			for (final ProductPlan.Column column : table.columns)
			{
				final ObjectNode c = columns.addObject();
				c.put("name", column.name);
				c.put("dataType", column.dataType);
				c.put("sourceField", column.sourceField);
			}
		}
		final ArrayNode steps = out.putArray("creationSteps");
		for (final String step : pResult.creationSteps)
		{
			steps.add(step);
		}
		final ArrayNode problems = out.putArray("problems");
		for (final ProductPlan.Problem problem : pResult.problems)
		{
			final ObjectNode p = problems.addObject();
			p.put("field", problem.field);
			p.put("key", problem.key);
			p.put("detail", problem.detail);
		}
		final ArrayNode warnings = out.putArray("warnings");
		for (final String warning : pResult.warnings)
		{
			warnings.add(warning);
		}
		return out;
	}

	private static ObjectNode candidateJson(final ProductPlan.Candidate pCandidate)
	{
		final ObjectNode c = DataProductPublication.MAPPER.createObjectNode();
		c.put("source", pCandidate.source);
		c.put("key", pCandidate.key);
		c.put("label", pCandidate.label);
		c.put("origin", pCandidate.origin);
		c.put("rowCount", pCandidate.rowCount);
		c.put("child", pCandidate.child);
		c.put("parentKey", pCandidate.parentKey);
		c.put("ebxTable", pCandidate.ebxTable);
		final ArrayNode columns = c.putArray("columns");
		for (final ProductPlan.Column column : pCandidate.columns)
		{
			final ObjectNode col = columns.addObject();
			col.put("name", column.name);
			col.put("dataType", column.dataType);
			col.put("sourceField", column.sourceField);
		}
		return c;
	}

	private static ObjectNode defaultDraftJson(final String pProjectCode, final List<ProductPlan.Candidate> pCandidates, final JsonNode pWarehouses)
	{
		final ObjectNode draft = DataProductPublication.MAPPER.createObjectNode();
		draft.put("productName", pProjectCode);
		draft.put("code", ProductPlan.sanitize(pProjectCode));
		draft.put("owner", "");
		draft.put("domain", "");
		final boolean snowflakeReachable = pWarehouses.path("snowflake").path("reachable").asBoolean(false);
		draft.put("target", snowflakeReachable ? ProductPlan.TARGET_SNOWFLAKE : ProductPlan.TARGET_EBX_ONLY);
		draft.put("connectorId", pWarehouses.path("snowflake").path("connectorId").asText(""));
		draft.put("schema", "");
		draft.put("refreshMode", ProductPlan.REFRESH_ON_DEMAND);
		draft.put("marketplaceListing", false);
		final ArrayNode tables = draft.putArray("tables");
		for (final ProductPlan.Candidate candidate : pCandidates)
		{
			if (ProductPlan.SOURCE_CATALOG.equals(candidate.source))
			{
				continue; // added explicitly, never on by default
			}
			final ObjectNode t = tables.addObject();
			t.put("key", candidate.key);
			t.put("included", true);
			t.put("nestMode", ProductPlan.NEST_OWN);
		}
		draft.putArray("addedCatalogAssetIds");
		return draft;
	}

	private static List<MasterModel.FieldInfo> orEmpty(final List<MasterModel.FieldInfo> pList)
	{
		return pList == null ? new ArrayList<MasterModel.FieldInfo>() : pList;
	}

	private static String orString(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? "STRING" : pValue.trim().toUpperCase(Locale.ROOT);
	}

	private static String orNull(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? null : pValue.trim();
	}

	private static String quote(final String pValue)
	{
		return "'" + (pValue == null ? "" : pValue.replace("'", "''")) + "'";
	}

	private static String join(final List<String> pValues)
	{
		return DataProductPublication.join(pValues, ", ");
	}

	private static String join(final List<String> pValues, final String pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String value : pValues)
		{
			sb.append(sb.length() > 0 ? pSeparator : "").append(value);
		}
		return sb.toString();
	}

	private static McpClient.ToolResult call(final McpClient pClient, final String pTool, final ObjectNode pArgs) throws OperationException
	{
		try
		{
			return pClient.callTool(pTool, pArgs);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.coded("external.toolFailed", pTool + " failed: " + DataProductPublication.shorten(ex.getMessage()), pTool);
		}
	}

	private static String errorText(final McpClient.ToolResult pResult)
	{
		if (pResult.json != null && pResult.json.hasNonNull("error"))
		{
			return pResult.json.path("error").asText();
		}
		return DataProductPublication.shorten(pResult.text);
	}

	private static String shorten(final String pText)
	{
		if (pText == null)
		{
			return "(no message)";
		}
		final String oneLine = pText.replace('\n', ' ').replace('\r', ' ').trim();
		return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
	}
}
