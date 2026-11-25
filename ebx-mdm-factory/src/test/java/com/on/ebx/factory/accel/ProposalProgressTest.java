package com.on.ebx.factory.accel;

/** JUnit-free: run the main, a failure throws. */
public final class ProposalProgressTest
{
	public static void main(final String[] pArgs)
	{
		ProposalProgressTest.newRunIsAllTodo();
		ProposalProgressTest.transitionsAndRoundTrip();
		ProposalProgressTest.parseNeverThrows();
		ProposalProgressTest.passAndKeyMapping();
		System.out.println("ProposalProgressTest OK");
	}

	static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	static void newRunIsAllTodo()
	{
		final ProposalProgress p = ProposalProgress.newRun();
		check("four stages in pass order", p.stages().size() == 4 && p.stages().get(0).key.equals("MODEL") && p.stages().get(3).key.equals("WORKFLOWS"));
		for (final ProposalProgress.Stage s : p.stages())
		{
			check("todo", ProposalProgress.TODO.equals(s.state));
		}
		check("not over", !p.isOver());
	}

	static void transitionsAndRoundTrip()
	{
		final ProposalProgress p = ProposalProgress.newRun();
		p.start(ProposalProgress.MODEL, 1000L);
		check("running keeps no end", p.stage("MODEL").startedAt.longValue() == 1000L && p.stage("MODEL").endedAt == null);
		final ProposalProgress.Counts c = new ProposalProgress.Counts();
		c.kept = 7;
		c.toPlace = 2;
		c.keepRows = 3;
		c.refused = 1;
		c.dropped = 4;
		c.ignored = 5;
		c.noAnswer = true;
		p.finish(ProposalProgress.MODEL, true, "9 lines", c, 2500L);
		check("written counts both kept and to place", c.written() == 9);
		p.skip(ProposalProgress.RULES, "Not asked.");
		p.start(ProposalProgress.FORMS, 3000L);
		p.finish(ProposalProgress.FORMS, false, "model unreachable", null, 3100L);
		check("workflows still todo: not over", !p.isOver());
		p.skip(ProposalProgress.WORKFLOWS, "Waiting for the decisions of the forms.");
		check("over", p.isOver());
		final ProposalProgress back = ProposalProgress.parse(p.toJson());
		check("state round trip", back.stage("MODEL").state.equals("DONE") && back.stage("RULES").state.equals("SKIPPED") && back.stage("FORMS").state.equals("FAILED"));
		check("times round trip", back.stage("MODEL").startedAt.longValue() == 1000L && back.stage("MODEL").endedAt.longValue() == 2500L);
		check("summary round trip", back.stage("FORMS").summary.equals("model unreachable") && back.stage("RULES").summary.equals("Not asked."));
		final ProposalProgress.Counts cb = back.stage("MODEL").counts;
		check("counts round trip", cb.kept == 7 && cb.toPlace == 2 && cb.keepRows == 3 && cb.refused == 1 && cb.dropped == 4 && cb.ignored == 5 && cb.noAnswer);
		check("no counts stay null", back.stage("FORMS").counts == null);
		check("json carries the pass number", p.toJson().contains("\"pass\":3"));
	}

	static void parseNeverThrows()
	{
		check("null", ProposalProgress.parse(null).stages().size() == 4);
		check("blank", ProposalProgress.parse("  ").stage("MODEL").state.equals("TODO"));
		check("garbage", ProposalProgress.parse("{not json").stage("MODEL").state.equals("TODO"));
		check("wrong shape", ProposalProgress.parse("{\"stages\":5}").stage("RULES").state.equals("TODO"));
		check("unknown stage ignored", ProposalProgress.parse("{\"stages\":[{\"key\":\"NOPE\",\"state\":\"DONE\"}]}").stages().size() == 4);
	}

	static void passAndKeyMapping()
	{
		for (int pass = 1; pass <= 4; pass++)
		{
			check("pass " + pass, ProposalProgress.passOf(ProposalProgress.keyOf(pass)) == pass);
		}
		boolean refused = false;
		try
		{
			ProposalProgress.keyOf(5);
		}
		catch (final IllegalArgumentException ex)
		{
			refused = true;
		}
		check("pass 5 does not exist", refused);
	}
}
