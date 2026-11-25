package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit F-36: what the project explorer shows of each project besides the project row itself - its sources, its staging entities and its latest {@code PipelineStep} -
 * gathered in ONE streaming pass over each of the three tables instead of three predicate queries per card (a predicate on a non-key field scans the table, so the
 * explorer used to scan {@code PipelineStep} once per project and sorted every row it found). The accumulator keeps, per project, only a count, a short list of
 * source systems and the latest step: memory is the number of projects, never the number of rows. Pure: no EBX type; the EBX-bound reader is {@code
 * ProjectIndex}, which keeps the result until one of the tables changes (2026-10-11; it used to be a five-second cache).
 */
final class ExplorerFacts
{
	/**
	 * The longest a {@code ProjectIndex} is reused while none of the tables it reads reports a change (2026-10-11; it was a flat five seconds): bounds what the
	 * modification dates cannot see - a deleted {@code PipelineStep} that was a project's latest one, between two full reads of that table.
	 */
	static final long MAX_AGE_MS = 5L * 60L * 1000L;

	/** What the explorer shows of one project. */
	static final class ProjectFacts
	{
		int sourceCount;
		final List<String> sourceSystems = new ArrayList<String>();
		int stagingEntityCount;
		String lastActivityStep;
		Date lastActivityAt;
		/** Whether any step was offered: the first one is kept as it is even with no start time, like the first of the old sorted list. */
		private boolean hasStep;
	}

	private final Map<String, ProjectFacts> byProject = new HashMap<String, ProjectFacts>();

	/** Pure: whether a read made at {@code pLoadedAt} may still be used at {@code pNow} (a clock that went backwards reads as stale). */
	static boolean isFresh(final long pNow, final long pLoadedAt, final long pMaxAgeMs)
	{
		return pNow >= pLoadedAt && pNow - pLoadedAt <= pMaxAgeMs;
	}

	/** The facts of a project; never null (a project with no row anywhere reads as zeros and no activity). */
	ProjectFacts of(final String pProjectId)
	{
		final ProjectFacts facts = this.byProject.get(pProjectId);
		return facts == null ? new ProjectFacts() : facts;
	}

	private ProjectFacts forWrite(final String pProjectId)
	{
		ProjectFacts facts = this.byProject.get(pProjectId);
		if (facts == null)
		{
			facts = new ProjectFacts();
			this.byProject.put(pProjectId, facts);
		}
		return facts;
	}

	/**
	 * Server-paged explorer (2026-10-11): {@code ProjectIndex} rebuilds only the tables that changed, so the parts of a previous read whose table did not
	 * change are carried over into the new accumulator instead of being read again. Each copies one table's share of every project and nothing else.
	 */
	void copySourcesFrom(final ExplorerFacts pPrevious)
	{
		for (final Map.Entry<String, ProjectFacts> e : pPrevious.byProject.entrySet())
		{
			final ProjectFacts facts = this.forWrite(e.getKey());
			facts.sourceCount = e.getValue().sourceCount;
			facts.sourceSystems.clear();
			facts.sourceSystems.addAll(e.getValue().sourceSystems);
		}
	}

	void copyEntitiesFrom(final ExplorerFacts pPrevious)
	{
		for (final Map.Entry<String, ProjectFacts> e : pPrevious.byProject.entrySet())
		{
			this.forWrite(e.getKey()).stagingEntityCount = e.getValue().stagingEntityCount;
		}
	}

	void copyStepsFrom(final ExplorerFacts pPrevious)
	{
		for (final Map.Entry<String, ProjectFacts> e : pPrevious.byProject.entrySet())
		{
			if (!e.getValue().hasStep)
			{
				continue;
			}
			final ProjectFacts facts = this.forWrite(e.getKey());
			facts.hasStep = true;
			facts.lastActivityStep = e.getValue().lastActivityStep;
			facts.lastActivityAt = e.getValue().lastActivityAt;
		}
	}

	/** One {@code ProjectSource} row: counted, and its source system listed once when it is not blank. */
	void offerSource(final String pProjectId, final String pSourceSystem)
	{
		final ProjectFacts facts = this.forWrite(pProjectId);
		facts.sourceCount++;
		if (pSourceSystem != null && !pSourceSystem.isEmpty() && !facts.sourceSystems.contains(pSourceSystem))
		{
			facts.sourceSystems.add(pSourceSystem);
		}
	}

	/** One {@code StagingEntity} row: every entity ever proposed counts, accepted or not. */
	void offerEntity(final String pProjectId)
	{
		this.forWrite(pProjectId).stagingEntityCount++;
	}

	/**
	 * One {@code PipelineStep} row: the project's latest start wins, a row with no start time counts as the oldest possible, and among equals the FIRST row read is kept
	 * - what sorting the whole list by start time, most recent first, and taking its head used to give (the sort is stable).
	 */
	void offerStep(final String pProjectId, final String pStep, final Date pStartedAt)
	{
		final ProjectFacts facts = this.forWrite(pProjectId);
		final long at = pStartedAt == null ? Long.MIN_VALUE : pStartedAt.getTime();
		final long best = facts.lastActivityAt == null ? Long.MIN_VALUE : facts.lastActivityAt.getTime();
		if (!facts.hasStep || at > best)
		{
			facts.hasStep = true;
			facts.lastActivityStep = pStep;
			facts.lastActivityAt = pStartedAt;
		}
	}
}
