package com.on.ebx.factory.wizard;

import java.util.Locale;

import com.on.ebx.factory.matching.MatchStats;

/**
 * JUnit-free: run the main, a failure throws. UX lot U1 (2026-10-13): the row of the "last matching run" table in one sentence, scope in its own column,
 * raw text and volume warning kept apart. The messages are the ones measured on project UxCrmSf.
 */
public final class MatchingResultRowTest
{
	private static final String FAILED_MESSAGE = "Matching run on Adresse FAILED: the add-on's process failed at 75.0 % - reason from ebx-addon-mame.log: [MatchingManager] Got exception "
		+ "in matching operation | [MatchingManager] Process Exception. Xref.goldenKey NOT refreshed. Scope: incremental - 30 of 81 records were pivots (the last loaded batch); "
		+ "the other 51 were not matched in this run. WARNING: The candidate search of this plan is NOT filtered: measured 9 times slower at 1 million rows.";
	private static final String DONE_MESSAGE = "Matching run on Personne finished (DONE); Xref.goldenKey refreshed (30 set, 0 cleared, 51 unchanged). Scope: incremental - 30 of 81 "
		+ "records were pivots (the last loaded batch); the other 51 were not matched in this run. WARNING: The candidate search of this plan is NOT filtered.";

	public static void main(final String[] pArgs)
	{
		MatchingResultRowTest.aFailedRunIsOneSentenceWithTheRawTextFolded();
		MatchingResultRowTest.aDoneRunShowsTheScopeInTheSentenceAndTheColumn();
		MatchingResultRowTest.aMergedRunSaysSo();
		MatchingResultRowTest.scopeAllRecords();
		MatchingResultRowTest.scopeFallsBackToTheStoredResults();
		MatchingResultRowTest.noScopeAndNoResultsIsJustFinished();
		MatchingResultRowTest.stoppedRun();
		System.out.println("MatchingResultRowTest OK");
	}

	private static MatchStats.Kpis kpis(final int pTotal, final int pPending, final int pMerged, final int pUnmatched)
	{
		return new MatchStats.Kpis(pTotal, new java.util.LinkedHashMap<String, Integer>(), pTotal, pMerged, pPending, 0, pUnmatched);
	}

	private static void aFailedRunIsOneSentenceWithTheRawTextFolded()
	{
		final MatchingResultRow row = MatchingResultRow.of(Locale.ENGLISH, "FAILED", MatchingResultRowTest.FAILED_MESSAGE, null);
		MatchingResultRowTest.check("sentence", "Matching failed: the Match and Merge add-on stopped with an internal error. Details below.".equals(row.sentence));
		MatchingResultRowTest.check("no log text in the sentence", !row.sentence.contains("MatchingManager"));
		MatchingResultRowTest.check("raw text kept for the fold", row.details.contains("MatchingManager"));
		MatchingResultRowTest.check("scope still in the column", "30 / 81".equals(row.compared));
		MatchingResultRowTest.check("no counts for a failed run", "–".equals(row.pending) && "–".equals(row.merged));
		MatchingResultRowTest.check("warning separated", row.warning.startsWith("The candidate search"));
	}

	private static void aDoneRunShowsTheScopeInTheSentenceAndTheColumn()
	{
		final MatchingResultRow row = MatchingResultRow.of(Locale.ENGLISH, "DONE", MatchingResultRowTest.DONE_MESSAGE, MatchingResultRowTest.kpis(81, 0, 0, 51));
		MatchingResultRowTest.check("the brief's sentence", "30 of 81 records compared, no pair to decide, nothing merged".equals(row.sentence));
		MatchingResultRowTest.check("compared column", "30 / 81".equals(row.compared));
		MatchingResultRowTest.check("counts", "0".equals(row.pending) && "0".equals(row.merged) && "0".equals(row.distinct));
	}

	private static void aMergedRunSaysSo()
	{
		final MatchingResultRow row = MatchingResultRow.of(Locale.ENGLISH, "DONE", "Scope: all records - the 120 records of the table were pivots.", MatchingResultRowTest.kpis(120, 4, 2, 0));
		MatchingResultRowTest.check("all compared, plural pairs and groups", "All 120 records compared, 4 pairs to decide, 2 groups merged".equals(row.sentence));
		MatchingResultRowTest.check("column", "120 / 120".equals(row.compared));
	}

	private static void scopeAllRecords()
	{
		final long[] scope = MatchingResultRow.scope("Scope: all records - the 1,250 records of the table were pivots.", null);
		MatchingResultRowTest.check("thousands separator read", scope[0] == 1250L && scope[1] == 1250L);
	}

	// no scope sentence in the step message: compared = records in the master minus those never matched
	private static void scopeFallsBackToTheStoredResults()
	{
		final long[] scope = MatchingResultRow.scope("finished", MatchingResultRowTest.kpis(81, 0, 0, 51));
		MatchingResultRowTest.check("30 of 81", scope[0] == 30L && scope[1] == 81L);
	}

	private static void noScopeAndNoResultsIsJustFinished()
	{
		final MatchingResultRow row = MatchingResultRow.of(Locale.ENGLISH, "DONE", "", null);
		MatchingResultRowTest.check("generic sentence", "Matching finished.".equals(row.sentence));
		MatchingResultRowTest.check("unknown scope", "–".equals(row.compared));
	}

	private static void stoppedRun()
	{
		final MatchingResultRow row = MatchingResultRow.of(Locale.ENGLISH, "STOPPED", "Matching run on X stopped (user).", null);
		MatchingResultRowTest.check("stopped sentence", row.sentence.startsWith("Matching was stopped"));
		MatchingResultRowTest.check("french exists", !MatchingResultRow.of(Locale.FRENCH, "FAILED", "x", null).sentence.startsWith("wizard."));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
