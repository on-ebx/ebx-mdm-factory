package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.RunTexts;
import com.on.ebx.factory.matching.MatchingRunFailure;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.wizard.PartialFailures;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot C3 (2026-09-24, jobs-matching): the wizard's "Run the matching" button ({@code MATCHING_RUN}) as a job of
 * {@link FactoryJobQueue} (resource {@link FactoryJobs#MATCHING}) - same shape as {@link MatchingConfigureRun},
 * see that class' own Javadoc for why this serializes safely with {@link MatchingRunTaskBean} and never
 * re-enters the resource claim it already holds. The real work stays in {@link MatchingConfigurator#run(Session,
 * Adaptation, String, boolean, long, FactoryJobs.Handle)}.
 * <p>
 * <b>Scope is captured at enqueue time</b> ({@code scopeAll}, the wizard's incremental/all button choice - see
 * {@code ProjectWizardUserService}'s own posted {@code matchScope} field) since the job runs with the queue's
 * system session and params, never re-reading anything from the clicking browser.
 * <p>
 * <b>Long-running, checked between entities.</b> A real matching run over a large table can take minutes per
 * entity ({@code MameMatchingApi#launchScoped} already polls, bounded, inside ONE entity); this class adds no
 * new cancellation point inside a single entity's own launch/poll (none exists to add - see {@code
 * MatchingConfigurator#run}'s own Javadoc on {@code pMaxWaitMs}), only between entities of the same run, exactly
 * like {@link MatchingConfigureRun}.
 */
public final class MatchingRunRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "MATCHING_RUN";

	private MatchingRunRun()
	{
	}

	/** Queues the run (resource MATCHING) and returns at once - never refused, same as {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pScopeAll) throws OperationException
	{
		final Adaptation project = MatchingRunRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = MatchingRunRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("scopeAll", pScopeAll);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), MatchingRunRun.STEP, params);
		final ObjectNode out = MatchingRunRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final boolean scopeAll = pParams.path("scopeAll").asBoolean(false);
			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, MatchingRunRun.STEP, "");
			FactoryJobs.attach(pHandle, stepId);
			// lot MT (2026-10-04): set inside the try, thrown AFTER it - the catch below ends the parent step as "failed" and must not
			// overwrite the sentence that names the failed entities
			String failureMessage = null;
			// UX lot D: the run did something for some objects and not for others (a failed or refused object next to objects that ran): DONE_WITH_FAILURES
			FactoryJobQueue.PartialOutcome partial = null;
			try
			{
				final ObjectNode result = MatchingConfigurator.run(pJobSession, pFactory, projectCode, scopeAll, MatchingConfigurator.DEFAULT_MAX_WAIT_MS, pHandle);
				final List<String> failed = new ArrayList<String>();
				for (final JsonNode entry : result.path("failed"))
				{
					failed.add(entry.asText());
				}
				// run texts i18n (2026-10-18): the English of the job's message is unchanged (message()/withFollows() build it); what is stored carries the codes of its sentences
				final String message = MatchingRunRun.coded(pHandle.cancelled(), pHandle.stopReason(), result.path("ran").size(), result.path("notApplied").size(),
					result.path("refused").size(), result.path("stoppedBefore").size(), result.path("stopUnconfirmed").size(), failed, result.path("followsParent").size());
				// a run the person stopped stays a stopped job (its sentence names the failures); otherwise a failed entity fails the job
				final boolean failedAny = !failed.isEmpty() && !pHandle.cancelled();
				PipelineSteps.end(pJobSession, pFactory, stepId, !pHandle.cancelled() && failed.isEmpty(), EngineMessageCodec.decode(message).raw, result.toString());
				final List<String> refused = new ArrayList<String>();
				for (final JsonNode entry : result.path("refused"))
				{
					refused.add(entry.asText());
				}
				final List<String> notDone = new ArrayList<String>(failed);
				notDone.addAll(refused);
				final int ran = result.path("ran").size();
				if (!pHandle.cancelled() && !notDone.isEmpty() && (ran > 0 || failed.isEmpty()))
				{
					// something ran, or nothing failed but some objects were refused: not a success, not a total failure
					partial = new FactoryJobQueue.PartialOutcome(message, PartialFailures.fromEntries(notDone, ran + notDone.size()));
				}
				else if (failedAny)
				{
					failureMessage = message;
				}
				else
				{
					return message;
				}
			}
			catch (final Exception ex) // deliberately broad - the parent step must always be ended
			{
				final String message = "Matching run failed: " + ex.getMessage();
				try
				{
					PipelineSteps.end(pJobSession, pFactory, stepId, false, message, "");
				}
				catch (final Exception logged)
				{
					com.orchestranetworks.service.LoggingCategory.getKernel()
						.error("[mdm-factory] could not even record the failure of matching run for project " + projectCode + ": " + logged, logged);
				}
				throw ex;
			}
			if (partial != null)
			{
				throw partial;
			}
			// every object that was run failed: the job ends FAILED with the sentence ("1 of 1 entities failed: Personne - <reason>"), a routine outcome: no stack trace in the log
			throw new FactoryJobQueue.ExpectedFailure(failureMessage);
		}
	};

	/**
	 * The job's message as stored: the same English as {@link #withFollows}({@link #message}(...)), followed by the code of its sentences so the wizard words them in the viewer's
	 * language. A run that failed or was stopped keeps the failure texts (free text) as joined English after its coded first sentence.
	 */
	static String coded(final boolean pCancelled, final String pStopReason, final int pRan, final int pNotApplied, final int pRefused, final int pStoppedBefore,
		final int pStopUnconfirmed, final List<String> pFailed, final int pFollowsParent)
	{
		final String first;
		if (!pCancelled && pFailed != null && !pFailed.isEmpty())
		{
			first = MatchingRunFailure.jobMessage(pFailed, pRan, pNotApplied, pRefused);
		}
		else if (pCancelled)
		{
			// the stop sentence is coded; what follows it (failed entities, the add-on's warning) is the engine's own free text
			final String full = MatchingRunRun.message(pCancelled, pStopReason, pRan, pNotApplied, pRefused, pStoppedBefore, pStopUnconfirmed, pFailed);
			final KeyTexts.Text head = RunTexts.matchingStopped(pStopReason, pRan, pStoppedBefore);
			first = full.startsWith(head.english) && full.length() > head.english.length() ? RunTexts.joined(head.stored(), full.substring(head.english.length()).trim()).stored()
				: head.stored();
		}
		else
		{
			first = RunTexts.matchingResult(pRan, pNotApplied, pRefused).stored();
		}
		return pFollowsParent <= 0 ? first : RunTexts.joined(first, RunTexts.matchingFollows(pFollowsParent).stored()).stored();
	}

	/** Lot M2 (2026-10-17): the child objects the run did not touch because they have no plan of their own are named in the sentence, never counted as "not applied". Pure. */
	static String withFollows(final String pBase, final int pFollowsParent)
	{
		if (pFollowsParent <= 0)
		{
			return pBase;
		}
		return pBase + " " + (pFollowsParent == 1 ? "1 child object has no plan of its own: its records follow" : pFollowsParent + " child objects have no plan of their own: their records follow")
			+ " the matching of the parent.";
	}

	/** Same shape as {@link MatchingConfigureRun#status}. Never throws for "no run yet": {@code exists=false}. */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = MatchingRunRun.MAPPER.createObjectNode();
		final Adaptation project = MatchingRunRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			out.put("exists", false);
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation job = FactoryJobQueue.latest(pFactory, projectId, MatchingRunRun.STEP);
		final boolean queued = job != null && FactoryJobQueue.QUEUED.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		final Adaptation row = MatchingRunRun.latestRow(pFactory, projectId);
		if (row == null && !queued)
		{
			out.put("exists", false);
			return out;
		}
		final String status = queued ? FactoryJobQueue.QUEUED : row.getString(FactoryPaths.PipelineStep.STATUS);
		final boolean running = queued || "RUNNING".equals(status) && FactoryJobs.isLive(projectId, MatchingRunRun.STEP);
		out.put("exists", true);
		out.put("status", status);
		out.put("running", running);
		out.put("queued", queued);
		if (job != null)
		{
			out.put("jobId", job.getOccurrencePrimaryKey().format());
		}
		if (queued)
		{
			out.put("message", "Queued: waiting for the matching zone of this project to be free.");
			return out;
		}
		final String message = row.getString(FactoryPaths.PipelineStep.MESSAGE);
		if (message != null)
		{
			out.put("message", message);
		}
		return out;
	}

	/**
	 * Pure, package-visible for {@code MatchingRunRunTest}: the job's result sentence, built from {@link
	 * MatchingConfigurator#run(Session, Adaptation, String, boolean, long, FactoryJobs.Handle)}'s own output
	 * counts - see {@link MatchingConfigureRun#message}'s own Javadoc for the same convention.
	 */
	static String message(final boolean pCancelled, final String pStopReason, final int pRan, final int pNotApplied, final int pRefused, final int pStoppedBefore)
	{
		return MatchingRunRun.message(pCancelled, pStopReason, pRan, pNotApplied, pRefused, pStoppedBefore, 0);
	}

	/**
	 * Lot M4 (2026-10-03, defect 1): {@code pStopUnconfirmed} is the number of entities whose add-on process did NOT confirm the
	 * stop ({@code MatchingConfigurator.run}'s {@code stopUnconfirmed}) - the sentence then says so instead of letting "stopped"
	 * pass for "the add-on stopped": its threads may still be running.
	 */
	static String message(final boolean pCancelled, final String pStopReason, final int pRan, final int pNotApplied, final int pRefused, final int pStoppedBefore,
		final int pStopUnconfirmed)
	{
		return MatchingRunRun.message(pCancelled, pStopReason, pRan, pNotApplied, pRefused, pStoppedBefore, pStopUnconfirmed, Collections.<String>emptyList());
	}

	/**
	 * Lot MT (2026-10-04): {@code pFailed} is one {@code "Entity - reason"} entry per entity whose add-on process ended in failure
	 * ({@code MatchingConfigurator.run}'s {@code failed}). Not empty, and the run was not stopped: the sentence is
	 * {@link MatchingRunFailure#jobMessage} ("1 of 2 entities failed: Personne - ...") and the job ends FAILED. A stopped run keeps the
	 * "stopped" sentence (a stop is the person's own choice), naming the failures after it.
	 */
	static String message(final boolean pCancelled, final String pStopReason, final int pRan, final int pNotApplied, final int pRefused, final int pStoppedBefore,
		final int pStopUnconfirmed, final List<String> pFailed)
	{
		if (!pCancelled && pFailed != null && !pFailed.isEmpty())
		{
			return MatchingRunFailure.jobMessage(pFailed, pRan, pNotApplied, pRefused);
		}
		if (pCancelled)
		{
			final String base = "Matching run stopped (" + pStopReason + "): " + pRan + " entit" + (pRan == 1 ? "y" : "ies") + " run before the stop, " + pStoppedBefore
				+ " not started.";
			final String failedSentence = pFailed == null || pFailed.isEmpty() ? "" : " " + pFailed.size() + " entit" + (pFailed.size() == 1 ? "y" : "ies") + " had already failed: "
				+ String.join("; ", pFailed) + ".";
			return pStopUnconfirmed <= 0 ? base + failedSentence
				: base + failedSentence + " Warning: the add-on's process is still running on " + pStopUnconfirmed + " entit" + (pStopUnconfirmed == 1 ? "y" : "ies")
					+ " (it did not confirm the stop): it cannot be stopped from here, see the entity step.";
		}
		return pRan + " entit" + (pRan == 1 ? "y" : "ies") + " run, " + pNotApplied + " not applied, " + pRefused + " refused.";
	}

	private static Adaptation latestRow(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(MatchingRunRun.STEP) + " and " + PipelineSteps.scopeEquals("./scope", ""));
		try
		{
			Adaptation latest = null;
			Date latestStartedAt = null;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Date startedAt = (Date) row.get(FactoryPaths.PipelineStep.STARTED_AT);
				if (latest == null || (startedAt != null && (latestStartedAt == null || startedAt.after(latestStartedAt))))
				{
					latest = row;
					latestStartedAt = startedAt;
				}
			}
			return latest;
		}
		finally
		{
			rows.close();
		}
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}
}
