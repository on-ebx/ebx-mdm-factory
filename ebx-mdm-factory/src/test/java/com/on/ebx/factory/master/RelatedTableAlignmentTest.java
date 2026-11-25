package com.on.ebx.factory.master;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * JUnit-free: run the main, a failure throws (same convention as every other test in this codebase - see
 * {@code ebx.MatchingConfiguratorTest}'s own class Javadoc).
 * <p>
 * Covers {@link RelatedTableAlignment} in isolation - {@link MatchingPolicyComparatorTest}/{@code
 * MatchingConfigRewriteBuilderTest} cover it again at the point each real caller actually uses it, but this
 * class is the direct, exhaustive proof of {@link RelatedTableAlignment#align}/{@link
 * RelatedTableAlignment#alignedPaths}/{@link RelatedTableAlignment#changesAnything} on their own.
 */
public final class RelatedTableAlignmentTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		RelatedTableAlignmentTest.align_childEntity_setToWanted();
		RelatedTableAlignmentTest.align_foreignTable_staysManual();
		RelatedTableAlignmentTest.align_noneAndNotAChild_staysNone();
		RelatedTableAlignmentTest.align_emptyStoredList_emptyResult();
		RelatedTableAlignmentTest.align_nullOrNonArrayStored_emptyResult();
		RelatedTableAlignmentTest.align_blankOrMissingPath_neverMatchesNeverThrows();
		RelatedTableAlignmentTest.align_nullChildTablePaths_treatedAsEmpty();
		RelatedTableAlignmentTest.align_neverAddsOrRemovesEntries();
		RelatedTableAlignmentTest.align_preservesEntryOrderAndExtraKeys();
		RelatedTableAlignmentTest.alignedPaths_onlyListsEntriesThatActuallyFlip();
		RelatedTableAlignmentTest.changesAnything_trueOnlyWhenSomethingWouldFlip();
		RelatedTableAlignmentTest.mismatches_keepsTheValueFound();
		System.out.println("RelatedTableAlignmentTest OK");
	}

	private static void align_childEntity_setToWanted() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\"}]");
		final ArrayNode aligned = RelatedTableAlignment.align(stored, Collections.singleton("/root/Contact"));
		RelatedTableAlignmentTest.check("a promoted child's own entry is set to NONE (lot MT: RelatedTableAlignment.WANTED)",
			"NONE".equals(aligned.get(0).path("alignForeignKeys").asText()) && RelatedTableAlignment.WANTED.equals("NONE"));
		final JsonNode storedAutomatic = RelatedTableAlignmentTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"AUTOMATIC\"}]");
		RelatedTableAlignmentTest.check("a stored AUTOMATIC on a promoted child is rewritten to NONE too (existing configurations)",
			"NONE".equals(RelatedTableAlignment.align(storedAutomatic, Collections.singleton("/root/Contact")).get(0).path("alignForeignKeys").asText()));
	}

	private static void align_foreignTable_staysManual() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest.parse("[{\"name\":\"Roles\",\"path\":\"/root/Roles\",\"alignForeignKeys\":\"MANUAL\"}]");
		final ArrayNode aligned = RelatedTableAlignment.align(stored, Collections.singleton("/root/Contact"));
		RelatedTableAlignmentTest.check("a table belonging to another model/project (not a known child) stays MANUAL",
			"MANUAL".equals(aligned.get(0).path("alignForeignKeys").asText()));
	}

	private static void align_noneAndNotAChild_staysNone() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest.parse("[{\"name\":\"Address\",\"path\":\"/root/Address\",\"alignForeignKeys\":\"NONE\"}]");
		final ArrayNode aligned = RelatedTableAlignment.align(stored, Collections.<String>emptySet());
		RelatedTableAlignmentTest.check("NONE, and not a child of this entity: stays NONE, never touched",
			"NONE".equals(aligned.get(0).path("alignForeignKeys").asText()));
	}

	private static void align_emptyStoredList_emptyResult() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest.parse("[]");
		final ArrayNode aligned = RelatedTableAlignment.align(stored, Collections.singleton("/root/Contact"));
		RelatedTableAlignmentTest.check("an empty stored relatedTables[] stays empty - nothing is ever added", aligned.size() == 0);
	}

	private static void align_nullOrNonArrayStored_emptyResult() throws Exception
	{
		RelatedTableAlignmentTest.check("null stored: empty result, never throws", RelatedTableAlignment.align(null, Collections.singleton("/root/Contact")).size() == 0);
		final JsonNode notAnArray = RelatedTableAlignmentTest.parse("{\"not\":\"an array\"}");
		RelatedTableAlignmentTest.check("a non-array stored node: empty result, never throws",
			RelatedTableAlignment.align(notAnArray, Collections.singleton("/root/Contact")).size() == 0);
	}

	private static void align_blankOrMissingPath_neverMatchesNeverThrows() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest.parse("[{\"name\":\"NoPathAtAll\"},{\"name\":\"BlankPath\",\"path\":\"   \"}]");
		final ArrayNode aligned = RelatedTableAlignment.align(stored, Collections.singleton("/root/Contact"));
		RelatedTableAlignmentTest.check("both entries copied through unchanged, never throwing on a missing/blank path", aligned.size() == 2);
		RelatedTableAlignmentTest.check("no alignForeignKeys was ever added to the entry with no path at all",
			aligned.get(0).path("alignForeignKeys").isMissingNode());
		RelatedTableAlignmentTest.check("a blank path never matches a real child path", "   ".equals(aligned.get(1).path("path").asText()));
	}

	private static void align_nullChildTablePaths_treatedAsEmpty() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\"}]");
		final ArrayNode aligned = RelatedTableAlignment.align(stored, null);
		RelatedTableAlignmentTest.check("a null child-table-paths set is treated as empty, never throws, nothing flips",
			"MANUAL".equals(aligned.get(0).path("alignForeignKeys").asText()));
	}

	private static void align_neverAddsOrRemovesEntries() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest
			.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\"}," + "{\"name\":\"Roles\",\"path\":\"/root/Roles\",\"alignForeignKeys\":\"MANUAL\"}]");
		// a child path the stored list has never heard of - align() must never invent a new entry for it.
		final Set<String> childTablePaths = new LinkedHashSet<String>();
		childTablePaths.add("/root/Contact");
		childTablePaths.add("/root/NeverStored");
		final ArrayNode aligned = RelatedTableAlignment.align(stored, childTablePaths);
		RelatedTableAlignmentTest.check("exactly the same number of entries as stored - the add-on owns list membership, this class never does",
			aligned.size() == 2);
	}

	private static void align_preservesEntryOrderAndExtraKeys() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest.parse("[{\"name\":\"Roles\",\"path\":\"/root/Roles\",\"alignForeignKeys\":\"MANUAL\",\"extra\":1},"
			+ "{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\",\"extra\":2}]");
		final ArrayNode aligned = RelatedTableAlignment.align(stored, Collections.singleton("/root/Contact"));
		RelatedTableAlignmentTest.check("order preserved: Roles first, Contact second", "Roles".equals(aligned.get(0).path("name").asText())
			&& "Contact".equals(aligned.get(1).path("name").asText()));
		RelatedTableAlignmentTest.check("an unrelated key on each entry survives verbatim",
			aligned.get(0).path("extra").asInt() == 1 && aligned.get(1).path("extra").asInt() == 2);
	}

	private static void alignedPaths_onlyListsEntriesThatActuallyFlip() throws Exception
	{
		final JsonNode stored = RelatedTableAlignmentTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\"},"
			+ "{\"name\":\"AlreadyNone\",\"path\":\"/root/AlreadyNone\",\"alignForeignKeys\":\"NONE\"},"
			+ "{\"name\":\"WasAuto\",\"path\":\"/root/WasAuto\",\"alignForeignKeys\":\"AUTOMATIC\"},"
			+ "{\"name\":\"Roles\",\"path\":\"/root/Roles\",\"alignForeignKeys\":\"MANUAL\"}]");
		final Set<String> childTablePaths = new LinkedHashSet<String>();
		childTablePaths.add("/root/Contact");
		childTablePaths.add("/root/AlreadyNone");
		childTablePaths.add("/root/WasAuto");
		final List<String> paths = RelatedTableAlignment.alignedPaths(stored, childTablePaths);
		RelatedTableAlignmentTest.check("only the TWO entries that actually need to flip are listed (MANUAL and AUTOMATIC children)", paths.size() == 2);
		RelatedTableAlignmentTest.check("they are Contact and WasAuto, not the already-NONE AlreadyNone nor the non-child Roles",
			"/root/Contact".equals(paths.get(0)) && "/root/WasAuto".equals(paths.get(1)));
	}

	private static void changesAnything_trueOnlyWhenSomethingWouldFlip() throws Exception
	{
		final JsonNode allManual = RelatedTableAlignmentTest.parse("[{\"name\":\"Roles\",\"path\":\"/root/Roles\",\"alignForeignKeys\":\"MANUAL\"}]");
		RelatedTableAlignmentTest.check("no promoted child among the stored entries: nothing changes",
			!RelatedTableAlignment.changesAnything(allManual, Collections.<String>emptySet()));

		final JsonNode withChild = RelatedTableAlignmentTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"MANUAL\"}]");
		RelatedTableAlignmentTest.check("a MANUAL child entry: something would change", RelatedTableAlignment.changesAnything(withChild, Collections.singleton("/root/Contact")));

		final JsonNode alreadyNone = RelatedTableAlignmentTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"NONE\"}]");
		RelatedTableAlignmentTest.check("an already-NONE child entry: nothing left to change",
			!RelatedTableAlignment.changesAnything(alreadyNone, Collections.singleton("/root/Contact")));
		final JsonNode automaticChild = RelatedTableAlignmentTest.parse("[{\"name\":\"Contact\",\"path\":\"/root/Contact\",\"alignForeignKeys\":\"AUTOMATIC\"}]");
		RelatedTableAlignmentTest.check("an AUTOMATIC child entry (older configuration): it must change to NONE",
			RelatedTableAlignment.changesAnything(automaticChild, Collections.singleton("/root/Contact")));
	}

	/** Lot MT fix (2026-10-04): what a configure reads back after its writes (Vol100k: an entry the add-on re-added as MANUAL). */
	private static void mismatches_keepsTheValueFound() throws Exception
	{
		final Set<String> child = Collections.singleton("/root/Adresse");
		RelatedTableAlignmentTest.check("stored list empty: nothing to align, nothing is invented (the add-on owns membership)",
			RelatedTableAlignment.mismatches(RelatedTableAlignmentTest.parse("[]"), child).isEmpty()
				&& RelatedTableAlignment.mismatches(null, child).isEmpty());
		final Map<String, String> manual = RelatedTableAlignment.mismatches(
			RelatedTableAlignmentTest.parse("[{\"name\":\"Address\",\"path\":\"/root/Adresse\",\"alignForeignKeys\":\"MANUAL\"}]"), child);
		RelatedTableAlignmentTest.check("a MANUAL promoted child is reported with the value found", manual.size() == 1 && "MANUAL".equals(manual.get("/root/Adresse")));
		RelatedTableAlignmentTest.check("an AUTOMATIC one too",
			"AUTOMATIC".equals(RelatedTableAlignment.mismatches(
				RelatedTableAlignmentTest.parse("[{\"name\":\"Address\",\"path\":\"/root/Adresse\",\"alignForeignKeys\":\"AUTOMATIC\"}]"), child).get("/root/Adresse")));
		RelatedTableAlignmentTest.check("an entry with no value is reported as <missing>",
			"<missing>".equals(RelatedTableAlignment.mismatches(RelatedTableAlignmentTest.parse("[{\"name\":\"Address\",\"path\":\"/root/Adresse\"}]"), child).get("/root/Adresse")));
		RelatedTableAlignmentTest.check("already NONE: unchanged, not reported",
			RelatedTableAlignment.mismatches(
				RelatedTableAlignmentTest.parse("[{\"name\":\"Address\",\"path\":\"/root/Adresse\",\"alignForeignKeys\":\"NONE\"}]"), child).isEmpty());
		final Map<String, String> mixed = RelatedTableAlignment.mismatches(RelatedTableAlignmentTest.parse(
			"[{\"name\":\"Roles\",\"path\":\"/root/Roles\",\"alignForeignKeys\":\"MANUAL\"},{\"name\":\"Address\",\"path\":\"/root/Adresse\",\"alignForeignKeys\":\"MANUAL\"}]"),
			child);
		RelatedTableAlignmentTest.check("a non-child entry (Roles) is never reported, so never touched", mixed.size() == 1 && mixed.containsKey("/root/Adresse"));
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return RelatedTableAlignmentTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
