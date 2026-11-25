package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;

/**
 * Server-paged project explorer (2026-10-11): one card per project of a factory dataset - the project row plus what {@link
 * ExplorerFacts} gathers from {@code ProjectSource}, {@code StagingEntity} and {@code PipelineStep} - kept per dataset and
 * shared by every session, so a page change, a search or a facet click answers from memory ({@link ProjectExplorerQuery})
 * instead of reading the tables again.
 * <p>
 * Freshness: the index remembers {@link AdaptationTable#getLastModificationDate()} of the four tables (a cheap read, no record
 * scanned) and is rebuilt when one of them moved, or after {@link ExplorerFacts#MAX_AGE_MS}. A rebuild reads again only the
 * tables that moved (the others are carried over from the previous index while it is younger than the limit), and {@code
 * PipelineStep} - the table that grows with every run - is read incrementally: its key is an auto-increment id and a step's
 * start time is written once, at its creation ({@code PipelineSteps.begin}), so only the rows with an id above the highest one
 * already read can change a project's latest step. What that cannot see (a step deleted by a reset or by retention) is bounded by
 * the age limit and by a full read whenever the {@code Project} table itself moved (project deletion and reset write there).
 * <p>
 * Permissions (2026-10-14): this index is read with no session, so it is a cache of VALUES, never an authority. Nothing of it reaches a page without going
 * through {@link ProjectAccess} (the records, child rows and fields the user's session may see) and {@link ProjectView} (the user's own cards). It also keeps
 * the child rows with their keys ({@link #sourceRows}...) so a user who sees only part of them gets counts over that part.
 * <p>
 * Concurrency: one lock per dataset, never a class-wide one. While one thread rebuilds, the others keep answering with the
 * previous index; only a dataset that has no index yet makes its first readers wait for the first build.
 */
final class ProjectIndex
{
	private static final ConcurrentHashMap<String, ProjectIndex> CACHE = new ConcurrentHashMap<String, ProjectIndex>();
	private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<String, ReentrantLock>();

	private static final int PROJECT = 0;
	private static final int SOURCE = 1;
	private static final int ENTITY = 2;
	private static final int STEP = 3;
	private static final Path[] TABLES = { FactoryPaths.PROJECT, FactoryPaths.PROJECT_SOURCE, FactoryPaths.STAGING_ENTITY, FactoryPaths.PIPELINE_STEP };

	/** Every project, in table order, facts included, {@link ProjectExplorerPage.Card#favorite} always false (a favorite belongs to a session). Never modified once built. */
	final List<ProjectExplorerPage.Card> cards;
	/** {@link ProjectExplorerQuery#searchKey} of each card, parallel to {@link #cards}. */
	final List<String> searchKeys;
	/**
	 * 2026-10-14 (EBX permissions): the rows of the three fact tables, each with its primary key, so that {@link ProjectView} can recount the facts over
	 * the rows ONE user's session returns. The aggregates of {@link #cards} stay the answer when the user sees every row. Never modified once built.
	 */
	final List<ProjectView.Row> sourceRows;
	final List<ProjectView.Row> entityRows;
	final List<ProjectView.Row> stepRows;
	/** The project rows alone (no facts), reused when only a fact table moved. */
	private final List<ProjectExplorerPage.Card> bases;
	private final ExplorerFacts facts;
	private final long[] dates;
	private final long maxStepId;
	private final long builtAt;
	/** The first build of this generation of the index (a carried-over part is never older than this plus the age limit). */
	private final long fullAt;
	final long buildMillis;

	private ProjectIndex(final List<ProjectExplorerPage.Card> pBases, final ExplorerFacts pFacts, final List<ProjectView.Row> pSourceRows,
		final List<ProjectView.Row> pEntityRows, final List<ProjectView.Row> pStepRows, final long[] pDates, final long pMaxStepId, final long pFullAt,
		final long pBuildMillis)
	{
		this.bases = pBases;
		this.sourceRows = Collections.unmodifiableList(pSourceRows);
		this.entityRows = Collections.unmodifiableList(pEntityRows);
		this.stepRows = Collections.unmodifiableList(pStepRows);
		this.facts = pFacts;
		this.dates = pDates;
		this.maxStepId = pMaxStepId;
		this.builtAt = System.currentTimeMillis();
		this.fullAt = pFullAt;
		this.buildMillis = pBuildMillis;
		final List<ProjectExplorerPage.Card> cards = new ArrayList<ProjectExplorerPage.Card>(pBases.size());
		final List<String> keys = new ArrayList<String>(pBases.size());
		for (final ProjectExplorerPage.Card base : pBases)
		{
			final ProjectExplorerPage.Card card = base.copy();
			final ExplorerFacts.ProjectFacts of = pFacts.of(card.key);
			card.sourceCount = of.sourceCount;
			card.sourceSystems.addAll(of.sourceSystems);
			card.stagingEntityCount = of.stagingEntityCount;
			card.lastActivityStep = of.lastActivityStep;
			card.lastActivityAt = of.lastActivityAt;
			cards.add(card);
			keys.add(ProjectExplorerQuery.searchKey(card));
		}
		this.cards = Collections.unmodifiableList(cards);
		this.searchKeys = Collections.unmodifiableList(keys);
	}

