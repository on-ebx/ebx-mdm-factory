package com.on.ebx.factory.ebx;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.MasterZoneActivation.Triple;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * The EBX-bound half of the "Record lineage" record-level action's activation cache - what {@code
 * ebx.ui.RecordLineageOnRecordPermissionRule} checks on EVERY record-form/table-view render across the
 * WHOLE repository, so it must be cheap and must never itself read a business record. The actual "is this
 * triple allowed" and "is the cache still fresh" decisions are the PURE {@link MasterZoneActivation} (no
 * EBX class, unit-tested directly) - this class only computes the triple SET (an EBX read) and holds it
 * behind a TTL, deferring both decisions to that pure core.
 * <p>
 * Computed from the factory's own {@code Project} rows: for each project with a generated master zone
 * ({@code Project.masterDataspace}/{@code masterDataset} both set), every entity {@code
 * ProjectMasterEntities.promotedMasterTablePaths} reports (the same {@code
 * MasterModel.buildFromStaging(...).builtEntities} resolution {@code RecordLineageReader.read} uses for one
 * project/entity pair at a time, here run for every project at once) contributes one triple.
 * <p>
 * <b>Extended, 2026-09-22 (toolbar module-level services lot)</b>: the cache is now a {@code Map<Triple,
 * String>} (triple -&gt; owning {@code Project.code}), not a bare {@code Set<Triple>} - same TTL, same
 * invalidation, same fail-soft-on-refresh-failure rule, computed in the exact same {@link #compute} pass
 * (one extra {@code Project.getString(CODE)} read per triple, no extra EBX round trip). {@link
 * #isMasterZoneTable} is unchanged (it only ever needed the key set); {@link #projectCodeOf} is the new
 * question this map answers.
 * <p>
 * <b>Currently has no caller</b> (2026-09-22, later the same day): {@code ebx.ui.MasterZoneProjectResolution}
 * - the one class that asked {@link #projectCodeOf} to resolve a project from a master table's own Cockpit/
 * Proposal review toolbar button - was deleted when the product owner decided those two buttons belong on the
 * factory's own {@code Project} table instead (`docs/MASTER-ZONE.md` &sect;12, {@code master.MasterToolbars}'
 * own Javadoc). Kept anyway, tested (harmless, and the shape is likely useful again for whatever ends up
 * resolving a project from a table selection next), rather than deleted along with its one caller.
 * <p>
 * <b>Corrected, coordinator review, 2026-09-22 (second pass): these reads are NOT permission-filtered, and
 * carry no session at all</b> - {@link #systemSession} is stored (from {@link #init}, called from {@code
 * FactoryModuleRegistration.handleRepositoryStartup} once the factory dataset exists) purely as a "the
 * module has actually started" guard alongside {@link #repository}, checked in {@link #compute} before
 * touching anything. Every EBX call `compute()` makes - {@code Repository.lookupHome}, {@code
 * AdaptationHome.findAdaptationOrNull}, {@code AdaptationTable.createRequestResult}, {@code
 * Adaptation.getString} (via {@code ProposalReviews.projects}/{@code ProjectMasterEntities}) - takes no
 * {@link Session} parameter and applies no access check of any kind; an earlier version of this Javadoc
 * claimed these reads ran "with the module's own SYSTEM session" as if that session gated them the way
 * {@code SessionPermissions.getAdaptationAccessPermission} does elsewhere in this module - that was wrong,
 * caught in review, and is corrected here. This is still safe for the same underlying reason the earlier
 * text gave, just stated accurately: the triples computed here (which dataspace/dataset/table hold a
 * project's master zone) are the MODULE'S OWN configuration, not business data, and this class reads only
 * {@code Project}/{@code StagingEntity}/{@code StagingAttribute} rows of the factory's own {@code
 * MdmFactory} dataset - never a master/landing/staging business record. The actual record/field VALUES the
 * "Record lineage" screen later shows are still gated by {@code RecordLineageReader}'s own per-viewer
 * permission checks (`docs/RECORD-LINEAGE.md` "Authorisation", which DOES pass a real {@link Session} to
 * {@code SessionPermissions}) - this cache only ever decides whether the ACTION is offered, never what it
 * may show once opened, and needs no session to do that.
 * <p>
 * <b>Cache, not a live read</b>: a {@value #TTL_MILLIS}ms TTL (a record-form render is not the place for a
 * {@code Project} table scan on every single display/execution check, {@link MasterZoneActivation#isFresh}
 * decides) plus explicit {@link #invalidate()} from the routes that change a project's master zone ({@code
 * MasterGenerator.generate}, {@code MasterReset.reset}) - called on their own success path so a steward
 * does not wait out the TTL after generating or resetting a master zone. A refresh failure (repository not
 * fully started yet, a genuinely unexpected error reading the factory dataset) NEVER throws and NEVER
 * clears a good cache - it keeps serving the last known-good triples (fail-SOFT here, on the CACHE); {@code
 * RecordLineageOnRecordPermissionRule}'s own final answer is what fails CLOSED (an empty/stale cache only
 * ever means "the action is not offered yet somewhere it should be", never "offered somewhere it should
 * not be" - see that class's own Javadoc, and {@link MasterZoneActivation#matches}'s own fail-closed rule).
 */
public final class MasterZoneIndex
{
	private static final long TTL_MILLIS = 30_000L;

	private static volatile Repository repository;
	/** A "{@link #init} has run" guard, kept alongside {@link #repository} for symmetry with {@code FactoryModuleRegistration}'s own call shape - never passed to any read in {@link #compute} (see the class Javadoc's correction, above: none of those reads take or need a {@link Session}). */
	private static volatile Session systemSession;
	/** Triple -&gt; owning {@code Project.code} (2026-09-22 extension, class Javadoc); {@link #isMasterZoneTable} only ever consults its key set. */
	private static volatile Map<Triple, String> cachedProjectByTriple = Collections.<Triple, String> emptyMap();
	private static volatile long cachedAt = 0L;
	/**
	 * {@code null} when the most recent refresh attempt (whether it found the cache already fresh or actually
	 * ran {@link #compute}) succeeded; {@code <exception class>: <exception message>} otherwise - no record
	 * VALUE ever appears here, only the exception's own class/message (coordinator review, 2026-09-22, "fact
	 * 2": added so a swallowed {@link #compute} failure - candidate 1 of the coordinator's own ranked list - is
	 * no longer invisible; exposed read-only by {@code rest.FactoryResource}'s diagnostics route).
	 */
	private static volatile String lastRefreshError;
	/** Sticky across repeated failures within the SAME streak, so {@link #allowedTriples} logs the failure exactly ONCE per streak (at WARN) rather than once every {@value #TTL_MILLIS}ms for as long as the underlying cause persists - and once more, at INFO, when a streak ends. */
	private static volatile boolean lastRefreshFailed = false;

	private MasterZoneIndex()
	{
	}

	/**
	 * Called once, from {@code FactoryModuleRegistration.handleRepositoryStartup}, AFTER the factory dataset
	 * itself is installed - stores the "module has started" guard ({@link #repository}/{@link
	 * #systemSession}) for every later cache refresh, and forces the first real computation on the next
	 * {@link #isMasterZoneTable} call (rather than serving an empty cache for up to {@value #TTL_MILLIS}ms
	 * after startup). {@code pSystemSession} is accepted (and kept) purely because the caller already has one
	 * to hand at this call site ({@code pContext.createSystemUserSession(...)}, the same session {@code
	 * FactoryInstaller.install}/{@code PerspectiveInstaller.install} also receive there) - it plays no role in
	 * any read {@link #compute} performs.
	 */
	public static void init(final Repository pRepository, final Session pSystemSession)
	{
		MasterZoneIndex.repository = pRepository;
		MasterZoneIndex.systemSession = pSystemSession;
		MasterZoneIndex.invalidate();
	}

	/**
	 * Forces the next {@link #isMasterZoneTable} call to recompute, whatever the TTL says - called after a
	 * project's master zone changes ({@code master/generate}, {@code master/reset}) so a steward sees the
	 * "Record lineage" action appear (or disappear) without waiting out the TTL. Cheap: only resets a
	 * timestamp, never reads anything itself.
	 */
	public static void invalidate()
	{
		MasterZoneIndex.cachedAt = 0L;
	}

	/**
	 * Whether {@code (pDataspaceKey, pDatasetName, pTablePath)} is currently a promoted master table of some
	 * project - the ONE question {@code RecordLineageOnRecordPermissionRule} asks, on every record-form/
	 * table-view render. Never throws (delegates the actual matching to {@link MasterZoneActivation#matches},
	 * itself fail-closed).
	 */
	public static boolean isMasterZoneTable(final String pDataspaceKey, final String pDatasetName, final String pTablePath)
	{
		return MasterZoneActivation.matches(MasterZoneIndex.refreshedProjectByTriple().keySet(), pDataspaceKey, pDatasetName, pTablePath);
	}

	/**
	 * The project code owning {@code (pDataspaceKey, pDatasetName, pTablePath)}, or {@code null} when it is
	 * not currently a promoted master table of any project. Same cache, same TTL, same
	 * fail-soft-on-refresh-failure rule as {@link #isMasterZoneTable}; delegates the actual lookup to the
	 * PURE {@link MasterZoneActivation#projectCodeFor}, fail-CLOSED (never throws, {@code null} on any
	 * ambiguity) the same way {@link MasterZoneActivation#matches} already is. <b>No caller today</b> - class
	 * Javadoc.
	 */
	public static String projectCodeOf(final String pDataspaceKey, final String pDatasetName, final String pTablePath)
	{
		return MasterZoneActivation.projectCodeFor(MasterZoneIndex.refreshedProjectByTriple(), pDataspaceKey, pDatasetName, pTablePath);
	}

	private static Map<Triple, String> refreshedProjectByTriple()
	{
		final long now = System.currentTimeMillis();
		if (MasterZoneActivation.isFresh(MasterZoneIndex.cachedAt, now, MasterZoneIndex.TTL_MILLIS))
		{
			return MasterZoneIndex.cachedProjectByTriple; // fresh enough - the common, cheap case: no read at all
		}
		synchronized (MasterZoneIndex.class)
		{
			if (MasterZoneActivation.isFresh(MasterZoneIndex.cachedAt, System.currentTimeMillis(), MasterZoneIndex.TTL_MILLIS))
			{
				return MasterZoneIndex.cachedProjectByTriple; // another thread refreshed first while this one waited
			}
			try
			{
				MasterZoneIndex.cachedProjectByTriple = MasterZoneIndex.compute();
				if (MasterZoneIndex.lastRefreshFailed)
				{
					// end of a failure streak - one INFO line so the log shows recovery, not just the original warning
					LoggingCategory.getKernel().info("[mdm-factory] MasterZoneIndex refreshed successfully again ("
						+ MasterZoneIndex.cachedProjectByTriple.size() + " promoted master table(s)) after a previous failure.");
				}
				MasterZoneIndex.lastRefreshFailed = false;
				MasterZoneIndex.lastRefreshError = null;
			}
			catch (final RuntimeException ex)
			{
				// fail SOFT here: keep the last known-good triples rather than emptying a working cache over one
				// transient failure (see the class Javadoc's "cache, not a live read" paragraph). But make the
				// failure VISIBLE (coordinator review, 2026-09-22, "fact 2", candidate 1 of the ranked list): a
				// silently, permanently empty/stale cache is otherwise indistinguishable from "working as
				// intended, nothing promoted yet" - class+message only, never a record value, and only ONCE per
				// failure streak (not every TTL window) so a persistent outage does not spam the kernel log.
				MasterZoneIndex.lastRefreshError = ex.getClass().getName() + ": " + ex.getMessage();
				if (!MasterZoneIndex.lastRefreshFailed)
				{
					LoggingCategory.getKernel().warn("[mdm-factory] MasterZoneIndex failed to refresh the 'Record lineage' activation cache ("
						+ MasterZoneIndex.lastRefreshError + ") - the action may be silently missing from every master record until this is fixed; "
						+ "keeping the last known-good set of " + MasterZoneIndex.cachedProjectByTriple.size() + " triple(s). "
						+ "Further failures in this streak are not logged again until it recovers - see GET /diagnostics/master-zone-index.");
				}
				MasterZoneIndex.lastRefreshFailed = true;
			}
			MasterZoneIndex.cachedAt = System.currentTimeMillis(); // back off for one TTL window either way - never hammer Project on every call after a failure
			return MasterZoneIndex.cachedProjectByTriple;
		}
	}

	/** Read-only snapshot for {@code rest.FactoryResource}'s diagnostics route - never triggers a refresh itself beyond the ordinary TTL-bounded one {@link #isMasterZoneTable} already performs (the caller is expected to call that first, as {@link #diagnostics} itself does not accept a triple to check - see {@code FactoryResource}'s own route, which calls {@link #isMasterZoneTable} for the optional query triple and this method for the rest of the payload). */
	public static final class Diagnostics
	{
		public final long cachedAt;
		public final int tripleCount;
		/** Every currently-cached triple, formatted ({@code MasterZoneActivation.Triple#toString}) - small (one row per promoted master table across every project), safe to return in full. */
		public final Set<String> triples;
		/** Null when the last refresh succeeded (or none has run since the last success) - see {@link #lastRefreshError}'s own Javadoc for exactly what it holds. */
		public final String lastRefreshError;

		private Diagnostics(final long pCachedAt, final int pTripleCount, final Set<String> pTriples, final String pLastRefreshError)
		{
			this.cachedAt = pCachedAt;
			this.tripleCount = pTripleCount;
			this.triples = pTriples;
			this.lastRefreshError = pLastRefreshError;
		}
	}

	/**
	 * Snapshot of the cache's current state, for administrators to diagnose in seconds why the "Record
	 * lineage" record-level action is (or is not) offered somewhere, rather than reasoning about {@link
	 * MasterZoneIndex}/{@link MasterZoneActivation} from source alone (coordinator review, 2026-09-22, "fact
	 * 2"). Reads the volatile fields directly - cheap, no EBX call, safe to expose on a route hit at any rate.
	 */
	public static Diagnostics diagnostics()
	{
		final Map<Triple, String> projectByTriple = MasterZoneIndex.cachedProjectByTriple; // one volatile read - a consistent snapshot, not a live re-check
		final Set<String> formatted = new LinkedHashSet<String>();
		for (final Triple triple : projectByTriple.keySet())
		{
			formatted.add(triple.toString());
		}
		return new Diagnostics(MasterZoneIndex.cachedAt, projectByTriple.size(), formatted, MasterZoneIndex.lastRefreshError);
	}

	private static Map<Triple, String> compute()
	{
		final Repository repo = MasterZoneIndex.repository;
		final Session session = MasterZoneIndex.systemSession;
		if (repo == null || session == null)
		{
			return Collections.<Triple, String> emptyMap(); // module still starting up - nothing to offer yet, never an error
		}
		final AdaptationHome factoryHome = repo.lookupHome(HomeKey.forBranchName(FactoryPaths.DATASPACE));
		final Adaptation factory = factoryHome == null ? null : factoryHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DATASET));
		if (factory == null)
		{
			return Collections.<Triple, String> emptyMap(); // factory dataset not installed yet
		}
		final Map<Triple, String> out = new HashMap<Triple, String>();
		for (final Adaptation project : ProposalReviews.projects(factory))
		{
			final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
			final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
			if (MasterZoneIndex.blank(masterDataspaceKey) || MasterZoneIndex.blank(masterDatasetName))
			{
				continue; // no master zone generated yet for this project
			}
			final String projectCode = project.getString(FactoryPaths.Project.CODE);
			final String projectId = project.getOccurrencePrimaryKey().format();
			for (final String tablePath : ProjectMasterEntities.promotedMasterTablePaths(factory, projectId))
			{
				out.put(new Triple(masterDataspaceKey, masterDatasetName, tablePath), projectCode);
			}
		}
		return out;
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
