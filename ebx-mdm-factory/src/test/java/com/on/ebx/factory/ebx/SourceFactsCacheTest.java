package com.on.ebx.factory.ebx;

import com.on.ebx.factory.wizard.SourceOnboarding;

/** JUnit-free: run the main, a failure throws. Wave 11 (audit F-48): the cache of the last facts read for a connection, with an injected clock. */
public final class SourceFactsCacheTest
{
	public static void main(final String[] pArgs)
	{
		SourceFactsCacheTest.nothingBeforeTheFirstRead();
		SourceFactsCacheTest.aReadIsKeptAndAgesWithTheClock();
		SourceFactsCacheTest.aNewReadReplacesTheOldOne();
		SourceFactsCacheTest.theOldestEntryGoesFirst();
		SourceFactsCacheTest.nothingIsKeptWithoutAKeyOrFacts();
		SourceFactsCacheTest.aViewerNeverSeesWhatAnotherSessionRead();
		SourceFactsCacheTest.theRenderPathNeverReadsLive();
		System.out.println("SourceFactsCacheTest OK");
	}

	private static final class FakeClock implements SourceFactsCache.Clock
	{
		long now = 1000L;

		@Override
		public long now()
		{
			return this.now;
		}
	}

	private static SourceOnboarding.Facts facts(final String pId)
	{
		final SourceOnboarding.Facts facts = new SourceOnboarding.Facts();
		facts.connectorId = pId;
		return facts;
	}

	private static void nothingBeforeTheFirstRead()
	{
		final SourceFactsCache cache = new SourceFactsCache(new FakeClock());
		SourceFactsCacheTest.check("empty", cache.get("A") == null && cache.size() == 0);
		SourceFactsCacheTest.check("fresh(null) is false, never a failure", !cache.fresh(null, 1000L));
		SourceFactsCacheTest.check("cachedFacts of a connection never read is null (the card then says checking)", SourceOnboarder.cachedFacts("alice", "NEVER_READ_" + System.nanoTime(), 15000L) == null);
	}

	private static void aReadIsKeptAndAgesWithTheClock()
	{
		final FakeClock clock = new FakeClock();
		final SourceFactsCache cache = new SourceFactsCache(clock);
		final SourceOnboarding.Facts a = SourceFactsCacheTest.facts("A");
		cache.put("A", a);
		SourceFactsCacheTest.check("the same facts come back", cache.get("A").facts == a);
		SourceFactsCacheTest.check("age 0 at once", cache.ageMillis(cache.get("A")) == 0L && cache.fresh(cache.get("A"), 15000L));
		clock.now += 15000L;
		SourceFactsCacheTest.check("exactly at the limit still fresh", cache.fresh(cache.get("A"), 15000L));
		clock.now += 1L;
		SourceFactsCacheTest.check("a millisecond past it is not, but the facts are still there to show", !cache.fresh(cache.get("A"), 15000L) && cache.get("A").facts == a);
		clock.now -= 60000L;
		SourceFactsCacheTest.check("a clock that went back never gives a negative age", cache.ageMillis(cache.get("A")) == 0L);
		SourceFactsCacheTest.check("the settled limit is longer than the running one", SourceOnboarder.CACHE_SETTLED_MAX_AGE_MS > SourceOnboarder.CACHE_RUNNING_MAX_AGE_MS);
	}

	private static void aNewReadReplacesTheOldOne()
	{
		final FakeClock clock = new FakeClock();
		final SourceFactsCache cache = new SourceFactsCache(clock);
		cache.put("A", SourceFactsCacheTest.facts("A"));
		clock.now += 20000L;
		final SourceOnboarding.Facts again = SourceFactsCacheTest.facts("A");
		cache.put("A", again);
		SourceFactsCacheTest.check("replaced and fresh again", cache.get("A").facts == again && cache.fresh(cache.get("A"), 15000L) && cache.size() == 1);
		cache.forget("A");
		SourceFactsCacheTest.check("forgotten", cache.get("A") == null);
	}

