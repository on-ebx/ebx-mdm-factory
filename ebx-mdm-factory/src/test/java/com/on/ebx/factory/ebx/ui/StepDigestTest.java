package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

/** JUnit-free: run the main, a failure throws. The cockpit shows the same pipeline from the digest of a project's steps as from every row of them (audit F-36). */
public final class StepDigestTest
{
	private static final Date NOW = new Date(1790000000000L);

	public static void main(final String[] pArgs)
	{
		StepDigestTest.sameScreenAsEveryRow();
		StepDigestTest.tiesAreBrokenByIdLikeBefore();
		StepDigestTest.memoryIsTheNumberOfPairsNotRows();
		StepDigestTest.smallProjectIsUntouched();
		System.out.println("StepDigestTest OK");
	}

	private static CockpitData.StepInfo step(final String pId, final String pStep, final String pScope, final String pStatus, final long pStartedMinutesAgo)
	{
		final CockpitData.StepInfo s = new CockpitData.StepInfo();
		s.id = pId;
		s.step = pStep;
		s.scope = pScope;
		s.status = pStatus;
		s.message = "run " + pId;
		s.startedAt = new Date(StepDigestTest.NOW.getTime() - pStartedMinutesAgo * 60000L);
		s.endedAt = "RUNNING".equals(pStatus) ? null : new Date(StepDigestTest.NOW.getTime() - (pStartedMinutesAgo - 1) * 60000L);
		return s;
	}

	private static CockpitData.StepInfo copy(final CockpitData.StepInfo pStep)
	{
		final CockpitData.StepInfo s = new CockpitData.StepInfo();
		s.id = pStep.id;
		s.step = pStep.step;
		s.scope = pStep.scope;
		s.status = pStep.status;
		s.message = pStep.message;
		s.startedAt = pStep.startedAt;
		s.endedAt = pStep.endedAt;
		return s;
	}

	private static CockpitData.Snapshot snapshot(final List<CockpitData.StepInfo> pSteps)
	{
		final CockpitData.Inputs in = new CockpitData.Inputs();
		in.now = StepDigestTest.NOW;
		for (final String system : new String[] { "ERP", "CRM" })
		{
			final CockpitData.SourceInfo source = new CockpitData.SourceInfo();
			source.id = system;
			source.sourceSystem = system;
			in.sources.add(source);
		}
		in.steps.addAll(pSteps);
		return CockpitData.compute(in);
	}

	private static String describe(final CockpitData.Snapshot pSnapshot)
	{
		final StringBuilder out = new StringBuilder();
		for (final CockpitData.SourceBlock block : pSnapshot.pipeline)
		{
			out.append('[').append(block.sourceSystem).append(block.project ? "*" : "").append(']');
			for (final CockpitData.StepRow row : block.steps)
			{
				out.append(row.step).append('/').append(row.status).append('/').append(row.message).append('/').append(row.runs).append('/').append(row.startedAt == null ? "-" : row.startedAt.getTime())
					.append('/').append(row.endedAt == null ? "-" : row.endedAt.getTime()).append('/').append(row.stuck).append(';');
			}
		}
		return out.toString();
	}

	private static List<CockpitData.StepInfo> rows(final int pCount, final long pSeed)
	{
		final String[] steps = { "LANDING_MODEL", "LANDING_LOAD", "STAGING_SUGGEST", "STAGING_LOAD", "MASTER_LOAD", "MATCHING_RUN", "SOMETHING_NEW" };
		final String[] scopes = { "ERP", "CRM", "", "LLM", "ERP" };
		final String[] statuses = { "DONE", "FAILED", "RUNNING", "DONE", "DONE" };
		final Random random = new Random(pSeed);
		final List<CockpitData.StepInfo> out = new ArrayList<CockpitData.StepInfo>();
		for (int i = 0; i < pCount; i++)
		{
			out.add(StepDigestTest.step(String.valueOf(i + 1), steps[random.nextInt(steps.length)], scopes[random.nextInt(scopes.length)], statuses[random.nextInt(statuses.length)],
				random.nextInt(60 * 24 * 40) + 1)); // minutes ago, up to forty days
		}
		return out;
	}

