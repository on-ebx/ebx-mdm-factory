package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JUnit-free: run the main, a failure throws. Lot M17 (2026-10-18, docs/MATCHING.md section 69): an accepted object the facts mark as needing no plan (one source system, no key shared by
 * two of its rows) is worded as a measured fact - "No plan - one source only, no shared key (measured fact)" - never as a gap and never as silence, whatever the model did with it
 * (skipped it with that reason, with another one, said nothing, or ran out of budget on it).
 */
public final class MatchingPlanCoverageNoPlanTest
{
	public static void main(final String[] pArgs)
	{
		MatchingPlanCoverageNoPlanTest.theFactsAreReadFromTheStepDetail();
		MatchingPlanCoverageNoPlanTest.anObjectTheFactsMarkIsNeverAGapWhateverTheModelDid();
		MatchingPlanCoverageNoPlanTest.theWordingIsTheMeasuredFactInBothLanguages();
		MatchingPlanCoverageNoPlanTest.theJobMessageNamesThemAndNothingToDoStaysTruthful();
		System.out.println("MatchingPlanCoverageNoPlanTest OK");
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static final String DETAIL = "{\"status\":\"BUDGET\",\"noPlanNeeded\":{\"VisiteMagasin\":\"STORES, 48 rows\",\"PaysClient\":\"STORES, 30 rows\",\"SuiviClient\":\"STORES, 20 rows\"},"
		+ "\"notes\":[\"SuiviClient skipped: not worth a plan\"],\"attempts\":{\"PaysClient\":{\"count\":2,\"lastRefusal\":\"x\",\"endedBy\":\"\"}}}";
	private static final List<String> CANDIDATES = Arrays.asList("Client", "VisiteMagasin", "PaysClient", "SuiviClient");

	private static void theFactsAreReadFromTheStepDetail()
	{
		final Map<String, String> facts = MatchingPlanCoverage.noPlanNeeded(DETAIL);
		check("three entities, in order, with their source", facts.size() == 3 && "STORES, 48 rows".equals(facts.get("VisiteMagasin")) && "[VisiteMagasin, PaysClient, SuiviClient]".equals(facts.keySet().toString()));
		check("a result written before the lot has none", MatchingPlanCoverage.noPlanNeeded("{\"status\":\"DONE\"}").isEmpty() && MatchingPlanCoverage.noPlanNeeded("nope").isEmpty());
	}

	private static void anObjectTheFactsMarkIsNeverAGapWhateverTheModelDid()
	{
		final Set<String> withPlan = new HashSet<String>(Arrays.asList("Client"));
		final Map<String, String> facts = MatchingPlanCoverage.noPlanNeeded(DETAIL);
		// without the facts the three read as gaps: skipped with its own words, budget after refusals, silent
		final List<MatchingPlanCoverage.Gap> before = MatchingPlanCoverage.gaps(CANDIDATES, withPlan, true, MatchingPlanCoverage.modelNotes(DETAIL), MatchingPlanCoverage.modelRefused(DETAIL),
			MatchingPlanCoverage.modelAttempts(DETAIL));
		check("three gaps without the facts: " + MatchingPlanCoverage.names(before), before.size() == 3);
		final List<MatchingPlanCoverage.Gap> after = MatchingPlanCoverage.gaps(CANDIDATES, withPlan, true, MatchingPlanCoverage.modelNotes(DETAIL), MatchingPlanCoverage.modelRefused(DETAIL),
			MatchingPlanCoverage.modelAttempts(DETAIL), facts);
		check("no gap with them: " + MatchingPlanCoverage.names(after), after.isEmpty());
		final List<MatchingPlanCoverage.NoPlan> noPlans = MatchingPlanCoverage.noPlans(CANDIDATES, withPlan, facts);
		check("they are listed as no-plan objects, in the candidates' order", noPlans.size() == 3 && "VisiteMagasin".equals(noPlans.get(0).entity) && "STORES, 20 rows".equals(noPlans.get(2).source));
		// an object that has a plan anyway (the model proposed one) is neither
		final Set<String> proposed = new HashSet<String>(Arrays.asList("Client", "PaysClient"));
		check("a plan the model proposed anyway is just a plan", MatchingPlanCoverage.noPlans(CANDIDATES, proposed, facts).size() == 2
			&& !MatchingPlanCoverage.names(MatchingPlanCoverage.gaps(CANDIDATES, proposed, true, null, null, null, facts)).contains("PaysClient"));
		// an object NOT in the facts keeps its gap
		final List<MatchingPlanCoverage.Gap> others = MatchingPlanCoverage.gaps(Arrays.asList("Client", "Autre"), new HashSet<String>(), true, new ArrayList<String>(), null, null, facts);
		check("others keep their reading", others.size() == 2 && MatchingPlanCoverage.KIND_MODEL_SILENT.equals(others.get(1).kind));
	}

	private static void theWordingIsTheMeasuredFactInBothLanguages()
	{
		check("English", "No plan — one source only, no shared key (measured fact)".equals(MatchingPlanCoverage.noPlanReason(Locale.ENGLISH)));
		check("French: " + MatchingPlanCoverage.noPlanReason(Locale.FRENCH), "Pas de plan — une seule source, aucune clé partagée (fait mesuré)".equals(MatchingPlanCoverage.noPlanReason(Locale.FRENCH)));
		final List<MatchingPlanCoverage.NoPlan> one = Arrays.asList(new MatchingPlanCoverage.NoPlan("VisiteMagasin", "STORES, 48 rows"));
		check("the sentence", "VisiteMagasin: No plan — one source only, no shared key (measured fact).".equals(MatchingPlanCoverage.noPlanSentences(Locale.ENGLISH, one)));
		check("nothing to say, nothing added", "base".equals(MatchingPlanCoverage.withNoPlan(Locale.ENGLISH, "base", new ArrayList<MatchingPlanCoverage.NoPlan>())));
	}

	private static void theJobMessageNamesThemAndNothingToDoStaysTruthful()
	{
		final List<MatchingPlanCoverage.NoPlan> noPlans = Arrays.asList(new MatchingPlanCoverage.NoPlan("VisiteMagasin", "STORES, 48 rows"));
		final String withPlans = MatchingPlanCoverage.withNoPlan(Locale.ENGLISH, "1 plan.", noPlans);
		check("appended to the job's sentence: " + withPlans, withPlans.startsWith("1 plan. VisiteMagasin: No plan") && withPlans.endsWith("(measured fact)."));
		final String nothing = MatchingPlanCoverage.nothingToDo(Locale.ENGLISH, 2, new ArrayList<MatchingPlanCoverage.Gap>(), noPlans, true, true);
		check("with no other gap: every object that needs a plan has one, then the fact: " + nothing, nothing.startsWith(MatchingPlanCoverage.NOTHING_TO_DO)
			&& nothing.contains("Every accepted business object that needs a matching plan already has one.") && nothing.contains("VisiteMagasin: No plan") && !nothing.contains("already has a matching plan"));
		final String old = MatchingPlanCoverage.nothingToDo(Locale.ENGLISH, 2, new ArrayList<MatchingPlanCoverage.Gap>(), true, true);
		check("the old form is unchanged: " + old, old.contains("Every accepted business object already has a matching plan."));
		final List<MatchingPlanCoverage.Gap> gaps = Arrays.asList(new MatchingPlanCoverage.Gap("Autre", MatchingPlanCoverage.KIND_MODEL_SILENT, ""));
		final String mixed = MatchingPlanCoverage.nothingToDo(Locale.ENGLISH, 3, gaps, noPlans, true, true);
		check("a real gap is still the lead: " + mixed, mixed.contains("No matching plan was written.") && mixed.contains("Autre:") && mixed.contains("VisiteMagasin: No plan"));
	}
}
