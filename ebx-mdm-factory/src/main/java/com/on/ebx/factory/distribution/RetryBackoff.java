package com.on.ebx.factory.distribution;

/**
 * Distribution lot 1 (engine): "marks SENT/FAILED with retry/backoff" (SPEC-distribution.md "Real time").
 * Pure - no EBX class. {@code ebx.DistributionDispatcher} reads {@code DistributionEvent.attempts} and asks
 * this class whether the event is due for a retry yet, and what {@code attempts} value to write after a
 * failed send. Exponential, capped, with a small fixed ceiling on the attempt count so a permanently broken
 * target does not grow the outbox forever - {@code MAX_ATTEMPTS} reached marks the event {@code FAILED} for
 * good (visible on the Cockpit, replayable by a person - SPEC-distribution.md "replay from the Cockpit").
 * <p>
 * <b>Round 5 (2026-09-26, task brief item 3, DistribTest live incident):</b> with the original 1-hour cap
 * ({@code MAX_DELAY_SECONDS = 3600}), a row already at attempt 7 was measured live with a {@code
 * nextAttemptAt} roughly 33 minutes after the previous one - too long a wait for a Kafka-class target that is
 * very often back within a couple of minutes, and the dispatcher's own 10-second poll period (CLAUDE.md's
 * "a row due mid-interval waits for the next pass") only adds to it. {@code MAX_DELAY_SECONDS} is now capped
 * at 5 minutes; {@code BASE_SECONDS} and {@code MAX_ATTEMPTS} are unchanged (kept as their own named
 * constants below) - the retry BUDGET (how many tries before {@link #exhausted}/manual replay) is a separate
 * concern from how long any one wait is, and nothing in this incident suggested 8 attempts was itself wrong,
 * only that the tail of the exponential curve grew too slow.
 */
public final class RetryBackoff
{
	private RetryBackoff()
	{
	}

	/** An event past this many attempts is never retried automatically again - it is left {@code FAILED}, replayable only by a person from the Cockpit. */
	public static final int MAX_ATTEMPTS = 8;
	private static final int BASE_SECONDS = 15;
	/** Round 5: capped at 5 minutes (was 1 hour) - see this class' own Javadoc for the live measurement that prompted it. */
	private static final int MAX_DELAY_SECONDS = 300;

	/** Delay before the NEXT attempt, given how many attempts have already failed (0 = never tried yet: send immediately). */
	public static long delaySeconds(final int pAttemptsSoFar)
	{
		if (pAttemptsSoFar <= 0)
		{
			return 0L;
		}
		final long delay = (long) RetryBackoff.BASE_SECONDS << Math.min(pAttemptsSoFar - 1, 10);
		return Math.min(delay, RetryBackoff.MAX_DELAY_SECONDS);
	}

	/** Whether a failed event (its {@code attempts} count, and how long ago it was last attempted) is due for another try right now. */
	public static boolean isDue(final int pAttemptsSoFar, final long pSecondsSinceLastAttempt)
	{
		if (pAttemptsSoFar >= RetryBackoff.MAX_ATTEMPTS)
		{
			return false;
		}
		return pSecondsSinceLastAttempt >= RetryBackoff.delaySeconds(pAttemptsSoFar);
	}

	/** Whether {@code pAttemptsSoFar} (after incrementing for the attempt about to be recorded) has exhausted the retry budget - the caller then leaves the event {@code FAILED} without scheduling another automatic retry. */
	public static boolean exhausted(final int pAttemptsSoFar)
	{
		return pAttemptsSoFar >= RetryBackoff.MAX_ATTEMPTS;
	}
}
