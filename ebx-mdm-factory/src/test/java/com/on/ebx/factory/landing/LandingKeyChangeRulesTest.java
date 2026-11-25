package com.on.ebx.factory.landing;

import java.util.Arrays;
import java.util.List;

/** JUnit-free: run the main, a failure throws. */
public final class LandingKeyChangeRulesTest
{
	public static void main(final String[] pArgs)
	{
		final List<String> tableColumns = Arrays.asList("A_ID", "B_ID", "NAME");

		final LandingKeyChangeRules.Validation single = LandingKeyChangeRules.validate(tableColumns, Arrays.asList("A_ID"));
		LandingKeyChangeRulesTest.check("a single real column: accepted", single.ok && single.columns.equals(Arrays.asList("A_ID")));

		final LandingKeyChangeRules.Validation composite = LandingKeyChangeRules.validate(tableColumns, Arrays.asList("A_ID", "B_ID"));
		LandingKeyChangeRulesTest.check("two real columns: accepted, order kept", composite.ok && composite.columns.equals(Arrays.asList("A_ID", "B_ID")));

		final LandingKeyChangeRules.Validation blank = LandingKeyChangeRules.validate(tableColumns, Arrays.asList(" A_ID ", "B_ID", "A_ID", ""));
		LandingKeyChangeRulesTest.check("blank entries dropped, duplicates collapsed, values trimmed", blank.ok && blank.columns.equals(Arrays.asList("A_ID", "B_ID")));

		final LandingKeyChangeRules.Validation empty = LandingKeyChangeRules.validate(tableColumns, java.util.Collections.<String> emptyList());
		LandingKeyChangeRulesTest.check("an empty list is refused", !empty.ok && empty.message != null && empty.columns.isEmpty());

		final LandingKeyChangeRules.Validation onlyBlanks = LandingKeyChangeRules.validate(tableColumns, Arrays.asList(" ", "", "   "));
		LandingKeyChangeRulesTest.check("a list of nothing but blanks is refused, same as an empty list", !onlyBlanks.ok);

		final LandingKeyChangeRules.Validation nullList = LandingKeyChangeRules.validate(tableColumns, null);
		LandingKeyChangeRulesTest.check("a null list is refused, not an exception", !nullList.ok);

		final LandingKeyChangeRules.Validation unknown = LandingKeyChangeRules.validate(tableColumns, Arrays.asList("A_ID", "NOPE"));
		LandingKeyChangeRulesTest.check("a column not on the table is refused, naming it: " + unknown.message, !unknown.ok && unknown.message.contains("NOPE"));

		final LandingKeyChangeRules.Validation caseSensitive = LandingKeyChangeRules.validate(tableColumns, Arrays.asList("a_id"));
		LandingKeyChangeRulesTest.check("column matching is case-sensitive, same convention as WizardData.setTableKey", !caseSensitive.ok);

		System.out.println("LandingKeyChangeRulesTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
