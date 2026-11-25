package com.on.ebx.factory.quality;

import java.util.Arrays;
import java.util.Collections;

/** JUnit-free: run the main, a failure throws. Desk lot L4b - the pure parts of a pass narrowed to one rule. */
public final class RuleFocusTest
{
	public static void main(final String[] pArgs)
	{
		RuleFocusTest.theModelIsAskedOnlyWhenItMayNameTheValues();
		RuleFocusTest.onlyTheRuleWithAConditionIsAccepted();
		RuleFocusTest.theModelsNoteIsReducedToItsReason();
		System.out.println("RuleFocusTest OK");
	}

	private static void theModelIsAskedOnlyWhenItMayNameTheValues()
	{
		RuleFocusTest.check("real values shared: asked", RuleFocus.whyNotAsked(QualityLlmPass.Mode.SAMPLE_VALUES, false) == null);
		RuleFocusTest.check("shapes only: values of the other field are not shown, so not asked", RuleFocus.whyNotAsked(QualityLlmPass.Mode.SHAPES_ONLY, false) != null);
		RuleFocusTest.check("policy NONE: not asked", RuleFocus.whyNotAsked(QualityLlmPass.Mode.NONE, false).contains("cannot name a condition"));
		RuleFocusTest.check("a sensitive column is never shown", RuleFocus.whyNotAsked(QualityLlmPass.Mode.SAMPLE_VALUES, true).contains("sensitive"));
	}

	private static void onlyTheRuleWithAConditionIsAccepted()
	{
		final RuleFocus focus = new RuleFocus("a1", "PATTERN", "^[0-9]{5}$");
		RuleFocusTest.check("same rule, with a condition", focus.proposalProblem("PATTERN", "^[0-9]{5}$", "countryCode") == null);
		RuleFocusTest.check("same rule, no condition", focus.proposalProblem("PATTERN", "^[0-9]{5}$", null) != null && focus.proposalProblem("PATTERN", "^[0-9]{5}$", " ") != null);
		RuleFocusTest.check("another argument", focus.proposalProblem("PATTERN", "^[0-9]{4}$", "countryCode") != null);
		RuleFocusTest.check("another kind", focus.proposalProblem("MANDATORY", null, "countryCode") != null);
		final RuleFocus noArgument = new RuleFocus("a2", "REFERENCE", null);
		RuleFocusTest.check("a null argument matches a null argument", noArgument.proposalProblem("REFERENCE", null, "x") == null);
	}

	private static void theModelsNoteIsReducedToItsReason()
	{
		RuleFocusTest.check("entity.attribute prefix dropped", "failures are spread over every country".equals(RuleFocus.reasonOf(Arrays.asList("Adresse.postalCode: failures are spread over every country"))));
		RuleFocusTest.check("a free text stays whole", "no condition applies".equals(RuleFocus.reasonOf(Arrays.asList("no condition applies"))));
		RuleFocusTest.check("the early-stop line is not a reason", RuleFocus.reasonOf(Arrays.asList("Run stopped early: refused 3 times")) == null);
		RuleFocusTest.check("no note, no reason", RuleFocus.reasonOf(Collections.<String>emptyList()) == null && RuleFocus.reasonOf(null) == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
