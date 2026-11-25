package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JUnit-free, like the sibling tests: run the main, a failure throws. Exercises {@link
 * RecordLineageReader#excludingGolden} - the pure isolated-flag/member-count decision split out 2026-09-24
 * (see that method's own Javadoc, "must be measured live" for the underlying fact): {@code
 * Group.getMatchedRecords()} was measured live on Companies_Master/Master/root/Company record 217 to include
 * the group's OWN golden record among its "matched" entries, even when the golden is alone in its group - so
 * a raw {@code matchedRecords.isEmpty()}/{@code size()} can never decide isolation or count members without
 * filtering the golden's own key out first. Plain {@code List<String>} in, plain {@code List<String>} out -
 * no {@code Adaptation}/EBX type anywhere, same "small, directly testable seam" pattern as {@code
 * FieldMergeLineageSource#readFromItems} ({@code FieldMergeLineageSourceTest}).
 */
public final class RecordLineageReaderTest
{
	public static void main(final String[] pArgs)
	{
		RecordLineageReaderTest.goldenAloneInGroup_isolated();
		RecordLineageReaderTest.goldenPlusOneOtherMember_notIsolated();
		RecordLineageReaderTest.goldenAbsentFromList_stillFiltersCorrectly();
		RecordLineageReaderTest.orderPreserved_andGoldenCanAppearAnywhere();
		RecordLineageReaderTest.nullEntryDroppedDefensively();
		RecordLineageReaderTest.contributorsArePaged();
		System.out.println("RecordLineageReaderTest OK");
	}

	/** F2-112: a golden with 200 contributors resolves 50 (golden included in the page), says "N of M", and the next page starts where this one ended. */
	private static void contributorsArePaged()
	{
		final java.util.Set<String> keys = new java.util.LinkedHashSet<String>();
		keys.add("G");
		for (int i = 0; i < 199; i++)
		{
			keys.add("c" + i);
		}
		final java.util.List<String> first = RecordLineageReader.contributorWindow(keys, "G", 0, 50);
		if (first.size() != 51 || !"G".equals(first.get(0)) || !"c49".equals(first.get(50)))
		{
			throw new AssertionError("first page: the golden then 50 contributors, got " + first.size());
		}
		final com.fasterxml.jackson.databind.node.ObjectNode meta = RecordLineageReader.contributorsNode(keys.size(), true, first.size(), 0, 50);
		if (meta.get("total").asInt() != 200 || meta.get("shown").asInt() != 51 || meta.get("nextOffset").asInt() != 50)
		{
			throw new AssertionError("N of M: " + meta);
		}
		final java.util.List<String> last = RecordLineageReader.contributorWindow(keys, "G", 150, 50);
		if (last.size() != 50 || !"c198".equals(last.get(49)) || RecordLineageReader.contributorsNode(200, true, last.size(), 150, 50).has("nextOffset"))
		{
			throw new AssertionError("last page: 49 contributors left, no next link");
		}
		if (RecordLineageReader.contributorWindow(keys, "G", 0, 1000).size() != 200 || RecordLineageReader.maxContributors() != 50)
		{
			throw new AssertionError("a wide window returns everyone; the default size is 50");
		}
	}

	/** The exact shape measured live 2026-09-24 (class Javadoc): record 217 alone in its group, matchedRecords == [itself]. */
	private static void goldenAloneInGroup_isolated()
	{
		final List<String> matchedKeys = Arrays.asList("217");
		final List<String> other = RecordLineageReader.excludingGolden(matchedKeys, "217");
		RecordLineageReaderTest.check("golden filtered out of its own matched list", other.isEmpty());
	}

	/** A real group of two: the golden (322) plus one genuinely other confirmed member (290) - matches goldens 323/324's shape. */
	private static void goldenPlusOneOtherMember_notIsolated()
	{
		final List<String> matchedKeys = Arrays.asList("322", "290");
		final List<String> other = RecordLineageReader.excludingGolden(matchedKeys, "322");
		RecordLineageReaderTest.check("one other member left", other.size() == 1);
		RecordLineageReaderTest.check("the other member is 290, not the golden", "290".equals(other.get(0)));
	}

	/** Defensive: a build where the add-on does NOT include the golden must still work unchanged (filtering an absent key is a no-op). */
	private static void goldenAbsentFromList_stillFiltersCorrectly()
	{
		final List<String> matchedKeys = Arrays.asList("290", "248");
		final List<String> other = RecordLineageReader.excludingGolden(matchedKeys, "322");
		RecordLineageReaderTest.check("nothing filtered when the golden was never in the list", other.size() == 2);
	}

	private static void orderPreserved_andGoldenCanAppearAnywhere()
	{
		final List<String> matchedKeys = Arrays.asList("290", "322", "248");
		final List<String> other = RecordLineageReader.excludingGolden(matchedKeys, "322");
		RecordLineageReaderTest.check("order preserved, golden removed from the middle", Arrays.asList("290", "248").equals(other));
	}

	private static void nullEntryDroppedDefensively()
	{
		final List<String> matchedKeys = Arrays.asList("290", null, "248");
		final List<String> other = RecordLineageReader.excludingGolden(matchedKeys, "322");
		RecordLineageReaderTest.check("null entry dropped, real entries kept", Arrays.asList("290", "248").equals(other));
		RecordLineageReaderTest.check("empty input answers empty output", RecordLineageReader.excludingGolden(Collections.<String>emptyList(), "322").isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
