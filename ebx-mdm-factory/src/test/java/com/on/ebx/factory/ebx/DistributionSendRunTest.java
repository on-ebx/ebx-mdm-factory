package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. Audit F-11: how a queued send reads in the job row, and which stored params name a target. */
public final class DistributionSendRunTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final ObjectMapper mapper = new ObjectMapper();
		DistributionSendRunTest.check("target in params", DistributionSendRun.paramsNameTarget("{\"projectCode\":\"A\",\"targetId\":\"7\"}", "7"));
		DistributionSendRunTest.check("other target", !DistributionSendRun.paramsNameTarget("{\"projectCode\":\"A\",\"targetId\":\"7\"}", "8"));
		DistributionSendRunTest.check("7 is not 17", !DistributionSendRun.paramsNameTarget("{\"targetId\":\"17\"}", "7"));
		DistributionSendRunTest.check("blank target", !DistributionSendRun.paramsNameTarget("{\"targetId\":\"\"}", ""));
		DistributionSendRunTest.check("null params", !DistributionSendRun.paramsNameTarget(null, "7"));
		DistributionSendRunTest.check("garbage params", !DistributionSendRun.paramsNameTarget("{not json", "7"));

		final ObjectNode done = mapper.createObjectNode();
		done.put("status", "DONE");
		done.put("message", "Sent 12 row(s) across 2 mapping(s), scope=all");
		final String ok = DistributionSendRun.verdict("Send of target 7", done);
		DistributionSendRunTest.check("done keeps the message: " + ok, ok.startsWith("Send of target 7: DONE") && ok.contains("Sent 12 row(s)"));

		final ObjectNode failed = mapper.createObjectNode();
		failed.put("status", "FAILED");
		failed.put("message", "broker down");
		try
		{
			DistributionSendRun.verdict("Send of target 7", failed);
			throw new AssertionError("a FAILED send must fail the job");
		}
		catch (final FactoryJobQueue.ExpectedFailure ex)
		{
			DistributionSendRunTest.check("failure message: " + ex.getMessage(), ex.getMessage().contains("failed: broker down"));
		}
		System.out.println("DistributionSendRunTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
