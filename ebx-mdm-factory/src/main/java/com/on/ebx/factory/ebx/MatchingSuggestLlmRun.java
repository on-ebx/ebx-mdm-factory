package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.wizard.MatchingPlanCoverage;
import com.on.ebx.factory.wizard.WizardMessages;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Job-engine lot C3 (2026-09-24): the "Suggest with the language model" button of the matching step
 * ({@code MATCHING_SUGGEST_LLM}) - moved onto {@link FactoryJobQueue} (kind {@link #STEP}, resource {@link
 * FactoryJobs#DESIGN}, shared with {@code StagingProposalRun}/{@link StagingSuggestLlmRun}/{@link
 * QualitySuggestLlmRun}/{@link CapabilitySuggestLlmRun} so none of them ever run over each other for one
 * project). Same shape as {@link StagingSuggestLlmRun} - see that class's own Javadoc for the attach/role
 * reasoning. Unlike the other three, {@link MatchPlanLlmSuggester#suggest} DOES read {@code
 * pSession.getLocale()} directly to build its own run summary (line-level, not a role/gating check) - fixed
 * by the new {@code Locale}-taking overload added to that class this same lot (mirrors {@code
 * AnomalyLlmSuggester}'s own locale overload from the {@code StagingProposalRun} migration), so the captured
 * launcher locale reaches it instead of the job's SYSTEM session locale.
 */
public final class MatchingSuggestLlmRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "MATCHING_SUGGEST_LLM";

	private MatchingSuggestLlmRun()
	{
	}

	/** Queues the run (resource DESIGN) and returns at once - never refused, see {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = MatchingSuggestLlmRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = MatchingSuggestLlmRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", MatchingSuggestLlmRun.locale(pSession).toLanguageTag());
		final FactoryJobQueue.Queued queued =
			FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), MatchingSuggestLlmRun.STEP, params);
		final ObjectNode out = MatchingSuggestLlmRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/**
	 * Audit F-34: "Propose another" on one entity's matching plan, queued instead of asking the language model inside the request. The
	 * caller has already marked the plan SUPERSEDED with the person's comment ({@link ProposalReviews#startMatchRepropose}, which keeps
	 * the old plan acceptable); the job asks for this entity only and, when the model wrote nothing new for it, puts the plan back to
	 * PROPOSED with the reason - exactly what the wizard did inline.
	 */
	public static ObjectNode startRepropose(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityCode,
		final String pPlanRowId) throws OperationException
	{
		final Adaptation project = MatchingSuggestLlmRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = MatchingSuggestLlmRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", MatchingSuggestLlmRun.locale(pSession).toLanguageTag());
		params.put("entityCode", pEntityCode);
		params.put("reproposeRowId", pPlanRowId);
		final FactoryJobQueue.Queued queued =
			FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), MatchingSuggestLlmRun.STEP, params);
		final ObjectNode out = MatchingSuggestLlmRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		return out;
	}

	/** Audit F-34: one entity's "Propose another" - see {@link #startRepropose}. */
	static String repropose(final Session pJobSession, final Adaptation pFactory, final String pProjectCode, final String pEntityCode, final String pRowId,
		final Locale pLocale) throws Exception
	{
		final ObjectNode outcome;
		try
		{
			outcome = MatchPlanLlmSuggester.suggest(pJobSession, pFactory, pProjectCode, pEntityCode, pLocale);
		}
		catch (final OperationException ex)
		{
			ProposalReviews.revertMatchPlanToProposed(pJobSession, pFactory, pRowId, ex.getMessage());
			throw ex;
		}
		if (MatchingSuggestLlmRun.contains(outcome.path("written"), pEntityCode) || MatchingSuggestLlmRun.contains(outcome.path("refreshed"), pEntityCode)
			|| MatchingSuggestLlmRun.contains(outcome.path("repeated"), pEntityCode))
		{
			return WizardMessages.get(pLocale, "review.match.repropose.done", pEntityCode);
		}
		final String reason = MatchingSuggestLlmRun.reproposeMissReason(outcome, pEntityCode);
		ProposalReviews.revertMatchPlanToProposed(pJobSession, pFactory, pRowId, reason);
		throw new FactoryJobQueue.ExpectedFailure(WizardMessages.get(pLocale, "review.match.repropose.miss", pEntityCode, reason));
	}

	/** Whether one of the outcome's arrays names the entity. */
	static boolean contains(final JsonNode pArray, final String pText)
	{
		if (pArray == null || !pArray.isArray())
		{
			return false;
		}
		for (final JsonNode element : pArray)
		{
			if (pText.equals(element.asText()))
			{
				return true;
			}
		}
		return false;
	}

	/** Why the model wrote nothing new for the entity - moved here from the wizard with "Propose another" (audit F-34), unchanged. */
	static String reproposeMissReason(final JsonNode pOutcome, final String pEntityCode)
	{
		// fix/match-plan-requirements-length (2026-10-12): the plan was written by the model but refused for not fitting its field - the reason says so
		final String refused = pOutcome.path("refused").path(pEntityCode).asText("");
		if (!refused.isEmpty())
		{
			return "the model's new plan was not stored: " + refused + ". The previous plan is kept.";
		}
		// lot M5 (2026-10-18): the model proposed and was refused until the pass or the token budget ended it - say so, with the last refusal, never "made no proposal"
		final JsonNode attempt = pOutcome.path("attempts").path(pEntityCode);
		if (attempt.isObject() && !attempt.path("lastRefusal").asText("").isEmpty())
		{
			final boolean budget = "BUDGET".equals(pOutcome.path("status").asText(""));
			return "the proposal ended " + (budget ? "on the token budget" : "without a plan") + " after " + attempt.path("count").asInt(0) + " attempt(s), every one refused; last refusal: "
				+ attempt.path("lastRefusal").asText("");
		}
		final JsonNode notes = pOutcome.path("notes");
		if (notes.isArray())
		{
			for (final JsonNode note : notes)
			{
				final String text = note.asText("");
				if (text.contains(pEntityCode))
				{
					return text;
				}
			}
		}
		if (pOutcome.path("proseAnswerRecovered").asBoolean(false) || "NO_OUTPUT".equals(pOutcome.path("status").asText("")))
		{
			final String modelText = pOutcome.path("modelText").asText("");
			return modelText.isEmpty() ? "the model's answer never proposed or skipped this entity." : modelText;
		}
		return "the language model made no proposal for this entity this round (run status: " + pOutcome.path("status").asText("unknown") + ").";
	}

	/** The job's work, registered with the queue at module start ({@link FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId,
			final JsonNode pParams) throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final Locale locale = MatchingSuggestLlmRun.localeFromParams(pParams);
			// audit F-34: "Propose another" on one entity (startRepropose) - the whole-project pass otherwise, unchanged
			final String entityCode = pParams.path("entityCode").asText("");
			if (!entityCode.isEmpty())
			{
				final String message = MatchingSuggestLlmRun.repropose(pJobSession, pFactory, projectCode, entityCode, pParams.path("reproposeRowId").asText(""), locale);
				MatchingSuggestLlmRun.attachStep(pFactory, pProjectId, pHandle);
				return message;
			}
			final ObjectNode result = MatchPlanLlmSuggester.suggest(pJobSession, pFactory, projectCode, null, locale);
			MatchingSuggestLlmRun.attachStep(pFactory, pProjectId, pHandle);
			final String refusedText = MatchPlanLlmSuggester.refusedText(result.path("refused"));
			if (!refusedText.isEmpty())
			{
				throw new FactoryJobQueue.ExpectedFailure("Some plans were not stored (" + refusedText + "); the others were.");
			}
			final int eligibleEntities = result.path("eligibleEntities").asInt(0);
			// UX lot U1 (2026-10-13): "refined for 3 entities" while only 2 plans were written, and nothing about the third. Say which objects have no plan and
			// why; a pass that wrote nothing starts with "Nothing to do:" (the convention of the UX lots)
			final WizardSnapshot after = WizardData.readSnapshot(pFactory, pProjectId, pJobSession);
			final List<String> candidates = MatchingPlanCoverage.candidates(after);
			// lot M17 (2026-10-18): an object the facts mark as needing no plan (one source, no shared key) is a measured fact, never a gap
			final java.util.Map<String, String> noPlanFacts = MatchingPlanCoverage.noPlanNeeded(result);
			final List<MatchingPlanCoverage.NoPlan> noPlans = MatchingPlanCoverage.noPlans(candidates, MatchingPlanCoverage.withPlan(after), noPlanFacts);
			final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(candidates, MatchingPlanCoverage.withPlan(after), true, MatchingPlanCoverage.modelNotes(result),
				MatchingPlanCoverage.modelRefused(result), MatchingPlanCoverage.modelAttempts(result), noPlanFacts);
			final int plans = result.path("written").size() + result.path("refreshed").size() + result.path("repeated").size();
			// lot M2 (2026-10-17): the children that follow their parent are named, never counted as objects without a plan
			final List<MatchingPlanCoverage.Follower> followers = MatchingPlanCoverage.followers(after);
			if (plans == 0)
			{
				return MatchingPlanCoverage.withFollows(locale, MatchingPlanCoverage.nothingToDo(locale, candidates.size(), gaps, noPlans, true, true), followers,
					MatchingPlanCoverage.withPlan(after));
			}
			// lot M9 (2026-10-18): an object whose plan is the Factory's fallback is counted among the plans and named, with why
			return MatchingPlanCoverage.withFollows(locale, MatchingPlanCoverage.withNoPlan(locale, MatchingPlanCoverage.withFallbacks(locale, MatchingPlanCoverage.withGaps(locale,
				WizardMessages.plural(locale, plans, "wizard.matching.llm.plans.one", "wizard.matching.llm.plans.other", Integer.valueOf(eligibleEntities)), gaps),
				MatchingPlanCoverage.fallbacks(result)), noPlans), followers, MatchingPlanCoverage.withPlan(after));
		}
	};

	/** See {@link StagingSuggestLlmRun#attachStep} - same best-effort lookup, this pass's own STEP/scope ({@code ""}). */
	private static void attachStep(final Adaptation pFactory, final String pProjectId, final FactoryJobs.Handle pHandle)
	{
		try
		{
			final String stepId = MatchingSuggestLlmRun.latestStepId(pFactory, pProjectId, MatchPlanLlmSuggester.STEP, "");
			if (stepId != null)
			{
				FactoryJobs.attach(pHandle, stepId);
			}
		}
		catch (final RuntimeException ex) // defensive only - never fail the job over the banner's own detail link
		{
			// nothing to do
		}
	}

	/** The most recently STARTED {@code PipelineStep} row of {@code pStep}/{@code pScope} for the project, or null. */
	private static String latestStepId(final Adaptation pFactory, final String pProjectId, final String pStep, final String pScope)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(pStep) + " and " + PipelineSteps.scopeEquals("./scope", pScope));
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
			return latest == null ? null : latest.getOccurrencePrimaryKey().format();
		}
		finally
		{
			rows.close();
		}
	}

	private static Locale locale(final Session pSession)
	{
		return pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
	}

	private static Locale localeFromParams(final JsonNode pParams)
	{
		final String tag = pParams.path("locale").asText("");
		return tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
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
