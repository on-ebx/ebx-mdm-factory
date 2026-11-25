package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JUnit-free, like {@code TableRowCountTest}: run the main, a failure throws. UX lot U1 (2026-10-13): which accepted objects have no matching plan and why,
 * whether the language-model pass is chained to the rules, the "Nothing to do:" message. The scenario is the measured one (project UxCrmSf: Organisation,
 * Personne, Adresse accepted; the rules wrote nothing; the model wrote Personne and Adresse and skipped Organisation).
 */
public final class MatchingPlanCoverageTest
{
	public static void main(final String[] pArgs)
	{
		MatchingPlanCoverageTest.candidatesAreAcceptedObjectsThatAreNotMembers();
		MatchingPlanCoverageTest.rulesOnlyGapForEveryObjectWithoutPlan();
		MatchingPlanCoverageTest.modelSkipNoteWinsOnceTheModelRan();
		MatchingPlanCoverageTest.modelRefusalAndSilence();
		MatchingPlanCoverageTest.anObjectRefusedUntilTheBudgetOrTheBreakerEndedItNeverReadsAsNoReason();
		MatchingPlanCoverageTest.aRejectedPlanIsADecisionNotAGap();
		MatchingPlanCoverageTest.notesAndRefusalsAreReadFromTheStepDetail();
		MatchingPlanCoverageTest.theModelIsChainedOnlyWhenAllowedConfiguredAndSomethingIsMissing();
		MatchingPlanCoverageTest.nothingToDoStartsWithTheConventionAndNamesEveryObject();
		MatchingPlanCoverageTest.nothingToDoSaysWhenThereIsNothingToPropose();
		MatchingPlanCoverageTest.withGapsAppendsTheObjectsWithoutPlan();
		MatchingPlanCoverageTest.rulesRanFromAPlanOrAFinishedTask();
		MatchingPlanCoverageTest.anObjectThatGotTheFactorysFallbackPlanIsNamedWithWhyNeverAGap();
		MatchingPlanCoverageTest.aFallbackRefusedTooIsSaidNextToTheModelsLastRefusal();
		System.out.println("MatchingPlanCoverageTest OK");
	}

