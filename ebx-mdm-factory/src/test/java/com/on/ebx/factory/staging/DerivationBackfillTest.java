package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.List;

/**
 * JUnit-free: run the main, a failure throws. {@link DerivationBackfill} (lot T1, 2026-10-04) against an in-memory table whose "triggers" are
 * a flag: live (the written sentinel is replaced by a computed value) or not live (the sentinel stays).
 */
public final class DerivationBackfillTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		DerivationBackfillTest.decisionTable();
		DerivationBackfillTest.theFirstRowIsWrittenAloneThenTheRestInBatches();
		DerivationBackfillTest.rowsWithNothingToComputeAreNotWritten();
		DerivationBackfillTest.aTriggerThatIsNotLiveStopsTheFirstWriteAndChangesNothing();
		DerivationBackfillTest.aNonTextFieldGetsNullAndAnEmptyOneIsCountedAsSkipped();
		DerivationBackfillTest.aCancellationStopsAfterTheCurrentBatch();
		DerivationBackfillTest.aFailedBatchIsNeverHidden();
		DerivationBackfillTest.aTableWithNoRowToTouchWritesNothing();
		System.out.println("DerivationBackfillTest OK");
	}

	/** A staged row: what the scan reports and what a write changes. */
	private static final class Row
	{
		final String key;
		final boolean sourceNonNull;
		String derived;

		Row(final String pKey, final boolean pSourceNonNull, final String pDerived)
		{
			this.key = pKey;
			this.sourceNonNull = pSourceNonNull;
			this.derived = pDerived;
		}
	}

	/** The table and its writer; {@code live} says whether the trigger replaces the sentinel by a computed value. */
	private static final class Fake implements DerivationBackfill.Rows, DerivationBackfill.Writer
	{
		final List<Row> rows = new ArrayList<Row>();
		final boolean live;
		final List<Integer> transactionSizes = new ArrayList<Integer>();
		int scans;
		boolean failBatches;
		int cancelAfterTransactions = Integer.MAX_VALUE;

		Fake(final boolean pLive)
		{
			this.live = pLive;
		}

		@Override
		public void scan(final DerivationBackfill.RowVisitor pVisitor) throws Exception
		{
			this.scans++;
			for (final Row row : new ArrayList<Row>(this.rows))
			{
				if (!pVisitor.row(row.key, row.sourceNonNull, row.derived != null, row))
				{
					return;
				}
			}
		}

		@Override
		public void writeFirst(final DerivationBackfill.Update pUpdate) throws Exception
		{
			final Row row = (Row) pUpdate.handle;
			final String before = row.derived;
			this.apply(row, pUpdate.value);
			this.transactionSizes.add(1);
			if (DerivationBackfill.SENTINEL.equals(row.derived))
			{
				row.derived = before; // the transaction rolls back
				throw new DerivationBackfill.TriggerNotLive("trigger not live on " + row.key + ", nothing changed");
			}
		}

		@Override
		public void writeBatch(final List<DerivationBackfill.Update> pBatch) throws Exception
		{
			if (this.failBatches)
			{
				throw new IllegalStateException("EBX refused the batch");
			}
			for (final DerivationBackfill.Update update : pBatch)
			{
				this.apply((Row) update.handle, update.value);
			}
			this.transactionSizes.add(pBatch.size());
		}

		private void apply(final Row pRow, final String pValue)
		{
			pRow.derived = pValue;
			if (this.live && pRow.sourceNonNull)
			{
				pRow.derived = "computed-" + pRow.key; // the modify trigger overwrites whatever was written
			}
			else if (this.live)
			{
				pRow.derived = null; // a null source gives a null result
			}
		}
	}

	private static void decisionTable()
	{
		DerivationBackfillTest.check("text, source filled", DerivationBackfill.decide(true, true, false) == DerivationBackfill.Action.WRITE_SENTINEL);
		DerivationBackfillTest.check("text, only an old derived value", DerivationBackfill.decide(true, false, true) == DerivationBackfill.Action.WRITE_SENTINEL);
		DerivationBackfillTest.check("text, both filled", DerivationBackfill.decide(true, true, true) == DerivationBackfill.Action.WRITE_SENTINEL);
		DerivationBackfillTest.check("text, nothing", DerivationBackfill.decide(true, false, false) == DerivationBackfill.Action.SKIP_NOTHING_TO_DO);
		DerivationBackfillTest.check("non-text holding a value", DerivationBackfill.decide(false, true, true) == DerivationBackfill.Action.WRITE_NULL);
		DerivationBackfillTest.check("non-text empty, source filled", DerivationBackfill.decide(false, true, false) == DerivationBackfill.Action.SKIP_NULL_NON_STRING);
		DerivationBackfillTest.check("non-text, nothing", DerivationBackfill.decide(false, false, false) == DerivationBackfill.Action.SKIP_NOTHING_TO_DO);
		DerivationBackfillTest.check("sentinel value", DerivationBackfill.SENTINEL.equals(DerivationBackfill.valueOf(DerivationBackfill.Action.WRITE_SENTINEL)));
		DerivationBackfillTest.check("null value", DerivationBackfill.valueOf(DerivationBackfill.Action.WRITE_NULL) == null);
	}

	private static void theFirstRowIsWrittenAloneThenTheRestInBatches() throws Exception
	{
		final Fake fake = new Fake(true);
		for (int i = 0; i < 7; i++)
		{
			fake.rows.add(new Row("k" + i, true, i % 2 == 0 ? "stale copy" : null));
		}
		final DerivationBackfill.Result result = DerivationBackfill.run(fake, fake, true, 3, null);
		DerivationBackfillTest.check("one scan: " + fake.scans, fake.scans == 1);
		DerivationBackfillTest.check("first alone, then 3 + 3: " + fake.transactionSizes, fake.transactionSizes.equals(java.util.Arrays.asList(1, 3, 3)));
		DerivationBackfillTest.check("counts: " + result.summary(), result.scanned == 7 && result.recomputed == 7 && result.skipped == 0 && result.writeBatches == 2);
		DerivationBackfillTest.check("guard row named", "k0".equals(result.guardRow));
		boolean sentinelStored = false;
		for (final Row row : fake.rows)
		{
			sentinelStored = sentinelStored || DerivationBackfill.SENTINEL.equals(row.derived);
			DerivationBackfillTest.check("computed value in " + row.key, ("computed-" + row.key).equals(row.derived));
		}
		DerivationBackfillTest.check("the sentinel is never left in a row", !sentinelStored);
	}

	private static void rowsWithNothingToComputeAreNotWritten() throws Exception
	{
		final Fake fake = new Fake(true);
		fake.rows.add(new Row("empty", false, null));
		fake.rows.add(new Row("filled", true, null));
		fake.rows.add(new Row("orphanValue", false, "old"));
		final DerivationBackfill.Result result = DerivationBackfill.run(fake, fake, true, 10, null);
		DerivationBackfillTest.check("counts: " + result.summary(), result.scanned == 3 && result.recomputed == 2 && result.skipped == 1);
		DerivationBackfillTest.check("untouched", fake.rows.get(0).derived == null);
		DerivationBackfillTest.check("a derived value with no source is recomputed (to empty)", fake.rows.get(2).derived == null);
		DerivationBackfillTest.check("guard on the first row to touch, not on the first row", "filled".equals(result.guardRow));
	}

	private static void aTriggerThatIsNotLiveStopsTheFirstWriteAndChangesNothing() throws Exception
	{
		final Fake fake = new Fake(false);
		fake.rows.add(new Row("a", true, "old a"));
		fake.rows.add(new Row("b", true, "old b"));
		try
		{
			DerivationBackfill.run(fake, fake, true, 10, null);
			throw new AssertionError("a pass over rows whose trigger is not live went through");
		}
		catch (final DerivationBackfill.TriggerNotLive expected)
		{
			DerivationBackfillTest.check("message names the row: " + expected.getMessage(), expected.getMessage().contains("not live"));
		}
		DerivationBackfillTest.check("nothing changed", "old a".equals(fake.rows.get(0).derived) && "old b".equals(fake.rows.get(1).derived));
		DerivationBackfillTest.check("only the guard transaction ran: " + fake.transactionSizes, fake.transactionSizes.size() == 1);
	}

	private static void aNonTextFieldGetsNullAndAnEmptyOneIsCountedAsSkipped() throws Exception
	{
		final Fake fake = new Fake(true);
		fake.rows.add(new Row("holds", true, "12"));
		fake.rows.add(new Row("emptyWithSource", true, null));
		fake.rows.add(new Row("nothing", false, null));
		final DerivationBackfill.Result result = DerivationBackfill.run(fake, fake, false, 10, null);
		DerivationBackfillTest.check("counts: " + result.summary(), result.scanned == 3 && result.recomputed == 1 && result.skipped == 2 && result.skippedNullNonString == 1);
		DerivationBackfillTest.check("the summary mentions the limitation", result.summary().contains("a write cannot provoke"));
	}

	private static void aCancellationStopsAfterTheCurrentBatch() throws Exception
	{
		final Fake fake = new Fake(true);
		for (int i = 0; i < 20; i++)
		{
			fake.rows.add(new Row("k" + i, true, null));
		}
		final DerivationBackfill.Result result = DerivationBackfill.run(fake, fake, true, 4, new ChildKeysSweep.Cancellation()
		{
			@Override
			public boolean cancelled()
			{
				return fake.transactionSizes.size() >= 2;
			}
		});
		DerivationBackfillTest.check("stopped: " + result.summary(), result.cancelled && result.recomputed < 20 && result.recomputed == 5);
	}

	private static void aFailedBatchIsNeverHidden() throws Exception
	{
		final Fake fake = new Fake(true);
		fake.failBatches = true;
		fake.rows.add(new Row("a", true, null));
		fake.rows.add(new Row("b", true, null));
		fake.rows.add(new Row("c", true, null));
		try
		{
			DerivationBackfill.run(fake, fake, true, 2, null);
			throw new AssertionError("a failed batch was swallowed");
		}
		catch (final IllegalStateException expected)
		{
			DerivationBackfillTest.check("the writer's own error comes through", expected.getMessage().contains("refused"));
		}
	}

	private static void aTableWithNoRowToTouchWritesNothing() throws Exception
	{
		final Fake fake = new Fake(true);
		fake.rows.add(new Row("a", false, null));
		final DerivationBackfill.Result result = DerivationBackfill.run(fake, fake, true, 10, null);
		DerivationBackfillTest.check("nothing written: " + result.summary(), fake.transactionSizes.isEmpty() && result.guardRow == null && result.recomputed == 0);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
