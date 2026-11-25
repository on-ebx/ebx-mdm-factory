package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import com.on.ebx.factory.delivery.OriginRule;
import com.on.ebx.factory.review.ReviewEntityFormat;

/**
 * Pure computation behind the "Cockpit du projet" screen (mockup screen 3): plain value objects in
 * ({@link Inputs}), one {@link Snapshot} out, no EBX type anywhere — so {@code CockpitDataTest} exercises
 * it directly. Honest by construction rather than by convention: the staging/master zones report
 * "not generated yet" from the absence of input (no staging dataset recorded on the project, no entity ever
 * loaded) rather than from a flag the caller has to remember to set, and {@link Snapshot#rejects} is the
 * real count of unresolved {@code TransferReject} rows — not a hardcoded zero, now that the transfer
 * engine (a later lot than when that hardcoding was written) can actually reject a row.
 * <p>
 * A step is normally re-run (flows regenerated with {@code ?force=true}, a load repeated daily): the
 * pipeline would otherwise grow one line per run, without bound. {@link #compute} instead keeps, per (scope,
 * step) pair, only the LATEST run as the line ({@link #latest}: by {@code startedAt}, {@code id} as the
 * tie-break for two runs recorded in the same instant), with how many times it ran ({@link StepRow#runs})
 * and that latest run's own end time — so a step that failed on its most recent run is reported as failed
 * even when an earlier run of the same step succeeded. The "now" a still-{@code RUNNING} step is compared
 * against to flag it as possibly stuck is passed in by the caller ({@link Inputs#now}), never read from the
 * system clock here, so {@code CockpitDataTest} can fix it.
 */
final class CockpitData
{
	private CockpitData()
	{
	}

	/** A step running longer than this without ending is flagged as possibly stuck. */
	static final long STUCK_AFTER_MS = 30L * 60L * 1000L;

	/** The order a step normally runs in, for grouping {@link StepInfo} under its source; unknown steps sort last. */
	private static final String[] STEP_ORDER = { "LANDING_MODEL", "LANDING_DATASET", "LANDING_FLOWS", "LANDING_LOAD", "STAGING_SUGGEST", "STAGING_MODEL",
		"STAGING_LOAD", "MASTER_LOAD", "MATCHING_CONFIG", "MATCHING_RUN" };

	// ------------------------------------------------------------------ input (read off the factory dataset)

	static final class ProjectInfo
	{
		String code = "";
		String label = "";
		String mode = "";
		String status = "";
		/** Delivery lot 1: {@code FactorySettings.instanceId} of the instance this project was created on; empty for a row written before that field existed. */
		String originRepository = "";
	}

	static final class SourceInfo
	{
		String id = "";
		String sourceSystem = "";
		String status = "";
	}

	/** One {@code PipelineStep} row. */
	static final class StepInfo
	{
		/** Tie-break when two runs of the same step share a {@code startedAt} (same second, e.g. a fast retry). */
		String id = "";
		String step = "";
		/** The source system this step ran for; empty or {@code LLM} for a whole-project step (see {@link #isProjectScope}). */
		String scope = "";
		String status = "";
		String message = "";
		Date startedAt;
		Date endedAt;
		/** How many {@code PipelineStep} rows this entry stands for: 1 for a row as read, more for the latest run a {@link StepDigest} kept in place of the older runs of its (scope, step) (audit F-36). */
		int runs = 1;
	}

	/** {@code STAGING_SUGGEST} runs with an empty scope (the deterministic rule pass) or {@code LLM} (the language
	 * model alignment pass, {@code StagingLlmSuggester.STEP_SCOPE}): neither names an actual project source, so
	 * both group under "Project", never under a source block. */
	private static boolean isProjectScope(final String pScope)
	{
		return pScope == null || pScope.isEmpty() || "LLM".equals(pScope);
	}

	/** One {@code LandingTable} row. */
	static final class TableInfo
	{
		String id = "";
		String sourceSystem = "";
		String sourceObject = "";
		String tableName = "";
		String status = "";
		Long sourceRowCount;
		String lastLoadId = "";
		Date lastLoadAt;
		Long lastLoadRows;
		String message = "";
	}

