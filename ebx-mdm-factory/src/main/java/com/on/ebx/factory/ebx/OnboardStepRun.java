package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.wizard.SourceOnboarding;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Job-engine lot C3 (2026-09-24): the onboarding panel's own two write actions - {@code SOURCE_ONBOARD_CATALOG}
 * ({@link SourceOnboarder#applyCatalog}, one {@code connectors_catalog_source} write) and
 * {@code SOURCE_ONBOARD_SCAN} ({@link SourceOnboarder#runScan}, one {@code connectors_sync} write) - moved onto
 * {@link FactoryJobQueue}, resource {@link FactoryJobs#SOURCES} (the SAME resource {@link SourcesRun} uses: a
 * catalog-apply/scan job and a whole "Do everything" onboarding pass of the same project never run at once, and
 * neither ever runs the clicking user's own session or is silently lost to a Tomcat restart any more). Each click
 * is its own job, one connector at a time - unlike {@link SourcesRun} there is exactly ONE MCP call per job here,
 * so no internal stage loop and no {@code PipelineStep} of its own: {@link SourceOnboarder}'s own class Javadoc
 * already explains why onboarding a connection is not itself a project pass (only {@code kernel.log} records the
 * write) - that reasoning is unchanged by moving the call onto a job's own thread instead of the request thread.
 * <p>
 * {@code ProjectWizardUserService}'s onboarding panel keeps its checklist ({@code onboardDiagnosis}/{@code
 * onboardScanMessage}) as plain instance state, filled synchronously by the OLD version of these two actions -
 * now that the actual MCP write runs on a job's own thread, the postback that enqueues it returns before any
 * fresh {@link SourceOnboarding.Diagnosis} exists to show, so this job's own result message is the one place the
 * outcome is recorded until the panel re-reads the connector's facts fresh (see {@code
 * ProjectWizardUserService#onboardPendingKind}, which does exactly that once the job is no longer live).
 */
public final class OnboardStepRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String CATALOG_STEP = "ONBOARD_CATALOG_RUN";
	public static final String SCAN_STEP = "ONBOARD_SCAN_RUN";

	private OnboardStepRun()
	{
	}

	/** Queues {@code SOURCE_ONBOARD_CATALOG}'s work (resource {@link FactoryJobs#SOURCES}) and returns at once - never refused, see {@link StagingLoadRun#start}'s own Javadoc for why. */
	public static ObjectNode startCatalog(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pConnectorId) throws OperationException
	{
		return OnboardStepRun.start(pSession, pFactory, pProjectId, pConnectorId, OnboardStepRun.CATALOG_STEP);
	}

	/** Same as {@link #startCatalog}, for {@code SOURCE_ONBOARD_SCAN}. */
	public static ObjectNode startScan(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pConnectorId) throws OperationException
	{
		return OnboardStepRun.start(pSession, pFactory, pProjectId, pConnectorId, OnboardStepRun.SCAN_STEP);
	}

	private static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pConnectorId, final String pKind)
		throws OperationException
	{
		// audit F-02 (catalog reads): the job registers and scans the source in the catalog on the system session, then the
		// panel shows what it left - queued only for a person who can see the catalog
		CatalogReader.requireVisible(pSession, CatalogReader.dataset(pFactory.getHome().getRepository()), null, "the data catalog's sources");
		final ObjectNode params = OnboardStepRun.MAPPER.createObjectNode();
		params.put("connectorId", pConnectorId);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, pKind, params);
		final ObjectNode out = OnboardStepRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** {@code SOURCE_ONBOARD_CATALOG}'s work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler CATALOG_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String connectorId = pParams.path("connectorId").asText("");
			final SourceOnboarding.Facts facts = SourceOnboarder.applyCatalog(pJobSession, pFactory, connectorId);
			final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
			final String display = facts.connectorLabel == null || facts.connectorLabel.isEmpty() ? connectorId : facts.connectorLabel;
			return diagnosis.ready ? "Catalog source ready for " + display + "." : "Catalog checked for " + display + ": " + OnboardStepRun.firstUnfinished(diagnosis);
		}
	};

	/** {@code SOURCE_ONBOARD_SCAN}'s work, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler SCAN_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String connectorId = pParams.path("connectorId").asText("");
			final SourceOnboarding.Facts facts = SourceOnboarder.runScan(pJobSession, pFactory, connectorId);
			final SourceOnboarding.Diagnosis diagnosis = SourceOnboarding.diagnose(facts);
			final String display = facts.connectorLabel == null || facts.connectorLabel.isEmpty() ? connectorId : facts.connectorLabel;
			final String scanMessage = facts.scanMessage == null || facts.scanMessage.isEmpty() ? "" : " (" + facts.scanMessage + ")";
			return diagnosis.ready ? "Scan finished for " + display + scanMessage + "."
				: "Scan finished for " + display + scanMessage + ": " + OnboardStepRun.firstUnfinished(diagnosis);
		}
	};

	/** Package-visible so {@code OnboardStepRunTest} can exercise it directly, same convention as {@code SourcesRun#uniqueCode}. */
	static String firstUnfinished(final SourceOnboarding.Diagnosis pDiagnosis)
	{
		for (final SourceOnboarding.Step step : pDiagnosis.steps)
		{
			if (step.state != SourceOnboarding.State.DONE)
			{
				return "not finished automatically, " + step.key + " needs attention.";
			}
		}
		return "not finished automatically.";
	}
}
