package com.on.ebx.factory.transfer;

import java.util.Map;

/**
 * Lot V3-S2 (2026-10-04, docs/VOLUME.md "Lot V3-S2"): a fixed-size DIGEST of exactly what {@link StagingRowDiff} compares for a staging row, so that a scope
 * {@code all} load can answer "does this row already hold every planned value" from ONE streaming read of the staging table (the digest of every row, kept
 * in memory) instead of one random read of the row per landing row - the same cure lot V3-M2 applied to the master load. Pure, no EBX class: the columns are
 * seen through {@link StagingRowDiff.Field} and {@link StagingRowDiff.Stored}, so {@code StagingRowDigestTest} pins the digest against {@link
 * StagingRowDiff#unchanged} without an instance.
 * <p>
 * <b>What a digest stands for.</b> A LAYOUT is the sorted list of staging columns a feed plans. The digest of a row under a layout is the {@link RowDigest}
 * (SHA-256 truncated to 128 bits, the encoding of {@link StoredValueEquality}) of, in this order: the typed value of every column of the layout, the
 * {@code _sourceHash} (only when the table carries it), and a flag "the row is {@code STAGED}". The PLANNED side builds the typed values the way a write
 * does ({@code SchemaNode.parseXsString} of the planned xs-string - the call {@code setValueFromXsString} makes - or the key text itself for a foreign key,
 * {@code null} for a planned null) and the flag is true; the STORED side reads {@code Adaptation.get} of each column and {@code _status}. Two digests are
 * equal exactly when every compared pair satisfies {@link StoredValueEquality#sameValue} (the equivalence {@code RowDigestTest} pins), which is what {@link
 * StagingRowDiff#sameField} tests first. So <b>equal digests imply {@code StagingRowDiff.unchanged}</b>; the converse is not claimed (see below).
 * <p>
 * <b>The safe direction.</b> Anything that cannot be shown equal gives {@link #UNKNOWN}, which equals nothing, and the caller then reads the row and decides
 * with {@code StagingRowDiff} exactly as lot V3-S did: a planned value on a column the table does not define, a multi-valued, computed or non-terminal column,
 * a planned text the node cannot parse, a value of a class the digest cannot encode (it would be compared with {@code equals}), any exception while reading.
 * A row whose values are equal only through {@code StagingRowDiff}'s SECOND test (the stored value formats to the planned text although it does not parse
 * to an equal typed value) has different digests and takes the read path too - a doubt costs a read, never a lost update. Only a 128-bit collision could turn
 * a different row into an "unchanged" one.
 * <p>
 * <b>Why one 128-bit digest per layout, not one 64-bit hash per column.</b> Several feeds can write the same staging table, each planning its own columns
 * (a feed supplies some attributes, the others are forced to null, a child feed adds the link to its parent). The column set a stored row will be compared on
 * is the one of the feed that will plan it, unknown when the table is scanned - so the scan computes one digest per DISTINCT layout of the entity (nearly
 * always one: every feed plans every accepted attribute, supplied or forced to null), 16 bytes each. A per-column hash would let any feed compare any subset,
 * at 8 bytes x columns (40 columns: 320 bytes a row against 32), exact all the same; the layouts buy ten times less memory and stay exact because a row is
 * compared only under the layout its feed announced ({@link #sameColumns}: any other planned set goes to the read path).
 */
public final class StagingRowDigest
{
	/** No digest: the row must be read and compared with {@link StagingRowDiff}. Shared, never mutated. */
	public static final long[] UNKNOWN = new long[0];

	private StagingRowDigest()
	{
	}

	/** The columns of the staging table, by name: the same {@link StagingRowDiff.Field} the comparison uses. */
	public interface Columns
	{
		/** The column named {@code pName}, or {@code null} when the table does not define it. */
		StagingRowDiff.Field field(String pName);
	}

