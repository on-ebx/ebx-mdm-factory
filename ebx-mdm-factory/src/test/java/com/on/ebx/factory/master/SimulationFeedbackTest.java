package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.matching.B2cKeyFixtureTest;

/**
 * JUnit-free: run the main, a failure throws. Lot F1 (2026-10-19, {@code docs/MATCHING.md} &sect;70): the numeric feedback of a simulation, measured on the 534 person records of the B2C master
 * scenario ({@code b2c_person_keys.tsv}, 565 true pairs, the measured key facts of the entity). The simulation is replayed here by an evaluator written independently of the class under test
 * (three hand-coded nodes over the keys, the candidates being the pairs that share an e-mail key or a phone), so the figures the class reports are checked against a count made another way.
 * Run with {@code -Dmdmf.print=true} to print the statements the model reads.
 */
public final class SimulationFeedbackTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int EMAIL = 2;
	private static final int PHONE = 4;
	private static final int ADDRESS = 6;

	public static void main(final String[] pArgs) throws Exception
	{
		SimulationFeedbackTest.aPlanWithoutMatchLeafLeavesTheSafePairsToAsteward();
		SimulationFeedbackTest.aMatchLeafOnTheSafePairMergesThem();
		SimulationFeedbackTest.theFigureOfEveryLeafComesFromTheEnginePath();
		SimulationFeedbackTest.pairsWithoutARecordedPathAreNotAttributedToALeaf();
		SimulationFeedbackTest.probedPairsAreCountedApart();
		SimulationFeedbackTest.aKeyComparedByAScoreIsNotCounted();
		SimulationFeedbackTest.theStatementsHoldCountsAndNeverAValue();
		SimulationFeedbackTest.possibleMissesAreNotMatchedPairsThatAgreeOnAKeySet();
		SimulationFeedbackTest.verdictNamesAreNormalised();
		SimulationFeedbackTest.theGate();
		SimulationFeedbackTest.theResultIsStoredAsCounts();
		SimulationFeedbackTest.nothingToMeasureIsEmptyAndHarmless();
		System.out.println("SimulationFeedbackTest OK");
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	// ------------------------------------------------------------------------------------------------------ fixture plans

	private static String exactNode(final String pId, final String pFields, final String pOnTrue, final String pOnFalse)
	{
		final StringBuilder fields = new StringBuilder();
		for (final String field : pFields.split(","))
		{
			fields.append(fields.length() == 0 ? "" : ",").append("{\"field\":\"").append(field).append("\",\"algorithm\":\"ebx-addon-mame@exact\",\"minimumScore\":100,\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}");
		}
		return "{\"id\":\"" + pId + "\",\"function\":\"ALL_FIELDS\",\"fields\":[" + fields + "],\"onTrue\":\"" + pOnTrue + "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	private static String addressNode(final String pId, final String pOnTrue, final String pOnFalse)
	{
		return "{\"id\":\"" + pId + "\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"addressKeys\",\"algorithm\":\"ebx-addon-mame@exactFullText\",\"minimumScore\":50,"
			+ "\"bothNull\":\"NOT_MATCH\",\"oneNull\":\"NOT_MATCH\"}],\"onTrue\":\"" + pOnTrue + "\",\"onFalse\":\"" + pOnFalse + "\"}";
	}

	private static ObjectNode plan(final String pRoot, final String pNodes)
	{
		try
		{
			final ObjectNode node = (ObjectNode) SimulationFeedbackTest.MAPPER.readTree("{\"decisionTree\":{\"root\":\"" + pRoot + "\",\"nodes\":[" + pNodes + "]},\"clusteringFields\":[\"emailKey\",\"mobileE164\"]}");
			node.set("keyFacts", B2cKeyFixtureTest.facts().toJson());
			return node;
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	/** Run-6 style: nothing ends in MATCH - the e-mail alone, the phone alone and the address keys end in a review. */
	private static ObjectNode reviewOnlyPlan()
	{
		return SimulationFeedbackTest.plan("N1", SimulationFeedbackTest.exactNode("N1", "emailKey", "SUSPECTED", "N2") + "," + SimulationFeedbackTest.exactNode("N2", "mobileE164", "SUSPECTED", "N3") + ","
			+ SimulationFeedbackTest.addressNode("N3", "SUSPECTED", "NOT_MATCHED"));
	}

	/** Run-5 style: e-mail AND phone together end in MATCH, then the same review paths. */
	private static ObjectNode matchPlan()
	{
		return SimulationFeedbackTest.plan("N0", SimulationFeedbackTest.exactNode("N0", "emailKey,mobileE164", "MATCH", "N1") + "," + SimulationFeedbackTest.exactNode("N1", "emailKey", "SUSPECTED", "N2") + ","
			+ SimulationFeedbackTest.exactNode("N2", "mobileE164", "SUSPECTED", "N3") + "," + SimulationFeedbackTest.addressNode("N3", "SUSPECTED", "NOT_MATCHED"));
	}

	// ------------------------------------------------------------------------------------------------------ the replayed simulation

	/** Two records of the fixture. */
	private static final class Candidate
	{
		final String[] a;
		final String[] b;

		Candidate(final String[] pA, final String[] pB)
		{
			this.a = pA;
			this.b = pB;
		}

		boolean agree(final int pCell)
		{
			return this.a[pCell] != null && this.a[pCell].equals(this.b[pCell]);
		}
	}

	/** Every pair of records sharing an e-mail key or a phone (what the add-on offers as candidates), plus a deterministic spread of pairs that share neither. */
	private static List<Candidate> candidates()
	{
		final List<String[]> records = B2cKeyFixtureTest.records();
		final List<Candidate> out = new ArrayList<Candidate>();
		for (int i = 0; i < records.size(); i++)
		{
			for (int j = i + 1; j < records.size(); j++)
			{
				final Candidate pair = new Candidate(records.get(i), records.get(j));
				if (pair.agree(SimulationFeedbackTest.EMAIL) || pair.agree(SimulationFeedbackTest.PHONE) || (i * 31 + j) % 997 == 0)
				{
					out.add(pair);
				}
			}
		}
		return out;
	}

	/** The verdict and the nodes visited for a pair under the review-only plan, hand-coded. Returns {verdict, id1, id2...}. */
	private static List<String> walkReviewOnly(final Candidate pPair)
	{
		final List<String> path = new ArrayList<String>();
		path.add("N1");
		if (pPair.agree(SimulationFeedbackTest.EMAIL))
		{
			return SimulationFeedbackTest.finish(path, "SUSPECTED");
		}
		path.add("N2");
		if (pPair.agree(SimulationFeedbackTest.PHONE))
		{
			return SimulationFeedbackTest.finish(path, "SUSPECTED");
		}
		path.add("N3");
		return SimulationFeedbackTest.finish(path, pPair.agree(SimulationFeedbackTest.ADDRESS) ? "SUSPECTED" : "NOT_MATCHED");
	}

	/** Same, under the plan with a MATCH leaf on e-mail + phone. */
	private static List<String> walkMatchPlan(final Candidate pPair)
	{
		if (pPair.agree(SimulationFeedbackTest.EMAIL) && pPair.agree(SimulationFeedbackTest.PHONE))
		{
			return SimulationFeedbackTest.finish(new ArrayList<String>(Arrays.asList("N0")), "MATCHED");
		}
		final List<String> path = new ArrayList<String>(Arrays.asList("N0"));
		final List<String> rest = SimulationFeedbackTest.walkReviewOnly(pPair);
		path.addAll(rest.subList(1, rest.size()));
		return SimulationFeedbackTest.finish(path, rest.get(0));
	}

	private static List<String> finish(final List<String> pPath, final String pVerdict)
	{
		final List<String> out = new ArrayList<String>();
		out.add(pVerdict);
		out.addAll(pPath);
		return out;
	}

	/** What the add-on names a node: the label the translator sends (every label of these plans is distinct). */
	private static String engineName(final String pNodeId, final ObjectNode pPlan)
	{
		final DecisionTreePlan.Plan plan = DecisionTreePlan.readFrom(pPlan);
		DecisionTreePlan.freezeAndDisambiguateLabels(plan);
		return DecisionTreePlan.labelFor(plan.byId(pNodeId));
	}

	private static Map<String, String> values(final String[] pRecord)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		final String[] names = { "emailKey", "mobileE164", "addressKeys" };
		final int[] cells = { SimulationFeedbackTest.EMAIL, SimulationFeedbackTest.PHONE, SimulationFeedbackTest.ADDRESS };
		for (int i = 0; i < names.length; i++)
		{
			if (pRecord[cells[i]] != null)
			{
				out.put(names[i], pRecord[cells[i]]);
			}
		}
		return out;
	}

	/** The simulation of {@code pCandidates} under {@code pPlan}: engine pairs with the recorded path of the first {@code pWithPath}, the others without. */
	private static List<SimulationFeedback.Pair> simulate(final List<Candidate> pCandidates, final ObjectNode pPlan, final boolean pMatchPlan, final int pWithPath)
	{
		final List<SimulationFeedback.Pair> out = new ArrayList<SimulationFeedback.Pair>();
		int index = 0;
		for (final Candidate candidate : pCandidates)
		{
			final List<String> walk = pMatchPlan ? SimulationFeedbackTest.walkMatchPlan(candidate) : SimulationFeedbackTest.walkReviewOnly(candidate);
			final List<String> names = new ArrayList<String>();
			for (final String id : walk.subList(1, walk.size()))
			{
				names.add(SimulationFeedbackTest.engineName(id, pPlan));
			}
			out.add(new SimulationFeedback.Pair(SimulationFeedback.ORIGIN_ENGINE, walk.get(0), SimulationFeedbackTest.values(candidate.a), SimulationFeedbackTest.values(candidate.b),
				index < pWithPath ? names : Collections.<String>emptyList()));
			index++;
		}
		return out;
	}

	private static SimulationFeedback.SetCount set(final SimulationFeedback.Result pResult, final String pFieldsText)
	{
		for (final SimulationFeedback.SetCount count : pResult.sets)
		{
			if (pFieldsText.equals(count.set.fieldsText()))
			{
				return count;
			}
		}
		throw new AssertionError("no key set " + pFieldsText + " in " + pResult.toJson());
	}

	// ------------------------------------------------------------------------------------------------------ tests

	/** Run 6: no MATCH leaf. The pairs that agree on e-mail AND phone (a pair of keys the facts mark SAFE) all wait for a steward, and the text says so in numbers. */
	private static void aPlanWithoutMatchLeafLeavesTheSafePairsToAsteward()
	{
		final ObjectNode plan = SimulationFeedbackTest.reviewOnlyPlan();
		final List<Candidate> candidates = SimulationFeedbackTest.candidates();
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, SimulationFeedbackTest.simulate(candidates, plan, false, Integer.MAX_VALUE), 20);

		int expectedSuspected = 0;
		int expectedBoth = 0;
		int expectedEmailOnly = 0;
		for (final Candidate candidate : candidates)
		{
			final boolean email = candidate.agree(SimulationFeedbackTest.EMAIL);
			final boolean phone = candidate.agree(SimulationFeedbackTest.PHONE);
			final boolean address = candidate.agree(SimulationFeedbackTest.ADDRESS);
			if (email || phone || address)
			{
				expectedSuspected++;
			}
			if (email && phone)
			{
				expectedBoth++;
			}
			if (email)
			{
				expectedEmailOnly++;
			}
		}
		check("the fixture has pairs that agree on e-mail and phone (the true pairs): " + expectedBoth, expectedBoth > 300);
		check("pairs evaluated", result.engine.pairs == candidates.size());
		check("no MATCH leaf, so no MATCH", result.engine.match == 0);
		check("SUSPECTED counted as the evaluator counts them: " + result.engine.suspected + " vs " + expectedSuspected, result.engine.suspected == expectedSuspected);
		check("the rest is NOT_MATCHED", result.engine.notMatched == candidates.size() - expectedSuspected && result.engine.notMatched > 0);

		final SimulationFeedback.SetCount safe = SimulationFeedbackTest.set(result, "emailKey + mobileE164");
		check("the SAFE pair of keys is a key set (both keys are used by the plan)", safe.set.safe && safe.set.countable);
		check("every pair that agrees on both waits for a steward: " + safe.engine.suspected + " of " + safe.engine.pairs, safe.engine.pairs == expectedBoth && safe.engine.suspected == expectedBoth
			&& safe.engine.match == 0 && safe.engine.notMatched == 0);
		final SimulationFeedback.SetCount email = SimulationFeedbackTest.set(result, "emailKey");
		check("e-mail alone: every pair that shares it is SUSPECTED here", email.engine.pairs == expectedEmailOnly && email.engine.suspected == expectedEmailOnly);
		check("distinct SUSPECTED pairs on a key set", result.suspectedOnKeys == expectedSuspected - SimulationFeedbackTest.suspectedOnlyByAddress(candidates));
		int expectedPhone = 0;
		for (final Candidate candidate : candidates)
		{
			if (candidate.agree(SimulationFeedbackTest.PHONE))
			{
				expectedPhone++;
			}
		}
		check("headline: the SAFE pair of keys first (" + expectedBoth + " pairs, the phone alone has " + expectedPhone + "): " + result.headlineSet().set.fieldsText(),
			expectedPhone > expectedBoth && "emailKey + mobileE164".equals(result.headlineSet().set.fieldsText()) && result.headlineSet().engine.suspected == expectedBoth);
		final List<String> facts = result.facts();
		final String consequence = "With this plan, " + expectedBoth + " of the " + expectedBoth + " pair(s) the simulation judged identical on emailKey + mobileE164 wait for a steward instead of merging.";
		boolean found = false;
		for (final String fact : facts)
		{
			found |= fact.contains(consequence);
		}
		check("the consequence is stated plainly: '" + consequence + "' in " + facts, found);
		check("the sample is told with its size", facts.get(0).startsWith("sample: 20 pivot(s) drawn at random across the source systems; the add-on evaluated " + candidates.size() + " distinct pair(s)"));
		if (Boolean.getBoolean("mdmf.print"))
		{
			System.out.println("---- review-only plan, " + candidates.size() + " pairs");
			for (int i = 0; i < facts.size(); i++)
			{
				System.out.println((i + 1) + ") " + facts.get(i));
			}
			System.out.println("summary: " + result.summaryLine());
			System.out.println("json: " + result.toJson());
		}
	}

	private static int suspectedOnlyByAddress(final List<Candidate> pCandidates)
	{
		int n = 0;
		for (final Candidate candidate : pCandidates)
		{
			if (!candidate.agree(SimulationFeedbackTest.EMAIL) && !candidate.agree(SimulationFeedbackTest.PHONE) && candidate.agree(SimulationFeedbackTest.ADDRESS))
			{
				n++;
			}
		}
		return n;
	}

	/** Run 5: e-mail + phone end in MATCH: the SAFE pair's pairs all merge, nothing of it waits for a steward, and the text says nothing about a wait for that set. */
	private static void aMatchLeafOnTheSafePairMergesThem()
	{
		final ObjectNode plan = SimulationFeedbackTest.matchPlan();
		final List<Candidate> candidates = SimulationFeedbackTest.candidates();
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, SimulationFeedbackTest.simulate(candidates, plan, true, Integer.MAX_VALUE), 20);
		int both = 0;
		for (final Candidate candidate : candidates)
		{
			if (candidate.agree(SimulationFeedbackTest.EMAIL) && candidate.agree(SimulationFeedbackTest.PHONE))
			{
				both++;
			}
		}
		final SimulationFeedback.SetCount safe = SimulationFeedbackTest.set(result, "emailKey + mobileE164");
		check("the pairs agreeing on both go to MATCH: " + safe.engine.match + " of " + both, safe.engine.pairs == both && safe.engine.match == both && safe.engine.suspected == 0);
		check("MATCH total = those pairs", result.engine.match == both);
		check("the set is told as the keys of a path ending in MATCH and SAFE", safe.set.pathOutcomes.contains("MATCH") && safe.set.safe && safe.set.why().contains("ending in MATCH")
			&& safe.set.why().contains("SAFE"));
		boolean waits = false;
		for (final String fact : result.facts())
		{
			waits |= fact.contains("pair(s) the simulation judged identical on emailKey + mobileE164 wait for a steward");
		}
		check("no 'wait for a steward' sentence for a set whose pairs all merge", !waits);
		check("the e-mail only and phone only sets carry the SUSPECTED ones", SimulationFeedbackTest.set(result, "emailKey").engine.suspected + SimulationFeedbackTest.set(result, "mobileE164").engine.suspected > 0);
	}

	/** The leaf counts come from the add-on's visit order of each pair, node names resolved through the stored plan's labels. */
	private static void theFigureOfEveryLeafComesFromTheEnginePath()
	{
		final ObjectNode plan = SimulationFeedbackTest.matchPlan();
		final List<Candidate> candidates = SimulationFeedbackTest.candidates();
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, SimulationFeedbackTest.simulate(candidates, plan, true, Integer.MAX_VALUE), 20);
		check("a path was resolved for every pair", result.engineWithLeaf == candidates.size());
		final Map<String, Integer> expected = new HashMap<String, Integer>();
		for (final Candidate candidate : candidates)
		{
			final List<String> walk = SimulationFeedbackTest.walkMatchPlan(candidate);
			final String last = walk.get(walk.size() - 1);
			final String outcome = "MATCHED".equals(walk.get(0)) ? "MATCH" : walk.get(0);
			final String key = last + "->" + outcome;
			expected.put(key, Integer.valueOf((expected.containsKey(key) ? expected.get(key).intValue() : 0) + 1));
		}
		int total = 0;
		for (final SimulationFeedback.LeafCount leaf : result.leaves)
		{
			total += leaf.pairs;
			if ("either".equals(leaf.branch))
			{
				continue;
			}
			final Integer want = expected.get(leaf.node + "->" + leaf.outcome);
			// two leaves of one node with the same outcome are reported as 'either'; a node with two different outcomes has one expected figure per leaf
			final int wanted = want == null ? 0 : want.intValue();
			check("leaf " + leaf.label() + " counted " + leaf.pairs + ", the evaluator counted " + wanted, leaf.pairs == wanted);
		}
		check("every pair sits under exactly one leaf: " + total, total == candidates.size());
		final StringBuilder labels = new StringBuilder();
		for (final SimulationFeedback.LeafCount leaf : result.leaves)
		{
			labels.append(leaf.label()).append(';');
		}
		check("the leaves are named by node and branch: " + labels, labels.toString().contains("N0 onTrue -> MATCH;") && labels.toString().contains("N1 onTrue -> SUSPECTED;")
			&& labels.toString().contains("N3 onFalse -> NOT_MATCHED;"));
	}

	/** The add-on's detail calls are bounded: a pair whose path was not recorded counts in the verdicts and the key sets, never under a leaf, and the text says for how many pairs the path is known. */
	private static void pairsWithoutARecordedPathAreNotAttributedToALeaf()
	{
		final ObjectNode plan = SimulationFeedbackTest.reviewOnlyPlan();
		final List<Candidate> candidates = SimulationFeedbackTest.candidates();
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, SimulationFeedbackTest.simulate(candidates, plan, false, 40), 20);
		int total = 0;
		for (final SimulationFeedback.LeafCount leaf : result.leaves)
		{
			total += leaf.pairs;
		}
		check("only the 40 pairs with a path are under a leaf", total == 40 && result.engineWithLeaf == 40);
		check("the verdicts count every pair", result.engine.pairs == candidates.size());
		boolean told = false;
		for (final String fact : result.facts())
		{
			told |= fact.startsWith("leaf by leaf (the add-on's visit order was recorded for 40 of the " + candidates.size() + " pair(s))");
		}
		check("the text says for how many pairs the path is known", told);
	}

	/** Pairs the runner chose (they share a key across systems) are not a sample: counted apart, never in the verdicts of the drawn pivots. */
	private static void probedPairsAreCountedApart()
	{
		final ObjectNode plan = SimulationFeedbackTest.reviewOnlyPlan();
		final List<Candidate> candidates = SimulationFeedbackTest.candidates();
		final List<SimulationFeedback.Pair> pairs = SimulationFeedbackTest.simulate(candidates.subList(0, 50), plan, false, 0);
		final List<SimulationFeedback.Pair> all = new ArrayList<SimulationFeedback.Pair>(pairs);
		int probed = 0;
		for (final Candidate candidate : candidates.subList(50, 70))
		{
			all.add(new SimulationFeedback.Pair(SimulationFeedback.ORIGIN_PROBE, "SUSPECTED", SimulationFeedbackTest.values(candidate.a), SimulationFeedbackTest.values(candidate.b), null));
			probed++;
		}
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, all, 20);
		check("the drawn sample is the engine pairs only", result.engine.pairs == 50 && result.probe.pairs == probed);
		check("the probed pairs are told apart", result.facts().toString().contains("the Factory probed " + probed + " more") && result.facts().toString().contains("probed pairs (chosen, not drawn): " + probed));
		final SimulationFeedback.Result engineOnly = SimulationFeedback.measure(plan, pairs, 20);
		check("the figures of the drawn sample do not move when probes are added", engineOnly.engine.suspected == result.engine.suspected && engineOnly.suspectedOnKeys == result.suspectedOnKeys);
	}

	/** addressKeys is compared with exactFullText: two records agree on it by a score, so no 'identical on' figure is made for it, and the text says why. */
	private static void aKeyComparedByAScoreIsNotCounted()
	{
		final ObjectNode plan = SimulationFeedbackTest.reviewOnlyPlan();
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, SimulationFeedbackTest.simulate(SimulationFeedbackTest.candidates(), plan, false, 0), 20);
		final SimulationFeedback.SetCount address = SimulationFeedbackTest.set(result, "addressKeys");
		check("the set exists but is not countable", !address.set.countable && address.engine.pairs == 0);
		check("it is named as not counted", result.uncountedSets.contains("addressKeys") && result.facts().toString().contains("not counted: addressKeys"));
	}

	/** Counts only: no record value of the fixture appears in any statement. */
	private static void theStatementsHoldCountsAndNeverAValue()
	{
		final ObjectNode plan = SimulationFeedbackTest.reviewOnlyPlan();
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, SimulationFeedbackTest.simulate(SimulationFeedbackTest.candidates(), plan, false, Integer.MAX_VALUE), 20);
		final String text = String.join("\n", result.facts()) + result.summaryLine() + result.toJson();
		for (final String[] record : B2cKeyFixtureTest.records())
		{
			for (int cell = 2; cell < record.length; cell++)
			{
				check("a value of the data leaked into the feedback: " + record[cell], record[cell] == null || record[cell].length() < 8 || !text.contains(record[cell]));
			}
		}
		check("nothing that looks like an address", !text.contains("@") && !text.contains("+33"));
	}

	/** NOT_MATCHED pairs that agree on every field of a key set are the possible misses: counted, and named by set. */
	private static void possibleMissesAreNotMatchedPairsThatAgreeOnAKeySet()
	{
		final ObjectNode plan = SimulationFeedbackTest.reviewOnlyPlan();
		final Map<String, String> same = new LinkedHashMap<String, String>();
		same.put("emailKey", "A@B.C");
		same.put("mobileE164", "+33100000000");
		final Map<String, String> otherPhone = new LinkedHashMap<String, String>();
		otherPhone.put("emailKey", "A@B.C");
		otherPhone.put("mobileE164", "+33199999999");
		final List<SimulationFeedback.Pair> pairs = new ArrayList<SimulationFeedback.Pair>();
		// the engine says NOT_MATCHED for a pair that shares an e-mail key (a contradiction node of another plan could do that)
		pairs.add(new SimulationFeedback.Pair(SimulationFeedback.ORIGIN_ENGINE, "NOT_MATCHED", same, same, null));
		pairs.add(new SimulationFeedback.Pair(SimulationFeedback.ORIGIN_ENGINE, "NOT_MATCHED", same, otherPhone, null));
		pairs.add(new SimulationFeedback.Pair(SimulationFeedback.ORIGIN_ENGINE, "SUSPECTED", same, same, null));
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, pairs, 3);
		check("two NOT_MATCHED pairs share a key set", result.notMatchedOnKeys == 2 && result.engine.notMatched == 2);
		check("per set: e-mail 2, e-mail + phone 1", SimulationFeedbackTest.set(result, "emailKey").engine.notMatched == 2 && SimulationFeedbackTest.set(result, "emailKey + mobileE164").engine.notMatched == 1);
		check("the sentence names the sets", result.facts().toString().contains("possible misses: 2 of the 2 NOT_MATCHED pair(s) agree on every field of at least one key set above"));
	}

	private static void verdictNamesAreNormalised()
	{
		check("MATCHED", SimulationFeedback.MATCH.equals(SimulationFeedback.normalizedVerdict("matched")) && SimulationFeedback.MATCH.equals(SimulationFeedback.normalizedVerdict("MATCH")));
		check("SUSPECT", SimulationFeedback.SUSPECTED.equals(SimulationFeedback.normalizedVerdict(" SUSPECT ")));
		check("NOT_MATCH", SimulationFeedback.NOT_MATCHED.equals(SimulationFeedback.normalizedVerdict("NOT_MATCH")));
		check("unknown", SimulationFeedback.normalizedVerdict(null).isEmpty() && SimulationFeedback.normalizedVerdict("x").isEmpty());
	}

	/** When the proposal job simulates by itself: under the bound (the module's scan budget unless the property says otherwise), within the time bound; otherwise why not. */
	private static void theGate()
	{
		check("the property wins", SimulationFeedback.maxRows("1000", 15000000L) == 1000L);
		check("unset: the scan budget", SimulationFeedback.maxRows(null, 15000000L) == 15000000L && SimulationFeedback.maxRows("  ", 5L) == 5L);
		check("not a number: the scan budget", SimulationFeedback.maxRows("many", 7L) == 7L);
		check("534 rows run", SimulationFeedback.gateRefusal(534L, 15000000L, 0L) == null);
		check("at the bound runs", SimulationFeedback.gateRefusal(15000000L, 15000000L, 0L) == null);
		check("above the bound: the reason names the property and the way out", SimulationFeedback.gateRefusal(20000000L, 15000000L, 0L).contains(SimulationFeedback.MAX_ROWS_PROPERTY)
			&& SimulationFeedback.gateRefusal(20000000L, 15000000L, 0L).contains("Simulate"));
		check("0 turns it off", SimulationFeedback.gateRefusal(1L, 0L, 0L).startsWith("disabled"));
		check("the time bound", SimulationFeedback.gateRefusal(10L, 100L, SimulationFeedback.MAX_PHASE_MILLIS + 1L).contains("time bound"));
	}

	/** The step detail gets counts: fields, SAFE flag, per-verdict figures, the leaves. */
	private static void theResultIsStoredAsCounts()
	{
		final ObjectNode plan = SimulationFeedbackTest.matchPlan();
		final SimulationFeedback.Result result = SimulationFeedback.measure(plan, SimulationFeedbackTest.simulate(SimulationFeedbackTest.candidates(), plan, true, Integer.MAX_VALUE), 20);
		final JsonNode json = result.toJson();
		check("keys", json.has("pivots") && json.has("engine") && json.has("probe") && json.has("suspectedOnKeys") && json.has("notMatchedOnKeys") && json.has("keySets") && json.has("leaves"));
		check("engine counts", json.path("engine").path("pairs").asInt() == result.engine.pairs && json.path("engine").path("match").asInt() == result.engine.match);
		boolean safe = false;
		for (final JsonNode set : json.path("keySets"))
		{
			safe |= set.path("safe").asBoolean() && "emailKey".equals(set.path("fields").get(0).asText());
		}
		check("the SAFE set is flagged", safe);
		check("the summary line carries the counts", result.summaryLine().startsWith("pivots=20 pairs=" + result.engine.pairs + " match=" + result.engine.match + " suspected=" + result.engine.suspected));
	}

	private static void nothingToMeasureIsEmptyAndHarmless()
	{
		final SimulationFeedback.Result empty = SimulationFeedback.measure(SimulationFeedbackTest.reviewOnlyPlan(), new ArrayList<SimulationFeedback.Pair>(), 0);
		check("empty", empty.isEmpty() && empty.headlineSet() == null);
		final SimulationFeedback.Result noTree = SimulationFeedback.measure(SimulationFeedbackTest.MAPPER.createObjectNode(), SimulationFeedbackTest.simulate(SimulationFeedbackTest.candidates().subList(0, 5),
			SimulationFeedbackTest.reviewOnlyPlan(), false, 0), 5);
		check("a plan without a tree: the verdicts are counted, no key set, no leaf", noTree.engine.pairs == 5 && noTree.sets.isEmpty() && noTree.leaves.isEmpty());
		check("and it still says something", !noTree.facts().isEmpty());
	}
}
