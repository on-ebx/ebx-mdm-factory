package com.on.ebx.factory.agent;

import com.on.ebx.factory.agent.AgentPolicy.Autonomy;
import com.on.ebx.factory.agent.AgentPolicy.Disposition;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link AgentPolicy} (agentic framework lot, 2026-10-30). */
public final class AgentPolicyTest
{
	public static void main(final String[] pArgs)
	{
		AgentPolicyTest.checkAutonomyParse();
		AgentPolicyTest.checkAbstainAndRefuse();
		AgentPolicyTest.checkAutonomyLevels();
		AgentPolicyTest.checkThresholdAndConfidence();
		System.out.println("AgentPolicyTest OK");
	}

	private static void checkAutonomyParse()
	{
		AgentPolicyTest.check("ACT reads", Autonomy.parse("act") == Autonomy.ACT);
		AgentPolicyTest.check("ACT_WITH_APPROVAL reads", Autonomy.parse(" ACT_WITH_APPROVAL ") == Autonomy.ACT_WITH_APPROVAL);
		AgentPolicyTest.check("unknown is SUGGEST", Autonomy.parse("YOLO") == Autonomy.SUGGEST);
		AgentPolicyTest.check("null is SUGGEST", Autonomy.parse(null) == Autonomy.SUGGEST);
	}

	private static void checkAbstainAndRefuse()
	{
		AgentPolicyTest.check("abstention wins", AgentPolicy.decide(Autonomy.ACT, 50, 99, true, false, false) == Disposition.NO_ACTION);
		AgentPolicyTest.check("tool off the list is refused", AgentPolicy.decide(Autonomy.ACT, 50, 99, false, false, true) == Disposition.REFUSED);
		AgentPolicyTest.check("no permission is refused", AgentPolicy.decide(Autonomy.ACT, 50, 99, false, true, false) == Disposition.REFUSED);
		AgentPolicyTest.check("refused never escalates", !Disposition.REFUSED.escalates() && !Disposition.NO_ACTION.escalates() && !Disposition.APPLY.escalates());
		AgentPolicyTest.check("suggest and approval escalate", Disposition.SUGGEST.escalates() && Disposition.AWAIT_APPROVAL.escalates());
	}

	private static void checkAutonomyLevels()
	{
		AgentPolicyTest.check("SUGGEST never applies", AgentPolicy.decide(Autonomy.SUGGEST, 0, 100, false, true, true) == Disposition.SUGGEST);
		AgentPolicyTest.check("null autonomy is SUGGEST", AgentPolicy.decide(null, 0, 100, false, true, true) == Disposition.SUGGEST);
		AgentPolicyTest.check("ACT_WITH_APPROVAL always waits", AgentPolicy.decide(Autonomy.ACT_WITH_APPROVAL, 0, 100, false, true, true) == Disposition.AWAIT_APPROVAL);
		AgentPolicyTest.check("ACT at the threshold applies", AgentPolicy.decide(Autonomy.ACT, 80, 80, false, true, true) == Disposition.APPLY);
		AgentPolicyTest.check("ACT below the threshold waits", AgentPolicy.decide(Autonomy.ACT, 80, 79, false, true, true) == Disposition.AWAIT_APPROVAL);
	}

	private static void checkThresholdAndConfidence()
	{
		AgentPolicyTest.check("default threshold", AgentPolicy.threshold(null) == AgentPolicy.DEFAULT_THRESHOLD);
		AgentPolicyTest.check("threshold clamped", AgentPolicy.threshold(150) == 100 && AgentPolicy.threshold(-3) == 0);
		AgentPolicyTest.check("no confidence is 0", AgentPolicy.confidence(null) == 0);
		AgentPolicyTest.check("ACT without confidence waits", AgentPolicy.decide(Autonomy.ACT, null, null, false, true, true) == Disposition.AWAIT_APPROVAL);
		AgentPolicyTest.check("default threshold 90: 89 waits", AgentPolicy.decide(Autonomy.ACT, null, 89, false, true, true) == Disposition.AWAIT_APPROVAL);
		AgentPolicyTest.check("default threshold 90: 90 applies", AgentPolicy.decide(Autonomy.ACT, null, 90, false, true, true) == Disposition.APPLY);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
