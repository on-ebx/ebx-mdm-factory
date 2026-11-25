package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.landing.LandingReadiness.Table;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. */
public final class LandingReadinessTest
{
	public static void main(final String[] pArgs)
	{
		LandingReadinessTest.noTablesIsNotReady();
		LandingReadinessTest.oneSelectedLoadedIsReady();
		LandingReadinessTest.oneSelectedNotLoadedIsNotReady();
		LandingReadinessTest.unselectedTableNeverBlocks();
		LandingReadinessTest.allSelectedMustBeLoaded();
		System.out.println("LandingReadinessTest OK");
	}

	private static void noTablesIsNotReady()
	{
		LandingReadinessTest.check("no tables at all is not ready", !LandingReadiness.allTickedTablesLoaded(new ArrayList<Table>()));
	}

	private static void oneSelectedLoadedIsReady()
	{
		final List<Table> tables = new ArrayList<Table>();
		tables.add(new Table(true, true));
		LandingReadinessTest.check("one selected, loaded table is ready", LandingReadiness.allTickedTablesLoaded(tables));
	}

	private static void oneSelectedNotLoadedIsNotReady()
	{
		final List<Table> tables = new ArrayList<Table>();
		tables.add(new Table(true, false));
		LandingReadinessTest.check("one selected, not-loaded table is not ready", !LandingReadiness.allTickedTablesLoaded(tables));
	}

	/** DistribTest live incident, 2026-09-26: a source can carry technical/unselected tables that are never loaded - they must never block the checklist. */
	private static void unselectedTableNeverBlocks()
	{
		final List<Table> tables = new ArrayList<Table>();
		tables.add(new Table(true, true));
		tables.add(new Table(false, false));
		LandingReadinessTest.check("an unselected table never blocks readiness", LandingReadiness.allTickedTablesLoaded(tables));
	}

	private static void allSelectedMustBeLoaded()
	{
		final List<Table> tables = new ArrayList<Table>();
		tables.add(new Table(true, true));
		tables.add(new Table(true, false));
		LandingReadinessTest.check("every selected table must be loaded, not just one", !LandingReadiness.allTickedTablesLoaded(tables));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
