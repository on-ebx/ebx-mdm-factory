package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Audit F2-110: the normalisation cache (up to 400,000 values per project) is released on every way out of a proposal run - an
 * exception and an Error included - and is bounded: a cache older than the age limit is dropped by any later access, and at most {@link NormalisationSuggester#CACHE_MAX_PROJECTS}
 * projects hold one.
 */
public final class NormalisationCacheReleaseTest
{
	public static void main(final String[] pArgs)
	{
		NormalisationCacheReleaseTest.releasedWhenTheBodyThrows();
		NormalisationCacheReleaseTest.releasedWhenTheBodyReturns();
		NormalisationCacheReleaseTest.releasedWhenTheBodyThrowsAnError();
		NormalisationCacheReleaseTest.staleCachesOfOtherProjectsAreDropped();
		NormalisationCacheReleaseTest.theNumberOfCachesIsBounded();
		System.out.println("NormalisationCacheReleaseTest OK");
	}

	private static void releasedWhenTheBodyThrows()
	{
		NormalisationSuggester.seedForTest("p-throw", 0L);
		final int[] released = { 0 };
		try
		{
			StagingProposalRun.releasingAfter(new StagingProposalRun.Body()
			{
				@Override
				public String run()
				{
					throw new IllegalStateException("the rule pass failed");
				}
			}, NormalisationCacheReleaseTest.release("p-throw", released));
			throw new AssertionError("the exception must reach the caller");
		}
		catch (final IllegalStateException expected)
		{
			NormalisationCacheReleaseTest.check("the exception is not swallowed", "the rule pass failed".equals(expected.getMessage()));
		}
		NormalisationCacheReleaseTest.check("released once after an exception", released[0] == 1);
		NormalisationCacheReleaseTest.check("the cache of the project is gone", NormalisationSuggester.cachedProjects() == 0);
	}

	private static void releasedWhenTheBodyReturns()
	{
		NormalisationSuggester.seedForTest("p-ok", 0L);
		final int[] released = { 0 };
		final String answer = StagingProposalRun.releasingAfter(new StagingProposalRun.Body()
		{
			@Override
			public String run()
			{
				return "done";
			}
		}, NormalisationCacheReleaseTest.release("p-ok", released));
		NormalisationCacheReleaseTest.check("the answer is kept and the cache released", "done".equals(answer) && released[0] == 1 && NormalisationSuggester.cachedProjects() == 0);
	}

	private static void releasedWhenTheBodyThrowsAnError()
	{
		NormalisationSuggester.seedForTest("p-error", 0L);
		final int[] released = { 0 };
		try
		{
			StagingProposalRun.releasingAfter(new StagingProposalRun.Body()
			{
				@Override
				public String run()
				{
					throw new StackOverflowError();
				}
			}, NormalisationCacheReleaseTest.release("p-error", released));
			throw new AssertionError("the error must reach the caller");
		}
		catch (final StackOverflowError expected)
		{
			NormalisationCacheReleaseTest.check("released after an Error", released[0] == 1 && NormalisationSuggester.cachedProjects() == 0);
		}
	}

	private static void staleCachesOfOtherProjectsAreDropped()
	{
		NormalisationSuggester.seedForTest("stale", 31L * 60L * 1000L);
		NormalisationSuggester.seedForTest("recent", 1000L);
		NormalisationSuggester.cache("another", false);
		NormalisationCacheReleaseTest.check("a cache older than 30 minutes goes at the next access of ANY project", NormalisationSuggester.cachedProjects() == 2);
		NormalisationSuggester.release("recent");
		NormalisationSuggester.release("another");
		NormalisationCacheReleaseTest.check("nothing left", NormalisationSuggester.cachedProjects() == 0);
	}

	private static void theNumberOfCachesIsBounded()
	{
		for (int i = 0; i < NormalisationSuggester.CACHE_MAX_PROJECTS + 4; i++)
		{
			NormalisationSuggester.seedForTest("seed" + i, (NormalisationSuggester.CACHE_MAX_PROJECTS + 4 - i) * 1000L);
			NormalisationSuggester.cache("p" + i, true);
		}
		NormalisationCacheReleaseTest.check("at most CACHE_MAX_PROJECTS caches after a series of fresh runs (" + NormalisationSuggester.cachedProjects() + ")",
			NormalisationSuggester.cachedProjects() <= NormalisationSuggester.CACHE_MAX_PROJECTS);
		for (int i = 0; i < NormalisationSuggester.CACHE_MAX_PROJECTS + 4; i++)
		{
			NormalisationSuggester.release("p" + i);
			NormalisationSuggester.release("seed" + i);
		}
		NormalisationCacheReleaseTest.check("cleaned", NormalisationSuggester.cachedProjects() == 0);
	}

	private static Runnable release(final String pProject, final int[] pCount)
	{
		return new Runnable()
		{
			@Override
			public void run()
			{
				pCount[0]++;
				NormalisationSuggester.release(pProject);
			}
		};
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
