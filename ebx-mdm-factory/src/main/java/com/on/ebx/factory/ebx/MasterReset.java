package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.master.MameMetadataset;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.master.MasterResetEligibility;
import com.on.ebx.factory.staging.StagingModel;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The recovery path for `docs/MASTER-ZONE.md`'s 2026-09-21 incident: a GREENFIELD master zone generated with
 * a wrong entity set ({@code Company}, {@code Supplier}, {@code Account} all promoted, instead of just {@code
 * Company}) can never be fixed by regeneration alone - dropping a table is NON-ADDITIVE, so {@code
 * master.MasterModelState}'s own regeneration path correctly refuses to remove {@code Supplier}/{@code
 * Account} once they exist. This class closes and deletes the whole master zone instead, so a corrected
 * {@code master/generate} can build it again from scratch.
 * <p>
 * <b>Eligibility is entirely decided by {@link MasterResetEligibility#decide}</b> (pure, unit-tested) - this
 * class only gathers the live evidence it needs (the project's own fields, {@code PipelineStep} history, the
 * matching metadataset's existence) and acts once it says {@code allowed}. See that class' own Javadoc for
 * the full guard list; the short version: GREENFIELD only, the dataspace/dataset names must be exactly what
 * this factory would itself generate (never a foreign value like the demo project's hand-typed {@code
 * TestMasterDataspace}), there must be real {@code PipelineStep} evidence the factory actually created the
 * dataspace, the add-on's own matching metadataset must not exist yet, and the caller must pass {@code
 * confirm} equal to the master dataspace key exactly.
 * <p>
 * <b>What actually gets deleted, and how - verified read-only, not invented:</b>
 * <ul>
 * <li>The dataspace: {@code Repository.closeHome(AdaptationHome, Session)} then {@code
 * Repository.deleteHome(AdaptationHome, Session)} - both real, public, abstract methods on {@code
 * com.orchestranetworks.instance.Repository}, confirmed by {@code javap} on {@code ebx-lib.jar} 2026-09-21 (no
 * Javadoc text available that way, only the signatures). Called directly with the session, the same way {@code
 * ebx.MasterGenerator.runDatasetStep} already calls {@code Repository.createHome} outside any {@code
 * Procedure} - a repository-level lifecycle operation, not a record write. <b>Unverified beyond the
 * signatures</b>: whether {@code deleteHome} requires the home to already be closed (defensively closed first
 * here, only if {@link AdaptationHome#isOpen()}) and whether it deletes immediately rather than requiring a
 * separate {@code purgeHomes} - inferred from the Data Services REST route's own documented behaviour
 * (CLAUDE.md §6: {@code POST .../{dataspace}:close} with {@code deleteHistoryOnClose=true&deleteDataOnClose=true}
 * deletes a dataspace in one call, {@code 204}), never independently confirmed by running it.</li>
 * <li>The DMA data model: {@code execute_operation(operation="delete_data_model", arguments_json={"confirm":
 * "true"}})} on the {@code data-model} MCP server - found live, read-only, via {@code list_operations(name=
 * "delete_data_model")}: <i>"Delete the whole data model. Requires confirm=true."</i>; its own contract text,
 * quoted in full: <i>"Deleting the model does NOT delete any of [the datasets built on it] - they keep
 * working, it only orphans them - but the source they can be evolved and republished from is gone."</i> - the
 * dataspace/dataset deletion above and this model deletion are therefore two genuinely independent cleanups,
 * both needed for a clean reset; this call is soft-failed (recorded in the response, the reset step still
 * ends DONE) rather than aborting the whole reset over it, since by the time it runs the dataspace is already
 * gone and retrying the whole step would just try to re-delete something that no longer exists.</li>
 * </ul>
 * <p>
 * <b>Bookkeeping, one procedure on the factory's own dataset</b>: {@code Project.masterDataspace}/{@code
 * masterDataset} cleared (so the next {@code master/generate} sees a blank master zone, no {@code
 * overwriteTarget} needed); every {@code Xref} row of the project with a {@code masterTable}/{@code masterKey}
 * cleared of exactly those two fields plus {@code goldenKey} (the staging/landing provenance fields are left
 * untouched - a reset does not undo the staging transfer, only the master one); every {@code PipelineStep} row
 * of the project for {@code MASTER_MODEL}/{@code MASTER_DATASET}/{@code MASTER_LOAD} currently {@code DONE}
 * moved to {@code RESET} (never deleted - history stays, but {@code PipelineSteps.isDone} must once again treat
 * them as not done, exactly like a fresh project); and this call's own {@code PipelineStep MASTER_RESET}.
 * <p>
 * <b>This reset does NOT, and cannot, delete the DMA publication object itself - MEASURED LIVE, 2026-09-22
 * (`docs/MASTER-ZONE.md` &sect;10).</b> {@code delete_data_model} (above) removes the MODEL; the separate
 * publication row the model was published under survives it and re-attaches itself to the next model built
 * under the exact same name (this class always regenerates under the SAME computed name -
 * {@code MasterNames.modelName} - so a project reset then regenerated hits this every time). The
 * {@code data-model} MCP server's own operation catalogue has no delete-publication operation at all -
 * {@code list_data_model_publications}/{@code set_publication_documentation}/{@code create_dataset}/versions/
 * export/{@code delete_data_model} is the complete list, read live the same session - so there is no call this
 * class could add to close that gap even if it tried. The consequence lands one level up, in
 * {@code ebx.MasterGenerator}: a publication the server reports {@code found} for a freshly-reset-and-rebuilt
 * model is not, on its own, proof that the CURRENT generation is the one that published it - see that class'
 * own {@code runModelStep} comment for the fix (the factory's own {@code PipelineStep} bookkeeping, not the
 * server's publication lookup, decides {@code MasterModelState.Found.MODEL_AND_PUBLICATION} vs.
 * {@code MODEL_ONLY}).
 */
