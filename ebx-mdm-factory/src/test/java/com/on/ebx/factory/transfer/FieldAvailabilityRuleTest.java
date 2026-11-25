package com.on.ebx.factory.transfer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class FieldAvailabilityRuleTest
{
	public static void main(final String[] pArgs)
	{
		FieldAvailabilityRuleTest.everyCandidatePresentIsAllWritable();
		FieldAvailabilityRuleTest.aCandidateNotYetOnTheTableIsMissing();
		FieldAvailabilityRuleTest.mixOfWritableAndMissing();
		FieldAvailabilityRuleTest.nullExistingSetMeansEverythingMissing();
		FieldAvailabilityRuleTest.nullCandidatesIsEmptyBothWays();
		System.out.println("FieldAvailabilityRuleTest OK");
	}

	private static void everyCandidatePresentIsAllWritable()
	{
		final Set<String> existing = new HashSet<String>(Arrays.asList("name", "country"));
		final FieldAvailabilityRule.Result result = FieldAvailabilityRule.classify(Arrays.asList("name", "country"), existing);
		FieldAvailabilityRuleTest.check("both writable", result.writable.contains("name") && result.writable.contains("country"));
		FieldAvailabilityRuleTest.check("nothing missing", result.missing.isEmpty());
	}

	/** The coordinator's exact scenario: an attribute ACCEPTED in the review, staging/generate not re-run yet - the field is real to the plan but not to the live schema. */
	private static void aCandidateNotYetOnTheTableIsMissing()
	{
		final Set<String> existing = new HashSet<String>(Arrays.asList("name"));
		final FieldAvailabilityRule.Result result = FieldAvailabilityRule.classify(Arrays.asList("name", "lei"), existing);
		FieldAvailabilityRuleTest.check("name is writable", result.writable.contains("name"));
		FieldAvailabilityRuleTest.check("lei (accepted, not yet generated) is missing", result.missing.contains("lei"));
		FieldAvailabilityRuleTest.check("lei is not also writable", !result.writable.contains("lei"));
	}

	private static void mixOfWritableAndMissing()
	{
		final Set<String> existing = new HashSet<String>(Arrays.asList("legalForm", "legalFormRef"));
		final FieldAvailabilityRule.Result result = FieldAvailabilityRule.classify(Arrays.asList("legalForm", "legalFormRef", "vatNo", "registrationNo"), existing);
		FieldAvailabilityRuleTest.check("2 writable", result.writable.size() == 2);
		FieldAvailabilityRuleTest.check("2 missing", result.missing.size() == 2);
		FieldAvailabilityRuleTest.check("vatNo and registrationNo are the missing ones", result.missing.contains("vatNo") && result.missing.contains("registrationNo"));
	}

	private static void nullExistingSetMeansEverythingMissing()
	{
		final FieldAvailabilityRule.Result result = FieldAvailabilityRule.classify(Arrays.asList("a", "b"), null);
		FieldAvailabilityRuleTest.check("nothing writable with no confirmed existing set", result.writable.isEmpty());
		FieldAvailabilityRuleTest.check("both missing", result.missing.size() == 2);
	}

	private static void nullCandidatesIsEmptyBothWays()
	{
		final FieldAvailabilityRule.Result result = FieldAvailabilityRule.classify(null, Collections.<String>emptySet());
		FieldAvailabilityRuleTest.check("no candidates: nothing writable", result.writable.isEmpty());
		FieldAvailabilityRuleTest.check("no candidates: nothing missing either", result.missing.isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
