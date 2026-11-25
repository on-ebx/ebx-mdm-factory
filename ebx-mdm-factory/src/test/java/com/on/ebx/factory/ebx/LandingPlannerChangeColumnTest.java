package com.on.ebx.factory.ebx;

import java.math.BigDecimal;

import com.on.ebx.factory.landing.ChangeColumnRule;

/**
 * JUnit-free, like the rest of this package's tests. Volume wave 2, lot V2-E (2026-10-03): {@link LandingPlanner#changeColumn} - the
 * change column is picked from the catalog's column metadata only for a table that is read through generated SQL, never for a file or a
 * deduced-scan table, whatever columns they hold.
 */
public final class LandingPlannerChangeColumnTest
{
	public static void main(final String[] pArgs)
	{
		LandingPlannerChangeColumnTest.aDatabaseTableGetsItsMarkerColumn();
		LandingPlannerChangeColumnTest.theCatalogTypeNamesAreNormalisedLikeTheLandingColumns();
		LandingPlannerChangeColumnTest.aFileOrADeducedTableGetsNone();
		LandingPlannerChangeColumnTest.aProfiledNullRateKeepsTheTableFull();
		System.out.println("LandingPlannerChangeColumnTest OK");
	}

	private static CatalogReader.Column column(final String pName, final String pDataType, final BigDecimal pNullRate)
	{
		final CatalogReader.Column column = new CatalogReader.Column();
		column.name = pName;
		column.dataType = pDataType;
		column.nullRate = pNullRate;
		return column;
	}

	private static CatalogReader.Asset asset(final String pKind, final String pScanOrigin, final CatalogReader.Column... pColumns)
	{
		final CatalogReader.Asset asset = new CatalogReader.Asset();
		asset.qualifiedName = "DB.PUBLIC.CONTACT";
		asset.kind = pKind;
		asset.scanOrigin = pScanOrigin;
		for (final CatalogReader.Column column : pColumns)
		{
			asset.columns.add(column);
		}
		return asset;
	}

	private static void aDatabaseTableGetsItsMarkerColumn()
	{
		final ChangeColumnRule.Choice choice = LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("TABLE", "OFFICIAL",
			LandingPlannerChangeColumnTest.column("ID", "INTEGER", null), LandingPlannerChangeColumnTest.column("LAST_MODIFIED", "TIMESTAMP", null)));
		LandingPlannerChangeColumnTest.check("LAST_MODIFIED is chosen", choice != null && "LAST_MODIFIED".equals(choice.column) && ChangeColumnRule.TIMESTAMP.equals(choice.kind));
		LandingPlannerChangeColumnTest.check("a view and a row with no declared kind or origin are database tables too",
			LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("VIEW", null, LandingPlannerChangeColumnTest.column("UPDATED_AT", "TIMESTAMP", null))) != null
				&& LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset(null, "", LandingPlannerChangeColumnTest.column("UPDATED_AT", "TIMESTAMP", null))) != null);
		LandingPlannerChangeColumnTest.check("a table with no marker column stays full",
			LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("TABLE", "", LandingPlannerChangeColumnTest.column("ID", "INTEGER", null),
				LandingPlannerChangeColumnTest.column("CREATED_AT", "TIMESTAMP", null))) == null);
	}

	/** The planner hands the rule the same normalised type it writes to LandingColumn.inferredType (a catalog DATE_TIME-like type it does not know reads as text, never as a date). */
	private static void theCatalogTypeNamesAreNormalisedLikeTheLandingColumns()
	{
		LandingPlannerChangeColumnTest.check("a DATE column qualifies",
			LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("TABLE", "", LandingPlannerChangeColumnTest.column("AEDAT", "DATE", null))) != null);
		LandingPlannerChangeColumnTest.check("an unknown catalog type is text: refused",
			LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("TABLE", "", LandingPlannerChangeColumnTest.column("LAST_MODIFIED", "VARIANT", null))) == null);
		LandingPlannerChangeColumnTest.check("an unknown (null) catalog type is text: refused",
			LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("TABLE", "", LandingPlannerChangeColumnTest.column("LAST_MODIFIED", null, null))) == null);
	}

	private static void aFileOrADeducedTableGetsNone()
	{
		final CatalogReader.Column marker = LandingPlannerChangeColumnTest.column("LAST_MODIFIED", "TIMESTAMP", null);
		LandingPlannerChangeColumnTest.check("a file (kind FILE) is read through a feed: none", LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("FILE", "", marker)) == null);
		LandingPlannerChangeColumnTest.check("a deduced-scan table is read through a recipe: none", LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("TABLE", "DEDUCED", marker)) == null);
	}

	private static void aProfiledNullRateKeepsTheTableFull()
	{
		LandingPlannerChangeColumnTest.check("a column profiled with NULLs is refused (>= never returns a NULL)",
			LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("TABLE", "", LandingPlannerChangeColumnTest.column("LAST_MODIFIED", "TIMESTAMP", new BigDecimal("0.01")))) == null);
		LandingPlannerChangeColumnTest.check("a column profiled with no NULL is accepted",
			LandingPlanner.changeColumn(LandingPlannerChangeColumnTest.asset("TABLE", "", LandingPlannerChangeColumnTest.column("LAST_MODIFIED", "TIMESTAMP", BigDecimal.ZERO))) != null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
