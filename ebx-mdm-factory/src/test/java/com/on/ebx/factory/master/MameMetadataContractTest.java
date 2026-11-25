package com.on.ebx.factory.master;

import java.util.Locale;

import com.on.ebx.factory.master.MameMetadataContract.TableContract;

/**
 * Audit F-27: the contract with the Match and Merge add-on's metadataset tables ({@link MameMetadataContract}) and the registry of what was found wrong
 * ({@link MameSchemaWatch}). JUnit-free: run the main.
 */
public final class MameMetadataContractTest
{
	public static void main(final String[] pArgs)
	{
		MameMetadataContractTest.contract();
		MameMetadataContractTest.stateCodes();
		MameMetadataContractTest.watch();
		System.out.println("MameMetadataContractTest OK");
	}

	private static void contract()
	{
		final TableContract record = MameMetadataContract.of("RecordMetadata");
		check("RecordMetadata needs functionalId and state", record != null && record.required.contains("functionalId") && record.required.contains("state"));
		check("RecordMetadata is expected to carry the golden and merge columns", record.expected.containsAll(java.util.Arrays.asList("goldenId", "mergingProcessId", "hasSuspects", "suspectedCount")));
		check("MergingProcess is read by id and isUnmerged", MameMetadataContract.of("MergingProcess").required.containsAll(java.util.Arrays.asList("id", "isUnmerged")));
		check("Suspected is read by sourceId and targetId", MameMetadataContract.of("Suspected").required.containsAll(java.util.Arrays.asList("sourceId", "targetId")));
		check("the compact lineage table is read by mergingProcessId and Items", MameMetadataContract.of("MergeValueLineageCompact").required.containsAll(java.util.Arrays.asList("mergingProcessId", "Items")));
		check("a table nobody reads by field has no contract (it only has to exist)", MameMetadataContract.of("Matched") == null && MameMetadataContract.of(null) == null);
		for (final TableContract table : MameMetadataContract.all())
		{
			check(table.table + " lists no field twice", new java.util.HashSet<String>(table.required).size() == table.required.size()
				&& java.util.Collections.disjoint(table.required, table.expected));
		}
	}

	private static void stateCodes()
	{
		check("GOLDEN is 1, MERGED 2, PENDING_APPROVAL 3, DELETED 4 (measured 2026-09-22/23)", MameMetadataContract.STATE_GOLDEN == 1 && MameMetadataContract.STATE_MERGED == 2
			&& MameMetadataContract.STATE_PENDING_APPROVAL == 3 && MameMetadataContract.STATE_DELETED == 4);
		check("the codes are the indexes of the names", MameMetadataContract.STATE_NAMES.indexOf("GOLDEN") == MameMetadataContract.STATE_GOLDEN
			&& MameMetadataContract.STATE_NAMES.indexOf("MERGED") == MameMetadataContract.STATE_MERGED
			&& MameMetadataContract.STATE_NAMES.indexOf("PENDING_APPROVAL") == MameMetadataContract.STATE_PENDING_APPROVAL
			&& MameMetadataContract.STATE_NAMES.indexOf("DELETED") == MameMetadataContract.STATE_DELETED && MameMetadataContract.STATE_NAMES.indexOf("UNSET") == 0);
		check("stateName", "MERGED".equals(MameMetadataContract.stateName(2)) && "UNSET".equals(MameMetadataContract.stateName(9)) && "UNSET".equals(MameMetadataContract.stateName(-1)));
	}

	private static void watch()
	{
		MameSchemaWatch.clearAll();
		check("nothing found, nothing to say", !MameSchemaWatch.any() && MameSchemaWatch.summary(Locale.ENGLISH, null) == null && MameSchemaWatch.details("M_MDS") == null);
		check("the first report is the one to log", MameSchemaWatch.report("Master_Company_MDS", "RecordMetadata", "the field state does not exist"));
		check("a repeat is not", !MameSchemaWatch.report("Master_Company_MDS", "RecordMetadata", "the field state does not exist"));
		check("another problem on the same table is new", MameSchemaWatch.report("Master_Company_MDS", "RecordMetadata", "the field functionalId does not exist"));
		check("findings are kept per metadataset", MameSchemaWatch.findingsOf("Master_Company_MDS").size() == 2 && MameSchemaWatch.findingsOf("Other_MDS").isEmpty());
		check("the visible text in English", MameSchemaWatch.summary(Locale.ENGLISH, "Master_Company_MDS").contains("unavailable")
			&& MameSchemaWatch.summary(Locale.ENGLISH, "Master_Company_MDS").contains("Master_Company_MDS/RecordMetadata: the field state does not exist"));
		check("the visible text in French", MameSchemaWatch.summary(Locale.FRENCH, "Master_Company_MDS").contains("indisponibles"));
		check("another metadataset has nothing to show", MameSchemaWatch.summary(Locale.ENGLISH, "Other_MDS") == null);
		for (int i = 0; i < 5; i++)
		{
			MameSchemaWatch.report("Master_Company_MDS", "Suspected", "problem " + i);
		}
		check("the line is cut after three findings", MameSchemaWatch.details("Master_Company_MDS").endsWith("; ..."));
		MameSchemaWatch.clear("Master_Company_MDS");
		check("a metadataset checked again can be cleared", !MameSchemaWatch.any());
		MameSchemaWatch.clearAll();
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
