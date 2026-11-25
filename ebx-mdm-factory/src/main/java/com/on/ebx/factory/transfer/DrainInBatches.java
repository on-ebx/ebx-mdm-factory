package com.on.ebx.factory.transfer;

import java.util.List;

/**
 * Empties a table (or any source) in bounded batches without ever holding more than one batch in memory (audit F-35):
 * read the next {@code pBatchSize} keys, remove them in their own transaction, read again - until nothing is left. The pure loop,
 * apart from EBX so its three decisions are tested directly: it ends on an empty read, it reports how many were removed when a
 * batch fails, and it refuses to spin when a batch that "succeeded" leaves the same first key behind.
 */
public final class DrainInBatches
{
	private DrainInBatches()
	{
	}

	/** The two sides of one pass; both may throw, the loop reports how far it got. */
	public interface Source<K>
	{
		/** At most {@code pMax} keys still present, oldest-first or any stable order; empty when nothing is left. */
		List<K> peek(int pMax) throws Exception;

		/** Removes exactly these keys in one transaction. */
		void remove(List<K> pBatch) throws Exception;
	}

	/** A pass failed: {@link #removed} rows were already gone (committed batches) when it did. */
	public static final class Failure extends Exception
	{
		private static final long serialVersionUID = 1L;
		public final int removed;

		Failure(final int pRemoved, final String pMessage, final Throwable pCause)
		{
			super(pMessage, pCause);
			this.removed = pRemoved;
		}
	}

	/** @return how many keys were removed in all. */
	public static <K> int run(final Source<K> pSource, final int pBatchSize) throws Failure
	{
		return DrainInBatches.run(pSource, pBatchSize, null);
	}

	/**
	 * Same loop, asking {@code pStop} before each batch (a queued job's check-in and cancellation, {@code FactoryJobs.Handle#cancelled}): a
	 * {@code true} ends the drain with a {@link Failure} saying how many rows were removed - the batches already committed stay committed.
	 *
	 * @param pStop may be null (never stops)
	 */
	public static <K> int run(final Source<K> pSource, final int pBatchSize, final java.util.function.BooleanSupplier pStop) throws Failure
	{
		final int size = Math.max(1, pBatchSize);
		int removed = 0;
		K previousFirst = null;
		while (true)
		{
			if (pStop != null && pStop.getAsBoolean())
			{
				throw new Failure(removed, "stopped on request after " + removed + " row(s) were removed", null);
			}
			final List<K> batch;
			try
			{
				batch = pSource.peek(size);
			}
			catch (final Exception ex)
			{
				throw new Failure(removed, "reading the next batch failed after " + removed + " row(s) were removed: " + DrainInBatches.text(ex), ex);
			}
			if (batch == null || batch.isEmpty())
			{
				return removed;
			}
			if (previousFirst != null && previousFirst.equals(batch.get(0)))
			{
				throw new Failure(removed, "the rows were not removed although the batch reported success (" + removed + " row(s) removed before it)", null);
			}
			try
			{
				pSource.remove(batch);
			}
			catch (final Exception ex)
			{
				throw new Failure(removed, "removing a batch failed after " + removed + " row(s) were removed: " + DrainInBatches.text(ex), ex);
			}
			removed += batch.size();
			previousFirst = batch.get(0);
		}
	}

	private static String text(final Exception pException)
	{
		final String message = pException.getMessage();
		return message == null || message.trim().isEmpty() ? pException.getClass().getName() : message;
	}
}
