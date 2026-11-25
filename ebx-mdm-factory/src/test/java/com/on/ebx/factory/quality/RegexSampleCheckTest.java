package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Lot M8b, 2026-10-04. */
public final class RegexSampleCheckTest
{
	public static void main(final String[] pArgs)
	{
		RegexSampleCheckTest.groupOneWhenThePatternHasAGroupElseGroupZero();
		RegexSampleCheckTest.explicitGroupArgument();
		RegexSampleCheckTest.cleaningStepsRunBeforeThePipeline();
		RegexSampleCheckTest.notMeasurableCasesAnswerNull();
		System.out.println("RegexSampleCheckTest OK");
	}

	private static List<CleaningSteps.Step> steps(final String... pKinds)
	{
		final List<CleaningSteps.Step> out = new ArrayList<CleaningSteps.Step>();
		for (final String kind : pKinds)
		{
			out.add(new CleaningSteps.Step(kind, null));
		}
		return out;
	}

	private static void groupOneWhenThePatternHasAGroupElseGroupZero()
	{
		RegexSampleCheck.Outcome outcome = RegexSampleCheck.measure(null, "regex(^Dr (\\w+))", Arrays.asList("Dr Smith", "Dr Jones", "Mme Roy", "  ", null), null);
		RegexSampleCheckTest.check("two of three non-empty values yield", outcome.tried == 3 && outcome.yielded == 2 && !outcome.yieldedNothing());
		RegexSampleCheckTest.check("the input that gives nothing is kept", outcome.emptyInputs.size() == 1 && "Mme Roy".equals(outcome.emptyInputs.get(0)));
		outcome = RegexSampleCheck.measure(null, "regex(\\d+)", Arrays.asList("ab12", "cd"), null);
		RegexSampleCheckTest.check("no group: the whole match", outcome.tried == 2 && outcome.yielded == 1);
	}

	private static void explicitGroupArgument()
	{
		final RegexSampleCheck.Outcome outcome = RegexSampleCheck.measure(null, "regex('^(\\d+),(\\w*)',2)", Arrays.asList("12,rue", "12,"), null);
		RegexSampleCheckTest.check("group 2 empty on the second value: it yields nothing there", outcome.tried == 2 && outcome.yielded == 1);
	}

	private static void cleaningStepsRunBeforeThePipeline()
	{
		final List<String> samples = Arrays.asList("Dr Smith", "Dr Jones");
		RegexSampleCheck.Outcome outcome = RegexSampleCheck.measure(RegexSampleCheckTest.steps("TRIM"), "regex(^Dr (\\w+))", samples, null);
		RegexSampleCheckTest.check("TRIM first: still yields", outcome.yielded == 2);
		outcome = RegexSampleCheck.measure(RegexSampleCheckTest.steps("LOWER"), "regex(^Dr (\\w+))", samples, null);
		RegexSampleCheckTest.check("LOWER first: the pattern finds nothing on every value", outcome.yieldedNothing() && outcome.tried == 2 && outcome.emptyInputs.size() == 2
			&& "Dr Smith".equals(outcome.emptyInputs.get(0)));
		RegexSampleCheckTest.check("LOWER at the END of the pipeline, after the regex, is the right order",
			RegexSampleCheck.measure(null, "regex(^Dr (\\w+))|lower", samples, null).yielded == 2);
	}

	private static void notMeasurableCasesAnswerNull()
	{
		RegexSampleCheckTest.check("no regex step", RegexSampleCheck.measure(null, "trim|upper", Arrays.asList("a"), null) == null);
		RegexSampleCheckTest.check("a step the check cannot reproduce ahead of the regex", RegexSampleCheck.measure(null, "map(A=B)|regex(^(.))", Arrays.asList("A"), null) == null);
		RegexSampleCheckTest.check("an invalid pattern", RegexSampleCheck.measure(null, "regex((unclosed)", Arrays.asList("A"), null) == null);
		RegexSampleCheckTest.check("a catastrophic pattern is refused, not run", RegexSampleCheck.measure(null, "regex('(a+)+$')", Arrays.asList("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!"), null) == null);
		RegexSampleCheckTest.check("a pattern that runs past its time limit is not measurable, never read as nothing matched",
			RegexSampleCheck.measure(null, "regex('^(.*a){28}$')", Arrays.asList("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!"), null) == null);
		RegexSampleCheckTest.check("no sample", RegexSampleCheck.measure(null, "regex(^(.))", Collections.<String>emptyList(), null) == null
			&& RegexSampleCheck.measure(null, "regex(^(.))", Arrays.asList(" ", null), null) == null && RegexSampleCheck.measure(null, "regex(^(.))", null, null) == null);
		RegexSampleCheckTest.check("a step AFTER the last regex is ignored", RegexSampleCheck.measure(null, "regex(^(.))|map(A=B)", Arrays.asList("A"), null).yielded == 1);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
