package com.on.ebx.factory.master;

import java.util.HashMap;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws. {@link GoldenKeyScanPlan} (lot V3-M3, 2026-10-04): every state of a record, a chain, a cycle, a record without
 * metadata, a golden key already right - and the same answer as {@link GoldenKeyRefreshDecision} fed the per-record way.
 */
public final class GoldenKeyScanPlanTest
{
	private static final int UNSET = 0;
	private static final int GOLDEN = 1;
	private static final int MERGED = 2;
	private static final int PENDING = 3;
	private static final int DELETED = 4;

	public static void main(final String[] pArgs)
	{
		GoldenKeyScanPlanTest.encoding();
		GoldenKeyScanPlanTest.eachStateOfARecord();
		GoldenKeyScanPlanTest.aMergedRecordPointsAtItsGolden();
		GoldenKeyScanPlanTest.aTwoHopChainEndsAtTheSurvivor();
		GoldenKeyScanPlanTest.aCycleStops();
		GoldenKeyScanPlanTest.aRecordWithoutMetadata();
		GoldenKeyScanPlanTest.aMergedRecordWithoutGoldenIdFallsBackToItsOwnKey();
		GoldenKeyScanPlanTest.aKeyAlreadyRightIsNotWritten();
		GoldenKeyScanPlanTest.countersAndWritesAddUp();
		GoldenKeyScanPlanTest.sameAnswerAsThePerRecordDecision();
		System.out.println("GoldenKeyScanPlanTest OK");
	}

	private static Map<String, String> index(final Object... pKeyStateGolden)
	{
		final Map<String, String> index = new HashMap<String, String>();
		for (int i = 0; i < pKeyStateGolden.length; i += 3)
		{
			index.put((String) pKeyStateGolden[i], GoldenKeyScanPlan.encode(((Integer) pKeyStateGolden[i + 1]).intValue(), (String) pKeyStateGolden[i + 2]));
		}
		return index;
	}

	private static void encoding()
	{
		GoldenKeyScanPlanTest.check("a golden record is present", GoldenKeyScanPlan.PRESENT.equals(GoldenKeyScanPlan.encode(GoldenKeyScanPlanTest.GOLDEN, "7")));
		GoldenKeyScanPlanTest.check("a golden id of a non merged state is ignored", GoldenKeyScanPlan.PRESENT.equals(GoldenKeyScanPlan.encode(GoldenKeyScanPlanTest.DELETED, "7")));
		GoldenKeyScanPlanTest.check("merged: the golden itself", "40".equals(GoldenKeyScanPlan.encode(GoldenKeyScanPlanTest.MERGED, " 40 ")));
		GoldenKeyScanPlanTest.check("merged, blank golden", GoldenKeyScanPlan.MERGED_NO_GOLDEN.equals(GoldenKeyScanPlan.encode(GoldenKeyScanPlanTest.MERGED, "  ")));
		GoldenKeyScanPlanTest.check("merged, null golden", GoldenKeyScanPlan.MERGED_NO_GOLDEN.equals(GoldenKeyScanPlan.encode(GoldenKeyScanPlanTest.MERGED, null)));
	}

