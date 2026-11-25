package com.on.ebx.factory.ebx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.AttributeInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.AttributeMappingInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.CleaningStep;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.EntityInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.LandingTableInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.ProjectInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.SourceColumn;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.SourceZone;
import com.on.ebx.factory.core.CatalogRightMessages;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Declares one project's STRUCTURE lineage (source table &rarr; landing table &rarr; staging attribute
 * &rarr; master field) to the sibling data catalog, following the SAME channel {@code ebx-connectors} uses
 * for its own declared lineage - HTTP POST, EBX Basic authentication, straight to {@code
 * /ebx-data-catalog/rest/v1/openlineage} (`docs/CATALOG-LINEAGE.md` &sect;1 has the full "how Connectors
 * does it" writeup and why this class does the same rather than something else). Reads the factory's own
 * tables into the pure {@link CatalogLineageBuilder}, then POSTs every job it built in ONE request (the
 * catalog's importer accepts a JSON array of events - {@code OpenLineageImporter.importEvents}, read in
 * full).
 * <p>
 * <b>Coupled to the catalog and to ebx-connectors BY DATA, never by compile-time dependency</b> (the
 * project's standing rule, CLAUDE.md/PLAN.md): the catalog's own dataset is found and checked the exact way
 * {@link CatalogReader#dataset} already does for the wizard's "pick a scanned source" step (no class of
 * {@code ebx-data-catalog} is imported here, only its data); the OpenLineage JSON shape is built from
 * scratch in {@link CatalogLineageBuilder}, following {@code ebx-connectors}' {@code OpenLineageEmitter} as
 * a REFERENCE (read in full) rather than a dependency, for the same reason {@link AgentRuntime} reads the
 * {@code llm} connector of {@code ebx-connectors} as plain rows instead of calling its Java API.
 * <p>
 * <b>Absent catalog: one {@code SKIPPED} {@code PipelineStep} per zone, never an error, never an HTTP
 * attempt</b> - {@link #declare} checks {@link CatalogReader#dataset} FIRST; when it is null, no request is
 * ever sent (task brief: "detect, and do nothing with an explicit note"). A request that IS sent but fails
 * (network, non-2xx, malformed reply) records a {@code FAILED} step with the failure message instead -
 * still never an uncaught exception reaching the caller.
 * <p>
 * <b>Idempotent and re-runnable, never skipped-if-already-done</b> - unlike {@code LandingGenerator}'s
 * one-time setup steps, this is a refresh a caller repeats whenever the project's structure changes (the
 * same "load is not skipped" reasoning {@code LandingFlows}' own Javadoc gives for {@code LANDING_LOAD}):
 * every call declares the entity's/table's WHOLE current ACCEPTED-or-{@code APPLIED} mapping set, and the
 * catalog's own importer retires whatever an earlier call listed that this one does not ({@code
 * OpenLineageImporter.retireEdges}) - so a mapping that was accepted and is later rejected disappears from
 * the graph on the very next {@link #declare} call, with no delta logic needed here.
 */
public final class CatalogLineageDeclarer
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String STEP = "CATALOG_LINEAGE";
	private static final String ZONE_LANDING = "LANDING";
	private static final String ZONE_STAGING = "STAGING";
	private static final String ZONE_MASTER = "MASTER";

	/** {@code LandingTable} statuses a flow (and so a declared edge) can be built from - the same three {@code LandingFlows.FLOW_ELIGIBLE_STATUSES} accepts, kept as its own copy (project convention: readers are not shared across callers, see {@code ebx.RecordLineageReader}'s own Javadoc). */
	private static final Set<String> LANDING_ELIGIBLE_STATUSES = new LinkedHashSet<String>(java.util.Arrays.asList("GENERATED", "LOADED", "DRIFTED"));

	/** Connect/read timeouts for the catalog POST - a bounded metadata call, not a build/publish MCP exchange, so far shorter than {@code McpServers}' own 300s. */
	/** Value of the CSRF header the catalog's OpenLineage route asks of a programmatic caller (audit D2-15). */
	static final String CATALOG_CLIENT_HEADER_VALUE = "ebx-catalog-client";
	static final String CATALOG_CLIENT_HEADER_NAME = "X-Requested-With";

	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int READ_TIMEOUT_MS = 30000;

	private CatalogLineageDeclarer()
	{
	}

	// ============================================================================================== entry point

	public static ObjectNode declare(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = CatalogLineageDeclarer.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();

		final ObjectNode out = CatalogLineageDeclarer.MAPPER.createObjectNode();
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			final String message = "The data catalog (ebx-data-catalog) is not installed on this instance: nothing declared.";
			CatalogLineageDeclarer.recordSkipped(pSession, pFactory, projectId, CatalogLineageDeclarer.ZONE_LANDING, message);
			CatalogLineageDeclarer.recordSkipped(pSession, pFactory, projectId, CatalogLineageDeclarer.ZONE_STAGING, message);
			CatalogLineageDeclarer.recordSkipped(pSession, pFactory, projectId, CatalogLineageDeclarer.ZONE_MASTER, message);
			out.put("status", "SKIPPED");
			out.put("message", message);
			return out;
		}

		final ProjectInput input = CatalogLineageDeclarer.readProjectInput(pFactory, project, projectId);
		final CatalogLineageBuilder.Result result = CatalogLineageBuilder.build(input, new Date());

		final HttpOutcome outcome = result.events.isEmpty() ? HttpOutcome.nothingToSend() : CatalogLineageDeclarer.post(pSession, pFactory, result.events);

		CatalogLineageDeclarer.recordZone(pSession, pFactory, projectId, CatalogLineageDeclarer.ZONE_LANDING, result.counts.landingJobs, outcome, result.notes);
		CatalogLineageDeclarer.recordZone(pSession, pFactory, projectId, CatalogLineageDeclarer.ZONE_STAGING, result.counts.stagingJobs, outcome, result.notes);
		CatalogLineageDeclarer.recordZone(pSession, pFactory, projectId, CatalogLineageDeclarer.ZONE_MASTER, result.counts.masterJobs, outcome, result.notes);

		out.put("status", outcome.ok ? "DECLARED" : "FAILED");
		out.put("landingJobs", result.counts.landingJobs);
		out.put("stagingJobs", result.counts.stagingJobs);
		out.put("masterJobs", result.counts.masterJobs);
		out.put("columnLinks", result.counts.columnLinks);
		out.put("skipped", result.counts.skipped);
		final ArrayNode notes = out.putArray("notes");
		for (final String note : result.notes)
		{
			notes.add(note);
		}
		out.put("catalogHttpStatus", outcome.httpStatus);
		if (outcome.message != null)
		{
			out.put("catalogMessage", outcome.message);
		}
		return out;
	}

	// =================================================================================================== reading

	private static ProjectInput readProjectInput(final Adaptation pFactory, final Adaptation pProject, final String pProjectId)
	{
		final String ebxNamespace = LineageNamespace.resolve(pFactory);
		final String landingDataspace = pProject.getString(FactoryPaths.Project.LANDING_DATASPACE);
		final String stagingDataspace = pProject.getString(FactoryPaths.Project.STAGING_DATASPACE);
		final String stagingDataset = pProject.getString(FactoryPaths.Project.STAGING_DATASET);
		final String masterDataspace = pProject.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDataset = pProject.getString(FactoryPaths.Project.MASTER_DATASET);

		final List<SourceZone> sources = CatalogLineageDeclarer.readSources(pFactory, pProjectId);

		final Map<String, String> entityIdByName = new LinkedHashMap<String, String>();
		final List<StagingModel.EntityInfo> entities = CatalogLineageDeclarer.readEntities(pFactory, pProjectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(entities);
		final Set<String> promoted = new LinkedHashSet<String>(masterModel.builtEntities);

		final List<EntityInput> entityInputs = new ArrayList<EntityInput>();
		for (final StagingModel.EntityInfo entity : entities)
		{
			final String entityId = entityIdByName.get(entity.name);
			final List<AttributeInput> attributes = CatalogLineageDeclarer.readAttributeInputs(pFactory, entityId);
			entityInputs.add(new EntityInput(entity.name, promoted.contains(entity.name), attributes));
		}

		return new ProjectInput(pProject.getString(FactoryPaths.Project.CODE), ebxNamespace, landingDataspace, sources, stagingDataspace, stagingDataset, entityInputs,
			masterDataspace, masterDataset);
	}

	/** {@code ProjectSource} rows of the project, each with its ELIGIBLE {@code LandingTable}/{@code LandingColumn} rows. */
	private static List<SourceZone> readSources(final Adaptation pFactory, final String pProjectId)
	{
		final List<SourceZone> out = new ArrayList<SourceZone>();
		final RequestResult sourceRows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation sourceRow = sourceRows.nextAdaptation(); sourceRow != null; sourceRow = sourceRows.nextAdaptation())
			{
				final String sourceId = sourceRow.getOccurrencePrimaryKey().format();
				final String sourceSystem = sourceRow.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				final String landingDataset = sourceRow.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
				final List<LandingTableInput> tables = new ArrayList<LandingTableInput>();
				final RequestResult tableRows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(sourceId));
				try
				{
					for (Adaptation tableRow = tableRows.nextAdaptation(); tableRow != null; tableRow = tableRows.nextAdaptation())
					{
						if (!CatalogLineageDeclarer.LANDING_ELIGIBLE_STATUSES.contains(tableRow.getString(FactoryPaths.LandingTable.STATUS)))
						{
							continue;
						}
						final String tableId = tableRow.getOccurrencePrimaryKey().format();
						final List<SourceColumn> columns = new ArrayList<SourceColumn>();
						final List<Adaptation> columnRows = new ArrayList<Adaptation>();
						final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(tableId));
						try
						{
							for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
							{
								columnRows.add(row);
							}
						}
						finally
						{
							rows.close();
						}
						java.util.Collections.sort(columnRows, new java.util.Comparator<Adaptation>()
						{
							@Override
							public int compare(final Adaptation pLeft, final Adaptation pRight)
							{
								final Object left = pLeft.get(FactoryPaths.LandingColumn.POSITION);
								final Object right = pRight.get(FactoryPaths.LandingColumn.POSITION);
								final int l = left instanceof Number ? ((Number) left).intValue() : Integer.MAX_VALUE;
								final int r = right instanceof Number ? ((Number) right).intValue() : Integer.MAX_VALUE;
								return Integer.compare(l, r);
							}
						});
						for (final Adaptation row : columnRows)
						{
							columns.add(new SourceColumn(row.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN), row.getString(FactoryPaths.LandingColumn.FIELD_NAME)));
						}
						tables.add(new LandingTableInput(sourceSystem, tableRow.getString(FactoryPaths.LandingTable.SOURCE_OBJECT),
							tableRow.getString(FactoryPaths.LandingTable.TABLE_NAME), columns));
					}
				}
				finally
				{
					tableRows.close();
				}
				out.add(new SourceZone(sourceSystem, landingDataset, tables));
			}
		}
		finally
		{
			sourceRows.close();
		}
		return out;
	}

	/** Every non-primary-key, ACCEPTED-or-{@code APPLIED} {@code StagingAttribute} of the entity, with its ACCEPTED-or-{@code APPLIED} {@code AttributeMapping}s. */
	private static List<AttributeInput> readAttributeInputs(final Adaptation pFactory, final String pEntityId)
	{
		final List<AttributeInput> out = new ArrayList<AttributeInput>();
		if (pEntityId == null)
		{
			return out;
		}
		final Map<String, CatalogLineageDeclarer.LandingColumnRef> columnCache = new HashMap<String, CatalogLineageDeclarer.LandingColumnRef>();
		final Map<String, CatalogLineageDeclarer.LandingTableRef> tableCache = new HashMap<String, CatalogLineageDeclarer.LandingTableRef>();
		final Map<String, String> sourceSystemCache = new HashMap<String, String>();

		final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
			{
				if (Boolean.TRUE.equals(attributeRow.get(FactoryPaths.StagingAttribute.PRIMARY_KEY))
					|| !TechnicalKeyRule.isAcceptedLike(attributeRow.getString(FactoryPaths.StagingAttribute.STATUS)))
				{
					continue;
				}
				final String attributeName = attributeRow.getString(FactoryPaths.StagingAttribute.NAME);
				final String attributeId = attributeRow.getOccurrencePrimaryKey().format();
				final List<AttributeMappingInput> mappings = new ArrayList<AttributeMappingInput>();
				final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult("./attribute=" + CatalogReader.quote(attributeId));
				try
				{
					for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
					{
						if (!TechnicalKeyRule.isAcceptedLike(mappingRow.getString(FactoryPaths.AttributeMapping.STATUS)))
						{
							continue; // PROPOSED or REJECTED: never declared (task brief)
						}
						final String landingColumnId = mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
						final CatalogLineageDeclarer.LandingColumnRef column = CatalogLineageDeclarer.landingColumn(pFactory, landingColumnId, columnCache);
						if (column == null)
						{
							continue;
						}
						final CatalogLineageDeclarer.LandingTableRef table = CatalogLineageDeclarer.landingTableRef(pFactory, column.landingTableId, tableCache);
						if (table == null)
						{
							continue;
						}
						final String sourceSystem = CatalogLineageDeclarer.projectSourceField(pFactory, table.projectSourceId, FactoryPaths.ProjectSource.SOURCE_SYSTEM,
							sourceSystemCache);
						final String mappingId = mappingRow.getOccurrencePrimaryKey().format();
						mappings.add(new AttributeMappingInput(sourceSystem, table.tableName, column.fieldName,
							mappingRow.getString(FactoryPaths.AttributeMapping.TRANSFORMATION), mappingRow.getString(FactoryPaths.AttributeMapping.ARGUMENT),
							CatalogLineageDeclarer.readCleaningSteps(pFactory, mappingId)));
					}
				}
				finally
				{
					mappingRows.close();
				}
				out.add(new AttributeInput(attributeName, mappings));
			}
		}
		finally
		{
			attributeRows.close();
		}
		return out;
	}

	/** This mapping's ACCEPTED-or-{@code APPLIED} {@code MappingStep}s, in {@code position} order (kind + argument - {@code ebx.RecordLineageReader.readCleaningStepKinds} only keeps the kind, this class also needs the argument for {@code ProposalWording}'s wording). */
	private static List<CleaningStep> readCleaningSteps(final Adaptation pFactory, final String pMappingId)
	{
		final List<Adaptation> rows = new ArrayList<Adaptation>();
		final RequestResult stepRows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation row = stepRows.nextAdaptation(); row != null; row = stepRows.nextAdaptation())
			{
				if (TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.MappingStep.STATUS)))
				{
					rows.add(row);
				}
			}
		}
		finally
		{
			stepRows.close();
		}
		java.util.Collections.sort(rows, new java.util.Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				final Object left = pLeft.get(FactoryPaths.MappingStep.POSITION);
				final Object right = pRight.get(FactoryPaths.MappingStep.POSITION);
				final int l = left instanceof Number ? ((Number) left).intValue() : Integer.MAX_VALUE;
				final int r = right instanceof Number ? ((Number) right).intValue() : Integer.MAX_VALUE;
				return Integer.compare(l, r);
			}
		});
		final List<CleaningStep> out = new ArrayList<CleaningStep>();
		for (final Adaptation row : rows)
		{
			out.add(new CleaningStep(row.getString(FactoryPaths.MappingStep.KIND), row.getString(FactoryPaths.MappingStep.ARGUMENT)));
		}
		return out;
	}

	private static final class LandingColumnRef
	{
		final String fieldName;
		final String landingTableId;

		LandingColumnRef(final String pFieldName, final String pLandingTableId)
		{
			this.fieldName = pFieldName;
			this.landingTableId = pLandingTableId;
		}
	}

	private static LandingColumnRef landingColumn(final Adaptation pFactory, final String pLandingColumnId, final Map<String, LandingColumnRef> pCache)
	{
		if (pLandingColumnId == null)
		{
			return null;
		}
		if (pCache.containsKey(pLandingColumnId))
		{
			return pCache.get(pLandingColumnId);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingColumnId));
		final LandingColumnRef ref = row == null ? null
			: new LandingColumnRef(row.getString(FactoryPaths.LandingColumn.FIELD_NAME), row.getString(FactoryPaths.LandingColumn.LANDING_TABLE));
		pCache.put(pLandingColumnId, ref);
		return ref;
	}

	private static final class LandingTableRef
	{
		final String tableName;
		final String projectSourceId;

		LandingTableRef(final String pTableName, final String pProjectSourceId)
		{
			this.tableName = pTableName;
			this.projectSourceId = pProjectSourceId;
		}
	}

	private static LandingTableRef landingTableRef(final Adaptation pFactory, final String pLandingTableId, final Map<String, LandingTableRef> pCache)
	{
		if (pLandingTableId == null)
		{
			return null;
		}
		if (pCache.containsKey(pLandingTableId))
		{
			return pCache.get(pLandingTableId);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
		final LandingTableRef ref = row == null ? null
			: new LandingTableRef(row.getString(FactoryPaths.LandingTable.TABLE_NAME), row.getString(FactoryPaths.LandingTable.PROJECT_SOURCE));
		pCache.put(pLandingTableId, ref);
		return ref;
	}

	private static String projectSourceField(final Adaptation pFactory, final String pProjectSourceId, final Path pField, final Map<String, String> pCache)
	{
		if (pProjectSourceId == null)
		{
			return null;
		}
		final String cacheKey = pProjectSourceId + "#" + pField.format();
		if (pCache.containsKey(cacheKey))
		{
			return pCache.get(cacheKey);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		final String value = row == null ? null : row.getString(pField);
		pCache.put(cacheKey, value);
		return value;
	}

	/** Every {@code StagingEntity} of the project - the same reading shape {@code ebx.RecordLineageReader#readEntities} uses, kept as its own copy (project convention: see that method's own Javadoc). */
	private static List<StagingModel.EntityInfo> readEntities(final Adaptation pFactory, final String pProjectId, final Map<String, String> pEntityIdByName)
	{
		final List<Adaptation> entityRowsList = new ArrayList<Adaptation>();
		final Map<String, String> entityIdToName = new LinkedHashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityRowsList.add(row);
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.StagingEntity.NAME);
				entityIdToName.put(id, name);
				pEntityIdByName.put(name, id);
			}
		}
		finally
		{
			entityRows.close();
		}
		final List<StagingModel.EntityInfo> out = new ArrayList<StagingModel.EntityInfo>();
		for (final Adaptation row : entityRowsList)
		{
			final List<String> memberNames = new ArrayList<String>();
			final List<?> rawMemberIds = row.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					final String memberName = entityIdToName.get(String.valueOf(rawMemberId));
					if (memberName != null)
					{
						memberNames.add(memberName);
					}
				}
			}
			// this class never reads attribute business shapes (data type/length/mandatory/reference) - only
			// MasterModel.buildFromStaging's PROMOTION decision (memberEntities alone, see its own Javadoc) needs
			// the entity list at all. But buildFromStaging first re-derives StagingModel's own "is this entity
			// even a candidate at all" test (StagingModel.build pass 1: a primary-key attribute AND a business
			// attribute, both accepted-like - read in full, 2026-09-21), so a placeholder pair of exactly that
			// shape is supplied here, never the real attributes - an entity this method builds a placeholder
			// for is, by construction, already known ACCEPTED-or-APPLIED (read from StagingEntity.status
			// itself), so it would pass the real test too were the real attributes read.
			final List<StagingModel.AttributeInfo> attributes = new ArrayList<StagingModel.AttributeInfo>();
			attributes.add(new StagingModel.AttributeInfo("_placeholderKey", null, "STRING", null, true, true, null, "ACCEPTED"));
			attributes.add(new StagingModel.AttributeInfo("_placeholderBusiness", null, "STRING", null, false, false, null, "ACCEPTED"));
			out.add(new StagingModel.EntityInfo(row.getString(FactoryPaths.StagingEntity.NAME), row.getString(FactoryPaths.StagingEntity.LABEL),
				row.getString(FactoryPaths.StagingEntity.STATUS), attributes, memberNames));
		}
		return out;
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	// ====================================================================================================== http

	private static final class HttpOutcome
	{
		final boolean ok;
		final int httpStatus;
		final String message;

		HttpOutcome(final boolean pOk, final int pHttpStatus, final String pMessage)
		{
			this.ok = pOk;
			this.httpStatus = pHttpStatus;
			this.message = pMessage;
		}

		static HttpOutcome nothingToSend()
		{
			return new HttpOutcome(true, 0, "Nothing to declare (no ACCEPTED-or-APPLIED structure found).");
		}
	}

	/** Headers of one declaration request: the catalog's CSRF marker on every path (D2-15), then the cookie when there is one, else the Authorization header. Pure. */
	static Map<String, String> requestHeaders(final String pCookie, final String pAuthorization)
	{
		final Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/json; charset=UTF-8");
		headers.put(CATALOG_CLIENT_HEADER_NAME, CATALOG_CLIENT_HEADER_VALUE);
		if (pCookie != null)
		{
			headers.put("Cookie", pCookie);
		}
		else
		{
			headers.put("Authorization", pAuthorization);
		}
		return headers;
	}

	/** POSTs every event as one JSON array to {@code <this node>/ebx-data-catalog/rest/v1/openlineage} ({@link LocalEndpoint}), as the caller (a session token, {@link SessionToken}), the agent account only as a fallback allowed by {@link CredentialPolicy}. Never throws. */
	private static HttpOutcome post(final Session pSession, final Adaptation pFactory, final List<ObjectNode> pEvents)
	{
		try
		{
			// as the caller when possible (the catalog's REST API accepts EBX's EBXUID cookie: 200 with, 401 without,
			// measured 2026-09-24), the agent account only through CredentialPolicy
			String authorizationHeader = null;
			final String cookie = SessionToken.cookie(pSession, pFactory.getHome().getRepository());
			if (cookie == null)
			{
				CredentialPolicy.checkFallback(pFactory, "ebx-data-catalog", pSession == null ? "no session" : "no session token could be minted");
				final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
				final String user = settings == null ? null : settings.getString(FactoryPaths.Settings.AGENT_USER);
				final String password = settings == null ? null : McpServers.secret(settings.getString(FactoryPaths.Settings.AGENT_PASSWORD));
				if (user == null || user.trim().isEmpty() || password == null || password.isEmpty())
				{
					return new HttpOutcome(false, 0, "MDM Factory settings: the agent account is not set");
				}
				authorizationHeader = "Basic " + Base64.getEncoder().encodeToString((user.trim() + ":" + password).getBytes(StandardCharsets.UTF_8));
			}
			final String base = LocalEndpoint.resolve(pFactory).baseUrl;
			CredentialPolicy.requireOwnNode(base);
			final ArrayNode body = CatalogLineageDeclarer.MAPPER.createArrayNode();
			for (final ObjectNode event : pEvents)
			{
				body.add(event);
			}
			final URL url = new URL(base + "/ebx-data-catalog/rest/v1/openlineage");
			final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setInstanceFollowRedirects(false); // security review: never replay the session token to a redirect target
			final Map<String, String> headers = CatalogLineageDeclarer.requestHeaders(cookie, authorizationHeader);
			for (final Map.Entry<String, String> header : headers.entrySet())
			{
				connection.setRequestProperty(header.getKey(), header.getValue());
			}
			connection.setConnectTimeout(CatalogLineageDeclarer.CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(CatalogLineageDeclarer.READ_TIMEOUT_MS);
			connection.setDoOutput(true);
			final byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
			final OutputStream requestBody = connection.getOutputStream();
			try
			{
				requestBody.write(payload);
			}
			finally
			{
				requestBody.close();
			}
			final int status = connection.getResponseCode();
			final String responseText = CatalogLineageDeclarer.readFully(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
			if (status == 403)
			{
				// audit F-02 (catalog reads): the catalog's OpenLineage route asks the catalog right ADMINISTER of the caller
				return new HttpOutcome(false, status, TextLimits.truncate(CatalogRightMessages.refusedOrRaw(CatalogRightMessages.CatalogWrite.DECLARE_LINEAGE,
					"HTTP 403 " + responseText), TextLimits.MESSAGE_MAX_LENGTH));
			}
			if (status < 200 || status >= 300)
			{
				return new HttpOutcome(false, status, TextLimits.truncate("Catalog refused the declaration: HTTP " + status + " " + responseText, TextLimits.MESSAGE_MAX_LENGTH));
			}
			String reportMessage = null;
			try
			{
				final JsonNode report = CatalogLineageDeclarer.MAPPER.readTree(responseText);
				reportMessage = "catalog reports " + report.path("edges").asInt(0) + " edge(s), " + report.path("edgesRetired").asInt(0) + " retired, "
					+ report.path("errors").size() + " error(s)";
			}
			catch (final IOException ex)
			{
				reportMessage = "catalog answered a non-JSON body";
			}
			return new HttpOutcome(status != 207, status, TextLimits.truncate(reportMessage, TextLimits.MESSAGE_MAX_LENGTH));
		}
		catch (final IOException ex)
		{
			return new HttpOutcome(false, 0, TextLimits.truncate("Could not reach the data catalog: " + ex, TextLimits.MESSAGE_MAX_LENGTH));
		}
		catch (final OperationException ex)
		{
			return new HttpOutcome(false, 0, TextLimits.truncate(ex.getMessage(), TextLimits.MESSAGE_MAX_LENGTH));
		}
		catch (final RuntimeException ex)
		{
			return new HttpOutcome(false, 0, TextLimits.truncate("Declaring lineage failed unexpectedly: " + ex, TextLimits.MESSAGE_MAX_LENGTH));
		}
	}

	private static String readFully(final InputStream pStream) throws IOException
	{
		if (pStream == null)
		{
			return "";
		}
		try
		{
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			final byte[] chunk = new byte[4096];
			int read;
			while ((read = pStream.read(chunk)) >= 0)
			{
				buffer.write(chunk, 0, read);
			}
			return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
		}
		finally
		{
			pStream.close();
		}
	}

	// ============================================================================================= PipelineStep

	private static void recordZone(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pZone, final int pJobCount,
		final HttpOutcome pOutcome, final List<String> pNotes) throws OperationException
	{
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, CatalogLineageDeclarer.STEP, pZone);
		final String message = pJobCount + " job(s) declared" + (pOutcome.message == null ? "" : " - " + pOutcome.message);
		final ObjectNode detail = CatalogLineageDeclarer.MAPPER.createObjectNode();
		detail.put("jobs", pJobCount);
		detail.put("httpStatus", pOutcome.httpStatus);
		final ArrayNode notes = detail.putArray("notes");
		for (final String note : pNotes)
		{
			notes.add(note);
		}
		PipelineSteps.end(pSession, pFactory, stepId, pOutcome.ok, message, detail.toString());
	}

	private static void recordSkipped(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pZone, final String pMessage)
		throws OperationException
	{
		CatalogLineageDeclarer.writeStatus(pSession, pFactory, pProjectId, pZone, "SKIPPED", pMessage);
	}

	/**
	 * {@code PipelineSteps.begin}/{@code end} only ever write {@code DONE}/{@code FAILED} - this class also
	 * needs {@code SKIPPED} for "the catalog is absent", a real value of the same {@code PipelineStep.status}
	 * enumeration ({@code wizard.WizardSnapshot}'s own Javadoc lists it), so this writes the row directly
	 * rather than widening a shared helper other callers rely on for its current two-outcome contract.
	 */
	private static void writeStatus(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pZone, final String pStatus,
		final String pMessage) throws OperationException
	{
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, CatalogLineageDeclarer.STEP, pZone);
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final com.orchestranetworks.service.ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = steps.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(stepId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pStatus, FactoryPaths.PipelineStep.STATUS);
				value.setValue(new Date(), FactoryPaths.PipelineStep.ENDED_AT);
				value.setValue(TextLimits.truncate(pMessage, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.PipelineStep.MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			// the step row `begin` wrote is still RUNNING: end it FAILED (best-effort) rather than leave it that way, and say it to the caller
			final String why = result.getExceptionFullMessage(java.util.Locale.ENGLISH);
			PipelineSteps.end(pSession, pFactory, stepId, false, "could not record the " + pStatus + " outcome of zone " + pZone + ": " + why, null);
			throw EngineRefusal.coded("unexpected.lineageOutcome", "Could not record the " + pStatus + " outcome of the catalog lineage declaration for zone " + pZone + ": " + why, pStatus, pZone);
		}
	}
}
