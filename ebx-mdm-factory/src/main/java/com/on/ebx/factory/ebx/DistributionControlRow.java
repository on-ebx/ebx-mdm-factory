package com.on.ebx.factory.ebx;

import java.util.Date;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.ArmedRealtimeChange;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Volume-operations lot 8b (2026-09-28, task 1): maintains the per-project {@code DistributionOutbox.Control}
 * row - one row, keyed by {@code Project.code}, holding {@code armedRealtimeTargets}, the count of this
 * project's {@code DistributionTarget} rows with {@code mode=REALTIME and state=ARMED} - the single fact
 * {@code distribution.DistributionTriggerBody}'s generated trigger now reads by PRIMARY KEY, with no SQL query
 * at all, before ever running its old unconditional {@code select ... from factory."DistributionTarget" ...}
 * fallback (task brief's own evidence: 5 million such queries during a 5M-row master load, even while every
 * target of the project was {@code PAUSED} - lot 8a's {@link com.on.ebx.factory.distribution.ArmedTargetCache}
 * cannot help here, since a table trigger has no sanctioned way to call an arbitrary custom Java static method
 * from EBX Script - see that class' own Javadoc).
 * <p>
 * <b>The invariant that makes a missing/zero row safe to treat as "nothing armed" (never fail-open)</b>: a
 * target must never become {@code ARMED} (REALTIME) before this row already counts it. Two mechanisms
 * together maintain that:
 * <ol>
 * <li>Every write path that can change whether a target counts - the exact four sites lot 8a already wired to
 * {@code ArmedTargetCache#invalidate} ({@link DistributionPause#setState}, {@link DistributionRunner}'s
 * {@code writeTargetState}, {@link DistributionReader#setState}/{@code #upsertTarget}) - calls {@link
 * #beforeStateChange}/{@link #afterStateChange} around its own state-writing {@link Procedure}, gated by
 * {@link ArmedRealtimeChange#directionOf}. The two calls are asymmetric ON PURPOSE - see {@link
 * ArmedRealtimeChange}'s own Javadoc for exactly why: {@link #beforeStateChange} (an {@code ARM} transition)
 * writes the row's bumped count BEFORE the target's own {@code state} commits, so there is never a window
 * where the row under-counts a target that is, by the time a record trigger can observe it, genuinely armed;
 * {@link #afterStateChange} (a {@code DISARM} transition) recomputes AFTER the state commits, which is safe
 * the other way around (a stale, too-HIGH count only costs one harmless extra SQL query on the trigger side,
 * never a lost event). A write that does not cross the ARMED-REALTIME boundary in either direction (most
 * target edits) costs nothing - both methods are no-ops when {@link ArmedRealtimeChange#directionOf} answers
 * {@code NONE}.</li>
 * <li>{@link #recomputeOne} is also called once, unconditionally, by {@code DistributionTriggerInstaller#install}
 * right after it ensures the {@code DistributionOutbox} dataset exists ({@code DistributionOutboxInstaller.ensure})
 * - so the row is correct from the very first time a project's triggers are installed, even for a target that
 * was armed before the triggers (and therefore this row) ever existed. {@link #recomputeAll} does the same for
 * EVERY project with a master dataspace at module startup ({@code FactoryModuleRegistration}) - the self-heal
 * for a server upgraded to a build carrying this lot, where nothing yet wrote a row for an already-armed
 * target.</li>
 * </ol>
 * <p>
 * <b>Deliberately best-effort, like every write path it hooks into</b> ({@link DistributionPause}'s own
 * Javadoc: "never throws - a bookkeeping failure here must not block the reload itself"). A failure to write
 * this row only costs the trigger its short-circuit for that project (it falls back to the SQL query, exactly
 * as before this lot) - never a reason to fail the caller's own, more important, write.
 * <p>
 * <b>Not verified live</b>: no EBX/Connectors instance was reachable this session (same posture as every
 * other lot in {@code docs/VOLUME.md}). The generated trigger text was checked against {@code
 * get_syntax_guide}/{@code get_api_help} only - see {@code distribution.DistributionTriggerBody}'s own
 * Javadoc for exactly what was and was not validated.
 */
public final class DistributionControlRow
{
	private DistributionControlRow()
	{
	}

	/**
	 * Called by every write path that can change a target's {@code mode}/{@code state} - a no-op unless the
	 * transition {@link ArmedRealtimeChange#directionOf} classifies as {@link ArmedRealtimeChange.Direction#ARM}.
	 * Must be called BEFORE the caller's own {@link Procedure} commits the target's new state - see this
	 * class' own Javadoc for why that ordering (not the reverse) is the one that never lets a real event slip
	 * through the gap between the two, necessarily separate, non-transactional writes.
	 */
	public static void beforeStateChange(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pOldMode, final String pOldState,
		final String pNewMode, final String pNewState)
	{
		if (pProjectId == null || ArmedRealtimeChange.directionOf(pOldMode, pOldState, pNewMode, pNewState) != ArmedRealtimeChange.Direction.ARM)
		{
			return;
		}
		try
		{
			// this target's OLD (mode, state) does not count - ARM means exactly that - so the live query
			// below already excludes it; the new total the row must show, once this target becomes armed, is
			// simply one more than what is counted today.
			final int liveCount = DistributionControlRow.countArmedRealtimeTargets(pFactory, pProjectId);
			DistributionControlRow.writeCount(pSession, pFactory, pProjectId, liveCount + 1);
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not bump the distribution control row of project " + pProjectId + " before arming a target: " + ex);
		}
	}

	/**
	 * Called by the same write paths as {@link #beforeStateChange} - a no-op unless the transition is a {@link
	 * ArmedRealtimeChange.Direction#DISARM}. Called AFTER the caller's own {@link Procedure} has committed the
	 * target's new state (so the live recount below already excludes the now-disarmed target) - see this
	 * class' own Javadoc for why this direction is safe to refresh after the fact, unlike {@link
	 * #beforeStateChange}.
	 */
	public static void afterStateChange(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pOldMode, final String pOldState,
		final String pNewMode, final String pNewState)
	{
		if (pProjectId == null || ArmedRealtimeChange.directionOf(pOldMode, pOldState, pNewMode, pNewState) != ArmedRealtimeChange.Direction.DISARM)
		{
			return;
		}
		try
		{
			DistributionControlRow.writeCount(pSession, pFactory, pProjectId, DistributionControlRow.countArmedRealtimeTargets(pFactory, pProjectId));
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not refresh the distribution control row of project " + pProjectId + " after disarming a target: " + ex);
		}
	}

	/**
	 * Unconditional full recompute for one project - called once by {@code DistributionTriggerInstaller.install}
	 * right after the outbox dataset is ensured to exist, so the row is correct from the project's very first
	 * trigger install onward (see this class' own Javadoc, point 2). Safe to call at any other time too: it is
	 * exactly what {@link #afterStateChange} does, just unconditionally.
	 */
	public static void recomputeOne(final Session pSession, final Adaptation pFactory, final String pProjectId)
	{
		if (pProjectId == null)
		{
			return;
		}
		try
		{
			DistributionControlRow.writeCount(pSession, pFactory, pProjectId, DistributionControlRow.countArmedRealtimeTargets(pFactory, pProjectId));
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not recompute the distribution control row of project " + pProjectId + ": " + ex);
		}
	}

	/**
	 * Recomputes every project's row - called once at module startup ({@code FactoryModuleRegistration}). The
	 * self-heal for a server upgraded to a build carrying this lot (or one restarted after a gap where a write
	 * path failed to keep the row in step): every project's row is rebuilt from the live, authoritative
	 * {@code DistributionTarget} state, regardless of what it held before. One project's failure (a corrupt
	 * master dataspace reference, a transient repository error) is logged and skipped - never stops the rest.
	 */
	public static void recomputeAll(final Session pSession, final Adaptation pFactory)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult(null);
		try
		{
			for (Adaptation project = rows.nextAdaptation(); project != null; project = rows.nextAdaptation())
			{
				DistributionControlRow.recomputeOne(pSession, pFactory, project.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
	}

	/** The exact predicate the trigger's own SQL fallback and {@code DistributionArmingGate#mayQueueRealtimeEvent} already use - same shape as {@code DistributionPause#pause}'s own query. */
	private static int countArmedRealtimeTargets(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./mode="
			+ CatalogReader.quote(FactoryPaths.DistributionTarget.MODE_REALTIME) + " and ./state=" + CatalogReader.quote(FactoryPaths.DistributionTarget.STATE_ARMED));
		try
		{
			int count = 0;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				count++;
			}
			return count;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Resolves the project's master dataspace and, inside it, the {@code DistributionOutbox} dataset - a no-op
	 * (not an error) when either does not exist yet: a project with no master dataspace, or one whose triggers
	 * were never installed, has no row to write and none is needed (the trigger itself does not exist either).
	 * Upserts the one {@code Control} row of {@code pProjectId} with {@code pCount}/{@code now()}.
	 */
	private static void writeCount(final Session pSession, final Adaptation pFactory, final String pProjectId, final int pCount)
	{
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
		if (project == null)
		{
			return;
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		if (masterDataspaceKey == null || masterDataspaceKey.trim().isEmpty())
		{
			return;
		}
		final AdaptationHome masterHome = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		if (masterHome == null)
		{
			return;
		}
		final Adaptation outboxDataset = masterHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DISTRIBUTION_OUTBOX_DATASET));
		if (outboxDataset == null)
		{
			// triggers never installed yet for this project (DistributionOutboxInstaller.ensure creates this
			// dataset) - nothing to write, and nothing needs it: the trigger that would read this row does not
			// exist either. DistributionTriggerInstaller.install calls recomputeOne once it does.
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, masterHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final AdaptationTable controlTable = outboxDataset.getTable(FactoryPaths.DistributionOutbox.CONTROL);
				final Adaptation existing = controlTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
				final ValueContextForUpdate value;
				if (existing != null)
				{
					value = pContext.getContext(existing.getAdaptationName());
				}
				else
				{
					value = pContext.getContextForNewOccurrence(controlTable);
					value.setValue(pProjectId, FactoryPaths.DistributionOutbox.CONTROL_PROJECT);
				}
				value.setValue(Integer.valueOf(pCount), FactoryPaths.DistributionOutbox.CONTROL_ARMED_REALTIME_TARGETS);
				value.setValue(new Date(), FactoryPaths.DistributionOutbox.CONTROL_UPDATED_AT);
				if (existing != null)
				{
					pContext.doModifyContent(existing, value);
				}
				else
				{
					pContext.doCreateOccurrence(value, controlTable);
				}
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not write the distribution control row of project " + pProjectId + ": "
				+ result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
	}
}
