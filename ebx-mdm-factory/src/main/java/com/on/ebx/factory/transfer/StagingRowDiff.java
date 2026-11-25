package com.on.ebx.factory.transfer;

import java.util.Map;

/**
 * Lot V3-S (2026-10-04, docs/VOLUME.md "Lot V3-S"): whether the staging row a load would WRITE already holds every value the load
 * would write, so that a scope {@code all} load re-applies the whole pipeline (mappings, cleaning, derivations) to every landing row
 * but writes only the rows whose result differs from what staging holds ({@code ebx.StagingLoader.processRow}). Pure, no EBX class:
 * the row and its columns are seen through {@link Stored} and {@link Field}, so the decision is unit-tested without an instance.
 * <p>
 * <b>The comparison form, and why it is exact.</b> {@code ebx.StagingLoader.upsertOne} writes a planned value with {@code
 * ValueContextForUpdate.setValueFromXsString(xs, path)}, which (javap -c on {@code CfContentHolder}, EBX 6.3.0-rc2) is exactly {@code
 * getNode(path).parseXsString(xs)} followed by {@code setValue(parsed, path)}. So the value a write would store is {@code
 * SchemaNode.parseXsString(planned)}; this class parses the planned xs-string the same way and compares the TYPED value with the one
 * the row holds ({@code Adaptation.get}) through {@link StoredValueEquality#sameValue} (a decimal by value, a date by instant, an empty
 * string as null). Comparing the planned string with {@code SchemaNode.formatToXsString(stored)} would be exact only when EBX's canonical
 * text of a value equals the loader's text ({@code "1.0"} versus {@code "1"}, a date-time with or without milliseconds): when the typed
 * comparison finds a difference this class still accepts a field whose formatted stored value is the planned string itself (two sufficient
 * conditions, either one proves the write would change nothing).
 * <p>
 * <b>The safe direction.</b> Whatever cannot be shown equal is reported as DIFFERENT and the row is written exactly as before this lot: a
 * planned string the node cannot parse (the write would be refused too), a multi-valued or computed field, a stored value of an unexpected
 * class, any exception while reading - a doubt costs a write, never a lost update.
 * <p>
 * <b>What is compared</b> - every entry of the row's planned field map (business fields, foreign-key fields, the structural parent link,
 * derived fields, forced nulls), plus {@code _sourceHash} when the table carries it (a row whose landing hash changed but whose mapped
 * values did not must still receive its new hash, otherwise scope {@code new} would later skip a row it should read), plus {@code _status}
 * (the row must already be {@code STAGED}: a row in any other status is written). <b>What is NOT compared</b>: {@code _loadId} and
 * {@code _receivedAt} - they say "the load that last CHANGED this row" (the meaning they already have in scope {@code new}), so they differ
 * from the landing row's whenever the staging row was not rewritten by that very load - and {@code _rejectReason}, which the loader never writes.
 * A script-derived attribute that is APPLIED is not in the planned map (the table triggers keep it), so it is not compared either.
 */
public final class StagingRowDiff
{
	/** The {@code _status} a written staging row carries, and the only one a row can have and still be left alone. */
	public static final String STATUS_STAGED = "STAGED";

	private StagingRowDiff()
	{
	}

	/** One column of the staging table, as the comparison needs to see it. */
	public interface Field
	{
		/** False for a column the comparison cannot reason about (multi-valued, computed, not a terminal value): its row is written. */
		boolean comparable();

		/** True for a foreign key: the planned text is the formatted key of the target row and the stored value comes back as that same text, never parsed. */
		boolean reference();

		/** {@code SchemaNode.parseXsString} - the very call {@code setValueFromXsString} makes; throws when the text is not a legal value. */
		Object parse(String pXsString) throws Exception;

		/** {@code SchemaNode.formatToXsString}. */
		String format(Object pStoredValue);
	}

	/** One existing staging row. */
	public interface Stored
	{
		/** The column named {@code pName}, or {@code null} when the table does not define it. */
		Field field(String pName);

		/** {@code Adaptation.get} of that column. */
		Object value(String pName);

		/** The row's {@code _status}. */
		String status();

		/** The row's {@code _sourceHash}; only asked when {@code pCompareSourceHash} is true. */
		Object sourceHash();
	}

	/**
	 * @param pPlannedFields what the load would write: field name to xs-string (or {@code null}, a forced default or a genuinely empty value);
	 *            a value that is not a string is compared as {@code String.valueOf} of it, the form the write uses
	 * @param pPlannedSourceHash the landing row's {@code _rowHash}
	 * @param pCompareSourceHash whether the live staging table carries {@code _sourceHash} (a table not regenerated since that field was added does not)
	 * @return true only when writing the row would change nothing but its {@code _loadId}/{@code _receivedAt}
	 */
	public static boolean unchanged(final Map<String, Object> pPlannedFields, final String pPlannedSourceHash, final boolean pCompareSourceHash, final Stored pStored)
	{
		if (!StagingRowDiff.STATUS_STAGED.equals(pStored.status()))
		{
			return false;
		}
		if (pCompareSourceHash && !StoredValueEquality.sameValue(pPlannedSourceHash, pStored.sourceHash()))
		{
			return false;
		}
		for (final Map.Entry<String, Object> entry : pPlannedFields.entrySet())
		{
			if (!StagingRowDiff.sameField(entry.getValue(), pStored.value(entry.getKey()), pStored.field(entry.getKey())))
			{
				return false;
			}
		}
		return true;
	}

	/** One planned value against the stored one - see the class Javadoc for the form. */
	static boolean sameField(final Object pPlanned, final Object pStored, final Field pField)
	{
		if (pPlanned == null)
		{
			return StoredValueEquality.sameValue(null, pStored);
		}
		if (pField == null || !pField.comparable())
		{
			return false;
		}
		final String xs = String.valueOf(pPlanned);
		if (pField.reference())
		{
			return StoredValueEquality.sameValue(xs, pStored);
		}
		final Object parsed;
		try
		{
			parsed = pField.parse(xs);
		}
		catch (final Exception ex)
		{
			return false;
		}
		if (StoredValueEquality.sameValue(parsed, pStored))
		{
			return true;
		}
		if (pStored == null)
		{
			return false;
		}
		try
		{
			return xs.equals(pField.format(pStored));
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/**
	 * Lot V3-S, the staging-side {@code Xref} row: true when the existing row already holds every compared text. The caller passes the
	 * same fields in the same order on both sides (project, staging table, staging key, landing dataset, landing table, landing key, source
	 * system, source id - never {@code loadId}/{@code occurredAt}, which only say when the row was last written). Null equals empty.
	 */
	public static boolean sameTexts(final String[] pPlanned, final String[] pStored)
	{
		if (pPlanned.length != pStored.length)
		{
			return false;
		}
		for (int i = 0; i < pPlanned.length; i++)
		{
			if (!StoredValueEquality.sameValue(pPlanned[i], pStored[i]))
			{
				return false;
			}
		}
		return true;
	}
}
