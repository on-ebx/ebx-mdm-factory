package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JUnit-free: run the main, a failure throws. Lot M16 ({@code docs/MATCHING.md} &sect;68): the candidate coverage of a simulation - for each exact key of a plan, how many records sharing its value with a
 * pivot were never among the candidates the add-on evaluated - as numbers, as numbered statements with their cause, and as the JSON stored with the plan. The figures of the live case are used
 * (ECOM pivots, STORES tickets without an e-mail that share the phone).
 */
public final class CandidateCoverageTest
{
	public static void main(final String[] pArgs)
	{
		CandidateCoverageTest.countsWhatWasNeverACandidate();
		CandidateCoverageTest.aCandidateOfThePivotIsCountedWhateverTheOtherSideSaw();
		CandidateCoverageTest.statementsCarryNumbersSystemsAndCause();
		CandidateCoverageTest.aKeyWithoutGapSaysSo();
		CandidateCoverageTest.theStoredFactsRoundTripAndLeaveThePlanAlone();
		System.out.println("CandidateCoverageTest OK");
	}

	private static List<String[]> stores(final int pFirst, final int pCount)
	{
		final List<String[]> out = new ArrayList<String[]>();
		for (int i = 0; i < pCount; i++)
		{
			out.add(new String[] { "S" + (pFirst + i), "STORES" });
		}
		return out;
	}

	private static Set<String> set(final String... pIds)
	{
		return new HashSet<String>(Arrays.asList(pIds));
	}

	/** Three ECOM pivots share the phone with 4, 3 and 2 tickets; the engine evaluated only some of them. */
	private static void countsWhatWasNeverACandidate()
	{
		final List<CandidateCoverage.Observation> observations = new ArrayList<CandidateCoverage.Observation>();
		observations.add(new CandidateCoverage.Observation("mobileE164", "E1", "ECOM", CandidateCoverageTest.stores(1, 4)));
		observations.add(new CandidateCoverage.Observation("mobileE164", "E2", "ECOM", CandidateCoverageTest.stores(5, 3)));
		observations.add(new CandidateCoverage.Observation("mobileE164", "E3", "ECOM", CandidateCoverageTest.stores(8, 2)));
		observations.add(new CandidateCoverage.Observation("emailKey", "E1", "ECOM", Collections.singletonList(new String[] { "L1", "LOYALTY" })));
		final Map<String, Set<String>> candidates = new LinkedHashMap<String, Set<String>>();
		candidates.put("E1", CandidateCoverageTest.set("L1", "S1"));
		candidates.put("E2", CandidateCoverageTest.set("S5", "S6", "S7"));
		candidates.put("E3", Collections.<String>emptySet());
		final CandidateCoverage.Result result = CandidateCoverage.measure(observations, candidates, 18);
		final CandidateCoverage.KeyCoverage phone = result.key("mobileE164");
		check("9 records share the phone with a pivot", phone != null && phone.sharing == 9 && phone.pivots == 3);
		check("5 of them were never candidates: E1 saw 1 of 4 (3 missing), E2 all 3, E3 none of 2 (2 missing)", phone.missing == 5);
		check("by system and by pivot system", phone.missingBySystem.get("STORES").intValue() == 5 && phone.missingByPivotSystem.get("ECOM").intValue() == 5);
		check("the e-mail has no gap", result.key("emailKey").missing == 0 && result.key("emailKey").sharing == 1);
		check("a gap exists", result.hasGap());
		check("18 pivots in all", result.pivotCount == 18);
	}

