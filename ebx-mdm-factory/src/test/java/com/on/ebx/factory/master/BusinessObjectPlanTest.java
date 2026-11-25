package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws.
 * <p>
 * Lot W13 (2026-10-12, {@code docs/MATCHING.md} sect;33): the pure decisions of the business objects of a master model - what is declared for which parent
 * ({@link BusinessObjectPlan#plan}), the host field names, the idempotence rule of the master generator ({@link BusinessObjectPlan#reconcile}), the answers
 * read from the {@code data-model} and {@code match-merge} servers (shapes measured on the scratch model W13BO), and the route the matching configurator takes
 * ({@link BusinessObjectPlan#route}): business object present, relation plan without one (coded refusal), plain plan without one (table tools).
 */
public final class BusinessObjectPlanTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		BusinessObjectPlanTest.masterWithoutChild_declaresNothing();
		BusinessObjectPlanTest.parentWithOneChild_oneObjectNamedAfterTheParent();
		BusinessObjectPlanTest.selfReference_neverARelationship();
		BusinessObjectPlanTest.twoForeignKeysFromOneChild_oneRelationship_otherListed();
		BusinessObjectPlanTest.hostFieldName_pluralOfTheChild_neverClashing();
		BusinessObjectPlanTest.reconcile_freshModel_writesEverythingOnce();
		BusinessObjectPlanTest.reconcile_secondGeneration_writesNothing();
		BusinessObjectPlanTest.reconcile_halfDone_resumesAtTheMissingWrite();
		BusinessObjectPlanTest.reconcile_hostNameTakenByAnotherField_picksAnotherName();
		BusinessObjectPlanTest.reconcile_existingAssociationOverTheSameKey_reusedWhateverItsName();
		BusinessObjectPlanTest.readExisting_w13boShapes();
		BusinessObjectPlanTest.failureOf_refusedAndNotFound();
		BusinessObjectPlanTest.candidateMainTables_ownThenParents();
		BusinessObjectPlanTest.isDeclared_andMemberReady_matchTheMeasuredAnswers();
		BusinessObjectPlanTest.route_businessObjectPresent_usesTheBusinessObjectTools();
		BusinessObjectPlanTest.route_relationPlanWithoutBusinessObject_refused();
		BusinessObjectPlanTest.route_plainPlanWithoutBusinessObject_tableTools();
		BusinessObjectPlanTest.businessObjectTable_neverApplied_alignmentSkipped();
		System.out.println("BusinessObjectPlanTest OK");
	}

	// ------------------------------------------------------------------------------------------------- fixtures

	private static MasterModel.EntityInfo entity(final String pName, final MasterModel.AttributeInfo... pExtra)
	{
		final java.util.ArrayList<MasterModel.AttributeInfo> attributes = new java.util.ArrayList<MasterModel.AttributeInfo>();
		attributes.add(new MasterModel.AttributeInfo("sourceSystem", "Source system", "STRING", Integer.valueOf(40), true, null, "ACCEPTED"));
		attributes.add(new MasterModel.AttributeInfo("sourceId", "Source id", "STRING", Integer.valueOf(255), true, null, "ACCEPTED"));
		attributes.add(new MasterModel.AttributeInfo("name", "name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED"));
		attributes.addAll(Arrays.asList(pExtra));
		return new MasterModel.EntityInfo(pName, pName, "ACCEPTED", attributes, Collections.<String>emptyList());
	}

	private static MasterModel.AttributeInfo reference(final String pName, final String pEntity)
	{
		return new MasterModel.AttributeInfo(pName, pName, "STRING", Integer.valueOf(40), false, pEntity, "ACCEPTED");
	}

	private static MasterModel.Result personneAdresse()
	{
		return MasterModel.build(Arrays.asList(BusinessObjectPlanTest.entity("Adresse", BusinessObjectPlanTest.reference("personneId", "Personne")), BusinessObjectPlanTest.entity("Personne")));
	}

	private static String fkOfAdresse(final MasterModel.Result pResult)
	{
		for (final MasterModel.ForeignKeyInfo fk : pResult.foreignKeys)
		{
			if ("Adresse".equals(fk.entityName))
			{
				return fk.fieldName;
			}
		}
		throw new AssertionError("no foreign key of Adresse in " + pResult.foreignKeys.size() + " foreign keys");
	}

	private static Set<String> set(final String... pValues)
	{
		return new LinkedHashSet<String>(Arrays.asList(pValues));
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return BusinessObjectPlanTest.MAPPER.readTree(pText);
	}

	// ----------------------------------------------------------------------------------------------- the plan

	private static void masterWithoutChild_declaresNothing()
	{
		final MasterModel.Result result = MasterModel.build(Arrays.asList(BusinessObjectPlanTest.entity("Company")));
		check("a master with no child entity declares nothing", BusinessObjectPlan.plan(result).isEmpty());
		check("and no table is a business object candidate", BusinessObjectPlan.candidateMainTables(result, "Company").isEmpty());
	}

	private static void parentWithOneChild_oneObjectNamedAfterTheParent()
	{
		final MasterModel.Result result = BusinessObjectPlanTest.personneAdresse();
		final List<BusinessObjectPlan.Declaration> plan = BusinessObjectPlan.plan(result);
		check("one business object for the parent only: " + plan.size(), plan.size() == 1);
		final BusinessObjectPlan.Declaration declaration = plan.get(0);
		check("named <Parent>BO, main table the parent: " + declaration.name + " / " + declaration.mainTablePath,
			"PersonneBO".equals(declaration.name) && "/root/Personne".equals(declaration.mainTablePath));
		check("one relationship, over the child's foreign key", declaration.relationships.size() == 1 && "/root/Adresse".equals(declaration.relationships.get(0).childTablePath)
			&& declaration.relationships.get(0).fkFieldPath().equals("/root/Adresse/" + BusinessObjectPlanTest.fkOfAdresse(result)));
	}

	private static void selfReference_neverARelationship()
	{
		final MasterModel.Result result = MasterModel.build(Arrays.asList(BusinessObjectPlanTest.entity("Account", BusinessObjectPlanTest.reference("parentId", "Account"))));
		check("a self-reference is not a child (a recursive business object is refused at publication)", BusinessObjectPlan.plan(result).isEmpty());
	}

	private static void twoForeignKeysFromOneChild_oneRelationship_otherListed()
	{
		final MasterModel.Result result = MasterModel.build(Arrays.asList(BusinessObjectPlanTest.entity("Personne"),
			BusinessObjectPlanTest.entity("Adresse", BusinessObjectPlanTest.reference("billingId", "Personne"), BusinessObjectPlanTest.reference("shippingId", "Personne"))));
		final BusinessObjectPlan.Declaration declaration = BusinessObjectPlan.plan(result).get(0);
		check("one relationship per child table", declaration.relationships.size() == 1);
		check("the second foreign key is reported, not silently dropped: " + declaration.skippedForeignKeys, declaration.skippedForeignKeys.size() == 1);
	}

	private static void hostFieldName_pluralOfTheChild_neverClashing()
	{
		check("Adresse -> adresses (the name measured on W13BO)", "adresses".equals(BusinessObjectPlan.pickHostFieldName("Adresse", Collections.<String>emptySet())));
		check("a name already ending in s is not pluralised again: Address -> address", "address".equals(BusinessObjectPlan.pickHostFieldName("Address", Collections.<String>emptySet())));
		check("a taken name gets the _assoc suffix", "adresses_assoc".equals(BusinessObjectPlan.pickHostFieldName("Adresse", BusinessObjectPlanTest.set("adresses"))));
		check("and then a counter", "adresses_assoc2".equals(BusinessObjectPlan.pickHostFieldName("Adresse", BusinessObjectPlanTest.set("adresses", "adresses_assoc"))));
	}

	// ------------------------------------------------------------------------------------------- idempotence

	private static BusinessObjectPlan.Declaration declaration()
	{
		return BusinessObjectPlan.plan(BusinessObjectPlanTest.personneAdresse()).get(0);
	}

	private static String fk()
	{
		return "/root/Adresse/" + BusinessObjectPlanTest.fkOfAdresse(BusinessObjectPlanTest.personneAdresse());
	}

	private static void reconcile_freshModel_writesEverythingOnce()
	{
		final BusinessObjectPlan.Steps steps = BusinessObjectPlan.reconcile(BusinessObjectPlanTest.declaration(),
			BusinessObjectPlan.ExistingState.empty(BusinessObjectPlanTest.set("id", "sourceSystem", "sourceId", "name")));
		check("extension enabled, object created", steps.enableExtension && steps.createBusinessObject && "PersonneBO".equals(steps.businessObjectName));
		final BusinessObjectPlan.RelationshipStep step = steps.relationships.get(0);
		check("host field adresses created, associated, attached as a relationship", "adresses".equals(step.hostFieldName) && step.createHostField && step.setAssociation
			&& step.addRelationship && "/root/Personne/adresses".equals(step.relationshipPath));
		check("something to do", !steps.nothingToDo());
	}

	private static void reconcile_secondGeneration_writesNothing()
	{
		final BusinessObjectPlan.ExistingState existing = new BusinessObjectPlan.ExistingState(true, BusinessObjectPlanTest.set("id", "name", "adresses"),
			Collections.singletonMap("adresses", BusinessObjectPlanTest.fk()), "PersonneBO", BusinessObjectPlanTest.set("/root/Personne/adresses"));
		check("everything in place: nothing to write", BusinessObjectPlan.reconcile(BusinessObjectPlanTest.declaration(), existing).nothingToDo());
	}

	private static void reconcile_halfDone_resumesAtTheMissingWrite()
	{
		// the host field exists (create_field ran), the association was refused: resume at set_association, no second create_field
		final BusinessObjectPlan.ExistingState existing = new BusinessObjectPlan.ExistingState(true, BusinessObjectPlanTest.set("id", "adresses"),
			Collections.<String, String>emptyMap(), null, null);
		final BusinessObjectPlan.Steps steps = BusinessObjectPlan.reconcile(BusinessObjectPlanTest.declaration(), existing);
		final BusinessObjectPlan.RelationshipStep step = steps.relationships.get(0);
		check("no extension write, the object is still to create", !steps.enableExtension && steps.createBusinessObject);
		check("host name reused, field not created again, association still to set: " + step.hostFieldName, "adresses".equals(step.hostFieldName) && !step.createHostField && step.setAssociation);
		// the object exists but its relationship was never added
		final BusinessObjectPlan.ExistingState noRelationship = new BusinessObjectPlan.ExistingState(true, BusinessObjectPlanTest.set("id", "adresses"),
			Collections.singletonMap("adresses", BusinessObjectPlanTest.fk()), "PersonneBO", Collections.<String>emptySet());
		final BusinessObjectPlan.RelationshipStep late = BusinessObjectPlan.reconcile(BusinessObjectPlanTest.declaration(), noRelationship).relationships.get(0);
		check("only the relationship is added", !late.createHostField && !late.setAssociation && late.addRelationship);
	}

	private static void reconcile_hostNameTakenByAnotherField_picksAnotherName()
	{
		final BusinessObjectPlan.Steps steps = BusinessObjectPlan.reconcile(BusinessObjectPlanTest.declaration(),
			new BusinessObjectPlan.ExistingState(true, BusinessObjectPlanTest.set("id", "adresses"), Collections.singletonMap("adresses", "/root/Other/foreignKey"), null, null));
		// a field "adresses" that is an association over ANOTHER key is not ours
		check("another association's name is never reused: " + steps.relationships.get(0).hostFieldName, "adresses_assoc".equals(steps.relationships.get(0).hostFieldName)
			&& steps.relationships.get(0).createHostField);
	}

	private static void reconcile_existingAssociationOverTheSameKey_reusedWhateverItsName()
	{
		final BusinessObjectPlan.Steps steps = BusinessObjectPlan.reconcile(BusinessObjectPlanTest.declaration(),
			new BusinessObjectPlan.ExistingState(true, BusinessObjectPlanTest.set("id", "addressList"), Collections.singletonMap("addressList", BusinessObjectPlanTest.fk()), "MyBO",
				BusinessObjectPlanTest.set("/root/Personne/addressList")));
		check("a hand-made association and object are kept as they are", steps.nothingToDo() && "MyBO".equals(steps.businessObjectName)
			&& "addressList".equals(steps.relationships.get(0).hostFieldName));
	}

	// ------------------------------------------------------------------------------------------ server answers

	private static void readExisting_w13boShapes() throws Exception
	{
		final JsonNode objects = BusinessObjectPlanTest.json("{\"dataModelName\":\"W13BO\",\"found\":true,\"businessObjectsEnabled\":true,\"businessObjects\":[{\"documentation\":{},"
			+ "\"mainTable\":\"/root/Personne\",\"name\":\"PersonneBO\",\"relationships\":[{\"children\":[],\"id\":2,\"relationshipPath\":\"/root/Personne/adresses\"}]}]}");
		final JsonNode describe = BusinessObjectPlanTest.json("{\"children\":[{\"name\":\"id\",\"isPrimaryKey\":true},{\"name\":\"lastName\"},"
			+ "{\"association\":{\"fieldToSource\":\"/root/Adresse/personneRef\"},\"associationType\":\"tableRefInverse\",\"isAssociation\":true,\"name\":\"adresses\",\"type\":\"string\"}],\"found\":true}");
		final BusinessObjectPlan.ExistingState existing = BusinessObjectPlan.readExisting(objects, describe, "/root/Personne");
		check("extension enabled, object found by main table", existing.extensionEnabled && "PersonneBO".equals(existing.businessObjectName));
		check("relationship path read", existing.relationshipPaths.contains("/root/Personne/adresses"));
		check("fields and the association's key read", existing.parentFieldNames.containsAll(BusinessObjectPlanTest.set("id", "lastName", "adresses"))
			&& "/root/Adresse/personneRef".equals(existing.associationFkByHostField.get("adresses")));
		final BusinessObjectPlan.ExistingState none = BusinessObjectPlan.readExisting(null, null, "/root/Personne");
		check("unreadable answers read as nothing declared", !none.extensionEnabled && none.businessObjectName == null && none.parentFieldNames.isEmpty());
		final JsonNode other = BusinessObjectPlanTest.json("{\"businessObjectsEnabled\":true,\"businessObjects\":[{\"mainTable\":\"/root/Other\",\"name\":\"OtherBO\",\"relationships\":[]}]}");
		check("an object on another main table is not ours", BusinessObjectPlan.readExisting(other, describe, "/root/Personne").businessObjectName == null);
	}

	private static void failureOf_refusedAndNotFound() throws Exception
	{
		check("a plain success is not a failure", BusinessObjectPlan.failureOf(BusinessObjectPlanTest.json("{\"found\":true,\"alreadyExists\":true}")) == null);
		check("null answer is not a failure", BusinessObjectPlan.failureOf(null) == null);
		final String refused = BusinessObjectPlan.failureOf(BusinessObjectPlanTest.json("{\"refused\":true,\"code\":\"DUPLICATE\",\"message\":\"business object PersonneBO already exists\"}"));
		check("refused: code and message: " + refused, refused != null && refused.contains("DUPLICATE") && refused.contains("already exists"));
		final String notFound = BusinessObjectPlan.failureOf(BusinessObjectPlanTest.json("{\"found\":false,\"code\":\"BUSINESS_OBJECT_NOT_FOUND\",\"message\":null}"));
		check("found:false is a failure: " + notFound, notFound != null && notFound.contains("BUSINESS_OBJECT_NOT_FOUND"));
	}

	private static void candidateMainTables_ownThenParents()
	{
		final MasterModel.Result result = BusinessObjectPlanTest.personneAdresse();
		check("Personne: its own table", BusinessObjectPlan.candidateMainTables(result, "Personne").equals(Arrays.asList("/root/Personne")));
		check("Adresse (a child only): the table of its parent", BusinessObjectPlan.candidateMainTables(result, "Adresse").equals(Arrays.asList("/root/Personne")));
		final MasterModel.Result chain = MasterModel.build(Arrays.asList(BusinessObjectPlanTest.entity("A"), BusinessObjectPlanTest.entity("B", BusinessObjectPlanTest.reference("aId", "A")),
			BusinessObjectPlanTest.entity("C", BusinessObjectPlanTest.reference("bId", "B"))));
		check("B (parent of C, child of A): its own table first, then A's: " + BusinessObjectPlan.candidateMainTables(chain, "B"),
			BusinessObjectPlan.candidateMainTables(chain, "B").equals(Arrays.asList("/root/B", "/root/A")));
		check("firstDeclared keeps that order", "/root/A".equals(BusinessObjectPlan.firstDeclared(Arrays.asList("/root/B", "/root/A"), BusinessObjectPlanTest.set("/root/A")))
			&& BusinessObjectPlan.firstDeclared(Arrays.asList("/root/B"), BusinessObjectPlanTest.set("/root/A")) == null);
	}

	private static void isDeclared_andMemberReady_matchTheMeasuredAnswers() throws Exception
	{
		check("success: declared", BusinessObjectPlan.isDeclared(true, null));
		check("CONFIG_NOT_FOUND (declared, nothing configured yet): declared", BusinessObjectPlan.isDeclared(false, "CONFIG_NOT_FOUND"));
		check("BUSINESS_OBJECT_NOT_FOUND: not declared", !BusinessObjectPlan.isDeclared(false, "BUSINESS_OBJECT_NOT_FOUND"));
		check("AMBIGUOUS_TABLE left unsettled: not declared", !BusinessObjectPlan.isDeclared(false, "AMBIGUOUS_TABLE"));
		final JsonNode status = BusinessObjectPlanTest.json("{\"data\":{\"mainTable\":\"/root/Personne\",\"overallState\":\"PARTIAL\",\"members\":["
			+ "{\"table\":\"/root/Personne\",\"state\":\"ENABLED\",\"published\":true,\"paused\":false,\"metadatasetExists\":true},"
			+ "{\"table\":\"/root/Adresse\",\"state\":\"ENABLED\",\"published\":true,\"paused\":false,\"metadatasetExists\":false}]}}");
		check("the main table reads ready", BusinessObjectPlan.memberReady(status, "/root/Personne"));
		check("a member ENABLED without its metadataset is not ready", !BusinessObjectPlan.memberReady(status, "/root/Adresse"));
		check("a table that is not listed (no configuration) is not ready", !BusinessObjectPlan.memberReady(status, "/root/Other"));
		check("members described for a refusal", BusinessObjectPlan.describeMembers(status).contains("/root/Adresse ENABLED (no metadataset)"));
	}

	// -------------------------------------------------------------------------------------- configurator route

	private static void route_businessObjectPresent_usesTheBusinessObjectTools()
	{
		check("relation plan, own object declared: business-object tools", BusinessObjectPlan.route(true, true, "/root/Personne") == BusinessObjectPlan.Route.BUSINESS_OBJECT);
		check("plain plan on a table that belongs to an object: business-object tools too (activate_matching is refused on a member)",
			BusinessObjectPlan.route(false, false, "/root/Personne") == BusinessObjectPlan.Route.BUSINESS_OBJECT);
	}

	private static void route_relationPlanWithoutBusinessObject_refused()
	{
		check("relation plan, nothing declared: coded refusal, no fallback", BusinessObjectPlan.route(true, false, null) == BusinessObjectPlan.Route.REFUSE_MISSING_BUSINESS_OBJECT);
		check("relation plan on a table that is only a member of its parent's object: refused (its own children need its own object)",
			BusinessObjectPlan.route(true, false, "/root/Parent") == BusinessObjectPlan.Route.REFUSE_MISSING_BUSINESS_OBJECT);
	}

	private static void route_plainPlanWithoutBusinessObject_tableTools()
	{
		check("plain plan, nothing declared: the table tools, as before", BusinessObjectPlan.route(false, false, null) == BusinessObjectPlan.Route.TABLE);
	}

	/** Measured on W13BO 2026-10-12: apply_matching_config is refused on a business-object member, and generate_merge_policy refuses relatedTables there. */
	private static void businessObjectTable_neverApplied_alignmentSkipped()
	{
		check("a business-object table is rewritten with the policy tools", BusinessObjectPlan.writeMode(true) == BusinessObjectPlan.WriteMode.GENERATE);
		check("and apply_matching_config is never used on it", !BusinessObjectPlan.mayUseApplyMatchingConfig(true));
		check("the related-table alignment is skipped on it", !BusinessObjectPlan.alignsRelatedTables(true));
		check("an ordinary table keeps apply_matching_config", BusinessObjectPlan.writeMode(false) == BusinessObjectPlan.WriteMode.APPLY_CONFIG && BusinessObjectPlan.mayUseApplyMatchingConfig(false));
		check("and its related-table alignment", BusinessObjectPlan.alignsRelatedTables(false));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
