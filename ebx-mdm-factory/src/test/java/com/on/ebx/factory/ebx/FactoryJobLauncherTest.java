package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryRight;

/** JUnit-free (F2-03, F2-16): the dispatcher checks again that the user a job row names holds the right of its kind. */
public final class FactoryJobLauncherTest
{
	public static void main(final String[] pArgs)
	{
		FactoryJobKinds.registerAll();
		final String kind = "PROJECT_DELETE";
		final FactoryRight needed = FactoryJobQueue.rightOf(kind);
		FactoryJobLauncherTest.check("the kind asks a real right (not the fallback of an unknown kind only)", needed != null);
		// the same table of rights as the queuing: a holder of less than needed is refused, a holder of enough passes
		FactoryJobLauncherTest.check("a user with no right is refused", !FactoryAccess.mayRun(null, needed, false, false));
		FactoryJobLauncherTest.check("a viewer is refused a " + kind, needed == FactoryRight.VIEW || !FactoryAccess.mayRun(FactoryRight.VIEW, needed, false, false));
		FactoryJobLauncherTest.check("an administrator passes", FactoryAccess.mayRun(FactoryRight.ADMINISTRATOR, needed, false, false));
		FactoryJobLauncherTest.check("the pipeline launcher role passes where the kind admits it", FactoryAccess.mayRun(null, FactoryRight.DESIGN, true, true));
		FactoryJobLauncherTest.check("... and not where it does not", !FactoryAccess.mayRun(null, FactoryRight.DESIGN, false, true));
		// the row-level decision: no repository to resolve the user, an unknown kind or nobody named: refused, never run
		FactoryJobLauncherTest.check("an unknown kind is refused", !FactoryJobQueue.launcherMayRun(null, null, "NO_SUCH_KIND", "alice"));
		FactoryJobLauncherTest.check("a row naming nobody is refused", !FactoryJobQueue.launcherMayRun(null, null, kind, null) && !FactoryJobQueue.launcherMayRun(null, null, kind, " "));
		FactoryJobLauncherTest.check("a user the directory cannot resolve holds no right", !FactoryJobQueue.launcherMayRun(null, null, kind, "alice"));
		System.out.println("FactoryJobLauncherTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
