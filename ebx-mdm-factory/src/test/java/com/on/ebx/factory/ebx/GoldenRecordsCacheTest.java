package com.on.ebx.factory.ebx;

/**
 * JUnit-free: run the main, a failure throws. Covers {@link GoldenRecordsCache} (wave 2, lot V2-A): the key changes with anything
 * that can change the figures, an entry expires, and the cache stays bounded.
 */
public final class GoldenRecordsCacheTest
{
	public static void main(final String[] pArgs)
	{
		GoldenRecordsCacheTest.keyChangesWithEveryInput();
		GoldenRecordsCacheTest.hitMissAndExpiry();
		GoldenRecordsCacheTest.staysBounded();
		System.out.println("GoldenRecordsCacheTest OK");
	}

	private static void keyChangesWithEveryInput()
	{
		final String base = GoldenRecordsCache.key("7", "Vol100k_Master", "Master", 3L, "MASTER_LOAD:1:DONE;");
		GoldenRecordsCacheTest.check("same inputs, same key", base.equals(GoldenRecordsCache.key("7", "Vol100k_Master", "Master", 3L, "MASTER_LOAD:1:DONE;")));
		GoldenRecordsCacheTest.check("project", !base.equals(GoldenRecordsCache.key("8", "Vol100k_Master", "Master", 3L, "MASTER_LOAD:1:DONE;")));
		GoldenRecordsCacheTest.check("dataspace", !base.equals(GoldenRecordsCache.key("7", "Other_Master", "Master", 3L, "MASTER_LOAD:1:DONE;")));
		GoldenRecordsCacheTest.check("dataset", !base.equals(GoldenRecordsCache.key("7", "Vol100k_Master", "Other", 3L, "MASTER_LOAD:1:DONE;")));
		GoldenRecordsCacheTest.check("epoch (a run or a decision)", !base.equals(GoldenRecordsCache.key("7", "Vol100k_Master", "Master", 4L, "MASTER_LOAD:1:DONE;")));
		GoldenRecordsCacheTest.check("change token (a master load)", !base.equals(GoldenRecordsCache.key("7", "Vol100k_Master", "Master", 3L, "MASTER_LOAD:2:DONE;")));
		GoldenRecordsCacheTest.check("null token is an empty token", GoldenRecordsCache.key("7", "a", "b", 0L, null).equals(GoldenRecordsCache.key("7", "a", "b", 0L, "")));
		GoldenRecordsCacheTest.check("no accidental collision by concatenation", !GoldenRecordsCache.key("1", "23", "x", 0L, "").equals(GoldenRecordsCache.key("12", "3", "x", 0L, "")));
	}

	private static void hitMissAndExpiry()
	{
		GoldenRecordsCache.clear();
		final GoldenRecordsReader.Result result = new GoldenRecordsReader.Result();
		result.goldenCount = 57568;
		GoldenRecordsCacheTest.check("miss", GoldenRecordsCache.get("k", 0L) == null);
		GoldenRecordsCache.put("k", result, 1000L);
		GoldenRecordsCacheTest.check("hit returns the same object", GoldenRecordsCache.get("k", 1000L + GoldenRecordsCache.MAX_AGE_MS) == result);
		GoldenRecordsCacheTest.check("expired", GoldenRecordsCache.get("k", 1000L + GoldenRecordsCache.MAX_AGE_MS + 1) == null);
		GoldenRecordsCacheTest.check("an expired entry is gone", GoldenRecordsCache.get("k", 1000L) == null);
	}

	private static void staysBounded()
	{
		GoldenRecordsCache.clear();
		final GoldenRecordsReader.Result result = new GoldenRecordsReader.Result();
		for (int i = 0; i < GoldenRecordsCache.MAX_ENTRIES + 10; i++)
		{
			GoldenRecordsCache.put("k" + i, result, 5L);
		}
		int present = 0;
		for (int i = 0; i < GoldenRecordsCache.MAX_ENTRIES + 10; i++)
		{
			if (GoldenRecordsCache.get("k" + i, 5L) != null)
			{
				present++;
			}
		}
		GoldenRecordsCacheTest.check("bounded", present == GoldenRecordsCache.MAX_ENTRIES);
		GoldenRecordsCacheTest.check("the oldest went", GoldenRecordsCache.get("k0", 5L) == null && GoldenRecordsCache.get("k" + (GoldenRecordsCache.MAX_ENTRIES + 9), 5L) != null);
		GoldenRecordsCache.clear();
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
