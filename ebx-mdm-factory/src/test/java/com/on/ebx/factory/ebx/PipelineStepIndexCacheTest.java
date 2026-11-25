package com.on.ebx.factory.ebx;

import java.util.Date;

/** Robustness audit F2-104: the step index of a project is read once per table change, not once per render; DONE steps age out on their own, adjustable age. */
public final class PipelineStepIndexCacheTest
{
	public static void main(final String[] pArgs)
	{
		PipelineStepIndex.clearCache();
		final int[] loads = new int[1];
		final PipelineStepIndex.Loader loader = new PipelineStepIndex.Loader()
		{
			@Override
			public PipelineStepIndex.Latest<com.onwbp.adaptation.Adaptation> load()
			{
				loads[0]++;
				return new PipelineStepIndex.Latest<com.onwbp.adaptation.Adaptation>();
			}
		};
		final String key = PipelineStepIndex.cacheKey("P1", "U1", "1000");
		for (int i = 0; i < 3; i++)
		{
			PipelineStepIndex.cachedOrLoad(key, loader);
		}
		check("three renders read PipelineStep once", loads[0] == 1);
		PipelineStepIndex.cachedOrLoad(PipelineStepIndex.cacheKey("P1", "U1", "1001"), loader);
		check("a new modification date of the table reads again", loads[0] == 2);
		PipelineStepIndex.cachedOrLoad(PipelineStepIndex.cacheKey("P2", "U1", "1001"), loader);
		check("another project has its own index", loads[0] == 3);
		PipelineStepIndex.cachedOrLoad(PipelineStepIndex.cacheKey("P2", "U2", "1001"), loader);
		check("another viewer has its own index (permissions)", loads[0] == 4);
		PipelineStepIndex.cachedOrLoad(PipelineStepIndex.cacheKey("P2", "U2", null), loader);
		PipelineStepIndex.cachedOrLoad(PipelineStepIndex.cacheKey("P2", "U2", null), loader);
		check("an unreadable date is never cached", loads[0] == 6);

		final PipelineStepIndex.Latest<String> latest = new PipelineStepIndex.Latest<String>();
		final String k = PipelineStepIndex.key("MASTER_LOAD", null);
		latest.offer(k, "a", new Date(1L), "DONE");
		latest.offer(k, "b", new Date(2L), "RUNNING");
		check("latest is the later start", "b".equals(latest.get(k)));
		check("an earlier DONE still counts as done", latest.everDone(k));
		check("a RUNNING row is seen", latest.isRunning(k));
		check("rows are counted", latest.count(k) == 2 && latest.count("zz") == 0);

		// retention: the age of DONE rows is adjustable, 90 days by default
		check("default DONE age", FactoryRetention.doneStepDays(null) == 90 && FactoryRetention.doneStepDays("x") == 90 && FactoryRetention.doneStepDays("0") == 90);
		check("set DONE age", FactoryRetention.doneStepDays(" 14 ") == 14);
		final long day = 24L * 3600L * 1000L;
		final long now = 1000L * day;
		final FactoryRetention.StepScan scan = new FactoryRetention.StepScan(now - 90L * day, now - 14L * day);
		scan.offer("1", "p|STAGING_LOAD|", "DONE", now - 40L * day);
		scan.offer("2", "p|STAGING_LOAD|", "DONE", now - 30L * day);
		scan.offer("3", "p|STAGING_LOAD|", "DONE", now - 20L * day);
		scan.offer("4", "p|STAGING_LOAD|", "FAILED", now - 20L * day);
		scan.offer("5", "p|STAGING_LOAD|", "DONE", now - 1L * day);
		final java.util.Set<String> purged = scan.toPurge(null);
		check("DONE rows past the DONE age go, the latest DONE stays, a FAILED waits for the general age", purged.contains("1") && purged.contains("2") && purged.contains("3")
			&& !purged.contains("4") && !purged.contains("5") && purged.size() == 3);
		System.out.println("PipelineStepIndexCacheTest OK");
	}

	private static void check(final String pName, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pName);
		}
	}
}
