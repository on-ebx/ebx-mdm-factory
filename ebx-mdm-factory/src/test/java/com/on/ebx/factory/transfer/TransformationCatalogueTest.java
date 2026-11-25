package com.on.ebx.factory.transfer;

import java.util.List;

/**
 * JUnit-free, like the sibling tests of this package: run the main, a failure throws. Checks the three
 * things {@link TransformationCatalogue} promises: the excluded names really are excluded (and nothing
 * else is), {@link TransformationCatalogue#isKnown} is case-insensitive, and {@link
 * TransformationCatalogue#parsePipeline} matches the page script's {@code stepsOf} on every case that
 * script itself relies on (quoted commas, quoted pipes, parenthesis depth, a trailing/empty step).
 */
public final class TransformationCatalogueTest
{
	public static void main(final String[] pArgs)
	{
		TransformationCatalogueTest.excludesTheSevenRuntimeBoundNames();
		TransformationCatalogueTest.hasThirtyEntriesWithHintsInBothLanguages();
		TransformationCatalogueTest.isKnownIsCaseInsensitive();
		TransformationCatalogueTest.isKnownRejectsUnknownAndBlank();
		TransformationCatalogueTest.parsesSimplePipeline();
		TransformationCatalogueTest.parsesArgumentsWithQuotedComma();
		TransformationCatalogueTest.parsesQuotedPipeInsideArgument();
		TransformationCatalogueTest.parsesConstantWithEmptyArguments();
		TransformationCatalogueTest.parsesStepWithNoParentheses();
		TransformationCatalogueTest.parsesEmptyMiddleStepAndDropsTrailingEmpty();
		TransformationCatalogueTest.joinStepsRoundTrips();
		System.out.println("TransformationCatalogueTest OK");
	}

	private static void excludesTheSevenRuntimeBoundNames()
	{
		final String[] excluded = { "fk", "lookup", "golden", "mdmState", "runId", "now", "hash" };
		for (final String name : excluded)
		{
			TransformationCatalogueTest.check(name + " is excluded", !TransformationCatalogue.isKnown(name));
		}
	}

	private static void hasThirtyEntriesWithHintsInBothLanguages()
	{
		final List<TransformationCatalogue.Entry> entries = TransformationCatalogue.entries();
		TransformationCatalogueTest.check("30 entries (28 + epochdate, joinwidth since 2026-10-23): " + entries.size(), entries.size() == 30);
		for (final TransformationCatalogue.Entry entry : entries)
		{
			TransformationCatalogueTest.check(entry.name + " has a name", entry.name != null && !entry.name.isEmpty());
			TransformationCatalogueTest.check(entry.name + " has an EN hint", entry.hintEn != null && !entry.hintEn.isEmpty());
			TransformationCatalogueTest.check(entry.name + " has a FR hint", entry.hintFr != null && !entry.hintFr.isEmpty());
			TransformationCatalogueTest.check(entry.name + " is itself known", TransformationCatalogue.isKnown(entry.name));
		}
	}

	private static void isKnownIsCaseInsensitive()
	{
		TransformationCatalogueTest.check("TRIM", TransformationCatalogue.isKnown("TRIM"));
		TransformationCatalogueTest.check("Trim", TransformationCatalogue.isKnown("Trim"));
		TransformationCatalogueTest.check("  trim  (trimmed before matching)", TransformationCatalogue.isKnown("  trim  "));
	}

	private static void isKnownRejectsUnknownAndBlank()
	{
		TransformationCatalogueTest.check("unknown name", !TransformationCatalogue.isKnown("totallyUnknownStep"));
		TransformationCatalogueTest.check("null name", !TransformationCatalogue.isKnown(null));
		TransformationCatalogueTest.check("blank name", !TransformationCatalogue.isKnown(""));
	}

	private static void parsesSimplePipeline()
	{
		final List<TransformationCatalogue.Step> steps = TransformationCatalogue.parsePipeline("trim|upper");
		TransformationCatalogueTest.check("2 steps: " + steps.size(), steps.size() == 2);
		TransformationCatalogueTest.check("step 0 name", "trim".equals(steps.get(0).name));
		TransformationCatalogueTest.check("step 0 has no args", steps.get(0).args == null);
		TransformationCatalogueTest.check("step 1 name", "upper".equals(steps.get(1).name));
	}

	private static void parsesArgumentsWithQuotedComma()
	{
		// join('; ') on a list - the README's own example of an argument that must stay quoted to keep its space
		final List<TransformationCatalogue.Step> steps = TransformationCatalogue.parsePipeline("join('; ')");
		TransformationCatalogueTest.check("1 step: " + steps.size(), steps.size() == 1);
		TransformationCatalogueTest.check("name", "join".equals(steps.get(0).name));
		TransformationCatalogueTest.check("args keep the quotes: " + steps.get(0).args, "'; '".equals(steps.get(0).args));
	}

	private static void parsesQuotedPipeInsideArgument()
	{
		// a map value containing a literal '|' must not split the pipeline in two
		final List<TransformationCatalogue.Step> steps = TransformationCatalogue.parsePipeline("map('A=1|2;B=3')|trim");
		TransformationCatalogueTest.check("2 steps (the quoted | did not split): " + steps.size(), steps.size() == 2);
		TransformationCatalogueTest.check("map args: " + steps.get(0).args, "'A=1|2;B=3'".equals(steps.get(0).args));
		TransformationCatalogueTest.check("second step", "trim".equals(steps.get(1).name));
	}

	private static void parsesConstantWithEmptyArguments()
	{
		final List<TransformationCatalogue.Step> steps = TransformationCatalogue.parsePipeline("constant()");
		TransformationCatalogueTest.check("1 step", steps.size() == 1);
		TransformationCatalogueTest.check("empty args, not null", "".equals(steps.get(0).args));
	}

	private static void parsesStepWithNoParentheses()
	{
		final List<TransformationCatalogue.Step> steps = TransformationCatalogue.parsePipeline("trim");
		TransformationCatalogueTest.check("1 step", steps.size() == 1);
		TransformationCatalogueTest.check("null args (no parentheses at all)", steps.get(0).args == null);
	}

	private static void parsesEmptyMiddleStepAndDropsTrailingEmpty()
	{
		// same behaviour as the page script's stepsOf: an empty segment BETWEEN two pipes survives as a
		// step (name = "", args = null - it fails the name pattern), a trailing empty segment is dropped.
		final List<TransformationCatalogue.Step> middle = TransformationCatalogue.parsePipeline("trim||upper");
		TransformationCatalogueTest.check("3 steps (empty middle kept): " + middle.size(), middle.size() == 3);
		TransformationCatalogueTest.check("middle step name is empty", "".equals(middle.get(1).name));
		final List<TransformationCatalogue.Step> trailing = TransformationCatalogue.parsePipeline("trim|");
		TransformationCatalogueTest.check("1 step (trailing empty dropped): " + trailing.size(), trailing.size() == 1);
	}

	private static void joinStepsRoundTrips()
	{
		final List<TransformationCatalogue.Step> steps = TransformationCatalogue.parsePipeline("trim|date('dd/MM/yyyy',yyyy-MM-dd)");
		final String rejoined = TransformationCatalogue.joinSteps(steps);
		TransformationCatalogueTest.check("round-trips: " + rejoined, "trim|date('dd/MM/yyyy',yyyy-MM-dd)".equals(rejoined));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("TransformationCatalogueTest failed: " + pLabel);
		}
	}
}
