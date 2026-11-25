package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.agent.AgentLoop;
import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers {@link
 * ProseAnswerRecovery}'s orchestration ({@link ProseAnswerRecovery#recover}) with fabricated {@link
 * AgentRuntime.Outcome}s ({@code AgentRuntime.Outcome}'s own test-only constructor - see its Javadoc for why
 * one is needed at all) and a fake {@link ProseAnswerRecovery.FollowUpRunner}/{@link
 * ProseAnswerRecovery.RecordedNothing} - never a live EBX repository or a real HTTP call, same discipline as
 * {@code AgentRuntimeTest}. The end-to-end mechanism (a real {@code AgentLoop} actually recovering a prose-only
 * answer on a second {@code run()} call against the SAME {@code Session}) is proven separately, with a fake
 * HTTP provider, by {@code capability.CapabilityLlmPassTest#agentLoopRecoversFromProseOnlyAnswer} - this class
 * only proves the counting/decision logic {@link ProseAnswerRecovery} itself adds on top of that mechanism.
 */
public final class ProseAnswerRecoveryTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		ProseAnswerRecoveryTest.somethingAlreadyRecordedMeansNoFollowUp();
		ProseAnswerRecoveryTest.notDoneMeansNoFollowUp();
		ProseAnswerRecoveryTest.proseOnceThenRecordedIsRecovered();
		ProseAnswerRecoveryTest.proseTwiceMeansNoOutput();
		ProseAnswerRecoveryTest.combinedGettersSumBothRuns();
		ProseAnswerRecoveryTest.followUpTaskQuotesPreviousTextAndInstructsTools();
		ProseAnswerRecoveryTest.followUpTaskBoundsAVeryLongPreviousText();
		ProseAnswerRecoveryTest.followUpTaskHandlesNullOrBlankText();
		System.out.println("ProseAnswerRecoveryTest OK");
	}

	// -------------------------------------------------------------------------------------------------- recover()

	/** "a run that recorded something ⇒ no follow-up" (coordinator, 2026-09-21, required test). */
	private static void somethingAlreadyRecordedMeansNoFollowUp() throws Exception
	{
		final AgentRuntime.Outcome first = ProseAnswerRecoveryTest.outcome("run-1", AgentLoop.Status.DONE, 2, 300, 120, 0, 3,
			"Proposed isValid on Product.phone.", null);
		final ProseAnswerRecoveryTest.CountingRunner runner = new ProseAnswerRecoveryTest.CountingRunner(null);
		final ProseAnswerRecovery.Result result = ProseAnswerRecovery.recover(first, ProseAnswerRecoveryTest.recordedNothing(false), runner);

		ProseAnswerRecoveryTest.check("no follow-up attempted", !result.followUpAttempted);
		ProseAnswerRecoveryTest.check("runner never called", runner.calls == 0);
		ProseAnswerRecoveryTest.check("not recovered (nothing to recover)", !result.proseAnswerRecovered);
		ProseAnswerRecoveryTest.check("not NO_OUTPUT", !result.noOutput);
		ProseAnswerRecoveryTest.check("finalText is null", result.finalText == null);
		ProseAnswerRecoveryTest.check("latest() is the first outcome unchanged", result.latest() == first);
	}

	/** A run that did not end DONE (FAILED/BUDGET) is not this class's concern - its own status/detail already explains itself; no follow-up is ever attempted regardless of what the session recorded. */
	private static void notDoneMeansNoFollowUp() throws Exception
	{
		final AgentRuntime.Outcome first = ProseAnswerRecoveryTest.outcome("run-1", AgentLoop.Status.FAILED, 0, 50, 0, 0, 0, "",
			"LLM call failed: HTTP 429 ...");
		final ProseAnswerRecoveryTest.CountingRunner runner = new ProseAnswerRecoveryTest.CountingRunner(null);
		// recordedNothing(true): even though the session recorded nothing, a non-DONE run is never a "prose answer" case
		final ProseAnswerRecovery.Result result = ProseAnswerRecovery.recover(first, ProseAnswerRecoveryTest.recordedNothing(true), runner);

		ProseAnswerRecoveryTest.check("no follow-up attempted for a non-DONE run", !result.followUpAttempted);
		ProseAnswerRecoveryTest.check("runner never called", runner.calls == 0);
		ProseAnswerRecoveryTest.check("not NO_OUTPUT (that status belongs to a DONE-but-empty run only)", !result.noOutput);
	}

	/** "prose-only final answer ⇒ follow-up issued once, suggestions recorded, proseAnswerRecovered: true" (coordinator, required test). */
	private static void proseOnceThenRecordedIsRecovered() throws Exception
	{
		final AgentRuntime.Outcome first = ProseAnswerRecoveryTest.outcome("run-1", AgentLoop.Status.DONE, 2, 400, 150, 0, 2,
			"Here is what I would propose: isValid on Product.phone, and REGISTRY#lookup on Product.phone.", null);
		final AgentRuntime.Outcome followUp = ProseAnswerRecoveryTest.outcome("run-2", AgentLoop.Status.DONE, 1, 200, 60, 0, 2, "Recorded both as you asked.",
			null);
		// the fake session starts empty; the fake runner "records something" as a side effect of being called,
		// exactly like a real follow-up run mutating the SAME Session via its tool calls
		final boolean[] recordedAfterRunnerCalled = { false };
		final ProseAnswerRecovery.RecordedNothing check = new ProseAnswerRecovery.RecordedNothing()
		{
			@Override
			public boolean test()
			{
				return !recordedAfterRunnerCalled[0];
			}
		};
		final ProseAnswerRecoveryTest.CountingRunner runner = new ProseAnswerRecoveryTest.CountingRunner(followUp)
		{
			@Override
			AgentRuntime.Outcome doRun(final String pTask)
			{
				recordedAfterRunnerCalled[0] = true;
				return followUp;
			}
		};

		final ProseAnswerRecovery.Result result = ProseAnswerRecovery.recover(first, check, runner);

		ProseAnswerRecoveryTest.check("follow-up attempted", result.followUpAttempted);
		ProseAnswerRecoveryTest.check("runner called exactly once", runner.calls == 1);
		ProseAnswerRecoveryTest.check("recovered: true", result.proseAnswerRecovered);
		ProseAnswerRecoveryTest.check("not NO_OUTPUT", !result.noOutput);
		ProseAnswerRecoveryTest.check("finalText null on a genuine recovery", result.finalText == null);
		ProseAnswerRecoveryTest.check("latest() is the follow-up outcome", result.latest() == followUp);
		ProseAnswerRecoveryTest.check("the follow-up task quoted the first run's own text", runner.lastTask != null && runner.lastTask.contains("isValid on Product.phone"));
	}

	/** "prose twice ⇒ NO_OUTPUT, ok false" (coordinator, required test) - one follow-up only, never a second one. */
	private static void proseTwiceMeansNoOutput() throws Exception
	{
		final AgentRuntime.Outcome first = ProseAnswerRecoveryTest.outcome("run-1", AgentLoop.Status.DONE, 2, 400, 150, 0, 0, "I would propose X and Y.", null);
		final AgentRuntime.Outcome followUp = ProseAnswerRecoveryTest.outcome("run-2", AgentLoop.Status.DONE, 1, 250, 90, 0, 0, "Sure, X and Y as described.",
			null);
		final ProseAnswerRecoveryTest.CountingRunner runner = new ProseAnswerRecoveryTest.CountingRunner(followUp);
		// the session never records anything, whatever runs against it - exactly the "prose twice" case
		final ProseAnswerRecovery.Result result = ProseAnswerRecovery.recover(first, ProseAnswerRecoveryTest.recordedNothing(true), runner);

		ProseAnswerRecoveryTest.check("follow-up attempted", result.followUpAttempted);
		ProseAnswerRecoveryTest.check("runner called exactly ONCE - one follow-up only, never retried a second time", runner.calls == 1);
		ProseAnswerRecoveryTest.check("not recovered", !result.proseAnswerRecovered);
		ProseAnswerRecoveryTest.check("NO_OUTPUT: true", result.noOutput);
		ProseAnswerRecoveryTest.check("finalText carries the follow-up's own last text", "Sure, X and Y as described.".equals(result.finalText));
		ProseAnswerRecoveryTest.check("latest() is the follow-up outcome, itself still DONE (a caller must override ok/status from noOutput, not from status alone)",
			result.latest() == followUp && result.latest().status == AgentLoop.Status.DONE);
	}

	/** When the follow-up itself has no text (e.g. it ended FAILED), the ORIGINAL run's text is kept for {@link ProseAnswerRecovery.Result#finalText} rather than showing nothing. */
	private static void combinedGettersSumBothRuns() throws Exception
	{
		final AgentRuntime.Outcome first = ProseAnswerRecoveryTest.outcome("run-1", AgentLoop.Status.DONE, 2, 400, 150, 1, 3, "I would propose X.", null);
		final AgentRuntime.Outcome followUp = ProseAnswerRecoveryTest.outcome("run-2", AgentLoop.Status.FAILED, 1, 100, 0, 2, 1, "", "connector timeout");
		final ProseAnswerRecoveryTest.CountingRunner runner = new ProseAnswerRecoveryTest.CountingRunner(followUp);
		final ProseAnswerRecovery.Result result = ProseAnswerRecovery.recover(first, ProseAnswerRecoveryTest.recordedNothing(true), runner);

		ProseAnswerRecoveryTest.check("NO_OUTPUT (the follow-up recorded nothing either)", result.noOutput);
		ProseAnswerRecoveryTest.check("finalText falls back to the FIRST run's text when the follow-up's own is blank", "I would propose X.".equals(result.finalText));
		ProseAnswerRecoveryTest.check("combined steps = 2 + 1", result.combinedSteps() == 3);
		ProseAnswerRecoveryTest.check("combined toolCalls = 3 + 1", result.combinedToolCalls() == 4);
		ProseAnswerRecoveryTest.check("combined inputTokens = 400 + 100", result.combinedInputTokens() == 500);
		ProseAnswerRecoveryTest.check("combined outputTokens = 150 + 0", result.combinedOutputTokens() == 150);
		ProseAnswerRecoveryTest.check("combined refusedToolCalls = 1 + 2", result.combinedRefusedToolCalls() == 3);
	}

	// ----------------------------------------------------------------------------------------------- followUpTask()

	private static void followUpTaskQuotesPreviousTextAndInstructsTools()
	{
		final String task = ProseAnswerRecovery.followUpTask("Proposed isValid on Product.phone.");
		ProseAnswerRecoveryTest.check("quotes the previous text", task.contains("Proposed isValid on Product.phone."));
		ProseAnswerRecoveryTest.check("says nothing was recorded", task.toLowerCase(Locale.ROOT).contains("nothing") && task.toLowerCase(Locale.ROOT).contains("recorded"));
		ProseAnswerRecoveryTest.check("says the quoted text is data, not an instruction", task.toLowerCase(Locale.ROOT).contains("data")
			&& task.toLowerCase(Locale.ROOT).contains("never"));
		ProseAnswerRecoveryTest.check("tells it to record now with the matching tool, or note once", task.toLowerCase(Locale.ROOT).contains("record each item")
			&& task.toLowerCase(Locale.ROOT).contains("call note"));
		ProseAnswerRecoveryTest.check("says not to answer in prose only", task.toLowerCase(Locale.ROOT).contains("do not answer in prose"));
	}

	private static void followUpTaskBoundsAVeryLongPreviousText()
	{
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 1000; i++)
		{
			longText.append("proposal ").append(i).append(' ');
		}
		final String task = ProseAnswerRecovery.followUpTask(longText.toString());
		ProseAnswerRecoveryTest.check("bounded, never the whole long text verbatim", !task.contains("proposal 999"));
		ProseAnswerRecoveryTest.check("still contains the start of the text", task.contains("proposal 0 "));
	}

	private static void followUpTaskHandlesNullOrBlankText()
	{
		final String task = ProseAnswerRecovery.followUpTask(null);
		ProseAnswerRecoveryTest.check("a null previous text still produces a sane task", task != null && task.contains("(no text)"));
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static AgentRuntime.Outcome outcome(final String pRunId, final AgentLoop.Status pStatus, final int pSteps, final int pInputTokens,
		final int pOutputTokens, final int pRefusedToolCalls, final int pToolCalls, final String pText, final String pDetail)
	{
		return new AgentRuntime.Outcome(pRunId, pStatus, pSteps, pInputTokens, pOutputTokens, pRefusedToolCalls, pToolCalls, pText, pDetail);
	}

	private static ProseAnswerRecovery.RecordedNothing recordedNothing(final boolean pAlwaysEmpty)
	{
		return new ProseAnswerRecovery.RecordedNothing()
		{
			@Override
			public boolean test()
			{
				return pAlwaysEmpty;
			}
		};
	}

	/** A {@link ProseAnswerRecovery.FollowUpRunner} that counts its own calls and records the last task text it was given - overridden per test when the fake run must also mutate shared state (simulating a real follow-up recording something in the Session). */
	private static class CountingRunner implements ProseAnswerRecovery.FollowUpRunner
	{
		final AgentRuntime.Outcome toReturn;
		int calls;
		String lastTask;
		final List<String> tasks = new ArrayList<String>();

		CountingRunner(final AgentRuntime.Outcome pToReturn)
		{
			this.toReturn = pToReturn;
		}

		@Override
		public final AgentRuntime.Outcome run(final String pFollowUpTask) throws OperationException
		{
			this.calls++;
			this.lastTask = pFollowUpTask;
			this.tasks.add(pFollowUpTask);
			return this.doRun(pFollowUpTask);
		}

		AgentRuntime.Outcome doRun(final String pTask)
		{
			return this.toReturn;
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
