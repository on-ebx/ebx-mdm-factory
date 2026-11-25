package com.on.ebx.factory.landing;

/**
 * Volume-guards lot (2026-09-28, task item 1, "truncated landing loads must fail, not pass"): pure decision on
 * whether a {@code connectors_sync} feed answer represents a COMPLETE read of the source, given what the
 * source is now reporting ({@code truncated}/{@code available} - top-level fields a separate Connectors-side
 * lot is adding to the answer, not present on every build yet, see {@link #decide}'s own parameters) and what
 * the catalog last saw ({@code sourceRowCount}, from {@code Column.rowCount} - can be 0 when the table was
 * never profiled, or stale when rows were added or removed at the source since the last scan).
 * <p>
 * <b>Why a separate class instead of inline logic in {@link LandingFlow#loadOutcome}</b>: this is the one
 * genuinely new DECISION this lot adds (the connector-answer parsing around it - {@code status}/{@code count}/
 * {@code errors} - already existed and is unchanged); keeping it its own pure class makes the FAIL/WARN/OK
 * boundary (and the tolerance band) directly unit-testable without building a fake {@code JsonNode} tree for
 * every case.
 * <p>
 * <b>Precedence, deliberately in this order</b>: an explicit {@code truncated=true} or {@code available &gt;
 * count} from the source always wins - the source is telling the factory, in so many words, that it did not
 * hand over everything it has, so no row-count heuristic could ever make that a lesser signal. Only when
 * neither is present (an older Connectors build, or a connector type that never reports them) does this fall
 * back to comparing {@code count} against the catalog's own last-known {@code sourceRowCount} - a much
 * weaker signal (the catalog snapshot itself can be stale in EITHER direction), so it only ever produces
 * {@link Level#WARN} until the shortfall is large enough ({@link #FAIL_BELOW_RATIO}) to call {@link
 * Level#FAILED} instead.
 */
public final class LoadOutcomeRule
{
	/**
	 * Below this fraction of {@code sourceRowCount}, a shortfall is no longer explained by ordinary churn
	 * (rows legitimately deleted at the source between the catalog's scan and this load) and is treated as a
	 * FAILED, not merely a WARN, load. Task brief's own suggestion, applied as written: "FAILED when count
	 * &lt; 0.95 * sourceRowCount, WARN otherwise" - i.e. a shortfall of more than 5%. Package-visible for the
	 * test.
	 */
	static final double FAIL_BELOW_RATIO = 0.95;

	/** How complete a load reads, once {@link #decide} has weighed the source's own signal against the catalog's. */
	public enum Level
	{
		/** Nothing suggests the read was incomplete. */
		OK,
		/** A shortfall against the catalog's last-known count, small enough to plausibly be ordinary churn (rows deleted at the source since the last scan). */
		WARN,
		/** The source itself says the read was incomplete, or the shortfall is too large to be ordinary churn. */
		FAILED
	}

	/** {@link #level} plus, for {@link Level#WARN}/{@link Level#FAILED}, a plain-language {@link #reason} - null for {@link Level#OK} (nothing to say). */
	public static final class Decision
	{
		public final Level level;
		public final String reason;

		Decision(final Level pLevel, final String pReason)
		{
			this.level = pLevel;
			this.reason = pReason;
		}
	}

	private LoadOutcomeRule()
	{
	}

	/**
	 * @param pTruncated the connector answer's own top-level {@code truncated} field, or {@code null} when the
	 *            answer does not carry it (an older Connectors build - the whole check then falls back to
	 *            {@code pSourceRowCount}).
	 * @param pAvailable the connector answer's own top-level {@code available} field (how many rows the source
	 *            reports it actually has), or {@code null} when the answer does not carry it.
	 * @param pCount rows the feed actually reported reading ({@code connectors_sync}'s own {@code count}).
	 * @param pSourceRowCount the catalog's last-known row count for this table (0 or negative: never profiled,
	 *            or genuinely unknown - the row-count comparison is then skipped entirely, never guessed).
	 */
	public static Decision decide(final Boolean pTruncated, final Long pAvailable, final long pCount, final long pSourceRowCount)
	{
		return LoadOutcomeRule.decide(pTruncated, pAvailable, pCount, 0L, false, pSourceRowCount);
	}

	/**
	 * Volume wave 2, lot V2-E (2026-10-03): the same decision for a run that may have read through a feed WATERMARK
	 * ({@link WatermarkOutcome}).
	 * <ul>
	 * <li>{@code pFiltered} true: the run started from a stored checkpoint, so {@code pCount} is only the rows CHANGED
	 * since - 0 of 500,000 is a perfectly good load. The comparison with the catalog's {@code sourceRowCount} is then
	 * skipped (it would call every quiet night a failure); the source's own {@code truncated}/{@code available} keep
	 * their full weight.</li>
	 * <li>{@code pSkipped}: rows the engine dropped at or below the checkpoint after the source returned them - they
	 * are part of what the source reported {@code available}, so {@code available &gt; count + skipped} is the real
	 * "the read was cut short" test (with no watermark {@code pSkipped} is 0 and nothing changes).</li>
	 * </ul>
	 * A first incremental run and a full refresh read everything: {@code pFiltered} false, the catalog comparison applies.
	 */
	public static Decision decide(final Boolean pTruncated, final Long pAvailable, final long pCount, final long pSkipped, final boolean pFiltered, final long pSourceRowCount)
	{
		if (Boolean.TRUE.equals(pTruncated))
		{
			return new Decision(Level.FAILED, "the source reports the read was truncated (truncated=true): only " + pCount + " row(s) were read");
		}
		if (pAvailable != null && pAvailable.longValue() > pCount + Math.max(0L, pSkipped))
		{
			return new Decision(Level.FAILED, "the source reports " + pAvailable + " row(s) available but only " + pCount + " were read");
		}
		if (!pFiltered && pSourceRowCount > 0 && pCount < pSourceRowCount)
		{
			final double ratio = (double) pCount / (double) pSourceRowCount;
			final String common = pCount + " of the " + pSourceRowCount + " row(s) last seen at the source were read (" + LoadOutcomeRule.percent(ratio) + "%)";
			if (ratio < LoadOutcomeRule.FAIL_BELOW_RATIO)
			{
				return new Decision(Level.FAILED,
					common + " - below the " + LoadOutcomeRule.percent(LoadOutcomeRule.FAIL_BELOW_RATIO) + "% tolerance for rows deleted at the source between the scan and the load");
			}
			return new Decision(Level.WARN, common + " - some may have been deleted at the source between the scan and the load");
		}
		return new Decision(Level.OK, null);
	}

	private static long percent(final double pRatio)
	{
		return Math.round(pRatio * 100d);
	}
}
