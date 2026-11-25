package com.on.ebx.factory.agent;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link AgentTrigger} (agentic framework lot, 2026-10-30). */
public final class AgentTriggerTest
{
	private static final long MINUTE = 60000L;

	public static void main(final String[] pArgs)
	{
		AgentTriggerTest.checkEvents();
		AgentTriggerTest.checkSchedule();
		System.out.println("AgentTriggerTest OK");
	}

	private static void checkEvents()
	{
		AgentTriggerTest.check("wakes on its event", AgentTrigger.wakesOn("EVENT", AgentTrigger.MATCHING_RUN_DONE, true, "alice", AgentTrigger.MATCHING_RUN_DONE));
		AgentTriggerTest.check("not on another event", !AgentTrigger.wakesOn("EVENT", AgentTrigger.MATCHING_RUN_DONE, true, "alice", AgentTrigger.MASTER_LOAD_DONE));
		AgentTriggerTest.check("not when disabled", !AgentTrigger.wakesOn("EVENT", AgentTrigger.MATCHING_RUN_DONE, false, "alice", AgentTrigger.MATCHING_RUN_DONE));
		AgentTriggerTest.check("not without someone to act for", !AgentTrigger.wakesOn("EVENT", AgentTrigger.MATCHING_RUN_DONE, true, " ", AgentTrigger.MATCHING_RUN_DONE));
		AgentTriggerTest.check("not a manual agent", !AgentTrigger.wakesOn("MANUAL", AgentTrigger.MATCHING_RUN_DONE, true, "alice", AgentTrigger.MATCHING_RUN_DONE));
		AgentTriggerTest.check("not a null event", !AgentTrigger.wakesOn("EVENT", null, true, "alice", null));
	}

	private static void checkSchedule()
	{
		final long now = 1000L * AgentTriggerTest.MINUTE;
		AgentTriggerTest.check("never ran: due", AgentTrigger.isDue("SCHEDULE", 60, true, "alice", 0L, now));
		AgentTriggerTest.check("interval passed: due", AgentTrigger.isDue("SCHEDULE", 60, true, "alice", now - 60 * AgentTriggerTest.MINUTE, now));
		AgentTriggerTest.check("interval not passed", !AgentTrigger.isDue("SCHEDULE", 60, true, "alice", now - 59 * AgentTriggerTest.MINUTE, now));
		AgentTriggerTest.check("floor of 5 minutes", !AgentTrigger.isDue("SCHEDULE", 1, true, "alice", now - 4 * AgentTriggerTest.MINUTE, now));
		AgentTriggerTest.check("floor reached", AgentTrigger.isDue("SCHEDULE", 1, true, "alice", now - 5 * AgentTriggerTest.MINUTE, now));
		AgentTriggerTest.check("disabled never due", !AgentTrigger.isDue("SCHEDULE", 60, false, "alice", 0L, now));
		AgentTriggerTest.check("no actor never due", !AgentTrigger.isDue("SCHEDULE", 60, true, null, 0L, now));
		AgentTriggerTest.check("no interval never due", !AgentTrigger.isDue("SCHEDULE", null, true, "alice", 0L, now) && !AgentTrigger.isDue("SCHEDULE", 0, true, "alice", 0L, now));
		AgentTriggerTest.check("event agent never due", !AgentTrigger.isDue("EVENT", 60, true, "alice", 0L, now));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
