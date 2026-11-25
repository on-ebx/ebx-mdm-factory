package com.on.ebx.factory.distribution;

import java.util.Arrays;
import java.util.LinkedHashSet;

/** JUnit-free: run the main, a failure throws. */
public final class MergeLogStateTest
{
	public static void main(final String[] pArgs)
	{
		MergeLogStateTest.theOldShapeStillParses();
		MergeLogStateTest.roundTripKeepsEveryMark();
		MergeLogStateTest.malformedTextIsAnEmptyState();
		MergeLogStateTest.aNewTableGetsItsBaselineAnExistingOneKeepsIt();
		MergeLogStateTest.sweepFactsDoNotTurnIntoATable();
		System.out.println("MergeLogStateTest OK");
	}

	private static void theOldShapeStillParses()
	{
		final MergeLogState state = MergeLogState.parse("{\"/root/Personne\":{\"last\":4,\"undone\":[2,3]}}");
		final MergeLogState.TableMark mark = state.table("/root/Personne");
		MergeLogStateTest.check("last", mark.last == 4L);
		MergeLogStateTest.check("undone", mark.undone.size() == 2 && mark.undone.contains(Long.valueOf(3L)));
		MergeLogStateTest.check("no baseline in the old shape", mark.baseline == null && mark.swept == null);
		MergeLogStateTest.check("no sweep facts", state.sweep().at.isEmpty());
		MergeLogStateTest.check("the watch sees its watermark", state.watermark("/root/Personne").lastMergeId == 4L);
		MergeLogStateTest.check("an unknown table has none", state.watermark("/root/Adresse") == null);
	}

	private static void roundTripKeepsEveryMark()
	{
		final MergeLogState state = new MergeLogState();
		state.putWatermark("/root/Personne", new MergeLogWatch.Watermark(7L, new LinkedHashSet<Long>(Arrays.asList(Long.valueOf(2L)))));
		state.tableOrCreate("/root/Personne").swept = Long.valueOf(7L);
		state.sweep().at = "2026-10-18T10:00:00Z";
		state.sweep().queued = 5L;
		state.sweep().stamps.put("/root/Personne", Long.valueOf(1760781600000L));
		final MergeLogState back = MergeLogState.parse(state.format());
		MergeLogStateTest.check("last", back.table("/root/Personne").last == 7L);
		MergeLogStateTest.check("baseline", back.table("/root/Personne").baseline.longValue() == 7L);
		MergeLogStateTest.check("swept", back.table("/root/Personne").swept.longValue() == 7L);
		MergeLogStateTest.check("time", "2026-10-18T10:00:00Z".equals(back.sweep().at));
		MergeLogStateTest.check("queued", back.sweep().queued == 5L);
		MergeLogStateTest.check("stamp", back.sweep().stamps.get("/root/Personne").longValue() == 1760781600000L);
		MergeLogStateTest.check("stable text", back.format().equals(state.format()));
	}

	private static void malformedTextIsAnEmptyState()
	{
		MergeLogStateTest.check("garbage", MergeLogState.parse("not json").tables().isEmpty());
		MergeLogStateTest.check("blank", MergeLogState.parse("  ").tables().isEmpty());
		MergeLogStateTest.check("null", MergeLogState.parse(null).tables().isEmpty());
		MergeLogStateTest.check("an array", MergeLogState.parse("[1,2]").tables().isEmpty());
	}

	/** The baseline is where the log stood when the table was first met - never moved by a later watermark. */
	private static void aNewTableGetsItsBaselineAnExistingOneKeepsIt()
	{
		final MergeLogState state = new MergeLogState();
		state.putWatermark("/root/Personne", new MergeLogWatch.Watermark(7L, null));
		state.putWatermark("/root/Personne", new MergeLogWatch.Watermark(12L, null));
		MergeLogStateTest.check("baseline stays at the first value", state.table("/root/Personne").baseline.longValue() == 7L);
		MergeLogStateTest.check("last moved", state.table("/root/Personne").last == 12L);
		final MergeLogState legacy = MergeLogState.parse("{\"/root/Personne\":{\"last\":4,\"undone\":[]}}");
		legacy.putWatermark("/root/Personne", new MergeLogWatch.Watermark(9L, null));
		MergeLogStateTest.check("a field written before this lot gets no baseline invented", legacy.table("/root/Personne").baseline == null);
	}

	private static void sweepFactsDoNotTurnIntoATable()
	{
		final MergeLogState state = MergeLogState.parse("{\"_sweep\":{\"at\":\"x\",\"queued\":2,\"stamps\":{}}}");
		MergeLogStateTest.check("no table named _sweep", state.tables().isEmpty());
		MergeLogStateTest.check("facts read", "x".equals(state.sweep().at) && state.sweep().queued == 2L);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("MergeLogStateTest: " + pWhat);
		}
	}
}
