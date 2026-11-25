package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.quality.ScanGuard;
import com.on.ebx.factory.staging.RowFacts;
import com.on.ebx.factory.staging.StagingRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Session;

/**
 * B2B test (2026-10-23, docs/TARGET-MAPPING.md sec 23): the EBX half of {@link RowFacts}. For each landing table of the staging proposal it reads the text columns in ONE
 * streaming pass ({@code createRequestResult}, CLAUDE.md sec 15: read each table once), bounded by the volume guard the fill rates use ({@code quality.ScanGuard}) and by
 * {@link RowFacts#ROW_CAP} rows kept, measures them and, once every table is read, compares each column with the keys of the other sources ({@link RowFacts#crossTable}).
 * The figures are written onto the columns the prompt renders ({@link StagingRules.LandingColumnInfo#rowFacts}); nothing else leaves this class, nothing is logged.
 * <p>
 * <b>Data policy</b>: counts always go (own key, keys of another source, a continuation pair); the shapes of a column only when {@code pShapesAllowed} says so (not for a
 * sensitive column, not under {@code NONE}); the codes of a companion column only when real values may be shown. Best effort: a table that cannot be read keeps no figure.
 * <b>Cost</b>: one pass per landing table on top of the identity pass; at most {@link RowFacts#ROW_CAP} rows of text are held at a time, then only counts and the key
 * values of each table until the cross-table pass ends.
 */
final class RowFactsReader
{
	/** Whether the shapes of one landing column may be shown: {@code (table, field) -> boolean}. */
	interface ShapePolicy
	{
		boolean shapesAllowed(StagingRules.LandingTableInfo pTable, StagingRules.LandingColumnInfo pColumn);
	}

	private RowFactsReader()
	{
	}

	static void measure(final Session pSession, final Adaptation pFactory, final String pLandingDataspace, final Map<String, String> pDatasetBySystem,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final ShapePolicy pShapes, final boolean pShowValues)
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
		final Map<String, RowFacts.Table> measured = new LinkedHashMap<String, RowFacts.Table>();
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
				final RowFacts.Table facts = RowFactsReader.read(pSession, ref, table);
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
		RowFacts.crossTable(measured);
		for (final Map.Entry<String, RowFacts.Table> entry : measured.entrySet())
		{
			final StagingRules.LandingTableInfo table = pTablesByKey.get(entry.getKey());
			for (final StagingRules.LandingColumnInfo column : table.columns)
			{
				column.rowFacts = RowFacts.columnText(entry.getValue(), column.fieldName, pShapes.shapesAllowed(table, column), pShowValues);
			}
		}
	}

	/** One pass over the text columns of the table (the technical ones, starting with an underscore, left out). */
	private static RowFacts.Table read(final Session pSession, final QualitySuggester.LandingTableRef pRef, final StagingRules.LandingTableInfo pTable)
	{
		final List<String> fields = new ArrayList<String>();
		String keyField = null;
		int keys = 0;
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			if (column.fieldName == null || column.fieldName.startsWith("_"))
			{
				continue;
			}
			if (column.sourceKey)
			{
				keys++;
				keyField = column.fieldName;
			}
			final String type = column.inferredType == null || column.inferredType.trim().isEmpty() ? "STRING" : column.inferredType.trim();
			if ("STRING".equalsIgnoreCase(type) || column.sourceKey)
			{
				fields.add(column.fieldName);
			}
		}
		if (fields.size() < 2)
		{
			return null;
		}
		final ScanGuard.Decision decision = ColumnReads.decision(pSession, pRef);
		final Path[] paths = new Path[fields.size()];
		for (int i = 0; i < paths.length; i++)
		{
			paths[i] = Path.parse("./" + fields.get(i));
		}
		final RowFacts.Accumulator accumulator = new RowFacts.Accumulator(pTable.sourceSystem, pTable.name, fields, keys == 1 ? keyField : null, decision.sampled);
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
		return accumulator.finish();
	}
}
