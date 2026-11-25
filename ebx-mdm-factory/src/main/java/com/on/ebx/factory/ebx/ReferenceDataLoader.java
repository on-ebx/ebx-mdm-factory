package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.accel.ReferenceTable;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Writes the rows a pack ships ({@code ReferenceTable}: CSV + the header-to-field map of {@code pack.json}) into the table of the domain dataset,
 * in {@link #BATCH}-row procedures (lot C1-D, step ACCEL_REFERENCE_ROWS). The mapping and conversion half is pure and tested on the real CSVs
 * ({@code ReferenceDataLoaderTest}). Idempotent: a row whose key already exists is left alone (counted {@code existing}); a blank cell is not
 * written (EBX stores an empty string as null anyway); a repeated key in the file keeps its first row.
 */
final class ReferenceDataLoader
{
	static final int BATCH = 500;

	private ReferenceDataLoader()
	{
	}

	/** What a load did. */
	static final class Result
	{
		int created;
		int existing;
		int duplicatesInFile;
		int withoutKey;
	}

	/** The pack's records keyed by field name, blank cells dropped, rows without a key or with a repeated key left out (counted in {@code pResult}). */
	static List<Map<String, String>> rows(final ReferenceTable pTable, final Result pResult)
	{
		final List<Map<String, String>> out = new ArrayList<Map<String, String>>();
		final Set<String> seen = new LinkedHashSet<String>();
		for (final Map<String, String> record : pTable.records())
		{
			final java.util.LinkedHashMap<String, String> clean = new java.util.LinkedHashMap<String, String>();
			for (final Map.Entry<String, String> e : record.entrySet())
			{
				final String v = e.getValue() == null ? "" : e.getValue().trim();
				if (!v.isEmpty())
				{
					clean.put(e.getKey(), v);
				}
			}
			final String key = clean.get(pTable.key());
			if (key == null)
			{
				if (pResult != null)
				{
					pResult.withoutKey++;
				}
				continue;
			}
			if (!seen.add(key))
			{
				if (pResult != null)
				{
					pResult.duplicatesInFile++;
				}
				continue;
			}
			out.add(clean);
		}
		return out;
	}

	/** The value a cell becomes for a field whose occurrences are {@code pType}; anything not recognised stays the text (foreign keys, enumerations). */
	static Object convert(final String pText, final Class<?> pType)
	{
		if (pType == null || pText == null)
		{
			return pText;
		}
		if (Integer.class.equals(pType))
		{
			return Integer.valueOf(pText.trim());
		}
		if (BigDecimal.class.equals(pType))
		{
			return new BigDecimal(pText.trim());
		}
		if (Boolean.class.equals(pType))
		{
			final String t = pText.trim().toLowerCase(Locale.ROOT);
			return Boolean.valueOf("true".equals(t) || "1".equals(t) || "yes".equals(t) || "y".equals(t));
		}
		if (java.util.Date.class.equals(pType))
		{
			try
			{
				return new SimpleDateFormat("yyyy-MM-dd").parse(pText.trim());
			}
			catch (final ParseException ex)
			{
				throw new IllegalArgumentException("Not a date (yyyy-MM-dd): " + pText);
			}
		}
		return pText;
	}

	static Result load(final Session pSession, final Adaptation pDataset, final ReferenceTable pTable) throws OperationException
	{
		final Result result = new Result();
		final List<Map<String, String>> rows = ReferenceDataLoader.rows(pTable, result);
		final AdaptationTable table = pDataset.getTable(Path.parse(pTable.path()));
		if (table == null)
		{
			throw EngineRefusal.coded("missing.domainTable", "The table " + pTable.path() + " is not in the domain dataset.", pTable.path());
		}
		final SchemaNode root = table.getTableOccurrenceRootNode();
		final SchemaNode keyNode = root.getNode(Path.parse("./" + pTable.key()));
		if (keyNode == null)
		{
			throw EngineRefusal.coded("missing.domainTableKey", "The key field " + pTable.key() + " is not in " + pTable.path() + ".", pTable.key(), pTable.path());
		}
		for (int from = 0; from < rows.size(); from += ReferenceDataLoader.BATCH)
		{
			final List<Map<String, String>> batch = rows.subList(from, Math.min(rows.size(), from + ReferenceDataLoader.BATCH));
			final int[] counts = new int[2];
			final ProcedureResult done = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					for (final Map<String, String> record : batch)
					{
						final Object key = ReferenceDataLoader.convert(record.get(pTable.key()), keyNode.getOccurrencesJavaClass());
						if (table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { key })) != null)
						{
							counts[1]++;
							continue;
						}
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
						for (final Map.Entry<String, String> field : record.entrySet())
						{
							final Path path = Path.parse("./" + field.getKey());
							final SchemaNode node = root.getNode(path);
							if (node == null)
							{
								throw new IllegalStateException("Field " + field.getKey() + " is not in " + pTable.path());
							}
							value.setValue(ReferenceDataLoader.convert(field.getValue(), node.getOccurrencesJavaClass()), path);
						}
						pContext.doCreateOccurrence(value, table);
						counts[0]++;
					}
				}
			});
			if (done.hasFailed())
			{
				throw EngineRefusal.coded("external.referenceRows", "Writing the rows of " + pTable.path() + " failed after " + result.created + " row(s): "
					+ done.getExceptionFullMessage(Locale.ENGLISH), pTable.path());
			}
			result.created += counts[0];
			result.existing += counts[1];
		}
		return result;
	}

	/** Number of rows currently in the table (a read of the whole table is avoided: a count request). */
	static int count(final Adaptation pDataset, final String pTablePath)
	{
		final AdaptationTable table = pDataset.getTable(Path.parse(pTablePath));
		final Request request = table.createRequest();
		final RequestResult rows = request.execute();
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}
}
