package com.on.ebx.factory.ebx;

import com.on.ebx.factory.wizard.EngineMessageCodec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.wizard.PartialFailures;
import com.on.ebx.factory.wizard.RunPanelText;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;

/**
 * What the wizard's progress banner polls (lot C1/C2, 2026-09-24): the project's active jobs - QUEUED with their
 * position on their resource, RUNNING with how long and, when the job reports into a PipelineStep, that step's message
 * and progress JSON - and the last ten finished ones. Plain reads, no MCP call: cheap enough to poll every few seconds.
 */
public final class FactoryJobStatus
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	static final int RECENT = 10;

	private FactoryJobStatus()
	{
	}

	/**
	 * Audit F-49 / end-of-task notice (wave 10): {@link #of(Adaptation, String)} plus {@code me}, the login of the person asking - the screens compare it with the
	 * {@code requestedBy} of the finished jobs to tell the person that a task THEY launched has ended. Null session = no {@code me}.
	 */
	public static ObjectNode of(final Adaptation pFactory, final String pProjectId, final com.orchestranetworks.service.Session pViewer)
	{
		return FactoryJobStatus.of(pFactory, pProjectId, pViewer, (java.util.Locale) null);
	}

	/**
	 * Run panel i18n (2026-10-17): {@link #of(Adaptation, String, com.orchestranetworks.service.Session)} with the texts the engine stored with a code worded in
	 * {@code pLocale} ({@code message}, the texts inside the {@code progressJson} of a running landing job, the {@code reason} of a failed item). With a null locale (REST and MCP
	 * readers: no {@code lang} parameter) the answer is what it always was, the engine's English text with no code marker anywhere. The wizard passes its own language
	 * ({@code ?lang=fr}) so that the script that repaints the live panel from this answer shows the same words as the page it was rendered with.
	 */
	public static ObjectNode of(final Adaptation pFactory, final String pProjectId, final com.orchestranetworks.service.Session pViewer, final java.util.Locale pLocale)
	{
		// second permissions pass (2026-10-14): what a person polls is read with that person's view (job rows the FactoryJob access rule
		// or any other record rule hides are neither listed nor counted, hidden fields stay out of the answer); the engine's own of() reads everything
		final ObjectNode out = FactoryJobStatus.of(UserView.of(pViewer), pFactory, pProjectId, pLocale);
		final String me = pViewer == null ? null : Actor.current(pViewer).userId;
		if (me != null)
		{
			out.put("me", me);
		}
		return out;
	}

	public static ObjectNode of(final Adaptation pFactory, final String pProjectId)
	{
		return FactoryJobStatus.of(UserView.ENGINE, pFactory, pProjectId);
	}

	static ObjectNode of(final UserView pView, final Adaptation pFactory, final String pProjectId)
	{
		return FactoryJobStatus.of(pView, pFactory, pProjectId, (java.util.Locale) null);
	}

	static ObjectNode of(final UserView pView, final Adaptation pFactory, final String pProjectId, final java.util.Locale pLocale)
	{
		final ObjectNode out = FactoryJobStatus.MAPPER.createObjectNode();
		out.put("projectId", pProjectId);
		// UX lot U3 (2026-10-13): the server's clock, so a live list that shows "running for 1 min 12 s" from a stage's startedAt does not depend on the browser's clock
		out.put("serverTime", System.currentTimeMillis());
		// lot M4 (2026-10-03, defect 4, Vol1m): ONE scan of FactoryJob per poll, not two (it used to run the "active" predicate and then
		// the "finished" one, each a full pass over a table that only grows) - the rows of the project are split by status here.
		final List<Adaptation> activeRows = new ArrayList<Adaptation>();
		final List<Adaptation> finished = new ArrayList<Adaptation>();
		final RequestResult rows = pView.rows(pFactory.getTable(FactoryPaths.FACTORY_JOB), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = pView.text(row, FactoryPaths.FactoryJob.STATUS);
				if (FactoryJobQueue.QUEUED.equals(status) || FactoryJobQueue.RUNNING.equals(status))
				{
					activeRows.add(row);
				}
				else
				{
					finished.add(row);
				}
			}
		}
		finally
		{
			rows.close();
		}
		activeRows.sort((pA, pB) -> Long.compare(FactoryJobQueue.idOf(pA), FactoryJobQueue.idOf(pB)));
		final ArrayNode active = out.putArray("active");
		for (final Adaptation row : activeRows)
		{
			final ObjectNode job = FactoryJobStatus.job(pView, pFactory, row, pLocale);
			if (FactoryJobQueue.QUEUED.equals(job.path("status").asText()))
			{
				job.put("position", FactoryJobStatus.position(activeRows, row));
			}
			active.add(job);
		}
		final ArrayNode recent = out.putArray("recent");
		finished.sort((pA, pB) -> Long.compare(FactoryJobQueue.idOf(pB), FactoryJobQueue.idOf(pA)));
		for (int i = 0; i < finished.size() && i < FactoryJobStatus.RECENT; i++)
		{
			recent.add(FactoryJobStatus.job(pView, pFactory, finished.get(i), pLocale));
		}
		out.put("busy", !activeRows.isEmpty());
		return out;
	}

	/**
	 * Audit F-43: the last {@code pLimit} finished jobs of the project, newest first, for the "Recent tasks" list of the wizard and the
	 * Cockpit. {@code mayRerun} says whether {@code pViewer} may queue a job of that kind again ({@link FactoryJobQueue#mayQueue}).
	 */
	public static List<com.on.ebx.factory.wizard.RecentTasks.Task> recentTasks(final Adaptation pFactory, final String pProjectId, final int pLimit,
		final com.orchestranetworks.service.Session pViewer)
	{
		final List<Adaptation> finished = new ArrayList<Adaptation>();
		// second permissions pass (2026-10-14): read with the viewer's view (records and fields); a null viewer is the engine
		final UserView view = UserView.of(pViewer);
		// the same one-predicate read as of() - the status is filtered here
		final RequestResult rows = view.rows(pFactory.getTable(FactoryPaths.FACTORY_JOB), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = view.text(row, FactoryPaths.FactoryJob.STATUS);
				if (!FactoryJobQueue.QUEUED.equals(status) && !FactoryJobQueue.RUNNING.equals(status))
				{
					finished.add(row);
				}
			}
		}
		finally
		{
			rows.close();
		}
		finished.sort((pA, pB) -> Long.compare(FactoryJobQueue.idOf(pB), FactoryJobQueue.idOf(pA)));
		final List<com.on.ebx.factory.wizard.RecentTasks.Task> out = new ArrayList<com.on.ebx.factory.wizard.RecentTasks.Task>();
		for (int i = 0; i < finished.size() && i < pLimit; i++)
		{
			final Adaptation row = finished.get(i);
			final com.on.ebx.factory.wizard.RecentTasks.Task task = new com.on.ebx.factory.wizard.RecentTasks.Task();
			task.jobId = row.getOccurrencePrimaryKey().format();
			task.kind = view.text(row, FactoryPaths.FactoryJob.KIND);
			task.status = view.text(row, FactoryPaths.FactoryJob.STATUS);
			task.requestedBy = view.text(row, FactoryPaths.FactoryJob.REQUESTED_BY);
			task.message = view.text(row, FactoryPaths.FactoryJob.MESSAGE);
			task.requestedAt = FactoryJobStatus.date(view.value(row, FactoryPaths.FactoryJob.REQUESTED_AT));
			task.startedAt = FactoryJobStatus.date(view.value(row, FactoryPaths.FactoryJob.STARTED_AT));
			task.endedAt = FactoryJobStatus.date(view.value(row, FactoryPaths.FactoryJob.ENDED_AT));
			task.mayRerun = pViewer != null && com.on.ebx.factory.wizard.RecentTasks.rerunnable(task.kind) && FactoryJobQueue.mayQueue(pViewer, pFactory, task.kind);
			out.add(task);
		}
		return out;
	}

	/**
	 * UX lot D (2026-10-14): how the latest job that ended with an outcome (DONE, DONE_WITH_FAILURES, FAILED) of each group of kinds
	 * ({@link PartialFailures#LANDING}, {@link PartialFailures#CONFIGURE}, {@link PartialFailures#RUN}) ended, with the items that failed - what the wizard's
	 * missing-items blocks, the warnings under the "Continue" buttons and the Summary's open points read. A cancelled or interrupted job resolved nothing, a
	 * queued or running one has no outcome yet: neither replaces the latest outcome. One scan of the project's jobs of those kinds; a group with no outcome yet
	 * is absent from the map.
	 */
	public static java.util.Map<String, PartialFailures.Last> lastOutcomes(final Adaptation pFactory, final String pProjectId)
	{
		final java.util.Map<String, PartialFailures.Last> out = new java.util.LinkedHashMap<String, PartialFailures.Last>();
		final java.util.Map<String, Long> ids = new java.util.HashMap<String, Long>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.FACTORY_JOB).createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and (./kind="
			+ CatalogReader.quote(PartialFailures.LANDING_KINDS[0]) + " or ./kind=" + CatalogReader.quote(PartialFailures.LANDING_KINDS[1]) + " or ./kind="
			+ CatalogReader.quote(PartialFailures.CONFIGURE_KIND) + " or ./kind=" + CatalogReader.quote(PartialFailures.RUN_KIND) + ")");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = row.getString(FactoryPaths.FactoryJob.STATUS);
				if (!FactoryJobQueue.DONE.equals(status) && !FactoryJobQueue.DONE_WITH_FAILURES.equals(status) && !FactoryJobQueue.FAILED.equals(status))
				{
					continue;
				}
				final String group = PartialFailures.groupOf(row.getString(FactoryPaths.FactoryJob.KIND));
				final long id = FactoryJobQueue.idOf(row);
				final Long seen = ids.get(group);
				if (group == null || seen != null && seen.longValue() >= id)
				{
					continue;
				}
				ids.put(group, Long.valueOf(id));
				out.put(group, new PartialFailures.Last(group, status, FactoryJobQueue.DONE_WITH_FAILURES.equals(status)
					? PartialFailures.parse(row.getString(FactoryPaths.FactoryJob.DETAIL)) : null));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String text(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	private static Date date(final Object pValue)
	{
		return pValue instanceof Date ? (Date) pValue : null;
	}

	private static ObjectNode job(final UserView pView, final Adaptation pFactory, final Adaptation pRow, final java.util.Locale pLocale)
	{
		final ObjectNode job = FactoryJobStatus.MAPPER.createObjectNode();
		job.put("jobId", pRow.getOccurrencePrimaryKey().format());
		job.put("kind", FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.KIND));
		job.put("resource", FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.RESOURCE));
		job.put("status", FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.STATUS));
		job.put("requestedBy", FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.REQUESTED_BY));
		FactoryJobStatus.putDate(job, "requestedAt", pView.value(pRow, FactoryPaths.FactoryJob.REQUESTED_AT));
		FactoryJobStatus.putDate(job, "startedAt", pView.value(pRow, FactoryPaths.FactoryJob.STARTED_AT));
		FactoryJobStatus.putDate(job, "endedAt", pView.value(pRow, FactoryPaths.FactoryJob.ENDED_AT));
		final String message = FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.MESSAGE);
		if (message != null)
		{
			job.put("message", pLocale != null ? RunPanelText.display(message, pLocale) : EngineMessageCodec.decode(message).raw); // wave 10: REST readers get the engine text, not the marker
		}
		// UX lot D: the status is DONE_WITH_FAILURES as stored (no screen needs to read the sentence), and the items that failed come with it
		if (FactoryJobQueue.DONE_WITH_FAILURES.equals(FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.STATUS)))
		{
			final PartialFailures.Failures failures = PartialFailures.parse(FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.DETAIL));
			job.put("failedTotal", failures.total);
			final ArrayNode failed = job.putArray("failedItems");
			for (final PartialFailures.Item item : failures.items)
			{
				failed.addObject().put("id", item.id).put("label", item.label).put("reason", pLocale != null ? RunPanelText.display(item.reason, pLocale) : EngineMessageCodec.stripMarkers(item.reason));
			}
		}
		final FactoryJobs.Handle live = FactoryJobs.live(FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.PROJECT), FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.RESOURCE));
		final String stepId = live != null && live.kind.equals(job.path("kind").asText()) && live.stepId() != null ? live.stepId()
			: FactoryJobStatus.orNull(pView, pRow, FactoryPaths.FactoryJob.STEP_ID);
		if (live != null && live.kind.equals(job.path("kind").asText()))
		{
			job.put("stopRequested", live.cancelled());
		}
		// lot M4 (2026-10-03, defect 4): the parent step's message and progress JSON are only read for a job that is RUNNING - the
		// only one the wizard uses them for. A finished job's row carries its own message, and its step's detail can weigh 70 000
		// characters (the matching-results snapshot, a pipeline trace): reading it for each of the ten "recent" jobs at every poll
		// was ten large rows loaded, serialised and sent for nothing.
		if (stepId != null)
		{
			job.put("stepId", stepId);
		}
		if (stepId != null && FactoryJobQueue.RUNNING.equals(job.path("status").asText()))
		{
			final Adaptation step = pView.lookup(pFactory.getTable(FactoryPaths.PIPELINE_STEP), stepId);
			if (step != null)
			{
				final String stepMessage = FactoryJobStatus.orNull(pView, step, FactoryPaths.PipelineStep.MESSAGE);
				if (stepMessage != null)
				{
					job.put("stepMessage", stepMessage);
				}
				final String detail = FactoryJobStatus.orNull(pView, step, FactoryPaths.PipelineStep.DETAIL);
				if (detail != null)
				{
					job.put("progressJson", pLocale != null && FactoryJobStatus.progressIsWorded(job.path("kind").asText()) ? RunPanelText.localizeProgressJson(detail, pLocale) : EngineMessageCodec.stripMarkers(detail));
				}
			}
		}
		return job;
	}

	/**
	 * Staging screens (2026-10-17): the jobs whose progress carries texts the Factory wrote with a code - the landing run (run panel i18n) and, since this lot, the staging
	 * proposal, the staging build and the master build, so the live panels the page's script repaints show the words of the page they were rendered with.
	 */
	public static boolean progressIsWorded(final String pKind)
	{
		return PartialFailures.LANDING.equals(PartialFailures.groupOf(pKind)) || "STAGING_PROPOSAL".equals(pKind) || "STAGING_LOAD_RUN".equals(pKind) || "MASTER_BUILD_RUN".equals(pKind);
	}

		/** The field as text through the view, null when empty or hidden (the JSON then leaves the key out or writes null, as for an empty value). */
	private static String orNull(final UserView pView, final Adaptation pRow, final com.orchestranetworks.schema.Path pField)
	{
		final Object value = pView.value(pRow, pField);
		return value == null ? null : String.valueOf(value);
	}

/** 1-based place among the active jobs of the same resource (they are listed oldest first). */
	static int position(final List<Adaptation> pActive, final Adaptation pRow)
	{
		final String resource = pRow.getString(FactoryPaths.FactoryJob.RESOURCE);
		int position = 0;
		for (final Adaptation row : pActive)
		{
			if (resource != null && resource.equals(row.getString(FactoryPaths.FactoryJob.RESOURCE)))
			{
				position++;
			}
			if (row == pRow)
			{
				break;
			}
		}
		return position;
	}

	private static void putDate(final ObjectNode pOut, final String pName, final Object pValue)
	{
		if (pValue instanceof Date)
		{
			pOut.put(pName, ((Date) pValue).getTime());
		}
	}
}