	/** One {@code LandingRelation} row, including the value-check evidence a later pass writes onto it. */
	static final class RelationInfo
	{
		String fromTable = "";
		String toTable = "";
		String status = "";
		/** Null: never checked. Set together by the same pass, but read independently — no assumption either is null iff the other is. */
		Date checkedAt;
		Long childValues;
		Long orphanValues;
	}

	/** One {@code StagingEntity} row, including the counters {@code StagingLoader} writes after each transfer. */
	static final class EntityInfo
	{
		String id = "";
		String name = "";
		String status = "";
		Date lastLoadAt;
		Long lastLoadRead;
		Long lastLoadWritten;
		Long lastLoadSkipped;
		Long lastLoadRejected;
		String lastLoadMessage = "";
		/** Resolved by the caller from {@code StagingEntity.memberEntities}; empty for an entity fed by one landing table (not canonical). */
		final List<ReviewEntityFormat.Member> members = new ArrayList<ReviewEntityFormat.Member>();
	}

	/** One {@code TransferReject} row. */
	static final class RejectInfo
	{
		String entityName = "";
		/** {@code STAGING} or {@code MASTER} — which transfer rejected the row. */
		String stage = "";
		String sourceSystem = "";
		String sourceId = "";
		/** Text written by the engine, never the row's own field values (only its identifiers are ever shown here). */
		String reasons = "";
		Date rejectedAt;
		boolean resolved;
		/** Wave 10 (audit F-38): a steward set it aside; counts as no longer open. */
		boolean acknowledged;
	}

	/**
	 * One {@code QualityRule} row, down to what the Cockpit's quality block needs (task C) - {@code
	 * lastLoadFailing} is {@code null} when this rule has never actually run at transfer (a {@code
	 * MODEL_CONSTRAINT} rule, or one never yet reached by a load), never a hidden zero.
	 */
	static final class QualityRuleInfo
	{
		String entityName = "";
		String status = "";
		Long lastLoadFailing;
	}

	/** Reject/repropose lot (2026-09-22): one {@code MatchPlan} row, down to what the Cockpit's matching block needs. */
	static final class MatchPlanInfo
	{
		String entityName = "";
		/** {@code PROPOSED}/{@code SUPERSEDED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}. */
		String status = "";
		/** Only meaningful when {@link #status} is {@code REJECTED} (or, briefly, {@code SUPERSEDED}) - why, in the reviewer's own words. */
		String reviewComment = "";
	}

	/** The project's most recent {@code AgentRun}, whichever phase. */
	static final class AgentRunInfo
	{
		String phase = "";
		String status = "";
		Integer steps;
		Long inputTokens;
		Long outputTokens;
		Date startedAt;
		Date endedAt;
	}