	/** The index of a factory dataset, rebuilt when one of its four tables moved (see the class comment). */
	static ProjectIndex of(final Adaptation pDataset)
	{
		final String key = pDataset.getHome().getKey().format() + "|" + pDataset.getAdaptationName().getStringName();
		final ProjectIndex cached = ProjectIndex.CACHE.get(key);
		if (cached != null && cached.usable(ProjectIndex.dates(pDataset), System.currentTimeMillis()))
		{
			return cached;
		}
		final ReentrantLock lock = ProjectIndex.LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
		if (cached != null)
		{
			if (!lock.tryLock())
			{
				return cached; // someone is rebuilding it: the previous index still answers meanwhile
			}
		}
		else
		{
			lock.lock();
		}
		try
		{
			final ProjectIndex previous = ProjectIndex.CACHE.get(key);
			final long[] dates = ProjectIndex.dates(pDataset);
			if (previous != null && previous.usable(dates, System.currentTimeMillis()))
			{
				return previous;
			}
			final ProjectIndex built = ProjectIndex.build(pDataset, previous, dates);
			ProjectIndex.CACHE.put(key, built);
			return built;
		}
		finally
		{
			lock.unlock();
		}
	}

	private boolean usable(final long[] pDates, final long pNow)
	{
		return java.util.Arrays.equals(this.dates, pDates) && ExplorerFacts.isFresh(pNow, this.builtAt, ExplorerFacts.MAX_AGE_MS);
	}

	/** Whether a part of this index may be carried over into the next one: its table did not move and this generation is within the age limit. */
	private boolean reusable(final int pTable, final long[] pDates, final long pNow)
	{
		return this.dates[pTable] == pDates[pTable] && ExplorerFacts.isFresh(pNow, this.fullAt, ExplorerFacts.MAX_AGE_MS);
	}

	private static long[] dates(final Adaptation pDataset)
	{
		final long[] out = new long[ProjectIndex.TABLES.length];
		for (int i = 0; i < ProjectIndex.TABLES.length; i++)
		{
			final Date date = pDataset.getTable(ProjectIndex.TABLES[i]).getLastModificationDate();
			out[i] = date == null ? 0L : date.getTime();
		}
		return out;
	}

	private static ProjectIndex build(final Adaptation pDataset, final ProjectIndex pPrevious, final long[] pDates)
	{
		final long start = System.currentTimeMillis();
		final boolean young = pPrevious != null && ExplorerFacts.isFresh(start, pPrevious.fullAt, ExplorerFacts.MAX_AGE_MS);
		final long fullAt = young ? pPrevious.fullAt : start;
		final List<ProjectExplorerPage.Card> bases = young && pPrevious.reusable(ProjectIndex.PROJECT, pDates, start) ? pPrevious.bases : ProjectIndex.readProjects(pDataset);
		final ExplorerFacts facts = new ExplorerFacts();
		final List<ProjectView.Row> sourceRows = new ArrayList<ProjectView.Row>();
		final List<ProjectView.Row> entityRows = new ArrayList<ProjectView.Row>();
		final List<ProjectView.Row> stepRows = new ArrayList<ProjectView.Row>();
		if (young && pPrevious.reusable(ProjectIndex.SOURCE, pDates, start))
		{
			facts.copySourcesFrom(pPrevious.facts);
			sourceRows.addAll(pPrevious.sourceRows);
		}
		else
		{
			ProjectIndex.readSources(pDataset, facts, sourceRows);
		}
		if (young && pPrevious.reusable(ProjectIndex.ENTITY, pDates, start))
		{
			facts.copyEntitiesFrom(pPrevious.facts);
			entityRows.addAll(pPrevious.entityRows);
		}
		else
		{
			ProjectIndex.readEntities(pDataset, facts, entityRows);
		}
		final long maxStepId;
		if (young && pPrevious.reusable(ProjectIndex.STEP, pDates, start))
		{
			facts.copyStepsFrom(pPrevious.facts);
			stepRows.addAll(pPrevious.stepRows);
			maxStepId = pPrevious.maxStepId;
		}
		else if (young && pPrevious.reusable(ProjectIndex.PROJECT, pDates, start) && pPrevious.maxStepId >= 0)
		{
			// only new steps can change a project's latest one: carry the previous ones over, read the rows created since
			facts.copyStepsFrom(pPrevious.facts);
			stepRows.addAll(pPrevious.stepRows);
			maxStepId = ProjectIndex.readSteps(pDataset, facts, stepRows, "./id>" + pPrevious.maxStepId, pPrevious.maxStepId);
		}
		else
		{
			maxStepId = ProjectIndex.readSteps(pDataset, facts, stepRows, null, -1L);
		}
		final long took = System.currentTimeMillis() - start;
		final ProjectIndex built = new ProjectIndex(bases, facts, sourceRows, entityRows, stepRows, pDates, maxStepId, fullAt, took);
		if (took > 2000L)
		{
			LoggingCategory.getKernel().info("[mdm-factory] project explorer index of " + pDataset.getAdaptationName().getStringName() + " built in " + took + " ms ("
				+ built.cards.size() + " projects)");
		}
		return built;
	}

