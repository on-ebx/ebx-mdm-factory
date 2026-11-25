package com.on.ebx.factory.master;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot M14 ({@code docs/MATCHING.md} &sect;67), replayed on the 10th live run of the B2C master scenario (2026-10-18, Factory 1.2.0-285):
 * the tree of MatchPlan 81 (project {@code E2EMASTER20261018090711}, {@code AgentRun} 562, step 6, entity {@code Client}) produced 1190 suspect pairs of which 679 were two store tickets
 * of one area sharing only a first or a last name; the simulate-first plan of the same run (project {@code E2EMASTER20261018091200}, {@code AgentRun} 573, step 8, entity
 * {@code Personne}) must still pass. Two rules: child evidence never carries a path alone; a SUSPECTED/MATCH leaf is never the {@code onFalse} of a similarity node unless an own-identity
 * key already holds on the path.
 */
public final class PathEvidenceChildKeysTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		PathEvidenceChildKeysTest.plan81IsRefusedOnBothRules();
		PathEvidenceChildKeysTest.thePassTellsBothTextsInOneAnswer();
		PathEvidenceChildKeysTest.theFixesTheTextsNameSatisfyBothRules();
		PathEvidenceChildKeysTest.theSimulateFirstPlanOfTheSameRunStillPasses();
		PathEvidenceChildKeysTest.theRuleReadsPathsNotSingleNodes();
		PathEvidenceChildKeysTest.theRulesAreInactiveWithoutCompleteFacts();
		PathEvidenceChildKeysTest.theStartingPlanPutsTheChildKeysBesideAnOwnKeyAndPassesTheRules();
		PathEvidenceChildKeysTest.thePromptAndTheChecklistSayIt();
		System.out.println("PathEvidenceChildKeysTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static Path testResource(final String pName)
	{
		final String sourceDir = System.getProperty("mdmf.sourceDir");
		return sourceDir == null ? Paths.get("ebx-mdm-factory/src/test/resources/com/on/ebx/factory/" + pName)
			: Paths.get(sourceDir).resolve("../../test/resources/com/on/ebx/factory/" + pName).normalize();
	}

	private static JsonNode proposal(final String pName)
	{
		try
		{
			return PathEvidenceChildKeysTest.MAPPER.readTree(new String(Files.readAllBytes(PathEvidenceChildKeysTest.testResource("master/" + pName)), StandardCharsets.UTF_8));
		}
		catch (final Exception ex)
		{
			throw new AssertionError("cannot read " + pName + ": " + ex, ex);
		}
	}

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@exactFullText", "ebx-addon-mame@jarowinkler"),
			Arrays.asList("MATCH", "NOT_MATCH"), Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.AttributeInfo plain(final String pName, final int pDistinct, final double pFill)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 255, null, Double.valueOf(pFill), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(), Collections.<String>emptyList());
	}

	private static MatchPlanLlmPass.AttributeInfo matchKey(final String pName, final String pDerivedFrom, final int pDistinct, final double pFill)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 255, null, Double.valueOf(pFill), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_MATCH_KEY, Collections.<String>emptyList(), Collections.<String>emptyList(), pDerivedFrom, null);
	}

	private static MatchPlanLlmPass.AttributeInfo childKeys(final String pName, final String pChild)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 2000, null, Double.valueOf(0.56), "EXACT", Integer.valueOf(300), "EXACT", null, null, null, null, true, null,
			Collections.<String>emptyList(), Collections.<String>emptyList(), null, null, null, null, null, null, null, null, null, MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS, pChild);
	}

	/**
	 * The person entity of the 10th run (the names of the stored plans) with the facts of the scenario (the 534 records reduced to their keys, as lot M13's tests use them) and the
	 * child entity {@code Adresse}: its street (183 distinct among 300 rows) and its full address.
	 */
	private static MatchPlanLlmPass.EntityInfo person(final String pName, final String pFullAddressField, final KeyFacts pFacts)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(PathEvidenceChildKeysTest.matchKey("emailKey", "email", 207, 0.73));
		attributes.add(PathEvidenceChildKeysTest.matchKey("personNameKey", "name", 187, 1.0));
		attributes.add(PathEvidenceChildKeysTest.matchKey("mobileE164", "mobile", 212, 1.0));
		attributes.add(PathEvidenceChildKeysTest.plain("firstName", 28, 1.0));
		attributes.add(PathEvidenceChildKeysTest.plain("lastName", 27, 1.0));
		attributes.add(PathEvidenceChildKeysTest.plain("birthDate", 195, 0.56));
		attributes.add(PathEvidenceChildKeysTest.childKeys("addressKeys", "Adresse"));
		final List<MatchPlanLlmPass.AttributeInfo> childAttributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		childAttributes.add(PathEvidenceChildKeysTest.plain("street", 183, 0.56));
		childAttributes.add(PathEvidenceChildKeysTest.plain(pFullAddressField, 204, 1.0));
		final List<MatchPlanLlmPass.RelationChildInfo> children = Collections.singletonList(
			new MatchPlanLlmPass.RelationChildInfo("Adresse", childAttributes, Integer.valueOf(1), Integer.valueOf(2), Double.valueOf(1.0), Double.valueOf(0.0)));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo(pName, pName, 534, attributes, null, null, null, null, null, children);
		entity.keyFacts = pFacts;
		return entity;
	}

	private static MatchPlanLlmPass.Session session(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put(pEntity.name, pEntity);
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(), PathEvidenceChildKeysTest.vocabulary())
			.requireFilteredCandidateSearch();
	}

	private static List<String> lastNodes(final List<PathEvidenceRule.Offender> pOffenders)
	{
		final List<String> out = new ArrayList<String>();
		for (final PathEvidenceRule.Offender offender : pOffenders)
		{
			out.add(String.join(">", offender.path.nodeIds));
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------ tests

	/**
	 * Plan 81, to the letter. Child evidence alone: F4 (addressKeys) then F5 (names, not exact) then F6 (street of Adresse) - no own key on the path; F4 then F5.onFalse; F7.onFalse then F9
	 * (fullAddress of Adresse). A failed comparison: F5.onFalse -> SUSPECTED, the path that produced the 679 false pairs. The other paths hold an own key (emailKey, mobileE164, the name key).
	 */
	static void plan81IsRefusedOnBothRules()
	{
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceChildKeysTest.person("Client", "fullAddress", MatchPlanLlmPassGuidanceTest.facts());
		final DecisionTreePlan.Plan plan = DecisionTreePlan.fromJson(PathEvidenceChildKeysTest.proposal("run562_step6_client_proposal.json"));
		check(CrossSystemPathRule.positivePaths(plan).paths.size() == 7, "7 positive paths in plan 81: " + CrossSystemPathRule.positivePaths(plan).paths.size());
		check(PathEvidenceRule.check(entity, plan).ok(), "the rule of lot M10a lets plan 81 through (the address keys 'identify'): that is the defect");
		final PathEvidenceRule.StructureResult result = PathEvidenceRule.checkStructure(entity, plan);
		check(result.active && !result.ok(), "lot M14 refuses it");
		check(PathEvidenceChildKeysTest.lastNodes(result.childAlone).equals(Arrays.asList("F1>F2>F3>F4>F5>F6", "F1>F2>F3>F4>F5", "F1>F2>F3>F4>F7>F9")),
			"child evidence alone: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone));
		check(PathEvidenceChildKeysTest.lastNodes(result.failedComparison).equals(Arrays.asList("F1>F2>F3>F4>F5")), "the failed comparison: " + PathEvidenceChildKeysTest.lastNodes(result.failedComparison));
		check(result.ownKeys.contains("emailKey") && result.ownKeys.contains("personNameKey") && result.ownKeys.contains("mobileE164") && !result.ownKeys.contains("addressKeys"),
			"the own-identity keys: " + result.ownKeys);

		final List<String> texts = PathEvidenceRule.structureRefusals(entity, result);
		check(texts.size() == 2, "two texts, one per rule: " + texts.size());
		final String child = texts.get(0);
		check(child.startsWith("the proposed tree has 3 path(s) to SUSPECTED or MATCH carried by child evidence alone: path F1 > F2 > F3 > F4 > F5 > F6 (requires addressKeys exactFullText, personNameKey average jarowinkler, "
			+ "birthDate average exact, street of Adresse exact -> SUSPECTED); path F1 > F2 > F3 > F4 > F5 (requires addressKeys exactFullText -> SUSPECTED); path F1 > F2 > F3 > F4 > F7 > F9 "
			+ "(requires fullAddress of Adresse exactFullText -> SUSPECTED)."), "the child-evidence text names the paths: " + child);
		check(child.contains("must never carry a path alone") && child.contains("exact own-identity key of the record (birthDate, emailKey, mobileE164, personNameKey)")
			&& child.contains("the node that compares the child evidence (node 'F4', node 'F6', node 'F9')") && child.contains("point the onTrue of node 'F4', node 'F6', node 'F9' to NOT_MATCHED"),
			"and the fix: " + child);
		final String failed = texts.get(1);
		check(failed.startsWith("the proposed tree reaches SUSPECTED or MATCH through the onFalse edge of a similarity node, i.e. a FAILED comparison used as evidence, and no own-identity key holds on the path: "
			+ "node 'F5'.onFalse -> SUSPECTED (path F1 > F2 > F3 > F4 > F5 (requires addressKeys exactFullText -> SUSPECTED))."), "the failed-comparison text names the node: " + failed);
		check(failed.contains("point node 'F5'.onFalse to NOT_MATCHED, or open the path with an own-identity key"), "and the two fixes: " + failed);
		System.out.println("plan 81 text 1: " + child);
		System.out.println("plan 81 text 2: " + failed);
	}

	static void thePassTellsBothTextsInOneAnswer()
	{
		final MatchPlanLlmPass.Session session = PathEvidenceChildKeysTest.session(PathEvidenceChildKeysTest.person("Client", "fullAddress", MatchPlanLlmPassGuidanceTest.facts()));
		final LocalTool.Result result = session.proposeMatchPlan(PathEvidenceChildKeysTest.proposal("run562_step6_client_proposal.json"));
		check(result.isError && result.text.contains("carried by child evidence alone") && result.text.contains("through the onFalse edge of a similarity node"),
			"both rules answer: " + result.text);
		check(session.createdPlans().isEmpty(), "nothing recorded");
	}

	/** The fixes the texts name: F5.onFalse to NOT_MATCHED, an own key beside every child condition. Nothing is left to refuse. */
	static void theFixesTheTextsNameSatisfyBothRules()
	{
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceChildKeysTest.person("Client", "fullAddress", MatchPlanLlmPassGuidanceTest.facts());
		final JsonNode original = PathEvidenceChildKeysTest.proposal("run562_step6_client_proposal.json");
		// 1. only the failed comparison fixed: the three child-only paths remain, F5.onFalse is gone
		final DecisionTreePlan.Plan partly = DecisionTreePlan.fromJson(original);
		partly.byId("F5").onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		PathEvidenceRule.StructureResult result = PathEvidenceRule.checkStructure(entity, partly);
		check(result.failedComparison.isEmpty() && PathEvidenceChildKeysTest.lastNodes(result.childAlone).equals(Arrays.asList("F1>F2>F3>F4>F5>F6", "F1>F2>F3>F4>F7>F9")),
			"F5.onFalse -> NOT_MATCHED removes the failed comparison and the child-only path that went through it: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone));
		// 2. an own key opens the child nodes: F4 gets the name key beside the address keys, F9 beside the e-mail... (F7 false = e-mail differs): the name key
		final ObjectNode fixed = ((ObjectNode) original).deepCopy();
		for (final JsonNode node : fixed.path("nodes"))
		{
			if ("F5".equals(node.path("id").asText()))
			{
				((ObjectNode) node).put("onFalse", "NOT_MATCHED");
			}
			if ("F4".equals(node.path("id").asText()) || "F9".equals(node.path("id").asText()))
			{
				((com.fasterxml.jackson.databind.node.ArrayNode) node.path("fields")).insert(0, PathEvidenceChildKeysTest.nameKey());
			}
		}
		final DecisionTreePlan.Plan repaired = DecisionTreePlan.fromJson(fixed);
		result = PathEvidenceRule.checkStructure(entity, repaired);
		check(result.active && result.ok(), "with the name key in F4 and F9 and F5.onFalse -> NOT_MATCHED nothing is refused: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone)
			+ " / " + PathEvidenceChildKeysTest.lastNodes(result.failedComparison));
		check(PathEvidenceRule.check(entity, repaired).ok(), "and lot M10a is still satisfied");
		// 3. the same repair as a proposal: the pass accepts it (as far as these two rules go)
		final LocalTool.Result viaPass = PathEvidenceChildKeysTest.session(entity).proposeMatchPlan(fixed);
		check(!viaPass.text.contains("carried by child evidence alone") && !viaPass.text.contains("through the onFalse edge of a similarity node"), "the pass no longer tells these two rules: " + viaPass.text);
	}

	private static JsonNode nameKey()
	{
		try
		{
			return PathEvidenceChildKeysTest.MAPPER.readTree("{\"field\":\"personNameKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\","
				+ "\"oneNullValueHandling\":\"NOT_MATCH\"}");
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	/**
	 * The simulate-first plan of the same run (project {@code E2EMASTER20261018091200}, entity {@code Personne}, 27 nodes): every path holds an own key (R1 emailKey, R3 mobileE164, R9 the
	 * name key) and its only {@code SUSPECTED} leaf hanging on an {@code onFalse} edge (R6.onFalse, under R1's e-mail key) is the legitimate "key equal, secondary comparison differs".
	 */
	static void theSimulateFirstPlanOfTheSameRunStillPasses()
	{
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceChildKeysTest.person("Personne", "fullPostalAddress", MatchPlanLlmPassGuidanceTest.facts());
		final DecisionTreePlan.Plan plan = DecisionTreePlan.fromJson(PathEvidenceChildKeysTest.proposal("run573_step8_personne_proposal.json"));
		final CrossSystemPathRule.Paths paths = CrossSystemPathRule.positivePaths(plan);
		boolean viaOnFalse = false;
		for (final CrossSystemPathRule.PositivePath path : paths.paths)
		{
			viaOnFalse |= path.leafViaOnFalse && "R6".equals(path.nodeIds.get(path.nodeIds.size() - 1));
		}
		check(viaOnFalse, "R6.onFalse -> SUSPECTED is a leaf reached through an onFalse edge");
		final PathEvidenceRule.StructureResult result = PathEvidenceRule.checkStructure(entity, plan);
		check(result.active && result.ok(), "the simulate-first plan passes both rules: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone) + " / "
			+ PathEvidenceChildKeysTest.lastNodes(result.failedComparison));
		check(PathEvidenceRule.structureRefusals(entity, result).isEmpty(), "no text");
		// and the same R6 edge WITHOUT the e-mail key above it is refused: R6 (an AVERAGE of the names) as the root's false side
		final DecisionTreePlan.Plan bare = DecisionTreePlan.fromJson(PathEvidenceChildKeysTest.tree("{\"root\":\"A\",\"nodes\":[" + PathEvidenceChildKeysTest.average("A", "NOT_MATCHED", "SUSPECTED") + "]}"));
		final PathEvidenceRule.StructureResult bareResult = PathEvidenceRule.checkStructure(entity, bare);
		check(PathEvidenceChildKeysTest.lastNodes(bareResult.failedComparison).equals(Arrays.asList("A")) && bareResult.childAlone.isEmpty(), "an AVERAGE root with SUSPECTED on its false side: " + PathEvidenceChildKeysTest.lastNodes(bareResult.failedComparison));
	}

	private static JsonNode tree(final String pJson)
	{
		try
		{
			return PathEvidenceChildKeysTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static String exact(final String pField, final String pVia, final String pAlgorithm, final int pScore)
	{
		return "{\"field\":\"" + pField + "\"" + (pVia == null ? "" : ",\"via\":\"" + pVia + "\"") + ",\"algorithm\":\"ebx-addon-mame@" + pAlgorithm + "\",\"minimumScore\":" + pScore
			+ ",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String node(final String pId, final String pFields, final String pOnTrue, final String pOnFalse)
	{
		return "{\"id\":\"" + pId + "\",\"function\":\"ALL_FIELDS\",\"fields\":[" + pFields + "],\"onTrue\":\"" + pOnTrue + "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	private static String average(final String pId, final String pOnTrue, final String pOnFalse)
	{
		return "{\"id\":\"" + pId + "\",\"function\":\"AVERAGE\",\"minimumScore\":85,\"fields\":[{\"field\":\"firstName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3,"
			+ "\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"},{\"field\":\"lastName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3,"
			+ "\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}],\"onTrue\":\"" + pOnTrue + "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	private static PathEvidenceRule.StructureResult run(final String pNodes, final String pRoot, final MatchPlanLlmPass.EntityInfo pEntity)
	{
		return PathEvidenceRule.checkStructure(pEntity, DecisionTreePlan.fromJson(PathEvidenceChildKeysTest.tree("{\"root\":\"" + pRoot + "\",\"nodes\":[" + pNodes + "]}")));
	}

	/** Small trees, one decision each: a related record's field is child evidence too; the node DIRECTLY above does not have to hold the own key; an exact node's false side is not a similarity. */
	static void theRuleReadsPathsNotSingleNodes()
	{
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceChildKeysTest.person("Client", "fullAddress", MatchPlanLlmPassGuidanceTest.facts());
		// a field of the related address alone (a street: precise, M10a identifies) is child evidence alone
		PathEvidenceRule.StructureResult result = PathEvidenceChildKeysTest.run(PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("street", "Adresse", "exact", 100), "SUSPECTED", "NOT_MATCHED"), "A", entity);
		check(PathEvidenceChildKeysTest.lastNodes(result.childAlone).equals(Arrays.asList("A")), "a street of Adresse alone: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone));
		// ... beside the name key in the same node, or in a node above through onTrue: accepted
		result = PathEvidenceChildKeysTest.run(PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("personNameKey", null, "exact", 100) + "," + PathEvidenceChildKeysTest.exact("street", "Adresse", "exact", 100),
			"SUSPECTED", "NOT_MATCHED"), "A", entity);
		check(result.ok(), "name key + street in one node: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone));
		result = PathEvidenceChildKeysTest.run(PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("emailKey", null, "exact", 100), "B", "NOT_MATCHED") + ","
			+ PathEvidenceChildKeysTest.average("B", "C", "NOT_MATCHED") + "," + PathEvidenceChildKeysTest.node("C", PathEvidenceChildKeysTest.exact("addressKeys", null, "exactFullText", 50), "SUSPECTED", "NOT_MATCHED"), "A", entity);
		check(result.ok(), "e-mail key, names, then the address keys (the own key sits two nodes above): " + PathEvidenceChildKeysTest.lastNodes(result.childAlone));
		// the own key left through onFalse counts for nothing
		result = PathEvidenceChildKeysTest.run(PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("emailKey", null, "exact", 100), "NOT_MATCHED", "B") + ","
			+ PathEvidenceChildKeysTest.node("B", PathEvidenceChildKeysTest.exact("addressKeys", null, "exactFullText", 50), "SUSPECTED", "NOT_MATCHED"), "A", entity);
		check(PathEvidenceChildKeysTest.lastNodes(result.childAlone).equals(Arrays.asList("A>B")), "an ancestor left through onFalse is not evidence: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone));
		// a coarse non-key attribute (the first name) is not an own-identity key: it does not rescue the address keys
		result = PathEvidenceChildKeysTest.run(PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("firstName", null, "exact", 100) + "," + PathEvidenceChildKeysTest.exact("addressKeys", null, "exactFullText", 50),
			"SUSPECTED", "NOT_MATCHED"), "A", entity);
		check(PathEvidenceChildKeysTest.lastNodes(result.childAlone).equals(Arrays.asList("A")), "first name + address keys: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone));
		// an AVERAGE whose false side is SUSPECTED under the e-mail key: legitimate; under nothing: refused; an exact node's false side is no similarity comparison
		result = PathEvidenceChildKeysTest.run(PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("emailKey", null, "exact", 100), "B", "NOT_MATCHED") + "," + PathEvidenceChildKeysTest.average("B", "SUSPECTED", "SUSPECTED"),
			"A", entity);
		check(result.ok(), "key equal, names differ -> suspect, under an own key: " + PathEvidenceChildKeysTest.lastNodes(result.failedComparison));
		result = PathEvidenceChildKeysTest.run(PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("birthDate", null, "exact", 100), "SUSPECTED", "SUSPECTED"), "A", entity);
		check(result.failedComparison.isEmpty(), "the false side of an ALL_FIELDS node on exact conditions is not a failed similarity (lot M10a judges that path): " + PathEvidenceChildKeysTest.lastNodes(result.failedComparison));
		// a similarity node reached through onTrue is the usual refinement
		result = PathEvidenceChildKeysTest.run(PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("mobileE164", null, "exact", 100), "B", "NOT_MATCHED") + "," + PathEvidenceChildKeysTest.average("B", "SUSPECTED", "NOT_MATCHED"),
			"A", entity);
		check(result.ok(), "own key then similar names: " + PathEvidenceChildKeysTest.lastNodes(result.childAlone));
	}

	static void theRulesAreInactiveWithoutCompleteFacts()
	{
		final String offending = PathEvidenceChildKeysTest.node("A", PathEvidenceChildKeysTest.exact("addressKeys", null, "exactFullText", 50), "SUSPECTED", "NOT_MATCHED");
		final PathEvidenceRule.StructureResult none = PathEvidenceChildKeysTest.run(offending, "A", PathEvidenceChildKeysTest.person("Client", "fullAddress", null));
		check(!none.active && none.ok(), "no facts: the rules do not apply, the pass behaves as before");
		final KeyFacts full = MatchPlanLlmPassGuidanceTest.facts();
		check(PathEvidenceChildKeysTest.run(offending, "A", PathEvidenceChildKeysTest.person("Client", "fullAddress", full)).active, "complete facts: active");
	}

	/**
	 * The Factory's starting plan on the facts of the scenario (the fixture of lot M3, the entity of run 562): the child keys are no longer alone - the name key, the key no other node
	 * already carries alone, stands beside them - and the tree passes both rules and lot M10a.
	 */
	static void theStartingPlanPutsTheChildKeysBesideAnOwnKeyAndPassesTheRules()
	{
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceChildKeysTest.person("Client", "fullAddress", MatchPlanLlmPassGuidanceTest.facts());
		final MatchPlanFallback.Built built = MatchPlanFallback.buildStartingPlan(entity, PathEvidenceChildKeysTest.vocabulary());
		check(built.arguments != null, "built: " + built.reason);
		final StringBuilder tree = new StringBuilder();
		for (final JsonNode node : built.arguments.path("nodes"))
		{
			tree.append(node.path("id").asText()).append(node.path("function").asText().equals("ALL_FIELDS") ? " [" : " ?[");
			final List<String> fields = new ArrayList<String>();
			for (final JsonNode field : node.path("fields"))
			{
				fields.add(field.path("field").asText() + " " + field.path("algorithm").asText().replaceAll(".*@", "") + " " + field.path("minimumScore").asInt());
			}
			tree.append(String.join(", ", fields)).append("] -> ").append(node.path("onTrue").asText()).append(" / ").append(node.path("onFalse").asText()).append("; ");
		}
		System.out.println("starting plan: " + tree);
		check(tree.toString().trim().equals("F1 [mobileE164 exact 100, personNameKey exact 100] -> MATCH / F2; F2 [emailKey exact 100] -> SUSPECTED / F3; F3 [mobileE164 exact 100] -> SUSPECTED / F4; "
			+ "F4 [personNameKey exact 100, addressKeys exactFullText 50] -> SUSPECTED / NOT_MATCHED;"), "the child keys beside the name key: " + tree);
		check(built.summary.endsWith("SUSPECTED on personNameKey + shared child keys addressKeys (exactFullText 50)"), "named in the summary: " + built.summary);
		final DecisionTreePlan.Plan plan = DecisionTreePlan.fromJson(built.arguments);
		check(PathEvidenceRule.check(entity, plan).ok() && PathEvidenceRule.checkStructure(entity, plan).ok(), "the starting plan passes lot M10a and lot M14");
		// and the pass accepts it, the child-evidence challenge excepted
		final MatchPlanLlmPass.Session session = PathEvidenceChildKeysTest.session(entity);
		final LocalTool.Result first = session.proposeMatchPlan(built.arguments);
		final LocalTool.Result result = first.isError ? session.proposeMatchPlan(built.arguments) : first;
		check(!result.isError && session.createdPlans().size() == 1, "the pass accepts it: " + result.text);
	}

	static void thePromptAndTheChecklistSayIt()
	{
		final String system = MatchPlanLlmPass.systemPrompt();
		check(system.contains("CHILD EVIDENCE NEVER CARRIES A PATH ALONE") && system.contains("A FAILED COMPARISON IS NOT EVIDENCE") && system.contains("point it to NOT_MATCHED"), "the system prompt");
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceChildKeysTest.person("Client", "fullAddress", MatchPlanLlmPassGuidanceTest.facts());
		final String block = MatchPlanChecklist.entityBlock(entity);
		check(block.contains("Child evidence never carries a path alone (lot M14)") && block.contains("'names not similar -> suspect' is refused"), "the checklist: " + block);
		final String line = PathEvidenceRule.promptLine(entity);
		check(line.contains("Child evidence (derived child keys, a street or a full address of a related record) never carries a path alone"), "the identifying-keys line: " + line);
		if (line.contains("addressKeys"))
		{
			check(line.contains("addressKeys is child evidence"), "a child key listed among the identifying keys says what it is: " + line);
		}
	}
}
