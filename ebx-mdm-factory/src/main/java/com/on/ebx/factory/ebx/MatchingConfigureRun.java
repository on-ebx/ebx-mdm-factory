package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.wizard.PartialFailures;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot C3 (2026-09-24, jobs-matching): the wizard's "Configure the matching" button ({@code MATCHING_CONFIGURE})
 * as a job of {@link FactoryJobQueue} (resource {@link FactoryJobs#MATCHING}) - same shape as {@link
 * MasterBuildRun}/{@link StagingProposalRun}: {@link #start} enqueues and returns at once, {@link #status} reads
 * back the latest {@code PipelineStep} the handler wrote. The real work stays entirely in {@link
 * MatchingConfigurator#configure(Session, Adaptation, String, FactoryJobs.Handle)} - this class only wraps it for
 * the queue (parent {@code PipelineStep}, cancellation handle, launcher identity).
 * <p>
 * <b>Why background:</b> {@code configure} makes several {@code match-merge} MCP calls PER promoted entity
 * (get/generate/generate/patch/get again, cascade alignment, default-policy correction) - a project with more
 * than a couple of entities is comfortably past a single HTTP postback, and the button used to block the
 * browser for it.
 * <p>
 * <b>Serializes with the matching workflow bean on purpose.</b> {@link MatchingRunTaskBean} claims {@code
 * FactoryJobs.MATCHING} of the project through {@code PipelineLauncher.run(ctx, bean, resource, projectCode,
 * work)} ({@code FactoryJobs.awaitClaim}), and the queue's own dispatcher claims the SAME resource key (project |
 * MATCHING) before running a queued job of this kind ({@code FactoryJobQueue#tryStart} -&gt; {@code
 * FactoryJobs.claim}) - both go through the one in-memory registry, so a workflow-launched matching run and a
 * wizard-launched configure/run job never touch the add-on's configuration for the same project at the same
 * time; whichever asks first is served first, the other waits its turn (a workflow run waits up to its own
 * bound, {@code PipelineLauncher.WAIT_MS}; a queued job simply keeps its place - {@link FactoryJobQueue#tick}
 * retries every 2 s). Neither {@link MatchingConfigurator#configure} nor {@link MatchingConfigurator#run} (nor
 * {@link MameMatchingApi}) itself ever calls {@code FactoryJobs.claim}/{@code awaitClaim} - checked by grep,
 * 2026-09-24 - so running this job's own body never re-enters the claim it already holds; that would deadlock a
 * job waiting on a resource lock it is itself sitting on.
 * <p>
 * <b>Launcher identity:</b> {@link FactoryJobs#submit} already runs the job body inside {@link Actor#as} for
 * {@code pHandle.launcher} (the user who clicked the button, captured by {@link FactoryJobQueue#enqueue} at
 * queue time via {@link Actor#current}) - every {@link McpServers#client(Session, Adaptation, String)} call
 * {@code configureOne} makes therefore mints its {@code EBXUID} session token for THAT user ({@link
 * SessionToken#cookie}, which reads {@link Actor#explicit()} first), never for the queue's system session.
 * Confirmed by reading the call chain, not by a live run (no running EBX in this worktree).
 */
public final class MatchingConfigureRun
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	public static final String STEP = "MATCHING_CONFIGURE";

	private MatchingConfigureRun()
	{
	}

	/** Queues the run (resource MATCHING) and returns at once - never refused, same as {@link StagingLoadRun#start}. */
	public static ObjectNode start(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = MatchingConfigureRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final ObjectNode params = MatchingConfigureRun.MAPPER.createObjectNode();
		params.put("projectCode", pProjectCode);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), MatchingConfigureRun.STEP, params);
		final ObjectNode out = MatchingConfigureRun.MAPPER.createObjectNode();
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
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams)
			throws Exception
		{
			final String projectCode = pParams.path("projectCode").asText("");
			final String stepId = PipelineSteps.begin(pJobSession, pFactory, pProjectId, MatchingConfigureRun.STEP, "");
			FactoryJobs.attach(pHandle, stepId);
			// UX lot D: set inside the try, thrown AFTER it - the catch below ends the parent step as "failed" and a partial outcome is not that
			FactoryJobQueue.PartialOutcome partial = null;
			final String message;
			try
			{
				final ObjectNode result = MatchingConfigurator.configure(pJobSession, pFactory, projectCode, pHandle);
				message = MatchingConfigureRun.withNotes(MatchingConfigureRun.message(pHandle.cancelled(), pHandle.stopReason(), result.path("configured").size(),
					result.path("refused").size(), result.path("notAccepted").size(), result.path("stoppedBefore").size(), result.path("skippedAlreadyDone").size()),
					MatchingConfigureRun.strings(result.path("followsParent")), MatchingConfigureRun.strings(result.path("childKeptAsIs")),
					MatchingConfigureRun.strings(result.path("previewsLeft")), MatchingConfigureRun.strings(result.path("memberPolicies")),
					MatchingConfigureRun.strings(result.path("validationNotes")));
				PipelineSteps.end(pJobSession, pFactory, stepId, !pHandle.cancelled(), message, result.toString());
				if (!pHandle.cancelled() && result.path("refused").size() > 0)
				{
					final List<String> refused = new ArrayList<String>();
					for (final JsonNode entry : result.path("refused"))
					{
						refused.add(entry.asText());
					}
					partial = new FactoryJobQueue.PartialOutcome(message, PartialFailures.fromEntries(refused,
						result.path("configured").size() + result.path("skippedAlreadyDone").size() + refused.size()));
				}
			}
			catch (final Exception ex) // deliberately broad - the parent step must always be ended, same discipline as MasterBuildRun/StagingProposalRun
			{
				final String failure = "Matching configuration failed: " + ex.getMessage();
				try
				{
					PipelineSteps.end(pJobSession, pFactory, stepId, false, failure, "");
				}
				catch (final Exception logged)
				{
					com.orchestranetworks.service.LoggingCategory.getKernel()
						.error("[mdm-factory] could not even record the failure of matching configure run for project " + projectCode + ": " + logged, logged);
				}
				throw ex;
			}
			// some objects configured, others refused: the job ends DONE_WITH_FAILURES with the refused objects and their reasons (it was a green DONE)
			if (partial != null)
			{
				throw partial;
			}
			return message;
		}
	};

	/** Same shape as {@link MasterBuildRun#status}. Never throws for "no run yet": {@code exists=false}. */
	public static ObjectNode status(final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = MatchingConfigureRun.MAPPER.createObjectNode();
		final Adaptation project = MatchingConfigureRun.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			out.put("exists", false);
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation job = FactoryJobQueue.latest(pFactory, projectId, MatchingConfigureRun.STEP);
		final boolean queued = job != null && FactoryJobQueue.QUEUED.equals(job.getString(FactoryPaths.FactoryJob.STATUS));
		final Adaptation row = MatchingConfigureRun.latestRow(pFactory, projectId);
		if (row == null && !queued)
		{
			out.put("exists", false);
			return out;
		}
		final String status = queued ? FactoryJobQueue.QUEUED : row.getString(FactoryPaths.PipelineStep.STATUS);
		final boolean running = queued || "RUNNING".equals(status) && FactoryJobs.isLive(projectId, MatchingConfigureRun.STEP);
		out.put("exists", true);
		out.put("status", status);
		out.put("running", running);
		out.put("queued", queued);
		if (job != null)
		{
			out.put("jobId", job.getOccurrencePrimaryKey().format());
		}
		if (queued)
		{
			out.put("message", "Queued: waiting for the matching zone of this project to be free.");
			return out;
		}
		final String message = row.getString(FactoryPaths.PipelineStep.MESSAGE);
		if (message != null)
		{
			out.put("message", message);
		}
		return out;
	}

	/**
	 * Pure, package-visible for {@code MatchingConfigureRunTest}: the job's result sentence, built from {@link
	 * MatchingConfigurator#configure(Session, Adaptation, String, FactoryJobs.Handle)}'s own output counts - the
	 * same human wording the synchronous action used to show as {@code wizard.notice.matchingConfigured}, one
	 * more clause added for a stop asked mid-run.
	 */
	static String message(final boolean pCancelled, final String pStopReason, final int pConfigured, final int pRefused, final int pNotAccepted,
		final int pStoppedBefore)
	{
		return MatchingConfigureRun.message(pCancelled, pStopReason, pConfigured, pRefused, pNotAccepted, pStoppedBefore, 0);
	}

	/**
	 * Lot M4 (2026-10-03, defect 2): {@code pUnchanged} counts the entities whose stored policy already matched their accepted
	 * (or APPLIED) plan ({@code skippedAlreadyDone}) - they are configured, just nothing needed rewriting, and must not read as
	 * "not accepted" or vanish from the sentence ("0 configured, 0 refused, 2 not accepted" was the live symptom). The clause only
	 * appears when it is not zero, so the older wording is unchanged.
	 */
	static String message(final boolean pCancelled, final String pStopReason, final int pConfigured, final int pRefused, final int pNotAccepted,
		final int pStoppedBefore, final int pUnchanged)
	{
		if (pCancelled)
		{
			return "Matching configuration stopped (" + pStopReason + "): " + pConfigured + " entit" + (pConfigured == 1 ? "y" : "ies")
				+ " configured before the stop, " + pStoppedBefore + " not started.";
		}
		return pConfigured + " entit" + (pConfigured == 1 ? "y" : "ies") + " configured, " + (pUnchanged > 0 ? pUnchanged + " unchanged (already up to date), " : "")
			+ pRefused + " refused, " + pNotAccepted + " not accepted.";
	}

	private static List<String> strings(final JsonNode pArray)
	{
		final List<String> out = new ArrayList<String>();
		for (final JsonNode element : pArray)
		{
			out.add(element.asText());
		}
		return out;
	}

	/**
	 * Lot M2 (2026-10-17, docs/MATCHING.md sect;56), pure: the three things a configure reports without counting them as a failure - the child entities that follow their
	 * parent (no plan of their own, nothing to configure), a child's own plan an earlier pass configured and this one left as it is, and the Simulate preview policies it could
	 * not remove (with the reason). Nothing is added when all three are empty, so the older sentence is unchanged.
	 */
	static String withNotes(final String pBase, final List<String> pFollowsParent, final List<String> pChildKeptAsIs, final List<String> pPreviewsLeft)
	{
		return MatchingConfigureRun.withNotes(pBase, pFollowsParent, pChildKeptAsIs, pPreviewsLeft, new ArrayList<String>());
	}

	/**
	 * Lot M4 (2026-10-18, docs/MATCHING.md sect;59): {@code pMemberPolicies} are the sentences of the inert policies written on the member tables of a business object (the add-on
	 * refuses a run of the object while a member has no default matching policy and no merge policy); said after the other notes, one sentence per member.
	 */
	static String withNotes(final String pBase, final List<String> pFollowsParent, final List<String> pChildKeptAsIs, final List<String> pPreviewsLeft,
		final List<String> pMemberPolicies)
	{
		return MatchingConfigureRun.withNotes(pBase, pFollowsParent, pChildKeptAsIs, pPreviewsLeft, pMemberPolicies, new ArrayList<String>());
	}

	/**
	 * Lot M6 (2026-10-18, docs/MATCHING.md sect;61): {@code pValidationNotes} are the notes of a business-object validation the add-on's tool could not run (it threw) and
	 * that the configure replaced by a validation of the main table alone - coded strings ({@code MatchingValidation.note}: English text + bundle marker); the job's message,
	 * read as it is in the live panel, carries their English only (the marker is stripped: a job message holds several sentences and is never one coded sentence), the
	 * wizard renders the coded form from the job's detail in the person's language.
	 */
	static String withNotes(final String pBase, final List<String> pFollowsParent, final List<String> pChildKeptAsIs, final List<String> pPreviewsLeft,
		final List<String> pMemberPolicies, final List<String> pValidationNotes)
	{
		final StringBuilder out = new StringBuilder(pBase);
		if (!pFollowsParent.isEmpty())
		{
			out.append(' ').append(pFollowsParent.size() == 1 ? "1 child object has no plan of its own (" : pFollowsParent.size() + " child objects have no plan of their own (")
				.append(String.join("; ", pFollowsParent)).append("): its records follow the parent's matching.");
		}
		if (!pChildKeptAsIs.isEmpty())
		{
			out.append(' ').append(String.join(" ", pChildKeptAsIs));
		}
		if (!pPreviewsLeft.isEmpty())
		{
			out.append(' ').append(String.join(" ", pPreviewsLeft));
		}
		if (!pMemberPolicies.isEmpty())
		{
			out.append(' ').append(String.join(" ", pMemberPolicies));
		}
		for (final String note : pValidationNotes)
		{
			final String english = com.on.ebx.factory.wizard.EngineMessageCodec.decode(note).raw.trim();
			if (!english.isEmpty())
			{
				out.append(" NOTE: ").append(english);
			}
		}
		return out.toString();
	}

	private static Adaptation latestRow(final Adaptation pFactory, final String pProjectId)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(
			"./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(MatchingConfigureRun.STEP) + " and "
				+ PipelineSteps.scopeEquals("./scope", ""));
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
			return latest;
		}
		finally
		{
			rows.close();
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
