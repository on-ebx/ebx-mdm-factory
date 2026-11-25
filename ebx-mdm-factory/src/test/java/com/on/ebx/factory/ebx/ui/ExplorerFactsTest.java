package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Random;

/** JUnit-free: run the main, a failure throws. One pass over the tables gives the explorer what its per-card queries gave (audit F-36). */
public final class ExplorerFactsTest
{
	public static void main(final String[] pArgs)
	{
		ExplorerFactsTest.latestStepMatchesTheOldSortedHead();
		ExplorerFactsTest.sourcesAndEntities();
		ExplorerFactsTest.unknownProjectReadsAsEmpty();
		ExplorerFactsTest.freshness();
		ExplorerFactsTest.carriedOverPartsEqualAFullRead();
		System.out.println("ExplorerFactsTest OK");
	}

	/** One step row as the old code saw it. */
	private static final class Row
	{
		final String project;
		final String step;
		final Date startedAt;

		Row(final String pProject, final String pStep, final Date pStartedAt)
		{
			this.project = pProject;
			this.step = pStep;
			this.startedAt = pStartedAt;
		}
	}

	private static void latestStepMatchesTheOldSortedHead()
	{
		for (long seed = 1; seed <= 40; seed++)
		{
			final Random random = new Random(seed);
			final List<Row> rows = new ArrayList<Row>();
			for (int i = 0; i < 300; i++)
			{
				final long minute = random.nextInt(25); // many ties on purpose, and some rows with no start time
				rows.add(new Row("P" + random.nextInt(6), "STEP_" + i, random.nextInt(10) == 0 ? null : new Date(1790000000000L - minute * 60000L)));
			}
			final ExplorerFacts facts = new ExplorerFacts();
			for (final Row row : rows)
			{
				facts.offerStep(row.project, row.step, row.startedAt);
			}
			for (int p = 0; p < 6; p++)
			{
				final String project = "P" + p;
				// the old code: the project's rows in table order, a stable sort by start time descending (no start time = oldest), the head
				final List<Row> steps = new ArrayList<Row>();
				for (final Row row : rows)
				{
					if (row.project.equals(project))
					{
						steps.add(row);
					}
				}
				final ExplorerFacts.ProjectFacts got = facts.of(project);
				if (steps.isEmpty())
				{
					ExplorerFactsTest.check("no step, no activity", got.lastActivityStep == null && got.lastActivityAt == null);
					continue;
				}
				Collections.sort(steps, new Comparator<Row>()
				{
					@Override
					public int compare(final Row pA, final Row pB)
					{
						final long a = pA.startedAt == null ? Long.MIN_VALUE : pA.startedAt.getTime();
						final long b = pB.startedAt == null ? Long.MIN_VALUE : pB.startedAt.getTime();
						return Long.valueOf(b).compareTo(Long.valueOf(a));
					}
				});
				ExplorerFactsTest.check("seed " + seed + " " + project + ": same latest step as the sorted head: " + got.lastActivityStep + " vs " + steps.get(0).step,
					steps.get(0).step.equals(got.lastActivityStep));
				ExplorerFactsTest.check("and the same start", steps.get(0).startedAt == null ? got.lastActivityAt == null : steps.get(0).startedAt.equals(got.lastActivityAt));
			}
		}
		final ExplorerFacts onlyUndated = new ExplorerFacts();
		onlyUndated.offerStep("P", "A", null);
		onlyUndated.offerStep("P", "B", null);
		ExplorerFactsTest.check("steps with no start: the first one read is kept", "A".equals(onlyUndated.of("P").lastActivityStep) && onlyUndated.of("P").lastActivityAt == null);
	}

	private static void sourcesAndEntities()
	{
		final ExplorerFacts facts = new ExplorerFacts();
		facts.offerSource("P1", "ERP");
		facts.offerSource("P1", "CRM");
		facts.offerSource("P1", "ERP"); // a second application of the same system: counted, listed once
		facts.offerSource("P1", "");
		facts.offerSource("P1", null);
		facts.offerSource("P2", "SAP");
		for (int i = 0; i < 4; i++)
		{
			facts.offerEntity("P1");
		}
		ExplorerFactsTest.check("five sources of P1, blanks counted", facts.of("P1").sourceCount == 5);
		ExplorerFactsTest.check("two distinct systems, in order: " + facts.of("P1").sourceSystems, facts.of("P1").sourceSystems.equals(java.util.Arrays.asList("ERP", "CRM")));
		ExplorerFactsTest.check("four entities of P1, none of P2", facts.of("P1").stagingEntityCount == 4 && facts.of("P2").stagingEntityCount == 0);
		ExplorerFactsTest.check("P2 has its one source", facts.of("P2").sourceCount == 1 && facts.of("P2").sourceSystems.equals(java.util.Collections.singletonList("SAP")));
	}

