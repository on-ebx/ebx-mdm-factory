package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. F3-08 point 1 (1.4.2): a run started by hand (POST /agents/{code}/run) needs the agent switched on with a valid, signed activation,
 * like the scheduler and the events; a disabled agent, in ACT mode too, is refused with a coded message.
 */
public final class AgentManualRunActivationTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		AgentManualRunActivationTest.check("an agent that is not enabled is refused", "conflict.agentNotActivated".equals(AgentIdentity.manualRunProblem(false, null)));
		AgentManualRunActivationTest.check("... even if a signed actor is still stored", "conflict.agentNotActivated".equals(AgentIdentity.manualRunProblem(false, "alice")));
		AgentManualRunActivationTest.check("enabled but with no valid signature (changed settings, forged value) is refused", "conflict.agentNotActivated".equals(AgentIdentity.manualRunProblem(true, null))
			&& "conflict.agentNotActivated".equals(AgentIdentity.manualRunProblem(true, "  ")));
		AgentManualRunActivationTest.check("enabled with a verified actor may run", AgentIdentity.manualRunProblem(true, "alice") == null);
		final String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir"), "com/on/ebx/factory/ebx/AgentRunJob.java")),
			java.nio.charset.StandardCharsets.UTF_8);
		AgentManualRunActivationTest.check("AgentRunJob.start applies it to the via 'manual'", source.contains("\"manual\".equals(pVia)") && source.contains("AgentIdentity.manualRunProblem("));
		System.out.println("AgentManualRunActivationTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
