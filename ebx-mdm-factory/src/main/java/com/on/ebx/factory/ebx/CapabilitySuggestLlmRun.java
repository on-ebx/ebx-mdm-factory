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
 * Job-engine lot C3 (2026-09-24): the "Suggest script constraints" button of the capability step
 * ({@code CAPABILITY_SUGGEST_LLM}) - moved onto {@link FactoryJobQueue} (kind {@link #STEP}, resource {@link
 * FactoryJobs#DESIGN}, shared with {@code StagingProposalRun}/{@link StagingSuggestLlmRun}/{@link
 * QualitySuggestLlmRun}/{@code MatchingSuggestLlmRun} so none of them ever run over each other for one
 * project). Same shape as {@link StagingSuggestLlmRun} - see that class's own Javadoc for the attach/role/
 * locale reasoning, identical here: {@link CapabilityLlmSuggester#suggest} performs no session-dependent
 * gating of its own (checked by grep, 2026-09-24), so only the locale travels in the job's params, used to
 * rebuild the exact notice {@code ProjectWizardUserService#suggestCapabilitiesLlm} used to show (including
 * the "long-passes-visible" zero-result reason, {@link LlmSuggestZeroResult}).
 */
public final class CapabilitySuggestLlmRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "CAPABILITY_SUGGEST_LLM";

	private CapabilitySuggestLlmRun()
	{
	}

	/** Queues the run (resource DESIGN) and returns at once - never refused, see {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = CapabilitySuggestLlmRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = CapabilitySuggestLlmRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", CapabilitySuggestLlmRun.locale(pSession).toLanguageTag());
		final FactoryJobQueue.Queued queued =
			FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), CapabilitySuggestLlmRun.STEP, params);
		final ObjectNode out = CapabilitySuggestLlmRun.MAPPER.createObjectNode();
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
			final Locale locale = CapabilitySuggestLlmRun.localeFromParams(pParams);
			final ObjectNode result = CapabilityLlmSuggester.suggest(pJobSession, pFactory, projectCode, null, null, com.on.ebx.factory.staging.MappingScope.ACCEPTED_ONLY, locale);
			CapabilitySuggestLlmRun.attachStep(pFactory, pProjectId, pHandle);
			final int scriptConstraintsCreated = result.path("scriptConstraintsCreated").asInt(0);
			final int servicesCreated = result.path("servicesCreated").asInt(0);
			if (scriptConstraintsCreated == 0 && servicesCreated == 0)
			{
				final String reason = LlmSuggestZeroResult.reason(result);
				return reason == null ? WizardMessages.get(locale, "wizard.notice.capabilitiesSuggestedZero")
					: WizardMessages.get(locale, "wizard.notice.capabilitiesSuggestedZeroReason", reason);
			}
			return WizardMessages.get(locale, "wizard.notice.capabilitiesSuggested",
				WizardMessages.countWords(locale, scriptConstraintsCreated, "wizard.unit.scriptConstraint"),
				WizardMessages.countWords(locale, servicesCreated, "wizard.unit.connectorServiceNote"));
		}
	};

	/** See {@link StagingSuggestLlmRun#attachStep} - same best-effort lookup, this pass's own STEP/scope ({@code ""}). */
	private static void attachStep(final Adaptation pFactory, final String pProjectId, final FactoryJobs.Handle pHandle)
	{
		try
		{
			final String stepId = CapabilitySuggestLlmRun.latestStepId(pFactory, pProjectId, CapabilityLlmSuggester.STEP, "");
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
