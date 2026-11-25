package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.matching.B2cKeyFixtureTest;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot M9 (2026-10-18, {@code docs/MATCHING.md} &sect;63): live run 497 left the person entity without a plan after five
 * refused proposals, two identical {@code UNKNOWN_BRANCH_TARGET} among them. Three things: the refusal names the ids the tree defines and the two fixes; a structural
 * refusal gets one more chance before the breaker ends the entity; and the Factory proposes its own plan, built from the measured key facts, when the model ends with
 * none - run through the same checks as a model plan.
 */
public final class MatchPlanFallbackTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanFallbackTest.theUnknownTargetRefusalNamesTheDefinedIdsAndTheTwoFixes();
		MatchPlanFallbackTest.aStructuralRefusalEndsTheEntityOnTheThirdTimeInARowAContentRefusalOnTheSecond();
		MatchPlanFallbackTest.theRefusalClassesAreTheOnesDocumented();
		MatchPlanFallbackTest.theBuilderPutsTheSafePairAtTheRootThenEachKeyAloneThenTheChildKeys();
		MatchPlanFallbackTest.withoutASafePairTheChainStartsWithTheKeysAlone();
		MatchPlanFallbackTest.coarseKeysSourcesOfAKeyAndUnknownAttributesNeverGetANode();
		MatchPlanFallbackTest.noFactsASampleOrAChildEntityGiveNoFallback();
		MatchPlanFallbackTest.theSessionProposesTheFallbackForAnEndedEntityThroughTheSameChecks();
		MatchPlanFallbackTest.aFallbackThatFailsTheChecksLeavesTheEntityWithoutAPlanAndSaysWhy();
		MatchPlanFallbackTest.theB2cPersonFactsGiveTheTreeOfRun497();
		MatchPlanFallbackTest.theFactsOfRun7NeverPutTheNameKeyAloneInTheFallbackAndThePathRuleAcceptsTheTree();
		System.out.println("MatchPlanFallbackTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@exactFullText", "ebx-addon-mame@jarowinkler"),
			Arrays.asList("MATCH", "NOT_MATCH"), Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.AllowedVocabulary vocabularyWithoutFullText()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@jarowinkler"), Arrays.asList("MATCH", "NOT_MATCH"),
			Arrays.asList("mostCompleted"));
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
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 4000, null, Double.valueOf(0.56), "EXACT", Integer.valueOf(194), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(), Collections.<String>emptyList(), null, null, null, null, null, null, null, null, null,
			MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS, pChild);
	}

	/** Person: a phone, a name key, two names (the LoopTest fixture), optionally the child keys of its addresses. */
	private static MatchPlanLlmPass.EntityInfo person(final KeyFacts pFacts, final boolean pWithChildKeys)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanFallbackTest.plain("mobile", 212, 1.0));
		attributes.add(MatchPlanFallbackTest.plain("nameKey", 187, 1.0));
		attributes.add(MatchPlanFallbackTest.plain("firstName", 150, 1.0));
		attributes.add(MatchPlanFallbackTest.plain("lastName", 150, 1.0));
		if (pWithChildKeys)
		{
			attributes.add(MatchPlanFallbackTest.childKeys("addressKeys", "Adresse"));
		}
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Person", "Person", 534, attributes);
		entity.keyFacts = pFacts;
		return entity;
	}

	private static MatchPlanLlmPass.EntityInfo store()
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanFallbackTest.plain("storeCode", 100, 1.0));
		return new MatchPlanLlmPass.EntityInfo("Store", "Store", 100, attributes);
	}

	private static MatchPlanLlmPass.Session session(final KeyFacts pFacts, final List<MatchPlanLlmPass.ExistingPlan> pPrior)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Person", MatchPlanFallbackTest.person(pFacts, false));
		entities.put("Store", MatchPlanFallbackTest.store());
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), pPrior, MatchPlanFallbackTest.vocabulary());
	}

	private static String key(final String pName)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String fuzzy(final String pName)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"minimumScore\":85,\"weight\":3,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	/** The live shape of run 497: a node whose onFalse names a node the tree never defines; the rest of the tree varies between attempts. */
	private static String unknownTarget(final String pSecondField)
	{
		return "{\"entity\":\"Person\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanFallbackTest.key("mobile") + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N2\"},"
			+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":85,\"fields\":[" + MatchPlanFallbackTest.fuzzy(pSecondField) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N7\"}],"
			+ "\"clusteringFields\":[],\"rationale\":\"a tree with a dangling branch\",\"confidence\":60}";
	}

	/** The refusal of live run 433: an exact MATCH-terminal node on a phone that is not near-unique. */
	private static String phoneMatch(final String pSecondNodeField)
	{
		return "{\"entity\":\"Person\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanFallbackTest.key("mobile") + "],\"onTrue\":\"MATCH\",\"onFalse\":\"N2\"},"
			+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":85,\"fields\":[" + MatchPlanFallbackTest.fuzzy(pSecondNodeField)
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[\"mobile\"],\"rationale\":\"the phone\",\"confidence\":70}";
	}

	private static String unknownField(final String pEntity, final String pField)
	{
		return "{\"entity\":\"" + pEntity + "\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanFallbackTest.key(pField)
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[],\"rationale\":\"x\",\"confidence\":50}";
	}

	private static LocalTool.Result submit(final MatchPlanLlmPass.Session pSession, final String pCall)
	{
		try
		{
			return pSession.proposeMatchPlan(MatchPlanFallbackTest.MAPPER.readTree(pCall));
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	/** Keys {@code mobile} and {@code nameKey}: twelve groups of two records that share both (SAFE both ways), over two source systems. */
	private static KeyFacts safeFacts(final boolean pSampled)
	{
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (int i = 0; i < 12; i++)
		{
			rows.add(new KeyFacts.Row("A", new String[] { "m" + i, "n" + i }));
			rows.add(new KeyFacts.Row("B", new String[] { "m" + i, "n" + i }));
		}
		return KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("mobile", "mobile", false), new KeyFacts.KeySpec("nameKey", "name", false)), pSampled);
	}

	/** The same two keys but the people sharing a phone have different names, every name unique: nothing is SAFE, the name key shares no pair. */
	private static KeyFacts unsafeFacts()
	{
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (int i = 0; i < 12; i++)
		{
			rows.add(new KeyFacts.Row("A", new String[] { "m" + i, "n" + i }));
			rows.add(new KeyFacts.Row("B", new String[] { "m" + i, "other" + i }));
		}
		return KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("mobile", "mobile", false), new KeyFacts.KeySpec("nameKey", "name", false)), false);
	}

	private static String treeOf(final JsonNode pArguments)
	{
		final StringBuilder out = new StringBuilder();
		for (final JsonNode node : pArguments.path("nodes"))
		{
			out.append(node.path("id").asText()).append(' ').append(node.path("function").asText()).append('[');
			for (final JsonNode field : node.path("fields"))
			{
				out.append(field.path("field").asText()).append('/').append(field.path("algorithm").asText().replace("ebx-addon-mame@", "")).append('/').append(field.path("minimumScore").asInt()).append(' ');
			}
			out.append("] -> ").append(node.path("onTrue").asText()).append(" / ").append(node.path("onFalse").asText()).append("; ");
		}
		return out.toString().trim();
	}

	// ------------------------------------------------------------------------------------------------------- tests

	private static void theUnknownTargetRefusalNamesTheDefinedIdsAndTheTwoFixes()
	{
		final MatchPlanLlmPass.Session session = MatchPlanFallbackTest.session(null, new ArrayList<MatchPlanLlmPass.ExistingPlan>());
		final LocalTool.Result r = MatchPlanFallbackTest.submit(session, MatchPlanFallbackTest.unknownTarget("firstName"));
		MatchPlanFallbackTest.check(r.isError && r.text.contains("UNKNOWN_BRANCH_TARGET: node 'N2'.onFalse = 'N7' is neither a leaf nor a known node id"), "the live refusal: " + r.text);
		MatchPlanFallbackTest.check(r.text.contains("(the tree defines N1 and N2; the leaves are MATCH, SUSPECTED, NOT_MATCHED)"), "names the ids the tree DOES define: " + r.text);
		MatchPlanFallbackTest.check(r.text.contains("Two fixes: define node 'N7' in nodes[], or point N2.onFalse to a leaf (SUSPECTED or NOT_MATCHED)."), "and the two ways out: " + r.text);
		// an onTrue branch may also end in MATCH
		final DecisionTreePlan.Plan plan = new DecisionTreePlan.Plan();
		plan.root = "N1";
		final DecisionTreePlan.Node n1 = new DecisionTreePlan.Node("N1", DecisionTreePlan.FUNCTION_ALL_FIELDS);
		n1.fields.add(new DecisionTreePlan.FieldRef("x", "ebx-addon-mame@exact"));
		n1.onTrue = "N9";
		n1.onFalse = DecisionTreePlan.LEAF_NOT_MATCHED;
		plan.nodes.add(n1);
		final DecisionTreePlan.ValidationResult result = DecisionTreePlan.validate(plan);
		MatchPlanFallbackTest.check(!result.ok && result.reasons.get(0).contains("point N1.onTrue to a leaf (MATCH, SUSPECTED or NOT_MATCHED)"), "onTrue lists MATCH too: " + result.reasons);
	}

	private static void aStructuralRefusalEndsTheEntityOnTheThirdTimeInARowAContentRefusalOnTheSecond()
	{
		final MatchPlanLlmPass.Session session = MatchPlanFallbackTest.session(null, new ArrayList<MatchPlanLlmPass.ExistingPlan>());
		final LocalTool.Result first = MatchPlanFallbackTest.submit(session, MatchPlanFallbackTest.unknownTarget("firstName"));
		final LocalTool.Result second = MatchPlanFallbackTest.submit(session, MatchPlanFallbackTest.unknownTarget("lastName"));
		MatchPlanFallbackTest.check(first.isError && second.isError && !first.text.contains("Giving up") && !second.text.contains("Giving up"),
			"the same structural refusal twice in a row is still a plain refusal (live run 497 ended here): " + second.text);
		final LocalTool.Result third = MatchPlanFallbackTest.submit(session, MatchPlanFallbackTest.unknownTarget("nameKey"));
		MatchPlanFallbackTest.check(third.isError && third.text.contains("Giving up on Person") && third.text.contains("3 times in a row"), "the third ends it: " + third.text);
		final MatchPlanLlmPass.Session.Attempt attempt = session.unplannedAttempts().get(0);
		MatchPlanFallbackTest.check(attempt.attempts == 3 && MatchPlanLlmPass.Session.ENDED_SAME_REFUSAL.equals(attempt.endedBy) && attempt.lastRefusal.contains("UNKNOWN_BRANCH_TARGET"),
			"the ledger: three proposals, ended by the same refusal, the last one kept");
		// a refusal of the content keeps the limit of lot M5
		final MatchPlanLlmPass.Session content = MatchPlanFallbackTest.session(null, new ArrayList<MatchPlanLlmPass.ExistingPlan>());
		MatchPlanFallbackTest.check(!MatchPlanFallbackTest.submit(content, MatchPlanFallbackTest.unknownField("Person", "zz")).text.contains("Giving up"), "content: the first is plain");
		MatchPlanFallbackTest.check(MatchPlanFallbackTest.submit(content, MatchPlanFallbackTest.unknownField("Person", "zz")).text.contains("2 times in a row"), "content: the second ends it");
		// the system prompt says so
		final String prompt = MatchPlanLlmPass.systemPrompt();
		MatchPlanFallbackTest.check(prompt.contains("the same refusal twice in a row (or three times in the run)") && prompt.contains("is tolerated three times in a row instead of two"),
			"the contract tells the model about the two classes");
	}

	private static void theRefusalClassesAreTheOnesDocumented()
	{
		MatchPlanFallbackTest.check(MatchPlanLlmPass.Session.isStructuralRefusal(MatchPlanLlmPass.Session.STRUCTURAL_REFUSAL_PREFIX + "UNKNOWN_BRANCH_TARGET: ..."), "validate reasons");
		MatchPlanFallbackTest.check(MatchPlanLlmPass.Session.isStructuralRefusal(MatchPlanLlmPass.Session.STRUCTURAL_REFUSAL_PREFIX + "CYCLE_DETECTED: the tree loops back on itself."), "a cycle");
		MatchPlanFallbackTest.check(MatchPlanLlmPass.Session.isStructuralRefusal("node id 'N2' is used more than once - every node needs a unique id."), "a duplicated id");
		MatchPlanFallbackTest.check(MatchPlanLlmPass.Session.isStructuralRefusal("node 'N2' needs both 'onTrue' and 'onFalse' - each either another node's id or one of MATCH/SUSPECTED/NOT_MATCHED."), "a missing branch");
		MatchPlanFallbackTest.check(MatchPlanLlmPass.Session.isStructuralRefusal("'root' must name the id of the first node to evaluate."), "a missing root");
		MatchPlanFallbackTest.check(!MatchPlanLlmPass.Session.isStructuralRefusal("'mobile' is not near-unique enough, among its filled values, for an exact, MATCH-terminal node"), "a field is content");
		MatchPlanFallbackTest.check(!MatchPlanLlmPass.Session.isStructuralRefusal("'Client' has child evidence Adresse: ...") && !MatchPlanLlmPass.Session.isStructuralRefusal(null), "a challenge is content");
		MatchPlanFallbackTest.check(MatchPlanLlmPass.Session.inARowLimit(MatchPlanLlmPass.Session.STRUCTURAL_REFUSAL_PREFIX + "x") == 3 && MatchPlanLlmPass.Session.inARowLimit("'x' is wrong") == 2, "the limits");
	}

	private static void theBuilderPutsTheSafePairAtTheRootThenEachKeyAloneThenTheChildKeys()
	{
		final MatchPlanFallback.Built plain = MatchPlanFallback.build(MatchPlanFallbackTest.person(MatchPlanFallbackTest.safeFacts(false), false), MatchPlanFallbackTest.vocabulary(), 2,
			"'mobile' is not near-unique enough");
		MatchPlanFallbackTest.check(plain.arguments != null, "built: " + plain.reason);
		final String tree = MatchPlanFallbackTest.treeOf(plain.arguments);
		MatchPlanFallbackTest.check(tree.equals("F1 ALL_FIELDS[mobile/exact/100 nameKey/exact/100 ] -> MATCH / F2; F2 ALL_FIELDS[mobile/exact/100 ] -> SUSPECTED / F3; F3 ALL_FIELDS[nameKey/exact/100 ] -> SUSPECTED / NOT_MATCHED;"),
			"the SAFE pair at the root, then each key alone, then NOT_MATCHED: " + tree);
		MatchPlanFallbackTest.check("F1".equals(plain.arguments.path("root").asText()) && plain.arguments.path("clusteringFields").size() == 0
			&& plain.arguments.path("confidence").asInt() == MatchPlanFallback.CONFIDENCE && "Person".equals(plain.arguments.path("entity").asText()), "root, no clustering field, confidence 40");
		for (final JsonNode node : plain.arguments.path("nodes"))
		{
			for (final JsonNode field : node.path("fields"))
			{
				MatchPlanFallbackTest.check("NOT_MATCH".equals(field.path("bothNullValueHandling").asText()) && "NOT_MATCH".equals(field.path("oneNullValueHandling").asText()), "nulls never match");
			}
		}
		final String rationale = plain.arguments.path("rationale").asText();
		MatchPlanFallbackTest.check(rationale.startsWith("Factory fallback plan, not a proposal of the language model: the model made 2 proposal(s) for 'Person' and none was accepted (last refusal: \"'mobile' is not near-unique enough\")"),
			"the rationale says what it is and quotes the last refusal: " + rationale);
		MatchPlanFallbackTest.check(rationale.contains("SAFE pair: 12 pair(s) share mobile, 12 of the 12 that can be compared also share nameKey, 0 differ") && rationale.contains("MATCH")
			&& rationale.contains("Asking for another proposal replaces it with the model's plan."), "the measured justification and the way to replace it: " + rationale);
		MatchPlanFallbackTest.check(plain.summary.equals("MATCH on mobile + nameKey; SUSPECTED on mobile, nameKey (each alone)"), "the summary for the note: " + plain.summary);

		final MatchPlanFallback.Built withChild = MatchPlanFallback.build(MatchPlanFallbackTest.person(MatchPlanFallbackTest.safeFacts(false), true), MatchPlanFallbackTest.vocabulary(), 3, "x");
		final String childTree = MatchPlanFallbackTest.treeOf(withChild.arguments);
		MatchPlanFallbackTest.check(childTree.endsWith("F3 ALL_FIELDS[nameKey/exact/100 ] -> SUSPECTED / F4; F4 ALL_FIELDS[mobile/exact/100 addressKeys/exactFullText/50 ] -> SUSPECTED / NOT_MATCHED;"),
			"the child keys last, never alone (lot M14: beside an exact key of the record), exactFullText at the ceiling (50 when the largest child count is unknown): " + childTree);
		MatchPlanFallbackTest.check(withChild.summary.endsWith("SUSPECTED on mobile + shared child keys addressKeys (exactFullText 50)"), "named in the summary: " + withChild.summary);
		final MatchPlanFallback.Built noFullText = MatchPlanFallback.build(MatchPlanFallbackTest.person(MatchPlanFallbackTest.safeFacts(false), true), MatchPlanFallbackTest.vocabularyWithoutFullText(), 3, "x");
		MatchPlanFallbackTest.check(noFullText.arguments.path("nodes").size() == 3, "no exactFullText in the vocabulary: no child-keys node");
	}

	private static void withoutASafePairTheChainStartsWithTheKeysAlone()
	{
		final MatchPlanFallback.Built built = MatchPlanFallback.build(MatchPlanFallbackTest.person(MatchPlanFallbackTest.unsafeFacts(), false), MatchPlanFallbackTest.vocabulary(), 4, "x");
		MatchPlanFallbackTest.check(built.arguments != null, "built: " + built.reason);
		final String tree = MatchPlanFallbackTest.treeOf(built.arguments);
		MatchPlanFallbackTest.check(tree.equals("F1 ALL_FIELDS[mobile/exact/100 ] -> SUSPECTED / NOT_MATCHED;"),
			"no MATCH root, the phone alone to a review, the name key (shared by no pair) has no node: " + tree);
		MatchPlanFallbackTest.check(!built.arguments.path("rationale").asText().contains("- MATCH") && built.arguments.path("rationale").asText().contains("(mobile) are shown to a steward - SUSPECTED"),
			"the rationale promises no MATCH: " + built.arguments.path("rationale").asText());
	}

	private static void coarseKeysSourcesOfAKeyAndUnknownAttributesNeverGetANode()
	{
		// keys: a phone (12 groups of 2), a city every record shares (276 pairs for 24 records: a grouping, not an identity), the source 'name' of 'nameKey', and a key the entity does not carry
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (int i = 0; i < 12; i++)
		{
			rows.add(new KeyFacts.Row("A", new String[] { "m" + i, "Paris", "Name " + i, "n" + i, "g" + i }));
			rows.add(new KeyFacts.Row("B", new String[] { "m" + i, "Paris", "Name " + i, "n" + i, "g" + i }));
		}
		final KeyFacts facts = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("mobile", "mobile", false), new KeyFacts.KeySpec("city", "city", false),
			new KeyFacts.KeySpec("name", "name", false), new KeyFacts.KeySpec("nameKey", "name", false), new KeyFacts.KeySpec("ghost", "ghost", false)), false);
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanFallbackTest.plain("mobile", 12, 1.0));
		attributes.add(MatchPlanFallbackTest.plain("city", 1, 1.0));
		attributes.add(MatchPlanFallbackTest.plain("name", 12, 1.0));
		attributes.add(MatchPlanFallbackTest.matchKey("nameKey", "name", 12, 1.0));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Person", "Person", 24, attributes);
		entity.keyFacts = facts;
		final List<MatchPlanFallback.Key> keys = MatchPlanFallback.eligibleKeys(entity, facts, true);
		final List<String> names = new ArrayList<String>();
		for (final MatchPlanFallback.Key key : keys)
		{
			names.add(key.name);
		}
		MatchPlanFallbackTest.check(names.equals(Arrays.asList("mobile", "nameKey")), "the city (too many pairs), the source of a key and the unknown attribute are out: " + names);
		final String tree = MatchPlanFallbackTest.treeOf(MatchPlanFallback.build(entity, MatchPlanFallbackTest.vocabulary(), 1, "x").arguments);
		MatchPlanFallbackTest.check(tree.startsWith("F1 ALL_FIELDS[mobile/exact/100 nameKey/exact/100 ] -> MATCH / F2;") && !tree.contains("city") && !tree.contains("[name/"),
			"and the tree only uses the two: " + tree);
	}

	private static void noFactsASampleOrAChildEntityGiveNoFallback()
	{
		final MatchPlanFallback.Built none = MatchPlanFallback.build(MatchPlanFallbackTest.person(null, false), MatchPlanFallbackTest.vocabulary(), 2, "x");
		MatchPlanFallbackTest.check(none.arguments == null && none.reason.contains("no key facts were measured on 'Person'"), "no facts: " + none.reason);
		final MatchPlanFallback.Built sampled = MatchPlanFallback.build(MatchPlanFallbackTest.person(MatchPlanFallbackTest.safeFacts(true), false), MatchPlanFallbackTest.vocabulary(), 2, "x");
		MatchPlanFallbackTest.check(sampled.arguments == null && sampled.reason.contains("sample of the table"), "a sample: " + sampled.reason);
		final MatchPlanLlmPass.EntityInfo child = new MatchPlanLlmPass.EntityInfo("Adresse", "Adresse", 300, new ArrayList<MatchPlanLlmPass.AttributeInfo>(), "Person", "person");
		child.keyFacts = MatchPlanFallbackTest.safeFacts(false);
		final MatchPlanFallback.Built childBuilt = MatchPlanFallback.build(child, MatchPlanFallbackTest.vocabulary(), 2, "x");
		MatchPlanFallbackTest.check(childBuilt.arguments == null && childBuilt.reason.contains("child entity of 'Person'"), "a child follows its parent: " + childBuilt.reason);
		// facts whose keys the entity does not carry at all
		final MatchPlanLlmPass.EntityInfo bare = new MatchPlanLlmPass.EntityInfo("Person", "Person", 24, new ArrayList<MatchPlanLlmPass.AttributeInfo>());
		bare.keyFacts = MatchPlanFallbackTest.safeFacts(false);
		final MatchPlanFallback.Built noKey = MatchPlanFallback.build(bare, MatchPlanFallbackTest.vocabulary(), 2, "x");
		MatchPlanFallbackTest.check(noKey.arguments == null && noKey.reason.contains("offer no exact key of the record itself") && noKey.reason.contains("mobile, nameKey were measured"), "no usable key: " + noKey.reason);
	}

	private static void theSessionProposesTheFallbackForAnEndedEntityThroughTheSameChecks() throws Exception
	{
		final MatchPlanLlmPass.Session session = MatchPlanFallbackTest.session(MatchPlanFallbackTest.safeFacts(false), new ArrayList<MatchPlanLlmPass.ExistingPlan>()).requireFilteredCandidateSearch();
		MatchPlanFallbackTest.check(MatchPlanFallbackTest.submit(session, MatchPlanFallbackTest.phoneMatch("firstName")).isError, "first refusal");
		// lot M13: the breaker compares the WHOLE set of violations of a proposal, so the same mistakes twice means the same set (a different second node would name a different field in the path rule)
		final LocalTool.Result second = MatchPlanFallbackTest.submit(session, MatchPlanFallbackTest.phoneMatch("firstName"));
		MatchPlanFallbackTest.check(second.text.contains("Giving up on Person"), "the model's proposals for Person ended: " + second.text);
		MatchPlanFallbackTest.check(!MatchPlanFallbackTest.submit(session, "{\"entity\":\"Store\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":["
			+ MatchPlanFallbackTest.key("storeCode") + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[],\"rationale\":\"the store code\",\"confidence\":70}").isError, "Store accepted");
		final int notesBefore = session.notes().size();

		final List<MatchPlanLlmPass.Session.FallbackOutcome> outcomes = session.proposeFallbacks();
		MatchPlanFallbackTest.check(outcomes.size() == 1 && "Person".equals(outcomes.get(0).entity) && outcomes.get(0).proposed && outcomes.get(0).reason.isEmpty() && outcomes.get(0).attempts == 2
			&& outcomes.get(0).lastRefusal.contains("'mobile' is not near-unique enough") && MatchPlanLlmPass.Session.ENDED_SAME_REFUSAL.equals(outcomes.get(0).endedBy),
			"one fallback, for the ended entity only, with the model's history: " + outcomes.size() + (outcomes.isEmpty() ? "" : " " + outcomes.get(0).reason));
		MatchPlanFallbackTest.check(session.createdPlans().size() == 2, "Store's plan and the fallback");
		MatchPlanLlmPass.CreatedPlan plan = null;
		for (final MatchPlanLlmPass.CreatedPlan candidate : session.createdPlans())
		{
			if ("Person".equals(candidate.entityName))
			{
				plan = candidate;
			}
		}
		MatchPlanFallbackTest.check(plan != null && plan.isFallback() && MatchPlanLlmPass.CreatedPlan.ORIGIN_RULE.equals(plan.origin) && plan.confidence == MatchPlanFallback.CONFIDENCE,
			"origin RULE, confidence 40");
		final JsonNode requirements = MatchPlanFallbackTest.MAPPER.readTree(plan.requirementsJson);
		MatchPlanFallbackTest.check(requirements.path("fallback").path("afterRefusedProposals").asInt() == 2 && requirements.path("fallback").path("lastRefusal").asText().contains("not near-unique")
			&& MatchPlanLlmPass.Session.ENDED_SAME_REFUSAL.equals(requirements.path("fallback").path("endedBy").asText()), "requirements.fallback carries the history: " + requirements.path("fallback"));
		MatchPlanFallbackTest.check("F1".equals(requirements.path("decisionTree").path("root").asText()) && requirements.path("decisionTree").path("nodes").size() == 3
			&& requirements.path("keyFacts").isObject(), "the tree and the facts are stored like a model plan's");
		MatchPlanFallbackTest.check(CandidateFilterCheck.check(requirements).filtered(), "every path starts with an exact key: FILTERED");
		MatchPlanFallbackTest.check(plan.rationale.startsWith("Factory fallback plan") && plan.rationale.contains("MATCH path F1 (mobile + nameKey) is justified by the measured data: 12 pair(s) of records share mobile"),
			"the pass accepted the MATCH root on the measured justification and said so in the rationale: " + plan.rationale);
		MatchPlanFallbackTest.check(session.unplannedAttempts().isEmpty(), "Person has a plan now: no unplanned attempt");
		MatchPlanFallbackTest.check(session.notes().size() == notesBefore + 1
			&& session.notes().get(notesBefore).equals("Person: the Factory proposed its fallback plan, built from the measured key facts, after 2 refused proposal(s) of the model (MATCH on mobile + nameKey; SUSPECTED on mobile, nameKey (each alone))."),
			"one note says so: " + session.notes());
		MatchPlanFallbackTest.check(session.proposeFallbacks().isEmpty() && session.fallbackOutcomes().size() == 1, "tried once per entity");
		MatchPlanFallbackTest.check(!session.stoppedEarly(), "never a run-wide stop");
	}

	private static void aFallbackThatFailsTheChecksLeavesTheEntityWithoutAPlanAndSaysWhy()
	{
		// a plan a person decided: the model's proposals are refused for it, and so is the Factory's fallback - by the same check
		final List<MatchPlanLlmPass.ExistingPlan> prior = new ArrayList<MatchPlanLlmPass.ExistingPlan>();
		prior.add(new MatchPlanLlmPass.ExistingPlan("Person", "ACCEPTED", "USER"));
		final MatchPlanLlmPass.Session session = MatchPlanFallbackTest.session(MatchPlanFallbackTest.safeFacts(false), prior);
		MatchPlanFallbackTest.check(MatchPlanFallbackTest.submit(session, MatchPlanFallbackTest.phoneMatch("firstName")).text.contains("already has a matching plan"), "refused once");
		MatchPlanFallbackTest.check(MatchPlanFallbackTest.submit(session, MatchPlanFallbackTest.phoneMatch("lastName")).text.contains("Giving up on Person"), "ended");
		final List<MatchPlanLlmPass.Session.FallbackOutcome> outcomes = session.proposeFallbacks();
		MatchPlanFallbackTest.check(outcomes.size() == 1 && !outcomes.get(0).proposed && outcomes.get(0).reason.startsWith("refused by the same checks as a model plan: 'Person' already has a matching plan (status ACCEPTED, origin USER)"),
			"the fallback met the same refusal: " + (outcomes.isEmpty() ? "none" : outcomes.get(0).reason));
		MatchPlanFallbackTest.check(session.createdPlans().isEmpty(), "no plan");
		final MatchPlanLlmPass.Session.Attempt attempt = session.unplannedAttempts().get(0);
		MatchPlanFallbackTest.check(attempt.attempts == 2 && attempt.fallbackRefusal.contains("already has a matching plan"), "the ledger keeps the fallback's refusal next to the model's: " + attempt.fallbackRefusal);
		boolean noted = false;
		for (final String note : session.notes())
		{
			noted |= note.startsWith("Person: no Factory fallback plan - refused by the same checks as a model plan");
		}
		MatchPlanFallbackTest.check(noted, "and a note says so: " + session.notes());
		// an entity the model never proposed for gets no fallback either
		final MatchPlanLlmPass.Session silent = MatchPlanFallbackTest.session(MatchPlanFallbackTest.safeFacts(false), new ArrayList<MatchPlanLlmPass.ExistingPlan>());
		MatchPlanFallbackTest.check(silent.proposeFallbacks().isEmpty() && silent.createdPlans().isEmpty(), "nothing proposed, nothing refused: no fallback");
	}

	/**
	 * The B2C fixture of lot M3 ({@code b2c_person_keys.tsv}, the 534 persons of the e2e scenario reduced to their keys) reproduces the SAFE pairs the refusal of live run 497
	 * quoted (mobileE164 + fullNameKey 565 of 565, emailKey + fullNameKey 341 of 341): the tree the Factory would have proposed for Client.
	 */
	private static void theB2cPersonFactsGiveTheTreeOfRun497() throws Exception
	{
		final KeyFacts facts = B2cKeyFixtureTest.facts();
		MatchPlanFallbackTest.check(facts.agreement("mobileE164", "fullNameKey").agree == 565 && facts.agreement("emailKey", "fullNameKey").agree == 341, "the fixture is run 497's data");
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanFallbackTest.matchKey("emailKey", "email", 207, 0.73));
		attributes.add(MatchPlanFallbackTest.matchKey("fullNameKey", "name", 187, 1.0));
		attributes.add(MatchPlanFallbackTest.plain("mobileE164", 212, 1.0));
		attributes.add(MatchPlanFallbackTest.plain("birthDate", 195, 0.56));
		attributes.add(MatchPlanFallbackTest.plain("firstName", 28, 1.0));
		attributes.add(MatchPlanFallbackTest.plain("lastName", 27, 1.0));
		attributes.add(MatchPlanFallbackTest.childKeys("addressKeys", "Adresse"));
		final MatchPlanLlmPass.EntityInfo client = new MatchPlanLlmPass.EntityInfo("Client", "Client", 534, attributes);
		client.keyFacts = facts;
		final MatchPlanFallback.Built built = MatchPlanFallback.build(client, MatchPlanFallbackTest.vocabulary(), 5,
			"the proposed decision tree is not valid: UNKNOWN_BRANCH_TARGET: node 'N5'.onFalse = 'N7' is neither a leaf nor a known node id.");
		MatchPlanFallbackTest.check(built.arguments != null, "built: " + built.reason);
		final String tree = MatchPlanFallbackTest.treeOf(built.arguments);
		System.out.println("Client fallback tree (run 497 facts): " + tree);
		MatchPlanFallbackTest.check(tree.equals("F1 ALL_FIELDS[mobileE164/exact/100 fullNameKey/exact/100 ] -> MATCH / F2; F2 ALL_FIELDS[emailKey/exact/100 ] -> SUSPECTED / F3; "
			+ "F3 ALL_FIELDS[mobileE164/exact/100 ] -> SUSPECTED / F4; F4 ALL_FIELDS[fullNameKey/exact/100 addressKeys/exactFullText/50 ] -> SUSPECTED / NOT_MATCHED;"),
			"the phone + name pair (565 comparable pairs, the most) at the root, the e-mail then the phone alone to a review, the address keys last and never alone (lot M14: beside the name key, the key that does not already stand alone); the name key alone (namesakes: not SAFE as a key), "
				+ "the birth date (two people born the same day: not SAFE as a key) and the first/last names (coarse) get no node: " + tree);
		// and the pass accepts it as it accepts a model plan
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Client", client);
		final MatchPlanLlmPass.Session session = new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(),
			MatchPlanFallbackTest.vocabulary()).requireFilteredCandidateSearch();
		final String dangling = "{\"entity\":\"Client\",\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanFallbackTest.key("emailKey")
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N7\"}],\"clusteringFields\":[],\"rationale\":\"x\",\"confidence\":60}";
		for (int i = 0; i < 3; i++)
		{
			MatchPlanFallbackTest.check(MatchPlanFallbackTest.submit(session, dangling).isError, "refused " + (i + 1));
		}
		final List<MatchPlanLlmPass.Session.FallbackOutcome> outcomes = session.proposeFallbacks();
		MatchPlanFallbackTest.check(outcomes.size() == 1 && outcomes.get(0).proposed, "accepted by the same checks: " + (outcomes.isEmpty() ? "none" : outcomes.get(0).reason));
		final MatchPlanLlmPass.CreatedPlan plan = session.createdPlans().get(0);
		final JsonNode stored = MatchPlanFallbackTest.MAPPER.readTree(plan.requirementsJson);
		MatchPlanFallbackTest.check(stored.path("decisionTree").path("nodes").size() == 4 && stored.path("fallback").path("afterRefusedProposals").asInt() == 3 && plan.confidence == 40
			&& plan.rationale.contains("MATCH path F1 (mobileE164 + fullNameKey) is justified by the measured data: 565 pair(s)"), "stored with the measured justification: " + plan.rationale);
		MatchPlanFallbackTest.check(plan.rationale.contains("Reach:") || !plan.rationale.contains("out of reach"), "the reach finding, when any, is a note of the rationale, never a refusal");
	}

	/**
	 * Lot M10a: the stored key facts of the 7th e2e run (plan 74). The name key agrees 694 of 694 with the FIRST NAME, a component of itself - SAFE as a key before the partner had to
	 * be selective - so the fallback of lot M9 would have put {@code nomCompletKey} alone in a SUSPECTED node: 129 namesake pairs for a steward. Now only keys whose sharing
	 * goes with a selective independent key at 99% get a node, and the whole tree passes the path rule.
	 */
	private static void theFactsOfRun7NeverPutTheNameKeyAloneInTheFallbackAndThePathRuleAcceptsTheTree() throws Exception
	{
		final KeyFacts facts = KeyFacts.fromJson(PathEvidenceRuleTest.resource("run7_keyfacts.json"));
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceRuleTest.run7Entity(facts);
		final MatchPlanFallback.Built built = MatchPlanFallback.build(entity, MatchPlanFallbackTest.vocabulary(), 3, "a refusal");
		MatchPlanFallbackTest.check(built.arguments != null, "built: " + built.reason);
		final String tree = MatchPlanFallbackTest.treeOf(built.arguments);
		System.out.println("Personne fallback tree (run 7 facts): " + tree);
		MatchPlanFallbackTest.check(!tree.contains("[nomCompletKey/exact/100 ] -> SUSPECTED") && !tree.contains("[nom/") && !tree.contains("[prenom/"),
			"no SUSPECTED node on the name key alone, none on the names: " + tree);
		MatchPlanFallbackTest.check(tree.contains("[telephoneE164/exact/100 ] -> SUSPECTED") && tree.contains("[emailKey/exact/100 ] -> SUSPECTED") && tree.contains("addressKeys/exactFullText/50"),
			"the phone, the e-mail and the address keys each get their node: " + tree);
		final com.fasterxml.jackson.databind.node.ObjectNode wrapped = MatchPlanFallbackTest.MAPPER.createObjectNode();
		wrapped.put("root", built.arguments.path("root").asText());
		wrapped.set("nodes", built.arguments.path("nodes"));
		final PathEvidenceRule.Result result = PathEvidenceRule.check(entity, DecisionTreePlan.fromJson(wrapped));
		MatchPlanFallbackTest.check(result.active && result.ok(), "every path of the fallback requires a condition that identifies a record");
	}
}
