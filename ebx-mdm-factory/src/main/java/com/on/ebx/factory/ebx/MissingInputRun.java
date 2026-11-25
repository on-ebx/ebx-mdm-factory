package com.on.ebx.factory.ebx;

import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.capability.MissingInputs;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Validation desk lot L5 (docs/STAGING-DESK.md): the "Propose a field for it" button of the "Cannot run as proposed" group, as a background job (kind {@link #STEP},
 * resource {@link FactoryJobs#DESIGN}, shared with the other language-model passes so none runs over another for one project). The work is {@link
 * CapabilityLlmSuggester#suggestForMissingInputs}: the capability pass narrowed to the computed field a waiting suggestion needs. The needs are re-read when the job
 * RUNS (a person may have decided some meanwhile), so the job's parameters carry only the project and the language. Same shape as {@link CapabilitySuggestLlmRun}.
 */
public final class MissingInputRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "STAGING_INPUT";

	private MissingInputRun()
	{
	}

	/** Queues the run and returns at once; a request identical to a queued or running one folds into it ({@link FactoryJobQueue#enqueue}). */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = MissingInputRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = MissingInputRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", (pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale()).toLanguageTag());
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), MissingInputRun.STEP, params);
		final ObjectNode out = MissingInputRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** Whether a job of this kind is queued or running for the project (the desk's button waits). */
	public static boolean isActive(final Adaptation pFactory, final String pProjectId)
	{
		for (final Adaptation job : FactoryJobQueue.active(pFactory, pProjectId))
		{
			if (MissingInputRun.STEP.equals(job.getString(FactoryPaths.FactoryJob.KIND)))
			{
				return true;
			}
		}
		return false;
	}

	/** The job's work, registered with the queue at module start ({@link FactoryJobKinds}). */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String tag = pParams.path("locale").asText("");
			final Locale locale = tag.isEmpty() ? Locale.ENGLISH : Locale.forLanguageTag(tag);
			final List<MissingInputs.Need> needs = MissingInputReader.needs(pFactory, pProjectId);
			if (needs.isEmpty())
			{
				return WizardMessages.get(locale, "desk.l5.notice.nothingWaiting");
			}
			final ObjectNode result = CapabilityLlmSuggester.suggestForMissingInputs(pJobSession, pFactory, projectCode, needs, locale);
			MissingInputRun.attachStep(pFactory, pProjectId, pHandle);
			final int fields = result.path("scriptDerivationsCreated").asInt(0);
			if (fields == 0)
			{
				return WizardMessages.get(locale, "desk.l5.notice.noField");
			}
			return WizardMessages.get(locale, fields == 1 ? "desk.l5.notice.fieldProposed.one" : "desk.l5.notice.fieldProposed.other", Integer.valueOf(fields));
		}
	};

	/** The step row the pass opened, for the jobs banner's detail link; best effort. */
	private static void attachStep(final Adaptation pFactory, final String pProjectId, final FactoryJobs.Handle pHandle)
	{
		try
		{
			final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./step="
				+ CatalogReader.quote(CapabilityLlmSuggester.STEP) + " and " + PipelineSteps.scopeEquals("./scope", ""));
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
				if (latest != null)
				{
					FactoryJobs.attach(pHandle, latest.getOccurrencePrimaryKey().format());
				}
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex) // defensive only - never fail the job over the banner's own detail link
		{
			// nothing to do
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
