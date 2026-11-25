package com.on.ebx.factory.landing;

import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class LandingModelTest
{
	public static void main(final String[] pArgs)
	{
		LandingModelTest.check("fieldName keeps a plain name", "SUPPLIER_ID".equals(LandingModel.fieldName("SUPPLIER_ID")));
		LandingModelTest.check("fieldName replaces what XML refuses", "Name__legal_".equals(LandingModel.fieldName("Name (legal)")));
		LandingModelTest.check("fieldName never starts with a digit", "c_2ND_LINE".equals(LandingModel.fieldName("2ND_LINE")));
		LandingModelTest.check("fieldName leaves the underscore prefix to the factory", "c_sourceId".equals(LandingModel.fieldName("_sourceId")));
		LandingModelTest.check("fieldName of nothing", "column".equals(LandingModel.fieldName(" ")));

		final LandingModel.Builder builder = new LandingModel.Builder();
		final LandingModel.Table supplier = builder.table("SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER", Arrays.asList("SUPPLIER_ID", "Name", "NAME", "_sourceId"));
		final LandingModel.Table other = builder.table("SNOWFLAKE_LEARNING_DB.CRM_SOURCE.SUPPLIER", Arrays.asList("ID"));
		LandingModelTest.check("table named after the last part", "SUPPLIER".equals(supplier.tableName));
		LandingModelTest.check("a second table ending alike takes its schema: " + other.tableName, "CRM_SOURCE_SUPPLIER".equals(other.tableName));
		LandingModelTest.check("columns differing by case only stay apart: " + supplier.columns.get(2).fieldName, "NAME_2".equals(supplier.columns.get(2).fieldName));

		final JsonNode tree = builder.tree();
		final JsonNode table = tree.path("tables").get(0);
		LandingModelTest.check("path", "/root/SUPPLIER".equals(table.path("path").asText()));
		LandingModelTest.check("key is _sourceId", "_sourceId".equals(table.path("primaryKey").get(0).asText()));
		final JsonNode fields = table.path("fields");
		// _sourceId + 4 source columns + 5 technical fields
		LandingModelTest.check("10 fields: " + fields.size(), fields.size() == 10);
		LandingModelTest.check("the key comes first", "_sourceId".equals(fields.get(0).path("field").path("name").asText()));
		LandingModelTest.check("the source column named like the key moved aside", "c_sourceId".equals(fields.get(4).path("field").path("name").asText()));
		LandingModelTest.check("the label is the source column", "Name".equals(fields.get(2).path("field").path("label").asText()));
		LandingModelTest.check("received is a dateTime", "dateTime".equals(fields.get(7).path("field").path("type").asText()));
		for (final JsonNode field : fields)
		{
			LandingModelTest.check("no constraint on a landing field", !field.has("constraints"));
			LandingModelTest.check("field path under its table", field.path("field").path("path").asText().startsWith("/root/SUPPLIER/"));
		}
		// the generator's path: names already decided by a stored plan, not re-derived by the Builder's
		// naming/uniqueness rules. Given the same names the Builder produced, the tree must be identical.
		final LandingModel.Table named = LandingModel.namedTable("SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER", "SUPPLIER");
		named.columns.add(LandingModel.namedColumn("SUPPLIER_ID", "SUPPLIER_ID"));
		named.columns.add(LandingModel.namedColumn("Name", "Name"));
		named.columns.add(LandingModel.namedColumn("NAME", "NAME_2"));
		named.columns.add(LandingModel.namedColumn("_sourceId", "c_sourceId"));
		final JsonNode namedTree = LandingModel.tree(Arrays.asList(named));
		LandingModelTest.check("the generator reproduces the planner's tree for the same names", namedTree.path("tables").get(0).equals(table));
		LandingModelTest.check("tablePath matches what tree() actually puts in the model", "/root/SUPPLIER".equals(LandingModel.tablePath("SUPPLIER")));

		// a landing never carries a foreign key, even between tables RelationRule would propose a relation for
		// (LandingRelation keeps that as data, on the side; tree() never even sees it) - built from the real
		// example of PLAN.md: CONTACT.ACCOUNT_ID and ACCOUNT.PARENT_ACCOUNT_ID both look exactly like references.
		final LandingModel.Builder related = new LandingModel.Builder();
		related.table("CRM_SOURCE.ACCOUNT", Arrays.asList("ACCOUNT_ID", "PARENT_ACCOUNT_ID", "NAME"));
		related.table("CRM_SOURCE.CONTACT", Arrays.asList("CONTACT_ID", "ACCOUNT_ID", "EMAIL"));
		final JsonNode relatedTree = related.tree();
		LandingModelTest.check("two tables built", relatedTree.path("tables").size() == 2);
		for (final JsonNode relatedTable : relatedTree.path("tables"))
		{
			for (final JsonNode relatedField : relatedTable.path("fields"))
			{
				LandingModelTest.check("no fk on a landing field, however much its name looks like one", !relatedField.path("field").has("fk"));
				LandingModelTest.check("no constraint of any kind on a landing field", !relatedField.has("constraints"));
			}
		}

		// a file connector's qualifiedName is a plain path like "sample.csv": baseNameOf strips a recognised
		// trailing extension so the table is named after the file, not the extension (two files sharing an
		// extension would otherwise collide onto the same generated name before unique() gets a chance to act).
		LandingModelTest.check("baseNameOf strips a recognised file extension", "sample".equals(LandingModel.baseNameOf("sample.csv")));
		LandingModelTest.check("baseNameOf matches the extension case-insensitively", "sample".equals(LandingModel.baseNameOf("sample.CSV")));
		LandingModelTest.check("baseNameOf leaves a genuine SQL qualified name alone: TABLE is not a recognised extension",
			"DB.SCHEMA.TABLE".equals(LandingModel.baseNameOf("DB.SCHEMA.TABLE")));
		LandingModelTest.check("baseNameOf leaves a name with no dot alone", "sample".equals(LandingModel.baseNameOf("sample")));

		final LandingModel.Builder fileBuilder = new LandingModel.Builder();
		final LandingModel.Table sampleFile = fileBuilder.table("sample.csv", Arrays.asList("ID", "NAME"));
		LandingModelTest.check("a file connector's table is named from the file, not its extension: " + sampleFile.tableName, "sample".equals(sampleFile.tableName));

		final LandingModel.Builder sqlBuilder = new LandingModel.Builder();
		final LandingModel.Table sqlTable = sqlBuilder.table("DB.SCHEMA.TABLE", Arrays.asList("ID"));
		LandingModelTest.check("a genuine DB.SCHEMA.TABLE qualified name still names from TABLE, unchanged: " + sqlTable.tableName, "TABLE".equals(sqlTable.tableName));

		System.out.println("LandingModelTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
