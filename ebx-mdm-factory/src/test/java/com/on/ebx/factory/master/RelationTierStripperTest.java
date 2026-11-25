package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free: run the main, a failure throws. Covers {@link RelationTierStripper} (lot V2-B, 2026-09-30,
 * `docs/MATCHING.md` sec 47) on the real Vol100k Personne plan (four tiers, P3/P4 compare Adresse fields through
 * the parent relation - the plan whose simulation failed live on 2026-09-29).
 */
public final class RelationTierStripperTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** MatchPlan 30 of project Vol100k (read 2026-09-30), trimmed of survivorship/evidence. */
	public static final String VOL100K_PERSONNE = "{\"decisionTree\":{\"root\":\"P1\",\"nodes\":["
		+ "{\"id\":\"P1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"P2\"},"
		+ "{\"id\":\"P2\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":["
		+ "{\"field\":\"lastName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":5,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"},"
		+ "{\"field\":\"firstName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":4,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"},"
		+ "{\"field\":\"phoneE164\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":3,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"}],\"onTrue\":\"P3\",\"onFalse\":\"P4\"},"
		+ "{\"id\":\"P3\",\"function\":\"AVERAGE\",\"minimumScore\":76,\"fields\":["
		+ "{\"field\":\"street\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"dataType\":\"STRING\",\"weight\":3,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"},"
		+ "{\"field\":\"houseNumber\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"dataType\":\"STRING\",\"weight\":2,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"},"
		+ "{\"field\":\"postalCode\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"dataType\":\"STRING\",\"weight\":2,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"},"
		+ "{\"field\":\"city\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"dataType\":\"STRING\",\"weight\":1,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"},"
		+ "{\"id\":\"P4\",\"function\":\"ALL_FIELDS\",\"fields\":["
		+ "{\"field\":\"street\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"dataType\":\"STRING\",\"minimumScore\":90,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"},"
		+ "{\"field\":\"houseNumber\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"dataType\":\"STRING\",\"minimumScore\":100,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"},"
		+ "{\"field\":\"postalCode\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"dataType\":\"STRING\",\"minimumScore\":100,\"bothNull\":\"DO_NOTHING\",\"oneNull\":\"DO_NOTHING\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},"
		+ "\"clusteringFields\":[\"emailKey\",\"lastNameKey\",\"phoneE164\"]}";

	public static void main(final String[] pArgs) throws Exception
	{
		RelationTierStripperTest.hasRelationFields_vol100kPlan_true();
		RelationTierStripperTest.hasRelationFields_plainPlan_false();
		RelationTierStripperTest.strip_vol100kPlan_keepsOnlyTheEmailTier_relationTiersFallToNotMatched();
		RelationTierStripperTest.strip_vol100kPlan_namesEveryStrippedTierWithItsFields();
		RelationTierStripperTest.strip_doesNotMutateItsInput();
		RelationTierStripperTest.strip_plainPlan_unchanged();
		RelationTierStripperTest.strip_relationTierBetweenTwoOrdinaryOnes_fallsThroughToTheNextTier();
		RelationTierStripperTest.strip_rootIsARelationTier_followsOnFalseToTheNextTier();
		RelationTierStripperTest.strip_everyTierIsRelation_nothingLeft();
		RelationTierStripperTest.strip_legacyTiers_becomeADecisionTree();
		RelationTierStripperTest.strip_resultTranslatesToAPolicyWithoutAnyChildField();
		System.out.println("RelationTierStripperTest OK");
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return RelationTierStripperTest.MAPPER.readTree(pJson);
	}

	private static void hasRelationFields_vol100kPlan_true() throws Exception
	{
		RelationTierStripperTest.check("P3/P4 compare Adresse fields", RelationTierStripper.hasRelationFields(RelationTierStripperTest.parse(RelationTierStripperTest.VOL100K_PERSONNE)));
	}

	private static void hasRelationFields_plainPlan_false() throws Exception
	{
		final JsonNode plain = RelationTierStripperTest.parse("{\"decisionTree\":{\"root\":\"A\",\"nodes\":[{\"id\":\"A\",\"function\":\"ALL_FIELDS\",\"fields\":["
			+ "{\"field\":\"nameKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}");
		RelationTierStripperTest.check("no child field", !RelationTierStripper.hasRelationFields(plain));
		RelationTierStripperTest.check("no plan at all", !RelationTierStripper.hasRelationFields(RelationTierStripperTest.MAPPER.createObjectNode()));
	}

	/** P3 and P4 fall to NOT_MATCHED, which leaves P2 with the same target on both branches: P2 decides nothing and goes, P1 falls straight to NOT_MATCHED. */
	private static void strip_vol100kPlan_keepsOnlyTheEmailTier_relationTiersFallToNotMatched() throws Exception
	{
		final RelationTierStripper.Result result = RelationTierStripper.strip(RelationTierStripperTest.parse(RelationTierStripperTest.VOL100K_PERSONNE));
		RelationTierStripperTest.check("something is left", !result.nothingLeft);
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(result.requirements);
		RelationTierStripperTest.check("one node left: " + plan.nodes.size(), plan.nodes.size() == 1);
		final DecisionTreePlan.Node root = plan.byId(plan.root);
		RelationTierStripperTest.check("the email tier is the root", root != null && "P1".equals(root.id));
		RelationTierStripperTest.check("email match -> SUSPECTED", DecisionTreePlan.LEAF_SUSPECTED.equals(root.onTrue));
		RelationTierStripperTest.check("email differs -> NOT_MATCHED (the name tier that only led to child tiers is gone)", DecisionTreePlan.LEAF_NOT_MATCHED.equals(root.onFalse));
		RelationTierStripperTest.check("the stripped plan is structurally valid", DecisionTreePlan.validate(plan).ok);
		RelationTierStripperTest.check("clustering fields kept", result.requirements.path("clusteringFields").size() == 3);
	}

	private static void strip_vol100kPlan_namesEveryStrippedTierWithItsFields() throws Exception
	{
		final RelationTierStripper.Result result = RelationTierStripper.strip(RelationTierStripperTest.parse(RelationTierStripperTest.VOL100K_PERSONNE));
		RelationTierStripperTest.check("two tiers skipped: " + result.skippedTiers, result.skippedTiers.size() == 2);
		RelationTierStripperTest.check("P3 named with its fields: " + result.skippedTiers.get(0),
			"P3 (Adresse.street, Adresse.houseNumber, Adresse.postalCode, Adresse.city)".equals(result.skippedTiers.get(0)));
		RelationTierStripperTest.check("P4 named: " + result.skippedTiers.get(1), "P4 (Adresse.street, Adresse.houseNumber, Adresse.postalCode)".equals(result.skippedTiers.get(1)));
	}

	private static void strip_doesNotMutateItsInput() throws Exception
	{
		final JsonNode original = RelationTierStripperTest.parse(RelationTierStripperTest.VOL100K_PERSONNE);
		final String before = original.toString();
		RelationTierStripper.strip(original);
		RelationTierStripperTest.check("input untouched (the caller reads the full plan again later)", before.equals(original.toString()));
	}

	private static void strip_plainPlan_unchanged() throws Exception
	{
		final JsonNode plain = RelationTierStripperTest.parse("{\"decisionTree\":{\"root\":\"A\",\"nodes\":[{\"id\":\"A\",\"function\":\"ALL_FIELDS\",\"fields\":["
			+ "{\"field\":\"nameKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}");
		final RelationTierStripper.Result result = RelationTierStripper.strip(plain);
		RelationTierStripperTest.check("nothing skipped", result.skippedTiers.isEmpty() && !result.nothingLeft);
		RelationTierStripperTest.check("the requirements come back as they were", result.requirements == plain);
	}

	/** A -> (true) SUSPECTED / (false) R -> (true) SUSPECTED / (false) B -> SUSPECTED / NOT_MATCHED: without R, A's false branch reaches B. */
	private static void strip_relationTierBetweenTwoOrdinaryOnes_fallsThroughToTheNextTier() throws Exception
	{
		final JsonNode requirements = RelationTierStripperTest.parse("{\"decisionTree\":{\"root\":\"A\",\"nodes\":["
			+ "{\"id\":\"A\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"nameKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"R\"},"
			+ "{\"id\":\"R\",\"label\":\"same address\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"city\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"B\"},"
			+ "{\"id\":\"B\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"phoneE164\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}");
		final RelationTierStripper.Result result = RelationTierStripper.strip(requirements);
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(result.requirements);
		RelationTierStripperTest.check("A and B are left", plan.nodes.size() == 2 && plan.byId("A") != null && plan.byId("B") != null && plan.byId("R") == null);
		RelationTierStripperTest.check("A's false branch now reaches B", "B".equals(plan.byId("A").onFalse));
		RelationTierStripperTest.check("the label names the skipped tier: " + result.skippedTiers, "same address (Adresse.city)".equals(result.skippedTiers.get(0)));
		RelationTierStripperTest.check("valid", DecisionTreePlan.validate(plan).ok);
	}

	private static void strip_rootIsARelationTier_followsOnFalseToTheNextTier() throws Exception
	{
		final JsonNode requirements = RelationTierStripperTest.parse("{\"decisionTree\":{\"root\":\"R\",\"nodes\":["
			+ "{\"id\":\"R\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"city\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"B\"},"
			+ "{\"id\":\"B\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"phoneE164\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}");
		final RelationTierStripper.Result result = RelationTierStripper.strip(requirements);
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(result.requirements);
		RelationTierStripperTest.check("the root is now B", "B".equals(plan.root) && plan.nodes.size() == 1);
	}

	private static void strip_everyTierIsRelation_nothingLeft() throws Exception
	{
		final JsonNode requirements = RelationTierStripperTest.parse("{\"decisionTree\":{\"root\":\"R\",\"nodes\":["
			+ "{\"id\":\"R\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"city\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}");
		final RelationTierStripper.Result result = RelationTierStripper.strip(requirements);
		RelationTierStripperTest.check("nothing to simulate", result.nothingLeft);
		RelationTierStripperTest.check("the skipped tier is still named", result.skippedTiers.size() == 1);
	}

	/** A pre-2026-09-22 plan (tiers[]) carries no relation field: hasRelationFields is false, strip returns it untouched. */
	private static void strip_legacyTiers_becomeADecisionTree() throws Exception
	{
		final JsonNode legacy = RelationTierStripperTest.parse("{\"tiers\":[{\"type\":\"EXACT\",\"outcome\":\"SUSPECTED\",\"fields\":[{\"field\":\"nameKey\",\"algorithm\":\"ebx-addon-mame@exact\"}]}]}");
		RelationTierStripperTest.check("no relation field in a tiers[] plan", !RelationTierStripper.hasRelationFields(legacy));
		RelationTierStripperTest.check("returned untouched", RelationTierStripper.strip(legacy).requirements == legacy);
	}

	/** The point of the whole class: the stripped plan goes through the ordinary translation and no child field name reaches the add-on. */
	private static void strip_resultTranslatesToAPolicyWithoutAnyChildField() throws Exception
	{
		final RelationTierStripper.Result result = RelationTierStripper.strip(RelationTierStripperTest.parse(RelationTierStripperTest.VOL100K_PERSONNE));
		final JsonNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Personne__preview", result.requirements, false);
		for (final JsonNode field : policy.path("fields"))
		{
			final String name = field.path("field").asText();
			RelationTierStripperTest.check("no /street & co in the policy fields: " + name, !"/street".equals(name) && !"/houseNumber".equals(name) && !"/postalCode".equals(name) && !"/city".equals(name));
		}
		RelationTierStripperTest.check("the email tier survives translation", "/emailKey".equals(policy.path("decisionTree").path("fields").path(0).path("field").asText()));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
