package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers the pure
 * classification/rendering logic of {@link MatchSimulationBlock} - review-match-plan-simulation lot,
 * 2026-09-22 - against hand-built fixtures, no EBX involved (same discipline as {@code MatchPlanViewTest}).
 */
public final class MatchSimulationBlockTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One AVERAGE tier, three fields (fuzzy anchor + two exact fields) - the real MATCH-1 plan shape (task brief). */
	private static final String AVERAGE_PLAN = "{\"tiers\":[{\"outcome\":\"SUSPECTED\",\"type\":\"AVERAGE\",\"minimumScore\":80,"
		+ "\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":10},"
		+ "{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5},"
		+ "{\"field\":\"cityKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5}]}],"
		+ "\"clusteringFields\":[\"country\"],\"mergePolicy\":{}}";

	/** One EXACT tier, a single field - the simplest possible plan. */
	private static final String EXACT_PLAN = "{\"tiers\":[{\"outcome\":\"MATCH\",\"type\":\"EXACT\","
		+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}]}],"
		+ "\"clusteringFields\":[\"vatNo\"],\"mergePolicy\":{}}";

	/**
	 * The validated mockup's own six-node Company tree (`docs/mockups/review-match-plan-binary-tree.html`,
	 * same shape as {@code master.DecisionTreePlanTest#mockupSixNodeTree}/{@code
	 * review.MatchPlanTreeDiagramTest#sixNodeMockupRequirements}) - feeds {@link
	 * #leafWords_heuristicWalk_twoOfTheFourMockupPathExamples}/{@link
	 * #leafWords_enginePath_twoOfTheFourMockupPathExamples} (binary-decision-tree-screen lot, 2026-09-22, task
	 * brief: "path naming for the four mockup examples").
	 */
	private static final String SIX_NODE_MOCKUP_PLAN = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
		+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
		+ "\"onTrue\":\"N2\",\"onFalse\":\"N4\"},"
		+ "{\"id\":\"N2\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
		+ "\"onTrue\":\"MATCH\",\"onFalse\":\"N3\"},"
		+ "{\"id\":\"N3\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"minimumScore\":85}],"
		+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"},"
		+ "{\"id\":\"N4\",\"function\":\"AVERAGE\",\"minimumScore\":80,\"fields\":["
		+ "{\"field\":\"name\",\"algorithm\":\"ebx-addon-mame@fuzzyFullText\",\"weight\":10},"
		+ "{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5},"
		+ "{\"field\":\"cityKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"weight\":5}],\"onTrue\":\"N5\",\"onFalse\":\"NOT_MATCHED\"},"
		+ "{\"id\":\"N5\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"legalForm\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
		+ "\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N6\"},"
		+ "{\"id\":\"N6\",\"function\":\"AT_LEAST_N\",\"minimumMatches\":2,\"fields\":["
		+ "{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100},"
		+ "{\"field\":\"cityKey\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100},"
		+ "{\"field\":\"legalForm\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}"
		+ "]}}";

	public static void main(final String[] pArgs) throws Exception
	{
		MatchSimulationBlockTest.exactFieldNames_onlyExactAlgorithmFields();
		MatchSimulationBlockTest.coverage_isShownBeforeThePairsInBothLanguages();
		MatchSimulationBlockTest.firstAverageTier_foundAndMissing();
		MatchSimulationBlockTest.exactFieldsEqual_trueFalseAndNotApplicable();
		MatchSimulationBlockTest.weightedAverageScore_computedAndMissing();
		MatchSimulationBlockTest.isBorderline_exactlyAtTheMarginAndJustOutside();
		MatchSimulationBlockTest.normalizedVerdict_mapsEveryKnownSpelling();
		MatchSimulationBlockTest.exactCellWords_equalDifferentMissing();
		MatchSimulationBlockTest.analyze_countsAndFamiliesFromAMixOfPairs();
		MatchSimulationBlockTest.analyze_capsEachFamilyAtThreeRowsButCountsEveryOne();
		MatchSimulationBlockTest.analyze_sortsDeterministically();
		MatchSimulationBlockTest.leafWords_exactTierAcceptedAndRefused();
		MatchSimulationBlockTest.leafWords_averageTierAcceptedAndRefused();
		MatchSimulationBlockTest.leafWords_unknownWhenNothingCanBeTold();
		MatchSimulationBlockTest.leafWords_heuristicWalk_twoOfTheFourMockupPathExamples();
		MatchSimulationBlockTest.leafWords_enginePath_twoOfTheFourMockupPathExamples();
		MatchSimulationBlockTest.leafWords_enginePath_ambiguousLabelFallsBackPositionallyAndSaysSo();
		MatchSimulationBlockTest.render_emptyPairsShowsNoneMessage();
		MatchSimulationBlockTest.render_emptyFamilyNeverInventsARow();
		MatchSimulationBlockTest.analyze_sameKeyFlagOverridesExactFieldsFallback();
		MatchSimulationBlockTest.analyze_matchVerdictWithDifferentKeyAlsoCountsAsFamily1();
		MatchSimulationBlockTest.analyze_probedCountsByOrigin();
		MatchSimulationBlockTest.analyze_family0PopulatedOnlyWhenFamilies1To3AreAllEmpty();
		MatchSimulationBlockTest.originWords_mapsEveryKnownOrigin();
		MatchSimulationBlockTest.render_frenchEmptyFamilyMessagesFormatCleanly();
		System.out.println("MatchSimulationBlockTest OK");
	}

	// ------------------------------------------------------------------------------------------------- field roles

	private static void exactFieldNames_onlyExactAlgorithmFields()
	{
		final LinkedHashSet<String> exact = MatchSimulationBlock.exactFieldNames(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		MatchSimulationBlockTest.check("country is exact", exact.contains("country"));
		MatchSimulationBlockTest.check("cityKey is exact", exact.contains("cityKey"));
		MatchSimulationBlockTest.check("name (fuzzy) is not exact", !exact.contains("name"));
		MatchSimulationBlockTest.check("exactly two exact fields", exact.size() == 2);
	}

	private static void firstAverageTier_foundAndMissing()
	{
		final JsonNode found = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		MatchSimulationBlockTest.check("the average tier is found", "AVERAGE".equals(found.path("type").asText()));
		final JsonNode missing = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.EXACT_PLAN));
		MatchSimulationBlockTest.check("no average tier in an exact-only plan", missing.isMissingNode());
	}

	// ------------------------------------------------------------------------------------------------ exact fields

	private static void exactFieldsEqual_trueFalseAndNotApplicable()
	{
		final java.util.Set<String> exactFields = new LinkedHashSet<String>();
		exactFields.add("country");
		exactFields.add("cityKey");

		final MatchPlanView.SimulationPair bothEqual = MatchSimulationBlockTest.pair(
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), null,
			"SUSPECTED");
		MatchSimulationBlockTest.check("both exact fields equal -> TRUE", Boolean.TRUE.equals(MatchSimulationBlock.exactFieldsEqual(exactFields, bothEqual)));

		final MatchPlanView.SimulationPair oneDiffers = MatchSimulationBlockTest.pair(MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"),
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "LYON"), null, "SUSPECTED");
		MatchSimulationBlockTest.check("one exact field differs -> FALSE", Boolean.FALSE.equals(MatchSimulationBlock.exactFieldsEqual(exactFields, oneDiffers)));

		final MatchPlanView.SimulationPair neitherPresent = MatchSimulationBlockTest.pair(new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), null,
			"SUSPECTED");
		MatchSimulationBlockTest.check("neither side has a value -> not applicable (null)",
			MatchSimulationBlock.exactFieldsEqual(exactFields, neitherPresent) == null);

		final java.util.Set<String> noExactFields = new LinkedHashSet<String>();
		MatchSimulationBlockTest.check("plan with no exact field at all -> not applicable (null)",
			MatchSimulationBlock.exactFieldsEqual(noExactFields, bothEqual) == null);
	}

	// -------------------------------------------------------------------------------------------- weighted average

	private static void weightedAverageScore_computedAndMissing()
	{
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final Map<String, Double> scores = new LinkedHashMap<String, Double>();
		scores.put("name", Double.valueOf(90));
		scores.put("country", Double.valueOf(100));
		scores.put("cityKey", Double.valueOf(100));
		final MatchPlanView.SimulationPair complete = MatchSimulationBlockTest.pairWithScores(scores);
		// (90*10 + 100*5 + 100*5) / 20 = 1900/20 = 95
		final Double score = MatchSimulationBlock.weightedAverageScore(averageTier, complete);
		MatchSimulationBlockTest.check("weighted average computed correctly: " + score, score != null && Math.abs(score.doubleValue() - 95.0) < 0.0001);

		final Map<String, Double> incomplete = new LinkedHashMap<String, Double>();
		incomplete.put("name", Double.valueOf(90));
		incomplete.put("country", Double.valueOf(100));
		// cityKey missing
		final MatchPlanView.SimulationPair partial = MatchSimulationBlockTest.pairWithScores(incomplete);
		MatchSimulationBlockTest.check("a missing field score -> null, never a partial average",
			MatchSimulationBlock.weightedAverageScore(averageTier, partial) == null);

		MatchSimulationBlockTest.check("no average tier -> null", MatchSimulationBlock.weightedAverageScore(
			com.fasterxml.jackson.databind.node.MissingNode.getInstance(), complete) == null);
	}

	// -------------------------------------------------------------------------------------------------- borderline

	private static void isBorderline_exactlyAtTheMarginAndJustOutside()
	{
		final JsonNode averageTier = MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN).path("tiers").get(0);
		// minimumScore = 80, margin = 3.0 units (task brief: +/-0.03 on the mockup's 0-1 display scale)
		MatchSimulationBlockTest.check("exactly 3 above the threshold is borderline",
			MatchSimulationBlock.isBorderline(averageTier, MatchSimulationBlockTest.pairWithUniformScore(83.0)));
		MatchSimulationBlockTest.check("exactly 3 below the threshold is borderline",
			MatchSimulationBlock.isBorderline(averageTier, MatchSimulationBlockTest.pairWithUniformScore(77.0)));
		MatchSimulationBlockTest.check("just outside the margin above is not borderline",
			!MatchSimulationBlock.isBorderline(averageTier, MatchSimulationBlockTest.pairWithUniformScore(83.01)));
		MatchSimulationBlockTest.check("just outside the margin below is not borderline",
			!MatchSimulationBlock.isBorderline(averageTier, MatchSimulationBlockTest.pairWithUniformScore(76.99)));
		MatchSimulationBlockTest.check("far from the threshold is never borderline",
			!MatchSimulationBlock.isBorderline(averageTier, MatchSimulationBlockTest.pairWithUniformScore(20.0)));
		MatchSimulationBlockTest.check("no score at all is never fabricated as borderline", !MatchSimulationBlock
			.isBorderline(averageTier, MatchSimulationBlockTest.pair(new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), null, "SUSPECTED")));
	}

	// ------------------------------------------------------------------------------------------------- verdict map

	private static void normalizedVerdict_mapsEveryKnownSpelling()
	{
		MatchSimulationBlockTest.check("MATCH", "MATCH".equals(MatchSimulationBlock.normalizedVerdict("MATCH")));
		MatchSimulationBlockTest.check("MATCHED", "MATCH".equals(MatchSimulationBlock.normalizedVerdict("MATCHED")));
		MatchSimulationBlockTest.check("SUSPECT", "SUSPECT".equals(MatchSimulationBlock.normalizedVerdict("SUSPECT")));
		MatchSimulationBlockTest.check("SUSPECTED", "SUSPECT".equals(MatchSimulationBlock.normalizedVerdict("SUSPECTED")));
		MatchSimulationBlockTest.check("NOT_MATCH", "NOT_MATCH".equals(MatchSimulationBlock.normalizedVerdict("NOT_MATCH")));
		MatchSimulationBlockTest.check("NOT_MATCHED", "NOT_MATCH".equals(MatchSimulationBlock.normalizedVerdict("NOT_MATCHED")));
		MatchSimulationBlockTest.check("unknown/null -> empty string", "".equals(MatchSimulationBlock.normalizedVerdict(null))
			&& "".equals(MatchSimulationBlock.normalizedVerdict("whatever")));
	}

	// ----------------------------------------------------------------------------------------------- field cell

	private static void exactCellWords_equalDifferentMissing()
	{
		MatchSimulationBlockTest.check("equal, case-insensitive", "= Paris".equals(MatchSimulationBlock.exactCellWords("Paris", "PARIS"))
			|| "= PARIS".equals(MatchSimulationBlock.exactCellWords("Paris", "PARIS")));
		MatchSimulationBlockTest.check("different values shown both sides", MatchSimulationBlock.exactCellWords("Paris", "Zurich").contains("Paris")
			&& MatchSimulationBlock.exactCellWords("Paris", "Zurich").contains("Zurich"));
		MatchSimulationBlockTest.check("pivot side missing -> dash", "-".equals(MatchSimulationBlock.exactCellWords(null, "Paris")));
		MatchSimulationBlockTest.check("target side missing -> dash", "-".equals(MatchSimulationBlock.exactCellWords("Paris", null)));
	}

	// ---------------------------------------------------------------------------------------------------- analyze

	private static void analyze_countsAndFamiliesFromAMixOfPairs()
	{
		final java.util.Set<String> exactFields = MatchSimulationBlock.exactFieldNames(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));

		final MatchPlanView.SimulationPair caughtByFuzzy = MatchSimulationBlockTest.namedPair("B", "T1",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "LYON"), null,
			"SUSPECTED");
		final MatchPlanView.SimulationPair trivialSuspect = MatchSimulationBlockTest.namedPair("A", "T2",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), null,
			"SUSPECTED");
		final MatchPlanView.SimulationPair sameKeyRefused = MatchSimulationBlockTest.namedPair("C", "T3",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), null,
			"NOT_MATCHED");
		final MatchPlanView.SimulationPair plainNotMatch = MatchSimulationBlockTest.namedPair("D", "T4",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "GB", "cityKey", "LONDON"), null,
			"NOT_MATCHED");
		final MatchPlanView.SimulationPair automaticMatch = MatchSimulationBlockTest.namedPair("E", "T5",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), null, "MATCHED");
		final Map<String, Double> borderlineScores = new LinkedHashMap<String, Double>();
		borderlineScores.put("name", Double.valueOf(82));
		borderlineScores.put("country", Double.valueOf(82));
		borderlineScores.put("cityKey", Double.valueOf(82));
		final MatchPlanView.SimulationPair borderline = new MatchPlanView.SimulationPair("F", "ERP", "T6", "CRM", MatchSimulationBlockTest.values("x", "y"),
			MatchSimulationBlockTest.values("x", "y"), borderlineScores, "SUSPECTED");

		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		pairs.add(caughtByFuzzy);
		pairs.add(trivialSuspect);
		pairs.add(sameKeyRefused);
		pairs.add(plainNotMatch);
		pairs.add(automaticMatch);
		pairs.add(borderline);

		final MatchSimulationBlock.Analysis analysis = MatchSimulationBlock.analyze(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), exactFields,
			averageTier, pairs, 3);

		MatchSimulationBlockTest.check("6 examined", analysis.examined == 6);
		MatchSimulationBlockTest.check("1 match", analysis.matchCount == 1);
		MatchSimulationBlockTest.check("3 suspect (caughtByFuzzy + trivialSuspect + borderline)", analysis.suspectCount == 3);
		MatchSimulationBlockTest.check("1 caught by fuzzy", analysis.suspectCaughtByFuzzy == 1);
		MatchSimulationBlockTest.check("2 not match", analysis.notMatchCount == 2);
		MatchSimulationBlockTest.check("1 same-key refused", analysis.notMatchSameKey == 1);
		MatchSimulationBlockTest.check("1 borderline", analysis.borderlineCount == 1);
		MatchSimulationBlockTest.check("family1 has exactly the caught-by-fuzzy pair", analysis.family1.size() == 1 && "B".equals(analysis.family1.get(0).pivotLabel));
		MatchSimulationBlockTest.check("family2 has exactly the same-key-refused pair", analysis.family2.size() == 1 && "C".equals(analysis.family2.get(0).pivotLabel));
		MatchSimulationBlockTest.check("family3 has exactly the borderline pair", analysis.family3.size() == 1 && "F".equals(analysis.family3.get(0).pivotLabel));
	}

	private static void analyze_capsEachFamilyAtThreeRowsButCountsEveryOne()
	{
		final java.util.Set<String> exactFields = MatchSimulationBlock.exactFieldNames(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		for (int i = 0; i < 5; i++)
		{
			pairs.add(MatchSimulationBlockTest.namedPair("P" + i, "T" + i, MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"),
				MatchSimulationBlockTest.values("country", "FR", "cityKey", "LYON"), null, "SUSPECTED"));
		}
		final MatchSimulationBlock.Analysis analysis = MatchSimulationBlock.analyze(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), exactFields,
			averageTier, pairs, 5);
		MatchSimulationBlockTest.check("all 5 counted", analysis.suspectCaughtByFuzzy == 5);
		MatchSimulationBlockTest.check("family1 capped at 3 rows", analysis.family1.size() == MatchSimulationBlock.MAX_FAMILY_ROWS);
	}

	private static void analyze_sortsDeterministically()
	{
		final java.util.Set<String> exactFields = MatchSimulationBlock.exactFieldNames(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		pairs.add(MatchSimulationBlockTest.namedPair("Zebra", "T1", MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"),
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "LYON"), null, "SUSPECTED"));
		pairs.add(MatchSimulationBlockTest.namedPair("Alpha", "T2", MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"),
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "LYON"), null, "SUSPECTED"));
		final MatchSimulationBlock.Analysis analysis = MatchSimulationBlock.analyze(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), exactFields,
			averageTier, pairs, 2);
		MatchSimulationBlockTest.check("Alpha sorts before Zebra regardless of input order", "Alpha".equals(analysis.family1.get(0).pivotLabel));
	}

	// -------------------------------------------------------------------------------------------------- leafWords

	/**
	 * Binary-decision-tree-screen lot (2026-09-22): {@code leafWords} now names the full path ("{@code T1
	 * passes -> Match}"), not "tier N accepted/refused (fields)" - see {@code MatchSimulationBlock#leafWords}'
	 * own class Javadoc. {@code EXACT_PLAN} is a single-tier cascade, so its one node keeps the id {@code T1}
	 * ({@code ReviewDecisionTree#fromTiers}' own naming) and reaching either leaf is a one-step path.
	 */
	private static void leafWords_exactTierAcceptedAndRefused()
	{
		final JsonNode exactPlan = MatchSimulationBlockTest.parse(MatchSimulationBlockTest.EXACT_PLAN);
		final MatchPlanView.SimulationPair accepted = MatchSimulationBlockTest.pair(MatchSimulationBlockTest.values("vatNo", "FR123"),
			MatchSimulationBlockTest.values("vatNo", "FR123"), null, "MATCH");
		final String acceptedWords = MatchSimulationBlock.leafWords(exactPlan, accepted, Locale.ENGLISH);
		MatchSimulationBlockTest.check("T1 passes, named path to Match: " + acceptedWords,
			acceptedWords.contains("T1") && acceptedWords.contains("passes") && acceptedWords.contains("Match"));

		final MatchPlanView.SimulationPair refused = MatchSimulationBlockTest.pair(MatchSimulationBlockTest.values("vatNo", "FR123"),
			MatchSimulationBlockTest.values("vatNo", "FR999"), null, "NOT_MATCH");
		final String refusedWords = MatchSimulationBlock.leafWords(exactPlan, refused, Locale.ENGLISH);
		MatchSimulationBlockTest.check("T1 does not pass, named path to Not a duplicate: " + refusedWords,
			refusedWords.contains("T1") && refusedWords.contains("does not pass") && refusedWords.contains("Not a duplicate"));
	}

	/** Same lot as above - an AVERAGE node's own step names its real computed score, not "accepted"/"refused". */
	private static void leafWords_averageTierAcceptedAndRefused()
	{
		final JsonNode averagePlan = MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN);
		final Map<String, Double> highScores = new LinkedHashMap<String, Double>();
		highScores.put("name", Double.valueOf(95));
		highScores.put("country", Double.valueOf(100));
		highScores.put("cityKey", Double.valueOf(100));
		final MatchPlanView.SimulationPair accepted = new MatchPlanView.SimulationPair("p", "ERP", "t", "CRM", MatchSimulationBlockTest.values("x", "y"),
			MatchSimulationBlockTest.values("x", "y"), highScores, "SUSPECTED");
		// (95*10 + 100*5 + 100*5) / 20 = 97.5
		final String acceptedWords = MatchSimulationBlock.leafWords(averagePlan, accepted, Locale.ENGLISH);
		MatchSimulationBlockTest.check("T1 names its own computed score, path reaches Suspect: " + acceptedWords,
			acceptedWords.contains("T1") && acceptedWords.contains("97.5") && acceptedWords.contains("Suspect"));

		final Map<String, Double> lowScores = new LinkedHashMap<String, Double>();
		lowScores.put("name", Double.valueOf(10));
		lowScores.put("country", Double.valueOf(0));
		lowScores.put("cityKey", Double.valueOf(0));
		final MatchPlanView.SimulationPair refused = new MatchPlanView.SimulationPair("p", "ERP", "t", "CRM", MatchSimulationBlockTest.values("x", "y"),
			MatchSimulationBlockTest.values("x", "y"), lowScores, "NOT_MATCHED");
		// (10*10 + 0*5 + 0*5) / 20 = 5
		final String refusedWords = MatchSimulationBlock.leafWords(averagePlan, refused, Locale.ENGLISH);
		MatchSimulationBlockTest.check("T1's low score reaches Not a duplicate: " + refusedWords,
			refusedWords.contains("T1") && refusedWords.contains("5") && refusedWords.contains("Not a duplicate"));
	}

	/** No exact-field values, no scores, a SUSPECTED verdict this class cannot corroborate either way - never guesses. */
	private static void leafWords_unknownWhenNothingCanBeTold()
	{
		final JsonNode averagePlan = MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN);
		final MatchPlanView.SimulationPair blind = MatchSimulationBlockTest.pair(new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), null,
			"SUSPECTED");
		final String words = MatchSimulationBlock.leafWords(averagePlan, blind, Locale.ENGLISH);
		MatchSimulationBlockTest.check("falls back to the honest unknown message: " + words, words.contains("not enough data"));
	}

	// ------------------------------------------------------------------------------- four mockup path examples

	/**
	 * The mockup's own examples 1 ("BPCE - FR &lt;-&gt; BPCE - FR", Match) and 2 ("Exemplar Industries FR &lt;-&gt; Air
	 * Liquide BE", Suspect) - reached with NO real {@code enginePath} (the pre-lot heuristic walk, generalized
	 * from a cascade to this real tree). Examples 3/4 (both routed through N4's null-handling, which the
	 * heuristic cannot model - see {@code master.DecisionTreePlan}'s own "vide d'un cote" note) are covered via
	 * a real {@code enginePath} instead, in {@link #leafWords_enginePath_twoOfTheFourMockupPathExamples}.
	 */
	private static void leafWords_heuristicWalk_twoOfTheFourMockupPathExamples() throws Exception
	{
		final JsonNode plan = MatchSimulationBlockTest.parse(MatchSimulationBlockTest.SIX_NODE_MOCKUP_PLAN);

		// example 1: N1 passes -> N2 passes -> Match
		final Map<String, String> bpce1 = MatchSimulationBlockTest.values("vatNo", "FR12345", "country", "FR");
		final Map<String, String> bpce2 = MatchSimulationBlockTest.values("vatNo", "FR12345", "country", "FR");
		final MatchPlanView.SimulationPair example1 = MatchSimulationBlockTest.namedPair("BPCE . FR", "BPCE . FR", bpce1, bpce2, null, "MATCHED");
		final String words1 = MatchSimulationBlock.leafWords(plan, example1, Locale.ENGLISH);
		MatchSimulationBlockTest.check("example 1: " + words1,
			words1.contains("N1") && words1.contains("N2") && words1.contains("passes") && words1.contains("Match") && !words1.contains("does not pass"));

		// example 2: N1 passes -> N2 does not pass -> N3 passes -> Suspect (same VAT, different country, name decides)
		final Map<String, String> alFr = MatchSimulationBlockTest.values("vatNo", "BE999", "country", "FR", "name", "Exemplar Industries");
		final Map<String, String> alBe = MatchSimulationBlockTest.values("vatNo", "BE999", "country", "BE", "name", "Exemplar Industries");
		final MatchPlanView.SimulationPair example2 = MatchSimulationBlockTest.namedPair("Exemplar Industries . FR", "Exemplar Industries . BE", alFr, alBe, null, "SUSPECTED");
		final String words2 = MatchSimulationBlock.leafWords(plan, example2, Locale.ENGLISH);
		MatchSimulationBlockTest.check("example 2: " + words2, words2.contains("N1") && words2.contains("N2") && words2.contains("N3")
			&& words2.contains("does not pass") && words2.contains("Suspect"));
	}

	/**
	 * The mockup's examples 3 ("HSBC Bank &lt;-&gt; HSBC BANK, VAT empty", Suspect via N4 then N5) and 4
	 * ("Bankinter &lt;-&gt; Bankia", Not a duplicate, refused at N4) - routed via a REAL {@code enginePath}
	 * ({@code TreeEvaluation.nodeNames}), exactly as {@code ebx.MatchPlanSimulationRunner} would populate it
	 * from a live {@code evaluatePair} call - {@link MatchSimulationBlock#leafWords}' own class Javadoc, item
	 * 1. Node names below are {@link ReviewDecisionTree#engineName}'s own auto-generated English labels.
	 */
	private static void leafWords_enginePath_twoOfTheFourMockupPathExamples() throws Exception
	{
		final JsonNode plan = MatchSimulationBlockTest.parse(MatchSimulationBlockTest.SIX_NODE_MOCKUP_PLAN);

		// example 3: N1 does not pass -> N4 (accepted) -> N5 passes -> Suspect
		final Map<String, Double> highScores = new LinkedHashMap<String, Double>();
		highScores.put("name", Double.valueOf(90));
		highScores.put("country", Double.valueOf(95));
		highScores.put("cityKey", Double.valueOf(95));
		final List<String> path3 = new ArrayList<String>();
		path3.add("Same vatNo");
		path3.add("Similar name and country and cityKey");
		path3.add("Same legalForm");
		final MatchPlanView.SimulationPair example3 = new MatchPlanView.SimulationPair("HSBC Bank", "ERP", "HSBC BANK", "CRM", new LinkedHashMap<String, String>(),
			new LinkedHashMap<String, String>(), highScores, "SUSPECTED", MatchPlanView.SimulationPair.ORIGIN_ENGINE, null, path3);
		final String words3 = MatchSimulationBlock.leafWords(plan, example3, Locale.ENGLISH);
		MatchSimulationBlockTest.check("example 3 names N1, N4's score, and N5: " + words3,
			words3.contains("N1") && words3.contains("does not pass") && words3.contains("N4") && words3.contains("N5") && words3.contains("passes")
				&& words3.contains("Suspect"));

		// example 4: N1 does not pass -> N4 refused -> Not a duplicate (the path stops at N4 - never reaches N5)
		final Map<String, Double> lowScores = new LinkedHashMap<String, Double>();
		lowScores.put("name", Double.valueOf(60));
		lowScores.put("country", Double.valueOf(70));
		lowScores.put("cityKey", Double.valueOf(80));
		final List<String> path4 = new ArrayList<String>();
		path4.add("Same vatNo");
		path4.add("Similar name and country and cityKey");
		final MatchPlanView.SimulationPair example4 = new MatchPlanView.SimulationPair("Bankinter", "ERP", "Bankia", "CRM", new LinkedHashMap<String, String>(),
			new LinkedHashMap<String, String>(), lowScores, "NOT_MATCHED", MatchPlanView.SimulationPair.ORIGIN_ENGINE, null, path4);
		final String words4 = MatchSimulationBlock.leafWords(plan, example4, Locale.ENGLISH);
		MatchSimulationBlockTest.check("example 4 stops at N4, never mentions N5: " + words4,
			words4.contains("N1") && words4.contains("does not pass") && words4.contains("N4") && !words4.contains("N5") && words4.contains("Not a duplicate"));
	}

	/**
	 * A relabelled node the engine path cannot match by name alone: {@link MatchSimulationBlock} falls back
	 * positionally (whichever branch target is itself a node) and marks the whole path approximate - task
	 * brief item 3, "fall back to the node order when labels are ambiguous and say so".
	 */
	private static void leafWords_enginePath_ambiguousLabelFallsBackPositionallyAndSaysSo() throws Exception
	{
		final String requirements = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
			+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"label\":\"Custom label\","
			+ "\"fields\":[{\"field\":\"vatNo\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],\"onTrue\":\"N2\",\"onFalse\":\"NOT_MATCHED\"},"
			+ "{\"id\":\"N2\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"country\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100}],"
			+ "\"onTrue\":\"MATCH\",\"onFalse\":\"NOT_MATCHED\"}]}}";
		final JsonNode plan = MatchSimulationBlockTest.parse(requirements);
		// the real add-on sent "Custom label" for N1 (matches), but a name for N2 this fixture never predicted
		// (simulating a relabel this class does not know about) - the match must still find N2 (its onTrue is
		// the only remaining NODE target) and flag the result as approximate.
		final List<String> enginePath = new ArrayList<String>();
		enginePath.add("Custom label");
		enginePath.add("Some other label entirely");
		final MatchPlanView.SimulationPair pair = new MatchPlanView.SimulationPair("p", "ERP", "t", "CRM", new LinkedHashMap<String, String>(),
			new LinkedHashMap<String, String>(), new LinkedHashMap<String, Double>(), "MATCHED", MatchPlanView.SimulationPair.ORIGIN_ENGINE, null, enginePath);
		final String words = MatchSimulationBlock.leafWords(plan, pair, Locale.ENGLISH);
		MatchSimulationBlockTest.check("still finds N2 despite the unmatched label, and says the match is approximate: " + words,
			words.contains("N1") && words.contains("N2") && words.contains("Match") && words.contains("approximate"));
	}

	// ----------------------------------------------------------------------------------------------------- render

	private static void render_emptyPairsShowsNoneMessage()
	{
		final StringBuilder out = new StringBuilder();
		MatchSimulationBlock.render(out, MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN),
			new MatchPlanView.SimulationResult(new ArrayList<MatchPlanView.SimulationPair>(), 0), Locale.ENGLISH);
		MatchSimulationBlockTest.check("no-data message shown", out.toString().contains("No record found to sample"));
	}

	/**
	 * review-match-plan-simulation-probes lot (2026-09-22): the pre-lot literal "No pair of this kind in this
	 * sample." is gone - every empty family now says why, with real numbers (task brief item 2). A lone
	 * MATCHED, same-key, engine-origin pair belongs to no family (families 1-3 AND family 0, which only ever
	 * collects SUSPECT pairs) - so all three of families 1-3 must render their OWN, distinct, numeric empty
	 * line, and family 0 must not render at all (nothing to show there either).
	 */
	private static void render_emptyFamilyNeverInventsARow()
	{
		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		pairs.add(MatchSimulationBlockTest.namedPair("A", "T1", MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"),
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), null, "MATCHED"));
		final StringBuilder out = new StringBuilder();
		MatchSimulationBlock.render(out, MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN),
			new MatchPlanView.SimulationResult(pairs, 1), Locale.ENGLISH);
		final String html = out.toString();
		MatchSimulationBlockTest.check("summary cards rendered", html.contains("mdmf-sim-cards"));
		MatchSimulationBlockTest.check("the old bare empty message is gone", !html.contains("No pair of this kind in this sample."));
		MatchSimulationBlockTest.check("no unresolved placeholder anywhere: " + html, !html.contains("{0}") && !html.contains("{1}"));
		MatchSimulationBlockTest.check("family 1 says why, with numbers", html.contains("0 near-miss pair(s) probed, 1 pair(s) examined overall"));
		MatchSimulationBlockTest.check("family 2 says why, with numbers", html.contains("No refusal among the 0 shared-key pair(s) probed (1 pair(s) examined overall)"));
		MatchSimulationBlockTest.check("family 3 says why, with numbers", html.contains("across 1 pair(s) examined"));
		MatchSimulationBlockTest.check("family 0 does not render when it has nothing to show (this pair is MATCHED, not SUSPECT)",
			!html.contains("review.match.simulate.family0.title") && !html.contains("Typical pairs the engine flagged"));
	}

	// -------------------------------------------------------------------------------- sameKey / origin (probes lot)

	/**
	 * {@link MatchPlanView.SimulationPair#sameKey}, when set by {@code ebx.MatchPlanSimulationRunner}, takes
	 * precedence over the older {@link MatchSimulationBlock#exactFieldsEqual} fallback - the whole point of
	 * the probes lot: family 2 must key on the plan's own IDENTIFYING key field (e.g. {@code nameKey}), not
	 * merely on the exact-TIER fields (country/cityKey), which can agree even when the identifying key does
	 * not (and vice versa).
	 */
	private static void analyze_sameKeyFlagOverridesExactFieldsFallback()
	{
		final java.util.Set<String> exactFields = MatchSimulationBlock.exactFieldNames(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));

		// exact-tier fields (country/cityKey) agree, but sameKey explicitly says the IDENTIFYING key differs -
		// this is exactly a probe-B "near miss" pair - must land in family 1, not nowhere.
		final MatchPlanView.SimulationPair nearMiss = new MatchPlanView.SimulationPair("NM-pivot", "ERP", "NM-target", "CRM",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"),
			new LinkedHashMap<String, Double>(), "SUSPECTED", MatchPlanView.SimulationPair.ORIGIN_PROBE_NEAR_MISS, Boolean.FALSE);
		// exact-tier fields disagree, but sameKey explicitly says the IDENTIFYING key is the SAME (a probe-A
		// "shared key" pair that got refused) - must land in family 2, not family 1.
		final MatchPlanView.SimulationPair sharedKeyRefused = new MatchPlanView.SimulationPair("SK-pivot", "ERP", "SK-target", "CRM",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "GB", "cityKey", "LONDON"),
			new LinkedHashMap<String, Double>(), "NOT_MATCHED", MatchPlanView.SimulationPair.ORIGIN_PROBE_SHARED_KEY, Boolean.TRUE);

		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		pairs.add(nearMiss);
		pairs.add(sharedKeyRefused);
		final MatchSimulationBlock.Analysis analysis = MatchSimulationBlock.analyze(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), exactFields,
			averageTier, pairs, 2);

		MatchSimulationBlockTest.check("near-miss pair (different real key) lands in family 1 despite equal exact-tier fields",
			analysis.family1.size() == 1 && "NM-pivot".equals(analysis.family1.get(0).pivotLabel));
		MatchSimulationBlockTest.check("shared-key-but-refused pair lands in family 2 despite differing exact-tier fields",
			analysis.family2.size() == 1 && "SK-pivot".equals(analysis.family2.get(0).pivotLabel));
	}

	/** Task brief: family 1 = "pairs with different key whose verdict is SUSPECTED/MATCHED" - a MATCHED verdict counts too, not only SUSPECTED. */
	private static void analyze_matchVerdictWithDifferentKeyAlsoCountsAsFamily1()
	{
		final java.util.Set<String> exactFields = MatchSimulationBlock.exactFieldNames(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final MatchPlanView.SimulationPair matchedDifferentKey = new MatchPlanView.SimulationPair("p", "ERP", "t", "CRM",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"),
			new LinkedHashMap<String, Double>(), "MATCHED", MatchPlanView.SimulationPair.ORIGIN_ENGINE, Boolean.FALSE);
		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		pairs.add(matchedDifferentKey);
		final MatchSimulationBlock.Analysis analysis = MatchSimulationBlock.analyze(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), exactFields,
			averageTier, pairs, 1);
		MatchSimulationBlockTest.check("1 match still counted", analysis.matchCount == 1);
		MatchSimulationBlockTest.check("a MATCHED pair with a different key is caught in family 1 too", analysis.family1.size() == 1);
	}

	/** {@link MatchSimulationBlock.Analysis#probedCount}/{@code probeSharedKeyExamined}/{@code probeNearMissExamined} count pairs by {@link MatchPlanView.SimulationPair#origin}, never by verdict. */
	private static void analyze_probedCountsByOrigin()
	{
		final java.util.Set<String> exactFields = MatchSimulationBlock.exactFieldNames(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		pairs.add(MatchSimulationBlockTest.namedPair("e", "t0", MatchSimulationBlockTest.values("x", "y"), MatchSimulationBlockTest.values("x", "y"), null,
			"SUSPECTED"));
		pairs.add(new MatchPlanView.SimulationPair("sk1", "ERP", "t1", "CRM", MatchSimulationBlockTest.values("x", "y"), MatchSimulationBlockTest.values("x", "y"),
			new LinkedHashMap<String, Double>(), "NOT_MATCHED", MatchPlanView.SimulationPair.ORIGIN_PROBE_SHARED_KEY, Boolean.TRUE));
		pairs.add(new MatchPlanView.SimulationPair("sk2", "ERP", "t2", "CRM", MatchSimulationBlockTest.values("x", "y"), MatchSimulationBlockTest.values("x", "y"),
			new LinkedHashMap<String, Double>(), "NOT_MATCHED", MatchPlanView.SimulationPair.ORIGIN_PROBE_SHARED_KEY, Boolean.TRUE));
		pairs.add(new MatchPlanView.SimulationPair("nm1", "ERP", "t3", "CRM", MatchSimulationBlockTest.values("x", "y"), MatchSimulationBlockTest.values("x", "y"),
			new LinkedHashMap<String, Double>(), "SUSPECTED", MatchPlanView.SimulationPair.ORIGIN_PROBE_NEAR_MISS, Boolean.FALSE));
		final MatchSimulationBlock.Analysis analysis = MatchSimulationBlock.analyze(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), exactFields,
			averageTier, pairs, 4);
		MatchSimulationBlockTest.check("3 probed (1 engine excluded)", analysis.probedCount == 3);
		MatchSimulationBlockTest.check("2 shared-key probes", analysis.probeSharedKeyExamined == 2);
		MatchSimulationBlockTest.check("1 near-miss probe", analysis.probeNearMissExamined == 1);
	}

	/** Task brief, "never an empty panel": family 0 collects up to 3 scored, engine-origin SUSPECT pairs, but ONLY when families 1-3 are all empty - otherwise it stays empty so {@code render} hides it. */
	private static void analyze_family0PopulatedOnlyWhenFamilies1To3AreAllEmpty()
	{
		final java.util.Set<String> exactFields = MatchSimulationBlock.exactFieldNames(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final JsonNode averageTier = MatchSimulationBlock.firstAverageTier(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN));
		final Map<String, Double> scores = new LinkedHashMap<String, Double>();
		scores.put("name", Double.valueOf(85));
		scores.put("country", Double.valueOf(100));
		scores.put("cityKey", Double.valueOf(100));
		// a trivial, same-key, scored SUSPECT engine candidate - belongs to no family 1/2/3, would otherwise
		// leave the whole panel showing nothing but empty lines
		final MatchPlanView.SimulationPair trivialScored = new MatchPlanView.SimulationPair("p", "ERP", "t", "CRM",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), scores,
			"SUSPECTED", MatchPlanView.SimulationPair.ORIGIN_ENGINE, Boolean.TRUE);
		final List<MatchPlanView.SimulationPair> onlyTrivial = new ArrayList<MatchPlanView.SimulationPair>();
		onlyTrivial.add(trivialScored);
		final MatchSimulationBlock.Analysis emptyFamilies = MatchSimulationBlock.analyze(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN),
			exactFields, averageTier, onlyTrivial, 1);
		MatchSimulationBlockTest.check("families 1-3 are indeed all empty here", emptyFamilies.family1.isEmpty() && emptyFamilies.family2.isEmpty()
			&& emptyFamilies.family3.isEmpty());
		MatchSimulationBlockTest.check("family 0 falls back to the trivial scored pair", emptyFamilies.family0.size() == 1);

		// add a real family-1 pair too - family 0 must now stay EMPTY (task brief: hidden when 1-3 are not empty)
		final MatchPlanView.SimulationPair caughtByFuzzy = MatchSimulationBlockTest.namedPair("B", "T1",
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), MatchSimulationBlockTest.values("country", "FR", "cityKey", "LYON"), null,
			"SUSPECTED");
		final List<MatchPlanView.SimulationPair> withFamily1 = new ArrayList<MatchPlanView.SimulationPair>();
		withFamily1.add(trivialScored);
		withFamily1.add(caughtByFuzzy);
		final MatchSimulationBlock.Analysis nonEmptyFamily1 = MatchSimulationBlock.analyze(MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN),
			exactFields, averageTier, withFamily1, 2);
		MatchSimulationBlockTest.check("family 0 hidden once family 1 has a real row", nonEmptyFamily1.family0.isEmpty());

		// render() must actually show family 0's block, with the scored pair, when it is the only thing to show
		final StringBuilder out = new StringBuilder();
		MatchSimulationBlock.render(out, MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), new MatchPlanView.SimulationResult(onlyTrivial, 1),
			Locale.ENGLISH);
		final String html = out.toString();
		MatchSimulationBlockTest.check("family 0 title rendered", html.contains("Typical pairs the engine flagged"));
		MatchSimulationBlockTest.check("family 0 shows the scored pair", html.contains("p") && html.contains("t"));
	}

	/** Lot M16 (docs/MATCHING.md sec 68): the candidate coverage the simulation measured is on the panel - the figures per key, the systems, and the way forward - even when no pair was found. */
	private static void coverage_isShownBeforeThePairsInBothLanguages()
	{
		final List<com.on.ebx.factory.matching.CandidateCoverage.Observation> observations = new ArrayList<com.on.ebx.factory.matching.CandidateCoverage.Observation>();
		final List<String[]> sharing = new ArrayList<String[]>();
		for (int i = 0; i < 4; i++)
		{
			sharing.add(new String[] { "S" + i, "STORES" });
		}
		observations.add(new com.on.ebx.factory.matching.CandidateCoverage.Observation("mobileE164", "E1", "ECOM", sharing));
		observations.add(new com.on.ebx.factory.matching.CandidateCoverage.Observation("emailKey", "E1", "ECOM", java.util.Collections.singletonList(new String[] { "L1", "LOYALTY" })));
		final java.util.Map<String, java.util.Set<String>> candidates = new LinkedHashMap<String, java.util.Set<String>>();
		candidates.put("E1", new LinkedHashSet<String>(java.util.Arrays.asList("L1", "S0")));
		final com.on.ebx.factory.matching.CandidateCoverage.Result coverage = com.on.ebx.factory.matching.CandidateCoverage.measure(observations, candidates, 18);
		final MatchPlanView.SimulationResult result = new MatchPlanView.SimulationResult(new ArrayList<MatchPlanView.SimulationPair>(), 18).withCoverage(coverage, coverage.facts("x"));
		final StringBuilder english = new StringBuilder();
		MatchSimulationBlock.render(english, MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), result, Locale.ENGLISH);
		final String en = english.toString();
		MatchSimulationBlockTest.check("title: " + en, en.contains("Candidates the add-on really evaluated"));
		MatchSimulationBlockTest.check("the gap in numbers, by system", en.contains("mobileE164: 3 of 4 record(s) sharing it with a pivot were never candidates (STORES 3)."));
		MatchSimulationBlockTest.check("the key without gap says so", en.contains("emailKey: all 1 record(s) sharing it with a pivot were candidates."));
		MatchSimulationBlockTest.check("the way forward", en.contains("ask for another proposal") && en.contains("clusteringWeights"));
		MatchSimulationBlockTest.check("still no pair to show", en.contains("mdmf-hint") && en.indexOf("Candidates the add-on really evaluated") < en.lastIndexOf("mdmf-hint"));
		final StringBuilder french = new StringBuilder();
		MatchSimulationBlock.render(french, MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), result, Locale.FRENCH);
		MatchSimulationBlockTest.check("in French: " + french, french.toString().contains("Candidats r\u00E9ellement \u00E9valu\u00E9s") && french.toString().contains("n&#39;ont jamais \u00E9t\u00E9 candidats (STORES 3)"));
		final StringBuilder none = new StringBuilder();
		MatchSimulationBlock.render(none, MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN), new MatchPlanView.SimulationResult(new ArrayList<MatchPlanView.SimulationPair>(), 0), Locale.ENGLISH);
		MatchSimulationBlockTest.check("nothing measured, nothing shown", !none.toString().contains("really evaluated"));
	}

	private static void originWords_mapsEveryKnownOrigin()
	{
		MatchSimulationBlockTest.check("engine", "Engine candidate".equals(MatchSimulationBlock.originWords(MatchPlanView.SimulationPair.ORIGIN_ENGINE,
			Locale.ENGLISH)));
		MatchSimulationBlockTest.check("shared key", "Factory probe: shared key".equals(MatchSimulationBlock.originWords(
			MatchPlanView.SimulationPair.ORIGIN_PROBE_SHARED_KEY, Locale.ENGLISH)));
		MatchSimulationBlockTest.check("near miss", "Factory probe: near miss".equals(MatchSimulationBlock.originWords(
			MatchPlanView.SimulationPair.ORIGIN_PROBE_NEAR_MISS, Locale.ENGLISH)));
		MatchSimulationBlockTest.check("unknown origin falls back to the raw value, never blank", "SOMETHING_ELSE".equals(MatchSimulationBlock.originWords(
			"SOMETHING_ELSE", Locale.ENGLISH)));
	}

	/**
	 * French goes through the SAME {@code java.text.MessageFormat} path as English (unlike EBX's own
	 * message-bundle mechanism, CLAUDE.md &sect;4) - every apostrophe in a key formatted WITH arguments must
	 * be doubled in the {@code .properties} source or it is silently eaten (the exact bug {@code
	 * ReviewMessagesTest} guards for {@code review.cascade.none}). {@code family1.empty}/{@code
	 * family3.empty} both carry an apostrophe ("n''a") and are both called with two arguments - this is the
	 * regression test for that specific trap on the NEW keys this lot adds.
	 */
	private static void render_frenchEmptyFamilyMessagesFormatCleanly()
	{
		// ReviewMessages.get directly - the precise place the java.text.MessageFormat escaping happens;
		// render()'s own output HTML-escapes every apostrophe to "&#39;" (ReviewPage.esc), which is a
		// SEPARATE, already-correct concern this test does not need to re-verify.
		final String family1 = ReviewMessages.get(Locale.FRENCH, "review.match.simulate.family1.empty", Integer.valueOf(0), Integer.valueOf(1));
		final String family3 = ReviewMessages.get(Locale.FRENCH, "review.match.simulate.family3.empty", Double.valueOf(3.0), Integer.valueOf(1));
		MatchSimulationBlockTest.check("family 1: no unresolved placeholder: " + family1, !family1.contains("{"));
		MatchSimulationBlockTest.check("family 1 apostrophe survives intact (n'a, not na): " + family1, family1.contains("n'a été suspectée"));
		MatchSimulationBlockTest.check("family 1: not eaten into \"na\": " + family1, !family1.contains(" na été"));
		MatchSimulationBlockTest.check("family 3: no unresolved placeholder: " + family3, !family3.contains("{"));
		MatchSimulationBlockTest.check("family 3 apostrophe survives intact (n'a, not na): " + family3, family3.contains("Aucune paire n'a un score"));
		MatchSimulationBlockTest.check("family 3: not eaten into \"na\": " + family3, !family3.contains("paire na un"));

		// render() itself: HTML-escaped, but must still be free of unresolved braces and must actually reach
		// the family-specific text (as opposed to the pre-probes-lot bare "no pair of this kind" line).
		final List<MatchPlanView.SimulationPair> pairs = new ArrayList<MatchPlanView.SimulationPair>();
		pairs.add(MatchSimulationBlockTest.namedPair("A", "T1", MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"),
			MatchSimulationBlockTest.values("country", "FR", "cityKey", "PARIS"), null, "MATCHED"));
		final StringBuilder out = new StringBuilder();
		MatchSimulationBlock.render(out, MatchSimulationBlockTest.parse(MatchSimulationBlockTest.AVERAGE_PLAN),
			new MatchPlanView.SimulationResult(pairs, 1), Locale.FRENCH);
		final String html = out.toString();
		MatchSimulationBlockTest.check("no unresolved placeholder in the rendered page: " + html, !html.contains("{0}") && !html.contains("{1}"));
		MatchSimulationBlockTest.check("family 1 text (HTML-escaped apostrophe) reaches the page",
			html.contains("n&#39;a été suspectée"));
		MatchSimulationBlockTest.check("family 3 text (HTML-escaped apostrophe) reaches the page", html.contains("Aucune paire n&#39;a un score"));
	}

	// ----------------------------------------------------------------------------------------------------- helpers

	private static JsonNode parse(final String pJson)
	{
		try
		{
			return MatchSimulationBlockTest.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static Map<String, String> values(final String... pKeyValuePairs)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i < pKeyValuePairs.length; i += 2)
		{
			out.put(pKeyValuePairs[i], pKeyValuePairs[i + 1]);
		}
		return out;
	}

	private static MatchPlanView.SimulationPair pair(final Map<String, String> pPivotValues, final Map<String, String> pTargetValues,
		final Map<String, Double> pScores, final String pVerdict)
	{
		return new MatchPlanView.SimulationPair("pivot", "ERP", "target", "CRM", pPivotValues, pTargetValues, pScores, pVerdict);
	}

	private static MatchPlanView.SimulationPair namedPair(final String pPivotLabel, final String pTargetLabel, final Map<String, String> pPivotValues,
		final Map<String, String> pTargetValues, final Map<String, Double> pScores, final String pVerdict)
	{
		return new MatchPlanView.SimulationPair(pPivotLabel, "ERP", pTargetLabel, "CRM", pPivotValues, pTargetValues, pScores, pVerdict);
	}

	private static MatchPlanView.SimulationPair pairWithScores(final Map<String, Double> pScores)
	{
		return new MatchPlanView.SimulationPair("pivot", "ERP", "target", "CRM", new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), pScores,
			"SUSPECTED");
	}

	private static MatchPlanView.SimulationPair pairWithUniformScore(final double pScore)
	{
		final Map<String, Double> scores = new LinkedHashMap<String, Double>();
		scores.put("name", Double.valueOf(pScore));
		scores.put("country", Double.valueOf(pScore));
		scores.put("cityKey", Double.valueOf(pScore));
		return MatchSimulationBlockTest.pairWithScores(scores);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
