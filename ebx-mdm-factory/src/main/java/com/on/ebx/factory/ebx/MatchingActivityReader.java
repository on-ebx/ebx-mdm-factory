package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.matching.MatchingActivity;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;

/**
 * Matching-live lot (2026-09-27): reads step 6's {@link MatchingActivity} - the most recent of "Run matching"
 * ({@link MatchingRunRun#STEP}, entity steps {@code MATCHING_RUN}) and "Configure" ({@link
 * MatchingConfigureRun#STEP}, entity steps {@code MATCHING_CONFIG}), whichever was asked last - from rows the
 * run already writes. A job still queued counts as the latest action (it has no step yet). An entity step
 * belongs to the action when it started between the action's own start and its end (or now, while it runs).
 * Read-only; called on every render of step 6 and by {@code GET .../matching/activity} while the panel polls.
 */
public final class MatchingActivityReader
{
	private static final String ENTITY_RUN_STEP = "MATCHING_RUN";
	private static final String ENTITY_CONFIG_STEP = "MATCHING_CONFIG";
	/** An entity step is written a few milliseconds after its parent ends at worst; a little slack keeps the last one. */
	private static final long SLACK_MS = 2000L;

	private MatchingActivityReader()
	{
	}

	public static MatchingActivity read(final Adaptation pFactory, final String pProjectId)
	{
		final MatchingActivity run = MatchingActivityReader.read(pFactory, pProjectId, MatchingActivity.RUN, MatchingRunRun.STEP, MatchingActivityReader.ENTITY_RUN_STEP);
		final MatchingActivity configure = MatchingActivityReader.read(pFactory, pProjectId, MatchingActivity.CONFIGURE, MatchingConfigureRun.STEP,
			MatchingActivityReader.ENTITY_CONFIG_STEP);
		if (MatchingActivity.NONE.equals(run.status))
		{
			return configure;
		}
		if (MatchingActivity.NONE.equals(configure.status))
		{
			return run;
		}
		// the one asked last; a queued action is "now"
		final long runAt = MatchingActivity.QUEUED.equals(run.status) ? Long.MAX_VALUE : run.startedAt;
		final long configureAt = MatchingActivity.QUEUED.equals(configure.status) ? Long.MAX_VALUE : configure.startedAt;
		return configureAt > runAt ? configure : run;
	}

