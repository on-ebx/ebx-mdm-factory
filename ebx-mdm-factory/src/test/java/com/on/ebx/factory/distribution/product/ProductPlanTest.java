package com.on.ebx.factory.distribution.product;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.on.ebx.factory.distribution.product.ProductPlan.Candidate;
import com.on.ebx.factory.distribution.product.ProductPlan.Column;
import com.on.ebx.factory.distribution.product.ProductPlan.Draft;
import com.on.ebx.factory.distribution.product.ProductPlan.PlannedTable;
import com.on.ebx.factory.distribution.product.ProductPlan.Result;
import com.on.ebx.factory.distribution.product.ProductPlan.TableChoice;

/** JUnit-free: run the main, a failure throws. */
public final class ProductPlanTest
{
	public static void main(final String[] pArgs)
	{
		ProductPlanTest.defaultDraftAllIncludedOwnTables();
		ProductPlanTest.nestedChildFoldedIntoParent();
		ProductPlanTest.missingCodeAndOwnerRefused();
		ProductPlanTest.invalidCodeRefused();
		ProductPlanTest.ebxOnlyNeedsNoSchema();
		ProductPlanTest.snowflakeWithoutSchemaRefused();
		ProductPlanTest.noTableSelectedRefused();
		ProductPlanTest.nestingANonChildRefused();
		ProductPlanTest.duplicatePublishedNameRefused();
		ProductPlanTest.crosswalkSharesHostMasterTableAndIsNotBlocked();
		ProductPlanTest.crosswalkRefusedWhenHostMasterExcluded();
		ProductPlanTest.addedCatalogAssetIncluded();
		ProductPlanTest.unknownAddedAssetRefused();
		ProductPlanTest.parentExcludedWhileChildNestedRefused();
		ProductPlanTest.onDemandRefreshHasNoWarningNoMaxLag();
		ProductPlanTest.scheduledRefreshWarnsAboutSlaSweepCadence();
		System.out.println("ProductPlanTest OK");
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static Candidate person()
	{
		return new Candidate(ProductPlan.SOURCE_MASTER, "Person", "Person", "Project master",
			Arrays.asList(new Column("GOLDEN_ID", "STRING", "Person.id"), new Column("LAST_NAME", "STRING", "Person.lastName")), 301L, false, null, null, true,
			"MPersonnes", "Master", "/root/Person");
	}

	private static Candidate address()
	{
		return new Candidate(ProductPlan.SOURCE_MASTER, "Address", "Address", "Project master",
			Arrays.asList(new Column("GOLDEN_ID", "STRING", "Address.id"), new Column("CITY", "STRING", "Address.city")), 412L, true, "Person", "personRef", true,
			"MPersonnes", "Master", "/root/Address");
	}

	/** A second root mapping on the SAME master table as {@link #person} (dataspace/dataset/tablePath identical) - never the shared Xref table. {@code parentKey} names the host master entity. */
	private static Candidate crosswalk()
	{
		return new Candidate(ProductPlan.SOURCE_CROSSWALK, "Person__xref", "Cross-references", "Project matching",
			Arrays.asList(new Column("GOLDEN_ID", "STRING", "Person.id"), new Column("SOURCE_SYSTEM", "STRING", "Person.sourceSystem"),
				new Column("SOURCE_ID", "STRING", "Person.sourceId"), new Column("MDM_STATE", "STRING", "Person.id")),
			687L, false, "Person", null, true, "MPersonnes", "Master", "/root/Person");
	}

	private static Candidate country()
	{
		return new Candidate(ProductPlan.SOURCE_CATALOG, "42", "Country", "Catalogue - Reference",
			Arrays.asList(new Column("CODE", "STRING", "Country.code")), 249L, false, null, null, true, "BReference", "Referentials", "/root/Country");
	}

	private static Draft baseDraft()
	{
		final Draft draft = new Draft();
		draft.productName = "Person 360";
		draft.code = "PERSON_360";
		draft.owner = "jdoe";
		draft.domain = "Customer";
		draft.target = ProductPlan.TARGET_SNOWFLAKE;
		draft.connectorId = "SnowflakeDemo";
		draft.schema = "EBX_DATA_PRODUCTS.PERSONNES";
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Person", true, ProductPlan.NEST_OWN),
			new TableChoice("Address", true, ProductPlan.NEST_OWN), new TableChoice("Person__xref", true, ProductPlan.NEST_OWN)));
		return draft;
	}

	// ----------------------------------------------------------------------------------------------------- tests

	private static void defaultDraftAllIncludedOwnTables()
	{
		final Result result = ProductPlan.plan(ProductPlanTest.baseDraft(), Arrays.asList(ProductPlanTest.person(), ProductPlanTest.address(), ProductPlanTest.crosswalk()));
		ProductPlanTest.check("no problems", result.ok());
		ProductPlanTest.check("3 tables planned", result.tables.size() == 3);
		ProductPlanTest.check("Address is its own table, not nested", !ProductPlanTest.tableOf(result, "Address").nested);
		ProductPlanTest.check("published names PERSON/ADDRESS/CROSS-REFERENCES",
			"PERSON".equals(ProductPlanTest.tableOf(result, "Person").publishedName) && "ADDRESS".equals(ProductPlanTest.tableOf(result, "Address").publishedName));
		ProductPlanTest.check("contract column count is the sum of all columns", result.contractColumnCount == 2 + 2 + 4);
		ProductPlanTest.check("creation steps computed", !result.creationSteps.isEmpty());
	}

	private static void nestedChildFoldedIntoParent()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Person", true, ProductPlan.NEST_OWN),
			new TableChoice("Address", true, ProductPlan.NEST_NESTED)));
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.address()));
		ProductPlanTest.check("no problems", result.ok());
		final PlannedTable addressTable = ProductPlanTest.tableOf(result, "Address");
		ProductPlanTest.check("Address is nested", addressTable.nested);
		ProductPlanTest.check("Address's parent published name is PERSON", "PERSON".equals(addressTable.parentPublishedName));
		ProductPlanTest.check("a nested child's columns are not counted a second time", result.contractColumnCount == 2);
	}

	private static void missingCodeAndOwnerRefused()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.code = "";
		draft.owner = "";
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person()));
		ProductPlanTest.check("code required", ProductPlanTest.hasProblem(result, "code"));
		ProductPlanTest.check("owner required", ProductPlanTest.hasProblem(result, "owner"));
	}

	private static void invalidCodeRefused()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.code = "person_360"; // lower case: CODE_PATTERN requires upper case
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person()));
		ProductPlanTest.check("invalid code refused", ProductPlanTest.hasProblem(result, "code"));
	}

	private static void ebxOnlyNeedsNoSchema()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.target = ProductPlan.TARGET_EBX_ONLY;
		draft.schema = "";
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.address(), ProductPlanTest.crosswalk()));
		ProductPlanTest.check("no problems for EBX_ONLY without a schema", result.ok());
	}

	private static void snowflakeWithoutSchemaRefused()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.schema = "  ";
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.address(), ProductPlanTest.crosswalk()));
		ProductPlanTest.check("schema required for SNOWFLAKE", ProductPlanTest.hasProblem(result, "schema"));
	}

	private static void noTableSelectedRefused()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>();
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person()));
		ProductPlanTest.check("at least one table required", ProductPlanTest.hasProblem(result, "tables"));
	}

	private static void nestingANonChildRefused()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Person", true, ProductPlan.NEST_NESTED)));
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person()));
		ProductPlanTest.check("nesting a non-child table is refused", ProductPlanTest.hasProblem(result, "tables"));
	}

	private static void duplicatePublishedNameRefused()
	{
		final Candidate duplicate = new Candidate(ProductPlan.SOURCE_MASTER, "PersonBis", "Person", "Project master",
			Arrays.asList(new Column("GOLDEN_ID", "STRING", "PersonBis.id")), 10L, false, null, null, true, "MPersonnes", "Master", "/root/PersonBis");
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Person", true, ProductPlan.NEST_OWN),
			new TableChoice("PersonBis", true, ProductPlan.NEST_OWN)));
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), duplicate));
		ProductPlanTest.check("a table published twice under the same name is refused", ProductPlanTest.hasProblem(result, "tables"));
	}

	private static void crosswalkSharesHostMasterTableAndIsNotBlocked()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Person", true, ProductPlan.NEST_OWN),
			new TableChoice("Person__xref", true, ProductPlan.NEST_OWN)));
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.crosswalk()));
		ProductPlanTest.check("no problems", result.ok());
		final PlannedTable xref = ProductPlanTest.tableOf(result, "Person__xref");
		ProductPlanTest.check("crosswalk is its own published table (a second mapping, not folded)", !xref.nested);
		ProductPlanTest.check("crosswalk is a real EBX table (buildable, never blocked)", xref.ebxTable);
		ProductPlanTest.check("crosswalk published name is CROSS_REFERENCES", "CROSS_REFERENCES".equals(xref.publishedName));
		ProductPlanTest.check("crosswalk carries all four columns (GOLDEN_ID/SOURCE_SYSTEM/SOURCE_ID/MDM_STATE)", xref.columns.size() == 4);
	}

	private static void crosswalkRefusedWhenHostMasterExcluded()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Person__xref", true, ProductPlan.NEST_OWN)));
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.crosswalk()));
		ProductPlanTest.check("a crosswalk table needs its host master table included too", ProductPlanTest.hasProblem(result, "tables"));
	}

	private static void addedCatalogAssetIncluded()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Person", true, ProductPlan.NEST_OWN)));
		draft.addedCatalogAssetIds = Arrays.asList("42");
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.country()));
		ProductPlanTest.check("no problems", result.ok());
		ProductPlanTest.check("Country is planned", ProductPlanTest.tableOf(result, "42") != null);
	}

	private static void unknownAddedAssetRefused()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Person", true, ProductPlan.NEST_OWN)));
		draft.addedCatalogAssetIds = Arrays.asList("999");
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person()));
		ProductPlanTest.check("unknown asset refused", ProductPlanTest.hasProblem(result, "addedCatalogAssetIds"));
	}

	private static void parentExcludedWhileChildNestedRefused()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.tables = new ArrayList<TableChoice>(Arrays.asList(new TableChoice("Address", true, ProductPlan.NEST_NESTED)));
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.address()));
		ProductPlanTest.check("nesting a child whose parent is excluded is refused", ProductPlanTest.hasProblem(result, "tables"));
	}

	private static void onDemandRefreshHasNoWarningNoMaxLag()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.refreshMode = ProductPlan.REFRESH_ON_DEMAND;
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.address(), ProductPlanTest.crosswalk()));
		ProductPlanTest.check("no problems", result.ok());
		ProductPlanTest.check("no maxLagMinutes for on demand", ProductPlan.maxLagMinutes(draft.refreshMode) == null);
		ProductPlanTest.check("no SLA-sweep warning for on demand", result.warnings.isEmpty());
	}

	private static void scheduledRefreshWarnsAboutSlaSweepCadence()
	{
		final Draft draft = ProductPlanTest.baseDraft();
		draft.refreshMode = ProductPlan.REFRESH_DAILY;
		final Result result = ProductPlan.plan(draft, Arrays.asList(ProductPlanTest.person(), ProductPlanTest.address(), ProductPlanTest.crosswalk()));
		ProductPlanTest.check("no problems", result.ok());
		ProductPlanTest.check("daily is 1440 minutes", ProductPlan.maxLagMinutes(ProductPlan.REFRESH_DAILY).intValue() == 1440);
		ProductPlanTest.check("hourly is 60 minutes", ProductPlan.maxLagMinutes(ProductPlan.REFRESH_HOURLY).intValue() == 60);
		ProductPlanTest.check("weekly is 10080 minutes", ProductPlan.maxLagMinutes(ProductPlan.REFRESH_WEEKLY).intValue() == 10080);
		ProductPlanTest.check("a warning names the SLA sweep as the real cadence driver", !result.warnings.isEmpty());
	}

	// ---------------------------------------------------------------------------------------------------- helpers

	private static PlannedTable tableOf(final Result pResult, final String pKey)
	{
		for (final PlannedTable table : pResult.tables)
		{
			if (pKey.equals(table.key))
			{
				return table;
			}
		}
		return null;
	}

	private static boolean hasProblem(final Result pResult, final String pField)
	{
		for (final ProductPlan.Problem problem : pResult.problems)
		{
			if (pField.equals(problem.field))
			{
				return true;
			}
		}
		return false;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
