package com.on.ebx.factory.agent;

import java.util.Arrays;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link AgentTools} (agentic framework lot, 2026-10-30). */
public final class AgentToolsTest
{
	public static void main(final String[] pArgs)
	{
		AgentToolsTest.checkNormalise();
		AgentToolsTest.checkExact();
		AgentToolsTest.checkPatterns();
		System.out.println("AgentToolsTest OK");
	}

	private static void checkNormalise()
	{
		AgentToolsTest.check("family lower-cased", "factory:suspect.decide".equals(AgentTools.normalise(" FACTORY:suspect.decide ")));
		AgentToolsTest.check("rest kept", "data-access/Get_Record".equals(AgentTools.normalise("Data-Access/Get_Record")));
		AgentToolsTest.check("blank is null", AgentTools.normalise("  ") == null && AgentTools.normalise(null) == null);
		AgentToolsTest.check("no family is null", AgentTools.normalise("suspect.decide") == null && AgentTools.normalise(":x") == null && AgentTools.normalise("x/") == null);
		final AgentTools tools = AgentTools.of(Arrays.asList("factory:suspect.decide", "FACTORY:suspect.decide", "", null, "nonsense"));
		AgentToolsTest.check("duplicates and junk dropped", tools.entries().equals(Arrays.asList("factory:suspect.decide")));
	}

	private static void checkExact()
	{
		final AgentTools tools = AgentTools.of(Arrays.asList(AgentTools.SUSPECT_DECIDE));
		AgentToolsTest.check("listed tool allowed", tools.allows("factory:suspect.decide"));
		AgentToolsTest.check("case of the family ignored", tools.allows("Factory:suspect.decide"));
		AgentToolsTest.check("other tool refused", !tools.allows(AgentTools.RECORD_CORRECT));
		AgentToolsTest.check("null refused", !tools.allows(null));
		AgentToolsTest.check("empty list refuses everything", !AgentTools.of(null).allows(AgentTools.SUSPECT_DECIDE));
	}

	private static void checkPatterns()
	{
		final AgentTools tools = AgentTools.of(Arrays.asList("data-access/*", "connectors:*"));
		AgentToolsTest.check("server/* covers its tools", tools.allows("data-access/get_record"));
		AgentToolsTest.check("server/* never covers another server", !tools.allows("data-model/publish_data_model"));
		AgentToolsTest.check("server/* never covers a prefix-sharing server", !tools.allows("data-access-x/get_record"));
		AgentToolsTest.check("connectors:* covers a flow", tools.allows("connectors:CRM_PUSH"));
		AgentToolsTest.check("a decision naming a pattern is refused", !tools.allows("data-access/*"));
		AgentToolsTest.check("factory tools not covered by patterns", !tools.allows(AgentTools.RECORD_CORRECT));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
