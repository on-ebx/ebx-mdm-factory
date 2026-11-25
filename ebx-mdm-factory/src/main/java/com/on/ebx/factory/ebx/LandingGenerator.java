package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.PublishDecision;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.LandingModel;
import com.on.ebx.factory.landing.ProgressNote;
import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.landing.LandingModelState;
import com.on.ebx.factory.landing.LandingNames;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.instance.HomeCreationSpec;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Turns the landing plan of one project source ({@code LandingTable}/{@code LandingColumn} rows written by
 * {@link LandingPlanner}, status {@code PROPOSED}) into a real landing zone in EBX: a data model built and
 * published from the planned, selected tables, then a dataspace and dataset to hold it. Every step is
 * recorded in {@code PipelineStep} (status {@code RUNNING} then {@code DONE}/{@code FAILED}, with the
 * request/response of every MCP call as its {@code detail}), via {@link PipelineSteps}, so that a run
 * interrupted or failed partway resumes instead of restarting: a step already {@code DONE} for the same
 * project and source system is skipped.
 * <p>
 * EBX writes ({@code PipelineStep}, {@code ProjectSource}, {@code LandingTable}, {@code Project}) act with
 * the rights of the session given to {@link #generate}. The MCP calls that build and publish the model, and
 * that create the dataset, act as the factory's one agent account ({@link McpServers}) instead: what that
 * account may do on this instance is what generation may do.
 * <p>
 * The publish step checks {@code publish_data_model}'s own {@code published} field ({@link
 * PublishDecision}), not merely the absence of a transport-level error - a gate block (the model does not
 * validate/compile) comes back as an ordinary, non-error response with {@code published:false}, never a
 * throw. Brought in line with {@code StagingGenerator} (which had this right first) in a coordinator
 * follow-up review, 2026-09-20, so both generators share the one check instead of each re-deriving it.
 */
