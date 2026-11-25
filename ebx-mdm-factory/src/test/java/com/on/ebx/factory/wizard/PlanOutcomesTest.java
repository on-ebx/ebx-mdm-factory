package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free: run the main, a failure throws. Exercises {@link PlanOutcomes} (UX lot U2, 2026-10-13): a plan that writes nothing says why. */
public final class PlanOutcomesTest
{
	public static void main(final String[] pArgs)
	{
		PlanOutcomesTest.somethingPlannedKeepsTheCounts();
		PlanOutcomesTest.salesforceAsMeasuredNamesTheObjectAndTheGesture();
		PlanOutcomesTest.manyObjectsAreCut();
		PlanOutcomesTest.aRescanThatRefreshedATableIsNotNothingToDo();
		PlanOutcomesTest.alreadyPlannedIsNothingToDoButNotAnError();
		PlanOutcomesTest.noObjectAtAllSaysScanAgain();
		PlanOutcomesTest.changedTablesDoNotAskForAScan();
		PlanOutcomesTest.unmatchedSchemaFilterIsNamed();
		PlanOutcomesTest.reasonsAndNames();
		System.out.println("PlanOutcomesTest OK");
	}

	private static List<PlanOutcomes.Line> lines(final String... pPairs)
	{
		final List<PlanOutcomes.Line> out = new ArrayList<PlanOutcomes.Line>();
		for (int i = 0; i < pPairs.length; i += 2)
		{
			out.add(new PlanOutcomes.Line(pPairs[i], pPairs[i + 1]));
		}
		return out;
	}

	private static void somethingPlannedKeepsTheCounts()
	{
		final String message = PlanOutcomes.jobMessage(17, 0, PlanOutcomesTest.lines("Account", "PLANNED"), null);
		PlanOutcomesTest.check("counts", "17 table(s) planned, 0 already planned.".equals(message));
		PlanOutcomesTest.check("not a nothing-to-do", !message.startsWith(PlanOutcomes.NOTHING_TO_DO));
	}

	/** The case measured live 2026-10-13: the only object of the source came from OpenLineage, no scanned column. */
	private static void salesforceAsMeasuredNamesTheObjectAndTheGesture()
	{
		final String message = PlanOutcomes.jobMessage(0, 0, PlanOutcomesTest.lines("Account", "SKIPPED_NO_SCANNED_COLUMN"), new ArrayList<String>());
		PlanOutcomesTest.check("starts with the shared convention", message.startsWith("Nothing to do:"));
		PlanOutcomesTest.check("reason in user words: " + message, message.equals("Nothing to do: Account: no column scanned yet. Scan the application again."));
		PlanOutcomesTest.check("no internal outcome name leaks", !message.contains("SKIPPED"));
	}

	private static void manyObjectsAreCut()
	{
		final String message = PlanOutcomes.jobMessage(0, 0, PlanOutcomesTest.lines("A", "SKIPPED_NO_SCANNED_COLUMN", "B", "SKIPPED_NO_SCANNED_COLUMN", "C", "SKIPPED_NO_SCANNED_COLUMN",
			"DB.S.D", "SKIPPED_NO_SCANNED_COLUMN", "E", "SKIPPED_NO_SCANNED_COLUMN", "F", "SKIPPED_NO_SCANNED_COLUMN"), null);
		PlanOutcomesTest.check("four names then the rest counted, a qualified name shortened: " + message, message.contains("A, B, C, D and 2 more: no column scanned yet."));
	}

	private static void alreadyPlannedIsNothingToDoButNotAnError()
	{
		final String message = PlanOutcomes.jobMessage(0, 3, PlanOutcomesTest.lines("A", "ALREADY_PLANNED", "B", "ALREADY_PLANNED", "C", "ALREADY_PLANNED"), null);
		PlanOutcomesTest.check(message, message.equals("Nothing to do: the 3 tables found were already planned."));
		PlanOutcomesTest.check("singular", PlanOutcomes.jobMessage(0, 1, PlanOutcomesTest.lines("A", "ALREADY_PLANNED"), null).equals("Nothing to do: the 1 table found was already planned."));
	}

