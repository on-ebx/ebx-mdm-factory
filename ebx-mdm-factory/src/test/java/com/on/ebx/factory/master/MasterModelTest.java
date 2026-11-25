package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MasterModelTest
{
	public static void main(final String[] pArgs)
	{
		MasterModelTest.realDemo_companyAndContactPromoted_supplierAndAccountKeptInStaging();
		MasterModelTest.idFieldIsAutoIncrementPrimaryKey();
		MasterModelTest.sourceSystemAndSourceIdAreOptionalNeverPrimaryKey();
		MasterModelTest.businessAttributesAreNeverMandatoryEvenIfStagingWas();
		MasterModelTest.derivedFoldKeyAttributeCarriedLikeAnyOtherAttribute();
		MasterModelTest.childKeysAttributeCarriedLikeAnyOtherAttribute();
		MasterModelTest.technicalFieldsAreOnlyLoadIdAndReceivedAt();
		MasterModelTest.unresolvableReferenceKeptAsPlainDataWithNote();
		MasterModelTest.noEntityPromotedWhenEverythingIsAMember();
		MasterModelTest.resolveReference_memberResolvesToItsCanonical();
		MasterModelTest.resolveReference_directPromotedEntityResolvesToItself();
		MasterModelTest.resolveReference_selfReferenceResolvesToItself_notExcludedHere();
		MasterModelTest.resolveReference_unknownEntityResolvesToNull();
		MasterModelTest.childTablePaths_selfReferenceExcluded_multipleEdgesFromSameChildDeduped();
		MasterModelTest.childEntityListedBeforeItsParent_reorderedParentFirst();
		MasterModelTest.mutualReferenceCycle_neverThrows_fallsBackToOriginalOrder();
		MasterModelTest.defaultLabel_pointsAtNameField_whenPresent();
		MasterModelTest.defaultLabel_pointsAtSuffixNameField_whenNoBareName();
		MasterModelTest.defaultLabel_fallsBackToFirstEligibleField_whenNoNameLikeField();
		MasterModelTest.defaultLabel_absentWhenNoEligibleField();
		MasterModelTest.defaultLabel_neverPicksForeignKeyOrNonStringField();
		MasterModelTest.reservedAttributeName_promotedWithSrcSuffix_mappingAndNoteFollow();
		MasterModelTest.reservedAttributeName_referenceAndDisplayCandidateUseTheMasterName();
		System.out.println("MasterModelTest OK");
	}

	// ------------------------------------------------------------------------------------------- osd:table/defaultLabel

	/**
	 * A genuinely NEW table's defaultLabel points at a bare "name" field over any other candidate. MEASURED
	 * LIVE, 2026-09-22 (`docs/MASTER-ZONE.md` &sect;10): {@code apply_data_model_patch} refuses {@code
	 * "defaultLabel"} as a {@code set_element_property} property outright, so this is plain DATA
	 * ({@code defaultLabelByTable}, table path -&gt; label template), never a {@code set_element_property} op -
	 * {@code ebx.MasterGenerator} applies it itself via {@code execute_operation(set_default_label)}.
	 */
	private static void defaultLabel_pointsAtNameField_whenPresent()
	{
		final MasterModel.EntityInfo widget = MasterModelTest.entity("Widget", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("city", "STRING", 80, null), MasterModelTest.attribute("name", "STRING", 80, null)));
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(widget));
		MasterModelTest.check("a bare 'name' field wins the display attribute, regardless of declaration order",
			"${./name}".equals(result.defaultLabelByTable.get("/root/Widget")));
		MasterModelTest.check("no defaultLabel property is ever emitted as a set_element_property op (apply_data_model_patch refuses it)",
			!MasterModelTest.hasAnySetElementPropertyOp(result.operations, "defaultLabel"));
	}

	private static void defaultLabel_pointsAtSuffixNameField_whenNoBareName()
	{
		final MasterModel.EntityInfo widget = MasterModelTest.entity("Widget", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("city", "STRING", 80, null), MasterModelTest.attribute("legalName", "STRING", 120, null)));
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(widget));
		MasterModelTest.check("'legalName' wins over 'city' when no bare 'name'/'label'/'title' field exists",
			"${./legalName}".equals(result.defaultLabelByTable.get("/root/Widget")));
	}

	private static void defaultLabel_fallsBackToFirstEligibleField_whenNoNameLikeField()
	{
		final MasterModel.EntityInfo widget = MasterModelTest.entity("Widget", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("city", "STRING", 80, null), MasterModelTest.attribute("country", "STRING", 80, null)));
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(widget));
		MasterModelTest.check("the first eligible business attribute wins when nothing looks name-shaped",
			"${./city}".equals(result.defaultLabelByTable.get("/root/Widget")));
	}

	private static void defaultLabel_absentWhenNoEligibleField()
	{
		final MasterModel.EntityInfo widget = MasterModelTest.entity("Widget", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255)));
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(widget));
		MasterModelTest.check("no defaultLabelByTable entry at all when only technical fields exist - the table keeps its key-based label",
			!result.defaultLabelByTable.containsKey("/root/Widget"));
		MasterModelTest.check("no defaultLabel property appears anywhere in operations either",
			!MasterModelTest.hasAnySetElementPropertyOp(result.operations, "defaultLabel"));
	}

	/** Neither a foreign key field ("*Ref", points at ANOTHER record) nor a non-STRING attribute is ever picked. */
	private static void defaultLabel_neverPicksForeignKeyOrNonStringField()
	{
		final MasterModel.EntityInfo account = MasterModelTest.entity("Account", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("name", "STRING", 80, null)));
		final MasterModel.EntityInfo widget = MasterModelTest.entity("Widget", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("employeeCount", "INTEGER", 0, null), MasterModelTest.reference("accountId", "Account", 40),
				MasterModelTest.attribute("city", "STRING", 80, null)));
		final MasterModel.Result result = MasterModel.build(Arrays.asList(account, widget));
		MasterModelTest.check("neither the foreign key field nor the INTEGER field is picked - 'city' (the only eligible STRING business field) wins",
			"${./city}".equals(result.defaultLabelByTable.get("/root/Widget")));
	}

	// ------------------------------------------------------------------------------------- Result.resolveReference

	/**
	 * The shared resolution {@code ebx.MatchPlanSuggester}/{@code ebx.MatchPlanLlmSuggester} now both call
	 * instead of keeping their own copy (coordinator review, 2026-09-21, after a real run showed the LLM
	 * pass' own copy missing exactly this case - see {@code ebx.MatchPlanLlmSuggester}'s own Javadoc).
	 * Neutrally-named fixture, same shape as the real {@code Contact.accountId -> Account -> Company} case
	 * covered by {@link #realDemo_companyAndContactPromoted_supplierAndAccountKeptInStaging()}: {@code Order}
	 * references {@code Customer}, a member of the canonical {@code Party} - the reference must resolve to
	 * {@code Party}, not to {@code Customer} (never independently promoted) and not to nothing.
	 */
	private static MasterModel.Result orderCustomerPartyResult()
	{
		final MasterModel.EntityInfo customer = MasterModelTest.entity("Customer", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("name", "STRING", 255, null)));
		final MasterModel.EntityInfo party = MasterModelTest.entity("Party", Collections.singletonList("Customer"),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("name", "STRING", 255, null), MasterModelTest.reference("parentPartyId", "Party", 40)));
		final MasterModel.EntityInfo order = MasterModelTest.entity("Order", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.reference("customerId", "Customer", 40), MasterModelTest.reference("partyId", "Party", 40),
				MasterModelTest.reference("ghostId", "Ghost", 40)));
		return MasterModel.build(Arrays.asList(customer, party, order));
	}

	private static void resolveReference_memberResolvesToItsCanonical()
	{
		final MasterModel.Result result = MasterModelTest.orderCustomerPartyResult();
		MasterModelTest.check("Customer is kept in staging only (member of Party)", !result.builtEntities.contains("Customer"));
		MasterModelTest.check("a reference to the member Customer resolves to its canonical Party",
			"Party".equals(result.resolveReference("Customer")));
	}

	private static void resolveReference_directPromotedEntityResolvesToItself()
	{
		final MasterModel.Result result = MasterModelTest.orderCustomerPartyResult();
		MasterModelTest.check("Party is itself promoted", result.builtEntities.contains("Party"));
		MasterModelTest.check("a reference to the already-promoted Party resolves to itself", "Party".equals(result.resolveReference("Party")));
	}

	/** {@code resolveReference} itself does NOT exclude a self-reference (a self-referencing FK, e.g. a hierarchy field, is exactly as valid as any other reference in {@code MasterModel.buildTable}'s own use of this rule) - a "child entity" caller must compare the result against its own entity name itself, see {@code ebx.MatchPlanLlmSuggester#childLink}'s own Javadoc. */
	private static void resolveReference_selfReferenceResolvesToItself_notExcludedHere()
	{
		final MasterModel.Result result = MasterModelTest.orderCustomerPartyResult();
		MasterModelTest.check("Party.parentPartyId (a self-reference) resolves to Party itself, not null",
			"Party".equals(result.resolveReference("Party")));
	}

	private static void resolveReference_unknownEntityResolvesToNull()
	{
		final MasterModel.Result result = MasterModelTest.orderCustomerPartyResult();
		MasterModelTest.check("a reference to an entity that is neither promoted nor a known member resolves to null", result.resolveReference("Ghost") == null);
	}

	/**
	 * Cascade lot (2026-09-23, `docs/MATCHING.md` &sect;12.4/&sect;22): {@code Party.parentPartyId} is a self-reference -
	 * {@link MasterModel.Result#childTablePaths} must NOT count {@code Party} as its own child (same exclusion
	 * {@link MasterModel#orderParentsBeforeChildren} already applies for the same reason - a self-reference
	 * carries no cascade meaning either). {@code Order} references {@code Party} TWICE ({@code customerId},
	 * redirected from the member {@code Customer}, and the direct {@code partyId}) - both edges collapse to the
	 * SAME single {@code /root/Order} entry, never listed twice.
	 */
	private static void childTablePaths_selfReferenceExcluded_multipleEdgesFromSameChildDeduped()
	{
		final MasterModel.Result result = MasterModelTest.orderCustomerPartyResult();
		MasterModelTest.check("Party's own children: exactly /root/Order, once - the self-reference is excluded and the two Order->Party edges dedupe",
			result.childTablePaths("Party").equals(Collections.singleton("/root/Order")));
	}

	// -------------------------------------------------------------------------- parent-before-child ordering (2026-09-22)

	/**
	 * The new guarantee `docs/MATCHING.md`/`MasterModel.orderParentsBeforeChildren`'s own Javadoc describes:
	 * before this lot, {@code builtEntities} was exactly the input order (here: Contact, then Company - the
	 * OPPOSITE of parent-first) - every caller that iterates it for matching (propose/configure/run) needs the
	 * parent processed first. This is the one test in this suite that would have failed on the un-reordered
	 * behaviour: {@code realDemo_companyAndContactPromoted_supplierAndAccountKeptInStaging} above happens to
	 * hand entities in an already-parent-first order, which proves nothing about the reordering itself.
	 */
	private static void childEntityListedBeforeItsParent_reorderedParentFirst()
	{
		final MasterModel.EntityInfo contact = MasterModelTest.entity("Contact", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("email", "STRING", 255, null), MasterModelTest.reference("companyId", "Company", 40)));
		final MasterModel.EntityInfo company = MasterModelTest.entity("Company", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("name", "STRING", 255, null)));
		// Contact BEFORE Company - the opposite of what a topological sort must produce
		final MasterModel.Result result = MasterModel.build(Arrays.asList(contact, company));
		MasterModelTest.check("both promoted", result.builtEntities.size() == 2);
		MasterModelTest.check("Company is reordered before its child Contact, despite the input order",
			result.builtEntities.equals(Arrays.asList("Company", "Contact")));
	}

	/**
	 * A genuine cycle (A references B, B references A) has no valid topological order - {@code
	 * orderParentsBeforeChildren} must never throw; it degrades to leaving whatever is left over in its
	 * original relative order, exactly as if the reordering pass had not run for that unorderable subset.
	 */
	private static void mutualReferenceCycle_neverThrows_fallsBackToOriginalOrder()
	{
		final MasterModel.EntityInfo a = MasterModelTest.entity("A", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("name", "STRING", 255, null), MasterModelTest.reference("bId", "B", 40)));
		final MasterModel.EntityInfo b = MasterModelTest.entity("B", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("name", "STRING", 255, null), MasterModelTest.reference("aId", "A", 40)));
		final MasterModel.Result result = MasterModel.build(Arrays.asList(a, b));
		MasterModelTest.check("both promoted, never throws on a cycle", result.builtEntities.size() == 2);
		MasterModelTest.check("a cycle falls back to the original relative order", result.builtEntities.equals(Arrays.asList("A", "B")));
	}

	/**
	 * The exact reference demo the coordinator's brief asks for: {@code Company} (canonical, members {@code
	 * Supplier} + {@code Account}) and {@code Contact} are promoted; {@code Supplier}/{@code Account} are kept
	 * in staging only; {@code Contact.accountIdRef} is redirected from {@code Account} to {@code Company}.
	 */
	private static void realDemo_companyAndContactPromoted_supplierAndAccountKeptInStaging()
	{
		final MasterModel.EntityInfo supplier = MasterModelTest.entity("Supplier", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("legalName", "STRING", 255, null)));
		final MasterModel.EntityInfo account = MasterModelTest.entity("Account", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("name", "STRING", 255, null)));
		final MasterModel.EntityInfo company = MasterModelTest.entity("Company", Arrays.asList("Supplier", "Account"),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("name", "STRING", 255, null), MasterModelTest.attribute("country", "STRING", 2, null)));
		final MasterModel.EntityInfo contact = MasterModelTest.entity("Contact", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("email", "STRING", 255, null), MasterModelTest.reference("accountId", "Account", 40)));

		final MasterModel.Result result = MasterModel.build(Arrays.asList(supplier, account, company, contact));

		MasterModelTest.check("Company and Contact promoted, in that order", result.builtEntities.equals(Arrays.asList("Company", "Contact")));
		MasterModelTest.check("Supplier and Account kept in staging only",
			MasterModelTest.anyContains(result.keptInStagingOnly, "Supplier: member of Company, kept in staging only.")
				&& MasterModelTest.anyContains(result.keptInStagingOnly, "Account: member of Company, kept in staging only."));
		MasterModelTest.check("no table built for Supplier or Account", MasterModelTest.tableNames(result.tree).equals(Arrays.asList("/root/Company", "/root/Contact")));

		MasterModelTest.check("exactly one foreign key: Contact.accountId", result.foreignKeys.size() == 1);
		final MasterModel.ForeignKeyInfo fk = result.foreignKeys.get(0);
		MasterModelTest.check("Contact.accountId redirected to Company, not Account",
			"Contact".equals(fk.entityName) && "accountId".equals(fk.attributeName) && "Company".equals(fk.targetEntityName));
		MasterModelTest.check("generated field is accountIdRef", "accountIdRef".equals(fk.fieldName));
		MasterModelTest.check("the FK field targets /root/Company in the tree",
			MasterModelTest.hasForeignKeyField(result.tree, "/root/Contact", "accountIdRef", "/root/Company"));

		// cascade lot (2026-09-23, docs/MATCHING.md sec 12.4/22): Contact is Company's own promoted child (it holds
		// the redirected FK) - master.RelatedTableAlignment's own child-lookup relies on this.
		MasterModelTest.check("Company's own child table paths: exactly /root/Contact",
			result.childTablePaths("Company").equals(Collections.singleton("/root/Contact")));
		MasterModelTest.check("Contact has no children of its own - nothing points at it", result.childTablePaths("Contact").isEmpty());
		MasterModelTest.check("an entity that was never promoted (or does not exist) has no children either",
			result.childTablePaths("NeverPromoted").isEmpty());
	}

	private static void idFieldIsAutoIncrementPrimaryKey()
	{
		final MasterModel.Result result = MasterModelTest.oneEntityResult();
		final JsonNode table = MasterModelTest.tableNode(result.tree, "/root/Widget");
		final List<String> primaryKey = new ArrayList<String>();
		for (final JsonNode key : table.path("primaryKey"))
		{
			primaryKey.add(key.asText());
		}
		MasterModelTest.check("primary key is exactly [id]", primaryKey.equals(Collections.singletonList("id")));
		MasterModelTest.check("id field is int", "int".equals(MasterModelTest.fieldNode(table, "id").path("type").asText()));
		MasterModelTest.check("autoIncrementStart=1 op present", MasterModelTest.hasSetElementPropertyOp(result.operations, "/root/Widget/id", "autoIncrementStart", "1"));
		MasterModelTest.check("autoIncrementStep=1 op present", MasterModelTest.hasSetElementPropertyOp(result.operations, "/root/Widget/id", "autoIncrementStep", "1"));
		MasterModelTest.check("autoIncrementOps recorded per entity", result.autoIncrementOps.get("Widget") != null && result.autoIncrementOps.get("Widget").size() == 2);
	}

	private static void sourceSystemAndSourceIdAreOptionalNeverPrimaryKey()
	{
		final MasterModel.Result result = MasterModelTest.oneEntityResult();
		final JsonNode table = MasterModelTest.tableNode(result.tree, "/root/Widget");
		MasterModelTest.check("sourceSystem is a plain string field", "string".equals(MasterModelTest.fieldNode(table, "sourceSystem").path("type").asText()));
		MasterModelTest.check("sourceId is a plain string field", "string".equals(MasterModelTest.fieldNode(table, "sourceId").path("type").asText()));
		final List<String> primaryKey = new ArrayList<String>();
		for (final JsonNode key : table.path("primaryKey"))
		{
			primaryKey.add(key.asText());
		}
		MasterModelTest.check("neither is in the primary key", !primaryKey.contains("sourceSystem") && !primaryKey.contains("sourceId"));
		MasterModelTest.check("no mandatory op on sourceSystem", !MasterModelTest.hasSetElementPropertyOp(result.operations, "/root/Widget/sourceSystem", "mandatory", "true"));
		MasterModelTest.check("no mandatory op on sourceId", !MasterModelTest.hasSetElementPropertyOp(result.operations, "/root/Widget/sourceId", "mandatory", "true"));
		MasterModelTest.check("no uniqueConstraints op on the table at all",
			!MasterModelTest.hasOp(result.operations, "set_element_property", "property", "uniqueConstraints"));
	}

	/** Even a technical-key-flavoured mandatory attribute (unrealistic, but a defensive check) never gets a mandatory op on master. */
	private static void businessAttributesAreNeverMandatoryEvenIfStagingWas()
	{
		final MasterModel.EntityInfo widget = MasterModelTest.entity("Widget", Collections.<String>emptyList(), Arrays.asList(
			MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
			new MasterModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(80), false, null, "ACCEPTED"))); // mandatory would have been true in staging
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(widget));
		MasterModelTest.check("no mandatory op anywhere", !MasterModelTest.hasOp(result.operations, "set_element_property", "property", "mandatory"));
	}

	/**
	 * A {@code FOLD_KEY} derived attribute (2026-09-22 - {@code quality.CrossSystemFold}) is a plain {@code
	 * StagingAttribute} row with no special marker {@code MasterModel.AttributeInfo} even carries a field for
	 * (see {@code MasterModel}'s own Javadoc: derived-ness is entirely a {@code staging.StagingModel}/{@code
	 * ebx.StagingLoader} concern) - it is promoted to the master table exactly like any other business
	 * attribute, field name unchanged, no mandatory op, no foreign key. A second domain (a product designation,
	 * never only company/city vocabulary): {@code designationKey} next to {@code designation}.
	 */
	private static void derivedFoldKeyAttributeCarriedLikeAnyOtherAttribute()
	{
		final MasterModel.EntityInfo part = MasterModelTest.entity("Part", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("designation", "STRING", 255, null), MasterModelTest.attribute("designationKey", "STRING", 255, null)));
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(part));
		final JsonNode table = MasterModelTest.tableNode(result.tree, "/root/Part");
		MasterModelTest.check("designationKey field present on the master table", !MasterModelTest.fieldNode(table, "designationKey").isMissingNode());
		boolean found = false;
		for (final MasterModel.FieldInfo field : result.fields)
		{
			if ("Part".equals(field.entityName) && "designationKey".equals(field.masterField))
			{
				found = true;
				MasterModelTest.check("stagingField unchanged (never renamed)", "designationKey".equals(field.stagingField));
			}
		}
		MasterModelTest.check("designationKey listed among Part's ordinary FieldInfo entries", found);
		MasterModelTest.check("no foreign key generated for it", result.foreignKeys.isEmpty());
	}

	/**
	 * Lot M3 (2026-10-03): a {@code CHILD_KEYS} attribute (the keys of the child records, on the parent) is promoted to the master
	 * table like any other string attribute - the master load copies the value the staging post-pass wrote, nothing special.
	 */
	private static void childKeysAttributeCarriedLikeAnyOtherAttribute()
	{
		final MasterModel.EntityInfo personne = MasterModelTest.entity("Personne", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("lastName", "STRING", 100, null), MasterModelTest.attribute("adresseKeys", "STRING", 2000, null)));
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(personne));
		final JsonNode table = MasterModelTest.tableNode(result.tree, "/root/Personne");
		final JsonNode keys = MasterModelTest.fieldNode(table, "adresseKeys");
		MasterModelTest.check("adresseKeys field present on the master table", !keys.isMissingNode());
		MasterModelTest.check("a plain string, not a foreign key", "string".equals(keys.path("type").asText()) && !keys.path("isForeignKey").asBoolean(false));
		MasterModelTest.check("its length carried", MasterModelTest.hasOp(result.operations, "set_element_property", "element_path", "/root/Personne/adresseKeys")
			&& MasterModelTest.anyOpValue(result.operations, "/root/Personne/adresseKeys", "maxLength", "2000"));
		boolean found = false;
		for (final MasterModel.FieldInfo field : result.fields)
		{
			found |= "Personne".equals(field.entityName) && "adresseKeys".equals(field.masterField) && "adresseKeys".equals(field.stagingField);
		}
		MasterModelTest.check("listed among Personne's ordinary FieldInfo entries, name unchanged", found);
	}

	private static boolean anyOpValue(final com.fasterxml.jackson.databind.node.ArrayNode pOperations, final String pElementPath, final String pProperty, final String pValue)
	{
		for (final JsonNode op : pOperations)
		{
			final JsonNode arguments = op.path("arguments");
			if (pElementPath.equals(arguments.path("element_path").asText()) && pProperty.equals(arguments.path("property").asText())
				&& pValue.equals(arguments.path("value").asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static void technicalFieldsAreOnlyLoadIdAndReceivedAt()
	{
		final MasterModel.Result result = MasterModelTest.oneEntityResult();
		final JsonNode table = MasterModelTest.tableNode(result.tree, "/root/Widget");
		MasterModelTest.check("_loadId present", !MasterModelTest.fieldNode(table, "_loadId").isMissingNode());
		MasterModelTest.check("_receivedAt present", !MasterModelTest.fieldNode(table, "_receivedAt").isMissingNode());
		MasterModelTest.check("_status absent", MasterModelTest.fieldNode(table, "_status").isMissingNode());
		MasterModelTest.check("_rejectReason absent", MasterModelTest.fieldNode(table, "_rejectReason").isMissingNode());
	}

	private static void unresolvableReferenceKeptAsPlainDataWithNote()
	{
		final MasterModel.EntityInfo widget = MasterModelTest.entity("Widget", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.reference("gadgetId", "Gadget", 40))); // Gadget does not exist / is not promoted
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(widget));
		MasterModelTest.check("no foreign key generated", result.foreignKeys.isEmpty());
		final JsonNode table = MasterModelTest.tableNode(result.tree, "/root/Widget");
		MasterModelTest.check("plain gadgetId field still present", !MasterModelTest.fieldNode(table, "gadgetId").isMissingNode());
		MasterModelTest.check("note explains why", MasterModelTest.anyContains(result.notes, "Widget.gadgetId: referenced entity Gadget is not promoted"));
	}

	private static void noEntityPromotedWhenEverythingIsAMember()
	{
		final MasterModel.EntityInfo member = MasterModelTest.entity("Member", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255), MasterModelTest.attribute("x", "STRING", 10, null)));
		final MasterModel.EntityInfo canonical = MasterModelTest.entity("Canonical", Collections.singletonList("Member"),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255), MasterModelTest.attribute("y", "STRING", 10, null)));
		final MasterModel.Result result = MasterModel.build(Arrays.asList(member, canonical));
		MasterModelTest.check("only Canonical promoted", result.builtEntities.equals(Collections.singletonList("Canonical")));
		MasterModelTest.check("Member reported kept in staging only", MasterModelTest.anyContains(result.keptInStagingOnly, "Member: member of Canonical"));
	}

	// ------------------------------------------------------------------------------------------ reserved field names

	/**
	 * The live refusal of 2026-09-23 (project {@code ScratchMatch}, staging entity {@code SalesforceSample}
	 * whose accepted attribute {@code Id} is the Salesforce record id): {@code build_data_model}'s dry run
	 * answered {@code SubTypeAlreadyExistsException ... AspectKey[id]} because the master table's own
	 * auto-increment {@code id} and the attribute {@code Id} are the same field name to EBX. The attribute is
	 * now promoted as {@code Id_src} ({@code MasterFieldNameRule}), the id primary key is untouched, the
	 * {@code FieldInfo} carries both names (so {@code MasterTransferPlan}/{@code MasterLoader} follow) and a
	 * note in the result says so - what {@code master/generate} echoes in its own "notes".
	 */
	private static void reservedAttributeName_promotedWithSrcSuffix_mappingAndNoteFollow()
	{
		final MasterModel.EntityInfo sample = MasterModelTest.entity("SalesforceSample", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.attribute("Id", "STRING", 18, null), MasterModelTest.attribute("Name", "STRING", 255, null),
				MasterModelTest.attribute("_receivedAt", "TIMESTAMP", 0, null)));
		final MasterModel.Result result = MasterModel.build(Collections.singletonList(sample));
		final JsonNode table = MasterModelTest.tableNode(result.tree, "/root/SalesforceSample");
		MasterModelTest.check("the table's own id primary key is untouched", "int".equals(MasterModelTest.fieldNode(table, "id").path("type").asText()));
		MasterModelTest.check("no business field named Id (case-insensitively) next to it", MasterModelTest.fieldNode(table, "Id").isMissingNode());
		MasterModelTest.check("Id is promoted as Id_src", !MasterModelTest.fieldNode(table, "Id_src").isMissingNode());
		MasterModelTest.check("Id_src keeps the attribute's own label", "Id".equals(MasterModelTest.fieldNode(table, "Id_src").path("label").asText()));
		MasterModelTest.check("the maxLength op targets the renamed field", MasterModelTest.hasSetElementPropertyOp(result.operations, "/root/SalesforceSample/Id_src", "maxLength", "18"));
		MasterModelTest.check("_receivedAt (a bookkeeping name) is promoted as _receivedAt_src, the bookkeeping field itself still present",
			!MasterModelTest.fieldNode(table, "_receivedAt_src").isMissingNode() && "dateTime".equals(MasterModelTest.fieldNode(table, "_receivedAt").path("type").asText()));
		MasterModelTest.check("Name is untouched", !MasterModelTest.fieldNode(table, "Name").isMissingNode());
		final List<String> fieldNames = new ArrayList<String>();
		for (final JsonNode fieldNode : table.path("fields"))
		{
			fieldNames.add(fieldNode.path("field").path("name").asText().toLowerCase(java.util.Locale.ROOT));
		}
		MasterModelTest.check("no two fields of the table share a name, case-insensitively: " + fieldNames, new java.util.HashSet<String>(fieldNames).size() == fieldNames.size());
		boolean found = false;
		for (final MasterModel.FieldInfo field : result.fields)
		{
			if ("SalesforceSample".equals(field.entityName) && "Id".equals(field.stagingField))
			{
				found = true;
				MasterModelTest.check("FieldInfo maps staging Id to master Id_src", "Id_src".equals(field.masterField));
			}
		}
		MasterModelTest.check("a FieldInfo entry exists for the renamed attribute", found);
		MasterModelTest.check("a note names the attribute, the master field and the colliding field",
			MasterModelTest.anyContains(result.notes, "SalesforceSample.Id: promoted as master field Id_src") && MasterModelTest.anyContains(result.notes, "'id' field"));
		MasterModelTest.check("no note for the ordinary attribute", !MasterModelTest.anyContains(result.notes, "SalesforceSample.Name"));
	}

	/** A renamed attribute that is a reference still gets its {@code <attribute>Ref} foreign key, and a renamed STRING attribute is a display candidate under its MASTER name. */
	private static void reservedAttributeName_referenceAndDisplayCandidateUseTheMasterName()
	{
		final MasterModel.EntityInfo parent = MasterModelTest.entity("Parent", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255), MasterModelTest.attribute("code", "STRING", 10, null)));
		final MasterModel.EntityInfo child = MasterModelTest.entity("Child", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255),
				MasterModelTest.reference("id", "Parent", 40), MasterModelTest.attribute("sourceid", "STRING", 30, null)));
		final MasterModel.Result result = MasterModel.build(Arrays.asList(parent, child));
		final JsonNode table = MasterModelTest.tableNode(result.tree, "/root/Child");
		MasterModelTest.check("the lower-case reference attribute 'id' is promoted as id_src", !MasterModelTest.fieldNode(table, "id_src").isMissingNode());
		MasterModelTest.check("its foreign key field is still idRef, targeting Parent", MasterModelTest.hasForeignKeyField(result.tree, "/root/Child", "idRef", "/root/Parent"));
		MasterModelTest.check("ForeignKeyInfo still names the ATTRIBUTE (id), not the renamed field",
			result.foreignKeys.size() == 1 && "id".equals(result.foreignKeys.get(0).attributeName) && "idRef".equals(result.foreignKeys.get(0).fieldName));
		MasterModelTest.check("'sourceid' (differs from the key attribute sourceId by case only) is promoted as sourceid_src",
			!MasterModelTest.fieldNode(table, "sourceid_src").isMissingNode() && !MasterModelTest.fieldNode(table, "sourceId").isMissingNode());
		MasterModelTest.check("the default label picks the renamed STRING field under its master name (the only non-reference candidate)",
			"${./sourceid_src}".equals(result.defaultLabelByTable.get("/root/Child")));
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static MasterModel.Result oneEntityResult()
	{
		final MasterModel.EntityInfo widget = MasterModelTest.entity("Widget", Collections.<String>emptyList(),
			Arrays.asList(MasterModelTest.technical("sourceSystem", 40), MasterModelTest.technical("sourceId", 255), MasterModelTest.attribute("name", "STRING", 80, null)));
		return MasterModel.build(Collections.singletonList(widget));
	}

	private static MasterModel.EntityInfo entity(final String pName, final List<String> pMemberEntities, final List<MasterModel.AttributeInfo> pAttributes)
	{
		return new MasterModel.EntityInfo(pName, pName, "ACCEPTED", pAttributes, pMemberEntities);
	}

	private static MasterModel.AttributeInfo technical(final String pName, final int pMaxLength)
	{
		return new MasterModel.AttributeInfo(pName, "sourceSystem".equals(pName) ? "Source system" : "Source id", "STRING", Integer.valueOf(pMaxLength), true, null,
			"ACCEPTED");
	}

	private static MasterModel.AttributeInfo attribute(final String pName, final String pDataType, final int pMaxLength, final String pReferenceEntity)
	{
		return new MasterModel.AttributeInfo(pName, pName, pDataType, Integer.valueOf(pMaxLength), false, pReferenceEntity, "ACCEPTED");
	}

	private static MasterModel.AttributeInfo reference(final String pName, final String pReferenceEntity, final int pMaxLength)
	{
		return new MasterModel.AttributeInfo(pName, pName, "STRING", Integer.valueOf(pMaxLength), false, pReferenceEntity, "ACCEPTED");
	}

	// ---------------------------------------------------------------------------------------------------------- json

	private static List<String> tableNames(final JsonNode pTree)
	{
		final List<String> out = new ArrayList<String>();
		for (final JsonNode table : pTree.path("tables"))
		{
			out.add(table.path("path").asText());
		}
		return out;
	}

	private static JsonNode tableNode(final JsonNode pTree, final String pPath)
	{
		for (final JsonNode table : pTree.path("tables"))
		{
			if (pPath.equals(table.path("path").asText()))
			{
				return table;
			}
		}
		throw new AssertionError("table not found: " + pPath);
	}

	private static JsonNode fieldNode(final JsonNode pTable, final String pName)
	{
		for (final JsonNode fieldNode : pTable.path("fields"))
		{
			final JsonNode field = fieldNode.path("field");
			if (pName.equals(field.path("name").asText()))
			{
				return field;
			}
		}
		return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
	}

	private static boolean hasForeignKeyField(final JsonNode pTree, final String pTablePath, final String pFieldName, final String pTargetTablePath)
	{
		final JsonNode field = MasterModelTest.fieldNode(MasterModelTest.tableNode(pTree, pTablePath), pFieldName);
		return field.path("isForeignKey").asBoolean(false) && pTargetTablePath.equals(field.path("foreignKeyTargetPath").asText());
	}

	private static boolean hasOp(final com.fasterxml.jackson.databind.node.ArrayNode pOperations, final String pOp, final String pArgumentName, final String pArgumentValue)
	{
		for (final JsonNode op : pOperations)
		{
			if (pOp.equals(op.path("op").asText()) && pArgumentValue.equals(op.path("arguments").path(pArgumentName).asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasSetElementPropertyOp(final com.fasterxml.jackson.databind.node.ArrayNode pOperations, final String pElementPath, final String pProperty,
		final String pValue)
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

	/** Same as {@link #hasSetElementPropertyOp}, but matching on the property name alone - for asserting that a property is (or is not) set ANYWHERE, regardless of which element or value. */
	private static boolean hasAnySetElementPropertyOp(final com.fasterxml.jackson.databind.node.ArrayNode pOperations, final String pProperty)
	{
		for (final JsonNode op : pOperations)
		{
			if ("set_element_property".equals(op.path("op").asText()) && pProperty.equals(op.path("arguments").path("property").asText()))
			{
				return true;
			}
		}
		return false;
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

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
