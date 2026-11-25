package com.on.ebx.factory.transfer;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure "split N items into batches of at most a given size" decision - volume lot 2a (2026-09-28,
 * docs/VOLUME.md, task 8), pulled out of {@code ebx.VolumeBatchOps}/{@code ebx.GoldenKeyRefresher} so the
 * batch-count math itself is unit-tested without EBX. Each {@link Range} is a half-open {@code [start, end)}
 * pair over an index space of size {@code pTotal} (e.g. a key list's indices); the caller does whatever
 * per-range work it needs (a {@code List.subList}, a page of a paged query, ...).
 */
public final class BatchRanges
{
	public static final class Range
	{
		public final int start;
		public final int end;

		public Range(final int pStart, final int pEnd)
		{
			this.start = pStart;
			this.end = pEnd;
		}

		public int size()
		{
			return this.end - this.start;
		}
	}

	private BatchRanges()
	{
	}

	/**
	 * {@code pTotal <= 0}: no ranges at all. {@code pBatchSize <= 0}: treated as "everything in one batch" (a
	 * single {@code [0, pTotal)} range) - never an exception, never an infinite loop, never a zero-size batch.
	 */
	public static List<Range> of(final int pTotal, final int pBatchSize)
	{
		final List<Range> out = new ArrayList<Range>();
		if (pTotal <= 0)
		{
			return out;
		}
		final int size = pBatchSize > 0 ? pBatchSize : pTotal;
		for (int start = 0; start < pTotal; start += size)
		{
			out.add(new Range(start, Math.min(start + size, pTotal)));
		}
		return out;
	}
}
