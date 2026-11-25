package com.on.ebx.factory.landing;

/**
 * Staging-proposal live-run fix (docs/STAGING-PROPOSAL.md, bug 1): the pure decision {@code
 * ebx.LandingRowCounts} is a thin EBX-bound wrapper around - which of a {@code LandingTable}'s two row-count
 * fields ({@code lastLoadRows}, {@code sourceRowCount}) is the right denominator for a {@code LandingColumn}'s
 * {@code fillRate}/{@code distinctCount} (a percentage/count over "the rows of this table"). See {@code
 * ebx.LandingRowCounts}'s own Javadoc for why {@code lastLoadRows} is preferred and no third field is added.
 * No EBX type here - kept pure, and tested in isolation, the same {@code landing.ColumnProfileSql}/{@code
 * ebx.LandingColumnProfiler} split this module already keeps for its other generated-SQL feature.
 */
public final class RowCountFallback
{
	private RowCountFallback()
	{
	}

	/**
	 * {@code pLastLoadRows} if not null (the landing table's own last-load count - right for every source
	 * once it has loaded at least once, sample-file sources included), else {@code pSourceRowCount} (a
	 * live-database catalog scan's count - the only signal available before a first load). Null when
	 * neither is known yet.
	 */
	public static Long pick(final Long pLastLoadRows, final Long pSourceRowCount)
	{
		return pLastLoadRows != null ? pLastLoadRows : pSourceRowCount;
	}
}
