package com.on.ebx.factory.ebx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wave 2 (2026-09-30, lot V2-A): a small in-memory cache of {@link GoldenRecordsReader.Result} - the whole-table aggregates behind
 * wizard step 8 (record and golden counts, fill rates) and step 7's "the master holds N golden records" sentence. They used to run
 * on the first render of every wizard opening (and again for step 7); they now run only when one of those steps is the step shown,
 * and only when what they measure may have changed: the cache key holds the project, the master dataset, {@link
 * MatchingResultsStore#epoch} (bumped by every matching run and every decision), and the token of the latest master load and
 * matching runs (read off the wizard snapshot, no extra read). A time limit ({@link #MAX_AGE_MS}) bounds what neither can see - a
 * record entered through Match-and-Merge's own triggers, a change made in the add-on's screens.
 * <p>
 * Single node, in memory, bounded ({@link #MAX_ENTRIES}); a restart empties it. The cached {@code Result} is shared, never mutated
 * by its readers.
 */
public final class GoldenRecordsCache
{
	static final long MAX_AGE_MS = 10L * 60L * 1000L;
	static final int MAX_ENTRIES = 32;

	private static final class Cached
	{
		final GoldenRecordsReader.Result result;
		final long at;

		Cached(final GoldenRecordsReader.Result pResult, final long pAt)
		{
			this.result = pResult;
			this.at = pAt;
		}
	}

	private static final Map<String, Cached> CACHE = new LinkedHashMap<String, Cached>(16, 0.75f, true)
	{
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(final Map.Entry<String, Cached> pEldest)
		{
			return size() > GoldenRecordsCache.MAX_ENTRIES;
		}
	};

	private GoldenRecordsCache()
	{
	}

	/** Pure: the cache key. {@code pChangeToken} is any string that changes when the master load or a matching run did (the wizard passes the start times and statuses of those steps). */
	public static String key(final String pProjectId, final String pMasterDataspace, final String pMasterDataset, final long pEpoch, final String pChangeToken)
	{
		return pProjectId + "\u0001" + pMasterDataspace + "\u0001" + pMasterDataset + "\u0001" + pEpoch + "\u0001" + (pChangeToken == null ? "" : pChangeToken);
	}

	public static GoldenRecordsReader.Result get(final String pKey)
	{
		return GoldenRecordsCache.get(pKey, System.currentTimeMillis());
	}

	/** Package-visible with the clock as a parameter for the test. */
	static GoldenRecordsReader.Result get(final String pKey, final long pNow)
	{
		synchronized (GoldenRecordsCache.CACHE)
		{
			final Cached entry = GoldenRecordsCache.CACHE.get(pKey);
			if (entry == null)
			{
				return null;
			}
			if (pNow - entry.at > GoldenRecordsCache.MAX_AGE_MS)
			{
				GoldenRecordsCache.CACHE.remove(pKey);
				return null;
			}
			return entry.result;
		}
	}

	public static void put(final String pKey, final GoldenRecordsReader.Result pResult)
	{
		GoldenRecordsCache.put(pKey, pResult, System.currentTimeMillis());
	}

	static void put(final String pKey, final GoldenRecordsReader.Result pResult, final long pNow)
	{
		synchronized (GoldenRecordsCache.CACHE)
		{
			GoldenRecordsCache.CACHE.put(pKey, new Cached(pResult, pNow));
		}
	}

	static void clear()
	{
		synchronized (GoldenRecordsCache.CACHE)
		{
			GoldenRecordsCache.CACHE.clear();
		}
	}
}
