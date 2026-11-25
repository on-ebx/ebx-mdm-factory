package com.on.ebx.factory.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws (same convention as {@code MatchingRunTaskBeanTest}). Covers
 * ONLY {@link LandingLoadTaskBean}'s two pure static helpers, {@link LandingLoadTaskBean#failedCount} and
 * {@link LandingLoadTaskBean#sourceFailed} - {@link LandingLoadTaskBean#executeScript} itself needs a live
 * EBX {@code Session}/{@code Repository} and is out of reach for {@code tools/test_local.sh}, same
 * discipline already used for every other EBX-dependent class in this module.
 */
public final class LandingLoadTaskBeanTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		LandingLoadTaskBeanTest.failedCount_null_isZero();
		LandingLoadTaskBeanTest.failedCount_missing_isZero();
		LandingLoadTaskBeanTest.failedCount_present_isRead();
		LandingLoadTaskBeanTest.sourceFailed_null_isFalse();
		LandingLoadTaskBeanTest.sourceFailed_zeroFailedDoneStatus_isFalse();
		LandingLoadTaskBeanTest.sourceFailed_positiveFailed_isTrue();
		LandingLoadTaskBeanTest.sourceFailed_zeroFailedButFailedStatus_isTrue();
		System.out.println("LandingLoadTaskBeanTest OK");
	}

	private static void failedCount_null_isZero()
	{
		LandingLoadTaskBeanTest.check("null result has 0 failed", LandingLoadTaskBean.failedCount(null) == 0);
	}

	private static void failedCount_missing_isZero()
	{
		final ObjectNode result = LandingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("status", "DONE");
		LandingLoadTaskBeanTest.check("a result with no 'failed' field has 0 failed", LandingLoadTaskBean.failedCount(result) == 0);
	}

	private static void failedCount_present_isRead()
	{
		final ObjectNode result = LandingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("failed", 3);
		LandingLoadTaskBeanTest.check("a result's own 'failed' field is read as-is", LandingLoadTaskBean.failedCount(result) == 3);
	}

	private static void sourceFailed_null_isFalse()
	{
		LandingLoadTaskBeanTest.check("a null result is not failed", !LandingLoadTaskBean.sourceFailed(null));
	}

	private static void sourceFailed_zeroFailedDoneStatus_isFalse()
	{
		final ObjectNode result = LandingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("failed", 0);
		result.put("status", "DONE");
		LandingLoadTaskBeanTest.check("failed=0, status=DONE is not failed", !LandingLoadTaskBean.sourceFailed(result));
	}

	private static void sourceFailed_positiveFailed_isTrue()
	{
		final ObjectNode result = LandingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("failed", 1);
		result.put("status", "FAILED");
		LandingLoadTaskBeanTest.check("failed=1 is failed", LandingLoadTaskBean.sourceFailed(result));
	}

	private static void sourceFailed_zeroFailedButFailedStatus_isTrue()
	{
		// LandingFlows.load never produces this combination itself (failed==0 always pairs with status DONE) -
		// this helper still checks status defensively, see its own Javadoc
		final ObjectNode result = LandingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("failed", 0);
		result.put("status", "FAILED");
		LandingLoadTaskBeanTest.check("status=FAILED alone is enough, even with failed=0", LandingLoadTaskBean.sourceFailed(result));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
