package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free: run the main, a failure throws. Lot MP (2026-10-04): the wording and the warning rule of what a load says about the matching it paused. */
public final class MatchingPauseSummaryTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MatchingPauseSummaryTest.aCleanPauseResumeAndCatchUpIsASentenceNotAWarning();
		MatchingPauseSummaryTest.aTableLeftPausedIsAWarningWithTheRemedy();
		MatchingPauseSummaryTest.aPauseThatCouldNotBeMadeIsAWarning();
		MatchingPauseSummaryTest.aFailedCatchUpIsAWarning();
		MatchingPauseSummaryTest.wholeTableAndNothingToMatchAndSkipped();
		MatchingPauseSummaryTest.nothingPausedSaysNothing();
		System.out.println("MatchingPauseSummaryTest OK");
	}

	private static JsonNode json(final String pText) throws Exception
	{
		return MatchingPauseSummaryTest.MAPPER.readTree(pText);
	}

	private static void aCleanPauseResumeAndCatchUpIsASentenceNotAWarning() throws Exception
	{
		final JsonNode pause = MatchingPauseSummaryTest.json("{\"trigger\":\"REALTIME\",\"tables\":[{\"entity\":\"Personne\",\"table\":\"/root/Personne\",\"action\":\"PAUSED\","
			+ "\"pausedByLoad\":true,\"resumed\":true,\"catchUp\":{\"status\":\"DONE\",\"mode\":\"ENUMERATED\",\"selected\":412,\"runCount\":3}}]}");
		MatchingPauseSummaryTest.check("no warning", MatchingPauseSummary.warning(pause).isEmpty());
		final String sentence = MatchingPauseSummary.sentence(pause);
		MatchingPauseSummaryTest.check("recap: " + sentence, sentence.contains("paused on Personne") && sentence.contains("and resumed") && sentence.contains("412 record(s) matched afterwards")
			&& sentence.contains("3 run(s) on explicit keys"));
	}

	private static void aTableLeftPausedIsAWarningWithTheRemedy() throws Exception
	{
		final JsonNode pause = MatchingPauseSummaryTest.json("{\"tables\":[{\"entity\":\"Adresse\",\"table\":\"/root/Adresse\",\"action\":\"PAUSED\",\"pausedByLoad\":true,"
			+ "\"resumed\":false,\"resumeError\":\"set_table_settings answered a soft miss\"}]}");
		final String warning = MatchingPauseSummary.warning(pause);
		MatchingPauseSummaryTest.check("names the table and the state: " + warning, warning.startsWith("WARNING:") && warning.contains("matching of Adresse is still PAUSED"));
		MatchingPauseSummaryTest.check("gives the remedy: " + warning, warning.contains("set_table_settings") && warning.contains("/root/Adresse") && warning.contains("pauseMatching"));
		MatchingPauseSummaryTest.check("the recap points at the warning", MatchingPauseSummary.sentence(pause).contains("see the warning"));
	}

	private static void aPauseThatCouldNotBeMadeIsAWarning() throws Exception
	{
		final JsonNode failed = MatchingPauseSummaryTest.json("{\"tables\":[{\"entity\":\"Adresse\",\"action\":\"PAUSE_FAILED\",\"pausedByLoad\":false,\"note\":\"HTTP 503\"}]}");
		MatchingPauseSummaryTest.check("pause failed", MatchingPauseSummary.warning(failed).contains("could not be paused (HTTP 503)"));
		final JsonNode skipped = MatchingPauseSummaryTest.json("{\"skipped\":\"no match-merge client: agent account not set\",\"tables\":[]}");
		MatchingPauseSummaryTest.check("no client", MatchingPauseSummary.warning(skipped).contains("agent account not set"));
		final JsonNode uncertain = MatchingPauseSummaryTest.json("{\"tables\":[{\"entity\":\"Adresse\",\"table\":\"/root/Adresse\",\"action\":\"PAUSE_FAILED\",\"pausedByLoad\":false,"
			+ "\"note\":\"read timed out\",\"resumed\":false,\"resumeError\":\"read timed out\"}]}");
		MatchingPauseSummaryTest.check("an uncertain pause that could not be resumed is warned about", MatchingPauseSummary.warning(uncertain).contains("still PAUSED"));
		final JsonNode alreadyPaused = MatchingPauseSummaryTest.json("{\"tables\":[{\"entity\":\"Adresse\",\"action\":\"ALREADY_PAUSED\",\"pausedByLoad\":false}]}");
		MatchingPauseSummaryTest.check("a table paused by someone else is neither warned about nor resumed", MatchingPauseSummary.warning(alreadyPaused).isEmpty()
			&& MatchingPauseSummary.sentence(alreadyPaused).isEmpty());
	}

	private static void aFailedCatchUpIsAWarning() throws Exception
	{
		final JsonNode pause = MatchingPauseSummaryTest.json("{\"tables\":[{\"entity\":\"Personne\",\"table\":\"/root/Personne\",\"pausedByLoad\":true,\"resumed\":true,"
			+ "\"catchUp\":{\"status\":\"FAILED\",\"error\":\"matchRecords refused\"}}]}");
		final String warning = MatchingPauseSummary.warning(pause);
		MatchingPauseSummaryTest.check("failed catch-up: " + warning, warning.contains("catch-up of Personne failed (matchRecords refused)") && warning.contains("whole table"));
	}

	private static void wholeTableAndNothingToMatchAndSkipped() throws Exception
	{
		final JsonNode pause = MatchingPauseSummaryTest.json("{\"tables\":["
			+ "{\"entity\":\"Personne\",\"pausedByLoad\":true,\"resumed\":true,\"catchUp\":{\"status\":\"DONE\",\"mode\":\"WHOLE_TABLE\",\"selected\":634295,\"runCount\":1}},"
			+ "{\"entity\":\"Adresse\",\"pausedByLoad\":true,\"resumed\":true,\"catchUp\":{\"status\":\"NOTHING_TO_MATCH\"}},"
			+ "{\"entity\":\"Contact\",\"pausedByLoad\":true,\"resumed\":true,\"catchUp\":{\"status\":\"SKIPPED\",\"reason\":\"the load was stopped\"}}]}");
		final String sentence = MatchingPauseSummary.sentence(pause);
		MatchingPauseSummaryTest.check("whole table: " + sentence, sentence.contains("634,295 record(s) matched afterwards (one run on the whole table"));
		MatchingPauseSummaryTest.check("nothing to match: " + sentence, sentence.contains("Adresse: no record to match afterwards"));
		MatchingPauseSummaryTest.check("skipped: " + sentence, sentence.contains("Contact: catch-up not run (the load was stopped)"));
	}

	private static void nothingPausedSaysNothing() throws Exception
	{
		MatchingPauseSummaryTest.check("null", MatchingPauseSummary.warning(null).isEmpty() && MatchingPauseSummary.sentence(null).isEmpty());
		final JsonNode none = MatchingPauseSummaryTest.json("{\"tables\":[]}");
		MatchingPauseSummaryTest.check("no table", MatchingPauseSummary.warning(none).isEmpty() && MatchingPauseSummary.sentence(none).isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
