package com.on.ebx.factory.wizard;

import com.on.ebx.factory.matching.MatchStats;

/**
 * UX lot U1 (2026-10-13, RAPPORT 2.11 / 3.5): the matching plan cards and the results used to open with performance warnings that only matter at volume
 * - "Candidate search is NOT filtered ... measured 9 times slower ... at 1 million rows", "AUTOMATIC MERGE IS OFF" - on a project of 81 rows. They are shown
 * inline only when the master table is big enough for them to matter ({@link #INLINE_ROWS}); below it they sit under a "Details" disclosure, still one click away.
 * <p>
 * The row count comes from what the page already holds: the stored matching results of the entity (records in the master) when it has run, otherwise the
 * rows the project's landing tables loaded - an upper bound of any master table built from them, so a warning is never hidden for a project that is
 * big. Pure: no EBX class.
 */
public final class MatchingVolume
{
	/** From this many rows the volume warnings are shown inline. */
	public static final long INLINE_ROWS = 50000L;

	private MatchingVolume()
	{
	}

	public static boolean inline(final long pRows)
	{
		return pRows >= MatchingVolume.INLINE_ROWS;
	}

	/** Rows loaded by the landing tables of every source of the project (the table row count known to the wizard). */
	public static long landingRows(final WizardSnapshot pSnapshot)
	{
		long total = 0L;
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (!table.selected)
				{
					continue;
				}
				final TableRowCount count = TableRowCount.of(table.sourceRowCountKnown, table.sourceRowCount, table.lastLoadRows);
				total += count.known ? count.value : 0L;
			}
		}
		return total;
	}

	/** The row count to judge an entity's warnings by: its stored results when it has some, else the landing rows of the project. */
	public static long rowsFor(final MatchStats.Kpis pKpis, final long pLandingRows)
	{
		return pKpis != null && pKpis.totalRecords > 0 ? pKpis.totalRecords : pLandingRows;
	}
}
