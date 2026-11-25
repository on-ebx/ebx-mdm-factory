package com.on.ebx.factory.landing;

import java.util.Arrays;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class LandingRunProgressTest
{
	public static void main(final String[] pArgs)
	{
		LandingRunProgressTest.newRunIsAllTodo();
		LandingRunProgressTest.transitions();
		LandingRunProgressTest.isOverVersusFullyDone();
		LandingRunProgressTest.waitingTables();
		LandingRunProgressTest.stageCounts();
		LandingRunProgressTest.jsonRoundTrip();
		LandingRunProgressTest.parseNullOrGarbageIsSafe();
		LandingRunProgressTest.unknownTableIsIgnoredNotThrown();
		LandingRunProgressTest.waitingStageIsWaitingNotSkipped();
		LandingRunProgressTest.oneSourceFailingNeverBlocksAnotherSource();
		LandingRunProgressTest.everyChangeIsReportedAStartIncluded();
		LandingRunProgressTest.cellsCarryTheirTimesAndActivity();
		LandingRunProgressTest.journalKeepsTheLatestLines();
		LandingRunProgressTest.liveFieldsSurviveJson();
		LandingRunProgressTest.gridWrittenBeforeTheLotStillParses();
		LandingRunProgressTest.typicalDurationsAreMediansPerUnit();
		LandingRunProgressTest.estimateMovesInsideAStage();
		System.out.println("LandingRunProgressTest OK");
	}

	/** Coordinator follow-up round 3 (2026-09-24): a FLOW/LOAD cell blocked behind a WAITING key is WAITING, never SKIPPED - it will still run once the key resolves. */
	private static void waitingStageIsWaitingNotSkipped()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		progress.waiting("1", "no column is certain");
		progress.finish("1", LandingRunProgress.TABLE, true, "Built.");
		progress.waitingStage("1", LandingRunProgress.FLOW, "Waits for the key.");
		progress.waitingStage("1", LandingRunProgress.LOAD, "Waits for the key.");
		LandingRunProgressTest.check("KEY is WAITING", LandingRunProgress.WAITING.equals(progress.table("1").cell(LandingRunProgress.KEY).state));
		LandingRunProgressTest.check("FLOW is WAITING, not SKIPPED", LandingRunProgress.WAITING.equals(progress.table("1").cell(LandingRunProgress.FLOW).state));
		LandingRunProgressTest.check("LOAD is WAITING, not SKIPPED", LandingRunProgress.WAITING.equals(progress.table("1").cell(LandingRunProgress.LOAD).state));
		LandingRunProgressTest.check("FLOW summary carries the reason", "Waits for the key.".equals(progress.table("1").cell(LandingRunProgress.FLOW).summary));
		// a WAITING FLOW/LOAD is not counted "done" (doneStageCount), same rule as a WAITING KEY - it still needs a person
		LandingRunProgressTest.check("WAITING cells (any stage) never count as done", progress.doneStageCount() == 1 /* only TABLE */);
		// the run is still "over" once nothing is left TODO/RUNNING anywhere - a WAITING cell (any stage)
		// settles it exactly like SKIPPED did, only isFullyDone (checked below) tells the two apart
		progress.skip("2", LandingRunProgress.KEY, "n/a");
		progress.skip("2", LandingRunProgress.TABLE, "n/a");
		progress.skip("2", LandingRunProgress.FLOW, "n/a");
		progress.skip("2", LandingRunProgress.LOAD, "n/a");
		LandingRunProgressTest.check("nothing left TODO/RUNNING -> over, even with WAITING cells on table 1", progress.isOver());
		LandingRunProgressTest.check("a WAITING FLOW/LOAD -> not fully done", !progress.isFullyDone());

		// resuming: once the key resolves, waitingStage's own table moves on exactly like a plain skip would have
		progress.finish("1", LandingRunProgress.KEY, true, "chosen by a person");
		progress.finish("1", LandingRunProgress.FLOW, true, "Created.");
		progress.finish("1", LandingRunProgress.LOAD, true, "Loaded.");
		LandingRunProgressTest.check("fully done once every WAITING cell resolves", progress.isFullyDone());
	}

	/**
	 * fix/wizard-live-wave3 (2026-09-24, live bug item 8, measured live on project Vague1): "8 of 24 stages"
	 * frozen - SFDC tables WAITING, CRM tables never even started (stuck TODO on every stage). {@code
	 * ebx.LandingRun#runInBackground}'s per-source loop now catches {@code Throwable} around each SOURCE's own
	 * four stages, so one source's catastrophic failure marks only ITS OWN tables FAILED and moves on - the
	 * model asserted here is the invariant that fix relies on: nothing in {@link LandingRunProgress} couples
	 * one table's transitions to another's, so a table of one source can reach every stage {@code DONE} while
	 * a table of a completely different source sits {@code FAILED} (or, in the live case, {@code WAITING}).
	 * The Java {@code try}/{@code catch(Throwable)} control flow itself needs a live EBX {@code Session} and is
	 * not testable here (see the pure/ebx.* split the common brief requires) - this pins the data model it
	 * depends on instead.
	 */
	private static void oneSourceFailingNeverBlocksAnotherSource()
	{
		final List<LandingRunProgress.TableRef> refs = Arrays.asList(new LandingRunProgress.TableRef("sfdc1", "Account", "SFDC"),
			new LandingRunProgress.TableRef("sfdc2", "Contact", "SFDC"), new LandingRunProgress.TableRef("crm1", "Account", "CRM"),
			new LandingRunProgress.TableRef("crm2", "Contact", "CRM"));
		final LandingRunProgress progress = LandingRunProgress.newRun(refs);

		// SFDC's source blows up catastrophically (the per-source catch(Throwable) fires): every one of its
		// still-TODO cells is force-settled FAILED, exactly as ebx.LandingRun's catch block now does.
		for (final String tableId : new String[] { "sfdc1", "sfdc2" })
		{
			for (final String stage : LandingRunProgress.STAGES)
			{
				progress.finish(tableId, stage, false, "Unexpected error on this application - see ebxLog/kernel.log.");
			}
		}

		// CRM's source, processed independently (a different source, and in the live bug a LATER one in
		// iteration order), still reaches DONE on every one of its own four stages.
		for (final String tableId : new String[] { "crm1", "crm2" })
		{
			for (final String stage : LandingRunProgress.STAGES)
			{
				progress.start(tableId, stage);
				progress.finish(tableId, stage, true, "Done.");
			}
		}

		LandingRunProgressTest.check("SFDC's tables are FAILED, not stuck TODO",
			LandingRunProgress.FAILED.equals(progress.table("sfdc1").cell(LandingRunProgress.LOAD).state)
				&& LandingRunProgress.FAILED.equals(progress.table("sfdc2").cell(LandingRunProgress.LOAD).state));
		LandingRunProgressTest.check("CRM's tables reached DONE on every stage, unaffected by SFDC's failure",
			LandingRunProgress.DONE.equals(progress.table("crm1").cell(LandingRunProgress.LOAD).state)
				&& LandingRunProgress.DONE.equals(progress.table("crm2").cell(LandingRunProgress.LOAD).state));
		// nothing left TODO anywhere (both sources settled - one FAILED, one DONE, both count as "settled") -
		// the whole run is over, and doneStageCount counts DONE/FAILED/SKIPPED alike (see its own Javadoc).
		LandingRunProgressTest.check("the whole run is over: nothing left TODO/RUNNING", progress.isOver());
		LandingRunProgressTest.check("all 16 cells settled (8 FAILED for SFDC, 8 DONE for CRM)", progress.doneStageCount() == 16);
	}

	private static List<LandingRunProgress.TableRef> twoTables()
	{
		return Arrays.asList(new LandingRunProgress.TableRef("1", "BusinessPartner", "SAP"), new LandingRunProgress.TableRef("2", "Contact", "EBX"));
	}

	private static void newRunIsAllTodo()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		LandingRunProgressTest.check("2 tables", progress.tables().size() == 2);
		for (final LandingRunProgress.TableProgress table : progress.tables())
		{
			LandingRunProgressTest.check("4 cells", table.cells().size() == 4);
			for (final LandingRunProgress.Cell cell : table.cells())
			{
				LandingRunProgressTest.check(table.id + "/" + cell.stage + " starts TODO", LandingRunProgress.TODO.equals(cell.state));
			}
		}
		LandingRunProgressTest.check("total = 8", progress.totalStageCount() == 8);
	}

	private static void transitions()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		progress.start("1", LandingRunProgress.KEY);
		LandingRunProgressTest.check("RUNNING after start", LandingRunProgress.RUNNING.equals(progress.table("1").cell(LandingRunProgress.KEY).state));
		progress.finish("1", LandingRunProgress.KEY, true, "declared by the source");
		final LandingRunProgress.Cell done = progress.table("1").cell(LandingRunProgress.KEY);
		LandingRunProgressTest.check("DONE after finish(true)", LandingRunProgress.DONE.equals(done.state));
		LandingRunProgressTest.check("summary kept", "declared by the source".equals(done.summary));

		progress.start("1", LandingRunProgress.TABLE);
		progress.finish("1", LandingRunProgress.TABLE, false, "publish_data_model refused");
		LandingRunProgressTest.check("FAILED after finish(false)", LandingRunProgress.FAILED.equals(progress.table("1").cell(LandingRunProgress.TABLE).state));

		progress.skip("1", LandingRunProgress.FLOW, "the table step failed: nothing to build a flow on");
		final LandingRunProgress.Cell skipped = progress.table("1").cell(LandingRunProgress.FLOW);
		LandingRunProgressTest.check("SKIPPED after skip", LandingRunProgress.SKIPPED.equals(skipped.state));
		LandingRunProgressTest.check("skip reason in summary", skipped.summary.startsWith("the table step failed"));

		progress.waiting("2", "2 possible keys");
		final LandingRunProgress.Cell waiting = progress.table("2").cell(LandingRunProgress.KEY);
		LandingRunProgressTest.check("WAITING after waiting()", LandingRunProgress.WAITING.equals(waiting.state));
		LandingRunProgressTest.check("wait reason in summary", "2 possible keys".equals(waiting.summary));
	}

	private static void isOverVersusFullyDone()
	{
		final LandingRunProgress fresh = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		LandingRunProgressTest.check("fresh run is not over", !fresh.isOver());
		LandingRunProgressTest.check("fresh run is not fully done", !fresh.isFullyDone());

		final LandingRunProgress allSkipped = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		for (final LandingRunProgress.TableProgress table : allSkipped.tables())
		{
			for (final String stage : LandingRunProgress.STAGES)
			{
				allSkipped.skip(table.id, stage, "n/a");
			}
		}
		LandingRunProgressTest.check("every cell settled -> over", allSkipped.isOver());
		LandingRunProgressTest.check("every cell settled -> fully done", allSkipped.isFullyDone());

		// a WAITING key only settles the KEY cell - TABLE still runs (it does not need the key), FLOW/LOAD are
		// explicitly SKIPPED by the caller (ebx.LandingRun) rather than left TODO forever - see LandingRunProgress's
		// own "isOver" contract: nothing may stay TODO/RUNNING once nobody is ever coming back to it on its own.
		final LandingRunProgress oneWaiting = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		for (final String stage : LandingRunProgress.STAGES)
		{
			oneWaiting.skip("1", stage, "n/a");
		}
		oneWaiting.waiting("2", "no column is certain");
		oneWaiting.finish("2", LandingRunProgress.TABLE, true, "Built.");
		oneWaiting.skip("2", LandingRunProgress.FLOW, "Waiting for this application's key(s) to be confirmed.");
		oneWaiting.skip("2", LandingRunProgress.LOAD, "Waiting for this application's key(s) to be confirmed.");
		LandingRunProgressTest.check("nothing left to run -> over even with a WAITING table", oneWaiting.isOver());
		LandingRunProgressTest.check("a WAITING table -> not fully done", !oneWaiting.isFullyDone());

		final LandingRunProgress running = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		running.start("1", LandingRunProgress.KEY);
		LandingRunProgressTest.check("a RUNNING cell -> not over", !running.isOver());
	}

	private static void waitingTables()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		LandingRunProgressTest.check("no waiting table yet", !progress.hasWaiting());
		LandingRunProgressTest.check("empty waiting list", progress.waitingTableIds().isEmpty());
		progress.waiting("2", "2 possible keys");
		LandingRunProgressTest.check("has waiting", progress.hasWaiting());
		LandingRunProgressTest.check("waiting list names table 2", progress.waitingTableIds().equals(Arrays.asList("2")));
		// a table whose KEY later moves past WAITING (once a person applies a key) is no longer reported waiting
		progress.finish("2", LandingRunProgress.KEY, true, "chosen by a person");
		LandingRunProgressTest.check("no longer waiting once the key is resolved", !progress.hasWaiting());
	}

	private static void stageCounts()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		LandingRunProgressTest.check("nothing done yet", progress.doneStageCount() == 0);
		progress.finish("1", LandingRunProgress.KEY, true, "ok");
		progress.finish("1", LandingRunProgress.TABLE, false, "failed");
		progress.skip("1", LandingRunProgress.FLOW, "n/a");
		LandingRunProgressTest.check("DONE+FAILED+SKIPPED count", progress.doneStageCount() == 3);
		progress.waiting("2", "no column is certain");
		LandingRunProgressTest.check("WAITING never counts as done", progress.doneStageCount() == 3);
		LandingRunProgressTest.check("total unaffected", progress.totalStageCount() == 8);
	}

	private static void jsonRoundTrip()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		progress.start("1", LandingRunProgress.KEY);
		progress.finish("1", LandingRunProgress.KEY, true, "declared by the source");
		progress.waiting("2", "2 possible keys");
		final LandingRunProgress back = LandingRunProgress.parse(progress.toJson());
		LandingRunProgressTest.check("2 tables survive", back.tables().size() == 2);
		LandingRunProgressTest.check("table 1 id", "1".equals(back.table("1").id));
		LandingRunProgressTest.check("table 1 name", "BusinessPartner".equals(back.table("1").tableName));
		LandingRunProgressTest.check("table 1 source", "SAP".equals(back.table("1").sourceSystem));
		LandingRunProgressTest.check("table 1 KEY state", LandingRunProgress.DONE.equals(back.table("1").cell(LandingRunProgress.KEY).state));
		LandingRunProgressTest.check("table 1 KEY summary", "declared by the source".equals(back.table("1").cell(LandingRunProgress.KEY).summary));
		LandingRunProgressTest.check("table 2 WAITING", LandingRunProgress.WAITING.equals(back.table("2").cell(LandingRunProgress.KEY).state));
		LandingRunProgressTest.check("table 2 other cells still TODO", LandingRunProgress.TODO.equals(back.table("2").cell(LandingRunProgress.TABLE).state));
	}

	private static void parseNullOrGarbageIsSafe()
	{
		LandingRunProgressTest.check("null -> empty run", LandingRunProgress.parse(null).tables().isEmpty());
		LandingRunProgressTest.check("blank -> empty run", LandingRunProgress.parse("   ").tables().isEmpty());
		LandingRunProgressTest.check("garbage -> empty run", LandingRunProgress.parse("{not json").tables().isEmpty());
		LandingRunProgressTest.check("unrelated json -> empty run", LandingRunProgress.parse("{\"other\":1}").tables().isEmpty());
	}

	private static void unknownTableIsIgnoredNotThrown()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		progress.start("does-not-exist", LandingRunProgress.KEY);
		progress.finish("does-not-exist", LandingRunProgress.KEY, true, "irrelevant");
		progress.skip("does-not-exist", LandingRunProgress.FLOW, "irrelevant");
		progress.waiting("does-not-exist", "irrelevant");
		LandingRunProgressTest.check("still 2 known tables", progress.tables().size() == 2);
	}

	/** A fixed, settable clock for the live-progress tests. */
	private static final class Clock implements java.util.function.LongSupplier
	{
		long now = 1000000L;

		@Override
		public long getAsLong()
		{
			return this.now;
		}
	}

	private static LandingRunProgress clocked(final Clock pClock)
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables(), pClock.now);
		progress.clock(pClock);
		return progress;
	}

	/** Live-progress lot (2026-09-26): the job persists the grid from onChange - a stage's START must reach it, not only its end. */
	private static void everyChangeIsReportedAStartIncluded()
	{
		final LandingRunProgress progress = LandingRunProgress.newRun(LandingRunProgressTest.twoTables());
		final int[] calls = { 0 };
		final String[] lastJson = { null };
		progress.onChange(() -> {
			calls[0]++;
			lastJson[0] = progress.toJson();
		});
		progress.start("1", LandingRunProgress.KEY, "Looking for the key.");
		LandingRunProgressTest.check("the start is reported", calls[0] == 1);
		LandingRunProgressTest.check("what was persisted shows the cell RUNNING",
			LandingRunProgress.RUNNING.equals(LandingRunProgress.parse(lastJson[0]).table("1").cell(LandingRunProgress.KEY).state));
		progress.activity("1", LandingRunProgress.KEY, "Asking the language model.");
		progress.finish("1", LandingRunProgress.KEY, true, "ID");
		progress.note("hello");
		LandingRunProgressTest.check("activity, finish and note are reported too", calls[0] == 4);
		progress.onChange(() -> {
			throw new IllegalStateException("write failed");
		});
		progress.start("2", LandingRunProgress.KEY); // must not throw: a failed write never takes the run down
		LandingRunProgressTest.check("the change is applied even when the listener fails",
			LandingRunProgress.RUNNING.equals(progress.table("2").cell(LandingRunProgress.KEY).state));
	}

	private static void cellsCarryTheirTimesAndActivity()
	{
		final Clock clock = new Clock();
		final LandingRunProgress progress = LandingRunProgressTest.clocked(clock);
		progress.startAll(Arrays.asList("1", "2"), LandingRunProgress.TABLE, "Building the landing tables.");
		clock.now += 5000L;
		progress.activityAll(Arrays.asList("1", "2"), LandingRunProgress.TABLE, "Creating the landing model: 30 fields.");
		final LandingRunProgress.Cell running = progress.table("2").cell(LandingRunProgress.TABLE);
		LandingRunProgressTest.check("start time kept by an activity", running.startedAt == 1000000L);
		LandingRunProgressTest.check("the activity replaces the previous one", "Creating the landing model: 30 fields.".equals(running.activity));
		LandingRunProgressTest.check("running for 5 s", running.elapsedMs(clock.now) == 5000L);
		clock.now += 7000L;
		progress.finish("1", LandingRunProgress.TABLE, true, "Built.");
		final LandingRunProgress.Cell done = progress.table("1").cell(LandingRunProgress.TABLE);
		LandingRunProgressTest.check("a finished cell keeps its start and gets its end", done.startedAt == 1000000L && done.endedAt == 1012000L);
		LandingRunProgressTest.check("and loses its activity", done.activity == null);
		LandingRunProgressTest.check("elapsed of a finished cell is its duration", done.elapsedMs(9999999L) == 12000L);
		progress.activity("1", LandingRunProgress.TABLE, "late line");
		LandingRunProgressTest.check("an activity on a settled cell is ignored", progress.table("1").cell(LandingRunProgress.TABLE).activity == null);
		progress.skip("1", LandingRunProgress.FLOW, "n/a");
		LandingRunProgressTest.check("a stage skipped without starting has no times",
			progress.table("1").cell(LandingRunProgress.FLOW).startedAt == 0L && progress.table("1").cell(LandingRunProgress.FLOW).endedAt == 0L);
		LandingRunProgressTest.check("the running cell of the headline is table 2's TABLE", progress.currentCell() == progress.table("2").cell(LandingRunProgress.TABLE));
		LandingRunProgressTest.check("updatedAt follows the last change", progress.updatedAt() == clock.now);
	}

	private static void journalKeepsTheLatestLines()
	{
		final Clock clock = new Clock();
		final LandingRunProgress progress = LandingRunProgressTest.clocked(clock);
		progress.startAll(Arrays.asList("1", "2"), LandingRunProgress.TABLE, "Building.");
		LandingRunProgressTest.check("a source-wide start is ONE line naming both tables",
			progress.events().size() == 1 && progress.events().get(0).text.startsWith("BusinessPartner, Contact"));
		progress.finish("1", LandingRunProgress.TABLE, false, "model refused");
		LandingRunProgressTest.check("newest first, a failure says so", progress.events().get(0).text.contains("Table failed: model refused"));
		for (int i = 0; i < 30; i++)
		{
			clock.now++;
			progress.note("line " + i);
		}
		LandingRunProgressTest.check("bounded", progress.events().size() == LandingRunProgress.MAX_EVENTS);
		LandingRunProgressTest.check("the newest line is kept", "line 29".equals(progress.events().get(0).text));
		final StringBuilder longLine = new StringBuilder();
		for (int i = 0; i < 500; i++)
		{
			longLine.append('x');
		}
		progress.note(longLine.append("\nsecond line").toString());
		LandingRunProgressTest.check("a line is one line, capped", progress.events().get(0).text.length() <= 240 && !progress.events().get(0).text.contains("\n"));
	}

	private static void liveFieldsSurviveJson()
	{
		final Clock clock = new Clock();
		final LandingRunProgress progress = LandingRunProgressTest.clocked(clock);
		progress.start("1", LandingRunProgress.KEY, "Looking for the key.");
		clock.now += 3000L;
		progress.finish("1", LandingRunProgress.KEY, true, "ID (declared by the source)");
		progress.start("2", LandingRunProgress.KEY, "Asking the language model.");
		final LandingRunProgress back = LandingRunProgress.parse(progress.toJson());
		LandingRunProgressTest.check("run start", back.startedAt() == 1000000L);
		LandingRunProgressTest.check("run heartbeat", back.updatedAt() == clock.now);
		LandingRunProgressTest.check("cell times", back.table("1").cell(LandingRunProgress.KEY).startedAt == 1000000L && back.table("1").cell(LandingRunProgress.KEY).endedAt == 1003000L);
		LandingRunProgressTest.check("cell activity", "Asking the language model.".equals(back.table("2").cell(LandingRunProgress.KEY).activity));
		LandingRunProgressTest.check("journal", back.events().size() == progress.events().size() && back.events().get(0).text.equals(progress.events().get(0).text));
	}

	private static void gridWrittenBeforeTheLotStillParses()
	{
		final String old = "{\"tables\":[{\"id\":\"1\",\"tableName\":\"T\",\"sourceSystem\":\"S\",\"cells\":{\"KEY\":{\"state\":\"DONE\",\"summary\":\"ID\"}}}]}";
		final LandingRunProgress back = LandingRunProgress.parse(old);
		LandingRunProgressTest.check("old grid: cells read", LandingRunProgress.DONE.equals(back.table("1").cell(LandingRunProgress.KEY).state));
		LandingRunProgressTest.check("old grid: no times, no journal", back.startedAt() == 0L && back.events().isEmpty()
			&& back.table("1").cell(LandingRunProgress.KEY).startedAt == 0L);
		LandingRunProgressTest.check("old grid: no sample for the estimates", LandingRunProgress.typicalDurations(Arrays.asList(back)).isEmpty());
	}

	private static LandingRunProgress finishedRun(final long pKeyMs, final long pTableMs)
	{
		final Clock clock = new Clock();
		final LandingRunProgress run = LandingRunProgress.newRun(Arrays.asList(new LandingRunProgress.TableRef("1", "A", "SAP"),
			new LandingRunProgress.TableRef("2", "B", "SAP")), clock.now);
		run.clock(clock);
		run.start("1", LandingRunProgress.KEY);
		clock.now += pKeyMs;
		run.finish("1", LandingRunProgress.KEY, true, "k");
		run.start("2", LandingRunProgress.KEY);
		clock.now += pKeyMs;
		run.finish("2", LandingRunProgress.KEY, true, "k");
		run.startAll(Arrays.asList("1", "2"), LandingRunProgress.TABLE, "t");
		clock.now += pTableMs;
		run.finish("1", LandingRunProgress.TABLE, true, "t");
		run.finish("2", LandingRunProgress.TABLE, true, "t");
		return run;
	}

	private static void typicalDurationsAreMediansPerUnit()
	{
		final java.util.Map<String, Long> typical = LandingRunProgress.typicalDurations(Arrays.asList(LandingRunProgressTest.finishedRun(2000L, 90000L),
			LandingRunProgressTest.finishedRun(4000L, 60000L), LandingRunProgressTest.finishedRun(30000L, 100000L)));
		// KEY: 6 samples (2 tables x 3 runs) 2,2,4,4,30,30 s -> upper median 4 s; TABLE: ONE sample per source and run (not per row) 60, 90, 100 -> 90 s
		LandingRunProgressTest.check("KEY median", typical.get(LandingRunProgress.KEY).longValue() == 4000L);
		LandingRunProgressTest.check("TABLE median, one sample per source", typical.get(LandingRunProgress.TABLE).longValue() == 90000L);
		LandingRunProgressTest.check("no sample, no entry", !typical.containsKey(LandingRunProgress.LOAD));
	}

	private static void estimateMovesInsideAStage()
	{
		final java.util.Map<String, Long> typical = new java.util.HashMap<String, Long>();
		typical.put(LandingRunProgress.KEY, Long.valueOf(10000L));
		typical.put(LandingRunProgress.TABLE, Long.valueOf(100000L));
		typical.put(LandingRunProgress.FLOW, Long.valueOf(2000L));
		typical.put(LandingRunProgress.LOAD, Long.valueOf(40000L));
		final Clock clock = new Clock();
		// two tables of ONE source: units = KEY x2, TABLE x1, FLOW x2, LOAD x1 = 6
		final LandingRunProgress progress = LandingRunProgress.newRun(Arrays.asList(new LandingRunProgress.TableRef("1", "A", "SAP"),
			new LandingRunProgress.TableRef("2", "B", "SAP")), clock.now);
		progress.clock(clock);
		LandingRunProgress.Estimate estimate = progress.estimate(typical, clock.now);
		LandingRunProgressTest.check("nothing done: 0 %", estimate.percent == 0);
		LandingRunProgressTest.check("all to do: 10+10+100+2+2+40 s", estimate.remainingMs.longValue() == 164000L);
		progress.start("1", LandingRunProgress.KEY);
		clock.now += 5000L;
		estimate = progress.estimate(typical, clock.now);
		LandingRunProgressTest.check("half of one unit of six: 8 %", estimate.percent == 8);
		LandingRunProgressTest.check("the running unit counts its rest only", estimate.remainingMs.longValue() == 159000L);
		clock.now += 60000L;
		estimate = progress.estimate(typical, clock.now);
		LandingRunProgressTest.check("an overrunning stage never passes 90 % of its share", estimate.percent == 15);
		LandingRunProgressTest.check("and adds nothing left", estimate.remainingMs.longValue() == 154000L);
		progress.finish("1", LandingRunProgress.KEY, true, "k");
		progress.waiting("2", "not certain");
		estimate = progress.estimate(typical, clock.now);
		LandingRunProgressTest.check("a waiting unit is not work left", estimate.remainingMs.longValue() == 144000L);
		typical.remove(LandingRunProgress.LOAD);
		LandingRunProgressTest.check("unknown as soon as a stage has no history", progress.estimate(typical, clock.now).remainingMs == null);
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
