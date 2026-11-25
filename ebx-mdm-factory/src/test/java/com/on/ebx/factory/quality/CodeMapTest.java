package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws. Codes transcoding lot H3 (2026-10-17): the code table a {@code TRANSCODE_REFERENCE} step may carry instead of naming a
 * configured reference - parsing, matching, coverage, the edit and the step engine that executes it (what the loader runs).
 */
public final class CodeMapTest
{
	public static void main(final String[] pArgs)
	{
		CodeMapTest.roundTripAndForms();
		CodeMapTest.matchingIgnoresCaseAccentsAndPunctuation();
		CodeMapTest.conflictsResolveNothing();
		CodeMapTest.coverageCountsRowsAndDistinctCodes();
		CodeMapTest.theStepEngineRunsTheTable();
		CodeMapTest.anEditReplacesTargetsOfKnownCodesOnly();
		CodeMapTest.codesOfAReferenceAreItsDistinctCodes();
		System.out.println("CodeMapTest OK");
	}

	private static CodeMap loyaltyLevels()
	{
		final Map<String, String> codes = new LinkedHashMap<String, String>();
		codes.put("Or", "GOLD");
		codes.put("Bronze", "BRONZE");
		codes.put("Argent", "SILVER");
		final Map<String, Long> counts = new LinkedHashMap<String, Long>();
		counts.put("Or", 52L);
		counts.put("Bronze", 50L);
		counts.put("Argent", 38L);
		return new CodeMap(codes, Arrays.asList("BRONZE", "SILVER", "GOLD"), counts, "");
	}

	private static void roundTripAndForms()
	{
		final CodeMap map = CodeMapTest.loyaltyLevels();
		final String json = map.toJson();
		check("the argument is a JSON object with codes, targets and counts", json.startsWith("{\"codes\":{") && json.contains("\"targets\":[\"BRONZE\",\"SILVER\",\"GOLD\"]")
			&& json.contains("\"counts\":{\"Or\":52"));
		final CodeMap again = CodeMap.parse(json);
		check("it reads back the same", again != null && again.codes.equals(map.codes) && again.targets.equals(map.targets) && again.counts.equals(map.counts)
			&& again.basedOn.isEmpty());
		check("basedOn travels", CodeMap.parse(new CodeMap(map.codes, map.targets, map.counts, "TIER").toJson()).basedOn.equals("TIER"));
		check("a reference-naming argument is not a table", CodeMap.parseCached("{\"reference\":\"LEGAL_FORM\",\"scopeField\":\"country\"}") == null);
		check("nor blank, malformed or without a codes object", CodeMap.parseCached(null) == null && CodeMap.parseCached("") == null && CodeMap.parseCached("{\"codes\":") == null
			&& CodeMap.parseCached("{\"codes\":[1]}") == null && CodeMap.parseCached("not json \"codes\"") == null);
		check("the cache answers the same object", CodeMap.parseCached(json) == CodeMap.parseCached(json));
		final Map<String, String> accents = new LinkedHashMap<String, String>();
		accents.put("Monsieur \"M\"", "MR\\X");
		check("quotes and backslashes survive", CodeMap.parse(new CodeMap(accents, Arrays.asList("MR\\X"), null, "").toJson()).codes.get("Monsieur \"M\"").equals("MR\\X"));
	}

	private static void matchingIgnoresCaseAccentsAndPunctuation()
	{
		final Map<String, String> codes = new LinkedHashMap<String, String>();
		codes.put("M.", "MR");
		codes.put("Mme", "MRS");
		codes.put("-", "UNKNOWN");
		codes.put("Société", "COMPANY");
		final CodeMap map = new CodeMap(codes, Arrays.asList("MR", "MRS", "UNKNOWN", "COMPANY"), null, "");
		check("M. matches m, M and ' M. '", "MR".equals(map.lookup("m")) && "MR".equals(map.lookup("M")) && "MR".equals(map.lookup(" M. ")));
		check("case: MME", "MRS".equals(map.lookup("MME")));
		check("a code made of punctuation keeps its own characters as its key", "UNKNOWN".equals(map.lookup("-")) && map.lookup("?") == null);
		check("accents ignored", "COMPANY".equals(map.lookup("SOCIETE")));
		check("an unknown code and a blank one resolve to nothing", map.lookup("Mlle") == null && map.lookup("  ") == null && map.lookup(null) == null);
		final Map<String, String> empty = new LinkedHashMap<String, String>();
		empty.put("Mlle", "");
		check("an empty target is not a target", new CodeMap(empty, null, null, "").lookup("Mlle") == null);
	}

	private static void conflictsResolveNothing()
	{
		final Map<String, String> codes = new LinkedHashMap<String, String>();
		codes.put("A-1", "ONE");
		codes.put("A1", "TWO");
		codes.put("B", "B");
		final CodeMap map = new CodeMap(codes, null, null, "");
		check("two spellings with one key and two targets are a conflict", map.conflicts().size() == 1 && map.lookup("A1") == null && map.lookup("A-1") == null);
		check("the others still resolve", "B".equals(map.lookup("b")));
		codes.put("A1", "ONE");
		check("the same target is no conflict", new CodeMap(codes, null, null, "").conflicts().isEmpty() && "ONE".equals(new CodeMap(codes, null, null, "").lookup("a 1")));
	}

