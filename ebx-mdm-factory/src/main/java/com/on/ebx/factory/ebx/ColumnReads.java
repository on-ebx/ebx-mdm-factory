package com.on.ebx.factory.ebx;

import java.util.Date;

import com.on.ebx.factory.quality.ReadCache;
import com.on.ebx.factory.quality.ScanGuard;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.Session;

/**
 * Staging polish (2026-10-17, docs/UX-STAGING-POLISH.md sec 2): the EBX half of the volume guard of the proposal passes' column reads - the caches
 * {@link QualitySuggester#distinctValues} and {@link QualitySuggester#exactAggregates} / {@link QualitySuggester#boundedAggregates} consult, and the
 * row count that {@link ScanGuard} decides on. Every entry is keyed by user (record permissions can differ), dataspace, dataset, table and column, and
 * stamped with {@code AdaptationTable.getLastModificationDate()} (a cheap read that changes at any write to the table - CLAUDE.md sec 9), so a landing load
 * invalidates the entries of the table at once; they also expire after {@link #TTL_MILLIS} and are bounded in total values held, so a proposal that
 * finished leaves nothing behind for long.
 */
final class ColumnReads
{
	static final long TTL_MILLIS = 10L * 60L * 1000L;
	/** About 400,000 held values: a few tens of MB at most (values are short strings); one entry is at most {@link QualitySuggester#DEFAULT_MAX_DISTINCT_VALUES}. */
	static final long MAX_VALUES = 400000L;
	static final long MAX_ENTRIES = 4000L;

	static final ReadCache<QualitySuggester.ColumnQuery> DISTINCT = new ReadCache<QualitySuggester.ColumnQuery>(ColumnReads.MAX_VALUES, ColumnReads.TTL_MILLIS);
	static final ReadCache<QualitySuggester.ExactAggregates> AGGREGATES = new ReadCache<QualitySuggester.ExactAggregates>(ColumnReads.MAX_ENTRIES,
		ColumnReads.TTL_MILLIS);
	static final ReadCache<Long> ROWS = new ReadCache<Long>(ColumnReads.MAX_ENTRIES, ColumnReads.TTL_MILLIS);
	/** Lot S10 (2026-10-18): the identity figures of a landing table (digests of its distinct identity values, never the values), weighed by the digests held. */
	static final ReadCache<com.on.ebx.factory.staging.IdentityFacts.Table> IDENTITY = new ReadCache<com.on.ebx.factory.staging.IdentityFacts.Table>(ColumnReads.MAX_VALUES,
		ColumnReads.TTL_MILLIS);

	private ColumnReads()
	{
	}

	/** user | dataspace | dataset | table - what every cache key starts with. */
	static String tableKey(final Session pSession, final QualitySuggester.LandingTableRef pLandingTable)
	{
		final String user = pSession == null || pSession.getUserReference() == null ? "-" : pSession.getUserReference().getUserId();
		return user + "|" + pLandingTable.dataset.getHome().getKey().getName() + "|" + pLandingTable.dataset.getAdaptationName().getStringName() + "|"
			+ pLandingTable.table.getTablePath().format();
	}

	static String columnKey(final Session pSession, final QualitySuggester.LandingTableRef pLandingTable, final String pField, final String pScopeField)
	{
		return ColumnReads.tableKey(pSession, pLandingTable) + "|" + pField + "|" + (pScopeField == null ? "" : pScopeField);
	}

	/** The table's last-modification time, or -1 when EBX does not give one (nothing is then cached). */
	static long stamp(final QualitySuggester.LandingTableRef pLandingTable)
	{
		try
		{
			final Date date = pLandingTable.table.getLastModificationDate();
			return date == null ? -1L : date.getTime();
		}
		catch (final RuntimeException ex)
		{
			return -1L;
		}
	}

	/** The table's row count (one count per table and modification date), or -1 when it cannot be read. */
	static long tableRows(final Session pSession, final QualitySuggester.LandingTableRef pLandingTable)
	{
		final String key = ColumnReads.tableKey(pSession, pLandingTable);
		final long stamp = ColumnReads.stamp(pLandingTable);
		final Long cached = ColumnReads.ROWS.get(key, stamp);
		if (cached != null)
		{
			return cached.longValue();
		}
		try
		{
			final RequestResult result = pLandingTable.table.createRequestResult(null);
			try
			{
				final long size = result.getSize();
				ColumnReads.ROWS.put(key, stamp, Long.valueOf(size), 1L);
				return size;
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return -1L;
		}
	}

	/** Whole table or a sample, under the bounds in force now. With the guard off ({@code mdm.factory.profile.maxRowsFullScan} 0) the table is not even counted. */
	static ScanGuard.Decision decision(final Session pSession, final QualitySuggester.LandingTableRef pLandingTable)
	{
		final long max = ScanGuard.maxRowsFullScan();
		if (max <= 0L)
		{
			return ScanGuard.decide(0L, max, 0L);
		}
		return ScanGuard.decide(ColumnReads.tableRows(pSession, pLandingTable), max, ScanGuard.sampleRows());
	}
}
