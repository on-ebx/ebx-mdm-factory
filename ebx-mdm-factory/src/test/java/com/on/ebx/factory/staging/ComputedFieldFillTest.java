package com.on.ebx.factory.staging;

/** JUnit-free: run the main, a failure throws. Fix/staging-proposal-quality (2026-10-17, item 1). */
public final class ComputedFieldFillTest
{
	public static void main(final String[] pArgs)
	{
		ComputedFieldFillTest.reportsOnlyAFieldEmptyOnEveryRow();
		ComputedFieldFillTest.wordsTheCause();
		System.out.println("ComputedFieldFillTest OK");
	}

	private static void reportsOnlyAFieldEmptyOnEveryRow()
	{
		ComputedFieldFillTest.check("394 rows, none filled", ComputedFieldFill.emptyEverywhere(394L, 0L));
		ComputedFieldFillTest.check("one filled row is enough", !ComputedFieldFill.emptyEverywhere(394L, 1L));
		ComputedFieldFillTest.check("an empty table says nothing", !ComputedFieldFill.emptyEverywhere(0L, 0L));
	}

	private static void wordsTheCause()
	{
		final String notApplied = ComputedFieldFill.report("Customer", "phoneE164", "mobilePhone", 1394L, true);
		ComputedFieldFillTest.check(notApplied, notApplied.equals("Customer.phoneE164: computed field empty on all 1,394 row(s) - its script derivation is not applied and could not be applied by this load"));
		final String other = ComputedFieldFill.report("Customer", "personNameKey", "lastName", 394L, false);
		ComputedFieldFillTest.check(other, other.startsWith("Customer.personNameKey: computed field empty on all 394 row(s) - check that its source lastName is filled")
			&& other.contains("triggers are live"));
		ComputedFieldFillTest.check("unknown source", ComputedFieldFill.report("E", "a", null, 3L, false).contains("its source attribute"));
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