	private static void eachStateOfARecord()
	{
		final int[] states = { GoldenKeyScanPlanTest.UNSET, GoldenKeyScanPlanTest.GOLDEN, GoldenKeyScanPlanTest.PENDING, GoldenKeyScanPlanTest.DELETED };
		for (final int state : states)
		{
			final GoldenKeyScanPlan blank = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("5", Integer.valueOf(state), null));
			final GoldenKeyRefreshDecision.Decision fresh = blank.accept("x|5", "5", null);
			GoldenKeyScanPlanTest.check("state " + state + ": own golden, blank goldenKey -> SET its own key",
				fresh.action == GoldenKeyRefreshDecision.Action.SET && "5".equals(fresh.newGoldenKey));
			final GoldenKeyRefreshDecision.Decision stale = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("5", Integer.valueOf(state), null)).accept("x|5", "5", "9");
			GoldenKeyScanPlanTest.check("state " + state + ": a wrong goldenKey is reset to the own key", stale.action == GoldenKeyRefreshDecision.Action.SET && "5".equals(stale.newGoldenKey));
		}
	}

	private static void aMergedRecordPointsAtItsGolden()
	{
		final GoldenKeyScanPlan plan = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("12", Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "40", "40",
			Integer.valueOf(GoldenKeyScanPlanTest.GOLDEN), null));
		final GoldenKeyRefreshDecision.Decision d = plan.accept("x|12", "12", "12");
		GoldenKeyScanPlanTest.check("merged: SET the golden", d.action == GoldenKeyRefreshDecision.Action.SET && "40".equals(d.newGoldenKey));
		GoldenKeyScanPlanTest.check("the write is recorded", plan.writeCount() == 1 && "x|12".equals(plan.writeKey(0)) && "40".equals(plan.writeValue(0)));
	}

	private static void aTwoHopChainEndsAtTheSurvivor()
	{
		final GoldenKeyScanPlan plan = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("12", Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "40", "40",
			Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "55", "55", Integer.valueOf(GoldenKeyScanPlanTest.GOLDEN), null));
		GoldenKeyScanPlanTest.check("12 -> 40 -> 55: the survivor", "55".equals(plan.accept("x|12", "12", null).newGoldenKey));
		GoldenKeyScanPlanTest.check("40 -> 55 as well", "55".equals(plan.accept("x|40", "40", null).newGoldenKey));
		GoldenKeyScanPlanTest.check("55 is its own", "55".equals(plan.accept("x|55", "55", null).newGoldenKey));
	}

	private static void aCycleStops()
	{
		final GoldenKeyScanPlan plan = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("1", Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "2", "2",
			Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "1"));
		final GoldenKeyRefreshDecision.Decision d = plan.accept("x|1", "1", null); // must return, not spin
		GoldenKeyScanPlanTest.check("a cycle ends on the last record before it repeats", d.action == GoldenKeyRefreshDecision.Action.SET && "2".equals(d.newGoldenKey));
		final GoldenKeyScanPlan self = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("3", Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "3"));
		GoldenKeyScanPlanTest.check("a record merged into itself keeps its own key", "3".equals(self.accept("x|3", "3", null).newGoldenKey));
	}

	private static void aRecordWithoutMetadata()
	{
		final GoldenKeyScanPlan plan = new GoldenKeyScanPlan(new HashMap<String, String>());
		GoldenKeyScanPlanTest.check("no metadata, blank goldenKey: unchanged", plan.accept("x|8", "8", null).action == GoldenKeyRefreshDecision.Action.NO_CHANGE);
		GoldenKeyScanPlanTest.check("no metadata, blank string: unchanged", plan.accept("x|8", "8", "  ").action == GoldenKeyRefreshDecision.Action.NO_CHANGE);
		final GoldenKeyRefreshDecision.Decision cleared = plan.accept("x|9", "9", "9");
		GoldenKeyScanPlanTest.check("no metadata, a stale goldenKey: CLEAR", cleared.action == GoldenKeyRefreshDecision.Action.CLEAR);
		GoldenKeyScanPlanTest.check("a null master key reads as no metadata", plan.accept("x|n", null, "5").action == GoldenKeyRefreshDecision.Action.CLEAR);
		GoldenKeyScanPlanTest.check("the clear is a write of null", plan.writeCount() == 2 && plan.writeValue(0) == null && plan.writeValue(1) == null);
	}

	private static void aMergedRecordWithoutGoldenIdFallsBackToItsOwnKey()
	{
		final GoldenKeyScanPlan plan = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("6", Integer.valueOf(GoldenKeyScanPlanTest.MERGED), ""));
		final GoldenKeyRefreshDecision.Decision d = plan.accept("x|6", "6", null);
		GoldenKeyScanPlanTest.check("merged, no golden id: the per-record path also used the own key", d.action == GoldenKeyRefreshDecision.Action.SET && "6".equals(d.newGoldenKey));
		// a chain that arrives at such a record stops there
		final GoldenKeyScanPlan chain = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("6", Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "",
			"7", Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "6"));
		GoldenKeyScanPlanTest.check("a chain ends at a merged record that has no golden", "6".equals(chain.accept("x|7", "7", null).newGoldenKey));
	}

	private static void aKeyAlreadyRightIsNotWritten()
	{
		final GoldenKeyScanPlan plan = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("12", Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "40", "40",
			Integer.valueOf(GoldenKeyScanPlanTest.GOLDEN), null));
		GoldenKeyScanPlanTest.check("merged, goldenKey already the golden", plan.accept("x|12", "12", "40").action == GoldenKeyRefreshDecision.Action.NO_CHANGE);
		GoldenKeyScanPlanTest.check("golden, goldenKey already its own", plan.accept("x|40", "40", "40").action == GoldenKeyRefreshDecision.Action.NO_CHANGE);
		GoldenKeyScanPlanTest.check("nothing to write", plan.writeCount() == 0 && plan.unchanged() == 2);
	}

	private static void countersAndWritesAddUp()
	{
		final GoldenKeyScanPlan plan = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("1", Integer.valueOf(GoldenKeyScanPlanTest.GOLDEN), null, "2",
			Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "1"));
		plan.accept("x|1", "1", "1"); // unchanged
		plan.accept("x|2", "2", null); // set
		plan.accept("x|3", "3", "3"); // cleared (no metadata)
		plan.accept("x|4", "4", null); // unchanged (no metadata, blank)
		GoldenKeyScanPlanTest.check("counters", plan.set() == 1 && plan.cleared() == 1 && plan.unchanged() == 2);
		GoldenKeyScanPlanTest.check("only the changed rows are kept", plan.writeCount() == 2 && "x|2".equals(plan.writeKey(0)) && "x|3".equals(plan.writeKey(1)));
	}

	/** For a record with no chain, the scan answers exactly what the per-record path fed to the decision answered. */
	private static void sameAnswerAsThePerRecordDecision()
	{
		final Object[][] cases = { { Integer.valueOf(GoldenKeyScanPlanTest.GOLDEN), null }, { Integer.valueOf(GoldenKeyScanPlanTest.DELETED), null },
			{ Integer.valueOf(GoldenKeyScanPlanTest.PENDING), null }, { Integer.valueOf(GoldenKeyScanPlanTest.MERGED), "99" } };
		final String[] currents = { null, "", "5", "99" };
		for (final Object[] c : cases)
		{
			for (final String current : currents)
			{
				final int state = ((Integer) c[0]).intValue();
				final String golden = (String) c[1];
				final GoldenKeyRefreshDecision.Decision expected = GoldenKeyRefreshDecision.decide(true, state == GoldenKeyScanPlanTest.MERGED ? golden : null, "5", current);
				final GoldenKeyRefreshDecision.Decision actual = new GoldenKeyScanPlan(GoldenKeyScanPlanTest.index("5", Integer.valueOf(state), golden)).accept("x|5", "5", current);
				GoldenKeyScanPlanTest.check("state " + state + ", current '" + current + "'", expected.action == actual.action
					&& (expected.newGoldenKey == null ? actual.newGoldenKey == null : expected.newGoldenKey.equals(actual.newGoldenKey)));
			}
		}
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
