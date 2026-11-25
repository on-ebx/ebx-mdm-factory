package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.AgentReviews;
import com.on.ebx.factory.ebx.EngineRefusal;
import com.on.ebx.factory.ebx.FeatureSwitches;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.workflow.ScriptTaskBean;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * The step after the agent review task ({@code MdmFactoryAgentEscalation}, agentic framework lot 2026-10-30): decides the run's {@code AgentProposal} lines
 * as the reviewer did ({@link AgentReviews#close}). Task accepted: every line still PROPOSED becomes ACCEPTED (the reviewer may first have set some lines to
 * REJECTED in the table the task opens), and the accepted lines of an agent allowed to act are applied with the REVIEWER's EBX permissions. Task rejected:
 * every line still PROPOSED becomes REJECTED. Who decided comes from the work item ({@link AgentEscalationUserTask}), never from a variable a launcher
 * could set: the reviewer must also still hold the agent's owner profile, or nothing is applied.
 */
public final class AgentDecisionTaskBean extends ScriptTaskBean
{
	private String agentRun;
	private String decision;
	private String decidedBy;
	private String resultJson;

	public String getAgentRun()
	{
		return this.agentRun;
	}

	public void setAgentRun(final String pAgentRun)
	{
		this.agentRun = pAgentRun;
	}

	public String getDecision()
	{
		return this.decision;
	}

	public void setDecision(final String pDecision)
	{
		this.decision = pDecision;
	}

	public String getDecidedBy()
	{
		return this.decidedBy;
	}

	public void setDecidedBy(final String pDecidedBy)
	{
		this.decidedBy = pDecidedBy;
	}

	public String getResultJson()
	{
		return this.resultJson;
	}

	public void setResultJson(final String pResultJson)
	{
		this.resultJson = pResultJson;
	}

	@Override
	public void executeScript(final ScriptTaskBeanContext pContext) throws OperationException
	{
		// production review 2026-11-12 (F3-08): closed agents decide and apply nothing; the refusal stops this step (AgentReviews.close would only report each line UNCHANGED)
		FeatureSwitches.requireAgents();
		final Adaptation factory = FactoryInstaller.dataset(pContext.getRepository());
		if (factory == null)
		{
			throw EngineRefusal.coded("missing.taskNoDataset", "AgentDecisionTaskBean: the MDM Factory dataset is not installed", "AgentDecisionTaskBean");
		}
		this.resultJson = AgentReviews.close(pContext.getSession(), factory, this.agentRun, "ACCEPTED".equals(this.decision), this.decidedBy).toString();
	}
}
