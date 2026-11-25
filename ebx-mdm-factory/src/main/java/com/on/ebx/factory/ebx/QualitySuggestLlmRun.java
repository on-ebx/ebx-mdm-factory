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
 * Job-engine lot C3 (2026-09-24): the "Suggest with the language model" button of the quality step
 * ({@code QUALITY_SUGGEST_LLM}) - moved onto {@link FactoryJobQueue} (kind {@link #STEP}, resource {@link
 * FactoryJobs#DESIGN}, shared with {@code StagingProposalRun}/{@link StagingSuggestLlmRun}/{@code
 * CapabilitySuggestLlmRun}/{@code MatchingSuggestLlmRun} so none of them ever run over each other for one
 * project). Same shape as {@link StagingSuggestLlmRun} - see that class's own Javadoc for the attach/role/
 * locale reasoning, identical here: {@link QualityLlmSuggester#suggest} performs no session-dependent gating
 * of its own (checked by grep, 2026-09-24), so only the locale travels in the job's params, used to rebuild
 * the exact notice {@code ProjectWizardUserService#suggestQualityLlm} used to show (including the
 * "long-passes-visible" zero-result reason, {@link LlmSuggestZeroResult}).
 */
public final class QualitySuggestLlmRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "QUALITY_SUGGEST_LLM";

	private QualitySuggestLlmRun()
	{
	}

	/** Queues the run (resource DESIGN) and returns at once - never refused, see {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = QualitySuggestLlmRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = QualitySuggestLlmRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", QualitySuggestLlmRun.locale(pSession).toLanguageTag());
		final FactoryJobQueue.Queued queued =
			FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), QualitySuggestLlmRun.STEP, params);
		final ObjectNode out = QualitySuggestLlmRun.MAPPER.createObjectNode();
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
			final Locale locale = QualitySuggestLlmRun.localeFromParams(pParams);
			final ObjectNode result = QualityLlmSuggester.suggest(pJobSession, pFactory, projectCode, null);
			QualitySuggestLlmRun.attachStep(pFactory, pProjectId, pHandle);
			final int stepsCreated = result.path("stepsCreated").asInt(0);
			final int rulesCreated = result.path("rulesCreated").asInt(0);
			if (stepsCreated == 0 && rulesCreated == 0)
			{
				final String reason = LlmSuggestZeroResult.reason(result);
				return reason == null ? WizardMessages.get(locale, "wizard.notice.qualitySuggestedLlmZero")
					: WizardMessages.get(locale, "wizard.notice.qualitySuggestedLlmZeroReason", reason);
			}
			return WizardMessages.get(locale, "wizard.notice.qualitySuggestedLlm", WizardMessages.countWords(locale, stepsCreated, "wizard.unit.cleaningStep"),
				WizardMessages.countWords(locale, rulesCreated, "wizard.unit.qualityRule"),
				WizardMessages.countWords(locale, result.path("entitiesNotNormal").asInt(0), "wizard.unit.entity"));
		}
	};

	/** See {@link StagingSuggestLlmRun#attachStep} - same best-effort lookup, this pass's own STEP/scope ({@code ""}). */
	private static void attachStep(final Adaptation pFactory, final String pProjectId, final FactoryJobs.Handle pHandle)
	{
		try
		{
			final String stepId = QualitySuggestLlmRun.latestStepId(pFactory, pProjectId, QualityLlmSuggester.STEP, "");
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
