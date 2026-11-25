package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
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
import com.orchestranetworks.schema.Path;

/**
 * Lot C4 (2026-09-24): the factory's run history does not grow for ever. Once a day, rows older than {@code
 * Settings.historyRetentionDays} (default 90, 0 = keep everything) are deleted from {@code FactoryJob}, {@code AgentRun}
 * (with their {@code AgentStep} rows) and {@code PipelineStep}. Audit F3-10 (2026-11-12): {@code AgentProposal} follows its run (a run holding a line still waiting for a decision is
 * kept), and {@code SourceDrift} decided longer ago than the same age is purged; a line waiting for a decision is never purged.
 * <p>
 * {@code PipelineStep} rows are also EVIDENCE, and evidence is never purged: {@code StepResetter} and {@code
 * ProjectDeleter} only drop a dataspace or dataset when some DONE row of its step says {@code "created": true} in its
 * detail, and idempotence checks ({@code PipelineSteps.isDone}, {@code StepResetter.stepEverDone}) look for a DONE row
 * of a (project, step, scope). So an old row is deleted only when it is not RUNNING, carries no creation proof, and is
 * not the latest DONE row of its (project, step, scope) - see {@link #pipelineStepsToPurge}.
 * <p>
 * <b>Volume-operations lot 8a (2026-09-28, task 5):</b> two more tables now join the daily purge, both genuinely
 * volume-scale (one row per outbound event / per rejected staged row, unlike the model-scale tables above) -
 * {@code DistributionEvent} (permanent SENT/SKIPPED/FAILED history, {@code ebx.DistributionDispatcher#writeHistory} -
 * every row is already terminal, so age alone decides, on {@code createdAt}) and {@code TransferReject}, RESOLVED
 * rows - and, since wave 10 (audit F-38), rows a steward ACKNOWLEDGED in the review screen ({@code ebx.RejectReview}) - only (an open reject is still an
 * item a data steward has to see - CLAUDE.md's own "never presented as a hard limit" posture on volume does not license deleting work still to do),
 * on {@code rejectedAt}. Neither read
 * needs a table's own {@code detail}-sized field the way {@code PipelineStep}'s decision does (see
 * {@link #creationProof}) - both purges are a plain date/flag predicate, batched the SAME way (transactions of
 * {@link #BATCH}) as every table above. {@code ProjectDeleter} gained the matching {@code DistributionEvent} entry in
 * its own per-project table list (volume lot 2a's {@code VolumeBatchOps} pattern, reused rather than redeveloped).
 */
public final class FactoryRetention
{
	static final int DEFAULT_DAYS = 90;
	/** Audit F-38: the {@code TransferReject} rows the purge may delete once old enough: resolved by a later load, or acknowledged by a steward. An open one (neither) stays. */
	static final String REJECT_PURGE_PREDICATE = "./resolved=true() or ./acknowledged=true()";
	/** Audit F2-104: JVM property, the age in days past which a DONE {@code PipelineStep} row is purged (the latest DONE of each key and creation proofs are kept anyway). */
	static final String DONE_STEP_DAYS_PROPERTY = "mdmf.retention.doneStepDays";
	private static final int BATCH = 500;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static ScheduledExecutorService scheduler;

	private FactoryRetention()
	{
	}

	/** One PipelineStep row as the purge rule sees it. */
	static final class StepRow
	{
		final String id;
		final String key;
		final String status;
		final long at;
		final boolean creationProof;
		/** False when the proof was not read (audit F-36): {@link StepScan#toPurge} asks for it, for a row that would otherwise be purged only. */
		final boolean proofKnown;

		StepRow(final String pId, final String pKey, final String pStatus, final long pAt, final boolean pCreationProof)
		{
			this.id = pId;
			this.key = pKey;
			this.status = pStatus;
			this.at = pAt;
			this.creationProof = pCreationProof;
			this.proofKnown = true;
		}

