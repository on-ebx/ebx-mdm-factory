package com.on.ebx.factory.master;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link ParentKeyCache} (volume lot 2a, 2026-09-28, task 3/8). */
public final class ParentKeyCacheTest
{
	public static void main(final String[] pArgs)
	{
		ParentKeyCacheTest.missReadsNull();
		ParentKeyCacheTest.roundTrip();
		ParentKeyCacheTest.notFoundSentinelIsDistinctFromAMiss();
		ParentKeyCacheTest.sameStagingRefDifferentEntityNamesDoNotCollide();
		ParentKeyCacheTest.boundedEvictsTheLeastRecentlyUsed();
		ParentKeyCacheTest.aReadRefreshesRecency();
		ParentKeyCacheTest.nonPositiveMaxEntriesIsFlooredAtOne();
		System.out.println("ParentKeyCacheTest OK");
	}

	private static void missReadsNull()
	{
		final ParentKeyCache cache = new ParentKeyCache();
		ParentKeyCacheTest.check("a key never put reads null", cache.get("Personne", "SAP|4711") == null);
	}

	private static void roundTrip()
	{
		final ParentKeyCache cache = new ParentKeyCache();
		cache.put("Personne", "SAP|4711", new ParentKeyCache.Value("322", true));
		final ParentKeyCache.Value value = cache.get("Personne", "SAP|4711");
		ParentKeyCacheTest.check("the exact value put comes back", value != null && "322".equals(value.masterKey) && value.redirected);
		ParentKeyCacheTest.check("size reflects the one entry", cache.size() == 1);
	}

	private static void notFoundSentinelIsDistinctFromAMiss()
	{
		final ParentKeyCache cache = new ParentKeyCache();
		cache.put("Personne", "SAP|9999", ParentKeyCache.Value.NOT_FOUND);
		final ParentKeyCache.Value value = cache.get("Personne", "SAP|9999");
		ParentKeyCacheTest.check("a cached NOT_FOUND is a real (non-null) cache hit", value != null);
		ParentKeyCacheTest.check("NOT_FOUND carries a null master key", value.masterKey == null);
		ParentKeyCacheTest.check("NOT_FOUND is never a redirection", !value.redirected);
		ParentKeyCacheTest.check("an entirely different key is still a genuine miss (null)", cache.get("Personne", "SAP|OTHER") == null);
	}

	/** A staging ref can itself embed the PrimaryKey "|" separator (CLAUDE.md sec 9) - string concatenation of (entityName, ref) could collide across different pairs; the dedicated Key class must not. */
	private static void sameStagingRefDifferentEntityNamesDoNotCollide()
	{
		final ParentKeyCache cache = new ParentKeyCache();
		// "A|B" + "C" and "A" + "B|C" would concatenate to the identical string "A|B|C" - the dedicated Key
		// class (entityName, stagingRef) as two separate fields must still tell them apart.
		cache.put("A|B", "C", new ParentKeyCache.Value("first", false));
		cache.put("A", "B|C", new ParentKeyCache.Value("second", false));
		ParentKeyCacheTest.check("two entries stored, not collapsed into one", cache.size() == 2);
		ParentKeyCacheTest.check("the first pair reads its own value", "first".equals(cache.get("A|B", "C").masterKey));
		ParentKeyCacheTest.check("the second pair reads its own, different value", "second".equals(cache.get("A", "B|C").masterKey));
	}

	private static void boundedEvictsTheLeastRecentlyUsed()
	{
		final ParentKeyCache cache = new ParentKeyCache(3);
		cache.put("E", "1", new ParentKeyCache.Value("m1", false));
		cache.put("E", "2", new ParentKeyCache.Value("m2", false));
		cache.put("E", "3", new ParentKeyCache.Value("m3", false));
		ParentKeyCacheTest.check("at capacity: all three present", cache.size() == 3);
		cache.put("E", "4", new ParentKeyCache.Value("m4", false));
		ParentKeyCacheTest.check("adding a fourth entry stays bounded at the configured max", cache.size() == 3);
		ParentKeyCacheTest.check("the least recently used entry (\"1\", never re-read) was evicted", cache.get("E", "1") == null);
		ParentKeyCacheTest.check("the newest entry is present", cache.get("E", "4") != null);
	}

	private static void aReadRefreshesRecency()
	{
		final ParentKeyCache cache = new ParentKeyCache(3);
		cache.put("E", "1", new ParentKeyCache.Value("m1", false));
		cache.put("E", "2", new ParentKeyCache.Value("m2", false));
		cache.put("E", "3", new ParentKeyCache.Value("m3", false));
		// re-read "1" - it is now the MOST recently used, so "2" becomes the least recently used instead
		ParentKeyCacheTest.check("re-reading \"1\" still finds it", cache.get("E", "1") != null);
		cache.put("E", "4", new ParentKeyCache.Value("m4", false));
		ParentKeyCacheTest.check("\"1\" survives (it was just accessed)", cache.get("E", "1") != null);
		ParentKeyCacheTest.check("\"2\" is the one evicted instead, since it was not re-read", cache.get("E", "2") == null);
	}

	private static void nonPositiveMaxEntriesIsFlooredAtOne()
	{
		final ParentKeyCache zero = new ParentKeyCache(0);
		zero.put("E", "1", new ParentKeyCache.Value("m1", false));
		zero.put("E", "2", new ParentKeyCache.Value("m2", false));
		ParentKeyCacheTest.check("a max of 0 is floored at 1, never zero/unbounded/an exception", zero.size() == 1);

		final ParentKeyCache negative = new ParentKeyCache(-5);
		negative.put("E", "1", new ParentKeyCache.Value("m1", false));
		negative.put("E", "2", new ParentKeyCache.Value("m2", false));
		ParentKeyCacheTest.check("a negative max behaves the same as 0 (floored at 1)", negative.size() == 1);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
