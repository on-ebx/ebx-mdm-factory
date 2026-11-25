package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws. Same package as {@link LandingFlows} on purpose, to reach its
 * package-private {@link LandingFlows#firstFailureReason} directly - the fragment behind live bug 7a
 * (2026-09-24, project Vague1): the run grid used to show a bare "1 of 1 table(s) failed." with no reason at
 * all, because nothing ever read the per-table {@code error}/{@code transportError}/{@code writeBackError}
 * fields {@link LandingFlows#load} already recorded.
 */
public final class LandingFlowsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		LandingFlowsTest.check("error field wins first, tableName prefixed", "SUPPLIER: boom"
			.equals(LandingFlowsTest.reasonOf(LandingFlowsTest.detail("SUPPLIER", "error", "boom"))));
		LandingFlowsTest.check("transportError read when no error", "T2: no route"
			.equals(LandingFlowsTest.reasonOf(LandingFlowsTest.detail("T2", "transportError", "no route"))));
		LandingFlowsTest.check("writeBackError read when neither error nor transportError", "T3: decimal cast failed"
			.equals(LandingFlowsTest.reasonOf(LandingFlowsTest.detail("T3", "writeBackError", "decimal cast failed"))));
		LandingFlowsTest.check("the FIRST failing table in the array wins, not the last", "FIRST: bad"
			.equals(LandingFlowsTest.reasonOf(LandingFlowsTest.detail("FIRST", "error", "bad"), LandingFlowsTest.detail("SECOND", "error", "also bad"))));
		LandingFlowsTest.check("a table with none of the three fields is skipped in favour of the next one", "SECOND: bad"
			.equals(LandingFlowsTest.reasonOf(LandingFlowsTest.MAPPER.createObjectNode().put("tableName", "FIRST"),
				LandingFlowsTest.detail("SECOND", "error", "bad"))));
		LandingFlowsTest.check("no table detail carries a reason: falls back to 'see detail', never empty",
			"see detail".equals(LandingFlowsTest.reasonOf(LandingFlowsTest.MAPPER.createObjectNode().put("tableName", "X"))));
		System.out.println("LandingFlowsTest OK");
	}

	private static ObjectNode detail(final String pTableName, final String pField, final String pMessage)
	{
		return LandingFlowsTest.MAPPER.createObjectNode().put("tableName", pTableName).put(pField, pMessage);
	}

	private static String reasonOf(final ObjectNode... pDetails)
	{
		final ArrayNode array = LandingFlowsTest.MAPPER.createArrayNode();
		for (final ObjectNode detail : pDetails)
		{
			array.add(detail);
		}
		return LandingFlows.firstFailureReason(array);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
