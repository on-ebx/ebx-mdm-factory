package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Map;

/** JUnit-free: the pure inputs of an automatic-intake chain ({@link PipelineWorkflowLauncher#intakeInputs}). */
public final class PipelineWorkflowLauncherIntakeTest
{
	public static void main(final String[] pArgs)
	{
		Map<String, String> inputs = PipelineWorkflowLauncher.intakeInputs("Personnes", "uuid-1", null);
		check("order and content", Arrays.asList("projectCode", "loadLanding", "reviewRejects", "stagingScope", "scope", "intakeChain").equals(new java.util.ArrayList<String>(inputs.keySet())));
		check("incremental, no landing read", "false".equals(inputs.get("loadLanding")) && "new".equals(inputs.get("stagingScope")) && "incremental".equals(inputs.get("scope")));
		check("never blocks on a review", "false".equals(inputs.get("reviewRejects")));
		check("token", "uuid-1".equals(inputs.get("intakeChain")) && "Personnes".equals(inputs.get("projectCode")));
		check("no threshold, no input", !inputs.containsKey("maxRejectPercent"));
		inputs = PipelineWorkflowLauncher.intakeInputs("P", "u", Integer.valueOf(5));
		check("threshold as text", "5".equals(inputs.get("maxRejectPercent")));
		System.out.println("PipelineWorkflowLauncherIntakeTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
