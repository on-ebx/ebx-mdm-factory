package com.on.ebx.factory.agent;

/**
 * When an agent wakes (agentic framework lot, 2026-10-30). Pure. {@code AgentDefinition.trigger} is {@code MANUAL}, {@code EVENT} (with {@code triggerEvent})
 * or {@code SCHEDULE} (with {@code scheduleMinutes}). An event or a schedule only wakes an ENABLED agent with someone to act for ({@code enabledBy}); a manual
 * run is the person's own click and needs neither.
 */
public final class AgentTrigger
{
	/** The Factory events an agent can listen to ({@code AgentDefinition.triggerEvent}). */
	public static final String MATCHING_RUN_DONE = "MATCHING_RUN_DONE";
	public static final String MASTER_LOAD_DONE = "MASTER_LOAD_DONE";
	public static final String STAGING_LOAD_DONE = "STAGING_LOAD_DONE";

	/** The shortest interval a schedule accepts: the scheduled task's own tick is no finer, and a model call costs. */
	public static final int MIN_SCHEDULE_MINUTES = 5;

	private AgentTrigger()
	{
	}

	/** Whether an agent with these settings wakes on {@code pEvent}. */
	public static boolean wakesOn(final String pTrigger, final String pTriggerEvent, final boolean pEnabled, final String pEnabledBy, final String pEvent)
	{
		return pEnabled && AgentTrigger.hasText(pEnabledBy) && "EVENT".equals(pTrigger) && pEvent != null && pEvent.equals(pTriggerEvent);
	}

	/**
	 * Whether a scheduled agent is due at {@code pNowMillis}: enabled, someone to act for, and at least its interval (never below {@link #MIN_SCHEDULE_MINUTES})
	 * since its last run ({@code pLastRunMillis} 0 = never ran: due).
	 */
	public static boolean isDue(final String pTrigger, final Integer pScheduleMinutes, final boolean pEnabled, final String pEnabledBy, final long pLastRunMillis,
		final long pNowMillis)
	{
		if (!pEnabled || !AgentTrigger.hasText(pEnabledBy) || !"SCHEDULE".equals(pTrigger) || pScheduleMinutes == null || pScheduleMinutes.intValue() <= 0)
		{
			return false;
		}
		if (pLastRunMillis <= 0L)
		{
			return true;
		}
		final long interval = Math.max(pScheduleMinutes.intValue(), AgentTrigger.MIN_SCHEDULE_MINUTES) * 60000L;
		return pNowMillis - pLastRunMillis >= interval;
	}

	private static boolean hasText(final String pValue)
	{
		return pValue != null && !pValue.trim().isEmpty();
	}
}
