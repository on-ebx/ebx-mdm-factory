package com.on.ebx.factory.workflow;

import com.on.ebx.factory.ebx.Actor;

/**
 * JUnit-free: run the main, a failure throws. The pure parts of the pipeline-audit lot (2026-09-24): the launcher-role
 * setting, and the thread-bound {@link Actor} the workflow beans set so their steps record the launcher.
 */
public final class PipelineLauncherTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		PipelineLauncherTest.check("empty setting = administrators", PipelineLauncher.roleName(null) == null && PipelineLauncher.roleName("  ") == null);
		PipelineLauncherTest.check("a role name is trimmed", "DataOps".equals(PipelineLauncher.roleName(" DataOps ")));

		PipelineLauncherTest.check("no actor set: the session's user, via session",
			Actor.current(null).userId == null && "session".equals(Actor.current(null).via));
		final String seen = Actor.as("alice", "workflow 42", () -> Actor.current(null).userId + "|" + Actor.current(null).via);
		PipelineLauncherTest.check("inside as(): the launcher: " + seen, "alice|workflow 42".equals(seen));
		PipelineLauncherTest.check("cleared after as()", "session".equals(Actor.current(null).via));
		try
		{
			Actor.as("bob", "workflow 43", () -> {
				throw new IllegalStateException("boom");
			});
		}
		catch (final IllegalStateException expected)
		{
			// the work failed: the actor must still be cleared
		}
		PipelineLauncherTest.check("cleared after a failing as()", "session".equals(Actor.current(null).via));
		System.out.println("PipelineLauncherTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