	/** Everything {@link #compute} needs, gathered by the caller in one place instead of a growing positional
	 * parameter list. Every list defaults to empty and {@link #now} to "this instant" so a test only sets
	 * what the scenario actually needs. */
	static final class Inputs
	{
		ProjectInfo project = new ProjectInfo();
		List<SourceInfo> sources = new ArrayList<SourceInfo>();
		List<StepInfo> steps = new ArrayList<StepInfo>();
		List<TableInfo> tables = new ArrayList<TableInfo>();
		List<RelationInfo> relations = new ArrayList<RelationInfo>();
		List<EntityInfo> entities = new ArrayList<EntityInfo>();
		List<RejectInfo> rejects = new ArrayList<RejectInfo>();
		/**
		 * Lot volume-4 (2026-09-28, task brief item 8): the REAL count of unresolved {@code TransferReject} rows
		 * of the project, measured by the caller some cheaper way than materializing every one (a bounded SQL/
		 * XPath count - {@code ebx.ui.WizardSummaryReader#unresolvedRejectCount}) - null means "not measured
		 * separately", in which case {@link #compute} falls back to counting {@link #rejects} itself (every
		 * {@code CockpitDataTest} scenario written before this lot, which always lists the project's WHOLE reject
		 * set in {@link #rejects}, keeps working unchanged). Set this whenever {@link #rejects} is only a
		 * bounded SAMPLE (e.g. {@code ebx.ui.FactoryCockpitUserService#readRejects}'s own capped, most-recent-
		 * first read) - otherwise {@link Snapshot#rejects} would under-report a project with more rejects than
		 * the sample holds.
		 */
		Integer unresolvedRejectsTotal;
		/**
		 * Wave 10 (audit F-38): the per-(entity, stage) count of OPEN rejects (not resolved, not acknowledged), computed by the caller with ONE aggregate query
		 * ({@code ebx.RejectReview#summary}) - null means "derive it from {@link #rejects}" (the fixtures of the older tests; a bounded sample is never right for it).
		 */
		List<RejectSummary> rejectSummaries;
		/** Robustness audit F2-105: true when the aggregate query failed and {@link #rejectSummaries} is null for that reason (the breakdown then comes from a sample). */
		boolean rejectSummaryPartial;
		List<QualityRuleInfo> qualityRules = new ArrayList<QualityRuleInfo>();
		/** Reject/repropose lot (2026-09-22): every {@code MatchPlan} of the project's entities. */
		List<MatchPlanInfo> matchPlans = new ArrayList<MatchPlanInfo>();
		/** Null: no agent run recorded for this project yet. */
		AgentRunInfo lastAgentRun;
		/** {@code Project.stagingDataset} is non-empty: the staging model/dataset exist, even for an entity never loaded into them. */
		boolean stagingDatasetExists;
		/** Delivery lot 2: this instance's own {@code FactorySettings.instanceId} - compared against {@link ProjectInfo#originRepository} to tell a delivered project apart from a local one ({@code delivery.OriginRule}). */
		String instanceId = "";
		/** Delivery lot 2: this instance's {@code FactorySettings.role} ({@code DEVELOPMENT}/{@code TARGET}). */
		String instanceRole = "";
		Date now = new Date();
	}

	// ------------------------------------------------------------------ output (ready to render, no EBX type)

	static final class StepRow
	{
		final String step;
		/** Status of the LATEST run only: a run that failed after an earlier success still reports failed here. */
		final String status;
		final String message;
		/** How many {@code PipelineStep} rows share this (scope, step) pair; 1 when the step never re-ran. */
		final int runs;
		/** The latest run's own start time (shown when it has no end time yet: still running). */
		final Date startedAt;
		/** The latest run's own end time; null while that run is still {@code RUNNING}. */
		final Date endedAt;
		/** True when the latest run is still {@code RUNNING} and has been for more than {@link #STUCK_AFTER_MS}. */
		final boolean stuck;

		StepRow(final String pStep, final String pStatus, final String pMessage, final int pRuns, final Date pStartedAt, final Date pEndedAt, final boolean pStuck)
		{
			this.step = pStep;
			this.status = pStatus;
			this.message = pMessage;
			this.runs = pRuns;
			this.startedAt = pStartedAt;
			this.endedAt = pEndedAt;
			this.stuck = pStuck;
		}
	}

	static final class SourceBlock
	{
		/** Null for the "Project" pseudo-block ({@link #project}); the real source system name otherwise. */
		final String sourceSystem;
		/** True for the whole-project group (steps with an empty or {@code LLM} scope), shown above the sources. */
		final boolean project;
		final List<StepRow> steps = new ArrayList<StepRow>();

		SourceBlock(final String pSourceSystem)
		{
			this.sourceSystem = pSourceSystem;
			this.project = false;
		}

		private SourceBlock(final boolean pProject)
		{
			this.sourceSystem = null;
			this.project = pProject;
		}

		static SourceBlock project()
		{
			return new SourceBlock(true);
		}
	}

	static final class VolumeRow
	{
		final String zone;
		final String table;
		/** Null when never loaded/generated: rendered as an em dash, never as 0. */
		final Long rows;
		final String status;
		/** Staging only, and only once loaded at least once: rows rejected at that same load. Null otherwise. */
		final Long rejected;
		/** The entity's own last transfer time, or the landing table's own last load time; null when neither ever ran. */
		final Date lastAt;
		/** e.g. "Supplier (ERP), Account (CRM)": set only for a canonical entity fed by more than one system. */
		final String memberSummary;

