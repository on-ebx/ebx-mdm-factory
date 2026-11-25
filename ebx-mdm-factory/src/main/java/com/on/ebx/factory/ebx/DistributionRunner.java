package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.DistributionArmingGate;
import com.on.ebx.factory.distribution.DistributionBatches;
import com.on.ebx.factory.distribution.DistributionMasterReadiness;
import com.on.ebx.factory.distribution.GoldenOnlySends;
import com.on.ebx.factory.distribution.SendRuns;
import com.on.ebx.factory.distribution.SyncOutcome;
import com.on.ebx.factory.distribution.TargetMappingList;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.LandingReadiness;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Distribution lot 1 (engine): sends a target's mappings through Connectors and writes the target's own
 * send bookkeeping back ({@code lastSendAt}/{@code lastStatus}/{@code lastMessage}, and the arming fields
 * for a first full send - {@code distribution.DistributionArmingGate}). Every send goes through
 * {@code connectors_sync} (scope {@code all}/{@code changed}, one call per mapping - CLAUDE.md sec 14's own
 * "one connector = one database" caution does not apply here, a mapping is already scoped to one table) via
 * {@link McpServers#connectors} (the caller's own session through the in-JVM bridge when available).
 * <p>
 * Wave 13 (2026-10-12): each {@code connectors_sync} is started with {@code async: true} and followed to its terminal state
 * ({@link DistributionSendCalls}, {@code distribution.SendRuns}), with a stop check between polls, a run id recorded in the step's detail, and a
 * guard against a second send while a recorded run still runs ({@link #refuseIfRunning}); a Connectors that does not run a scope send
 * asynchronously is sent to synchronously, as before. See {@code docs/DISTRIBUTION.md} "Following and stopping a send".
 * <p>
 * <b>Not verified live</b>: no EBX/Connectors instance was reachable while writing this class. See
 * {@code docs/DISTRIBUTION.md} for the exact steps to verify after deployment. The verdict of a call is read by
 * {@link SyncOutcome#read} (a synchronous answer, modelled on {@code LandingFlow.loadOutcome}'s inbound-sync answer) or
 * {@code SendRuns#terminal} (a followed run's {@code execution{}}).
 */
public final class DistributionRunner
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String STEP_FIRST_FULL = "DISTRIBUTION_FIRST_FULL_SEND";
	private static final String STEP_SEND = "DISTRIBUTION_SEND";

	private DistributionRunner()
	{
	}

	/** The project's own pipeline readiness for {@link DistributionArmingGate#canSendFirstFull}. */
	public static DistributionArmingGate.Readiness readiness(final Adaptation pFactory, final String pProjectId)
	{
		final boolean landing = DistributionRunner.everySourceLoaded(pFactory, pProjectId);
		final boolean master = DistributionMasterReadiness.ready(PipelineSteps.isDone(pFactory, pProjectId, "MASTER_LOAD", ""),
			DistributionRunner.masterZoneHasData(pFactory, pProjectId));
		final boolean matching = DistributionRunner.anyMatchingRunDone(pFactory, pProjectId);
		final boolean noMatchingPlanned = DistributionRunner.noMatchingPlanned(pFactory, pProjectId);
		return new DistributionArmingGate.Readiness(landing, master, matching, noMatchingPlanned);
	}

	/**
	 * Live bug fix (DistribTest, 2026-09-25): the {@code MASTER_LOAD} {@code PipelineStep} row can be
	 * missing/cleaned up even when the master zone genuinely holds data (same class of fragility
	 * {@code master.MasterStagingReadiness} already covers for {@code STAGING_LOAD}) - direct evidence
	 * instead of step history alone: does the project's master zone actually have a promoted table with at
	 * least one row. Reuses {@link ProjectMasterEntities#promotedMasterTablePaths}, the same shared helper
	 * that replaced the stale {@code StagingEntity.targetTable} read in {@link DistributionConfigurator}/
	 * {@link DistributionTriggerInstaller}. Best-effort: any lookup failure (no master zone yet, a bad
	 * dataspace/dataset pointer) reads as "no evidence", never thrown.
	 */
	static boolean masterZoneHasData(final Adaptation pFactory, final String pProjectId)
	{
		try
		{
			final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
			if (project == null)
			{
				return false;
			}
			final String masterDataspace = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
			final String masterDataset = project.getString(FactoryPaths.Project.MASTER_DATASET);
			if (masterDataspace == null || masterDataspace.trim().isEmpty() || masterDataset == null || masterDataset.trim().isEmpty())
			{
				return false;
			}
			final java.util.List<String> tablePaths = ProjectMasterEntities.promotedMasterTablePaths(pFactory, pProjectId);
			if (tablePaths.isEmpty())
			{
				return false;
			}
			final Repository repository = pFactory.getHome().getRepository();
			final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspace));
			final Adaptation masterDatasetRoot = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDataset));
			if (masterDatasetRoot == null)
			{
				return false;
			}
			for (final String tablePath : tablePaths)
			{
				final AdaptationTable table = masterDatasetRoot.getTable(Path.parse(tablePath));
				if (table == null)
				{
					continue;
				}
				final RequestResult rows = table.createRequestResult(null);
				try
				{
					if (rows.nextAdaptation() != null)
					{
						return true;
					}
				}
				finally
				{
					rows.close();
				}
			}
			return false;
		}
		catch (final RuntimeException ex)
		{
			// best-effort evidence, see this method's own Javadoc: a lookup hiccup here must never hide the
			// PipelineStep-based signal readiness() already computed, nor blow up the wizard's own render.
			return false;
		}
	}

	/**
	 * Distribution round 4 fix (2026-09-26, task brief item 2, DistribTest live incident): used to require
	 * {@code ProjectSource.status == "LOADED"}, a rolled-up status the wizard's own landing flow never
	 * actually sets that way once the tables themselves are loaded - DistribTest's source stayed
	 * {@code GENERATED} although its one landing table was genuinely loaded ({@code PipelineStep LANDING_LOAD
	 * DONE "Loaded 1 landing table(s)"}, 180 rows), so the checklist item stayed unticked forever. Reads the
	 * real per-TABLE evidence {@code wizard.WizardRules#allTickedTablesLoaded} already gates the Staging
	 * step's reachability on, through the SAME shared pure rule ({@link LandingReadiness}) - never a third,
	 * independent notion of "landing done".
	 */
	private static boolean everySourceLoaded(final Adaptation pFactory, final String pProjectId)
	{
		final List<LandingReadiness.Table> tables = new ArrayList<LandingReadiness.Table>();
		final RequestResult sourceRows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation source = sourceRows.nextAdaptation(); source != null; source = sourceRows.nextAdaptation())
			{
				final String sourceId = source.getOccurrencePrimaryKey().format();
				final RequestResult tableRows = pFactory.getTable(FactoryPaths.LANDING_TABLE)
					.createRequestResult("./projectSource=" + CatalogReader.quote(sourceId));
				try
				{
					for (Adaptation table = tableRows.nextAdaptation(); table != null; table = tableRows.nextAdaptation())
					{
						// same "absent/non-boolean defaults to true" reading WizardData#readLandingTable already uses
						final boolean selected = !Boolean.FALSE.equals(table.get(FactoryPaths.LandingTable.SELECTED));
						final String status = table.getString(FactoryPaths.LandingTable.STATUS);
						tables.add(new LandingReadiness.Table(selected, "LOADED".equals(status)));
					}
				}
				finally
				{
					tableRows.close();
				}
			}
		}
		finally
		{
			sourceRows.close();
		}
		return LandingReadiness.allTickedTablesLoaded(tables);
	}

	private static boolean anyMatchingRunDone(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote("MATCHING_RUN") + " and ./status=" + CatalogReader.quote("DONE"));
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Task brief item 3 (DistribTest live incident): a project may legitimately have no matching plan at all
	 * - the rule pass skips an entity for lack of a validated identifier ({@code master.MatchPlanRules}), and
	 * the language-model pass skips it too, so NO {@code MatchPlan} row is ever written for that entity.
	 * Requiring "first matching run finished" in that case blocks arming forever for no real reason. True only
	 * when NOT ONE {@code MatchPlan} row exists for any promoted {@code StagingEntity} of the project -
	 * {@link DistributionArmingGate.Readiness#matchingSatisfied} then accepts either a finished run OR this.
	 */
	private static boolean noMatchingPlanned(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation entity = entityRows.nextAdaptation(); entity != null; entity = entityRows.nextAdaptation())
			{
				final String entityId = entity.getOccurrencePrimaryKey().format();
				final RequestResult planRows = pFactory.getTable(FactoryPaths.MATCH_PLAN).createRequestResult("./entity=" + CatalogReader.quote(entityId));
				try
				{
					if (planRows.nextAdaptation() != null)
					{
						return false; // at least one plan exists somewhere for this project - matching IS planned
					}
				}
				finally
				{
					planRows.close();
				}
			}
			return true;
		}
		finally
		{
			entityRows.close();
		}
	}

	/** Lot S2: the master dataset of the project, or null while it has none or it cannot be reached. */
	private static Adaptation masterDataset(final Adaptation pFactory, final Adaptation pProject)
	{
		final String dataspace = pProject.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String dataset = pProject.getString(FactoryPaths.Project.MASTER_DATASET);
		if (dataspace == null || dataspace.trim().isEmpty() || dataset == null || dataset.trim().isEmpty())
		{
			return null;
		}
		final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(dataspace));
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(dataset));
	}

	/** Lot S2: {@link GoldenOnlySends#predicate} for the table - null when it carries no {@code Linked_Field_State} (no Match and Merge on it) or cannot be read. */
	private static String livePredicate(final Adaptation pFactory, final Adaptation pProject, final String pTablePath)
	{
		try
		{
			final Adaptation dataset = DistributionRunner.masterDataset(pFactory, pProject);
			final AdaptationTable table = dataset == null ? null : dataset.getTable(Path.parse(pTablePath));
			final boolean hasState = table != null && table.getTableOccurrenceRootNode().getNode(Path.parse("./Linked_Field_State")) != null;
			return GoldenOnlySends.predicate(hasState);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Lot S2: best effort - see {@link MergeLogWatcher#captureBaseline}. Only a REALTIME target is watched for merges. */
	private static void captureMergeLogBaseline(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final String pTargetId)
	{
		try
		{
			final Adaptation target = DistributionConfigurator.findTarget(pFactory, pTargetId);
			final Adaptation dataset = DistributionRunner.masterDataset(pFactory, pProject);
			if (target == null || dataset == null || !FactoryPaths.DistributionTarget.MODE_REALTIME.equals(target.getString(FactoryPaths.DistributionTarget.MODE)))
			{
				return;
			}
			MergeLogWatcher.captureBaseline(pSession, pFactory, dataset.getHome(), dataset.getAdaptationName().getStringName(), target, pTargetId,
				com.orchestranetworks.service.LoggingCategory.getKernel());
		}
		catch (final RuntimeException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] merge log baseline of target " + pTargetId + " was not recorded: " + ex, ex);
		}
	}

	/** Scope-ALL send of every mapping of the target: on success the target is ARMED, on failure it WAITS - see {@code distribution.DistributionArmingGate}. Refused outright (no send attempted) when the project's pipeline is not ready. */
	public static ObjectNode sendFirstFull(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId) throws OperationException
	{
		return DistributionRunner.sendFirstFull(pSession, pFactory, pProjectCode, pTargetId, null);
	}

	/**
	 * {@link #sendFirstFull(Session, Adaptation, String, String)} run by a job: {@code pHandle} (null for a direct call) is checked for a stop between
	 * the polls of the Connectors run, see {@link DistributionSendCalls}. A send stopped that way leaves the target WAITING, like a failed one, and
	 * writes no {@code firstFullSendRunId}.
	 */
	public static ObjectNode sendFirstFull(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId, final FactoryJobs.Handle pHandle)
		throws OperationException
	{
		final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " does not exist", pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final DistributionArmingGate.Decision gate = DistributionArmingGate.canSendFirstFull(DistributionRunner.readiness(pFactory, projectId));
		if (!gate.allowed)
		{
			throw EngineRefusal.coded("conflict.firstSendRefused", "Cannot start the first full send: " + gate.reason);
		}
		// lot S2 (2026-10-18): note where the merge log stands BEFORE the send reads a single row - a record merged under that mark is not live to the target
		// (the send leaves merged records out) and needs no retire notice; one merged after it does. See distribution.MatchingStateSweep.
		DistributionRunner.captureMergeLogBaseline(pSession, pFactory, project, pTargetId);
		final ObjectNode result = DistributionRunner.send(pSession, pFactory, project, projectId, pTargetId, "all", DistributionRunner.STEP_FIRST_FULL, pHandle);
		final boolean succeeded = "DONE".equals(result.path("status").asText(""));
		final String newState = DistributionArmingGate.stateAfterFirstFullSend(succeeded);
		// wave 13: the Connectors run ids of the mappings (the Factory's step id when none is known) - what the wizard's "first full send done" tick reads
		final java.util.List<String> runIds = new ArrayList<String>();
		for (final com.fasterxml.jackson.databind.JsonNode id : result.path("connectorsRunIds"))
		{
			runIds.add(id.asText(""));
		}
		DistributionRunner.writeTargetState(pSession, pFactory, pTargetId, newState, false, succeeded ? SendRuns.fieldValue(runIds, result.path("runId").asText(null)) : null);
		result.put("targetState", newState);
		return result;
	}

	/** Admin-only override (enforced by the caller, {@code rest.FactoryResource}): arms the target directly, with no send attempted. {@code DistributionTarget.armedViaOverride} records it for audit. */
	public static ObjectNode armWithoutFirstFullSend(final Session pSession, final Adaptation pFactory, final String pTargetId) throws OperationException
	{
		DistributionRunner.writeTargetState(pSession, pFactory, pTargetId, DistributionArmingGate.stateForOverride(), true, null);
		final ObjectNode out = DistributionRunner.MAPPER.createObjectNode();
		out.put("status", "DONE");
		out.put("targetState", DistributionArmingGate.stateForOverride());
		out.put("message", "Armed without a successful first full send (administrator override).");
		return out;
	}

	/** An ON_DEMAND/SCHEDULED (or manual "send now") batch: {@code pScope} is {@code changed} or {@code all}; null/blank falls back to the target's own {@code scope} field. Does not change the target's arming state. */
	public static ObjectNode sendNow(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId, final String pScope) throws OperationException
	{
		return DistributionRunner.sendNow(pSession, pFactory, pProjectCode, pTargetId, pScope, null);
	}

	/** {@link #sendNow(Session, Adaptation, String, String, String)} run by a job: {@code pHandle} (null for a direct call) is checked for a stop between the polls of the Connectors run. */
	public static ObjectNode sendNow(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId, final String pScope,
		final FactoryJobs.Handle pHandle) throws OperationException
	{
		final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " does not exist", pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation target = DistributionConfigurator.findTarget(pFactory, pTargetId);
		final String scope = pScope == null || pScope.trim().isEmpty()
			? (target == null ? "changed" : String.valueOf(target.getString(FactoryPaths.DistributionTarget.SCOPE)).toLowerCase(java.util.Locale.ENGLISH))
			: pScope.toLowerCase(java.util.Locale.ENGLISH);
		return DistributionRunner.send(pSession, pFactory, project, projectId, pTargetId, scope, DistributionRunner.STEP_SEND, pHandle);
	}

	/**
	 * Wave 13 (2026-10-12, "asynchronous distribution send"): every mapping's {@code connectors_sync} is started with {@code async: true} and followed
	 * to its terminal state ({@link DistributionSendCalls}) instead of held for as long as it lasts - a synchronous call of 88,991 rows lasted 9 min 36
	 * on Vol100k and was cut by the fixed 300 s read timeout while Connectors went on sending. The run id of each mapping is written into the step's
	 * detail the moment it is known ({@code tables[].connectorsRunId}), which is also what the double-send guard ({@link #refuseIfRunning}) reads. A
	 * stop asked of {@code pHandle} between two polls stops the run and ends the send STOPPED. A Connectors that does not run a scope send
	 * asynchronously (the one of 2026-10-12 refuses it) is sent to synchronously, as before, under {@link DistributionBatches#sendTimeoutMs}.
	 */
	private static ObjectNode send(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final String pProjectId, final String pTargetId,
		final String pScope, final String pStep, final FactoryJobs.Handle pHandle) throws OperationException
	{
		final Adaptation target = DistributionConfigurator.findTarget(pFactory, pTargetId);
		if (target == null || !pProjectId.equals(target.getString(FactoryPaths.DistributionTarget.PROJECT)))
		{
			throw EngineRefusal.coded("invalid.targetNotOfProject", "Distribution target " + pTargetId + " does not belong to this project", pTargetId);
		}
		final Map<String, String> mappings = TargetMappingList.parse(target.getString(FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS));
		if (mappings.isEmpty())
		{
			throw EngineRefusal.coded("missing.targetMapping", "Distribution target " + pTargetId + " has no configured mapping yet: run the configure step first", pTargetId);
		}
		DistributionRunner.refuseIfRunning(pSession, pFactory, pProjectId, pTargetId);
		final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, pStep, pTargetId);
		final ObjectNode detail = DistributionRunner.MAPPER.createObjectNode();
		final ArrayNode tableDetails = detail.putArray("tables");
		final List<String> connectorsRunIds = new ArrayList<String>();
		long totalRows = 0L;
		long totalSent = 0L;
		long totalUnchanged = 0L;
		// step 7 screen counts (2026-10-18): rows sent per master table, in the order the send ran - the end message names them
		final Map<String, Long> sentByTable = new java.util.LinkedHashMap<String, Long>();
		boolean anyFailed = false;
		boolean stoppedByHandle = false;
		boolean stopPending = false;
		final StringBuilder messages = new StringBuilder();
		String currentMapping = null;
		try
		{
			final int maxRows = DistributionBatches.maxRows();
			// the ordinary client: the async start and the polls answer at once. The long read timeout (DistributionBatches.sendTimeoutMs) is built only
			// if Connectors refuses async for this send and the call has to be synchronous
			final McpClient client = McpServers.connectors(pSession, pFactory);
			detail.put("transport", McpServers.connectorsTransport(pSession));
			final DistributionSendCalls calls = new DistributionSendCalls(client, new DistributionSendCalls.ClientSource()
			{
				@Override
				public McpClient syncClient() throws OperationException
				{
					final int sendTimeoutMs = DistributionBatches.sendTimeoutMs(maxRows);
					detail.put("syncReadTimeoutSeconds", sendTimeoutMs / 1000);
					return McpServers.connectors(pSession, pFactory, sendTimeoutMs);
				}
			}, pHandle);
			for (final Map.Entry<String, String> entry : mappings.entrySet())
			{
				if (calls.stopAsked())
				{
					stoppedByHandle = true;
					break;
				}
				currentMapping = entry.getValue();
				final ObjectNode tableDetail = tableDetails.addObject();
				tableDetail.put("tablePath", entry.getKey());
				tableDetail.put("mapping", entry.getValue());
				final ObjectNode args = DistributionRunner.MAPPER.createObjectNode();
				args.put("mapping", entry.getValue());
				args.put("scope", pScope);
				args.put("dryRun", false);
				// lot S2 (2026-10-18): only live records - Connectors' scope sends walk the whole table and ignore the mapping's mergedRecords=exclude input
				// (read by the data-product publication only), so MERGED and DELETED records used to be sent as if they were golden
				final String livePredicate = DistributionRunner.livePredicate(pFactory, pProject, entry.getKey());
				if (livePredicate != null)
				{
					args.put("predicate", livePredicate);
					tableDetail.put("predicate", livePredicate);
				}
				// audit C-35 (wave 10): an explicit max - without it Connectors took its API default (200) and a larger table
				// was only partly sent, in silence; a "changed" send that is still cut asks again (its backlog drains), see DistributionBatches
				args.put("max", maxRows);
				final DistributionSendCalls.Recorder recorder = new DistributionSendCalls.Recorder()
				{
					@Override
					public void runStarted(final String pRunId)
					{
						// the run is written down before the wait: the guard of the next send, a person and a restarted Factory find it there
						PipelineSteps.updateDetail(pSession, pFactory, stepId, detail.toString());
					}
				};
				long tableRows = 0L;
				long tableSent = 0L;
				long tableUnchanged = 0L;
				int rounds = 0;
				SyncOutcome.Result outcome;
				boolean stoppedHere = false;
				do
				{
					// distribution round 4 fix (2026-09-26, task brief item 1, "false success"): connectors_sync answers
					// WITHOUT sync.error even when every record failed to reach the target (a downed Kafka
					// broker, live-measured on DistribTest) - SyncOutcome, not sync.error alone, is the real verdict.
					// A followed run is read into the same SyncOutcome.Result (SendRuns.terminal).
					final DistributionSendCalls.Round round = calls.round(args, tableDetail, recorder);
					outcome = round.outcome;
					rounds++;
					tableRows += outcome.rows;
					tableSent += outcome.sent;
					tableUnchanged += outcome.unchanged;
					DistributionRunner.noteRunId(tableDetail, connectorsRunIds, outcome.runId);
					stoppedHere = round.stoppedByHandle;
				}
				while (!stoppedHere && outcome.ok && DistributionBatches.askAgain(pScope, outcome.truncated, outcome.rows, rounds));
				tableDetail.put("ok", outcome.ok && !outcome.truncated && !stoppedHere);
				tableDetail.put("rounds", rounds);
				if (outcome.runId != null)
				{
					tableDetail.put("connectorsRunId", outcome.runId);
				}
				if (stoppedHere)
				{
					stoppedByHandle = true;
					tableDetail.put("rows", tableRows);
					tableDetail.put("sent", tableSent);
					tableDetail.put("unchanged", tableUnchanged);
					totalRows += tableRows;
					totalSent += tableSent;
					totalUnchanged += tableUnchanged;
					tableDetail.put("stopped", true);
					stopPending = tableDetail.path("stopPending").asBoolean(false);
					break;
				}
				if (!outcome.ok)
				{
					anyFailed = true;
					tableDetail.put("error", outcome.message);
					messages.append(entry.getKey()).append(": ").append(outcome.message).append(' ');
					continue;
				}
				tableDetail.put("rows", tableRows);
				tableDetail.put("sent", tableSent);
				tableDetail.put("unchanged", tableUnchanged);
				totalRows += tableRows;
				totalSent += tableSent;
				totalUnchanged += tableUnchanged;
				sentByTable.put(DistributionRunner.tableLabel(entry.getKey()), Long.valueOf(tableSent));
				if (outcome.truncated)
				{
					// a partial full send is never a success: the records sent stay sent, the target says what is missing
					anyFailed = true;
					final String cut = DistributionBatches.cutMessage(entry.getKey(), pScope, tableRows, maxRows);
					tableDetail.put("error", cut);
					messages.append(cut).append(' ');
				}
			}
			detail.put("async", calls.asyncSupported());
			if (stoppedByHandle)
			{
				final String reason = pHandle == null || pHandle.stopReason() == null ? "stopped" : pHandle.stopReason();
				final String stopped = DistributionBatches.stoppedMessage(reason, totalSent, totalUnchanged, stopPending);
				PipelineSteps.endStopped(pSession, pFactory, stepId, stopped, detail.toString());
				DistributionRunner.writeLastSend(pSession, pFactory, pTargetId, "STOPPED", stopped);
				final ObjectNode stoppedResult = DistributionRunner.result("STOPPED", stepId, totalRows, stopped, detail, connectorsRunIds, null);
				stoppedResult.put("sent", totalSent);
				stoppedResult.put("unchanged", totalUnchanged);
				return stoppedResult;
			}
			final boolean ok = !anyFailed;
			// step 7 screen counts (2026-10-18): the sentence is coded (RunTexts) so the wizard words it in the person's language, with the rows sent per table; the REST answer keeps the plain English
			final com.on.ebx.factory.landing.KeyTexts.Text sentText = ok
				? com.on.ebx.factory.landing.RunTexts.sent(totalSent, totalUnchanged, mappings.size(), pScope, DistributionRunner.sentDetail(sentByTable)) : null;
			final String message = ok ? sentText.stored() : messages.toString().trim();
			PipelineSteps.end(pSession, pFactory, stepId, ok, message, detail.toString());
			final String lastSendError = DistributionRunner.writeLastSend(pSession, pFactory, pTargetId, ok ? "DONE" : "FAILED", message);
			final ObjectNode doneResult = DistributionRunner.result(ok ? "DONE" : "FAILED", stepId, totalRows, ok ? sentText.english : message, detail, connectorsRunIds, lastSendError);
			if (ok)
			{
				doneResult.put("messageCoded", message);
			}
			doneResult.put("sent", totalSent);
			doneResult.put("unchanged", totalUnchanged);
			return doneResult;
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			DistributionRunner.writeLastSend(pSession, pFactory, pTargetId, "FAILED", ex.getMessage());
			throw ex;
		}
		catch (final McpException ex)
		{
			if (currentMapping != null && DistributionBatches.isReadTimeout(ex.getMessage()))
			{
				// the Factory stopped waiting, Connectors did not: say so, and where to look (wave 13)
				final String timedOut = DistributionBatches.timeoutMessage(ex.getMessage(), currentMapping);
				PipelineSteps.end(pSession, pFactory, stepId, false, timedOut, detail.toString());
				DistributionRunner.writeLastSend(pSession, pFactory, pTargetId, "FAILED", timedOut);
				throw EngineRefusal.coded("external.sendTargetTimeout", "Could not send distribution target " + pTargetId + ": " + timedOut, pTargetId, currentMapping);
			}
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			DistributionRunner.writeLastSend(pSession, pFactory, pTargetId, "FAILED", ex.getMessage());
			throw EngineRefusal.coded("external.sendTarget", "Could not send distribution target " + pTargetId + ": " + ex.getMessage(), pTargetId);
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
			throw ex;
		}
	}

	/** "Personne" of "/root/Personne" - the name the wizard and the person use for a master table. */
	static String tableLabel(final String pTablePath)
	{
		if (pTablePath == null)
		{
			return "";
		}
		final int slash = pTablePath.lastIndexOf('/');
		return slash >= 0 ? pTablePath.substring(slash + 1) : pTablePath;
	}

	/** "Personne 212, Adresse 534, Achat 234": the per-table counts of a send, blank for a send of one table (the total is that table's count). */
	static String sentDetail(final Map<String, Long> pSentByTable)
	{
		if (pSentByTable.size() < 2)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Long> entry : pSentByTable.entrySet())
		{
			out.append(out.length() == 0 ? "" : ", ").append(entry.getKey()).append(' ').append(entry.getValue());
		}
		return out.toString();
	}

	private static ObjectNode result(final String pStatus, final String pStepId, final long pRows, final String pMessage, final ObjectNode pDetail, final List<String> pRunIds,
		final String pLastSendError)
	{
		final ObjectNode out = DistributionRunner.MAPPER.createObjectNode();
		out.put("status", pStatus);
		out.put("runId", pStepId);
		out.put("rows", pRows);
		out.put("message", pMessage);
		final ArrayNode ids = out.putArray("connectorsRunIds");
		for (final String id : pRunIds)
		{
			ids.add(id);
		}
		if (pLastSendError != null)
		{
			// the send itself ran and its step says so; the target's own lastStatus/lastMessage did not move, which is a stale screen, not a failed send
			out.put("lastSendNotRecorded", pLastSendError);
		}
		out.set("detail", pDetail);
		return out;
	}

	/** Keeps the Connectors run id of a round: all of them in {@code pAll} (the result), the last {@link SendRuns#RUN_IDS_KEPT} of the table in its detail. */
	private static void noteRunId(final ObjectNode pTableDetail, final List<String> pAll, final String pRunId)
	{
		if (pRunId == null || pRunId.isEmpty())
		{
			return;
		}
		if (!pAll.contains(pRunId))
		{
			pAll.add(pRunId);
		}
		final ArrayNode ids = pTableDetail.withArray("runIds");
		if (ids.size() == 0 || !pRunId.equals(ids.get(ids.size() - 1).asText()))
		{
			ids.add(pRunId);
		}
		while (ids.size() > SendRuns.RUN_IDS_KEPT)
		{
			ids.remove(0);
		}
	}

	/**
	 * Wave 13 double-send guard: a send of the target whose Connectors run is still RUNNING (the Factory restarted, a person clicked twice before the
	 * first job showed) is refused BEFORE a step is written. Reads the run ids recorded by the target's latest send step and the ones stored on the
	 * target, asks {@code connectors_run_get} about each; a run Connectors no longer knows, or cannot answer for, does not block.
	 */
	static void refuseIfRunning(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pTargetId) throws OperationException
	{
		final List<String> recorded = DistributionRunner.recordedRunIds(pFactory, pProjectId, pTargetId);
		if (recorded.isEmpty())
		{
			return;
		}
		final McpClient client = McpServers.connectors(pSession, pFactory);
		final String running = SendRuns.firstRunning(recorded, new SendRuns.RunLookup()
		{
			@Override
			public boolean isRunning(final String pRunId)
			{
				return DistributionRunner.runIsRunning(client, pRunId);
			}
		});
		if (running != null)
		{
			throw EngineRefusal.coded("conflict.sendStillRunning", "A send of target " + pTargetId + " is still running: run " + running + "; wait for it or stop it", pTargetId, running);
		}
	}

	/** True when Connectors reports {@code pRunId} as RUNNING; false for a finished run, an unknown one, or a failed question. */
	static boolean runIsRunning(final McpClient pClient, final String pRunId)
	{
		return SendRuns.isRunning(DistributionRunner.runState(pClient, pRunId));
	}

	/** The {@code connectors_run_get} answer for a run (calls omitted), or null when it cannot be read. */
	static com.fasterxml.jackson.databind.JsonNode runState(final McpClient pClient, final String pRunId)
	{
		try
		{
			final ObjectNode args = DistributionRunner.MAPPER.createObjectNode();
			args.put("runId", pRunId);
			args.put("callsMax", 0);
			final McpClient.ToolResult run = pClient.callTool("connectors_run_get", args);
			return run.error ? null : run.json;
		}
		catch (final McpException ex)
		{
			return null;
		}
	}

	/** The Connectors run ids the target's latest send step and its {@code firstFullSendRunId} name (see {@link SendRuns#candidateRunIds}). */
	static List<String> recordedRunIds(final Adaptation pFactory, final String pProjectId, final String pTargetId)
	{
		String detail = null;
		long bestAt = Long.MIN_VALUE;
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./scope="
			+ CatalogReader.quote(pTargetId) + " and (./step=" + CatalogReader.quote(DistributionRunner.STEP_SEND) + " or ./step=" + CatalogReader.quote(DistributionRunner.STEP_FIRST_FULL) + ")");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object started = row.get(FactoryPaths.PipelineStep.STARTED_AT);
				final long at = started instanceof Date ? ((Date) started).getTime() : 0L;
				if (detail == null || at >= bestAt)
				{
					detail = row.getString(FactoryPaths.PipelineStep.DETAIL);
					bestAt = at;
				}
			}
		}
		finally
		{
			rows.close();
		}
		final Adaptation target = DistributionConfigurator.findTarget(pFactory, pTargetId);
		return SendRuns.candidateRunIds(detail, target == null ? null : target.getString(FactoryPaths.DistributionTarget.FIRST_FULL_SEND_RUN_ID));
	}

	/** @return null when the target's last-send fields were written, else why not (also logged): a send whose own outcome is recorded elsewhere (its step) must say that the target's status is stale */
	private static String writeLastSend(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pStatus, final String pMessage)
	{
		try
		{
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					final Adaptation row = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
					if (row == null)
					{
						return;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(new Date(), FactoryPaths.DistributionTarget.LAST_SEND_AT);
					value.setValue(pStatus, FactoryPaths.DistributionTarget.LAST_STATUS);
					value.setValue(com.on.ebx.factory.landing.TextLimits.truncate(pMessage, com.on.ebx.factory.landing.TextLimits.MESSAGE_MAX_LENGTH),
						FactoryPaths.DistributionTarget.LAST_MESSAGE);
					pContext.doModifyContent(row, value);
				}
			});
			if (result.hasFailed())
			{
				final String why = result.getExceptionFullMessage(java.util.Locale.ENGLISH);
				com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] could not record the last send of distribution target " + pTargetId + ": " + why);
				return why;
			}
			return null;
		}
		catch (final RuntimeException ex)
		{
			// best-effort bookkeeping, same discipline as PipelineSteps.end: never let a write-back failure hide the real outcome
			com.orchestranetworks.service.LoggingCategory.getKernel().error("[mdm-factory] could not record the last send of distribution target " + pTargetId + ": " + ex);
			return String.valueOf(ex);
		}
	}

	private static void writeTargetState(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pState, final boolean pOverride,
		final String pFirstFullSendRunId) throws OperationException
	{
		final Adaptation before = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
		final String projectId = before == null ? null : before.getString(FactoryPaths.DistributionTarget.PROJECT);
		final String oldMode = before == null ? null : before.getString(FactoryPaths.DistributionTarget.MODE);
		final String oldState = before == null ? null : before.getString(FactoryPaths.DistributionTarget.STATE);
		// volume-operations lot 8b (2026-09-28, task 1): this is the actual arming path (sendFirstFull on
		// success, armWithoutFirstFullSend) - bump the control row BEFORE the state below commits, see
		// DistributionControlRow's own Javadoc. A no-op when pState is not an ARM transition (e.g. WAITING on
		// a failed first-full-send attempt).
		DistributionControlRow.beforeStateChange(pSession, pFactory, projectId, oldMode, oldState, oldMode, pState);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pState, FactoryPaths.DistributionTarget.STATE);
				if (pOverride)
				{
					value.setValue(Boolean.TRUE, FactoryPaths.DistributionTarget.ARMED_VIA_OVERRIDE);
				}
				if (pFirstFullSendRunId != null)
				{
					value.setValue(pFirstFullSendRunId, FactoryPaths.DistributionTarget.FIRST_FULL_SEND_RUN_ID);
				}
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveTargetState", "Distribution target " + pTargetId + ": writing the new state failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH), pTargetId);
		}
		// volume-operations lot 8a (2026-09-28, task 3): see DistributionPause.setState's own comment.
		com.on.ebx.factory.distribution.ArmedTargetCache.invalidate(projectId);
		// volume-operations lot 8b: the DISARM half - a no-op here in practice (this method never moves a
		// target away from ARMED), kept for symmetry/defensiveness should a future caller ever pass such a state.
		DistributionControlRow.afterStateChange(pSession, pFactory, projectId, oldMode, oldState, oldMode, pState);
	}
}
