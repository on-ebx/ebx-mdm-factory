package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.DistributionArmingGate;
import com.on.ebx.factory.distribution.EventCoalescer;
import com.on.ebx.factory.distribution.GoldenOnlySends;
import com.on.ebx.factory.distribution.NoticeDocument;
import com.on.ebx.factory.distribution.RetireRules;
import com.on.ebx.factory.distribution.RetryBackoff;
import com.on.ebx.factory.distribution.SyncOutcome;
import com.on.ebx.factory.distribution.TargetMappingList;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
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
 * Distribution lot 1 (engine, SPEC-distribution.md "Real time"): "A module-owned Java dispatcher drains
 * committed PENDING events a few seconds later and sends each record through Connectors ..., marks SENT /
 * FAILED (retry with backoff, replay from the Cockpit)." One daemon thread for the module's whole life,
 * started/stopped from {@link FactoryModuleRegistration} - the established pattern in this module (see
 * {@code PerspectiveInstaller}'s own retry thread; CLAUDE.md sec 10's "never pins a stopped class loader"
 * caution is why {@link #stop} is called from {@code handleRepositoryShutdown}, unlike
 * {@code PerspectiveInstaller}'s short-lived retry thread, which needs no explicit stop).
 * <p>
 * <b>Round 3 redesign (2026-09-26): the outbox moved out of MdmFactory, into each project's own master
 * dataspace</b> - a master-table trigger cannot open a transaction of its own at all (measured live,
 * PipelineStep 395: "This type of script cannot create transactions"), so it can only write through its
 * ambient {@code _ebx.transaction}, scoped to its own dataspace; it can never reach a row of MdmFactory (see
 * {@code DistributionTriggerBody}'s own Javadoc for the full transcript). This class is UNAFFECTED by that
 * restriction - it runs as an ordinary Java background thread with a system session, not inside a table
 * trigger, so it reaches BOTH the project's master dataspace (to drain the outbox) AND MdmFactory (to read
 * {@code DistributionTarget} and write the {@code DistributionEvent} HISTORY row) freely, each through its
 * own {@code ProgrammaticService} call on the right {@code AdaptationHome}.
 * <p>
 * Each pass: reads every {@code ARMED} REALTIME {@code DistributionTarget} of MdmFactory, resolves its
 * project's master dataspace, and - only if that dataspace already carries a
 * {@code DistributionOutbox} dataset (it will not yet, on a project whose triggers were installed before
 * this pass' redeploy - degrades to "nothing to drain for this target", not an error) - drains that
 * dataset's {@code Event} rows filtered to {@code status=PENDING and target=<this target's id>}, groups them
 * by {@code (tablePath, recordKey)}, nets each group with {@code distribution.EventCoalescer}, re-checks the
 * master record's CURRENT committed state (coordinator review item 2, unchanged reasoning - the outbox row's
 * own transaction is independent of the record's save, so it cannot be trusted blindly), then sends the
 * surviving row and DELETES the outbox row - the outbox is a working queue, not a log. On a TERMINAL outcome
 * (SENT, SKIPPED, or FAILED past {@link RetryBackoff#MAX_ATTEMPTS}) a row is written into MdmFactory's own
 * {@code DistributionEvent} table as a permanent history/audit entry (what the Cockpit/wizard events list
 * and the REST {@code /distribution/events} route show - {@code DistributionReader}/{@code FactoryResource}
 * read that table, unchanged). A FAILED attempt still under budget is NOT terminal: the outbox row itself is
 * updated in place (status stays {@code PENDING}, {@code attempts}/{@code nextAttemptAt} advanced) and
 * nothing is written to history yet - only the eventual terminal outcome is.
 * <p>
 * <b>Not verified live</b>: no EBX/Connectors instance was reachable while writing this class - never run.
 * See {@code docs/DISTRIBUTION.md}, including for what is NOT measured (whether a by-key {@code
 * connectors_sync} on a since-deleted record actually propagates a delete to the target).
 */
public final class DistributionDispatcher
{
	private static final long PERIOD_SECONDS = 10;
	/**
	 * Volume-operations lot 8a (2026-09-28, task 4): caps how many PENDING outbox rows one {@link #drainTarget}
	 * pass reads for one target - a hard-coded constant, not a {@code Settings} field (a new field would be an
	 * XSD/schema change to {@code MdmFactory.xsd}, owned by the sibling lot on
	 * {@code feature/volume-loader-memory} per this lot's own file-ownership boundary; flagged for the
	 * coordinator as a follow-up if this needs to be tunable). A target with more than this many due rows
	 * simply drains the rest on later passes (every 10s, {@link #PERIOD_SECONDS}) - never a correctness issue,
	 * only how many passes a large backlog takes to clear.
	 */
	static final int DRAIN_BATCH_CAP = 1000;
	private static volatile ScheduledExecutorService executor;
	private static volatile Repository repository;
	private static volatile Session systemSession;
	private static volatile LoggingCategory log;
	/** Lot S2: projects whose targets must sweep at their next pass whatever the minimum interval (a matching run has just ended). */
	private static final Set<String> FORCED_SWEEPS = ConcurrentHashMap.newKeySet();
	/** The projects forced for the pass under way (a snapshot of {@link #FORCED_SWEEPS} taken when the pass starts, so every target of the project sees it). */
	private static volatile Set<String> passForced = java.util.Collections.emptySet();

	/**
	 * Volume-operations lot 8a (2026-09-28, task 4): the current {@link #drainTarget} pass' deferred writes -
	 * every {@link #writeHistory}/{@link #deleteOutboxRows} call during one pass appends here instead of
	 * opening its own {@link Procedure}; {@link #drainTarget} flushes both lists in exactly TWO transactions
	 * (one history write, one delete) once the whole pass' decisions are made, instead of up to two PER GROUP
	 * (task brief item 4: "one history transaction, one delete transaction" per record before this lot). A
	 * {@code ThreadLocal} rather than a plain static field on principle (the dispatcher runs one target at a
	 * time on its own single background thread today - {@link #drainAllProjects} - but nothing about this
	 * accumulator should assume that stays true forever). {@code null} outside a pass (defensive - every write
	 * call falls back to its OLD immediate-{@link Procedure} behaviour when read outside one, so a future
	 * caller of {@link #writeHistory}/{@link #deleteOutboxRows} added elsewhere in this module is never
	 * silently swallowed).
	 * <p>
	 * <b>Accepted trade-off</b> (same posture as this module's other batching lots - {@code BatchCircuitBreaker},
	 * {@code ProjectDeleter}'s own partial-commit note): deferring every group's write to the END of the pass
	 * means a crash mid-pass loses the WHOLE pass' history/deletes, not just the not-yet-reached groups - the
	 * next pass re-sends every record the crashed pass had already (successfully) sent. This is judged safe
	 * because a {@code connectors_sync}/{@code connectors_call} send is a resync of the record's CURRENT state,
	 * not a create-only operation (CLAUDE.md's own "one connector = one database" family of notes; every send
	 * in this class is keyed by the record's own primary key) - a re-send after a crash is redundant, not
	 * duplicating data, unlike the per-group-immediate-commit design this replaces which never had to make
	 * that trade-off. Not verified live (no EBX/Connectors instance reachable this session).
	 */
	private static final ThreadLocal<DrainWrites> CURRENT_WRITES = new ThreadLocal<DrainWrites>();
	/** Lot S2: {@code [reason, mergeId]} of the outbox group {@link #drainGroup} is working on, read by the three {@code finish*} methods for their history row. */
	private static final ThreadLocal<String[]> CURRENT_NOTE = new ThreadLocal<String[]>();

	private static String noteReason()
	{
		final String[] note = DistributionDispatcher.CURRENT_NOTE.get();
		return note == null ? null : note[0];
	}

	private static String noteMergeId()
	{
		final String[] note = DistributionDispatcher.CURRENT_NOTE.get();
		return note == null ? null : note[1];
	}

	/** One pass' deferred writes - see {@link #CURRENT_WRITES}'s own Javadoc. */
	private static final class DrainWrites
	{
		final List<HistoryRow> history = new ArrayList<HistoryRow>();
		/** May contain the same id more than once (harmless - {@link #flush} looks each one up by primary key and skips an already-gone row). */
		final List<String> deletes = new ArrayList<String>();
	}

	/** One row to write into {@code MdmFactory.DistributionEvent} once the pass flushes - the exact fields {@link #writeHistory} used to write immediately. */
	private static final class HistoryRow
	{
		final String targetId, projectId, tablePath, recordKey, change, survivorKey, status, message;
		/** Lot S2: the outbox row's {@code reason}/{@code mergeId} (set by the merge log watch and the state sweep); null for a trigger-written row. */
		final String reason, mergeId;
		final int attempts;
		final boolean sent;
		final Date createdAt;

		HistoryRow(final String pTargetId, final String pProjectId, final String pTablePath, final String pRecordKey, final String pChange,
			final String pSurvivorKey, final String pStatus, final int pAttempts, final String pMessage, final boolean pSent, final Date pCreatedAt,
			final String pReason, final String pMergeId)
		{
			this.reason = pReason;
			this.mergeId = pMergeId;
			this.targetId = pTargetId;
			this.projectId = pProjectId;
			this.tablePath = pTablePath;
			this.recordKey = pRecordKey;
			this.change = pChange;
			this.survivorKey = pSurvivorKey;
			this.status = pStatus;
			this.attempts = pAttempts;
			this.message = pMessage;
			this.sent = pSent;
			this.createdAt = pCreatedAt;
		}
	}

	private DistributionDispatcher()
	{
	}

	/** Starts the dispatcher if it is not already running. Idempotent - a second call is a no-op. */
	public static synchronized void start(final Repository pRepository, final Session pSystemSession, final LoggingCategory pLog)
	{
		if (DistributionDispatcher.executor != null)
		{
			return;
		}
		DistributionDispatcher.repository = pRepository;
		DistributionDispatcher.systemSession = pSystemSession;
		DistributionDispatcher.log = pLog;
		final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
		{
			@Override
			public Thread newThread(final Runnable pRunnable)
			{
				final Thread thread = new Thread(pRunnable, "ebx-mdm-factory-distribution-dispatcher");
				thread.setDaemon(true);
				return thread;
			}
		});
		scheduler.scheduleWithFixedDelay(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					DistributionDispatcher.drainAllProjects();
				}
				catch (final Throwable ex) // a scheduled task that throws is never run again: the whole distribution would silently stop
				{
					DistributionDispatcher.log.error("[mdm-factory] distribution dispatcher pass failed: " + ex, ex);
				}
			}
		}, DistributionDispatcher.PERIOD_SECONDS, DistributionDispatcher.PERIOD_SECONDS, TimeUnit.SECONDS);
		DistributionDispatcher.executor = scheduler;
	}

	/**
	 * Lot S2 (2026-10-18): asks for an immediate pass over the project's armed REALTIME targets with the state sweep forced - called by the Factory when a
	 * matching run has ended (an automatic merge writes the metadataset only, so nothing else would tell the targets before the next minute-tick). Returns at
	 * once; the pass runs on the dispatcher's own thread, so it never overlaps a drain. A no-op while the dispatcher is not started.
	 */
	public static void requestSweep(final String pProjectId)
	{
		final ScheduledExecutorService running = DistributionDispatcher.executor;
		if (running == null || pProjectId == null || pProjectId.trim().isEmpty())
		{
			return;
		}
		DistributionDispatcher.FORCED_SWEEPS.add(pProjectId);
		try
		{
			running.execute(new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						DistributionDispatcher.drainAllProjects();
					}
					catch (final Throwable ex)
					{
						DistributionDispatcher.log.error("[mdm-factory] distribution dispatcher: requested pass failed: " + ex, ex);
					}
				}
			});
		}
		catch (final java.util.concurrent.RejectedExecutionException ex)
		{
			DistributionDispatcher.FORCED_SWEEPS.remove(pProjectId); // stopped between the check and the call
		}
	}

	/** Stops the dispatcher; called from {@code FactoryModuleRegistration#handleRepositoryShutdown} so the background thread never outlives a stopped webapp classloader (CLAUDE.md sec 10). Idempotent. */
	public static synchronized void stop()
	{
		if (DistributionDispatcher.executor != null)
		{
			DistributionDispatcher.executor.shutdownNow();
			DistributionDispatcher.executor = null;
		}
	}

	static void drainAllProjects()
	{
		final Set<String> forced = new java.util.HashSet<String>(DistributionDispatcher.FORCED_SWEEPS);
		DistributionDispatcher.FORCED_SWEEPS.removeAll(forced);
		DistributionDispatcher.passForced = forced;
		final Adaptation factory = FactoryInstaller.dataset(DistributionDispatcher.repository);
		if (factory == null)
		{
			return;
		}
		final Session session = DistributionDispatcher.systemSession;
		final RequestResult targets = factory.getTable(FactoryPaths.DISTRIBUTION_TARGET).createRequestResult(
			"./mode=" + CatalogReader.quote(FactoryPaths.DistributionTarget.MODE_REALTIME) + " and ./state=" + CatalogReader.quote(FactoryPaths.DistributionTarget.STATE_ARMED));
		final List<Adaptation> armedTargets = new ArrayList<Adaptation>();
		try
		{
			for (Adaptation target = targets.nextAdaptation(); target != null; target = targets.nextAdaptation())
			{
				armedTargets.add(target);
			}
		}
		finally
		{
			targets.close();
		}
		for (final Adaptation target : armedTargets)
		{
			try
			{
				DistributionDispatcher.drainTarget(session, factory, target);
			}
			catch (final Throwable ex) // one target (or an Error such as a LinkageError) must not keep the other targets from draining
			{
				DistributionDispatcher.log.error("[mdm-factory] distribution dispatcher: target " + target.getOccurrencePrimaryKey().format() + " failed: " + ex, ex);
			}
		}
	}

	/** One ARMED REALTIME target: resolves its project's master dataspace, finds the outbox dataset there (may not exist yet - degrades to nothing to drain), and drains its PENDING rows for this target. */
	private static void drainTarget(final Session pSession, final Adaptation pFactory, final Adaptation pTarget)
	{
		final String targetId = pTarget.getOccurrencePrimaryKey().format();
		final String projectId = pTarget.getString(FactoryPaths.DistributionTarget.PROJECT);
		final Adaptation project = projectId == null ? null : pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectId));
		if (project == null)
		{
			return;
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (masterDataspaceKey == null || masterDataspaceKey.trim().isEmpty() || masterDatasetName == null || masterDatasetName.trim().isEmpty())
		{
			return;
		}
		final AdaptationHome masterHome = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		if (masterHome == null)
		{
			return;
		}
		final Adaptation masterDataset = masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		if (masterDataset == null)
		{
			return;
		}
		final Adaptation outboxDataset = masterHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DISTRIBUTION_OUTBOX_DATASET));
		if (outboxDataset == null)
		{
			// the project's triggers were installed before this module carried the outbox model, or never
			// installed at all - nothing to drain, not an error; ebx.DistributionTriggerInstaller creates it
			return;
		}
		// "retire notices from the merge log" (SPEC-retire.md): run BEFORE draining, so a merge's own RETIRE
		// rows (written straight into this same outbox by MergeLogWatcher) are queued in the same pass as the
		// golden's own ordinary trigger-written CREATE/MODIFY event - see this class' own ordering fix below.
		MergeLogWatcher.watch(pSession, pFactory, masterHome, masterDatasetName, outboxDataset, pTarget, targetId, DistributionDispatcher.log);
		// lot S2 (2026-10-18): the state sweep, right after the watch (its fresh rows are then in the ledger) and before the drain below: what the merge log cannot
		// show - former members of an undone merge, a notice that was skipped - is queued in this same pass. At most once a minute per target, at once after a matching run.
		try
		{
			MatchingStateSweeper.sweep(pSession, pFactory, masterHome, masterDatasetName, outboxDataset, targetId, DistributionDispatcher.passForced.contains(projectId), DistributionDispatcher.log);
		}
		catch (final Throwable ex) // the sweep is a safety net: whatever it throws, the drain below must still run
		{
			DistributionDispatcher.log.error("[mdm-factory] distribution dispatcher: state sweep of target " + targetId + " failed: " + ex, ex);
		}
		final AdaptationTable outboxTable = outboxDataset.getTable(FactoryPaths.DistributionOutbox.EVENT);
		final Date now = new Date();
		final Map<String, List<EventCoalescer.Event>> groups = new LinkedHashMap<String, List<EventCoalescer.Event>>();
		final Map<String, Adaptation> firstRowOfGroup = new LinkedHashMap<String, Adaptation>();
		// volume-operations lot 8a (2026-09-28, task 4, item 1): capped at DRAIN_BATCH_CAP rows READ (not
		// groups - a burst of changes on one record can still produce fewer groups than rows read), never an
		// unbounded materialization of every PENDING row of the target. A target with more due rows than the
		// cap simply drains the rest on the next pass (10s later) - see DRAIN_BATCH_CAP's own Javadoc.
		int rowsRead = 0;
		boolean capped = false;
		final RequestResult rows = outboxTable.createRequestResult(
			"./status=" + CatalogReader.quote(FactoryPaths.DistributionOutbox.STATUS_PENDING) + " and ./target=" + CatalogReader.quote(targetId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (rowsRead >= DistributionDispatcher.DRAIN_BATCH_CAP)
				{
					capped = true;
					break;
				}
				rowsRead++;
				final Object nextAttemptAt = row.get(FactoryPaths.DistributionOutbox.NEXT_ATTEMPT_AT);
				if (nextAttemptAt instanceof Date && ((Date) nextAttemptAt).after(now))
				{
					continue; // not due yet - left PENDING, picked up on a later pass
				}
				final String id = row.getOccurrencePrimaryKey().format();
				final String tablePath = row.getString(FactoryPaths.DistributionOutbox.TABLE_PATH);
				final String recordKey = row.getString(FactoryPaths.DistributionOutbox.RECORD_KEY);
				final String change = row.getString(FactoryPaths.DistributionOutbox.CHANGE);
				final String groupKey = tablePath + "|" + recordKey;
				List<EventCoalescer.Event> group = groups.get(groupKey);
				if (group == null)
				{
					group = new ArrayList<EventCoalescer.Event>();
					groups.put(groupKey, group);
				}
				group.add(new EventCoalescer.Event(id, change, DistributionDispatcher.sequenceOf(id)));
				firstRowOfGroup.put(groupKey, row);
			}
		}
		finally
		{
			rows.close();
		}
		if (capped && DistributionDispatcher.log != null)
		{
			DistributionDispatcher.log.info("[mdm-factory] distribution dispatcher: target " + targetId + " has more than " + DistributionDispatcher.DRAIN_BATCH_CAP
				+ " PENDING row(s) due - draining the rest on a later pass.");
		}
		// "retire notices from the merge log" (SPEC-retire.md): within one pass for one target, every
		// CREATE/MODIFY group is sent before every RETIRE group - a merge's retire notice only makes sense
		// once the golden's own create/modify has actually gone out. Coalescing is cheap and pure
		// (EventCoalescer), so it is run once here purely to classify/order groups; drainGroup below runs it
		// again on the exact same input list (deterministic, harmless) rather than threading the already-
		// coalesced result through and changing that method's signature for this lot alone.
		final List<Map.Entry<String, List<EventCoalescer.Event>>> nonRetireGroups = new ArrayList<Map.Entry<String, List<EventCoalescer.Event>>>();
		final List<Map.Entry<String, List<EventCoalescer.Event>>> retireGroups = new ArrayList<Map.Entry<String, List<EventCoalescer.Event>>>();
		for (final Map.Entry<String, List<EventCoalescer.Event>> entry : groups.entrySet())
		{
			final String effectiveChange = EventCoalescer.coalesce(entry.getValue()).effectiveChange;
			(EventCoalescer.RETIRE.equals(effectiveChange) ? retireGroups : nonRetireGroups).add(entry);
		}
		final List<Map.Entry<String, List<EventCoalescer.Event>>> ordered = new ArrayList<Map.Entry<String, List<EventCoalescer.Event>>>(nonRetireGroups);
		ordered.addAll(retireGroups);
		// volume-operations lot 8a (2026-09-28, task 4, items 2-3): one accumulator for the WHOLE pass - every
		// writeHistory/deleteOutboxRows call below appends to it instead of opening its own Procedure; flushed
		// in exactly two transactions once every group has been decided. See CURRENT_WRITES' own Javadoc for
		// the accepted trade-off.
		final DrainWrites writes = new DrainWrites();
		DistributionDispatcher.CURRENT_WRITES.set(writes);
		try
		{
			for (final Map.Entry<String, List<EventCoalescer.Event>> entry : ordered)
			{
				try
				{
					DistributionDispatcher.drainGroup(pSession, pFactory, masterHome, masterDataset, outboxTable, pTarget, targetId, projectId, entry.getValue(),
						firstRowOfGroup.get(entry.getKey()));
				}
				catch (final Throwable ex)
				{
					DistributionDispatcher.log.error("[mdm-factory] distribution dispatcher: group " + entry.getKey() + " of target " + targetId + " failed: " + ex, ex);
				}
				finally
				{
					DistributionDispatcher.CURRENT_NOTE.remove();
				}
			}
		}
		finally
		{
			DistributionDispatcher.CURRENT_WRITES.remove();
		}
		DistributionDispatcher.flush(pSession, pFactory, masterHome, outboxTable, writes);
	}

	/**
	 * Volume-operations lot 8a (2026-09-28, task 4, items 2-3): the pass' one and only write - every history
	 * row accumulated by {@link #writeHistory} in ONE {@link Procedure} against {@code MdmFactory}, then every
	 * accumulated outbox id in ONE {@link Procedure} against the master dataspace's outbox dataset. Either list
	 * being empty skips its own transaction entirely (a pass with nothing to send opens neither). Logged, never
	 * thrown - same "a dispatcher pass must never die because one part of the bookkeeping failed" posture the
	 * rest of this class already has.
	 */
	private static void flush(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final AdaptationTable pOutboxTable,
		final DrainWrites pWrites)
	{
		if (!pWrites.history.isEmpty())
		{
			final AdaptationTable events = pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT);
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					for (final HistoryRow row : pWrites.history)
					{
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(events);
						value.setValue(row.projectId, FactoryPaths.DistributionEvent.PROJECT);
						value.setValue(row.targetId, FactoryPaths.DistributionEvent.TARGET);
						value.setValue(row.tablePath, FactoryPaths.DistributionEvent.TABLE_PATH);
						value.setValue(row.recordKey, FactoryPaths.DistributionEvent.RECORD_KEY);
						value.setValue(row.change, FactoryPaths.DistributionEvent.CHANGE);
						value.setValue(row.survivorKey, FactoryPaths.DistributionEvent.SURVIVOR_KEY);
						value.setValue(row.status, FactoryPaths.DistributionEvent.STATUS);
						value.setValue(Integer.valueOf(row.attempts), FactoryPaths.DistributionEvent.ATTEMPTS);
						value.setValue(row.createdAt, FactoryPaths.DistributionEvent.CREATED_AT);
						if (row.reason != null && !row.reason.trim().isEmpty())
						{
							value.setValue(row.reason, FactoryPaths.DistributionEvent.REASON);
						}
						if (row.mergeId != null && !row.mergeId.trim().isEmpty())
						{
							value.setValue(row.mergeId, FactoryPaths.DistributionEvent.MERGE_ID);
						}
						if (row.sent)
						{
							value.setValue(new Date(), FactoryPaths.DistributionEvent.SENT_AT);
						}
						if (row.message != null)
						{
							value.setValue(com.on.ebx.factory.landing.TextLimits.truncate(row.message, com.on.ebx.factory.landing.TextLimits.MESSAGE_MAX_LENGTH),
								FactoryPaths.DistributionEvent.MESSAGE);
						}
						pContext.doCreateOccurrence(value, events);
					}
				}
			});
			if (result.hasFailed() && DistributionDispatcher.log != null)
			{
				DistributionDispatcher.log.error(
					"[mdm-factory] distribution dispatcher: could not write " + pWrites.history.size() + " history event(s): " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
			}
		}
		if (!pWrites.deletes.isEmpty())
		{
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pMasterHome).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					for (final String id : pWrites.deletes)
					{
						final Adaptation row = pOutboxTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(id));
						if (row != null)
						{
							pContext.doDelete(row.getAdaptationName(), false);
						}
					}
				}
			});
			if (result.hasFailed() && DistributionDispatcher.log != null)
			{
				DistributionDispatcher.log.error(
					"[mdm-factory] distribution dispatcher: could not delete " + pWrites.deletes.size() + " outbox row(s): " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
			}
		}
	}

	/** The row's own auto-increment id as a long, for {@link EventCoalescer.Event#sequence}. */
	private static long sequenceOf(final String pFormattedId)
	{
		try
		{
			return Long.parseLong(pFormattedId.trim());
		}
		catch (final NumberFormatException ex)
		{
			return 0L;
		}
	}

	private static void drainGroup(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final Adaptation pMasterDataset,
		final AdaptationTable pOutboxTable, final Adaptation pTarget, final String pTargetId, final String pProjectId, final List<EventCoalescer.Event> pEvents,
		final Adaptation pAnyRow)
	{
		final EventCoalescer.Coalesced coalesced = EventCoalescer.coalesce(pEvents);
		final Map<String, String> idToChange = new LinkedHashMap<String, String>();
		for (final EventCoalescer.Event event : pEvents)
		{
			idToChange.put(event.id, event.change);
		}
		final String tablePath = pAnyRow.getString(FactoryPaths.DistributionOutbox.TABLE_PATH);
		final String recordKey = pAnyRow.getString(FactoryPaths.DistributionOutbox.RECORD_KEY);
		final String survivorKey = pAnyRow.getString(FactoryPaths.DistributionOutbox.SURVIVOR_KEY);
		// "retire notices from the merge log" (SPEC-retire.md): only ever set by ebx.MergeLogWatcher - read
		// off the same representative row as survivorKey above (an existing, pre-lot convention this follows
		// rather than changes).
		final String retireReason = pAnyRow.getString(FactoryPaths.DistributionOutbox.REASON);
		final String mergeId = pAnyRow.getString(FactoryPaths.DistributionOutbox.MERGE_ID);
		DistributionDispatcher.CURRENT_NOTE.set(new String[] { retireReason, mergeId });

		if (EventCoalescer.NONE.equals(coalesced.effectiveChange) || coalesced.survivingId == null)
		{
			// round 6 fix (2026-09-26, task brief): a NONE result means nothing was EVER going to be sent for
			// this burst (e.g. CREATE+DELETE) - there is no representative id to name, so every folded id gets
			// EventCoalescer.NETTED_TO_NONE_MESSAGE instead of "superseded by event <id>". This is itself the
			// terminal outcome (nothing to wait for), so these ids are closed immediately, unlike the
			// coalesced-away ids below which wait for the survivor's own outcome.
			DistributionDispatcher.writeFoldedHistory(pSession, pFactory, pTargetId, pProjectId, tablePath, recordKey, idToChange, pOutboxTable,
				EventCoalescer.NETTED_TO_NONE_MESSAGE, coalesced.allIds);
			DistributionDispatcher.deleteOutboxRows(pSession, pMasterHome, pOutboxTable, coalesced.allIds);
			return;
		}
		// round 6 fix (2026-09-26, task brief): these ids were netted away by THIS SAME coalesce() call (e.g.
		// CREATE+MODIFY -> CREATE) - they must still get exactly one SKIPPED history row each, but only once
		// coalesced.survivingId's own send below reaches a terminal outcome (see EventCoalescer#supersededMessage's
		// Javadoc for why - the previous code deleted them here, immediately, with no history at all).
		final List<String> foldedIds = coalesced.idsToSkip();

		if (!DistributionArmingGate.mayQueueRealtimeEvent(pTarget.getString(FactoryPaths.DistributionTarget.STATE)))
		{
			// the target was disarmed/paused/disabled after the event was queued: skip rather than send against a stale configuration
			DistributionDispatcher.finishSkipped(pSession, pFactory, pMasterHome, pOutboxTable, pTarget, pTargetId, pProjectId, tablePath, recordKey, coalesced.effectiveChange,
				survivorKey, coalesced.survivingId, "target no longer ARMED at dispatch time", idToChange, foldedIds);
			return;
		}

		// coordinator review item 2 (unchanged reasoning, now against the outbox row instead of a MdmFactory
		// row): the outbox row's own transaction is independent of the master save's own transaction, so it
		// cannot be trusted blindly. RETIRE is the one exception: CLAUDE.md sec 8 says the row still
		// physically exists after a merge/unmerge, so plain existence cannot distinguish it from a live record.
		final boolean isRetire = EventCoalescer.RETIRE.equals(coalesced.effectiveChange);
		// "retire notices from the merge log" (SPEC-retire.md): retireMode=NONE means no retire notice is ever
		// sent for this target - distribution.MergeLogWatch itself already never emits one for a NONE target,
		// but a RETIRE could in principle still reach the outbox some other way (a row written by a trigger installed
		// by the pre-F3 generator, which compared Linked_Field_State - docs/DISTRIBUTION.md sec 5.3), so this is checked
		// here too, not only upstream.
		final String retireMode = pTarget.getString(FactoryPaths.DistributionTarget.RETIRE_MODE);
		if (isRetire && FactoryPaths.DistributionTarget.RETIRE_MODE_NONE.equals(retireMode))
		{
			DistributionDispatcher.finishSkipped(pSession, pFactory, pMasterHome, pOutboxTable, pTarget, pTargetId, pProjectId, tablePath, recordKey,
				coalesced.effectiveChange, survivorKey, coalesced.survivingId, "retireMode is NONE for this target", idToChange, foldedIds);
			return;
		}
		final boolean isDeleteChange = EventCoalescer.DELETE.equals(coalesced.effectiveChange);
		if (!isRetire)
		{
			final boolean exists = DistributionDispatcher.masterRecordExists(pMasterDataset, tablePath, recordKey);
			if (isDeleteChange == exists)
			{
				final String reason = isDeleteChange ? "record still exists at dispatch time - the delete never committed, or it was recreated"
					: "record no longer exists at dispatch time - the save never committed, or it was since deleted";
				DistributionDispatcher.finishSkipped(pSession, pFactory, pMasterHome, pOutboxTable, pTarget, pTargetId, pProjectId, tablePath, recordKey,
					coalesced.effectiveChange, survivorKey, coalesced.survivingId, reason, idToChange, foldedIds);
				return;
			}
			if (!isDeleteChange)
			{
				// lot S2 (2026-10-18): a CREATE/MODIFY of a record Match and Merge now holds as MERGED or DELETED is not a live record - sending it would hand the
				// target the very row its retire notice withdraws (the sync of one record has no Match and Merge filter, measured in Connectors)
				// lot F3 (2026-10-19): this is THE live/not-live decision - the trigger only enqueues {table, key, change} and reads no Match and Merge field, so a
				// record that was merged, or deleted by an unmerge, between the save and the dispatch is dropped here (SKIPPED + the reason below, history row kept).
				// PENDING_APPROVAL (3), GOLDEN (1), a record without metadata and a table without the add-on are live (GoldenOnlySends.isLive).
				final String mameState = DistributionDispatcher.mameStateOf(pMasterDataset, tablePath, recordKey);
				if (mameState != null && !GoldenOnlySends.isLive(mameState))
				{
					DistributionDispatcher.finishSkipped(pSession, pFactory, pMasterHome, pOutboxTable, pTarget, pTargetId, pProjectId, tablePath, recordKey,
						coalesced.effectiveChange, survivorKey, coalesced.survivingId, "record is " + mameState + " in Match and Merge at dispatch time - not a live record, a retire notice covers it",
						idToChange, foldedIds);
					return;
				}
			}
		}
		// a retire notice and a delete notice are documents the Factory builds (the record is no longer one the mapping can read): see distribution.NoticeDocument
		final boolean isNotice = isRetire || isDeleteChange;

		final int attemptsSoFar = DistributionDispatcher.attemptsOf(pOutboxTable, coalesced.survivingId);
		final Map<String, String> mappings = TargetMappingList.parse(pTarget.getString(FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS));
		final String mappingId = mappings.get(tablePath);
		if (mappingId == null)
		{
			DistributionDispatcher.finishOrRetryFailed(pSession, pFactory, pMasterHome, pOutboxTable, pTarget, pTargetId, pProjectId, tablePath, recordKey,
				coalesced.effectiveChange, survivorKey, coalesced.survivingId, attemptsSoFar, "No configured mapping for " + tablePath + " on target " + pTargetId, idToChange,
				foldedIds);
			return;
		}
		final String connector = pTarget.getString(FactoryPaths.DistributionTarget.CONNECTOR);
		final String operation = pTarget.getString(FactoryPaths.DistributionTarget.OPERATION);
		// "retire notices from the merge log" (SPEC-retire.md): TOMBSTONE is Kafka-only and not yet supported
		// by the Kafka connector (the Connectors lot) - a target that reaches dispatch with TOMBSTONE anyway
		// (e.g. its mode was changed to TOMBSTONE after DistributionConfigurator.configure already refused it
		// once) still gets an ordinary NOTICE, with the event's own message saying so, rather than the retire
		// being silently dropped.
		final String retireFallbackNote = isRetire && FactoryPaths.DistributionTarget.RETIRE_MODE_TOMBSTONE.equals(retireMode)
			? "retireMode is TOMBSTONE, which the Kafka connector does not support yet - sent as NOTICE instead" : null;
		final String destination = TargetMappingList.parse(pTarget.getString(FactoryPaths.DistributionTarget.TABLE_DESTINATIONS)).get(tablePath);
		final McpClient.ToolResult result;
		try
		{
			final McpClient client = McpServers.connectors(pSession, pFactory);
			result = isNotice
				? DistributionDispatcher.sendNotice(client, connector, operation, isRetire, recordKey, survivorKey, retireReason, mergeId, destination)
				: DistributionDispatcher.sendRecord(client, mappingId, recordKey);
		}
		catch (final Throwable ex) // OperationException / McpException, but also whatever else a send can throw (a RuntimeException, a LinkageError): left to the pass' own catch it would not count an attempt and be retried every pass for ever
		{
			DistributionDispatcher.finishOrRetryFailed(pSession, pFactory, pMasterHome, pOutboxTable, pTarget, pTargetId, pProjectId, tablePath, recordKey,
				coalesced.effectiveChange, survivorKey, coalesced.survivingId, attemptsSoFar, DistributionDispatcher.failureText(ex), idToChange, foldedIds);
			return;
		}
		// distribution round 4 fix (2026-09-26, task brief item 1, "false success"): connectors_sync (the
		// ordinary, non-retire path) answers WITHOUT result.error even when the send failed at the target -
		// SyncOutcome is the real verdict there. connectors_call (the retire path) is unaffected: a broker/
		// target failure DOES throw inside it, so result.error already means what it says - see
		// DistributionDispatcher#sendNotice and SyncOutcome's own Javadoc.
		final boolean failed;
		final String failureMessage;
		if (isNotice)
		{
			failed = result.error;
			failureMessage = failed ? DistributionConfigurator.errorText(result) : null;
		}
		else
		{
			final SyncOutcome.Result outcome = SyncOutcome.read(result.error, DistributionConfigurator.errorText(result), result.json);
			failed = !outcome.ok;
			failureMessage = failed ? (outcome.runId == null ? outcome.message : outcome.message + " (connectors_sync runId " + outcome.runId + ")") : null;
		}
		if (failed)
		{
			DistributionDispatcher.finishOrRetryFailed(pSession, pFactory, pMasterHome, pOutboxTable, pTarget, pTargetId, pProjectId, tablePath, recordKey,
				coalesced.effectiveChange, survivorKey, coalesced.survivingId, attemptsSoFar, failureMessage, idToChange, foldedIds);
		}
		else
		{
			DistributionDispatcher.finishSent(pSession, pFactory, pMasterHome, pOutboxTable, pTarget, pTargetId, pProjectId, tablePath, recordKey, coalesced.effectiveChange,
				survivorKey, coalesced.survivingId, idToChange, foldedIds, retireFallbackNote);
		}
	}

	/** What a send that threw is recorded as: the exception's message, or its class when it has none (an Error often has no message). */
	static String failureText(final Throwable pFailure)
	{
		final String message = pFailure.getMessage();
		return message == null || message.trim().isEmpty() ? pFailure.getClass().getName() : message;
	}

	/** An ordinary send: Connectors reads the record's own current state from the outbound mapping. */
	private static McpClient.ToolResult sendRecord(final McpClient pClient, final String pMappingId, final String pRecordKey) throws McpException
	{
		final ObjectNode args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		args.put("mapping", pMappingId);
		args.put("key", pRecordKey);
		args.put("dryRun", false);
		return pClient.callTool("connectors_sync", args);
	}

	/**
	 * A retire notice bypasses the outbound mapping entirely (its own {@code mergedRecords=exclude} input
	 * would otherwise hide the very record being retired) and calls the connector's write operation directly
	 * with a small hand-built document, {@code connectors_call(..., allowWrite=true)}.
	 * <p>
	 * <b>"retire notices from the merge log" (SPEC-retire.md)</b>: the document now also carries {@code reason}
	 * ({@code MERGE}/{@code UNMERGE}, from {@code distribution.MergeLogWatch} via the outbox row - null/absent
	 * for a RETIRE written by a trigger of the pre-F3 generator, whose {@code Linked_Field_State} comparison no longer exists),
	 * {@code mergeId} (the add-on's {@code MergingProcess.id}, when known) and {@code at} (this call's own
	 * UTC instant, ISO-8601 - the outbox row's {@code createdAt} is when the merge/unmerge itself happened,
	 * not when it was actually sent, so this is deliberately a fresh timestamp). It also now passes {@code
	 * inputs} ({@code topic}/{@code key}) exactly the way {@link DistributionConfigurator#upsertMapping}
	 * configured the table's own OUTBOUND mapping to read them - before this fix the retire call passed no
	 * {@code inputs} at all, so on a per-table-topic (Kafka) target every retire notice would have published on
	 * the connector's default topic instead of the table's own one (SPEC-retire.md).
	 * <p>
	 * <b>Coordinator review fix (2026-09-28)</b>: {@code inputs.key} here is the record's own key VALUE
	 * ({@code pRecordKey}), never {@link DistributionConfigurator#DEFAULT_KEY_TEMPLATE}. That constant is a
	 * {@code ${id}} TEMPLATE, resolved per record by Connectors against a record it reads through a mapping -
	 * a direct {@code connectors_call} has no record for Connectors to resolve anything against, so passing the
	 * template literally here would send the four literal characters {@code ${id}} as the Kafka message key on
	 * every retire notice, not the record's id. This mirrors the SAME bug just fixed on the ordinary mapping
	 * path (measured live on RC2: every message, retire or not, carried the literal key {@code id}) - see
	 * {@code DEFAULT_KEY_TEMPLATE}'s own Javadoc for the full measurement.
	 * <p>
	 * <b>Not verified live</b>: the document shape is this class' own invention - no target connector's actual expected
	 * "retire" document was available to check against.
	 * <p>
	 * <b>Lot S2 (2026-10-18)</b>: this method sends the RETIRE and, new, the DELETE notice (a deleted record cannot be read
	 * by the mapping, and {@code connectors_sync} of its key answers "No record for key"); both documents come from
	 * {@code distribution.NoticeDocument} and carry {@code _change}.
	 */
	private static McpClient.ToolResult sendNotice(final McpClient pClient, final String pConnector, final String pOperation, final boolean pRetire, final String pRecordKey,
		final String pSurvivorKey, final String pReason, final String pMergeId, final String pDestination) throws McpException
	{
		// lot S2: the document is built by distribution.NoticeDocument - ONE convention for the messages the Factory writes itself (RETIRE, DELETE)
		final String at = java.time.Instant.now().toString();
		final Map<String, Object> fields = pRetire ? NoticeDocument.retire(pRecordKey, pSurvivorKey, pReason, pMergeId, at) : NoticeDocument.delete(pRecordKey, at);
		final ObjectNode document = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		for (final Map.Entry<String, Object> field : fields.entrySet())
		{
			if (field.getValue() instanceof Boolean)
			{
				document.put(field.getKey(), ((Boolean) field.getValue()).booleanValue());
			}
			else
			{
				document.put(field.getKey(), String.valueOf(field.getValue()));
			}
		}
		final ObjectNode args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		args.put("connector", pConnector);
		args.put("operation", pOperation);
		args.put("allowWrite", true);
		args.set("document", document);
		if (pDestination != null && !pDestination.trim().isEmpty())
		{
			final ObjectNode inputs = args.putObject("inputs");
			inputs.put("topic", pDestination.trim());
			// coordinator review fix (2026-09-28): the record's own key VALUE, never DEFAULT_KEY_TEMPLATE - see this method's own Javadoc.
			inputs.put("key", pRecordKey);
		}
		return pClient.callTool("connectors_call", args);
	}

	/**
	 * Lot S2: the Match and Merge state of a master record ({@link RetireRules} vocabulary) read from its {@code Linked_Field_State}; null when the table has
	 * no such field (no matching on it), the record is gone, or the value is empty. Never throws.
	 */
	private static String mameStateOf(final Adaptation pMasterDataset, final String pTablePath, final String pRecordKey)
	{
		try
		{
			final AdaptationTable table = pMasterDataset.getTable(Path.parse(pTablePath));
			if (table == null || table.getTableOccurrenceRootNode().getNode(Path.parse("./Linked_Field_State")) == null)
			{
				return null;
			}
			final Adaptation record = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRecordKey));
			if (record == null)
			{
				return null;
			}
			final Object raw = record.get(Path.parse("./Linked_Field_State"));
			return raw instanceof Number ? MergeLogWatcher.stateName(raw) : null;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** Whether the master record still exists, read directly against the ALREADY-RESOLVED master dataset (no MCP call, no re-resolution of the project - the caller resolved it once per target). Safe {@code PrimaryKey.parseString} because the key was formatted by EBX's own {@code core.complex.formatPrimaryKey} in the trigger, never hand-built. */
	private static boolean masterRecordExists(final Adaptation pMasterDataset, final String pTablePath, final String pRecordKey)
	{
		final AdaptationTable table = pMasterDataset.getTable(Path.parse(pTablePath));
		if (table == null)
		{
			return false;
		}
		try
		{
			return table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRecordKey)) != null;
		}
		catch (final RuntimeException ex)
		{
			// a malformed/legacy key (e.g. written before this table's key shape changed): treated as "not found"
			return false;
		}
	}

	private static int attemptsOf(final AdaptationTable pOutboxTable, final String pOutboxRowId)
	{
		final Adaptation row = pOutboxTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pOutboxRowId));
		if (row == null)
		{
			return 0;
		}
		final Object attempts = row.get(FactoryPaths.DistributionOutbox.ATTEMPTS);
		return attempts instanceof Number ? ((Number) attempts).intValue() : 0;
	}

	/** Deletes outbox rows superseded within one drain pass (coalescing) - internal bookkeeping, never a real send attempt, so no history row. */
	/**
	 * Volume-operations lot 8a (2026-09-28, task 4): appends to the current pass' {@link #CURRENT_WRITES}
	 * accumulator instead of opening its own {@link Procedure} - {@link #flush} does the actual delete, once,
	 * for the whole pass. Falls back to the OLD immediate-{@link Procedure} behaviour when called outside a
	 * pass (defensive - {@link #drainTarget} always sets one, so this is never expected to trigger live, but a
	 * future caller of this method must not silently lose a delete).
	 */
	private static void deleteOutboxRows(final Session pSession, final AdaptationHome pMasterHome, final AdaptationTable pOutboxTable, final List<String> pIds)
	{
		if (pIds.isEmpty())
		{
			return;
		}
		final DrainWrites writes = DistributionDispatcher.CURRENT_WRITES.get();
		if (writes != null)
		{
			writes.deletes.addAll(pIds);
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pMasterHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final String id : pIds)
				{
					final Adaptation row = pOutboxTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(id));
					if (row != null)
					{
						pContext.doDelete(row.getAdaptationName(), false);
					}
				}
			}
		});
		if (result.hasFailed() && DistributionDispatcher.log != null)
		{
			DistributionDispatcher.log.error("[mdm-factory] distribution dispatcher: could not delete superseded outbox row(s): " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
	}

	/**
	 * Terminal SENT: history row in MdmFactory + delete the outbox row, then close every other superseded id -
	 * both the SAME-PASS ones {@code coalesce()} folded into this send ({@code pFoldedIds}, round 6 fix) and
	 * the CROSS-PASS ones a later {@link EventCoalescer#idsSupersededBySend} query finds still sitting in retry
	 * backoff (round 5, {@code pTablePath}/{@code pRecordKey} re-queried since those rows were excluded from
	 * this pass' own group by {@code drainTarget}'s {@code nextAttemptAt} filter - live incident: outbox row 4,
	 * attempt 7, would otherwise wake up later and re-send the exact state this send just delivered). Both go
	 * through the SAME {@link #closeSupersededIds} helper (task brief: "prefer one code path for both cases").
	 */
	private static void finishSent(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final AdaptationTable pOutboxTable,
		final Adaptation pTarget, final String pTargetId, final String pProjectId, final String pTablePath, final String pRecordKey, final String pChange,
		final String pSurvivorKey, final String pOutboxRowId, final Map<String, String> pIdToChange, final List<String> pFoldedIds, final String pNote)
	{
		DistributionDispatcher.writeHistory(pSession, pFactory, pTargetId, pProjectId, pTablePath, pRecordKey, pChange, pSurvivorKey,
			FactoryPaths.DistributionEvent.STATUS_SENT, DistributionDispatcher.attemptsOf(pOutboxTable, pOutboxRowId) + 1, pNote, true, new Date(),
			DistributionDispatcher.noteReason(), DistributionDispatcher.noteMergeId());
		DistributionDispatcher.closeSupersededIds(pSession, pFactory, pMasterHome, pOutboxTable, pTargetId, pProjectId, pTablePath, pRecordKey, pIdToChange,
			pOutboxRowId, EventCoalescer.OUTCOME_SENT, pFoldedIds);
		if (EventCoalescer.CREATE.equals(pChange) || EventCoalescer.MODIFY.equals(pChange))
		{
			DistributionDispatcher.closeCrossPassSuperseded(pSession, pFactory, pMasterHome, pOutboxTable, pTargetId, pProjectId, pTablePath, pRecordKey, pChange, pOutboxRowId);
		}
		DistributionDispatcher.deleteOutboxRows(pSession, pMasterHome, pOutboxTable, java.util.Collections.singletonList(pOutboxRowId));
	}

	/**
	 * Round 5 (2026-09-26, task brief item 2): reads every OTHER still-PENDING outbox row of the same
	 * {@code (target, tablePath, recordKey)} - deliberately with NO {@code nextAttemptAt} filter, unlike
	 * {@code drainTarget}'s own query, since a row in retry backoff is exactly what this must still catch -
	 * works out which ones {@link EventCoalescer#idsSupersededBySend} names as stale, then hands them to
	 * {@link #closeSupersededIds} (round 6: the single shared closer, instead of writing history/deleting
	 * inline here as before).
	 * <p>
	 * <b>Volume-operations lot 8a (2026-09-28, task 4, item 3 - partially addressed, flagged for the
	 * coordinator):</b> the WRITES this method triggers (through {@link #closeSupersededIds} ->
	 * {@link #writeFoldedHistory}/{@link #deleteOutboxRows}) are now batched into the whole pass' single
	 * flush ({@link #CURRENT_WRITES}) like every other write in this class - so this no longer opens its own
	 * history/delete transaction. Its own READ (the query below) is deliberately UNCHANGED - still one
	 * predicate query per sent CREATE/MODIFY group, not merged into a single pass-wide query across every sent
	 * group's {@code (tablePath, recordKey)} - a correct, bounded OR-predicate covering an arbitrary number of
	 * distinct keys (up to {@link #DRAIN_BATCH_CAP} groups) felt like real, untested-live risk for one query
	 * count reduction, once the two actual WRITE transactions per record (the brief's items 2 costs) were
	 * already removed. Not implemented this lot; a genuine "one query for the whole pass" would read every
	 * PENDING row of the target ONCE (reusing {@code drainTarget}'s own initial scan, WITHOUT its {@code
	 * nextAttemptAt} filter) and bucket it by key instead of re-querying per sent group.
	 */
	private static void closeCrossPassSuperseded(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final AdaptationTable pOutboxTable,
		final String pTargetId, final String pProjectId, final String pTablePath, final String pRecordKey, final String pSentChange, final String pSentOutboxRowId)
	{
		final Map<String, EventCoalescer.Event> others = new LinkedHashMap<String, EventCoalescer.Event>();
		final RequestResult rows = pOutboxTable.createRequestResult(
			"./status=" + CatalogReader.quote(FactoryPaths.DistributionOutbox.STATUS_PENDING) + " and ./target=" + CatalogReader.quote(pTargetId) + " and ./tablePath="
				+ CatalogReader.quote(pTablePath) + " and ./recordKey=" + CatalogReader.quote(pRecordKey));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				if (id.equals(pSentOutboxRowId))
				{
					continue;
				}
				others.put(id, new EventCoalescer.Event(id, row.getString(FactoryPaths.DistributionOutbox.CHANGE), DistributionDispatcher.sequenceOf(id)));
			}
		}
		finally
		{
			rows.close();
		}
		if (others.isEmpty())
		{
			return;
		}
		final List<String> superseded = EventCoalescer.idsSupersededBySend(pSentChange, DistributionDispatcher.sequenceOf(pSentOutboxRowId),
			new ArrayList<EventCoalescer.Event>(others.values()));
		final Map<String, String> idToChange = new LinkedHashMap<String, String>();
		for (final Map.Entry<String, EventCoalescer.Event> entry : others.entrySet())
		{
			idToChange.put(entry.getKey(), entry.getValue().change);
		}
		DistributionDispatcher.closeSupersededIds(pSession, pFactory, pMasterHome, pOutboxTable, pTargetId, pProjectId, pTablePath, pRecordKey, idToChange, pSentOutboxRowId,
			EventCoalescer.OUTCOME_SENT, superseded);
	}

	/**
	 * Round 6 (2026-09-26, task brief): the ONE place - used by both the same-pass {@code coalesce()} netting
	 * ({@code finishSent}/{@code finishSkipped}/{@code finishOrRetryFailed}, each passing their own
	 * {@code pFoldedIds}) and the cross-pass {@link EventCoalescer#idsSupersededBySend} check
	 * ({@code closeCrossPassSuperseded}) - that turns a set of ids folded into {@code pRepresentativeId} into
	 * one SKIPPED history row each (never silently deleted with no trace - the round-6 bug this method fixes),
	 * then deletes their outbox rows. {@link EventCoalescer#supersededMessage} decides the exact wording, and
	 * returns {@code null} when {@code pOutcome} is {@link EventCoalescer#OUTCOME_FAILED_RETRY}: the
	 * representative itself is not yet terminal, so this method does nothing at all in that case, leaving every
	 * folded id exactly as it was - still PENDING, still there to be re-coalesced (or re-queried, for the
	 * cross-pass case) on the next drain pass once the representative's own fate is actually decided.
	 * <p>
	 * Each folded id's ORIGINAL {@code createdAt} is read and preserved onto its history row (never "now" - a
	 * folded id was never itself attempted, so its history entry should say when the underlying change
	 * actually happened, not when it was cleaned up).
	 */
	private static void closeSupersededIds(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final AdaptationTable pOutboxTable,
		final String pTargetId, final String pProjectId, final String pTablePath, final String pRecordKey, final Map<String, String> pIdToChange,
		final String pRepresentativeId, final String pOutcome, final List<String> pIds)
	{
		if (pIds == null || pIds.isEmpty())
		{
			return;
		}
		final String message = EventCoalescer.supersededMessage(pRepresentativeId, pOutcome);
		if (message == null)
		{
			// the representative is still retrying, not terminal yet - leave every folded id PENDING (see this
			// method's own Javadoc and EventCoalescer#supersededMessage's).
			return;
		}
		DistributionDispatcher.writeFoldedHistory(pSession, pFactory, pTargetId, pProjectId, pTablePath, pRecordKey, pIdToChange, pOutboxTable, message, pIds);
		DistributionDispatcher.deleteOutboxRows(pSession, pMasterHome, pOutboxTable, pIds);
	}

	/** Writes one SKIPPED history row per id in {@code pIds}, each with its OWN {@code change} (from {@code pIdToChange}) and its OWN original {@code createdAt} (read from the still-live outbox row) - shared by the {@link EventCoalescer#NETTED_TO_NONE_MESSAGE} case and {@link #closeSupersededIds}. Never deletes anything itself - callers delete only after this returns. */
	private static void writeFoldedHistory(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pProjectId, final String pTablePath,
		final String pRecordKey, final Map<String, String> pIdToChange, final AdaptationTable pOutboxTable, final String pMessage, final List<String> pIds)
	{
		for (final String id : pIds)
		{
			final String change = pIdToChange.get(id);
			final Date createdAt = DistributionDispatcher.createdAtOf(pOutboxTable, id);
			DistributionDispatcher.writeHistory(pSession, pFactory, pTargetId, pProjectId, pTablePath, pRecordKey, change, null, FactoryPaths.DistributionEvent.STATUS_SKIPPED,
				DistributionDispatcher.attemptsOf(pOutboxTable, id), pMessage, false, createdAt);
		}
	}

	/** Terminal SKIPPED (arming gate / stale record): history row in MdmFactory + delete the outbox row, then close this pass' folded ids (round 6) with the same OUTCOME_SKIPPED outcome. */
	private static void finishSkipped(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final AdaptationTable pOutboxTable,
		final Adaptation pTarget, final String pTargetId, final String pProjectId, final String pTablePath, final String pRecordKey, final String pChange,
		final String pSurvivorKey, final String pOutboxRowId, final String pReason, final Map<String, String> pIdToChange, final List<String> pFoldedIds)
	{
		DistributionDispatcher.writeHistory(pSession, pFactory, pTargetId, pProjectId, pTablePath, pRecordKey, pChange, pSurvivorKey,
			FactoryPaths.DistributionEvent.STATUS_SKIPPED, DistributionDispatcher.attemptsOf(pOutboxTable, pOutboxRowId), pReason, false, new Date(),
			DistributionDispatcher.noteReason(), DistributionDispatcher.noteMergeId());
		DistributionDispatcher.closeSupersededIds(pSession, pFactory, pMasterHome, pOutboxTable, pTargetId, pProjectId, pTablePath, pRecordKey, pIdToChange, pOutboxRowId,
			EventCoalescer.OUTCOME_SKIPPED, pFoldedIds);
		DistributionDispatcher.deleteOutboxRows(pSession, pMasterHome, pOutboxTable, java.util.Collections.singletonList(pOutboxRowId));
	}

	/**
	 * A failed send: under the retry budget the OUTBOX row is updated in place (not terminal yet) and this
	 * pass' folded ids (round 6) are left untouched too - {@link #closeSupersededIds} no-ops on
	 * {@link EventCoalescer#OUTCOME_FAILED_RETRY}. Past {@link RetryBackoff#MAX_ATTEMPTS} it becomes a
	 * terminal FAILED history row, the outbox row is deleted, and the folded ids are closed with
	 * {@link EventCoalescer#OUTCOME_FAILED_EXHAUSTED} (message "superseded by event &lt;id&gt; (failed)" - they
	 * share the representative's own exhausted fate rather than being left to revive on their own).
	 */
	private static void finishOrRetryFailed(final Session pSession, final Adaptation pFactory, final AdaptationHome pMasterHome, final AdaptationTable pOutboxTable,
		final Adaptation pTarget, final String pTargetId, final String pProjectId, final String pTablePath, final String pRecordKey, final String pChange,
		final String pSurvivorKey, final String pOutboxRowId, final int pAttemptsSoFar, final String pMessage, final Map<String, String> pIdToChange,
		final List<String> pFoldedIds)
	{
		final int attemptsAfter = pAttemptsSoFar + 1;
		final boolean exhausted = RetryBackoff.exhausted(attemptsAfter);
		if (exhausted)
		{
			final String message = pMessage + " (retry budget exhausted after " + attemptsAfter + " attempt(s) - replay manually from the Cockpit)";
			DistributionDispatcher.writeHistory(pSession, pFactory, pTargetId, pProjectId, pTablePath, pRecordKey, pChange, pSurvivorKey,
				FactoryPaths.DistributionEvent.STATUS_FAILED, attemptsAfter, message, false, new Date(), DistributionDispatcher.noteReason(), DistributionDispatcher.noteMergeId());
			DistributionDispatcher.closeSupersededIds(pSession, pFactory, pMasterHome, pOutboxTable, pTargetId, pProjectId, pTablePath, pRecordKey, pIdToChange, pOutboxRowId,
				EventCoalescer.OUTCOME_FAILED_EXHAUSTED, pFoldedIds);
			DistributionDispatcher.deleteOutboxRows(pSession, pMasterHome, pOutboxTable, java.util.Collections.singletonList(pOutboxRowId));
		}
		else
		{
			final Date nextAttemptAt = new Date(System.currentTimeMillis() + RetryBackoff.delaySeconds(attemptsAfter) * 1000L);
			DistributionDispatcher.updateOutboxRetry(pSession, pMasterHome, pOutboxTable, pOutboxRowId, attemptsAfter, nextAttemptAt, pMessage);
			DistributionDispatcher.closeSupersededIds(pSession, pFactory, pMasterHome, pOutboxTable, pTargetId, pProjectId, pTablePath, pRecordKey, pIdToChange, pOutboxRowId,
				EventCoalescer.OUTCOME_FAILED_RETRY, pFoldedIds); // no-op: leaves pFoldedIds PENDING until the representative's own fate is decided
		}
		if (DistributionDispatcher.log != null)
		{
			DistributionDispatcher.log.error("[mdm-factory] distribution outbox row " + pOutboxRowId + " of target " + pTargetId + " failed (attempt " + attemptsAfter
				+ (exhausted ? ", exhausted" : ", will retry") + "): " + pMessage);
		}
	}

	/** Not terminal: the outbox row stays PENDING, its attempts/nextAttemptAt/message updated in place. */
	private static void updateOutboxRetry(final Session pSession, final AdaptationHome pMasterHome, final AdaptationTable pOutboxTable, final String pOutboxRowId,
		final int pAttemptsAfter, final Date pNextAttemptAt, final String pMessage)
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pMasterHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = pOutboxTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pOutboxRowId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(FactoryPaths.DistributionOutbox.STATUS_PENDING, FactoryPaths.DistributionOutbox.STATUS);
				value.setValue(Integer.valueOf(pAttemptsAfter), FactoryPaths.DistributionOutbox.ATTEMPTS);
				value.setValue(pNextAttemptAt, FactoryPaths.DistributionOutbox.NEXT_ATTEMPT_AT);
				value.setValue(com.on.ebx.factory.landing.TextLimits.truncate(pMessage, com.on.ebx.factory.landing.TextLimits.MESSAGE_MAX_LENGTH),
					FactoryPaths.DistributionOutbox.MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed() && DistributionDispatcher.log != null)
		{
			DistributionDispatcher.log
				.error("[mdm-factory] distribution dispatcher: could not update outbox row " + pOutboxRowId + ": " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
	}

	/**
	 * Writes one permanent history row into MdmFactory's own {@code DistributionEvent} table - what the
	 * wizard/Cockpit events list and the REST {@code /distribution/events} route read (unchanged). Only called
	 * on a TERMINAL outcome (SENT, SKIPPED, or FAILED past the retry budget) for the row it is written for.
	 * <p>
	 * Round 6 (2026-09-26, task brief): {@code pCreatedAt} is the row's own {@code createdAt} to record on the
	 * history entry - for the event actually attempted (sent/skipped/exhausted) this is simply {@code new
	 * Date()} (when the outcome was decided, same as before this round), but for a FOLDED/superseded id (one
	 * that was never itself attempted - {@link DistributionDispatcher#closeSupersededIds}) it is that id's own
	 * ORIGINAL outbox {@code createdAt}, read before the row is deleted, so the audit trail still shows when
	 * that particular change actually happened rather than only when it was cleaned up.
	 */
	private static void writeHistory(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pProjectId, final String pTablePath,
		final String pRecordKey, final String pChange, final String pSurvivorKey, final String pStatus, final int pAttempts, final String pMessage,
		final boolean pSent, final Date pCreatedAt)
	{
		DistributionDispatcher.writeHistory(pSession, pFactory, pTargetId, pProjectId, pTablePath, pRecordKey, pChange, pSurvivorKey, pStatus, pAttempts, pMessage, pSent, pCreatedAt,
			null, null);
	}

	/** Lot S2: the history row of a send that carries the outbox row's own reason and merge id - without them the history could not tell a reinstating CREATE from an ordinary one (the ledger of {@link MatchingStateSweeper} and the wizard's history column read them). */
	private static void writeHistory(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pProjectId, final String pTablePath,
		final String pRecordKey, final String pChange, final String pSurvivorKey, final String pStatus, final int pAttempts, final String pMessage,
		final boolean pSent, final Date pCreatedAt, final String pReason, final String pMergeId)
	{
		// volume-operations lot 8a (2026-09-28, task 4): appends to the current pass' accumulator instead of
		// opening its own Procedure - see deleteOutboxRows' own comment, same reasoning/fallback.
		final DrainWrites writes = DistributionDispatcher.CURRENT_WRITES.get();
		if (writes != null)
		{
			writes.history.add(new HistoryRow(pTargetId, pProjectId, pTablePath, pRecordKey, pChange, pSurvivorKey, pStatus, pAttempts, pMessage, pSent, pCreatedAt, pReason, pMergeId));
			return;
		}
		final AdaptationTable events = pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(events);
				value.setValue(pProjectId, FactoryPaths.DistributionEvent.PROJECT);
				value.setValue(pTargetId, FactoryPaths.DistributionEvent.TARGET);
				value.setValue(pTablePath, FactoryPaths.DistributionEvent.TABLE_PATH);
				value.setValue(pRecordKey, FactoryPaths.DistributionEvent.RECORD_KEY);
				value.setValue(pChange, FactoryPaths.DistributionEvent.CHANGE);
				value.setValue(pSurvivorKey, FactoryPaths.DistributionEvent.SURVIVOR_KEY);
				value.setValue(pStatus, FactoryPaths.DistributionEvent.STATUS);
				value.setValue(Integer.valueOf(pAttempts), FactoryPaths.DistributionEvent.ATTEMPTS);
				value.setValue(pCreatedAt, FactoryPaths.DistributionEvent.CREATED_AT);
				if (pReason != null && !pReason.trim().isEmpty())
				{
					value.setValue(pReason, FactoryPaths.DistributionEvent.REASON);
				}
				if (pMergeId != null && !pMergeId.trim().isEmpty())
				{
					value.setValue(pMergeId, FactoryPaths.DistributionEvent.MERGE_ID);
				}
				if (pSent)
				{
					value.setValue(new Date(), FactoryPaths.DistributionEvent.SENT_AT);
				}
				if (pMessage != null)
				{
					value.setValue(com.on.ebx.factory.landing.TextLimits.truncate(pMessage, com.on.ebx.factory.landing.TextLimits.MESSAGE_MAX_LENGTH),
						FactoryPaths.DistributionEvent.MESSAGE);
				}
				pContext.doCreateOccurrence(value, events);
			}
		});
		if (result.hasFailed() && DistributionDispatcher.log != null)
		{
			DistributionDispatcher.log.error(
				"[mdm-factory] distribution dispatcher: could not write history event for target " + pTargetId + ": " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
	}

	/** The outbox row's own {@code createdAt}, read before it is deleted - round 6 fix, see {@link #writeHistory}'s own Javadoc. Falls back to now if the row is already gone or the field somehow holds no value (defensive; every caller reads this before deleting the row). */
	private static Date createdAtOf(final AdaptationTable pOutboxTable, final String pOutboxRowId)
	{
		final Adaptation row = pOutboxTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pOutboxRowId));
		if (row == null)
		{
			return new Date();
		}
		final Object createdAt = row.get(FactoryPaths.DistributionOutbox.CREATED_AT);
		return createdAt instanceof Date ? (Date) createdAt : new Date();
	}
}
