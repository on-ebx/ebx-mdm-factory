package com.on.ebx.factory.quality;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Staging polish (2026-10-17, docs/UX-STAGING-POLISH.md sec 2): a small, bounded, thread-safe cache of column reads. A proposal run asks the same landing
 * column several times (the normalisation pass, the staging pass' shapes and typed checks, the capability pass); each entry is stored with a STAMP - the
 * table's last-modification date - and is served only while the stamp is the same, so a load that touches the table makes the entry unusable at once
 * (it is dropped on the next look). Bounded in total WEIGHT (the number of values held) with least-recently-used eviction, and in age ({@code ttl}), so
 * nothing stays in heap after the run that read it. Pure: no EBX class; the clock is injected for the tests.
 */
public final class ReadCache<V>
{
	/** The time source. */
	public interface Clock
	{
		long now();
	}

	private static final class Entry<V>
	{
		final V value;
		final long stamp;
		final long weight;
		final long storedAt;

		Entry(final V pValue, final long pStamp, final long pWeight, final long pStoredAt)
		{
			this.value = pValue;
			this.stamp = pStamp;
			this.weight = pWeight;
			this.storedAt = pStoredAt;
		}
	}

	private final long maxWeight;
	private final long ttlMillis;
	private final Clock clock;
	private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<String, Entry<V>>(16, 0.75f, true);
	private long weight;
	private long hits;
	private long misses;

	public ReadCache(final long pMaxWeight, final long pTtlMillis)
	{
		this(pMaxWeight, pTtlMillis, new Clock()
		{
			@Override
			public long now()
			{
				return System.currentTimeMillis();
			}
		});
	}

	public ReadCache(final long pMaxWeight, final long pTtlMillis, final Clock pClock)
	{
		this.maxWeight = pMaxWeight;
		this.ttlMillis = pTtlMillis;
		this.clock = pClock;
	}

	/** The value stored under {@code pKey} for the same {@code pStamp}, or null (absent, another stamp, expired). A negative stamp (the table's date is unknown) never hits. */
	public synchronized V get(final String pKey, final long pStamp)
	{
		final Entry<V> entry = this.entries.get(pKey);
		if (entry == null || pStamp < 0L)
		{
			this.misses++;
			return null;
		}
		if (entry.stamp != pStamp || this.clock.now() - entry.storedAt > this.ttlMillis)
		{
			this.remove(pKey);
			this.misses++;
			return null;
		}
		this.hits++;
		return entry.value;
	}

	/** Stores {@code pValue}; nothing is kept for a negative stamp or for a value heavier than the whole cache. */
	public synchronized void put(final String pKey, final long pStamp, final V pValue, final long pWeight)
	{
		this.remove(pKey);
		final long entryWeight = Math.max(1L, pWeight);
		if (pStamp < 0L || entryWeight > this.maxWeight)
		{
			return;
		}
		this.entries.put(pKey, new Entry<V>(pValue, pStamp, entryWeight, this.clock.now()));
		this.weight += entryWeight;
		final Iterator<Map.Entry<String, Entry<V>>> oldest = this.entries.entrySet().iterator();
		while (this.weight > this.maxWeight && oldest.hasNext())
		{
			final Map.Entry<String, Entry<V>> eldest = oldest.next();
			this.weight -= eldest.getValue().weight;
			oldest.remove();
		}
	}

	private void remove(final String pKey)
	{
		final Entry<V> removed = this.entries.remove(pKey);
		if (removed != null)
		{
			this.weight -= removed.weight;
		}
	}

	public synchronized void clear()
	{
		this.entries.clear();
		this.weight = 0L;
	}

	public synchronized int size()
	{
		return this.entries.size();
	}

	public synchronized long weight()
	{
		return this.weight;
	}

	public synchronized long hits()
	{
		return this.hits;
	}

	public synchronized long misses()
	{
		return this.misses;
	}
}
