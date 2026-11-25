package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Lot M8b, 2026-10-04. */
public final class CaseOrderRuleTest
{
	/** The two regexes the language model proposed on the Vol1m full-name column (and the form that reads the token in capitals first). */
	private static final String CAPITALS_ELSE_LAST = "regex((?:^(?=[\\p{Lu}'-]+\\s)|(?<=\\s)(?=[\\p{L}'-]+$))([\\p{L}'-]+))";
	private static final String LOWER_AFTER_CAPITALS = "regex(^(?:[\\p{Lu}'-]+\\s+)?([\\p{L}'-]*\\p{Ll}[\\p{L}'-]*))";

	public static void main(final String[] pArgs)
	{
		CaseOrderRuleTest.stepKindsThatChangeCase();
		CaseOrderRuleTest.patternsThatReadCase();
		CaseOrderRuleTest.patternsAreReadTheWayConnectorsCutsAPipeline();
		CaseOrderRuleTest.stepRefusalOnTheVol1mShape();
		CaseOrderRuleTest.pipelineRefusalInsideOnePipeline();
		System.out.println("CaseOrderRuleTest OK");
	}

	private static void stepKindsThatChangeCase()
	{
		CaseOrderRuleTest.check("UPPER", CaseOrderRule.changesCase("UPPER"));
		CaseOrderRuleTest.check("LOWER, any case", CaseOrderRule.changesCase(" lower "));
		CaseOrderRuleTest.check("NORMALISE_IDENTIFIER upper-cases", CaseOrderRule.changesCase("NORMALISE_IDENTIFIER"));
		for (final String kind : new String[] { "TRIM", "COLLAPSE_SPACES", "EMPTY_TO_NULL", "STRIP_CHARS", "STRIP_ACCENTS", "COUNTRY_TO_ISO2", "TRANSCODE_REFERENCE", "", null })
		{
			CaseOrderRuleTest.check(kind + " keeps case", !CaseOrderRule.changesCase(kind));
		}
		CaseOrderRuleTest.check("pipeline upper/lower/capitalize", CaseOrderRule.pipelineStepChangesCase("upper") && CaseOrderRule.pipelineStepChangesCase("Lower")
			&& CaseOrderRule.pipelineStepChangesCase("capitalize") && !CaseOrderRule.pipelineStepChangesCase("trim") && !CaseOrderRule.pipelineStepChangesCase("regex"));
	}

	private static void patternsThatReadCase()
	{
		for (final String pattern : new String[] { "\\p{Lu}+", "\\p{Ll}", "\\P{Lu}", "\\p{Upper}", "\\p{Lower}+", "\\p{IsUppercase}", "\\p{javaLowerCase}", "[A-Z]+", "[a-z]",
			"[A-Za-z]+", "[^a-z]", "^(?:[\\p{Lu}'-]+\\s+)?([\\p{L}'-]*\\p{Ll}[\\p{L}'-]*)" })
		{
			CaseOrderRuleTest.check("reads case: " + pattern, CaseOrderRule.patternReadsCase(pattern));
		}
		for (final String pattern : new String[] { "\\p{L}+", "\\d+", "^(\\w+)", "[0-9]+", "[A-]", "\\s*(.*)$", "(?i)smith", "", null })
		{
			CaseOrderRuleTest.check("does not read case: " + pattern, !CaseOrderRule.patternReadsCase(pattern));
		}
		CaseOrderRuleTest.check("the Vol1m regexes both read case", CaseOrderRule.pipelineRegexReadsCase(CaseOrderRuleTest.CAPITALS_ELSE_LAST)
			&& CaseOrderRule.pipelineRegexReadsCase(CaseOrderRuleTest.LOWER_AFTER_CAPITALS));
		CaseOrderRuleTest.check("a pipeline with no regex does not", !CaseOrderRule.pipelineRegexReadsCase("trim|upper|map(A=B)"));
	}

