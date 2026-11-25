package com.on.ebx.factory.scheduler;

import com.on.ebx.factory.ebx.AgentRunJob;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.scheduler.ScheduledExecutionContext;
import com.orchestranetworks.scheduler.ScheduledTask;
import com.orchestranetworks.service.OperationException;

/**
 * The tick of the scheduled agents (agentic framework lot, 2026-10-30, {@code docs/AGENTS.md}): every enabled agent with trigger SCHEDULE whose interval has
 * passed since its last run is queued as a background job acting for the person who switched it on ({@code AgentRunJob#tick}). One Task Scheduler task for
 * every agent of the instance, registered like {@link GoldenKeyRefreshScheduledTask} (a {@code /tasks} row, then a schedule; never set {@code userReference}
 * on the row): tick it every 5 minutes, an agent's own {@code scheduleMinutes} decides how often it actually runs. No parameter. Not run live yet.
 */
public final class AgentScheduledTask extends ScheduledTask
{
	@Override
	public void execute(final ScheduledExecutionContext pContext) throws OperationException
	{
		final Adaptation factory = FactoryInstaller.dataset(pContext.getRepository());
		if (factory == null)
		{
			pContext.addExecutionInformation("MDM Factory dataset not installed: no agent to tick");
			return;
		}
		pContext.addExecutionInformation("agents queued: " + AgentRunJob.tick(pContext.getSession(), factory, System.currentTimeMillis()));
	}
}
