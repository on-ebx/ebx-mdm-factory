package com.on.ebx.factory.review;

import java.util.LinkedHashMap;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.CandidateFilterCheckTest;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Lot M8a (2026-10-04, {@code docs/MATCHING.md} &sect;51): the candidate-search verdict on
 * the plan card - the sentence in the "How candidates are found" band of {@link MatchPlanTreeDiagram} (filtered on which keys / NOT filtered and which paths lack one, in
 * both languages), the unchanged M2 sentence about relation fields, and the notice {@link MatchPlanView#renderCandidateSearchNotice} gives a plan whose tree is not drawn.
 */
public final class MatchPlanCandidateSearchTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanCandidateSearchTest.filteredPlan_bandNamesTheKeysInPathOrder();
		MatchPlanCandidateSearchTest.filteredPlan_inFrench();
		MatchPlanCandidateSearchTest.notFilteredPlan_bandNamesThePathsAndTheMeasuredCost();
		MatchPlanCandidateSearchTest.notFilteredPlan_inFrench();
		MatchPlanCandidateSearchTest.notFilteredPlan_keptOnPurposeShowsTheReason();
		MatchPlanCandidateSearchTest.manyPathsWithoutKeyAreSummarised();
		MatchPlanCandidateSearchTest.theRelationSentenceOfLotM2IsKept();
		MatchPlanCandidateSearchTest.noTreeNoVerdictLine();
		MatchPlanCandidateSearchTest.noticeWarnsWhenAFilteredPlanRunsWithoutTheJvmOption();
		MatchPlanCandidateSearchTest.noticeRepeatsTheNotFilteredVerdictOnlyWhenAsked();
		System.out.println("MatchPlanCandidateSearchTest OK");
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MatchPlanCandidateSearchTest.MAPPER.readTree(pJson);
	}

	private static String band(final JsonNode pRequirements, final Locale pLocale)
	{
		final String html = MatchPlanTreeDiagram.build(pRequirements, new LinkedHashMap<String, MatchPlanFieldEvidence>(), false, "Personne", pLocale);
		final int start = html.indexOf("<text", html.indexOf("font-weight=\"700\""));
		return html.substring(start, html.indexOf("</svg>"));
	}

	/** One AVERAGE node at the root: its only path has no key. */
	private static String averageOnly()
	{
		return "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":["
			+ "{\"field\":\"lastName\",\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":3}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},\"clusteringFields\":[]}";
	}

	private static void filteredPlan_bandNamesTheKeysInPathOrder() throws Exception
	{
		final String band = MatchPlanCandidateSearchTest.band(CandidateFilterCheckTest.e3Requirements(), Locale.ENGLISH);
		MatchPlanCandidateSearchTest.check("the band says the candidates are filtered, on the three keys in path order: " + band,
			band.contains("Candidates are filtered on: Email key OR Phone E164 OR Adresse keys.") || band.contains("Candidates are filtered on: Email key OR Phone E164 OR Address keys."));
		MatchPlanCandidateSearchTest.check("a filtered plan carries no warning line", !band.contains("NOT filtered") && !band.contains("weighted search"));
		MatchPlanCandidateSearchTest.check("the existing search sentence is still there", band.contains("Candidates are first searched on"));
	}

	private static void filteredPlan_inFrench() throws Exception
	{
		final String band = MatchPlanCandidateSearchTest.band(CandidateFilterCheckTest.e3Requirements(), Locale.FRENCH);
		MatchPlanCandidateSearchTest.check("French sentence with OU: " + band, band.contains("Les candidats sont filtrés sur : ") && band.contains(" OU "));
	}

	private static void notFilteredPlan_bandNamesThePathsAndTheMeasuredCost() throws Exception
	{
		final String band = MatchPlanCandidateSearchTest.band(MatchPlanCandidateSearchTest.parse(MatchPlanCandidateSearchTest.averageOnly()), Locale.ENGLISH);
		MatchPlanCandidateSearchTest.check("says NOT filtered and names the path in guillemets: " + band, band.contains("Candidate search is NOT filtered: path «N1» has no exact key."));
		MatchPlanCandidateSearchTest.check("says what it costs", band.contains("compares by weighted search: measured 9 times slower and 10 points of recall lower at 1 million rows"));
		MatchPlanCandidateSearchTest.check("no accepted-reason line without a reason", !band.contains("Kept on purpose"));
	}

	private static void notFilteredPlan_inFrench() throws Exception
	{
		final String band = MatchPlanCandidateSearchTest.band(MatchPlanCandidateSearchTest.parse(MatchPlanCandidateSearchTest.averageOnly()), Locale.FRENCH);
		MatchPlanCandidateSearchTest.check("French: " + band, band.contains("n’est PAS filtrée") && band.contains("«N1»") && band.contains("9 fois plus lent"));
	}

	private static void notFilteredPlan_keptOnPurposeShowsTheReason() throws Exception
	{
		final ObjectNode requirements = (ObjectNode) MatchPlanCandidateSearchTest.parse(MatchPlanCandidateSearchTest.averageOnly());
		requirements.put("unfilteredCandidateSearchReason", "No field repeats exactly across sources.");
		final String band = MatchPlanCandidateSearchTest.band(requirements, Locale.ENGLISH);
		MatchPlanCandidateSearchTest.check("the reason is shown: " + band, band.contains("Kept on purpose: No field repeats exactly across sources."));
	}

	private static void manyPathsWithoutKeyAreSummarised() throws Exception
	{
		// five SUSPECTED leaves reached through five AVERAGE nodes chained on onFalse: five paths without a key
		final StringBuilder nodes = new StringBuilder();
		for (int i = 1; i <= 5; i++)
		{
			nodes.append(i == 1 ? "" : ",").append("{\"id\":\"N").append(i).append("\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":[{\"field\":\"lastName\","
				+ "\"algorithm\":\"ebx-addon-mame@jarowinkler\",\"weight\":1}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"").append(i < 5 ? "N" + (i + 1) : "NOT_MATCHED").append("\"}");
		}
		final String band = MatchPlanCandidateSearchTest.band(MatchPlanCandidateSearchTest.parse("{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[" + nodes + "]},\"clusteringFields\":[]}"),
			Locale.ENGLISH);
		MatchPlanCandidateSearchTest.check("plural wording and the first three paths, then a count: " + band,
			band.contains("paths «N1», «N1 &gt; N2», «N1 &gt; N2 &gt; N3» (+2) have no exact key"));
	}

	private static void theRelationSentenceOfLotM2IsKept() throws Exception
	{
		final String requirements = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"N2\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"N2\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"street\",\"via\":\"Adresse\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]},\"clusteringFields\":[\"emailKey\"]}";
		final String band = MatchPlanCandidateSearchTest.band(MatchPlanCandidateSearchTest.parse(requirements), Locale.ENGLISH);
		MatchPlanCandidateSearchTest.check("the relation sentence of lot M2 is still there: " + band, band.contains("related records (Street) are compared below but take no part in this search"));
		MatchPlanCandidateSearchTest.check("and so is the verdict (the e-mail node on the path gives the key): " + band,
			band.contains("Candidates are filtered on: Email key."));
	}

	private static void noTreeNoVerdictLine() throws Exception
	{
		final String band = MatchPlanCandidateSearchTest.band(MatchPlanCandidateSearchTest.parse("{\"decisionTree\":{\"root\":\"N1\",\"nodes\":[{\"id\":\"N1\",\"function\":\"ALL_FIELDS\","
			+ "\"fields\":[],\"onTrue\":\"NOT_MATCHED\",\"onFalse\":\"NOT_MATCHED\"}]},\"clusteringFields\":[]}"), Locale.ENGLISH);
		MatchPlanCandidateSearchTest.check("no verdict for a tree that never reaches a match: " + band, !band.contains("filtered") && !band.contains("NOT filtered"));
	}

	private static void noticeWarnsWhenAFilteredPlanRunsWithoutTheJvmOption() throws Exception
	{
		final String requirements = CandidateFilterCheckTest.e3Requirements().toString();
		final String off = MatchPlanView.renderCandidateSearchNotice(requirements, false, false, Locale.ENGLISH);
		MatchPlanCandidateSearchTest.check("option off: the notice names it: " + off, off.contains("mdmf-candidate-search")
			&& off.contains("candidate filters are OFF on this server") && off.contains("-Debx.addon.mame.matching.mandatoryFieldSets.enabled=true"));
		MatchPlanCandidateSearchTest.check("option on: nothing", MatchPlanView.renderCandidateSearchNotice(requirements, false, true, Locale.ENGLISH).isEmpty());
		MatchPlanCandidateSearchTest.check("French", MatchPlanView.renderCandidateSearchNotice(requirements, false, false, Locale.FRENCH).contains("DÉSACTIVÉS sur ce serveur"));
	}

	private static void noticeRepeatsTheNotFilteredVerdictOnlyWhenAsked() throws Exception
	{
		final String requirements = MatchPlanCandidateSearchTest.averageOnly();
		MatchPlanCandidateSearchTest.check("a plan whose tree is drawn already carries the verdict in its band: no repeat",
			MatchPlanView.renderCandidateSearchNotice(requirements, false, true, Locale.ENGLISH).isEmpty());
		final String shown = MatchPlanView.renderCandidateSearchNotice(requirements, true, true, Locale.ENGLISH);
		MatchPlanCandidateSearchTest.check("a decided plan (no tree) gets it as a notice: " + shown, shown.contains("Candidate search is NOT filtered: path «N1» has no exact key.")
			&& shown.contains("measured 9 times slower"));
		MatchPlanCandidateSearchTest.check("html is escaped and well formed", shown.startsWith("<p class=\"mdmf-hint mdmf-hint-warn mdmf-candidate-search\">") && shown.endsWith("</p>"));
		MatchPlanCandidateSearchTest.check("unparsable requirements: nothing", MatchPlanView.renderCandidateSearchNotice("not json", true, false, Locale.ENGLISH).isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
