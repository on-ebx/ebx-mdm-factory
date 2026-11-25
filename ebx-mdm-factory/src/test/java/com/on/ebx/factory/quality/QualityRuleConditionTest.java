package com.on.ebx.factory.quality;

import java.util.Arrays;
import java.util.Collections;

/**
 * Validation desk lot L4: the condition of a quality rule at transfer time - which rows a conditioned rule applies to. The postal code case: {@code ^[0-9]{5}$} only when the
 * country is FR. JUnit-free: run the main, a failure throws.
 */
public final class QualityRuleConditionTest
{
	public static void main(final String[] pArgs)
	{
		QualityRuleConditionTest.aRuleWithoutConditionAlwaysApplies();
		QualityRuleConditionTest.aConditionAppliesToTheListedValuesOnly();
		QualityRuleConditionTest.anIncompleteConditionIsNoCondition();
		QualityRuleConditionTest.theVerdictIsTheSameWithOrWithoutCondition();
		System.out.println("QualityRuleConditionTest OK");
	}

	private static void aRuleWithoutConditionAlwaysApplies()
	{
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("1", QualityDetectors.PATTERN, "^[0-9]{5}$", QualityDetectors.WARN);
		check("no condition", !rule.hasCondition() && rule.conditionField == null && rule.conditionValues.isEmpty());
		check("applies whatever the row holds", rule.appliesTo(null) && rule.appliesTo("") && rule.appliesTo("CH"));
	}

	private static void aConditionAppliesToTheListedValuesOnly()
	{
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("1", QualityDetectors.PATTERN, "^[0-9]{5}$", QualityDetectors.WARN, "countryCode", Arrays.asList("FR"));
		check("a condition", rule.hasCondition() && "countryCode".equals(rule.conditionField));
		check("a French row", rule.appliesTo("FR") && rule.appliesTo(" fr "));
		check("a Swiss row is outside", !rule.appliesTo("CH"));
		check("a row with no country is outside: never flagged by a condition it cannot meet", !rule.appliesTo(null) && !rule.appliesTo("") && !rule.appliesTo("  "));
		check("several values", new QualityRuleCheck.Rule("2", QualityDetectors.PATTERN, "x", QualityDetectors.WARN, "c", Arrays.asList("FR", "DE")).appliesTo("de"));
	}

	private static void anIncompleteConditionIsNoCondition()
	{
		check("a field without values", !new QualityRuleCheck.Rule("1", QualityDetectors.PATTERN, "x", QualityDetectors.WARN, "countryCode", Collections.<String> emptyList()).hasCondition());
		check("values without a field", !new QualityRuleCheck.Rule("1", QualityDetectors.PATTERN, "x", QualityDetectors.WARN, "", Arrays.asList("FR")).hasCondition());
		check("the value check alone", QualityRuleCheck.valueIn("FR", Arrays.asList("fr")) && !QualityRuleCheck.valueIn("FR", null) && !QualityRuleCheck.valueIn(null, Arrays.asList("FR")));
	}

	private static void theVerdictIsTheSameWithOrWithoutCondition()
	{
		final QualityRuleCheck.Rule rule = new QualityRuleCheck.Rule("1", QualityDetectors.PATTERN, "^[0-9]{5}$", QualityDetectors.WARN, "countryCode", Arrays.asList("FR"));
		check("the value is judged as ever", QualityRuleCheck.verdict(rule, "75001", null) == QualityRuleCheck.Verdict.PASS && QualityRuleCheck.verdict(rule, "1201", null) == QualityRuleCheck.Verdict.FAIL);
		check("a conditioned WARN rule is not a reject", !rule.isRejectSeverity());
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
