package com.on.ebx.factory.ebx;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.on.ebx.factory.master.MameMetadataContract;
import com.on.ebx.factory.master.MameMetadataContract.TableContract;
import com.on.ebx.factory.master.MameSchemaWatch;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;

/**
 * The one door through which a reader gets a table of the Match and Merge add-on's metadataset (audit F-27): every class that reads the {@code _MDS}
 * tables ({@link MameMetadatasetReads}, {@code MergeLogWatcher}, {@code MatchingResultsBuilder} and so {@code GoldenRecordsReader}) resolves the table here.
 * <ul>
 * <li>No metadataset at all is the ordinary "matching was never activated for this table": answered with null, no finding, no log.</li>
 * <li>A metadataset that exists but lacks the table, lacks a field {@link MameMetadataContract} marks required, or whose {@code state} codes no longer
 * match the add-on's own enumeration ({@link MameStateCodes}) is a schema difference: ONE WARN line in {@code kernel.log} the first time, a finding in
 * {@link MameSchemaWatch} for every screen that wants to say "merge / lineage information unavailable", and null for the reader (which degrades as it
 * always did - but now somebody was told).</li>
 * <li>A missing field that is only "expected" is reported the same way and the table is still returned: the reader that uses the field copes with its
 * absence.</li>
 * </ul>
 */
final class MameMetadataGuard
{
	private static final Set<String> CHECKED_OK = ConcurrentHashMap.newKeySet();

	private MameMetadataGuard()
	{
	}

	/** Where the guard gets a table and its row schema from: the add-on's metadataset in production, a stand-in in the test (the EBX dataset and table types cannot be faked). */
	interface Source<T>
	{
		/** The table of that name; null or an exception when there is none. */
		T table(String pTableName);

		/** Its row schema (the table occurrence root node), or null. */
		SchemaNode rowSchema(T pTable);
	}

	/** The table, or null (never an exception) when it cannot be used. {@code pMetadataset} null = no metadataset: null, quietly. */
	static AdaptationTable table(final Adaptation pMetadataset, final String pTableName)
	{
		if (pMetadataset == null)
		{
			return null;
		}
		return MameMetadataGuard.resolve(MameMetadataGuard.nameOf(pMetadataset), new Source<AdaptationTable>()
		{
			@Override
			public AdaptationTable table(final String pName)
			{
				return pMetadataset.getTable(Path.parse("/root/" + pName));
			}

			@Override
			public SchemaNode rowSchema(final AdaptationTable pTable)
			{
				return pTable.getTableOccurrenceRootNode();
			}
		}, pTableName);
	}

	/** {@link #table(Adaptation, String)} on any {@link Source}. */
	static <T> T resolve(final String pMetadatasetName, final Source<T> pSource, final String pTableName)
	{
		final String key = pMetadatasetName + "|" + pTableName;
		T table = null;
		try
		{
			table = pSource.table(pTableName);
		}
		catch (final RuntimeException ex)
		{
			// reported below as a missing table
		}
		final SchemaNode root = table == null ? null : pSource.rowSchema(table);
		if (table == null || root == null)
		{
			MameMetadataGuard.report(pMetadatasetName, pTableName, "the table does not exist in the add-on's metadataset", false);
			return null;
		}
		if (MameMetadataGuard.CHECKED_OK.contains(key))
		{
			return table;
		}
		final String stateMismatch = MameStateCodes.mismatch();
		if ("RecordMetadata".equals(pTableName) && stateMismatch != null)
		{
			MameMetadataGuard.report(pMetadatasetName, pTableName, stateMismatch, false);
			return null;
		}
		final TableContract contract = MameMetadataContract.of(pTableName);
		boolean requiredMissing = false;
		boolean expectedMissing = false;
		if (contract != null)
		{
			for (final String field : contract.required)
			{
				if (!MameMetadataGuard.has(root, field))
				{
					MameMetadataGuard.report(pMetadatasetName, pTableName, "the field " + field + " does not exist", false);
					requiredMissing = true;
				}
			}
			for (final String field : contract.expected)
			{
				if (!MameMetadataGuard.has(root, field))
				{
					MameMetadataGuard.report(pMetadatasetName, pTableName, "the field " + field + " does not exist", true);
					expectedMissing = true;
				}
			}
		}
		if (requiredMissing)
		{
			return null;
		}
		if (!expectedMissing)
		{
			MameMetadataGuard.CHECKED_OK.add(key);
		}
		return table;
	}

	private static boolean has(final SchemaNode pRoot, final String pField)
	{
		try
		{
			return pRoot.getNode(Path.parse("./" + pField)) != null;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	private static String nameOf(final Adaptation pMetadataset)
	{
		try
		{
			return pMetadataset.getAdaptationName().getStringName();
		}
		catch (final RuntimeException ex)
		{
			return "(unnamed metadataset)";
		}
	}

	private static void report(final String pMetadataset, final String pTable, final String pProblem, final boolean pStillReadable)
	{
		if (MameSchemaWatch.report(pMetadataset, pTable, pProblem))
		{
			try
			{
				LoggingCategory.getKernel().warn("[mdm-factory] Match and Merge metadata unavailable: " + pMetadataset + "/" + pTable + ": " + pProblem
					+ (pStillReadable ? ". The table is still read, but whatever uses that field is NOT available (not empty)"
						: ". The merge, lineage and golden-record information read from this table is NOT available (not empty)")
					+ " until the module is checked against this add-on build; see docs/OPERATIONS.md.");
			}
			catch (final RuntimeException ex)
			{
				// no kernel logger (a test)
			}
			catch (final LinkageError ex)
			{
				// same
			}
		}
	}

	/** For the reason text of a reader that answers "unavailable": the findings in one phrase, or the empty string when there is none. */
	static String reasonSuffix()
	{
		final String summary = MameSchemaWatch.summary(java.util.Locale.ENGLISH, null);
		return summary == null ? "" : " - " + summary;
	}
}
