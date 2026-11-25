package com.on.ebx.factory.ebx;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orchestranetworks.addon.mame.common.process.ProcessStatus;
import com.orchestranetworks.addon.mame.model.config.DecisionNodeComparisonType;
import com.orchestranetworks.addon.mame.model.config.ParameterValue;
import com.orchestranetworks.addon.mame.model.decisiontree.DecisionField;
import com.orchestranetworks.addon.mame.model.decisiontree.DecisionNode;
import com.orchestranetworks.addon.mame.model.metadata.MatchingResultType;
import com.orchestranetworks.addon.mame.service.decisiontree.DecisionTreeExecutionResult;
import com.orchestranetworks.addon.mame.service.matching.MatchingProcess;

/**
 * JUnit-free: run the main, a failure throws. Covers {@link MameMatchingApi#buildResult}'s guarded reading
 * ({@link MameMatchingApi}'s own class Javadoc, 2026-09-22: {@code getDetail}/{@code getCompletionRate}/
 * {@code getDuration} can each throw once the matching process has genuinely finished - none of the three
 * may ever fail the whole call) against a FAKE {@link MatchingProcess}, with no live add-on, {@code Session}
 * or {@code AdaptationTable} involved at all - reproduces the real live exception class (a {@link
 * NullPointerException}, from the add-on's own {@code MatchingOperationProcess.getCurrentPhase().name()}
 * once the process's current phase is cleared on completion) without needing that internal type, which this
 * module deliberately never references directly (see {@link MameMatchingApi}'s own class Javadoc).
 */