		VolumeRow(final String pZone, final String pTable, final Long pRows, final String pStatus, final Long pRejected, final Date pLastAt, final String pMemberSummary)
		{
			this.zone = pZone;
			this.table = pTable;
			this.rows = pRows;
			this.status = pStatus;
			this.rejected = pRejected;
			this.lastAt = pLastAt;
			this.memberSummary = pMemberSummary;
		}

		VolumeRow(final String pZone, final String pTable, final Long pRows, final String pStatus)
		{
			this(pZone, pTable, pRows, pStatus, null, null, null);
		}
	}

	static final class LoadRow
	{
		final String table;
		final String loadId;
		final long loadedRows;
		final Long sourceRows;
		final Date loadedAt;
		final String status;

		LoadRow(final String pTable, final String pLoadId, final long pLoadedRows, final Long pSourceRows, final Date pLoadedAt, final String pStatus)
		{
			this.table = pTable;
			this.loadId = pLoadId;
			this.loadedRows = pLoadedRows;
			this.sourceRows = pSourceRows;
			this.loadedAt = pLoadedAt;
			this.status = pStatus;
		}
	}

	static final class Alert
	{
		final String table;
		final String status;
		final String message;

		Alert(final String pTable, final String pStatus, final String pMessage)
		{
			this.table = pTable;
			this.status = pStatus;
			this.message = pMessage;
		}
	}

	/** One relation's value-check evidence, whatever its status (proposed, accepted or rejected): the viewer sees the whole picture. */
	static final class RelationEvidence
	{
		final String fromTable;
		final String toTable;
		final String status;
		final Date checkedAt;
		final Long childValues;
		final Long orphanValues;

		RelationEvidence(final String pFromTable, final String pToTable, final String pStatus, final Date pCheckedAt, final Long pChildValues, final Long pOrphanValues)
		{
			this.fromTable = pFromTable;
			this.toTable = pToTable;
			this.status = pStatus;
			this.checkedAt = pCheckedAt;
			this.childValues = pChildValues;
			this.orphanValues = pOrphanValues;
		}
	}

	/** One entity's quality-rule tally (task C): how many rules are accepted/pending review, and how many rows failed one of the accepted rules at the last load - the compact block the Cockpit shows, one line per entity, linking to the review filtered on {@code nature=QUALITY}. */
	static final class QualityEntitySummary
	{
		final String entityName;
		final int accepted;
		final int pending;
		/** Sum of every accepted-like rule's own {@code lastLoadFailing} for this entity - 0 when every accepted rule either never failed or never ran yet, never null (there is always a number to show once a rule is accepted). */
		final long lastLoadFailing;

		QualityEntitySummary(final String pEntityName, final int pAccepted, final int pPending, final long pLastLoadFailing)
		{
			this.entityName = pEntityName;
			this.accepted = pAccepted;
			this.pending = pPending;
			this.lastLoadFailing = pLastLoadFailing;
		}
	}

	/**
	 * Reject/repropose lot (2026-09-22): one entity's matching status for the Cockpit's compact "Matching"
	 * block - {@link #comment} is only ever non-empty when {@link #status} is {@code REJECTED} (the "Matching:
	 * none, by decision" case - {@code ebx.MatchingConfigurator.configure}'s own {@code excludedByDecision}
	 * reports the same thing on the API side).
	 */
	static final class MatchEntitySummary
	{
		final String entityName;
		final String status;
		final String comment;

		MatchEntitySummary(final String pEntityName, final String pStatus, final String pComment)
		{
			this.entityName = pEntityName;
			this.status = pStatus;
			this.comment = pComment;
		}
	}

	/** How many unresolved rejects one entity has at one transfer stage. */
	static final class RejectSummary
	{
		final String entityName;
		final String stage;
		final int unresolvedCount;

		RejectSummary(final String pEntityName, final String pStage, final int pUnresolvedCount)
		{
			this.entityName = pEntityName;
			this.stage = pStage;
			this.unresolvedCount = pUnresolvedCount;
		}
	}

