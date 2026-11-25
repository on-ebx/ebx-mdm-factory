package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.staging.ModelAnswers;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Validation desk lot L3 (docs/STAGING-DESK.md): the desk's "Ask for a correction" - the anomaly pass ({@link AnomalyLlmSuggester}) for ONE business object and for the
 * {@code SAME_CONSTANT} findings alone, as a background job of {@link FactoryJobQueue} (kind {@link #STEP}, resource {@link FactoryJobs#DESIGN}, shared with the other
 * language-model passes so none runs over another for one project). Same shape as {@link QualitySuggestLlmRun}: the postback that enqueues returns at once, the job
 * runs with the system session, the launcher's locale travels in the job's params. The kind starts with {@code STAGING_} so the jobs banner files it under step 4.
 * <p>
 * The pass writes the model's proposals as PROPOSED alternative mappings ({@code AttributeMapping.alternativeTo}); nothing is accepted here - a person decides on the desk.
 */
public final class AnomalyReviewRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "STAGING_ANOMALY_REVIEW";

	private AnomalyReviewRun()
	{
	}

	/** Queues the run for one business object ({@code pEntityId}, an occurrence primary key) and returns at once - never refused, see {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityId) throws OperationException
	{
		final Adaptation project = AnomalyReviewRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = AnomalyReviewRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("entityId", pEntityId == null ? "" : pEntityId);
		params.put("locale", AnomalyReviewRun.locale(pSession).toLanguageTag());
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), AnomalyReviewRun.STEP, params);
		final ObjectNode out = AnomalyReviewRun.MAPPER.createObjectNode();
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
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String entityId = pParams.path("entityId").asText("");
			final Locale locale = AnomalyReviewRun.localeFromParams(pParams);
			final AnomalyLlmSuggester.Result result =
				AnomalyLlmSuggester.suggest(pJobSession, pFactory, projectCode, locale, entityId.isEmpty() ? null : entityId, true);
			// desk lot L3b: the model's answer per submitted mapping goes on the job's own row, so the desk stops offering to ask again for a source it judged right
			final String jobId = FactoryJobQueue.currentJobId();
			if (jobId != null && !result.answers.isEmpty())
			{
				FactoryJobQueue.replaceParams(pFactory, jobId, ModelAnswers.withAnswers(pParams.toString(), result.answers));
			}
			if (result.replacementsProposed == 0)
			{
				return WizardMessages.get(locale, result.sameConstantFlags == 0 ? "wizard.notice.anomalyReviewNone" : "wizard.notice.anomalyReviewZero");
			}
			return WizardMessages.get(locale, "wizard.notice.anomalyReview", WizardMessages.countWords(locale, result.replacementsProposed, "wizard.unit.replacementProposed"));
		}
	};

	/**
	 * Desk lot L3b: what the model answered the last time it was asked about this business object - the answers stored on the latest DONE job of this kind whose params name
	 * {@code pEntityId} (by mapping id); empty when never asked.
	 */
	public static java.util.Map<String, ModelAnswers.Answer> answersOf(final Adaptation pFactory, final String pProjectId, final String pEntityId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.FACTORY_JOB)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./kind=" + CatalogReader.quote(AnomalyReviewRun.STEP));
		try
		{
			String latestParams = null;
			long latestId = -1;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final long id = FactoryJobQueue.idOf(row);
				final String raw = row.getString(FactoryPaths.FactoryJob.PARAMS);
				if (id > latestId && FactoryJobQueue.DONE.equals(row.getString(FactoryPaths.FactoryJob.STATUS)) && AnomalyReviewRun.paramsNameEntity(raw, pEntityId))
				{
					latestId = id;
					latestParams = raw;
				}
			}
			return ModelAnswers.parse(latestParams);
		}
		finally
		{
			rows.close();
		}
	}

	private static boolean paramsNameEntity(final String pParams, final String pEntityId)
	{
		try
		{
			return pParams != null && pEntityId.equals(AnomalyReviewRun.MAPPER.readTree(pParams).path("entityId").asText(""));
		}
		catch (final java.io.IOException ex)
		{
			return false;
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
