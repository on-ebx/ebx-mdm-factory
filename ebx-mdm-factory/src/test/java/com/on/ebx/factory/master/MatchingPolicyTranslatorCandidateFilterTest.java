package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.matching.CandidateFilterCheckTest;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws.
 * <p>
 * Lot M8a (2026-10-04, {@code docs/MATCHING.md} &sect;51), item 6: the measured "E3" plan (project Vol1m, 8 min 21, recall 99.15 %, precision 99.84 %) translates
 * as the measured configuration did - an {@code exactFullText} field on a {@code CHILD_KEYS} attribute carried through to the add-on tree, the four address fields at
 * pre-clustering weight 0, the e-mail key dominating the weights - and its candidate-filter verdict is FILTERED with the three key sets.
 */
public final class MatchingPolicyTranslatorCandidateFilterTest
{
	public static void main(final String[] pArgs)
	{
		MatchingPolicyTranslatorCandidateFilterTest.e3Translates_andIsFiltered();
		MatchingPolicyTranslatorCandidateFilterTest.theChildKeysFieldReachesTheAddonTreeAsExactFullText();
		MatchingPolicyTranslatorCandidateFilterTest.relationFieldsStayOutOfTheCandidateSearch();
		System.out.println("MatchingPolicyTranslatorCandidateFilterTest OK");
	}

	private static MatchingPolicyTranslator.TranslationResult translate(final ObjectNode pRequirements)
	{
		final Map<String, String> child = new LinkedHashMap<String, String>();
		child.put("Adresse", "/root/Adresse");
		return MatchingPolicyTranslator.translate("Personne", pRequirements, false, child);
	}

	private static JsonNode policyField(final ObjectNode pPolicy, final String pName)
	{
		for (final JsonNode field : pPolicy.path("fields"))
		{
			if (("/" + pName).equals(field.path("field").asText()))
			{
				return field;
			}
		}
		throw new AssertionError("no matchingPolicy field " + pName + " in " + pPolicy.path("fields"));
	}

	private static void e3Translates_andIsFiltered()
	{
		final ObjectNode requirements = CandidateFilterCheckTest.e3Requirements();
		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslatorCandidateFilterTest.translate(requirements);
		check("translation succeeds and the four relation fields are native business-object fields", result.relationFieldWeights.size() == 4);
		final CandidateFilterCheck.Result filter = CandidateFilterCheck.check(requirements);
		check("FILTERED", filter.filtered());
		check("three key sets {emailKey} {phoneE164} {adresseKeys}", filter.keySets().equals(
			Arrays.asList(Arrays.asList("emailKey"), Arrays.asList("phoneE164"), Arrays.asList("adresseKeys"))));
		// the clustering fields come first, in the plan's order
		final ObjectNode policy = result.matchingPolicy;
		check("the first declared fields are the clustering fields: " + policy.path("fields").get(0).path("field").asText(), "/emailKey".equals(policy.path("fields").get(0).path("field").asText())
			&& "/phoneE164".equals(policy.path("fields").get(1).path("field").asText()) && "/adresseKeys".equals(policy.path("fields").get(2).path("field").asText()));
		final int email = MatchingPolicyTranslatorCandidateFilterTest.policyField(policy, "emailKey").path("weight").asInt();
		final int phone = MatchingPolicyTranslatorCandidateFilterTest.policyField(policy, "phoneE164").path("weight").asInt();
		final int keys = MatchingPolicyTranslatorCandidateFilterTest.policyField(policy, "adresseKeys").path("weight").asInt();
		final int lastName = MatchingPolicyTranslatorCandidateFilterTest.policyField(policy, "lastName").path("weight").asInt();
		final int firstName = MatchingPolicyTranslatorCandidateFilterTest.policyField(policy, "firstName").path("weight").asInt();
		check("names keep their weights 5 and 4: " + lastName + "/" + firstName, lastName == 5 && firstName == 4);
		check("the other keys weigh at least 1: " + phone + "/" + keys, phone >= 1 && keys >= 1);
		// lot M16 (docs/MATCHING.md sec 68): nothing raises a weight any more - the dominance of lot R16 produced the run 11 miss; the model's clusteringWeights are the only lever, and the shares of
		// the key sets are measured (candidateScoreShare, applied:false)
		check("the e-mail key keeps the weight the plan gives it (1), no dominance guard: " + email, email == 1);
		check("the shares are measured and not applied: " + result.candidateScoreShare, result.candidateScoreShare != null && !result.candidateScoreShare.path("applied").asBoolean());
	}

