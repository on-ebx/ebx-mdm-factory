package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free: run the main, a failure throws. Exercises {@link SourceTableList} (UX lot U2, 2026-10-13). */
public final class SourceTableListTest
{
	public static void main(final String[] pArgs)
	{
		SourceTableListTest.salesforceOrderIsRowsDescendingUnknownLast();
		SourceTableListTest.lastLoadRowsCountWhenTheCatalogDidNotProfile();
		SourceTableListTest.bannerOnlyWhenTablesExistAndNoneIsTicked();
		SourceTableListTest.parseTicks();
		SourceTableListTest.noTableStates();
		System.out.println("SourceTableListTest OK");
	}

	private static WizardSnapshot.TableInfo table(final String pName, final boolean pKnown, final long pRows, final boolean pSelected)
	{
		final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
		table.id = pName;
		table.tableName = pName;
		table.sourceRowCountKnown = pKnown;
		table.sourceRowCount = pRows;
		table.selected = pSelected;
		return table;
	}

	/** The 17 Salesforce tables of the live report, shortened: Account 52 rows, Contact 51, Lead 41, User 6, Pricebook2 1, the rest 0. */
	private static void salesforceOrderIsRowsDescendingUnknownLast()
	{
		final List<WizardSnapshot.TableInfo> tables = new ArrayList<WizardSnapshot.TableInfo>(Arrays.asList(SourceTableListTest.table("Asset", true, 0, false),
			SourceTableListTest.table("Contact", true, 51, false), SourceTableListTest.table("Unprofiled", false, 0, false), SourceTableListTest.table("Account", true, 52, false),
			SourceTableListTest.table("User", true, 6, false), SourceTableListTest.table("Case", true, 0, false), SourceTableListTest.table("Lead", true, 41, false),
			SourceTableListTest.table("Pricebook2", true, 1, false)));
		final List<WizardSnapshot.TableInfo> ordered = SourceTableList.order(tables);
		final StringBuilder names = new StringBuilder();
		for (final WizardSnapshot.TableInfo table : ordered)
		{
			names.append(table.tableName).append(' ');
		}
		SourceTableListTest.check("most rows first, real zeros by name, unknown last: " + names, names.toString().equals("Account Contact Lead User Pricebook2 Asset Case Unprofiled "));
		SourceTableListTest.check("the input is not reordered", "Asset".equals(tables.get(0).tableName));
		SourceTableListTest.check("none ticked", SourceTableList.ticked(ordered) == 0);
	}

	private static void lastLoadRowsCountWhenTheCatalogDidNotProfile()
	{
		final WizardSnapshot.TableInfo loaded = SourceTableListTest.table("Loaded", false, 0, true);
		loaded.lastLoadRows = 180;
		final List<WizardSnapshot.TableInfo> ordered = SourceTableList.order(Arrays.asList(SourceTableListTest.table("Small", true, 5, true), loaded));
		SourceTableListTest.check("a loaded table counts its last load", "Loaded".equals(ordered.get(0).tableName));
		SourceTableListTest.check("ticked count", SourceTableList.ticked(ordered) == 2);
	}

	private static void bannerOnlyWhenTablesExistAndNoneIsTicked()
	{
		final WizardSnapshot.SourceInfo empty = new WizardSnapshot.SourceInfo();
		final WizardSnapshot.SourceInfo none = new WizardSnapshot.SourceInfo();
		none.tables.add(SourceTableListTest.table("A", true, 1, false));
		final WizardSnapshot.SourceInfo some = new WizardSnapshot.SourceInfo();
		some.tables.add(SourceTableListTest.table("B", true, 1, true));
		SourceTableListTest.check("no table at all: no banner (nothing to tick)", !SourceTableList.tablesButNoneTicked(Arrays.asList(empty)));
		SourceTableListTest.check("tables, none ticked: banner", SourceTableList.tablesButNoneTicked(Arrays.asList(empty, none)));
		SourceTableListTest.check("one ticked anywhere: no banner", !SourceTableList.tablesButNoneTicked(Arrays.asList(none, some)));
		SourceTableListTest.check("no application: no banner", !SourceTableList.tablesButNoneTicked(new ArrayList<WizardSnapshot.SourceInfo>()));
	}

	private static void parseTicks()
	{
		final List<String>[] ticks = SourceTableList.parseTicks("+12, +13,-14,x15,+,-abc,+16");
		SourceTableListTest.check("ticked ids " + ticks[0], ticks[0].equals(Arrays.asList("12", "13", "16")));
		SourceTableListTest.check("unticked ids " + ticks[1], ticks[1].equals(Arrays.asList("14")));
		final List<String>[] none = SourceTableList.parseTicks(null);
		SourceTableListTest.check("null and empty parse to nothing", none[0].isEmpty() && none[1].isEmpty() && SourceTableList.parseTicks("")[0].isEmpty());
	}

	private static void noTableStates()
	{
		SourceTableListTest.check("no object", SourceTableList.noTableState(new ArrayList<UnplannedAsset>()) == SourceTableList.NoTableState.NOTHING_FOUND);
		SourceTableListTest.check("Salesforce as measured: one object, no column", SourceTableList.noTableState(Arrays.asList(new UnplannedAsset("Account", false))) == SourceTableList.NoTableState.NEEDS_SCAN);
		final List<UnplannedAsset> mixed = Arrays.asList(new UnplannedAsset("Account", true), new UnplannedAsset("Stub", false));
		SourceTableListTest.check("one with columns is enough to plan", SourceTableList.noTableState(mixed) == SourceTableList.NoTableState.READY_TO_PLAN);
		SourceTableListTest.check("counts", SourceTableList.withColumns(mixed) == 1 && SourceTableList.namesWithoutColumn(mixed).equals(Arrays.asList("Stub")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
