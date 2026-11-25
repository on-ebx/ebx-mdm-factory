package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.quality.Detectors;

/**
 * Staging polish (2026-10-17, docs/UX-STAGING-POLISH.md sec 2): the pure parts of the column-read guard that live next to the EBX reads - when a cached
 * read answers a later request for another cap, and what a sample's aggregates report. (The decision itself is {@code quality.ScanGuardTest}, the cache
 * {@code quality.ReadCacheTest}; the EBX reads need a repository and are checked live.) JUnit-free: run the main, a failure throws.
 */
public final class ColumnReadsTest
{
	public static void main(final String[] pArgs)
	{
		ColumnReadsTest.aLongerReadAnswersAShorterRequest();
		ColumnReadsTest.aReadThatHeldEveryDistinctValueAnswersAnyRequest();
		ColumnReadsTest.aTruncatedReadDoesNotAnswerALongerRequest();
		ColumnReadsTest.theSampleFlagsFollowTheRowsRead();
		ColumnReadsTest.aggregatesOfASampleKeepTheCountedTotal();
		System.out.println("ColumnReadsTest OK");
	}

	private static QualitySuggester.ColumnQuery read(final int pDistinct, final int pCap, final long pSampledRows)
	{
		final List<Detectors.ValueCount> values = new ArrayList<Detectors.ValueCount>();
		for (int i = 0; i < pDistinct; i++)
		{
			values.add(new Detectors.ValueCount("v" + i, 1000L - i)); // most frequent first, as the GROUP BY returns them
		}
		return new QualitySuggester.ColumnQuery(values, pSampledRows > 0L ? "SAMPLE" : "SQL", pSampledRows, 1465857L, pCap);
	}

	/** The normalisation pass reads 2000, the shapes 200, the emptiness probe 50: one read of 2000 serves the three. */
	private static void aLongerReadAnswersAShorterRequest()
	{
		final QualitySuggester.ColumnQuery cached = ColumnReadsTest.read(2000, 2000, 0L);
		check("200 are covered", cached.coversCap(200) && cached.coversCap(50) && cached.coversCap(2000));
		final QualitySuggester.ColumnQuery shaped = cached.withCap(200);
		check("the 200 most frequent, in order", shaped.values.size() == 200 && "v0".equals(shaped.values.get(0).value) && "v199".equals(shaped.values.get(199).value));
		check("the cut keeps what the read knew", shaped.path.equals("SQL") && shaped.tableRows == 1465857L && shaped.cap == 200 && !shaped.sampled());
		check("asking for more than it holds returns it as it is", cached.withCap(5000) == cached);
	}

	private static void aReadThatHeldEveryDistinctValueAnswersAnyRequest()
	{
		final QualitySuggester.ColumnQuery cached = ColumnReadsTest.read(12, 200, 0L);
		check("12 distinct values read with a cap of 200: nothing more exists", cached.coversCap(2000) && cached.withCap(2000) == cached);
	}

	private static void aTruncatedReadDoesNotAnswerALongerRequest()
	{
		final QualitySuggester.ColumnQuery cached = ColumnReadsTest.read(200, 200, 0L);
		check("a read cut at its cap cannot serve a longer one", !cached.coversCap(2000) && cached.coversCap(200) && cached.coversCap(10));
	}

	private static void theSampleFlagsFollowTheRowsRead()
	{
		check("whole table", !ColumnReadsTest.read(3, 10, 0L).sampled());
		final QualitySuggester.ColumnQuery sampled = ColumnReadsTest.read(3, 10, 50000L);
		check("first rows", sampled.sampled() && sampled.sampledRows == 50000L && "SAMPLE".equals(sampled.path));
		check("the sampled flag survives the cut", ColumnReadsTest.read(300, 300, 50000L).withCap(20).sampled());
	}

	private static void aggregatesOfASampleKeepTheCountedTotal()
	{
		final QualitySuggester.ExactAggregates exact = new QualitySuggester.ExactAggregates(1000L, 900L, 50L);
		check("exact: total is the rows, not sampled", !exact.sampled() && exact.totalRows() == 1000L);
		final QualitySuggester.ExactAggregates sample = new QualitySuggester.ExactAggregates(50000L, 40000L, 900L, false, 50000L, 1465857L);
		check("sample: rate figures on the sample, total from the count", sample.sampled() && sample.rows == 50000L && sample.filled == 40000L && sample.totalRows() == 1465857L);
		final QualitySuggester.ExactAggregates uncounted = new QualitySuggester.ExactAggregates(50000L, 40000L, 900L, false, 50000L, -1L);
		check("sample of a table that could not be counted: the rows read", uncounted.totalRows() == 50000L);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError("ColumnReadsTest: " + pWhat);
		}
	}
}
