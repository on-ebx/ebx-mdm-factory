package com.on.ebx.factory.quality;

/** Staging polish (2026-10-17): the bounded read cache. JUnit-free: run the main, a failure throws. */
public final class ReadCacheTest
{
	private static final class FakeClock implements ReadCache.Clock
	{
		long now = 1000L;

		@Override
		public long now()
		{
			return this.now;
		}
	}

	public static void main(final String[] pArgs)
	{
		ReadCacheTest.readOnceWhileTheTableIsUnchanged();
		ReadCacheTest.aWriteToTheTableMakesTheEntryUnusable();
		ReadCacheTest.anUnknownDateNeverCaches();
		ReadCacheTest.entriesExpire();
		ReadCacheTest.leastRecentlyUsedIsEvictedByWeight();
		ReadCacheTest.aValueHeavierThanTheCacheIsNotKept();
		ReadCacheTest.replacingAnEntryCorrectsTheWeight();
		System.out.println("ReadCacheTest OK");
	}

	/** The scenario of a proposal run: three passes ask the same column; the reader runs once. */
	private static void readOnceWhileTheTableIsUnchanged()
	{
		final ReadCache<String> cache = new ReadCache<String>(100L, 60000L, new FakeClock());
		int reads = 0;
		for (int pass = 0; pass < 3; pass++)
		{
			String value = cache.get("u|ds|Customer|lastName", 42L);
			if (value == null)
			{
				reads++;
				value = "values";
				cache.put("u|ds|Customer|lastName", 42L, value, 10L);
			}
		}
		check("read once, served twice", reads == 1 && cache.hits() == 2L && cache.misses() == 1L);
		check("another column is another entry", cache.get("u|ds|Customer|city", 42L) == null);
		check("another user is another entry", cache.get("v|ds|Customer|lastName", 42L) == null);
	}

	private static void aWriteToTheTableMakesTheEntryUnusable()
	{
		final ReadCache<String> cache = new ReadCache<String>(100L, 60000L, new FakeClock());
		cache.put("k", 42L, "old", 5L);
		check("same modification date: hit", "old".equals(cache.get("k", 42L)));
		check("the table was written (new date): miss", cache.get("k", 43L) == null);
		check("and the stale entry is gone, with its weight", cache.size() == 0 && cache.weight() == 0L);
	}

	private static void anUnknownDateNeverCaches()
	{
		final ReadCache<String> cache = new ReadCache<String>(100L, 60000L, new FakeClock());
		cache.put("k", -1L, "v", 5L);
		check("nothing stored for an unknown date", cache.size() == 0);
		cache.put("k", 7L, "v", 5L);
		check("and nothing served for an unknown date", cache.get("k", -1L) == null);
	}

	private static void entriesExpire()
	{
		final FakeClock clock = new FakeClock();
		final ReadCache<String> cache = new ReadCache<String>(100L, 600L, clock);
		cache.put("k", 1L, "v", 5L);
		clock.now += 600L;
		check("at the ttl: still there", "v".equals(cache.get("k", 1L)));
		clock.now += 1L;
		check("past the ttl: gone", cache.get("k", 1L) == null && cache.size() == 0);
	}

	private static void leastRecentlyUsedIsEvictedByWeight()
	{
		final ReadCache<String> cache = new ReadCache<String>(100L, 60000L, new FakeClock());
		cache.put("a", 1L, "A", 40L);
		cache.put("b", 1L, "B", 40L);
		check("a looked at last", "A".equals(cache.get("a", 1L)));
		cache.put("c", 1L, "C", 40L);
		check("b, the least recently used, made room", cache.get("b", 1L) == null && "A".equals(cache.get("a", 1L)) && "C".equals(cache.get("c", 1L)));
		check("the weight is bounded", cache.weight() <= 100L && cache.weight() == 80L);
	}

	private static void aValueHeavierThanTheCacheIsNotKept()
	{
		final ReadCache<String> cache = new ReadCache<String>(100L, 60000L, new FakeClock());
		cache.put("small", 1L, "s", 10L);
		cache.put("huge", 1L, "h", 101L);
		check("not kept, and nothing else evicted for it", cache.get("huge", 1L) == null && "s".equals(cache.get("small", 1L)));
	}

	private static void replacingAnEntryCorrectsTheWeight()
	{
		final ReadCache<String> cache = new ReadCache<String>(100L, 60000L, new FakeClock());
		cache.put("k", 1L, "v1", 30L);
		cache.put("k", 2L, "v2", 50L);
		check("one entry, the new weight", cache.size() == 1 && cache.weight() == 50L && "v2".equals(cache.get("k", 2L)));
		cache.clear();
		check("clear", cache.size() == 0 && cache.weight() == 0L);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError("ReadCacheTest: " + pWhat);
		}
	}
}
