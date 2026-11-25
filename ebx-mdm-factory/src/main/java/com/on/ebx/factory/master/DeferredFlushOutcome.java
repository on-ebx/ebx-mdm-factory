package com.on.ebx.factory.master;

/**
 * What a failed flush of deferred references does to a load's counters. The second pass of a load counts a deferred reference as resolved when it PLANS the
 * update and writes the updates later, in batches: when a batch's procedure fails, the references in it were never written, so counting them as resolved would
 * report a master zone more complete than it is. They are moved to "unresolved" and the failure is said in the step's message. Pure: no EBX class, unit-tested.
 */
public final class DeferredFlushOutcome
{
	private DeferredFlushOutcome()
	{
	}

	/** {@code [resolved, unresolved]} after {@code pLost} references counted resolved turned out not to be written. */
	public static long[] moveToUnresolved(final long pResolved, final long pUnresolved, final long pLost)
	{
		final long lost = Math.max(0L, Math.min(pLost, pResolved));
		return new long[] { pResolved - lost, pUnresolved + lost };
	}

	/** The sentence for the step message, empty when every flush succeeded. */
	public static String warning(final long pFailedRows, final long pLostReferences, final String pFirstError)
	{
		if (pFailedRows <= 0L)
		{
			return "";
		}
		return "WARNING: " + pFailedRows + " master row(s) could not get their deferred reference(s) written (" + pLostReferences + " reference(s) counted unresolved"
			+ (pFirstError == null || pFirstError.isEmpty() ? "" : ": " + pFirstError) + "): those references are empty or stale until the next load.";
	}
}
