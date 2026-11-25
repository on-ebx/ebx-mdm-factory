package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;

/**
 * Audit F-32 (2026-10-06): ONE read of a table for a whole set of parent keys, instead of one predicate query per parent. Only the primary
 * key of a Factory table is indexed (CLAUDE.md section 5), so every {@code ./entity='5'} query on a non-key field scans the table: the
 * wizard snapshot ran one such scan per source, landing table, entity, attribute and feed, on tables shared by every project. {@link #byKey}
 * answers all the parents of a snapshot with a single scan: a short key set becomes ONE {@code or} predicate (rows of other projects are
 * never materialised), a long one a single unfiltered streaming read joined in memory (the loaders' own pattern, CLAUDE.md section 15). Either
 * way each key's rows come back in the table's own order, exactly what its own predicate query returned.
 * <p>
 * Equality is the one XPath {@code =} would give on a string or a foreign key: the stored text of the field, compared exactly; a null field
 * matches no key (the same reason {@code ./field=''} never matches, see {@link PipelineSteps#scopeEquals}).
 */
final class KeyedRows
{
	/** Beyond this many keys one {@code or} predicate gets long to parse for every row it is evaluated on: a single unfiltered streaming read is cheaper then. */
	static final int MAX_OR_KEYS = 30;

	private KeyedRows()
	{
	}

	/** Pure: {@code ./f='a' or ./f='b'} - the same fragment {@code ProjectDeleter.queryIn} builds. Empty for no key. */
	static String inPredicate(final String pFieldPath, final Collection<String> pKeys)
	{
		final StringBuilder predicate = new StringBuilder();
		for (final String key : pKeys)
		{
			if (predicate.length() > 0)
			{
				predicate.append(" or ");
			}
			predicate.append(pFieldPath).append('=').append(CatalogReader.quote(key));
		}
		return predicate.toString();
	}

	/** Pure: whether {@link #byKey} filters in the query ({@code true}) or streams the whole table and filters in memory. */
	static boolean filterInQuery(final int pKeyCount)
	{
		return pKeyCount <= KeyedRows.MAX_OR_KEYS;
	}

	/** The wanted keys and the rows met so far under each, in arrival order. */
	static final class Buckets<T>
	{
		private final Map<String, List<T>> out = new LinkedHashMap<String, List<T>>();

		Buckets(final Set<String> pWanted)
		{
			for (final String key : pWanted)
			{
				this.out.put(key, new ArrayList<T>());
			}
		}

		/** Keeps the row when its key is wanted; a null key matches nothing. */
		void offer(final String pKey, final T pRow)
		{
			if (pKey == null)
			{
				return;
			}
			final List<T> bucket = this.out.get(pKey);
			if (bucket != null)
			{
				bucket.add(pRow);
			}
		}

		/** Every wanted key is present, with an empty list when no row carries it - the same "nothing found" a per-key query gave. */
		Map<String, List<T>> result()
		{
			return this.out;
		}
	}

	/**
	 * Every row of {@code pTable} whose {@code pKeyField} is one of {@code pKeys}, grouped by that key, in table order. One scan whatever the
	 * number of keys; no scan at all for no key (a per-key loop over nothing never queried either).
	 */
	static Map<String, List<Adaptation>> byKey(final AdaptationTable pTable, final Path pKeyField, final Collection<String> pKeys)
	{
		final Set<String> wanted = new LinkedHashSet<String>(pKeys);
		wanted.remove(null);
		if (wanted.isEmpty())
		{
			return new LinkedHashMap<String, List<Adaptation>>();
		}
		final String predicate = KeyedRows.filterInQuery(wanted.size()) ? KeyedRows.inPredicate(pKeyField.format(), wanted) : null;
		final Buckets<Adaptation> buckets = new Buckets<Adaptation>(wanted);
		// second permissions pass (2026-10-14): inside a user-facing read scope (UserView#scoped) only the rows and the link field the user may see;
		// the engine (no scope) reads every row as before
		final UserView view = UserView.current();
		final RequestResult result = view.rows(pTable, predicate);
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
			{
				final Object key = view.value(row, pKeyField);
				buckets.offer(key == null ? null : String.valueOf(key), row);
			}
		}
		finally
		{
			result.close();
		}
		return buckets.result();
	}
}
