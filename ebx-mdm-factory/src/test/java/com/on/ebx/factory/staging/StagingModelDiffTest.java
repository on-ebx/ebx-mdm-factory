package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class StagingModelDiffTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		StagingModelDiffTest.identicalModelIsUpToDate();
		StagingModelDiffTest.newTableIsAdditive();
		StagingModelDiffTest.newOptionalFieldAndLongerMaxLengthAreAdditive();
		StagingModelDiffTest.existingTableWithoutSourceHashGetsItAdditively();
		StagingModelDiffTest.newDerivedFoldKeyFieldIsAdditive();
		StagingModelDiffTest.newForeignKeyFieldIsAdditive();
		StagingModelDiffTest.removedTableAndRemovedFieldAreNonAdditive();
		StagingModelDiffTest.typeChangeAndShorterMaxLengthAndNewMandatoryAreNonAdditive();
		StagingModelDiffTest.opOrderingIsTableThenKeyThenPrimaryKeyThenFieldThenForeignKeyThenFacet();
		StagingModelDiffTest.parseExistingReadsTheRealLiveShape();
		StagingModelDiffTest.parseExistingReadsAutoIncrementStartAndDefaultLabel();
		System.out.println("StagingModelDiffTest OK");
	}

	/** The common case once a project is stable: nothing changed since the last generation - {@code StagingGenerator} must answer UP_TO_DATE, not re-patch anything. */
	private static void identicalModelIsUpToDate()
	{
		final List<StagingModelDiff.ExistingTable> existing = Collections.singletonList(StagingModelDiffTest.supplierExistingTable());
		final StagingModel.Result desired = StagingModel.build(Collections.singletonList(StagingModelDiffTest.supplierEntity()));
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existing, desired);
		StagingModelDiffTest.check("no additive change", diff.additive.isEmpty());
		StagingModelDiffTest.check("no non-additive change", diff.nonAdditive.isEmpty());
		StagingModelDiffTest.check("no operation to apply", diff.operations.size() == 0);
		StagingModelDiffTest.check("isUpToDate() true", diff.isUpToDate());
	}

	/** {@code Company}, round 4's live case: an entity accepted after the model was first built - a brand-new table, entirely additive. */
	private static void newTableIsAdditive()
	{
		final List<StagingModelDiff.ExistingTable> existing = Collections.singletonList(StagingModelDiffTest.supplierExistingTable());
		final StagingModel.EntityInfo company = StagingModelDiffTest.entity("Company", Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40),
			StagingModelDiffTest.technical("sourceId", 255), StagingModelDiffTest.attribute("name", "STRING", 255, false)));
		final StagingModel.Result desired = StagingModel.build(Arrays.asList(StagingModelDiffTest.supplierEntity(), company));
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existing, desired);
		StagingModelDiffTest.check("not up to date", !diff.isUpToDate());
		StagingModelDiffTest.check("no non-additive change", diff.nonAdditive.isEmpty());
		StagingModelDiffTest.check("one additive change, naming Company as a new table", diff.additive.size() == 1 && diff.additive.get(0).entityName.equals("Company")
			&& diff.additive.get(0).description.contains("new table"));
		StagingModelDiffTest.check("create_table for /root/Company", StagingModelDiffTest.hasOp(diff.operations, "create_table", "table_path", "/root/Company"));
		StagingModelDiffTest.check("create_field for both technical-key fields",
			StagingModelDiffTest.hasOp(diff.operations, "create_field", "field_path", "/root/Company/sourceSystem")
				&& StagingModelDiffTest.hasOp(diff.operations, "create_field", "field_path", "/root/Company/sourceId"));
		StagingModelDiffTest.check("set_primary_key with both key fields, comma-separated",
			StagingModelDiffTest.hasOp(diff.operations, "set_primary_key", "fields", "sourceSystem,sourceId"));
		StagingModelDiffTest.check("create_field for the business field and the technical bookkeeping fields",
			StagingModelDiffTest.hasOp(diff.operations, "create_field", "field_path", "/root/Company/name")
				&& StagingModelDiffTest.hasOp(diff.operations, "create_field", "field_path", "/root/Company/_loadId"));
	}

	/** A new optional field on an EXISTING table, and a longer maxLength on an existing field: both safe, both additive. */
	private static void newOptionalFieldAndLongerMaxLengthAreAdditive()
	{
		final List<StagingModelDiff.ExistingTable> existing = Collections.singletonList(StagingModelDiffTest.supplierExistingTable());
		final StagingModel.EntityInfo supplierGrown = StagingModelDiffTest.entity("Supplier",
			Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40), StagingModelDiffTest.technical("sourceId", 255),
				StagingModelDiffTest.attribute("legalName", "STRING", 500, false), // was 255, now longer
				StagingModelDiffTest.attribute("website", "STRING", 255, false))); // brand new optional field
		final StagingModel.Result desired = StagingModel.build(Collections.singletonList(supplierGrown));
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existing, desired);
		StagingModelDiffTest.check("no non-additive change", diff.nonAdditive.isEmpty());
		StagingModelDiffTest.check("two additive changes: new field + longer maxLength", diff.additive.size() == 2);
		StagingModelDiffTest.check("create_field for website", StagingModelDiffTest.hasOp(diff.operations, "create_field", "field_path", "/root/Supplier/website"));
		StagingModelDiffTest.check("set_element_property maxLength=500 for legalName",
			StagingModelDiffTest.hasSetElementPropertyOp(diff.operations, "/root/Supplier/legalName", "maxLength", "500"));
	}

	/**
	 * The exact real-world case of the 2026-09-23 incremental-transfer lot: a project generated BEFORE
	 * {@code _sourceHash} existed re-runs {@code staging/generate} - {@code StagingModelDiff} must see the
	 * missing field and report it as one ordinary additive new field (a {@code create_field} op), exactly
	 * like {@code website} in {@link #newOptionalFieldAndLongerMaxLengthAreAdditive} above, never as anything
	 * needing a person's decision.
	 */
	private static void existingTableWithoutSourceHashGetsItAdditively()
	{
		final List<StagingModelDiff.ExistingField> fieldsBeforeSourceHash = new ArrayList<StagingModelDiff.ExistingField>(Arrays.asList(
			new StagingModelDiff.ExistingField("sourceSystem", "string", true, false, Integer.valueOf(40), false, null),
			new StagingModelDiff.ExistingField("sourceId", "string", true, false, Integer.valueOf(255), false, null),
			new StagingModelDiff.ExistingField("legalName", "string", false, false, Integer.valueOf(255), false, null)));
		fieldsBeforeSourceHash.addAll(StagingModelDiffTest.technicalBookkeepingFieldsBeforeSourceHash());
		final List<StagingModelDiff.ExistingTable> existing = Collections
			.singletonList(new StagingModelDiff.ExistingTable("Supplier", fieldsBeforeSourceHash));
		final StagingModel.Result desired = StagingModel.build(Collections.singletonList(StagingModelDiffTest.supplierEntity()));
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existing, desired);
		StagingModelDiffTest.check("no non-additive change", diff.nonAdditive.isEmpty());
		StagingModelDiffTest.check("one additive change: the missing _sourceHash field", diff.additive.size() == 1
			&& diff.additive.get(0).description.contains("_sourceHash") && diff.additive.get(0).description.contains("new field"));
		StagingModelDiffTest.check("create_field for _sourceHash", StagingModelDiffTest.hasOp(diff.operations, "create_field", "field_path", "/root/Supplier/_sourceHash"));
	}

	/**
	 * A {@code FOLD_KEY} derived attribute (2026-09-22 - {@code quality.CrossSystemFold}), once ACCEPTED on an
	 * entity whose staging model is already published, is just an ordinary new optional STRING field to {@link
	 * StagingModel}/{@link StagingModelDiff} - {@code StagingModel.AttributeInfo} carries no {@code
	 * derivedFrom}/{@code derivation} of its own (see {@code StagingModel}'s own Javadoc: it treats a derived
	 * attribute as perfectly ordinary) - so the diff engine needs no special case at all: one {@code
	 * create_field} op, additive, exactly like any other brand-new field.
	 */
	private static void newDerivedFoldKeyFieldIsAdditive()
	{
		final List<StagingModelDiff.ExistingTable> existing = Collections.singletonList(StagingModelDiffTest.supplierExistingTable());
		final StagingModel.EntityInfo supplierWithDerivedKey = StagingModelDiffTest.entity("Supplier",
			Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40), StagingModelDiffTest.technical("sourceId", 255),
				StagingModelDiffTest.attribute("legalName", "STRING", 255, false), StagingModelDiffTest.attribute("legalNameKey", "STRING", 255, false)));
		final StagingModel.Result desired = StagingModel.build(Collections.singletonList(supplierWithDerivedKey));
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existing, desired);
		StagingModelDiffTest.check("no non-additive change", diff.nonAdditive.isEmpty());
		StagingModelDiffTest.check("one additive change: the new derived key field", diff.additive.size() == 1
			&& diff.additive.get(0).description.contains("legalNameKey") && diff.additive.get(0).description.contains("new field"));
		StagingModelDiffTest.check("exactly one create_field op for legalNameKey",
			StagingModelDiffTest.hasOp(diff.operations, "create_field", "field_path", "/root/Supplier/legalNameKey"));
	}

	/** A new field that is also a foreign key to a table already built: additive (create_field then add_foreign_key, in that order). */
	private static void newForeignKeyFieldIsAdditive()
	{
		final List<StagingModelDiff.ExistingField> accountFields = new ArrayList<StagingModelDiff.ExistingField>(Arrays.asList(
			new StagingModelDiff.ExistingField("sourceSystem", "string", true, false, Integer.valueOf(40), false, null),
			new StagingModelDiff.ExistingField("sourceId", "string", true, false, Integer.valueOf(255), false, null),
			new StagingModelDiff.ExistingField("name", "string", false, false, Integer.valueOf(255), false, null)));
		accountFields.addAll(StagingModelDiffTest.technicalBookkeepingFields());
		// NOT supplierExistingTable() - that one carries a "legalName" field, but supplierWithRef below (the desired shape) does not:
		// a fixture must match the SAME entity's shape, or a genuinely unrelated field mismatch (legalName missing) would sneak in as noise
		final List<StagingModelDiff.ExistingField> supplierFieldsWithoutRef = new ArrayList<StagingModelDiff.ExistingField>(Arrays.asList(
			new StagingModelDiff.ExistingField("sourceSystem", "string", true, false, Integer.valueOf(40), false, null),
			new StagingModelDiff.ExistingField("sourceId", "string", true, false, Integer.valueOf(255), false, null)));
		supplierFieldsWithoutRef.addAll(StagingModelDiffTest.technicalBookkeepingFields());
		final List<StagingModelDiff.ExistingTable> existing = Arrays.asList(new StagingModelDiff.ExistingTable("Supplier", supplierFieldsWithoutRef),
			new StagingModelDiff.ExistingTable("Account", accountFields));
		// a business attribute is required, or StagingModel.build skips the entity entirely (no table at all) - see its own Javadoc
		final StagingModel.EntityInfo account = StagingModelDiffTest.entity("Account",
			Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40), StagingModelDiffTest.technical("sourceId", 255),
				StagingModelDiffTest.attribute("name", "STRING", 255, false)));
		final StagingModel.EntityInfo supplierWithRef = StagingModelDiffTest.entity("Supplier",
			Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40), StagingModelDiffTest.technical("sourceId", 255),
				new StagingModel.AttributeInfo("parentAccountId", "Parent account", "STRING", Integer.valueOf(40), false, false, "Account", "ACCEPTED")));
		final StagingModel.Result desired = StagingModel.build(Arrays.asList(account, supplierWithRef));
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existing, desired);
		StagingModelDiffTest.check("no non-additive change", diff.nonAdditive.isEmpty());
		StagingModelDiffTest.check("create_field then add_foreign_key for parentAccountIdRef",
			StagingModelDiffTest.hasOp(diff.operations, "create_field", "field_path", "/root/Supplier/parentAccountIdRef")
				&& StagingModelDiffTest.hasOp(diff.operations, "add_foreign_key", "field_path", "/root/Supplier/parentAccountIdRef"));
		StagingModelDiffTest.check("create_field for the FK field comes before add_foreign_key on it",
			StagingModelDiffTest.opIndex(diff.operations, "create_field", "field_path", "/root/Supplier/parentAccountIdRef") < StagingModelDiffTest
				.opIndex(diff.operations, "add_foreign_key", "field_path", "/root/Supplier/parentAccountIdRef"));
	}

	/** A table or a field the accepted proposals no longer describe: never dropped by this class - only reported, so a person decides. */
	private static void removedTableAndRemovedFieldAreNonAdditive()
	{
		final List<StagingModelDiff.ExistingField> supplierWithExtraFieldFields = new ArrayList<StagingModelDiff.ExistingField>(Arrays.asList(
			new StagingModelDiff.ExistingField("sourceSystem", "string", true, false, Integer.valueOf(40), false, null),
			new StagingModelDiff.ExistingField("sourceId", "string", true, false, Integer.valueOf(255), false, null),
			new StagingModelDiff.ExistingField("legalName", "string", false, false, Integer.valueOf(255), false, null),
			new StagingModelDiff.ExistingField("nowGone", "string", false, false, Integer.valueOf(255), false, null)));
		supplierWithExtraFieldFields.addAll(StagingModelDiffTest.technicalBookkeepingFields());
		final StagingModelDiff.ExistingTable supplierWithExtraField = new StagingModelDiff.ExistingTable("Supplier", supplierWithExtraFieldFields);
		final List<StagingModelDiff.ExistingTable> existing = Arrays.asList(supplierWithExtraField,
			new StagingModelDiff.ExistingTable("NowGoneTable", Collections.<StagingModelDiff.ExistingField>emptyList()));
		final StagingModel.Result desired = StagingModel.build(Collections.singletonList(StagingModelDiffTest.supplierEntity()));
		final StagingModelDiff.Result diff = StagingModelDiff.diff(existing, desired);
		StagingModelDiffTest.check("nothing additive", diff.additive.isEmpty());
		StagingModelDiffTest.check("no operation generated for a removal", diff.operations.size() == 0);
		StagingModelDiffTest.check("two non-additive changes: the removed table and the removed field", diff.nonAdditive.size() == 2);
		StagingModelDiffTest.check("blockedEntityNames names both", diff.blockedEntityNames().containsAll(Arrays.asList("Supplier", "NowGoneTable")));
	}

	/** A type change, a shrinking maxLength, and optional-becoming-mandatory all risk losing or refusing already-loaded data: all three refused. */
	private static void typeChangeAndShorterMaxLengthAndNewMandatoryAreNonAdditive()
	{
		final List<StagingModelDiff.ExistingField> existingSupplierFields = new ArrayList<StagingModelDiff.ExistingField>(Arrays.asList(
			new StagingModelDiff.ExistingField("sourceSystem", "string", true, false, Integer.valueOf(40), false, null),
			new StagingModelDiff.ExistingField("sourceId", "string", true, false, Integer.valueOf(255), false, null),
			new StagingModelDiff.ExistingField("legalName", "string", false, false, Integer.valueOf(255), false, null),
			new StagingModelDiff.ExistingField("annualRevenue", "decimal", false, false, null, false, null)));
		existingSupplierFields.addAll(StagingModelDiffTest.technicalBookkeepingFields());
		final StagingModelDiff.ExistingTable existingSupplier = new StagingModelDiff.ExistingTable("Supplier", existingSupplierFields);
		final StagingModel.EntityInfo changed = StagingModelDiffTest.entity("Supplier",
			Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40), StagingModelDiffTest.technical("sourceId", 255),
				StagingModelDiffTest.attribute("legalName", "STRING", 80, true), // shorter AND newly mandatory
				new StagingModel.AttributeInfo("annualRevenue", "Annual revenue", "STRING", Integer.valueOf(40), false, false, null, "ACCEPTED"))); // decimal -> string
		final StagingModel.Result desired = StagingModel.build(Collections.singletonList(changed));
		final StagingModelDiff.Result diff = StagingModelDiff.diff(Collections.singletonList(existingSupplier), desired);
		StagingModelDiffTest.check("nothing additive", diff.additive.isEmpty());
		StagingModelDiffTest.check("no operation generated", diff.operations.size() == 0);
		StagingModelDiffTest.check("three non-additive changes: type, shorter maxLength, newly mandatory", diff.nonAdditive.size() == 3);
		final List<String> descriptions = new ArrayList<String>();
		for (final StagingModelDiff.Change change : diff.nonAdditive)
		{
			descriptions.add(change.description);
		}
		StagingModelDiffTest.check("type change reported", StagingModelDiffTest.anyContains(descriptions, "type would change from decimal to string"));
		StagingModelDiffTest.check("shrinking maxLength reported", StagingModelDiffTest.anyContains(descriptions, "maxLength would shrink from 255 to 80"));
		StagingModelDiffTest.check("newly mandatory reported", StagingModelDiffTest.anyContains(descriptions, "would become mandatory"));
	}

	/** The exact op ordering {@code data_model_guide} asks for: table, then its key fields, then set_primary_key, then the other fields, then foreign keys, then facets. */
	private static void opOrderingIsTableThenKeyThenPrimaryKeyThenFieldThenForeignKeyThenFacet()
	{
		// a business attribute is required, or StagingModel.build skips the entity entirely (no table at all) - see its own Javadoc
		final StagingModel.EntityInfo account = StagingModelDiffTest.entity("Account",
			Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40), StagingModelDiffTest.technical("sourceId", 255),
				StagingModelDiffTest.attribute("name", "STRING", 255, false)));
		final StagingModel.EntityInfo newEntity = StagingModelDiffTest.entity("Company",
			Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40), StagingModelDiffTest.technical("sourceId", 255),
				new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), true, false, null, "ACCEPTED"),
				new StagingModel.AttributeInfo("accountId", "Account id", "STRING", Integer.valueOf(40), false, false, "Account", "ACCEPTED")));
		final StagingModel.Result desired = StagingModel.build(Arrays.asList(account, newEntity));
		// only the diff against Company matters here: Account is already "existing" in every other test, keep this one focused on op order for a from-scratch table
		final StagingModelDiff.Result diff = StagingModelDiff.diff(Collections.singletonList(StagingModelDiffTest.existingTableFor(account)), desired);

		final int createTable = StagingModelDiffTest.opIndex(diff.operations, "create_table", "table_path", "/root/Company");
		final int keyField = StagingModelDiffTest.opIndex(diff.operations, "create_field", "field_path", "/root/Company/sourceSystem");
		final int primaryKey = StagingModelDiffTest.opIndex(diff.operations, "set_primary_key", "table_path", "/root/Company");
		final int businessField = StagingModelDiffTest.opIndex(diff.operations, "create_field", "field_path", "/root/Company/name");
		final int fkField = StagingModelDiffTest.opIndex(diff.operations, "create_field", "field_path", "/root/Company/accountIdRef");
		final int addFk = StagingModelDiffTest.opIndex(diff.operations, "add_foreign_key", "field_path", "/root/Company/accountIdRef");
		final int facet = StagingModelDiffTest.opIndex(diff.operations, "set_element_property", "element_path", "/root/Company/name");
		StagingModelDiffTest.check("every op is present: " + diff.operations, createTable >= 0 && keyField >= 0 && primaryKey >= 0 && businessField >= 0
			&& fkField >= 0 && addFk >= 0 && facet >= 0);
		StagingModelDiffTest.check("create_table before its key fields", createTable < keyField);
		StagingModelDiffTest.check("key fields before set_primary_key", keyField < primaryKey);
		StagingModelDiffTest.check("set_primary_key before the other fields", primaryKey < businessField && primaryKey < fkField);
		StagingModelDiffTest.check("the FK field is created before add_foreign_key targets it", fkField < addFk);
		StagingModelDiffTest.check("add_foreign_key before facet ops", addFk < facet);
	}

	/** {@code get_data_model(deep=true)} on {@code Companies_Staging}, read live 2026-09-20: the exact field/constraints shape {@link StagingModelDiff#parseExisting} must read correctly. */
	private static void parseExistingReadsTheRealLiveShape()
	{
		final ObjectNode tableNode = StagingModelDiffTest.MAPPER.createObjectNode();
		tableNode.put("path", "/root/Contact");
		tableNode.put("name", "Contact");
		final ArrayNode fields = tableNode.putArray("fields");
		StagingModelDiffTest.rawField(fields, "sourceSystem", "string", true, null, "40", null, null);
		StagingModelDiffTest.rawField(fields, "sourceId", "string", true, true, "255", null, null);
		StagingModelDiffTest.rawField(fields, "accountIdRef", "string", null, null, "500", true, "/root/Account");
		StagingModelDiffTest.rawField(fields, "hasOptedOutOfEmail", "boolean", null, null, null, null, null);
		final ArrayNode tables = StagingModelDiffTest.MAPPER.createArrayNode();
		tables.add(tableNode);

		final List<StagingModelDiff.ExistingTable> parsed = StagingModelDiff.parseExisting(tables);
		StagingModelDiffTest.check("one table parsed", parsed.size() == 1);
		final StagingModelDiff.ExistingTable contact = parsed.get(0);
		StagingModelDiffTest.check("table name is the last path segment", "Contact".equals(contact.name));
		StagingModelDiffTest.check("four fields parsed", contact.fields.size() == 4);

		final StagingModelDiff.ExistingField sourceSystem = contact.field("sourceSystem");
		StagingModelDiffTest.check("sourceSystem is the primary key, maxLength 40, not mandatory (constraints.mandatory absent, like the real dump)",
			sourceSystem.primaryKey && sourceSystem.maxLength.intValue() == 40 && !sourceSystem.mandatory);
		final StagingModelDiff.ExistingField sourceId = contact.field("sourceId");
		StagingModelDiffTest.check("sourceId: isPrimaryKey true even though constraints.mandatory is also true in this fixture (real dump never sets it, kept generic)",
			sourceId.primaryKey && sourceId.mandatory);
		final StagingModelDiff.ExistingField accountIdRef = contact.field("accountIdRef");
		StagingModelDiffTest.check("accountIdRef: isForeignKey true, target /root/Account, maxLength 500, not a primary key",
			accountIdRef.isForeignKey && "/root/Account".equals(accountIdRef.foreignKeyTargetPath) && accountIdRef.maxLength.intValue() == 500
				&& !accountIdRef.primaryKey);
		final StagingModelDiff.ExistingField optedOut = contact.field("hasOptedOutOfEmail");
		StagingModelDiffTest.check("hasOptedOutOfEmail: boolean, no maxLength (absent, like every non-string field in the real dump)",
			"boolean".equals(optedOut.type) && optedOut.maxLength == null && !optedOut.isForeignKey);
	}

	/**
	 * Added 2026-09-22 for {@code master.MasterFacetRepair} (`docs/MASTER-ZONE.md` &sect;10): a table's own
	 * {@code tableProperties.defaultLabel} and a field's own {@code constraints.autoIncrementStart} - one
	 * present, one absent, on the SAME table, exactly the shape a patch aborted mid-way leaves behind.
	 */
	private static void parseExistingReadsAutoIncrementStartAndDefaultLabel()
	{
		final ObjectNode tableWithBoth = StagingModelDiffTest.MAPPER.createObjectNode();
		tableWithBoth.put("path", "/root/Contact");
		tableWithBoth.putObject("tableProperties").put("defaultLabel", "${./email}");
		final ArrayNode fieldsWithBoth = tableWithBoth.putArray("fields");
		StagingModelDiffTest.rawFieldWithAutoIncrement(fieldsWithBoth, "id", "int", Boolean.TRUE, Integer.valueOf(1));
		StagingModelDiffTest.rawFieldWithAutoIncrement(fieldsWithBoth, "email", "string", Boolean.FALSE, null);

		final ObjectNode tableWithNeither = StagingModelDiffTest.MAPPER.createObjectNode();
		tableWithNeither.put("path", "/root/Company");
		final ArrayNode fieldsWithNeither = tableWithNeither.putArray("fields");
		StagingModelDiffTest.rawFieldWithAutoIncrement(fieldsWithNeither, "id", "int", Boolean.TRUE, null);

		final ArrayNode tables = StagingModelDiffTest.MAPPER.createArrayNode();
		tables.add(tableWithBoth);
		tables.add(tableWithNeither);

		final List<StagingModelDiff.ExistingTable> parsed = StagingModelDiff.parseExisting(tables);
		final StagingModelDiff.ExistingTable contact = parsed.get(0);
		StagingModelDiffTest.check("Contact's defaultLabel is read from tableProperties.defaultLabel", "${./email}".equals(contact.defaultLabel));
		StagingModelDiffTest.check("Contact's id has autoIncrementStart=1", contact.field("id").autoIncrementStart.intValue() == 1);

		final StagingModelDiff.ExistingTable company = parsed.get(1);
		StagingModelDiffTest.check("Company has no tableProperties at all -> defaultLabel null, not an empty string", company.defaultLabel == null);
		StagingModelDiffTest.check("Company's id has no autoIncrementStart facet -> null, not zero", company.field("id").autoIncrementStart == null);
	}

	// --------------------------------------------------------------------------------------------------------- helpers

	private static void rawFieldWithAutoIncrement(final ArrayNode pFields, final String pName, final String pType, final Boolean pIsPrimaryKey,
		final Integer pAutoIncrementStart)
	{
		final ObjectNode fieldNode = pFields.addObject();
		final ObjectNode field = fieldNode.putObject("field");
		field.put("name", pName);
		field.put("path", "/root/Contact/" + pName);
		field.put("type", pType);
		if (pIsPrimaryKey != null)
		{
			field.put("isPrimaryKey", pIsPrimaryKey.booleanValue());
		}
		final ObjectNode constraints = fieldNode.putObject("constraints");
		if (pAutoIncrementStart != null)
		{
			constraints.put("autoIncrementStart", pAutoIncrementStart.intValue());
		}
	}

	private static void rawField(final ArrayNode pFields, final String pName, final String pType, final Boolean pIsPrimaryKey, final Boolean pMandatory,
		final String pMaxLength, final Boolean pIsForeignKey, final String pForeignKeyTargetPath)
	{
		final ObjectNode fieldNode = pFields.addObject();
		final ObjectNode field = fieldNode.putObject("field");
		field.put("name", pName);
		field.put("path", "/root/Contact/" + pName);
		field.put("type", pType);
		if (pIsPrimaryKey != null)
		{
			field.put("isPrimaryKey", pIsPrimaryKey.booleanValue());
		}
		if (pIsForeignKey != null)
		{
			field.put("isForeignKey", pIsForeignKey.booleanValue());
		}
		if (pForeignKeyTargetPath != null)
		{
			field.put("foreignKeyTargetPath", pForeignKeyTargetPath);
		}
		final ObjectNode constraints = fieldNode.putObject("constraints");
		if (pMandatory != null)
		{
			constraints.put("mandatory", pMandatory.booleanValue());
		}
		if (pMaxLength != null)
		{
			constraints.put("maxLength", pMaxLength);
		}
	}

	private static StagingModelDiff.ExistingTable existingTableFor(final StagingModel.EntityInfo pEntity)
	{
		final List<StagingModelDiff.ExistingField> fields = new ArrayList<StagingModelDiff.ExistingField>();
		for (final StagingModel.AttributeInfo attribute : pEntity.attributes)
		{
			fields.add(new StagingModelDiff.ExistingField(attribute.name, "string", attribute.primaryKey, attribute.mandatory, attribute.maxLength, false, null));
		}
		return new StagingModelDiff.ExistingTable(pEntity.name, fields);
	}

	private static StagingModelDiff.ExistingTable supplierExistingTable()
	{
		final List<StagingModelDiff.ExistingField> fields = new ArrayList<StagingModelDiff.ExistingField>(Arrays.asList(
			new StagingModelDiff.ExistingField("sourceSystem", "string", true, false, Integer.valueOf(40), false, null),
			new StagingModelDiff.ExistingField("sourceId", "string", true, false, Integer.valueOf(255), false, null),
			new StagingModelDiff.ExistingField("legalName", "string", false, false, Integer.valueOf(255), false, null)));
		fields.addAll(StagingModelDiffTest.technicalBookkeepingFields());
		return new StagingModelDiff.ExistingTable("Supplier", fields);
	}

	/**
	 * The five technical bookkeeping fields {@link StagingModel#build} always adds to every generated table
	 * (never facet-carrying, never a business decision): an already fully-generated table's fixture needs
	 * these too, or the diff sees them as missing and reports spurious additive "new field" changes.
	 * {@code _sourceHash} (added 2026-09-23, the incremental-transfer lot) is included here too - a table
	 * built by the CURRENT {@link StagingModel#build} always carries it; {@link
	 * #technicalBookkeepingFieldsBeforeSourceHash} is the separate fixture for the "not yet regenerated"
	 * project a real diff must still handle.
	 */
	private static List<StagingModelDiff.ExistingField> technicalBookkeepingFields()
	{
		final List<StagingModelDiff.ExistingField> fields = new ArrayList<StagingModelDiff.ExistingField>(StagingModelDiffTest.technicalBookkeepingFieldsBeforeSourceHash());
		fields.add(new StagingModelDiff.ExistingField("_sourceHash", "string", false, false, null, false, null));
		return fields;
	}

	/** The four technical bookkeeping fields a table built BEFORE the 2026-09-23 incremental-transfer lot has - {@code _sourceHash} itself is the very thing a diff against it must report as additive. */
	private static List<StagingModelDiff.ExistingField> technicalBookkeepingFieldsBeforeSourceHash()
	{
		return Arrays.asList(new StagingModelDiff.ExistingField("_loadId", "string", false, false, null, false, null),
			new StagingModelDiff.ExistingField("_receivedAt", "dateTime", false, false, null, false, null),
			new StagingModelDiff.ExistingField("_status", "string", false, false, null, false, null),
			new StagingModelDiff.ExistingField("_rejectReason", "text", false, false, null, false, null));
	}

	private static StagingModel.EntityInfo supplierEntity()
	{
		return StagingModelDiffTest.entity("Supplier", Arrays.asList(StagingModelDiffTest.technical("sourceSystem", 40),
			StagingModelDiffTest.technical("sourceId", 255), StagingModelDiffTest.attribute("legalName", "STRING", 255, false)));
	}

	private static StagingModel.EntityInfo entity(final String pName, final List<StagingModel.AttributeInfo> pAttributes)
	{
		return new StagingModel.EntityInfo(pName, pName, "ACCEPTED", pAttributes, Collections.<String>emptyList());
	}

	private static StagingModel.AttributeInfo technical(final String pName, final int pMaxLength)
	{
		return new StagingModel.AttributeInfo(pName, "sourceSystem".equals(pName) ? "Source system" : "Source id", "STRING", Integer.valueOf(pMaxLength), true, true,
			null, "ACCEPTED");
	}

	private static StagingModel.AttributeInfo attribute(final String pName, final String pDataType, final int pMaxLength, final boolean pMandatory)
	{
		return new StagingModel.AttributeInfo(pName, pName, pDataType, Integer.valueOf(pMaxLength), pMandatory, false, null, "ACCEPTED");
	}

	private static boolean anyContains(final List<String> pStrings, final String pFragment)
	{
		for (final String string : pStrings)
		{
			if (string.contains(pFragment))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasOp(final ArrayNode pOperations, final String pOp, final String pArgumentName, final String pArgumentValue)
	{
		return StagingModelDiffTest.opIndex(pOperations, pOp, pArgumentName, pArgumentValue) >= 0;
	}

	private static boolean hasSetElementPropertyOp(final ArrayNode pOperations, final String pElementPath, final String pProperty, final String pValue)
	{
		for (final JsonNode op : pOperations)
		{
			if (!"set_element_property".equals(op.path("op").asText()))
			{
				continue;
			}
			final JsonNode arguments = op.path("arguments");
			if (pElementPath.equals(arguments.path("element_path").asText()) && pProperty.equals(arguments.path("property").asText())
				&& pValue.equals(arguments.path("value").asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static int opIndex(final ArrayNode pOperations, final String pOp, final String pArgumentName, final String pArgumentValue)
	{
		for (int i = 0; i < pOperations.size(); i++)
		{
			final JsonNode op = pOperations.get(i);
			if (!pOp.equals(op.path("op").asText()))
			{
				continue;
			}
			if (pArgumentValue.equals(op.path("arguments").path(pArgumentName).asText()))
			{
				return i;
			}
		}
		return -1;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