public final class LandingGenerator
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String STEP_LANDING_MODEL = "LANDING_MODEL";
	private static final String STEP_LANDING_DATASET = "LANDING_DATASET";

	private LandingGenerator()
	{
	}

	public static ObjectNode generate(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		return LandingGenerator.generate(pSession, pFactory, pProjectSourceId, ProgressNote.NONE);
	}

	/** Same, saying each phase to {@code pNote} (live-progress lot: the landing run's running screen). */
	public static ObjectNode generate(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final ProgressNote pNote)
		throws OperationException
	{
		final Adaptation source = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		if (source == null)
		{
			throw EngineRefusal.coded("missing.projectSource", "Project source " + pProjectSourceId + " not found", pProjectSourceId);
		}
		final String sourceSystem = source.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		final String projectId = source.getString(FactoryPaths.ProjectSource.PROJECT);
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectId));
		if (project == null)
		{
			throw EngineRefusal.coded("missing.projectOfSource", "Project " + projectId + " of source " + pProjectSourceId + " not found", projectId, pProjectSourceId);
		}
		final String projectCode = project.getString(FactoryPaths.Project.CODE);

		final List<PlannedTable> planned = LandingGenerator.plannedTables(pFactory, pProjectSourceId);
		if (planned.isEmpty())
		{
			throw EngineRefusal.coded("missing.noPlannedLandingTable", "Project source " + pProjectSourceId
				+ " has no selected, planned landing table: run POST /sources/" + pProjectSourceId + "/landing/plan first", pProjectSourceId);
		}
		final List<LandingModel.Table> tables = new ArrayList<LandingModel.Table>();
		for (final PlannedTable table : planned)
		{
			tables.add(table.model);
		}

		final String modelName = LandingNames.modelName(projectCode, sourceSystem);
		final String dataspaceKey = LandingNames.dataspaceKey(projectCode, LandingGenerator.landingSuffix(pFactory));
		final String datasetName = sourceSystem;

		final ObjectNode out = LandingGenerator.MAPPER.createObjectNode();
		out.put("projectSource", pProjectSourceId);
		out.put("sourceSystem", sourceSystem);
		out.put("landingModel", modelName);
		out.put("landingDataspace", dataspaceKey);
		out.put("landingDataset", datasetName);

		try
		{
			out.put("landingModelStep",
				LandingGenerator.runModelStep(pSession, pFactory, projectId, sourceSystem, modelName, tables, pNote) ? "SKIPPED_ALREADY_DONE" : "DONE");
			pNote.note(RunTexts.creatingDataset(datasetName, dataspaceKey).stored());
			out.put("landingDatasetStep",
				LandingGenerator.runDatasetStep(pSession, pFactory, projectId, sourceSystem, modelName, dataspaceKey, datasetName) ? "SKIPPED_ALREADY_DONE" : "DONE");
		}
		catch (final OperationException ex)
		{
			LandingGenerator.failSource(pSession, pFactory, pProjectSourceId, ex.getMessage());
			throw ex;
		}

		LandingGenerator.writeBackSuccess(pSession, pFactory, pProjectSourceId, projectId, modelName, dataspaceKey, datasetName, planned);
		out.put("status", "GENERATED");
		return out;
	}

	// ------------------------------------------------------------------------------------------- LANDING_MODEL

	/** @return true when the step was already DONE (nothing run again) */
	private static boolean runModelStep(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pSourceSystem,
		final String pModelName, final List<LandingModel.Table> pTables, final ProgressNote pNote) throws OperationException
	{
		if (PipelineSteps.isDone(pFactory, pProjectId, LandingGenerator.STEP_LANDING_MODEL, pSourceSystem))
		{
			pNote.note(RunTexts.modelReused(pModelName).stored());
			return true;
		}
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, LandingGenerator.STEP_LANDING_MODEL, pSourceSystem);
		final ObjectNode detail = LandingGenerator.MAPPER.createObjectNode();
		try
		{
			final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);

			// resumability: build_data_model refuses a name that already exists, so a run resumed after a
			// partial failure (model built, then publish failed or the process died) must not blindly build
			// again - find out first, read-only, what the server already has under this name.
			final ObjectNode getModelArgs = LandingGenerator.MAPPER.createObjectNode();
			getModelArgs.put("data_model_name", pModelName);
			final McpClient.ToolResult modelLookup = client.callTool("get_data_model", getModelArgs);
			detail.set("existingModel", LandingGenerator.toolDetail(getModelArgs, modelLookup));
			final boolean modelExists = modelLookup.json != null && modelLookup.json.path("found").asBoolean(false);

			final List<String> existingTablePaths = new ArrayList<String>();
			if (modelExists && modelLookup.json.path("tables").isArray())
			{
				for (final JsonNode t : modelLookup.json.path("tables"))
				{
					existingTablePaths.add(t.path("path").asText());
				}
			}

			LandingModelState.Found found = LandingModelState.Found.NOTHING;
			if (modelExists)
			{
				final ObjectNode getPubArgs = LandingGenerator.MAPPER.createObjectNode();
				getPubArgs.put("publication_name", pModelName);
				final McpClient.ToolResult pubLookup = client.callTool("get_publication", getPubArgs);
				detail.set("existingPublication", LandingGenerator.toolDetail(getPubArgs, pubLookup));
				final boolean published = pubLookup.json != null && pubLookup.json.path("found").asBoolean(false) && pubLookup.json.hasNonNull("version");
				found = published ? LandingModelState.Found.MODEL_AND_PUBLICATION : LandingModelState.Found.MODEL_ONLY;
			}

			final List<String> plannedTablePaths = new ArrayList<String>();
			for (final LandingModel.Table table : pTables)
			{
				plannedTablePaths.add(LandingModel.tablePath(table.tableName));
			}

			final LandingModelState.Decision decision = LandingModelState.decide(found, pModelName, pSourceSystem, plannedTablePaths, existingTablePaths);
			final ObjectNode stateDetail = detail.putObject("state");
			stateDetail.put("found", found.name());
			stateDetail.put("build", decision.build);
			stateDetail.put("publish", decision.publish);
			if (!decision.ok())
			{
				throw EngineRefusal.coded("conflict.regenerateRefused", decision.refusal);
			}

			if (decision.build)
			{
				final String treeJson = LandingModel.tree(pTables).toString();
				final int fields = LandingGenerator.fieldCount(pTables);
				pNote.note(RunTexts.creatingModel(pModelName, pTables.size(), fields).stored());
				// one build_data_model call, its own answer is the gate (wave 2 lot V2-C, ModelBuilder): no rehearsal first
				ModelBuilder.build(pSession, pFactory, client, "landing", pSourceSystem, pModelName, treeJson, detail);
			}

			if (decision.publish)
			{
				LandingGenerator.ensureEmbedded(client, pModelName, detail);

				// publish_data_model's own answer is the gate, not the absence of a transport error: a gate block
				// (validate/compile refused) is a normal, non-error response with published:false - see
				// PublishDecision's own Javadoc for the exact contract lines (shared with StagingGenerator, which
				// had this right first - a coordinator follow-up review, 2026-09-20, brought this class in line)
				final ObjectNode publishArgs = LandingGenerator.MAPPER.createObjectNode();
				publishArgs.put("data_model_name", pModelName);
				pNote.note(RunTexts.publishingModel(pModelName).stored());
				final McpClient.ToolResult publish = client.callTool("publish_data_model", publishArgs);
				detail.set("publish", LandingGenerator.toolDetail(publishArgs, publish));
				if (publish.error)
				{
					throw EngineRefusal.coded("external.publishModel", "publish_data_model could not publish " + pModelName + ": " + LandingGenerator.shortText(publish.text), pModelName);
				}
				if (!PublishDecision.published(publish.json))
				{
					throw EngineRefusal.coded("invalid.modelNotValid", "publish_data_model refused to publish " + pModelName + " (it does not validate/compile): " + LandingGenerator.shortText(publish.text), pModelName);
				}
			}

			final String message = decision.build ? "Landing model built and published."
				: decision.publish ? "Landing model already existed with the planned tables; published." : "Landing model already built and published; nothing to do.";
			PipelineSteps.end(pSession, pFactory, stepId, true, message, detail.toString());
			return false;
		}
		catch (final McpException ex)
		{
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.codedWithCause("external.mcpBuildModel", "MCP call failed while building the landing model of " + pSourceSystem + ": " + ex.getMessage(), ex, pSourceSystem);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
			throw ex;
		}
	}

	/**
	 * A model {@code build_data_model} creates is not packaged inside any module (no {@code pathInModule}),
	 * so it should already read {@code publicationMode: EMBEDDED} - unlike a model brought in by
	 * {@code import_data_model_from_module}, which defaults to {@code IN_MODULE} (see the knowledge base).
	 * Checked and fixed anyway, defensively: publishing an {@code IN_MODULE} model rewrites a module file
	 * this generator does not own.
	 */
	private static void ensureEmbedded(final McpClient pClient, final String pModelName, final ObjectNode pDetail) throws McpException, OperationException
	{
		final ObjectNode getArgs = LandingGenerator.MAPPER.createObjectNode();
		getArgs.put("data_model_name", pModelName);
		getArgs.put("operation", "get_global_properties");
		final McpClient.ToolResult props = pClient.callTool("execute_query", getArgs);
		pDetail.set("globalProperties", LandingGenerator.toolDetail(getArgs, props));
		final String mode = props.json == null ? null : props.json.path("configuration").path("publicationMode").asText(null);
		if ("EMBEDDED".equals(mode))
		{
			return;
		}
		final ObjectNode setArgs = LandingGenerator.MAPPER.createObjectNode();
		setArgs.put("data_model_name", pModelName);
		setArgs.put("operation", "set_global_properties");
		final ObjectNode moduleProperties = LandingGenerator.MAPPER.createObjectNode();
		moduleProperties.put("publicationMode", "EMBEDDED");
		final ObjectNode operationArgs = LandingGenerator.MAPPER.createObjectNode();
		operationArgs.set("module_properties", moduleProperties);
		setArgs.put("arguments_json", operationArgs.toString());
		final McpClient.ToolResult set = pClient.callTool("execute_operation", setArgs);
		pDetail.set("publicationMode", LandingGenerator.toolDetail(setArgs, set));
		if (set.error)
		{
			throw EngineRefusal.coded("external.publicationMode", "Could not set the publication mode of " + pModelName + " to EMBEDDED: " + LandingGenerator.shortText(set.text), pModelName);
		}
	}

	// ----------------------------------------------------------------------------------------- LANDING_DATASET

	/** @return true when the step was already DONE (nothing run again) */
	private static boolean runDatasetStep(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pSourceSystem,
		final String pModelName, final String pDataspaceKey, final String pDatasetName) throws OperationException
	{
		if (PipelineSteps.isDone(pFactory, pProjectId, LandingGenerator.STEP_LANDING_DATASET, pSourceSystem))
		{
			return true;
		}
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, LandingGenerator.STEP_LANDING_DATASET, pSourceSystem);
		final ObjectNode detail = LandingGenerator.MAPPER.createObjectNode();
		// 1.4.2 (X3-11): true once THIS step created the zone, so that an empty refusal of create_dataset can remove it again (ZoneDatasetDenial)
		boolean zoneCreatedHere = false;
		try
		{
			final Repository repository = pFactory.getHome().getRepository();
			AdaptationHome landingHome = repository.lookupHome(HomeKey.forBranchName(pDataspaceKey));
			final ObjectNode dataspaceDetail = detail.putObject("dataspace");
			dataspaceDetail.put("key", pDataspaceKey);
			if (landingHome == null)
			{
				final HomeCreationSpec spec = new HomeCreationSpec();
				spec.setKey(HomeKey.forBranchName(pDataspaceKey));
				final AdaptationHome parentHome = repository.getReferenceBranch();
				spec.setParent(parentHome);
				spec.setOwner(Profile.ADMINISTRATOR);
				// 1.4.3 (X3-11): the permissions of the parent dataspace by default, or of the dataspace named by -Debx.factory.zonePermissionsFrom, or none (ZonePermissions)
				final String permissionsFrom = FactoryAccess.copyZonePermissions(spec, repository, parentHome);
				dataspaceDetail.put("permissionsCopiedFrom", permissionsFrom == null ? "none" : permissionsFrom);
				spec.setLabel(UserMessage.createInfo(pDataspaceKey));
				spec.setDescription(UserMessage.createInfo("Landing zone of project " + pProjectId + ": one dataset per source system."));
				landingHome = repository.createHome(spec, pSession);
				zoneCreatedHere = true;
				dataspaceDetail.put("created", true);
			}
			else
			{
				dataspaceDetail.put("created", false);
			}

			final Adaptation existingDataset = landingHome.findAdaptationOrNull(AdaptationName.forName(pDatasetName));
			final ObjectNode datasetDetail = detail.putObject("dataset");
			datasetDetail.put("name", pDatasetName);
			if (existingDataset == null)
			{
				final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
				final ObjectNode createArgs = LandingGenerator.MAPPER.createObjectNode();
				createArgs.put("data_model_name", pModelName);
				createArgs.put("operation", "create_dataset");
				final ObjectNode operationArgs = LandingGenerator.MAPPER.createObjectNode();
				operationArgs.put("publication_name", pModelName);
				operationArgs.put("dataset_name", pDatasetName);
				operationArgs.put("dataspace", pDataspaceKey);
				createArgs.put("arguments_json", operationArgs.toString());
				final McpClient.ToolResult create = client.callTool("execute_operation", createArgs);
				datasetDetail.set("call", LandingGenerator.toolDetail(createArgs, create));
				if (create.error)
				{
					if (ZoneDatasetDenial.emptyToolFailure(create.text))
					{
						throw ZoneDatasetDenial.translate(pSession, pFactory, pDataspaceKey, zoneCreatedHere);
					}
					throw EngineRefusal.coded("external.createDataset", "create_dataset could not create dataset " + pDatasetName + " in " + pDataspaceKey + ": " + LandingGenerator.shortText(create.text), pDatasetName, pDataspaceKey);
				}
				datasetDetail.put("skipped", false);
			}
			else
			{
				datasetDetail.put("skipped", true);
			}

			PipelineSteps.end(pSession, pFactory, stepId, true, "Landing dataspace and dataset ready.", detail.toString());
			return false;
		}
		catch (final McpException ex)
		{
			if (ZoneDatasetDenial.emptyToolFailure(ex.getMessage()))
			{
				final OperationException denied = ZoneDatasetDenial.translate(pSession, pFactory, pDataspaceKey, zoneCreatedHere);
				detail.put("transportError", ex.getMessage());
				PipelineSteps.end(pSession, pFactory, stepId, false, denied.getMessage(), detail.toString());
				throw denied;
			}
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.codedWithCause("external.mcpCreateDataset", "MCP call failed while creating the landing dataset of " + pSourceSystem + ": " + ex.getMessage(), ex);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
			throw ex;
		}
	}

	// ------------------------------------------------------------------------------------------------- write-back

	/** On failure: the project source turns FAILED with the reason, in its own procedure, and generation stops. */
	private static void failSource(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final String pMessage)
	{
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final String message = TextLimits.truncate(pMessage, TextLimits.MESSAGE_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation source = sources.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
				if (source == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(source.getAdaptationName());
				value.setValue("FAILED", FactoryPaths.ProjectSource.STATUS);
				value.setValue(message, FactoryPaths.ProjectSource.MESSAGE);
				pContext.doModifyContent(source, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not record the failure of project source " + pProjectSourceId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/** Every step succeeded (or was already done): the project source, its landing tables and the project itself say so. */
	private static void writeBackSuccess(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final String pProjectId,
		final String pModelName, final String pDataspaceKey, final String pDatasetName, final List<PlannedTable> pTables) throws OperationException
	{
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		final AdaptationTable landingTables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation source = sources.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
				if (source != null)
				{
					final ValueContextForUpdate value = pContext.getContext(source.getAdaptationName());
					value.setValue(pModelName, FactoryPaths.ProjectSource.LANDING_MODEL);
					value.setValue(pModelName, FactoryPaths.ProjectSource.LANDING_PUBLICATION);
					value.setValue(pDatasetName, FactoryPaths.ProjectSource.LANDING_DATASET);
					value.setValue("GENERATED", FactoryPaths.ProjectSource.STATUS);
					pContext.doModifyContent(source, value);
				}
				final Adaptation project = projects.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
				if (project != null)
				{
					final ValueContextForUpdate value = pContext.getContext(project.getAdaptationName());
					value.setValue(pDataspaceKey, FactoryPaths.Project.LANDING_DATASPACE);
					pContext.doModifyContent(project, value);
				}
				for (final PlannedTable table : pTables)
				{
					final Adaptation row = landingTables.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(table.id));
					if (row != null)
					{
						final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
						value.setValue("GENERATED", FactoryPaths.LandingTable.STATUS);
						pContext.doModifyContent(row, value);
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.writeBackLanding", "The landing of " + pProjectSourceId + " was generated, but writing it back failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pProjectSourceId);
		}
	}

	// ------------------------------------------------------------------------------------------------------- plan

	/** One planned, selected {@code LandingTable} row, with its columns already built into a {@link LandingModel.Table}. */
	private static final class PlannedTable
	{
		final String id;
		final LandingModel.Table model;

		PlannedTable(final String pId, final LandingModel.Table pModel)
		{
			this.id = pId;
			this.model = pModel;
		}
	}

	/** The selected, planned tables of one project source, columns in position order, tables in id order. */
	private static List<PlannedTable> plannedTables(final Adaptation pFactory, final String pProjectSourceId)
	{
		final List<PlannedTable> planned = new ArrayList<PlannedTable>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (Boolean.FALSE.equals(row.get(FactoryPaths.LandingTable.SELECTED)))
				{
					continue;
				}
				final String id = row.getOccurrencePrimaryKey().format();
				final LandingModel.Table table = LandingModel.namedTable(row.getString(FactoryPaths.LandingTable.SOURCE_OBJECT), row.getString(FactoryPaths.LandingTable.TABLE_NAME));
				for (final Adaptation column : LandingGenerator.orderedColumns(pFactory, id))
				{
					table.columns.add(LandingModel.namedColumn(column.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN), column.getString(FactoryPaths.LandingColumn.FIELD_NAME)));
				}
				planned.add(new PlannedTable(id, table));
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(planned, new Comparator<PlannedTable>()
		{
			@Override
			public int compare(final PlannedTable pLeft, final PlannedTable pRight)
			{
				return Integer.compare(Integer.parseInt(pLeft.id), Integer.parseInt(pRight.id));
			}
		});
		return planned;
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

	/** Package-visible (not {@code private}) since the project-deletion lot: {@code ebx.ProjectDeleter} needs the exact same computed dataspace key to recognise a factory-generated landing zone. */
	static String landingSuffix(final Adaptation pFactory) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing");
		}
		return settings.getString(FactoryPaths.Settings.LANDING_SUFFIX);
	}

	// ------------------------------------------------------------------------------------------------------- detail

	/** Fields the model build creates: every column plus the six technical ones {@link LandingModel#tree} adds to each table. */
	private static int fieldCount(final List<LandingModel.Table> pTables)
	{
		int n = 0;
		for (final LandingModel.Table table : pTables)
		{
			n += table.columns.size() + 6;
		}
		return n;
	}

	private static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = LandingGenerator.MAPPER.createObjectNode();
		node.set("arguments", pArguments);
		node.put("error", pResult.error);
		if (pResult.json != null)
		{
			node.set("result", pResult.json);
		}
		else
		{
			node.put("resultText", pResult.text);
		}
		return node;
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