	/** A pair seen from the other side is still a candidate of THIS pivot only if the engine returned it for this pivot: the sets are per pivot. */
	private static void aCandidateOfThePivotIsCountedWhateverTheOtherSideSaw()
	{
		final List<CandidateCoverage.Observation> observations = Arrays.asList(
			new CandidateCoverage.Observation("k", "A", "X", Collections.singletonList(new String[] { "B", "Y" })), new CandidateCoverage.Observation("k", "B", "Y", Collections.singletonList(new String[] { "A", "X" })));
		final Map<String, Set<String>> candidates = new LinkedHashMap<String, Set<String>>();
		candidates.put("A", CandidateCoverageTest.set("B"));
		candidates.put("B", Collections.<String>emptySet());
		final CandidateCoverage.KeyCoverage key = CandidateCoverage.measure(observations, candidates, 2).key("k");
		check("A saw B, B did not see A: one gap, for the pivot B", key.sharing == 2 && key.missing == 1 && key.missingByPivotSystem.get("Y").intValue() == 1 && key.missingBySystem.get("X").intValue() == 1);
		check("a pivot the engine returned nothing for counts as having no candidate", CandidateCoverage.measure(observations, Collections.<String, Set<String>>emptyMap(), 2).key("k").missing == 2);
	}

	private static void statementsCarryNumbersSystemsAndCause()
	{
		final List<CandidateCoverage.Observation> observations = Arrays.asList(new CandidateCoverage.Observation("mobileE164", "E1", "ECOM", CandidateCoverageTest.stores(1, 4)));
		final CandidateCoverage.Result result = CandidateCoverage.measure(observations, Collections.<String, Set<String>>emptyMap(), 18);
		final List<String> facts = result.facts("the add-on keeps a candidate only above 30% of the pivot's weight (total 7); [mobileE164 + personNameKey] holds 28.6 %");
		check("one statement", facts.size() == 1);
		check("the numbers: " + facts.get(0), facts.get(0).startsWith("simulation: 18 pivot(s); 4 of 4 record(s) sharing mobileE164 with a pivot were never candidates (4 in STORES), for pivots of ECOM 4."));
		check("the cause follows: " + facts.get(0), facts.get(0).contains(" Cause: the add-on keeps a candidate only above 30%") && facts.get(0).endsWith("28.6 %"));
		check("without a cause no 'Cause' sentence", !result.facts(null).get(0).contains("Cause"));
	}

	private static void aKeyWithoutGapSaysSo()
	{
		final List<CandidateCoverage.Observation> observations = Arrays.asList(new CandidateCoverage.Observation("emailKey", "E1", "ECOM", Collections.singletonList(new String[] { "L1", "LOYALTY" })));
		final Map<String, Set<String>> candidates = Collections.singletonMap("E1", CandidateCoverageTest.set("L1"));
		final List<String> facts = CandidateCoverage.measure(observations, candidates, 18).facts("ignored when nothing is missing");
		check("what worked is told too: " + facts, facts.size() == 1 && facts.get(0).equals("simulation: 18 pivot(s); all 1 record(s) sharing emailKey with a pivot were among the candidates the add-on evaluated."));
		check("a key nobody shares is not mentioned", CandidateCoverage.measure(Arrays.asList(new CandidateCoverage.Observation("k", "A", "X", new ArrayList<String[]>())), candidates, 1).keys.isEmpty());
		check("the JSON form carries the figures", CandidateCoverage.measure(observations, candidates, 18).toJson().path("keys").get(0).path("sharing").asInt() == 1);
	}

	private static void theStoredFactsRoundTripAndLeaveThePlanAlone()
	{
		final String plan = "{\"decisionTree\":{\"root\":\"N1\"},\"clusteringFields\":[\"a\"],\"clusteringWeights\":{\"a\":3}}";
		final List<String> facts = Arrays.asList("simulation: 1", "simulation: 2");
		final String stored = CandidateCoverage.withFacts(plan, facts);
		check("read back in order", CandidateCoverage.factsOf(stored).equals(facts));
		check("the rest of the plan is untouched", stored.contains("\"clusteringWeights\":{\"a\":3}") && stored.contains("\"root\":\"N1\""));
		check("a new simulation replaces the facts", CandidateCoverage.factsOf(CandidateCoverage.withFacts(stored, Arrays.asList("simulation: 3"))).equals(Arrays.asList("simulation: 3")));
		check("no facts removes the key", !CandidateCoverage.withFacts(stored, new ArrayList<String>()).contains("simulationFacts"));
		check("an unreadable plan stores nothing", CandidateCoverage.withFacts("not json", facts) == null && CandidateCoverage.withFacts(null, facts) == null && CandidateCoverage.factsOf("not json").isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