	private static void patternsAreReadTheWayConnectorsCutsAPipeline()
	{
		CaseOrderRuleTest.check("one pattern, the alternation's | does not cut the step", CaseOrderRule.regexPatterns(CaseOrderRuleTest.CAPITALS_ELSE_LAST).size() == 1
			&& CaseOrderRule.regexPatterns(CaseOrderRuleTest.CAPITALS_ELSE_LAST).get(0).startsWith("(?:^(?=[\\p{Lu}'-]+\\s)|(?<=\\s)"));
		final List<String> quoted = CaseOrderRule.regexPatterns("trim|regex('^\\d{1,3},(.*)',1)|upper");
		CaseOrderRuleTest.check("a quoted pattern with a comma is one argument, unquoted", quoted.size() == 1 && "^\\d{1,3},(.*)".equals(quoted.get(0)));
		CaseOrderRuleTest.check("no regex step, no pattern", CaseOrderRule.regexPatterns("trim|upper").isEmpty() && CaseOrderRule.regexPatterns(null).isEmpty());
		CaseOrderRuleTest.check("two regex steps, two patterns", CaseOrderRule.regexPatterns("regex(^a)|regex(b$)").size() == 2);
	}

	private static void stepRefusalOnTheVol1mShape()
	{
		final String refusal = CaseOrderRule.stepRefusal("Personne.lastName", "UPPER", Arrays.asList(CaseOrderRuleTest.CAPITALS_ELSE_LAST));
		CaseOrderRuleTest.check("UPPER ahead of the Vol1m regex is refused", refusal != null);
		CaseOrderRuleTest.check("the message names the column, the order and the fix", refusal.contains("Personne.lastName") && refusal.contains("BEFORE the mapping's own pipeline")
			&& refusal.contains("regex(...)|upper"));
		CaseOrderRuleTest.check("LOWER the same", CaseOrderRule.stepRefusal("A.b", "LOWER", Arrays.asList(CaseOrderRuleTest.LOWER_AFTER_CAPITALS)) != null);
		CaseOrderRuleTest.check("TRIM is not a case change", CaseOrderRule.stepRefusal("A.b", "TRIM", Arrays.asList(CaseOrderRuleTest.CAPITALS_ELSE_LAST)) == null);
		CaseOrderRuleTest.check("no pipeline, nothing to break", CaseOrderRule.stepRefusal("A.b", "UPPER", Collections.<String>emptyList()) == null
			&& CaseOrderRule.stepRefusal("A.b", "UPPER", null) == null);
		CaseOrderRuleTest.check("a regex that does not read case is no reason", CaseOrderRule.stepRefusal("A.b", "UPPER", Arrays.asList("regex(^(\\p{L}+))")) == null);
		final List<String> several = new ArrayList<String>(Arrays.asList(null, "", "trim", CaseOrderRuleTest.CAPITALS_ELSE_LAST));
		CaseOrderRuleTest.check("one case-reading pipeline among several refuses", CaseOrderRule.stepRefusal("A.b", "UPPER", several) != null);
	}

	private static void pipelineRefusalInsideOnePipeline()
	{
		final String refusal = CaseOrderRule.pipelineRefusal("upper|" + CaseOrderRuleTest.CAPITALS_ELSE_LAST);
		CaseOrderRuleTest.check("upper before a case-reading regex is refused", refusal != null && refusal.contains("regex(...)|upper"));
		CaseOrderRuleTest.check("trim|lower|regex too", CaseOrderRule.pipelineRefusal("trim|lower|" + CaseOrderRuleTest.LOWER_AFTER_CAPITALS) != null);
		CaseOrderRuleTest.check("the case change AFTER the regex is the right form", CaseOrderRule.pipelineRefusal(CaseOrderRuleTest.CAPITALS_ELSE_LAST + "|upper") == null);
		CaseOrderRuleTest.check("upper before a regex that does not read case is fine", CaseOrderRule.pipelineRefusal("upper|regex(^(\\p{L}+))") == null);
		CaseOrderRuleTest.check("no case change, no refusal", CaseOrderRule.pipelineRefusal("trim|" + CaseOrderRuleTest.CAPITALS_ELSE_LAST) == null);
		CaseOrderRuleTest.check("empty and null pipelines", CaseOrderRule.pipelineRefusal("") == null && CaseOrderRule.pipelineRefusal(null) == null);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