	private static MatchingActivity read(final Adaptation pFactory, final String pProjectId, final String pKind, final String pParentStep, final String pEntityStep)
	{
		final MatchingActivity out = new MatchingActivity();
		out.kind = pKind;
		final Adaptation job = FactoryJobQueue.latest(pFactory, pProjectId, pParentStep);
		if (job != null && FactoryJobQueue.QUEUED.equals(UserView.string(job, FactoryPaths.FactoryJob.STATUS)))
		{
			out.status = MatchingActivity.QUEUED;
			final Date requested = (Date) UserView.get(job, FactoryPaths.FactoryJob.REQUESTED_AT);
			out.startedAt = requested == null ? System.currentTimeMillis() : requested.getTime();
			return out;
		}
		final Adaptation parent = MatchingActivityReader.latestParent(pFactory, pProjectId, pParentStep);
		if (parent == null)
		{
			return out;
		}
		final String stepStatus = UserView.string(parent, FactoryPaths.PipelineStep.STATUS);
		// RUNNING with no live job (the server restarted mid-run) is not live any more: INTERRUPTED rows say so
		out.status = MatchingActivity.statusOf(stepStatus);
		out.startedAt = MatchingActivityReader.millis(parent, FactoryPaths.PipelineStep.STARTED_AT);
		out.endedAt = MatchingActivityReader.millis(parent, FactoryPaths.PipelineStep.ENDED_AT);
		final String message = UserView.string(parent, FactoryPaths.PipelineStep.MESSAGE);
		// lot M12: the step keeps the engine's English; the job of the same run keeps it with the code of its sentences, which the wizard words in the viewer's language
		out.message = MatchingActivityReader.codedMessage(message, job == null ? null : UserView.string(job, FactoryPaths.FactoryJob.MESSAGE));
		if (MatchingActivity.CONFIGURE.equals(pKind))
		{
			// lot M6 (2026-10-18): the configure's detail is its own result (short lists of strings); its validationNotes are coded sentences the panel renders localized
			out.notes.addAll(MatchingActivityReader.validationNotes(UserView.string(parent, FactoryPaths.PipelineStep.DETAIL)));
		}
		final long until = out.endedAt > 0L ? out.endedAt + MatchingActivityReader.SLACK_MS : Long.MAX_VALUE;

		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.PIPELINE_STEP), "./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(pEntityStep));
		final List<Adaptation> entitySteps = new ArrayList<Adaptation>();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String scope = UserView.string(row, FactoryPaths.PipelineStep.SCOPE);
				if (scope == null || scope.isEmpty())
				{
					continue; // the parent itself (MATCHING_RUN is both the parent's and the entities' step name)
				}
				final long started = MatchingActivityReader.millis(row, FactoryPaths.PipelineStep.STARTED_AT);
				if (started >= out.startedAt && started <= until)
				{
					entitySteps.add(row);
				}
			}
		}
		finally
		{
			rows.close();
		}
		entitySteps.sort((a, b) -> Long.compare(MatchingActivityReader.millis(a, FactoryPaths.PipelineStep.STARTED_AT),
			MatchingActivityReader.millis(b, FactoryPaths.PipelineStep.STARTED_AT)));
		for (final Adaptation row : entitySteps)
		{
			final String name = UserView.string(row, FactoryPaths.PipelineStep.SCOPE);
			MatchingActivity.Entity entity = out.entity(name);
			if (entity == null)
			{
				entity = new MatchingActivity.Entity();
				entity.name = name;
				out.entities.add(entity);
			}
			// the latest step of that entity wins (a retried entity inside the same action)
			entity.status = MatchingActivity.statusOf(UserView.string(row, FactoryPaths.PipelineStep.STATUS));
			entity.startedAt = MatchingActivityReader.millis(row, FactoryPaths.PipelineStep.STARTED_AT);
			entity.endedAt = MatchingActivityReader.millis(row, FactoryPaths.PipelineStep.ENDED_AT);
			final String entityMessage = UserView.string(row, FactoryPaths.PipelineStep.MESSAGE);
			entity.message = entityMessage == null ? "" : entityMessage;
			if (!out.isLive() && MatchingActivity.RUNNING.equals(entity.status))
			{
				// the action is over but this entity step never closed: say so instead of "running" forever
				entity.status = MatchingActivity.FAILED;
			}
		}
		return out;
	}

	/**
	 * Pure, package-visible for its test: the text of the step, or the job's message when it is the same sentence carrying its codes ({@code wizard.EngineMessageCodec}). A job
	 * message that says something else (a newer run, a failure text) is not this step's. Never null.
	 */
	public static String codedMessage(final String pStepMessage, final String pJobMessage)
	{
		final String step = pStepMessage == null ? "" : pStepMessage;
		if (pJobMessage == null || pJobMessage.isEmpty())
		{
			return step;
		}
		final com.on.ebx.factory.wizard.EngineMessageCodec.Decoded job = com.on.ebx.factory.wizard.EngineMessageCodec.decode(pJobMessage);
		if (!job.coded())
		{
			return step;
		}
		final String stepEnglish = com.on.ebx.factory.wizard.EngineMessageCodec.decode(step).raw.trim();
		return stepEnglish.isEmpty() || job.raw.trim().equals(stepEnglish) ? pJobMessage : step;
	}

	/** Pure, package-visible for its test: the {@code validationNotes[]} of a configure result JSON; an unreadable or empty detail is "no note". */
	static List<String> validationNotes(final String pDetailJson)
	{
		final List<String> out = new ArrayList<String>();
		if (pDetailJson == null || pDetailJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			for (final com.fasterxml.jackson.databind.JsonNode note : new com.fasterxml.jackson.databind.ObjectMapper().readTree(pDetailJson).path("validationNotes"))
			{
				if (note.isTextual() && !note.asText().trim().isEmpty())
				{
					out.add(note.asText());
				}
			}
		}
		catch (final java.io.IOException ex)
		{
			return out;
		}
		return out;
	}

	private static Adaptation latestParent(final Adaptation pFactory, final String pProjectId, final String pStep)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.PIPELINE_STEP), "./project=" + CatalogReader.quote(pProjectId) + " and ./step=" + CatalogReader.quote(pStep) + " and " + PipelineSteps.scopeEquals("./scope", ""));
		try
		{
			Adaptation latest = null;
			long latestAt = -1L;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final long at = MatchingActivityReader.millis(row, FactoryPaths.PipelineStep.STARTED_AT);
				if (latest == null || at > latestAt)
				{
					latest = row;
					latestAt = at;
				}
			}
			return latest;
		}
		finally
		{
			rows.close();
		}
	}

	private static long millis(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final Object value = UserView.get(pRow, pPath);
		return value instanceof Date ? ((Date) value).getTime() : 0L;
	}
}
