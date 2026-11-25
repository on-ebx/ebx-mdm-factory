package com.on.ebx.factory.master;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU cache of resolved parent master keys, keyed by {@code (targetEntityName, stagingRef)} - pulled
 * out of {@code ebx.MasterLoader.ParentKeys} (volume lot 2a, 2026-09-28, docs/VOLUME.md, task 3/8) purely so
 * its eviction and key-equality behaviour can be unit-tested without EBX.
 * <p>
 * <b>Why bounded, and by how much.</b> A {@link Key} holds two short strings (~40-80 bytes each including
 * object overhead for a typical formatted key) and a {@link Value} one more (~40-80 bytes), plus ~48 bytes of
 * {@code LinkedHashMap.Entry} overhead (two link pointers for the access-order list, on top of the hash-bucket
 * entry) - call it roughly 250-400 bytes per entry. At {@link #DEFAULT_MAX_ENTRIES} (200 000) that is at most
 * ~50-80 MB - a deliberate, documented trade-off rather than an unbounded map that could grow with the number
 * of DISTINCT parent references in a single load (never with the number of CHILD rows, which is exactly what
 * this cache exists to avoid re-paying for on every fan-out).
 */
public final class ParentKeyCache
{
	public static final int DEFAULT_MAX_ENTRIES = 200000;

	/** {@code (targetEntityName, stagingRef)} - a dedicated key class rather than string concatenation: a staging key can itself embed the {@code "|"} {@code PrimaryKey} separator (CLAUDE.md sec 9), so two different (entity, ref) pairs could otherwise collide on the same concatenated string. */
	public static final class Key
	{
		public final String entityName;
		public final String stagingRef;

		public Key(final String pEntityName, final String pStagingRef)
		{
			this.entityName = pEntityName;
			this.stagingRef = pStagingRef;
		}

		@Override
		public boolean equals(final Object pOther)
		{
			if (!(pOther instanceof Key))
			{
				return false;
			}
			final Key other = (Key) pOther;
			return this.entityName.equals(other.entityName) && this.stagingRef.equals(other.stagingRef);
		}

		@Override
		public int hashCode()
		{
			return (31 * this.entityName.hashCode()) + this.stagingRef.hashCode();
		}
	}

	/** A cached resolution: the master key to write ({@code null} for "not found"), and whether it was a redirection to a golden record (so a caller's own counters still count correctly on a cache hit). */
	public static final class Value
	{
		public static final Value NOT_FOUND = new Value(null, false);

		public final String masterKey;
		public final boolean redirected;

		public Value(final String pMasterKey, final boolean pRedirected)
		{
			this.masterKey = pMasterKey;
			this.redirected = pRedirected;
		}
	}

	private final int maxEntries;
	private final Map<Key, Value> cache;

	public ParentKeyCache()
	{
		this(ParentKeyCache.DEFAULT_MAX_ENTRIES);
	}

	public ParentKeyCache(final int pMaxEntries)
	{
		this.maxEntries = pMaxEntries > 0 ? pMaxEntries : 1;
		this.cache = new LinkedHashMap<Key, Value>(16, 0.75f, true)
		{
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(final Map.Entry<Key, Value> pEldest)
			{
				return this.size() > ParentKeyCache.this.maxEntries;
			}
		};
	}

	/** {@code null} on a cache miss - never {@link Value#NOT_FOUND} implicitly confused with "not cached yet". */
	public Value get(final String pEntityName, final String pStagingRef)
	{
		return this.cache.get(new Key(pEntityName, pStagingRef));
	}

	public void put(final String pEntityName, final String pStagingRef, final Value pValue)
	{
		this.cache.put(new Key(pEntityName, pStagingRef), pValue);
	}

	public int size()
	{
		return this.cache.size();
	}
}