public final class MameMatchingApiTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		MameMatchingApiTest.detailThrowsAfterCompletion_statusStillDone_reportNoteRecorded();
		MameMatchingApiTest.everyGuardedReadThrows_allThreeNoted_noneRethrown();
		MameMatchingApiTest.noFailure_reportNotesEmptyAndValuesReadNormally();
		MameMatchingApiTest.evaluatePair_neverThrows_degradesToUnavailableOutsideALiveServer();
		MameMatchingApiTest.buildTreeEvaluation_oneNodeTree_usesSingleScoreEntryRegardlessOfKey();
		MameMatchingApiTest.buildTreeEvaluation_twoNodeTree_indexBasedLookup();
		MameMatchingApiTest.buildTreeEvaluation_emptyNodeScores_diagnosticDescribesIt();
		MameMatchingApiTest.buildTreeEvaluation_nullExecution_isUnavailable();
		MameMatchingApiTest.isDataspaceLockedMessage_trueOnlyForTheLockedShape();
		MameMatchingApiTest.shouldRetryLock_trueOnlyWhileBudgetRemainsAndMessageIsLocked();
		MameMatchingApiTest.shouldKeepPolling_fakeClockAndStatusSequence();
		MameMatchingApiTest.finalResult_reusesLastProgress_neverTouchesProcessAgain();
		MameMatchingApiTest.finalResult_noPriorProgress_returnsUnknownValuesWithGivenNotes();
		MameMatchingApiTest.requestStop_callsCancelAndWaitsForTheTerminalStatus();
		MameMatchingApiTest.requestStop_neverConfirmed_isBoundedAndSaysTheProcessIsStillRunning();
		MameMatchingApiTest.requestStop_cancelThrows_reportedNeverRethrown();
		MameMatchingApiTest.withStop_keepsTheResultAndCarriesTheOutcome();
		System.out.println("MameMatchingApiTest OK");
	}

	// ------------------------------------------------------------------------------------- lot R12 (2026-09-27)

	/** {@link MameMatchingApi#isDataspaceLockedMessage} - pure, case-insensitive, null-safe. */
	private static void isDataspaceLockedMessage_trueOnlyForTheLockedShape()
	{
		MameMatchingApiTest.check("the exact live wording matches",
			MameMatchingApi.isDataspaceLockedMessage("invalid state - This service cannot be executed because the dataspace is locked."));
		MameMatchingApiTest.check("case-insensitive", MameMatchingApi.isDataspaceLockedMessage("THE DATASPACE IS LOCKED"));
		MameMatchingApiTest.check("null is never locked", !MameMatchingApi.isDataspaceLockedMessage(null));
		MameMatchingApiTest.check("an unrelated refusal is never locked", !MameMatchingApi.isDataspaceLockedMessage("matching is paused for this table"));
	}

	/** {@link MameMatchingApi#shouldRetryLock} - retries only while budget remains AND the message is the locked shape. */
	private static void shouldRetryLock_trueOnlyWhileBudgetRemainsAndMessageIsLocked()
	{
		final String locked = "the dataspace is locked";
		MameMatchingApiTest.check("attempt 1 of 5, locked: retry", MameMatchingApi.shouldRetryLock(locked, 1, 5));
		MameMatchingApiTest.check("attempt 4 of 5, locked: retry", MameMatchingApi.shouldRetryLock(locked, 4, 5));
		MameMatchingApiTest.check("attempt 5 of 5 (budget exhausted), locked: no more retry", !MameMatchingApi.shouldRetryLock(locked, 5, 5));
		MameMatchingApiTest.check("attempt 1 of 5, NOT locked: never retried regardless of budget", !MameMatchingApi.shouldRetryLock("matching is paused", 1, 5));
		MameMatchingApiTest.check("null message: never retried", !MameMatchingApi.shouldRetryLock(null, 1, 5));
	}

	/**
	 * {@link MameMatchingApi#shouldKeepPolling} against a fake clock/status sequence (task brief): RUNNING before
	 * the deadline and not cancelled keeps polling; a terminal status, a passed deadline, or a requested stop each
	 * independently stop it.
	 */
	private static void shouldKeepPolling_fakeClockAndStatusSequence()
	{
		MameMatchingApiTest.check("RUNNING, well before the deadline, not cancelled: keep polling",
			MameMatchingApi.shouldKeepPolling(ProcessStatus.RUNNING, 1000L, 5000L, false));
		MameMatchingApiTest.check("DONE, even before the deadline: stop", !MameMatchingApi.shouldKeepPolling(ProcessStatus.DONE, 1000L, 5000L, false));
		MameMatchingApiTest.check("FAILED: stop", !MameMatchingApi.shouldKeepPolling(ProcessStatus.FAILED, 1000L, 5000L, false));
		MameMatchingApiTest.check("CANCELLED: stop", !MameMatchingApi.shouldKeepPolling(ProcessStatus.CANCELLED, 1000L, 5000L, false));
		MameMatchingApiTest.check("RUNNING, but the deadline has passed: stop", !MameMatchingApi.shouldKeepPolling(ProcessStatus.RUNNING, 5001L, 5000L, false));
		MameMatchingApiTest.check("RUNNING, at the exact deadline instant: stop (strict less-than)",
			!MameMatchingApi.shouldKeepPolling(ProcessStatus.RUNNING, 5000L, 5000L, false));
		MameMatchingApiTest.check("RUNNING, well before the deadline, but a stop was requested: stop",
			!MameMatchingApi.shouldKeepPolling(ProcessStatus.RUNNING, 1000L, 5000L, true));
		MameMatchingApiTest.check("INITIALIZING (not terminal), before the deadline: keep polling",
			MameMatchingApi.shouldKeepPolling(ProcessStatus.INITIALIZING, 1000L, 5000L, false));
	}

	/**
	 * {@link MameMatchingApi#finalResult} (fix item 3): once the process is terminal, the last RUNNING snapshot's
	 * own detail/completionRate/durationMs/reportNotes survive verbatim - the method's own signature (no {@code
	 * MatchingProcess} parameter) is the proof that the process is never touched again.
	 */
	private static void finalResult_reusesLastProgress_neverTouchesProcessAgain()
	{
		final List<String> priorNotes = new ArrayList<String>();
		priorNotes.add("a note captured while still RUNNING");
		final MameMatchingApi.RunResult lastProgress = new MameMatchingApi.RunResult("RUNNING", 0.5, "halfway there", 1234L, priorNotes);
		final MameMatchingApi.RunResult result = MameMatchingApi.finalResult(ProcessStatus.DONE, lastProgress);
		MameMatchingApiTest.check("status is the TERMINAL one, not the last-progress one", "DONE".equals(result.status));
		// project ret, 2026-09-28: a DONE run is complete whatever its last snapshot said - 100 % and a FINISHED phase
		// that still names the last phase observed, so a reader is never told a finished run is half done
		MameMatchingApiTest.check("detail names the finish and the last observed phase", "FINISHED (last observed phase: halfway there)".equals(result.detail));
		MameMatchingApiTest.check("completionRate is 100 on a DONE run", result.completionRate == 100.0);
		MameMatchingApiTest.check("durationMs reused from the last RUNNING snapshot", result.durationMs == 1234L);
		MameMatchingApiTest.check("reportNotes reused verbatim - no new note added for the terminal read that never happened",
			result.reportNotes.size() == 1 && result.reportNotes.get(0).equals("a note captured while still RUNNING"));

		final MameMatchingApi.RunResult failed = MameMatchingApi.finalResult(ProcessStatus.FAILED, lastProgress);
		MameMatchingApiTest.check("a FAILED run keeps its last snapshot verbatim - only DONE is complete by definition",
			"FAILED".equals(failed.status) && "halfway there".equals(failed.detail) && failed.completionRate == 0.5);
		final MameMatchingApi.RunResult noPhase = MameMatchingApi.finalResult(ProcessStatus.DONE, new MameMatchingApi.RunResult("RUNNING", 0.1, null, 5L, new ArrayList<String>()));
		MameMatchingApiTest.check("DONE with no observed phase reads FINISHED", "FINISHED".equals(noPhase.detail) && noPhase.completionRate == 100.0);
	}

	/** No progress was ever captured (the process was already terminal at the very first status read) - unknown values, never a guess, never a crash. */
	private static void finalResult_noPriorProgress_returnsUnknownValuesWithGivenNotes()
	{
		final MameMatchingApi.RunResult result = MameMatchingApi.finalResult(ProcessStatus.FAILED, null);
		MameMatchingApiTest.check("status FAILED", "FAILED".equals(result.status));
		MameMatchingApiTest.check("detail is null - never a guess", result.detail == null);
		MameMatchingApiTest.check("completionRate is -1 - never a guess", result.completionRate == -1);
		MameMatchingApiTest.check("durationMs is -1 - never a guess", result.durationMs == -1);
		MameMatchingApiTest.check("no report notes - the terminal read that would have produced one never happened", result.reportNotes.isEmpty());
	}

	// -------------------------------------------------------------------------------------- buildTreeEvaluation

	/**
	 * Diagnostic-visibility lot (2026-09-22): the live incident this lot answers - a one-node AVERAGE tree
	 * whose {@code getNodeScores()} answered exactly one entry, under some key nobody could confirm was 0 (the
	 * {@code Integer} key semantics were never measured live - {@link MameMatchingApi}'s own class Javadoc).
	 * {@link MameMatchingApi#buildTreeEvaluation} must use that single entry regardless of its key.
	 */
	private static void buildTreeEvaluation_oneNodeTree_usesSingleScoreEntryRegardlessOfKey()
	{
		final Map<Integer, List<DecisionTreeExecutionResult.ScoreField>> nodeScores = new LinkedHashMap<Integer, List<DecisionTreeExecutionResult.ScoreField>>();
		final List<DecisionTreeExecutionResult.ScoreField> scores = new ArrayList<DecisionTreeExecutionResult.ScoreField>();
		scores.add(new FakeScoreField("name", 91.5, "Acme", "ACME Inc"));
		// key 7, deliberately NOT 0 - the one-node/one-entry special case must not depend on the key at all
		nodeScores.put(Integer.valueOf(7), scores);
		final List<DecisionNode> nodes = new ArrayList<DecisionNode>();
		nodes.add(new FakeDecisionNode("N1", Boolean.TRUE));
		final MameMatchingApi.TreeEvaluation evaluation = MameMatchingApi
			.buildTreeEvaluation(new FakeExecution(MatchingResultType.SUSPECTED, nodes, nodeScores));
		MameMatchingApiTest.check("verdict read through", "SUSPECTED".equals(evaluation.matchingResult));
		MameMatchingApiTest.check("one node name", evaluation.nodeNames.size() == 1 && "N1".equals(evaluation.nodeNames.get(0)));
		MameMatchingApiTest.check("last node name", "N1".equals(evaluation.lastNodeName));
		MameMatchingApiTest.check("the single score entry is used despite its key not being 0",
			evaluation.fieldScores.size() == 1 && "name".equals(evaluation.fieldScores.get(0).field) && evaluation.fieldScores.get(0).score == 91.5);
		MameMatchingApiTest.check("never marked failed - this is a successful read", !evaluation.failed);
		MameMatchingApiTest.check("diagnostic always present on a successful call: " + evaluation.diagnostic,
			evaluation.diagnostic != null && evaluation.diagnostic.contains("getNodeScores() answered 1 node(s)") && evaluation.diagnostic.contains("name")
				&& evaluation.diagnostic.contains("N1"));
	}

	/** Two or more nodes: the pre-lot index-based lookup (key == position in {@code getDecisionNodes()}) is unchanged. */
	private static void buildTreeEvaluation_twoNodeTree_indexBasedLookup()
	{
		final Map<Integer, List<DecisionTreeExecutionResult.ScoreField>> nodeScores = new LinkedHashMap<Integer, List<DecisionTreeExecutionResult.ScoreField>>();
		final List<DecisionTreeExecutionResult.ScoreField> scoresA = new ArrayList<DecisionTreeExecutionResult.ScoreField>();
		scoresA.add(new FakeScoreField("a", 10.0, "x", "y"));
		final List<DecisionTreeExecutionResult.ScoreField> scoresB = new ArrayList<DecisionTreeExecutionResult.ScoreField>();
		scoresB.add(new FakeScoreField("b", 20.0, "x", "y"));
		nodeScores.put(Integer.valueOf(0), scoresA);
		nodeScores.put(Integer.valueOf(1), scoresB);
		final List<DecisionNode> nodes = new ArrayList<DecisionNode>();
		nodes.add(new FakeDecisionNode("N1", Boolean.FALSE));
		nodes.add(new FakeDecisionNode("N2", Boolean.TRUE));
		final MameMatchingApi.TreeEvaluation evaluation = MameMatchingApi
			.buildTreeEvaluation(new FakeExecution(MatchingResultType.MATCHED, nodes, nodeScores));
		MameMatchingApiTest.check("both nodes' scores collected, node order",
			evaluation.fieldScores.size() == 2 && "a".equals(evaluation.fieldScores.get(0).field) && "b".equals(evaluation.fieldScores.get(1).field));
		MameMatchingApiTest.check("last node is N2", "N2".equals(evaluation.lastNodeName));
	}

	/** A successful call that answers no per-field scores at all - never marked {@code failed}, but the diagnostic says exactly what was seen (task brief item 1, "getNodeScores() is empty"). */
	private static void buildTreeEvaluation_emptyNodeScores_diagnosticDescribesIt()
	{
		final List<DecisionNode> nodes = new ArrayList<DecisionNode>();
		nodes.add(new FakeDecisionNode("N1", Boolean.TRUE));
		final MameMatchingApi.TreeEvaluation evaluation = MameMatchingApi
			.buildTreeEvaluation(new FakeExecution(MatchingResultType.SUSPECTED, nodes, new LinkedHashMap<Integer, List<DecisionTreeExecutionResult.ScoreField>>()));
		MameMatchingApiTest.check("no field scores", evaluation.fieldScores.isEmpty());
		MameMatchingApiTest.check("not failed - the call itself succeeded", !evaluation.failed);
		MameMatchingApiTest.check("diagnostic says 0 node(s): " + evaluation.diagnostic,
			evaluation.diagnostic != null && evaluation.diagnostic.contains("getNodeScores() answered 0 node(s)"));
	}

	private static void buildTreeEvaluation_nullExecution_isUnavailable()
	{
		final MameMatchingApi.TreeEvaluation evaluation = MameMatchingApi.buildTreeEvaluation(null);
		MameMatchingApiTest.check("matchingResult null", evaluation.matchingResult == null);
		MameMatchingApiTest.check("no diagnostic - never attempted", evaluation.diagnostic == null);
		MameMatchingApiTest.check("not failed - never attempted, not a failure", !evaluation.failed);
	}

	/**
	 * Review-match-plan-simulation lot (2026-09-22): {@link MameMatchingApi#evaluatePair} calls {@code
	 * OperationsFactory.getMatchingOperations(Session)}, whose singleton factory is only ever initialized by a
	 * live EBX server - outside one (exactly this test's own situation, and every other test in this module),
	 * that call itself throws. This test proves the method's own documented contract - "any {@code
	 * RuntimeException} degrades to {@link MameMatchingApi.TreeEvaluation#UNAVAILABLE}, never propagated" -
	 * rather than merely asserting it in prose: it is the one method in this class safely callable with no
	 * live add-on, {@code Session}, or {@code Adaptation} at all, precisely because it is designed to degrade
	 * gracefully instead of requiring one.
	 */
	private static void evaluatePair_neverThrows_degradesToUnavailableOutsideALiveServer()
	{
		final MameMatchingApi.TreeEvaluation evaluation = MameMatchingApi.evaluatePair(null, null, null, null);
		MameMatchingApiTest.check("never throws, degrades instead", evaluation != null);
		MameMatchingApiTest.check("matchingResult is null", evaluation.matchingResult == null);
		MameMatchingApiTest.check("no node names", evaluation.nodeNames.isEmpty());
		MameMatchingApiTest.check("no last node", evaluation.lastNodeName == null);
		MameMatchingApiTest.check("no field scores - read as \"not verified for this pair\", never as \"zero fields\"", evaluation.fieldScores.isEmpty());
		// diagnostic-visibility lot (2026-09-22): OperationsFactory.getMatchingOperations(Session) itself throws
		// outside a live server (class Javadoc) - evaluatePair's own catch block must now record that as a
		// diagnostic (exception class/message/frames), never leave it silent the way the pre-lot code did.
		MameMatchingApiTest.check("marked failed - the call itself threw, this is not merely \"never attempted\"", evaluation.failed);
		MameMatchingApiTest.check("diagnostic names the exception class: " + evaluation.diagnostic,
			evaluation.diagnostic != null && evaluation.diagnostic.contains("Exception") && evaluation.diagnostic.contains(" at "));
	}

	/**
	 * Reproduces the live incident, 2026-09-22 09:04 ({@code POST /projects/Companies/matching/run?scope=all},
	 * 107 records, 0 MATCHED, 38 SUSPECTED): {@code getDetail()} throws once the process has finished - the
	 * run's own outcome ({@code DONE}, from {@code getMatchingStatus()} alone) must survive it, never a bare
	 * exception out of {@link MameMatchingApi#launchScoped}.
	 */
	private static void detailThrowsAfterCompletion_statusStillDone_reportNoteRecorded()
	{
		final MameMatchingApi.RunResult result = MameMatchingApi.buildResult(ProcessStatus.DONE, new FakeProcess()
		{
			@Override
			public String getDetail()
			{
				throw new NullPointerException(
					"Cannot invoke \"RunMatchPhase.name()\" because the return value of \"MatchingOperationProcess.getCurrentPhase()\" is null");
			}
		});
		MameMatchingApiTest.check("status is still DONE, unaffected by the guarded read failing", "DONE".equals(result.status));
		MameMatchingApiTest.check("done() reports true", result.done());
		MameMatchingApiTest.check("detail is null, not a crash", result.detail == null);
		MameMatchingApiTest.check("exactly one reportNote, naming getDetail", result.reportNotes.size() == 1 && result.reportNotes.get(0).startsWith("getDetail()"));
		MameMatchingApiTest.check("the reportNote names the real exception class", result.reportNotes.get(0).contains("NullPointerException"));
		MameMatchingApiTest.check("completionRate and durationMs are still read normally (only getDetail was made to fail)",
			result.completionRate == 1.0 && result.durationMs == 900L);
	}

	private static void everyGuardedReadThrows_allThreeNoted_noneRethrown()
	{
		final MameMatchingApi.RunResult result = MameMatchingApi.buildResult(ProcessStatus.DONE, new FakeProcess()
		{
			@Override
			public String getDetail()
			{
				throw new IllegalStateException("detail boom");
			}

			@Override
			public double getCompletionRate()
			{
				throw new IllegalStateException("rate boom");
			}

			@Override
			public Duration getDuration()
			{
				throw new IllegalStateException("duration boom");
			}
		});
		MameMatchingApiTest.check("status still DONE", "DONE".equals(result.status));
		MameMatchingApiTest.check("completionRate defaults to -1 on failure", result.completionRate == -1);
		MameMatchingApiTest.check("durationMs defaults to -1 on failure", result.durationMs == -1);
		MameMatchingApiTest.check("all three failures noted, none rethrown (this call itself did not throw)", result.reportNotes.size() == 3);
	}

	private static void noFailure_reportNotesEmptyAndValuesReadNormally()
	{
		final MameMatchingApi.RunResult result = MameMatchingApi.buildResult(ProcessStatus.RUNNING, new FakeProcess());
		MameMatchingApiTest.check("status RUNNING, not done", !result.done());
		MameMatchingApiTest.check("no report notes when nothing throws", result.reportNotes.isEmpty());
		MameMatchingApiTest.check("detail read normally", "all good".equals(result.detail));
		MameMatchingApiTest.check("completionRate read normally", result.completionRate == 1.0);
		MameMatchingApiTest.check("durationMs read normally", result.durationMs == 900L);
	}

	// ------------------------------------------------------------------------------------- lot M4 (2026-10-03), defect 1

	/** A fake process that goes CANCELLING at cancel() and CANCELLED after {@code pPollsToConfirm} status reads (0 = never). */
	private static final class StoppableProcess extends FakeProcess
	{
		private final int pollsToConfirm;
		boolean cancelCalled;
		private int reads;

		StoppableProcess(final int pPollsToConfirm)
		{
			this.pollsToConfirm = pPollsToConfirm;
		}

		@Override
		public ProcessStatus getMatchingStatus()
		{
			if (!this.cancelCalled)
			{
				return ProcessStatus.RUNNING;
			}
			this.reads++;
			return this.pollsToConfirm > 0 && this.reads >= this.pollsToConfirm ? ProcessStatus.CANCELLED : ProcessStatus.CANCELLING;
		}

		@Override
		public void cancel()
		{
			this.cancelCalled = true;
		}
	}

	/** The add-on's public stop is called, and the stop is "confirmed" only once its status is terminal. */
	private static void requestStop_callsCancelAndWaitsForTheTerminalStatus()
	{
		final StoppableProcess process = new StoppableProcess(3);
		final MameMatchingApi.StopOutcome outcome = MameMatchingApi.requestStop(process, 5000L, 2L);
		MameMatchingApiTest.check("MatchingProcess.cancel() was called", process.cancelCalled);
		MameMatchingApiTest.check("requested", outcome.requested);
		MameMatchingApiTest.check("confirmed once CANCELLED was read", outcome.confirmed && "CANCELLED".equals(outcome.finalStatus));
		MameMatchingApiTest.check("no note", outcome.note == null);
		MameMatchingApiTest.check("the sentence says the add-on confirmed: " + outcome.sentence(), outcome.sentence().startsWith("The add-on confirmed the stop (CANCELLED after "));
		final MameMatchingApi.RunResult result = new MameMatchingApi.RunResult("CANCELLED", 0.4, "MATCHING", 10L, new ArrayList<String>()).withStop(outcome);
		MameMatchingApiTest.check("a confirmed stop is not 'unconfirmed'", !result.stopUnconfirmed());
	}

	/** The add-on never leaves CANCELLING: the wait is bounded and the sentence says the process is still running. */
	private static void requestStop_neverConfirmed_isBoundedAndSaysTheProcessIsStillRunning()
	{
		final StoppableProcess process = new StoppableProcess(0);
		final long before = System.currentTimeMillis();
		final MameMatchingApi.StopOutcome outcome = MameMatchingApi.requestStop(process, 60L, 5L);
		final long took = System.currentTimeMillis() - before;
		MameMatchingApiTest.check("bounded wait (took " + took + " ms)", took < 2000L);
		MameMatchingApiTest.check("requested but not confirmed", outcome.requested && !outcome.confirmed && "CANCELLING".equals(outcome.finalStatus));
		MameMatchingApiTest.check("the sentence says it is still running: " + outcome.sentence(), outcome.sentence().startsWith("The add-on's process is still running"));
		final MameMatchingApi.RunResult result = new MameMatchingApi.RunResult("CANCELLING", 0.4, "MATCHING", 10L, new ArrayList<String>()).withStop(outcome);
		MameMatchingApiTest.check("flagged unconfirmed", result.stopUnconfirmed());
	}

	/** cancel() itself failing is reported, never rethrown, and counts as not stopped. */
	private static void requestStop_cancelThrows_reportedNeverRethrown()
	{
		final MameMatchingApi.StopOutcome outcome = MameMatchingApi.requestStop(new FakeProcess()
		{
			@Override
			public ProcessStatus getMatchingStatus()
			{
				return ProcessStatus.RUNNING;
			}

			@Override
			public void cancel()
			{
				throw new IllegalStateException("cancel boom");
			}
		}, 50L, 5L);
		MameMatchingApiTest.check("not requested, not confirmed", !outcome.requested && !outcome.confirmed);
		MameMatchingApiTest.check("note carries the exception", outcome.note != null && outcome.note.contains("cancel boom"));
		MameMatchingApiTest.check("sentence: cannot be stopped from here: " + outcome.sentence(), outcome.sentence().contains("it cannot be stopped from here"));
	}

	private static void withStop_keepsTheResultAndCarriesTheOutcome()
	{
		final MameMatchingApi.RunResult plain = new MameMatchingApi.RunResult("RUNNING", 0.5, "GROUPING", 99L, new ArrayList<String>());
		MameMatchingApiTest.check("no stop by default", plain.stop == null && !plain.stopUnconfirmed());
		final MameMatchingApi.RunResult stopped = plain.withStop(new MameMatchingApi.StopOutcome(true, false, "CANCELLING", 1000L, null));
		MameMatchingApiTest.check("same values, outcome attached", "RUNNING".equals(stopped.status) && stopped.completionRate == 0.5 && stopped.stop != null);
	}

	/** A well-behaved {@link MatchingProcess} - each test overrides one method at a time to inject a failure, a minimal hand-built fixture matching this codebase's own convention (no mocking library anywhere in this module). */
	private static class FakeProcess implements MatchingProcess
	{
		@Override
		public ProcessStatus getMatchingStatus()
		{
			return ProcessStatus.DONE;
		}

		@Override
		public String getDetail()
		{
			return "all good";
		}

		@Override
		public LocalDateTime getExecutionDate()
		{
			return LocalDateTime.now();
		}

		@Override
		public double getCompletionRate()
		{
			return 1.0;
		}

		@Override
		public Duration getDuration()
		{
			return Duration.ofMillis(900);
		}

		@Override
		public void cancel()
		{
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	// --------------------------------------------------------------------------------------------- fake add-on types

	/** Hand-built fake, like {@link FakeProcess} - {@code DecisionTreeExecutionResult} is an interface (`javap`, 2026-09-22), so no mocking library is needed. */
	private static final class FakeExecution implements DecisionTreeExecutionResult
	{
		private final MatchingResultType result;
		private final List<DecisionNode> nodes;
		private final Map<Integer, List<ScoreField>> nodeScores;

		FakeExecution(final MatchingResultType pResult, final List<DecisionNode> pNodes, final Map<Integer, List<ScoreField>> pNodeScores)
		{
			this.result = pResult;
			this.nodes = pNodes;
			this.nodeScores = pNodeScores;
		}

		@Override
		public MatchingResultType getMatchingResult()
		{
			return this.result;
		}

		@Override
		public Iterable<DecisionNode> getDecisionNodes()
		{
			return this.nodes;
		}

		@Override
		public Map<Integer, List<ScoreField>> getNodeScores()
		{
			return this.nodeScores;
		}
	}

	/** Only {@code getName()}/{@code isLastNode()} matter to {@link MameMatchingApi#buildTreeEvaluation} - every other method of this interface is unused by this codebase (see that method's own Javadoc) and returns null. */
	private static final class FakeDecisionNode implements DecisionNode
	{
		private final String name;
		private final Boolean lastNode;

		FakeDecisionNode(final String pName, final Boolean pLastNode)
		{
			this.name = pName;
			this.lastNode = pLastNode;
		}

		@Override
		public String getName()
		{
			return this.name;
		}

		@Override
		public DecisionNodeComparisonType getComparisonFunction()
		{
			return null;
		}

		@Override
		public Collection<ParameterValue> getComparisonFunctionParameters()
		{
			return null;
		}

		@Override
		public Collection<DecisionField> getDecisionFields()
		{
			return null;
		}

		@Override
		public MatchingResultType getMatchingResult()
		{
			return null;
		}

		@Override
		public Boolean isLastNode()
		{
			return this.lastNode;
		}
	}

	/** Hand-built fake for {@code DecisionTreeExecutionResult$ScoreField} (`javap`, 2026-09-22: an interface). */
	private static final class FakeScoreField implements DecisionTreeExecutionResult.ScoreField
	{
		private final String field;
		private final double score;
		private final Object pivotRawValue;
		private final Object suspectRawValue;

		FakeScoreField(final String pField, final double pScore, final Object pPivotRawValue, final Object pSuspectRawValue)
		{
			this.field = pField;
			this.score = pScore;
			this.pivotRawValue = pPivotRawValue;
			this.suspectRawValue = pSuspectRawValue;
		}

		@Override
		public double getScore()
		{
			return this.score;
		}

		@Override
		public String getField()
		{
			return this.field;
		}

		@Override
		public String getFieldType()
		{
			return null;
		}

		@Override
		public Object getPivotRecordRawValue()
		{
			return this.pivotRawValue;
		}

		@Override
		public Object getSuspectRecordRawValue()
		{
			return this.suspectRawValue;
		}
	}
}
