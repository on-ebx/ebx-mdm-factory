package com.on.ebx.factory.intake;

/**
 * Automatic intake lot: the in-memory state the engine keeps per source of an ON project, rebuilt at every restart (nothing here is persisted: the queue is the
 * landing table itself, and a re-count at startup rebuilds it). Immutable; every method returns the next state. Pure.
 * <p>
 * Times are epoch milliseconds. {@code consumedSignalMs} is the signal's {@code lastRowAt} the engine already acted on (a chain was launched, or a re-count
 * decided nothing waited): a signal NEWER than it means new rows. {@code pendingSinceMs} is the first time the engine saw rows waiting (null: none known).
 * {@code lastRowMs} is the latest sign of a waiting row (the signal, or the instant of a re-count that found rows).
 */
public final class IntakePending
{
	/** Where the knowledge that rows wait came from: the trigger's signal, the safety sweep, or the re-count after a chain / at startup. */
	public enum Origin
	{
		SIGNAL, SWEEP, RECOUNT
	}

	public final long consumedSignalMs;
	public final Long pendingSinceMs;
	public final Long lastRowMs;
	public final Origin origin;
	/** The application said its batch is complete (REST call or the Factory's own read ended): due at once. */
	public final boolean batchComplete;

	private IntakePending(final long pConsumedSignalMs, final Long pPendingSinceMs, final Long pLastRowMs, final Origin pOrigin, final boolean pBatchComplete)
	{
		this.consumedSignalMs = pConsumedSignalMs;
		this.pendingSinceMs = pPendingSinceMs;
		this.lastRowMs = pLastRowMs;
		this.origin = pOrigin;
		this.batchComplete = pBatchComplete;
	}

	/** Nothing waiting, nothing consumed yet. */
	public static IntakePending empty()
	{
		return new IntakePending(0L, null, null, Origin.SIGNAL, false);
	}

	public boolean isPending()
	{
		return this.pendingSinceMs != null;
	}

	/**
	 * Takes in what the signal row says now. A {@code lastRowAt} newer than the one already consumed starts a pending period (if none runs) and moves the last-row
	 * time; the same or an older one changes nothing. Null (no signal row, or no value yet) changes nothing.
	 */
	public IntakePending observe(final Long pSignalLastRowMs, final long pNowMs)
	{
		if (pSignalLastRowMs == null || pSignalLastRowMs.longValue() <= this.consumedSignalMs)
		{
			return this;
		}
		final Long since = this.pendingSinceMs == null ? Long.valueOf(pNowMs) : this.pendingSinceMs;
		final Long last = this.lastRowMs == null ? pSignalLastRowMs : Long.valueOf(Math.max(this.lastRowMs.longValue(), pSignalLastRowMs.longValue()));
		// a sweep/re-count origin is kept until a chain consumes it; a fresh signal after it is a signal like any other
		return new IntakePending(this.consumedSignalMs, since, last, this.pendingSinceMs == null ? Origin.SIGNAL : this.origin, this.batchComplete);
	}

	/** A re-count or a sweep found {@code pCount} rows at NEW: some waiting start (or keep) their pending period with the last row at "now"; zero rows clear it. */
	public IntakePending counted(final long pCount, final long pNowMs, final Origin pOrigin)
	{
		if (pCount <= 0)
		{
			return new IntakePending(this.consumedSignalMs, null, null, Origin.SIGNAL, false);
		}
		if (this.pendingSinceMs != null)
		{
			return this;
		}
		return new IntakePending(this.consumedSignalMs, Long.valueOf(pNowMs), Long.valueOf(pNowMs), pOrigin, this.batchComplete);
	}

	/** The application (or the Factory's own read) said the batch is complete. */
	public IntakePending withBatchComplete(final long pNowMs)
	{
		return new IntakePending(this.consumedSignalMs, this.pendingSinceMs == null ? Long.valueOf(pNowMs) : this.pendingSinceMs,
			this.lastRowMs == null ? Long.valueOf(pNowMs) : this.lastRowMs, this.origin, true);
	}

	/** A chain was launched for this source: what the signal said is consumed, the pending period ends. */
	public IntakePending launched(final Long pSignalLastRowMs)
	{
		final long consumed = pSignalLastRowMs == null ? this.consumedSignalMs : Math.max(this.consumedSignalMs, pSignalLastRowMs.longValue());
		return new IntakePending(consumed, null, null, Origin.SIGNAL, false);
	}
}
