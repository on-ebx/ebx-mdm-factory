package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Job-engine lot C3 (2026-09-24): the standalone "Refine with the language model" button of step 4
 * ({@code STAGING_SUGGEST_LLM}) - moved onto {@link FactoryJobQueue} (kind {@link #STEP}, resource {@link
 * FactoryJobs#DESIGN}, the same resource {@code StagingProposalRun}/{@code QualitySuggestLlmRun}/{@code
 * CapabilitySuggestLlmRun}/{@code MatchingSuggestLlmRun} already claim, so none of them ever run over each
 * other for one project). This is the STAND-ALONE button only - {@code ProjectWizardUserService#suggestStaging}
 * / {@code #runStagingLlm} (the deterministic rule pass, and its own synchronous chain into the language-model
 * pass when the gate allows) are untouched, still calling {@link StagingLlmSuggester#suggest} directly, exactly
 * as before this lot.
 * <p>
 * {@link StagingLlmSuggester#suggest} already opens and closes its OWN {@code PipelineStep} ({@link
 * StagingLlmSuggester#STEP_STAGING_SUGGEST}/{@link StagingLlmSuggester#STEP_SCOPE}) before this class's {@link
 * #HANDLER} regains control - {@link #attachStep} looks that row up (newest by {@code startedAt}; the DESIGN
 * resource lock this job holds for the whole project rules out a same-project race) and calls {@link
 * FactoryJobs#attach} with it, best-effort, so the finished job still carries a working {@code stepId}
 * (progress detail, message) for the generic jobs banner, same as every other job kind.
 * <p>
 * <b>Role and locale:</b> {@link StagingLlmSuggester#suggest} performs no session-dependent gating of its own
 * (checked by grep, 2026-09-24) - the administrator check and the "a language model is configured" check both
 * stay in {@code ProjectWizardUserService#suggestStagingLlm}, re-validated synchronously, on the REAL caller
 * session, before this class's {@link #start} is ever called (defence in depth, same posture every other
 * gated action already applies). So nothing but the locale needs to travel in the job's params - captured at
 * enqueue time (the job itself runs with the SYSTEM session, which has none of the launcher's) and used to
 * build the exact same human sentence {@code ProjectWizardUserService#runStagingLlm} used to show as its
 * notice ({@code wizard.step4.llm.summary}).
 */
public final class StagingSuggestLlmRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "STAGING_SUGGEST_LLM";

	private StagingSuggestLlmRun()
	{
	}

	/** Queues the run (resource DESIGN) and returns at once - never refused, see {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = StagingSuggestLlmRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = StagingSuggestLlmRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", StagingSuggestLlmRun.locale(pSession).toLanguageTag());
		final FactoryJobQueue.Queued queued =
			FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), StagingSuggestLlmRun.STEP, params);
		final ObjectNode out = StagingSuggestLlmRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered with the queue at module start ({@link FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId,
			final JsonNode pParams) throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final Locale locale = StagingSuggestLlmRun.localeFromParams(pParams);
			final ObjectNode result = StagingLlmSuggester.suggest(pJobSession, pFactory, projectCode, locale);
			StagingSuggestLlmRun.attachStep(pFactory, pProjectId, pHandle);
			return StagingSuggestLlmRun.summary(locale, result);
		}
	};

	/** The cost and outcome sentence of a language-model staging pass ({@code wizard.step4.llm.summary}) - shared with the chained pass of {@link SuggestRulesRun}. */
	static String summary(final Locale pLocale, final ObjectNode pResult)
	{
		final JsonNode agentRun = pResult.path("agentRun");
		return WizardMessages.get(pLocale, "wizard.step4.llm.summary",
			WizardMessages.countWords(pLocale, agentRun.path("steps").asInt(0), "wizard.unit.step"),
			WizardMessages.countWords(pLocale, agentRun.path("inputTokens").asInt(0), "wizard.unit.token"),
			WizardMessages.countWords(pLocale, agentRun.path("outputTokens").asInt(0), "wizard.unit.token"),
			WizardMessages.countWords(pLocale, pResult.path("entitiesCreated").asInt(0), "wizard.unit.entity"),
			WizardMessages.countWords(pLocale, pResult.path("attributesCreated").asInt(0), "wizard.unit.attribute"),
			WizardMessages.countWords(pLocale, pResult.path("entitiesSuperseded").asInt(0), "wizard.unit.entity"));
	}

	/**
	 * {@link StagingLlmSuggester#suggest} already wrote and closed its own step by the time this runs - see
	 * this class's own Javadoc. Best-effort: a lookup failure never fails the job itself, it only means the
	 * finished job's banner entry carries no {@code stepId} (the job's own result message, built by the
	 * {@link #HANDLER} above, is unaffected either way).
	 */
	private static void attachStep(final Adaptation pFactory, final String pProjectId, final FactoryJobs.Handle pHandle)
	{
		try
		{
			final String stepId = StagingSuggestLlmRun.latestStepId(pFactory, pProjectId, StagingLlmSuggester.STEP_STAGING_SUGGEST, StagingLlmSuggester.STEP_SCOPE);
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
