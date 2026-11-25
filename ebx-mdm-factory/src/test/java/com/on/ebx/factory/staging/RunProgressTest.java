package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.List;

/** JUnit-free, like {@code ProposalProgressTest}: run the main, a failure throws. */
public final class RunProgressTest
{
	private static final List<String> STAGES = Arrays.asList("MODEL", "LOAD");
	private static final List<String> THREE_STAGES = Arrays.asList("TABLES", "COPY", "MATCHING_READY");

	public static void main(final String[] pArgs)
	{
		RunProgressTest.newRunIsAllTodoInGivenOrder();
		RunProgressTest.startFinishSkipTransitions();
		RunProgressTest.isOverOnlyOnceEveryStageSettled();
		RunProgressTest.jsonRoundTripIsSelfDescribing();
		RunProgressTest.parseNullOrGarbageFallsBackToGivenStages();
		RunProgressTest.differentStageCountsStayIndependent();
		RunProgressTest.everyChangeIsReported();
		RunProgressTest.unitsCountRealRows();
		RunProgressTest.remainingFollowsTheMeasuredRate();
		RunProgressTest.liveFieldsSurviveJson();
		RunProgressTest.routineChangesAreThrottledTransitionsAreNot();
		System.out.println("RunProgressTest OK");
	}

	private static void newRunIsAllTodoInGivenOrder()
	{
		final RunProgress progress = RunProgress.newRun(RunProgressTest.STAGES);
		RunProgressTest.check("2 stages", progress.stages().size() == 2);
		RunProgressTest.check("order kept", "MODEL".equals(progress.stages().get(0).key) && "LOAD".equals(progress.stages().get(1).key));
		for (final String key : RunProgressTest.STAGES)
		{
			RunProgressTest.check(key + " starts TODO", RunProgress.TODO.equals(progress.stage(key).state));
		}
	}

	private static void startFinishSkipTransitions()
	{
		final RunProgress progress = RunProgress.newRun(RunProgressTest.STAGES);
		progress.start("MODEL", 1000L);
		RunProgressTest.check("RUNNING after start", RunProgress.RUNNING.equals(progress.stage("MODEL").state));
		RunProgressTest.check("startedAt kept", progress.stage("MODEL").startedAt.longValue() == 1000L);
		progress.finish("MODEL", true, "staging tables created", 2000L);
		final RunProgress.Stage done = progress.stage("MODEL");
		RunProgressTest.check("DONE after finish(true)", RunProgress.DONE.equals(done.state));
		RunProgressTest.check("startedAt survives finish", done.startedAt.longValue() == 1000L);
		RunProgressTest.check("endedAt set", done.endedAt.longValue() == 2000L);
		RunProgressTest.check("summary kept", "staging tables created".equals(done.summary));

		progress.start("LOAD", 3000L);
		progress.finish("LOAD", false, "connector error", 4000L);
		RunProgressTest.check("FAILED after finish(false)", RunProgress.FAILED.equals(progress.stage("LOAD").state));

		final RunProgress three = RunProgress.newRun(RunProgressTest.THREE_STAGES);
		three.skip("MATCHING_READY", "the copy stage did not complete");
		final RunProgress.Stage skipped = three.stage("MATCHING_READY");
		RunProgressTest.check("SKIPPED after skip", RunProgress.SKIPPED.equals(skipped.state));
		RunProgressTest.check("skip reason in summary", "the copy stage did not complete".equals(skipped.summary));
		RunProgressTest.check("skip never started", skipped.startedAt == null);
	}

	private static void isOverOnlyOnceEveryStageSettled()
	{
		final RunProgress progress = RunProgress.newRun(RunProgressTest.THREE_STAGES);
		RunProgressTest.check("fresh run is not over", !progress.isOver());
		for (final String key : RunProgressTest.THREE_STAGES)
		{
			progress.skip(key, "n/a");
		}
		RunProgressTest.check("every stage settled -> over", progress.isOver());

		final RunProgress mixed = RunProgress.newRun(RunProgressTest.STAGES);
		mixed.finish("MODEL", true, null, 1L);
		mixed.start("LOAD", 2L);
		RunProgressTest.check("one stage still RUNNING -> not over", !mixed.isOver());
	}

	private static void jsonRoundTripIsSelfDescribing()
	{
		final RunProgress progress = RunProgress.newRun(RunProgressTest.THREE_STAGES);
		progress.start("TABLES", 10L);
		progress.finish("TABLES", true, "2 business object(s)", 20L);
		progress.skip("COPY", "not selected");
		// parse with a WRONG fallback list on purpose - a self-describing JSON must not need it
		final RunProgress reparsed = RunProgress.parse(progress.toJson(), RunProgressTest.STAGES);
		RunProgressTest.check("stage order recovered from JSON, not the fallback", reparsed.stages().size() == 3
			&& "TABLES".equals(reparsed.stages().get(0).key) && "MATCHING_READY".equals(reparsed.stages().get(2).key));
		final RunProgress.Stage tables = reparsed.stage("TABLES");
		RunProgressTest.check("state survives round trip", RunProgress.DONE.equals(tables.state));
		RunProgressTest.check("startedAt survives round trip", tables.startedAt.longValue() == 10L);
		RunProgressTest.check("endedAt survives round trip", tables.endedAt.longValue() == 20L);
		RunProgressTest.check("summary survives round trip", "2 business object(s)".equals(tables.summary));
		RunProgressTest.check("skip survives round trip", RunProgress.SKIPPED.equals(reparsed.stage("COPY").state));
		RunProgressTest.check("untouched stage still TODO after round trip", RunProgress.TODO.equals(reparsed.stage("MATCHING_READY").state));
	}

