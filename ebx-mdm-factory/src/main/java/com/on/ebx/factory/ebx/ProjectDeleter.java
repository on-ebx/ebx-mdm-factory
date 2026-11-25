package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.TargetMappingList;
import com.on.ebx.factory.landing.LandingNames;
import com.on.ebx.factory.master.MameAnswer;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.project.MatchingConfigRows;
import com.on.ebx.factory.project.ProjectDeletionPlan;
import com.on.ebx.factory.project.ProjectDeletionPlan.ConnectionFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.Facts;
import com.on.ebx.factory.project.ProjectDeletionPlan.Item;
import com.on.ebx.factory.project.ProjectDeletionPlan.MatchingFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.Plan;
import com.on.ebx.factory.project.ProjectDeletionPlan.SourceFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.TargetFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.TargetFlowFacts;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.StagingNames;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;

/**
 * Deletes a project: an optional cleanup of what the factory generated for it (dataspaces, datasets, data
 * models, matching configuration, Connectors flows - whatever the caller ticks and {@link ProjectDeletionPlan}
 * says is eligible), then, always, the project's own rows in the factory dataset. {@link ProjectDeletionPlan}
 * (pure, unit-tested) decides WHAT is eligible and WHETHER the call may proceed at all; this class only
 * gathers the live evidence it needs and, once allowed, executes - reusing every technique already proven
 * elsewhere in this module rather than inventing a new one:
 * <ul>
 * <li>a matching configuration - {@code delete_matching_config} on the {@code match-merge} MCP server, {@code
 * dataModel} sent unconditionally via {@code ebx.MameCalls#call} (`docs/MATCHING.md` &sect;28) - the same
 * discipline every match-merge call in this module now follows;</li>
 * <li>a Connectors flow - {@code connectors_mapping_delete} on the {@code ebx-connectors} MCP server: the inbound mapping of each landing table
 * and (fix of 2026-10-12) the OUTBOUND mapping of each table of each of the project's {@code DistributionTarget} rows, which are then deleted
 * with their {@code LoadPause} rows among the project's own rows (the {@code DistributionEvent} rows go first, with the volume tables);</li>
 * <li>a dataspace - {@code Repository.closeHome}/{@code deleteHome}, called directly with the session outside
 * any {@code Procedure}, exactly as {@code ebx.MasterReset} already does;</li>
 * <li>a data model - {@code execute_operation(operation="delete_data_model", arguments_json={"confirm":
 * "true"}})} on the {@code data-model} MCP server, exactly as {@code ebx.MasterReset} already does;</li>
 * <li>(lot C, 2026-10-17) a catalogue source and a sample-file connection the factory created for the project - {@link ConnectionCleanup}: the catalogue's own
 * flows through {@code connectors_mapping_delete}, the rows through {@code Procedure}s (neither module has a deletion tool for them); an object something else
 * still uses is {@code KEPT_IN_USE}, reported, not a failure.</li>
 * </ul>
 * <p>
 * <b>Order and failure handling.</b> Every ticked, eligible item of {@link Plan#items} is attempted, in that
 * list's own order (matching, then flows, then dataspaces, then data models - {@link ProjectDeletionPlan}'s
 * own Javadoc explains why that order matters); an item that throws is recorded {@code FAILED} and the loop
 * keeps going through the rest, so one failure never hides another. Once every ticked item has been attempted,
 * if ANY of them failed, {@link #delete} throws with a message naming every failure and stops - the project's
 * own rows are never touched in that case, so the whole call can simply be retried once the underlying issue
 * is fixed: {@link #plan}/{@link #delete} always recompute the plan from the live state, never from a cache,
 * so an object already removed by a previous partial run is reported {@code SKIPPED_ABSENT}, never a failure.
 * Only once every ticked item is {@code DELETED}/{@code SKIPPED_ABSENT} (or was never ticked/was ineligible)
 * does the final step run: one {@link Procedure} deleting the project's own factory rows, children before
 * parents, per the foreign-key graph {@code tools/build_xsd.py} declares - {@code MappingStep},
 * {@code AttributeMapping}, {@code QualityRule}, {@code CapabilitySuggestion}, {@code MatchPlan},
 * {@code StagingAttribute}, {@code StagingEntity}, {@code LandingColumn}, {@code LandingRelation},
 * {@code LandingTable}, {@code SourceDrift}, {@code ProjectSource}, {@code AgentStep}, {@code AgentProposal}, {@code AgentRun}, {@code AgentDefinition}, {@code Xref},
 * {@code TransferReject}, {@code DistributionEvent}, {@code PipelineStep}, then the {@code Project} row itself.
 * {@code ReferenceBinding}/{@code FactorySettings} carry no link to a project and are never touched.
 * <p>
 * <b>Volume-operations lot 8a (2026-09-28, task 5):</b> {@code DistributionEvent} (permanent outbound-send
 * history, one row per terminal SENT/SKIPPED/FAILED event - {@code ebx.DistributionDispatcher}) joins {@code
 * Xref}/{@code TransferReject} in the "genuinely volume-scale, streamed and batched through {@link
 * VolumeBatchOps}" group below, never the single all-in-one {@link Procedure} the model-scale tables share -
 * same reasoning, same pattern, reused rather than redeveloped.
 */
