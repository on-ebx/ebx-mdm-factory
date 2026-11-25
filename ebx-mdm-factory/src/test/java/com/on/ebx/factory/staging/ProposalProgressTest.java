package com.on.ebx.factory.staging;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ProposalProgressTest
{
	public static void main(final String[] pArgs)
	{
		ProposalProgressTest.newRunIsAllTodo();
		ProposalProgressTest.startFinishSkipTransitions();
		ProposalProgressTest.isOverOnlyOnceEveryStageSettled();
		ProposalProgressTest.jsonRoundTrip();
		ProposalProgressTest.parseNullOrGarbageIsSafe();
		System.out.println("ProposalProgressTest OK");
	}

	private static void newRunIsAllTodo()
	{
		final ProposalProgress progress = ProposalProgress.newRun();
		for (final String key : ProposalProgress.STAGES)
		{
			ProposalProgressTest.check(key + " starts TODO", ProposalProgress.TODO.equals(progress.stage(key).state));
		}
		ProposalProgressTest.check("6 stages", progress.stages().size() == 6);
	}

	private static void startFinishSkipTransitions()
	{
		final ProposalProgress progress = ProposalProgress.newRun();
		progress.start(ProposalProgress.STRUCTURE_RULE, 1000L);
		ProposalProgressTest.check("RUNNING after start", ProposalProgress.RUNNING.equals(progress.stage(ProposalProgress.STRUCTURE_RULE).state));
		ProposalProgressTest.check("startedAt kept", progress.stage(ProposalProgress.STRUCTURE_RULE).startedAt.longValue() == 1000L);
		progress.finish(ProposalProgress.STRUCTURE_RULE, true, "3 entities", 2000L);
		final ProposalProgress.Stage done = progress.stage(ProposalProgress.STRUCTURE_RULE);
		ProposalProgressTest.check("DONE after finish(true)", ProposalProgress.DONE.equals(done.state));
		ProposalProgressTest.check("startedAt survives finish", done.startedAt.longValue() == 1000L);
		ProposalProgressTest.check("endedAt set", done.endedAt.longValue() == 2000L);
		ProposalProgressTest.check("summary kept", "3 entities".equals(done.summary));

		progress.start(ProposalProgress.STRUCTURE_LLM, 3000L);
		progress.finish(ProposalProgress.STRUCTURE_LLM, false, "connector error", 4000L);
		ProposalProgressTest.check("FAILED after finish(false)", ProposalProgress.FAILED.equals(progress.stage(ProposalProgress.STRUCTURE_LLM).state));

		progress.skip(ProposalProgress.CAPABILITY, "no language model configured");
		final ProposalProgress.Stage skipped = progress.stage(ProposalProgress.CAPABILITY);
		ProposalProgressTest.check("SKIPPED after skip", ProposalProgress.SKIPPED.equals(skipped.state));
		ProposalProgressTest.check("skip reason in summary", "no language model configured".equals(skipped.summary));
		ProposalProgressTest.check("skip never started", skipped.startedAt == null);
	}

	private static void isOverOnlyOnceEveryStageSettled()
	{
		final ProposalProgress progress = ProposalProgress.newRun();
		ProposalProgressTest.check("fresh run is not over", !progress.isOver());
		for (final String key : ProposalProgress.STAGES)
		{
			progress.skip(key, "n/a");
		}
		ProposalProgressTest.check("every stage settled -> over", progress.isOver());

		final ProposalProgress mixed = ProposalProgress.newRun();
		mixed.finish(ProposalProgress.STRUCTURE_RULE, true, null, 1L);
		mixed.start(ProposalProgress.STRUCTURE_LLM, 2L);
		ProposalProgressTest.check("one stage still RUNNING -> not over", !mixed.isOver());
	}

	private static void jsonRoundTrip()
	{
		final ProposalProgress progress = ProposalProgress.newRun();
		progress.start(ProposalProgress.STRUCTURE_RULE, 10L);
		progress.finish(ProposalProgress.STRUCTURE_RULE, true, "4 entities created", 20L);
		progress.skip(ProposalProgress.STRUCTURE_LLM, "no language model configured");
		final ProposalProgress reparsed = ProposalProgress.parse(progress.toJson());
		final ProposalProgress.Stage rule = reparsed.stage(ProposalProgress.STRUCTURE_RULE);
		ProposalProgressTest.check("state survives round trip", ProposalProgress.DONE.equals(rule.state));
		ProposalProgressTest.check("startedAt survives round trip", rule.startedAt.longValue() == 10L);
		ProposalProgressTest.check("endedAt survives round trip", rule.endedAt.longValue() == 20L);
		ProposalProgressTest.check("summary survives round trip", "4 entities created".equals(rule.summary));
		ProposalProgressTest.check("skip survives round trip", ProposalProgress.SKIPPED.equals(reparsed.stage(ProposalProgress.STRUCTURE_LLM).state));
		ProposalProgressTest.check("untouched stage still TODO after round trip", ProposalProgress.TODO.equals(reparsed.stage(ProposalProgress.PROFILE).state));
	}

	private static void parseNullOrGarbageIsSafe()
	{
		ProposalProgressTest.check("null -> all TODO", ProposalProgress.parse(null).stage(ProposalProgress.STRUCTURE_RULE).state.equals(ProposalProgress.TODO));
		ProposalProgressTest.check("blank -> all TODO", ProposalProgress.parse("   ").stage(ProposalProgress.PROFILE).state.equals(ProposalProgress.TODO));
		ProposalProgressTest.check("garbage -> all TODO, never throws",
			ProposalProgress.parse("{not json").stage(ProposalProgress.ANOMALY).state.equals(ProposalProgress.TODO));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
