package com.on.ebx.factory.transfer;

/**
 * Volume-guards lot (2026-09-28, task item 6, "BatchRetry: bisect + circuit breaker"): stops a load after
 * {@link #tripAfter} CONSECUTIVE batches that failed ENTIRELY (nothing at all written from them, even after
 * {@link BatchRetry#commit}'s own bisection all the way down to single rows) - a systematic failure
 * (connectivity, a permission dropped mid-run, a schema mismatch affecting every row identically) must abort
 * the load with a clear message instead of spending one {@code Procedure} per remaining batch, and then one
 * per remaining ROW once bisected, all rejected the same way (the audit's own example: "a systematic failure
 * turns a 5M-row load into 5M transactions and 5M rejects"). A batch that partially failed (bisection isolated
 * one bad row out of 500) is NOT a "batch that failed entirely" and resets the counter - only a batch that
 * wrote NOTHING AT ALL counts, and only consecutively: an isolated fully-failed batch surrounded by
 * successful ones is exactly what bisection is FOR, not a reason to abort.
 * <p>
 * Pure, stateful (one instance per load run/entity - see {@code ebx.StagingLoader#loadRootFeed}/{@code
 * ebx.MasterLoader#loadEntity}, each of which creates its own and threads it through every {@link
 * BatchRetry#commit} call its own flush makes), no EBX class.
 */
public final class BatchCircuitBreaker
{
	/** Task brief's own number: "K = 3, configurable". */
	public static final int DEFAULT_CONSECUTIVE_FAILURES_TO_TRIP = 3;

	private final int tripAfter;
	private int consecutiveFailures;
	private boolean tripped;

	public BatchCircuitBreaker()
	{
		this(BatchCircuitBreaker.DEFAULT_CONSECUTIVE_FAILURES_TO_TRIP);
	}

	/** @param pTripAfter how many consecutive fully-failed batches trip the breaker; floored at 1 (a breaker that never trips is not a breaker). */
	public BatchCircuitBreaker(final int pTripAfter)
	{
		this.tripAfter = Math.max(1, pTripAfter);
	}

	/**
	 * Reports one batch's outcome. A non-empty batch that wrote nothing at all counts toward the consecutive
	 * streak; anything else (a batch that wrote at least one row, or an empty batch - nothing was even
	 * attempted) resets it to 0.
	 *
	 * @param pBatchWasEmpty true when there was nothing to commit at all (an empty buffer) - never counted
	 *            either way, and deliberately NOT the same as "wrote nothing": a caller must not report an
	 *            empty flush as a failure.
	 * @param pBatchWroteNothing true when the batch was attempted (non-empty) and {@link BatchRetry.Outcome#written}
	 *            came back empty - every single row of it, root batch and every bisected piece, was rejected.
	 * @return true the FIRST time this call trips the breaker (an already-tripped breaker keeps returning
	 *         false - the caller acts on the trip once and then stops calling {@link #report} at all, since the
	 *         load is being aborted).
	 */
	public boolean report(final boolean pBatchWasEmpty, final boolean pBatchWroteNothing)
	{
		if (this.tripped)
		{
			return false;
		}
		if (pBatchWasEmpty)
		{
			return false;
		}
		if (pBatchWroteNothing)
		{
			this.consecutiveFailures++;
		}
		else
		{
			this.consecutiveFailures = 0;
		}
		if (this.consecutiveFailures >= this.tripAfter)
		{
			this.tripped = true;
			return true;
		}
		return false;
	}

	public boolean isTripped()
	{
		return this.tripped;
	}

	/** For the abort message: how many consecutive batches failed entirely when the breaker tripped. */
	public int consecutiveFailures()
	{
		return this.consecutiveFailures;
	}
}