		StepRow(final String pId, final String pKey, final String pStatus, final long pAt)
		{
			this.id = pId;
			this.key = pKey;
			this.status = pStatus;
			this.at = pAt;
			this.creationProof = false;
			this.proofKnown = false;
		}
	}

	/**
	 * The purge rule over a stream of rows (audit F-36): {@link #offer} takes the rows one by one, in any order, and keeps only what the rule can still need - the
	 * latest DONE row of each (project, step, scope) key, and the rows that are old enough to be purged (older than the cutoff, dated, not RUNNING). A row within the
	 * retention, a RUNNING one or an undated one is never a candidate, so it is not kept: memory is the number of keys plus the purgeable rows, not the whole
	 * table, and the {@code detail} (up to 100,000 characters, parsed to look for a creation proof) is read by the caller only for a row {@link #toPurge} would
	 * otherwise delete.
	 */
	static final class StepScan
	{
		private final long cutoff;
		/** Audit F2-104: the age past which a DONE row is a candidate (the intake writes one per chain); never later than {@link #cutoff}'s own rule for the others. */
		private final long doneCutoff;
		private final Map<String, StepRow> latestDone = new HashMap<String, StepRow>();
		private final List<StepRow> candidates = new ArrayList<StepRow>();

		StepScan(final long pCutoff)
		{
			this(pCutoff, pCutoff);
		}

		StepScan(final long pCutoff, final long pDoneCutoff)
		{
			this.cutoff = pCutoff;
			this.doneCutoff = pDoneCutoff;
		}

		void offer(final String pId, final String pKey, final String pStatus, final long pAt)
		{
			this.offer(new StepRow(pId, pKey, pStatus, pAt));
		}

		void offer(final StepRow pRow)
		{
			if ("DONE".equals(pRow.status))
			{
				final StepRow current = this.latestDone.get(pRow.key);
				if (current == null || pRow.at > current.at || pRow.at == current.at && pRow.id.compareTo(current.id) > 0)
				{
					this.latestDone.put(pRow.key, pRow);
				}
			}
			if (pRow.at > 0L && pRow.at < ("DONE".equals(pRow.status) ? this.doneCutoff : this.cutoff) && !"RUNNING".equals(pRow.status))
			{
				this.candidates.add(pRow);
			}
		}

		/** @param pProofOf the creation proof of a row by id, called only for a row that is old, not RUNNING and not the latest DONE of its key (may be null: no proof then) */
		Set<String> toPurge(final java.util.function.Predicate<String> pProofOf)
		{
			final Set<String> out = new LinkedHashSet<String>();
			for (final StepRow row : this.candidates)
			{
				final StepRow latest = this.latestDone.get(row.key);
				if (latest != null && latest.id.equals(row.id))
				{
					continue;
				}
				final boolean proof = row.proofKnown ? row.creationProof : pProofOf != null && pProofOf.test(row.id);
				if (!proof)
				{
					out.add(row.id);
				}
			}
			return out;
		}
	}

	/**
	 * The ids to delete: older than {@code pCutoff}, not RUNNING, no creation proof, and not the latest DONE row of its
	 * key (project, step, scope). A row with no date is never deleted.
	 */
	static Set<String> pipelineStepsToPurge(final List<StepRow> pRows, final long pCutoff)
	{
		final StepScan scan = new StepScan(pCutoff);
		for (final StepRow row : pRows)
		{
			scan.offer(row);
		}
		return scan.toPurge(null);
	}

	/** True when a step's detail JSON says it created its dataspace or dataset - the proof the cleanup screens rely on. */
	static boolean creationProof(final String pDetailJson)
	{
		if (pDetailJson == null || pDetailJson.indexOf("\"created\"") < 0)
		{
			return false;
		}
		try
		{
			final JsonNode detail = FactoryRetention.MAPPER.readTree(pDetailJson);
			return detail.path("dataspace").path("created").asBoolean(false) || detail.path("dataset").path("created").asBoolean(false)
				|| detail.path("created").asBoolean(false);
		}
		catch (final java.io.IOException ex)
		{
			return true; // unreadable: keep it rather than guess
		}
	}

