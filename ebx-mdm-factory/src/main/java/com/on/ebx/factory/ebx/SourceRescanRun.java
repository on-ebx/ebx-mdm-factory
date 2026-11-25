package com.on.ebx.factory.ebx;

import com.on.ebx.factory.landing.KeyPrimeRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.wizard.PlanOutcomes;
import com.on.ebx.factory.wizard.SourceOnboarding;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * UX lot U2 (2026-10-13): "Scan again" on an application card of step 2, for ANY application - one whose catalog scan is the
 * regular one included (until now only a deduced-scan application had a "Rescan"; measured live the same day, a Salesforce
 * application whose only catalog object had no scanned column had no way out inside the wizard). One background job, resource
 * {@link FactoryJobs#SOURCES} (the resource the onboarding scan uses, so it never runs beside one):
 * <ol>
 * <li>{@link SourceOnboarder#rescan}: the catalog scan of the application's connection through Connectors - "Catalog scan: &lt;SOURCE&gt;" mapping,
 * the same call as {@link OnboardStepRun#SCAN_HANDLER} - creating the catalog source first when it is missing;</li>
 * <li>{@link LandingPlanner#plan}: the tables the scan found, new ones unticked, and the result text of a plan ({@link LandingSourceRun#planMessage}: the
 * counts, or "Nothing to do: ..." with the reasons).</li>
 * </ol>
 * The plan runs inside this job, not as a second one: it is a read of the catalog plus writes of the project's own rows, which is what
 * {@link SourcesRun} (resource SOURCES as well) already does after adding an application.
 */
public final class SourceRescanRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** Starts with {@code SOURCES_} so the task list files it under step 2 ({@code RecentTasks#stepOf}). */
	public static final String STEP = "SOURCES_RESCAN_RUN";

	private SourceRescanRun()
	{
	}

	/** Queues the scan and the plan of one application (a ProjectSource id) and returns at once. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectSourceId) throws OperationException
	{
		final Adaptation source = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		if (source == null)
		{
			throw EngineRefusal.coded("missing.projectSource", "Project source " + pProjectSourceId + " not found", pProjectSourceId);
		}
		final String connector = source.getString(FactoryPaths.ProjectSource.CONNECTOR);
		if (connector == null || connector.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noConnectorToScan", "Application " + pProjectSourceId + " names no connection: there is nothing to scan again.", pProjectSourceId);
		}
		// audit F-02 (catalog reads): the job registers/scans in the catalog and plans from what it finds on the system session - queued only for a person who can see the catalog
		CatalogReader.requireVisible(pSession, CatalogReader.dataset(pFactory.getHome().getRepository()), null, "the data catalog's sources");
		final String projectId = source.getString(FactoryPaths.ProjectSource.PROJECT);
		final ObjectNode params = SourceRescanRun.MAPPER.createObjectNode();
		params.put("projectSourceId", pProjectSourceId);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, projectId, SourceRescanRun.STEP, params);
		final ObjectNode out = SourceRescanRun.MAPPER.createObjectNode();
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
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams) throws Exception
		{
			final String projectSourceId = pParams.path("projectSourceId").asText("");
			final Adaptation source = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
			if (source == null)
			{
				throw new FactoryJobQueue.ExpectedFailure("The application is no longer in the project.");
			}
			final SourceOnboarding.Facts facts = SourceOnboarder.rescan(pJobSession, pFactory, source.getString(FactoryPaths.ProjectSource.CONNECTOR));
			if ("ERROR".equals(facts.lastScanStatus) || SourceRescanRun.failed(facts.scanMessage))
			{
				throw new FactoryJobQueue.ExpectedFailure("The scan did not complete" + (facts.scanMessage == null || facts.scanMessage.isEmpty() ? "." : ": " + facts.scanMessage));
			}
			if (pHandle.cancelled())
			{
				return "The scan was done; the tables were not looked for again.";
			}
			final ObjectNode planned = LandingPlanner.plan(pJobSession, pFactory, projectSourceId, false, true);
			// landing leftovers (2026-10-17): a key from the naming rule for the tables the source gave no profile for (sample files); never fails the job
			final KeyPrimeRunner.Outcome primed = KeyPrimer.prime(pJobSession, pFactory, projectSourceId, pHandle);
			final String message = LandingSourceRun.planMessage(planned);
			final String done = message.startsWith(PlanOutcomes.NOTHING_TO_DO) ? message : "Scanned again. " + message;
			final FactoryJobQueue.PartialOutcome partial = JobOutcomes.keyPrimeOutcome(primed, done); // F2-15
			if (partial != null)
			{
				throw partial;
			}
			return done;
		}
	};

	/** Whether {@link SourceOnboarder#runScan}'s outcome text says the Connectors call itself was refused or failed (its two failure wordings). */
	static boolean failed(final String pScanMessage)
	{
		return pScanMessage != null && (pScanMessage.startsWith("connectors_sync refused") || pScanMessage.startsWith("connectors_sync failed"));
	}
}
