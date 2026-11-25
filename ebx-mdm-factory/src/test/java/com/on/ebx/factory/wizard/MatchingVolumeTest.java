package com.on.ebx.factory.wizard;

import com.on.ebx.factory.matching.MatchStats;

/** JUnit-free: run the main, a failure throws. UX lot U1 (2026-10-13): when the volume warnings of step 6 are shown inline. */
public final class MatchingVolumeTest
{
	public static void main(final String[] pArgs)
	{
		MatchingVolumeTest.thresholdIsFiftyThousandRows();
		MatchingVolumeTest.rowsComeFromTheStoredResultsFirst();
		MatchingVolumeTest.landingRowsSumTheSelectedTablesOfEverySource();
		System.out.println("MatchingVolumeTest OK");
	}

	private static void thresholdIsFiftyThousandRows()
	{
		MatchingVolumeTest.check("81 rows fold", !MatchingVolume.inline(81L));
		MatchingVolumeTest.check("49,999 fold", !MatchingVolume.inline(49999L));
		MatchingVolumeTest.check("50,000 inline", MatchingVolume.inline(50000L));
		MatchingVolumeTest.check("1 million inline", MatchingVolume.inline(1000000L));
	}

	private static void rowsComeFromTheStoredResultsFirst()
	{
		final MatchStats.Kpis kpis = new MatchStats.Kpis(81, new java.util.LinkedHashMap<String, Integer>(), 81, 0, 0, 0, 0);
		MatchingVolumeTest.check("kpis win", MatchingVolume.rowsFor(kpis, 900000L) == 81L);
		MatchingVolumeTest.check("no results: landing rows", MatchingVolume.rowsFor(null, 900000L) == 900000L);
	}

	private static void landingRowsSumTheSelectedTablesOfEverySource()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		snapshot.sources.add(source);
		source.tables.add(MatchingVolumeTest.table(true, true, 40000L, 0L, true));
		source.tables.add(MatchingVolumeTest.table(true, false, 0L, 15000L, true));
		source.tables.add(MatchingVolumeTest.table(true, true, 999999L, 0L, false));
		MatchingVolumeTest.check("profiled + last load, unselected ignored", MatchingVolume.landingRows(snapshot) == 55000L);
		MatchingVolumeTest.check("so the warnings show", MatchingVolume.inline(MatchingVolume.landingRows(snapshot)));
	}

	private static WizardSnapshot.TableInfo table(final boolean pSelected, final boolean pKnown, final long pSourceRows, final long pLoadRows, final boolean pKeep)
	{
		final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
		table.selected = pKeep && pSelected;
		table.sourceRowCountKnown = pKnown;
		table.sourceRowCount = pSourceRows;
		table.lastLoadRows = pLoadRows;
		return table;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
