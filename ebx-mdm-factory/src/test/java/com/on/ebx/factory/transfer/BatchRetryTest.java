package com.on.ebx.factory.transfer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class BatchRetryTest
{
	public static void main(final String[] pArgs)
	{
		BatchRetryTest.emptyBatchIsFine();
		BatchRetryTest.wholeBatchSucceedsNoRetry();
		BatchRetryTest.oneOffendingRowIsRejectedTheRestIsWritten();
		BatchRetryTest.everyRowFailsIndividuallyToo();
		BatchRetryTest.bisectionIsolatesOneBadRowInLogarithmicCalls();
		BatchRetryTest.bisectionPreservesOriginalOrder();
		BatchRetryTest.bisectionHandlesTwoBadRowsInDifferentHalves();
		System.out.println("BatchRetryTest OK");
	}

	private static void emptyBatchIsFine()
	{
		final BatchRetry.Outcome<String> outcome = BatchRetry.commit(java.util.Collections.<String> emptyList(), new NeverCalled());
		BatchRetryTest.check("nothing written", outcome.written.isEmpty());
		BatchRetryTest.check("nothing rejected", outcome.rejected.isEmpty());
		BatchRetryTest.check("counts as succeeded", outcome.batchSucceeded);
	}

	private static void wholeBatchSucceedsNoRetry()
	{
		final List<String> items = Arrays.asList("a", "b", "c");
		final CountingAttempt attempt = new CountingAttempt(java.util.Collections.<String> emptySet());
		final BatchRetry.Outcome<String> outcome = BatchRetry.commit(items, attempt);
		BatchRetryTest.check("every item written: " + outcome.written, outcome.written.equals(items));
		BatchRetryTest.check("nothing rejected", outcome.rejected.isEmpty());
		BatchRetryTest.check("batch succeeded outright", outcome.batchSucceeded);
		BatchRetryTest.check("tryOne never called", attempt.oneCalls == 0);
	}

	/**
	 * The whole point of this class: one bad row does not lose the other two.
	 * <p>
	 * Volume-guards lot (2026-09-28, task item 6): with bisection, "a" and "c" each succeed via their OWN
	 * {@code tryBatch} call once isolated into a clean half - {@code tryOne} is only ever reached for "bad"
	 * itself, once bisection has narrowed it down to a single item. Before this lot every item went through
	 * {@code tryOne} (asserted {@code oneCalls == 3}); that is no longer true, and is exactly the efficiency
	 * this lot buys (see {@link #bisectionIsolatesOneBadRowInLogarithmicCalls} for the general case).
	 */
	private static void oneOffendingRowIsRejectedTheRestIsWritten()
	{
		final List<String> items = Arrays.asList("a", "bad", "c");
		final CountingAttempt attempt = new CountingAttempt(new HashSet<String>(Arrays.asList("bad")));
		final BatchRetry.Outcome<String> outcome = BatchRetry.commit(items, attempt);
		BatchRetryTest.check("a and c written, order preserved: " + outcome.written, outcome.written.equals(Arrays.asList("a", "c")));
		BatchRetryTest.check("exactly one rejected", outcome.rejected.size() == 1);
		BatchRetryTest.check("the offending row, with a reason", "bad".equals(outcome.rejected.get(0).item) && outcome.rejected.get(0).reason != null);
		BatchRetryTest.check("batch did not succeed outright", !outcome.batchSucceeded);
		BatchRetryTest.check("tryOne reached only for the single isolated bad row, not every item: " + attempt.oneCalls, attempt.oneCalls == 1);
	}

	/**
	 * Volume-guards lot (2026-09-28, task item 6): the exact call-count math the class Javadoc claims - a
	 * single bad row among N = 2^k items costs {@code 2*k + 2} total calls (tryBatch and tryOne combined),
	 * never N+1. Worked by hand for N=8 (k=3): top tryBatch(8) fails [1]; the clean half of 4 succeeds in one
	 * tryBatch [2]; the bad half of 4 fails [3], its clean half of 2 succeeds in one tryBatch [4], its bad half
	 * of 2 fails [5], its clean half of 1 succeeds in one tryBatch [6], its bad half of 1 fails tryBatch [7]
	 * then fails tryOne [8] - 8 calls total, not 9 (N+1).
	 */
	private static void bisectionIsolatesOneBadRowInLogarithmicCalls()
	{
		final List<String> items = Arrays.asList("g0", "g1", "g2", "g3", "BAD", "g5", "g6", "g7");
		final CountingAttempt attempt = new CountingAttempt(new HashSet<String>(Arrays.asList("BAD")));
		final BatchRetry.Outcome<String> outcome = BatchRetry.commit(items, attempt);
		BatchRetryTest.check("every good row written, order preserved: " + outcome.written,
			outcome.written.equals(Arrays.asList("g0", "g1", "g2", "g3", "g5", "g6", "g7")));
		BatchRetryTest.check("exactly the bad row rejected", outcome.rejected.size() == 1 && "BAD".equals(outcome.rejected.get(0).item));
		// the old flat fallback (every item through tryOne once tryBatch fails) would cost N+1 = 9 calls here;
		// bisection costs exactly 2*log2(8)+2 = 8 - a small margin at N=8, but the SAME formula at a real
		// batch size (N=500: 2*log2(500)+2 ~= 20 vs. 501) is the actual efficiency gain this lot is about.
		BatchRetryTest.check("total calls (tryBatch + tryOne) is exactly 2*log2(8)+2 = 8, not N+1 = 9: batch=" + attempt.batchCalls + " one=" + attempt.oneCalls,
			attempt.batchCalls + attempt.oneCalls == 8);
		BatchRetryTest.check("only one tryOne call - never one per item", attempt.oneCalls == 1);
	}

	/** Order is preserved end to end through however many bisection levels a batch goes through - left-to-right concatenation at every level. */
	private static void bisectionPreservesOriginalOrder()
	{
		final List<String> items = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "BAD", "h");
		final CountingAttempt attempt = new CountingAttempt(new HashSet<String>(Arrays.asList("BAD")));
		final BatchRetry.Outcome<String> outcome = BatchRetry.commit(items, attempt);
		BatchRetryTest.check("written keeps the original relative order: " + outcome.written,
			outcome.written.equals(Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h")));
	}

	/** Two bad rows in different halves: both are found, bisection recurses independently on each side. */
	private static void bisectionHandlesTwoBadRowsInDifferentHalves()
	{
		final List<String> items = Arrays.asList("BAD1", "g1", "g2", "g3", "g4", "g5", "g6", "BAD2");
		final CountingAttempt attempt = new CountingAttempt(new HashSet<String>(Arrays.asList("BAD1", "BAD2")));
		final BatchRetry.Outcome<String> outcome = BatchRetry.commit(items, attempt);
		BatchRetryTest.check("both good middles written: " + outcome.written,
			outcome.written.equals(Arrays.asList("g1", "g2", "g3", "g4", "g5", "g6")));
		BatchRetryTest.check("both bad rows rejected", outcome.rejected.size() == 2);
		final java.util.Set<String> rejectedItems = new HashSet<String>();
		for (final BatchRetry.Rejected<String> rejected : outcome.rejected)
		{
			rejectedItems.add(rejected.item);
		}
		BatchRetryTest.check("exactly BAD1 and BAD2", rejectedItems.equals(new HashSet<String>(Arrays.asList("BAD1", "BAD2"))));
	}

	private static void everyRowFailsIndividuallyToo()
	{
		final List<String> items = Arrays.asList("a", "b");
		final CountingAttempt attempt = new CountingAttempt(new HashSet<String>(Arrays.asList("a", "b")));
		final BatchRetry.Outcome<String> outcome = BatchRetry.commit(items, attempt);
		BatchRetryTest.check("nothing written", outcome.written.isEmpty());
		BatchRetryTest.check("both rejected", outcome.rejected.size() == 2);
	}

	private static final class NeverCalled implements BatchRetry.Attempt<String>
	{
		@Override
		public String tryBatch(final List<String> pItems)
		{
			throw new AssertionError("must not be called for an empty batch");
		}

		@Override
		public String tryOne(final String pItem)
		{
			throw new AssertionError("must not be called for an empty batch");
		}
	}

	/** The whole batch fails whenever any of {@code pFailingItems} is present; an individual item fails only when it is itself in {@code pFailingItems}. */
	private static final class CountingAttempt implements BatchRetry.Attempt<String>
	{
		private final Set<String> failingItems;
		int oneCalls;
		/** Volume-guards lot (2026-09-28, task item 6): counts every tryBatch call, at every bisection level - what the call-count math tests check against. */
		int batchCalls;

		CountingAttempt(final Set<String> pFailingItems)
		{
			this.failingItems = pFailingItems;
		}

		@Override
		public String tryBatch(final List<String> pItems)
		{
			this.batchCalls++;
			for (final String item : pItems)
			{
				if (this.failingItems.contains(item))
				{
					return "batch refused because of " + item;
				}
			}
			return null;
		}

		@Override
		public String tryOne(final String pItem)
		{
			this.oneCalls++;
			return this.failingItems.contains(pItem) ? "row refused: " + pItem : null;
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