	private static List<ProjectExplorerPage.Card> readProjects(final Adaptation pDataset)
	{
		final List<ProjectExplorerPage.Card> out = new ArrayList<ProjectExplorerPage.Card>();
		final RequestResult rows = pDataset.getTable(FactoryPaths.PROJECT).createRequestResult(null);
		try
		{
			for (Adaptation project = rows.nextAdaptation(); project != null; project = rows.nextAdaptation())
			{
				out.add(ProjectIndex.base(project));
			}
		}
		finally
		{
			rows.close();
		}
		return Collections.unmodifiableList(out);
	}

	/** What the card shows of the project row itself; the facts are added by the constructor. */
	private static ProjectExplorerPage.Card base(final Adaptation pProject)
	{
		final ProjectExplorerPage.Card card = new ProjectExplorerPage.Card();
		card.code = FactoryHtml.text(pProject, FactoryPaths.Project.CODE);
		card.label = FactoryHtml.text(pProject, FactoryPaths.Project.LABEL);
		card.description = FactoryHtml.text(pProject, FactoryPaths.Project.DESCRIPTION);
		card.status = FactoryHtml.text(pProject, FactoryPaths.Project.STATUS);
		card.style = FactoryHtml.text(pProject, FactoryPaths.Project.STYLE);
		card.mode = FactoryHtml.text(pProject, FactoryPaths.Project.MODE);
		card.owner = FactoryHtml.text(pProject, FactoryPaths.Project.OWNER);
		card.createdAt = (Date) pProject.get(FactoryPaths.Project.CREATED_AT);
		card.hasLanding = !FactoryHtml.text(pProject, FactoryPaths.Project.LANDING_DATASPACE).isEmpty();
		card.hasStaging = !FactoryHtml.text(pProject, FactoryPaths.Project.STAGING_DATASPACE).isEmpty();
		card.hasMaster = !FactoryHtml.text(pProject, FactoryPaths.Project.MASTER_DATASPACE).isEmpty();
		card.key = pProject.getOccurrencePrimaryKey().format();
		// the page sorted on data-name, the label in lower case; the index is shared by every locale, so the root locale's
		card.sortName = card.label.toLowerCase(Locale.ROOT);
		return card;
	}

	private static void readSources(final Adaptation pDataset, final ExplorerFacts pFacts, final List<ProjectView.Row> pRows)
	{
		final RequestResult rows = pDataset.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String project = FactoryHtml.text(row, FactoryPaths.ProjectSource.PROJECT);
				final String system = FactoryHtml.text(row, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				pFacts.offerSource(project, system);
				pRows.add(new ProjectView.Row(row.getOccurrencePrimaryKey().format(), project, system, null));
			}
		}
		finally
		{
			rows.close();
		}
	}

	private static void readEntities(final Adaptation pDataset, final ExplorerFacts pFacts, final List<ProjectView.Row> pRows)
	{
		final RequestResult rows = pDataset.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String project = FactoryHtml.text(row, FactoryPaths.StagingEntity.PROJECT);
				pFacts.offerEntity(project);
				pRows.add(new ProjectView.Row(row.getOccurrencePrimaryKey().format(), project, null, null));
			}
		}
		finally
		{
			rows.close();
		}
	}

	/** Streams {@code PipelineStep} (all of it, or the rows of {@code pPredicate}) into the facts; answers the highest id seen, never below {@code pFloor}. */
	private static long readSteps(final Adaptation pDataset, final ExplorerFacts pFacts, final List<ProjectView.Row> pRows, final String pPredicate, final long pFloor)
	{
		long max = pFloor;
		final RequestResult rows = pDataset.getTable(FactoryPaths.PIPELINE_STEP).createRequestResult(pPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String project = FactoryHtml.text(row, FactoryPaths.PipelineStep.PROJECT);
				final String step = FactoryHtml.text(row, FactoryPaths.PipelineStep.STEP);
				final Date startedAt = (Date) row.get(FactoryPaths.PipelineStep.STARTED_AT);
				pFacts.offerStep(project, step, startedAt);
				pRows.add(new ProjectView.Row(row.getOccurrencePrimaryKey().format(), project, step, startedAt));
				final Object id = row.get(FactoryPaths.PipelineStep.ID);
				if (id instanceof Number)
				{
					max = Math.max(max, ((Number) id).longValue());
				}
			}
		}
		finally
		{
			rows.close();
		}
		return max;
	}
}
