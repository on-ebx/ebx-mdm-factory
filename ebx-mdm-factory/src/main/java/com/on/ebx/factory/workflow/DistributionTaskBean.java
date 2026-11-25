package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.EngineRefusal;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.DistributionRunner;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.workflow.ScriptTaskBean;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * Distribution lot 1 (engine, SPEC-distribution.md "On demand / Scheduled"): a workflow "script task" bean
 * sending one target (or every non-REALTIME target of the project when {@code targetId} is blank) through
 * {@link DistributionRunner#sendNow} - the exact same call {@code rest.FactoryResource}'s "send now" route
 * exposes. Declared in {@code module.xml} (library mode, {@code ebx-root-1.0@} prefix on the built-in
 * beans it never uses - see CLAUDE.md sec 7), used by the {@code MdmFactoryDistribution} workflow model
 * ({@code tools/workflow/MdmFactoryDistribution.json}): an optional "Approve the send" user task runs BEFORE
 * this bean, gated on {@code DistributionTarget.approval} - the bean itself has no notion of approval, same
 * separation of concerns as {@code MdmFactoryPipeline}'s own review/no-review branch.
 * <p>
 * Same discipline as {@link MasterLoadTaskBean}/{@link MatchingRunTaskBean}: no HTTP loopback, no permission
 * check here ({@link PipelineLauncher} is the gate), outputs set before any throw.
 * <p>
 * <b>Volume-operations lot 8b (2026-09-28, task 4)</b>: this was the one workflow bean with no resource claim
 * of its own (lot 8a's own {@code docs/VOLUME.md} flagged it: every other bean already claims its resource via
 * {@code PipelineLauncher.run(..., FactoryJobs.XXX, ...)}), so a workflow-launched send could race a
 * wizard/REST-launched send of the SAME project against the same {@code DistributionTarget}/{@code
 * DistributionOutbox} rows. Now claims {@link com.on.ebx.factory.ebx.FactoryJobs#DISTRIBUTION} like its
 * siblings - {@link #executeScript} waits for that resource of the project to be free
 * ({@code FactoryJobs.awaitClaim}, {@link PipelineLauncher}'s own 5-arg overload), holds it for the whole send,
 * and releases it afterward.
 */
public final class DistributionTaskBean extends ScriptTaskBean
{
	private String projectCode;
	private String targetId;
	private String scope;
	private String resultJson;
	private String failed;
	private String status;

	public String getProjectCode()
	{
		return this.projectCode;
	}

	public void setProjectCode(final String pProjectCode)
	{
		this.projectCode = pProjectCode;
	}

	/** Blank (default): every non-REALTIME target of the project. Set: send only that target. */
	public String getTargetId()
	{
		return this.targetId;
	}

	public void setTargetId(final String pTargetId)
	{
		this.targetId = pTargetId;
	}

	/** {@code changed} (default) or {@code all}; blank falls back to each target's own {@code scope} field. */
	public String getScope()
	{
		return this.scope;
	}

	public void setScope(final String pScope)
	{
		this.scope = pScope;
	}

	/** One {@link DistributionRunner#sendNow} result per target sent, as JSON text. */
	public String getResultJson()
	{
		return this.resultJson;
	}

	public void setResultJson(final String pResultJson)
	{
		this.resultJson = pResultJson;
	}

	/** Number of targets whose send failed, as text. */
	public String getFailed()
	{
		return this.failed;
	}

	public void setFailed(final String pFailed)
	{
		this.failed = pFailed;
	}

	public String getStatus()
	{
		return this.status;
	}

	public void setStatus(final String pStatus)
	{
		this.status = pStatus;
	}

	@Override
	public void executeScript(final ScriptTaskBeanContext pContext) throws OperationException
	{
		// volume-operations lot 8b (task 4): claim FactoryJobs.DISTRIBUTION like every sibling bean already
		// does - see this class' own Javadoc.
		PipelineLauncher.run(pContext, "DistributionTaskBean", com.on.ebx.factory.ebx.FactoryJobs.DISTRIBUTION, this.projectCode, () -> {
			this.executeAuthorized(pContext);
			return null;
		});
	}

	private void executeAuthorized(final ScriptTaskBeanContext pContext) throws OperationException
	{
		if (this.projectCode == null || this.projectCode.trim().isEmpty())
		{
			throw EngineRefusal.coded("invalid.taskNoProject", "DistributionTaskBean: 'projectCode' is required", "DistributionTaskBean");
		}
		final Repository repository = pContext.getRepository();
		final Session session = pContext.getSession();
		FactoryInstaller.ensure(repository, session);
		final Adaptation dataset = FactoryInstaller.dataset(repository);
		if (dataset == null)
		{
			throw EngineRefusal.coded("missing.taskNoDataset", "DistributionTaskBean: the MDM Factory dataset is not installed", "DistributionTaskBean");
		}

		final String sendScope = BeanScope.parse("DistributionTaskBean", this.scope, "changed", "all");
		final java.util.List<String> targetIds = new java.util.ArrayList<String>();
		if (this.targetId != null && !this.targetId.trim().isEmpty())
		{
			targetIds.add(this.targetId.trim());
		}
		else
		{
			targetIds.addAll(DistributionTaskBean.nonRealtimeTargets(dataset, this.projectCode));
		}

		final ObjectNode aggregate = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		final ArrayNode results = aggregate.putArray("results");
		int failedCount = 0;
		for (final String targetId : targetIds)
		{
			final ObjectNode entry = results.addObject();
			entry.put("target", targetId);
			try
			{
				entry.set("result", DistributionRunner.sendNow(session, dataset, this.projectCode, targetId, sendScope));
			}
			catch (final OperationException ex)
			{
				failedCount++;
				entry.put("error", ex.getMessage());
			}
		}
		this.resultJson = aggregate.toString();
		this.failed = String.valueOf(failedCount);
		this.status = failedCount == 0 ? "DONE" : "FAILED";
		if (failedCount > 0)
		{
			throw EngineRefusal.coded("unexpected.sendTaskFailed", "DistributionTaskBean: " + failedCount + " of " + targetIds.size() + " target(s) failed to send for project " + this.projectCode, failedCount, targetIds.size(), this.projectCode);
		}
	}

	/** Every {@code ON_DEMAND}/{@code SCHEDULED} target of the project - a REALTIME target is never driven from this bean, only by its own table trigger + the dispatcher. */
	private static java.util.List<String> nonRealtimeTargets(final Adaptation pFactory, final String pProjectCode)
	{
		final java.util.List<String> out = new java.util.ArrayList<String>();
		final RequestResult projectRows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + DistributionTaskBean.quote(pProjectCode));
		final Adaptation project;
		try
		{
			project = projectRows.nextAdaptation();
		}
		finally
		{
			projectRows.close();
		}
		if (project == null)
		{
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final RequestResult rows = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).createRequestResult("./project=" + DistributionTaskBean.quote(projectId)
			+ " and ./mode<>" + DistributionTaskBean.quote(FactoryPaths.DistributionTarget.MODE_REALTIME));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String quote(final String pValue)
	{
		return "'" + (pValue == null ? "" : pValue.replace("'", "''")) + "'";
	}
}
