package com.on.ebx.factory.master;

import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JUnit-free (same convention as every other test in this package): run the main, a failure throws. Fake
 * "field exists" predicates only - no {@code Adaptation}/{@code AdaptationTable} (this codebase never fakes
 * those, see {@link MasterFieldFilter}'s own class Javadoc).
 */
public final class MasterFieldFilterTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MasterFieldFilterTest.everyFieldExists_nothingDropped_noNotes();
		MasterFieldFilterTest.missingField_dropped_noteExplainsRunMasterGenerate();
		MasterFieldFilterTest.validFieldNaming_noteMentionsValidDerivedField();
		MasterFieldFilterTest.technicalFieldsNeverDropped_whenPredicateSaysTheyExist();
		MasterFieldFilterTest.noSurvivorsArray_noop();
		MasterFieldFilterTest.emptySurvivorsArray_noop();
		System.out.println("MasterFieldFilterTest OK");
	}

	/** The exact live shape found (2026-09-23 13:30, `docs/MATCHING.md` &sect;26): {@code /emailValid} accepted in staging, not yet on the master table. */
	private static void everyFieldExists_nothingDropped_noNotes() throws Exception
	{
		final ObjectNode mergePolicy = MasterFieldFilterTest.mergePolicyWithSurvivors("/sourceSystem", "/sourceId", "/supplierId");
		final Predicate<String> everythingExists = pName -> true;
		final List<String> notes = MasterFieldFilter.dropSurvivorsNotOnMaster(mergePolicy, "/root/Contact", everythingExists);
		MasterFieldFilterTest.check("no notes when every field resolves", notes.isEmpty());
		MasterFieldFilterTest.check("all three survivors kept", mergePolicy.path("survivors").size() == 3);
	}

	private static void missingField_dropped_noteExplainsRunMasterGenerate() throws Exception
	{
		final ObjectNode mergePolicy = MasterFieldFilterTest.mergePolicyWithSurvivors("/sourceSystem", "/emailValid");
		final Predicate<String> onlySourceSystemExists = pName -> "sourceSystem".equals(pName);
		final List<String> notes = MasterFieldFilter.dropSurvivorsNotOnMaster(mergePolicy, "/root/Contact", onlySourceSystemExists);
		MasterFieldFilterTest.check("exactly one note for the missing field", notes.size() == 1);
		MasterFieldFilterTest.check("note names the field", notes.get(0).contains("emailValid"));
		MasterFieldFilterTest.check("note names the table", notes.get(0).contains("/root/Contact"));
		MasterFieldFilterTest.check("note says to run master/generate", notes.get(0).contains("master/generate"));
		MasterFieldFilterTest.check("only the surviving field is left", mergePolicy.path("survivors").size() == 1);
		MasterFieldFilterTest.check("the kept survivor is sourceSystem", "/sourceSystem".equals(mergePolicy.path("survivors").get(0).path("field").asText()));
	}

	/** Task brief: "distinguish, if cheap, a *Valid derived field from an LLM/business field" - wording only, never a different check. */
	private static void validFieldNaming_noteMentionsValidDerivedField() throws Exception
	{
		final ObjectNode mergePolicy = MasterFieldFilterTest.mergePolicyWithSurvivors("/emailValid", "/vatNo");
		final Predicate<String> nothingExists = pName -> false;
		final List<String> notes = MasterFieldFilter.dropSurvivorsNotOnMaster(mergePolicy, "/root/Contact", nothingExists);
		MasterFieldFilterTest.check("two notes, one per missing field", notes.size() == 2);
		final String emailNote = notes.stream().filter(pNote -> pNote.startsWith("emailValid")).findFirst().orElse(null);
		final String vatNote = notes.stream().filter(pNote -> pNote.startsWith("vatNo")).findFirst().orElse(null);
		MasterFieldFilterTest.check("emailValid note found", emailNote != null);
		MasterFieldFilterTest.check("vatNo note found", vatNote != null);
		MasterFieldFilterTest.check("emailValid is called out as a *Valid derived field", emailNote.contains("*Valid"));
		MasterFieldFilterTest.check("vatNo is NOT called out as a *Valid derived field (no misleading suffix match)", !vatNote.contains("*Valid"));
		MasterFieldFilterTest.check("every survivor dropped, none left", mergePolicy.path("survivors").size() == 0);
	}

	private static void technicalFieldsNeverDropped_whenPredicateSaysTheyExist() throws Exception
	{
		final ObjectNode mergePolicy = MasterFieldFilterTest.mergePolicyWithSurvivors("/sourceSystem", "/sourceId", "/_loadId", "/_receivedAt");
		final Predicate<String> technicalFieldsExist = pName -> pName.equals("sourceSystem") || pName.equals("sourceId") || pName.equals("_loadId")
			|| pName.equals("_receivedAt");
		final List<String> notes = MasterFieldFilter.dropSurvivorsNotOnMaster(mergePolicy, "/root/Contact", technicalFieldsExist);
		MasterFieldFilterTest.check("the four fixed technical fields (always on the master table) are never dropped", notes.isEmpty());
		MasterFieldFilterTest.check("all four kept", mergePolicy.path("survivors").size() == 4);
	}

	private static void noSurvivorsArray_noop() throws Exception
	{
		final ObjectNode mergePolicy = MasterFieldFilterTest.MAPPER.createObjectNode();
		mergePolicy.put("code", "Contact");
		final List<String> notes = MasterFieldFilter.dropSurvivorsNotOnMaster(mergePolicy, "/root/Contact", pName -> false);
		MasterFieldFilterTest.check("no survivors array at all: no notes, never throws", notes.isEmpty());
	}

	private static void emptySurvivorsArray_noop() throws Exception
	{
		final ObjectNode mergePolicy = MasterFieldFilterTest.MAPPER.createObjectNode();
		mergePolicy.putArray("survivors");
		final List<String> notes = MasterFieldFilter.dropSurvivorsNotOnMaster(mergePolicy, "/root/Contact", pName -> false);
		MasterFieldFilterTest.check("empty survivors array: no notes, never throws", notes.isEmpty());
	}

	private static ObjectNode mergePolicyWithSurvivors(final String... pFieldPaths)
	{
		final ObjectNode mergePolicy = MasterFieldFilterTest.MAPPER.createObjectNode();
		mergePolicy.put("code", "Contact");
		final com.fasterxml.jackson.databind.node.ArrayNode survivors = mergePolicy.putArray("survivors");
		for (final String fieldPath : pFieldPaths)
		{
			final ObjectNode survivor = survivors.addObject();
			survivor.put("field", fieldPath);
			survivor.putArray("mergeFunctionChain").addObject().put("function", "no merge");
		}
		return mergePolicy;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
