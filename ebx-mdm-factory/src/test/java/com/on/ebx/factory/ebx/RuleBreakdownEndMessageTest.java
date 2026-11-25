package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Locale;

import com.on.ebx.factory.quality.RuleFocus;

/** JUnit-free: run the main, a failure throws. Desk lot L4b - the end message of the "Measure" job, which now also says what the model answered. */
public final class RuleBreakdownEndMessageTest
{
	public static void main(final String[] pArgs)
	{
		final RuleFocus proposes = new RuleFocus("a1", "PATTERN", "x");
		proposes.asked = true;
		proposes.conditionAttribute = "countryCode";
		proposes.conditionValues = Arrays.asList("FR");
		check("a condition", "Measured: failures broken down by countryCode. The model proposes the condition countryCode in FR."
			.equals(RuleBreakdownRun.endMessage(Locale.ENGLISH, "countryCode", proposes)));

		final RuleFocus none = new RuleFocus("a1", "PATTERN", "x");
		none.asked = true;
		check("no condition, no note", "Measured: failures broken down by countryCode. The model proposes no condition.".equals(RuleBreakdownRun.endMessage(Locale.ENGLISH, "countryCode", none)));
		none.note = "the failures are spread over every country";
		check("no condition, with its note", "Measured: failures broken down by countryCode. The model proposes no condition: the failures are spread over every country"
			.equals(RuleBreakdownRun.endMessage(Locale.ENGLISH, "countryCode", none)));

		final RuleFocus notAsked = new RuleFocus("a1", "PATTERN", "x");
		notAsked.notAskedReason = "no LLM connection is configured";
		final String text = RuleBreakdownRun.endMessage(Locale.ENGLISH, "countryCode", notAsked);
		check("the breakdown is still reported, the model was not asked and why", text.startsWith("Measured: failures broken down by countryCode.")
			&& text.endsWith("The model was not asked: no LLM connection is configured."));
		check("French", RuleBreakdownRun.endMessage(Locale.FRENCH, "countryCode", proposes).contains("Le mod\u00E8le propose la condition countryCode dans FR."));
		System.out.println("RuleBreakdownEndMessageTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
