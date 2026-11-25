package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * JUnit-free: run the main, a failure throws. Fix/derivation-apply-backfill (2026-10-17, lot G1): the live table of capability 226 in miniature - 534 rows written before the
 * apply, a derived column empty on all of them, an unchanged-row reload that writes nothing - and the repair's own scan ({@link DerivationStaleness} filter in front of {@link
 * DerivationBackfill#run}) against a fake table whose "trigger" turns the marker into the library's value, exactly the contract {@code DerivationBackfillPass} relies on.
 * Not a measurement of EBX: the live write path could not be exercised (the live instance was read-only for this lot).
 */
public final class DerivationRepairSimulationTest
{
	private static final Date APPLIED = new Date(1_000_000L);

	private static final class Row
	{
		final String key;
		final String mobile;
		String derived;
		Date lastWrite;

		Row(final String pKey, final String pMobile, final Date pLastWrite)
		{
			this.key = pKey;
			this.mobile = pMobile;
			this.lastWrite = pLastWrite;
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		DerivationRepairSimulationTest.fillsEveryRowOfTheLiveCase();
		DerivationRepairSimulationTest.leavesAValueTheLibraryRefusedAloneOnTheNextLoad();
		DerivationRepairSimulationTest.stopsWhenTheTriggersAreNotLive();
		System.out.println("DerivationRepairSimulationTest OK");
	}

	private static List<Row> table(final int pRows)
	{
		final List<Row> rows = new ArrayList<Row>();
		for (int i = 0; i < pRows; i++)
		{
			// every 10th row has no mobile at all; every 7th has a number the library refuses (it answers nothing)
			rows.add(new Row("R" + i, i % 10 == 0 ? null : (i % 7 == 0 ? "xx" : "06 12 34 56 " + (10 + i % 80)), new Date(900_000L)));
		}
		return rows;
	}

	/** The trigger: the library's answer for the row's source, whatever the marker. */
	private static String library(final String pMobile)
	{
		return pMobile == null || pMobile.startsWith("xx") ? null : "+33" + pMobile.replace(" ", "").substring(1);
	}

	private static long run(final List<Row> pTable, final DerivationStaleness.Need pNeed, final boolean pTriggersLive, final Date pNow) throws Exception
	{
		final DerivationBackfill.Rows rows = new DerivationBackfill.Rows()
		{
			@Override
			public void scan(final DerivationBackfill.RowVisitor pVisitor) throws Exception
			{
				for (final Row row : new ArrayList<Row>(pTable))
				{
					final boolean anySource = row.mobile != null;
					final boolean derivedFilled = row.derived != null;
					// DerivationBackfillPass: the predicate (EMPTY_DERIVED) selects empty derived + filled input; the per-row rule keeps what was written before the apply
					if (pNeed == DerivationStaleness.Need.EMPTY_DERIVED && !(DerivationStaleness.emptyDerivedCandidate(anySource, derivedFilled)
						&& DerivationStaleness.rowStale(anySource, derivedFilled, row.lastWrite, APPLIED)))
					{
						continue;
					}
					if (!pVisitor.row(row.key, anySource, derivedFilled, row))
					{
						return;
					}
				}
			}
		};
		final DerivationBackfill.Writer writer = new DerivationBackfill.Writer()
		{
			private void write(final DerivationBackfill.Update pUpdate)
			{
				final Row row = (Row) pUpdate.handle;
				row.derived = pTriggersLive ? DerivationRepairSimulationTest.library(row.mobile) : pUpdate.value;
				row.lastWrite = pNow;
			}

			@Override
			public void writeFirst(final DerivationBackfill.Update pUpdate) throws Exception
			{
				this.write(pUpdate);
				if (DerivationBackfill.SENTINEL.equals(((Row) pUpdate.handle).derived))
				{
					((Row) pUpdate.handle).derived = null;
					throw new DerivationBackfill.TriggerNotLive("trigger not live");
				}
			}

			@Override
			public void writeBatch(final List<DerivationBackfill.Update> pBatch) throws Exception
			{
				for (final DerivationBackfill.Update update : pBatch)
				{
					this.write(update);
				}
			}
		};
		return DerivationBackfill.run(rows, writer, true, 100, null).recomputed;
	}

	private static void fillsEveryRowOfTheLiveCase() throws Exception
	{
		final List<Row> table = DerivationRepairSimulationTest.table(534);
		final DerivationStaleness.Need need = DerivationStaleness.decide(534L, APPLIED, new Date(900_000L), false);
		DerivationRepairSimulationTest.check("nothing was written since the apply: every row", need == DerivationStaleness.Need.ALL_ROWS);
		final long recomputed = DerivationRepairSimulationTest.run(table, need, true, new Date(1_200_000L));
		long filled = 0;
		long expected = 0;
		for (final Row row : table)
		{
			filled += row.derived != null ? 1 : 0;
			expected += DerivationRepairSimulationTest.library(row.mobile) != null ? 1 : 0;
		}
		DerivationRepairSimulationTest.check("every row with a number the library accepts is filled: " + filled + "/" + expected, filled == expected && filled > 400);
		DerivationRepairSimulationTest.check("rows with no input at all were not written", recomputed == 534 - 54);
		// the next load: the table changed after the apply, and the only empty derived values are the ones the library refused, all written after the apply
		final DerivationStaleness.Need next = DerivationStaleness.decide(534L, APPLIED, new Date(1_200_000L), false);
		DerivationRepairSimulationTest.check("EMPTY_DERIVED at the next load", next == DerivationStaleness.Need.EMPTY_DERIVED);
		DerivationRepairSimulationTest.check("and nothing is stale any more", DerivationRepairSimulationTest.run(table, next, true, new Date(1_300_000L)) == 0L);
	}

	private static void leavesAValueTheLibraryRefusedAloneOnTheNextLoad() throws Exception
	{
		// a few rows written before the apply are still empty (the table was partly rewritten since): only they are candidates, the others are not touched
		final List<Row> table = DerivationRepairSimulationTest.table(40);
		for (final Row row : table)
		{
			row.lastWrite = new Date(1_500_000L);
			row.derived = DerivationRepairSimulationTest.library(row.mobile);
		}
		final Row old = table.get(3);
		old.lastWrite = new Date(900_000L);
		old.derived = null;
		final long recomputed = DerivationRepairSimulationTest.run(table, DerivationStaleness.Need.EMPTY_DERIVED, true, new Date(1_600_000L));
		DerivationRepairSimulationTest.check("exactly the stale row", recomputed == 1L && old.derived != null);
	}

	private static void stopsWhenTheTriggersAreNotLive() throws Exception
	{
		final List<Row> table = DerivationRepairSimulationTest.table(30);
		boolean refused = false;
		try
		{
			DerivationRepairSimulationTest.run(table, DerivationStaleness.Need.ALL_ROWS, false, new Date(1_200_000L));
		}
		catch (final DerivationBackfill.TriggerNotLive ex)
		{
			refused = true;
		}
		DerivationRepairSimulationTest.check("the guard ends the pass", refused);
		for (final Row row : table)
		{
			DerivationRepairSimulationTest.check("no marker is left in a row", !DerivationBackfill.SENTINEL.equals(row.derived));
		}
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