	// ========================================================================================================= schedule

	/** Starts the daily purge (first run ten minutes after startup). */
	public static synchronized void start(final Session pSystemSession, final Adaptation pFactory)
	{
		FactoryRetention.stop();
		FactoryRetention.scheduler = Executors.newSingleThreadScheduledExecutor(pRunnable -> {
			final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-retention");
			thread.setDaemon(true);
			return thread;
		});
		FactoryRetention.scheduler.scheduleWithFixedDelay(() -> {
			try
			{
				FactoryRetention.purge(pSystemSession, pFactory, FactoryRetention.days(pFactory));
			}
			catch (final Throwable ex) // never let the scheduler die
			{
				LoggingCategory.getKernel().error("[mdm-factory] history purge failed: " + ex, ex);
			}
		}, 10L, 24L * 60L, TimeUnit.MINUTES);
	}

	public static synchronized void stop()
	{
		if (FactoryRetention.scheduler != null)
		{
			FactoryRetention.scheduler.shutdownNow();
			FactoryRetention.scheduler = null;
		}
	}

	/** Pure: the DONE-step age in days from the property text; absent, unreadable or not positive reads as {@link #DEFAULT_DAYS}. */
	static int doneStepDays(final String pProperty)
	{
		if (pProperty == null)
		{
			return FactoryRetention.DEFAULT_DAYS;
		}
		try
		{
			final int days = Integer.parseInt(pProperty.trim());
			return days > 0 ? days : FactoryRetention.DEFAULT_DAYS;
		}
		catch (final NumberFormatException ex)
		{
			return FactoryRetention.DEFAULT_DAYS;
		}
	}

	static int days(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final Object value = settings == null ? null : settings.get(FactoryPaths.Settings.HISTORY_RETENTION_DAYS);
		return value instanceof Number ? Math.max(0, ((Number) value).intValue()) : FactoryRetention.DEFAULT_DAYS;
	}

	// ============================================================================================================ purge

