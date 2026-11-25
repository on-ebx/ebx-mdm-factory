package com.on.ebx.factory.transfer;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * Master-load lot V3-M (2026-10-04, docs/VOLUME.md "lot V3-M"): whether a value a load WOULD write onto a
 * record is the value the record already holds, so that a row whose every planned value is already stored is
 * not written at all ({@code ebx.MasterLoader.planRow}). Pure, no EBX class - {@code StoredValueEqualityTest}
 * runs without an instance.
 * <p>
 * <b>Why not a plain {@code equals}.</b> Both sides come from the EBX Java API, but they are not the same kind
 * of object: the planned value is read off a STAGING record (or computed - a foreign key is the formatted key of
 * the parent's master record), the stored one off the MASTER record through {@code Adaptation#get}. For the same
 * declared type EBX answers the same Java class, yet three differences are real and each would make an
 * unchanged row look changed at every load (the very cost this lot removes):
 * <ul>
 * <li><b>Empty versus absent.</b> EBX stores an empty string as null (CLAUDE.md sec 9, "An XPath predicate
 * {@code ./field=''} never matches a record whose field is empty"), and a multi-valued field that was never set
 * reads as null or as an empty list depending on the path. So {@code ""} equals null, and a null list equals an
 * empty one.</li>
 * <li><b>Numbers.</b> {@code BigDecimal.equals} compares the scale ({@code 10} is not {@code 10.0}), and a value
 * planned as an {@code Integer} may be answered back as a {@code BigDecimal} (an {@code xs:decimal} key, a
 * counter). Two numbers are equal when they are numerically equal, whatever their boxed type.</li>
 * <li><b>Dates.</b> Compared by their time in milliseconds, never by class ({@code java.sql.Timestamp} is a
 * {@code Date} whose {@code equals} is asymmetric).</li>
 * </ul>
 * <b>The safe direction.</b> Whatever this class cannot show to be equal is reported as DIFFERENT, and a
 * different value means the row is written exactly as before this lot: a doubt costs a write, never a lost
 * update. In particular a {@code String} is never compared with a non-string through {@code toString} - the
 * only place the two sides legitimately differ in type is a foreign key, which EBX answers as the formatted key
 * string and the loader plans as the same formatted key string.
 */
public final class StoredValueEquality
{
	private StoredValueEquality()
	{
	}

	/**
	 * @param pPlanned the value the load would write
	 * @param pStored the value the record holds now ({@code Adaptation#get})
	 * @return true only when writing {@code pPlanned} over {@code pStored} would change nothing
	 */
	public static boolean sameValue(final Object pPlanned, final Object pStored)
	{
		final Object planned = StoredValueEquality.normalise(pPlanned);
		final Object stored = StoredValueEquality.normalise(pStored);
		if (planned == null || stored == null)
		{
			return planned == stored;
		}
		if (planned instanceof Number && stored instanceof Number)
		{
			return StoredValueEquality.sameNumber((Number) planned, (Number) stored);
		}
		if (planned instanceof Date && stored instanceof Date)
		{
			return ((Date) planned).getTime() == ((Date) stored).getTime();
		}
		if (planned instanceof List && stored instanceof List)
		{
			final List<?> plannedList = (List<?>) planned;
			final List<?> storedList = (List<?>) stored;
			if (plannedList.size() != storedList.size())
			{
				return false;
			}
			for (int i = 0; i < plannedList.size(); i++)
			{
				if (!StoredValueEquality.sameValue(plannedList.get(i), storedList.get(i)))
				{
					return false;
				}
			}
			return true;
		}
		return planned.equals(stored);
	}

	/** EBX stores an empty string as null, and an unset multi-valued field reads as null or as an empty list: all of those are "no value". */
	private static Object normalise(final Object pValue)
	{
		if (pValue instanceof String && ((String) pValue).isEmpty())
		{
			return null;
		}
		if (pValue instanceof List && ((List<?>) pValue).isEmpty())
		{
			return null;
		}
		return pValue;
	}

	private static boolean sameNumber(final Number pPlanned, final Number pStored)
	{
		final BigDecimal planned = StoredValueEquality.toDecimal(pPlanned);
		final BigDecimal stored = StoredValueEquality.toDecimal(pStored);
		if (planned == null || stored == null)
		{
			return pPlanned.equals(pStored); // NaN / infinity: only the identical boxed value is "the same"
		}
		return planned.compareTo(stored) == 0;
	}

	private static BigDecimal toDecimal(final Number pValue)
	{
		if (pValue instanceof BigDecimal)
		{
			return (BigDecimal) pValue;
		}
		try
		{
			return new BigDecimal(pValue.toString());
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}
}
