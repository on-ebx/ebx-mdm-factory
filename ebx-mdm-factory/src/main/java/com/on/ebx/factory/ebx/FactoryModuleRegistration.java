package com.on.ebx.factory.ebx;

import jakarta.servlet.annotation.WebListener;

import com.on.ebx.factory.ebx.ui.RecordLineageOnRecordServiceDeclaration;
import com.orchestranetworks.module.ModuleContextOnRepositoryStartup;
import com.orchestranetworks.module.ModuleRegistrationListener;
import com.orchestranetworks.module.ModuleServiceRegistrationContext;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;

/**
 * Registration of the ebx-mdm-factory module: the MdmFactory dataspace and dataset are created at
 * repository startup. Services on the factory's own tables are registered by the data model itself
 * ({@link FactorySchemaExtensions}) so that they reload with the model. The one exception is {@link
 * RecordLineageOnRecordServiceDeclaration} (coordinator review, 2026-09-22): a record-level action on a
 * MASTER record cannot be a schema extension at all - the master model is DMA-generated, not an XSD this
 * module packages (CLAUDE.md &sect;9) - so it is registered here, at MODULE level, instead. Unlike a
 * schema-extension service, a module-level registration change needs a full Tomcat restart to take effect
 * (CLAUDE.md &sect;9) - this lot was never deployed or seen running.
 * <p>
 * <b>Cockpit/Proposal review module-level services, added then removed the same day (2026-09-22)</b>: a
 * follow-up lot briefly added {@code FactoryCockpitOnMasterServiceDeclaration}/{@code
 * ProposalReviewOnMasterServiceDeclaration} here, for the same reason as {@code
 * RecordLineageOnRecordServiceDeclaration} above (measured live, 15:19-15:22: "No service with key
 * '...@FactoryCockpit'/'...@ProposalReview' could be found" in {@code kernel.log} when those buttons tried to
 * resolve a cross-model schema-extension service from the master model's own toolbar). the product owner then decided,
 * 16:40 the same day, that Cockpit/Proposal review belong on the factory's own {@code Project} table instead
 * of the master tables (`docs/MASTER-ZONE.md` &sect;12, {@code master.MasterToolbars}' own Javadoc) - the two
 * declarations, their user services, and their registration here were removed accordingly.
 */
@WebListener
public final class FactoryModuleRegistration extends ModuleRegistrationListener
{
	/** The Actions submenu that holds this module's services. */
	public static final com.orchestranetworks.service.ServiceGroupKey SERVICE_GROUP = com.orchestranetworks.service.ServiceGroupKey.forServiceGroupInModule("ebx-mdm-factory", "MdmFactory");