	static final class Snapshot
	{
		ProjectInfo project = new ProjectInfo();
		int sourceCount;
		int landingTableCount;
		long rowsLoaded;
		/** Sum of every entity's {@code lastLoadWritten}: the new "Staging rows" KPI tile, next to "Rows loaded". */
		long stagingRowsWritten;
		int relationsTotal;
		int relationsAccepted;
		/** The real count of unresolved {@code TransferReject} rows for this project — what the KPI tile shows. */
		int rejects;
		int stagingEntityCount;
		final List<String> stagingEntityNames = new ArrayList<String>();
		/** True once a master dataspace/dataset is recorded on the project; always false until a later lot. */
		boolean masterZoneExists;
		/** Delivery lot 2: true when this project's {@code originRepository} differs from this instance's own ({@code delivery.OriginRule#isDelivered}). */
		boolean delivered;
		/** Set only when {@link #delivered}: the instance this project was designed on. */
		String originRepository = "";
		/** Delivery lot 2: this instance's own {@code FactorySettings.role} ({@code DEVELOPMENT}/{@code TARGET}), shown regardless of {@link #delivered}. */
		String instanceRole = "";
		final List<SourceBlock> pipeline = new ArrayList<SourceBlock>();
		final List<VolumeRow> volumes = new ArrayList<VolumeRow>();
		final List<LoadRow> loads = new ArrayList<LoadRow>();
		final List<Alert> alerts = new ArrayList<Alert>();
		final List<RelationEvidence> relations = new ArrayList<RelationEvidence>();
		final List<RejectSummary> rejectSummaries = new ArrayList<RejectSummary>();
		/** Robustness audit F2-105: the per-entity breakdown was derived from the sample of latest rejects because the aggregate query failed - the screen says so. */
		boolean rejectSummaryPartial;
		/** Up to 10, most recent first, resolved or not — an all-resolved list is still shown, so "fixed" history is not indistinguishable from "never happened". */
		final List<RejectInfo> latestRejects = new ArrayList<RejectInfo>();
		/** Null: no agent run recorded for this project yet. */
		AgentRunInfo lastAgentRun;
		/** Task C: total accepted-or-applied / still-proposed QualityRule rows of the project, across every entity. */
		int qualityRulesAccepted;
		int qualityRulesPending;
		/** One line per entity that has at least one QualityRule row, whatever its status. */
		final List<QualityEntitySummary> quality = new ArrayList<QualityEntitySummary>();
		/** Reject/repropose lot (2026-09-22): one line per entity that has a {@code MatchPlan} row at all - an entity with none is not listed (matching was never even proposed for it yet). */
		final List<MatchEntitySummary> matching = new ArrayList<MatchEntitySummary>();
	}