	private static WizardSnapshot snapshot()
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		for (final String name : new String[] { "Organisation", "Personne", "Adresse" })
		{
			final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
			entity.name = name;
			entity.status = "ACCEPTED";
			snapshot.stagingEntities.add(entity);
		}
		return snapshot;
	}

	private static WizardSnapshot.MatchPlanInfo plan(final String pEntity, final String pOrigin, final String pStatus)
	{
		final WizardSnapshot.MatchPlanInfo plan = new WizardSnapshot.MatchPlanInfo();
		plan.entityName = pEntity;
		plan.origin = pOrigin;
		plan.status = pStatus;
		return plan;
	}

	private static void candidatesAreAcceptedObjectsThatAreNotMembers()
	{
		final WizardSnapshot snapshot = MatchingPlanCoverageTest.snapshot();
		snapshot.stagingEntities.get(1).status = "PROPOSED";
		snapshot.stagingEntities.get(2).supersededBy = "Organisation";
		MatchingPlanCoverageTest.check("only the accepted, non-member object", Arrays.asList("Organisation").equals(MatchingPlanCoverage.candidates(snapshot)));
	}

	private static void rulesOnlyGapForEveryObjectWithoutPlan()
	{
		final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(Arrays.asList("Organisation", "Personne", "Adresse"), new HashSet<String>(), false, null, null);
		MatchingPlanCoverageTest.check("three gaps", gaps.size() == 3);
		MatchingPlanCoverageTest.check("rules reason", MatchingPlanCoverage.KIND_RULES.equals(gaps.get(0).kind));
		MatchingPlanCoverageTest.check("said in user words", MatchingPlanCoverage.reasonText(Locale.ENGLISH, gaps.get(0)).contains("check digit"));
		MatchingPlanCoverageTest.check("no internal name in the reason", !MatchingPlanCoverage.reasonText(Locale.ENGLISH, gaps.get(0)).contains("CHECK_DIGIT"));
	}

	// the measured case: the model wrote Personne and Adresse, said nothing visible about Organisation
	private static void modelSkipNoteWinsOnceTheModelRan()
	{
		final Set<String> withPlan = new HashSet<String>(Arrays.asList("Personne", "Adresse"));
		final List<String> notes = Arrays.asList("Organisation skipped: no field is filled well enough on both systems.", "2 plans proposed.");
		final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(Arrays.asList("Organisation", "Personne", "Adresse"), withPlan, true, notes, null);
		MatchingPlanCoverageTest.check("one gap", gaps.size() == 1 && "Organisation".equals(gaps.get(0).entity));
		MatchingPlanCoverageTest.check("skipped kind", MatchingPlanCoverage.KIND_MODEL_SKIPPED.equals(gaps.get(0).kind));
		MatchingPlanCoverageTest.check("the model's own text", "no field is filled well enough on both systems.".equals(gaps.get(0).detail));
		MatchingPlanCoverageTest.check("shown in the reason", MatchingPlanCoverage.reasonText(Locale.ENGLISH, gaps.get(0)).contains("no field is filled well enough"));
	}

	private static void modelRefusalAndSilence()
	{
		final Map<String, String> refused = new LinkedHashMap<String, String>();
		refused.put("Organisation", "the requirements are too long");
		final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(Arrays.asList("Organisation", "Personne"), new HashSet<String>(), true, new ArrayList<String>(), refused);
		MatchingPlanCoverageTest.check("refused kind", MatchingPlanCoverage.KIND_MODEL_REFUSED.equals(gaps.get(0).kind));
		MatchingPlanCoverageTest.check("refusal text kept", MatchingPlanCoverage.reasonText(Locale.ENGLISH, gaps.get(0)).contains("the requirements are too long"));
		MatchingPlanCoverageTest.check("silent kind", MatchingPlanCoverage.KIND_MODEL_SILENT.equals(gaps.get(1).kind));
	}

	/** Lot M5 (2026-10-18): live run 433, the wizard said "proposed nothing and gave no reason" for an object the model had proposed for ten times. */
	private static void anObjectRefusedUntilTheBudgetOrTheBreakerEndedItNeverReadsAsNoReason()
	{
		final String last = "'mobileE164' is not near-unique enough, among its filled values, for an exact, MATCH-terminal node";
		final String budgetDetail = "{\"status\":\"BUDGET\",\"notes\":[],\"attempts\":{\"Personne\":{\"count\":10,\"lastRefusal\":\"" + last + "\",\"endedBy\":\"\"}}}";
		final List<MatchingPlanCoverage.Gap> budget = MatchingPlanCoverage.gaps(Arrays.asList("Personne", "Vente"), new HashSet<String>(), true, MatchingPlanCoverage.modelNotes(budgetDetail),
			MatchingPlanCoverage.modelRefused(budgetDetail), MatchingPlanCoverage.modelAttempts(budgetDetail));
		MatchingPlanCoverageTest.check("budget kind with the attempts", MatchingPlanCoverage.KIND_MODEL_BUDGET.equals(budget.get(0).kind) && budget.get(0).attempts == 10);
		final String en = MatchingPlanCoverage.reasonText(Locale.ENGLISH, budget.get(0));
		MatchingPlanCoverageTest.check("says the budget, the count and the last refusal: " + en,
			en.contains("ended on the token budget after 10 attempt(s)") && en.contains(last) && !en.contains("no reason"));
		final String fr = MatchingPlanCoverage.reasonText(Locale.FRENCH, budget.get(0));
		MatchingPlanCoverageTest.check("and in French: " + fr, fr.contains("budget de jetons") && fr.contains("essais\u00A0: 10") && fr.contains(last) && !fr.contains("aucune raison"));
		MatchingPlanCoverageTest.check("an object never proposed for is still silent", MatchingPlanCoverage.KIND_MODEL_SILENT.equals(budget.get(1).kind));
		// the pass ended it (same refusal, cap, skip after refusals): refused, with the last refusal, whatever the run's status
		final String endedDetail = "{\"status\":\"BUDGET\",\"notes\":[\"Personne skipped: model prose that reads like a contradiction\"],\"attempts\":{\"Personne\":{\"count\":3,\"lastRefusal\":\"" + last
			+ "\",\"endedBy\":\"SKIPPED_AFTER_REFUSAL\"}}}";
		final List<MatchingPlanCoverage.Gap> ended = MatchingPlanCoverage.gaps(Arrays.asList("Personne"), new HashSet<String>(), true, MatchingPlanCoverage.modelNotes(endedDetail),
			MatchingPlanCoverage.modelRefused(endedDetail), MatchingPlanCoverage.modelAttempts(endedDetail));
		final String endedText = MatchingPlanCoverage.reasonText(Locale.ENGLISH, ended.get(0));
		MatchingPlanCoverageTest.check("refused kind, the Factory's refusal and not the model's prose: " + endedText, MatchingPlanCoverage.KIND_MODEL_REFUSED.equals(ended.get(0).kind)
			&& endedText.contains("3 proposal(s)") && endedText.contains(last) && !endedText.contains("contradiction"));
		// the run stopped by the model itself after refusals (status DONE): refused, not budget
		final List<MatchingPlanCoverage.Gap> done = MatchingPlanCoverage.gaps(Arrays.asList("Personne"), new HashSet<String>(), true, new ArrayList<String>(), null,
			MatchingPlanCoverage.modelAttempts("{\"status\":\"DONE\",\"attempts\":{\"Personne\":{\"count\":2,\"lastRefusal\":\"x\",\"endedBy\":\"\"}}}"));
		MatchingPlanCoverageTest.check("DONE after refusals: refused", MatchingPlanCoverage.KIND_MODEL_REFUSED.equals(done.get(0).kind));
		MatchingPlanCoverageTest.check("an unreadable detail has no attempts", MatchingPlanCoverage.modelAttempts("nope").byEntity.isEmpty() && !MatchingPlanCoverage.modelAttempts("nope").budgetEnded);
	}

	private static void aRejectedPlanIsADecisionNotAGap()
	{
		final WizardSnapshot snapshot = MatchingPlanCoverageTest.snapshot();
		snapshot.matchPlans.add(MatchingPlanCoverageTest.plan("Personne", "LLM", "REJECTED"));
		final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(MatchingPlanCoverage.candidates(snapshot), MatchingPlanCoverage.withPlan(snapshot), false, null, null);
		MatchingPlanCoverageTest.check("Personne is decided", !MatchingPlanCoverage.names(gaps).contains("Personne"));
		MatchingPlanCoverageTest.check("the two others are gaps", gaps.size() == 2);
	}

	private static void notesAndRefusalsAreReadFromTheStepDetail()
	{
		final String detail = "{\"notes\":[\"Organisation skipped: nothing distinctive\",\"summary\"],\"refused\":{\"Adresse\":\"too long\"},\"written\":[\"Personne\"]}";
		MatchingPlanCoverageTest.check("notes", MatchingPlanCoverage.modelNotes(detail).size() == 2);
		MatchingPlanCoverageTest.check("refused", "too long".equals(MatchingPlanCoverage.modelRefused(detail).get("Adresse")));
		MatchingPlanCoverageTest.check("unreadable detail is empty", MatchingPlanCoverage.modelNotes("not json").isEmpty() && MatchingPlanCoverage.modelRefused("").isEmpty());
		MatchingPlanCoverageTest.check("null detail is empty", MatchingPlanCoverage.modelNotes((String) null).isEmpty());
	}

	private static void theModelIsChainedOnlyWhenAllowedConfiguredAndSomethingIsMissing()
	{
		MatchingPlanCoverageTest.check("all conditions", MatchingPlanCoverage.chainModel(true, true, 3));
		MatchingPlanCoverageTest.check("not allowed", !MatchingPlanCoverage.chainModel(false, true, 3));
		MatchingPlanCoverageTest.check("no connector", !MatchingPlanCoverage.chainModel(true, false, 3));
		MatchingPlanCoverageTest.check("every object has a plan", !MatchingPlanCoverage.chainModel(true, true, 0));
	}

	private static void nothingToDoStartsWithTheConventionAndNamesEveryObject()
	{
		final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(Arrays.asList("Organisation", "Personne", "Adresse"), new HashSet<String>(), false, null, null);
		final String noModel = MatchingPlanCoverage.nothingToDo(Locale.ENGLISH, 3, gaps, false, false);
		MatchingPlanCoverageTest.check("convention prefix", noModel.startsWith("Nothing to do:"));
		MatchingPlanCoverageTest.check("every object named", noModel.contains("Organisation:") && noModel.contains("Personne:") && noModel.contains("Adresse:"));
		MatchingPlanCoverageTest.check("hint to connect a model", noModel.contains("Connect a language model"));
		final String askModel = MatchingPlanCoverage.nothingToDo(Locale.ENGLISH, 3, gaps, false, true);
		MatchingPlanCoverageTest.check("hint to ask the model", askModel.contains("Ask the language model"));
		final String tried = MatchingPlanCoverage.nothingToDo(Locale.ENGLISH, 3, gaps, true, true);
		MatchingPlanCoverageTest.check("no hint once the model took part", !tried.contains("Ask the language model") && !tried.contains("Connect a language model"));
		MatchingPlanCoverageTest.check("french keeps the convention prefix", MatchingPlanCoverage.nothingToDo(Locale.FRENCH, 3, gaps, false, false).startsWith("Nothing to do:"));
	}

	private static void nothingToDoSaysWhenThereIsNothingToPropose()
	{
		MatchingPlanCoverageTest.check("no accepted object", MatchingPlanCoverage.nothingToDo(Locale.ENGLISH, 0, new ArrayList<MatchingPlanCoverage.Gap>(), false, false)
			.contains("No business object is accepted"));
		MatchingPlanCoverageTest.check("everything covered", MatchingPlanCoverage.nothingToDo(Locale.ENGLISH, 3, new ArrayList<MatchingPlanCoverage.Gap>(), false, false)
			.contains("already has a matching plan"));
	}

	private static void withGapsAppendsTheObjectsWithoutPlan()
	{
		final List<MatchingPlanCoverage.Gap> gaps = Arrays.asList(new MatchingPlanCoverage.Gap("Organisation", MatchingPlanCoverage.KIND_MODEL_SKIPPED, "too sparse"));
		final String text = MatchingPlanCoverage.withGaps(Locale.ENGLISH, "2 matching plans proposed.", gaps);
		MatchingPlanCoverageTest.check("base kept", text.startsWith("2 matching plans proposed."));
		MatchingPlanCoverageTest.check("object and reason", text.contains("Organisation: ") && text.contains("too sparse"));
		MatchingPlanCoverageTest.check("unchanged without gap", "base".equals(MatchingPlanCoverage.withGaps(Locale.ENGLISH, "base", new ArrayList<MatchingPlanCoverage.Gap>())));
	}

	private static void rulesRanFromAPlanOrAFinishedTask()
	{
		final WizardSnapshot snapshot = MatchingPlanCoverageTest.snapshot();
		MatchingPlanCoverageTest.check("nothing ran", !MatchingPlanCoverage.rulesRan(snapshot, new ArrayList<RecentTasks.Task>()));
		final RecentTasks.Task task = new RecentTasks.Task();
		task.kind = "MATCHING_SUGGEST_RULES";
		task.status = "DONE";
		MatchingPlanCoverageTest.check("a finished rule proposal", MatchingPlanCoverage.rulesRan(snapshot, Arrays.asList(task)));
		task.status = "RUNNING";
		MatchingPlanCoverageTest.check("a running one does not count", !MatchingPlanCoverage.rulesRan(snapshot, Arrays.asList(task)));
		snapshot.matchPlans.add(MatchingPlanCoverageTest.plan("Personne", "RULE", "PROPOSED"));
		MatchingPlanCoverageTest.check("a plan of origin RULE", MatchingPlanCoverage.rulesRan(snapshot, null));
	}

	/** Lot M9 (2026-10-18): live run 497 - the person entity ended with no plan; with the fallback it has one, stored with origin RULE and requirements.fallback, and the message says so. */
	private static void anObjectThatGotTheFactorysFallbackPlanIsNamedWithWhyNeverAGap()
	{
		final String last = "the proposed decision tree is not valid: UNKNOWN_BRANCH_TARGET: node 'N5'.onFalse = 'N7' is neither a leaf nor a known node id";
		final String detail = "{\"status\":\"DONE\",\"notes\":[],\"attempts\":{},\"fallback\":{\"Client\":{\"proposed\":true,\"reason\":\"\",\"attempts\":5,\"lastRefusal\":\"" + last
			+ "\",\"endedBy\":\"SAME_REFUSAL\"},\"Achat\":{\"proposed\":false,\"reason\":\"no key facts\",\"attempts\":2,\"lastRefusal\":\"x\",\"endedBy\":\"\"}}}";
		final JsonNode result;
		try
		{
			result = new ObjectMapper().readTree(detail);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
		final List<MatchingPlanCoverage.FallbackInfo> fallbacks = MatchingPlanCoverage.fallbacks(result);
		MatchingPlanCoverageTest.check("only the proposed fallback is listed", fallbacks.size() == 1 && "Client".equals(fallbacks.get(0).entity) && fallbacks.get(0).attempts == 5
			&& fallbacks.get(0).lastRefusal.equals(last));
		// Client has a plan: never a gap
		final Set<String> withPlan = new HashSet<String>(Arrays.asList("Client", "PointVente"));
		final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(Arrays.asList("Client", "Achat", "PointVente"), withPlan, true, MatchingPlanCoverage.modelNotes(result),
			MatchingPlanCoverage.modelRefused(result), MatchingPlanCoverage.modelAttempts(result));
		MatchingPlanCoverageTest.check("Client is not a gap, Achat still is", MatchingPlanCoverage.names(gaps).equals(Arrays.asList("Achat")));
		final String en = MatchingPlanCoverage.withFallbacks(Locale.ENGLISH, "The language model proposed 3 matching plans.", fallbacks);
		MatchingPlanCoverageTest.check("the message names the object and why: " + en, en.equals("The language model proposed 3 matching plans. Client: the language model made 5 proposal(s) and none was "
			+ "accepted; the Factory proposed its fallback plan, built from the measured key facts, to review (last refusal: " + last + ")."));
		final String fr = MatchingPlanCoverage.withFallbacks(Locale.FRENCH, "Base.", fallbacks);
		MatchingPlanCoverageTest.check("and in French: " + fr, fr.contains("Client\u00A0: le mod\u00E8le de langage a propos\u00E9 un plan 5 fois") && fr.contains("plan de repli") && fr.contains(last));
		MatchingPlanCoverageTest.check("no fallback: the base is unchanged", MatchingPlanCoverage.withFallbacks(Locale.ENGLISH, "Base.", new ArrayList<MatchingPlanCoverage.FallbackInfo>()).equals("Base."));
		// the stored plan carries the marker; a RULE plan with it did not come from the rule pass
		final String requirements = "{\"decisionTree\":{\"root\":\"F1\",\"nodes\":[]},\"clusteringFields\":[],\"fallback\":{\"afterRefusedProposals\":5,\"lastRefusal\":\"" + last + "\",\"endedBy\":\"SAME_REFUSAL\"}}";
		final MatchingPlanCoverage.FallbackInfo marker = MatchingPlanCoverage.fallbackOf(requirements);
		MatchingPlanCoverageTest.check("the marker is read off the stored plan", marker != null && marker.attempts == 5 && marker.lastRefusal.equals(last));
		MatchingPlanCoverageTest.check("a model plan or plain text has none", MatchingPlanCoverage.fallbackOf("{\"decisionTree\":{}}") == null && MatchingPlanCoverage.fallbackOf("Same name.") == null
			&& MatchingPlanCoverage.fallbackOf(null) == null);
		final WizardSnapshot snapshot = MatchingPlanCoverageTest.snapshot();
		final WizardSnapshot.MatchPlanInfo fallbackPlan = MatchingPlanCoverageTest.plan("Personne", "RULE", "PROPOSED");
		fallbackPlan.requirements = requirements;
		snapshot.matchPlans.add(fallbackPlan);
		MatchingPlanCoverageTest.check("a fallback plan does not mean the rules ran", !MatchingPlanCoverage.rulesRan(snapshot, null));
		snapshot.matchPlans.add(MatchingPlanCoverageTest.plan("Adresse", "RULE", "PROPOSED"));
		MatchingPlanCoverageTest.check("a real rule plan does", MatchingPlanCoverage.rulesRan(snapshot, null));
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			for (final String key : new String[] { "wizard.step6.origin.RULE_FALLBACK", "wizard.step6.fallback.note", "wizard.matching.fallback.sentence", "wizard.matching.gap.reason.fallbackRefused" })
			{
				MatchingPlanCoverageTest.check(key + " resolves in " + locale, !WizardMessages.get(locale, key, "a", "b", "c").contains(key));
			}
		}
	}

	private static void aFallbackRefusedTooIsSaidNextToTheModelsLastRefusal()
	{
		final String last = "'mobileE164' is not near-unique enough, among its filled values, for an exact, MATCH-terminal node";
		final String detail = "{\"status\":\"DONE\",\"notes\":[],\"attempts\":{\"Personne\":{\"count\":4,\"lastRefusal\":\"" + last
			+ "\",\"endedBy\":\"SAME_REFUSAL\",\"fallback\":\"refused by the same checks as a model plan: 'mobileE164' is compared with the exact algorithm, but its dominant value SHAPE differs\"}}}";
		final List<MatchingPlanCoverage.Gap> gaps = MatchingPlanCoverage.gaps(Arrays.asList("Personne"), new HashSet<String>(), true, MatchingPlanCoverage.modelNotes(detail),
			MatchingPlanCoverage.modelRefused(detail), MatchingPlanCoverage.modelAttempts(detail));
		MatchingPlanCoverageTest.check("a gap, refused, with the fallback's refusal", gaps.size() == 1 && MatchingPlanCoverage.KIND_MODEL_REFUSED.equals(gaps.get(0).kind) && gaps.get(0).attempts == 4
			&& gaps.get(0).fallbackRefusal.startsWith("refused by the same checks"));
		final String en = MatchingPlanCoverage.reasonText(Locale.ENGLISH, gaps.get(0));
		MatchingPlanCoverageTest.check("the model's refusal first, the fallback's after: " + en, en.startsWith("The language model made 4 proposal(s) for this object and none was accepted. Last refusal: " + last)
			&& en.contains(" The Factory's fallback plan was refused too: refused by the same checks as a model plan: 'mobileE164' is compared"));
		final String fr = MatchingPlanCoverage.reasonText(Locale.FRENCH, gaps.get(0));
		MatchingPlanCoverageTest.check("and in French: " + fr, fr.contains("Le plan de repli de la fabrique a lui aussi \u00E9t\u00E9 refus\u00E9\u00A0: refused by the same checks"));
		// without a fallback refusal the text is lot M5's, unchanged
		final List<MatchingPlanCoverage.Gap> plain = MatchingPlanCoverage.gaps(Arrays.asList("Personne"), new HashSet<String>(), true, new ArrayList<String>(), null,
			MatchingPlanCoverage.modelAttempts("{\"attempts\":{\"Personne\":{\"count\":4,\"lastRefusal\":\"" + last + "\",\"endedBy\":\"SAME_REFUSAL\"}}}"));
		MatchingPlanCoverageTest.check("unchanged without a fallback", MatchingPlanCoverage.reasonText(Locale.ENGLISH, plain.get(0)).endsWith("Last refusal: " + last));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
