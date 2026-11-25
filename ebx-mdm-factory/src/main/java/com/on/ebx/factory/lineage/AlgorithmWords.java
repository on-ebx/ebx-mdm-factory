package com.on.ebx.factory.lineage;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Short, human words for a {@code ebx-addon-mame@...} matching algorithm code - the comparison panel's own
 * "field, in words, {@code name} (fuzzy match)" line (task brief, 2026-09-22, "decide on a suspect" lot).
 * <p>
 * <b>A small, LOCAL map - not {@code review.ProposalWording}'s.</b> The task brief said to reuse that class'
 * own algorithm-word map "if it exists on {@code main} by the time you merge" - it does not (checked
 * 2026-09-22: {@code review.ProposalWording} has no algorithm vocabulary of its own, only {@code
 * mappingStepProposition} for cleaning steps), so this is the fallback "small local map" the brief allowed.
 * <p>
 * <b>The 11 codes covered</b> come from `docs/MATCHING.md`'s own measured read of {@code
 * matching_config_schema}'s algorithm enum (2026-09-21, {@code master.MatchPlanLlmPass}'s own investigation):
 * {@code exact, jarowinkler, levenshtein, beider-morse, soundex, range, operator, hybridFuzzy, fuzzyFullText,
 * exactFullText, phoneticFullText} - a real, live-measured enumeration, not a guess. Words are chosen for a
 * data steward reading a comparison table, not for a developer reading a policy - "fuzzy match" rather than
 * an algorithm-family name a steward has no reason to know. An algorithm code this map does not recognise
 * (a future addition to the add-on's own vocabulary) still renders - the raw suffix, quoted - never dropped.
 */
final class AlgorithmWords
{
	private static final Map<String, String> WORDS = new HashMap<String, String>();
	static
	{
		AlgorithmWords.WORDS.put("exact", "exact match");
		AlgorithmWords.WORDS.put("jarowinkler", "similar spelling (Jaro-Winkler)");
		AlgorithmWords.WORDS.put("levenshtein", "similar spelling (edit distance)");
		AlgorithmWords.WORDS.put("beider-morse", "similar-sounding name (Beider-Morse)");
		AlgorithmWords.WORDS.put("soundex", "similar-sounding value (Soundex)");
		AlgorithmWords.WORDS.put("range", "within a numeric range");
		AlgorithmWords.WORDS.put("operator", "custom comparison");
		AlgorithmWords.WORDS.put("hybridfuzzy", "fuzzy match (hybrid)");
		AlgorithmWords.WORDS.put("fuzzyfulltext", "fuzzy full-text match");
		AlgorithmWords.WORDS.put("exactfulltext", "exact full-text match");
		AlgorithmWords.WORDS.put("phoneticfulltext", "phonetic full-text match");
	}

	private AlgorithmWords()
	{
	}

	/**
	 * @param pAlgorithm the raw code, with or without the {@code ebx-addon-mame@} prefix (CLAUDE.md &sect;8's
	 *            own "algorithm codes are prefixed and must be passed as-is" - this method strips the prefix
	 *            only for LOOKUP, never for anything sent back to the add-on) - null/blank answers an empty
	 *            string, never {@code "null"} or a stray label.
	 */
	static String words(final String pAlgorithm)
	{
		if (pAlgorithm == null || pAlgorithm.trim().isEmpty())
		{
			return "";
		}
		final String trimmed = pAlgorithm.trim();
		final int at = trimmed.indexOf('@');
		final String suffix = at >= 0 ? trimmed.substring(at + 1) : trimmed;
		final String words = AlgorithmWords.WORDS.get(suffix.toLowerCase(Locale.ENGLISH));
		return words != null ? words : "algorithm '" + suffix + "'";
	}
}
