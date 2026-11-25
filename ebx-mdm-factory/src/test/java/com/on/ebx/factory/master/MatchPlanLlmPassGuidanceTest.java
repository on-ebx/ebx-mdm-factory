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
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot M13 ({@code docs/MATCHING.md} &sect;66), on the entity {@code Client} of the 9th live run of the B2C master scenario (run 542, 2026-10-18,
 * 534 person records, three sources, the keys {@code mobileE164}, {@code emailKey}, {@code personNameKey}, child keys {@code addressKeys}, Adresse as child evidence):
 * <ul>
 * <li>A - a MATCH is judged on the conjunction of the exact conditions met along its path: the tree the model proposed twice and the pass refused twice (step 10 of the run) is accepted;
 * a single-node MATCH on a derived key, an ancestor left through {@code onFalse}, an ancestor whose null handling lets a missing value pass are still refused;</li>
 * <li>B - every violation of a proposal in ONE numbered answer, the breaker comparing the whole set;</li>
 * <li>C - the checklist of the entity's own facts in the prompt, before the first proposal;</li>
 * <li>D - the Factory's starting plan in the prompt, accepted as it is when resubmitted unchanged, never replacing a plan of the model that passes.</li>
 * </ul>
 */
public final class MatchPlanLlmPassGuidanceTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanLlmPassGuidanceTest.theTreeOfRun542IsAcceptedBecauseItsMatchPathRequiresBothKeys();
		MatchPlanLlmPassGuidanceTest.aSingleNodeMatchOnADerivedKeyIsStillRefused();
		MatchPlanLlmPassGuidanceTest.anAncestorLeftThroughOnFalseOrLettingAMissingValuePassRequiresNothing();
		MatchPlanLlmPassGuidanceTest.theOtherRulesOfAPathReadItsConjunctionToo();
		MatchPlanLlmPassGuidanceTest.theViolationsOfAProposalAreToldTogether();
		MatchPlanLlmPassGuidanceTest.theBreakerComparesTheWholeSet();
		MatchPlanLlmPassGuidanceTest.theFirstProposalOfRun542DrawsItsViolationsInOneAnswer();
		MatchPlanLlmPassGuidanceTest.combinedViolationsAreNumberedAndSingleOnesUntouched();
		MatchPlanLlmPassGuidanceTest.theChecklistStatesTheFactsOfTheEntity();
		MatchPlanLlmPassGuidanceTest.theChecklistIsSilentWhenNothingIsMeasured();
		MatchPlanLlmPassGuidanceTest.theStartingPlanIsGivenAndAcceptedAsItIsButNeverReplacesAPlanOfTheModel();
		System.out.println("MatchPlanLlmPassGuidanceTest OK");
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(
			Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@exactFullText", "ebx-addon-mame@jarowinkler"), Arrays.asList("MATCH", "NOT_MATCH"),
			Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.AttributeInfo plain(final String pName, final String pType, final int pDistinct, final double pFill)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, pType, 255, null, Double.valueOf(pFill), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true,
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
			Collections.<String>emptyList(), Collections.<String>emptyList(), null, null, null, null, null, null, null, null, null,
			MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS, pChild);
	}

	/** Client of run 542: the three keys the Factory derives, the raw names and the birth date, child keys of the addresses, Adresse as child evidence (at most 2 per client). */
	private static MatchPlanLlmPass.EntityInfo client(final KeyFacts pFacts)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanLlmPassGuidanceTest.matchKey("emailKey", "email", 207, 0.73));
		attributes.add(MatchPlanLlmPassGuidanceTest.matchKey("personNameKey", "name", 187, 1.0));
		attributes.add(MatchPlanLlmPassGuidanceTest.matchKey("mobileE164", "mobile", 212, 1.0));
		attributes.add(MatchPlanLlmPassGuidanceTest.plain("firstName", "STRING", 150, 1.0));
		attributes.add(MatchPlanLlmPassGuidanceTest.plain("lastName", "STRING", 150, 1.0));
		attributes.add(MatchPlanLlmPassGuidanceTest.plain("birthDate", "DATE", 195, 0.56));
		attributes.add(MatchPlanLlmPassGuidanceTest.childKeys("addressKeys", "Adresse"));
		final List<MatchPlanLlmPass.AttributeInfo> childAttributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		childAttributes.add(MatchPlanLlmPassGuidanceTest.plain("fullAddress", "STRING", 204, 1.0));
		final List<MatchPlanLlmPass.RelationChildInfo> relationChildren = Collections.singletonList(
			new MatchPlanLlmPass.RelationChildInfo("Adresse", childAttributes, Integer.valueOf(1), Integer.valueOf(2), Double.valueOf(1.0), Double.valueOf(0.0)));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Client", "Client", 534, attributes, null, null, null, null, null, relationChildren);
		entity.keyFacts = pFacts;
		return entity;
	}

	private static MatchPlanLlmPass.Session session(final KeyFacts pFacts)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Client", MatchPlanLlmPassGuidanceTest.client(pFacts));
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(), MatchPlanLlmPassGuidanceTest.vocabulary())
			.requireFilteredCandidateSearch();
	}

	private static String key(final String pName)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String addressKeys(final int pMinimumScore)
	{
		return "{\"field\":\"addressKeys\",\"algorithm\":\"ebx-addon-mame@exactFullText\",\"minimumScore\":" + pMinimumScore
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

	private static String call(final String pRoot, final String pNodes, final String pClustering)
	{
		return "{\"entity\":\"Client\",\"root\":\"" + pRoot + "\",\"nodes\":[" + pNodes + "],\"clusteringFields\":[" + pClustering + "],\"rationale\":\"keys first, weaker paths after.\",\"confidence\":80}";
	}

	/** A plan of the model that passes every check: the phone then the e-mail for a MATCH, each alone for a review, the address keys last; the child evidence is challenged once. */
	private static String validPlan()
	{
		return MatchPlanLlmPassGuidanceTest.call("A1",
			MatchPlanLlmPassGuidanceTest.node("A1", MatchPlanLlmPassGuidanceTest.key("mobileE164"), "A2", "A3") + ","
				+ MatchPlanLlmPassGuidanceTest.node("A2", MatchPlanLlmPassGuidanceTest.key("emailKey"), "MATCH", "A3") + ","
				+ MatchPlanLlmPassGuidanceTest.node("A3", MatchPlanLlmPassGuidanceTest.key("mobileE164"), "SUSPECTED", "A4") + ","
				+ MatchPlanLlmPassGuidanceTest.node("A4", MatchPlanLlmPassGuidanceTest.key("emailKey"), "SUSPECTED", "A5") + ","
				+ MatchPlanLlmPassGuidanceTest.node("A5", MatchPlanLlmPassGuidanceTest.key("personNameKey") + "," + MatchPlanLlmPassGuidanceTest.addressKeys(50), "SUSPECTED", "NOT_MATCHED"),
			"\"mobileE164\",\"emailKey\",\"addressKeys\"");
	}

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchPlanLlmPassGuidanceTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static LocalTool.Result submit(final MatchPlanLlmPass.Session pSession, final String pCall)
	{
		return pSession.proposeMatchPlan(MatchPlanLlmPassGuidanceTest.parse(pCall));
	}

	private static Path testResource(final String pName)
	{
		final String sourceDir = System.getProperty("mdmf.sourceDir");
		return sourceDir == null ? Paths.get("ebx-mdm-factory/src/test/resources/com/on/ebx/factory/" + pName)
			: Paths.get(sourceDir).resolve("../../test/resources/com/on/ebx/factory/" + pName).normalize();
	}

	/** The proposal of the live run (step {@code pStep} of {@code AgentRun} 542, read over Data Services; field names and counts, no value). */
	private static String liveProposal(final int pStep)
	{
		try
		{
			return new String(Files.readAllBytes(MatchPlanLlmPassGuidanceTest.testResource("master/run542_step" + pStep + "_client_proposal.json")), StandardCharsets.UTF_8);
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError(ex);
		}
	}

	/**
	 * The proposal of step 10 up to G8 (G8 ending in NOT_MATCHED): G1 and G2, and G3 to G6, G8, are the model's own, to the letter; the nodes G9 to G15 (the name-similarity and child-evidence
	 * part) are left out. Lot M14: G7 was {@code addressKeys} ALONE -> SUSPECTED, which child evidence may no longer do; it carries {@code personNameKey} beside it here.
	 */
	private static String run542UpToG8()
	{
		final com.fasterxml.jackson.databind.node.ObjectNode arguments = (com.fasterxml.jackson.databind.node.ObjectNode) MatchPlanLlmPassGuidanceTest.parse(MatchPlanLlmPassGuidanceTest.liveProposal(10));
		final com.fasterxml.jackson.databind.node.ArrayNode kept = arguments.arrayNode();
		for (final JsonNode node : arguments.path("nodes"))
		{
			final String id = node.path("id").asText();
			if (id.length() == 2 && "G1G2G3G4G5G6G7G8".contains(id))
			{
				if ("G8".equals(id))
				{
					((com.fasterxml.jackson.databind.node.ObjectNode) node).put("onFalse", "NOT_MATCHED");
				}
				if ("G7".equals(id))
				{
					((com.fasterxml.jackson.databind.node.ArrayNode) node.path("fields")).insert(0, MatchPlanLlmPassGuidanceTest.parse(MatchPlanLlmPassGuidanceTest.key("personNameKey")));
				}
				kept.add(node);
			}
		}
		arguments.set("nodes", kept);
		return arguments.toString();
	}

	/** The facts of the scenario: the 534 person records reduced to their keys ({@code matching/b2c_person_keys.tsv}), under the key names of run 542. */
	static KeyFacts facts()
	{
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		try
		{
			final List<String> all = Files.readAllLines(MatchPlanLlmPassGuidanceTest.testResource("matching/b2c_person_keys.tsv"), StandardCharsets.UTF_8);
			for (int i = 1; i < all.size(); i++)
			{
				final String[] cells = all.get(i).split("\t", -1);
				for (int c = 0; c < cells.length; c++)
				{
					if (cells[c].isEmpty())
					{
						cells[c] = null;
					}
				}
				rows.add(new KeyFacts.Row(cells[0], new String[] { cells[2], cells[3], cells[4], cells[6], cells[7] }));
			}
		}
		catch (final java.io.IOException ex)
		{
			throw new AssertionError(ex);
		}
		return KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("emailKey", "email", false), new KeyFacts.KeySpec("personNameKey", "name", false),
			new KeyFacts.KeySpec("mobileE164", "mobile", false), new KeyFacts.KeySpec("addressKeys", "addressKeys", true), new KeyFacts.KeySpec("birthDate", "birthDate", false)), false);
	}

	// ------------------------------------------------------------------------------------------------------- A

	private static void theTreeOfRun542IsAcceptedBecauseItsMatchPathRequiresBothKeys()
	{
		// the proposal the model made twice (steps 10 and 12 of the run): G1 [mobileE164] onTrue G2; G2 [emailKey] onTrue MATCH - refused twice, on emailKey "alone", by a rule that
		// looked at the last node only; the same for G3 [mobileE164] > G4 [personNameKey]. As the model wrote it (15 nodes) the tree is no longer refused for that: what is left is the
		// SIZE the Factory's copies give it (74 nodes against a cap of 64) - found on the way, told with its cause
		final LocalTool.Result whole = MatchPlanLlmPassGuidanceTest.submit(MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts()), MatchPlanLlmPassGuidanceTest.liveProposal(10));
		check(whole.isError && !whole.text.contains("MATCH-terminal") && whole.text.contains("TOO_MANY_NODES: 74 nodes, the cap is 64") && whole.text.contains("Your proposal had 15 node(s)")
			&& whole.text.contains("Reach each node from ONE branch only"), "the MATCH-terminal rule no longer refuses it; the copies of its shared nodes do, with the reason: " + whole.text);
		// the same tree up to G8, G1 and G2 exactly as the model wrote them: the shared nodes it still has are copied within the cap. The child evidence it does not use is challenged once -
		// the only thing the first answer says - and the resubmission is accepted
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		final LocalTool.Result challenge = MatchPlanLlmPassGuidanceTest.submit(session, MatchPlanLlmPassGuidanceTest.run542UpToG8());
		check(challenge.isError && challenge.text.startsWith("'Client' has child evidence Adresse") && !challenge.text.contains("all reported at once"),
			"the first answer is the one-shot child-evidence challenge, alone: " + challenge.text);
		final LocalTool.Result result = MatchPlanLlmPassGuidanceTest.submit(session, MatchPlanLlmPassGuidanceTest.run542UpToG8());
		check(!result.isError, "the G1 > G2 tree of run 542 passes: " + result.text);
		final MatchPlanLlmPass.CreatedPlan plan = session.createdPlans().get(0);
		final JsonNode tree = MatchPlanLlmPassGuidanceTest.parse(plan.requirementsJson).path("decisionTree");
		int matchLeaves = 0;
		for (final JsonNode node : tree.path("nodes"))
		{
			matchLeaves += "MATCH".equals(node.path("onTrue").asText()) ? 1 : 0;
		}
		check(matchLeaves == 3, "the MATCH leaves are kept (the e-mail pair once, the name pair twice: its node is copied, being reached from two branches): " + matchLeaves);
		check(plan.rationale.contains("MATCH path G1 > G2 (") && plan.rationale.contains("is justified by the measured data") && plan.rationale.contains("341 of the 341 that can be compared"),
			"the rationale says which path was justified and by what: " + plan.rationale);
	}

	private static void aSingleNodeMatchOnADerivedKeyIsStillRefused()
	{
		// the same e-mail node, reached without the phone: emailKey alone is a normalised copy of a business field
		final String alone = MatchPlanLlmPassGuidanceTest.call("N1", MatchPlanLlmPassGuidanceTest.node("N1", MatchPlanLlmPassGuidanceTest.key("emailKey"), "MATCH", "N2") + ","
			+ MatchPlanLlmPassGuidanceTest.node("N2", MatchPlanLlmPassGuidanceTest.key("mobileE164"), "SUSPECTED", "NOT_MATCHED"), "\"emailKey\",\"mobileE164\"");
		final LocalTool.Result result = MatchPlanLlmPassGuidanceTest.submit(MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts()), alone);
		check(result.isError && result.text.contains("'emailKey' is a matching key derived from 'email'") && result.text.contains("cannot terminate a MATCH-terminal node on its own"),
			"refused as before: " + result.text);
		check(result.text.contains("Pair it with") && result.text.contains("The path to the MATCH of node 'N1' requires emailKey")
			&& result.text.contains("keys count together when they sit on the SAME path"), "and the refusal says what THIS path requires and how the pair is made: " + result.text);
	}

	private static void anAncestorLeftThroughOnFalseOrLettingAMissingValuePassRequiresNothing()
	{
		// the phone is the node ABOVE the e-mail, but the e-mail node is reached through its onFalse: a pair that failed the phone says nothing about the phone
		final String viaFalse = MatchPlanLlmPassGuidanceTest.call("N1", MatchPlanLlmPassGuidanceTest.node("N1", MatchPlanLlmPassGuidanceTest.key("mobileE164"), "SUSPECTED", "N2") + ","
			+ MatchPlanLlmPassGuidanceTest.node("N2", MatchPlanLlmPassGuidanceTest.key("emailKey"), "MATCH", "NOT_MATCHED"), "\"mobileE164\",\"emailKey\"");
		final LocalTool.Result viaFalseResult = MatchPlanLlmPassGuidanceTest.submit(MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts()), viaFalse);
		check(viaFalseResult.isError && viaFalseResult.text.contains("cannot terminate a MATCH-terminal node on its own") && viaFalseResult.text.contains("requires emailKey;"),
			"an ancestor left through onFalse contributes nothing: " + viaFalseResult.text);
		// the phone node lets a missing value pass (oneNullValueHandling MATCH): a pair without a phone reaches the e-mail node, so the path does not require the phone
		final String lenient = MatchPlanLlmPassGuidanceTest.call("N1",
			MatchPlanLlmPassGuidanceTest.node("N1", "{\"field\":\"mobileE164\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"MATCH\"}", "N2", "N3")
				+ "," + MatchPlanLlmPassGuidanceTest.node("N2", MatchPlanLlmPassGuidanceTest.key("emailKey"), "MATCH", "N3") + ","
				+ MatchPlanLlmPassGuidanceTest.node("N3", MatchPlanLlmPassGuidanceTest.key("mobileE164"), "SUSPECTED", "NOT_MATCHED"), "\"mobileE164\",\"emailKey\"");
		final LocalTool.Result lenientResult = MatchPlanLlmPassGuidanceTest.submit(MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts()), lenient);
		check(lenientResult.isError && lenientResult.text.contains("cannot terminate a MATCH-terminal node on its own"), "an ancestor on which a missing value passes contributes nothing: " + lenientResult.text);
	}

	/** The identifying condition (lot M10a) and the candidate filter (lot M8a) judge a path on every node it crosses through onTrue: a coarse key may follow an identifying one on the path, not stand alone. */
	private static void theOtherRulesOfAPathReadItsConjunctionToo()
	{
		final String after = MatchPlanLlmPassGuidanceTest.call("N1",
			MatchPlanLlmPassGuidanceTest.node("N1", MatchPlanLlmPassGuidanceTest.key("mobileE164"), "N2", "N3") + ","
				+ MatchPlanLlmPassGuidanceTest.node("N2", MatchPlanLlmPassGuidanceTest.key("personNameKey"), "SUSPECTED", "NOT_MATCHED") + ","
				+ MatchPlanLlmPassGuidanceTest.node("N3", MatchPlanLlmPassGuidanceTest.key("emailKey"), "SUSPECTED", "NOT_MATCHED"), "\"mobileE164\",\"emailKey\",\"personNameKey\"");
		final MatchPlanLlmPass.Session ancestorSession = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		MatchPlanLlmPassGuidanceTest.submit(ancestorSession, after);
		final LocalTool.Result accepted = MatchPlanLlmPassGuidanceTest.submit(ancestorSession, after);
		check(!accepted.isError, "the name key after the phone is a path that requires both: accepted (child evidence read once): " + accepted.text);
		final String alone = MatchPlanLlmPassGuidanceTest.call("N1", MatchPlanLlmPassGuidanceTest.node("N1", MatchPlanLlmPassGuidanceTest.key("personNameKey"), "SUSPECTED", "N2") + ","
			+ MatchPlanLlmPassGuidanceTest.node("N2", MatchPlanLlmPassGuidanceTest.key("mobileE164"), "SUSPECTED", "NOT_MATCHED"), "\"mobileE164\",\"personNameKey\"");
		final MatchPlanLlmPass.Session aloneSession = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		MatchPlanLlmPassGuidanceTest.submit(aloneSession, alone);
		final LocalTool.Result refused = MatchPlanLlmPassGuidanceTest.submit(aloneSession, alone);
		check(refused.isError && refused.text.contains("require no condition identifying a record") && refused.text.contains("path N1 (requires personNameKey exact -> SUSPECTED)"),
			"the same key alone opens a path that identifies nobody: " + refused.text);
	}

	// ------------------------------------------------------------------------------------------------------- B

	/** Violations of four classes: the child-keys threshold (a node), a MATCH on a derived key (a path), a source system the candidate filter cannot reach (a challenge), and the child-evidence challenge. */
	private static String threeViolations()
	{
		return MatchPlanLlmPassGuidanceTest.call("N1",
			MatchPlanLlmPassGuidanceTest.node("N1", MatchPlanLlmPassGuidanceTest.key("emailKey"), "MATCH", "N2") + "," + MatchPlanLlmPassGuidanceTest.average("N2", "N3", "NOT_MATCHED") + ","
				+ MatchPlanLlmPassGuidanceTest.node("N3", MatchPlanLlmPassGuidanceTest.addressKeys(100), "SUSPECTED", "NOT_MATCHED"),
			"\"emailKey\",\"addressKeys\"");
	}

	private static void theViolationsOfAProposalAreToldTogether()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		final LocalTool.Result first = MatchPlanLlmPassGuidanceTest.submit(session, MatchPlanLlmPassGuidanceTest.threeViolations());
		check(first.isError && first.text.contains("problems, all reported at once - fix every one of them in the next proposal"), "one answer for all of them: " + first.text);
		check(first.text.contains("(1) ") && first.text.contains("(2) ") && first.text.contains("(3) "), "numbered: " + first.text);
		check(first.text.contains("'addressKeys' holds the derived child keys of Adresse: minimumScore 100 is too high") && first.text.contains("Set minimumScore to 50 or less"),
			"the child-keys ceiling, with its fix: " + first.text);
		check(first.text.contains("'emailKey' is a matching key derived from 'email'") && first.text.contains("Pair it with"), "the MATCH on a derived key, with its fix: " + first.text);
		check(first.text.contains("leaves part of a source system out of reach") && first.text.contains("Source STORES: 103 of 234 record(s) fill none of the candidate-filter keys"),
			"the tickets the candidate filter cannot reach, with the keys they fill: " + first.text);
		check(first.text.contains("'Client' has child evidence Adresse"), "and the one-shot challenge, asked once, in the same answer: " + first.text);
		check(!first.text.contains("Giving up"), "the first proposal never ends the entity");
		// ONE new proposal fixes them all
		final LocalTool.Result second = MatchPlanLlmPassGuidanceTest.submit(session, MatchPlanLlmPassGuidanceTest.validPlan());
		check(!second.isError, "the corrected proposal is accepted at once, the challenge being asked only once: " + second.text);
	}

	private static void theBreakerComparesTheWholeSet()
	{
		// the same set twice in a row ends the entity (the first answer also carries the two one-shot challenges, asked once: the SET of the second answer is smaller, the third equals it)
		final MatchPlanLlmPass.Session same = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		MatchPlanLlmPassGuidanceTest.submit(same, MatchPlanLlmPassGuidanceTest.threeViolations());
		final LocalTool.Result secondTime = MatchPlanLlmPassGuidanceTest.submit(same, MatchPlanLlmPassGuidanceTest.threeViolations());
		check(secondTime.isError && !secondTime.text.contains("Giving up") && !secondTime.text.contains("has child evidence"), "a set that lost the challenges it already read is another set: " + secondTime.text);
		final LocalTool.Result again = MatchPlanLlmPassGuidanceTest.submit(same, MatchPlanLlmPassGuidanceTest.threeViolations());
		check(again.isError && again.text.contains("Giving up on Client") && again.text.contains("2 times in a row"), "the same set of violations twice in a row ends it: " + again.text);
		// the same tree with the SAME node renamed is the same set (node ids are taken out of the signature)
		final MatchPlanLlmPass.Session renamed = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		MatchPlanLlmPassGuidanceTest.submit(renamed, MatchPlanLlmPassGuidanceTest.threeViolations());
		MatchPlanLlmPassGuidanceTest.submit(renamed, MatchPlanLlmPassGuidanceTest.threeViolations());
		final LocalTool.Result renamedAgain = MatchPlanLlmPassGuidanceTest.submit(renamed, MatchPlanLlmPassGuidanceTest.threeViolations().replace("\"N1\"", "\"Z1\"").replace("\"N2\"", "\"Z2\"").replace("\"N3\"", "\"Z3\""));
		check(renamedAgain.isError && renamedAgain.text.contains("Giving up on Client"), "renaming the nodes is not a new set: " + renamedAgain.text);
		// one violation fixed: another set, the model is given another chance
		final MatchPlanLlmPass.Session changed = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		MatchPlanLlmPassGuidanceTest.submit(changed, MatchPlanLlmPassGuidanceTest.threeViolations());
		MatchPlanLlmPassGuidanceTest.submit(changed, MatchPlanLlmPassGuidanceTest.threeViolations());
		final LocalTool.Result other = MatchPlanLlmPassGuidanceTest.submit(changed, MatchPlanLlmPassGuidanceTest.threeViolations().replace("\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\","
			+ "\"oneNullValueHandling\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\"", "\"minimumScore\":50,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}],\"onTrue\":\"SUSPECTED\""));
		check(other.isError && !other.text.contains("Giving up") && !other.text.contains("holds the derived child keys of Adresse: minimumScore"), "a smaller set is another refusal: " + other.text);
	}

	private static void theFirstProposalOfRun542DrawsItsViolationsInOneAnswer()
	{
		// the first proposal of the live run (step 2) was refused for the child-evidence challenge ONLY, then the child-keys ceiling, then (the next turns) the other rules, one per turn
		final LocalTool.Result first = MatchPlanLlmPassGuidanceTest.submit(MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts()), MatchPlanLlmPassGuidanceTest.liveProposal(2));
		check(first.isError && first.text.contains("'Client' has child evidence Adresse") && first.text.contains("minimumScore 100 is too high"),
			"the challenge of turn 1 and the ceiling of turn 2 arrive together: " + first.text);
		check(first.text.contains("problems, all reported at once"), "as a numbered list: " + first.text);
	}

	private static void combinedViolationsAreNumberedAndSingleOnesUntouched()
	{
		check("a lone violation".equals(MatchPlanLlmPass.Session.combineViolations(Arrays.asList("a lone violation"))), "one violation is told as it always was");
		check("a lone violation".equals(MatchPlanLlmPass.Session.combineViolations(Arrays.asList("a lone violation", "a lone violation"))), "a violation reported twice is one");
		final String two = MatchPlanLlmPass.Session.combineViolations(Arrays.asList("first one.", "second one"));
		check(two.contains("2 problems, all reported at once") && two.contains("(1) first one.") && two.contains("(2) second one."), "several are numbered: " + two);
		final String structural = MatchPlanLlmPass.Session.combineViolations(Arrays.asList(MatchPlanLlmPass.Session.STRUCTURAL_REFUSAL_PREFIX + "UNKNOWN_BRANCH_TARGET: x.",
			MatchPlanLlmPass.Session.STRUCTURAL_REFUSAL_PREFIX + "CYCLE_DETECTED: y."));
		check(structural.startsWith(MatchPlanLlmPass.Session.STRUCTURAL_REFUSAL_PREFIX) && MatchPlanLlmPass.Session.isStructuralRefusal(structural) && !structural.substring(10).contains(
			MatchPlanLlmPass.Session.STRUCTURAL_REFUSAL_PREFIX), "a set made only of refusals of the tree's shape keeps the structural class: " + structural);
		check(!MatchPlanLlmPass.Session.isStructuralRefusal(MatchPlanLlmPass.Session.combineViolations(Arrays.asList(MatchPlanLlmPass.Session.STRUCTURAL_REFUSAL_PREFIX + "x.", "a field."))),
			"a mixed set is a refusal of the content");
	}

	// ------------------------------------------------------------------------------------------------------- C

	private static MatchPlanLlmPass.ProjectInfo project(final MatchPlanLlmPass.EntityInfo pEntity)
	{
		return new MatchPlanLlmPass.ProjectInfo("E2E", "e2e", new ArrayList<MatchPlanLlmPass.EntityInfo>(Arrays.asList(pEntity)), new ArrayList<String>(), null, false);
	}

	private static void theChecklistStatesTheFactsOfTheEntity()
	{
		final MatchPlanLlmPass.EntityInfo entity = MatchPlanLlmPassGuidanceTest.client(MatchPlanLlmPassGuidanceTest.facts());
		final String block = MatchPlanChecklist.entityBlock(entity);
		System.out.println("--- checklist of Client (run 542 facts) ---\n" + block);
		check(block.startsWith("  Checklist for the plan of Client"), "the checklist: " + block);
		check(block.contains("Every path to SUSPECTED or MATCH opens with an exact key of the record itself") && block.contains("AVERAGE nodes, similarity scores and nodes on related records come only AFTER such a node"),
			"the opening rule: " + block);
		check(block.contains("mobileE164 (ECOM 160 of 160 (100%), LOYALTY 140 of 140 (100%), STORES 234 of 234 (100%)) [identifies a record]"), "a key and its fill per source system: " + block);
		check(!block.contains("birthDate (ECOM"), "a key one system never fills does not open a path: " + block);
		check(block.contains("emailKey (ECOM 160 of 160 (100%), LOYALTY 140 of 140 (100%), STORES 131 of 234 (56%)) [identifies a record]"), "the e-mail key: STORES fills 56%: " + block);
		check(block.contains("Fields that at least one source system never fills (measured): birthDate (empty in STORES 0 of 234)"), "the field empty in a system, with its numbers: " + block);
		check(!block.contains("addressKeys (empty in"), "the child keys are not in that list (the pass exempts them from the fill guards): " + block);
		check(block.contains("addressKeys holds the derived child keys of Adresse: compare it only with ebx-addon-mame@exactFullText in an ALL_FIELDS node, NOT_MATCH on both null handlings, minimumScore at most 50 "
			+ "(measured: one record has 2 child record(s) at most, which supports 50; the pass refuses anything above 50 whatever the count)."), "the child keys and their ceiling: " + block);
		check(block.contains("Child evidence (Adresse: fullAddress) is expected in the plan"), "the child evidence: " + block);
		// in the prompt, after the facts of the entity and before its relation evidence
		final String prompt = MatchPlanLlmPass.userPrompt(MatchPlanLlmPassGuidanceTest.project(entity));
		check(prompt.indexOf("Measured key facts") < prompt.indexOf("Checklist for the plan of Client") && prompt.indexOf("Checklist for the plan of Client") < prompt.indexOf("Relation evidence (child entities"),
			"the checklist stands after the measured facts and before the relation evidence");
		check(prompt.contains("a MATCH is judged on every exact condition its path requires"), "and the MATCH line says that the keys of a pair may sit on one path");
	}

	private static void theChecklistIsSilentWhenNothingIsMeasured()
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanLlmPassGuidanceTest.plain("code", "STRING", 90, 1.0));
		final MatchPlanLlmPass.EntityInfo bare = new MatchPlanLlmPass.EntityInfo("Store", "Store", 100, attributes);
		check(MatchPlanChecklist.entityBlock(bare).isEmpty(), "no facts, no child keys, no child evidence: nothing said, the prompt is what it was");
		// a sample is not a measure
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		rows.add(new KeyFacts.Row("A", new String[] { "x" }));
		rows.add(new KeyFacts.Row("B", new String[] { "x" }));
		bare.keyFacts = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("code", "code", false)), true);
		check(MatchPlanChecklist.entityBlock(bare).isEmpty(), "a sample neither: no number is claimed");
	}

	// ------------------------------------------------------------------------------------------------------- D

	private static void theStartingPlanIsGivenAndAcceptedAsItIsButNeverReplacesAPlanOfTheModel()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		final List<MatchPlanLlmPass.StartingPlan> plans = session.prepareStartingPlans();
		check(plans.size() == 1 && "Client".equals(plans.get(0).entity), "a starting plan for Client, judged valid by the checks of the pass: " + session.startingPlanReasons());
		check(session.startingPlans().containsKey("Client"), "registered by entity");
		final String arguments = plans.get(0).arguments.toString();
		// the prompt carries it, with the words that ask the model to refine
		final MatchPlanLlmPass.EntityInfo promptEntity = MatchPlanLlmPassGuidanceTest.client(MatchPlanLlmPassGuidanceTest.facts());
		final Map<String, MatchPlanLlmPass.EntityInfo> map = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		map.put("Client", promptEntity);
		final MatchPlanLlmPass.Session forPrompt = new MatchPlanLlmPass.Session(map, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(),
			MatchPlanLlmPassGuidanceTest.vocabulary()).requireFilteredCandidateSearch();
		forPrompt.prepareStartingPlans();
		final String prompt = MatchPlanLlmPass.userPrompt(MatchPlanLlmPassGuidanceTest.project(promptEntity));
		System.out.println("--- starting plan block ---\n" + MatchPlanChecklist.startingPlanBlock("Client", promptEntity.startingPlan.arguments, promptEntity.startingPlan.summary));
		check(prompt.contains("Starting plan for Client (built by the Factory, by the rules of this pass, from the measured facts above - it passes every check of the pass for Client; "
			+ "keep its structure, improve its leaves and thresholds, add what the facts justify"), "the prompt gives the starting plan and what to do with it: " + prompt);
		check(prompt.contains("or explain in the rationale why you prefer another shape; resubmitted unchanged it is accepted as it is") && prompt.contains("To resubmit as it is: {\"root\":\"F1\",\"nodes\":["),
			"and the tree to resubmit");
		check(prompt.indexOf("Checklist for the plan of Client") < prompt.indexOf("Starting plan for Client"), "after the checklist");
		// resubmitted unchanged (the model adds its own rationale, confidence and no clustering fields): accepted at once, although the child evidence is not used (the Factory cannot challenge itself)
		final JsonNode tree = plans.get(0).arguments;
		final String unchanged = "{\"entity\":\"Client\",\"root\":" + tree.path("root") + ",\"nodes\":" + tree.path("nodes") + ",\"clusteringFields\":[],\"rationale\":\"the Factory's plan, kept.\",\"confidence\":60}";
		final LocalTool.Result kept = MatchPlanLlmPassGuidanceTest.submit(session, unchanged);
		check(!kept.isError, "the starting plan resubmitted unchanged is accepted: " + kept.text + " / " + arguments);
		check(session.createdPlans().get(0).rationale.contains("kept the Factory's starting plan"), "and says so: " + session.createdPlans().get(0).rationale);
		// without the starting plan registered, the same tree is an ordinary proposal: the child evidence is challenged once
		final MatchPlanLlmPass.Session ordinary = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		final LocalTool.Result challenged = MatchPlanLlmPassGuidanceTest.submit(ordinary, unchanged);
		check(challenged.isError && challenged.text.contains("has child evidence"), "an ordinary proposal of the same tree is challenged: " + challenged.text);
		// a change - however small - is the model's plan, judged as any plan
		final MatchPlanLlmPass.Session changed = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		changed.prepareStartingPlans();
		final LocalTool.Result modified = MatchPlanLlmPassGuidanceTest.submit(changed, unchanged.replace("\"onFalse\":\"NOT_MATCHED\"}],", "\"onFalse\":\"NOT_MATCHED\"}],").replace("exactFullText\",\"minimumScore\":", "exactFullText\",\"minimumScore\":1"));
		check(modified.isError, "a tree that is not the starting plan any more is challenged like any other: " + modified.text);
		// a plan of the model that passes is kept as the model wrote it: never replaced by the starting plan
		final MatchPlanLlmPass.Session own = MatchPlanLlmPassGuidanceTest.session(MatchPlanLlmPassGuidanceTest.facts());
		own.prepareStartingPlans();
		MatchPlanLlmPassGuidanceTest.submit(own, MatchPlanLlmPassGuidanceTest.validPlan());
		final LocalTool.Result ownResult = MatchPlanLlmPassGuidanceTest.submit(own, MatchPlanLlmPassGuidanceTest.validPlan());
		check(!ownResult.isError, "the model's own plan passes (after the challenge, asked once): " + ownResult.text);
		final JsonNode ownTree = MatchPlanLlmPassGuidanceTest.parse(own.createdPlans().get(0).requirementsJson).path("decisionTree");
		check("A1".equals(ownTree.path("root").asText()) && !own.createdPlans().get(0).rationale.contains("starting plan") && "LLM".equals(own.createdPlans().get(0).origin),
			"the stored plan is the model's: " + ownTree);
	}
}
