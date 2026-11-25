package com.on.ebx.factory.ebx.ui;

/** Robustness audit F2-113: the cockpit asks the database for ten rejects and one run, never a sort of the whole table. */
public final class CockpitBoundedReadsTest
{
	public static void main(final String[] pArgs)
	{
		final String rejects = FactoryCockpitUserService.latestRejectsSql("P1");
		check("rejects: bounded", rejects.contains("FETCH NEXT 10 ROWS ONLY") && rejects.contains("ORDER BY"));
		check("rejects: for the project", rejects.contains("'P1'"));
		final String run = FactoryCockpitUserService.lastAgentRunSql("P'1");
		check("agent run: bounded to one", run.contains("FETCH NEXT 1 ROWS ONLY") && run.contains("ORDER BY t.\"startedAt\" DESC"));
		check("agent run: the project literal is escaped", run.contains("'P''1'"));
		System.out.println("CockpitBoundedReadsTest OK");
	}

	private static void check(final String pName, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pName);
		}
	}
}
