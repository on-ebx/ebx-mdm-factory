package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws. Robustness audit F2-105: the readers of the reject counters ADD tuples of the same key instead of overwriting them, so the numbers stay
 * right even if the SQL engine splits a group (the "one group per row" defect of a GROUP BY on a foreign key, measured absent for the form the module writes on 2026-10-24).
 */
public final class RejectCountersTest
{
	public static void main(final String[] pArgs)
	{
		RejectCountersTest.heldBackGridAddsSplitGroups();
		RejectCountersTest.summaryAddsSplitGroups();
		RejectCountersTest.causesAddSplitGroups();
		System.out.println("RejectCountersTest OK");
	}

	private static void heldBackGridAddsSplitGroups()
	{
		final Map<String, Long> out = new LinkedHashMap<String, Long>();
		final String key = "ENT\u0001SYS";
		for (int i = 0; i < 3; i++)
		{
			StagingLoadGridReader.accumulate(out, key, 1L);
		}
		StagingLoadGridReader.accumulate(out, "ENT\u0001OTHER", 4L);
		RejectCountersTest.check("three identical (ENT, SYS, 1) tuples give 3, not 1: " + out, out.get(key) == 3L);
		RejectCountersTest.check("two distinct keys give two lines: " + out, out.size() == 2 && out.get("ENT\u0001OTHER") == 4L);
	}

	private static void summaryAddsSplitGroups()
	{
		final List<RejectReview.Summary> out = new ArrayList<RejectReview.Summary>();
		for (int i = 0; i < 3; i++)
		{
			RejectReview.addSummary(out, "E1", "Person", "STAGING", 1L, 0L, 0L);
		}
		RejectReview.addSummary(out, "E1", "Person", "MASTER", 2L, 1L, 0L);
		RejectCountersTest.check("same (entity, stage) is one line of 3 open: " + out.size(), out.size() == 2 && out.get(0).open == 3L && out.get(0).total() == 3L);
		RejectCountersTest.check("another stage is its own line", "MASTER".equals(out.get(1).stage) && out.get(1).acknowledged == 1L);
	}

	private static void causesAddSplitGroups()
	{
		final RejectCauseReader.Causes causes = new RejectCauseReader.Causes();
		for (int i = 0; i < 3; i++)
		{
			causes.add("Person", "EBX_REFUSAL", 1L);
		}
		causes.add("Account", "EBX_REFUSAL", 5L);
		RejectCountersTest.check("three tuples of one entity and cause give 3: " + causes.entity("Person").total(), causes.entity("Person").total() == 3L);
		RejectCountersTest.check("the project sums both entities", causes.project().total() == 8L);
		RejectCountersTest.check("two entities, two lines", causes.byEntity().size() == 2);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
