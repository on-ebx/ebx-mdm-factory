package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.ForeignWording;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.orchestranetworks.addon.mame.common.InvalidParameterException;
import com.orchestranetworks.addon.mame.common.InvalidStateException;
import com.orchestranetworks.addon.mame.common.process.ProcessStatus;
import com.orchestranetworks.addon.mame.model.SimulationResult;
import com.orchestranetworks.addon.mame.model.decisiontree.DecisionNode;
import com.orchestranetworks.addon.mame.service.OperationsFactory;
import com.orchestranetworks.addon.mame.service.decisiontree.DecisionTreeExecutionResult;
import com.orchestranetworks.addon.mame.service.matching.MatchingEngineResult;
import com.orchestranetworks.addon.mame.service.matching.MatchingOperations;
import com.orchestranetworks.addon.mame.service.matching.MatchingProcess;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * The ONE class that touches the Match and Merge add-on's public Java API (`docs/MATCHING.md` &sect;4.4,
 * coordinator review of 1cdb8bd) - {@code com.orchestranetworks.addon.mame.service.*}, found by {@code javap}
 * on {@code ebx-ps-fasttrack-server/lib/ebx-addons.jar} and read from that same server's on-disk Javadoc
 * ({@code webapps/ebx-addon-mame/www/common/html/onHelp/advanced/Java_API/...}), 2026-09-21. Every other
 * class of this module (including {@code ebx.MatchingConfigurator}, the only caller) deals only in this
 * class' own plain return types ({@link RunResult}, {@link SimulationOutcome}) - never an add-on type -
 * so nothing outside this file ever needs to import an add-on class.
 * <p>
 * <b>The {@code LinkageError} guard for a server without this add-on installed lives in the CALLER
 * ({@code ebx.MatchingConfigurator}), not in this class</b> - a correction from this class' first version,
 * which caught it here instead. {@code MameMatchingApi.class} itself references add-on types in its method
 * signatures and {@code throws} clauses ({@code InvalidParameterException}, {@code ProcessStatus}, {@code
 * SimulationResult}, ...); if the add-on is not on the classpath, the {@code NoClassDefFoundError} is
 * raised while the JVM loads/verifies THIS class - which happens at the CALLER's own first reference to
 * it (the {@code invokestatic} that resolves {@link #launchScoped}/{@link #simulate}), before this class'
 * own method body - and any {@code catch} written inside that body, wrapping code that never got a chance
 * to run, cannot catch it. {@code launchScoped}/{@link #simulate}'s own signatures stay free of add-on
 * types (only {@code Session}/{@code AdaptationTable}/{@code Adaptation}/{@code String}/{@code long} in,
 * {@link RunResult}/{@link SimulationOutcome} out) specifically so the caller CAN safely reference this
 * class and catch {@code LinkageError} around exactly that call, without the error being able to occur
 * anywhere else in {@code MatchingConfigurator}'s own frame. <b>Reasoned from JVM class-loading/
 * verification semantics, not observed</b> - no server without the MAME add-on installed was available to
 * actually trigger this path this session.
 * <p>
 * <b>Replaces the earlier REST-loopback design entirely</b> (the coordinator's own review: "REPLACE THE
 * REST LOOPBACK OF matching/run BY THE ADD-ON'S PUBLIC JAVA API"). No HTTP call, no agent-account
 * credentials on this path any more - {@link #launchScoped}/{@link #simulate} run with the CALLER's own
 * session, exactly like {@code ebx.StagingLoader}/{@code MasterLoader}.
 * <p>
 * <b>{@code matchRecords} is asynchronous; this class never blocks unboundedly on it.</b> The add-on's own
 * {@code ProcessOperations.waitingForBackgroundProcessDone(AdaptationTable)} has no timeout parameter at
 * all (READ, the Java API's own Javadoc) - blocking a JAX-RS request thread on it indefinitely would be a
 * real hazard if the add-on ever hangs. {@link #launchScoped} instead polls {@code MatchingProcess
 * .getMatchingStatus()} itself, on a short fixed interval, up to an explicit {@code pMaxWaitMs} deadline,
 * and returns whatever status it has when that deadline passes - {@code RUNNING}, not an error. The caller
 * ({@code ebx.MatchingConfigurator.run}) only refreshes {@code Xref.goldenKey} when the returned status is
 * {@code DONE}; a still-{@code RUNNING} result is reported as such, with the recommendation to re-run later
 * once the add-on's own background process has actually finished.
 * <p>
 * <b>{@link #simulate} is read-only and zero-risk by the add-on's own contract</b> (Java API Javadoc, quoted
 * in `docs/MATCHING.md`: "matching results are not persisted into the MatchResult table") - it never writes
 * anything, so it is safe to expose without the ownership/scoping guards {@link #launchScoped} needs.
 * <p>
 * <b>2026-09-22, 09:04 - the first real {@code matching/run}, real bare 500, fixed.</b> {@code POST
 * /projects/Companies/matching/run?scope=all} matched all 107 records to completion (0 MATCHED, 38
 * SUSPECTED, 0.9s - {@code ebxLog/ebx-addon-mame.log}'s own summary), then this method itself threw:
 * {@code NullPointerException: Cannot invoke "RunMatchPhase.name()" because the return value of
 * "MatchingOperationProcess.getCurrentPhase()" is null}, at the (former) single {@code return new
 * RunResult(status.name(), process.getCompletionRate(), process.getDetail(), ...)} line. {@code javap -p} on
 * the deployed {@code com.orchestranetworks.addon.mame.common.process.MatchingOperationProcess} (webapp
 * classes, `ebx-addon-mame/WEB-INF/classes`, the concrete type behind the {@link MatchingProcess} interface
 * this class holds) confirms {@code getDetail()} depends on {@code getCurrentPhase()}, which can legitimately
 * answer null once the process has finished (an internal type - {@code common.process}, not {@code
 * service.*} - this class deliberately never references it directly, so this could only be guarded at the
 * CALL, never fixed by inspecting or patching the add-on's own code). The lesson: a report-only read
 * ({@code getDetail}/{@code getCompletionRate}/{@code getDuration}) can throw AFTER the process has
 * genuinely finished - none of the three may ever be allowed to fail the whole call when the one thing that
 * actually matters, {@link MatchingProcess#getMatchingStatus()}, already answered a terminal status. Each of
 * the three is now read independently ({@link #safeDetail}/{@link #safeCompletionRate}/{@link
 * #safeDurationMs}), a failure recorded as a {@link RunResult#reportNotes} entry naming the method and the
 * exception class/message, never re-thrown. {@code getMatchingStatus()} itself is NOT wrapped this way - it
 * is what {@link RunResult#done()} keys on, and it answered correctly throughout the run that hit this bug
 * (the poll loop above already completed on it before the NPE ever fired) - wrapping it too would risk
 * hiding a genuine "the process itself cannot be read" failure behind a fabricated status.
 * <p>
 * <b>No public getter for the MATCHED/SUSPECTED/isolated/group counts the log line shows.</b> Same {@code
 * javap} pass: {@code MatchingOperationProcess} keeps {@code matchedNumber}/{@code suspectedNumber}/{@code
 * isolatedRecordNumber}/{@code processedGroup} as PRIVATE fields with no accessor (the sole public numeric
 * getter besides the ones on the {@link MatchingProcess} interface is {@code getSelectedRecordNumber()},
 * unrelated to this breakdown); {@link MatchingProcess} itself (the only type this class may reference, per
 * this class' own "public API only" rule above) declares only {@code getMatchingStatus}/{@code getDetail}/
 * {@code getExecutionDate}/{@code getCompletionRate}/{@code getDuration}/{@code cancel} - none of them the
 * per-outcome counts either. {@code ProcessInfo getProcessInfo(Locale)} (also on the concrete class, also
 * off-limits) only carries a UI progress percentage/remaining-time/error string, not these counts either.
 * {@link RunResult} therefore does NOT carry a matched/suspected/isolated/group breakdown - there is no
 * supported way to read it from Java at all; a caller that needs it has only {@code ebxLog/ebx-addon-mame.log}'s
 * own summary line (never scraped by this class - CLAUDE.md's own "an internal API found by javap is
 * build-specific" caution applies doubly to a log line, which is not even an API).
 * <p>
 * <p>
 * <b>lot R12 (2026-09-27, docs/MATCHING.md &sect;41).</b> Three related fixes, live evidence B/C in
 * {@code ebx.MatchingConfigurator}'s own class Javadoc: (1) {@link #launchScoped} retries a {@code matchRecords}
 * refusal whose message is the "dataspace is locked" shape ({@link #isDataspaceLockedMessage}/{@link
 * #shouldRetryLock}) instead of failing the entity outright - a real live sequence (project PersVol7) had one
 * entity's own run still holding the master dataspace when the next entity's own {@code matchRecords} call was
 * made; (2) the poll loop now also stops on a job's own requested stop ({@link #shouldKeepPolling}, {@code
 * FactoryJobs.Handle}), not only the deadline; (3) once {@code pStatus} is terminal, {@link #buildResult} (and
 * therefore {@code getDetail()}/{@code getCompletionRate()}/{@code getDuration()}) is NEVER called again - {@link
 * #finalResult} reuses whatever was captured during the LAST running iteration instead, so the "getDetail():
 * NullPointerException" report note this class' own 2026-09-22 fix used to add on every completed run no longer
 * appears at all (the read that used to produce it is simply never attempted once the process is genuinely done).
 * <p>
 * Package-private: only {@code ebx.MatchingConfigurator} calls this class.
 */
final class MameMatchingApi
{
	/** Fixed poll interval while waiting for an asynchronous matching process - short enough to report a fast completion promptly, coarse enough not to hammer the add-on. */
	private static final long POLL_INTERVAL_MS = 500;

	private MameMatchingApi()
	{
	}

	// -------------------------------------------------------------------------------------------------------- launch

	static final class RunResult
	{
		/** {@code ProcessStatus.name()} - one of INITIALIZING/RUNNING/DONE/FAILED/CANCELLING/CANCELLED - read OUTSIDE the guarded reads below, see class Javadoc: this is the one call this class trusts unconditionally. */
		final String status;
		/** {@code -1} if {@code getCompletionRate()} itself threw (see {@link #reportNotes}), a real rate otherwise. */
		final double completionRate;
		/** {@code MatchingProcess.getDetail()} - "what the add-on is doing in the background", not necessarily an error; {@code null} if the read itself threw (see {@link #reportNotes}). */
		final String detail;
		/** {@code -1} if the add-on reports no duration yet, OR if {@code getDuration()} itself threw (see {@link #reportNotes}) - the two are indistinguishable to a caller, both mean "no reliable duration". */
		final long durationMs;
		/** One entry per report-only read ({@code getDetail}/{@code getCompletionRate}/{@code getDuration}) that threw - see class Javadoc, 2026-09-22. Never affects {@link #status} or {@link #done()}. Empty, never null. */
		final List<String> reportNotes;
		/** Lot M4 (2026-10-03): what happened to the add-on's process when the job asked it to stop; {@code null} when no stop was asked (the normal case). */
		final StopOutcome stop;

		RunResult(final String pStatus, final double pCompletionRate, final String pDetail, final long pDurationMs, final List<String> pReportNotes)
		{
			this(pStatus, pCompletionRate, pDetail, pDurationMs, pReportNotes, null);
		}

		RunResult(final String pStatus, final double pCompletionRate, final String pDetail, final long pDurationMs, final List<String> pReportNotes, final StopOutcome pStop)
		{
			this.status = pStatus;
			this.completionRate = pCompletionRate;
			this.detail = pDetail;
			this.durationMs = pDurationMs;
			this.reportNotes = pReportNotes;
			this.stop = pStop;
		}

		/** The same result carrying the outcome of a stop request (lot M4). */
		RunResult withStop(final StopOutcome pStop)
		{
			return new RunResult(this.status, this.completionRate, this.detail, this.durationMs, this.reportNotes, pStop);
		}

		/** Lot M4: {@code true} when a stop was asked and the add-on's process is NOT known to have ended - it may still be consuming the instance. */
		boolean stopUnconfirmed()
		{
			return this.stop != null && !this.stop.confirmed;
		}

		boolean done()
		{
			return "DONE".equals(this.status);
		}
	}

	/**
	 * Lot M4 (2026-10-03, defect 1, project Vol1m job 161): how long a stopped job waits for the add-on to confirm that its
	 * process ended. {@code MatchingProcess.cancel()} is cooperative (it aborts the add-on's {@code ClusterProducer} and sets the
	 * status CANCELLING; the status becomes CANCELLED only when the process' own thread reaches {@code finish()}), so a bounded
	 * wait is the only honest way to say "stopped". Past it the job ends anyway and says the process is still running.
	 */
	static final long STOP_CONFIRM_WAIT_MS = 120000;

	/**
	 * What a stop request did to the add-on's process (lot M4): {@link #requested} = {@code MatchingProcess.cancel()} was
	 * called without throwing; {@link #confirmed} = the process then reported a terminal status ({@code CANCELLED}, or {@code
	 * DONE}/{@code FAILED} if it ended on its own meanwhile); {@link #finalStatus} the last status read; {@link #waitedMs} how
	 * long the wait lasted; {@link #note} set when {@code cancel()} threw or the wait was interrupted.
	 */
	static final class StopOutcome
	{
		final boolean requested;
		final boolean confirmed;
		final String finalStatus;
		final long waitedMs;
		final String note;

		StopOutcome(final boolean pRequested, final boolean pConfirmed, final String pFinalStatus, final long pWaitedMs, final String pNote)
		{
			this.requested = pRequested;
			this.confirmed = pConfirmed;
			this.finalStatus = pFinalStatus;
			this.waitedMs = pWaitedMs;
			this.note = pNote;
		}

		/** One honest sentence for the step message / job result. */
		String sentence()
		{
			final String seconds = String.format(Locale.ROOT, "%.1f", Double.valueOf(this.waitedMs / 1000.0));
			if (this.confirmed)
			{
				return "The add-on confirmed the stop (" + this.finalStatus + " after " + seconds + " s).";
			}
			if (!this.requested)
			{
				return "The add-on's process is still running: it cannot be stopped from here (MatchingProcess.cancel() failed: " + this.note + ").";
			}
			return "The add-on's process is still running: it has not confirmed the stop after " + seconds + " s (status " + this.finalStatus + ")"
				+ (this.note == null ? "." : " - " + this.note);
		}
	}

	/**
	 * Lot M4 (defect 1): asks the add-on to stop a running matching process through its public API ({@code
	 * MatchingProcess.cancel()}, found by {@code javap} on {@code ebx-addons.jar} 2026-10-03; its implementation {@code
	 * OperationProcess.cancel} aborts the {@code ClusterProducer} - the 8 {@code ebx-clustering-N} threads - and the status goes
	 * CANCELLING then CANCELLED), then polls {@link MatchingProcess#getMatchingStatus()} every {@code pPollMs} for at most
	 * {@code pMaxWaitMs} until it is terminal. Never throws: a failing {@code cancel()} or an interrupted wait is returned in
	 * {@link StopOutcome#note}. Pure with respect to everything but the process it is given - unit-tested with a fake one.
	 */
	static StopOutcome requestStop(final MatchingProcess pProcess, final long pMaxWaitMs, final long pPollMs)
	{
		final long started = System.currentTimeMillis();
		try
		{
			pProcess.cancel();
		}
		catch (final RuntimeException ex)
		{
			return new StopOutcome(false, false, MameMatchingApi.statusNameOrUnknown(pProcess), System.currentTimeMillis() - started, ex.getClass().getName() + ": " + ex.getMessage());
		}
		ProcessStatus status = pProcess.getMatchingStatus();
		String note = null;
		while (!MameMatchingApi.isTerminal(status) && System.currentTimeMillis() - started < pMaxWaitMs)
		{
			try
			{
				Thread.sleep(Math.max(1, pPollMs));
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				note = "the wait for the add-on's confirmation was interrupted";
				break;
			}
			status = pProcess.getMatchingStatus();
		}
		return new StopOutcome(true, MameMatchingApi.isTerminal(status), status == null ? "UNKNOWN" : status.name(), System.currentTimeMillis() - started, note);
	}

	private static String statusNameOrUnknown(final MatchingProcess pProcess)
	{
		try
		{
			final ProcessStatus status = pProcess.getMatchingStatus();
			return status == null ? "UNKNOWN" : status.name();
		}
		catch (final RuntimeException ex)
		{
			return "UNKNOWN";
		}
	}

	/**
	 * lot R12 (2026-09-27, docs/MATCHING.md &sect;41, live evidence B/C): retry budget for a {@code matchRecords}
	 * refusal whose message contains "dataspace is locked" - measured live twice the same evening (project
	 * PersVol7: Personne's own run took 34s under the OLD 30s default, still held the master dataspace when
	 * {@code runInternal} moved on to Adresse, whose own {@code matchRecords} call then refused outright). 5
	 * attempts, 10s apart (a round trip through the add-on's own async schema/lock bookkeeping is not
	 * instantaneous) before this class gives up and lets the refusal reach the caller exactly as before this lot.
	 */
	static final int LOCK_RETRY_MAX_ATTEMPTS = 5;
	static final long LOCK_RETRY_DELAY_MS = 10000;

	/**
	 * {@code true} for the exact wording the add-on's own {@code InvalidStateException} carries when a scoped
	 * {@code matchRecords} call cannot run because another process still holds the master dataspace (this class'
	 * own earlier entity, in the SAME {@code matching/run} pass, or an unrelated one) - see {@link #launchScoped}'s
	 * own Javadoc for the live sequence this fixes. Pure, case-insensitive, null-safe; unit-tested directly.
	 */
	static boolean isDataspaceLockedMessage(final String pMessage)
	{
		return ForeignWording.isDataspaceLocked(pMessage);
	}

	/**
	 * Pure retry decision (lot R12), unit-tested directly: {@code true} only while the attempt budget remains AND
	 * the refusal is the dataspace-locked shape - any other {@code InvalidStateException} (matching genuinely
	 * paused, policy inactive, a process already running for a DIFFERENT reason) is never retried, whatever
	 * budget remains. {@code pAttemptNumber} is the attempt that JUST failed (1-based).
	 */
	static boolean shouldRetryLock(final String pMessage, final int pAttemptNumber, final int pMaxAttempts)
	{
		return pAttemptNumber < pMaxAttempts && MameMatchingApi.isDataspaceLockedMessage(pMessage);
	}

	/**
	 * Pure poll-continuation decision (lot R12), unit-tested against a fake clock/status sequence: keep polling
	 * only while the process is not yet terminal, the deadline has not passed, and no stop was requested (the
	 * job's own {@code FactoryJobs.Handle#cancelled()}, threaded in by {@link #launchScoped} - {@code pCancelled}
	 * is always {@code false} for every synchronous caller, which passes no handle at all).
	 */
	static boolean shouldKeepPolling(final ProcessStatus pStatus, final long pNowMs, final long pDeadlineMs, final boolean pCancelled)
	{
		return !MameMatchingApi.isTerminal(pStatus) && pNowMs < pDeadlineMs && !pCancelled;
	}

	/**
	 * {@code MatchingOperations.matchRecords(AdaptationTable, Request, String)} (the 3-arg overload -
	 * the 4-arg one ANDs a second XPath string onto the same {@code Request}, per the Java API's own
	 * example; nothing this class needs, since the caller already builds the exact predicate it wants
	 * via {@code master.MatchingRunScope}). Polls up to {@code pMaxWaitMs}, see class Javadoc; {@code
	 * pHandleOrNull} is null for every synchronous caller (see {@link #shouldKeepPolling}'s own Javadoc).
	 * <p>
	 * <b>lot R12 (2026-09-27, docs/MATCHING.md &sect;41):</b> retries a {@code matchRecords} refusal whose own
	 * message is the "dataspace is locked" shape ({@link #isDataspaceLockedMessage}/{@link #shouldRetryLock}) up
	 * to {@link #LOCK_RETRY_MAX_ATTEMPTS} times, {@link #LOCK_RETRY_DELAY_MS} apart, before letting it reach the
	 * caller - the DEFAULT_MAX_WAIT_MS raise ({@code ebx.MatchingConfigurator}'s own Javadoc) fixes the common
	 * case (this class' own earlier entity finishing before the deadline instead of after it), this retry covers
	 * whatever residual race remains (an unrelated process, or a run that still overruns even a generous bound).
	 */
	static RunResult launchScoped(final Session pSession, final AdaptationTable pTable, final String pXpathPredicate, final String pPolicyCodeOrNull, final long pMaxWaitMs)
		throws OperationException
	{
		return MameMatchingApi.launchScoped(pSession, pTable, pXpathPredicate, pPolicyCodeOrNull, pMaxWaitMs, null);
	}

	/** Same as {@link #launchScoped(Session, AdaptationTable, String, String, long)}, checked for a stop between polls (lot R12) - see {@link #shouldKeepPolling}'s own Javadoc. */
	static RunResult launchScoped(final Session pSession, final AdaptationTable pTable, final String pXpathPredicate, final String pPolicyCodeOrNull, final long pMaxWaitMs,
		final FactoryJobs.Handle pHandleOrNull) throws OperationException
	{
		final MatchingOperations matchingOperations = OperationsFactory.getMatchingOperations(pSession);
		final Request request = pTable.createRequest();
		request.setXPathFilter(pXpathPredicate);

		MatchingProcess process = null;
		for (int attempt = 1;; attempt++)
		{
			try
			{
				process = matchingOperations.matchRecords(pTable, request, pPolicyCodeOrNull);
				break;
			}
			catch (final InvalidParameterException ex)
			{
				throw EngineRefusal.codedWithCause("invalid.matchParam", "matchRecords refused (invalid parameter): " + ex.getMessage(pSession.getLocale()), ex);
			}
			catch (final InvalidStateException ex)
			{
				final String message = ex.getMessage(pSession.getLocale());
				if (MameMatchingApi.shouldRetryLock(message, attempt, MameMatchingApi.LOCK_RETRY_MAX_ATTEMPTS))
				{
					try
					{
						Thread.sleep(MameMatchingApi.LOCK_RETRY_DELAY_MS);
					}
					catch (final InterruptedException interrupted)
					{
						Thread.currentThread().interrupt();
						throw EngineRefusal.codedWithCause("conflict.matchingState", "matchRecords refused (invalid state - dataspace is locked) and the retry wait was interrupted: " + message, ex);
					}
					continue;
				}
				throw EngineRefusal.codedWithCause("conflict.matchingState", "matchRecords refused (invalid state - matching paused, policy inactive, or a process already running on this table): " + message, ex);
			}
		}

		final long deadline = System.currentTimeMillis() + Math.max(0, pMaxWaitMs);
		ProcessStatus status = process.getMatchingStatus();
		RunResult lastProgress = null;
		// lot M4 (2026-10-03, defect 1): a job's stop (a person's button OR the watchdog's time limit - both set the handle's flag)
		// or an interrupt of the job thread must reach the ADD-ON: before this lot the loop just returned and the add-on's 8
		// clustering threads kept burning CPU until Tomcat was restarted (project Vol1m, job 161).
		boolean stopAsked = false;
		boolean interrupted = false;
		while (true)
		{
			stopAsked = pHandleOrNull != null && pHandleOrNull.cancelled();
			if (!MameMatchingApi.shouldKeepPolling(status, System.currentTimeMillis(), deadline, stopAsked))
			{
				break;
			}
			// safe to read now (fix item 3, class Javadoc, 2026-09-22): the process has not finished yet.
			lastProgress = MameMatchingApi.buildResult(status, process);
			try
			{
				Thread.sleep(MameMatchingApi.POLL_INTERVAL_MS);
			}
			catch (final InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				interrupted = true;
				break;
			}
			status = process.getMatchingStatus();
		}

		StopOutcome stop = null;
		if (!MameMatchingApi.isTerminal(status) && pHandleOrNull != null && (stopAsked || interrupted))
		{
			// an interrupted thread cannot sleep: the stop is still sent, the wait is then zero-length (requestStop notes it)
			stop = MameMatchingApi.requestStop(process, MameMatchingApi.STOP_CONFIRM_WAIT_MS, MameMatchingApi.POLL_INTERVAL_MS);
			status = process.getMatchingStatus();
		}

		if (!MameMatchingApi.isTerminal(status))
		{
			if (stop != null)
			{
				return MameMatchingApi.buildResult(status, process).withStop(stop);
			}
			// the deadline passed, or a stop was requested, while the process was still running - one more read is
			// still safe (the process has not finished), and becomes the returned result directly.
			return MameMatchingApi.buildResult(status, process);
		}
		// terminal (lot R12, fix item 3): never call getDetail()/getCompletionRate()/getDuration() again - the
		// add-on's own MatchingOperationProcess.getCurrentPhase() can answer null once the process has genuinely
		// finished, and getDetail() throws a bare NullPointerException from inside it (class Javadoc, 2026-09-22).
		// Reuse whatever was captured during the LAST running iteration instead of touching the process again.
		final RunResult finalResult = MameMatchingApi.finalResult(status, lastProgress);
		return stop == null ? finalResult : finalResult.withStop(stop);
	}

	/**
	 * Builds a {@link RunResult} by reading the guarded report-only accessors NOW - only ever safe to call while
	 * {@code pStatus} is NOT terminal (the process has not finished, so {@code getDetail()}/{@code
	 * getCompletionRate()}/{@code getDuration()} cannot yet hit the "current phase is null" defect the class
	 * Javadoc describes). Split out from {@link #launchScoped} so the guarded-read behaviour is unit-testable
	 * against a fake {@link MatchingProcess} with no {@code Session}/{@code AdaptationTable}/live add-on involved
	 * at all. {@code pStatus} is passed in rather than re-read here: {@link MatchingProcess#getMatchingStatus()}
	 * is the one call this class trusts unconditionally (see class Javadoc).
	 */
	static RunResult buildResult(final ProcessStatus pStatus, final MatchingProcess pProcess)
	{
		final List<String> reportNotes = new ArrayList<String>();
		final String detail = MameMatchingApi.safeDetail(pProcess, reportNotes);
		final double completionRate = MameMatchingApi.safeCompletionRate(pProcess, reportNotes);
		final long durationMs = MameMatchingApi.safeDurationMs(pProcess, reportNotes);
		return new RunResult(pStatus.name(), completionRate, detail, durationMs, reportNotes);
	}

	/**
	 * The result {@link #launchScoped} returns once {@code pStatus} is terminal (lot R12, fix item 3, class
	 * Javadoc, 2026-09-22): NEVER reads {@link MatchingProcess} again (there is no {@code MatchingProcess}
	 * parameter at all - the signature itself is the guarantee) - reuses whichever {@link RunResult} was last
	 * captured, while the process was still RUNNING, if any. Pure, unit-tested directly.
	 *
	 * @param pLastProgressOrNull the last {@link #buildResult} snapshot taken while the process was still
	 *            non-terminal, or {@code null} when the process was already terminal at this method's very
	 *            first {@code getMatchingStatus()} read - no progress was ever safely observable, so {@code
	 *            detail}/{@code completionRate}/{@code durationMs} default to "unknown", never a guess.
	 */
	static RunResult finalResult(final ProcessStatus pStatus, final RunResult pLastProgressOrNull)
	{
		if (pLastProgressOrNull == null)
		{
			return new RunResult(pStatus.name(), -1, null, -1, new ArrayList<String>());
		}
		if (pStatus == ProcessStatus.DONE)
		{
			// a DONE process is complete whatever its last observed snapshot said (project ret, 2026-09-28: the
			// step detail kept "completionRate 50, detail GROUPING" on a finished run and read like a half-done
			// one) - the rate is 100 by definition, and the phase is named as what it is: the last one OBSERVED
			final String detail = pLastProgressOrNull.detail == null ? "FINISHED" : "FINISHED (last observed phase: " + pLastProgressOrNull.detail + ")";
			return new RunResult(pStatus.name(), 100.0, detail, pLastProgressOrNull.durationMs, pLastProgressOrNull.reportNotes);
		}
		return new RunResult(pStatus.name(), pLastProgressOrNull.completionRate, pLastProgressOrNull.detail, pLastProgressOrNull.durationMs, pLastProgressOrNull.reportNotes);
	}

	private static boolean isTerminal(final ProcessStatus pStatus)
	{
		return pStatus == ProcessStatus.DONE || pStatus == ProcessStatus.FAILED || pStatus == ProcessStatus.CANCELLED;
	}

	/** See class Javadoc, 2026-09-22: {@code getDetail()} can throw once the process has finished - never lets that fail the whole run. */
	private static String safeDetail(final MatchingProcess pProcess, final List<String> pReportNotes)
	{
		try
		{
			return pProcess.getDetail();
		}
		catch (final RuntimeException ex)
		{
			pReportNotes.add(MameMatchingApi.reportNote("getDetail", ex));
			return null;
		}
	}

	/** See class Javadoc, 2026-09-22: same guard as {@link #safeDetail}, for {@code getCompletionRate()}. */
	private static double safeCompletionRate(final MatchingProcess pProcess, final List<String> pReportNotes)
	{
		try
		{
			return pProcess.getCompletionRate();
		}
		catch (final RuntimeException ex)
		{
			pReportNotes.add(MameMatchingApi.reportNote("getCompletionRate", ex));
			return -1;
		}
	}

	/** See class Javadoc, 2026-09-22: same guard as {@link #safeDetail}, for {@code getDuration()}. */
	private static long safeDurationMs(final MatchingProcess pProcess, final List<String> pReportNotes)
	{
		try
		{
			final java.time.Duration duration = pProcess.getDuration();
			return duration == null ? -1 : duration.toMillis();
		}
		catch (final RuntimeException ex)
		{
			pReportNotes.add(MameMatchingApi.reportNote("getDuration", ex));
			return -1;
		}
	}

	private static String reportNote(final String pMethodName, final RuntimeException pEx)
	{
		return pMethodName + "(): " + pEx.getClass().getName() + (pEx.getMessage() == null ? "" : ": " + pEx.getMessage());
	}

	// ----------------------------------------------------------------------------------------------------- simulate

	static final class SimulationMatch
	{
		final String targetRecordPrimaryKey;
		final String pivotRecordPrimaryKey;
		/** {@code MatchingResultType.name()} - MATCHED/SUSPECTED/NOT_MATCHED - or null if the add-on reports none. */
		final String result;
		/**
		 * {@code MatchingEngineResult.getTargetRecordAdaptation()}/{@code getPivotRecordAdaptation()} - plain
		 * EBX types (not add-on types), so exposing them here does not widen this class' own "only this class
		 * touches add-on types" rule (class Javadoc). Added for the review-match-plan-simulation lot
		 * (2026-09-22): the caller ({@code ebx.MatchPlanSimulationRunner}) reads whichever fields the plan
		 * compares straight off these, instead of a second table lookup by primary key - the same records the
		 * add-on itself just compared, guaranteed in sync with the verdict above. <b>Assumed, not verified
		 * live</b>: that an {@code Adaptation} handle returned from inside the (now-closed) {@code
		 * SimulationResult}'s own iteration stays valid for ordinary field reads after {@link
		 * SimulationResult#close()} - reasoned from {@code Adaptation} being a plain data-home handle, not a
		 * cursor tied to the add-on's own result set, the same assumption every other {@code Adaptation} this
		 * codebase reads from a {@code RequestResult} already relies on once the request is closed.
		 */
		final Adaptation targetRecord;
		final Adaptation pivotRecord;

		SimulationMatch(final String pTargetRecordPrimaryKey, final String pPivotRecordPrimaryKey, final String pResult, final Adaptation pTargetRecord,
			final Adaptation pPivotRecord)
		{
			this.targetRecordPrimaryKey = pTargetRecordPrimaryKey;
			this.pivotRecordPrimaryKey = pPivotRecordPrimaryKey;
			this.result = pResult;
			this.targetRecord = pTargetRecord;
			this.pivotRecord = pPivotRecord;
		}
	}

	static final class SimulationOutcome
	{
		final List<SimulationMatch> matches;

		SimulationOutcome(final List<SimulationMatch> pMatches)
		{
			this.matches = pMatches;
		}
	}

	/**
	 * {@code MatchingOperations.simulateMatch(Adaptation, String)} - nothing persisted, per the add-on's own
	 * Javadoc; the {@code SimulationResult} is closed here, never leaked to the caller.
	 * <p>
	 * <b>2026-09-22 23:30 - the add-on's own canopy/Lucene candidate search can throw a bare, uncaught
	 * {@code RuntimeException}, translated here instead of reaching the caller raw</b> (measured live twice,
	 * 2026-09-22 15:11 and 23:26, both right after a Tomcat restart: {@code IllegalArgumentException: field
	 * [Linked_Field_State] not found} from {@code CnyFilterBuilder.addTableParameters} - see
	 * {@code ebx.MatchingActivationGuard}'s own class Javadoc for the full root cause and the self-heal that
	 * now runs BEFORE this method is even reached, in both callers). This catch is defense in depth for
	 * whatever slips past that self-heal (a race between its status read and the add-on's actual index state,
	 * or any other failure from the same {@code com.orchestranetworks.cce.*} layer) - never load-bearing on
	 * its own, but the difference between a message a reviewer can act on and EBX's generic "A fatal error
	 * occurred" page.
	 */
	static SimulationOutcome simulate(final Session pSession, final Adaptation pRecord, final String pPolicyCodeOrNull) throws OperationException
	{
		final MatchingOperations matchingOperations = OperationsFactory.getMatchingOperations(pSession);
		final List<SimulationMatch> matches = new ArrayList<SimulationMatch>();
		try (SimulationResult simulation = matchingOperations.simulateMatch(pRecord, pPolicyCodeOrNull))
		{
			for (final MatchingEngineResult result : simulation)
			{
				matches.add(new SimulationMatch(result.getTargetRecordPrimaryKey(), result.getPivotRecordPrimaryKey(),
					result.getResult() == null ? null : result.getResult().name(), result.getTargetRecordAdaptation(), result.getPivotRecordAdaptation()));
			}
		}
		catch (final InvalidParameterException ex)
		{
			throw EngineRefusal.codedWithCause("invalid.matchParam", "simulateMatch refused (invalid parameter): " + ex.getMessage(pSession.getLocale()), ex);
		}
		catch (final InvalidStateException ex)
		{
			throw EngineRefusal.codedWithCause("conflict.matchingState", "simulateMatch refused (invalid state - matching paused or policy inactive): " + ex.getMessage(pSession.getLocale()), ex);
		}
		catch (final RuntimeException ex)
		{
			// InvalidParameterException/InvalidStateException (both caught above, both from
			// com.orchestranetworks.addon.mame.common) never reach here - only a DIFFERENT add-on failure does,
			// most notably the canopy/Lucene one this catch exists for (class Javadoc, 2026-09-22 23:30).
			if (MatchingActivationGuard.isAddonIndexFailure(ex))
			{
				throw MatchingActivationGuard.translateAddonIndexFailure(MameMatchingApi.tablePathOrUnknown(pRecord), ex);
			}
			throw ex;
		}
		return new SimulationOutcome(matches);
	}

	/** Best-effort, for the translated message only - never lets a failure to describe the table hide the real exception (see {@link #simulate}'s own catch). */
	private static String tablePathOrUnknown(final Adaptation pRecord)
	{
		try
		{
			return pRecord.getContainerTable().getTablePath().format();
		}
		catch (final RuntimeException ex)
		{
			return "<unknown table>";
		}
	}

	// ---------------------------------------------------------------------------------------------- decision tree

	/** One {@code DecisionTreeExecutionResult$ScoreField} entry (`javap`, 2026-09-22, see class Javadoc) - the ONE place this codebase reads a real, engine-computed per-field similarity score, as opposed to a factory-side estimate. */
	static final class FieldScore
	{
		final String field;
		/**
		 * {@code ScoreField.getScore()} - raw, unconverted. <b>Not verified live</b> whether this is on a
		 * 0-100 scale (matching a tier's own configured {@code minimumScore}, always an integer 0-100 in
		 * every plan {@code master.MatchingPolicyTranslator} builds) or already 0-1: nothing in the Java API's
		 * on-disk Javadoc for {@code ScoreField} states the unit, and no live run was available to check
		 * (task constraint: no EBX access this session). {@code review.MatchSimulationBlock} therefore never
		 * divides or multiplies this value by 100 anywhere - it is always compared and displayed against the
		 * tier's own {@code minimumScore} in whatever units both happen to share, rather than assuming a
		 * conversion that could silently be wrong in either direction.
		 */
		final double score;
		final String pivotRawValue;
		final String candidateRawValue;

		FieldScore(final String pField, final double pScore, final String pPivotRawValue, final String pCandidateRawValue)
		{
			this.field = pField;
			this.score = pScore;
			this.pivotRawValue = pPivotRawValue;
			this.candidateRawValue = pCandidateRawValue;
		}
	}

	/**
	 * {@code MatchingOperations.executeDecisionTree(Adaptation, Adaptation, String)} run for exactly the pair
	 * given (`javap` on {@code ebx-addons.jar}, 2026-09-22: {@code
	 * com.orchestranetworks.addon.mame.service.decisiontree.DecisionTreeExecutionResult}, methods {@code
	 * getMatchingResult()}/{@code getDecisionNodes()}/{@code getNodeScores()}) - the ONLY method in this add-on's
	 * public Java API found (this session) to expose a per-field similarity score and the decision nodes a
	 * comparison actually walked, as opposed to {@link #simulate}'s own {@code MatchingEngineResult} (verdict
	 * and the two primary keys only - `javap` on {@code MatchingEngineResult}, no score, no node). {@code
	 * getNodeScores()} returns {@code Map<Integer, List<ScoreField>>}; this method assumes - REASONED, NOT
	 * OBSERVED LIVE - that the integer key is the zero-based position of the corresponding node in {@code
	 * getDecisionNodes()}'s own iteration order (the only correlation the two methods could plausibly share,
	 * given neither {@code DecisionNode} nor {@code ScoreField} itself carries a shared id field - `javap`
	 * confirms this: {@code DecisionNode} has no {@code getIndex()}/{@code getId()}, and {@code ScoreField} has
	 * no node reference at all besides sitting under that map's own key).
	 * <p>
	 * <b>Best effort, never load-bearing for a verdict</b>: {@link #simulate}'s own {@code MatchingEngineResult}
	 * is the trusted source of the verdict for a given pair (class Javadoc, "read-only and zero-risk"); this
	 * method only adds descriptive per-field detail. Any {@code RuntimeException} degrades to a {@link
	 * TreeEvaluation} with {@link TreeEvaluation#matchingResult} null, never propagated: the caller ({@code
	 * ebx.MatchPlanSimulationRunner}) treats missing detail as "not verified for this pair", exactly the
	 * discipline {@code ebx.CrossSystemMeasures}'s own live-SQL measurements already apply to a failed
	 * measurement.
	 * <p>
	 * <b>2026-09-22 23:38, diagnostic-visibility lot - never silent any more.</b> A live "Simulate on 20 pivots"
	 * click (Company, preview policy {@code Company__preview}, one AVERAGE &ge; 80 node on {@code name}
	 * {@code fuzzyFullText} only) showed "score not exposed by the engine" on EVERY row with no trace anywhere
	 * in {@code kernel.log} - because the pre-lot catch block below discarded the exception (or the empty
	 * {@code getNodeScores()} result) without a word. {@link TreeEvaluation} now always carries a {@link
	 * TreeEvaluation#diagnostic} - the exception's class/message/top 5 frames when this method's own attempt
	 * threw ({@link TreeEvaluation#failed} true), or a descriptive "{@code getNodeScores() answered N node(s),
	 * field names: [...]; nodeNames: [...]}" string when it succeeded (whether {@link TreeEvaluation#fieldScores}
	 * ended up empty or not) - so the caller can always say WHY a score is missing instead of just that it is.
	 * This method itself never logs (it is called up to {@code MatchPlanSimulationRunner#MAX_DETAIL_PAIRS} times
	 * per click - logging here would spam {@code kernel.log} once per pair); {@code
	 * ebx.MatchPlanSimulationRunner#simulate} aggregates every diagnostic produced during ONE click and logs
	 * exactly one {@code kernel.log} warning for it, see that method's own Javadoc.
	 * <p>
	 * <b>The success-path logic is split into {@link #buildTreeEvaluation}, callable directly against a hand-built
	 * fake {@code DecisionTreeExecutionResult}</b> - the SAME testability split {@link #buildResult} already
	 * applies to {@link #launchScoped} (class Javadoc): {@code OperationsFactory.getMatchingOperations(Session)}
	 * only initializes against a live server, so this method's own try block can never be exercised end to end
	 * outside one, but the scoring/diagnostic logic that matters can be.
	 */
	static TreeEvaluation evaluatePair(final Session pSession, final Adaptation pPivot, final Adaptation pCandidate, final String pPolicyCodeOrNull)
	{
		try
		{
			final MatchingOperations matchingOperations = OperationsFactory.getMatchingOperations(pSession);
			final DecisionTreeExecutionResult execution = matchingOperations.executeDecisionTree(pPivot, pCandidate, pPolicyCodeOrNull);
			return MameMatchingApi.buildTreeEvaluation(execution);
		}
		catch (final RuntimeException ex)
		{
			return new TreeEvaluation(null, new ArrayList<String>(), null, new ArrayList<FieldScore>(), MameMatchingApi.exceptionDiagnostic(ex), true);
		}
	}

	/**
	 * The success-path body of {@link #evaluatePair}, split out for direct unit testing (class Javadoc,
	 * 2026-09-22 diagnostic-visibility lot) - {@code pExecution} is a hand-built fake {@code
	 * DecisionTreeExecutionResult} in {@code MameMatchingApiTest} (an interface, `javap`-confirmed, so no
	 * mocking library is needed - same discipline as {@code MameMatchingApiTest.FakeProcess}).
	 * <p>
	 * <b>One-node-tree defensive read (item 2, 2026-09-22)</b>: when the tree {@code pExecution} evaluated has
	 * exactly ONE decision node AND {@code getNodeScores()} answers exactly one map entry, that entry is used
	 * regardless of what its own {@code Integer} key actually is - with only one node and one entry there is
	 * exactly one thing the entry could mean, so the (REASONED, NOT OBSERVED LIVE - see this class' own class
	 * Javadoc) "key equals node index" assumption is never even consulted for this common single-node-preview-
	 * policy shape (the live incident this lot answers: {@code Company__preview}, one AVERAGE node). For two or
	 * more nodes, the index-based lookup remains the only available correlation and is used as before.
	 */
	static TreeEvaluation buildTreeEvaluation(final DecisionTreeExecutionResult pExecution)
	{
		if (pExecution == null)
		{
			return TreeEvaluation.UNAVAILABLE;
		}
		final List<DecisionNode> nodes = new ArrayList<DecisionNode>();
		for (final DecisionNode node : pExecution.getDecisionNodes())
		{
			nodes.add(node);
		}
		final List<String> nodeNames = new ArrayList<String>();
		String lastNodeName = null;
		for (final DecisionNode node : nodes)
		{
			final String name = node.getName();
			nodeNames.add(name);
			if (Boolean.TRUE.equals(node.isLastNode()))
			{
				lastNodeName = name;
			}
		}
		final Map<Integer, List<DecisionTreeExecutionResult.ScoreField>> nodeScores = pExecution.getNodeScores();
		final List<FieldScore> fieldScores = new ArrayList<FieldScore>();
		if (nodeScores != null)
		{
			if (nodes.size() == 1 && nodeScores.size() == 1)
			{
				MameMatchingApi.appendFieldScores(fieldScores, nodeScores.values().iterator().next());
			}
			else
			{
				for (int index = 0; index < nodes.size(); index++)
				{
					final List<DecisionTreeExecutionResult.ScoreField> scores = nodeScores.get(Integer.valueOf(index));
					if (scores != null)
					{
						MameMatchingApi.appendFieldScores(fieldScores, scores);
					}
				}
			}
		}
		final String matchingResult = pExecution.getMatchingResult() == null ? null : pExecution.getMatchingResult().name();
		final String diagnostic = MameMatchingApi.successDiagnostic(nodeScores, fieldScores, nodeNames);
		return new TreeEvaluation(matchingResult, nodeNames, lastNodeName, fieldScores, diagnostic, false);
	}

	private static void appendFieldScores(final List<FieldScore> pOut, final List<DecisionTreeExecutionResult.ScoreField> pScores)
	{
		for (final DecisionTreeExecutionResult.ScoreField score : pScores)
		{
			pOut.add(new FieldScore(score.getField(), score.getScore(), MameMatchingApi.asString(score.getPivotRecordRawValue()),
				MameMatchingApi.asString(score.getSuspectRecordRawValue())));
		}
	}

	/** "{@code getNodeScores() answered N node(s), field names: [...]; nodeNames: [...]}" - see {@link #evaluatePair}'s own Javadoc, "never silent any more". Always built on a successful call, whether {@code pFieldScores} ended up empty or not - the caller ({@code ebx.MatchPlanSimulationRunner}) decides whether this is worth surfacing. */
	private static String successDiagnostic(final Map<Integer, List<DecisionTreeExecutionResult.ScoreField>> pNodeScores, final List<FieldScore> pFieldScores,
		final List<String> pNodeNames)
	{
		final List<String> fieldNames = new ArrayList<String>();
		for (final FieldScore fieldScore : pFieldScores)
		{
			fieldNames.add(fieldScore.field);
		}
		return "getNodeScores() answered " + (pNodeScores == null ? 0 : pNodeScores.size()) + " node(s), field names: " + fieldNames + "; nodeNames: " + pNodeNames;
	}

	/** "{@code java.lang.IllegalArgumentException: field [Linked_Field_State] not found at a.B.c(D.java:12); ...}" - class, message (when any), and up to 5 stack frames, so a kernel-log reader can act on it without needing a live repro. */
	private static String exceptionDiagnostic(final RuntimeException pEx)
	{
		return pEx.getClass().getName() + (pEx.getMessage() == null ? "" : ": " + pEx.getMessage()) + " at " + MameMatchingApi.topFrames(pEx, 5);
	}

	private static String topFrames(final Throwable pEx, final int pMax)
	{
		final StackTraceElement[] trace = pEx.getStackTrace();
		if (trace == null || trace.length == 0)
		{
			return "<no stack trace>";
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < trace.length && i < pMax; i++)
		{
			if (i > 0)
			{
				out.append("; ");
			}
			out.append(trace[i]);
		}
		return out.toString();
	}

	private static String asString(final Object pValue)
	{
		return pValue == null ? null : String.valueOf(pValue);
	}

	static final class TreeEvaluation
	{
		/** Never attempted - {@code fieldScores} empty, {@code nodeNames} empty, {@code matchingResult}/{@code lastNodeName}/{@code diagnostic} null, {@code failed} false: {@code review.MatchSimulationBlock} reads an empty {@code fieldScores} as "not verified for this pair", never as "the engine measured zero fields". Distinct from a call that WAS attempted and failed (a non-null {@link #diagnostic}, {@link #failed} true) - see {@link #evaluatePair}'s own Javadoc, 2026-09-22 diagnostic-visibility lot. */
		static final TreeEvaluation UNAVAILABLE = new TreeEvaluation(null, new ArrayList<String>(), null, new ArrayList<FieldScore>(), null, false);

		/** {@code DecisionTreeExecutionResult.getMatchingResult().name()} - the SAME verdict category {@link #simulate} itself already reports for this pair (kept here too only as a cross-check; never substituted for {@link #simulate}'s own verdict). */
		final String matchingResult;
		/** {@code DecisionNode.getName()} for every node {@code getDecisionNodes()} returned, in iteration order - the SAME strings {@code master.MatchingPolicyTranslator#tierLabel} writes when the policy is generated, so a name here can be matched back to the plan's own tier. */
		final List<String> nodeNames;
		/** The name of whichever node had {@code isLastNode() == true}, or null if none did (or detail is unavailable). */
		final String lastNodeName;
		/** Flattened across every node's own {@code ScoreField} list, in node-then-field order. Empty means "not verified for this pair" (see {@link #UNAVAILABLE}), never "the engine found nothing to score". */
		final List<FieldScore> fieldScores;
		/**
		 * 2026-09-22 diagnostic-visibility lot: non-null whenever this evaluation has something worth reporting
		 * about WHY {@link #fieldScores} looks the way it does - the exception's class/message/top-5-frames when
		 * {@link #failed} is true, or a "{@code getNodeScores() answered N node(s), ...}" description on a
		 * successful call (see {@link #evaluatePair}'s own Javadoc). Null only for {@link #UNAVAILABLE} (never
		 * attempted at all - the {@code MAX_DETAIL_PAIRS} cap, or a probe pair {@code evaluatePair} was never
		 * called for). {@code ebx.MatchPlanSimulationRunner} aggregates this across a whole "Simulate" click.
		 */
		final String diagnostic;
		/** True only when this method's own call to the add-on threw and was caught - see {@link #diagnostic}. Never true for {@link #UNAVAILABLE} (which was simply never attempted) nor for a successful call with an empty {@link #fieldScores}. */
		final boolean failed;

		TreeEvaluation(final String pMatchingResult, final List<String> pNodeNames, final String pLastNodeName, final List<FieldScore> pFieldScores,
			final String pDiagnostic, final boolean pFailed)
		{
			this.matchingResult = pMatchingResult;
			this.nodeNames = pNodeNames;
			this.lastNodeName = pLastNodeName;
			this.fieldScores = pFieldScores;
			this.diagnostic = pDiagnostic;
			this.failed = pFailed;
		}
	}
}
