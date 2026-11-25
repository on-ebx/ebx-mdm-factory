package com.on.ebx.factory.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import com.on.ebx.factory.quality.CleaningSteps;

/**
 * Audit F2-100 (2026-10-24, lot B2): the Mapping matrix is read at constant cost. The EBX-bound reader needs a repository, so what is checked here is what
 * the cost rests on: ONE sweep of the shared {@code MappingStep} table per read ({@link MappingStepIndex}), a result kept under the tables' modification
 * dates and re-checked against the tables outside the Factory ({@link MatrixReadCache}), cleaning steps identical to what the per-mapping query gave, and -
 * from the sources - that the reader no longer opens a per-mapping query on {@code MappingStep}. JUnit-free: run the main, a failure throws.
 */
public final class MappingMatrixCostTest
{
	private MappingMatrixCostTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		MappingMatrixCostTest.oneSweepWhateverTheNumberOfMappings();
		MappingMatrixCostTest.threeReadsOfTheSameStateTakeOneSweepAndOneMeasure();
		MappingMatrixCostTest.aChangedTableDateReadsAgain();
		MappingMatrixCostTest.aChangedOutsideTableReadsAgain();
		MappingMatrixCostTest.theCacheIsBounded();
		MappingMatrixCostTest.theIndexGivesWhatThePerMappingQueryGave();
		MappingMatrixCostTest.theReaderNoLongerQueriesMappingStepPerMapping();
		System.out.println("MappingMatrixCostTest OK");
	}

	/** 50 mappings, a few steps each, plus rows of other projects: the simulated table. */
	private static List<MappingStepIndex.StepRow> table(final int pMappings, final long pSeed)
	{
		final Random random = new Random(pSeed);
		final String[] statuses = { "ACCEPTED", "APPLIED", "PROPOSED", "REJECTED" };
		final String[] kinds = { "TRIM", "UPPER", "COUNTRY_TO_ISO2", "TRANSCODE_REFERENCE" };
		final List<MappingStepIndex.StepRow> rows = new ArrayList<MappingStepIndex.StepRow>();
		for (int i = 0; i < pMappings * 4; i++)
		{
			rows.add(new MappingStepIndex.StepRow(String.valueOf(1 + random.nextInt(pMappings)), statuses[random.nextInt(statuses.length)], kinds[random.nextInt(kinds.length)],
				"arg" + i, random.nextInt(5) == 0 ? null : Integer.valueOf(random.nextInt(4)), null));
		}
		return rows;
	}

	private static final class CountingSource implements MappingStepIndex.Source
	{
		final List<MappingStepIndex.StepRow> rows;
		int sweeps;

		CountingSource(final List<MappingStepIndex.StepRow> pRows)
		{
			this.rows = pRows;
		}

		@Override
		public List<MappingStepIndex.StepRow> scan()
		{
			this.sweeps++;
			return this.rows;
		}
	}

	private static void oneSweepWhateverTheNumberOfMappings()
	{
		final CountingSource source = new CountingSource(MappingMatrixCostTest.table(50, 1L));
		final MappingStepIndex index = MappingStepIndex.of(source);
		int visited = 0;
		for (int mapping = 1; mapping <= 50; mapping++)
		{
			CleaningSteps.Step unused = null;
			for (final CleaningSteps.Step step : MappingStepIndex.cleaningStepsOf(index.rowsOf(String.valueOf(mapping))))
			{
				unused = step;
			}
			index.rowsOf(String.valueOf(mapping)); // the code-list read of the same mapping
			visited++;
			check("no row for a mapping the table does not know", unused == null || unused.kind != null);
		}
		check("50 mappings read, two readers each: ONE sweep of the table (it was 100 per-mapping scans)", visited == 50 && source.sweeps == 1);
		check("the index counts what it swept", index.stepCount() == 200 && index.mappingCount() <= 50 && index.rowsOf("nope").isEmpty() && index.rowsOf(null).isEmpty());
	}

	/** The loader stands for MappingMatrixReader#readUncached: it sweeps MappingStep once and measures one sample per mapping. */
	private static MatrixReadCache<String> cacheWithLoader(final CountingSource pSource, final int[] pMeasured)
	{
		return new MatrixReadCache<String>(4, 60000L);
	}

	private static String readThroughCache(final MatrixReadCache<String> pCache, final String pKey, final Function<String, String> pTokens, final CountingSource pSource,
		final int[] pMeasured, final Set<String> pExternals, final long pNow)
	{
		final String cached = pCache.get(pKey, pTokens, pNow);
		if (cached != null)
		{
			return cached;
		}
		final MappingStepIndex index = MappingStepIndex.of(pSource);
		pMeasured[0] += index.mappingCount(); // one sample per mapping on a miss
		final String model = "model of " + index.stepCount() + " steps";
		pCache.put(pKey, model, pExternals, pTokens, pNow);
		return model;
	}

	private static void threeReadsOfTheSameStateTakeOneSweepAndOneMeasure()
	{
		final CountingSource source = new CountingSource(MappingMatrixCostTest.table(50, 2L));
		final int[] measured = new int[1];
		final MatrixReadCache<String> cache = MappingMatrixCostTest.cacheWithLoader(source, measured);
		final Set<String> externals = new LinkedHashSet<String>();
		externals.add("landing\u0001ds\u0001/root/T");
		final Function<String, String> tokens = MappingMatrixCostTest.tokens("100");
		final String a = MappingMatrixCostTest.readThroughCache(cache, "P|u|fr|-|t1", tokens, source, measured, externals, 1000L);
		final int measuredAfterFirst = measured[0];
		final String b = MappingMatrixCostTest.readThroughCache(cache, "P|u|fr|-|t1", tokens, source, measured, externals, 2000L); // the desk
		final String c = MappingMatrixCostTest.readThroughCache(cache, "P|u|fr|-|t1", tokens, source, measured, externals, 3000L); // the matrix
		check("three reads of the same state: one sweep of MappingStep", source.sweeps == 1);
		check("... and no sample measured after the first read", measured[0] == measuredAfterFirst && measuredAfterFirst > 0);
		check("... the same model each time", a == b && b == c && cache.hits() == 2 && cache.misses() == 1);
	}

	private static void aChangedTableDateReadsAgain()
	{
		final CountingSource source = new CountingSource(MappingMatrixCostTest.table(10, 3L));
		final int[] measured = new int[1];
		final MatrixReadCache<String> cache = MappingMatrixCostTest.cacheWithLoader(source, measured);
		final Function<String, String> tokens = MappingMatrixCostTest.tokens("1");
		MappingMatrixCostTest.readThroughCache(cache, "P|u|fr|-|MappingStep@100", tokens, source, measured, new LinkedHashSet<String>(), 1000L);
		MappingMatrixCostTest.readThroughCache(cache, "P|u|fr|-|MappingStep@100", tokens, source, measured, new LinkedHashSet<String>(), 1100L);
		check("same dates: one sweep", source.sweeps == 1);
		MappingMatrixCostTest.readThroughCache(cache, "P|u|fr|-|MappingStep@101", tokens, source, measured, new LinkedHashSet<String>(), 1200L); // a mapping was accepted
		check("a table modified since: read again (the key moved)", source.sweeps == 2);
		check("another user or another entity is another entry", cache.get("P|other|fr|-|MappingStep@101", tokens, 1300L) == null);
		check("an entry older than the bound is dropped", new MatrixReadCache<String>(4, 1000L).get("k", tokens, 0L) == null);
		final MatrixReadCache<String> aged = new MatrixReadCache<String>(4, 1000L);
		aged.put("k", "v", new LinkedHashSet<String>(), tokens, 0L);
		check("within the bound it answers", "v".equals(aged.get("k", tokens, 999L)));
		check("past the bound it does not", aged.get("k", tokens, 1001L) == null);
	}

	private static void aChangedOutsideTableReadsAgain()
	{
		final Set<String> externals = new LinkedHashSet<String>();
		externals.add("landing\u0001ds\u0001/root/Customer");
		final MatrixReadCache<String> cache = new MatrixReadCache<String>(4, 60000L);
		cache.put("k", "model", externals, MappingMatrixCostTest.tokens("100"), 0L);
		check("the landing table is as it was: served", "model".equals(cache.get("k", MappingMatrixCostTest.tokens("100"), 1L)));
		check("a load wrote the landing table since: not served", cache.get("k", MappingMatrixCostTest.tokens("101"), 2L) == null);
		check("a table that cannot be read is not trusted", cache.get("k", new Function<String, String>()
		{
			@Override
			public String apply(final String pRef)
			{
				return null;
			}
		}, 3L) == null);
		final MatrixReadCache<String> unreadable = new MatrixReadCache<String>(4, 60000L);
		unreadable.put("k", "model", externals, new Function<String, String>()
		{
			@Override
			public String apply(final String pRef)
			{
				return null;
			}
		}, 0L);
		check("nothing is kept when a token cannot be read", unreadable.size() == 0);
		unreadable.put(null, "model", externals, MappingMatrixCostTest.tokens("1"), 0L);
		check("nothing is kept under a null key", unreadable.size() == 0);
	}

	private static void theCacheIsBounded()
	{
		final MatrixReadCache<String> cache = new MatrixReadCache<String>(3, 60000L);
		for (int i = 0; i < 10; i++)
		{
			cache.put("k" + i, "v" + i, new LinkedHashSet<String>(), MappingMatrixCostTest.tokens("1"), i);
		}
		check("least recently used goes first, never more than the bound", cache.size() == 3 && cache.get("k0", MappingMatrixCostTest.tokens("1"), 11L) == null
			&& "v9".equals(cache.get("k9", MappingMatrixCostTest.tokens("1"), 11L)));
	}

	/** The old per-mapping reader, as StagingLoader#readCleaningSteps read it before: the rows of the mapping, accepted-like only, sorted by position (none last). */
	private static List<String> oldReader(final List<MappingStepIndex.StepRow> pTable, final String pMapping)
	{
		final List<MappingStepIndex.StepRow> rows = new ArrayList<MappingStepIndex.StepRow>();
		for (final MappingStepIndex.StepRow row : pTable)
		{
			if (pMapping.equals(row.mapping) && ("ACCEPTED".equals(row.status) || "APPLIED".equals(row.status)))
			{
				rows.add(row);
			}
		}
		Collections.sort(rows, new Comparator<MappingStepIndex.StepRow>()
		{
			@Override
			public int compare(final MappingStepIndex.StepRow pLeft, final MappingStepIndex.StepRow pRight)
			{
				final int left = pLeft.position == null ? Integer.MAX_VALUE : pLeft.position.intValue();
				final int right = pRight.position == null ? Integer.MAX_VALUE : pRight.position.intValue();
				return Integer.compare(left, right);
			}
		});
		final List<String> out = new ArrayList<String>();
		for (final MappingStepIndex.StepRow row : rows)
		{
			out.add(row.kind + "(" + row.argument + ")");
		}
		return out;
	}

	private static void theIndexGivesWhatThePerMappingQueryGave()
	{
		int compared = 0;
		for (long seed = 10; seed < 15; seed++)
		{
			final List<MappingStepIndex.StepRow> table = MappingMatrixCostTest.table(50, seed);
			final MappingStepIndex index = MappingStepIndex.of(new CountingSource(table));
			for (int mapping = 1; mapping <= 50; mapping++)
			{
				final List<String> fromIndex = new ArrayList<String>();
				for (final CleaningSteps.Step step : MappingStepIndex.cleaningStepsOf(index.rowsOf(String.valueOf(mapping))))
				{
					fromIndex.add(step.kind + "(" + step.argument + ")");
				}
				check("seed " + seed + " mapping " + mapping + ": same cleaning steps, same order", fromIndex.equals(MappingMatrixCostTest.oldReader(table, String.valueOf(mapping))));
				compared++;
			}
		}
		check("250 mappings compared", compared == 250);
	}

	private static String source(final String pName) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get(System.getProperty("mdmf.sourceDir", "src/main/java") + "/com/on/ebx/factory/ebx/" + pName)), StandardCharsets.UTF_8);
	}

	private static int count(final String pText, final String pNeedle)
	{
		int n = 0;
		for (int at = pText.indexOf(pNeedle); at >= 0; at = pText.indexOf(pNeedle, at + 1))
		{
			n++;
		}
		return n;
	}

	private static void theReaderNoLongerQueriesMappingStepPerMapping() throws Exception
	{
		final String reader = MappingMatrixCostTest.source("MappingMatrixReader.java");
		check("no per-mapping query on MappingStep in the matrix reader", !reader.contains("FactoryPaths.MAPPING_STEP), \"./mapping=\"") && !reader.contains("readCleaningSteps(pFactory,"));
		check("the sweep is taken in one place only", MappingMatrixCostTest.count(reader, "MappingStepIndex.scan(") == 1);
		check("every cleaning-step read of the reader goes through the sweep", MappingMatrixCostTest.count(reader, "StagingLoader.readCleaningSteps(MappingMatrixReader.steps(pFactory)") == 2);
		check("read() looks in the cache before reading, and the cache key holds the MappingStep date",
			reader.indexOf("MappingMatrixReader.CACHE.get(") > 0 && reader.indexOf("MappingMatrixReader.CACHE.get(") < reader.indexOf("MappingMatrixReader.readUncached(")
				&& reader.contains("FactoryPaths.MAPPING_STEP,"));
		check("the debug line says what was read", reader.contains("\" mappings, \"") && reader.contains("mapping matrix for \" + pProjectId + \" read in \""));
	}

	private static Function<String, String> tokens(final String pToken)
	{
		return new Function<String, String>()
		{
			@Override
			public String apply(final String pRef)
			{
				return pToken;
			}
		};
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
