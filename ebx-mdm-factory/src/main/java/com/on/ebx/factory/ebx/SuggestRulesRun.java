package com.on.ebx.factory.ebx;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.wizard.MatchingPlanCoverage;
import com.on.ebx.factory.wizard.WizardMessages;
import com.on.ebx.factory.wizard.WizardRules;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Audit F-34 (wave 10): the three rule-based "propose" passes of the wizard as jobs of {@link FactoryJobQueue}, resource {@link FactoryJobs#DESIGN} (the
 * resource the language-model passes and the one-click proposal run already hold, so none of them ever runs over another for one project):
 * <ul>
 * <li>{@link #STAGING_STEP} - the advanced "Propose the staging" ({@code STAGING_SUGGEST}): the rule pass and, when its gate allows it, the language-model
 * pass in the same job, exactly the chain the button ran inside the request;</li>
 * <li>{@link #QUALITY_STEP} - "Suggest quality rules" ({@code QUALITY_SUGGEST}), which profiles the project's data;</li>
 * <li>{@link #MATCHING_STEP} - "Suggest the matching plans" ({@code MATCHING_SUGGEST}).</li>
 * </ul>
 * The click returns at once with "queued"; the result sentence the wizard used to show as a notice is the job's own message, shown in the banner and in
 * "Recent tasks". Permissions are checked where the job is queued ({@link FactoryJobQueue#enqueue}: the DESIGN right of the kind); the job then runs with the
 * system session, so the only thing the requester's session decided - whether the language model may be chained - travels in the parameters
 * ({@code llmAllowed}), as does the locale the sentences are written in.
 */
public final class SuggestRulesRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STAGING_STEP = "STAGING_SUGGEST_RULES";
	public static final String QUALITY_STEP = "QUALITY_SUGGEST_RULES";
	public static final String MATCHING_STEP = "MATCHING_SUGGEST_RULES";

	private SuggestRulesRun()
	{
	}

	/** Queues the advanced "Propose the staging". {@code pLlmAllowed}: the requester may spend language-model tokens and a connector is configured (the job still re-evaluates the full gate once the rules have written their entities). */
	public static ObjectNode startStaging(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pLlmAllowed) throws OperationException
	{
		return SuggestRulesRun.start(pSession, pFactory, pProjectCode, SuggestRulesRun.STAGING_STEP, pLlmAllowed);
	}

	public static ObjectNode startQuality(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return SuggestRulesRun.start(pSession, pFactory, pProjectCode, SuggestRulesRun.QUALITY_STEP, false);
	}

	/**
	 * Queues "Propose the matching plans". {@code pLlmAllowed}: the requester may spend language-model tokens and a connector is configured - when an accepted object
	 * got no plan from the rules the job then chains the language-model pass, exactly like step 4's "Propose the staging" (UX lot U1, 2026-10-13).
	 */
	public static ObjectNode startMatching(final Session pSession, final Adaptation pFactory, final String pProjectCode, final boolean pLlmAllowed) throws OperationException
	{
		return SuggestRulesRun.start(pSession, pFactory, pProjectCode, SuggestRulesRun.MATCHING_STEP, pLlmAllowed);
	}

	private static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pKind, final boolean pLlmAllowed)
		throws OperationException
	{
		final Adaptation project = SuggestRulesRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = SuggestRulesRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", SuggestRulesRun.locale(pSession).toLanguageTag());
		if (SuggestRulesRun.STAGING_STEP.equals(pKind) || SuggestRulesRun.MATCHING_STEP.equals(pKind))
		{
			params.put("llmAllowed", pLlmAllowed);
		}
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), pKind, params);
		final ObjectNode out = SuggestRulesRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The rule pass of the staging, then - when the requester may and the gate allows - the language-model pass. A model failure after a successful rule pass says both. */
	static final FactoryJobQueue.Handler STAGING_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final Locale locale = SuggestRulesRun.localeFromParams(pParams);
			final ObjectNode rules = StagingSuggester.suggest(pJobSession, pFactory, projectCode);
			final int proposed = rules.path("entitiesCreated").asInt(0);
			final String ruleNotice = WizardMessages.plural(locale, proposed, "wizard.notice.stagingSuggested.one", "wizard.notice.stagingSuggested.other");
			if (!pParams.path("llmAllowed").asBoolean(false))
			{
				return ruleNotice;
			}
			// the gate of the language-model pass on a fresh snapshot, once the rules have written their entities (the requester's DESIGN right was checked when the job was queued)
			final WizardSnapshot after = WizardData.readSnapshot(pFactory, pProjectId, pJobSession);
			if (!WizardRules.actionGates(after, true).suggestStagingLlm.enabled)
			{
				return ruleNotice;
			}
			final ObjectNode llm;
			try
			{
				llm = StagingLlmSuggester.suggest(pJobSession, pFactory, projectCode, locale);
			}
			catch (final OperationException ex)
			{
				throw new FactoryJobQueue.ExpectedFailure(ruleNotice + " " + ErrorText.of(ex));
			}
			return WizardMessages.plural(locale, proposed, "wizard.notice.stagingSuggestedChained.one", "wizard.notice.stagingSuggestedChained.other",
				StagingSuggestLlmRun.summary(locale, llm));
		}
	};

	static final FactoryJobQueue.Handler QUALITY_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = SuggestRulesRun.localeFromParams(pParams);
			final ObjectNode result = QualitySuggester.suggest(pJobSession, pFactory, pParams.path("projectCode").asText(""), null);
			return WizardMessages.get(locale, "wizard.notice.qualitySuggested", WizardMessages.countWords(locale, result.path("stepsCreated").asInt(0), "wizard.unit.cleaningStep"),
				WizardMessages.countWords(locale, result.path("rulesCreated").asInt(0), "wizard.unit.qualityRule"));
		}
	};

	/**
	 * UX lot U1 (2026-10-13): the rule pass, then - when the requester may use the language model, a connector is configured and at least one accepted object got
	 * no plan from the rules - the language-model pass in the same job. A job that ends with no plan written says why per object, in a message starting with
	 * {@link MatchingPlanCoverage#NOTHING_TO_DO} (the rules' only reason to skip an object is that it has no validated identifier: the run on UxCrmSf ended
	 * "0 matching plans written, 3 entities skipped" and the page said nothing more).
	 */
	static final FactoryJobQueue.Handler MATCHING_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = SuggestRulesRun.localeFromParams(pParams);
			final String projectCode = pParams.path("projectCode").asText("");
			final ObjectNode rules = MatchPlanSuggester.suggest(pJobSession, pFactory, projectCode);
			final int rulesPlans = rules.path("written").size() + rules.path("refreshed").size();
			final String ruleSentence = WizardMessages.get(locale, "wizard.notice.matchingSuggested", WizardMessages.countWords(locale, rules.path("written").size(), "wizard.unit.matchPlan"),
				Integer.valueOf(rules.path("refreshed").size()), WizardMessages.countWords(locale, rules.path("skipped").size(), "wizard.unit.entity"));

			// the objects the rules left without a plan, and whether the language model follows (the requester's DESIGN right was checked when the job was queued)
			final WizardSnapshot afterRules = WizardData.readSnapshot(pFactory, pProjectId, pJobSession);
			final List<String> candidates = MatchingPlanCoverage.candidates(afterRules);
			final List<MatchingPlanCoverage.Gap> gapsAfterRules = MatchingPlanCoverage.gaps(candidates, MatchingPlanCoverage.withPlan(afterRules), false, null, null);
			final boolean gate = WizardRules.actionGates(afterRules, true).suggestMatchingLlm.enabled;
			// lot M2 (2026-10-17): the children that follow their parent are named in the message, never counted as objects without a plan
			final List<MatchingPlanCoverage.Follower> followers = MatchingPlanCoverage.followers(afterRules);
			final java.util.Set<String> plansAfterRules = MatchingPlanCoverage.withPlan(afterRules);
			final boolean chain = gate && MatchingPlanCoverage.chainModel(pParams.path("llmAllowed").asBoolean(false), afterRules.llmConfigured, gapsAfterRules.size());
			if (!chain)
			{
				if (rulesPlans == 0)
				{
					return MatchingPlanCoverage.withFollows(locale, MatchingPlanCoverage.nothingToDo(locale, candidates.size(), gapsAfterRules, false, afterRules.llmConfigured),
						followers, plansAfterRules);
				}
				return MatchingPlanCoverage.withFollows(locale, MatchingPlanCoverage.withGaps(locale, ruleSentence, gapsAfterRules), followers, plansAfterRules);
			}

			final ObjectNode llm;
			try
			{
				llm = MatchPlanLlmSuggester.suggest(pJobSession, pFactory, projectCode, null, locale);
			}
			catch (final OperationException ex)
			{
				throw new FactoryJobQueue.ExpectedFailure(ruleSentence + " " + ErrorText.of(ex));
			}
			final WizardSnapshot afterModel = WizardData.readSnapshot(pFactory, pProjectId, pJobSession);
			// lot M17 (2026-10-18): an object the facts mark as needing no plan (one source, no shared key) is a measured fact, never a gap
			final java.util.Map<String, String> noPlanFacts = MatchingPlanCoverage.noPlanNeeded(llm);
			final List<MatchingPlanCoverage.NoPlan> noPlans = MatchingPlanCoverage.noPlans(MatchingPlanCoverage.candidates(afterModel), MatchingPlanCoverage.withPlan(afterModel),
				noPlanFacts);
			final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(MatchingPlanCoverage.candidates(afterModel), MatchingPlanCoverage.withPlan(afterModel), true,
				MatchingPlanCoverage.modelNotes(llm), MatchingPlanCoverage.modelRefused(llm), MatchingPlanCoverage.modelAttempts(llm), noPlanFacts);
			final int plans = rulesPlans + llm.path("written").size() + llm.path("refreshed").size() + llm.path("repeated").size();
			final String refusedText = MatchPlanLlmSuggester.refusedText(llm.path("refused"));
			if (!refusedText.isEmpty())
			{
				throw new FactoryJobQueue.ExpectedFailure(WizardMessages.get(locale, "wizard.matching.refused", refusedText));
			}
			if (plans == 0)
			{
				return MatchingPlanCoverage.withFollows(locale, MatchingPlanCoverage.nothingToDo(locale, candidates.size(), gaps, noPlans, true, true), followers,
					MatchingPlanCoverage.withPlan(afterModel));
			}
			return MatchingPlanCoverage.withFollows(locale,
				MatchingPlanCoverage.withNoPlan(locale,
					MatchingPlanCoverage.withGaps(locale, WizardMessages.get(locale, "wizard.matching.chained", WizardMessages.countWords(locale, plans, "wizard.unit.matchPlan")), gaps), noPlans),
				followers, MatchingPlanCoverage.withPlan(afterModel));
		}
	};

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
