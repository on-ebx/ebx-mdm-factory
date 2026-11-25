package com.on.ebx.factory.landing;

import java.util.Arrays;

/** Lot L2 (2026-10-12): the bound of parallel landing loads and the schedule of a chain of sources. JUnit-free: run the main, a failure throws. */
public final class LandingParallelismTest
{
	public static void main(final String[] pArgs)
	{
		LandingParallelismTest.parseClampsAndDefaults();
		LandingParallelismTest.propertyIsReadAtEachCall();
		LandingParallelismTest.poolOnlyWhenItCanHelp();
		LandingParallelismTest.boundOneIsTheSequentialSum();
		LandingParallelismTest.neverMoreLoadsThanTheBound();
		LandingParallelismTest.vol1mFBaselineShape();
		LandingParallelismTest.preparationsStayOnOneThread();
		LandingParallelismTest.edgeCases();
		System.out.println("LandingParallelismTest OK");
	}

	private static void parseClampsAndDefaults()
	{
		LandingParallelismTest.check("absent = default 2", LandingParallelism.parse(null) == 2);
		LandingParallelismTest.check("blank = default", LandingParallelism.parse("  ") == 2);
		LandingParallelismTest.check("garbage = default", LandingParallelism.parse("many") == 2);
		LandingParallelismTest.check("1 = sequential", LandingParallelism.parse("1") == 1);
		LandingParallelismTest.check("0 -> 1", LandingParallelism.parse("0") == 1);
		LandingParallelismTest.check("negative -> 1", LandingParallelism.parse("-3") == 1);
		LandingParallelismTest.check("3 kept", LandingParallelism.parse(" 3 ") == 3);
		LandingParallelismTest.check("above the cap -> cap", LandingParallelism.parse("50") == LandingParallelism.MAX);
		LandingParallelismTest.check("cap is below the bridge's 8 threads", LandingParallelism.MAX <= 4);
	}

	private static void propertyIsReadAtEachCall()
	{
		final String previous = System.getProperty(LandingParallelism.PROPERTY);
		try
		{
			System.clearProperty(LandingParallelism.PROPERTY);
			LandingParallelismTest.check("default without property", LandingParallelism.bound() == 2);
			System.setProperty(LandingParallelism.PROPERTY, "1");
			LandingParallelismTest.check("1", LandingParallelism.bound() == 1);
			System.setProperty(LandingParallelism.PROPERTY, "3");
			LandingParallelismTest.check("3 without restart", LandingParallelism.bound() == 3);
		}
		finally
		{
			if (previous == null)
			{
				System.clearProperty(LandingParallelism.PROPERTY);
			}
			else
			{
				System.setProperty(LandingParallelism.PROPERTY, previous);
			}
		}
	}

	private static void poolOnlyWhenItCanHelp()
	{
		LandingParallelismTest.check("one source, bound 2: inline", !LandingParallelism.usesPool(1, 2));
		LandingParallelismTest.check("three sources, bound 1: inline", !LandingParallelism.usesPool(3, 1));
		LandingParallelismTest.check("three sources, bound 2: pool", LandingParallelism.usesPool(3, 2));
		LandingParallelismTest.check("no source: inline", !LandingParallelism.usesPool(0, 4));
	}

	private static void boundOneIsTheSequentialSum()
	{
		final long[] prepare = { 20, 30, 10 };
		final long[] load = { 282, 669, 171 };
		final LandingParallelism.Schedule one = LandingParallelism.schedule(prepare, load, 1);
		LandingParallelismTest.check("bound 1 = sum of everything", one.makespan == 20 + 282 + 30 + 669 + 10 + 171);
		LandingParallelismTest.check("source 2 loads after source 1 ended", one.loadStart[1] == one.loadEnd[0] + 30);
		LandingParallelismTest.check("one load at a time", one.maxConcurrentLoads == 1);
		final LandingParallelism.Schedule single = LandingParallelism.schedule(new long[] { 5 }, new long[] { 100 }, 4);
		LandingParallelismTest.check("a single source never pools", single.makespan == 105 && single.maxConcurrentLoads == 1);
	}

	private static void neverMoreLoadsThanTheBound()
	{
		final long[] prepare = { 5, 5, 5, 5, 5, 5 };
		final long[] load = { 100, 100, 100, 100, 100, 100 };
		for (int bound = 1; bound <= LandingParallelism.MAX; bound++)
		{
			final LandingParallelism.Schedule schedule = LandingParallelism.schedule(prepare, load, bound);
			LandingParallelismTest.check("bound " + bound + " respected (saw " + schedule.maxConcurrentLoads + ")", schedule.maxConcurrentLoads <= bound);
			for (int i = 0; i < load.length; i++)
			{
				LandingParallelismTest.check("a load never starts before its preparation ended", schedule.loadStart[i] >= 5L * (i + 1));
				LandingParallelismTest.check("a load lasts its own length", schedule.loadEnd[i] - schedule.loadStart[i] == load[i]);
			}
		}
		final LandingParallelism.Schedule two = LandingParallelism.schedule(prepare, load, 2);
		final LandingParallelism.Schedule four = LandingParallelism.schedule(prepare, load, 4);
		LandingParallelismTest.check("a larger bound is never slower", four.makespan <= two.makespan);
		LandingParallelismTest.check("two beats one", two.makespan < LandingParallelism.schedule(prepare, load, 1).makespan);
	}

	/** The shape measured on project Vol1mF (2026-10-04): loads of 282 + 669 + 171 s in a 1 190 s chain, i.e. ~68 s of everything else. The model, not a measurement. */
	private static void vol1mFBaselineShape()
	{
		final long[] prepare = { 23, 23, 22 };
		final long[] load = { 282, 669, 171 };
		final LandingParallelism.Schedule sequential = LandingParallelism.schedule(prepare, load, 1);
		LandingParallelismTest.check("baseline close to the measured 1190 s", sequential.makespan == 1190L);
		final LandingParallelism.Schedule two = LandingParallelism.schedule(prepare, load, 2);
		// B (the 669 s one) starts at 46 s and ends at 715 s; A (282) then C (171) fit in the other slot
		LandingParallelismTest.check("bound 2: the longest source decides (46 + 669)", two.makespan == 715L);
		LandingParallelismTest.check("bound 2: C waits for A's slot", two.loadStart[2] == two.loadEnd[0]);
		final LandingParallelism.Schedule three = LandingParallelism.schedule(prepare, load, 3);
		LandingParallelismTest.check("bound 3 cannot beat the longest source", three.makespan == 715L);
	}

	/** The model/flows of two sources never overlap: the preparation clock only moves forward by one preparation at a time. */
	private static void preparationsStayOnOneThread()
	{
		final long[] prepare = { 40, 40, 40 };
		final long[] load = { 1, 1, 1 };
		final LandingParallelism.Schedule schedule = LandingParallelism.schedule(prepare, load, 4);
		LandingParallelismTest.check("load 3 cannot start before the three preparations (120)", schedule.loadStart[2] == 120L);
		LandingParallelismTest.check("makespan = last preparation + its load", schedule.makespan == 121L);
	}

	private static void edgeCases()
	{
		final LandingParallelism.Schedule empty = LandingParallelism.schedule(new long[0], new long[0], 2);
		LandingParallelismTest.check("no source, no time", empty.makespan == 0L && empty.maxConcurrentLoads == 0);
		LandingParallelismTest.check("sanity of Arrays", Arrays.equals(new long[] { 1 }, new long[] { 1 }));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