	private static void sameScreenAsEveryRow()
	{
		for (long seed = 1; seed <= 25; seed++)
		{
			final List<CockpitData.StepInfo> all = StepDigestTest.rows(40 + (int) seed * 30, seed);
			for (int shuffle = 0; shuffle < 3; shuffle++)
			{
				// the rows come back in table order, whatever it is: both sides read the SAME order, so even the position of two pairs of equal rank is compared like for like
				final List<CockpitData.StepInfo> order = new ArrayList<CockpitData.StepInfo>();
				for (final CockpitData.StepInfo row : all)
				{
					order.add(StepDigestTest.copy(row));
				}
				Collections.shuffle(order, new Random(seed * 31 + shuffle));
				final String expected = StepDigestTest.describe(StepDigestTest.snapshot(order));
				final StepDigest digest = new StepDigest();
				for (final CockpitData.StepInfo row : order)
				{
					digest.offer(StepDigestTest.copy(row));
				}
				final String actual = StepDigestTest.describe(StepDigestTest.snapshot(digest.result()));
				StepDigestTest.check("seed " + seed + " shuffle " + shuffle + ": the digest draws the same pipeline\n  " + expected + "\n  " + actual, expected.equals(actual));
			}
		}
	}

	private static void tiesAreBrokenByIdLikeBefore()
	{
		final List<CockpitData.StepInfo> all = new ArrayList<CockpitData.StepInfo>();
		all.add(StepDigestTest.step("9", "LANDING_LOAD", "ERP", "DONE", 5));
		all.add(StepDigestTest.step("10", "LANDING_LOAD", "ERP", "FAILED", 5)); // same instant: the larger id (numerically) is the latest
		all.add(StepDigestTest.step("2", "LANDING_LOAD", "ERP", "DONE", 5));
		final StepDigest digest = new StepDigest();
		for (final CockpitData.StepInfo row : all)
		{
			digest.offer(StepDigestTest.copy(row));
		}
		StepDigestTest.check("one entry", digest.result().size() == 1);
		StepDigestTest.check("numeric id wins a tie: " + digest.result().get(0).id, "10".equals(digest.result().get(0).id) && "FAILED".equals(digest.result().get(0).status));
		StepDigestTest.check("three runs counted", digest.result().get(0).runs == 3);
	}

	private static void memoryIsTheNumberOfPairsNotRows()
	{
		final StepDigest digest = new StepDigest();
		for (final CockpitData.StepInfo row : StepDigestTest.rows(20000, 7))
		{
			digest.offer(row);
		}
		StepDigestTest.check("20000 rows leave at most scopes x steps entries: " + digest.result().size(), digest.result().size() <= 4 * 7);
		int runs = 0;
		for (final CockpitData.StepInfo kept : digest.result())
		{
			runs += kept.runs;
		}
		StepDigestTest.check("no run is lost in the count: " + runs, runs == 20000);
	}

	private static void smallProjectIsUntouched()
	{
		final List<CockpitData.StepInfo> all = new ArrayList<CockpitData.StepInfo>();
		all.add(StepDigestTest.step("1", "LANDING_FLOWS", "ERP", "DONE", 120));
		all.add(StepDigestTest.step("2", "LANDING_FLOWS", "ERP", "DONE", 10));
		all.add(StepDigestTest.step("3", "LANDING_LOAD", "ERP", "DONE", 30));
		final StepDigest digest = new StepDigest();
		for (final CockpitData.StepInfo row : all)
		{
			digest.offer(StepDigestTest.copy(row));
		}
		final CockpitData.SourceBlock block = StepDigestTest.snapshot(digest.result()).pipeline.get(0);
		StepDigestTest.check("two lines", block.steps.size() == 2);
		StepDigestTest.check("the re-run step says twice", block.steps.get(0).runs == 2 && block.steps.get(1).runs == 1);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
