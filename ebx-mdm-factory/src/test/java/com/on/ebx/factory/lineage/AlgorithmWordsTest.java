package com.on.ebx.factory.lineage;

/** JUnit-free, like the sibling tests: run the main, a failure throws. */
public final class AlgorithmWordsTest
{
	public static void main(final String[] pArgs)
	{
		AlgorithmWordsTest.allElevenMeasuredCodesResolveToNonEmptyWords();
		AlgorithmWordsTest.prefixIsStrippedBeforeLookup();
		AlgorithmWordsTest.unknownCodeShowsTheRawSuffixRatherThanDropping();
		AlgorithmWordsTest.blankOrNullNeverThrowsAndAnswersEmpty();
		System.out.println("AlgorithmWordsTest OK");
	}

	/** The 11 `ebx-addon-mame@...` codes `docs/MATCHING.md` measured live from `matching_config_schema` (2026-09-21) - every one must resolve to real words, never fall back to the "unrecognised" shape. */
	private static void allElevenMeasuredCodesResolveToNonEmptyWords()
	{
		final String[] codes = { "exact", "jarowinkler", "levenshtein", "beider-morse", "soundex", "range", "operator", "hybridFuzzy", "fuzzyFullText", "exactFullText",
			"phoneticFullText" };
		for (final String code : codes)
		{
			final String words = AlgorithmWords.words("ebx-addon-mame@" + code);
			AlgorithmWordsTest.check("recognised, non-empty words for " + code, words != null && !words.isEmpty() && !words.startsWith("algorithm '"));
		}
	}

	/** Case-insensitive lookup, and the {@code ebx-addon-mame@} prefix is stripped only for the LOOKUP - never mutates or re-emits the code itself (this class only ever returns words, never a code). */
	private static void prefixIsStrippedBeforeLookup()
	{
		AlgorithmWordsTest.check("with prefix", "exact match".equals(AlgorithmWords.words("ebx-addon-mame@exact")));
		AlgorithmWordsTest.check("without prefix, same word", "exact match".equals(AlgorithmWords.words("exact")));
		AlgorithmWordsTest.check("case-insensitive", "exact match".equals(AlgorithmWords.words("EBX-ADDON-MAME@EXACT")));
	}

	/** An algorithm code this map does not know (a future add-on addition) still renders - the raw suffix, quoted - never silently dropped. */
	private static void unknownCodeShowsTheRawSuffixRatherThanDropping()
	{
		final String words = AlgorithmWords.words("ebx-addon-mame@futureAlgorithm");
		AlgorithmWordsTest.check("unknown code names itself rather than vanishing", words.contains("futureAlgorithm"));
	}

	private static void blankOrNullNeverThrowsAndAnswersEmpty()
	{
		AlgorithmWordsTest.check("null answers empty, never throws", "".equals(AlgorithmWords.words(null)));
		AlgorithmWordsTest.check("blank answers empty", "".equals(AlgorithmWords.words("   ")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