public final class ProjectDeleter
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ProjectDeleter()
	{
	}

	// ================================================================================================================ plan

	/**
	 * A cheap read (no MCP call, no {@link #gather}) the REST route uses to decide whether {@link #plan} is
	 * about to reach the {@code match-merge} server at all - {@link #gather} only ever probes
	 * {@code get_matching_config} when the project has a master dataspace (see its own matching-facts
	 * section) - so the caller can preflight {@code MATCH_MERGE} only when it would actually be used, the
	 * same "only the servers this call needs" discipline every other route here follows. A project that does
	 * not exist reads as {@code false}: {@link #plan}/{@link #delete} report that refusal themselves.
	 */
	public static boolean hasMasterDataspace(final Adaptation pFactory, final String pProjectCode)
	{
		final Adaptation project = ProjectDeleter.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			return false;
		}
		final String masterDataspace = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		return masterDataspace != null && !masterDataspace.trim().isEmpty();
	}

	public static ObjectNode plan(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = ProjectDeleter.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final Gathered gathered = ProjectDeleter.gather(pSession, pFactory, project, pProjectCode);
		final Plan plan = ProjectDeletionPlan.build(gathered.facts);
		return ProjectDeleter.planJson(plan, project, gathered.facts.delivered, gathered.openReviews);
	}

	private static ObjectNode planJson(final Plan pPlan, final Adaptation pProject, final boolean pDelivered, final List<String> pOpenReviews)
	{
		final ObjectNode out = ProjectDeleter.MAPPER.createObjectNode();
		out.put("project", pPlan.projectCode);
		out.put("label", ProjectDeleter.str(pProject.getString(FactoryPaths.Project.LABEL)));
		out.put("delivered", pDelivered);
		final ArrayNode items = out.putArray("items");
		for (final Item item : pPlan.items)
		{
			final ObjectNode node = items.addObject();
			node.put("kind", item.kind.name());
			node.put("option", item.option);
			node.put("name", item.name);
			node.put("detail", item.detail);
			node.put("eligible", item.eligible);
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
		ProjectDeleter.putOpenReviews(out, pOpenReviews);
		return out;
	}

	/**
	 * Audit F3-10: an agent review (workflow {@code MdmFactoryAgentEscalation}) still open for this project. The deletion removes the rows, not the workflow instance (no private
	 * API, and ending someone's task is not the Factory's call): the plan and the result NAME the instances so an administrator can end them in the workflow inbox, where the task
	 * would otherwise fail on approval ("No agent is declared for run").
	 */
	private static void putOpenReviews(final ObjectNode pOut, final List<String> pOpenReviews)
	{
		if (pOpenReviews.isEmpty())
		{
			return;
		}
		final ArrayNode reviews = pOut.putArray("openAgentReviews");
		for (final String key : pOpenReviews)
		{
			reviews.add(key);
		}
		pOut.put("openAgentReviewsNote", "These workflow instances review agent decisions of this project and stay open: end them in the workflow inbox after the deletion.");
	}

	// ============================================================================================================== delete

	/** Deletes the project, holding every resource of the project so that no job can run meanwhile. */
	public static ObjectNode delete(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Set<String> pOptions, final String pConfirm)
		throws OperationException
	{
		final Adaptation project = ProjectDeleter.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		return FactoryJobQueue.exclusively(pFactory, project.getOccurrencePrimaryKey().format(), "PROJECT_DELETE", pSession,
			new Actor.Work<ObjectNode, OperationException>()
			{
				@Override
				public ObjectNode run() throws OperationException
				{
					return ProjectDeleter.deleteHoldingProject(pSession, pFactory, pProjectCode, pOptions, pConfirm);
				}
			});
	}

	// volume-operations lot 8a (2026-09-28, task 2): package-visible - see StepResetter.resetHoldingProject's
	// own comment, same reasoning, for ProjectDeleteRun's job handler.
	static ObjectNode deleteHoldingProject(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Set<String> pOptions,
		final String pConfirm) throws OperationException
	{
		final Adaptation project = ProjectDeleter.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final Gathered gathered = ProjectDeleter.gather(pSession, pFactory, project, pProjectCode);
		final Plan plan = ProjectDeletionPlan.build(gathered.facts);
		final String refusal = ProjectDeletionPlan.validate(plan, pOptions, pConfirm);
		if (refusal != null)
		{
			throw EngineRefusal.coded("conflict.refused", refusal);
		}

		// automatic-intake lot (2026-10-05): the landing triggers and the signal dataset disappear with the landing objects below - the engine must stop
		// looking at this project first (its chains would otherwise find the objects half deleted)
		Intake.forceOff(pSession, pFactory, project);

		final ObjectNode out = ProjectDeleter.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		final ArrayNode optionsOut = out.putArray("options");
		for (final String option : pOptions)
		{
			optionsOut.add(option);
		}
		final ArrayNode generated = out.putArray("generated");
		final List<String> failed = new ArrayList<String>();
		int keptBusinessObject = 0;
		boolean businessObjectFailed = false;
		boolean triggersFailed = false;
		for (final Item item : plan.items)
		{
			final ObjectNode g = generated.addObject();
			g.put("kind", item.kind.name());
			g.put("name", item.name);
			if (!pOptions.contains(item.option))
			{
				g.put("outcome", "NOT_SELECTED");
				continue;
			}
			if (!item.eligible)
			{
				g.put("outcome", "KEPT_INELIGIBLE");
				g.put("message", item.reason);
				continue;
			}
			// lot M18: the add-on needs the master model (and its dataspace) to delete a configuration cleanly, and a business-object row left without its model breaks the
			// add-on at the next start (CLAUDE.md section 8): when the business object could not be removed, the master model and dataspace are kept for the retry
			// lot S9: the distribution triggers could not all be removed - deleting a matching configuration now would break the master model's compile and strand the next ones
			if (triggersFailed && (item.kind == ProjectDeletionPlan.Kind.MATCHING_CONFIG || item.kind == ProjectDeletionPlan.Kind.MATCHING_BUSINESS_OBJECT
				|| item.kind == ProjectDeletionPlan.Kind.MASTER_MODEL || item.kind == ProjectDeletionPlan.Kind.MASTER_DATASPACE))
			{
				g.put("outcome", "KEPT_INELIGIBLE");
				g.put("message", "kept: the distribution triggers of the master model could not be removed, and deleting this now would break the model's compile (the Match and Merge linked "
					+ "fields they read disappear with a configuration) or leave configurations without their model. Fix the cause and retry.");
				continue;
			}
			if (businessObjectFailed && (item.kind == ProjectDeletionPlan.Kind.MASTER_MODEL || item.kind == ProjectDeletionPlan.Kind.MASTER_DATASPACE))
			{
				g.put("outcome", "KEPT_INELIGIBLE");
				g.put("message", "kept: the business object's configuration could not be removed from the add-on's dataset, and deleting the master " + (item.kind == ProjectDeletionPlan.Kind.MASTER_MODEL ? "model" : "dataspace")
					+ " now would leave it without its model (the add-on would fail at the next start). Fix the cause and retry.");
				continue;
			}
			final GeneratedObjectDeleter.Outcome outcome = ProjectDeleter.execute(pSession, pFactory, gathered.facts, item);
			g.put("outcome", outcome.result);
			if (outcome.message != null)
			{
				g.put("message", outcome.message);
			}
			if (outcome.code != null)
			{
				g.put("reasonCode", outcome.code);
			}
			if (outcome.answer != null && !outcome.answer.isEmpty())
			{
				g.put("answer", outcome.answer);
			}
			if (item.kind == ProjectDeletionPlan.Kind.MATCHING_CONFIG || item.kind == ProjectDeletionPlan.Kind.MATCHING_BUSINESS_OBJECT || item.kind == ProjectDeletionPlan.Kind.DISTRIBUTION_TRIGGERS)
			{
				// lot M18 (second round): the add-on's answer for each matching item reads itself in kernel.log (and in the job's message, ProjectDeleteRun#matchingNote)
				LoggingCategory.getKernel().info("[mdm-factory] project " + pProjectCode + " deletion: " + item.kind + " " + item.name + " -> " + outcome.result
					+ (outcome.message == null ? "" : ": " + outcome.message) + (outcome.answer == null || outcome.answer.isEmpty() ? "" : " | add-on answer: " + outcome.answer));
			}
			if ("FAILED".equals(outcome.result))
			{
				failed.add(item.kind + " " + item.name + ": " + outcome.message);
				businessObjectFailed |= item.kind == ProjectDeletionPlan.Kind.MATCHING_BUSINESS_OBJECT;
				triggersFailed |= item.kind == ProjectDeletionPlan.Kind.DISTRIBUTION_TRIGGERS;
			}
			if (GeneratedObjectDeleter.RESULT_KEPT_BUSINESS_OBJECT.equals(outcome.result))
			{
				keptBusinessObject++;
			}
		}
		// lot W13: the configurations the add-on will not delete (business-object members) are orphaned when the model or the dataspace goes
		final boolean modelOrDataspaceDeleted = pOptions.contains(ProjectDeletionPlan.OPTION_MODELS) || pOptions.contains(ProjectDeletionPlan.OPTION_DATASPACES);
		String orphanNote = GeneratedObjectDeleter.orphanedConfigurationNote(keptBusinessObject, modelOrDataspaceDeleted, gathered.facts.masterModelName);
		// lot M18: the read-back - what the add-on's dataset still holds for the master publication once the matching items ran (the live rows are the truth, not the outcomes)
		if (pOptions.contains(ProjectDeletionPlan.OPTION_MATCHING) && !gathered.facts.masterDataspaceStored.isEmpty())
		{
			final int[] left = MameConfigRows.remainingCounts(pFactory.getHome().getRepository(), gathered.facts.masterModelName);
			final ObjectNode leftNode = out.putObject("matchingConfigRowsLeft");
			leftNode.put("businessObjectConfigurations", left[0]);
			leftNode.put("tableConfigurations", left[1]);
			final ArrayNode leftRows = leftNode.putArray("rows");
			for (final String row : MameConfigRows.remaining(pFactory.getHome().getRepository(), gathered.facts.masterModelName))
			{
				leftRows.add(row);
			}
			if (left[0] + left[1] > 0)
			{
				orphanNote = MatchingConfigRows.leftSentence(gathered.facts.masterModelName, left[0], left[1], modelOrDataspaceDeleted);
			}
		}
		if (orphanNote != null)
		{
			out.put("orphanedMatchingConfiguration", orphanNote);
			LoggingCategory.getKernel().warn("[mdm-factory] project " + pProjectCode + " deletion: " + orphanNote);
		}
		// lot C1-D: a Centralisation project's generated domain (workflow publication and model, matching configuration, dataspace, form layouts, model)
		if (DomainCleanup.hasDomain(pFactory, project.getOccurrencePrimaryKey().format()))
		{
			out.set("domain", DomainCleanup.run(pSession, pFactory, project.getOccurrencePrimaryKey().format(), pOptions, failed));
		}
		if (!failed.isEmpty())
		{
			throw EngineRefusal.coded("conflict.deletionBlocked", "Project " + pProjectCode + " deletion stopped before removing its own rows - " + failed.size()
				+ " generated object(s) could not be removed: " + ProjectDeleter.join(failed, "; ") + ". None of the project's own rows were touched; fix the "
				+ "underlying issue and retry - the plan is recomputed from the live state on every call, and an object already gone is reported absent, never a "
				+ "failure.", pProjectCode);
		}

		// TODO (fix of 2026-10-12): a SCHEDULED target may name a scheduler schedule (scheduleKey) and a workflow launcher (launcherName). No code in this
		// module deletes either - DistributionScheduler only creates them and nothing calls it yet - so they are reported, never silently dropped.
		final ArrayNode schedulerLeftBehind = out.putArray("schedulerLeftBehind");
		for (final Adaptation target : gathered.rowsByTable.get("DistributionTarget"))
		{
			final String scheduleKey = ProjectDeleter.str(target.getString(FactoryPaths.DistributionTarget.SCHEDULE_KEY)).trim();
			final String launcherName = ProjectDeleter.str(target.getString(FactoryPaths.DistributionTarget.LAUNCHER_NAME)).trim();
			if (!scheduleKey.isEmpty() || !launcherName.isEmpty())
			{
				final String left = "distribution target " + target.getOccurrencePrimaryKey().format() + ": schedule '" + scheduleKey + "', workflow launcher '" + launcherName + "'";
				schedulerLeftBehind.add(left);
				LoggingCategory.getKernel().warn("[mdm-factory] project " + pProjectCode + " deletion removed the " + left + " row but not the scheduler schedule or launcher itself: delete them by hand.");
			}
		}
		final Map<String, Integer> rowsDeleted = ProjectDeleter.deleteFactoryRows(pSession, pFactory, project, gathered);
		ProjectDeleter.putOpenReviews(out, gathered.openReviews);
		if (!gathered.openReviews.isEmpty())
		{
			LoggingCategory.getKernel().warn("[mdm-factory] project " + pProjectCode + " deletion left " + gathered.openReviews.size()
				+ " agent review workflow instance(s) open (" + ProjectDeleter.join(gathered.openReviews, ", ") + "): end them in the workflow inbox.");
		}
		final ObjectNode rowsOut = out.putObject("rowsDeleted");
		for (final Map.Entry<String, Integer> entry : rowsDeleted.entrySet())
		{
			rowsOut.put(entry.getKey(), entry.getValue().intValue());
		}
		out.put("status", "DELETED");
		// the "Record lineage" record-level action's own activation cache (same rule ebx.MasterReset already
		// applies): the deleted project's tables must stop being offered without waiting out MasterZoneIndex's TTL.
		MasterZoneIndex.invalidate();
		LoggingCategory.getKernel().info("[mdm-factory] project " + pProjectCode + " deleted: options=" + pOptions + ", rowsDeleted=" + rowsDeleted);
		return out;
	}

	// ------------------------------------------------------------------------------------------------- per-item execution

	/**
	 * Step-reset lot (2026-09-23): the actual per-kind deletion mechanics now live in {@link GeneratedObjectDeleter},
	 * shared with {@code StepResetter} - a straight extraction, no behaviour change (see that class' own Javadoc).
	 */
	private static GeneratedObjectDeleter.Outcome execute(final Session pSession, final Adaptation pFactory, final Facts pFacts, final Item pItem)
	{
		try
		{
			switch (pItem.kind)
			{
				case DISTRIBUTION_TRIGGERS:
					// lot S9: the triggers read Linked_Field_State on every table that carries it; they go first, by name, then ONE publication
					return GeneratedObjectDeleter.deleteDistributionTriggers(pSession, pFactory, pFacts.masterModelName, pFacts.distributionTriggerTables);
				case MATCHING_BUSINESS_OBJECT:
					// lot M18: unlink the member table configurations and delete the business-object row, while the model still exists; the MATCHING_CONFIG items that follow
					// in the plan then find plain tables
					return GeneratedObjectDeleter.deleteBusinessObjectConfig(pSession, pFactory, pFacts.masterModelName, pItem.name);
				case MATCHING_CONFIG:
					// lot M18 (second round): the MAIN table of a business object is refused by the add-on while the published model still declares the object (javap, section 8 of
					// docs/PROJECT-DELETION.md): the declaration is removed from the model, the model republished, and the call made again - once
					return GeneratedObjectDeleter.deleteMatchingConfigUndeclaringBusinessObject(pSession, pFactory, pItem.name, pFacts.masterModelName);
				case LANDING_FLOW:
				case DISTRIBUTION_FLOW:
					return GeneratedObjectDeleter.deleteMapping(pSession, pFactory, pItem.name);
				case MASTER_DATASPACE:
				case STAGING_DATASPACE:
				case LANDING_DATASPACE:
					return GeneratedObjectDeleter.deleteDataspace(pSession, pFactory, pItem.name);
				case MASTER_MODEL:
				case STAGING_MODEL:
				case LANDING_MODEL:
					return GeneratedObjectDeleter.deleteDataModel(pSession, pFactory, pItem.name);
				case CATALOG_SOURCE:
				case CONNECTION:
				{
					// staging integration (2026-10-17, live: "Unknown item kind CATALOG_SOURCE" - lot C planned these items but never executed them)
					final ConnectionFacts connection = ProjectDeleter.connectionOf(pFacts, pItem);
					if (connection == null)
					{
						return GeneratedObjectDeleter.Outcome.failed("No proof this project created " + pItem.kind + " " + pItem.name + " any more: kept");
					}
					return pItem.kind == ProjectDeletionPlan.Kind.CATALOG_SOURCE ? ConnectionCleanup.deleteCatalogSource(pSession, pFactory, connection)
						: ConnectionCleanup.deleteConnection(pSession, pFactory, connection);
				}
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

	/** The {@link ConnectionFacts} a CATALOG_SOURCE item (named by its source id) or a CONNECTION item (named by its connection id) was built from; null when none. */
	private static ConnectionFacts connectionOf(final Facts pFacts, final Item pItem)
	{
		for (final ConnectionFacts connection : pFacts.connections)
		{
			final String name = pItem.kind == ProjectDeletionPlan.Kind.CATALOG_SOURCE ? connection.catalogSourceId : connection.connectionId;
			if (name.equals(pItem.name))
			{
				return connection;
			}
		}
		return null;
	}

	// ============================================================================================== gathering the facts

	/** {@link Facts} plus the raw rows the factory-row deletion will need - gathered once, reused by both {@link #plan} and {@link #delete} (the generated-object deletion phase never touches these rows, so the same snapshot stays valid through it). */
	private static final class Gathered
	{
		final Facts facts;
		/** Deletion order (children before parents), {@code Project} itself NOT included - see the class Javadoc. */
		final Map<String, List<Adaptation>> rowsByTable;

		/** Audit F3-10: the review workflow instances ({@code AgentRun.escalation}) still open for an agent decision of this project; they are NOT terminated by the deletion. */
		final List<String> openReviews;

		private Gathered(final Facts pFacts, final Map<String, List<Adaptation>> pRowsByTable, final List<String> pOpenReviews)
		{
			this.facts = pFacts;
			this.rowsByTable = pRowsByTable;
			this.openReviews = pOpenReviews;
		}
	}

	private static Gathered gather(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final String pProjectCode) throws OperationException
	{
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final String mode = pProject.getString(FactoryPaths.Project.MODE);
		final boolean delivered = ProjectOrigin.isDelivered(pFactory, pProject);

		final String landingDataspaceStored = ProjectDeleter.str(pProject.getString(FactoryPaths.Project.LANDING_DATASPACE));
		final String landingDataspaceComputed = LandingNames.dataspaceKey(pProjectCode, LandingGenerator.landingSuffix(pFactory));

		final String stagingDataspaceStored = ProjectDeleter.str(pProject.getString(FactoryPaths.Project.STAGING_DATASPACE));
		final String stagingDatasetStored = ProjectDeleter.str(pProject.getString(FactoryPaths.Project.STAGING_DATASET));
		final String stagingDataspaceComputed = StagingNames.dataspaceKey(pProjectCode, StagingGenerator.stagingSuffix(pFactory));
		final boolean stagingModelStepDone = ProjectDeleter.stepEverDone(pFactory, projectId, "STAGING_MODEL", "");
		final boolean stagingDatasetCreatedByFactory = ProjectDeleter.datasetCreatedByFactory(pFactory, projectId, "STAGING_DATASET", "");

		final String masterDataspaceStored = ProjectDeleter.str(pProject.getString(FactoryPaths.Project.MASTER_DATASPACE));
		final String masterDatasetStored = ProjectDeleter.str(pProject.getString(FactoryPaths.Project.MASTER_DATASET));
		final String masterDataspaceComputed = MasterNames.dataspaceKey(pProjectCode, MasterGenerator.masterSuffix(pFactory));
		final boolean masterModelStepDone = ProjectDeleter.stepEverDone(pFactory, projectId, "MASTER_MODEL", "");
		final boolean masterDatasetCreatedByFactory = ProjectDeleter.datasetCreatedByFactory(pFactory, projectId, "MASTER_DATASET", "");

		// ---- the factory's own rows, children before parents, one read pass (parents read first so their ids
		// are known to scope the next level's query) - see Gathered's own Javadoc.
		// volume lot 2a (2026-09-28, docs/VOLUME.md, task 6): Xref and TransferReject (one row per staged/
		// master record - genuinely volume-scale, unlike every other table gathered here) are NO LONGER
		// materialized into a List at all: their row COUNT (for rowCounts, read by plan() only) comes from
		// RequestResult.getSize() below, and their actual deletion (delete() only) streams and batches through
		// VolumeBatchOps in #deleteFactoryRows - never a List<Adaptation>, never one giant Procedure.
		final List<Adaptation> pipelineSteps = ProjectDeleter.queryEq(pFactory, FactoryPaths.PIPELINE_STEP, "./project", projectId);
		// audit F3-10 / F3-103 (2026-11-12): the three tables of the agentic lot. A definition has a required foreign key to Project; a run, a proposal and a step reach the project
		// through the definition (agent) or the run, so each is read by every way it can belong to the project and merged (a run written without ./project still goes)
		final List<Adaptation> agentDefinitions = ProjectDeleter.queryEq(pFactory, FactoryPaths.AGENT_DEFINITION, "./project", projectId);
		final List<String> agentCodes = ProjectDeleter.ids(agentDefinitions);
		final List<Adaptation> agentRuns = ProjectDeleter.union(ProjectDeleter.queryEq(pFactory, FactoryPaths.AGENT_RUN, "./project", projectId),
			ProjectDeleter.queryIn(pFactory, FactoryPaths.AGENT_RUN, "./agent", agentCodes));
		final List<Adaptation> agentSteps = ProjectDeleter.queryIn(pFactory, FactoryPaths.AGENT_STEP, "./run", ProjectDeleter.ids(agentRuns));
		final List<Adaptation> agentProposals = ProjectDeleter.union(ProjectDeleter.queryEq(pFactory, FactoryPaths.AGENT_PROPOSAL, "./project", projectId),
			ProjectDeleter.queryIn(pFactory, FactoryPaths.AGENT_PROPOSAL, "./run", ProjectDeleter.ids(agentRuns)), ProjectDeleter.queryIn(pFactory, FactoryPaths.AGENT_PROPOSAL, "./agent", agentCodes));
		final List<String> openReviews = ProjectDeleter.openReviews(agentRuns, agentProposals);
		final List<Adaptation> projectSources = ProjectDeleter.queryEq(pFactory, FactoryPaths.PROJECT_SOURCE, "./project", projectId);
		final List<String> sourceIds = ProjectDeleter.ids(projectSources);
		final List<Adaptation> landingTables = ProjectDeleter.queryIn(pFactory, FactoryPaths.LANDING_TABLE, "./projectSource", sourceIds);
		final List<Adaptation> landingRelations = ProjectDeleter.queryIn(pFactory, FactoryPaths.LANDING_RELATION, "./projectSource", sourceIds);
		final List<Adaptation> landingColumns = ProjectDeleter.queryIn(pFactory, FactoryPaths.LANDING_COLUMN, "./landingTable", ProjectDeleter.ids(landingTables));
		// audit F3-10: the schema drifts of the landing tables (required foreign key to LandingTable); also the ones that name the project only
		final List<Adaptation> sourceDrifts = ProjectDeleter.union(ProjectDeleter.queryIn(pFactory, FactoryPaths.SOURCE_DRIFT, "./landingTable", ProjectDeleter.ids(landingTables)),
			ProjectDeleter.queryEq(pFactory, FactoryPaths.SOURCE_DRIFT, "./project", projectId));
		final List<Adaptation> stagingEntities = ProjectDeleter.queryEq(pFactory, FactoryPaths.STAGING_ENTITY, "./project", projectId);
		final List<String> entityIds = ProjectDeleter.ids(stagingEntities);
		final List<Adaptation> stagingAttributes = ProjectDeleter.queryIn(pFactory, FactoryPaths.STAGING_ATTRIBUTE, "./entity", entityIds);
		final List<String> attributeIds = ProjectDeleter.ids(stagingAttributes);
		final List<Adaptation> matchPlans = ProjectDeleter.queryIn(pFactory, FactoryPaths.MATCH_PLAN, "./entity", entityIds);
		final List<Adaptation> transferFeeds = ProjectDeleter.queryIn(pFactory, FactoryPaths.TRANSFER_FEED, "./entity", entityIds);
		final List<Adaptation> capabilitySuggestions = ProjectDeleter.queryEq(pFactory, FactoryPaths.CAPABILITY_SUGGESTION, "./project", projectId);
		final List<Adaptation> qualityRules = ProjectDeleter.queryIn(pFactory, FactoryPaths.QUALITY_RULE, "./entity", entityIds);
		final List<Adaptation> attributeMappings = ProjectDeleter.queryIn(pFactory, FactoryPaths.ATTRIBUTE_MAPPING, "./attribute", attributeIds);
		final List<Adaptation> mappingSteps = ProjectDeleter.queryIn(pFactory, FactoryPaths.MAPPING_STEP, "./mapping", ProjectDeleter.ids(attributeMappings));
		// fix of 2026-10-12: the distribution targets (a required FK to Project, which EBX let go and left dangling) and the pauses the factory recorded on them
		final List<Adaptation> distributionTargets = ProjectDeleter.queryEq(pFactory, FactoryPaths.DISTRIBUTION_TARGET, "./project", projectId);
		final List<Adaptation> loadPauses = ProjectDeleter.drain(pFactory.getTable(FactoryPaths.LOAD_PAUSE).createRequestResult("./project=" + CatalogReader.quote(projectId)
			+ " and ./kind=" + CatalogReader.quote(FactoryPaths.LoadPause.KIND_DISTRIBUTION_TARGET)));

		final Map<String, List<Adaptation>> rowsByTable = new LinkedHashMap<String, List<Adaptation>>();
		rowsByTable.put("MappingStep", mappingSteps);
		rowsByTable.put("AttributeMapping", attributeMappings);
		rowsByTable.put("QualityRule", qualityRules);
		rowsByTable.put("CapabilitySuggestion", capabilitySuggestions);
		rowsByTable.put("MatchPlan", matchPlans);
		// step-reset lot (2026-09-23): TransferFeed rows were never queried/deleted here - a project deletion left
		// them dangling on a StagingEntity/AttributeMapping the same call just removed. Deleted between
		// AttributeMapping (which references a feed) and StagingAttribute/StagingEntity (which a feed references).
		rowsByTable.put("TransferFeed", transferFeeds);
		rowsByTable.put("StagingAttribute", stagingAttributes);
		rowsByTable.put("StagingEntity", stagingEntities);
		rowsByTable.put("LandingColumn", landingColumns);
		rowsByTable.put("LandingRelation", landingRelations);
		rowsByTable.put("LandingTable", landingTables);
		rowsByTable.put("ProjectSource", projectSources);
		rowsByTable.put("SourceDrift", sourceDrifts);
		rowsByTable.put("AgentStep", agentSteps);
		rowsByTable.put("AgentProposal", agentProposals);
		rowsByTable.put("AgentRun", agentRuns);
		rowsByTable.put("AgentDefinition", agentDefinitions);
		rowsByTable.put("PipelineStep", pipelineSteps);
		// no FK between these and the tables above (DistributionEvent, which points at a target, is deleted before this Procedure, by project)
		rowsByTable.put("LoadPause", loadPauses);
		rowsByTable.put("DistributionTarget", distributionTargets);

		// ---- per-source landing facts: inboundMapping ids of each source's own landing tables
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
			final String sourceSystem = ProjectDeleter.str(row.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM));
			final String landingModel = ProjectDeleter.str(row.getString(FactoryPaths.ProjectSource.LANDING_MODEL));
			final String landingDataset = ProjectDeleter.str(row.getString(FactoryPaths.ProjectSource.LANDING_DATASET));
			final boolean landingModelStepDone = ProjectDeleter.stepEverDone(pFactory, projectId, "LANDING_MODEL", sourceSystem);
			final boolean landingDatasetCreatedByFactory = ProjectDeleter.datasetCreatedByFactory(pFactory, projectId, "LANDING_DATASET", sourceSystem);
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

		// ---- matching facts: every PROMOTED master entity's live get_matching_config answer, only when a master
		// dataspace exists at all - the exact same set ebx.MasterReset's own promotedMasterTablePaths recomputes.
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
				final boolean configured = ProjectDeleter.isConfigured(pSession, pFactory, tablePath, masterModelName);
				matching.add(new MatchingFacts(entityName, tablePath, configured));
			}
		}

		final List<TargetFacts> targets = ProjectDeleter.targetFacts(pSession, pFactory, distributionTargets);
		// lot C (2026-10-17): the sample-file connections and catalogue sources the factory has proof it created for the project
		final List<ConnectionFacts> connections = ConnectionCleanup.gather(pFactory, projectId);

		// audit F3-10: the deletion order is ONE list (children before the tables their foreign keys name), pinned by ProjectDeletionPlanTest
		final Map<String, List<Adaptation>> ordered = ProjectDeletionPlan.inDeletionOrder(rowsByTable);
		rowsByTable.clear();
		rowsByTable.putAll(ordered);
		final Map<String, Integer> rowCounts = new LinkedHashMap<String, Integer>();
		for (final Map.Entry<String, List<Adaptation>> entry : rowsByTable.entrySet())
		{
			rowCounts.put(entry.getKey(), Integer.valueOf(entry.getValue().size()));
		}
		// volume lot 2a (2026-09-28, docs/VOLUME.md, task 6): counted via RequestResult.getSize(), never by
		// collecting rows - see the comment above rowsByTable's own construction for why these two are not in it.
		rowCounts.put("Xref", Integer.valueOf(ProjectDeleter.countEq(pFactory, FactoryPaths.XREF, "./project", projectId)));
		rowCounts.put("TransferReject", Integer.valueOf(ProjectDeleter.countEq(pFactory, FactoryPaths.TRANSFER_REJECT, "./project", projectId)));
		// volume-operations lot 8a (2026-09-28, task 5): same reasoning, same counting technique.
		rowCounts.put("DistributionEvent", Integer.valueOf(ProjectDeleter.countEq(pFactory, FactoryPaths.DISTRIBUTION_EVENT, "./project", projectId)));
		// automatic-intake lot (2026-10-05): same treatment
		rowCounts.put("IntakeChain", Integer.valueOf(ProjectDeleter.countEq(pFactory, FactoryPaths.INTAKE_CHAIN, "./project", projectId)));
		// lot C1-B: the CENTRALISATION path's three tables (plain FKs to Project; DomainObject also points to a PipelineStep)
		rowCounts.put("DomainObject", Integer.valueOf(ProjectDeleter.countEq(pFactory, FactoryPaths.DOMAIN_OBJECT, "./project", projectId)));
		rowCounts.put("Difference", Integer.valueOf(ProjectDeleter.countEq(pFactory, FactoryPaths.DIFFERENCE, "./project", projectId)));
		rowCounts.put("ProjectPack", Integer.valueOf(ProjectDeleter.countEq(pFactory, FactoryPaths.PROJECT_PACK, "./project", projectId)));
		rowCounts.put("Project", Integer.valueOf(1));

		final Facts facts = new Facts(pProjectCode, mode, delivered, landingDataspaceStored, landingDataspaceComputed, stagingDataspaceStored, stagingDatasetStored,
			stagingDataspaceComputed, StagingNames.DATASET, stagingModelStepDone, stagingDatasetCreatedByFactory, masterDataspaceStored, masterDatasetStored,
			masterDataspaceComputed, MasterNames.DATASET, masterModelStepDone, masterDatasetCreatedByFactory, sources, matching, targets, connections, masterModelName,
			StagingNames.modelName(pProjectCode), rowCounts);
		// lot M18 (2026-10-18): the Match and Merge business objects of the master publication, read straight from the add-on's configuration dataset (no MCP call)
		if (!masterDataspaceStored.isEmpty())
		{
			facts.businessObjects = MameConfigRows.businessObjectsOf(pFactory.getHome().getRepository(), masterModelName);
			// lot S9 (2026-10-18): the distribution triggers the factory declared on the promoted master tables; known from the install step (any status: a failed install may have declared some)
			if (ProjectDeleter.stepEverRecorded(pFactory, projectId, DistributionTriggerInstaller.STEP))
			{
				facts.distributionTriggerTables = new ArrayList<String>(ProjectMasterEntities.promotedMasterTablePaths(pFactory, projectId));
			}
		}
		return new Gathered(facts, rowsByTable, openReviews);
	}

	/**
	 * The distribution targets of the project and, for each mapping id of their {@code connectorsMappingIds}, whose mapping it is (one {@code
	 * connectors_mapping_get} per id, see {@link DistributionFlowOwnership}). A scheduler schedule or workflow launcher a SCHEDULED target
	 * names ({@code scheduleKey}, {@code launcherName}) is NOT removed: no code in this module deletes one ({@code DistributionScheduler}
	 * only creates them, and nothing calls it yet) - {@link #deleteHoldingProject} reports a target that names one before its row goes.
	 */
	private static List<TargetFacts> targetFacts(final Session pSession, final Adaptation pFactory, final List<Adaptation> pTargetRows)
	{
		final Map<String, String> tablePathByMappingId = new LinkedHashMap<String, String>();
		final Map<String, Map<String, String>> mappingsByTarget = new LinkedHashMap<String, Map<String, String>>();
		for (final Adaptation row : pTargetRows)
		{
			final String targetId = row.getOccurrencePrimaryKey().format();
			final Map<String, String> byTable = TargetMappingList.parse(row.getString(FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS));
			mappingsByTarget.put(targetId, byTable);
			for (final Map.Entry<String, String> entry : byTable.entrySet())
			{
				tablePathByMappingId.put(entry.getValue(), entry.getKey());
			}
		}
		final Map<String, TargetFlowFacts> evidence = DistributionFlowOwnership.read(pSession, pFactory, tablePathByMappingId);
		final List<TargetFacts> out = new ArrayList<TargetFacts>();
		for (final Map.Entry<String, Map<String, String>> target : mappingsByTarget.entrySet())
		{
			final List<TargetFlowFacts> flows = new ArrayList<TargetFlowFacts>();
			for (final String mappingId : target.getValue().values())
			{
				flows.add(evidence.get(mappingId));
			}
			out.add(new TargetFacts(target.getKey(), flows));
		}
		return out;
	}

	/**
	 * A best-effort probe: {@code get_matching_config}, {@code dataModel} sent unconditionally via {@link
	 * MameCalls#call} from {@code pMasterModelName} (`docs/MATCHING.md` &sect;28, 2026-09-23 14:20 - never
	 * omitted-until-ambiguous any more). A transport error (the server unreachable) OR an {@code AMBIGUOUS_TABLE}
	 * {@link MameCalls#call} itself could not resolve is read as "not configured" here - this only feeds the
	 * PLANNING read (what would there be to delete), never the actual deletion, which re-probes for real and
	 * reports its own failure honestly if the server is still unreachable at that point.
	 */
	private static boolean isConfigured(final Session pSession, final Adaptation pFactory, final String pTablePath, final String pMasterModelName) throws OperationException
	{
		try
		{
			final McpClient client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
			final ObjectNode args = ProjectDeleter.MAPPER.createObjectNode();
			args.put("table", pTablePath);
			final McpClient.ToolResult result = MameCalls.call(client, "get_matching_config", args, pMasterModelName);
			return MameAnswer.success(result.json, false);
		}
		catch (final McpException ex)
		{
			return false;
		}
		catch (final OperationException ex)
		{
			// same best-effort discipline as the McpException catch above (method Javadoc) - an AMBIGUOUS_TABLE
			// MameCalls itself could not resolve reads as "not configured" for this planning-only probe.
			return false;
		}
	}

	// ---------------------------------------------------------------------------------------------------- evidence reads

	/** True when the project has at least one {@code PipelineStep} row of that name, whatever its scope and status. */
	private static boolean stepEverRecorded(final Adaptation pFactory, final String pProjectId, final String pStep)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(pStep));
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/** Generalised from {@code ebx.MasterReset#stepEverDone}: any step name, any scope (never {@code ./scope=''} for a blank scope - {@code PipelineSteps.scopeEquals}'s own Javadoc explains why). */
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

	/** Generalised from {@code ebx.MasterReset#masterDatasetCreatedByFactory}: any step name, any scope - true when at least one DONE step of that name/scope recorded {@code dataspace.created == true} in its own detail JSON. */
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
					final JsonNode detail = ProjectDeleter.MAPPER.readTree(detailJson);
					if (detail.path("dataspace").path("created").asBoolean(false))
					{
						return true;
					}
				}
				catch (final java.io.IOException ex)
				{
					// not valid JSON - should not happen, every generator here always writes one; never treated as evidence
				}
			}
			return false;
		}
		finally
		{
			rows.close();
		}
	}

	// -------------------------------------------------------------------------------------------------------------- misc

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
		return ProjectDeleter.drain(rows);
	}

	/** {@code RequestResult.getSize()}, never a drained {@code List} - volume lot 2a (2026-09-28, docs/VOLUME.md, task 6): a plain count for {@link #plan}'s own preview. */
	private static int countEq(final Adaptation pFactory, final Path pTable, final String pFieldPath, final String pValue)
	{
		final RequestResult rows = pFactory.getTable(pTable).createRequestResult(pFieldPath + "=" + CatalogReader.quote(pValue));
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	/** An OR-chain of {@code pFieldPath=id} terms - the same idiom {@code master.MatchingRunScope} already uses to scope a query to several primary keys at once (EBX xpath has no {@code IN} operator). Empty {@code pIds}: no query at all, an empty list. */
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
		return ProjectDeleter.drain(rows);
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

	/** The rows of several reads, each row once (by primary key), in the order met; the same row can be reached by its project, its run and its agent. */
	@SafeVarargs
	private static List<Adaptation> union(final List<Adaptation>... pLists)
	{
		final Map<String, Adaptation> byKey = new LinkedHashMap<String, Adaptation>();
		for (final List<Adaptation> list : pLists)
		{
			for (final Adaptation row : list)
			{
				final String key = row.getOccurrencePrimaryKey().format();
				if (!byKey.containsKey(key))
				{
					byKey.put(key, row);
				}
			}
		}
		return new ArrayList<Adaptation>(byKey.values());
	}

	/** Audit F3-10: the review workflow instance keys of the runs that still have a decision waiting ({@link FactoryRetention#proposalPending}); one key once. */
	private static List<String> openReviews(final List<Adaptation> pRuns, final List<Adaptation> pProposals)
	{
		final java.util.Set<String> runsWithPending = new java.util.HashSet<String>();
		for (final Adaptation proposal : pProposals)
		{
			if (FactoryRetention.proposalPending(proposal.getString(FactoryPaths.AgentProposal.STATUS), Boolean.TRUE.equals(proposal.get(FactoryPaths.AgentProposal.APPLY_AFTER_APPROVAL)),
				proposal.get(FactoryPaths.AgentProposal.DECIDED_AT) != null))
			{
				runsWithPending.add(proposal.getString(FactoryPaths.AgentProposal.RUN));
			}
		}
		final java.util.Set<String> keys = new java.util.LinkedHashSet<String>();
		for (final Adaptation run : pRuns)
		{
			final String escalation = run.getString(FactoryPaths.AgentRun.ESCALATION);
			if (escalation != null && !escalation.trim().isEmpty() && runsWithPending.contains(run.getOccurrencePrimaryKey().format()))
			{
				keys.add(escalation.trim());
			}
		}
		return new ArrayList<String>(keys);
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

	// ======================================================================================================= factory rows

	/**
	 * Children before parents - see the class Javadoc for the exact order and why. {@code Xref}/
	 * {@code TransferReject} (one row per staged/master record - genuinely volume-scale, unlike every other
	 * table here) are deleted FIRST, streamed and batched through {@link VolumeBatchOps} (volume lot 2a,
	 * 2026-09-28, docs/VOLUME.md, task 6) - never a {@code List<Adaptation>}, never inside the single
	 * {@link Procedure} that still handles every other (model-scale, bounded) table below in one transaction.
	 */
	private static Map<String, Integer> deleteFactoryRows(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final Gathered pGathered)
		throws OperationException
	{
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		final String projectEquals = "./project=" + CatalogReader.quote(projectId);
		final int xrefDeleted = VolumeBatchOps.forEachBatched(pSession, pFactory.getHome(), pFactory.getTable(FactoryPaths.XREF), projectEquals,
			"Project " + projectId + " deletion: deleting Xref rows failed", new VolumeBatchOps.RowOp()
			{
				@Override
				public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
				{
					pContext.doDelete(pRow.getAdaptationName(), false);
				}
			});
		counts.put("Xref", Integer.valueOf(xrefDeleted));
		final int transferRejectDeleted = VolumeBatchOps.forEachBatched(pSession, pFactory.getHome(), pFactory.getTable(FactoryPaths.TRANSFER_REJECT), projectEquals,
			"Project " + projectId + " deletion: deleting TransferReject rows failed", new VolumeBatchOps.RowOp()
			{
				@Override
				public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
				{
					pContext.doDelete(pRow.getAdaptationName(), false);
				}
			});
		counts.put("TransferReject", Integer.valueOf(transferRejectDeleted));
		// volume-operations lot 8a (2026-09-28, task 5): same treatment as Xref/TransferReject above - see the
		// class Javadoc.
		final int distributionEventsDeleted = VolumeBatchOps.forEachBatched(pSession, pFactory.getHome(), pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT), projectEquals,
			"Project " + projectId + " deletion: deleting DistributionEvent rows failed", new VolumeBatchOps.RowOp()
			{
				@Override
				public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
				{
					pContext.doDelete(pRow.getAdaptationName(), false);
				}
			});
		counts.put("DistributionEvent", Integer.valueOf(distributionEventsDeleted));
		// automatic-intake lot (2026-10-05): the chains of the project (a plain FK to Project, like DistributionEvent)
		final int intakeChainsDeleted = VolumeBatchOps.forEachBatched(pSession, pFactory.getHome(), pFactory.getTable(FactoryPaths.INTAKE_CHAIN), projectEquals,
			"Project " + projectId + " deletion: deleting IntakeChain rows failed", new VolumeBatchOps.RowOp()
			{
				@Override
				public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
				{
					pContext.doDelete(pRow.getAdaptationName(), false);
				}
			});
		counts.put("IntakeChain", Integer.valueOf(intakeChainsDeleted));
		// lot C1-B: DomainObject first (it points to a PipelineStep, deleted below), then Difference and ProjectPack. The objects the generation wrote in ON EBX
		// (models, datasets, workflows...) are removed by sub-lot D's GeneratedObjectDeleter before this point; these are the factory's own rows.
		for (final Object[] one : new Object[][] { { FactoryPaths.DOMAIN_OBJECT, "DomainObject" }, { FactoryPaths.DIFFERENCE, "Difference" }, { FactoryPaths.PROJECT_PACK, "ProjectPack" } })
		{
			final String tableName = (String) one[1];
			counts.put(tableName, Integer.valueOf(VolumeBatchOps.forEachBatched(pSession, pFactory.getHome(), pFactory.getTable((com.orchestranetworks.schema.Path) one[0]), projectEquals,
				"Project " + projectId + " deletion: deleting " + tableName + " rows failed", new VolumeBatchOps.RowOp()
				{
					@Override
					public void apply(final ProcedureContext pContext, final Adaptation pRow) throws Exception
					{
						pContext.doDelete(pRow.getAdaptationName(), false);
					}
				})));
		}

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
				pContext.doDelete(pProject.getAdaptationName(), false);
				counts.put("Project", Integer.valueOf(1));
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.deleteRows", "Generated objects were removed, but deleting the project's own rows failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		return counts;
	}
}
