package com.on.ebx.factory.ebx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audit F-33 (2026-10-06): a small in-memory cache for the whole-table aggregates the wizard redraws on every render (the staging load grid's
 * {@code GROUP BY}, the held-back count, a master entity's row counts per source system). The pattern of {@link GoldenRecordsCache}, generic:
 * the CALLER builds a key holding everything the figure depends on - above all the {@code getLastModificationDate()} of the tables it measures,
 * which moves on any record creation, update or deletion, so a load, a matching run's writes or a decision change the key and the old entry is
 * simply never asked for again - and a time limit bounds what no key can see.
 * <p>
 * Single node, in memory, bounded (least recently used goes first); a restart empties it. A cached value is shared between requests and must
 * never be mutated by its readers. Only a COMPLETE figure is ever put: a count that failed is not worth keeping for the next ten minutes.
 */
final class ReadCache<V>
{
	private final long maxAgeMs;
	private final Map<String, Object[]> entries;

	ReadCache(final int pMaxEntries, final long pMaxAgeMs)
	{
		this.maxAgeMs = pMaxAgeMs;
		final int maxEntries = pMaxEntries;
		this.entries = new LinkedHashMap<String, Object[]>(16, 0.75f, true)
		{
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(final Map.Entry<String, Object[]> pEldest)
			{
				return this.size() > maxEntries;
			}
		};
	}

	/** The entry put under {@code pKey}, or null when none or when it is older than the limit (it is dropped then). */
	@SuppressWarnings("unchecked")
	V get(final String pKey, final long pNow)
	{
		synchronized (this.entries)
		{
			final Object[] entry = this.entries.get(pKey);
			if (entry == null)
			{
				return null;
			}
			if (pNow - ((Long) entry[1]).longValue() > this.maxAgeMs)
			{
				this.entries.remove(pKey);
				return null;
			}
			return (V) entry[0];
		}
	}

	void put(final String pKey, final V pValue, final long pNow)
	{
		synchronized (this.entries)
		{
			this.entries.put(pKey, new Object[] { pValue, Long.valueOf(pNow) });
		}
	}

	void clear()
	{
		synchronized (this.entries)
		{
			this.entries.clear();
		}
	}

	int size()
	{
		synchronized (this.entries)
		{
			return this.entries.size();
		}
	}
}
