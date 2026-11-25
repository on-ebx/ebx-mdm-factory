package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ChangeColumnRule;
import com.on.ebx.factory.landing.LandingFlow;
import com.on.ebx.factory.landing.LoadDeadline;
import com.on.ebx.factory.landing.ProgressNote;
import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.landing.ReadRecipe;
import com.on.ebx.factory.landing.RunStateOutcome;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
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
 * Turns the landing plan of one project source into a real {@code ebx-connectors} flow, then loads it:
 * <ul>
 * <li>{@link #generate} - one root INBOUND mapping per landing table ({@link LandingFlow#mapping}),
 * written through {@code connectors_mapping_upsert} (a clean {@code dryRun} first, then for real), the
 * generated mapping id stored on {@code LandingTable.inboundMapping}. One {@code PipelineStep}
 * {@code LANDING_FLOWS} per project source (scope = source system); a table whose flow fails to write does
 * not stop the others, and the step is marked {@code FAILED} when any table did. Skipped when that step
 * already ran {@code DONE}, unless {@code pForce} is true (a mapping upsert is idempotent on its label - which
 * carries the project code since 2026-10-03, {@link LandingFlow#label} - and a mapping the table already owns is
 * updated by id, so forcing safely regenerates and updates the mappings already written, renaming one written
 * under the old, project-less label; see {@link #ownedMappingId}).</li>
 * <li>{@link #load} - one {@code connectors_sync} feed run per landing table that already has a mapping,
 * with a load id minted by the factory and a generous, explicit {@code max} ({@link LandingFlow#SYNC_MAX_ROWS}:
 * see the knowledge base for why {@code connectors_sync}'s own default silently caps a load at 200 rows). One
 * {@code PipelineStep} {@code LANDING_LOAD} per project source; again, one table's failure does not stop
 * the others, and the step is {@code FAILED} when any table did, with a message saying how many.
 * <p>
 * <b>Volume lot 7c (2026-09-28, "asynchronous landing load"):</b> the {@code connectors_sync} call is now
 * {@code async: true} - it answers at once with a run id, and {@link #awaitLoad} polls {@code
 * connectors_run_get} every {@link #LOAD_POLL_INTERVAL_MS} to that run's own terminal state instead of
 * blocking on one long synchronous call (which used to tie up this thread, and the {@code ebx-connectors}
 * MCP client's own {@code McpServers#READ_TIMEOUT_MS} read timeout, for as long as the whole table took to
 * copy). Checked for a stop between polls via the optional {@link FactoryJobs.Handle} overload: on a job
 * asking to stop, {@code connectors_run_stop} is called and the whole {@code LANDING_LOAD} step ends {@code
 * STOPPED} rather than starting the next table. An older Connectors build that does not know {@code async}
 * ignores it and answers the complete, already-terminal synchronous shape directly - {@link
 * RunStateOutcome#isAsyncStart} tells the two apart from the shape of the answer, never from a version
 * number, and the pre-lot synchronous handling ({@link LandingFlow#loadOutcome}) still runs unchanged in
 * that case. See docs/VOLUME.md "Lot 7c" and docs/LANDING-FLOWS.md for the full contract this rests on.</li>
 * </ul>
 * Unlike {@code LandingGenerator}'s {@code LANDING_MODEL}/{@code LANDING_DATASET} steps, {@link #load} is
 * <b>not</b> skipped when a {@code LANDING_LOAD} step already ran {@code DONE} for the same source: a load
 * is a refresh a project owner repeats on demand, not a one-time setup action - see {@link PipelineSteps}
 * for the resumable, skip-if-done semantics {@link #generate} does use.
 * <p>
 * EBX writes ({@code PipelineStep}, {@code LandingTable}) act with the rights of the session given to each
 * method. The MCP calls to {@code ebx-connectors} act as the factory's one agent account
 * ({@link McpServers}) instead: what that account may do on this instance is what generation and loading
 * may do.
 */
public final class LandingFlows
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String STEP_LANDING_FLOWS = "LANDING_FLOWS";
	private static final String STEP_LANDING_LOAD = "LANDING_LOAD";

	/**
	 * Volume lot 7c (2026-09-28, "asynchronous landing load"): how long {@link #awaitLoad} sleeps between two
	 * {@code connectors_run_get} calls while a table's {@code connectors_sync(async: true)} run is still
	 * RUNNING - the task brief's own "N = 5 seconds". A plain constant, not a {@code Settings} field: no such
	 * field exists yet, and adding one is an XSD/restart change out of proportion for a sleep interval alone (the distribution send polls at the same
	 * interval, wave 13: {@code DistributionSendCalls}) -
	 * same reasoning docs/VOLUME.md "Lot 8a" task 4 documents for {@code DistributionDispatcher.DRAIN_BATCH_CAP}.
	 */
	static final long LOAD_POLL_INTERVAL_MS = 5000L;

	/** {@link #summaryDetail}: at most this many non-OK/non-NO_CHANGE result or error messages kept per table, per {@code connectors_sync}/{@code connectors_run_get} call. */
	private static final int DETAIL_ERROR_SAMPLE_MAX = 5;

	/**
	 * Landing tables eligible for a flow. GENERATED and LOADED are the obvious cases (an EBX table exists);
	 * DRIFTED is included too - {@code LandingPlanner.drift()} only flags the table and changes its status,
	 * it never touches the table's own {@code LandingColumn} rows, so the columns a flow would be generated
	 * from are exactly the ones the currently published landing model actually has. The risk a DRIFTED
	 * table carries is at the SQL/source level (a column removed at the source makes the generated SELECT
	 * fail), not at the EBX field level, and that failure is a clear, diagnosable connectors_sync error, not
	 * a silent one - so generating from a DRIFTED table's frozen plan is safe enough to allow rather than
	 * force a user to regenerate the landing model before they can even see that error. PROPOSED tables have
	 * no EBX table yet and are always excluded.
	 * <p>
	 * FAILED is included too (volume-test bug, project PersVol, 2026-09-24): {@code LandingTable.STATUS}
	 * only ever turns FAILED from {@link #writeFlowFailure} (this class) or the load-step write-back
	 * ({@link #writeLoadOutcome}), and BOTH only ever run on a table already past {@link
	 * #writeBackSuccess}-equivalent - i.e. one whose landing MODEL and dataset already exist ({@code
	 * LandingGenerator#writeBackSuccess} is the only place that sets a table row to GENERATED, and it is the
	 * one precondition for either write-back above to even be reached). A table whose MODEL step itself
	 * failed never gets an individual {@code LandingTable} row turned FAILED this way - generation stops
	 * before {@code writeBackSuccess} runs and only {@code ProjectSource.status} turns FAILED ({@code
	 * LandingGenerator#failSource}), leaving any of its table rows at their PROPOSED default, which stays
	 * excluded. So a FAILED row here always means "the flow or the load failed, not the model/dataset" -
	 * exactly the case a person re-running the flow step (after fixing whatever the flow failed on, e.g. a
	 * connector transiently unreachable) needs to retry without first having to re-run the model/dataset step
	 * as a workaround (as project PersVol had to before this fix - {@code landing/generate} answering
	 * {@code SKIPPED_ALREADY_DONE} on the model/dataset steps still put every table back to GENERATED as a
	 * side effect of {@code writeBackSuccess} always re-running the per-table write-back loop).
	 */
	private static final Set<String> FLOW_ELIGIBLE_STATUSES = new HashSet<String>(Arrays.asList("GENERATED", "LOADED", "DRIFTED", "FAILED"));

	/**
	 * Pure wrapper around {@link #FLOW_ELIGIBLE_STATUSES} (no EBX type in the signature) so the eligibility
	 * rule itself - including the FAILED case documented on that field - is unit-testable without a live
	 * repository, same discipline as {@code MatchingActivationGuard}'s own pure decision methods. Package-
	 * visible for {@code LandingFlowsEligibilityTest}.
	 */
	static boolean isFlowEligibleStatus(final String pStatus)
	{
		return LandingFlows.FLOW_ELIGIBLE_STATUSES.contains(pStatus);
	}

	private LandingFlows()
	{
	}

	// =================================================================================================== generate

	/** @param pForce true bypasses the "LANDING_FLOWS already DONE" skip and records a new step: the mapping upsert is idempotent on its label (project-specific since 2026-10-03) and updates by id the mapping a table already owns, so this safely regenerates every eligible table's flow from the current plan/SQL generator, updating the mappings already written in place. */
	public static ObjectNode generate(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final boolean pForce) throws OperationException
	{
		final Adaptation source = LandingFlows.source(pFactory, pProjectSourceId);
		final String sourceSystem = source.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		final String projectId = source.getString(FactoryPaths.ProjectSource.PROJECT);
		final Adaptation project = LandingFlows.project(pFactory, projectId, pProjectSourceId);
		final String projectCode = project.getString(FactoryPaths.Project.CODE);
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		if (landingDataspace == null || landingDataspace.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.landingZone", "Project source " + pProjectSourceId
				+ " has no landing dataspace yet: run POST /sources/" + pProjectSourceId + "/landing/generate (the data model step) first", pProjectSourceId);
		}
		// the landing dataset is whatever LandingGenerator actually named it (ProjectSource.landingDataset),
		// not assumed to equal sourceSystem even though that is what it is named today - see LandingFlow.Table.
		final String landingDataset = source.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
		if (landingDataset == null || landingDataset.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.landingZone", "Project source " + pProjectSourceId
				+ " has no landing dataset yet: run POST /sources/" + pProjectSourceId + "/landing/generate (the data model step) first", pProjectSourceId);
		}
		final String connectorId = source.getString(FactoryPaths.ProjectSource.CONNECTOR);
		if (connectorId == null || connectorId.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noConnector", "Project source " + pProjectSourceId + " names no connector: set ProjectSource.connector before generating its landing flows", pProjectSourceId);
		}

		final List<FlowTable> tables = LandingFlows.flowTables(pFactory, pProjectSourceId);
		if (tables.isEmpty())
		{
			throw EngineRefusal.coded("missing.noPlannedLandingTable", "Project source " + pProjectSourceId + " has no generated landing table selected: run the landing plan and model/dataset steps first", pProjectSourceId);
		}

		final ObjectNode out = LandingFlows.MAPPER.createObjectNode();
		out.put("projectSource", pProjectSourceId);
		out.put("sourceSystem", sourceSystem);

		if (!pForce && PipelineSteps.isDone(pFactory, projectId, LandingFlows.STEP_LANDING_FLOWS, sourceSystem))
		{
			out.put("status", "SKIPPED_ALREADY_DONE");
			return out;
		}

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, LandingFlows.STEP_LANDING_FLOWS, sourceSystem);
		final ObjectNode detail = LandingFlows.MAPPER.createObjectNode();
		final ArrayNode tableDetails = detail.putArray("tables");

		final McpClient client;
		final ConnectorInfo connectorInfo;
		try
		{
			client = McpServers.connectors(pSession, pFactory);
			detail.put("transport", McpServers.connectorsTransport(pSession));
			connectorInfo = LandingFlows.describeConnector(client, connectorId, detail);
		}
		catch (final McpException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ex.getMessage(), detail.toString());
			throw EngineRefusal.codedWithCause("external.readConnector", "MCP call failed while reading connector " + connectorId + ": " + ex.getMessage(), ex, connectorId);
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

		int failed = 0;
		for (final FlowTable table : tables)
		{
			final ObjectNode tableDetail = tableDetails.addObject();
			tableDetail.put("landingTable", table.id);
			tableDetail.put("tableName", table.tableName);
			try
			{
				// read-recipe lot (2026-09-24): the catalog's own recipe (or its absence/scanState) decides
				// whether this table gets a recipe-driven mapping, falls back to the legacy per-connector-type
				// generator (with a WARN), or must not get a flow at all - see ReadRecipe.resolve's own Javadoc.
				final ReadRecipe.Eligibility eligibility = LandingFlows.recipeEligibility(pFactory, table);
				if (eligibility.kind == ReadRecipe.Eligibility.Kind.FAILED)
				{
					throw EngineRefusal.coded("conflict.flowNotEligible", eligibility.reason);
				}
				final boolean legacy = eligibility.kind == ReadRecipe.Eligibility.Kind.LEGACY_FALLBACK;
				tableDetail.put("recipe", !legacy);
				final String owned = LandingFlows.ownedMappingId(client, table, landingDataspace, landingDataset, tableDetail);
				final LandingFlow.Table flowTable = LandingFlows.withChangeColumn(new LandingFlow.Table(connectorId, connectorInfo.type, sourceSystem, table.sourceObject,
					landingDataspace, landingDataset, table.tableName, table.columns, connectorInfo.fileFeed, legacy ? null : eligibility.recipe).forProject(projectCode, owned), table,
					tableDetail);
				final String mappingId = LandingFlows.upsertFlow(pSession, client, flowTable, tableDetail);
				tableDetail.put("mappingId", mappingId);
				LandingFlows.writeFlowSuccess(pSession, pFactory, table.id, mappingId, legacy ? eligibility.reason : null);
			}
			catch (final IllegalArgumentException ex) // LandingFlow.mapping()/dialectFor() refuse a malformed or unsupported table
			{
				tableDetail.put("error", ex.getMessage());
				LandingFlows.writeFlowFailure(pSession, pFactory, table.id, ex.getMessage());
				failed++;
			}
			catch (final OperationException ex)
			{
				tableDetail.put("error", ex.getMessage());
				LandingFlows.writeFlowFailure(pSession, pFactory, table.id, ex.getMessage());
				failed++;
			}
			catch (final McpException ex)
			{
				tableDetail.put("transportError", ex.getMessage());
				LandingFlows.writeFlowFailure(pSession, pFactory, table.id, ex.getMessage());
				failed++;
			}
			catch (final RuntimeException ex) // one table's unexpected bug fails that table, never the whole run's bookkeeping
			{
				LoggingCategory.getKernel().error("[mdm-factory] landing flow generation failed unexpectedly for table " + table.id + ": " + ex, ex);
				tableDetail.put("error", "Unexpected error: " + ex);
				LandingFlows.writeFlowFailure(pSession, pFactory, table.id, "Unexpected error: " + ex);
				failed++;
			}
			catch (final Error ex)
			{
				// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
				StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
				throw ex;
			}
		}

		final String message = failed == 0 ? "Landing flow generated for " + tables.size() + " table(s)."
			: failed + " of " + tables.size() + " landing table(s) failed to get their flow.";
		PipelineSteps.end(pSession, pFactory, stepId, failed == 0, message, detail.toString());

		out.put("tables", tables.size());
		out.put("failed", failed);
		out.put("status", failed == 0 ? "DONE" : "FAILED");
		return out;
	}

	/**
	 * Regenerates the {@code ebx-connectors} flow of exactly ONE landing table - the write {@code
	 * ebx.LandingKeyChange#change} needs after it rewrites a table's key, so the mapping's generated SQL (or
	 * feed rules) build {@code _sourceId} from the NEW key from then on. Reuses every private helper
	 * {@link #generate} itself uses ({@link #source}, {@link #project}, {@link #flowTables},
	 * {@link #describeConnector}, {@link #upsertFlow}, {@link #writeFlowSuccess}/{@link #writeFlowFailure}) -
	 * not a copy of {@link #generate}'s body, only its per-table inner loop, narrowed to the one table named
	 * by {@code pLandingTableId} (which must still be flow-eligible - see {@link #FLOW_ELIGIBLE_STATUSES} -
	 * and currently selected; {@code ebx.LandingKeyChange} only ever calls this on a table it just confirmed
	 * is GENERATED/LOADED, so this refusal is defensive, not the normal path).
	 * <p>
	 * Deliberately outside the {@code LANDING_FLOWS} {@link PipelineStep}: that step is "the whole project
	 * source's flows, generated once" (see {@link #generate}'s own Javadoc, the {@code pForce} skip-guard) -
	 * a single table's on-demand regeneration after a key change is a different kind of event, and recording
	 * it as a new, source-system-wide step would misrepresent it as re-running the whole source. No load is
	 * triggered either: the caller decides when (or whether) to reload.
	 */
	public static ObjectNode generateOne(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final String pLandingTableId) throws OperationException
	{
		final Adaptation source = LandingFlows.source(pFactory, pProjectSourceId);
		final String sourceSystem = source.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		final String projectId = source.getString(FactoryPaths.ProjectSource.PROJECT);
		final Adaptation project = LandingFlows.project(pFactory, projectId, pProjectSourceId);
		final String projectCode = project.getString(FactoryPaths.Project.CODE);
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		if (landingDataspace == null || landingDataspace.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.landingZone", "Project source " + pProjectSourceId + " has no landing dataspace yet.", pProjectSourceId);
		}
		final String landingDataset = source.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
		if (landingDataset == null || landingDataset.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.landingZone", "Project source " + pProjectSourceId + " has no landing dataset yet.", pProjectSourceId);
		}
		final String connectorId = source.getString(FactoryPaths.ProjectSource.CONNECTOR);
		if (connectorId == null || connectorId.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noConnector", "Project source " + pProjectSourceId + " names no connector.", pProjectSourceId);
		}

		FlowTable table = null;
		for (final FlowTable candidate : LandingFlows.flowTables(pFactory, pProjectSourceId))
		{
			if (candidate.id.equals(pLandingTableId))
			{
				table = candidate;
				break;
			}
		}
		if (table == null)
		{
			throw EngineRefusal.coded("conflict.tableNotFlowEligible", "Landing table " + pLandingTableId + " is not a selected, flow-eligible table of project source " + pProjectSourceId + ".", pLandingTableId, pProjectSourceId);
		}

		final ObjectNode out = LandingFlows.MAPPER.createObjectNode();
		out.put("projectSource", pProjectSourceId);
		out.put("landingTable", pLandingTableId);
		final ObjectNode tableDetail = LandingFlows.MAPPER.createObjectNode();
		tableDetail.put("landingTable", table.id);
		tableDetail.put("tableName", table.tableName);
		try
		{
			final ReadRecipe.Eligibility eligibility = LandingFlows.recipeEligibility(pFactory, table);
			if (eligibility.kind == ReadRecipe.Eligibility.Kind.FAILED)
			{
				throw EngineRefusal.coded("conflict.flowNotEligible", eligibility.reason);
			}
			final boolean legacy = eligibility.kind == ReadRecipe.Eligibility.Kind.LEGACY_FALLBACK;
			tableDetail.put("recipe", !legacy);
			final McpClient client = McpServers.connectors(pSession, pFactory);
			final ConnectorInfo connectorInfo = LandingFlows.describeConnector(client, connectorId, tableDetail);
			final String owned = LandingFlows.ownedMappingId(client, table, landingDataspace, landingDataset, tableDetail);
			final LandingFlow.Table flowTable = LandingFlows.withChangeColumn(new LandingFlow.Table(connectorId, connectorInfo.type, sourceSystem, table.sourceObject,
				landingDataspace, landingDataset, table.tableName, table.columns, connectorInfo.fileFeed, legacy ? null : eligibility.recipe).forProject(projectCode, owned), table,
				tableDetail);
			final String mappingId = LandingFlows.upsertFlow(pSession, client, flowTable, tableDetail);
			tableDetail.put("mappingId", mappingId);
			LandingFlows.writeFlowSuccess(pSession, pFactory, table.id, mappingId, legacy ? eligibility.reason : null);
			out.put("status", "DONE");
			out.put("mapping", mappingId);
		}
		catch (final IllegalArgumentException ex) // LandingFlow.mapping()/dialectFor() refuse a malformed or unsupported table
		{
			tableDetail.put("error", ex.getMessage());
			LandingFlows.writeFlowFailure(pSession, pFactory, table.id, ex.getMessage());
			out.put("status", "FAILED");
			out.put("error", ex.getMessage());
		}
		catch (final OperationException ex)
		{
			tableDetail.put("error", ex.getMessage());
			LandingFlows.writeFlowFailure(pSession, pFactory, table.id, ex.getMessage());
			out.put("status", "FAILED");
			out.put("error", ex.getMessage());
		}
		catch (final McpException ex)
		{
			tableDetail.put("transportError", ex.getMessage());
			LandingFlows.writeFlowFailure(pSession, pFactory, table.id, ex.getMessage());
			out.put("status", "FAILED");
			out.put("error", ex.getMessage());
		}
		out.set("detail", tableDetail);
		return out;
	}

	/**
	 * Volume wave 2, lot V2-E (2026-10-03): the table's change column goes into its flow ({@link LandingFlow.Table#withChangeColumn})
	 * - recorded on the table's detail either way, so a flow that stayed FULL says why: only a SQL flow reads a watermark (the generated one, or a catalog
	 * recipe whose operation is a SQL {@code query}); a file feed and any other recipe ignore it ({@link LandingFlow#changeColumnIgnoredReason}).
	 */
	private static LandingFlow.Table withChangeColumn(final LandingFlow.Table pFlowTable, final FlowTable pTable, final ObjectNode pTableDetail)
	{
		if (pTable.changeColumn.isEmpty())
		{
			return pFlowTable;
		}
		pTableDetail.put("changeColumn", pTable.changeColumn);
		pTableDetail.put("changeColumnKind", pTable.changeColumnKind);
		final String ignored = LandingFlow.changeColumnIgnoredReason(pFlowTable);
		if (ignored != null)
		{
			pTableDetail.put("changeColumnIgnored", ignored);
			return pFlowTable;
		}
		return pFlowTable.withChangeColumn(pTable.changeColumn, pTable.changeColumnKind);
	}

	/**
	 * Writes the table's flow. A flow with a change column carries three properties an ebx-connectors build without the feed watermark
	 * (volume lot V2-D) refuses for the {@code query} operation ("the strategy must be supported by the operation"): that must not
	 * leave a table with no flow at all, so the flow is written ONCE MORE without the change column - the table is reloaded in full,
	 * as before the lot - and the table's detail says why ({@code changeColumnIgnored}). The change column stays on the
	 * {@code LandingTable}: the next generation after Connectors is updated writes the incremental flow again. A table without a change
	 * column, or whose second attempt is refused as well, fails exactly as before.
	 */
	static String upsertFlow(final McpClient pClient, final LandingFlow.Table pFlowTable, final ObjectNode pTableDetail) throws McpException, OperationException
	{
		return LandingFlows.upsertFlow(null, pClient, pFlowTable, pTableDetail);
	}

	/**
	 * {@link #upsertFlow(McpClient, LandingFlow.Table, ObjectNode)} as {@code pSession}: with a session and a compatible Connectors contract
	 * ({@code ebx.factory.connectors.via}) the flow is written by {@code flows.put}, otherwise by the tool {@code connectors_mapping_upsert} as before.
	 */
	static String upsertFlow(final Session pSession, final McpClient pClient, final LandingFlow.Table pFlowTable, final ObjectNode pTableDetail)
		throws McpException, OperationException
	{
		final String mappingId;
		try
		{
			mappingId = LandingFlows.upsertFlowOnce(pSession, pClient, pFlowTable, pTableDetail);
		}
		catch (final OperationException ex)
		{
			if (pFlowTable.changeColumn == null || pFlowTable.changeColumn.trim().isEmpty())
			{
				throw ex;
			}
			pTableDetail.put("changeColumnIgnored", "Connectors refused the incremental flow (" + LandingFlows.shortText(ex.getMessage()) + "): the table is reloaded in full");
			return LandingFlows.retireSuperseded(pClient, pFlowTable, LandingFlows.upsertFlowOnce(pSession, pClient, pFlowTable.withoutChangeColumn(), pTableDetail), pTableDetail);
		}
		return LandingFlows.retireSuperseded(pClient, pFlowTable, mappingId, pTableDetail);
	}

	/**
	 * Fix of 2026-10-03: the id of the mapping {@code pTable} may be updated IN PLACE (sent as {@code id} + {@code updateById}, see
	 * {@link LandingFlow#mapping}), or {@code null} when it must be looked up by label / created. {@code LandingTable.inboundMapping} is
	 * only a pointer: two projects on the same source system used to share one mapping, so the stored id can name ANOTHER project's flow
	 * - rewriting that would take it over and move its watermark. The pointer is followed only when the mapping really writes into this
	 * table (its dataspace, dataset and table path are this project's landing dataspace, the source's landing dataset and {@code
	 * /root/<table>}); a mapping that is gone, or writes elsewhere, is not (the table gets its own, found or created by its
	 * project-specific label), and the table's detail says which. A mapping that cannot be read at all throws: a flow whose ownership
	 * cannot be shown is never rewritten.
	 */
	static String ownedMappingId(final McpClient pClient, final FlowTable pTable, final String pLandingDataspace, final String pLandingDataset, final ObjectNode pTableDetail)
		throws McpException, OperationException
	{
		if (pTable.inboundMapping.isEmpty())
		{
			return null;
		}
		final LandingFlowOwnership.Mapping mapping = LandingFlowOwnership.read(pClient, pTable.inboundMapping);
		if (mapping.gone)
		{
			pTableDetail.put("previousMapping", "mapping " + pTable.inboundMapping + " no longer exists: a new flow is created");
			return null;
		}
		if (!mapping.writesInto(pLandingDataspace, pLandingDataset, "/root/" + pTable.tableName))
		{
			pTableDetail.put("previousMapping", "mapping " + pTable.inboundMapping + " writes into " + mapping.dataspace + "/" + mapping.dataset + " " + mapping.table
				+ ", not into this table (it belongs to another project, or was edited by hand): left untouched, this table gets its own flow");
			return null;
		}
		return pTable.inboundMapping;
	}

	/**
	 * The upsert answered {@code pMappingId}; when the table owned ANOTHER mapping beforehand ({@link LandingFlow.Table#existingMappingId},
	 * verified to write into this very table) the label found or created a different one - two active landing mappings on one table, which
	 * would load the same rows twice. The old one is then deleted (best effort, recorded on the table's detail either way): it is
	 * this table's own by the ownership check, and only its watermark is lost.
	 */
	static String retireSuperseded(final McpClient pClient, final LandingFlow.Table pFlowTable, final String pMappingId, final ObjectNode pTableDetail) throws McpException
	{
		final String previous = pFlowTable.existingMappingId;
		if (previous == null || previous.isEmpty() || previous.equals(pMappingId))
		{
			return pMappingId;
		}
		final ObjectNode args = LandingFlows.MAPPER.createObjectNode();
		args.put("mapping", previous);
		final McpClient.ToolResult deleted = pClient.callTool("connectors_mapping_delete", args);
		pTableDetail.put("supersededMapping", deleted.error ? "mapping " + previous + " could not be deleted (" + LandingFlows.errorText(deleted) + "): delete it by hand"
			: "mapping " + previous + " deleted: the flow is now mapping " + pMappingId);
		return pMappingId;
	}

	private static String upsertFlowOnce(final Session pSession, final McpClient pClient, final LandingFlow.Table pFlowTable, final ObjectNode pTableDetail)
		throws McpException, OperationException
	{
		final ObjectNode mappingDocument = LandingFlow.mapping(pFlowTable);
		final String viaContract = LandingFlows.upsertThroughContract(pSession, pFlowTable, mappingDocument, pTableDetail);
		if (viaContract != null)
		{
			return viaContract;
		}

		final ObjectNode dryArgs = LandingFlows.MAPPER.createObjectNode();
		dryArgs.set("mapping", mappingDocument);
		dryArgs.put("dryRun", true);
		final McpClient.ToolResult dry = pClient.callTool("connectors_mapping_upsert", dryArgs);
		pTableDetail.set("dryRun", LandingFlows.toolDetail(dryArgs, dry));
		if (dry.error)
		{
			throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert (dry run) refused the flow of " + pFlowTable.landingTableName + ": " + LandingFlows.errorText(dry), pFlowTable.landingTableName);
		}

		final ObjectNode realArgs = LandingFlows.MAPPER.createObjectNode();
		realArgs.set("mapping", mappingDocument);
		realArgs.put("dryRun", false);
		final McpClient.ToolResult real = pClient.callTool("connectors_mapping_upsert", realArgs);
		pTableDetail.set("upsert", LandingFlows.toolDetail(realArgs, real));
		if (real.error)
		{
			throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert could not write the flow of " + pFlowTable.landingTableName + ": " + LandingFlows.errorText(real), pFlowTable.landingTableName);
		}
		final String mappingId = real.json == null ? null : real.json.path("mapping").asText(null);
		if (mappingId == null || mappingId.trim().isEmpty())
		{
			throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert did not return a mapping id for " + pFlowTable.landingTableName + ": " + LandingFlows.shortText(real.text), pFlowTable.landingTableName);
		}
		return mappingId;
	}

	/**
	 * Wave 11 (audit F-26): the flow of {@code pMappingDocument} written by the Connectors contract's {@code flows.put} under its label (the key) - the
	 * mapping of the same label is updated in place, so a flow generated before the contract is found, never duplicated. Answers the mapping id, or null
	 * when the contract is not to be used for THIS write:
	 * <ul>
	 * <li>no session, {@code via=direct}, or no compatible contract with {@code via=auto};</li>
	 * <li>the table already owns a mapping ({@link LandingFlow.Table#existingMappingId}) that is not the one the new label designates: the contract designates a
	 * flow by its label only, and this write must rename that mapping in place ({@code id} + {@code updateById}, which the contract refuses), so it goes by the tool;</li>
	 * <li>{@code via=auto} and the contract answered that it is gone or switched off.</li>
	 * </ul>
	 * With {@code via=contract} a refusal is thrown as the coded engine message and nothing falls back.
	 */
	private static String upsertThroughContract(final Session pSession, final LandingFlow.Table pFlowTable, final ObjectNode pMappingDocument, final ObjectNode pTableDetail)
		throws OperationException
	{
		if (pSession == null || !ConnectorsContractClient.useContract())
		{
			return null;
		}
		final String key = ContractFlows.keyOf(pMappingDocument);
		final String landingTable = pFlowTable.landingTableName;
		try
		{
			final String existing = pFlowTable.existingMappingId == null ? "" : pFlowTable.existingMappingId.trim();
			if (!existing.isEmpty())
			{
				final String designated = key == null ? null : ContractFlows.idOf(pSession, key);
				if (!existing.equals(designated))
				{
					pTableDetail.put("flowVia", "tool: the table's own mapping " + existing + " is renamed in place (the contract designates a flow by its label)");
					return null;
				}
			}
			final JsonNode dry = ContractFlows.put(pSession, pMappingDocument, true);
			pTableDetail.set("dryRun", LandingFlows.contractDetail("flows.put", key, true, dry));
			final JsonNode real = ContractFlows.put(pSession, pMappingDocument, false);
			pTableDetail.set("upsert", LandingFlows.contractDetail("flows.put", key, false, real));
			pTableDetail.put("flowVia", "contract");
			final String mappingId = real.hasNonNull("mappingId") ? real.path("mappingId").asText() : null;
			if (mappingId == null || mappingId.trim().isEmpty())
			{
				throw EngineRefusal.coded("external.writeMapping", "flows.put did not return a mapping id for " + landingTable + ": " + LandingFlows.shortText(real.toString()), landingTable);
			}
			return mappingId;
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			if (ConnectorsContractClient.mayFallBack(ex))
			{
				pTableDetail.put("flowVia", "tool: " + ex.type + " (" + LandingFlows.shortText(ex.getMessage()) + ")");
				return null;
			}
			if (ConnectorsContractClient.OPERATION_FAILED.equals(ex.type))
			{
				// Connectors' own validation of the flow (the same as the tool's): the same coded sentence as the tool's refusal
				throw EngineRefusal.coded("external.writeMapping", "flows.put refused the flow of " + landingTable + ": " + ex.getMessage(), landingTable);
			}
			throw ConnectorsContractClient.engineRefusal(ex, "flows.put", key);
		}
	}

	/** The detail of one contract call of a table's flow, in the shape of {@link #toolDetail}'s ({@code arguments}, {@code error}, {@code result}). */
	private static ObjectNode contractDetail(final String pOperation, final String pKey, final boolean pDryRun, final JsonNode pResult)
	{
		final ObjectNode node = LandingFlows.MAPPER.createObjectNode();
		final ObjectNode arguments = node.putObject("arguments");
		arguments.put("operation", pOperation);
		arguments.put("key", pKey);
		arguments.put("dryRun", pDryRun);
		node.put("error", false);
		node.set("result", pResult);
		return node;
	}

	/** What {@link LandingFlow.Table} needs to know about one connector, read once per {@link #generate} call. */
	static final class ConnectorInfo
	{
		/** As {@code connectors_describe} reports it (e.g. {@code "snowflake"}): selects the {@link LandingFlow#dialectFor} dialect. */
		final String type;
		/** See {@link LandingFlows#isFileFeed}. */
		final boolean fileFeed;

		ConnectorInfo(final String pType, final boolean pFileFeed)
		{
			this.type = pType;
			this.fileFeed = pFileFeed;
		}
	}

	static ConnectorInfo describeConnector(final McpClient pClient, final String pConnectorId, final ObjectNode pDetail) throws McpException, OperationException
	{
		final ObjectNode args = LandingFlows.MAPPER.createObjectNode();
		args.put("connector", pConnectorId);
		final McpClient.ToolResult result = pClient.callTool("connectors_describe", args);
		pDetail.set("connectorDescribe", LandingFlows.toolDetail(args, result));
		if (result.error)
		{
			throw EngineRefusal.coded("external.describeConnector", "connectors_describe could not describe connector " + pConnectorId + ": " + LandingFlows.errorText(result), pConnectorId);
		}
		final String type = result.json == null ? null : result.json.path("type").asText(null);
		if (type == null || type.trim().isEmpty())
		{
			throw EngineRefusal.coded("external.describeConnector", "connectors_describe did not answer a type for connector " + pConnectorId + ": " + LandingFlows.shortText(result.text), pConnectorId);
		}
		return new ConnectorInfo(type, LandingFlows.isFileFeed(result.json));
	}

	/**
	 * True when the connector's own {@code operations[]} (as {@code connectors_describe} reports it: each
	 * {@code {name, kind, description, requiredInputs[], optionalInputs[]}}) advertises a {@code read}
	 * operation whose {@code requiredInputs} include {@code path} - the {@code AbstractFileConnector} family's
	 * own contract ({@code sample-file}, {@code sftp}, {@code s3}, {@code azure-blob}, {@code gcs}, {@code ftp}
	 * today, and any future connector shaped the same way). Deliberately structural, not a hardcoded list of
	 * connector type strings: a connector matching this contract gets the feed-based landing flow with no
	 * further {@code ebx-mdm-factory} change needed, the same way {@link LandingFlow#dialectFor} avoids
	 * hardcoding "which types are SQL" anywhere beyond the {@link SqlDialect#supports} contract itself.
	 * {@code requiredInputs} entries are read as either a bare string or an object carrying a {@code name}, to
	 * tolerate either shape connectors_describe might answer.
	 */
	private static boolean isFileFeed(final JsonNode pDescribeResult)
	{
		if (pDescribeResult == null)
		{
			return false;
		}
		for (final JsonNode operation : pDescribeResult.path("operations"))
		{
			if (!"read".equals(operation.path("name").asText("")))
			{
				continue;
			}
			for (final JsonNode input : operation.path("requiredInputs"))
			{
				final String name = input.isTextual() ? input.asText() : input.path("name").asText("");
				if ("path".equals(name))
				{
					return true;
				}
			}
		}
		return false;
	}

	// ======================================================================================================= load

	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		return LandingFlows.load(pSession, pFactory, pProjectSourceId, ProgressNote.NONE, null);
	}

	/** Same, saying which table is being copied and how many rows each brought (live-progress lot: the landing run's running screen). */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final ProgressNote pNote)
		throws OperationException
	{
		return LandingFlows.load(pSession, pFactory, pProjectSourceId, pNote, null);
	}

	/**
	 * Same, checked for a stop between tables AND, since this lot, between polls of one table's own run
	 * (volume lot 7c, 2026-09-28, "asynchronous landing load") - {@code pHandle} null for a caller with no
	 * job ({@code rest.FactoryResource}'s synchronous route, {@code workflow.LandingLoadTaskBean}: both stay
	 * on the 3-/4-arg overloads deliberately, the same posture docs/VOLUME.md "Lot 8a" task 1 documents for
	 * every workflow bean - a workflow/REST thread still runs this to completion, it just no longer blocks on
	 * one long synchronous {@code connectors_sync} call to do so, see {@link #awaitLoad}), non-null for a
	 * job-driven caller ({@code ebx.LandingSourceRun}/{@code ebx.LandingRun}), which can then have its Stop
	 * button reach a table's load WHILE it is running, not only between tables/stages as before this lot.
	 */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final ProgressNote pNote,
		final FactoryJobs.Handle pHandle) throws OperationException
	{
		return LandingFlows.load(pSession, pFactory, pProjectSourceId, pNote, pHandle, false);
	}

	/**
	 * Same, with the volume wave 2 (lot V2-E, 2026-10-03) FULL REFRESH switch: {@code pFullRefresh} true makes every table that is
	 * read through a change column ignore its stored watermark for this load and reset it ({@code connectors_sync(fullRefresh: true)},
	 * ebx-connectors docs/REFERENCE.md 2.14.7) - what a person asks for to also pick up what a watermark can never see (a row
	 * whose change column was not touched, a source rebuilt from a backup). A table with no change column is reloaded in full
	 * anyway. Independently of the switch, a table is refreshed in full by itself when its change column is not the one its last
	 * load read through, or when its landing table is empty ({@link ChangeColumnRule#fullRefreshNeeded}).
	 */
	public static ObjectNode load(final Session pSession, final Adaptation pFactory, final String pProjectSourceId, final ProgressNote pNote,
		final FactoryJobs.Handle pHandle, final boolean pFullRefresh) throws OperationException
	{
		final Adaptation source = LandingFlows.source(pFactory, pProjectSourceId);
		final String sourceSystem = source.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		final String projectId = source.getString(FactoryPaths.ProjectSource.PROJECT);
		final String landingDataspace = LandingFlows.project(pFactory, projectId, pProjectSourceId).getString(FactoryPaths.Project.LANDING_DATASPACE);
		final String landingDataset = source.getString(FactoryPaths.ProjectSource.LANDING_DATASET);

		final List<LoadTable> tables = LandingFlows.loadTables(pFactory, pProjectSourceId);
		if (tables.isEmpty())
		{
			throw EngineRefusal.coded("missing.noFlow", "Project source " + pProjectSourceId + " has no landing table with a flow: run POST /sources/" + pProjectSourceId + "/landing/flows first", pProjectSourceId);
		}

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, LandingFlows.STEP_LANDING_LOAD, sourceSystem);
		final ObjectNode detail = LandingFlows.MAPPER.createObjectNode();
		final ArrayNode tableDetails = detail.putArray("tables");

		final McpClient client;
		try
		{
			client = McpServers.connectors(pSession, pFactory);
			detail.put("transport", McpServers.connectorsTransport(pSession));
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

		// the EBX server's own local zone, not UTC: _receivedAt is meant to be read as server-local time (see
		// LandingFlow.receivedAt). loadId's own formatting stays UTC regardless (it forces the zone itself),
		// so sharing this one clock between both values does not change loadId's output.
		final Clock clock = Clock.systemDefaultZone();
		int failed = 0;
		int position = 0;
		boolean stoppedByHandle = false;
		for (final LoadTable table : tables)
		{
			if (pHandle != null && pHandle.cancelled())
			{
				// checked BEFORE starting a new table's run at all - the same "between stages" convention every
				// other job body in this module already follows (MatchingConfigurator, LandingSourceRun...);
				// mid-table cancellation (a run already started) is handled inside awaitLoad instead.
				stoppedByHandle = true;
				break;
			}
			position++;
			final ObjectNode tableDetail = tableDetails.addObject();
			tableDetail.put("landingTable", table.id);
			tableDetail.put("tableName", table.tableName);
			final String loadId = LandingFlow.loadId(table.id, clock);
			final String receivedAt = LandingFlow.receivedAt(clock);
			tableDetail.put("loadId", loadId);
			tableDetail.put("receivedAt", receivedAt);
			// volume wave 2, lot V2-E: a table read through a change column reloads only what changed since its last
			// successful load - unless its stored watermark cannot be trusted (see ChangeColumnRule.fullRefreshNeeded) or a person asked
			final boolean incremental = !table.changeColumn.isEmpty();
			final long landingRowsBefore = incremental ? LandingFlows.landingRowCount(pFactory, landingDataspace, landingDataset, table.tableName) : -1L;
			final boolean fullRefresh = incremental && (pFullRefresh || ChangeColumnRule.fullRefreshNeeded(table.changeColumn, table.changeColumnLoaded, landingRowsBefore));
			if (incremental)
			{
				tableDetail.put("changeColumn", table.changeColumn);
				tableDetail.put("fullRefresh", fullRefresh);
				if (fullRefresh)
				{
					tableDetail.put("fullRefreshBecause", pFullRefresh ? "asked for" : !table.changeColumn.equals(table.changeColumnLoaded)
						? "the change column is not the one the last load read through" : "the landing table is empty");
				}
			}
			try
			{
				// volume-guards lot (2026-09-28, task item 1): no longer sized from the catalog's own row count
				// (stale or 0/never-profiled - see LandingFlow.SYNC_MAX_ROWS's own Javadoc) - ask for everything the
				// connector can give, and let LoadOutcomeRule catch an incomplete read from what actually came back.
				final int max = LandingFlow.SYNC_MAX_ROWS;
				tableDetail.put("max", max);
				final ObjectNode args = LandingFlows.MAPPER.createObjectNode();
				// the mapping id, not its label: connectors_mapping_upsert's own label-uniqueness caveat
				// (see the knowledge base) means a label is only guaranteed unique per connector/operation/
				// direction/tablePath, and connectors_sync resolves a label with no ambiguity check - the id
				// this same generator wrote is unambiguous.
				args.put("mapping", table.inboundMapping);
				// name=value pairs, ';'-separated: ConnectorService.runtimeInputs (ConnectorService.java:701-724)
				// splits on the first '=' of each ';'/newline-separated part and keeps everything after it
				// verbatim (trimmed) - a value with ':' and '-' in it (receivedAt) survives untouched, since
				// neither character is a separator the parser looks for.
				args.put("inputs", "loadId=" + loadId + ";receivedAt=" + receivedAt);
				args.put("max", max);
				args.put("dryRun", false);
				// volume lot 7c (2026-09-28): the run no longer ties up this thread (or the ebx-connectors MCP
				// client's own read timeout, McpServers.READ_TIMEOUT_MS) for as long as the whole table takes to
				// copy - connectors_sync starts the run and answers at once; awaitLoad below polls
				// connectors_run_get to the run's own terminal state. An older Connectors build that does not
				// know "async" ignores it and answers the complete, already-terminal synchronous shape directly
				// (RunStateOutcome.isAsyncStart tells the two apart from the shape of the answer itself).
				args.put("async", true);
				if (fullRefresh)
				{
					args.put("fullRefresh", true);
				}
				pNote.note(RunTexts.copying(table.tableName, position, tables.size()).stored());
				final McpClient.ToolResult sync = client.callTool("connectors_sync", args);
				tableDetail.set("sync", LandingFlows.summaryDetail(args, sync));
				if (sync.error)
				{
					throw EngineRefusal.coded("external.syncRefused", "connectors_sync refused the load of " + table.tableName + ": " + LandingFlows.errorText(sync), table.tableName);
				}
				final PollOutcome poll = LandingFlows.awaitLoad(client, pNote, pHandle, table, sync.json, tableDetail, fullRefresh);
				// landing test fixes (2026-10-17): one row read into the columns of a flattened array is not a loaded table
				final LandingFlow.LoadOutcome outcome = com.on.ebx.factory.landing.FlattenedDocument.apply(poll.outcome, table.sourceColumns);
				tableDetail.put("outcome", outcome.ok);
				tableDetail.put("rows", outcome.rows);
				// LandingTable.lastLoadRows is read everywhere as "the rows of the landing table" (LandingRowCounts): after a run
				// that read only the rows changed since the watermark, that is the landing table's own size, not what the run read
				long recordedRows = outcome.rows;
				if (outcome.watermark.incremental)
				{
					LandingFlows.putWatermark(tableDetail, outcome.watermark);
					if (outcome.ok && outcome.watermark.filtered())
					{
						final long landingRows = LandingFlows.landingRowCount(pFactory, landingDataspace, landingDataset, table.tableName);
						tableDetail.put("landingRows", landingRows);
						recordedRows = landingRows >= 0 ? landingRows : recordedRows;
					}
				}
				pNote.note(RunTexts.rowsCopied(table.tableName, outcome.rows, outcome.watermark.filtered()).stored());
				if (!LandingFlows.writeLoadOutcome(pSession, pFactory, table.id, loadId, recordedRows, outcome.ok, outcome.message,
					ChangeColumnRule.columnToRecord(table.changeColumn, outcome.ok, outcome.watermark, outcome.rows)))
				{
					// the sync itself may have gone fine - it is the EBX write-back that failed (a bug the first
					// time this was seen live: LAST_LOAD_ROWS is xs:decimal, and passing it a java.lang.Long threw
					// inside the procedure, silently rolling back the whole row). Never let that read as DONE.
					tableDetail.put("writeBackError", "Load ran (" + outcome.message + ") but recording the outcome on LandingTable " + table.id
						+ " failed - see ebxLog/kernel.log for the underlying EBX error.");
					failed++;
				}
				else if (!outcome.ok)
				{
					failed++;
				}
				if (poll.stoppedByHandle)
				{
					// this table's own outcome is already written back above (FAILED, "Load stopped...") - stop
					// the whole source's load here rather than starting the next table.
					stoppedByHandle = true;
					break;
				}
			}
			catch (final McpException ex)
			{
				tableDetail.put("transportError", ex.getMessage());
				if (!LandingFlows.writeLoadOutcome(pSession, pFactory, table.id, loadId, 0, false, ex.getMessage(), null))
				{
					tableDetail.put("writeBackError", "Recording the failure of table " + table.id + " itself failed - see ebxLog/kernel.log.");
				}
				failed++;
			}
			catch (final OperationException ex)
			{
				tableDetail.put("error", ex.getMessage());
				if (!LandingFlows.writeLoadOutcome(pSession, pFactory, table.id, loadId, 0, false, ex.getMessage(), null))
				{
					tableDetail.put("writeBackError", "Recording the failure of table " + table.id + " itself failed - see ebxLog/kernel.log.");
				}
				failed++;
			}
			catch (final RuntimeException ex) // one table's unexpected bug fails that table, never the whole run's bookkeeping
			{
				LoggingCategory.getKernel().error("[mdm-factory] landing load failed unexpectedly for table " + table.id + ": " + ex, ex);
				tableDetail.put("error", "Unexpected error: " + ex);
				if (!LandingFlows.writeLoadOutcome(pSession, pFactory, table.id, loadId, 0, false, "Unexpected error: " + ex, null))
				{
					tableDetail.put("writeBackError", "Recording the failure of table " + table.id + " itself failed - see ebxLog/kernel.log.");
				}
				failed++;
			}
			catch (final Error ex)
			{
				// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
				StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
				throw ex;
			}
		}

		final String message;
		if (stoppedByHandle)
		{
			message = "Load stopped after " + position + " of " + tables.size() + " table(s)"
				+ (pHandle == null || pHandle.stopReason() == null ? "" : " (" + pHandle.stopReason() + ")") + ".";
			PipelineSteps.endStopped(pSession, pFactory, stepId, message, detail.toString());
		}
		else
		{
			message = failed == 0 ? "Loaded " + tables.size() + " landing table(s)." : failed + " of " + tables.size() + " landing table(s) failed to load.";
			PipelineSteps.end(pSession, pFactory, stepId, failed == 0, message, detail.toString());
			if (failed == 0)
			{
				// automatic-intake lot: the Factory ran this read itself, so it knows when it ended - the intake chain starts right after it
				// (a no-op when the intake is not ON; never fails the load)
				IntakeEngine.sourceLoaded(projectId, sourceSystem);
			}
		}

		final ObjectNode out = LandingFlows.MAPPER.createObjectNode();
		out.put("projectSource", pProjectSourceId);
		out.put("sourceSystem", sourceSystem);
		out.put("tables", tables.size());
		out.put("failed", failed);
		// volume lot 7c (2026-09-28): a THIRD status, alongside the pre-existing DONE/FAILED - a job's Stop
		// button reached this table's/source's load (see stoppedByHandle above); the caller (LandingRun
		// .runLoadForSourceIfReady) reads it to phrase its own progress line, rather than a generic failure count.
		out.put("status", stoppedByHandle ? "STOPPED" : failed == 0 ? "DONE" : "FAILED");
		// live fix (2026-09-24, project Vague1, bug 7a): the caller (LandingRun.runLoadForSourceIfReady) used to
		// summarise a failure as a bare count ("1 of 1 table(s) failed.") with no readable reason at all - the
		// per-table reason (error/transportError/writeBackError) was recorded in tableDetail all along, just
		// never surfaced past this method. The first failing table's own message, short enough for the grid.
		if (failed > 0)
		{
			out.put("firstFailureReason", LandingFlows.firstFailureReason(tableDetails));
		}
		return out;
	}

	/**
	 * The first per-table failure reason worth showing in a summary line (bug fixed live 2026-09-24, project
	 * Vague1, bug 7a): {@code error}/{@code transportError}/{@code writeBackError} are the three fields a
	 * table's own {@code tableDetail} can carry (see {@link #load}'s own try/catch above), read in that order
	 * since {@code error}/{@code transportError} are the actual cause and {@code writeBackError} is a
	 * secondary "recording the outcome also failed" note. Truncated ({@link TextLimits}) - this text ends up
	 * in a grid cell, not a log. "see detail" when a table detail was marked failed some other way (should not
	 * happen given the three fields above, but a summary line must never come back empty either way).
	 */
	static String firstFailureReason(final ArrayNode pTableDetails)
	{
		for (final JsonNode tableDetail : pTableDetails)
		{
			for (final String field : new String[] { "error", "transportError", "writeBackError" })
			{
				final String text = tableDetail.path(field).asText("");
				if (!text.isEmpty())
				{
					return TextLimits.truncate(tableDetail.path("tableName").asText("") + ": " + text, 200);
				}
			}
		}
		return "see detail";
	}

	/**
	 * The ids of every {@code ProjectSource} row of {@code pProjectCode}, in table order - the same project/
	 * {@code ProjectSource} lookup {@link com.on.ebx.factory.ebx.MatchingConfigurator#readTrustedSources} and
	 * {@link com.on.ebx.factory.ebx.StagingSuggester#suggest} already inline for their own purposes, factored
	 * out here (public, unlike those two private copies) because {@code com.on.ebx.factory.workflow.
	 * LandingLoadTaskBean} is the first caller from OUTSIDE this package: a workflow task bean loading "every
	 * source of the project" when no single {@code ProjectSource.id} was given needs the same list without a
	 * fourth inline copy of the query.
	 *
	 * @throws OperationException {@code pProjectCode} does not name a project.
	 */
	public static List<String> projectSourceIds(final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = LandingFlows.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final List<String> ids = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(projectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				ids.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return ids;
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

	// ------------------------------------------------------------------------------------------------- watermark

	/**
	 * Rows in the landing table right now, {@code -1} when it cannot be counted (dataspace, dataset or table not found, or any
	 * read failure): a caller never reads a count it did not get as "empty". Volume wave 2, lot V2-E: used before an incremental load
	 * (an EMPTY landing table must be refreshed in full whatever the stored watermark says) and after one that read only the changed
	 * rows (the table's own size is then what {@code LandingTable.lastLoadRows} records).
	 */
	static long landingRowCount(final Adaptation pFactory, final String pLandingDataspace, final String pLandingDataset, final String pTableName)
	{
		if (pLandingDataspace == null || pLandingDataspace.trim().isEmpty() || pLandingDataset == null || pLandingDataset.trim().isEmpty())
		{
			return -1L;
		}
		try
		{
			final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(pLandingDataspace));
			final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pLandingDataset));
			if (dataset == null)
			{
				return -1L;
			}
			final RequestResult rows = dataset.getTable(Path.parse("/root/" + pTableName)).createRequestResult(null);
			try
			{
				return rows.getSize();
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return -1L;
		}
	}

	/** The run's watermark, scalar by scalar, on the table's {@code PipelineStep.detail} entry (what the load did, readable without Connectors). */
	private static void putWatermark(final ObjectNode pTableDetail, final com.on.ebx.factory.landing.WatermarkOutcome pWatermark)
	{
		if (pWatermark.before != null)
		{
			pTableDetail.put("watermarkBefore", pWatermark.before);
		}
		if (pWatermark.after != null)
		{
			pTableDetail.put("watermarkAfter", pWatermark.after);
		}
		pTableDetail.put("skippedByWatermark", pWatermark.skipped);
		pTableDetail.put("watermarkFiltered", pWatermark.filtered());
		pTableDetail.put("fullRefresh", pWatermark.fullRefresh);
		if (pWatermark.note != null)
		{
			pTableDetail.put("watermarkNote", pWatermark.note);
		}
	}

	// ------------------------------------------------------------------------------------------------- write-back

	/**
	 * Success: the mapping id is recorded, the table's own status is left as it was (a flow does not by
	 * itself change whether the table is generated/loaded/drifted). {@code pWarn} (read-recipe lot,
	 * 2026-09-24) is non-null only when the flow was built by the legacy per-connector-type generator
	 * (no catalog recipe yet) - appended to the message so a person reading {@code LandingTable.message}
	 * sees the same "re-scan the source" nudge {@link ReadRecipe#resolve} produced.
	 */
	private static void writeFlowSuccess(final Session pSession, final Adaptation pFactory, final String pLandingTableId, final String pMappingId, final String pWarn)
		throws OperationException
	{
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final String message = "Landing flow generated (mapping " + pMappingId + ")." + (pWarn == null || pWarn.trim().isEmpty() ? "" : " WARN: " + pWarn.trim() + ".");
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = tables.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pMappingId, FactoryPaths.LandingTable.INBOUND_MAPPING);
				value.setValue(message, FactoryPaths.LandingTable.MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			final String why = result.getExceptionFullMessage(Locale.ENGLISH);
			LoggingCategory.getKernel().error("[mdm-factory] could not record the landing flow of table " + pLandingTableId + " (mapping " + pMappingId + "): " + why);
			// the mapping EXISTS in Connectors: its id goes in the message (kept by the caller's failure write and the step detail), so that it is not lost. The next generation
			// finds it again by its label and updates it in place.
			throw EngineRefusal.coded("unexpected.recordFlowMapping", "The landing flow mapping " + pMappingId + " was created but could not be recorded on landing table " + pLandingTableId + ": " + why, pMappingId, pLandingTableId);
		}
	}

	private static void writeFlowFailure(final Session pSession, final Adaptation pFactory, final String pLandingTableId, final String pMessage)
	{
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final String message = TextLimits.truncate(pMessage, TextLimits.MESSAGE_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = tables.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue("FAILED", FactoryPaths.LandingTable.STATUS);
				value.setValue(message, FactoryPaths.LandingTable.MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel()
				.error("[mdm-factory] could not record the landing flow failure of table " + pLandingTableId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}

	/**
	 * @param pChangeColumnLoaded the value for {@code LandingTable.changeColumnLoaded} ({@code ""} clears it), or {@code null} to leave it
	 * @return true when the write-back itself succeeded (the load's own outcome, success or failure, is
	 *         {@code pOk} - a caller must not read a false return as "the load failed", only as "this row was
	 *         not updated to say so, and must not be reported as DONE either").
	 */
	private static boolean writeLoadOutcome(final Session pSession, final Adaptation pFactory, final String pLandingTableId, final String pLoadId, final long pRows,
		final boolean pOk, final String pMessage, final String pChangeColumnLoaded)
	{
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final String message = TextLimits.truncate(pMessage, TextLimits.MESSAGE_MAX_LENGTH);
		final Date now = new Date();
		// LandingTable.lastLoadRows is declared 'long' in tools/build_xsd.py, but EBX has no xs:long: the DSL
		// maps it to xs:decimal (xsd_emitter.py:7-8; confirmed in the generated XSD,
		// lastLoadRows type="xs:decimal"). The internal Java API expects a BigDecimal for that field, not a
		// java.lang.Long - passing a Long here was the root cause of the load write-back silently never
		// landing: ValueContextForUpdate.setValue threw inside the procedure, ProcedureResult.hasFailed()
		// came back true, and the only trace of it was a kernel.log line nothing here surfaced to the caller.
		// The pattern to follow for any other 'long'-declared field: LandingPlanner already writes
		// SOURCE_ROW_COUNT (also 'long') as a BigDecimal (LandingPlanner.java:167).
		final BigDecimal rows = BigDecimal.valueOf(pRows);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = tables.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pLoadId, FactoryPaths.LandingTable.LAST_LOAD_ID);
				value.setValue(now, FactoryPaths.LandingTable.LAST_LOAD_AT);
				value.setValue(rows, FactoryPaths.LandingTable.LAST_LOAD_ROWS);
				value.setValue(pOk ? "LOADED" : "FAILED", FactoryPaths.LandingTable.STATUS);
				value.setValue(message, FactoryPaths.LandingTable.MESSAGE);
				// volume wave 2, lot V2-E: the change column this load read through (null: leave it - the load failed, or the
				// stored watermark was not reset to it); a model without the field yet (module XSD loads at the next start) is skipped
				if (pChangeColumnLoaded != null && row.getSchemaNode().getNode(FactoryPaths.LandingTable.CHANGE_COLUMN_LOADED) != null)
				{
					value.setValue(pChangeColumnLoaded.isEmpty() ? null : pChangeColumnLoaded, FactoryPaths.LandingTable.CHANGE_COLUMN_LOADED);
				}
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not record the load outcome of table " + pLandingTableId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------------------------------------------------- reads

	private static Adaptation source(final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		final Adaptation source = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		if (source == null)
		{
			throw EngineRefusal.coded("missing.projectSource", "Project source " + pProjectSourceId + " not found", pProjectSourceId);
		}
		return source;
	}

	private static Adaptation project(final Adaptation pFactory, final String pProjectId, final String pProjectSourceId) throws OperationException
	{
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
		if (project == null)
		{
			throw EngineRefusal.coded("missing.projectOfSource", "Project " + pProjectId + " of source " + pProjectSourceId + " not found", pProjectId, pProjectSourceId);
		}
		return project;
	}

	/** One selected, flow-eligible landing table with its columns, in position order. */
	static final class FlowTable
	{
		final String id;
		final String tableName;
		final String sourceObject;
		final List<LandingFlow.Column> columns;
		/** The key of the table's catalog asset ({@code LandingTable.catalogAsset}, read-recipe lot 2026-09-24; a string since audit D-07), resolved by {@link CatalogReader#resolveAssetKey} - {@code null} when no asset answers (planned before that field existed, catalog absent, asset gone). */
		final String catalogAssetKey;
		/** {@code LandingTable.changeColumn}/{@code changeColumnKind} (volume wave 2, lot V2-E), empty strings when the table is reloaded in full. */
		final String changeColumn;
		final String changeColumnKind;
		/** {@code LandingTable.inboundMapping} as stored (the id of the mapping this table was last generated with), empty when never generated. Only a pointer: {@link #ownedMappingId} decides whether it is this table's own. */
		final String inboundMapping;

		FlowTable(final String pId, final String pTableName, final String pSourceObject, final List<LandingFlow.Column> pColumns, final String pCatalogAssetKey,
			final String pChangeColumn, final String pChangeColumnKind, final String pInboundMapping)
		{
			this.inboundMapping = pInboundMapping == null ? "" : pInboundMapping.trim();
			this.id = pId;
			this.tableName = pTableName;
			this.sourceObject = pSourceObject;
			this.columns = pColumns;
			this.catalogAssetKey = pCatalogAssetKey;
			this.changeColumn = pChangeColumn;
			this.changeColumnKind = pChangeColumnKind;
		}
	}

	/**
	 * Audit F-02 (catalog reads): why the recipe of a landing table is read without the person's session. The recipe
	 * (connector operation, inputs, items path) and the scan state only decide HOW the factory reads a table already planned
	 * in the project (flows, loads, preview, key study - jobs for most of them); what is then shown comes from the source
	 * through Connectors, under the person's own Connectors right. The landing plan that put the table in the project read
	 * the catalog as the person ({@link LandingPlanner}).
	 */
	static final String RECIPE_REASON = "read recipe and scan state of a landing table already planned in the project: decide how its flow reads the"
		+ " source (flows, loads, preview, key study; jobs for most), nothing of the catalog is shown";

	/**
	 * Read-recipe lot (2026-09-24): what {@code pTable}'s flow generation should do, from its catalog
	 * asset's {@code readRecipe}/{@code scanState} ({@link ReadRecipe#resolve}). A table with no
	 * {@code catalogAsset} at all (planned before that field existed) or whose catalog is not installed
	 * reads the same as a blank recipe - {@code ReadRecipe.resolve("", "")} - which is
	 * {@code LEGACY_FALLBACK}, never {@code FAILED}: neither case is evidence the source itself is
	 * unconfirmed/left out, only that there is nothing to read a recipe from.
	 */
	private static ReadRecipe.Eligibility recipeEligibility(final Adaptation pFactory, final FlowTable pTable)
	{
		return LandingFlows.recipeEligibility(pFactory, pTable.catalogAssetKey);
	}

	/** Same decision for one {@code LandingTable} row (also used by {@link LandingPreview} and {@link KeySourceUniqueness}): its asset key resolved by {@link CatalogReader#resolveAssetKey}. */
	static ReadRecipe.Eligibility recipeEligibility(final Adaptation pFactory, final Adaptation pLandingTable)
	{
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		return LandingFlows.recipeEligibilityIn(catalog,
			catalog == null ? null : CatalogReader.resolveAssetKey(CatalogReader.Reader.system(LandingFlows.RECIPE_REASON), catalog, pFactory, pLandingTable));
	}

	/** Same decision for an asset key already resolved. */
	static ReadRecipe.Eligibility recipeEligibility(final Adaptation pFactory, final String pCatalogAssetKey)
	{
		if (pCatalogAssetKey == null || pCatalogAssetKey.isEmpty())
		{
			return ReadRecipe.resolve("", "");
		}
		return LandingFlows.recipeEligibilityIn(CatalogReader.dataset(pFactory.getHome().getRepository()), pCatalogAssetKey);
	}

	private static ReadRecipe.Eligibility recipeEligibilityIn(final Adaptation pCatalog, final String pCatalogAssetKey)
	{
		if (pCatalog == null || pCatalogAssetKey == null || pCatalogAssetKey.isEmpty())
		{
			return ReadRecipe.resolve("", "");
		}
		final CatalogReader.AssetRecipe asset = CatalogReader.assetRecipe(CatalogReader.Reader.system(LandingFlows.RECIPE_REASON), pCatalog, pCatalogAssetKey);
		return ReadRecipe.resolve(asset.readRecipe, asset.scanState);
	}

	/** A text field of a landing table row, {@code ""} when empty or when the model has not got the field yet (a module XSD loads at the next start). */
	private static String textOrEmpty(final Adaptation pRow, final Path pPath)
	{
		if (pRow.getSchemaNode().getNode(pPath) == null)
		{
			return "";
		}
		final String text = pRow.getString(pPath);
		return text == null ? "" : text.trim();
	}

	private static List<FlowTable> flowTables(final Adaptation pFactory, final String pProjectSourceId)
	{
		final List<FlowTable> tables = new ArrayList<FlowTable>();
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (Boolean.FALSE.equals(row.get(FactoryPaths.LandingTable.SELECTED)))
				{
					continue;
				}
				if (!LandingFlows.isFlowEligibleStatus(row.getString(FactoryPaths.LandingTable.STATUS)))
				{
					continue;
				}
				final String id = row.getOccurrencePrimaryKey().format();
				final List<LandingFlow.Column> columns = new ArrayList<LandingFlow.Column>();
				for (final Adaptation column : LandingFlows.orderedColumns(pFactory, id))
				{
					columns.add(new LandingFlow.Column(column.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN), column.getString(FactoryPaths.LandingColumn.FIELD_NAME),
						Boolean.TRUE.equals(column.get(FactoryPaths.LandingColumn.SOURCE_KEY))));
				}
				final String catalogAssetKey = catalog == null ? null : CatalogReader.resolveAssetKey(CatalogReader.Reader.system(LandingFlows.RECIPE_REASON), catalog, pFactory, row);
				tables.add(new FlowTable(id, row.getString(FactoryPaths.LandingTable.TABLE_NAME), row.getString(FactoryPaths.LandingTable.SOURCE_OBJECT), columns, catalogAssetKey,
					LandingFlows.textOrEmpty(row, FactoryPaths.LandingTable.CHANGE_COLUMN), LandingFlows.textOrEmpty(row, FactoryPaths.LandingTable.CHANGE_COLUMN_KIND),
					LandingFlows.textOrEmpty(row, FactoryPaths.LandingTable.INBOUND_MAPPING)));
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(tables, new Comparator<FlowTable>()
		{
			@Override
			public int compare(final FlowTable pLeft, final FlowTable pRight)
			{
				return Integer.compare(Integer.parseInt(pLeft.id), Integer.parseInt(pRight.id));
			}
		});
		return tables;
	}

	/**
	 * Position order, duplicated from {@code LandingGenerator.orderedColumns}: that method is private and
	 * out of scope to touch here (another change is expected to point both at a shared helper).
	 */
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

	/** One landing table that already has a generated flow (an {@code inboundMapping}). */
	private static final class LoadTable
	{
		final String id;
		final String tableName;
		final String inboundMapping;
		final long sourceRowCount;
		/** Volume wave 2, lot V2-E: {@code LandingTable.changeColumn} and {@code changeColumnLoaded} - empty when the table is reloaded in full / was never loaded. */
		final String changeColumn;
		final String changeColumnLoaded;
		/** Landing test fixes (2026-10-17): the source column names, to recognise a flattened document ({@link com.on.ebx.factory.landing.FlattenedDocument}). */
		final List<String> sourceColumns;

		LoadTable(final String pId, final String pTableName, final String pInboundMapping, final long pSourceRowCount, final String pChangeColumn, final String pChangeColumnLoaded,
			final List<String> pSourceColumns)
		{
			this.sourceColumns = pSourceColumns;
			this.id = pId;
			this.tableName = pTableName;
			this.inboundMapping = pInboundMapping;
			this.sourceRowCount = pSourceRowCount;
			this.changeColumn = pChangeColumn;
			this.changeColumnLoaded = pChangeColumnLoaded;
		}
	}

	/** The source column names of a landing table (landing test fixes, 2026-10-17). */
	private static List<String> sourceColumnNames(final Adaptation pFactory, final String pLandingTableId)
	{
		final List<String> names = new ArrayList<String>();
		final RequestResult columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTableId));
		try
		{
			for (Adaptation column = columns.nextAdaptation(); column != null; column = columns.nextAdaptation())
			{
				names.add(column.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN));
			}
		}
		finally
		{
			columns.close();
		}
		return names;
	}

	private static List<LoadTable> loadTables(final Adaptation pFactory, final String pProjectSourceId)
	{
		final List<LoadTable> tables = new ArrayList<LoadTable>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String mapping = row.getString(FactoryPaths.LandingTable.INBOUND_MAPPING);
				if (mapping == null || mapping.trim().isEmpty())
				{
					continue;
				}
				final Object rawRowCount = row.get(FactoryPaths.LandingTable.SOURCE_ROW_COUNT);
				final long rowCount = rawRowCount instanceof Number ? ((Number) rawRowCount).longValue() : 0L;
				tables.add(new LoadTable(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.LandingTable.TABLE_NAME), mapping.trim(), rowCount,
					LandingFlows.textOrEmpty(row, FactoryPaths.LandingTable.CHANGE_COLUMN), LandingFlows.textOrEmpty(row, FactoryPaths.LandingTable.CHANGE_COLUMN_LOADED),
					LandingFlows.sourceColumnNames(pFactory, row.getOccurrencePrimaryKey().format())));
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(tables, new Comparator<LoadTable>()
		{
			@Override
			public int compare(final LoadTable pLeft, final LoadTable pRight)
			{
				return Integer.compare(Integer.parseInt(pLeft.id), Integer.parseInt(pRight.id));
			}
		});
		return tables;
	}

	// --------------------------------------------------------------------------------------------- asynchronous load

	/**
	 * What one table's {@link #awaitLoad} run produced (volume lot 7c, 2026-09-28): the outcome to write back
	 * on {@code LandingTable}, and whether {@code pHandle} ITSELF asked to stop - as opposed to the run
	 * finishing on its own in a {@code STOPPED}/{@code INTERRUPTED} state for some OTHER reason (another
	 * caller stopping the same run, or a server restart), which {@link RunStateOutcome#outcome} already turns
	 * into an ordinary failed outcome for this one table. Only {@link #stoppedByHandle} means the caller
	 * ({@link #load}) should stop the whole source's load rather than starting the next table.
	 */
	private static final class PollOutcome
	{
		final LandingFlow.LoadOutcome outcome;
		final boolean stoppedByHandle;

		PollOutcome(final LandingFlow.LoadOutcome pOutcome, final boolean pStoppedByHandle)
		{
			this.outcome = pOutcome;
			this.stoppedByHandle = pStoppedByHandle;
		}
	}

	/**
	 * Volume lot 7c (2026-09-28, "asynchronous landing load"): {@code pStartAnswer} is what {@code
	 * connectors_sync(..., async: true)} just answered for {@code pTable} - either the async-start
	 * acknowledgement (poll {@code connectors_run_get} every {@link #LOAD_POLL_INTERVAL_MS} to {@code
	 * execution.status}'s own terminal state, ebx-connectors docs/REFERENCE.md &sect;2.14.5) or, on an older
	 * Connectors build that ignores {@code async}, the complete synchronous answer already ({@link
	 * RunStateOutcome#isAsyncStart} tells the two apart - see its own Javadoc).
	 * <p>
	 * Checked for a stop BETWEEN polls, never mid-poll (the same posture {@code ebx.MameMatchingApi
	 * #launchScoped} already established for a long-running MCP-adjacent wait, docs/VOLUME.md "Lot 1b" task
	 * 3): when {@code pHandle} asks to stop, {@code connectors_run_stop} is called (best-effort - a failed
	 * stop call never blocks recording that this table's load was cancelled; the run may also still stop on
	 * its own between batches even if this call fails, per ebx-connectors' own task 5) and one more {@code
	 * connectors_run_get} is read to capture whatever the run had reached by then, before returning a {@code
	 * stoppedByHandle} outcome - the caller then ends the whole {@code LANDING_LOAD} step {@code STOPPED} and
	 * does not start the next table.
	 */
	private static PollOutcome awaitLoad(final McpClient pClient, final ProgressNote pNote, final FactoryJobs.Handle pHandle, final LoadTable pTable,
		final JsonNode pStartAnswer, final ObjectNode pTableDetail, final boolean pFullRefresh) throws McpException, OperationException
	{
		if (!RunStateOutcome.isAsyncStart(pStartAnswer))
		{
			return new PollOutcome(LandingFlow.loadOutcome(pStartAnswer, pTable.sourceRowCount, pFullRefresh), false);
		}
		final String runId = RunStateOutcome.runId(pStartAnswer);
		pTableDetail.put("runId", runId);
		// audit F-20: an absolute limit of its own - every iteration below checks the job in (cancelled() pushes the inactivity watchdog
		// back), so without it a run that never ends would hold this thread and the project's LANDING claim for ever
		final long waitStarted = System.currentTimeMillis();
		final long waitLimit = LoadDeadline.maxMillis();
		while (true)
		{
			if (LoadDeadline.expired(waitStarted, System.currentTimeMillis(), waitLimit))
			{
				final boolean stopAsked = LandingFlows.stopRun(pClient, runId, pTableDetail);
				final JsonNode execution = LandingFlows.tryRunGet(pClient, runId, pTableDetail);
				LandingFlows.recordNextIndex(pTableDetail, execution);
				throw EngineRefusal.coded("external.loadTimeout", LoadDeadline.message(pTable.tableName, runId, System.currentTimeMillis() - waitStarted, waitLimit,
					RunStateOutcome.processedSoFar(execution), stopAsked), pTable.tableName);
			}
			if (pHandle != null && pHandle.cancelled())
			{
				LandingFlows.stopRun(pClient, runId, pTableDetail);
				final JsonNode execution = LandingFlows.tryRunGet(pClient, runId, pTableDetail);
				LandingFlows.recordNextIndex(pTableDetail, execution);
				final long rows = RunStateOutcome.processedSoFar(execution);
				return new PollOutcome(RunStateOutcome.stoppedByHandle(pHandle.stopReason(), rows), true);
			}
			try
			{
				Thread.sleep(LandingFlows.LOAD_POLL_INTERVAL_MS);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				return new PollOutcome(RunStateOutcome.interrupted(), true);
			}
			final ObjectNode getArgs = LandingFlows.MAPPER.createObjectNode();
			getArgs.put("runId", runId);
			final McpClient.ToolResult run = pClient.callTool("connectors_run_get", getArgs);
			pTableDetail.set("poll", LandingFlows.summaryDetail(getArgs, run));
			if (run.error)
			{
				throw EngineRefusal.coded("external.pollRun", "connectors_run_get refused while polling the load of " + pTable.tableName + ": " + LandingFlows.errorText(run), pTable.tableName);
			}
			final JsonNode execution = run.json == null ? null : run.json.path("execution");
			if (execution == null || execution.isMissingNode())
			{
				// ebx-connectors docs/REFERENCE.md 2.14.5: execution{} is only absent when the row does not exist
				// (an instance predating this lot, or a run that never went through runImport) or when the
				// schema lacks the new status field - the initial ExecutionRun row is written SYNCHRONOUSLY
				// before connectors_sync(async: true) even answers, so this should not happen for a run this
				// same call just started; kept polling defensively rather than guessing a status from nothing.
				continue;
			}
			final String status = execution.path("status").asText("");
			if (!RunStateOutcome.isTerminal(status))
			{
				pNote.note(RunTexts.copiedSoFar(pTable.tableName, RunStateOutcome.processedSoFar(execution)).stored());
				continue;
			}
			LandingFlows.recordNextIndex(pTableDetail, execution);
			return new PollOutcome(RunStateOutcome.outcome(execution, pTable.sourceRowCount, pFullRefresh), false);
		}
	}

	/** Best-effort: a failed stop call is recorded on the table's detail but never thrown - the run may still stop on its own between batches. True when Connectors accepted the stop. */
	private static boolean stopRun(final McpClient pClient, final String pRunId, final ObjectNode pTableDetail)
	{
		try
		{
			final ObjectNode args = LandingFlows.MAPPER.createObjectNode();
			args.put("runId", pRunId);
			final McpClient.ToolResult stop = pClient.callTool("connectors_run_stop", args);
			pTableDetail.set("stop", LandingFlows.summaryDetail(args, stop));
			return !stop.error;
		}
		catch (final McpException ex)
		{
			pTableDetail.put("stopError", ex.getMessage());
			return false;
		}
	}

	/** One last {@code connectors_run_get} after a stop, to capture whatever the run had reached - null (never throws) when even this fails. */
	private static JsonNode tryRunGet(final McpClient pClient, final String pRunId, final ObjectNode pTableDetail)
	{
		try
		{
			final ObjectNode args = LandingFlows.MAPPER.createObjectNode();
			args.put("runId", pRunId);
			final McpClient.ToolResult run = pClient.callTool("connectors_run_get", args);
			pTableDetail.set("pollAfterStop", LandingFlows.summaryDetail(args, run));
			return run.error || run.json == null ? null : run.json.path("execution");
		}
		catch (final McpException ex)
		{
			return null;
		}
	}

	/**
	 * Records {@code execution.nextIndex} on the table's own {@code PipelineStep.detail} entry when known - not
	 * used to resume automatically in this lot (deliberately, per the task brief), only so a later lot has
	 * somewhere to read it from without re-deriving it from {@code connectors_run_get} after the fact.
	 */
	private static void recordNextIndex(final ObjectNode pTableDetail, final JsonNode pExecution)
	{
		if (pExecution != null && pExecution.hasNonNull("nextIndex"))
		{
			pTableDetail.put("nextIndex", pExecution.path("nextIndex").asLong());
		}
	}

	// ------------------------------------------------------------------------------------------------------- detail

	private static ObjectNode toolDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = LandingFlows.MAPPER.createObjectNode();
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

	/**
	 * Volume lot 7c (2026-09-28, task 2, "LandingFlows tool detail"): like {@link #toolDetail}, but for an
	 * answer that can carry a large, unbounded array - {@code results[]} on a {@code connectors_sync} feed
	 * answer (up to 1000 entries even before ebx-connectors volume lot 7a's own bound), {@code calls[]} on
	 * {@code connectors_run_get} (one {@code SyncLog} row per batch of a long run, growing on every poll of the
	 * same run). Audit finding this fixes: before this lot the WHOLE answer was serialised into {@code
	 * PipelineStep.detail} verbatim, and the per-source detail (every table's own entry, in the SAME JSON
	 * document) is truncated at {@code TextLimits.DETAIL_MAX_LENGTH} the moment a handful of tables push it
	 * past that - silently losing the LATER tables' own detail (truncation cuts the tail), not just trimming
	 * one oversized field.
	 * <p>
	 * Keeps every top-level SCALAR field verbatim (an array is dropped, never copied whole), the same for
	 * {@code execution{}}'s own fields when present, and at most {@link #DETAIL_ERROR_SAMPLE_MAX} non-OK/
	 * non-NO_CHANGE messages from {@code errorSamples[]} (falling back to {@code results[]} when there is no
	 * {@code errorSamples} at all - the shape an older Connectors build answers).
	 */
	private static ObjectNode summaryDetail(final ObjectNode pArguments, final McpClient.ToolResult pResult)
	{
		final ObjectNode node = LandingFlows.MAPPER.createObjectNode();
		node.set("arguments", pArguments);
		node.put("error", pResult.error);
		final JsonNode json = pResult.json;
		if (json == null)
		{
			node.put("resultText", LandingFlows.shortText(pResult.text));
			return node;
		}
		final ObjectNode result = node.putObject("result");
		LandingFlows.copyScalars(json, result);
		final JsonNode execution = json.path("execution");
		if (execution.isObject())
		{
			LandingFlows.copyScalars(execution, result.putObject("execution"));
		}
		final ArrayNode firstErrors = result.putArray("firstErrors");
		LandingFlows.addFirstErrors(firstErrors, json.path("errorSamples"));
		if (firstErrors.size() == 0)
		{
			LandingFlows.addFirstErrors(firstErrors, json.path("results"));
		}
		return node;
	}

	/** Every field of {@code pFrom} whose value is not an array, copied onto {@code pInto} verbatim. */
	private static void copyScalars(final JsonNode pFrom, final ObjectNode pInto)
	{
		final Iterator<String> names = pFrom.fieldNames();
		while (names.hasNext())
		{
			final String field = names.next();
			final JsonNode value = pFrom.get(field);
			if (!value.isArray())
			{
				pInto.set(field, value);
			}
		}
	}

	/** Appends up to {@link #DETAIL_ERROR_SAMPLE_MAX} non-OK/non-NO_CHANGE/non-DRY_RUN messages from {@code pArray} onto {@code pOut}. */
	private static void addFirstErrors(final ArrayNode pOut, final JsonNode pArray)
	{
		for (final JsonNode item : pArray)
		{
			if (pOut.size() >= LandingFlows.DETAIL_ERROR_SAMPLE_MAX)
			{
				return;
			}
			final String status = item.path("status").asText("");
			if ("OK".equals(status) || "NO_CHANGE".equals(status) || "DRY_RUN".equals(status))
			{
				continue;
			}
			final String message = item.path("message").asText("");
			pOut.add(message.isEmpty() ? item.path("key").asText("") : message);
		}
	}

	private static String errorText(final McpClient.ToolResult pResult)
	{
		if (pResult.json != null && pResult.json.hasNonNull("error"))
		{
			return pResult.json.path("error").asText();
		}
		return LandingFlows.shortText(pResult.text);
	}

	private static String shortText(final String pText)
	{
		final String text = pText == null ? "" : pText.trim();
		return text.length() > 500 ? text.substring(0, 500) + "..." : text;
	}
}
