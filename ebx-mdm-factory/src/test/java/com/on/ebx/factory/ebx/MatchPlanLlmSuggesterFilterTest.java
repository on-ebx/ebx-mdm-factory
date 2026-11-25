package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JUnit-free: run the main, a failure throws. Covers {@link MatchPlanLlmSuggester#filterToEntity} - the pure
 * decision behind the {@code ?entity=} query parameter of {@code POST /projects/{code}/matching/suggest-llm}:
 * a blank filter is the ordinary full sweep, unchanged; a named promoted entity (child or not - 2026-09-22,
 * "child entities matched too") narrows to just that one; an unknown name is refused with a message naming it.
 * <p>
 * Simplified 2026-09-22: this method used to take a second {@code Map<String,String>} argument (child entity
 * -&gt; parent name) and give a distinct "it's a child entity" refusal - removed along with the eligibility
 * exclusion itself, since a child entity is now a normal promoted entity, exactly as selectable by name as any
 * other. Same package as {@link MatchPlanLlmSuggester} on purpose, to reach this package-private static
 * directly - the same convention {@code MatchingConfiguratorTest} already follows.
 */
public final class MatchPlanLlmSuggesterFilterTest
{
	public static void main(final String[] pArgs)
	{
		MatchPlanLlmSuggesterFilterTest.blankFilterKeepsTheWholeSweepUnchanged();
		MatchPlanLlmSuggesterFilterTest.nullFilterKeepsTheWholeSweepUnchanged();
		MatchPlanLlmSuggesterFilterTest.namedPromotedEntityNarrowsToJustThatOne();
		MatchPlanLlmSuggesterFilterTest.childEntityIsSelectableLikeAnyOther();
		MatchPlanLlmSuggesterFilterTest.unknownEntityRefused();
		MatchPlanLlmSuggesterFilterTest.filterIsTrimmed();
		System.out.println("MatchPlanLlmSuggesterFilterTest OK");
	}

	/** "Address" is included here to stand in for a child entity - 2026-09-22, a child is simply one more name in the promoted list, nothing distinguishes it at this layer any more. */
	private static List<String> promoted()
	{
		return new ArrayList<String>(Arrays.asList("Company", "Contact", "Address"));
	}

	private static void blankFilterKeepsTheWholeSweepUnchanged()
	{
		final MatchPlanLlmSuggester.EntityFilterResult result = MatchPlanLlmSuggester.filterToEntity(MatchPlanLlmSuggesterFilterTest.promoted(), "");
		MatchPlanLlmSuggesterFilterTest.check("no refusal", result.refusalReason == null);
		MatchPlanLlmSuggesterFilterTest.check("every promoted entity kept, in order",
			result.entities.equals(Arrays.asList("Company", "Contact", "Address")));
	}

	private static void nullFilterKeepsTheWholeSweepUnchanged()
	{
		final MatchPlanLlmSuggester.EntityFilterResult result = MatchPlanLlmSuggester.filterToEntity(MatchPlanLlmSuggesterFilterTest.promoted(), null);
		MatchPlanLlmSuggesterFilterTest.check("no refusal", result.refusalReason == null);
		MatchPlanLlmSuggesterFilterTest.check("every promoted entity kept", result.entities.equals(Arrays.asList("Company", "Contact", "Address")));
	}

	private static void namedPromotedEntityNarrowsToJustThatOne()
	{
		final MatchPlanLlmSuggester.EntityFilterResult result = MatchPlanLlmSuggester.filterToEntity(MatchPlanLlmSuggesterFilterTest.promoted(), "Company");
		MatchPlanLlmSuggesterFilterTest.check("no refusal", result.refusalReason == null);
		MatchPlanLlmSuggesterFilterTest.check("narrowed to exactly Company", result.entities.equals(Arrays.asList("Company")));
	}

	/** 2026-09-22: "Address" (standing in for a child entity here) is exactly as selectable as any other promoted name - no special "it's a child" refusal any more. */
	private static void childEntityIsSelectableLikeAnyOther()
	{
		final MatchPlanLlmSuggester.EntityFilterResult result = MatchPlanLlmSuggester.filterToEntity(MatchPlanLlmSuggesterFilterTest.promoted(), "Address");
		MatchPlanLlmSuggesterFilterTest.check("no refusal", result.refusalReason == null);
		MatchPlanLlmSuggesterFilterTest.check("narrowed to exactly Address", result.entities.equals(Arrays.asList("Address")));
	}

	private static void unknownEntityRefused()
	{
		final MatchPlanLlmSuggester.EntityFilterResult result = MatchPlanLlmSuggester.filterToEntity(MatchPlanLlmSuggesterFilterTest.promoted(), "NoSuchEntity");
		MatchPlanLlmSuggesterFilterTest.check("no entities on a refusal", result.entities == null);
		MatchPlanLlmSuggesterFilterTest.check("names the unknown entity", result.refusalReason != null && result.refusalReason.contains("'NoSuchEntity'"));
		MatchPlanLlmSuggesterFilterTest.check("carries the {project} placeholder for the caller to substitute", result.refusalReason.contains("{project}"));
	}

	private static void filterIsTrimmed()
	{
		final MatchPlanLlmSuggester.EntityFilterResult result = MatchPlanLlmSuggester.filterToEntity(MatchPlanLlmSuggesterFilterTest.promoted(), "  Company  ");
		MatchPlanLlmSuggesterFilterTest.check("no refusal", result.refusalReason == null);
		MatchPlanLlmSuggesterFilterTest.check("trimmed to Company", result.entities.equals(Arrays.asList("Company")));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
