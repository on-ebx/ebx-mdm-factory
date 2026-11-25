package com.on.ebx.factory.workflow;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link AgentEscalationUserTask#decisionOf} (agentic framework lot, 2026-10-30). */
public final class AgentEscalationUserTaskTest
{
	public static void main(final String[] pArgs)
	{
		AgentEscalationUserTaskTest.check("accepted", "ACCEPTED".equals(AgentEscalationUserTask.decisionOf(true, false)));
		AgentEscalationUserTaskTest.check("rejected", "REJECTED".equals(AgentEscalationUserTask.decisionOf(false, true)));
		AgentEscalationUserTaskTest.check("neither is a rejection", "REJECTED".equals(AgentEscalationUserTask.decisionOf(false, false)));
		AgentEscalationUserTaskTest.check("both is a rejection", "REJECTED".equals(AgentEscalationUserTask.decisionOf(true, true)));
		System.out.println("AgentEscalationUserTaskTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
