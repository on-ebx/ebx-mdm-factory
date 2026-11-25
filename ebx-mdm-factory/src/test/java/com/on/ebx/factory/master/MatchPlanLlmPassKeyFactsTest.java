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
import com.on.ebx.factory.matching.KeyFacts;
import com.on.ebx.factory.matching.KeyFindings;

/**
 * JUnit-free: run the main, a failure throws. Lot M3 ({@code docs/MATCHING.md} &sect;56): the language-model pass given the measured key facts of the B2C master scenario
 * (534 person records, three sources, 565 true pairs): the contract told to the model, the facts in the prompt, a MATCH path accepted on measured agreement and refused
 * where the data does not support it, the one-shot reach challenge, and the facts stored with the plan.
 */
public final class MatchPlanLlmPassKeyFactsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanLlmPassKeyFactsTest.contractIsToldToTheModel();
		MatchPlanLlmPassKeyFactsTest.promptCarriesTheFacts();
		MatchPlanLlmPassKeyFactsTest.aSafeCombinationMayEndInMatchAndTheFactsAreStored();
		MatchPlanLlmPassKeyFactsTest.withoutFactsTheOldRuleStandsAndTheRefusalNamesNothing();
		MatchPlanLlmPassKeyFactsTest.aSingleKeyNeverEndsInMatchAndTheRefusalPointsAtTheSafePairs();
		MatchPlanLlmPassKeyFactsTest.aSampleOrAConflictRelaxesNothing();
		MatchPlanLlmPassKeyFactsTest.theLivePlanIsChallengedOnceForTheTicketsItCannotReach();
		MatchPlanLlmPassKeyFactsTest.selectionPicksKeysFromProfilesNotNames();
		MatchPlanLlmPassKeyFactsTest.theCandidateArithmeticIsToldToTheModel();
		MatchPlanLlmPassKeyFactsTest.aMatchPathUnder30PercentIsToldOnceWithTheNumbersAndTheWeightsThatHoldIt();
		MatchPlanLlmPassKeyFactsTest.theSuggestedWeightsAreAcceptedAndStoredWithThePlan();
		MatchPlanLlmPassKeyFactsTest.aSuspectedPathUnder30PercentIsOnlySaidInTheRationale();
		MatchPlanLlmPassKeyFactsTest.badClusteringWeightsAreRefusedWithTheValidKeys();
		MatchPlanLlmPassKeyFactsTest.aSourceSystemThatFillsNoDeclaredFieldIsRefused();
		MatchPlanLlmPassKeyFactsTest.clusteringWeightsSurviveARewriteOfThePlan();
		MatchPlanLlmPassKeyFactsTest.theSimulationFactsReachTheNextPromptAsNumberedFacts();
		System.out.println("MatchPlanLlmPassKeyFactsTest OK");
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

	private static MatchPlanLlmPass.AttributeInfo childKeys(final String pName)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 2000, null, Double.valueOf(0.56), "EXACT", Integer.valueOf(300), "EXACT", null, null, null, null, true, null,
			Collections.<String>emptyList(), Collections.<String>emptyList(), null, null, null, null, null, null, null, null, null,
			MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS, "AdresseClient");
	}

	/** Client of the B2C master scenario: the keys the Factory derives (e-mail key, harmonised name key, E.164 phone), child keys of the address, the raw names and the birth date. */
	private static MatchPlanLlmPass.EntityInfo client(final KeyFacts pFacts)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanLlmPassKeyFactsTest.matchKey("emailKey", "email", 207, 0.73));
		attributes.add(MatchPlanLlmPassKeyFactsTest.matchKey("fullNameKey", "name", 187, 1.0));
		attributes.add(MatchPlanLlmPassKeyFactsTest.matchKey("mobileE164", "mobile", 212, 1.0));
		attributes.add(MatchPlanLlmPassKeyFactsTest.plain("firstName", "STRING", 150, 1.0));
		attributes.add(MatchPlanLlmPassKeyFactsTest.plain("lastName", "STRING", 150, 1.0));
		attributes.add(MatchPlanLlmPassKeyFactsTest.plain("birthDate", "DATE", 195, 0.56));
		attributes.add(MatchPlanLlmPassKeyFactsTest.childKeys("addressKeys"));
		final MatchPlanLlmPass.EntityInfo entity = new MatchPlanLlmPass.EntityInfo("Client", "Client", 534, attributes);
		entity.keyFacts = pFacts;
		return entity;
	}

	private static MatchPlanLlmPass.Session session(final KeyFacts pFacts)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Client", MatchPlanLlmPassKeyFactsTest.client(pFacts));
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(), MatchPlanLlmPassKeyFactsTest.vocabulary())
			.requireFilteredCandidateSearch();
	}

	private static String key(final String pName)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String addressKeys()
	{
		return "{\"field\":\"addressKeys\",\"algorithm\":\"ebx-addon-mame@exactFullText\",\"minimumScore\":50,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String node(final String pId, final String pFields, final String pOnTrue, final String pOnFalse)
	{
		return "{\"id\":\"" + pId + "\",\"function\":\"ALL_FIELDS\",\"fields\":[" + pFields + "],\"onTrue\":\"" + pOnTrue + "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	private static String call(final String pNodes, final String pClustering)
	{
		return "{\"entity\":\"Client\",\"root\":\"N1\",\"nodes\":[" + pNodes + "],\"clusteringFields\":[" + pClustering + "],\"rationale\":\"keys first, weaker paths after.\",\"confidence\":80}";
	}

	/** E-mail and name together end in MATCH, the phone and the name too (the tickets), the e-mail alone and the phone alone in a review (the name key alone would pair namesakes: lot M10a). */
	private static String matchPlan()
	{
		return MatchPlanLlmPassKeyFactsTest.call(
			MatchPlanLlmPassKeyFactsTest.node("N1", MatchPlanLlmPassKeyFactsTest.key("emailKey") + "," + MatchPlanLlmPassKeyFactsTest.key("fullNameKey"), "MATCH", "N2") + ","
				+ MatchPlanLlmPassKeyFactsTest.node("N2", MatchPlanLlmPassKeyFactsTest.key("mobileE164") + "," + MatchPlanLlmPassKeyFactsTest.key("fullNameKey"), "MATCH", "N3") + ","
				+ MatchPlanLlmPassKeyFactsTest.node("N3", MatchPlanLlmPassKeyFactsTest.key("emailKey"), "SUSPECTED", "N4") + ","
				+ MatchPlanLlmPassKeyFactsTest.node("N4", MatchPlanLlmPassKeyFactsTest.key("mobileE164"), "SUSPECTED", "NOT_MATCHED"),
			"\"emailKey\",\"mobileE164\",\"fullNameKey\"");
	}

	/**
	 * The plan the model really proposed for the scenario (MatchPlan 55): e-mail key, then names, then address keys - every path ends in a review. Lot M14: the address keys of the
	 * original N3 and N4 stood alone; child evidence never does, so each node now carries the e-mail key beside them (the candidate-filter keys, hence the reach finding this test is about,
	 * stay emailKey and addressKeys).
	 */
	private static String livePlan()
	{
		return MatchPlanLlmPassKeyFactsTest.call(
			MatchPlanLlmPassKeyFactsTest.node("N1", MatchPlanLlmPassKeyFactsTest.key("emailKey"), "SUSPECTED", "N2") + ","
				+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[{\"field\":\"fullNameKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5,\"bothNullValueHandling\":\"NOT_MATCH\","
				+ "\"oneNullValueHandling\":\"NOT_MATCH\"},{\"field\":\"firstName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3,\"bothNullValueHandling\":\"NOT_MATCH\","
				+ "\"oneNullValueHandling\":\"NOT_MATCH\"},{\"field\":\"lastName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}],"
				+ "\"onTrue\":\"N3\",\"onFalse\":\"N4\"},"
				+ MatchPlanLlmPassKeyFactsTest.node("N3", MatchPlanLlmPassKeyFactsTest.key("emailKey") + "," + MatchPlanLlmPassKeyFactsTest.addressKeys(), "SUSPECTED", "NOT_MATCHED") + ","
				+ MatchPlanLlmPassKeyFactsTest.node("N4", MatchPlanLlmPassKeyFactsTest.key("emailKey") + "," + MatchPlanLlmPassKeyFactsTest.addressKeys(), "SUSPECTED", "NOT_MATCHED"),
			"\"emailKey\",\"fullNameKey\"");
	}

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchPlanLlmPassKeyFactsTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static LocalTool.Result submit(final MatchPlanLlmPass.Session pSession, final String pCall)
	{
		return pSession.proposeMatchPlan(MatchPlanLlmPassKeyFactsTest.parse(pCall));
	}

	private static KeyFacts facts()
	{
		return B2cFactsHolder.facts();
	}

	/** The facts of the scenario, through the test fixture of package {@code matching} (package-private there, so reached by reflection-free duplication of its few lines). */
	private static final class B2cFactsHolder
	{
		static KeyFacts facts()
		{
			final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
			for (final String[] line : B2cFactsHolder.lines())
			{
				rows.add(new KeyFacts.Row(line[0], new String[] { line[2], line[3], line[4], line[6], line[7] }));
			}
			return KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("emailKey", "email", false), new KeyFacts.KeySpec("fullNameKey", "name", false),
				new KeyFacts.KeySpec("mobileE164", "mobile", false), new KeyFacts.KeySpec("addressKeys", "addressKeys", true), new KeyFacts.KeySpec("birthDate", "birthDate", false)), false);
		}

		static List<String[]> lines()
		{
			final String sourceDir = System.getProperty("mdmf.sourceDir");
			final java.nio.file.Path file = sourceDir == null ? java.nio.file.Paths.get("ebx-mdm-factory/src/test/resources/com/on/ebx/factory/matching/b2c_person_keys.tsv")
				: java.nio.file.Paths.get(sourceDir).resolve("../../test/resources/com/on/ebx/factory/matching/b2c_person_keys.tsv").normalize();
			final List<String[]> out = new ArrayList<String[]>();
			try
			{
				final List<String> all = java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
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
					out.add(cells);
				}
			}
			catch (final java.io.IOException ex)
			{
				throw new AssertionError(ex);
			}
			return out;
		}
	}

	// ------------------------------------------------------------------------------------------------------- tests

	private static void contractIsToldToTheModel()
	{
		final String prompt = MatchPlanLlmPass.systemPrompt();
		check(prompt.contains("MATCH ON TWO INDEPENDENT AGREEMENTS") && prompt.contains("mark the pair [SAFE]"), "the contract names the SAFE mark");
		check(prompt.contains("at least 99% of the pairs that can be compared agree, none differ, over at least ten comparable pairs"), "and its thresholds");
		check(prompt.contains("the same name with a different address, phone or birth date is not a match"), "the old rule stays");
		check(prompt.contains("REACH EVERY SOURCE SYSTEM") && prompt.contains("a quarter or more of a source system's records"), "the reach rule and its challenge");
		check(prompt.contains("You never set autoMerge"), "autoMerge stays a person's decision");
		check(!prompt.contains("emailKey") && !prompt.contains("mobileE164"), "no column name in the contract");
	}

	private static void promptCarriesTheFacts()
	{
		final MatchPlanLlmPass.ProjectInfo project = new MatchPlanLlmPass.ProjectInfo("P", "P", Collections.singletonList(MatchPlanLlmPassKeyFactsTest.client(MatchPlanLlmPassKeyFactsTest.facts())),
			Collections.<String>emptyList(), null, false);
		final String prompt = MatchPlanLlmPass.userPrompt(project);
		check(prompt.contains("Measured key facts (counted on 534 record(s) of Client"), "the block header");
		check(prompt.contains("same emailKey -> fullNameKey: 341 pair(s), agree 341, differ 0, unknown 0, rate 100.0% [SAFE"), "the agreement of the e-mail key with the name key");
		check(prompt.contains("same mobileE164 -> fullNameKey: 565 pair(s)") && prompt.contains("same mobileE164 -> emailKey: 565 pair(s), agree 341, differ 0, unknown 224"), "the phone");
		check(prompt.contains("same fullNameKey -> mobileE164: 694 pair(s), agree 565, differ 129") && !prompt.contains("same fullNameKey -> mobileE164: 694 pair(s), agree 565, differ 129, unknown 0, rate 81.4% [SAFE"),
			"the name key alone is not SAFE: namesakes");
		check(prompt.contains("emailKey: filled ECOM 160 (100%), LOYALTY 140 (100%), STORES 131 (56%)"), "reach per source system");
		// no value of any record
		check(!prompt.contains("JULIE.RICHARD62@FREE.FR") && !prompt.contains("+33646860523"), "counts only, never a value");
		final MatchPlanLlmPass.ProjectInfo without = new MatchPlanLlmPass.ProjectInfo("P", "P", Collections.singletonList(MatchPlanLlmPassKeyFactsTest.client(null)),
			Collections.<String>emptyList(), null, false);
		check(!MatchPlanLlmPass.userPrompt(without).contains("Measured key facts"), "no facts, no block");
	}

	private static void aSafeCombinationMayEndInMatchAndTheFactsAreStored()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts());
		final LocalTool.Result result = MatchPlanLlmPassKeyFactsTest.submit(session, MatchPlanLlmPassKeyFactsTest.matchPlan());
		check(!result.isError, "accepted: " + result.text);
		final MatchPlanLlmPass.CreatedPlan plan = session.createdPlans().get(0);
		final JsonNode requirements = MatchPlanLlmPassKeyFactsTest.parse(plan.requirementsJson);
		check("MATCH".equals(requirements.path("decisionTree").path("nodes").get(0).path("onTrue").asText()), "N1 keeps its MATCH leaf");
		check(requirements.path("keyFacts").path("rows").asInt() == 534 && KeyFacts.fromJson(requirements.path("keyFacts")) != null, "the facts are stored with the plan");
		check(plan.rationale.contains("MATCH path N1 (emailKey + fullNameKey) is justified by the measured data: 341 pair(s) of records share emailKey, 341 of the 341 that can be compared also share fullNameKey, 0 differ"),
			"the rationale says why: " + plan.rationale);
		check(plan.rationale.contains("MATCH path N2 (mobileE164 + fullNameKey)"), "and for the second path");
		check(KeyFindings.check(requirements).isEmpty(), "a plan with a MATCH leaf that reaches every ticket has no finding left: " + KeyFindings.check(requirements).size());
		// translated: the MATCH leaves survive when a person turns automatic merge on, and go to the steward's queue while it is off
		final Map<String, String> noChildren = Collections.emptyMap();
		final String merged = MatchingPolicyTranslator.translate("Client", requirements, true, noChildren).matchingPolicy.path("decisionTree").toString();
		final String reviewed = MatchingPolicyTranslator.translate("Client", requirements, false, noChildren).matchingPolicy.path("decisionTree").toString();
		check(merged.contains("\"name\":\"MATCHED\"") || merged.contains("\"name\":\"Match\""), "automatic merge on: a MATCH leaf reaches the add-on: " + merged);
		check(!reviewed.contains("\"name\":\"MATCHED\"") && !reviewed.contains("\"name\":\"Match\""), "automatic merge off: every leaf is a review: " + reviewed);
	}

	private static void withoutFactsTheOldRuleStandsAndTheRefusalNamesNothing()
	{
		final LocalTool.Result result = MatchPlanLlmPassKeyFactsTest.submit(MatchPlanLlmPassKeyFactsTest.session(null), MatchPlanLlmPassKeyFactsTest.matchPlan());
		check(result.isError && result.text.contains("is a matching key derived from"), "refused as before: " + result.text);
		check(!result.text.contains("SAFE"), "and with nothing measured, no hint");
	}

	private static void aSingleKeyNeverEndsInMatchAndTheRefusalPointsAtTheSafePairs()
	{
		final String plan = MatchPlanLlmPassKeyFactsTest.call(MatchPlanLlmPassKeyFactsTest.node("N1", MatchPlanLlmPassKeyFactsTest.key("emailKey"), "MATCH", "N2") + ","
			+ MatchPlanLlmPassKeyFactsTest.node("N2", MatchPlanLlmPassKeyFactsTest.key("mobileE164"), "SUSPECTED", "NOT_MATCHED"), "\"emailKey\",\"mobileE164\"");
		final LocalTool.Result result = MatchPlanLlmPassKeyFactsTest.submit(MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts()), plan);
		check(result.isError && result.text.contains("is a matching key derived from"), "one key is not enough: " + result.text);
		check(result.text.contains("The measured key facts mark these pairs of independent keys SAFE") && result.text.contains("emailKey + fullNameKey (341 of 341 comparable pairs agree)"),
			"the refusal names the pairs that would be accepted: " + result.text);
	}

	private static void aSampleOrAConflictRelaxesNothing()
	{
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		for (final String[] line : B2cFactsHolder.lines())
		{
			rows.add(new KeyFacts.Row(line[0], new String[] { line[2], line[3], line[4], line[6], line[7] }));
		}
		final List<KeyFacts.KeySpec> specs = Arrays.asList(new KeyFacts.KeySpec("emailKey", "email", false), new KeyFacts.KeySpec("fullNameKey", "name", false),
			new KeyFacts.KeySpec("mobileE164", "mobile", false), new KeyFacts.KeySpec("addressKeys", "addressKeys", true), new KeyFacts.KeySpec("birthDate", "birthDate", false));
		final LocalTool.Result sampled = MatchPlanLlmPassKeyFactsTest.submit(MatchPlanLlmPassKeyFactsTest.session(KeyFacts.compute(rows, specs, true)), MatchPlanLlmPassKeyFactsTest.matchPlan());
		check(sampled.isError && !sampled.text.contains("SAFE"), "a prefix of the table relaxes nothing: " + sampled.text);
		// the e-mail shared by two different people, twelve times: the pair is no longer safe
		final List<KeyFacts.Row> conflicting = new ArrayList<KeyFacts.Row>();
		int changed = 0;
		for (final KeyFacts.Row row : rows)
		{
			final String[] values = row.values.clone();
			if (changed < 40 && values[0] != null && "ECOM".equals(row.system))
			{
				values[1] = "someone else " + changed;
				changed++;
			}
			conflicting.add(new KeyFacts.Row(row.system, values));
		}
		final KeyFacts facts = KeyFacts.compute(conflicting, specs, false);
		check(!facts.safePair("emailKey", "fullNameKey") && facts.agreement("emailKey", "fullNameKey").conflict > 0, "the changed names make the pair unsafe");
		final LocalTool.Result result = MatchPlanLlmPassKeyFactsTest.submit(MatchPlanLlmPassKeyFactsTest.session(facts), MatchPlanLlmPassKeyFactsTest.call(
			MatchPlanLlmPassKeyFactsTest.node("N1", MatchPlanLlmPassKeyFactsTest.key("emailKey") + "," + MatchPlanLlmPassKeyFactsTest.key("fullNameKey"), "MATCH", "N2") + ","
				+ MatchPlanLlmPassKeyFactsTest.node("N2", MatchPlanLlmPassKeyFactsTest.key("emailKey"), "SUSPECTED", "NOT_MATCHED"), "\"emailKey\",\"fullNameKey\""));
		check(result.isError && result.text.contains("is a matching key derived from"), "an e-mail shared by people with different names is not an agreement: " + result.text);
	}

	private static void theLivePlanIsChallengedOnceForTheTicketsItCannotReach()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts());
		final LocalTool.Result first = MatchPlanLlmPassKeyFactsTest.submit(session, MatchPlanLlmPassKeyFactsTest.livePlan());
		check(first.isError && first.text.contains("leaves part of a source system out of reach") && first.text.contains("Source STORES: 103 of 234 record(s) fill none of the candidate-filter keys (emailKey, addressKeys)"),
			"challenged: " + first.text);
		check(first.text.contains("It fills fullNameKey (100%), mobileE164 (100%)") || first.text.contains("It fills mobileE164 (100%), fullNameKey (100%)"), "with the keys the tickets do fill: " + first.text);
		check(first.text.contains("This is asked once"), "and said to be asked once");
		check(session.createdPlans().isEmpty(), "nothing recorded yet");
		final LocalTool.Result second = MatchPlanLlmPassKeyFactsTest.submit(session, MatchPlanLlmPassKeyFactsTest.livePlan());
		check(!second.isError, "the resubmission as it is stands: " + second.text);
		final MatchPlanLlmPass.CreatedPlan plan = session.createdPlans().get(0);
		check(plan.rationale.contains("Reach: Source STORES: 103 of 234"), "the stored rationale keeps the finding: " + plan.rationale);
		final List<KeyFindings.Finding> findings = KeyFindings.check(MatchPlanLlmPassKeyFactsTest.parse(plan.requirementsJson));
		// lot M14: N3 and N4 now carry the e-mail key beside the address keys, so the safe-path finding is told once per path that does (3 instead of 1); the reach finding stays one
		int safePaths = 0;
		int unreachable = 0;
		for (final KeyFindings.Finding finding : findings)
		{
			safePaths += KeyFindings.KIND_SAFE_AUTOMATIC_PATH.equals(finding.kind) ? 1 : 0;
			unreachable += KeyFindings.KIND_UNREACHABLE_SOURCE.equals(finding.kind) ? 1 : 0;
		}
		check(safePaths >= 1 && unreachable == 1 && KeyFindings.KIND_SAFE_AUTOMATIC_PATH.equals(findings.get(0).kind) && KeyFindings.KIND_UNREACHABLE_SOURCE.equals(findings.get(findings.size() - 1).kind),
			"and the card gets both kinds of findings: " + findings.size());
		// a plan that reaches the tickets with a phone path is not challenged
		final MatchPlanLlmPass.Session other = MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts());
		check(!MatchPlanLlmPassKeyFactsTest.submit(other, MatchPlanLlmPassKeyFactsTest.matchPlan()).isError, "no challenge when every source is reached");
	}

	// ------------------------------------------------------------------------------------------------------- lot M16 (docs/MATCHING.md sec 68)

	private static String withExtra(final String pCall, final String pExtra)
	{
		return pCall.substring(0, pCall.length() - 1) + "," + pExtra + "}";
	}

	private static void theCandidateArithmeticIsToldToTheModel() throws Exception
	{
		final String prompt = MatchPlanLlmPass.systemPrompt();
		check(prompt.contains("HOW THE ADD-ON FINDS CANDIDATES") && prompt.contains("at least 30% of the pivot's own weight"), "the measured rule is in the contract");
		check(prompt.contains("2 of 7 = 29%") && prompt.contains("150 of 565 true pairs lost") && prompt.contains("2 of 4 = 50%"), "with its numbers");
		check(prompt.contains("clusteringWeights") && prompt.contains("refused"), "the lever, and the one refusal");
		check(!prompt.contains("the system raises the weights") && !prompt.contains("SYSTEM raises"), "the Factory no longer says it raises weights itself");
		final java.lang.reflect.Method build = Class.forName("com.on.ebx.factory.master.MatchPlanLlmPass$Session").getDeclaredMethod("buildProposeSchema", MatchPlanLlmPass.AllowedVocabulary.class);
		build.setAccessible(true);
		final JsonNode schema = (JsonNode) build.invoke(null, MatchPlanLlmPassKeyFactsTest.vocabulary());
		check("object".equals(schema.path("properties").path("clusteringWeights").path("type").asText()) && schema.path("properties").path("clusteringWeights").path("additionalProperties").path("minimum").asInt() == 1,
			"clusteringWeights is an optional object of integers >= 1");
		check(!schema.path("required").toString().contains("clusteringWeights"), "optional");
		final MatchPlanLlmPass.ProjectInfo project = new MatchPlanLlmPass.ProjectInfo("P", "P", Collections.singletonList(MatchPlanLlmPassKeyFactsTest.client(MatchPlanLlmPassKeyFactsTest.facts())),
			Collections.<String>emptyList(), null, false);
		final String user = MatchPlanLlmPass.userPrompt(project);
		check(user.contains("Gaps (records of a system that do not fill a key") && user.contains("emailKey: STORES 103 of 234") && user.contains("addressKeys: STORES 234 of 234"), "the gaps, in numbers, per source system: "
			+ user.substring(Math.max(0, user.indexOf("Gaps")), Math.min(user.length(), user.indexOf("Gaps") + 400)));
	}

	/** matchPlan(): MATCH on e-mail + name and on phone + name, SUSPECTED on e-mail alone and phone alone. The e-mail at weight 6 puts the total at 8: phone + name holds 2 of 8 = 25 %. */
	private static void aMatchPathUnder30PercentIsToldOnceWithTheNumbersAndTheWeightsThatHoldIt()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts());
		final String heavyEmail = MatchPlanLlmPassKeyFactsTest.withExtra(MatchPlanLlmPassKeyFactsTest.matchPlan(), "\"clusteringWeights\":{\"emailKey\":6}");
		final LocalTool.Result first = MatchPlanLlmPassKeyFactsTest.submit(session, heavyEmail);
		check(first.isError, "told once: " + first.text);
		check(first.text.contains("candidate search will not propose some pairs") && first.text.contains("at least 30% of the pivot's own weight"), "the measured rule: " + first.text);
		check(first.text.contains("here 8") && first.text.contains("emailKey 6, mobileE164 1, fullNameKey 1"), "the total and the model's own weights: " + first.text);
		check(first.text.contains("[mobileE164 + fullNameKey] 25.0 %"), "the failing MATCH set with its share: " + first.text);
		check(first.text.contains("Fill per source system - mobileE164 filled ECOM 160 (100%), LOYALTY 140 (100%), STORES 234 (100%)"), "the fill rate per source system of the keys concerned: " + first.text);
		check(first.text.contains("clusteringWeights {") && first.text.contains("This is asked once"), "the weights that would hold it, and 'once': " + first.text);
		check(session.createdPlans().isEmpty(), "nothing recorded yet");
		final LocalTool.Result second = MatchPlanLlmPassKeyFactsTest.submit(session, heavyEmail);
		check(!second.isError, "the resubmission as it is stands: " + second.text);
		final MatchPlanLlmPass.CreatedPlan plan = session.createdPlans().get(0);
		check(plan.rationale.contains("Candidate search: a pair that agrees on [mobileE164 + fullNameKey] holds 25.0 % of the total weight 8"), "kept, and said in the rationale: " + plan.rationale);
		check(MatchPlanLlmPassKeyFactsTest.parse(plan.requirementsJson).path("clusteringWeights").path("emailKey").asInt() == 6, "the model's weight is stored: " + plan.requirementsJson);
	}

	private static void theSuggestedWeightsAreAcceptedAndStoredWithThePlan()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts());
		final LocalTool.Result first = MatchPlanLlmPassKeyFactsTest.submit(session, MatchPlanLlmPassKeyFactsTest.withExtra(MatchPlanLlmPassKeyFactsTest.matchPlan(), "\"clusteringWeights\":{\"emailKey\":6}"));
		final int start = first.text.indexOf("clusteringWeights {") + "clusteringWeights ".length();
		final String suggested = first.text.substring(start, first.text.indexOf('}', start) + 1);
		final LocalTool.Result second = MatchPlanLlmPassKeyFactsTest.submit(session, MatchPlanLlmPassKeyFactsTest.withExtra(MatchPlanLlmPassKeyFactsTest.matchPlan(),
			"\"clusteringWeights\":" + suggested.replace("}", ", \"emailKey\": 6}").replace("{ ,", "{")));
		check(!second.isError, "accepted with the weights the Factory suggested: " + second.text + " / " + suggested);
		check(!session.createdPlans().get(0).rationale.contains("Candidate search:"), "and nothing to warn about: " + session.createdPlans().get(0).rationale);
	}

	private static void aSuspectedPathUnder30PercentIsOnlySaidInTheRationale()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts());
		// total 1 + 2 + 2 = 5: e-mail + name 3 of 5, phone + name 4 of 5 (MATCH paths hold), the e-mail alone 1 of 5 = 20 % (a SUSPECTED path)
		final LocalTool.Result result = MatchPlanLlmPassKeyFactsTest.submit(session,
			MatchPlanLlmPassKeyFactsTest.withExtra(MatchPlanLlmPassKeyFactsTest.matchPlan(), "\"clusteringWeights\":{\"mobileE164\":2,\"fullNameKey\":2}"));
		check(!result.isError, "no round trip for a SUSPECTED path: " + result.text);
		final String rationale = session.createdPlans().get(0).rationale;
		check(rationale.contains("Candidate search: a pair that agrees on [emailKey] holds 20.0 % of the total weight 5") && rationale.contains("(kept as proposed)"), rationale);
	}

	private static void badClusteringWeightsAreRefusedWithTheValidKeys()
	{
		final LocalTool.Result unknown = MatchPlanLlmPassKeyFactsTest.submit(MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts()),
			MatchPlanLlmPassKeyFactsTest.withExtra(MatchPlanLlmPassKeyFactsTest.matchPlan(), "\"clusteringWeights\":{\"nowhere\":3}"));
		check(unknown.isError && unknown.text.contains("clusteringWeights names 'nowhere'") && unknown.text.contains("Valid keys: emailKey"), "an unknown field: " + unknown.text);
		final LocalTool.Result zero = MatchPlanLlmPassKeyFactsTest.submit(MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts()),
			MatchPlanLlmPassKeyFactsTest.withExtra(MatchPlanLlmPassKeyFactsTest.matchPlan(), "\"clusteringWeights\":{\"emailKey\":0}"));
		check(zero.isError && zero.text.contains("must be an integer >= 1"), "a weight under 1: " + zero.text);
	}

	/** A plan whose declared fields are filled by no record of STORES (234 records): refused with the numbers and the way out. */
	private static void aSourceSystemThatFillsNoDeclaredFieldIsRefused()
	{
		final String plan = MatchPlanLlmPassKeyFactsTest.call(MatchPlanLlmPassKeyFactsTest.node("N1", MatchPlanLlmPassKeyFactsTest.key("emailKey") + "," + MatchPlanLlmPassKeyFactsTest.addressKeys(), "SUSPECTED",
			"NOT_MATCHED"), "\"emailKey\",\"addressKeys\"");
		final LocalTool.Result result = MatchPlanLlmPassKeyFactsTest.submit(MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts()), plan);
		// STORES fills the e-mail for 131 of 234 records, so it is reached: the refusal is for a plan that declares ONLY what a whole system leaves empty
		check(!result.text.contains("filled by NO record of a whole source system"), "STORES fills the e-mail key: not a whole-system gap: " + result.text);
		final String only = MatchPlanLlmPassKeyFactsTest.call(MatchPlanLlmPassKeyFactsTest.node("N1", MatchPlanLlmPassKeyFactsTest.addressKeys(), "SUSPECTED", "NOT_MATCHED"), "\"addressKeys\"");
		final MatchPlanLlmPass.EntityInfo entity = MatchPlanLlmPassKeyFactsTest.client(MatchPlanLlmPassKeyFactsTest.facts());
		final String refusal = MatchPlanLlmPass.wholeSystemUnreachableRefusal(entity, new java.util.LinkedHashSet<String>(Arrays.asList("addressKeys")));
		check(refusal != null && refusal.contains("STORES: 234 record(s), none of them fills addressKeys") && refusal.contains("keys it does fill: ") && refusal.contains("mobileE164 (100%)"), "the refusal names the system, the numbers and the keys it fills: " + refusal);
		check(MatchPlanLlmPass.wholeSystemUnreachableRefusal(entity, new java.util.LinkedHashSet<String>(Arrays.asList("addressKeys", "emailKey"))) == null, "one key a system fills is enough");
		check(MatchPlanLlmPass.wholeSystemUnreachableRefusal(entity, new java.util.LinkedHashSet<String>(Arrays.asList("addressKeys", "unmeasured"))) == null, "a declared field the facts do not know proves nothing");
		final MatchPlanLlmPass.EntityInfo sampled = MatchPlanLlmPassKeyFactsTest.client(null);
		check(MatchPlanLlmPass.wholeSystemUnreachableRefusal(sampled, new java.util.LinkedHashSet<String>(Arrays.asList("addressKeys"))) == null, "no facts, no refusal");
		final LocalTool.Result throughTheSession = MatchPlanLlmPassKeyFactsTest.submit(MatchPlanLlmPassKeyFactsTest.session(MatchPlanLlmPassKeyFactsTest.facts()), only);
		check(throughTheSession.isError && throughTheSession.text.contains("filled by NO record of a whole source system") && throughTheSession.text.contains("STORES: 234 record(s)"),
			"and the proposal is refused: " + throughTheSession.text);
	}

	private static void clusteringWeightsSurviveARewriteOfThePlan()
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Client", MatchPlanLlmPassKeyFactsTest.client(MatchPlanLlmPassKeyFactsTest.facts()));
		final List<MatchPlanLlmPass.ExistingPlan> priors = new ArrayList<MatchPlanLlmPass.ExistingPlan>();
		priors.add(new MatchPlanLlmPass.ExistingPlan("Client", "PROPOSED", "LLM", "{\"clusteringWeights\":{\"emailKey\":2,\"gone\":9}}", null));
		final MatchPlanLlmPass.Session session = new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), priors, MatchPlanLlmPassKeyFactsTest.vocabulary()).requireFilteredCandidateSearch();
		final LocalTool.Result result = MatchPlanLlmPassKeyFactsTest.submit(session, MatchPlanLlmPassKeyFactsTest.matchPlan());
		check(!result.isError, "accepted: " + result.text);
		final JsonNode weights = MatchPlanLlmPassKeyFactsTest.parse(session.createdPlans().get(0).requirementsJson).path("clusteringWeights");
		check(weights.path("emailKey").asInt() == 2 && !weights.has("gone"), "the weight of a field still in the tree is carried over, the others dropped: " + weights);
	}

	private static void theSimulationFactsReachTheNextPromptAsNumberedFacts()
	{
		final java.util.List<String> facts = Arrays.asList("simulation: 18 pivot(s); 23 of 41 record(s) sharing mobileE164 with a pivot were never candidates (19 in STORES). Cause: x.",
			"simulation: 18 pivot(s); all 12 record(s) sharing emailKey with a pivot were among the candidates the add-on evaluated.");
		final String stored = com.on.ebx.factory.matching.CandidateCoverage.withFacts("{\"clusteringFields\":[\"emailKey\"]}", facts);
		check(stored != null && com.on.ebx.factory.matching.CandidateCoverage.factsOf(stored).equals(facts), "stored and read back");
		final MatchPlanLlmPass.ExistingPlan prior = new MatchPlanLlmPass.ExistingPlan("Client", "SUPERSEDED", "LLM", stored, "");
		final String note = MatchPlanLlmPass.rejectionNote("Client", prior);
		check(note != null && note.contains("MEASURED by the simulation of that plan") && note.contains(" 1) simulation: 18 pivot(s); 23 of 41 record(s) sharing mobileE164") && note.contains(" 2) simulation: 18"),
			"the facts are numbered in the prompt, with no reviewer comment needed: " + note);
		check(note.contains("Rewrite clusteringWeights and the clustering fields") && !note.contains("\"simulationFacts\""), "the model is told what to rewrite, and the plan JSON carries no copy of the facts: " + note);
		check(MatchPlanLlmPass.rejectionNote("Client", new MatchPlanLlmPass.ExistingPlan("Client", "SUPERSEDED", "LLM", "{}", "")) == null, "without comment nor facts, no note");
		final String withComment = MatchPlanLlmPass.rejectionNote("Client", new MatchPlanLlmPass.ExistingPlan("Client", "REJECTED", "LLM", stored, "too many suspects"));
		check(withComment.contains("MEASURED by the simulation") && withComment.contains("REVIEWER'S COMMENT") && withComment.contains("too many suspects"), "facts and comment together: " + withComment);
	}

	private static void selectionPicksKeysFromProfilesNotNames()
	{
		final List<KeyFacts.KeySpec> specs = KeyFactsSelection.select(MatchPlanLlmPassKeyFactsTest.client(null));
		final List<String> names = new ArrayList<String>();
		for (final KeyFacts.KeySpec spec : specs)
		{
			names.add(spec.name);
		}
		check(names.subList(0, 4).containsAll(Arrays.asList("emailKey", "fullNameKey", "mobileE164", "addressKeys")), "derived keys and child keys come first: " + names);
		check(names.subList(4, names.size()).containsAll(Arrays.asList("firstName", "lastName", "birthDate")) && names.size() == 7, "then the ordinary attributes: " + names);
		for (final KeyFacts.KeySpec spec : specs)
		{
			check(spec.multiToken == "addressKeys".equals(spec.name), "only the child keys are multi-token: " + spec.name);
		}
		// a reference, a per-source record key, a boolean, a free text and a never-repeating field are not keys
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanLlmPassKeyFactsTest.plain("ok", "STRING", 100, 0.9));
		attributes.add(MatchPlanLlmPassKeyFactsTest.plain("constant", "STRING", 1, 0.9));
		attributes.add(MatchPlanLlmPassKeyFactsTest.plain("flag", "BOOLEAN", 2, 0.9));
		attributes.add(MatchPlanLlmPassKeyFactsTest.plain("neverRepeats", "STRING", 450, 0.9));
		attributes.add(MatchPlanLlmPassKeyFactsTest.plain("rare", "STRING", 10, 0.01));
		attributes.add(new MatchPlanLlmPass.AttributeInfo("freeText", "freeText", "STRING", 4000, null, Double.valueOf(0.9), "EXACT", Integer.valueOf(100), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_BUSINESS, Collections.<String>emptyList(), Collections.<String>emptyList()));
		attributes.add(new MatchPlanLlmPass.AttributeInfo("ref", "ref", "STRING", 20, "Other", Double.valueOf(0.9), "EXACT", Integer.valueOf(100), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_REFERENCE, Collections.<String>emptyList(), Collections.<String>emptyList()));
		attributes.add(new MatchPlanLlmPass.AttributeInfo("sourceKey", "sourceKey", "STRING", 20, null, Double.valueOf(0.9), "EXACT", Integer.valueOf(100), "EXACT", null, null, null, null, true,
			MatchPlanLlmPass.AttributeInfo.ROLE_SOURCE_KEY, Collections.<String>emptyList(), Collections.<String>emptyList()));
		final List<KeyFacts.KeySpec> chosen = KeyFactsSelection.select(new MatchPlanLlmPass.EntityInfo("E", "E", 500, attributes));
		check(chosen.size() == 1 && "ok".equals(chosen.get(0).name), "only the ordinary key-like attribute is chosen: " + chosen.size());
	}
}