	private static void unknownProjectReadsAsEmpty()
	{
		final ExplorerFacts.ProjectFacts none = new ExplorerFacts().of("nobody");
		ExplorerFactsTest.check("a project with no row anywhere", none.sourceCount == 0 && none.stagingEntityCount == 0 && none.sourceSystems.isEmpty() && none.lastActivityStep == null
			&& none.lastActivityAt == null);
	}

	private static void freshness()
	{
		ExplorerFactsTest.check("just read is fresh", ExplorerFacts.isFresh(1000L, 1000L, 5000L));
		ExplorerFactsTest.check("at the limit is fresh", ExplorerFacts.isFresh(6000L, 1000L, 5000L));
		ExplorerFactsTest.check("past the limit is stale", !ExplorerFacts.isFresh(6001L, 1000L, 5000L));
		ExplorerFactsTest.check("a clock that went back is stale", !ExplorerFacts.isFresh(500L, 1000L, 5000L));
		ExplorerFactsTest.check("the index is reused a few minutes at most", ExplorerFacts.MAX_AGE_MS >= 60000L && ExplorerFacts.MAX_AGE_MS <= 600000L);
	}

	/**
	 * ProjectIndex (server-paged explorer): a rebuild carries the parts of unchanged tables over and reads only the steps created since the last read
	 * (ids above the highest one seen, in table order) - which must give what one full read gives.
	 */
	private static void carriedOverPartsEqualAFullRead()
	{
		for (long seed = 1; seed <= 20; seed++)
		{
			final Random random = new Random(seed);
			final List<Row> rows = new ArrayList<Row>();
			for (int i = 0; i < 200; i++)
			{
				// steps are created with the current time: start times never go back as ids grow (ties allowed)
				rows.add(new Row("P" + random.nextInt(5), "STEP_" + i, random.nextInt(15) == 0 ? null : new Date(1790000000000L + (i / 3) * 1000L)));
			}
			final int cut = random.nextInt(rows.size());
			final ExplorerFacts full = new ExplorerFacts();
			final ExplorerFacts before = new ExplorerFacts();
			for (int i = 0; i < rows.size(); i++)
			{
				full.offerStep(rows.get(i).project, rows.get(i).step, rows.get(i).startedAt);
				if (i < cut)
				{
					before.offerStep(rows.get(i).project, rows.get(i).step, rows.get(i).startedAt);
				}
				full.offerSource(rows.get(i).project, "S" + (i % 3));
				before.offerSource(rows.get(i).project, "S" + (i % 3));
				full.offerEntity(rows.get(i).project);
				before.offerEntity(rows.get(i).project);
			}
			final ExplorerFacts after = new ExplorerFacts();
			after.copySourcesFrom(before);
			after.copyEntitiesFrom(before);
			after.copyStepsFrom(before);
			for (int i = cut; i < rows.size(); i++)
			{
				after.offerStep(rows.get(i).project, rows.get(i).step, rows.get(i).startedAt);
			}
			for (int p = 0; p < 6; p++)
			{
				final ExplorerFacts.ProjectFacts a = full.of("P" + p);
				final ExplorerFacts.ProjectFacts b = after.of("P" + p);
				ExplorerFactsTest.check("seed " + seed + " P" + p + " latest step: " + a.lastActivityStep + " vs " + b.lastActivityStep,
					a.lastActivityStep == null ? b.lastActivityStep == null : a.lastActivityStep.equals(b.lastActivityStep));
				ExplorerFactsTest.check("sources carried over", a.sourceCount == b.sourceCount && a.sourceSystems.equals(b.sourceSystems));
				ExplorerFactsTest.check("entities carried over", a.stagingEntityCount == b.stagingEntityCount);
			}
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
