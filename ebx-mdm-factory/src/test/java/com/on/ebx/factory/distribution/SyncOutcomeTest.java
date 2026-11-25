package com.on.ebx.factory.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.distribution.SyncOutcome.Result;

/**
 * JUnit-free, like the other pure-package tests: run the main, a failure throws. Every JSON literal here is
 * the exact shape {@code ebx-connectors}' {@code ConnectorsTools#results}/{@code #enrich} +
 * {@code #putOutcome} build (read directly off that module's source, 2026-09-26 - see {@code SyncOutcome}'s
 * own Javadoc), not a guess - this is the regression test for the "false success" bug measured live on
 * DistribTest (task brief item 1): a downed Kafka broker made {@code connectors_sync} answer without a
 * transport-level error while its own {@code errors} counter said otherwise.
 */
public final class SyncOutcomeTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		SyncOutcomeTest.transportErrorIsFailed();
		SyncOutcomeTest.nullJsonIsFailed();
		SyncOutcomeTest.cleanBatchSendIsOk();
		SyncOutcomeTest.batchSendWithOneErrorItemIsFailedEvenWithoutTransportError();
		SyncOutcomeTest.singleRecordSendErrorIsFailed();
		SyncOutcomeTest.partialStatusIsFailed();
		SyncOutcomeTest.skippedWithNoErrorsIsOk();
		System.out.println("SyncOutcomeTest OK");
	}

	private static void transportErrorIsFailed()
	{
		final Result r = SyncOutcome.read(true, "No mapping 'X'", null);
		SyncOutcomeTest.check("transport error is never ok", !r.ok);
		SyncOutcomeTest.check("transport error message carried through", "No mapping 'X'".equals(r.message));
		SyncOutcomeTest.check("transport error has no runId", r.runId == null);
	}

	private static void nullJsonIsFailed()
	{
		final Result r = SyncOutcome.read(false, null, null);
		SyncOutcomeTest.check("no readable content is never ok", !r.ok);
	}

	/** {@code ConnectorsTools#results} shape: a scope batch send, every item OK. */
	private static void cleanBatchSendIsOk() throws Exception
	{
		final JsonNode json = SyncOutcomeTest.MAPPER.readTree("{"
			+ "\"runId\":\"R20260926-100000-0001\",\"dryRun\":false,\"mapping\":\"302\",\"scope\":\"ALL\","
			+ "\"results\":[{\"mapping\":\"302\",\"record\":\"Alice\",\"key\":\"1\",\"status\":\"OK\",\"message\":\"\"},"
			+ "{\"mapping\":\"302\",\"record\":\"Bob\",\"key\":\"2\",\"status\":\"OK\",\"message\":\"\"}],"
			+ "\"count\":2,\"ok\":2,\"errors\":0,\"failed\":0,\"partial\":0,\"skipped\":0,\"noData\":0,\"status\":\"OK\"}");
		final Result r = SyncOutcome.read(false, null, json);
		SyncOutcomeTest.check("clean batch is ok", r.ok);
		SyncOutcomeTest.check("row count from count field", r.rows == 2L);
		SyncOutcomeTest.check("runId carried through", "R20260926-100000-0001".equals(r.runId));
		SyncOutcomeTest.check("no message when ok", r.message.isEmpty());
	}

	/**
	 * The exact live-measured failure (task brief item 1): {@code isError} would be false (no transport
	 * error), but {@code errors} is non-zero and one item is {@code ERROR} with a real message - this is the
	 * shape that used to be recorded as {@code DistributionEvent status SENT, message null}.
	 */
	private static void batchSendWithOneErrorItemIsFailedEvenWithoutTransportError() throws Exception
	{
		final JsonNode json = SyncOutcomeTest.MAPPER.readTree("{"
			+ "\"runId\":\"R20260926-133426-0004\",\"dryRun\":false,\"mapping\":\"302\",\"scope\":\"ALL\","
			+ "\"results\":[{\"mapping\":\"302\",\"record\":\"Person 1\",\"key\":\"1\",\"status\":\"ERROR\","
			+ "\"message\":\"Kafka publish to mdm.distribtest.person failed: org.apache.kafka.common.errors.TimeoutException: "
			+ "Topic mdm.distribtest.person not present in metadata after 10000 ms.\",\"errors\":[\"Kafka publish to "
			+ "mdm.distribtest.person failed: org.apache.kafka.common.errors.TimeoutException: Topic mdm.distribtest.person "
			+ "not present in metadata after 10000 ms.\"]}],"
			+ "\"count\":1,\"ok\":0,\"errors\":1,\"failed\":1,\"partial\":0,\"skipped\":0,\"noData\":0,\"status\":\"ERROR\"}");
		final Result r = SyncOutcome.read(false, null, json);
		SyncOutcomeTest.check("an ERROR item is never ok even without a transport error", !r.ok);
		SyncOutcomeTest.check("Connectors' own message is copied through", r.message.contains("Kafka publish to mdm.distribtest.person failed"));
		SyncOutcomeTest.check("Connectors' own message is copied through (TimeoutException)", r.message.contains("TimeoutException"));
		SyncOutcomeTest.check("runId is kept", "R20260926-133426-0004".equals(r.runId));
	}

	/** {@code ConnectorsTools#enrich} shape: a single record, no {@code scope} - what {@code DistributionDispatcher#sendRecord} sends. */
	private static void singleRecordSendErrorIsFailed() throws Exception
	{
		final JsonNode json = SyncOutcomeTest.MAPPER.readTree("{"
			+ "\"runId\":\"R20260926-140000-0002\",\"record\":\"Person 1\",\"dryRun\":false,"
			+ "\"results\":[{\"mapping\":\"302\",\"label\":\"Kafka publish\",\"connector\":\"KafkaMdmDistribTest\",\"status\":\"ERROR\","
			+ "\"message\":\"Kafka publish to mdm.distribtest.person failed: broker unreachable\",\"changes\":[],"
			+ "\"errors\":[\"Kafka publish to mdm.distribtest.person failed: broker unreachable\"]}],"
			+ "\"ok\":0,\"errors\":1,\"failed\":1,\"partial\":0,\"skipped\":0,\"noData\":0,\"status\":\"ERROR\","
			+ "\"conflicts\":0,\"hasConflicts\":false}");
		final Result r = SyncOutcome.read(false, null, json);
		SyncOutcomeTest.check("a failed single-record enrich send is not ok", !r.ok);
		SyncOutcomeTest.check("message names the mapping when there is no key/record label", r.message.contains("broker unreachable"));
	}

	private static void partialStatusIsFailed() throws Exception
	{
		final JsonNode json = SyncOutcomeTest.MAPPER.readTree("{"
			+ "\"runId\":\"R3\",\"results\":[{\"status\":\"PARTIAL\",\"message\":\"some fields rejected\",\"errors\":[\"field X rejected\"]}],"
			+ "\"count\":1,\"ok\":0,\"errors\":1,\"failed\":0,\"partial\":1,\"skipped\":0,\"noData\":0,\"status\":\"PARTIAL\"}");
		final Result r = SyncOutcome.read(false, null, json);
		SyncOutcomeTest.check("a PARTIAL outcome is not ok - partial commit must never read as success", !r.ok);
	}

	/** {@code SKIPPED}/{@code NO_DATA} with zero errors is not the "false success" bug - nothing failed, just nothing to send. */
	private static void skippedWithNoErrorsIsOk() throws Exception
	{
		final JsonNode json = SyncOutcomeTest.MAPPER.readTree("{"
			+ "\"runId\":\"R4\",\"results\":[{\"status\":\"NO_CHANGE\",\"message\":\"\"}],"
			+ "\"count\":1,\"ok\":1,\"errors\":0,\"failed\":0,\"partial\":0,\"skipped\":0,\"noData\":0,\"status\":\"OK\"}");
		final Result r = SyncOutcome.read(false, null, json);
		SyncOutcomeTest.check("no errors at all is ok", r.ok);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
