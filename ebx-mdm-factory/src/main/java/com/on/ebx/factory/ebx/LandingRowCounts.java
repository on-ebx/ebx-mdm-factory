package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.RowCountFallback;
import com.onwbp.adaptation.Adaptation;

/**
 * Staging-proposal live-run fix (docs/STAGING-PROPOSAL.md): the single place that answers "how many rows
 * is a {@code LandingTable}'s {@code LandingColumn} profile (fillRate/distinctCount) a percentage/count
 * OF" - before this class the answer was read from three different fields in three different files
 * ({@code ebx.AnomalyLlmSuggester}, {@code ebx.ui.FeedDesignerUserService}: {@code
 * LandingTable.sourceRowCount}; {@code ebx.MappingMatrixReader}: {@code LandingTable.lastLoadRows}),
 * which silently disagreed on a sample-file source (project Personnes, measured live 2026-09-23):
 * {@code sourceRowCount} ("as profiled by the catalog", {@code tools/build_xsd.py}) is populated only by
 * a live-database catalog scan ({@code ebx.LandingPlanner}) and stays {@code null} for a sample-file
 * source, while {@code lastLoadRows} ("rows of the last load") was correctly 180/165 - so the two files
 * reading {@code sourceRowCount} silently saw no row count at all and {@code staging.AttentionRules}
 * never fired {@code CONSTANT_COLUMN}/{@code EMPTY_COLUMN} for that project.
 * <p>
 * <b>Why {@code lastLoadRows} first, not a new field</b>: {@code ebx.LandingColumnProfiler}'s own
 * aggregate query (docs/STAGING-PROPOSAL.md lot 0 sec 1) runs {@code COUNT(*)} on the SAME landing table
 * {@code lastLoadRows} already counts - the landing table only ever holds the rows of its last load (there
 * is no separate "current data" the profiler could see that a load did not already produce), so a fresh
 * {@code ROW_COUNT} would equal the stored {@code lastLoadRows} and persisting it again would be a
 * redundant write, never a correction. Adding a fourth field to disagree with the other two was rejected
 * for the same reason. {@code sourceRowCount} is kept as the fallback for the one case {@code
 * lastLoadRows} cannot cover: a live-database source profiled by the catalog before its first load into
 * this project (no load has happened yet, so {@code lastLoadRows} is still null, but the catalog's own
 * scan already knows a row count).
 */
public final class LandingRowCounts
{
	private LandingRowCounts()
	{
	}

	/**
	 * The row count a {@code LandingColumn}'s {@code fillRate}/{@code distinctCount} of this {@code
	 * LandingTable} should be read against: {@code lastLoadRows} (the landing table's own last-load count,
	 * right for every source once it has loaded at least once - sample-file included) if set, else {@code
	 * sourceRowCount} (a live-database catalog scan's count, the only signal available before a first
	 * load). Null when neither is known yet. {@code pLandingTableRow} may itself be null (a column whose
	 * {@code landingTable} foreign key could not be resolved) - returns null rather than throwing. The
	 * fallback decision itself is pure and tested separately, see {@link RowCountFallback#pick}.
	 */
	public static Long rowCountOf(final Adaptation pLandingTableRow)
	{
		if (pLandingTableRow == null)
		{
			return null;
		}
		final Long lastLoadRows = LandingRowCounts.asLong(pLandingTableRow.get(FactoryPaths.LandingTable.LAST_LOAD_ROWS));
		final Long sourceRowCount = LandingRowCounts.asLong(pLandingTableRow.get(FactoryPaths.LandingTable.SOURCE_ROW_COUNT));
		return RowCountFallback.pick(lastLoadRows, sourceRowCount);
	}

	private static Long asLong(final Object pValue)
	{
		return pValue instanceof Number ? Long.valueOf(((Number) pValue).longValue()) : null;
	}
}
