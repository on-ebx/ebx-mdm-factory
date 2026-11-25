package com.on.ebx.factory.ebx;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;

/**
 * Audit F-31/F-32 (2026-10-06): the latest {@code PipelineStep} row of every (step, scope) pair the wizard snapshot shows, read in ONE scan of the
 * table for the project. The snapshot used to run one predicate query per pair (eleven project-wide steps, four per source, one per match plan) and
 * every one of them scans the whole table, which holds the history of every project (only the primary key is indexed, CLAUDE.md section 5).
 * <p>
 * "Latest" is exactly what the per-pair loops computed: the first row met, replaced only by a row whose {@code startedAt} is strictly after the one
 * kept and when both dates are set ({@link Latest#offer}); rows come in table order, as the filtered queries returned them.
 */
final class PipelineStepIndex
{
	/** The latest row per key under {@code WizardData.after}'s rule. Pure, generic so a test needs no EBX row. */
	static final class Latest<T>
	{
		private final Map<String, T> rows = new HashMap<String, T>();
		private final Map<String, Date> started = new HashMap<String, Date>();
		private final Map<String, Integer> counts = new HashMap<String, Integer>();
		private final java.util.Set<String> done = new java.util.HashSet<String>();
		private final java.util.Set<String> running = new java.util.HashSet<String>();

		/** Same as {@link #offer(String, Object, Date)}, and records the row's status for {@link #everDone} / {@link #isRunning} and counts it. */
		void offer(final String pKey, final T pRow, final Date pStartedAt, final String pStatus)
		{
			this.counts.put(pKey, Integer.valueOf(this.count(pKey) + 1));
			if ("DONE".equals(pStatus))
			{
				this.done.add(pKey);
			}
			else if ("RUNNING".equals(pStatus))
			{
				this.running.add(pKey);
			}
			this.offer(pKey, pRow, pStartedAt);
		}

		/** True when some row of the key was DONE - what {@code PipelineSteps.isDone} asks (any DONE row, not only the latest). */
		boolean everDone(final String pKey)
		{
			return this.done.contains(pKey);
		}

		/** True when some row of the key is RUNNING. */
		boolean isRunning(final String pKey)
		{
			return this.running.contains(pKey);
		}

		/** How many rows were offered under the key. */
		int count(final String pKey)
		{
			final Integer n = this.counts.get(pKey);
			return n == null ? 0 : n.intValue();
		}

		int size()
		{
			return this.rows.size();
		}

		void offer(final String pKey, final T pRow, final Date pStartedAt)
		{
			if (!this.rows.containsKey(pKey))
			{
				this.rows.put(pKey, pRow);
				this.started.put(pKey, pStartedAt);
				return;
			}
			final Date kept = this.started.get(pKey);
			if (pStartedAt != null && kept != null && pStartedAt.after(kept))
			{
				this.rows.put(pKey, pRow);
				this.started.put(pKey, pStartedAt);
			}
		}

		T get(final String pKey)
		{
			return this.rows.get(pKey);
		}
	}

	/** Most (step, scope) entries one project's index may hold; past it the index is still returned for the render but never kept. */
	static final int MAX_ENTRIES = 20000;
	/** Entries kept at most (one per project and viewer); a time limit bounds what the table date cannot see. */
	private static final ReadCache<Latest<Adaptation>> CACHE = new ReadCache<Latest<Adaptation>>(32, 10L * 60L * 1000L);
	/** Scans of {@code PipelineStep} made by {@link #read} since start - the test counter (F2-104). */
	static final java.util.concurrent.atomic.AtomicLong SCANS = new java.util.concurrent.atomic.AtomicLong();

	private final Latest<Adaptation> latest;

	private PipelineStepIndex(final Latest<Adaptation> pLatest)
	{
		this.latest = pLatest;
	}

	/** A scan of the table for one project: the loader of {@link #cachedOrLoad}. */
	interface Loader
	{
		Latest<Adaptation> load();
	}

	/**
	 * Audit F-104: the index of a project under {@code pKey} (project, viewer, modification date of the table), loaded once and shared by every render until the
	 * table moves - any step written or ended changes the date, so the entry is simply never asked for again. A null key (date unreadable) or an index over
	 * {@link #MAX_ENTRIES} is not kept. Pure, so a test counts the loads.
	 */
	static Latest<Adaptation> cachedOrLoad(final String pKey, final Loader pLoader)
	{
		if (pKey != null)
		{
			final Latest<Adaptation> kept = PipelineStepIndex.CACHE.get(pKey, System.currentTimeMillis());
			if (kept != null)
			{
				return kept;
			}
		}
		final Latest<Adaptation> loaded = pLoader.load();
		if (pKey != null && loaded.size() <= PipelineStepIndex.MAX_ENTRIES)
		{
			PipelineStepIndex.CACHE.put(pKey, loaded, System.currentTimeMillis());
		}
		return loaded;
	}

	static void clearCache()
	{
		PipelineStepIndex.CACHE.clear();
	}

