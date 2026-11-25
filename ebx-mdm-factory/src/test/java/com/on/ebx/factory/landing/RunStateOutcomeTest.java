package com.on.ebx.factory.landing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link RunStateOutcome} (volume lot 7c, 2026-09-28). */
public final class RunStateOutcomeTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		RunStateOutcomeTest.checkAsyncStartAcknowledgementDetected();
		RunStateOutcomeTest.checkOldSynchronousAnswerNotTreatedAsAsyncStart();
		RunStateOutcomeTest.checkNullAndMissingFieldsNeverThrow();
		RunStateOutcomeTest.checkTerminalStatuses();
		RunStateOutcomeTest.checkProcessedAndErrorsSoFar();
		RunStateOutcomeTest.checkOutcomeDoneNoErrors();
		RunStateOutcomeTest.checkOutcomePartialWithErrors();
		RunStateOutcomeTest.checkOutcomeError();
		RunStateOutcomeTest.checkOutcomeStoppedWithResumeHint();
		RunStateOutcomeTest.checkOutcomeInterruptedWithResumeHint();
		RunStateOutcomeTest.checkOutcomeTruncatedFailsRegardlessOfStatus();
		RunStateOutcomeTest.checkOutcomeFallsBackToSourceRowCountRatio();
		System.out.println("RunStateOutcomeTest OK");
	}

	private static void checkAsyncStartAcknowledgementDetected()
	{
		final ObjectNode ack = RunStateOutcomeTest.MAPPER.createObjectNode();
		ack.put("runId", "R1");
		ack.put("status", "RUNNING");
		ack.put("mapping", "217");
		ack.put("feed", true);
		ack.put("async", true);
		ack.put("nextStep", "poll connectors_run_get");
		RunStateOutcomeTest.check("the async-start acknowledgement is detected", RunStateOutcome.isAsyncStart(ack));
		RunStateOutcomeTest.check("runId is read off it", "R1".equals(RunStateOutcome.runId(ack)));
	}

	private static void checkOldSynchronousAnswerNotTreatedAsAsyncStart()
	{
		// an older Connectors build that does not know "async" ignores it and answers the complete synchronous
		// shape instead - it always carries "count", even for a run whose status happens to read something
		// unusual; detection must never be fooled by that.
		final ObjectNode oldSync = RunStateOutcomeTest.MAPPER.createObjectNode();
		oldSync.put("runId", "R2");
		oldSync.put("status", "OK");
		oldSync.put("count", 100);
		oldSync.put("ok", 100);
		oldSync.put("errors", 0);
		RunStateOutcomeTest.check("a synchronous answer (carries count) is never treated as an async start", !RunStateOutcome.isAsyncStart(oldSync));

		final ObjectNode partial = RunStateOutcomeTest.MAPPER.createObjectNode();
		partial.put("runId", "R3");
		partial.put("status", "PARTIAL");
		partial.put("count", 50);
		RunStateOutcomeTest.check("a synchronous PARTIAL answer is not an async start either", !RunStateOutcome.isAsyncStart(partial));
	}

	private static void checkNullAndMissingFieldsNeverThrow()
	{
		RunStateOutcomeTest.check("null answer is not an async start", !RunStateOutcome.isAsyncStart(null));
		RunStateOutcomeTest.check("null answer has no runId", RunStateOutcome.runId(null) == null);
		RunStateOutcomeTest.check("null execution has 0 rows processed", RunStateOutcome.processedSoFar(null) == 0L);
		RunStateOutcomeTest.check("null execution has 0 errors so far", RunStateOutcome.errorsSoFar(null) == 0L);
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(null, 0);
		RunStateOutcomeTest.check("outcome(null, ...) never throws and reports 0 rows", outcome.rows == 0L);
		// an ObjectNode missing every technical field: an empty status is not any known terminal status, so
		// outcome() falls through to the default (non-error/non-stopped) branch rather than one of the special
		// ones - still must not throw.
		final LandingFlow.LoadOutcome blank = RunStateOutcome.outcome(RunStateOutcomeTest.MAPPER.createObjectNode(), 0);
		RunStateOutcomeTest.check("outcome() on a blank object is ok (nothing says otherwise)", blank.ok);
	}

	private static void checkTerminalStatuses()
	{
		RunStateOutcomeTest.check("DONE is terminal", RunStateOutcome.isTerminal("DONE"));
		RunStateOutcomeTest.check("PARTIAL is terminal", RunStateOutcome.isTerminal("PARTIAL"));
		RunStateOutcomeTest.check("ERROR is terminal", RunStateOutcome.isTerminal("ERROR"));
		RunStateOutcomeTest.check("STOPPED is terminal", RunStateOutcome.isTerminal("STOPPED"));
		RunStateOutcomeTest.check("INTERRUPTED is terminal", RunStateOutcome.isTerminal("INTERRUPTED"));
		RunStateOutcomeTest.check("RUNNING is not terminal", !RunStateOutcome.isTerminal("RUNNING"));
		RunStateOutcomeTest.check("an unknown/blank status is not terminal", !RunStateOutcome.isTerminal(""));
	}

	private static void checkProcessedAndErrorsSoFar()
	{
		final ObjectNode execution = RunStateOutcomeTest.MAPPER.createObjectNode();
		execution.put("status", "RUNNING");
		execution.put("created", 10);
		execution.put("updated", 20);
		execution.put("noChange", 5);
		execution.put("errorCount", 2);
		RunStateOutcomeTest.check("processedSoFar sums created+updated+noChange", RunStateOutcome.processedSoFar(execution) == 35L);
		RunStateOutcomeTest.check("errorsSoFar reads errorCount", RunStateOutcome.errorsSoFar(execution) == 2L);
	}

	private static void checkOutcomeDoneNoErrors()
	{
		final ObjectNode execution = RunStateOutcomeTest.execution("DONE", 100, 0, 0, 0);
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(execution, 0);
		RunStateOutcomeTest.check("DONE with no errors is ok", outcome.ok);
		RunStateOutcomeTest.check("count = created+updated+noChange: " + outcome.rows, outcome.rows == 100L);
	}

	private static void checkOutcomePartialWithErrors()
	{
		final ObjectNode execution = RunStateOutcomeTest.execution("PARTIAL", 90, 5, 0, 5);
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(execution, 0);
		RunStateOutcomeTest.check("PARTIAL is still ok (a load that happened, with some errors)", outcome.ok);
		RunStateOutcomeTest.check("rows counts created+updated+noChange only, not errorCount: " + outcome.rows, outcome.rows == 95L);
		RunStateOutcomeTest.check("message mentions the error count", outcome.message.contains("5 error"));
	}

	private static void checkOutcomeError()
	{
		final ObjectNode execution = RunStateOutcomeTest.execution("ERROR", 0, 0, 0, 100);
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(execution, 0);
		RunStateOutcomeTest.check("ERROR is never ok", !outcome.ok);
		RunStateOutcomeTest.check("message says the load failed", outcome.message.contains("failed"));
	}

	private static void checkOutcomeStoppedWithResumeHint()
	{
		final ObjectNode execution = RunStateOutcomeTest.execution("STOPPED", 40, 10, 0, 0);
		execution.put("nextIndex", 50);
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(execution, 0);
		RunStateOutcomeTest.check("STOPPED is never ok", !outcome.ok);
		RunStateOutcomeTest.check("message names the resume point: " + outcome.message, outcome.message.contains("50"));
		RunStateOutcomeTest.check("message invites a re-run", outcome.message.contains("re-run"));
	}

	private static void checkOutcomeInterruptedWithResumeHint()
	{
		final ObjectNode execution = RunStateOutcomeTest.execution("INTERRUPTED", 200, 0, 0, 0);
		execution.put("nextIndex", 200);
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(execution, 0);
		RunStateOutcomeTest.check("INTERRUPTED is never ok (FAILED, per the task's own instruction)", !outcome.ok);
		RunStateOutcomeTest.check("message says the server restarted", outcome.message.contains("restarted"));
		RunStateOutcomeTest.check("message names the resume point: " + outcome.message, outcome.message.contains("200"));
	}

	private static void checkOutcomeTruncatedFailsRegardlessOfStatus()
	{
		final ObjectNode execution = RunStateOutcomeTest.execution("DONE", 100, 0, 0, 0);
		execution.put("truncated", true);
		final LandingFlow.LoadOutcome outcome = RunStateOutcome.outcome(execution, 0);
		RunStateOutcomeTest.check("a truncated DONE execution still fails (LoadOutcomeRule wins)", !outcome.ok);
		RunStateOutcomeTest.check("message names the FAILED reason", outcome.message.contains("FAILED"));
	}

	private static void checkOutcomeFallsBackToSourceRowCountRatio()
	{
		final ObjectNode execution = RunStateOutcomeTest.execution("DONE", 80, 0, 0, 0);
		final LandingFlow.LoadOutcome failed = RunStateOutcome.outcome(execution, 100);
		RunStateOutcomeTest.check("80 of 100 last seen at the source: FAILED (same LoadOutcomeRule a synchronous answer uses)", !failed.ok);

		final ObjectNode warnExecution = RunStateOutcomeTest.execution("DONE", 96, 0, 0, 0);
		final LandingFlow.LoadOutcome warn = RunStateOutcome.outcome(warnExecution, 100);
		RunStateOutcomeTest.check("96 of 100: WARN, still ok", warn.ok);
		RunStateOutcomeTest.check("message names the WARNING", warn.message.contains("WARNING"));
	}

	private static ObjectNode execution(final String pStatus, final long pCreated, final long pUpdated, final long pNoChange, final long pErrorCount)
	{
		final ObjectNode execution = RunStateOutcomeTest.MAPPER.createObjectNode();
		execution.put("status", pStatus);
		execution.put("created", pCreated);
		execution.put("updated", pUpdated);
		execution.put("noChange", pNoChange);
		execution.put("errorCount", pErrorCount);
		return execution;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
