package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.transfer.BatchRanges;
import com.on.ebx.factory.transfer.DrainInBatches;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;

/**
 * Shared "fix a volume-scale table without a giant transaction" pattern - volume lot 2a (2026-09-28,
 * docs/VOLUME.md, task 6). {@code ebx.MasterReset}, {@code ebx.StepResetter} and {@code ebx.ProjectDeleter}
 * each used to gather EVERY matching {@code Xref}/{@code TransferReject} row of a project into a {@code
 * List<Adaptation>} (or iterate them inside one all-or-nothing {@link Procedure}) before clearing or deleting
 * them - fine at the tens-to-thousands scale every prior lot was measured against, not at real data volume
 * (one {@code Xref} row per staged/master record).
 * <p>
 * {@link #forEachBatched} streams the scan into lightweight PRIMARY KEY strings only (never a {@code
 * List<Adaptation>}), fully closes the {@link RequestResult} before any write, then applies {@code pOp} in
 * batches of {@link #BATCH_SIZE}, each its own {@link Procedure} - so a huge project commits incrementally
 * instead of one all-or-nothing transaction, and a still-open scan of the SAME table is never held while a
 * separate write transaction modifies it.
 */
final class VolumeBatchOps
{
	/** Same granularity as {@code ebx.GoldenKeyRefresher} (task 5: "commit in Procedures of 1000 rows"). */
	static final int BATCH_SIZE = 1000;

	private VolumeBatchOps()
	{
	}

	/** One row's fix, applied fresh inside its own batch's {@link Procedure} (the row is re-read by primary key, never carried over from the initial scan). */
	interface RowOp
	{
		void apply(ProcedureContext pContext, Adaptation pRow) throws Exception;
	}

	/**
	 * Streams {@code pTable} for {@code pPredicate}, collecting only primary-key strings, then applies
	 * {@code pOp} to each matching row in batches of {@link #BATCH_SIZE}. Returns the number of rows the scan
	 * found (attempted - {@code pOp} decides per row whether anything actually changes).
	 */
	static int forEachBatched(final Session pSession, final AdaptationHome pHome, final AdaptationTable pTable, final String pPredicate, final String pErrorContext,
		final RowOp pOp) throws OperationException
	{
		final List<String> keys = new ArrayList<String>();
		final RequestResult rows = pTable.createRequestResult(pPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				keys.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}

		for (final BatchRanges.Range range : BatchRanges.of(keys.size(), VolumeBatchOps.BATCH_SIZE))
		{
			final List<String> batch = keys.subList(range.start, range.end);
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pHome).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					for (final String key : batch)
					{
						final Adaptation row = pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key));
						if (row == null)
						{
							continue; // deleted/changed meanwhile - defensive
						}
						pOp.apply(pContext, row);
					}
				}
			});
			if (result.hasFailed())
			{
				throw EngineRefusal.coded("unexpected.batchWrite", pErrorContext + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
			}
		}
		return keys.size();
	}

	/**
	 * Deletes EVERY row of {@code pTable} in batches of {@link #BATCH_SIZE}, each its own {@link Procedure} (audit F-35): the next
	 * batch of primary keys is read (and the scan closed) before each write, so neither the whole table's keys nor one giant
	 * transaction is ever held - {@link #forEachBatched} first lists every key, which a landing table of tens of millions of rows
	 * should not need. A failed batch stops the drain and says how many rows were already deleted (those batches are committed).
	 *
	 * @return how many rows were deleted
	 */
	static int deleteAllInBatches(final Session pSession, final AdaptationHome pHome, final AdaptationTable pTable, final String pErrorContext)
		throws OperationException
	{
		return VolumeBatchOps.deleteAllInBatches(pSession, pHome, pTable, pErrorContext, null);
	}

	/** Same, asking {@code pStop} (a job's {@code Handle#cancelled}, which is also its check-in) before each batch; null never stops. */
	static int deleteAllInBatches(final Session pSession, final AdaptationHome pHome, final AdaptationTable pTable, final String pErrorContext,
		final java.util.function.BooleanSupplier pStop) throws OperationException
	{
		try
		{
			return DrainInBatches.run(new DrainInBatches.Source<String>()
			{
				@Override
				public List<String> peek(final int pMax)
				{
					final List<String> keys = new ArrayList<String>();
					final RequestResult rows = pTable.createRequestResult(null);
					try
					{
						for (Adaptation row = rows.nextAdaptation(); row != null && keys.size() < pMax; row = rows.nextAdaptation())
						{
							keys.add(row.getOccurrencePrimaryKey().format());
						}
					}
					finally
					{
						rows.close();
					}
					return keys;
				}

				@Override
				public void remove(final List<String> pBatch) throws Exception
				{
					final ProcedureResult result = ProgrammaticService.createForSession(pSession, pHome).execute(new Procedure()
					{
						@Override
						public void execute(final ProcedureContext pContext) throws Exception
						{
							for (final String key : pBatch)
							{
								final Adaptation row = pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key));
								if (row != null)
								{
									pContext.doDelete(row.getAdaptationName(), false);
								}
							}
						}
					});
					if (result.hasFailed())
					{
						throw new IllegalStateException(result.getExceptionFullMessage(Locale.ENGLISH));
					}
				}
			}, VolumeBatchOps.BATCH_SIZE, pStop);
		}
		catch (final DrainInBatches.Failure ex)
		{
			throw EngineRefusal.coded("unexpected.batchWrite", pErrorContext + ": " + ex.getMessage());
		}
	}
}
