package com.on.ebx.factory.transfer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 1, "landing status per batch"): counts how many ROOT feeds of
 * one staging-load run read each landing table, so {@code ebx.StagingLoader} can tell the COMMON case - a
 * landing table read by exactly ONE root feed this run, the overwhelming majority of real projects - from the
 * RARE case where two or more root feeds share a landing table (a canonical entity and one of its members both
 * reading the SAME landing table, e.g. {@code Company} and {@code Supplier} both reading Companies'
 * {@code SUPPLIER} table).
 * <p>
 * <b>Why this matters for memory</b>: before this lot, {@code ebx.StagingLoader.PendingLandingStatus}
 * accumulated every root feed's {@code _status} decision for the WHOLE RUN, for EVERY landing table, only
 * writing it back once everything else had finished - a {@code Map&lt;landing row key, status&gt;} sized to
 * the run's total row count (~1 GB at 5M rows, per the 2026-09-28 audit). A landing table read by exactly ONE
 * root feed this run does not need that: nothing else will ever re-decide the SAME row this run, so its
 * {@code _status} can be written back at the end of EACH BATCH, bounded by the batch size, not the run's total
 * row count. Only a table this class calls "shared" still needs the whole-run accumulator, and only for its
 * own rows - see {@code PendingLandingStatus}'s own updated Javadoc for how the two paths fit together.
 * <p>
 * Pure, no EBX class: {@code ebx.StagingLoader} supplies the plain key strings (built the same way
 * {@code PendingLandingStatus} keys itself: {@link #key}) from walking {@code TransferPlan.Result#loadOrder}
 * once, before any row of the run is read.
 */
public final class LandingTableFanout
{
	private LandingTableFanout()
	{
	}

	/** Same key shape {@code ebx.StagingLoader.PendingLandingStatus} already used before this lot: {@code landingDataspace|landingDataset|landingTableName}. */
	public static String key(final String pLandingDataspace, final String pLandingDataset, final String pLandingTableName)
	{
		return pLandingDataspace + "|" + pLandingDataset + "|" + pLandingTableName;
	}

	/**
	 * How many times each key appears in {@code pRootFeedLandingTableKeys} - one entry per ROOT feed of the
	 * run (never a child feed - see the class Javadoc, a child feed never writes its own landing status), in
	 * any order, duplicates expected (that is exactly what this counts).
	 */
	public static Map<String, Integer> countReaders(final List<String> pRootFeedLandingTableKeys)
	{
		final Map<String, Integer> out = new HashMap<String, Integer>();
		for (final String key : pRootFeedLandingTableKeys)
		{
			final Integer current = out.get(key);
			out.put(key, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
		}
		return out;
	}

	/**
	 * True when more than one root feed of this run reads {@code pKey}'s landing table - the rare case that
	 * still needs whole-run accumulation (see the class Javadoc). A key absent from {@code pCounts} (should
	 * never happen - every key a caller asks about comes from the same walk that built the map) is treated as
	 * NOT shared, the safe default: a single, unaccumulated per-batch write-back, never an accidental promotion
	 * to the more expensive path.
	 */
	public static boolean isShared(final Map<String, Integer> pCounts, final String pKey)
	{
		final Integer count = pCounts.get(pKey);
		return count != null && count.intValue() > 1;
	}
}
