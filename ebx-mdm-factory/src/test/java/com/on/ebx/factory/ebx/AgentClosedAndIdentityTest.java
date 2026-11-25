package com.on.ebx.factory.ebx;

import com.on.ebx.factory.workflow.AgentDecisionTaskBean;
import com.on.ebx.factory.workflow.AgentEscalationUserTask;

/**
 * JUnit-free: run the main, a failure throws. F3-08 points 1 and 2 (production review, 2026-11-12): with the agents closed NO path runs or decides anything, and
 * a path started by a person answers the coded refusal before it touches the repository (every call below gets null EBX objects: a path that did not check the
 * switch first would throw a NullPointerException instead). F3-02: the pure decision of who an unattended run acts for.
 */
public final class AgentClosedAndIdentityTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		System.clearProperty(FeatureSwitches.AGENTS_PROPERTY);
		AgentClosedAndIdentityTest.checkEveryPersonPathRefuses();
		AgentClosedAndIdentityTest.checkUnattendedPathsDoNothing();
		AgentClosedAndIdentityTest.checkOpenIsNotRefused();
		AgentClosedAndIdentityTest.checkActivationDecision();
		System.out.println("AgentClosedAndIdentityTest OK");
	}

	private static void checkEveryPersonPathRefuses() throws Exception
	{
		AgentClosedAndIdentityTest.refuses("manual run / REST queue (AgentRunJob.start)", () -> AgentRunJob.start(null, null, "A1", "manual"));
		AgentClosedAndIdentityTest.refuses("a job already queued (AgentFramework.run)", () -> AgentFramework.run(null, null, "A1", "manual"));
		AgentClosedAndIdentityTest.refuses("decision and application (AgentFramework.decideProposal)", () -> AgentFramework.decideProposal(null, null, "1", true, "alice"));
		AgentClosedAndIdentityTest.refuses("switching an agent on (AgentIdentity.enable)", () -> AgentIdentity.enable(null, null, "A1"));
		AgentClosedAndIdentityTest.refuses("review workflow bean", () -> new AgentDecisionTaskBean().executeScript(null));
		AgentClosedAndIdentityTest.refuses("review workflow user task", () -> new AgentEscalationUserTask().handleWorkItemCompletion(null));
	}

	private static void checkUnattendedPathsDoNothing()
	{
		AgentRunJob.forgetNotes();
		AgentClosedAndIdentityTest.check("an event queues nothing and does not read the repository", AgentRunJob.fire(null, null, "P1", "MATCHING_RUN_DONE").isEmpty());
		AgentClosedAndIdentityTest.check("a project id and an event with a null factory still queue nothing", AgentRunJob.fire(null, null, "P1", "STAGING_LOAD_DONE").isEmpty());
		AgentRunJob.forgetNotes();
		AgentClosedAndIdentityTest.check("a tick queues nothing and does not read the repository", AgentRunJob.tick(null, null, System.currentTimeMillis()).isEmpty());
		// a tick or an event that went on to read the repository would have been swallowed by its own catch: the closed note proves the switch was read first
		AgentClosedAndIdentityTest.check("the closed tick is the one that wrote the closed note", !AgentRunJob.noteClosed("x"));
		AgentRunJob.forgetNotes();
		AgentClosedAndIdentityTest.check("the first closed note of a start is written", AgentRunJob.noteClosed("the schedule"));
		AgentClosedAndIdentityTest.check("the next ticks write nothing more", !AgentRunJob.noteClosed("the schedule") && !AgentRunJob.noteClosed("an event"));
		AgentRunJob.forgetNotes();
		AgentClosedAndIdentityTest.check("one WARN per unsigned agent per start", AgentRunJob.noteUnsigned("A1") && !AgentRunJob.noteUnsigned("A1") && AgentRunJob.noteUnsigned("A2"));
		AgentRunJob.forgetNotes();
	}

	private static void checkOpenIsNotRefused() throws Exception
	{
		System.setProperty(FeatureSwitches.AGENTS_PROPERTY, "true");
		try
		{
			// open: the guard lets the call through, so the null factory is reached (and fails there, which proves the guard was passed)
			boolean reached = false;
			try
			{
				AgentRunJob.start(null, null, "A1", "manual");
			}
			catch (final NullPointerException expected)
			{
				reached = true;
			}
			AgentClosedAndIdentityTest.check("open agents pass the switch", reached);
		}
		finally
		{
			System.clearProperty(FeatureSwitches.AGENTS_PROPERTY);
		}
	}

	private static void checkActivationDecision()
	{
		AgentClosedAndIdentityTest.check("a run started by hand needs no activation", AgentIdentity.activationProblem("manual", null, "alice") == null);
		AgentClosedAndIdentityTest.check("an event run needs a signed actor", "conflict.agentNotActivated".equals(AgentIdentity.activationProblem("event MATCHING_RUN_DONE", null, "alice")));
		AgentClosedAndIdentityTest.check("a schedule run needs a signed actor", "conflict.agentNotActivated".equals(AgentIdentity.activationProblem("schedule", " ", "alice")));
		AgentClosedAndIdentityTest.check("a job that names another person than the signed one is refused",
			"conflict.agentNotActivated".equals(AgentIdentity.activationProblem("schedule", "alice", "admin")));
		AgentClosedAndIdentityTest.check("the signed actor may run", AgentIdentity.activationProblem("schedule", "alice", "alice") == null
			&& AgentIdentity.activationProblem("event STAGING_LOAD_DONE", "alice", " alice ") == null);
		AgentClosedAndIdentityTest.check("which vias are unattended", AgentIdentity.viaNeedsActivation("schedule") && AgentIdentity.viaNeedsActivation("event X")
			&& !AgentIdentity.viaNeedsActivation("manual") && !AgentIdentity.viaNeedsActivation(null));
	}

	private interface Call
	{
		void run() throws Exception;
	}

	private static void refuses(final String pWhat, final Call pCall) throws Exception
	{
		try
		{
			pCall.run();
		}
		catch (final EngineRefusal refusal)
		{
			AgentClosedAndIdentityTest.check(pWhat + ": refusal is the closed-agents one", "engine.missing.agentsClosed".equals(refusal.code())
				&& refusal.getMessage().contains(FeatureSwitches.AGENTS_PROPERTY));
			return;
		}
		throw new IllegalStateException("FAILED: " + pWhat + " did not refuse while the agents are closed");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new IllegalStateException("FAILED: " + pWhat);
		}
	}
}
