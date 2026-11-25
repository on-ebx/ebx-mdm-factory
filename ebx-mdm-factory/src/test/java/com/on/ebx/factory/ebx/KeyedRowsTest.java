package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;

/**
 * JUnit-free: run the main, a failure throws. Audit F-32 (2026-10-06): the one-scan joins of the wizard snapshot ({@link KeyedRows},
 * {@link PipelineStepIndex}) must answer what the per-parent queries they replace answered. The "reference" below is the old loop: for each wanted
 * key, filter the rows of the table in order; for each (step, scope), keep the first row and replace it only by a strictly later start.
 */
public final class KeyedRowsTest
{
	/** A stand-in for a table row: its foreign key text (null = empty) and its start time (null = unset). */
	private static final class Row
	{
		final String key;
		final Date startedAt;
		final int position;

		Row(final String pKey, final Date pStartedAt, final int pPosition)
		{
			this.key = pKey;
			this.startedAt = pStartedAt;
			this.position = pPosition;
		}
	}

	public static void main(final String[] pArgs)
	{
		KeyedRowsTest.predicate();
		KeyedRowsTest.thresholds();
		KeyedRowsTest.groupingEqualsPerKeyFiltering();
		KeyedRowsTest.latestEqualsPerPairLoop();
		KeyedRowsTest.stepKeys();
		System.out.println("KeyedRowsTest OK");
	}

	private static void predicate()
	{
		KeyedRowsTest.check("no key, no predicate", KeyedRows.inPredicate("./entity", new ArrayList<String>()).isEmpty());
		KeyedRowsTest.check("one key", "./entity='5'".equals(KeyedRows.inPredicate("./entity", Arrays.asList("5"))));
		KeyedRowsTest.check("keys are or-ed, in order", "./entity='5' or ./entity='12'".equals(KeyedRows.inPredicate("./entity", Arrays.asList("5", "12"))));
		KeyedRowsTest.check("a quote is doubled like every other predicate of the module", KeyedRows.inPredicate("./f", Arrays.asList("a'b")).equals("./f=" + CatalogReader.quote("a'b")));
		KeyedRowsTest.check("the paths of the schema give the same field text", "./entity".equals(FactoryPaths.StagingAttribute.ENTITY.format())
			&& "./projectSource".equals(FactoryPaths.LandingTable.PROJECT_SOURCE.format()) && "./landingTable".equals(FactoryPaths.LandingColumn.LANDING_TABLE.format())
			&& "./attribute".equals(FactoryPaths.AttributeMapping.ATTRIBUTE.format()) && "./feed".equals(FactoryPaths.AttributeMapping.FEED.format()));
	}

	private static void thresholds()
	{
		KeyedRowsTest.check("a short key set is filtered in the query", KeyedRows.filterInQuery(1) && KeyedRows.filterInQuery(KeyedRows.MAX_OR_KEYS));
		KeyedRowsTest.check("a long one is joined in memory", !KeyedRows.filterInQuery(KeyedRows.MAX_OR_KEYS + 1));
	}

	private static void groupingEqualsPerKeyFiltering()
	{
		final Random random = new Random(42);
		for (int round = 0; round < 200; round++)
		{
			final int keyCount = 1 + random.nextInt(8);
			final List<Row> table = new ArrayList<Row>();
			for (int i = 0; i < 60; i++)
			{
				final int draw = random.nextInt(keyCount + 3);
				table.add(new Row(draw == keyCount + 2 ? null : String.valueOf(draw), null, i));
			}
			final Set<String> wanted = new LinkedHashSet<String>();
			for (int k = 0; k < keyCount; k++)
			{
				if (random.nextInt(4) != 0)
				{
					wanted.add(String.valueOf(k));
				}
			}
			final KeyedRows.Buckets<Row> buckets = new KeyedRows.Buckets<Row>(wanted);
			for (final Row row : table)
			{
				buckets.offer(row.key, row);
			}
			final Map<String, List<Row>> joined = buckets.result();
			KeyedRowsTest.check("exactly the wanted keys are answered", joined.keySet().equals(wanted));
			for (final String key : wanted)
			{
				final List<Row> reference = new ArrayList<Row>();
				for (final Row row : table)
				{
					if (key.equals(row.key))
					{
						reference.add(row);
					}
				}
				KeyedRowsTest.check("the rows of key " + key + " are those of its own query, in table order", reference.equals(joined.get(key)));
			}
		}
		final KeyedRows.Buckets<Row> empty = new KeyedRows.Buckets<Row>(new LinkedHashSet<String>());
		empty.offer("1", new Row("1", null, 0));
		KeyedRowsTest.check("no wanted key keeps nothing", empty.result().isEmpty());
	}

	/** The old loop of readProjectSteps/readPipelineSteps/latestPipelineStep: one pass per pair, first row kept, replaced by a strictly later start when both are set. */
	private static Row referenceLatest(final List<Row> pTable, final String pKey)
	{
		Row latest = null;
		for (final Row row : pTable)
		{
			if (!pKey.equals(row.key))
			{
				continue;
			}
			if (latest == null || row.startedAt != null && latest.startedAt != null && row.startedAt.after(latest.startedAt))
			{
				latest = row;
			}
		}
		return latest;
	}

	private static void latestEqualsPerPairLoop()
	{
		final Random random = new Random(7);
		for (int round = 0; round < 300; round++)
		{
			final List<Row> table = new ArrayList<Row>();
			for (int i = 0; i < 40; i++)
			{
				// few distinct timestamps, some unset, so ties and unset dates are common
				final Date startedAt = random.nextInt(5) == 0 ? null : new Date(1000L * random.nextInt(6));
				table.add(new Row("k" + random.nextInt(4), startedAt, i));
			}
			final PipelineStepIndex.Latest<Row> latest = new PipelineStepIndex.Latest<Row>();
			for (final Row row : table)
			{
				latest.offer(row.key, row, row.startedAt);
			}
			for (int k = 0; k < 5; k++)
			{
				final String key = "k" + k;
				KeyedRowsTest.check("latest of " + key + " is the old loop's pick (round " + round + ")", KeyedRowsTest.referenceLatest(table, key) == latest.get(key));
			}
		}
	}

	private static void stepKeys()
	{
		KeyedRowsTest.check("a project-wide step carries no scope", PipelineStepIndex.key("STAGING_LOAD", null).equals(PipelineStepIndex.key("STAGING_LOAD", "")));
		KeyedRowsTest.check("step and scope do not run into each other", !PipelineStepIndex.key("AB", "C").equals(PipelineStepIndex.key("A", "BC")));
		KeyedRowsTest.check("./scope='' matched nothing, an empty source code selects no row", PipelineStepIndex.exactKey("LANDING_LOAD", "") == null
			&& PipelineStepIndex.exactKey("LANDING_LOAD", null) == null);
		KeyedRowsTest.check("a source code selects its own key", PipelineStepIndex.key("LANDING_LOAD", "ERP").equals(PipelineStepIndex.exactKey("LANDING_LOAD", "ERP")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("KeyedRowsTest: " + pWhat);
		}
	}
}