	/** Pure: the cache key - project, viewer, modification token of {@code PipelineStep}; null when the token could not be read. */
	static String cacheKey(final String pProjectId, final String pViewer, final String pToken)
	{
		return pToken == null ? null : pProjectId + "\u0002" + pViewer + "\u0002" + pToken;
	}

	/** Pure: the map key of a (step, scope) pair; a null scope reads as empty, which is what a project-wide step carries (stored as NULL). */
	static String key(final String pStep, final String pScope)
	{
		return pStep + "\u0001" + (pScope == null ? "" : pScope);
	}

	/**
	 * The index of {@code pProjectId}: ONE streaming read restricted to the project, shared across renders under the table's modification date (audit F-104).
	 * {@code pSteps} only says whether anything is wanted; the index holds every step of the project, looked up by key.
	 */
	static PipelineStepIndex read(final Adaptation pFactory, final String pProjectId, final Collection<String> pSteps)
	{
		if (pProjectId == null || pProjectId.trim().isEmpty() || pSteps.isEmpty())
		{
			return new PipelineStepIndex(new Latest<Adaptation>());
		}
		return PipelineStepIndex.forProject(pFactory, pProjectId);
	}

	/** The cached index of a project - what the wizard snapshot and the grids read. */
	static PipelineStepIndex forProject(final Adaptation pFactory, final String pProjectId)
	{
		final UserView view = UserView.current();
		final com.orchestranetworks.service.Session viewer = view.session();
		final String key = PipelineStepIndex.cacheKey(pProjectId, viewer == null ? "-" : String.valueOf(viewer.getUserReference()),
			StagingLoadGridReader.modificationToken(pFactory.getTable(FactoryPaths.PIPELINE_STEP)));
		return new PipelineStepIndex(PipelineStepIndex.cachedOrLoad(key, new Loader()
		{
			@Override
			public Latest<Adaptation> load()
			{
				return PipelineStepIndex.scan(pFactory, pProjectId, view);
			}
		}));
	}

	private static Latest<Adaptation> scan(final Adaptation pFactory, final String pProjectId, final UserView pView)
	{
		PipelineStepIndex.SCANS.incrementAndGet();
		final Latest<Adaptation> latest = new Latest<Adaptation>();
		// second permissions pass (2026-10-14): inside a user-facing read scope (UserView#scoped) only the rows and fields the user may see
		final RequestResult rows = pView.rows(pFactory.getTable(FactoryPaths.PIPELINE_STEP), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object step = pView.value(row, FactoryPaths.PipelineStep.STEP);
				if (step == null)
				{
					continue;
				}
				final Object startedAt = pView.value(row, FactoryPaths.PipelineStep.STARTED_AT);
				final Object scope = pView.value(row, FactoryPaths.PipelineStep.SCOPE);
				final Object status = pView.value(row, FactoryPaths.PipelineStep.STATUS);
				latest.offer(PipelineStepIndex.key(String.valueOf(step), scope == null ? null : String.valueOf(scope)), row, startedAt instanceof Date ? (Date) startedAt : null,
					status == null ? null : String.valueOf(status));
			}
		}
		finally
		{
			rows.close();
		}
		return latest;
	}

	/** True when some row of {@code pStep} for {@code pScope} (empty = project-wide) is DONE - {@code PipelineSteps.isDone}, from the index. */
	boolean isDone(final String pStep, final String pScope)
	{
		return this.latest.everDone(PipelineStepIndex.key(pStep, pScope));
	}

	/** True when a row of {@code pStep} for {@code pScope} is RUNNING. */
	boolean isRunning(final String pStep, final String pScope)
	{
		return this.latest.isRunning(PipelineStepIndex.key(pStep, pScope));
	}

	/** How many rows exist for {@code pStep} and {@code pScope}. */
	int count(final String pStep, final String pScope)
	{
		return this.latest.count(PipelineStepIndex.key(pStep, pScope));
	}

	/** The latest row of a project-wide step (empty scope) - what {@code PipelineSteps.scopeEquals("./scope", "")} selected. */
	Adaptation projectWide(final String pStep)
	{
		return this.latest.get(PipelineStepIndex.key(pStep, ""));
	}

	/**
	 * The latest row of {@code pStep} for {@code pScope}, as {@code ./scope=<pScope>} selected it: an EMPTY scope matches nothing (EBX stores an
	 * empty string as NULL, so {@code ./scope=''} never matched), unlike {@link #scopedOrWide}.
	 */
	Adaptation scopedExact(final String pStep, final String pScope)
	{
		final String key = PipelineStepIndex.exactKey(pStep, pScope);
		return key == null ? null : this.latest.get(key);
	}

	/** Pure: the map key {@link #scopedExact} looks up - null (nothing can match) for an empty scope. */
	static String exactKey(final String pStep, final String pScope)
	{
		return pScope == null || pScope.isEmpty() ? null : PipelineStepIndex.key(pStep, pScope);
	}

	/** The latest row under {@code PipelineSteps.scopeEquals("./scope", pScope)}: an empty scope selects the project-wide rows. */
	Adaptation scopedOrWide(final String pStep, final String pScope)
	{
		return this.latest.get(PipelineStepIndex.key(pStep, pScope));
	}
}