	static Snapshot compute(final Inputs pIn)
	{
		final Snapshot out = new Snapshot();
		out.project = pIn.project;
		out.sourceCount = pIn.sources.size();
		out.landingTableCount = pIn.tables.size();
		out.lastAgentRun = pIn.lastAgentRun;
		out.masterZoneExists = false;
		out.delivered = OriginRule.isDelivered(pIn.project.originRepository, pIn.instanceId);
		out.originRepository = out.delivered ? pIn.project.originRepository : "";
		out.instanceRole = pIn.instanceRole == null ? "" : pIn.instanceRole;

		long rowsLoaded = 0;
		for (final TableInfo table : pIn.tables)
		{
			if (table.lastLoadRows != null)
			{
				rowsLoaded += table.lastLoadRows.longValue();
			}
		}
		out.rowsLoaded = rowsLoaded;

		out.relationsTotal = pIn.relations.size();
		int accepted = 0;
		for (final RelationInfo relation : pIn.relations)
		{
			if ("ACCEPTED".equals(relation.status))
			{
				accepted++;
			}
			out.relations.add(new RelationEvidence(relation.fromTable, relation.toTable, relation.status, relation.checkedAt, relation.childValues, relation.orphanValues));
		}
		out.relationsAccepted = accepted;

		// project-level steps (STAGING_SUGGEST from the rule pass, scope "", or from the language model pass,
		// scope "LLM") group above the sources, never appear twice just because two passes both ran it
		final List<StepInfo> projectSteps = new ArrayList<StepInfo>();
		for (final StepInfo step : pIn.steps)
		{
			if (CockpitData.isProjectScope(step.scope))
			{
				projectSteps.add(step);
			}
		}
		if (!projectSteps.isEmpty())
		{
			final SourceBlock projectBlock = SourceBlock.project();
			projectBlock.steps.addAll(CockpitData.latestPerStep(projectSteps, pIn.now));
			out.pipeline.add(projectBlock);
		}

		for (final SourceInfo source : pIn.sources)
		{
			final SourceBlock block = new SourceBlock(source.sourceSystem);
			final List<StepInfo> ofThisSource = new ArrayList<StepInfo>();
			for (final StepInfo step : pIn.steps)
			{
				if (source.sourceSystem.equals(step.scope))
				{
					ofThisSource.add(step);
				}
			}
			block.steps.addAll(CockpitData.latestPerStep(ofThisSource, pIn.now));
			out.pipeline.add(block);
		}

		for (final TableInfo table : pIn.tables)
		{
			out.volumes.add(new VolumeRow("landing", table.tableName, table.lastLoadRows != null ? table.lastLoadRows : table.sourceRowCount, table.status));
			if (table.lastLoadId != null && !table.lastLoadId.isEmpty())
			{
				out.loads.add(new LoadRow(table.tableName, table.lastLoadId, table.lastLoadRows == null ? 0L : table.lastLoadRows.longValue(), table.sourceRowCount,
					table.lastLoadAt, table.status));
			}
			if ("DRIFTED".equals(table.status) || "FAILED".equals(table.status))
			{
				out.alerts.add(new Alert(table.tableName, table.status, table.message));
			}
		}

		CockpitData.computeStagingVolumes(pIn, out);
		out.volumes.add(new VolumeRow("master", null, null, "NONE"));

		CockpitData.computeRejects(pIn, out);
		CockpitData.computeQuality(pIn, out);
		CockpitData.computeMatching(pIn, out);
		return out;
	}

	/** Reject/repropose lot (2026-09-22): one line per entity that has a MatchPlan row - status plus, for a REJECTED one, the reviewer's own comment ("Matching: none, by decision"). */
	private static void computeMatching(final Inputs pIn, final Snapshot pOut)
	{
		for (final MatchPlanInfo plan : pIn.matchPlans)
		{
			pOut.matching.add(new MatchEntitySummary(plan.entityName, plan.status, "REJECTED".equals(plan.status) ? plan.reviewComment : ""));
		}
	}

	/** Task C: one line per entity with at least one QualityRule row - accepted/pending counts, and how many rows failed an accepted-like rule at the last transfer (summed across every rule of that entity). */
	private static void computeQuality(final Inputs pIn, final Snapshot pOut)
	{
		final java.util.LinkedHashMap<String, int[]> countsByEntity = new java.util.LinkedHashMap<String, int[]>(); // [accepted, pending]
		final java.util.LinkedHashMap<String, Long> failingByEntity = new java.util.LinkedHashMap<String, Long>();
		for (final QualityRuleInfo rule : pIn.qualityRules)
		{
			int[] counts = countsByEntity.get(rule.entityName);
			if (counts == null)
			{
				counts = new int[2];
				countsByEntity.put(rule.entityName, counts);
				failingByEntity.put(rule.entityName, Long.valueOf(0));
			}
			final boolean acceptedLike = "ACCEPTED".equals(rule.status) || "APPLIED".equals(rule.status);
			if (acceptedLike)
			{
				counts[0]++;
				pOut.qualityRulesAccepted++;
				if (rule.lastLoadFailing != null)
				{
					failingByEntity.put(rule.entityName, Long.valueOf(failingByEntity.get(rule.entityName).longValue() + rule.lastLoadFailing.longValue()));
				}
			}
			else if ("PROPOSED".equals(rule.status))
			{
				counts[1]++;
				pOut.qualityRulesPending++;
			}
		}
		for (final java.util.Map.Entry<String, int[]> entry : countsByEntity.entrySet())
		{
			pOut.quality.add(new QualityEntitySummary(entry.getKey(), entry.getValue()[0], entry.getValue()[1], failingByEntity.get(entry.getKey()).longValue()));
		}
	}

