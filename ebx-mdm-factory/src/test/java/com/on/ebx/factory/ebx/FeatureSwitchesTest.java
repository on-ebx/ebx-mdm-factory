package com.on.ebx.factory.ebx;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link FeatureSwitches} (production review, 2026-11-12). */
public final class FeatureSwitchesTest
{
	public static void main(final String[] pArgs)
	{
		FeatureSwitchesTest.checkClosedByDefault();
		FeatureSwitchesTest.checkOnlyTrueOpens();
		FeatureSwitchesTest.checkIndependent();
		System.out.println("FeatureSwitchesTest OK");
	}

	private static void checkClosedByDefault()
	{
		System.clearProperty(FeatureSwitches.AGENTS_PROPERTY);
		System.clearProperty(FeatureSwitches.SOURCE_DRIFT_PROPERTY);
		FeatureSwitchesTest.check("agents closed when nothing is set", !FeatureSwitches.agents());
		FeatureSwitchesTest.check("source drift closed when nothing is set", !FeatureSwitches.sourceDrift());
		FeatureSwitchesTest.check("agents refusal is coded and names the property",
			FeatureSwitchesTest.refusal(true).contains("engine.missing.agentsClosed|") && FeatureSwitchesTest.refusal(true).contains(FeatureSwitches.AGENTS_PROPERTY));
		FeatureSwitchesTest.check("source drift refusal is coded and names the property", FeatureSwitchesTest.refusal(false).contains("engine.missing.sourceDriftClosed|")
			&& FeatureSwitchesTest.refusal(false).contains(FeatureSwitches.SOURCE_DRIFT_PROPERTY));
	}

	private static void checkOnlyTrueOpens()
	{
		for (final String value : new String[] { "", " ", "yes", "1", "on", "truee", "false" })
		{
			System.setProperty(FeatureSwitches.AGENTS_PROPERTY, value);
			FeatureSwitchesTest.check("'" + value + "' keeps the agents closed", !FeatureSwitches.agents());
		}
		for (final String value : new String[] { "true", "TRUE", " True " })
		{
			System.setProperty(FeatureSwitches.AGENTS_PROPERTY, value);
			FeatureSwitchesTest.check("'" + value + "' opens the agents", FeatureSwitches.agents());
			FeatureSwitchesTest.check("no refusal once open", FeatureSwitchesTest.refusal(true).isEmpty());
		}
		System.clearProperty(FeatureSwitches.AGENTS_PROPERTY);
	}

	private static void checkIndependent()
	{
		System.setProperty(FeatureSwitches.SOURCE_DRIFT_PROPERTY, "true");
		FeatureSwitchesTest.check("opening source drift does not open the agents", FeatureSwitches.sourceDrift() && !FeatureSwitches.agents());
		System.clearProperty(FeatureSwitches.SOURCE_DRIFT_PROPERTY);
		FeatureSwitchesTest.check("closed again as soon as the property is gone", !FeatureSwitches.sourceDrift());
	}

	/** {@code code|message} of the refusal, empty when the function is open. */
	private static String refusal(final boolean pAgents)
	{
		try
		{
			if (pAgents)
			{
				FeatureSwitches.requireAgents();
			}
			else
			{
				FeatureSwitches.requireSourceDrift();
			}
			return "";
		}
		catch (final EngineRefusal ex)
		{
			return ex.code() + "|" + ex.getMessage();
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
