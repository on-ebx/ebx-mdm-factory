package com.on.ebx.factory.ebx;

import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.service.Session;

/**
 * Relation-fields lot (2026-09-26, `docs/MATCHING.md` dated entry): "how many CHILD rows does a typical
 * PARENT have" - the measured evidence {@code MatchPlanLlmSuggester} feeds the language-model pass alongside
 * the child's own attribute profiling, so the model can judge whether a relation field is likely to have
 * evidence to compare at all (a parent with zero child rows on either side of a pair can never confirm
 * anything through that relation field - {@code DecisionTreePlan}'s own {@code DO_NOTHING} null-handling
 * convention already covers this at RUN time; this measurement is only ever advisory, at PROPOSAL time).
 * <p>
 * Same discipline as {@link CrossSystemMeasures} (package-private, live EBX SQL via {@code
 * Adaptation.createQuery}/{@code Query<Tuple>}/{@code QueryResult<Tuple>}, every identifier checked against
 * {@link CrossSystemMeasures#isQuotable} before being concatenated into SQL text, ANY failure - a refused
 * statement, a transport error, the FK column turning out to need a dotted target-key path instead of a bare
 * scalar reference in a {@code GROUP BY} - returns "not measured" rather than throwing or guessing). <b>Never
 * run live</b> (no {@code match-merge}/EBX write access in the session that wrote this class; built by direct
 * analogy to {@link CrossSystemMeasures#sharedCount}'s own live-verified statement shape, GROUP BY substituted
 * for HAVING). If a real run ever finds the FK column needs its target key dotted on (CLAUDE.md &sect;2, "a
 * foreign-key column in SQL is a record, not a scalar"), only the two SQL strings below need to change.
 */
final class RelationRowCountMeasures
{
	private RelationRowCountMeasures()
	{
	}

	/** {@link #childRowCountStats}'s own result - {@code measured} false means every other field is meaningless (not "zero"). */
	static final class Stats
	{
		final boolean measured;
		final int minPerParent;
		final int maxPerParent;
		final double avgPerParent;
		/** Share (0..1) of parent rows with NO child row at all. */
		final double shareOfParentsWithNone;

		private Stats(final boolean pMeasured, final int pMin, final int pMax, final double pAvg, final double pShareOfParentsWithNone)
		{
			this.measured = pMeasured;
			this.minPerParent = pMin;
			this.maxPerParent = pMax;
			this.avgPerParent = pAvg;
			this.shareOfParentsWithNone = pShareOfParentsWithNone;
		}

		static Stats notMeasured()
		{
			return new Stats(false, 0, 0, 0d, 0d);
		}

		static Stats of(final int pMin, final int pMax, final double pAvg, final double pShareOfParentsWithNone)
		{
			return new Stats(true, pMin, pMax, pAvg, pShareOfParentsWithNone);
		}
	}

	/**
	 * @param pMasterDataset the project's master dataset (both {@code pChildEntityName}'s and {@code
	 *            pParentEntityName}'s own table live in it - {@code MasterModel.tablePath}'s "one dataset,
	 *            {@code /root/<Entity>} per table" convention).
	 * @param pChildEntityName the child entity's own table/entity name (identical, {@code MasterModel}'s convention).
	 * @param pForeignKeyFieldName the child's own FK field name (bare, e.g. {@code personneRef}).
	 * @param pParentEntityName the parent entity's own table/entity name - needed for the total-parent-row count
	 *            {@link Stats#shareOfParentsWithNone} divides by.
	 */
	static Stats childRowCountStats(final Session pSession, final Adaptation pMasterDataset, final String pChildEntityName, final String pForeignKeyFieldName,
		final String pParentEntityName)
	{
		if (!CrossSystemMeasures.isQuotable(pChildEntityName) || !CrossSystemMeasures.isQuotable(pForeignKeyFieldName)
			|| !CrossSystemMeasures.isQuotable(pParentEntityName))
		{
			return Stats.notMeasured();
		}
		try
		{
			final long totalParents = RelationRowCountMeasures.count(pSession, pMasterDataset, "SELECT COUNT(*) AS CNT FROM " + CrossSystemMeasures.quoteIdentifier(pParentEntityName));
			if (totalParents <= 0)
			{
				return Stats.notMeasured();
			}
			final String child = CrossSystemMeasures.quoteIdentifier(pChildEntityName);
			final String fk = CrossSystemMeasures.quoteIdentifier(pForeignKeyFieldName);
			final String sql = "SELECT COUNT(*) AS CNT FROM " + child + " a WHERE a." + fk + " IS NOT NULL GROUP BY a." + fk;
			final Query<Tuple> query = pMasterDataset.createQuery(sql);
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			long sum = 0;
			int min = Integer.MAX_VALUE;
			int max = 0;
			long groupsWithAtLeastOne = 0;
			try
			{
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext())
				{
					final Object value = it.next().get("CNT");
					if (!(value instanceof Number))
					{
						continue;
					}
					final int count = ((Number) value).intValue();
					if (count <= 0)
					{
						continue;
					}
					groupsWithAtLeastOne++;
					sum += count;
					min = Math.min(min, count);
					max = Math.max(max, count);
				}
			}
			finally
			{
				result.close();
			}
			if (groupsWithAtLeastOne == 0)
			{
				return Stats.of(0, 0, 0d, 1d);
			}
			final double avg = ((double) sum) / totalParents;
			final double shareWithNone = 1d - (((double) groupsWithAtLeastOne) / totalParents);
			return Stats.of(min == Integer.MAX_VALUE ? 0 : min, max, avg, Math.max(0d, shareWithNone));
		}
		// deliberately broad, exactly like CrossSystemMeasures's own methods - "not measured" is the documented
		// fallback for ANY failure here (a refused statement shape included), never a route failure
		catch (final RuntimeException ex)
		{
			return Stats.notMeasured();
		}
	}

	private static long count(final Session pSession, final Adaptation pMasterDataset, final String pSql)
	{
		final Query<Tuple> query = pMasterDataset.createQuery(pSql);
		query.setSession(pSession);
		final QueryResult<Tuple> result = query.getResult();
		try
		{
			final java.util.Iterator<Tuple> it = result.iterator();
			if (!it.hasNext())
			{
				return 0L;
			}
			final Object value = it.next().get("CNT");
			return value instanceof Number ? ((Number) value).longValue() : 0L;
		}
		finally
		{
			result.close();
		}
	}
}
