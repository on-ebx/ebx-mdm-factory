package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the module expects of the Match and Merge add-on's own metadataset tables ({@code <dataset>_<table>_MDS}, see {@link MameMetadataset}),
 * in ONE place (audit F-27). Six classes of the module read those tables by name and by integer state code; the add-on documents neither, and a
 * rename on a new build used to turn every one of those reads into "empty" - no merge, no lineage, no retire notice - with no error anywhere.
 * {@code ebx.MameMetadataGuard} checks a real table against this contract before a reader uses it and reports a difference through
 * {@link MameSchemaWatch}; {@code MameMetadataContractTest} pins it to the add-on's own {@code MetadataTemplate.xsd} reading of 2026-09-22/23
 * (CLAUDE.md section 8).
 * <p>
 * Two lists per table: a {@code required} field is one without which the reader cannot work at all (the table is then reported unavailable); an
 * {@code expected} field is one a reader uses when present (a difference is reported once, the table stays readable for the other fields).
 */
public final class MameMetadataContract
{
	/** The expectations of one metadataset table. */
	public static final class TableContract
	{
		public final String table;
		public final List<String> required;
		public final List<String> expected;

		TableContract(final String pTable, final List<String> pRequired, final List<String> pExpected)
		{
			this.table = pTable;
			this.required = Collections.unmodifiableList(pRequired);
			this.expected = Collections.unmodifiableList(pExpected);
		}
	}

	/** {@code RecordMetadata.state} of a golden record - the add-on's {@code State} enumeration order (UNSET 0, GOLDEN 1, MERGED 2, PENDING_APPROVAL 3, DELETED 4). */
	public static final int STATE_GOLDEN = 1;
	public static final int STATE_MERGED = 2;
	public static final int STATE_PENDING_APPROVAL = 3;
	public static final int STATE_DELETED = 4;

	/** The enumeration names, in the add-on's order: the index IS the stored code. {@code ebx.MameStateCodes} compares this order with the add-on's own {@code State} enum at run time. */
	public static final List<String> STATE_NAMES = Collections.unmodifiableList(Arrays.asList("UNSET", "GOLDEN", "MERGED", "PENDING_APPROVAL", "DELETED"));

	private static final List<TableContract> TABLES = Collections.unmodifiableList(Arrays.asList(
		new TableContract("RecordMetadata", Arrays.asList("functionalId", "state"),
			Arrays.asList("goldenId", "groupId", "mergingProcessId", "hasSuspects", "suspectedCount", "autoCreated")),
		new TableContract("Suspected", Arrays.asList("sourceId", "targetId"), Arrays.asList("id", "matchingProcessId")),
		new TableContract("MergingProcess", Arrays.asList("id", "isUnmerged"), Collections.<String>emptyList()),
		new TableContract("MergeValueLineageCompact", Arrays.asList("mergingProcessId", "Items"), Collections.<String>emptyList()),
		new TableContract("FalsePositiveDecision", Arrays.asList("sourceId", "targetId"), Arrays.asList("id", "user", "decisionDate"))));

	private MameMetadataContract()
	{
	}

	/** The contract of {@code pTableName}, or null when the module only needs that table to exist (no field is read by name). */
	public static TableContract of(final String pTableName)
	{
		for (final TableContract contract : MameMetadataContract.TABLES)
		{
			if (contract.table.equals(pTableName))
			{
				return contract;
			}
		}
		return null;
	}

	/** Every table the contract names, for the test. */
	public static List<TableContract> all()
	{
		return MameMetadataContract.TABLES;
	}

	/** The state name a stored code stands for, or {@code UNSET} for a code outside the enumeration. */
	public static String stateName(final int pCode)
	{
		return pCode >= 0 && pCode < MameMetadataContract.STATE_NAMES.size() ? MameMetadataContract.STATE_NAMES.get(pCode) : "UNSET";
	}
}
