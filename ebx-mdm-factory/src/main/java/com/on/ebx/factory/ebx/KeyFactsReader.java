package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.master.KeyFactsSelection;
import com.on.ebx.factory.master.MatchPlanLlmPass;
import com.on.ebx.factory.matching.KeyFacts;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.query.Query;
import com.orchestranetworks.query.QueryResult;
import com.orchestranetworks.query.Tuple;
import com.orchestranetworks.service.Session;

/**
 * Lot M3 (2026-10-17, {@code docs/MATCHING.md} &sect;56): reads the key columns of an entity's master table once - ONE read-only EBX SQL statement over the candidate keys
 * {@link KeyFactsSelection} chose, source system included - and hands the rows to {@link KeyFacts#compute}, which counts in linear time.
 * <p>
 * Bounded: at most {@link KeyFacts#MAX_ROWS} rows are read (the result is streamed and closed after one more than the cap, which tells the table is bigger); a larger table gives facts computed on a prefix,
 * flagged {@link KeyFacts#sampled()} - lower bounds that relax no rule and produce no finding. Any failure (a statement the engine refuses, a column the table lacks) gives
 * {@code null}: "not measured", the matching pass then behaves as it did before the lot. The identifiers are quoted through {@link CrossSystemMeasures#isQuotable}, like every
 * statement of that class.
 */
final class KeyFactsReader
{
	private KeyFactsReader()
	{
	}

	/** The facts of {@code pEntity}'s master table, or null when no key is worth measuring, the table cannot be read, or a name is not quotable. */
	static KeyFacts read(final Session pSession, final Adaptation pMasterDataset, final MatchPlanLlmPass.EntityInfo pEntity)
	{
		if (pMasterDataset == null || !CrossSystemMeasures.isQuotable(pEntity.name))
		{
			return null;
		}
		final List<KeyFacts.KeySpec> specs = KeyFactsSelection.select(pEntity);
		if (specs.size() < 1)
		{
			return null;
		}
		final StringBuilder sql = new StringBuilder("SELECT a.").append(CrossSystemMeasures.quoteIdentifier("sourceSystem")).append(" AS SYS");
		for (int i = 0; i < specs.size(); i++)
		{
			if (!CrossSystemMeasures.isQuotable(specs.get(i).name))
			{
				return null;
			}
			sql.append(", a.").append(CrossSystemMeasures.quoteIdentifier(specs.get(i).name)).append(" AS K").append(i);
		}
		sql.append(" FROM ").append(CrossSystemMeasures.quoteIdentifier(pEntity.name)).append(" a");
		try
		{
			final Query<Tuple> query = pMasterDataset.createQuery(sql.toString());
			query.setSession(pSession);
			final QueryResult<Tuple> result = query.getResult();
			try
			{
				final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
				final java.util.Iterator<Tuple> it = result.iterator();
				while (it.hasNext() && rows.size() <= KeyFacts.MAX_ROWS)
				{
					final Tuple tuple = it.next();
					final String[] values = new String[specs.size()];
					for (int i = 0; i < values.length; i++)
					{
						final Object value = tuple.get("K" + i);
						values[i] = value == null ? null : String.valueOf(value);
					}
					final Object system = tuple.get("SYS");
					rows.add(new KeyFacts.Row(system == null ? "" : String.valueOf(system), values));
				}
				final boolean sampled = rows.size() > KeyFacts.MAX_ROWS;
				return KeyFacts.compute(sampled ? rows.subList(0, KeyFacts.MAX_ROWS) : rows, specs, sampled);
			}
			finally
			{
				result.close();
			}
		}
		// deliberately broad, like CrossSystemMeasures: "not measured" is the documented fallback for ANY failure, never a failure of the proposal
		catch (final RuntimeException ex)
		{
			return null;
		}
	}
}
