package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.matching.KeyFacts;

/**
 * JUnit-free: run the main, a failure throws. Lot M10a ({@code docs/MATCHING.md} &sect;64) through the language-model pass: the person entity of the 7th e2e run (its stored key facts)
 * is given the proposal that produced the 16 false pairs - the name key, similar names, the same postal code - and the pass refuses it naming the path, the reasons and the fixes;
 * a street of the related address, the precise field, is accepted; the same refusal twice ends the entity and the Factory's fallback plan, run through the same checks, is accepted.
 */
public final class MatchPlanLlmPassPathEvidenceTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanLlmPassPathEvidenceTest.thePathOfRun7IsRefusedThenAPreciseRelationFieldIsAccepted();
		MatchPlanLlmPassPathEvidenceTest.theRefusalEndsTheEntityTheSecondTimeAndTheFallbackPassesTheSameRule();
		MatchPlanLlmPassPathEvidenceTest.withoutFactsThePassBehavesAsBefore();
		MatchPlanLlmPassPathEvidenceTest.theSystemPromptAndTheEntityBlockSayIt();
		System.out.println("MatchPlanLlmPassPathEvidenceTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		return new MatchPlanLlmPass.AllowedVocabulary(Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@exactFullText", "ebx-addon-mame@jarowinkler"),
			Arrays.asList("MATCH", "NOT_MATCH"), Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.Session session(final KeyFacts pFacts)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Personne", PathEvidenceRuleTest.run7Entity(pFacts));
		return new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(), MatchPlanLlmPassPathEvidenceTest.vocabulary());
	}

	private static String exact(final String pField, final String pAlgorithm, final int pScore, final String pVia)
	{
		return "{\"field\":\"" + pField + "\"" + (pVia == null ? "" : ",\"via\":\"" + pVia + "\"") + ",\"algorithm\":\"ebx-addon-mame@" + pAlgorithm + "\",\"minimumScore\":" + pScore
			+ ",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String names()
	{
		return "{\"id\":\"N7\",\"function\":\"AVERAGE\",\"minimumScore\":85,\"fields\":[{\"field\":\"nom\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":1,\"bothNullValueHandling\":\"NOT_MATCH\","
			+ "\"oneNullValueHandling\":\"NOT_MATCH\"},{\"field\":\"prenom\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":1,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}],"
			+ "\"onTrue\":\"N9\",\"onFalse\":\"NOT_MATCHED\"}";
	}

	private static String node(final String pId, final String pField, final String pOnTrue, final String pOnFalse)
	{
		return "{\"id\":\"" + pId + "\",\"function\":\"ALL_FIELDS\",\"fields\":[" + pField + "],\"onTrue\":\"" + pOnTrue + "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	/** The shape of plan 74's name branch: the name key, similar names, then the related address - the last node is the variable part. */
	private static String plan(final String pLastNode)
	{
		return "{\"entity\":\"Personne\",\"root\":\"C0\",\"nodes\":["
			+ MatchPlanLlmPassPathEvidenceTest.node("C0", MatchPlanLlmPassPathEvidenceTest.exact("nomCompletKey", "exact", 100, null), "N7", "N4") + ","
			+ MatchPlanLlmPassPathEvidenceTest.names() + "," + pLastNode + ","
			+ MatchPlanLlmPassPathEvidenceTest.node("N4", MatchPlanLlmPassPathEvidenceTest.exact("emailKey", "exact", 100, null), "SUSPECTED", "N5") + ","
			+ MatchPlanLlmPassPathEvidenceTest.node("N5", MatchPlanLlmPassPathEvidenceTest.exact("telephoneE164", "exact", 100, null), "SUSPECTED", "N6") + ","
			// the independent-evidence node the relation-roles challenge asks for (R15): a precise field of the related address - lot M14: beside a key of the record itself, never alone
			+ MatchPlanLlmPassPathEvidenceTest.node("N6", MatchPlanLlmPassPathEvidenceTest.exact("nomCompletKey", "exact", 100, null) + "," + MatchPlanLlmPassPathEvidenceTest.exact("street", "exactFullText", 100, "Adresse"),
				"SUSPECTED", "NOT_MATCHED") + "],"
			+ "\"clusteringFields\":[\"emailKey\",\"telephoneE164\",\"nomCompletKey\"],\"rationale\":\"name key then similar names then the address; e-mail and phone alone.\",\"confidence\":80}";
	}

	private static String postalCodeNode()
	{
		return MatchPlanLlmPassPathEvidenceTest.node("N9", MatchPlanLlmPassPathEvidenceTest.exact("postalCode", "exact", 100, "Adresse"), "SUSPECTED", "NOT_MATCHED");
	}

	private static String streetNode()
	{
		return MatchPlanLlmPassPathEvidenceTest.node("N9", MatchPlanLlmPassPathEvidenceTest.exact("street", "exactFullText", 100, "Adresse"), "SUSPECTED", "NOT_MATCHED");
	}

	private static LocalTool.Result submit(final MatchPlanLlmPass.Session pSession, final String pCall)
	{
		try
		{
			return pSession.proposeMatchPlan(MatchPlanLlmPassPathEvidenceTest.MAPPER.readTree(pCall));
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static KeyFacts run7Facts()
	{
		return KeyFacts.fromJson(PathEvidenceRuleTest.resource("run7_keyfacts.json"));
	}

	static void thePathOfRun7IsRefusedThenAPreciseRelationFieldIsAccepted() throws Exception
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassPathEvidenceTest.session(MatchPlanLlmPassPathEvidenceTest.run7Facts());
		final LocalTool.Result refused = MatchPlanLlmPassPathEvidenceTest.submit(session, MatchPlanLlmPassPathEvidenceTest.plan(MatchPlanLlmPassPathEvidenceTest.postalCodeNode()));
		MatchPlanLlmPassPathEvidenceTest.check(refused.isError && refused.text.startsWith("the proposed tree has 1 path(s) to SUSPECTED or MATCH that require no condition identifying a record: path C0 > N7 > N9 "
			+ "(requires nomCompletKey exact, nom average jarowinkler, prenom average jarowinkler, postalCode of Adresse exact -> SUSPECTED)."), "refused, path named: " + refused.text);
		MatchPlanLlmPassPathEvidenceTest.check(refused.text.contains("postalCode of Adresse: each value is shared by 53.4 rows on average (10 distinct values)")
			&& refused.text.contains("point the onTrue of N9 to NOT_MATCHED"), "with the measure and the node to re-point: " + refused.text);
		MatchPlanLlmPassPathEvidenceTest.check(session.createdPlans().isEmpty(), "nothing recorded");

		// a street of the related address is the precise field: accepted, and the plan is stored with the facts it was judged on
		final LocalTool.Result accepted = MatchPlanLlmPassPathEvidenceTest.submit(session, MatchPlanLlmPassPathEvidenceTest.plan(MatchPlanLlmPassPathEvidenceTest.streetNode()));
		MatchPlanLlmPassPathEvidenceTest.check(!accepted.isError && session.createdPlans().size() == 1, "accepted with the street: " + accepted.text);
		final JsonNode stored = MatchPlanLlmPassPathEvidenceTest.MAPPER.readTree(session.createdPlans().get(0).requirementsJson);
		MatchPlanLlmPassPathEvidenceTest.check(stored.path("keyFacts").isObject() && CandidateFilterCheck.check(stored).paths.size() == 4, "stored with its facts");
	}

	static void theRefusalEndsTheEntityTheSecondTimeAndTheFallbackPassesTheSameRule() throws Exception
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassPathEvidenceTest.session(MatchPlanLlmPassPathEvidenceTest.run7Facts());
		MatchPlanLlmPassPathEvidenceTest.check(MatchPlanLlmPassPathEvidenceTest.submit(session, MatchPlanLlmPassPathEvidenceTest.plan(MatchPlanLlmPassPathEvidenceTest.postalCodeNode())).isError, "first refusal");
		final LocalTool.Result second = MatchPlanLlmPassPathEvidenceTest.submit(session, MatchPlanLlmPassPathEvidenceTest.plan(MatchPlanLlmPassPathEvidenceTest.postalCodeNode()).replace("name key then", "the same"));
		MatchPlanLlmPassPathEvidenceTest.check(second.isError && second.text.contains("Giving up on Personne") && second.text.contains("2 times in a row"), "a content refusal ends the entity at the second time: " + second.text);
		final List<MatchPlanLlmPass.Session.FallbackOutcome> outcomes = session.proposeFallbacks();
		MatchPlanLlmPassPathEvidenceTest.check(outcomes.size() == 1 && outcomes.get(0).proposed, "the fallback is accepted by the same checks, path rule included: " + (outcomes.isEmpty() ? "none" : outcomes.get(0).reason));
		final MatchPlanLlmPass.CreatedPlan plan = session.createdPlans().get(0);
		final JsonNode stored = MatchPlanLlmPassPathEvidenceTest.MAPPER.readTree(plan.requirementsJson);
		final StringBuilder tree = new StringBuilder();
		for (final JsonNode node : stored.path("decisionTree").path("nodes"))
		{
			for (final JsonNode field : node.path("fields"))
			{
				tree.append(node.path("id").asText()).append(':').append(field.path("field").asText()).append("->").append(node.path("onTrue").asText()).append(' ');
			}
		}
		MatchPlanLlmPassPathEvidenceTest.check(tree.toString().trim().equals("F1:telephoneE164->MATCH F1:nomCompletKey->MATCH F2:emailKey->SUSPECTED F3:telephoneE164->SUSPECTED F4:nomCompletKey->SUSPECTED F4:addressKeys->SUSPECTED"),
			"the name key never stands alone, and child evidence never stands alone either: " + tree);
	}

	static void withoutFactsThePassBehavesAsBefore()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassPathEvidenceTest.session(null);
		final LocalTool.Result result = MatchPlanLlmPassPathEvidenceTest.submit(session, MatchPlanLlmPassPathEvidenceTest.plan(MatchPlanLlmPassPathEvidenceTest.postalCodeNode()));
		MatchPlanLlmPassPathEvidenceTest.check(!result.isError && session.createdPlans().size() == 1, "no facts, no rule: " + result.text);
	}

	static void theSystemPromptAndTheEntityBlockSayIt()
	{
		final String system = MatchPlanLlmPass.systemPrompt();
		MatchPlanLlmPassPathEvidenceTest.check(system.contains("EVERY PATH NEEDS A CONDITION THAT IDENTIFIES A RECORD") && system.contains("Identifying keys") && system.contains("never a postal code or a city")
			&& system.contains("Where an entity has no identifying key at all the rule does not apply"), "the system prompt states the rule");
		final MatchPlanLlmPass.EntityInfo entity = PathEvidenceRuleTest.run7Entity(MatchPlanLlmPassPathEvidenceTest.run7Facts());
		final String prompt = MatchPlanLlmPass.userPrompt(new MatchPlanLlmPass.ProjectInfo("P", "P", Arrays.asList(entity), new ArrayList<String>(), null, false));
		MatchPlanLlmPassPathEvidenceTest.check(prompt.contains("  Identifying keys on Personne: emailKey, addressKeys, telephone, telephoneE164, dateNaissance ("), "and each entity's block names its identifying keys");
	}
}
