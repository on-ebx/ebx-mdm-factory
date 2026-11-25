package com.on.ebx.factory.distribution;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class ArmedTargetCacheTest
{
	public static void main(final String[] pArgs)
	{
		ArmedTargetCacheTest.missOnEmptyCache();
		ArmedTargetCacheTest.hitReturnsThePutValue();
		ArmedTargetCacheTest.expiresAfterTtl();
		ArmedTargetCacheTest.stillLiveJustBeforeTtl();
		ArmedTargetCacheTest.invalidateForcesAMiss();
		ArmedTargetCacheTest.projectsAreIndependent();
		ArmedTargetCacheTest.nullProjectIdIsAlwaysAMissAndNeverStored();
		System.out.println("ArmedTargetCacheTest OK");
	}

	private static void missOnEmptyCache()
	{
		ArmedTargetCache.invalidateAll();
		ArmedTargetCacheTest.check("no entry at all -> miss", ArmedTargetCache.get("P1", 0L) == null);
	}

	private static void hitReturnsThePutValue()
	{
		ArmedTargetCache.invalidateAll();
		ArmedTargetCache.put("P1", true, 1000L);
		ArmedTargetCacheTest.check("cached true comes back true", Boolean.TRUE.equals(ArmedTargetCache.get("P1", 1000L)));
		ArmedTargetCache.put("P1", false, 2000L);
		ArmedTargetCacheTest.check("a later put overwrites the earlier value", Boolean.FALSE.equals(ArmedTargetCache.get("P1", 2000L)));
	}

	private static void expiresAfterTtl()
	{
		ArmedTargetCache.invalidateAll();
		ArmedTargetCache.put("P1", true, 0L);
		ArmedTargetCacheTest.check("expired exactly at the TTL boundary -> miss", ArmedTargetCache.get("P1", ArmedTargetCache.TTL_MILLIS) == null);
		ArmedTargetCacheTest.check("well past the TTL -> miss", ArmedTargetCache.get("P1", ArmedTargetCache.TTL_MILLIS * 10L) == null);
	}

	private static void stillLiveJustBeforeTtl()
	{
		ArmedTargetCache.invalidateAll();
		ArmedTargetCache.put("P1", true, 0L);
		ArmedTargetCacheTest.check("one millisecond before the TTL -> still a hit", Boolean.TRUE.equals(ArmedTargetCache.get("P1", ArmedTargetCache.TTL_MILLIS - 1L)));
	}

	private static void invalidateForcesAMiss()
	{
		ArmedTargetCache.invalidateAll();
		ArmedTargetCache.put("P1", true, 0L);
		ArmedTargetCache.invalidate("P1");
		ArmedTargetCacheTest.check("invalidate forces a miss even well within the TTL", ArmedTargetCache.get("P1", 1L) == null);
		// idempotent / never throws on a project that was never cached
		ArmedTargetCache.invalidate("NEVER_CACHED");
	}

	private static void projectsAreIndependent()
	{
		ArmedTargetCache.invalidateAll();
		ArmedTargetCache.put("P1", true, 0L);
		ArmedTargetCache.put("P2", false, 0L);
		ArmedTargetCacheTest.check("P1 unaffected by P2's value", Boolean.TRUE.equals(ArmedTargetCache.get("P1", 0L)));
		ArmedTargetCacheTest.check("P2 unaffected by P1's value", Boolean.FALSE.equals(ArmedTargetCache.get("P2", 0L)));
		ArmedTargetCache.invalidate("P1");
		ArmedTargetCacheTest.check("invalidating P1 leaves P2 cached", Boolean.FALSE.equals(ArmedTargetCache.get("P2", 0L)));
	}

	private static void nullProjectIdIsAlwaysAMissAndNeverStored()
	{
		ArmedTargetCache.invalidateAll();
		ArmedTargetCache.put(null, true, 0L);
		ArmedTargetCacheTest.check("a null project id is never cached", ArmedTargetCache.get(null, 0L) == null);
		ArmedTargetCache.invalidate(null); // must not throw
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
