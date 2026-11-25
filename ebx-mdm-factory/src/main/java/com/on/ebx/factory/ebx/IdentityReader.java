package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.quality.ScanGuard;
import com.on.ebx.factory.staging.IdentityFacts;
import com.on.ebx.factory.staging.StagingRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Session;

/**
 * Lot S10 (2026-10-18): the EBX half of {@link IdentityFacts}. For each landing table of the staging proposal it counts the rows of the landing table itself and, when the
 * table has columns the catalog tagged with an identity tag, reads those columns in ONE streaming pass ({@code createRequestResult}, CLAUDE.md sec 15: read each table once)
 * and folds every value to a 128-bit digest; the figures (distinct values, repeated rows, values seen in no other table) are written onto the {@link StagingRules} value
 * objects the prompt renders. Nothing but counts leaves this class - no value is kept, shown or logged - which is why the LLM data policy lets it run at every level and on
 * sensitive columns too ("column names, types, fill rates and counts always go", {@code LlmDataPolicy}).
 * <p>
 * <b>Cost</b>: one pass over each table that has an identity column, bounded by the volume guard the fill rates use ({@code quality.ScanGuard}: past
 * {@code mdm.factory.profile.maxRowsFullScan} rows only the first {@code mdm.factory.profile.sampleRows} are read, the figure says so, and the cross-table comparison is
 * skipped for it); memory is the digests of the distinct values (16 bytes each plus the map entry), at most the sample bound per table. A table without identity column
 * costs one cached row count. The read is cached per table state ({@code ColumnReads}, stamped with the table's last-modification date). Best effort: a table that cannot
 * be read keeps no figure, the pass goes on.
 */
final class IdentityReader
{
	private IdentityReader()
	{
	}

	/**
	 * Measures every table of {@code pTablesByKey} and writes the figures into the tables ({@link StagingRules.LandingTableInfo#landedRows}, {@link
	 * StagingRules.LandingTableInfo#identity}) and their identity columns ({@link StagingRules.LandingColumnInfo#identity}).
	 *
	 * @param pDatasetBySystem the landing dataset name by source system
	 */
	static void measure(final Session pSession, final Adaptation pFactory, final String pLandingDataspace, final Map<String, String> pDatasetBySystem,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey)
	{
		if (pLandingDataspace == null)
		{
			return;
		}
		final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(pLandingDataspace));
		if (home == null)
		{
			return;
		}
		final Map<String, IdentityFacts.Table> measured = new LinkedHashMap<String, IdentityFacts.Table>();
		for (final Map.Entry<String, StagingRules.LandingTableInfo> entry : pTablesByKey.entrySet())
		{
			final StagingRules.LandingTableInfo table = entry.getValue();
			try
			{
				final String datasetName = pDatasetBySystem.get(table.sourceSystem);
				final Adaptation dataset = datasetName == null ? null : home.findAdaptationOrNull(AdaptationName.forName(datasetName));
				if (dataset == null)
				{
					continue;
				}
				final QualitySuggester.LandingTableRef ref = new QualitySuggester.LandingTableRef(dataset, dataset.getTable(Path.parse("/root/" + table.name)));
				final long rows = ColumnReads.tableRows(pSession, ref);
				if (rows >= 0L)
				{
					table.landedRows = Long.valueOf(rows);
				}
				final IdentityFacts.Table facts = IdentityReader.read(pSession, ref, table);
				if (facts != null)
				{
					measured.put(entry.getKey(), facts);
				}
			}
			catch (final RuntimeException ex)
			{
				// best effort: a table that cannot be read (not in the published landing model yet...) keeps no figure
			}
		}
		IdentityFacts.crossTable(measured);
		for (final Map.Entry<String, IdentityFacts.Table> entry : measured.entrySet())
		{
			final StagingRules.LandingTableInfo table = pTablesByKey.get(entry.getKey());
			table.identity = entry.getValue();
			for (final StagingRules.LandingColumnInfo column : table.columns)
			{
				column.identity = entry.getValue().columns.get(column.fieldName);
			}
		}
	}

	/** One pass over the identity columns of the table; null when it has none. Served from the cache while the table is unchanged. */
	private static IdentityFacts.Table read(final Session pSession, final QualitySuggester.LandingTableRef pRef, final StagingRules.LandingTableInfo pTable)
	{
		final List<String> fields = new ArrayList<String>();
		final List<IdentityFacts.Kind> kinds = new ArrayList<IdentityFacts.Kind>();
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			final IdentityFacts.Kind kind = IdentityFacts.kindOf(column);
			if (kind != null && column.fieldName != null)
			{
				fields.add(column.fieldName);
				kinds.add(kind);
			}
		}
		if (fields.isEmpty())
		{
			return null;
		}
		final ScanGuard.Decision decision = ColumnReads.decision(pSession, pRef);
		final String key = ColumnReads.tableKey(pSession, pRef) + "|identity|" + String.join(",", fields) + "|" + kinds;
		final long stamp = ColumnReads.stamp(pRef);
		final IdentityFacts.Table cached = ColumnReads.IDENTITY.get(key, stamp);
		if (cached != null && cached.sampled == decision.sampled)
		{
			return cached;
		}
		final Path[] paths = new Path[fields.size()];
		for (int i = 0; i < paths.length; i++)
		{
			paths[i] = Path.parse("./" + fields.get(i));
		}
		final IdentityFacts.Accumulator accumulator = new IdentityFacts.Accumulator(fields, kinds, decision.sampled);
		final RequestResult rows = pRef.table.createRequestResult(null);
		try
		{
			long read = 0L;
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (decision.sampled && read >= decision.rowsToRead)
				{
					break;
				}
				read++;
				final String[] values = new String[paths.length];
				for (int i = 0; i < paths.length; i++)
				{
					final Object value = row.get(paths[i]);
					values[i] = value == null ? null : String.valueOf(value);
				}
				accumulator.row(values);
			}
		}
		finally
		{
			rows.close();
		}
		final IdentityFacts.Table facts = accumulator.finish();
		ColumnReads.IDENTITY.put(key, stamp, facts, facts.weight());
		return facts;
	}
}
