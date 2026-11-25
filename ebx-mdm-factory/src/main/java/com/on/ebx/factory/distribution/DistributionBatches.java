package com.on.ebx.factory.distribution;

/**
 * How many records one distribution send asks {@code connectors_sync} for, and what a cut send does next.
 * <p>
 * A send used to pass no {@code max}, so Connectors applied its API default (200, {@code RunLimits.API}) and a target
 * table larger than that was only partly sent, in silence (found by the Connectors audit C-35, wave 10). A send now
 * passes {@link #maxRows()} explicitly. When Connectors still answers {@code truncated: true}:
 * <ul>
 * <li>scope {@code changed} drains its backlog (what was sent is no longer "changed"), so the send asks again, up to
 * {@link #MAX_ROUNDS} rounds, until a round is not cut;</li>
 * <li>scope {@code all} would start again from the first record, so the send stops and fails with a sentence that names
 * the property to raise - a partial full send is never reported as a success.</li>
 * </ul>
 * The limit is a JVM property read at each send ({@link #PROPERTY}), like {@code LoadDeadline}; 0, a negative or an
 * unreadable value is read as the default, never as "no limit".
 * <p>
 * <b>Read timeout of a send (wave 13, measured 2026-10-12 on Vol100k).</b> One {@code connectors_sync} call sends up to
 * {@link #maxRows()} records and its duration grows with them (150-250 rows/s measured: 100,000 rows need 7-11 minutes), so the
 * 300 s read timeout of every other Connectors call cut a 88,991-row send while Connectors went on sending. A send now waits
 * {@link #sendTimeoutMs(int)} = {@link #BASE_TIMEOUT_MS} + rows x {@link #perRowMs()}, bounded by {@link #sendTimeoutMaxMs()}.
 * <p>
 * <b>Asynchronous send (wave 13, same day).</b> The real fix is the landing's pattern: the send starts each call with {@code async: true} and follows the
 * Connectors run ({@code ebx.DistributionSendCalls}), on the ordinary client, so no long read timeout is involved. {@link #sendTimeoutMs(int)} and its
 * properties are kept for the calls that are still synchronous: an older Connectors that ignores {@code async}, and a Connectors that refuses it for a
 * scope send (the Connectors of 2026-10-12). {@link #isReadTimeout} and {@link #timeoutMessage} keep serving those.
 */
public final class DistributionBatches
{
	/** JVM property: the records one {@code connectors_sync} call of a distribution send may take. */
	public static final String PROPERTY = "ebx.factory.distribution.maxRows";
	public static final int DEFAULT_MAX_ROWS = 100000;
	/** Rounds of a {@code changed} send before it gives up on a backlog that does not drain. */
	public static final int MAX_ROUNDS = 1000;

	/** JVM property: milliseconds added to the read timeout of a send for each record it may take. */
	public static final String PER_ROW_PROPERTY = "ebx.factory.distribution.perRowMs";
	public static final int DEFAULT_PER_ROW_MS = 20;
	/** JVM property: the ceiling of the read timeout of a send, in milliseconds. */
	public static final String TIMEOUT_MAX_PROPERTY = "ebx.factory.distribution.sendTimeoutMaxMs";
	public static final long DEFAULT_TIMEOUT_MAX_MS = 2L * 60L * 60L * 1000L;
	/** The read timeout of any other Connectors call ({@code McpServers.readTimeoutMs()}, 300 s): what a send with no record would wait. */
	public static final int BASE_TIMEOUT_MS = 300000;

	private DistributionBatches()
	{
	}

	public static int maxRows()
	{
		return DistributionBatches.maxRows(System.getProperty(DistributionBatches.PROPERTY));
	}

	/** Same for an explicit property value (the test's entry point). */
	public static int maxRows(final String pPropertyValue)
	{
		if (pPropertyValue != null)
		{
			try
			{
				final int parsed = Integer.parseInt(pPropertyValue.trim());
				if (parsed > 0)
				{
					return parsed;
				}
			}
			catch (final NumberFormatException ex)
			{
				// an unreadable value falls back to the default: a typo must not cut every send at 200 again
			}
		}
		return DistributionBatches.DEFAULT_MAX_ROWS;
	}

	/** Whether a cut round of scope {@code pScope} is asked again: only {@code changed}, only while a round moved records and the rounds last. */
	public static boolean askAgain(final String pScope, final boolean pTruncated, final long pRoundRows, final int pRoundsDone)
	{
		return pTruncated && "changed".equalsIgnoreCase(pScope) && pRoundRows > 0L && pRoundsDone < DistributionBatches.MAX_ROUNDS;
	}

	/** The failure sentence of a send left cut (scope {@code all}, or a {@code changed} backlog that does not drain). */
	public static String cutMessage(final String pTablePath, final String pScope, final long pRowsSent, final int pMaxRows)
	{
		return pTablePath + ": only " + pRowsSent + " record(s) sent, more remain (scope=" + pScope + ", " + pMaxRows
			+ " per call) - raise the JVM property " + DistributionBatches.PROPERTY + " and send again";
	}