	private static void theOldestEntryGoesFirst()
	{
		final SourceFactsCache cache = new SourceFactsCache(new FakeClock());
		for (int i = 0; i < SourceFactsCache.MAX_ENTRIES + 5; i++)
		{
			cache.put("C" + i, SourceFactsCacheTest.facts("C" + i));
			if (i == 0)
			{
				cache.put("C0", SourceFactsCacheTest.facts("C0")); // read again: now the newest at that moment
			}
		}
		SourceFactsCacheTest.check("bounded", cache.size() == SourceFactsCache.MAX_ENTRIES);
		SourceFactsCacheTest.check("the oldest are gone", cache.get("C0") == null && cache.get("C1") == null && cache.get("C4") == null);
		SourceFactsCacheTest.check("the newest are kept", cache.get("C" + (SourceFactsCache.MAX_ENTRIES + 4)) != null && cache.get("C5") != null);
	}

	private static void nothingIsKeptWithoutAKeyOrFacts()
	{
		final SourceFactsCache cache = new SourceFactsCache(new FakeClock());
		cache.put(null, SourceFactsCacheTest.facts("x"));
		cache.put("", SourceFactsCacheTest.facts("x"));
		cache.put("A", null);
		SourceFactsCacheTest.check("nothing", cache.size() == 0);
	}

	/** Audit F-02 (catalog reads) kept by wave 11: facts hold what the reading session could see of the catalog, so a read is only ever shown to the user who made it. */
	private static void aViewerNeverSeesWhatAnotherSessionRead()
	{
		final String connector = "SHARED_" + System.nanoTime();
		final SourceOnboarding.Facts byJob = SourceFactsCacheTest.facts(connector);
		byJob.sourceExists = true;
		SourceOnboarder.remember("ebx-systemUser", connector, byJob); // what the onboarding job's system session read
		final SourceOnboarding.Facts byAdmin = SourceFactsCacheTest.facts(connector);
		SourceOnboarder.remember("admin", connector, byAdmin);
		SourceFactsCacheTest.check("a viewer without the catalog right sees nothing of either read", SourceOnboarder.cachedFacts("steward", connector, Long.MAX_VALUE) == null);
		SourceFactsCacheTest.check("each reader sees its own", SourceOnboarder.cachedFacts("ebx-systemUser", connector, Long.MAX_VALUE).facts == byJob
			&& SourceOnboarder.cachedFacts("admin", connector, Long.MAX_VALUE).facts == byAdmin);
		final SourceOnboarding.Facts bySteward = SourceFactsCacheTest.facts(connector); // the steward's own (restricted) read
		SourceOnboarder.remember("steward", connector, bySteward);
		SourceFactsCacheTest.check("then the steward sees theirs, not the others'", SourceOnboarder.cachedFacts("steward", connector, Long.MAX_VALUE).facts == bySteward);
		SourceFactsCacheTest.check("no session, no user: the empty id is a user of its own", SourceOnboarder.cachedFacts("", connector, Long.MAX_VALUE) == null
			&& SourceOnboarder.viewerId(null).isEmpty());
		SourceFactsCacheTest.check("the key separates user and connection", !SourceOnboarder.cacheKey("a", "bc").equals(SourceOnboarder.cacheKey("ab", "c")));
	}

	/** The render path's contract, read in the source: the card's facts come from the cache and a miss is a background request, never a call. */
	private static void theRenderPathNeverReadsLive()
	{
		final String source;
		try
		{
			source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"),
				"com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java")), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError(ex);
		}
		final int model = source.indexOf("WizardPage.Model model()");
		final int end = source.indexOf("private static String script", model) > 0 ? source.indexOf("static String labelsScript", model) : source.length();
		final String renderPath = source.substring(model, end);
		SourceFactsCacheTest.check("model() reads the cached facts", renderPath.contains("SourceOnboarder.cachedFacts(SourceOnboarder.viewerId(this.session)"));
		SourceFactsCacheTest.check("a miss or an old read is refreshed on a background thread", renderPath.contains("SourceOnboarder.refreshInBackground("));
		SourceFactsCacheTest.check("model() makes no live read of any connection", !renderPath.contains("SourceOnboarder.facts(") && !renderPath.contains("SourceOnboarder.connections(")
			&& !renderPath.contains("SourceOnboarder.applyCatalog(") && !renderPath.contains("SourceOnboarder.runScan("));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
