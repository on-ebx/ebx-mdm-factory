package com.on.ebx.factory.landing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Landing test fixes (2026-10-17, defect 3): a rescan used to leave a table that was planned but not yet built exactly as it was
 * ("Nothing to do: the 1 table found was already planned"), so a file planned from a bad first scan (1 column) stayed at 1 column
 * whatever the catalog now held (13). What the catalog holds now against what the table has: which columns appear, which are gone, and
 * whether the table's key survives. A renamed column cannot be told from a gone one and a new one: it is reported as both. Pure: no EBX class.
 */
public final class ColumnRefresh
{
	private ColumnRefresh()
	{
	}

	/** One {@code LandingColumn} row as far as the refresh is concerned. */
	public static final class Existing
	{
		public final String sourceColumn;
		public final String fieldName;
		public final boolean sourceKey;

		public Existing(final String pSourceColumn, final String pFieldName, final boolean pSourceKey)
		{
			this.sourceColumn = pSourceColumn;
			this.fieldName = pFieldName;
			this.sourceKey = pSourceKey;
		}
	}

	public static final class Diff
	{
		/** Scanned columns the table does not have, in scan order. */
		public final List<String> added = new ArrayList<String>();
		/** Columns of the table the catalog no longer has. */
		public final List<String> removed = new ArrayList<String>();
		/** The table's key columns (source column names) before the refresh. */
		public final List<String> keyBefore = new ArrayList<String>();
		/** True when the table had a key and at least one of its columns is gone: the key is dropped whole (a composite key minus a part identifies nothing). */
		public boolean keyDropped;
		public int columnsBefore;
		public int columnsNow;

		/** Whether the table's columns differ from what was scanned. */
		public boolean changed()
		{
			return !this.added.isEmpty() || !this.removed.isEmpty();
		}
	}

	public static Diff diff(final List<Existing> pExisting, final List<String> pScanned)
	{
		final Diff diff = new Diff();
		final Set<String> scanned = new HashSet<String>(pScanned);
		final Set<String> had = new HashSet<String>();
		for (final Existing column : pExisting)
		{
			had.add(column.sourceColumn);
			if (!scanned.contains(column.sourceColumn))
			{
				diff.removed.add(column.sourceColumn);
			}
			if (column.sourceKey)
			{
				diff.keyBefore.add(column.sourceColumn);
			}
		}
		for (final String name : pScanned)
		{
			if (!had.contains(name))
			{
				diff.added.add(name);
			}
		}
		for (final String key : diff.keyBefore)
		{
			diff.keyDropped |= diff.removed.contains(key);
		}
		diff.columnsBefore = pExisting.size();
		diff.columnsNow = pScanned.size();
		return diff;
	}

	/** Field names for {@code pAddedColumns}, each distinct (case-insensitive, like EBX siblings) from {@code pTakenFieldNames} and from each other. */
	public static List<String> newFieldNames(final List<String> pTakenFieldNames, final List<String> pAddedColumns)
	{
		final Set<String> taken = new HashSet<String>();
		for (final String name : pTakenFieldNames)
		{
			taken.add(name.toLowerCase(Locale.ROOT));
		}
		final List<String> out = new ArrayList<String>();
		for (final String column : pAddedColumns)
		{
			String name = LandingModel.fieldName(column);
			String candidate = name;
			for (int i = 2; !taken.add(candidate.toLowerCase(Locale.ROOT)); i++)
			{
				candidate = name + "_" + i;
			}
			out.add(candidate);
		}
		return out;
	}

	/** {@code Fidelite_Membres: 13 columns now (was 1)}, then what happened to the key, as the job's result text. */
	public static String summary(final String pTableName, final Diff pDiff)
	{
		final StringBuilder text = new StringBuilder(pTableName).append(": ").append(pDiff.columnsNow).append(pDiff.columnsNow == 1 ? " column" : " columns").append(" now (was ")
			.append(pDiff.columnsBefore).append(")");
		if (pDiff.keyDropped)
		{
			text.append("; the key ").append(String.join(" + ", pDiff.keyBefore)).append(" was dropped (a column of it is gone)");
		}
		return text.append(".").toString();
	}
}
