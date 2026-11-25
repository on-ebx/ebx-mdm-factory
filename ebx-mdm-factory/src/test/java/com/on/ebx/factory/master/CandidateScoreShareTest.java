package com.on.ebx.factory.master;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws.
 * <p>
 * Lot M16 (2026-10-18, {@code docs/MATCHING.md} &sect;68): the arithmetic of the add-on's candidate search as a MEASUREMENT - a candidate is kept when it scores at least 30 % of the pivot's own score,
 * the pivot being worth the sum of the weights of the fields it fills. {@code CandidateScoreShare} computes the share of the total weight each key set of a tree holds and the weights that would lift the
 * failing sets; {@code MatchingPolicyTranslator} writes only the weights the plan carries (the model's {@code clusteringWeights} on top of the node weights) and no longer raises any (the dominance guard of
 * lot R16, which produced run 11's miss: emailKey 4 + three fields at 1 = 7, the phone + name path 2 of 7 = 29 %, 150 true pairs never proposed). The two live trees are fixtures
 * ({@code run11_plan85_requirements.json} - plain variant, 565 of 565 pairs found - and {@code run11_plan86_requirements.json} - simulate-first variant, 415 of 565).
 */
public final class CandidateScoreShareTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		CandidateScoreShareTest.liveWeightsOfRun11_phoneAndNamePathIsUnder30Percent();
		CandidateScoreShareTest.theSuggestionLiftsEverySetAndIsAFactNotAnAction();
		CandidateScoreShareTest.theTranslatorWritesTheModelsWeightsAndRaisesNothing();
		CandidateScoreShareTest.defaultWeightsOnTheLiveTrees();
		CandidateScoreShareTest.aKeyTooWeakToReachTheShareAlone_isNotServed();
		CandidateScoreShareTest.tooManyDisjointSets_matchSetsServedFirst();
		CandidateScoreShareTest.nothingSatisfiable_noSuggestion();
		CandidateScoreShareTest.relationFieldWeightCountsInTheTotal();
		CandidateScoreShareTest.aPlanWithoutKeySetHasNoAnalysis();
		CandidateScoreShareTest.theFactorysOwnPlanCarriesTheWeightsThatHoldItsSets();
		CandidateScoreShareTest.translationIsDeterministic();
		System.out.println("CandidateScoreShareTest OK");
	}

	// ------------------------------------------------------------------------------------------------------ the add-on's rule, written independently of the class under test

	/** The add-on's candidate rule for ONE pivot: its score is the sum of the weights of the fields it fills; a candidate satisfying the key set scores the set's sum; kept when at least 30 % of the pivot's. */
	private static boolean addonKeepsCandidate(final Map<String, Integer> pWeights, final Set<String> pPivotFills, final List<String> pKeySet)
	{
		double pivot = 0d;
		for (final String field : pPivotFills)
		{
			pivot += pWeights.get(field).doubleValue();
		}
		double pair = 0d;
		for (final String field : pKeySet)
		{
			pair += pWeights.get(field).doubleValue();
		}
		return pair >= 0.3d * pivot;
	}

	private static Map<String, Integer> policyWeights(final ObjectNode pPolicy)
	{
		final Map<String, Integer> out = new LinkedHashMap<String, Integer>();
		for (final JsonNode field : pPolicy.path("fields"))
		{
			if (field.hasNonNull("field"))
			{
				out.put(field.path("field").asText().replace("/", ""), Integer.valueOf(field.path("weight").asInt()));
			}
		}
		return out;
	}

	private static Set<String> set(final String... pFields)
	{
		return new LinkedHashSet<String>(Arrays.asList(pFields));
	}

	private static ObjectNode liveRequirements86(final String pClusteringWeightsJson) throws Exception
	{
		final ObjectNode requirements = (ObjectNode) CandidateScoreShareTest.fixture("run11_plan86_requirements.json");
		if (pClusteringWeightsJson != null)
		{
			requirements.set("clusteringWeights", CandidateScoreShareTest.MAPPER.readTree(pClusteringWeightsJson));
		}
		return requirements;
	}

	// ------------------------------------------------------------------------------------------------------ the live trees

	/** Run 11, plan 86: with the weights the old guard wrote (emailKey 4, the others 1: total 7) the phone + name path (a MATCH path) holds 2 of 7 = 28.6 %, the phone + address path 21.4 %. */
	private static void liveWeightsOfRun11_phoneAndNamePathIsUnder30Percent() throws Exception
	{
		final ObjectNode requirements = CandidateScoreShareTest.liveRequirements86("{\"emailKey\":4}");
		final CandidateScoreShare.Analysis analysis = MatchingPolicyTranslator.analyzeShares(requirements);
		check("an analysis exists", analysis != null && analysis.totalWeight == 7);
		check("the sets under 30 % are the phone + name path and the phone + address path: " + analysis.toJson(), analysis.failing.size() == 2
			&& "mobileE164 + personNameKey".equals(analysis.failing.get(0).fieldsText()) && "mobileE164 + addressKeys".equals(analysis.failing.get(1).fieldsText()));
		check("the phone + name set is a MATCH path", analysis.failing.get(0).set.match);
		check("28.6 %", Math.abs(analysis.failing.get(0).share - 2d / 7d) < 1e-9);
		final Map<String, Integer> weights = analysis.weights;
		final Set<String> ecomFills = CandidateScoreShareTest.set("emailKey", "mobileE164", "personNameKey", "addressKeys");
		check("the add-on's rule agrees: not a candidate for a pivot that fills all four fields: " + weights,
			!CandidateScoreShareTest.addonKeepsCandidate(weights, ecomFills, Arrays.asList("mobileE164", "personNameKey")));
		check("but it is one for a pivot without the address key (2 of 6): the persons without an ECOM record were all merged live",
			CandidateScoreShareTest.addonKeepsCandidate(weights, CandidateScoreShareTest.set("emailKey", "mobileE164", "personNameKey"), Arrays.asList("mobileE164", "personNameKey")));
	}

	/** The suggestion is a map for the model to read (clusteringWeights), computed without touching the input; applied, it holds every set and the add-on's rule keeps the pairs. */
	private static void theSuggestionLiftsEverySetAndIsAFactNotAnAction() throws Exception
	{
		final ObjectNode requirements = CandidateScoreShareTest.liveRequirements86("{\"emailKey\":4}");
		final CandidateScoreShare.Analysis analysis = MatchingPolicyTranslator.analyzeShares(requirements);
		check("something is suggested", !analysis.suggested.isEmpty() && "ALL_PATHS".equals(analysis.scope) && analysis.suggestedShare >= 0.315);
		check("the note says it is not applied", !analysis.toJson().path("applied").asBoolean() && analysis.toJson().has("suggestedClusteringWeights"));
		check("the input was not changed", requirements.path("clusteringWeights").path("emailKey").asInt() == 4 && requirements.path("clusteringWeights").size() == 1);
		final Map<String, Integer> lifted = new LinkedHashMap<String, Integer>(analysis.weights);
		lifted.putAll(analysis.suggested);
		final ObjectNode weights = CandidateScoreShareTest.MAPPER.createObjectNode();
		for (final Map.Entry<String, Integer> entry : lifted.entrySet())
		{
			weights.put(entry.getKey(), entry.getValue().intValue());
		}
		requirements.set("clusteringWeights", weights);
		final CandidateScoreShare.Analysis again = MatchingPolicyTranslator.analyzeShares(requirements);
		check("with the suggested weights nothing fails any more: " + again.toJson(), again.ok());
		final Set<String> ecomFills = CandidateScoreShareTest.set("emailKey", "mobileE164", "personNameKey", "addressKeys");
		check("the phone + name pair is a candidate for the fullest pivot", CandidateScoreShareTest.addonKeepsCandidate(again.weights, ecomFills, Arrays.asList("mobileE164", "personNameKey")));
		check("so is the e-mail alone", CandidateScoreShareTest.addonKeepsCandidate(again.weights, ecomFills, Arrays.asList("emailKey")));
		check("no weight was lowered", again.weights.get("emailKey").intValue() >= 4);
	}

	/** The policy carries the node weights (1 for an exact key) and the model's clusteringWeights on top - nothing raises a weight any more. */
	private static void theTranslatorWritesTheModelsWeightsAndRaisesNothing() throws Exception
	{
		final MatchingPolicyTranslator.TranslationResult plain = MatchingPolicyTranslator.translate("Personne", CandidateScoreShareTest.liveRequirements86(null), true,
			Collections.<String, String>emptyMap());
		check("no clusteringWeights: every key weighs 1 (the old guard wrote emailKey 4): " + CandidateScoreShareTest.policyWeights(plain.matchingPolicy),
			CandidateScoreShareTest.policyWeights(plain.matchingPolicy).values().stream().allMatch(w -> w.intValue() == 1));
		final MatchingPolicyTranslator.TranslationResult chosen = MatchingPolicyTranslator.translate("Personne",
			CandidateScoreShareTest.liveRequirements86("{\"emailKey\":4,\"mobileE164\":4,\"nowhere\":9}"), true, Collections.<String, String>emptyMap());
		final Map<String, Integer> weights = CandidateScoreShareTest.policyWeights(chosen.matchingPolicy);
		check("the model's weights are written: " + weights, weights.get("emailKey").intValue() == 4 && weights.get("mobileE164").intValue() == 4
			&& weights.get("personNameKey").intValue() == 1 && weights.get("addressKeys").intValue() == 1 && !weights.containsKey("nowhere"));
		check("the note reports the shares of those weights, applied:false", chosen.candidateScoreShare != null && !chosen.candidateScoreShare.path("applied").asBoolean()
			&& chosen.candidateScoreShare.path("totalWeight").asInt() == 10);
		check("and they hold every set: phone + name 50 %", chosen.candidateScoreShare.path("ok").asBoolean());
	}

	/** Plan 86 at weight 1 everywhere: only the e-mail alone (a SUSPECTED path) is under 30 % (1 of 4); plan 85 (plain variant, found 565 of 565): same figures. */
	private static void defaultWeightsOnTheLiveTrees() throws Exception
	{
		final CandidateScoreShare.Analysis at86 = MatchingPolicyTranslator.analyzeShares(CandidateScoreShareTest.liveRequirements86(null));
		check("plan 86 at weight 1: only [emailKey] 25 % fails: " + at86.toJson(), at86.failing.size() == 1 && "emailKey".equals(at86.failing.get(0).fieldsText()) && !at86.failing.get(0).set.match);
		check("the MATCH set (phone + name) holds 50 %", Math.abs(at86.sets.get(2).share - 0.5) < 1e-9);
		final JsonNode requirements85 = CandidateScoreShareTest.fixture("run11_plan85_requirements.json");
		final CandidateScoreShare.Analysis at85 = MatchingPolicyTranslator.analyzeShares(requirements85);
		check("plan 85 at weight 1 (a relation field at 0): the MATCH path holds 50 %: " + at85.toJson(), Math.abs(at85.sets.get(0).share - 0.5) < 1e-9 && at85.sets.get(0).set.match);
		check("and its single-key SUSPECTED paths are under 30 %, told as facts", at85.failing.size() >= 2 && at85.failing.stream().noneMatch(f -> f.set.match));
	}

	// ------------------------------------------------------------------------------------------------------ shapes

	/** An exactFullText key worth 20 % of its weight can never hold 31.5 % of the total, whatever its weight: not served, the other set is. */
	private static void aKeyTooWeakToReachTheShareAlone_isNotServed()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("phone", "ebx-addon-mame@exact"));
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = "N1";
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		final DecisionTreePlan.FieldRef child = new DecisionTreePlan.FieldRef("childKeys", "ebx-addon-mame@exactFullText");
		child.minimumScore = Integer.valueOf(20);
		n1.fields.add(child);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final CandidateScoreShare.Analysis analysis = MatchingPolicyTranslator.analyzeShares(CandidateScoreShareTest.requirementsFor(plan));
		check("both sets fail at weight 1 (1 of 2 = 50 % passes for the phone; 20 % of 1 of 2 = 10 % for the child keys): " + analysis.toJson(),
			analysis.failing.size() == 1 && "childKeys".equals(analysis.failing.get(0).fieldsText()));
		check("no suggestion can lift a 20 % key: it is left", analysis.suggested.isEmpty() && "NONE".equals(analysis.scope));
	}

	/** Four disjoint single-field sets cannot all hold 30 % of the total; the one of the MATCH path is served first. */
	private static void tooManyDisjointSets_matchSetsServedFirst()
	{
		final CandidateScoreShare.Analysis analysis = MatchingPolicyTranslator.analyzeShares(CandidateScoreShareTest.requirementsFor(CandidateScoreShareTest.disjointPlan(DecisionTreePlan.LEAF_MATCH)));
		check("scope is the MATCH paths: " + analysis.toJson(), "MATCH_PATHS".equals(analysis.scope) && !analysis.suggested.isEmpty());
		check("only the MATCH key (a) is raised", analysis.suggested.keySet().equals(CandidateScoreShareTest.set("a")));
	}

	private static void nothingSatisfiable_noSuggestion()
	{
		final CandidateScoreShare.Analysis analysis = MatchingPolicyTranslator.analyzeShares(CandidateScoreShareTest.requirementsFor(CandidateScoreShareTest.disjointPlan(DecisionTreePlan.LEAF_SUSPECTED)));
		check("scope NONE, nothing suggested: " + analysis.toJson(), "NONE".equals(analysis.scope) && analysis.suggested.isEmpty() && analysis.failing.size() == 4);
	}

	/** A relation field is worth its configured weight in the total: the e-mail needs more to hold its share. */
	private static void relationFieldWeightCountsInTheTotal()
	{
		final DecisionTreePlan.Plan plan = CandidateScoreShareTest.identifierPlan();
		final DecisionTreePlan.FieldRef cityKey = new DecisionTreePlan.FieldRef("cityKey", "ebx-addon-mame@exact");
		cityKey.via = "Adresse";
		plan.nodes.get(1).fields.add(cityKey);
		final ObjectNode requirements = (ObjectNode) CandidateScoreShareTest.requirementsFor(plan);
		final CandidateScoreShare.Analysis noRelationWeight = MatchingPolicyTranslator.analyzeShares(requirements);
		requirements.putObject("relationClusteringWeights").put("Adresse.cityKey", 6);
		final CandidateScoreShare.Analysis withRelationWeight = MatchingPolicyTranslator.analyzeShares(requirements);
		check("total 1 + 6 + 8 = 15 without a relation weight, 21 with 6", noRelationWeight.totalWeight == 15 && withRelationWeight.totalWeight == 21);
		check("the e-mail alone is 1 of 21 = 4.8 % with it", Math.abs(withRelationWeight.sets.get(0).share - 1d / 21d) < 1e-9);
		check("the suggestion for the e-mail grows with the relation weight (>= 10 without, >= 14 with)", noRelationWeight.suggested.get("emailKey").intValue() >= 10
			&& withRelationWeight.suggested.get("emailKey").intValue() >= 14);
	}

	private static void aPlanWithoutKeySetHasNoAnalysis()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_AVERAGE);
		n0.minimumScore = Integer.valueOf(85);
		final DecisionTreePlan.FieldRef name = new DecisionTreePlan.FieldRef("name", "ebx-addon-mame@fuzzyFullText");
		name.weight = Integer.valueOf(3);
		n0.fields.add(name);
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n0);
		check("no analysis, no note", MatchingPolicyTranslator.analyzeShares(CandidateScoreShareTest.requirementsFor(plan)) == null
			&& CandidateScoreShareTest.translate(plan).candidateScoreShare == null);
		check("the proposed weight is written as it is", CandidateScoreShareTest.policyWeights(CandidateScoreShareTest.translate(plan).matchingPolicy).get("name").intValue() == 3);
	}

	/** The one place the Factory writes weights: the plan it builds itself (fallback / starting plan) carries clusteringWeights when its default weights leave a set under 30 %. */
	private static void theFactorysOwnPlanCarriesTheWeightsThatHoldItsSets()
	{
		final ObjectNode arguments = MAPPER.createObjectNode();
		arguments.put("root", "N0");
		final DecisionTreePlan.Plan plan = CandidateScoreShareTest.identifierPlan();
		arguments.set("nodes", DecisionTreePlan.toJson(plan).path("nodes"));
		arguments.putArray("clusteringFields");
		final ObjectNode suggested = CandidateScoreShare.suggestFor(arguments);
		check("the e-mail is given the weight that holds its share: " + suggested, suggested.path("emailKey").asInt() >= 10);
		arguments.set("clusteringWeights", suggested);
		check("with them nothing is left to suggest", CandidateScoreShare.suggestFor(arguments).size() == 0);
	}

	private static void translationIsDeterministic() throws Exception
	{
		final JsonNode requirements = CandidateScoreShareTest.liveRequirements86("{\"emailKey\":4,\"mobileE164\":4}");
		check("the same plan gives the same policy twice", MatchingPolicyTranslator.buildMatchingPolicy("Personne", requirements, true)
			.equals(MatchingPolicyTranslator.buildMatchingPolicy("Personne", requirements, true)));
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private static DecisionTreePlan.Plan identifierPlan()
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final DecisionTreePlan.Node n0 = new DecisionTreePlan.Node("N0", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n0.fields.add(new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact"));
		n0.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n0.onFalse = "N1";
		plan.nodes.add(n0);
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_AVERAGE);
		n1.minimumScore = Integer.valueOf(85);
		final DecisionTreePlan.FieldRef lastName = new DecisionTreePlan.FieldRef("lastNameKey", "ebx-addon-mame@fuzzyFullText");
		lastName.weight = Integer.valueOf(6);
		final DecisionTreePlan.FieldRef firstName = new DecisionTreePlan.FieldRef("firstNameKey", "ebx-addon-mame@fuzzyFullText");
		firstName.weight = Integer.valueOf(8);
		n1.fields.add(lastName);
		n1.fields.add(firstName);
		n1.onTrue = DecisionTreePlan.LEAF_SUSPECTED;
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		return plan;
	}

	/** Four single-field exact nodes in a chain a, b, c, d: four disjoint key sets. {@code pFirstOutcome} is the outcome of the first (a); the others end in SUSPECTED. */
	private static DecisionTreePlan.Plan disjointPlan(final String pFirstOutcome)
	{
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N0";
		final String[] fields = { "a", "b", "c", "d" };
		for (int i = 0; i < fields.length; i++)
		{
			final DecisionTreePlan.Node node = new DecisionTreePlan.Node("N" + i, DecisionTreePlan.FUNCTION_ALL_FIELDS);
			node.fields.add(new DecisionTreePlan.FieldRef(fields[i], "ebx-addon-mame@exact"));
			node.onTrue = i == 0 ? pFirstOutcome : DecisionTreePlan.LEAF_SUSPECTED;
			node.onFalse = i + 1 < fields.length ? "N" + (i + 1) : DecisionTreePlan.LEAF_NOT_MATCHED;
			plan.nodes.add(node);
		}
		return plan;
	}

	private static MatchingPolicyTranslator.TranslationResult translate(final DecisionTreePlan.Plan pPlan)
	{
		return MatchingPolicyTranslator.translate("Personne", CandidateScoreShareTest.requirementsFor(pPlan), false, Collections.<String, String>emptyMap());
	}

	private static JsonNode requirementsFor(final DecisionTreePlan.Plan pPlan)
	{
		final ObjectNode requirements = CandidateScoreShareTest.MAPPER.createObjectNode();
		requirements.set("decisionTree", DecisionTreePlan.toJson(pPlan));
		requirements.putArray("clusteringFields");
		requirements.putObject("mergePolicy").put("mode", "ONLY_DUPLICATES");
		return requirements;
	}

	private static JsonNode fixture(final String pName) throws Exception
	{
		final String sourceDir = System.getProperty("mdmf.sourceDir");
		final String path = "com/on/ebx/factory/master/" + pName;
		return CandidateScoreShareTest.MAPPER.readTree(new String(Files.readAllBytes(sourceDir == null ? Paths.get("ebx-mdm-factory/src/test/resources/" + path)
			: Paths.get(sourceDir).resolve("../../test/resources/" + path).normalize()), StandardCharsets.UTF_8));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
