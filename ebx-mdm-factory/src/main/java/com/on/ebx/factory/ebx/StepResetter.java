package com.on.ebx.factory.ebx;

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
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.LandingNames;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.project.ProjectDeletionPlan;
import com.on.ebx.factory.project.ProjectDeletionPlan.MatchingFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.SourceFacts;
import com.on.ebx.factory.project.StepResetPlan;
import com.on.ebx.factory.project.StepResetPlan.Disposition;
import com.on.ebx.factory.project.StepResetPlan.Item;
import com.on.ebx.factory.project.StepResetPlan.Plan;
import com.on.ebx.factory.project.StepResetPlan.Stage;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.StagingNames;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Cleans up a project's pipeline FROM a given {@link Stage} onward, without touching the project itself or
 * its chosen sources - the counterpart to {@code ProjectDeleter} for the narrower "start over from here"
 * request (docs/STEP-RESET.md). {@link StepResetPlan} (pure, unit-tested) decides WHAT is in scope and
 * WHETHER the call may proceed; this class gathers the live evidence, executes every {@link
 * StepResetPlan.Disposition#DELETE} item through {@link GeneratedObjectDeleter} (the exact same mechanics
 * {@code ProjectDeleter} uses, never a second implementation), stopping BEFORE touching a single row if any
 * of them fails - then, only once every one succeeded, runs ONE {@link Procedure}: deletes the in-scope rows
 * (children before parents), clears the project/entity fields that memorize the reset zones, and moves every
 * in-scope {@code PipelineStep} name back to {@code RESET}.
 * <p>
 * <b>Corrections to the original brief, made while verifying every affected class - see
 * docs/STEP-RESET.md for the full reasoning:</b>
 * <ul>
 * <li>{@code Xref} rows are never DELETED (the brief's "lignes MatchPlan, Xref" for the {@code MATCHING}
 * stage read literally). Deleting them would destroy the landing/staging provenance of every record, matching
 * or not. Instead: a {@code MATCHING}-only reset clears {@code Xref.goldenKey} alone (the master record and
 * its provenance survive); a reset that also reaches {@code MASTER} clears {@code masterTable}/{@code
 * masterKey}/{@code goldenKey} together, exactly the three fields {@code ebx.MasterReset} already clears for
 * the same reason (the master record itself is gone). Both are project-wide, not filtered to the entities
 * being reset - the same scope {@code ebx.MasterReset} already uses.</li>
 * <li>{@code CAPABILITY_APPLY}/{@code CAPABILITY_REMOVE} {@code PipelineStep} rows are scoped by the acted-on
 * {@code CapabilitySuggestion} row's OWN id ({@code ebx.CapabilityApplier}/{@code DerivationApplier}: {@code
 * scope = suggestionId}), never a literal zone string - resolved here by first reading the in-scope {@code
 * CapabilitySuggestion} ids (by {@code zone} for a {@code MASTER}-only reset, every id of the project for a
 * {@code STAGING}-or-deeper reset, where every suggestion row is deleted anyway) and only then matching {@code
 * PipelineStep} rows on those scopes.</li>
 * <li>{@code MATCHING_*}/{@code GOLDEN_KEY_REFRESH} steps are scoped by ENTITY NAME (one row per promoted
 * entity), so they are reset per entity from {@link ProjectDeletionPlan.Facts#matching}, not project-wide.</li>
 * </ul>
 */
public final class StepResetter
{
	// Lot F3 (2026-10-19): no distribution-trigger handling is needed anywhere in this class. The master model's distribution triggers (DistributionTriggerBody) reference no Match and
	// Merge field any more, so deleting a table's matching configuration here (the MATCHING_CONFIG item) can no longer make the model stop compiling (it did while the afterModify body
	// read Linked_Field_State, measured 2026-10-18 22:17). Project deletion keeps lot S9's DISTRIBUTION_TRIGGERS item, which is now only housekeeping.
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private StepResetter()
	{
	}

	// ================================================================================================================ plan

	public static ObjectNode plan(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pFrom) throws OperationException
	{
		if (StepResetter.isDomain(pFrom))
		{
			final Adaptation domainProject = StepResetter.findProject(pFactory, pProjectCode);
			if (domainProject == null)
			{
				throw EngineRefusal.projectNotFound(pProjectCode);
			}
			final ObjectNode out = StepResetter.MAPPER.createObjectNode();
			out.put("project", pProjectCode);
			out.put("from", StepResetter.DOMAIN);
			out.set("items", DomainCleanup.plan(pFactory, domainProject.getOccurrencePrimaryKey().format()));
			out.putObject("rowCounts");
			final ArrayNode stepNames = out.putArray("stepNames");
			for (final String stepName : StepResetPlan.domainStepNames())
			{
				stepNames.add(stepName);
			}
			return out;
		}
		final Stage from = StepResetter.parseStage(pFrom);
		final Adaptation project = StepResetter.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final Gathered gathered = StepResetter.gather(pSession, pFactory, project, pProjectCode, from);
		final Plan plan = StepResetPlan.build(from, gathered.facts);
		return StepResetter.planJson(plan);
	}

	private static ObjectNode planJson(final Plan pPlan)
	{
		final ObjectNode out = StepResetter.MAPPER.createObjectNode();
		out.put("project", pPlan.projectCode);
		out.put("from", pPlan.from.name());
		if (pPlan.refusal != null)
		{
			out.put("refusal", pPlan.refusal);
		}
		final ArrayNode items = out.putArray("items");
		for (final Item item : pPlan.items)
		{
			final ObjectNode node = items.addObject();
			node.put("kind", item.kind.name());
			node.put("name", item.name);
			node.put("detail", item.detail);
			node.put("disposition", item.disposition.name());
			if (item.reason != null)
			{
				node.put("reason", item.reason);
			}
		}
		final ObjectNode rowCounts = out.putObject("rowCounts");
		for (final Map.Entry<String, Integer> entry : pPlan.rowCounts.entrySet())
		{
			rowCounts.put(entry.getKey(), entry.getValue().intValue());
		}
		final ArrayNode stepNames = out.putArray("stepNames");
		for (final String stepName : pPlan.stepNames)
		{
			stepNames.add(stepName);
		}
		return out;
	}

	// ============================================================================================================== reset

	/** Cleans the project from {@code pFrom} on, holding every resource of the project so that no job can run meanwhile. */
	public static ObjectNode reset(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pFrom, final String pConfirm)
		throws OperationException
	{
		final Adaptation project = StepResetter.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		return FactoryJobQueue.exclusively(pFactory, project.getOccurrencePrimaryKey().format(), "STEP_RESET", pSession,
			new Actor.Work<ObjectNode, OperationException>()
			{
				@Override
				public ObjectNode run() throws OperationException
				{
					return StepResetter.resetHoldingProject(pSession, pFactory, pProjectCode, pFrom, pConfirm);
				}
			});
	}

	// volume-operations lot 8a (2026-09-28, task 2): package-visible, not private, so StepResetRun's job
	// handler can call this directly - its own whole-project claim (FactoryJobs.claimAll) already provides the
	// exclusivity #reset's exclusively(...) call gives a synchronous caller; calling THROUGH exclusively from
	// inside a job that already holds every resource would deadlock on its own claim.
	static ObjectNode resetHoldingProject(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pFrom,
		final String pConfirm) throws OperationException
	{
		if (StepResetter.isDomain(pFrom))
		{
			return StepResetter.resetDomain(pSession, pFactory, pProjectCode, pConfirm);
		}
		final Stage from = StepResetter.parseStage(pFrom);
		final Adaptation project = StepResetter.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final Gathered gathered = StepResetter.gather(pSession, pFactory, project, pProjectCode, from);
		final Plan plan = StepResetPlan.build(from, gathered.facts);
		final String refusal = StepResetPlan.validate(plan, pConfirm);
		if (refusal != null)
		{
			throw EngineRefusal.coded("conflict.refused", refusal);
		}

		// automatic-intake lot (2026-10-05): a reset of the landing, the staging or the master removes what an automatic chain works on (the landing
		// triggers and the signal dataset go with the landing objects): the intake is switched OFF first, to be switched on again after the first full load
		if (from == Stage.LANDING || from == Stage.STAGING || from == Stage.MASTER)
		{
			Intake.forceOff(pSession, pFactory, project);
		}

		final ObjectNode out = StepResetter.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("from", from.name());
		final ArrayNode generated = out.putArray("generated");
		final List<String> failed = new ArrayList<String>();
		int keptBusinessObject = 0;
		boolean modelOrDataspaceDeleted = false;
		for (final Item item : plan.items)
		{
			final ObjectNode g = generated.addObject();
			g.put("kind", item.kind.name());
			g.put("name", item.name);
			if (item.disposition == Disposition.EXCLUDED)
			{
				g.put("outcome", "EXCLUDED");
				g.put("message", item.reason);
				continue;
			}
			// item.disposition == DELETE here: StepResetPlan.validate above already refused the whole call if
			// any item was REFUSED, so nothing REFUSED ever reaches this loop.
			final GeneratedObjectDeleter.Outcome outcome = StepResetter.execute(pSession, pFactory, gathered.facts.base, item);
			g.put("outcome", outcome.result);
			if (outcome.message != null)
			{
				g.put("message", outcome.message);
			}
			if (outcome.code != null)
			{
				g.put("reasonCode", outcome.code);
			}
			if ("FAILED".equals(outcome.result))
			{
				failed.add(item.kind + " " + item.name + ": " + outcome.message);
			}
			if (GeneratedObjectDeleter.RESULT_KEPT_BUSINESS_OBJECT.equals(outcome.result))
			{
				keptBusinessObject++;
			}
			if ("DELETED".equals(outcome.result) && (item.kind == com.on.ebx.factory.project.StepResetPlan.Kind.MASTER_MODEL || item.kind == com.on.ebx.factory.project.StepResetPlan.Kind.MASTER_DATASPACE))
			{
				modelOrDataspaceDeleted = true;
			}
		}
		// lot W13: the configurations the add-on will not delete (business-object members) are orphaned when the model or the dataspace goes
		final String orphanNote = GeneratedObjectDeleter.orphanedConfigurationNote(keptBusinessObject, modelOrDataspaceDeleted, gathered.facts.base.masterModelName);
		if (orphanNote != null)
		{
			out.put("orphanedMatchingConfiguration", orphanNote);
			LoggingCategory.getKernel().warn("[mdm-factory] cleanup of " + pProjectCode + ": " + orphanNote);
		}
		if (!failed.isEmpty())
		{
			throw EngineRefusal.coded("conflict.cleanupBlocked", "Cleaning up " + pProjectCode + " from " + from + " stopped before touching a single row - " + failed.size()
				+ " generated object(s) could not be removed: " + StepResetter.join(failed, "; ") + ". Nothing was written; fix the underlying issue and retry - "
				+ "the plan is recomputed from the live state on every call, and an object already gone is reported absent, never a failure.", pProjectCode, from);
		}

		final StepReport report = StepResetter.writeReset(pSession, pFactory, project, gathered, from, plan);
		final ObjectNode rowsOut = out.putObject("rowsDeleted");
		for (final Map.Entry<String, Integer> entry : report.rowsDeleted.entrySet())
		{
			rowsOut.put(entry.getKey(), entry.getValue().intValue());
		}
		out.put("xrefCleared", report.xrefCleared);
		out.put("stepsReset", report.stepsReset);
		out.put("status", "RESET");
		MasterZoneIndex.invalidate();
		LoggingCategory.getKernel()
			.info("[mdm-factory] project " + pProjectCode + " step-reset from " + from + ": rowsDeleted=" + report.rowsDeleted + ", stepsReset=" + report.stepsReset);
		return out;
	}

	// ------------------------------------------------------------------------------------------------- the generated domain (lot C1)

	/** The pseudo stage of a Centralisation project: {@code ?from=DOMAIN} removes what {@code DomainGenerator} generated (the {@code ACCEL_*} steps). */
	static final String DOMAIN = "DOMAIN";

	static boolean isDomain(final String pFrom)
	{
		return pFrom != null && StepResetter.DOMAIN.equalsIgnoreCase(pFrom.trim());
	}

	/**
	 * Reset of the generated domain: the {@code DomainObject} items are removed through {@link DomainCleanup} (workflow publication, model, matching
	 * configuration, dataspace, form layouts, data model; all four options are taken, a reset is a full start-over), and only when every one succeeded
	 * the {@code ACCEL_*} {@code PipelineStep} rows go back to {@code RESET}, the project's {@code domain*} fields are cleared and its packs are
	 * {@code CHOSEN} again. The proposal ({@code Difference} rows, {@code ProjectPack} choice) is kept: the generation can simply run again.
	 */
	private static ObjectNode resetDomain(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pConfirm) throws OperationException
	{
		final Adaptation project = StepResetter.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		if (pConfirm == null || !pConfirm.equals(pProjectCode))
		{
			throw EngineRefusal.coded("conflict.refused", "Pass ?confirm=" + pProjectCode + " (the exact project code) to actually remove the generated domain - this cannot be undone.");
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final ObjectNode out = StepResetter.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("from", StepResetter.DOMAIN);
		final List<String> failed = new ArrayList<String>();
		final java.util.Set<String> options = new java.util.LinkedHashSet<String>(java.util.Arrays.asList(ProjectDeletionPlan.OPTIONS));
		out.set("generated", DomainCleanup.run(pSession, pFactory, projectId, options, failed));
		if (!failed.isEmpty())
		{
			throw EngineRefusal.coded("conflict.cleanupBlocked", "Cleaning up " + pProjectCode + " from " + StepResetter.DOMAIN + " stopped before touching a single row - " + failed.size()
				+ " generated object(s) could not be removed: " + StepResetter.join(failed, "; ") + ". Nothing was written; fix the underlying issue and retry.", pProjectCode,
				StepResetter.DOMAIN);
		}
		final StringBuilder names = new StringBuilder();
		for (final String stepName : StepResetPlan.domainStepNames())
		{
			names.append(names.length() == 0 ? "" : " or ").append("./step=").append(CatalogReader.quote(stepName));
		}
		final List<Adaptation> steps = StepResetter.drain(pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(projectId) + " and (" + names + ") and ./status!=" + CatalogReader.quote("RESET")));
		final List<Adaptation> packs = StepResetter.queryEq(pFactory, FactoryPaths.PROJECT_PACK, "./project", projectId);
		final int[] stepsReset = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Adaptation step : steps)
				{
					final ValueContextForUpdate value = pContext.getContext(step.getAdaptationName());
					value.setValue("RESET", FactoryPaths.PipelineStep.STATUS);
					pContext.doModifyContent(step, value);
					stepsReset[0]++;
				}
				for (final Adaptation pack : packs)
				{
					if (FactoryPaths.ProjectPack.STATUS_GENERATED.equals(pack.getString(FactoryPaths.ProjectPack.STATUS)))
					{
						final ValueContextForUpdate value = pContext.getContext(pack.getAdaptationName());
						value.setValue(FactoryPaths.ProjectPack.STATUS_CHOSEN, FactoryPaths.ProjectPack.STATUS);
						pContext.doModifyContent(pack, value);
					}
				}
				final ValueContextForUpdate projectValue = pContext.getContext(project.getAdaptationName());
				projectValue.setValue(null, FactoryPaths.Project.DOMAIN_DATASPACE);
				projectValue.setValue(null, FactoryPaths.Project.DOMAIN_DATASET);
				projectValue.setValue(null, FactoryPaths.Project.DOMAIN_MODEL);
				pContext.doModifyContent(project, projectValue);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.resetBookkeeping", "The domain was removed, but the row/bookkeeping write-back failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, "STEP_RESET", StepResetter.DOMAIN);
		PipelineSteps.end(pSession, pFactory, stepId, true, "Generated domain removed: " + stepsReset[0] + " step(s) reset.", out.toString());
		out.put("stepsReset", stepsReset[0]);
		out.put("status", "RESET");
		return out;
	}

	// ------------------------------------------------------------------------------------------------- per-item execution

	private static GeneratedObjectDeleter.Outcome execute(final Session pSession, final Adaptation pFactory, final ProjectDeletionPlan.Facts pFacts, final Item pItem)
	{
		try
		{
			switch (pItem.kind)
			{
				case MATCHING_CONFIG:
					return GeneratedObjectDeleter.deleteMatchingConfig(pSession, pFactory, pItem.name, pFacts.masterModelName);
				case MASTER_DATASPACE:
				case STAGING_DATASPACE:
				case LANDING_DATASPACE:
					return GeneratedObjectDeleter.deleteDataspace(pSession, pFactory, pItem.name);
				case MASTER_MODEL:
				case STAGING_MODEL:
				case LANDING_MODEL:
					return GeneratedObjectDeleter.deleteDataModel(pSession, pFactory, pItem.name);
				case LANDING_FLOW:
					return GeneratedObjectDeleter.deleteMapping(pSession, pFactory, pItem.name);
				default:
					return GeneratedObjectDeleter.Outcome.failed("Unknown item kind " + pItem.kind);
			}
		}
		catch (final McpException ex)
		{
			return GeneratedObjectDeleter.Outcome.failed(ex.getMessage());
		}
		catch (final OperationException ex)
		{
			return GeneratedObjectDeleter.Outcome.failed(ex.getMessage());
		}
		catch (final RuntimeException ex)
		{
			return GeneratedObjectDeleter.Outcome.failed(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
		}
	}

	// ============================================================================================== gathering the facts

	private static final class Gathered
	{
		final StepResetPlan.Facts facts;
		/** Deletion order (children before parents) - see the class Javadoc. Only tables in scope for {@code from} carry rows here. */
		final Map<String, List<Adaptation>> rowsByTable;
		// volume lot 2a (2026-09-28, docs/VOLUME.md, task 6): Xref is no longer gathered into a List here at
		// all (it never was for plan() - planJson never read it - and for reset() it is now fixed by
		// VolumeBatchOps.forEachBatched, streamed and batched, called directly from #writeReset).
		final List<Adaptation> stagingEntityRowsToClearTargetTable;
		/** {@code PipelineStep} rows to move to {@code RESET} - resolved here (entity-name/suggestion-id scoping), never guessed from a name alone. */
		final List<Adaptation> stepsToReset;

		private Gathered(final StepResetPlan.Facts pFacts, final Map<String, List<Adaptation>> pRowsByTable,
			final List<Adaptation> pStagingEntityRowsToClearTargetTable, final List<Adaptation> pStepsToReset)
		{
			this.facts = pFacts;
			this.rowsByTable = pRowsByTable;
			this.stagingEntityRowsToClearTargetTable = pStagingEntityRowsToClearTargetTable;
			this.stepsToReset = pStepsToReset;
		}
	}

	private static Gathered gather(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final String pProjectCode, final Stage pFrom) throws OperationException
	{
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final String mode = pProject.getString(FactoryPaths.Project.MODE);
		final boolean delivered = ProjectOrigin.isDelivered(pFactory, pProject);

		final String landingDataspaceStored = StepResetter.str(pProject.getString(FactoryPaths.Project.LANDING_DATASPACE));
		final String landingDataspaceComputed = LandingNames.dataspaceKey(pProjectCode, LandingGenerator.landingSuffix(pFactory));
		final String stagingDataspaceStored = StepResetter.str(pProject.getString(FactoryPaths.Project.STAGING_DATASPACE));
		final String stagingDatasetStored = StepResetter.str(pProject.getString(FactoryPaths.Project.STAGING_DATASET));
		final String stagingDataspaceComputed = StagingNames.dataspaceKey(pProjectCode, StagingGenerator.stagingSuffix(pFactory));
		final boolean stagingModelStepDone = StepResetter.stepEverDone(pFactory, projectId, "STAGING_MODEL", "");
		final boolean stagingDatasetCreatedByFactory = StepResetter.datasetCreatedByFactory(pFactory, projectId, "STAGING_DATASET", "");

		final String masterDataspaceStored = StepResetter.str(pProject.getString(FactoryPaths.Project.MASTER_DATASPACE));
		final String masterDatasetStored = StepResetter.str(pProject.getString(FactoryPaths.Project.MASTER_DATASET));
		final String masterDataspaceComputed = MasterNames.dataspaceKey(pProjectCode, MasterGenerator.masterSuffix(pFactory));
		final boolean masterModelStepDone = StepResetter.stepEverDone(pFactory, projectId, "MASTER_MODEL", "");
		final boolean masterDatasetCreatedByFactory = StepResetter.datasetCreatedByFactory(pFactory, projectId, "MASTER_DATASET", "");

		final List<Adaptation> projectSources = StepResetter.queryEq(pFactory, FactoryPaths.PROJECT_SOURCE, "./project", projectId);
		final List<String> sourceIds = StepResetter.ids(projectSources);
		final List<Adaptation> landingTables = StepResetter.queryIn(pFactory, FactoryPaths.LANDING_TABLE, "./projectSource", sourceIds);
		final List<Adaptation> landingRelations = StepResetter.queryIn(pFactory, FactoryPaths.LANDING_RELATION, "./projectSource", sourceIds);
		final List<Adaptation> landingColumns = StepResetter.queryIn(pFactory, FactoryPaths.LANDING_COLUMN, "./landingTable", StepResetter.ids(landingTables));
		// audit F3-10: the schema drifts of those landing tables go with them (read only when the landing is in scope: a staging or master reset never touches them)
		final List<Adaptation> sourceDrifts = pFrom == Stage.LANDING ? StepResetter.queryIn(pFactory, FactoryPaths.SOURCE_DRIFT, "./landingTable", StepResetter.ids(landingTables))
			: new ArrayList<Adaptation>();

		final Map<String, List<String>> mappingIdsBySource = new LinkedHashMap<String, List<String>>();
		for (final Adaptation row : landingTables)
		{
			final String sourceId = row.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
			final String mappingId = row.getString(FactoryPaths.LandingTable.INBOUND_MAPPING);
			if (mappingId == null || mappingId.trim().isEmpty())
			{
				continue;
			}
			List<String> list = mappingIdsBySource.get(sourceId);
			if (list == null)
			{
				list = new ArrayList<String>();
				mappingIdsBySource.put(sourceId, list);
			}
			list.add(mappingId.trim());
		}
		// fix of 2026-10-03: whose is each mapping? Its own dataspace, read once from Connectors, says it (two projects on the same
		// source system once shared one mapping) - the plan refuses a mapping that writes anywhere but this project's landing dataspace.
		final List<String> allMappingIds = new ArrayList<String>();
		for (final List<String> ids : mappingIdsBySource.values())
		{
			allMappingIds.addAll(ids);
		}
		final Map<String, String> mappingDataspaces = LandingFlowOwnership.dataspaces(pSession, pFactory, allMappingIds);
		final List<SourceFacts> sources = new ArrayList<SourceFacts>();
		for (final Adaptation row : projectSources)
		{
			final String sourceId = row.getOccurrencePrimaryKey().format();
			final String sourceSystem = StepResetter.str(row.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM));
			final String landingModel = StepResetter.str(row.getString(FactoryPaths.ProjectSource.LANDING_MODEL));
			final String landingDataset = StepResetter.str(row.getString(FactoryPaths.ProjectSource.LANDING_DATASET));
			final boolean landingModelStepDone = StepResetter.stepEverDone(pFactory, projectId, "LANDING_MODEL", sourceSystem);
			final boolean landingDatasetCreatedByFactory = StepResetter.datasetCreatedByFactory(pFactory, projectId, "LANDING_DATASET", sourceSystem);
			final List<String> mappingIds = mappingIdsBySource.get(sourceId);
			final List<String> sourceMappingIds = mappingIds == null ? new ArrayList<String>() : mappingIds;
			final Map<String, String> sourceMappingDataspaces = new LinkedHashMap<String, String>();
			for (final String mappingId : sourceMappingIds)
			{
				sourceMappingDataspaces.put(mappingId, mappingDataspaces.get(mappingId));
			}
			sources.add(new SourceFacts(sourceSystem, landingModel, landingDataset, landingModelStepDone, landingDatasetCreatedByFactory, sourceMappingIds,
				sourceMappingDataspaces));
		}

		final List<Adaptation> stagingEntities = StepResetter.queryEq(pFactory, FactoryPaths.STAGING_ENTITY, "./project", projectId);
		final List<String> entityIds = StepResetter.ids(stagingEntities);
		final List<Adaptation> stagingAttributes = StepResetter.queryIn(pFactory, FactoryPaths.STAGING_ATTRIBUTE, "./entity", entityIds);
		final List<String> attributeIds = StepResetter.ids(stagingAttributes);
		final List<Adaptation> attributeMappings = StepResetter.queryIn(pFactory, FactoryPaths.ATTRIBUTE_MAPPING, "./attribute", attributeIds);
		final List<Adaptation> mappingSteps = StepResetter.queryIn(pFactory, FactoryPaths.MAPPING_STEP, "./mapping", StepResetter.ids(attributeMappings));
		final List<Adaptation> qualityRules = StepResetter.queryIn(pFactory, FactoryPaths.QUALITY_RULE, "./entity", entityIds);
		final List<Adaptation> capabilitySuggestions = StepResetter.queryEq(pFactory, FactoryPaths.CAPABILITY_SUGGESTION, "./project", projectId);
		final List<Adaptation> transferFeeds = StepResetter.queryIn(pFactory, FactoryPaths.TRANSFER_FEED, "./entity", entityIds);
		final List<Adaptation> matchPlans = StepResetter.queryIn(pFactory, FactoryPaths.MATCH_PLAN, "./entity", entityIds);

		final List<MatchingFacts> matching = new ArrayList<MatchingFacts>();
		final String masterModelName = MasterNames.modelName(pProjectCode);
		if (!masterDataspaceStored.isEmpty())
		{
			final Map<String, String> entityIdByName = new LinkedHashMap<String, String>();
			final List<StagingModel.EntityInfo> stagingEntityInfos = MasterLoader.readEntities(pFactory, projectId, entityIdByName);
			final MasterModel.Result masterModel = MasterModel.buildFromStaging(stagingEntityInfos);
			for (final String entityName : masterModel.builtEntities)
			{
				final String tablePath = MasterModel.tablePath(entityName);
				final boolean configured = StepResetter.isConfigured(pSession, pFactory, tablePath, masterModelName);
				matching.add(new MatchingFacts(entityName, tablePath, configured));
			}
		}

		final ProjectDeletionPlan.Facts base = new ProjectDeletionPlan.Facts(pProjectCode, mode, delivered, landingDataspaceStored, landingDataspaceComputed,
			stagingDataspaceStored, stagingDatasetStored, stagingDataspaceComputed, StagingNames.DATASET, stagingModelStepDone, stagingDatasetCreatedByFactory,
			masterDataspaceStored, masterDatasetStored, masterDataspaceComputed, MasterNames.DATASET, masterModelStepDone, masterDatasetCreatedByFactory, sources,
			matching, masterModelName, StagingNames.modelName(pProjectCode), null);

		// ---- rows in scope for `from`, in deletion order (children before parents) - see the class Javadoc
		final boolean stagingInScope = pFrom == Stage.LANDING || pFrom == Stage.STAGING;
		// volume lot 2a (2026-09-28, docs/VOLUME.md, task 6): filtered by ./stage IN THE PREDICATE - a
		// MASTER-only reset never even reads the (potentially much larger) STAGING-stage rows of this
		// volume-scale table, instead of fetching everything and filtering in Java.
		final List<Adaptation> transferRejects = stagingInScope ? StepResetter.queryEq(pFactory, FactoryPaths.TRANSFER_REJECT, "./project", projectId)
			: StepResetter.queryEqAnd(pFactory, FactoryPaths.TRANSFER_REJECT, "./project", projectId, "./stage", "MASTER");
		final Map<String, List<Adaptation>> available = new LinkedHashMap<String, List<Adaptation>>();
		available.put("MappingStep", mappingSteps);
		available.put("AttributeMapping", attributeMappings);
		available.put("QualityRule", qualityRules);
		available.put("CapabilitySuggestion", capabilitySuggestions);
		available.put("MatchPlan", matchPlans);
		available.put("TransferReject", transferRejects);
		available.put("TransferFeed", transferFeeds);
		available.put("StagingAttribute", stagingAttributes);
		available.put("StagingEntity", stagingEntities);
		available.put("SourceDrift", sourceDrifts);
		available.put("LandingColumn", landingColumns);
		available.put("LandingRelation", landingRelations);
		available.put("LandingTable", landingTables);
		// the tables in scope and their order come from the pure plan (StepResetPlanTest pins them)
		final Map<String, List<Adaptation>> rowsByTable = new LinkedHashMap<String, List<Adaptation>>();
		for (final String table : StepResetPlan.rowTables(pFrom))
		{
			rowsByTable.put(table, available.get(table));
		}
		final Map<String, Integer> rowCounts = new LinkedHashMap<String, Integer>();
		for (final Map.Entry<String, List<Adaptation>> entry : rowsByTable.entrySet())
		{
			rowCounts.put(entry.getKey(), Integer.valueOf(entry.getValue().size()));
		}

		final List<Adaptation> stagingEntityRowsToClearTargetTable = pFrom == Stage.MASTER ? stagingEntities : new ArrayList<Adaptation>();

		// ---- PipelineStep rows to move to RESET - resolved here, never guessed from the step name alone
		final List<Adaptation> stepsToReset = new ArrayList<Adaptation>();
		final Set<String> plainStepNames = new LinkedHashSet<String>();
		for (final String stepName : StepResetPlan.stepNames(pFrom))
		{
			if (!"CAPABILITY_APPLY".equals(stepName) && !"CAPABILITY_REMOVE".equals(stepName) && !StepResetter.isMatchingEntityScopedStep(stepName))
			{
				plainStepNames.add(stepName);
			}
		}
		if (!plainStepNames.isEmpty())
		{
			stepsToReset.addAll(StepResetter.queryStepsByNamesAnyScope(pFactory, projectId, plainStepNames));
		}
		if (StepResetPlan.stepNames(pFrom).contains("MATCHING_CONFIG"))
		{
			final Set<String> matchingStepNames = new LinkedHashSet<String>();
			matchingStepNames.add("MATCHING_CONFIG");
			matchingStepNames.add("MATCHING_RUN");
			matchingStepNames.add("MATCHING_SURVIVORSHIP");
			matchingStepNames.add("MATCHING_SIMULATE_PREVIEW");
			matchingStepNames.add("GOLDEN_KEY_REFRESH");
			for (final MatchingFacts matchingFact : matching)
			{
				stepsToReset.addAll(StepResetter.queryStepsByNamesAndScope(pFactory, projectId, matchingStepNames, matchingFact.entityName));
			}
		}
		if (StepResetPlan.stepNames(pFrom).contains("CAPABILITY_APPLY"))
		{
			final Set<String> suggestionIds = new LinkedHashSet<String>();
			if (stagingInScope)
			{
				for (final Adaptation row : capabilitySuggestions)
				{
					suggestionIds.add(row.getOccurrencePrimaryKey().format());
				}
			}
			else
			{
				// MASTER-only reset: the CapabilitySuggestion rows survive, only the ones applied to the (now
				// gone) MASTER model are invalidated - see the class Javadoc.
				final List<Adaptation> masterZoneSuggestions = StepResetter.queryEq(pFactory, FactoryPaths.CAPABILITY_SUGGESTION, "./project", projectId);
				for (final Adaptation row : masterZoneSuggestions)
				{
					if ("MASTER".equals(row.getString(FactoryPaths.CapabilitySuggestion.ZONE)))
					{
						suggestionIds.add(row.getOccurrencePrimaryKey().format());
					}
				}
			}
			final Set<String> capabilityStepNames = new LinkedHashSet<String>();
			capabilityStepNames.add("CAPABILITY_APPLY");
			capabilityStepNames.add("CAPABILITY_REMOVE");
			for (final String suggestionId : suggestionIds)
			{
				stepsToReset.addAll(StepResetter.queryStepsByNamesAndScope(pFactory, projectId, capabilityStepNames, suggestionId));
			}
		}

		return new Gathered(new StepResetPlan.Facts(base, rowCounts), rowsByTable, stagingEntityRowsToClearTargetTable, stepsToReset);
	}

	private static boolean isMatchingEntityScopedStep(final String pStepName)
	{
		return "MATCHING_CONFIG".equals(pStepName) || "MATCHING_RUN".equals(pStepName) || "MATCHING_SURVIVORSHIP".equals(pStepName)
			|| "MATCHING_SIMULATE_PREVIEW".equals(pStepName) || "GOLDEN_KEY_REFRESH".equals(pStepName);
	}

	private static boolean isConfigured(final Session pSession, final Adaptation pFactory, final String pTablePath, final String pMasterModelName) throws OperationException
	{
		try
		{
			final com.on.ebx.agent.McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
			final ObjectNode args = StepResetter.MAPPER.createObjectNode();
			args.put("table", pTablePath);
			final com.on.ebx.agent.McpClient.ToolResult result = MameCalls.call(client, "get_matching_config", args, pMasterModelName);
			return com.on.ebx.factory.master.MameAnswer.success(result.json, false);
		}
		catch (final McpException ex)
		{
			return false;
		}
		catch (final OperationException ex)
		{
			return false;
		}
	}

	// ================================================================================================================ write

	private static final class StepReport
	{
		final Map<String, Integer> rowsDeleted;
		final int xrefCleared;
		final int stepsReset;

		private StepReport(final Map<String, Integer> pRowsDeleted, final int pXrefCleared, final int pStepsReset)
		{
			this.rowsDeleted = pRowsDeleted;
			this.xrefCleared = pXrefCleared;
			this.stepsReset = pStepsReset;
		}
	}

	/**
	 * One {@link Procedure}: deletes every in-scope row (children before parents, per {@link Gathered#rowsByTable}'s
	 * own insertion order), clears {@code Xref}/{@code StagingEntity.targetTable}/{@code Project} fields for the
	 * reset zones, moves every resolved {@code PipelineStep} row to {@code RESET}, and writes this call's own
	 * {@code STEP_RESET} step ({@code scope} = {@code pFrom}, {@code status DONE}, {@code detail} = the plan JSON) -
	 * the audit trail of the reset itself, never itself reset by a later call (same posture as {@code MASTER_RESET}).
	 */
	private static StepReport writeReset(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final Gathered pGathered, final Stage pFrom,
		final Plan pPlan) throws OperationException
	{
		final boolean masterInScope = pFrom == Stage.LANDING || pFrom == Stage.STAGING || pFrom == Stage.MASTER;
		final boolean stagingInScope = pFrom == Stage.LANDING || pFrom == Stage.STAGING;
		final boolean landingInScope = pFrom == Stage.LANDING;

		// volume lot 2a (2026-09-28, docs/VOLUME.md, task 6): Xref (one row per staged/master record - a
		// genuinely volume-scale table, unlike every other row this call touches) is fixed OUTSIDE the main
		// Procedure below, streamed and committed in batches (VolumeBatchOps) - the predicate itself narrows to
		// rows that need SOMETHING cleared (goldenKey always; masterTable/masterKey only once MASTER is in
		// scope), so a project with nothing to clear never even opens a batch.
		final String xrefPredicate = "./project=" + CatalogReader.quote(pProject.getOccurrencePrimaryKey().format()) + " and (osd:is-not-null(./goldenKey)"
			+ (masterInScope ? " or osd:is-not-null(./masterTable)" : "") + ")";
		final int[] xrefCleared = new int[1];
		VolumeBatchOps.forEachBatched(pSession, pFactory.getHome(), pFactory.getTable(FactoryPaths.XREF), xrefPredicate,
			"Step reset of " + pPlan.projectCode + " from " + pFrom + ": clearing Xref rows failed", new VolumeBatchOps.RowOp()
			{
				@Override
				public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
				{
					final ValueContextForUpdate value = pContext.getContext(pRow.getAdaptationName());
					value.setValue(null, FactoryPaths.Xref.GOLDEN_KEY);
					if (masterInScope)
					{
						value.setValue(null, FactoryPaths.Xref.MASTER_TABLE);
						value.setValue(null, FactoryPaths.Xref.MASTER_KEY);
					}
					pContext.doModifyContent(pRow, value);
					xrefCleared[0]++;
				}
			});

		final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		final int[] stepsReset = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Map.Entry<String, List<Adaptation>> entry : pGathered.rowsByTable.entrySet())
				{
					int deleted = 0;
					for (final Adaptation row : entry.getValue())
					{
						pContext.doDelete(row.getAdaptationName(), false);
						deleted++;
					}
					counts.put(entry.getKey(), Integer.valueOf(deleted));
				}

				// StagingEntity.targetTable - only a MASTER-only reset (StagingEntity itself survives): see the
				// class Javadoc for why STAGING-or-deeper never reaches here (the row is deleted above instead).
				for (final Adaptation row : pGathered.stagingEntityRowsToClearTargetTable)
				{
					final String target = row.getString(FactoryPaths.StagingEntity.TARGET_TABLE);
					if (target == null || target.trim().isEmpty())
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(null, FactoryPaths.StagingEntity.TARGET_TABLE);
					pContext.doModifyContent(row, value);
				}

				// Project fields memorizing the reset zones
				final ValueContextForUpdate projectValue = pContext.getContext(pProject.getAdaptationName());
				boolean projectTouched = false;
				if (masterInScope)
				{
					projectValue.setValue(null, FactoryPaths.Project.MASTER_DATASPACE);
					projectValue.setValue(null, FactoryPaths.Project.MASTER_DATASET);
					projectTouched = true;
				}
				if (stagingInScope)
				{
					projectValue.setValue(null, FactoryPaths.Project.STAGING_DATASPACE);
					projectValue.setValue(null, FactoryPaths.Project.STAGING_DATASET);
					projectTouched = true;
				}
				if (projectTouched)
				{
					pContext.doModifyContent(pProject, projectValue);
				}

				// ProjectSource fields memorizing the landing zone - only when LANDING is reset (the source
				// selection itself, ProjectSource.sourceSystem/catalogSource/connector/schemaFilter/trustRank,
				// is never touched: "les sources choisies ne sont pas remises en cause" - the product owner's own words)
				if (landingInScope)
				{
					final AdaptationTable projectSourceTable = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
					final RequestResult sourceRows = projectSourceTable.createRequestResult("./project=" + CatalogReader.quote(pProject.getOccurrencePrimaryKey().format()));
					try
					{
						for (Adaptation sourceRow = sourceRows.nextAdaptation(); sourceRow != null; sourceRow = sourceRows.nextAdaptation())
						{
							final ValueContextForUpdate sourceValue = pContext.getContext(sourceRow.getAdaptationName());
							sourceValue.setValue(null, FactoryPaths.ProjectSource.LANDING_MODEL);
							sourceValue.setValue(null, FactoryPaths.ProjectSource.LANDING_PUBLICATION);
							sourceValue.setValue(null, FactoryPaths.ProjectSource.LANDING_DATASET);
							sourceValue.setValue("SELECTED", FactoryPaths.ProjectSource.STATUS);
							sourceValue.setValue(null, FactoryPaths.ProjectSource.MESSAGE);
							pContext.doModifyContent(sourceRow, sourceValue);
						}
					}
					finally
					{
						sourceRows.close();
					}
				}

				// PipelineStep rows moved to RESET
				for (final Adaptation stepRow : pGathered.stepsToReset)
				{
					final ValueContextForUpdate stepValue = pContext.getContext(stepRow.getAdaptationName());
					stepValue.setValue("RESET", FactoryPaths.PipelineStep.STATUS);
					pContext.doModifyContent(stepRow, stepValue);
					stepsReset[0]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.resetBookkeeping", "Generated objects were removed, but the row/bookkeeping write-back failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}

		// this call's own audit step - its own procedure, so a failure here never hides the reset that already
		// succeeded above (same "begin/end in their own procedure" discipline as PipelineSteps itself)
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final ObjectNode detail = StepResetter.MAPPER.createObjectNode();
		detail.put("from", pFrom.name());
		final ArrayNode rowsNode = detail.putArray("rowsDeleted");
		for (final Map.Entry<String, Integer> entry : counts.entrySet())
		{
			final ObjectNode n = rowsNode.addObject();
			n.put("table", entry.getKey());
			n.put("count", entry.getValue().intValue());
		}
		detail.put("xrefCleared", xrefCleared[0]);
		detail.put("stepsReset", stepsReset[0]);
		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, "STEP_RESET", pFrom.name());
		PipelineSteps.end(pSession, pFactory, stepId, true, "Cleaned up from " + pFrom + " onward: " + stepsReset[0] + " step(s) reset, " + xrefCleared[0]
			+ " Xref row(s) cleared.", detail.toString());

		return new StepReport(counts, xrefCleared[0], stepsReset[0]);
	}

	// ---------------------------------------------------------------------------------------------------- evidence reads

	private static boolean stepEverDone(final Adaptation pFactory, final String pProjectId, final String pStep, final String pScope)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./step="
			+ CatalogReader.quote(pStep) + " and " + PipelineSteps.scopeEquals("./scope", pScope) + " and ./status=" + CatalogReader.quote("DONE"));
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	private static boolean datasetCreatedByFactory(final Adaptation pFactory, final String pProjectId, final String pStep, final String pScope)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./step="
			+ CatalogReader.quote(pStep) + " and " + PipelineSteps.scopeEquals("./scope", pScope) + " and ./status=" + CatalogReader.quote("DONE"));
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
					final JsonNode detail = StepResetter.MAPPER.readTree(detailJson);
					if (detail.path("dataspace").path("created").asBoolean(false))
					{
						return true;
					}
				}
				catch (final java.io.IOException ex)
				{
					// not valid JSON - never treated as evidence
				}
			}
			return false;
		}
		finally
		{
			rows.close();
		}
	}

	private static List<Adaptation> queryStepsByNamesAnyScope(final Adaptation pFactory, final String pProjectId, final Set<String> pStepNames)
	{
		if (pStepNames.isEmpty())
		{
			return new ArrayList<Adaptation>();
		}
		final StringBuilder namesPredicate = new StringBuilder();
		for (final String stepName : pStepNames)
		{
			if (namesPredicate.length() > 0)
			{
				namesPredicate.append(" or ");
			}
			namesPredicate.append("./step=").append(CatalogReader.quote(stepName));
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and (" + namesPredicate + ") and ./status=" + CatalogReader.quote("DONE"));
		return StepResetter.drain(rows);
	}

	private static List<Adaptation> queryStepsByNamesAndScope(final Adaptation pFactory, final String pProjectId, final Set<String> pStepNames, final String pScope)
	{
		if (pStepNames.isEmpty())
		{
			return new ArrayList<Adaptation>();
		}
		final StringBuilder namesPredicate = new StringBuilder();
		for (final String stepName : pStepNames)
		{
			if (namesPredicate.length() > 0)
			{
				namesPredicate.append(" or ");
			}
			namesPredicate.append("./step=").append(CatalogReader.quote(stepName));
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ("
			+ namesPredicate + ") and " + PipelineSteps.scopeEquals("./scope", pScope) + " and ./status=" + CatalogReader.quote("DONE"));
		return StepResetter.drain(rows);
	}

	// -------------------------------------------------------------------------------------------------------------- misc

	static Stage parseStage(final String pFrom) throws OperationException
	{
		if (pFrom == null || pFrom.trim().isEmpty())
		{
			throw EngineRefusal.coded("invalid.stage", "Pass ?from=<stage>, one of LANDING/STAGING/MASTER/MATCHING.");
		}
		try
		{
			return Stage.valueOf(pFrom.trim().toUpperCase(Locale.ENGLISH));
		}
		catch (final IllegalArgumentException ex)
		{
			throw EngineRefusal.coded("invalid.stageUnknown", "Unknown stage '" + pFrom + "': expected one of LANDING/STAGING/MASTER/MATCHING.", pFrom);
		}
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

	private static List<Adaptation> queryEq(final Adaptation pFactory, final Path pTable, final String pFieldPath, final String pValue)
	{
		final RequestResult rows = pFactory.getTable(pTable).createRequestResult(pFieldPath + "=" + CatalogReader.quote(pValue));
		return StepResetter.drain(rows);
	}

	/** Two equality conditions ANDed together, in the predicate - volume lot 2a (2026-09-28, docs/VOLUME.md, task 6): used to filter {@code TransferReject} by {@code ./stage} server-side rather than fetching every row and filtering in Java. */
	private static List<Adaptation> queryEqAnd(final Adaptation pFactory, final Path pTable, final String pFieldPath1, final String pValue1, final String pFieldPath2,
		final String pValue2)
	{
		final RequestResult rows = pFactory.getTable(pTable)
			.createRequestResult(pFieldPath1 + "=" + CatalogReader.quote(pValue1) + " and " + pFieldPath2 + "=" + CatalogReader.quote(pValue2));
		return StepResetter.drain(rows);
	}

	private static List<Adaptation> queryIn(final Adaptation pFactory, final Path pTable, final String pFieldPath, final List<String> pIds)
	{
		if (pIds.isEmpty())
		{
			return new ArrayList<Adaptation>();
		}
		final StringBuilder predicate = new StringBuilder();
		for (final String id : pIds)
		{
			if (predicate.length() > 0)
			{
				predicate.append(" or ");
			}
			predicate.append(pFieldPath).append("=").append(CatalogReader.quote(id));
		}
		final RequestResult rows = pFactory.getTable(pTable).createRequestResult(predicate.toString());
		return StepResetter.drain(rows);
	}

	private static List<Adaptation> drain(final RequestResult pRows)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		try
		{
			for (Adaptation row = pRows.nextAdaptation(); row != null; row = pRows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			pRows.close();
		}
		return out;
	}

	private static List<String> ids(final List<Adaptation> pRows)
	{
		final List<String> out = new ArrayList<String>();
		for (final Adaptation row : pRows)
		{
			out.add(row.getOccurrencePrimaryKey().format());
		}
		return out;
	}

	private static String str(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	private static String join(final List<String> pValues, final String pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String value : pValues)
		{
			if (sb.length() > 0)
			{
				sb.append(pSeparator);
			}
			sb.append(value);
		}
		return sb.toString();
	}
}
