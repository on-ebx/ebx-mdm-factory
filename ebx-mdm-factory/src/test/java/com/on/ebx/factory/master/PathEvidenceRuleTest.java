package com.on.ebx.factory.master;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot M10a ({@code docs/MATCHING.md} &sect;64): a path to SUSPECTED/MATCH must require a condition that identifies a record. The two live
 * plans of the 7th e2e run of the B2C master scenario are replayed from what the Factory stored: the key facts and the decision tree of MatchPlan 74 (535 records, recall 1.00, 16 false
 * pairs) and of MatchPlan 75 (274 false pairs of 839). Only the path that produced the false pairs is refused; every other path of both plans stays.
 */
public final class PathEvidenceRuleTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		PathEvidenceRuleTest.thePathOfThe16FalsePairsOfRun7IsTheOnlyOneRefused();
		PathEvidenceRuleTest.theRefusalNamesThePathTheReasonsTheKeysAndTheFixes();
		PathEvidenceRuleTest.rePointingTheOffendingEdgeSatisfiesTheRule();
		PathEvidenceRuleTest.thePathOfThe274FalsePairsOfRun8IsRefusedAndTheStreetPathStays();
		PathEvidenceRuleTest.theRuleIsInactiveWithoutCompleteFactsOrWithoutAPredictiveKey();
		PathEvidenceRuleTest.fuzzyAndAverageNodesNeverIdentifyAndAnUnmeasuredFieldGetsTheBenefitOfTheDoubt();
		PathEvidenceRuleTest.thePromptNamesTheIdentifyingKeys();
		System.out.println("PathEvidenceRuleTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	static JsonNode resource(final String pName)
	{
		final String sourceDir = System.getProperty("mdmf.sourceDir");
		final Path file = sourceDir == null ? Paths.get("ebx-mdm-factory/src/test/resources/com/on/ebx/factory/matching/" + pName)
			: Paths.get(sourceDir).resolve("../../test/resources/com/on/ebx/factory/matching/" + pName).normalize();
		try
		{
			return PathEvidenceRuleTest.MAPPER.readTree(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		}
		catch (final Exception ex)
		{
			throw new AssertionError("cannot read " + file + ": " + ex, ex);
		}
	}

	private static MatchPlanLlmPass.AttributeInfo plain(final String pName, final int pDistinct, final double pFill)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 255, null, Double.valueOf(pFill), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(), Collections.<String>emptyList());
	}

	/** A derived child-keys attribute (lot M8a): one key token per address, 194 distinct among the 56% filled. */
	private static MatchPlanLlmPass.AttributeInfo childKeys(final String pName, final String pChild)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 4000, null, Double.valueOf(0.56), "EXACT", Integer.valueOf(194), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(), Collections.<String>emptyList(), null, null, null, null, null, null, null, null, null,
			MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS, pChild);
	}

	private static MatchPlanLlmPass.AttributeInfo unmeasured(final String pName)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 255, null, null, "NOT_SUPPORTED", null, "NOT_SUPPORTED", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(), Collections.<String>emptyList());
	}

	private static MatchPlanLlmPass.RelationChildInfo adresse()
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(PathEvidenceRuleTest.plain("street", 183, 0.56));
		attributes.add(PathEvidenceRuleTest.plain("postalCode", 10, 1.0));
		return new MatchPlanLlmPass.RelationChildInfo("Adresse", attributes, Integer.valueOf(1), Integer.valueOf(1), Double.valueOf(1.0), Double.valueOf(0.0));
	}

	/** The person entity of run 7 (names as the Factory stages them: a name key plus the first and last names) with its stored key facts. */
	static MatchPlanLlmPass.EntityInfo run7Entity(final KeyFacts pFacts)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(PathEvidenceRuleTest.plain("emailKey", 207, 0.73));
		attributes.add(PathEvidenceRuleTest.plain("telephoneE164", 212, 1.0));
		attributes.add(PathEvidenceRuleTest.plain("nomCompletKey", 187, 1.0));
		attributes.add(PathEvidenceRuleTest.plain("nom", 54, 1.0));
		attributes.add(PathEvidenceRuleTest.plain("prenom", 28, 1.0));
		attributes.add(PathEvidenceRuleTest.plain("dateNaissance", 195, 0.56));
		attributes.add(PathEvidenceRuleTest.childKeys("addressKeys", "Adresse"));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Personne", "Personne", 534, attributes, null, null, null, null, null,
			Collections.singletonList(PathEvidenceRuleTest.adresse()));
		entity.keyFacts = pFacts;
		return entity;
	}

	static MatchPlanLlmPass.EntityInfo run8Entity(final KeyFacts pFacts)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(PathEvidenceRuleTest.plain("emailKey", 207, 0.73));
		attributes.add(PathEvidenceRuleTest.plain("mobileE164", 212, 1.0));
		attributes.add(PathEvidenceRuleTest.plain("firstName", 28, 1.0));
		attributes.add(PathEvidenceRuleTest.plain("lastName", 27, 1.0));
		attributes.add(PathEvidenceRuleTest.childKeys("addressKeys", "Adresse"));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Client", "Client", 534, attributes, null, null, null, null, null,
			Collections.singletonList(PathEvidenceRuleTest.adresse()));
		entity.keyFacts = pFacts;
		return entity;
	}

	private static DecisionTreePlan.Plan tree(final String pResource)
	{
		return DecisionTreePlan.fromJson(PathEvidenceRuleTest.resource(pResource));
	}

	private static List<String> lastNodes(final PathEvidenceRule.Result pResult)
	{
		final List<String> out = new ArrayList<String>();
		for (final PathEvidenceRule.Offender offender : pResult.offenders)
		{
			out.add(offender.lastNode());
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------ tests

	/**
	 * MatchPlan 74: 35 leaves, 27 of them positive. Its path C0 > N7 > N9 - the same name key, similar first and last names, the same postal code - is the one the 15 suspect edges
	 * of the 16 false pairs went through (all measured: different e-mail, phone, birth date and street). N30 is its copy under the branch "no key shared" (the shared node N4 was copied
	 * three times by the Factory): the same defect, not reached live only because the candidate search proposes pairs through a shared key. Every other positive path crosses a node
	 * on an identifying key (the e-mail key, the phone key, the address keys) or on the street of the related address.
	 */
	static void thePathOfThe16FalsePairsOfRun7IsTheOnlyOneRefused()
	{
		final KeyFacts facts = KeyFacts.fromJson(PathEvidenceRuleTest.resource("run7_keyfacts.json"));
		PathEvidenceRuleTest.check(facts != null && !facts.sampled() && facts.rows() == 534, "the stored facts of run 7 are read back");
		PathEvidenceRuleTest.check(facts.predictiveKeys().equals(Arrays.asList("emailKey", "addressKeys", "telephone", "telephoneE164", "dateNaissance")),
			"predictive keys of run 7: " + facts.predictiveKeys());
		PathEvidenceRuleTest.check(!facts.predictive("nomCompletKey") && !facts.predictive("prenom") && !facts.predictive("nom"),
			"the name key (79-81% of its pairs share the e-mail or the phone: namesakes) and the first/last names (coarse) do not identify");
		PathEvidenceRuleTest.check(facts.safePair("nomCompletKey", "prenom"),
			"... although the name key agrees 694 of 694 with the first name, a component of itself: the old 'SAFE as a key' reading let it through, the selective partner is what counts");

		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceRuleTest.run7Entity(facts);
		final DecisionTreePlan.Plan plan = PathEvidenceRuleTest.tree("run7_tree.json");
		final CrossSystemPathRule.Paths paths = CrossSystemPathRule.positivePaths(plan);
		PathEvidenceRuleTest.check(paths.paths.size() == 27 && !paths.truncated, "27 positive paths in the stored plan, got " + paths.paths.size());
		final PathEvidenceRule.Result result = PathEvidenceRule.check(entity, plan);
		PathEvidenceRuleTest.check(result.active, "the rule applies to run 7's entity");
		PathEvidenceRuleTest.check(PathEvidenceRuleTest.lastNodes(result).equals(Arrays.asList("N9", "N30")), "only the N9 path and its copy N30 are refused: " + PathEvidenceRuleTest.lastNodes(result));
		final PathEvidenceRule.Offender offender = result.offenders.get(0);
		PathEvidenceRuleTest.check(offender.path.nodeIds.equals(Arrays.asList("C0", "N1", "N4", "N5", "N6", "N7", "N8", "N9")), "the path crosses the name branch: " + offender.path.nodeIds);
		final List<String> texts = new ArrayList<String>();
		for (final PathEvidenceRule.Condition condition : offender.conditions)
		{
			texts.add(condition.text + "=" + condition.verdict);
		}
		PathEvidenceRuleTest.check(texts.equals(Arrays.asList("nomCompletKey exact=COARSE", "nom average jarowinkler=NOT_EXACT", "prenom average jarowinkler=NOT_EXACT",
			"postalCode of Adresse exact=COARSE")), "the four conditions of the path: " + texts);
	}

	static void theRefusalNamesThePathTheReasonsTheKeysAndTheFixes()
	{
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceRuleTest.run7Entity(KeyFacts.fromJson(PathEvidenceRuleTest.resource("run7_keyfacts.json")));
		final String refusal = PathEvidenceRule.refusal(entity, PathEvidenceRule.check(entity, PathEvidenceRuleTest.tree("run7_tree.json")));
		PathEvidenceRuleTest.check(refusal != null, "a refusal");
		PathEvidenceRuleTest.check(refusal.startsWith("the proposed tree has 2 path(s) to SUSPECTED or MATCH that require no condition identifying a record: path C0 > N1 > N4 > N5 > N6 > N7 > N8 > N9 "
			+ "(requires nomCompletKey exact, nom average jarowinkler, prenom average jarowinkler, postalCode of Adresse exact -> SUSPECTED); path "), "the path is named: " + refusal);
		PathEvidenceRuleTest.check(refusal.contains("nomCompletKey: among the 694 pair(s) of records that share it, 79% share emailKey") && refusal.contains("81% share telephoneE164")
			&& refusal.contains("below 90%"), "the name key's own numbers: " + refusal);
		PathEvidenceRuleTest.check(refusal.contains("postalCode of Adresse: each value is shared by 53.4 rows on average (10 distinct values) - a grouping, not an identity"), "the postal code's numbers: " + refusal);
		PathEvidenceRuleTest.check(refusal.contains("emailKey, addressKeys, telephone, telephoneE164, dateNaissance"), "the keys that identify: " + refusal);
		PathEvidenceRuleTest.check(refusal.contains("(1) require an exact condition on one of those keys") && refusal.contains("(2) require an exact condition on a precise field")
			&& refusal.contains("(3) end the path in NOT_MATCHED: point the onTrue of N9, N30 to NOT_MATCHED"), "the three fixes, the last one naming the nodes: " + refusal);
		PathEvidenceRuleTest.check(PathEvidenceRule.refusal(entity, PathEvidenceRule.check(entity, new DecisionTreePlan.Plan())) == null, "an empty plan has nothing to refuse");
	}

	/** The fix the refusal names: N9.onTrue and N30.onTrue to NOT_MATCHED. Nothing is left to refuse, and the plan still has 25 positive paths. */
	static void rePointingTheOffendingEdgeSatisfiesTheRule()
	{
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceRuleTest.run7Entity(KeyFacts.fromJson(PathEvidenceRuleTest.resource("run7_keyfacts.json")));
		final DecisionTreePlan.Plan plan = PathEvidenceRuleTest.tree("run7_tree.json");
		plan.byId("N9").onTrue = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.byId("N30").onTrue = DecisionTreePlan.LEAF_NOT_MATCHED;
		final PathEvidenceRule.Result result = PathEvidenceRule.check(entity, plan);
		PathEvidenceRuleTest.check(result.active && result.ok(), "after the fix no path is refused: " + PathEvidenceRuleTest.lastNodes(result));
		PathEvidenceRuleTest.check(CrossSystemPathRule.positivePaths(plan).paths.size() == 25, "25 positive paths remain");
		// the other way: adding an exact condition on an identifying key to the node satisfies it too
		final DecisionTreePlan.Plan other = PathEvidenceRuleTest.tree("run7_tree.json");
		final DecisionTreePlan.FieldRef email = new DecisionTreePlan.FieldRef("emailKey", "ebx-addon-mame@exact");
		email.minimumScore = Integer.valueOf(100);
		email.bothNull = "NOT_MATCH";
		email.oneNull = "NOT_MATCH";
		other.byId("N9").fields.add(email);
		other.byId("N30").fields.add(email);
		PathEvidenceRuleTest.check(PathEvidenceRule.check(entity, other).ok(), "an exact condition on the e-mail key in the same node identifies the path");
	}

	/**
	 * MatchPlan 75: the first name (28 distinct values) as an exact gate, then similar names: 234 of the 238 false suspect edges. The street path (4 edges) stays - the street is
	 * precise (1.6 rows per value) - and so does every path through the e-mail, the mobile key or the address keys.
	 */
	static void thePathOfThe274FalsePairsOfRun8IsRefusedAndTheStreetPathStays()
	{
		final KeyFacts facts = KeyFacts.fromJson(PathEvidenceRuleTest.resource("run8_keyfacts.json"));
		PathEvidenceRuleTest.check(facts.predictiveKeys().equals(Arrays.asList("emailKey", "addressKeys", "mobile", "mobileE164", "birthDate")), "predictive keys of run 8: " + facts.predictiveKeys());
		PathEvidenceRuleTest.check(!facts.predictive("firstName") && !facts.predictive("lastName"), "the first and last names are groupings");
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceRuleTest.run8Entity(facts);
		final PathEvidenceRule.Result result = PathEvidenceRule.check(entity, PathEvidenceRuleTest.tree("run8_tree.json"));
		PathEvidenceRuleTest.check(PathEvidenceRuleTest.lastNodes(result).equals(Collections.singletonList("N10")), "only the path N6 > N10 (first name, similar names) is refused: "
			+ PathEvidenceRuleTest.lastNodes(result));
		final PathEvidenceRule.Offender offender = result.offenders.get(0);
		PathEvidenceRuleTest.check(offender.path.nodeIds.equals(Arrays.asList("N1", "N2", "N3", "N4", "N6", "N10")), "the path: " + offender.path.nodeIds);
		final String refusal = PathEvidenceRule.refusal(entity, result);
		PathEvidenceRuleTest.check(refusal.contains("firstName: 5660 pair(s) of records share it for 534 filled record(s) (more than 2 per record) - a grouping, not an identity"),
			"the first name is a grouping, with its own numbers: " + refusal);
	}

	static void theRuleIsInactiveWithoutCompleteFactsOrWithoutAPredictiveKey()
	{
		final DecisionTreePlan.Plan plan = PathEvidenceRuleTest.tree("run7_tree.json");
		final MatchPlanLlmPass.EntityInfo noFacts = PathEvidenceRuleTest.run7Entity(null);
		PathEvidenceRuleTest.check(!PathEvidenceRule.check(noFacts, plan).active && PathEvidenceRule.refusal(noFacts, PathEvidenceRule.check(noFacts, plan)) == null && !PathEvidenceRule.applies(noFacts),
			"no facts: the old behaviour");
		// a sample: the numbers are lower bounds
		final ObjectNode sampledJson = (ObjectNode) PathEvidenceRuleTest.resource("run7_keyfacts.json").deepCopy();
		sampledJson.put("sampled", true);
		final MatchPlanLlmPass.EntityInfo sampled = PathEvidenceRuleTest.run7Entity(KeyFacts.fromJson(sampledJson));
		PathEvidenceRuleTest.check(!PathEvidenceRule.check(sampled, plan).active && sampled.keyFacts.predictiveKeys().isEmpty(), "a sample relaxes and refuses nothing");
		// facts in which no key predicts another one: nothing could satisfy the rule, so it does not apply
		final ObjectNode weak = (ObjectNode) PathEvidenceRuleTest.resource("run7_keyfacts.json").deepCopy();
		for (final JsonNode agreement : weak.path("agreements"))
		{
			((ObjectNode) agreement).put("conflict", agreement.path("agree").asLong() * 3);
		}
		final MatchPlanLlmPass.EntityInfo noPredictive = PathEvidenceRuleTest.run7Entity(KeyFacts.fromJson(weak));
		PathEvidenceRuleTest.check(noPredictive.keyFacts.predictiveKeys().isEmpty() && !PathEvidenceRule.check(noPredictive, plan).active, "no predictive key: the rule does not apply");
		PathEvidenceRuleTest.check(PathEvidenceRule.promptLine(noPredictive).isEmpty(), "... and the prompt says nothing about it");
	}

	static void fuzzyAndAverageNodesNeverIdentifyAndAnUnmeasuredFieldGetsTheBenefitOfTheDoubt()
	{
		final KeyFacts facts = KeyFacts.fromJson(PathEvidenceRuleTest.resource("run7_keyfacts.json"));
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceRuleTest.run7Entity(facts);
		// a fuzzy comparison of the e-mail key, and an AVERAGE node that carries it, identify nothing
		final DecisionTreePlan.Plan fuzzy = DecisionTreePlan.fromJson(PathEvidenceRuleTest.json("{\"root\":\"A\",\"nodes\":["
			+ "{\"id\":\"A\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"minimumScore\":95,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"B\"},"
			+ "{\"id\":\"B\",\"function\":\"AVERAGE\",\"minimumScore\":85,\"fields\":[{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}"));
		final PathEvidenceRule.Result fuzzyResult = PathEvidenceRule.check(entity, fuzzy);
		PathEvidenceRuleTest.check(fuzzyResult.offenders.size() == 2, "both paths refused: " + PathEvidenceRuleTest.lastNodes(fuzzyResult));
		// exactFullText below 50 shares less than half of the words
		final DecisionTreePlan.Plan low = DecisionTreePlan.fromJson(PathEvidenceRuleTest.json("{\"root\":\"A\",\"nodes\":["
			+ "{\"id\":\"A\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"addressKeys\",\"algorithm\":\"ebx-addon-mame@exactFullText\",\"minimumScore\":30,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}"));
		PathEvidenceRuleTest.check(PathEvidenceRule.check(entity, low).offenders.size() == 1, "exactFullText at 30 identifies nothing");
		// a field nothing is known about (unmeasured profile, not in the facts) gets the benefit of the doubt; the same field measured coarse does not
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>(entity.attributes);
		attributes.add(PathEvidenceRuleTest.unmeasured("passport"));
		attributes.add(PathEvidenceRuleTest.plain("city", 11, 1.0));
		final MatchPlanLlmPass.EntityInfo extended = new MatchPlanLlmPass.EntityInfo("Personne", "Personne", 534, attributes, null, null, null, null, null,
			Collections.singletonList(PathEvidenceRuleTest.adresse()));
		extended.keyFacts = facts;
		final String exactNode = "{\"id\":\"A\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"%s\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}";
		final DecisionTreePlan.Plan unknown = DecisionTreePlan.fromJson(PathEvidenceRuleTest.json("{\"root\":\"A\",\"nodes\":[" + String.format(exactNode, "passport") + "]}"));
		PathEvidenceRuleTest.check(PathEvidenceRule.check(extended, unknown).ok(), "an unmeasured field is not read as coarse");
		final DecisionTreePlan.Plan coarse = DecisionTreePlan.fromJson(PathEvidenceRuleTest.json("{\"root\":\"A\",\"nodes\":[" + String.format(exactNode, "city") + "]}"));
		final PathEvidenceRule.Result coarseResult = PathEvidenceRule.check(extended, coarse);
		PathEvidenceRuleTest.check(coarseResult.offenders.size() == 1 && coarseResult.offenders.get(0).conditions.get(0).why.startsWith("city: each value is shared by 48.5 rows on average (11 distinct values)"),
			"a measured coarse field is: " + (coarseResult.offenders.isEmpty() ? "ok" : coarseResult.offenders.get(0).conditions.get(0).why));
		// the street alone (precise: 1.6 rows per value) identifies, the postal code alone does not
		final String relation = "{\"id\":\"A\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"%s\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exactFullText\",\"minimumScore\":100,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}";
		PathEvidenceRuleTest.check(PathEvidenceRule.check(entity, DecisionTreePlan.fromJson(PathEvidenceRuleTest.json("{\"root\":\"A\",\"nodes\":[" + String.format(relation, "street") + "]}"))).ok(),
			"the street of the related address identifies");
		PathEvidenceRuleTest.check(PathEvidenceRule.check(entity, DecisionTreePlan.fromJson(PathEvidenceRuleTest.json("{\"root\":\"A\",\"nodes\":[" + String.format(relation, "postalCode") + "]}"))).offenders.size() == 1,
			"the postal code of the related address does not");
	}

	static void thePromptNamesTheIdentifyingKeys()
	{
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceRuleTest.run7Entity(KeyFacts.fromJson(PathEvidenceRuleTest.resource("run7_keyfacts.json")));
		final String line = PathEvidenceRule.promptLine(entity);
		PathEvidenceRuleTest.check(line.startsWith("  Identifying keys on Personne: emailKey, addressKeys, telephone, telephoneE164, dateNaissance (") && line.contains("90% or more of the time")
			&& line.endsWith("\n"), "the line: " + line);
		PathEvidenceRuleTest.check(PathEvidenceRule.promptLine(PathEvidenceRuleTest.run7Entity(null)).isEmpty(), "nothing without facts");
	}

	private static JsonNode json(final String pText)
	{
		try
		{
			return PathEvidenceRuleTest.MAPPER.readTree(pText);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}
}