	private static void coverageCountsRowsAndDistinctCodes()
	{
		final CodeMap map = CodeMapTest.loyaltyLevels();
		final List<Detectors.ValueCount> values = new ArrayList<Detectors.ValueCount>(Arrays.asList(new Detectors.ValueCount("Or", 10), new Detectors.ValueCount("Bronze", 5),
			new Detectors.ValueCount("Platine", 3), new Detectors.ValueCount("  ", 9), new Detectors.ValueCount("GOLD", 2)));
		final CodeMap.Coverage coverage = map.cover(values);
		check("blank values are not counted", coverage.rows == 20L && coverage.distinct == 4L);
		check("an unlisted code is unresolved, and so is a target written as such (GOLD is not a source code of this table)", coverage.unresolved == 5L && coverage.unresolvedDistinct == 2L
			&& coverage.unresolvedValues.contains("Platine") && !coverage.complete());
		check("the others are resolved and rewritten", coverage.resolved == 15L && coverage.changed == 15L);
		check("percent of rows", coverage.percent() == 75);
		final CodeMap.Coverage fromCounts = map.coverageFromCounts();
		check("from the recorded counts: complete", fromCounts.complete() && fromCounts.rows == 140L && fromCounts.changed == 140L && fromCounts.percent() == 100);
		final Map<String, String> half = new LinkedHashMap<String, String>(map.codes);
		half.put("Argent", "");
		final CodeMap.Coverage partial = new CodeMap(half, map.targets, map.counts, "").coverageFromCounts();
		check("a code with no target leaves its rows unresolved", partial.unresolved == 38L && partial.percent() == 72);
		check("apply replaces the resolved ones and keeps the others", map.apply(values).get(0).value.equals("GOLD") && map.apply(values).get(2).value.equals("Platine"));
		check("isIdentity is about the table, not the coverage", !map.isIdentity() && new CodeMap(CodeMapTest.table("A", "A", "B", "B"), null, null, "").isIdentity());
	}

	private static void theStepEngineRunsTheTable()
	{
		final String argument = CodeMapTest.loyaltyLevels().toJson();
		final CleaningSteps.Result resolved = CleaningSteps.applyStep(CleaningSteps.TRANSCODE_REFERENCE, argument, "  argent ", null, CleaningSteps.Lookups.empty());
		check("the loader's engine maps a code through the step's own table", "SILVER".equals(resolved.value) && resolved.changed && !resolved.unresolved);
		final CleaningSteps.Result unknown = CleaningSteps.applyStep(CleaningSteps.TRANSCODE_REFERENCE, argument, "Platine", null, CleaningSteps.Lookups.empty());
		check("an unlisted code is left as it is and counted unresolved - never invented", "Platine".equals(unknown.value) && unknown.unresolved && !unknown.changed);
		final CleaningSteps.Result blank = CleaningSteps.applyStep(CleaningSteps.TRANSCODE_REFERENCE, argument, " ", null, CleaningSteps.Lookups.empty());
		check("a blank value is passed through", blank.value == null && !blank.unresolved);
		final List<CleaningSteps.Step> chain = Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRIM, null), new CleaningSteps.Step(CleaningSteps.UPPER, null),
			new CleaningSteps.Step(CleaningSteps.TRANSCODE_REFERENCE, argument));
		check("in a pipeline, after the steps before it", "GOLD".equals(CleaningSteps.applyPipeline(chain, " or ", null, CleaningSteps.Lookups.empty()).value));
		check("no scope field is asked of the row", CleaningSteps.scopeFieldOf(chain) == null);
		final CleaningSteps.Result reference = CleaningSteps.applyStep(CleaningSteps.TRANSCODE_REFERENCE, "{\"reference\":\"LEGAL_FORM\"}", "SA", null, CleaningSteps.Lookups.empty());
		check("a reference-naming argument still goes the lookups' way", reference.unresolved && "SA".equals(reference.value));
	}

	private static void anEditReplacesTargetsOfKnownCodesOnly()
	{
		final CodeMap map = CodeMapTest.loyaltyLevels();
		final Map<String, String> edits = new LinkedHashMap<String, String>();
		edits.put("Or", "PLATINUM");
		edits.put("Argent", "");
		edits.put("Unknown", "X");
		final CodeMap edited = map.withTargets(edits);
		check("a target is replaced and joins the list", "PLATINUM".equals(edited.lookup("Or")) && edited.targets.contains("PLATINUM"));
		check("an emptied target leaves the code unmapped", edited.lookup("Argent") == null);
		check("a code that was not in the table is ignored", !edited.codes.containsKey("Unknown"));
		check("the counts stay", edited.counts.equals(map.counts) && map.lookup("Or").equals("GOLD"));
	}

	private static void codesOfAReferenceAreItsDistinctCodes()
	{
		final Map<String, List<String>> rows = new LinkedHashMap<String, List<String>>();
		rows.put("MR", Arrays.asList("Monsieur", "Mister"));
		rows.put("MRS", Arrays.asList("Madame"));
		final Map<String, Map<String, String>> flat = new LinkedHashMap<String, Map<String, String>>();
		flat.put("TITLE", CleaningSteps.buildFlatTable(rows, null));
		final CleaningSteps.Lookups lookups = new CleaningSteps.Lookups(flat, null);
		check("codesOf lists each code once", new ArrayList<String>(lookups.codesOf("TITLE")).equals(Arrays.asList("MR", "MRS")) && lookups.codesOf("NOPE").isEmpty());
	}

	static Map<String, String> table(final String... pPairs)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i + 1 < pPairs.length; i += 2)
		{
			out.put(pPairs[i], pPairs[i + 1]);
		}
		return out;
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("CodeMapTest: " + pLabel);
		}
	}
}
