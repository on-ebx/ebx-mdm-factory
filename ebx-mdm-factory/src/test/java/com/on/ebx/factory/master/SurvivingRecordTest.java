package com.on.ebx.factory.master;

import java.util.HashMap;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. */
public final class SurvivingRecordTest
{
	public static void main(final String[] pArgs)
	{
		final Map<String, String> merges = new HashMap<String, String>();
		final SurvivingRecord.GoldenOf goldenOf = new SurvivingRecord.GoldenOf()
		{
			@Override
			public String goldenOf(final String pMasterKey)
			{
				return merges.get(pMasterKey);
			}
		};

		SurvivingRecordTest.check("a record never merged is its own target", "12".equals(SurvivingRecord.follow("12", goldenOf)));
		SurvivingRecordTest.check("null key stays null", SurvivingRecord.follow(null, goldenOf) == null);

		merges.put("12", "40");
		SurvivingRecordTest.check("a merged person: the address points to the golden", "40".equals(SurvivingRecord.follow("12", goldenOf)));

		merges.put("40", "55");
		SurvivingRecordTest.check("a golden merged again later: follow the whole chain", "55".equals(SurvivingRecord.follow("12", goldenOf)));

		merges.put("55", "");
		SurvivingRecordTest.check("a blank golden key ends the chain", "55".equals(SurvivingRecord.follow("12", goldenOf)));

		merges.put("55", "12");
		SurvivingRecordTest.check("a cycle stops instead of looping", "55".equals(SurvivingRecord.follow("12", goldenOf)));

		final Map<String, String> endless = new HashMap<String, String>();
		final SurvivingRecord.GoldenOf alwaysNext = new SurvivingRecord.GoldenOf()
		{
			@Override
			public String goldenOf(final String pMasterKey)
			{
				endless.put(pMasterKey, String.valueOf(Integer.parseInt(pMasterKey) + 1));
				return endless.get(pMasterKey);
			}
		};
		SurvivingRecordTest.check("the hop guard ends an unbounded chain", String.valueOf(SurvivingRecord.MAX_HOPS).equals(SurvivingRecord.follow("0", alwaysNext)));
		System.out.println("SurvivingRecordTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
