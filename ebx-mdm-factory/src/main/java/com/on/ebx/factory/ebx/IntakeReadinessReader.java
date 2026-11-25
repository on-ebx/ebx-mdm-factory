package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.DistributionMasterReadiness;
import com.on.ebx.factory.intake.IntakeReadiness;
import com.on.ebx.factory.landing.LandingReadiness;
import com.on.ebx.factory.master.MasterStagingReadiness;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.Session;

/**
 * Automatic intake lot: collects the plain facts {@link IntakeReadiness} judges, reusing the proofs of the distribution arming gate and the master generator
 * ({@code DistributionRunner}'s landing/master evidence, {@code MasterStagingReadiness}, {@code DistributionMasterReadiness}). Reads only. Also keeps the cheap, cached
 * count of rows held back (a predicate count over {@code TransferReject}: refreshed by the engine after a chain and at a re-count, never on the status polling path).
 */
final class IntakeReadinessReader
{
	private static final long HELD_BACK_TTL_MS = 60_000L;
	private static final ConcurrentHashMap<String, long[]> HELD_BACK = new ConcurrentHashMap<String, long[]>();

	private IntakeReadinessReader()
	{
	}

	static IntakeReadiness.Result evaluate(final Session pSession, final Adaptation pFactory, final Adaptation pProject, final List<IntakeSources.Info> pSources)
	{
		final String projectId = pProject.getOccurrencePrimaryKey().format();
		final boolean declares = PipelineWorkflowLauncher.declaresIntake(pFactory.getHome().getRepository(), pSession);
		int notLoaded = 0;
		for (final IntakeSources.Info source : pSources)
		{
			if (!IntakeReadinessReader.sourceLoaded(pFactory, source))
			{
				notLoaded++;
			}
		}
		final boolean stagingLoaded = IntakeReadinessReader.stagingLoaded(pFactory, projectId);
		final boolean masterLoaded = DistributionMasterReadiness.ready(PipelineSteps.isDone(pFactory, projectId, "MASTER_LOAD", ""),
			DistributionRunner.masterZoneHasData(pFactory, projectId));
		return IntakeReadiness.evaluate(declares, pSources.size(), notLoaded, stagingLoaded, masterLoaded, IntakeReadinessReader.heldBack(pFactory, projectId, false));
	}

	/** Every ticked landing table of the source has status LOADED ({@code LandingReadiness}, the proof the Staging step's own gate uses). */
	private static boolean sourceLoaded(final Adaptation pFactory, final IntakeSources.Info pSource)
	{
		final List<LandingReadiness.Table> tables = new ArrayList<LandingReadiness.Table>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.LANDING_TABLE), "./projectSource=" + CatalogReader.quote(pSource.id));
		try
		{
			for (Adaptation table = rows.nextAdaptation(); table != null; table = rows.nextAdaptation())
			{
				final boolean selected = !Boolean.FALSE.equals(UserView.get(table, FactoryPaths.LandingTable.SELECTED));
				tables.add(new LandingReadiness.Table(selected, "LOADED".equals(UserView.string(table, FactoryPaths.LandingTable.STATUS))));
			}
		}
		finally
		{
			rows.close();
		}
		return LandingReadiness.allTickedTablesLoaded(tables);
	}

	/** The STAGING_LOAD step done, or an entity of the project whose last load wrote or found up-to-date at least one row ({@code MasterStagingReadiness}'s two signals). */
	private static boolean stagingLoaded(final Adaptation pFactory, final String pProjectId)
	{
		final List<MasterStagingReadiness.EntityLoad> loads = new ArrayList<MasterStagingReadiness.EntityLoad>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation entity = rows.nextAdaptation(); entity != null; entity = rows.nextAdaptation())
			{
				final Long written = IntakeStore.number(entity, FactoryPaths.StagingEntity.LAST_LOAD_WRITTEN);
				final Long unchanged = IntakeStore.number(entity, FactoryPaths.StagingEntity.LAST_LOAD_UNCHANGED);
				loads.add(new MasterStagingReadiness.EntityLoad(UserView.string(entity, FactoryPaths.StagingEntity.NAME),
					(written == null ? 0L : written.longValue()) + (unchanged == null ? 0L : unchanged.longValue())));
			}
		}
		finally
		{
			rows.close();
		}
		return MasterStagingReadiness.decide(PipelineSteps.isDone(pFactory, pProjectId, "STAGING_LOAD", ""), loads).ready;
	}

	/**
	 * Unresolved staging rejects of the project ({@code not(./resolved='true')}, the predicate the Cockpit's own grid counts with). Cached for a minute: it is a predicate
	 * count over {@code TransferReject}, which has only its primary key as an index. {@code pRefresh} (the engine, after a chain or a re-count) recomputes.
	 */
	static long heldBack(final Adaptation pFactory, final String pProjectId, final boolean pRefresh)
	{
		final long now = System.currentTimeMillis();
		final long[] cached = IntakeReadinessReader.HELD_BACK.get(pProjectId);
		if (!pRefresh && cached != null && now - cached[0] < IntakeReadinessReader.HELD_BACK_TTL_MS)
		{
			return cached[1];
		}
		long count = 0L;
		try
		{
			final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.TRANSFER_REJECT), "./project=" + CatalogReader.quote(pProjectId)
				+ " and ./stage=" + CatalogReader.quote("STAGING") + " and not(./resolved=" + CatalogReader.quote("true") + ")");
			try
			{
				count = rows.getSize();
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			return cached == null ? 0L : cached[1];
		}
		IntakeReadinessReader.HELD_BACK.put(pProjectId, new long[] { now, count });
		return count;
	}

	/** The cached value only (never a query): for the status of a project that is ON. Null when never counted. */
	static Long heldBackCached(final String pProjectId)
	{
		final long[] cached = IntakeReadinessReader.HELD_BACK.get(pProjectId);
		return cached == null ? null : Long.valueOf(cached[1]);
	}
}
