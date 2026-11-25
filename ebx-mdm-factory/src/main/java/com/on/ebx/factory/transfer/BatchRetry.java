package com.on.ebx.factory.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The generic "commit this batch as one unit; if the whole unit fails, isolate the offending item(s) so only
 * they are rejected" decision (PLAN.md, task C: {@code StagingLoader} "one failing row must not lose the
 * batch: on a batch failure, retry that batch row by row so only the offending rows are rejected"). No EBX
 * class: the actual attempt (a real {@code Procedure} against the staging/master dataset) is delegated to the
 * caller-supplied {@link Attempt}, so this orchestration is unit-tested against a fake attempt, and {@code
 * StagingLoader}/{@code MasterLoader} are the only callers that plug in a real EBX write.
 * <p>
 * <b>Volume-guards lot (2026-09-28, task item 6): BISECTION, not a flat per-row fallback.</b> Before this lot,
 * a batch failure fell back to calling {@link Attempt#tryOne} once for EVERY item, whatever the batch size -
 * a batch of 500 with one bad row cost 500 extra {@code Procedure}s just to find it. {@link #commit} now
 * splits a failing batch in half and retries each half the SAME way (recursively, down to a single item),
 * so a clean half succeeds in ONE {@link Attempt#tryBatch} call and only the half(s) still containing an
 * offending item keep splitting - a single bad row among N costs roughly {@code 2*log2(N)+2} calls total
 * instead of {@code N+1} (see {@code BatchRetryTest}'s own worked example). {@link Attempt#tryOne} is now
 * called only once bisection has narrowed a failure down to exactly one item - it is never the fallback for
 * an entire batch any more. {@link Outcome#written}/{@link Outcome#rejected} preserve the original list's
 * relative order regardless of how the bisection tree split (left-to-right concatenation at every level).
 * See {@link com.on.ebx.factory.transfer.BatchCircuitBreaker} for the companion "abort after K consecutive
 * batches that failed entirely" guard this lot also adds - a separate class, reported to by the CALLER
 * (once per top-level {@link #commit} call, not by this class itself, which has no notion of "consecutive").
 */
public final class BatchRetry
{
	private BatchRetry()
	{
	}

	/** One way to commit a list of items - real ({@code StagingLoader}: a {@code Procedure}) or fake (tests). */
	public interface Attempt<T>
	{
		/** @return null on success, a failure message otherwise. Never called with an empty list. */
		String tryBatch(List<T> pItems);

		/** @return null on success, a failure message otherwise. Only ever called after {@link #tryBatch} failed. */
		String tryOne(T pItem);
	}

	public static final class Rejected<T>
	{
		public final T item;
		public final String reason;

		Rejected(final T pItem, final String pReason)
		{
			this.item = pItem;
			this.reason = pReason;
		}
	}

	public static final class Outcome<T>
	{
		public final List<T> written;
		public final List<Rejected<T>> rejected;
		/** True when the batch attempt succeeded outright: {@link #tryOne} was never called for this batch. */
		public final boolean batchSucceeded;

		Outcome(final List<T> pWritten, final List<Rejected<T>> pRejected, final boolean pBatchSucceeded)
		{
			this.written = pWritten;
			this.rejected = pRejected;
			this.batchSucceeded = pBatchSucceeded;
		}
	}

	/** @return every item of {@code pItems}, split into written and rejected(-with-reason); {@code pItems} itself is never mutated. */
	public static <T> Outcome<T> commit(final List<T> pItems, final Attempt<T> pAttempt)
	{
		if (pItems.isEmpty())
		{
			return new Outcome<T>(Collections.<T> emptyList(), Collections.<Rejected<T>> emptyList(), true);
		}
		final String batchError = pAttempt.tryBatch(pItems);
		if (batchError == null)
		{
			return new Outcome<T>(new ArrayList<T>(pItems), Collections.<Rejected<T>> emptyList(), true);
		}
		if (pItems.size() == 1)
		{
			final T only = pItems.get(0);
			final String oneError = pAttempt.tryOne(only);
			if (oneError == null)
			{
				return new Outcome<T>(new ArrayList<T>(pItems), Collections.<Rejected<T>> emptyList(), false);
			}
			final List<Rejected<T>> rejected = new ArrayList<Rejected<T>>(1);
			rejected.add(new Rejected<T>(only, oneError));
			return new Outcome<T>(Collections.<T> emptyList(), rejected, false);
		}
		// bisect (volume-guards lot, 2026-09-28, task item 6): the whole batch failed - split it in half and
		// retry each half the same way, recursively down to a single item, instead of falling back to tryOne
		// for every item. A half with no offending item succeeds in ONE tryBatch call; only the half(s) still
		// containing one keep splitting - see the class Javadoc for the exact call-count math.
		final int mid = pItems.size() / 2;
		final Outcome<T> left = BatchRetry.commit(pItems.subList(0, mid), pAttempt);
		final Outcome<T> right = BatchRetry.commit(pItems.subList(mid, pItems.size()), pAttempt);
		final List<T> written = new ArrayList<T>(left.written.size() + right.written.size());
		written.addAll(left.written);
		written.addAll(right.written);
		final List<Rejected<T>> rejected = new ArrayList<Rejected<T>>(left.rejected.size() + right.rejected.size());
		rejected.addAll(left.rejected);
		rejected.addAll(right.rejected);
		return new Outcome<T>(written, rejected, false);
	}
}
