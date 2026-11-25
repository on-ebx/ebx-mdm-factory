package com.on.ebx.factory.ebx;

import com.on.ebx.factory.transfer.StagingRowDigest;

/**
 * JUnit-free: run the main, a failure throws. Lot IDX (2026-10-12): the staging index ({@code StagingLoadReads.Entity}) as a flat digest table answers what the {@code
 * HashMap<String, long[]>} did - absent key (null: a new row), present key with no digest ({@link StagingRowDigest#UNKNOWN}: read it), present key with its digests per
 * layout, the presence checks, a written row's entry reset to "unknown" (and a freshly inserted key present), and a key-only index when no digest is kept.
 */
public final class StagingLoadReadsCompactTest
{
	public static void main(final String[] pArgs)
	{
		StagingLoadReadsCompactTest.entriesPerLayout();
		StagingLoadReadsCompactTest.writtenRowsBecomeUnknownAndNewKeysArePresent();
		StagingLoadReadsCompactTest.keyOnlyIndex();
		StagingLoadReadsCompactTest.releaseDropsTheTableButKeepsTheSize();
		System.out.println("StagingLoadReadsCompactTest OK");
	}

	private static StagingLoadReads.Entity entity(final int pLayouts, final long pRows)
	{
		final StagingLoadReads.Entity entity = new StagingLoadReads(5000000L).entity("Personne");
		entity.installForTest(pLayouts, pRows);
		return entity;
	}

	private static void entriesPerLayout()
	{
		final StagingLoadReads.Entity entity = StagingLoadReadsCompactTest.entity(2, 10L);
		entity.putForTest("SAP|1", 11L, 12L, 13L, 14L);
		entity.putForTest("SAP|2", 21L, 22L); // second layout unknown
		entity.putForTest("SAP|3"); // no digest at all
		StagingLoadReadsCompactTest.check("an absent key is null (a new row)", entity.entry("SAP|9") == null && !entity.contains("SAP|9"));
		final long[] one = entity.entry("SAP|1");
		StagingLoadReadsCompactTest.check("both layouts' words", one.length == 4 && one[0] == 11L && one[1] == 12L && one[2] == 13L && one[3] == 14L);
		StagingLoadReadsCompactTest.check("the first layout matches its planned digest", StagingRowDigest.same(new long[] { 11L, 12L }, one, 0));
		StagingLoadReadsCompactTest.check("the second layout matches its planned digest", StagingRowDigest.same(new long[] { 13L, 14L }, one, 1));
		final long[] two = entity.entry("SAP|2");
		StagingLoadReadsCompactTest.check("a half-known entry: layout 0 can match, layout 1 never", StagingRowDigest.same(new long[] { 21L, 22L }, two, 0)
			&& !StagingRowDigest.same(new long[] { 0L, 0L }, two, 1) && !StagingRowDigest.same(new long[] { 23L, 24L }, two, 1));
		final long[] three = entity.entry("SAP|3");
		StagingLoadReadsCompactTest.check("an entry with no digest is the empty array: read the row", three.length == 0 && three == StagingRowDigest.UNKNOWN);
		StagingLoadReadsCompactTest.check("it is present all the same", entity.contains("SAP|3") && entity.contains("SAP|1"));
		StagingLoadReadsCompactTest.check("a duplicate is refused", !entity.putForTest("SAP|1", 1L, 2L, 3L, 4L));
	}

	private static void writtenRowsBecomeUnknownAndNewKeysArePresent()
	{
		final StagingLoadReads.Entity entity = StagingLoadReadsCompactTest.entity(1, 10L);
		entity.putForTest("SAP|1", 11L, 12L);
		entity.recordWritten("SAP|1");
		StagingLoadReadsCompactTest.check("the written row's digest is forgotten", entity.entry("SAP|1").length == 0 && entity.contains("SAP|1"));
		entity.recordWritten("SAP|NEW");
		StagingLoadReadsCompactTest.check("a key inserted by the load is present, with no digest", entity.contains("SAP|NEW") && entity.entry("SAP|NEW").length == 0);
		StagingLoadReadsCompactTest.check("counted", entity.writtenKeys == 2L);
		for (int i = 0; i < 5000; i++)
		{
			entity.recordWritten("SAP|BULK" + i); // far more than the 10 rows the index was sized for: it grows
		}
		StagingLoadReadsCompactTest.check("growth keeps the earlier entries", entity.contains("SAP|BULK4999") && entity.contains("SAP|NEW") && entity.contains("SAP|1"));
	}

	private static void keyOnlyIndex()
	{
		final StagingLoadReads.Entity entity = StagingLoadReadsCompactTest.entity(0, 10L);
		entity.putForTest("A|1");
		StagingLoadReadsCompactTest.check("present, no digest", entity.contains("A|1") && entity.entry("A|1").length == 0);
		StagingLoadReadsCompactTest.check("absent", entity.entry("A|2") == null);
		entity.recordWritten("A|2");
		StagingLoadReadsCompactTest.check("a written key is present", entity.contains("A|2"));
	}

	private static void releaseDropsTheTableButKeepsTheSize()
	{
		final StagingLoadReads.Entity entity = StagingLoadReadsCompactTest.entity(1, 1000L);
		entity.putForTest("A|1", 1L, 2L);
		entity.release();
		StagingLoadReadsCompactTest.check("released: no entry, no presence", entity.entry("A|1") == null && !entity.contains("A|1"));
		StagingLoadReadsCompactTest.check("the largest size is kept for the step detail", entity.indexBytes >= 8L * 4L * 1000L);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