	/** Nothing is generated from a line that is not accepted (or applied): only those entities are ever shown as staging volumes. */
	private static boolean inScope(final EntityInfo pEntity)
	{
		return "ACCEPTED".equals(pEntity.status) || "APPLIED".equals(pEntity.status);
	}

	private static void computeStagingVolumes(final Inputs pIn, final Snapshot pOut)
	{
		final List<EntityInfo> inScope = new ArrayList<EntityInfo>();
		for (final EntityInfo entity : pIn.entities)
		{
			if (CockpitData.inScope(entity))
			{
				inScope.add(entity);
			}
		}
		pOut.stagingEntityCount = inScope.size();
		for (final EntityInfo entity : inScope)
		{
			pOut.stagingEntityNames.add(entity.name);
		}

		long written = 0;
		for (final EntityInfo entity : pIn.entities)
		{
			if (entity.lastLoadWritten != null)
			{
				written += entity.lastLoadWritten.longValue();
			}
		}
		pOut.stagingRowsWritten = written;

		if (inScope.isEmpty())
		{
			// honest empty state: no entity has been accepted yet, whether or not a staging dataset happens to exist
			pOut.volumes.add(new VolumeRow("staging", null, null, "NONE"));
			return;
		}
		for (final EntityInfo entity : inScope)
		{
			final String memberSummary = ReviewEntityFormat.unifiesText(entity.members);
			if (entity.lastLoadWritten != null)
			{
				pOut.volumes.add(new VolumeRow("staging", entity.name, entity.lastLoadWritten, "LOADED", entity.lastLoadRejected, entity.lastLoadAt, memberSummary));
			}
			else
			{
				// generated (the model/dataset exist) but this entity itself was never loaded, vs. no dataset at all yet
				final String status = pIn.stagingDatasetExists ? "GENERATED" : "NOT_GENERATED";
				pOut.volumes.add(new VolumeRow("staging", entity.name, null, status, null, null, memberSummary));
			}
		}
	}

	private static void computeRejects(final Inputs pIn, final Snapshot pOut)
	{
		final java.util.LinkedHashMap<String, Integer> unresolvedByEntityAndStage = new java.util.LinkedHashMap<String, Integer>();
		int unresolvedTotal = 0;
		for (final RejectInfo reject : pIn.rejects)
		{
			if (!reject.resolved && !reject.acknowledged)
			{
				unresolvedTotal++;
				final String key = reject.entityName + " " + reject.stage;
				final Integer current = unresolvedByEntityAndStage.get(key);
				unresolvedByEntityAndStage.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
			}
		}
		// lot volume-4 (2026-09-28, task brief item 8): prefer the caller's own accurate, separately-measured
		// total over counting pIn.rejects, which may now be a bounded sample - see Inputs#unresolvedRejectsTotal.
		pOut.rejects = pIn.unresolvedRejectsTotal != null ? pIn.unresolvedRejectsTotal.intValue() : unresolvedTotal;
		for (final RejectInfo reject : pIn.rejects)
		{
			if (pIn.rejectSummaries == null && !reject.resolved && !reject.acknowledged)
			{
				final String key = reject.entityName + " " + reject.stage;
				final Integer count = unresolvedByEntityAndStage.remove(key);
				if (count != null)
				{
					pOut.rejectSummaries.add(new RejectSummary(reject.entityName, reject.stage, count.intValue()));
				}
			}
		}

		pOut.rejectSummaryPartial = pIn.rejectSummaries == null && pIn.rejectSummaryPartial;
		if (pIn.rejectSummaries != null)
		{
			for (final RejectSummary summary : pIn.rejectSummaries)
			{
				if (summary.unresolvedCount > 0)
				{
					pOut.rejectSummaries.add(summary);
				}
			}
		}

		final List<RejectInfo> sorted = new ArrayList<RejectInfo>(pIn.rejects);
		java.util.Collections.sort(sorted, new Comparator<RejectInfo>()
		{
			@Override
			public int compare(final RejectInfo pA, final RejectInfo pB)
			{
				final long a = pA.rejectedAt == null ? Long.MIN_VALUE : pA.rejectedAt.getTime();
				final long b = pB.rejectedAt == null ? Long.MIN_VALUE : pB.rejectedAt.getTime();
				return Long.valueOf(b).compareTo(Long.valueOf(a)); // most recent first
			}
		});
		for (int i = 0; i < sorted.size() && i < 10; i++)
		{
			pOut.latestRejects.add(sorted.get(i));
		}
	}

