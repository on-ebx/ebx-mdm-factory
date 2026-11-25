package com.on.ebx.factory.ebx;

/** JUnit-free: run the main, a failure throws. What an agent run left RUNNING by a server stop reads after the recovery. */
public final class AgentRunRecoveryTest
{
	public static void main(final String[] pArgs)
	{
		AgentRunRecoveryTest.check("status", "INTERRUPTED".equals(AgentRunRecovery.STATUS_INTERRUPTED));
		final String bare = AgentRunRecovery.interruptedOutcome(null, 8000);
		AgentRunRecoveryTest.check("a run with no outcome: " + bare, bare.startsWith("Interrupted:") && bare.contains("restarted"));
		AgentRunRecoveryTest.check("blank is no outcome", AgentRunRecovery.interruptedOutcome("   ", 8000).equals(bare));
		final String kept = AgentRunRecovery.interruptedOutcome("partial answer", 8000);
		AgentRunRecoveryTest.check("what the row already said is kept: " + kept, kept.startsWith(bare) && kept.endsWith("partial answer"));
		final StringBuilder long8000 = new StringBuilder();
		for (int i = 0; i < 9000; i++)
		{
			long8000.append('z');
		}
		AgentRunRecoveryTest.check("never longer than the field", AgentRunRecovery.interruptedOutcome(long8000.toString(), 8000).length() <= 8000);
		System.out.println("AgentRunRecoveryTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
