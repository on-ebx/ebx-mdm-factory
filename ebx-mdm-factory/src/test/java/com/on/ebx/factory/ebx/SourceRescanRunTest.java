package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws. UX lot U2 (2026-10-13): the text a plan job ends with, built from the planner's own answer
 * ({@link LandingSourceRun#planMessage}), and how {@link SourceRescanRun} reads the outcome text of a catalog scan.
 */
public final class SourceRescanRunTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		SourceRescanRunTest.theSalesforcePlanOfTheLiveReportExplainsItself();
		SourceRescanRunTest.aPlanThatWroteTablesKeepsItsCounts();
		SourceRescanRunTest.scanFailuresAreRecognised();
		SourceRescanRunTest.theKindIsFiledUnderStep2();
		System.out.println("SourceRescanRunTest OK");
	}

	private static ObjectNode answer(final int pPlanned, final int pAlready)
	{
		final ObjectNode out = SourceRescanRunTest.MAPPER.createObjectNode();
		out.put("planned", pPlanned);
		out.put("alreadyPlanned", pAlready);
		out.putArray("unmatchedSchemas");
		return out;
	}

	/** 2026-10-13: "0 table(s) planned, 0 already planned." in green; the report held {Account, SKIPPED_NO_SCANNED_COLUMN}. */
	private static void theSalesforcePlanOfTheLiveReportExplainsItself()
	{
		final ObjectNode answer = SourceRescanRunTest.answer(0, 0);
		final ArrayNode tables = answer.putArray("tables");
		tables.addObject().put("sourceObject", "Account").put("outcome", "SKIPPED_NO_SCANNED_COLUMN");
		final String message = LandingSourceRun.planMessage(answer);
		SourceRescanRunTest.check(message, "Nothing to do: Account: no column scanned yet. Scan the application again.".equals(message));
	}

	private static void aPlanThatWroteTablesKeepsItsCounts()
	{
		SourceRescanRunTest.check("counts", "17 table(s) planned, 0 already planned.".equals(LandingSourceRun.planMessage(SourceRescanRunTest.answer(17, 0))));
	}

	private static void scanFailuresAreRecognised()
	{
		SourceRescanRunTest.check("refused", SourceRescanRun.failed("connectors_sync refused: no right"));
		SourceRescanRunTest.check("failed", SourceRescanRun.failed("connectors_sync failed: timeout"));
		SourceRescanRunTest.check("a normal outcome is not a failure", !SourceRescanRun.failed("Loaded 17 of 17 row(s).") && !SourceRescanRun.failed(null) && !SourceRescanRun.failed(""));
	}

	private static void theKindIsFiledUnderStep2()
	{
		SourceRescanRunTest.check("task list: step 2", com.on.ebx.factory.wizard.RecentTasks.stepOf(SourceRescanRun.STEP) == com.on.ebx.factory.wizard.WizardRules.STEP_SOURCES);
		FactoryJobKinds.registerAll();
		SourceRescanRunTest.check("resource SOURCES", FactoryJobs.SOURCES.equals(FactoryJobQueue.resourceOf(SourceRescanRun.STEP)));
		SourceRescanRunTest.check("DESIGN right", FactoryJobQueue.rightOf(SourceRescanRun.STEP) == com.on.ebx.factory.core.FactoryRight.DESIGN);
		for (final java.util.Locale locale : new java.util.Locale[] { java.util.Locale.ENGLISH, java.util.Locale.FRENCH })
		{
			final String label = com.on.ebx.factory.wizard.RecentTasks.kindLabel(SourceRescanRun.STEP, locale);
			SourceRescanRunTest.check("labelled in " + locale + ": " + label, !label.equals(SourceRescanRun.STEP));
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
