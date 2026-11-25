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
import com.on.ebx.factory.matching.CandidateFilterCheck;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws.
 * <p>
 * Lot M8a (2026-10-04, {@code docs/MATCHING.md} &sect;51): the candidate-filter rule inside {@link MatchPlanLlmPass} - the contract the model is told, the refusal of a plan whose
 * candidate search would not be filtered (with the per-path explanation), the explicit way out for an entity with no exact key, and a derived child-keys attribute
 * ({@code CHILD_KEYS}) accepted as a tree field and as a clustering field with {@code exactFullText} only.
 */
public final class MatchPlanLlmPassCandidateFilterTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanLlmPassCandidateFilterTest.contractIsToldToTheModel();
		MatchPlanLlmPassCandidateFilterTest.schemaOffersTheExplicitWayOut();
		MatchPlanLlmPassCandidateFilterTest.derivationConstantMatchesTheStagingOne();
		MatchPlanLlmPassCandidateFilterTest.e3PlanIsAcceptedAndFiltered();
		MatchPlanLlmPassCandidateFilterTest.aPlanWithoutAnyKeyPathIsRefusedWithTheExplanation();
		MatchPlanLlmPassCandidateFilterTest.aKeyWithTheWrongNullHandlingIsRefusedAsNotFiltered();
		MatchPlanLlmPassCandidateFilterTest.theExplicitWayOutStoresTheReasonAndTheWarning();
		MatchPlanLlmPassCandidateFilterTest.theWayOutNeedsAReason();
		MatchPlanLlmPassCandidateFilterTest.theWayOutIsIgnoredWhenThePlanIsFiltered();
		MatchPlanLlmPassCandidateFilterTest.aSessionThatDidNotAskForTheRuleKeepsProposingAsBefore();
		MatchPlanLlmPassCandidateFilterTest.childKeysNeedExactFullText();
		MatchPlanLlmPassCandidateFilterTest.childKeysNullHandlingIsNotMatch_omittedMeansNotMatch();
		MatchPlanLlmPassCandidateFilterTest.childKeysMinimumScoreIsCapped();
		MatchPlanLlmPassCandidateFilterTest.childKeysClusteringFieldIsAcceptedOnlyThroughATreeNode();
		MatchPlanLlmPassCandidateFilterTest.childKeysThresholdAboveTheMeasuredCeilingIsNotedInTheRationale();
		MatchPlanLlmPassCandidateFilterTest.promptRendersTheChildKeysAttribute();
		System.out.println("MatchPlanLlmPassCandidateFilterTest OK");
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	private static MatchPlanLlmPass.AllowedVocabulary vocabulary()
	{
		// DO_NOTHING first on purpose: an omitted null handling resolves to it for an ordinary field, and must still be NOT_MATCH for child keys
		return new MatchPlanLlmPass.AllowedVocabulary(
			Arrays.asList("ebx-addon-mame@exact", "ebx-addon-mame@exactFullText", "ebx-addon-mame@jarowinkler", "ebx-addon-mame@hybridFuzzy", "ebx-addon-mame@operator"),
			Arrays.asList("DO_NOTHING", "NOT_MATCH"), Arrays.asList("mostCompleted", "lastUpdate", "mostTrustedSource"));
	}

	private static MatchPlanLlmPass.AttributeInfo attribute(final String pName, final int pDistinct)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 80, null, Double.valueOf(0.9), "EXACT", Integer.valueOf(pDistinct), "EXACT", null, null, null, null, true,
			null, Collections.<String>emptyList(), Collections.<String>emptyList());
	}

	private static MatchPlanLlmPass.AttributeInfo childKeys(final String pName, final String pChild)
	{
		return new MatchPlanLlmPass.AttributeInfo(pName, pName, "STRING", 2000, null, Double.valueOf(0.85), "EXACT", Integer.valueOf(950), "EXACT", null, null, null, null, true, null,
			Collections.<String>emptyList(), Collections.<String>emptyList(), null, null, null, null, null, null, null, null, null,
			MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS, pChild);
	}

	/** Personne: e-mail, phone, names, one derived child-keys attribute of Adresse (at most {@code pMaxChildren} addresses per person) and Adresse as relation evidence. */
	private static MatchPlanLlmPass.EntityInfo personne(final Integer pMaxChildren)
	{
		final List<MatchPlanLlmPass.AttributeInfo> attributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		attributes.add(MatchPlanLlmPassCandidateFilterTest.attribute("emailKey", 500));
		attributes.add(MatchPlanLlmPassCandidateFilterTest.attribute("phoneE164", 450));
		attributes.add(MatchPlanLlmPassCandidateFilterTest.attribute("lastName", 400));
		attributes.add(MatchPlanLlmPassCandidateFilterTest.attribute("firstName", 300));
		attributes.add(MatchPlanLlmPassCandidateFilterTest.childKeys("adresseKeys", "Adresse"));
		final List<MatchPlanLlmPass.AttributeInfo> childAttributes = new ArrayList<MatchPlanLlmPass.AttributeInfo>();
		childAttributes.add(MatchPlanLlmPassCandidateFilterTest.attribute("cityKey", 40));
		childAttributes.add(MatchPlanLlmPassCandidateFilterTest.attribute("postalCode", 35));
		final List<MatchPlanLlmPass.RelationChildInfo> relationChildren = Collections.singletonList(
			new MatchPlanLlmPass.RelationChildInfo("Adresse", childAttributes, Integer.valueOf(1), pMaxChildren, Double.valueOf(1.4), Double.valueOf(0.05)));
		return new MatchPlanLlmPass.EntityInfo("Personne", "Personne", 1000, attributes, null, null, null, null, null, relationChildren);
	}

	private static MatchPlanLlmPass.Session session(final Integer pMaxChildren, final boolean pRequireFiltered)
	{
		final Map<String, MatchPlanLlmPass.EntityInfo> entities = new LinkedHashMap<String, MatchPlanLlmPass.EntityInfo>();
		entities.put("Personne", MatchPlanLlmPassCandidateFilterTest.personne(pMaxChildren));
		final MatchPlanLlmPass.Session session = new MatchPlanLlmPass.Session(entities, new LinkedHashMap<String, String>(), new ArrayList<MatchPlanLlmPass.ExistingPlan>(),
			MatchPlanLlmPassCandidateFilterTest.vocabulary());
		return pRequireFiltered ? session.requireFilteredCandidateSearch() : session;
	}

	private static String field(final String pName, final String pAlgorithm, final int pMinimumScore)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"" + pAlgorithm + "\",\"minimumScore\":" + pMinimumScore
			+ ",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String similar(final String pName, final int pWeight)
	{
		return "{\"field\":\"" + pName + "\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":" + pWeight + ",\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"NOT_MATCH\"}";
	}

	private static String relation(final String pName)
	{
		return "{\"field\":\"" + pName + "\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":1}";
	}

	/** The E3 tree as a {@code propose_match_plan} call; {@code pAdresseKeysField} is the JSON of the field P5 compares, {@code pExtra} extra top-level members. */
	private static String e3Call(final String pAdresseKeysField, final String pExtra)
	{
		return "{\"entity\":\"Personne\",\"root\":\"P1\",\"nodes\":["
			+ "{\"id\":\"P1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.field("emailKey", "ebx-addon-mame@exact", 100) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"P2\"},"
			+ "{\"id\":\"P2\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.field("phoneE164", "ebx-addon-mame@exact", 100) + "],\"onTrue\":\"P2B\",\"onFalse\":\"P5\"},"
			+ "{\"id\":\"P2B\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.similar("lastName", 5) + "," + MatchPlanLlmPassCandidateFilterTest.similar("firstName", 4)
			+ "],\"onTrue\":\"P3\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"P3\",\"function\":\"AVERAGE\",\"minimumScore\":76,\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.relation("cityKey") + "," + MatchPlanLlmPassCandidateFilterTest.relation("postalCode")
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"P5\",\"function\":\"ALL_FIELDS\",\"fields\":[" + pAdresseKeysField + "],\"onTrue\":\"P5B\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"P5B\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.similar("lastName", 5) + "," + MatchPlanLlmPassCandidateFilterTest.similar("firstName", 4)
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],"
			+ "\"clusteringFields\":[\"emailKey\",\"phoneE164\",\"adresseKeys\"],\"rationale\":\"three exact keys, each starting a path; names and addresses after.\",\"confidence\":80" + pExtra + "}";
	}

	private static String adresseKeys()
	{
		return MatchPlanLlmPassCandidateFilterTest.field("adresseKeys", "ebx-addon-mame@exactFullText", 20);
	}

	/**
	 * Submits {@code pCall}; the relation-evidence challenge (lot R15, a plan that only filters with child evidence is refused ONCE) is answered by resubmitting the identical call,
	 * which is what a model does - so a call that fails for any other reason fails twice with the same text.
	 */
	private static LocalTool.Result submit(final MatchPlanLlmPass.Session pSession, final String pCall)
	{
		final LocalTool.Result first = pSession.proposeMatchPlan(MatchPlanLlmPassCandidateFilterTest.parse(pCall));
		// lot M13: a one-shot challenge told together with other violations (the answer says "all reported at once") is the whole answer, not a challenge to resubmit through
		if (!first.isError || !first.text.contains("child evidence") || first.text.contains("all reported at once"))
		{
			return first;
		}
		return pSession.proposeMatchPlan(MatchPlanLlmPassCandidateFilterTest.parse(pCall));
	}

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchPlanLlmPassCandidateFilterTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static JsonNode requirementsOfFirstPlan(final MatchPlanLlmPass.Session pSession)
	{
		return MatchPlanLlmPassCandidateFilterTest.parse(pSession.createdPlans().get(0).requirementsJson);
	}

	// ------------------------------------------------------------------------------------------------------- tests

	private static void contractIsToldToTheModel()
	{
		final String prompt = MatchPlanLlmPass.systemPrompt();
		check("the system prompt has the candidate-filters paragraph", prompt.contains("CANDIDATE FILTERS"));
		check("it states the rule: every path starts with an all-identical node on an exact key of the record itself",
			prompt.contains("EVERY path of your tree that ends in SUSPECTED or MATCH starts with an 'all identical' node (ALL_FIELDS) on an exact key of the record itself"));
		check("empty values never match", prompt.contains("bothNullValueHandling and oneNullValueHandling both NOT_MATCH"));
		check("similarity and related records come after", prompt.contains("AFTER such a node, never instead of it"));
		check("child keys: exactFullText and a low threshold", prompt.contains("derived child-keys attribute") && prompt.contains("exactFullText")
			&& prompt.contains("100 divided by the largest number of child records one record has") && prompt.contains("20 accepts a shared child among up to 5"));
		check("the measured figures", prompt.contains("9 times slower and 10 points of recall lower at 1 million rows") && prompt.contains("78 min 40")
			&& prompt.contains("91.9 %") && prompt.contains("8 min 21") && prompt.contains("99.15 %"));
		check("the way out is named", prompt.contains("acceptUnfilteredCandidateSearch") && prompt.contains("unfilteredCandidateSearchReason"));
	}

	private static void schemaOffersTheExplicitWayOut() throws Exception
	{
		final java.lang.reflect.Method build = Class.forName("com.on.ebx.factory.master.MatchPlanLlmPass$Session").getDeclaredMethod("buildProposeSchema",
			MatchPlanLlmPass.AllowedVocabulary.class);
		build.setAccessible(true);
		final JsonNode schema = (JsonNode) build.invoke(null, MatchPlanLlmPassCandidateFilterTest.vocabulary());
		check("acceptUnfilteredCandidateSearch is a boolean", "boolean".equals(schema.path("properties").path("acceptUnfilteredCandidateSearch").path("type").asText()));
		check("its reason is a string", "string".equals(schema.path("properties").path("unfilteredCandidateSearchReason").path("type").asText()));
		check("both are optional", !schema.path("required").toString().contains("nfilteredCandidateSearch"));
		check("the description names the cost", schema.path("properties").path("acceptUnfilteredCandidateSearch").path("description").asText().contains("9 times slower"));
	}

	private static void derivationConstantMatchesTheStagingOne()
	{
		check("the pass's literal is the staging derivation value", com.on.ebx.factory.quality.ChildKeysDerivation.DERIVATION_CHILD_KEYS
			.equals(MatchPlanLlmPass.AttributeInfo.DERIVATION_CHILD_KEYS));
	}

	private static void e3PlanIsAcceptedAndFiltered()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session, MatchPlanLlmPassCandidateFilterTest.e3Call(MatchPlanLlmPassCandidateFilterTest.adresseKeys(), ""));
		check("E3 accepted: " + result.text, !result.isError);
		final JsonNode requirements = MatchPlanLlmPassCandidateFilterTest.requirementsOfFirstPlan(session);
		check("it is FILTERED on the three keys", CandidateFilterCheck.check(requirements).filtered()
			&& "emailKey OR phoneE164 OR adresseKeys".equals(CandidateFilterCheck.check(requirements).filtersPhrase()));
		check("clustering fields kept, adresseKeys included", requirements.path("clusteringFields").toString().equals("[\"emailKey\",\"phoneE164\",\"adresseKeys\"]"));
		check("no reason stored for a filtered plan", requirements.path("unfilteredCandidateSearchReason").isMissingNode());
		final JsonNode p5 = requirements.path("decisionTree").path("nodes").get(4).path("fields").get(0);
		check("the child-keys field is stored with exactFullText, 20, NOT_MATCH/NOT_MATCH", "ebx-addon-mame@exactFullText".equals(p5.path("algorithm").asText())
			&& p5.path("minimumScore").asInt() == 20 && "NOT_MATCH".equals(p5.path("bothNull").asText()) && "NOT_MATCH".equals(p5.path("oneNull").asText()));
	}

	private static void aPlanWithoutAnyKeyPathIsRefusedWithTheExplanation()
	{
		// names by AVERAGE at the root, the address by relation fields: no path starts with an exact key
		final String call = "{\"entity\":\"Personne\",\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.similar("lastName", 5) + "],\"onTrue\":\"N2\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":70,\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.relation("cityKey") + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],"
			+ "\"clusteringFields\":[],\"rationale\":\"names then city.\",\"confidence\":50}";
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session, call);
		check("refused: " + result.text, result.isError);
		check("it says the search would not be filtered and what that costs", result.text.contains("would NOT be filtered") && result.text.contains("9 times slower"));
		check("it names the path and why: " + result.text, result.text.contains("Path N1 > N2 (SUSPECTED)") && result.text.contains("not an 'all fields match'"));
		check("it names the way out", result.text.contains("acceptUnfilteredCandidateSearch") && result.text.contains("unfilteredCandidateSearchReason"));
		check("nothing is recorded", session.createdPlans().isEmpty());
	}

	private static void aKeyWithTheWrongNullHandlingIsRefusedAsNotFiltered()
	{
		// the e-mail key exists but a missing value could match: the add-on would ignore it as a filter
		final String call = MatchPlanLlmPassCandidateFilterTest.e3Call(MatchPlanLlmPassCandidateFilterTest.adresseKeys(), "").replace(
			MatchPlanLlmPassCandidateFilterTest.field("phoneE164", "ebx-addon-mame@exact", 100),
			"{\"field\":\"phoneE164\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNullValueHandling\":\"DO_NOTHING\",\"oneNullValueHandling\":\"NOT_MATCH\"}");
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session, call);
		check("refused: " + result.text, result.isError && result.text.contains("would NOT be filtered"));
		check("the explanation blames the null handling of phoneE164: " + result.text, result.text.contains("field 'phoneE164'") && result.text.contains("bothNull is DO_NOTHING"));
	}

	private static void theExplicitWayOutStoresTheReasonAndTheWarning()
	{
		final String call = "{\"entity\":\"Personne\",\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.similar("lastName", 5) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],"
			+ "\"clusteringFields\":[],\"rationale\":\"names only.\",\"confidence\":40,"
			+ "\"acceptUnfilteredCandidateSearch\":true,\"unfilteredCandidateSearchReason\":\"This entity has no field that repeats exactly across sources.\"}";
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session, call);
		check("accepted with the way out: " + result.text, !result.isError);
		final JsonNode requirements = MatchPlanLlmPassCandidateFilterTest.requirementsOfFirstPlan(session);
		check("the reason is stored in the plan", "This entity has no field that repeats exactly across sources.".equals(CandidateFilterCheck.acceptedReason(requirements)));
		final String rationale = session.createdPlans().get(0).rationale;
		check("the rationale carries the warning: " + rationale, rationale.contains("Candidate search NOT filtered (accepted by the model: This entity has no field")
			&& rationale.contains("Path N1 (SUSPECTED)"));
		check("the plan is still judged NOT_FILTERED by the check", CandidateFilterCheck.check(requirements).notFiltered());
	}

	private static void theWayOutNeedsAReason()
	{
		for (final String reason : new String[] { "", ",\"unfilteredCandidateSearchReason\":\"   \"" })
		{
			final String call = "{\"entity\":\"Personne\",\"root\":\"N1\",\"nodes\":["
				+ "{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.similar("lastName", 5)
				+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[],\"rationale\":\"names only.\",\"confidence\":40,"
				+ "\"acceptUnfilteredCandidateSearch\":true" + reason + "}";
			final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
			final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session, call);
			check("refused without a reason (" + reason + "): " + result.text, result.isError && result.text.contains("needs a non-empty unfilteredCandidateSearchReason"));
			check("nothing recorded", session.createdPlans().isEmpty());
		}
	}

	private static void theWayOutIsIgnoredWhenThePlanIsFiltered()
	{
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session, MatchPlanLlmPassCandidateFilterTest.e3Call(MatchPlanLlmPassCandidateFilterTest.adresseKeys(),
			",\"acceptUnfilteredCandidateSearch\":true,\"unfilteredCandidateSearchReason\":\"not needed\""));
		check("accepted: " + result.text, !result.isError);
		check("no reason is stored for a plan that IS filtered", MatchPlanLlmPassCandidateFilterTest.requirementsOfFirstPlan(session).path("unfilteredCandidateSearchReason").isMissingNode());
	}

	private static void aSessionThatDidNotAskForTheRuleKeepsProposingAsBefore()
	{
		final String call = "{\"entity\":\"Personne\",\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.similar("lastName", 5)
			+ "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],\"clusteringFields\":[],\"rationale\":\"names only.\",\"confidence\":40}";
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), false);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session, call);
		check("not refused when the rule is off: " + result.text, !result.isError);
		check("and the rationale is untouched", !session.createdPlans().get(0).rationale.contains("Candidate search"));
	}

	private static void childKeysNeedExactFullText()
	{
		for (final String algorithm : new String[] { "ebx-addon-mame@exact", "ebx-addon-mame@jarowinkler" })
		{
			final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
			final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session,
				MatchPlanLlmPassCandidateFilterTest.e3Call(MatchPlanLlmPassCandidateFilterTest.field("adresseKeys", algorithm, 20), ""));
			check(algorithm + " refused: " + result.text, result.isError && result.text.contains("compare it with the ebx-addon-mame@exactFullText algorithm only"));
		}
	}

	private static void childKeysNullHandlingIsNotMatch_omittedMeansNotMatch()
	{
		final MatchPlanLlmPass.Session explicit = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
		final LocalTool.Result refused = MatchPlanLlmPassCandidateFilterTest.submit(explicit, MatchPlanLlmPassCandidateFilterTest.e3Call(
			"{\"field\":\"adresseKeys\",\"algorithm\":\"ebx-addon-mame@exactFullText\",\"minimumScore\":20,\"bothNullValueHandling\":\"NOT_MATCH\",\"oneNullValueHandling\":\"DO_NOTHING\"}", ""));
		check("an explicit DO_NOTHING is refused: " + refused.text, refused.isError && refused.text.contains("oneNullValueHandling must be NOT_MATCH"));

		final MatchPlanLlmPass.Session omitted = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
		final LocalTool.Result accepted = MatchPlanLlmPassCandidateFilterTest.submit(omitted,
			MatchPlanLlmPassCandidateFilterTest.e3Call("{\"field\":\"adresseKeys\",\"algorithm\":\"ebx-addon-mame@exactFullText\",\"minimumScore\":20}", ""));
		check("omitted is accepted: " + accepted.text, !accepted.isError);
		final JsonNode field = MatchPlanLlmPassCandidateFilterTest.requirementsOfFirstPlan(omitted).path("decisionTree").path("nodes").get(4).path("fields").get(0);
		check("and written as NOT_MATCH on both sides although the vocabulary's first value is DO_NOTHING",
			"NOT_MATCH".equals(field.path("bothNull").asText()) && "NOT_MATCH".equals(field.path("oneNull").asText()));
		check("which keeps the plan FILTERED", CandidateFilterCheck.check(MatchPlanLlmPassCandidateFilterTest.requirementsOfFirstPlan(omitted)).filtered());
	}

	private static void childKeysMinimumScoreIsCapped()
	{
		for (final int score : new int[] { 100, 51 })
		{
			final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
			final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session,
				MatchPlanLlmPassCandidateFilterTest.e3Call(MatchPlanLlmPassCandidateFilterTest.field("adresseKeys", "ebx-addon-mame@exactFullText", score), ""));
			check(score + " refused: " + result.text, result.isError && result.text.contains("minimumScore " + score + " is too high"));
			check("the hint gives the formula and the measured ceiling (5 addresses -> 20): " + result.text,
				result.text.contains("100 divided by the largest number of child records one record has") && result.text.contains("measured here, one record has 5 child record(s) at most, which supports 20")
					&& result.text.contains("this pass refuses anything above 50") && result.text.contains("Set minimumScore to 20 or less."));
			check("the ceiling it states is the one it applies: never 'at most 100' next to 'never above 50': " + result.text, !result.text.contains("at most 100") && !result.text.contains("never above"));
		}
		final MatchPlanLlmPass.Session unknown = MatchPlanLlmPassCandidateFilterTest.session(null, true);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(unknown,
			MatchPlanLlmPassCandidateFilterTest.e3Call(MatchPlanLlmPassCandidateFilterTest.field("adresseKeys", "ebx-addon-mame@exactFullText", 80), ""));
		check("without a measured maximum the hint gives the examples: " + result.text, result.isError && result.text.contains("20 accepts a shared child among up to 5") && result.text.contains("Set minimumScore to 50 or less."));
		final MatchPlanLlmPass.Session fifty = MatchPlanLlmPassCandidateFilterTest.session(null, true);
		check("50 is accepted", !MatchPlanLlmPassCandidateFilterTest.submit(fifty,
			MatchPlanLlmPassCandidateFilterTest.e3Call(MatchPlanLlmPassCandidateFilterTest.field("adresseKeys", "ebx-addon-mame@exactFullText", 50), "")).isError);
	}

	private static void childKeysClusteringFieldIsAcceptedOnlyThroughATreeNode()
	{
		// accepted in E3 (see e3PlanIsAcceptedAndFiltered): the fill/distinctness guards do not apply to it although 950 of 1000 rows are distinct (> 90 %)
		// not compared by any node: refused with the child-keys wording
		final String call = "{\"entity\":\"Personne\",\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanLlmPassCandidateFilterTest.field("emailKey", "ebx-addon-mame@exact", 100) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}],"
			+ "\"clusteringFields\":[\"adresseKeys\"],\"rationale\":\"e-mail only.\",\"confidence\":50}";
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(5), true);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session, call);
		check("refused: " + result.text, result.isError && result.text.contains("holds derived child keys") && result.text.contains("It is not in any node of this plan"));
	}

	private static void childKeysThresholdAboveTheMeasuredCeilingIsNotedInTheRationale()
	{
		// at most 40 addresses per person: the ceiling is 2; a threshold of 20 is allowed (<= 50) but misses pairs sharing a child between people with more than 5
		final MatchPlanLlmPass.Session session = MatchPlanLlmPassCandidateFilterTest.session(Integer.valueOf(40), true);
		final LocalTool.Result result = MatchPlanLlmPassCandidateFilterTest.submit(session,
			MatchPlanLlmPassCandidateFilterTest.e3Call(MatchPlanLlmPassCandidateFilterTest.adresseKeys(), ""));
		check("accepted (a fact, not a refusal): " + result.text, !result.isError);
		final String rationale = session.createdPlans().get(0).rationale;
		check("the rationale says the threshold is above the ceiling: " + rationale, rationale.contains("Child keys: 'adresseKeys' in node 'P5' needs minimumScore 20, above 2 (100 / 40 child records at most)")
			&& rationale.contains("neither has more than 5 child record(s)"));
		check("no note when the threshold fits (5 -> 20)", MatchPlanLlmPass.childKeysThresholdNotes(MatchPlanLlmPassCandidateFilterTest.personne(Integer.valueOf(5)),
			(com.fasterxml.jackson.databind.node.ArrayNode) MatchPlanLlmPassCandidateFilterTest.parse("[{\"id\":\"P5\",\"fields\":[{\"field\":\"adresseKeys\",\"minimumScore\":20}]}]")).isEmpty());
	}

	private static void promptRendersTheChildKeysAttribute()
	{
		final MatchPlanLlmPass.ProjectInfo project = new MatchPlanLlmPass.ProjectInfo("P", "Project", Collections.singletonList(MatchPlanLlmPassCandidateFilterTest.personne(Integer.valueOf(5))),
			new ArrayList<String>(), null, false);
		final String prompt = MatchPlanLlmPass.userPrompt(project);
		check("the attribute is marked: " + prompt, prompt.contains("adresseKeys") && prompt.contains("derived child keys of Adresse (at most 5 per record: minimumScore up to 20)"));
		check("and its hint says how to compare it", prompt.contains("[derived child keys of Adresse: one opaque key per child record, compared with ebx-addon-mame@exactFullText (never exact)"));
	}

	private static void check(final String pDescription, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pDescription);
		}
	}
}