	private static void noObjectAtAllSaysScanAgain()
	{
		final String message = PlanOutcomes.jobMessage(0, 0, new ArrayList<PlanOutcomes.Line>(), null);
		PlanOutcomesTest.check(message, message.equals("Nothing to do: the catalog holds no table for this application yet. Scan the application again."));
	}

	private static void changedTablesDoNotAskForAScan()
	{
		System.setProperty(com.on.ebx.factory.ebx.FeatureSwitches.SOURCE_DRIFT_PROPERTY, "true");
		try
		{
			final String message = PlanOutcomes.jobMessage(0, 2, PlanOutcomesTest.lines("A", "DRIFTED", "B", "DRIFTED"), null);
			PlanOutcomesTest.check(message, message.startsWith("Nothing to do: 2 tables changed in the source") && !message.contains("Scan the application again"));
			PlanOutcomesTest.check("open: no promise of a screen that does not exist", !message.contains("next step"));
			PlanOutcomesTest.check("open: the reason line exists", "wizard.src2.reason.drifted".equals(PlanOutcomes.reasonKey("DRIFTED")));
		}
		finally
		{
			System.clearProperty(com.on.ebx.factory.ebx.FeatureSwitches.SOURCE_DRIFT_PROPERTY);
		}
		// F3-105: closed (the default), the v1.3.0 warning stays, without promising a review screen
		final String closed = PlanOutcomes.jobMessage(0, 2, PlanOutcomesTest.lines("A", "DRIFTED", "B", "DRIFTED"), null);
		PlanOutcomesTest.check(closed, closed.startsWith("Nothing to do: 2 tables changed in the source") && closed.contains("prepare their landing again, or clean up from the Landing step")
			&& !closed.contains("next step") && !closed.contains("decision") && !closed.contains("Scan the application again"));
		PlanOutcomesTest.check("closed: the reason line is the one that names what to do", "wizard.src2.reason.driftedClosed".equals(PlanOutcomes.reasonKey("DRIFTED")));
	}

	private static void unmatchedSchemaFilterIsNamed()
	{
		final String message = PlanOutcomes.jobMessage(0, 0, new ArrayList<PlanOutcomes.Line>(), Arrays.asList("ERP_SOURCE"));
		PlanOutcomesTest.check(message, message.equals("Nothing to do: the schema filter (ERP_SOURCE) matches nothing the catalog scanned. Scan the application again."));
	}

	private static void reasonsAndNames()
	{
		PlanOutcomesTest.check("no column has a key", "wizard.src2.reason.noColumn".equals(PlanOutcomes.reasonKey("SKIPPED_NO_SCANNED_COLUMN")));
		PlanOutcomesTest.check("planned and already planned are no problem", PlanOutcomes.reasonKey("PLANNED") == null && PlanOutcomes.reasonKey("ALREADY_PLANNED") == null && PlanOutcomes.reasonKey(null) == null);
		PlanOutcomesTest.check("only a missing column is cured by a scan", PlanOutcomes.scanAgainHelps("SKIPPED_NO_SCANNED_COLUMN") && !PlanOutcomes.scanAgainHelps("DRIFTED"));
		PlanOutcomesTest.check("short names", "TABLE".equals(PlanOutcomes.shortName("DB.SCHEMA.TABLE")) && "Account".equals(PlanOutcomes.shortName("Account")) && "".equals(PlanOutcomes.shortName(null)));
	}

	/** Landing test fixes (2026-10-17). */
	private static void aRescanThatRefreshedATableIsNotNothingToDo()
	{
		final String text = PlanOutcomes.refreshMessage(0, java.util.Arrays.asList("Fidelite_Membres: 13 columns now (was 1)."));
		check("refresh text", text.contains("Fidelite_Membres: 13 columns now (was 1).") && !text.startsWith(PlanOutcomes.NOTHING_TO_DO) && text.startsWith("1 table refreshed"));
		check("with new tables too", PlanOutcomes.refreshMessage(2, java.util.Arrays.asList("A: 2 columns now (was 1).", "B: 3 columns now (was 1).")).startsWith("2 table(s) planned. 2 tables refreshed"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
