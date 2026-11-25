package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.EngineRefusal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.on.ebx.factory.ebx.StagingLoader;
import com.on.ebx.factory.intake.RejectThreshold;
import com.on.ebx.factory.transfer.IncrementalTransfer;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.workflow.ScriptTaskBean;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * A workflow "script task" bean that transfers landing rows into the staging zone of an MDM Factory
 * project, by calling {@link StagingLoader#load} directly - the exact same call {@code
 * FactoryResource#loadStaging} already exposes over REST ({@code POST
 * /ebx-mdm-factory/rest/v1/projects/{code}/staging/load}). Same discipline as {@link MatchingRunTaskBean}
 * (see its own class Javadoc): no HTTP loopback, and no permission check here - a workflow step's
 * effective caller identity is governed by EBX's own workflow permissions, not by a check duplicated
 * inside the bean. Unlike the REST route, this bean runs under the workflow engine's own session, not an
 * administrator's - see {@code docs/TRANSFER-WORKFLOW.md} for the permission model this implies.
 * <p>
 * A row EBX would refuse is caught before the write and turned into a {@code TransferReject} row instead
 * (see {@link StagingLoader}'s own Javadoc, "Rejects never reach staging"), so a normal run with SOME rejects
 * is still {@code DONE}. A run where EVERY row read was rejected ({@code master.MasterLoadOutcome#isTotalFailure},
 * the rule the master load applies) returns {@code status: "FAILED"} on a normal return: the outputs are set
 * first, then an {@link OperationException} is thrown so the workflow step lands {@code inError} instead of
 * letting the pipeline go on over an empty staging zone, exactly as {@link MasterLoadTaskBean} does. A genuine
 * failure ({@code OperationException} - no entity promoted, a project not found, ...) is let through as-is,
 * same as {@link MatchingRunTaskBean} lets {@code MatchingConfigurator#run}'s own exception through.
 * <p>
 * <b>Automatic intake: a total reject is not an error</b> (2026-10-06). For a chain the intake engine launched, the audit's throw above is wrong: one bad row
 * arriving alone would leave the instance in error, the chain FAILED and the intake paused, to be terminated by hand - for the ordinary case the review task exists for.
 * When the optional input {@link #getIntakeChain intakeChain} is non-empty the bean does NOT throw on a total reject: it sets {@code stopped = "true"} (so the pipeline's
 * condition "Too many rows held back?" routes to the stop end node: nothing to carry to master) and the outputs carry the counts as usual; the engine then decides
 * {@code STOPPED} or {@code DONE_WITH_REJECTS} from the counts and the project's own threshold ({@code intake.IntakeChainOutcome}). A pipeline launched by a person keeps the
 * throw, unchanged. {@code intakeChain} is trusted for NOTHING but this routing choice: the token's validity was already proved by the gate ({@code PipelineLauncher} /
 * {@code IntakeGate}) before this work ran, and the bean does not re-check it - a forged value can only turn a person-launched pipeline's error into a stop, never grant anything.
 * <p>
 * <b>{@code scope} (2026-09-23, incremental-transfer lot - {@code transfer.IncrementalTransfer}).</b>
 * Defaults to {@code new} (a blank/unset property, like anything {@link IncrementalTransfer#isAllScope}
 * does not recognise, reads as {@code new}) - the pipeline's ordinary, recurring run only transfers landing
 * rows still at "version received, not yet transferred". {@code tools/workflow/MdmFactoryPipeline.json}
 * wires this to its own {@code stagingScope} data-context variable (default {@code "new"}); a caller that
 * needs a full re-transform (after a mapping/cleaning-step/derived-field change) passes {@code "all"} as an
 * input parameter when launching the instance.
 */
public final class StagingLoadTaskBean extends ScriptTaskBean
{
	private String projectCode;
	private String scope;
	private String resultJson;
	private String rejected;
	private String written;
	private String unchanged;
	private String status;
	private String maxRejectPercent;
	private String stopped;
	private String intakeChain;

	public String getProjectCode()
	{
		return this.projectCode;
	}

	public void setProjectCode(final String pProjectCode)
	{
		this.projectCode = pProjectCode;
	}

	/** {@code new} (default) or {@code all} - see this class' own Javadoc and {@link IncrementalTransfer#isAllScope}. */
	public String getScope()
	{
		return this.scope;
	}

	public void setScope(final String pScope)
	{
		this.scope = pScope;
	}

	/** The {@link StagingLoader#load} result, as JSON text. */
	public String getResultJson()
	{
		return this.resultJson;
	}

	public void setResultJson(final String pResultJson)
	{
		this.resultJson = pResultJson;
	}

	/** {@link StagingLoader#load}'s own {@code rejected} total, as plain text. */
	public String getRejected()
	{
		return this.rejected;
	}

	public void setRejected(final String pRejected)
	{
		this.rejected = pRejected;
	}

	/** {@link StagingLoader#load}'s own {@code written} total, as plain text. */
	public String getWritten()
	{
		return this.written;
	}

	public void setWritten(final String pWritten)
	{
		this.written = pWritten;
	}

	/** {@link StagingLoader#load}'s own {@code unchanged} total, as plain text (2026-09-23, incremental-transfer lot; since lot V3-S, 2026-10-04, also under scope {@code all}: the rows found already up to date). */
	public String getUnchanged()
	{
		return this.unchanged;
	}

	public void setUnchanged(final String pUnchanged)
	{
		this.unchanged = pUnchanged;
	}

	/** {@link StagingLoader#load}'s own {@code status} field ({@code "DONE"}, or {@code "FAILED"} when every row read was rejected - see this class' own Javadoc). */
	public String getStatus()
	{
		return this.status;
	}

	public void setStatus(final String pStatus)
	{
		this.status = pStatus;
	}

	/**
	 * Automatic intake lot (2026-10-05): optional input, a whole number 1-100 as text. When the load held back AT LEAST this percentage of the rows it read
	 * ({@link RejectThreshold#reached}) {@link #getStopped} is {@code "true"} and the pipeline's condition "Too many rows held back?" ends the instance before the master
	 * load. Empty (the default, and what every launch that does not pass it gets): never stops, exactly the behaviour before this lot.
	 */
	public String getMaxRejectPercent()
	{
		return this.maxRejectPercent;
	}

	public void setMaxRejectPercent(final String pMaxRejectPercent)
	{
		this.maxRejectPercent = pMaxRejectPercent;
	}

	/**
	 * Automatic intake (2026-10-06): the token of the chain this step belongs to, empty for a pipeline a person launched. Non-empty only changes how a TOTAL reject
	 * ends: no throw, {@code stopped = "true"} (see the class Javadoc). Never used for anything else.
	 */
	public String getIntakeChain()
	{
		return this.intakeChain;
	}

	public void setIntakeChain(final String pIntakeChain)
	{
		this.intakeChain = pIntakeChain;
	}

	/** Pure: non-empty {@code intakeChain} = an intake chain. */
	static boolean isIntake(final String pIntakeChain)
	{
		return pIntakeChain != null && !pIntakeChain.trim().isEmpty();
	}

	/** Pure: the value of {@code stopped}: the threshold was reached, or - intake chains only - every row read was rejected (nothing to carry to master). */
	static boolean routesToStop(final boolean pReached, final boolean pTotalFailure, final boolean pIntake)
	{
		return pReached || pIntake && pTotalFailure;
	}

	/** Pure: the audit's throw on a total reject applies to a person-launched pipeline only. */
	static boolean mustThrow(final boolean pTotalFailure, final boolean pIntake)
	{
		return pTotalFailure && !pIntake;
	}

	/** {@code "true"} when {@link #getMaxRejectPercent} was reached or, in an intake chain, every row read was rejected; else {@code "false"} (also when no threshold was given). */
	public String getStopped()
	{
		return this.stopped;
	}

	public void setStopped(final String pStopped)
	{
		this.stopped = pStopped;
	}

	/** Pure, unit-testable: whether the load's own {@code read}/{@code rejected} totals reach the threshold text. */
	static boolean reached(final JsonNode pResult, final String pMaxRejectPercent)
	{
		return pResult != null && RejectThreshold.reached(pResult.path("read").asLong(0L), pResult.path("rejected").asLong(0L), RejectThreshold.parse(pMaxRejectPercent));
	}

	/** Pure, unit-testable: reads a {@code long}-valued field of a loader result as plain text, defaulting to {@code "0"} when absent/not a number. */
	static String countText(final JsonNode pResult, final String pField)
	{
		return String.valueOf(pResult == null ? 0L : pResult.path(pField).asLong(0L));
	}

	@Override
	public void executeScript(final ScriptTaskBeanContext pContext) throws OperationException
	{
		// pipeline-audit lot: the launcher must hold the pipeline role, and the steps are recorded as theirs
		PipelineLauncher.run(pContext, "StagingLoadTaskBean", com.on.ebx.factory.ebx.FactoryJobs.STAGING, this.projectCode, () -> {
			this.executeAuthorized(pContext);
			return null;
		});
	}

	private void executeAuthorized(final ScriptTaskBeanContext pContext) throws OperationException
	{
		if (this.projectCode == null || this.projectCode.trim().isEmpty())
		{
			throw EngineRefusal.coded("invalid.taskNoProject", "StagingLoadTaskBean: 'projectCode' is required", "StagingLoadTaskBean");
		}
		final Repository repository = pContext.getRepository();
		final Session session = pContext.getSession();
		FactoryInstaller.ensure(repository, session);
		final Adaptation dataset = FactoryInstaller.dataset(repository);
		if (dataset == null)
		{
			throw EngineRefusal.coded("missing.taskNoDataset", "StagingLoadTaskBean: the MDM Factory dataset is not installed", "StagingLoadTaskBean");
		}
		final boolean allRows = IncrementalTransfer.SCOPE_ALL.equals(BeanScope.parse("StagingLoadTaskBean", this.scope, IncrementalTransfer.SCOPE_NEW, IncrementalTransfer.SCOPE_ALL));
		final ObjectNode result = StagingLoader.load(session, dataset, this.projectCode, allRows);
		this.resultJson = result.toString();
		this.rejected = StagingLoadTaskBean.countText(result, "rejected");
		this.written = StagingLoadTaskBean.countText(result, "written");
		this.unchanged = StagingLoadTaskBean.countText(result, "unchanged");
		this.status = result.path("status").asText("DONE");
		final boolean intake = StagingLoadTaskBean.isIntake(this.intakeChain);
		final boolean totalFailure = StagingLoadTaskBean.isFailed(result);
		this.stopped = String.valueOf(StagingLoadTaskBean.routesToStop(StagingLoadTaskBean.reached(result, this.maxRejectPercent), totalFailure, intake));
		if (StagingLoadTaskBean.mustThrow(totalFailure, intake))
		{
			if (StagingLoadTaskBean.isPartial(result))
			{
				// F2-06: the rows were read and written, the closing writes were not: say that, with the coded message of the load, not "N rows rejected"
				final String refs = StagingLoadTaskBean.countText(result, "referencesNotWritten");
				final String landing = StagingLoadTaskBean.countText(result, "landingStatusNotWritten");
				throw EngineRefusal.coded("unexpected.stagingEndNotWritten", "StagingLoadTaskBean: the staging load of project " + this.projectCode + " could not write "
					+ refs + " deferred reference(s) and " + landing + " landing status row(s): " + result.path("endMessage").asText(""), refs, landing);
			}
			throw EngineRefusal.coded("unexpected.loadTaskFailed", "StagingLoadTaskBean: the staging load failed for project " + this.projectCode + " (" + this.rejected + " of "
				+ StagingLoadTaskBean.countText(result, "read") + " row(s) rejected)", this.projectCode, this.rejected);
		}
	}

	/** Pure, unit-testable: true when a loader result's own {@code status} field is exactly {@code "FAILED"}. */
	static boolean isFailed(final JsonNode pResult)
	{
		return pResult != null && ("FAILED".equals(pResult.path("status").asText("")) || StagingLoadTaskBean.isPartial(pResult));
	}

	/**
	 * F2-06: a load whose closing writes (deferred references, landing status) were refused answers {@code status: PARTIAL}. In a workflow that is a failure of the step: the pipeline must
	 * not go on to the master over staging rows that keep an empty reference.
	 */
	static boolean isPartial(final JsonNode pResult)
	{
		return pResult != null && "PARTIAL".equals(pResult.path("status").asText(""));
	}
}
