package com.on.ebx.factory.ebx;

import com.on.ebx.factory.agent.AgentTrigger;

/**
 * JUnit-free, like every other test in this module: run the main, a failure throws. The pure parts of the agent runner (agentic framework lot, 2026-10-30):
 * {@link AgentFramework#splitTool}, {@link AgentFramework#userOf}, {@link AgentRunJob#eventAfter}, {@link QualityFixAgent#fieldOf},
 * {@link AgentEscalations#ownerProfile} on empty values.
 */
public final class AgentFrameworkPureTest
{
	public static void main(final String[] pArgs)
	{
		AgentFrameworkPureTest.checkSplitTool();
		AgentFrameworkPureTest.checkUserOf();
		AgentFrameworkPureTest.checkEventAfter();
		AgentFrameworkPureTest.checkFieldOf();
		AgentFrameworkPureTest.check("blank owner is none", AgentEscalations.ownerProfile("  ") == null && AgentEscalations.ownerProfile(null) == null);
		System.out.println("AgentFrameworkPureTest OK");
	}

	private static void checkSplitTool()
	{
		final String[] split = AgentFramework.splitTool("data-access/get_record");
		AgentFrameworkPureTest.check("server and tool", split != null && "data-access".equals(split[0]) && "get_record".equals(split[1]));
		AgentFrameworkPureTest.check("factory tool is not a call", AgentFramework.splitTool("factory:suspect.decide") == null);
		AgentFrameworkPureTest.check("connectors flow is not a call", AgentFramework.splitTool("connectors:a/b") == null);
		AgentFrameworkPureTest.check("half a call", AgentFramework.splitTool("/x") == null && AgentFramework.splitTool("x/") == null && AgentFramework.splitTool(null) == null);
	}

	private static void checkUserOf()
	{
		AgentFrameworkPureTest.check("user profile", "alice".equals(AgentFramework.userOf("Ualice")));
		AgentFrameworkPureTest.check("role profile", AgentFramework.userOf("Rsteward") == null);
		AgentFrameworkPureTest.check("bare U", AgentFramework.userOf("U") == null && AgentFramework.userOf(null) == null);
	}

	private static void checkEventAfter()
	{
		AgentFrameworkPureTest.check("matching run", AgentTrigger.MATCHING_RUN_DONE.equals(AgentRunJob.eventAfter(MatchingRunRun.STEP)));
		AgentFrameworkPureTest.check("master load", AgentTrigger.MASTER_LOAD_DONE.equals(AgentRunJob.eventAfter(MasterBuildRun.STEP)));
		AgentFrameworkPureTest.check("staging load", AgentTrigger.STAGING_LOAD_DONE.equals(AgentRunJob.eventAfter(StagingLoadRun.STEP)));
		AgentFrameworkPureTest.check("an agent run raises nothing", AgentRunJob.eventAfter(AgentFramework.RUN_STEP) == null);
		AgentFrameworkPureTest.check("null raises nothing", AgentRunJob.eventAfter(null) == null);
	}

	private static void checkFieldOf()
	{
		AgentFrameworkPureTest.check("slash path", "Country".equals(QualityFixAgent.fieldOf("/Country")));
		AgentFrameworkPureTest.check("dot path", "Country".equals(QualityFixAgent.fieldOf("./Country")));
		AgentFrameworkPureTest.check("nested is none", QualityFixAgent.fieldOf("/Address/City") == null);
		AgentFrameworkPureTest.check("the record is none", QualityFixAgent.fieldOf("/") == null && QualityFixAgent.fieldOf(".") == null && QualityFixAgent.fieldOf(null) == null);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
