package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.PublishDecision;
import com.on.ebx.factory.landing.SchemaDrift;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Source drift (lot B, 2026-10-30, {@code docs/DRIFT.md}): the {@code SourceDrift} proposals and what accepting one does. Detection runs in
 * the landing plan ({@link LandingPlanner}, a rescan of the catalog): each column ADDED, REMOVED or RETYPED since the landing was generated becomes one
 * PROPOSED row with the landing, staging and mapping changes it implies. Nothing changes until a person decides: a steward accepts or rejects
 * ({@link #decide}), then a designer applies an accepted row ({@link #apply}) - the landing model patched through {@code apply_data_model_patch} and
 * republished, the {@code LandingColumn} rows updated, the landing flow regenerated.
 */
public final class SourceDrifts
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private SourceDrifts()
	{
	}

	// --------------------------------------------------------------------------------------------------- detection

	/** The {@code LandingColumn} rows of a landing table, as the drift reads them. */
	static List<SchemaDrift.Known> known(final Adaptation pFactory, final Adaptation pLandingTable)
	{
		final List<SchemaDrift.Known> out = new ArrayList<SchemaDrift.Known>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN)
			.createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTable.getOccurrencePrimaryKey().format()));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new SchemaDrift.Known(row.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN), row.getString(FactoryPaths.LandingColumn.FIELD_NAME),
					row.getString(FactoryPaths.LandingColumn.SOURCE_DATA_TYPE), Boolean.TRUE.equals(row.get(FactoryPaths.LandingColumn.SOURCE_KEY))));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** The scanned columns of a catalog asset, typed the way the planner records {@code sourceDataType}. */
	static List<SchemaDrift.Scanned> scanned(final CatalogReader.Asset pAsset)
	{
		final List<SchemaDrift.Scanned> out = new ArrayList<SchemaDrift.Scanned>();
		for (final CatalogReader.Column column : pAsset.columns)
		{
			out.add(new SchemaDrift.Scanned(column.name, column.nativeType == null || column.nativeType.trim().isEmpty() ? column.dataType : column.nativeType,
				column.dataType));
		}
		return out;
	}

	/**
	 * Records the changes of one landing table, inside the planner's procedure: one PROPOSED row per change not already on file with the same signature
	 * (PROPOSED, ACCEPTED, REJECTED or FAILED: a rescan never brings back what a person rejected, and a change whose application failed after the model was
	 * patched is retried from its own row, not proposed a second time - F3-113). Returns the rows created.
	 */
	static int record(final ProcedureContext pContext, final Adaptation pFactory, final Adaptation pLandingTable, final List<SchemaDrift.Change> pChanges,
		final String pStatusBefore) throws OperationException
	{
		final String tableId = pLandingTable.getOccurrencePrimaryKey().format();
		final Set<String> open = new HashSet<String>();
		String statusBefore = pStatusBefore;
		for (final Adaptation row : SourceDrifts.rows(pFactory, "./landingTable=" + CatalogReader.quote(tableId) + " and (./status='PROPOSED' or ./status='ACCEPTED' or ./status='REJECTED' or ./status='FAILED')"))
		{
			open.add(row.getString(FactoryPaths.SourceDrift.SIGNATURE));
			if (statusBefore == null)
			{
				statusBefore = row.getString(FactoryPaths.SourceDrift.TABLE_STATUS_BEFORE); // the table is already DRIFTED: keep what it was before
			}
		}
		final String tableName = pLandingTable.getString(FactoryPaths.LandingTable.TABLE_NAME);
		final String sourceId = pLandingTable.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
		final Adaptation source = sourceId == null ? null : pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(sourceId));
		final String projectId = source == null ? null : source.getString(FactoryPaths.ProjectSource.PROJECT);
		final AdaptationTable table = pFactory.getTable(FactoryPaths.SOURCE_DRIFT);
		int created = 0;
		for (final SchemaDrift.Change change : pChanges)
		{
			if (!open.add(change.signature()))
			{
				continue;
			}
			final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
			value.setValue(projectId, FactoryPaths.SourceDrift.PROJECT);
			value.setValue(tableId, FactoryPaths.SourceDrift.LANDING_TABLE);
			value.setValue(change.kind, FactoryPaths.SourceDrift.KIND);
			value.setValue(TextLimits.truncate(change.sourceColumn, 255), FactoryPaths.SourceDrift.SOURCE_COLUMN);
			value.setValue(TextLimits.truncate(change.fieldName, 80), FactoryPaths.SourceDrift.FIELD_NAME);
			value.setValue(TextLimits.truncate(change.oldType, 120), FactoryPaths.SourceDrift.OLD_TYPE);
			value.setValue(TextLimits.truncate(change.newType, 120), FactoryPaths.SourceDrift.NEW_TYPE);
			value.setValue(TextLimits.truncate(change.newDataType, 40), FactoryPaths.SourceDrift.NEW_DATA_TYPE);
			value.setValue(Boolean.valueOf(change.sourceKey), FactoryPaths.SourceDrift.SOURCE_KEY);
			value.setValue(TextLimits.truncate(change.signature(), 400), FactoryPaths.SourceDrift.SIGNATURE);
			value.setValue(TextLimits.truncate(SchemaDrift.landingImpact(tableName, change), 2000), FactoryPaths.SourceDrift.LANDING_CHANGE);
			value.setValue(TextLimits.truncate(SchemaDrift.stagingImpact(change, SourceDrifts.mappedAttributes(pFactory, tableId, change.sourceColumn)), 2000),
				FactoryPaths.SourceDrift.STAGING_CHANGE);
			value.setValue(TextLimits.truncate(SchemaDrift.mappingImpact(change), 2000), FactoryPaths.SourceDrift.MAPPING_CHANGE);
			// shown for information only (F3-01): nothing ever executes this text, apply() recomputes the operations from the real structures
			value.setValue(SchemaDrift.operations(tableName, change).toString(), FactoryPaths.SourceDrift.OPERATIONS);
			value.setValue(statusBefore, FactoryPaths.SourceDrift.TABLE_STATUS_BEFORE);
			value.setValue("PROPOSED", FactoryPaths.SourceDrift.STATUS);
			value.setValue(new Date(), FactoryPaths.SourceDrift.DETECTED_AT);
			pContext.doCreateOccurrence(value, table);
			created++;
		}
		return created;
	}

	/** The names of the staging attributes whose mappings read {@code pSourceColumn} of the landing table. */
	static List<String> mappedAttributes(final Adaptation pFactory, final String pLandingTableId, final String pSourceColumn)
	{
		final List<String> out = new ArrayList<String>();
		final Adaptation column = SourceDrifts.landingColumn(pFactory, pLandingTableId, pSourceColumn);
		if (column == null)
		{
			return out;
		}
		for (final Adaptation mapping : SourceDrifts.mappingsOf(pFactory, column))
		{
			final String attributeId = mapping.getString(FactoryPaths.AttributeMapping.ATTRIBUTE);
			final Adaptation attribute = attributeId == null ? null
				: pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(attributeId));
			final String name = attribute == null ? attributeId : attribute.getString(FactoryPaths.StagingAttribute.NAME);
			if (name != null && !out.contains(name))
			{
				out.add(name);
			}
		}
		return out;
	}

	/** The drifts of a project, newest first ({@code pStatus} empty = every status). */
	public static ArrayNode list(final Adaptation pFactory, final String pProjectId, final String pStatus)
	{
		final ArrayNode out = SourceDrifts.MAPPER.createArrayNode();
		final String status = pStatus == null ? "" : pStatus.trim().toUpperCase(Locale.ROOT).replace("'", "");
		final List<Adaptation> rows = SourceDrifts.rows(pFactory, "./project=" + CatalogReader.quote(pProjectId) + (status.isEmpty() ? "" : " and ./status='" + status + "'"));
		for (int i = rows.size() - 1; i >= 0; i--)
		{
			final Adaptation row = rows.get(i);
			final ObjectNode one = out.addObject();
			one.put("id", row.getOccurrencePrimaryKey().format());
			for (final Object[] field : new Object[][] { { "landingTable", FactoryPaths.SourceDrift.LANDING_TABLE }, { "kind", FactoryPaths.SourceDrift.KIND },
				{ "sourceColumn", FactoryPaths.SourceDrift.SOURCE_COLUMN }, { "fieldName", FactoryPaths.SourceDrift.FIELD_NAME },
				{ "oldType", FactoryPaths.SourceDrift.OLD_TYPE }, { "newType", FactoryPaths.SourceDrift.NEW_TYPE },
				{ "landingChange", FactoryPaths.SourceDrift.LANDING_CHANGE }, { "stagingChange", FactoryPaths.SourceDrift.STAGING_CHANGE },
				{ "mappingChange", FactoryPaths.SourceDrift.MAPPING_CHANGE }, { "status", FactoryPaths.SourceDrift.STATUS },
				{ "decidedBy", FactoryPaths.SourceDrift.DECIDED_BY }, { "appliedBy", FactoryPaths.SourceDrift.APPLIED_BY },
				{ "applyMessage", FactoryPaths.SourceDrift.APPLY_MESSAGE } })
			{
				final String value = row.getString((com.orchestranetworks.schema.Path) field[1]);
				if (value != null)
				{
					one.put((String) field[0], value);
				}
			}
			one.put("sourceKey", Boolean.TRUE.equals(row.get(FactoryPaths.SourceDrift.SOURCE_KEY)));
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------------- decisions

	/** A steward's decision on a PROPOSED row: ACCEPTED (still to be applied) or REJECTED. */
	public static ObjectNode decide(final Session pSession, final Adaptation pFactory, final String pId, final boolean pAccept, final String pDecidedBy)
		throws OperationException
	{
		FeatureSwitches.requireSourceDrift(); // F3-105: closed unless the instance opens it
		final Adaptation row = SourceDrifts.row(pFactory, pId);
		final String status = row.getString(FactoryPaths.SourceDrift.STATUS);
		if (!"PROPOSED".equals(status))
		{
			throw EngineRefusal.coded("conflict.driftDecided", "The source drift " + pId + " is already " + status + ".", pId, String.valueOf(status));
		}
		final String next = pAccept ? "ACCEPTED" : "REJECTED";
		SourceDrifts.update(pSession, pFactory, row, next, pDecidedBy, null, null);
		if (!pAccept)
		{
			SourceDrifts.settleTable(pSession, pFactory, row.getString(FactoryPaths.SourceDrift.LANDING_TABLE), row.getString(FactoryPaths.SourceDrift.TABLE_STATUS_BEFORE));
		}
		final ObjectNode out = SourceDrifts.MAPPER.createObjectNode();
		out.put("id", pId);
		out.put("status", next);
		return out;
	}

	/**
	 * Applies an ACCEPTED row, as {@code pSession} (the designer who asked): ADDED patches the landing model ({@code create_field}), republishes it and adds
	 * the {@code LandingColumn}; REMOVED removes the column's mappings and its {@code LandingColumn} (the landing field and the rows loaded stay); RETYPED
	 * records the new type. ADDED and REMOVED then regenerate the landing flow. A failure leaves the row FAILED with the reason, and the model, if already
	 * patched, as it is (a re-apply of an ADDED row whose field exists is refused by the server: see {@code docs/DRIFT.md}).
	 */
	public static ObjectNode apply(final Session pSession, final Adaptation pFactory, final String pId, final String pAppliedBy) throws OperationException
	{
		FeatureSwitches.requireSourceDrift(); // F3-105: closed unless the instance opens it
		final Adaptation row = SourceDrifts.row(pFactory, pId);
		final String status = row.getString(FactoryPaths.SourceDrift.STATUS);
		if (!"ACCEPTED".equals(status) && !"FAILED".equals(status))
		{
			throw EngineRefusal.coded("conflict.driftNotAccepted", "The source drift " + pId + " is " + status + ": only an accepted change is applied.", pId,
				String.valueOf(status));
		}
		final String tableId = row.getString(FactoryPaths.SourceDrift.LANDING_TABLE);
		final Adaptation landingTable = tableId == null ? null : pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(tableId));
		if (landingTable == null)
		{
			throw EngineRefusal.coded("missing.driftLandingTable", "The landing table of source drift " + pId + " no longer exists.", pId);
		}
		final String sourceId = landingTable.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
		final Adaptation source = sourceId == null ? null : pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(sourceId));
		final String model = source == null ? null : source.getString(FactoryPaths.ProjectSource.LANDING_MODEL);
		// F3-01: the row only designates the change (kind, column, field); what is executed is recomputed here from the real structures
		final SchemaDrift.Change change = SourceDrifts.recompute(pSession, pFactory, landingTable, source, row, pId);
		final String kind = change.kind;
		final ObjectNode out = SourceDrifts.MAPPER.createObjectNode();
		out.put("id", pId);
		out.put("kind", kind);
		final StringBuilder message = new StringBuilder();
		try
		{
			if (SchemaDrift.ADDED.equals(kind))
			{
				if (model == null || model.trim().isEmpty())
				{
					throw EngineRefusal.coded("missing.driftLandingModel", "The project source of source drift " + pId + " has no landing model.", pId);
				}
				if (SchemaDrift.modelAlreadyPatched(row.getString(FactoryPaths.SourceDrift.APPLY_MESSAGE), change.fieldName, model))
				{
					// F3-113: an earlier attempt patched and republished the model, then failed: the field exists, only the rest is replayed
					message.append("Field ").append(change.fieldName).append(" added to ").append(model).append(" and republished. ");
				}
				else
				{
					SourceDrifts.patchAndPublish(pSession, pFactory, model, SchemaDrift.operations(landingTable.getString(FactoryPaths.LandingTable.TABLE_NAME), change), out);
					message.append("Field ").append(change.fieldName).append(" added to ").append(model).append(" and republished. ");
				}
				SourceDrifts.addColumn(pSession, pFactory, landingTable, change);
			}
			else if (SchemaDrift.REMOVED.equals(kind))
			{
				message.append(SourceDrifts.removeColumn(pSession, pFactory, tableId, change.sourceColumn)).append(' ');
			}
			else
			{
				SourceDrifts.retypeColumn(pSession, pFactory, tableId, change);
				message.append("Source type of ").append(change.sourceColumn).append(" recorded as ").append(change.newType).append(". ");
			}
			SourceDrifts.update(pSession, pFactory, row, "APPLIED", null, pAppliedBy, null);
			SourceDrifts.settleTable(pSession, pFactory, tableId, row.getString(FactoryPaths.SourceDrift.TABLE_STATUS_BEFORE));
			if (!SchemaDrift.RETYPED.equals(kind))
			{
				try
				{
					LandingFlows.generateOne(pSession, pFactory, sourceId, tableId);
					message.append("Landing flow regenerated.");
				}
				catch (final OperationException | RuntimeException ex)
				{
					message.append("The landing flow could not be regenerated (").append(ex.getMessage()).append("): regenerate it from the landing step.");
				}
			}
			SourceDrifts.update(pSession, pFactory, SourceDrifts.row(pFactory, pId), "APPLIED", null, pAppliedBy, message.toString().trim());
			out.put("status", "APPLIED");
			out.put("message", message.toString().trim());
			return out;
		}
		catch (final OperationException | RuntimeException ex)
		{
			final String reason = message.append(ex.getMessage()).toString().trim();
			try
			{
				SourceDrifts.update(pSession, pFactory, SourceDrifts.row(pFactory, pId), "FAILED", null, pAppliedBy, reason);
			}
			catch (final OperationException written)
			{
				LoggingCategory.getKernel().warn("[mdm-factory] source drift " + pId + ": the failure could not be recorded: " + written.getMessage());
			}
			throw ex;
		}
	}

	/**
	 * F3-01: the change to execute, recomputed from the landing's frozen columns and the catalog's CURRENT scan of the table's asset
	 * ({@link SchemaDrift#revalidate}); the stored row only names the change. A refusal is coded and tells the person to plan again.
	 */
	private static SchemaDrift.Change recompute(final Session pSession, final Adaptation pFactory, final Adaptation pLandingTable, final Adaptation pSource,
		final Adaptation pRow, final String pId) throws OperationException
	{
		final String catalogSource = pSource == null ? null : pSource.getString(FactoryPaths.ProjectSource.CATALOG_SOURCE);
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		CatalogReader.Asset asset = null;
		if (catalog != null && catalogSource != null && !catalogSource.trim().isEmpty())
		{
			final String sourceObject = pLandingTable.getString(FactoryPaths.LandingTable.SOURCE_OBJECT);
			for (final CatalogReader.Asset one : CatalogReader.assets(CatalogReader.Reader.of(pSession), catalog, catalogSource.trim(), new ArrayList<String>()))
			{
				if (sourceObject != null && sourceObject.equals(one.qualifiedName))
				{
					asset = one;
					break;
				}
			}
		}
		if (asset == null)
		{
			throw EngineRefusal.coded("missing.driftAsset",
				"The scanned structure of the table of source drift " + pId + " cannot be read from the catalog, so the change cannot be recomputed: nothing was applied.", pId);
		}
		final SchemaDrift.Verdict verdict = SchemaDrift.revalidate(pLandingTable.getString(FactoryPaths.LandingTable.TABLE_NAME), pRow.getString(FactoryPaths.SourceDrift.KIND),
			pRow.getString(FactoryPaths.SourceDrift.SOURCE_COLUMN), pRow.getString(FactoryPaths.SourceDrift.FIELD_NAME), SourceDrifts.known(pFactory, pLandingTable),
			SourceDrifts.scanned(asset));
		if (!verdict.ok())
		{
			throw EngineRefusal.coded(verdict.refusal, "The source drift " + pId + " cannot be applied (" + verdict.refusal + ": " + verdict.about
				+ "): nothing was applied. If the source changed since the drift was recorded, plan the landing again.", pId, verdict.about);
		}
		return verdict.change;
	}

	/**
	 * The route's gate (F3-12): the drift's project, or a coded refusal when the function is closed, the drift does not exist or it names no project.
	 * A drift without a project used to skip the project permission check.
	 */
	public static String projectOrRefuse(final Adaptation pFactory, final String pId) throws OperationException
	{
		FeatureSwitches.requireSourceDrift();
		final String project = SourceDrifts.projectOf(pFactory, pId);
		if (project == null || project.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.driftProject", "The source drift " + pId + " does not exist or names no project: it cannot be decided or applied.", String.valueOf(pId));
		}
		// 1.4.2 (F3-12): the project a steward may write on the row is only a claim - the second permissions pass must run on the project the landing table really belongs to
		final String real = SourceDrifts.landingProjectOf(pFactory, pId);
		if (!SourceDrifts.sameProject(project, real))
		{
			throw EngineRefusal.coded("invalid.driftProjectMismatch", "The source drift " + pId + " names project " + project + " but its landing table belongs to "
				+ (real == null ? "no project" : "project " + real) + ": it cannot be decided or applied. Ask an administrator to correct or delete the row.", String.valueOf(pId), project);
		}
		return real;
	}

	/** Pure: whether the project written on a drift row is the project of its landing table (both present and equal). */
	static boolean sameProject(final String pClaimed, final String pReal)
	{
		return pClaimed != null && pReal != null && !pClaimed.trim().isEmpty() && pClaimed.trim().equals(pReal.trim());
	}

	/** The project the landing table of a drift belongs to (landing table, its source, the source's project); null when any link is missing. */
	static String landingProjectOf(final Adaptation pFactory, final String pId)
	{
		try
		{
			final Adaptation drift = pFactory.getTable(FactoryPaths.SOURCE_DRIFT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId.trim()));
			final String landingKey = drift == null ? null : drift.getString(FactoryPaths.SourceDrift.LANDING_TABLE);
			final Adaptation landing = landingKey == null || landingKey.trim().isEmpty() ? null
				: pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(landingKey.trim()));
			final String sourceKey = landing == null ? null : landing.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
			final Adaptation source = sourceKey == null || sourceKey.trim().isEmpty() ? null
				: pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(sourceKey.trim()));
			return source == null ? null : source.getString(FactoryPaths.ProjectSource.PROJECT);
		}
		catch (final RuntimeException malformed)
		{
			return null;
		}
	}

	/** Patches and republishes the landing model with {@code pOperations}, which the caller computed itself (never read from a stored row: F3-01). */
	private static void patchAndPublish(final Session pSession, final Adaptation pFactory, final String pModel, final ArrayNode pOperations, final ObjectNode pOut)
		throws OperationException
	{
		final ArrayNode operations = pOperations;
		try
		{
			final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
			if (operations.size() > 0)
			{
				final ObjectNode patchArgs = SourceDrifts.MAPPER.createObjectNode();
				patchArgs.put("data_model_name", pModel);
				patchArgs.put("operations_json", operations.toString());
				patchArgs.put("gate", false); // publish_data_model, which follows, is the gate (as in MasterGenerator)
				final McpClient.ToolResult patch = client.callTool("apply_data_model_patch", patchArgs);
				pOut.put("patch", SourceDrifts.shortText(patch.text));
				final int failed = patch.json == null ? 0 : patch.json.path("failed").asInt(0);
				if (patch.error || failed > 0 || patch.json != null && (!patch.json.path("compiled").asBoolean(true) || !patch.json.path("valid").asBoolean(true)))
				{
					throw EngineRefusal.coded("external.driftPatch", "apply_data_model_patch could not change " + pModel + ": " + SourceDrifts.shortText(patch.text), pModel);
				}
			}
			final ObjectNode publishArgs = SourceDrifts.MAPPER.createObjectNode();
			publishArgs.put("data_model_name", pModel);
			final McpClient.ToolResult publish = client.callTool("publish_data_model", publishArgs);
			if (publish.error || !PublishDecision.published(publish.json))
			{
				throw EngineRefusal.coded("external.driftPublish", "publish_data_model could not republish " + pModel + ": " + SourceDrifts.shortText(publish.text), pModel);
			}
			pOut.put("published", true);
		}
		catch (final McpException ex)
		{
			throw EngineRefusal.coded("external.driftPatch", "The data model server could not be reached for " + pModel + ": " + ex.getMessage(), pModel);
		}
	}

	private static void addColumn(final Session pSession, final Adaptation pFactory, final Adaptation pLandingTable, final SchemaDrift.Change pChange) throws OperationException
	{
		final String tableId = pLandingTable.getOccurrencePrimaryKey().format();
		int position = 0;
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(tableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object one = row.get(FactoryPaths.LandingColumn.POSITION);
				position = Math.max(position, one instanceof Integer ? ((Integer) one).intValue() : 0);
			}
		}
		finally
		{
			rows.close();
		}
		final int next = position + 1;
		SourceDrifts.write(pSession, pFactory, pContext -> {
			final AdaptationTable table = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
			final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
			value.setValue(tableId, FactoryPaths.LandingColumn.LANDING_TABLE);
			value.setValue(Integer.valueOf(next), FactoryPaths.LandingColumn.POSITION);
			value.setValue(pChange.sourceColumn, FactoryPaths.LandingColumn.SOURCE_COLUMN);
			value.setValue(pChange.fieldName, FactoryPaths.LandingColumn.FIELD_NAME);
			value.setValue(pChange.newType, FactoryPaths.LandingColumn.SOURCE_DATA_TYPE);
			value.setValue(Boolean.FALSE, FactoryPaths.LandingColumn.SOURCE_KEY);
			value.setValue(LandingPlanner.inferredType(pChange.newDataType), FactoryPaths.LandingColumn.INFERRED_TYPE);
			pContext.doCreateOccurrence(value, table);
		});
	}

	/** Removes the column's mappings and its {@code LandingColumn}; the sentence says what went. */
	private static String removeColumn(final Session pSession, final Adaptation pFactory, final String pTableId, final String pSourceColumn) throws OperationException
	{
		final Adaptation column = SourceDrifts.landingColumn(pFactory, pTableId, pSourceColumn);
		if (column == null)
		{
			return "The landing column " + pSourceColumn + " was already gone.";
		}
		final List<Adaptation> mappings = SourceDrifts.mappingsOf(pFactory, column);
		final List<String> removed = new ArrayList<String>();
		SourceDrifts.write(pSession, pFactory, pContext -> {
			for (final Adaptation mapping : mappings)
			{
				removed.add(mapping.getOccurrencePrimaryKey().format() + " (attribute " + mapping.getString(FactoryPaths.AttributeMapping.ATTRIBUTE) + ", "
					+ mapping.getString(FactoryPaths.AttributeMapping.TRANSFORMATION) + ")");
				pContext.doDelete(mapping.getAdaptationName(), false);
			}
			pContext.doDelete(column.getAdaptationName(), false);
		});
		return "Landing column " + pSourceColumn + " retired" + (removed.isEmpty() ? "." : "; mappings removed: " + removed + ".");
	}

	private static void retypeColumn(final Session pSession, final Adaptation pFactory, final String pTableId, final SchemaDrift.Change pChange) throws OperationException
	{
		final Adaptation column = SourceDrifts.landingColumn(pFactory, pTableId, pChange.sourceColumn);
		if (column == null)
		{
			throw EngineRefusal.coded("missing.driftColumn", "The landing column " + pChange.sourceColumn + " no longer exists.", String.valueOf(pChange.sourceColumn));
		}
		SourceDrifts.write(pSession, pFactory, pContext -> {
			final ValueContextForUpdate value = pContext.getContext(column.getAdaptationName());
			value.setValue(pChange.newType, FactoryPaths.LandingColumn.SOURCE_DATA_TYPE);
			final String dataType = pChange.newDataType;
			if (dataType != null && !dataType.trim().isEmpty())
			{
				value.setValue(LandingPlanner.inferredType(dataType), FactoryPaths.LandingColumn.INFERRED_TYPE);
			}
			pContext.doModifyContent(column, value);
		});
	}

	/** Whether a change of the landing table is still open (PROPOSED, ACCEPTED or FAILED). */
	static boolean hasOpen(final Adaptation pFactory, final String pTableId)
	{
		return !SourceDrifts.rows(pFactory, "./landingTable=" + CatalogReader.quote(pTableId) + " and (./status='PROPOSED' or ./status='ACCEPTED' or ./status='FAILED')")
			.isEmpty();
	}

	/** Once no change of the table is open any more, the table goes back to the status it had before the drift. */
	private static void settleTable(final Session pSession, final Adaptation pFactory, final String pTableId, final String pStatusBefore) throws OperationException
	{
		if (pTableId == null || SourceDrifts.hasOpen(pFactory, pTableId))
		{
			return;
		}
		final Adaptation table = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTableId));
		if (table == null || !"DRIFTED".equals(table.getString(FactoryPaths.LandingTable.STATUS)))
		{
			return;
		}
		final String back = "LOADED".equals(pStatusBefore) ? "LOADED" : "GENERATED";
		SourceDrifts.write(pSession, pFactory, pContext -> {
			pContext.setAllPrivileges(true); // F3-11: the decision of a steward (no right on LandingTable) must not fail here, after it was recorded
			final ValueContextForUpdate value = pContext.getContext(table.getAdaptationName());
			value.setValue(back, FactoryPaths.LandingTable.STATUS);
			value.setValue("Every source drift of this table is decided.", FactoryPaths.LandingTable.MESSAGE);
			pContext.doModifyContent(table, value);
		});
	}

	// --------------------------------------------------------------------------------------------------- helpers

	private interface Write
	{
		void run(ProcedureContext pContext) throws Exception;
	}

	private static void write(final Session pSession, final Adaptation pFactory, final Write pWrite) throws OperationException
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pWrite.run(pContext);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.driftWrite", "The source drift change could not be written: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	private static void update(final Session pSession, final Adaptation pFactory, final Adaptation pRow, final String pStatus, final String pDecidedBy,
		final String pAppliedBy, final String pMessage) throws OperationException
	{
		SourceDrifts.write(pSession, pFactory, pContext -> {
			pContext.setAllPrivileges(true); // the Factory's own bookkeeping: the route already checked the right
			final ValueContextForUpdate value = pContext.getContext(pRow.getAdaptationName());
			value.setValue(pStatus, FactoryPaths.SourceDrift.STATUS);
			if (pDecidedBy != null)
			{
				value.setValue(pDecidedBy, FactoryPaths.SourceDrift.DECIDED_BY);
				value.setValue(new Date(), FactoryPaths.SourceDrift.DECIDED_AT);
			}
			if (pAppliedBy != null)
			{
				value.setValue(pAppliedBy, FactoryPaths.SourceDrift.APPLIED_BY);
				value.setValue(new Date(), FactoryPaths.SourceDrift.APPLIED_AT);
			}
			if (pMessage != null)
			{
				value.setValue(TextLimits.truncate(pMessage, 2000), FactoryPaths.SourceDrift.APPLY_MESSAGE);
			}
			pContext.doModifyContent(pRow, value);
		});
	}

	/** The project key of a drift, null when there is no such drift (or no Factory dataset). */
	public static String projectOf(final Adaptation pFactory, final String pId)
	{
		if (pFactory == null || pId == null || pId.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final Adaptation row = pFactory.getTable(FactoryPaths.SOURCE_DRIFT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId.trim()));
			return row == null ? null : row.getString(FactoryPaths.SourceDrift.PROJECT);
		}
		catch (final RuntimeException malformed)
		{
			return null;
		}
	}

	static Adaptation row(final Adaptation pFactory, final String pId) throws OperationException
	{
		final Adaptation row = pId == null || pId.trim().isEmpty() ? null
			: pFactory.getTable(FactoryPaths.SOURCE_DRIFT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId.trim()));
		if (row == null)
		{
			throw EngineRefusal.coded("missing.drift", "No source drift " + pId + ".", String.valueOf(pId));
		}
		return row;
	}

	private static List<Adaptation> rows(final Adaptation pFactory, final String pPredicate)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.SOURCE_DRIFT).createRequestResult(pPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static Adaptation landingColumn(final Adaptation pFactory, final String pTableId, final String pSourceColumn)
	{
		if (pTableId == null || pSourceColumn == null)
		{
			return null;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN)
			.createRequestResult("./landingTable=" + CatalogReader.quote(pTableId) + " and ./sourceColumn=" + CatalogReader.quote(pSourceColumn));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	private static List<Adaptation> mappingsOf(final Adaptation pFactory, final Adaptation pLandingColumn)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING)
			.createRequestResult("./landingColumn=" + CatalogReader.quote(pLandingColumn.getOccurrencePrimaryKey().format()));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String shortText(final String pText)
	{
		return pText == null ? "" : pText.length() > 500 ? pText.substring(0, 500) + "..." : pText;
	}
}
