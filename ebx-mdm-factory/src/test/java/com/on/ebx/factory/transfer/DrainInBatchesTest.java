package com.on.ebx.factory.transfer;

import java.util.ArrayList;
import java.util.List;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link DrainInBatches} (audit F-35). */
public final class DrainInBatchesTest
{
	/** An in-memory "table" of the keys 1..n; {@code failOnBatch} (1-based, 0 = never) makes that remove throw; {@code stuck} makes remove a no-op. */
	private static final class Fake implements DrainInBatches.Source<Integer>
	{
		private final List<Integer> rows = new ArrayList<Integer>();
		private final int failOnBatch;
		private final boolean stuck;
		private int removes;
		private int biggestBatch;
		private int peeks;

		Fake(final int pCount, final int pFailOnBatch, final boolean pStuck)
		{
			for (int i = 1; i <= pCount; i++)
			{
				this.rows.add(Integer.valueOf(i));
			}
			this.failOnBatch = pFailOnBatch;
			this.stuck = pStuck;
		}

		@Override
		public List<Integer> peek(final int pMax)
		{
			this.peeks++;
			return new ArrayList<Integer>(this.rows.subList(0, Math.min(pMax, this.rows.size())));
		}

		@Override
		public void remove(final List<Integer> pBatch) throws Exception
		{
			this.removes++;
			this.biggestBatch = Math.max(this.biggestBatch, pBatch.size());
			if (this.removes == this.failOnBatch)
			{
				throw new IllegalStateException();
			}
			if (!this.stuck)
			{
				this.rows.removeAll(pBatch);
			}
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final Fake empty = new Fake(0, 0, false);
		DrainInBatchesTest.check("an empty source removes nothing and does not call remove", DrainInBatches.run(empty, 1000) == 0 && empty.removes == 0);

		final Fake many = new Fake(2500, 0, false);
		final int removed = DrainInBatches.run(many, 1000);
		DrainInBatchesTest.check("2500 rows are all removed and counted", removed == 2500 && many.rows.isEmpty());
		DrainInBatchesTest.check("in 3 transactions of at most 1000", many.removes == 3 && many.biggestBatch == 1000);

		final Fake exact = new Fake(2000, 0, false);
		DrainInBatchesTest.check("an exact multiple needs no extra pass", DrainInBatches.run(exact, 1000) == 2000 && exact.removes == 2);

		final Fake failing = new Fake(2500, 2, false);
		try
		{
			DrainInBatches.run(failing, 1000);
			DrainInBatchesTest.check("a failing batch must throw", false);
		}
		catch (final DrainInBatches.Failure ex)
		{
			DrainInBatchesTest.check("the failure reports the committed rows", ex.removed == 1000 && failing.rows.size() == 1500);
			DrainInBatchesTest.check("a message-less exception is named by its class: " + ex.getMessage(), ex.getMessage().contains("IllegalStateException") && ex.getMessage().contains("1000 row(s)"));
		}

		final Fake stuck = new Fake(10, 0, true);
		try
		{
			DrainInBatches.run(stuck, 4);
			DrainInBatchesTest.check("a drain that makes no progress must stop", false);
		}
		catch (final DrainInBatches.Failure ex)
		{
			DrainInBatchesTest.check("it stops on the second pass instead of spinning", stuck.removes == 1 && ex.getMessage().contains("not removed"));
		}

		final Fake zero = new Fake(3, 0, false);
		DrainInBatchesTest.check("a batch size of 0 is read as 1, never an endless empty read", DrainInBatches.run(zero, 0) == 3);

		// audit F-35 (wave 5): a queued job's Stop is asked before each batch; the batches already committed stay committed
		final Fake stoppable = new Fake(3500, 0, false);
		final int[] asked = new int[1];
		try
		{
			DrainInBatches.run(stoppable, 1000, new java.util.function.BooleanSupplier()
			{
				@Override
				public boolean getAsBoolean()
				{
					asked[0]++;
					return asked[0] > 2; // the third question says stop: two batches went through
				}
			});
			DrainInBatchesTest.check("a stop request must end the drain", false);
		}
		catch (final DrainInBatches.Failure ex)
		{
			DrainInBatchesTest.check("two batches committed, then stopped: " + ex.getMessage(), ex.removed == 2000 && stoppable.rows.size() == 1500 && ex.getMessage().contains("stopped on request"));
		}
		final Fake neverStopped = new Fake(1500, 0, false);
		DrainInBatchesTest.check("a stop check that always says no changes nothing", DrainInBatches.run(neverStopped, 1000, new java.util.function.BooleanSupplier()
		{
			@Override
			public boolean getAsBoolean()
			{
				return false;
			}
		}) == 1500);
		System.out.println("DrainInBatchesTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