public final class MasterReset
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String STEP = "MASTER_RESET";
	private static final String SCOPE = "";
	private static final String[] STEPS_TO_INVALIDATE = { "MASTER_MODEL", "MASTER_DATASET", "MASTER_LOAD" };

	private MasterReset()
	{
	}

	/** Resets the master zone, holding every resource of the project so that no job can run meanwhile. */
	public static ObjectNode reset(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pConfirm) throws OperationException
	{
		final Adaptation project = MasterReset.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		return FactoryJobQueue.exclusively(pFactory, project.getOccurrencePrimaryKey().format(), "MASTER_RESET", pSession,
			new Actor.Work<ObjectNode, OperationException>()
			{
				@Override
				public ObjectNode run() throws OperationException
				{
					return MasterReset.resetHoldingProject(pSession, pFactory, pProjectCode, pConfirm);
				}
			});
	}

	// volume-operations lot 8a (2026-09-28, task 2): package-visible - see StepResetter.resetHoldingProject's
	// own comment, same reasoning, for MasterResetRun's job handler.
	static ObjectNode resetHoldingProject(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pConfirm)
		throws OperationException
	{
		final Adaptation project = MasterReset.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String mode = project.getString(FactoryPaths.Project.MODE);
		final String existingDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String existingDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		final String computedDataspaceKey = MasterNames.dataspaceKey(pProjectCode, MasterGenerator.masterSuffix(pFactory));
		final String computedDatasetName = MasterNames.DATASET;
		final String modelName = MasterNames.modelName(pProjectCode);

		final boolean masterModelStepEverDone = MasterReset.stepEverDone(pFactory, projectId, "MASTER_MODEL");
		final boolean masterDatasetCreatedByFactory = MasterReset.masterDatasetCreatedByFactory(pFactory, projectId);
		final boolean metadatasetExists = MasterReset.metadatasetExists(pFactory, projectId, existingDataspaceKey, existingDatasetName);

		final MasterResetEligibility.Decision decision = MasterResetEligibility.decide(pProjectCode, mode, existingDataspaceKey, existingDatasetName,
			computedDataspaceKey, computedDatasetName, masterModelStepEverDone, masterDatasetCreatedByFactory, metadatasetExists, pConfirm);
		if (!decision.allowed)
		{
			throw EngineRefusal.coded("conflict.refused", decision.refusal);
		}

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, MasterReset.STEP, MasterReset.SCOPE);
		final ObjectNode detail = MasterReset.MAPPER.createObjectNode();
		final ObjectNode out = MasterReset.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("masterDataspace", existingDataspaceKey);
		out.put("masterDataset", existingDatasetName);
		try
		{
			// 1. the dataspace - a repository-level operation, called directly with the session, never inside a
			// Procedure (the same convention MasterGenerator.runDatasetStep already uses for createHome). Any
			// failure here aborts the whole reset: nothing below is safe to do if the dataspace is still there.
			final Repository repository = pFactory.getHome().getRepository();
			final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(existingDataspaceKey));
			if (masterHome != null)
			{
				try
				{
					if (masterHome.isOpen())
					{
						repository.closeHome(masterHome, pSession);
					}
					repository.deleteHome(masterHome, pSession);
					out.put("dataspaceDeleted", true);
				}
				catch (final OperationException ex)
				{
					throw EngineRefusal.codedWithCause("external.closeMasterDataspace", "Could not close/delete master dataspace " + existingDataspaceKey + ": " + ex.getMessage(), ex, existingDataspaceKey);
				}
			}
			else
			{
				out.put("dataspaceDeleted", false);
				detail.put("dataspaceNote", "dataspace " + existingDataspaceKey + " was not found - nothing to delete there");
			}

			// 2. the DMA model - soft-failed, see the class Javadoc for why
			boolean modelDeleted = false;
			String modelDeleteError = null;
			try
			{
				final McpClient client = McpServers.client(pSession, pFactory, McpServers.DATA_MODEL);
				final ObjectNode args = MasterReset.MAPPER.createObjectNode();
				args.put("data_model_name", modelName);
				args.put("operation", "delete_data_model");
				final ObjectNode operationArgs = MasterReset.MAPPER.createObjectNode();
				operationArgs.put("confirm", "true");
				args.put("arguments_json", operationArgs.toString());
				final McpClient.ToolResult deleteModel = client.callTool("execute_operation", args);
				detail.set("deleteDataModel", MasterReset.toolDetail(args, deleteModel));
				modelDeleted = !deleteModel.error;
				if (deleteModel.error)
				{
					modelDeleteError = MasterReset.shortText(deleteModel.text);
				}
			}
			catch (final McpException ex)
			{
				modelDeleteError = ex.getMessage();
				detail.put("deleteDataModelTransportError", ex.getMessage());
			}
			out.put("modelDeleted", modelDeleted);
			if (modelDeleteError != null)
			{
				out.put("modelDeleteError", modelDeleteError);
			}

			// 3a. Xref: volume lot 2a (2026-09-28, docs/VOLUME.md, task 6) - this used to be gathered/rewritten
			// inside the SAME single Procedure as everything else, one row at a time, for the WHOLE project (a
			// genuinely volume-scale table, one row per staged/master record). VolumeBatchOps streams only
			// primary-key strings (never a List<Adaptation>) and commits in batches of its own BATCH_SIZE, each
			// its own Procedure - a huge project's reset commits incrementally. The predicate itself now filters
			// to rows that actually have a masterTable set (the Java `if (masterTable blank) continue` guard is
			// gone - never scan a row this pass would do nothing to).
			final AdaptationTable xrefTable = pFactory.getTable(FactoryPaths.XREF);
			final int[] xrefCleared = new int[1];
			VolumeBatchOps.forEachBatched(pSession, pFactory.getHome(), xrefTable,
				"./project=" + CatalogReader.quote(projectId) + " and osd:is-not-null(./masterTable)",
				"Master reset of project " + pProjectCode + ": clearing Xref rows failed", new VolumeBatchOps.RowOp()
				{
					@Override
					public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
					{
						final ValueContextForUpdate xrefValue = pContext.getContext(pRow.getAdaptationName());
						xrefValue.setValue(null, FactoryPaths.Xref.MASTER_TABLE);
						xrefValue.setValue(null, FactoryPaths.Xref.MASTER_KEY);
						xrefValue.setValue(null, FactoryPaths.Xref.GOLDEN_KEY);
						pContext.doModifyContent(pRow, xrefValue);
						xrefCleared[0]++;
					}
				});

			// 3b. bookkeeping - one small procedure (bounded by model size, not data volume): clear Project's
			// pointer fields, mark the invalidated PipelineStep rows RESET.
			final int[] stepsReset = new int[1];
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					final ValueContextForUpdate projectValue = pContext.getContext(project.getAdaptationName());
					projectValue.setValue(null, FactoryPaths.Project.MASTER_DATASPACE);
					projectValue.setValue(null, FactoryPaths.Project.MASTER_DATASET);
					pContext.doModifyContent(project, projectValue);

					final AdaptationTable stepsTable = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
					for (final String stepName : MasterReset.STEPS_TO_INVALIDATE)
					{
						final RequestResult stepRows = stepsTable.createRequestResult(
							"./project=" + CatalogReader.quote(projectId) + " and ./step=" + CatalogReader.quote(stepName) + " and ./status=" + CatalogReader.quote("DONE"));
						try
						{
							for (Adaptation stepRow = stepRows.nextAdaptation(); stepRow != null; stepRow = stepRows.nextAdaptation())
							{
								final ValueContextForUpdate stepValue = pContext.getContext(stepRow.getAdaptationName());
								stepValue.setValue("RESET", FactoryPaths.PipelineStep.STATUS);
								pContext.doModifyContent(stepRow, stepValue);
								stepsReset[0]++;
							}
						}
						finally
						{
							stepRows.close();
						}
					}
				}
			});
			if (result.hasFailed())
			{
				throw EngineRefusal.coded("unexpected.masterResetBookkeeping", "Master reset of project " + pProjectCode + ": the dataspace and model were removed, but the bookkeeping "
					+ "write-back failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pProjectCode);
			}
			out.put("xrefCleared", xrefCleared[0]);
			out.put("stepsReset", stepsReset[0]);

			final String message = "Master zone of " + pProjectCode + " reset: dataspace " + (out.path("dataspaceDeleted").asBoolean() ? "deleted" : "not found")
				+ ", model " + (modelDeleted ? "deleted" : "NOT deleted (" + modelDeleteError + ")") + ", " + xrefCleared[0] + " Xref row(s) cleared, " + stepsReset[0]
				+ " step(s) reset.";
			PipelineSteps.end(pSession, pFactory, stepId, true, message, detail.toString());
			out.put("status", "RESET");
			// the "Record lineage" record-level action's own activation cache (coordinator review, 2026-09-22):
			// the reset master zone's tables must stop being offered without waiting out MasterZoneIndex's TTL.
			MasterZoneIndex.invalidate();
			return out;
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ex.getMessage(), detail.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
			throw ex;
		}
	}

	// ============================================================================================== evidence reads

	private static boolean stepEverDone(final Adaptation pFactory, final String pProjectId, final String pStep)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(pStep) + " and ./status=" + CatalogReader.quote("DONE"));
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/** True when at least one DONE {@code MASTER_DATASET} step of the project recorded {@code dataspace.created == true} in its own detail JSON - the exact shape {@code ebx.MasterGenerator.runDatasetStep} writes. */
	private static boolean masterDatasetCreatedByFactory(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote("MASTER_DATASET") + " and ./status=" + CatalogReader.quote("DONE"));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String detailJson = row.getString(FactoryPaths.PipelineStep.DETAIL);
				if (detailJson == null || detailJson.trim().isEmpty())
				{
					continue;
				}
				try
				{
					final JsonNode detail = MasterReset.MAPPER.readTree(detailJson);
					if (detail.path("dataspace").path("created").asBoolean(false))
					{
						return true;
					}
				}
				catch (final java.io.IOException ex)
				{
					// a detail blob that is not valid JSON (should not happen, MasterGenerator always writes one) -
					// never treated as evidence of anything
				}
			}
			return false;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Whether the add-on's Match and Merge metadataset already exists for ANY of the project's promoted master
	 * tables - naming convention {@link MameMetadataset} (one per TABLE, {@code
	 * <masterDatasetName>_<tableLastStep>_MDS} in the master dataspace, MEASURED LIVE 2026-09-22; the OLDER
	 * {@code <masterDataspaceKey>_<masterDatasetName>_MDS} this method built by hand until that fix never
	 * matched a real metadataset name, so this guard could never actually trigger - see
	 * {@code ebx.RecordLineageReader}'s own Javadoc for the full story). Checked across EVERY promoted table,
	 * not just one: a project with several promoted entities (e.g. {@code Company} and {@code Contact}) has one
	 * metadataset per table, and matching could have been activated on any of them independently.
	 */
	private static boolean metadatasetExists(final Adaptation pFactory, final String pProjectId, final String pMasterDataspaceKey, final String pMasterDatasetName)
	{
		if (pMasterDataspaceKey == null || pMasterDataspaceKey.trim().isEmpty() || pMasterDatasetName == null || pMasterDatasetName.trim().isEmpty())
		{
			return false;
		}
		try
		{
			final Repository repository = pFactory.getHome().getRepository();
			final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(pMasterDataspaceKey));
			if (home == null)
			{
				return false;
			}
			for (final String tablePath : MasterReset.promotedMasterTablePaths(pFactory, pProjectId))
			{
				final String metadatasetName = MameMetadataset.name(pMasterDatasetName, tablePath);
				if (metadatasetName != null && home.findAdaptationOrNull(AdaptationName.forName(metadatasetName)) != null)
				{
					return true;
				}
			}
			return false;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/**
	 * Every promoted (master-table) entity of the project, as {@code MasterModel.tablePath} table paths - the
	 * same set {@code ebx.MasterLoader} would build/load tables for, recomputed read-only from staging exactly
	 * the way that loader does ({@code MasterLoader.readEntities} + {@code MasterModel.buildFromStaging}), so
	 * this guard never drifts from what a real master load would actually create.
	 */
	private static List<String> promotedMasterTablePaths(final Adaptation pFactory, final String pProjectId)
	{
		final Map<String, String> entityIdByName = new LinkedHashMap<String, String>();
		final List<StagingModel.EntityInfo> stagingEntities = MasterLoader.readEntities(pFactory, pProjectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntities);
		final List<String> tablePaths = new ArrayList<String>();
		for (final String name : masterModel.builtEntities)
		{
			tablePaths.add(MasterModel.tablePath(name));
		}
		return tablePaths;
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

	// ================================================================================================================ misc

	private static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = MasterReset.MAPPER.createObjectNode();
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