	private static void parseNullOrGarbageFallsBackToGivenStages()
	{
		RunProgressTest.check("null -> fallback stages, all TODO",
			RunProgress.parse(null, RunProgressTest.STAGES).stage("MODEL").state.equals(RunProgress.TODO));
		RunProgressTest.check("blank -> fallback stages, all TODO",
			RunProgress.parse("   ", RunProgressTest.STAGES).stage("LOAD").state.equals(RunProgress.TODO));
		RunProgressTest.check("garbage -> fallback stages, never throws",
			RunProgress.parse("{not json", RunProgressTest.STAGES).stage("MODEL").state.equals(RunProgress.TODO));
		RunProgressTest.check("empty stages array -> fallback stages",
			RunProgress.parse("{\"stages\":[]}", RunProgressTest.STAGES).stage("LOAD").state.equals(RunProgress.TODO));
	}

	private static void differentStageCountsStayIndependent()
	{
		final RunProgress two = RunProgress.newRun(RunProgressTest.STAGES);
		final RunProgress three = RunProgress.newRun(RunProgressTest.THREE_STAGES);
		RunProgressTest.check("2-stage run has 2", two.stages().size() == 2);
		RunProgressTest.check("3-stage run has 3", three.stages().size() == 3);
		RunProgressTest.check("unrelated stage key is unknown", two.stage("TABLES") == null);
	}

	private static final class Clock implements java.util.function.LongSupplier
	{
		long now = 1000000L;

		@Override
		public long getAsLong()
		{
			return this.now;
		}
	}

	/** Staging-live lot (2026-09-27): the job persists the detail from onChange - a stage start, an activity and every batch must reach it. */
	private static void everyChangeIsReported()
	{
		final RunProgress progress = RunProgress.newRun(RunProgressTest.STAGES);
		final int[] calls = { 0 };
		progress.onChange(() -> calls[0]++);
		progress.start("MODEL", 1L);
		progress.activity("MODEL", "Creating the staging model.");
		progress.unitPlanned("Personne", "SAP");
		progress.unitStart("Personne", "SAP", 10L);
		progress.unitProgress("Personne", "SAP", 5L, 5L, 0L);
		progress.unitEnd("Personne", "SAP", true);
		progress.note("x");
		RunProgressTest.check("7 changes reported", calls[0] == 7);
		progress.onChange(() -> {
			throw new IllegalStateException("write failed");
		});
		progress.finish("MODEL", true, "ok", 2L);
		RunProgressTest.check("a failing listener never blocks the change", RunProgress.DONE.equals(progress.stage("MODEL").state));
		progress.activity("MODEL", "late");
		RunProgressTest.check("an activity on a settled stage is ignored", progress.stage("MODEL").activity == null);
	}

	private static void unitsCountRealRows()
	{
		final Clock clock = new Clock();
		final RunProgress progress = RunProgress.newRun(RunProgressTest.STAGES);
		progress.clock(clock);
		progress.unitPlanned("Personne", "SAP");
		progress.unitPlanned("Personne", "SF");
		progress.unitPlanned("Adresse", "SAP");
		RunProgressTest.check("planned cells are TODO with no total", RunProgress.TODO.equals(progress.unit("Personne", "SF").state) && progress.unit("Personne", "SF").total == -1L);
		progress.unitStart("Personne", "SAP", 165L);
		progress.unitStart("Adresse", "SAP", -1L);
		clock.now += 10000L;
		progress.unitProgress("Personne", "SAP", 96L, 95L, 1L);
		progress.unitProgress("Adresse", "SAP", 90L, 90L, 0L);
		final long[] rows = progress.rowsReadOfTotal();
		RunProgressTest.check("only cells with a known total count in the bar", rows[0] == 96L && rows[1] == 165L);
		progress.unitPlanned("Personne", "SAP");
		RunProgressTest.check("planning again never resets a started cell", progress.unit("Personne", "SAP").read == 96L);
		progress.unitEnd("Personne", "SAP", true);
		RunProgressTest.check("an ended cell keeps its counters and gets its end", progress.unit("Personne", "SAP").written == 95L
			&& progress.unit("Personne", "SAP").endedAt == clock.now && RunProgress.DONE.equals(progress.unit("Personne", "SAP").state));
		RunProgressTest.check("cells in first-seen order", "Personne".equals(progress.units().get(0).row) && "Adresse".equals(progress.units().get(2).row));
	}