	/**
	 * True when the planned map holds exactly the columns of the layout - the only case a planned digest stands for the row. The caller passes the sorted
	 * layout and the planned map of a row (insertion order is not the layout order).
	 */
	public static boolean sameColumns(final String[] pColumns, final Map<String, Object> pPlannedFields)
	{
		if (pPlannedFields.size() != pColumns.length)
		{
			return false;
		}
		for (final String column : pColumns)
		{
			if (!pPlannedFields.containsKey(column))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The digest of what a load would write for a row: {@code pColumns} (a layout) read in the planned map, {@code pPlannedSourceHash} when {@code
	 * pCompareSourceHash}, and "STAGED". {@link #UNKNOWN} when a value cannot be shown equal (see the class Javadoc).
	 */
	public static long[] planned(final String[] pColumns, final Map<String, Object> pPlannedFields, final String pPlannedSourceHash, final boolean pCompareSourceHash,
		final Columns pTable)
	{
		final Object[] values = new Object[pColumns.length + (pCompareSourceHash ? 1 : 0) + 1];
		for (int i = 0; i < pColumns.length; i++)
		{
			final Object planned = pPlannedFields.get(pColumns[i]);
			if (planned == null)
			{
				values[i] = null; // StagingRowDiff.sameField: sameValue(null, stored)
				continue;
			}
			final StagingRowDiff.Field field = pTable.field(pColumns[i]);
			if (field == null || !field.comparable())
			{
				return StagingRowDigest.UNKNOWN;
			}
			final String xs = String.valueOf(planned);
			if (field.reference())
			{
				values[i] = xs; // a foreign key is its key text on both sides, never parsed
				continue;
			}
			try
			{
				values[i] = field.parse(xs);
			}
			catch (final Exception ex)
			{
				return StagingRowDigest.UNKNOWN;
			}
		}
		int next = pColumns.length;
		if (pCompareSourceHash)
		{
			values[next++] = pPlannedSourceHash;
		}
		values[next] = Boolean.TRUE;
		return StagingRowDigest.words(values);
	}

	/** The digest of a stored row under the same layout: the values {@code Adaptation.get} answers, the {@code _sourceHash}, and whether {@code _status} is STAGED. */
	public static long[] stored(final String[] pColumns, final StagingRowDiff.Stored pRow, final boolean pCompareSourceHash)
	{
		try
		{
			final Object[] values = new Object[pColumns.length + (pCompareSourceHash ? 1 : 0) + 1];
			for (int i = 0; i < pColumns.length; i++)
			{
				values[i] = pRow.value(pColumns[i]);
			}
			int next = pColumns.length;
			if (pCompareSourceHash)
			{
				values[next++] = pRow.sourceHash();
			}
			values[next] = Boolean.valueOf(StagingRowDiff.STATUS_STAGED.equals(pRow.status()));
			return StagingRowDigest.words(values);
		}
		catch (final RuntimeException ex)
		{
			return StagingRowDigest.UNKNOWN; // a row that cannot be read here is read, and compared, by the per-row path
		}
	}

	/** Whether a planned digest equals the stored one at the position of its layout; false whenever either side is unknown. */
	public static boolean same(final long[] pPlanned, final long[] pStoredEntry, final int pLayoutIndex)
	{
		if (pPlanned == null || pPlanned.length != 2 || pStoredEntry == null || pStoredEntry.length < 2 * (pLayoutIndex + 1))
		{
			return false;
		}
		// lot IDX: the compact index keeps "no digest known" as zero words (a digest whose 128 bits are zero is treated the same way: a read, never a lost update)
		if (pStoredEntry[2 * pLayoutIndex] == 0L && pStoredEntry[2 * pLayoutIndex + 1] == 0L)
		{
			return false;
		}
		return pStoredEntry[2 * pLayoutIndex] == pPlanned[0] && pStoredEntry[2 * pLayoutIndex + 1] == pPlanned[1];
	}

	private static long[] words(final Object[] pValues)
	{
		final long[] words = RowDigest.of(pValues, null).words();
		return words == null ? StagingRowDigest.UNKNOWN : words;
	}
}
