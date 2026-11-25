package com.on.ebx.factory.ebx;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Audit F2-100 (2026-10-24): the Mapping matrix of a project, kept between the renders of the wizard's step 4, the Validation desk and the matrix itself.
 * The key holds everything the Factory tables say (their {@code getLastModificationDate()}, CLAUDE.md section 9, plus the project, the user and the locale);
 * what lives outside them - the landing tables a sample reads, the staging table a computed field is measured on - is listed with the entry
 * ({@code externals}) and re-checked on every hit, so a load or a landing write is seen as well. Bounded in entries and in age ({@link ReadCache}).
 * <p>
 * A cached value is shared between requests and must never be mutated by its readers (the matrix model is built immutable).
 */
final class MatrixReadCache<V>
{
	/** What was read and what it depended on outside the Factory's own tables: reference (an opaque string the caller can resolve) -&gt; the token it had. */
	private static final class Entry<V>
	{
		final V value;
		final Map<String, String> externalTokens;

		Entry(final V pValue, final Map<String, String> pExternalTokens)
		{
			this.value = pValue;
			this.externalTokens = pExternalTokens;
		}
	}

	private final ReadCache<Entry<V>> cache;
	private final java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.concurrent.atomic.AtomicInteger misses = new java.util.concurrent.atomic.AtomicInteger();

	MatrixReadCache(final int pMaxEntries, final long pMaxAgeMs)
	{
		this.cache = new ReadCache<Entry<V>>(pMaxEntries, pMaxAgeMs);
	}

	/** The value cached under {@code pKey} when every external reference still has its token ({@code pTokenOf} resolves a reference; null = unreadable = a miss), else null. */
	V get(final String pKey, final Function<String, String> pTokenOf, final long pNow)
	{
		final Entry<V> entry = pKey == null ? null : this.cache.get(pKey, pNow);
		if (entry == null)
		{
			this.misses.incrementAndGet();
			return null;
		}
		for (final Map.Entry<String, String> external : entry.externalTokens.entrySet())
		{
			final String token = pTokenOf.apply(external.getKey());
			if (token == null || !token.equals(external.getValue()))
			{
				this.misses.incrementAndGet();
				return null;
			}
		}
		this.hits.incrementAndGet();
		return entry.value;
	}

	/** Keeps a COMPLETE read; {@code pExternals} are the references the read depended on, tokened now by {@code pTokenOf}. A reference with no token stops the caching. */
	void put(final String pKey, final V pValue, final Set<String> pExternals, final Function<String, String> pTokenOf, final long pNow)
	{
		if (pKey == null)
		{
			return;
		}
		final Map<String, String> tokens = new LinkedHashMap<String, String>();
		for (final String external : pExternals)
		{
			final String token = pTokenOf.apply(external);
			if (token == null)
			{
				return;
			}
			tokens.put(external, token);
		}
		this.cache.put(pKey, new Entry<V>(pValue, tokens), pNow);
	}

	int hits()
	{
		return this.hits.get();
	}

	int misses()
	{
		return this.misses.get();
	}

	int size()
	{
		return this.cache.size();
	}

	void clear()
	{
		this.cache.clear();
	}
}
