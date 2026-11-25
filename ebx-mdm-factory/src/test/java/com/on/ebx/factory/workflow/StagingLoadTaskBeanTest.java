package com.on.ebx.factory.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free: run the main, a failure throws (same convention as {@code MatchingRunTaskBeanTest}). Covers
 * ONLY {@link StagingLoadTaskBean}'s pure static helper, {@link StagingLoadTaskBean#countText} -
 * {@link StagingLoadTaskBean#executeScript} itself needs a live EBX {@code Session}/{@code Repository} and
 * is out of reach for {@code tools/test_local.sh}, same discipline already used for every other
 * EBX-dependent class in this module.
 */
public final class StagingLoadTaskBeanTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		StagingLoadTaskBeanTest.countText_nullResult_isZero();
		StagingLoadTaskBeanTest.countText_missingField_isZero();
		StagingLoadTaskBeanTest.countText_present_isRead();
		StagingLoadTaskBeanTest.countText_unchanged_isRead();
		StagingLoadTaskBeanTest.reached_usesTheLoadersOwnTotals();
		StagingLoadTaskBeanTest.isFailed_readsTheStatusField();
		StagingLoadTaskBeanTest.intakeChain_totalRejectIsAStopNotAnError();
		final ObjectNode partial = StagingLoadTaskBeanTest.MAPPER.createObjectNode();
		partial.put("status", "PARTIAL");
		StagingLoadTaskBeanTest.check("F2-06: a PARTIAL load fails the workflow step", StagingLoadTaskBean.isFailed(partial) && StagingLoadTaskBean.isPartial(partial));
		StagingLoadTaskBeanTest.check("F2-06: an intake chain stops instead of throwing", !StagingLoadTaskBean.mustThrow(true, true) && StagingLoadTaskBean.routesToStop(false, true, true));
		System.out.println("StagingLoadTaskBeanTest OK");
	}

	private static void countText_nullResult_isZero()
	{
		StagingLoadTaskBeanTest.check("a null result reads as '0'", "0".equals(StagingLoadTaskBean.countText(null, "rejected")));
	}

	private static void countText_missingField_isZero()
	{
		final ObjectNode result = StagingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("written", 303);
		StagingLoadTaskBeanTest.check("a missing field reads as '0'", "0".equals(StagingLoadTaskBean.countText(result, "rejected")));
	}

	private static void countText_present_isRead()
	{
		final ObjectNode result = StagingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("rejected", 12);
		result.put("written", 303);
		StagingLoadTaskBeanTest.check("'rejected' is read as text", "12".equals(StagingLoadTaskBean.countText(result, "rejected")));
		StagingLoadTaskBeanTest.check("'written' is read as text", "303".equals(StagingLoadTaskBean.countText(result, "written")));
	}

	/** 2026-09-23, incremental-transfer lot: {@code unchanged} is read through the exact same generic helper - nothing bean-specific to test beyond that. */
	private static void countText_unchanged_isRead()
	{
		final ObjectNode result = StagingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("unchanged", 42);
		StagingLoadTaskBeanTest.check("'unchanged' is read as text", "42".equals(StagingLoadTaskBean.countText(result, "unchanged")));
	}

	/** Automatic-intake lot: the stop decision reads the loader's own {@code read}/{@code rejected} totals; no threshold (the default of every older launch) never stops. */
	private static void reached_usesTheLoadersOwnTotals()
	{
		final ObjectNode result = StagingLoadTaskBeanTest.MAPPER.createObjectNode();
		result.put("read", 1204);
		result.put("rejected", 72);
		StagingLoadTaskBeanTest.check("72/1204 (5.98 %) reaches 5", StagingLoadTaskBean.reached(result, "5"));
		StagingLoadTaskBeanTest.check("72/1204 (5.98 %) does not reach 6", !StagingLoadTaskBean.reached(result, "6"));
		final ObjectNode exact = StagingLoadTaskBeanTest.MAPPER.createObjectNode();
		exact.put("read", 100);
		exact.put("rejected", 5);
		StagingLoadTaskBeanTest.check("5/100 reaches 5: exactly at the threshold stops", StagingLoadTaskBean.reached(exact, "5"));
		exact.put("rejected", 4);
		StagingLoadTaskBeanTest.check("4/100 does not reach 5", !StagingLoadTaskBean.reached(exact, "5"));
		exact.put("rejected", 100);
		StagingLoadTaskBeanTest.check("100/100 reaches 100: a total reject stops", StagingLoadTaskBean.reached(exact, "100"));
		exact.put("rejected", 99);
		StagingLoadTaskBeanTest.check("99/100 does not reach 100", !StagingLoadTaskBean.reached(exact, "100"));
		exact.put("rejected", 0);
		StagingLoadTaskBeanTest.check("0/100 does not reach 1: nothing held back never stops", !StagingLoadTaskBean.reached(exact, "1"));
		StagingLoadTaskBeanTest.check("no threshold never stops", !StagingLoadTaskBean.reached(result, null) && !StagingLoadTaskBean.reached(result, ""));
		StagingLoadTaskBeanTest.check("an unreadable threshold never stops", !StagingLoadTaskBean.reached(result, "abc") && !StagingLoadTaskBean.reached(result, "0"));
		StagingLoadTaskBeanTest.check("a null result never stops", !StagingLoadTaskBean.reached(null, "5"));
		result.put("read", 0);
		StagingLoadTaskBeanTest.check("read 0 never stops", !StagingLoadTaskBean.reached(result, "1"));
	}

	/** A staging load whose every row was rejected answers status FAILED on a normal return; the bean throws on it, like the master load bean. */
	private static void isFailed_readsTheStatusField()
	{
		final ObjectNode failed = StagingLoadTaskBeanTest.MAPPER.createObjectNode();
		failed.put("status", "FAILED");
		final ObjectNode done = StagingLoadTaskBeanTest.MAPPER.createObjectNode();
		done.put("status", "DONE");
		StagingLoadTaskBeanTest.check("FAILED is failed", StagingLoadTaskBean.isFailed(failed));
		StagingLoadTaskBeanTest.check("DONE and STOPPED are not", !StagingLoadTaskBean.isFailed(done) && !StagingLoadTaskBean.isFailed(null)
			&& !StagingLoadTaskBean.isFailed(StagingLoadTaskBeanTest.MAPPER.createObjectNode().put("status", "STOPPED")));
	}

	/** Automatic intake: a total reject stops an intake chain at staging without throwing; a person-launched pipeline keeps the audit's throw. */
	private static void intakeChain_totalRejectIsAStopNotAnError()
	{
		StagingLoadTaskBeanTest.check("non-empty token = intake", StagingLoadTaskBean.isIntake("uuid") && !StagingLoadTaskBean.isIntake(null) && !StagingLoadTaskBean.isIntake("  ")
			&& !StagingLoadTaskBean.isIntake(""));
		StagingLoadTaskBeanTest.check("intake + total reject: no throw", !StagingLoadTaskBean.mustThrow(true, true));
		StagingLoadTaskBeanTest.check("person + total reject: throws (the audit's behaviour)", StagingLoadTaskBean.mustThrow(true, false));
		StagingLoadTaskBeanTest.check("nothing rejected totally: never throws", !StagingLoadTaskBean.mustThrow(false, true) && !StagingLoadTaskBean.mustThrow(false, false));
		StagingLoadTaskBeanTest.check("intake + total reject routes to the stop node", StagingLoadTaskBean.routesToStop(false, true, true));
		StagingLoadTaskBeanTest.check("person + total reject does not (it throws)", !StagingLoadTaskBean.routesToStop(false, true, false));
		StagingLoadTaskBeanTest.check("threshold reached stops either way", StagingLoadTaskBean.routesToStop(true, false, true) && StagingLoadTaskBean.routesToStop(true, false, false));
		StagingLoadTaskBeanTest.check("partial rejects, no threshold: no stop", !StagingLoadTaskBean.routesToStop(false, false, true));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