	private static void remainingFollowsTheMeasuredRate()
	{
		final Clock clock = new Clock();
		final RunProgress progress = RunProgress.newRun(RunProgressTest.STAGES);
		progress.clock(clock);
		progress.unitPlanned("Personne", "SAP");
		RunProgressTest.check("no rate yet: unknown", progress.remainingMs(clock.now) == null);
		progress.unitStart("Personne", "SAP", 200L);
		clock.now += 10000L;
		progress.unitProgress("Personne", "SAP", 50L, 50L, 0L);
		// 50 rows in 10 s -> 150 left at 200 ms a row = 30 s
		RunProgressTest.check("150 rows left at the measured rate", progress.remainingMs(clock.now).longValue() == 30000L);
	}

	private static void liveFieldsSurviveJson()
	{
		final Clock clock = new Clock();
		final RunProgress progress = RunProgress.newRun(RunProgressTest.STAGES);
		progress.clock(clock);
		progress.start("LOAD", clock.now);
		progress.activity("LOAD", "Personne \u00B7 SAP: 96 of 165 rows read.");
		progress.unitStart("Personne", "SAP", 165L);
		progress.unitProgress("Personne", "SAP", 96L, 95L, 1L);
		final RunProgress back = RunProgress.parse(progress.toJson(), RunProgressTest.STAGES);
		RunProgressTest.check("activity", "Personne \u00B7 SAP: 96 of 165 rows read.".equals(back.stage("LOAD").activity));
		RunProgressTest.check("units", back.unit("Personne", "SAP").total == 165L && back.unit("Personne", "SAP").held == 1L);
		RunProgressTest.check("journal", back.events().size() == 1);
		RunProgressTest.check("heartbeat", back.updatedAt() == clock.now);
		// lot V3-S (2026-10-04): the rows a load found already up to date travel with the cell and survive the JSON, ended or not
		progress.unitProgress("Personne", "SAP", 165L, 0L, 165L, 0L);
		progress.unitEnd("Personne", "SAP", true);
		final RunProgress unchangedBack = RunProgress.parse(progress.toJson(), RunProgressTest.STAGES);
		RunProgressTest.check("unchanged rows survive the JSON and the cell end", unchangedBack.unit("Personne", "SAP").unchanged == 165L
			&& unchangedBack.unit("Personne", "SAP").written == 0L && unchangedBack.unit("Personne", "SAP").read == 165L);
		RunProgressTest.check("the five-argument form keeps unchanged at zero", back.unit("Personne", "SAP").unchanged == 0L);
		final RunProgress old = RunProgress.parse("{\"stages\":[{\"key\":\"MODEL\",\"state\":\"DONE\"}]}", RunProgressTest.STAGES);
		RunProgressTest.check("a detail from before the lot still parses", RunProgress.DONE.equals(old.stage("MODEL").state) && old.units().isEmpty() && old.startedAt() == 0L);
	}

	/** Progress-throttle fix (2026-09-27): batches and activity lines at most once per interval; a stage or cell starting or ending at once. */
	private static void routineChangesAreThrottledTransitionsAreNot()
	{
		final Clock clock = new Clock();
		final RunProgress progress = RunProgress.newRun(RunProgressTest.STAGES);
		progress.clock(clock);
		final int[] writes = { 0 };
		final String[] last = { null };
		progress.onChange(() -> {
			writes[0]++;
			last[0] = progress.toJson();
		}, 1500L);
		progress.start("LOAD", clock.now);
		RunProgressTest.check("a stage start is written at once", writes[0] == 1);
		progress.unitStart("Personne", "SAP", 345L);
		RunProgressTest.check("a cell start is written at once", writes[0] == 2);
		for (int i = 1; i <= 10; i++)
		{
			clock.now += 100L;
			progress.unitProgress("Personne", "SAP", i * 10L, i * 10L, 0L);
			progress.activity("LOAD", "batch " + i);
		}
		RunProgressTest.check("20 routine changes in 1 s: none written yet", writes[0] == 2);
		clock.now += 600L;
		progress.unitProgress("Personne", "SAP", 120L, 120L, 0L);
		RunProgressTest.check("past the interval: one write carrying everything", writes[0] == 3 && last[0].contains("\"read\":120"));
		clock.now += 100L;
		progress.unitProgress("Personne", "SAP", 345L, 345L, 0L);
		RunProgressTest.check("throttled again", writes[0] == 3);
		progress.unitEnd("Personne", "SAP", true);
		RunProgressTest.check("a cell end is written at once, with the last counters", writes[0] == 4 && last[0].contains("\"read\":345"));
		final RunProgress plain = RunProgress.newRun(RunProgressTest.STAGES);
		final int[] every = { 0 };
		plain.onChange(() -> every[0]++);
		plain.note("a");
		plain.note("b");
		RunProgressTest.check("without an interval every change is written", every[0] == 2);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
