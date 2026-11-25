package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.List;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * fix/matrix-computed-field: the call budget of {@link LibraryDocCache} - a render never makes more than one call
 * per library (two when the library changed), and most renders none.
 */
public final class LibraryDocCacheTest
{
	private static final String LIB = "library/Fasttrack/lib/phone";

	/** Scripted answers, and a log of every call made. */
	private static final class FakeFetcher implements LibraryDocCache.Fetcher
	{
		final List<String> calls = new ArrayList<String>();
		String revision = "r1";
		String source = "src-1";
		boolean fail;

		@Override
		public LibraryDocCache.Fetched fetch(final String pLibraryIdePath, final boolean pIncludeSource) throws Exception
		{
			this.calls.add(pLibraryIdePath + (pIncludeSource ? "+source" : ""));
			if (this.fail)
			{
				throw new IllegalStateException("scripting server not usable");
			}
			return new LibraryDocCache.Fetched(this.revision, pIncludeSource ? this.source : null);
		}
	}

	public static void main(final String[] pArgs)
	{
		LibraryDocCacheTest.firstLookupIsOneCallWithTheSource();
		LibraryDocCacheTest.repeatedRendersWithinTheFreshWindowMakeNoCall();
		LibraryDocCacheTest.anOldEntryIsRevalidatedWithOneMetadataCallAndKeptWhenTheRevisionIsTheSame();
		LibraryDocCacheTest.aNewRevisionReloadsTheSource();
		LibraryDocCacheTest.aFailureIsRememberedForTheFreshWindowAndADeadServerIsNotRetriedOnEveryClick();
		LibraryDocCacheTest.aFailureAfterAGoodEntryServesTheStaleSource();
		LibraryDocCacheTest.librariesAreCachedSeparately();
		System.out.println("LibraryDocCacheTest OK");
	}

	private static void firstLookupIsOneCallWithTheSource()
	{
		final FakeFetcher fetcher = new FakeFetcher();
		final LibraryDocCache cache = new LibraryDocCache(fetcher, 60000L);
		final LibraryDocCache.Lookup lookup = cache.lookup(LibraryDocCacheTest.LIB, 1000L);
		LibraryDocCacheTest.check("the source is returned", "src-1".equals(lookup.source) && lookup.problem.isEmpty());
		LibraryDocCacheTest.check("ONE call, with the source", fetcher.calls.size() == 1 && fetcher.calls.get(0).endsWith("+source"));
	}

	private static void repeatedRendersWithinTheFreshWindowMakeNoCall()
	{
		final FakeFetcher fetcher = new FakeFetcher();
		final LibraryDocCache cache = new LibraryDocCache(fetcher, 60000L);
		cache.lookup(LibraryDocCacheTest.LIB, 1000L);
		for (int i = 0; i < 10; i++)
		{
			cache.lookup(LibraryDocCacheTest.LIB, 1000L + i * 1000L);
		}
		LibraryDocCacheTest.check("ten more renders in the window: still the one call", fetcher.calls.size() == 1);
	}

	private static void anOldEntryIsRevalidatedWithOneMetadataCallAndKeptWhenTheRevisionIsTheSame()
	{
		final FakeFetcher fetcher = new FakeFetcher();
		final LibraryDocCache cache = new LibraryDocCache(fetcher, 60000L);
		cache.lookup(LibraryDocCacheTest.LIB, 0L);
		final LibraryDocCache.Lookup lookup = cache.lookup(LibraryDocCacheTest.LIB, 61000L);
		LibraryDocCacheTest.check("one more call, without the source", fetcher.calls.size() == 2 && !fetcher.calls.get(1).endsWith("+source"));
		LibraryDocCacheTest.check("same revision: the cached source is served", "src-1".equals(lookup.source));
		cache.lookup(LibraryDocCacheTest.LIB, 62000L);
		LibraryDocCacheTest.check("and its age restarted: the next render makes no call", fetcher.calls.size() == 2);
	}

	private static void aNewRevisionReloadsTheSource()
	{
		final FakeFetcher fetcher = new FakeFetcher();
		final LibraryDocCache cache = new LibraryDocCache(fetcher, 60000L);
		cache.lookup(LibraryDocCacheTest.LIB, 0L);
		fetcher.revision = "r2";
		fetcher.source = "src-2";
		final LibraryDocCache.Lookup lookup = cache.lookup(LibraryDocCacheTest.LIB, 61000L);
		LibraryDocCacheTest.check("metadata call, then the source call: two calls for the render", fetcher.calls.size() == 3 && fetcher.calls.get(2).endsWith("+source"));
		LibraryDocCacheTest.check("the new source is served", "src-2".equals(lookup.source));
	}

	private static void aFailureIsRememberedForTheFreshWindowAndADeadServerIsNotRetriedOnEveryClick()
	{
		final FakeFetcher fetcher = new FakeFetcher();
		fetcher.fail = true;
		final LibraryDocCache cache = new LibraryDocCache(fetcher, 60000L);
		final LibraryDocCache.Lookup first = cache.lookup(LibraryDocCacheTest.LIB, 0L);
		LibraryDocCacheTest.check("no source, a problem is said", first.source == null && first.problem.contains("not usable"));
		cache.lookup(LibraryDocCacheTest.LIB, 5000L);
		cache.lookup(LibraryDocCacheTest.LIB, 6000L);
		LibraryDocCacheTest.check("the failure is cached: one call only for three renders", fetcher.calls.size() == 1);
		fetcher.fail = false;
		final LibraryDocCache.Lookup later = cache.lookup(LibraryDocCacheTest.LIB, 61000L);
		LibraryDocCacheTest.check("after the window it is tried again, with the source (there was none)", "src-1".equals(later.source) && fetcher.calls.get(1).endsWith("+source"));
	}

	private static void aFailureAfterAGoodEntryServesTheStaleSource()
	{
		final FakeFetcher fetcher = new FakeFetcher();
		final LibraryDocCache cache = new LibraryDocCache(fetcher, 60000L);
		cache.lookup(LibraryDocCacheTest.LIB, 0L);
		fetcher.fail = true;
		final LibraryDocCache.Lookup lookup = cache.lookup(LibraryDocCacheTest.LIB, 61000L);
		LibraryDocCacheTest.check("a good entry is never replaced by a failure", "src-1".equals(lookup.source) && lookup.problem.isEmpty());
	}

	private static void librariesAreCachedSeparately()
	{
		final FakeFetcher fetcher = new FakeFetcher();
		final LibraryDocCache cache = new LibraryDocCache(fetcher, 60000L);
		cache.lookup("library/a", 0L);
		cache.lookup("library/b", 0L);
		cache.lookup("library/a", 1L);
		cache.lookup("library/b", 1L);
		LibraryDocCacheTest.check("one call per library", fetcher.calls.size() == 2);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
