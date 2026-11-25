package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws.
 * <p>
 * 2026-10-12, add-on build 6.3.0.5-RC3 (of 2026-10-10): a decision-tree field no longer accepts {@code DO_NOTHING}
 * ({@code INVALID_PARAMS "Invalid null-value handling on a decision-tree field: DO_NOTHING"}, measured live on Vol100kM). The stored plan keeps
 * {@code DO_NOTHING} (parent confirmation, "not required" in an average); {@link MatchingPolicyTranslator#translateTreeNullHandling} turns it into
 * {@code NOT_MATCH} in the payload only, and says so.
 */
public final class MatchingPolicyTranslatorTreeNullTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final List<String> RC3_TREE = Arrays.asList("MATCH", "NOT_MATCH");
	private static final List<String> OLD_TREE = Arrays.asList("DO_NOTHING", "NOT_MATCH", "MATCH");

	public static void main(final String[] pArgs)
	{
		MatchingPolicyTranslatorTreeNullTest.allFieldsParentConfirmation_andAverageField_areTranslated();
		MatchingPolicyTranslatorTreeNullTest.theStoredPlanKeepsDoNothing_onlyThePayloadChanges();
		MatchingPolicyTranslatorTreeNullTest.aSchemaThatStillAcceptsDoNothing_leavesThePayloadUntouched();
		MatchingPolicyTranslatorTreeNullTest.anUnknownOrUselessAllowedList_translatesNothing();
		MatchingPolicyTranslatorTreeNullTest.translationIsIdempotent_andTheSummaryWordsIt();
		System.out.println("MatchingPolicyTranslatorTreeNullTest OK");
	}

	/**
	 * N1 exact e-mail (NOT_MATCH) -&gt; N2 parent confirmation ({@code personRef}, DO_NOTHING both, MATCH/NOT_MATCHED) ; N1 false -&gt; N3 AVERAGE (name weight 3
	 * NOT_MATCH, city weight 1 DO_NOTHING) -&gt; SUSPECTED / NOT_MATCHED. The measured Vol100kM shape: the reference field of an ALL_FIELDS node.
	 */
	private static ObjectNode requirements()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef email = new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact");
		email.bothNull = "NOT_MATCH";
		email.oneNull = "NOT_MATCH";
		n1.fields.add(email);
		n1.onTrue = "N2";
		n1.onFalse = "N3";
		final DecisionTreePlan.Node n2 = new DecisionTreePlan.Node("N2", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef personRef = new DecisionTreePlan.FieldRef("personRef", "ebx-addon-mame@exact");
		personRef.referenceRole = true;
		personRef.bothNull = "DO_NOTHING";
		personRef.oneNull = "DO_NOTHING";
		n2.fields.add(personRef);
		n2.onTrue = DecisionTreePlan.LEAF_MATCH;
		n2.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		final DecisionTreePlan.Node n3 = new DecisionTreePlan.Node("N3", DecisionTreePlan.FUNCTION_AVERAGE);
		n3.minimumScore = Integer.valueOf(85);
		final DecisionTreePlan.FieldRef name = new DecisionTreePlan.FieldRef("name", "ebx-addon-mame@jarowinkler");
		name.weight = Integer.valueOf(3);
		name.bothNull = "NOT_MATCH";
		name.oneNull = "NOT_MATCH";
		final DecisionTreePlan.FieldRef city = new DecisionTreePlan.FieldRef("city", "ebx-addon-mame@jarowinkler");
		city.weight = Integer.valueOf(1);
		city.bothNull = "DO_NOTHING";
		city.oneNull = "DO_NOTHING";
		n3.fields.add(name);
		n3.fields.add(city);
		n3.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n3.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		plan.nodes.add(n2);
		plan.nodes.add(n3);
		final ObjectNode requirements = MatchingPolicyTranslatorTreeNullTest.MAPPER.createObjectNode();
		requirements.set("decisionTree", DecisionTreePlan.toJson(plan));
		requirements.putArray("clusteringFields").add("emailKey");
		return requirements;
	}

	private static JsonNode findNode(final JsonNode pTree, final String pFieldName)
	{
		if (pTree == null || pTree.isMissingNode() || !pTree.has("fields"))
		{
			return null;
		}
		for (final JsonNode field : pTree.path("fields"))
		{
			if (("/" + pFieldName).equals(field.path("field").asText()))
			{
				return field;
			}
		}
		final JsonNode inTrue = MatchingPolicyTranslatorTreeNullTest.findNode(pTree.path("trueNode"), pFieldName);
		return inTrue != null ? inTrue : MatchingPolicyTranslatorTreeNullTest.findNode(pTree.path("falseNode"), pFieldName);
	}

	private static void allFieldsParentConfirmation_andAverageField_areTranslated()
	{
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Address", MatchingPolicyTranslatorTreeNullTest.requirements(), true);
		MatchingPolicyTranslatorTreeNullTest.check("before: the tree carries DO_NOTHING on personRef (the payload the RC3 add-on refused)",
			"DO_NOTHING".equals(MatchingPolicyTranslatorTreeNullTest.findNode(policy.path("decisionTree"), "personRef").path("oneNullValueHandling").asText()));
		final List<String> notes = MatchingPolicyTranslator.translateTreeNullHandling(policy, MatchingPolicyTranslatorTreeNullTest.RC3_TREE, "Address");
		MatchingPolicyTranslatorTreeNullTest.check("four values translated (personRef x2 in the ALL_FIELDS node, city x2 in the AVERAGE node): " + notes, notes.size() == 4);
		final JsonNode personRef = MatchingPolicyTranslatorTreeNullTest.findNode(policy.path("decisionTree"), "personRef");
		MatchingPolicyTranslatorTreeNullTest.check("personRef oneNull -> NOT_MATCH", "NOT_MATCH".equals(personRef.path("oneNullValueHandling").asText()));
		MatchingPolicyTranslatorTreeNullTest.check("personRef bothNull -> NOT_MATCH", "NOT_MATCH".equals(personRef.path("bothNullValueHandling").asText()));
		final JsonNode city = MatchingPolicyTranslatorTreeNullTest.findNode(policy.path("decisionTree"), "city");
		MatchingPolicyTranslatorTreeNullTest.check("AVERAGE field city oneNull -> NOT_MATCH", "NOT_MATCH".equals(city.path("oneNullValueHandling").asText()));
		MatchingPolicyTranslatorTreeNullTest.check("AVERAGE field city bothNull -> NOT_MATCH", "NOT_MATCH".equals(city.path("bothNullValueHandling").asText()));
		MatchingPolicyTranslatorTreeNullTest.check("values the tree already had are untouched",
			"NOT_MATCH".equals(MatchingPolicyTranslatorTreeNullTest.findNode(policy.path("decisionTree"), "name").path("oneNullValueHandling").asText())
				&& "NOT_MATCH".equals(MatchingPolicyTranslatorTreeNullTest.findNode(policy.path("decisionTree"), "emailKey").path("bothNullValueHandling").asText()));
		boolean allFieldsNote = false;
		boolean averageNote = false;
		for (final String note : notes)
		{
			MatchingPolicyTranslatorTreeNullTest.check("every note names the entity, the change and the reason: " + note,
				note.startsWith("Address / node ") && note.contains("DO_NOTHING -> NOT_MATCH") && note.contains("reserved for matching-policy fields"));
			if (note.contains("/personRef") && note.contains("A missing value no longer abstains") && note.contains("(All fields match)"))
			{
				allFieldsNote = true;
			}
			if (note.contains("/city") && note.contains("counts this field as 0") && note.contains("(Average)") && note.contains("minimumScore"))
			{
				averageNote = true;
			}
		}
		MatchingPolicyTranslatorTreeNullTest.check("the ALL_FIELDS note says a missing value fails the field", allFieldsNote);
		MatchingPolicyTranslatorTreeNullTest.check("the AVERAGE note says the field now counts as 0 and the threshold may need lowering", averageNote);
		// the matching-policy (pre-clustering) fields ACCEPT DO_NOTHING and are not touched
		boolean policyFieldKept = false;
		for (final JsonNode field : policy.path("fields"))
		{
			if ("/personRef".equals(field.path("field").asText()))
			{
				policyFieldKept = "DO_NOTHING".equals(field.path("oneNullValueHandling").asText()) && "DO_NOTHING".equals(field.path("bothNullValueHandling").asText());
			}
		}
		MatchingPolicyTranslatorTreeNullTest.check("matchingPolicy.fields[] keeps DO_NOTHING (the policy-field enum has it)", policyFieldKept);
	}

	private static void theStoredPlanKeepsDoNothing_onlyThePayloadChanges()
	{
		final ObjectNode requirements = MatchingPolicyTranslatorTreeNullTest.requirements();
		final String before = requirements.toString();
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Address", requirements, true);
		MatchingPolicyTranslator.translateTreeNullHandling(policy, MatchingPolicyTranslatorTreeNullTest.RC3_TREE, "Address");
		MatchingPolicyTranslatorTreeNullTest.check("the requirements document is not rewritten", before.equals(requirements.toString()));
		final DecisionTreePlan.Plan stored = DecisionTreePlan.readFrom(requirements);
		boolean found = false;
		for (final DecisionTreePlan.Node node : stored.nodes)
		{
			for (final DecisionTreePlan.FieldRef field : node.fields)
			{
				if ("personRef".equals(field.field))
				{
					found = "DO_NOTHING".equals(field.oneNull) && "DO_NOTHING".equals(field.bothNull);
				}
			}
		}
		MatchingPolicyTranslatorTreeNullTest.check("DecisionTreePlan reads DO_NOTHING back from the stored plan (dominance / candidate-filter reasoning keeps its input)", found);
		MatchingPolicyTranslatorTreeNullTest.check("and still validates", DecisionTreePlan.validate(stored).ok);
	}

	private static void aSchemaThatStillAcceptsDoNothing_leavesThePayloadUntouched()
	{
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Address", MatchingPolicyTranslatorTreeNullTest.requirements(), true);
		final String before = policy.toString();
		final List<String> notes = MatchingPolicyTranslator.translateTreeNullHandling(policy, MatchingPolicyTranslatorTreeNullTest.OLD_TREE, "Address");
		MatchingPolicyTranslatorTreeNullTest.check("no translation on the 2026-09-27 build's enum", notes.isEmpty() && before.equals(policy.toString()));
	}

	private static void anUnknownOrUselessAllowedList_translatesNothing()
	{
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Address", MatchingPolicyTranslatorTreeNullTest.requirements(), true);
		final String before = policy.toString();
		MatchingPolicyTranslatorTreeNullTest.check("schema not read (null): payload sent as stored",
			MatchingPolicyTranslator.translateTreeNullHandling(policy, null, "Address").isEmpty() && before.equals(policy.toString()));
		MatchingPolicyTranslatorTreeNullTest.check("empty list: payload sent as stored",
			MatchingPolicyTranslator.translateTreeNullHandling(policy, Collections.<String>emptyList(), "Address").isEmpty() && before.equals(policy.toString()));
		MatchingPolicyTranslatorTreeNullTest.check("a list without NOT_MATCH offers no target: payload sent as stored",
			MatchingPolicyTranslator.translateTreeNullHandling(policy, Collections.singletonList("MATCH"), "Address").isEmpty() && before.equals(policy.toString()));
	}

	private static void translationIsIdempotent_andTheSummaryWordsIt()
	{
		final ObjectNode policy = MatchingPolicyTranslator.buildMatchingPolicy("Address", MatchingPolicyTranslatorTreeNullTest.requirements(), true);
		final List<String> notes = MatchingPolicyTranslator.translateTreeNullHandling(policy, MatchingPolicyTranslatorTreeNullTest.RC3_TREE, "Address");
		MatchingPolicyTranslatorTreeNullTest.check("a second translation finds nothing left",
			MatchingPolicyTranslator.translateTreeNullHandling(policy, MatchingPolicyTranslatorTreeNullTest.RC3_TREE, "Address").isEmpty());
		final String summary = MatchingPolicyTranslator.summariseNullHandlingTranslations(notes);
		MatchingPolicyTranslatorTreeNullTest.check("summary counts the values and points at the step detail: " + summary,
			summary.startsWith(" NOTE: 4 decision-tree null-handling value(s) translated DO_NOTHING -> NOT_MATCH") && summary.contains("nullHandlingTranslated")
				&& summary.contains("AVERAGE"));
		MatchingPolicyTranslatorTreeNullTest.check("no summary when nothing was translated", MatchingPolicyTranslator.summariseNullHandlingTranslations(Collections.<String>emptyList()).isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