	/** Milliseconds per record added to a send's read timeout: the property {@link #PER_ROW_PROPERTY}, the default when absent, not positive or unreadable. */
	public static int perRowMs()
	{
		return (int) DistributionBatches.positive(System.getProperty(DistributionBatches.PER_ROW_PROPERTY), DistributionBatches.DEFAULT_PER_ROW_MS);
	}

	/** The ceiling of a send's read timeout: the property {@link #TIMEOUT_MAX_PROPERTY}, the default (2 hours) when absent, not positive or unreadable. */
	public static long sendTimeoutMaxMs()
	{
		return DistributionBatches.positive(System.getProperty(DistributionBatches.TIMEOUT_MAX_PROPERTY), DistributionBatches.DEFAULT_TIMEOUT_MAX_MS);
	}

	/** How long one {@code connectors_sync} call of {@code pMaxRows} records is waited for, in the properties' current values. */
	public static int sendTimeoutMs(final int pMaxRows)
	{
		return DistributionBatches.sendTimeoutMs(pMaxRows, DistributionBatches.perRowMs(), DistributionBatches.sendTimeoutMaxMs());
	}

	/**
	 * The pure rule: {@link #BASE_TIMEOUT_MS} + {@code pMaxRows} x {@code pPerRowMs}, never below the base and never above
	 * {@code pCeilingMs} (a ceiling under the base still leaves the base). Computed in {@code long}: a huge row limit must not overflow.
	 */
	static int sendTimeoutMs(final int pMaxRows, final long pPerRowMs, final long pCeilingMs)
	{
		final long wanted = DistributionBatches.BASE_TIMEOUT_MS + Math.max(0, pMaxRows) * Math.max(0L, pPerRowMs);
		final long bounded = Math.max(DistributionBatches.BASE_TIMEOUT_MS, Math.min(wanted, pCeilingMs));
		return (int) Math.min(bounded, Integer.MAX_VALUE);
	}

	/** A property value as a positive number; the default for anything else (absent, 0, negative, garbage). */
	static long positive(final String pValue, final long pDefault)
	{
		if (pValue != null)
		{
			try
			{
				final long parsed = Long.parseLong(pValue.trim());
				if (parsed > 0L)
				{
					return parsed;
				}
			}
			catch (final NumberFormatException ex)
			{
				// a typo falls back to the default: it must not turn the timeout off
			}
		}
		return pDefault;
	}

	/** Whether {@code pMessage} is a read timeout (the in-JVM bridge's "read timeout", an HTTP "timed out"). */
	public static boolean isReadTimeout(final String pMessage)
	{
		if (pMessage == null)
		{
			return false;
		}
		final String lower = pMessage.toLowerCase(java.util.Locale.ENGLISH);
		return lower.contains("read timeout") || lower.contains("timed out");
	}

	/**
	 * The sentence of a send cut by the Factory's own read timeout: the transport's message, then what that means. Connectors does not stop when
	 * the Factory stops waiting - the run went on to the end and Kafka received every row (Vol100k, 2026-10-12) - so the person is told to look
	 * at the run before sending again, not to send again.
	 */
	public static String timeoutMessage(final String pTransportMessage, final String pMappingId)
	{
		String base = pTransportMessage == null ? "The Connectors call timed out" : pTransportMessage.trim();
		while (base.endsWith("."))
		{
			base = base.substring(0, base.length() - 1);
		}
		return base + ": the Connectors run may still be sending; check the Connectors sync log of mapping " + pMappingId
			+ " (connectors_run_get) before sending again.";
	}

	/**
	 * Wave 13 (2026-10-12): the end message of a finished send. {@code pSent} (created + updated) and {@code pUnchanged} (noChange) stay apart: a
	 * {@code changed} send where every record was unchanged ended "Sent 146559 row(s)" on Vol100k although nothing was sent. Plainly
	 * "Sent N row(s) across M mapping(s), scope=S" when nothing was unchanged, "Sent N row(s), U unchanged, across ..." otherwise.
	 */
	public static String sentMessage(final long pSent, final long pUnchanged, final int pMappings, final String pScope)
	{
		return "Sent " + pSent + " row(s)" + (pUnchanged > 0L ? ", " + pUnchanged + " unchanged," : "") + " across " + pMappings + " mapping(s), scope=" + pScope;
	}

	/**
	 * The message of a send stopped on request: how many rows went out before the stop (a stopped Connectors run keeps what it sent, 13000 on
	 * Vol100k, 2026-10-12 - the message read 0), that they keep their outbound state, and where a {@code changed} send picks up.
	 * {@code pStillStopping}: the run had not reached its final state when the Factory last read it, so the count may be short.
	 */
	public static String stoppedMessage(final String pReason, final long pSent, final long pUnchanged, final boolean pStillStopping)
	{
		return "Send stopped (" + pReason + ") after " + pSent + " row(s) sent" + (pUnchanged > 0L ? " and " + pUnchanged + " found unchanged" : "")
			+ "; the rows sent keep their outbound state, a 'changed' send resumes after them."
			+ (pStillStopping ? " The Connectors run had not finished stopping when this was written (it stops at its next batch), so the count may be short: read the run in Connectors." : "");
	}
}
