package com.on.ebx.factory.transfer;

import java.util.List;

/** JUnit-free, like every other test in this module: run the main, a failure throws. {@link BatchRanges} (volume lot 2a, 2026-09-28, task 8). */
public final class BatchRangesTest
{
	public static void main(final String[] pArgs)
	{
		BatchRangesTest.exactMultipleSplitsEvenly();
		BatchRangesTest.remainderGetsAShorterLastBatch();
		BatchRangesTest.fewerItemsThanOneBatch();
		BatchRangesTest.zeroOrNegativeTotalIsNoRangesAtAll();
		BatchRangesTest.zeroOrNegativeBatchSizeIsOneBigBatch();
		BatchRangesTest.rangesTileTheWholeSpaceWithNoGapOrOverlap();
		System.out.println("BatchRangesTest OK");
	}

	private static void exactMultipleSplitsEvenly()
	{
		final List<BatchRanges.Range> ranges = BatchRanges.of(3000, 1000);
		BatchRangesTest.check("3000/1000 is exactly 3 batches", ranges.size() == 3);
		BatchRangesTest.check("batch 1 is [0,1000)", ranges.get(0).start == 0 && ranges.get(0).end == 1000);
		BatchRangesTest.check("batch 2 is [1000,2000)", ranges.get(1).start == 1000 && ranges.get(1).end == 2000);
		BatchRangesTest.check("batch 3 is [2000,3000)", ranges.get(2).start == 2000 && ranges.get(2).end == 3000);
		BatchRangesTest.check("every batch is full-sized", ranges.get(0).size() == 1000 && ranges.get(2).size() == 1000);
	}

	private static void remainderGetsAShorterLastBatch()
	{
		final List<BatchRanges.Range> ranges = BatchRanges.of(2500, 1000);
		BatchRangesTest.check("2500/1000 is 3 batches (2 full + 1 partial)", ranges.size() == 3);
		BatchRangesTest.check("the last batch is only 500 wide", ranges.get(2).start == 2000 && ranges.get(2).end == 2500 && ranges.get(2).size() == 500);
	}

	private static void fewerItemsThanOneBatch()
	{
		final List<BatchRanges.Range> ranges = BatchRanges.of(7, 1000);
		BatchRangesTest.check("fewer items than the batch size: exactly one (short) batch", ranges.size() == 1);
		BatchRangesTest.check("that one batch covers [0,7)", ranges.get(0).start == 0 && ranges.get(0).end == 7);
	}

	private static void zeroOrNegativeTotalIsNoRangesAtAll()
	{
		BatchRangesTest.check("zero total: no ranges", BatchRanges.of(0, 1000).isEmpty());
		BatchRangesTest.check("negative total: no ranges, never a negative-length range or an exception", BatchRanges.of(-5, 1000).isEmpty());
	}

	private static void zeroOrNegativeBatchSizeIsOneBigBatch()
	{
		final List<BatchRanges.Range> zero = BatchRanges.of(50, 0);
		BatchRangesTest.check("batch size 0: everything in one batch, never an infinite loop", zero.size() == 1 && zero.get(0).start == 0 && zero.get(0).end == 50);
		final List<BatchRanges.Range> negative = BatchRanges.of(50, -3);
		BatchRangesTest.check("a negative batch size behaves the same as zero", negative.size() == 1 && negative.get(0).end == 50);
	}

	private static void rangesTileTheWholeSpaceWithNoGapOrOverlap()
	{
		final List<BatchRanges.Range> ranges = BatchRanges.of(9137, 1000);
		int expectedStart = 0;
		int total = 0;
		for (final BatchRanges.Range range : ranges)
		{
			BatchRangesTest.check("no gap: this range starts exactly where the previous one ended", range.start == expectedStart);
			BatchRangesTest.check("a range is never empty", range.size() > 0);
			BatchRangesTest.check("a range never exceeds the requested batch size", range.size() <= 1000);
			expectedStart = range.end;
			total += range.size();
		}
		BatchRangesTest.check("the ranges cover every item exactly once", total == 9137);
		BatchRangesTest.check("the last range ends exactly at the total", expectedStart == 9137);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
