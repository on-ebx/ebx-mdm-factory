package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.EngineRefusal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.on.ebx.factory.ebx.MasterLoader;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.workflow.ScriptTaskBean;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * A workflow "script task" bean that transfers staging rows into the master (MDM) zone of an MDM Factory
 * project, by calling {@link MasterLoader#load} directly - the exact same call {@code
 * FactoryResource#loadMaster} already exposes over REST ({@code POST
 * /ebx-mdm-factory/rest/v1/projects/{code}/master/load}). Same discipline as {@link MatchingRunTaskBean}
 * (see its own class Javadoc): no HTTP loopback, and no permission check here - a workflow step's
 * effective caller identity is governed by EBX's own workflow permissions, not by a check duplicated
 * inside the bean.
 * <p>
 * <b>Unlike {@link StagingLoadTaskBean}, {@link MasterLoader#load} CAN report {@code status: "FAILED"} on a
 * normal return</b> ({@code master.MasterLoadOutcome#isTotalFailure} - every row read ended up rejected,
 * not merely "0 written": see that class' own Javadoc for why a legitimately all-skipped-merged run must
 * not be misclassified the same way). The outputs are still set first (same "outputs before the throw"
 * discipline as {@link LandingLoadTaskBean}), then an {@link OperationException} is thrown so the workflow
 * step itself lands {@code inError} instead of silently letting matching run against an empty master zone.
 * An {@link OperationException} {@link MasterLoader#load} itself throws (no entity promoted, the master/
 * staging zone does not exist yet, ...) is let through as-is, same as every other loader bean here.
 */
public final class MasterLoadTaskBean extends ScriptTaskBean
{
	private String projectCode;
	/** Lot M-INC (2026-10-12): {@code changed} or {@code full}; blank takes {@code -Dmdm.factory.masterLoad.defaultScope} (default {@code full}) - see {@link MasterLoader#load(Session, Adaptation, String, String)}. */
	private String scope;
	private String resultJson;
	private String rejected;
	private String written;
	private String status;

	public String getProjectCode()
	{
		return this.projectCode;
	}

	public void setProjectCode(final String pProjectCode)
	{
		this.projectCode = pProjectCode;
	}

	/**
	 * The master load's scope: {@code changed} reads only the staging rows the journal recorded since the last successful load, and falls back to a full read, entity by
	 * entity, whenever the journal cannot stand for one (first load, triggers missing, plan changed, ...). {@code MdmFactoryPipeline} binds it to its {@code masterScope}
	 * variable (default {@code changed}); a published model that predates the property leaves it null, i.e. the instance default.
	 */
	public String getScope()
	{
		return this.scope;
	}

	public void setScope(final String pScope)
	{
		this.scope = pScope;
	}

	/** The {@link MasterLoader#load} result, as JSON text. */
	public String getResultJson()
	{
		return this.resultJson;
	}

	public void setResultJson(final String pResultJson)
	{
		this.resultJson = pResultJson;
	}

	/** {@link MasterLoader#load}'s own {@code rejected} total, as plain text. */
	public String getRejected()
	{
		return this.rejected;
	}

	public void setRejected(final String pRejected)
	{
		this.rejected = pRejected;
	}

	/** {@link MasterLoader#load}'s own {@code written} total, as plain text. */
	public String getWritten()
	{
		return this.written;
	}

	public void setWritten(final String pWritten)
	{
		this.written = pWritten;
	}

	/** {@link MasterLoader#load}'s own {@code status} field ({@code "DONE"} or {@code "FAILED"} - see this class' own Javadoc). */
	public String getStatus()
	{
		return this.status;
	}

	public void setStatus(final String pStatus)
	{
		this.status = pStatus;
	}

	/** Pure, unit-testable: reads a {@code long}-valued field of a loader result as plain text, defaulting to {@code "0"} when absent/not a number. */
	static String countText(final JsonNode pResult, final String pField)
	{
		return String.valueOf(pResult == null ? 0L : pResult.path(pField).asLong(0L));
	}

	/** Pure, unit-testable: true when a loader result's own {@code status} field is exactly {@code "FAILED"}. */
	static boolean isFailed(final JsonNode pResult)
	{
		return pResult != null && "FAILED".equals(pResult.path("status").asText(""));
	}

	@Override
	public void executeScript(final ScriptTaskBeanContext pContext) throws OperationException
	{
		// pipeline-audit lot: the launcher must hold the pipeline role, and the steps are recorded as theirs
		PipelineLauncher.run(pContext, "MasterLoadTaskBean", com.on.ebx.factory.ebx.FactoryJobs.MASTER, this.projectCode, () -> {
			this.executeAuthorized(pContext);
			return null;
		});
	}

	private void executeAuthorized(final ScriptTaskBeanContext pContext) throws OperationException
	{
		if (this.projectCode == null || this.projectCode.trim().isEmpty())
		{
			throw EngineRefusal.coded("invalid.taskNoProject", "MasterLoadTaskBean: 'projectCode' is required", "MasterLoadTaskBean");
		}
		final Repository repository = pContext.getRepository();
		final Session session = pContext.getSession();
		FactoryInstaller.ensure(repository, session);
		final Adaptation dataset = FactoryInstaller.dataset(repository);
		if (dataset == null)
		{
			throw EngineRefusal.coded("missing.taskNoDataset", "MasterLoadTaskBean: the MDM Factory dataset is not installed", "MasterLoadTaskBean");
		}
		final String masterScope = BeanScope.parse("MasterLoadTaskBean", this.scope, com.on.ebx.factory.master.MasterLoadScope.FULL, com.on.ebx.factory.master.MasterLoadScope.CHANGED);
		final ObjectNode result = MasterLoader.load(session, dataset, this.projectCode, masterScope);

		// outputs are set BEFORE a possible throw below - see this class' own Javadoc
		this.resultJson = result.toString();
		this.rejected = MasterLoadTaskBean.countText(result, "rejected");
		this.written = MasterLoadTaskBean.countText(result, "written");
		this.status = result.path("status").asText("DONE");

		if (MasterLoadTaskBean.isFailed(result))
		{
			throw EngineRefusal.coded("unexpected.loadTaskFailed", "MasterLoadTaskBean: the master load failed for project " + this.projectCode + " (" + this.rejected
				+ " of " + MasterLoadTaskBean.countText(result, "read") + " row(s) rejected)", this.projectCode, this.rejected);
		}
	}
}