	/**
	 * Groups the given steps (already scoped to one source, or to the whole project) by (scope, step) —
	 * within the "Project" pseudo-group this still tells the rule pass's {@code STAGING_SUGGEST} (scope
	 * {@code ""}) apart from the language model pass's own {@code STAGING_SUGGEST} (scope {@code LLM}): two
	 * different processes that happen to share a step code must not have one's re-run count absorb the
	 * other's. Keeps only the latest run of each group ({@link #latest}) with its run count, and orders the
	 * resulting rows by the canonical pipeline order ({@link #STEP_ORDER}), not by time — a step re-run
	 * today must not jump to the top of a list that otherwise reads top-to-bottom as "what happens first".
	 */
	private static List<StepRow> latestPerStep(final List<StepInfo> pSteps, final Date pNow)
	{
		final java.util.LinkedHashMap<String, List<StepInfo>> byStep = new java.util.LinkedHashMap<String, List<StepInfo>>();
		for (final StepInfo step : pSteps)
		{
			final String key = step.scope + " " + step.step;
			List<StepInfo> group = byStep.get(key);
			if (group == null)
			{
				group = new ArrayList<StepInfo>();
				byStep.put(key, group);
			}
			group.add(step);
		}
		final List<StepRow> rows = new ArrayList<StepRow>();
		for (final List<StepInfo> group : byStep.values())
		{
			final StepInfo latest = CockpitData.latest(group);
			final boolean stuck = "RUNNING".equals(latest.status) && latest.startedAt != null && pNow != null
				&& pNow.getTime() - latest.startedAt.getTime() > CockpitData.STUCK_AFTER_MS;
			int runs = 0;
			for (final StepInfo member : group)
			{
				runs += member.runs;
			}
			rows.add(new StepRow(latest.step, latest.status, latest.message, runs, latest.startedAt, latest.endedAt, stuck));
		}
		java.util.Collections.sort(rows, new Comparator<StepRow>()
		{
			@Override
			public int compare(final StepRow pA, final StepRow pB)
			{
				return CockpitData.order(pA.step) - CockpitData.order(pB.step);
			}
		});
		return rows;
	}

	/** The run with the latest {@code startedAt}; {@code id} (numeric when possible) breaks a tie. Never called on an empty list. */
	private static StepInfo latest(final List<StepInfo> pRuns)
	{
		StepInfo best = null;
		for (final StepInfo run : pRuns)
		{
			if (best == null || CockpitData.isLater(run, best))
			{
				best = run;
			}
		}
		return best;
	}

	static boolean isLater(final StepInfo pCandidate, final StepInfo pCurrentBest)
	{
		final long candidateAt = pCandidate.startedAt == null ? Long.MIN_VALUE : pCandidate.startedAt.getTime();
		final long bestAt = pCurrentBest.startedAt == null ? Long.MIN_VALUE : pCurrentBest.startedAt.getTime();
		if (candidateAt != bestAt)
		{
			return candidateAt > bestAt;
		}
		return CockpitData.compareIds(pCandidate.id, pCurrentBest.id) > 0;
	}

	/** Numeric comparison when both ids parse as numbers (EBX {@code type="id"} is auto-increment), a plain string compare otherwise. */
	private static int compareIds(final String pA, final String pB)
	{
		try
		{
			return Long.valueOf(Long.parseLong(pA)).compareTo(Long.valueOf(Long.parseLong(pB)));
		}
		catch (final NumberFormatException ex)
		{
			return String.valueOf(pA).compareTo(String.valueOf(pB));
		}
	}

	private static int order(final String pStep)
	{
		for (int i = 0; i < CockpitData.STEP_ORDER.length; i++)
		{
			if (CockpitData.STEP_ORDER[i].equals(pStep))
			{
				return i;
			}
		}
		return CockpitData.STEP_ORDER.length;
	}
}
