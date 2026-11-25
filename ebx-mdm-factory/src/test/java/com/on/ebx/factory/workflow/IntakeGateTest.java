package com.on.ebx.factory.workflow;

/** JUnit-free: {@link IntakeGate}. */
public final class IntakeGateTest
{
	public static void main(final String[] pArgs)
	{
		final IntakeGate.Decision ok = IntakeGate.decide(true, "tok", true, "RUNNING", "P", "P", "ON", "alice", true);
		check("the exact case is allowed as the enabler", ok.allowed && "alice".equals(ok.actAs));
		check("a padded bean project code still matches", IntakeGate.decide(true, "tok", true, "RUNNING", "P", " P ", "ON", " alice ", true).allowed);
		check("variable not declared (old publication)", !IntakeGate.decide(false, "tok", true, "RUNNING", "P", "P", "ON", "alice", true).allowed);
		check("no token", !IntakeGate.decide(true, null, true, "RUNNING", "P", "P", "ON", "alice", true).allowed);
		check("blank token", !IntakeGate.decide(true, "  ", true, "RUNNING", "P", "P", "ON", "alice", true).allowed);
		check("unknown chain", !IntakeGate.decide(true, "tok", false, null, null, "P", "ON", "alice", true).allowed);
		for (final String status : new String[] { "DONE", "DONE_WITH_REJECTS", "STOPPED", "FAILED", null })
		{
			check("chain " + status + " is not running", !IntakeGate.decide(true, "tok", true, status, "P", "P", "ON", "alice", true).allowed);
		}
		check("other project", !IntakeGate.decide(true, "tok", true, "RUNNING", "Q", "P", "ON", "alice", true).allowed);
		check("no project in the bean", !IntakeGate.decide(true, "tok", true, "RUNNING", "P", null, "ON", "alice", true).allowed);
		for (final String state : new String[] { "OFF", null, "", "on", "SOMETHING" })
		{
			check("intake " + state, !IntakeGate.decide(true, "tok", true, "RUNNING", "P", "P", state, "alice", true).allowed);
		}
		// a pause during a chain: the running chain finishes (its master and matching steps are still allowed)
		final IntakeGate.Decision paused = IntakeGate.decide(true, "tok", true, "RUNNING", "P", "P", "PAUSED", "alice", true);
		check("PAUSED with a RUNNING chain is allowed, as the enabler", paused.allowed && "alice".equals(paused.actAs));
		check("PAUSED does not open anything the chain row does not prove", !IntakeGate.decide(true, "tok", true, "STOPPED", "P", "P", "PAUSED", "alice", true).allowed
			&& !IntakeGate.decide(true, "tok", true, "RUNNING", "Q", "P", "PAUSED", "alice", true).allowed
			&& !IntakeGate.decide(true, "tok", true, "RUNNING", "P", "P", "PAUSED", "alice", false).allowed);
		check("nobody enabled it", !IntakeGate.decide(true, "tok", true, "RUNNING", "P", "P", "ON", null, true).allowed);
		check("blank enabler", !IntakeGate.decide(true, "tok", true, "RUNNING", "P", "P", "ON", " ", true).allowed);
		final IntakeGate.Decision revoked = IntakeGate.decide(true, "tok", true, "RUNNING", "P", "P", "ON", "alice", false);
		check("the enabler lost the role", !revoked.allowed && revoked.reason.contains("alice"));
		System.out.println("IntakeGateTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
