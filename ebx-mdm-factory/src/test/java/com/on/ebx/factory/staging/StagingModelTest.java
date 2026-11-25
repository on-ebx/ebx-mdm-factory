package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class StagingModelTest
{
	public static void main(final String[] pArgs)
	{
		StagingModelTest.realDemoAccountContact();
		StagingModelTest.proposedAndRejectedLinesAreNeverGenerated();
		StagingModelTest.entityWithOnlyTheTechnicalKeyIsSkipped();
		StagingModelTest.entityWithNoAcceptedPrimaryKeyIsSkipped();
		StagingModelTest.referenceToASkippedEntityIsKeptAsPlainData();
		StagingModelTest.canonicalEntityAndAcceptedMemberBothGenerateAndGetANote();
		StagingModelTest.childKeysAttributeIsAPlainStringFieldWithItsLength();
		System.out.println("StagingModelTest OK");
	}

	/**
	 * The real demo (PLAN.md, 2026-09-20): entities {@code Account} and {@code Contact} accepted, {@code Contact.accountId} an accepted
	 * foreign key to {@code Account}. Checks the tree (tables, primary keys, field order, the generated {@code accountIdRef}), the ops
	 * (maxLength / mandatory+minOccurs, and none of either on a primary-key field), and the foreign key bookkeeping.
	 */
	private static void realDemoAccountContact()
	{
		final StagingModel.EntityInfo account = StagingModelTest.entity("Account", "ACCEPTED", Arrays.asList(
			StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
			new StagingModel.AttributeInfo("accountId", "Account id", "STRING", Integer.valueOf(40), true, false, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, false, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("annualRevenue", "Annual revenue", "DECIMAL", null, false, false, null, "ACCEPTED")));
		final StagingModel.EntityInfo contact = StagingModelTest.entity("Contact", "ACCEPTED", Arrays.asList(
			StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
			new StagingModel.AttributeInfo("contactId", "Contact id", "STRING", Integer.valueOf(40), true, false, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("accountId", "Account id", "STRING", Integer.valueOf(40), false, false, "Account", "ACCEPTED"),
			new StagingModel.AttributeInfo("hasOptedOutOfEmail", "Opted out", "BOOLEAN", null, false, false, null, "ACCEPTED")));

		final StagingModel.Result result = StagingModel.build(Arrays.asList(account, contact));
		StagingModelTest.check("no entity skipped", result.skippedEntities.isEmpty());
		StagingModelTest.check("no note (both entities built)", result.notes.isEmpty());
		StagingModelTest.check("two tables", result.tree.path("tables").size() == 2);
		StagingModelTest.check("both entities reported as built", result.builtEntities.size() == 2 && result.builtEntities.contains("Account")
			&& result.builtEntities.contains("Contact"));

		final JsonNode accountTable = StagingModelTest.table(result.tree, "Account");
		StagingModelTest.check("Account path", "/root/Account".equals(accountTable.path("path").asText()));
		StagingModelTest.check("Account primary key: sourceSystem then sourceId", "sourceSystem".equals(accountTable.path("primaryKey").get(0).asText())
			&& "sourceId".equals(accountTable.path("primaryKey").get(1).asText()) && accountTable.path("primaryKey").size() == 2);
		// sourceSystem, sourceId, accountId, name, annualRevenue, _loadId, _receivedAt, _status, _rejectReason, _sourceHash
		final JsonNode accountFields = accountTable.path("fields");
		StagingModelTest.check("Account has 10 fields: " + accountFields.size(), accountFields.size() == 10);
		StagingModelTest.check("field order: key first", "sourceSystem".equals(accountFields.get(0).path("field").path("name").asText())
			&& "sourceId".equals(accountFields.get(1).path("field").path("name").asText()));
		StagingModelTest.check("technical fields at the end, in order", "_loadId".equals(accountFields.get(5).path("field").path("name").asText())
			&& "_receivedAt".equals(accountFields.get(6).path("field").path("name").asText()) && "_status".equals(accountFields.get(7).path("field").path("name").asText())
			&& "_rejectReason".equals(accountFields.get(8).path("field").path("name").asText())
			&& "_sourceHash".equals(accountFields.get(9).path("field").path("name").asText()));
		StagingModelTest.check("_receivedAt is dateTime", "dateTime".equals(accountFields.get(6).path("field").path("type").asText()));
		StagingModelTest.check("_rejectReason is text", "text".equals(accountFields.get(8).path("field").path("type").asText()));
		StagingModelTest.check("_sourceHash is string", "string".equals(accountFields.get(9).path("field").path("type").asText()));
		StagingModelTest.check("annualRevenue is decimal", "decimal".equals(accountFields.get(4).path("field").path("type").asText()));
		for (final JsonNode field : accountFields)
		{
			StagingModelTest.check("no constraints block in the tree (never lowered anyway)", !field.has("constraints"));
		}

		final JsonNode contactTable = StagingModelTest.table(result.tree, "Contact");
		final JsonNode contactFields = contactTable.path("fields");
		// sourceSystem, sourceId, contactId, accountId, accountIdRef, hasOptedOutOfEmail, _loadId, _receivedAt, _status, _rejectReason, _sourceHash
		StagingModelTest.check("Contact has 11 fields: " + contactFields.size(), contactFields.size() == 11);
		final JsonNode accountIdField = contactFields.get(3);
		final JsonNode accountIdRefField = contactFields.get(4);
		StagingModelTest.check("accountId stays plain data, untouched", "accountId".equals(accountIdField.path("field").path("name").asText())
			&& !accountIdField.path("field").path("isForeignKey").asBoolean(false));
		StagingModelTest.check("accountIdRef is the generated foreign key, right after accountId",
			"accountIdRef".equals(accountIdRefField.path("field").path("name").asText()) && accountIdRefField.path("field").path("isForeignKey").asBoolean(false)
				&& "/root/Account".equals(accountIdRefField.path("field").path("foreignKeyTargetPath").asText()));
		StagingModelTest.check("accountIdRef is a string field", "string".equals(accountIdRefField.path("field").path("type").asText()));

		// foreign key bookkeeping
		StagingModelTest.check("one foreign key reported", result.foreignKeys.size() == 1);
		final StagingModel.ForeignKeyInfo fk = result.foreignKeys.get(0);
		StagingModelTest.check("fk bookkeeping is correct", "Contact".equals(fk.entityName) && "accountId".equals(fk.attributeName)
			&& "accountIdRef".equals(fk.fieldName) && "Account".equals(fk.targetEntityName));

		// ops: maxLength on every STRING field that has one, mandatory+minOccurs together on mandatory business fields,
		// nothing at all (no op) on a primary-key field (minOccurs=1 already comes from the primaryKey array itself)
		StagingModelTest.check("no op on sourceSystem/sourceId (primary key, handled by the tree alone)",
			!StagingModelTest.hasOp(result.operations, "/root/Account/sourceSystem", "mandatory")
				&& !StagingModelTest.hasOp(result.operations, "/root/Account/sourceId", "mandatory"));
		StagingModelTest.check("sourceSystem/sourceId still get maxLength", StagingModelTest.opValue(result.operations, "/root/Account/sourceSystem", "maxLength").equals("40")
			&& StagingModelTest.opValue(result.operations, "/root/Contact/sourceId", "maxLength").equals("255"));
		StagingModelTest.check("accountId (mandatory business key) gets mandatory+minOccurs",
			"true".equals(StagingModelTest.opValue(result.operations, "/root/Account/accountId", "mandatory"))
				&& "1".equals(StagingModelTest.opValue(result.operations, "/root/Account/accountId", "minOccurs")));
		StagingModelTest.check("name (not mandatory) gets no mandatory op", !StagingModelTest.hasOp(result.operations, "/root/Account/name", "mandatory"));
		StagingModelTest.check("annualRevenue (DECIMAL) gets no maxLength op", !StagingModelTest.hasOp(result.operations, "/root/Account/annualRevenue", "maxLength"));
		StagingModelTest.check("accountIdRef gets its own maxLength op", "500".equals(StagingModelTest.opValue(result.operations, "/root/Contact/accountIdRef", "maxLength")));

		// order within one field: maxLength before mandatory/minOccurs
		final int maxLengthIndex = StagingModelTest.opIndex(result.operations, "/root/Account/accountId", "maxLength");
		final int mandatoryIndex = StagingModelTest.opIndex(result.operations, "/root/Account/accountId", "mandatory");
		StagingModelTest.check("maxLength emitted before mandatory for the same field", maxLengthIndex >= 0 && mandatoryIndex >= 0 && maxLengthIndex < mandatoryIndex);
	}

	/** Status filtering is StagingModel's own job (PLAN.md: "a PROPOSED or REJECTED line is never generated"), not the caller's. */
	private static void proposedAndRejectedLinesAreNeverGenerated()
	{
		final StagingModel.EntityInfo proposedEntity = StagingModelTest.entity("Draft", "PROPOSED", Arrays.asList(
			StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
			new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(80), false, false, null, "PROPOSED")));
		final StagingModel.EntityInfo acceptedWithMixedAttributes = StagingModelTest.entity("Item", "ACCEPTED", Arrays.asList(
			StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
			new StagingModel.AttributeInfo("keep", "Keep", "STRING", Integer.valueOf(80), false, false, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("stillProposed", "Still proposed", "STRING", Integer.valueOf(80), false, false, null, "PROPOSED"),
			new StagingModel.AttributeInfo("rejected", "Rejected", "STRING", Integer.valueOf(80), false, false, null, "REJECTED")));

		final StagingModel.Result result = StagingModel.build(Arrays.asList(proposedEntity, acceptedWithMixedAttributes));
		StagingModelTest.check("the PROPOSED entity never becomes a table", StagingModelTest.table(result.tree, "Draft") == null);
		final JsonNode itemFields = StagingModelTest.table(result.tree, "Item").path("fields");
		// sourceSystem, sourceId, keep, _loadId, _receivedAt, _status, _rejectReason, _sourceHash = 8 (stillProposed/rejected excluded)
		StagingModelTest.check("only the ACCEPTED attribute is generated: " + itemFields.size(), itemFields.size() == 8);
		final List<String> names = new ArrayList<String>();
		for (final JsonNode field : itemFields)
		{
			names.add(field.path("field").path("name").asText());
		}
		StagingModelTest.check("keep is there, stillProposed/rejected are not", names.contains("keep") && !names.contains("stillProposed") && !names.contains("rejected"));
	}

	/** The exact message shape the coordinator asked for: names the entity, says how many (non-key) attributes are still waiting for review. */
	private static void entityWithOnlyTheTechnicalKeyIsSkipped()
	{
		final StagingModel.EntityInfo empty = StagingModelTest.entity("Empty", "ACCEPTED", Arrays.asList(StagingModelTest.technical("sourceSystem", 40),
			StagingModelTest.technical("sourceId", 255), new StagingModel.AttributeInfo("stillWaiting", "Still waiting", "STRING", Integer.valueOf(80), false, false, null, "PROPOSED"),
			new StagingModel.AttributeInfo("alsoWaiting", "Also waiting", "STRING", Integer.valueOf(80), false, false, null, "PROPOSED")));
		final StagingModel.Result result = StagingModel.build(Arrays.asList(empty));
		StagingModelTest.check("no table built", result.tree.path("tables").size() == 0);
		StagingModelTest.check("one entity skipped, naming it and saying how many attributes wait: " + result.skippedEntities, result.skippedEntities.size() == 1
			&& result.skippedEntities.get(0).equals("Accept at least one attribute of Empty: 2 attribute(s) waiting for review."));
	}

	private static void entityWithNoAcceptedPrimaryKeyIsSkipped()
	{
		final StagingModel.EntityInfo noKey = StagingModelTest.entity("NoKey", "ACCEPTED", Arrays.asList(
			new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(80), false, false, null, "ACCEPTED")));
		final StagingModel.Result result = StagingModel.build(Arrays.asList(noKey));
		StagingModelTest.check("no table built", result.tree.path("tables").size() == 0);
		StagingModelTest.check("skipped for missing the technical key, not for lacking a business attribute",
			result.skippedEntities.get(0).contains("primary key"));
	}

	/** An accepted reference whose target entity did not itself get built: the plain attribute stays, no foreign key, a note explains why. */
	private static void referenceToASkippedEntityIsKeptAsPlainData()
	{
		final StagingModel.EntityInfo child = StagingModelTest.entity("Child", "ACCEPTED", Arrays.asList(
			StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
			new StagingModel.AttributeInfo("parentId", "Parent id", "STRING", Integer.valueOf(40), false, false, "Parent", "ACCEPTED")));
		// "Parent" is never passed in at all - as if it were rejected, or never proposed
		final StagingModel.Result result = StagingModel.build(Arrays.asList(child));
		final JsonNode childFields = StagingModelTest.table(result.tree, "Child").path("fields");
		final List<String> names = new ArrayList<String>();
		for (final JsonNode field : childFields)
		{
			names.add(field.path("field").path("name").asText());
		}
		StagingModelTest.check("parentId kept as plain data", names.contains("parentId"));
		StagingModelTest.check("no parentIdRef generated", !names.contains("parentIdRef"));
		StagingModelTest.check("no foreign key reported", result.foreignKeys.isEmpty());
		StagingModelTest.check("a note explains why", result.notes.size() == 1 && result.notes.get(0).contains("Parent"));
	}

	/**
	 * The overlap the coordinator asked for (2026-09-20): a canonical entity ({@code Company}, unifying {@code Supplier} and {@code
	 * Account}) and one of its members ({@code Supplier}) both accepted independently - {@link StagingModel} must NOT silently decide
	 * that the member's table is unnecessary: both tables are generated, and a note tells a person to decide which one feeds the master.
	 */
	private static void canonicalEntityAndAcceptedMemberBothGenerateAndGetANote()
	{
		final StagingModel.EntityInfo supplier = StagingModelTest.entity("Supplier", "ACCEPTED", Arrays.asList(
			StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
			new StagingModel.AttributeInfo("legalName", "Legal name", "STRING", Integer.valueOf(255), false, false, null, "ACCEPTED")));
		final StagingModel.EntityInfo account = StagingModelTest.entity("Account", "ACCEPTED", Arrays.asList(
			StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
			new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, false, null, "ACCEPTED")));
		final StagingModel.EntityInfo company = new StagingModel.EntityInfo("Company", "Company", "ACCEPTED",
			Arrays.asList(StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
				new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, false, null, "ACCEPTED")),
			Arrays.asList("Supplier", "Account"));

		final StagingModel.Result result = StagingModel.build(Arrays.asList(supplier, account, company));
		StagingModelTest.check("all three tables generated - StagingModel never silently drops the member",
			result.tree.path("tables").size() == 3 && result.builtEntities.containsAll(Arrays.asList("Supplier", "Account", "Company")));
		StagingModelTest.check("two notes, one per overlapping member, naming Company and the exact wording asked for",
			result.notes.size() == 2 && StagingModelTest.containsNote(result.notes, "Company and its member Supplier are both accepted")
				&& StagingModelTest.containsNote(result.notes, "Company and its member Account are both accepted"));
		StagingModelTest.check("the note says to decide which one feeds the master", result.notes.get(0).contains("decide which one feeds the master"));

		// an entity with no members at all (the ordinary case) never gets this note
		final StagingModel.Result soloResult = StagingModel.build(Arrays.asList(supplier));
		StagingModelTest.check("no members, no note", soloResult.notes.isEmpty());
	}

	/**
	 * The exact live defect (PLAN.md, 2026-09-20, project {@code Companies}): {@code Account} accepted, its {@code sourceSystem}/{@code
	 * sourceId} still {@code PROPOSED} (nobody had, or should have had, to accept them independently) - {@code staging/generate} refused
	 * with "no accepted primary key attribute". {@link TechnicalKeyRule}: the technical key follows its entity, whatever its own stored
	 * status - PROPOSED, or even REJECTED (stale, out-of-sync data) - the table is still generated.
	 */
	private static void technicalKeyFollowsItsEntityRegardlessOfItsOwnStatus()
	{
		final StagingModel.EntityInfo accountStalePending = StagingModelTest.entity("Account", "ACCEPTED", Arrays.asList(
			StagingModelTest.technicalWithStatus("sourceSystem", 40, "PROPOSED"), StagingModelTest.technicalWithStatus("sourceId", 255, "PROPOSED"),
			new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, false, null, "ACCEPTED")));
		final StagingModel.Result result = StagingModel.build(Arrays.asList(accountStalePending));
		StagingModelTest.check("still generated: the technical key's own PROPOSED status never blocks generation", result.skippedEntities.isEmpty()
			&& result.tree.path("tables").size() == 1);
		final JsonNode fields = StagingModelTest.table(result.tree, "Account").path("fields");
		StagingModelTest.check("sourceSystem/sourceId are still the primary key",
			"sourceSystem".equals(fields.get(0).path("field").path("name").asText()) && "sourceId".equals(fields.get(1).path("field").path("name").asText()));

		// even a stale REJECTED technical key (desynced data) does not block its accepted entity - it has no decision of its own
		final StagingModel.EntityInfo accountStaleRejected = StagingModelTest.entity("Account", "ACCEPTED", Arrays.asList(
			StagingModelTest.technicalWithStatus("sourceSystem", 40, "REJECTED"), StagingModelTest.technicalWithStatus("sourceId", 255, "REJECTED"),
			new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, false, null, "ACCEPTED")));
		final StagingModel.Result rejectedKeyResult = StagingModel.build(Arrays.asList(accountStaleRejected));
		StagingModelTest.check("a REJECTED technical key still does not block its ACCEPTED entity", rejectedKeyResult.skippedEntities.isEmpty()
			&& rejectedKeyResult.tree.path("tables").size() == 1);
	}

	private static boolean containsNote(final List<String> pNotes, final String pFragment)
	{
		for (final String note : pNotes)
		{
			if (note.contains(pFragment))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Lot M3 (2026-10-03): a {@code CHILD_KEYS} attribute (the keys of the child records, on the parent) needs nothing special
	 * from the generated model - an ordinary optional string field with the attribute's own length, never mandatory, never a
	 * foreign key - the post-pass of the loader writes it, the master load copies it.
	 */
	private static void childKeysAttributeIsAPlainStringFieldWithItsLength()
	{
		final StagingModel.EntityInfo personne = StagingModelTest.entity("Personne", "ACCEPTED", Arrays.asList(
			StagingModelTest.technical("sourceSystem", 40), StagingModelTest.technical("sourceId", 255),
			new StagingModel.AttributeInfo("lastName", "Last name", "STRING", Integer.valueOf(100), false, false, null, "ACCEPTED"),
			new StagingModel.AttributeInfo("adresseKeys", "Address keys", "STRING", Integer.valueOf(2000), false, false, null, "ACCEPTED", "CHILD_KEYS")));
		final StagingModel.Result result = StagingModel.build(Collections.singletonList(personne));
		StagingModelTest.check("entity built", result.builtEntities.contains("Personne") && result.skippedEntities.isEmpty());
		JsonNode keys = null;
		for (final JsonNode field : StagingModelTest.table(result.tree, "Personne").path("fields"))
		{
			if ("adresseKeys".equals(field.path("field").path("name").asText()))
			{
				keys = field.path("field");
			}
		}
		StagingModelTest.check("adresseKeys is a field of the staging table", keys != null);
		StagingModelTest.check("a plain string", "string".equals(keys.path("type").asText()) && !keys.path("isForeignKey").asBoolean(false));
		StagingModelTest.check("its length is the attribute's own", "2000".equals(StagingModelTest.opValue(result.operations, "/root/Personne/adresseKeys", "maxLength")));
		StagingModelTest.check("never mandatory", !StagingModelTest.hasOp(result.operations, "/root/Personne/adresseKeys", "mandatory"));
		StagingModelTest.check("no foreign key", result.foreignKeys.isEmpty());
	}

	// --------------------------------------------------------------------------------------------------------- helpers

	/** Builds an {@code EntityInfo} with no members - the ordinary, rule-proposed case every test but the member-overlap one needs. */
	private static StagingModel.EntityInfo entity(final String pName, final String pStatus, final List<StagingModel.AttributeInfo> pAttributes)
	{
		return new StagingModel.EntityInfo(pName, pName, pStatus, pAttributes, Collections.<String>emptyList());
	}

	private static StagingModel.AttributeInfo technical(final String pName, final int pMaxLength)
	{
		return StagingModelTest.technicalWithStatus(pName, pMaxLength, "ACCEPTED");
	}

	/** A technical key attribute with an explicit status, to prove {@link TechnicalKeyRule}: its own status never gates generation. */
	private static StagingModel.AttributeInfo technicalWithStatus(final String pName, final int pMaxLength, final String pStatus)
	{
		return new StagingModel.AttributeInfo(pName, "sourceSystem".equals(pName) ? "Source system" : "Source id", "STRING", Integer.valueOf(pMaxLength), true, true,
			null, pStatus);
	}

	private static JsonNode table(final JsonNode pTree, final String pName)
	{
		for (final JsonNode table : pTree.path("tables"))
		{
			if (("/root/" + pName).equals(table.path("path").asText()))
			{
				return table;
			}
		}
		return null;
	}

	private static boolean hasOp(final JsonNode pOperations, final String pElementPath, final String pProperty)
	{
		return StagingModelTest.opIndex(pOperations, pElementPath, pProperty) >= 0;
	}

	private static int opIndex(final JsonNode pOperations, final String pElementPath, final String pProperty)
	{
		for (int i = 0; i < pOperations.size(); i++)
		{
			final JsonNode arguments = pOperations.get(i).path("arguments");
			if (pElementPath.equals(arguments.path("element_path").asText()) && pProperty.equals(arguments.path("property").asText()))
			{
				return i;
			}
		}
		return -1;
	}

	private static String opValue(final JsonNode pOperations, final String pElementPath, final String pProperty)
	{
		final int index = StagingModelTest.opIndex(pOperations, pElementPath, pProperty);
		return index < 0 ? null : pOperations.get(index).path("arguments").path("value").asText();
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
