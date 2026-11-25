package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.review.MatchPlanView;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers the pure helpers
 * the diagnostic-visibility lot (2026-09-22) added to {@link MatchPlanSimulationRunner} - field-name
 * normalisation/matching (task brief item 2), the score-diagnostic aggregation (item 1), and the near-miss
 * applicability test (item 3) - no live {@code Session}/{@code Adaptation} needed, same discipline as {@link
 * MatchPlanSimulationRunnerSqlTest}.
 */
public final class MatchPlanSimulationRunnerScoreMatchTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		MatchPlanSimulationRunnerScoreMatchTest.normalizeFieldPath_stripsLeadingDotSlashOrSlash();
		MatchPlanSimulationRunnerScoreMatchTest.lastPathStep_returnsSubstringAfterLastSlash();
		MatchPlanSimulationRunnerScoreMatchTest.matchScoreFieldToPlanField_exactAndLastStepAndNoMatch();
		MatchPlanSimulationRunnerScoreMatchTest.mergeFieldScores_matchesNormalizedFieldNameAndNeverFlagsWhenScored();
		MatchPlanSimulationRunnerScoreMatchTest.mergeFieldScores_flagsWhenAnExpectedFieldIsNeverScored();
		MatchPlanSimulationRunnerScoreMatchTest.mergeFieldScores_fullTextOnlyPlan_neverFlags();
		MatchPlanSimulationRunnerScoreMatchTest.mergeFieldScores_alwaysFlagsOnFailureEvenForAFullTextField();
		MatchPlanSimulationRunnerScoreMatchTest.mergeFieldScores_mixedPlan_onlyFlagsWhenTheNonFullTextFieldIsMissing();
		MatchPlanSimulationRunnerScoreMatchTest.nearMissApplicable_falseWithNoExactFieldOrKeyEqualsOnlyExactField();
		MatchPlanSimulationRunnerScoreMatchTest.buildScoreDiagnostics_countsEveryOccurrenceCapsDistinctMessagesAtThree();
		MatchPlanSimulationRunnerScoreMatchTest.buildScoreDiagnostics_nullWhenNothingToReport();
		System.out.println("MatchPlanSimulationRunnerScoreMatchTest OK");
	}

	// ------------------------------------------------------------------------------------------- path normalisation

	private static void normalizeFieldPath_stripsLeadingDotSlashOrSlash()
	{
		MatchPlanSimulationRunnerScoreMatchTest.check("./ prefix stripped", "name".equals(MatchPlanSimulationRunner.normalizeFieldPath("./name")));
		MatchPlanSimulationRunnerScoreMatchTest.check("/ prefix stripped", "name".equals(MatchPlanSimulationRunner.normalizeFieldPath("/name")));
		MatchPlanSimulationRunnerScoreMatchTest.check("bare name unchanged", "name".equals(MatchPlanSimulationRunner.normalizeFieldPath("name")));
		MatchPlanSimulationRunnerScoreMatchTest.check("null in, null out", MatchPlanSimulationRunner.normalizeFieldPath(null) == null);
	}

	private static void lastPathStep_returnsSubstringAfterLastSlash()
	{
		MatchPlanSimulationRunnerScoreMatchTest.check("last step of a multi-segment path", "name".equals(MatchPlanSimulationRunner.lastPathStep("root/Company/name")));
		MatchPlanSimulationRunnerScoreMatchTest.check("no slash - the whole string", "name".equals(MatchPlanSimulationRunner.lastPathStep("name")));
		MatchPlanSimulationRunnerScoreMatchTest.check("null in, null out", MatchPlanSimulationRunner.lastPathStep(null) == null);
	}

	/** Task brief item 2: "strip a leading / or ./, compare the last path step too". */
	private static void matchScoreFieldToPlanField_exactAndLastStepAndNoMatch()
	{
		final List<String> planFields = Arrays.asList("name", "country");
		MatchPlanSimulationRunnerScoreMatchTest.check("./ prefix normalised then matched exactly",
			"name".equals(MatchPlanSimulationRunner.matchScoreFieldToPlanField("./name", planFields)));
		MatchPlanSimulationRunnerScoreMatchTest.check("case-insensitive exact match after normalising",
			"name".equals(MatchPlanSimulationRunner.matchScoreFieldToPlanField("NAME", planFields)));
		MatchPlanSimulationRunnerScoreMatchTest.check("last path step matches a differently-rooted raw name",
			"name".equals(MatchPlanSimulationRunner.matchScoreFieldToPlanField("root/Company/name", planFields)));
		MatchPlanSimulationRunnerScoreMatchTest.check("no match -> null, never guessed",
			MatchPlanSimulationRunner.matchScoreFieldToPlanField("totallyUnrelatedField", planFields) == null);
		MatchPlanSimulationRunnerScoreMatchTest.check("null raw name -> null", MatchPlanSimulationRunner.matchScoreFieldToPlanField(null, planFields) == null);
	}

	// ------------------------------------------------------------------------------------------------- score merge

	/** The ordinary, healthy case: a raw score field name differs only by a leading "./" from the plan's own spelling, matches, and is never flagged as a diagnostic. */
	private static void mergeFieldScores_matchesNormalizedFieldNameAndNeverFlagsWhenScored()
	{
		final MameMatchingApi.TreeEvaluation evaluation = MatchPlanSimulationRunnerScoreMatchTest.evaluation("./name", 90.0, "getNodeScores() answered 1 node(s)",
			false);
		final Map<String, Double> scores = new LinkedHashMap<String, Double>();
		final List<String> diagnostics = new ArrayList<String>();
		MatchPlanSimulationRunner.mergeFieldScores(scores, evaluation, Arrays.asList("name"), new ArrayList<String>(), new LinkedHashSet<String>(), diagnostics);
		MatchPlanSimulationRunnerScoreMatchTest.check("score stored under the PLAN's own spelling", Double.valueOf(90.0).equals(scores.get("name")));
		MatchPlanSimulationRunnerScoreMatchTest.check("no diagnostic - the expected field WAS scored", diagnostics.isEmpty());
	}

	/**
	 * Report scenario (b): a successful call whose {@code getNodeScores()} came back empty for a field the
	 * engine WAS expected to score (not exact, not full-text) - this must be flagged.
	 */
	private static void mergeFieldScores_flagsWhenAnExpectedFieldIsNeverScored()
	{
		final MameMatchingApi.TreeEvaluation evaluation = new MameMatchingApi.TreeEvaluation(null, new ArrayList<String>(), null,
			new ArrayList<MameMatchingApi.FieldScore>(), "getNodeScores() answered 0 node(s), field names: []; nodeNames: [N1]", false);
		final Map<String, Double> scores = new LinkedHashMap<String, Double>();
		final List<String> diagnostics = new ArrayList<String>();
		MatchPlanSimulationRunner.mergeFieldScores(scores, evaluation, Arrays.asList("name"), new ArrayList<String>(), new LinkedHashSet<String>(), diagnostics);
		MatchPlanSimulationRunnerScoreMatchTest.check("no score stored", scores.isEmpty());
		MatchPlanSimulationRunnerScoreMatchTest.check("flagged - a real, expected field never got a score: " + diagnostics,
			diagnostics.size() == 1 && diagnostics.get(0).contains("getNodeScores() answered 0 node(s)"));
	}

	/**
	 * Report scenario (c): a full-text-only plan (the live Company__preview incident, one AVERAGE node on
	 * {@code name}/{@code fuzzyFullText}) - the same empty result as above must NEVER be flagged, because the
	 * add-on not scoring a full-text field is an expected characteristic (HYPOTHESIS), not a failure.
	 */
	private static void mergeFieldScores_fullTextOnlyPlan_neverFlags()
	{
		final MameMatchingApi.TreeEvaluation evaluation = new MameMatchingApi.TreeEvaluation(null, new ArrayList<String>(), null,
			new ArrayList<MameMatchingApi.FieldScore>(), "getNodeScores() answered 0 node(s), field names: []; nodeNames: [N1]", false);
		final Map<String, Double> scores = new LinkedHashMap<String, Double>();
		final List<String> diagnostics = new ArrayList<String>();
		final Set<String> fullTextFields = new LinkedHashSet<String>();
		fullTextFields.add("name");
		MatchPlanSimulationRunner.mergeFieldScores(scores, evaluation, Arrays.asList("name"), new ArrayList<String>(), fullTextFields, diagnostics);
		MatchPlanSimulationRunnerScoreMatchTest.check("never flagged for a full-text-only plan", diagnostics.isEmpty());
	}

	/** Report scenario (a): an outright exception ALWAYS gets flagged, even for a full-text-only plan (a real failure, not the expected full-text characteristic). */
	private static void mergeFieldScores_alwaysFlagsOnFailureEvenForAFullTextField()
	{
		final MameMatchingApi.TreeEvaluation evaluation = new MameMatchingApi.TreeEvaluation(null, new ArrayList<String>(), null,
			new ArrayList<MameMatchingApi.FieldScore>(),
			"java.lang.IllegalArgumentException: field [Linked_Field_State] not found at a.B.c(D.java:12)", true);
		final Map<String, Double> scores = new LinkedHashMap<String, Double>();
		final List<String> diagnostics = new ArrayList<String>();
		final Set<String> fullTextFields = new LinkedHashSet<String>();
		fullTextFields.add("name");
		MatchPlanSimulationRunner.mergeFieldScores(scores, evaluation, Arrays.asList("name"), new ArrayList<String>(), fullTextFields, diagnostics);
		MatchPlanSimulationRunnerScoreMatchTest.check("failure always flagged, regardless of full-text fields", diagnostics.size() == 1
			&& diagnostics.get(0).contains("IllegalArgumentException"));
	}

	/** A plan with BOTH a full-text field and a plain exact-tier field the engine should still score (an AVERAGE tier mixing a fuzzy anchor with exact supporting fields, the real MATCH-1 shape) - only the missing non-full-text field triggers a diagnostic. */
	private static void mergeFieldScores_mixedPlan_onlyFlagsWhenTheNonFullTextFieldIsMissing()
	{
		final MameMatchingApi.FieldScore countryScore = new MameMatchingApi.FieldScore("country", 100.0, "FR", "FR");
		final List<MameMatchingApi.FieldScore> fieldScores = new ArrayList<MameMatchingApi.FieldScore>();
		fieldScores.add(countryScore);
		final MameMatchingApi.TreeEvaluation evaluation = new MameMatchingApi.TreeEvaluation("SUSPECTED", Arrays.asList("N1"), "N1", fieldScores,
			"getNodeScores() answered 1 node(s), field names: [country]; nodeNames: [N1]", false);
		final Map<String, Double> scores = new LinkedHashMap<String, Double>();
		final List<String> diagnostics = new ArrayList<String>();
		final Set<String> fullTextFields = new LinkedHashSet<String>();
		fullTextFields.add("name");
		// "name" (full-text, never scored) and "country" (plain, WAS scored) are both expected fields, but only
		// "country" needs to have been scored to avoid a diagnostic
		MatchPlanSimulationRunner.mergeFieldScores(scores, evaluation, Arrays.asList("name", "country"), new ArrayList<String>(), fullTextFields, diagnostics);
		MatchPlanSimulationRunnerScoreMatchTest.check("country's score stored", Double.valueOf(100.0).equals(scores.get("country")));
		MatchPlanSimulationRunnerScoreMatchTest.check("not flagged - the one non-full-text expected field (country) WAS scored", diagnostics.isEmpty());
	}

	// ------------------------------------------------------------------------------------------ near-miss applicable

	/** Task brief item 3: not applicable with no exact field at all, or when the only exact field IS the key itself (nothing left to probe a "same exact fields, different key" pair with). */
	private static void nearMissApplicable_falseWithNoExactFieldOrKeyEqualsOnlyExactField()
	{
		MatchPlanSimulationRunnerScoreMatchTest.check("no exact field at all -> not applicable",
			!MatchPlanSimulationRunner.nearMissApplicable(new ArrayList<String>(), "nameKey"));
		MatchPlanSimulationRunnerScoreMatchTest.check("no key field resolved -> not applicable",
			!MatchPlanSimulationRunner.nearMissApplicable(Arrays.asList("country"), null));
		MatchPlanSimulationRunnerScoreMatchTest.check("the only exact field IS the key -> not applicable",
			!MatchPlanSimulationRunner.nearMissApplicable(Arrays.asList("nameKey"), "nameKey"));
		MatchPlanSimulationRunnerScoreMatchTest.check("a real, different exact field besides the key -> applicable",
			MatchPlanSimulationRunner.nearMissApplicable(Arrays.asList("country", "cityKey"), "nameKey"));
	}

	// ---------------------------------------------------------------------------------------------- diagnostics

	/** Task brief item 1: "count + first distinct messages, capped at 3". */
	private static void buildScoreDiagnostics_countsEveryOccurrenceCapsDistinctMessagesAtThree()
	{
		final List<String> raw = Arrays.asList("A", "A", "B", "C", "D", "D", "D");
		final MatchPlanView.ScoreDiagnostics diagnostics = MatchPlanSimulationRunner.buildScoreDiagnostics(raw);
		MatchPlanSimulationRunnerScoreMatchTest.check("count is every occurrence, not just distinct ones", diagnostics.count == 7);
		MatchPlanSimulationRunnerScoreMatchTest.check("capped at 3 distinct messages, first-seen order: " + diagnostics.messages,
			diagnostics.messages.size() == 3 && "A".equals(diagnostics.messages.get(0)) && "B".equals(diagnostics.messages.get(1))
				&& "C".equals(diagnostics.messages.get(2)));
	}

	private static void buildScoreDiagnostics_nullWhenNothingToReport()
	{
		MatchPlanSimulationRunnerScoreMatchTest.check("null, never a zero-count instance",
			MatchPlanSimulationRunner.buildScoreDiagnostics(new ArrayList<String>()) == null);
	}

	// ----------------------------------------------------------------------------------------------------- helpers

	private static MameMatchingApi.TreeEvaluation evaluation(final String pRawFieldName, final double pScore, final String pDiagnostic, final boolean pFailed)
	{
		final List<MameMatchingApi.FieldScore> fieldScores = new ArrayList<MameMatchingApi.FieldScore>();
		fieldScores.add(new MameMatchingApi.FieldScore(pRawFieldName, pScore, "pivot", "candidate"));
		return new MameMatchingApi.TreeEvaluation("SUSPECTED", Arrays.asList("N1"), "N1", fieldScores, pDiagnostic, pFailed);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
