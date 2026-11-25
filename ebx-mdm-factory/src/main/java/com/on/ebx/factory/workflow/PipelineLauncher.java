package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.EngineRefusal;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.Actor;
import com.on.ebx.factory.ebx.FactoryAccess;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Role;
import com.orchestranetworks.service.UserReference;
import com.orchestranetworks.service.directory.DirectoryHandler;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * The gate every factory workflow bean passes first (pipeline-audit lot, 2026-09-24). A script task runs with a
 * system-user session that bypasses every permission (EBX Javadoc, {@code ProcessExecutionInfoContext.getSession}),
 * so the publication's own launch permissions are the only thing between a user and platform-wide writes. This adds
 * a check the factory owns: the instance's CREATOR (recorded by the workflow engine, not a data-context variable a
 * launcher could fill in) must hold the MDM Factory right DESIGN (administrators included) or the role of setting
 * {@code pipelineLauncherRole} (audit F-02: before, the role alone, administrators when it was empty - {@code
 * FactoryAccess#userMayOperatePipeline}).
 * The bean's work then runs with that creator as the {@link Actor}, so every {@code PipelineStep} it records says
 * who launched it and through which instance.
 * <p>
 * Automatic-intake lot (2026-10-05): ONE explicit, narrow second path, {@link IntakeGate} - a chain the intake engine launched (system-user session, no person) carries
 * a token and runs as the person who switched the intake on, while that person still holds the role. See that class for who could forge it.
 */
final class PipelineLauncher
{
	private PipelineLauncher()
	{
	}

	static void run(final ScriptTaskBeanContext pContext, final String pBean, final Actor.Work<Void, OperationException> pWork) throws OperationException
	{
		PipelineLauncher.run(pContext, pBean, null, pWork);
	}

	/**
	 * The gate. Automatic-intake lot (2026-10-05): when the data context carries a valid {@code intakeChain} token ({@link IntakeGate}, {@link #intakeDecision}), the
	 * work runs as the person who switched the intake on - provided that person still holds the role; anything else falls through to the creator check, unchanged.
	 */
	static void run(final ScriptTaskBeanContext pContext, final String pBean, final String pProjectCode, final Actor.Work<Void, OperationException> pWork)
		throws OperationException
	{
		final Adaptation dataset = FactoryInstaller.dataset(pContext.getRepository());
		final Adaptation settings = dataset == null ? null
			: dataset.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final String roleName = PipelineLauncher.roleName(settings == null ? null : settings.getString(FactoryPaths.Settings.PIPELINE_LAUNCHER_ROLE));
		if (roleName != null)
		{
			try
			{
				Profile.forSpecificRole(roleName);
			}
			catch (final IllegalArgumentException ex)
			{
				throw EngineRefusal.coded("invalid.launcherRole", pBean + ": the factory setting pipelineLauncherRole '" + roleName + "' is not a valid role name; refused.", roleName);
			}
		}
		final String via = "workflow " + (pContext.getProcessInstanceKey() == null ? "?" : pContext.getProcessInstanceKey().format());
		final String[] token = new String[1];
		final IntakeGate.Decision intake = PipelineLauncher.intakeDecision(pContext, dataset, pProjectCode, token);
		if (intake.allowed)
		{
			Actor.as(intake.actAs, "intake " + token[0] + " \u00B7 " + via, pWork);
			return;
		}
		final UserReference creator = pContext.getProcessInstanceCreator();
		if (creator == null)
		{
			throw EngineRefusal.coded("conflict.noCreator", pBean + ": the workflow instance has no recorded creator; refused.");
		}
		if (!FactoryAccess.userMayOperatePipeline(pContext.getRepository(), creator, dataset))
		{
			throw EngineRefusal.coded("conflict.launcherRight", pBean + ": " + creator.getUserId() + ", who launched this workflow, holds neither the MDM Factory right DESIGN nor"
				+ (roleName == null ? " a pipeline launcher role (setting pipelineLauncherRole is empty)" : " the role " + roleName + " (setting pipelineLauncherRole)")
				+ ", required to run the MDM Factory pipeline; refused.", creator.getUserId());
		}
		Actor.as(creator.getUserId(), via, pWork);
	}

	/** The data-context variable an intake chain carries: the primary key of its {@code IntakeChain} row. */
	static final String INTAKE_VARIABLE = "intakeChain";

	/**
	 * Collects the facts {@link IntakeGate#decide} needs. Never throws: any failure reads as "not an intake chain" (the creator check then decides). The variable is
	 * only read when the instance's data context DECLARES it ({@code DataContextReadOnly.isVariableDefined}, javap-checked): an old publication does not.
	 * <b>Not run live yet.</b>
	 *
	 * @param pTokenOut one-element array receiving the token when it was read
	 */
	private static IntakeGate.Decision intakeDecision(final ScriptTaskBeanContext pContext, final Adaptation pDataset, final String pBeanProjectCode,
		final String[] pTokenOut)
	{
		try
		{
			final com.orchestranetworks.workflow.ProcessInstance instance = pContext.getProcessInstance();
			final com.orchestranetworks.workflow.DataContextReadOnly context = instance == null ? null : instance.getDataContext();
			final boolean defined = context != null && context.isVariableDefined(PipelineLauncher.INTAKE_VARIABLE);
			final String token = defined ? context.getVariableString(PipelineLauncher.INTAKE_VARIABLE) : null;
			pTokenOut[0] = token;
			if (!defined || token == null || token.trim().isEmpty() || pDataset == null)
			{
				return IntakeGate.decide(defined, token, false, null, null, pBeanProjectCode, null, null, false);
			}
			final com.onwbp.adaptation.AdaptationTable chains = pDataset.getTable(FactoryPaths.INTAKE_CHAIN);
			final Adaptation chain = chains.lookupAdaptationByPrimaryKey(chains.computePrimaryKey(new Object[] { token.trim() }));
			if (chain == null)
			{
				return IntakeGate.decide(true, token, false, null, null, pBeanProjectCode, null, null, false);
			}
			final String projectKey = chain.getString(FactoryPaths.IntakeChain.PROJECT);
			final Adaptation project = projectKey == null ? null : pDataset.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectKey));
			if (project == null)
			{
				return IntakeGate.decide(true, token, true, chain.getString(FactoryPaths.IntakeChain.STATUS), null, pBeanProjectCode, null, null, false);
			}
			final String enabledBy = PipelineLauncher.text(project, FactoryPaths.Project.INTAKE_ENABLED_BY);
			final boolean holds = enabledBy != null && !enabledBy.trim().isEmpty()
				&& FactoryAccess.userMayOperatePipeline(pContext.getRepository(), Profile.forUser(enabledBy.trim()), pDataset);
			return IntakeGate.decide(true, token.trim(), true, chain.getString(FactoryPaths.IntakeChain.STATUS), project.getString(FactoryPaths.Project.CODE), pBeanProjectCode,
				PipelineLauncher.text(project, FactoryPaths.Project.INTAKE_STATE), enabledBy, holds);
		}
		catch (final RuntimeException ex)
		{
			return IntakeGate.decide(false, null, false, null, null, pBeanProjectCode, null, null, false);
		}
	}

	/** A guarded string read: a model written before the intake lot has no such field and {@code Adaptation.get} throws on an undeclared path. */
	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		return pRow.getSchemaNode().getNode(pPath) == null ? null : pRow.getString(pPath);
	}

	/**
	 * {@link #run(ScriptTaskBeanContext, String, Actor.Work)} plus the project resource the step writes (lot C1): the step
	 * waits until no factory job holds that resource of the project ({@code FactoryJobs.awaitClaim}, up to the jobs' own
	 * time limit), holds it while it runs, and frees it after - so a pipeline workflow never writes the staging while a
	 * click in the wizard builds it, and never fails just because one does.
	 */
	static void run(final ScriptTaskBeanContext pContext, final String pBean, final String pResource, final String pProjectCode,
		final Actor.Work<Void, OperationException> pWork) throws OperationException
	{
		PipelineLauncher.run(pContext, pBean, pProjectCode, () -> {
			final Adaptation dataset = FactoryInstaller.dataset(pContext.getRepository());
			final String projectId = PipelineLauncher.projectId(dataset, pProjectCode);
			if (projectId == null)
			{
				return pWork.run(); // an unknown project: the bean's own work reports it
			}
			final com.on.ebx.factory.ebx.FactoryJobs.Handle claim = com.on.ebx.factory.ebx.FactoryJobs.awaitClaim(projectId, pResource, pBean,
				Actor.current(pContext.getSession()).userId, PipelineLauncher.WAIT_MS);
			try
			{
				return pWork.run();
			}
			finally
			{
				com.on.ebx.factory.ebx.FactoryJobs.release(claim);
			}
		});
	}

	/** How long a workflow step waits for a busy resource: the jobs' default time limit. */
	static final long WAIT_MS = 120L * 60000L;

	private static String projectId(final Adaptation pDataset, final String pProjectCode)
	{
		if (pDataset == null || pProjectCode == null || pProjectCode.trim().isEmpty())
		{
			return null;
		}
		final com.onwbp.adaptation.RequestResult rows = pDataset.getTable(FactoryPaths.PROJECT).createRequestResult("./code='"
			+ pProjectCode.trim().replace("'", "''") + "'");
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

	/** Pure: the configured role name, or null for "administrators". */
	static String roleName(final String pSetting)
	{
		return pSetting == null || pSetting.trim().isEmpty() ? null : pSetting.trim();
	}
}
