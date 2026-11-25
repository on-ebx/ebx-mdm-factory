package com.on.ebx.factory.review;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.B2cKeyFixtureTest;

/**
 * JUnit-free: run the main, a failure throws. Lot M3 ({@code docs/MATCHING.md} &sect;56): the findings on the plan card ({@link MatchPlanView#renderKeyFindings}), in both
 * languages, on the real plan of the B2C master scenario with the measured facts stored in it.
 */
public final class MatchPlanKeyFindingsViewTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanKeyFindingsViewTest.englishFindings();
		MatchPlanKeyFindingsViewTest.frenchFindings();
		MatchPlanKeyFindingsViewTest.nothingToSayMeansNoMarkup();
		MatchPlanKeyFindingsViewTest.theTreeBlockCarriesThem();
		System.out.println("MatchPlanKeyFindingsViewTest OK");
	}

	private static String key(final String pField, final String pAlgorithm, final int pMinimumScore)
	{
		return "{\"field\":\"" + pField + "\",\"algorithm\":\"" + pAlgorithm + "\",\"minimumScore\":" + pMinimumScore + ",\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}";
	}

	/** MatchPlan 55 of the live scenario: the e-mail key, then names, then address keys; every path ends in a review. */
	private static ObjectNode plan() throws Exception
	{
		final ObjectNode node = (ObjectNode) MatchPlanKeyFindingsViewTest.MAPPER.readTree("{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanKeyFindingsViewTest.key("emailKey", "ebx-addon-mame@exact", 100) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N2\"},"
			+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":82,\"fields\":[{\"field\":\"firstName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3}],\"onTrue\":\"N3\",\"onFalse\":\"N4\"},"
			+ "{\"id\":\"N3\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanKeyFindingsViewTest.key("addressKeys", "ebx-addon-mame@exactFullText", 50) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"N4\",\"function\":\"ALL_FIELDS\",\"fields\":[" + MatchPlanKeyFindingsViewTest.key("addressKeys", "ebx-addon-mame@exactFullText", 50) + "],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},"
			+ "\"clusteringFields\":[\"emailKey\"]}");
		node.set("keyFacts", B2cKeyFixtureTest.facts().toJson());
		return node;
	}

	private static void englishFindings() throws Exception
	{
		final String html = MatchPlanView.renderKeyFindings(MatchPlanKeyFindingsViewTest.plan(), Locale.ENGLISH);
		B2cKeyFixtureTest.check(html.contains("What the measured data says about this plan"), "title: " + html);
		B2cKeyFixtureTest.check(html.contains("Path N1 is safe to merge automatically: 341 pair(s) of records share emailKey, and 341 of the 341 that can be compared also share fullNameKey; 0 differ."),
			"the safe path: " + html);
		B2cKeyFixtureTest.check(html.contains("To make it a confirmed match, require emailKey and fullNameKey together on that path"), "what to do: " + html);
		B2cKeyFixtureTest.check(html.contains("Source STORES: 103 of 234 record(s) fill none of the candidate-filter keys (emailKey, addressKeys)"), "the unreachable tickets: " + html);
		B2cKeyFixtureTest.check(html.contains("That source fills ") && html.contains("fullNameKey (100%)") && html.contains("mobileE164 (100%)"), "and what they do fill: " + html);
		B2cKeyFixtureTest.check(html.contains("mdmf-key-finding-safe") && html.contains("mdmf-key-finding-reach"), "styled");
	}

	private static void frenchFindings() throws Exception
	{
		final String html = MatchPlanView.renderKeyFindings(MatchPlanKeyFindingsViewTest.plan(), Locale.FRENCH);
		B2cKeyFixtureTest.check(html.contains("Ce que disent les données mesurées sur ce plan"), "titre: " + html);
		B2cKeyFixtureTest.check(html.contains("Le chemin N1 peut être fusionné automatiquement sans risque") && html.contains("341 paire(s) d’enregistrements partagent emailKey"), "chemin sûr: " + html);
		B2cKeyFixtureTest.check(html.contains("341 des 341 paires comparables partagent aussi fullNameKey") && html.contains("0 diffèrent"), "chiffres: " + html);
		B2cKeyFixtureTest.check(html.contains("Source STORES : 103 enregistrement(s) sur 234") || html.contains("Source STORES : 103 enregistrement(s) sur 234"), "tickets: " + html);
	}

	private static void nothingToSayMeansNoMarkup() throws Exception
	{
		final ObjectNode withoutFacts = MatchPlanKeyFindingsViewTest.plan();
		withoutFacts.remove("keyFacts");
		B2cKeyFixtureTest.check(MatchPlanView.renderKeyFindings(withoutFacts, Locale.ENGLISH).isEmpty(), "a plan from before the lot shows nothing");
		B2cKeyFixtureTest.check(MatchPlanView.renderKeyFindings(MatchPlanKeyFindingsViewTest.MAPPER.createObjectNode(), Locale.ENGLISH).isEmpty(), "an empty plan shows nothing");
	}

	private static void theTreeBlockCarriesThem() throws Exception
	{
		final String json = MatchPlanKeyFindingsViewTest.plan().toString();
		final MatchPlanView.Input input = new MatchPlanView.Input("Client", json, null, false, false, null, null, 0, "55", Locale.ENGLISH);
		final String html = MatchPlanView.renderTree(input);
		B2cKeyFixtureTest.check(html.contains("mdmf-key-findings") && html.contains("Path N1 is safe to merge automatically"), "the tree block of the card (and of the wizard) carries the findings");
	}
}
