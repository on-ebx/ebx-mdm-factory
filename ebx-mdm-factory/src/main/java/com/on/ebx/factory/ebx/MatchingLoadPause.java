package com.on.ebx.factory.ebx;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.LoadPauseRule;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.master.MameAnswer;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.master.MatchingLoadPauseRule;
import com.on.ebx.factory.master.MatchingPauseSummary;
import com.on.ebx.factory.staging.StagingModel;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot MP (2026-10-04, docs/MASTER-ZONE.md "Matching paused during a load (lot MP)"): pauses the Match and Merge add-on's matching on the master tables of a
 * REALTIME project while {@code MasterLoader.load} writes to them, resumes exactly the tables this load paused whatever happened, and then catches up on the
 * records written during the window ({@link MatchingCatchUp}). Used by {@code MasterLoader.load(...)} next to {@link DistributionPause}, the precedent:
 * {@link #begin} before the load, {@link #finish} in a {@code finally}.
 * <p>
 * <b>The measured defect this guards against</b> (2026-10-04, project Vol1mF, EBX 6.3.0-rc2): a matching policy whose execution mode is "on update" makes the
 * add-on run an inline matching at EVERY batch commit of a master load ({@code handleBeforeTransactionCommit /root/Adresse -> "Start clustering"}, about 50 ms per
 * batch of 500). On the 8th batch of one load a clustering thread recovered the table's Lucene index to an older transaction; from then on every read of that
 * table failed ("requested transaction ... is in the future of the last transaction ...") until Tomcat was restarted - a product defect, reported. With the
 * matching PAUSED ({@code set_table_settings {"pauseMatching": true}}, then {@code get_matching_activation_status} answers {@code state: PAUSED, paused: true})
 * a load of 634,295 updated rows ran to the end.
 * <p>
 * <b>The rules.</b> (1) Only when {@code Project.matchingTrigger} is {@code REALTIME}; in {@code MANUAL} mode (lot MT makes the policy's execution mode manual,
 * so a load triggers no inline matching) this class does nothing at all - {@link #INACTIVE}, no call, no detail. The field is read by its path {@code
 * ./matchingTrigger} through the record's schema node, so a model that does not have it yet (lot MT not deployed) reads as manual. (2) Tables: the master tables
 * of the project's built entities whose {@code MatchPlan} is {@code APPLIED} (the facts {@code MatchingConfigurator.runInternal} uses). Each is read with {@code
 * get_matching_activation_status}; only an ENABLED, not-paused table is paused ({@link MatchingLoadPauseRule#decide}); a table already paused by someone else is
 * left alone and NOT resumed by this load. (3) {@code pauseStartedAt} is taken BEFORE the first call and recorded with the tables in the {@code MASTER_LOAD}
 * step's detail ({@code matchingPause}). (4) In {@link #finish}, whatever the load did (success, failure, stop), exactly the tables this load paused are resumed
 * ({@code pauseMatching: false}, one retry); a resume that fails is said loudly - first in the step's message, with the call that resumes the table - and never
 * masks the load's own exception. (5) If no match-merge client can be obtained, nothing is paused and the detail says so: the load runs as it did before.
 * <p>
 * <b>The catch-up</b> runs only after a load that ended DONE and a successful resume: after a failure or a stop the records written before it stay unmatched
 * (said in the detail). It is a step of the same call, not of {@code MasterBuildRun}, so that every caller of {@code MasterLoader.load} (REST route, workflow bean,
 * the job) gets it; its own sentence goes into the {@code MASTER_LOAD} step's message ({@link MatchingPauseSummary}).
 * <p>
 * Not exercised live when written: see docs/MASTER-ZONE.md for what only an instance can confirm.
 */
final class MatchingLoadPause
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Path TRIGGER = Path.parse("./matchingTrigger");
	private static final String ACTIVE_PLAN = "APPLIED";

	/** The pause of a project that does not need one (manual trigger, no applied plan): every method is a no-op. */
	static final MatchingLoadPause INACTIVE = new MatchingLoadPause(null, null, null, null, null, null, null, null);

	private final Session session;
	private final Adaptation factory;
	private final String projectCode;
	private final String projectId;
	private final String masterDataspaceKey;
	private final String masterDatasetName;
	private final Repository repository;
	/** Where each pause is recorded before it is applied ({@link LoadPauseLedger}); null for a pause that is not recorded (never the case for a load). */
	private final LoadPauseLedger.Owner owner;
	private final boolean active;
	private final ObjectNode detail = MatchingLoadPause.MAPPER.createObjectNode();
	private final ArrayNode tablesNode = this.detail.putArray("tables");
	/** The tables this load will resume - the ones it paused, and the ones whose pause call failed in a way that leaves the state uncertain. */
	private final List<Paused> toResume = new ArrayList<Paused>();
	private McpClient client;
	private String publicationName;
	private Date pauseStartedAt;
	private volatile String stepId;

	private static final class Paused
	{
		final String entityName;
		final String tablePath;
		final ObjectNode node;

		Paused(final String pEntityName, final String pTablePath, final ObjectNode pNode)
		{
			this.entityName = pEntityName;
			this.tablePath = pTablePath;
			this.node = pNode;
		}
	}

	private MatchingLoadPause(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final String pMasterDataspaceKey,
		final String pMasterDatasetName, final Repository pRepository, final LoadPauseLedger.Owner pOwner)
	{
		this.owner = pOwner;
		this.session = pSession;
		this.factory = pFactory;
		this.projectCode = pProjectCode;
		this.projectId = pProjectId;
		this.masterDataspaceKey = pMasterDataspaceKey;
		this.masterDatasetName = pMasterDatasetName;
		this.repository = pRepository;
		this.active = pSession != null;
	}

	/**
	 * Pauses what must be paused for a load of {@code pProject}; never throws (a problem here must not block the load, it is said in the detail) and returns
	 * {@link #INACTIVE} when the project is not in the REALTIME trigger mode or has no table with an applied matching plan.
	 */
	static MatchingLoadPause begin(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final String pProjectCode, final LoadPauseLedger.Owner pOwner)
	{
		try
		{
			if (pProject == null || !MatchingLoadPauseRule.isRealtime(MatchingLoadPause.readTrigger(pProject)))
			{
				return MatchingLoadPause.INACTIVE;
			}
			final String masterDataspaceKey = pProject.getString(FactoryPaths.Project.MASTER_DATASPACE);
			final String masterDatasetName = pProject.getString(FactoryPaths.Project.MASTER_DATASET);
			if (masterDataspaceKey == null || masterDataspaceKey.trim().isEmpty() || masterDatasetName == null || masterDatasetName.trim().isEmpty())
			{
				return MatchingLoadPause.INACTIVE;
			}
			final MatchingLoadPause pause = new MatchingLoadPause(pSession, pFactory, pProjectCode, pProject.getOccurrencePrimaryKey().format(), masterDataspaceKey,
				masterDatasetName, pFactory.getHome().getRepository(), pOwner);
			final List<String> candidates = pause.candidateEntities();
			if (candidates.isEmpty())
			{
				return MatchingLoadPause.INACTIVE;
			}
			pause.pauseAll(candidates);
			return pause;
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not prepare the matching pause of the master load of project " + pProjectCode + ": " + ex, ex);
			return MatchingLoadPause.INACTIVE;
		}
	}

	/** The raw value of {@code Project.matchingTrigger}, or null when the model has no such field (lot MT not deployed) or it cannot be read. */
	static Object readTrigger(final Adaptation pProject)
	{
		try
		{
			return pProject.getSchemaNode().getNode(MatchingLoadPause.TRIGGER) == null ? null : pProject.get(MatchingLoadPause.TRIGGER);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	boolean active()
	{
		return this.active;
	}

	/** Puts the pause (when there is one) in the {@code MASTER_LOAD} step's detail; the node is shared, so what {@link #finish} adds later is part of it. */
	void describeInto(final ObjectNode pStepDetail)
	{
		if (this.active)
		{
			pStepDetail.set("matchingPause", this.detail);
		}
	}

	/** The load began its step: {@link #finish} amends that step once the matching is resumed and caught up. */
	void stepBegun(final String pStepId)
	{
		this.stepId = pStepId;
	}

	// ======================================================================================================== pause

	/** The built entities of the project whose matching plan is applied, in the matching order (parents first). */
	private List<String> candidateEntities()
	{
		final Map<String, String> entityIdByName = new LinkedHashMap<String, String>();
		final List<StagingModel.EntityInfo> entities = MasterLoader.readEntities(this.factory, this.projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(entities);
		final List<String> out = new ArrayList<String>();
		for (final String entityName : masterModel.builtEntities)
		{
			if (MatchingLoadPause.ACTIVE_PLAN.equals(MasterLoader.readMatchPlanStatus(this.factory, entityIdByName.get(entityName))))
			{
				out.add(entityName);
			}
		}
		return out;
	}

	private void pauseAll(final List<String> pEntities)
	{
		// BEFORE the first call (even before the client): a record written once the first table is paused is in the window
		this.pauseStartedAt = new Date();
		this.detail.put("trigger", MatchingLoadPauseRule.REALTIME);
		this.detail.put("pauseStartedAt", MatchingLoadPause.iso(this.pauseStartedAt));
		try
		{
			this.client = McpServers.client(this.session, this.factory, McpServers.MATCH_MERGE);
		}
		catch (final OperationException | RuntimeException ex)
		{
			this.detail.put("skipped", "no match-merge client: " + ex.getMessage());
			return;
		}
		this.publicationName = MasterNames.modelName(this.projectCode);
		for (final String entityName : pEntities)
		{
			this.pauseOne(entityName, MasterModel.tablePath(entityName));
		}
	}

	private void pauseOne(final String pEntityName, final String pTablePath)
	{
		final ObjectNode node = this.tablesNode.addObject();
		node.put("entity", pEntityName);
		node.put("table", pTablePath);
		node.put("pausedByLoad", false);
		boolean callMade = false;
		try
		{
			final McpClient.ToolResult status = this.status(pTablePath);
			if (status.error || !MameAnswer.success(status.json, false))
			{
				node.put("action", "STATUS_UNREADABLE");
				node.put("note", MatchingLoadPause.reason(status));
				return;
			}
			final String state = MameAnswer.field(status.json, "state").asText(null);
			final JsonNode pausedField = MameAnswer.field(status.json, "paused");
			node.put("state", state);
			final MatchingLoadPauseRule.Action action = MatchingLoadPauseRule.decide(state, pausedField.isMissingNode() ? null : Boolean.valueOf(pausedField.asBoolean(false)));
			if (action == MatchingLoadPauseRule.Action.ALREADY_PAUSED)
			{
				node.put("action", "ALREADY_PAUSED");
				node.put("note", "paused before this load: left alone, not resumed by it");
				return;
			}
			if (action == MatchingLoadPauseRule.Action.NOT_ENABLED)
			{
				node.put("action", "NOT_ENABLED");
				return;
			}
			// recorded BEFORE the pause is applied: a stop between the two leaves a row for a table that is not paused, never a pause nobody can recover
			if (this.owner != null)
			{
				try
				{
					LoadPauseLedger.claim(this.session, this.factory, this.owner, FactoryPaths.LoadPause.KIND_MATCHING_TABLE, pTablePath);
				}
				catch (final OperationException ex)
				{
					node.put("action", "PAUSE_FAILED");
					node.put("note", "not paused, the pause could not be recorded so it could not be lifted after a stop: " + ex.getMessage());
					return;
				}
			}
			callMade = true;
			final String failure = this.setPause(pTablePath, true);
			if (failure != null)
			{
				node.put("action", "PAUSE_FAILED");
				node.put("note", failure);
				// the call may have gone through before it failed: resume at the end, it is idempotent on a running table
				this.toResume.add(new Paused(pEntityName, pTablePath, node));
				return;
			}
			node.put("action", "PAUSED");
			node.put("pausedByLoad", true);
			this.toResume.add(new Paused(pEntityName, pTablePath, node));
			final McpClient.ToolResult after = this.status(pTablePath);
			final JsonNode pausedAfter = MameAnswer.field(after.json, "paused");
			final boolean confirmed = pausedAfter.asBoolean(false) || "PAUSED".equalsIgnoreCase(MameAnswer.field(after.json, "state").asText(""));
			node.put("pauseConfirmed", confirmed);
			if (!confirmed)
			{
				node.put("note", "the pause was accepted but the status read right after does not say PAUSED: " + MatchingLoadPause.reason(after));
			}
		}
		catch (final McpException | OperationException | RuntimeException ex)
		{
			node.put("action", callMade ? "PAUSE_FAILED" : "STATUS_UNREADABLE");
			node.put("note", ErrorText.of(ex));
			if (callMade)
			{
				this.toResume.add(new Paused(pEntityName, pTablePath, node));
			}
		}
	}

	private McpClient.ToolResult status(final String pTablePath) throws McpException, OperationException
	{
		final ObjectNode args = MatchingLoadPause.MAPPER.createObjectNode();
		args.put("table", pTablePath);
		return MameCalls.call(this.client, "get_matching_activation_status", args, this.publicationName);
	}

	/** {@code set_table_settings {"pauseMatching": pPause}} (the call {@code MatchingConfigurator} makes to pause a table); null when it succeeded, else why not. */
	private String setPause(final String pTablePath, final boolean pPause) throws McpException, OperationException
	{
		final ObjectNode args = MatchingLoadPause.MAPPER.createObjectNode();
		args.put("table", pTablePath);
		args.putObject("settings").put("pauseMatching", pPause);
		final McpClient.ToolResult result = MameCalls.call(this.client, "set_table_settings", args, this.publicationName);
		if (result.error || !MameAnswer.success(result.json, true))
		{
			return "set_table_settings (pauseMatching=" + pPause + ") failed: " + MatchingLoadPause.reason(result);
		}
		return null;
	}

	// ====================================================================================================== finish

	/**
	 * After the load, in a {@code finally}: resumes the tables this load paused, catches up on the records written meanwhile (only after a load that ended DONE),
	 * puts the outcome in the result and amends the {@code MASTER_LOAD} step. Never throws.
	 *
	 * @param pResult the load's result, or null when it threw
	 */
	void finish(final FactoryJobs.Handle pHandle, final ObjectNode pResult)
	{
		if (!this.active)
		{
			return;
		}
		try
		{
			this.finishInternal(pHandle, pResult);
		}
		catch (final RuntimeException | LinkageError ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] the end of the matching pause of project " + this.projectCode + " failed: " + ex, ex);
			this.detail.put("finishError", String.valueOf(ex));
		}
		if (pResult != null)
		{
			pResult.set("matchingPause", this.detail);
		}
	}

	private void finishInternal(final FactoryJobs.Handle pHandle, final ObjectNode pResult)
	{
		// 1. resume everything first, so a catch-up that fails or takes long never leaves a table paused
		boolean anyFailure = false;
		for (final Paused paused : this.toResume)
		{
			final String error = this.resumeOne(paused);
			paused.node.put("resumed", error == null);
			if (error == null && this.owner != null)
			{
				LoadPauseLedger.release(this.session, this.factory, this.owner, FactoryPaths.LoadPause.KIND_MATCHING_TABLE, paused.tablePath);
			}
			if (error != null)
			{
				paused.node.put("resumeError", error);
				anyFailure = true;
			}
		}
		this.detail.put("resume", this.toResume.isEmpty() ? "NONE" : anyFailure ? "FAILED" : "OK");

		// 2. catch up on what was written while the tables were paused - only after a load that ended DONE
		final String status = pResult == null ? null : pResult.path("status").asText("");
		final String skipReason = pResult == null ? "the load failed" : !"DONE".equals(status) ? "the load ended " + status
			: pHandle != null && pHandle.cancelled() ? "the load was stopped" : null;
		for (final Paused paused : this.toResume)
		{
			if (!paused.node.path("pausedByLoad").asBoolean(false) || !paused.node.path("resumed").asBoolean(false))
			{
				continue;
			}
			if (skipReason != null)
			{
				final ObjectNode skipped = paused.node.putObject("catchUp");
				skipped.put("status", "SKIPPED");
				skipped.put("reason", skipReason + "; the records written during the pause are not matched - run the matching on the whole table");
				continue;
			}
			paused.node.set("catchUp", MatchingCatchUp.run(this.session, this.factory, this.projectId, paused.entityName, this.repository, this.masterDataspaceKey,
				this.masterDatasetName, this.pauseStartedAt, pHandle));
		}

		// 3. say it on the step
		final String warning = MatchingPauseSummary.warning(this.detail);
		final String sentence = MatchingPauseSummary.sentence(this.detail);
		if (!warning.isEmpty())
		{
			LoggingCategory.getKernel().error("[mdm-factory] master load of project " + this.projectCode + ": " + warning);
		}
		if (this.stepId != null && (!warning.isEmpty() || !sentence.isEmpty()))
		{
			PipelineSteps.amendEnded(this.session, this.factory, this.stepId, warning, sentence, "matchingPause", this.detail);
		}
	}

	/** Resumes one table (one retry); null on success, else the reason, which stays in the detail and the step's message. */
	private String resumeOne(final Paused pPaused)
	{
		String last = null;
		for (int attempt = 1; attempt <= 2; attempt++)
		{
			try
			{
				last = this.setPause(pPaused.tablePath, false);
				if (last == null)
				{
					return null;
				}
			}
			catch (final McpException | OperationException | RuntimeException ex)
			{
				last = ErrorText.of(ex);
			}
			if (attempt == 1)
			{
				try
				{
					Thread.sleep(2000L);
				}
				catch (final InterruptedException interrupted)
				{
					Thread.currentThread().interrupt();
					return last;
				}
			}
		}
		return last;
	}

	// ================================================================================================ recovery

	/** What {@link #recoverTable} did to one table whose pause outlived its load. */
	enum Recovery
	{
		/** It was paused and is resumed now. */
		RESUMED,
		/** It is not paused (a person, or a later run, already resumed it): nothing to do. */
		NOT_PAUSED,
		/** Could not be read, or the client could not be built (the add-on is not up yet): try again later. */
		UNREADABLE,
		/** It is paused and the resume call failed (see {@code message}). */
		RESUME_FAILED
	}

	/**
	 * Start-of-module recovery of one table the factory paused for a load that never came back to resume it ({@link LoadPauseRecovery}). Reads the table's state
	 * first and resumes it only when it still is paused: a table somebody resumed meanwhile, or paused again by a person after that, is judged by what it looks like now
	 * ({@link LoadPauseRule}) and a pause nobody recorded is never reached from here. Never throws.
	 */
	static Recovery recoverTable(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pProjectId, final String pTablePath,
		final String[] pMessage)
	{
		try
		{
			final MatchingLoadPause pause = new MatchingLoadPause(pSession, pFactory, pProjectCode, pProjectId, null, null, pFactory.getHome().getRepository(), null);
			pause.client = McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE);
			pause.publicationName = MasterNames.modelName(pProjectCode);
			final McpClient.ToolResult status = pause.status(pTablePath);
			final boolean read = !status.error && MameAnswer.success(status.json, false);
			final JsonNode pausedField = read ? MameAnswer.field(status.json, "paused") : null;
			final LoadPauseRule.Observation observation = LoadPauseRule.observeMatching(read, read ? MameAnswer.field(status.json, "state").asText(null) : null,
				pausedField == null || pausedField.isMissingNode() ? null : Boolean.valueOf(pausedField.asBoolean(false)));
			final LoadPauseRule.Action action = LoadPauseRule.decide(false, observation);
			if (action == LoadPauseRule.Action.DROP)
			{
				return Recovery.NOT_PAUSED;
			}
			if (action == LoadPauseRule.Action.KEEP)
			{
				pMessage[0] = MatchingLoadPause.reason(status);
				return Recovery.UNREADABLE;
			}
			final String error = pause.resumeOne(new Paused(null, pTablePath, null));
			if (error != null)
			{
				pMessage[0] = error;
				return Recovery.RESUME_FAILED;
			}
			return Recovery.RESUMED;
		}
		catch (final McpException | OperationException | RuntimeException ex)
		{
			pMessage[0] = ErrorText.of(ex);
			return Recovery.UNREADABLE;
		}
	}

	// ======================================================================================================= misc

	private static String reason(final McpClient.ToolResult pResult)
	{
		final String text = pResult == null || pResult.text == null ? "" : pResult.text.trim();
		final String code = pResult == null ? null : MameAnswer.code(pResult.json);
		final String shortText = text.length() > 300 ? text.substring(0, 300) + "..." : text;
		return (code == null ? "" : code + ": ") + shortText;
	}

	/** {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} in UTC - an explicit pattern: {@code LocalDateTime.toString()} drops zero seconds (CLAUDE.md section 14). */
	static String iso(final Date pDate)
	{
		final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		format.setTimeZone(TimeZone.getTimeZone("UTC"));
		return format.format(pDate);
	}
}
