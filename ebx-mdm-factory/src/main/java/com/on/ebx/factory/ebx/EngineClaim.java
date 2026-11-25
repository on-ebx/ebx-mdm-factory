package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * The project resource an engine entry point works on, held for the length of the call (audit F-15). The job queue claims the resource of
 * every queued job ({@link FactoryJobs#claim}), but a synchronous route ({@code ?sync=true}, the REST routes of the language-model passes, a wizard
 * action that calls an engine directly) used to reach the same engine without claiming anything, so two runs could write one project at once.
 * Every such engine now enters through {@link #enter}: a free resource is claimed and released in the caller's {@code finally}; a resource the
 * CALLING THREAD already holds (the queued job's own body, a workflow step that waited for it, an interactive claim) is simply re-entered - the
 * job body must never deadlock on its own claim; a resource held by anything else refuses the call with {@link FactoryJobs#busyMessage}, which a
 * REST route turns into a 409.
 * <p>
 * Single node, like the registry it uses (see {@link FactoryJobs}).
 */
final class EngineClaim
{
	/** What an entry point does about a resource, given who holds it. */
	enum Decision
	{
		/** Nobody holds it: claim it. */
		ACQUIRE,
		/** The calling thread already holds it: the call is part of the claimed work. */
		REENTER,
		/** Another run holds it. */
		REFUSE
	}

	/** A claim that holds nothing: the call re-entered a claim of its own thread, or the project does not exist (the engine reports that itself). */
	private static final EngineClaim NOTHING = new EngineClaim(new ArrayList<FactoryJobs.Handle>());

	private final List<FactoryJobs.Handle> handles;

	private EngineClaim(final List<FactoryJobs.Handle> pHandles)
	{
		this.handles = pHandles;
	}

	/** A claim that holds nothing. */
	static EngineClaim none()
	{
		return EngineClaim.NOTHING;
	}

	/** Pure: what to do about a resource whose live claim is {@code pLive} (null when free), for a call made on {@code pCurrent}. */
	static Decision decide(final FactoryJobs.Handle pLive, final Thread pCurrent)
	{
		if (pLive == null)
		{
			return Decision.ACQUIRE;
		}
		return pLive.ownedBy(pCurrent) ? Decision.REENTER : Decision.REFUSE;
	}

	/**
	 * Enters the project of code {@code pProjectCode} for the engine. An unknown project claims nothing - the engine's own "project not found" follows.
	 *
	 * @param pKind what the call is, as {@link FactoryJobs#busyMessage} tells whoever is refused; never a kind the job queue registers (a direct call
	 *            is not a queued job, and {@link FactoryJobs#isLive} reads kinds)
	 */
	static EngineClaim enter(final Adaptation pFactory, final Session pSession, final String pProjectCode, final String pKind, final String... pResources)
		throws OperationException
	{
		final String projectId = EngineClaim.projectId(pFactory, pProjectCode);
		if (projectId == null)
		{
			return EngineClaim.NOTHING;
		}
		return EngineClaim.enterProject(projectId, Actor.current(pSession).userId, pKind, pResources);
	}

	/** {@link #enter} for a project id already known: every resource or none (a claim made so far is given back when a later one is refused). */
	static EngineClaim enterProject(final String pProjectId, final String pLauncher, final String pKind, final String... pResources) throws OperationException
	{
		final Thread current = Thread.currentThread();
		final List<FactoryJobs.Handle> taken = new ArrayList<FactoryJobs.Handle>();
		for (final String resource : pResources)
		{
			final FactoryJobs.Handle live = FactoryJobs.live(pProjectId, resource);
			final Decision decision = EngineClaim.decide(live, current);
			if (decision == Decision.REENTER)
			{
				continue;
			}
			final FactoryJobs.Handle handle = decision == Decision.ACQUIRE ? FactoryJobs.claim(pProjectId, resource, pKind, pLauncher) : null;
			if (handle == null)
			{
				EngineClaim.releaseAll(taken);
				final FactoryJobs.Handle holder = FactoryJobs.live(pProjectId, resource);
				throw holder == null ? EngineRefusal.justStarted() : EngineRefusal.busy(holder);
			}
			taken.add(handle);
		}
		return taken.isEmpty() ? EngineClaim.NOTHING : new EngineClaim(taken);
	}

	/**
	 * Pure: the project resources a set of constraint suggestions writes, by the zone of each ("MASTER" writes the master model, anything else the staging model),
	 * in a fixed order so two callers claiming both never wait on each other crosswise.
	 */
	static String[] resourcesOfZones(final java.util.Collection<String> pZones)
	{
		boolean staging = false;
		boolean master = false;
		for (final String zone : pZones)
		{
			if ("MASTER".equals(zone))
			{
				master = true;
			}
			else
			{
				staging = true;
			}
		}
		final List<String> out = new ArrayList<String>();
		if (staging)
		{
			out.add(FactoryJobs.STAGING);
		}
		if (master)
		{
			out.add(FactoryJobs.MASTER);
		}
		return out.toArray(new String[0]);
	}

	/** Gives back what this call claimed, and nothing else: call it in a {@code finally}. */
	void release()
	{
		EngineClaim.releaseAll(this.handles);
	}

	private static void releaseAll(final List<FactoryJobs.Handle> pHandles)
	{
		for (final FactoryJobs.Handle handle : pHandles)
		{
			FactoryJobs.release(handle);
		}
	}

	private static String projectId(final Adaptation pFactory, final String pProjectCode)
	{
		if (pFactory == null || pProjectCode == null || pProjectCode.trim().isEmpty())
		{
			return null;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			final Adaptation project = rows.nextAdaptation();
			return project == null ? null : project.getOccurrencePrimaryKey().format();
		}
		finally
		{
			rows.close();
		}
	}
}
