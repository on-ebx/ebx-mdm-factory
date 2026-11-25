package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The durable record of the pauses the factory itself applies for the length of a load: the REALTIME distribution targets set to {@code PAUSED}
 * ({@link DistributionPause}) and the Match and Merge matching of the master tables ({@link MatchingLoadPause}). A load undoes them from an in-memory list in a
 * {@code finally}, which a server stop between the pause and the resume never reaches: the target would stay PAUSED and the matching paused for ever, and
 * nothing could tell such an orphan from a pause an administrator set on purpose. One {@code LoadPause} row is written BEFORE each pause call and deleted once
 * the resume succeeded, so a row that survives a restart is exactly "the factory paused this and never lifted it" ({@link LoadPauseRecovery} lifts it at start).
 * <p>
 * A pause nobody asked the factory for (a person's) never gets a row,
 * so it can never be lifted by the recovery. Every row carries the token of the {@link Owner} (one load) that wrote it: a token no live load of this JVM holds
 * is a load that is gone. The cost is one small write per paused target or table per load, never per row.
 */
final class LoadPauseLedger
{
	/** Tokens of the loads running in this JVM right now. */
	private static final Set<String> LIVE = ConcurrentHashMap.newKeySet();

	private LoadPauseLedger()
	{
	}

	/** One load that may pause things: opened before its first pause, closed in its {@code finally}. */
	static final class Owner
	{
		final String token = UUID.randomUUID().toString();
		final String step;
		final String projectId;
		private final Map<String, String> rowIdByTarget = new HashMap<String, String>();

		private Owner(final String pStep, final String pProjectId)
		{
			this.step = pStep;
			this.projectId = pProjectId;
		}

		/** The load is over (whatever came of it): its remaining rows are orphans from now on. */
		void close()
		{
			LoadPauseLedger.LIVE.remove(this.token);
		}

		private static String slot(final String pKind, final String pTarget)
		{
			return pKind + "|" + pTarget;
		}
	}

	static Owner open(final String pStep, final String pProjectId)
	{
		final Owner owner = new Owner(pStep, pProjectId);
		LoadPauseLedger.LIVE.add(owner.token);
		return owner;
	}

	static boolean isLive(final String pToken)
	{
		return pToken != null && LoadPauseLedger.LIVE.contains(pToken);
	}

	/** A snapshot of the live tokens, for {@code LoadPauseRule#isOrphan}. */
	static Set<String> liveTokens()
	{
		return new java.util.HashSet<String>(LoadPauseLedger.LIVE);
	}

	/**
	 * Writes the row that says "the factory is about to pause {@code pTarget}". Call it BEFORE the pause call: a stop between the two then leaves a row for a
	 * thing that is not paused (the recovery sees NOT_PAUSED and only drops it), never a pause without a row.
	 *
	 * @throws OperationException the row could not be written: the caller must not pause (a pause nobody could ever recover is the defect this class exists for)
	 */
	static void claim(final Session pSession, final Adaptation pFactory, final Owner pOwner, final String pKind, final String pTarget) throws OperationException
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.LOAD_PAUSE);
		final String who = Actor.current(pSession).userId;
		final String[] holder = new String[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
				value.setValue(pOwner.projectId, FactoryPaths.LoadPause.PROJECT);
				value.setValue(pKind, FactoryPaths.LoadPause.KIND);
				value.setValue(pTarget, FactoryPaths.LoadPause.TARGET);
				value.setValue(pOwner.step, FactoryPaths.LoadPause.OWNER_STEP);
				value.setValue(pOwner.token, FactoryPaths.LoadPause.OWNER_TOKEN);
				if (who != null)
				{
					value.setValue(who, FactoryPaths.LoadPause.PAUSED_BY);
				}
				value.setValue(new Date(), FactoryPaths.LoadPause.PAUSED_AT);
				holder[0] = pContext.doCreateOccurrence(value, table).getOccurrencePrimaryKey().format();
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.recordPause", "Could not record the pause of " + pKind + " " + pTarget + ": " + result.getExceptionFullMessage(Locale.ENGLISH), pKind, pTarget);
		}
		pOwner.rowIdByTarget.put(Owner.slot(pKind, pTarget), holder[0]);
	}

	/** Deletes the row of a pause this owner lifted. False (and an ERROR in the log) when the delete failed: the row stays and is dropped by the recovery at the next start. */
	static boolean release(final Session pSession, final Adaptation pFactory, final Owner pOwner, final String pKind, final String pTarget)
	{
		final String rowId = pOwner.rowIdByTarget.remove(Owner.slot(pKind, pTarget));
		return rowId == null || LoadPauseLedger.delete(pSession, pFactory, rowId);
	}

	/** Deletes one row by key; true when it is gone (including "was already gone"). */
	static boolean delete(final Session pSession, final Adaptation pFactory, final String pRowId)
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.LOAD_PAUSE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRowId));
				if (row != null)
				{
					pContext.doDelete(row.getAdaptationName(), false);
				}
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not delete the load-pause row " + pRowId + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
			return false;
		}
		return true;
	}

	/** One row of the ledger, as the recovery reads it. */
	static final class Row
	{
		final String id;
		final String projectId;
		final String kind;
		final String target;
		final String ownerStep;
		final String ownerToken;
		final String pausedBy;

		Row(final String pId, final String pProjectId, final String pKind, final String pTarget, final String pOwnerStep, final String pOwnerToken, final String pPausedBy)
		{
			this.id = pId;
			this.projectId = pProjectId;
			this.kind = pKind;
			this.target = pTarget;
			this.ownerStep = pOwnerStep;
			this.ownerToken = pOwnerToken;
			this.pausedBy = pPausedBy;
		}
	}

	/** Every row of the ledger (a handful at most: one per paused target or table of a load in flight). */
	static List<Row> readAll(final Adaptation pFactory)
	{
		final List<Row> out = new ArrayList<Row>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LOAD_PAUSE).createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new Row(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.LoadPause.PROJECT), row.getString(FactoryPaths.LoadPause.KIND),
					row.getString(FactoryPaths.LoadPause.TARGET), row.getString(FactoryPaths.LoadPause.OWNER_STEP), row.getString(FactoryPaths.LoadPause.OWNER_TOKEN),
					row.getString(FactoryPaths.LoadPause.PAUSED_BY)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}
}
