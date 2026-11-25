package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.EngineRefusal;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.on.ebx.factory.ebx.MatchingConfigurator;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.workflow.ScriptTaskBean;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * A workflow "script task" bean that launches a real Match and Merge run for an MDM Factory project, by
 * calling {@link MatchingConfigurator#run} directly - the exact same call {@code
 * FactoryResource#runMatching} already exposes over REST ({@code POST
 * /ebx-mdm-factory/rest/v1/projects/{code}/matching/run}). This bean exists so the SAME run can also be
 * launched through EBX's own Workflow engine, which unlocks three things a REST-only launch cannot: the
 * Workflow REST API ({@code POST /ebx-dataservices/rest/workflow/v1/publications/{name}/workflows}, even
 * for a one-step workflow), the Task Scheduler's built-in {@code SchedulerWorkflowLaunchTask}
 * ({@code workflowLauncherName} parameter), and an EBX Script trigger (which cannot itself launch a
 * workflow, but CAN launch a workflow publication that runs this bean as its single step -
 * {@code CLAUDE.md} &sect;2, "taskScheduler" bullet).
 * <p>
 * <b>No HTTP, no loopback.</b> Like {@link MatchingConfigurator} itself and, in the same discipline,
 * {@code com.on.ebx.factory.ebx.MameMatchingApi}'s own class Javadoc ("Replaces the earlier REST-loopback
 * design entirely... No HTTP call, no agent-account credentials on this path any more"), this bean never
 * issues an HTTP call back to the module's own REST endpoint. It runs in-process, under the workflow
 * engine's own {@link Session} and {@link Repository} (obtained from the {@link ScriptTaskBeanContext}),
 * and calls the module's own matching logic directly.
 * <p>
 * <b>No permission check here, on purpose.</b> Unlike {@code FactoryResource#runMatching} (which refuses
 * any caller not in the {@code ADMINISTRATOR} profile), this bean does not duplicate an authorization
 * check: a workflow step's effective caller identity is governed by EBX's own workflow permissions - who
 * may launch or allocate the workflow publication this bean is wired into - not by a check repeated inside
 * the bean itself. Restricting who can launch that publication is the responsibility of whoever builds the
 * workflow model, not of this class.
 */
public final class MatchingRunTaskBean extends ScriptTaskBean
{
	private String projectCode;
	private String scope;
	private String maxWaitMs;
	private String resultJson;

	public String getProjectCode()
	{
		return this.projectCode;
	}

	public void setProjectCode(final String pProjectCode)
	{
		this.projectCode = pProjectCode;
	}

	public String getScope()
	{
		return this.scope;
	}

	public void setScope(final String pScope)
	{
		this.scope = pScope;
	}

	public String getMaxWaitMs()
	{
		return this.maxWaitMs;
	}

	public void setMaxWaitMs(final String pMaxWaitMs)
	{
		this.maxWaitMs = pMaxWaitMs;
	}

	/**
	 * The {@link MatchingConfigurator#run} result, as JSON text - set once {@link #executeScript} returns
	 * successfully. Follows the same "getter+setter pair on a plain bean property" convention observed (via
	 * {@code javap}) on the built-in {@code ScriptTaskGetRecordValue.value} field, meant to be read by the
	 * workflow engine after the step runs and bound to a data-context variable through the workflow model's
	 * own output mapping - exact wiring left to whoever builds that model, see {@code docs/MATCHING.md}.
	 */
	public String getResultJson()
	{
		return this.resultJson;
	}

	public void setResultJson(final String pResultJson)
	{
		this.resultJson = pResultJson;
	}

	/**
	 * Pure, unit-testable: mirrors {@code FactoryResource#runMatching}'s own {@code
	 * "all".equalsIgnoreCase(pScope)} logic exactly - {@code null}/blank defaults to incremental (the same
	 * default the REST route's {@code @DefaultValue("incremental")} applies). Since 2026-10-12 any other word than
	 * {@code incremental}/{@code all} is refused with an {@link IllegalArgumentException} (the REST route answers 400), where it used to read as
	 * incremental; {@link #executeAuthorized} turns that into a coded refusal.
	 */
	static boolean isScopeAll(final String pScope)
	{
		return "all".equals(com.on.ebx.factory.core.ScopeParam.parse(pScope, "incremental", "all"));
	}

	/**
	 * Pure, unit-testable: parses {@code pMaxWaitMs} to a {@code long}, falling back to {@link
	 * MatchingConfigurator#DEFAULT_MAX_WAIT_MS} when the value is {@code null}, blank, or not a valid
	 * number - mirroring the REST route's own {@code pMaxWaitMs == null ? DEFAULT_MAX_WAIT_MS : ...}
	 * fallback, extended to cover a blank/unparsable bean-property string (a workflow {@code beanParams}
	 * value is always a string, never a nullable boxed {@code Long} the way a REST query parameter is).
	 */
	static long parseMaxWaitMs(final String pMaxWaitMs)
	{
		if (pMaxWaitMs == null)
		{
			return MatchingConfigurator.DEFAULT_MAX_WAIT_MS;
		}
		final String trimmed = pMaxWaitMs.trim();
		if (trimmed.isEmpty())
		{
			return MatchingConfigurator.DEFAULT_MAX_WAIT_MS;
		}
		try
		{
			return Long.parseLong(trimmed);
		}
		catch (final NumberFormatException ex)
		{
			return MatchingConfigurator.DEFAULT_MAX_WAIT_MS;
		}
	}

	/**
	 * Reproduces {@code FactoryResource#runMatching}'s own body, using the workflow step's own {@link
	 * Repository}/{@link Session} in place of the REST resource's session context - no HTTP, no loopback
	 * (see this class' own Javadoc). {@link MatchingConfigurator#run}'s own {@link OperationException} is
	 * let through as-is, never wrapped or swallowed.
	 */
	@Override
	public void executeScript(final ScriptTaskBeanContext pContext) throws OperationException
	{
		// pipeline-audit lot: the launcher must hold the pipeline role, and the steps are recorded as theirs
		PipelineLauncher.run(pContext, "MatchingRunTaskBean", com.on.ebx.factory.ebx.FactoryJobs.MATCHING, this.projectCode, () -> {
			this.executeAuthorized(pContext);
			return null;
		});
	}

	private void executeAuthorized(final ScriptTaskBeanContext pContext) throws OperationException
	{
		if (this.projectCode == null || this.projectCode.trim().isEmpty())
		{
			throw EngineRefusal.coded("invalid.taskNoProject", "MatchingRunTaskBean: 'projectCode' is required", "MatchingRunTaskBean");
		}
		final Repository repository = pContext.getRepository();
		final Session session = pContext.getSession();
		FactoryInstaller.ensure(repository, session);
		final Adaptation dataset = FactoryInstaller.dataset(repository);
		if (dataset == null)
		{
			throw EngineRefusal.coded("missing.taskNoDataset", "MatchingRunTaskBean: the MDM Factory dataset is not installed", "MatchingRunTaskBean");
		}
		final boolean scopeAll = "all".equals(BeanScope.parse("MatchingRunTaskBean", this.scope, "incremental", "all"));
		final long maxWaitMsValue = MatchingRunTaskBean.parseMaxWaitMs(this.maxWaitMs);
		this.resultJson = MatchingConfigurator.run(session, dataset, this.projectCode, scopeAll, maxWaitMsValue).toString();
	}
}