	/** Deletes history older than {@code pDays} days; {@code pDays <= 0} does nothing. Returns what was deleted, per table. */
	public static ObjectNode purge(final Session pSession, final Adaptation pFactory, final int pDays) throws OperationException
	{
		final ObjectNode out = FactoryRetention.MAPPER.createObjectNode();
		out.put("days", pDays);
		if (pDays <= 0)
		{
			return out;
		}
		final long cutoff = System.currentTimeMillis() - pDays * 24L * 3600L * 1000L;

		final List<String> jobs = FactoryRetention.keys(pFactory.getTable(FactoryPaths.FACTORY_JOB), "not(./status='QUEUED' or ./status='RUNNING')",
			FactoryPaths.FactoryJob.ENDED_AT, cutoff);
		out.put("factoryJobs", FactoryRetention.delete(pSession, pFactory, FactoryPaths.FACTORY_JOB, jobs));

		final List<String> oldRuns = FactoryRetention.keys(pFactory.getTable(FactoryPaths.AGENT_RUN), "not(./status='RUNNING')", FactoryPaths.AgentRun.ENDED_AT, cutoff);
		// audit F3-10 / F3-103 (2026-11-12): AgentProposal has a required foreign key to its run, and a line still waiting for a decision is work for a person: a run that
		// still has one is NOT purged, every proposal of a purged run goes with it, and a decided line outlives neither the retention nor its own run
		final ProposalPurge proposalPurge = FactoryRetention.proposalPurge(FactoryRetention.readProposals(pFactory.getTable(FactoryPaths.AGENT_PROPOSAL)), new java.util.HashSet<String>(oldRuns), cutoff);
		final List<String> runs = new ArrayList<String>(oldRuns);
		runs.removeAll(proposalPurge.runsKept);
		out.put("agentProposals", FactoryRetention.delete(pSession, pFactory, FactoryPaths.AGENT_PROPOSAL, proposalPurge.proposals));
		// audit F-50 (wave 5): ONE read of AgentStep for every purged run - a predicate on ./run scans the whole table, and it used to run once per purged run
		final List<String> agentSteps = FactoryRetention.agentStepKeysOfRuns(pFactory.getTable(FactoryPaths.AGENT_STEP), runs);
		out.put("agentSteps", FactoryRetention.delete(pSession, pFactory, FactoryPaths.AGENT_STEP, agentSteps));
		out.put("agentRuns", FactoryRetention.delete(pSession, pFactory, FactoryPaths.AGENT_RUN, runs));

		// audit F3-10: a schema drift that was decided (rejected, applied or failed) ages out like a run; one still waiting for a decision never does
		out.put("sourceDrifts", FactoryRetention.delete(pSession, pFactory, FactoryPaths.SOURCE_DRIFT, FactoryRetention.driftKeysToPurge(pFactory.getTable(FactoryPaths.SOURCE_DRIFT), cutoff)));

		// audit F-36 (wave 5): one streaming pass that keeps only the latest DONE row of each key and the rows old enough to be purged; the detail of a row (which can
		// be 100,000 characters, parsed for a creation proof) is read only for a row that would otherwise be deleted - not for every row of the table, most of which are within the retention
		final StepScan scan = new StepScan(cutoff, System.currentTimeMillis() - FactoryRetention.doneStepDays(System.getProperty(FactoryRetention.DONE_STEP_DAYS_PROPERTY)) * 24L * 3600L * 1000L);
		final AdaptationTable stepTable = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final RequestResult rows = stepTable.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object ended = row.get(FactoryPaths.PipelineStep.ENDED_AT);
				final Object started = row.get(FactoryPaths.PipelineStep.STARTED_AT);
				final long at = ended instanceof Date ? ((Date) ended).getTime() : started instanceof Date ? ((Date) started).getTime() : 0L;
				final String scope = row.getString(FactoryPaths.PipelineStep.SCOPE);
				scan.offer(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.PipelineStep.PROJECT) + "|" + row.getString(FactoryPaths.PipelineStep.STEP) + "|"
					+ (scope == null ? "" : scope), row.getString(FactoryPaths.PipelineStep.STATUS), at);
			}
		}
		finally
		{
			rows.close();
		}
		final Set<String> stepsToPurge = scan.toPurge(new java.util.function.Predicate<String>()
		{
			@Override
			public boolean test(final String pId)
			{
				final Adaptation row = stepTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId));
				return row != null && FactoryRetention.creationProof(row.getString(FactoryPaths.PipelineStep.DETAIL));
			}
		});
		out.put("pipelineSteps", FactoryRetention.delete(pSession, pFactory, FactoryPaths.PIPELINE_STEP, new ArrayList<String>(stepsToPurge)));

		// volume-operations lot 8a (2026-09-28, task 5): both genuinely volume-scale (one row per outbound
		// event / per rejected staged row) - a plain date (and, for TransferReject, RESOLVED) predicate, never
		// a detail-sized field read. See this class' own Javadoc for why an unresolved reject is never purged.
		final List<String> distributionEvents = FactoryRetention.keys(pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT), null, FactoryPaths.DistributionEvent.CREATED_AT, cutoff);
		out.put("distributionEvents", FactoryRetention.delete(pSession, pFactory, FactoryPaths.DISTRIBUTION_EVENT, distributionEvents));

		// automatic-intake lot (2026-10-05): finished chains older than the retention - a RUNNING chain is the project's "a chain runs" proof and is never purged
		final List<String> intakeChains = FactoryRetention.keys(pFactory.getTable(FactoryPaths.INTAKE_CHAIN), "not(./status='RUNNING')", FactoryPaths.IntakeChain.STARTED_AT, cutoff);
		out.put("intakeChains", FactoryRetention.delete(pSession, pFactory, FactoryPaths.INTAKE_CHAIN, intakeChains));

		// audit F-38 (wave 10): a reject a steward acknowledged is set aside on purpose, so it ages out like a resolved one; an open one never does
		final List<String> transferRejects = FactoryRetention.keys(pFactory.getTable(FactoryPaths.TRANSFER_REJECT), FactoryRetention.REJECT_PURGE_PREDICATE,
			FactoryPaths.TransferReject.REJECTED_AT, cutoff);
		out.put("transferRejects", FactoryRetention.delete(pSession, pFactory, FactoryPaths.TRANSFER_REJECT, transferRejects));

		LoggingCategory.getKernel().info("[mdm-factory] history older than " + pDays + " days purged: " + out);
		return out;
	}

	// ------------------------------------------------------------------------------------------------ agent proposals and source drifts (audit F3-10)

	/** One {@code AgentProposal} line as the purge rule sees it; {@code decidedAt} is 0 when none. */
	static final class ProposalRow
	{
		final String id;
		final String run;
		final String status;
		final boolean applyAfterApproval;
		final long decidedAt;

		ProposalRow(final String pId, final String pRun, final String pStatus, final boolean pApplyAfterApproval, final long pDecidedAt)
		{
			this.id = pId;
			this.run = pRun;
			this.status = pStatus;
			this.applyAfterApproval = pApplyAfterApproval;
			this.decidedAt = pDecidedAt;
		}
	}

	/** What {@link #proposalPurge} decides: the proposals to delete and the candidate runs that must stay. */
	static final class ProposalPurge
	{
		final List<String> proposals = new ArrayList<String>();
		final Set<String> runsKept = new java.util.LinkedHashSet<String>();
	}

	/**
	 * A proposal still waiting on a person: {@code PROPOSED}, or {@code ACCEPTED} by hand on an agent that may act and not yet applied ({@code applyAfterApproval} and no
	 * {@code decidedAt}, the lines {@code AgentFramework} applies once the review task is accepted). Everything else is decided or applied.
	 */
	static boolean proposalPending(final String pStatus, final boolean pApplyAfterApproval, final boolean pDecided)
	{
		return "PROPOSED".equals(pStatus) || "ACCEPTED".equals(pStatus) && pApplyAfterApproval && !pDecided;
	}

	/**
	 * Pure: which proposals the purge deletes. A candidate run (old, not running) holding a pending proposal is kept, with ALL its proposals; the proposals of any other
	 * candidate run go with it (the foreign key); a decided proposal of a kept run goes alone once its own {@code decidedAt} is before the cutoff. A pending proposal is never deleted.
	 */
	static ProposalPurge proposalPurge(final List<ProposalRow> pRows, final Set<String> pCandidateRuns, final long pCutoff)
	{
		final ProposalPurge out = new ProposalPurge();
		for (final ProposalRow row : pRows)
		{
			if (pCandidateRuns.contains(row.run) && FactoryRetention.proposalPending(row.status, row.applyAfterApproval, row.decidedAt > 0L))
			{
				out.runsKept.add(row.run);
			}
		}
		for (final ProposalRow row : pRows)
		{
			if (FactoryRetention.proposalPending(row.status, row.applyAfterApproval, row.decidedAt > 0L))
			{
				continue;
			}
			final boolean runGoes = pCandidateRuns.contains(row.run) && !out.runsKept.contains(row.run);
			if (runGoes || row.decidedAt > 0L && row.decidedAt < pCutoff)
			{
				out.proposals.add(row.id);
			}
		}
		return out;
	}

	/** Pure: a {@code SourceDrift} is purgeable once decided (not PROPOSED, not ACCEPTED-and-unapplied) and its last date (applied, else decided, else detected) is before the cutoff; no date, never. */
	static boolean driftPurgeable(final String pStatus, final long pAppliedAt, final long pDecidedAt, final long pDetectedAt, final long pCutoff)
	{
		if ("PROPOSED".equals(pStatus) || "ACCEPTED".equals(pStatus))
		{
			return false;
		}
		final long at = pAppliedAt > 0L ? pAppliedAt : pDecidedAt > 0L ? pDecidedAt : pDetectedAt;
		return at > 0L && at < pCutoff;
	}

	private static long time(final Object pDate)
	{
		return pDate instanceof Date ? ((Date) pDate).getTime() : 0L;
	}

	/** Every proposal line, in ONE pass (the table is small: lines of agent runs, no volume). */
	private static List<ProposalRow> readProposals(final AdaptationTable pTable)
	{
		final List<ProposalRow> out = new ArrayList<ProposalRow>();
		final RequestResult rows = pTable.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new ProposalRow(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.AgentProposal.RUN), row.getString(FactoryPaths.AgentProposal.STATUS),
					Boolean.TRUE.equals(row.get(FactoryPaths.AgentProposal.APPLY_AFTER_APPROVAL)), FactoryRetention.time(row.get(FactoryPaths.AgentProposal.DECIDED_AT))));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static List<String> driftKeysToPurge(final AdaptationTable pTable, final long pCutoff)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pTable.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (FactoryRetention.driftPurgeable(row.getString(FactoryPaths.SourceDrift.STATUS), FactoryRetention.time(row.get(FactoryPaths.SourceDrift.APPLIED_AT)),
					FactoryRetention.time(row.get(FactoryPaths.SourceDrift.DECIDED_AT)), FactoryRetention.time(row.get(FactoryPaths.SourceDrift.DETECTED_AT)), pCutoff))
				{
					out.add(row.getOccurrencePrimaryKey().format());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** The keys of every {@code AgentStep} row of the given runs, in ONE pass over the table (audit F-50); no read at all when there is no run to purge. */
	private static List<String> agentStepKeysOfRuns(final AdaptationTable pSteps, final List<String> pRuns)
	{
		if (pRuns.isEmpty())
		{
			return new ArrayList<String>();
		}
		final Set<String> runs = new java.util.HashSet<String>(pRuns);
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pSteps.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String run = row.getString(FactoryPaths.AgentStep.RUN);
				if (run != null && runs.contains(run))
				{
					out.add(row.getOccurrencePrimaryKey().format());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Keys of the rows matching {@code pPredicate} and, when {@code pDate} is given, whose date there is before {@code pBefore}. */
	private static List<String> keys(final AdaptationTable pTable, final String pPredicate, final Path pDate, final long pBefore)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = pTable.createRequestResult(pPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (pDate != null)
				{
					final Object value = row.get(pDate);
					if (!(value instanceof Date) || ((Date) value).getTime() >= pBefore)
					{
						continue;
					}
				}
				out.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Deletes the rows in transactions of {@link #BATCH}; a row already gone is skipped. Returns how many were deleted. */
	private static int delete(final Session pSession, final Adaptation pFactory, final Path pTable, final List<String> pKeys) throws OperationException
	{
		int deleted = 0;
		for (int from = 0; from < pKeys.size(); from += FactoryRetention.BATCH)
		{
			final List<String> batch = pKeys.subList(from, Math.min(pKeys.size(), from + FactoryRetention.BATCH));
			final int[] count = { 0 };
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					final AdaptationTable table = pFactory.getTable(pTable);
					for (final String key : batch)
					{
						final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key));
						if (row != null)
						{
							pContext.doDelete(row.getAdaptationName(), false);
							count[0]++;
						}
					}
				}
			});
			if (result.hasFailed())
			{
				throw result.getException() != null ? result.getException() : EngineRefusal.coded("unexpected.historyPurge", "History purge of " + pTable + " failed", pTable);
			}
			deleted += count[0];
		}
		return deleted;
	}
}
