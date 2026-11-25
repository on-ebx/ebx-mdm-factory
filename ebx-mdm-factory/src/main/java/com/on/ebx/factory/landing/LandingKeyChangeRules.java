package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pure validation of {@code ebx.LandingKeyChange#change} - "Change the key..." on a table whose landing
 * is already GENERATED/LOADED (composite-key lot, 2026-09-24, item 4 of the brief). Deliberately narrow:
 * only checks that the requested column list is non-empty and names real columns of the table; it has no
 * opinion on whether the table's status allows a change at all (that guard, and the EBX write itself, stay
 * in {@code ebx.LandingKeyChange} - this class never sees an {@code Adaptation}). Pure: no EBX class.
 */
public final class LandingKeyChangeRules
{
	private LandingKeyChangeRules()
	{
	}

	/** {@code ok} true with a normalised, de-duplicated {@link #columns}; false with a {@link #message} naming exactly what was wrong, and no write to make. */
	public static final class Validation
	{
		public final boolean ok;
		public final String message;
		public final List<String> columns;

		private Validation(final boolean pOk, final String pMessage, final List<String> pColumns)
		{
			this.ok = pOk;
			this.message = pMessage;
			this.columns = pColumns;
		}

		private static Validation refuse(final String pMessage)
		{
			return new Validation(false, pMessage, new ArrayList<String>());
		}

		private static Validation ok(final List<String> pColumns)
		{
			return new Validation(true, null, pColumns);
		}
	}

	/**
	 * @param pTableColumns every real source column of the table ({@code LandingColumn.sourceColumn}), in any order
	 * @param pRequestedColumns the columns a person picked for the new key; blank entries are dropped, duplicates
	 *            collapsed to their first occurrence, everything else trimmed as-is (case-sensitive: a source
	 *            column name is compared exactly, the same convention {@code WizardData.setTableKey} already uses)
	 */
	public static Validation validate(final List<String> pTableColumns, final List<String> pRequestedColumns)
	{
		final List<String> requested = new ArrayList<String>();
		final Set<String> seen = new HashSet<String>();
		if (pRequestedColumns != null)
		{
			for (final String column : pRequestedColumns)
			{
				final String trimmed = column == null ? "" : column.trim();
				if (!trimmed.isEmpty() && seen.add(trimmed))
				{
					requested.add(trimmed);
				}
			}
		}
		if (requested.isEmpty())
		{
			return Validation.refuse("A key needs at least one column.");
		}
		final Set<String> known = new HashSet<String>(pTableColumns == null ? new ArrayList<String>() : pTableColumns);
		for (final String column : requested)
		{
			if (!known.contains(column))
			{
				return Validation.refuse("'" + column + "' is not a column of this table.");
			}
		}
		return Validation.ok(requested);
	}
}
