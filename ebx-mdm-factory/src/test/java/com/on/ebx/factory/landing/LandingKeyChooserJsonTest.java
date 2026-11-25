package com.on.ebx.factory.landing;

import java.util.Arrays;

/**
 * Pins the exact JSON shape {@code wizard.js}'s composite-key builder reads (coordinator follow-up,
 * 2026-09-24) - see {@link LandingKeyChooserJson}'s own Javadoc. JUnit-free: run the main, a failure throws.
 */
public final class LandingKeyChooserJsonTest
{
	public static void main(final String[] pArgs)
	{
		LandingKeyChooserJsonTest.pinsTheShape();
		LandingKeyChooserJsonTest.emptySampleIsAnEmptyArray();
		System.out.println("LandingKeyChooserJsonTest OK");
	}

	private static void pinsTheShape()
	{
		final KeyStudyReport report = new KeyStudyReport();
		report.sampleColumns.addAll(Arrays.asList("companyId", "contactNumber"));
		report.sampleRows.add(Arrays.asList("C-0012", "1"));
		report.sampleRows.add(Arrays.asList("C-0012", "2"));

		final String json = LandingKeyChooserJson.sample(report);
		LandingKeyChooserJsonTest.check("exact shape, one object per row, keyed by column name: " + json,
			"[{\"companyId\":\"C-0012\",\"contactNumber\":\"1\"},{\"companyId\":\"C-0012\",\"contactNumber\":\"2\"}]".equals(json));
	}

	private static void emptySampleIsAnEmptyArray()
	{
		final KeyStudyReport report = new KeyStudyReport();
		LandingKeyChooserJsonTest.check("no columns/rows -> []", "[]".equals(LandingKeyChooserJson.sample(report)));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
