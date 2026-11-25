package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * UX lot U2 (2026-10-13): how the application card of step 2 lists the tables of a source. Measured live the same day: 17 Salesforce
 * tables arrived ALL ticked, in alphabetical order, 11 of them empty, and every untick was a full page round trip. The order is now
 * "most rows first, unknown last" (a person keeps the tables that hold data), and the pure rules here decide the order and the counts
 * the card shows. Default ticks are written by the plan itself ({@code LandingPlanner}: a table it plans for the wizard starts
 * unticked; one already ticked or loaded keeps its tick), so there is nothing to compute here for them. No EBX access: tested in
 * {@code SourceTableListTest}.
 */
public final class SourceTableList
{
	/** How many objects without a column a card names before "and N more". */
	public static final int REASONS_SHOWN = 6;

	private SourceTableList()
	{
	}

	/** What an application with no planned table is waiting for. */
	public enum NoTableState
	{
		/** The catalog holds at least one table with columns: "Find its tables" plans them. */
		READY_TO_PLAN,
		/** The catalog holds objects but none carries a column (declared by lineage events, never read): scan the application again. */
		NEEDS_SCAN,
		/** The catalog holds nothing for this application. */
		NOTHING_FOUND
	}

	/** The state of an application with no planned table, from what the catalog holds for its source ({@code WizardSnapshot.SourceInfo#unplannedAssets}). */
	public static NoTableState noTableState(final List<UnplannedAsset> pAssets)
	{
		if (pAssets.isEmpty())
		{
			return NoTableState.NOTHING_FOUND;
		}
		return SourceTableList.withColumns(pAssets) > 0 ? NoTableState.READY_TO_PLAN : NoTableState.NEEDS_SCAN;
	}

	/** How many of the objects carry a scanned column. */
	public static int withColumns(final List<UnplannedAsset> pAssets)
	{
		int count = 0;
		for (final UnplannedAsset asset : pAssets)
		{
			if (asset.hasColumns)
			{
				count++;
			}
		}
		return count;
	}

	/** The names of the objects that carry no scanned column, in the given order. */
	public static List<String> namesWithoutColumn(final List<UnplannedAsset> pAssets)
	{
		final List<String> out = new ArrayList<String>();
		for (final UnplannedAsset asset : pAssets)
		{
			if (!asset.hasColumns)
			{
				out.add(asset.name);
			}
		}
		return out;
	}

	/** A copy of {@code pTables} sorted by row count, largest first, a table whose count is unknown after the known ones (a real zero counts as known), then by name (case-insensitive) so the order is stable. */
	public static List<WizardSnapshot.TableInfo> order(final List<WizardSnapshot.TableInfo> pTables)
	{
		final List<WizardSnapshot.TableInfo> out = new ArrayList<WizardSnapshot.TableInfo>(pTables);
		Collections.sort(out, new Comparator<WizardSnapshot.TableInfo>()
		{
			@Override
			public int compare(final WizardSnapshot.TableInfo pLeft, final WizardSnapshot.TableInfo pRight)
			{
				final TableRowCount left = SourceTableList.rows(pLeft);
				final TableRowCount right = SourceTableList.rows(pRight);
				if (left.known != right.known)
				{
					return left.known ? -1 : 1;
				}
				if (left.known && left.value != right.value)
				{
					return left.value > right.value ? -1 : 1;
				}
				return pLeft.tableName.compareToIgnoreCase(pRight.tableName);
			}
		});
		return out;
	}

	/** The row count shown for one table ({@link TableRowCount}: the catalog's profile, else the last load, else unknown). */
	public static TableRowCount rows(final WizardSnapshot.TableInfo pTable)
	{
		return TableRowCount.of(pTable.sourceRowCountKnown, pTable.sourceRowCount, pTable.lastLoadRows);
	}

	/** How many of the tables are ticked. */
	public static int ticked(final List<WizardSnapshot.TableInfo> pTables)
	{
		int count = 0;
		for (final WizardSnapshot.TableInfo table : pTables)
		{
			if (table.selected)
			{
				count++;
			}
		}
		return count;
	}

	/** Whether the project holds tables and none of them is ticked: the only state in which step 2 says "select the tables to keep" (with no table at all, each card explains what to do instead). */
	public static boolean tablesButNoneTicked(final List<WizardSnapshot.SourceInfo> pSources)
	{
		boolean any = false;
		for (final WizardSnapshot.SourceInfo source : pSources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				any = true;
				if (table.selected)
				{
					return false;
				}
			}
		}
		return any;
	}

	/**
	 * The ticks posted by "Keep these tables" ({@code mdmfTableTicks}): {@code "+12,+13,-14"} - a table id with {@code +} to tick it,
	 * {@code -} to untick it. Only the tables whose state the person changed are posted, so a page left open while another user
	 * changed other tables never overwrites them. Unparsable entries are dropped. {@code result[0]} are the ids to tick, {@code result[1]} to untick.
	 */
	@SuppressWarnings("unchecked")
	public static List<String>[] parseTicks(final String pPosted)
	{
		final List<String> on = new ArrayList<String>();
		final List<String> off = new ArrayList<String>();
		if (pPosted != null)
		{
			for (final String raw : pPosted.split(","))
			{
				final String entry = raw.trim();
				if (entry.length() < 2 || !entry.substring(1).matches("[0-9]+"))
				{
					continue;
				}
				if (entry.charAt(0) == '+')
				{
					on.add(entry.substring(1));
				}
				else if (entry.charAt(0) == '-')
				{
					off.add(entry.substring(1));
				}
			}
		}
		return new List[] { on, off };
	}
}
