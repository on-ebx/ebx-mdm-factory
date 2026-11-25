package com.on.ebx.factory.master;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws.
 * <p>
 * Covers {@code requirements.relationClusteringWeights} ({@code "<childEntity>.<field>"} -&gt; int &gt;= 0): the optional,
 * additive plan key that sets the pre-clustering weight of a relation (child-table) matching field, carried into
 * {@link MatchingPolicyTranslator.TranslationResult#relationFieldWeights} (and the weight of the {@code businessObjectField} policy field) by {@link MatchingPolicyTranslator#translate}.
 * Absent key or entry = 1, the behaviour before the key existed.
 */
public final class MatchingPolicyTranslatorRelationWeightTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		MatchingPolicyTranslatorRelationWeightTest.absentKey_everyRelationFieldWeighsZero();
		MatchingPolicyTranslatorRelationWeightTest.explicitEntries_carriedPerField_unlistedFieldKeepsZero();
		MatchingPolicyTranslatorRelationWeightTest.invalidValues_refused();
		MatchingPolicyTranslatorRelationWeightTest.policyFieldsAreNativeBusinessObjectFields();
		System.out.println("MatchingPolicyTranslatorRelationWeightTest OK");
	}

	/** Node N1: lastName (parent) + Adresse.street + Adresse.city (relations); "firstName"/"country" declared for placeholders. */
	private static ObjectNode requirements()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		node.fields.add(new DecisionTreePlan.FieldRef("lastName", "ebx-addon-mame@exact"));
		final DecisionTreePlan.FieldRef street = new DecisionTreePlan.FieldRef("street", "ebx-addon-mame@exact");
		street.via = "Adresse";
		street.dataType = "STRING";
		node.fields.add(street);
		final DecisionTreePlan.FieldRef city = new DecisionTreePlan.FieldRef("city", "ebx-addon-mame@exact");
		city.via = "Adresse";
		city.dataType = "STRING";
		node.fields.add(city);
		node.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		node.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(node);
		final ObjectNode requirements = MAPPER.createObjectNode();
		requirements.set("decisionTree", DecisionTreePlan.toJson(plan));
		requirements.putArray("clusteringFields").add("firstName").add("country");
		return requirements;
	}

	private static MatchingPolicyTranslator.TranslationResult translate(final ObjectNode pRequirements)
	{
		final Map<String, String> child = new LinkedHashMap<String, String>();
		child.put("Adresse", "/root/Adresse");
		return MatchingPolicyTranslator.translate("Personne", pRequirements, false, child);
	}

	/** The weight written for the relation field at {@code pPath} ({@code /root/Adresse/street}), failing when there is none. */
	private static int weightFor(final MatchingPolicyTranslator.TranslationResult pResult, final String pPath)
	{
		final Integer weight = pResult.relationFieldWeights.get(pPath);
		if (weight == null)
		{
			throw new AssertionError("no relation field " + pPath + " in " + pResult.relationFieldWeights);
		}
		return weight.intValue();
	}

	private static void absentKey_everyRelationFieldWeighsZero()
	{
		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslatorRelationWeightTest.translate(MatchingPolicyTranslatorRelationWeightTest.requirements());
		MatchingPolicyTranslatorRelationWeightTest.check("two relation fields", result.relationFieldWeights.size() == 2);
		for (final Map.Entry<String, Integer> relation : result.relationFieldWeights.entrySet())
		{
			MatchingPolicyTranslatorRelationWeightTest.check("absent key -> weight 0 for " + relation.getKey(), relation.getValue().intValue() == 0);
		}
		final ObjectNode requirements = MatchingPolicyTranslatorRelationWeightTest.requirements();
		requirements.putObject("relationClusteringWeights");
		MatchingPolicyTranslatorRelationWeightTest.check("empty object -> weight 0",
			MatchingPolicyTranslatorRelationWeightTest.translate(requirements).relationFieldWeights.get("/root/Adresse/street").intValue() == 0);
	}

	private static void explicitEntries_carriedPerField_unlistedFieldKeepsZero()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorRelationWeightTest.requirements();
		requirements.putObject("relationClusteringWeights").put("Adresse.street", 1).put("Adresse.city", 3).put("Adresse.unknown", 9);
		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslatorRelationWeightTest.translate(requirements);
		MatchingPolicyTranslatorRelationWeightTest.check("street weighs 1", MatchingPolicyTranslatorRelationWeightTest.weightFor(result, "/root/Adresse/street") == 1);
		MatchingPolicyTranslatorRelationWeightTest.check("city weighs 3", MatchingPolicyTranslatorRelationWeightTest.weightFor(result, "/root/Adresse/city") == 3);

		final ObjectNode onlyStreet = MatchingPolicyTranslatorRelationWeightTest.requirements();
		onlyStreet.putObject("relationClusteringWeights").put("Adresse.street", 2);
		final MatchingPolicyTranslator.TranslationResult partial = MatchingPolicyTranslatorRelationWeightTest.translate(onlyStreet);
		MatchingPolicyTranslatorRelationWeightTest.check("an entry absent from the object keeps 0",
			MatchingPolicyTranslatorRelationWeightTest.weightFor(partial, "/root/Adresse/city") == 0);
		MatchingPolicyTranslatorRelationWeightTest.check("an explicit entry above 0 is carried",
			MatchingPolicyTranslatorRelationWeightTest.weightFor(partial, "/root/Adresse/street") == 2);
	}

	private static void invalidValues_refused()
	{
		final Object[] bad = new Object[] { Integer.valueOf(-1), "2", Double.valueOf(1.5) };
		for (final Object value : bad)
		{
			final ObjectNode requirements = MatchingPolicyTranslatorRelationWeightTest.requirements();
			final ObjectNode weights = requirements.putObject("relationClusteringWeights");
			if (value instanceof Integer)
			{
				weights.put("Adresse.street", (Integer) value);
			}
			else if (value instanceof Double)
			{
				weights.put("Adresse.street", (Double) value);
			}
			else
			{
				weights.put("Adresse.street", (String) value);
			}
			boolean refused = false;
			try
			{
				MatchingPolicyTranslatorRelationWeightTest.translate(requirements);
			}
			catch (final IllegalStateException ex)
			{
				refused = ex.getMessage().contains("relationClusteringWeights");
			}
			MatchingPolicyTranslatorRelationWeightTest.check("value " + value + " refused", refused);
		}
		final ObjectNode notAnObject = MatchingPolicyTranslatorRelationWeightTest.requirements();
		notAnObject.put("relationClusteringWeights", 0);
		boolean refused = false;
		try
		{
			MatchingPolicyTranslatorRelationWeightTest.translate(notAnObject);
		}
		catch (final IllegalStateException ex)
		{
			refused = true;
		}
		MatchingPolicyTranslatorRelationWeightTest.check("a non-object key refused", refused);
		MatchingPolicyTranslatorRelationWeightTest.check("null requirements: no weights",
			MatchingPolicyTranslator.readRelationClusteringWeights(null).equals(Collections.<String, Integer>emptyMap()));
	}

	/** The policy side: a relation field is declared with businessObjectField (the absolute path) and no field, at its weight; the tree field carries both keys. */
	private static void policyFieldsAreNativeBusinessObjectFields()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorRelationWeightTest.requirements();
		requirements.putObject("relationClusteringWeights").put("Adresse.street", 4);
		final ObjectNode policy = MatchingPolicyTranslatorRelationWeightTest.translate(requirements).matchingPolicy;
		com.fasterxml.jackson.databind.JsonNode street = null;
		com.fasterxml.jackson.databind.JsonNode city = null;
		for (final com.fasterxml.jackson.databind.JsonNode field : policy.path("fields"))
		{
			if ("/root/Adresse/street".equals(field.path("businessObjectField").asText(null)))
			{
				street = field;
			}
			if ("/root/Adresse/city".equals(field.path("businessObjectField").asText(null)))
			{
				city = field;
			}
			if (field.hasNonNull("businessObjectField"))
			{
				MatchingPolicyTranslatorRelationWeightTest.check("a relation policy field has no `field`: " + field, !field.hasNonNull("field"));
				MatchingPolicyTranslatorRelationWeightTest.check("and no useRelationships/location (the server derives them): " + field,
					!field.has("useRelationships") && !field.has("location"));
			}
		}
		MatchingPolicyTranslatorRelationWeightTest.check("street declared with weight 4: " + street, street != null && street.path("weight").asInt(-1) == 4);
		MatchingPolicyTranslatorRelationWeightTest.check("city declared with the default weight 0: " + city, city != null && city.path("weight").asInt(-1) == 0);
		final com.fasterxml.jackson.databind.JsonNode treeField = policy.path("decisionTree").path("fields").get(1);
		MatchingPolicyTranslatorRelationWeightTest.check("the tree field is field + businessObjectField with the same path: " + treeField,
			"/root/Adresse/street".equals(treeField.path("field").asText(null)) && "/root/Adresse/street".equals(treeField.path("businessObjectField").asText(null)));
		MatchingPolicyTranslatorRelationWeightTest.check("a parent field of the tree keeps `field` only: " + policy.path("decisionTree").path("fields").get(0),
			!policy.path("decisionTree").path("fields").get(0).has("businessObjectField"));
		MatchingPolicyTranslatorRelationWeightTest.check("the node name is the plan's label, not the paths: " + policy.path("decisionTree").path("name").asText(),
			policy.path("decisionTree").path("name").asText().contains("Adresse.street") && !policy.path("decisionTree").path("name").asText().contains("/root"));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