	@Override
	public void handleRepositoryStartup(final ModuleContextOnRepositoryStartup pContext) throws OperationException
	{
		super.handleRepositoryStartup(pContext);
		// the kernel log: the module's own logging category is written nowhere on a default installation (seen 2026-09-20)
		FactoryInstaller.install(pContext.getRepository(), pContext.createSystemUserSession("mdm-factory"), pContext.getModuleName(),
			LoggingCategory.getKernel());
		// the record-level "Record lineage" action's own activation cache (MasterZoneIndex) needs the
		// repository to read the factory's own Project/StagingEntity/StagingAttribute rows, regardless of
		// which viewer is rendering a record's action menu - see that class's own Javadoc for why this is safe
		// (the triples it computes are not sensitive; the screen's own read side still enforces per-viewer
		// permissions when the action is actually used) and for a correction, coordinator review, 2026-09-22:
		// none of MasterZoneIndex's reads are permission-filtered or take a session at all - the session passed
		// here is kept only as a "module has started" guard alongside the repository, not used for reads.
		// Called AFTER FactoryInstaller.install above, so the factory dataset already exists by the time the
		// first activation check can run.
		// 1.4.3 (X3-11): say once where the permissions of new generated zones come from
		LoggingCategory.getKernel().info("[mdm-factory] " + ZonePermissions.describe(ZonePermissions.decide(pContext.getRepository(), pContext.getRepository().getReferenceBranch())));
		MasterZoneIndex.init(pContext.getRepository(), pContext.createSystemUserSession("mdm-factory"));
		// perspective installation is independent of the dataset above and must never take startup down with it
		// (e.g. ebx-manager not ready yet on this attempt: PerspectiveInstaller retries on its own background thread)
		try
		{
			PerspectiveInstaller.install(pContext.getRepository(), pContext.createSystemUserSession("mdm-factory"), LoggingCategory.getKernel());
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] perspective installation failed: " + ex, ex);
		}
		// lot C1 (2026-09-24): the background-job engine - its pool, its watchdog, its system session, and every
		// PipelineStep the previous server run left RUNNING marked INTERRUPTED
		try
		{
			final com.orchestranetworks.service.Session jobsSession = pContext.createSystemUserSession("mdm-factory-jobs");
			final com.onwbp.adaptation.Adaptation factoryDataset = FactoryInstaller.dataset(pContext.getRepository());
			FactoryJobs.start(pContext.getRepository(), jobsSession, factoryDataset);
			// the language-model conversations the previous run left RUNNING are as dead as its steps: marked INTERRUPTED (and so purgeable)
			final int interruptedRuns = AgentRunRecovery.interruptOrphans(jobsSession, factoryDataset);
			if (interruptedRuns > 0)
			{
				LoggingCategory.getKernel().info("[mdm-factory] " + interruptedRuns + " agent run(s) left RUNNING by the previous server run marked INTERRUPTED.");
			}
			// the durable queue in front of the engine: kinds first, then RUNNING jobs -> INTERRUPTED and QUEUED ones resume
			FactoryJobKinds.registerAll();
			FactoryJobQueue.start(jobsSession, factoryDataset);
			// lot C4: the daily history purge (FactoryRetention's own Javadoc says what is always kept)
			FactoryRetention.start(jobsSession, factoryDataset);
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] background-job engine could not start: " + ex, ex);
		}
		// wave 11 (audit F-26): is the versioned contract of ebx-connectors offered, and which version? One line in kernel.log, once (a Connectors that registers it later
		// is logged when first seen); never throws. The way the calls go is ebx.factory.connectors.via (docs/OPERATIONS.md).
		ConnectorsContractClient.status();
		// audit F-16: one minted-and-discarded session token proves that the internal EBX token API still works on this build; never throws, no MCP server is called
		SessionToken.startupCheck(pContext.getRepository(), pContext.createSystemUserSession("mdm-factory-token-check"));
		// a pause the factory applied for a load the previous server run never let resume it (a REALTIME distribution target left PAUSED, the matching of a master
		// table left paused) is lifted by a thread of its own: the match-merge server and Connectors are not up yet at this point. Only the pauses recorded in the
		// LoadPause ledger are touched, never one a person set.
		try
		{
			LoadPauseRecovery.start(pContext.getRepository(), pContext.createSystemUserSession("mdm-factory-pause-recovery"));
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] the recovery of the pauses left by a load that never ended could not start: " + ex, ex);
		}
		// Distribution lot 1 (engine, SPEC-distribution.md "Real time"): one module-owned daemon thread draining
		// PENDING DistributionEvent rows, for the module's whole life - stopped in handleRepositoryShutdown below,
		// never left pinning a stopped webapp classloader (CLAUDE.md sec 10).
		try
		{
			DistributionDispatcher.start(pContext.getRepository(), pContext.createSystemUserSession("mdm-factory-distribution"), LoggingCategory.getKernel());
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] distribution dispatcher could not start: " + ex, ex);
		}
		// Automatic-intake lot (2026-10-05, docs/AUTOMATIC-INTAKE.md): the intake engine - one daemon thread with its own system session; a failure to
		// start must never take module startup down, and the engine starts with a re-count of every ON project's new rows
		try
		{
			IntakeEngine.start(pContext.getRepository(), pContext.createSystemUserSession("mdm-factory-intake"), LoggingCategory.getKernel());
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] intake engine could not start: " + ex, ex);
		}
		// Volume-operations lot 8b (2026-09-28, task 1): rebuild every project's DistributionOutbox.Control row
		// from the live, authoritative DistributionTarget state - the self-heal for a server upgraded to a build
		// carrying this lot (or restarted after a gap where a write path failed to keep a row in step). See
		// DistributionControlRow's own Javadoc. Best-effort per project internally; wrapped here too so a
		// startup-time repository hiccup never blocks the rest of this method.
		try
		{
			DistributionControlRow.recomputeAll(pContext.createSystemUserSession("mdm-factory-distribution-control"), FactoryInstaller.dataset(pContext.getRepository()));
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not recompute the distribution control rows at startup: " + ex, ex);
		}
	}

	@Override
	public void handleServiceRegistration(final ModuleServiceRegistrationContext pContext)
	{
		super.handleServiceRegistration(pContext);
		// one submenu per module in the Actions menus of business tables, registered before the services that use it
		final com.onwbp.base.text.UserMessageString groupLabel = new com.onwbp.base.text.UserMessageString();
		groupLabel.setString(java.util.Locale.FRENCH, "MDM Factory");
		groupLabel.setStringForDefault("MDM Factory");
		final com.onwbp.base.text.UserMessageString groupDescription = new com.onwbp.base.text.UserMessageString();
		groupDescription.setString(java.util.Locale.FRENCH, "Lignage et services de la MDM Factory.");
		groupDescription.setStringForDefault("Lineage and services of the MDM Factory.");
		pContext.registerServiceGroup(SERVICE_GROUP, groupLabel, groupDescription);
		// the EBX repository is NOT yet initialised at this point (Repository.getDefault() would throw) - only
		// register the declaration here, never read any data; RecordLineageOnRecordPermissionRule's own cache
		// (MasterZoneIndex) is what actually reads the factory's data, later, lazily, once the repository has
		// started (see handleRepositoryStartup above and MasterZoneIndex's own Javadoc).
		pContext.registerUserService(new RecordLineageOnRecordServiceDeclaration(pContext.getModuleName()));
	}

	/**
	 * Audit F-52: the module may be unregistered (a context reload) without the repository shutting down, and then {@link #handleRepositoryShutdown}
	 * is never called: the pools and threads would keep running on a stopped class loader (CLAUDE.md sec 10). Every stop below is idempotent, so
	 * running them again when the repository shuts down after this changes nothing.
	 */
	@Override
	public void destroyBeforeUnregisterModule()
	{
		this.stopModuleThreads();
		super.destroyBeforeUnregisterModule();
	}

	@Override
	public void handleRepositoryShutdown()
	{
		this.stopModuleThreads();
		super.handleRepositoryShutdown();
	}

	/**
	 * Stops the module's threads; one stop never prevents the next. EBX calls handleRepositoryShutdown after the web
	 * application is stopped, when a class the module never loaded can no longer be loaded (seen at the stop of
	 * 2026-10-06 08:07 with ConnectorsBridge): that only means what the class owns was never started, so a
	 * LinkageError is logged as "nothing to stop". No helper class or lambda here: those would need loading too.
	 */
	private void stopModuleThreads()
	{
		// lot C1: ask running jobs to stop and shut the pool down before the classloader goes away
		try
		{
			FactoryRetention.stop();
			FactoryJobQueue.stop();
			FactoryJobs.stop();
		}
		catch (final LinkageError ex)
		{
			LoggingCategory.getKernel().info("[mdm-factory] background-job engine: nothing to stop (" + ex + ").");
		}
		catch (final Throwable ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] background-job engine could not stop cleanly: " + ex, ex);
		}
		try
		{
			LoadPauseRecovery.stop();
		}
		catch (final LinkageError ex)
		{
			LoggingCategory.getKernel().info("[mdm-factory] pause recovery: nothing to stop (" + ex + ").");
		}
		catch (final Throwable ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] pause recovery could not stop cleanly: " + ex, ex);
		}
		// Distribution lot 1: stop the dispatcher thread before the classloader that started it goes away.
		try
		{
			DistributionDispatcher.stop();
		}
		catch (final LinkageError ex)
		{
			LoggingCategory.getKernel().info("[mdm-factory] distribution dispatcher: nothing to stop (" + ex + ").");
		}
		catch (final Throwable ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] distribution dispatcher could not stop cleanly: " + ex, ex);
		}
		// Automatic-intake lot: stop the engine thread before its class loader goes away
		try
		{
			IntakeEngine.stop();
		}
		catch (final LinkageError ex)
		{
			LoggingCategory.getKernel().info("[mdm-factory] intake engine: nothing to stop (" + ex + ").");
		}
		catch (final Throwable ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] intake engine could not stop cleanly: " + ex, ex);
		}
		// lot L2: the threads of the parallel landing loads (mdm-factory-landing-N) stop with the module
		try
		{
			LandingLoadPool.stopAll();
		}
		catch (final LinkageError ex)
		{
			LoggingCategory.getKernel().info("[mdm-factory] landing load pools: nothing to stop (never loaded in this run).");
		}
		catch (final Throwable ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] landing load pools could not stop cleanly: " + ex, ex);
		}
		try
		{
			ConnectorsBridge.stop();
		}
		catch (final LinkageError ex)
		{
			LoggingCategory.getKernel().info("[mdm-factory] connectors bridge pool: nothing to stop (never loaded in this run).");
		}
		catch (final Throwable ex)
		{
			LoggingCategory.getKernel().error("[mdm-factory] connectors bridge pool could not stop cleanly: " + ex, ex);
		}
	}
}
