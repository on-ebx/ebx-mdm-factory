package com.on.ebx.factory.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws (same convention as {@code MatchingRunTaskBeanTest}). Covers
 * ONLY {@link MasterLoadTaskBean}'s two pure static helpers, {@link MasterLoadTaskBean#countText} and
 * {@link MasterLoadTaskBean#isFailed} - {@link MasterLoadTaskBean#executeScript} itself needs a live EBX
 * {@code Session}/{@code Repository} and is out of reach for {@code tools/test_local.sh}, same discipline
 * already used for every other EBX-dependent class in this module.
 */
public final class MasterLoadTaskBeanTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MasterLoadTaskBeanTest.countText_nullResult_isZero();
		MasterLoadTaskBeanTest.countText_missingField_isZero();
		MasterLoadTaskBeanTest.countText_present_isRead();
		MasterLoadTaskBeanTest.isFailed_null_isFalse();
		MasterLoadTaskBeanTest.isFailed_done_isFalse();
		MasterLoadTaskBeanTest.isFailed_failed_isTrue();
		System.out.println("MasterLoadTaskBeanTest OK");
	}

	private static void countText_nullResult_isZero()
	{
		MasterLoadTaskBeanTest.check("a null result reads as '0'", "0".equals(MasterLoadTaskBean.countText(null, "rejected")));
	}

	private static void countText_missingField_isZero()
	{
		final ObjectNode result = MasterLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("written", 196);
		MasterLoadTaskBeanTest.check("a missing field reads as '0'", "0".equals(MasterLoadTaskBean.countText(result, "rejected")));
	}

	private static void countText_present_isRead()
	{
		final ObjectNode result = MasterLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("rejected", 5);
		result.put("written", 196);
		result.put("read", 201);
		MasterLoadTaskBeanTest.check("'rejected' is read as text", "5".equals(MasterLoadTaskBean.countText(result, "rejected")));
		MasterLoadTaskBeanTest.check("'written' is read as text", "196".equals(MasterLoadTaskBean.countText(result, "written")));
		MasterLoadTaskBeanTest.check("'read' is read as text", "201".equals(MasterLoadTaskBean.countText(result, "read")));
	}

	private static void isFailed_null_isFalse()
	{
		MasterLoadTaskBeanTest.check("a null result is not failed", !MasterLoadTaskBean.isFailed(null));
	}

	private static void isFailed_done_isFalse()
	{
		final ObjectNode result = MasterLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("status", "DONE");
		MasterLoadTaskBeanTest.check("status=DONE is not failed", !MasterLoadTaskBean.isFailed(result));
	}

	private static void isFailed_failed_isTrue()
	{
		final ObjectNode result = MasterLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("status", "FAILED");
		MasterLoadTaskBeanTest.check("status=FAILED is failed", MasterLoadTaskBean.isFailed(result));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
