package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.on.ebx.factory.core.FactoryPaths;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Volume-operations lot 8a (2026-09-28, task 1): wraps {@link DistributionRunner#sendFirstFull}/{@link
 * DistributionRunner#sendNow} as jobs of {@link FactoryJobQueue} (resource {@link FactoryJobs#DISTRIBUTION},
 * new this lot) - the exact same shape {@link StagingLoadRun}/{@link MasterBuildRun}/{@link MatchingRunRun}
 * already use for their own REST routes. Before this lot {@code rest.FactoryResource}'s {@code
 * .../first-full-send}/{@code .../send} routes called {@link DistributionRunner} directly, on the Tomcat
 * request thread, for as long as every mapping's own {@code connectors_sync} call took - one call per mapping,
 * synchronous, no cap (task brief item 1). This class changes nothing about WHAT is sent or HOW (both methods
 * below are thin wrappers, {@link DistributionRunner} untouched) - only WHERE it runs and how a caller learns
 * the outcome (poll {@code GET /projects/{code}/jobs} instead of holding an HTTP connection open).
 * <p>
 * Two separate job kinds ({@link #FIRST_FULL_STEP}/{@link #SEND_STEP}), same resource: a first-full send and
 * an ordinary send-now of two DIFFERENT targets of the same project may run side by side under the old
 * direct-call code (nothing there ever serialized them either), but a project's DISTRIBUTION resource is
 * shared across every target once jobified - two sends of the SAME project now queue one after another rather
 * than racing on the same {@code DistributionOutbox}/{@code DistributionTarget} rows. Not verified live (no
 * EBX/Connectors instance reachable this session).
 * <p>
 * Wave 13: the job's {@link FactoryJobs.Handle} is handed to {@link DistributionRunner}, which follows each mapping's Connectors run and, when the
 * job is asked to stop (the {@code POST .../targets/{targetId}/stop} route, {@link DistributionSendStop}, or {@code POST /jobs/{id}/cancel}),
 * stops the run and ends the send STOPPED; the queue then records the job CANCELLED.
 */
public final class DistributionSendRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String FIRST_FULL_STEP = "DISTRIBUTION_FIRST_FULL_SEND_RUN";
	public static final String SEND_STEP = "DISTRIBUTION_SEND_RUN";

	private DistributionSendRun()
	{
	}

	/** Queues a scope-ALL first full send of one target - never refused, same as {@link StagingLoadRun#start}. */
	public static ObjectNode startFirstFull(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId)
		throws OperationException
	{
		final Adaptation project = DistributionSendRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = DistributionSendRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("targetId", pTargetId);
		return DistributionSendRun.enqueue(pSession, pFactory, project, DistributionSendRun.FIRST_FULL_STEP, params);
	}

	/** Queues an ON_DEMAND/SCHEDULED/manual send-now of one target - {@code pScope} is {@code changed}/{@code all}, blank falls back to the target's own scope (see {@link DistributionRunner#sendNow}). */
	public static ObjectNode startSendNow(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId, final String pScope)
		throws OperationException
	{
		final Adaptation project = DistributionSendRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = DistributionSendRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		params.put("targetId", pTargetId);
		params.put("scope", pScope == null ? "" : pScope);
		return DistributionSendRun.enqueue(pSession, pFactory, project, DistributionSendRun.SEND_STEP, params);
	}

	private static ObjectNode enqueue(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final String pKind, final ObjectNode pParams)
		throws OperationException
	{
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProject.getOccurrencePrimaryKey().format(), pKind, pParams);
		final ObjectNode out = DistributionSendRun.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work for {@link #FIRST_FULL_STEP}, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler FIRST_FULL_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String targetId = pParams.path("targetId").asText("");
			final ObjectNode result = DistributionRunner.sendFirstFull(pJobSession, pFactory, projectCode, targetId, pHandle);
			return DistributionSendRun.verdictCoded(true, targetId, result);
		}
	};

	/** The job's work for {@link #SEND_STEP}, registered with the queue at module start ({@code FactoryJobKinds}). */
	static final FactoryJobQueue.Handler SEND_HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String targetId = pParams.path("targetId").asText("");
			final String scope = pParams.path("scope").asText("");
			final ObjectNode result = DistributionRunner.sendNow(pJobSession, pFactory, projectCode, targetId, scope.isEmpty() ? null : scope, pHandle);
			return DistributionSendRun.verdictCoded(false, targetId, result);
		}
	};

	/**
	 * Audit F-11 (wave 5): a send whose own status is FAILED ends the JOB as failed too, with the send's message - the banner
	 * (and {@code GET .../jobs}) used to show such a job as DONE ("... FAILED.") in green. A DONE send returns its message.
	 */
	static String verdict(final String pLabel, final ObjectNode pResult) throws FactoryJobQueue.ExpectedFailure
	{
		final String status = pResult.path("status").asText("?");
		final String message = pResult.path("message").asText("");
		if ("FAILED".equals(status))
		{
			throw new FactoryJobQueue.ExpectedFailure(pLabel + " failed" + (message.isEmpty() ? "." : ": " + message));
		}
		return pLabel + ": " + status + (message.isEmpty() ? "." : " - " + message);
	}

	/**
	 * {@link #verdict} with its words coded ({@code RunTexts.sendEnded} / {@code sendFailed}): the same English text, plus the marker the wizard words in the person's language
	 * (step 7 screen counts, 2026-10-18). The send's own message is the coded one when the send wrote it.
	 */
	static String verdictCoded(final boolean pFirstFull, final String pTargetId, final ObjectNode pResult) throws FactoryJobQueue.ExpectedFailure
	{
		final String status = pResult.path("status").asText("?");
		final String message = pResult.has("messageCoded") ? pResult.path("messageCoded").asText("") : pResult.path("message").asText("");
		if ("FAILED".equals(status))
		{
			throw new FactoryJobQueue.ExpectedFailure(com.on.ebx.factory.landing.RunTexts.sendFailed(pFirstFull, pTargetId, message).stored());
		}
		return com.on.ebx.factory.landing.RunTexts.sendEnded(pFirstFull, pTargetId, status, message).stored();
	}

	/** Pure: whether a queued job's stored parameters name this target (the wizard refuses a second identical send while one is queued or running). */
	static boolean paramsNameTarget(final String pParamsJson, final String pTargetId)
	{
		if (pParamsJson == null || pTargetId == null || pTargetId.isEmpty())
		{
			return false;
		}
		try
		{
			return pTargetId.equals(DistributionSendRun.MAPPER.readTree(pParamsJson).path("targetId").asText(""));
		}
		catch (final java.io.IOException ex)
		{
			return false;
		}
	}

	/**
	 * Audit F-11: true when a first-full or send-now job of this target is already QUEUED or RUNNING - the wizard refuses a second
	 * click with a sentence instead of queuing a send that would only wait behind the first one on the project's DISTRIBUTION resource.
	 */
	public static boolean isActive(final Adaptation pFactory, final String pProjectId, final String pTargetId)
	{
		for (final Adaptation job : FactoryJobQueue.active(pFactory, pProjectId))
		{
			final String kind = job.getString(FactoryPaths.FactoryJob.KIND);
			if ((DistributionSendRun.FIRST_FULL_STEP.equals(kind) || DistributionSendRun.SEND_STEP.equals(kind))
				&& DistributionSendRun.paramsNameTarget(job.getString(FactoryPaths.FactoryJob.PARAMS), pTargetId))
			{
				return true;
			}
		}
		return false;
	}

	/** Wave 13: the ids of the first-full and send-now jobs of this target that are QUEUED or RUNNING - what the stop route cancels. */
	public static java.util.List<String> activeJobIds(final Adaptation pFactory, final String pProjectId, final String pTargetId)
	{
		final java.util.List<String> ids = new java.util.ArrayList<String>();
		for (final Adaptation job : FactoryJobQueue.active(pFactory, pProjectId))
		{
			final String kind = job.getString(FactoryPaths.FactoryJob.KIND);
			if ((DistributionSendRun.FIRST_FULL_STEP.equals(kind) || DistributionSendRun.SEND_STEP.equals(kind))
				&& DistributionSendRun.paramsNameTarget(job.getString(FactoryPaths.FactoryJob.PARAMS), pTargetId))
			{
				ids.add(job.getOccurrencePrimaryKey().format());
			}
		}
		return ids;
	}

	/** Audit F-11: the arming gate's refusal for a first full send, or null when it may start - read BEFORE queuing so a refusal is shown at once, not as a failed job. */
	public static String firstFullRefusal(final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = DistributionSendRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final com.on.ebx.factory.distribution.DistributionArmingGate.Decision gate = com.on.ebx.factory.distribution.DistributionArmingGate
			.canSendFirstFull(DistributionRunner.readiness(pFactory, project.getOccurrencePrimaryKey().format()));
		return gate.allowed ? null : "Cannot start the first full send: " + gate.reason;
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