	private static void theChildKeysFieldReachesTheAddonTreeAsExactFullText()
	{
		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslatorCandidateFilterTest.translate(CandidateFilterCheckTest.e3Requirements());
		final JsonNode keysField = MatchingPolicyTranslatorCandidateFilterTest.policyField(result.matchingPolicy, "adresseKeys");
		// NOT asserted: this entry's own null handling (it mirrors the field's FIRST appearance in the tree). The add-on derives the candidate filter from the
		// null handling of the tree NODE field (P5's, asserted below), which is what the measured E3 run configured.
		check("declared in the search with the default strategy", "@default".equals(keysField.path("searchStrategy").asText()));
		// the occurrence that matters is the one in P5, the only one compared with exactFullText
		final List<JsonNode> occurrences = new ArrayList<JsonNode>();
		MatchingPolicyTranslatorCandidateFilterTest.collectTreeFields(result.matchingPolicy.path("decisionTree"), "/adresseKeys", occurrences);
		JsonNode treeField = null;
		for (final JsonNode occurrence : occurrences)
		{
			if ("ebx-addon-mame@exactFullText".equals(occurrence.path("algorithm").asText()))
			{
				treeField = occurrence;
			}
		}
		check("the tree compares adresseKeys with exactFullText once: " + occurrences, treeField != null);
		check("with minimumScore 20 and NOT_MATCH on both null handlings", "NOT_MATCH".equals(treeField.path("bothNullValueHandling").asText())
			&& "NOT_MATCH".equals(treeField.path("oneNullValueHandling").asText()) && "20".equals(MatchingPolicyTranslatorCandidateFilterTest.paramValue(treeField, "minimumScore")));
	}

	private static void relationFieldsStayOutOfTheCandidateSearch()
	{
		final MatchingPolicyTranslator.TranslationResult result = MatchingPolicyTranslatorCandidateFilterTest.translate(CandidateFilterCheckTest.e3Requirements());
		for (final Map.Entry<String, Integer> relation : result.relationFieldWeights.entrySet())
		{
			check("relation field " + relation.getKey() + " weighs 0 in the candidate search (lot M2 default)", relation.getValue().intValue() == 0);
			final JsonNode policyField = MatchingPolicyTranslatorCandidateFilterTest.relationPolicyField(result.matchingPolicy, relation.getKey());
			check("its policy field carries businessObjectField and no field, weight 0: " + policyField, !policyField.hasNonNull("field") && policyField.path("weight").asInt(-1) == 0);
		}
	}

	private static JsonNode relationPolicyField(final ObjectNode pPolicy, final String pPath)
	{
		for (final JsonNode field : pPolicy.path("fields"))
		{
			if (pPath.equals(field.path("businessObjectField").asText(null)))
			{
				return field;
			}
		}
		throw new AssertionError("no relation policy field " + pPath + " in " + pPolicy.path("fields"));
	}

	private static String paramValue(final JsonNode pField, final String pName)
	{
		for (final JsonNode param : pField.path("params"))
		{
			if (pName.equals(param.path("name").asText()))
			{
				return param.path("value").asText();
			}
		}
		return null;
	}

	/** Depth-first walk of the nested add-on tree ({@code trueNode}/{@code falseNode}) collecting every node field of the given path. */
	private static void collectTreeFields(final JsonNode pNode, final String pFieldPath, final List<JsonNode> pOut)
	{
		if (pNode == null || pNode.isMissingNode() || pNode.isNull())
		{
			return;
		}
		for (final JsonNode field : pNode.path("fields"))
		{
			if (pFieldPath.equals(field.path("field").asText()))
			{
				pOut.add(field);
			}
		}
		MatchingPolicyTranslatorCandidateFilterTest.collectTreeFields(pNode.path("trueNode"), pFieldPath, pOut);
		MatchingPolicyTranslatorCandidateFilterTest.collectTreeFields(pNode.path("falseNode"), pFieldPath, pOut);
	}

	private static void check(final String pDescription, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pDescription);
		}
	}
}
