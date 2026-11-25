package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.master.MasterNames;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Audit F-34 (wave 10): the three set-up actions of the distribution step that used to run inside the wizard's request, as jobs of {@link FactoryJobQueue}:
 * <ul>
 * <li>{@link #CONFIGURE_STEP} - "Configure": one Connectors mapping per master table ({@link DistributionConfigurator#configure}); resource {@link
 * FactoryJobs#DISTRIBUTION}, which a send of the same project holds too, so a mapping is never rewritten under a send;</li>
 * <li>{@link #TRIGGERS_STEP} - "Install the triggers": declares, authors and publishes the three triggers of every master table
 * ({@link DistributionTriggerInstaller#install}, a data-model publication); resource {@link FactoryJobs#MASTER}, the model it republishes;</li>
 * <li>{@link #PRODUCT_STEP} - "Create the data product" ({@link DataProductPublication#create}); resource {@link FactoryJobs#DISTRIBUTION}. The wizard
 * has already planned the draft as the person (the catalog rights decide what they may name) before it queues the job; the job writes the plan again.</li>
 * </ul>
 * The result sentence the wizard showed as a notice is the job's message. Permissions are checked where the job is queued (the DESIGN right of the kind).
 */
public final class DistributionSetupRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String CONFIGURE_STEP = "DISTRIBUTION_CONFIGURE_RUN";
	public static final String TRIGGERS_STEP = "DISTRIBUTION_TRIGGERS_RUN";
	public static final String PRODUCT_STEP = "DISTRIBUTION_PRODUCT_RUN";

	private DistributionSetupRun()
	{
	}

	/** Queues the configuration of one target's mappings. */
	public static ObjectNode startConfigure(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId) throws OperationException
	{
		final ObjectNode params = DistributionSetupRun.params(pSession, pProjectCode);
		params.put("targetId", pTargetId);
		return DistributionSetupRun.enqueue(pSession, pFactory, pProjectCode, DistributionSetupRun.CONFIGURE_STEP, params);
	}

	/** Queues the installation of the realtime triggers on the project's master tables. */
	public static ObjectNode startTriggers(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return DistributionSetupRun.enqueue(pSession, pFactory, pProjectCode, DistributionSetupRun.TRIGGERS_STEP, DistributionSetupRun.params(pSession, pProjectCode));
	}

	/** Queues the creation of the data product described by {@code pDraft} (the draft JSON {@link DataProductPublication#create} takes, its connector id already filled in). */
	public static ObjectNode startProduct(final Session pSession, final Adaptation pFactory, final String pProjectCode, final JsonNode pDraft) throws OperationException
	{
		final ObjectNode params = DistributionSetupRun.params(pSession, pProjectCode);
		params.set("draft", pDraft);
		return DistributionSetupRun.enqueue(pSession, pFactory, pProjectCode, DistributionSetupRun.PRODUCT_STEP, params);
	}

	private static ObjectNode params(final Session pSession, final String pProjectCode)
	{
		final ObjectNode params = DistributionSetupRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("locale", DistributionSetupRun.locale(pSession).toLanguageTag());
		return params;
	}

	private static ObjectNode enqueue(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pKind, final ObjectNode pParams)
		throws OperationException
	{
		final Adaptation project = DistributionSetupRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), pKind, pParams);
		final ObjectNode out = DistributionSetupRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	static final FactoryJobQueue.Handler CONFIGURE_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = DistributionSetupRun.localeFromParams(pParams);
			final ObjectNode result = DistributionConfigurator.configure(pJobSession, pFactory, pParams.path("projectCode").asText(""), pParams.path("targetId").asText(""));
			return result.has("message") ? result.path("message").asText() : WizardMessages.get(locale, "wizard.step7dist.notice.done");
		}
	};

	static final FactoryJobQueue.Handler TRIGGERS_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = DistributionSetupRun.localeFromParams(pParams);
			final String projectCode = pParams.path("projectCode").asText("");
			final ObjectNode result = DistributionTriggerInstaller.install(pJobSession, pFactory, projectCode, MasterNames.modelName(projectCode));
			// installed before any matching run: no retire branch was generated - said explicitly rather than the generic "done" (DistributionTriggerInstaller.install's matchingNotActiveHint)
			if (result.path("matchingNotActiveHint").asBoolean(false))
			{
				return WizardMessages.get(locale, "wizard.step7dist.notice.installedNoMatching");
			}
			return result.has("message") ? result.path("message").asText() : WizardMessages.get(locale, "wizard.step7dist.notice.done");
		}
	};

	static final FactoryJobQueue.Handler PRODUCT_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final Locale locale = DistributionSetupRun.localeFromParams(pParams);
			final JsonNode result = DataProductPublication.create(pJobSession, pFactory, pParams.path("projectCode").asText(""), pParams.path("draft"));
			if (DistributionSetupRun.created(result))
			{
				return WizardMessages.get(locale, "wizard.step7dist.notice.productCreated");
			}
			final String why = result.path("write").path("message").asText("");
			throw new FactoryJobQueue.ExpectedFailure(WizardMessages.get(locale, "wizard.step7dist.notice.productPlanFailed") + (why.isEmpty() ? "" : " " + why));
		}
	};

	/** Pure: whether a {@link DataProductPublication#create} answer says the product was written. */
	static boolean created(final JsonNode pResult)
	{
		return pResult.path("write").path("success").asBoolean(pResult.path("success").asBoolean(false));
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
